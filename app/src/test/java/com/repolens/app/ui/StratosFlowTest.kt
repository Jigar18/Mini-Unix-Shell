package com.repolens.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.repolens.app.AppViewModel
import com.repolens.app.CrashReporter
import com.repolens.app.core.TokenType
import com.repolens.app.testing.MiniHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import java.io.File

/**
 * The user's real repository (github.com/Jigar18/Stratos, 82 files, fixture made with
 * `git archive --prefix=Stratos-main/`) driven through the whole app, from the URL exactly as the
 * user pasted it: download (local server), unzip, index, open + draw every file, tap words,
 * references, symbol search, file drawer, close and reopen from the recents list.
 *
 * One subclass per SDK (lowest supported, a middle one, the newest): each runs in its own JVM
 * (`forkEvery = 1`) because Robolectric's native graphics per SDK is memory hungry.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class StratosFlowBase {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: AppViewModel
    private lateinit var server: MiniHttpServer
    private val tag get() = javaClass.simpleName

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "repos").deleteRecursively()
        CrashReporter.store(app).clear()
        app.getSharedPreferences("repolens", 0).edit().clear().commit()
        server = MiniHttpServer()
        server.githubRepo("Jigar18", "Stratos", MiniHttpServer.stratosZip())
        vm = AppViewModel(app)
        vm.downloader = server.downloader(File(app.filesDir, "repos"))
        compose.setContent {
            RepoLensTheme {
                val ws = vm.workspace
                if (ws == null) HomeScreen(vm) else WorkspaceScreen(vm, ws)
            }
        }
    }

    @After fun tearDown() { server.close() }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!cond() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        if (!cond()) throw AssertionError("timed out waiting for $what; error=${vm.error} busy=${vm.busyMessage} doc=${vm.workspace?.doc?.path} indexErr=${vm.workspace?.indexError}")
        compose.waitForIdle()
    }

    private fun findCodeView(): CodeView? {
        var found: CodeView? = null
        fun walk(v: View) { if (v is CodeView) found = v else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(compose.activity.window.decorView)
        return found
    }

    private fun drawOffscreen(v: View) {
        if (v.width == 0 || v.height == 0) return
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        bmp.recycle()
    }

    private fun tap(view: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        compose.waitForIdle()
    }

    private fun usedHeapAfterGc(): Long {
        repeat(3) { System.gc(); Thread.sleep(20) }
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test fun wholeRepoFlow() {
        // exactly what the user pasted
        vm.input = "https://github.com/Jigar18/Stratos.git"
        vm.open()
        waitFor("workspace") { vm.workspace?.doc != null && vm.workspace?.index != null }
        val ws = vm.workspace!!
        assertNull(ws.indexError)
        assertEquals("README.md", ws.doc!!.path)
        assertTrue("downloaded through the API + codeload", server.requests.contains("/Jigar18/Stratos/zip/refs/heads/main"))
        assertEquals(82 - 7, ws.paths.size) // .idea/ is skipped
        assertEquals(44 + 2 + 1, ws.index!!.files.size) // .java, .jsx and vite.config.js
        compose.onRoot().captureRoboImage("build/screenshots/stratos_${tag}_01_readme.png")

        val heapAfterFirst = usedHeapAfterGc()
        val openedDocs = ArrayList<java.lang.ref.WeakReference<Any>>()
        for (path in ws.paths) {
            vm.openFile(path, null)
            waitFor("open $path") { ws.doc?.path == path }
            val doc = ws.doc!!
            openedDocs.add(java.lang.ref.WeakReference(doc))
            val lines = doc.lines
            assertNotNull("$path should be shown as text: ${doc.note}", lines)
            val cv = findCodeView()
            assertNotNull("code view for $path", cv)
            drawOffscreen(cv!!)
            // tap the first identifier on each of the first lines that are on screen
            for (ln in 0 until minOf(lines!!.size, 12)) {
                val l = lines[ln]
                val t = (0 until l.tokenCount).firstOrNull { TokenType.isIdentifier(l.type(it)) } ?: continue
                val p = cv.pointFor(ln, l.start(t))
                if (p.y < 0 || p.y > cv.height || p.x < 0 || p.x > cv.width) continue
                tap(cv, p.x, p.y)
                drawOffscreen(cv)
            }
            if (ws.lenses.isNotEmpty()) {
                vm.onLensTapped(ws.lenses.keys.first())
                waitFor("refs sheet in $path") { ws.sheet?.loading == false }
                ws.sheet = null
            }
            vm.clearSelection()
            compose.waitForIdle()
        }
        val heapAfterAll = usedHeapAfterGc()
        val stillAlive = openedDocs.count { it.get() != null }
        println("STRATOS $tag heap after first file ${heapAfterFirst / 1048576} MB, after all ${ws.paths.size} files ${heapAfterAll / 1048576} MB, docs still reachable: $stillAlive")
        // Each opened document must be released when the next one replaces it. (The raw heap does
        // grow in this test, but that is Robolectric keeping every dismissed dialog and native
        // theme object; the app itself only keeps the current document.)
        assertTrue("$stillAlive of ${openedDocs.size} opened documents are still in memory", stillAlive <= 1)

        // references of a class used across files, as the user would open them
        vm.openFile("auth_service/src/main/java/com/stratos/auth_service/model/User.java", null)
        waitFor("User.java + lenses") { ws.doc?.path?.endsWith("model/User.java") == true && ws.lenses.isNotEmpty() }
        vm.onLensTapped(ws.lenses.keys.min())
        waitFor("User references") { ws.sheet?.loading == false }
        assertTrue("User is used in other files", ws.sheet!!.folders.isNotEmpty())
        compose.waitForIdle()
        ws.sheet = null

        // symbol search
        compose.onNodeWithContentDescription("Search symbols").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("symbolQuery").performTextInput("Github")
        compose.mainClock.advanceTimeBy(500)
        compose.waitUntil(10_000) { runCatching { compose.onAllNodesWithText("GithubClient").onFirst().fetchSemanticsNode() }.isSuccess }
        compose.onAllNodesWithText("GithubClient").onFirst().performClick()
        waitFor("GithubClient.java") { ws.doc?.path?.endsWith("GithubClient.java") == true }

        // file drawer: it scrolls to the open file (client/GithubClient.java) with the folders
        // visited so far expanded; open a file a few rows below it
        compose.onNodeWithContentDescription("Files").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/stratos_${tag}_02_drawer.png")
        compose.onAllNodesWithText("SecurityConfig.java").onFirst().performClick()
        waitFor("SecurityConfig.java from the drawer") { ws.doc?.path?.endsWith("config/SecurityConfig.java") == true }

        // close and reopen from the recents list (the second launch in the user's report)
        vm.closeRepo()
        waitFor("home") { vm.workspace == null }
        val before = server.requests.size
        compose.onNodeWithText("Jigar18/Stratos").performClick()
        waitFor("reopened") { vm.workspace?.doc != null && vm.workspace?.index != null }
        assertNull(vm.error)
        assertEquals("reopening uses the cached copy, no download", before, server.requests.size)
        compose.onRoot().captureRoboImage("build/screenshots/stratos_${tag}_03_reopened.png")
        assertNull("nothing reached the crash handler", CrashReporter.store(RuntimeEnvironment.getApplication()).pending())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w393dp-h852dp-xxhdpi")
class StratosFlowSdk26Test : StratosFlowBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w393dp-h852dp-xxhdpi")
class StratosFlowSdk30Test : StratosFlowBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class StratosFlowSdk35Test : StratosFlowBase()
