package top.niunaijun.blackboxa.view.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.graphics.Color
import android.widget.TextView
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.view.Window
import android.graphics.drawable.ColorDrawable
import android.app.Dialog
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
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
import top.niunaijun.blackboxa.util.FontProvisioner
import top.niunaijun.blackboxa.util.GuestResetter
import top.niunaijun.blackboxa.util.ShortcutUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import java.io.File

class HomeFragment : Fragment() {

    private val viewBinding: FragmentHomeBinding by inflate()
    private val bgmiPackageName = "com.pubg.imobile"
    private var isInstallingObb = false

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

    private val getContentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (uri != null) {
            handleSelectedObbUri(uri)
        }
    }

    private val allFilesAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasHostObbAccess()) {
            scanAndNotifyObb()
        } else {
            toast("Storage access is required to copy the original OBB.")
        }
    }

    private val readStoragePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scanAndNotifyObb()
        } else {
            toast("Storage access is required to copy the original OBB.")
        }
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
        viewBinding.tvAppVersion.text = "v" + top.niunaijun.blackboxa.BuildConfig.VERSION_NAME
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        if (!isInstallingObb && !ObbManager.isObbInstalled(bgmiPackageName, currentUserId)) {
            val obbFile = ObbManager.findTelegramObbFiles(bgmiPackageName).firstOrNull()
                ?: ObbManager.findHostObbFiles(requireContext(), bgmiPackageName).firstOrNull()
            if (obbFile != null) {
                installDownloadedTelegramObb(obbFile)
            } else {
                updateUI()
            }
        } else {
            updateUI()
        }
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
                viewBinding.tvLaunchGameText.text = "Launch"
                viewBinding.btnLaunchGame.isEnabled = true
            } else {
                val statusText = if (isInstallingObb) {
                    "Installed • Installing OBB..."
                } else if (versionName.isNotEmpty()) {
                    "Installed • $versionName • OBB required"
                } else {
                    "Installed • OBB required"
                }
                viewBinding.tvGameStatus.text = statusText
                viewBinding.tvGameStatus.setTextColor(Color.parseColor("#8EA2B9"))
                viewBinding.viewStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                viewBinding.tvLaunchGameText.text = if (isInstallingObb) "Installing OBB..." else "Install OBB"
                viewBinding.btnLaunchGame.isEnabled = !isInstallingObb
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
            var appIcon: Drawable? = null
            try {
                val appInfo = pm.getApplicationInfo(bgmiPackageName, 0)
                appLabel = appInfo.loadLabel(pm).toString()
                appIcon = appInfo.loadIcon(pm)
            } catch (t: Throwable) {}
            showHostGameInstallDialog(appLabel, appIcon)
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

    private fun showHostGameInstallDialog(appLabel: String, appIcon: Drawable?) {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dialogView = layoutInflater.inflate(R.layout.dialog_install_game, null)
        dialog.setContentView(dialogView)
        dialogView.findViewById<ImageView>(R.id.iv_install_game_icon).apply {
            if (appIcon != null) setImageDrawable(appIcon) else setImageResource(R.mipmap.ic_launcher)
        }
        dialogView.findViewById<TextView>(R.id.tv_install_game_title).text = "Install $appLabel"
        dialogView.findViewById<TextView>(R.id.tv_install_game_message).text =
            "$appLabel is installed on your device.\nWould you like to install it into Inverness virtual space now?"
        dialogView.findViewById<View>(R.id.btn_install_cancel).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<View>(R.id.btn_install_confirm).setOnClickListener {
            dialog.dismiss()
            installHostGame()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.90).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
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
            installObbFromTelegram()
            return
        }
        performDirectLaunch()
    }

    private fun installObbFromTelegram() {
        if (isInstallingObb) return
        if (!hasHostObbAccess()) {
            requestHostObbAccess()
            return
        }

        val obbFile = ObbManager.findTelegramObbFiles(bgmiPackageName).firstOrNull()
            ?: ObbManager.findHostObbFiles(requireContext(), bgmiPackageName).firstOrNull()
        if (obbFile != null) {
            installDownloadedTelegramObb(obbFile)
        } else {
            openTelegramObbSource()
        }
    }

    private fun openTelegramObbSource() {
        val telegramUrl = "https://t.me/c/2552970003/193398"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(telegramUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            toast("Download OBB from Telegram, then return here to install.")
        } catch (t: Throwable) {
            toast("Unable to open Telegram: ${t.localizedMessage}")
        }
    }

    private fun installDownloadedTelegramObb(sourceFile: File? = null) {
        val fileToInstall = sourceFile
            ?: ObbManager.findTelegramObbFiles(bgmiPackageName).firstOrNull()
            ?: ObbManager.findHostObbFiles(requireContext(), bgmiPackageName).firstOrNull()
            ?: run {
                updateUI()
                return
            }
        isInstallingObb = true
        updateUI()
        Thread {
            val targetDir = ObbManager.getVirtualObbDir(bgmiPackageName, currentUserId)
            val targetFile = File(targetDir, "main.${ObbManager.REQUIRED_BGMI_OBB_VERSION}.$bgmiPackageName.obb")
            val success = ObbManager.copyFileWithProgress(fileToInstall, targetFile)
            activity?.runOnUiThread {
                isInstallingObb = false
                if (success) {
                    toast("OBB installed successfully!")
                } else {
                    toast("Failed to install OBB.")
                }
                updateUI()
            }
        }.start()
    }

    private fun performDirectLaunch() {
        val ctx = context ?: return
        val loading = showGamePreparationDialog()
        Thread {
            try {
                when (val fontResult = FontProvisioner.ensureBgmiFont(currentUserId)) {
                    FontProvisioner.Result.Available -> activity?.runOnUiThread {
                        loading.findViewById<TextView>(R.id.tv_prepare_status)?.text = "Font ready. Downloading and verifying module..."
                    }
                    is FontProvisioner.Result.Failed -> {
                        activity?.runOnUiThread {
                            loading.dismiss()
                            showFontPreparationFailure(fontResult.message)
                        }
                        return@Thread
                    }
                }
                top.niunaijun.blackbox.app.NativeLoader.configure(
                    ctx,
                    bgmiPackageName,
                    bgmiPackageName,
                    "https://raw.githubusercontent.com/whatshouldInameitidk/InvernessLoader/refs/heads/main/InvernessAndriod.so",
                    "8bea8da5cfbd10242372bf2673c30cc542f8c54d967d9b24ee69eb29214bdf5c"
                )
                val prepareResult = top.niunaijun.blackbox.app.NativeLoader.prepare(ctx, bgmiPackageName)
                if (prepareResult != top.niunaijun.blackbox.app.NativeLoader.PrepareResult.READY) {
                    activity?.runOnUiThread {
                        loading.dismiss()
                        MaterialDialog(requireContext()).show {
                            title(text = "Module preparation failed")
                            message(text = "The game was not launched.\n\n${top.niunaijun.blackbox.app.NativeLoader.getLastFailureMessage()}")
                            positiveButton(text = "Close")
                        }
                    }
                    return@Thread
                }
            } catch (t: Throwable) {
                android.util.Log.e("NativeLoader", "Module configure/prepare error: ${t.message}")
                activity?.runOnUiThread {
                    loading.dismiss()
                    MaterialDialog(requireContext()).show {
                        title(text = "Module preparation failed")
                        message(text = "The game was not launched.\n\n${t.localizedMessage ?: t.javaClass.simpleName}")
                        positiveButton(text = "Close")
                    }
                }
                return@Thread
            }
            activity?.runOnUiThread {
                try {
                    loading.dismiss()
                    toast("Launching BGMI...")
                    val blackBoxCore = BlackBoxCore.get()
                    blackBoxCore.stopPackage(bgmiPackageName, currentUserId)
                    blackBoxCore.launchApk(bgmiPackageName, currentUserId)
                } catch (t: Throwable) {
                    toast("Failed to launch: ${t.message}")
                }
            }
        }.start()
    }

    private fun promptAutoCopyObbAndLaunch(sourceFile: File) {
        val context = context ?: return
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dialogView = layoutInflater.inflate(R.layout.dialog_obb_detected, null)
        dialog.setContentView(dialogView)

        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val sizeStr = if (sourceFile.exists() && sourceFile.length() > 0) ObbManager.formatFileSize(sourceFile.length()) else "1.1 GB" 
        val tvDialogTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val tvDialogSubtitle = dialogView.findViewById<TextView>(R.id.tvDialogSubtitle)
        val tvFileCardLabel = dialogView.findViewById<TextView>(R.id.tvFileCardLabel)
        val tvFileName = dialogView.findViewById<TextView>(R.id.tvFileName)
        val tvFileSize = dialogView.findViewById<TextView>(R.id.tvFileSize)
        val tvQuestionMessage = dialogView.findViewById<TextView>(R.id.tvQuestionMessage)
        val layoutProgressSection = dialogView.findViewById<LinearLayout>(R.id.layoutProgressSection)
        val layoutButtons = dialogView.findViewById<LinearLayout>(R.id.layoutButtons)
        val layoutQuestionPrompt = dialogView.findViewById<LinearLayout>(R.id.layoutQuestionPrompt)
        val pbObbProgress = dialogView.findViewById<ProgressBar>(R.id.pbObbProgress)
        val tvProgressStatus = dialogView.findViewById<TextView>(R.id.tvProgressStatus)
        val tvProgressPercent = dialogView.findViewById<TextView>(R.id.tvProgressPercent)

        val btnPrimaryAction = dialogView.findViewById<LinearLayout>(R.id.btnPrimaryAction)
        val tvPrimaryBtnText = dialogView.findViewById<TextView>(R.id.tvPrimaryBtnText)
        val btnDialogCancel = dialogView.findViewById<FrameLayout>(R.id.btnDialogCancel)
        val btnDialogSecondary = dialogView.findViewById<FrameLayout>(R.id.btnDialogSecondary)
        val tvSecondaryText = dialogView.findViewById<TextView>(R.id.tvSecondaryText)

        tvDialogTitle.text = "Game Data (OBB)"
        tvDialogSubtitle.text = "Detected"
        tvFileCardLabel.text = "FOUND OBB GAME FILE:"
        tvFileName.text = sourceFile.name
        tvFileSize.text = "($sizeStr)"
        tvQuestionMessage.text = "Would you like to install it into\nInverness virtual space now?"
        tvPrimaryBtnText.text = "INSTALL & LAUNCH"
        tvSecondaryText.text = "SELECT ANOTHER"

        btnPrimaryAction.setOnClickListener {
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            layoutButtons.visibility = View.GONE
            layoutQuestionPrompt.visibility = View.GONE
            layoutProgressSection.visibility = View.VISIBLE
            tvProgressStatus.text = "Copying ${sourceFile.name}..."
            tvProgressPercent.text = "0%"
            pbObbProgress.progress = 0

            val targetDir = ObbManager.getVirtualObbDir(bgmiPackageName, currentUserId)
            val targetFile = File(targetDir, sourceFile.name)

            Thread {
                val success = ObbManager.copyFileWithProgress(sourceFile, targetFile) { percent, copied, total ->
                    activity?.runOnUiThread {
                        pbObbProgress.progress = percent
                        tvProgressPercent.text = "$percent%"
                        val formattedCopied = ObbManager.formatFileSize(copied)
                        val formattedTotal = ObbManager.formatFileSize(total)
                        tvProgressStatus.text = "Copying: $formattedCopied / $formattedTotal"
                    }
                }

                activity?.runOnUiThread {
                    dialog.dismiss()
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

        btnDialogCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnDialogSecondary.setOnClickListener {
            dialog.dismiss()
            openObbPicker()
        }

        dialog.show()
    }

    private fun showObbMissingDialog(protectedHostFile: File? = null) {
        val context = context ?: return
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dialogView = layoutInflater.inflate(R.layout.dialog_obb_detected, null)
        dialog.setContentView(dialogView)

        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val tvDialogTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val tvDialogSubtitle = dialogView.findViewById<TextView>(R.id.tvDialogSubtitle)
        val tvFileCardLabel = dialogView.findViewById<TextView>(R.id.tvFileCardLabel)
        val tvFileName = dialogView.findViewById<TextView>(R.id.tvFileName)
        val tvFileSize = dialogView.findViewById<TextView>(R.id.tvFileSize)
        val tvQuestionMessage = dialogView.findViewById<TextView>(R.id.tvQuestionMessage)
        val btnPrimaryAction = dialogView.findViewById<LinearLayout>(R.id.btnPrimaryAction)
        val tvPrimaryBtnText = dialogView.findViewById<TextView>(R.id.tvPrimaryBtnText)
        val btnDialogCancel = dialogView.findViewById<FrameLayout>(R.id.btnDialogCancel)
        val tvCancelText = dialogView.findViewById<TextView>(R.id.tvCancelText)
        val btnDialogSecondary = dialogView.findViewById<FrameLayout>(R.id.btnDialogSecondary)
        val tvSecondaryText = dialogView.findViewById<TextView>(R.id.tvSecondaryText)

        tvDialogTitle.text = "Game Data (OBB)"

        if (protectedHostFile != null) {
            tvDialogSubtitle.text = "Detected on Device"
            tvFileCardLabel.text = "FOUND ORIGINAL OBB:"
            tvFileName.text = protectedHostFile.name
            val sizeStr = ObbManager.formatFileSize(protectedHostFile.length())
            tvFileSize.text = "($sizeStr • In /Android/obb)"
            tvQuestionMessage.text = "Android protects /Android/obb from direct copy.\nPlease select it using your file manager (ZArchiver / Files) or move it into 'Download', then tap AUTO-SCAN."
            tvPrimaryBtnText.text = "SELECT WITH FILE MANAGER"
            tvCancelText.text = "CANCEL"
            btnDialogCancel.setOnClickListener {
                dialog.dismiss()
            }
        } else {
            tvDialogSubtitle.text = "Required"
            tvFileCardLabel.text = "STATUS:"
            tvFileName.text = "OBB File Not Found"
            tvFileSize.text = "(Searched storage & Downloads)"
            tvQuestionMessage.text = "BGMI requires an OBB file to start.\nIf your game has in-app data or no OBB, tap LAUNCH ANYWAY."
            tvPrimaryBtnText.text = "SELECT OBB FILE"
            tvCancelText.text = "LAUNCH ANYWAY"
            btnDialogCancel.setOnClickListener {
                dialog.dismiss()
                performDirectLaunch()
            }
        }

        tvSecondaryText.text = "AUTO-SCAN"

        btnPrimaryAction.setOnClickListener {
            dialog.dismiss()
            openObbPicker()
        }

        btnDialogSecondary.setOnClickListener {
            dialog.dismiss()
            scanAndNotifyObb()
        }

        dialog.show()
    }

    private fun openObbPicker() {
        try {
            val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            val openDocIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"))
                }
            }
            val chooser = Intent.createChooser(getContentIntent, "Select BGMI OBB File").apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(openDocIntent))
            }
            getContentLauncher.launch(chooser)
        } catch (t: Throwable) {
            try {
                selectObbLauncher.launch(arrayOf("*/*"))
            } catch (e: Throwable) {
                toast("Unable to open file picker: ${e.localizedMessage}")
            }
        }
    }

    private fun showGamePreparationDialog(): Dialog {
        return Dialog(requireContext()).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(layoutInflater.inflate(R.layout.dialog_prepare_game, null))
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            show()
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout((resources.displayMetrics.widthPixels * 0.90).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun showFontPreparationFailure(message: String) {
        MaterialDialog(requireContext()).show {
            title(text = "Font preparation failed")
            message(text = "The game was not launched.\n\n$message")
            positiveButton(text = "Close")
        }
    }

    private fun handleSelectedObbUri(uri: Uri) {
        val context = context ?: return
        val fileName = ObbManager.getUriFileName(context, uri, bgmiPackageName)
        val targetDir = ObbManager.getVirtualObbDir(bgmiPackageName, currentUserId)
        val targetFile = File(targetDir, fileName)

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dialogView = layoutInflater.inflate(R.layout.dialog_obb_detected, null)
        dialog.setContentView(dialogView)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val tvDialogTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val tvDialogSubtitle = dialogView.findViewById<TextView>(R.id.tvDialogSubtitle)
        val tvFileCardLabel = dialogView.findViewById<TextView>(R.id.tvFileCardLabel)
        val tvFileName = dialogView.findViewById<TextView>(R.id.tvFileName)
        val tvFileSize = dialogView.findViewById<TextView>(R.id.tvFileSize)
        val layoutProgressSection = dialogView.findViewById<LinearLayout>(R.id.layoutProgressSection)
        val layoutButtons = dialogView.findViewById<LinearLayout>(R.id.layoutButtons)
        val layoutQuestionPrompt = dialogView.findViewById<LinearLayout>(R.id.layoutQuestionPrompt)
        val pbObbProgress = dialogView.findViewById<ProgressBar>(R.id.pbObbProgress)
        val tvProgressStatus = dialogView.findViewById<TextView>(R.id.tvProgressStatus)
        val tvProgressPercent = dialogView.findViewById<TextView>(R.id.tvProgressPercent)

        tvDialogTitle.text = "Game Data (OBB)"
        tvDialogSubtitle.text = "Installing..."
        tvFileCardLabel.text = "SELECTED OBB FILE:"
        tvFileName.text = fileName
        tvFileSize.text = "(Storage Picker)"
        layoutButtons.visibility = View.GONE
        layoutQuestionPrompt.visibility = View.GONE
        layoutProgressSection.visibility = View.VISIBLE
        tvProgressStatus.text = "Copying $fileName into virtual space..."
        tvProgressPercent.text = "0%"

        dialog.show()

        Thread {
            val success = ObbManager.copyUriWithProgress(context, uri, targetFile) { percent, copied, total ->
                activity?.runOnUiThread {
                    pbObbProgress.progress = percent
                    tvProgressPercent.text = "$percent%"
                    val progressMsg = if (total > 0) {
                        "Copying: ${ObbManager.formatFileSize(copied)} / ${ObbManager.formatFileSize(total)}"
                    } else {
                        "Copying: ${ObbManager.formatFileSize(copied)}..."
                    }
                    tvProgressStatus.text = progressMsg
                }
            }

            activity?.runOnUiThread {
                dialog.dismiss()
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

    private fun showManageObbDialog() {
        val context = context ?: return
        val installedFiles = ObbManager.getInstalledObbFiles(bgmiPackageName, currentUserId)
        val hasFiles = installedFiles.isNotEmpty()

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dialogView = layoutInflater.inflate(R.layout.dialog_obb_detected, null)
        dialog.setContentView(dialogView)

        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val tvDialogTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val tvDialogSubtitle = dialogView.findViewById<TextView>(R.id.tvDialogSubtitle)
        val tvFileCardLabel = dialogView.findViewById<TextView>(R.id.tvFileCardLabel)
        val tvFileName = dialogView.findViewById<TextView>(R.id.tvFileName)
        val tvFileSize = dialogView.findViewById<TextView>(R.id.tvFileSize)
        val tvQuestionMessage = dialogView.findViewById<TextView>(R.id.tvQuestionMessage)
        val btnPrimaryAction = dialogView.findViewById<LinearLayout>(R.id.btnPrimaryAction)
        val tvPrimaryBtnText = dialogView.findViewById<TextView>(R.id.tvPrimaryBtnText)
        val btnDialogCancel = dialogView.findViewById<FrameLayout>(R.id.btnDialogCancel)
        val btnDialogSecondary = dialogView.findViewById<FrameLayout>(R.id.btnDialogSecondary)
        val tvSecondaryText = dialogView.findViewById<TextView>(R.id.tvSecondaryText)

        tvDialogTitle.text = "Game Data (OBB)"
        tvDialogSubtitle.text = if (hasFiles) "Installed" else "Missing"
        tvFileCardLabel.text = "VIRTUAL STORAGE:"
        if (hasFiles) {
            val f = installedFiles.first()
            tvFileName.text = f.name
            tvFileSize.text = "(${ObbManager.formatFileSize(f.length())})"
            tvQuestionMessage.text = "OBB is currently active in Inverness virtual space."
        } else {
            val protectedFile = ObbManager.findProtectedHostObbFile(context, bgmiPackageName)
            if (protectedFile != null) {
                tvFileName.text = protectedFile.name
                val sizeStr = ObbManager.formatFileSize(protectedFile.length())
                tvFileSize.text = "($sizeStr • Protected on Device)"
                tvQuestionMessage.text = "Detected in /Android/obb. Tap AUTO-SCAN or SELECT to copy."
            } else {
                tvFileName.text = "No separate OBB installed"
                tvFileSize.text = "(This version may include data in its APK)"
                tvQuestionMessage.text = "Select an OBB only if BGMI asks for one."
            }
        }

        tvPrimaryBtnText.text = "SELECT OBB FILE"
        tvSecondaryText.text = "AUTO-SCAN"

        btnPrimaryAction.setOnClickListener {
            dialog.dismiss()
            openObbPicker()
        }

        btnDialogCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnDialogSecondary.setOnClickListener {
            dialog.dismiss()
            scanAndNotifyObb()
        }

        dialog.show()
    }

    private fun scanAndNotifyObb() {
        if (!hasHostObbAccess()) {
            requestHostObbAccess()
            return
        }
        val found = ObbManager.findHostObbFiles(requireContext(), bgmiPackageName)
        if (found.isNotEmpty()) {
            promptAutoCopyObbAndLaunch(found.first())
        } else {
            openTelegramObbSource()
        }
    }

    private fun hasHostObbAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> android.os.Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            requireContext().checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    private fun requestHostObbAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MaterialDialog(requireContext()).show {
                title(text = "Storage access required")
                message(text = "Allow All files access so Inverness can copy BGMI's original OBB into virtual storage.")
                positiveButton(text = "Open Settings") {
                    allFilesAccessLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${requireContext().packageName}")
                        }
                    )
                }
                negativeButton(res = R.string.cancel)
            }
        } else {
            readStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
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
        val context = context ?: return
        val isInstalled = try {
            BlackBoxCore.get().isInstalled(bgmiPackageName, currentUserId)
        } catch (t: Throwable) {
            false
        }

        if (!isInstalled) {
            checkAndPromptGameInstall()
            return
        }

        val popupView = layoutInflater.inflate(R.layout.popup_game_menu, null)
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true
        popupWindow.elevation = 20f
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        popupView.findViewById<View>(R.id.menuItemLaunch).setOnClickListener {
            popupWindow.dismiss()
            launchBgmi()
        }

        popupView.findViewById<View>(R.id.menuItemManageObb).setOnClickListener {
            popupWindow.dismiss()
            showManageObbDialog()
        }

        popupView.findViewById<View>(R.id.menuItemShortcut).setOnClickListener {
            popupWindow.dismiss()
            createBgmiShortcut()
        }

        popupView.findViewById<View>(R.id.menuItemClearData).setOnClickListener {
            popupWindow.dismiss()
            MaterialDialog(requireContext()).show {
                title(text = "Guest Reset")
                message(text = "Reset selected virtual BGMI data? The installed OBB will be kept.")
                positiveButton(R.string.done) {
                val appContext = requireContext().applicationContext
                    Thread {
                        BlackBoxCore.get().stopPackage(bgmiPackageName, currentUserId)
                    GuestResetter.stopGuestProcess(appContext)
                        Thread.sleep(500)
                        val reset = GuestResetter.reset(bgmiPackageName, currentUserId)
                        activity?.runOnUiThread {
                            toast(if (reset) "Virtual guest data reset" else "Some guest data could not be reset")
                            updateUI()
                        }
                    }.start()
                }
                negativeButton(R.string.cancel)
            }
        }

        popupView.findViewById<View>(R.id.menuItemUninstall).setOnClickListener {
            popupWindow.dismiss()
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

        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val xOffset = anchor.width - popupView.measuredWidth
        popupWindow.showAsDropDown(anchor, xOffset, 12)
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
