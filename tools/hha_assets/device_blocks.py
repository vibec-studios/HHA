"""Arena-Geräte und Gewitterwolke: Modelle, Texturen, Blockstates, Vorschau (Überarbeitung 4, PLAN 2.1/2.3/7.8; P05).

Aufruf (aus dem Projektordner):
    python tools/hha_assets/device_blocks.py             # Modelle, Blockstates, Item-Modelle, Texturen schreiben
    python tools/hha_assets/device_blocks.py --preview   # zusätzlich Belege unter tmp/overhaul4/previews/P05/

Vier Geräte (`messenger_bell`, `grounding_rod`, `chain_post`, `sluice_wheel`) mit je drei Fassungen `<id>_mode0..2`
(0 still, 1 golden = wirkt jetzt, 2 verbraucht). Die Modelle sind nach Norden gebaut (Vorderseite z 0, Wand z 16); der
Blockstate dreht sie über `facing` (Osten 90°, Süden 180°, Westen 270°) wie `deco_registry.py`. Das Item zeigt Zustand 0.

Machart (PLAN 2.3):
- Wenige, klare Elemente (10–24 je Fassung), Kanten gebrochen, Rundungen aus zwei bis drei Stufen bzw. aus gedrehten
  Leisten (Minecraft 1.21.11 erlaubt freie Drehwinkel je Element: `ModelElementRotation$OfAxisAngle`).
- Texturen 32 px (2 Texel je Modell-Einheit), UV = Lage des Elements (wie die Vanilla-Automatik, hier ausgeschrieben).
  Nur Rampen-Stufen 0–7 aus `stonekit.RAMPS` (BRONZE, DARK_BRONZE, MARBLE, BASALT, GOLD), Licht oben links.
- Goldene Teile gibt es nur im Zustand 1, je als eigenes Element mit `"light_emission": 15, "shade": false`.
- Je Gerät ein Satz Werkstoff-Texturen `<id>_<werkstoff>.png` und die Akzent-Textur `<id>_mode<n>.png` (die Teile, die
  den Zustand zeigen: Glockenrand und Flügel, Griff, Ring und Kette, Rad). `deco_registry.py` erwartet genau diese
  `<id>_mode<n>.png` und schreibt sie nicht, solange sie da sind.

Gewitterwolke `storm_cloud`: 32 × 512 (16 Bilder, `frametime` 4, `interpolate`), zwei periodische Rauschlagen (Schlieren,
die einmal je Umlauf um eine Kachel ziehen, und eine kreisende Lage), Rampe CLOUD, Alpha 150–215, Licht oben links.

Vorschau (`--preview`): eigener kleiner Elemente-Zeichner (orthografisch, schräg von vorn, Licht oben links), je Gerät
die drei Zustände; dazu Texturblätter, Wolke 3 × 3 und vier Einzelbilder der Wolke.
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(__file__))
try:  # Rampen und Rauschen aus dem Baukasten von P01; Rückfall auf die Startwerte aus PLAN 2.2
    from stonekit import RAMPS, h01, pn  # noqa: E402
except Exception:  # pragma: no cover - nur, falls stonekit fehlt
    def _r(t):
        return [tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) for h in t.split()]
    RAMPS = {
        'MARBLE': _r('6f6a7c 8d8898 aaa5b0 c3bec4 d8d3cf e7e2da f3efe6 fffbf2'),
        'GOLD': _r('4a2408 6e3a0c 935414 b8761e d69a2c ecbd48 f8db7c fff3c4'),
        'BRONZE': _r('2e1a0c 4a2a12 6b3e18 8c5622 ad7230 c9924a e0b56e f3d9a0'),
        'CLOUD': _r('3b4152 525a6e 6c7689 8892a3 a9b2c0'),
        'BASALT': _r('0a080e 15121c 201b29 2c2536 393045 473b54 574763 6c5878'),
        'DARK_BRONZE': _r('0d1210 1a211c 2a332a 3d4a3a 55634a 74805c 98a078 c2c8a0'),
    }

    def h01(seed, x, y):
        v = math.sin(seed * 12.9898 + x * 78.233 + y * 37.719) * 43758.5453
        return v - math.floor(v)

    def pn(x, y, cells, seed, n=32, cy=None, ph=None):
        cy = cells if cy is None else cy
        ph = n if ph is None else ph
        fx, fy = x * cells / n, y * cy / ph
        x0, y0 = math.floor(fx), math.floor(fy)
        tx, ty = fx - x0, fy - y0
        tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
        xa, xb, ya, yb = x0 % cells, (x0 + 1) % cells, y0 % cy, (y0 + 1) % cy
        a, b, c, d = h01(seed, xa, ya), h01(seed, xb, ya), h01(seed, xa, yb), h01(seed, xb, yb)
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
ASSETS = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'hha')
TEX = os.path.join(ASSETS, 'textures', 'block')
MODELS = os.path.join(ASSETS, 'models', 'block')
STATES = os.path.join(ASSETS, 'blockstates')
ITEMS = os.path.join(ASSETS, 'items')
PREVIEW = os.path.join(ROOT, 'tmp', 'overhaul4', 'previews', 'P05')

N = 32
DEVICES = ['messenger_bell', 'grounding_rod', 'chain_post', 'sluice_wheel']
FACINGS = (('north', 0), ('east', 90), ('south', 180), ('west', 270))
MODES = (0, 1, 2)

# Akzent-Rampe je Gerät und Zustand: (Rampe, Verschiebung der Stufen). Gold nur im Zustand 1.
ACCENT = {
    'messenger_bell': [('BRONZE', 0), ('GOLD', 1), ('BRONZE', -1)],
    'grounding_rod': [('BRONZE', 0), ('GOLD', 1), ('DARK_BRONZE', 0)],      # Kupfergriff frisch · golden · oxidiert
    'chain_post': [('DARK_BRONZE', 1), ('GOLD', 1), ('DARK_BRONZE', -1)],
    'sluice_wheel': [('DARK_BRONZE', 0), ('GOLD', 1), ('DARK_BRONZE', -1)],
}


def lim(v, lo, hi):
    return max(lo, min(hi, v))


# =============================================================================================
# Texturen (Index-Raster 0–7 → Rampe)
# =============================================================================================

def grid(v=4):
    return [[v] * N for _ in range(N)]


def paint(lv, ramp, shift=0, alpha=None):
    pal = RAMPS[ramp]
    img = Image.new('RGBA', (N, len(lv)))
    px = img.load()
    for y, row in enumerate(lv):
        for x, v in enumerate(row):
            if v is None:
                px[x, y] = (0, 0, 0, 0)
                continue
            a = 255 if alpha is None else alpha[y][x]
            px[x, y] = pal[lim(v + shift, 0, len(pal) - 1)] + (a,)
    return img


def grain(seed, lo=3, hi=5, cells=8):
    """Ruhige Fläche: zwei Rauschlagen auf die Stufen lo…hi geplättet, einzelne Lichtkörner mit Schatten unten rechts."""
    lv = grid()
    for y in range(N):
        for x in range(N):
            n = pn(x, y, cells, seed) * 0.6 + pn(x, y, cells * 2, seed + 1) * 0.4
            lv[y][x] = hi if n > 0.64 else lo if n < 0.34 else (lo + hi) // 2
    for y in range(N):
        for x in range(N):
            if h01(seed + 7, x, y) > 0.985:
                lv[y][x] = min(lv[y][x] + 2, 7)
                if x + 1 < N and y + 1 < N:
                    lv[y + 1][x + 1] = max(lv[y + 1][x + 1] - 1, 0)
    return lv


def frame(lv, x0, y0, x1, y1, light=6, dark=2):
    """Erhabene Kante: oben/links hell, unten/rechts dunkel, gemischte Ecken."""
    for i in range(x0, x1 + 1):
        lv[y0][i] = light
        lv[y1][i] = dark
    for i in range(y0, y1 + 1):
        lv[i][x0] = light
        lv[i][x1] = dark
    lv[y1][x0] = (light + dark) // 2
    lv[y0][x1] = (light + dark) // 2
    return lv


def groove(lv, x0, y0, x1, y1, dark=2, light=5):
    """Eingelassene Rille: Wand oben/links dunkel, Lippe unten/rechts hell."""
    return frame(lv, x0, y0, x1, y1, light=dark, dark=light)


def rivet(lv, x, y):
    lv[y][x] = 6
    lv[y][x + 1] = 5
    lv[y + 1][x] = 5
    lv[y + 1][x + 1] = 3
    if y + 2 < N and x + 2 < N:
        lv[y + 2][x + 2] = min(lv[y + 2][x + 2], 2)


def sheen(lv, cols, amount=1):
    """Senkrechter Glanzstreifen (gegossenes Metall, Licht von links)."""
    for y in range(N):
        for x in cols:
            lv[y][x] = min(lv[y][x] + amount, 7)
    return lv


def tex_marble(seed):
    """Polierter Marmor mit weicher Ader und Fase am Rand (Sockel der Glocke)."""
    lv = grain(seed, 4, 6)
    for y in range(N):
        for x in range(N):
            v = abs(pn(x + 3 * math.sin(y / 5.0), y, 3, seed + 20) - 0.5)
            if v < 0.03:
                lv[y][x] -= 1
    frame(lv, 0, 0, N - 1, N - 1, light=7, dark=3)
    frame(lv, 1, 1, N - 2, N - 2, light=6, dark=3)
    return lv


def metal(seed, base=4, sheen_cols=range(4, 7), dent_cells=5):
    """Ruhiges Metall: Grundton [base], breite weiche Flecken ±1 (niedrige Frequenz), wenige Dellen mit Licht oben
    links, seltene Lichtpunkte, ein senkrechter Glanzstreifen links und unten eine Stufe dunkler (Licht von oben)."""
    lv = grid(base)
    for y in range(N):
        for x in range(N):
            n = pn(x, y, 4, seed) * 0.7 + pn(x, y, 8, seed + 1) * 0.3
            lv[y][x] = base + (1 if n > 0.76 else -1 if n < 0.2 else 0)
            if pn(x, y, dent_cells, seed + 3) < 0.16:
                d = pn(x + 1, y + 1, dent_cells, seed + 3) - pn(x - 1, y - 1, dent_cells, seed + 3)
                lv[y][x] += 1 if d > 0.05 else -1 if d < -0.05 else 0
            if y >= N - 3:
                lv[y][x] -= 1
    sheen(lv, sheen_cols)
    for y in range(N - 1):
        for x in range(N - 1):
            if h01(seed + 7, x, y) > 0.992:
                lv[y][x] = 6
                lv[y + 1][x + 1] = base - 1
    return lv


def tex_cast(seed, base=4, band_rows=()):
    """Gegossene Bronze: [metal]; optional erhabene Bänder (Zeile Licht, darunter Grundton, dann Schatten)."""
    lv = metal(seed, base)
    for r in band_rows:
        for x in range(N):
            lv[r][x] = 6
            lv[r + 1][x] = 5
            lv[r + 2][x] = 2
    return lv


def tex_feathers(seed):
    """Glockenrand und Flügel: Federreihen zu 4 Zeilen (Kante oben hell, Fuß dunkel), Kiele alle 4 Spalten versetzt."""
    lv = grid(4)
    for y in range(N):
        row, ly = divmod(y, 4)
        for x in range(N):
            lx = (x + (2 if row % 2 else 0)) % 4
            v = (5, 4, 4, 2)[ly]
            if lx == 0:
                v = min(v, 3)
            elif lx == 1 and ly < 2:
                v = 6
            lv[y][x] = v
    return lv


def tex_plate(seed):
    """Erdungsplatte: zwei Bronzeplatten übereinander, Fuge dunkel, Kante oben/links hell, Nieten in den Ecken."""
    lv = metal(seed, 4, sheen_cols=())
    for (y0, y1) in ((0, 15), (16, 31)):
        frame(lv, 0, y0, N - 1, y1, light=6, dark=1)
        frame(lv, 1, y0 + 1, N - 2, y1 - 1, light=5, dark=3)
        for (x, y) in ((3, y0 + 3), (N - 5, y0 + 3), (3, y1 - 4), (N - 5, y1 - 4)):
            rivet(lv, x, y)
    return lv


def tex_grip(seed):
    """Griff mit Wicklung: Rillen alle 3 Zeilen (Lippe hell, Rille dunkel), Glanz links."""
    lv = grid(4)
    for y in range(N):
        for x in range(N):
            lv[y][x] = (6, 4, 2)[y % 3]
    sheen(lv, range(2, 5))
    return lv


def tex_patina(seed):
    """Dunkle Bronze mit Patina: ruhiger Grund, wenige blasse Grünspan-Flecken mit Lichtkante oben links, seltene
    senkrechte Laufspuren."""
    lv = metal(seed, 3, sheen_cols=range(3, 5))
    for y in range(N):
        for x in range(N):
            p = pn(x, y, 4, seed + 30) * 0.75 + pn(x, y, 8, seed + 31) * 0.25
            if p > 0.72:
                lv[y][x] = 6 if p > 0.8 else 5
            elif p > 0.69 and pn(x + 1, y + 1, 4, seed + 30) > 0.72:
                lv[y][x] = 2                                   # Schattenkante oben links des Flecks
            if pn(x, y * 0.25, 16, seed + 40) > 0.9 and y > 4:
                lv[y][x] = max(lv[y][x] - 1, 1)
    return lv


def tex_basalt(seed):
    """Polierter Basalt mit Fase, eingelassenem Rahmen und vier Nieten (Platte des Schleusenrads)."""
    lv = grid(4)
    for y in range(N):
        for x in range(N):
            n = pn(x, y, 4, seed) * 0.7 + pn(x, y, 8, seed + 1) * 0.3
            lv[y][x] = 5 if n > 0.7 else 3 if n < 0.25 else 4
    frame(lv, 0, 0, N - 1, N - 1, light=6, dark=1)
    groove(lv, 3, 3, N - 4, N - 4, dark=1, light=5)
    for (x, y) in ((5, 5), (N - 7, 5), (5, N - 7), (N - 7, N - 7)):
        rivet(lv, x, y)
    return lv


def tex_wheel(seed):
    """Gegossenes Rad: ruhiges Metall mit Glanz links."""
    return metal(seed, 4, sheen_cols=range(2, 5))


def tex_vein(seed):
    """Blitzader in der Erdungsplatte (Zustand 1): Zickzack von oben nach unten mit zwei Ästen, Röhre mit Lichtseite
    links, durchsichtig daneben (CUTOUT). Liegt auf der Fläche u 4,5…11,5 / v 3…13 (Spalten 9…23, Zeilen 6…26)."""
    lv = [[None] * N for _ in range(N)]

    def stroke(points, core=7, edge=5):
        for (xa, ya), (xb, yb) in zip(points, points[1:]):
            steps = int(max(abs(xb - xa), abs(yb - ya)) * 3) + 1
            for s in range(steps + 1):
                t = s / steps
                x, y = round(xa + (xb - xa) * t), round(ya + (yb - ya) * t)
                for dx, v in ((-1, edge), (0, core), (1, edge - 2)):
                    if 0 <= x + dx < N and 0 <= y < N and (lv[y][x + dx] is None or lv[y][x + dx] < v):
                        lv[y][x + dx] = v

    main = [(16, 6), (14, 9), (18, 12), (15, 15), (19, 18), (16, 21), (18, 24), (16, 26)]
    stroke(main)
    stroke([(14, 9), (11, 11), (10, 14)], core=6, edge=4)
    stroke([(19, 18), (22, 20), (22, 23)], core=6, edge=4)
    return lv


def tex_water(frame_no, frames=8):
    """Wasserrinne im Zustand 1: dunkles Wasser mit goldenen Lichtstreifen, die je Bild 32/8 Zeilen nach unten
    wandern (eine Periode je Umlauf → nahtlos in der Zeit). Nur Helligkeit wandert, die Form steht."""
    lv = grid(1)
    t = frame_no / frames
    for y in range(N):
        for x in range(N):
            wave = math.sin(2 * math.pi * ((y / N) - t) * 2 + math.sin(2 * math.pi * x / N) * 0.8)
            n = pn(x, y - t * N, 4, 91) * 0.5 + 0.5 * (wave * 0.5 + 0.5)
            lv[y][x] = 5 if n > 0.8 else 4 if n > 0.66 else 3 if n > 0.5 else 2 if n > 0.3 else 1
    return lv


def accent_tex(device, mode):
    ramp, shift = ACCENT[device][mode]
    if device == 'messenger_bell':
        lv = tex_feathers(11)
    elif device == 'grounding_rod':
        lv = tex_grip(21)
        if mode == 2:                                           # oxidiert: Grünspan-Flecken über der Wicklung
            for y in range(N):
                for x in range(N):
                    if pn(x, y, 6, 23) > 0.62:
                        lv[y][x] = 6 if lv[y][x] >= 4 else 4
    elif device == 'chain_post':
        lv = metal(31, 4, sheen_cols=range(3, 6))
    else:
        lv = tex_wheel(41)
    return paint(lv, ramp, shift)


def device_textures():
    """Alle Texturen der Geräte: Name → (Bild, mcmeta oder None)."""
    out = {}
    for d in DEVICES:
        for m in MODES:
            out[f'{d}_mode{m}'] = (accent_tex(d, m), None)
    out['messenger_bell_marble'] = (paint(tex_marble(12), 'MARBLE'), None)
    out['messenger_bell_bronze'] = (paint(sheen(tex_cast(13), range(4, 6)), 'BRONZE'), None)
    out['grounding_rod_plate'] = (paint(tex_plate(22), 'BRONZE'), None)
    out['grounding_rod_bronze'] = (paint(tex_cast(24), 'BRONZE'), None)
    vein = tex_vein(25)
    out['grounding_rod_vein'] = (paint(vein, 'GOLD'), None)
    out['chain_post_bronze'] = (paint(tex_patina(33), 'DARK_BRONZE'), None)
    out['sluice_wheel_basalt'] = (paint(tex_basalt(42), 'BASALT'), None)
    out['sluice_wheel_bronze'] = (paint(tex_cast(43), 'DARK_BRONZE'), None)
    water = Image.new('RGBA', (N, N * 8))
    for f in range(8):
        water.paste(paint(tex_water(f), 'GOLD'), (0, f * N))
    out['sluice_wheel_water'] = (water, {'animation': {'frametime': 3, 'interpolate': True}})
    return out


# --- Gewitterwolke ----------------------------------------------------------------------------

CLOUD_FRAMES = 16
CLOUD_FRAMETIME = 4
CLOUD_ALPHA = (150, 215)


def cloud_density(x, y, t):
    """Dichte 0…1 bei (x, y) im Bild t ∈ [0, 1): Schlieren (waagerecht gestreckt), die je Umlauf genau eine Kachel
    nach rechts ziehen, plus eine Lage, die auf einem kleinen Kreis kreist. Beide periodisch → nahtlos in Ort und Zeit."""
    sx = x + N * t
    streak = pn(sx, y, 2, 61, cy=4) * 0.7 + pn(sx, y, 4, 62, cy=8) * 0.3
    cx, cy = x + 4 * math.cos(2 * math.pi * t), y + 4 * math.sin(2 * math.pi * t)
    puff = pn(cx, cy, 4, 63) * 0.8 + pn(cx, cy, 8, 64) * 0.2
    return lim((streak * 0.6 + puff * 0.4 - 0.25) / 0.5, 0.0, 1.0)


def cloud_frame(t):
    pal = RAMPS['CLOUD']
    img = Image.new('RGBA', (N, N))
    px = img.load()
    for y in range(N):
        for x in range(N):
            d = cloud_density(x, y, t)
            # Licht oben links: wo die Dichte nach oben links abfällt, ist die Kante beleuchtet.
            lit = d - cloud_density((x - 1) % N, (y - 1) % N, t)
            idx = 1 + round(d * 2.6) + (1 if lit > 0.09 else -1 if lit < -0.09 else 0)
            idx = lim(idx, 0, len(pal) - 1)
            a = round(CLOUD_ALPHA[0] + (CLOUD_ALPHA[1] - CLOUD_ALPHA[0]) * d)
            px[x, y] = pal[idx] + (a,)
    return img


def cloud_strip():
    img = Image.new('RGBA', (N, N * CLOUD_FRAMES))
    for f in range(CLOUD_FRAMES):
        img.paste(cloud_frame(f / CLOUD_FRAMES), (0, f * N))
    return img


# =============================================================================================
# Modelle
# =============================================================================================

FACE_DIRS = ('north', 'south', 'east', 'west', 'up', 'down')


class El:
    """Ein Modell-Element: Kasten (Modell-Einheiten), Werkstoff je Fläche, optional Drehung um eine Achse."""

    def __init__(self, fr, to, tex, faces=FACE_DIRS, axis=None, angle=0.0, origin=None, glow=False):
        self.fr, self.to = [float(v) for v in fr], [float(v) for v in to]
        self.tex = tex
        self.faces = faces
        self.axis, self.angle = axis, float(angle)
        self.origin = [float(v) for v in origin] if origin is not None else self.center()
        self.glow = glow

    def center(self):
        return [(a + b) / 2 for a, b in zip(self.fr, self.to)]

    def json(self):
        x0, y0, z0 = self.fr
        x1, y1, z1 = self.to
        faces = {}
        for f in self.faces:
            face = {'uv': [round(v, 4) for v in auto_uv(f, self.fr, self.to)], 'texture': '#' + self.tex}
            if not self.axis:  # Flächen auf der Blockgrenze dürfen wegfallen, wenn der Nachbar voll ist
                cull = {'down': y0 == 0, 'up': y1 == 16, 'north': z0 == 0, 'south': z1 == 16, 'west': x0 == 0, 'east': x1 == 16}[f]
                if cull:
                    face['cullface'] = f
            faces[f] = face
        out = {'from': [round(v, 4) for v in self.fr], 'to': [round(v, 4) for v in self.to]}
        if self.axis and self.angle:
            out['rotation'] = {'origin': [round(v, 4) for v in self.origin], 'axis': self.axis, 'angle': round(self.angle, 4)}
        if self.glow:
            out['shade'] = False
            out['light_emission'] = 15
        out['faces'] = faces
        return out


def auto_uv(face, fr, to):
    """UV wie die Vanilla-Automatik (`ModelElementFace`): Lage des Elements im Block."""
    x0, y0, z0 = fr
    x1, y1, z1 = to
    return {
        'down': [x0, 16 - z1, x1, 16 - z0],
        'up': [x0, z0, x1, z1],
        'north': [16 - x1, 16 - y1, 16 - x0, 16 - y0],
        'south': [x0, 16 - y1, x1, 16 - y0],
        'west': [z0, 16 - y1, z1, 16 - y0],
        'east': [16 - z1, 16 - y1, 16 - z0, 16 - y0],
    }[face]


def centred(cx, cy, cz, sx, sy, sz, tex, **kw):
    return El((cx - sx / 2, cy - sy / 2, cz - sz / 2), (cx + sx / 2, cy + sy / 2, cz + sz / 2), tex, **kw)


def rot2(x, y, deg):
    a = math.radians(deg)
    return x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)


def tilt_z(els, pivot, deg):
    """Kippt eine Gruppe starr um die z-Achse durch [pivot]: jedes Element dreht um die eigene Mitte (Winkel addiert),
    die Mitte wandert mit. Geht nur für Elemente ohne oder mit z-Drehung um die eigene Mitte."""
    out = []
    for e in els:
        c = e.center()
        dx, dy = rot2(c[0] - pivot[0], c[1] - pivot[1], deg)
        nx, ny = pivot[0] + dx, pivot[1] + dy
        sx, sy, sz = (b - a for a, b in zip(e.fr, e.to))
        out.append(centred(nx, ny, c[2], sx, sy, sz, e.tex, faces=e.faces, axis='z', angle=e.angle + deg, glow=e.glow))
    return out


# --- Botenglocke --------------------------------------------------------------------------------

BELL_PIVOT = (8.0, 12.75, 8.0)     # Aufhängung am Ausleger (Drehpunkt beim Anschlagen)
BELL_SWING = 8.0                   # Zustand 2: Glocke schwingt nach dem Schlag (Grad um z)
# Flügel: je Seite drei Federn (Wurzelhöhe, Länge, Winkel über der Waagerechten); Zustand 2: Federn hängen.
BELL_FEATHERS = ((10.2, 3.0, 48.0), (9.5, 2.6, 28.0), (8.8, 2.1, 8.0))
BELL_FEATHERS_DOWN = (-12.0, -28.0, -44.0)
BELL_FEATHER_ROOT = 2.9            # Abstand der Federwurzel von der Mitte (Flanke 2,75)


def chamfered(x0, y0, z0, x1, y1, z1, c, tex, glow=False):
    """Achteckiger Querschnitt aus zwei gekreuzten Kästen (Kante um [c] gebrochen), ohne Drehung."""
    return [El((x0, y0, z0 + c), (x1, y1, z1 - c), tex, glow=glow), El((x0 + c, y0, z0), (x1 - c, y1, z1), tex, glow=glow)]


def bell(mode):
    gold = mode == 1
    els = [
        El((2, 0, 2), (14, 2, 14), 'marble'),                       # Sockel
        El((3, 2, 3), (13, 3, 13), 'marble'),                       # Sockelstufe (gebrochene Kante)
        El((1.75, 2, 10), (3.25, 4, 12.5), 'bronze'),               # Fußschuh links (Bügel steht hinter der Glocke)
        El((12.75, 2, 10), (14.25, 4, 12.5), 'bronze'),             # Fußschuh rechts
        El((2, 4, 10.5), (3, 12.5, 12), 'bronze'),                  # Bügel links
        El((13, 4, 10.5), (14, 12.5, 12), 'bronze'),                # Bügel rechts
        El((1.75, 12.5, 10.25), (14.25, 14, 12.25), 'bronze'),      # Joch
        El((7.25, 12.75, 7.25), (8.75, 13.75, 10.25), 'bronze'),    # Ausleger vom Joch zur Krone
    ]
    body = [El((7.25, 11.75, 7.25), (8.75, 12.75, 8.75), 'bronze')]            # Krone (Öse)
    body += chamfered(6, 10.25, 6, 10, 11.75, 10, 0.75, 'bronze')            # Schulter
    body += chamfered(5.25, 7.75, 5.25, 10.75, 10.25, 10.75, 1.0, 'bronze')  # Flanke
    body += chamfered(4.25, 6, 4.25, 11.75, 7.75, 11.75, 1.25, 'accent', glow=gold)   # Rand (golden im Zustand 1)
    body.append(El((7.5, 5, 7.5), (8.5, 6, 8.5), 'bronze'))                  # Klöppel
    for side in (-1, 1):
        for i, (root_y, length, deg) in enumerate(BELL_FEATHERS):
            if mode == 2:
                deg = BELL_FEATHERS_DOWN[i]
            rx = 8 + side * BELL_FEATHER_ROOT
            cx = rx + side * (length / 2) * math.cos(math.radians(deg))
            cy = root_y + (length / 2) * math.sin(math.radians(deg))
            body.append(centred(cx, cy, 8, length, 1.0, 0.6, 'accent', axis='z', angle=side * deg, glow=gold))
    if mode == 2:
        body = tilt_z(body, BELL_PIVOT, BELL_SWING)
    return els + body, {'marble': 'messenger_bell_marble', 'bronze': 'messenger_bell_bronze'}, 'messenger_bell_marble'


# --- Erdungsstab --------------------------------------------------------------------------------

ROD_PIVOT = (8.0, 8.5, 13.0)       # Lager des Hebels vor der Platte
ROD_LEAN = 6.0                     # Hebel steht leicht von der Wand ab (Grad um x)


def rod(mode):
    gold = mode == 1
    els = [
        El((3, 1, 14.5), (13, 15, 16), 'plate'),                    # Platte in der Wand
        El((2.5, 1.5, 14.75), (13.5, 14.5, 16), 'plate'),           # Platte, gebrochene Ecken
        El((4, 2.5, 14), (12, 13.5, 14.5), 'plate'),                # erhabenes Feld
        El((6.5, 7, 12.75), (9.5, 10, 14), 'bronze'),               # Lagerbock
        El((7, 7.5, 12.25), (9, 9.5, 12.75), 'bronze'),             # Lagerdeckel
        El((6.5, 1.5, 13.25), (9.5, 3, 14), 'bronze'),              # Erdkontakt unten
    ]
    for (x, y) in ((3.25, 1.75), (11.75, 1.75), (3.25, 13.25), (11.75, 13.25)):
        els.append(El((x, y, 14.25), (x + 1, y + 1, 14.75), 'bronze'))   # Bolzen der Platte
    px, py, pz = ROD_PIVOT
    if mode == 2:   # Hebel unten, Kupfer oxidiert
        lever = [
            El((7.4, 3.5, 12.4), (8.6, py, 13.6), 'bronze'),
            El((7, 0.75, 12), (9, 3.75, 14), 'accent'),
            El((7.25, 0.25, 12.25), (8.75, 0.75, 13.75), 'accent'),
        ]
        lean = ROD_LEAN
    else:           # Hebel oben, Griff kupfern (0) bzw. golden (1)
        lever = [
            El((7.4, py, 12.4), (8.6, 13.5, 13.6), 'bronze'),
            El((7, 12.5, 12), (9, 15.5, 14), 'accent', glow=gold),
            El((7.25, 15.5, 12.25), (8.75, 16, 13.75), 'accent', glow=gold),
        ]
        lean = -ROD_LEAN
    for e in lever:
        e.axis, e.angle, e.origin = 'x', lean, [px, py, pz]
    els += lever
    if gold:   # Blitzader im Feld: dünne Fläche knapp vor dem Feld
        els.append(El((4.5, 3, 13.98), (11.5, 13, 13.98), 'vein', faces=('north',), glow=True))
    return els, {'plate': 'grounding_rod_plate', 'bronze': 'grounding_rod_bronze', 'vein': 'grounding_rod_vein'}, 'grounding_rod_plate'


# --- Kettenpfahl --------------------------------------------------------------------------------

POST_FALL_ORIGIN = (8.0, 6.0, 10.0)   # Drehpunkt beim Umlegen: der Pfahl liegt danach in z 0…16, y 0…8
POST_CHAIN_TILT = -37.0               # obere Kette läuft schräg nach vorn oben (Grad um x)


def post(mode):
    gold = mode == 1
    els = [
        El((4, 0, 4), (12, 1.5, 12), 'bronze'),                     # Fuß
        El((4.5, 1.5, 4.5), (11.5, 2.5, 11.5), 'bronze'),           # Fußstufe
        El((5, 2.5, 5.75), (11, 14, 10.25), 'bronze'),              # Schaft (achteckig aus zwei Kästen)
        El((5.75, 2.5, 5), (10.25, 14, 11), 'bronze'),
        El((4.75, 4, 4.75), (11.25, 5, 11.25), 'bronze'),           # Band
        El((4.75, 13.5, 4.75), (11.25, 14.75, 11.25), 'bronze'),    # Kappe
        El((6, 14.75, 6), (10, 16, 10), 'bronze'),                  # Knauf
        El((7.25, 10.75, 4.25), (8.75, 11.75, 5), 'bronze'),        # Krampe
        El((6.25, 10.25, 4), (9.75, 11, 4.75), 'accent', glow=gold),    # Kettenring (vier Leisten)
        El((6.25, 7.25, 4), (9.75, 8, 4.75), 'accent', glow=gold),
        El((6.25, 7.25, 4), (7, 11, 4.75), 'accent', glow=gold),
        El((9, 7.25, 4), (9.75, 11, 4.75), 'accent', glow=gold),
    ]
    if mode == 2:   # umgelegt in Richtung der Vorderseite; die Kette ist abgerissen
        for e in els:
            e.axis, e.angle, e.origin = 'x', -90.0, list(POST_FALL_ORIGIN)
        return els, {'bronze': 'chain_post_bronze'}, 'chain_post_bronze'
    # Obere Kette: drei Glieder vom Ring schräg nach vorn oben, abwechselnd quer und längs.
    start = (8.0, 11.25, 4.4)
    dy, dz = math.cos(math.radians(POST_CHAIN_TILT)), math.sin(math.radians(POST_CHAIN_TILT))
    for i in range(3):
        s = 1.1 + 1.7 * i
        c = (start[0], start[1] + dy * s, start[2] + dz * s)
        size = (1.5, 2.2, 0.5) if i % 2 == 0 else (0.5, 2.2, 1.5)
        els.append(centred(*c, *size, 'accent', axis='x', angle=POST_CHAIN_TILT, glow=gold))
    return els, {'bronze': 'chain_post_bronze'}, 'chain_post_bronze'


# --- Schleusenrad -------------------------------------------------------------------------------

WHEEL_CENTER = (8.0, 8.0)
WHEEL_RIM_R = 6.0          # Mitte des Radkranzes
WHEEL_SEGMENTS = 12        # Kranz aus 12 gedrehten Leisten (30°)
WHEEL_SPOKES = 3           # drei Durchmesser = sechs Speichen


def wheel(mode):
    gold = mode == 1
    cx, cy = WHEEL_CENTER
    els = [
        El((1.5, 1, 14.5), (14.5, 15, 16), 'basalt'),               # Basaltplatte
        El((1, 1.5, 14.5), (15, 14.5, 16), 'basalt'),               # gebrochene Ecken
        El((4.5, 0, 11), (11.5, 1.25, 14.5), 'basalt'),             # Rinne unter dem Rad
        El((7, 7, 11.75), (9, 9, 14.5), 'bronze'),                  # Achse (Stirn vor der Nabe)
        El((6.25, 6.25, 12.25), (9.75, 9.75, 13.25), 'accent', glow=gold),   # Nabe
    ]
    base = 90.0 if mode == 1 else 0.0   # 0/2: eine Speiche quer (zu); 1: eine Speiche längs (offen)
    for k in range(WHEEL_SPOKES):
        els.append(centred(cx, cy, 12.8, 12.0, 1.2, 0.8, 'accent', axis='z', angle=base + 60.0 * k, glow=gold))
    seg = 2 * WHEEL_RIM_R * math.tan(math.pi / WHEEL_SEGMENTS) + 0.2
    for j in range(WHEEL_SEGMENTS):
        th = 360.0 / WHEEL_SEGMENTS * (j + 0.5)
        x, y = cx + WHEEL_RIM_R * math.cos(math.radians(th)), cy + WHEEL_RIM_R * math.sin(math.radians(th))
        els.append(centred(x, y, 12.75, seg, 1.25, 1.0, 'accent', axis='z', angle=(th + 90.0) % 180.0, glow=gold))
    if mode == 1:   # Wasser läuft: Spiegel in der Rinne und Strahl aus dem Schlitz hinter dem Rad
        els.append(El((5, 1.25, 11.5), (11, 1.25, 14.5), 'water', faces=('up',)))
        els.append(El((6.5, 1.25, 14.45), (9.5, 4.5, 14.45), 'water', faces=('north',)))
    if mode == 2:   # Sperrbolzen quer über dem Rad, an beiden Enden in die Platte
        els.append(El((1.25, 7.25, 11.25), (14.75, 8.75, 12.25), 'bronze'))
        els.append(El((1.25, 6.75, 11.25), (2.5, 9.25, 14.5), 'bronze'))
        els.append(El((13.5, 6.75, 11.25), (14.75, 9.25, 14.5), 'bronze'))
    textures = {'basalt': 'sluice_wheel_basalt', 'bronze': 'sluice_wheel_bronze', 'water': 'sluice_wheel_water'}
    return els, textures, 'sluice_wheel_basalt'


BUILDERS = {'messenger_bell': bell, 'grounding_rod': rod, 'chain_post': post, 'sluice_wheel': wheel}


def model_json(device, mode):
    els, textures, particle = BUILDERS[device](mode)
    tex = {'particle': f'hha:block/{particle}'}
    used = {e.tex for e in els}
    for key, name in textures.items():
        if key in used:
            tex[key] = f'hha:block/{name}'
    if 'accent' in used:
        tex['accent'] = f'hha:block/{device}_mode{mode}'
    return {
        'parent': 'minecraft:block/block',
        'ambientocclusion': False,
        'textures': tex,
        'elements': [e.json() for e in els],
    }, els


# =============================================================================================
# Schreiben
# =============================================================================================

CHANGED = []


def put_json(path, obj):
    text = json.dumps(obj, indent=2, ensure_ascii=False) + '\n'
    if os.path.exists(path) and open(path, encoding='utf8').read() == text:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf8', newline='\n') as f:
        f.write(text)
    CHANGED.append(path)


def put_png(path, img):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if os.path.exists(path):
        old = Image.open(path).convert('RGBA')
        if old.size == img.size and old.tobytes() == img.convert('RGBA').tobytes():
            return
    img.save(path)
    CHANGED.append(path)


def blockstate(device):
    """Gleiches Schema wie `deco_registry.py` (facing × mode, Drehung über `y`)."""
    variants = {}
    for facing, rot in FACINGS:
        for mode in MODES:
            v = {'model': f'hha:block/{device}_mode{mode}'}
            if rot:
                v['y'] = rot
            variants[f'facing={facing},mode={mode}'] = v
    return {'variants': variants}


def write_all():
    textures = device_textures()
    for name, (img, meta) in textures.items():
        put_png(os.path.join(TEX, f'{name}.png'), img)
        if meta is not None:
            put_json(os.path.join(TEX, f'{name}.png.mcmeta'), meta)
    counts = {}
    for d in DEVICES:
        put_json(os.path.join(STATES, f'{d}.json'), blockstate(d))
        for m in MODES:
            model, els = model_json(d, m)
            assert 10 <= len(els) <= 24, f'{d} mode {m}: {len(els)} Elemente'
            counts[f'{d}_mode{m}'] = len(els)
            put_json(os.path.join(MODELS, f'{d}_mode{m}.json'), model)
        put_json(os.path.join(ITEMS, f'{d}.json'), {'model': {'type': 'minecraft:model', 'model': f'hha:block/{d}_mode0'}})
    # Gewitterwolke
    put_png(os.path.join(TEX, 'storm_cloud.png'), cloud_strip())
    put_json(os.path.join(TEX, 'storm_cloud.png.mcmeta'), {'animation': {'frametime': CLOUD_FRAMETIME, 'interpolate': True}})
    put_json(os.path.join(STATES, 'storm_cloud.json'), {'variants': {'': {'model': 'hha:block/storm_cloud'}}})
    put_json(os.path.join(MODELS, 'storm_cloud.json'), {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'hha:block/storm_cloud'}})
    put_json(os.path.join(ITEMS, 'storm_cloud.json'), {'model': {'type': 'minecraft:model', 'model': 'hha:block/storm_cloud'}})
    return textures, counts


# =============================================================================================
# Vorschau: kleiner Elemente-Zeichner (orthografisch, schräg von vorn, Licht oben links)
# =============================================================================================

def v_add(a, b):
    return [a[i] + b[i] for i in range(3)]


def v_sub(a, b):
    return [a[i] - b[i] for i in range(3)]


def v_dot(a, b):
    return sum(a[i] * b[i] for i in range(3))


def v_cross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def v_norm(a):
    l = math.sqrt(v_dot(a, a)) or 1.0
    return [v / l for v in a]


def rotate_point(p, axis, deg, origin):
    """Drehung nach der rechten Hand um +Achse (wie `Quaternionf.rotationAxis` im Spiel)."""
    if not axis or not deg:
        return p
    q = v_sub(p, origin)
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    x, y, z = q
    if axis == 'x':
        q = [x, y * c - z * s, y * s + z * c]
    elif axis == 'y':
        q = [x * c + z * s, y, -x * s + z * c]
    else:
        q = [x * c - y * s, x * s + y * c, z]
    return v_add(q, origin)


FACE_GEOM = {
    # Fläche → (feste Achse, 0 = from / 1 = to, Normale)
    'down': (1, 0, (0, -1, 0)), 'up': (1, 1, (0, 1, 0)),
    'north': (2, 0, (0, 0, -1)), 'south': (2, 1, (0, 0, 1)),
    'west': (0, 0, (-1, 0, 0)), 'east': (0, 1, (1, 0, 0)),
}


def face_uv_at(face, p):
    x, y, z = p
    return {
        'down': (x, 16 - z), 'up': (x, z), 'north': (16 - x, 16 - y),
        'south': (x, 16 - y), 'west': (z, 16 - y), 'east': (16 - z, 16 - y),
    }[face]


def sample(img, u, v):
    """Texel bei (u, v) in Modell-Einheiten 0…16; animierte Streifen: erstes Bild."""
    w = img.size[0]
    return img.getpixel((lim(int(u / 16 * w), 0, w - 1), lim(int(v / 16 * w), 0, w - 1)))


class View:
    """Orthografische Kamera schräg vorn oben (Modell-Vorderseite = Norden, −z), Licht oben links aus Sicht des
    Betrachters. [project] liefert Bild-x, Bild-y (Einheiten) und Tiefe (größer = weiter weg)."""

    def __init__(self, cam=(-0.55, 0.62, -1.0)):
        self.cam = v_norm(list(cam))
        self.fwd = [-c for c in self.cam]
        self.right = v_norm(v_cross(self.fwd, [0, 1, 0]))
        self.up = v_cross(self.right, self.fwd)
        self.light = v_norm([0.8 * self.up[i] - 0.6 * self.right[i] + 0.45 * self.cam[i] for i in range(3)])

    def project(self, p):
        q = v_sub(p, [8, 8, 8])
        return v_dot(q, self.right), -v_dot(q, self.up), v_dot(q, self.fwd)


def faces_of(els, view):
    """Sichtbare Flächen als Parallelogramme: (Ecke, Kante s, Kante t im Bild), Tiefe, Abbildung (s, t) → 3D vor der
    Drehung, Helligkeit, Fläche, Element."""
    out = []
    for e in els:
        for face in e.faces:
            fixed_axis, side, normal = FACE_GEOM[face]
            nrm = v_norm(rotate_point(list(normal), e.axis, e.angle, [0, 0, 0]))
            if v_dot(nrm, view.cam) <= 1e-6:
                continue
            a0, a1 = [a for a in range(3) if a != fixed_axis]
            base = list(e.fr)
            base[fixed_axis] = e.to[fixed_axis] if side else e.fr[fixed_axis]
            ds, dt = [0.0] * 3, [0.0] * 3
            ds[a0] = e.to[a0] - e.fr[a0]
            dt[a1] = e.to[a1] - e.fr[a1]
            p0 = view.project(rotate_point(base, e.axis, e.angle, e.origin))
            ps = view.project(rotate_point(v_add(base, ds), e.axis, e.angle, e.origin))
            pt = view.project(rotate_point(v_add(base, dt), e.axis, e.angle, e.origin))
            bright = 1.08 if e.glow else 0.42 + 0.58 * max(0.0, v_dot(nrm, view.light))
            out.append((p0, ps, pt, base, ds, dt, bright, face, e))
    return out


def bounds(els, view):
    xs, ys = [], []
    for e in els:
        for i in range(8):
            c = [e.to[k] if (i >> k) & 1 else e.fr[k] for k in range(3)]
            x, y, _ = view.project(rotate_point(c, e.axis, e.angle, e.origin))
            xs.append(x)
            ys.append(y)
    return min(xs), min(ys), max(xs), max(ys)


def render(els, texmap, view, box, size=420, bg=(36, 34, 44)):
    """Zeichnet die Flächen mit Tiefenpuffer je Pixel (genau auch bei Durchdringungen). [box] = Bildausschnitt in
    Einheiten (x0, y0, x1, y1), gleich für alle Zustände eines Geräts."""
    x0, y0, x1, y1 = box
    scale = min((size - 60) / (x1 - x0), (size - 70) / (y1 - y0))
    ox = size / 2 - (x0 + x1) / 2 * scale
    oy = size / 2 + 12 - (y0 + y1) / 2 * scale
    img = Image.new('RGB', (size, size), bg)
    d = ImageDraw.Draw(img)
    for yy in range(size):
        g = int(18 * yy / size)
        d.line([(0, yy), (size, yy)], fill=(bg[0] + g, bg[1] + g, bg[2] + g))
    px = img.load()
    zbuf = [[float('inf')] * size for _ in range(size)]
    for (p0, ps, pt, base, ds, dt, bright, face, e) in faces_of(els, view):
        sx0, sy0 = ox + p0[0] * scale, oy + p0[1] * scale
        e1 = ((ps[0] - p0[0]) * scale, (ps[1] - p0[1]) * scale, ps[2] - p0[2])
        e2 = ((pt[0] - p0[0]) * scale, (pt[1] - p0[1]) * scale, pt[2] - p0[2])
        det = e1[0] * e2[1] - e1[1] * e2[0]
        if abs(det) < 1e-9:
            continue
        xs = [sx0, sx0 + e1[0], sx0 + e2[0], sx0 + e1[0] + e2[0]]
        ys = [sy0, sy0 + e1[1], sy0 + e2[1], sy0 + e1[1] + e2[1]]
        tex = texmap[e.tex]
        for yy in range(max(0, int(min(ys))), min(size, int(max(ys)) + 2)):
            for xx in range(max(0, int(min(xs))), min(size, int(max(xs)) + 2)):
                rx, ry = xx + 0.5 - sx0, yy + 0.5 - sy0
                s = (rx * e2[1] - ry * e2[0]) / det
                t = (e1[0] * ry - e1[1] * rx) / det
                if s < 0 or s > 1 or t < 0 or t > 1:
                    continue
                depth = p0[2] + s * e1[2] + t * e2[2]
                if depth >= zbuf[yy][xx]:
                    continue
                p = [base[k] + s * ds[k] + t * dt[k] for k in range(3)]
                u, v = face_uv_at(face, p)
                col = sample(tex, u, v)
                if col[3] < 128:
                    continue
                zbuf[yy][xx] = depth
                px[xx, yy] = tuple(lim(int(col[k] * bright), 0, 255) for k in range(3))
    return img


def label(img, text, colour=(235, 228, 210)):
    d = ImageDraw.Draw(img)
    d.text((10, 8), text, fill=colour)
    return img


def write_previews(textures, counts):
    os.makedirs(PREVIEW, exist_ok=True)
    names = ['0 still', '1 golden (bereit)', '2 verbraucht']
    view = View()
    for d in DEVICES:
        sheet = Image.new('RGB', (420 * 3, 420), (0, 0, 0))
        built = [model_json(d, m) for m in MODES]
        bb = [bounds(els, view) for _, els in built]
        box = (min(b[0] for b in bb), min(b[1] for b in bb), max(b[2] for b in bb), max(b[3] for b in bb))
        for m, (model, els) in zip(MODES, built):
            texmap = {key: textures[ref.split('/')[-1]][0] for key, ref in model['textures'].items()}
            pic = render(els, texmap, view, box)
            label(pic, f'{d}  Zustand {names[m]}  ({counts[f"{d}_mode{m}"]} Elemente)')
            sheet.paste(pic, (420 * m, 0))
        sheet.save(os.path.join(PREVIEW, f'{d}.png'))
    # Texturblatt aller Geräte-Texturen (×6)
    keys = sorted(textures)
    cols = 6
    cell = N * 6 + 24
    rows = (len(keys) + cols - 1) // cols
    board = Image.new('RGB', (cols * cell, rows * cell), (28, 26, 34))
    dd = ImageDraw.Draw(board)
    for i, k in enumerate(keys):
        img = textures[k][0].crop((0, 0, N, N)).resize((N * 6, N * 6), Image.NEAREST)
        x, y = (i % cols) * cell, (i // cols) * cell
        chk = Image.new('RGBA', img.size, (60, 58, 66, 255))
        chk.alpha_composite(img)
        board.paste(chk.convert('RGB'), (x + 12, y + 4))
        dd.text((x + 12, y + N * 6 + 7), k, fill=(220, 214, 200))
    board.save(os.path.join(PREVIEW, 'textures.png'))
    # Wolke: 3 × 3 (Bild 0) vor Himmelsgrund und vier Einzelbilder (0, 4, 8, 12), je ×8
    strip = cloud_strip()
    sky = (92, 118, 160)
    tile = Image.new('RGBA', (N * 3, N * 3), sky + (255,))
    f0 = strip.crop((0, 0, N, N))
    for i in range(3):
        for j in range(3):
            tile.alpha_composite(f0, (i * N, j * N))
    tile.convert('RGB').resize((N * 3 * 6, N * 3 * 6), Image.NEAREST).save(os.path.join(PREVIEW, 'storm_cloud_3x3.png'))
    four = Image.new('RGB', (N * 8 * 4 + 30, N * 8 + 20), (28, 26, 34))
    for k, f in enumerate((0, 4, 8, 12)):
        bgimg = Image.new('RGBA', (N, N), sky + (255,))
        bgimg.alpha_composite(strip.crop((0, f * N, N, (f + 1) * N)))
        four.paste(bgimg.convert('RGB').resize((N * 8, N * 8), Image.NEAREST), (k * (N * 8 + 10), 0))
        ImageDraw.Draw(four).text((k * (N * 8 + 10) + 4, N * 8 + 4), f'Bild {f}', fill=(220, 214, 200))
    four.save(os.path.join(PREVIEW, 'storm_cloud_frames.png'))
    # Wolkenfeld 3 × 3 aus allen 16 Bildern (Bewegung), als Streifen
    anim = Image.new('RGB', (N * 2 * 16, N * 2), (28, 26, 34))
    for f in range(CLOUD_FRAMES):
        bgimg = Image.new('RGBA', (N, N), sky + (255,))
        bgimg.alpha_composite(strip.crop((0, f * N, N, (f + 1) * N)))
        anim.paste(bgimg.convert('RGB').resize((N * 2, N * 2), Image.NEAREST), (f * N * 2, 0))
    anim.save(os.path.join(PREVIEW, 'storm_cloud_strip.png'))


def self_check(textures):
    """Selbsttest: jede deckende Pixelfarbe stammt aus einer der erlaubten Rampen; Wolke kachelt nahtlos (Rand-Sprung
    nicht größer als im Inneren) und Alpha liegt in 150…215."""
    allowed = set()
    for r in ('BRONZE', 'DARK_BRONZE', 'MARBLE', 'BASALT', 'GOLD', 'CLOUD'):
        allowed.update(RAMPS[r])
    bad = []
    for name, (img, _) in textures.items():
        rgba = img.convert('RGBA')
        w, h = rgba.size
        px = rgba.load()
        if any(px[x, y][3] > 0 and px[x, y][:3] not in allowed for y in range(h) for x in range(w)):
            bad.append(name)
    strip = cloud_strip()
    sp = strip.load()
    alphas = [sp[x, y][3] for y in range(strip.size[1]) for x in range(strip.size[0])]
    f0 = strip.crop((0, 0, N, N)).load()

    def jump(a, b):
        return sum(abs(a[k] - b[k]) for k in range(3))
    inner = max(jump(f0[x, y], f0[x + 1, y]) for x in range(N - 1) for y in range(N))
    edge = max(jump(f0[N - 1, y], f0[0, y]) for y in range(N))
    return bad, (min(alphas), max(alphas)), (edge, inner)


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    textures, counts = write_all()
    bad, alpha, seam = self_check(textures)
    print(f'device_blocks: {len(CHANGED)} Dateien geschrieben/geändert')
    print('Elemente je Fassung:', ', '.join(f'{k} {v}' for k, v in counts.items()))
    print('Rampen-Check:', 'ok' if not bad else f'FREMDE FARBEN in {bad}')
    print(f'Wolke: Alpha {alpha[0]}…{alpha[1]}, Randsprung {seam[0]} (innen max {seam[1]})')
    if '--preview' in sys.argv:
        write_previews(textures, counts)
        print('Vorschau unter', PREVIEW)
    if bad:
        sys.exit(1)


if __name__ == '__main__':
    main()
