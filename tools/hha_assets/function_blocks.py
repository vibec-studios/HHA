"""Texturen, Modelle und Blockstate der Boss-Altäre (Überarbeitung 2, P02) und Vorschaubilder der Effekt-Arbeit.

Aufruf (aus dem Projektordner):
    python tools/hha_assets/function_blocks.py             # Texturen + Modelle + Blockstate schreiben
    python tools/hha_assets/function_blocks.py --preview   # zusätzlich die Vorschauen unter tmp/overhaul2/previews/effekte/

Stil wie die vorhandenen HHA-Blöcke (`world_assets.py`: Olymp-Altar, Opferstein): Marmor mit feinem Korn, Goldrand,
ein Zeichen als Inlay. Jede Variante von `boss_altar` (`BOSS` 0–4) trägt das Zeichen ihres Bosses in seiner Farbe:
0 Hekate Schlüssel (Mondviolett), 1 Brontes Amboss (Schmiedeglut), 2 Kratos Faust (Purpurrot), 3 Zeus Blitz
(Elektrischblau), 4 frei (goldener Ring wie der Opferstein). Die Seiten haben Säulenrillen wie der Olymp-Altar, oben
ein Band in der Bossfarbe, unten Gold, in der Mitte eine kleine Raute als Erkennungszeichen von der Seite.
"""
import json
import math
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(__file__))
from texkit import ASSETS, ROOT, Image, flush, hexc, ramp, save, tile_noise  # noqa: E402
from world_assets import GOLD, MARBLE, stone_face  # noqa: E402

# Drei Töne je Bossfarbe: Schatten (Kontur), Mitte, Licht. Gleiche Farbwelten wie die Effekt-Stile.
ACCENTS = {
    0: [hexc('#2a2060'), hexc('#7d68d6'), hexc('#d9d0ff')],   # Hekate, MOON
    1: [hexc('#6e1c06'), hexc('#e8701a'), hexc('#ffd28a')],   # Brontes, Schmiedeglut (FIRE)
    2: [hexc('#5c0e0b'), hexc('#c8362c'), hexc('#ff9a8a')],   # Kratos, CRIMSON
    3: [hexc('#123a8c'), hexc('#5aa6f0'), hexc('#dff1ff')],   # Zeus, THUNDER
}

# 16 × 16 Zeichen: x = Schatten/Kontur, # = Mitte, + = Licht, . = Marmor.
GLYPHS = {
    0: [  # Schlüssel: Reide oben, Halm, zwei Bärte
        '................',
        '................',
        '......xxxx......',
        '.....x+##+x.....',
        '.....x#..#x.....',
        '.....x#..#x.....',
        '.....x+##+x.....',
        '......xx#x......',
        '.......x#x......',
        '.......x#xxx....',
        '.......x#++x....',
        '.......x#xxx....',
        '.......x#xx.....',
        '.......x#+x.....',
        '.......xxxx.....',
        '................',
    ],
    1: [  # Amboss: Horn links, Bahn, Fuß
        '................',
        '................',
        '................',
        '..xxxxxxxxxxxx..',
        '.x++++++++++#x..',
        '..xx########xx..',
        '....xx####xx....',
        '......x##x......',
        '......x##x......',
        '......x##x......',
        '.....x####x.....',
        '....x######x....',
        '....x+#####x....',
        '....xxxxxxxx....',
        '................',
        '................',
    ],
    2: [  # Faust: vier Knöchel, Daumen quer, Handgelenk
        '................',
        '................',
        '....xxxxxxxx....',
        '...x++x++x++x...',
        '...x##x##x##x...',
        '...x########x...',
        '...xx+++++##x...',
        '...x#xxxxx##x...',
        '...x########x...',
        '....x######x....',
        '.....x####x.....',
        '.....x####x.....',
        '.....x#++#x.....',
        '.....xxxxxx.....',
        '................',
        '................',
    ],
    3: [  # Blitz (Keraunos): Zickzack von rechts oben nach links unten
        '................',
        '.........xxxx...',
        '........x++#x...',
        '.......x+##x....',
        '......x+##x.....',
        '.....x+##xxxx...',
        '....x+######x...',
        '....xxxx###x....',
        '.......x##x.....',
        '......x##x......',
        '.....x##x.......',
        '....x##x........',
        '...x#+x.........',
        '...xxx..........',
        '................',
        '................',
    ],
}

BOSS_NAMES = {0: 'Hekate', 1: 'Brontes', 2: 'Kratos', 3: 'Zeus', 4: 'frei'}


def glyph_inlay(boss):
    rows = GLYPHS[boss]
    shadow, mid, light = ACCENTS[boss]

    def paint(x, y):
        if x in (0, 15) or y in (0, 15):
            return ramp(GOLD, 0.6)
        c = rows[y][x]
        if c == 'x':
            return shadow
        if c == '#':
            return mid
        if c == '+':
            return light
        return None
    return paint


def ring_inlay(x, y):
    """Freie Variante: Goldring mit Kern, wie der Opferstein, nur ohne Licht."""
    if x in (0, 15) or y in (0, 15):
        return ramp(GOLD, 0.6)
    r = math.hypot(x - 7.5, y - 7.5)
    if r < 1.8:
        return ramp(GOLD, 0.82)
    if 4.2 < r < 5.4:
        return ramp(GOLD, 0.55)
    return None


def side_inlay(accent):
    """Säulenrillen wie der Olymp-Altar; oben ein Band in der Bossfarbe, unten Gold, mittig eine kleine Raute."""
    def paint(x, y):
        if y in (0, 1):
            return accent[1] if y == 1 else accent[0]
        if y in (14, 15):
            return ramp(GOLD, 0.7 if y == 14 else 0.5)
        d = abs(x - 7.5) + abs(y - 7.5)
        if d < 1.6:
            return accent[2]
        if d < 2.6:
            return accent[1]
        if d < 3.4:
            return accent[0]
        if x % 4 == 1:
            return ramp(MARBLE, 0.2)
        return None
    return paint


GENERIC_SIDE_ACCENT = [ramp(GOLD, 0.35), ramp(GOLD, 0.6), ramp(GOLD, 0.9)]


def stele_olymp(x, y):
    """Stele des Olymp (STORY 6–15): dieselben Schriftzeilen wie die Tartaros-Stele, aber Gold auf Marmor."""
    if x in (2, 13) or y in (0, 15):
        return ramp(GOLD, 0.45)
    if (x % 4 == 1 and y % 3 != 0) or (y % 6 == 3 and 3 < x < 12):
        return ramp(GOLD, 0.7)
    return None


def textures():
    save(stone_face(MARBLE, 540, stele_olymp), 'textures/block/stele_olymp.png')
    for boss in range(4):
        save(stone_face(MARBLE, 520 + boss, glyph_inlay(boss)), f'textures/block/boss_altar_{boss}_top.png')
        save(stone_face(MARBLE, 530 + boss, side_inlay(ACCENTS[boss])), f'textures/block/boss_altar_{boss}_side.png')
    save(stone_face(MARBLE, 524, ring_inlay), 'textures/block/boss_altar_top.png')
    save(stone_face(MARBLE, 534, side_inlay(GENERIC_SIDE_ACCENT)), 'textures/block/boss_altar_side.png')


def write_json(rel, data):
    path = os.path.join(ASSETS, rel)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def models():
    def cube(top, side):
        return {'parent': 'minecraft:block/cube_bottom_top',
                'textures': {'top': top, 'bottom': side, 'side': side}}
    for boss in range(4):
        write_json(f'models/block/boss_altar_{boss}.json',
                   cube(f'hha:block/boss_altar_{boss}_top', f'hha:block/boss_altar_{boss}_side'))
    write_json('models/block/boss_altar.json', cube('hha:block/boss_altar_top', 'hha:block/boss_altar_side'))
    variants = {f'boss={b}': {'model': f'hha:block/boss_altar_{b}' if b < 4 else 'hha:block/boss_altar'} for b in range(5)}
    write_json('blockstates/boss_altar.json', {'variants': variants})


# --------------------------------------------------------------------------------------------------------------------
# Vorschau (reines Python: PNG lesen/schreiben mit zlib)
# --------------------------------------------------------------------------------------------------------------------

def read_png(path):
    data = open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', path
    pos, idat = 8, b''
    w = h = depth = ctype = 0
    palette, trns = [], []
    while pos < len(data):
        length = struct.unpack('>I', data[pos:pos + 4])[0]
        kind, body = data[pos + 4:pos + 8], data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b'IHDR':
            w, h, depth, ctype = struct.unpack('>IIBB', body[:10])
        elif kind == b'PLTE':
            palette = [tuple(body[i:i + 3]) for i in range(0, len(body), 3)]
        elif kind == b'tRNS':
            trns = list(body)
        elif kind == b'IDAT':
            idat += body
    raw = zlib.decompress(idat)
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    bits = depth * channels
    stride = (w * bits + 7) // 8
    bpp = max(1, bits // 8)
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        prev = line
    px = []
    for line in rows:
        for x in range(w):
            if ctype == 3:
                per = 8 // depth
                idx = (line[x // per] >> (8 - depth * (x % per + 1))) & ((1 << depth) - 1)
                r, g, b = palette[idx]
                px.append((r, g, b, trns[idx] if idx < len(trns) else 255))
            else:
                v = line[x * channels:(x + 1) * channels]
                if ctype == 0:
                    px.append((v[0], v[0], v[0], 255))
                elif ctype == 4:
                    px.append((v[0], v[0], v[0], v[1]))
                elif ctype == 2:
                    px.append((v[0], v[1], v[2], 255))
                else:
                    px.append((v[0], v[1], v[2], v[3]))
    return w, h, px


def write_png(path, w, h, px):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            r, g, b = px[y * w + x][:3]
            raw += bytes((r, g, b))

    def chunk(kind, body):
        return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body) & 0xffffffff)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)))
        f.write(chunk(b'IDAT', zlib.compress(bytes(raw), 9)))
        f.write(chunk(b'IEND', b''))


class Canvas:
    def __init__(self, w, h, bg):
        self.w, self.h = w, h
        self.px = [bg] * (w * h)

    def put(self, x, y, c, a=1.0):
        if 0 <= x < self.w and 0 <= y < self.h:
            o = self.px[y * self.w + x]
            self.px[y * self.w + x] = tuple(int(o[i] + (c[i] - o[i]) * a) for i in range(3))

    def blit(self, tex, ox, oy, scale, shade=1.0):
        w, h, px = tex
        for y in range(h * scale):
            for x in range(w * scale):
                r, g, b, a = px[(y // scale) * w + x // scale]
                if a:
                    self.put(ox + x, oy + y, (int(r * shade), int(g * shade), int(b * shade)), a / 255)

    def save(self, path):
        write_png(path, self.w, self.h, self.px)


def block_tex(name):
    return read_png(os.path.join(ASSETS, 'textures', 'block', name + '.png'))


def preview_blocks(out):
    """Oben die vorhandenen HHA-Blöcke, unten die neuen Altäre; je Block Oberseite und Seite (Seite leicht schattiert)."""
    old = [('olympus_altar_top', 'olympus_altar_side'), ('offering_pedestal_top', 'offering_pedestal_side'),
           ('hades_altar_top', 'hades_altar_side'), ('hades_seal', 'hades_seal'), ('stele', 'stele_olymp')]
    new = [(f'boss_altar_{b}_top', f'boss_altar_{b}_side') for b in range(4)] + [('boss_altar_top', 'boss_altar_side')]
    scale, pad = 8, 16
    cell = 16 * scale
    width = pad + 5 * (2 * cell + pad * 2)
    height = pad + 2 * (cell + pad * 2)
    c = Canvas(width, height, (34, 36, 42))
    for row, items in enumerate((old, new)):
        for i, (top, side) in enumerate(items):
            x = pad + i * (2 * cell + pad * 2)
            y = pad + row * (cell + pad * 2)
            c.blit(block_tex(top), x, y, scale)
            c.blit(block_tex(side), x + cell + 4, y, scale, 0.82)
    c.save(out)


# --- Kontrast der Ansagen -------------------------------------------------------------------------------------------

STYLES = {
    # Name: (Kern, Haupt, Rand) aus VfxDraw.kt; Gefahrstile in ihrer Gefahrenvariante (Palette.danger).
    'GOLD': ('#fff4c2', '#ffc23c', '#b06a16'),
    'HEAVEN': ('#ffffff', '#b8e2ff', '#4f8fd0'),
    'HELL': ('#d8fdff', '#3cd6ef', '#0b7690'),
    'FIRE': ('#ffd0a8', '#ee5a1c', '#8c1e06'),
    'SOUL': ('#d8fdff', '#14a6c4', '#064a5c'),
    'STORM': ('#ffffff', '#d8c2ff', '#7a4fc0'),
    'SHADOW': ('#eadcff', '#a27cff', '#4a2c8a'),
    'HADES': ('#e8ffd0', '#4cff5a', '#0e7a2c'),
    'AEON': ('#eef2ff', '#6a7cff', '#26346c'),
    'THUNDER': ('#ffffff', '#8fc7ff', '#1d4fb8'),
    'MOON': ('#f1edff', '#9a86e8', '#3b2e7a'),
    'CRIMSON': ('#ffd9d2', '#e0483c', '#7a1410'),
}

# Werte aus VfxEffects.kt (TelegraphEffect): Füllung 0,25 → 0,45, Rand 0,95, Kontur 0,6 (Rand × 0,55).
FILL_START, FILL_END, RIM_ALPHA, OUTLINE_ALPHA, OUTLINE_DARK = 0.25, 0.45, 0.95, 0.6, 0.55


def rgb(h):
    c = hexc(h)
    return c[:3]


def over(bg, c, a):
    return tuple(bg[i] + (c[i] - bg[i]) * a for i in range(3))


def lum(c):
    def ch(v):
        v /= 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = (ch(v) for v in c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    la, lb = lum(a), lum(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def delta_e(a, b):
    """Grober Farbabstand (Rotmittel-Formel), reicht zum Vergleich Gold gegen Gefahr."""
    rm = (a[0] + b[0]) / 2
    dr, dg, db = a[0] - b[0], a[1] - b[1], a[2] - b[2]
    return math.sqrt((2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db)


def floor_tex(name):
    """Vanilla-Bodentextur aus der Spiel-Jar (Marmor = Quarzblock, Schwarzstein = polierter Schwarzstein)."""
    import glob
    import zipfile
    jars = glob.glob(os.path.join(ROOT, '.gradle', 'loom-cache', 'minecraftMaven', 'net', 'minecraft', 'minecraft-merged-*', '**', '*.jar'), recursive=True)
    jars += glob.glob(os.path.expanduser('~/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar'))
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as z:
                data = z.read(f'assets/minecraft/textures/block/{name}.png')
            tmp = os.path.join(ROOT, 'tmp', f'_floor_{name}.png')
            open(tmp, 'wb').write(data)
            tex = read_png(tmp)
            os.remove(tmp)
            return tex
        except (KeyError, zipfile.BadZipFile, OSError):
            continue
    return None


def average_tex(tex, fallback):
    if tex is None:
        return fallback
    w, h, px = tex
    n = len(px)
    return tuple(int(sum(p[i] for p in px) / n) for i in range(3))


def fill_color(style):
    """Füllfarbe wie TelegraphEffect.body: Gold (sicher) 30 % Richtung Kern."""
    core, main, _ = (rgb(v) for v in STYLES[style])
    k = 0.3 if style == 'GOLD' else 0.0
    return tuple(int(main[i] + (core[i] - main[i]) * k) for i in range(3))


def rim_color(style):
    core, main, _ = (rgb(v) for v in STYLES[style])
    return tuple(int(main[i] + (core[i] - main[i]) * 0.35) for i in range(3))


def outline_color(style):
    edge = rgb(STYLES[style][2])
    return tuple(int(v * OUTLINE_DARK) for v in edge)


def preview_telegraphs(out, report):
    """Je Stil eine Ansage auf Marmor (oben) und Schwarzstein (unten): Kontur, Rand, Füllung 0,45 (kurz vor dem Treffer)."""
    floors = [('quartz_block_side', (236, 230, 222)), ('polished_blackstone', (52, 45, 52))]
    textures = [floor_tex(name) for name, _ in floors]
    bases = [average_tex(t, fb) for t, (_, fb) in zip(textures, floors)]
    names = list(STYLES)
    size, pad = 96, 8
    c = Canvas(pad + len(names) * (size + pad), pad + 2 * (size + pad), (20, 20, 24))
    lines = ['| Stil | Rand/Marmor | Rand/Schwarzstein | Füllung 0,45/Marmor | Füllung 0,45/Schwarzstein | Abstand zu Gold (Füllung, Marmor) |',
             '| --- | --- | --- | --- | --- | --- |']
    gold_fill = [over(b, fill_color('GOLD'), FILL_END) for b in bases]
    for col, style in enumerate(names):
        main = fill_color(style)
        rim = rim_color(style)
        dark = outline_color(style)
        for row, tex in enumerate(textures):
            ox, oy = pad + col * (size + pad), pad + row * (size + pad)
            base = bases[row]
            for y in range(size):
                for x in range(size):
                    if tex:
                        w, h, px = tex
                        p = px[((y // 6) % h) * w + (x // 6) % w][:3]
                    else:
                        p = base
                    r = math.hypot(x - size / 2 + 0.5, y - size / 2 + 0.5)
                    color = p
                    radius, rim_w = size * 0.38, 2.2
                    if r <= radius:
                        color = over(color, main, FILL_END)
                    if abs(r - radius) <= rim_w * 1.9:
                        color = over(color, dark, OUTLINE_ALPHA)
                    if abs(r - radius) <= rim_w:
                        color = over(color, rim, RIM_ALPHA)
                    if style == 'GOLD' and abs(r - (radius - 5 * 1.0 - rim_w * 2)) <= rim_w * 0.6:
                        color = over(color, rim, RIM_ALPHA)  # Doppelrand der sicheren Fläche
                    c.put(ox + x, oy + y, tuple(int(v) for v in color))
        rim_m = over(over(bases[0], dark, OUTLINE_ALPHA), rim, RIM_ALPHA)
        rim_b = over(over(bases[1], dark, OUTLINE_ALPHA), rim, RIM_ALPHA)
        out_m = over(bases[0], dark, OUTLINE_ALPHA)
        fill_m = over(bases[0], main, FILL_END)
        fill_b = over(bases[1], main, FILL_END)
        gold_d = '–' if style == 'GOLD' else f'{delta_e(fill_m, gold_fill[0]):.0f}'
        lines.append(f'| {style} | Kontur {contrast(out_m, bases[0]):.1f}:1 | Linie {contrast(rim_b, bases[1]):.1f}:1 | '
                     f'{contrast(fill_m, bases[0]):.2f}:1 | {contrast(fill_b, bases[1]):.2f}:1 | {gold_d} |')
    c.save(out)
    with open(report, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# Kontrast der Ansagen (berechnet von function_blocks.py --preview)\n\n')
        f.write(f'Boden: Marmor = Quarzblock (Mittel {bases[0]}), Schwarzstein = polierter Schwarzstein (Mittel {bases[1]}).\n')
        f.write('Kontur = dunkler Saum der Randlinie (trägt auf hellem Boden), Linie = helle Randlinie (trägt auf dunklem).\n')
        f.write('Abstand zu Gold: Farbabstand der Füllung zur goldenen Füllung auf Marmor (Rotmittel-Formel; > 150 klar).\n\n')
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))


def main():
    textures()
    flush()
    models()
    print('Boss-Altäre geschrieben:', ', '.join(f'{b} {n}' for b, n in BOSS_NAMES.items()))
    if '--preview' in sys.argv:
        out_dir = os.path.join(ROOT, 'tmp', 'overhaul2', 'previews', 'effekte')
        preview_blocks(os.path.join(out_dir, 'bloecke.png'))
        preview_telegraphs(os.path.join(out_dir, 'ansagen_kontrast.png'), os.path.join(out_dir, 'ansagen_kontrast.md'))
        print('Vorschauen in', out_dir)


if __name__ == '__main__':
    main()
