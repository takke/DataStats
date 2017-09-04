package jp.takke.datastats;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

public class OverlayUtil {

    public static boolean checkOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return Settings.canDrawOverlays(context);
    }
}
