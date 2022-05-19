package jp.takke.datastats

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import jp.takke.util.MyLog
import jp.takke.util.TkUtil
import java.lang.ref.WeakReference

internal class NotificationPresenter(service: Service) {

    private val mServiceRef: WeakReference<Service> = WeakReference(service)


    fun showNotification(visibleOverlayView: Boolean) {

        MyLog.d("showNotification")

        val service = mServiceRef.get() ?: return

        // 通知ウインドウをクリックした際に起動するインテント
        val intent = Intent(service, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(service, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT or TkUtil.getPendingIntentImmutableFlagIfOverM())

        val builder = NotificationCompat.Builder(service.applicationContext, CHANNEL_ID)

        // カスタムレイアウト生成
        val notificationLayout = createCustomLayout(service, visibleOverlayView)
        builder.setContentIntent(null)
        builder.setCustomContentView(notificationLayout)

        // Android 12 からは「展開させたくなくても展開できてしまう」ので同じものを設定しておく
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setCustomBigContentView(notificationLayout)
        }

        // 端末の通知エリア(上部のアイコンが並ぶ部分)に本アプリのアイコンを表示しないようにemptyなdrawableを指定する
        builder.setSmallIcon(R.drawable.transparent_image)
        builder.setOngoing(true)
        builder.priority = NotificationCompat.PRIORITY_MIN

        builder.setContentTitle(service.getString(R.string.resident_service_running))
//        builder.setContentText("表示・非表示を切り替える");
        builder.setContentIntent(pendingIntent)

        service.startForeground(MY_NOTIFICATION_ID, builder.build())
    }

    private fun createCustomLayout(service: Service, visibleOverlayView: Boolean): RemoteViews {

        val notificationLayout = RemoteViews(service.packageName, R.layout.custom_notification)

        // Android 12+ ならアイコンは不要(通知エリアが狭いので)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notificationLayout.setViewVisibility(R.id.app_icon, View.GONE)
        }

        // show button
        if (!visibleOverlayView) {
            val switchIntent = Intent(service, SwitchButtonReceiver::class.java)
            switchIntent.action = "show"
            val switchPendingIntent = PendingIntent.getBroadcast(service, 0, switchIntent, TkUtil.getPendingIntentImmutableFlagIfOverM())
            notificationLayout.setOnClickPendingIntent(R.id.show_button, switchPendingIntent)
            notificationLayout.setViewVisibility(R.id.show_button, View.VISIBLE)
        } else {
            notificationLayout.setViewVisibility(R.id.show_button, View.GONE)
        }

        // hide button
        if (visibleOverlayView) {
            val switchIntent = Intent(service, SwitchButtonReceiver::class.java)
            switchIntent.action = "hide"
            val switchPendingIntent = PendingIntent.getBroadcast(service, 0, switchIntent, TkUtil.getPendingIntentImmutableFlagIfOverM())
            notificationLayout.setOnClickPendingIntent(R.id.hide_button, switchPendingIntent)
            notificationLayout.setViewVisibility(R.id.hide_button, View.VISIBLE)
        } else {
            notificationLayout.setViewVisibility(R.id.hide_button, View.GONE)
        }

        // timer (hide and resume) button
        if (visibleOverlayView) {
            val switchIntent = Intent(service, SwitchButtonReceiver::class.java)
            switchIntent.action = "hide_and_resume"
            val switchPendingIntent = PendingIntent.getBroadcast(service, 0, switchIntent, TkUtil.getPendingIntentImmutableFlagIfOverM())
            notificationLayout.setOnClickPendingIntent(R.id.hide_and_resume_button, switchPendingIntent)
            notificationLayout.setViewVisibility(R.id.hide_and_resume_button, View.VISIBLE)
        } else {
            notificationLayout.setViewVisibility(R.id.hide_and_resume_button, View.INVISIBLE)
        }

        return notificationLayout
    }

    fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val service = mServiceRef.get() ?: return

            val channel = NotificationChannel(CHANNEL_ID, service.getString(R.string.resident_notification),
                    NotificationManager.IMPORTANCE_LOW)

            // invisible on lockscreen
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET

            val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun hideNotification() {

        MyLog.d("hideNotification")

        val service = mServiceRef.get() ?: return

        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(MY_NOTIFICATION_ID)
    }

    companion object {

        private const val MY_NOTIFICATION_ID = 1

        private const val CHANNEL_ID = "resident"
    }

}
