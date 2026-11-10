package com.repolens.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Where the last crash report lives. Plain JVM code (no Android APIs) so it can be unit tested.
 * One report is kept: the most recent crash.
 */
class CrashStore(private val dir: File) {
    private val file get() = File(dir, "last-crash.txt")

    /** What the app was doing, recorded with a crash ("Opening owner/repo", …). */
    @Volatile var breadcrumb: String? = null

    fun format(thread: Thread, error: Throwable, header: Map<String, String>, now: Long = System.currentTimeMillis()): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("RepoLens crash report")
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            pw.println("Time: ${fmt.format(Date(now))}")
            for ((k, v) in header) pw.println("$k: $v")
            pw.println("Thread: ${thread.name}")
            breadcrumb?.let { pw.println("Doing: $it") }
            pw.println()
            error.printStackTrace(pw)
        }
        return sw.toString()
    }

    /** Writes [report] synchronously; must never throw (it runs inside the crash handler). */
    fun write(report: String) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "last-crash.tmp")
            tmp.writeText(report.take(MAX_CHARS))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Throwable) {
        }
    }

    fun pending(): String? = try { file.takeIf { it.isFile }?.readText() } catch (_: Exception) { null }

    fun clear() { try { file.delete() } catch (_: Exception) {} }

    /** The repository the app was opening or showing when it crashed, if the report says so. */
    fun crashedRepo(report: String?): String? =
        report?.lineSequence()?.firstOrNull { it.startsWith("Doing: ") }?.let { Regex("""\b([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)\b""").find(it)?.value }

    /**
     * Records uncaught exceptions to the store, logs them, then hands them to the handler that was
     * there before (so Android still shows its dialog / ends the process as usual).
     */
    fun installHandler(header: Map<String, String>, log: (String) -> Unit = {}) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val report = format(t, e, header)
                write(report)
                log(report)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        private const val MAX_CHARS = 64 * 1024
    }
}

/** Process-wide crash reporting, installed by [RepoLensApp]. */
object CrashReporter {
    @Volatile private var store: CrashStore? = null

    fun store(context: Context): CrashStore =
        store ?: synchronized(this) { store ?: CrashStore(File(context.filesDir, "crash")).also { store = it } }

    fun install(app: Application) {
        val s = store(app)
        val version = try {
            @Suppress("DEPRECATION") app.packageManager.getPackageInfo(app.packageName, 0).let { "${it.versionName} (${it.versionCode})" }
        } catch (e: Exception) { "?" }
        s.installHandler(
            linkedMapOf(
                "App" to "${app.packageName} $version",
                "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "Heap limit" to "${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB",
            ),
        ) { Log.e("RepoLens", it) }
    }

    fun breadcrumb(context: Context, what: String?) { store(context).breadcrumb = what }
}

class RepoLensApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
