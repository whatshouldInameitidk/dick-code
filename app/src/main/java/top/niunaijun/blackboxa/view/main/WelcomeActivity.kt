package top.niunaijun.blackboxa.view.main

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.view.list.ListViewModel

class WelcomeActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val openMainRunnable = Runnable { jump() }
    private var hasOpenedMain = false

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        jump()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)
        previewInstalledAppList()
        animateLogo()
        mainHandler.postDelayed(openMainRunnable, SPLASH_DURATION_MS)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(openMainRunnable)
        super.onDestroy()
    }

    private fun jump() {
        if (hasOpenedMain) return
        hasOpenedMain = true
        mainHandler.removeCallbacks(openMainRunnable)
        AuthActivity.start(this)
        finish()
    }

    private fun animateLogo() {
        viewBindingLogo().apply {
            alpha = 0f
            scaleX = LOGO_START_SCALE
            scaleY = LOGO_START_SCALE
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(LOGO_ANIMATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        findViewById<TextView>(R.id.tv_splash_brand).apply {
            alpha = 0f
            animate()
                .alpha(1f)
                .setStartDelay(BRAND_ANIMATION_DELAY_MS)
                .setDuration(BRAND_ANIMATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun viewBindingLogo() = findViewById<android.widget.ImageView>(R.id.iv_splash_logo)

    private fun previewInstalledAppList(){
        val viewModel = ViewModelProvider(this,InjectionUtil.getListFactory()).get(ListViewModel::class.java)
        viewModel.previewInstalledList()
    }

    private companion object {
        const val SPLASH_DURATION_MS = 700L
        const val LOGO_ANIMATION_MS = 420L
        const val LOGO_START_SCALE = 0.82f
        const val BRAND_ANIMATION_DELAY_MS = 180L
        const val BRAND_ANIMATION_MS = 300L
    }
}
