package com.repolens.app.core

import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Small main() used to measure indexing + opening big files under a constrained heap, e.g.
 * `java -Xmx128m -cp <test classpath> com.repolens.app.core.IndexHarness <repo dir>`.
 * Prints `RESULT ok ...` (exit 0) or `RESULT oom ...` (exit 3); any other failure exits 4.
 * `LargeRepoTest` runs it in a child JVM with a small `-Xmx`.
 */
object IndexHarness {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0])
        val peak = AtomicLong()
        val rt = Runtime.getRuntime()
        val sampler = Thread {
            while (true) {
                val u = rt.totalMemory() - rt.freeMemory()
                if (u > peak.get()) peak.set(u)
                try { Thread.sleep(2) } catch (e: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        val t0 = System.nanoTime()
        try {
            val index = runBlocking { SymbolIndex.build(root) }
            val t1 = System.nanoTime()
            System.gc(); Thread.sleep(200); System.gc()
            val retained = rt.totalMemory() - rt.freeMemory()
            println("INDEX files=${index.files.size}/${index.candidateFiles} symbols=${index.symbolCount} refs=${index.referenceTotal} timeMs=${(t1 - t0) / 1_000_000} retainedMB=${retained / 1048576} note=${index.note}")
            // open the five biggest files the way the app does
            val biggest = root.walkTopDown().filter { it.isFile }.sortedByDescending { it.length() }.take(5).toList()
            for (f in biggest) {
                val doc = com.repolens.app.DocLoader.load(root, f.relativeTo(root).path.replace(File.separatorChar, '/'))
                println("OPEN ${f.name} bytes=${f.length()} lines=${doc.lines?.size} note=${doc.note} banner=${doc.banner}")
            }
            println("RESULT ok peakMB=${peak.get() / 1048576} max=${Runtime.getRuntime().maxMemory() / 1048576} timeMs=${(System.nanoTime() - t0) / 1_000_000} keep=${index.files.size}")
        } catch (e: OutOfMemoryError) {
            println("RESULT oom peakMB=${peak.get() / 1048576} max=${Runtime.getRuntime().maxMemory() / 1048576} afterMs=${(System.nanoTime() - t0) / 1_000_000}")
            System.out.flush()
            Runtime.getRuntime().halt(3)
        } catch (e: Throwable) {
            println("RESULT error $e")
            e.printStackTrace(System.out)
            System.out.flush()
            Runtime.getRuntime().halt(4)
        }
        sampler.interrupt()
    }
}
