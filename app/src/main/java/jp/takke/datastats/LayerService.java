package jp.takke.datastats;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import jp.takke.util.MyLog;

public class LayerService extends Service implements View.OnAttachStateChangeListener {

    private static final int MY_NOTIFICATION_ID = 1;

    private final static String CHANNEL_ID = "resident";


    public class LocalBinder extends ILayerService.Stub {

        @Override
        public void restart() throws RemoteException {

            MyLog.d("LayerService.restart");

            mSnapshot = false;

            Config.loadPreferences(LayerService.this);

            // 通知(常駐)
            hideNotification();
            if (Config.residentMode) {
                showNotification();
            }

            // Alarmループ開始
            stopAlarm();
            scheduleNextTime(C.ALARM_STARTUP_DELAY_MSEC);

            // 通信量取得スレッド再起動
            if (mThread == null) {
                startGatherThread();
            }

            showTraffic();
        }

        @Override
        public void stop() throws RemoteException {

            MyLog.d("LayerService.stop");

            stopSelf();

            // -> スレッド停止処理等は onDestroy で。
        }

        @Override
        public void startSnapshot(long previewBytes) throws RemoteException {

            mSnapshot = true;
            mSnapshotBytes = previewBytes;
            MyLog.d("LayerService.startSnapshot " +
                    "bytes[" + mSnapshotBytes + "]");

            showTraffic();
        }
    }


    private LocalBinder mBinder = new LocalBinder();

    private MyRelativeLayout mView;
    private WindowManager mWindowManager;
    private boolean mAttached = false;


    private boolean mSleeping = false;

    private boolean mServiceAlive = true;

    private long mLastRxBytes = 0;
    private long mLastTxBytes = 0;

    private long mLastLoopbackRxBytes = 0;
    private long mLastLoopbackTxBytes = 0;

    private long mDiffRxBytes = 0;
    private long mDiffTxBytes = 0;

    private long mLastTime = System.currentTimeMillis();
    private long mElapsedMs = Config.intervalMs;

//    private long mLastCommandStarted = 0;


    // SNAPSHOTモードの送受信データ
    private boolean mSnapshot = false;
    private long mSnapshotBytes = 0;


    // 通信量取得スレッド管理
    private GatherThread mThread = null;
    private boolean mThreadActive = false;
    private Handler mHandler = new Handler();


    private int mScreenOnOffSequence = 0;

    /**
     * スリープ状態(SCREEN_ON/OFF)の検出用レシーバ
     */
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {

            mScreenOnOffSequence ++;

            final String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_ON)) {

                onScreenOn(mScreenOnOffSequence);

            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {

                // たいていは GatherThread で検出したほうが早いんだけど設定値によっては
                // 遅い場合もあるので Receiver での検出時も呼び出しておく
                onScreenOff(mScreenOnOffSequence, "Intent");
            }
        }
    };


    private void onScreenOn(final int screenOnOffSequence) {

        MyLog.d("LayerService: onScreenOn[" + screenOnOffSequence + "]");

        // 停止していれば再開する
        mSleeping = false;

        // SurfaceViewにSleepingフラグを反映
        setSleepingFlagToSurfaceView();

        // 表示初期化
        final MySurfaceView mySurfaceView = (MySurfaceView) mView.findViewById(R.id.mySurfaceView);
        if (mySurfaceView != null) {
            mySurfaceView.drawBlank();
        }


        // スレッド開始は少し遅延させる
        // ※スレッド開始処理は重いので端末をロックさせてしまう。一時的なスリープ解除で端末がロックしてしまうのを回避するため。
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {

                // スリープ状態に戻っていたら開始しない
                if (mSleeping) {
                    MyLog.d("LayerService: screen on[" + screenOnOffSequence + "]: skip to start threads (sleeping)");
                    return;
                }

                // 既にスレッドが開始していたら処理しない
                if (mThread != null) {
                    MyLog.d("LayerService: screen on[" + screenOnOffSequence + "]: skip to start threads (already started)");
                    return;
                }

                MyLog.d("LayerService: screen on[" + screenOnOffSequence + "]: starting threads");

                // 通信量取得スレッド開始
                startGatherThread();

                // 通知(常駐)
                if (Config.residentMode) {
                    showNotification();
                }

                // Alarmループ開始
                scheduleNextTime(C.ALARM_STARTUP_DELAY_MSEC);

            }
        }, C.SCREEN_ON_LOGIC_DELAY_MSEC);
    }


    private void onScreenOff(final int screenOnOffSequence, String cause) {

        if (mSleeping) {
            MyLog.d("LayerService.onScreenOff[" + screenOnOffSequence + "][" + cause + "]: already sleeping");
            return;
        }

        MyLog.d("LayerService.onScreenOff[" + screenOnOffSequence + "][" + cause + "]");

        // 停止する
        mSleeping = true;

        // SurfaceViewにSleepingフラグを反映
        setSleepingFlagToSurfaceView();

        // スレッド停止は少し遅延させる
        // ※スレッド開始と同様
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {

                // スリープ復帰済みなら停止しない
                if (!mSleeping) {
                    MyLog.d("LayerService: screen off[" + screenOnOffSequence + "]: skip to stop threads (not sleeping)");
                    return;
                }

                // 既にスレッドが停止していたら処理しない
                if (mThread == null) {
                    MyLog.d("LayerService: screen off[" + screenOnOffSequence + "]: skip to stop threads (already stopped)");
                    return;
                }

                MyLog.d("LayerService: screen off[" + screenOnOffSequence + "]: stopping threads");

                // 通信量取得スレッド停止
                stopGatherThread();

                // 通知終了(常駐解除)
                hideNotification();

                // アラーム停止
                stopAlarm();

            }
        }, C.SCREEN_OFF_LOGIC_DELAY_MSEC);
    }


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

        // 定期取得スレッド開始
        startGatherThread();

//        showTraffic();

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

        // M以降の権限対応
        if (!OverlayUtil.checkOverlayPermission(this)) {
            MyLog.w("no overlay permission");
            return;
        }

        // Viewからインフレータを作成する
        LayoutInflater layoutInflater = LayoutInflater.from(this);

        // 重ね合わせするViewの設定を行う
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                getMyLayerType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;

        // WindowManagerを取得する
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        // レイアウトファイルから重ね合わせするViewを作成する
        mView = (MyRelativeLayout) layoutInflater.inflate(R.layout.overlay, null);

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


    private int getMyLayerType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
        } else {
            return WindowManager.LayoutParams.TYPE_TOAST;
        }
    }


    private void showNotification() {

        MyLog.d("showNotification");

        final NotificationManager nm = ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE));

        // 通知ウインドウをクリックした際に起動するインテント
        final Intent intent = new Intent(this, MainActivity.class);
        final PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT);

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext());

        builder.setSmallIcon(R.drawable.ic_launcher);
        builder.setOngoing(true);
        builder.setPriority(NotificationCompat.PRIORITY_MIN);

        builder.setContentTitle(getString(R.string.resident_service_running));
//        builder.setContentText("表示・非表示を切り替える");
        builder.setContentIntent(pendingIntent);

        // channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "常駐通知", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
            builder.setChannelId(CHANNEL_ID);
        }

        startForeground(MY_NOTIFICATION_ID, builder.build());
    }


    private void hideNotification() {

        MyLog.d("hideNotification");

        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(MY_NOTIFICATION_ID);
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        MyLog.d("LayerService.onStartCommand[" + flags + "][" + startId + "]");

        // 通信量取得スレッド開始
        if (mThread == null) {
            startGatherThread();
        }

        // 通知(常駐)
        if (Config.residentMode) {
            showNotification();
        }

        // Alarmループ続行
        scheduleNextTime(C.ALARM_INTERVAL_MSEC);

        return START_STICKY;
    }


    private void showTraffic() {

//        MyLog.d("LayerService.showTraffic, attached[" + mAttached + "]");

        if (!mAttached) {
            return;
        }
        
        
        //--------------------------------------------------
        // update widget size and location
        //--------------------------------------------------
        updateWidgetSize();


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
        // bars
        //--------------------------------------------------
        final int pTx = convertBytesToPerThousand(tx);    // [0, 1000]
        final int pRx = convertBytesToPerThousand(rx);    // [0, 1000]
//      MyLog.d("tx[" + tx + "byes] -> [" + pTx + "]");
//      MyLog.d("rx[" + rx + "byes] -> [" + pRx + "]");

        final MySurfaceView mySurfaceView = (MySurfaceView) mView.findViewById(R.id.mySurfaceView);
        mySurfaceView.setTraffic(tx, pTx, rx, pRx);
    }


    private void updateWidgetSize() {

        final Resources resources = getResources();
        final DisplayMetrics displayMetrics = resources.getDisplayMetrics();

        //--------------------------------------------------
        // hide when in fullscreen
        //--------------------------------------------------
        boolean inFullScreen = mView.isFullScreen();
        {

//            MyLog.d("LayerService.showTraffic: hide[" + Config.hideWhenInFullscreen + "], fullscreen[" + inFullScreen + "], " +
//                    "[" + dim.left + "," + dim.top + "], view[" + dim.width() + "x" + dim.height() + "], " +
//                    "system[" + displayMetrics.widthPixels + "x" + displayMetrics.heightPixels + "]"
//            );

            final View mySurfaceView = mView.findViewById(R.id.mySurfaceView);
            if (Config.hideWhenInFullscreen) {
                if (inFullScreen) {
                    mySurfaceView.setVisibility(View.GONE);
                } else {
                    mySurfaceView.setVisibility(View.VISIBLE);
                }
            } else {
                mySurfaceView.setVisibility(View.VISIBLE);
            }
        }
        
        
        //--------------------------------------------------
        // set widget width
        //--------------------------------------------------

        final float scaledDensity = displayMetrics.scaledDensity;
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

            final int right = (screenWidth - widgetWidth) * (100 - Config.xPos) / 100;
//            MyLog.d("LayerService.showTraffic: right-padding[" + right + "]");
            mView.setPadding(0, statusBarHeight, right, 0);
        }
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

        // loopback通信量を省く処理
        // Android4.3未満はTrafficStats.getTotalRx/TxBytes()に
        // loopback通信量を含んでいないのでこの処理はしない
        // ※Android 8.0以降は denied となるので除外する
        if (Build.VERSION_CODES.JELLY_BEAN_MR2 <= Build.VERSION.SDK_INT &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            final long loopbackRxBytes = MyTrafficUtil.getLoopbackRxBytes();
            final long loopbackTxBytes = MyTrafficUtil.getLoopbackTxBytes();
            final long diffLoopbackRxBytes = loopbackRxBytes - mLastLoopbackRxBytes;
            final long diffLoopbackTxBytes = loopbackTxBytes - mLastLoopbackTxBytes;
            mDiffRxBytes -= diffLoopbackRxBytes;
            mDiffTxBytes -= diffLoopbackTxBytes;
            mLastLoopbackRxBytes = loopbackRxBytes;
            mLastLoopbackTxBytes = loopbackTxBytes;
//            MyLog.d("loopback[" + diffLoopbackRxBytes + "][" + diffLoopbackTxBytes + "]");
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

        hideNotification();

        // 通信量取得スレッド停止
        stopGatherThread();

        if (mView != null) {
            // スリープ状態のレシーバ解除
            getApplicationContext().unregisterReceiver(mReceiver);

            mView.removeOnAttachStateChangeListener(this);

            // サービスが破棄されるときには重ね合わせしていたViewを削除する
            mWindowManager.removeView(mView);
        }
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


    private void startGatherThread() {

        if (mThread == null) {
            mThread = new GatherThread();
            mThreadActive = true;
            mThread.start();
            MyLog.d("LayerService.startGatherThread: thread start");
        } else {
            MyLog.d("LayerService.startGatherThread: already running");
        }
    }


    private void stopGatherThread() {

        if (mThreadActive && mThread != null) {
            MyLog.d("LayerService.stopGatherThread");

            mThreadActive = false;
            while (true) {
                try {
                    mThread.join();
                    break;
                } catch (InterruptedException ignored) {
                    MyLog.e(ignored);
                }
            }
            mThread = null;
        } else {
            MyLog.d("LayerService.stopGatherThread: no thread");
        }
    }


    /**
     * 通信量取得スレッド
     */
    private class GatherThread extends Thread {

        @Override
        public void run() {

            MyLog.d("LayerService$GatherThread: start");

            final PowerManager powermanager;
            powermanager = (PowerManager) getSystemService(Context.POWER_SERVICE);

            while (mThread != null && mThreadActive) {

                SystemClock.sleep(Config.intervalMs);

                gatherTraffic();

                if (mAttached && !mSleeping) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {

                            if (mThreadActive && mAttached) {
                                showTraffic();
                            }
                        }
                    });

                    //noinspection deprecation
                    if (!powermanager.isScreenOn()) {
                        MyLog.d("LayerService$GatherThread: not interactive");
                        onScreenOff(mScreenOnOffSequence, "GatherThread");
                    }
                }
            }

            MyLog.d("LayerService$GatherThread: done");
        }
    }
}