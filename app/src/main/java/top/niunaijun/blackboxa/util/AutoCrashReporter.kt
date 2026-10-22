package top.niunaijun.blackboxa.util

import android.content.Context
import android.os.Build
import android.os.Environment
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
 * native crashes, and process deaths) and sends comprehensive diagnostic text reports to the Telegram bot.
 * Highly structured and detailed so AI or developers can immediately identify the root cause and fix it.
 */
object AutoCrashReporter {

    private const val TAG = "AutoCrashReporter"
    private const val PREFS = "auto_crash_reporter"
    private const val KEY_ENABLED = "send_crash_report"
    private const val KEY_LAST_SENT_HASH = "last_sent_hash"
    private const val KEY_LAST_SENT_TIME = "last_sent_time"
    private const val KEY_LAST_LOGCAT_HASH = "last_logcat_hash"

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
     * Checks if there are pending crash reports on startup or when returning to MainActivity.
     */
    fun checkAndSendPending(context: Context = App.getContext()) {
        if (!isEnabled()) return
        if (isSending.get()) return
        Thread {
            try {
                // 1. Check for leftover auto_crash_*.txt files from previous crash terminations
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
                    return@Thread
                }

                // 2. Check recorded crash history
                val recorded = CrashRecorder.read().trim()
                if (recorded.isNotEmpty()) {
                    val hash = recorded.hashCode()
                    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val lastHash = prefs.getInt(KEY_LAST_SENT_HASH, 0)
                    val lastTime = prefs.getLong(KEY_LAST_SENT_TIME, 0L)
                    val now = System.currentTimeMillis()

                    if (hash != lastHash && (now - lastTime > 30_000L)) {
                        val reportContent = CrashReportCollector.collect()
                        val reportFile = File(context.filesDir, "auto_crash_${System.currentTimeMillis()}.txt")
                        reportFile.writeText(reportContent)

                        val pkg = extractPackageFromText(recorded) ?: resolveCurrentPackage(recorded)
                        val error = extractErrorFromText(recorded)
                        val caption = buildCaption("Auto Crash Report (Diagnostic History)", pkg, null, error)

                        val sent = sendReportFile(reportFile, caption)
                        if (sent) {
                            prefs.edit()
                                .putInt(KEY_LAST_SENT_HASH, hash)
                                .putLong(KEY_LAST_SENT_TIME, now)
                                .apply()
                        }
                    }
                }

                // 3. Check system logcat crash buffer for native crashes (tombstones / SIGSEGV)
                checkNativeCrashLogcat(context)
            } catch (t: Throwable) {
                Log.e(TAG, "checkAndSendPending failed", t)
            }
        }.start()
    }

    /**
     * Called when a virtual app process dies unexpectedly (e.g. Scudo abort, SIGSEGV, SIGABRT, OOM).
     */
    fun onProcessDied(processName: String, packageName: String, pid: Int) {
        if (!isEnabled()) return
        Thread {
            try {
                // Short grace period so system / scudo log buffers flush
                Thread.sleep(400)

                val now = System.currentTimeMillis()
                val lastTime = lastReportTimeByPkg[packageName] ?: 0L
                if (now - lastTime < 10_000L) {
                    Log.d(TAG, "Suppressed duplicate crash report for $packageName (reported within 10s)")
                    return@Thread
                }
                lastReportTimeByPkg[packageName] = now

                val logcatLines = fetchCrashLogcat(pid, packageName)
                val fatalLine = findFatalLine(logcatLines, pid, packageName)
                val tombstone = findAppTombstone(packageName)

                val crashSummary = fatalLine ?: "Process died unexpectedly (SIGABRT/Crash/OOM)"
                val caption = buildCaption(
                    title = "Auto Crash: Virtual Process Died",
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
                    append("Event: Virtual Process Died (binderDied)\n")
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
                    "Virtual Process Died: $packageName ($processName, PID $pid)\n" +
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
                if (newest != null && newest.exists()) {
                    newest.readText().take(30_000)
                } else null
            } else null
        } catch (t: Throwable) {
            null
        }
    }

    private fun fetchCrashLogcat(pid: Int, packageName: String): List<String> {
        val lines = mutableListOf<String>()
        val context = App.getContext()

        try {
            val downloadLog = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${context.packageName}_logcat.txt")
            if (downloadLog.exists() && downloadLog.length() > 0) {
                val fileLines = downloadLog.readLines()
                lines.addAll(fileLines.takeLast(500))
            }
        } catch (t: Throwable) {}

        if (lines.size < 50) {
            try {
                val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "400").start()
                val cmdLines = p.inputStream.bufferedReader().use { it.readLines() }
                for (l in cmdLines) {
                    if (!lines.contains(l)) {
                        lines.add(l)
                    }
                }
            } catch (t: Throwable) {}
        }

        return lines
    }

    private fun findFatalLine(lines: List<String>, pid: Int, packageName: String): String? {
        val pidStr = pid.toString()
        for (line in lines.asReversed()) {
            val containsTarget = line.contains(pidStr) || line.contains(packageName)
            if (containsTarget) {
                if (line.contains("scudo", ignoreCase = true) ||
                    line.contains("invalid chunk state", ignoreCase = true) ||
                    line.contains("Fatal signal", ignoreCase = true) ||
                    line.contains("SIGSEGV", ignoreCase = true) ||
                    line.contains("SIGABRT", ignoreCase = true) ||
                    line.contains("SIGBUS", ignoreCase = true) ||
                    line.contains("Abort message", ignoreCase = true) ||
                    line.contains("FATAL EXCEPTION", ignoreCase = true)) {
                    return line.trim()
                }
            }
        }

        for (line in lines.asReversed()) {
            if (line.contains("scudo", ignoreCase = true) && line.contains("ERROR", ignoreCase = true)) {
                return line.trim()
            }
            if (line.contains("Fatal signal", ignoreCase = true)) {
                return line.trim()
            }
            if (line.contains("Abort message:", ignoreCase = true)) {
                return line.trim()
            }
            if (line.contains("SIGSEGV") || line.contains("SIGABRT")) {
                return line.trim()
            }
        }

        return null
    }

    private fun checkNativeCrashLogcat(context: Context) {
        try {
            var output = ""
            try {
                val process = ProcessBuilder("logcat", "-b", "crash", "-d", "-v", "threadtime").start()
                output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            } catch (t: Throwable) {}

            if (output.isBlank()) {
                val downloadLog = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${context.packageName}_logcat.txt")
                if (downloadLog.exists() && downloadLog.length() > 0) {
                    val recent = downloadLog.readLines().takeLast(100)
                    val fatal = recent.firstOrNull { 
                        (it.contains("scudo", true) && it.contains("ERROR", true)) ||
                        it.contains("Fatal signal", true) ||
                        it.contains("SIGSEGV") ||
                        it.contains("SIGABRT")
                    }
                    if (fatal != null) {
                        output = recent.joinToString("\n")
                    }
                }
            }

            if (output.isNotBlank()) {
                val hash = output.hashCode()
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val lastHash = prefs.getInt(KEY_LAST_LOGCAT_HASH, 0)
                if (hash != lastHash) {
                    val reportContent = buildString {
                        append("===== INVERNESS NATIVE CRASH REPORT =====\n")
                        append("detected: ").append(timeFormat.format(Date())).append("\n\n")
                        append(output).append("\n\n")
                        append(CrashReportCollector.collect())
                    }
                    val reportFile = File(context.filesDir, "auto_crash_native_${System.currentTimeMillis()}.txt")
                    reportFile.writeText(reportContent)

                    val pkg = extractPackageFromText(output) ?: resolveCurrentPackage(output)
                    val error = extractErrorFromText(output)
                    val caption = buildCaption("Auto Crash Report (Native Crash)", pkg, null, error)

                    val sent = sendReportFile(reportFile, caption)
                    if (sent) {
                        prefs.edit().putInt(KEY_LAST_LOGCAT_HASH, hash).apply()
                    }
                }
            }
        } catch (t: Throwable) {}
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