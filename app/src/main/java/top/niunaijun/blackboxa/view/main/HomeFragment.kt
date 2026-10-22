package top.niunaijun.blackboxa.view.main

import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.databinding.FragmentHomeBinding
import top.niunaijun.blackboxa.util.ObbManager
import top.niunaijun.blackboxa.util.ShortcutUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import java.io.File

class HomeFragment : Fragment() {

    private val viewBinding: FragmentHomeBinding by inflate()
    private val bgmiPackageName = "com.pubg.imobile"

    var currentUserId: Int = 0
        set(value) {
            field = value
            if (isAdded) {
                updateUI()
            }
        }

    private val selectApkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { installApkUri(it) }
    }

    private val selectObbLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { handleSelectedObbUri(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initClicks()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun initClicks() {
        viewBinding.btnUserChip.setOnClickListener {
            showUserSelectorDialog()
        }

        viewBinding.btnLaunchGame.setOnClickListener {
            launchBgmi()
        }

        viewBinding.btnShortcutGame.setOnClickListener {
            createBgmiShortcut()
        }

        viewBinding.btnInstallGame.setOnClickListener {
            checkAndPromptGameInstall()
        }

        viewBinding.btnGameMenu.setOnClickListener { anchor ->
            showGameMenu(anchor)
        }

        viewBinding.cardBgmi.setOnLongClickListener {
            showGameMenu(viewBinding.btnGameMenu)
            true
        }
    }

    fun updateUI() {
        if (!isAdded) return

        val remark = AppManager.mRemarkSharedPreferences.getString("Remark$currentUserId", "User $currentUserId")
        viewBinding.tvUserName.text = if (remark.isNullOrEmpty()) "User $currentUserId" else remark

        val isInstalled = try {
            BlackBoxCore.get().isInstalled(bgmiPackageName, currentUserId)
        } catch (t: Throwable) {
            false
        }

        if (isInstalled) {
            var versionName = ""
            var iconDrawable: Drawable? = null
            var appLabel = "BATTLEGROUNDS MOBILE INDIA"

            try {
                val packageInfo: PackageInfo? = BlackBoxCore.getBPackageManager().getPackageInfo(bgmiPackageName, 0, currentUserId)
                if (packageInfo != null) {
                    versionName = packageInfo.versionName ?: ""
                    val appInfo = packageInfo.applicationInfo
                    if (appInfo != null) {
                        appLabel = appInfo.loadLabel(requireContext().packageManager).toString()
                        iconDrawable = appInfo.loadIcon(requireContext().packageManager)
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }

            if (iconDrawable != null) {
                viewBinding.ivGameIcon.setImageDrawable(iconDrawable)
            } else {
                try {
                    val hostIcon = requireContext().packageManager.getApplicationIcon(bgmiPackageName)
                    viewBinding.ivGameIcon.setImageDrawable(hostIcon)
                } catch (t: Throwable) {
                    viewBinding.ivGameIcon.setImageResource(R.mipmap.ic_launcher)
                }
            }

            viewBinding.tvGameTitle.text = appLabel

            val hasObb = ObbManager.isObbInstalled(bgmiPackageName, currentUserId)
            if (hasObb) {
                val obbFile = ObbManager.getPrimaryObbFile(bgmiPackageName, currentUserId)
                val obbSizeStr = if (obbFile != null) " (${ObbManager.formatFileSize(obbFile.length())})" else ""
                val statusText = if (versionName.isNotEmpty()) "Installed • $versionName$obbSizeStr" else "Installed$obbSizeStr"
                viewBinding.tvGameStatus.text = statusText
                viewBinding.tvGameStatus.setTextColor(Color.parseColor("#8EA2B9"))
                viewBinding.viewStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E676"))
            } else {
                val statusText = if (versionName.isNotEmpty()) "Installed • $versionName (OBB Missing)" else "Installed (OBB Missing)"
                viewBinding.tvGameStatus.text = statusText
                viewBinding.tvGameStatus.setTextColor(Color.parseColor("#FFB300"))
                viewBinding.viewStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFB300"))
            }

            viewBinding.layoutInstalledActions.visibility = View.VISIBLE
            viewBinding.layoutNotInstalledActions.visibility = View.GONE
        } else {
            viewBinding.tvGameTitle.text = "BATTLEGROUNDS MOBILE INDIA"
            viewBinding.tvGameStatus.text = "Not installed"
            viewBinding.tvGameStatus.setTextColor(Color.parseColor("#6C798A"))
            viewBinding.viewStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#6C798A"))

            try {
                val hostIcon = requireContext().packageManager.getApplicationIcon(bgmiPackageName)
                viewBinding.ivGameIcon.setImageDrawable(hostIcon)
            } catch (t: Throwable) {
                viewBinding.ivGameIcon.setImageResource(R.mipmap.ic_launcher)
            }

            viewBinding.layoutInstalledActions.visibility = View.GONE
            viewBinding.layoutNotInstalledActions.visibility = View.VISIBLE
        }
    }

    private fun checkAndPromptGameInstall() {
        val pm = requireContext().packageManager
        val isHostInstalled = try {
            pm.getPackageInfo(bgmiPackageName, 0) != null
        } catch (e: Exception) {
            false
        }

        if (isHostInstalled) {
            var appLabel = "BATTLEGROUNDS MOBILE INDIA"
            try {
                val appInfo = pm.getApplicationInfo(bgmiPackageName, 0)
                appLabel = appInfo.loadLabel(pm).toString()
            } catch (t: Throwable) {}

            MaterialDialog(requireContext()).show {
                title(text = "Install $appLabel")
                message(text = "$appLabel is installed on your device. Would you like to install it into Inverness virtual space now?")
                positiveButton(text = "Install Now") {
                    installHostGame()
                }
                negativeButton(res = R.string.cancel)
            }
        } else {
            MaterialDialog(requireContext()).show {
                title(text = "Game Not Found")
                message(text = "BATTLEGROUNDS MOBILE INDIA is not installed on this device. You can choose an APK file from storage or install it from Play Store.")
                positiveButton(text = "Choose APK") {
                    openApkPicker()
                }
                negativeButton(res = R.string.cancel)
            }
        }
    }

    private fun installHostGame() {
        val loading = MaterialDialog(requireContext()).apply {
            title(text = "Installing")
            message(text = "Installing BATTLEGROUNDS MOBILE INDIA into virtual space... Please wait.")
            cancelable(false)
            show()
        }

        Thread {
            try {
                val blackBoxCore = BlackBoxCore.get()
                val installResult = blackBoxCore.installPackageAsUser(bgmiPackageName, currentUserId)
                val isInstalled = blackBoxCore.isInstalled(bgmiPackageName, currentUserId)

                // Try auto-syncing OBB files if discovered
                val hostObbs = ObbManager.findHostObbFiles(requireContext(), bgmiPackageName)
                if (hostObbs.isNotEmpty() && !ObbManager.isObbInstalled(bgmiPackageName, currentUserId)) {
                    val targetDir = ObbManager.getVirtualObbDir(bgmiPackageName, currentUserId)
                    for (obb in hostObbs) {
                        val target = File(targetDir, obb.name)
                        if (!target.exists() || target.length() != obb.length()) {
                            ObbManager.copyFileWithProgress(obb, target)
                        }
                    }
                }

                activity?.runOnUiThread {
                    loading.dismiss()
                    if (installResult.success && isInstalled) {
                        toast("BGMI installed successfully!")
                        updateUI()
                        (activity as? MainActivity)?.refreshHomeAndApps()
                    } else {
                        val errorDetail = if (!installResult.success) installResult.msg else "Verification failed: app not active in virtual space"
                        toast("Install failed: $errorDetail")
                        updateUI()
                    }
                }
            } catch (t: Throwable) {
                activity?.runOnUiThread {
                    loading.dismiss()
                    toast("Install error: ${t.localizedMessage}")
                }
            }
        }.start()
    }

    private fun openApkPicker() {
        try {
            selectApkLauncher.launch("application/vnd.android.package-archive")
        } catch (t: Throwable) {
            toast("Unable to open file picker: ${t.localizedMessage}")
        }
    }

    private fun installApkUri(uri: Uri) {
        val loading = MaterialDialog(requireContext()).apply {
            title(text = "Installing")
            message(text = "Installing APK into virtual space... Please wait.")
            cancelable(false)
            show()
        }

        Thread {
            try {
                val blackBoxCore = BlackBoxCore.get()
                val installResult = blackBoxCore.installPackageAsUser(uri, currentUserId)

                activity?.runOnUiThread {
                    loading.dismiss()
                    if (installResult.success) {
                        toast("App installed successfully!")
                        updateUI()
                        (activity as? MainActivity)?.refreshHomeAndApps()
                    } else {
                        toast("Install failed: ${installResult.msg}")
                    }
                }
            } catch (t: Throwable) {
                activity?.runOnUiThread {
                    loading.dismiss()
                    toast("Install error: ${t.localizedMessage}")
                }
            }
        }.start()
    }

    private fun launchBgmi() {
        if (!ObbManager.isObbInstalled(bgmiPackageName, currentUserId)) {
            val candidates = ObbManager.findHostObbFiles(requireContext(), bgmiPackageName)
            if (candidates.isNotEmpty()) {
                promptAutoCopyObbAndLaunch(candidates.first())
                return
            }
            showObbMissingDialog()
            return
        }
        performDirectLaunch()
    }

    private fun performDirectLaunch() {
        try {
            toast("Launching BGMI...")
            BlackBoxCore.get().launchApk(bgmiPackageName, currentUserId)
        } catch (t: Throwable) {
            toast("Failed to launch: ${t.message}")
        }
    }

    private fun promptAutoCopyObbAndLaunch(sourceFile: File) {
        val sizeStr = ObbManager.formatFileSize(sourceFile.length())
        MaterialDialog(requireContext()).show {
            title(text = "Game Data (OBB) Detected")
            message(text = "Found OBB game file:\n${sourceFile.name} ($sizeStr)\n\nWould you like to install it into Inverness virtual space now?")
            positiveButton(text = "Install & Launch") {
                copyObbFileAndLaunch(sourceFile)
            }
            neutralButton(text = "Select Another") {
                openObbPicker()
            }
            negativeButton(res = R.string.cancel)
        }
    }

    private fun showObbMissingDialog() {
        MaterialDialog(requireContext()).show {
            title(text = "Game Data (OBB) Required")
            message(text = "BATTLEGROUNDS MOBILE INDIA requires its OBB data file to start.\n\nOn Samsung & Android 14+ devices, system OBB folders are protected.\n\nHow to fix:\n• Tap 'Select OBB File' to choose your main.xxxx.com.pubg.imobile.obb file from Downloads or Storage.\n• Or use Samsung 'My Files' to copy the OBB file into your Download folder, then tap 'Auto-Scan'.")
            positiveButton(text = "Select OBB File") {
                openObbPicker()
            }
            neutralButton(text = "Auto-Scan") {
                scanAndNotifyObb()
            }
            negativeButton(text = "Launch Anyway") {
                performDirectLaunch()
            }
        }
    }

    private fun openObbPicker() {
        try {
            selectObbLauncher.launch(arrayOf("*/*"))
        } catch (t: Throwable) {
            toast("Unable to open file picker: ${t.localizedMessage}")
        }
    }

    private fun handleSelectedObbUri(uri: Uri) {
        val fileName = ObbManager.getUriFileName(requireContext(), uri, bgmiPackageName)
        val targetDir = ObbManager.getVirtualObbDir(bgmiPackageName, currentUserId)
        val targetFile = File(targetDir, fileName)

        val loading = MaterialDialog(requireContext()).apply {
            title(text = "Installing OBB Data")
            message(text = "Copying $fileName into virtual space... Please wait.")
            cancelable(false)
            show()
        }

        Thread {
            val success = ObbManager.copyUriWithProgress(requireContext(), uri, targetFile) { percent, copied, total ->
                activity?.runOnUiThread {
                    val progressMsg = if (total > 0) {
                        "Copying $fileName: $percent% (${ObbManager.formatFileSize(copied)} / ${ObbManager.formatFileSize(total)})"
                    } else {
                        "Copying $fileName: ${ObbManager.formatFileSize(copied)}..."
                    }
                    loading.message(text = progressMsg)
                }
            }

            activity?.runOnUiThread {
                loading.dismiss()
                if (success) {
                    toast("OBB installed successfully!")
                    updateUI()
                    performDirectLaunch()
                } else {
                    toast("Failed to copy OBB file. Please check storage space.")
                }
            }
        }.start()
    }

    private fun copyObbFileAndLaunch(sourceFile: File) {
        val targetDir = ObbManager.getVirtualObbDir(bgmiPackageName, currentUserId)
        val targetFile = File(targetDir, sourceFile.name)

        val loading = MaterialDialog(requireContext()).apply {
            title(text = "Installing OBB Data")
            message(text = "Copying ${sourceFile.name}... Please wait.")
            cancelable(false)
            show()
        }

        Thread {
            val success = ObbManager.copyFileWithProgress(sourceFile, targetFile) { percent, copied, total ->
                activity?.runOnUiThread {
                    loading.message(text = "Copying ${sourceFile.name}: $percent% (${ObbManager.formatFileSize(copied)} / ${ObbManager.formatFileSize(total)})")
                }
            }

            activity?.runOnUiThread {
                loading.dismiss()
                if (success) {
                    toast("OBB installed successfully!")
                    updateUI()
                    performDirectLaunch()
                } else {
                    toast("Failed to copy OBB file.")
                }
            }
        }.start()
    }

    private fun showManageObbDialog() {
        val installedFiles = ObbManager.getInstalledObbFiles(bgmiPackageName, currentUserId)
        val statusMsg = if (installedFiles.isNotEmpty()) {
            val details = installedFiles.joinToString("\n") { "• ${it.name} (${ObbManager.formatFileSize(it.length())})" }
            "OBB Status: Installed\n\nActive files in virtual space:\n$details"
        } else {
            "OBB Status: Missing (Game will hang on 0MB / 0MB download screen)\n\nNo OBB files found in virtual storage."
        }

        MaterialDialog(requireContext()).show {
            title(text = "Manage OBB Game Data")
            message(text = statusMsg)
            positiveButton(text = "Select OBB File") {
                openObbPicker()
            }
            neutralButton(text = "Auto-Scan Storage") {
                scanAndNotifyObb()
            }
            negativeButton(res = R.string.cancel)
        }
    }

    private fun scanAndNotifyObb() {
        val found = ObbManager.findHostObbFiles(requireContext(), bgmiPackageName)
        if (found.isNotEmpty()) {
            promptAutoCopyObbAndLaunch(found.first())
        } else {
            MaterialDialog(requireContext()).show {
                title(text = "No OBB Files Found")
                message(text = "Could not find any OBB file for $bgmiPackageName in Downloads or storage.\n\nPlease copy your OBB file into your 'Download' folder or tap 'Select OBB File' to browse manually.")
                positiveButton(text = "Select OBB File") {
                    openObbPicker()
                }
                negativeButton(res = R.string.cancel)
            }
        }
    }

    private fun createBgmiShortcut() {
        try {
            var icon = viewBinding.ivGameIcon.drawable
            if (icon == null) {
                icon = requireContext().packageManager.getApplicationIcon(bgmiPackageName)
            }
            val appInfo = AppInfo(
                name = "BGMI",
                icon = icon,
                packageName = bgmiPackageName,
                sourceDir = "",
                isXpModule = false
            )
            ShortcutUtil.createShortcut(requireContext(), currentUserId, appInfo)
        } catch (t: Throwable) {
            toast("Failed to create shortcut: ${t.message}")
        }
    }

    private fun showGameMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        val isInstalled = try {
            BlackBoxCore.get().isInstalled(bgmiPackageName, currentUserId)
        } catch (t: Throwable) {
            false
        }

        if (isInstalled) {
            popup.menu.add(0, 1, 0, "Launch Game")
            popup.menu.add(0, 6, 1, "Manage OBB Data")
            popup.menu.add(0, 2, 2, "Create Shortcut")
            popup.menu.add(0, 3, 3, "Clear Data")
            popup.menu.add(0, 4, 4, "Uninstall")
        } else {
            popup.menu.add(0, 5, 0, "Install from Device")
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> launchBgmi()
                6 -> showManageObbDialog()
                2 -> createBgmiShortcut()
                3 -> {
                    MaterialDialog(requireContext()).show {
                        title(text = "Clear Data")
                        message(text = "Clear all data and caches for BGMI?")
                        positiveButton(R.string.done) {
                            try {
                                BlackBoxCore.get().clearPackage(bgmiPackageName, currentUserId)
                                toast("Data cleared")
                            } catch (t: Throwable) {
                                toast("Failed to clear data")
                            }
                        }
                        negativeButton(R.string.cancel)
                    }
                }
                4 -> {
                    MaterialDialog(requireContext()).show {
                        title(text = "Uninstall Game")
                        message(text = "Uninstall BGMI from virtual space?")
                        positiveButton(R.string.done) {
                            try {
                                BlackBoxCore.get().uninstallPackageAsUser(bgmiPackageName, currentUserId)
                                toast("Uninstalled")
                                updateUI()
                                (activity as? MainActivity)?.refreshHomeAndApps()
                            } catch (t: Throwable) {
                                toast("Failed to uninstall")
                            }
                        }
                        negativeButton(R.string.cancel)
                    }
                }
                5 -> {
                    checkAndPromptGameInstall()
                }
            }
            true
        }
        popup.show()
    }

    private fun showUserSelectorDialog() {
        val users = try {
            BlackBoxCore.get().users ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }

        val userItems = users.map { u ->
            val rem = AppManager.mRemarkSharedPreferences.getString("Remark${u.id}", "User ${u.id}")
            if (u.id == currentUserId) "$rem (Active)" else rem ?: "User ${u.id}"
        }

        if (userItems.isEmpty()) {
            toast("No other users available")
            return
        }

        MaterialDialog(requireContext()).show {
            title(text = "Select Virtual User")
            listItems(items = userItems) { _, index, _ ->
                val selectedUser = users[index].id
                (activity as? MainActivity)?.setCurrentUser(selectedUser)
            }
            positiveButton(text = "Manage Users") {
                (activity as? MainActivity)?.switchToTab(2) // Switch to Users tab
            }
            negativeButton(res = R.string.cancel)
        }
    }

    companion object {
        fun newInstance(userId: Int = 0): HomeFragment {
            val f = HomeFragment()
            f.currentUserId = userId
            return f
        }
    }
}
