package jp.takke.datastats

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import jp.takke.util.MyLog
import jp.takke.util.TkUtil

class LayerService : Service(), View.OnAttachStateChangeListener {

  private val mNotificationPresenter = NotificationPresenter(this)


  private val mBinder = LocalBinder()

  private var mView: MyRelativeLayout? = null
  private var mWindowManager: WindowManager? = null
  private var mOverlayLayoutParams: WindowManager.LayoutParams? = null

  /**
   * 全画面判定用のダミーオーバーレイ。
   * `FLAG_LAYOUT_IN_SCREEN` を付けず、`fitInsetsTypes = statusBars or navigationBars` +
   * `isFitInsetsIgnoringVisibility = false` を指定した MATCH_PARENT 窓は、
   * system bars の可視状態に応じて描画サイズが変化する。
   * そのためビュー高さ == 実ディスプレイ高さ なら全画面(イマーシブ)と判定できる。
   *
   * `TYPE_APPLICATION_OVERLAY` の `rootWindowInsets` が他アプリの immersive 切替を
   * 反映しない Android 14 の実機に対して、確実に検出するために利用する。
   */
  private var mFullScreenDetectorView: View? = null

  // 現在のデフォルトネットワーク種別モニタ(mobileOnlyMeter / showOnlyOnMobile 用)
  private var mNetworkMonitor: NetworkTypeMonitor? = null

  // ユーザが「表示中」を望んでいるか(通知の Show/Hide ボタン / QS タイルで切り替え)。
  // このフラグと「全画面時の一時非表示」を組み合わせて mView.visibility を決定する。
  private var mUserWantsVisible = true

  // hide_and_resume による一時非表示中かどうか。
  // ※あくまで一時的な状態なので prefs には永続化しない
  //  (永続化すると 10 秒以内のプロセス死で「永久に非表示」になってしまうため)
  private var mTemporarilyHidden = false

  // hide_and_resume の復帰処理(show/hide 操作やサービス破棄でキャンセルできるよう保持する)
  private var mResumeRunnable: Runnable? = null

  // startForeground がバックグラウンド開始制限で拒否され、再試行待ちかどうか。
  // Android 15+(targetSdk 35+)では SYSTEM_ALERT_WINDOW 権限だけでは許可されず
  // 「オーバーレイが実際に表示中」である必要があるため、表示後に再試行する。
  private var mForegroundPending = false
  private var mForegroundRetryCount = 0
  private val mForegroundRetryRunnable = Runnable { retryStartForeground() }

  @Volatile
  private var mAttached = false

  // onCreate 時点でオーバーレイ権限があったかどうか
  private var mHasOverlayPermission = false


  @Volatile
  private var mSleeping = false

  private var mLastRxBytes: Long = 0
  private var mLastTxBytes: Long = 0

  // 前回 gatherTraffic 時の計測ソース(true = モバイルのみ)。切替検出用
  private var mLastCounterSourceMobile = false

  private var mLastLoopbackRxBytes: Long = 0
  private var mLastLoopbackTxBytes: Long = 0

  private var mDiffRxBytes: Long = 0
  private var mDiffTxBytes: Long = 0

  // 経過時間計算には単調増加クロックを使う(壁時計時刻の変更で狂わないように)
  private var mLastTime = SystemClock.elapsedRealtime()
  private var mElapsedMs = Config.intervalMs.toLong()


  // SNAPSHOTモードの送受信データ
  private var mSnapshot = false
  private var mSnapshotBytes: Long = 0

  // updateWidgetSize のキャッシュ
  // 依存する Config 値・画面幅・全画面状態が変わらない限り再計算をスキップして毎秒の負荷を減らす
  private var mLayoutCached = false
  private var mCachedTextSizeSp = -1
  private var mCachedDensity = 0f
  private var mCachedFontScale = 0f
  private var mCachedScreenWidth = -1
  private var mCachedXPos = -1
  private var mCachedAtBottom = false
  private var mCachedTopPadding = -1
  private var mCachedInFullScreen = false

  // 実ディスプレイ高さのキャッシュ(回転時に onConfigurationChanged で無効化)
  private var mCachedDisplayRealHeight = -1


  // 通信量取得スレッド管理
  @Volatile
  private var mThread: GatherThread? = null
  @Volatile
  private var mThreadActive = false
  private val mHandler = Handler(Looper.getMainLooper())


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

  private val myLayerType: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
      // M〜N 向け(minSdk 23 のため TYPE_TOAST フォールバックは不要)
      @Suppress("DEPRECATION")
      WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
    }

  inner class LocalBinder : ILayerService.Stub() {

    @Throws(RemoteException::class)
    override fun restart() {

      MyLog.d("LayerService.restart")

      // オーバーレイ権限なしで bind 経由の restart が来た場合、通知(startForeground)や
      // スレッド起動を行うと「表示なしの常駐サービス」が復活してしまうため停止する
      if (!mHasOverlayPermission) {
        MyLog.w("LayerService.restart: no overlay permission -> stopSelf")
        stopSelf()
        return
      }

      mSnapshot = false

      Config.loadPreferences(this@LayerService)

      // ユーザ意図(Show/Hide)を prefs から同期する
      // (アプリの Start ボタンは事前に true を書き込むため、明示的な開始で確実に再表示される)
      mUserWantsVisible = PreferenceManager.getDefaultSharedPreferences(this@LayerService)
        .getBoolean(C.PREF_KEY_USER_WANTS_VISIBLE, true)

      // 通知(常駐)
      // ※ startForeground は再度呼んでも通知を更新するのでhide不要
      showNotification()

      // 通信量取得スレッド再起動
      if (mThread == null) {
        startGatherThread()
      }

      // 補間モード等の Config 反映(Interpolate ON/OFF を即時反映するため)
      val mySurfaceView = mView?.findViewById<MySurfaceView>(R.id.mySurfaceView)
      mySurfaceView?.applyInterpolationConfig()

      // 縦位置・不透明度の設定変更をウィンドウへ反映
      applyOverlayLayoutConfig()

      showTrafficWithForceRedraw()
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
      MyLog.d(
        "LayerService.startSnapshot " +
                "bytes[" + mSnapshotBytes + "]"
      )

      showTraffic()
    }

    @Throws(RemoteException::class)
    override fun forceRedraw(previewBytes: Long) {

      MyLog.d("LayerService.forceRedraw bytes[$previewBytes]")

      mSnapshot = true
      mSnapshotBytes = previewBytes

      showTrafficWithForceRedraw()
    }
  }

  /** 補間モードや同一フレームスキップを無効化して 1 フレーム強制描画する */
  private fun showTrafficWithForceRedraw() {
    MySurfaceView.sForceRedraw = true
    try {
      showTraffic()
    } finally {
      MySurfaceView.sForceRedraw = false
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

      // 通知は残したままにする(startForegroundで表示している通知はcancelでは消えないため、
      // 画面OFF中はスレッドだけ止めて通知は維持する)

    }, C.SCREEN_OFF_LOGIC_DELAY_MSEC.toLong())
  }

  private fun setSleepingFlagToSurfaceView() {

    if (mView == null) {
      return
    }
    val mySurfaceView = mView?.findViewById<MySurfaceView>(R.id.mySurfaceView) ?: return

    mySurfaceView.setSleeping(mSleeping)
  }

  override fun onBind(intent: Intent): IBinder {

    MyLog.d("LayerService.onBind")

    // オーバーレイ権限がない場合はスレッドを起動しない
    // (BIND_AUTO_CREATE で権限なしのままインスタンスが生き残る経路への対策)
    if (mHasOverlayPermission) {
      // 定期取得スレッド開始
      startGatherThread()
    }

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

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)

    MyLog.d("LayerService.onConfigurationChanged orientation[${newConfig.orientation}]")

    // 回転などで画面サイズが変化する。以下を実施:
    //  1) レイアウトキャッシュを無効化して次の updateWidgetSize で再計算させる
    //  2) WindowManager オーバーレイは MATCH_PARENT でも自動リサイズしないことがあるため
    //     LayoutParams を再適用して新しい画面幅で確実に再レイアウトさせる
    mLayoutCached = false
    mCachedDisplayRealHeight = -1
    val view = mView ?: return
    val params = mOverlayLayoutParams ?: return
    try {
      mWindowManager?.updateViewLayout(view, params)
    } catch (e: Exception) {
      MyLog.e(e)
    }

    // 次の描画で新しい screenWidth が反映されるよう即描画をキック
    showTraffic()
  }

  @SuppressLint("RtlHardcoded", "InflateParams")
  override fun onCreate() {
    super.onCreate()

    MyLog.d("LayerService.onCreate")

    // M以降の権限対応
    // 権限がない状態でBootReceiver等から起動されると何も表示しないサービスが常駐するため
    // onStartCommand側でstopSelfして即座に停止する
    if (!OverlayUtil.checkOverlayPermission(this)) {
      MyLog.w("no overlay permission")
      return
    }

    mHasOverlayPermission = true

    // ユーザ意図(Show/Hide 状態)を prefs から復元
    // ※ QS タイルからの切替を LayerService 再作成後も引き継ぐため
    mUserWantsVisible = PreferenceManager.getDefaultSharedPreferences(this)
      .getBoolean(C.PREF_KEY_USER_WANTS_VISIBLE, true)

    // ウィンドウの gravity / alpha が Config に依存するため、params 生成前にロードする
    Config.loadPreferences(this)

    // Viewからインフレータを作成する
    val layoutInflater = LayoutInflater.from(this)

    // 重ね合わせするViewの設定を行う
    @Suppress("DEPRECATION")
    // API 30 で FLAG_LAYOUT_INSET_DECOR が deprecated だがこれを外すと Y 座標がツールバーの下くらいになってしまう
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      myLayerType,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
              or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
              or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
              or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
      PixelFormat.TRANSLUCENT
    )
    params.alpha = computeOverlayAlpha()
    params.gravity = overlayGravity()
    mOverlayLayoutParams = params

    // WindowManagerを取得する
    mWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // レイアウトファイルから重ね合わせするViewを作成する
    mView = layoutInflater.inflate(R.layout.overlay, null) as MyRelativeLayout

    // 全画面状態の変化を次回の更新タイミングを待たず即座に表示へ反映する
    // (showTraffic 冒頭の applyOverlayVisibility が可視性も更新する)
    mView?.onFullScreenChangedListener = {
      showTraffic()
    }

    // オーバーレイの実サイズ変化(端末回転など)を検知してレイアウトキャッシュを無効化する
    // ※WindowManager オーバーレイ + MATCH_PARENT でも回転で即座にサイズ更新されないことがあるため
    mView?.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
      if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
        mLayoutCached = false
        // レイアウトが変わったので即描画を反映する
        showTraffic()
      }
    }

    // Viewを画面上に重ね合わせする
    mWindowManager?.addView(mView, params)

    // 全画面判定用のダミー窓を追加(FLAG_LAYOUT_IN_SCREEN なし)
    addFullScreenDetectorView()

    // デフォルトネットワーク種別モニタ起動
    // (showOnlyOnMobile の可視性判定、および mobileOnlyMeter モードの切替時反映のため)
    mNetworkMonitor = NetworkTypeMonitor(this).apply {
      onChangedListener = { type ->
        // ネットワーク遷移で応答すべきは可視性(showOnlyOnMobile)と種別バッジ
        // callback は非メインスレッドの可能性があるので main に post する
        mHandler.post {
          applyOverlayVisibility()
          mView?.findViewById<MySurfaceView>(R.id.mySurfaceView)?.setNetworkType(type)
        }
      }
      start()
    }
    // 初期値も反映
    mView?.findViewById<MySurfaceView>(R.id.mySurfaceView)
      ?.setNetworkType(mNetworkMonitor?.currentType ?: NetworkTypeMonitor.NetworkType.NONE)

    // スリープ状態のレシーバ登録
    applicationContext.registerReceiver(mReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    applicationContext.registerReceiver(mReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))


    // attach されるまでサイズ不明
    mView?.visibility = View.GONE
    mView?.addOnAttachStateChangeListener(this)
  }

  /**
   * オーバーレイの alpha を設定値から計算する。
   * Android 12+ はタッチ保護のため maximumObscuringOpacityForTouch を超えられない。
   */
  private fun computeOverlayAlpha(): Float {
    var alpha = Config.overlayOpacity.coerceIn(C.MIN_OVERLAY_OPACITY, 100) / 100f
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val maxAlpha =
        (getSystemService(Context.INPUT_SERVICE) as InputManager).maximumObscuringOpacityForTouch
      if (alpha > maxAlpha) {
        alpha = maxAlpha
      }
    }
    return alpha
  }

  /** オーバーレイの gravity(縦位置設定に応じて上端/下端) */
  @SuppressLint("RtlHardcoded")
  private fun overlayGravity(): Int {
    return (if (Config.overlayAtBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.LEFT
  }

  /** 縦位置・不透明度の設定変更をウィンドウへ再適用する(restart 時に呼ぶ) */
  private fun applyOverlayLayoutConfig() {
    val view = mView ?: return
    val params = mOverlayLayoutParams ?: return
    val newGravity = overlayGravity()
    val newAlpha = computeOverlayAlpha()
    if (params.gravity == newGravity && params.alpha == newAlpha) {
      return
    }
    params.gravity = newGravity
    params.alpha = newAlpha
    try {
      mWindowManager?.updateViewLayout(view, params)
    } catch (e: Exception) {
      MyLog.e(e)
    }
    // 縦位置変更で padding(statusBar/navBar)も変わるためレイアウトを再計算させる
    mLayoutCached = false
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)

    val action = intent?.action
    MyLog.d("LayerService.onStartCommand flags[$flags] startId[$startId] intent.action[$action]")

    // 通知チャンネルは startForegroundService の 5 秒ルール対応で
    // 最初の startForeground より前に必ず作成する(action 有無どちらの経路でも)
    mNotificationPresenter.createNotificationChannel()

    // オーバーレイ権限がない状態(BootReceiver経由等)で起動された場合は
    // 何もせず自身を停止する。FGS の 5 秒ルールを満たすため一度だけ startForeground してから終了する。
    if (!mHasOverlayPermission) {
      MyLog.w("LayerService.onStartCommand: no overlay permission -> stopSelf")
      // ※すぐ停止するので、拒否された場合の再試行(showNotification())は行わない
      mNotificationPresenter.showNotification(false)
      mNotificationPresenter.hideNotification()
      stopSelf()
      return START_NOT_STICKY
    }

    // 通信量取得スレッド開始
    if (mThread == null) {
      startGatherThread()
    }

    //--------------------------------------------------
    // 通知ボタン / QS タイルからの処理
    //--------------------------------------------------
    if (action != null) {
      when (action) {
        C.ACTION_SHOW -> {
          cancelPendingResume()
          mTemporarilyHidden = false
          setUserWantsVisible(true)
        }

        C.ACTION_HIDE -> {
          cancelPendingResume()
          mTemporarilyHidden = false
          setUserWantsVisible(false)
        }

        C.ACTION_HIDE_AND_RESUME -> {
          // 一時非表示は prefs に永続化しない(10 秒以内のプロセス死で永久非表示になるのを防ぐ)
          cancelPendingResume()
          mTemporarilyHidden = true
          applyOverlayVisibility()
          val resume = Runnable {
            mResumeRunnable = null
            mTemporarilyHidden = false
            applyOverlayVisibility()
            showNotification()
          }
          mResumeRunnable = resume
          mHandler.postDelayed(resume, HIDE_AND_RESUME_DELAY_MSEC)
          Toast.makeText(
            this,
            getString(R.string.close_temporarily_resume_after_10_seconds),
            Toast.LENGTH_SHORT
          ).show()
        }

        else -> MyLog.w("LayerService.onStartCommand: unknown action[$action]")
      }

      // 通知(ボタン変更)
      showNotification()

      return START_STICKY
    }

    // 通知(常駐)
    showNotification()

    // ※かつては AlarmManager による 60 秒周期の keep-alive ループを張っていたが、
    //  Doze 環境では発火が遅延・抑制され効果が薄い一方でバッテリーを消費するため廃止した。
    //  プロセス kill 後の復帰は FGS + START_STICKY によるシステムの再起動に任せる。

    return START_STICKY
  }

  private fun showNotification() {
    // 通知のボタン(Show/Hide)はユーザ意図と一時非表示状態に従う。全画面による自動非表示は反映しない。
    val succeeded =
      mNotificationPresenter.showNotification(mUserWantsVisible && !mTemporarilyHidden)

    if (succeeded) {
      if (mForegroundPending) {
        MyLog.i("LayerService: startForeground succeeded after $mForegroundRetryCount retries")
        mForegroundPending = false
        mForegroundRetryCount = 0
        mHandler.removeCallbacks(mForegroundRetryRunnable)
        // 再試行中に強制表示していたオーバーレイを本来の可視状態へ戻す
        applyOverlayVisibility()
      }
      return
    }

    // バックグラウンドからの開始が拒否された(プロセス kill 後の START_STICKY 再起動など)。
    // オーバーレイが表示されれば許可されるので、少し待って再試行する。
    if (!mForegroundPending) {
      mForegroundPending = true
      mForegroundRetryCount = 0
      // 非表示設定中でも許可条件(オーバーレイ表示中)を満たせるよう一時的に表示する
      applyOverlayVisibility()
    }
    scheduleForegroundRetry()
  }

  private fun scheduleForegroundRetry() {
    mHandler.removeCallbacks(mForegroundRetryRunnable)
    if (mForegroundRetryCount >= FOREGROUND_RETRY_DELAYS_MSEC.size) {
      // これ以上は粘らない。フォアグラウンド化できないサービスはいずれシステムに停止されるが、
      // スリープ復帰時(onScreenOn)やアプリ起動時に再度 showNotification() が呼ばれて回復を試みる。
      MyLog.w("LayerService: give up retrying startForeground")
      return
    }
    val delay = FOREGROUND_RETRY_DELAYS_MSEC[mForegroundRetryCount]
    mForegroundRetryCount++
    MyLog.d("LayerService: retry startForeground after ${delay}ms [$mForegroundRetryCount]")
    mHandler.postDelayed(mForegroundRetryRunnable, delay)
  }

  private fun retryStartForeground() {
    if (!mForegroundPending) {
      return
    }
    showNotification()
  }

  /** hide_and_resume の保留中の復帰処理をキャンセルする */
  private fun cancelPendingResume() {
    mResumeRunnable?.let { mHandler.removeCallbacks(it) }
    mResumeRunnable = null
  }

  /**
   * ユーザ意図(Show/Hide)を更新する。prefs にも永続化して
   * クイック設定タイル側からも参照できるようにする。
   */
  private fun setUserWantsVisible(value: Boolean) {
    mUserWantsVisible = value
    PreferenceManager.getDefaultSharedPreferences(this).edit {
      putBoolean(C.PREF_KEY_USER_WANTS_VISIBLE, value)
    }
    applyOverlayVisibility()
    // QS タイルの表示状態を最新化(タイルが listening 中なら onStartListening が再度呼ばれて更新される)
    requestOverlayTileUpdate(this)
  }

  /**
   * 現在の全画面(イマーシブ)状態。
   * WindowInsets ベースの判定(MyRelativeLayout)とダミー窓ベースの判定(detector)を
   * どちらか true なら全画面とみなす。Android 14 では前者が更新されないことがあるため二重化。
   */
  private fun isInFullScreenNow(): Boolean {
    val view = mView ?: return false
    return view.isFullScreen || isFullScreenViaDetector()
  }

  /**
   * ユーザ意図(Show/Hide)と全画面判定(hideWhenInFullscreen)から
   * オーバーレイの表示/非表示を確定する。
   *
   * Android 14 では `mySurfaceView.visibility = GONE` だけでは描画残りが起きるケースが
   * あるため、親の `mView.visibility` 自体を切り替える方針とする。
   */
  private fun applyOverlayVisibility(inFullScreen: Boolean = isInFullScreenNow()) {
    val view = mView ?: return
    val hidingByFullscreen = Config.hideWhenInFullscreen && inFullScreen
    // showOnlyOnMobile: 現在の接続がモバイル以外なら非表示にする(VPN 経由も非モバイル扱い)
    val hidingByNonMobile =
      Config.showOnlyOnMobile &&
              mNetworkMonitor?.currentType != NetworkTypeMonitor.NetworkType.MOBILE
    // フォアグラウンド化の再試行中は、許可条件(オーバーレイ表示中)を満たすため強制的に表示する
    val target =
      if (mForegroundPending ||
        (mUserWantsVisible && !mTemporarilyHidden && !hidingByFullscreen && !hidingByNonMobile)
      ) {
        View.VISIBLE
      } else {
        View.GONE
      }
    if (view.visibility != target) {
      MyLog.d {
        "applyOverlayVisibility: " +
                (if (target == View.VISIBLE) "VISIBLE" else "GONE") +
                " (foregroundPending=$mForegroundPending" +
                ", userWants=$mUserWantsVisible, temporarilyHidden=$mTemporarilyHidden" +
                ", hidingByFullscreen=$hidingByFullscreen, hidingByNonMobile=$hidingByNonMobile)"
      }
      view.visibility = target
    }
  }

  private fun showTraffic() {

    if (!mAttached) {
      return
    }

    // 全画面状態は insets/detector の二重評価があるため 1 回だけ計算して使い回す
    val inFullScreen = isInFullScreenNow()

    // 可視性判定はキャッシュに乗せず毎回評価する。
    // (showOnlyOnMobile / hideWhenInFullscreen / mUserWantsVisible は
    //  updateWidgetSize のキャッシュキーに含まれておらず、
    //  cache hit で早期 return されると反映されないため)
    applyOverlayVisibility(inFullScreen)

    //--------------------------------------------------
    // update widget size and location
    //--------------------------------------------------
    updateWidgetSize(inFullScreen)


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

  private fun updateWidgetSize(inFullScreen: Boolean) {

    val view = mView ?: return
    val mySurfaceView = view.findViewById<View>(R.id.mySurfaceView) ?: return

    val displayMetrics = resources.displayMetrics
    // sp→px 変換は density とフォントスケール設定に依存するため両方をキャッシュキーにする
    // (非線形フォントスケーリング対応のため換算係数のキャッシュは不可)
    val density = displayMetrics.density
    val fontScale = resources.configuration.fontScale
    val textSizeSp = Config.textSizeSp
    val screenWidth = view.width
    val xPos = Config.xPos
    val atBottom = Config.overlayAtBottom

    // 上端表示はステータスバー分の padding を確保する
    // (FLAG_LAYOUT_IN_SCREEN によりウィンドウがステータスバーへ重なって配置されるため)。
    // 下端表示は FLAG_LAYOUT_INSET_DECOR によりウィンドウ自体がナビゲーションバーの
    // 上に配置されるため、追加の padding は不要(付けると二重にオフセットされる)。
    // ※padding もキャッシュキーに含める。insets の反映が window 移動より遅れて
    //  一時的に 0 を返しても、値が確定した次回の tick で再適用され自己修復する。
    val topPadding =
      if (!atBottom && !inFullScreen) getStatusBarHeight() else 0

    // 依存する値がすべて前回と同じなら再計算・レイアウト適用をスキップする
    // (毎秒 showTraffic() から呼ばれるため、setLayoutParams / setPadding の
    //  再実行を避けるためのキャッシュ)
    // ※visibility は showTraffic() 側の applyOverlayVisibility() で毎回反映する
    if (
      mLayoutCached &&
      textSizeSp == mCachedTextSizeSp &&
      density == mCachedDensity &&
      fontScale == mCachedFontScale &&
      screenWidth == mCachedScreenWidth &&
      xPos == mCachedXPos &&
      atBottom == mCachedAtBottom &&
      topPadding == mCachedTopPadding &&
      inFullScreen == mCachedInFullScreen
    ) {
      return
    }
    mCachedTextSizeSp = textSizeSp
    mCachedDensity = density
    mCachedFontScale = fontScale
    mCachedScreenWidth = screenWidth
    mCachedXPos = xPos
    mCachedAtBottom = atBottom
    mCachedTopPadding = topPadding
    mCachedInFullScreen = inFullScreen
    mLayoutCached = true

    //--------------------------------------------------
    // set widget width
    //--------------------------------------------------
    // width = (iconSize + textAreaWidth) * 2
    // iconSize = textSize+4
    // textAreaWidth = (textSize+2) * 6
    val widgetWidthSp = (textSizeSp + 4 + (textSizeSp + 2) * 6) * 2
    val widgetWidth = TkUtil.spToPx(widgetWidthSp.toFloat(), displayMetrics).toInt()

    val params = mySurfaceView.layoutParams
    params.width = widgetWidth
    // height = textSize * 1.25
    params.height = TkUtil.spToPx(textSizeSp * 1.25f, displayMetrics).toInt()
    mySurfaceView.layoutParams = params

    //--------------------------------------------------
    // set padding (x pos / y pos)
    //--------------------------------------------------
    val right = (screenWidth - widgetWidth) * (100 - xPos) / 100
    view.setPadding(0, topPadding, right, 0)
  }

  /**
   * ステータスバーの高さを WindowInsets から取得する。
   * (旧実装の内部リソース `status_bar_height` の `getIdentifier` 参照は非公開 API のため廃止)
   * `getInsetsIgnoringVisibility` を使うことで、一時的にバーが隠れていても安定値を返す。
   */
  private fun getStatusBarHeight(): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      // ビューの rootWindowInsets は「自ウィンドウのフレーム」に依存するため、
      // 下端表示中(ステータスバーに重ならない)はステータスバー高さが 0 になってしまう。
      // ウィンドウ位置に依存しないディスプレイレベルの insets を使う。
      val wm = mWindowManager ?: return 0
      return wm.currentWindowMetrics.windowInsets
        .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
        .top
    }

    // API 23〜29: view の insets から取得(ウィンドウ移動直後は古い値になり得るが、
    // updateWidgetSize が padding をキャッシュキーに含めるため次回の tick で自己修復する)
    val view = mView ?: return 0
    val rootInsets = view.rootWindowInsets ?: return 0
    return WindowInsetsCompat.toWindowInsetsCompat(rootInsets, view)
      .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
      .top
  }

  /**
   * 全画面判定用のダミーオーバーレイを WindowManager に追加する。
   * MATCH_PARENT サイズにして `setFitInsetsTypes(status+nav)` + `setFitInsetsIgnoringVisibility(false)`
   * を指定することで、system bars 可視時はそれらを避けたサイズに縮み、非表示時は full display 相当に広がる。
   * 描画された view の高さと実ディスプレイの高さを比較して全画面を判定する。
   */
  @SuppressLint("InflateParams")
  private fun addFullScreenDetectorView() {
    val wm = mWindowManager ?: return
    if (mFullScreenDetectorView != null) return

    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
      myLayerType,
      // 重要: FLAG_LAYOUT_IN_SCREEN を意図的に付けない
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
              or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
              or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSPARENT,
    )
    params.gravity = Gravity.TOP or Gravity.LEFT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      params.alpha = 0f
    }
    // API 30+: system bars の "現在の可視状態" にウィンドウサイズを追従させる
    // (setFitInsetsIgnoringVisibility(false) が肝: 可視時のみ避ける = 全画面時は広がる)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      params.fitInsetsTypes =
        WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
      params.isFitInsetsIgnoringVisibility = false
    }

    val detector = View(this)
    // サイズが変化したら(≒ system bars 状態が変わったら)即描画を反映する
    // (showTraffic 冒頭の applyOverlayVisibility が可視性も更新する)
    detector.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
      val h = bottom - top
      val oldH = oldBottom - oldTop
      if (h != oldH) {
        MyLog.d { "FullScreenDetector: layout height=$h (old=$oldH), full=${isFullScreenViaDetector()}" }
        mLayoutCached = false
        showTraffic()
      }
    }
    mFullScreenDetectorView = detector

    try {
      wm.addView(detector, params)
    } catch (e: Exception) {
      MyLog.e(e)
      mFullScreenDetectorView = null
    }
  }

  private fun removeFullScreenDetectorView() {
    val v = mFullScreenDetectorView ?: return
    try {
      mWindowManager?.removeView(v)
    } catch (e: Exception) {
      MyLog.e(e)
    }
    mFullScreenDetectorView = null
  }

  /**
   * ダミー窓の高さと実ディスプレイの高さを比較して全画面判定する。
   * 完全一致 = system bars を挟まずに描画されている = 全画面(イマーシブ)。
   * ダミー窓がまだ addView 直後で layout 未完了(未 attach / height 0)の場合は false を返す。
   */
  private fun isFullScreenViaDetector(): Boolean {
    val v = mFullScreenDetectorView ?: return false
    if (!v.isAttachedToWindow) return false
    val detectorHeight = v.height
    if (detectorHeight <= 0) return false
    val realHeight = getDisplayRealHeight()
    return detectorHeight >= realHeight
  }

  @Suppress("DEPRECATION")
  private fun getDisplayRealHeight(): Int {
    // 毎秒の判定で WindowMetrics/Point を生成しないようキャッシュする(回転時に無効化)
    if (mCachedDisplayRealHeight > 0) return mCachedDisplayRealHeight
    val wm = mWindowManager ?: return 0
    val height = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      wm.maximumWindowMetrics.bounds.height()
    } else {
      val point = android.graphics.Point()
      wm.defaultDisplay.getRealSize(point)
      point.y
    }
    mCachedDisplayRealHeight = height
    return height
  }

  private fun convertBytesToPerThousand(bytes: Long): Int {
    // 変換ロジックはテスト可能な純粋関数として MyTrafficUtil に切り出している
    return MyTrafficUtil.convertBytesToPerThousand(bytes, Config.logBar, Config.barMaxKB)
  }

  private fun gatherTraffic() {

    // モバイル通信量のみを計測するモード。TrafficStats.getMobileXxxBytes() は
    // モバイル回線分の送受信バイト数を返す(Wi-Fi 分は含まない)
    val mobileOnly = Config.mobileOnlyMeter

    // 計測ソース(total/mobile)が切り替わった直後は、前回値が別ソースの累積値のため
    // diff が数十 GB 規模の異常値になる。リセットして今回のサンプルは diff 0 で読み飛ばす。
    if (mobileOnly != mLastCounterSourceMobile) {
      mLastCounterSourceMobile = mobileOnly
      mLastRxBytes = 0
      mLastTxBytes = 0
      mDiffRxBytes = 0
      mDiffTxBytes = 0
    }

    val totalRxBytes =
      if (mobileOnly) TrafficStats.getMobileRxBytes() else TrafficStats.getTotalRxBytes()
    val totalTxBytes =
      if (mobileOnly) TrafficStats.getMobileTxBytes() else TrafficStats.getTotalTxBytes()

    // TrafficStats.UNSUPPORTED (-1) が返る端末では diff 計算ができないので 0 として扱う
    if (totalRxBytes == TrafficStats.UNSUPPORTED.toLong() ||
      totalTxBytes == TrafficStats.UNSUPPORTED.toLong()
    ) {
      mDiffRxBytes = 0
      mDiffTxBytes = 0
      val now = SystemClock.elapsedRealtime()
      mElapsedMs = now - mLastTime
      if (mElapsedMs <= 0L) {  // prohibit div by zero
        mElapsedMs = Config.intervalMs.toLong()
      }
      mLastTime = now
      return
    }

    if (mLastRxBytes > 0) {
      // カウンタリセット(モデム再初期化等)で負値になるケースをクランプ
      mDiffRxBytes = maxOf(0L, totalRxBytes - mLastRxBytes)
    }
    if (mLastTxBytes > 0) {
      mDiffTxBytes = maxOf(0L, totalTxBytes - mLastTxBytes)
    }

    // loopback通信量を省く処理
    // Android4.3未満はTrafficStats.getTotalRx/TxBytes()に
    // loopback通信量を含んでいないのでこの処理はしない
    // ※Android 8.0以降は denied となるので除外する
    // ※モバイル計測(getMobileXxxBytes)には loopback 分が含まれないため減算しない
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 && !mobileOnly) {
      val loopbackRxBytes = MyTrafficUtil.loopbackRxBytes
      val loopbackTxBytes = MyTrafficUtil.loopbackTxBytes
      val diffLoopbackRxBytes = loopbackRxBytes - mLastLoopbackRxBytes
      val diffLoopbackTxBytes = loopbackTxBytes - mLastLoopbackTxBytes
      mDiffRxBytes = maxOf(0L, mDiffRxBytes - diffLoopbackRxBytes)
      mDiffTxBytes = maxOf(0L, mDiffTxBytes - diffLoopbackTxBytes)
      mLastLoopbackRxBytes = loopbackRxBytes
      mLastLoopbackTxBytes = loopbackTxBytes
//            MyLog.d("loopback[" + diffLoopbackRxBytes + "][" + diffLoopbackTxBytes + "]")
    }

    val now = SystemClock.elapsedRealtime()
    mElapsedMs = now - mLastTime
    if (mElapsedMs <= 0L) {  // prohibit div by zero
      mElapsedMs = Config.intervalMs.toLong()
    }
    mLastTime = now

    mLastRxBytes = totalRxBytes
    mLastTxBytes = totalTxBytes
  }

  override fun onDestroy() {
    super.onDestroy()

    MyLog.d("LayerService.onDestroy")

    // hide_and_resume の復帰処理等、保留中の遅延タスクを掃除する
    // (破棄後に発火すると破棄済みインスタンス上で通知再表示などが走ってしまう)
    mHandler.removeCallbacksAndMessages(null)
    mResumeRunnable = null

    mNotificationPresenter.hideNotification()

    // 通信量取得スレッド停止
    stopGatherThread()

    if (mView != null) {
      // スリープ状態のレシーバ解除
      applicationContext.unregisterReceiver(mReceiver)

      mView?.removeOnAttachStateChangeListener(this)

      // 全画面検出用ダミー窓の解放
      removeFullScreenDetectorView()

      // ネットワークモニタ解放
      mNetworkMonitor?.stop()
      mNetworkMonitor = null

      // サービスが破棄されるときには重ね合わせしていたViewを削除する
      mWindowManager?.removeView(mView)
    }
  }

  override fun onViewAttachedToWindow(v: View) {

    mAttached = true

    MyLog.d("LayerService.onViewAttachedToWindow")
    // attach 完了後、ユーザ意図と全画面状態から visibility を確定する
    applyOverlayVisibility()
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
      // Thread.sleep 中の GatherThread を即座に起こすため interrupt() で中断してから join する
      // (最大 2 秒のメインスレッドブロックによる ANR 回避)
      mThread?.interrupt()
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

        // SystemClock.sleep は割り込み不可のため Thread.sleep を使用する
        // (stopGatherThread() からの interrupt() で即座に抜けられるように)
        try {
          Thread.sleep(Config.intervalMs.toLong())
        } catch (e: InterruptedException) {
          MyLog.d("LayerService\$GatherThread: interrupted")
          break
        }

        gatherTraffic()

        if (mAttached && !mSleeping) {
          mHandler.post {

            if (mThreadActive && mAttached) {
              showTraffic()
            }
          }


          if (!powerManager.isInteractive) {
            MyLog.d("LayerService\$GatherThread: not interactive")
            // onScreenOff は mSleeping の書き換えと mHandler.postDelayed の登録を行うため
            // メインスレッドに委譲する(ワーカースレッド直接呼び出しは競合の原因になる)
            val seq = mScreenOnOffSequence
            mHandler.post { onScreenOff(seq, "GatherThread") }
          }
        }
      }

      MyLog.d("LayerService\$GatherThread: done")
    }
  }

  companion object {
    // hide_and_resume で再表示するまでの時間[ms]
    private const val HIDE_AND_RESUME_DELAY_MSEC = 10_000L

    // startForeground 拒否時の再試行間隔[ms]
    // - START_STICKY 再起動時: オーバーレイのウィンドウが表示され次第(数百 ms 後)許可される
    // - startService で新規に開始された場合: システムはサービス生成から 10 秒経過するまで
    //   許可判定をやり直さないため、累計 10 秒を超える再試行が必要(実機で確認)
    private val FOREGROUND_RETRY_DELAYS_MSEC = longArrayOf(300, 1_000, 3_000, 7_000, 15_000, 30_000, 60_000)

    /**
     * Activity 以外(端末起動時 / アプリ更新後 / 通知ボタン / QS タイル)からサービスを開始する。
     *
     * バックグラウンドからの startForegroundService は Android 12+ で
     * ForegroundServiceStartNotAllowedException になり得るため、拒否された場合は
     * 通常の startService にフォールバックする(サービス側でオーバーレイ表示後に
     * startForeground を再試行する)。どちらも不可なら諦める(クラッシュさせない)。
     *
     * @return 開始要求を出せたら true
     */
    fun startFromBackground(context: Context, action: String? = null): Boolean {
      val serviceIntent = Intent(context, LayerService::class.java)
      serviceIntent.action = action
      try {
        ContextCompat.startForegroundService(context, serviceIntent)
        return true
      } catch (e: IllegalStateException) {
        MyLog.w("LayerService.startFromBackground: startForegroundService not allowed: $e")
      }
      return try {
        context.startService(serviceIntent)
        true
      } catch (e: IllegalStateException) {
        MyLog.w("LayerService.startFromBackground: startService not allowed: $e")
        false
      }
    }
  }
}