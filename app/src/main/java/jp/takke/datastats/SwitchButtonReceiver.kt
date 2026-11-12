package jp.takke.datastats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

import jp.takke.util.MyLog

/**
 * 通知のカスタムボタンからの押下イベントレシーバー
 */
class SwitchButtonReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {

    val action = intent?.action
    MyLog.d("SwitchButtonReceiver.onReceive [$action]")


    // action を引き継いで LayerService.onStartCommand に投げる
    // startService() だとサービス停止直後のタイミング等でバックグラウンド起動制限に触れ
    // IllegalStateException が発生し得るため startForegroundService を優先する
    // (拒否された場合のフォールバックは startFromBackground 側で行う)。
    LayerService.startFromBackground(context, action)
  }
}
