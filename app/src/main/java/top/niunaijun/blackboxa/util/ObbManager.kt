package top.niunaijun.blackboxa.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.DecimalFormat

object ObbManager {
    private const val TAG = "ObbManager"
    private const val MIN_OBB_SIZE = 5 * 1024 * 1024L // 5 MB
    private const val REQUIRED_BGMI_OBB_VERSION = 21535

    fun getVirtualObbDir(packageName: String, userId: Int): File {
        val dir = BEnvironment.getExternalObbDir(packageName, userId)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getInstalledObbFiles(packageName: String, userId: Int): List<File> {
        val dir = getVirtualObbDir(packageName, userId)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.name.endsWith(".obb", ignoreCase = true) && it.length() > MIN_OBB_SIZE }
    }

    fun isObbInstalled(packageName: String, userId: Int): Boolean {
        return getInstalledObbFiles(packageName, userId).isNotEmpty()
    }

    fun getPrimaryObbFile(packageName: String, userId: Int): File? {
        val files = getInstalledObbFiles(packageName, userId)
        if (files.isEmpty()) return null
        return files.firstOrNull { it.name.startsWith("main.", ignoreCase = true) } ?: files.maxByOrNull { it.length() }
    }

    fun findHostObbFiles(context: Context, packageName: String): List<File> {
        val candidates = mutableListOf<File>()
        val searchDirs = mutableListOf<File>()

        val versionCodes = setOf(REQUIRED_BGMI_OBB_VERSION)
        android.util.Log.d(TAG, "findHostObbFiles for $packageName, versionCodes=$versionCodes")

        // 2. Direct OBB locations
        val obbDirs = mutableListOf<File>()
        obbDirs.add(File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName"))
        obbDirs.add(File("/storage/emulated/0/Android/obb/$packageName"))
        obbDirs.add(File("/sdcard/Android/obb/$packageName"))
        obbDirs.add(File("/storage/emulated/legacy/Android/obb/$packageName"))
        obbDirs.add(File("/mnt/sdcard/Android/obb/$packageName"))

        try {
            val extDirs = context.getExternalFilesDirs(null)
            if (extDirs != null) {
                for (ed in extDirs) {
                    if (ed != null) {
                        val root = ed.parentFile?.parentFile?.parentFile?.parentFile
                        if (root != null) {
                            obbDirs.add(File(root, "Android/obb/$packageName"))
                        }
                    }
                }
            }
        } catch (t: Throwable) {}

        try {
            val appObbDirs = context.obbDirs
            if (appObbDirs != null) {
                for (d in appObbDirs) {
                    if (d != null) {
                        obbDirs.add(File(d.parentFile, packageName))
                    }
                }
            }
        } catch (t: Throwable) {}

        // 3. Direct probe on obbDirs (bypasses opendir restrictions on Android 11+)
        for (dir in obbDirs.distinct()) {
            android.util.Log.d(TAG, "Probing dir: ${dir.absolutePath}, exists=${dir.exists()}, canRead=${dir.canRead()}")
            for (vc in versionCodes) {
                val testFiles = listOf(
                    File(dir, "main.$vc.$packageName.obb"),
                    File(dir, "patch.$vc.$packageName.obb")
                )
                for (f in testFiles) {
                    if (isDirectFileAccessible(f)) {
                        android.util.Log.d(TAG, "Direct file probe FOUND OBB: ${f.absolutePath} (${f.length()} bytes)")
                        candidates.add(f)
                    }
                }
            }
        }

        // 4. Also scan directories if listFiles() works
        searchDirs.addAll(obbDirs)
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir != null) {
            searchDirs.add(downloadDir)
            try {
                downloadDir.listFiles()?.filter { it.isDirectory }?.forEach(searchDirs::add)
            } catch (t: Throwable) {}
            searchDirs.add(File(downloadDir, packageName))
            searchDirs.add(File(downloadDir, "obb"))
            searchDirs.add(File(downloadDir, "OBB"))
        }
        searchDirs.add(File("/storage/emulated/0/Download"))
        searchDirs.add(File("/storage/emulated/0/Download/$packageName"))
        searchDirs.add(File("/storage/emulated/0/Downloads"))
        searchDirs.add(File("/storage/emulated/0/Downloads/$packageName"))
        searchDirs.add(File("/storage/emulated/0/Documents"))
        searchDirs.add(File("/storage/emulated/0/Documents/$packageName"))
        searchDirs.add(File("/storage/emulated/0/OBB"))
        searchDirs.add(File("/storage/emulated/0/obb"))
        searchDirs.add(File("/storage/emulated/0/Telegram/Telegram Files"))

        for (dir in searchDirs.distinct()) {
            try {
                if (dir.exists() && dir.isDirectory) {
                    val files = dir.listFiles()
                    if (files != null) {
                        for (file in files) {
                            if (file.isFile && isValidObbFile(file, packageName)) {
                                android.util.Log.d(TAG, "Directory scan FOUND OBB: ${file.absolutePath}")
                                candidates.add(file)
                            } else if (file.isDirectory && (file.name.equals(packageName, true) || file.name.equals("obb", true) || file.name.equals("OBB", true))) {
                                val subFiles = file.listFiles()
                                if (subFiles != null) {
                                    for (sf in subFiles) {
                                        if (sf.isFile && isValidObbFile(sf, packageName)) {
                                            android.util.Log.d(TAG, "Directory subscan FOUND OBB: ${sf.absolutePath}")
                                            candidates.add(sf)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {}
        }

        val result = candidates.distinctBy { it.absolutePath }
            .sortedWith(compareByDescending<File> { it.name.startsWith("main.", ignoreCase = true) }
                .thenByDescending { it.length() })
        android.util.Log.d(TAG, "findHostObbFiles total candidates: ${result.size}")
        return result
    }

    fun findProtectedHostObbFile(context: Context, packageName: String): File? {
        val versionCodes = setOf(REQUIRED_BGMI_OBB_VERSION)

        val directories = listOf(
            File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName"),
            File("/storage/emulated/0/Android/obb/$packageName"),
            File("/sdcard/Android/obb/$packageName")
        )
        return directories.asSequence()
            .flatMap { directory -> versionCodes.asSequence().flatMap { version -> sequenceOf(File(directory, "main.$version.$packageName.obb"), File(directory, "patch.$version.$packageName.obb")) } }
            .firstOrNull { it.exists() && it.length() > MIN_OBB_SIZE }
    }

    private fun isDirectFileAccessible(file: File): Boolean {
        try {
            if (!file.exists()) return false
            if (file.length() <= MIN_OBB_SIZE) return false
            FileInputStream(file).use { input ->
                val buf = ByteArray(16)
                if (input.read(buf) > 0) {
                    return true
                }
            }
        } catch (t: Throwable) {
            // Unreadable
        }
        return false
    }

    private fun isValidObbFile(file: File, packageName: String): Boolean {
        if (!file.name.endsWith(".obb", ignoreCase = true)) return false
        if (file.length() <= MIN_OBB_SIZE) return false
        return file.name.equals("main.$REQUIRED_BGMI_OBB_VERSION.$packageName.obb", ignoreCase = true)
    }

    fun copyFileWithProgress(
        source: File,
        targetFile: File,
        onProgress: ((percent: Int, copied: Long, total: Long) -> Unit)? = null
    ): Boolean {
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, targetFile.name + ".tmp")
        val totalBytes = source.length()
        var copiedBytes = 0L

        try {
            FileInputStream(source).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var bytesRead: Int
                    var lastPercent = -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        copiedBytes += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((copiedBytes * 100) / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress?.invoke(percent, copiedBytes, totalBytes)
                            }
                        }
                    }
                    output.flush()
                }
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            targetFile.setReadable(true, false)
            targetFile.setWritable(true, false)
            return true
        } catch (t: Throwable) {
            tempFile.delete()
            t.printStackTrace()
            return false
        }
    }

    fun copyUriWithProgress(
        context: Context,
        uri: Uri,
        targetFile: File,
        onProgress: ((percent: Int, copied: Long, total: Long) -> Unit)? = null
    ): Boolean {
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, targetFile.name + ".tmp")
        var totalBytes = getUriFileSize(context, uri)
        var copiedBytes = 0L

        try {
            val input: InputStream? = context.contentResolver.openInputStream(uri)
            if (input == null) return false

            input.use { inStream ->
                FileOutputStream(tempFile).use { outStream ->
                    val buffer = ByteArray(128 * 1024)
                    var bytesRead: Int
                    var lastPercent = -1

                    while (inStream.read(buffer).also { bytesRead = it } != -1) {
                        outStream.write(buffer, 0, bytesRead)
                        copiedBytes += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((copiedBytes * 100) / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress?.invoke(percent, copiedBytes, totalBytes)
                            }
                        }
                    }
                    outStream.flush()
                }
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            targetFile.setReadable(true, false)
            targetFile.setWritable(true, false)
            return true
        } catch (t: Throwable) {
            tempFile.delete()
            t.printStackTrace()
            return false
        }
    }

    fun getUriFileName(context: Context, uri: Uri, fallbackPackageName: String): String {
        var fileName: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        } catch (t: Throwable) {}

        if (fileName.isNullOrEmpty()) {
            fileName = uri.lastPathSegment
        }

        if (fileName != null && fileName!!.endsWith(".obb", ignoreCase = true)) {
            return fileName!!
        }

        val versionCode = try {
            val pInfo = BlackBoxCore.getBPackageManager().getPackageInfo(fallbackPackageName, 0, 0)
            pInfo?.versionCode ?: 21535
        } catch (t: Throwable) {
            21535
        }
        return "main.$versionCode.$fallbackPackageName.obb"
    }

    private fun getUriFileSize(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    return cursor.getLong(sizeIndex)
                }
            }
        } catch (t: Throwable) {}
        return -1L
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val df = DecimalFormat("#,##0.#")
        return df.format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }
}
