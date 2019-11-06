package jp.takke.datastats

import android.app.Application

import jp.takke.util.MyLog

@Suppress("unused")
class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // ログの設定
        MyLog.setContext(this)

        MyLog.i("start")
    }

    override fun onTerminate() {
        super.onTerminate()

        MyLog.close()
    }
}
