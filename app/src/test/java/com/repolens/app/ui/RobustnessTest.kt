package com.repolens.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.repolens.app.AppViewModel
import com.repolens.app.CrashReporter
import com.repolens.app.MainActivity
import com.repolens.app.core.IndexLimits
import com.repolens.app.core.Languages
import com.repolens.app.core.LexedLine
import com.repolens.app.core.Lexer
import com.repolens.app.core.SourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private fun idleMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

private fun waitUntil(what: String, timeoutMs: Long = 30_000, cond: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond() && System.currentTimeMillis() < deadline) { idleMain(); Thread.sleep(10) }
    if (!cond()) throw AssertionError("timed out waiting for $what")
}

/** The CodeView must never throw while measuring, drawing or handling touch, whatever it shows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class CodeViewEdgeCaseTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun lex(lang: com.repolens.app.core.Language, text: String): List<LexedLine> =
        Lexer(lang).lexAll(SourceText.splitLinesCapped(text, 200_000, 20_000).lines)

    private fun event(action: Int, x: Float, y: Float, t: Long) = MotionEvent.obtain(t, t, action, x, y, 0)

    private fun pinch(v: View, from: Float, to: Float) {
        val t = SystemClock.uptimeMillis()
        val props = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        fun coords(d: Float) = arrayOf(
            MotionEvent.PointerCoords().apply { x = 500f - d; y = 400f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 500f + d; y = 400f; pressure = 1f; size = 1f },
        )
        fun ev(action: Int, n: Int, d: Float, dt: Long) =
            MotionEvent.obtain(t, t + dt, action, n, props, coords(d), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 1, from, 0))
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, from, 10))
        for (k in 1..10) v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 2, from + (to - from) * k / 10, 10L + 16 * k))
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, to, 200))
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 1, to, 210))
    }

    private fun exercise(name: String, lines: List<LexedLine>, lenses: Map<Int, String> = emptyMap()) {
        val v = CodeView(ctx)
        var taps = 0
        v.listener = object : CodeView.Listener {
            override fun onWordTapped(line: Int, col: Int, word: String) { taps++ }
            override fun onLensTapped(line: Int) {}
            override fun onBlankTapped() {}
            override fun onTextSizeChanged(sp: Float) {}
        }
        val bmp = Bitmap.createBitmap(1179, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        try {
            for (wrap in listOf(false, true)) {
                v.setContent(lines, lenses, wrap)
                v.measure(View.MeasureSpec.makeMeasureSpec(1179, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, 1179, 1600)
                v.draw(canvas)
                // taps all over the place, a drag and a fling
                for (y in listOf(5f, 300f, 900f, 1599f)) for (x in listOf(2f, 200f, 700f, 1178f)) {
                    val t = SystemClock.uptimeMillis()
                    v.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y, t))
                    v.dispatchTouchEvent(event(MotionEvent.ACTION_UP, x, y, t + 30))
                }
                val t = SystemClock.uptimeMillis()
                v.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 600f, 1200f, t))
                for (k in 1..8) v.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 600f - k * 60, 1200f - k * 140, t + k * 8))
                v.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 100f, 80f, t + 80))
                v.computeScroll()
                v.draw(canvas)
                // navigation requests out of range
                v.reveal(-5, -5, 3); v.reveal(Int.MAX_VALUE / 2, 1_000_000, 50); v.reveal(lines.size - 1, 0, 0)
                v.scrollToTopLine(-1); v.scrollToTopLine(Int.MAX_VALUE)
                v.draw(canvas)
                // zoom extremes, by API and by pinch
                v.setTextSize(Float.NaN); v.setTextSize(-3f); v.setTextSize(1000f); v.draw(canvas)
                pinch(v, 300f, 20f); v.draw(canvas)
                pinch(v, 20f, 500f); v.draw(canvas)
                v.setTextSize(v.fitTextSize()); v.draw(canvas)
                assertTrue(v.textSizeSp in CodeView.MIN_SP..CodeView.MAX_SP)
                v.setTextSize(11f)
                // replace the content with something much shorter while scrolled to the end
                v.scrollToTopLine(Int.MAX_VALUE)
                v.setContent(lex(Languages.KOTLIN, "fun short() = 1"), emptyMap(), wrap)
                v.draw(canvas)
                assertEquals(0, v.firstVisibleLine())
                v.setContent(lines, lenses, wrap)
                v.draw(canvas)
                assertTrue(v.firstVisibleLine() in -1 until maxOf(1, lines.size))
            }
        } catch (e: Throwable) {
            throw AssertionError("CodeView failed on '$name'", e)
        } finally {
            bmp.recycle()
        }
    }

    @Test fun emptyAndBlank() {
        exercise("no lines", emptyList())
        exercise("empty file", lex(Languages.KOTLIN, ""))
        exercise("blank lines", lex(Languages.PLAIN, "\n\n\n   \n\t\n"))
        exercise("lens on line 0", lex(Languages.KOTLIN, "fun a() = 1\nfun b() = 2"), mapOf(0 to "3 references", 1 to "1 reference", 99 to "stale"))
    }

    @Test fun veryLongLines() {
        exercise("1 MB minified line (cut to 20k)", lex(Languages.JAVASCRIPT, "function f(a){return a+1};".repeat(40_000)))
        exercise("150k chars uncut", Lexer(Languages.JAVASCRIPT).lexAll(listOf("x=1;".repeat(37_500))))
        exercise("long non-ASCII line", lex(Languages.PLAIN, "Ünïcödé 👋🏽 שלום é ".repeat(1_500)))
    }

    @Test fun unicodeAndLineEndings() {
        exercise("emoji, surrogates, combining, RTL, BOM, CRLF, tabs",
            lex(Languages.PYTHON, "﻿def wave():\r\n\treturn \"👋🏽\"  # שלום עולם é́́\r\n‮evil‬ = 1\r\n𝔘𝔫𝔦𝔠𝔬𝔡𝔢 = '\uD83D'\n\t\tx\t= 1"))
    }

    @Test fun manyLines() {
        exercise("200k lines", lex(Languages.KOTLIN, buildString { repeat(200_000) { append("val v").append(it).append(" = ").append(it).append('\n') } }))
    }
}

/** Odd files, failing loaders, crash reports and restoring after process death, through the ViewModel and the real screens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class RobustnessTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var vm: AppViewModel

    @Before fun setUp() {
        File(app.filesDir, "repos").deleteRecursively()
        app.getSharedPreferences("repolens", 0).edit().clear().commit()
        CrashReporter.store(app).clear()
        installDemoRepo(app.filesDir)
        val src = File(app.filesDir, "repos/odd/files/src")
        fun put(path: String, bytes: ByteArray) = File(src, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        put("README.md", "# odd\n".toByteArray())
        put("empty.kt", ByteArray(0))
        put("blank.kt", "\n\n\n\n".toByteArray())
        put("dist/app.js", "function f(a){return a+1};".repeat(40_000).toByteArray())
        put("big.txt", buildString { repeat(200_000) { append("line ").append(it).append('\n') } }.toByteArray())
        put("emoji.py", "def wave():\r\n    return \"👋🏽 שלום é\"\r\n".toByteArray())
        put("bin/blob.dat", ByteArray(5000) { it.toByte() })
        put("bin/nul.kt", "fun a() = 1\n".toByteArray() + ByteArray(10))
        put("img/corrupt.png", byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0))
        put("img/empty.png", ByteArray(0))
        put("huge.json", ByteArray(5 * 1024 * 1024) { 'a'.code.toByte() })
        File(app.filesDir, "repos/odd/files/meta.properties").writeText("branch=main\n")
    }

    private fun showApp(model: AppViewModel) {
        vm = model
        compose.setContent { RepoLensTheme { val ws = vm.workspace; if (ws == null) HomeScreen(vm) else WorkspaceScreen(vm, ws) } }
    }

    private fun openRepo(name: String) {
        vm.open(name)
        waitUntil("open $name") { vm.workspace?.doc != null && (vm.workspace?.index != null || vm.workspace?.indexError != null) || vm.error != null }
        assertNull(vm.error)
        compose.waitForIdle()
    }

    @Test fun oddFilesOpenWithoutCrashing() {
        showApp(AppViewModel(app))
        openRepo("odd/files")
        val ws = vm.workspace!!
        val expectText = mapOf("empty.kt" to 1, "blank.kt" to 4, "dist/app.js" to 1, "big.txt" to 200_000, "emoji.py" to 2)
        for (path in ws.paths) {
            vm.openFile(path, null)
            waitUntil(path) { ws.doc?.path == path }
            compose.waitForIdle()
            val doc = ws.doc!!
            expectText[path]?.let { n -> assertEquals(path, n, doc.lines?.size) }
            if (path.startsWith("bin/") || path == "huge.json") assertNotNull("$path should explain why it isn't shown", doc.note)
            if (path.startsWith("img/")) assertNotNull(doc.imageFile)
        }
        vm.openFile("dist/app.js", null)
        waitUntil("minified") { ws.doc?.path == "dist/app.js" }
        assertTrue(ws.doc!!.banner!!.contains("cut for display"))
        assertNull(decodeImageSafely(File(app.filesDir, "repos/odd/files/src/img/corrupt.png")))
        assertNull(decodeImageSafely(File(app.filesDir, "repos/odd/files/src/img/empty.png")))
        assertNull(CrashReporter.store(app).pending())
    }

    /**
     * What happened on phones with 1.0.0: a class used to open files failed to initialise (an
     * ICU-incompatible regex) inside an unguarded coroutine, which killed the app. Now it's a message.
     */
    @Test fun aFailingFileLoaderOrIndexIsAMessageNotACrash() {
        val model = AppViewModel(app)
        model.docLoader = { _, path -> if (path == "README.md") throw ExceptionInInitializerError("simulated: Languages failed to initialise") else throw OutOfMemoryError("simulated") }
        model.indexLimits = { throw OutOfMemoryError("simulated") }
        showApp(model)
        openRepo("demo/shop")
        val ws = vm.workspace!!
        assertTrue(ws.doc!!.note!!, ws.doc!!.note!!.startsWith("Couldn't open this file"))
        assertTrue(ws.indexError!!, ws.indexError!!.startsWith("Not enough memory to index"))
        vm.openFile("src/main/kotlin/shop/Cart.kt", null)
        waitUntil("second file") { ws.doc?.path == "src/main/kotlin/shop/Cart.kt" }
        assertTrue(ws.doc!!.note!!.contains("Not enough memory"))
        compose.waitForIdle()
        assertNull(CrashReporter.store(app).pending())
    }

    @Test fun crashReportCardCopiesAndDismisses() {
        val report = "RepoLens crash report\nTime: 2026-11-09 10:00:00 UTC\nDoing: Opening Jigar18/Stratos\n\njava.lang.ExceptionInInitializerError\n\tat com.repolens.app.core.Languages.<clinit>(Language.kt:60)\n"
        CrashReporter.store(app).write(report)
        showApp(AppViewModel(app))
        waitUntil("crash report") { vm.crashReport != null }
        compose.waitForIdle()
        compose.onNodeWithText("RepoLens crashed last time").assertExists()
        compose.onNodeWithText("Copy").performClick()
        val clip = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(report, clip.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithText("Dismiss").performClick()
        waitUntil("dismissed") { vm.crashReport == null && CrashReporter.store(app).pending() == null }
        compose.onNodeWithText("RepoLens crashed last time").assertDoesNotExist()
    }

    @Test fun restoresTheOpenRepoAfterProcessDeath() {
        showApp(AppViewModel(app, SavedStateHandle(mapOf("openRepo" to "demo/shop", "openFile" to "src/main/kotlin/shop/Cart.kt"))))
        waitUntil("restored") { vm.workspace?.doc != null }
        assertEquals("src/main/kotlin/shop/Cart.kt", vm.workspace!!.doc!!.path)
    }

    @Test fun doesNotReopenTheRepoThatCrashedTheApp() {
        CrashReporter.store(app).write("RepoLens crash report\nDoing: Browsing demo/shop, file README.md\n\njava.lang.RuntimeException: x\n")
        showApp(AppViewModel(app, SavedStateHandle(mapOf("openRepo" to "demo/shop"))))
        waitUntil("startup") { vm.crashReport != null && vm.error != null }
        assertNull(vm.workspace)
        assertTrue(vm.error!!, vm.error!!.contains("wasn't reopened automatically"))
    }

    @Test fun oddShareIntentsDontCrashTheActivity() {
        val intents = listOf(
            Intent(Intent.ACTION_SEND),
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, ""),
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "just some words"),
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, SpannableString("look: https://github.com/demo/shop").apply { setSpan(StyleSpan(1), 0, 4, 0) }),
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "x".repeat(100_000)),
            Intent(Intent.ACTION_VIEW),
        )
        for (intent in intents) {
            val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
            idleMain()
            controller.pause().stop().destroy()
        }
        assertNull(CrashReporter.store(app).pending())
    }
}
