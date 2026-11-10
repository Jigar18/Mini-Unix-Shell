package com.repolens.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.repolens.app.AppViewModel
import com.repolens.app.RecentRepo
import com.repolens.app.core.RepoDownloader

@Composable
fun HomeScreen(vm: AppViewModel) {
    val focus = LocalFocusManager.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val busy = vm.busyMessage != null

    Box(
        Modifier
            .fillMaxSize()
            .background(UiColors.Bg)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(UiColors.SurfaceHigh),
                        contentAlignment = Alignment.Center,
                    ) { Icon(AppIcons.Code, null, tint = UiColors.Accent, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("RepoLens", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("Read any public GitHub repo like an IDE", color = UiColors.Muted, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(28.dp))

                OutlinedTextField(
                    value = vm.input,
                    onValueChange = { vm.input = it; vm.error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("GitHub repository URL") },
                    placeholder = { Text("https://github.com/owner/repo") },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { focus.clearFocus(); vm.open() }),
                    trailingIcon = {
                        if (vm.input.isNotEmpty()) {
                            IconButton(onClick = { vm.input = "" }, enabled = !busy) { Icon(Icons.Default.Close, "Clear") }
                        } else {
                            IconButton(onClick = { clipboard.getText()?.text?.let { vm.input = it.trim() } }, enabled = !busy) {
                                Icon(AppIcons.Paste, "Paste")
                            }
                        }
                    },
                    isError = vm.error != null,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { focus.clearFocus(); vm.open() }, enabled = !busy && vm.input.isNotBlank()) {
                        Text("Open repository")
                    }
                    if (busy) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { vm.cancelOpen() }) { Text("Cancel") }
                    }
                }

                if (busy) {
                    Spacer(Modifier.height(16.dp))
                    Surface(color = UiColors.SurfaceHigh, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(vm.busyMessage.orEmpty(), fontSize = 14.sp)
                        }
                    }
                }
                vm.crashReport?.let { report -> CrashReportCard(report, onCopy = { clipboard.setText(AnnotatedString(report)) }, onDismiss = { vm.dismissCrashReport() }) }
                vm.error?.let {
                    Spacer(Modifier.height(16.dp))
                    Surface(color = UiColors.Error.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
                        Text(it, color = UiColors.Error, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(14.dp))
                    }
                }

                if (vm.recents.isEmpty() && !busy) {
                    Spacer(Modifier.height(28.dp))
                    Text("Try one", color = UiColors.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("square/retrofit", "pallets/flask", "gin-gonic/gin").forEach { r ->
                            SuggestionChip(onClick = { vm.input = "https://github.com/$r"; vm.open() }, label = { Text(r, fontSize = 12.sp) })
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Tips()
                }

                if (vm.recents.isNotEmpty()) {
                    Spacer(Modifier.height(32.dp))
                    Text("Recent", color = UiColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                }
            }
            items(vm.recents, key = { it.fullName }) { r -> RecentRow(vm, r, enabled = !busy) }
            if (vm.recents.isNotEmpty()) item { Spacer(Modifier.height(24.dp)); Tips() }
        }
    }
}

/** Shown after a crash so the user can send us the stack trace. Deliberately plain. */
@Composable
private fun CrashReportCard(report: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    Spacer(Modifier.height(16.dp))
    Surface(color = UiColors.SurfaceHigh, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("RepoLens crashed last time", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Copy the report and paste it into an issue so it can be fixed.",
                color = UiColors.Muted, fontSize = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                report.lineSequence().filter { it.isNotBlank() }.take(8).joinToString("\n"),
                fontFamily = Mono, fontSize = 11.sp, color = UiColors.Muted, maxLines = 8, overflow = TextOverflow.Ellipsis,
            )
            Row {
                TextButton(onClick = { onCopy(); copied = true }) { Text(if (copied) "Copied" else "Copy") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun RecentRow(vm: AppViewModel, r: RecentRepo, enabled: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { vm.input = "https://github.com/${r.fullName}"; vm.open() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Folder, null, tint = UiColors.Accent2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.fullName, fontFamily = Mono, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${r.branch} · ${RepoDownloader.formatBytes(r.bytes)}" + (if (r.openedAt > 0) " · ${DateUtils.getRelativeTimeSpanString(r.openedAt)}" else ""),
                color = UiColors.Muted, fontSize = 12.sp, maxLines = 1,
            )
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Download latest") },
                    leadingIcon = { Icon(Icons.Default.Refresh, null) },
                    onClick = { menu = false; vm.input = "https://github.com/${r.fullName}"; vm.open(refresh = true) },
                )
                DropdownMenuItem(
                    text = { Text("Remove") },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = { menu = false; vm.deleteRecent(r) },
                )
            }
        }
    }
}

@Composable
private fun Tips() {
    Surface(color = UiColors.Surface, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("How it works", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            listOf(
                "Tap any name in the code to select it, then Go to definition or Find references.",
                "Functions and classes show a small “N references” link above them. Tap it to see every file that uses them, grouped by folder.",
                "Lines never wrap. Swipe sideways for long lines, pinch to zoom, or use the text-size menu's Fit option.",
                "The back button returns to where you jumped from.",
            ).forEach {
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("•", color = UiColors.Accent, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(it, color = UiColors.Muted, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}
