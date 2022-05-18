package jp.takke.util

import android.os.Build

object TkUtil {

    // チェック高速化のためのキャッシュ
    private var isEmulatorChecked = false
    private var isEmulatorCache = false

    // 未チェック(未キャッシュ)の場合にのみ実際にチェックする
//  isEmulatorCache = android.os.Build.MODEL.equals("sdk");
    val isEmulator: Boolean
        get() {
            if (!isEmulatorChecked) {
                // 未チェック(未キャッシュ)の場合にのみ実際にチェックする
//			isEmulatorCache = android.os.Build.MODEL.equals("sdk");
                isEmulatorCache = false
                if (Build.DEVICE == "generic") {
                    if (Build.BRAND == "generic") {
                        isEmulatorCache = true
                    }
                }
                isEmulatorChecked = true
            }
            return isEmulatorCache
        }
}