package com.repolens.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.github.takahirom.roborazzi.captureRoboImage
import com.repolens.app.R
import com.repolens.app.core.Languages
import com.repolens.app.core.LexedLine
import com.repolens.app.core.Lexer
import com.repolens.app.core.SourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Code must be drawn in a real monospace font whatever the phone's fonts are, and must still be laid
 * out correctly (contiguous runs, right tap targets) if the font in use turns out to be proportional.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class CodeViewFontTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private val source = """
        package com.stratos.auth_service.controller;

        import org.springframework.http.HttpStatus;
        import org.springframework.http.ResponseEntity;
        import org.springframework.web.bind.annotation.*;

        /** Issues and refreshes access tokens for the mobile and web clients. */
        @RestController
        @RequestMapping("/api/v1/auth")
        public class AuthController {
            private final AuthService authService;

            public AuthController(AuthService authService) {
                this.authService = authService;
            }

            @PostMapping("/login")
            public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request) {
                if (!authService.isValid(request)) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Invalid credentials", authenticationFailureCount, requestTimestamp));
                }
                return ResponseEntity.ok(authService.issueTokens(request.getUsername())); // short-lived access token + refresh token
            }
        }
    """.trimIndent()

    private val lines: List<LexedLine> = Lexer(Languages.JAVA).lexAll(SourceText.splitLinesCapped(source, 200_000, 20_000).lines)
    private val lenses = mapOf(
        lines.indexOfFirst { it.text.startsWith("public class") } to "4 references",
        lines.indexOfFirst { it.text.contains("login(") } to "2 references",
    )
    private val w = 1179
    private val h = 1300

    private fun view(face: Typeface?): CodeView {
        val v = CodeView(ctx)
        v.setDarkTheme(false)
        if (face != null) v.setCodeTypeface(face)
        v.setContent(lines, lenses, false)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return v
    }

    /** Measures text the way CodeView should: its typeface and size, no ligatures. */
    private fun paintOf(v: CodeView) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = v.codeTypeface
        textSize = v.codeTextSizePx
        fontFeatureSettings = "'calt' 0, 'liga' 0"
    }

    private fun shot(v: CodeView, name: String) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        bmp.captureRoboImage("build/screenshots/$name.png")
        bmp.recycle()
    }

    /** Drags the view sideways by [dx] px slowly enough that it doesn't fling. */
    private fun dragBy(v: View, dx: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val x0 = if (dx > 0) 1100f else 200f
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x0, y, 0))
        for (k in 1..10) v.dispatchTouchEvent(MotionEvent.obtain(t, t + 20L * k, MotionEvent.ACTION_MOVE, x0 - dx * k / 10, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 1000, MotionEvent.ACTION_MOVE, x0 - dx, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 1010, MotionEvent.ACTION_UP, x0 - dx, y, 0))
    }

    private fun isFixedPitch(face: Typeface): Boolean {
        val p = Paint(Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = face; textSize = 40f }
        val m = p.measureText("M")
        return "il.,;:_ W0@#{}|()".all { abs(p.measureText(it.toString()) - m) < 0.01f }
    }

    @Test fun bundledFontLoadsIsFixedPitchAndIsUsedForCode() {
        val face = ResourcesCompat.getFont(ctx, R.font.jetbrains_mono_regular)
        assertNotNull("bundled font loads", face)
        assertTrue("bundled font is fixed-pitch", isFixedPitch(face!!))
        assertSame(face, CodeView.loadCodeTypeface(ctx))

        val v = view(null)
        assertSame("CodeView draws code in the bundled font", face, v.codeTypeface)
        assertTrue(v.isFixedPitch)
        // on the grid, column x is exactly what the font measures
        val paint = paintOf(v)
        val line = lines.indexOfFirst { it.text.contains("UNAUTHORIZED") }
        val text = lines[line].text
        for (col in listOf(0, 10, 40, text.length)) assertEquals(paint.measureText(text, 0, col), v.xForColumn(line, col), 0.5f)
        shot(v, "font_bundled_mono")
    }

    @Test fun proportionalFallbackIsMeasuredNotSpreadOnAGrid() {
        val face = Typeface.SANS_SERIF
        assertFalse("test needs a proportional font", isFixedPitch(face))
        val v = view(face)
        assertFalse(v.isFixedPitch)
        val paint = paintOf(v)

        // every token starts right after the measured text before it: no grid gaps
        for (line in lines.indices) {
            val l = lines[line]
            for (t in 0 until l.tokenCount) {
                val s = l.start(t)
                assertEquals("x of '${l.text.substring(s, l.end(t))}' on line ${line + 1}", paint.measureText(l.text, 0, s), v.xForColumn(line, s), 1f)
            }
        }

        // tapping the middle of a word late in a long line selects that word (scrolled there by dragging)
        val line = lines.indexOfFirst { it.text.contains("UNAUTHORIZED") }
        val text = lines[line].text
        val word = "requestTimestamp"
        val start = text.indexOf(word)
        val y = v.pointFor(line, 0).y
        val wordX = paint.measureText(text, 0, start)
        repeat(4) {
            val scrolled = v.textLeftPx - v.textOriginX
            val need = wordX - 300f - scrolled
            if (abs(need) > 1f) dragBy(v, need.coerceIn(-900f, 900f), y)
        }
        var tapped: Pair<Int, String>? = null
        v.listener = object : CodeView.Listener {
            override fun onWordTapped(line: Int, col: Int, word: String) { tapped = col to word }
            override fun onLensTapped(line: Int) {}
            override fun onBlankTapped() {}
            override fun onTextSizeChanged(sp: Float) {}
        }
        val mid = start + word.length / 2
        val x = v.textOriginX + paint.measureText(text, 0, mid) + paint.measureText(text, mid, mid + 1) / 2
        assertTrue("tap point $x is on screen", x > v.textLeftPx && x < w)
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        assertEquals(start to word, tapped)

        // going to a location late in the line scrolls the measured word into view
        val other = text.indexOf("authenticationFailureCount")
        v.reveal(line, other, "authenticationFailureCount".length)
        val left = v.textOriginX + paint.measureText(text, 0, other)
        val right = v.textOriginX + paint.measureText(text, 0, other + "authenticationFailureCount".length)
        assertTrue("revealed word [$left, $right] is on screen", left >= v.textLeftPx && right <= w)

        // the horizontal scroll extent ends at the measured widest line
        val widest = lines.maxOf { paint.measureText(it.text) }
        assertEquals(v.textLeftPx + widest + v.textRightPadPx - w, v.horizontalScrollRange, 1f)

        shot(view(face), "font_proportional_fallback")
    }
}
