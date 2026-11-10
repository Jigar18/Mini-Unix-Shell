package com.repolens.app.core

import com.repolens.app.DocLoader
import com.repolens.app.testing.LargeRepoFixture
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * A 9000-file generated repository (48 MB of Kotlin/Java/TS/Python, 575k definitions) plus
 * pathological files. 1.0.0 needed more than 160 MB of heap to index it (and hung for good when it
 * ran out); phones commonly give an app 128–256 MB.
 */
class LargeRepoTest {
    companion object {
        private lateinit var root: File
        private lateinit var facts: LargeRepoFixture.Facts

        @JvmStatic @BeforeClass fun generate() {
            root = Files.createTempDirectory("large-repo").toFile()
            facts = LargeRepoFixture.generate(root)
        }

        @JvmStatic @AfterClass fun cleanUp() { root.deleteRecursively() }
    }

    /** Indexing + opening the biggest files in a separate JVM limited to 160 MB of heap. */
    @Test fun indexesAndOpensFilesWithin160MbHeap() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cp = System.getProperty("java.class.path")
        val p = ProcessBuilder(
            java, "-Xmx160m", "-XX:ActiveProcessorCount=8", // a phone has 8 cores
            "-cp", cp, IndexHarness::class.java.name, root.path,
        ).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { start() }
        val finished = p.waitFor(240, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(5_000)
        val out = output.toString()
        println(out.lines().filter { it.startsWith("INDEX") || it.startsWith("RESULT") || it.startsWith("OPEN") }.joinToString("\n"))
        assertTrue("child JVM did not finish in time (hung after running out of memory?):\n$out", finished)
        assertEquals("child JVM failed:\n$out", 0, p.exitValue())
        assertTrue(out, out.contains("RESULT ok"))
        assertTrue(out, out.contains("INDEX files=${facts.files + 5}/"))
    }

    @Test fun answersAreRightOnTheLargeRepo() = runBlocking {
        val index = SymbolIndex.build(root, IndexLimits.forHeap(maxMemory = 512L shl 20))
        assertNull(index.note)
        // generated files + manyLines.ts, CrlfThing.java, emoji.py, nul.kt (binary: no defs) and Generated.kt? no: > 1 MB
        assertEquals(facts.files + 5, index.files.size)

        val shared = index.definitionsFor("sharedTarget", facts.someJavaFile, 3)
        assertEquals(listOf(facts.kotlinDefFile), shared.map { it.file.path }) // the Python one is another family
        assertEquals(facts.jvmSharedTargetCalls, index.referenceCount(shared.single()))
        assertEquals(facts.jvmSharedTargetCalls, index.referencesTo(shared.single()).size)

        val py = index.definitionsFor("sharedTarget", facts.pythonDefFile, 0).single()
        assertEquals(facts.pythonDefFile, py.file.path)
        assertEquals(facts.pySharedTargetCalls, index.referenceCount(py))

        val widget = index.definitionsFor("Widget", facts.someJavaFile, 0).single()
        assertEquals(SymbolKind.CLASS, widget.kind)
        assertEquals(facts.widgetUses, index.referenceCount(widget))
        assertEquals(widget, index.definitionAt(widget.file.path, widget.line, widget.col))

        // a local declared in many files resolves to the one in the current file only
        val local = index.definitionsFor("first3", facts.kotlinDefFile, 20)
        assertEquals(listOf(facts.kotlinDefFile), local.map { it.file.path })

        // pathological files
        val crlf = index.definitionsFor("CrlfThing", "pathological/CrlfThing.java", 0).single()
        assertEquals(2, crlf.line)
        assertEquals(1, index.definitionsFor("emoji_target", "pathological/emoji.py", 0).size)
        assertEquals(1, index.referenceCount(index.definitionsFor("emoji_target", "pathological/emoji.py", 0).single()))
        assertTrue(index.definitionsInFile("pathological/minified.js").isEmpty()) // minified: not indexed
        assertTrue(index.definitionsInFile("pathological/nul.kt").isEmpty()) // binary
        assertNotNull(index.definitionsFor("manyLinesStart", "pathological/manyLines.ts", 0).singleOrNull())
        assertTrue(index.searchSymbols("op12x").isNotEmpty())
    }

    @Test fun capsIndexAPrioritisedSubsetAndSayWhy() = runBlocking {
        val small = SymbolIndex.build(root, IndexLimits(maxFiles = 100, maxTotalBytes = 1L shl 40, maxOccurrences = Int.MAX_VALUE, parallelism = 2))
        assertEquals(100, small.files.size)
        assertEquals(facts.files + 5, small.candidateFiles)
        assertTrue(small.note!!, small.note!!.startsWith("Navigation limited to 100 of ${facts.files + 5} source files"))
        assertEquals(small.files.sortedBy { it.path }.map { it.path }, small.files.map { it.path })
        assertEquals(small.files.indices.toList(), small.files.map { it.id })

        val byBytes = SymbolIndex.build(root, IndexLimits(maxFiles = 100_000, maxTotalBytes = 1_000_000, maxOccurrences = Int.MAX_VALUE, parallelism = 2))
        assertTrue(byBytes.files.sumOf { File(root, it.path).length() } <= 1_000_000)
        assertNotNull(byBytes.note)

        val fewRefs = SymbolIndex.build(root, IndexLimits(maxFiles = 100_000, maxTotalBytes = 1L shl 40, maxOccurrences = 10_000, parallelism = 2))
        assertEquals(facts.files + 5, fewRefs.files.size) // every file still has its definitions
        assertTrue(fewRefs.note!!, fewRefs.note!!.contains("References are only counted in the first"))
        assertTrue(fewRefs.referenceTotal < 20_000)
    }

    @Test fun hugeAndMinifiedFilesOpenWithinLimits() {
        val limits = DocLoader.limitsFor(160L shl 20)
        val minified = DocLoader.load(root, "pathological/minified.js", limits)
        assertEquals(1, minified.lines!!.size)
        assertTrue(minified.lines!![0].text.length < DocLoader.MAX_LINE_CHARS + 100)
        assertTrue(minified.banner!!, minified.banner!!.contains("cut for display"))
        val many = DocLoader.load(root, "pathological/manyLines.ts", limits)
        assertEquals(100_002, many.lines!!.size)
        val capped = DocLoader.load(root, "pathological/manyLines.ts", DocLoader.Limits(limits.maxBytes, 50_000, 1000))
        assertEquals(50_000, capped.lines!!.size)
        assertTrue(capped.banner!!.startsWith("Showing the first 50000 of 100002 lines"))
        assertNotNull(DocLoader.load(root, "pathological/binary.dat").note)
        assertNotNull(DocLoader.load(root, "pathological/nul.kt").note)
        assertEquals(1, DocLoader.load(root, "pathological/empty.kt").lines!!.size)
        assertEquals("public class CrlfThing {", DocLoader.load(root, "pathological/CrlfThing.java").lines!![2].text)
        assertTrue(DocLoader.load(root, "pathological/emoji.py").lines!![0].text.contains("👋🏽"))
        val tooBig = DocLoader.load(root, "pathological/Generated.kt", DocLoader.Limits(1L shl 20, 200_000, 20_000))
        assertTrue(tooBig.note!!, tooBig.note!!.contains("too large to display"))
        assertEquals("This path is outside the repository.", DocLoader.load(root, "../etc/passwd").note)
        assertEquals("This file no longer exists.", DocLoader.load(root, "nope.kt").note)
    }
}
