package jp.takke.datastats

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.os.postDelayed
import jp.takke.util.MyLog
import kotlin.math.log10

class LayerService : Service(), View.OnAttachStateChangeListener {

    private val mNotificationPresenter = NotificationPresenter(this)


    private val mBinder = LocalBinder()

    private var mView: MyRelativeLayout? = null
    private var mWindowManager: WindowManager? = null
    private var mAttached = false


    private var mSleeping = false

    private var mServiceAlive = true

    private var mLastRxBytes: Long = 0
    private var mLastTxBytes: Long = 0

    private var mLastLoopbackRxBytes: Long = 0
    private var mLastLoopbackTxBytes: Long = 0

    private var mDiffRxBytes: Long = 0
    private var mDiffTxBytes: Long = 0

    private var mLastTime = System.currentTimeMillis()
    private var mElapsedMs = Config.intervalMs.toLong()


    // SNAPSHOTモードの送受信データ
    private var mSnapshot = false
    private var mSnapshotBytes: Long = 0


    // 通信量取得スレッド管理
    private var mThread: GatherThread? = null
    private var mThreadActive = false
    private val mHandler = Handler()


    private var mScreenOnOffSequence = 0

    /**
     * スリープ状態(SCREEN_ON/OFF)の検出用レシーバ
     */
    private val mReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {

            mScreenOnOffSequence++

            when (intent.action ?: return) {
                Intent.ACTION_SCREEN_ON ->
                    onScreenOn(mScreenOnOffSequence)

                Intent.ACTION_SCREEN_OFF ->
                    // たいていは GatherThread で検出したほうが早いんだけど設定値によっては
                    // 遅い場合もあるので Receiver での検出時も呼び出しておく
                    onScreenOff(mScreenOnOffSequence, "Intent")
            }
        }
    }

    @Suppress("DEPRECATION")
    private val myLayerType: Int
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            else -> WindowManager.LayoutParams.TYPE_TOAST
        }

    inner class LocalBinder : ILayerService.Stub() {

        @Throws(RemoteException::class)
        override fun restart() {

            MyLog.d("LayerService.restart")

            mSnapshot = false

            Config.loadPreferences(this@LayerService)

            // 通知(常駐)
            mNotificationPresenter.hideNotification()
            showNotification()

            // Alarmループ開始
            stopAlarm()
            scheduleNextTime(C.ALARM_STARTUP_DELAY_MSEC)

            // 通信量取得スレッド再起動
            if (mThread == null) {
                startGatherThread()
            }

            showTraffic()
        }

        @Throws(RemoteException::class)
        override fun stop() {

            MyLog.d("LayerService.stop")

            stopSelf()

            // -> スレッド停止処理等は onDestroy で。
        }

        @Throws(RemoteException::class)
        override fun startSnapshot(previewBytes: Long) {

            mSnapshot = true
            mSnapshotBytes = previewBytes
            MyLog.d("LayerService.startSnapshot " +
                    "bytes[" + mSnapshotBytes + "]")

            showTraffic()
        }
    }


    private fun onScreenOn(screenOnOffSequence: Int) {

        MyLog.d("LayerService: onScreenOn[$screenOnOffSequence]")

        // 停止していれば再開する
        mSleeping = false

        // SurfaceViewにSleepingフラグを反映
        setSleepingFlagToSurfaceView()

        // 表示初期化
        val mySurfaceView = mView?.findViewById<MySurfaceView>(R.id.mySurfaceView)
        mySurfaceView?.drawBlank()


        // スレッド開始は少し遅延させる
        // ※スレッド開始処理は重いので端末をロックさせてしまう。一時的なスリープ解除で端末がロックしてしまうのを回避するため。
        mHandler.postDelayed({

            // スリープ状態に戻っていたら開始しない
            if (mSleeping) {
                MyLog.d("LayerService: screen on[$screenOnOffSequence]: skip to start threads (sleeping)")
                return@postDelayed
            }

            // 既にスレッドが開始していたら処理しない
            if (mThread != null) {
                MyLog.d("LayerService: screen on[$screenOnOffSequence]: skip to start threads (already started)")
                return@postDelayed
            }

            MyLog.d("LayerService: screen on[$screenOnOffSequence]: starting threads")

            // 通信量取得スレッド開始
            startGatherThread()

            // 通知(常駐)
            showNotification()

            // Alarmループ開始
            scheduleNextTime(C.ALARM_STARTUP_DELAY_MSEC)

        }, C.SCREEN_ON_LOGIC_DELAY_MSEC.toLong())
    }

    private fun onScreenOff(screenOnOffSequence: Int, cause: String) {

        if (mSleeping) {
            MyLog.d("LayerService.onScreenOff[$screenOnOffSequence][$cause]: already sleeping")
            return
        }

        MyLog.d("LayerService.onScreenOff[$screenOnOffSequence][$cause]")

        // 停止する
        mSleeping = true

        // SurfaceViewにSleepingフラグを反映
        setSleepingFlagToSurfaceView()

        // スレッド停止は少し遅延させる
        // ※スレッド開始と同様
        mHandler.postDelayed({

            // スリープ復帰済みなら停止しない
            if (!mSleeping) {
                MyLog.d("LayerService: screen off[$screenOnOffSequence]: skip to stop threads (not sleeping)")
                return@postDelayed
            }

            // 既にスレッドが停止していたら処理しない
            if (mThread == null) {
                MyLog.d("LayerService: screen off[$screenOnOffSequence]: skip to stop threads (already stopped)")
                return@postDelayed
            }

            MyLog.d("LayerService: screen off[$screenOnOffSequence]: stopping threads")

            // 通信量取得スレッド停止
            stopGatherThread()

            // 通知終了(常駐解除)
            mNotificationPresenter.hideNotification()

            // アラーム停止
            stopAlarm()

        }, C.SCREEN_OFF_LOGIC_DELAY_MSEC.toLong())
    }

    private fun setSleepingFlagToSurfaceView() {

        if (mView == null) {
            return
        }
        val mySurfaceView = mView?.findViewById<MySurfaceView>(R.id.mySurfaceView) ?: return

        mySurfaceView.setSleeping(mSleeping)
    }

    override fun onBind(intent: Intent): IBinder? {

        MyLog.d("LayerService.onBind")

        // 定期取得スレッド開始
        startGatherThread()

//        showTraffic()

        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {

        MyLog.d("LayerService.onUnbind")

        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent) {

        MyLog.d("LayerService.onRebind")

        super.onRebind(intent)
    }

    @SuppressLint("RtlHardcoded", "InflateParams")
    override fun onCreate() {
        super.onCreate()

        MyLog.d("LayerService.onCreate")

        // M以降の権限対応
        if (!OverlayUtil.checkOverlayPermission(this)) {
            MyLog.w("no overlay permission")
            return
        }

        // Viewからインフレータを作成する
        val layoutInflater = LayoutInflater.from(this)

        // 重ね合わせするViewの設定を行う
        val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                myLayerType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.LEFT

        // WindowManagerを取得する
        mWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // レイアウトファイルから重ね合わせするViewを作成する
        mView = layoutInflater.inflate(R.layout.overlay, null) as MyRelativeLayout

        // Viewを画面上に重ね合わせする
        mWindowManager?.addView(mView, params)

        // スリープ状態のレシーバ登録
        applicationContext.registerReceiver(mReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        applicationContext.registerReceiver(mReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))


        // attach されるまでサイズ不明
        mView?.visibility = View.GONE
        mView?.addOnAttachStateChangeListener(this)

        Config.loadPreferences(this)

        // 定期処理開始
//        scheduleNextTime(intervalMs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val action = intent?.action
        MyLog.d("LayerService.onStartCommand flags[$flags] startId[$startId] intent.action[$action]")

        // 通信量取得スレッド開始
        if (mThread == null) {
            startGatherThread()
        }

        //--------------------------------------------------
        // SwitchButtonReceiver からの処理
        //--------------------------------------------------
        if (action != null) {
            when (action) {
                "show" ->
                    // OverlayView表示
                    mView?.visibility = View.VISIBLE

                "hide" ->
                    // OverlayView非表示
                    mView?.visibility = View.GONE

                "hide_and_resume" -> {
                    // OverlayView非表示 -> 10秒後に復帰
                    mView?.visibility = View.GONE
                    mHandler.postDelayed(10000L) {
                        mView?.visibility = View.VISIBLE

                        // 通知(ボタン変更)
                        showNotification()
                    }
                    Toast.makeText(this, getString(R.string.close_temporarily_resume_after_10_seconds), Toast.LENGTH_SHORT).show()
                }
            }

            // 通知(ボタン変更)
            showNotification()

            return START_STICKY
        }

        // 通知(常駐)
        mNotificationPresenter.createNotificationChannel()
        showNotification()

        // Alarmループ続行
        scheduleNextTime(C.ALARM_INTERVAL_MSEC)

        return START_STICKY
    }

    private fun showNotification() {
        mNotificationPresenter.showNotification(mView == null || mView?.visibility == View.VISIBLE)
    }

    private fun showTraffic() {

//        MyLog.d("LayerService.showTraffic, attached[" + mAttached + "]");

        if (!mAttached) {
            return
        }


        //--------------------------------------------------
        // update widget size and location
        //--------------------------------------------------
        updateWidgetSize()


        //--------------------------------------------------
        // prepare
        //--------------------------------------------------
        val rx: Long
        val tx: Long
        if (mSnapshot) {
            rx = mSnapshotBytes
            tx = mSnapshotBytes
        } else {
            rx = mDiffRxBytes * 1000 / mElapsedMs          // B/s
            tx = mDiffTxBytes * 1000 / mElapsedMs          // B/s
        }


        //--------------------------------------------------
        // bars
        //--------------------------------------------------
        val pTx = convertBytesToPerThousand(tx)    // [0, 1000]
        val pRx = convertBytesToPerThousand(rx)    // [0, 1000]
//      MyLog.d("tx[" + tx + "byes] -> [" + pTx + "]")
//      MyLog.d("rx[" + rx + "byes] -> [" + pRx + "]")

        val mySurfaceView = mView?.findViewById<MySurfaceView>(R.id.mySurfaceView)
        mySurfaceView?.setTraffic(tx, pTx, rx, pRx)
    }

    private fun updateWidgetSize() {

        val resources = resources
        val displayMetrics = resources.displayMetrics
        val view = mView ?: return

        //--------------------------------------------------
        // hide when in fullscreen
        //--------------------------------------------------
        val inFullScreen = view.isFullScreen
        run {

//            MyLog.d("LayerService.showTraffic: hide[" + Config.hideWhenInFullscreen + "], fullscreen[" + inFullScreen + "], " +
//                    "[" + dim.left + "," + dim.top + "], view[" + dim.width() + "x" + dim.height() + "], " +
//                    "system[" + displayMetrics.widthPixels + "x" + displayMetrics.heightPixels + "]"
//            )

            val mySurfaceView = view.findViewById<View>(R.id.mySurfaceView) ?: return
            if (Config.hideWhenInFullscreen) {
                if (inFullScreen) {
                    mySurfaceView.visibility = View.GONE
                } else {
                    mySurfaceView.visibility = View.VISIBLE
                }
            } else {
                mySurfaceView.visibility = View.VISIBLE
            }
        }


        //--------------------------------------------------
        // set widget width
        //--------------------------------------------------

        val scaledDensity = displayMetrics.scaledDensity
        val textSizeSp = Config.textSizeSp

        val mySurfaceView = view.findViewById<MySurfaceView>(R.id.mySurfaceView) ?: return

        // width = (iconSize + textAreaWidth) * 2
        // iconSize = textSize+4
        // textAreaWidth = (textSize+2) * 6
        val widgetWidthSp = (textSizeSp + 4 + (textSizeSp + 2) * 6) * 2
//        MyLog.d("LayerService.showTraffic: widgetWidth[" + widgetWidthSp + "sp]")


        val widgetWidth = (widgetWidthSp * scaledDensity).toInt()

        run {
            val params = mySurfaceView.layoutParams

            params.width = widgetWidth

            // height = textSize * 1.25
            params.height = (textSizeSp * 1.25 * scaledDensity).toInt()

            mySurfaceView.layoutParams = params
        }


        //--------------------------------------------------
        // set padding (x pos)
        //--------------------------------------------------
        run {
            val screenWidth = view.width

            var statusBarHeight = 0
            if (!inFullScreen) {
                val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
                if (resourceId > 0) {
                    statusBarHeight = resources.getDimensionPixelSize(resourceId)
                }
            }

            val right = (screenWidth - widgetWidth) * (100 - Config.xPos) / 100
//            MyLog.d("LayerService.showTraffic: right-padding[" + right + "]")
            view.setPadding(0, statusBarHeight, right, 0)
        }
    }

    private fun convertBytesToPerThousand(bytes: Long): Int {

        if (!Config.logBar) {
            return if (bytes / 1024 > Config.barMaxKB) 1000 else (bytes / Config.barMaxKB).toInt()   // [0, 1000]
        } else {
            // 100KB基準値
            val normalBytes = bytes * 100 / Config.barMaxKB
            return if (normalBytes < 1) {
                0
            } else {
                // max=100KB
                //   1KB -> 300
                //  10KB -> 400
                // 100KB -> 500
                val log = (log10(normalBytes.toDouble()) * 100).toInt()

                // max=100KB -> 500*2 = 1000
                log * 2
            }
        }
    }

    private fun gatherTraffic() {

        val totalRxBytes = TrafficStats.getTotalRxBytes()
        val totalTxBytes = TrafficStats.getTotalTxBytes()

        if (mLastRxBytes > 0) {
            mDiffRxBytes = totalRxBytes - mLastRxBytes
        }
        if (mLastTxBytes > 0) {
            mDiffTxBytes = totalTxBytes - mLastTxBytes
        }

        // loopback通信量を省く処理
        // Android4.3未満はTrafficStats.getTotalRx/TxBytes()に
        // loopback通信量を含んでいないのでこの処理はしない
        // ※Android 8.0以降は denied となるので除外する
        if (Build.VERSION_CODES.JELLY_BEAN_MR2 <= Build.VERSION.SDK_INT && Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            val loopbackRxBytes = MyTrafficUtil.getLoopbackRxBytes()
            val loopbackTxBytes = MyTrafficUtil.getLoopbackTxBytes()
            val diffLoopbackRxBytes = loopbackRxBytes - mLastLoopbackRxBytes
            val diffLoopbackTxBytes = loopbackTxBytes - mLastLoopbackTxBytes
            mDiffRxBytes -= diffLoopbackRxBytes
            mDiffTxBytes -= diffLoopbackTxBytes
            mLastLoopbackRxBytes = loopbackRxBytes
            mLastLoopbackTxBytes = loopbackTxBytes
//            MyLog.d("loopback[" + diffLoopbackRxBytes + "][" + diffLoopbackTxBytes + "]")
        }

        val now = System.currentTimeMillis()
        mElapsedMs = now - mLastTime
        if (mElapsedMs == 0L) {  // prohibit div by zero
            mElapsedMs = Config.intervalMs.toLong()
        }
        mLastTime = now

        mLastRxBytes = totalRxBytes
        mLastTxBytes = totalTxBytes
    }

    private fun scheduleNextTime(intervalMs: Int) {

        // サービス終了の指示が出ていたら，次回の予約はしない。
        if (!mServiceAlive) {
            return
        }
        if (mSleeping) {
            return
        }

        val now = System.currentTimeMillis()

        // アラームをセット
        val intent = Intent(this, this.javaClass)
        val alarmSender = PendingIntent.getService(
                this,
                0,
                intent,
                0
        )
        // ※onStartCommandが呼ばれるように設定する

        val am = this.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.RTC, now + intervalMs, alarmSender)

//        MyLog.d(" scheduled[" + intervalMs + "]");
    }

    private fun stopAlarm() {

        // サービス名を指定
        val intent = Intent(this, this.javaClass)

        // アラームを解除
        val pendingIntent = PendingIntent.getService(
                this,
                0, // ここを-1にすると解除に成功しない
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent)
        // @see "http://creadorgranoeste.blogspot.com/2011/06/alarmmanager.html"
    }

    override fun onDestroy() {
        super.onDestroy()

        MyLog.d("LayerService.onDestroy")

        mServiceAlive = false

        stopAlarm()

        mNotificationPresenter.hideNotification()

        // 通信量取得スレッド停止
        stopGatherThread()

        if (mView != null) {
            // スリープ状態のレシーバ解除
            applicationContext.unregisterReceiver(mReceiver)

            mView?.removeOnAttachStateChangeListener(this)

            // サービスが破棄されるときには重ね合わせしていたViewを削除する
            mWindowManager?.removeView(mView)
        }
    }

    override fun onViewAttachedToWindow(v: View) {

        mAttached = true

        MyLog.d("LayerService.onViewAttachedToWindow")
        mView?.visibility = View.VISIBLE
    }

    override fun onViewDetachedFromWindow(v: View) {

        mAttached = false
    }

    private fun startGatherThread() {

        if (mThread == null) {
            mThread = GatherThread()
            mThreadActive = true
            mThread?.start()
            MyLog.d("LayerService.startGatherThread: thread start")
        } else {
            MyLog.d("LayerService.startGatherThread: already running")
        }
    }

    private fun stopGatherThread() {

        if (mThreadActive && mThread != null) {
            MyLog.d("LayerService.stopGatherThread")

            mThreadActive = false
            while (true) {
                try {
                    mThread?.join()
                    break
                } catch (ignored: InterruptedException) {
                    MyLog.e(ignored)
                }

            }
            mThread = null
        } else {
            MyLog.d("LayerService.stopGatherThread: no thread")
        }
    }

    /**
     * 通信量取得スレッド
     */
    private inner class GatherThread : Thread() {

        override fun run() {

            MyLog.d("LayerService\$GatherThread: start")

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            while (mThread != null && mThreadActive) {

                SystemClock.sleep(Config.intervalMs.toLong())

                gatherTraffic()

                if (mAttached && !mSleeping) {
                    mHandler.post {

                        if (mThreadActive && mAttached) {
                            showTraffic()
                        }
                    }


                    @Suppress("DEPRECATION")
                    if (!powerManager.isScreenOn) {
                        MyLog.d("LayerService\$GatherThread: not interactive")
                        onScreenOff(mScreenOnOffSequence, "GatherThread")
                    }
                }
            }

            MyLog.d("LayerService\$GatherThread: done")
        }
    }
}