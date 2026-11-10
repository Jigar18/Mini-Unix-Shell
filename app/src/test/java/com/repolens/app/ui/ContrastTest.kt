package com.repolens.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x contrast of the colour pairs the screens actually draw, in both themes:
 * text ≥ 4.5:1, icons/outlines/line numbers ≥ 3:1.
 */
class ContrastTest {
    private class Pair(val what: String, val fg: Color, val bg: Color, val min: Double)

    private fun luminance(c: Color): Double {
        fun ch(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [fg] drawn at [alpha] over an opaque [bg]. */
    private fun over(fg: Color, alpha: Float, bg: Color) = Color(
        fg.red * alpha + bg.red * (1 - alpha), fg.green * alpha + bg.green * (1 - alpha), fg.blue * alpha + bg.blue * (1 - alpha),
    )

    private fun uiPairs(name: String, s: ColorScheme, x: RepoColors): List<Pair> = buildList {
        fun text(what: String, fg: Color, vararg bgs: kotlin.Pair<String, Color>) = bgs.forEach { (n, bg) -> add(Pair("$name $what on $n", fg, bg, 4.5)) }
        fun icon(what: String, fg: Color, vararg bgs: kotlin.Pair<String, Color>) = bgs.forEach { (n, bg) -> add(Pair("$name $what on $n", fg, bg, 3.0)) }
        val surfaces = arrayOf(
            "background" to s.background, "surface" to s.surface, "surfaceContainerLow" to s.surfaceContainerLow,
            "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
            "surfaceContainerHighest" to s.surfaceContainerHighest,
        )
        text("onSurface", s.onSurface, *surfaces)
        text("onBackground", s.onBackground, "background" to s.background)
        // secondary text, placeholders, labels, disabled-but-informative button text, tips, metadata
        text("onSurfaceVariant", s.onSurfaceVariant, *surfaces)
        // text buttons, section labels, links
        text("primary", s.primary, "background" to s.background, "surface" to s.surface, "surfaceContainerLow" to s.surfaceContainerLow, "surfaceContainerHigh" to s.surfaceContainerHigh)
        text("onPrimary", s.onPrimary, "primary" to s.primary)
        // busy card, selected tree row, matched word in reference previews
        text("onPrimaryContainer", s.onPrimaryContainer, "primaryContainer" to s.primaryContainer)
        text("onSecondaryContainer", s.onSecondaryContainer, "secondaryContainer" to s.secondaryContainer)
        text("onErrorContainer", s.onErrorContainer, "errorContainer" to s.errorContainer)
        text("error (field label)", s.error, "surface" to s.surface, "background" to s.background)
        text("tertiary (reference labels)", s.tertiary, "surfaceContainerLow" to s.surfaceContainerLow, "surface" to s.surface)
        text("snackbar text", s.inverseOnSurface, "inverseSurface" to s.inverseSurface)
        text("snackbar action", s.inversePrimary, "inverseSurface" to s.inverseSurface)
        icon("outline", s.outline, "background" to s.background, "surface" to s.surface, "surfaceContainerLow" to s.surfaceContainerLow, "surfaceContainerHigh" to s.surfaceContainerHigh)
        icon("folder icon", x.folder, "surface" to s.surface, "surfaceContainer" to s.surfaceContainer, "surfaceContainerLow" to s.surfaceContainerLow)
        icon("primary icon", s.primary, "surfaceContainer" to s.surfaceContainer)
        icon("tertiary icon", s.tertiary, "surface" to s.surface)
        for (k in listOf(x.kindFunction, x.kindClass, x.kindVariable, x.kindMacro)) {
            text("kind badge $k", k, "its tint" to over(k, BADGE_ALPHA, s.background))
        }
        for (f in listOf("a.kt", "A.java", "a.ts", "a.js", "a.py", "a.go", "a.rs", "a.c", "a.cs", "a.swift", "a.rb", "a.php", "a.dart", "a.sh", "README.md", "notes.txt")) {
            icon("file tint $f", fileTint(f, x.isDark), "surfaceContainerLow" to s.surfaceContainerLow, "primaryContainer" to s.primaryContainer, "surface" to s.surface)
        }
    }

    private fun codePairs(p: Palette): List<Pair> = buildList {
        val c = { v: Int -> Color(v) }
        val tokens = mapOf(
            "text" to p.text, "ident" to p.ident, "keyword" to p.keyword, "type" to p.type, "function" to p.function,
            "string" to p.string, "number" to p.number, "comment" to p.comment, "annotation" to p.annotation, "lens" to p.lens,
        )
        for ((n, v) in tokens) {
            add(Pair("${p.name} code $n on bg", c(v), c(p.bg), 4.5))
            add(Pair("${p.name} code $n on current line", c(v), c(p.currentLine), 4.5))
        }
        // the selected word and its other occurrences are identifiers
        for (n in listOf("text", "ident", "function", "type")) {
            add(Pair("${p.name} code $n on selection", c(tokens.getValue(n)), c(p.selection), 4.5))
            add(Pair("${p.name} code $n on occurrence", c(tokens.getValue(n)), c(p.occurrence), 4.5))
        }
        add(Pair("${p.name} line numbers", c(p.lineNo), c(p.gutterBg), 3.0))
        add(Pair("${p.name} active line number", c(p.lineNoActive), c(p.gutterBg), 4.5))
    }

    @Test fun everyColourPairMeetsWcagAA() {
        val pairs = uiPairs("light", LightColors, LightRepoColors) + uiPairs("dark", DarkColors, DarkRepoColors) +
            codePairs(Palette.Light) + codePairs(Palette.Dark)
        val failures = pairs.map { it to ratio(it.fg, it.bg) }.filter { (p, r) -> r < p.min }
        assertTrue(
            "contrast below WCAG AA:\n" + failures.joinToString("\n") { (p, r) -> "  ${p.what}: %.2f < %.1f".format(r, p.min) },
            failures.isEmpty(),
        )
        assertTrue(pairs.size > 150)
    }

    @Test fun editorBackgroundMatchesThePalette() {
        assertTrue(LightRepoColors.editor == Color(Palette.Light.bg))
        assertTrue(DarkRepoColors.editor == Color(Palette.Dark.bg))
    }
}
