package com.webaa.extpack;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/**
 * Sayfadan parmak izi / yuz tanima: window.WebaaBioNative. Sistemin kendi BiometricPrompt penceresini kullanir (Android 9+),
 * ek kutuphane gerekmez. Android 11 ve ustunde cihaz PIN'i de kabul edilir. Sonuc window.__webaaBioCb(id, "ok" | "canceled" | "error").
 */
public class BioBridge {
    private final Activity act;
    private final WebView web;

    BioBridge(Activity a, WebView w) {
        act = a;
        web = w;
    }

    private void reply(final int id, final String r) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    web.evaluateJavascript("window.__webaaBioCb&&window.__webaaBioCb(" + id + ",\"" + r + "\")", null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @JavascriptInterface
    public boolean available() {
        try {
            if (Build.VERSION.SDK_INT < 28) return false;
            if (Build.VERSION.SDK_INT == 28) return act.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
            BiometricManager m = act.getSystemService(BiometricManager.class);
            if (m == null) return false;
            if (Build.VERSION.SDK_INT >= 30) {
                return m.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS;
            }
            return legacyCan(m);
        } catch (Throwable e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean legacyCan(BiometricManager m) {
        return m.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
    }

    @JavascriptInterface
    public void authenticate(final int id, final String title, final String sub) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT < 28) {
                    reply(id, "error");
                    return;
                }
                try {
                    BiometricPrompt.Builder b = new BiometricPrompt.Builder(act);
                    b.setTitle(title == null || title.length() == 0 ? "Kimlik doğrulama" : title);
                    if (sub != null && sub.length() > 0) b.setSubtitle(sub);
                    if (Build.VERSION.SDK_INT >= 30) {
                        b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                    } else {
                        b.setNegativeButton("İptal", act.getMainExecutor(), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                reply(id, "canceled");
                            }
                        });
                    }
                    b.build().authenticate(new CancellationSignal(), act.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            reply(id, "ok");
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence msg) {
                            reply(id, code == 10 || code == 5 ? "canceled" : "error");
                        }
                    });
                } catch (Throwable e) {
                    reply(id, "error");
                }
            }
        });
    }
}
