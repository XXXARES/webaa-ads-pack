package com.webaa.extpack;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;

/**
 * Uygulama sureci acilirken calisir. Ayarlari assets/webaa_ext.json dosyasindan okur:
 *   bio   : WebView'a "WebaaBioNative" (parmak izi / yuz tanima) koprusu
 *   notif : WebView'a "WebaaAndroid" koprusu: yerel bildirim, zamanlama, iptal, izin
 *   fcm   : ayni kopruye pushToken / subscribe / unsubscribe
 *   seg   : Push icin dil, ulke, Android ve uygulama surumu konularina abonelik
 *   edge  : sistem cubugu bosluklarini veren "WebaaInsetsNative" koprusu (--webaa-top... CSS degiskenleri)
 *   bg    : on plan hizmeti (BgService)
 *   splash: acilis ekrani suresi (ms); bildirim baglantisi bundan sonra acilir
 * Her adim try/catch icindedir: burada hata olsa da uygulama acilir ve calisir.
 */
public class ExtBoot extends ContentProvider implements Application.ActivityLifecycleCallbacks {
    private static final String MAIN = "com.web2apk.Main";

    private boolean bio = false;
    private boolean notif = false;
    private boolean fcm = false;
    private boolean seg = false;
    private boolean edge = false;
    private boolean bg = false;
    private long splashMs = 0L;
    private long attachedAt = 0L;
    private volatile String insets = "0,0,0,0";
    private WeakReference<WebView> webRef;

    /** OpenActivity'nin biraktigi, sayfada acilacak adres. */
    static volatile String pendingUrl = null;

    @Override
    public boolean onCreate() {
        try {
            Context c = getContext();
            if (c == null) return true;
            final Context a = c.getApplicationContext() != null ? c.getApplicationContext() : c;
            if (!(a instanceof Application)) return true;
            if (!readConfig(c)) return true;
            ((Application) a).registerActivityLifecycleCallbacks(this);
            if (notif) ExtNotif.channel(a);
            if (seg) {
                // PushBoot (initOrder 3) Firebase'i baslattiktan sonra calissin diye ana kuyruga birakilir
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            segments(a);
                        } catch (Throwable ignored) {
                            // Firebase baslamadiysa (google-services degerleri yok) konular atlanir
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
            // baslamasa da uygulama acilir
        }
        return true;
    }

    private boolean readConfig(Context c) {
        try {
            InputStream in = c.getAssets().open("webaa_ext.json");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject j = new JSONObject(bo.toString("UTF-8"));
            bio = j.optBoolean("bio", false);
            notif = j.optBoolean("notif", false);
            fcm = j.optBoolean("fcm", false);
            seg = j.optBoolean("seg", false);
            edge = j.optBoolean("edge", false);
            bg = j.optBoolean("bg", false);
            splashMs = Math.max(0L, Math.min(j.optLong("splash", 0L), 15000L));
            return bio || notif || fcm || seg || edge || bg;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Telefonun dili, ulkesi, Android surumu ve uygulama surum kodu Firebase konusu olur; degisenler guncellenir. */
    private void segments(Context c) throws Exception {
        long vc = 1L;
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : (long) pi.versionCode;
        } catch (Throwable ignored) {
        }
        Locale loc = Locale.getDefault();
        String[] raw = { "lang_" + loc.getLanguage(), "country_" + loc.getCountry(), "sdk_" + Build.VERSION.SDK_INT, "app_" + vc };
        Set<String> now = new HashSet<String>();
        for (String t : raw) {
            if (t.endsWith("_")) continue;
            now.add(t.replaceAll("[^A-Za-z0-9_.~%-]", "_"));
        }
        SharedPreferences sp = c.getSharedPreferences("webaa", Context.MODE_PRIVATE);
        Set<String> old = sp.getStringSet("seg", new HashSet<String>());
        FirebaseMessaging fm = FirebaseMessaging.getInstance();
        if (old != null) {
            for (String t : old) {
                if (!now.contains(t)) fm.unsubscribeFromTopic(t);
            }
        }
        for (String t : now) fm.subscribeToTopic(t);
        sp.edit().putStringSet("seg", new HashSet<String>(now)).apply();
    }

    private WebView findWebView(View v) {
        if (v instanceof WebView) return (WebView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                WebView w = findWebView(g.getChildAt(i));
                if (w != null) return w;
            }
        }
        return null;
    }

    private void attach(Activity a) {
        try {
            if (a == null || !MAIN.equals(a.getClass().getName())) return;
            if (!bio && !notif && !fcm && !edge) return;
            WebView w = findWebView(a.findViewById(android.R.id.content));
            if (w == null) return;
            if (webRef != null && webRef.get() == w) return;
            if (bio) w.addJavascriptInterface(new BioBridge(a, w), "WebaaBioNative");
            if (notif || fcm) w.addJavascriptInterface(new Js(a), "WebaaAndroid");
            if (edge) {
                w.addJavascriptInterface(new Insets(), "WebaaInsetsNative");
                watchInsets(a, w);
            }
            webRef = new WeakReference<WebView>(w);
            attachedAt = SystemClock.uptimeMillis();
        } catch (Throwable ignored) {
            // kopru eklenemezse uygulama yine calisir
        }
    }

    public class Insets {
        @JavascriptInterface
        public String get() {
            return insets;
        }
    }

    /** Yerlesim degistikce bosluklari okur; degistiyse sayfaya haber verir. Pencerenin kendi dinleyicilerine dokunmaz. */
    private void watchInsets(final Activity a, final WebView w) {
        final View decor = a.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                try {
                    String s = readInsets(a, decor);
                    if (s != null && !s.equals(insets)) {
                        insets = s;
                        w.evaluateJavascript("window.__webaaInsets&&window.__webaaInsets()", null);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** "ust,alt,sol,sag" (CSS pikseli). */
    @SuppressWarnings("deprecation")
    private String readInsets(Activity a, View decor) {
        WindowInsets in = decor.getRootWindowInsets();
        if (in == null) return null;
        float d = a.getResources().getDisplayMetrics().density;
        int t, b, l, r;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets x = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            t = x.top;
            b = x.bottom;
            l = x.left;
            r = x.right;
        } else {
            t = in.getSystemWindowInsetTop();
            b = in.getSystemWindowInsetBottom();
            l = in.getSystemWindowInsetLeft();
            r = in.getSystemWindowInsetRight();
        }
        return Math.round(t / d) + "," + Math.round(b / d) + "," + Math.round(l / d) + "," + Math.round(r / d);
    }

    private void startBg(Activity a) {
        try {
            if (!bg || !MAIN.equals(a.getClass().getName())) return;
            Intent i = new Intent(a, BgService.class);
            if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i);
            else a.startService(i);
        } catch (Throwable ignored) {
            // hizmet baslamazsa uygulama yine calisir
        }
    }

    private void openPending(Activity a) {
        if (pendingUrl == null || !MAIN.equals(a.getClass().getName())) return;
        WebView w = findWebView(a.findViewById(android.R.id.content));
        if (w == null) return;
        final String url = pendingUrl;
        pendingUrl = null;
        navigate(w, url, 0);
    }

    /** Sayfa (ve varsa acilis ekrani) bitince adresi acar; en fazla yaklasik 20 saniye bekler. */
    private void navigate(final WebView w, final String url, final int tries) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean ready = w.getUrl() != null && w.getProgress() == 100
                            && (attachedAt == 0L || SystemClock.uptimeMillis() >= attachedAt + splashMs + 800L);
                    if (ready || tries >= 50) w.evaluateJavascript("location.href=" + JSONObject.quote(url), null);
                    else navigate(w, url, tries + 1);
                } catch (Throwable ignored) {
                }
            }
        }, 400L);
    }

    /** Sayfanin cagirdigi yerel kopru (window.WebaaAndroid): kaynak projedeki NativeBridge ile ayni yontemler. */
    public class Js {
        private final Activity act;

        Js(Activity a) {
            act = a;
        }

        @JavascriptInterface
        public void showNotification(String title, String body, String url) {
            if (!notif) return;
            ExtNotif.show(act, (int) (System.currentTimeMillis() % 100000L), title, body, url);
        }

        @JavascriptInterface
        public void schedule(int id, String title, String body, double delayMs, String url) {
            if (!notif) return;
            ExtNotif.schedule(act, id, title, body, (long) delayMs, url);
        }

        @JavascriptInterface
        public void cancel(int id) {
            if (!notif) return;
            ExtNotif.cancel(act, id);
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String p = "android.permission.POST_NOTIFICATIONS";
                        if (Build.VERSION.SDK_INT >= 33 && act.checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            act.requestPermissions(new String[] { p }, 4);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        @JavascriptInterface
        public String fcmToken() {
            if (!fcm) return "";
            try {
                return act.getSharedPreferences("webaa_push", Context.MODE_PRIVATE).getString("token", "");
            } catch (Throwable e) {
                return "";
            }
        }

        @JavascriptInterface
        public void subscribe(String topic) {
            if (!fcm) return;
            try {
                FirebaseMessaging.getInstance().subscribeToTopic(topic);
            } catch (Throwable ignored) {
            }
        }

        @JavascriptInterface
        public void unsubscribe(String topic) {
            if (!fcm) return;
            try {
                FirebaseMessaging.getInstance().unsubscribeFromTopic(topic);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override public void onActivityStarted(Activity a) { attach(a); }
    @Override
    public void onActivityResumed(Activity a) {
        attach(a);
        startBg(a);
        try {
            openPending(a);
        } catch (Throwable ignored) {
        }
    }
    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityPaused(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
    @Override public void onActivityDestroyed(Activity a) { }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
