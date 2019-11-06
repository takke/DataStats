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
        val serviceIntent = Intent(context, LayerService::class.java)
        serviceIntent.action = action
        context.startService(serviceIntent)
    }
}
