package jp.takke.datastats

import android.content.Context
import android.os.Build
import android.provider.Settings

object OverlayUtil {

    fun checkOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT < 23) {
            true
        } else Settings.canDrawOverlays(context)
    }
}
