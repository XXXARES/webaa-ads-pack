package com.webaa.adspack;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Uygulama sureci acilirken calisir. Satin alma ayarlarini assets/webaa_billing.json dosyasindan okur
 * (remove: reklamlari kaldiran urunler, consume: tekrar alinabilen urunler, key: lisans anahtari).
 * Sayfaya "WebaaBillingNative" koprusunu ekler; sayfadaki window.WebaaBilling bunu cagirir.
 * Her adim try/catch icindedir: satin alma tarafinda hata olsa da uygulama acilir ve calisir.
 */
public class BillingBoot extends ContentProvider implements Application.ActivityLifecycleCallbacks, PurchasesUpdatedListener {
    private static final String MAIN = "com.web2apk.Main";

    /** "Reklamlari kaldir" urunu alinmissa true (AdsBoot banner, gecis ve acilis reklamini gostermez). */
    public static volatile boolean noAds = false;
    /** Reklam kaldirma ilk kez acildiginda calisir (AdsBoot banner'i gizler). */
    public static volatile Runnable onNoAds = null;

    private interface Cb<T> {
        void on(T v);
    }

    private interface Owned {
        void on(List<Purchase> all, boolean good);
    }

    private Context app;
    private Handler ui;
    private SharedPreferences prefs;
    private final Set<String> consumable = new HashSet<String>();
    private final Set<String> removeAds = new HashSet<String>();
    private String licenseKey = "";
    private BillingClient client;
    private boolean connecting = false;
    private boolean synced = false;
    private final List<Cb<Boolean>> waiters = new ArrayList<Cb<Boolean>>();
    private WeakReference<Activity> cur;
    private WeakReference<WebView> webRef;
    private int pendingJs = -1;
    private String pendingId = "";

    @Override
    public boolean onCreate() {
        try {
            Context c = getContext();
            if (c == null) return true;
            Context a = c.getApplicationContext();
            app = a != null ? a : c;
            if (!(app instanceof Application)) return true;
            if (!readConfig(c)) return true;
            prefs = app.getSharedPreferences("webaa_iap", Context.MODE_PRIVATE);
            noAds = prefs.getBoolean("noads", false);
            ui = new Handler(Looper.getMainLooper());
            ((Application) app).registerActivityLifecycleCallbacks(this);
        } catch (Throwable ignored) {
            // satin alma baslamasa da uygulama acilir
        }
        return true;
    }

    private boolean readConfig(Context c) {
        try {
            InputStream in = c.getAssets().open("webaa_billing.json");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject j = new JSONObject(bo.toString("UTF-8"));
            JSONArray r = j.optJSONArray("remove");
            if (r != null) for (int i = 0; i < r.length(); i++) removeAds.add(r.getString(i));
            JSONArray k = j.optJSONArray("consume");
            if (k != null) for (int i = 0; i < k.length(); i++) consumable.add(k.getString(i));
            licenseKey = j.optString("key", "").trim();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- baglanti

    private BillingClient client() {
        if (client == null) {
            client = BillingClient.newBuilder(app)
                    .setListener(this)
                    .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                    .build();
        }
        return client;
    }

    private void connect(Cb<Boolean> cb) {
        try {
            BillingClient c = client();
            if (c.isReady()) {
                cb.on(true);
                return;
            }
            waiters.add(cb);
            if (connecting) return;
            connecting = true;
            c.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(BillingResult r) {
                    connecting = false;
                    List<Cb<Boolean>> list = new ArrayList<Cb<Boolean>>(waiters);
                    waiters.clear();
                    boolean good = r.getResponseCode() == BillingClient.BillingResponseCode.OK;
                    for (Cb<Boolean> w : list) w.on(good);
                }

                @Override
                public void onBillingServiceDisconnected() {
                    connecting = false;
                }
            });
        } catch (Throwable t) {
            connecting = false;
            cb.on(false);
        }
    }

    // ---------------------------------------------------------------- sayfaya yanit

    private void reply(final int id, final String json) {
        if (ui == null) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    WebView w = webRef == null ? null : webRef.get();
                    if (w != null) w.evaluateJavascript("window.__webaaIapCb&&window.__webaaIapCb(" + id + "," + json + ")", null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void fail(int id, String pid, String reason) {
        String s;
        try {
            s = new JSONObject().put("purchased", false).put("id", pid).put("reason", reason).toString();
        } catch (Throwable t) {
            s = "{\"purchased\":false,\"reason\":\"error\"}";
        }
        reply(id, s);
    }

    // ---------------------------------------------------------------- urun bilgisi

    private void query(String pid, String type, final Cb<ProductDetails> cb) {
        try {
            List<QueryProductDetailsParams.Product> one = new ArrayList<QueryProductDetailsParams.Product>();
            one.add(QueryProductDetailsParams.Product.newBuilder().setProductId(pid).setProductType(type).build());
            QueryProductDetailsParams prm = QueryProductDetailsParams.newBuilder().setProductList(one).build();
            client.queryProductDetailsAsync(prm, (r, list) -> {
                ProductDetails d = null;
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null && !list.isEmpty()) d = list.get(0);
                cb.on(d);
            });
        } catch (Throwable t) {
            cb.on(null);
        }
    }

    private void details(final String pid, final Cb<ProductDetails> cb) {
        query(pid, BillingClient.ProductType.INAPP, d -> {
            if (d != null) cb.on(d);
            else query(pid, BillingClient.ProductType.SUBS, cb);
        });
    }

    private String priceOf(ProductDetails d) {
        try {
            ProductDetails.OneTimePurchaseOfferDetails one = d.getOneTimePurchaseOfferDetails();
            if (one != null) return one.getFormattedPrice();
            List<ProductDetails.SubscriptionOfferDetails> o = d.getSubscriptionOfferDetails();
            if (o != null && !o.isEmpty()) {
                List<ProductDetails.PricingPhase> ph = o.get(0).getPricingPhases().getPricingPhaseList();
                if (!ph.isEmpty()) return ph.get(0).getFormattedPrice();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void listNext(final List<String> ids, final int i, final JSONArray out, final int id) {
        if (i >= ids.size()) {
            reply(id, out.toString());
            return;
        }
        details(ids.get(i), d -> {
            try {
                if (d != null) out.put(new JSONObject().put("id", d.getProductId()).put("title", d.getTitle()).put("price", priceOf(d)));
            } catch (Throwable ignored) {
            }
            listNext(ids, i + 1, out, id);
        });
    }

    // ---------------------------------------------------------------- sayfa koprusu

    /** Sayfanin cagirdigi yerel kopru (window.WebaaBillingNative). */
    public class Js {
        @JavascriptInterface
        public void products(final int id, final String arg) {
            final List<String> ids = new ArrayList<String>();
            try {
                JSONArray a = new JSONArray(arg);
                for (int i = 0; i < a.length(); i++) ids.add(a.getString(i));
            } catch (Throwable ignored) {
            }
            ui.post(new Runnable() {
                @Override
                public void run() {
                    connect(ok -> {
                        if (ok) listNext(ids, 0, new JSONArray(), id);
                        else reply(id, "[]");
                    });
                }
            });
        }

        @JavascriptInterface
        public void purchase(final int id, final String pid) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    startPurchase(id, pid);
                }
            });
        }

        @JavascriptInterface
        public void owned(final int id, final String arg) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    connect(ok -> {
                        if (!ok) {
                            reply(id, "[]");
                            return;
                        }
                        queryOwned((all, good) -> {
                            List<String> ids = new ArrayList<String>();
                            for (Purchase p : all) {
                                if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
                                for (String x : p.getProducts()) {
                                    if (!consumable.contains(x) && !ids.contains(x)) ids.add(x);
                                }
                            }
                            reply(id, new JSONArray(ids).toString());
                        });
                    });
                }
            });
        }
    }

    private Activity curAct() {
        Activity a = cur == null ? null : cur.get();
        return (a != null && !a.isFinishing()) ? a : null;
    }

    private void startPurchase(final int id, final String pid) {
        try {
            if (pendingJs >= 0) {
                fail(id, pid, "busy");
                return;
            }
            connect(ok -> {
                if (!ok) {
                    fail(id, pid, "unavailable");
                    return;
                }
                details(pid, d -> {
                    try {
                        Activity a = curAct();
                        if (d == null) {
                            fail(id, pid, "not_found");
                            return;
                        }
                        if (a == null) {
                            fail(id, pid, "error");
                            return;
                        }
                        BillingFlowParams.ProductDetailsParams.Builder b = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d);
                        List<ProductDetails.SubscriptionOfferDetails> offers = d.getSubscriptionOfferDetails();
                        if (offers != null && !offers.isEmpty()) b.setOfferToken(offers.get(0).getOfferToken());
                        BillingFlowParams fp = BillingFlowParams.newBuilder()
                                .setProductDetailsParamsList(Collections.singletonList(b.build())).build();
                        pendingJs = id;
                        pendingId = pid;
                        BillingResult r = client.launchBillingFlow(a, fp);
                        if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                            pendingJs = -1;
                            boolean owned = r.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED;
                            fail(id, pid, owned ? "owned" : "error");
                            if (owned) sync();
                        }
                    } catch (Throwable t) {
                        pendingJs = -1;
                        fail(id, pid, "error");
                    }
                });
            });
        } catch (Throwable t) {
            pendingJs = -1;
            fail(id, pid, "error");
        }
    }

    // ---------------------------------------------------------------- satin alma sonucu

    @Override
    public void onPurchasesUpdated(BillingResult r, List<Purchase> list) {
        try {
            int js = pendingJs;
            String pid = pendingId;
            int code = r.getResponseCode();
            if (code == BillingClient.BillingResponseCode.OK && list != null) {
                boolean handled = false;
                for (Purchase p : list) {
                    if (js >= 0 && !handled && p.getProducts().contains(pid)) {
                        handled = true;
                        pendingJs = -1;
                        process(p, js);
                    } else {
                        process(p, -1);
                    }
                }
                if (!handled && js >= 0) {
                    pendingJs = -1;
                    fail(js, pid, "error");
                }
            } else {
                pendingJs = -1;
                boolean owned = code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED;
                if (js >= 0) fail(js, pid, code == BillingClient.BillingResponseCode.USER_CANCELED ? "canceled" : (owned ? "owned" : "error"));
                if (owned) sync();
            }
        } catch (Throwable t) {
            pendingJs = -1;
        }
    }

    private boolean verify(Purchase p) {
        if (licenseKey.length() == 0) return true;
        try {
            java.security.PublicKey k = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.decode(licenseKey, Base64.DEFAULT)));
            Signature sg = Signature.getInstance("SHA1withRSA");
            sg.initVerify(k);
            sg.update(p.getOriginalJson().getBytes());
            return sg.verify(Base64.decode(p.getSignature(), Base64.DEFAULT));
        } catch (Throwable t) {
            return false;
        }
    }

    private void setNoAds(boolean v) {
        try {
            prefs.edit().putBoolean("noads", v).apply();
        } catch (Throwable ignored) {
        }
        boolean was = noAds;
        noAds = v;
        Runnable r = onNoAds;
        if (v && !was && r != null && ui != null) ui.post(r);
    }

    private void done(int js, String pid, boolean consumed) {
        if (js < 0) return;
        String s;
        try {
            s = new JSONObject().put("purchased", true).put("id", pid).put("consumed", consumed).toString();
        } catch (Throwable t) {
            s = "{\"purchased\":true}";
        }
        reply(js, s);
    }

    /** Satin almayi dogrular; tuketilebilirse tuketir, degilse onaylar (3 gun icinde onaylanmazsa Google iade eder). js >= 0 ise sayfaya bildirir. */
    private void process(Purchase p, final int js) {
        final String pid = p.getProducts().isEmpty() ? "" : p.getProducts().get(0);
        if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) {
            if (js >= 0) fail(js, pid, "pending");
            return;
        }
        if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
            if (js >= 0) fail(js, pid, "error");
            return;
        }
        if (!verify(p)) {
            if (js >= 0) fail(js, pid, "invalid");
            return;
        }
        if (removeAds.contains(pid)) setNoAds(true);
        if (consumable.contains(pid)) {
            client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(), (res, token) -> {
                if (res.getResponseCode() == BillingClient.BillingResponseCode.OK) done(js, pid, true);
                else if (js >= 0) fail(js, pid, "consume_failed");
            });
        } else if (!p.isAcknowledged()) {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(), res -> {
                if (res.getResponseCode() == BillingClient.BillingResponseCode.OK) done(js, pid, false);
                else if (js >= 0) fail(js, pid, "ack_failed");
            });
        } else {
            done(js, pid, false);
        }
    }

    private void queryOwned(final Owned cb) {
        final List<Purchase> all = new ArrayList<Purchase>();
        final boolean[] good = new boolean[] {true};
        try {
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(), (r1, l1) -> {
                if (r1.getResponseCode() == BillingClient.BillingResponseCode.OK && l1 != null) all.addAll(l1);
                else good[0] = false;
                try {
                    client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(), (r2, l2) -> {
                        if (r2.getResponseCode() == BillingClient.BillingResponseCode.OK && l2 != null) all.addAll(l2);
                        else good[0] = false;
                        cb.on(all, good[0]);
                    });
                } catch (Throwable t) {
                    cb.on(all, false);
                }
            });
        } catch (Throwable t) {
            cb.on(all, false);
        }
    }

    /** Acilista: yarim kalmis tuketilebilir satin almalari tuketir, onaylanmamislari onaylar, reklam kaldirma durumunu gunceller. */
    private void sync() {
        queryOwned((all, good) -> {
            try {
                boolean has = false;
                for (Purchase p : all) {
                    if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
                    String pid = p.getProducts().isEmpty() ? "" : p.getProducts().get(0);
                    if (removeAds.contains(pid)) has = true;
                    if (consumable.contains(pid) || !p.isAcknowledged()) process(p, -1);
                }
                if (has || good) setNoAds(has);
            } catch (Throwable ignored) {
            }
        });
    }

    // ---------------------------------------------------------------- WebView ve etkinlik

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
            WebView w = findWebView(a.findViewById(android.R.id.content));
            if (w == null) return;
            if (webRef != null && webRef.get() == w) return;
            w.addJavascriptInterface(new Js(), "WebaaBillingNative");
            webRef = new WeakReference<WebView>(w);
        } catch (Throwable ignored) {
            // kopru eklenemezse sayfa "unavailable" yaniti alir, uygulama calisir
        }
    }

    private boolean isMain(Activity a) {
        return a != null && MAIN.equals(a.getClass().getName());
    }

    private void onMain(Activity a) {
        cur = new WeakReference<Activity>(a);
        attachBridge(a);
    }

    @Override
    public void onActivityStarted(Activity a) {
        if (!isMain(a)) return;
        onMain(a);
        if (!synced) {
            synced = true;
            try {
                connect(ok -> {
                    if (ok) sync();
                });
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onActivityResumed(Activity a) {
        if (isMain(a)) onMain(a);
    }

    @Override
    public void onActivityDestroyed(Activity a) {
        if (!isMain(a)) return;
        if (cur != null && cur.get() == a) cur = null;
        if (webRef != null && a.isFinishing()) webRef = null;
    }

    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityPaused(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
