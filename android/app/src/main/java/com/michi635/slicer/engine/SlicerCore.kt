package com.michi635.slicer.engine

import android.content.Context
import com.michi635.slicer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Runs the slicing engine (OrcaSlicer's libslic3r in the program `slicercore`) as a separate process.
 * A crash or an out-of-memory kill of the engine never takes the app down; the app only sees an error.
 *
 * The engine ships as `lib/arm64-v8a/libslicercore.so` (an executable named like a library) and the profiles as the
 * asset `orca.zip`, unpacked to files/orca on the first start of each app version.
 */
class SlicerCore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "orca")
    private val resources = File(root, "resources")
    private val data = File(root, "data")
    private val work = File(app.filesDir, "work").apply { mkdirs() }
    val logFile = File(File(app.filesDir, "logs").apply { mkdirs() }, "slicercore.log")
    private val binary = File(app.applicationInfo.nativeLibraryDir, "libslicercore.so")

    @Volatile private var running: Process? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        running?.destroy()
    }

    @Synchronized
    private fun ensureUnpacked() {
        val marker = File(root, ".version")
        val version = "${BuildConfig.VERSION_CODE}"
        if (marker.isFile && marker.readText() == version && File(data, "system").isDirectory) return
        root.deleteRecursively()
        root.mkdirs()
        app.assets.open("orca.zip").use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                val base = root.canonicalPath + File.separator
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(root, entry.name)
                    if (!out.canonicalPath.startsWith(base)) continue
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        marker.writeText(version)
    }

    suspend fun version(): JSONObject = run(listOf("version"))

    suspend fun describe(printer: String? = null, process: String? = null): JSONObject {
        val args = mutableListOf("describe", resources.path, data.path)
        if (!printer.isNullOrEmpty()) {
            args += printer
            if (!process.isNullOrEmpty()) args += process
        }
        return run(args)
    }

    suspend fun arrange(job: JSONObject): JSONObject {
        val jobFile = File(work, "arrange.json").apply { writeText(job.toString()) }
        return run(listOf("arrange", resources.path, data.path, jobFile.path))
    }

    suspend fun slice(job: JSONObject, output: File, onProgress: (Int, String) -> Unit): JSONObject {
        val jobFile = File(work, "slice.json").apply { writeText(job.toString()) }
        output.parentFile?.mkdirs()
        return run(listOf("slice", resources.path, data.path, jobFile.path, output.path), onProgress)
    }

    private suspend fun run(args: List<String>, onProgress: (Int, String) -> Unit = { _, _ -> }): JSONObject =
        withContext(Dispatchers.IO) {
            cancelled = false
            try {
                ensureUnpacked()
                val builder = ProcessBuilder(listOf(binary.path) + args)
                    .directory(work)
                    .redirectError(logFile)
                builder.environment()["LD_LIBRARY_PATH"] = binary.parent ?: ""
                builder.environment()["TMPDIR"] = app.cacheDir.path
                builder.environment()["HOME"] = work.path
                val process = builder.start()
                running = process
                var result: JSONObject? = null
                process.inputStream.bufferedReader().forEachLine { line ->
                    when {
                        line.startsWith("PROGRESS ") -> {
                            val rest = line.substring(9)
                            val space = rest.indexOf(' ')
                            val percent = (if (space > 0) rest.substring(0, space) else rest).toIntOrNull() ?: 0
                            onProgress(percent, if (space > 0) rest.substring(space + 1) else "")
                        }
                        line.startsWith("RESULT ") -> result = runCatching { JSONObject(line.substring(7)) }.getOrNull()
                    }
                }
                val code = process.waitFor()
                running = null
                result ?: failure(
                    when {
                        cancelled -> "Abgebrochen."
                        code == 137 || code == 9 -> "Die Engine wurde vom System beendet – vermutlich zu wenig Arbeitsspeicher."
                        else -> "Die Engine ist abgestürzt (Code $code).\n${logTail()}"
                    }
                )
            } catch (e: Exception) {
                running = null
                failure(if (cancelled) "Abgebrochen." else "Engine nicht startbar: ${e.message}")
            }
        }

    private fun failure(message: String) = JSONObject().put("ok", false).put("error", message)

    private fun logTail(): String =
        runCatching { logFile.readLines().takeLast(8).joinToString("\n") }.getOrDefault("")
}
