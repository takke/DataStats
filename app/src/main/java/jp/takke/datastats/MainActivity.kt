package jp.takke.datastats

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import jp.takke.datastats.ui.AppTheme
import jp.takke.datastats.ui.ConfigUiState
import jp.takke.datastats.ui.MainScreen
import jp.takke.datastats.ui.MainScreenCallbacks
import jp.takke.datastats.ui.OnboardingCallbacks
import jp.takke.datastats.ui.OnboardingScreen
import jp.takke.datastats.ui.OnboardingUiState
import jp.takke.util.MyLog
import jp.takke.util.TkConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {

  private var mServiceIF: ILayerService? = null

  // bindService() 呼び出し済みかどうか。
  // onServiceConnected が呼ばれる前に onDestroy が来ても unbind が漏れないよう
  // 接続状態(mServiceIF)ではなく bind 状態で unbind の判定を行う
  private var mBound = false

  // Compose UI 状態
  private var mUiState by mutableStateOf(ConfigUiState())

  // オンボーディング状態
  private var mShowOnboarding by mutableStateOf(false)
  private var mOnboardingState by mutableStateOf(OnboardingUiState())

  // 実トラフィックのライブプレビュー用ポーリング Job
  private var mLiveTrafficJob: Job? = null

  private val mServiceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) {

      MyLog.d("onServiceConnected[$name]")

      mServiceIF = ILayerService.Stub.asInterface(service)
    }

    override fun onServiceDisconnected(name: ComponentName) {

      MyLog.d("onServiceDisconnected[$name]")

      mServiceIF = null
    }
  }

  private val overlayPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      refreshOnboardingState()
      if (!mShowOnboarding && OverlayUtil.checkOverlayPermission(this)) {
        // 従来フロー(オンボーディング済で不足だった権限が入った場合の再開)
        doStopService()
        doRestartService()
      }
    }

  private val batteryOptimizationLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      refreshOnboardingState()
    }

  private val requestPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
      refreshOnboardingState()
      if (isGranted) {
        MyLog.d("POST_NOTIFICATION: 通知許可")
      } else {
        MyLog.i("POST_NOTIFICATION: 通知許可しない")
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
          Toast.makeText(this, "通知を受け取るには許可が必要です", Toast.LENGTH_LONG).show()
        }
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    MyLog.d("MainActivity.onCreate")

    loadUiStateFromPrefs()
    refreshOnboardingState()
    mShowOnboarding = shouldShowOnboarding()

    setContent {
      AppTheme {
        if (mShowOnboarding) {
          OnboardingScreen(state = mOnboardingState, callbacks = buildOnboardingCallbacks())
        } else {
          MainScreen(state = mUiState, callbacks = buildCallbacks())
        }
      }
    }

    if (!mShowOnboarding) {
      // 通知権限は初回のみオンボーディングで要求するが、
      // オンボーディング済かつ拒否済みの状態から復帰する場合の再確認は不要にしている
      if (OverlayUtil.checkOverlayPermission(this)) {
        doBindService()
      }
    }

    MyLog.deleteBigExternalLogFile()
  }

  override fun onResume() {
    super.onResume()
    refreshOnboardingState()
    if (mUiState.previewLiveMode) {
      startLiveTrafficPolling()
    }
  }

  //----------------------------------------------------------------
  // 実トラフィックのライブプレビュー
  //----------------------------------------------------------------

  private fun startLiveTrafficPolling() {
    if (mLiveTrafficJob?.isActive == true) return
    mLiveTrafficJob = lifecycleScope.launch {
      // オーバーレイと同じ計測ソースを使う(mobileOnlyMeter 有効時はモバイル通信量のみ)
      fun currentTxBytes() =
        if (Config.mobileOnlyMeter) TrafficStats.getMobileTxBytes() else TrafficStats.getTotalTxBytes()

      fun currentRxBytes() =
        if (Config.mobileOnlyMeter) TrafficStats.getMobileRxBytes() else TrafficStats.getTotalRxBytes()

      var lastMobileOnly = Config.mobileOnlyMeter
      var lastTx = currentTxBytes()
      var lastRx = currentRxBytes()
      var lastTime = SystemClock.elapsedRealtime()
      while (isActive) {
        delay(1000L)
        val now = SystemClock.elapsedRealtime()
        val mobileOnly = Config.mobileOnlyMeter
        val tx = currentTxBytes()
        val rx = currentRxBytes()
        val elapsed = now - lastTime
        val unsupported = TrafficStats.UNSUPPORTED.toLong()
        if (mobileOnly != lastMobileOnly) {
          // 計測ソースが切り替わった直後は diff が異常値になるため 1 サンプル読み飛ばす
          lastMobileOnly = mobileOnly
        } else if (
          tx != unsupported && rx != unsupported && elapsed > 0 && lastTx > 0 && lastRx > 0
        ) {
          val diffTx = maxOf(0L, tx - lastTx)
          val diffRx = maxOf(0L, rx - lastRx)
          val txBps = diffTx * 1000L / elapsed
          val rxBps = diffRx * 1000L / elapsed
          mUiState = mUiState.copy(previewLiveTxBps = txBps, previewLiveRxBps = rxBps)
        }
        lastTx = tx
        lastRx = rx
        lastTime = now
      }
    }
  }

  private fun stopLiveTrafficPolling() {
    mLiveTrafficJob?.cancel()
    mLiveTrafficJob = null
  }

  private fun shouldShowOnboarding(): Boolean {
    val pref = PreferenceManager.getDefaultSharedPreferences(this)
    val onboarded = pref.getBoolean(C.PREF_KEY_ONBOARDING_DONE, false)
    // オンボーディング完了後でもオーバーレイ権限が剥奪されていたら再表示し、
    // 再許可の導線にする(でないと権限を再要求する手段がなくアプリが沈黙したままになる)
    if (onboarded) return !mOnboardingState.overlayGranted
    // 初回起動時、または明示的な onboardingDone フラグが立っていない場合はオンボーディングを表示する。
    // 既に必須権限がすべて揃っている場合(想定外だが)はスキップして通常フローに戻す。
    return !mOnboardingState.canProceed
  }

  private fun refreshOnboardingState() {
    val overlay = OverlayUtil.checkOverlayPermission(this)
    val notifRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val notifGranted = if (!notifRequired) true
    else ContextCompat.checkSelfPermission(
      this,
      Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    val batteryIgnored = isIgnoringBatteryOptimizations()
    mOnboardingState = OnboardingUiState(
      overlayGranted = overlay,
      notificationGranted = notifGranted,
      notificationRequired = notifRequired,
      batteryOptimizationIgnored = batteryIgnored,
    )
    // メイン画面の警告カードにも反映する
    if (mUiState.batteryOptimizationEnabled == batteryIgnored) {
      mUiState = mUiState.copy(batteryOptimizationEnabled = !batteryIgnored)
    }
  }

  private fun isIgnoringBatteryOptimizations(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(packageName)
  }

  private fun buildOnboardingCallbacks(): OnboardingCallbacks = OnboardingCallbacks(
    onGrantOverlay = { requestOverlayPermission() },
    onGrantNotification = {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        requestNotificationPermission()
      }
    },
    onConfigureBattery = { openBatteryOptimizationSettings() },
    onComplete = { completeOnboarding() },
  )

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun requestNotificationPermission() {
    val rationale = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
    if (rationale) {
      NotificationPermissionUtil.showNotificationPermissionRationaleDialog(
        this,
        onOk = { requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        onCancel = { MyLog.d("通知権限: キャンセル") },
      )
    } else {
      requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  private fun openBatteryOptimizationSettings() {
    // ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS はアプリ一覧を開くだけの無害な導線。
    // 直接除外を要求する ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS は Play ポリシー制約があるため使わない。
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    try {
      batteryOptimizationLauncher.launch(intent)
    } catch (e: Exception) {
      MyLog.e(e)
      Toast.makeText(this, "Settings not available", Toast.LENGTH_SHORT).show()
    }
  }

  private fun completeOnboarding() {
    if (!mOnboardingState.canProceed) return

    // オンボーディング済フラグと「常駐 ON」(端末起動時自動起動)を明示的に保存する。
    // startOnBoot はデフォルト true だが、初期状態を明示してユーザ変更前の意図を prefs に固定する。
    savePref {
      putBoolean(C.PREF_KEY_ONBOARDING_DONE, true)
      putBoolean(C.PREF_KEY_START_ON_BOOT, true)
    }

    mUiState = mUiState.copy(autoStartOnBoot = true)
    mShowOnboarding = false

    // サービス起動 → 常駐開始
    doBindService()
  }

  private fun loadUiStateFromPrefs() {
    Config.loadPreferences(this)
    val pref = PreferenceManager.getDefaultSharedPreferences(this)
    mUiState = ConfigUiState(
      autoStartOnBoot = pref.getBoolean(C.PREF_KEY_START_ON_BOOT, true),
      hideWhenInFullscreen = Config.hideWhenInFullscreen,
      logBar = Config.logBar,
      interpolateMode = Config.interpolateMode,
      sparklineMode = Config.sparklineMode,
      showOnlyOnMobile = Config.showOnlyOnMobile,
      mobileOnlyMeter = Config.mobileOnlyMeter,
      showNetworkTypeIcon = Config.showNetworkTypeIcon,
      textSizeSp = Config.textSizeSp,
      xPos = Config.xPos,
      intervalMs = Config.intervalMs,
      barMaxKB = Config.barMaxKB,
      unitTypeBps = Config.unitTypeBps,
      autoUnitScale = Config.autoUnitScale,
      overlayAtBottom = Config.overlayAtBottom,
      overlayOpacity = Config.overlayOpacity,
      overlayBgColor = Config.overlayBgColor,
      displayStyle = Config.displayStyle,
      debugMode = TkConfig.debugMode,
      batteryOptimizationEnabled = !isIgnoringBatteryOptimizations(),
      previewLabel = "-",
      previewSlider = 0,
    )
  }

  private fun buildCallbacks(): MainScreenCallbacks = MainScreenCallbacks(
    onAutoStartOnBootChange = { checked ->
      savePref { putBoolean(C.PREF_KEY_START_ON_BOOT, checked) }
      mUiState = mUiState.copy(autoStartOnBoot = checked)
    },
    onHideWhenInFullscreenChange = { checked ->
      savePref { putBoolean(C.PREF_KEY_HIDE_WHEN_IN_FULLSCREEN, checked) }
      mUiState = mUiState.copy(hideWhenInFullscreen = checked)
      // restart で Config を再読込し、画面を開いたまま切替が即座に効くようにする
      doRestartService()
    },
    onLogBarChange = { checked ->
      savePref { putBoolean(C.PREF_KEY_LOGARITHM_BAR, checked) }
      mUiState = mUiState.copy(logBar = checked)
      doRestartService()
    },
    onInterpolateChange = { checked ->
      savePref { putBoolean(C.PREF_KEY_INTERPOLATE_MODE, checked) }
      mUiState = mUiState.copy(interpolateMode = checked)
      // サービス再起動時に MySurfaceView.applyInterpolationConfig() が呼ばれるので
      // Surface を作り直す必要はなく、restart のみで即座に反映される
      doRestartService()
    },
    onSparklineChange = { checked ->
      savePref { putBoolean(C.PREF_KEY_SPARKLINE_MODE, checked) }
      mUiState = mUiState.copy(sparklineMode = checked)
      // 描画スレッドは restart で Config を読み直し、次フレームからスパークラインが反映される
      doRestartService()
    },
    onShowOnlyOnMobileChange = { checked ->
      savePref { putBoolean(C.PREF_KEY_SHOW_ONLY_ON_MOBILE, checked) }
      mUiState = mUiState.copy(showOnlyOnMobile = checked)
      // 現在のネットワーク種別と照らして即座に visibility を反映
      doRestartService()
    },
    onMobileOnlyMeterChange = { checked ->
      savePref { putBoolean(C.PREF_KEY_MOBILE_ONLY_METER, checked) }
      mUiState = mUiState.copy(mobileOnlyMeter = checked)
      // 次回 gatherTraffic からモバイル計測に切替
      doRestartService()
    },
    onShowNetworkTypeIconChange = { checked ->
      savePref { putBoolean(C.PREF_KEY_SHOW_NETWORK_TYPE_ICON, checked) }
      mUiState = mUiState.copy(showNetworkTypeIcon = checked)
      // 次フレームからバッジ表示切替
      doRestartService()
    },
    onTextSizeDelta = { delta -> updateTextSize(delta) },
    onXPosChange = { pos ->
      if (pos == mUiState.xPos) return@MainScreenCallbacks
      savePref { putInt(C.PREF_KEY_X_POS, pos) }
      mUiState = mUiState.copy(xPos = pos)
      doRestartService()
    },
    onOverlayAtBottomChange = { atBottom ->
      if (atBottom == mUiState.overlayAtBottom) return@MainScreenCallbacks
      savePref { putBoolean(C.PREF_KEY_OVERLAY_AT_BOTTOM, atBottom) }
      mUiState = mUiState.copy(overlayAtBottom = atBottom)
      // restart 内の applyOverlayLayoutConfig で gravity が再適用される
      doRestartService()
    },
    onOverlayOpacityChange = { opacity ->
      if (opacity == mUiState.overlayOpacity) return@MainScreenCallbacks
      savePref { putInt(C.PREF_KEY_OVERLAY_OPACITY, opacity) }
      mUiState = mUiState.copy(overlayOpacity = opacity)
      doRestartService()
    },
    onOverlayBgColorChange = { color ->
      if (color == mUiState.overlayBgColor) return@MainScreenCallbacks
      savePref { putInt(C.PREF_KEY_OVERLAY_BG_COLOR, color) }
      mUiState = mUiState.copy(overlayBgColor = color)
      doRestartService()
    },
    onDisplayStyleChange = { style ->
      if (style == mUiState.displayStyle) return@MainScreenCallbacks
      savePref { putInt(C.PREF_KEY_DISPLAY_STYLE, style) }
      mUiState = mUiState.copy(displayStyle = style)
      doRestartService()
    },
    onIntervalChange = { interval ->
      if (interval == mUiState.intervalMs) return@MainScreenCallbacks
      savePref { putInt(C.PREF_KEY_INTERVAL_MSEC, interval) }
      mUiState = mUiState.copy(intervalMs = interval)
      doRestartService()
    },
    onBarMaxChange = { speed ->
      if (speed == mUiState.barMaxKB) return@MainScreenCallbacks
      savePref { putInt(C.PREF_KEY_BAR_MAX_SPEED_KB, speed) }
      mUiState = mUiState.copy(barMaxKB = speed)
      doRestartService()
    },
    onUnitTypeChange = { bps ->
      if (bps == mUiState.unitTypeBps) return@MainScreenCallbacks
      savePref { putBoolean(C.PREF_KEY_UNIT_TYPE_BPS, bps) }
      mUiState = mUiState.copy(unitTypeBps = bps)
      doRestartService()
    },
    onAutoUnitScaleChange = { checked ->
      savePref { putBoolean(C.PREF_KEY_AUTO_UNIT_SCALE, checked) }
      mUiState = mUiState.copy(autoUnitScale = checked)
      // 次フレームから表示単位が切替
      doRestartService()
    },
    onPreviewSliderChange = { progress ->
      val kb = progress / 10
      val kbd1 = progress % 10
      mUiState = mUiState.copy(
        previewSlider = progress,
        previewLabel = "$kb.${kbd1}KB",
      )
      startSnapshot(kb.toLong() * 1024 + kbd1.toLong() * 100)
    },
    onSampleClick = { kb ->
      val progress = kb * 10
      mUiState = mUiState.copy(
        previewSlider = progress,
        previewLabel = "$kb.0KB",
      )
      startSnapshot(kb.toLong() * 1024)
    },
    onPreviewLiveModeChange = { live ->
      mUiState = mUiState.copy(previewLiveMode = live)
      if (live) {
        // 前のセッションでスナップショットが残っていたらクリア
        doRestartService()
        startLiveTrafficPolling()
      } else {
        stopLiveTrafficPolling()
      }
    },
    onStart = {
      // 明示的な開始操作なので、通知ボタン/QSタイルで非表示にしていた状態を解除して確実に表示する
      savePref { putBoolean(C.PREF_KEY_USER_WANTS_VISIBLE, true) }
      doRestartService()
    },
    onStop = { doStopService() },
    onRestart = {
      savePref { putBoolean(C.PREF_KEY_USER_WANTS_VISIBLE, true) }
      doStopService()
      doRestartService()
    },
    onToggleDebug = {
      val newValue = !mUiState.debugMode
      TkConfig.debugMode = newValue
      savePref { putBoolean(C.PREF_KEY_DEBUG_MODE, newValue) }
      mUiState = mUiState.copy(debugMode = newValue)
    },
    onConfigureBattery = { openBatteryOptimizationSettings() },
  )

  private inline fun savePref(action: android.content.SharedPreferences.Editor.() -> Unit) {
    PreferenceManager.getDefaultSharedPreferences(this).edit(commit = false, action = action)
  }

  private fun doBindService() {

    val serviceIntent = Intent(this, LayerService::class.java)

    MyLog.d("MainActivity: startService of LayerService")
    if (Build.VERSION.SDK_INT >= 26) {
      startForegroundService(serviceIntent)
    } else {
      startService(serviceIntent)
    }

    MyLog.d("MainActivity: bindService of LayerService")
    if (bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE)) {
      mBound = true
    }
  }

  private fun requestOverlayPermission() {
    val intent =
      Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
    overlayPermissionLauncher.launch(intent)
  }

  override fun onPause() {

    // 画面外ではライブポーリング停止
    stopLiveTrafficPolling()

    // プレビュー状態の解除
    if (mServiceIF != null) {
      try {
        mServiceIF!!.restart()
      } catch (e: RemoteException) {
        MyLog.e(e)
      }
    }

    super.onPause()
  }

  override fun onDestroy() {

    unbindServiceIfBound()

    super.onDestroy()
  }

  /**
   * bind 済みなら unbind する。
   * onServiceConnected 前でも bind は生きているため、
   * 接続状態(mServiceIF)ではなく mBound で判定する(接続状態判定ではリークが発生する)。
   */
  private fun unbindServiceIfBound() {
    if (mBound) {
      unbindService(mServiceConnection)
      mBound = false
      mServiceIF = null
    }
  }

  private fun doStopService() {

    if (mServiceIF != null) {
      try {
        mServiceIF!!.stop()
      } catch (e: RemoteException) {
        MyLog.e(e)
      }
    }

    unbindServiceIfBound()
  }

  private fun doRestartService() {

    MyLog.d("MainActivity.doRestartService")

    if (mServiceIF != null) {
      try {
        mServiceIF!!.restart()
      } catch (e: RemoteException) {
        MyLog.e(e)
      }
    } else {
      // rebind
      doBindService()
    }

    // プレビュー状態を解除する(ラベルとスライダー位置の両方をリセットして表示のずれを防ぐ)
    mUiState = mUiState.copy(previewLabel = "-", previewSlider = 0)
  }

  private fun updateTextSize(delta: Int) {

    val newSize = Config.textSizeSp + delta
    if (newSize < 6 || newSize > 24) return
    Config.textSizeSp = newSize

    savePref { putInt(C.PREF_KEY_TEXT_SIZE_SP, Config.textSizeSp) }
    mUiState = mUiState.copy(textSizeSp = Config.textSizeSp)

    // restart() が Config 再読込と強制再描画(showTrafficWithForceRedraw)を行うため、
    // 旧実装の forceRedraw(1) + 1ms 遅延のダンスは不要
    doRestartService()
  }

  private fun startSnapshot(previewBytes: Long) {
    if (mServiceIF != null) {
      try {
        mServiceIF!!.startSnapshot(previewBytes)
      } catch (e: RemoteException) {
        MyLog.e(e)
      }
    }
  }
}
