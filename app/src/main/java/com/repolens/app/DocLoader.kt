package com.repolens.app

import com.repolens.app.core.Language
import com.repolens.app.core.Languages
import com.repolens.app.core.LexedLine
import com.repolens.app.core.Lexer
import com.repolens.app.core.RepoDownloader
import com.repolens.app.core.SourceText
import java.io.File

class OpenDoc(
    val path: String,
    val language: Language,
    val lines: List<LexedLine>?,
    val imageFile: File? = null,
    /** Shown instead of the content when [lines] and [imageFile] are null. */
    val note: String? = null,
    /** Shown above the content, e.g. when long lines were cut for display. */
    val banner: String? = null,
) {
    val name get() = path.substringAfterLast('/')
    val dir get() = path.substringBeforeLast('/', "")
}

/**
 * Turns a file of the repository into something the code view can show, with hard limits so a
 * huge or minified file can never exhaust memory. Never throws (except cancellation-free
 * programming errors): every failure becomes an [OpenDoc] with a [OpenDoc.note].
 */
object DocLoader {
    class Limits(val maxBytes: Long, val maxLines: Int, val maxLineChars: Int)

    const val MAX_LINES = 200_000
    const val MAX_LINE_CHARS = 20_000

    /** About 1/48 of the heap (a 4 MB file needs ~10× that while it is read and lexed). */
    fun limitsFor(maxMemory: Long = Runtime.getRuntime().maxMemory()) =
        Limits((maxMemory / 48).coerceIn(512L * 1024, SourceText.MAX_DISPLAY_BYTES), MAX_LINES, MAX_LINE_CHARS)

    fun load(root: File, path: String, limits: Limits = limitsFor()): OpenDoc {
        val name = path.substringAfterLast('/')
        val lang = try { Languages.forFileName(name) } catch (e: Throwable) { Languages.PLAIN }
        return try {
            loadUnsafe(root, path, name, lang, limits)
        } catch (e: OutOfMemoryError) {
            OpenDoc(path, lang, null, note = "Not enough memory to display this file.")
        } catch (e: Exception) {
            OpenDoc(path, lang, null, note = "Couldn't open this file: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun loadUnsafe(root: File, path: String, name: String, lang: Language, limits: Limits): OpenDoc {
        val file = File(root, path)
        val rootPath = root.canonicalPath + File.separator
        if (!file.canonicalPath.startsWith(rootPath)) return OpenDoc(path, lang, null, note = "This path is outside the repository.")
        if (!file.isFile) return OpenDoc(path, lang, null, note = "This file no longer exists.")
        if (SourceText.isImage(name)) return OpenDoc(path, lang, null, imageFile = file)
        val size = file.length()
        if (SourceText.isKnownBinary(name)) return OpenDoc(path, lang, null, note = "Binary file (${RepoDownloader.formatBytes(size)}) — nothing to show.")
        if (size > limits.maxBytes) {
            return OpenDoc(path, lang, null, note = "This file is ${RepoDownloader.formatBytes(size)} — too large to display (limit ${RepoDownloader.formatBytes(limits.maxBytes)}).")
        }
        val text = SourceText.readText(file, limits.maxBytes) ?: return OpenDoc(path, lang, null, note = "Binary file — nothing to show.")
        val split = SourceText.splitLinesCapped(text, limits.maxLines, limits.maxLineChars)
        val banner = buildList {
            if (split.droppedLines > 0) add("Showing the first ${split.lines.size} of ${split.lines.size + split.droppedLines} lines.")
            if (split.truncatedLines > 0) add("${split.truncatedLines} very long ${if (split.truncatedLines == 1) "line was" else "lines were"} cut for display.")
        }.joinToString(" ").ifEmpty { null }
        return OpenDoc(path, lang, Lexer(lang).lexAll(split.lines), banner = banner)
    }
}
