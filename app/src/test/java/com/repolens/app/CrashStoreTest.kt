package com.repolens.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CrashStoreTest {
    @Test fun handlerWritesReportAndChainsToThePreviousHandler() {
        val dir = Files.createTempDirectory("crash").toFile()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var chained: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> chained = e }
        try {
            val store = CrashStore(dir)
            store.breadcrumb = "Opening Jigar18/Stratos"
            val logged = ArrayList<String>()
            store.installHandler(linkedMapOf("App" to "com.repolens.app 1.0.0 (1)", "Device" to "Pixel Test", "Android" to "14 (API 34)")) { logged.add(it) }
            val boom = IllegalStateException("boom")
            val t = Thread({ throw boom }, "indexer")
            t.start(); t.join()

            assertSame("the previous handler still runs (Android ends the process there)", boom, chained)
            val report = store.pending()!!
            assertTrue(report, report.startsWith("RepoLens crash report"))
            for (part in listOf("App: com.repolens.app 1.0.0 (1)", "Device: Pixel Test", "Android: 14 (API 34)", "Thread: indexer", "Doing: Opening Jigar18/Stratos", "java.lang.IllegalStateException: boom", "CrashStoreTest")) {
                assertTrue("missing '$part' in\n$report", report.contains(part))
            }
            assertTrue(Regex("""Time: \d{4}-\d\d-\d\d \d\d:\d\d:\d\d UTC""").containsMatchIn(report))
            assertEquals(listOf(report), logged)
            assertEquals("Jigar18/Stratos", store.crashedRepo(report))

            store.clear()
            assertNull(store.pending())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
            dir.deleteRecursively()
        }
    }

    @Test fun crashedRepoNeedsABreadcrumb() {
        val store = CrashStore(Files.createTempDirectory("crash").toFile())
        assertNull(store.crashedRepo("RepoLens crash report\njava.lang.RuntimeException: x"))
        assertEquals("a/b", store.crashedRepo("Doing: Browsing a/b, file src/x/y.kt"))
        assertNull(store.crashedRepo(null))
    }
}
