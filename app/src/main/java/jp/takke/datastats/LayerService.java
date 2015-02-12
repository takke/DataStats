package jp.takke.datastats;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.TrafficStats;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import android.widget.TextView;

import jp.takke.util.MyLog;

public class LayerService extends Service implements View.OnAttachStateChangeListener {

    public class LocalBinder extends ILayerService.Stub {

        @Override
        public void restart() throws RemoteException {

            MyLog.d("LayerService.restart");

            mSnapshot = false;

            loadPreferences();

            execTask();
        }

        @Override
        public void stop() throws RemoteException {

            stopSelf();
        }

        @Override
        public void startSnapshot(long previewBytes) throws RemoteException {

            mSnapshot = true;
            mSnapshotBytes = previewBytes;
            MyLog.d("LayerService.startSnapshot " +
                    "bytes[" + mSnapshotBytes + "]");

            execTask();
        }
    }


    private LocalBinder mBinder = new LocalBinder();

    private View mView;
    private WindowManager mWindowManager;
    private boolean mAttached = false;

    private int mXPos = 90;  // [0, 100]

    private int mBarMaxKB = 100;

    private int mIntervalMs = 1000;

    private boolean mSleeping = false;

    private boolean mServiceAlive = true;
    private long mLastRxBytes = 0;
    private long mLastTxBytes = 0;

    private long mDiffRxBytes = 0;
    private long mDiffTxBytes = 0;

    private long mLastTime = System.currentTimeMillis();
    private long mElapsedMs = mIntervalMs;

//    private long mLastCommandStarted = 0;



    // SNAPSHOTモードの送受信データ
    private boolean mSnapshot = false;
    private long mSnapshotBytes = 0;


    /**
     * スリープ状態(SCREEN_ON/OFF)の検出用レシーバ
     */
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {

                MyLog.d("LayerService: screen on");

                // 停止していれば再開する
                mSleeping = false;

                // 次の onStart を呼ぶ
                scheduleNextTime(mIntervalMs);

            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {

                MyLog.d("LayerService: screen off");

                // 停止する
                mSleeping = true;

                // アラーム停止
                stopAlarm();
            }
        }
    };


    @Override
    public IBinder onBind(Intent intent) {

        MyLog.d("LayerService.onBind");

        execTask();

        return mBinder;
    }


    @Override
    public boolean onUnbind(Intent intent) {

        MyLog.d("LayerService.onUnbind");

        return super.onUnbind(intent);
    }


    @Override
    public void onRebind(Intent intent) {

        MyLog.d("LayerService.onRebind");

        super.onRebind(intent);
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
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        // WindowManagerを取得する
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        // レイアウトファイルから重ね合わせするViewを作成する
        mView = layoutInflater.inflate(R.layout.overlay, null);

        // Viewを画面上に重ね合わせする
        mWindowManager.addView(mView, params);

        // スリープ状態のレシーバ登録
        getApplicationContext().registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        getApplicationContext().registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));


        // attach されるまでサイズ不明
        mView.setVisibility(View.GONE);
        mView.addOnAttachStateChangeListener(this);


        loadPreferences();


        // 定期処理開始
//        scheduleNextTime(mIntervalMs);
    }


    private void loadPreferences() {

        final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        mXPos = pref.getInt(C.PREF_KEY_X_POS, 100);
        mIntervalMs = pref.getInt(C.PREF_KEY_INTERVAL_MSEC, 1000);
        mBarMaxKB = pref.getInt(C.PREF_KEY_BAR_MAX_SPEED_KB, 100);
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final int result = super.onStartCommand(intent, flags, startId);

//        final long now = System.currentTimeMillis();
//        if (mLastCommandStarted == 0) {
//            MyLog.d("LayerService.onStartCommand: [first time]");
//        } else {
//            MyLog.d("LayerService.onStartCommand: [" + (now - mLastCommandStarted) + "ms]");
//        }
//        mLastCommandStarted = now;

        execTask();

        return result;
    }


    private void execTask() {

//        MyLog.d("LayerService.execTask");

        if (mSnapshot) {

            showTraffic();

            // 次回の実行予約
            scheduleNextTime(5000);
        } else {
            gatherTraffic();

            showTraffic();

            // 次回の実行予約
            scheduleNextTime(mIntervalMs);
        }
    }


    private void showTraffic() {

        if (!mAttached) {
            return;
        }

        long rxKb, txKb, rxD1Kb, txD1Kb;

        //--------------------------------------------------
        // prepare
        //--------------------------------------------------
        if (mSnapshot) {
            rxKb = mSnapshotBytes/1024;
            txKb = mSnapshotBytes/1024;
            rxD1Kb = (mSnapshotBytes%1024)/100;
            txD1Kb = (mSnapshotBytes%1024)/100;
        } else {
            rxKb = mDiffRxBytes / 1024 * 1000 / mElapsedMs;  // KB/s
            rxD1Kb = mDiffRxBytes % 1024;    // [0, 1023]
            // to [0, 9]
            if (rxD1Kb >= 900) rxD1Kb = 9;
            else if (rxD1Kb == 0) rxD1Kb = 0;
            else if (rxD1Kb <= 100) rxD1Kb = 1;
            else rxD1Kb = rxD1Kb / 100;

            txKb = mDiffTxBytes / 1024 * 1000 / mElapsedMs;  // KB/s
            txD1Kb = mDiffTxBytes % 1024;    // [0, 1023]
            // to [0, 9]
            if (txD1Kb >= 900) txD1Kb = 9;
            else if (txD1Kb == 0) txD1Kb = 0;
            else if (txD1Kb <= 100) txD1Kb = 1;
            else txD1Kb = txD1Kb / 100;
        }

        // set padding (x pos)
        {
            final int w0 = mView.getWidth();
            final int w = mView.findViewById(R.id.download_text_view).getRight() -
                    mView.findViewById(R.id.upload_bar).getLeft();
//            MyLog.d("LayerService.onViewAttachedToWindow: w[" + w + "]");
            mView.setPadding(0, 0, (w0 - w) * (100 - mXPos) / 100, 0);
        }

        final TextView uploadTextView = (TextView) mView.findViewById(R.id.upload_text_view);
        uploadTextView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        final String u = txKb + "." + txD1Kb + "KB/s";
        uploadTextView.setText(u);
        uploadTextView.setTextColor(getTextColorByKb(txKb));
        uploadTextView.setShadowLayer(1.5f, 1.5f, 1.5f, getTextShadowColorByKb(txKb));

        final TextView downloadTextView = (TextView) mView.findViewById(R.id.download_text_view);
        downloadTextView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        final String d = rxKb + "." + rxD1Kb + "KB/s";
        downloadTextView.setText(d);
        downloadTextView.setTextColor(getTextColorByKb(rxKb));
        downloadTextView.setShadowLayer(1.5f, 1.5f, 1.5f, getTextShadowColorByKb(rxKb));

//        MyLog.d("LayerService.showTraffic: U: " + u + ", D:" + d + ", elapsed[" + mElapsedMs + "]");

        // bars
        {
            final View mark = mView.findViewById(R.id.upload_mark);
            final int width = uploadTextView.getWidth() + mark.getWidth();

            final View bar = mView.findViewById(R.id.upload_bar);
//            bar.setBackgroundColor(0xAAff2222);
            bar.setBackgroundResource(R.drawable.upload_background);
            setColorBar(txKb, txD1Kb, width, bar);
        }

        {
            final View mark = mView.findViewById(R.id.download_mark);
            final int width = downloadTextView.getWidth() + mark.getWidth();

            final View bar = mView.findViewById(R.id.download_bar);
//            bar.setBackgroundColor(0xAAaaaaff);
            bar.setBackgroundResource(R.drawable.download_background);
            setColorBar(rxKb, rxD1Kb, width, bar);
        }
    }


    private void setColorBar(long kb, long d1Kb, int width, View bar) {

        if (width > 0) {
            final RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) bar.getLayoutParams();

            final int p = kb > mBarMaxKB ? 1000 : (int) ((kb * 1000 + d1Kb *100) / mBarMaxKB);   // [0, 1000]
            lp.rightMargin = width - width*p/1000;
            bar.setVisibility(View.VISIBLE);
            bar.setLayoutParams(lp);
        } else {
            bar.setVisibility(View.GONE);
        }
    }


    private int getTextShadowColorByKb(long kb) {

        if (kb < 10) {
            return getResources().getColor(R.color.textShadowColorLow);
        }
        if (kb < mBarMaxKB) {
            return getResources().getColor(R.color.textShadowColorMiddle);
        }
        return getResources().getColor(R.color.textShadowColorHigh);
    }


    private int getTextColorByKb(long kb) {

        if (kb < 10) {
            return getResources().getColor(R.color.textColorLow);
        }
        if (kb < mBarMaxKB) {
            return getResources().getColor(R.color.textColorMiddle);
        }
        return getResources().getColor(R.color.textColorHigh);
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
        // ※onStartCommandが呼ばれるように設定する

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
        // @see "http://creadorgranoeste.blogspot.com/2011/06/alarmmanager.html"
    }


    @Override
    public void onDestroy() {
        super.onDestroy();

        MyLog.d("LayerService.onDestroy");

        mServiceAlive = false;

        stopAlarm();

        // スリープ状態のレシーバ解除
        getApplicationContext().unregisterReceiver(mReceiver);

        mView.removeOnAttachStateChangeListener(this);

        // サービスが破棄されるときには重ね合わせしていたViewを削除する
        mWindowManager.removeView(mView);
    }


    @Override
    public void onViewAttachedToWindow(View v) {

        mAttached = true;

        MyLog.d("LayerService.onViewAttachedToWindow");
        mView.setVisibility(View.VISIBLE);
    }


    @Override
    public void onViewDetachedFromWindow(View v) {

        mAttached = false;
    }
}