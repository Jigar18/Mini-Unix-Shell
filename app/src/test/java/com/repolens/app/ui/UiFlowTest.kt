package com.repolens.app.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.repolens.app.AppViewModel
import com.repolens.app.core.Lexer
import com.repolens.app.core.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

private const val SHOTS = "build/screenshots"

/** A small fake repository placed where the downloader keeps its cache, so no network is needed. */
fun installDemoRepo(filesDir: File) {
    val root = File(filesDir, "repos/demo/shop")
    val src = File(root, "src")
    root.deleteRecursively()
    fun put(path: String, text: String) = File(src, path).apply { parentFile!!.mkdirs(); writeText(text.trimIndent() + "\n") }
    put("README.md", """
        # Shop
        A tiny example store used to exercise RepoLens: carts, pricing rules and checkout. This paragraph is intentionally long so that soft-wrapping of prose files can be seen in the screenshots.
    """)
    put("src/main/kotlin/shop/Cart.kt", """
        package shop

        import shop.pricing.Pricing
        import shop.pricing.Discount

        /** A shopping cart holding line items. */
        class Cart(private val pricing: Pricing) {
            private val items = mutableListOf<LineItem>()

            fun add(product: Product, quantity: Int = 1) {
                require(quantity > 0) { "quantity must be positive" }
                items.add(LineItem(product, quantity))
            }

            fun subtotal(): Long = items.sumOf { it.product.priceCents * it.quantity }

            fun total(discount: Discount? = null): Long {
                val base = subtotal()
                return pricing.applyTax(discount?.let { pricing.applyDiscount(base, it) } ?: base) // tax is applied after any discount so coupons never reduce the tax owed below the legal minimum
            }
        }

        data class LineItem(val product: Product, val quantity: Int)
        data class Product(val sku: String, val name: String, val priceCents: Long)
    """)
    put("src/main/kotlin/shop/pricing/Pricing.kt", """
        package shop.pricing

        class Pricing(private val taxRate: Double) {
            fun applyTax(cents: Long): Long = (cents * (1 + taxRate)).toLong()

            fun applyDiscount(cents: Long, discount: Discount): Long = when (discount) {
                is Discount.Percent -> cents - cents * discount.value / 100
                is Discount.Fixed -> maxOf(0, cents - discount.cents)
            }
        }

        sealed class Discount {
            data class Percent(val value: Int) : Discount()
            data class Fixed(val cents: Long) : Discount()
        }
    """)
    put("src/main/kotlin/shop/Main.kt", """
        package shop

        import shop.pricing.Pricing
        import shop.pricing.Discount

        fun main() {
            val cart = Cart(Pricing(taxRate = 0.08))
            cart.add(Product("A1", "Mug", 1299), quantity = 2)
            println(cart.total(Discount.Percent(10)))
            println(cart.subtotal())
        }
    """)
    put("src/main/java/shop/legacy/Checkout.java", """
        package shop.legacy;

        import shop.Cart;

        public class Checkout {
            private final Cart cart;

            public Checkout(Cart cart) { this.cart = cart; }

            public long charge() {
                return cart.total(null);
            }
        }
    """)
    put("scripts/report.py", """
        def total(rows):
            return sum(r["cents"] for r in rows)
    """)
    File(root, "meta.properties").writeText("branch=main\ndownloadedAt=0\nbytes=48211\n")
}

@OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class UiFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var vm: AppViewModel

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        installDemoRepo(app.filesDir)
        app.getSharedPreferences("repolens", 0).edit().clear().commit()
        vm = AppViewModel(app)
        compose.setContent {
            RepoLensTheme {
                val ws = vm.workspace
                if (ws == null) HomeScreen(vm) else WorkspaceScreen(vm, ws)
            }
        }
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!cond() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(15)
        }
        try { compose.waitUntil(1_000) { cond() } } catch (e: Throwable) { throw AssertionError("timed out waiting for $what; error=${vm.error} busy=${vm.busyMessage} ws=${vm.workspace} doc=${vm.workspace?.doc?.path} index=${vm.workspace?.index} progress=${vm.workspace?.indexProgress} indexErr=${vm.workspace?.indexError}", e) }
        compose.waitForIdle()
    }

    private fun findCodeView(): CodeView {
        var found: CodeView? = null
        val root = compose.activity.window.decorView
        fun walk(v: View) { if (v is CodeView) found = v else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(root)
        return found!!
    }

    private fun tap(view: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        compose.waitForIdle()
    }

    @Test fun fullFlow() {
        compose.onRoot().captureRoboImage("$SHOTS/01_home.png")

        vm.input = "https://github.com/demo/shop"
        vm.open()
        waitFor("workspace") { vm.workspace?.doc != null && vm.workspace?.index != null }
        compose.onRoot().captureRoboImage("$SHOTS/02_readme_wrapped.png")

        val ws = vm.workspace!!
        vm.openFile("src/main/kotlin/shop/Cart.kt", null)
        waitFor("Cart.kt + lenses") { ws.doc?.path?.endsWith("Cart.kt") == true && ws.lenses.isNotEmpty() }
        compose.onRoot().captureRoboImage("$SHOTS/03_editor_with_reference_lenses.png")

        // Lens above `fun subtotal()` (line 14): used in Cart.total and Main.kt.
        assertEquals("2 references", ws.lenses[14])
        // Lens above `fun total()` (line 16): Main.kt and Checkout.java — not the Python `total`.
        assertEquals("2 references", ws.lenses[16])

        // Tap the word `applyDiscount` (a call on line 18) in the real view.
        val cv = findCodeView()
        val line = 18
        val col = cv.let { ws.doc!!.lines!![line].text.indexOf("applyDiscount") }
        val p = cv.pointFor(line, col + 3)
        tap(cv, p.x, p.y)
        val sel = ws.selection
        assertNotNull("tapping a word should select it", sel)
        assertEquals("applyDiscount", sel!!.word)
        assertEquals("src/main/kotlin/shop/pricing/Pricing.kt", sel.definitions.single().file.path)
        compose.onRoot().captureRoboImage("$SHOTS/04_word_selected.png")

        // Go to definition -> jumps into Pricing.kt and remembers where we came from.
        compose.onNodeWithText("Go to definition").performClick()
        waitFor("Pricing.kt") { ws.doc?.path?.endsWith("Pricing.kt") == true && ws.lenses.isNotEmpty() }
        assertEquals(2, ws.history.size) // README -> Cart.kt -> Pricing.kt
        compose.onRoot().captureRoboImage("$SHOTS/05_jumped_to_definition.png")

        // Tap the lens above `fun applyTax` to list its references.
        val cv2 = findCodeView()
        val taxLine = ws.doc!!.lines!!.indexOfFirst { it.text.contains("fun applyTax") }
        assertTrue(ws.lenses.containsKey(taxLine))
        val lp = cv2.lensPointFor(taxLine)
        tap(cv2, lp.x, lp.y)
        waitFor("references sheet") { ws.sheet?.loading == false }
        val sheet = ws.sheet!!
        assertEquals(listOf("src/main/kotlin/shop/Cart.kt"), sheet.folders.flatMap { f -> f.files.map { it.path } })
        compose.waitForIdle()
        captureScreenRoboImage("$SHOTS/06_references_sheet.png")

        // Tap the reference (shown as line 19) -> navigates to Cart.kt
        compose.onNodeWithText("19").performClick()
        waitFor("back in Cart.kt") { ws.doc?.path?.endsWith("Cart.kt") == true && ws.sheet == null }
        assertEquals(3, ws.history.size)

        // `total` is defined in Kotlin (Cart) and Python (report.py): only the JVM one is offered,
        // and Checkout.java's call counts as a reference.
        val totalDef = ws.index!!.definitionsFor("total", "src/main/kotlin/shop/Main.kt", 8)
        assertEquals(listOf("src/main/kotlin/shop/Cart.kt"), totalDef.map { it.file.path })
        assertEquals(2, ws.index!!.referenceCount(totalDef[0]))

        // Back returns to Pricing.kt, then to the original spot in Cart.kt.
        vm.back()
        waitFor("history back") { ws.doc?.path?.endsWith("Pricing.kt") == true }
        vm.back()
        waitFor("history back 2") { ws.doc?.path?.endsWith("Cart.kt") == true }

        // File drawer
        compose.onNodeWithContentDescription("Files").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage("$SHOTS/07_file_tree.png")
    }

    @Test fun symbolSearchAndDefinitionChooser() {
        vm.open("demo/shop")
        waitFor("workspace") { vm.workspace?.index != null && vm.workspace?.doc != null }
        val ws = vm.workspace!!
        vm.openFile("src/main/kotlin/shop/Main.kt", null)
        waitFor("Main.kt") { ws.doc?.path?.endsWith("Main.kt") == true }

        compose.onNodeWithContentDescription("Search symbols").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNode(hasTestTag("symbolQuery")).performTextInput("Disc")
        compose.mainClock.advanceTimeBy(500)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Discount").fetchSemanticsNode() }.isSuccess }
        captureScreenRoboImage("$SHOTS/08_symbol_search.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class CodeViewTest {
    @Test fun noWrapScrollsSideways_andWrapSplitsRows() {
        val ctx = RuntimeEnvironment.getApplication()
        val v = CodeView(ctx)
        val long = "val x = " + "veryLongIdentifierName + ".repeat(20) + "end"
        val lines = Lexer(Languages.KOTLIN).lexAll(listOf("fun main() {", "    $long", "}"))
        v.measure(View.MeasureSpec.makeMeasureSpec(1179, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1179, 600)
        v.setContent(lines, mapOf(0 to "3 references"), wrapLines = false)
        val noWrapY = v.pointFor(2, 0).y
        v.setContent(lines, mapOf(0 to "3 references"), wrapLines = true)
        val wrapY = v.pointFor(2, 0).y
        assertTrue("wrapping should push the closing brace further down ($noWrapY vs $wrapY)", wrapY > noWrapY * 2)

        // tapping a word reports it
        v.setContent(lines, emptyMap(), wrapLines = false)
        var tapped: String? = null
        v.listener = object : CodeView.Listener {
            override fun onWordTapped(line: Int, col: Int, word: String) { tapped = word }
            override fun onLensTapped(line: Int) {}
            override fun onBlankTapped() {}
            override fun onTextSizeChanged(sp: Float) {}
        }
        val p = v.pointFor(0, 5)
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, p.x, p.y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, p.x, p.y, 0))
        assertEquals("main", tapped)
    }
}
