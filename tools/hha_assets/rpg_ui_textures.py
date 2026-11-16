"""Texturen für Lobby und Startanimation des RPG-Modus (weiße Alpha-Masken, im Spiel eingefärbt).

Ohne Abhängigkeiten (PNG per zlib). Aufruf aus dem Projektordner:
    python tools/hha_assets/rpg_ui_textures.py
Schreibt nach src/main/resources/assets/hha/textures/gui/rpg/:
    rays.png   – weiche Lichtstrahlen um die Mitte (dreht sich in der Animation)
    glow.png   – runder, weicher Schein
    ring.png   – Portalring mit Teilstrichen
    iris.png   – Maske zum Aufblenden: Mitte durchsichtig, Rand schwarz (wird beim Aufdecken größer)
    spot.png   – flacher Lichtfleck für den Boden unter den Figuren
Dazu je eine .png.mcmeta mit blur + clamp, damit die Masken im Spiel weich skaliert werden (sonst Treppenstufen).
"""
import math
import os
import struct
import zlib

OUT = os.path.join(os.path.dirname(__file__), '..', '..', 'src', 'main', 'resources', 'assets', 'hha', 'textures', 'gui', 'rpg')


def write_png(path, w, h, pixel):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            r, g, b, a = pixel(x, y)
            raw += bytes((int(r), int(g), int(b), max(0, min(255, int(a)))))

    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)


def smooth(e0, e1, x):
    t = max(0.0, min(1.0, (x - e0) / (e1 - e0)))
    return t * t * (3 - 2 * t)


def rays(x, y, n=256):
    cx = cy = (n - 1) / 2
    dx, dy = x - cx, y - cy
    r = math.hypot(dx, dy) / (n / 2)
    ang = math.atan2(dy, dx)
    beams = 0.0
    for k, (count, width, weight) in enumerate(((12, 0.16, 1.0), (7, 0.08, 0.6), (19, 0.05, 0.35))):
        v = 0.5 + 0.5 * math.cos(ang * count + k * 1.7)
        beams += weight * max(0.0, (v - (1 - width)) / width)
    beams = min(1.0, beams)
    fade = (1 - smooth(0.25, 1.0, r)) * smooth(0.02, 0.18, r)
    return 255, 255, 255, 255 * beams * fade


def glow(x, y, n=128):
    c = (n - 1) / 2
    r = math.hypot(x - c, y - c) / (n / 2)
    a = max(0.0, 1 - r) ** 2.2
    return 255, 255, 255, 255 * a


def ring(x, y, n=256):
    c = (n - 1) / 2
    dx, dy = x - c, y - c
    r = math.hypot(dx, dy) / (n / 2)
    ang = math.atan2(dy, dx)
    a = 0.0
    a = max(a, 1 - abs(r - 0.86) / 0.018)                       # Hauptring
    a = max(a, 0.55 * (1 - abs(r - 0.78) / 0.008))              # Innenring
    ticks = (ang / (2 * math.pi) * 48) % 1.0
    if 0.70 < r < 0.76 and ticks < 0.12:                        # Teilstriche
        a = max(a, 0.7)
    big = (ang / (2 * math.pi) * 8) % 1.0
    if 0.90 < r < 0.97 and big < 0.03:                          # acht Marken außen
        a = max(a, 0.9)
    return 255, 255, 255, 255 * max(0.0, min(1.0, a))


def iris(x, y, n=256):
    c = (n - 1) / 2
    r = math.hypot(x - c, y - c) / (n / 2)
    a = smooth(0.62, 0.98, r)
    return 0, 0, 0, 255 * a


def spot(x, y, w=128, h=32):
    dx = (x - (w - 1) / 2) / (w / 2)
    dy = (y - (h - 1) / 2) / (h / 2)
    r = math.hypot(dx, dy)
    a = max(0.0, 1 - r) ** 1.6
    return 255, 255, 255, 255 * a


META = '''{
  "texture": {
    "blur": true,
    "clamp": true
  }
}
'''


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, w, h, fn in (('rays', 256, 256, rays), ('glow', 128, 128, glow), ('ring', 256, 256, ring),
                           ('iris', 256, 256, iris), ('spot', 128, 32, spot)):
        write_png(os.path.join(OUT, name + '.png'), w, h, fn)
        with open(os.path.join(OUT, name + '.png.mcmeta'), 'w', encoding='utf-8') as f:
            f.write(META)
    print('geschrieben:', os.path.normpath(OUT))


if __name__ == '__main__':
    main()
