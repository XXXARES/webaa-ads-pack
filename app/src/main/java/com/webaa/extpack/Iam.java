package com.webaa.extpack;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/** Uygulama ici mesaj: Push verisinden saklanir, ana ekran one gelince pencere olarak bir kez gosterilir. */
public final class Iam {
    private static final String PREFS = "webaa_push";
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_SIDE = 1280;

    private static AlertDialog dialog = null;
    private static Activity owner = null;

    private Iam() {
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    public static void save(Context c, String title, String body, String image, String url, String expires) {
        try {
            if (text(title).length() == 0 && text(body).length() == 0 && text(image).length() == 0) return;
            long exp = 0L;
            try {
                exp = Long.parseLong(text(expires).trim());
            } catch (Throwable ignored) {
            }
            JSONObject j = new JSONObject();
            j.put("t", text(title));
            j.put("b", text(body));
            j.put("i", text(image));
            j.put("u", text(url));
            j.put("exp", exp);
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("iam", j.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Yalnizca https; en fazla 5 MB, uzun kenar yaklasik 1280 pikseli gecmez. Hata olursa null. Ana is parcaciginda cagrilmaz. */
    public static Bitmap image(String url) {
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) return null;
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            if (con.getResponseCode() != 200) return null;
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long start = System.currentTimeMillis();
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_BYTES || System.currentTimeMillis() - start > 8000L) return null;
            }
            in.close();
            byte[] data = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > MAX_SIDE) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Bekleyen mesaj varsa gosterir. Suresi dolmus mesaj silinir; pencere acilamazsa mesaj saklanir. */
    static void show(final Activity a, final ExtBoot boot) {
        try {
            if (a == null || a.isFinishing()) return;
            SharedPreferences sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = sp.getString("iam", null);
            if (raw == null) return;
            JSONObject j = new JSONObject(raw);
            long exp = j.optLong("exp", 0L);
            if (exp > 0L && exp < System.currentTimeMillis()) {
                sp.edit().remove("iam").apply();
                return;
            }
            String t = j.optString("t", "");
            String b = j.optString("b", "");
            final String img = j.optString("i", "");
            final String u = j.optString("u", "");
            float density = a.getResources().getDisplayMetrics().density;
            int pad = (int) (20 * density);
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, pad / 2, pad, 0);
            if (img.length() > 0) {
                final ImageView iv = new ImageView(a);
                iv.setAdjustViewBounds(true);
                iv.setMaxHeight((int) (260 * density));
                box.addView(iv, new LinearLayout.LayoutParams(-1, -2));
                new Thread() {
                    @Override
                    public void run() {
                        final Bitmap bm = image(img);
                        if (bm == null) return;
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                iv.setImageBitmap(bm);
                            }
                        });
                    }
                }.start();
            }
            if (b.length() > 0) {
                TextView tv = new TextView(a);
                tv.setText(b);
                tv.setTextSize(15f);
                tv.setPadding(0, pad / 2, 0, 0);
                box.addView(tv);
            }
            AlertDialog.Builder bld = new AlertDialog.Builder(a);
            if (t.length() > 0) bld.setTitle(t);
            bld.setView(box);
            if (u.length() > 0) {
                bld.setPositiveButton("A\u00e7", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        boot.openNow(a, u);
                    }
                });
                bld.setNegativeButton("Kapat", null);
            } else {
                bld.setPositiveButton("Tamam", null);
            }
            close(null);
            dialog = bld.show();
            owner = a;
            sp.edit().remove("iam").apply();
        } catch (Throwable ignored) {
        }
    }

    /** Pencereyi kapatir; a verilirse yalnizca o ekrana aitse. */
    static void close(Activity a) {
        try {
            if (a != null && a != owner) return;
            if (dialog != null) dialog.dismiss();
        } catch (Throwable ignored) {
        }
        dialog = null;
        owner = null;
    }
}
