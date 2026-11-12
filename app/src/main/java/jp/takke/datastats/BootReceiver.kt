package jp.takke.datastats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import jp.takke.util.MyLog

/**
 * 端末起動時、およびアプリ更新後にサービスを自動起動するレシーバー。
 *
 * アプリ更新時はプロセスが kill されサービスも再起動されないため、
 * MY_PACKAGE_REPLACED でも起動する(でないと次回の端末再起動かアプリ起動まで止まったままになる)。
 */
class BootReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {

    val action = intent.action
    MyLog.i("BootReceiver.onReceive[$action]")

    if (Intent.ACTION_BOOT_COMPLETED == action || Intent.ACTION_MY_PACKAGE_REPLACED == action) {

      // 自動起動の確認
      val pref = PreferenceManager.getDefaultSharedPreferences(context)
      val startOnBoot = pref.getBoolean(C.PREF_KEY_START_ON_BOOT, true)

      MyLog.i("start on boot[" + (if (startOnBoot) "YES" else "NO") + "]")

      if (startOnBoot) {
        // サービス起動(バックグラウンド開始が拒否されてもクラッシュさせない)
        LayerService.startFromBackground(context)
      }
    }

  }
}
