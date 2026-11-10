package com.repolens.app.ui

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.repolens.app.AppViewModel
import com.repolens.app.CrashReporter
import com.repolens.app.Location
import com.repolens.app.testing.MiniHttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Version, back while downloading, branch-aware links and refresh, recreation, big drawers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class AuditFixesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var vm: AppViewModel
    private var shown by mutableStateOf(true)

    @Before fun setUp() {
        File(app.filesDir, "repos").deleteRecursively()
        app.getSharedPreferences("repolens", 0).edit().clear().commit()
        CrashReporter.store(app).clear()
        installDemoRepo(app.filesDir)
    }

    private fun showApp(model: AppViewModel) {
        vm = model
        compose.setContent { RepoLensTheme { val ws = vm.workspace; if (ws == null) HomeScreen(vm) else if (shown) WorkspaceScreen(vm, ws) } }
    }

    private fun idleMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun waitUntil(what: String, timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) { idleMain(); Thread.sleep(10) }
        if (!cond()) throw AssertionError("timed out waiting for $what; error=${vm.error}")
        compose.waitForIdle()
    }

    private fun findCodeView(): CodeView? {
        var found: CodeView? = null
        fun walk(v: View) { if (v is CodeView) found = v else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(compose.activity.window.decorView)
        return found
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, t) in entries) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test fun versionIsBumpedSoCrashReportsTellBuildsApart() {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        assertEquals("1.1.1", info.versionName)
        @Suppress("DEPRECATION") assertEquals(3, info.versionCode)
    }

    @Test fun backOnHomeWhileDownloadingCancelsTheDownload() {
        MiniHttpServer().use { server ->
            server.bytes("/repos/o/r", """{"default_branch":"main"}""".toByteArray())
            server.slow("/o/r/zip/refs/heads/main", chunk = 64 * 1024, chunks = 100, delayMs = 50)
            val model = AppViewModel(app)
            model.downloader = server.downloader(File(app.filesDir, "repos"))
            showApp(model)
            vm.open("o/r")
            waitUntil("downloading") { vm.busyMessage?.startsWith("Downloading") == true }
            compose.activity.onBackPressedDispatcher.onBackPressed()
            compose.waitForIdle()
            assertNull("back cancels the download", vm.busyMessage)
            assertFalse("and doesn't leave the app", compose.activity.isFinishing)
            Thread.sleep(300); idleMain()
            assertNull(vm.workspace)
            assertNull(vm.error)
        }
    }

    @Test fun slashBranchLinkOpensTheLinkedFileAndRefreshKeepsTheBranch() {
        MiniHttpServer().use { server ->
            server.bytes("/o/r/zip/refs/heads/feature/x", zipOf("r-feature-x/README.md" to "# r\n", "r-feature-x/src/A.kt" to "fun a() = 1\n"))
            val model = AppViewModel(app)
            model.downloader = server.downloader(File(app.filesDir, "repos"))
            showApp(model)
            vm.open("https://github.com/o/r/blob/feature/x/src/A.kt")
            waitUntil("opened") { vm.workspace?.doc != null || vm.error != null }
            assertNull(vm.error)
            assertEquals("feature/x", vm.workspace!!.info.branch)
            assertEquals("src/A.kt", vm.workspace!!.doc!!.path)

            vm.closeRepo()
            compose.waitForIdle()
            val before = server.requests.size
            vm.refreshRecent(vm.recents.first { it.fullName == "o/r" })
            waitUntil("refreshed") { vm.workspace?.doc != null || vm.error != null }
            assertNull(vm.error)
            val made = server.requests.drop(before)
            assertTrue("$made", "/o/r/zip/refs/heads/feature/x" in made)
            assertTrue("no default-branch download: $made", made.none { it.startsWith("/repos/") || it.endsWith("/main") })
        }
    }

    /**
     * A font-scale or locale change recreates the activity, and with it the code view: the new
     * view must keep the reading position, not replay the last "go to definition" jump.
     */
    @Test fun recreatedCodeViewKeepsItsPositionAndDoesNotReplayNavigation() {
        showApp(AppViewModel(app))
        vm.open("demo/shop")
        waitUntil("open") { vm.workspace?.doc != null }
        val ws = vm.workspace!!
        val path = "src/main/kotlin/shop/Cart.kt"
        val big = File(app.filesDir, "repos/demo/shop/src/$path")
        big.writeText((1..3000).joinToString("\n") { "val v$it = $it" })
        vm.openFile(path, Location(path, 2500, 0, 1))
        waitUntil("Cart.kt") { ws.doc?.path == path }
        idleMain(); compose.waitForIdle()
        val cv = findCodeView()!!
        assertTrue("navigation applied: ${cv.firstVisibleLine()}", cv.firstVisibleLine() in 2400..2500)
        cv.scrollToTopLine(700)
        assertEquals(700, cv.firstVisibleLine())

        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()
        idleMain(); compose.waitForIdle()
        val cv2 = findCodeView()!!
        assertNotSame(cv, cv2)
        assertEquals("reading position kept", 700, cv2.firstVisibleLine())
    }

    @Test fun bigRepoDrawerAndFilterWorkOffTheMainThread() {
        val root = File(app.filesDir, "repos/big/tree")
        repeat(INLINE_PATHS + 200) { File(root, "src/d${it % 40}/f$it.txt").apply { parentFile!!.mkdirs(); writeText("x") } }
        File(root, "meta.properties").writeText("branch=main\n")
        showApp(AppViewModel(app))
        vm.open("big/tree")
        waitUntil("open") { vm.workspace?.doc != null }
        compose.onNodeWithContentDescription("Files").performClick()
        compose.waitUntil(20_000) { runCatching { compose.onAllNodesWithText("d0").onFirst().fetchSemanticsNode() }.isSuccess }
        compose.onNodeWithTag("fileFilter").performTextInput("f4321.txt")
        compose.waitUntil(20_000) { runCatching { compose.onAllNodesWithText("f4321.txt").onFirst().fetchSemanticsNode() }.isSuccess }
    }
}
