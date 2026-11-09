package com.repolens.app.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GitHubUrlTest {
    @Test fun parsesCommonForms() {
        assertEquals(RepoRef("square", "okhttp"), RepoRef.parse("https://github.com/square/okhttp"))
        assertEquals(RepoRef("square", "okhttp"), RepoRef.parse("github.com/square/okhttp.git"))
        assertEquals(RepoRef("square", "okhttp"), RepoRef.parse("  square/okhttp/ "))
        assertEquals(RepoRef("square", "okhttp"), RepoRef.parse("git@github.com:square/okhttp.git"))
        assertEquals(RepoRef("square", "okhttp"), RepoRef.parse("https://www.github.com/square/okhttp?tab=readme"))
        assertEquals(RepoRef("a", "b", "dev"), RepoRef.parse("https://github.com/a/b/tree/dev"))
        assertEquals(RepoRef("a", "b", "main", "src/Main.kt"), RepoRef.parse("https://github.com/a/b/blob/main/src/Main.kt"))
        assertEquals(RepoRef("torvalds", "linux"), RepoRef.parse("Check this out: https://github.com/torvalds/linux cool right"))
    }

    @Test fun rejectsGarbage() {
        assertNull(RepoRef.parse("hello"))
        assertNull(RepoRef.parse("https://github.com/onlyowner"))
        assertNull(RepoRef.parse(""))
    }
}

class DefinitionFinderTest {
    private fun assertHas(d: List<String>, expected: List<String>) =
        assertTrue("missing ${expected - d.toSet()} in $d", d.containsAll(expected))
    private fun assertNone(d: List<String>, vararg names: String) =
        assertTrue("unexpected ${d.filter { x -> names.any { x.endsWith(":$it") } }}", d.none { x -> names.any { x.endsWith(":$it") } })

    private fun defs(lang: Language, code: String): List<String> {
        val lexer = Lexer(lang)
        return SourceText.splitLines(code.trimIndent()).flatMap { line ->
            DefinitionFinder.find(lang, lexer.lexLine(line).masked()).map { "${it.kind.label}:${it.name}" }
        }
    }

    @Test fun kotlin() {
        val d = defs(Languages.KOTLIN, """
            class Repo(val name: String) {
                fun load(): Int = 1
                private suspend fun <T> List<T>.second(): T = this[1]
                // fun commentedOut()
                val s = "fun inString()"
                companion object { fun create() = Repo("x") }
            }
            data class Point(val x: Int)
            interface Shape
            object Registry
        """)
        assertHas(d, listOf("type:Repo", "function:load", "function:second", "function:create", "type:Point", "type:Shape", "type:Registry", "variable:s"))
        assertTrue(d.none { "commentedOut" in it || "inString" in it })
    }

    @Test fun java() {
        val d = defs(Languages.JAVA, """
            public class Server implements Runnable {
                @Override
                public void run() {
                    if (ready()) { start(); }
                    String s = format(x);
                    return compute(1);
                }
                private static <T> List<T> listOf(T... items) {
                public Server(int port) {
                Map<String, Integer> counts(List<String> words) {
                interface Handler {
                    void handle(Request r);
                    System.out.println(x);
                    new Thread(r).start();
                    foo(bar);
            }
        """)
        assertHas(d, listOf("type:Server", "function:run", "function:listOf", "function:Server", "function:counts", "type:Handler", "function:handle"))
        assertTrue(d.none { it.endsWith(":ready") || it.endsWith(":start") || it.endsWith(":format") || it.endsWith(":compute") || it.endsWith(":println") || it.endsWith(":foo") })
    }

    @Test fun cAndCpp() {
        val c = defs(Languages.C, """
            #include <stdio.h>
            #define MAX_LEN 128
            static int parse_line(char *line, size_t n) {
            struct node *make_node(int v);
            typedef struct { int x; } point_t;
                printf("x");
                if (x) return helper(y);
                result = compute(a, b);
        """)
        assertHas(c, listOf("macro:MAX_LEN", "function:parse_line", "function:make_node", "type:point_t"))
        assertTrue(c.none { it.endsWith(":printf") || it.endsWith(":helper") || it.endsWith(":compute") })

        val cpp = defs(Languages.CPP, """
            namespace net {
            class Socket : public Base {
            void Socket::connect(const std::string& host) {
            Socket::Socket(int fd) : fd_(fd) {
            std::vector<int> Socket::ports() const {
                EXPECT_EQ(a, b);
                obj->send(data);
        """)
        assertHas(cpp, listOf("type:net", "type:Socket", "function:connect", "function:Socket", "function:ports"))
        assertTrue(cpp.none { it.endsWith(":EXPECT_EQ") || it.endsWith(":send") })
    }

    @Test fun javascriptAndTypescript() {
        val d = defs(Languages.TYPESCRIPT, """
            export function render(el: Element): void {
            const handler = async (e) => {
            let count = 0;
            export class Store<T> {
              private items: T[] = [];
              add(item: T): void {
              async fetchAll() {
              get size() {
            }
            interface Props { title: string }
            type Id = string | number;
            describe('store', () => {
              it('adds', () => {
                if (ok) {
                store.add(1);
                foo(bar).then(x => {
            const api = { load: function() {}, save: (x) => x };
        """)
        assertHas(d, listOf("function:render", "function:handler", "variable:count", "type:Store", "function:add", "function:fetchAll", "function:size", "type:Props", "type:Id", "function:load", "function:save"))
        assertTrue(d.none { it.endsWith(":describe") || it.endsWith(":it") || it.endsWith(":foo") || it.endsWith(":then") })
    }

    @Test fun pythonGoRustSwiftRuby() {
        assertEquals(listOf("type:Parser", "function:parse", "function:helper", "variable:VERSION"), defs(Languages.PYTHON, """
            class Parser(Base):
                def parse(self, text):
                    return helper2(text)  # def fake():
            async def helper():
            VERSION = "1.0"
                x = 3
        """))
        assertEquals(listOf("function:main", "function:Serve", "type:Server", "variable:Timeout"), defs(Languages.GO, """
            func main() {
            func (s *Server) Serve(addr string) error {
                fmt.Println("x")
            type Server struct {
            var Timeout = 5
        """))
        assertTrue(defs(Languages.RUST, """
            pub fn run(cfg: &Config) -> Result<()> {
            struct Config<'a> { name: &'a str }
            impl Config { fn new() -> Self {
            macro_rules! log {
                let mut total = 0;
        """).containsAll(listOf("function:run", "type:Config", "function:new", "macro:log", "variable:total")))
        assertHas(defs(Languages.SWIFT, "struct Card {\n    func flip() -> Bool {\n"), listOf("type:Card", "function:flip"))
        assertEquals(listOf("type:User", "function:valid?", "function:build"), defs(Languages.RUBY, """
            class User < ApplicationRecord
              def valid?
              def self.build(attrs)
        """))
    }

    @Test fun dartFlutterArgumentsAreNotDefinitions() {
        val d = defs(Languages.DART, """
            class Home extends StatelessWidget {
              @override
              Widget build(BuildContext context) {
                return Scaffold(
                  body: Center(
                    child: Text('hi'),
                  ),
                );
              }
              void _tap() {
                setState(() {
            }
        """)
        assertEquals(listOf("type:Home", "function:build", "function:_tap"), d)
    }
}

class SymbolIndexTest {
    private fun repo(vararg files: Pair<String, String>): File {
        val dir = Files.createTempDirectory("repolens").toFile()
        for ((path, text) in files) File(dir, path).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
        return dir
    }

    @Test fun goToDefinitionAndReferencesAcrossFiles() = runBlocking {
        val root = repo(
            "src/util/Math.kt" to """
                package util
                fun square(x: Int) = x * x
                class Calculator { fun total(xs: List<Int>) = xs.sumOf { square(it) } }
            """,
            "src/app/Main.kt" to """
                import util.square
                fun main() {
                    val c = Calculator()
                    println(square(3) + c.total(listOf(1, 2)))
                    // square in a comment is not a reference
                }
            """,
            "src/app/Helper.java" to """
                class Helper {
                    int twice(int v) { return Math.addExact(v, v); }
                    void use() { Calculator c = new Calculator(); }
                }
            """,
            "scripts/tool.py" to """
                def square(x):
                    return x * x
                print(square(2))
            """,
            "README.md" to "square everything",
        )
        val index = SymbolIndex.build(root)

        val defs = index.definitionsFor("square", "src/app/Main.kt", 3)
        assertEquals(1, defs.size) // the Python one is a different language family
        assertEquals("src/util/Math.kt", defs[0].file.path)
        assertEquals(1, defs[0].line)

        val refs = index.referencesTo(defs[0]).map { "${it.file.path}:${it.line}" }.sorted()
        assertEquals(listOf("src/app/Main.kt:0", "src/app/Main.kt:3", "src/util/Math.kt:2"), refs)
        assertEquals(3, index.referenceCount(defs[0]))

        val py = index.definitionsFor("square", "scripts/tool.py", 2).single()
        assertEquals(1, index.referenceCount(py))

        // Kotlin and Java share the JVM family, so Java's use of Calculator counts.
        val calc = index.definitionsFor("Calculator", "src/app/Main.kt", 2).single()
        assertEquals(3, index.referenceCount(calc))

        assertEquals(calc, index.definitionAt("src/util/Math.kt", 2, 6))
        assertTrue(index.searchSymbols("calc").any { it.name == "Calculator" })

        val words = index.searchWord("addExact", Family.JVM)
        assertEquals(1, words.size)
        root.deleteRecursively()
        Unit
    }

    @Test fun localVariablesStayInTheirFile() = runBlocking {
        val root = repo(
            "a.ts" to "function a() {\n    const result = 1;\n    return result;\n}",
            "b.ts" to "function b() {\n    const result = 2;\n    return result + result;\n}",
        )
        val index = SymbolIndex.build(root)
        val d = index.definitionsFor("result", "b.ts", 2)
        assertEquals(listOf("b.ts"), d.map { it.file.path })
        assertEquals(2, index.referencesTo(d[0]).size)
        root.deleteRecursively()
        Unit
    }
}

class UnzipTest {
    @Test fun stripsTopFolderAndBlocksZipSlip() {
        val dir = Files.createTempDirectory("zip").toFile()
        val zip = File(dir, "r.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("repo-main/")); z.closeEntry()
            z.putNextEntry(ZipEntry("repo-main/src/A.kt")); z.write("fun a() = 1".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("repo-main/../../evil.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        val out = File(dir, "out")
        RepoDownloader.unzipStrippingTopFolder(zip, out)
        assertEquals("fun a() = 1", File(out, "src/A.kt").readText())
        assertTrue(!File(dir.parentFile, "evil.txt").exists() && !File(dir, "evil.txt").exists())
        dir.deleteRecursively()
    }
}

class LexerTest {
    @Test fun multiLineStateAndMasking() {
        val lexer = Lexer(Languages.KOTLIN)
        val lines = lexer.lexAll(listOf("val a = 1 /* start", "fun hidden() {}", "end */ fun shown() {}", "val s = \"\"\"", "fun alsoHidden()", "\"\"\""))
        assertTrue(lines[1].masked().isBlank())
        assertTrue(lines[2].masked().contains("fun shown"))
        assertTrue(lines[4].masked().isBlank())
        val l = Lexer(Languages.JAVA).lexLine("int x = foo(\"a(b\") + Bar.baz;")
        val types = (0 until l.tokenCount).map { l.text.substring(l.start(it), l.end(it)) to l.type(it) }.toMap()
        assertEquals(TokenType.KEYWORD, types["int"])
        assertEquals(TokenType.FUNCTION, types["foo"])
        assertEquals(TokenType.STRING, types["\"a(b\""])
        assertEquals(TokenType.TYPE, types["Bar"])
        assertEquals(TokenType.IDENT, types["baz"])
    }
}
