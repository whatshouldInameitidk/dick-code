package top.niunaijun.blackboxa.view.diagnostic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityCrashReportBinding
import top.niunaijun.blackboxa.util.CrashRecorder
import top.niunaijun.blackboxa.util.CrashReportCollector
import top.niunaijun.blackboxa.util.TelegramReporter
import top.niunaijun.blackboxa.util.getString
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.BaseActivity
import java.io.File

/**
 * Collects the crash log of the container and sends it to the telegram channel.
 */
class CrashReportActivity : BaseActivity() {

    private val viewBinding: ActivityCrashReportBinding by inflate()

    private var reportFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.crash_report, true)

        viewBinding.etToken.setText(TelegramReporter.token())

        viewBinding.btnCollect.setOnClickListener { collect() }
        viewBinding.btnSend.setOnClickListener { send() }
        viewBinding.btnOpenChannel.setOnClickListener { openChannel() }
        viewBinding.btnClear.setOnClickListener {
            CrashRecorder.clear()
            viewBinding.tvReport.text = ""
            reportFile = null
            viewBinding.tvStatus.setText(R.string.crash_cleared)
        }
    }

    private fun collect() {
        viewBinding.btnCollect.isEnabled = false
        viewBinding.tvStatus.setText(R.string.crash_collecting)
        Thread {
            val text = CrashReportCollector.collect()
            val file = File(filesDir, "blackbox_report_${System.currentTimeMillis()}.txt")
            runCatching { file.writeText(text) }
            // only the newest report is kept, the older ones are junk
            runCatching {
                filesDir.listFiles { f -> f.name.startsWith("blackbox_report_") }
                    ?.filter { it != file }
                    ?.forEach { it.delete() }
            }
            runOnUiThread {
                viewBinding.btnCollect.isEnabled = true
                viewBinding.tvReport.text = text.take(MAX_PREVIEW)
                reportFile = file
                viewBinding.tvStatus.text = getString(R.string.crash_collected, file.length())
            }
        }.start()
    }

    private fun send() {
        val token = viewBinding.etToken.text.toString().trim().ifBlank {
            TelegramReporter.DEFAULT_TOKEN
        }
        viewBinding.etToken.setText(token)
        TelegramReporter.saveToken(token)

        val file = reportFile
        if (file == null || !file.exists()) {
            viewBinding.tvStatus.setText(R.string.crash_collect_first)
            return
        }

        viewBinding.btnSend.isEnabled = false
        viewBinding.tvStatus.setText(R.string.crash_sending)
        Thread {
            val result = TelegramReporter.sendToOwner(
                token,
                file,
                "BlackBox crash report ${file.name}"
            )
            runOnUiThread {
                viewBinding.btnSend.isEnabled = true
                viewBinding.tvStatus.text = result.message
                if (result.success) toast(R.string.crash_sent)
            }
        }.start()
    }

    private fun openChannel() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TelegramReporter.BOT_URL))
        startActivity(intent)
    }

    companion object {
        private const val MAX_PREVIEW = 60_000
    }
}
