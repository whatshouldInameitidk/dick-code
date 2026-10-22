package top.niunaijun.blackboxa.view.diagnostic

import android.os.Bundle
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityCrashReportBinding
import top.niunaijun.blackboxa.util.AutoCrashReporter
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.BaseActivity

/**
 * Clean Crash Report settings activity.
 * Provides a single toggle for Automatic Crash Reporting and a test button.
 */
class CrashReportActivity : BaseActivity() {

    private val viewBinding: ActivityCrashReportBinding by inflate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.crash_report, true)

        viewBinding.switchAutoReport.isChecked = AutoCrashReporter.isEnabled()
        updateStatus(viewBinding.switchAutoReport.isChecked)

        viewBinding.switchAutoReport.setOnCheckedChangeListener { _, isChecked ->
            AutoCrashReporter.setEnabled(isChecked)
            updateStatus(isChecked)
            toast(if (isChecked) "Crash reporting enabled" else "Crash reporting disabled")
        }

        viewBinding.btnSendTestReport.setOnClickListener {
            viewBinding.btnSendTestReport.isEnabled = false
            viewBinding.tvStatus.text = "Sending test crash report to @InvXCrashReporterBot..."
            AutoCrashReporter.sendTestReport(this) { success, message ->
                runOnUiThread {
                    viewBinding.btnSendTestReport.isEnabled = true
                    if (success) {
                        viewBinding.tvStatus.text = "✅ Successfully sent test crash report to Telegram!"
                        toast("Test report delivered to bot")
                    } else {
                        viewBinding.tvStatus.text = "❌ Failed to send: $message"
                        toast("Failed: $message")
                    }
                }
            }
        }
    }

    private fun updateStatus(enabled: Boolean) {
        if (enabled) {
            viewBinding.tvStatus.text = "Status: Active (Automatic reporting to @InvXCrashReporterBot enabled)"
        } else {
            viewBinding.tvStatus.text = "Status: Disabled (Crash logs will not be sent)"
        }
    }
}
