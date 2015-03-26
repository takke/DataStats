package jp.takke.datastats;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.TrafficStats;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import jp.takke.util.MyLog;

public class LayerService extends Service implements View.OnAttachStateChangeListener {

    public class LocalBinder extends ILayerService.Stub {

        @Override
        public void restart() throws RemoteException {

            MyLog.d("LayerService.restart");

            mSnapshot = false;

            Config.loadPreferences(LayerService.this);

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


    private boolean mSleeping = false;

    private boolean mServiceAlive = true;
    private long mLastRxBytes = 0;
    private long mLastTxBytes = 0;

    private long mDiffRxBytes = 0;
    private long mDiffTxBytes = 0;

    private long mLastTime = System.currentTimeMillis();
    private long mElapsedMs = Config.intervalMs;

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

                // SurfaceViewに設定
                setSleepingFlagToSurfaceView();

                // 次の onStart を呼ぶ
                scheduleNextTime(Config.intervalMs);

            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {

                MyLog.d("LayerService: screen off");

                // 停止する
                mSleeping = true;

                // SurfaceViewに設定
                setSleepingFlagToSurfaceView();
                
                // アラーム停止
                stopAlarm();
            }
        }
    };


    private void setSleepingFlagToSurfaceView() {

        if (mView == null) {
            return;
        }
        final MySurfaceView mySurfaceView = (MySurfaceView) mView.findViewById(R.id.mySurfaceView);
        if (mySurfaceView == null) {
            return;
        }
        
        mySurfaceView.setSleeping(mSleeping);
    }


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
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_TOAST,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;

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


        Config.loadPreferences(this);


        // 定期処理開始
//        scheduleNextTime(intervalMs);
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
            scheduleNextTime(Config.intervalMs);
        }
    }


    private void showTraffic() {

        if (!mAttached) {
            return;
        }
        
        //--------------------------------------------------
        // hide when in fullscreen
        //--------------------------------------------------
        boolean inFullScreen;
        {
            final Rect dim = new Rect();
            mView.getWindowVisibleDisplayFrame(dim);
//            MyLog.d("LayerService.showTraffic: top[" + dim.top + "]");
            
            inFullScreen = dim.top == 0;

//            MyLog.d("LayerService.showTraffic: hide[" + hideWhenInFullscreen + "], fullscreen[" + inFullScreen + "]");
            if (Config.hideWhenInFullscreen) {
                if (inFullScreen) {
                    mView.setVisibility(View.GONE);
                } else {
                    mView.setVisibility(View.VISIBLE);
                }
            } else {
                mView.setVisibility(View.VISIBLE);
            }
        }


        //--------------------------------------------------
        // prepare
        //--------------------------------------------------
        final long rx, tx;
        if (mSnapshot) {
            rx = mSnapshotBytes;
            tx = mSnapshotBytes;
        } else {
            rx = mDiffRxBytes * 1000 / mElapsedMs;          // B/s
            tx = mDiffTxBytes * 1000 / mElapsedMs;          // B/s
        }

        //--------------------------------------------------
        // set widget width
        //--------------------------------------------------
        final Resources resources = getResources();

        final float scaledDensity = resources.getDisplayMetrics().scaledDensity;
        final int textSizeSp = Config.textSizeSp;

        final MySurfaceView mySurfaceView = (MySurfaceView) mView.findViewById(R.id.mySurfaceView);
        
        // width = (iconSize + textAreaWidth) * 2
        // iconSize = textSize+4
        // textAreaWidth = (textSize+2) * 6
        final int widgetWidthSp = ((textSizeSp + 4) + (textSizeSp + 2) * 6) * 2;
//        MyLog.d("LayerService.showTraffic: widgetWidth[" + widgetWidthSp + "sp]");
        
        
        final int widgetWidth = (int) (widgetWidthSp * scaledDensity);
        
        {
            final ViewGroup.LayoutParams params = mySurfaceView.getLayoutParams();
            
            params.width = widgetWidth;
            
            // height = textSize * 1.25
            params.height = (int) ((textSizeSp * 1.25) * scaledDensity);
            
            mySurfaceView.setLayoutParams(params);
        }

        //--------------------------------------------------
        // set padding (x pos)
        //--------------------------------------------------
        {
            final int screenWidth = mView.getWidth();

            int statusBarHeight = 0;
            if (!inFullScreen) {
                int resourceId = resources.getIdentifier("status_bar_height", "dimen", "android");
                if (resourceId > 0) {
                    statusBarHeight = resources.getDimensionPixelSize(resourceId);
                }
            }

            mView.setPadding(0, statusBarHeight, (screenWidth - widgetWidth) * (100 - Config.xPos) / 100, 0);
        }

        //--------------------------------------------------
        // bars
        //--------------------------------------------------
        final int pTx = convertBytesToPerThousand(tx);    // [0, 1000]
        final int pRx = convertBytesToPerThousand(rx);    // [0, 1000]
//      MyLog.d("tx[" + tx + "byes] -> [" + pTx + "]");
//      MyLog.d("rx[" + rx + "byes] -> [" + pRx + "]");

        mySurfaceView.setTraffic(tx, pTx, rx, pRx);
    }


    private int convertBytesToPerThousand(long bytes) {
        
        if (!Config.logBar) {
            return bytes/1024 > Config.barMaxKB ? 1000 : (int) (bytes / Config.barMaxKB);   // [0, 1000]
        } else {
            // 100KB基準値
            final long normalBytes = bytes * 100 / Config.barMaxKB;
            if (normalBytes < 1) {
                return 0;
            } else {
                // max=100KB
                //   1KB -> 300
                //  10KB -> 400
                // 100KB -> 500
                final int log = (int) (Math.log10(normalBytes) * 100);

                // max=100KB -> 500*2 = 1000
                return log * 2;
            }
        }
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
            mElapsedMs = Config.intervalMs;
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