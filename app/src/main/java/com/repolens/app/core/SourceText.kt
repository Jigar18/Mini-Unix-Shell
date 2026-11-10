package com.repolens.app.core

import java.io.File
import java.io.FileInputStream

object SourceText {
    const val TAB_WIDTH = 4
    const val MAX_DISPLAY_BYTES = 4L * 1024 * 1024
    const val MAX_INDEX_BYTES = 1L * 1024 * 1024
    private const val BOM = '\uFEFF'

    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    private val binaryExtensions = setOf(
        "ico", "jar", "class", "so", "dll", "exe", "o", "a", "zip", "gz", "tgz", "xz", "7z", "rar", "pdf", "mp3", "mp4",
        "wav", "ogg", "mov", "avi", "ttf", "otf", "woff", "woff2", "eot", "keystore", "jks", "apk", "aab", "bin", "dat",
        "pyc", "wasm", "psd", "sketch", "fig", "db", "sqlite", "dylib", "lib", "pdb", "obj", "mo",
    )

    fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in imageExtensions
    fun isKnownBinary(name: String) = name.substringAfterLast('.', "").lowercase().let { it in binaryExtensions || it in imageExtensions }

    /**
     * The file decoded as UTF-8, or null if it is missing, bigger than [maxBytes] or looks binary
     * (a NUL byte in the first 8000 bytes). Never reads more than [maxBytes] even if the file grows.
     */
    fun readText(file: File, maxBytes: Long = MAX_DISPLAY_BYTES): String? {
        if (!file.isFile) return null
        val len = file.length()
        if (len > maxBytes || len > Int.MAX_VALUE - 16) return null
        val bytes = ByteArray(len.toInt())
        var n = 0
        FileInputStream(file).use { input ->
            while (n < bytes.size) {
                val r = input.read(bytes, n, bytes.size - n)
                if (r < 0) break
                n += r
            }
        }
        val probe = minOf(n, 8000)
        for (i in 0 until probe) if (bytes[i] == 0.toByte()) return null
        return String(bytes, 0, n, Charsets.UTF_8)
    }

    /** Reads a file as lines with tabs expanded, or null if it looks binary. */
    fun readLines(file: File, maxBytes: Long = MAX_DISPLAY_BYTES): List<String>? =
        readText(file, maxBytes)?.let { splitLines(it) }

    /** Result of [splitLinesCapped]. */
    class Lines(val lines: List<String>, val truncatedLines: Int, val droppedLines: Int)

    fun splitLines(text: String): List<String> = splitLinesCapped(text, Int.MAX_VALUE, Int.MAX_VALUE).lines

    /**
     * Splits on \n, \r\n and \r, expands tabs and strips a UTF-8 BOM. At most [maxLines] lines are
     * returned; a line longer than [maxLineChars] is cut there and ends with [truncationMarker].
     */
    fun splitLinesCapped(text: String, maxLines: Int, maxLineChars: Int): Lines {
        val n = text.length
        val out = ArrayList<String>(minOf(maxLines, text.length / 32 + 1).coerceAtLeast(1))
        var start = 0
        var i = 0
        var truncated = 0
        var dropped = 0
        if (n > 0 && text[0] == BOM) { start = 1; i = 1 }
        fun emit(from: Int, to: Int) {
            if (out.size >= maxLines) { dropped++; return }
            if (to - from > maxLineChars) {
                var cut = from + maxLineChars
                if (Character.isHighSurrogate(text[cut - 1])) cut-- // don't split a surrogate pair
                truncated++
                out.add(expandTabs(text, from, cut) + truncationMarker(to - cut))
            } else {
                out.add(expandTabs(text, from, to))
            }
        }
        while (i < n) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                emit(start, i)
                if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        if (start < n || out.isEmpty()) emit(start, n)
        return Lines(out, truncated, dropped)
    }

    fun truncationMarker(remaining: Int) = "  ⋯ [line cut: $remaining more characters]"

    private fun expandTabs(text: String, from: Int, to: Int): String {
        var hasTab = false
        for (k in from until to) if (text[k] == '\t') { hasTab = true; break }
        if (!hasTab) return text.substring(from, to)
        val sb = StringBuilder(to - from + 16)
        for (k in from until to) {
            val c = text[k]
            if (c == '\t') {
                val spaces = TAB_WIDTH - (sb.length % TAB_WIDTH)
                repeat(spaces) { sb.append(' ') }
            } else sb.append(c)
        }
        return sb.toString()
    }
}
