"""Kampe, die Kerkermeisterin der Titanen: Frau, Schlangenleib und Skorpionschwanz (Brief tmp/overhaul/models/kampe.md).

Vorn ist -z, der Leib liegt bei y = 0 (im Knochen `root`, der bei y = 24 sitzt) auf dem Boden. Knochen:

    root (Knoten am Boden, wo der Leib sich aufrichtet)
    ├─ tail_0 ── tail_1 ── tail_2 ── hips                     aufsteigender Teil, trägt den Oberkörper
    │                                 └─ chest
    │                                    ├─ head ── jaw, hair_0 … hair_5 (je mit Kind hair_N_1)
    │                                    ├─ arm_r ── fore_r ── claw_r
    │                                    ├─ arm_l ── fore_l ── claw_l
    │                                    └─ wing_r, wing_l (je mit Fingern wing_r_0 … wing_r_2)
    └─ tail_3 ── tail_4 ── … ── tail_9 ── sting                liegender Teil + Skorpionschwanz

Jedes Glied dreht am Ende des vorigen. Die aufsteigenden Glieder wachsen entlang -y, die liegenden entlang +z (nach
hinten); positive Neigung (pitch) kippt ein aufsteigendes Glied nach vorn und hebt ein liegendes an.

Abweichung vom Brief (begründet im Bericht): Die Glieder 5–9 sind länger (28/28/28/26/22 statt 22/22/16/16/16).
Mit den Brieflängen ist der Weg vom Boden bis zum Stachel 136 Einheiten lang; für einen Bogen, der bei 130 über dem
Kopf steht und nach vorn umschlägt, braucht er rund 175. Die Gesamtlänge kommt so auf ~265 (Plan 6.3: ~260).
Dafür liegen nur die Glieder 3–4 als Bogen am Boden; 5–6 steigen als zweite Säule hinter ihrer linken Schulter auf.

Die Schlüsselposen stehen in POSES (Grad im Quelltext, Radiant im Dict). `variants()` schreibt dieselben Werte beim
Erzeugen in den markierten Block von client/model/KampeModel.kt, damit Vorschau und Spiel nie auseinanderlaufen.
"""
import math
import os
import re

from bosskit import Cube
from modelkit import Model, Part, hexc, mix, ramp, rgb, speckle

HERE = os.path.dirname(os.path.abspath(__file__))
KOTLIN_MODEL = os.path.join(HERE, '..', '..', 'src', 'main', 'kotlin', 'dev', 'henny', 'hha', 'client', 'model', 'KampeModel.kt')

# ---------------------------------------------------------------------------------------------------------------------
# Materialien (Brief: Panzerschuppen dunkelviolett, Knochenbleich, Bronze matt; Leuchtfarbe Blutrot)
# ---------------------------------------------------------------------------------------------------------------------

SCALES = rgb('#0e0612', '#2a1638', '#4e2c66', '#8058a0')
PALE = rgb('#4a4038', '#7e7266', '#b0a292', '#d4c9b9', '#f0e8da')
BRONZE = rgb('#1c1008', '#3c2612', '#62421e', '#8a6634', '#ad8a52')
VENOM = rgb('#3a0308', '#7a0a16', '#c01826', '#ff3c46', '#ff9a9a')
BLOOD = (255, 60, 70)

LIGHT = {'top': 0.10, 'bottom': -0.16, 'front': 0.0, 'back': -0.06, 'left': -0.03, 'right': -0.03}


def grain(x, y, seed, amount=0.03):
    """Feines Korn, höchstens ± amount (Stil: ± 4 %)."""
    return (speckle(x, y, seed) - 0.5) * 2 * amount


def lit(color, strength):
    return (color, (BLOOD[0], BLOOD[1], BLOOD[2], strength))


# ---------------------------------------------------------------------------------------------------------------------
# Haut des Leibs: Schuppen oben und an den Flanken, Bauchschienen unten. Ein Maler kennt die Achse seines Glieds und
# rechnet jede Fläche in (entlang, rundum) um, damit Schuppen und Schienen auf allen Gliedern gleich laufen.
# ---------------------------------------------------------------------------------------------------------------------

def _frame(axis, face, i, j, w, h):
    """-> (Rolle, entlang in Texeln vom kopfseitigen Ende, quer in Texeln, rundum 0 = Rücken … 1 = Bauch)."""
    if axis == 'z':                      # liegend, Kopfende bei z = 0, Bauch unten
        if face == 'top':
            return 'dorsal', j, i, 0.0
        if face == 'bottom':
            return 'belly', j, i, 1.0
        if face == 'right':
            return 'flank', w - 1 - i, j, j / max(h - 1, 1)
        if face == 'left':
            return 'flank', i, j, j / max(h - 1, 1)
        return 'cap', i, j, 0.5
    # aufsteigend: Kopfende oben (j = 0), Bauch vorn
    if face == 'back':
        return 'dorsal', j, i, 0.0
    if face == 'front':
        return 'belly', j, i, 1.0
    if face == 'right':
        return 'flank', j, i, i / max(w - 1, 1)
    if face == 'left':
        return 'flank', j, i, 1.0 - i / max(w - 1, 1)
    return 'cap', i, j, 0.5


def scale_shade(a, x, cell=5):
    """Überlappende Schuppe: dunkel unter dem Rand der vorigen, heller Glanzgrat zur freien, runden Kante hin."""
    row = a // cell
    u = (a % cell) / (cell - 1)
    v = ((x + (row % 2) * (cell // 2)) % cell) / (cell - 1)
    edge = u + 0.5 * (2 * v - 1) ** 2
    if edge > 1.05:
        return -0.15
    return -0.12 + 0.24 * min(edge, 1.0) + 0.05 * (1 - abs(2 * v - 1))


def hide(axis, seed, cell=5, base=0.42, chamfer=False):
    """Schlangenhaut: Schuppen (Rücken dunkler, Flanke heller), bleicher geschienter Bauch.

    chamfer: Maler des breiten Quaders eines Achtecks; dessen Bauchseite ist nur die untere Fase (dunkel)."""
    def paint(face, i, j, w, h):
        role, a, x, c = _frame(axis, face, i, j, w, h)
        g = grain(i, j, seed)
        if role == 'cap':
            return ramp(SCALES, 0.24 + g)
        if role == 'belly' and chamfer:
            role, c = 'flank', 0.8
        across = w if face in ('top', 'bottom', 'front', 'back') else h
        if role == 'belly' and abs(x - (across - 1) / 2) > across * 0.36:
            role, c = 'flank', 0.84
        if role == 'belly':
            r = a % 4
            v = 0.5 + (0.08 if r == 1 else -0.14 if r == 0 else -0.02 * (r - 1))
            return ramp(PALE, v + LIGHT[face] * 0.5 + g)
        # Rücken (c = 0) dunkel, obere Flanke am hellsten, untere Flanke wieder dunkler (Bauchschatten)
        body = base - 0.06 + 0.16 * math.sin(min(c, 0.86) / 0.86 * math.pi * 0.85)
        return ramp(SCALES, body + scale_shade(a, x, cell) + LIGHT[face] + g)
    return paint


def ring(axis, seed):
    """Bleicher Ring (alle zwei Glieder) mit dunklen Fugen an beiden Kanten."""
    def paint(face, i, j, w, h):
        role, a, x, c = _frame(axis, face, i, j, w, h)
        if role == 'cap':
            return ramp(PALE, 0.36)
        n = h if axis == 'y' else (h if face in ('top', 'bottom') else w)
        edge = a == 0 or a == n - 1
        v = 0.66 + LIGHT[face] - (0.26 if edge else 0.0) - 0.1 * c + grain(i, j, seed)
        return ramp(PALE, v)
    return paint


def collar(axis, seed):
    """Dunkler Wulst über einem Gelenk: glatte Platte, heller Grat an der kopfseitigen Kante."""
    def paint(face, i, j, w, h):
        role, a, x, c = _frame(axis, face, i, j, w, h)
        if role == 'cap':
            return ramp(SCALES, 0.2)
        v = 0.5 - 0.12 * a + LIGHT[face] - 0.12 * c + grain(i, j, seed)
        return ramp(SCALES, v)
    return paint


def carapace(axis, seed, glow_stripe=False):
    """Skorpionpanzer: glatte, harte Platte mit hellen Kanten, dunkle Fuge am hinteren Ende."""
    def paint(face, i, j, w, h):
        role, a, x, c = _frame(axis, face, i, j, w, h)
        n = h if (axis == 'z' and face in ('top', 'bottom')) or (axis == 'y' and face != 'top' and face != 'bottom') else w
        if role == 'cap':
            return ramp(SCALES, 0.22)
        t = a / max(n - 1, 1)
        v = 0.5 + 0.12 * (1 - t) + LIGHT[face] + grain(i, j, seed, 0.02)
        across = w if face in ('top', 'bottom', 'front', 'back') else h
        if x == 0 or x == across - 1:
            v += 0.16
        if a == n - 1:
            v -= 0.24
        if role == 'belly':
            v -= 0.16
        if glow_stripe and role == 'dorsal' and abs(x - (across - 1) / 2) < 1 and 0 < a < n - 1:
            return lit(ramp(VENOM, 0.6 + 0.2 * (1 - t)), 200)
        return ramp(SCALES, v)
    return paint


def crest(axis, seed, glow_stripe=False):
    """Niedrige Rückenplatte; auf den Skorpiongliedern mit leuchtendem Mittelstreifen."""
    def paint(face, i, j, w, h):
        role, a, x, c = _frame(axis, face, i, j, w, h)
        if glow_stripe and role == 'dorsal' and 0 < a:
            return lit(ramp(VENOM, 0.7), 210)
        n = h if (axis == 'z' and face in ('top', 'bottom')) or (axis == 'y' and face not in ('top', 'bottom')) else w
        v = 0.56 - 0.2 * a / max(n - 1, 1) + LIGHT[face] + grain(i, j, seed)
        if role == 'dorsal':
            v += 0.12
        return ramp(SCALES, v)
    return paint


def smooth(material, base, seed, grad=0.12):
    def paint(face, i, j, w, h):
        return ramp(material, base + LIGHT[face] + (0.5 - j / max(h - 1, 1)) * grad + grain(i, j, seed))
    return paint


def bronze(seed, base=0.5):
    """Matte, warme Bronze mit hellen Kanten. Leuchtet nie."""
    def paint(face, i, j, w, h):
        v = base + LIGHT[face] * 1.2 + (0.5 - j / max(h - 1, 1)) * 0.16 + grain(i, j, seed)
        if i == 0 or j == 0 or i == w - 1 or j == h - 1:
            v += 0.12
        return ramp(BRONZE, v)
    return paint


def scale_plates(seed, cell=5, base=0.46):
    """Schuppenpanzer an Brust, Schultern und Rücken: große Schuppen, Freikante nach unten."""
    def paint(face, i, j, w, h):
        if face in ('top', 'bottom'):
            return ramp(SCALES, base - 0.08 + LIGHT[face] + grain(i, j, seed))
        v = base + scale_shade(j, i, cell) + LIGHT[face] + (0.5 - j / max(h - 1, 1)) * 0.1 + grain(i, j, seed)
        if j == h - 1:
            v -= 0.12
        return ramp(SCALES, v)
    return paint


def torso_skin(seed, center=0.5, base=0.44):
    """Rumpf: dunkle Schuppenflanken, vorn ein bleicher, geschienter Mittelstreifen (setzt den Schlangenbauch fort)."""
    def paint(face, i, j, w, h):
        g = grain(i, j, seed)
        if face == 'front' and abs(i - (w - 1) / 2) <= w * center / 2:
            r = j % 3
            v = 0.58 + (0.08 if r == 1 else -0.14 if r == 0 else 0.0)
            return ramp(PALE, v + g)
        if face in ('top', 'bottom'):
            return ramp(SCALES, base - 0.1 + LIGHT[face] + g)
        return ramp(SCALES, base + scale_shade(j, i, 4) + LIGHT[face] + g)
    return paint


def splinted(seed):
    """Bleicher, geschienter Bauch (Taille)."""
    def paint(face, i, j, w, h):
        if face in ('front', 'left', 'right'):
            r = j % 3
            v = 0.6 + (0.1 if r == 1 else -0.18 if r == 0 else 0.0)
            return ramp(PALE, v + LIGHT[face] + grain(i, j, seed))
        return ramp(PALE, 0.5 + LIGHT[face] + grain(i, j, seed))
    return paint


def membrane(seed, reach, scallop=0.22):
    """Flughaut in der yz-Ebene unter einem Finger: Dreieck mit gewellter Hinterkante, dunkle Adern."""
    def paint(face, i, j, w, h):
        if face not in ('left', 'right'):
            return None
        a = (w - 1 - i) if face == 'right' else i
        t = a / max(w - 1, 1)
        depth = min(h, reach * (a + 1)) * (1 - scallop * math.sin(math.pi * t))
        if j >= depth:
            return None
        v = 0.22 + 0.1 * (1 - j / max(depth, 1)) + grain(i, j, seed)
        if j >= depth - 1:
            v += 0.12
        if (a + j) % 7 == 0:
            v -= 0.08
        return ramp(SCALES, v)
    return paint


def face_paint(face, i, j, w, h):
    """Schädel 11 x 9 x 11: vorn das Gesicht, oben, hinten und an den hinteren Seiten dunkles Schuppenhaar."""
    skin = ramp(PALE, 0.7 + LIGHT[face] + (0.5 - j / max(h - 1, 1)) * 0.12 + grain(i, j, 77))
    hair = ramp(SCALES, 0.4 + LIGHT[face] + scale_shade(j, i, 3) * 0.6 + grain(i, j, 78))
    if face in ('top', 'back'):
        return hair
    if face in ('left', 'right'):
        front = i if face == 'left' else w - 1 - i           # 0 = vorn
        if front >= 6 or (front >= 3 and j <= 4) or j <= 1:
            return hair
        if j in (5, 6) and front <= 2:
            return ramp(PALE, 0.6)                             # Wangenschatten seitlich
        return skin
    if face != 'front':
        return skin
    c = (w - 1) / 2
    dx = abs(i - c)
    if j == 0 or (j == 1 and dx >= 1):
        return hair                                            # Haaransatz mit Witwenspitze
    if j == 3 and 1 <= dx <= 4:
        return ramp(SCALES, 0.46)                              # dunkler Lidschatten
    if (j == 4 and 1.5 <= dx <= 3.5) or (j == 3 and 4 < dx <= 5):
        return lit(ramp(VENOM, 0.84), 255)                     # Schlitzaugen, außen hochgezogen
    if j == 4 and (dx > 3.5 or dx < 1.5):
        return ramp(PALE, 0.5)
    if j == 5 and 1 <= dx <= 4:
        return ramp(PALE, 0.56)                                # Augenringe
    if j in (6, 7) and dx >= 4:
        return ramp(PALE, 0.52)                                # hohle Wangen unter den Wangenknochen
    if j == 8 and dx < 2:
        return ramp(SCALES, 0.42)                              # schmale dunkle Lippen
    return skin


def lion_face(face, i, j, w, h):
    base = bronze(501, 0.56)(face, i, j, w, h)
    if face != 'front':
        return base
    c = (w - 1) / 2
    if j == 2 and 1 <= abs(i - c) <= 2:
        return ramp(BRONZE, 0.08)                          # Augenhöhlen (dunkel, ohne Leuchten)
    if j >= h - 2 and abs(i - c) <= 2:
        return ramp(BRONZE, 0.18 if (i + j) % 2 else 0.3)  # Maul
    return base


def side_face(seed, side):
    """Seitliche Maske (Eber/Wolf): Augenhöhle auf der Außenseite."""
    out_face = 'left' if side > 0 else 'right'
    paint_b = bronze(seed, 0.5)

    def paint(face, i, j, w, h):
        if face == out_face and j == 2 and abs(i - (w - 1) / 2) >= 2:
            return ramp(BRONZE, 0.08)
        return paint_b(face, i, j, w, h)
    return paint


def venom_bulb(face, i, j, w, h):
    """Giftblase: dunkelrote Adern über glühendem Grund."""
    # Entlang der Blase (zum Dorn hin) heller; dunkle Adern laufen längs.
    if face in ('top', 'bottom'):
        t = j / max(h - 1, 1)
    elif face in ('left', 'right'):
        t = (i if face == 'left' else w - 1 - i) / max(w - 1, 1)
    else:
        t = 1.0 if face == 'back' else 0.0
    across = i if face in ('top', 'bottom', 'front', 'back') else j
    if across % 3 == 1 and 0.1 < t < 0.9:
        return lit(ramp(VENOM, 0.3 + 0.16 * t), 70)
    return lit(ramp(VENOM, 0.42 + 0.26 * t + LIGHT[face] * 0.4), 110 + int(90 * t))


def barb(face, i, j, w, h):
    return lit(ramp(VENOM, 0.86 + LIGHT[face] * 0.3), 255)


def viper_eye(face, i, j, w, h):
    """Kopf einer Haar-Viper (entlang +z): Augen als je 1 Texel an den Seiten nahe der Schnauze."""
    if face in ('left', 'right') and j == 0:
        a = i if face == 'left' else w - 1 - i
        if a == w - 2:
            return lit(ramp(VENOM, 0.85), 255)
    if face == 'bottom':
        return ramp(PALE, 0.5)
    return ramp(SCALES, 0.48 + LIGHT[face] + grain(i, j, 91))


def viper_skin(seed):
    """Haar-Viper: helleres Violett mit Glanz auf dem Rücken, bleiche Kehle."""
    def paint(face, i, j, w, h):
        if face == 'bottom':
            return ramp(PALE, 0.46 + grain(i, j, seed))
        v = 0.56 + LIGHT[face] * 1.4 + grain(i, j, seed)
        if face == 'top' and i == w // 2:
            v += 0.14
        return ramp(SCALES, v)
    return paint


def claw_paint(face, i, j, w, h):
    return ramp(SCALES, 0.36 + 0.3 * (j / max(h - 1, 1)) + LIGHT[face] + grain(i, j, 61))


# ---------------------------------------------------------------------------------------------------------------------
# Formen
# ---------------------------------------------------------------------------------------------------------------------

def _even(v):
    return max(2, 2 * round(v / 2))


def octo(axis, a0, a1, t, paint, k=0.6, off=(0.0, 0.0)):
    """Achteckiger Querschnitt aus zwei überlappenden Quadern entlang der Achse.

    axis 'z': von z = a0 bis a1, Querschnitt in x/y um (off). axis 'y': von y = -a1 bis -a0 (aufwärts), Quer x/z.
    """
    s = _even(t * k)
    n = a1 - a0
    ox, oy = off
    pa, pb = paint if isinstance(paint, tuple) else (paint, paint)
    if axis == 'z':
        return [Cube(ox - t / 2, oy - s / 2, a0, t, s, n, pa), Cube(ox - s / 2, oy - t / 2, a0, s, t, n, pb)]
    return [Cube(ox - t / 2, -a1, oy - s / 2, t, n, s, pa), Cube(ox - s / 2, -a1, oy - t / 2, s, n, t, pb)]


def body_segment(axis, length, t0, t1, seed, pale_ring, scorpion=False, joint=True):
    """Ein Glied: Gelenkwulst (oder bleicher Ring), zwei Abschnitte mit Verjüngung, Rückenplatten."""
    cubes = []
    tm = _even((t0 + t1) / 2)
    if scorpion:
        skin = carapace(axis, seed)
        cubes += octo(axis, -1, 4, t0 + 3, carapace(axis, seed + 1, glow_stripe=False), k=0.66)     # Kragen
        cubes += octo(axis, 3, length * 0.62, t0, skin, k=0.66)
        cubes += octo(axis, length * 0.55, length + 1, tm, skin, k=0.66)
    else:
        skin = (hide(axis, seed, chamfer=True), hide(axis, seed))
        band = ring(axis, seed + 1) if pale_ring else collar(axis, seed + 1)
        if joint:
            cubes += octo(axis, -2, 2, t0 + 2, band, k=0.64)
        cubes += octo(axis, 1, length * 0.6, t0, skin)
        cubes += octo(axis, length * 0.5, length + 1, tm, skin)
    # Rückenkamm: auf dem Leib zwei niedrige Platten, auf dem Skorpion ein Kiel mit Leuchtstreifen.
    if axis == 'z':
        top = -t0 / 2
        if scorpion:
            cubes.append(Cube(-1.5, top - 2, 4, 3, 3, length - 6, crest(axis, seed + 2, glow_stripe=True)))
        else:
            for z0 in (length * 0.18, length * 0.58):
                cubes.append(Cube(-2, top - 1.5, z0, 4, 2, length * 0.28, crest(axis, seed + 2)))
    else:
        back = t0 / 2
        for y0 in (length * 0.18, length * 0.58):
            cubes.append(Cube(-2, -(y0 + length * 0.28), back - 0.5, 4, length * 0.28, 2, crest(axis, seed + 2)))
    return cubes


# ---------------------------------------------------------------------------------------------------------------------
# Grundhaltung (Grad). Dieselben Zahlen stehen in den Parts; die Posen unten bauen darauf auf.
# ---------------------------------------------------------------------------------------------------------------------

RISE = [(22, 20, 18), (22, 18, 16), (22, 16, 16)]                     # (Länge, Dicke Anfang, Dicke Ende) tail_0–2
LIE = [(22, 20, 18), (22, 18, 16), (28, 16, 14), (28, 14, 12), (28, 12, 10), (28, 10, 9), (24, 9, 8)]   # tail_3–9

REST = {
    'root': (0, 0, 0),
    'tail_0': (-36, 0, 6), 'tail_1': (66, 0, -14), 'tail_2': (-30, 0, 8),
    'hips': (-4, 0, 0), 'chest': (8, 0, 0), 'head': (-4, 0, 0), 'jaw': (4, 0, 0),
    'arm_r': (-8, 0, 16), 'fore_r': (-30, 0, -4), 'claw_r': (-16, 0, 0),
    'arm_l': (-4, 0, -18), 'fore_l': (-24, 0, 6), 'claw_l': (-20, 0, 0),
    'wing_r': (26, -26, 0), 'wing_l': (26, 26, 0),
    'tail_3': (0, 75, 0), 'tail_4': (-3, -55, 0), 'tail_5': (82, 0, -8), 'tail_6': (12, 0, 0),
    'tail_7': (12, 0, 0), 'tail_8': (22, 0, 0), 'tail_9': (24, 0, 0), 'sting': (26, 0, 0),
}
HAIR_REST = [(-26 - 10 * abs(i - 2.5), (i - 2.5) * 12, 0) for i in range(6)]
for _i, _r in enumerate(HAIR_REST):
    REST[f'hair_{_i}'] = _r
    REST[f'hair_{_i}_1'] = (36, 0, 0)
for _s in ('r', 'l'):
    REST[f'wing_{_s}_0'] = (24, 0, 0)
    REST[f'wing_{_s}_1'] = (-14, 0, 0)
    REST[f'wing_{_s}_2'] = (-52, 0, 0)


def P(name, pivot, cubes=(), children=()):
    return Part(name, pivot, REST.get(name, (0, 0, 0)), cubes=cubes, children=children)


# ---------------------------------------------------------------------------------------------------------------------
# Oberkörper
# ---------------------------------------------------------------------------------------------------------------------

def head_part():
    skin = smooth(PALE, 0.68, 71)
    hair_cap = scale_plates(72, cell=3, base=0.44)
    cubes = [
        Cube(-5.5, -12, -5.5, 11, 9, 11, face_paint),                 # Schädel mit Gesicht
        Cube(-4.5, -3, -4.5, 9, 1, 7, skin),                          # Oberkiefer
        Cube(-5, -13, -4.5, 10, 2, 10, hair_cap),                     # Scheitel, gerundet
        Cube(-4.5, -11, 5, 9, 8, 2, hair_cap),                        # Hinterkopf
        Cube(-4.5, -11, -6.5, 9, 2, 2, smooth(PALE, 0.74, 73)),       # Brauenwulst
        Cube(-1, -8.5, -6.5, 2, 4, 2, smooth(PALE, 0.72, 74)),        # Nase
        Cube(-6.5, -7.5, -5, 2, 2, 5, smooth(PALE, 0.78, 75)),        # hohe Wangenknochen
        Cube(4.5, -7.5, -5, 2, 2, 5, smooth(PALE, 0.78, 76)),
        Cube(-2.5, -2, -4.5, 1, 2, 1, smooth(PALE, 0.9, 78)),         # Giftzähne (hinter der Lippe)
        Cube(1.5, -2, -4.5, 1, 2, 1, smooth(PALE, 0.9, 79)),
    ]
    jaw = P('jaw', (0, -3, 2), cubes=[
        Cube(-4, 0, -7, 8, 2, 8, skin),
        Cube(-2.5, 1.5, -7.5, 5, 2, 5, smooth(PALE, 0.62, 80)),       # spitzes Kinn
        Cube(-3.5, 0, -6, 7, 1, 6, smooth(rgb('#1a0608', '#4a0c16', '#7a1a2a'), 0.5, 81)),   # Mundraum
    ])
    hair = []
    for i in range(6):
        row = 0 if i in (2, 3) else 1 if i in (1, 4) else 2
        x = (i - 2.5) * 1.8
        length0 = 8 - row
        link = viper_skin(90 + i)
        tip = P(f'hair_{i}_1', (0, 0, length0), cubes=[
            Cube(-1.5, -1.5, -0.5, 3, 3, 6, link),
            Cube(-2, -2, 5, 4, 3, 5, viper_eye),                     # Viperkopf, 4 breit
            Cube(-1, -1.5, 10, 2, 2, 2, viper_eye),                   # Schnauze
        ])
        hair.append(P(f'hair_{i}', (x, -12 + row * 1.5, 3.5), cubes=[
            Cube(-1.5, -1.5, -1, 3, 3, length0 + 1, link),
        ], children=[tip]))
    return P('head', (0, -29, -1), cubes=cubes, children=[jaw] + hair)


def arm_part(side, s):
    """side = -1 rechts (x negativ), +1 links. Lang und sehnig, Klauenhand mit drei Krallen."""
    upper = scale_plates(110 + side, cell=3, base=0.42)
    fore = smooth(PALE, 0.62, 112 + side)
    claws = []
    for k, x in enumerate((-2.5, 0, 2.5)):
        claws += [
            Cube(x - 1, 4, -2, 2, 4, 2, claw_paint),
            Cube(x - 1, 7, -3.5, 2, 3, 2, claw_paint),
        ]
    hand = P(f'claw_{s}', (0, 16, 0), cubes=[
        Cube(-3.5, -0.5, -2, 7, 5, 4, smooth(PALE, 0.56, 114 + side)),
        Cube(-3.5, 2.5, -2.5, 7, 2, 2, smooth(PALE, 0.5, 115 + side)),  # Knöchel
    ] + claws)
    forearm = P(f'fore_{s}', (0, 16, 0), cubes=[
        Cube(-2.5, 0, -2.5, 5, 9, 5, fore),                             # sehnig: 5 -> 4
        Cube(-2, 8, -2, 4, 9, 4, fore),
        Cube(-3, 11, -3, 6, 3, 6, bronze(116 + side)),                  # Armreif
        Cube(-2, -1, 1.5, 4, 3, 2, smooth(PALE, 0.5, 117)),             # Ellbogen
    ], children=[hand])
    return P(f'arm_{s}', (side * 11, -23, 0.5), cubes=[
        Cube(-2.5, -1, -2.5, 5, 18, 5, upper),
        Cube(-3, 9, -3, 6, 3, 6, bronze(118 + side)),                   # Oberarmreif
    ], children=[forearm])


def wing_part(side, s):
    """Verkümmerte Schwinge: Armknochen nach hinten, drei Finger mit Haut. Gespreizt per yaw ±80."""
    bone = smooth(SCALES, 0.46, 130 + side)
    fingers = []
    for k, (length, reach) in enumerate(((16, 0.0), (15, 0.75), (12, 0.75))):
        cubes = [Cube(-1, -1, 0, 2, 2, length, bone)]
        if k > 0:
            cubes.append(Cube(-1, 1, 0, 2, 11, length, membrane(132 + k, reach)))
        fingers.append(P(f'wing_{s}_{k}', (0, 0, 12), cubes=cubes))
    return P(f'wing_{s}', (side * 5, -22, 5), cubes=[
        Cube(-1.5, -1.5, 0, 3, 3, 13, bone),
        Cube(-1, 1.5, 1, 2, 10, 11, membrane(131, 0.9, 0.1)),
    ], children=fingers)


def chest_part():
    pale = smooth(PALE, 0.66, 141)
    plates = scale_plates(142, cell=5, base=0.5)
    plates_b = scale_plates(143, cell=5, base=0.44)
    cubes = [
        Cube(-6, -10, -4.5, 12, 11, 9, torso_skin(140, 0.6)),        # Taille 12, Bauch bleich und geschient
        Cube(-5, -10, -5.5, 10, 11, 11, torso_skin(140, 0.6)),
        Cube(-8.5, -21, -5, 17, 12, 10, torso_skin(141, 0.4)),        # Brustkorb
        Cube(-7.5, -21, -6, 15, 12, 12, torso_skin(141, 0.45)),
        Cube(-6.5, -19, -7, 13, 4, 2, plates),                        # Schuppenpanzer: drei Reihen, V-förmig
        Cube(-9, -22, -7.5, 18, 4, 2, plates),
        Cube(-11, -25.5, -8, 22, 5, 3, plates),
        Cube(-9, -27, -6, 18, 6, 12, plates_b),                       # Schultergürtel
        Cube(-10, -26, 4.5, 20, 15, 3, plates_b),                     # Rückenpanzer
        Cube(8, -28, -5.5, 5, 7, 11, plates),                         # Schulterstücke: Schultern 26 breit
        Cube(-13, -28, -5.5, 5, 7, 11, plates),
        Cube(8.5, -23, -6, 5, 4, 12, plates_b),
        Cube(-13.5, -23, -6, 5, 4, 12, plates_b),
        Cube(-3, -31, -3.5, 6, 5, 6, pale),                           # Hals
        Cube(-4, -30, 0, 8, 4, 4, plates_b),                          # Nackenschuppen
    ]
    return P('chest', (0, -10, 0), cubes=cubes, children=[
        head_part(), arm_part(-1, 'r'), arm_part(1, 'l'), wing_part(-1, 'r'), wing_part(1, 'l'),
    ])


def hips_part():
    scales = hide('y', 150)
    belt = bronze(151, 0.46)
    cubes = [
        Cube(-8, -10, -6, 16, 12, 12, scales),
        Cube(-7, -10, -7, 14, 12, 14, scales),
        Cube(-9, -6, -7.5, 18, 3, 15, belt),                          # Gürtel
        # Löwe vorn (9 breit)
        Cube(-4.5, -10, -9.5, 9, 9, 2, bronze(152, 0.36)),            # Mähne
        Cube(-3.5, -9, -10.5, 7, 7, 2, lion_face),
        Cube(-1.5, -6, -12, 3, 3, 2, bronze(153, 0.62)),              # Schnauze
        Cube(-4.5, -11.5, -9.5, 2, 2, 2, bronze(154, 0.5)),           # Ohren
        Cube(2.5, -11.5, -9.5, 2, 2, 2, bronze(154, 0.5)),
        # Eber links (+x, 7 breit)
        Cube(8.5, -9, -3.5, 2, 7, 7, side_face(155, 1)),
        Cube(10.5, -6, -1.5, 2, 3, 3, bronze(156, 0.58)),
        Cube(10.5, -8, -3, 2, 3, 1, smooth(PALE, 0.72, 157)),        # Hauer
        Cube(10.5, -8, 2, 2, 3, 1, smooth(PALE, 0.72, 157)),
        Cube(9, -11, -3, 2, 2, 2, bronze(158, 0.5)),
        # Wolf rechts (-x, 7 breit)
        Cube(-10.5, -9, -3.5, 2, 7, 7, side_face(159, -1)),
        Cube(-13.5, -5, -1.5, 3, 3, 3, bronze(160, 0.58)),            # Schnauze
        Cube(-10, -12, -3, 2, 3, 2, bronze(161, 0.5)),                # spitze Ohren
        Cube(-10, -12, 1, 2, 3, 2, bronze(161, 0.5)),
    ]
    return P('hips', (0, -22, 0), cubes=cubes, children=[chest_part()])


# ---------------------------------------------------------------------------------------------------------------------
# Leib und Schwanz
# ---------------------------------------------------------------------------------------------------------------------

def sting_part():
    """Giftblase 9 x 9 x 9 (gefast, zum Dorn hin verjüngt) und Dorn 14 lang, 4 -> 3 -> 2, mit dem Bogen gekrümmt."""
    return P('sting', (0, 0, LIE[-1][0]), cubes=[
        Cube(-3, -3, -1, 6, 6, 3, carapace('z', 170)),               # Hals
        Cube(-4.5, -2.5, 2, 9, 5, 6, venom_bulb),
        Cube(-2.5, -4.5, 2, 5, 9, 6, venom_bulb),
        Cube(-3.5, -3.5, 1, 7, 7, 8, venom_bulb),
        Cube(-2.5, -3, 9, 5, 5, 2, venom_bulb),                       # Spitze der Blase
        Cube(-2, -3.5, 10, 4, 4, 5, barb),
        Cube(-1.5, -5, 14, 3, 3, 5, barb),
        Cube(-1, -6.5, 18, 2, 2, 5, barb),
    ])


def rising_branch():
    hips = hips_part()
    child = hips
    for i in (2, 1, 0):
        length, t0, t1 = RISE[i]
        cubes = body_segment('y', length, t0, t1, 200 + i, pale_ring=(i % 2 == 0), joint=i > 0)
        pivot = (0, -RISE[i - 1][0], 0) if i > 0 else (0, -8, 1)
        child = P(f'tail_{i}', pivot, cubes=cubes, children=[child])
    return child


def lying_branch():
    child = sting_part()
    for k in range(len(LIE) - 1, -1, -1):
        n = k + 3
        length, t0, t1 = LIE[k]
        cubes = body_segment('z', length, t0, t1, 220 + n, pale_ring=(n % 2 == 1), scorpion=n >= 7)
        pivot = (0, -10, 4) if k == 0 else (0, 0, LIE[k - 1][0])
        child = P(f'tail_{n}', pivot, cubes=cubes, children=[child])
    return child


def kampe():
    knot = (hide('y', 190, chamfer=True), hide('y', 190))
    root = P('root', (0, 24, 0), cubes=[
        # Knoten am Boden: hier biegt der Leib aus dem Liegen ins Aufrichten.
        Cube(-10, -15, -8, 20, 15, 18, knot[0]),
        Cube(-7, -17, -9.5, 14, 17, 20, knot[1]),
    ], children=[rising_branch(), lying_branch()])
    return Model('kampe', 512, 512, [root])


# ---------------------------------------------------------------------------------------------------------------------
# Posen (Grad im Quelltext; POSES in Radiant). Jede Schlüsselpose erbt von der vorigen derselben Animation, so wie
# KampeModel.kt sie nacheinander überblendet; nicht genannte Knochen stehen in der Grundhaltung.
# ---------------------------------------------------------------------------------------------------------------------

def _hair_all(pitch, spread, tip):
    out = {}
    for i in range(6):
        out[f'hair_{i}'] = (pitch - 4 * abs(i - 2.5), (i - 2.5) * spread, 0)
        out[f'hair_{i}_1'] = (tip, 0, 0)
    return out


def _hood(pitch, fan, tip):
    """Vipern stellen sich auf und fächern in der Stirnebene auf (Drehung um die Längsachse) wie eine Kobrahaube."""
    out = {}
    for i in range(6):
        out[f'hair_{i}'] = (pitch - 6 * abs(i - 2.5), 0, -(i - 2.5) * fan)
        out[f'hair_{i}_1'] = (tip, 0, 0)
    return out


def _wings(spread, lift, finger=1.0):
    """spread 0 = angelegt, 1 = ganz gespreizt."""
    yaw = -26 - 54 * spread
    out = {'wing_r': (26 - 26 * spread, yaw, lift * spread), 'wing_l': (26 - 26 * spread, -yaw, -lift * spread)}
    for s in ('r', 'l'):
        out[f'wing_{s}_0'] = (24 + 16 * spread * finger, 0, 0)
        out[f'wing_{s}_1'] = (-14 + 4 * spread * finger, 0, 0)
        out[f'wing_{s}_2'] = (-52 + 6 * spread * finger, 0, 0)
    return out


KEYS = {}


def key(name, parent, **bones):
    """Neue Schlüsselpose: erbt alle Knochen von parent (Name oder None) und überschreibt die genannten."""
    pose = dict(KEYS[parent]) if parent else {}
    for bone, value in bones.items():
        pose[bone] = value
    KEYS[name] = pose
    return pose


def _merge(name, extra):
    KEYS[name].update(extra)


key('idle', None)

# 1 TAIL_SWEEP: Oberkörper dreht weg und duckt sich, liegender Leib als gespannter Bogen nach links (+x), Stachel am
# Boden; dann peitscht der Leib flach nach rechts.
key('sweep_windup', None,
    tail_0=(-6, 0, 0), tail_1=(24, -10, 0), tail_2=(14, -14, 0), hips=(6, -14, 0), chest=(16, -24, -6), head=(-18, 30, 6),
    arm_r=(-40, 0, 40), fore_r=(-50, 0, 0), arm_l=(-50, 0, -20), fore_l=(-60, 0, 0),
    tail_3=(0, 80, 0), tail_4=(-3, 10, 0), tail_5=(-2, 8, 0), tail_6=(0, 8, 0), tail_7=(0, 8, 0),
    tail_8=(0, 8, 0), tail_9=(0, 6, 0), sting=(-8, 6, 0))
_merge('sweep_windup', _wings(0.2, 10))
key('sweep_hit', 'sweep_windup',
    tail_0=(-6, 0, 0), tail_1=(22, 10, 0), tail_2=(10, 18, 0), hips=(4, 16, 0), chest=(10, 30, 8), head=(-10, -24, -6),
    arm_r=(-60, 0, 30), arm_l=(-30, 0, -50),
    tail_3=(0, -80, 0), tail_4=(-3, 20, 0), tail_5=(-2, 14, 0), tail_6=(0, 12, 0), tail_7=(0, 10, 0),
    tail_8=(0, 8, 0), tail_9=(0, 6, 0), sting=(-8, 4, 0))

# 2 SPIT: richtet sich höher auf, Kopf weit zurück, Vipern als Haube, Kiefer auf; dann stößt der Kopf vor.
key('spit_windup', None,
    tail_0=(-6, 0, 0), tail_1=(10, 0, 0), tail_2=(-6, 0, 0), hips=(-6, 0, 0), chest=(-14, 0, 0), head=(-34, 0, 0),
    jaw=(34, 0, 0), arm_r=(-30, 0, 30), fore_r=(-70, 0, 0), arm_l=(-30, 0, -30), fore_l=(-70, 0, 0),
    tail_5=(70, 0, -8), tail_7=(8, 0, 0), tail_8=(14, 0, 0), tail_9=(16, 0, 0), sting=(40, 0, 0))
_merge('spit_windup', _hood(120, 24, -24))
_merge('spit_windup', _wings(0.35, 15))
key('spit_release', 'spit_windup',
    tail_1=(30, 0, 0), tail_2=(6, 0, 0), hips=(4, 0, 0), chest=(22, 0, 0), head=(26, 0, 0), jaw=(46, 0, 0),
    arm_r=(-20, 0, 40), arm_l=(-20, 0, -40))
_merge('spit_release', _hood(100, 26, -30))

# 3 STING: Stachel hoch über den Kopf zurückgezogen, Arme breit; dann über die linke Schulter nach vorn-unten.
key('sting_windup', None,
    tail_0=(-12, 0, 0), tail_1=(22, 0, 0), tail_2=(-10, 0, 0), hips=(-6, 0, 0), chest=(-10, 0, 0), head=(-10, 0, 0),
    arm_r=(-20, 0, 70), fore_r=(-30, 0, 0), claw_r=(-30, 0, 0), arm_l=(-20, 0, -70), fore_l=(-30, 0, 0), claw_l=(-30, 0, 0),
    tail_3=(0, 75, 0), tail_4=(-3, -60, 0), tail_5=(88, 0, -4), tail_6=(4, 0, 0), tail_7=(0, 0, 0),
    tail_8=(6, 0, 0), tail_9=(26, 0, 0), sting=(60, 0, 0))
_merge('sting_windup', _wings(0.3, 10))
key('sting_hit', 'sting_windup',
    tail_0=(-8, 0, 0), tail_1=(30, 0, 0), tail_2=(6, 0, 0), hips=(6, 0, 0), chest=(16, -12, 0), head=(10, 10, 0),
    arm_r=(-50, 0, 40), arm_l=(-30, 0, -50),
    tail_3=(0, 70, 0), tail_4=(-3, -70, 0), tail_5=(112, 0, 2), tail_6=(30, 0, 0), tail_7=(44, 0, 0),
    tail_8=(40, 0, 0), tail_9=(46, 0, 0), sting=(34, 0, 0))
key('sting_stuck', 'sting_hit',
    tail_0=(-12, 0, -6), tail_1=(26, 0, 8), tail_2=(0, 14, 0), hips=(-4, 18, 0), chest=(-6, 24, -8), head=(-14, -20, 0),
    jaw=(24, 0, 0), arm_r=(-80, 0, 20), fore_r=(-30, 0, 0), arm_l=(-80, 0, -20), fore_l=(-30, 0, 0))

# 4 COIL_UP: der liegende Leib zieht sich zu einer engen Spirale um den aufsteigenden zusammen.
key('coil', None,
    tail_0=(-4, 0, 0), tail_1=(6, 0, 0), tail_2=(-2, 0, 0), hips=(0, 0, 0), chest=(4, 0, 0), head=(-14, 0, 0),
    arm_r=(-110, 0, 20), fore_r=(-40, 0, 0), arm_l=(-120, 0, -20), fore_l=(-30, 0, 0),
    tail_3=(8, 90, 0), tail_4=(18, 62, 0), tail_5=(18, 62, 0), tail_6=(18, 62, 0), tail_7=(18, 62, 0),
    tail_8=(18, 58, 0), tail_9=(20, 50, 0), sting=(40, 10, 0))

# 5 DIVE: oben weit zurück, Schwingen gespreizt, Klauen erhoben; dann gestreckt Kopf voran, Leib als Linie.
key('dive_windup', None,
    tail_0=(-12, 0, 0), tail_1=(10, 0, 0), tail_2=(-6, 0, 0), hips=(-10, 0, 0), chest=(-24, 0, 0), head=(-16, 0, 0),
    jaw=(30, 0, 0), arm_r=(-160, 0, 30), fore_r=(-30, 0, 0), claw_r=(-40, 0, 0),
    arm_l=(-160, 0, -30), fore_l=(-30, 0, 0), claw_l=(-40, 0, 0),
    tail_3=(0, 0, 0), tail_4=(-3, 0, 0), tail_5=(26, 0, 0), tail_6=(16, 0, 0), tail_7=(14, 0, 0),
    tail_8=(14, 0, 0), tail_9=(16, 0, 0), sting=(24, 0, 0))
_merge('dive_windup', _wings(1.0, 30))
_merge('dive_windup', _hair_all(60, 26, 0))
key('dive', 'dive_windup',
    root=(112, 0, 0, 0, -72, 59),
    tail_0=(0, 0, 0), tail_1=(0, 0, 0), tail_2=(0, 0, 0), hips=(0, 0, 0), chest=(0, 0, 0), head=(-60, 0, 0), jaw=(40, 0, 0),
    arm_r=(-170, 0, 8), fore_r=(0, 0, 0), claw_r=(0, 0, 0), arm_l=(-170, 0, -8), fore_l=(0, 0, 0), claw_l=(0, 0, 0),
    tail_3=(-90, 0, 0), tail_4=(0, 0, 0), tail_5=(0, 0, 0), tail_6=(0, 0, 0), tail_7=(0, 0, 0), tail_8=(6, 0, 0),
    tail_9=(10, 0, 0), sting=(14, 0, 0))
_merge('dive', _wings(0.6, -10))
_merge('dive', _hair_all(-20, 8, 10))

# 6 DAZED: lang am Boden, Oberkörper auf den Unterarmen, Kopf hängt, Stachel liegt neben ihr.
key('dazed', None,
    tail_0=(64, 0, 0), tail_1=(22, 0, 0), tail_2=(-10, 0, 0), hips=(-20, 0, 0), chest=(-30, 0, 0), head=(40, 0, 10),
    jaw=(16, 0, 0), arm_r=(-50, 0, 20), fore_r=(-60, 0, 0), claw_r=(10, 0, 0),
    arm_l=(-50, 0, -20), fore_l=(-60, 0, 0), claw_l=(10, 0, 0),
    tail_3=(0, 20, 0), tail_4=(-3, -16, 0), tail_5=(-4, -24, 0), tail_6=(0, -26, 0), tail_7=(0, -30, 0),
    tail_8=(0, -34, 0), tail_9=(0, -30, 0), sting=(-6, -20, 0))
_merge('dazed', _hair_all(-30, 10, 10))

# 7 ROAR: Oberkörper hoch, Arme und Schwingen weit, Kiefer offen, alle Vipern nach vorn.
key('roar', None,
    tail_0=(-8, 0, 0), tail_1=(16, 0, 0), tail_2=(-8, 0, 0), hips=(-8, 0, 0), chest=(-16, 0, 0), head=(-6, 0, 0),
    jaw=(50, 0, 0), arm_r=(-40, 0, 90), fore_r=(-40, 0, 0), claw_r=(-30, 0, 0),
    arm_l=(-40, 0, -90), fore_l=(-40, 0, 0), claw_l=(-30, 0, 0),
    tail_5=(76, 0, -8), tail_7=(8, 0, 0), tail_8=(10, 0, 0), tail_9=(12, 0, 0), sting=(16, 0, 0))
_merge('roar', _wings(1.0, 20))
_merge('roar', _hood(140, 30, -24))

# 8 DEATH: bäumt sich auf, der Stachel sticht ins Leere; dann sackt der Leib Glied für Glied zusammen.
key('death_rear', None,
    tail_0=(-14, 0, 0), tail_1=(10, 0, 0), tail_2=(-10, 0, 0), hips=(-10, 0, 0), chest=(-28, 0, 0), head=(-40, 0, 0),
    jaw=(50, 0, 0), arm_r=(-150, 0, 40), arm_l=(-150, 0, -40), fore_r=(-10, 0, 0), fore_l=(-10, 0, 0),
    tail_5=(90, 20, 0), tail_6=(4, 0, 4), tail_7=(-4, -6, 4), tail_8=(-4, -10, 0), tail_9=(-4, 0, 0), sting=(-10, 0, 0))
_merge('death_rear', _wings(1.0, 40))
_merge('death_rear', _hair_all(110, 26, -20))
key('death', 'death_rear',
    tail_0=(-70, 0, 0), tail_1=(-14, 0, -10), tail_2=(-10, 0, 0), hips=(-6, 0, 10), chest=(10, 0, 6), head=(30, 20, 20),
    jaw=(20, 0, 0), arm_r=(10, 0, 50), arm_l=(0, 0, -60), fore_r=(-10, 0, 0), fore_l=(-20, 0, 0),
    tail_3=(0, 22, 0), tail_4=(-3, -24, 0), tail_5=(-2, -20, 0), tail_6=(0, -14, 0), tail_7=(0, -10, 0),
    tail_8=(0, -16, 0), tail_9=(0, -10, 0), sting=(-6, 0, 0))
_merge('death', _wings(0.4, -30))
_merge('death', _hair_all(-10, 30, 10))


def _rad(value):
    out = tuple(math.radians(v) for v in value[:3])
    return out + tuple(value[3:6]) if len(value) > 3 else out


POSES = {name: {bone: _rad(v) for bone, v in pose.items()} for name, pose in KEYS.items()}

# Vorschau-Hilfe für final_body.png: Ruhepose von oben (root um 90° nach vorn gekippt).
POSES['top_view'] = {'root': (math.radians(90), 0.0, 0.0)}


# ---------------------------------------------------------------------------------------------------------------------
# Posen nach Kotlin
# ---------------------------------------------------------------------------------------------------------------------

def _bone_paths(model):
    out = []

    def walk(part, path):
        p = f'{path}/{part.name}' if path else part.name
        out.append((part.name, p))
        for c in part.children:
            walk(c, p)
    for part in model.parts:
        walk(part, '')
    return out


def kotlin_poses(model):
    bones = _bone_paths(model)
    index = {name: i for i, (name, _) in enumerate(bones)}
    lines = [
        '// <kampe-poses> GENERIERT von tools/hha_assets/kampe_model.py (POSES) — nicht von Hand ändern.',
        'internal object KampeKeys {',
        '    /** Pfade aller Knochen ab der Modellwurzel. */',
        '    val BONES = arrayOf(' + ', '.join(f'"{p}"' for _, p in bones) + ')',
        '',
        '    /** Je Knochen sieben Zahlen: Index, pitch, yaw, roll (Radiant, absolut), dx, dy, dz (Einheiten). */',
    ]

    def num(v):
        return f'{v:.4f}f'
    for name, pose in KEYS.items():
        if name == 'idle':
            continue
        vals = []
        for bone, v in pose.items():
            r = _rad(v)
            off = r[3:6] if len(r) > 3 else (0, 0, 0)
            vals.append(', '.join([f'{index[bone]}f'] + [num(x) for x in r[:3]] + [num(float(x)) for x in off]))
        const = name.upper()
        lines.append(f'    val {const} = floatArrayOf(')
        for v in vals:
            lines.append(f'        {v},')
        lines.append('    )')
    lines.append('}')
    lines.append('// </kampe-poses>')
    return '\n'.join(lines)


def variants(model, albedo, glow):
    """Schreibt die Posen in den markierten Block von KampeModel.kt (keine zusätzlichen Texturen)."""
    path = os.path.normpath(KOTLIN_MODEL)
    with open(path, encoding='utf8') as f:
        src = f.read()
    block = kotlin_poses(model)
    pattern = re.compile(r'// <kampe-poses>.*?// </kampe-poses>', re.S)
    if pattern.search(src):
        src = pattern.sub(lambda m: block, src)
    else:
        src = src.rstrip('\n') + '\n\n' + block + '\n'
    with open(path, 'w', encoding='utf8', newline='\n') as f:
        f.write(src)

# Für die Größenprüfung in preview.py: Diese Knochen (samt Kindern) zählen nicht zur Körperhöhe (Fuß bis Scheitel),
# weil sie Zubehör sind, das laut Brief darüber hinausragen darf (Hut, Waffe, Busch, Ast, Stachel, Ring).
HEIGHT_EXCLUDE = ('tail_3',)
