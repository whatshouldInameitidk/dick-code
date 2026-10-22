package top.niunaijun.blackboxa.util

import android.os.Build
import android.os.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object FontProvisioner {
    private const val FONT_URL = "https://raw.githubusercontent.com/whatshouldInameitidk/InvernessLoader/refs/heads/main/Arial-Unicode-MS.ttf"
    private val targetFile = File("/sdcard/Android/data/com.pubg.imobile/arial-unicode-ms.ttf")

    sealed class Result {
        object Available : Result()
        data class Failed(val message: String) : Result()
    }

    fun requiresAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

    fun ensureBgmiFont(): Result {
        if (targetFile.isFile && targetFile.length() > 0L) return Result.Available
        if (requiresAllFilesAccess()) return Result.Failed("Allow All files access so Inverness can place the required game font.")
        val parent = targetFile.parentFile ?: return Result.Failed("The game font folder is unavailable.")
        if (!parent.exists() && !parent.mkdirs()) return Result.Failed("Unable to create the game font folder.")

        val temporaryFile = File(parent, "${targetFile.name}.part")
        return try {
            val connection = (URL(FONT_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                if (connection.responseCode !in 200..299) {
                    return Result.Failed("Font download failed with HTTP ${connection.responseCode}.")
                }
                connection.inputStream.use { input -> temporaryFile.outputStream().use { output -> input.copyTo(output, 32 * 1024) } }
                if (temporaryFile.length() == 0L) return Result.Failed("The downloaded font file was empty.")
                if (targetFile.exists() && !targetFile.delete()) return Result.Failed("Unable to replace the existing game font.")
                if (!temporaryFile.renameTo(targetFile)) return Result.Failed("Unable to save the downloaded game font.")
                Result.Available
            } finally {
                connection.disconnect()
                if (temporaryFile.exists()) temporaryFile.delete()
            }
        } catch (error: Exception) {
            Result.Failed(error.localizedMessage ?: "Unable to download the game font.")
        }
    }
}
