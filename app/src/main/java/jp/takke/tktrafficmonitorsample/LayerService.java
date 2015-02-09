package jp.takke.tktrafficmonitorsample;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.TrafficStats;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import jp.takke.util.MyLog;

public class LayerService extends Service {
    View view;
    WindowManager wm;


    private int mIntervalMs = 2000;

    private boolean mSleeping = false;

    private boolean mServiceAlive = true;
    private long mLastRxBytes = 0;
    private long mLastTxBytes = 0;

    private long mDiffRxBytes = 0;
    private long mDiffTxBytes = 0;

    private long mLastTime = System.currentTimeMillis();
    private long mElapsedMs = mIntervalMs;


    /**
     * スリープ状態(SCREEN_ON/OFF)の検出用レシーバ
     */
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {

                MyLog.d("screen on");

                // 停止していれば再開する
                mSleeping = false;

                // 次の onStart を呼ぶ
                scheduleNextTime(mIntervalMs);

            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {

                MyLog.d("screen off");

                // 停止する
                mSleeping = true;

                // アラーム停止
                stopAlarm();
            }
        }
    };


    @SuppressWarnings("deprecation")
    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);

//        MyLog.d("LayerService.onStart");

        execTask();
    }


    private void execTask() {

        MyLog.d("LayerService.execTask");

        gatherTraffic();

        showTraffic();


        // 次回の実行予約
        scheduleNextTime(mIntervalMs);
    }


    private void showTraffic() {

        final long rxKb = mDiffRxBytes / 1024 * 1000 / mElapsedMs;  // KB/s
        long rxD1Kb = mDiffRxBytes % 1024;    // [0, 1023]
        // to [0, 9]
        if (rxD1Kb >= 900) rxD1Kb = 9;
        else if (rxD1Kb == 0) rxD1Kb = 0;
        else if (rxD1Kb <= 100) rxD1Kb = 1;
        else rxD1Kb = rxD1Kb / 100;

        final long txKb = mDiffTxBytes / 1024 * 1000 / mElapsedMs;  // KB/s
        long txD1Kb = mDiffTxBytes % 1024;    // [0, 1023]
        // to [0, 9]
        if (txD1Kb >= 900) txD1Kb = 9;
        else if (txD1Kb == 0) txD1Kb = 0;
        else if (txD1Kb <= 100) txD1Kb = 1;
        else txD1Kb = txD1Kb / 100;

        final TextView upload = (TextView) view.findViewById(R.id.upload_text_view);
        upload.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        final String u = pad(txKb) + txKb + "." + txD1Kb + "KB/s";
        upload.setText(u);
        upload.setTextColor(getTextColorByKb(txKb));

        final TextView download = (TextView) view.findViewById(R.id.download_text_view);
        download.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        final String d = pad(rxKb) + rxKb + "." + rxD1Kb + "KB/s";
        download.setText(d);
        download.setTextColor(getTextColorByKb(rxKb));

//        MyLog.d("LayerService.showTraffic: U: " + u + ", D:" + d + ", elapsed[" + mElapsedMs + "]");
    }


    private int getTextColorByKb(long kb) {

        if (kb < 10) {
            return getResources().getColor(R.color.textColorLow);
        }
        if (kb < 100) {
            return getResources().getColor(R.color.textColorMiddle);
        }
        return getResources().getColor(R.color.textColorHigh);
    }



    private String pad(long kb) {

        if (kb < 10) {
            return "    ";
        }
        if (kb < 100) {
            return "   ";
        }
        if (kb < 1000) {
            return "  ";
        }
        if (kb < 10000) {
            return " ";
        }
        return "";
    }


    private void gatherTraffic() {

        final long totalRxBytes = TrafficStats.getTotalRxBytes();
        final long totalTxBytes = TrafficStats.getTotalTxBytes();

        if (mLastRxBytes > 0) {
            mDiffRxBytes = totalRxBytes - mLastRxBytes;
        }
        if (mLastTxBytes > 0) {
            mDiffTxBytes = totalTxBytes - mLastTxBytes;
        }

        final long now = System.currentTimeMillis();
        mElapsedMs = now - mLastTime;
        if (mElapsedMs == 0) {  // prohibit div by zero
            mElapsedMs = mIntervalMs;
        }
        mLastTime = now;

        mLastRxBytes = totalRxBytes;
        mLastTxBytes = totalTxBytes;
    }


    private void scheduleNextTime(int intervalMs) {

        // サービス終了の指示が出ていたら，次回の予約はしない。
        if (!mServiceAlive) {
            return;
        }
        if (mSleeping) {
            return;
        }

        final long now = System.currentTimeMillis();

        // アラームをセット
        final Intent intent = new Intent(this, this.getClass());
        final PendingIntent alarmSender = PendingIntent.getService(
                this,
                0,
                intent,
                0
        );
        // ※onStartが呼ばれるように設定する

        final AlarmManager am = (AlarmManager) this.getSystemService(Context.ALARM_SERVICE);

        am.set(AlarmManager.RTC, now + intervalMs, alarmSender);

//        MyLog.d(" scheduled[" + intervalMs + "]");
    }


    private void stopAlarm() {

        // サービス名を指定
        final Intent intent = new Intent(this, this.getClass());

        // アラームを解除
        final PendingIntent pendingIntent = PendingIntent.getService(
                this,
                0, // ここを-1にすると解除に成功しない
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
        );

        final AlarmManager am = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        am.cancel(pendingIntent);
        // @see http://creadorgranoeste.blogspot.com/2011/06/alarmmanager.html
    }


    @Override
    public void onCreate() {
        super.onCreate();

        MyLog.d("LayerService.onCreate");

        // Viewからインフレータを作成する
        LayoutInflater layoutInflater = LayoutInflater.from(this);

        // 重ね合わせするViewの設定を行う
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_TOAST,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);

        // WindowManagerを取得する
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        // レイアウトファイルから重ね合わせするViewを作成する
        view = layoutInflater.inflate(R.layout.overlay, null);

        // Viewを画面上に重ね合わせする
        wm.addView(view, params);

        // スリープ状態のレシーバ登録
        getApplicationContext().registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        getApplicationContext().registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));

        // 定期処理開始
        scheduleNextTime(mIntervalMs);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        MyLog.d("LayerService.onDestroy");

        mServiceAlive = false;

        // スリープ状態のレシーバ解除
        getApplicationContext().unregisterReceiver(mReceiver);

        // サービスが破棄されるときには重ね合わせしていたViewを削除する
        wm.removeView(view);
    }

    @Override
    public IBinder onBind(Intent intent) {

        MyLog.d("LayerService.onBind");

        return null;
    }
}