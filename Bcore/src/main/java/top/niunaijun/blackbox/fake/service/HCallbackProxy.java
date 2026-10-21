package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import black.android.app.ActivityThreadActivityClientRecordContext;
import black.android.app.BRActivityClient;
import black.android.app.BRActivityClientActivityClientControllerSingleton;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadCreateServiceData;
import black.android.app.BRActivityThreadH;
import black.android.app.BRIActivityManager;
import black.android.app.servertransaction.BRClientTransaction;
import black.android.app.servertransaction.BRLaunchActivityItem;
import black.android.app.servertransaction.LaunchActivityItemContext;
import black.android.os.BRHandler;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.proxy.record.ProxyActivityRecord;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class HCallbackProxy implements IInjectHook, Handler.Callback {
    public static final String TAG = "HCallbackStub";
    private Handler.Callback mOtherCallback;
    private AtomicBoolean mBeing = new AtomicBoolean(false);

    private Handler.Callback getHCallback() {
        return BRHandler.get(getH()).mCallback();
    }

    private Handler getH() {
        Object currentActivityThread = BlackBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mH();
    }

    @Override
    public void injectHook() {
        mOtherCallback = getHCallback();
        if (mOtherCallback != null && (mOtherCallback == this || mOtherCallback.getClass().getName().equals(this.getClass().getName()))) {
            mOtherCallback = null;
        }
        BRHandler.get(getH())._set_mCallback(this);
    }

    @Override
    public boolean isBadEnv() {
        Handler.Callback hCallback = getHCallback();
        return hCallback != null && hCallback != this;
    }

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        if (!mBeing.getAndSet(true)) {
            try {
                if (BuildCompat.isPie()) {
                    if (isExecuteTransaction(msg)) {
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
                    }
                } else {
                    if (isLaunchActivity(msg)) {
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
                    }
                }
                if (isCreateService(msg)) {
                    return handleCreateService(msg.obj);
                }
                if (mOtherCallback != null) {
                    return mOtherCallback.handleMessage(msg);
                }
                return false;
            } finally {
                mBeing.set(false);
            }
        }
        return false;
    }

    /**
     * The message codes of the framework are hidden api. On android 16 the hidden api
     * enforcement blocks the read of ActivityThread$H.EXECUTE_TRANSACTION, the bridge
     * returned null and the unboxing of that null killed the guest process with a
     * NullPointerException. The code is used when it can be read, when it is blocked
     * the payload of the message tells what the message is.
     */
    private boolean isExecuteTransaction(Message msg) {
        Integer code = messageCode(CODE_EXECUTE_TRANSACTION);
        if (code != null) {
            return code == msg.what;
        }
        Object obj = msg.obj;
        if (obj == null) {
            return false;
        }
        String name = obj.getClass().getName();
        // android 9 - 13: android.app.ClientTransaction
        // android 14 and later: android.app.servertransaction.ClientTransaction
        return "android.app.ClientTransaction".equals(name)
                || "android.app.servertransaction.ClientTransaction".equals(name);
    }

    private boolean isLaunchActivity(Message msg) {
        Integer code = messageCode(CODE_LAUNCH_ACTIVITY);
        if (code != null) {
            return code == msg.what;
        }
        Object obj = msg.obj;
        return obj != null && "android.app.ActivityClientRecord".equals(obj.getClass().getName());
    }

    private boolean isCreateService(Message msg) {
        Integer code = messageCode(CODE_CREATE_SERVICE);
        if (code != null) {
            return code == msg.what;
        }
        Object obj = msg.obj;
        return obj != null && "android.app.CreateServiceData".equals(obj.getClass().getName());
    }

    private Integer messageCode(int which) {
        try {
            if (BuildCompat.isPie()) {
                switch (which) {
                    case CODE_EXECUTE_TRANSACTION:
                        return BRActivityThreadH.get().EXECUTE_TRANSACTION();
                    case CODE_CREATE_SERVICE:
                        return BRActivityThreadH.get().CREATE_SERVICE();
                    default:
                        return BRActivityThreadH.get().LAUNCH_ACTIVITY();
                }
            }
            return BRActivityThreadH.get().LAUNCH_ACTIVITY();
        } catch (Throwable t) {
            Slog.w(TAG, "message code " + which + " is blocked: " + t);
            return null;
        }
    }

    /**
     * The launch item of the transaction. The shape of the transaction changed with
     * android 15: the field {@code mActivityCallbacks} was dropped, the items of the
     * transaction live in {@code mTransactionItems} now. The read of a field that does
     * not exist returns null, so every path has to be checked for null before it is used.
     */
    private Object getLaunchActivityItem(Object clientTransaction) {
        if (clientTransaction == null) {
            return null;
        }
        // android 9 - 14
        Object item = findLaunchItem(readActivityCallbacks(clientTransaction));
        if (item != null) {
            Slog.d(TAG, "the launch item came from mActivityCallbacks");
            return item;
        }
        // android 15 and later
        item = findLaunchItem(readTransactionItems(clientTransaction));
        if (item != null) {
            Slog.d(TAG, "the launch item came from getTransactionItems");
            return item;
        }
        // any other shape of the transaction
        item = findLaunchItemInFields(clientTransaction);
        if (item != null) {
            Slog.d(TAG, "the launch item came from the fields of the transaction");
        }
        return item;
    }

    private List<Object> readActivityCallbacks(Object clientTransaction) {
        try {
            return BRClientTransaction.get(clientTransaction).mActivityCallbacks();
        } catch (Throwable t) {
            Slog.w(TAG, "mActivityCallbacks is not available: " + t);
            return null;
        }
    }

    private List<Object> readTransactionItems(Object clientTransaction) {
        try {
            Method method = clientTransaction.getClass().getMethod("getTransactionItems");
            Object result = method.invoke(clientTransaction);
            if (result instanceof List) {
                return (List<Object>) result;
            }
        } catch (Throwable t) {
            Slog.w(TAG, "getTransactionItems is not available: " + t);
        }
        return null;
    }

    private Object findLaunchItem(List<Object> items) {
        if (items == null) {
            return null;
        }
        for (Object item : items) {
            if (isLaunchActivityItem(item)) {
                return item;
            }
        }
        return null;
    }

    private Object findLaunchItemInFields(Object clientTransaction) {
        try {
            for (Field field : clientTransaction.getClass().getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(clientTransaction);
                if (!(value instanceof List)) {
                    continue;
                }
                Object item = findLaunchItem((List<Object>) value);
                if (item != null) {
                    return item;
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "the fields of the transaction are not readable: " + t);
        }
        return null;
    }

    /**
     * The launch item lives in {@code android.app.LaunchActivityItem} on android 9 - 11
     * and in {@code android.app.servertransaction.LaunchActivityItem} from android 12 on.
     * The simple name is the same on every version.
     */
    private boolean isLaunchActivityItem(Object item) {
        if (item == null) {
            return false;
        }
        return "LaunchActivityItem".equals(item.getClass().getSimpleName());
    }

    private boolean handleLaunchActivity(Object client) {
        Object r;
        if (BuildCompat.isPie()) {
            // ClientTransaction
            r = getLaunchActivityItem(client);
        } else {
            // ActivityClientRecord
            r = client;
        }
        if (r == null)
            return false;

        Intent intent;
        IBinder token;
        if (BuildCompat.isPie()) {
            intent = readIntent(r);
            token = readActivityToken(client);
        } else {
            ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
            intent = clientRecordContext.intent();
            token = clientRecordContext.token();
        }

        if (intent == null)
            return false;

        ProxyActivityRecord stubRecord = ProxyActivityRecord.create(intent);
        ActivityInfo activityInfo = stubRecord.mActivityInfo;
        if (activityInfo != null) {
            if (BActivityThread.getAppConfig() == null) {
                BlackBoxCore.getBActivityManager().restartProcess(activityInfo.packageName, activityInfo.processName, stubRecord.mUserId);

                Intent launchIntentForPackage = BlackBoxCore.getBPackageManager().getLaunchIntentForPackage(activityInfo.packageName, stubRecord.mUserId);
                intent.setExtrasClassLoader(this.getClass().getClassLoader());
                ProxyActivityRecord.saveStub(intent, launchIntentForPackage, stubRecord.mActivityInfo, stubRecord.mActivityRecord, stubRecord.mUserId);
                if (BuildCompat.isPie()) {
                    LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                    launchActivityItemContext._set_mIntent(intent);
                    writeActivityInfo(r, activityInfo);
                } else {
                    ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
                    clientRecordContext._set_intent(intent);
                    clientRecordContext._set_activityInfo(activityInfo);
                }
                return true;
            }
            // bind
            if (!BActivityThread.currentActivityThread().isInit()) {
                BActivityThread.currentActivityThread().bindApplication(activityInfo.packageName,
                        activityInfo.processName);
                return true;
            }

            int taskId = BRIActivityManager.get(BRActivityManagerNative.get().getDefault()).getTaskForActivity(token, false);
            BlackBoxCore.getBActivityManager().onActivityCreated(taskId, token, stubRecord.mActivityRecord);

            if (BuildCompat.isS()) {
                checkActivityClient();
            }

            if (BuildCompat.isPie()) {
                // The framework builds the activity from the launch item, so the guest
                // intent and the guest ActivityInfo have to be written back into it.
                // Android 12 renamed LaunchActivityItem.mInfo -> mActivityInfo: probe the
                // running framework for the field instead of guessing from the SDK level.
                //
                // Writing the record back through ActivityThread.getLaunchingActivity()
                // does not work: that method never existed in the public framework, the
                // reflection call failed silently and the container kept launching its
                // own shadow ProxyActivity, which re-launched the guest intent forever.
                LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                launchActivityItemContext._set_mIntent(stubRecord.mTarget);
                writeActivityInfo(r, activityInfo);
            } else {
                ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
                clientRecordContext._set_intent(stubRecord.mTarget);
                clientRecordContext._set_activityInfo(activityInfo);
            }
        }
        return false;
    }

    private boolean handleCreateService(Object data) {
        if (BActivityThread.getAppConfig() != null) {
            String appPackageName = BActivityThread.getAppPackageName();
            assert appPackageName != null;

            ServiceInfo serviceInfo = BRActivityThreadCreateServiceData.get(data).info();
            if (!serviceInfo.name.equals(ProxyManifest.getProxyService(BActivityThread.getAppPid()))
                    && !serviceInfo.name.equals(ProxyManifest.getProxyJobService(BActivityThread.getAppPid()))) {
                Slog.d(TAG, "handleCreateService: " + data);
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(appPackageName, serviceInfo.name));
                BlackBoxCore.getBActivityManager().startService(intent, null, false, BActivityThread.getUserId());
                return true;
            }
        }
        return false;
    }

    /**
     * The intent of the launch item. The bridge knows the class of the item of the
     * running framework, a plain read of the field is the fallback for the versions
     * the bridge does not know.
     */
    private Intent readIntent(Object launchItem) {
        try {
            Intent intent = BRLaunchActivityItem.get(launchItem).mIntent();
            if (intent != null) {
                return intent;
            }
        } catch (Throwable t) {
            Slog.w(TAG, "mIntent through the bridge failed: " + t);
        }
        Object intent = readField(launchItem, "mIntent");
        return intent instanceof Intent ? (Intent) intent : null;
    }

    private IBinder readActivityToken(Object clientTransaction) {
        try {
            IBinder token = BRClientTransaction.get(clientTransaction).mActivityToken();
            if (token != null) {
                return token;
            }
        } catch (Throwable t) {
            Slog.w(TAG, "mActivityToken through the bridge failed: " + t);
        }
        Object token = readField(clientTransaction, "mActivityToken");
        return token instanceof IBinder ? (IBinder) token : null;
    }

    /**
     * The activity info of the launch item. Android 12 renamed {@code mInfo} to
     * {@code mActivityInfo}, android 15 has {@code mInfo} again.
     */
    private void writeActivityInfo(Object launchItem, ActivityInfo activityInfo) {
        try {
            LaunchActivityItemContext context = BRLaunchActivityItem.get(launchItem);
            if (context._check_mActivityInfo() != null) {
                context._set_mActivityInfo(activityInfo);
                return;
            }
            if (context._check_mInfo() != null) {
                context._set_mInfo(activityInfo);
                return;
            }
        } catch (Throwable t) {
            Slog.w(TAG, "the activity info through the bridge failed: " + t);
        }
        writeField(launchItem, "mActivityInfo", activityInfo);
        writeField(launchItem, "mInfo", activityInfo);
    }

    private Object readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable t) {
            Slog.w(TAG, "the field " + name + " is not readable: " + t);
            return null;
        }
    }

    private void writeField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Throwable t) {
            Slog.w(TAG, "the field " + name + " is not writable: " + t);
        }
    }

    private void checkActivityClient() {        try {
            Object activityClientController = BRActivityClient.get().getActivityClientController();
            if (!(activityClientController instanceof Proxy)) {
                IActivityClientProxy iActivityClientProxy = new IActivityClientProxy(activityClientController);
                iActivityClientProxy.onlyProxy(true);
                iActivityClientProxy.injectHook();
                Object instance = BRActivityClient.get().getInstance();
                Object o = BRActivityClient.get(instance).INTERFACE_SINGLETON();
                BRActivityClientActivityClientControllerSingleton.get(o)._set_mKnownInstance(iActivityClientProxy.getProxyInvocation());
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static final int CODE_EXECUTE_TRANSACTION = 0;
    private static final int CODE_CREATE_SERVICE = 1;
    private static final int CODE_LAUNCH_ACTIVITY = 2;
}
