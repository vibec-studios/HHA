"""Textur-Baukasten im Blood-Stil (Überarbeitung 4, PLAN 2.2/2.3; P01).

Port der Bausteine aus HHA Blood `tools/textures/GenHollows.java` / `GenPit.java` nach reinem Python (+ PIL):

- `h01`, `pn`, `fbm`: deterministischer Hash und periodisches Wertrauschen. Das Gitter wickelt modulo `cells`, darum kachelt
  jede Textur nahtlos (Nahtlosigkeit durch Periodizität, nicht durch Nachbearbeitung).
- Index-Raster `list[list[int]]` (0 dunkel … 7 hell, 4 = Grundton) und `colour(lv, ramp, acc)`: Muster werden nur in
  Rampen-Stufen gerechnet und erst am Ende eingefärbt. Akzente (Gold, Seelengrün, Glut, Blitz) liegen als eigenes
  Raster `acc[y][x] = (Rampe, Stufe)` darüber.
- Bausteine: `stone_idx` (Naturstein), `bricks` (Ziegel 15×7), `polished_idx` + `bevel` (poliert mit Fase), `inset`
  (Gravur), `chiseled` (gemeißelte Platte mit Emblem), `column_idx`/`column_top_idx` (kannelierte Säule), `vein`
  (periodische Adern als Röhre mit Lichtseite und Rille), `plates` (Bronzeplatten mit Nieten), `frames` (Puls-Streifen).

Licht kommt überall von oben links: Klumpen, Ziegelkante (+1 oben/links, −1 unten/rechts), Fase, Gravur (dunkle Wand
oben links, helle Lippe unten rechts), Grube (heller Rand unten rechts), Korn (Schatten +1/+1), Ader als Röhre.
"""
import math
import random

from PIL import Image

N = 32
TAU = math.pi * 2


def _ramp(text):
    return [tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) for h in text.split()]


def _blend(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


# Rampen (PLAN 2.2; Index 0 dunkel … 7 hell, 4 = Grundton). Schatten kippen kühl, Lichter warm.
# Endgültige Werte stehen im Bericht P01; Verschiebungen gegenüber dem PLAN sind dort begründet.
RAMPS = {
    'MARBLE': _ramp('6f6a7c 8d8898 aaa5b0 c3bec4 d8d3cf e7e2da f3efe6 fffbf2'),
    'GOLD': _ramp('4a2408 6e3a0c 935414 b8761e d69a2c ecbd48 f8db7c fff3c4'),
    'BRONZE': _ramp('2e1a0c 4a2a12 6b3e18 8c5622 ad7230 c9924a e0b56e f3d9a0'),
    'STORM': _ramp('10131f 1a2033 26304a 344363 465a80 5d769f 7e9bc0 b3cdea'),
    'CLOUD': _ramp('3b4152 525a6e 6c7689 8892a3 a9b2c0'),
    # BASALT: Buntheit gegenüber PLAN-Startwert × 0,7 (Lab, Helligkeit gleich), weil der Basalt in der Wandprobe neben
    # poliertem Schwarzstein und Tiefenschiefer violett herausstach. PLAN: 0a080e 15121c 201b29 2c2536 393045 473b54 574763 6c5878
    'BASALT': _ramp('0a080d 15121a 1f1c25 2b2632 383140 453d4e 54495c 685a71'),
    'SOUL': _ramp('04240e 0b4a1c 0e7a2c 22a83e 2fd14c 5cf07a 9dffb0 e8ffd0'),
    'EMBER': _ramp('2a0a06 5a1408 8c2a0a c2480e e8731c ff9c3a ffc878 fff0c8'),
    'DARK_BRONZE': _ramp('0d1210 1a211c 2a332a 3d4a3a 55634a 74805c 98a078 c2c8a0'),
}
# Blitzader des Sturmmarmors: PLAN „3a66ff → e8f4ff“ in 6 festen Stufen (gleichmäßig gemischt).
RAMPS['BOLT'] = [_blend((0x3a, 0x66, 0xff), (0xe8, 0xf4, 0xff), i / 5) for i in range(6)]


# ---------------------------------------------------------------------------------------------
# Rauschen
# ---------------------------------------------------------------------------------------------

_M64 = (1 << 64) - 1


def h01(seed, x, y):
    """Deterministischer Hash → [0, 1) (SplitMix-artig, wie Blood `h01`)."""
    h = (seed * 0x9E3779B97F4A7C15 + x * 0xC2B2AE3D27D4EB4F + y * 0x165667B19E3779F9) & _M64
    h ^= h >> 31
    h = (h * 0xBF58476D1CE4E5B9) & _M64
    h ^= h >> 29
    h = (h * 0x94D049BB133111EB) & _M64
    h ^= h >> 32
    return (h >> 11) * (2.0 ** -53)


def pn(x, y, cells, seed, n=N, cy=None, ph=None):
    """Periodisches Wertrauschen mit Smoothstep: `cells` Gitterzellen je Periode `n` (Gitter-Wrap → nahtlos)."""
    cy = cells if cy is None else cy
    ph = n if ph is None else ph
    fx, fy = x * cells / n, y * cy / ph
    x0, y0 = math.floor(fx), math.floor(fy)
    tx, ty = fx - x0, fy - y0
    tx = tx * tx * (3 - 2 * tx)
    ty = ty * ty * (3 - 2 * ty)
    xa, xb, ya, yb = x0 % cells, (x0 + 1) % cells, y0 % cy, (y0 + 1) % cy
    a, b, c, d = h01(seed, xa, ya), h01(seed, xb, ya), h01(seed, xa, yb), h01(seed, xb, yb)
    return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty


def fbm(x, y, seed):
    return pn(x, y, 4, seed) * 0.55 + pn(x, y, 8, seed + 1) * 0.3 + pn(x, y, 16, seed + 2) * 0.15


def w(v):
    return v % N


def lim(v, lo, hi):
    return max(lo, min(hi, v))


def grid(value=4):
    return [[value] * N for _ in range(N)]


def empty_acc():
    return [[None] * N for _ in range(N)]


def copy(lv):
    return [row[:] for row in lv]


# ---------------------------------------------------------------------------------------------
# Einfärben
# ---------------------------------------------------------------------------------------------

def colour(lv, ramp, acc=None):
    """Index-Raster → RGBA-Bild (32 × 32). `acc[y][x] = (Rampe, Stufe)` überschreibt mit einer Akzentrampe."""
    pal = RAMPS[ramp]
    img = Image.new('RGBA', (N, N))
    px = img.load()
    for y in range(N):
        for x in range(N):
            a = acc[y][x] if acc is not None else None
            if a is not None:
                r = RAMPS[a[0]]
                px[x, y] = r[lim(a[1], 0, len(r) - 1)] + (255,)
            else:
                px[x, y] = pal[lim(lv[y][x], 0, len(pal) - 1)] + (255,)
    return img


def frames(build, beat):
    """Puls-Streifen (32 × 32·Bilder): `build(I)` liefert je Hüllkurvenwert I ein fertiges 32er-Bild. Nur Helligkeit
    ändert sich, die Form steht fest (die Funktion bekommt keine Bildnummer)."""
    img = Image.new('RGBA', (N, N * len(beat)))
    for f, intensity in enumerate(beat):
        img.paste(build(intensity), (0, f * N))
    return img


# ---------------------------------------------------------------------------------------------
# Naturstein
# ---------------------------------------------------------------------------------------------

def stone_idx(seed, lo=2, hi=6, pits=6, grains=14, cracks=2, warp=4.5):
    """Naturstein: gewürfeltes 5×5-Voronoi (periodisch, verzerrt), je Klumpen Ton −1/0/+1 und Licht oben links,
    Korn, Fugen mit Lücken, Gruben (heller Rand unten rechts), Lichtkörner (Schatten unten rechts), Haarrisse."""
    lv = grid()
    r = random.Random(seed)
    g = 5
    cell = N / g
    sites = []
    tones = [-1, 0, 0, 0, 0, 0, 0, 1]
    for i in range(g * g):
        sites.append(((i % g + 0.2 + r.random() * 0.6) * cell, (i // g + 0.2 + r.random() * 0.6) * cell,
                      tones[r.randrange(len(tones))]))
    for y in range(N):
        for x in range(N):
            wx = x + 0.5 + (pn(x, y, 4, seed + 21) - 0.5) * warp
            wy = y + 0.5 + (pn(x, y, 4, seed + 22) - 0.5) * warp
            f1 = f2 = 1e9
            ox = oy = 0.0
            tone = 0
            for sx0, sy0, t in sites:
                for sx in (-N, 0, N):
                    dx = wx - sx0 - sx
                    for sy in (-N, 0, N):
                        dy = wy - sy0 - sy
                        d = math.hypot(dx, dy)
                        if d < f1:
                            f2, f1, ox, oy, tone = f1, d, dx, dy, t
                        elif d < f2:
                            f2 = d
            lt = -(ox + oy) / math.sqrt(2) / (cell * 0.55)      # > 0: Teil des Klumpens oben links
            grain = pn(x, y, 16, seed + 11) - 0.5 + (pn(x, y, 2, seed + 7) - 0.5) * 0.8
            v = 4 + tone
            if lt > 0.42:
                v += 1
            elif lt < -0.5:
                v -= 1
            if grain > 0.28:
                v += 1
            elif grain < -0.3:
                v -= 1
            if f2 - f1 < 1.0:                                      # Fuge zwischen Klumpen, mit Lücken
                v = min(v - 1, 3) if pn(x, y, 8, seed + 3) > 0.35 else v - 1
            lv[y][x] = lim(v, lo, hi)
    for k in range(pits):                                          # Grube: dunkel, heller Rand unten rechts
        x, y = r.randrange(N), r.randrange(N)
        lv[y][x] = lo - 1
        if r.random() < 0.5:
            lv[y][w(x + 1)] = lo - 1 if r.random() < 0.5 else lo
        if k % 3 == 0:
            lv[w(y + 1)][x] = lo
        lv[w(y + 1)][w(x + 1)] = max(lv[w(y + 1)][w(x + 1)], 5)
    for _ in range(grains):                                        # Lichtkorn mit Schatten unten rechts
        x, y = r.randrange(N), r.randrange(N)
        if lv[y][x] < 4:
            continue
        lv[y][x] = 7 if r.random() < 0.4 else 6
        lv[w(y + 1)][w(x + 1)] = min(lv[w(y + 1)][w(x + 1)], 3)
    for _ in range(cracks):                                        # Haarriss 5–8 px
        x, y = r.randrange(N), r.randrange(N)
        step = 1 if r.random() < 0.5 else -1
        length = 5 + r.randrange(4)
        for i in range(length):
            lv[w(y)][w(x)] = 3 if i in (0, length - 1) else 2
            if lv[w(y + 1)][w(x)] >= 3 and r.random() < 0.5:
                lv[w(y + 1)][w(x)] = min(6, lv[w(y + 1)][w(x)] + 1)
            if r.random() < 0.6:
                x += step
            else:
                y += 1
            if r.random() < 0.5:
                y += 1
    return lv


def marble_veins(lv, seed, strength=1, width=0.5, cells=3, warp=4.0, lit=True):
    """Weiche Marmoradern: Isolinie eines periodischen, verzerrten Rauschfelds (Abstand < `width` px) um `strength`
    Stufen verschoben; bei `lit` bekommt die Seite unten rechts der Ader eine Stufe Licht (Rille, Licht oben links)."""
    dist = iso_distance(noise_field(cells, seed, warp, seed + 50))
    hit = [[dist[y][x] < width for x in range(N)] for y in range(N)]
    for y in range(N):
        for x in range(N):
            if hit[y][x]:
                lv[y][x] = lv[y][x] - strength
            elif lit and strength > 0 and hit[w(y - 1)][w(x - 1)] and not hit[w(y + 1)][w(x + 1)]:
                lv[y][x] = min(lv[y][x] + 1, 7)
    return lv


# ---------------------------------------------------------------------------------------------
# Ziegel
# ---------------------------------------------------------------------------------------------

def _paint_brick(lv, x0, y0, bw, bh, r, seed, chip_level=1):
    shift = (-1, 0, 0, 0, 1)[r.randrange(5)]                      # Ton je Ziegel −1/0/+1
    loc = [[4] * bw for _ in range(bh)]
    for ly in range(bh):
        for lx in range(bw):
            x, y = w(x0 + lx), w(y0 + ly)
            n = pn(x, y, 8, seed) * 0.6 + pn(x, y, 16, seed + 1) * 0.4
            v = 4 + shift + (1 if n > 0.68 else -1 if n < 0.3 else 0)
            if ly >= bh - 3 and n < 0.45:
                v -= 1                                             # untere Zeilen etwas dunkler
            if ly == 0:
                v += 1
            if lx == 0:
                v += 1
            if ly == 0 and lx == 0:
                v += 1
            if ly == bh - 1:
                v -= 1
            if lx == bw - 1:
                v -= 1
            loc[ly][lx] = lim(v, 1, 7)
    for _ in range(2):                                             # Körner mit Schatten
        gx, gy = 1 + r.randrange(bw - 3), 1 + r.randrange(bh - 3)
        loc[gy][gx] = lim(loc[gy][gx] + 2, 1, 7)
        loc[gy + 1][gx + 1] = lim(loc[gy + 1][gx + 1] - 1, 1, 7)
    if r.random() < 0.45:                                          # Riss von oben oder unten, 2–4 px
        top = r.random() < 0.5
        cx = lim(3 + r.randrange(max(1, bw - 6)), 1, bw - 2)
        cy = 0 if top else bh - 1
        for _ in range(2 + r.randrange(3)):
            if not 0 <= cy < bh:
                break
            loc[cy][cx] = 1
            if cx + 1 < bw and loc[cy][cx + 1] > 1:
                loc[cy][cx + 1] = lim(loc[cy][cx + 1] + 1, 1, 7)
            cy += 1 if top else -1
            if r.random() < 0.5:
                cx = lim(cx + (1 if r.random() < 0.5 else -1), 1, bw - 2)
    for ly in range(bh):
        for lx in range(bw):
            lv[w(y0 + ly)][w(x0 + lx)] = loc[ly][lx]
    for cx, cy, dx, dy in ((0, 0, 1, 1), (bw - 1, 0, -1, 1), (0, bh - 1, 1, -1), (bw - 1, bh - 1, -1, -1)):
        if r.random() > 0.35:                                      # 35 % abgeschlagene Ecke
            continue
        lv[w(y0 + cy)][w(x0 + cx)] = chip_level
        if r.random() < 0.4:
            lv[w(y0 + cy)][w(x0 + cx + dx)] = chip_level
        lv[w(y0 + cy + dy)][w(x0 + cx + dx)] = chip_level + 1


def bricks(seed, mortar=1):
    """4 Reihen zu 15 × 7, versetzt um 8; Fuge Stufe `mortar` mit Flecken ±1; Ziegel wie `_paint_brick`."""
    lv = grid()
    for y in range(N):
        for x in range(N):
            n = h01(seed, x, y)
            lv[y][x] = mortar - 1 if n < 0.2 else mortar + 1 if n > 0.9 else mortar
    r = random.Random(seed)
    for row in range(4):
        off = (row % 2) * 8
        for k in range(2):
            _paint_brick(lv, off + k * 16, row * 8, 15, 7, r, seed + row * 13 + k, chip_level=mortar)
    return lv


# ---------------------------------------------------------------------------------------------
# Poliert, Fase, Gravur
# ---------------------------------------------------------------------------------------------

def polished_idx(seed, light_grains=7, pits=3):
    """Gleiches Korn wie der Naturstein, auf Stufen 3–5 geplättet; wenige Lichtkörner und Grübchen."""
    lv = grid()
    for y in range(N):
        for x in range(N):
            n = pn(x, y, 4, seed) * 0.6 + pn(x, y, 8, seed + 1) * 0.4
            v = 5 if n > 0.64 else 3 if n < 0.32 else 4
            g = pn(x, y, 16, seed + 2)
            if g > 0.8 and v < 5:
                v += 1
            elif g < 0.18 and v > 3:
                v -= 1
            lv[y][x] = v
    r = random.Random(seed)
    for _ in range(light_grains):
        x, y = 3 + r.randrange(N - 6), 3 + r.randrange(N - 6)
        lv[y][x] = 6
        lv[y + 1][x + 1] = min(lv[y + 1][x + 1], 3)
    for _ in range(pits):
        x, y = 3 + r.randrange(N - 6), 3 + r.randrange(N - 6)
        lv[y][x] = 2
        lv[y + 1][x + 1] = max(lv[y + 1][x + 1], 5)
    return lv


def bevel(lv, x0=0, y0=0, x1=N - 1, y1=N - 1, outer=(6, 2), inner=(5, 3)):
    """Erhabene Fase: oben/links hell (6, 5), unten/rechts dunkel (2, 3), gemischte Ecken 4."""
    hi_o, lo_o = outer
    hi_i, lo_i = inner
    for i in range(x0 + 1, x1):
        lv[y0 + 1][i] = hi_i
        lv[y1 - 1][i] = lo_i
    for i in range(y0 + 1, y1):
        lv[i][x0 + 1] = hi_i
        lv[i][x1 - 1] = lo_i
    for i in range(x0, x1 + 1):
        lv[y0][i] = hi_o
        lv[y1][i] = lo_o
    for i in range(y0, y1 + 1):
        lv[i][x0] = hi_o
        lv[i][x1] = lo_o
    mid_o, mid_i = (hi_o + lo_o) // 2, (hi_i + lo_i) // 2
    lv[y1][x0] = mid_o
    lv[y0][x1] = mid_o
    lv[y1 - 1][x0 + 1] = mid_i
    lv[y0 + 1][x1 - 1] = mid_i
    return lv


def inset(lv, x0, y0, x1, y1, dark=2, light=5):
    """Eingelassener Rahmen (Gravur): Wand oben/links dunkel, Lippe unten/rechts hell."""
    for i in range(x0, x1 + 1):
        lv[y0][i] = dark
        lv[y1][i] = light
    for i in range(y0, y1 + 1):
        lv[i][x0] = dark
        lv[i][x1] = light
    lv[y1][x0] = (dark + light) // 2
    lv[y0][x1] = (dark + light) // 2
    return lv


def carve(lv, acc, mask, ramp, levels=(0, 1, 2, 3), glint=None, light_dir=(-1, -1)):
    """Geschnitzte Form mit stumpfer Einlage: `mask[y][x]` True = Einlage. Rand oben links der Einlage = tiefste
    Stufe (Schatten der Kerbe), Inneres nach Abstand zum Licht; außen oben links eine dunkle Kante (Stein 2), unten
    rechts eine helle Lippe (Stein 5). `glint = (x, y)` setzt den einzigen Glanzpixel (Akzentstufe `levels[-1] + 3`)."""
    dk, mid_lo, mid_hi, top = levels
    for y in range(N):
        for x in range(N):
            if mask[y][x]:
                up_left = not mask[w(y - 1)][x] or not mask[y][w(x - 1)]
                down_right = not mask[w(y + 1)][x] or not mask[y][w(x + 1)]
                if up_left:
                    v = dk
                elif down_right:
                    v = mid_hi
                else:
                    v = mid_lo if (x + y) % 5 else mid_hi
                acc[y][x] = (ramp, v)
            elif mask[w(y - 1)][x] or mask[y][w(x - 1)]:
                if acc[y][x] is None:
                    lv[y][x] = 5                                   # Lippe unten/rechts der Kerbe
            elif mask[w(y + 1)][x] or mask[y][w(x + 1)]:
                if acc[y][x] is None:
                    lv[y][x] = 2                                   # Kante oben/links der Kerbe
    if glint is not None:
        gx, gy = glint
        if mask[gy][gx]:
            acc[gy][gx] = (ramp, top + 3)


def engrave_ring(lv, cx, cy, r_in, r_out, depth=2):
    """Gravierter Ring: Grund `depth`, Außenwand oben links Schatten, unten rechts Licht; innen erhabene Scheibe."""
    for y in range(N):
        for x in range(N):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            tl = -(dx + dy) / max(d, 0.01) / math.sqrt(2)          # +1 = zum Licht oben links
            if r_in <= d < r_out:
                v = depth
                if d > r_out - 1.0 and tl > 0.3:
                    v = depth - 1
                elif d > r_out - 1.0 and tl < -0.3:
                    v = 5
                lv[y][x] = v
            elif r_in - 1.0 <= d < r_in:
                lv[y][x] = 5 if tl > 0.25 else 3 if tl < -0.25 else 4


def studs(lv, points):
    """Nieten 2 × 2: oben links hell, unten rechts Schatten."""
    for x, y in points:
        lv[y][x] = 6
        lv[y][x + 1] = 5
        lv[y + 1][x] = 5
        lv[y + 1][x + 1] = 3
        if y + 2 < N and x + 2 < N:
            lv[y + 2][x + 2] = min(lv[y + 2][x + 2], 3)


def panel(lv, x0=4, y0=4, x1=27, y1=27):
    """Eingelassenes Feld eine Stufe dunkler mit Gravur-Rahmen (Blood `chiseled` Schritt 2–3)."""
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            lv[y][x] = lim(lv[y][x] - (1 if lv[y][x] >= 5 else 0), 3, 4)
    inset(lv, x0 - 1, y0 - 1, x1 + 1, y1 + 1, 2, 5)
    for i in range(x0, x1 + 1):
        lv[y0][i] = 3
    for i in range(y0, y1 + 1):
        lv[i][x0] = 3


def chiseled(seed, emblem, accent):
    """Gemeißelte Platte: polierte Platte mit Fase, eingelassenes Feld 4…27, darauf `emblem(lv, acc)`. Liefert
    (lv, acc); die Einlage aus `accent` bleibt stumpf (Stufen 0–3) mit höchstens einem Glanzpixel."""
    lv = polished_idx(seed)
    bevel(lv)
    panel(lv)
    acc = empty_acc()
    emblem(lv, acc, accent)
    return lv, acc


# ---------------------------------------------------------------------------------------------
# Säule
# ---------------------------------------------------------------------------------------------

# Kanneluren: Periode 8 px = Steg 2 + Hohlkehle 6. Licht von links: die linke Wand der Kehle liegt im Schatten, die
# rechte Wand fängt Licht; der Steg selbst ist hell (vorn), seine rechte Kante dunkler.
FLUTE = (6, 5, 3, 3, 4, 4, 5, 5)


def column_idx(seed, ring=True):
    """Säulenschaft: senkrechte Kanneluren (Licht von links), leichtes Korn; oben ein flacher Halsring als
    Trommelfuge (Zeile 0 Licht, 1 Grund, 2 Schatten), damit gestapelte Säulen als Trommeln lesen."""
    lv = grid()
    for y in range(N):
        for x in range(N):
            v = FLUTE[x % len(FLUTE)]
            g = pn(x, y, 8, seed, cy=4) * 0.6 + pn(x, y, 16, seed + 1) * 0.4
            if g > 0.7 and v < 6:
                v += 1
            elif g < 0.25 and v > 2:
                v -= 1
            lv[y][x] = v
    if ring:
        for x in range(N):
            lv[0][x] = 6 if FLUTE[x % 8] >= 4 else 5
            lv[1][x] = 5
            lv[2][x] = 3
            lv[3][x] = min(lv[3][x], 4)
    r = random.Random(seed)
    for _ in range(4):                                             # wenige Abplatzer an den Stegen
        x = r.randrange(4) * 8 + r.choice((0, 1))
        y = 5 + r.randrange(N - 8)
        lv[y][x] = 2
        lv[y + 1][x + 1] = max(lv[y + 1][x + 1], 5)
    return lv


def column_top_idx(seed):
    """Säulen-Stirn: polierte Platte mit Fase, darauf der runde Schaft als glatter gravierter Ring (r 12–13,4) mit
    16 Kerben für die Kanneluren und eine gravierte Mitte (r 4,6–6)."""
    lv = polished_idx(seed, light_grains=4, pits=2)
    bevel(lv, outer=(5, 2), inner=(4, 3))
    cx = cy = 16.0
    for y in range(2, N - 2):
        for x in range(2, N - 2):
            if math.hypot(x + 0.5 - cx, y + 0.5 - cy) < 12.0:
                lv[y][x] = lim(lv[y][x], 3, 5)                   # Schaftfläche ruhig
    engrave_ring(lv, cx, cy, 12.0, 13.4)
    for k in range(16):                                            # Kanneluren als Kerben innen am Ring
        a = k * TAU / 16 + TAU / 32
        x, y = int(cx + math.cos(a) * 11.2), int(cy + math.sin(a) * 11.2)
        lv[y][x] = 2
        lv[w(y + 1)][w(x + 1)] = max(lv[w(y + 1)][w(x + 1)], 5)
    engrave_ring(lv, cx, cy, 4.6, 6.0)
    return lv


# ---------------------------------------------------------------------------------------------
# Adern
# ---------------------------------------------------------------------------------------------

def _stamp(mask, side, x, y, brush, val, tx, ty):
    if brush == 2:
        bx, by = math.floor(x - 0.5), math.floor(y - 0.5)
        for j in range(2):
            for i in range(2):
                px, py = w(bx + i), w(by + j)
                if val >= mask[py][px]:
                    mask[py][px] = val
                    ox, oy = bx + i + 0.5 - x, by + j + 0.5 - y
                    nx, ny = -ty, tx
                    side[py][px] = (ox * nx + oy * ny) * math.copysign(1.0, -(nx + ny) + 1e-9)
    else:
        px, py = w(math.floor(x)), w(math.floor(y))
        if val > mask[py][px]:
            mask[py][px] = val
            side[py][px] = 0.0


def _branch(mask, side, x, y, ang, length, val, r, depth, brush=1, jag=0.0):
    curl = (1 if r.random() < 0.5 else -1) * (0.06 + r.random() * 0.08)
    wig = r.random() * 6
    forked = False
    s = 0.0
    while s < length:
        t = s / length
        _stamp(mask, side, x, y, brush, val * (1 - 0.5 * t), math.cos(ang), math.sin(ang))
        x += math.cos(ang) * 0.2
        y += math.sin(ang) * 0.2
        ang += (curl + math.sin(s * 0.7 + wig) * 0.06) * 0.2
        if jag and int(s * 5) % 12 == 0:
            ang += (r.random() - 0.5) * jag                        # Blitz: kurze Knicke
        if depth > 0 and not forked and s >= length * 0.5:
            forked = True
            sa = ang - math.copysign(0.85, curl)
            _branch(mask, side, x, y, sa, max(4, length // 2), val * 0.8, r, depth - 1, jag=jag)
        s += 0.2


def vein(seed, paths, branches=None, brush=2, branch_brush=1, jag=0.0, walks=()):
    """Periodische Adern: `paths` = Liste von Funktionen s → (x, y, tx, ty) für s in [0, 32); jede wickelt genau
    einmal über die Kachel (Sinus mit ganzer Periode), darum nahtlos. Je Ader Äste `(s, Winkel, Länge, Unterast)`.
    `walks = (x, y, Winkel, Länge, Tiefe)` sind freie, verästelte Linien ohne Grundader (Blitze).
    Liefert (mask, side, halo): mask 0…1 Aderstärke, side > 0 = Seite zum Licht oben links (Röhre), halo = Rille."""
    mask = [[0.0] * N for _ in range(N)]
    side = [[0.0] * N for _ in range(N)]
    r = random.Random(seed)
    for k, path in enumerate(paths):
        br = (branches or [[]] * len(paths))[k]
        nb = 0
        s = 0.0
        while s < N:
            x, y, tx, ty = path(s)
            tl = math.hypot(tx, ty)
            _stamp(mask, side, x, y, brush, 1.0, tx / tl, ty / tl)
            if nb < len(br) and s >= br[nb][0]:
                ang = math.atan2(ty, tx) + br[nb][1]
                _branch(mask, side, x + math.cos(ang), y + math.sin(ang), ang, int(br[nb][2]), 0.8, r, int(br[nb][3]),
                        brush=branch_brush, jag=jag)
                nb += 1
            s += 0.1
    for x, y, ang, length, depth in walks:                         # freie Äste (Blitze), wickeln über die Kachel
        _branch(mask, side, x, y, ang, length, 1.0, r, depth, brush=branch_brush, jag=jag)
    return mask, side, halo_of(mask)


def halo_of(mask):
    """Rille um eine Ader: Nachbarn (gerade 1,0, schräg 0,55) der Aderstärke, nur außerhalb der Ader."""
    halo = [[0.0] * N for _ in range(N)]
    for y in range(N):
        for x in range(N):
            if mask[y][x] > 0:
                continue
            m = 0.0
            for j in (-1, 0, 1):
                for i in (-1, 0, 1):
                    m = max(m, mask[w(y + j)][w(x + i)] * (1.0 if i == 0 or j == 0 else 0.55))
            halo[y][x] = m
    return halo


def _line(mask, x0, y0, x1, y1, val):
    """1-px-Linie (Bresenham, wickelt über den Rand)."""
    x0, y0, x1, y1 = round(x0), round(y0), round(x1), round(y1)
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x1 > x0 else -1), (1 if y1 > y0 else -1)
    err = dx + dy
    while True:
        px, py = w(x0), w(y0)
        mask[py][px] = max(mask[py][px], val)
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def lightning(seed, bolts, seg=(3, 5), spread=0.6):
    """Blitzadern: je Blitz `(x, y, Richtung, Länge, Äste)` eine Zickzack-Polylinie aus Stücken von 3–5 px, die um die
    Hauptrichtung bis ±`spread` rad pendeln (kein Kringeln), mit `Äste` Abzweigen halber Länge und schwächerem Wert.
    Liefert (mask, side, halo) wie `vein` (side = 0: die Linie ist 1 px dünn)."""
    mask = [[0.0] * N for _ in range(N)]
    r = random.Random(seed)

    def walk(x, y, direction, length, val, forks):
        done = 0.0
        points = []
        while done < length:
            a = direction + (r.random() * 2 - 1) * spread
            step = seg[0] + r.random() * (seg[1] - seg[0])
            nx, ny = x + math.cos(a) * step, y + math.sin(a) * step
            _line(mask, x, y, nx, ny, val * (1 - 0.35 * done / length))
            points.append((nx, ny))
            x, y = nx, ny
            done += step
        for k in range(forks):
            px, py = points[min(len(points) - 1, 1 + k * max(1, len(points) // (forks + 1)))]
            side = 1 if k % 2 == 0 else -1
            walk(px, py, direction + side * (0.6 + r.random() * 0.4), length * 0.45, val * 0.75, 0)
    for x, y, direction, length, forks in bolts:
        walk(x, y, direction, length, 1.0, forks)
    return mask, [[0.0] * N for _ in range(N)], halo_of(mask)


def sine_path_h(y0, a1, p1, a2, p2, jag=0.0, jag_k=5):
    """Waagerechte Ader: y = y0 + a1·sin(τs/N + p1) + a2·sin(2τs/N + p2) [+ Zacken mit ganzer Periode]."""
    def f(s):
        y = y0 + a1 * math.sin(TAU * s / N + p1) + a2 * math.sin(2 * TAU * s / N + p2) + jag * _tri(jag_k * s / N)
        ty = (a1 * TAU / N * math.cos(TAU * s / N + p1) + a2 * 2 * TAU / N * math.cos(2 * TAU * s / N + p2)
              + jag * jag_k / N * _dtri(jag_k * s / N))
        return s, y, 1.0, ty
    return f


def sine_path_v(x0, a1, p1, a2, p2, drift=0, jag=0.0, jag_k=5):
    """Senkrechte Ader: x = x0 + drift·s + a1·sin(τs/N + p1) + a2·sin(2τs/N + p2) [+ Zacken]; `drift` ganzzahlig
    (Kacheln je Umlauf / N), damit die Ader nach einem Umlauf wieder an derselben Stelle ankommt."""
    def f(s):
        x = x0 + drift * s + a1 * math.sin(TAU * s / N + p1) + a2 * math.sin(2 * TAU * s / N + p2) + jag * _tri(jag_k * s / N)
        tx = (drift + a1 * TAU / N * math.cos(TAU * s / N + p1) + a2 * 2 * TAU / N * math.cos(2 * TAU * s / N + p2)
              + jag * jag_k / N * _dtri(jag_k * s / N))
        return x, s, tx, 1.0
    return f


def _tri(t):
    """Dreieckswelle mit Periode 1, Werte −1…1 (für Blitz-Zacken; ganzzahlige Wiederholungen je Kachel → nahtlos)."""
    t = t % 1.0
    return 4 * t - 1 if t < 0.5 else 3 - 4 * t


def _dtri(t):
    return 4.0 if t % 1.0 < 0.5 else -4.0


def iso_distance(field):
    """Abstand jeder Pixelmitte (in px) zur Isolinie `field = 0,5`: |f − 0,5| / |∇f| (Gradient periodisch per
    Mittendifferenz). Damit werden Linien aus Rauschen überall gleich dick (≈ 1 px bei Abstand < 0,5) und
    zusammenhängend, statt in einzelne Pixel zu zerfallen."""
    out = []
    for y in range(N):
        row = []
        for x in range(N):
            gx = (field[y][w(x + 1)] - field[y][w(x - 1)]) / 2
            gy = (field[w(y + 1)][x] - field[w(y - 1)][x]) / 2
            row.append(abs(field[y][x] - 0.5) / max(math.hypot(gx, gy), 1e-4))
        out.append(row)
    return out


def noise_field(cells, seed, warp=0.0, warp_seed=0):
    """Periodisches Rauschfeld an den Pixelmitten, optional mit verzerrten Koordinaten (ganzzahlige Perioden)."""
    out = []
    for y in range(N):
        row = []
        for x in range(N):
            dx = (pn(x + 0.5, y + 0.5, 4, warp_seed) - 0.5) * warp if warp else 0.0
            dy = (pn(x + 0.5, y + 0.5, 4, warp_seed + 1) - 0.5) * warp if warp else 0.0
            row.append(pn(x + 0.5 + dx, y + 0.5 + dy, cells, seed))
        out.append(row)
    return out


def crack_field(seed, cells=(4, 8), warp=3.0, gate=0.45):
    """Glutrisse aus zwei `|pn − 0,5|`-Rinnen (Blood `GenPit.bloodGlow`), als Abstand in px zur nächsten Rinne.
    Isolinien sind geschlossene Schleifen; ein zweites Rauschfeld (`gate`) schneidet sie auf, damit offene,
    abbrechende Risse statt Ringe entstehen (außerhalb des Tors: Abstand 99)."""
    out = [[99.0] * N for _ in range(N)]
    for k, c in enumerate(cells):
        dist = iso_distance(noise_field(c, seed + 5 * k, warp, seed + 40 + k))
        door = noise_field(max(2, c // 2), seed + 70 + k)
        for y in range(N):
            for x in range(N):
                if door[y][x] > gate:
                    out[y][x] = min(out[y][x], dist[y][x] * (1.0 + 0.25 * k))
    return out


def lift(lv, d, lo=0, hi=7):
    """Verschiebt ein Index-Raster um `d` Stufen (z. B. Marmor näher an Quarz), begrenzt auf lo…hi."""
    for y in range(N):
        for x in range(N):
            lv[y][x] = lim(lv[y][x] + d, lo, hi)
    return lv


# ---------------------------------------------------------------------------------------------
# Platten (Bronze)
# ---------------------------------------------------------------------------------------------

def plates(seed, cols=2, rows=2, offset=0, rivets=True, hammer=True, tones=False):
    """Metallplatten: `cols × rows` Felder je Kachel (Reihe um `offset` versetzt), Fuge Stufe 1, Plattenkante
    oben/links hell und unten/rechts dunkel, gehämmerte Dellen (Licht oben links), Nieten in den Ecken."""
    lv = grid()
    pw, ph = N // cols, N // rows
    r = random.Random(seed)
    for y in range(N):
        for x in range(N):
            n = pn(x, y, 8, seed) * 0.5 + pn(x, y, 16, seed + 1) * 0.3 + pn(x, y, 4, seed + 2) * 0.2
            v = 4 + (1 if n > 0.64 else -1 if n < 0.34 else 0)
            if hammer:                                             # Delle: Licht unten rechts innen, Schatten oben links
                h = pn(x, y, 8, seed + 9)
                hl = pn(x + 1, y + 1, 8, seed + 9) - pn(x - 1, y - 1, 8, seed + 9)
                if h < 0.3 and hl > 0.06:
                    v += 1
                elif h < 0.3 and hl < -0.06:
                    v -= 1
            lv[y][x] = v
    for row in range(rows):
        off = offset if row % 2 else 0
        for col in range(cols):
            x0, y0 = col * pw + off, row * ph
            tone = (-1, 0, 0, 1)[r.randrange(4)] if tones else 0       # Ton je Platte erzeugt in der Fläche Streifen
            for ly in range(ph):
                for lx in range(pw):
                    x, y = w(x0 + lx), w(y0 + ly)
                    v = lv[y][x] + tone
                    if lx == pw - 1 or ly == ph - 1:
                        v = 1                                      # Fuge
                    elif ly == 0 or lx == 0:
                        v = max(v, 5) + (1 if ly == 0 and lx == 0 else 0)
                    elif ly == ph - 2 or lx == pw - 2:
                        v = min(v, 3)
                    lv[y][x] = lim(v, 0, 7)
            if rivets:
                for rx, ry in ((2, 2), (pw - 5, 2), (2, ph - 5), (pw - 5, ph - 5)):
                    x, y = w(x0 + rx), w(y0 + ry)
                    lv[y][x] = 7
                    lv[y][w(x + 1)] = 5
                    lv[w(y + 1)][x] = 5
                    lv[w(y + 1)][w(x + 1)] = 2
                    lv[w(y + 2)][w(x + 2)] = min(lv[w(y + 2)][w(x + 2)], 3)
    return lv


# ---------------------------------------------------------------------------------------------
# Messen (für `--check`)
# ---------------------------------------------------------------------------------------------

def srgb_lab(c):
    def lin(u):
        u /= 255
        return u / 12.92 if u <= 0.04045 else ((u + 0.055) / 1.055) ** 2.4
    r, g, b = (lin(v) for v in c[:3])
    xx = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    yy = 0.2126 * r + 0.7152 * g + 0.0722 * b
    zz = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883

    def f(t):
        return t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
    fx, fy, fz = f(xx), f(yy), f(zz)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def delta_e(a, b):
    """CIE76-Farbabstand in Lab (ΔE ≈ 2,3 ist eben sichtbar)."""
    la, lb = srgb_lab(a), srgb_lab(b)
    return math.sqrt(sum((la[i] - lb[i]) ** 2 for i in range(3)))
