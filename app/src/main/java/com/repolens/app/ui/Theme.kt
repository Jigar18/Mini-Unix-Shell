package com.repolens.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.repolens.app.core.Family
import com.repolens.app.core.Languages

val Mono = FontFamily.Monospace

object UiColors {
    val Bg = Color(0xFF1B1D23)
    val Surface = Color(0xFF21252B)
    val SurfaceHigh = Color(0xFF282C34)
    val Outline = Color(0xFF353B45)
    val Text = Color(0xFFD7DAE0)
    val Muted = Color(0xFF8A94A6)
    val Accent = Color(0xFF61AFEF)
    val Accent2 = Color(0xFFE5C07B)
    val Green = Color(0xFF98C379)
    val Error = Color(0xFFE06C75)
    val Highlight = Color(0xFF3E4F6E)
}

private val scheme = darkColorScheme(
    primary = UiColors.Accent,
    onPrimary = Color(0xFF0B1A2A),
    primaryContainer = Color(0xFF233A55),
    onPrimaryContainer = Color(0xFFD6E9FF),
    secondary = UiColors.Accent2,
    secondaryContainer = Color(0xFF3A3424),
    onSecondaryContainer = Color(0xFFFFE9B8),
    background = UiColors.Bg,
    onBackground = UiColors.Text,
    surface = UiColors.Surface,
    onSurface = UiColors.Text,
    surfaceVariant = UiColors.SurfaceHigh,
    onSurfaceVariant = UiColors.Muted,
    surfaceContainer = UiColors.Surface,
    surfaceContainerLow = UiColors.Surface,
    surfaceContainerHigh = UiColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF2E333C),
    surfaceContainerLowest = UiColors.Bg,
    outline = UiColors.Outline,
    outlineVariant = UiColors.Outline,
    error = UiColors.Error,
)

@Composable
fun RepoLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

/** A few Material icons not in material-icons-core (paths from Material Icons, Apache 2.0). */
object AppIcons {
    val Folder = icon("Folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    val FolderOpen = icon("FolderOpen", "M20,6h-8l-2,-2L4,4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2L22,8c0,-1.1 -0.9,-2 -2,-2zM20,18L4,18L4,8h16v10z")
    val File = icon("File", "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z")
    val Code = icon("Code", "M9.4,16.6L4.8,12l4.6,-4.6L8,6l-6,6 6,6 1.4,-1.4zM14.6,16.6l4.6,-4.6 -4.6,-4.6L16,6l6,6 -6,6 -1.4,-1.4z")
    val Paste = icon("Paste", "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2L5,2c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20L5,20L5,4h2v3h10L17,4h2v16z")
    val TextSize = icon("TextSize", "M9,4v3h5v12h3L17,7h5L22,4L9,4zM3,12h3v7h3v-7h3L12,9L3,9v3z")
    val Copy = icon("Copy", "M16,1L4,1c-1.1,0 -2,0.9 -2,2v14h2L4,3h12L16,1zM19,5L8,5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2L21,7c0,-1.1 -0.9,-2 -2,-2zM19,21L8,21L8,7h11v14z")
}

/** Colour that hints at a file's language in the tree. */
fun fileTint(name: String): Color {
    val lang = Languages.forFileName(name)
    return when {
        lang == Languages.KOTLIN -> Color(0xFFB07CFF)
        lang == Languages.JAVA -> Color(0xFFE5A04B)
        lang == Languages.TYPESCRIPT -> Color(0xFF4F9DEB)
        lang == Languages.JAVASCRIPT -> Color(0xFFE8D44D)
        lang == Languages.PYTHON -> Color(0xFF5FA3E0)
        lang.family == Family.GO -> Color(0xFF56C8D8)
        lang.family == Family.RUST -> Color(0xFFDE8A5A)
        lang.family == Family.C -> Color(0xFF6C9BD2)
        lang.family == Family.CSHARP -> Color(0xFF9B6BD8)
        lang.family == Family.SWIFT -> Color(0xFFF0734A)
        lang.family == Family.RUBY -> Color(0xFFE0475B)
        lang.family == Family.PHP -> Color(0xFF8C8FD6)
        lang.family == Family.DART -> Color(0xFF41C4E6)
        lang.family == Family.SHELL -> Color(0xFF98C379)
        name.endsWith(".md", true) -> Color(0xFF7FB5E8)
        else -> UiColors.Muted
    }
}
