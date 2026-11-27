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
 *   link  : Deep Link yonlendirme (dlScheme, dlHost, dlPath)
 *   site  : web adresi kaynakli uygulamanin baslangic adresi (dosyali uygulamada bos)
 * fcm acikken ayrica sistem bildirimine dokununca gelen adres acilir ve uygulama ici mesaj gosterilir.
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
    private boolean link = false;
    private String dlScheme = "";
    private String dlHost = "";
    private String dlPath = "";
    private String site = "";
    private static final String PAGE = "file:///android_asset/index.html";
    private long splashMs = 0L;
    private long attachedAt = 0L;
    private volatile String insets = "0,0,0,0";
    private WeakReference<WebView> webRef;

    /** OpenActivity'nin biraktigi, sayfada acilacak adres. */
    static volatile String pendingUrl = null;
    /** Deep Link ya da Push baglantisi: Links kurallariyla acilir ve sayfaya "webaa-deeplink" olayi gider. */
    static volatile String pendingLink = null;

    private static volatile ExtBoot self = null;
    private static WeakReference<Activity> front = null;

    @Override
    public boolean onCreate() {
        try {
            Context c = getContext();
            if (c == null) return true;
            final Context a = c.getApplicationContext() != null ? c.getApplicationContext() : c;
            if (!(a instanceof Application)) return true;
            if (!readConfig(c)) return true;
            self = this;
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
            link = j.optBoolean("link", false);
            dlScheme = j.optString("dlScheme", "");
            dlHost = j.optString("dlHost", "");
            dlPath = j.optString("dlPath", "");
            site = j.optString("site", "");
            return bio || notif || fcm || seg || edge || bg || link;
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
            if (!bio && !notif && !fcm && !edge && !link) return;
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

    private static String extra(Intent i, String key) {
        String s = i.getStringExtra(key);
        return s == null ? "" : s;
    }

    /**
     * Ana ekran yeni acilirken gelen niyet. Deep Link: VIEW adresi. Push: uygulama kapali ya da arka plandayken
     * bildirimi Android gosterir; dokununca mesajin verisi (url, iam...) ana ekrana "extra" olarak gelir.
     */
    private void readIntent(Context c, Intent i) {
        if (i == null || (i.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            String u = i.getData().toString();
            if (link && Links.isDeepLink(u, dlScheme, dlHost, dlPath)) pendingLink = u;
            return;
        }
        if (!fcm) return;
        String url = extra(i, "webaa_url");
        if (url.length() == 0) url = extra(i, "url");
        // baglantili bildirime dokunulduysa dogrudan o sayfa acilir, baglanti yoksa mesaj penceresi gosterilir
        if (url.length() > 0) pendingLink = url;
        else if ("1".equals(extra(i, "iam"))) {
            Iam.save(c, extra(i, "iam_title"), extra(i, "iam_body"), extra(i, "iam_img"), extra(i, "iam_url"), extra(i, "iam_exp"));
        }
        i.removeExtra("url");
        i.removeExtra("webaa_url");
        i.removeExtra("iam");
    }

    private void openPending(Activity a) {
        if ((pendingUrl == null && pendingLink == null) || !MAIN.equals(a.getClass().getName())) return;
        WebView w = findWebView(a.findViewById(android.R.id.content));
        if (w == null) return;
        final String url = pendingUrl;
        final String ln = pendingLink;
        pendingUrl = null;
        pendingLink = null;
        if (ln != null) whenReady(a, w, ln, 0);
        else navigate(w, url, 0);
    }

    /** Sayfa (ve varsa acilis ekrani, web adresi yonlendirmesi) bitti mi? */
    private boolean ready(WebView w) {
        String cur = w.getUrl();
        if (cur == null || w.getProgress() != 100) return false;
        if (cur.split("[?#]", 2)[0].endsWith("w2a_splash.html")) return false;
        if (site.length() > 0 && !cur.startsWith("http")) return false;
        return attachedAt == 0L || SystemClock.uptimeMillis() >= attachedAt + splashMs + 800L;
    }

    private static void later(Runnable r, long ms) {
        new Handler(Looper.getMainLooper()).postDelayed(r, ms);
    }

    /** Sayfa (ve varsa acilis ekrani) bitince adresi acar; en fazla yaklasik 20 saniye bekler. */
    private void navigate(final WebView w, final String url, final int tries) {
        later(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ready(w) || tries >= 50) w.evaluateJavascript("location.href=" + JSONObject.quote(url), null);
                    else navigate(w, url, tries + 1);
                } catch (Throwable ignored) {
                }
            }
        }, 400L);
    }

    private void whenReady(final Activity a, final WebView w, final String ln, final int tries) {
        final Links r = Links.resolve(ln, site, PAGE, dlScheme, dlHost, dlPath);
        if (r.kind == Links.NONE) return;
        if (r.kind == Links.EXTERNAL) {
            external(a, r.target);
            return;
        }
        later(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ready(w) || tries >= 50) go(w, r, ln);
                    else whenReady(a, w, ln, tries + 1);
                } catch (Throwable ignored) {
                }
            }
        }, 400L);
    }

    /** Mesaj penceresindeki "Ac": acilis ekrani surerken dokunulursa sayfanin bitmesi beklenir. */
    void openNow(Activity a, String ln) {
        try {
            WebView w = findWebView(a.findViewById(android.R.id.content));
            if (w != null) whenReady(a, w, ln, 0);
        } catch (Throwable ignored) {
        }
    }

    private static void external(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {
            // bu adresi acacak uygulama yok
        }
    }

    private void go(final WebView w, Links r, final String ln) {
        if (r.kind == Links.RELATIVE) {
            w.evaluateJavascript("location.href=" + JSONObject.quote(r.target), null);
            fireAfterLoad(w, ln, 0);
            return;
        }
        String cur = w.getUrl();
        int h = r.target.indexOf('#');
        if (cur != null && h >= 0 && cur.split("#", 2)[0].equals(r.target.substring(0, h))) {
            // ayni belgede yalnizca # degisir: sayfa yeniden yuklenmez
            w.evaluateJavascript("location.hash=" + JSONObject.quote(r.target.substring(h + 1)), null);
            later(new Runnable() {
                @Override
                public void run() {
                    fire(w, ln);
                }
            }, 150L);
        } else {
            w.loadUrl(r.target);
            fireAfterLoad(w, ln, 0);
        }
    }

    private void fireAfterLoad(final WebView w, final String ln, final int tries) {
        later(new Runnable() {
            @Override
            public void run() {
                try {
                    if (w.getProgress() == 100 || tries >= 30) fire(w, ln);
                    else fireAfterLoad(w, ln, tries + 1);
                } catch (Throwable ignored) {
                }
            }
        }, 700L);
    }

    private static void fire(WebView w, String ln) {
        try {
            String q = JSONObject.quote(ln);
            w.evaluateJavascript("window.__webaaLink=" + q + ";window.dispatchEvent(new CustomEvent('webaa-deeplink',{detail:" + q + "}))", null);
        } catch (Throwable ignored) {
        }
    }

    /** PushService cagirir: ana ekran aciksa bekleyen mesaj hemen gosterilir, degilse acilinca. */
    public static void deliverIam() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                ExtBoot s = self;
                Activity a = front != null ? front.get() : null;
                if (s != null && a != null) Iam.show(a, s);
            }
        });
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
            if (fcm && MAIN.equals(a.getClass().getName())) {
                front = new WeakReference<Activity>(a);
                Iam.show(a, this);
            }
        } catch (Throwable ignored) {
        }
    }
    @Override
    public void onActivityCreated(Activity a, Bundle state) {
        try {
            if (state == null && MAIN.equals(a.getClass().getName())) readIntent(a, a.getIntent());
        } catch (Throwable ignored) {
            // niyet okunamazsa uygulama baslangic sayfasinda acilir
        }
    }
    @Override
    public void onActivityPaused(Activity a) {
        if (front != null && front.get() == a) front = null;
    }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
    @Override public void onActivityDestroyed(Activity a) { Iam.close(a); }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
