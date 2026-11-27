package com.webaa.extpack;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Disaridan gelen baglantinin (Deep Link, bildirime dokunma, mesaj penceresindeki "Ac") nerede acilacagini secer.
 * Android sinifi kullanmaz. Ana ekran disaridan da cagrilabildigi icin yalnizca uygulamanin kendi adresleri
 * WebView icinde acilir; yabanci siteler tarayiciya gider, javascript:, data:, file: gibi adresler acilmaz.
 */
final class Links {
    static final int NONE = 0;
    static final int EXTERNAL = 1;  // baska uygulamada (tarayici)
    static final int LOAD = 2;      // WebView'da tam adres
    static final int RELATIVE = 3;  // acik sayfaya gore (location.href)

    private static final Pattern URI = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?$");

    final int kind;
    final String target;

    private Links(int kind, String target) {
        this.kind = kind;
        this.target = target;
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    private static boolean clean(String s) {
        if (s.length() == 0 || s.length() > 4096 || s.indexOf('\\') >= 0) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < 0x20 || s.charAt(i) == 0x7f) return false;
        }
        return true;
    }

    /** "kullanici@Ornek.com:443" -> "ornek.com" */
    private static String host(String authority) {
        String h = text(authority);
        int at = h.lastIndexOf('@');
        if (at >= 0) h = h.substring(at + 1);
        h = h.replaceAll(":\\d*$", "");
        return h.toLowerCase(Locale.ROOT);
    }

    private static String bare(String h) {
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private static boolean hostMatches(String rule, String h) {
        if (rule.length() == 0 || h.length() == 0) return false;
        if (rule.startsWith("*.")) return h.endsWith(rule.substring(1));
        return rule.equals(h);
    }

    /** Manifestteki Deep Link tanimina uyan VIEW baglantisi mi? (HTML/zip dosyasi acma niyetleri uymaz.) */
    static boolean isDeepLink(String link, String dlScheme, String dlHost, String dlPath) {
        if (link == null) return false;
        Matcher m = URI.matcher(link.trim());
        if (!m.matches()) return false;
        String sc = m.group(1).toLowerCase(Locale.ROOT);
        if (text(dlScheme).length() > 0 && sc.equals(dlScheme)) return true;
        if (!sc.equals("http") && !sc.equals("https")) return false;
        return hostMatches(text(dlHost), host(m.group(3))) && text(m.group(4)).startsWith(text(dlPath));
    }

    /**
     * site: web adresi kaynakli uygulamada baslangic adresi, dosyali uygulamada bos.
     * page: dosyali uygulamada ana sayfa (file:///android_asset/index.html).
     */
    static Links resolve(String link, String site, String page, String dlScheme, String dlHost, String dlPath) {
        Links no = new Links(NONE, "");
        if (link == null) return no;
        String s = link.trim();
        if (!clean(s)) return no;
        site = text(site);
        boolean local = site.length() == 0;
        if (!s.matches("^[^/?#]*:.*")) {
            if (s.startsWith("//")) return no;
            if (local) {
                // "/sdcard/..." ya da "../" ile cihazdaki baska bir dosya acilamasin
                if (s.startsWith("/")) return new Links(LOAD, page + "#" + s);
                String path = s.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
                if (path.contains("..") || path.contains("%")) return no;
            }
            return new Links(RELATIVE, s);
        }
        Matcher m = URI.matcher(s);
        if (!m.matches()) return no;
        String sc = m.group(1).toLowerCase(Locale.ROOT);
        String auth = text(m.group(3));
        String path = text(m.group(4));
        String query = m.group(5) == null ? "" : m.group(5);
        String frag = m.group(7) == null ? "" : m.group(7);
        if (sc.equals("http") || sc.equals("https")) {
            String h = host(auth);
            if (h.length() == 0) return no;
            if (auth.indexOf('@') >= 0) return new Links(EXTERNAL, s);
            if (!local) {
                Matcher sm = URI.matcher(site);
                if (sm.matches() && bare(h).equals(bare(host(sm.group(3))))) return new Links(LOAD, s);
            }
            if (hostMatches(text(dlHost), h) && path.startsWith(text(dlPath))) {
                return new Links(LOAD, local ? page + "#" + path + query : s);
            }
            return new Links(EXTERNAL, s);
        }
        if (text(dlScheme).length() > 0 && sc.equals(dlScheme)) {
            String tail;
            if (m.group(2) != null) tail = (auth.length() > 0 ? "/" + auth : "") + path + query;
            else tail = (path.startsWith("/") ? "" : "/") + path + query;
            if (local) return new Links(LOAD, page + "#" + tail);
            return new Links(LOAD, site.replaceAll("/+$", "") + tail + frag);
        }
        if (sc.equals("mailto") || sc.equals("tel") || sc.equals("market")) return new Links(EXTERNAL, s);
        return no;
    }
}
