package top.niunaijun.blackboxa.util

import android.content.Context
import android.util.Log
import top.niunaijun.blackboxa.app.App
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every crash of the host app and of the apps running inside the container ends up in
 * one file. The crash report screen and the telegram upload read from that file, the
 * logcat buffers are only a fallback because the guest crashes are already captured here
 * with the full stack trace of the guest process.
 */
object CrashRecorder {

    private const val TAG = "CrashRecorder"
    private const val FILE_NAME = "blackbox_crashes.log"
    private const val MAX_SIZE = 512 * 1024

    /**
     * A crash that is written twice in a row is the same crash.
     */
    private const val DUP_WINDOW_MS = 5_000L

    private var lastKey: String? = null
    private var lastTime = 0L

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun crashFile(context: Context = App.getContext()): File {
        return File(context.filesDir, FILE_NAME)
    }

    /**
     * Installs the recorder as the default handler of the process, the crash of the host
     * app is written to the file before the system handler kills the process.
     */
    fun install() {
        try {
            val default = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                record(thread, throwable)
                AutoCrashReporter.onCrash(thread, throwable, "Process Crash", syncWait = true)
                default?.uncaughtException(thread, throwable)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "install failed", t)
        }
    }

    fun record(thread: Thread, throwable: Throwable) {
        try {
            val text = buildString {
                append("---- ").append(timeFormat.format(Date())).append(" ----\n")
                append("thread: ").append(thread.name).append('\n')
                append(Log.getStackTraceString(throwable)).append('\n')
            }
            // the crash of the host app runs through the default handler and through the
            // handler of the core, the same crash must not be written twice
            val key = "${thread.name}:${text.hashCode()}"
            val now = System.currentTimeMillis()
            synchronized(this) {
                if (key == lastKey && now - lastTime < DUP_WINDOW_MS) return
                lastKey = key
                lastTime = now
            }
            append(text)
        } catch (t: Throwable) {
            Log.e(TAG, "record failed", t)
        }
    }

    fun recordRaw(rawText: String) {
        try {
            val text = buildString {
                append("---- ").append(timeFormat.format(Date())).append(" ----\n")
                append(rawText.trim()).append("\n\n")
            }
            append(text)
        } catch (t: Throwable) {
            Log.e(TAG, "recordRaw failed", t)
        }
    }

    private fun append(text: String) {
        val file = crashFile()
        synchronized(this) {
            if (file.exists() && file.length() > MAX_SIZE) {
                // the head is the oldest report, drop it
                val keep = file.readBytes().takeLast(MAX_SIZE / 2).toByteArray()
                file.writeBytes(keep)
            }
            file.appendText(text)
        }
    }

    fun read(): String {
        return try {
            val file = crashFile()
            if (file.exists()) file.readText() else ""
        } catch (t: Throwable) {
            Log.e(TAG, "read failed", t)
            ""
        }
    }

    fun clear() {
        try {
            crashFile().delete()
        } catch (t: Throwable) {
            Log.e(TAG, "clear failed", t)
        }
    }
}
