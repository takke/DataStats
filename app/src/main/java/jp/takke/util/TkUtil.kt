package jp.takke.util

import android.app.PendingIntent
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

  /**
   * M 以降なら PendingIntent.FLAG_IMMUTABLE を返す
   */
  fun getPendingIntentImmutableFlagIfOverM(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      PendingIntent.FLAG_IMMUTABLE
    } else {
      0
    }
  }
}