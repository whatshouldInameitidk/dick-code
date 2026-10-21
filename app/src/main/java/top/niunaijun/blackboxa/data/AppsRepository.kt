package top.niunaijun.blackboxa.data

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.webkit.URLUtil
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.BlackBoxCore.getPackageManager
import top.niunaijun.blackbox.utils.AbiUtils
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.bean.InstalledAppBean
import top.niunaijun.blackboxa.util.getString
import java.io.File


/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 23:05
 */

class AppsRepository {
    val TAG: String = "AppsRepository"
    private var mInstalledList = mutableListOf<AppInfo>()

    /**
     * App scoped scope. The preview scan is heavy (parse + label + icon of every package),
     * it must not be tied to the ViewModel of the screen that triggered it, otherwise
     * finishing that screen cancels the scan half way through and the list stays empty.
     */
    private val mScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mScanLock = Any()
    private var mScanJob: Deferred<List<AppInfo>>? = null
    private var mScanTime = 0L

    fun previewInstallListAsync() {
        mScope.launch {
            previewInstallList()
        }
    }

    /**
     * Scans the packages of the device. The scan is shared, a second caller joins the
     * running one instead of starting a second scan, and no lock is held while the
     * apk files are read, a blocked lock is what kept the list screen loading forever.
     */
    suspend fun previewInstallList(force: Boolean = false): List<AppInfo> {
        if (!force) {
            val cached = cachedList()
            if (cached.isNotEmpty()) return cached
        }
        val job = synchronized(mScanLock) {
            val running = mScanJob
            if (running != null && running.isActive) {
                running
            } else {
                mScope.async { scanInstalledApps() }.also { mScanJob = it }
            }
        }
        val result = job.await()
        synchronized(mScanLock) {
            mInstalledList.clear()
            mInstalledList.addAll(result)
            mScanTime = System.currentTimeMillis()
        }
        return result
    }

    /**
     * The scan takes a few seconds, a result that is not older than a minute is used
     * as it is, the list screen does not have to wait for a new scan.
     */
    private fun cachedList(): List<AppInfo> {
        synchronized(mScanLock) {
            if (mInstalledList.isEmpty()) return emptyList()
            if (System.currentTimeMillis() - mScanTime > CACHE_VALID_MS) return emptyList()
            return mInstalledList.toList()
        }
    }

    private suspend fun scanInstalledApps(): List<AppInfo> {
        val installedApplications: List<ApplicationInfo> = try {
            getPackageManager().getInstalledApplications(0)
        } catch (t: Throwable) {
            Log.e(TAG, "getInstalledApplications failed", t)
            emptyList()
        }

        val candidates = installedApplications.filter {
            (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        }
        Log.d(TAG, "scan ${candidates.size} package(s)")

        // reading the apk of every package is the expensive part, do it in parallel
        val semaphore = Semaphore(SCAN_PARALLELISM)
        return coroutineScope {
            candidates.map { app ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { buildAppInfo(app) }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun buildAppInfo(app: ApplicationInfo): AppInfo? {
        return try {
            val file = File(app.sourceDir)

            if (!AbiUtils.isSupport(file)) return null

            val isXpModule = BlackBoxCore.get().isXposedModule(file)

            AppInfo(
                loadLabel(app),
                loadIcon(app),
                app.packageName,
                app.sourceDir,
                isXpModule
            )
        } catch (t: Throwable) {
            // one broken package (bad icon, huge bitmap, oom) must not kill the whole list
            Log.e(TAG, "skip package ${app.packageName}", t)
            null
        }
    }

    private fun loadLabel(app: ApplicationInfo): String {
        return try {
            app.loadLabel(getPackageManager()).toString()
        } catch (t: Throwable) {
            Log.e(TAG, "loadLabel failed: ${app.packageName}", t)
            app.packageName
        }
    }

    private fun loadIcon(app: ApplicationInfo): Drawable {
        return try {
            app.loadIcon(getPackageManager())
        } catch (t: Throwable) {
            Log.e(TAG, "loadIcon failed: ${app.packageName}", t)
            getPackageManager().defaultActivityIcon
        }
    }

    suspend fun getInstalledAppList(
        userID: Int,
        loadingLiveData: MutableLiveData<Boolean>,
        appsLiveData: MutableLiveData<List<InstalledAppBean>>
    ) {
        loadingLiveData.postValue(true)
        try {
            val blackBoxCore = BlackBoxCore.get()
            val list = previewInstallList()
            Log.d(TAG, "installed packages: ${list.joinToString(",") { it.packageName }}")
            val newInstalledList = list.map {
                InstalledAppBean(
                    it.name,
                    it.icon,
                    it.packageName,
                    it.sourceDir,
                    isInstalled(blackBoxCore, it.packageName, userID)
                )
            }
            appsLiveData.postValue(newInstalledList)
        } catch (t: Throwable) {
            Log.e(TAG, "getInstalledAppList failed", t)
            appsLiveData.postValue(emptyList())
        } finally {
            // the spinner must always leave, whatever happened above
            loadingLiveData.postValue(false)
        }
    }

    private fun isInstalled(blackBoxCore: BlackBoxCore, packageName: String, userID: Int): Boolean {
        return try {
            blackBoxCore.isInstalled(packageName, userID)
        } catch (t: Throwable) {
            Log.e(TAG, "isInstalled failed: $packageName", t)
            false
        }
    }

    suspend fun getInstalledModuleList(
        loadingLiveData: MutableLiveData<Boolean>,
        appsLiveData: MutableLiveData<List<InstalledAppBean>>
    ) {

        loadingLiveData.postValue(true)
        try {
            val blackBoxCore = BlackBoxCore.get()
            val list = previewInstallList()
            val moduleList = list.filter {
                it.isXpModule
            }.map {
                InstalledAppBean(
                    it.name,
                    it.icon,
                    it.packageName,
                    it.sourceDir,
                    isInstalledXposedModule(blackBoxCore, it.packageName)
                )
            }
            appsLiveData.postValue(moduleList)
        } catch (t: Throwable) {
            Log.e(TAG, "getInstalledModuleList failed", t)
            appsLiveData.postValue(emptyList())
        } finally {
            loadingLiveData.postValue(false)
        }

    }

    private fun isInstalledXposedModule(blackBoxCore: BlackBoxCore, packageName: String): Boolean {
        return try {
            blackBoxCore.isInstalledXposedModule(packageName)
        } catch (t: Throwable) {
            Log.e(TAG, "isInstalledXposedModule failed: $packageName", t)
            false
        }
    }


    fun getVmInstallList(userId: Int, appsLiveData: MutableLiveData<List<AppInfo>>) {
        try {
            val sortListData =
                AppManager.mRemarkSharedPreferences.getString("AppList$userId", "")
            val sortList = sortListData?.split(",")

            val applicationList = BlackBoxCore.get().getInstalledApplications(0, userId)

            val appInfoList = mutableListOf<AppInfo>()
            applicationList.also {
                if (sortList.isNullOrEmpty()) {
                    return@also
                }
                it.sortWith(AppsSortComparator(sortList))

            }.forEach {
                val info = AppInfo(
                    loadLabel(it),
                    loadIcon(it),
                    it.packageName,
                    it.sourceDir,
                    isInstalledXpModule(it.packageName)
                )

                appInfoList.add(info)
            }


            appsLiveData.postValue(appInfoList)
        } catch (t: Throwable) {
            Log.e(TAG, "getVmInstallList failed", t)
            appsLiveData.postValue(emptyList())
        }
    }

    private fun isInstalledXpModule(packageName: String): Boolean {
        BlackBoxCore.get().installedXPModules.forEach {
            if (packageName == it.packageName) {
                return@isInstalledXpModule true
            }
        }

        return false
    }


    fun installApk(source: String, userId: Int, resultLiveData: MutableLiveData<String>) {
        val blackBoxCore = BlackBoxCore.get()
        val installResult = if (URLUtil.isValidUrl(source)) {
            val uri = Uri.parse(source)
            blackBoxCore.installPackageAsUser(uri, userId)
        } else {
            blackBoxCore.installPackageAsUser(source, userId)
        }

        if (installResult.success) {
            updateAppSortList(userId, installResult.packageName, true)
            resultLiveData.postValue(getString(R.string.install_success))
        } else {
            resultLiveData.postValue(getString(R.string.install_fail, installResult.msg))
        }
        scanUser()
    }

    fun unInstall(packageName: String, userID: Int, resultLiveData: MutableLiveData<String>) {
        BlackBoxCore.get().uninstallPackageAsUser(packageName, userID)
        updateAppSortList(userID, packageName, false)
        scanUser()
        resultLiveData.postValue(getString(R.string.uninstall_success))
    }


    fun launchApk(packageName: String, userId: Int, launchLiveData: MutableLiveData<Boolean>) {
        val result = BlackBoxCore.get().launchApk(packageName, userId)
        launchLiveData.postValue(result)
    }


    fun clearApkData(packageName: String, userID: Int, resultLiveData: MutableLiveData<String>) {
        BlackBoxCore.get().clearPackage(packageName, userID)
        resultLiveData.postValue(getString(R.string.clear_success))
    }

    /**
     * 倒序递归扫描用户，
     * 如果用户是空的，就删除用户，删除用户备注，删除应用排序列表
     */
    private fun scanUser() {
        val blackBoxCore = BlackBoxCore.get()
        val userList = blackBoxCore.users

        if (userList.isEmpty()) {
            return
        }

        val id = userList.last().id

        if (blackBoxCore.getInstalledApplications(0, id).isEmpty()) {
            blackBoxCore.deleteUser(id)
            AppManager.mRemarkSharedPreferences.edit {
                remove("Remark$id")
                remove("AppList$id")
            }
            scanUser()
        }
    }


    /**
     * 更新排序列表
     * @param userID Int
     * @param pkg String
     * @param isAdd Boolean true是添加，false是移除
     */
    private fun updateAppSortList(userID: Int, pkg: String, isAdd: Boolean) {

        val savedSortList =
            AppManager.mRemarkSharedPreferences.getString("AppList$userID", "")

        val sortList = linkedSetOf<String>()
        if (savedSortList != null) {
            sortList.addAll(savedSortList.split(","))
        }

        if (isAdd) {
            sortList.add(pkg)
        } else {
            sortList.remove(pkg)
        }

        AppManager.mRemarkSharedPreferences.edit {
            putString("AppList$userID", sortList.joinToString(","))
        }

    }

    /**
     * 保存排序后的apk顺序
     */
    fun updateApkOrder(userID: Int, dataList: List<AppInfo>) {
        AppManager.mRemarkSharedPreferences.edit {
            putString("AppList$userID",
                dataList.joinToString(",") { it.packageName })
        }

    }

    companion object {
        /**
         * How many apk files are read at the same time. Reading them is IO bound,
         * 4 keeps the phone responsive and takes a third of the time of a serial scan.
         */
        private const val SCAN_PARALLELISM = 4

        /**
         * How long a scan result is used before a new scan is started.
         */
        private const val CACHE_VALID_MS = 60_000L
    }

}
