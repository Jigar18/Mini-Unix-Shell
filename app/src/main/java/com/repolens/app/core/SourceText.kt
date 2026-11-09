package com.repolens.app.core

import java.io.File

object SourceText {
    const val TAB_WIDTH = 4
    const val MAX_DISPLAY_BYTES = 4L * 1024 * 1024
    const val MAX_INDEX_BYTES = 1L * 1024 * 1024

    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    private val binaryExtensions = setOf(
        "ico", "jar", "class", "so", "dll", "exe", "o", "a", "zip", "gz", "tgz", "xz", "7z", "rar", "pdf", "mp3", "mp4",
        "wav", "ogg", "mov", "avi", "ttf", "otf", "woff", "woff2", "eot", "keystore", "jks", "apk", "aab", "bin", "dat",
        "pyc", "wasm", "psd", "sketch", "fig", "db", "sqlite", "dylib", "lib", "pdb", "obj",
    )

    fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in imageExtensions
    fun isKnownBinary(name: String) = name.substringAfterLast('.', "").lowercase().let { it in binaryExtensions || it in imageExtensions }

    /** Reads a file as lines with tabs expanded, or null if it looks binary. */
    fun readLines(file: File, maxBytes: Long = MAX_DISPLAY_BYTES): List<String>? {
        if (!file.isFile || file.length() > maxBytes) return null
        val bytes = file.readBytes()
        val probe = minOf(bytes.size, 8000)
        for (i in 0 until probe) if (bytes[i] == 0.toByte()) return null
        return splitLines(String(bytes, Charsets.UTF_8))
    }

    fun splitLines(text: String): List<String> {
        val out = ArrayList<String>(text.length / 32 + 1)
        var start = 0
        val n = text.length
        var i = 0
        // strip UTF-8 BOM
        if (n > 0 && text[0] == '﻿') { start = 1; i = 1 }
        while (i < n) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                out.add(expandTabs(text, start, i))
                if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        if (start < n || out.isEmpty()) out.add(expandTabs(text, start, n))
        return out
    }

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
