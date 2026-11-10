package com.repolens.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.graphics.drawable.toDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import com.repolens.app.ui.DarkColors
import com.repolens.app.ui.LightColors
import com.repolens.app.ui.HomeScreen
import com.repolens.app.ui.RepoLensTheme
import com.repolens.app.ui.WorkspaceScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        applySystemBars(isNight())
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            // The activity handles uiMode changes itself (no recreation), so follow them here.
            LaunchedEffect(dark) { applySystemBars(dark) }
            RepoLensTheme(darkTheme = dark) {
                val ws = vm.workspace
                if (ws == null) {
                    HomeScreen(vm)
                } else {
                    BackHandler { if (!vm.back()) vm.closeRepo() }
                    WorkspaceScreen(vm, ws)
                }
            }
        }
    }

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Status/navigation bar icons dark on light and light on dark; window background to match. */
    private fun applySystemBars(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
        window.setBackgroundDrawable((if (dark) DarkColors else LightColors).background.toArgb().toDrawable())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Share → RepoLens" from the GitHub app or a browser. */
    private fun handleShare(intent: Intent?) {
        // Extras come from other apps: a malformed parcel or a styled CharSequence must not crash us.
        val text = try {
            if (intent?.action != Intent.ACTION_SEND) return
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank()) return
        vm.openShared(text)
    }

    private companion object {
        // The scrims androidx.activity uses for 3-button navigation.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
