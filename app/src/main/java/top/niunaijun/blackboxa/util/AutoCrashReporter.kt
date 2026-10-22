package top.niunaijun.blackboxa.util

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.BActivityThread
import top.niunaijun.blackboxa.app.App
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Automatically captures crashes in real-time (host crashes, virtual app crashes, container errors,
 * and native crashes) and sends comprehensive diagnostic text reports to the Telegram bot.
 * Highly structured and detailed so AI or developers can immediately identify the root cause and fix it.
 *
 * Normal app closures (clean exit, back press, recent swipe) are strictly ignored and never reported.
 */
object AutoCrashReporter {

    private const val TAG = "AutoCrashReporter"
    private const val PREFS = "auto_crash_reporter"
    private const val KEY_ENABLED = "send_crash_report"

    private val isSending = AtomicBoolean(false)
    private val lastReportTimeByPkg = ConcurrentHashMap<String, Long>()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Checks if automatic crash reporting is enabled. Enabled by default.
     */
    fun isEnabled(): Boolean {
        val prefs = App.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, true)
    }

    /**
     * Enables or disables automatic crash reporting.
     */
    fun setEnabled(enabled: Boolean) {
        val prefs = App.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Builds a rich, human-readable Telegram caption containing device model,
     * Android version, crashing app label/package, thread, timestamp, and error snippet.
     */
    fun buildCaption(
        title: String,
        packageName: String? = null,
        threadName: String? = null,
        errorSummary: String? = null
    ): String {
        val targetPkg = if (!packageName.isNullOrBlank()) packageName else resolveCurrentPackage()
        val appLabel = getAppLabel(targetPkg)
        val appDisplay = if (appLabel != targetPkg && appLabel.isNotBlank()) {
            "$appLabel ($targetPkg)"
        } else {
            targetPkg
        }

        return buildString {
            append("🚨 ").append(title).append('\n')
            append("📱 Device: ")
                .append(Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() })
                .append(' ').append(Build.MODEL).append('\n')
            append("🤖 Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("📦 App: ").append(appDisplay).append('\n')
            if (!threadName.isNullOrBlank()) {
                append("🧵 Thread: ").append(threadName).append('\n')
            }
            append("⏱ Time: ").append(timeFormat.format(Date())).append('\n')
            if (!errorSummary.isNullOrBlank()) {
                append("💥 Error: ").append(errorSummary.trim().take(150))
            }
        }.trim()
    }

    /**
     * Called immediately when an uncaught exception is thrown (host, virtual app, or background thread).
     */
    fun onCrash(thread: Thread, throwable: Throwable, source: String, syncWait: Boolean = true) {
        if (!isEnabled()) return
        try {
            val crashTime = timeFormat.format(Date())
            val stackTrace = Log.getStackTraceString(throwable)
            val currentPkg = resolveCurrentPackage()
            val firstLine = stackTrace.lines().firstOrNull { 
                it.contains("Exception") || it.contains("Error") 
            } ?: (throwable.javaClass.simpleName + ": " + (throwable.message ?: ""))

            val caption = buildCaption(
                title = "Auto Crash: $source",
                packageName = currentPkg,
                threadName = thread.name,
                errorSummary = firstLine
            )

            val context = App.getContext()
            val reportFile = File(context.filesDir, "auto_crash_${System.currentTimeMillis()}.txt")
            val reportContent = buildString {
                append("================================================================================\n")
                append("                    INVERNESS AUTOMATIC CRASH REPORT\n")
                append("================================================================================\n")
                append("[PROBLEM CLASSIFICATION & ROOT CAUSE]\n")
                append("Event: ").append(source).append('\n')
                append("Package: ").append(currentPkg).append('\n')
                append("App Name: ").append(getAppLabel(currentPkg)).append('\n')
                append("Thread: ").append(thread.name).append('\n')
                append("PID: ").append(Process.myPid()).append('\n')
                append("Timestamp: ").append(crashTime).append('\n')
                append("Root Exception: ").append(firstLine).append("\n\n")

                append("[ENVIRONMENT & DEVICE METADATA]\n")
                append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append("Supported ABIs: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
                append("Board / Hardware: ").append(Build.BOARD).append(" / ").append(Build.HARDWARE).append("\n\n")

                append("[FULL CRASH STACK TRACE]\n")
                append(stackTrace).append("\n\n")

                append(CrashReportCollector.collect())
            }

            reportFile.writeText(reportContent)
            Log.w(TAG, "Recorded auto crash to ${reportFile.name}, sending to Telegram bot...")

            if (syncWait) {
                val uploadThread = Thread {
                    sendReportFile(reportFile, caption)
                }
                uploadThread.start()
                uploadThread.join(4000)
            } else {
                Thread {
                    sendReportFile(reportFile, caption)
                }.start()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onCrash failed", t)
        }
    }

    /**
     * Checks if there are leftover auto_crash_*.txt files from previous crashes that failed to upload.
     */
    fun checkAndSendPending(context: Context = App.getContext()) {
        if (!isEnabled()) return
        if (isSending.get()) return
        Thread {
            try {
                val files = context.filesDir.listFiles { f ->
                    f.name.startsWith("auto_crash_") && f.name.endsWith(".txt")
                }
                if (!files.isNullOrEmpty()) {
                    files.sortedBy { it.lastModified() }.forEach { file ->
                        val content = file.readText()
                        val pkg = extractPackageFromText(content) ?: resolveCurrentPackage()
                        val error = extractErrorFromText(content)
                        val caption = buildCaption("Auto Crash Report (Pending)", pkg, null, error)
                        sendReportFile(file, caption)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "checkAndSendPending failed", t)
            }
        }.start()
    }

    /**
     * Called when a virtual app process dies.
     * Strictly verifies whether an actual crash occurred (native tombstone or fatal signal/scudo error).
     * If the process exited normally (clean exit, back press, user close), NO report is sent.
     */
    fun onProcessDied(processName: String, packageName: String, pid: Int) {
        if (!isEnabled()) return
        Thread {
            try {
                // Short wait to allow crash buffers/tombstones to flush if it was an actual crash
                Thread.sleep(600)

                // 1. Check if a native tombstone was freshly generated for this package (within last 20 seconds)
                val tombstone = findAppTombstone(packageName)

                // 2. Search live logcat specifically for fatal signals or memory corruption for this PID or process
                val logcatLines = fetchCrashLogcat()
                val fatalLine = findFatalLine(logcatLines, pid, packageName, processName)

                // CRITICAL FILTER: If neither a fresh tombstone nor a fatal signal/error is found,
                // this is a CLEAN / NORMAL EXIT (e.g. user closed the game or exited).
                // Do NOT send any report!
                if (fatalLine == null && tombstone.isNullOrBlank()) {
                    Log.d(TAG, "Process $processName (PID $pid, $packageName) exited normally. Skipping crash report.")
                    return@Thread
                }

                val now = System.currentTimeMillis()
                val lastTime = lastReportTimeByPkg[packageName] ?: 0L
                if (now - lastTime < 10_000L) {
                    Log.d(TAG, "Suppressed duplicate crash report for $packageName (reported within 10s)")
                    return@Thread
                }
                lastReportTimeByPkg[packageName] = now

                val crashSummary = fatalLine ?: "Native crash detected (Tombstone generated)"
                val caption = buildCaption(
                    title = "Auto Crash: Virtual Process Crashed",
                    packageName = packageName,
                    threadName = "PID $pid ($processName)",
                    errorSummary = crashSummary
                )

                val context = App.getContext()
                val reportFile = File(context.filesDir, "auto_crash_process_${System.currentTimeMillis()}.txt")
                val reportContent = buildString {
                    append("================================================================================\n")
                    append("                    INVERNESS VIRTUAL CRASH REPORT\n")
                    append("================================================================================\n")
                    append("[PROBLEM CLASSIFICATION & ROOT CAUSE]\n")
                    append("Event: Virtual Process Crashed (SIGSEGV/SIGABRT/Crash)\n")
                    append("Package: ").append(packageName).append('\n')
                    append("Process: ").append(processName).append('\n')
                    append("App Name: ").append(getAppLabel(packageName)).append('\n')
                    append("PID: ").append(pid).append('\n')
                    append("Timestamp: ").append(timeFormat.format(Date())).append('\n')
                    append("Cause: ").append(crashSummary).append("\n\n")

                    append("[ENVIRONMENT & DEVICE METADATA]\n")
                    append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    append("Supported ABIs: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
                    append("Hardware: ").append(Build.HARDWARE).append("\n\n")

                    if (!tombstone.isNullOrBlank()) {
                        append("================================================================================\n")
                        append("                    NATIVE CRASHKIT / TOMBSTONE DUMP\n")
                        append("================================================================================\n")
                        append(tombstone).append("\n\n")
                    }

                    append("================================================================================\n")
                    append("                    MATCHED ERROR & LOGCAT CONTEXT\n")
                    append("================================================================================\n")
                    if (fatalLine != null) {
                        append("Fatal Error Line:\n").append(fatalLine).append("\n\n")
                    }
                    append("Recent Process & System Logcat:\n")
                    logcatLines.takeLast(250).forEach { append(it).append('\n') }
                }

                reportFile.writeText(reportContent)

                CrashRecorder.recordRaw(
                    "Virtual Process Crashed: $packageName ($processName, PID $pid)\n" +
                    "Cause: $crashSummary\n" +
                    (fatalLine ?: "")
                )

                Log.w(TAG, "Sending virtual process crash report for $packageName (PID $pid) to Telegram...")
                sendReportFile(reportFile, caption)
            } catch (t: Throwable) {
                Log.e(TAG, "onProcessDied failed", t)
            }
        }.start()
    }

    /**
     * Sends a test crash report to verify that the bot is properly receiving detailed logs.
     */
    fun sendTestReport(context: Context, callback: ((Boolean, String) -> Unit)? = null) {
        Thread {
            try {
                val currentPkg = resolveCurrentPackage()
                val caption = buildCaption(
                    title = "Test Crash Report",
                    packageName = currentPkg,
                    threadName = Thread.currentThread().name,
                    errorSummary = "Test report triggered to verify Telegram bot connectivity"
                )
                val reportFile = File(context.filesDir, "auto_crash_test_${System.currentTimeMillis()}.txt")
                val reportContent = buildString {
                    append("================================================================================\n")
                    append("               INVERNESS AUTOMATIC CRASH REPORT (TEST VERIFICATION)\n")
                    append("================================================================================\n")
                    append("[SYSTEM STATUS]\n")
                    append("Status: Telegram Crash Reporting is ACTIVE and verified.\n")
                    append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    append("Supported ABIs: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
                    append("Package: ").append(currentPkg).append('\n')
                    append("App Name: ").append(getAppLabel(currentPkg)).append('\n')
                    append("Host PID: ").append(Process.myPid()).append('\n')
                    append("Timestamp: ").append(timeFormat.format(Date())).append("\n\n")
                    append("================================================================================\n")
                    append("                            DIAGNOSTIC REPORT\n")
                    append("================================================================================\n\n")
                    append(CrashReportCollector.collect())
                }
                reportFile.writeText(reportContent)
                val token = TelegramReporter.token()
                val result = TelegramReporter.sendToOwner(token, reportFile, caption)
                reportFile.delete()
                callback?.invoke(result.success, result.message)
            } catch (t: Throwable) {
                callback?.invoke(false, t.message ?: "Unknown error")
            }
        }.start()
    }

    private fun findAppTombstone(packageName: String): String? {
        return try {
            val context = App.getContext()
            val crashKitDir = File(context.filesDir.parentFile, "blackbox/data/user/0/$packageName/app_crashKit")
            if (crashKitDir.exists() && crashKitDir.isDirectory) {
                val tombs = crashKitDir.listFiles { f -> f.isFile && f.name.startsWith("tomb_") && f.name.endsWith(".txt") }
                val newest = tombs?.maxByOrNull { it.lastModified() }
                // Only consider it if it was created in the last 20 seconds
                if (newest != null && newest.exists() && (System.currentTimeMillis() - newest.lastModified() < 20_000L)) {
                    newest.readText().take(30_000)
                } else null
            } else null
        } catch (t: Throwable) {
            null
        }
    }

    private fun fetchCrashLogcat(): List<String> {
        val lines = mutableListOf<String>()
        try {
            val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "500").start()
            val cmdLines = p.inputStream.bufferedReader().use { it.readLines() }
            lines.addAll(cmdLines)
        } catch (t: Throwable) {
            Log.e(TAG, "fetchCrashLogcat failed", t)
        }
        return lines
    }

    private fun findFatalLine(lines: List<String>, pid: Int, packageName: String, processName: String): String? {
        val pidStr = pid.toString()
        for (line in lines.asReversed()) {
            val containsTarget = line.contains(pidStr) || line.contains(packageName) || line.contains(processName)
            if (containsTarget) {
                if (line.contains("Fatal signal", ignoreCase = true) ||
                    line.contains("SIGSEGV", ignoreCase = true) ||
                    line.contains("SIGABRT", ignoreCase = true) ||
                    line.contains("SIGBUS", ignoreCase = true) ||
                    line.contains("SIGFPE", ignoreCase = true) ||
                    line.contains("SIGILL", ignoreCase = true) ||
                    line.contains("scudo ERROR", ignoreCase = true) ||
                    line.contains("corrupted chunk header", ignoreCase = true) ||
                    line.contains("invalid chunk state", ignoreCase = true) ||
                    line.contains("Abort message:", ignoreCase = true) ||
                    line.contains("FATAL EXCEPTION", ignoreCase = true)) {
                    return line.trim()
                }
            }
        }
        return null
    }

    private fun sendReportFile(file: File, caption: String): Boolean {
        if (!file.exists()) return false
        isSending.set(true)
        return try {
            val token = TelegramReporter.token()
            val result = TelegramReporter.sendToOwner(token, file, caption)
            Log.d(TAG, "sendReportFile ${file.name}: success=${result.success}, msg=${result.message}")
            if (result.success) {
                file.delete()
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sendReportFile failed", t)
            false
        } finally {
            isSending.set(false)
        }
    }

    private fun getAppLabel(pkg: String): String {
        if (pkg.isBlank()) return "Unknown"
        return try {
            val pm = App.getContext().packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (t: Throwable) {
            try {
                val bInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(pkg, 0, BActivityThread.getUserId())
                bInfo?.loadLabel(App.getContext().packageManager)?.toString() ?: pkg
            } catch (t2: Throwable) {
                pkg
            }
        }
    }

    private fun resolveCurrentPackage(fallbackText: String? = null): String {
        if (!fallbackText.isNullOrBlank()) {
            val extracted = extractPackageFromText(fallbackText)
            if (!extracted.isNullOrBlank()) return extracted
        }
        return try {
            BActivityThread.getAppPackageName() ?: App.getContext().packageName
        } catch (t: Throwable) {
            App.getContext().packageName
        }
    }

    private fun extractPackageFromText(text: String): String? {
        val regex = Regex("""(?:Process:\s*|>>>\s*|package:\s*|pkg\s+)([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)""")
        return regex.find(text)?.groupValues?.get(1)
    }

    private fun extractErrorFromText(text: String): String? {
        val lines = text.lines()
        return lines.firstOrNull { line ->
            val trim = line.trim()
            trim.contains("Exception") || trim.contains("Error") ||
            trim.contains("signal ") || trim.contains("Fatal") ||
            trim.contains("FATAL")
        }?.trim() ?: lines.firstOrNull { it.isNotBlank() }?.trim()
    }
}
