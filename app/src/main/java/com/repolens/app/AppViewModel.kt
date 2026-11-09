package com.repolens.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.repolens.app.core.Family
import com.repolens.app.core.IndexProgress
import com.repolens.app.core.Language
import com.repolens.app.core.Languages
import com.repolens.app.core.LexedLine
import com.repolens.app.core.Lexer
import com.repolens.app.core.Occurrence
import com.repolens.app.core.RepoDownloader
import com.repolens.app.core.RepoException
import com.repolens.app.core.RepoInfo
import com.repolens.app.core.RepoRef
import com.repolens.app.core.SourceText
import com.repolens.app.core.Symbol
import com.repolens.app.core.SymbolIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Location(val path: String, val line: Int, val col: Int = 0, val length: Int = 0)

class RecentRepo(val owner: String, val repo: String, val branch: String, val openedAt: Long, val bytes: Long) {
    val fullName get() = "$owner/$repo"
}

class OpenDoc(
    val path: String,
    val language: Language,
    val lines: List<LexedLine>?,
    val imageFile: File? = null,
    val note: String? = null,
) {
    val name get() = path.substringAfterLast('/')
    val dir get() = path.substringBeforeLast('/', "")
}

/** A one-shot instruction for the code view: reveal a location, or restore a scroll position. */
class ViewRequest(val id: Long, val line: Int, val col: Int, val length: Int, val restoreTop: Boolean)

class WordSelection(
    val word: String,
    val path: String,
    val line: Int,
    val col: Int,
    /** Non-null when the tapped word is itself a definition. */
    val site: Symbol?,
    val definitions: List<Symbol>,
    val referenceCount: Int?,
    val indexReady: Boolean,
)

class RefItem(val path: String, val line: Int, val col: Int, val length: Int, val preview: String, val previewCol: Int, val label: String? = null)
class RefFile(val path: String, val items: List<RefItem>) { val name get() = path.substringAfterLast('/') }
class RefFolder(val dir: String, val files: List<RefFile>)

class NavSheet(
    val title: String,
    val subtitle: String,
    val definitions: List<RefFile> = emptyList(),
    val folders: List<RefFolder> = emptyList(),
    val loading: Boolean = false,
    val note: String? = null,
)

class Workspace(val info: RepoInfo, val paths: List<String>) {
    var index by mutableStateOf<SymbolIndex?>(null)
    var indexProgress by mutableStateOf<IndexProgress?>(IndexProgress("Preparing", 0, 0))
    var indexError by mutableStateOf<String?>(null)
    var doc by mutableStateOf<OpenDoc?>(null)
    var lenses by mutableStateOf<Map<Int, String>>(emptyMap())
    var lensSymbols: Map<Int, Symbol> = emptyMap()
    var selection by mutableStateOf<WordSelection?>(null)
    var sheet by mutableStateOf<NavSheet?>(null)
    var viewRequest by mutableStateOf<ViewRequest?>(null)
    var message by mutableStateOf<String?>(null)
    val history = mutableStateListOf<Location>()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("repolens", Context.MODE_PRIVATE)
    private val downloader = RepoDownloader(File(app.filesDir, "repos"))

    var input by mutableStateOf("")
    var busyMessage by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var recents by mutableStateOf(loadRecents())
        private set
    var workspace by mutableStateOf<Workspace?>(null)
        private set

    var textSizeSp by mutableStateOf(prefs.getFloat("textSize", 11f))
        private set
    var wrapCode by mutableStateOf(prefs.getBoolean("wrapCode", false))
        private set
    var showLenses by mutableStateOf(prefs.getBoolean("showLenses", true))
        private set

    /** Supplied by the code view so history can remember where you were. */
    var topLineProvider: () -> Int = { 0 }

    private var openJob: Job? = null
    private var indexJob: Job? = null
    private var docJob: Job? = null
    private var refsJob: Job? = null
    private var requestIds = 0L

    // ===================== repositories =====================

    fun open(text: String = input, refresh: Boolean = false) {
        val ref = RepoRef.parse(text)
        if (ref == null) {
            error = "That doesn't look like a GitHub repository link. Try https://github.com/owner/repo"
            return
        }
        input = "https://github.com/${ref.fullName}"
        error = null
        openJob?.cancel()
        openJob = viewModelScope.launch {
            try {
                busyMessage = "Opening ${ref.fullName}…"
                val cached = if (refresh) null else downloader.cached(ref)
                val info = cached ?: downloader.download(ref) { msg -> busyMessage = msg }
                busyMessage = "Reading files…"
                val paths = withContext(Dispatchers.IO) { SymbolIndex.listFiles(info.dir) }
                if (paths.isEmpty()) throw RepoException("This repository is empty.")
                rememberRecent(info)
                val ws = Workspace(info, paths)
                workspace = ws
                busyMessage = null
                startIndexing(ws)
                val first = ref.path?.takeIf { it in paths }
                    ?: paths.firstOrNull { it.equals("README.md", true) }
                    ?: paths.firstOrNull { it.startsWith("README", true) && '/' !in it }
                    ?: paths.first()
                openFile(first, null, pushHistory = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busyMessage = null
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun cancelOpen() {
        openJob?.cancel()
        busyMessage = null
    }

    fun closeRepo() {
        indexJob?.cancel(); docJob?.cancel(); refsJob?.cancel()
        workspace = null
        recents = loadRecents()
    }

    fun deleteRecent(r: RecentRepo) {
        downloader.delete(RepoRef(r.owner, r.repo))
        val list = recents.filterNot { it.fullName == r.fullName }
        saveRecents(list)
        recents = list
    }

    private fun startIndexing(ws: Workspace) {
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            try {
                val index = SymbolIndex.build(ws.info.dir) { p -> ws.indexProgress = p }
                ws.index = index
                ws.indexProgress = null
                refreshLenses(ws)
                // re-evaluate a selection made while indexing
                ws.selection?.let { s -> select(ws, s.line, s.col, s.word) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ws.indexProgress = null
                ws.indexError = "Indexing failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    // ===================== files =====================

    fun openFile(path: String, reveal: Location?, pushHistory: Boolean = true, restoreTop: Boolean = false) {
        val ws = workspace ?: return
        if (pushHistory) pushCurrentLocation(ws)
        ws.selection = null
        ws.sheet = null
        val current = ws.doc
        if (current != null && current.path == path) {
            reveal?.let { request(ws, it, restoreTop) }
            return
        }
        docJob?.cancel()
        docJob = viewModelScope.launch {
            val doc = withContext(Dispatchers.Default) { loadDoc(ws.info.dir, path) }
            ws.lenses = emptyMap(); ws.lensSymbols = emptyMap()
            ws.doc = doc
            reveal?.let { request(ws, it, restoreTop) }
            refreshLenses(ws)
        }
    }

    private fun loadDoc(root: File, path: String): OpenDoc {
        val file = File(root, path)
        val name = path.substringAfterLast('/')
        val lang = Languages.forFileName(name)
        if (SourceText.isImage(name)) return OpenDoc(path, lang, null, imageFile = file)
        if (SourceText.isKnownBinary(name)) return OpenDoc(path, lang, null, note = "Binary file (${RepoDownloader.formatBytes(file.length())}) — nothing to show.")
        if (file.length() > SourceText.MAX_DISPLAY_BYTES) return OpenDoc(path, lang, null, note = "This file is ${RepoDownloader.formatBytes(file.length())} — too large to display.")
        val lines = SourceText.readLines(file) ?: return OpenDoc(path, lang, null, note = "Binary file — nothing to show.")
        return OpenDoc(path, lang, Lexer(lang).lexAll(lines))
    }

    private fun refreshLenses(ws: Workspace) {
        val doc = ws.doc ?: return
        val index = ws.index ?: return
        if (!showLenses) { ws.lenses = emptyMap(); ws.lensSymbols = emptyMap(); return }
        viewModelScope.launch {
            val (labels, syms) = withContext(Dispatchers.Default) {
                val labels = HashMap<Int, String>()
                val syms = HashMap<Int, Symbol>()
                for (s in index.definitionsInFile(doc.path)) {
                    if (!s.kind.showsReferenceLens || syms.containsKey(s.line)) continue
                    val n = index.referenceCount(s)
                    labels[s.line] = if (n == 1) "1 reference" else "$n references"
                    syms[s.line] = s
                }
                labels to syms
            }
            if (ws.doc === doc) { ws.lensSymbols = syms; ws.lenses = labels }
        }
    }

    // ===================== selection & navigation =====================

    fun onWordTapped(line: Int, col: Int, word: String) {
        val ws = workspace ?: return
        ws.sheet = null
        select(ws, line, col, word)
    }

    private fun select(ws: Workspace, line: Int, col: Int, word: String) {
        val doc = ws.doc ?: return
        val index = ws.index
        if (index == null) {
            ws.selection = WordSelection(word, doc.path, line, col, null, emptyList(), null, indexReady = false)
            return
        }
        val site = index.definitionAt(doc.path, line, col)
        val defs = index.definitionsFor(word, doc.path, line)
        val count = site?.let { index.referenceCount(it) } ?: defs.firstOrNull()?.let { index.referenceCount(it) }
        ws.selection = WordSelection(word, doc.path, line, col, site, defs, count, indexReady = true)
    }

    fun clearSelection() {
        workspace?.selection = null
    }

    fun goToDefinition() {
        val ws = workspace ?: return
        val sel = ws.selection ?: return
        val defs = sel.definitions.filter { it !== sel.site }
        when {
            defs.isEmpty() -> ws.message = "No other definition of “${sel.word}” in this repository."
            defs.size == 1 || defs.all { it.file == defs[0].file } || defs[0].file.path == sel.path ->
                navigate(Location(defs[0].file.path, defs[0].line, defs[0].col, defs[0].name.length))
            else -> showDefinitionChooser(ws, sel.word, defs)
        }
    }

    private fun showDefinitionChooser(ws: Workspace, word: String, defs: List<Symbol>) {
        ws.sheet = NavSheet("Definitions of $word", "${defs.size} candidates, closest first", loading = true)
        refsJob?.cancel()
        refsJob = viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                groupByFile(ws.info.dir, defs.map { Occurrence(it.file, it.line, it.col, it.name.length) }, labels = defs.associate { key(it.file.path, it.line, it.col) to it.kind.label }, keepOrder = true)
            }
            ws.sheet = NavSheet("Definitions of $word", "${defs.size} candidates, closest first", definitions = files)
        }
    }

    fun showReferencesForSelection() {
        val ws = workspace ?: return
        val sel = ws.selection ?: return
        val sym = sel.site ?: sel.definitions.firstOrNull()
        showReferences(ws, sel.word, sym)
    }

    fun onLensTapped(line: Int) {
        val ws = workspace ?: return
        val sym = ws.lensSymbols[line] ?: return
        ws.selection = null
        showReferences(ws, sym.name, sym)
    }

    private fun showReferences(ws: Workspace, word: String, symbol: Symbol?) {
        val index = ws.index ?: run { ws.message = "Still indexing — try again in a moment."; return }
        ws.sheet = NavSheet("References to $word", "Searching…", loading = true)
        refsJob?.cancel()
        refsJob = viewModelScope.launch {
            val textSearch = symbol == null
            val occ: List<Occurrence> = if (symbol != null) {
                withContext(Dispatchers.Default) { index.referencesTo(symbol) }
            } else {
                val family = ws.doc?.language?.family
                index.searchWord(word, family?.takeIf { it != Family.DATA })
            }
            val defs = if (symbol != null) index.definitionsFor(word, symbol.file.path, symbol.line).filter { it.name == symbol.name && it.kind == symbol.kind || it === symbol } else emptyList()
            val (defFiles, folders) = withContext(Dispatchers.IO) {
                val d = groupByFile(ws.info.dir, defs.map { Occurrence(it.file, it.line, it.col, it.name.length) }, labels = defs.associate { key(it.file.path, it.line, it.col) to it.kind.label }, keepOrder = true)
                val files = groupByFile(ws.info.dir, occ, emptyMap(), keepOrder = false)
                d to files.groupBy { it.path.substringBeforeLast('/', "") }.toSortedMap().map { (dir, fs) -> RefFolder(dir, fs) }
            }
            val fileCount = folders.sumOf { it.files.size }
            ws.sheet = NavSheet(
                title = "References to $word",
                subtitle = when {
                    occ.isEmpty() -> "No uses found"
                    else -> "${occ.size} ${if (occ.size == 1) "reference" else "references"} in $fileCount ${if (fileCount == 1) "file" else "files"}"
                },
                definitions = defFiles,
                folders = folders,
                note = if (textSearch) "“$word” isn't defined in this repo (probably comes from a library), so these are text matches." else null,
            )
        }
    }

    private fun key(path: String, line: Int, col: Int) = "$path:$line:$col"

    private fun groupByFile(root: File, occ: List<Occurrence>, labels: Map<String, String>, keepOrder: Boolean): List<RefFile> {
        val byFile = LinkedHashMap<String, MutableList<Occurrence>>()
        for (o in occ) byFile.getOrPut(o.file.path) { ArrayList() }.add(o)
        val paths = if (keepOrder) byFile.keys.toList() else byFile.keys.sorted()
        return paths.map { path ->
            val lines = SourceText.readLines(File(root, path)) ?: emptyList()
            RefFile(path, byFile.getValue(path).sortedWith(compareBy({ it.line }, { it.col })).map { o ->
                val raw = lines.getOrElse(o.line) { "" }
                val indent = raw.length - raw.trimStart().length
                var preview = raw.trim()
                var pc = o.col - indent
                if (preview.length > 160) {
                    val from = (pc - 60).coerceAtLeast(0)
                    preview = (if (from > 0) "…" else "") + preview.substring(from, minOf(preview.length, from + 150))
                    pc = pc - from + if (from > 0) 1 else 0
                }
                RefItem(path, o.line, o.col, o.length, preview, pc, labels[key(path, o.line, o.col)])
            })
        }
    }

    fun navigate(to: Location) {
        val ws = workspace ?: return
        openFile(to.path, to, pushHistory = true)
        ws.sheet = null
    }

    private fun pushCurrentLocation(ws: Workspace) {
        val doc = ws.doc ?: return
        val top = topLineProvider().coerceAtLeast(0)
        ws.history.add(Location(doc.path, top))
        if (ws.history.size > 100) ws.history.removeAt(0)
    }

    /** Back button inside the workspace. Returns false when there is nothing left to go back to. */
    fun back(): Boolean {
        val ws = workspace ?: return false
        when {
            ws.sheet != null -> ws.sheet = null
            ws.selection != null -> ws.selection = null
            ws.history.isNotEmpty() -> {
                val loc = ws.history.removeAt(ws.history.lastIndex)
                openFile(loc.path, loc, pushHistory = false, restoreTop = true)
            }
            else -> return false
        }
        return true
    }

    private fun request(ws: Workspace, loc: Location, restoreTop: Boolean) {
        ws.viewRequest = ViewRequest(++requestIds, loc.line, loc.col, loc.length, restoreTop)
    }

    // ===================== settings =====================

    fun updateTextSize(sp: Float) {
        textSizeSp = sp
        prefs.edit().putFloat("textSize", sp).apply()
    }

    fun toggleWrap() {
        wrapCode = !wrapCode
        prefs.edit().putBoolean("wrapCode", wrapCode).apply()
    }

    fun toggleLenses() {
        showLenses = !showLenses
        prefs.edit().putBoolean("showLenses", showLenses).apply()
        workspace?.let { ws -> if (showLenses) refreshLenses(ws) else { ws.lenses = emptyMap(); ws.lensSymbols = emptyMap() } }
    }

    // ===================== recents =====================

    private fun rememberRecent(info: RepoInfo) {
        val r = RecentRepo(info.ref.owner, info.ref.repo, info.branch, System.currentTimeMillis(), info.bytes)
        val list = listOf(r) + recents.filterNot { it.fullName.equals(r.fullName, true) }
        saveRecents(list.take(20))
        recents = list.take(20)
    }

    private fun loadRecents(): List<RecentRepo> =
        prefs.getString("recents", "").orEmpty().lines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 5) null else RecentRepo(p[0], p[1], p[2], p[3].toLongOrNull() ?: 0, p[4].toLongOrNull() ?: 0)
        }

    private fun saveRecents(list: List<RecentRepo>) {
        prefs.edit().putString("recents", list.joinToString("\n") { "${it.owner}\t${it.repo}\t${it.branch}\t${it.openedAt}\t${it.bytes}" }).apply()
    }

    fun isProse(doc: OpenDoc?) = doc != null && doc.language == Languages.PLAIN &&
        (doc.name.endsWith(".md", true) || doc.name.endsWith(".txt", true) || doc.name.endsWith(".rst", true) || doc.name.startsWith("LICENSE", true) || doc.name.startsWith("README", true))
}
