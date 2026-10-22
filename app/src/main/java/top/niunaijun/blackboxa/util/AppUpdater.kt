package top.niunaijun.blackboxa.util

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import top.niunaijun.blackboxa.R
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles automated checking for updates against the InvernessLoader GitHub repository,
 * displaying the update notice dialog, downloading latest build with progress animation,
 * and seamlessly invoking the Android package installer.
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val REMOTE_VERSION_URL = "https://raw.githubusercontent.com/whatshouldInameitidk/InvernessLoader/main/remote_version.txt"
    private const val REMOTE_APK_URL = "https://raw.githubusercontent.com/whatshouldInameitidk/InvernessLoader/main/InvernessLoader.apk"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isChecking = false

    /**
     * Checks for updates.
     * @param activity Hosting activity
     * @param manual If true, shows a toast if the app is already up-to-date.
     */
    fun checkForUpdate(activity: Activity, manual: Boolean = false) {
        if (isChecking) return
        isChecking = true

        Thread {
            try {
                val remoteVersion = fetchRemoteVersion()
                val currentVersion = try {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "1.0.0"
                } catch (e: Exception) {
                    "1.0.0"
                }

                Log.d(TAG, "Current version: $currentVersion, Remote version: $remoteVersion")

                mainHandler.post {
                    isChecking = false
                    if (remoteVersion != null && isNewerVersion(remoteVersion, currentVersion)) {
                        showUpdateDialog(activity, currentVersion, remoteVersion)
                    } else if (manual) {
                        Toast.makeText(
                            activity,
                            "InvernessLoader is up to date (v$currentVersion)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Check update failed", t)
                mainHandler.post {
                    isChecking = false
                    if (manual) {
                        Toast.makeText(
                            activity,
                            "Unable to check for updates: ${t.localizedMessage}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }.start()
    }

    /**
     * Compares remote and local version strings (e.g. "1.0.1" vs "1.0.0").
     */
    fun isNewerVersion(remote: String, local: String): Boolean {
        try {
            val rParts = remote.trim().split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val lParts = local.trim().split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val maxLen = maxOf(rParts.size, lParts.size)

            for (i in 0 until maxLen) {
                val r = if (i < rParts.size) rParts[i] else 0
                val l = if (i < lParts.size) lParts[i] else 0
                if (r > l) return true
                if (r < l) return false
            }
            return false
        } catch (t: Throwable) {
            return remote.trim() != local.trim()
        }
    }

    private fun fetchRemoteVersion(): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(REMOTE_VERSION_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "InvernessLoader/1.0.0")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText().trim() }
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to fetch remote version: ${t.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun showUpdateDialog(activity: Activity, currentVersion: String, remoteVersion: String) {
        if (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) {
            return
        }

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_update_app)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.90).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val tvVersionInfo = dialog.findViewById<TextView>(R.id.tv_version_info)
        val tvUpdateMsg = dialog.findViewById<TextView>(R.id.tv_update_message)
        val layoutButtons = dialog.findViewById<LinearLayout>(R.id.layout_buttons)
        val layoutProgress = dialog.findViewById<LinearLayout>(R.id.layout_progress)
        val pbDownload = dialog.findViewById<ProgressBar>(R.id.pb_download)
        val tvProgressStatus = dialog.findViewById<TextView>(R.id.tv_progress_status)
        val tvProgressPercent = dialog.findViewById<TextView>(R.id.tv_progress_percent)
        val btnUpdateNow = dialog.findViewById<Button>(R.id.btn_update_now)
        val btnUpdateLater = dialog.findViewById<Button>(R.id.btn_update_later)

        tvVersionInfo.text = "v$currentVersion → v$remoteVersion"
        tvUpdateMsg.text = "A new version of InvernessLoader (v$remoteVersion) is available with the latest fixes. Tap update to download and install automatically."

        btnUpdateLater.setOnClickListener {
            dialog.dismiss()
        }

        btnUpdateNow.setOnClickListener {
            layoutButtons.visibility = View.GONE
            layoutProgress.visibility = View.VISIBLE
            dialog.setCancelable(false)

            downloadAndInstall(activity, remoteVersion, dialog, pbDownload, tvProgressStatus, tvProgressPercent, layoutButtons, layoutProgress)
        }

        dialog.show()
    }

    private fun downloadAndInstall(
        activity: Activity,
        remoteVersion: String,
        dialog: Dialog,
        pb: ProgressBar,
        tvStatus: TextView,
        tvPercent: TextView,
        layoutButtons: LinearLayout,
        layoutProgress: LinearLayout
    ) {
        Thread {
            var input: InputStream? = null
            var output: FileOutputStream? = null
            var connection: HttpURLConnection? = null
            val apkFile = File(activity.cacheDir, "InvernessLoader_v$remoteVersion.apk")

            try {
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                val url = URL(REMOTE_APK_URL)
                connection = openRedirectConnection(url)

                val fileLength = connection.contentLength
                input = connection.inputStream
                output = FileOutputStream(apkFile)

                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int
                var lastProgressUpdate = 0L

                while (input.read(data).also { count = it } != -1) {
                    total += count
                    output.write(data, 0, count)

                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate > 100 || total == fileLength.toLong()) {
                        lastProgressUpdate = now
                        val percent = if (fileLength > 0) ((total * 100) / fileLength).toInt() else 0
                        val downloadedMb = total / (1024f * 1024f)
                        val totalMb = if (fileLength > 0) fileLength / (1024f * 1024f) else 0f

                        mainHandler.post {
                            pb.progress = percent
                            tvPercent.text = "$percent%"
                            if (fileLength > 0) {
                                tvStatus.text = String.format("Downloading: %.1f MB / %.1f MB", downloadedMb, totalMb)
                            } else {
                                tvStatus.text = String.format("Downloading: %.1f MB", downloadedMb)
                            }
                        }
                    }
                }

                output.flush()

                mainHandler.post {
                    pb.progress = 100
                    tvPercent.text = "100%"
                    tvStatus.text = "Download complete! Launching installer..."
                    dialog.dismiss()
                    installApk(activity, apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                mainHandler.post {
                    dialog.setCancelable(true)
                    layoutProgress.visibility = View.GONE
                    layoutButtons.visibility = View.VISIBLE
                    Toast.makeText(activity, "Download failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } finally {
                try {
                    output?.close()
                    input?.close()
                    connection?.disconnect()
                } catch (t: Throwable) {}
            }
        }.start()
    }

    private fun openRedirectConnection(url: URL): HttpURLConnection {
        var currentUrl = url
        var connection: HttpURLConnection
        var redirectCount = 0

        while (redirectCount < 5) {
            connection = currentUrl.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "InvernessLoader/1.0.0")

            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == 307 || status == 308
            ) {
                val newUrl = connection.getHeaderField("Location") ?: break
                connection.disconnect()
                currentUrl = URL(newUrl)
                redirectCount++
            } else {
                return connection
            }
        }
        return currentUrl.openConnection() as HttpURLConnection
    }

    fun installApk(activity: Activity, apkFile: File) {
        try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                Toast.makeText(activity, "Installer file not found or corrupted", Toast.LENGTH_SHORT).show()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(activity, "Please allow InvernessLoader to install updates", Toast.LENGTH_LONG).show()
                    val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(manageIntent)
                    return
                }
            }

            val authority = "${activity.packageName}.update.FileProvider"
            val uri = FileProvider.getUriForFile(activity, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer", e)
            Toast.makeText(activity, "Install error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}
