package com.repolens.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.repolens.app.AppViewModel
import com.repolens.app.RecentRepo
import com.repolens.app.core.RepoDownloader

private val CardShape = RoundedCornerShape(16.dp)

private val Examples = listOf(
    "square/retrofit" to "Type-safe HTTP client · Java",
    "pallets/flask" to "Web micro-framework · Python",
    "gin-gonic/gin" to "HTTP web framework · Go",
)

@Composable
fun HomeScreen(vm: AppViewModel) {
    val focus = LocalFocusManager.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val busy = vm.busyMessage != null
    val colors = MaterialTheme.colorScheme
    // Back while a repository is downloading cancels the download instead of leaving the app.
    BackHandler(enabled = busy) { vm.cancelOpen() }

    Surface(color = colors.background, contentColor = colors.onBackground, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
            ) {
                item { BrandHeader() }

                item {
                    Spacer(Modifier.height(32.dp))
                    OutlinedTextField(
                        value = vm.input,
                        onValueChange = { vm.input = it; vm.error = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("GitHub repository URL") },
                        placeholder = { Text("https://github.com/owner/repo") },
                        singleLine = true,
                        readOnly = busy,
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = colors.outline,
                            focusedContainerColor = colors.surface,
                            unfocusedContainerColor = colors.surface,
                            errorContainerColor = colors.surface,
                            unfocusedLabelColor = colors.onSurfaceVariant,
                            unfocusedPlaceholderColor = colors.onSurfaceVariant,
                            focusedPlaceholderColor = colors.onSurfaceVariant,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = { if (!busy) { focus.clearFocus(); vm.open() } }),
                        trailingIcon = {
                            if (busy) return@OutlinedTextField
                            if (vm.input.isNotEmpty()) {
                                IconButton(onClick = { vm.input = ""; vm.error = null }) { Icon(Icons.Default.Close, "Clear") }
                            } else {
                                IconButton(onClick = { clipboard.getText()?.text?.let { vm.input = it.trim() } }) {
                                    Icon(AppIcons.Paste, "Paste")
                                }
                            }
                        },
                        isError = vm.error != null,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (busy) {
                        BusyCard(vm.busyMessage.orEmpty(), onCancel = { vm.cancelOpen() })
                    } else {
                        Button(
                            onClick = { focus.clearFocus(); vm.open() },
                            enabled = vm.input.isNotBlank(),
                            colors = primaryButtonColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("Open repository", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)) }
                    }
                    vm.error?.let { ErrorCard(it) }
                    vm.crashReport?.let { report ->
                        CrashReportCard(report, onCopy = { clipboard.setText(AnnotatedString(report)) }, onDismiss = { vm.dismissCrashReport() })
                    }
                }

                if (vm.recents.isNotEmpty()) {
                    item { SectionHeader("Recent repositories", top = 40.dp) }
                    itemsIndexed(vm.recents, key = { _, r -> r.fullName }) { i, r ->
                        ListCardItem(index = i, count = vm.recents.size) { RecentRow(vm, r, enabled = !busy) }
                    }
                } else if (!busy) {
                    item { SectionHeader("Try an example", top = 40.dp) }
                    itemsIndexed(Examples) { i, (name, about) ->
                        ListCardItem(index = i, count = Examples.size) {
                            ExampleRow(name, about) { vm.input = "https://github.com/$name"; vm.open() }
                        }
                    }
                }

                item { Spacer(Modifier.height(40.dp)); Tips() }
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(AppIcons.Code, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.width(16.dp))
        Column {
            Text("RepoLens", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Read any public GitHub repo like an IDE",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, top: androidx.compose.ui.unit.Dp) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = top, bottom = 12.dp, start = 4.dp),
    )
}

/** One row of a grouped card list: rounded outer corners on the first/last row, dividers between. */
@Composable
private fun ListCardItem(index: Int, count: Int, content: @Composable () -> Unit) {
    val r = 16.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) r else 0.dp, topEnd = if (index == 0) r else 0.dp,
        bottomStart = if (index == count - 1) r else 0.dp, bottomEnd = if (index == count - 1) r else 0.dp,
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            content()
            if (index < count - 1) HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun LeadingTile(icon: ImageVector, tint: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun BusyCard(message: String, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(16.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            TextButton(onClick = onCancel) { Text("Cancel", color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Spacer(Modifier.height(16.dp))
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Icon(Icons.Default.Warning, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Shown after a crash so the user can send us the stack trace. */
@Composable
private fun CrashReportCard(report: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    // The exception and where it happened: enough to recognise it, not a wall of trace.
    val preview = remember(report) {
        report.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("RepoLens crash report") && !it.startsWith("Time:") }
            .take(3).toList()
    }
    Spacer(Modifier.height(16.dp))
    Surface(color = colors.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)) {
            Row(Modifier.padding(end = 8.dp)) {
                Icon(Icons.Default.Info, null, tint = colors.tertiary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("RepoLens crashed last time", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Copy the report into a GitHub issue so it can be fixed.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    )
                    if (preview.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainer)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            preview.forEach {
                                Text(it, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant,
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { onCopy(); copied = true }) {
                    Icon(AppIcons.Copy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (copied) "Copied" else "Copy")
                }
            }
        }
    }
}

/** "owner/" quiet, "repo" prominent: one text so the full name stays a single readable string. */
@Composable
private fun repoName(fullName: String): AnnotatedString {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        val slash = fullName.indexOf('/')
        withStyle(SpanStyle(color = muted, fontWeight = FontWeight.Normal)) { append(fullName.substring(0, slash + 1)) }
        append(fullName.substring(slash + 1))
    }
}

@Composable
private fun RecentRow(vm: AppViewModel, r: RecentRepo, enabled: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { vm.input = "https://github.com/${r.fullName}"; vm.open() }
            .heightIn(min = 72.dp)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeadingTile(AppIcons.Folder, RepoTheme.colors.folder)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(repoName(r.fullName), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    r.branch,
                    RepoDownloader.formatBytes(r.bytes),
                    if (r.openedAt > 0) relativeTime(r.openedAt) else null,
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled) {
                Icon(Icons.Default.MoreVert, "More", tint = if (enabled) colors.onSurfaceVariant else colors.outline)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Download latest") },
                    leadingIcon = { Icon(Icons.Default.Refresh, null) },
                    onClick = { menu = false; vm.refreshRecent(r) },
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

/** "just now", "5 min ago", "3 h ago", "yesterday", "9 days ago", or a date. */
internal fun relativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - then).coerceAtLeast(0)
    val min = 60_000L
    val hour = 60 * min
    val day = 24 * hour
    return when {
        d < min -> "just now"
        d < hour -> "${d / min} min ago"
        d < day -> "${d / hour} h ago"
        d < 2 * day -> "yesterday"
        d < 30 * day -> "${d / day} days ago"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(then))
    }
}

@Composable
private fun ExampleRow(name: String, about: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeadingTile(AppIcons.Code, MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(repoName(name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Tips() {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text("How it works", style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        listOf(
            "Tap a name to go to its definition or find references.",
            "Tap “N references” above a function to see who uses it.",
            "Long lines don’t wrap: swipe sideways, pinch to zoom.",
        ).forEach {
            Row(Modifier.padding(vertical = 4.dp)) {
                Box(Modifier.padding(top = 8.dp).size(5.dp).clip(RoundedCornerShape(50)).background(colors.primary))
                Spacer(Modifier.width(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}
