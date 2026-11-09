package com.repolens.app.core

object TokenType {
    const val IDENT = 0
    const val KEYWORD = 1
    const val TYPE = 2       // Capitalised identifier
    const val FUNCTION = 3   // identifier followed by "("
    const val NUMBER = 4
    const val STRING = 5
    const val COMMENT = 6
    const val ANNOTATION = 7 // @Foo, #include
    const val PUNCT = 8

    fun isIdentifier(t: Int) = t == IDENT || t == TYPE || t == FUNCTION
}

/**
 * One source line with its tokens stored flat as `[start, end, type, start, end, type, ...]`.
 * Characters not covered by a token (whitespace, operators) are drawn in the default colour.
 */
class LexedLine(val text: String, val tokens: IntArray) {
    val tokenCount get() = tokens.size / 3
    fun start(i: Int) = tokens[i * 3]
    fun end(i: Int) = tokens[i * 3 + 1]
    fun type(i: Int) = tokens[i * 3 + 2]

    /** The identifier token covering [col], or -1. */
    fun identifierAt(col: Int): Int {
        for (i in 0 until tokenCount) {
            if (col >= start(i) && col < end(i)) return if (TokenType.isIdentifier(type(i))) i else -1
            if (start(i) > col) break
        }
        return -1
    }

    /**
     * The line with comments and string contents blanked out (same length, so columns still
     * line up), which is what the definition patterns run against.
     */
    fun masked(): String {
        var chars: CharArray? = null
        for (i in 0 until tokenCount) {
            val t = type(i)
            if (t == TokenType.COMMENT || t == TokenType.STRING) {
                if (chars == null) chars = text.toCharArray()
                val s = start(i); val e = end(i)
                // keep the quote characters themselves so `x = "a"` still reads as an assignment
                for (k in s until e) {
                    val q = chars[k] == '"' || chars[k] == '\'' || chars[k] == '`'
                    if (!(t == TokenType.STRING && q && (k == s || k == e - 1))) chars[k] = ' '
                }
            }
        }
        return if (chars == null) text else String(chars)
    }
}

class Lexer(private val lang: Language) {
    // Multi-line state carried between lines.
    private var inBlockComment = false
    private var openString: String? = null   // closing delimiter of a string still open from a previous line
    private var openStringRaw = false

    private val tokens = IntArrayList(64)

    fun lexAll(lines: List<String>): List<LexedLine> = lines.map { lexLine(it) }

    fun lexLine(text: String): LexedLine {
        tokens.clear()
        val n = text.length
        var i = 0

        if (inBlockComment) {
            val close = lang.blockComment!!.second
            val end = text.indexOf(close)
            if (end < 0) { add(0, n, TokenType.COMMENT); return done(text) }
            i = end + close.length
            add(0, i, TokenType.COMMENT)
            inBlockComment = false
        } else if (openString != null) {
            val end = findStringEnd(text, 0, openString!!, openStringRaw)
            if (end < 0) { add(0, n, TokenType.STRING); return done(text) }
            add(0, end, TokenType.STRING)
            i = end
            openString = null
        }

        // Preprocessor directive (#include, #define ...)
        if (lang.preprocessor && i == 0) {
            var k = 0
            while (k < n && text[k].isWhitespace()) k++
            if (k < n && text[k] == '#') {
                var e = k + 1
                while (e < n && text[e] == ' ') e++
                while (e < n && (text[e].isLetter())) e++
                add(k, e, TokenType.ANNOTATION)
                i = e
                // #include <foo.h>: treat the <...> as a string
                if (text.startsWith("include", k + 1) || text.substring(k + 1).trimStart().startsWith("include")) {
                    var a = e
                    while (a < n && text[a] == ' ') a++
                    if (a < n && text[a] == '<') {
                        val b = text.indexOf('>', a)
                        if (b > 0) { add(a, b + 1, TokenType.STRING); i = b + 1 }
                    }
                }
            }
        }

        while (i < n) {
            val c = text[i]
            if (c == ' ' || c == '\t') { i++; continue }

            // line comment
            val lc = lineCommentAt(text, i)
            if (lc) { add(i, n, TokenType.COMMENT); break }

            // block comment
            val bc = lang.blockComment
            if (bc != null && text.startsWith(bc.first, i)) {
                val end = text.indexOf(bc.second, i + bc.first.length)
                if (end < 0) {
                    add(i, n, TokenType.COMMENT); inBlockComment = true; break
                }
                add(i, end + bc.second.length, TokenType.COMMENT)
                i = end + bc.second.length
                continue
            }

            // strings
            if (lang.tripleQuoted && (c == '"' || c == '\'') && i + 2 < n && text[i + 1] == c && text[i + 2] == c) {
                val delim = "$c$c$c"
                val end = findStringEnd(text, i + 3, delim, raw = false)
                if (end < 0) { add(i, n, TokenType.STRING); openString = delim; openStringRaw = false; break }
                add(i, end, TokenType.STRING); i = end; continue
            }
            if (c == '`' && lang.backtickMultiline) {
                val raw = lang.family == Family.GO
                val end = findStringEnd(text, i + 1, "`", raw)
                if (end < 0) { add(i, n, TokenType.STRING); openString = "`"; openStringRaw = raw; break }
                add(i, end, TokenType.STRING); i = end; continue
            }
            if (c in lang.stringDelims) {
                if (c == '\'' && isRustLifetime(text, i)) { i++; continue }
                val end = findStringEnd(text, i + 1, c.toString(), raw = false)
                val e = if (end < 0) n else end
                add(i, e, TokenType.STRING); i = e; continue
            }

            // numbers
            if (c.isDigit() || (c == '.' && i + 1 < n && text[i + 1].isDigit() && (i == 0 || !isIdentPart(text[i - 1])))) {
                var e = i + 1
                while (e < n && (text[e].isLetterOrDigit() || text[e] == '_' || text[e] == '.' ||
                            ((text[e] == '+' || text[e] == '-') && (text[e - 1] == 'e' || text[e - 1] == 'E')))) {
                    if (text[e] == '.' && e + 1 < n && text[e + 1] == '.') break // ranges 1..10
                    e++
                }
                add(i, e, TokenType.NUMBER); i = e; continue
            }

            // annotations / decorators
            if (c == '@' && i + 1 < n && isIdentStart(text[i + 1])) {
                var e = i + 1
                while (e < n && (isIdentPart(text[e]) || text[e] == '.')) e++
                add(i, e, TokenType.ANNOTATION); i = e; continue
            }

            // identifiers & keywords
            if (isIdentStart(c)) {
                var e = i + 1
                while (e < n && isIdentPart(text[e])) e++
                if (e < n && text[e] in lang.trailingIdentChars) e++
                val word = text.substring(i, e)
                val type = when {
                    lang === Languages.PLAIN -> TokenType.IDENT // prose: no type/function colouring
                    word in lang.keywords -> TokenType.KEYWORD
                    nextNonSpace(text, e) == '(' -> TokenType.FUNCTION
                    word[0].isUpperCase() || (word.length > 1 && word[0] == '$' && word[1].isUpperCase()) -> TokenType.TYPE
                    else -> TokenType.IDENT
                }
                add(i, e, type)
                i = e
                continue
            }
            i++
        }
        return done(text)
    }

    private fun done(text: String) = LexedLine(text, tokens.toArray())

    private fun add(s: Int, e: Int, t: Int) { if (e > s) { tokens.add(s); tokens.add(e); tokens.add(t) } }

    private fun lineCommentAt(text: String, i: Int): Boolean {
        for (p in lang.lineComments) {
            if (text.startsWith(p, i)) {
                // Lua: "--[[" is a block comment, handled separately
                if (lang.blockComment != null && text.startsWith(lang.blockComment.first, i)) return false
                // Shell/YAML/etc: "#" only starts a comment at a word boundary (so $# and a#b are fine)
                if (p == "#" && i > 0 && !text[i - 1].isWhitespace() && lang.family != Family.PYTHON) return false
                return true
            }
        }
        return false
    }

    /** Index just after the closing delimiter, or -1 if the string runs past the end of the line. */
    private fun findStringEnd(text: String, from: Int, delim: String, raw: Boolean): Int {
        var k = from
        val n = text.length
        while (k < n) {
            val ch = text[k]
            if (!raw && ch == '\\') { k += 2; continue }
            if (text.startsWith(delim, k)) return k + delim.length
            k++
        }
        return -1
    }

    private fun isRustLifetime(text: String, i: Int): Boolean {
        if (lang.family != Family.RUST) return false
        // 'a' / '\n' are chars; 'a followed by something else is a lifetime
        if (i + 1 < text.length && text[i + 1] == '\\') return false
        return !(i + 2 < text.length && text[i + 2] == '\'')
    }

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || (c in lang.extraIdentChars && c != '?' && c != '!')
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c in lang.extraIdentChars

    private fun nextNonSpace(text: String, from: Int): Char {
        var k = from
        while (k < text.length && text[k] == ' ') k++
        return if (k < text.length) text[k] else '\u0000'
    }
}

class IntArrayList(capacity: Int = 16) {
    private var data = IntArray(capacity)
    var size = 0; private set
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    operator fun get(i: Int) = data[i]
    fun set(i: Int, v: Int) { data[i] = v }
    fun clear() { size = 0 }
    fun toArray(): IntArray = data.copyOf(size)
}

class LongArrayList(capacity: Int = 8) {
    private var data = LongArray(capacity)
    var size = 0; private set
    fun add(v: Long) {
        if (size == data.size) data = data.copyOf(maxOf(8, data.size * 2))
        data[size++] = v
    }
    operator fun get(i: Int) = data[i]
    fun toArray(): LongArray = data.copyOf(size)
}
