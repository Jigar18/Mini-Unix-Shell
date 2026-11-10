package com.repolens.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

class SourceFile(val id: Int, val path: String, val language: Language) {
    val name: String get() = path.substringAfterLast('/')
    val dir: String get() = path.substringBeforeLast('/', "")
    override fun toString() = path
}

/**
 * A definition. Created on demand from the index's packed arrays, so two lookups of the same
 * definition give equal (but not identical) objects: compare with `==`, never `===`.
 */
class Symbol(
    val name: String,
    val kind: SymbolKind,
    val file: SourceFile,
    val line: Int,
    val col: Int,
    /** Declared inside a function body: only resolvable within its own file. */
    val isLocal: Boolean,
) {
    val isLocalVariable get() = kind == SymbolKind.VARIABLE && isLocal
    override fun equals(other: Any?) =
        other is Symbol && other.file.id == file.id && other.file.path == file.path && other.line == line && other.col == col && other.name == name
    override fun hashCode() = (file.id * 31 + line) * 31 + col
    override fun toString() = "${kind.label} $name @ ${file.path}:${line + 1}"
}

class Occurrence(val file: SourceFile, val line: Int, val col: Int, val length: Int)

data class IndexProgress(val phase: String, val done: Int, val total: Int)

/**
 * Hard caps that keep indexing memory bounded and predictable on a phone. [forHeap] scales them
 * to the heap actually available (`Runtime.maxMemory()`).
 */
class IndexLimits(
    /** Most source files indexed; beyond that a prioritised subset is indexed. */
    val maxFiles: Int,
    /** Most source bytes indexed in total. */
    val maxTotalBytes: Long,
    /** Bigger files are not indexed (still viewable). */
    val maxFileBytes: Long = SourceText.MAX_INDEX_BYTES,
    /** Most stored references; once reached, files later in the index get no references. */
    val maxOccurrences: Int,
    /** Files lexed at the same time (each needs up to ~5 × [maxFileBytes] of transient memory). */
    val parallelism: Int,
) {
    companion object {
        private const val MB = 1024L * 1024

        fun forHeap(
            maxMemory: Long = Runtime.getRuntime().maxMemory(),
            cores: Int = Runtime.getRuntime().availableProcessors(),
        ): IndexLimits {
            val heapMb = maxMemory / MB
            return IndexLimits(
                // ~120 files per MB of heap: 128 MB → 15k files, 256 MB → 30k, capped at 40k
                maxFiles = (heapMb * 120).coerceIn(2_000, 40_000).toInt(),
                // 60% of the heap in source bytes: 128 MB → 77 MB of code, 256 MB → 154 MB
                maxTotalBytes = (maxMemory * 6 / 10).coerceIn(16 * MB, 256 * MB),
                // 8 bytes each, at most 20% of the heap: 128 MB → 3.3M references
                maxOccurrences = (maxMemory / 5 / 8).coerceIn(250_000, 40_000_000).toInt(),
                parallelism = cores.coerceIn(1, if (heapMb < 160) 2 else 4),
            )
        }
    }
}

/**
 * Repository-wide symbol table: where each name is defined and every place it is used.
 * Lookups never cross language [Family] boundaries.
 *
 * Everything is stored in primitive arrays: names are interned once, definitions are packed
 * `file/line/col` longs, and the uses of each name are one sorted `LongArray` with the definition
 * sites already removed. No per-definition or per-use objects are kept.
 */
class SymbolIndex private constructor(
    val root: File,
    /** The indexed source files, sorted by path; `files[i].id == i`. */
    val files: List<SourceFile>,
    /** Source files that could have been indexed (before the [IndexLimits] caps). */
    val candidateFiles: Int,
    /** Non-null when the caps kicked in: shown to the user ("Navigation limited to …"). */
    val note: String?,
    private val names: NameTable,
    // definitions in file order (file, line, then the order the patterns found them)
    private val defSite: LongArray,
    private val defName: IntArray,
    private val defMeta: ByteArray,
    private val fileDefStart: IntArray,
    // definitions grouped by name: indices into the arrays above, sorted by site within a name
    private val byName: IntArray,
    private val nameDefStart: IntArray,
    private val occurrences: Array<LongArray>,
) {
    private val refCountCache = HashMap<Long, Int>()

    val symbolCount: Int get() = defSite.size
    val referenceTotal: Long get() = occurrences.sumOf { it.size.toLong() }

    fun file(path: String): SourceFile? {
        var lo = 0
        var hi = files.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = files[mid].path.compareTo(path)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return files[mid]
            }
        }
        return null
    }

    private fun symbolAt(d: Int): Symbol {
        val site = defSite[d]
        val meta = defMeta[d].toInt()
        return Symbol(names[defName[d]], KINDS[meta and 0x7], files[unpackFile(site)], unpackLine(site), unpackCol(site), isLocal = meta and LOCAL != 0)
    }

    fun definitionsInFile(path: String): List<Symbol> {
        val f = file(path) ?: return emptyList()
        val from = fileDefStart[f.id]
        val to = fileDefStart[f.id + 1]
        return (from until to).map { symbolAt(it) }
    }

    fun definitionAt(path: String, line: Int, col: Int): Symbol? {
        val f = file(path) ?: return null
        val target = pack(f.id, line, col)
        for (d in fileDefStart[f.id] until fileDefStart[f.id + 1]) if (defSite[d] == target) return symbolAt(d)
        return null
    }

    fun isDefined(name: String) = names.find(name, 0, name.length) >= 0

    private fun defsNamed(name: String): List<Symbol> {
        val id = names.find(name, 0, name.length)
        if (id < 0) return emptyList()
        return (nameDefStart[id] until nameDefStart[id + 1]).map { symbolAt(byName[it]) }
    }

    /**
     * Candidate definitions for [name] used at [path]:[line], best match first.
     * Same file beats nearby folders beats far away; locals only resolve within their file.
     */
    fun definitionsFor(name: String, path: String, line: Int): List<Symbol> {
        val all = defsNamed(name)
        if (all.isEmpty()) return emptyList()
        val here = file(path)
        val family = here?.language?.family ?: Languages.forFileName(path).family
        val candidates = all.filter { s ->
            s.file.language.family == family && (!s.isLocalVariable || s.file.path == path)
        }
        val fromDir = path.substringBeforeLast('/', "")
        return candidates.sortedWith(
            compareByDescending<Symbol> { if (it.file.path == path) 1 else 0 }
                // in the same file, a variable declared just above the use wins
                .thenBy { if (it.file.path == path && it.kind == SymbolKind.VARIABLE && it.line <= line) line - it.line else Int.MAX_VALUE }
                .thenBy { if (it.kind == SymbolKind.VARIABLE) 1 else 0 }
                .thenByDescending { commonPrefixSegments(fromDir, it.file.dir) }
                .thenBy { if (here != null && it.file.language == here.language) 0 else 1 }
                .thenBy { it.file.path.length }
                .thenBy { it.file.path }
                .thenBy { it.line },
        )
    }

    private fun usesOf(name: String): LongArray {
        val id = names.find(name, 0, name.length)
        return if (id < 0) EMPTY_LONGS else occurrences[id]
    }

    /** All uses of [symbol] in the repo (definition sites excluded). */
    fun referencesTo(symbol: Symbol, limit: Int = Int.MAX_VALUE): List<Occurrence> {
        val packed = usesOf(symbol.name)
        val family = symbol.file.language.family
        val onlyFile = if (symbol.isLocalVariable) symbol.file.id else -1
        val out = ArrayList<Occurrence>()
        for (p in packed) {
            val f = files[unpackFile(p)]
            if (f.language.family != family) continue
            if (onlyFile >= 0 && f.id != onlyFile) continue
            out.add(Occurrence(f, unpackLine(p), unpackCol(p), symbol.name.length))
            if (out.size >= limit) break
        }
        return out
    }

    /** Fast count for the "N references" lens; cached per name+family. */
    fun referenceCount(symbol: Symbol): Int {
        if (symbol.isLocalVariable) return referencesTo(symbol).size
        val id = names.find(symbol.name, 0, symbol.name.length)
        if (id < 0) return 0
        val family = symbol.file.language.family
        val key = (id.toLong() shl 8) or family.ordinal.toLong()
        synchronized(refCountCache) { refCountCache[key]?.let { return it } }
        var n = 0
        for (p in occurrences[id]) if (files[unpackFile(p)].language.family == family) n++
        synchronized(refCountCache) { refCountCache[key] = n }
        return n
    }

    /** Usages of a name nothing in the repo defines (e.g. a library call): plain word search. */
    suspend fun searchWord(word: String, family: Family?, limit: Int = 3000): List<Occurrence> = withContext(Dispatchers.IO) {
        val out = ArrayList<Occurrence>()
        if (word.isEmpty()) return@withContext out
        for (f in files) {
            ensureActive()
            if (family != null && f.language.family != family) continue
            val lines = SourceText.readLines(File(root, f.path), SourceText.MAX_INDEX_BYTES) ?: continue
            for ((ln, text) in lines.withIndex()) {
                var from = 0
                while (true) {
                    val i = text.indexOf(word, from)
                    if (i < 0) break
                    val before = if (i > 0) text[i - 1] else ' '
                    val after = if (i + word.length < text.length) text[i + word.length] else ' '
                    if (!isWordChar(before) && !isWordChar(after)) {
                        out.add(Occurrence(f, ln, i, word.length))
                        if (out.size >= limit) return@withContext out
                    }
                    from = i + word.length
                }
            }
        }
        out
    }

    /** "Go to symbol" search: prefix matches first, then substring matches. */
    fun searchSymbols(query: String, limit: Int = 200): List<Symbol> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val lower = q.lowercase()
        val prefix = ArrayList<Symbol>()
        val contains = ArrayList<Symbol>()
        for (id in 0 until names.size) {
            val ln = names[id].lowercase()
            val bucket = when {
                ln.startsWith(lower) -> prefix
                lower in ln -> contains
                else -> continue
            }
            for (k in nameDefStart[id] until nameDefStart[id + 1]) {
                val d = byName[k]
                if (KINDS[defMeta[d].toInt() and 0x7] == SymbolKind.VARIABLE && defMeta[d].toInt() and LOCAL != 0) continue
                bucket.add(symbolAt(d))
            }
            // enough for a phone screen: don't materialise hundreds of thousands of symbols
            if (prefix.size >= limit * 20) break
        }
        val order = compareBy<Symbol> { if (it.kind == SymbolKind.VARIABLE) 1 else 0 }.thenBy { it.name.length }.thenBy { it.name }
        prefix.sortWith(order); contains.sortWith(order)
        return (prefix + contains).take(limit)
    }

    companion object {
        /** Bigger trees are cut off when listing (the file drawer has to hold every path). */
        const val MAX_LISTED_FILES = 300_000
        /** Lines longer than this mean minified/generated code: such files are not indexed. */
        private const val MAX_INDEXED_LINE = 3000
        private const val BATCH = 32
        private const val LOCAL = 0x10
        private val KINDS = SymbolKind.entries.toTypedArray()
        private val EMPTY_LONGS = LongArray(0)
        private val skipDirs = setOf(".git", "node_modules", "bower_components", ".gradle", ".idea", "__pycache__", ".venv", "venv", "Pods", ".dart_tool")

        const val MAX_PACKED_LINE = (1 shl 22) - 1
        const val MAX_PACKED_COL = (1 shl 20) - 1

        fun pack(file: Int, line: Int, col: Int): Long =
            (file.toLong() shl 42) or ((line.toLong() and 0x3FFFFF) shl 20) or (col.toLong() and 0xFFFFF)
        fun unpackFile(p: Long) = (p ushr 42).toInt()
        fun unpackLine(p: Long) = ((p ushr 20) and 0x3FFFFF).toInt()
        fun unpackCol(p: Long) = (p and 0xFFFFF).toInt()
        private fun packable(line: Int, col: Int) = line in 0..MAX_PACKED_LINE && col in 0..MAX_PACKED_COL

        private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

        private fun commonPrefixSegments(a: String, b: String): Int {
            if (a.isEmpty() || b.isEmpty()) return 0
            val sa = a.split('/'); val sb = b.split('/')
            var n = 0
            while (n < sa.size && n < sb.size && sa[n] == sb[n]) n++
            return n
        }

        /** Relative paths of every file under [root], sorted (at most [MAX_LISTED_FILES]). */
        fun listFiles(root: File, max: Int = MAX_LISTED_FILES): List<String> {
            val out = ArrayList<String>()
            val base = root.absolutePath.length + 1
            for (f in root.walkTopDown().onEnter { it == root || it.name !in skipDirs }) {
                if (f.isFile) {
                    out.add(f.absolutePath.substring(base).replace(File.separatorChar, '/'))
                    if (out.size >= max) break
                }
            }
            out.sort()
            return out
        }

        private fun shouldIndex(path: String, lang: Language): Boolean {
            if (!lang.indexable) return false
            val name = path.substringAfterLast('/')
            if (name.contains(".min.") || name.endsWith(".bundle.js") || name.endsWith(".d.ts") && path.contains("node_modules")) return false
            return true
        }

        private val lowPriorityDirs = mapOf(
            "test" to 2, "tests" to 2, "__tests__" to 2, "spec" to 2, "testdata" to 3, "fixtures" to 3, "test-data" to 3,
            "examples" to 1, "example" to 1, "samples" to 1, "sample" to 1, "docs" to 1, "doc" to 1, "benchmarks" to 1, "benchmark" to 1,
            "vendor" to 4, "third_party" to 4, "thirdparty" to 4, "external" to 4, "extern" to 4, "deps" to 4,
            "generated" to 4, "gen" to 3, "build" to 4, "dist" to 4, "out" to 4, "target" to 4,
        )

        /** Lower is indexed first when a repository exceeds the caps. */
        private fun priority(path: String): Int {
            var p = 0
            for (seg in path.lowercase().split('/').dropLast(1)) p = maxOf(p, lowPriorityDirs[seg] ?: 0)
            return p
        }

        private class Candidate(val path: String, val lang: Language, val size: Long)

        suspend fun build(
            root: File,
            limits: IndexLimits = IndexLimits.forHeap(),
            onProgress: (IndexProgress) -> Unit = {},
        ): SymbolIndex = withContext(Dispatchers.Default) {
            val candidates = ArrayList<Candidate>()
            for (p in listFiles(root)) {
                ensureActive()
                val lang = Languages.forFileName(p.substringAfterLast('/'))
                if (!shouldIndex(p, lang)) continue
                val size = File(root, p).length()
                if (size <= 0 || size > limits.maxFileBytes) continue
                candidates.add(Candidate(p, lang, size))
            }
            val notes = ArrayList<String>()
            var chosen: List<Candidate> = candidates
            if (candidates.size > limits.maxFiles || candidates.sumOf { it.size } > limits.maxTotalBytes) {
                val picked = ArrayList<Candidate>()
                var bytes = 0L
                for (c in candidates.sortedWith(compareBy<Candidate> { priority(it.path) }.thenBy { it.size })) {
                    if (picked.size >= limits.maxFiles) break
                    if (bytes + c.size > limits.maxTotalBytes) continue
                    picked.add(c); bytes += c.size
                }
                picked.sortBy { it.path }
                chosen = picked
                notes.add("Navigation limited to ${picked.size} of ${candidates.size} source files to fit in this phone's memory.")
            }
            val files = chosen.mapIndexed { i, c -> SourceFile(i, c.path, c.lang) }
            val total = files.size
            val batches = files.chunked(BATCH)
            val window = maxOf(1, limits.parallelism)

            // ---- Pass 1: definitions ----
            val names = NameTable()
            val defSite = LongArrayList(1024)
            val defName = IntArrayList(1024)
            val defMeta = ByteArrayList(1024)
            val fileDefStart = IntArray(total + 1)
            var done = 0
            onProgress(IndexProgress("Finding definitions", 0, total))
            for (group in batches.chunked(window)) {
                ensureActive()
                val results = coroutineScope { group.map { b -> async { b.map { f -> definitionsOf(root, f) } } }.awaitAll() }
                for ((bi, perFile) in results.withIndex()) {
                    for ((fi, defs) in perFile.withIndex()) {
                        val f = group[bi][fi]
                        fileDefStart[f.id] = defSite.size
                        for (d in defs) {
                            if (!packable(d.line, d.col)) continue
                            defSite.add(pack(f.id, d.line, d.col))
                            defName.add(names.intern(d.name))
                            defMeta.add((d.kind.ordinal or if (d.isLocal) LOCAL else 0).toByte())
                        }
                    }
                    done += group[bi].size
                }
                onProgress(IndexProgress("Finding definitions", done, total))
            }
            fileDefStart[total] = defSite.size
            val sites = defSite.toArray()
            val defNames = defName.toArray()
            val metas = defMeta.toArray()
            val nameCount = names.size

            // group by name (counting sort keeps file/line order within each name)
            val nameDefStart = IntArray(nameCount + 1)
            for (n in defNames) nameDefStart[n + 1]++
            for (i in 0 until nameCount) nameDefStart[i + 1] += nameDefStart[i]
            val byName = IntArray(defNames.size)
            val fill = nameDefStart.copyOf(nameCount)
            for (d in defNames.indices) byName[fill[defNames[d]]++] = d
            // sort each name's definitions by site so a binary search can find definition sites
            for (id in 0 until nameCount) {
                val from = nameDefStart[id]; val to = nameDefStart[id + 1]
                if (to - from > 1) sortBySite(byName, from, to, sites)
            }

            // ---- Pass 2: every use of a defined name ----
            val occ = arrayOfNulls<LongArray>(nameCount)
            val occSize = IntArray(nameCount)
            var occTotal = 0L
            var cutAt = -1
            done = 0
            onProgress(IndexProgress("Finding references", 0, total))
            for (group in batches.chunked(window)) {
                ensureActive()
                if (cutAt >= 0) break
                val results = coroutineScope {
                    group.map { b -> async { collectOccurrences(root, b, names, sites, byName, nameDefStart) } }.awaitAll()
                }
                for ((bi, r) in results.withIndex()) {
                    for (i in 0 until r.ids.size) {
                        val id = r.ids[i]
                        var arr = occ[id]
                        val n = occSize[id]
                        if (arr == null) { arr = LongArray(4); occ[id] = arr } else if (n == arr.size) { arr = arr.copyOf(n + (n shr 1) + 4); occ[id] = arr }
                        arr[n] = r.sites[i]
                        occSize[id] = n + 1
                    }
                    occTotal += r.ids.size
                    done += group[bi].size
                    if (occTotal >= limits.maxOccurrences && cutAt < 0) cutAt = group[bi].last().id + 1
                }
                onProgress(IndexProgress("Finding references", done, total))
            }
            if (cutAt in 0 until total) {
                notes.add("References are only counted in the first $cutAt of $total indexed files (memory limit).")
            }
            val occurrences = Array(nameCount) { id ->
                val a = occ[id]
                val n = occSize[id]
                occ[id] = null
                when {
                    a == null || n == 0 -> EMPTY_LONGS
                    a.size == n -> a.also { it.sort() }
                    else -> a.copyOf(n).also { it.sort() }
                }
            }
            SymbolIndex(
                root, files, candidates.size, notes.joinToString(" ").ifEmpty { null },
                names, sites, defNames, metas, fileDefStart, byName, nameDefStart, occurrences,
            )
        }

        /** Insertion/shell sort of [idx] (indices into [sites]) by site, for the usually tiny per-name runs. */
        private fun sortBySite(idx: IntArray, from: Int, to: Int, sites: LongArray) {
            var gap = 1
            while (gap < (to - from) / 3) gap = gap * 3 + 1
            while (gap >= 1) {
                for (i in from + gap until to) {
                    val v = idx[i]
                    val key = sites[v]
                    var j = i
                    while (j - gap >= from && sites[idx[j - gap]] > key) { idx[j] = idx[j - gap]; j -= gap }
                    idx[j] = v
                }
                gap /= 3
            }
        }

        private class RawDef(val name: String, val kind: SymbolKind, val line: Int, val col: Int, val isLocal: Boolean)

        /** The file as lines, or null if it is binary or looks minified/generated. */
        private fun indexableLines(root: File, f: SourceFile): List<String>? {
            val text = SourceText.readText(File(root, f.path), SourceText.MAX_INDEX_BYTES) ?: return null
            // any very long line means minified/generated code: cheap scan before splitting
            var run = 0
            for (c in text) {
                if (c == '\n' || c == '\r') run = 0 else if (++run > MAX_INDEXED_LINE) return null
            }
            return SourceText.splitLines(text)
        }

        private fun definitionsOf(root: File, f: SourceFile): List<RawDef> {
            val lines = indexableLines(root, f) ?: return emptyList()
            val lexer = Lexer(f.language)
            val out = ArrayList<RawDef>()
            // Indents of the functions whose bodies we are inside (by indentation, which works for
            // both brace and offside-rule languages in normally formatted code).
            val fnIndents = IntArrayList()
            var depth = 0
            for ((i, text) in lines.withIndex()) {
                val lexed = lexer.lexLine(text)
                val masked = lexed.masked()
                if (masked.isBlank()) continue
                val indent = masked.length - masked.trimStart().length
                while (depth > 0 && fnIndents[depth - 1] >= indent) depth--
                for (d in DefinitionFinder.find(f.language, masked)) {
                    out.add(RawDef(d.name, d.kind, i, d.col, isLocal = depth > 0))
                }
                if (out.isNotEmpty() && out.last().line == i && out.last().kind == SymbolKind.FUNCTION) {
                    if (depth < fnIndents.size) fnIndents.set(depth, indent) else fnIndents.add(indent)
                    depth++
                }
            }
            return out
        }

        private class OccBatch(val ids: IntArrayList, val sites: LongArrayList)

        private fun collectOccurrences(
            root: File, batch: List<SourceFile>, names: NameTable,
            defSites: LongArray, byName: IntArray, nameDefStart: IntArray,
        ): OccBatch {
            val ids = IntArrayList(256)
            val sites = LongArrayList(256)
            for (f in batch) {
                val lines = indexableLines(root, f) ?: continue
                val lexer = Lexer(f.language)
                for ((i, text) in lines.withIndex()) {
                    val lexed = lexer.lexLine(text)
                    for (t in 0 until lexed.tokenCount) {
                        if (!TokenType.isIdentifier(lexed.type(t))) continue
                        val s = lexed.start(t)
                        val id = names.find(text, s, lexed.end(t))
                        if (id < 0 || !packable(i, s)) continue
                        val site = pack(f.id, i, s)
                        if (isDefSite(id, site, defSites, byName, nameDefStart)) continue
                        ids.add(id); sites.add(site)
                    }
                }
            }
            return OccBatch(ids, sites)
        }

        private fun isDefSite(id: Int, site: Long, defSites: LongArray, byName: IntArray, nameDefStart: IntArray): Boolean {
            var lo = nameDefStart[id]
            var hi = nameDefStart[id + 1] - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val v = defSites[byName[mid]]
                when {
                    v < site -> lo = mid + 1
                    v > site -> hi = mid - 1
                    else -> return true
                }
            }
            return false
        }
    }
}

/**
 * Interned names with an open-addressing table, so the reference pass can look a token up
 * straight from the line text without allocating a substring for it.
 */
internal class NameTable {
    private var names = arrayOfNulls<String>(256)
    private var slots = IntArray(512) // 0 = empty, otherwise id + 1
    var size = 0
        private set

    operator fun get(id: Int): String = names[id]!!

    private fun hash(text: CharSequence, start: Int, end: Int): Int {
        var h = 0
        for (i in start until end) h = 31 * h + text[i].code
        return h xor (h ushr 16)
    }

    fun find(text: String, start: Int, end: Int): Int {
        val len = end - start
        val mask = slots.size - 1
        var i = hash(text, start, end) and mask
        while (true) {
            val v = slots[i]
            if (v == 0) return -1
            val n = names[v - 1]!!
            if (n.length == len && text.regionMatches(start, n, 0, len)) return v - 1
            i = (i + 1) and mask
        }
    }

    fun intern(name: String): Int {
        find(name, 0, name.length).let { if (it >= 0) return it }
        if ((size + 1) * 2 > slots.size) rehash(slots.size * 2)
        if (size == names.size) names = names.copyOf(size * 2)
        names[size] = name
        insert(name, size)
        return size++
    }

    private fun insert(name: String, id: Int) {
        val mask = slots.size - 1
        var i = hash(name, 0, name.length) and mask
        while (slots[i] != 0) i = (i + 1) and mask
        slots[i] = id + 1
    }

    private fun rehash(capacity: Int) {
        slots = IntArray(capacity)
        for (id in 0 until size) insert(names[id]!!, id)
    }
}
