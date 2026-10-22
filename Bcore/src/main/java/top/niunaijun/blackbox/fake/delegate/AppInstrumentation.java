package top.niunaijun.blackbox.fake.delegate;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Field;

import black.android.app.BRActivity;
import black.android.app.BRActivityThread;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.fake.service.IActivityClientProxy;
import top.niunaijun.blackbox.utils.HackAppUtils;
import top.niunaijun.blackbox.utils.compat.ActivityCompat;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

public final class AppInstrumentation extends BaseInstrumentationDelegate implements IInjectHook {

    private static final String TAG = AppInstrumentation.class.getSimpleName();

    private static AppInstrumentation sAppInstrumentation;

    public static AppInstrumentation get() {
        if (sAppInstrumentation == null) {
            synchronized (AppInstrumentation.class) {
                if (sAppInstrumentation == null) {
                    sAppInstrumentation = new AppInstrumentation();
                }
            }
        }
        return sAppInstrumentation;
    }

    public AppInstrumentation() {
    }

    @Override
    public void injectHook() {
        try {
            Instrumentation mInstrumentation = getCurrInstrumentation();
            if (mInstrumentation == this || checkInstrumentation(mInstrumentation))
                return;
            mBaseInstrumentation = (Instrumentation) mInstrumentation;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInstrumentation(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Instrumentation getCurrInstrumentation() {
        Object currentActivityThread = BlackBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mInstrumentation();
    }

    @Override
    public boolean isBadEnv() {
        return !checkInstrumentation(getCurrInstrumentation());
    }

    private boolean checkInstrumentation(Instrumentation instrumentation) {
        if (instrumentation instanceof AppInstrumentation) {
            return true;
        }
        Class<?> clazz = instrumentation.getClass();
        if (Instrumentation.class.equals(clazz)) {
            return false;
        }
        do {
            assert clazz != null;
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                if (Instrumentation.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        Object obj = field.get(instrumentation);
                        if ((obj instanceof AppInstrumentation)) {
                            return true;
                        }
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        } while (!Instrumentation.class.equals(clazz));
        return false;
    }

    private void checkHCallback() {
        HookManager.get().checkEnv(HCallbackProxy.class);
    }

    private void checkActivity(Activity activity) {
        Log.d(TAG, "callActivityOnCreate: " + activity.getClass().getName());
        HackAppUtils.enableQQLogOutput(activity.getPackageName(), activity.getClassLoader());
        checkHCallback();
        HookManager.get().checkEnv(IActivityClientProxy.class);
        ActivityInfo info = BRActivity.get(activity).mActivityInfo();
        ContextCompat.fix(activity);
        ActivityCompat.fix(activity);
        if (info.theme != 0) {
            activity.getTheme().applyStyle(info.theme, true);
        }
        ActivityManagerCompat.setActivityOrientation(activity, info.screenOrientation);
    }

    @Override
    public Application newApplication(ClassLoader cl, String className, Context context) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ContextCompat.fix(context);
        BActivityThread.currentActivityThread().loadXposed(context);
        return super.newApplication(cl, className, context);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle, persistentState);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle);
    }

    @Override
    public void callApplicationOnCreate(Application app) {
        checkHCallback();
        super.callApplicationOnCreate(app);
    }

    public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            return super.newActivity(cl, className, intent);
        } catch (ClassNotFoundException e) {
            return mBaseInstrumentation.newActivity(cl, className, intent);
        }
    }

    @Override
    public ActivityResult execStartActivity(Context context, IBinder binder, IBinder binder1, Activity activity, Intent intent, int i, Bundle bundle) throws Throwable {
        if (handleExecStartActivity(intent, binder1, null, i, bundle)) {
            return null;
        }
        return super.execStartActivity(context, binder, binder1, activity, intent, i, bundle);
    }

    @Override
    public ActivityResult execStartActivity(Context context, IBinder binder, IBinder binder1, String str, Intent intent, int i, Bundle bundle) throws Throwable {
        if (handleExecStartActivity(intent, binder1, str, i, bundle)) {
            return null;
        }
        return super.execStartActivity(context, binder, binder1, str, intent, i, bundle);
    }

    @Override
    public ActivityResult execStartActivity(Context context, IBinder binder, IBinder binder1, Fragment fragment, Intent intent, int i) throws Throwable {
        if (handleExecStartActivity(intent, binder1, null, i, null)) {
            return null;
        }
        return super.execStartActivity(context, binder, binder1, fragment, intent, i);
    }

    @Override
    public ActivityResult execStartActivity(Context context, IBinder binder, IBinder binder1, Activity activity, Intent intent, int i) throws Throwable {
        if (handleExecStartActivity(intent, binder1, null, i, null)) {
            return null;
        }
        return super.execStartActivity(context, binder, binder1, activity, intent, i);
    }

    @Override
    public ActivityResult execStartActivity(Context context, IBinder binder, IBinder binder1, Fragment fragment, Intent intent, int i, Bundle bundle) throws Throwable {
        if (handleExecStartActivity(intent, binder1, null, i, bundle)) {
            return null;
        }
        return super.execStartActivity(context, binder, binder1, fragment, intent, i, bundle);
    }

    @Override
    public ActivityResult execStartActivity(Context context, IBinder iBinder, IBinder iBinder2, Activity activity, Intent intent, int i, Bundle bundle, UserHandle userHandle) throws Throwable {
        if (handleExecStartActivity(intent, iBinder2, null, i, bundle)) {
            return null;
        }
        return super.execStartActivity(context, iBinder, iBinder2, activity, intent, i, bundle, userHandle);
    }

    private boolean handleExecStartActivity(Intent intent, IBinder token, String resultWho, int requestCode, Bundle options) {
        if (intent == null) {
            return false;
        }

        // Don't intercept if intent is already targeting our own ProxyActivity stub
        ComponentName component = intent.getComponent();
        if (component != null && component.getPackageName().equals(BlackBoxCore.getHostPkg())) {
            return false;
        }

        int userId = BActivityThread.getUserId();
        ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveActivity(
                intent,
                PackageManager.GET_META_DATA,
                null,
                userId);
        if (resolveInfo == null) {
            String origPackage = intent.getPackage();
            if (intent.getPackage() == null && intent.getComponent() == null) {
                intent.setPackage(BActivityThread.getAppPackageName());
            }
            resolveInfo = BlackBoxCore.getBPackageManager().resolveActivity(
                    intent,
                    PackageManager.GET_META_DATA,
                    null,
                    userId);
            if (resolveInfo == null) {
                intent.setPackage(origPackage);
                return false;
            }
        }

        ClassLoader cl = BActivityThread.getApplication() != null
                ? BActivityThread.getApplication().getClassLoader()
                : getClass().getClassLoader();
        intent.setExtrasClassLoader(cl);
        intent.setComponent(new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name));

        Log.d(TAG, "execStartActivity intercepted container activity: " + resolveInfo.activityInfo.name);
        try {
            BlackBoxCore.getBActivityManager().startActivityAms(
                    userId,
                    intent,
                    null,
                    token,
                    resultWho,
                    requestCode,
                    0,
                    options);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "execStartActivity failed to route to BlackBox AMS", t);
            return false;
        }
    }
}
