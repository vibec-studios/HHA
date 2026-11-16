"""Kronos, der Titan der Zeit (Endboss): älter als die Götter und zu lange angekettet.

Erkennungsmerkmale (alle im Schattenriss):
1. Bucklige Kolossgestalt: der Kopf sitzt tief vorn zwischen felsigen Schultermassen, die Arme reichen bis unter die Knie.
2. Das Stundenglas im Leib: die Taille ist ein offener Hohlraum zwischen Rippenbogen und Becken, gehalten von vier
   steinernen Streben; darin fällt leuchtender Sternensand.
3. Der zerbrochene Himmelsring: ein Dreiviertelkreis aus Altgold (12 von 16 Segmenten) schwebt hinter den Schultern.
Dazu die Harpe, eine riesige Sichel aus Adamant. Sie ist in sich geschlossen (Ursprung am Griff), weil sie später als
Modell fliegt (Plan 6.5).

Material: Nachtstein (Körper), Altgold (Krone, Ring, Harpenbeschläge, Schellenränder; leuchtet nie), Adamant (Klinge,
Stundenglas), Sternenmantel (Mantel, Lendentuch) und Eisen für Schellen/Ketten. Leuchtfarbe: Äon-Indigo mit
sternweißem Kern (Augen, Schlund, Sand, Schneide, vier breite Risse).

Koordinaten: 1/16 Block, +y nach unten, -z vorn, Füße bei y = 24, Scheitel bei y = -114 (Krone bis -122). Renderer-Faktor
0,69 (× 1,18 in Form 1). Knochen siehe Brief tmp/overhaul/models/kronos.md.

`POSES` enthält jede Schlüsselpose der Kotlin-Animation (client/model/KronosModel.kt). Die Posen entstehen hier aus
wenigen Hauptwinkeln; Füße, Bodenkontakt, Mantel, Ketten, Bart und Lendentuch rechnet `dress()` daraus. Der Block
zwischen `// <keys>` und `// </keys>` in KronosModel.kt wird mit `python tools/hha_assets/kronos_model.py --kotlin`
aus genau diesen Posen erzeugt; so können Vorschau und Spiel nicht auseinanderlaufen.
"""
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from bosskit import IRON, Cube  # noqa: E402
from modelkit import Model, Part, hexc, mix, ramp, rgb, speckle  # noqa: E402

# ---------------------------------------------------------------------------------------------------------------------
# Paletten und Licht
# ---------------------------------------------------------------------------------------------------------------------

NIGHT = rgb('#07080f', '#161a2c', '#2a3050', '#4a5480')
NIGHT_RIDGE = hexc('#66719f')
GOLD = rgb('#3a2508', '#6a4512', '#a0701c', '#d4a32e')
ADAMANT = rgb('#3a4258', '#5c6680', '#8a96b4', '#c8d2ea')
MANTLE = rgb('#03040c', '#070b1e', '#0e1534', '#18224e', '#26346c')
CHAIN = IRON
AEON = (106, 124, 255)
STAR = (238, 242, 255)
LIGHT = {'top': 0.16, 'bottom': -0.24, 'front': 0.0, 'back': -0.1, 'left': -0.06, 'right': -0.06}


def aeon(t, strength=255):
    """Leuchten der Zeit: t = 0 tiefes Indigo, 1 sternweißer Kern."""
    color = ramp(rgb('#30388e', '#6a7cff', '#b8c4ff', '#eef2ff'), t)
    g = mix(AEON + (255,), STAR + (255,), max(0.0, (t - 0.45) / 0.55))
    return (color, (g[0], g[1], g[2], strength))


def _hash(*v):
    h = 2166136261
    for n in v:
        h = ((h ^ (int(n) & 0xffffffff)) * 16777619) & 0xffffffff
    return h / 0xffffffff


_cells = {}


def facets(face, w, h, seed):
    """Große Bruchflächen: wenige Zellen je Fläche (deterministisch), jede mit eigener Helligkeit."""
    key = (face, w, h, seed)
    if key not in _cells:
        n = max(2, min(9, (w * h) // 70 + 1))
        pts = [(_hash(seed, i, 1, w, h, len(face)) * w, _hash(seed, i, 2, w, h, len(face)) * h,
                (_hash(seed, i, 3, len(face)) - 0.5) * 0.16) for i in range(n)]
        _cells[key] = pts
    return _cells[key]


def _seg_dist(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    L = dx * dx + dy * dy
    t = 0.0 if L == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L))
    return math.hypot(px - (ax + dx * t), py - (ay + dy * t))


def crack_dist(lines, x, y, w, h):
    """Abstand eines Texels zur nächsten Risslinie (Linien in 0..1 der Fläche)."""
    best = 99.0
    for line in lines:
        for (ax, ay), (bx, by) in zip(line, line[1:]):
            best = min(best, _seg_dist(x + 0.5, y + 0.5, ax * w, ay * h, bx * w, by * h))
    return best


def stone(base=0.46, seed=0, glow_cracks=None, ridge=True, seam=True):
    """Nachtstein: fast schwarzer, blaustichiger Fels mit großen Bruchflächen, harten Lichtkanten oben und wenigen breiten
    Rissen. glow_cracks = {Fläche: [Polylinie, ...]} legt leuchtende Äonrisse (3 Texel breit, sternweißer Kern)."""
    def paint(face, x, y, w, h):
        if glow_cracks and face in glow_cracks:
            d = crack_dist(glow_cracks[face], x, y, w, h)
            if d < 0.75:
                return aeon(0.95)
            if d < 1.7:
                return aeon(0.5, 235)
            if d < 2.6:
                return ramp(NIGHT, 0.08)
        v = base + LIGHT[face]
        if face not in ('top', 'bottom'):
            v += (0.5 - y / max(h - 1, 1)) * 0.16
        pts = facets(face, w, h, seed)
        ds = sorted(((px - x - 0.5) ** 2 + (py - y - 0.5) ** 2, off) for px, py, off in pts)
        v += ds[0][1]
        if len(ds) > 1:
            gap = math.sqrt(ds[1][0]) - math.sqrt(ds[0][0])
            if gap < 0.9:
                v -= 0.16          # Fuge zwischen zwei Bruchflächen
            elif gap < 1.9:
                v += 0.05          # Grat neben der Fuge fängt Licht
        v += (speckle(x, y, seed) - 0.5) * 0.06
        if ridge and face != 'bottom' and y == 0:
            if face == 'top' or h > 2:
                c = ramp(NIGHT, v + 0.12)
                return mix(c, NIGHT_RIDGE, 0.45)
        if ridge and face == 'top' and (x == 0 or x == w - 1):
            v += 0.08
        if seam and face not in ('top', 'bottom') and y == h - 1:
            v -= 0.16
        return ramp(NIGHT, v)
    return paint


def gold(base=0.62, seed=0, notches=0):
    """Mattes Altgold mit Kantenlicht. notches > 0 graviert Stundenstriche quer über die Vorderfläche."""
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] * 0.9 + (0.5 - y / max(h - 1, 1)) * 0.1 + (speckle(x, y, seed) - 0.5) * 0.07
        if x == 0 or y == 0 or x == w - 1 or y == h - 1:
            v += 0.12
        if notches and face in ('front', 'back') and w > 6 and x in (w // 2 - 1, w // 2) and 0 < y < h - 1:
            v -= 0.32
        return ramp(GOLD, v)
    return paint


def iron(base=0.5, seed=0, rim=False, hole=None):
    """Eisen der Schellen; rim = Altgoldkante oben und unten; hole = (Flächen) mit Loch in der Mitte (Kettenglied)."""
    def paint(face, x, y, w, h):
        if hole and face in hole and 1 <= x <= w - 2 and 2 <= y <= h - 3 and w >= 4:
            return None
        if rim and face not in ('top', 'bottom') and (y == 0 or y == h - 1):
            return ramp(GOLD, 0.62 + LIGHT[face] + (0.1 if y == 0 else -0.05))
        v = base + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.12 + (speckle(x, y, seed) - 0.5) * 0.07
        if x == 0 or x == w - 1:
            v += 0.08
        return ramp(CHAIN, v)
    return paint


def adamant(base=0.55, seed=0):
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.12 + (speckle(x, y, seed) - 0.5) * 0.06
        if x == 0 or x == w - 1:
            v += 0.1
        return ramp(ADAMANT, v)
    return paint


def blade_paint(i):
    """Sichelklinge: flache Seiten blasser Adamant mit Hohlkehle, die Schneide (Innenkante, -z) leuchtet schmal."""
    def paint(face, x, y, w, h):
        if face == 'front':
            return aeon(0.92 if x == w // 2 else 0.7)
        if face in ('left', 'right'):
            e = x if face == 'left' else w - 1 - x      # Abstand zur Schneide in Texeln
            if e == 0:
                return aeon(0.55, 220)
            if e == 1:
                return ramp(ADAMANT, 0.97)
            v = 0.72 - e / max(w - 1, 1) * 0.42 + (0.5 - y / max(h - 1, 1)) * 0.08
            if w >= 6 and abs(e - w * 0.55) < 0.8:
                v -= 0.2                                  # Hohlkehle
            if e == w - 1:
                v = 0.32                                  # Rücken
            return ramp(ADAMANT, v + (speckle(x, y, 500 + i) - 0.5) * 0.05)
        if face == 'back':
            return ramp(ADAMANT, 0.3 + (speckle(x, y, 520 + i) - 0.5) * 0.05)
        return ramp(ADAMANT, 0.5 + LIGHT[face])
    return paint


def shaft_paint(face, x, y, w, h):
    """Harpenschaft: dunkler Adamant; nur an den beiden Griffstellen schräg umwickelt (Griff bei y = 44, zweiter Griff
    darunter bei 64, Schaft beginnt bei 0)."""
    wrap = (34 <= y <= 52 or 56 <= y <= 66) and ((y + x) % 4) < 2
    v = 0.16 + LIGHT[face] + (0.1 if wrap else 0.0) + (0.14 if x in (0, w - 1) else 0.0) + (speckle(x, y, 930) - 0.5) * 0.05
    return ramp(ADAMANT, v)


# Sternpunkte des Mantels (außen = 'back'-Fläche jedes Glieds): (Glied, x, y, Kern?) – höchstens 20 Leuchttexel.
STARS = {
    0: [(6, 5, True), (21, 11, False), (12, 19, False)],
    1: [(4, 4, False), (17, 8, True), (25, 15, False), (10, 14, False)],
    2: [(7, 3, False), (19, 6, False), (13, 11, True), (22, 13, False)],
    3: [(5, 4, False), (16, 6, False), (11, 9, True)],
}


def mantle(link=0, torn=False, rips=()):
    def paint(face, x, y, w, h):
        if torn and face in ('front', 'back', 'left', 'right'):
            jag = 2 + int(3 * abs(math.sin(x * 1.3 + 0.7)) + 2 * _hash(x, 77))
            if y >= h - jag:
                return None
        for rx, ry, rw, rh in rips:
            if face in ('front', 'back') and rx <= x < rx + rw and ry <= y < ry + rh - abs(x - rx - rw // 2):
                return None
        if face == 'back':
            for sx, sy, core in STARS.get(link, ()):
                if x == sx and y == sy:
                    return (hexc('#eef2ff') if core else hexc('#9aa8ff'),
                            (STAR + (255,)) if core else (AEON + (200,)))
        fold = math.sin(x * 0.55 + link) * 0.12 + math.sin(x * 1.3 + link * 2) * 0.04
        v = 0.5 + LIGHT[face] + fold + (0.5 - y / max(h - 1, 1)) * 0.1 + (speckle(x, y, 600 + link) - 0.5) * 0.05
        if face in ('front', 'back') and (x == 0 or x == w - 1):
            v += 0.14
        return ramp(MANTLE, v)
    return paint


def cloth(seed=0, torn=True):
    """Lendentuch aus demselben Sternenstoff, ohne Sterne."""
    def paint(face, x, y, w, h):
        if torn and face in ('front', 'back', 'left', 'right'):
            jag = 1 + int(3 * abs(math.sin(x * 0.9 + seed)) * _hash(x, seed))
            if y >= h - jag:
                return None
        fold = math.sin(x * 0.8 + seed) * 0.13
        return ramp(MANTLE, 0.52 + LIGHT[face] + fold + (0.5 - y / max(h - 1, 1)) * 0.12)
    return paint


def glass_paint(k, top):
    """Stundenglas: kaltes Adamantglas; jedes zweite Segment hat ein Fenster (der Sand ist sichtbar)."""
    def paint(face, x, y, w, h):
        if face in ('front', 'back') and 1 <= x <= w - 2 and (1 <= y <= h - 2 if k % 2 == 0 else 3 <= y <= h - 4):
            return None
        edge = x == 0 or x == w - 1
        v = (0.62 if edge else 0.42) + LIGHT[face] + (0.12 if (y < 2) == top else 0.0)
        return ramp(ADAMANT, v)
    return paint


def sand(t=0.7, strength=255):
    def paint(face, x, y, w, h):
        return aeon(t + (0.12 if face == 'top' else 0.0) + (speckle(x, y, 700) - 0.5) * 0.15, strength)
    return paint


def face_paint(face, x, y, w, h):
    """Schädel: tiefe Augenhöhlen unter dem Stirnwulst, darin indigo Augen; Gaumen leuchtet (nur bei offenem Kiefer)."""
    base = stone(0.5, 801)
    if face == 'front':
        cx = (w - 1) / 2.0
        d = abs(x - cx)
        if 5 <= y <= 8 and 1.5 <= d <= 7:
            if y in (6, 7) and 2.0 <= d <= 6.0:
                return aeon(1.0 if 3.0 <= d <= 5.0 else 0.62)
            return ramp(NIGHT, 0.02)
    if face == 'bottom':
        cx = (w - 1) / 2.0
        if y <= 1:
            return ramp(rgb('#3a3f58', '#7d86a6'), 0.7 if (x % 3) else 0.2)    # Zahnreihe
        if abs(x - cx) < w / 2 - 2 and y < h - 3:
            return aeon(0.25 + 0.3 * (1 - y / h), 230)
    return base(face, x, y, w, h)


def jaw_paint(face, x, y, w, h):
    base = stone(0.46, 802)
    if face == 'top':
        cx = (w - 1) / 2.0
        if y <= 1:
            return ramp(rgb('#3a3f58', '#7d86a6'), 0.7 if (x % 3) else 0.2)
        if abs(x - cx) < w / 2 - 2 and y < h - 2:
            return aeon(0.2 + 0.35 * (1 - y / h), 230)
    return base(face, x, y, w, h)


def beard_paint(seed):
    """Steinbart: Strähnenrillen der Länge nach, unten heller abgewittert."""
    def paint(face, x, y, w, h):
        v = 0.44 + LIGHT[face] + (0.1 if x % 3 == 1 else -0.04) + y / max(h - 1, 1) * 0.1 + (speckle(x, y, seed) - 0.5) * 0.05
        if face not in ('top', 'bottom') and y == 0:
            v -= 0.12
        return ramp(NIGHT, v)
    return paint


def broken_top(paint):
    """Abgebrochene Kronzacke: dunkle Bruchfläche oben."""
    def p(face, x, y, w, h):
        if face == 'top':
            return ramp(GOLD, 0.12 + 0.1 * ((x + y) % 2))
        if face != 'bottom' and y == 0 and x % 2 == 0:
            return None
        return paint(face, x, y, w, h)
    return p


# ---------------------------------------------------------------------------------------------------------------------
# Hilfen
# ---------------------------------------------------------------------------------------------------------------------

def bone_at(name, pivot, parent, rot=(0, 0, 0), cubes=(), children=()):
    """Knochen mit absolutem Drehpunkt (Modellraum); intern relativ zum Elternpunkt."""
    rel = tuple(a - b for a, b in zip(pivot, parent))
    return Part(name, rel, rot, cubes=list(cubes), children=list(children))


def slant(name, tilt, yaw, center_y, center_r, length, width, thick, paint):
    """Schräge Platte eines Kegelstumpfs: Mitte bei (Radius center_r, Höhe center_y), geneigt um tilt (Grad)."""
    t = math.radians(tilt)
    y, z = center_y, -center_r
    ly = y * math.cos(t) + z * math.sin(t)
    lz = -y * math.sin(t) + z * math.cos(t)
    return Part(name, (0, 0, 0), (tilt, yaw, 0), cubes=[
        Cube(-width / 2.0, ly - length / 2.0, lz - thick / 2.0, width, length, thick, paint)])


# ---------------------------------------------------------------------------------------------------------------------
# Absolute Drehpunkte (Ruhe = aufrecht, volle Größe)
# ---------------------------------------------------------------------------------------------------------------------

P_HIPS = (0, -34, 0)
P_TORSO = (0, -42, 0)
P_GLASS = (0, -53, 0)
P_CHEST = (0, -64, 0)
P_HEAD = (0, -96, -12)
P_RING = (0, -86, 25)
P_ARM_R = (-29, -95, 1)
P_ARM_L = (29, -91, 1)
P_THIGH_R = (-10, -30, 0)
P_THIGH_L = (10, -30, 0)
P_MANTLE = (21, -104, 16)
UPPER_ARM, FOREARM = 37, 42
THIGH, SHIN = 25, 23
GRIP = (0, 48, 0)              # Griff der Harpe im Unterarmraum (Mitte der Faust)
BLADE = [  # (Länge, Breite, Biegung zum Vorglied); die Glieder laufen an der Schneide entlang, der Rücken liegt außen
    (12, 10, 10), (12, 10, 28), (12, 9, 28), (12, 8, 28), (11, 7, 28), (11, 6, 28), (10, 4, 28), (9, 3, 30)]
SHAFT_TOP = -44                # Schaftende an der Klinge (Harpenraum, -y = zur Klinge); Knauf bei +26
RING_R = 34


def harpe_part():
    """Die Harpe, Ursprung am Griff: Schaft entlang -y, die Sichel biegt nach vorn (-z) und zurück zum Griff."""
    shaft_c = shaft_paint
    cubes = [
        Cube(-2.5, SHAFT_TOP, -2.5, 5, 70, 5, shaft_c),                       # Schaft 70 lang, 5 dick
        Cube(-3.5, SHAFT_TOP - 3, -4.5, 7, 6, 13, gold(0.6, 901)),           # Klingenfassung
        Cube(-3.5, -10, -3.5, 7, 3, 7, gold(0.55, 902)),                      # Griffring oben
        Cube(-3.5, 9, -3.5, 7, 3, 7, gold(0.55, 903)),                        # Griffring unten
        Cube(-3.5, 26, -3.5, 7, 4, 7, gold(0.66, 904)),                       # Knauf
        Cube(-2, 30, -2, 4, 3, 4, gold(0.5, 905)),
        Cube(-3, -27, -3, 6, 3, 6, gold(0.5, 906)),                           # Mittelring
        Cube(-1.5, SHAFT_TOP + 1, 7, 3, 7, 4, adamant(0.45, 907)),            # Rückendorn an der Fassung
        Cube(-1.5, SHAFT_TOP + 6, 9, 3, 5, 3, adamant(0.55, 908)),
    ]
    # Klinge: Glieder hängen aneinander; jedes überlappt das vorige um 2 Einheiten (keine Lücken außen am Bogen).
    chain = None
    root = None
    prev_len = None
    for i, (length, width, bend) in enumerate(BLADE):
        cube = Cube(-1.5, -length, 0, 3, length + 2, width, blade_paint(i))
        if root is None:
            part = Part('blade', (0, SHAFT_TOP - 2, -3), (bend, 0, 0), cubes=[cube])
            root = part
        else:
            part = Part(f'blade_{i}', (0, -prev_len, 0), (bend, 0, 0), cubes=[cube])
            chain.add(part)
        chain = part
        prev_len = length
    return Part('harpe', GRIP, (0, 0, 0), cubes=cubes, children=[root])


def chain_parts(s):
    """Gesprengte Kette am Handgelenk: drei Glieder (je 3 dick), das letzte offen."""
    link_a = iron(0.42, 910, hole=('left', 'right'))
    link_b = iron(0.42, 911, hole=('front', 'back'))
    side = -1 if s == 'r' else 1
    child = Part(f'chain_{s}_1', (0, 17, 0), cubes=[
        Cube(-1.5, 0, -4, 3, 10, 8, iron(0.4, 912, hole=('left', 'right'))),
        Cube(-1.5, 9, -4, 3, 3, 3, iron(0.36, 913)),                          # aufgebogenes Ende
    ])
    return Part(f'chain_{s}', (side * 5, 37, 7), cubes=[
        Cube(-1.5, 0, -4, 3, 10, 8, link_a),
        Cube(-4, 8, -1.5, 8, 10, 3, link_b),
    ], children=[child])


def arm(s):
    side = -1 if s == 'r' else 1
    pivot = P_ARM_R if s == 'r' else P_ARM_L
    cracks_fore = {('right' if s == 'r' else 'left'): [[(0.2, 0.05), (0.45, 0.3), (0.35, 0.55), (0.7, 0.9)]],
                   'front': [[(0.3, 0.0), (0.5, 0.2), (0.4, 0.45)]]} if s == 'l' else None
    hand_children = [Part(f'fingers_{s}', (0, 11, 0), cubes=[
        c for i, zc in enumerate((-6.75, -2.25, 2.25, 6.75)) for c in (
            Cube(-side * 1 - 2, 0, zc - 2, 4, 8 - (i in (0, 3)), 4, stone(0.48, 820 + i, seam=False)),
            Cube(-side * 3 - 2, 6 - (i in (0, 3)), zc - 2, 4, 6, 4, stone(0.52, 824 + i)),
        )
    ])]
    hand = Part(f'hand_{s}', (0, FOREARM, 0), cubes=[
        Cube(-5, 0, -9, 10, 12, 18, stone(0.5, 830 + (s == 'l'))),            # Handteller
        Cube(-6, -1, -8, 12, 4, 16, stone(0.44, 832)),                        # Handwurzel
        Cube(-side * 1 - 2, 3, -13, 4, 9, 5, stone(0.5, 833)),                # Daumen
    ], children=hand_children)
    fore_children = [hand, chain_parts(s)]
    if s == 'r':
        fore_children.append(harpe_part())
    fore = Part(f'fore_{s}', (0, UPPER_ARM, 0), cubes=[
        Cube(-6.5, 0, -6.5, 13, 18, 13, stone(0.48, 840 + (s == 'l'), glow_cracks=cracks_fore)),
        Cube(-8, 16, -8, 16, 26, 16, stone(0.5, 842 + (s == 'l'), glow_cracks=cracks_fore)),
        Cube(-5, -3, 3, 10, 8, 6, stone(0.44, 844)),                          # Ellbogen
        Cube(-10, 31, -10, 20, 9, 20, iron(0.48, 845, rim=True)),             # Eisenschelle
    ], children=fore_children + [
        # um 45° gedrehte Kerne runden die Kanten (Achteck statt Kasten)
        Part(f'fore_{s}_o', (0, 0, 0), (0, 45, 0), cubes=[
            Cube(-5, 1, -5, 10, 16, 10, stone(0.46, 846)),
            Cube(-6, 17, -6, 12, 14, 12, stone(0.48, 847, glow_cracks=cracks_fore))])])
    return Part(f'arm_{s}', tuple(a - b for a, b in zip(pivot, P_CHEST)), cubes=[
        Cube(-7, -3, -7, 14, 40, 14, stone(0.47, 850 + (s == 'l'))),
        Cube(-6, 6, -9, 12, 18, 3, stone(0.53, 852)),                         # Bizeps
        Cube(-8.5, -7, -8.5, 17, 11, 17, stone(0.5, 853)),                    # Schulterkappe
    ], children=[fore, Part(f'arm_{s}_o', (0, 0, 0), (0, 45, 0), cubes=[Cube(-5.5, 2, -5.5, 11, 33, 11, stone(0.45, 854))])])


def leg(s):
    side = -1 if s == 'r' else 1
    pivot = P_THIGH_R if s == 'r' else P_THIGH_L
    foot = Part(f'foot_{s}', (0, SHIN, 0), cubes=[
        Cube(-8, 0, -12, 16, 6, 20, stone(0.46, 860 + (s == 'l'))),
        Cube(-8, 2, -16, 5, 4, 4, stone(0.5, 862)),                           # Zehen
        Cube(-2.5, 2, -16.5, 5, 4, 5, stone(0.52, 863)),
        Cube(3, 2, -15.5, 5, 4, 4, stone(0.48, 864)),
        Cube(-6, -2, -5, 12, 3, 12, stone(0.44, 865)),                        # Knöchel
    ])
    shin_cubes = [
        Cube(-7, 0, -7, 14, 23, 14, stone(0.47, 866 + (s == 'l'))),
        Cube(-5.5, -4, -9.5, 11, 9, 4, stone(0.55, 868)),                     # Kniescheibe
    ]
    if s == 'l':
        shin_cubes.append(Cube(-9, 15, -9, 18, 6, 18, iron(0.46, 869, rim=True)))   # gesprengte Fußschelle
    shin = Part(f'shin_{s}', (0, THIGH, 0), cubes=shin_cubes, children=[
        foot, Part(f'shin_{s}_o', (0, 0, 0), (0, 45, 0), cubes=[Cube(-5, 1, -5, 10, 20, 10, stone(0.45, 874))])])
    return Part(f'thigh_{s}', tuple(a - b for a, b in zip(pivot, P_HIPS)), cubes=[
        Cube(-8, -2, -8, 16, 28, 16, stone(0.45, 870 + (s == 'l'))),
        Cube(-side * 1 - 6, 4, -9.5, 12, 14, 3, stone(0.5, 872)),             # Oberschenkelmuskel
    ], children=[shin, Part(f'thigh_{s}_o', (0, 0, 0), (0, 45, 0), cubes=[Cube(-6, 0, -6, 12, 24, 12, stone(0.43, 873))])])


def glass_part():
    parts = []
    for k in range(8):
        yaw = k * 45 + 22.5
        parts.append(slant(f'glass_t{k}', 28.6, yaw, -5.5, 6, 13, 7, 2, glass_paint(k, True)))
        parts.append(slant(f'glass_b{k}', -28.6, yaw, 5.5, 6, 13, 7, 2, glass_paint(k + 1, False)))
    for name, y, d in (('glass_cap_t', -13, 0), ('glass_cap_b', 11, 0)):
        parts.append(Part(name, (0, 0, 0), (0, 45, 0), cubes=[Cube(-9, y, -9, 18, 2, 18, adamant(0.38, 920))]))
    grains = [Part(f'sand_{i}', (0, -2 + i * 5, 0), cubes=[Cube(-1, -1, -1, 2, 2, 2, sand(1.0))]) for i in range(2)]
    return Part('glass', tuple(a - b for a, b in zip(P_GLASS, P_TORSO)), cubes=[
        Cube(-9, -13, -9, 18, 2, 18, adamant(0.42, 921)),                     # Deckel
        Cube(-9, 11, -9, 18, 2, 18, adamant(0.36, 922)),                      # Boden
        Cube(-6, -9, -6, 12, 4, 12, sand(0.62)),                              # Sand oben
        Cube(-1, -5, -1, 2, 14, 2, sand(0.95)),                               # Sandstrahl 2 x 2
        Cube(-7, 9, -7, 14, 2, 14, sand(0.6)),                                # Sandhaufen
        Cube(-5, 7, -5, 10, 2, 10, sand(0.72)),
        Cube(-3, 5, -3, 6, 2, 6, sand(0.85)),
    ], children=parts + grains)


def ring_part():
    """Zerbrochener Himmelsring: 12 von 16 Segmenten (Radius 34, 3 dick), Knoten wie an einer Armillarsphäre."""
    segs = []
    present = [k for k in range(16) if k not in (12, 13, 14, 15)]
    for k in present:
        ang = k * 22.5 - 45          # Lücke oben links (von vorn gesehen)
        cubes = [Cube(-7, -RING_R - 1.5, -2, 14, 3, 4, gold(0.6, 940 + k, notches=1))]
        if k in (1, 5, 9):
            cubes.append(Cube(-3, -RING_R - 4, -3, 6, 9, 6, gold(0.7, 960 + k)))   # Knoten
        if k == present[0]:
            cubes.append(Cube(-9, -RING_R - 2.5, -1.5, 4, 3, 3, gold(0.3, 970)))  # Bruchkante
        if k == present[-1]:
            cubes.append(Cube(5, -RING_R + 0.5, -1.5, 4, 3, 3, gold(0.3, 971)))
        segs.append(Part(f'ring_{k}', (0, 0, 0), (0, 0, ang), cubes=cubes))
    # Innerer Halbbogen (Ekliptik), schräg gestellt: gibt dem Ring Tiefe.
    for j in range(5):
        segs.append(Part(f'ring_e{j}', (0, 0, 0), (62, 0, -60 + j * 30), cubes=[
            Cube(-6, -RING_R + 6, -1.5, 12, 3, 3, gold(0.48, 980 + j))]))
    return Part('ring', tuple(a - b for a, b in zip(P_RING, P_CHEST)), children=segs)


def head_part():
    jaw_pivot = (0, -7, 1)
    crown_spots = [  # (x, z, Gierwinkel, steht?)
        (0, -10.5, 0, True), (-6.5, -9.5, 35, True), (6.5, -9.5, -35, False), (-9.5, -2, 90, False),
        (9.5, -2, -90, True), (-6.5, 4.5, 145, True), (6.5, 4.5, -145, False), (0, 6, 180, True)]
    prongs = []
    for k, (x, z, yaw, standing) in enumerate(crown_spots):
        tall = 2 if k == 0 else 0
        if standing:
            cubes = [Cube(-1.5, -6 - tall, -1.5, 3, 7 + tall, 3, gold(0.66, 990 + k))]
        else:
            cubes = [Cube(-1.5, -2, -1.5, 3, 3, 3, broken_top(gold(0.55, 1010 + k)))]
        prongs.append(Part(f'crown_{k}', (x, -1, z), (-10, yaw, 0), cubes=cubes))
    crown = Part('crown', (0, -18, -3), cubes=[
        Cube(-9, -1, -9.5, 18, 3, 2, gold(0.6, 1020)),
        Cube(-9, -1, 7.5, 18, 3, 2, gold(0.5, 1021)),
        Cube(-10, -1, -8, 2, 3, 16, gold(0.55, 1022)),
        Cube(8, -1, -8, 2, 3, 16, gold(0.55, 1023)),
    ], children=prongs)
    jaw = Part('jaw', jaw_pivot, cubes=[
        Cube(-7, 0, -14, 14, 6, 14, jaw_paint),
        Cube(-5, 5, -15, 10, 3, 7, stone(0.5, 1030)),                          # Kinn
    ])
    beard_1 = Part('beard_1', (0, 15, -12.5), cubes=[
        Cube(-6.5, -1, -1.5, 3, 5, 3, beard_paint(1040)),
        Cube(-2, -1, -2, 4, 7, 4, beard_paint(1041)),
        Cube(3.5, -1, -1.5, 3, 4, 3, beard_paint(1042)),
        Cube(-1.5, 5, -1.5, 3, 4, 3, beard_paint(1043)),
    ])
    beard = Part('beard', jaw_pivot, cubes=[
        Cube(-7, 7, -15, 4, 9, 4, beard_paint(1044)),
        Cube(-2, 7, -16, 4, 10, 4, beard_paint(1045)),
        Cube(3, 7, -15, 4, 9, 4, beard_paint(1046)),
        Cube(-6, 5, -15.5, 12, 3, 4, beard_paint(1047)),                        # Bartansatz am Kinn
    ], children=[beard_1])
    return Part('head', tuple(a - b for a, b in zip(P_HEAD, P_CHEST)), cubes=[
        Cube(-8, -18, -11, 16, 11, 16, face_paint),                            # Schädel
        Cube(-9, -16, -13.5, 18, 4, 4, stone(0.64, 1050)),                     # Stirnwulst
        Cube(-2.5, -13, -14, 5, 2, 3, stone(0.64, 1051)),                      # Nasenwurzel
        Cube(-2, -12, -14.5, 4, 5, 3, stone(0.58, 1052)),                      # Nase
        Cube(-8.5, -9, -12.5, 5, 3, 3, stone(0.6, 1053)),                      # Wangenknochen
        Cube(3.5, -9, -12.5, 5, 3, 3, stone(0.6, 1054)),
        Cube(-7, -16, 4, 14, 10, 3, stone(0.42, 1055)),                        # Hinterkopf
    ], children=[jaw, crown, beard])


def mantle_parts():
    m3 = Part('mantle_3', (0, 13, 0), cubes=[Cube(-10, -1, 0, 21, 15, 3, mantle(3, torn=True))])
    m2 = Part('mantle_2', (0, 14, 0), cubes=[Cube(-12, -1, 0, 25, 15, 3, mantle(2, rips=((17, 4, 4, 8),)))], children=[m3])
    m1 = Part('mantle_1', (0, 21, 0), cubes=[Cube(-14, -1, 0, 29, 15, 3, mantle(1))], children=[m2])
    return Part('mantle_0', tuple(a - b for a, b in zip(P_MANTLE, P_CHEST)), cubes=[
        Cube(-15, -3, 0, 30, 24, 3, mantle(0)),
    ], children=[m1])


def chest_part():
    chest_cracks = {'front': [[(0.18, 0.12), (0.33, 0.35), (0.3, 0.55), (0.48, 0.8), (0.46, 1.0)]]}
    shoulder_cracks = {'front': [[(0.15, 0.0), (0.4, 0.35), (0.35, 0.6), (0.6, 1.0)]],
                       'top': [[(0.3, 1.0), (0.5, 0.5), (0.8, 0.3)]]}
    hump_cracks = {'back': [[(0.6, 0.0), (0.5, 0.4), (0.62, 0.7), (0.55, 1.0)]]}
    cubes = [
        Cube(-24, -34, -14, 48, 22, 28, stone(0.47, 1100, glow_cracks=chest_cracks)),     # Brustkorb
        Cube(-22, -32, -16, 21, 14, 3, stone(0.54, 1101)),                    # Brustmuskel rechts
        Cube(1, -31, -16, 21, 13, 3, stone(0.52, 1102, glow_cracks=chest_cracks)),        # links
        # Rippenbogen: schmaler als die Brust, nach unten offen, vorn als Bogen
        Cube(-19, -12, -13, 8, 12, 26, stone(0.44, 1103)),
        Cube(11, -12, -13, 8, 12, 26, stone(0.44, 1104)),
        Cube(-11, -12, -13, 6, 7, 26, stone(0.42, 1105)),
        Cube(5, -12, -13, 6, 7, 26, stone(0.42, 1106)),
        Cube(-5, -12, -13, 10, 4, 26, stone(0.4, 1107)),
        # Buckel und Nacken
        Cube(-17, -38, 0, 34, 22, 14, stone(0.45, 1108, glow_cracks=hump_cracks)),     # Nacken/Trapez
        Cube(-21, -30, 6, 42, 16, 10, stone(0.42, 1109)),                     # Rückenwulst
        Cube(-9, -40, -18, 18, 10, 14, stone(0.4, 1110)),
        # Schultermassen wie Felsblöcke, rechts höher
        Cube(-37, -46, -12, 24, 23, 27, stone(0.5, 1111, glow_cracks=shoulder_cracks)),
        Cube(-40, -41, -8, 4, 15, 19, stone(0.46, 1113)),
        Cube(13, -42, -12, 24, 23, 27, stone(0.48, 1114)),
        Cube(36, -37, -8, 4, 14, 18, stone(0.44, 1116)),
        # Mantel über der linken Schulter: vorn eine Bahn, oben über den Fels, hinten beginnt die Kette
        Cube(9, -44, -17, 13, 26, 3, mantle(0)),
        Cube(36, -41, -7, 3, 20, 21, mantle(1)),                              # außen über den Fels
        Cube(12, -43, 14, 26, 22, 3, mantle(2)),                              # hinten über den Fels
        # Wirbel des Rückgrats unter dem Buckel
        Cube(-3.5, -34, 15, 7, 6, 4, stone(0.56, 1121)),
        Cube(-3, -25, 15, 6, 6, 4, stone(0.54, 1122)),
        Cube(-3, -16, 14, 6, 6, 4, stone(0.52, 1123)),
        Cube(-2.5, -7, 12, 5, 6, 4, stone(0.5, 1124)),
    ]
    # Schräg aufgesetzte Felsplatten machen die Schultern zu Brocken statt Kästen.
    rocks = [
        Part('rock_r', (-25, -46, 0), (10, 15, 16), cubes=[
            Cube(-11, -5, -10, 20, 6, 21, stone(0.58, 1112)),
            Cube(-11, -9, -1, 10, 5, 11, stone(0.62, 1117)),
            Cube(-12, -2, -13, 12, 8, 4, stone(0.52, 1118))]),
        Part('rock_l', (25, -42, 0), (-8, -12, -14), cubes=[
            Cube(-9, -5, -10, 19, 6, 20, stone(0.56, 1115)),
            Cube(0, -8, -2, 9, 4, 10, stone(0.6, 1119)),
            Cube(-4, -2, -13, 12, 7, 4, stone(0.5, 1120))]),
    ]
    return Part('chest', tuple(a - b for a, b in zip(P_CHEST, P_TORSO)), cubes=cubes, children=rocks + [
        head_part(), ring_part(), arm('r'), arm('l'), mantle_parts()])


def torso_part():
    struts = [
        Cube(-18.5, -22, -14, 5, 22, 5, stone(0.46, 1200)),
        Cube(13.5, -22, -14, 5, 22, 5, stone(0.46, 1201)),
        Cube(-19, -22, 9, 6, 22, 6, stone(0.42, 1202)),
        Cube(13, -22, 9, 6, 22, 6, stone(0.42, 1203)),
        Cube(-20, -4, -15.5, 8, 4, 8, stone(0.5, 1204)),                      # Sockel der Streben
        Cube(12, -4, -15.5, 8, 4, 8, stone(0.5, 1205)),
        Cube(-20.5, -4, 8, 8, 4, 8, stone(0.48, 1206)),
        Cube(12.5, -4, 8, 8, 4, 8, stone(0.48, 1207)),
    ]
    return Part('torso', tuple(a - b for a, b in zip(P_TORSO, P_HIPS)), cubes=struts, children=[glass_part(), chest_part()])


def loin(name, pivot, cube_args, paint, children=()):
    return Part(name, tuple(a - b for a, b in zip(pivot, P_HIPS)), cubes=[Cube(*cube_args, paint)], children=list(children))


def kronos():
    chain_ends = [
        Cube(-9, 2, -6, 3, 9, 6, iron(0.42, 1300, hole=('left', 'right'))),
        Cube(-10.5, 10, -4.5, 6, 8, 3, iron(0.4, 1301, hole=('front', 'back'))),
        Cube(6, 2, -6, 3, 9, 6, iron(0.42, 1302, hole=('left', 'right'))),
    ]
    loin_f = loin('loin_f', (0, -30, -12), (-11, 0, -3, 22, 24, 3), cloth(1), [])
    loin_f.cubes.extend(chain_ends)
    hips = Part('hips', P_HIPS, cubes=[
        Cube(-18, -8, -12, 36, 14, 24, stone(0.44, 1310)),                      # Becken
        Cube(-19, -9, -13, 38, 4, 26, stone(0.5, 1311)),                        # Gürtelwulst
        Cube(-6, -3, -14, 12, 7, 3, iron(0.5, 1312, rim=True)),                 # Schelle am Gürtel
    ], children=[
        torso_part(),
        leg('r'), leg('l'),
        loin_f,
        loin('loin_b', (0, -30, 12), (-12, 0, 0, 24, 27, 3), cloth(2)),
        loin('loin_r', (-18, -30, 0), (-3, 0, -9, 3, 19, 18), cloth(3)),
        loin('loin_l', (18, -30, 0), (0, 0, -9, 3, 19, 18), cloth(4)),
    ])
    return Model('kronos', 512, 512, [hips])


# ---------------------------------------------------------------------------------------------------------------------
# Posen
# ---------------------------------------------------------------------------------------------------------------------

# Knochen, die die Animation bewegt (Reihenfolge = Kotlin-Kanäle). Alle haben Grunddrehung 0.
BONES = ['hips', 'torso', 'chest', 'head', 'jaw', 'crown', 'beard', 'beard_1', 'ring', 'glass',
         'arm_r', 'fore_r', 'hand_r', 'fingers_r', 'harpe', 'chain_r', 'chain_r_1',
         'arm_l', 'fore_l', 'hand_l', 'fingers_l', 'chain_l', 'chain_l_1',
         'mantle_0', 'mantle_1', 'mantle_2', 'mantle_3',
         'thigh_r', 'shin_r', 'foot_r', 'thigh_l', 'shin_l', 'foot_l',
         'loin_f', 'loin_b', 'loin_r', 'loin_l']

from preview_model import apply as _apply, mat_mul as _mul, rot_zyx as _rot  # noqa: E402

_MODEL = None


def _model():
    global _MODEL
    if _MODEL is None:
        _MODEL = kronos()
    return _MODEL


def frames(pose):
    """Vorwärtskinematik wie preview.collect: Name -> (Drehung, Position) im Modellraum."""
    out = {}

    def walk(part, rot, pos):
        value = pose.get(part.name, ...)
        pivot = part.pivot
        if value is ... or value is None:
            r = tuple(math.radians(a) for a in part.rot)
        else:
            r = tuple(value[:3])
            if len(value) >= 6:
                pivot = (pivot[0] + value[3], pivot[1] + value[4], pivot[2] + value[5])
        o = _apply(rot, pivot)
        p = (pos[0] + o[0], pos[1] + o[1], pos[2] + o[2])
        m = _mul(rot, _rot(*r))
        out[part.name] = (m, p, part)
        for ch in part.children:
            walk(ch, m, p)
    for top in _model().parts:
        walk(top, [[1, 0, 0], [0, 1, 0], [0, 0, 1]], (0, 0, 0))
    return out


def corners(part):
    for c in part.cubes:
        for x in (c.x, c.x + c.w):
            for y in (c.y, c.y + c.h):
                for z in (c.z, c.z + c.d):
                    yield (x, y, z)


def world_corners(fr, name, recursive=False):
    m, p, part = fr[name]
    parts = list(part.walk()) if recursive else [part]
    pts = []
    for q in parts:
        mq, pq, _ = fr[q.name]
        for c in corners(q):
            v = _apply(mq, c)
            pts.append((v[0] + pq[0], v[1] + pq[1], v[2] + pq[2]))
    return pts


def world_pitch(fr, name):
    """Neigung der lokalen +y-Achse gegen die Senkrechte, in der y-z-Ebene (für Ketten/Mantel)."""
    m = fr[name][0]
    down = (m[0][1], m[1][1], m[2][1])
    return math.atan2(down[2], down[1])


def world_roll(fr, name):
    m = fr[name][0]
    down = (m[0][1], m[1][1], m[2][1])
    return math.atan2(-down[0], down[1])


LEG_PARTS = ['thigh_r', 'shin_r', 'foot_r', 'thigh_l', 'shin_l', 'foot_l']
IDENT = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
_PARENT = {}


def _parents():
    if not _PARENT:
        def walk(part, parent):
            _PARENT[part.name] = (parent, part)
            for ch in part.children:
                walk(ch, part.name)
        for top in _model().parts:
            walk(top, None)
    return _PARENT


def chain(pose, name):
    """Drehung und Lage eines einzelnen Knochens (schneller als frames())."""
    parents = _parents()
    path = []
    n = name
    while n:
        path.append(parents[n][1])
        n = parents[n][0]
    rot, pos = IDENT, (0.0, 0.0, 0.0)
    for part in reversed(path):
        value = pose.get(part.name, ...)
        pivot = part.pivot
        if value is ... or value is None:
            r = tuple(math.radians(a) for a in part.rot)
        else:
            r = tuple(value[:3])
            if len(value) >= 6:
                pivot = (pivot[0] + value[3], pivot[1] + value[4], pivot[2] + value[5])
        o = _apply(rot, pivot)
        pos = (pos[0] + o[0], pos[1] + o[1], pos[2] + o[2])
        rot = _mul(rot, _rot(*r))
    return rot, pos


def _t(m):
    return [[m[j][i] for j in range(3)] for i in range(3)]


def _norm(v):
    n = math.sqrt(sum(c * c for c in v)) or 1.0
    return tuple(c / n for c in v)


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def euler(m):
    """Zerlegt R = Rz(roll) · Ry(yaw) · Rx(pitch) (ModelPart-Reihenfolge) in (pitch, yaw, roll)."""
    sy = max(-1.0, min(1.0, -m[2][0]))
    yaw = math.asin(sy)
    if abs(sy) < 0.9999:
        pitch = math.atan2(m[2][1], m[2][2])
        roll = math.atan2(m[1][0], m[0][0])
    else:
        pitch = math.atan2(-m[1][2], m[1][1])
        roll = 0.0
    return (pitch, yaw, roll)


def frame_from(shaft, hook):
    """Weltdrehung der Harpe: lokales -y = Schaftrichtung (zur Klinge), lokales -z = Richtung der Sichel."""
    s = _norm(shaft)
    h = _norm(hook)
    h = _norm(tuple(hc - _dot(h, s) * sc for hc, sc in zip(h, s)))
    y = tuple(-c for c in s)
    z = tuple(-c for c in h)
    x = _cross(y, z)
    return [[x[i], y[i], z[i]] for i in range(3)]


def axis_angle(axis, ang):
    x, y, z = _norm(axis)
    c, s, t = math.cos(ang), math.sin(ang), 1 - math.cos(ang)
    return [[t * x * x + c, t * x * y - s * z, t * x * z + s * y],
            [t * x * y + s * z, t * y * y + c, t * y * z - s * x],
            [t * x * z - s * y, t * y * z + s * x, t * z * z + c]]


def rot_x(v, a):
    c, s = math.cos(a), math.sin(a)
    return (v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c)


PALM = (0, 6, 0)


def grip_hand(pose, s, shaft_world, twist=0.0):
    """Dreht die Hand so, dass die Griffachse der Faust (lokal z) entlang des Schafts liegt; liefert die Mitte der Faust
    im Unterarmraum."""
    rf, _ = chain(pose, f'fore_{s}')
    sf = _apply(_t(rf), _norm(shaft_world))
    if sf[2] < 0:
        sf = tuple(-c for c in sf)
    z = (0.0, 0.0, 1.0)
    ax = _cross(z, sf)
    ang = math.acos(max(-1.0, min(1.0, _dot(z, sf))))
    m = axis_angle(ax, ang) if math.hypot(*ax) > 1e-6 else IDENT
    if twist:
        m = _mul(m, _rot(0, 0, twist))
    pose[f'hand_{s}'] = euler(m) + (0.0, 0.0, 0.0)
    palm = _apply(m, PALM)
    return (palm[0], FOREARM + palm[1], palm[2])


def place_harpe(pose, shaft, hook):
    """Harpe in der rechten Faust mit Schaft- und Sichelrichtung in Weltkoordinaten."""
    palm = grip_hand(pose, 'r', shaft)
    rf, _ = chain(pose, 'fore_r')
    rel = _mul(_t(rf), frame_from(shaft, hook))
    off = tuple(p - g for p, g in zip(palm, GRIP))
    pose['harpe'] = euler(rel) + off


def harpe_point(pose, t):
    """Weltpunkt auf dem Schaft, t Einheiten vom Griff zur Klinge hin."""
    rh, ph = chain(pose, 'harpe')
    v = _apply(rh, (0, -t, 0))
    return tuple(a + b for a, b in zip(ph, v))


def palm_world(pose, s):
    rh, ph = chain(pose, f'hand_{s}')
    v = _apply(rh, PALM)
    return tuple(a + b for a, b in zip(ph, v))


def reach(pose, s, target, shaft):
    """Einfache IK: Schulter (Nicken, Rollen, Gieren) und Ellbogen so, dass die Faust den Zielpunkt greift."""
    names = [(f'arm_{s}', 0), (f'arm_{s}', 2), (f'fore_{s}', 0), (f'arm_{s}', 1)]
    start = {n: tuple(pose[n]) for n, _ in names}

    def cost():
        grip_hand(pose, s, shaft)
        p = palm_world(pose, s)
        d = sum((a - b) ** 2 for a, b in zip(p, target))
        reg = sum((pose[n][i] - start[n][i]) ** 2 for n, i in names) * 4.0
        fore = pose[f'fore_{s}'][0]
        bend = max(0.0, fore) ** 2 * 400 + max(0.0, -fore - 2.4) ** 2 * 400   # Ellbogen knickt nur nach vorn
        return d + reg + bend

    if shaft is None:
        def cost():  # noqa: F811  (ohne Griff: Hand bleibt, wie sie ist)
            p = palm_world(pose, s)
            d = sum((a - b) ** 2 for a, b in zip(p, target))
            reg = sum((pose[n][i] - start[n][i]) ** 2 for n, i in names) * 4.0
            fore = pose[f'fore_{s}'][0]
            return d + reg + max(0.0, fore) ** 2 * 400 + max(0.0, -fore - 2.4) ** 2 * 400
    best = cost()
    step = 0.25
    while step > 0.002:
        improved = False
        for n, i in names:
            for sign in (1, -1):
                v = list(pose[n])
                v[i] += sign * step
                old = pose[n]
                pose[n] = tuple(v)
                c = cost()
                if c < best - 1e-9:
                    best = c
                    improved = True
                else:
                    pose[n] = old
        if not improved:
            step *= 0.5
    if shaft is not None:
        grip_hand(pose, s, shaft)
    return math.dist(palm_world(pose, s), target)


def shoulder(pose, s):
    return chain(pose, f'arm_{s}')[1]


def dress(primary, ground=True, harpe=None, harpe_ground=None, grip_l=None, hand_r=None, hand_l=None):
    """Aus Hauptwinkeln eine vollständige Pose.

    harpe = (Schaftrichtung, Sichelrichtung) in Weltkoordinaten (+x = seine Linke, +y = unten, -z = vorn); die rechte
    Faust dreht sich mit. harpe_ground = Tiefe: die Harpe kippt um die Querachse, bis die Klinge so tief im Boden steckt
    (0 = Spitze schleift). grip_l = t: die linke Hand greift den Schaft t Einheiten über der rechten.
    Danach: Füße flach, Bodenkontakt, Schwerkraft für Mantel, Ketten, Bart und Lendentuch.
    """
    pose = {b: (0.0, 0.0, 0.0, 0.0, 0.0, 0.0) for b in BONES}
    for k, v in primary.items():
        pose[k] = tuple(v) + (0.0,) * (6 - len(v)) if v is not None else None

    def get(b):
        return pose[b] if pose[b] is not None else (0.0,) * 6

    def setp(b, i, val):
        if pose[b] is None:
            return
        v = list(get(b))
        v[i] = val
        pose[b] = tuple(v)

    # Füße flach auf den Boden, dann Bodenkontakt (tiefster Punkt der Beine auf y = 24).
    for s in 'rl':
        if f'foot_{s}' not in primary:
            rf, _ = chain(pose, f'foot_{s}')
            setp(f'foot_{s}', 0, get(f'foot_{s}')[0] - math.atan2(rf[2][1], rf[1][1]))
    if ground:
        fr = frames(pose)
        low = max(q[1] for name in LEG_PARTS for q in world_corners(fr, name))
        h = list(get('hips'))
        h[4] += 24 - low
        pose['hips'] = tuple(h)
    # Fäuste an Zielpunkte (relativ zur eigenen Schulter, Weltachsen)
    for s, rel in (('r', hand_r), ('l', hand_l)):
        if rel is not None:
            target = tuple(a + b for a, b in zip(shoulder(pose, s), rel[:3]))
            if len(rel) > 3:              # (dx, y, dz, 'y'): Höhe absolut (z. B. Faust auf dem Boden: y = 18)
                target = (target[0], rel[1], target[2])
            sh = harpe[0] if (s == 'r' and harpe is not None) else None
            err = reach(pose, s, target, sh)
            if err > 3:
                print(f'  Warnung: Faust {s} verfehlt ihr Ziel um {err:.1f}')
    # Harpe
    if harpe is not None and pose['harpe'] is not None:
        shaft, hook = harpe
        def lowest(a):
            place_harpe(pose, rot_x(shaft, a), rot_x(hook, a))
            fr2 = frames(pose)
            return max(q[1] for q in world_corners(fr2, 'blade', True))
        target = None
        if harpe_ground is not None:
            target = 24 + harpe_ground
        elif lowest(0.0) > 24.5:
            target = 24                       # nie durch den Boden: so wenig wie möglich anheben
        if target is not None:
            samples = [(-1.4 + 0.05 * i) for i in range(57)]
            vals = [lowest(a) - target for a in samples]
            pairs = [(samples[i], samples[i + 1], vals[i]) for i in range(56) if vals[i] * vals[i + 1] <= 0]
            if pairs:
                a0, a1, v0 = min(pairs, key=lambda p: min(abs(p[0]), abs(p[1])))
                for _ in range(22):
                    mid = (a0 + a1) / 2
                    vm = lowest(mid) - target
                    if v0 * vm <= 0:
                        a1 = mid
                    else:
                        a0, v0 = mid, vm
                shaft, hook = rot_x(shaft, (a0 + a1) / 2), rot_x(hook, (a0 + a1) / 2)
            else:
                print(f'  Warnung: Harpe erreicht den Boden nicht ({target})')
        place_harpe(pose, shaft, hook)
        if grip_l is not None:
            sw = _norm(shaft)
            keep = {n: pose[n] for n in ('arm_l', 'fore_l', 'hand_l')}
            tries = []
            for t in (grip_l, -grip_l):
                for n, v in keep.items():
                    pose[n] = v
                err = reach(pose, 'l', harpe_point(pose, t), sw)
                tries.append((err, t, {n: pose[n] for n in keep}))
            err, t, best = min(tries, key=lambda e: e[0])
            pose.update(best)
            if err > 3:
                print(f'  Warnung: linke Hand verfehlt den Schaft um {err:.1f}')
    # Bart hängt am Kiefer und pendelt mit der Schwerkraft.
    if 'beard' not in primary:
        setp('beard', 0, get('jaw')[0])
    if 'beard_1' not in primary:
        rb, _ = chain(pose, 'beard')
        setp('beard_1', 0, -math.atan2(rb[2][1], rb[1][1]) * 0.7)
    # Ketten an den Handgelenken hängen nach unten.
    for s in 'rl':
        if f'chain_{s}' not in primary:
            rf, _ = chain(pose, f'fore_{s}')
            setp(f'chain_{s}', 0, -math.atan2(rf[2][1], rf[1][1]))
            setp(f'chain_{s}', 2, -math.atan2(-rf[0][1], rf[1][1]) * 0.9)
        if f'chain_{s}_1' not in primary:
            rc, _ = chain(pose, f'chain_{s}')
            setp(f'chain_{s}_1', 0, -math.atan2(rc[2][1], rc[1][1]) * 0.8)
        # Liegt die Faust am Boden, legt sich die Kette flach statt durch den Boden zu hängen.
        for link in (f'chain_{s}', f'chain_{s}_1'):
            for _ in range(16):
                fr = frames(pose)
                if max(q[1] for q in world_corners(fr, f'chain_{s}', True)) <= 24.5:
                    break
                setp(link, 0, get(link)[0] - 0.12)
    # Mantel: liegt auf dem Rücken (mantle_0/1 folgen der Brust), unten hängt er frei.
    if 'mantle_0' not in primary:
        setp('mantle_0', 0, 0.06)
    if 'mantle_1' not in primary:
        rm, _ = chain(pose, 'mantle_0')
        setp('mantle_1', 0, max(0.04, -math.atan2(rm[2][1], rm[1][1]) * 0.5))
    for i in (2, 3):
        if f'mantle_{i}' not in primary:
            rm, _ = chain(pose, f'mantle_{i - 1}')
            setp(f'mantle_{i}', 0, -math.atan2(rm[2][1], rm[1][1]) * 0.75 + math.radians(2))
    # Lendentuch: vorne schiebt das vordere Bein, hinten das hintere; dazu Schwerkraft gegen die Hüfte.
    hp = get('hips')[0]
    tr, tl = get('thigh_r')[0], get('thigh_l')[0]
    if 'loin_f' not in primary:
        setp('loin_f', 0, min(0.0, min(tr, tl)) * 0.75 - hp * 0.8)
    if 'loin_b' not in primary:
        setp('loin_b', 0, max(0.0, max(tr, tl)) * 0.75 - hp * 0.8 + 0.06)
    if 'loin_r' not in primary:
        setp('loin_r', 2, max(0.0, -tr) * 0.25 + 0.05 - get('hips')[2])
    if 'loin_l' not in primary:
        setp('loin_l', 2, -max(0.0, -tl) * 0.25 - 0.05 - get('hips')[2])
    return pose


def P(**k):
    return k


def V(x, y, z):
    return (x, y, z)


# Hauptwinkel jeder Schlüsselpose (Radiant). Rechts = -x. Positive Gierung dreht nach seiner Rechten.
# Arme: Nicken negativ = nach vorn/oben; arm_r Rollen positiv = nach außen, arm_l negativ = nach außen.
# fingers_r Rollen negativ = Faust, fingers_l positiv = Faust. Harpe: (Schaft, Sichel) als Weltrichtungen.
GRIP_R, GRIP_L, OPEN_L = -1.1, 1.0, -0.1
FIST_L = 1.3
DOWN, UP, FWD, BACK, RIGHT, LEFT = V(0, 1, 0), V(0, -1, 0), V(0, 0, -1), V(0, 0, 1), V(-1, 0, 0), V(1, 0, 0)

KEYS = {
    # Ruhe: vornübergebeugt, die Harpe schleift mit der Spitze am Boden, die linke Faust hängt tief.
    'idle': (P(hips=(0.1, 0, 0), torso=(0.34, 0, 0), chest=(0.3, 0.05, 0.03), head=(-0.5, -0.05, 0, 0, 3, -2),
               ring=(-0.42, 0, 0),
               arm_r=(-0.82, 0, 0.08), fore_r=(-0.3, 0, 0), fingers_r=(0, 0, GRIP_R),
               arm_l=(-0.86, 0, -0.06), fore_l=(-0.12, 0, 0), fingers_l=(0, 0, FIST_L),
               thigh_r=(-0.32, 0, 0.04), shin_r=(0.38, 0, 0), thigh_l=(-0.12, 0, -0.04), shin_l=(0.22, 0, 0)),
             dict(harpe=(V(-0.15, 0.45, -1), V(0, 1, 0.3)), harpe_ground=0)),
    # Sichelhieb rechts → links: rechter Arm weit nach rechts-hinten, Harpe waagerecht, Brust 50° rechts, linker Fuß vor.
    'sweep_r_windup': (P(hips=(0.1, 0.3, 0), torso=(0.15, 0.25, 0), chest=(0.15, 0.4, -0.12), head=(-0.3, -0.65, 0),
                         ring=(-0.25, 0, 0),
                         arm_r=(0.5, 0, 1.3), fore_r=(-0.3, 0, 0), fingers_r=(0, 0, GRIP_R),
                         arm_l=(-1.0, 0, -0.25), fore_l=(-0.7, 0, 0), fingers_l=(0, 0, 0.3),
                         thigh_r=(0.3, 0, 0.14), shin_r=(0.35, 0, 0), thigh_l=(-0.8, 0, -0.1), shin_l=(0.55, 0, 0)),
                       dict(harpe=(V(-1, -0.05, 0.75), V(0.1, -1, -0.4)), hand_r=(-62, 12, 40))),
    'sweep_r_hit': (P(hips=(0.12, -0.3, 0), torso=(0.18, -0.25, 0), chest=(0.18, -0.45, 0.08), head=(-0.35, 0.5, 0),
                      ring=(-0.25, 0, 0),
                      arm_r=(-1.5, 0, -0.45), fore_r=(-0.1, 0, 0), fingers_r=(0, 0, GRIP_R),
                      arm_l=(0.35, 0, -0.5), fore_l=(-0.4, 0, 0), fingers_l=(0, 0, FIST_L),
                      thigh_r=(0.3, 0, 0.1), shin_r=(0.35, 0, 0), thigh_l=(-0.85, 0, -0.12), shin_l=(0.6, 0, 0)),
                    dict(harpe=(V(0.35, 0.1, -1), V(1, 0, 0.2)), hand_r=(25, 25, -70))),
    'sweep_r_follow': (P(hips=(0.2, -0.5, 0), torso=(0.24, -0.4, 0), chest=(0.24, -0.5, 0.12), head=(-0.35, 0.6, 0),
                         ring=(-0.25, 0, 0),
                         arm_r=(-1.25, 0, -1.0), fore_r=(-0.45, 0, 0), fingers_r=(0, 0, GRIP_R),
                         arm_l=(0.55, 0, -0.6), fore_l=(-0.5, 0, 0), fingers_l=(0, 0, FIST_L),
                         thigh_r=(0.35, 0, 0.1), shin_r=(0.5, 0, 0), thigh_l=(-0.9, 0, -0.12), shin_l=(0.8, 0, 0)),
                       dict(harpe=(V(1, 0.3, 0.1), V(0.1, 0, 1)), hand_r=(60, 30, -35))),
    # Sichelhieb links → rechts (Rückhand): Harpe beidhändig links über der Schulter, Brust nach links.
    'sweep_l_windup': (P(hips=(0.05, -0.35, 0), torso=(0.06, -0.3, 0), chest=(0.02, -0.5, 0.12), head=(-0.2, 0.7, 0),
                         ring=(-0.15, 0, 0),
                         arm_r=(-2.2, 0, -0.75), fore_r=(-0.8, 0, 0), fingers_r=(0, 0, GRIP_R),
                         arm_l=(-1.6, 0, -0.9), fore_l=(-1.1, 0, 0), fingers_l=(0, 0, GRIP_L),
                         thigh_r=(-0.7, 0, 0.12), shin_r=(0.5, 0, 0), thigh_l=(0.3, 0, -0.14), shin_l=(0.3, 0, 0)),
                       dict(harpe=(V(0.75, -0.55, 0.8), V(0, 0.3, -1)), grip_l=20, hand_r=(45, -42, 22))),
    'sweep_l_hit': (P(hips=(0.12, 0.35, 0), torso=(0.16, 0.3, 0), chest=(0.16, 0.5, -0.08), head=(-0.35, -0.6, 0),
                      ring=(-0.25, 0, 0),
                      arm_r=(-1.45, 0, 0.65), fore_r=(-0.15, 0, 0), fingers_r=(0, 0, GRIP_R),
                      arm_l=(-1.3, 0, 0.5), fore_l=(-0.5, 0, 0), fingers_l=(0, 0, GRIP_L),
                      thigh_r=(-0.8, 0, 0.12), shin_r=(0.55, 0, 0), thigh_l=(0.3, 0, -0.12), shin_l=(0.35, 0, 0)),
                    dict(harpe=(V(-0.45, 0.1, -1), V(-1, 0, -0.2)), grip_l=20, hand_r=(-35, 25, -62))),
    'sweep_l_follow': (P(hips=(0.2, 0.5, 0), torso=(0.22, 0.4, 0), chest=(0.22, 0.55, -0.12), head=(-0.35, -0.6, 0),
                         ring=(-0.25, 0, 0),
                         arm_r=(-1.1, 0, 1.1), fore_r=(-0.4, 0, 0), fingers_r=(0, 0, GRIP_R),
                         arm_l=(-1.0, 0, 0.75), fore_l=(-0.6, 0, 0), fingers_l=(0, 0, GRIP_L),
                         thigh_r=(-0.85, 0, 0.12), shin_r=(0.8, 0, 0), thigh_l=(0.35, 0, -0.12), shin_l=(0.5, 0, 0)),
                       dict(harpe=(V(-1, 0.3, 0.15), V(-0.1, 0, 1)), grip_l=20, hand_r=(-65, 30, -10))),
    # Spalthieb: Harpe beidhändig senkrecht über dem Kopf, er richtet sich zur vollen Größe auf.
    'cleave_windup': (P(hips=(-0.06, 0, 0), torso=(-0.1, 0, 0), chest=(-0.16, 0, 0), head=(-0.2, 0, 0, 0, -2, 0),
                        ring=(0.1, 0, 0),
                        arm_r=(-2.95, 0, -0.12), fore_r=(-0.3, 0, 0), fingers_r=(0, 0, GRIP_R),
                        arm_l=(-2.9, 0, 0.12), fore_l=(-0.4, 0, 0), fingers_l=(0, 0, GRIP_L),
                        thigh_r=(0.08, 0, 0.06), shin_r=(0.0, 0, 0), thigh_l=(-0.25, 0, -0.06), shin_l=(0.1, 0, 0)),
                      dict(harpe=(V(0, -1, 0.45), V(0, -0.3, -1)), grip_l=20, hand_r=(22, -74, 12))),
    'cleave_hit': (P(hips=(0.25, 0, 0), torso=(0.35, 0, 0), chest=(0.42, 0, 0), head=(-0.6, 0, 0),
                     ring=(-0.35, 0, 0),
                     arm_r=(-1.3, 0, -0.2), fore_r=(-0.2, 0, 0), fingers_r=(0, 0, GRIP_R),
                     arm_l=(-1.3, 0, 0.2), fore_l=(-0.2, 0, 0), fingers_l=(0, 0, GRIP_L),
                     thigh_r=(0.3, 0, 0.08), shin_r=(0.45, 0, 0), thigh_l=(-0.95, 0, -0.06), shin_l=(0.95, 0, 0)),
                   dict(harpe=(V(0, 0.4, -1), V(0, 1, 0.4)), harpe_ground=7, grip_l=20, hand_r=(20, 50, -55))),
    # Klinge steckt im Boden: er stützt sich darauf und zerrt.
    'stuck': (P(hips=(0.18, 0.12, 0), torso=(0.25, 0.05, 0), chest=(0.3, 0.05, 0), head=(-0.35, -0.15, 0),
                ring=(-0.25, 0, 0),
                arm_r=(-1.05, 0, -0.1), fore_r=(-0.4, 0, 0), fingers_r=(0, 0, GRIP_R),
                arm_l=(-1.0, 0, 0.18), fore_l=(-0.5, 0, 0), fingers_l=(0, 0, GRIP_L),
                thigh_r=(0.5, 0, 0.12), shin_r=(0.35, 0, 0), thigh_l=(-0.7, 0, -0.08), shin_l=(0.95, 0, 0)),
              dict(harpe=(V(0, 0.55, -1), V(0, 1, 0.45)), harpe_ground=7, grip_l=20, hand_r=(22, 55, -45))),
    # Äonenschlag: beide Fäuste über dem Kopf (die Harpe in der Rechten zeigt nach hinten), dann Einschlag, er kniet.
    'slam_windup': (P(hips=(-0.04, 0, 0), torso=(-0.15, 0, 0), chest=(-0.22, 0, 0), head=(-0.4, 0, 0),
                      ring=(0.15, 0, 0),
                      arm_r=(-2.7, 0, 0.5), fore_r=(-0.95, 0, 0), fingers_r=(0, 0, GRIP_R),
                      arm_l=(-2.7, 0, -0.5), fore_l=(-0.95, 0, 0), fingers_l=(0, 0, FIST_L),
                      thigh_r=(-0.25, 0, 0.2), shin_r=(0.4, 0, 0), thigh_l=(-0.25, 0, -0.2), shin_l=(0.4, 0, 0)),
                    dict(harpe=(V(-0.15, 0.35, 1), DOWN), hand_r=(10, -77, 10), hand_l=(-10, -77, 10))),
    'slam_hit': (P(hips=(0.3, 0, 0), torso=(0.42, 0, 0), chest=(0.45, 0, 0), head=(-0.5, 0, 0),
                   ring=(-0.3, 0, 0),
                   arm_r=(-0.8, 0, 0.22), fore_r=(-0.25, 0, 0), fingers_r=(0, 0, GRIP_R),
                   arm_l=(-0.8, 0, -0.22), fore_l=(-0.25, 0, 0), fingers_l=(0, 0, FIST_L),
                   thigh_r=(-1.5, 0, 0.12), shin_r=(1.6, 0, 0), thigh_l=(-0.15, 0, -0.1), shin_l=(1.55, 0, 0)),
                 dict(harpe=(V(-1, 0.05, 0.25), DOWN), hand_r=(14, 18, -34, 'y'), hand_l=(-14, 18, -34, 'y'))),
    # Diskuswerfer: eingedreht, Harpe hinter dem Rücken, dann Schleudern.
    'throw_windup': (P(hips=(0.15, 0.5, 0), torso=(0.15, 0.4, 0), chest=(0.15, 0.55, -0.12), head=(-0.35, -1.0, 0),
                       ring=(-0.2, 0, 0),
                       arm_r=(0.85, 0, 0.9), fore_r=(-0.3, 0, 0), fingers_r=(0, 0, GRIP_R),
                       arm_l=(-1.45, 0, 0.3), fore_l=(-0.15, 0, 0), fingers_l=(0, 0, OPEN_L),
                       thigh_r=(0.15, 0, 0.18), shin_r=(0.75, 0, 0), thigh_l=(-0.7, 0, -0.14), shin_l=(0.6, 0, 0)),
                     dict(harpe=(V(-0.35, 0.15, 1), RIGHT), hand_r=(-25, 45, 55))),
    'throw_release': (P(hips=(0.12, -0.45, 0), torso=(0.15, -0.35, 0), chest=(0.18, -0.55, 0.1), head=(-0.3, 0.45, 0),
                        ring=(-0.2, 0, 0),
                        arm_r=(-1.6, 0, -0.45), fore_r=(-0.05, 0, 0), fingers_r=(0, 0, -0.2),
                        arm_l=(0.35, 0, -0.6), fore_l=(-0.4, 0, 0), fingers_l=(0, 0, FIST_L),
                        thigh_r=(0.4, 0, 0.12), shin_r=(0.4, 0, 0), thigh_l=(-0.8, 0, -0.1), shin_l=(0.55, 0, 0)),
                      dict(harpe=(V(0.7, 0, -1), V(1, 0, 0.5)), hand_r=(30, 5, -72))),
    # Hand leer: die rechte Hand offen erhoben, wartet auf die Harpe.
    'empty': (P(hips=(0.05, -0.1, 0), torso=(0.12, 0, 0), chest=(0.12, -0.15, 0), head=(-0.35, 0.1, 0),
                ring=(-0.15, 0, 0),
                arm_r=(-2.35, 0, 0.35), fore_r=(-0.25, 0, 0), hand_r=(-0.3, 0, 0), fingers_r=(0, 0, 0.15), harpe=None,
                arm_l=(-0.45, 0, -0.1), fore_l=(-0.2, 0, 0), fingers_l=(0, 0, FIST_L),
                thigh_r=(-0.2, 0, 0.06), shin_r=(0.3, 0, 0), thigh_l=(-0.2, 0, -0.06), shin_l=(0.3, 0, 0)), {}),
    # Zeitschritt: erstarrt mitten im Schritt, ein Fuß gehoben, zusammengefaltet (Brust vor, Arme am Körper).
    'step': (P(hips=(0.15, 0, 0), torso=(0.3, 0, 0), chest=(0.4, 0, 0), head=(-0.45, 0, 0),
               ring=(-0.4, 0, 0),
               arm_r=(-0.3, 0, -0.12), fore_r=(-1.35, 0, 0), fingers_r=(0, 0, GRIP_R),
               arm_l=(-0.3, 0, 0.12), fore_l=(-1.45, 0, 0), fingers_l=(0, 0, FIST_L),
               thigh_r=(0.1, 0, 0.04), shin_r=(0.2, 0, 0), thigh_l=(-1.15, 0, -0.04), shin_l=(1.35, 0, 0)),
             dict(harpe=(V(0.15, -1, 0.35), FWD))),
    # Uhrzeiger: aufrecht, Arme waagerecht ausgebreitet wie Zeiger; die Harpe verlängert den rechten Zeiger.
    'hands': (P(hips=(0.0, 0, 0), torso=(0.0, 0, 0), chest=(-0.05, 0, 0), head=(-0.1, 0, 0),
                ring=(0.0, 0, 0),
                arm_r=(0.0, 0, 1.55), fore_r=(0.0, 0, 0), fingers_r=(0, 0, GRIP_R),
                arm_l=(0.0, 0, -1.55), fore_l=(0.0, 0, 0), fingers_l=(0, 0, OPEN_L),
                thigh_r=(0.0, 0, 0.1), shin_r=(0.0, 0, 0), thigh_l=(0.0, 0, -0.1), shin_l=(0.0, 0, 0)),
              dict(harpe=(RIGHT, DOWN))),
    # Stillstand: linke Hand flach nach vorn gestoßen („Halt“), dann schließt sich die Faust.
    'halt_raise': (P(hips=(0.06, -0.15, 0), torso=(0.1, -0.1, 0), chest=(0.06, -0.25, 0), head=(-0.25, 0.3, 0),
                     ring=(-0.1, 0, 0),
                     arm_r=(-0.4, 0, 0.3), fore_r=(-0.35, 0, 0), fingers_r=(0, 0, GRIP_R),
                     arm_l=(-1.6, 0, 0.05), fore_l=(-0.05, 0, 0), hand_l=(-1.2, 0, 0), fingers_l=(0, 0, OPEN_L),
                     thigh_r=(0.25, 0, 0.08), shin_r=(0.2, 0, 0), thigh_l=(-0.45, 0, -0.06), shin_l=(0.35, 0, 0)),
                   dict(harpe=(V(-0.3, 0.45, -1), V(0, 1, 0.3)), harpe_ground=0)),
    'halt_close': (P(hips=(0.1, -0.15, 0), torso=(0.12, -0.1, 0), chest=(0.15, -0.3, 0), head=(-0.3, 0.3, 0),
                     ring=(-0.15, 0, 0),
                     arm_r=(-0.4, 0, 0.3), fore_r=(-0.35, 0, 0), fingers_r=(0, 0, GRIP_R),
                     arm_l=(-1.35, 0, 0.05), fore_l=(-0.65, 0, 0), hand_l=(-0.2, 0, 0), fingers_l=(0, 0, FIST_L + 0.15),
                     thigh_r=(0.3, 0, 0.08), shin_r=(0.25, 0, 0), thigh_l=(-0.5, 0, -0.06), shin_l=(0.45, 0, 0)),
                   dict(harpe=(V(-0.3, 0.45, -1), V(0, 1, 0.3)), harpe_ground=0)),
    # Rückspulung: Harpe quer vor der Brust, beide Hände drehen sie wie einen Uhrschlüssel (rewind ↔ rewind_b).
    'rewind': (P(hips=(0.05, 0, 0), torso=(0.12, 0, 0), chest=(0.14, 0, 0), head=(-0.3, 0, 0),
                 ring=(-0.15, 0, 0),
                 arm_r=(-1.2, 0, -0.45), fore_r=(-0.9, 0, 0), fingers_r=(0, 0, GRIP_R),
                 arm_l=(-1.2, 0, 0.45), fore_l=(-0.9, 0, 0), fingers_l=(0, 0, GRIP_L),
                 thigh_r=(-0.15, 0, 0.1), shin_r=(0.25, 0, 0), thigh_l=(-0.15, 0, -0.1), shin_l=(0.25, 0, 0)),
               dict(harpe=(V(1, -0.4, 0), V(0, -1, -0.2)), grip_l=24, hand_r=(18, 22, -40))),
    'rewind_b': (P(hips=(0.05, 0, 0), torso=(0.12, 0, 0), chest=(0.14, 0, 0), head=(-0.3, 0, 0),
                   ring=(-0.15, 0, 0),
                   arm_r=(-1.2, 0, -0.45), fore_r=(-0.9, 0, 0), fingers_r=(0, 0, GRIP_R),
                   arm_l=(-1.2, 0, 0.45), fore_l=(-0.9, 0, 0), fingers_l=(0, 0, GRIP_L),
                   thigh_r=(-0.15, 0, 0.1), shin_r=(0.25, 0, 0), thigh_l=(-0.15, 0, -0.1), shin_l=(0.25, 0, 0)),
                 dict(harpe=(V(1, 0.35, 0), V(0, -1, -0.2)), grip_l=24, hand_r=(18, 14, -40))),
    # Verschlingen: tief herabgebeugt, Arme weit offen, Kiefer klappt ganz auf; dann schnappen Arme und Kiefer zu.
    'devour_inhale': (P(hips=(0.25, 0, 0), torso=(0.35, 0, 0), chest=(0.4, 0, 0), head=(-0.65, 0, 0), jaw=(1.05, 0, 0),
                        ring=(-0.5, 0, 0),
                        arm_r=(-1.0, 0, 1.25), fore_r=(-0.5, 0, 0), fingers_r=(0, 0, GRIP_R),
                        arm_l=(-1.0, 0, -1.25), fore_l=(-0.5, 0, 0), fingers_l=(0, 0, OPEN_L),
                        thigh_r=(-0.55, 0, 0.18), shin_r=(0.75, 0, 0), thigh_l=(-0.55, 0, -0.18), shin_l=(0.75, 0, 0)),
                      dict(harpe=(V(-1, 0.5, -0.4), DOWN))),
    'devour_bite': (P(hips=(0.3, 0, 0), torso=(0.4, 0, 0), chest=(0.45, 0, 0), head=(-0.35, 0, 0), jaw=(0.0, 0, 0),
                      ring=(-0.5, 0, 0),
                      arm_r=(-1.35, 0, -0.35), fore_r=(-0.6, 0, 0), fingers_r=(0, 0, GRIP_R),
                      arm_l=(-1.35, 0, 0.35), fore_l=(-0.6, 0, 0), fingers_l=(0, 0, FIST_L),
                      thigh_r=(-0.6, 0, 0.14), shin_r=(0.8, 0, 0), thigh_l=(-0.6, 0, -0.14), shin_l=(0.8, 0, 0)),
                    dict(harpe=(V(0.6, 0.3, -1), V(1, 0, 0)))),
    # Nach dem Biss (offen): tief vornübergesunken, der Kiefer hängt, die Arme baumeln.
    'devour_recover': (P(hips=(0.3, 0, 0), torso=(0.42, 0, 0), chest=(0.5, 0, 0.06), head=(-0.2, 0, 0.08), jaw=(0.4, 0, 0),
                         ring=(-0.55, 0, 0.15),
                         arm_r=(-0.7, 0, 0.12), fore_r=(-0.15, 0, 0), fingers_r=(0, 0, GRIP_R),
                         arm_l=(-0.75, 0, -0.1), fore_l=(-0.1, 0, 0), fingers_l=(0, 0, 0.4),
                         thigh_r=(-0.6, 0, 0.14), shin_r=(0.85, 0, 0), thigh_l=(-0.45, 0, -0.14), shin_l=(0.7, 0, 0)),
                       dict(harpe=(V(-0.2, 0.5, -1), DOWN), harpe_ground=0)),
    # Von Hades' Ketten gehalten: Arme nach hinten-unten gerissen, er bäumt sich auf.
    'chained': (P(hips=(-0.1, 0, 0), torso=(-0.2, 0, 0), chest=(-0.25, 0, 0), head=(-0.55, 0, 0), jaw=(0.45, 0, 0),
                  ring=(0.2, 0, 0.2),
                  arm_r=(0.75, 0, 0.45), fore_r=(-0.1, 0, 0), fingers_r=(0, 0, GRIP_R),
                  arm_l=(0.75, 0, -0.45), fore_l=(-0.1, 0, 0), fingers_l=(0, 0, OPEN_L),
                  thigh_r=(-0.2, 0, 0.12), shin_r=(0.35, 0, 0), thigh_l=(0.1, 0, -0.12), shin_l=(0.3, 0, 0)),
                dict(harpe=(V(-0.3, 0.5, 1), DOWN), harpe_ground=0)),
    # Taumeln: auf ein Knie, stützt sich auf die linke Faust, der Ring hängt schief.
    'stagger': (P(hips=(0.25, 0.15, 0.05), torso=(0.35, 0, 0), chest=(0.45, 0.1, 0.1), head=(-0.2, 0, 0.1), jaw=(0.3, 0, 0),
                  ring=(-0.2, 0.35, 0.6),
                  arm_r=(-0.3, 0, 0.35), fore_r=(-0.3, 0, 0), fingers_r=(0, 0, -0.3),
                  arm_l=(-0.75, 0, -0.1), fore_l=(-0.15, 0, 0), fingers_l=(0, 0, FIST_L),
                  thigh_r=(-1.25, 0, 0.12), shin_r=(1.45, 0, 0), thigh_l=(0.25, 0, -0.08), shin_l=(1.45, 0, 0)),
                dict(harpe=(V(-0.6, 0.4, -0.6), V(0, 1, 0)), harpe_ground=0, hand_l=(2, 18, -26, 'y'))),
    # Gebrüll: Kopf in den Nacken, Kiefer weit, Arme gespreizt.
    'roar': (P(hips=(-0.05, 0, 0), torso=(-0.15, 0, 0), chest=(-0.2, 0, 0), head=(-0.75, 0, 0), jaw=(1.0, 0, 0),
               ring=(0.15, 0, 0),
               arm_r=(-0.55, 0, 1.0), fore_r=(-0.5, 0, 0), fingers_r=(0, 0, GRIP_R),
               arm_l=(-0.55, 0, -1.0), fore_l=(-0.5, 0, 0), fingers_l=(0, 0, 0.3),
               thigh_r=(-0.2, 0, 0.14), shin_r=(0.3, 0, 0), thigh_l=(-0.2, 0, -0.14), shin_l=(0.3, 0, 0)),
             dict(harpe=(V(-0.6, -0.8, 0.1), FWD))),
    # Neutrale Zauberhaltung: linke Hand offen vor der Brust, Harpe aufrecht.
    'cast': (P(hips=(0.05, 0, 0), torso=(0.1, 0, 0), chest=(0.05, 0, 0), head=(-0.3, 0, 0),
               ring=(0.0, 0, 0),
               arm_r=(-0.6, 0, 0.35), fore_r=(-0.9, 0, 0), fingers_r=(0, 0, GRIP_R),
               arm_l=(-1.25, 0, -0.6), fore_l=(-0.95, 0, 0), hand_l=(-0.5, 0, 0), fingers_l=(0, 0, OPEN_L),
               thigh_r=(-0.12, 0, 0.1), shin_r=(0.2, 0, 0), thigh_l=(-0.12, 0, -0.1), shin_l=(0.2, 0, 0)),
             dict(harpe=(V(0, -1, 0.15), FWD))),
    # Drohhaltung: Harpe auf die Schulter gelegt, Kopf gesenkt.
    'windup': (P(hips=(0.1, 0.15, 0), torso=(0.2, 0.1, 0), chest=(0.2, 0.2, 0), head=(-0.3, -0.25, 0),
                 ring=(-0.2, 0, 0),
                 arm_r=(-1.6, 0, 0.45), fore_r=(-1.5, 0, 0), fingers_r=(0, 0, GRIP_R),
                 arm_l=(-0.4, 0, -0.35), fore_l=(-0.45, 0, 0), fingers_l=(0, 0, FIST_L),
                 thigh_r=(0.15, 0, 0.12), shin_r=(0.25, 0, 0), thigh_l=(-0.5, 0, -0.1), shin_l=(0.4, 0, 0)),
               dict(harpe=(V(0.1, -0.45, 1), DOWN))),
    # Cutscene: Sturz durch die Kuppel, Glieder nach oben gerissen.
    'descend': (P(hips=(-0.1, 0, 0), torso=(-0.15, 0, 0), chest=(-0.1, 0, 0), head=(0.1, 0, 0), jaw=(0.35, 0, 0),
                  ring=(0.3, 0, 0.3),
                  arm_r=(-2.3, 0, 1.1), fore_r=(-0.3, 0, 0), fingers_r=(0, 0, GRIP_R),
                  arm_l=(-2.3, 0, -1.1), fore_l=(-0.3, 0, 0), fingers_l=(0, 0, OPEN_L),
                  thigh_r=(-0.5, 0, 0.12), shin_r=(0.9, 0, 0), thigh_l=(0.2, 0, -0.1), shin_l=(0.4, 0, 0)),
                dict(ground=False, harpe=(V(-0.3, -1, 0.3), FWD))),
    # Cutscene: gefesselt auf beiden Knien, Arme hinter dem Rücken (ohne Harpe).
    'bound': (P(hips=(0.1, 0, 0), torso=(0.3, 0, 0), chest=(0.35, 0, 0), head=(0.1, 0, 0),
                ring=(-0.6, 0.2, 0.4),
                arm_r=(0.6, 0, 0.3), fore_r=(-0.3, 0, 0), fingers_r=(0, 0, GRIP_R), harpe=None,
                arm_l=(0.6, 0, -0.3), fore_l=(-0.3, 0, 0), fingers_l=(0, 0, FIST_L),
                thigh_r=(-0.25, 0, 0.1), shin_r=(1.6, 0, 0), thigh_l=(-0.25, 0, -0.1), shin_l=(1.6, 0, 0)), {}),
}


def build_poses():
    out = {}
    for name, (primary, opts) in KEYS.items():
        out[name] = dress(primary, **opts)
    return out


POSES = build_poses()


# ---------------------------------------------------------------------------------------------------------------------
# Kotlin-Block für KronosModel.kt
# ---------------------------------------------------------------------------------------------------------------------

def bone_paths():
    paths = {}

    def walk(part, prefix):
        path = f'{prefix}/{part.name}' if prefix else part.name
        paths[part.name] = path
        for ch in part.children:
            walk(ch, path)
    for p in _model().parts:
        walk(p, '')
    return {b: paths[b] for b in BONES}


def kotlin_block():
    paths = bone_paths()
    lines = ['    // <keys> erzeugt von tools/hha_assets/kronos_model.py --kotlin; nicht von Hand ändern', '    object Keys {']
    lines.append('        /** Knochenpfade in Kanalreihenfolge (je 6 Werte: pitch, yaw, roll, dx, dy, dz). */')
    lines.append('        val BONES = arrayOf(' + ', '.join(f'"{paths[b]}"' for b in BONES) + ')')
    for i, b in enumerate(BONES):
        lines.append(f'        const val {b.upper()} = {i}')
    lines.append(f'        const val COUNT = {len(BONES)}')
    lines.append('        /** Klingenglieder der Harpe: Länge und Breite (für die Schneiden-Anker). */')
    lines.append('        val BLADE_LEN = floatArrayOf(' + ', '.join(f'{l}f' for l, _, _ in BLADE) + ')')
    lines.append('        val BLADE_W = floatArrayOf(' + ', '.join(f'{w}f' for _, w, _ in BLADE) + ')')
    lines.append('        /** Schlüsselposen: je Knochen 6 Werte; die Liste nennt Knochen, die in der Pose ausgeblendet sind. */')
    for name, pose in POSES.items():
        vals = []
        hidden = []
        for i, b in enumerate(BONES):
            v = pose.get(b)
            if v is None:
                hidden.append(i)
                v = (0.0,) * 6
            vals.extend(v)
        data = ','.join(('%.4f' % x).rstrip('0').rstrip('.') if x else '0' for x in vals)
        const = name.upper()
        lines.append(f'        val {const} = key("{data}", intArrayOf({", ".join(map(str, hidden))}))')
    lines.append('    }')
    lines.append('    // </keys>')
    return '\n'.join(lines)


def write_kotlin():
    path = os.path.join(HERE, '..', '..', 'src', 'main', 'kotlin', 'dev', 'henny', 'hha', 'client', 'model', 'KronosModel.kt')
    with open(path, encoding='utf8') as f:
        text = f.read()
    new = re.sub(r'    // <keys>.*?// </keys>', lambda m: kotlin_block(), text, flags=re.S)
    if new == text and '// <keys>' not in text:
        raise SystemExit('Marker // <keys> fehlt in KronosModel.kt')
    with open(path, 'w', encoding='utf8', newline='\n') as f:
        f.write(new)
    print(f'KronosModel.kt: {len(POSES)} Schlüsselposen geschrieben')


if __name__ == '__main__':
    if '--kotlin' in sys.argv:
        write_kotlin()

# Für die Größenprüfung in preview.py: Diese Knochen (samt Kindern) zählen nicht zur Körperhöhe (Fuß bis Scheitel),
# weil sie Zubehör sind, das laut Brief darüber hinausragen darf (Hut, Waffe, Busch, Ast, Stachel, Ring).
HEIGHT_EXCLUDE = ('ring', 'harpe')
