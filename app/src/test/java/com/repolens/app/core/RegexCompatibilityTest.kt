package com.repolens.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * On Android `java.util.regex` is ICU, not the JVM engine these tests normally run on. ICU rejects
 * an unescaped `}` that the JVM accepts; two definition patterns had one, so on a phone the
 * `Languages` object failed to initialise and the app crashed as soon as any repository opened.
 * These tests check every regex the app compiles against ICU's rules.
 */
class RegexCompatibilityTest {
    private val all: List<String> = Languages.allPatterns + DefinitionFinder.allPatterns + RepoRef.allPatterns + com.repolens.app.CrashStore.allPatterns

    /** The two patterns as they were in 1.0.0 (crashed on Android). */
    private val oldBroken = listOf("""^\s*}\s*([A-Za-z_][A-Za-z0-9_]*)\s*;""", """^\s*typedef\b.*}\s*([A-Za-z_][A-Za-z0-9_]*)\s*;""")

    @Test fun everyPatternCompilesAndIsKept() {
        assertTrue(all.size > 50)
        for (l in Languages.all) for (p in l.defPatterns) assertNotNull("${l.name}: ${p.pattern}", p.regex)
    }

    @Test fun noPatternUsesSyntaxIcuRejects() {
        val problems = all.mapNotNull { p -> icuStrictProblem(p)?.let { "$it in: $p" } }
        assertEquals(emptyList<String>(), problems)
        // and the checker does catch the patterns that crashed the app
        for (p in oldBroken) assertNotNull(p, icuStrictProblem(p))
    }

    /** The same check with the real ICU4C regex engine, where the machine has one (Linux CI does). */
    @Test fun realIcuAcceptsEveryPattern() {
        val checker = NativeIcu.checker()
        assumeTrue("no ICU4C library or C compiler on this machine", checker != null)
        val rejected = NativeIcu.rejected(checker!!, all)
        assertEquals("rejected by ICU: $rejected", emptyList<String>(), rejected)
        // control: ICU really does reject the old patterns (so a pass above means something)
        assertEquals(oldBroken, NativeIcu.rejected(checker, oldBroken))
    }

    companion object {
        /**
         * Rules where ICU is stricter than java.util.regex: an unescaped `}` outside a character
         * class, a `{` that doesn't start a valid `{n}`, `{n,}` or `{n,m}` quantifier, and an
         * unescaped `[` inside a class (ICU reads it as a nested set).
         */
        fun icuStrictProblem(p: String): String? {
            var i = 0
            var inClass = false
            var classStart = -1
            while (i < p.length) {
                val c = p[i]
                when {
                    c == '\\' -> { i += 2; continue }
                    inClass -> when {
                        c == ']' && i > classStart + 1 && !(i == classStart + 2 && p[classStart + 1] == '^') -> inClass = false
                        c == '[' -> return "unescaped [ inside a character class at $i"
                    }
                    c == '[' -> { inClass = true; classStart = i }
                    c == '}' -> return "unescaped } at $i"
                    c == '{' -> {
                        val m = Regex("""\{\d+(,\d*)?}""").matchAt(p, i) ?: return "{ that is not a quantifier at $i"
                        i = m.range.last + 1
                        continue
                    }
                }
                i++
            }
            return null
        }
    }
}

/** Compiles a tiny C program against the system's ICU4C and asks it which patterns it rejects. */
object NativeIcu {
    private const val SOURCE = """
#include <stdio.h>
#include <stdint.h>
#include <string.h>
typedef uint16_t UChar;
typedef int UErrorCode;
typedef struct { int32_t line; int32_t offset; UChar pre[16]; UChar post[16]; } UParseError;
void* uregex_open_VER(const UChar*, int32_t, uint32_t, UParseError*, UErrorCode*);
void uregex_close_VER(void*);
int main(int argc, char** argv) {
    FILE* f = fopen(argv[1], "r"); static char line[8192];
    while (fgets(line, sizeof line, f)) {
        size_t len = strlen(line); if (len && line[len-1] == '\n') line[--len] = 0;
        static UChar u[8192]; for (size_t i = 0; i < len; i++) u[i] = (unsigned char)line[i];
        UParseError pe; memset(&pe, 0, sizeof pe); UErrorCode st = 0;
        void* r = uregex_open_VER(u, (int32_t)len, 0, &pe, &st);
        if (st > 0) printf("REJECT %s\n", line);
        if (r) uregex_close_VER(r);
    }
    return 0;
}
"""

    /** Path of the compiled checker, or null if ICU4C or a C compiler isn't available. */
    fun checker(): File? {
        val lib = listOf("/lib/x86_64-linux-gnu", "/usr/lib/x86_64-linux-gnu", "/lib/aarch64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/usr/lib64", "/usr/lib")
            .map(::File).flatMap { d -> d.listFiles { f -> Regex("""libicui18n\.so\.\d+""").matches(f.name) }?.toList() ?: emptyList() }
            .firstOrNull() ?: return null
        val version = lib.name.substringAfterLast('.')
        val uc = File(lib.parentFile, "libicuuc.so.$version")
        val dir = Files.createTempDirectory("icucheck").toFile()
        val src = File(dir, "icucheck.c").apply { writeText(SOURCE.replace("_VER", "_$version")) }
        val exe = File(dir, "icucheck")
        return try {
            val p = ProcessBuilder("cc", "-o", exe.path, src.path, lib.path, uc.path).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0 && exe.canExecute()) exe else null
        } catch (e: Exception) {
            null
        }
    }

    fun rejected(checker: File, patterns: List<String>): List<String> {
        val input = File(checker.parentFile, "patterns.txt").apply { writeText(patterns.joinToString("\n") + "\n") }
        val p = ProcessBuilder(checker.path, input.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readLines()
        p.waitFor(60, TimeUnit.SECONDS)
        return out.filter { it.startsWith("REJECT ") }.map { it.removePrefix("REJECT ") }
    }
}
