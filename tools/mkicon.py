# Paket projesi icin yer tutucu simge (PNG) uretir; Webaa uygulamanin kendi simgesiyle degistirir
import os, struct, zlib

def png(w, h, rgba):
    raw = b''.join(b'\x00' + bytes(rgba) * w for _ in range(h))
    def ch(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + ch(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) + ch(b'IDAT', zlib.compress(raw)) + ch(b'IEND', b'')

os.makedirs('app/src/main/res/mipmap-nodpi', exist_ok=True)
open('app/src/main/res/mipmap-nodpi/ic_launcher.png', 'wb').write(png(48, 48, (5, 150, 105, 255)))
print('simge hazir')
