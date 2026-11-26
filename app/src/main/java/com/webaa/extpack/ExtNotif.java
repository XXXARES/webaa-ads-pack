package com.webaa.extpack;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.Map;
import org.json.JSONObject;

/** Uygulamaya bagli Android bildirimleri: kanal, aninda gosterim, zamanlama ve iptal (kaynak projedeki Notifier ile ayni davranis). */
final class ExtNotif {
    static final String CH = "webaa_default";

    private ExtNotif() {
    }

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager m = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (m != null && m.getNotificationChannel(CH) == null) {
                m.createNotificationChannel(new NotificationChannel(CH, "Bildirimler", NotificationManager.IMPORTANCE_HIGH));
            }
        } catch (Throwable ignored) {
        }
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    static void show(Context c, int id, String title, String body, String url) {
        try {
            channel(c);
            if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent open;
            if (url != null && url.length() > 0) {
                open = new Intent(c, OpenActivity.class).putExtra("url", url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } else {
                open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
                if (open == null) open = new Intent();
            }
            String t = title != null ? title : "";
            String b = body != null ? body : "";
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
            nb.setSmallIcon(c.getApplicationInfo().icon)
                    .setContentTitle(t)
                    .setContentText(b)
                    .setStyle(new Notification.BigTextStyle().bigText(b))
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, id, open, flags()));
            nm.notify(id, nb.build());
        } catch (Throwable ignored) {
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("webaa_sched", Context.MODE_PRIVATE);
    }

    private static void setAlarm(Context c, int id, String title, String body, String url, long at) {
        Intent i = new Intent(c, ReminderReceiver.class).putExtra("id", id).putExtra("title", title).putExtra("body", body).putExtra("url", url);
        PendingIntent pi = PendingIntent.getBroadcast(c, id, i, flags());
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.set(AlarmManager.RTC_WAKEUP, at, pi);
    }

    /** Zamanlanan bildirim kalici saklanir; telefon yeniden acilinca BootReceiver geri kurar. */
    static void schedule(Context c, int id, String title, String body, long delayMs, String url) {
        try {
            long at = System.currentTimeMillis() + (delayMs > 0 ? delayMs : 0L);
            JSONObject o = new JSONObject().put("t", title).put("b", body).put("u", url == null ? "" : url).put("at", at);
            prefs(c).edit().putString("n" + id, o.toString()).apply();
            setAlarm(c, id, title, body, url, at);
        } catch (Throwable ignored) {
        }
    }

    static void cancel(Context c, int id) {
        try {
            PendingIntent pi = PendingIntent.getBroadcast(c, id, new Intent(c, ReminderReceiver.class), flags());
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pi);
            prefs(c).edit().remove("n" + id).apply();
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(id);
        } catch (Throwable ignored) {
        }
    }

    /** Bildirim gosterildikten sonra kayittan siler. */
    static void done(Context c, int id) {
        prefs(c).edit().remove("n" + id).apply();
    }

    /** Kayitli tum zamanlanmis bildirimleri yeniden kurar; suresi gecenler hemen gosterilir. */
    static void restore(Context c) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("n") || !(e.getValue() instanceof String)) continue;
            try {
                int id = Integer.parseInt(k.substring(1));
                JSONObject o = new JSONObject((String) e.getValue());
                String u = o.optString("u", "");
                long at = o.getLong("at");
                if (at <= now) {
                    show(c, id, o.optString("t"), o.optString("b"), u);
                    done(c, id);
                } else {
                    setAlarm(c, id, o.optString("t"), o.optString("b"), u, at);
                }
            } catch (Throwable ex) {
                p.edit().remove(k).apply();
            }
        }
    }
}
