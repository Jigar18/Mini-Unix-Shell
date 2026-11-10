package com.repolens.app.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.repolens.app.AppViewModel
import com.repolens.app.CrashReporter
import com.repolens.app.DocLoader
import com.repolens.app.core.IndexLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
 * Screenshots of every screen in light and dark mode for review, written to
 * build/screenshots/{light,dark}_*.png. Each mode is its own class (one JVM per class).
 */
@OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
abstract class ThemedScreens(private val mode: String, private val dark: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    protected val app get() = RuntimeEnvironment.getApplication()
    private var model by mutableStateOf<AppViewModel?>(null)
    protected val vm: AppViewModel get() = model!!

    @Before fun setUp() {
        File(app.filesDir, "repos").deleteRecursively()
        installDemoRepo(app.filesDir)
        app.getSharedPreferences("repolens", 0).edit().clear().commit()
        CrashReporter.store(app).clear()
        model = AppViewModel(app)
        compose.setContent {
            RepoLensTheme {
                val m = model ?: return@RepoLensTheme
                val ws = m.workspace
                if (ws == null) HomeScreen(m) else WorkspaceScreen(m, ws)
            }
        }
    }

    /** Replaces the view model shown (e.g. after seeding recents or a crash report). */
    protected fun useNewViewModel() {
        model = AppViewModel(app)
        compose.waitForIdle()
    }

    protected fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/${mode}_$name.png")
    }

    /** Includes popups, dialogs and bottom sheets. */
    protected fun screenShot(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("build/screenshots/${mode}_$name.png")
    }

    protected fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!cond() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(15)
        }
        try { compose.waitUntil(1_000) { cond() } } catch (e: Throwable) { throw AssertionError("timed out waiting for $what; error=${model?.error}", e) }
        compose.waitForIdle()
    }

    protected fun findCodeView(): CodeView {
        var found: CodeView? = null
        fun walk(v: View) { if (v is CodeView) found = v else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(compose.activity.window.decorView)
        return found!!
    }

    protected fun tap(view: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        compose.waitForIdle()
    }

    protected fun seedRecents() {
        val now = System.currentTimeMillis()
        val h = 3_600_000L
        val rows = listOf(
            "demo\tshop\tmain\t${now - 5 * 60_000}\t48211",
            "square\tretrofit\tmaster\t${now - 3 * h}\t4404019",
            "pallets\tflask\tmain\t${now - 26 * h}\t2936012",
            "Jigar18\tStratos\tmain\t${now - 9 * 24 * h}\t1258291",
        )
        app.getSharedPreferences("repolens", 0).edit().putString("recents", rows.joinToString("\n")).commit()
        useNewViewModel()
    }

    protected fun openCart() {
        if (vm.workspace == null) vm.open("demo/shop")
        waitFor("workspace") { vm.workspace?.doc != null && vm.workspace?.index != null }
        val ws = vm.workspace!!
        vm.openFile("src/main/kotlin/shop/Cart.kt", null)
        waitFor("Cart.kt + lenses") { ws.doc?.path?.endsWith("Cart.kt") == true && ws.lenses.isNotEmpty() }
        assertSame("code view palette follows the theme", if (dark) Palette.Dark else Palette.Light, findCodeView().palette)
    }
}

@OptIn(ExperimentalTestApi::class)
abstract class PhoneScreens(mode: String, dark: Boolean) : ThemedScreens(mode, dark) {
    @Test fun homeStates() {
        shot("home_empty")

        vm.input = "https://github.com/demo/nope"
        vm.error = "Repository demo/nope wasn't found. Check the link, or make sure the repository is public."
        shot("home_error")

        vm.error = null
        vm.input = "https://github.com/square/retrofit"
        vm.busyMessage = "Downloading square/retrofit… 1.8 MB"
        compose.mainClock.advanceTimeBy(700)
        shot("home_busy")
        vm.busyMessage = null

        CrashReporter.store(app).write(
            "RepoLens crash report\nTime: 2026-11-09 10:00:00 UTC\nDoing: Opening Jigar18/Stratos\n\n" +
                "java.lang.ExceptionInInitializerError\n\tat com.repolens.app.core.Languages.<clinit>(Language.kt:60)\n" +
                "\tat com.repolens.app.core.SymbolIndex.build(SymbolIndex.kt:337)\n\tat com.repolens.app.AppViewModel.startIndexing(AppViewModel.kt:262)\n",
        )
        useNewViewModel()
        waitFor("crash report") { vm.crashReport != null }
        shot("home_crash")
        compose.onNodeWithText("Dismiss").performClick()
        waitFor("dismissed") { vm.crashReport == null }

        seedRecents()
        shot("home_recents")
    }

    @Test fun workspace() {
        vm.open("demo/shop")
        waitFor("workspace") { vm.workspace?.doc != null && vm.workspace?.index != null }
        shot("ws_readme")

        openCart()
        val ws = vm.workspace!!
        shot("ws_code_lenses")

        val cv = findCodeView()
        val line = 18
        val col = ws.doc!!.lines!![line].text.indexOf("applyDiscount")
        val p = cv.pointFor(line, col + 3)
        tap(cv, p.x, p.y)
        assertEquals("applyDiscount", ws.selection?.word)
        compose.mainClock.advanceTimeBy(600)
        shot("ws_word_selected")

        compose.onNodeWithText("Go to definition").performClick()
        waitFor("Pricing.kt") { ws.doc?.path?.endsWith("Pricing.kt") == true && ws.lenses.isNotEmpty() }
        val cv2 = findCodeView()
        val taxLine = ws.doc!!.lines!!.indexOfFirst { it.text.contains("fun applyTax") }
        val lp = cv2.lensPointFor(taxLine)
        tap(cv2, lp.x, lp.y)
        waitFor("references sheet") { ws.sheet?.loading == false }
        compose.mainClock.advanceTimeBy(800)
        screenShot("ws_references_sheet")
        ws.sheet = null
        compose.waitForIdle()

        vm.openFile("src/main/kotlin/shop/Cart.kt", null)
        waitFor("Cart.kt") { ws.doc?.path?.endsWith("Cart.kt") == true }
        compose.onNodeWithContentDescription("Files").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        shot("ws_file_tree")
    }

    @Test fun symbolSearch() {
        openCart()
        compose.onNodeWithContentDescription("Search symbols").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNode(hasTestTag("symbolQuery")).performTextInput("Disc")
        compose.mainClock.advanceTimeBy(500)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Discount").fetchSemanticsNode() }.isSuccess }
        screenShot("ws_symbol_search")
    }

    /** The index-limit note and the "long line cut" banner, triggered with small limits. */
    @Test fun notices() {
        vm.indexLimits = { IndexLimits(maxFiles = 3, maxTotalBytes = Long.MAX_VALUE, maxOccurrences = 1_000_000, parallelism = 1) }
        vm.docLoader = { root, path -> DocLoader.load(root, path, DocLoader.Limits(4_000_000, 200_000, 90)) }
        vm.open("demo/shop")
        waitFor("workspace") { vm.workspace?.index != null && vm.workspace?.doc != null }
        val ws = vm.workspace!!
        assertNotNull(ws.indexNote)
        vm.openFile("src/main/kotlin/shop/Cart.kt", null)
        waitFor("Cart.kt") { ws.doc?.path?.endsWith("Cart.kt") == true && ws.doc?.banner != null }
        assertTrue(ws.doc!!.banner!!.contains("cut for display"))
        compose.mainClock.advanceTimeBy(500)
        screenShot("ws_notices")
    }
}

abstract class LandscapeScreens(mode: String, dark: Boolean) : ThemedScreens(mode, dark) {
    @Test fun landscape() {
        seedRecents()
        shot("land_home_recents")
        openCart()
        shot("land_code")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-notnight-xxhdpi")
class LightPhoneScreensTest : PhoneScreens("light", dark = false)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-night-xxhdpi")
class DarkPhoneScreensTest : PhoneScreens("dark", dark = true)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w852dp-h393dp-land-notnight-xxhdpi")
class LightLandscapeScreensTest : LandscapeScreens("light", dark = false)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w852dp-h393dp-land-night-xxhdpi")
class DarkLandscapeScreensTest : LandscapeScreens("dark", dark = true)

/**
 * The activity handles uiMode changes itself (no recreation): flipping the system to night mode
 * must switch the Compose scheme, the code view palette and the system bar icon style live.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-notnight-xxhdpi")
class ThemeSwitchTest {
    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            idle(); Thread.sleep(15)
        }
        idle()
    }

    private fun findCodeView(root: View): CodeView? {
        if (root is CodeView) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) findCodeView(root.getChildAt(i))?.let { return it }
        return null
    }

    /** Captures the window and returns the colour of a pixel in the top bar and one in the editor. */
    private fun capture(activity: ComponentActivity, name: String): kotlin.Pair<Int, Int> {
        val file = File("build/screenshots/$name.png")
        activity.window.decorView.captureRoboImage(file.path)
        val img = android.graphics.BitmapFactory.decodeFile(file.path)
        return img.getPixel(4, 4) to img.getPixel(img.width / 2, img.height - 120)
    }

    @Test fun followsSystemNightModeWithoutRecreating() {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "repos").deleteRecursively()
        installDemoRepo(app.filesDir)
        app.getSharedPreferences("repolens", 0).edit().clear().commit()
        val controller = org.robolectric.Robolectric.buildActivity(com.repolens.app.MainActivity::class.java).setup()
        val activity = controller.get()
        val vm = androidx.lifecycle.ViewModelProvider(activity)[AppViewModel::class.java]
        vm.open("demo/shop")
        waitFor("workspace") { vm.workspace?.index != null && vm.workspace?.doc != null }
        vm.openFile("src/main/kotlin/shop/Cart.kt", null)
        waitFor("Cart.kt") { vm.workspace?.doc?.path?.endsWith("Cart.kt") == true && findCodeView(activity.window.decorView) != null }
        val bars = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)

        assertSame(Palette.Light, findCodeView(activity.window.decorView)!!.palette)
        assertTrue("dark status bar icons in light mode", bars.isAppearanceLightStatusBars)
        val (barLight, editorLight) = capture(activity, "theme_switch_1_light")
        assertEquals(LightColors.surfaceContainerLow.toArgbInt(), barLight)
        assertEquals(Palette.Light.bg, editorLight)

        RuntimeEnvironment.setQualifiers("+night")
        controller.configurationChange(android.content.res.Configuration(app.resources.configuration))
        waitFor("dark palette") { findCodeView(activity.window.decorView)?.palette === Palette.Dark }
        assertSame("the activity handles uiMode itself", activity, controller.get())
        assertTrue("light status bar icons in dark mode", !bars.isAppearanceLightStatusBars)
        val (barDark, editorDark) = capture(activity, "theme_switch_2_dark")
        assertEquals(DarkColors.surfaceContainerLow.toArgbInt(), barDark)
        assertEquals(Palette.Dark.bg, editorDark)

        RuntimeEnvironment.setQualifiers("+notnight")
        controller.configurationChange(android.content.res.Configuration(app.resources.configuration))
        waitFor("light palette again") { findCodeView(activity.window.decorView)?.palette === Palette.Light }
        assertTrue(bars.isAppearanceLightStatusBars)
        controller.pause().stop().destroy()
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255 + 0.5f).toInt(), (green * 255 + 0.5f).toInt(), (blue * 255 + 0.5f).toInt())
}
