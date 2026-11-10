package com.repolens.app.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import com.repolens.app.core.LexedLine
import com.repolens.app.core.TokenType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A read-only code viewer drawn straight onto a Canvas, so files with tens of thousands of lines
 * scroll smoothly. Lines are never wrapped unless [wrap] is on: long lines scroll sideways while
 * the line-number gutter stays put.
 *
 * Rows are either code (one line, or one segment of a wrapped line) or a "lens" — the small
 * "N references" link drawn above a definition, like CodeLens in VS Code.
 */
class CodeView(context: Context) : View(context) {

    interface Listener {
        fun onWordTapped(line: Int, col: Int, word: String)
        fun onLensTapped(line: Int)
        fun onBlankTapped()
        fun onTextSizeChanged(sp: Float)
    }

    var listener: Listener? = null

    /** True while a word the user tapped is selected (as opposed to a highlight left by navigation). */
    var userSelection = false
        private set

    /** Id of the last navigation request applied, so each one runs exactly once. */
    var lastRequestId = -1L

    // ---------- content ----------
    private var lines: List<LexedLine> = emptyList()
    private var asciiLine = BooleanArray(0)
    private var lenses: Map<Int, String> = emptyMap()
    private var maxCols = 0

    // ---------- rows ----------
    private var rowCount = 0
    private var rowLine = IntArray(0)
    private var rowStart = IntArray(0)   // first column of this segment (wrap), or -1 for a lens row
    private var rowEnd = IntArray(0)
    private var rowTop = FloatArray(0)
    private var firstRowOfLine = IntArray(0)
    private var contentHeight = 0f

    // ---------- appearance ----------
    private val density = resources.displayMetrics.density

    /** Light or dark editor colours; the Compose host keeps this in step with the app theme. */
    var palette: Palette = if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) Palette.Dark else Palette.Light
        private set
    var textSizeSp = 11f
        private set
    var wrap = false
        private set

    private val codePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = Typeface.MONOSPACE }
    /** Always upright monospace, so measurements don't depend on what was drawn last. */
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = Typeface.MONOSPACE }

    /**
     * x offset of every column of recently drawn non-ASCII lines (emoji, CJK, combining marks…),
     * so drawing and hit-testing them is O(log n) instead of measuring the line again and again.
     */
    private val prefixCache = object : LinkedHashMap<Int, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FloatArray>?) = size > 256
    }
    private val italicFace = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT }
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val fill = Paint()
    private val rect = RectF()

    private var charW = 0f
    private var lineH = 0f
    private var lensH = 0f
    private var baseline = 0f
    private var lensBaseline = 0f
    private var gutterW = 0f
    private var padL = 0f
    private var padR = 0f
    private var bottomPad = 0f

    // ---------- scrolling ----------
    private var sx = 0f
    private var sy = 0f
    private val scroller = OverScroller(context)
    private var lockAxis = 0 // 0 undecided, 1 horizontal, 2 vertical, 3 free
    private var scaling = false
    private var pendingReveal: IntArray? = null
    private var pendingTopLine = -1

    // ---------- selection / highlight ----------
    private var selLine = -1
    private var selStart = 0
    private var selEnd = 0
    private var highlightWord: String? = null
    private var flashLine = -1
    private var flashStart = 0L

    init {
        isFocusable = true
        applyTextSize(textSizeSp)
    }

    // ================= public API =================

    fun setContent(newLines: List<LexedLine>, newLenses: Map<Int, String>, wrapLines: Boolean) {
        if (newLines === lines && newLenses === lenses && wrapLines == wrap) return
        val sameDoc = newLines === lines
        val topLine = if (sameDoc) firstVisibleLine() else -1
        val topOffset = if (sameDoc && topLine >= 0) sy - rowTop.getOrElse(firstRowOfLine.getOrElse(topLine) { 0 }) { 0f } else 0f
        lines = newLines
        lenses = newLenses
        wrap = wrapLines
        if (!sameDoc) {
            prefixCache.clear()
            asciiLine = BooleanArray(lines.size) { i -> lines[i].text.all { it.code < 128 } }
            maxCols = lines.maxOfOrNull { it.text.length } ?: 0
            sx = 0f; sy = 0f
            scroller.forceFinished(true)
            clearSelection()
            computeGutter()
        }
        rebuildRows()
        if (sameDoc && topLine >= 0 && topLine < firstRowOfLine.size) {
            sy = rowTop[firstRowOfLine[topLine]] + topOffset
            clampScroll()
        }
        invalidate()
    }

    /** Switches between the light and dark palette and redraws. */
    fun setDarkTheme(dark: Boolean) {
        val p = if (dark) Palette.Dark else Palette.Light
        if (p === palette) return
        palette = p
        invalidate()
    }

    fun setTextSize(sp: Float) {
        if (sp.isNaN() || abs(sp - textSizeSp) < 0.01f) return
        val top = firstVisibleLine()
        applyTextSize(sp)
        rebuildRows()
        if (top >= 0 && top < firstRowOfLine.size) sy = rowTop[firstRowOfLine[top]]
        clampScroll()
        invalidate()
    }

    /** Text size at which ~90% of this file's lines fit the screen width without scrolling. */
    fun fitTextSize(): Float {
        if (lines.isEmpty() || width == 0) return textSizeSp
        val lengths = lines.map { it.text.trimEnd().length }.filter { it > 0 }.sorted()
        if (lengths.isEmpty()) return textSizeSp
        val p90 = lengths[(lengths.size * 0.9).toInt().coerceAtMost(lengths.size - 1)].coerceAtLeast(40)
        val available = width - gutterW - padL - padR
        val perCharPx = available / p90
        // charW is proportional to text size
        val sp = textSizeSp * (perCharPx / charW)
        return sp.coerceIn(MIN_SP, 14f)
    }

    fun select(line: Int, start: Int, end: Int, word: String?) {
        selLine = line; selStart = start; selEnd = end; highlightWord = word
        invalidate()
    }

    fun clearSelection() {
        selLine = -1; highlightWord = null; userSelection = false
        invalidate()
    }

    /** Scrolls so [line] is in the upper third of the screen and flashes it. */
    fun reveal(line: Int, col: Int, length: Int) {
        if (height == 0 || rowCount == 0) { pendingReveal = intArrayOf(line, col, length); return }
        val l = line.coerceIn(0, max(0, lines.size - 1))
        // a stale location (file changed, restored history…) may point past the text
        val lineLen = lines.getOrNull(l)?.text?.length ?: 0
        val col = col.coerceIn(0, lineLen)
        val length = length.coerceIn(0, lineLen - col)
        var r = firstRowOfLine.getOrElse(l) { 0 }
        if (wrap) {
            while (r + 1 < rowCount && rowLine[r + 1] == l && rowStart[r + 1] >= 0 && rowStart[r + 1] <= col) r++
        }
        if (r > 0 && rowStart[r - 1] < 0) r-- // keep the lens above the definition visible
        scroller.forceFinished(true)
        sy = rowTop[r] - height * 0.28f
        if (!wrap && l < lines.size) {
            val x = xOfCol(l, col)
            val viewW = width - gutterW
            if (x - sx < 0 || x + length * charW - sx > viewW - padR) sx = max(0f, x - viewW * 0.25f)
        }
        clampScroll()
        flashLine = l
        flashStart = System.currentTimeMillis()
        if (length > 0) select(l, col, col + length, lines.getOrNull(l)?.text?.let { t -> if (col + length <= t.length) t.substring(col, col + length) else null })
        userSelection = false
        invalidate()
    }

    fun firstVisibleLine(): Int {
        if (rowCount == 0) return -1
        val r = rowAt(sy)
        for (k in r until rowCount) if (rowStart[k] >= 0) return rowLine[k]
        return rowLine[r]
    }

    fun scrollToTopLine(line: Int) {
        if (height == 0 || rowCount == 0) { pendingTopLine = line; return }
        val l = line.coerceIn(0, max(0, lines.size - 1))
        sy = rowTop[firstRowOfLine.getOrElse(l) { 0 }]
        clampScroll(); invalidate()
    }

    /** Screen position of the middle of [line]:[col] (unwrapped), for tests. */
    fun pointFor(line: Int, col: Int): android.graphics.PointF {
        if (line !in lines.indices) return android.graphics.PointF(-1f, -1f)
        val r = firstRowOfLine[line]
        return android.graphics.PointF(gutterW + padL - sx + xOfCol(line, col) + charW / 2, rowTop[r] - sy + lineH / 2)
    }

    /** Screen position of the lens above [line], for tests. */
    fun lensPointFor(line: Int): android.graphics.PointF {
        if (line !in lines.indices || firstRowOfLine[line] == 0) return android.graphics.PointF(-1f, -1f)
        val r = firstRowOfLine[line] - 1
        val text = lines[line].text
        val indent = text.length - text.trimStart().length
        return android.graphics.PointF(gutterW + padL - sx + xOfCol(line, indent) + charW * 2, rowTop[r] - sy + lensH / 2)
    }

    // ================= layout =================

    private fun applyTextSize(sp: Float) {
        textSizeSp = if (sp.isNaN()) 11f else sp.coerceIn(MIN_SP, MAX_SP)
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp, resources.displayMetrics)
        codePaint.textSize = px
        measurePaint.textSize = px
        prefixCache.clear()
        charW = codePaint.measureText("M").takeIf { it > 0f } ?: (px * 0.6f).coerceAtLeast(1f)
        val fm = codePaint.fontMetrics
        val textH = fm.descent - fm.ascent
        lineH = (textH * 1.32f).roundToInt().toFloat()
        baseline = (lineH - textH) / 2f - fm.ascent

        lensPaint.textSize = px * 0.8f
        val lfm = lensPaint.fontMetrics
        val lensTextH = lfm.descent - lfm.ascent
        lensH = (lensTextH * 1.25f).roundToInt().toFloat()
        lensBaseline = (lensH - lensTextH) / 2f - lfm.ascent + lensH * 0.08f

        gutterPaint.textSize = px * 0.88f
        padL = charW * 0.9f
        padR = charW * 3f
        bottomPad = 160 * density
        computeGutter()
    }

    private fun computeGutter() {
        val digits = max(2, lines.size.toString().length)
        gutterW = gutterPaint.measureText("0") * (digits + 1.6f)
    }

    private fun wrapCols(): Int {
        val w = width - gutterW - padL - padR * 0.4f
        return if (w <= 0 || charW <= 0) 80 else max(20, (w / charW).toInt())
    }

    private fun rebuildRows() {
        val n = lines.size
        val cols = if (wrap) wrapCols() else Int.MAX_VALUE
        var cap = n + lenses.size + 8
        var line = IntArray(cap); var start = IntArray(cap); var end = IntArray(cap)
        firstRowOfLine = IntArray(n)
        var count = 0
        fun add(l: Int, s: Int, e: Int) {
            if (count == cap) {
                cap *= 2
                line = line.copyOf(cap); start = start.copyOf(cap); end = end.copyOf(cap)
            }
            line[count] = l; start[count] = s; end[count] = e; count++
        }
        for (i in 0 until n) {
            if (lenses.containsKey(i)) add(i, -1, -1)
            firstRowOfLine[i] = count
            val len = lines[i].text.length
            if (!wrap || len <= cols) { add(i, 0, len); continue }
            var s = 0
            val text = lines[i].text
            while (s < len) {
                var e = min(len, s + cols)
                if (e < len) {
                    // break after the last space/punctuation in the final quarter of the segment
                    var k = e
                    while (k > s + cols * 3 / 4 && !text[k - 1].isWhitespace() && text[k - 1] !in ",;(){}[]") k--
                    if (k > s + cols * 3 / 4) e = k
                }
                add(i, s, e)
                s = e
            }
        }
        rowCount = count
        rowLine = line; rowStart = start; rowEnd = end
        rowTop = FloatArray(count + 1)
        var y = 0f
        for (r in 0 until count) {
            rowTop[r] = y
            y += if (start[r] < 0) lensH else lineH
        }
        rowTop[count] = y
        contentHeight = y
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (wrap && w != oldw) {
            val top = firstVisibleLine()
            rebuildRows()
            if (top >= 0) sy = rowTop[firstRowOfLine[top]]
        }
        clampScroll()
        pendingReveal?.let { pendingReveal = null; post { reveal(it[0], it[1], it[2]) } }
        if (pendingTopLine >= 0) { val l = pendingTopLine; pendingTopLine = -1; post { scrollToTopLine(l) } }
    }

    private fun maxScrollX(): Float =
        if (wrap) 0f else max(0f, gutterW + padL + maxCols * charW + padR - width)

    private fun maxScrollY(): Float = max(0f, contentHeight + bottomPad - height)

    private fun clampScroll() {
        sx = sx.coerceIn(0f, maxScrollX())
        sy = sy.coerceIn(0f, maxScrollY())
    }

    private fun rowAt(y: Float): Int {
        if (rowCount == 0) return 0
        var lo = 0
        var hi = rowCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (rowTop[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun prefix(line: Int): FloatArray = prefixCache.getOrPut(line) {
        val text = lines[line].text
        val widths = FloatArray(text.length)
        if (text.isNotEmpty()) measurePaint.getTextWidths(text, 0, text.length, widths)
        val p = FloatArray(text.length + 1)
        for (i in widths.indices) p[i + 1] = p[i] + widths[i].coerceAtLeast(0f)
        p
    }

    private fun xOfCol(line: Int, col: Int): Float {
        val text = lines[line].text
        val c = col.coerceIn(0, text.length)
        return if (asciiLine[line]) c * charW else prefix(line)[c]
    }

    private fun colAtX(line: Int, x: Float): Int {
        val text = lines[line].text
        if (asciiLine[line]) return (x / charW).toInt().coerceIn(0, text.length)
        val p = prefix(line)
        // first column whose middle is right of x
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if ((p[mid] + p[mid + 1]) / 2 > x) hi = mid else lo = mid + 1
        }
        return lo
    }

    // ================= drawing =================

    override fun onDraw(canvas: Canvas) {
        // fill only our bounds: drawColor() would paint the whole canvas when the host doesn't clip us
        fill.color = palette.bg; fill.alpha = 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        if (rowCount == 0) return
        val h = height
        val first = rowAt(sy)
        val codeLeft = gutterW
        val now = System.currentTimeMillis()
        val flashAlpha = if (flashLine >= 0) (1f - (now - flashStart) / FLASH_MS.toFloat()).coerceIn(0f, 1f) else 0f

        canvas.save()
        canvas.clipRect(codeLeft, 0f, width.toFloat(), h.toFloat())
        var r = first
        while (r < rowCount && rowTop[r] - sy < h) {
            val top = rowTop[r] - sy
            val line = rowLine[r]
            if (rowStart[r] < 0) {
                drawLens(canvas, line, top)
            } else {
                if (line == selLine) {
                    fill.color = palette.currentLine; fill.alpha = 255
                    canvas.drawRect(codeLeft, top, width.toFloat(), top + lineH, fill)
                }
                if (flashAlpha > 0f && line == flashLine) {
                    fill.color = palette.flash; fill.alpha = (flashAlpha * 255).toInt()
                    canvas.drawRect(codeLeft, top, width.toFloat(), top + lineH, fill)
                }
                drawCodeRow(canvas, r, line, top)
            }
            r++
        }
        canvas.restore()

        // gutter
        fill.color = palette.gutterBg; fill.alpha = 255
        canvas.drawRect(0f, 0f, gutterW, h.toFloat(), fill)
        fill.color = palette.gutterLine
        canvas.drawRect(gutterW - density, 0f, gutterW, h.toFloat(), fill)
        r = first
        val numX = gutterW - gutterPaint.measureText("0") * 0.7f
        while (r < rowCount && rowTop[r] - sy < h) {
            if (rowStart[r] == 0) {
                val line = rowLine[r]
                gutterPaint.color = if (line == selLine || line == flashLine && flashAlpha > 0) palette.lineNoActive else palette.lineNo
                canvas.drawText((line + 1).toString(), numX, rowTop[r] - sy + baseline, gutterPaint)
            }
            r++
        }

        drawScrollbars(canvas)
        if (flashAlpha > 0f) postInvalidateOnAnimation() else flashLine = -1
    }

    private fun drawLens(canvas: Canvas, line: Int, top: Float) {
        val label = lenses[line] ?: return
        val text = lines[line].text
        val indent = text.length - text.trimStart().length
        val x = gutterW + padL - sx + xOfCol(line, indent)
        lensPaint.color = palette.lens
        canvas.drawText(label, x, top + lensBaseline, lensPaint)
    }

    private fun drawCodeRow(canvas: Canvas, r: Int, line: Int, top: Float) {
        val lexed = lines[line]
        val text = lexed.text
        val segStart = rowStart[r]
        val segEnd = rowEnd[r]
        // x of column c on screen
        val ascii = asciiLine[line]
        val segX = if (wrap) xOfCol(line, segStart) else 0f
        val baseX = gutterW + padL - sx - segX
        val y = top + baseline

        var visStart = segStart
        var visEnd = segEnd
        if (ascii && !wrap) {
            visStart = max(segStart, ((sx - padL) / charW).toInt() - 1)
            visEnd = min(segEnd, ((sx + width) / charW).toInt() + 2)
            if (visStart >= visEnd) return
        } else if (!wrap && segEnd - segStart > 256) {
            // long non-ASCII line: only draw the part that is on screen
            visStart = max(segStart, colAtX(line, sx - padL) - 2)
            visEnd = min(segEnd, colAtX(line, sx + width) + 2)
            if (visStart >= visEnd) return
        }

        // occurrence highlights of the selected word
        val hw = highlightWord
        if (hw != null) {
            for (i in 0 until lexed.tokenCount) {
                val s = lexed.start(i); val e = lexed.end(i)
                if (e <= visStart) continue
                if (s >= visEnd) break
                if (e - s == hw.length && TokenType.isIdentifier(lexed.type(i)) && text.regionMatches(s, hw, 0, hw.length)) {
                    val sel = line == selLine && s == selStart
                    fill.color = if (sel) palette.selection else palette.occurrence; fill.alpha = 255
                    rect.set(baseX + xOfCol(line, s) - 1, top + 1, baseX + xOfCol(line, e) + 1, top + lineH - 1)
                    canvas.drawRoundRect(rect, 3 * density, 3 * density, fill)
                }
            }
        } else if (line == selLine && selEnd > segStart && selStart < segEnd) {
            fill.color = palette.selection; fill.alpha = 255
            rect.set(baseX + xOfCol(line, selStart) - 1, top + 1, baseX + xOfCol(line, selEnd) + 1, top + lineH - 1)
            canvas.drawRoundRect(rect, 3 * density, 3 * density, fill)
        }

        var pos = visStart
        for (i in 0 until lexed.tokenCount) {
            val ts = lexed.start(i); val te = lexed.end(i)
            if (te <= visStart) continue
            if (ts >= visEnd) break
            val s = max(ts, visStart); val e = min(te, visEnd)
            if (s > pos) drawRun(canvas, text, line, pos, s, baseX, y, palette.text, false)
            val type = lexed.type(i)
            drawRun(canvas, text, line, s, e, baseX, y, palette.colorOf(type), type == TokenType.COMMENT)
            pos = e
        }
        if (pos < visEnd) drawRun(canvas, text, line, pos, visEnd, baseX, y, palette.text, false)

        if (wrap && segStart > 0) {
            // continuation marker in the left padding
            lensPaint.color = palette.lineNo
            canvas.drawText("↪", gutterW + padL * 0.1f, y, lensPaint)
        }
    }

    private fun drawRun(canvas: Canvas, text: String, line: Int, s: Int, e: Int, baseX: Float, y: Float, color: Int, italic: Boolean) {
        if (e <= s) return
        // skip pure whitespace
        var k = s
        while (k < e && text[k] == ' ') k++
        if (k == e) return
        codePaint.color = color
        codePaint.typeface = if (italic) italicFace else Typeface.MONOSPACE
        canvas.drawText(text, k, e, baseX + xOfCol(line, k), y, codePaint)
    }

    private fun drawScrollbars(canvas: Canvas) {
        val viewH = height.toFloat()
        val total = contentHeight + bottomPad
        if (total > viewH * 1.05f) {
            val thumbH = max(32 * density, viewH * viewH / total)
            val t = sy / max(1f, maxScrollY()) * (viewH - thumbH)
            fill.color = palette.scrollbar
            rect.set(width - 4 * density, t, width - 1.5f * density, t + thumbH)
            canvas.drawRoundRect(rect, 2 * density, 2 * density, fill)
        }
        val maxX = maxScrollX()
        if (maxX > 0) {
            val viewW = width - gutterW
            val contentW = viewW + maxX
            val thumbW = max(32 * density, viewW * viewW / contentW)
            val l = gutterW + sx / maxX * (viewW - thumbW)
            fill.color = palette.scrollbar
            rect.set(l, viewH - 4 * density, l + thumbW, viewH - 1.5f * density)
            canvas.drawRoundRect(rect, 2 * density, 2 * density, fill)
        }
    }

    // ================= touch =================

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            lockAxis = 0
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling) return true
            if (lockAxis == 0) {
                val tx = abs(e2.x - (e1?.x ?: e2.x)); val ty = abs(e2.y - (e1?.y ?: e2.y))
                lockAxis = when {
                    maxScrollX() == 0f -> 2
                    ty > tx * 1.6f -> 2
                    tx > ty * 1.6f -> 1
                    else -> 3
                }
            }
            if (lockAxis != 2) sx += dx
            if (lockAxis != 1) sy += dy
            clampScroll()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling) return true
            val fx = if (lockAxis == 2) 0 else -vx.toInt()
            val fy = if (lockAxis == 1) 0 else -vy.toInt()
            scroller.fling(sx.toInt(), sy.toInt(), fx, fy, 0, maxScrollX().toInt(), 0, maxScrollY().toInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!scaling) handleTap(e.x, e.y)
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            scroller.forceFinished(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newSp = (textSizeSp * detector.scaleFactor).coerceIn(MIN_SP, MAX_SP)
            if (abs(newSp - textSizeSp) < 0.05f) return false
            val fx = detector.focusX; val fy = detector.focusY
            val oldLineH = lineH; val oldCharW = charW; val oldGutter = gutterW
            if (oldLineH <= 0f || oldCharW <= 0f || newSp.isNaN()) return false
            val contentY = sy + fy
            val contentX = sx + fx - oldGutter
            applyTextSize(newSp)
            rebuildRows()
            sy = contentY * (lineH / oldLineH) - fy
            sx = contentX * (charW / oldCharW) - (fx - gutterW)
            clampScroll()
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            listener?.onTextSizeChanged(textSizeSp)
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            scaling = false
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!scaleDetector.isInProgress) scaling = false
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.currX.toFloat()
            sy = scroller.currY.toFloat()
            clampScroll()
            postInvalidateOnAnimation()
        }
    }

    private fun handleTap(x: Float, y: Float) {
        if (rowCount == 0) return
        val cy = y + sy
        if (cy >= contentHeight) { listener?.onBlankTapped(); return }
        val r = rowAt(cy)
        val line = rowLine[r]
        if (rowStart[r] < 0) {
            val label = lenses[line]
            if (label != null) {
                val text = lines[line].text
                val indent = text.length - text.trimStart().length
                val lx = gutterW + padL - sx + xOfCol(line, indent)
                if (x >= lx - 12 * density && x <= lx + lensPaint.measureText(label) + 16 * density) {
                    listener?.onLensTapped(line); return
                }
            }
            listener?.onBlankTapped(); return
        }
        if (x < gutterW) { listener?.onBlankTapped(); return }
        val segX = if (wrap) xOfCol(line, rowStart[r]) else 0f
        val contentX = x - gutterW - padL + sx + segX
        val col = colAtX(line, contentX)
        val lexed = lines[line]
        // be forgiving: a fingertip is wider than a character
        var tok = lexed.identifierAt(col)
        if (tok < 0 && col > 0) tok = lexed.identifierAt(col - 1)
        if (tok < 0) tok = lexed.identifierAt(col + 1)
        if (tok < 0) { listener?.onBlankTapped(); return }
        val s = lexed.start(tok); val e = lexed.end(tok)
        val word = lexed.text.substring(s, e)
        select(line, s, e, word)
        userSelection = true
        listener?.onWordTapped(line, s, word)
    }

    companion object {
        const val MIN_SP = 6f
        const val MAX_SP = 30f
        private const val FLASH_MS = 1400L
    }
}

/**
 * Editor colours. Every token colour is at least 4.5:1 against [bg] and [currentLine], identifier
 * colours stay at least 4.5:1 on [selection]/[occurrence], and line numbers at least 3:1 (see ContrastTest).
 */
class Palette(
    val name: String,
    val bg: Int,
    val gutterBg: Int,
    val gutterLine: Int,
    val lineNo: Int,
    val lineNoActive: Int,
    val text: Int,
    val ident: Int,
    val keyword: Int,
    val type: Int,
    val function: Int,
    val string: Int,
    val number: Int,
    val comment: Int,
    val annotation: Int,
    val lens: Int,
    val selection: Int,
    val occurrence: Int,
    val flash: Int,
    val currentLine: Int,
    val scrollbar: Int,
) {
    fun colorOf(type: Int) = when (type) {
        TokenType.KEYWORD -> keyword
        TokenType.TYPE -> this.type
        TokenType.FUNCTION -> function
        TokenType.STRING -> string
        TokenType.NUMBER -> number
        TokenType.COMMENT -> comment
        TokenType.ANNOTATION -> annotation
        TokenType.IDENT -> ident
        else -> text
    }

    companion object {
        val Dark = Palette(
            name = "dark",
            bg = 0xFF16181D.toInt(),
            gutterBg = 0xFF16181D.toInt(),
            gutterLine = 0xFF2A2E36.toInt(),
            lineNo = 0xFF6F7785.toInt(),
            lineNoActive = 0xFFD0D4DB.toInt(),
            text = 0xFFD5D9E0.toInt(),
            ident = 0xFFDFE2E7.toInt(),
            keyword = 0xFFC99BF0.toInt(),
            type = 0xFFE8C27A.toInt(),
            function = 0xFF7DB7F5.toInt(),
            string = 0xFFA6D38A.toInt(),
            number = 0xFFEBA676.toInt(),
            comment = 0xFF969EAC.toInt(),
            annotation = 0xFFF28B94.toInt(),
            lens = 0xFF9CA8BC.toInt(),
            selection = 0xFF263D66.toInt(),
            occurrence = 0xFF283040.toInt(),
            flash = 0xFF283754.toInt(),
            currentLine = 0xFF1D2026.toInt(),
            scrollbar = 0x66FFFFFF,
        )

        val Light = Palette(
            name = "light",
            bg = 0xFFFFFFFF.toInt(),
            gutterBg = 0xFFF6F7F9.toInt(),
            gutterLine = 0xFFE3E6EB.toInt(),
            lineNo = 0xFF7F8692.toInt(),
            lineNoActive = 0xFF1F2328.toInt(),
            text = 0xFF24292F.toInt(),
            ident = 0xFF1F2328.toInt(),
            keyword = 0xFF8A2BB0.toInt(),
            type = 0xFF8A5300.toInt(),
            function = 0xFF1F5BBF.toInt(),
            string = 0xFF1A7230.toInt(),
            number = 0xFFA8460D.toInt(),
            comment = 0xFF5F6773.toInt(),
            annotation = 0xFFB8233A.toInt(),
            lens = 0xFF55627A.toInt(),
            selection = 0xFFD3E2FF.toInt(),
            occurrence = 0xFFE6EBF3.toInt(),
            flash = 0xFFDCE7FF.toInt(),
            currentLine = 0xFFF2F5F9.toInt(),
            scrollbar = 0x55000000,
        )
    }
}
