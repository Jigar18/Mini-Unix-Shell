package com.repolens.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class SourceFile(val id: Int, val path: String, val language: Language) {
    val name: String get() = path.substringAfterLast('/')
    val dir: String get() = path.substringBeforeLast('/', "")
    override fun toString() = path
}

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
    override fun toString() = "${kind.label} $name @ ${file.path}:${line + 1}"
}

class Occurrence(val file: SourceFile, val line: Int, val col: Int, val length: Int)

data class IndexProgress(val phase: String, val done: Int, val total: Int)

/**
 * Repository-wide symbol table: where each name is defined and every place it is used.
 * Lookups never cross language [Family] boundaries.
 */
class SymbolIndex private constructor(
    val root: File,
    val files: List<SourceFile>,
    private val defsByName: Map<String, List<Symbol>>,
    private val defsByFile: Array<List<Symbol>>,
    private val defSites: Map<Long, Symbol>,
    private val occurrences: Map<String, LongArray>,
) {
    private val filesByPath: Map<String, SourceFile> = files.associateBy { it.path }
    private val refCountCache = HashMap<String, Int>()

    val symbolCount: Int get() = defSites.size

    fun file(path: String): SourceFile? = filesByPath[path]

    fun definitionsInFile(path: String): List<Symbol> = filesByPath[path]?.let { defsByFile[it.id] } ?: emptyList()

    fun definitionAt(path: String, line: Int, col: Int): Symbol? {
        val f = filesByPath[path] ?: return null
        return defSites[pack(f.id, line, col)]
    }

    fun isDefined(name: String) = defsByName.containsKey(name)

    /**
     * Candidate definitions for [name] used at [path]:[line], best match first.
     * Same file beats nearby folders beats far away; locals only resolve within their file.
     */
    fun definitionsFor(name: String, path: String, line: Int): List<Symbol> {
        val all = defsByName[name] ?: return emptyList()
        val here = filesByPath[path]
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

    /** All uses of [symbol] in the repo (definition sites excluded). */
    fun referencesTo(symbol: Symbol): List<Occurrence> {
        val packed = occurrences[symbol.name] ?: return emptyList()
        val family = symbol.file.language.family
        val onlyFile = if (symbol.isLocalVariable) symbol.file.id else -1
        val out = ArrayList<Occurrence>()
        for (p in packed) {
            if (defSites.containsKey(p)) continue
            val f = files[unpackFile(p)]
            if (f.language.family != family) continue
            if (onlyFile >= 0 && f.id != onlyFile) continue
            out.add(Occurrence(f, unpackLine(p), unpackCol(p), symbol.name.length))
        }
        return out
    }

    /** Fast count for the "N references" lens; cached per name+family. */
    fun referenceCount(symbol: Symbol): Int {
        if (symbol.isLocalVariable) return referencesTo(symbol).size
        val key = symbol.name + "|" + symbol.file.language.family.name
        synchronized(refCountCache) { refCountCache[key]?.let { return it } }
        val packed = occurrences[symbol.name]
        var n = 0
        if (packed != null) {
            val family = symbol.file.language.family
            for (p in packed) {
                if (defSites.containsKey(p)) continue
                if (files[unpackFile(p)].language.family == family) n++
            }
        }
        synchronized(refCountCache) { refCountCache[key] = n }
        return n
    }

    /** Usages of a name nothing in the repo defines (e.g. a library call): plain word search. */
    suspend fun searchWord(word: String, family: Family?, limit: Int = 3000): List<Occurrence> = withContext(Dispatchers.IO) {
        val out = ArrayList<Occurrence>()
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
        for ((name, syms) in defsByName) {
            val ln = name.lowercase()
            val bucket = when {
                ln.startsWith(lower) -> prefix
                lower in ln -> contains
                else -> continue
            }
            syms.filter { !it.isLocalVariable }.forEach { bucket.add(it) }
        }
        val order = compareBy<Symbol> { if (it.kind == SymbolKind.VARIABLE) 1 else 0 }.thenBy { it.name.length }.thenBy { it.name }
        prefix.sortWith(order); contains.sortWith(order)
        return (prefix + contains).take(limit)
    }

    companion object {
        private const val MAX_FILES = 60_000
        private val skipDirs = setOf(".git", "node_modules", "bower_components", ".gradle", ".idea", "__pycache__", ".venv", "venv", "Pods", ".dart_tool")

        fun pack(file: Int, line: Int, col: Int): Long =
            (file.toLong() shl 42) or ((line.toLong() and 0x3FFFFF) shl 20) or (col.toLong() and 0xFFFFF)
        fun unpackFile(p: Long) = (p ushr 42).toInt()
        fun unpackLine(p: Long) = ((p ushr 20) and 0x3FFFFF).toInt()
        fun unpackCol(p: Long) = (p and 0xFFFFF).toInt()

        private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

        private fun commonPrefixSegments(a: String, b: String): Int {
            if (a.isEmpty() || b.isEmpty()) return 0
            val sa = a.split('/'); val sb = b.split('/')
            var n = 0
            while (n < sa.size && n < sb.size && sa[n] == sb[n]) n++
            return n
        }

        /** Relative paths of every file under [root], sorted. */
        fun listFiles(root: File): List<String> {
            val out = ArrayList<String>()
            val base = root.absolutePath.length + 1
            root.walkTopDown().onEnter { it == root || it.name !in skipDirs }.forEach {
                if (it.isFile) out.add(it.absolutePath.substring(base).replace(File.separatorChar, '/'))
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

        suspend fun build(root: File, onProgress: (IndexProgress) -> Unit = {}): SymbolIndex = withContext(Dispatchers.Default) {
            val paths = listFiles(root)
            val files = ArrayList<SourceFile>()
            for (p in paths) {
                val lang = Languages.forFileName(p.substringAfterLast('/'))
                if (!shouldIndex(p, lang)) continue
                if (File(root, p).length() > SourceText.MAX_INDEX_BYTES) continue
                files.add(SourceFile(files.size, p, lang))
                if (files.size >= MAX_FILES) break
            }
            val total = files.size
            val chunkSize = 48

            // ---- Pass 1: definitions ----
            val defsByFile = arrayOfNulls<List<Symbol>>(total)
            val done1 = AtomicInteger()
            onProgress(IndexProgress("Finding definitions", 0, total))
            files.chunked(chunkSize).map { chunk ->
                async {
                    for (f in chunk) {
                        ensureActive()
                        defsByFile[f.id] = definitionsOf(root, f)
                        val d = done1.incrementAndGet()
                        if (d % 25 == 0 || d == total) onProgress(IndexProgress("Finding definitions", d, total))
                    }
                }
            }.awaitAll()

            val defsByName = HashMap<String, MutableList<Symbol>>()
            val defSites = HashMap<Long, Symbol>()
            for (list in defsByFile) list?.forEach { s ->
                defsByName.getOrPut(s.name) { ArrayList(1) }.add(s)
                defSites[pack(s.file.id, s.line, s.col)] = s
            }

            // ---- Pass 2: every use of a defined name ----
            val names: Set<String> = defsByName.keys
            val merged = HashMap<String, LongArrayList>()
            val done2 = AtomicInteger()
            onProgress(IndexProgress("Finding references", 0, total))
            files.chunked(chunkSize).map { chunk ->
                async {
                    val local = HashMap<String, LongArrayList>()
                    for (f in chunk) {
                        ensureActive()
                        collectOccurrences(root, f, names, local)
                        val d = done2.incrementAndGet()
                        if (d % 25 == 0 || d == total) onProgress(IndexProgress("Finding references", d, total))
                    }
                    synchronized(merged) {
                        for ((k, v) in local) {
                            val target = merged.getOrPut(k) { LongArrayList(v.size) }
                            for (i in 0 until v.size) target.add(v[i])
                        }
                    }
                }
            }.awaitAll()

            val occurrences = HashMap<String, LongArray>(merged.size * 2)
            for ((k, v) in merged) occurrences[k] = v.toArray().also { it.sort() }

            SymbolIndex(
                root, files, defsByName,
                Array(total) { defsByFile[it] ?: emptyList() },
                defSites, occurrences,
            )
        }

        private fun definitionsOf(root: File, f: SourceFile): List<Symbol> {
            val lines = SourceText.readLines(File(root, f.path), SourceText.MAX_INDEX_BYTES) ?: return emptyList()
            if (lines.any { it.length > 3000 }) return emptyList() // minified / generated
            val lexer = Lexer(f.language)
            val out = ArrayList<Symbol>()
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
                    out.add(Symbol(d.name, d.kind, f, i, d.col, isLocal = depth > 0))
                }
                if (out.isNotEmpty() && out.last().line == i && out.last().kind == SymbolKind.FUNCTION) {
                    if (depth < fnIndents.size) fnIndents.set(depth, indent) else fnIndents.add(indent)
                    depth++
                }
            }
            return out
        }

        private fun collectOccurrences(root: File, f: SourceFile, names: Set<String>, into: HashMap<String, LongArrayList>) {
            val lines = SourceText.readLines(File(root, f.path), SourceText.MAX_INDEX_BYTES) ?: return
            if (lines.any { it.length > 3000 }) return
            val lexer = Lexer(f.language)
            for ((i, text) in lines.withIndex()) {
                val lexed = lexer.lexLine(text)
                for (t in 0 until lexed.tokenCount) {
                    if (!TokenType.isIdentifier(lexed.type(t))) continue
                    val word = text.substring(lexed.start(t), lexed.end(t))
                    if (word !in names) continue
                    into.getOrPut(word) { LongArrayList() }.add(pack(f.id, i, lexed.start(t)))
                }
            }
        }
    }
}
