package top.niunaijun.blackbox.utils.compat;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.utils.Slog;

/**
 * The container needs the hidden api of the framework, the platform blocks the access
 * to it for apps that are not exempt.
 *
 * The old trick (write a dex into the code cache, load it into the boot class path and
 * call VMRuntime.setHiddenApiExemptions from there) died on android 14, a writable dex
 * file is refused there. The exemption is set directly here, the result is logged
 * because it decides whether the container works at all on the device.
 */
public class HiddenApiCompat {

    private static final String TAG = "HiddenApi";

    /**
     * Sets the exemption for the whole framework ("L" is the prefix of every class).
     *
     * @return true when the platform accepted the exemption
     */
    public static boolean exemptAll() {
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            if (runtime == null) {
                Slog.e(TAG, "VMRuntime.getRuntime() returned null");
                return false;
            }
            Method setHiddenApiExemptions =
                    vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setHiddenApiExemptions.setAccessible(true);
            setHiddenApiExemptions.invoke(runtime, (Object) new String[]{"L"});
            Slog.i(TAG, "hidden api exemption granted");
            return true;
        } catch (Throwable t) {
            Slog.e(TAG, "hidden api exemption failed: " + t);
            return false;
        }
    }
}
