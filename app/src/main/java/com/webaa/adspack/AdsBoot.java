package com.webaa.adspack;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.ump.ConsentForm;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.FormError;
import com.google.android.ump.UserMessagingPlatform;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import org.json.JSONObject;

/**
 * Uygulama sureci acilirken calisir. Reklam ayarlarini assets/webaa_ads.json dosyasindan okur,
 * ana ekranin altina banner koyar ve ayarlanan aralikla gecis reklami gosterir.
 * Her adim try/catch icindedir: reklam tarafinda hata olsa da uygulama acilir ve calisir.
 */
public class AdsBoot extends ContentProvider implements Application.ActivityLifecycleCallbacks {
    private static final String MAIN = "com.web2apk.Main";
    private static final String MARK = "webaa_ads_root";

    private Context app;
    private Handler ui;
    private String bannerId = "";
    private String interId = "";
    private long every = 180000L;
    private boolean ready = false;
    private boolean ticking = false;
    private boolean resumed = false;
    private boolean loading = false;
    private boolean top = false;
    private boolean ump = true;
    private boolean started = false;
    private boolean consentAsked = false;
    private WeakReference<Activity> cur;
    private AdView banner;
    private InterstitialAd inter;

    @Override
    public boolean onCreate() {
        try {
            Context c = getContext();
            if (c == null) return true;
            Context a = c.getApplicationContext();
            app = a != null ? a : c;
            if (!(app instanceof Application)) return true;
            if (!readConfig(c)) return true;
            ui = new Handler(Looper.getMainLooper());
            ((Application) app).registerActivityLifecycleCallbacks(this);
            if (!ump) startAds();
        } catch (Throwable ignored) {
            // reklam baslamasa da uygulama acilir
        }
        return true;
    }

    /** MobileAds'i arka planda baslatir (bir kez). */
    private void startAds() {
        if (started) return;
        started = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    MobileAds.initialize(app, new OnInitializationCompleteListener() {
                        @Override
                        public void onInitializationComplete(InitializationStatus status) {
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    onReady();
                                }
                            });
                        }
                    });
                } catch (Throwable ignored) {
                    // reklam baslatilamazsa uygulama reklamsiz calisir
                }
            }
        }).start();
    }

    /** AB/Ingiltere icin Google gizlilik onay formu (UMP); onay alininca reklamlar baslar. */
    private void askConsent(final Activity a) {
        if (consentAsked) return;
        consentAsked = true;
        try {
            final ConsentInformation ci = UserMessagingPlatform.getConsentInformation(a);
            ci.requestConsentInfoUpdate(a, new ConsentRequestParameters.Builder().build(),
                new ConsentInformation.OnConsentInfoUpdateSuccessListener() {
                    @Override
                    public void onConsentInfoUpdateSuccess() {
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(a, new ConsentForm.OnConsentFormDismissedListener() {
                            @Override
                            public void onConsentFormDismissed(FormError e) {
                                if (ci.canRequestAds()) startAds();
                            }
                        });
                    }
                },
                new ConsentInformation.OnConsentInfoUpdateFailureListener() {
                    @Override
                    public void onConsentInfoUpdateFailure(FormError e) {
                        if (ci.canRequestAds()) startAds();
                    }
                });
            if (ci.canRequestAds()) startAds();
        } catch (Throwable ignored) {
            // onay formu calismazsa reklam baslatilmaz, uygulama acik kalir
        }
    }

    private boolean readConfig(Context c) {
        try {
            InputStream in = c.getAssets().open("webaa_ads.json");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject j = new JSONObject(bo.toString("UTF-8"));
            bannerId = j.optString("banner", "").trim();
            interId = j.optString("inter", "").trim();
            long m = j.optLong("mins", 3L);
            if (m < 1L) m = 1L;
            if (m > 60L) m = 60L;
            every = m * 60000L;
            top = j.optBoolean("top", false);
            ump = j.optBoolean("ump", true);
            return bannerId.length() > 0 || interId.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void onReady() {
        ready = true;
        try {
            if (banner != null) banner.loadAd(new AdRequest.Builder().build());
            loadInter();
        } catch (Throwable ignored) {
        }
    }

    private void loadInter() {
        if (!ready || loading || interId.length() == 0) return;
        loading = true;
        try {
            InterstitialAd.load(app, interId, new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(InterstitialAd ad) {
                    inter = ad;
                    loading = false;
                }

                @Override
                public void onAdFailedToLoad(LoadAdError e) {
                    inter = null;
                    loading = false;
                }
            });
        } catch (Throwable t) {
            loading = false;
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                Activity a = cur == null ? null : cur.get();
                if (ready && a != null && resumed && !a.isFinishing() && inter != null) {
                    final InterstitialAd ad = inter;
                    inter = null;
                    ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                        @Override
                        public void onAdDismissedFullScreenContent() {
                            loadInter();
                        }

                        @Override
                        public void onAdFailedToShowFullScreenContent(AdError e) {
                            loadInter();
                        }
                    });
                    ad.show(a);
                } else if (inter == null) {
                    loadInter();
                }
            } catch (Throwable ignored) {
            } finally {
                ui.postDelayed(this, every);
            }
        }
    };

    private boolean isMain(Activity a) {
        return a != null && MAIN.equals(a.getClass().getName());
    }

    /** WebView'i dikey bir kapsayiciya alir, altina banner ekler. */
    private void wrap(Activity a) {
        try {
            if (bannerId.length() == 0) return;
            ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
            if (content == null || content.getChildCount() != 1) return;
            View first = content.getChildAt(0);
            if (MARK.equals(first.getTag())) return;
            LinearLayout root = new LinearLayout(a);
            root.setTag(MARK);
            root.setOrientation(LinearLayout.VERTICAL);
            content.removeView(first);
            AdView v = new AdView(a);
            v.setAdSize(AdSize.BANNER);
            v.setAdUnitId(bannerId);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            if (top) {
                root.addView(v, lp);
                root.addView(first, wp);
            } else {
                root.addView(first, wp);
                root.addView(v, lp);
            }
            content.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (Build.VERSION.SDK_INT >= 30) {
                root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                    @Override
                    public WindowInsets onApplyWindowInsets(View view, WindowInsets ins) {
                        int nb = ins.getInsets(WindowInsets.Type.navigationBars()).bottom;
                        int st = top ? ins.getInsets(WindowInsets.Type.statusBars()).top : 0;
                        view.setPadding(0, st, 0, nb);
                        return ins;
                    }
                });
                root.requestApplyInsets();
            }
            banner = v;
            if (ready) v.loadAd(new AdRequest.Builder().build());
        } catch (Throwable ignored) {
            // banner eklenemezse uygulama reklamsiz acik kalir
        }
    }

    @Override
    public void onActivityStarted(Activity a) {
        if (!isMain(a)) return;
        wrap(a);
        if (ump) askConsent(a);
    }

    @Override
    public void onActivityResumed(Activity a) {
        if (!isMain(a)) return;
        cur = new WeakReference<Activity>(a);
        resumed = true;
        if (interId.length() > 0 && !ticking && ui != null) {
            ticking = true;
            ui.postDelayed(tick, every);
        }
    }

    @Override
    public void onActivityPaused(Activity a) {
        if (isMain(a)) resumed = false;
    }

    @Override
    public void onActivityDestroyed(Activity a) {
        if (!isMain(a)) return;
        try {
            if (banner != null) {
                banner.destroy();
                banner = null;
            }
        } catch (Throwable ignored) {
        }
        if (cur != null && cur.get() == a) cur = null;
    }

    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
