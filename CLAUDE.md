# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## プロジェクト概要

DataStats は Android 向けのリアルタイム通信速度メーター アプリ。ステータスバーやオーバーレイ上に通信速度を表示するフォアグラウンドサービスとして動作する。

パッケージ名: `jp.takke.datastats` (debug: `jp.takke.datastats.debug`)

## ビルドコマンド

```bash
# デバッグビルド
./gradlew assembleDebug

# リリースビルド (署名設定が必要)
./gradlew assembleRelease

# AAB ビルド
./gradlew bundleRelease
```

テストディレクトリは存在しない（自動テストなし）。

## バージョン管理

バージョン情報は `build.gradle`（ルート）の `ext` ブロックで一元管理:
- `versionName`, `versionCode`, `compileSdkVersion`, `targetSdkVersion`, `minSdkVersion`

ライブラリバージョンは `gradle/libs.versions.toml` で管理。

## アーキテクチャ

### 主要コンポーネント

- **LayerService** (`LayerService.kt`): フォアグラウンドサービス。通信量取得スレッドを管理し、オーバーレイ表示を制御する中核クラス
- **MySurfaceView** (`MySurfaceView.kt`): 補間アニメーション用の描画スレッドを持つ SurfaceView
- **MyRelativeLayout** (`MyRelativeLayout.kt`): オーバーレイのルート。全画面(イマーシブ)検出を担当。API 30+ は WindowInsets、それ以下はサイズ比較
- **MainActivity** (`MainActivity.kt`): 設定画面(Jetpack Compose)。サービスの開始/停止・設定変更・オンボーディングを制御する `ComponentActivity`
- **ui/AppTheme** (`ui/AppTheme.kt`): Material3 + DynamicColors(Android 12+ の Material You)対応の Compose テーマ
- **ui/MainScreen** (`ui/MainScreen.kt`): 表示 / 動作 / 起動 のセクションに分割した Compose 設定画面
- **ui/OnboardingScreen** (`ui/OnboardingScreen.kt`): 初回起動時のオーバーレイ / 通知 / 電池最適化ステップ画面
- **Config** (`Config.kt`): SharedPreferences ベースの設定管理
- **C** (`C.kt`): SharedPreferences キー等の定数
- **MyTrafficUtil** (`MyTrafficUtil.kt`): `TrafficStats` API を使った通信量取得ユーティリティ
- **NotificationPresenter** (`NotificationPresenter.kt`): 通知チャンネルと通知表示の管理
- **OverlayUtil** (`OverlayUtil.kt`): オーバーレイ権限の確認・要求
- **BootReceiver** (`BootReceiver.kt`): 端末起動時の自動起動 / アプリ更新後(`MY_PACKAGE_REPLACED`)の自動再開
- **SwitchButtonReceiver** (`SwitchButtonReceiver.kt`): 通知からのサービス ON/OFF 切り替え

### 通信量取得の流れ

1. `LayerService` が通信量取得スレッドを起動（設定の「更新間隔」に従う）
2. `MyTrafficUtil` で `TrafficStats` API から通信量を取得
3. 取得結果をオーバーレイ（`MySurfaceView`）と通知（`NotificationPresenter`）に反映

### スレッド構成

- **通信量取得スレッド**: `LayerService` 内で管理。起動タイミング: 端末起動時 / 開始ボタン / スリープ復帰時
- **描画スレッド**: `MySurfaceView` 内で管理。補間モードのアニメーション描画用

### AIDL

`ILayerService.aidl` で MainActivity とサービス間の IPC インターフェースを定義。

## 言語構成

コードはすべて Kotlin(旧 Java 実装は全て Kotlin 化済)。新規コードも Kotlin で記述する。コメントは日本語で書く。

## UI レイヤ

設定画面(`MainActivity`)は Jetpack Compose + Material 3(`Theme.Material3.DayNight`)ベース。Android 12+ では `DynamicColors` により Material You(ダイナミックカラー)対応。オーバーレイ描画側(`MySurfaceView`)は従来どおり `SurfaceView` + 手動描画。

## 署名設定

リリースビルドの署名情報は `gradle.properties` に `storeFile`, `storePassword`, `keyAlias`, `keyPassword` として設定する（リポジトリには含まれない）。デバッグビルドはリポジトリ内の `debug.keystore` を使用。
