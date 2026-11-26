package com.webaa.extpack;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Telefon yeniden acilinca veya uygulama guncellenince zamanlanmis bildirimleri tekrar kurar. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            String a = i.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(a) || "android.intent.action.QUICKBOOT_POWERON".equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
                ExtNotif.restore(c);
            }
        } catch (Throwable ignored) {
        }
    }
}
