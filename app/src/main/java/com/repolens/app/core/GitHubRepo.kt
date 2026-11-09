package com.repolens.app.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Properties
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** owner/repo plus an optional branch/tag/commit and a file path to open first. */
data class RepoRef(val owner: String, val repo: String, val ref: String? = null, val path: String? = null) {
    val fullName get() = "$owner/$repo"

    companion object {
        private val namePart = Regex("^[A-Za-z0-9_.-]+$")

        /**
         * Accepts anything a person is likely to paste:
         * `https://github.com/o/r`, `github.com/o/r.git`, `o/r`, `git@github.com:o/r.git`,
         * `.../tree/branch`, `.../blob/branch/path/File.kt`, or a sentence containing such a link.
         */
        fun parse(input: String): RepoRef? {
            var s = input.trim()
            // A shared link often comes with text around it.
            Regex("""(?:https?://)?(?:www\.|m\.)?github\.com[/:][^\s"'<>]+""").find(s)?.let { s = it.value }
            s = s.removePrefix("git@")
            s = s.replace(Regex("^(?:https?://)?(?:www\\.|m\\.)?github\\.com[/:]"), "")
            s = s.substringBefore('?').substringBefore('#').trim('/')
            val parts = s.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            val owner = parts[0]
            val repo = parts[1].removeSuffix(".git")
            if (!namePart.matches(owner) || !namePart.matches(repo) || repo == "." || repo == "..") return null
            if (parts.size >= 4 && (parts[2] == "tree" || parts[2] == "blob")) {
                val rest = parts.drop(3)
                return RepoRef(owner, repo, rest.first(), rest.drop(1).joinToString("/").ifEmpty { null })
            }
            return RepoRef(owner, repo)
        }
    }
}

class RepoInfo(val ref: RepoRef, val branch: String, val dir: File, val downloadedAt: Long, val bytes: Long)

class RepoException(message: String) : IOException(message)

/**
 * Downloads a public repository as one zip archive (a single request, so the 60-requests/hour
 * unauthenticated GitHub API limit is not an issue) and unpacks it into app storage.
 */
class RepoDownloader(private val baseDir: File) {

    fun repoDir(ref: RepoRef) = File(baseDir, "${ref.owner}/${ref.repo}")
    private fun srcDir(ref: RepoRef) = File(repoDir(ref), "src")
    private fun metaFile(ref: RepoRef) = File(repoDir(ref), "meta.properties")

    /** A copy downloaded earlier, if any. */
    fun cached(ref: RepoRef): RepoInfo? {
        val meta = metaFile(ref)
        val src = srcDir(ref)
        if (!meta.isFile || !src.isDirectory) return null
        val p = Properties().apply { meta.inputStream().use { load(it) } }
        val branch = p.getProperty("branch") ?: return null
        if (ref.ref != null && ref.ref != branch && !branch.startsWith(ref.ref + "/") ) return null
        return RepoInfo(ref.copy(ref = branch), branch, src, p.getProperty("downloadedAt")?.toLongOrNull() ?: 0, p.getProperty("bytes")?.toLongOrNull() ?: 0)
    }

    fun delete(ref: RepoRef) { repoDir(ref).deleteRecursively() }

    suspend fun download(ref: RepoRef, onProgress: (String) -> Unit): RepoInfo = withContext(Dispatchers.IO) {
        onProgress("Looking up ${ref.fullName}…")
        val candidates = archiveCandidates(ref)
        val tmpZip = File(baseDir, "${ref.owner}_${ref.repo}.zip.part")
        baseDir.mkdirs()
        var branch: String? = null
        var lastStatus = 0
        for ((b, url) in candidates) {
            ensureActive()
            val status = fetch(url, tmpZip) { bytes -> onProgress("Downloading… ${formatBytes(bytes)}") }
            if (status == 200) { branch = b; break }
            lastStatus = status
        }
        if (branch == null) {
            tmpZip.delete()
            throw RepoException(
                when (lastStatus) {
                    404 -> "Couldn't find ${ref.fullName}${ref.ref?.let { " @ $it" } ?: ""}. Check the URL — private repositories aren't supported."
                    403, 429 -> "GitHub is rate-limiting this network right now. Try again in a few minutes."
                    else -> "Download failed (HTTP $lastStatus)."
                },
            )
        }

        onProgress("Unpacking…")
        val target = repoDir(ref)
        val staging = File(baseDir, "${ref.owner}_${ref.repo}.staging")
        staging.deleteRecursively()
        val size = tmpZip.length()
        try {
            unzipStrippingTopFolder(tmpZip, staging) { n -> if (n % 200 == 0) onProgress("Unpacking… $n files") }
        } finally {
            tmpZip.delete()
        }
        target.deleteRecursively()
        target.mkdirs()
        if (!staging.renameTo(srcDir(ref))) {
            staging.copyRecursively(srcDir(ref), overwrite = true); staging.deleteRecursively()
        }
        val now = System.currentTimeMillis()
        Properties().apply {
            setProperty("branch", branch); setProperty("downloadedAt", now.toString()); setProperty("bytes", size.toString())
            metaFile(ref).outputStream().use { store(it, null) }
        }
        RepoInfo(ref.copy(ref = branch), branch, srcDir(ref), now, size)
    }

    /** (branch label, archive url) pairs to try in order. */
    private fun archiveCandidates(ref: RepoRef): List<Pair<String, String>> {
        val base = "https://codeload.github.com/${ref.owner}/${ref.repo}/zip"
        if (ref.ref == null) {
            val default = defaultBranch(ref)
            val list = ArrayList<Pair<String, String>>()
            if (default != null) list.add(default to "$base/refs/heads/${enc(default)}")
            list.add("HEAD" to "https://github.com/${ref.owner}/${ref.repo}/archive/HEAD.zip")
            if (default != "main") list.add("main" to "$base/refs/heads/main")
            if (default != "master") list.add("master" to "$base/refs/heads/master")
            return list
        }
        // A /tree/ URL can't tell "feature/x" (branch) from "feature" + folder "x"; try short to long.
        val segments = (ref.ref + (ref.path?.let { "/$it" } ?: "")).split('/')
        val list = ArrayList<Pair<String, String>>()
        for (n in 1..minOf(segments.size, 4)) {
            val r = segments.take(n).joinToString("/")
            list.add(r to "$base/refs/heads/${enc(r)}")
            list.add(r to "$base/refs/tags/${enc(r)}")
            if (n == 1) list.add(r to "$base/${enc(r)}") // commit sha
        }
        return list
    }

    private fun enc(ref: String) = ref.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun defaultBranch(ref: RepoRef): String? = try {
        val c = open("https://api.github.com/repos/${ref.owner}/${ref.repo}")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        when (c.responseCode) {
            200 -> Regex("\"default_branch\"\\s*:\\s*\"([^\"]+)\"").find(c.inputStream.bufferedReader().use { it.readText() })?.groupValues?.get(1)
            404 -> throw RepoException("Couldn't find ${ref.fullName}. Check the URL — private repositories aren't supported.")
            else -> null // rate limited etc.: fall back to HEAD.zip
        }
    } catch (e: RepoException) {
        throw e
    } catch (e: IOException) {
        throw RepoException("No connection to GitHub: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "RepoLens-Android")
        }

    private fun fetch(url: String, out: File, onBytes: (Long) -> Unit): Int {
        val c = try { open(url) } catch (e: IOException) { throw RepoException("No connection to GitHub: ${e.message}") }
        try {
            val code = c.responseCode
            if (code != 200) return code
            var total = 0L
            var lastReport = 0L
            c.inputStream.use { input ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        fos.write(buf, 0, r)
                        total += r
                        if (total - lastReport > 256 * 1024) { lastReport = total; onBytes(total) }
                    }
                }
            }
            onBytes(total)
            return 200
        } catch (e: IOException) {
            throw RepoException("Download interrupted: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            c.disconnect()
        }
    }

    companion object {
        fun formatBytes(b: Long): String = when {
            b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
            b >= 1024L * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
            b >= 1024 -> "%d KB".format(b / 1024)
            else -> "$b B"
        }

        /** Unzips [zip] into [dest], dropping the archive's single top-level folder (repo-branch/). */
        fun unzipStrippingTopFolder(zip: File, dest: File, onFile: (Int) -> Unit = {}) {
            dest.mkdirs()
            val destPath = dest.canonicalPath + File.separator
            var count = 0
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val name = e.name.replace('\\', '/')
                    val slash = name.indexOf('/')
                    val rel = if (slash >= 0) name.substring(slash + 1) else name
                    if (rel.isEmpty()) continue
                    val f = File(dest, rel)
                    if (!f.canonicalPath.startsWith(destPath)) continue // zip-slip guard
                    if (e.isDirectory) { f.mkdirs(); continue }
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { zin.copyTo(it, 64 * 1024) }
                    onFile(++count)
                }
            }
        }
    }
}
