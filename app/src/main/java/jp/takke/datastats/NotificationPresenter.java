package jp.takke.datastats;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;

import java.lang.ref.WeakReference;

import jp.takke.util.MyLog;

class NotificationPresenter {

    private static final int MY_NOTIFICATION_ID = 1;

    private final static String CHANNEL_ID = "resident";

    private final WeakReference<Service> mServiceRef;


    /*package*/ NotificationPresenter(Service service) {

        mServiceRef = new WeakReference<>(service);
    }

    /*package*/ void showNotification(boolean visibleOverlayView) {

        MyLog.d("showNotification");

        final Service service = mServiceRef.get();
        if (service == null) {
            return;
        }

        // 通知ウインドウをクリックした際に起動するインテント
        final Intent intent = new Intent(service, MainActivity.class);
        final PendingIntent pendingIntent = PendingIntent.getActivity(service, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT);

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(service.getApplicationContext(), CHANNEL_ID);

        // カスタムレイアウト生成
        {
            final RemoteViews remoteViews = new RemoteViews(service.getPackageName(), R.layout.custom_notification);

            // show button
            if (!visibleOverlayView) {
                final Intent switchIntent = new Intent(service, SwitchButtonReceiver.class);
                switchIntent.setAction("show");
                final PendingIntent switchPendingIntent = PendingIntent.getBroadcast(service, 0, switchIntent, 0);
                remoteViews.setOnClickPendingIntent(R.id.show_button, switchPendingIntent);
                remoteViews.setViewVisibility(R.id.show_button, View.VISIBLE);
            } else {
                remoteViews.setViewVisibility(R.id.show_button, View.GONE);
            }

            // hide button
            if (visibleOverlayView) {
                final Intent switchIntent = new Intent(service, SwitchButtonReceiver.class);
                switchIntent.setAction("hide");
                final PendingIntent switchPendingIntent = PendingIntent.getBroadcast(service, 0, switchIntent, 0);
                remoteViews.setOnClickPendingIntent(R.id.hide_button, switchPendingIntent);
                remoteViews.setViewVisibility(R.id.hide_button, View.VISIBLE);
            } else {
                remoteViews.setViewVisibility(R.id.hide_button, View.GONE);
            }

            builder.setContentIntent(null);
            builder.setContent(remoteViews);
        }


        // 端末の通知エリア(上部のアイコンが並ぶ部分)に本アプリのアイコンを表示しないようにemptyなdrawableを指定する
        builder.setSmallIcon(R.drawable.transparent_image);
        builder.setOngoing(true);
        builder.setPriority(NotificationCompat.PRIORITY_MIN);

        builder.setContentTitle(service.getString(R.string.resident_service_running));
//        builder.setContentText("表示・非表示を切り替える");
        builder.setContentIntent(pendingIntent);

        service.startForeground(MY_NOTIFICATION_ID, builder.build());
    }

    /*package*/ void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final Service service = mServiceRef.get();
            if (service == null) {
                return;
            }

            final NotificationChannel channel = new NotificationChannel(CHANNEL_ID, service.getString(R.string.resident_notification),
                    NotificationManager.IMPORTANCE_LOW);
            final NotificationManager nm = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
            assert nm != null;
            nm.createNotificationChannel(channel);
        }
    }

    /*package*/ void hideNotification() {

        MyLog.d("hideNotification");

        final Service service = mServiceRef.get();
        if (service == null) {
            return;
        }

        final NotificationManager nm = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
        assert nm != null;
        nm.cancel(MY_NOTIFICATION_ID);
    }

}
