package top.niunaijun.blackbox.utils.compat;

import android.content.Context;
import android.content.ContextWrapper;

import black.android.app.BRContextImpl;
import black.android.app.BRContextImplKitkat;
import black.android.content.AttributionSourceStateContext;
import black.android.content.BRAttributionSource;
import black.android.content.BRAttributionSourceState;
import black.android.content.BRContentResolver;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ContextCompat {
    public static final String TAG = "ContextCompat";

    public static void fixAttributionSourceState(Object obj, int uid) {
        if (obj == null) return;
        boolean fixed = false;
        try {
            if (BRAttributionSource.get(obj)._check_mAttributionSourceState() != null) {
                Object mAttributionSourceState = BRAttributionSource.get(obj).mAttributionSourceState();
                if (mAttributionSourceState != null) {
                    AttributionSourceStateContext attributionSourceStateContext = BRAttributionSourceState.get(mAttributionSourceState);
                    attributionSourceStateContext._set_packageName(BlackBoxCore.getHostPkg());
                    attributionSourceStateContext._set_uid(uid);
                    fixed = true;
                }
            }
        } catch (Throwable ignored) {}

        if (!fixed) {
            try {
                java.lang.reflect.Field stateField = obj.getClass().getDeclaredField("mAttributionSourceState");
                stateField.setAccessible(true);
                Object state = stateField.get(obj);
                if (state != null) {
                    java.lang.reflect.Field uidField = state.getClass().getDeclaredField("uid");
                    uidField.setAccessible(true);
                    uidField.setInt(state, uid);

                    java.lang.reflect.Field pkgField = state.getClass().getDeclaredField("packageName");
                    pkgField.setAccessible(true);
                    pkgField.set(state, BlackBoxCore.getHostPkg());
                }
            } catch (Throwable ignored) {}
        }

        try {
            fixAttributionSourceState(BRAttributionSource.get(obj).getNext(), uid);
        } catch (Throwable ignored) {
            try {
                java.lang.reflect.Method getNextMethod = obj.getClass().getMethod("getNext");
                Object next = getNextMethod.invoke(obj);
                if (next != null && next != obj) {
                    fixAttributionSourceState(next, uid);
                }
            } catch (Throwable ignored2) {}
        }
    }

    public static void fix(Context context) {
        try {
            int deep = 0;
            while (context instanceof ContextWrapper) {
                context = ((ContextWrapper) context).getBaseContext();
                deep++;
                if (deep >= 10) {
                    return;
                }
            }
            BRContextImpl.get(context)._set_mPackageManager(null);
            try {
                context.getPackageManager();
            } catch (Throwable e) {
                e.printStackTrace();
            }

            BRContextImpl.get(context)._set_mBasePackageName(BlackBoxCore.getHostPkg());
            BRContextImplKitkat.get(context)._set_mOpPackageName(BlackBoxCore.getHostPkg());
            BRContentResolver.get(context.getContentResolver())._set_mPackageName(BlackBoxCore.getHostPkg());

            if (BuildCompat.isS()) {
                Object attributionSource = null;
                try {
                    attributionSource = BRContextImpl.get(context).getAttributionSource();
                } catch (Throwable ignored) {
                    try {
                        java.lang.reflect.Method m = context.getClass().getMethod("getAttributionSource");
                        attributionSource = m.invoke(context);
                    } catch (Throwable ignored2) {}
                }
                if (attributionSource != null) {
                    fixAttributionSourceState(attributionSource, BlackBoxCore.getHostUid());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
