package com.repolens.app.testing

import java.io.File
import java.util.Random

/**
 * Generates a large, realistic-looking repository deterministically: thousands of Kotlin, Java,
 * TypeScript and Python files with many definitions, locals and cross-file calls, plus
 * pathological files. Known facts (how often `sharedTarget` is called, …) are returned so tests
 * can check the index's answers exactly.
 */
object LargeRepoFixture {
    class Facts(
        val files: Int,
        val bytes: Long,
        /** Calls to the Kotlin `sharedTarget` from Kotlin and Java files (JVM family). */
        val jvmSharedTargetCalls: Int,
        /** Calls to the Python `sharedTarget` from Python files. */
        val pySharedTargetCalls: Int,
        /** `Widget` (Kotlin class) uses in Java files. */
        val widgetUses: Int,
        val kotlinDefFile: String,
        val pythonDefFile: String,
        val someJavaFile: String,
    )

    private const val FUNCTIONS = 24

    private fun lang(i: Int) = when (i % 10) { 0, 1, 2, 3 -> "kt"; 4, 5, 6 -> "java"; 7, 8 -> "ts"; else -> "py" }
    private fun pathOf(i: Int) = "module${i / 400}/src/pkg${i % 37}/Unit$i.${lang(i)}"

    fun generate(root: File, fileCount: Int = 9000): Facts {
        val rnd = Random(42)
        var bytes = 0L
        var jvmCalls = 0
        var pyCalls = 0
        var widgetUses = 0
        var someJava = ""
        fun write(path: String, text: String) {
            val f = File(root, path)
            f.parentFile!!.mkdirs()
            f.writeText(text)
            bytes += f.length()
        }
        // a call to a function defined in another file of the same language
        fun other(i: Int): String {
            var k = rnd.nextInt(fileCount)
            while (lang(k) != lang(i)) k = (k + 1) % fileCount
            return "op${k}x${rnd.nextInt(FUNCTIONS)}"
        }
        for (i in 0 until fileCount) {
            val sb = StringBuilder(8192)
            when (lang(i)) {
                "kt" -> {
                    sb.append("package gen.m${i / 400}\n\n/** Generated unit $i. */\nclass Unit$i(val seed: Int) {\n")
                    if (i == 0) sb.append("    fun sharedTarget(x: Int): Int = x * 2\n\n    class Widget(val size: Int)\n\n")
                    for (j in 0 until FUNCTIONS) {
                        sb.append("    fun op${i}x$j(input: Int): Int {\n")
                        sb.append("        val first$j = input + ${rnd.nextInt(1000)} // keep it ${rnd.nextInt(99)}\n")
                        sb.append("        val second = first$j * ${other(i)}(first$j) - seed\n")
                        sb.append("        val label = \"unit $i function $j\"\n")
                        sb.append("        return second + ${other(i)}(second) + label.length\n    }\n\n")
                    }
                    if (i % 50 == 1) { sb.append("    fun callShared() = sharedTarget(seed)\n"); jvmCalls++ }
                    sb.append("}\n")
                }
                "java" -> {
                    if (someJava.isEmpty()) someJava = pathOf(i)
                    sb.append("package gen.m${i / 400};\n\nimport java.util.List;\n\npublic class Unit$i {\n    private final int seed;\n\n")
                    sb.append("    public Unit$i(int seed) { this.seed = seed; }\n\n")
                    for (j in 0 until FUNCTIONS) {
                        sb.append("    public int op${i}x$j(int input) {\n")
                        sb.append("        int first = input + ${rnd.nextInt(1000)};\n")
                        sb.append("        List<Integer> values = java.util.Arrays.asList(first, seed, ${rnd.nextInt(50)});\n")
                        sb.append("        return ${other(i)}(first) + values.size() + ${other(i)}(seed);\n    }\n\n")
                    }
                    if (i % 50 == 4) { sb.append("    int callShared() { return sharedTarget(seed); }\n"); jvmCalls++ }
                    if (i % 40 == 5) { sb.append("    Object make() { return new Widget(3); }\n"); widgetUses++ }
                    sb.append("}\n")
                }
                "ts" -> {
                    sb.append("import { helper } from './helper';\n\n")
                    for (j in 0 until FUNCTIONS) {
                        sb.append("export function op${i}x$j(input: number): number {\n")
                        sb.append("  const first = input + ${rnd.nextInt(1000)};\n")
                        sb.append("  const text = `unit $i ${'$'}{first}`;\n")
                        sb.append("  return ${other(i)}(first) + text.length + ${other(i)}(first);\n}\n\n")
                    }
                }
                else -> {
                    sb.append("\"\"\"Generated unit $i.\"\"\"\nimport os\n\n")
                    if (i == 9) sb.append("def sharedTarget(x):\n    return x * 2\n\n")
                    for (j in 0 until FUNCTIONS) {
                        sb.append("def op${i}x$j(value):\n")
                        sb.append("    first = value + ${rnd.nextInt(1000)}  # note ${rnd.nextInt(99)}\n")
                        sb.append("    return ${other(i)}(first) + ${other(i)}(value)\n\n")
                    }
                    if (i % 100 == 19) { sb.append("def call_shared():\n    return sharedTarget(1)\n"); pyCalls++ }
                }
            }
            write(pathOf(i), sb.toString())
        }

        // ---- pathological files ----
        val minified = StringBuilder(1_000_000)
        var n = 0
        while (minified.length < 999_000) minified.append("function f${n}(a){return a+${n++}};")
        write("pathological/minified.js", minified.toString())
        write("pathological/Generated.kt", buildString { for (k in 0 until 120_000) append("    // generated line ").append(k).append('\n') })
        write("pathological/manyLines.ts", buildString { append("export function manyLinesStart() {}\n"); repeat(100_001) { append("x = 1;\n") } })
        File(root, "pathological/binary.dat").writeBytes(ByteArray(200_000) { (it * 31 % 256).toByte() })
        File(root, "pathological/nul.kt").writeBytes("fun hidden() = 1\n".toByteArray() + ByteArray(64) + "fun more() = 2\n".toByteArray())
        File(root, "pathological/empty.kt").writeText("")
        write("pathological/CrlfThing.java", "package p;\r\n\r\npublic class CrlfThing {\r\n    int value() { return 1; }\r\n}\r\n")
        write("pathological/emoji.py", "# héllo 👋🏽 שלום é ‮evil‬ 𝔘𝔫𝔦𝔠𝔬𝔡𝔢\n\ndef wave_🙂():\n    pass\n\ndef emoji_target():\n    return \"👋🏽👋🏽\"\n\nemoji_target()\n")
        File(root, "pathological/photo.png").writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3))

        return Facts(
            files = fileCount, bytes = bytes,
            jvmSharedTargetCalls = jvmCalls, pySharedTargetCalls = pyCalls, widgetUses = widgetUses,
            kotlinDefFile = pathOf(0), pythonDefFile = pathOf(9), someJavaFile = someJava,
        )
    }

    /** `java ... LargeRepoFixture <dir> [files]` writes a fixture for manual measurements. */
    @JvmStatic fun main(args: Array<String>) {
        val f = generate(File(args[0]), args.getOrNull(1)?.toInt() ?: 9000)
        println("generated ${f.files} files, ${f.bytes / 1048576} MB")
    }
}
