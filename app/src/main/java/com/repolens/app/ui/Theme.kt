package com.repolens.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.repolens.app.core.Family
import com.repolens.app.core.Languages

val Mono = FontFamily.Monospace

// ------------------------------------------------------------------ colour scheme
//
// A calm neutral base with one blue accent. Every text/background pair used by the screens is
// checked against WCAG AA in ContrastTest, so change values there too if you change them here.

val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF2457C5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FB),
    onPrimaryContainer = Color(0xFF0E2A66),
    inversePrimary = Color(0xFF8AB4F8),
    secondary = Color(0xFF4F5664),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E7EE),
    onSecondaryContainer = Color(0xFF1A1D23),
    tertiary = Color(0xFF8F6200),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFF1D1),
    onTertiaryContainer = Color(0xFF4A3400),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF1A1D23),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1D23),
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = Color(0xFF4F5664),
    surfaceTint = Color(0xFF2457C5),
    inverseSurface = Color(0xFF2B2F36),
    inverseOnSurface = Color(0xFFF1F3F6),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF8C1D18),
    outline = Color(0xFF737B89),
    outlineVariant = Color(0xFFDADEE5),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE2E5EA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F5F8),
    surfaceContainer = Color(0xFFEEF0F4),
    surfaceContainerHigh = Color(0xFFE7EAEF),
    surfaceContainerHighest = Color(0xFFDFE3E9),
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B2A5C),
    primaryContainer = Color(0xFF233A61),
    onPrimaryContainer = Color(0xFFD8E4FF),
    inversePrimary = Color(0xFF2457C5),
    secondary = Color(0xFFA8AFBB),
    onSecondary = Color(0xFF1A1D23),
    secondaryContainer = Color(0xFF2D323A),
    onSecondaryContainer = Color(0xFFE6E8EC),
    tertiary = Color(0xFFE3B65C),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF3A3220),
    onTertiaryContainer = Color(0xFFF3DDA6),
    background = Color(0xFF121418),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF181B20),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF252A31),
    onSurfaceVariant = Color(0xFFA8AFBB),
    surfaceTint = Color(0xFF8AB4F8),
    inverseSurface = Color(0xFFE6E8EC),
    inverseOnSurface = Color(0xFF1F2329),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF4C1D1D),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF7D8594),
    outlineVariant = Color(0xFF343942),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2D323A),
    surfaceDim = Color(0xFF121418),
    surfaceContainerLowest = Color(0xFF0E1013),
    surfaceContainerLow = Color(0xFF181B20),
    surfaceContainer = Color(0xFF1E2228),
    surfaceContainerHigh = Color(0xFF252A31),
    surfaceContainerHighest = Color(0xFF2D323A),
)

/** Colours the Material scheme has no role for. */
@Immutable
class RepoColors(
    val isDark: Boolean,
    /** Folder icons and other amber accents. */
    val folder: Color,
    /** Symbol kind badges. */
    val kindFunction: Color,
    val kindClass: Color,
    val kindVariable: Color,
    val kindMacro: Color,
    /** Behind the code view (same as its palette background). */
    val editor: Color,
)

val LightRepoColors = RepoColors(
    isDark = false,
    folder = Color(0xFF8F6200),
    kindFunction = Color(0xFF1F5BBF),
    kindClass = Color(0xFF8A5300),
    kindVariable = Color(0xFF1A7230),
    kindMacro = Color(0xFF8A2BB0),
    editor = Color(Palette.Light.bg),
)

val DarkRepoColors = RepoColors(
    isDark = true,
    folder = Color(0xFFE3B65C),
    kindFunction = Color(0xFF7DB7F5),
    kindClass = Color(0xFFE8C27A),
    kindVariable = Color(0xFFA6D38A),
    kindMacro = Color(0xFFC99BF0),
    editor = Color(Palette.Dark.bg),
)

val LocalRepoColors = staticCompositionLocalOf { DarkRepoColors }

object RepoTheme {
    val colors: RepoColors
        @Composable @ReadOnlyComposable get() = LocalRepoColors.current
}

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = t.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = t.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    )
}

/** Monospace text for paths, code previews and symbol names. */
val MonoStyle = TextStyle(fontFamily = Mono)

@Composable
fun RepoLensTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRepoColors provides if (darkTheme) DarkRepoColors else LightRepoColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}

/** Filled button whose disabled state stays readable (≥ 4.5:1) instead of 38 % grey. */
@Composable
fun primaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** Outlined button whose disabled label stays readable. */
@Composable
fun secondaryButtonColors(): ButtonColors = ButtonDefaults.outlinedButtonColors(
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

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
    val Link = icon("Link", "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4L11,7L7,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9L7,15.1c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2L8,11v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4L13,17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z")
    val History = icon("History", "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z")
    val Image = icon("Image", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z")
}

/** Colour that hints at a file's language in the tree (an icon colour: ≥ 3:1 on its background). */
fun fileTint(name: String, dark: Boolean): Color {
    val lang = Languages.forFileName(name)
    val (light, night) = when {
        lang == Languages.KOTLIN -> 0xFF7A3FD1 to 0xFFB794FF
        lang == Languages.JAVA -> 0xFFA0520A to 0xFFE8A55C
        lang == Languages.TYPESCRIPT -> 0xFF1F63B8 to 0xFF6CB0F5
        lang == Languages.JAVASCRIPT -> 0xFF8A6D00 to 0xFFE8D44D
        lang == Languages.PYTHON -> 0xFF2F64A8 to 0xFF7AB4EA
        lang.family == Family.GO -> 0xFF00788A to 0xFF5CCBDB
        lang.family == Family.RUST -> 0xFFA8481A to 0xFFE59A6E
        lang.family == Family.C -> 0xFF3B65A0 to 0xFF8AB0E0
        lang.family == Family.CSHARP -> 0xFF6B3FB0 to 0xFFB693EA
        lang.family == Family.SWIFT -> 0xFFB8401A to 0xFFF48A66
        lang.family == Family.RUBY -> 0xFFB0233A to 0xFFF07A8A
        lang.family == Family.PHP -> 0xFF5658A8 to 0xFFA6A8E6
        lang.family == Family.DART -> 0xFF00769A to 0xFF5CCDEA
        lang.family == Family.SHELL -> 0xFF2E7A2E to 0xFF9CCB7E
        name.endsWith(".md", true) -> 0xFF2F6BA8 to 0xFF8DBDEB
        else -> 0xFF5D6573 to 0xFFA8AFBB
    }
    return Color(if (dark) night else light)
}

@Composable
@ReadOnlyComposable
fun fileTint(name: String): Color = fileTint(name, LocalRepoColors.current.isDark)
