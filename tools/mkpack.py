# Derlenen release APK ve AAB'den reklam paketini cikarir: dex + resources.arsc/res (APK) + resources.pb/res (AAB) + pack.json
import glob, json, os, re, struct, subprocess, sys, zipfile

PKG = 'com.webaa.adspack'
apks = glob.glob('app/build/outputs/apk/release/*.apk')
aabs = glob.glob('app/build/outputs/bundle/release/*.aab')
if not apks:
    sys.exit('Release APK bulunamadi')
if not aabs:
    sys.exit('Release AAB bulunamadi')
apk, aab = apks[0], aabs[0]

gms = None
for root_dir, _, files in os.walk(os.path.expanduser('~/.gradle/caches')):
    if 'values.xml' in files and root_dir.replace('\\', '/').endswith('res/values'):
        m = re.search(r'name="google_play_services_version">(\d+)<', open(os.path.join(root_dir, 'values.xml'), encoding='utf-8', errors='ignore').read())
        if m:
            gms = int(m.group(1))
            break
if not gms:
    gms = 12451000
    print('Uyari: google_play_services_version bulunamadi, 12451000 kullanildi')
print('google_play_services_version:', gms)

def is_icon(data):
    # yer tutucu simge: 48x48 PNG (APK'da dosya adi kisaltilabildigi icin boyuta gore bulunur)
    return data[:8] == b'\x89PNG\r\n\x1a\n' and data[16:24] == struct.pack('>II', 48, 48)

def find_aapt2():
    for r in (os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'), '/usr/local/lib/android/sdk'):
        if r:
            c = sorted(glob.glob(os.path.join(r, 'build-tools', '*', 'aapt2')))
            if c:
                return c[-1]
    return None

with zipfile.ZipFile(apk) as za, zipfile.ZipFile(aab) as zb:
    an, bn = za.namelist(), zb.namelist()
    dex = sorted(n for n in an if re.fullmatch(r'classes\d*\.dex', n))
    if not dex:
        sys.exit('APK icinde dex yok')
    if 'resources.arsc' not in an:
        sys.exit('APK icinde resources.arsc yok')
    if 'base/resources.pb' not in bn:
        sys.exit('AAB icinde base/resources.pb yok')
    if not any(b'Lcom/webaa/adspack/AdsBoot;' in za.read(n) for n in dex):
        sys.exit('dex icinde AdsBoot sinifi yok (R8 silmis olabilir)')
    if not any(b'Lcom/webaa/adspack/BillingBoot;' in za.read(n) for n in dex):
        sys.exit('dex icinde BillingBoot sinifi yok (R8 silmis olabilir)')
    if not any(b'Lcom/android/billingclient/api/BillingClient;' in za.read(n) for n in dex):
        sys.exit('dex icinde Google Play Billing kutuphanesi yok')
    ia = [n for n in an if n.startswith('res/') and n.endswith('.png') and not n.endswith('.9.png') and is_icon(za.read(n))]
    ib = [n[5:] for n in bn if n.startswith('base/res/') and n.endswith('.png') and not n.endswith('.9.png') and is_icon(zb.read(n))]
    if len(ia) != 1 or len(ib) != 1:
        sys.exit('Simge dosyasi bulunamadi veya birden fazla: APK %s / AAB %s' % (ia, ib))
    print('Simge yolu: APK', ia[0], '| AAB', ib[0])

    icon_id = None
    aapt2 = find_aapt2()
    if aapt2:
        out = subprocess.run([aapt2, 'dump', 'resources', apk], capture_output=True, text=True).stdout
        m = re.search(r'resource (0x7f[0-9a-fA-F]{6})\s+mipmap/ic_launcher', out)
        if not m:
            print(out[:1500])
            sys.exit('aapt2 ciktisinda mipmap/ic_launcher bulunamadi')
        icon_id = int(m.group(1), 16)
        print('simge kimligi:', m.group(1))
        m2 = re.search(r'mipmap/ic_launcher\s*\n\s*\(\)\s*\(file\)\s+(\S+)', out)
        if m2 and m2.group(1) in an and m2.group(1) != ia[0]:
            print('aapt2 simge yolu farkli, o kullaniliyor:', m2.group(1), '(boyuttan bulunan:', ia[0] + ')')
            ia = [m2.group(1)]
        elif m2:
            print('aapt2 simge yolu dogrulandi:', m2.group(1))
    else:
        sys.exit('aapt2 bulunamadi, simge kimligi okunamadi')

    cands = glob.glob('app/build/intermediates/merged_manifest*/release/**/AndroidManifest.xml', recursive=True)
    bacts = []
    for mf in cands[:1]:
        txt = open(mf, encoding='utf-8').read()
        names = re.findall(r'<(?:activity|service|provider|receiver)[^>]*android:name="([^"]+)"', txt)
        print('Birlesik manifest bilesenleri:', names)
        for tag in re.findall(r'<activity\b[^>]*>', txt):
            m = re.search(r'android:name="(com\.android\.billingclient\.[^"]+)"', tag)
            if not m:
                continue
            cc = re.search(r'android:configChanges="([^"]*)"', tag)
            th = re.search(r'android:theme="([^"]*)"', tag)
            bacts.append({'name': m.group(1), 'configChanges': cc.group(1) if cc else '', 'theme': th.group(1) if th else ''})
    print('Billing etkinlikleri:', bacts)
    if not bacts:
        print('Uyari: birlesik manifestte billing etkinligi bulunamadi, Webaa varsayilan ProxyBillingActivity satirini kullanir')
    os.makedirs('dist', exist_ok=True)
    out = 'dist/ads-pack.zip'
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zo:
        for n in dex:
            zo.writestr(n, za.read(n))
        zo.writestr('apk/resources.arsc', za.read('resources.arsc'))
        for n in an:
            if n.startswith('res/') and not n.endswith('/'):
                zo.writestr('apk/' + n, za.read(n))
        zo.writestr('aab/resources.pb', zb.read('base/resources.pb'))
        for n in bn:
            if n.startswith('base/res/') and not n.endswith('/'):
                zo.writestr('aab/' + n[5:], zb.read(n))
        zo.writestr('pack.json', json.dumps({'version': 3, 'api': 3, 'init': 'ads', 'billing': True, 'package': PKG, 'gmsVersion': gms, 'iconId': icon_id, 'iconApk': ia[0], 'iconAab': ib[0], 'billingActs': bacts}, indent=1))
print('Paket:', out, os.path.getsize(out), 'bayt; dex:', dex)
