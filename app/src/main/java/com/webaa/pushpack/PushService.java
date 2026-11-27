package com.webaa.pushpack;

import android.content.Intent;
import android.net.Uri;
import com.webaa.extpack.ExtBoot;
import com.webaa.extpack.Iam;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

public class PushService extends FirebaseMessagingService {
    @Override
    public void onNewToken(String token) {
        getSharedPreferences("webaa_push", MODE_PRIVATE).edit().putString("token", token).apply();
        sendBroadcast(new Intent("webaa.push.TOKEN").setPackage(getPackageName()).putExtra("token", token));
    }

    @Override
    public void onMessageReceived(RemoteMessage m) {
        Map<String, String> d = m.getData();
        if ("1".equals(d.get("iam"))) {
            String u = d.get("iam_url");
            if (u == null || u.length() == 0) u = d.get("url");
            Iam.save(this, d.get("iam_title"), d.get("iam_body"), d.get("iam_img"), u, d.get("iam_exp"));
            ExtBoot.deliverIam();
            if ("only".equals(d.get("iam_mode"))) return;   // yalnizca uygulama ici mesaj: bildirim gosterilmez
        }
        String title = null, body = null, image = d.get("image");
        if (m.getNotification() != null) {
            title = m.getNotification().getTitle();
            body = m.getNotification().getBody();
            Uri iu = m.getNotification().getImageUrl();
            if (iu != null) image = iu.toString();
        }
        if (title == null) title = d.get("title");
        if (body == null) body = d.get("body");
        if (empty(title) && empty(body) && empty(image)) return;   // yalnizca veri tasiyan mesaj: bos bildirim gosterme
        PushBoot.show(this, title, body, d.get("url"), Iam.image(image));
    }

    private static boolean empty(String s) {
        return s == null || s.length() == 0;
    }
}
