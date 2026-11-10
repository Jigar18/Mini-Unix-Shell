package com.repolens.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.repolens.app.AppViewModel
import com.repolens.app.Location
import com.repolens.app.NavSheet
import com.repolens.app.OpenDoc
import com.repolens.app.RefFile
import com.repolens.app.RefItem
import com.repolens.app.WordSelection
import com.repolens.app.Workspace
import com.repolens.app.core.LexedLine
import com.repolens.app.core.RepoDownloader
import com.repolens.app.core.Symbol
import com.repolens.app.core.SymbolKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(vm: AppViewModel, ws: Workspace) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showSearch by remember { mutableStateOf(false) }
    val codeView = remember { mutableStateOf<CodeView?>(null) }

    LaunchedEffect(ws.message) {
        val m = ws.message ?: return@LaunchedEffect
        ws.message = null
        snackbar.showSnackbar(m)
    }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen, // swiping inside the code must scroll it, not open the drawer
        drawerContent = {
            FileDrawer(ws, open = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open) { path ->
                vm.openFile(path, null)
                scope.launch { drawerState.close() }
            }
        },
    ) {
        Scaffold(
            containerColor = RepoTheme.colors.editor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
            topBar = {
                WorkspaceTopBar(
                    vm, ws,
                    onMenu = { scope.launch { drawerState.open() } },
                    onSearch = { showSearch = true },
                    onFit = { codeView.value?.fitTextSize()?.let { vm.updateTextSize(it) } },
                )
            },
        ) { padding ->
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)),
            ) {
                Column(Modifier.fillMaxSize()) {
                    IndexingStrip(ws)
                    val doc = ws.doc
                    val docLines = doc?.lines
                    val image = doc?.imageFile
                    doc?.banner?.let { InlineNotice(it, Icons.Default.Info) }
                    when {
                        doc == null -> Box(Modifier.fillMaxSize())
                        docLines != null -> CodeHost(vm, ws, doc, docLines, codeView)
                        image != null -> ImageDoc(doc, image)
                        else -> CenteredNote(AppIcons.File, doc.note.orEmpty())
                    }
                }
                SelectionBar(vm, ws, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    ws.sheet?.let { NavSheetView(vm, ws, it) }
    if (showSearch) SymbolSearchDialog(vm, ws) { showSearch = false }
}

// ------------------------------------------------------------------ top bar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceTopBar(vm: AppViewModel, ws: Workspace, onMenu: () -> Unit, onSearch: () -> Unit, onFit: () -> Unit) {
    var sizeMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val doc = ws.doc
    val colors = MaterialTheme.colorScheme
    Column {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.surfaceContainerLow,
            navigationIconContentColor = colors.onSurface,
            titleContentColor = colors.onSurface,
            actionIconContentColor = colors.onSurfaceVariant,
        ),
        navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Files") } },
        title = {
            Column {
                Text(doc?.name ?: ws.info.ref.repo, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    doc?.dir?.ifEmpty { null } ?: ws.info.ref.fullName,
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis,
                )
            }
        },
        actions = {
            if (ws.history.isNotEmpty()) {
                IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to previous location") }
            }
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search symbols") }
            Box {
                IconButton(onClick = { sizeMenu = true }) { Icon(AppIcons.TextSize, "Text size") }
                DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { vm.updateTextSize((vm.textSizeSp - 1f).coerceAtLeast(CodeView.MIN_SP)) }) { Text("A−") }
                        Text("%.1f".format(vm.textSizeSp), modifier = Modifier.width(56.dp), textAlign = TextAlign.Center, fontFamily = Mono, fontSize = 14.sp)
                        OutlinedButton(onClick = { vm.updateTextSize((vm.textSizeSp + 1f).coerceAtMost(CodeView.MAX_SP)) }) { Text("A+") }
                    }
                    DropdownMenuItem(text = { Text("Fit lines to screen width") }, onClick = { sizeMenu = false; onFit() })
                    DropdownMenuItem(text = { Text("Reset (11)") }, onClick = { sizeMenu = false; vm.updateTextSize(11f) })
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Wrap long lines") },
                        trailingIcon = { Checkbox(checked = vm.wrapCode, onCheckedChange = null) },
                        onClick = { vm.toggleWrap() },
                    )
                    DropdownMenuItem(
                        text = { Text("Show reference counts") },
                        trailingIcon = { Checkbox(checked = vm.showLenses, onCheckedChange = null) },
                        onClick = { vm.toggleLenses() },
                    )
                }
            }
            Box {
                IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(text = { Text("Close repository") }, onClick = { moreMenu = false; vm.closeRepo() })
                }
            }
        },
    )
    HorizontalDivider(color = colors.outlineVariant)
    }
}

@Composable
private fun IndexingStrip(ws: Workspace) {
    val p = ws.indexProgress
    val colors = MaterialTheme.colorScheme
    if (p != null) {
        Column(Modifier.fillMaxWidth().background(colors.surfaceContainerLow).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)) {
            val frac = if (p.total > 0) p.done.toFloat() / p.total else 0f
            Text(
                if (p.total > 0) "${p.phase}… ${p.done} / ${p.total} files" else "Preparing index…",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            val m = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
            if (p.total > 0) LinearProgressIndicator(progress = { frac }, modifier = m, trackColor = colors.surfaceContainerHighest, drawStopIndicator = {})
            else LinearProgressIndicator(modifier = m, trackColor = colors.surfaceContainerHighest)
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
    ws.indexError?.let { InlineNotice(it, Icons.Default.Warning, error = true) }
    ws.indexNote?.let { InlineNotice(it, Icons.Default.Info) }
}

/** A quiet one-line notice above the code (index limits, truncated file, index errors). */
@Composable
private fun InlineNotice(text: String, icon: ImageVector, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val bg = if (error) colors.errorContainer else colors.surfaceContainer
    val fg = if (error) colors.onErrorContainer else colors.onSurfaceVariant
    Column {
        Row(
            Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = if (error) fg else colors.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = fg)
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

/** Centered explanation for files that aren't shown as text (binary, too big, failed to load). */
@Composable
private fun CenteredNote(icon: ImageVector, text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
    }
}

// ------------------------------------------------------------------ code

@Composable
private fun CodeHost(vm: AppViewModel, ws: Workspace, doc: OpenDoc, lines: List<LexedLine>, holder: MutableState<CodeView?>) {
    val wrap = vm.wrapCode || vm.isProse(doc)
    val lenses = ws.lenses
    val request = ws.viewRequest
    val hasSelection = ws.selection != null
    val textSize = vm.textSizeSp
    val dark = RepoTheme.colors.isDark
    val path by rememberUpdatedState(doc.path)
    // The view model must not keep the view (and with it the activity and the last file's text)
    // after the code view leaves the screen, e.g. when the repository is closed. Where it was
    // scrolled to is remembered, for a code view created again after the activity is recreated.
    DisposableEffect(Unit) {
        onDispose {
            holder.value?.let { v -> v.firstVisibleLine().takeIf { it >= 0 }?.let { ws.lastTop = path to it } }
            vm.topLineProvider = { 0 }; holder.value = null
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            CodeView(ctx).also { v ->
                holder.value = v
                vm.topLineProvider = { v.firstVisibleLine() }
                v.listener = object : CodeView.Listener {
                    override fun onWordTapped(line: Int, col: Int, word: String) = vm.onWordTapped(line, col, word)
                    override fun onLensTapped(line: Int) = vm.onLensTapped(line)
                    override fun onBlankTapped() { vm.clearSelection(); v.clearSelection() }
                    override fun onTextSizeChanged(sp: Float) = vm.updateTextSize(sp)
                }
                ws.lastTop?.let { (p, line) -> if (p == doc.path) v.scrollToTopLine(line) }
                ws.lastTop = null
            }
        },
        update = { v ->
            v.contentDescription = "Code of ${doc.name}, ${lines.size} lines"
            v.setDarkTheme(dark)
            v.setTextSize(textSize)
            v.setContent(lines, lenses, wrap)
            if (!hasSelection && v.userSelection) v.clearSelection()
            if (request != null && request.id != ws.appliedRequestId) {
                ws.appliedRequestId = request.id
                if (request.restoreTop) v.scrollToTopLine(request.line) else v.reveal(request.line, request.col, request.length)
            }
        },
    )
}

@Composable
private fun ImageDoc(doc: OpenDoc, file: java.io.File) {
    var bmp by remember(doc.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(doc.path) {
        bmp = withContext(Dispatchers.IO) { decodeImageSafely(file) }
    }
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), doc.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else CenteredNote(AppIcons.Image, "Couldn't display this image.")
    }
}

/**
 * Decodes an image for display, or null if it is corrupt, empty or can't fit in memory: the
 * bitmap is downsampled to at most 2048 px per side and ~16 MB.
 */
internal fun decodeImageSafely(f: java.io.File): android.graphics.Bitmap? = try {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    if (o.outWidth <= 0 || o.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (o.outWidth / sample > 2048 || o.outHeight / sample > 2048 ||
            (o.outWidth.toLong() / sample) * (o.outHeight.toLong() / sample) * 4 > 16L * 1024 * 1024) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
} catch (e: OutOfMemoryError) {
    null
} catch (e: Exception) {
    null
}

// ------------------------------------------------------------------ selection bar

@Composable
private fun SelectionBar(vm: AppViewModel, ws: Workspace, modifier: Modifier) {
    val sel = ws.selection
    var last by remember { mutableStateOf<WordSelection?>(null) }
    if (sel != null) last = sel
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    AnimatedVisibility(
        visible = sel != null && ws.sheet == null,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        val s = sel ?: last ?: return@AnimatedVisibility
        val colors = MaterialTheme.colorScheme
        Surface(
            color = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.navigationBarsPadding().padding(12.dp).widthIn(max = 560.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.word, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(s.word)); ws.message = "Copied “${s.word}”" }) {
                        Icon(AppIcons.Copy, "Copy", tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Default.Close, "Close", tint = colors.onSurfaceVariant) }
                }
                Text(describe(s), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp))
                Spacer(Modifier.height(16.dp))
                Row(Modifier.padding(end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val others = s.definitions.count { it != s.site }
                    val m = Modifier.weight(1f).heightIn(min = 48.dp)
                    val pad = PaddingValues(horizontal = 12.dp)
                    if (s.site == null) {
                        Button(onClick = { vm.goToDefinition() }, enabled = s.indexReady && others > 0, modifier = m,
                            colors = primaryButtonColors(), contentPadding = pad) { Text("Go to definition", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        OutlinedButton(onClick = { vm.showReferencesForSelection() }, enabled = s.indexReady, modifier = m,
                            colors = secondaryButtonColors(), border = BorderStroke(1.dp, colors.outline), contentPadding = pad) {
                            Text(if (s.definitions.isEmpty()) "Find usages" else "References" + (s.referenceCount?.let { " ($it)" } ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Button(onClick = { vm.showReferencesForSelection() }, modifier = m, colors = primaryButtonColors(), contentPadding = pad) {
                            Text("Find references" + (s.referenceCount?.let { " ($it)" } ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (others > 0) OutlinedButton(onClick = { vm.goToDefinition() }, modifier = m, colors = secondaryButtonColors(),
                            border = BorderStroke(1.dp, colors.outline), contentPadding = pad) {
                            Text(if (others == 1) "Other definition" else "$others others", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private fun describe(s: WordSelection): String {
    if (!s.indexReady) return "Indexing the repository… definitions and references will be ready in a moment."
    s.site?.let { site ->
        val refs = s.referenceCount?.let { if (it == 1) " · 1 reference" else " · $it references" } ?: ""
        return "${site.kind.label.replaceFirstChar { it.uppercase() }} defined here$refs"
    }
    val d = s.definitions
    return when {
        d.isEmpty() -> "No definition found in this repository: it may come from a library, or be a parameter or local variable."
        d.size == 1 -> "${d[0].kind.label.replaceFirstChar { it.uppercase() }} in ${d[0].file.path}:${d[0].line + 1}"
        else -> "${d.size} possible definitions · closest: ${d[0].file.path}:${d[0].line + 1}"
    }
}

// ------------------------------------------------------------------ references / definitions sheet

private sealed interface SheetRow {
    class Section(val title: String) : SheetRow
    class Folder(val dir: String) : SheetRow
    class FileRow(val file: RefFile) : SheetRow
    class Item(val item: RefItem) : SheetRow
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavSheetView(vm: AppViewModel, ws: Workspace, sheet: NavSheet) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val rows = remember(sheet) {
        buildList {
            if (sheet.definitions.isNotEmpty() && sheet.folders.isNotEmpty()) add(SheetRow.Section(if (sheet.definitions.sumOf { it.items.size } == 1) "Definition" else "Definitions"))
            sheet.definitions.forEach { f -> add(SheetRow.FileRow(f)); f.items.forEach { add(SheetRow.Item(it)) } }
            if (sheet.folders.isNotEmpty() && sheet.definitions.isNotEmpty()) add(SheetRow.Section("References"))
            sheet.folders.forEach { folder ->
                add(SheetRow.Folder(folder.dir))
                folder.files.forEach { f -> add(SheetRow.FileRow(f)); f.items.forEach { add(SheetRow.Item(it)) } }
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = { ws.sheet = null },
        sheetState = state,
        containerColor = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
            Text(sheet.title, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(sheet.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            sheet.note?.let {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = colors.primary, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        if (sheet.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
        HorizontalDivider(color = colors.outlineVariant)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        ) {
            items(rows.size) { i ->
                when (val r = rows[i]) {
                    is SheetRow.Section -> Text(
                        r.title.uppercase(), style = MaterialTheme.typography.labelMedium, color = colors.primary,
                        letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                    )
                    is SheetRow.Folder -> Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Folder, null, tint = RepoTheme.colors.folder, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(r.dir.ifEmpty { "(repository root)" } + "/", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            fontFamily = Mono, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    }
                    is SheetRow.FileRow -> Row(
                        Modifier.fillMaxWidth().padding(start = 32.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.File, null, tint = fileTint(r.file.name), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(r.file.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Text("${r.file.items.size}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.surfaceContainerHighest).padding(horizontal = 8.dp, vertical = 1.dp))
                    }
                    is SheetRow.Item -> RefItemRow(r.item) { vm.navigate(Location(r.item.path, r.item.line, r.item.col, r.item.length)) }
                }
            }
        }
    }
}

@Composable
private fun RefItemRow(item: RefItem, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val text = remember(item, colors) {
        buildAnnotatedString {
            val p = item.preview
            val s = item.previewCol.coerceIn(0, p.length)
            val e = (s + item.length).coerceIn(s, p.length)
            append(p.substring(0, s))
            withStyle(SpanStyle(color = colors.onPrimaryContainer, background = colors.primaryContainer, fontWeight = FontWeight.Bold)) { append(p.substring(s, e)) }
            append(p.substring(e))
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 44.dp).padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${item.line + 1}", fontFamily = Mono, fontSize = 12.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            item.label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.tertiary) }
            Text(text, fontFamily = Mono, fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurface, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ file tree drawer

private class TreeNode(val name: String, val path: String, val isDir: Boolean)
private class TreeRow(val node: TreeNode, val depth: Int)

/** Above this many files the drawer's tree and filter are computed off the main thread. */
internal const val INLINE_PATHS = 5000

private fun filterPaths(paths: List<String>, q: String): List<String> =
    paths.filter { it.contains(q, ignoreCase = true) }
        .sortedWith(compareBy({ !it.substringAfterLast('/').contains(q, true) }, { it.length }))
        .take(500)

private fun buildTree(paths: List<String>): Map<String, List<TreeNode>> {
    val dirs = HashMap<String, MutableSet<String>>()
    val files = HashMap<String, MutableList<String>>()
    for (p in paths) {
        val parts = p.split('/')
        var parent = ""
        for (i in 0 until parts.size - 1) {
            val dir = if (parent.isEmpty()) parts[i] else "$parent/${parts[i]}"
            dirs.getOrPut(parent) { sortedSetOf() }.add(dir)
            parent = dir
        }
        files.getOrPut(parent) { ArrayList() }.add(p)
    }
    val keys = dirs.keys + files.keys
    return keys.associateWith { k ->
        val ds = dirs[k].orEmpty().map { TreeNode(it.substringAfterLast('/'), it, true) }.sortedBy { it.name.lowercase() }
        val fs = files[k].orEmpty().map { TreeNode(it.substringAfterLast('/'), it, false) }.sortedBy { it.name.lowercase() }
        ds + fs
    }
}

@Composable
private fun FileDrawer(ws: Workspace, open: Boolean, onOpen: (String) -> Unit) {
    // Small trees are built right away; a big one (up to 300k paths) only once the drawer is
    // first opened, and off the main thread.
    val small = ws.paths.size <= INLINE_PATHS
    var tree by remember(ws) { mutableStateOf(if (small) buildTree(ws.paths) else null) }
    LaunchedEffect(ws, open) {
        if (open && tree == null) tree = withContext(Dispatchers.Default) { buildTree(ws.paths) }
    }
    val expanded = remember(ws) { mutableStateMapOf<String, Boolean>() }
    var filter by remember { mutableStateOf("") }
    val current = ws.doc?.path
    val listState = rememberLazyListState()

    // Reveal the open file in the tree.
    LaunchedEffect(current) {
        current ?: return@LaunchedEffect
        var d = current.substringBeforeLast('/', "")
        while (d.isNotEmpty()) { expanded[d] = true; d = d.substringBeforeLast('/', "") }
    }
    val rows by remember(ws) {
        derivedStateOf {
            val out = ArrayList<TreeRow>()
            fun walk(dir: String, depth: Int) {
                for (n in tree?.get(dir).orEmpty()) {
                    out.add(TreeRow(n, depth))
                    if (n.isDir && expanded[n.path] == true) walk(n.path, depth + 1)
                }
            }
            walk("", 0)
            out
        }
    }
    LaunchedEffect(open, tree != null) {
        if (open && tree != null && filter.isEmpty()) {
            val i = rows.indexOfFirst { it.node.path == current }
            if (i > 3) listState.scrollToItem(i - 3)
        }
    }

    val colors = MaterialTheme.colorScheme
    ModalDrawerSheet(
        drawerContainerColor = colors.surfaceContainerLow,
        drawerContentColor = colors.onSurface,
        drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(0.88f),
    ) {
        Column(Modifier.fillMaxHeight().statusBarsPadding().imePadding()) {
            Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.onSurfaceVariant, fontWeight = FontWeight.Normal)) { append(ws.info.ref.owner + "/") }
                        append(ws.info.ref.repo)
                    },
                    fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val idx = ws.index
                Spacer(Modifier.height(2.dp))
                Text(
                    "${ws.info.branch} · ${ws.paths.size} files · ${RepoDownloader.formatBytes(ws.info.bytes)}" +
                        (idx?.let { " · ${it.symbolCount} symbols" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("fileFilter"),
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("Filter files", style = MaterialTheme.typography.bodyMedium) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono, color = colors.onSurface),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = colors.outline,
                        unfocusedPlaceholderColor = colors.onSurfaceVariant,
                        focusedPlaceholderColor = colors.onSurfaceVariant,
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                    ),
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Default.Close, "Clear") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                )
            }
            HorizontalDivider(color = colors.outlineVariant)
            val rowShape = RoundedCornerShape(10.dp)
            if (filter.isNotBlank()) {
                val q = filter.trim()
                // big repos: wait for typing to pause, then search off the main thread (the
                // previous results stay on screen meanwhile)
                var found by remember(ws) { mutableStateOf<List<String>?>(null) }
                LaunchedEffect(ws, q) {
                    if (small) return@LaunchedEffect
                    delay(150)
                    found = withContext(Dispatchers.Default) { filterPaths(ws.paths, q) }
                }
                val matches = if (small) remember(q) { filterPaths(ws.paths, q) } else found
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(matches.orEmpty()) { p ->
                        val selected = p == current
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(rowShape)
                                .background(if (selected) colors.primaryContainer else Color.Transparent)
                                .clickable { onOpen(p) }
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.File, null, tint = fileTint(p), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(p.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            }
                            Text(p.substringBeforeLast('/', "/"), style = MaterialTheme.typography.labelSmall, fontFamily = Mono,
                                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.padding(start = 30.dp))
                        }
                    }
                    if (matches?.isEmpty() == true) item {
                        Text("No matching files", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(rows, key = { it.node.path }) { row ->
                        val n = row.node
                        val isOpen = n.isDir && expanded[n.path] == true
                        val selected = n.path == current
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .clip(rowShape)
                                .background(if (selected) colors.primaryContainer else Color.Transparent)
                                .clickable { if (n.isDir) expanded[n.path] = !isOpen else onOpen(n.path) }
                                .heightIn(min = 40.dp)
                                .padding(start = (8 + row.depth * 16).dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (n.isDir) {
                                Icon(
                                    if (isOpen) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp),
                                )
                            } else Spacer(Modifier.width(18.dp))
                            Spacer(Modifier.width(6.dp))
                            val icon: ImageVector = if (n.isDir) (if (isOpen) AppIcons.FolderOpen else AppIcons.Folder) else AppIcons.File
                            Icon(icon, null, tint = if (n.isDir) RepoTheme.colors.folder else fileTint(n.name), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(n.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ symbol search

@Composable
private fun SymbolSearchDialog(vm: AppViewModel, ws: Workspace, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Symbol>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val index = ws.index
    LaunchedEffect(query, index) {
        delay(120)
        results = if (index == null) emptyList() else withContext(Dispatchers.Default) { index.searchSymbols(query) { !isActive } }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = colors.background, contentColor = colors.onBackground, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().background(colors.surfaceContainerLow).padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
                    TextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.weight(1f).focusRequester(focus).testTag("symbolQuery"),
                        placeholder = { Text("Go to function, class, variable…", style = MaterialTheme.typography.bodyLarge) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono, color = colors.onSurface),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedPlaceholderColor = colors.onSurfaceVariant, unfocusedPlaceholderColor = colors.onSurfaceVariant,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    )
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear", tint = colors.onSurfaceVariant) }
                }
                HorizontalDivider(color = colors.outlineVariant)
                val hint = when {
                    index == null -> "The repository is still being indexed…"
                    query.isBlank() -> "${index.symbolCount} symbols in ${index.files.size} source files"
                    results.isEmpty() -> "No symbols match “$query”"
                    else -> null
                }
                hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(results) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onDismiss()
                                vm.navigate(Location(s.file.path, s.line, s.col, s.name.length))
                            }.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            KindBadge(s.kind)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${s.file.path}:${s.line + 1}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.StartEllipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KindBadge(kind: SymbolKind) {
    val c = RepoTheme.colors
    val (letter, color) = when (kind) {
        SymbolKind.FUNCTION -> "f" to c.kindFunction
        SymbolKind.CLASS -> "T" to c.kindClass
        SymbolKind.VARIABLE -> "v" to c.kindVariable
        SymbolKind.MACRO -> "M" to c.kindMacro
    }
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = BADGE_ALPHA)),
        contentAlignment = Alignment.Center,
    ) { Text(letter, color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

/** Opacity of a kind badge's tinted background (ContrastTest checks the letter against it). */
const val BADGE_ALPHA = 0.14f
