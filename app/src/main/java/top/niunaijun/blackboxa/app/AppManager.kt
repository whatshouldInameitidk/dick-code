package top.niunaijun.blackboxa.app

import android.content.Context
import android.content.SharedPreferences
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.util.CrashRecorder
import top.niunaijun.blackboxa.view.main.BlackBoxLoader

object AppManager {
    @JvmStatic
    val mBlackBoxLoader by lazy {
        BlackBoxLoader()
    }

    @JvmStatic
    val mBlackBoxCore by lazy {
        mBlackBoxLoader.getBlackBoxCore()
    }

    @JvmStatic
    val mRemarkSharedPreferences: SharedPreferences by lazy {
        App.getContext().getSharedPreferences("UserRemark",Context.MODE_PRIVATE)
    }

    fun doAttachBaseContext(context: Context) {
        try {
            mBlackBoxLoader.attachBaseContext(context)
            mBlackBoxLoader.addLifecycleCallback()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun doOnCreate(context: Context) {
        mBlackBoxLoader.doOnCreate(context)
        initThirdService(context)
        // the crashes of the host and of the apps inside the container are written to a file,
        // the crash report screen reads it
        CrashRecorder.install()
        BlackBoxCore.get().setExceptionHandler { thread, throwable ->
            CrashRecorder.record(thread, throwable)
            top.niunaijun.blackboxa.util.AutoCrashReporter.onCrash(thread, throwable, "Virtual App / Game Crash", syncWait = true)
        }
        BlackBoxCore.get().addProcessDeathListener { record, isManual ->
            if (!isManual) {
                top.niunaijun.blackboxa.util.AutoCrashReporter.onProcessDied(record.processName, record.packageName, record.pid)
            }
        }
        top.niunaijun.blackboxa.util.AutoCrashReporter.checkAndSendPending(context)
    }

    private fun initThirdService(context: Context) {}
}
