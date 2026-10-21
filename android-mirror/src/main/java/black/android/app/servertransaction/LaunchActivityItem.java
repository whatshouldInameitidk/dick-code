package black.android.app.servertransaction;

import android.content.Intent;
import android.content.pm.ActivityInfo;

import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BField;

@BClassName("android.app.servertransaction.LaunchActivityItem")
public interface LaunchActivityItem {
    /**
     * Android 12 (API 31) renamed {@code mInfo} to {@code mActivityInfo}.
     * Both accessors are mirrored so callers can probe the running framework
     * with {@code _check_mInfo()} / {@code _check_mActivityInfo()} instead of
     * guessing from the SDK level.
     */
    @BField
    ActivityInfo mInfo();

    @BField
    ActivityInfo mActivityInfo();

    @BField
    Intent mIntent();
}
