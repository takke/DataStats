# メモ

設計メモを書いておく。


# 描画スレッド

補間モード(interpolation mode)のアニメーションを実現するために描画スレッドを立てている。

MySurfaceView.java 参照。


# 通信量取得スレッド

「更新間隔」の設定値に従って通信量を取得するスレッド。

従来は更新間隔を AlarmManager を呼び出していたが Android 5.1 から AlarmManager の最短間隔が
5～6 秒程度になったため、本方式に変更した。

AlarmManager は Service の維持のために継続して実行する。

LayerService.java 参照。



## 通信量取得スレッドの起動タイミング

タイミング                       | 実行箇所                                                | 備考
-------------------------------- | ------------------------------------------------------- | ---
端末起動時(自動起動設定ONの場合) | BootReceiver <br>-&gt; LayerService.onCreate+onStartCommand |
メイン画面の「開始」ボタン押下時 | MainActivity.doRestartService <br>-&gt; LayerService.onBind |
端末のスリープからの復帰時       | LayerService.mReceiver |
<!-- 定期起動                         | AlarmManager <br>-&gt; LayerService.onStartCommand | 既存のスレッドがあれば置き換える -->



## 通信量取得スレッドの終了タイミング

タイミング                       | 実行箇所                                                | 備考
-------------------------------- | ------------------------------------------------------- | ---
メイン画面の「停止」ボタン押下時 | MainActivity.doStopService <br>-&gt; LayerService$LocalBinder.stop
端末のスリープ時                 | LayerService.mReceiver

