package com.repolens.app.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** owner/repo plus an optional branch/tag/commit and a file path to open first. */
data class RepoRef(val owner: String, val repo: String, val ref: String? = null, val path: String? = null) {
    val fullName get() = "$owner/$repo"

    companion object {
        private const val NAME_PART = "^[A-Za-z0-9_.-]+$"
        private const val LINK = """(?:https?://)?(?:www\.|m\.)?github\.com[/:][^\s"'<>]+"""
        private const val LINK_PREFIX = """^(?:https?://)?(?:www\.|m\.)?github\.com[/:]"""
        internal const val DEFAULT_BRANCH = """"default_branch"\s*:\s*"([^"]+)""""
        /** Every regex source used here, for the ICU compatibility test. */
        internal val allPatterns = listOf(NAME_PART, LINK, LINK_PREFIX, DEFAULT_BRANCH)

        private val namePart = Regex(NAME_PART)
        private val link = Regex(LINK)
        private val linkPrefix = Regex(LINK_PREFIX)

        private fun validName(s: String) = s.length in 1..100 && namePart.matches(s) && s != "." && s != ".."

        /**
         * Accepts anything a person is likely to paste:
         * `https://github.com/o/r`, `github.com/o/r.git`, `o/r`, `git@github.com:o/r.git`,
         * `.../tree/branch`, `.../blob/branch/path/File.kt`, or a sentence containing such a link.
         */
        fun parse(input: String): RepoRef? {
            var s = input.trim()
            if (s.length > 4000) return null
            // A shared link often comes with text around it.
            link.find(s)?.let { s = it.value }
            s = s.removePrefix("git@")
            s = s.replace(linkPrefix, "")
            s = s.substringBefore('?').substringBefore('#').trim('/')
            val parts = s.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            val owner = parts[0]
            val repo = parts[1].removeSuffix(".git")
            if (!validName(owner) || !validName(repo)) return null
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

/** Caps against zip bombs and absurd archives. */
class UnzipLimits(
    val maxEntries: Int = 250_000,
    val maxTotalBytes: Long = 3L * 1024 * 1024 * 1024,
    val maxEntryBytes: Long = 1L * 1024 * 1024 * 1024,
)

/**
 * Downloads a public repository as one zip archive (a single request, so the 60-requests/hour
 * unauthenticated GitHub API limit is not an issue) and unpacks it into app storage.
 *
 * A repository is installed atomically: it is unpacked into a staging folder together with its
 * `meta.properties`, and only then renamed into place, so a crash or cancellation half way can
 * never leave something that looks like a valid copy.
 */
class RepoDownloader(
    private val baseDir: File,
    private val apiBase: String = "https://api.github.com",
    private val codeloadBase: String = "https://codeload.github.com",
    private val webBase: String = "https://github.com",
    private val unzipLimits: UnzipLimits = UnzipLimits(),
    /** Most bytes downloaded for one archive. */
    private val maxDownloadBytes: Long = 2L * 1024 * 1024 * 1024,
) {
    /** GitHub names are case-insensitive, so `Jigar18/Stratos` and `jigar18/stratos` share a copy. */
    fun repoDir(ref: RepoRef) = File(baseDir, "${ref.owner.lowercase(Locale.ROOT)}/${ref.repo.lowercase(Locale.ROOT)}")
    /** Where versions before 1.1 kept a copy (exact case). */
    private fun legacyDir(ref: RepoRef) = File(baseDir, "${ref.owner}/${ref.repo}")
    private fun key(ref: RepoRef) = "${ref.owner}_${ref.repo}".lowercase(Locale.ROOT)
    private fun tmpZip(ref: RepoRef) = File(baseDir, "${key(ref)}.zip.part")
    private fun staging(ref: RepoRef) = File(baseDir, "${key(ref)}.staging")

    /** A complete copy downloaded earlier, if any. Does file I/O: call off the main thread. */
    fun cached(ref: RepoRef): RepoInfo? {
        for (dir in listOf(repoDir(ref), legacyDir(ref)).distinctBy { it.path }) {
            val meta = File(dir, META)
            val src = File(dir, SRC)
            if (!meta.isFile || !src.isDirectory) continue
            val p = try { Properties().apply { meta.inputStream().use { load(it) } } } catch (e: Exception) { continue }
            val branch = p.getProperty("branch")?.takeIf { it.isNotBlank() } ?: continue
            if (ref.ref != null && ref.ref != branch && !branch.startsWith(ref.ref + "/")) continue
            return RepoInfo(ref.copy(ref = branch), branch, src, p.getProperty("downloadedAt")?.toLongOrNull() ?: 0, p.getProperty("bytes")?.toLongOrNull() ?: 0)
        }
        return null
    }

    fun delete(ref: RepoRef) {
        repoDir(ref).deleteRecursively()
        legacyDir(ref).deleteRecursively()
        tmpZip(ref).delete()
        staging(ref).deleteRecursively()
    }

    /**
     * Removes what an interrupted download (e.g. the process was killed) left behind. Only old
     * leftovers: a download that started a moment ago (a share intent at launch) must not lose
     * its files; each download also clears its own leftovers before it starts.
     */
    fun sweepLeftovers(olderThanMs: Long = 60 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        baseDir.listFiles()?.forEach { f ->
            if ((f.name.endsWith(".zip.part") || f.name.endsWith(".staging")) && now - f.lastModified() > olderThanMs) f.deleteRecursively()
        }
    }

    suspend fun download(ref: RepoRef, onProgress: (String) -> Unit): RepoInfo = withContext(Dispatchers.IO) {
        onProgress("Looking up ${ref.fullName}…")
        baseDir.mkdirs()
        val tmpZip = tmpZip(ref)
        val staging = staging(ref)
        try {
            tmpZip.delete()
            staging.deleteRecursively()
            if (baseDir.usableSpace in 1 until MIN_FREE_BYTES) throw RepoException("Not enough free storage on this phone to download a repository.")
            val candidates = archiveCandidates(ref)
            var branch: String? = null
            var lastStatus = 0
            for ((b, url) in candidates) {
                ensureActive()
                val status = fetch(url, tmpZip, { isActive }) { bytes -> onProgress("Downloading… ${formatBytes(bytes)}") }
                if (status == 200) { branch = b; break }
                lastStatus = status
            }
            if (branch == null) {
                throw RepoException(
                    when (lastStatus) {
                        404 -> "Couldn't find ${ref.fullName}${ref.ref?.let { " @ $it" } ?: ""}. Check the URL — private repositories aren't supported."
                        403, 429 -> "GitHub is rate-limiting this network right now. Try again in a few minutes."
                        else -> "Download failed (HTTP $lastStatus)."
                    },
                )
            }
            ensureActive()
            onProgress("Unpacking…")
            val size = tmpZip.length()
            val stagedSrc = File(staging, SRC)
            val count = try {
                unzipStrippingTopFolder(tmpZip, stagedSrc, unzipLimits, { isActive }) { n -> if (n % 200 == 0) onProgress("Unpacking… $n files") }
            } catch (e: ZipException) {
                throw RepoException("The download isn't a valid zip archive (${e.message ?: "corrupt"}). Try again.")
            }
            ensureActive()
            if (count == 0) throw RepoException("The downloaded archive contains no files.")
            tmpZip.delete()
            val now = System.currentTimeMillis()
            Properties().apply {
                setProperty("branch", branch); setProperty("downloadedAt", now.toString()); setProperty("bytes", size.toString())
                File(staging, META).outputStream().use { store(it, null) }
            }
            val target = repoDir(ref)
            target.deleteRecursively()
            legacyDir(ref).takeIf { it.path != target.path }?.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!staging.renameTo(target)) {
                // different file system: copy the files first and the meta last, so a half copy is never "valid"
                target.mkdirs()
                stagedSrc.copyRecursively(File(target, SRC), overwrite = true)
                File(staging, META).copyTo(File(target, META), overwrite = true)
            }
            RepoInfo(ref.copy(ref = branch), branch, File(target, SRC), now, size)
        } catch (e: IOException) {
            if (e is RepoException) throw e
            if (isOutOfSpace(e)) throw RepoException("Not enough free storage on this phone to unpack ${ref.fullName}.")
            throw RepoException("Couldn't save the repository: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            tmpZip.delete()
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun isOutOfSpace(e: IOException) = e.message?.let { "ENOSPC" in it || "No space left" in it } == true

    /** (branch label, archive url) pairs to try in order. */
    private fun archiveCandidates(ref: RepoRef): List<Pair<String, String>> {
        val base = "$codeloadBase/${ref.owner}/${ref.repo}/zip"
        if (ref.ref == null) {
            val default = defaultBranch(ref)
            val list = ArrayList<Pair<String, String>>()
            if (default != null) list.add(default to "$base/refs/heads/${enc(default)}")
            list.add("HEAD" to "$webBase/${ref.owner}/${ref.repo}/archive/HEAD.zip")
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

    private fun defaultBranch(ref: RepoRef): String? {
        val c = try { open("$apiBase/repos/${ref.owner}/${ref.repo}") } catch (e: IOException) { throw RepoException("No connection to GitHub: ${e.message ?: e.javaClass.simpleName}") }
        return try {
            c.setRequestProperty("Accept", "application/vnd.github+json")
            when (c.responseCode) {
                200 -> {
                    val body = c.inputStream.use { it.readNBytesCompat(1 shl 20) }.toString(Charsets.UTF_8)
                    Regex(RepoRef.DEFAULT_BRANCH).find(body)?.groupValues?.get(1)
                }
                404 -> throw RepoException("Couldn't find ${ref.fullName}. Check the URL — private repositories aren't supported.")
                else -> null // rate limited etc.: fall back to HEAD.zip
            }
        } catch (e: RepoException) {
            throw e
        } catch (e: IOException) {
            throw RepoException("No connection to GitHub: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "RepoLens-Android")
        }

    private fun fetch(url: String, out: File, active: () -> Boolean, onBytes: (Long) -> Unit): Int {
        val c = try { open(url) } catch (e: IOException) { throw RepoException("No connection to GitHub: ${e.message}") }
        try {
            val code = c.responseCode
            if (code != 200) return code
            val expected = c.contentLengthLong
            var total = 0L
            var lastReport = 0L
            c.inputStream.use { input ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (!active()) throw CancellationException("Download cancelled")
                        val r = input.read(buf)
                        if (r < 0) break
                        fos.write(buf, 0, r)
                        total += r
                        if (total > maxDownloadBytes) throw RepoException("This repository is too large to download (over ${formatBytes(maxDownloadBytes)}).")
                        if (total - lastReport > 256 * 1024) { lastReport = total; onBytes(total) }
                    }
                }
            }
            if (expected >= 0 && total != expected) throw RepoException("Download interrupted (got ${formatBytes(total)} of ${formatBytes(expected)}). Try again.")
            onBytes(total)
            return 200
        } catch (e: RepoException) {
            throw e
        } catch (e: IOException) {
            if (isOutOfSpace(e)) throw RepoException("Not enough free storage on this phone to download this repository.")
            throw RepoException("Download interrupted: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val META = "meta.properties"
        private const val SRC = "src"
        private const val MIN_FREE_BYTES = 20L * 1024 * 1024

        fun formatBytes(b: Long): String = when {
            b >= 1024L * 1024 * 1024 -> "%.1f GB".format(Locale.US, b / (1024.0 * 1024 * 1024))
            b >= 1024L * 1024 -> "%.1f MB".format(Locale.US, b / (1024.0 * 1024))
            b >= 1024 -> "%d KB".format(Locale.US, b / 1024)
            else -> "$b B"
        }

        private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < max) {
                val r = read(buf, 0, minOf(buf.size, max - out.size()))
                if (r < 0) break
                out.write(buf, 0, r)
            }
            return out.toByteArray()
        }

        /**
         * A safe relative path for a zip entry name, or null if it must be skipped: `..` segments,
         * NUL characters, names longer than the file system allows, or nothing left.
         */
        internal fun safeSegments(name: String): List<String>? {
            val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty()) return null
            for (p in parts) {
                if (p == ".." || p.indexOf('\u0000') >= 0 || p.toByteArray(Charsets.UTF_8).size > 255) return null
            }
            if (parts.sumOf { it.length + 1 } > 3000) return null
            return parts
        }

        /**
         * Unzips [zip] into [dest]. GitHub archives have a single top-level folder (`repo-branch/`)
         * which is dropped; an archive without one is unpacked as is. Entries that can't be written
         * (unsafe names, a file where a folder is needed, …) are skipped. Throws [RepoException] when
         * a cap in [limits] is exceeded. Returns the number of files written.
         */
        fun unzipStrippingTopFolder(
            zip: File,
            dest: File,
            limits: UnzipLimits = UnzipLimits(),
            active: () -> Boolean = { true },
            onFile: (Int) -> Unit = {},
        ): Int {
            dest.mkdirs()
            val destPath = dest.canonicalPath + File.separator
            var count = 0
            var written = 0L
            ZipFile(zip).use { zf ->
                val entries = ArrayList<Pair<java.util.zip.ZipEntry, List<String>>>()
                val all = zf.entries()
                while (all.hasMoreElements()) {
                    val e = all.nextElement()
                    if (entries.size >= limits.maxEntries) throw RepoException("This archive has more than ${limits.maxEntries} entries — too many to unpack on a phone.")
                    val seg = safeSegments(e.name) ?: continue
                    entries.add(e to seg)
                }
                val top = entries.firstOrNull()?.second?.first()
                val strip = top != null && entries.all { (e, s) -> s[0] == top && (s.size > 1 || e.isDirectory) }
                val buf = ByteArray(64 * 1024)
                for ((e, seg) in entries) {
                    if (!active()) throw CancellationException("Unpacking cancelled")
                    val parts = if (strip) seg.drop(1) else seg
                    if (parts.isEmpty()) continue
                    val f = File(dest, parts.joinToString(File.separator))
                    if (!f.canonicalPath.startsWith(destPath)) continue // zip-slip guard (belt and braces)
                    if (e.isDirectory) {
                        if (!f.isDirectory) f.mkdirs()
                        continue
                    }
                    val parent = f.parentFile ?: continue
                    if (!parent.isDirectory && !parent.mkdirs()) continue // a file is in the way of this folder
                    if (f.isDirectory) continue // a folder is in the way of this file
                    var entryBytes = 0L
                    try {
                        zf.getInputStream(e).use { input ->
                            FileOutputStream(f).use { out ->
                                while (true) {
                                    val r = input.read(buf)
                                    if (r < 0) break
                                    entryBytes += r
                                    written += r
                                    if (entryBytes > limits.maxEntryBytes) throw RepoException("${parts.last()} is too large to unpack (over ${formatBytes(limits.maxEntryBytes)}).")
                                    if (written > limits.maxTotalBytes) throw RepoException("This repository is too large to unpack on a phone (over ${formatBytes(limits.maxTotalBytes)}).")
                                    out.write(buf, 0, r)
                                }
                            }
                        }
                    } catch (x: java.io.FileNotFoundException) {
                        f.delete() // e.g. a name the file system refuses: skip it
                        continue
                    }
                    onFile(++count)
                }
            }
            return count
        }
    }
}
