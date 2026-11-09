package com.repolens.app.core

class FoundDef(val name: String, val kind: SymbolKind, val col: Int)

/**
 * Finds the symbols *defined* on a line. Works on masked lines (comments and string contents
 * blanked), so `// fun fake()` or `"def nope()"` never count.
 *
 * This is the same idea as ctags / GitHub's search-based code navigation: per-language patterns,
 * not a compiler. It is fast enough to index a whole repository on a phone.
 */
object DefinitionFinder {

    fun find(lang: Language, masked: String): List<FoundDef> {
        if (masked.isBlank()) return emptyList()
        var result: ArrayList<FoundDef>? = null
        fun add(name: String, kind: SymbolKind, col: Int) {
            val clean = name.trim('`')
            if (clean.isEmpty() || clean in lang.keywords || clean in lang.notSymbols) return
            val list = result ?: ArrayList<FoundDef>(2).also { result = it }
            if (list.none { it.name == clean }) list.add(FoundDef(clean, kind, col + if (name.startsWith('`')) 1 else 0))
        }
        for (p in lang.defPatterns) {
            for (m in p.regex.findAll(masked)) {
                val g = m.groups[p.group] ?: continue
                add(g.value, p.kind, g.range.first)
            }
        }
        if (lang.cStyleSignatures) {
            signature(lang, masked)?.let { (name, col) -> add(name, SymbolKind.FUNCTION, col) }
        }
        return result ?: emptyList()
    }

    private val callLike = Regex("""(~?[A-Za-z_$][\w$]*)\s*\(""")
    private val annotation = Regex("""@[\w.]+(?:\s*\([^)]*\))?""")
    private val prefixChars = Regex("""^[\s\w$<>\[\],.?*&:~]*$""")
    private val wordSplit = Regex("""[\s*&]+""")

    private val control = setOf(
        "if", "for", "while", "switch", "catch", "return", "sizeof", "new", "throw", "else", "do", "synchronized",
        "using", "lock", "foreach", "typeof", "nameof", "default", "case", "assert", "super", "this", "base", "await",
        "yield", "when", "with", "function", "alignof", "decltype", "static_assert", "defined", "elif", "delete",
        "fixed", "checked", "unchecked", "stackalloc", "operator", "import", "require", "noexcept", "alignas",
        "__attribute__", "__declspec", "until", "unless", "constructor", "print", "printf", "and", "or", "not",
    )
    private val statementWords = setOf(
        "return", "new", "throw", "else", "case", "await", "yield", "delete", "goto", "typeof", "in", "is", "as",
        "sizeof", "co_return", "co_await", "co_yield", "echo", "print", "not", "and", "or", "if", "while", "for",
        "switch", "do", "instanceof", "of", "assert", "throws", "extends", "implements", "import", "package",
        "using", "from", "export default",
    )
    private val restStarts = listOf(
        "{", ";", "=>", ":", "const", "override", "throws", "noexcept", "->", "where", "final", "async", "=", "&",
        "volatile", "mutable", "try", "requires", "sync", "*", "[[", "__attribute__",
    )

    /** "type name(...)" style method/function signatures for C, C++, Java, C#, Dart, JS/TS. */
    internal fun signature(lang: Language, masked: String): Pair<String, Int>? {
        val trimmed = masked.trimStart()
        if (trimmed.startsWith("#")) return null
        val line = if ('@' in masked) annotation.replace(masked) { " ".repeat(it.value.length) } else masked

        for (m in callLike.findAll(line)) {
            val g = m.groups[1]!!
            val name = g.value
            if (name in control || name in lang.keywords) continue
            val s = g.range.first
            var b = line.substring(0, s).trimEnd()
            if (b.endsWith(".") || b.endsWith("->") || b.endsWith("?.") || b.endsWith("!")) continue

            var qualified = false
            while (b.endsWith("::")) {
                b = b.dropLast(2).trimEnd()
                if (b.endsWith(">")) b = dropTemplate(b)
                b = b.dropLastWhile { it.isLetterOrDigit() || it == '_' || it == '~' }.trimEnd()
                qualified = true
            }
            if (!prefixChars.matches(b)) continue
            if (b.endsWith(">") && ".<" in b) continue // Java explicit generic call: Foo.<T>bar()
            val words = b.split(wordSplit).filter { it.isNotEmpty() }
            if (words.any { it in statementWords || it == "?" || it.startsWith("?") || it.endsWith(":") && !it.endsWith("::") }) continue
            if (words.any { it.endsWith(",") || it.startsWith(",") }) {
                // "a, b foo(" is never a signature, but "Map<K, V> foo(" is: only allow commas inside <>
                if (!commasInsideGenerics(b)) continue
            }

            val close = matchParen(line, m.range.last)
            val rest = if (close >= 0) line.substring(close + 1).trim() else null

            if (words.isEmpty()) {
                val ok = if (qualified) {
                    rest != null && (rest.isEmpty() || rest[0] == '{' || (rest[0] == ':' && !rest.startsWith("::")) ||
                            rest.startsWith("const") || rest.startsWith("noexcept") || rest.startsWith("->"))
                } else {
                    val untypedOk = lang.allowUntypedMethods || (lang.family == Family.C && name[0].isUpperCase())
                    untypedOk && rest != null && (
                        rest.startsWith("{") || rest.startsWith("=>") || rest.startsWith("async") ||
                            (lang.family == Family.C && rest.startsWith(":") && !rest.startsWith("::")) ||
                            (lang.family == Family.WEB && rest.startsWith(":") && (rest.endsWith("{") || rest.endsWith(";")))
                        )
                }
                if (!ok) continue
            } else if (!rest.isNullOrEmpty()) {
                if (restStarts.none { rest.startsWith(it) }) continue
                if (rest.startsWith("=") && !rest.startsWith("=>") && !(rest.startsWith("= default") ||
                        rest.startsWith("= delete") || rest.startsWith("= 0"))) continue
            }
            return name to s
        }
        return null
    }

    private fun dropTemplate(b: String): String {
        var depth = 0
        for (i in b.indices.reversed()) {
            when (b[i]) {
                '>' -> depth++
                '<' -> { depth--; if (depth == 0) return b.substring(0, i).trimEnd() }
            }
        }
        return b
    }

    private fun commasInsideGenerics(b: String): Boolean {
        var depth = 0
        for (c in b) {
            when (c) {
                '<', '[' -> depth++
                '>', ']' -> depth--
                ',' -> if (depth <= 0) return false
            }
        }
        return true
    }

    private fun matchParen(line: String, open: Int): Int {
        var depth = 0
        for (i in open until line.length) {
            when (line[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }
}
