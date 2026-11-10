package com.repolens.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.repolens.app.core.Family
import com.repolens.app.core.IndexLimits
import com.repolens.app.core.IndexProgress
import com.repolens.app.core.Languages
import com.repolens.app.core.Occurrence
import com.repolens.app.core.RepoDownloader
import com.repolens.app.core.RepoException
import com.repolens.app.core.RepoInfo
import com.repolens.app.core.RepoRef
import com.repolens.app.core.SourceText
import com.repolens.app.core.Symbol
import com.repolens.app.core.SymbolIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

data class Location(val path: String, val line: Int, val col: Int = 0, val length: Int = 0)

class RecentRepo(val owner: String, val repo: String, val branch: String, val openedAt: Long, val bytes: Long) {
    val fullName get() = "$owner/$repo"
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
    /** Non-fatal: e.g. "Navigation limited to N of M files". */
    var indexNote by mutableStateOf<String?>(null)
    var doc by mutableStateOf<OpenDoc?>(null)
    var lenses by mutableStateOf<Map<Int, String>>(emptyMap())
    var lensSymbols: Map<Int, Symbol> = emptyMap()
    var selection by mutableStateOf<WordSelection?>(null)
    var sheet by mutableStateOf<NavSheet?>(null)
    var viewRequest by mutableStateOf<ViewRequest?>(null)
    /**
     * The last [viewRequest] the code view applied. Kept here, not in the view, so a code view
     * created again (the activity was recreated for a font-scale or locale change) doesn't
     * apply it a second time and jump away from where the user was reading.
     */
    var appliedRequestId = -1L
    /** Where the code view was when it went away (path and first visible line), to restore it. */
    var lastTop: Pair<String, Int>? = null
    var message by mutableStateOf<String?>(null)
    val history = mutableStateListOf<Location>()
}

class AppViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, SavedStateHandle())

    private val prefs = app.getSharedPreferences("repolens", Context.MODE_PRIVATE)
    private val crashStore = CrashReporter.store(app)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Replaceable in tests (local HTTP server, small limits, failing loaders). */
    internal var downloader = RepoDownloader(File(app.filesDir, "repos"))
    internal var docLoader: (File, String) -> OpenDoc = { root, path -> DocLoader.load(root, path) }
    internal var indexLimits: () -> IndexLimits = { IndexLimits.forHeap() }

    var input by mutableStateOf("")
    var busyMessage by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var recents by mutableStateOf(loadRecents())
        private set
    var workspace by mutableStateOf<Workspace?>(null)
        private set

    /** The report of a crash in the previous run, until the user dismisses it. */
    var crashReport by mutableStateOf<String?>(null)
        private set

    var textSizeSp by mutableStateOf(prefs.getFloat("textSize", 11f).let { if (it.isNaN()) 11f else it })
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

    /** Last line of defence: an exception that escapes a coroutine becomes a message, not a crash. */
    private val handler = CoroutineExceptionHandler { _, e -> onUnexpected("Something went wrong", e) }

    init {
        launchSafe("startup") {
            val report = withContext(Dispatchers.IO) {
                try { downloader.sweepLeftovers() } catch (e: Exception) { Log.w(TAG, "sweep failed", e) }
                crashStore.pending()
            }
            crashReport = report
            // The process was killed in the background and Android recreated the activity: reopen
            // the repository that was open, unless the app crashed while showing it last time.
            val restore = saved.get<String>(KEY_REPO)
            if (restore != null && workspace == null && busyMessage == null) {
                val crashed = crashStore.crashedRepo(report)
                if (crashed != null && crashed.equals(restore, ignoreCase = true)) {
                    saved.remove<String>(KEY_REPO)
                    error = "RepoLens closed unexpectedly while $restore was open, so it wasn't reopened automatically."
                } else {
                    open(restore, restoreFile = saved.get<String>(KEY_FILE))
                }
            }
        }
    }

    private fun launchSafe(what: String, onError: (Throwable) -> Unit = {}, block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(handler) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "$what failed", e)
                try { onError(e) } catch (e2: Throwable) { onUnexpected(what, e2) }
            }
        }

    private fun onUnexpected(what: String, e: Throwable) {
        Log.e(TAG, what, e)
        val msg = "$what: ${describe(e)}"
        val ws = workspace
        if (ws != null) ws.message = msg else { busyMessage = null; error = msg }
    }

    private fun describe(e: Throwable): String = when (e) {
        is RepoException -> e.message ?: "Download failed."
        is OutOfMemoryError -> "Not enough memory on this phone."
        else -> e.message?.let { "$it (${e.javaClass.simpleName})" } ?: e.javaClass.simpleName
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // ===================== crash report =====================

    fun dismissCrashReport() {
        crashReport = null
        launchSafe("clear crash report") { withContext(Dispatchers.IO) { crashStore.clear() } }
    }

    // ===================== repositories =====================

    fun open(text: String = input, refresh: Boolean = false, restoreFile: String? = null) {
        val ref = try { RepoRef.parse(text) } catch (e: Exception) { null }
        if (ref == null) {
            error = "That doesn't look like a GitHub repository link. Try https://github.com/owner/repo"
            return
        }
        input = "https://github.com/${ref.fullName}"
        error = null
        openJob?.cancel()
        openJob = launchSafe("open", onError = { e -> busyMessage = null; error = describe(e) }) {
            busyMessage = "Opening ${ref.fullName}…"
            CrashReporter.breadcrumb(getApplication(), "Opening ${ref.fullName}")
            val cached = if (refresh) null else withContext(Dispatchers.IO) { downloader.cached(ref) }
            val info = cached ?: downloader.download(ref) { msg -> onMain { if (busyMessage != null) busyMessage = msg } }
            busyMessage = "Reading files…"
            val paths = withContext(Dispatchers.IO) { SymbolIndex.listFiles(info.dir) }
            if (paths.isEmpty()) throw RepoException("This repository is empty.")
            rememberRecent(info)
            val ws = Workspace(info, paths)
            workspace = ws
            busyMessage = null
            saved[KEY_REPO] = info.ref.fullName
            CrashReporter.breadcrumb(getApplication(), "Browsing ${info.ref.fullName}")
            startIndexing(ws)
            // a /blob/feature/x/src/A.kt link parses as ref "feature" + path "x/src/A.kt"; the
            // branch turned out to be "feature/x", so the file is src/A.kt
            val linked = ref.path?.let { p -> listOfNotNull(ref.ref, p).joinToString("/").removePrefix(info.branch + "/") }
            val first = restoreFile?.takeIf { it in paths }
                ?: linked?.takeIf { it in paths }
                ?: ref.path?.takeIf { it in paths }
                ?: paths.firstOrNull { it.equals("README.md", true) }
                ?: paths.firstOrNull { it.startsWith("README", true) && '/' !in it }
                ?: paths.first()
            openFile(first, null, pushHistory = false)
        }
    }

    /**
     * "Share → RepoLens". Only a text containing a GitHub repository link replaces what is open:
     * sharing anything else must not close the repository the user is reading.
     */
    fun openShared(text: String) {
        val link = text.take(4000)
        val ref = try { RepoRef.parse(link) } catch (e: Exception) { null }
        val ws = workspace
        if (ref == null && ws != null) {
            ws.message = "The shared text doesn't contain a GitHub repository link."
            return
        }
        input = link
        if (ref != null) closeRepo()
        open(link)
    }

    /** "Download latest" of a recent repository: the branch it was opened at, not the default one. */
    fun refreshRecent(r: RecentRepo) {
        val branch = r.branch.takeIf { it.isNotBlank() && it != "HEAD" }
        open("https://github.com/${r.fullName}" + (branch?.let { "/tree/$it" } ?: ""), refresh = true)
    }

    fun cancelOpen() {
        openJob?.cancel()
        busyMessage = null
    }

    fun closeRepo() {
        indexJob?.cancel(); docJob?.cancel(); refsJob?.cancel()
        workspace = null
        saved.remove<String>(KEY_REPO); saved.remove<String>(KEY_FILE)
        CrashReporter.breadcrumb(getApplication(), null)
        recents = loadRecents()
    }

    fun deleteRecent(r: RecentRepo) {
        val list = recents.filterNot { it.fullName == r.fullName }
        saveRecents(list)
        recents = list
        launchSafe("delete") { withContext(Dispatchers.IO) { downloader.delete(RepoRef(r.owner, r.repo)) } }
    }

    private val pendingProgress = AtomicReference<IndexProgress?>()

    /** Called from indexing threads: coalesces updates and applies them on the main thread. */
    private fun postProgress(ws: Workspace, p: IndexProgress) {
        if (pendingProgress.getAndSet(p) == null) {
            mainHandler.post {
                val q = pendingProgress.getAndSet(null)
                if (q != null && ws.index == null && ws.indexError == null && workspace === ws) ws.indexProgress = q
            }
        }
    }

    private fun startIndexing(ws: Workspace) {
        indexJob?.cancel()
        indexJob = launchSafe("index", onError = { e ->
            ws.indexProgress = null
            ws.indexError = if (e is OutOfMemoryError) {
                "Not enough memory to index this repository — you can still browse and read every file."
            } else {
                "Indexing failed: ${describe(e)}"
            }
        }) {
            val index = try {
                SymbolIndex.build(ws.info.dir, indexLimits()) { p -> postProgress(ws, p) }
            } catch (e: OutOfMemoryError) {
                System.gc() // the partial index is already unreachable
                throw e
            }
            ws.index = index
            ws.indexNote = index.note
            ws.indexProgress = null
            refreshLenses(ws)
            // re-evaluate a selection made while indexing
            ws.selection?.let { s -> select(ws, s.line, s.col, s.word) }
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
        docJob = launchSafe("open file", onError = { e ->
            ws.lenses = emptyMap(); ws.lensSymbols = emptyMap()
            ws.doc = OpenDoc(path, Languages.PLAIN, null, note = "Couldn't open this file: ${describe(e)}")
        }) {
            val doc = withContext(Dispatchers.Default) { docLoader(ws.info.dir, path) }
            ws.lenses = emptyMap(); ws.lensSymbols = emptyMap()
            ws.doc = doc
            saved[KEY_FILE] = path
            CrashReporter.breadcrumb(getApplication(), "Browsing ${ws.info.ref.fullName}, file $path")
            reveal?.let { request(ws, it, restoreTop) }
            refreshLenses(ws)
        }
    }

    private fun refreshLenses(ws: Workspace) {
        val doc = ws.doc ?: return
        val index = ws.index ?: return
        if (!showLenses) { ws.lenses = emptyMap(); ws.lensSymbols = emptyMap(); return }
        launchSafe("reference counts") {
            val (labels, syms) = withContext(Dispatchers.Default) {
                val labels = HashMap<Int, String>()
                val syms = HashMap<Int, Symbol>()
                for (s in index.lensSymbols(doc.path)) {
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
        ws.selection = try {
            val site = index.definitionAt(doc.path, line, col)
            val defs = index.definitionsFor(word, doc.path, line)
            val count = site?.let { index.referenceCount(it) } ?: defs.firstOrNull()?.let { index.referenceCount(it) }
            WordSelection(word, doc.path, line, col, site, defs, count, indexReady = true)
        } catch (e: Exception) {
            Log.e(TAG, "select failed", e)
            WordSelection(word, doc.path, line, col, null, emptyList(), null, indexReady = true)
        }
    }

    fun clearSelection() {
        workspace?.selection = null
    }

    fun goToDefinition() {
        val ws = workspace ?: return
        val sel = ws.selection ?: return
        val defs = sel.definitions.filter { it != sel.site }
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
        refsJob = launchSafe("definitions", onError = { e -> ws.sheet = NavSheet("Definitions of $word", "Couldn't list them: ${describe(e)}") }) {
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
        refsJob = launchSafe("references", onError = { e -> ws.sheet = NavSheet("References to $word", "Couldn't search: ${describe(e)}") }) {
            val textSearch = symbol == null
            val occ: List<Occurrence> = if (symbol != null) {
                withContext(Dispatchers.Default) { index.referencesTo(symbol, limit = MAX_LISTED_REFS) }
            } else {
                val family = ws.doc?.language?.family
                index.searchWord(word, family?.takeIf { it != Family.DATA })
            }
            val defs = if (symbol != null) index.definitionsFor(word, symbol.file.path, symbol.line).filter { it.name == symbol.name && it.kind == symbol.kind || it == symbol } else emptyList()
            val total = if (symbol != null && occ.size >= MAX_LISTED_REFS) index.referenceCount(symbol) else occ.size
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
                    total > occ.size -> "$total references · showing the first ${occ.size}, in $fileCount files"
                    else -> "${occ.size} ${if (occ.size == 1) "reference" else "references"} in $fileCount ${if (fileCount == 1) "file" else "files"}"
                },
                definitions = defFiles,
                folders = folders,
                note = if (textSearch) "No definition of “$word” was found in this repo (a library name, parameter or local variable?), so these are text matches." else null,
            )
        }
    }

    private fun key(path: String, line: Int, col: Int) = "$path:$line:$col"

    private fun groupByFile(root: File, occ: List<Occurrence>, labels: Map<String, String>, keepOrder: Boolean): List<RefFile> {
        val byFile = LinkedHashMap<String, MutableList<Occurrence>>()
        for (o in occ) byFile.getOrPut(o.file.path) { ArrayList() }.add(o)
        val paths = if (keepOrder) byFile.keys.toList() else byFile.keys.sorted()
        return paths.map { path ->
            val lines = try { SourceText.readLines(File(root, path)) } catch (e: Exception) { null } ?: emptyList()
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
        val top = try { topLineProvider().coerceAtLeast(0) } catch (e: Exception) { 0 }
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
        if (sp.isNaN() || sp.isInfinite()) return
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

    private fun loadRecents(): List<RecentRepo> = try {
        prefs.getString("recents", "").orEmpty().lines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 5) null else RecentRepo(p[0], p[1], p[2], p[3].toLongOrNull() ?: 0, p[4].toLongOrNull() ?: 0)
        }.distinctBy { it.fullName.lowercase() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveRecents(list: List<RecentRepo>) {
        prefs.edit().putString("recents", list.joinToString("\n") { "${it.owner}\t${it.repo}\t${it.branch}\t${it.openedAt}\t${it.bytes}" }).apply()
    }

    private companion object {
        const val TAG = "RepoLens"
        const val KEY_REPO = "openRepo"
        const val KEY_FILE = "openFile"
        /** A references sheet lists at most this many results (the count above it is exact). */
        const val MAX_LISTED_REFS = 5000
    }

    fun isProse(doc: OpenDoc?) = doc != null && doc.language == Languages.PLAIN &&
        (doc.name.endsWith(".md", true) || doc.name.endsWith(".txt", true) || doc.name.endsWith(".rst", true) || doc.name.startsWith("LICENSE", true) || doc.name.startsWith("README", true))
}
