package com.repolens.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
            FileDrawer(ws, open = drawerState.isOpen) { path ->
                vm.openFile(path, null)
                scope.launch { drawerState.close() }
            }
        },
    ) {
        Scaffold(
            containerColor = UiColors.Bg,
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
                    doc?.banner?.let { Text(it, color = UiColors.Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(UiColors.Surface).padding(8.dp)) }
                    when {
                        doc == null -> Box(Modifier.fillMaxSize())
                        docLines != null -> CodeHost(vm, ws, doc, docLines, codeView)
                        image != null -> ImageDoc(doc, image)
                        else -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(doc.note.orEmpty(), color = UiColors.Muted, textAlign = TextAlign.Center)
                        }
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
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = UiColors.Surface),
        navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Files") } },
        title = {
            Column {
                Text(doc?.name ?: ws.info.ref.repo, fontFamily = Mono, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    doc?.dir?.ifEmpty { null } ?: ws.info.ref.fullName,
                    fontSize = 11.sp, color = UiColors.Muted, maxLines = 1, overflow = TextOverflow.StartEllipsis,
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
                        Text("%.1f".format(vm.textSizeSp), modifier = Modifier.width(48.dp), textAlign = TextAlign.Center, fontFamily = Mono, fontSize = 13.sp)
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
}

@Composable
private fun IndexingStrip(ws: Workspace) {
    val p = ws.indexProgress
    if (p != null) {
        Column(Modifier.fillMaxWidth().background(UiColors.Surface).padding(horizontal = 12.dp, vertical = 4.dp)) {
            val frac = if (p.total > 0) p.done.toFloat() / p.total else 0f
            Text(
                if (p.total > 0) "${p.phase}… ${p.done} / ${p.total} files" else "Preparing index…",
                fontSize = 11.sp, color = UiColors.Muted,
            )
            Spacer(Modifier.height(3.dp))
            if (p.total > 0) LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(2.dp))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
    ws.indexError?.let {
        Text(it, color = UiColors.Error, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(UiColors.Surface).padding(8.dp))
    }
    ws.indexNote?.let {
        Text(it, color = UiColors.Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(UiColors.Surface).padding(8.dp))
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
            }
        },
        update = { v ->
            v.setTextSize(textSize)
            v.setContent(lines, lenses, wrap)
            if (!hasSelection && v.userSelection) v.clearSelection()
            if (request != null && request.id != v.lastRequestId) {
                v.lastRequestId = request.id
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
        else Text("Couldn't display this image.", color = UiColors.Muted)
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
        Surface(
            color = UiColors.SurfaceHigh,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 10.dp,
            modifier = Modifier.navigationBarsPadding().padding(10.dp).widthIn(max = 560.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.word, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(s.word)); ws.message = "Copied “${s.word}”" }) {
                        Icon(AppIcons.Copy, "Copy", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Default.Close, "Close") }
                }
                Text(describe(s), color = UiColors.Muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(end = 10.dp))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val others = s.definitions.count { it != s.site }
                    if (s.site == null) {
                        Button(onClick = { vm.goToDefinition() }, enabled = s.indexReady && others > 0) { Text("Go to definition") }
                        OutlinedButton(onClick = { vm.showReferencesForSelection() }, enabled = s.indexReady) {
                            Text(if (s.definitions.isEmpty()) "Find usages" else "References" + (s.referenceCount?.let { " ($it)" } ?: ""))
                        }
                    } else {
                        Button(onClick = { vm.showReferencesForSelection() }) { Text("Find references" + (s.referenceCount?.let { " ($it)" } ?: "")) }
                        if (others > 0) OutlinedButton(onClick = { vm.goToDefinition() }) { Text(if (others == 1) "Other definition" else "$others others") }
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
        d.isEmpty() -> "Not defined in this repository — probably from a library or the language itself."
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
    ModalBottomSheet(
        onDismissRequest = { ws.sheet = null },
        sheetState = state,
        containerColor = UiColors.Surface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(sheet.title, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sheet.subtitle, color = UiColors.Muted, fontSize = 12.5.sp)
            sheet.note?.let { Spacer(Modifier.height(6.dp)); Text(it, color = UiColors.Accent2, fontSize = 12.sp, lineHeight = 16.sp) }
            Spacer(Modifier.height(8.dp))
        }
        if (sheet.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(rows.size) { i ->
                when (val r = rows[i]) {
                    is SheetRow.Section -> Text(
                        r.title.uppercase(), color = UiColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp),
                    )
                    is SheetRow.Folder -> Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Folder, null, tint = UiColors.Accent2, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(r.dir.ifEmpty { "(repository root)" } + "/", color = UiColors.Muted, fontSize = 12.sp, fontFamily = Mono,
                            maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    }
                    is SheetRow.FileRow -> Row(
                        Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 6.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.File, null, tint = fileTint(r.file.name), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(r.file.name, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Text("${r.file.items.size}", fontSize = 11.sp, color = UiColors.Muted,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(UiColors.SurfaceHigh).padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                    is SheetRow.Item -> RefItemRow(r.item) { vm.navigate(Location(r.item.path, r.item.line, r.item.col, r.item.length)) }
                }
            }
        }
    }
}

@Composable
private fun RefItemRow(item: RefItem, onClick: () -> Unit) {
    val text = remember(item) {
        buildAnnotatedString {
            val p = item.preview
            val s = item.previewCol.coerceIn(0, p.length)
            val e = (s + item.length).coerceIn(s, p.length)
            withStyle(SpanStyle(color = UiColors.Text)) { append(p.substring(0, s)) }
            withStyle(SpanStyle(color = Color.White, background = UiColors.Highlight, fontWeight = FontWeight.SemiBold)) { append(p.substring(s, e)) }
            withStyle(SpanStyle(color = UiColors.Text)) { append(p.substring(e)) }
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 28.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${item.line + 1}", fontFamily = Mono, fontSize = 11.5.sp, color = UiColors.Muted, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            item.label?.let { Text(it, fontSize = 10.5.sp, color = UiColors.Accent2) }
            Text(text, fontFamily = Mono, fontSize = 12.5.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ file tree drawer

private class TreeNode(val name: String, val path: String, val isDir: Boolean)
private class TreeRow(val node: TreeNode, val depth: Int)

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
    val tree = remember(ws) { buildTree(ws.paths) }
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
                for (n in tree[dir].orEmpty()) {
                    out.add(TreeRow(n, depth))
                    if (n.isDir && expanded[n.path] == true) walk(n.path, depth + 1)
                }
            }
            walk("", 0)
            out
        }
    }
    LaunchedEffect(open) {
        if (open && filter.isEmpty()) {
            val i = rows.indexOfFirst { it.node.path == current }
            if (i > 3) listState.scrollToItem(i - 3)
        }
    }

    ModalDrawerSheet(
        drawerContainerColor = UiColors.Surface,
        drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(0.88f),
    ) {
        Column(Modifier.fillMaxHeight().statusBarsPadding().imePadding()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
                Text(ws.info.ref.fullName, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val idx = ws.index
                Text(
                    "${ws.info.branch} · ${ws.paths.size} files · ${RepoDownloader.formatBytes(ws.info.bytes)}" +
                        (idx?.let { " · ${it.symbolCount} symbols" } ?: ""),
                    fontSize = 11.5.sp, color = UiColors.Muted,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Filter files", fontSize = 13.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = Mono, color = UiColors.Text),
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Default.Close, "Clear") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                )
            }
            HorizontalDivider(color = UiColors.Outline)
            if (filter.isNotBlank()) {
                val q = filter.trim()
                val matches = remember(q) {
                    ws.paths.filter { it.contains(q, ignoreCase = true) }
                        .sortedWith(compareBy({ !it.substringAfterLast('/').contains(q, true) }, { it.length }))
                        .take(500)
                }
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                    items(matches) { p ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onOpen(p) }
                                .background(if (p == current) UiColors.Highlight.copy(alpha = 0.5f) else Color.Transparent)
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.File, null, tint = fileTint(p), modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(p.substringAfterLast('/'), fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(p.substringBeforeLast('/', "/"), fontSize = 11.sp, color = UiColors.Muted, fontFamily = Mono,
                                maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.padding(start = 23.dp))
                        }
                    }
                    if (matches.isEmpty()) item { Text("No matching files", color = UiColors.Muted, modifier = Modifier.padding(16.dp)) }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(rows, key = { it.node.path }) { row ->
                        val n = row.node
                        val isOpen = n.isDir && expanded[n.path] == true
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { if (n.isDir) expanded[n.path] = !isOpen else onOpen(n.path) }
                                .background(if (n.path == current) UiColors.Highlight.copy(alpha = 0.55f) else Color.Transparent)
                                .padding(start = (8 + row.depth * 14).dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (n.isDir) {
                                Icon(
                                    if (isOpen) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null, tint = UiColors.Muted, modifier = Modifier.size(16.dp),
                                )
                            } else Spacer(Modifier.width(16.dp))
                            Spacer(Modifier.width(4.dp))
                            val icon: ImageVector = if (n.isDir) (if (isOpen) AppIcons.FolderOpen else AppIcons.Folder) else AppIcons.File
                            Icon(icon, null, tint = if (n.isDir) UiColors.Accent2 else fileTint(n.name), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(n.name, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (n.path == current) Color.White else UiColors.Text)
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
        results = if (index == null) emptyList() else withContext(Dispatchers.Default) { index.searchSymbols(query) }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = UiColors.Bg, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().background(UiColors.Surface).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
                    TextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.weight(1f).focusRequester(focus).testTag("symbolQuery"),
                        placeholder = { Text("Go to function, class, variable…") },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 15.sp, color = UiColors.Text),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    )
                }
                when {
                    index == null -> Text("The repository is still being indexed…", color = UiColors.Muted, modifier = Modifier.padding(20.dp))
                    query.isBlank() -> Text("${index.symbolCount} symbols in ${index.files.size} source files", color = UiColors.Muted, modifier = Modifier.padding(20.dp))
                    results.isEmpty() -> Text("No symbols match “$query”", color = UiColors.Muted, modifier = Modifier.padding(20.dp))
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onDismiss()
                                vm.navigate(Location(s.file.path, s.line, s.col, s.name.length))
                            }.padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            KindBadge(s.kind)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontFamily = Mono, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${s.file.path}:${s.line + 1}", fontSize = 11.5.sp, color = UiColors.Muted, maxLines = 1, overflow = TextOverflow.StartEllipsis)
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
    val (letter, color) = when (kind) {
        SymbolKind.FUNCTION -> "f" to Color(0xFF61AFEF)
        SymbolKind.CLASS -> "T" to Color(0xFFE5C07B)
        SymbolKind.VARIABLE -> "v" to Color(0xFF98C379)
        SymbolKind.MACRO -> "M" to Color(0xFFC678DD)
    }
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Text(letter, color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}
