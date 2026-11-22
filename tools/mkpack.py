# Derlenen release APK ve AAB'den reklam paketini cikarir: dex + resources.arsc/res (APK) + resources.pb/res (AAB) + pack.json
import glob, json, os, re, subprocess, sys, zipfile

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

ICON = re.compile(r'^res/mipmap[^/]*/ic_launcher\.png$')

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
    ia = [n for n in an if ICON.match(n)]
    ib = [n[5:] for n in bn if n.startswith('base/') and ICON.match(n[5:])]
    if len(ia) != 1 or ia != ib:
        sys.exit('ic_launcher.png yolu APK ve AAB icinde ayni degil: %s / %s' % (ia, ib))
    print('Simge yolu:', ia[0])

    aapt2 = find_aapt2()
    if aapt2:
        out = subprocess.run([aapt2, 'dump', 'resources', apk], capture_output=True, text=True).stdout
        m = re.search(r'resource (0x7f[0-9a-fA-F]{6})\s+mipmap/ic_launcher', out)
        if m and m.group(1).lower() != '0x7f010000':
            print(out[:1500])
            sys.exit('mipmap/ic_launcher kimligi ' + m.group(1) + ', 0x7f010000 olmali (public.xml uygulanmadi)')
        print('aapt2 simge kimligi:', m.group(1) if m else 'bulunamadi (atlandi)')
    else:
        print('Uyari: aapt2 bulunamadi, simge kimligi dogrulanmadi')

    cands = glob.glob('app/build/intermediates/merged_manifest*/release/**/AndroidManifest.xml', recursive=True)
    for mf in cands[:1]:
        names = re.findall(r'<(?:activity|service|provider|receiver)[^>]*android:name="([^"]+)"', open(mf, encoding='utf-8').read())
        print('Birlesik manifest bilesenleri:', names)
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
        zo.writestr('pack.json', json.dumps({'version': 1, 'init': 'ads', 'package': PKG, 'gmsVersion': gms, 'iconApk': ia[0], 'iconAab': ib[0]}, indent=1))
print('Paket:', out, os.path.getsize(out), 'bayt; dex:', dex)
