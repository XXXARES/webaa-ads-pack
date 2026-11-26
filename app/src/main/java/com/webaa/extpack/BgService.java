package com.webaa.extpack;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** "Arka planda calismaya devam et": on plan hizmeti sureci canli tutar; sayfa zamanlayicilari ve sesi surer. */
public class BgService extends Service {
    private static final String CH = "webaa_bg";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CH, "Arka plan", NotificationManager.IMPORTANCE_LOW));
            }
            Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (open == null) open = new Intent();
            int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
            Notification n = b.setContentTitle(getApplicationInfo().loadLabel(getPackageManager()))
                    .setContentText("Arka planda çalışıyor")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentIntent(PendingIntent.getActivity(this, 0, open, pf))
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(1977, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(1977, n);
        } catch (Throwable e) {
            stopSelf();
        }
        // Surec kapanirsa sayfa da gider: hizmet tek basina yeniden baslatilmaz
        return START_NOT_STICKY;
    }

    /** Android 15+: dataSync hizmetinin suresi (24 saatte 6 saat) dolunca durmazsa uygulama cokertilir. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        stopSelf();
    }

    /** Uygulama son uygulamalardan silinince "calisiyor" bildirimi kalmasin. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
    }
}
