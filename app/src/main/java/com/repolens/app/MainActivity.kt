package com.repolens.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.repolens.app.ui.HomeScreen
import com.repolens.app.ui.RepoLensTheme
import com.repolens.app.ui.WorkspaceScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            RepoLensTheme {
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
        val link = text.take(4000)
        vm.input = link
        vm.closeRepo()
        vm.open(link)
    }
}
