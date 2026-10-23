package top.niunaijun.blackboxa.util

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import java.io.File
import java.util.UUID
import top.niunaijun.blackbox.core.env.BEnvironment

private fun generateUuid(): String =
    UUID.randomUUID().toString()

private fun generateHexId(): String =
    UUID.randomUUID().toString().replace("-", "")

private fun recreateDeviceXml(file: File) {
    file.delete()
    file.parentFile?.mkdirs()

    file.writeText(
        """
        <?xml version="1.0" encoding="utf-8" standalone="yes" ?>
        <map>
            <string name="random"></string>
            <string name="install">${generateUuid()}</string>
            <string name="uuid">${generateHexId()}</string>
        </map>
        """.trimIndent()
    )
}

object GuestResetter {

    fun stopGuestProcess(context: Context) {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        activityManager.runningAppProcesses
            ?.filter { it.uid == Process.myUid() && it.processName.endsWith(":black") }
            ?.forEach { Process.killProcess(it.pid) }
    }

    fun reset(packageName: String, userId: Int): Boolean {
        val internalRoot = BEnvironment.getDataDir(packageName, userId)
        val deviceProtectedRoot = BEnvironment.getDeDataDir(packageName, userId)
        val externalRoot = BEnvironment.getExternalDataDir(packageName, userId)

        val savedRoot = File(
            externalRoot,
            "files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved"
        )

        val results = listOf(
            deleteIfPresent(internalRoot),
            deleteIfPresent(deviceProtectedRoot),
            deleteIfPresent(File(externalRoot, "cache")),
            deleteIfPresent(File(externalRoot, "files/cacheFile.txt")),
            deleteIfPresent(File(externalRoot, "files/login-identifier.txt")),
            deleteIfPresent(
                File(
                    externalRoot,
                    "files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Intermediate/SaveGames"
                )
            ),
            deleteChildrenExcept(savedRoot, downloadedContent)
        )

        internalRoot.mkdirs()
        deviceProtectedRoot.mkdirs()

        recreateDeviceXml(
            File(internalRoot, "shared_prefs/device_id.xml")
        )

        return results.all { it }
    }

    private fun deleteChildrenExcept(
        directory: File,
        keep: Set<String>
    ): Boolean =
        directory.listFiles()
            ?.filterNot { it.name in keep }
            ?.all(::deleteIfPresent)
            ?: true

    private fun deleteIfPresent(file: File): Boolean =
        !file.exists() || file.deleteRecursively()

    private val downloadedContent = setOf(
        "CachePaks",
        "MoviesPakDir",
        "Paks",
        "PufferEifs0",
        "PufferEifs1",
        "PufferTmpDir"
    )
}
