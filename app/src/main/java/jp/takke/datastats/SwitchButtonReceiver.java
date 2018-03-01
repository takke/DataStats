package jp.takke.datastats;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import jp.takke.util.MyLog;

/**
 * 通知のカスタムボタンからの押下イベントレシーバー
 */
public class SwitchButtonReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        final String action = intent != null ? intent.getAction() : null;
        MyLog.d("SwitchButtonReceiver.onReceive [" + action + "]");


        // action を引き継いで LayerService.onStartCommand に投げる
        final Intent serviceIntent = new Intent(context, LayerService.class);
        serviceIntent.setAction(action);
        context.startService(serviceIntent);
    }
}
