package com.webaa.extpack;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Baglantili bildirime dokununca: adresi saklar, uygulamayi one getirir ve kapanir. ExtBoot adresi sayfada acar. */
public class OpenActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String u = getIntent() != null ? getIntent().getStringExtra("url") : null;
            if (safe(u)) ExtBoot.pendingUrl = u.trim();
            Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (i != null) startActivity(i);
        } catch (Throwable ignored) {
        }
        finish();
    }

    /** Yalnizca http(s), #sayfa ve goreli yollar; javascript:, data:, file: gibi semalar acilmaz. */
    static boolean safe(String u) {
        if (u == null) return false;
        String s = u.trim();
        if (s.length() == 0) return false;
        if (s.matches("(?i)^https?://.*")) return true;
        return !s.matches("^[^/?#]*:.*") && s.indexOf('\\') < 0 && s.indexOf('\n') < 0 && s.indexOf('\t') < 0 && s.indexOf('\r') < 0;
    }
}
