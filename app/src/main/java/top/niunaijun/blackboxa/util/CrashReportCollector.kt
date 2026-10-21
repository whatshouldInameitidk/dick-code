package top.niunaijun.blackboxa.util

import android.os.Build
import android.os.Process
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import java.util.Date

/**
 * Builds the crash report. It holds the state of the container, the crashes that were
 * recorded by the CrashRecorder and the logcat buffers of the app.
 */
object CrashReportCollector {

    private const val TAG = "CrashReportCollector"
    private const val MAX_LOG_CHARS = 200_000
    private const val EXEC_TIMEOUT_MS = 15_000L

    fun collect(): String {
        val sb = StringBuilder()
        sb.append(header())
        sb.append("\n\n===== recorded crashes =====\n")
        sb.append(CrashRecorder.read().ifBlank { "(nothing recorded)\n" })
        sb.append("\n\n===== logcat crash buffer =====\n")
        sb.append(exec("logcat", "-b", "crash", "-d", "-v", "threadtime"))
        sb.append("\n\n===== logcat main buffer (tail) =====\n")
        sb.append(tail(exec("logcat", "-d", "-v", "threadtime", "-t", "4000")))
        return sb.toString()
    }

    private fun header(): String {
        val sb = StringBuilder()
        sb.append("===== BlackBox crash report =====\n")
        sb.append("generated: ").append(Date()).append('\n')
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("android: ").append(Build.VERSION.RELEASE)
            .append(" (api ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("abi: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        sb.append("host pid: ").append(Process.myPid()).append('\n')
        sb.append("host process: ").append(currentProcessName()).append('\n')
        sb.append("app version: ").append(appVersion()).append('\n')
        sb.append("hidden api exempt: ").append(BlackBoxCore.get().isHiddenApiExempt).append('\n')

        sb.append("\n===== container users =====\n")
        try {
            val users = BlackBoxCore.get().users
            if (users.isNullOrEmpty()) {
                sb.append("(no user)\n")
            } else {
                users.forEach { sb.append("user ").append(it.id).append('\n') }
            }
        } catch (t: Throwable) {
            sb.append("failed: ").append(t).append('\n')
        }

        sb.append("\n===== installed inside the container =====\n")
        try {
            val users = BlackBoxCore.get().users
            val userIds = if (users.isNullOrEmpty()) listOf(0) else users.map { it.id }
            userIds.forEach { userId ->
                val apps = BlackBoxCore.get().getInstalledApplications(0, userId)
                if (apps.isNullOrEmpty()) {
                    sb.append("user ").append(userId).append(": (nothing)\n")
                } else {
                    sb.append("user ").append(userId).append(":\n")
                    apps.forEach {
                        sb.append("  ").append(it.packageName)
                            .append("  system=").append(it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
                            .append("  ").append(it.sourceDir)
                            .append('\n')
                    }
                }
            }
        } catch (t: Throwable) {
            sb.append("failed: ").append(t).append('\n')
        }
        return sb.toString()
    }

    private fun currentProcessName(): String {
        return try {
            val pid = Process.myPid()
            val file = java.io.File("/proc/$pid/cmdline")
            if (file.exists()) file.readText().trim { it <= ' ' } else "?"
        } catch (t: Throwable) {
            "?"
        }
    }

    private fun appVersion(): String {
        return try {
            val context = top.niunaijun.blackboxa.app.App.getContext()
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCode})"
        } catch (t: Throwable) {
            "?"
        }
    }

    private fun tail(text: String): String {
        if (text.length <= MAX_LOG_CHARS) return text
        return "... (${text.length - MAX_LOG_CHARS} chars dropped) ...\n" + text.takeLast(MAX_LOG_CHARS)
    }

    /**
     * Runs a command and returns its output. The app only sees its own logs, that is
     * exactly what is needed, the guest runs inside this process.
     */
    private fun exec(vararg command: String): String {
        return try {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            val thread = Thread {
                try {
                    reader.forEachLine { sb.append(it).append('\n') }
                } catch (t: Throwable) {
                    // the process died while reading
                }
            }
            thread.isDaemon = true
            thread.start()
            thread.join(EXEC_TIMEOUT_MS)
            if (process.isAlive) process.destroy()
            sb.toString()
        } catch (t: Throwable) {
            Log.e(TAG, "exec ${command.joinToString(" ")} failed", t)
            "failed to run ${command.joinToString(" ")}: $t\n"
        }
    }
}
