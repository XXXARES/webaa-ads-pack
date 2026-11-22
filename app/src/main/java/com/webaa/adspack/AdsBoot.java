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
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.OnUserEarnedRewardListener;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.gms.ads.appopen.AppOpenAd;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.ump.ConsentDebugSettings;
import com.google.android.ump.ConsentForm;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.FormError;
import com.google.android.ump.UserMessagingPlatform;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Uygulama sureci acilirken calisir. Reklam ayarlarini assets/webaa_ads.json dosyasindan okur:
 * banner, gecis, odullu ve uygulama acilis reklami; zaman / sayfa / acilis / elle tetikleme;
 * cocuk ve yas ayarlari, test cihazlari. Sayfadan WebaaAds.showRewarded() gibi cagrilar icin
 * WebView'a "WebaaAdsNative" koprusunu ekler.
 * Her adim try/catch icindedir: reklam tarafinda hata olsa da uygulama acilir ve calisir.
 */
public class AdsBoot extends ContentProvider implements Application.ActivityLifecycleCallbacks {
    private static final String MAIN = "com.web2apk.Main";
    private static final String MARK = "webaa_ads_root";

    /** Reklam kapaninca sonucu bildirir. */
    private interface Done {
        void on(boolean ok);
    }

    private Context app;
    private Handler ui;
    private String bannerId = "";
    private String interId = "";
    private String rewId = "";
    private String openId = "";
    private String mode = "time";
    private int pages = 4;
    private long every = 180000L;
    private int tfcd = -1;
    private int tfua = -1;
    private String rating = "";
    private final List<String> devs = new ArrayList<String>();
    private boolean umpTest = false;
    private boolean ready = false;
    private boolean ticking = false;
    private boolean resumed = false;
    private boolean top = false;
    private boolean ump = true;
    private boolean started = false;
    private boolean consentAsked = false;
    private boolean adFull = false;
    private boolean interLoading = false;
    private boolean interLaunch = false;
    private boolean rewLoading = false;
    private boolean openLoading = false;
    private boolean openFirst = true;
    private long openLast = 0L;
    private long openBg = 0L;
    private int pageCount = 0;
    private boolean earned = false;
    private int earnedAmt = 0;
    private String earnedType = "";
    private WeakReference<Activity> cur;
    private WeakReference<WebView> webRef;
    private AdView banner;
    private InterstitialAd inter;
    private RewardedAd rew;
    private AppOpenAd openAd;

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

    /** Yas / cocuk ayarlarini ve test cihazlarini uygular, MobileAds'i arka planda baslatir (bir kez). */
    private void startAds() {
        if (started) return;
        started = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    applyRequestConfig();
                } catch (Throwable ignored) {
                    // ayar uygulanamazsa varsayilanlarla devam
                }
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

    private void applyRequestConfig() {
        RequestConfiguration.Builder b = new RequestConfiguration.Builder();
        boolean any = false;
        if (tfcd == 1) {
            b.setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE);
            any = true;
        } else if (tfcd == 0) {
            b.setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE);
            any = true;
        }
        if (tfua == 1) {
            b.setTagForUnderAgeOfConsent(RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE);
            any = true;
        } else if (tfua == 0) {
            b.setTagForUnderAgeOfConsent(RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE);
            any = true;
        }
        if ("G".equals(rating)) {
            b.setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G);
            any = true;
        } else if ("PG".equals(rating)) {
            b.setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_PG);
            any = true;
        } else if ("T".equals(rating)) {
            b.setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_T);
            any = true;
        } else if ("MA".equals(rating)) {
            b.setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_MA);
            any = true;
        }
        if (!devs.isEmpty()) {
            b.setTestDeviceIds(new ArrayList<String>(devs));
            any = true;
        }
        if (any) MobileAds.setRequestConfiguration(b.build());
    }

    /** AB/Ingiltere icin Google gizlilik onay formu (UMP); onay alininca reklamlar baslar. */
    private void askConsent(final Activity a) {
        if (consentAsked) return;
        consentAsked = true;
        try {
            final ConsentInformation ci = UserMessagingPlatform.getConsentInformation(a);
            ConsentRequestParameters.Builder pb = new ConsentRequestParameters.Builder();
            if (tfua == 1) pb.setTagForUnderAgeOfConsent(true);
            if (umpTest && !devs.isEmpty()) {
                ConsentDebugSettings.Builder db = new ConsentDebugSettings.Builder(a)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA);
                for (String d : devs) db.addTestDeviceHashedId(d);
                pb.setConsentDebugSettings(db.build());
            }
            ci.requestConsentInfoUpdate(a, pb.build(),
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
            rewId = j.optString("rew", "").trim();
            openId = j.optString("open", "").trim();
            long m = j.optLong("mins", 3L);
            if (m < 1L) m = 1L;
            if (m > 60L) m = 60L;
            every = m * 60000L;
            mode = j.optString("mode", "time");
            if (!"time".equals(mode) && !"pages".equals(mode) && !"launch".equals(mode) && !"manual".equals(mode)) mode = "time";
            pages = j.optInt("pages", 4);
            if (pages < 1) pages = 1;
            if (pages > 50) pages = 50;
            interLaunch = "launch".equals(mode);
            top = j.optBoolean("top", false);
            ump = j.optBoolean("ump", true);
            umpTest = j.optBoolean("umpTest", false);
            tfcd = j.optInt("tfcd", -1);
            tfua = j.optInt("tfua", -1);
            rating = j.optString("rating", "");
            JSONArray da = j.optJSONArray("devs");
            if (da != null) {
                for (int i = 0; i < da.length() && i < 20; i++) {
                    String d = da.optString(i, "").trim();
                    if (d.length() > 0) devs.add(d);
                }
            }
            return bannerId.length() > 0 || interId.length() > 0 || rewId.length() > 0 || openId.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void onReady() {
        ready = true;
        try {
            if (banner != null) banner.loadAd(new AdRequest.Builder().build());
            loadInter();
            loadRew();
            loadOpen();
        } catch (Throwable ignored) {
        }
    }

    private Activity curAct() {
        Activity a = cur == null ? null : cur.get();
        return (a != null && !a.isFinishing()) ? a : null;
    }

    // ---------------------------------------------------------------- gecis reklami

    private void loadInter() {
        if (!ready || interLoading || inter != null || interId.length() == 0) return;
        interLoading = true;
        try {
            InterstitialAd.load(app, interId, new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(InterstitialAd ad) {
                    inter = ad;
                    interLoading = false;
                    if (interLaunch) {
                        interLaunch = false;
                        showInter(null);
                    }
                }

                @Override
                public void onAdFailedToLoad(LoadAdError e) {
                    inter = null;
                    interLoading = false;
                }
            });
        } catch (Throwable t) {
            interLoading = false;
        }
    }

    private boolean showInter(final Done done) {
        try {
            Activity a = curAct();
            final InterstitialAd ad = inter;
            if (ad == null || a == null || !resumed || adFull) {
                loadInter();
                if (done != null) done.on(false);
                return false;
            }
            inter = null;
            adFull = true;
            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    adFull = false;
                    loadInter();
                    if (done != null) done.on(true);
                }

                @Override
                public void onAdFailedToShowFullScreenContent(AdError e) {
                    adFull = false;
                    loadInter();
                    if (done != null) done.on(false);
                }
            });
            ad.show(a);
            return true;
        } catch (Throwable t) {
            adFull = false;
            if (done != null) done.on(false);
            return false;
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                showInter(null);
            } catch (Throwable ignored) {
            } finally {
                ui.postDelayed(this, every);
            }
        }
    };

    private final Runnable pageRun = new Runnable() {
        @Override
        public void run() {
            try {
                pageCount++;
                if (pageCount % pages == 0) showInter(null);
            } catch (Throwable ignored) {
            }
        }
    };

    // ---------------------------------------------------------------- odullu reklam

    private void loadRew() {
        if (!ready || rewLoading || rew != null || rewId.length() == 0) return;
        rewLoading = true;
        try {
            RewardedAd.load(app, rewId, new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
                @Override
                public void onAdLoaded(RewardedAd ad) {
                    rew = ad;
                    rewLoading = false;
                }

                @Override
                public void onAdFailedToLoad(LoadAdError e) {
                    rew = null;
                    rewLoading = false;
                }
            });
        } catch (Throwable t) {
            rewLoading = false;
        }
    }

    private String rewJson(boolean shown, String reason) {
        return "{\"shown\":" + shown + ",\"rewarded\":" + (shown && earned) + ",\"amount\":" + earnedAmt
            + ",\"type\":" + JSONObject.quote(earnedType) + ",\"reason\":" + JSONObject.quote(reason) + "}";
    }

    private void showRew(final int id) {
        try {
            Activity a = curAct();
            final RewardedAd ad = rew;
            if (ad == null || a == null || !resumed || adFull) {
                loadRew();
                earned = false;
                earnedAmt = 0;
                earnedType = "";
                reply(id, rewJson(false, "not_ready"));
                return;
            }
            rew = null;
            adFull = true;
            earned = false;
            earnedAmt = 0;
            earnedType = "";
            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    adFull = false;
                    loadRew();
                    reply(id, rewJson(true, ""));
                }

                @Override
                public void onAdFailedToShowFullScreenContent(AdError e) {
                    adFull = false;
                    loadRew();
                    reply(id, rewJson(false, "show_failed"));
                }
            });
            ad.show(a, new OnUserEarnedRewardListener() {
                @Override
                public void onUserEarnedReward(RewardItem item) {
                    earned = true;
                    earnedAmt = item.getAmount();
                    earnedType = item.getType() == null ? "" : item.getType();
                }
            });
        } catch (Throwable t) {
            adFull = false;
            reply(id, rewJson(false, "error"));
        }
    }

    // ---------------------------------------------------------------- acilis reklami

    private void loadOpen() {
        if (!ready || openLoading || openAd != null || openId.length() == 0) return;
        openLoading = true;
        try {
            AppOpenAd.load(app, openId, new AdRequest.Builder().build(), new AppOpenAd.AppOpenAdLoadCallback() {
                @Override
                public void onAdLoaded(AppOpenAd ad) {
                    openAd = ad;
                    openLoading = false;
                    if (openFirst) {
                        openFirst = false;
                        showOpen();
                    }
                }

                @Override
                public void onAdFailedToLoad(LoadAdError e) {
                    openAd = null;
                    openLoading = false;
                }
            });
        } catch (Throwable t) {
            openLoading = false;
        }
    }

    private void showOpen() {
        try {
            Activity a = curAct();
            final AppOpenAd ad = openAd;
            if (ad == null || a == null || !resumed || adFull) {
                loadOpen();
                return;
            }
            if (System.currentTimeMillis() - openLast < 60000L) return;
            openAd = null;
            adFull = true;
            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    adFull = false;
                    openLast = System.currentTimeMillis();
                    loadOpen();
                }

                @Override
                public void onAdFailedToShowFullScreenContent(AdError e) {
                    adFull = false;
                    loadOpen();
                }
            });
            ad.show(a);
        } catch (Throwable t) {
            adFull = false;
        }
    }

    // ---------------------------------------------------------------- sayfa koprusu

    /** Sayfadaki WebaaAds.* cagrilarinin sonucunu sayfaya iletir. */
    private void reply(final int id, final String json) {
        if (ui == null) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    WebView w = webRef == null ? null : webRef.get();
                    if (w != null) w.evaluateJavascript("window.__webaaAdCb&&window.__webaaAdCb(" + id + "," + json + ")", null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** Sayfanin cagirdigi yerel kopru (window.WebaaAdsNative). */
    public class Js {
        @JavascriptInterface
        public void showInterstitial(final int id) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    showInter(new Done() {
                        @Override
                        public void on(boolean ok) {
                            reply(id, "{\"shown\":" + ok + ",\"reason\":\"" + (ok ? "" : "not_ready") + "\"}");
                        }
                    });
                }
            });
        }

        @JavascriptInterface
        public void showRewarded(final int id) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    showRew(id);
                }
            });
        }

        @JavascriptInterface
        public boolean interstitialReady() {
            return inter != null;
        }

        @JavascriptInterface
        public boolean rewardedReady() {
            return rew != null;
        }

        @JavascriptInterface
        public void page() {
            ui.post(pageRun);
        }
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

    private void attachBridge(Activity a) {
        try {
            if (interId.length() == 0 && rewId.length() == 0) return;
            WebView w = findWebView(a.findViewById(android.R.id.content));
            if (w == null) return;
            if (webRef != null && webRef.get() == w) return;
            w.addJavascriptInterface(new Js(), "WebaaAdsNative");
            webRef = new WeakReference<WebView>(w);
        } catch (Throwable ignored) {
            // kopru eklenemezse otomatik reklamlar yine calisir
        }
    }

    // ---------------------------------------------------------------- banner

    private boolean isMain(Activity a) {
        return a != null && MAIN.equals(a.getClass().getName());
    }

    /** WebView'i dikey bir kapsayiciya alir, altina (ya da ustune) banner ekler. */
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

    // ---------------------------------------------------------------- yasam dongusu

    @Override
    public void onActivityStarted(Activity a) {
        if (!isMain(a)) return;
        wrap(a);
        attachBridge(a);
        if (ump) askConsent(a);
    }

    @Override
    public void onActivityResumed(Activity a) {
        if (!isMain(a)) return;
        cur = new WeakReference<Activity>(a);
        resumed = true;
        attachBridge(a);
        try {
            if (openId.length() > 0 && openBg != 0L && !adFull && System.currentTimeMillis() - openBg > 30000L) {
                openBg = 0L;
                showOpen();
            }
        } catch (Throwable ignored) {
        }
        if (interId.length() > 0 && "time".equals(mode) && !ticking && ui != null) {
            ticking = true;
            ui.postDelayed(tick, every);
        }
    }

    @Override
    public void onActivityPaused(Activity a) {
        if (isMain(a)) resumed = false;
    }

    @Override
    public void onActivityStopped(Activity a) {
        if (isMain(a) && !adFull) openBg = System.currentTimeMillis();
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
        if (webRef != null && webRef.get() != null && a.isFinishing()) webRef = null;
    }

    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
