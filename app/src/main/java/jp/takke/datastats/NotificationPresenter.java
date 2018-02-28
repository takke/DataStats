package jp.takke.datastats;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.v4.app.NotificationCompat;

import java.lang.ref.WeakReference;

import jp.takke.util.MyLog;

class NotificationPresenter {

    private static final int MY_NOTIFICATION_ID = 1;

    private final static String CHANNEL_ID = "resident";

    private final WeakReference<Service> mServiceRef;


    /*package*/ NotificationPresenter(Service service) {

        mServiceRef = new WeakReference<>(service);
    }

    /*package*/ void showNotification() {

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

        builder.setSmallIcon(R.drawable.ic_launcher);
        builder.setOngoing(true);
        builder.setPriority(NotificationCompat.PRIORITY_MIN);

        builder.setContentTitle(service.getString(R.string.resident_service_running));
//        builder.setContentText("表示・非表示を切り替える");
        builder.setContentIntent(pendingIntent);

        // channel
        // TODO 通知チャンネルは別途作成すること
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "常駐通知", NotificationManager.IMPORTANCE_LOW);
            final NotificationManager nm = ((NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE));
            assert nm != null;
            nm.createNotificationChannel(channel);
        }

        service.startForeground(MY_NOTIFICATION_ID, builder.build());
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
