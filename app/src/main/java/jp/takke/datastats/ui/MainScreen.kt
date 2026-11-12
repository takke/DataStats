package jp.takke.datastats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jp.takke.datastats.MyTrafficUtil
import jp.takke.datastats.R

/**
 * 設定画面の UI 状態。プレゼンテーション層への入力データ。
 */
data class ConfigUiState(
  val autoStartOnBoot: Boolean = true,
  val hideWhenInFullscreen: Boolean = false,
  val logBar: Boolean = true,
  val interpolateMode: Boolean = false,
  val sparklineMode: Boolean = false,
  val showOnlyOnMobile: Boolean = false,
  val mobileOnlyMeter: Boolean = false,
  val showNetworkTypeIcon: Boolean = false,
  val textSizeSp: Int = 10,
  val xPos: Int = 100,
  val overlayAtBottom: Boolean = false,
  val overlayOpacity: Int = 100,
  val overlayBgColor: Int = 0x66000000,
  val displayStyle: Int = 0,
  val intervalMs: Int = 1000,
  val barMaxKB: Int = 10240,
  val unitTypeBps: Boolean = false,
  val autoUnitScale: Boolean = false,
  val debugMode: Boolean = false,
  // 電池の最適化が有効(=未除外)かどうか。true のとき警告カードを表示する
  val batteryOptimizationEnabled: Boolean = false,
  val previewLabel: String = "-",
  val previewSlider: Int = 0,
  /** プレビュー領域を実トラフィックのライブ表示にするか(false = 従来のスライダー/サンプル注入) */
  val previewLiveMode: Boolean = true,
  /** ライブ表示中のアップロード速度 [B/s] */
  val previewLiveTxBps: Long = 0,
  /** ライブ表示中のダウンロード速度 [B/s] */
  val previewLiveRxBps: Long = 0,
)

/**
 * 設定画面のコールバック集約。UI から呼ばれる操作を Activity へ伝える。
 */
data class MainScreenCallbacks(
  val onAutoStartOnBootChange: (Boolean) -> Unit = {},
  val onHideWhenInFullscreenChange: (Boolean) -> Unit = {},
  val onLogBarChange: (Boolean) -> Unit = {},
  val onInterpolateChange: (Boolean) -> Unit = {},
  val onSparklineChange: (Boolean) -> Unit = {},
  val onShowOnlyOnMobileChange: (Boolean) -> Unit = {},
  val onMobileOnlyMeterChange: (Boolean) -> Unit = {},
  val onShowNetworkTypeIconChange: (Boolean) -> Unit = {},
  val onTextSizeDelta: (Int) -> Unit = {},
  val onXPosChange: (Int) -> Unit = {},
  val onOverlayAtBottomChange: (Boolean) -> Unit = {},
  val onOverlayOpacityChange: (Int) -> Unit = {},
  val onOverlayBgColorChange: (Int) -> Unit = {},
  val onDisplayStyleChange: (Int) -> Unit = {},
  val onIntervalChange: (Int) -> Unit = {},
  val onBarMaxChange: (Int) -> Unit = {},
  val onUnitTypeChange: (Boolean) -> Unit = {},
  val onAutoUnitScaleChange: (Boolean) -> Unit = {},
  val onPreviewSliderChange: (Int) -> Unit = {},
  val onSampleClick: (Int) -> Unit = {},
  val onPreviewLiveModeChange: (Boolean) -> Unit = {},
  val onStart: () -> Unit = {},
  val onStop: () -> Unit = {},
  val onRestart: () -> Unit = {},
  val onToggleDebug: () -> Unit = {},
  val onConfigureBattery: () -> Unit = {},
)

private val INTERVAL_OPTIONS = intArrayOf(500, 1000, 1500, 2000, 5000, 10000)
private val BAR_MAX_OPTIONS = intArrayOf(10, 50, 100, 500, 1024, 2048, 5120, 10240)
private val SAMPLE_KB = intArrayOf(1, 20, 50, 80, 100)

// 背景色プリセット(ARGB)。BG_COLOR_DEFAULT は C.DEFAULT_OVERLAY_BG_COLOR と同値
private const val BG_COLOR_DEFAULT = 0x66000000
private const val BG_COLOR_DARK = 0xCC000000.toInt()
private const val BG_COLOR_TRANSPARENT = 0x00000000
private const val BG_COLOR_NAVY = 0x663F51B5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
  state: ConfigUiState,
  callbacks: MainScreenCallbacks,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = { OverflowMenu(state, callbacks) },
      )
    },
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (state.batteryOptimizationEnabled) {
        BatteryWarningCard(callbacks)
      }
      DisplaySection(state, callbacks)
      BehaviorSection(state, callbacks)
      StartupSection(state, callbacks)
      PreviewSection(state, callbacks)
      Spacer(Modifier.height(8.dp))
    }
  }
}

@Composable
private fun OverflowMenu(
  state: ConfigUiState,
  callbacks: MainScreenCallbacks,
) {
  var expanded by remember { mutableStateOf(false) }
  IconButton(onClick = { expanded = true }) {
    Icon(Icons.Filled.MoreVert, contentDescription = null)
  }
  DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
    DropdownMenuItem(
      text = { Text(stringResource(R.string.config_start)) },
      onClick = {
        expanded = false
        callbacks.onStart()
      },
    )
    DropdownMenuItem(
      text = { Text(stringResource(R.string.config_stop)) },
      onClick = {
        expanded = false
        callbacks.onStop()
      },
    )
    DropdownMenuItem(
      text = { Text(stringResource(R.string.config_restart)) },
      onClick = {
        expanded = false
        callbacks.onRestart()
      },
    )
    HorizontalDivider()
    DropdownMenuItem(
      text = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(R.string.config_debug_mode))
          Spacer(Modifier.width(8.dp))
          Switch(checked = state.debugMode, onCheckedChange = null)
        }
      },
      onClick = {
        expanded = false
        callbacks.onToggleDebug()
      },
    )
  }
}

@Composable
private fun DisplaySection(state: ConfigUiState, callbacks: MainScreenCallbacks) {
  SectionCard(title = stringResource(R.string.section_display)) {
    // 文字サイズ(ステッパー)
    SettingRow(
      title = stringResource(R.string.config_text_size),
      description = stringResource(R.string.desc_text_size),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${state.textSizeSp}sp", modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { callbacks.onTextSizeDelta(-1) }) {
          Icon(Icons.Filled.Remove, contentDescription = null)
        }
        IconButton(onClick = { callbacks.onTextSizeDelta(+1) }) {
          Icon(Icons.Filled.Add, contentDescription = null)
        }
      }
    }
    RowDivider()

    // X 位置(スライダー)
    SettingRow(
      title = stringResource(R.string.config_pos),
      description = stringResource(R.string.desc_pos),
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Slider(
        value = state.xPos.toFloat(),
        onValueChange = { callbacks.onXPosChange(it.toInt()) },
        valueRange = 0f..100f,
        steps = 99,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(8.dp))
      Text("${state.xPos}%", modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
    }
    RowDivider()

    // 縦位置(上端 / 下端)
    SettingRow(
      title = stringResource(R.string.config_v_pos),
      description = stringResource(R.string.desc_v_pos),
    ) {
      DropdownSelector(
        current = stringResource(
          if (state.overlayAtBottom) R.string.v_pos_bottom else R.string.v_pos_top,
        ),
        options = listOf(
          stringResource(R.string.v_pos_top) to false,
          stringResource(R.string.v_pos_bottom) to true,
        ),
        onSelected = { callbacks.onOverlayAtBottomChange(it) },
      )
    }
    RowDivider()

    // 不透明度(スライダー)
    SettingRow(
      title = stringResource(R.string.config_opacity),
      description = stringResource(R.string.desc_opacity),
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Slider(
        value = state.overlayOpacity.toFloat(),
        onValueChange = { callbacks.onOverlayOpacityChange(it.toInt()) },
        valueRange = 20f..100f,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(8.dp))
      Text("${state.overlayOpacity}%", modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
    }
    RowDivider()

    // 背景色(プリセット)
    SettingRow(
      title = stringResource(R.string.config_bg_color),
      description = stringResource(R.string.desc_bg_color),
    ) {
      val bgOptions = listOf(
        stringResource(R.string.bg_color_default) to BG_COLOR_DEFAULT,
        stringResource(R.string.bg_color_dark) to BG_COLOR_DARK,
        stringResource(R.string.bg_color_transparent) to BG_COLOR_TRANSPARENT,
        stringResource(R.string.bg_color_navy) to BG_COLOR_NAVY,
      )
      DropdownSelector(
        current = bgOptions.firstOrNull { it.second == state.overlayBgColor }?.first
          ?: bgOptions[0].first,
        options = bgOptions,
        onSelected = { callbacks.onOverlayBgColorChange(it) },
      )
    }
    RowDivider()

    // 表示スタイル(テキスト+バー / テキストのみ / バーのみ)
    SettingRow(
      title = stringResource(R.string.config_display_style),
      description = stringResource(R.string.desc_display_style),
    ) {
      val styleOptions = listOf(
        stringResource(R.string.style_bar_and_text) to 0,
        stringResource(R.string.style_text_only) to 1,
        stringResource(R.string.style_bar_only) to 2,
      )
      DropdownSelector(
        current = styleOptions.firstOrNull { it.second == state.displayStyle }?.first
          ?: styleOptions[0].first,
        options = styleOptions,
        onSelected = { callbacks.onDisplayStyleChange(it) },
      )
    }
    RowDivider()

    // 通信速度の単位(ドロップダウン)
    SettingRow(
      title = stringResource(R.string.config_unit_type),
      description = stringResource(R.string.desc_unit_type),
    ) {
      DropdownSelector(
        current = if (state.unitTypeBps) "Kbps" else "KB/s",
        options = listOf("KB/s" to false, "Kbps" to true),
        onSelected = { callbacks.onUnitTypeChange(it) },
      )
    }
    RowDivider()

    // 単位の自動スケーリング(1MB/s 以上で MB/GB に切替)
    SwitchRow(
      title = stringResource(R.string.config_auto_unit_scale),
      description = stringResource(R.string.desc_auto_unit_scale),
      checked = state.autoUnitScale,
      onCheckedChange = callbacks.onAutoUnitScaleChange,
    )
    RowDivider()

    // バーの最大速度(ドロップダウン)
    SettingRow(
      title = stringResource(R.string.config_bar_max_kb),
      description = stringResource(R.string.desc_bar_max_kb),
    ) {
      DropdownSelector(
        current = formatBarMax(state.barMaxKB),
        options = BAR_MAX_OPTIONS.map { formatBarMax(it) to it },
        onSelected = { callbacks.onBarMaxChange(it) },
      )
    }
    RowDivider()

    // バーの対数表示
    SwitchRow(
      title = stringResource(R.string.config_logarithm_bar),
      description = stringResource(R.string.desc_logarithm_bar),
      checked = state.logBar,
      onCheckedChange = callbacks.onLogBarChange,
    )
    RowDivider()

    // 速度履歴のミニグラフ
    SwitchRow(
      title = stringResource(R.string.config_sparkline),
      description = stringResource(R.string.desc_sparkline),
      checked = state.sparklineMode,
      onCheckedChange = callbacks.onSparklineChange,
    )
    RowDivider()

    // フルスクリーン時に非表示
    SwitchRow(
      title = stringResource(R.string.config_hide_when_in_fullscreen),
      description = stringResource(R.string.desc_hide_when_in_fullscreen),
      checked = state.hideWhenInFullscreen,
      onCheckedChange = callbacks.onHideWhenInFullscreenChange,
    )
    RowDivider()

    // ネットワーク種別バッジ(W/M/E/V)
    SwitchRow(
      title = stringResource(R.string.config_show_network_type_icon),
      description = stringResource(R.string.desc_show_network_type_icon),
      checked = state.showNetworkTypeIcon,
      onCheckedChange = callbacks.onShowNetworkTypeIconChange,
    )
  }
}

@Composable
private fun BehaviorSection(state: ConfigUiState, callbacks: MainScreenCallbacks) {
  SectionCard(title = stringResource(R.string.section_behavior)) {
    // 更新間隔
    SettingRow(
      title = stringResource(R.string.config_interval),
      description = stringResource(R.string.desc_interval),
    ) {
      DropdownSelector(
        current = formatInterval(state.intervalMs),
        options = INTERVAL_OPTIONS.map { formatInterval(it) to it },
        onSelected = { callbacks.onIntervalChange(it) },
      )
    }
    RowDivider()

    // 補間モード(logBar が ON の場合のみ有効)
    SwitchRow(
      title = stringResource(R.string.config_interpolate),
      description = stringResource(R.string.desc_interpolate),
      checked = state.interpolateMode,
      enabled = state.logBar,
      onCheckedChange = callbacks.onInterpolateChange,
    )
    RowDivider()

    // モバイル通信時のみ表示
    SwitchRow(
      title = stringResource(R.string.config_show_only_on_mobile),
      description = stringResource(R.string.desc_show_only_on_mobile),
      checked = state.showOnlyOnMobile,
      onCheckedChange = callbacks.onShowOnlyOnMobileChange,
    )
    RowDivider()

    // モバイル通信量のみ計測
    SwitchRow(
      title = stringResource(R.string.config_mobile_only_meter),
      description = stringResource(R.string.desc_mobile_only_meter),
      checked = state.mobileOnlyMeter,
      onCheckedChange = callbacks.onMobileOnlyMeterChange,
    )
  }
}

@Composable
private fun StartupSection(state: ConfigUiState, callbacks: MainScreenCallbacks) {
  SectionCard(title = stringResource(R.string.section_startup)) {
    SwitchRow(
      title = stringResource(R.string.config_auto_start_on_boot),
      description = stringResource(R.string.desc_auto_start_on_boot),
      checked = state.autoStartOnBoot,
      onCheckedChange = callbacks.onAutoStartOnBootChange,
    )
  }
}

@Composable
private fun PreviewSection(state: ConfigUiState, callbacks: MainScreenCallbacks) {
  SectionCard(title = stringResource(R.string.preview)) {
    // ライブ表示 / 注入モードの切替トグル
    SwitchRow(
      title = stringResource(R.string.preview_live_mode),
      description = stringResource(R.string.preview_live_mode_desc),
      checked = state.previewLiveMode,
      onCheckedChange = callbacks.onPreviewLiveModeChange,
    )
    RowDivider()

    if (state.previewLiveMode) {
      LivePreviewContent(state)
    } else {
      InjectPreviewContent(state, callbacks)
    }
  }
}

@Composable
private fun LivePreviewContent(state: ConfigUiState) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceEvenly,
  ) {
    LiveTrafficItem(
      label = stringResource(R.string.preview_live_upload),
      valueText = formatBytesPerSec(state.previewLiveTxBps),
    )
    LiveTrafficItem(
      label = stringResource(R.string.preview_live_download),
      valueText = formatBytesPerSec(state.previewLiveRxBps),
    )
  }
}

@Composable
private fun LiveTrafficItem(label: String, valueText: String) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      valueText,
      style = MaterialTheme.typography.titleMedium,
    )
  }
}

@Composable
private fun InjectPreviewContent(state: ConfigUiState, callbacks: MainScreenCallbacks) {
  Text(
    stringResource(R.string.preview_hint),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Spacer(Modifier.height(12.dp))

  // サンプルボタン
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    SAMPLE_KB.forEach { kb ->
      FilledTonalButton(
        onClick = { callbacks.onSampleClick(kb) },
        modifier = Modifier.weight(1f),
      ) {
        Text("${kb}KB")
      }
    }
  }
  Spacer(Modifier.height(8.dp))

  // スライダー + プレビューラベル
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Slider(
      value = state.previewSlider.toFloat(),
      onValueChange = { callbacks.onPreviewSliderChange(it.toInt()) },
      valueRange = 0f..1200f,
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(8.dp))
    Text(
      state.previewLabel,
      modifier = Modifier.width(72.dp),
      textAlign = TextAlign.End,
    )
  }
}

private fun formatBytesPerSec(bps: Long): String {
  if (bps < 0) return "-"
  // オーバーレイと同じフォーマッタを使い、単位設定(Kbps / 自動スケーリング)を反映する
  return MyTrafficUtil.formatSpeedText(bps)
}

//-----------------------------------------------------------
// 共通コンポーネント
//-----------------------------------------------------------

/**
 * 電池の最適化が有効なままのときに表示する警告カード。
 * 最適化が有効だと、プロセス kill 後にフォアグラウンドサービスを再開できず
 * メーターが勝手に止まることがあるため、設定画面への導線を常時出しておく。
 */
@Composable
private fun BatteryWarningCard(callbacks: MainScreenCallbacks) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.errorContainer,
      contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        stringResource(R.string.battery_warning_title),
        style = MaterialTheme.typography.titleMedium,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        stringResource(R.string.battery_warning_desc),
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(Modifier.height(8.dp))
      FilledTonalButton(onClick = callbacks.onConfigureBattery) {
        Text(stringResource(R.string.onboarding_configure))
      }
    }
  }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.height(8.dp))
      content()
    }
  }
}

@Composable
private fun SettingRow(
  title: String,
  description: String? = null,
  trailing: @Composable (() -> Unit)? = null,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      if (description != null) {
        Text(
          description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (trailing != null) {
      Spacer(Modifier.width(8.dp))
      trailing()
    }
  }
}

@Composable
private fun SwitchRow(
  title: String,
  description: String,
  checked: Boolean,
  enabled: Boolean = true,
  onCheckedChange: (Boolean) -> Unit,
) {
  SettingRow(title = title, description = description) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
  }
}

@Composable
private fun <T> DropdownSelector(
  current: String,
  options: List<Pair<String, T>>,
  onSelected: (T) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    OutlinedButton(onClick = { expanded = true }) {
      Text(current)
      Spacer(Modifier.width(4.dp))
      Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { (label, value) ->
        DropdownMenuItem(
          text = { Text(label) },
          onClick = {
            expanded = false
            onSelected(value)
          },
        )
      }
    }
  }
}

@Composable
private fun RowDivider() {
  Spacer(Modifier.height(8.dp))
  HorizontalDivider()
  Spacer(Modifier.height(8.dp))
}

// String.format はロケールによって非 ASCII 数字になるため文字列テンプレートで組み立てる
private fun formatInterval(ms: Int): String = "${ms / 1000}.${ms % 1000 / 100}sec"

private fun formatBarMax(kb: Int): String =
  if (kb >= 1024) "${kb / 1024}MB/s" else "${kb}KB/s"
