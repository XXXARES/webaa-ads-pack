package com.webaa.extpack;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Zamanlanan bildirimin saati gelince bildirimi gosterir. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            int id = i.getIntExtra("id", 0);
            String t = i.getStringExtra("title");
            String b = i.getStringExtra("body");
            ExtNotif.show(c, id, t != null ? t : "", b != null ? b : "", i.getStringExtra("url"));
            ExtNotif.done(c, id);
        } catch (Throwable ignored) {
        }
    }
}
