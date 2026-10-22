package top.niunaijun.blackboxa.view.setting

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.util.AutoCrashReporter
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.diagnostic.CrashReportActivity
import top.niunaijun.blackboxa.view.gms.GmsManagerActivity
import top.niunaijun.blackboxa.view.xp.XpActivity

class SettingFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_setting, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val crashSwitch = view.findViewById<SwitchCompat>(R.id.switch_crash_reporting)
        val xposedSwitch = view.findViewById<SwitchCompat>(R.id.switch_xposed)
        val hideXposedSwitch = view.findViewById<SwitchCompat>(R.id.switch_hide_xposed)
        val hideRootSwitch = view.findViewById<SwitchCompat>(R.id.switch_hide_root)
        val daemonSwitch = view.findViewById<SwitchCompat>(R.id.switch_daemon)
        val moduleRow = view.findViewById<LinearLayout>(R.id.row_module_manager)
        val gmsRow = view.findViewById<LinearLayout>(R.id.row_gms_manager)

        crashSwitch.isChecked = AutoCrashReporter.isEnabled()
        crashSwitch.setOnCheckedChangeListener { _, checked -> AutoCrashReporter.setEnabled(checked) }

        xposedSwitch.isChecked = BlackBoxCore.get().isXPEnable
        moduleRow.isEnabled = xposedSwitch.isChecked
        moduleRow.alpha = if (xposedSwitch.isChecked) 1f else 0.45f
        xposedSwitch.setOnCheckedChangeListener { _, checked ->
            BlackBoxCore.get().isXPEnable = checked
            moduleRow.isEnabled = checked
            moduleRow.alpha = if (checked) 1f else 0.45f
        }
        moduleRow.setOnClickListener { startActivity(Intent(requireContext(), XpActivity::class.java)) }

        hideXposedSwitch.isChecked = AppManager.mBlackBoxLoader.hideXposed()
        hideRootSwitch.isChecked = AppManager.mBlackBoxLoader.hideRoot()
        daemonSwitch.isChecked = AppManager.mBlackBoxLoader.daemonEnable()
        hideXposedSwitch.setOnCheckedChangeListener { _, checked -> restartRequired { AppManager.mBlackBoxLoader.invalidHideXposed(checked) } }
        hideRootSwitch.setOnCheckedChangeListener { _, checked -> restartRequired { AppManager.mBlackBoxLoader.invalidHideRoot(checked) } }
        daemonSwitch.setOnCheckedChangeListener { _, checked -> restartRequired { AppManager.mBlackBoxLoader.invalidDaemonEnable(checked) } }

        if (BlackBoxCore.get().isSupportGms) {
            gmsRow.setOnClickListener { GmsManagerActivity.start(requireContext()) }
        } else {
            gmsRow.alpha = 0.45f
            gmsRow.isEnabled = false
        }
    }

    private fun restartRequired(change: () -> Unit) {
        change()
        toast(R.string.restart_module)
    }
}
