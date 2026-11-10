package com.repolens.app.core

/**
 * Languages that share a family can call each other's symbols (e.g. Kotlin calling Java, a .h
 * declaring what a .c defines). Go-to-definition and references never cross families, so a
 * Python `init` is never offered as the definition of a JavaScript `init`.
 */
enum class Family { JVM, C, WEB, PYTHON, GO, RUST, SWIFT, RUBY, PHP, DART, CSHARP, SHELL, LUA, DATA }

enum class SymbolKind(val label: String, val showsReferenceLens: Boolean) {
    FUNCTION("function", true),
    CLASS("type", true),
    VARIABLE("variable", false),
    MACRO("macro", true),
}

/**
 * A regex whose [group] captures the name of a symbol defined on that (masked) line.
 *
 * On Android `java.util.regex` is implemented by ICU, which is stricter than the JVM engine the
 * unit tests run on: an unescaped `}` (accepted by the JVM) is a syntax error there. These
 * patterns are compiled while the [Languages] object initialises, so a single bad pattern used to
 * make `Languages` unusable and crash the app the moment any repository was opened. Patterns
 * must be ICU-compatible (see `RegexCompatibilityTest`); if one still fails to compile on some
 * device it is dropped instead of taking the whole class down.
 */
class DefPattern(val pattern: String, val kind: SymbolKind, val group: Int = 1) {
    val regex: Regex? = compileOrNull(pattern)

    companion object {
        fun compileOrNull(pattern: String): Regex? = try {
            Regex(pattern)
        } catch (e: Exception) {
            null
        }
    }
}

class Language(
    val name: String,
    val family: Family,
    val extensions: Set<String>,
    keywords: String,
    val lineComments: List<String> = listOf("//"),
    val blockComment: Pair<String, String>? = "/*" to "*/",
    /** Characters that start a single-line string. */
    val stringDelims: String = "\"'",
    /** `"""` / `'''` strings that may span lines. */
    val tripleQuoted: Boolean = false,
    /** Backtick strings that may span lines (JS template literals, Go raw strings). */
    val backtickMultiline: Boolean = false,
    /** `#include`-style preprocessor lines. */
    val preprocessor: Boolean = false,
    /** Characters besides letters/digits/_ allowed inside identifiers. */
    val extraIdentChars: String = "",
    /** Characters allowed once at the very end of an identifier (Ruby's `empty?`, `save!`). */
    val trailingIdentChars: String = "",
    val defPatterns: List<DefPattern> = emptyList(),
    /** Use the C-style "type name(...)" signature detector for methods. */
    val cStyleSignatures: Boolean = false,
    /** Allow `name(...) {` with no return type (JS/TS/Dart class methods). */
    val allowUntypedMethods: Boolean = false,
    /** Names that the def patterns may capture but which are never real symbols. */
    val notSymbols: Set<String> = emptySet(),
    /**
     * Functions are only ever used by calling them (`f(`, trailing lambda `f {`) or with `::f`,
     * so a bare `f` is a variable of the same name, not a use of the function. Not true where
     * functions are passed around as values (JS, Python, Go, Dart tear-offs, C callbacks…) or
     * called without parentheses (Ruby, Scala, Groovy).
     */
    val functionUsesAreCalls: Boolean = false,
) {
    val keywords: Set<String> = keywords.split(' ', '\n').filter { it.isNotBlank() }.toHashSet()
    val indexable: Boolean get() = defPatterns.isNotEmpty() || cStyleSignatures

    override fun toString() = name
}

object Languages {
    private const val IDENT = "[A-Za-z_][A-Za-z0-9_]*"
    private const val JS_IDENT = "[A-Za-z_$][A-Za-z0-9_$]*"

    private val C_TYPES = listOf(
        DefPattern("""\b(?:class|struct|union|enum|namespace)\s+(?:class\s+|struct\s+)?(?:[A-Z_]+\s+)?($IDENT)\s*(?:final\s*)?(?:[:{]|$)""", SymbolKind.CLASS),
        DefPattern("""^\s*#\s*define\s+($IDENT)""", SymbolKind.MACRO),
        DefPattern("""^\s*\}\s*($IDENT)\s*;""", SymbolKind.CLASS),
        DefPattern("""^\s*typedef\b[^;({]*\b($IDENT)\s*;""", SymbolKind.CLASS),
        DefPattern("""^\s*typedef\b.*\}\s*($IDENT)\s*;""", SymbolKind.CLASS),
        DefPattern("""^\s*typedef\b[^;]*\(\s*\*\s*($IDENT)\s*\)""", SymbolKind.CLASS),
    )

    val KOTLIN = Language(
        "Kotlin", Family.JVM, setOf("kt", "kts"),
        """package import class interface object fun val var typealias if else when for while do return break continue
           throw try catch finally in is as this super null true false constructor init companion data sealed enum
           annotation open abstract override private protected public internal lateinit const suspend inline
           noinline crossinline reified operator infix tailrec external vararg out by get set where actual expect value""",
        tripleQuoted = true,
        functionUsesAreCalls = true,
        defPatterns = listOf(
            DefPattern("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, *]+\.)?(`?$IDENT`?)\s*\(""", SymbolKind.FUNCTION),
            DefPattern("""\b(?:class|interface|object|typealias)\s+($IDENT)""", SymbolKind.CLASS),
            DefPattern("""\b(?:val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?($IDENT)""", SymbolKind.VARIABLE),
        ),
    )

    val JAVA = Language(
        "Java", Family.JVM, setOf("java"),
        """abstract assert boolean break byte case catch char class const continue default do double else enum extends
           final finally float for goto if implements import instanceof int interface long native new package private
           protected public return short static strictfp super switch synchronized this throw throws transient try void
           volatile while true false null var record sealed permits yield non-sealed""",
        cStyleSignatures = true,
        functionUsesAreCalls = true,
        defPatterns = listOf(
            DefPattern("""\b(?:class|interface|enum|record)\s+($IDENT)""", SymbolKind.CLASS),
            DefPattern("""^\s*@interface\s+($IDENT)""", SymbolKind.CLASS),
            // fields (and modified locals): `private final UserRepository userRepository;`, `@Id private Long id;`
            DefPattern(
                """^\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:private|protected|public|static|final|transient|volatile)\s+)+(?:@[\w.]+\s+)*[\w.]+(?:<[^;=()]*>)?(?:\[\s*\])*\s+($IDENT)\s*(?:[;=,]|$)""",
                SymbolKind.VARIABLE,
            ),
        ),
    )

    val SCALA = Language(
        "Scala", Family.JVM, setOf("scala", "sc"),
        """abstract case catch class def do else extends false final finally for forSome if implicit import lazy match new
           null object override package private protected return sealed super this throw trait try true type val var
           while with yield given using enum then export end""",
        tripleQuoted = true,
        defPatterns = listOf(
            DefPattern("""\bdef\s+($IDENT)""", SymbolKind.FUNCTION),
            DefPattern("""\b(?:class|object|trait|enum|type)\s+($IDENT)""", SymbolKind.CLASS),
            DefPattern("""\b(?:val|var)\s+($IDENT)""", SymbolKind.VARIABLE),
        ),
    )

    val GROOVY = Language(
        "Groovy", Family.JVM, setOf("groovy", "gradle"),
        """abstract as assert boolean break byte case catch char class continue def default do double else enum extends
           false final finally float for if implements import in instanceof int interface long native new null package
           private protected public return short static super switch synchronized this throw throws trait true try void
           volatile while""",
        tripleQuoted = true,
        cStyleSignatures = true,
        defPatterns = listOf(
            DefPattern("""\bdef\s+($IDENT)\s*\(""", SymbolKind.FUNCTION),
            DefPattern("""\b(?:class|interface|enum|trait)\s+($IDENT)""", SymbolKind.CLASS),
        ),
    )

    val C = Language(
        "C", Family.C, setOf("c", "h"),
        """auto break case char const continue default do double else enum extern float for goto if inline int long
           register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while
           _Bool _Complex _Atomic _Noreturn _Static_assert bool true false NULL""",
        preprocessor = true,
        cStyleSignatures = true,
        defPatterns = C_TYPES,
    )

    val CPP = Language(
        "C++", Family.C, setOf("cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++", "ino", "cu", "cuh", "m", "mm", "ipp", "tpp"),
        """alignas alignof and asm auto bool break case catch char char8_t char16_t char32_t class concept const consteval
           constexpr constinit const_cast continue co_await co_return co_yield decltype default delete do double
           dynamic_cast else enum explicit export extern false float for friend goto if inline int long mutable namespace
           new noexcept not nullptr operator or private protected public register reinterpret_cast requires return short
           signed sizeof static static_assert static_cast struct switch template this thread_local throw true try typedef
           typeid typename union unsigned using virtual void volatile wchar_t while override final NULL
           interface implementation end property self nil YES NO id""",
        preprocessor = true,
        cStyleSignatures = true,
        defPatterns = C_TYPES + listOf(
            DefPattern("""^\s*@(?:interface|implementation|protocol)\s+($IDENT)""", SymbolKind.CLASS),
            DefPattern("""^\s*[-+]\s*\([^)]*\)\s*($IDENT)""", SymbolKind.FUNCTION),
            DefPattern("""\busing\s+($IDENT)\s*=""", SymbolKind.CLASS),
        ),
    )

    val CSHARP = Language(
        "C#", Family.CSHARP, setOf("cs", "csx"),
        """abstract as base bool break byte case catch char checked class const continue decimal default delegate do double
           else enum event explicit extern false finally fixed float for foreach goto if implicit in int interface
           internal is lock long namespace new null object operator out override params private protected public readonly
           record ref return sbyte sealed short sizeof stackalloc static string struct switch this throw true try typeof
           uint ulong unchecked unsafe ushort using virtual void volatile while var async await get set init value yield
           partial where nameof when""",
        cStyleSignatures = true,
        defPatterns = listOf(
            DefPattern("""\b(?:class|interface|struct|enum|record|delegate\s+\S+)\s+($IDENT)""", SymbolKind.CLASS),
            DefPattern("""^\s*(?:(?:public|private|protected|internal|static|virtual|override|abstract|sealed|new|readonly|required)\s+)+[\w<>\[\],.?]+\s+($IDENT)\s*(?:\{|=>)""", SymbolKind.VARIABLE),
        ),
    )

    private val JS_KEYWORDS = """break case catch class const continue debugger default delete do else export extends false
        finally for function if import in instanceof let new null return super switch this throw true try typeof var void
        while with yield async await of static get set undefined from as enum implements interface package private
        protected public type namespace declare abstract readonly keyof infer is never unknown any module satisfies
        override"""

    private val JS_DEFS = listOf(
        DefPattern("""\bfunction\b\s*\*?\s*($JS_IDENT)\s*[(<]""", SymbolKind.FUNCTION),
        DefPattern("""\b(?:const|let|var)\s+($JS_IDENT)\s*(?::[^=]+)?=\s*(?:async\s*)?(?:function\b|\([^)]*\)\s*(?::[^=]+)?=>|$JS_IDENT\s*=>|<[^>]*>\s*\()""", SymbolKind.FUNCTION),
        DefPattern("""^\s*(?:(?:public|private|protected|static|readonly|override)\s+)*($JS_IDENT)\s*=\s*(?:async\s*)?\([^)]*\)\s*(?::[^=]+)?=>""", SymbolKind.FUNCTION),
        DefPattern("""(?:^|[{,]\s*)($JS_IDENT)\s*:\s*(?:async\s*)?(?:function\b|\([^)]*\)\s*=>)""", SymbolKind.FUNCTION),
        DefPattern("""\b(?:class|interface|enum|namespace)\s+($JS_IDENT)""", SymbolKind.CLASS),
        DefPattern("""^\s*(?:export\s+)?(?:declare\s+)?type\s+($JS_IDENT)\s*(?:<[^>]*>)?\s*=""", SymbolKind.CLASS),
        DefPattern("""\b(?:const|let|var)\s+($JS_IDENT)""", SymbolKind.VARIABLE),
    )

    val JAVASCRIPT = Language(
        "JavaScript", Family.WEB, setOf("js", "jsx", "mjs", "cjs", "vue", "svelte"),
        JS_KEYWORDS, stringDelims = "\"'", backtickMultiline = true, extraIdentChars = "$",
        cStyleSignatures = true, allowUntypedMethods = true, defPatterns = JS_DEFS,
        notSymbols = setOf("constructor"),
    )

    val TYPESCRIPT = Language(
        "TypeScript", Family.WEB, setOf("ts", "tsx", "mts", "cts"),
        JS_KEYWORDS + " string number boolean symbol bigint object", stringDelims = "\"'", backtickMultiline = true,
        extraIdentChars = "$", cStyleSignatures = true, allowUntypedMethods = true, defPatterns = JS_DEFS,
        notSymbols = setOf("constructor"),
    )

    val PYTHON = Language(
        "Python", Family.PYTHON, setOf("py", "pyi", "pyw"),
        """False None True and as assert async await break class continue def del elif else except finally for from global
           if import in is lambda nonlocal not or pass raise return try while with yield match case self cls""",
        lineComments = listOf("#"), blockComment = null, tripleQuoted = true,
        defPatterns = listOf(
            DefPattern("""^\s*(?:async\s+)?def\s+($IDENT)""", SymbolKind.FUNCTION),
            DefPattern("""^\s*class\s+($IDENT)""", SymbolKind.CLASS),
            DefPattern("""^($IDENT)\s*(?::[^=]+)?=(?!=)""", SymbolKind.VARIABLE),
        ),
    )

    val GO = Language(
        "Go", Family.GO, setOf("go"),
        """break case chan const continue default defer else fallthrough for func go goto if import interface map package
           range return select struct switch type var true false nil iota""",
        stringDelims = "\"'", backtickMultiline = true,
        defPatterns = listOf(
            DefPattern("""^func\s+(?:\([^)]*\)\s*)?($IDENT)""", SymbolKind.FUNCTION),
            DefPattern("""^\s*type\s+($IDENT)""", SymbolKind.CLASS),
            DefPattern("""^\s+($IDENT)\s+(?:struct|interface)\s*\{""", SymbolKind.CLASS),
            DefPattern("""^(?:var|const)\s+($IDENT)""", SymbolKind.VARIABLE),
        ),
    )

    val RUST = Language(
        "Rust", Family.RUST, setOf("rs"),
        """as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut
           pub ref return self Self static struct super trait true type unsafe use where while union macro_rules""",
        stringDelims = "\"'",
        defPatterns = listOf(
            DefPattern("""\bfn\s+($IDENT)""", SymbolKind.FUNCTION),
            DefPattern("""\b(?:struct|enum|trait|type|union|mod)\s+($IDENT)""", SymbolKind.CLASS),
            DefPattern("""\bmacro_rules!\s*($IDENT)""", SymbolKind.MACRO),
            DefPattern("""\b(?:const|static)\s+(?:mut\s+)?($IDENT)\s*:""", SymbolKind.VARIABLE),
            DefPattern("""\blet\s+(?:mut\s+)?($IDENT)""", SymbolKind.VARIABLE),
        ),
    )

    val SWIFT = Language(
        "Swift", Family.SWIFT, setOf("swift"),
        """associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private
           protocol public rethrows static struct subscript typealias var break case continue default defer do else
           fallthrough for guard if in repeat return switch where while as Any catch false is nil super self Self throw
           throws true try async await actor some any mutating override final lazy weak unowned convenience required""",
        tripleQuoted = true, stringDelims = "\"",
        defPatterns = listOf(
            DefPattern("""\bfunc\s+($IDENT)""", SymbolKind.FUNCTION),
            DefPattern("""\b(?:class|struct|enum|protocol|actor|typealias)\s+($IDENT)""", SymbolKind.CLASS),
            DefPattern("""\b(?:let|var)\s+($IDENT)""", SymbolKind.VARIABLE),
        ),
    )

    val RUBY = Language(
        "Ruby", Family.RUBY, setOf("rb", "rake", "gemspec", "ru"),
        """BEGIN END alias and begin break case class def defined? do else elsif end ensure false for if in module next nil
           not or redo rescue retry return self super then true undef unless until when while yield require attr_reader
           attr_writer attr_accessor private protected public""",
        lineComments = listOf("#"), blockComment = "=begin" to "=end", trailingIdentChars = "?!",
        defPatterns = listOf(
            DefPattern("""^\s*def\s+(?:self\.)?([A-Za-z_][A-Za-z0-9_]*[?!=]?)""", SymbolKind.FUNCTION),
            DefPattern("""^\s*(?:class|module)\s+(?:\w+::)*([A-Z]\w*)""", SymbolKind.CLASS),
        ),
    )

    val PHP = Language(
        "PHP", Family.PHP, setOf("php", "phtml"),
        """abstract and array as break callable case catch class clone const continue declare default do echo else elseif
           empty enddeclare endfor endforeach endif endswitch endwhile enum extends final finally fn for foreach function
           global goto if implements include include_once instanceof insteadof interface isset list match namespace new
           or print private protected public readonly require require_once return static switch throw trait try unset use
           var while xor yield true false null self parent""",
        lineComments = listOf("//", "#"), extraIdentChars = "$",
        defPatterns = listOf(
            DefPattern("""\bfunction\s+&?\s*($IDENT)""", SymbolKind.FUNCTION),
            DefPattern("""\b(?:class|interface|trait|enum)\s+($IDENT)""", SymbolKind.CLASS),
        ),
    )

    val DART = Language(
        "Dart", Family.DART, setOf("dart"),
        """abstract as assert async await break case catch class const continue covariant default deferred do dynamic else
           enum export extends extension external factory false final finally for Function get hide if implements import
           in interface is late library mixin new null on operator part required rethrow return set show static super
           switch sync this throw true try typedef var void while with yield sealed base when""",
        tripleQuoted = true, cStyleSignatures = true, allowUntypedMethods = true,
        defPatterns = listOf(
            DefPattern("""\b(?:class|mixin|enum|extension|typedef)\s+($IDENT)""", SymbolKind.CLASS),
            DefPattern("""\b(?:final|const|var|late)\s+(?:[\w<>?,\s]+\s+)?($IDENT)\s*=""", SymbolKind.VARIABLE),
        ),
    )

    val SHELL = Language(
        "Shell", Family.SHELL, setOf("sh", "bash", "zsh", "ksh"),
        """if then else elif fi case esac for while until do done in function select time return exit local export readonly
           declare unset shift source alias true false echo""",
        lineComments = listOf("#"), blockComment = null, extraIdentChars = "",
        defPatterns = listOf(
            DefPattern("""^\s*function\s+($IDENT)""", SymbolKind.FUNCTION),
            DefPattern("""^\s*($IDENT)\s*\(\s*\)""", SymbolKind.FUNCTION),
        ),
    )

    val LUA = Language(
        "Lua", Family.LUA, setOf("lua"),
        """and break do else elseif end false for function goto if in local nil not or repeat return then true until while self""",
        lineComments = listOf("--"), blockComment = "--[[" to "]]",
        defPatterns = listOf(
            DefPattern("""\bfunction\s+(?:[\w.]+[.:])?($IDENT)""", SymbolKind.FUNCTION),
            DefPattern("""(?:^|\s)(?:local\s+)?(?:[\w.]+\.)?($IDENT)\s*=\s*function\b""", SymbolKind.FUNCTION),
        ),
    )

    // ---- display-only languages (highlighted, not indexed) ----
    val JSON = Language("JSON", Family.DATA, setOf("json", "jsonc", "json5", "ipynb", "lock"), "true false null", stringDelims = "\"")
    val YAML = Language("YAML", Family.DATA, setOf("yml", "yaml", "toml", "ini", "cfg", "conf", "properties", "env", "editorconfig"), "true false null yes no on off",
        lineComments = listOf("#"), blockComment = null)
    val XML = Language("XML", Family.DATA, setOf("xml", "html", "htm", "xhtml", "svg", "plist", "xaml", "csproj"), "",
        lineComments = emptyList(), blockComment = "<!--" to "-->", stringDelims = "\"")
    val CSS = Language("CSS", Family.DATA, setOf("css", "scss", "sass", "less"), "important", lineComments = listOf("//"), stringDelims = "\"'")
    val SQL = Language("SQL", Family.DATA, setOf("sql"),
        """select from where insert into values update set delete create table drop alter add index primary key foreign
           references join left right inner outer on and or not null is in as order by group having limit offset union all
           distinct case when then else end begin commit rollback view trigger function procedure returns return declare
           SELECT FROM WHERE INSERT INTO VALUES UPDATE SET DELETE CREATE TABLE DROP ALTER ADD INDEX PRIMARY KEY FOREIGN
           REFERENCES JOIN LEFT RIGHT INNER OUTER ON AND OR NOT NULL IS IN AS ORDER BY GROUP HAVING LIMIT OFFSET UNION ALL
           DISTINCT CASE WHEN THEN ELSE END BEGIN COMMIT ROLLBACK VIEW TRIGGER FUNCTION PROCEDURE RETURNS RETURN DECLARE""",
        lineComments = listOf("--"))
    val MAKE = Language("Makefile", Family.DATA, setOf("mk", "cmake"), "ifeq ifneq ifdef ifndef else endif include define endef export",
        lineComments = listOf("#"), blockComment = null)
    val PLAIN = Language("Text", Family.DATA, emptySet(), "", lineComments = emptyList(), blockComment = null, stringDelims = "")

    val all = listOf(
        KOTLIN, JAVA, SCALA, GROOVY, C, CPP, CSHARP, JAVASCRIPT, TYPESCRIPT, PYTHON, GO, RUST, SWIFT, RUBY, PHP, DART,
        SHELL, LUA, JSON, YAML, XML, CSS, SQL, MAKE,
    )

    private val byExtension: Map<String, Language> = buildMap { all.forEach { l -> l.extensions.forEach { put(it, l) } } }

    private val byFileName = mapOf(
        "makefile" to MAKE, "gnumakefile" to MAKE, "cmakelists.txt" to MAKE, "dockerfile" to SHELL,
        "gemfile" to RUBY, "rakefile" to RUBY, "podfile" to RUBY, "vagrantfile" to RUBY,
        "build" to PYTHON, "workspace" to PYTHON, ".bashrc" to SHELL, ".zshrc" to SHELL, ".profile" to SHELL,
    )

    /** Every regex source used for definitions, for the ICU compatibility test. */
    internal val allPatterns: List<String> get() = all.flatMap { l -> l.defPatterns.map { it.pattern } }

    fun forFileName(fileName: String): Language {
        val lower = fileName.lowercase()
        byFileName[lower]?.let { return it }
        val dot = lower.lastIndexOf('.')
        if (dot < 0) return PLAIN
        return byExtension[lower.substring(dot + 1)] ?: PLAIN
    }
}
