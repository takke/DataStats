package jp.takke.datastats

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.preference.PreferenceManager
import jp.takke.util.MyLog

/**
 * クイック設定パネルからオーバーレイの表示 / 非表示を切り替えるタイル。
 *
 * - タイル状態は `C.PREF_KEY_USER_WANTS_VISIBLE` の値と紐づく
 *   (LayerService の `mUserWantsVisible` と同期)。
 * - タップ時は `LayerService` に `show` / `hide` action を送るだけで、
 *   実際の状態遷移と prefs 更新は `LayerService.setUserWantsVisible()` 側で行う。
 * - オーバーレイ権限が付与されていない場合は `STATE_UNAVAILABLE` にし、
 *   タップ時に権限リクエスト画面を開く。
 *
 * TileService は API 24+ のため minSdk 23 では SystemUI から bind されない
 * (manifest 登録は無害)。
 */
@RequiresApi(Build.VERSION_CODES.N)
class OverlayTileService : TileService() {

  override fun onStartListening() {
    super.onStartListening()
    updateTileState()
  }

  override fun onClick() {
    super.onClick()

    if (!OverlayUtil.checkOverlayPermission(this)) {
      // 権限がない場合は設定画面へ導線
      val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName"),
      ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // targetSdk 34+ では Intent 版 startActivityAndCollapse は
        // UnsupportedOperationException を投げるため PendingIntent 版を使う
        val pendingIntent = PendingIntent.getActivity(
          this,
          0,
          intent,
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startActivityAndCollapse(pendingIntent)
      } else {
        @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
        startActivityAndCollapse(intent)
      }
      return
    }

    val pref = PreferenceManager.getDefaultSharedPreferences(this)
    val newVisible = !pref.getBoolean(C.PREF_KEY_USER_WANTS_VISIBLE, true)

    // 状態遷移は LayerService 側で prefs / mUserWantsVisible / 通知を一元管理するため
    // タイルは prefs を書かず、action だけ投げる。
    LayerService.startFromBackground(this, if (newVisible) C.ACTION_SHOW else C.ACTION_HIDE)

    // 楽観的にタイル表示を更新(直後に LayerService から requestTileUpdate() でも呼ばれる)
    setTileState(newVisible)
  }

  private fun updateTileState() {
    val pref = PreferenceManager.getDefaultSharedPreferences(this)
    val visible = pref.getBoolean(C.PREF_KEY_USER_WANTS_VISIBLE, true)
    val hasPermission = OverlayUtil.checkOverlayPermission(this)
    setTileState(visible, hasPermission)
  }

  private fun setTileState(visible: Boolean, hasPermission: Boolean = true) {
    val tile = qsTile ?: return
    tile.state = when {
      !hasPermission -> Tile.STATE_UNAVAILABLE
      visible -> Tile.STATE_ACTIVE
      else -> Tile.STATE_INACTIVE
    }
    tile.label = getString(R.string.tile_label)
    tile.updateTile()
  }

}

/**
 * QS タイルの表示状態更新を要求する。別コンポーネント(LayerService 等)から呼ばれる。
 * タイルが listening 中であれば `onStartListening()` が再度発火し、最新の prefs 値でタイルが更新される。
 *
 * TileService(QS タイル)は API 24+ のみのため、API 23 では何もしない
 * (クラス内 companion に置くと @RequiresApi がクラス全体に効いて呼び出し元が lint エラーになるため
 *  トップレベル関数としている)。
 */
fun requestOverlayTileUpdate(context: Context) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
  try {
    TileService.requestListeningState(
      context.applicationContext,
      ComponentName(context, OverlayTileService::class.java),
    )
  } catch (e: Exception) {
    MyLog.e(e)
  }
}
