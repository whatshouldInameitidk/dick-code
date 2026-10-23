package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.auth.AuthSession
import top.niunaijun.blackboxa.auth.KeyAuthClient
import top.niunaijun.blackboxa.auth.SecureSessionStore
import top.niunaijun.blackboxa.util.AppUpdater

class AuthActivity : AppCompatActivity() {
    private lateinit var licenseInput: EditText
    private lateinit var unlockButton: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AuthSession.isAuthorized) {
            openMain()
            return
        }
        setContentView(R.layout.activity_auth)
        licenseInput = findViewById(R.id.et_license_key)
        unlockButton = findViewById(R.id.btn_unlock)
        progress = findViewById(R.id.pb_auth)
        status = findViewById(R.id.tv_auth_status)
        unlockButton.setOnClickListener { authorize(licenseInput.text?.toString().orEmpty()) }
        status.setOnClickListener {
            AppUpdater.showUpdatePrompt(this@AuthActivity, forceUpdate = true)
        }

        // Proactively check for newer APK version on login screen
        AppUpdater.checkForUpdate(this)

        SecureSessionStore.licenseKey(this)?.let {
            licenseInput.setText(it)
            authorize(it)
        }
    }

    private fun authorize(rawLicenseKey: String) {
        val licenseKey = rawLicenseKey.trim()
        if (licenseKey.isEmpty()) {
            licenseInput.error = getString(R.string.auth_key_required)
            return
        }
        setLoading(true, getString(R.string.auth_verifying))
        lifecycleScope.launch {
            when (val result = KeyAuthClient.authorize(licenseKey, SecureSessionStore.deviceId(this@AuthActivity))) {
                is KeyAuthClient.Result.Authorized -> {
                    SecureSessionStore.saveLicenseKey(this@AuthActivity, licenseKey)
                    SecureSessionStore.saveLicenseExpiry(this@AuthActivity, result.expiryEpochSeconds)
                    AuthSession.authorize()
                    openMain()
                }
                is KeyAuthClient.Result.UpdateRequired -> {
                    setLoading(false, "Update required: ${result.message}")
                    AppUpdater.showUpdatePrompt(this@AuthActivity, result.latestVersion, forceUpdate = true)
                }
                is KeyAuthClient.Result.Rejected -> {
                    if (result.message.contains("invalidver", ignoreCase = true) ||
                        result.message.contains("version", ignoreCase = true)
                    ) {
                        setLoading(false, "New version available! Tap here to update.")
                        AppUpdater.showUpdatePrompt(this@AuthActivity, forceUpdate = true)
                    } else {
                        SecureSessionStore.clearLicenseKey(this@AuthActivity)
                        setLoading(false, result.message)
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean, message: String) {
        unlockButton.isEnabled = !loading
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        status.text = message
    }

    private fun openMain() {
        MainActivity.start(this)
        finish()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, AuthActivity::class.java))
        }
    }
}
