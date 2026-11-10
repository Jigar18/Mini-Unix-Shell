package com.repolens.app.core

import com.repolens.app.testing.MiniHttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun zipOf(vararg entries: Pair<String, String?>): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
        for ((name, text) in entries) {
            z.putNextEntry(ZipEntry(name))
            text?.let { z.write(it.toByteArray()) }
            z.closeEntry()
        }
    }
    return bos.toByteArray()
}

class UnzipHardeningTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("unzip").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun unzip(bytes: ByteArray, limits: UnzipLimits = UnzipLimits()): Pair<File, Int> {
        val zip = File(dir, "r.zip").apply { writeBytes(bytes) }
        val out = File(dir, "out")
        return out to RepoDownloader.unzipStrippingTopFolder(zip, out, limits)
    }

    @Test fun zipSlipAndOddNamesAreSkipped() {
        val (out, n) = unzip(zipOf(
            "repo-main/" to null,
            "repo-main/src/A.kt" to "fun a() = 1",
            "repo-main/../../evil.txt" to "x",
            "repo-main/src/../../../evil2.txt" to "x",
            "repo-main/a\\..\\..\\evil3.txt" to "x",
            "repo-main/./src/./B.kt" to "fun b() = 2",
            "repo-main/" + "n".repeat(300) + ".txt" to "too long for the file system",
            "repo-main//double//slash.txt" to "ok",
        ))
        assertEquals("fun a() = 1", File(out, "src/A.kt").readText())
        assertEquals("fun b() = 2", File(out, "src/B.kt").readText())
        assertEquals("ok", File(out, "double/slash.txt").readText())
        assertTrue(dir.walkTopDown().none { it.name.startsWith("evil") })
        assertEquals(3, n)
    }

    @Test fun symlinkLikeEntriesAreJustSmallFiles() {
        // GitHub stores a symlink as an entry whose content is the target path; it must never
        // become a link (or be followed) on the phone.
        val (out, _) = unzip(zipOf("repo-main/link" to "../../../../etc/passwd", "repo-main/real.txt" to "real"))
        val link = File(out, "link")
        assertTrue(link.isFile)
        assertEquals("../../../../etc/passwd", link.readText())
        assertFalse(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun archiveWithoutSingleTopFolderIsKeptAsIs() {
        val (out, n) = unzip(zipOf("README.md" to "hi", "src/Main.kt" to "fun main() {}"))
        assertEquals(2, n)
        assertEquals("hi", File(out, "README.md").readText())
        assertTrue(File(out, "src/Main.kt").isFile)
        val (out2, _) = unzip(zipOf("only.txt" to "single file"))
        assertEquals("single file", File(out2, "only.txt").readText())
    }

    @Test fun fileAndFolderConflictsDontAbort() {
        val (out, n) = unzip(zipOf(
            "r/a" to "a file",
            "r/a/b.txt" to "needs a folder named a",
            "r/c/" to null,
            "r/c" to "a file where a folder is",
            "r/d.txt" to "fine",
            "r/D.txt" to "case variant",
        ))
        assertEquals("a file", File(out, "a").readText())
        assertTrue(File(out, "c").isDirectory)
        assertEquals("fine", File(out, "d.txt").readText())
        assertEquals(3, n) // a, d.txt, D.txt (or D.txt overwrote d.txt on a case-insensitive disk)
    }

    @Test fun capsStopZipBombs() {
        val big = "x".repeat(200_000)
        try {
            unzip(zipOf("r/a.txt" to big, "r/b.txt" to big), UnzipLimits(maxTotalBytes = 300_000))
            fail("total size cap")
        } catch (e: RepoException) { assertTrue(e.message!!, e.message!!.contains("too large")) }
        try {
            unzip(zipOf("r/a.txt" to big), UnzipLimits(maxEntryBytes = 100_000))
            fail("entry size cap")
        } catch (e: RepoException) { assertTrue(e.message!!, e.message!!.contains("too large")) }
        try {
            unzip(zipOf(*Array(20) { "r/f$it.txt" to "x" }), UnzipLimits(maxEntries = 10))
            fail("entry count cap")
        } catch (e: RepoException) { assertTrue(e.message!!, e.message!!.contains("entries")) }
    }

    @Test fun emptyAndNonZipInput() {
        val (_, n) = unzip(zipOf())
        assertEquals(0, n)
        try {
            unzip("<html>rate limited</html>".toByteArray())
            fail("not a zip")
        } catch (e: java.util.zip.ZipException) {
            // RepoDownloader.download turns this into a readable RepoException
        }
    }
}

class DownloaderTest {
    private lateinit var dir: File
    private lateinit var server: MiniHttpServer

    @Before fun setUp() {
        dir = Files.createTempDirectory("dl").toFile()
        server = MiniHttpServer()
    }

    @After fun tearDown() { server.close(); dir.deleteRecursively() }

    private fun downloader() = server.downloader(File(dir, "repos"))
    private fun leftovers() = File(dir, "repos").listFiles()?.filter { it.name.endsWith(".part") || it.name.endsWith(".staging") }.orEmpty()

    private fun expectFailure(ref: RepoRef, contains: String) = runBlocking {
        try {
            downloader().download(ref) {}
            fail("expected failure containing '$contains'")
        } catch (e: RepoException) {
            assertTrue(e.message!!, e.message!!.contains(contains))
        }
        assertNull("no half-valid cache", downloader().cached(ref))
        assertEquals(emptyList<File>(), leftovers())
    }

    @Test fun downloadsTheUsersRepoAndCachesItCaseInsensitively() = runBlocking {
        server.githubRepo("Jigar18", "Stratos", MiniHttpServer.stratosZip())
        val ref = RepoRef.parse("https://github.com/Jigar18/Stratos.git")!!
        val progress = ArrayList<String>()
        val info = downloader().download(ref) { progress.add(it) }
        assertEquals("main", info.branch)
        assertTrue(File(info.dir, "README.md").isFile)
        assertEquals(82, info.dir.walkTopDown().count { it.isFile })
        assertTrue(progress.any { it.startsWith("Unpacking") })
        assertEquals(emptyList<File>(), leftovers())
        // the same repository typed in another case uses the same copy
        assertNotNull(downloader().cached(RepoRef("jigar18", "stratos")))
        assertEquals(info.dir, downloader().cached(ref)!!.dir)
        downloader().delete(RepoRef("JIGAR18", "STRATOS"))
        assertNull(downloader().cached(ref))
    }

    @Test fun legacyExactCaseCacheIsStillUsed() {
        val legacy = File(dir, "repos/Jigar18/Stratos")
        File(legacy, "src").mkdirs()
        File(legacy, "src/a.txt").writeText("x")
        File(legacy, "meta.properties").writeText("branch=main\n")
        assertEquals(File(legacy, "src"), downloader().cached(RepoRef("Jigar18", "Stratos"))!!.dir)
        // src without meta (killed mid-install by 1.0.0) is not a valid copy
        File(legacy, "meta.properties").delete()
        assertNull(downloader().cached(RepoRef("Jigar18", "Stratos")))
    }

    @Test fun notFound() = expectFailure(RepoRef("nobody", "nothing"), "Couldn't find nobody/nothing")

    @Test fun rateLimitedFallsBackThenReportsIt() {
        server.status("/repos/o/r", 403)
        server.status("/o/r/archive/HEAD.zip", 403)
        server.status("/o/r/zip/refs/heads/main", 403)
        server.status("/o/r/zip/refs/heads/master", 403)
        expectFailure(RepoRef("o", "r"), "rate-limiting")
        assertTrue(server.requests.contains("/o/r/archive/HEAD.zip"))
    }

    @Test fun followsRedirects() = runBlocking {
        server.status("/repos/o/r", 403) // API rate limited: falls back to archive/HEAD.zip
        server.redirect("/o/r/archive/HEAD.zip", "${server.base}/codeload/o/r/zip/HEAD")
        server.bytes("/codeload/o/r/zip/HEAD", zipOf("r-main/x.kt" to "fun x() = 1"))
        val info = downloader().download(RepoRef("o", "r")) {}
        assertEquals("HEAD", info.branch)
        assertEquals("fun x() = 1", File(info.dir, "x.kt").readText())
    }

    @Test fun truncatedBodyIsAnErrorNotAHalfRepo() {
        server.githubRepo("o", "r", ByteArray(0))
        val zip = zipOf("r-main/a.kt" to "fun a() = 1".repeat(1000))
        server.truncated("/o/r/zip/refs/heads/main", zip.copyOf(zip.size / 2), zip.size.toLong())
        expectFailure(RepoRef("o", "r"), "interrupted")
    }

    @Test fun htmlInsteadOfZipIsReadable() {
        server.githubRepo("o", "r", "<html>Too many requests</html>".toByteArray())
        expectFailure(RepoRef("o", "r"), "isn't a valid zip")
    }

    @Test fun emptyArchive() {
        server.githubRepo("o", "r", zipOf("r-main/" to null))
        expectFailure(RepoRef("o", "r"), "contains no files")
    }

    @Test fun cancellingMidDownloadLeavesNothingBehind() = runBlocking {
        server.bytes("/repos/o/r", """{"default_branch":"main"}""".toByteArray())
        server.slow("/o/r/zip/refs/heads/main", chunk = 64 * 1024, chunks = 200, delayMs = 20)
        val job = async { downloader().download(RepoRef("o", "r")) {} }
        while ((partFile("o_r")?.length() ?: 0) < 128 * 1024) delay(10)
        job.cancel()
        try { job.await(); fail("cancelled") } catch (e: kotlinx.coroutines.CancellationException) {}
        assertEquals(emptyList<File>(), leftovers())
        assertNull(downloader().cached(RepoRef("o", "r")))
    }

    private fun partFile(key: String) = File(dir, "repos").listFiles()?.firstOrNull { it.name.startsWith("$key~") && it.name.endsWith(".zip.part") }

    /**
     * Cancel, then open the same repository again while the cancelled download's thread is still
     * blocked in a read: when that thread finally cleans up it must not delete the files of the
     * download that replaced it (both used to be `o_r.zip.part`).
     */
    @Test fun aCancelledDownloadDoesNotDeleteTheFilesOfItsReplacement() = runBlocking {
        server.bytes("/repos/o/r", """{"default_branch":"main"}""".toByteArray())
        server.slow("/o/r/zip/refs/heads/main", chunk = 64 * 1024, chunks = 20, delayMs = 700)
        val first = async(kotlinx.coroutines.Dispatchers.IO) { downloader().download(RepoRef("o", "r")) {} }
        while (partFile("o_r") == null) delay(10)
        first.cancel() // its thread stays blocked in read() for up to 700 ms
        val zip = zipOf("r-main/a.kt" to "fun a() = 1\n".repeat(20_000))
        server.slowBytes("/o/r/zip/refs/heads/main", zip, pieces = 10, delayMs = 150)
        val info = downloader().download(RepoRef("o", "r")) {}
        assertTrue(File(info.dir, "a.kt").isFile)
        try { first.await() } catch (e: kotlinx.coroutines.CancellationException) {}
        assertEquals(emptyList<File>(), leftovers())
        assertNotNull(downloader().cached(RepoRef("o", "r")))
    }

    @Test fun deleteRemovesTheTemporaryFilesOfThatRepoOnly() {
        File(dir, "repos/o_r~abc.staging/src").mkdirs()
        File(dir, "repos/o_r~abc.zip.part").writeText("partial")
        File(dir, "repos/o_rx~abc.zip.part").writeText("another repo")
        downloader().delete(RepoRef("O", "R"))
        assertEquals(listOf("o_rx~abc.zip.part"), leftovers().map { it.name })
    }

    /** GitHub: the API answers for an empty repository, but there is no archive of it. */
    @Test fun emptyRepositoryIsReportedAsEmpty() {
        server.bytes("/repos/o/r", """{"default_branch":"main"}""".toByteArray())
        expectFailure(RepoRef("o", "r"), "This repository is empty")
    }

    @Test fun sweepRemovesLeftoversOfAKilledProcess() {
        File(dir, "repos/x_y.staging/src").mkdirs()
        File(dir, "repos/x_y.zip.part").writeText("partial")
        File(dir, "repos/fresh.zip.part").writeText("a download that is running right now")
        File(dir, "repos/x_y.staging").setLastModified(0)
        File(dir, "repos/x_y.zip.part").setLastModified(0)
        downloader().sweepLeftovers()
        assertEquals(listOf("fresh.zip.part"), leftovers().map { it.name })
    }

    @Test fun repoNamesCantEscapeTheCache() {
        assertNull(RepoRef.parse("github.com/../etc"))
        assertNull(RepoRef.parse("github.com/a/.."))
        assertNull(RepoRef.parse("github.com/./x"))
        assertNull(RepoRef.parse("github.com/a%2F..%2F/b"))
        assertEquals(RepoRef("o", ".github"), RepoRef.parse("https://github.com/o/.github"))
    }
}
