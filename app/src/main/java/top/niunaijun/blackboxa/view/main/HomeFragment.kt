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
import top.niunaijun.blackboxa.util.ShortcutUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initListeners()
        val ver = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
        viewBinding.tvAppTitle.text = "InvernessLoader"
        viewBinding.tvAppVersion.text = "v$ver"
        viewBinding.tvAppVersion.setOnClickListener {
            activity?.let { act -> top.niunaijun.blackboxa.util.AppUpdater.checkForUpdate(act, manual = true) }
        }
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun initListeners() {
        viewBinding.btnUserChip.setOnClickListener {
            showUserSelectorDialog()
        }

        viewBinding.btnSearch.setOnClickListener {
            val mainActivity = activity as? MainActivity
            mainActivity?.switchToTab(1) // Switch to Apps tab
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

        viewBinding.btnGameMenu.setOnClickListener { v ->
            showGameMenu(v)
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
            val statusText = if (versionName.isNotEmpty()) "Installed • $versionName" else "Installed"
            viewBinding.tvGameStatus.text = statusText
            viewBinding.tvGameStatus.setTextColor(Color.parseColor("#8EA2B9"))
            viewBinding.viewStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E676"))

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
        try {
            toast("Launching BGMI...")
            BlackBoxCore.get().launchApk(bgmiPackageName, currentUserId)
        } catch (t: Throwable) {
            toast("Failed to launch: ${t.message}")
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
            popup.menu.add(0, 2, 1, "Create Shortcut")
            popup.menu.add(0, 3, 2, "Clear Data")
            popup.menu.add(0, 4, 3, "Uninstall")
        } else {
            popup.menu.add(0, 5, 0, "Install from Device")
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> launchBgmi()
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
