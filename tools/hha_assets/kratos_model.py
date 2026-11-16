"""Kratos und Bia, die Thronwache des Zeus (Macht und Gewalt): zwei Modelle aus einer Datei.

Kratos ist eine Mauer: breit, geschlossener Helm mit querem Kamm von Ohr zu Ohr, mannshoher Turmschild mit Faust-Relief
am linken Arm und ein Kriegshammer mit Dorn. Bia ist eine Klinge: schmal, hochgewachsen, kahl mit wehender Stirnbinde,
zwei lange gerade Klingen. Beide tragen dieselbe dunkle Bronze und dasselbe Purpur.

Teil von boss_models.py (Aufruf dort mit --only kratos bzw. --only bia). Direkt aufgerufen schreibt diese Datei die
Posentabellen in client/model/KratosModel.kt (zwischen den Marken), damit Vorschau und Spiel dieselben Winkel benutzen:

    python tools/hha_assets/kratos_model.py --kotlin

Koordinaten: 1/16 Block vor dem Renderer-Faktor 0,70, +y nach unten, -z vorn, Füße bei y = 24. Kratos: Scheitel bei -76
(Kamm bis -86). Bia: Scheitel bei -68. Die rechte Körperseite liegt bei -x.

Requisiten (Hammer, Schild) sind Knochen unter den Unterarmen. In manchen Posen liegen oder stehen sie frei (Hammer
steckt im Boden, Schild liegt flach): Dann steht ihre Lage zusätzlich im Modellraum in PINS, und KratosModel.kt rechnet
sie jedes Bild gegen die Elternkette zurück, damit sie auch dann still liegen, wenn sich der Körper bewegt.
"""
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from bosskit import Cube  # noqa: E402
from modelkit import Model, Part, hexc, mix, ramp, rgb, speckle  # noqa: E402
from preview_model import apply as _apply, mat_mul as _mul, rot_zyx as _rot  # noqa: E402

# =====================================================================================================================
# Material
# =====================================================================================================================

BRZ = rgb('#1c1008', '#3a2412', '#5a3818', '#8a5a26', '#b07834', '#d39a4a', '#ecc47e')     # dunkle Bronze
VERD = rgb('#13241d', '#22433a', '#365f52')                                                 # Grünspan in Fugen
PURPLE = rgb('#22040a', '#4a0c10', '#6e1216', '#9a1c1c', '#bc2c28', '#e0483c', '#f07a64')   # Purpur der Macht
STEEL = rgb('#121520', '#2a2f3a', '#4b5361', '#7a8494', '#a8b1bf', '#d8dee8', '#f4f7fb')
STONE = rgb('#3a302b', '#6a5a52', '#86746a', '#a89484', '#c4b2a2', '#dccbbb')              # Haut wie Stein
LEATH = rgb('#120a06', '#2a1810', '#3f2416', '#5a3420', '#714529')
GOLDK = rgb('#4a2a06', '#86560f', '#b8801c', '#d9a02c', '#efc458', '#fbe6a0')             # Gold, leuchtet nie
GLOW = (255, 152, 144)           # Leitfarbe #ff9a8a; Blau knapp über 140, damit die Gold-Prüfung nicht anschlägt
LIGHT = {'top': 0.14, 'bottom': -0.22, 'front': 0.0, 'back': -0.08, 'left': -0.05, 'right': -0.05}
SIDES = ('front', 'back', 'left', 'right')


def glow_c(t=0.8, strength=255):
    """Leuchtfarbe der Wache: Glut von tiefem Rot (0) bis hellem Lachs (1)."""
    return (ramp(rgb('#5a1210', '#c0362c', '#ff8a78', '#ffc8bc'), t), (GLOW[0], GLOW[1], GLOW[2], strength))


def _v(face, x, y, w, h, base, seed, grad, round_x, noise=0.07):
    v = base + LIGHT[face] + grad * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * noise
    if face in SIDES and w > 3 and round_x:
        cx = (w - 1) / 2.0
        v -= round_x * ((x - cx) / cx) ** 2
    return v


def bronze(base=0.5, seed=0, grad=0.16, round_x=0.16, rim=True, seam=True, rivets=0):
    """Dunkle Bronze: Licht oben, heller Grat an der Oberkante, Fuge mit Grünspan unten, auf Wunsch eine Nietreihe."""
    def paint(face, x, y, w, h):
        v = _v(face, x, y, w, h, base - 0.06, seed, grad, round_x)
        if face == 'top':
            if x in (0, w - 1) or y in (0, h - 1):
                v += 0.12
        elif face in SIDES:
            if rim and y == 0:
                v += 0.24
            elif seam and y == h - 1 and h >= 4:
                if speckle(x, 7, seed + 5) > 0.58:
                    return ramp(VERD, 0.35 + LIGHT[face])
                return ramp(BRZ, 0.08)
            if rivets and h >= 6 and w >= 6 and y in (2, 3) and (x - 2) % rivets in (0, 1) and 1 < x < w - 2:
                return ramp(BRZ, 0.95 if (y == 2 and (x - 2) % rivets == 0) else 0.72)
        if (x * 11 + y * 7 + seed) % 41 == 0:
            v += 0.1                                                    # Kratzer
        return ramp(BRZ, v)
    return paint


def purple(base=0.5, seed=0, folds=True, hem=False):
    """Purpurstoff/-leder: weiche Falten, Saum dunkler, auf Wunsch ein Goldsaum ganz unten."""
    def paint(face, x, y, w, h):
        if hem and face in SIDES and y == h - 1:
            return ramp(GOLDK, 0.52 + LIGHT[face])
        v = base + LIGHT[face] + 0.12 * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.06
        if folds:
            v += math.sin(x * 1.1 + seed) * 0.07
        if face in SIDES and x in (0, w - 1) and w > 3:
            v -= 0.1
        return ramp(PURPLE, v)
    return paint


def steel(base=0.55, seed=0, grad=0.18, edge=0.18):
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] * 1.2 + grad * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.06
        if x in (0, w - 1) or y == 0:
            v += edge
        return ramp(STEEL, v)
    return paint


def stone_skin(base=0.55, seed=0, round_x=0.22, grad=0.12):
    """Haut wie Stein: kühles Graubraun, gerundet, leicht gefleckt (wie verwitterter Marmor)."""
    def paint(face, x, y, w, h):
        v = _v(face, x, y, w, h, base, seed, grad, round_x, noise=0.05)
        if speckle(x + 17, y + 5, seed + 9) > 0.8:
            v -= 0.07
        return ramp(STONE, v)
    return paint


def leather(base=0.5, seed=0, studs=False):
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] + 0.1 * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.06
        v += math.sin(x * 0.9 + seed) * 0.04
        if face in SIDES and x in (0, w - 1) and w > 4:
            v -= 0.12
        if studs and face == 'front' and w >= 6 and x in (1, w - 2) and y % 4 == 1:
            return ramp(BRZ, 0.88)
        return ramp(LEATH, v)
    return paint


def wrap(base=0.45, seed=0, pal=None):
    """Griff- oder Armwickel: schräge Bahnen."""
    pal = pal or LEATH

    def paint(face, x, y, w, h):
        v = base + LIGHT[face] + (speckle(x, y, seed) - 0.5) * 0.06
        band = (x + y * 2) % 6
        v += 0.08 if band in (0, 1) else (-0.08 if band == 5 else 0.0)
        return ramp(pal, v)
    return paint


def gold(base=0.6, seed=0):
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] * 0.9 + 0.14 * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.06
        if x in (0, w - 1) or y in (0, h - 1):
            v += 0.12
        return ramp(GOLDK, v)
    return paint


# --- Kratos: besondere Maler -----------------------------------------------------------------------------------------

def cuirass(part, seed):
    """Muskelpanzer aus zwei Facetten: linke Hälfte im Licht, rechte im Schatten, heller Mittelgrat.
    part: 'upper' (Brustmuskeln, Schlüsselbein), 'mid' (Rippenbogen), 'lower' (Bauch)."""
    base = bronze(0.56, seed, grad=0.12, round_x=0.0, rim=True, seam=True)

    def paint(face, x, y, w, h):
        if face not in ('front', 'back'):
            return base(face, x, y, w, h)
        c = base(face, x, y, w, h)
        cx = (w - 1) / 2.0
        d = x - cx
        v = 0.5 + (0.0 if face == 'front' else -0.1) + 0.12 * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.05
        v += 0.1 if d > 0.6 else (-0.08 if d < -0.6 else 0.0)      # zwei Facetten (Licht kommt von links)
        if abs(d) < 0.6:
            v += 0.3 if face == 'front' else 0.06                     # Mittelgrat
        elif face == 'front':
            if part == 'upper':
                arc = h - 3 - 2.2 * (abs(d) / cx) ** 2
                if abs(y - arc) < 0.6 and abs(d) < cx - 1.5:
                    v -= 0.26                                         # Unterkante der Brustmuskeln
                elif y < arc and abs(d) < cx - 1.5:
                    v += 0.1 * (1 - abs(abs(d) - cx * 0.48) / (cx * 0.48))
                if y == 1 and abs(d) > 2:
                    v -= 0.12                                         # Schlüsselbein-Kante
            elif part == 'mid':
                if abs(abs(d) - (2 + y * 0.6)) < 0.55 and y > 1:
                    v -= 0.2                                          # Rippenbogen
            else:
                if y in (3, 7) and 1 < abs(d) < cx - 2:
                    v -= 0.18                                         # Bauchreihen
                if abs(d) - 0.6 < 1 and 1 < y < h - 2:
                    v -= 0.06
        if y == 0:
            v += 0.22
        if y == h - 1:
            return c
        return ramp(BRZ, v)
    return paint


def helm_core(face, x, y, w, h):
    """Helmglocke von innen: vorn tiefschwarz, darin der T-Schlitz (leuchtet nur der Querbalken; der Steg ist Glut)."""
    base = bronze(0.6, 401, grad=0.24, round_x=0.22, rim=False, seam=False)
    if face == 'front':
        # Die Platten davor lassen ein T frei: Querbalken in den Reihen 4–5, Steg 4 breit darunter.
        cx = (w - 1) / 2.0
        if 4 <= y <= 5 and 1 <= x <= w - 2:
            core = abs(x - cx) > 1.0 and y == 4
            return glow_c(0.95 if core else 0.66, 255 if core else 200)
        if 6 <= y and abs(x - cx) < 2:
            return glow_c(0.62 - (y - 6) * 0.08, 190) if y < 9 else ramp(BRZ, 0.02)
        return ramp(BRZ, 0.03)
    if face in ('left', 'right') and y == h - 3:
        return ramp(BRZ, 0.88 if x % 3 == 1 else 0.36)              # Nietband an den Schläfen
    return base(face, x, y, w, h)


def helm_plate(seed, brow=False):
    """Stirnband oder Wangenplatte des Helms: polierte Kante, Gravurlinie."""
    base = bronze(0.66 if brow else 0.58, seed, grad=0.2, round_x=0.0, rim=True, seam=False)

    def paint(face, x, y, w, h):
        if face == 'front':
            if brow and y == h - 1:
                return ramp(BRZ, 0.3)
            if not brow and (x == 0 or x == w - 1):
                return ramp(BRZ, 0.86)
            if not brow and y == h - 1:
                return ramp(BRZ, 0.32)
        return base(face, x, y, w, h)
    return paint


def crest_paint(seed, lift=0.0):
    """Querer Helmbusch aus Rosshaar: dunkle Wurzel, Strähnen nach oben heller, unregelmäßige Spitzen."""
    def paint(face, x, y, w, h):
        strand = speckle(x * 5, 3, seed)
        if face in ('front', 'back'):
            t = 1 - y / max(h - 1, 1)
            v = 0.22 + lift + 0.34 * t + (strand - 0.5) * 0.22 + (speckle(x, y, seed) - 0.5) * 0.06
            if x % 3 == 1:
                v -= 0.08                                                 # Strähnenfuge
            if face == 'back':
                v -= 0.08
            return ramp(PURPLE, v)
        if face == 'top':
            return ramp(PURPLE, 0.58 + lift + (strand - 0.5) * 0.2)
        return ramp(PURPLE, 0.3 + lift + LIGHT[face] + 0.25 * (1 - y / max(h - 1, 1)))
    return paint


def shield_field(seed, edge_l=True, edge_r=True, top_rim=False, bottom_rim=False):
    """Schildfläche: dunkle Bronze mit Wölbung, zwei Texel Goldrand an den Außenkanten, Nietkranz innen."""
    base = bronze(0.5, seed, grad=0.18, round_x=0.0, rim=False, seam=False)

    def paint(face, x, y, w, h):
        if face == 'front':
            if (edge_l and x <= 1) or (edge_r and x >= w - 2) or (top_rim and y <= 1) or (bottom_rim and y >= h - 2):
                outer = (edge_l and x == 0) or (edge_r and x == w - 1) or (top_rim and y == 0) or (bottom_rim and y == h - 1)
                return ramp(GOLDK, (0.72 if outer else 0.5) + 0.1 * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.05)
            if (edge_l and x == 3) or (edge_r and x == w - 4):
                if y % 5 == 2:
                    return ramp(BRZ, 0.92)                            # Nietkranz
                return ramp(BRZ, 0.24)
            cx = (w - 1) / 2.0
            v = 0.46 + 0.12 * (1 - abs(x - cx) / max(cx, 1)) + 0.14 * (0.5 - y / max(h - 1, 1))
            v += (speckle(x, y, seed) - 0.5) * 0.08
            if (x * 7 + y * 13 + seed) % 47 == 0:
                v += 0.14
            return ramp(BRZ, v)
        if face in ('left', 'right', 'top', 'bottom'):
            return ramp(GOLDK, 0.5 + LIGHT[face])
        # Rückseite: Holz und Leder
        v = 0.42 + (speckle(x, y, seed + 2) - 0.5) * 0.08 + (0.06 if x % 4 == 0 else 0.0)
        return ramp(LEATH, v + 0.1)
    return paint


def relief(seed, kind):
    """Faust-Relief in Gold: kind = 'fist' (Knöchelreihe, Fingerfugen), 'thumb', 'wrist' (Armband)."""
    def paint(face, x, y, w, h):
        v = 0.6 + LIGHT[face] + 0.18 * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.05
        if face == 'front':
            if kind == 'fist':
                if y <= 1:
                    v += 0.22 if x % 3 != 2 else -0.1                 # Knöchel
                elif x % 3 == 2 and y < h - 2:
                    v -= 0.3                                          # Fingerfugen
                if y == h - 1:
                    v -= 0.15
            elif kind == 'wrist':
                if y in (1, h - 2):
                    v -= 0.25
            elif kind == 'thumb':
                if x == w - 1:
                    v += 0.15
        return ramp(GOLDK, v)
    return paint


def hammer_head(seed, face_side=False):
    """Hammerkopf aus Stahl: gehärtete helle Kanten, dunkle Mitte, Schlagfläche gerieft."""
    base = steel(0.36, seed, grad=0.2, edge=0.22)

    def paint(face, x, y, w, h):
        if face_side and face == 'front':
            cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
            r = max(abs(x - cx), abs(y - cy))
            v = 0.62 - 0.06 * r + (speckle(x, y, seed) - 0.5) * 0.06    # blank geschlagene Mitte
            if r >= cx - 0.5:
                v = 0.4                                                   # Fase
            return ramp(STEEL, v)
        return base(face, x, y, w, h)
    return paint


# --- Bia: besondere Maler --------------------------------------------------------------------------------------------

def bia_face(face, x, y, w, h):
    """Kahler Kopf, Gesicht in drei Ebenen: Stirn hell, Augenhöhlen tief mit leuchtenden Augen, Mund ein gerader Strich."""
    skin = stone_skin(0.6, 501, round_x=0.24, grad=0.16)
    if face != 'front':
        if face == 'top':
            return ramp(STONE, 0.66 + (speckle(x, y, 502) - 0.5) * 0.05 - 0.08 * abs(x - (w - 1) / 2.0) / max((w - 1) / 2.0, 1))
        return skin(face, x, y, w, h)
    # Reihen: 1–3 Stirnbinde (eigener Quader), 3–4 Brauenwulst (Quader), 5–6 Augen, 6–8 Nase (Quader), 9 Mund.
    cx = (w - 1) / 2.0
    d = abs(x - cx)
    if y == 5:
        if 1 <= d <= 3:
            return glow_c(0.95 if d >= 2 else 0.72, 255)              # Augen: schmale glühende Schlitze
        return ramp(STONE, 0.22 if d > 3 else 0.5)                    # Augenhöhle / Nasenwurzel
    if y == 6:
        if 1 <= d <= 3:
            return ramp(STONE, 0.2)                                    # tiefe Höhle unter dem Blick
        if d > 3:
            return ramp(STONE, 0.4)
    if y == 7 and d >= 3:
        return ramp(STONE, 0.66)                                       # Wangenknochen fangen Licht
    if y == 8 and d >= 2:
        return ramp(STONE, 0.42)                                       # Wangenschatten
    if y == 9 and d < 2.6:
        return ramp(STONE, 0.16)                                       # Mund: gerader Strich
    if y == 10 and d < 2:
        return ramp(STONE, 0.62)                                       # Kinn
    if y >= 10 and d > 3:
        return ramp(STONE, 0.38)                                       # Kieferkante
    v = 0.58 - 0.18 * (d / cx) ** 2 + 0.08 * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, 503) - 0.5) * 0.04
    return ramp(STONE, v)


def baldric(inner, x0, y0, w, seed):
    """Diagonaler Lederriemen über Brust und Rücken (rechte Schulter → linke Hüfte), mit Bronzenieten. x0, y0, w: Lage
    des Quaders im Brustraum, damit der Riemen über mehrere Quader durchläuft."""
    ax, ay, bx, by = -12.0, -28.0, 10.0, 0.0
    lx, ly = bx - ax, by - ay
    length = math.hypot(lx, ly)

    def paint(face, x, y, ww, h):
        if face not in ('front', 'back'):
            return inner(face, x, y, ww, h)
        px = x0 + x + 0.5 if face == 'front' else x0 + w - x - 0.5
        py = y0 + y + 0.5
        s = ((px - ax) * lx + (py - ay) * ly) / length              # Lage entlang des Riemens
        d = abs((px - ax) * ly - (py - ay) * lx) / length           # Abstand zur Mittellinie
        if d < 2.2 and -1 < s < length + 1:
            if d > 1.5:
                return ramp(LEATH, 0.12)                              # Kante
            if face == 'front' and int(s) % 6 == 2 and d < 0.8:
                return ramp(BRZ, 0.9)                                 # Niete
            return ramp(LEATH, 0.5 + (speckle(x, y, seed) - 0.5) * 0.08 + (0.06 if face == 'front' else -0.04))
        return inner(face, x, y, ww, h)
    return paint


def blade_paint(seed):
    """Lange gerade Klinge: Hohlkehle in der Mitte, helle Schliffkanten; je eine Kante (1 Texel) leuchtet."""
    def paint(face, x, y, w, h):
        if face in ('left', 'right'):
            if x == 0:
                return glow_c(0.78, 215)
            return ramp(STEEL, 0.9)
        if face in ('front', 'back'):
            v = 0.62 + 0.12 * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.05
            if x in (0, w - 1):
                v += 0.22                                             # Schliffkante
            elif w >= 4 and x in (w // 2 - 1, w // 2):
                v -= 0.22 if x == w // 2 - 1 else 0.1                # Hohlkehle
            if face == 'back':
                v -= 0.08
            return ramp(STEEL, v)
        return ramp(STEEL, 0.7 + LIGHT[face])
    return paint


def tip_paint(seed):
    def paint(face, x, y, w, h):
        if face in ('left', 'right') and x == 0:
            return glow_c(0.85, 215)
        return ramp(STEEL, 0.72 + LIGHT[face] + 0.16 * (y / max(h - 1, 1)) + (0.15 if x in (0, w - 1) else 0.0))
    return paint


# =====================================================================================================================
# Kinematik (wie beim Kerkermeister): Vorwärtsrechnung, Ausrichten, kleiner IK-Löser. Gerechnet wird nur hier;
# KratosModel.kt bekommt die fertigen Zahlen.
# =====================================================================================================================

_EYE = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]


def _tr(m):
    return [[m[j][i] for j in range(3)] for i in range(3)]


def _add(a, b):
    return tuple(x + y for x, y in zip(a, b))


def _sub(a, b):
    return tuple(x - y for x, y in zip(a, b))


def _scale(a, k):
    return tuple(x * k for x in a)


def _dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def _norm(a):
    n = math.sqrt(_dot(a, a)) or 1.0
    return tuple(x / n for x in a)


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def euler(m):
    yaw = math.asin(max(-1.0, min(1.0, -m[2][0])))
    pitch = math.atan2(m[2][1], m[2][2])
    roll = math.atan2(m[1][0], m[0][0])
    return pitch, yaw, roll


def frame(ydir, zhint):
    y = _norm(ydir)
    z = _sub(zhint, _scale(y, _dot(zhint, y)))
    if _dot(z, z) < 1e-6:
        z = _sub((1, 0, 0), _scale(y, y[0]))
    z = _norm(z)
    x = _cross(y, z)
    return [[x[i], y[i], z[i]] for i in range(3)]


class Rig:
    """Knochenbaum eines Modells für Rechnungen an einer Pose (Dict Name -> 3 oder 6 Zahlen)."""

    def __init__(self, model):
        self.parts, self.parent = {}, {}

        def walk(p, par):
            self.parts[p.name] = p
            self.parent[p.name] = par
            for c in p.children:
                walk(c, p.name)
        for p in model.parts:
            walk(p, None)

    def order(self):
        out = []

        def walk(name):
            out.append(name)
            for c in self.parts[name].children:
                walk(c.name)
        for n, par in self.parent.items():
            if par is None:
                walk(n)
        return out

    @staticmethod
    def _val(pose, name):
        v = tuple(pose[name])
        return v[:3], (v[3:6] if len(v) >= 6 else (0.0, 0.0, 0.0))

    def fk(self, pose, name):
        if name is None:
            return _EYE, (0.0, 0.0, 0.0)
        chain = []
        n = name
        while n is not None:
            chain.append(n)
            n = self.parent[n]
        rot, pos = _EYE, (0.0, 0.0, 0.0)
        for n in reversed(chain):
            r, off = self._val(pose, n)
            pos = _add(pos, _apply(rot, _add(self.parts[n].pivot, off)))
            rot = _mul(rot, _rot(*r))
        return rot, pos

    def world(self, pose, name, local=(0, 0, 0)):
        rot, pos = self.fk(pose, name)
        return _add(pos, _apply(rot, local))

    def set_world_rot(self, pose, name, rw):
        rp, _ = self.fk(pose, self.parent[name])
        m = _mul(_tr(rp), rw)
        pitch, yaw, roll = euler(m)
        _, off = self._val(pose, name)
        pose[name] = (pitch, yaw, roll) + (tuple(off) if any(off) else ())

    def place(self, pose, name, target):
        """Verschiebt den Knochenursprung (Versatz im Elternraum) auf den Weltpunkt target."""
        rp, pp = self.fk(pose, self.parent[name])
        local = _apply(_tr(rp), _sub(target, pp))
        r, _ = self._val(pose, name)
        pose[name] = tuple(r) + _sub(local, self.parts[name].pivot)

    def aim(self, pose, name, ydir, zhint=(0, 0, -1)):
        self.set_world_rot(pose, name, frame(ydir, zhint))

    def ik(self, pose, arm, fore, grip, target, elbow=None, weight=0.08, elbow_weight=0.04):
        """Zwei-Knochen-IK: Oberarm (pitch, yaw, roll) und Unterarm (pitch), damit der Griffpunkt target trifft."""
        names = [(arm, 0), (arm, 1), (arm, 2), (fore, 0)]
        start = [pose[n][k] for n, k in names]

        def put(vals):
            for (n, k), v in zip(names, vals):
                cur = list(pose[n])
                cur[k] = v
                pose[n] = tuple(cur)

        def cost(vals):
            put(vals)
            if not -2.7 <= vals[3] <= 0.05:
                return 1e9
            d = _sub(self.world(pose, fore, grip), target)
            c = _dot(d, d)
            if elbow is not None:
                e = _sub(self.world(pose, fore), elbow)
                c += elbow_weight * _dot(e, e)
            c += weight * sum((a - b) ** 2 for a, b in zip(vals, start))
            return c

        best = [pose[n][k] for n, k in names]
        best_c = cost(best)
        for step in (0.4, 0.15, 0.05, 0.015, 0.005):
            for _ in range(80):
                improved = False
                for i in range(len(best)):
                    for sgn in (1, -1):
                        trial = list(best)
                        trial[i] += sgn * step
                        c = cost(trial)
                        if c < best_c:
                            best, best_c, improved = trial, c, True
                if not improved:
                    break
        put(best)
        miss = _sub(self.world(pose, fore, grip), target)
        return math.sqrt(_dot(miss, miss))


# =====================================================================================================================
# KRATOS – Grundhaltung (Ruhe): Diese Winkel stehen als Grundrotation im Modell; IK ergänzt Arme und Requisiten.
# =====================================================================================================================

K_IDLE = {
    'body': (0.0, 0.0, 0.0),
    'chest': (0.04, 0.0, 0.0),
    'head': (0.06, 0.0, 0.0),
    'crest': (0.0, 0.0, 0.0),
    'arm_r': (-0.3, 0.0, 0.3),
    'fore_r': (-1.2, 0.0, 0.0),
    'fingers_r': (-2.6, 0.0, 0.0),
    'hammer': (0.0, 0.0, 0.0),
    'arm_l': (-0.3, 0.0, -0.25),
    'fore_l': (-0.9, 0.0, 0.0),
    'shield': (0.0, 0.0, 0.0),
    'skirt_f': (-0.04, 0.0, 0.0),
    'cape': (0.1, 0.0, 0.0),
    'cape_1': (0.06, 0.0, 0.0),
    'skirt_b': (0.06, 0.0, 0.0),
    'leg_r': (0.0, 0.0, 0.08),
    'shin_r': (0.0, 0.0, -0.08),
    'leg_l': (0.0, 0.0, -0.08),
    'shin_l': (0.0, 0.0, 0.08),
}


def kdeg(name):
    return tuple(math.degrees(a) for a in K_IDLE[name][:3])


GRIP_R = (0, 19.5, -2.0)      # Faustmitte rechts (Hammerstiel) im Unterarmraum
GRIP_L = (0, 20.0, 0.0)       # Faustmitte links (Schildgriff)


def hammer():
    """Kriegshammer: Stiel 44 (3 dick), Kopf 12 x 10 x 10 mit gerillter Schlagfläche, hinten ein Dorn (8 lang, 4 -> 2).
    Ursprung in der Faust; der Kopf liegt bei -y, die Schlagfläche zeigt nach -z, der Dorn nach +z."""
    head = hammer_head(310)
    return Part('hammer', (0, 19.5, -2.0), kdeg('hammer'), cubes=[
        Cube(-1.5, -34, -1.5, 3, 44, 3, steel(0.28, 300, grad=0.0, edge=0.1)),             # Stiel (dunkler Stahl)
        Cube(-2, -4, -2, 4, 12, 4, wrap(0.5, 301)),                                        # Griffwicklung
        Cube(-2.5, 9, -2.5, 5, 3, 5, bronze(0.62, 302, round_x=0.0, seam=False)),           # Knauf
        Cube(-2, -24, -2, 4, 3, 4, bronze(0.6, 303, round_x=0.0, seam=False)),              # Ring am Hals
        Cube(-5, -44, -5, 10, 10, 10, head),                                               # Kopf, Kern
        Cube(-6, -45, -8, 12, 12, 3, hammer_head(311, face_side=True)),                     # Schlagfläche (12 x 12)
        Cube(-5.5, -36, -5.5, 11, 2, 11, bronze(0.66, 304, round_x=0.0, seam=False)),       # Bronzeband unten
        Cube(-5.5, -45, -5.5, 11, 2, 11, bronze(0.7, 305, round_x=0.0, seam=False)),        # Bronzeband oben
        Cube(-2, -41, 5, 4, 4, 4, steel(0.5, 306)),                                        # Dorn: Wurzel 4
        Cube(-1, -40, 9, 2, 2, 4, steel(0.62, 307, edge=0.25)),                            # Dorn: Spitze 2
        Cube(-1, -49, -1, 2, 4, 2, steel(0.6, 308)),                                       # Kopfspitze oben
    ])


def shield():
    """Turmschild 30 x 62 x 3, Ecken um 45 Grad abgeschrägt (Stufen), Goldrand, Faust-Relief 14 x 16 erhaben um 1.
    Ursprung im Griff (in der linken Faust); die Schauseite zeigt nach -z, +y ist unten. In sich geschlossen."""
    z0 = -9.0                    # Vorderfläche
    cubes = [Cube(-15, -19, z0, 30, 38, 3, shield_field(201))]
    # Abschrägung oben und unten: drei Stufen, je 2 hoch und je Seite 2 schmaler (45 Grad).
    for k, w in enumerate((26, 22, 18)):
        top = -19 - 2 * (k + 1)
        bot = 19 + 2 * k
        cubes.append(Cube(-w / 2, top, z0, w, 2, 3, shield_field(210 + k, top_rim=(k == 2))))
        cubes.append(Cube(-w / 2, bot, z0, w, 2, 3, shield_field(220 + k, bottom_rim=(k == 2))))
    # Faust-Relief (erhaben um 1): Armband, Faust, Daumen, Knöchelreihe.
    rz = z0 - 1
    cubes += [
        Cube(-3, 4, rz, 6, 8, 1, relief(230, 'wrist')),
        Cube(-6, -7, rz, 12, 11, 1, relief(231, 'fist')),
        Cube(-7, -4, rz, 3, 6, 1, relief(232, 'thumb')),
        Cube(-5, -9, rz, 10, 2, 1, relief(233, 'fist')),
    ]
    # Rückseite: Griffleiste, zwei Lederriemen, Querholz.
    cubes += [
        Cube(-7, -1.5, -6, 14, 3, 2, leather(0.55, 240)),
        Cube(-12, -16, -6.5, 3, 34, 1.5, leather(0.4, 241)),
        Cube(9, -16, -6.5, 3, 34, 1.5, leather(0.4, 242)),
    ]
    return Part('shield', GRIP_L, kdeg('shield'), cubes=cubes)


def k_arm(side):
    """Arm: Schulterkeil (14 x 6 x 14, zweistufig), Oberarm 9 (Haut, Armring), Unterarm 10 (Bronzeschiene), Hand 10."""
    s = 'r' if side < 0 else 'l'
    seed = 610 if side < 0 else 640
    out = side * 2.0                                   # Schulterkeil steht nach außen über
    if side < 0:
        hand = [
            Cube(-4.5, 15, -3.5, 9, 7, 7, bronze(0.56, seed + 6, round_x=0.0, seam=False)),          # Handrücken/Ballen
            Cube(4.5 if side < 0 else -6.5, 15, -3, 2, 5, 4, bronze(0.6, seed + 7, round_x=0.0)),   # Daumen
        ]
        fingers = [Part('fingers_r', (0, 22, -3.5), kdeg('fingers_r'), cubes=[
            Cube(-4.5, 0, 0, 9, 6, 4, bronze(0.62, seed + 8, round_x=0.0, seam=False)),
        ])]
        item = [hammer()]
    else:
        hand = [
            Cube(-5, 15, -5, 10, 9, 10, bronze(0.55, seed + 6, round_x=0.0, seam=False)),           # Faust
            Cube(-6.5, 15, -4, 2, 5, 4, bronze(0.6, seed + 7, round_x=0.0)),                       # Daumen
        ]
        fingers = []
        item = [shield()]
    return Part(f'arm_{s}', (side * 21, -32, 0), kdeg(f'arm_{s}'), cubes=[
        Cube(-7 + out, -5, -7, 14, 4, 14, bronze(0.66, seed, round_x=0.2, rivets=4)),                # Schulterkeil oben
        Cube(-6.5 + out * 1.2, -1, -6.5, 13, 3, 13, bronze(0.52, seed + 1, round_x=0.2)),            # zweite Lamelle
        Cube(-4.5, 0, -4.5, 9, 21, 9, stone_skin(0.56, seed + 2)),                                 # Oberarm
        Cube(-5, 8, -5, 10, 3, 10, bronze(0.6, seed + 3, round_x=0.2, seam=False)),                # Armring
    ], children=[
        Part(f'fore_{s}', (0, 21, 0), kdeg(f'fore_{s}'), cubes=[
            Cube(-4.5, -1, -4.5, 9, 6, 9, stone_skin(0.5, seed + 4)),                              # Ellbogen
            Cube(-5, 3, -5, 10, 12, 10, bronze(0.52, seed + 5, round_x=0.22, rivets=4)),           # Unterarmschiene
            Cube(-5.5, 12, -5.5, 11, 3, 11, leather(0.45, seed + 9)),                              # Stulpe
        ] + hand, children=fingers + item),
    ])


def k_leg(side):
    s = 'r' if side < 0 else 'l'
    seed = 800 if side < 0 else 830
    return Part(f'leg_{s}', (side * 8, 0, 0), kdeg(f'leg_{s}'), cubes=[
        Cube(-6, 0, -6, 12, 20, 12, stone_skin(0.5, seed)),                                       # Oberschenkel
        Cube(-5.5, 15, -7.5, 11, 6, 3, bronze(0.64, seed + 1, round_x=0.2, seam=False)),           # Kniekachel
    ], children=[
        Part(f'shin_{s}', (0, 20, 0), kdeg(f'shin_{s}'), cubes=[
            Cube(-5.5, 0, -5.5, 11, 16, 11, stone_skin(0.46, seed + 2)),                          # Wade
            Cube(-6, 0, -6.5, 12, 15, 9, bronze(0.54, seed + 3, round_x=0.26, rivets=0)),          # Beinschiene (bis Knie)
            Cube(-6, 16, -10, 12, 4, 16, leather(0.42, seed + 4)),                                 # Stiefel 12 x 4 x 16
            Cube(-6.5, 13, -7, 13, 3, 13, leather(0.5, seed + 5, studs=False)),                    # Stiefelschaft
            Cube(-5, 15, -11, 10, 3, 3, bronze(0.62, seed + 6, round_x=0.0, seam=False)),          # Zehenkappe
        ]),
    ])


def pec(seed, side):
    """Erhabene Brustplatte (Muskelpanzer): gewölbt, Kante unten im Schatten, eine Nietreihe außen."""
    base = bronze(0.62, seed, grad=0.22, round_x=0.0, rim=True, seam=False)

    def paint(face, x, y, w, h):
        if face == 'front':
            k = (x if side > 0 else w - 1 - x) / max(w - 1, 1)       # 0 innen am Grat, 1 außen
            v = 0.56 + 0.16 * math.sin(k * math.pi) * (1 - y / max(h - 1, 1)) + (0.06 if side > 0 else -0.04)
            v += (speckle(x, y, seed) - 0.5) * 0.05
            if y == h - 1:
                v -= 0.28
            elif y == 0:
                v += 0.2
            if k > 0.86 and y % 3 == 1 and y < h - 1:
                return ramp(BRZ, 0.92)
            return ramp(BRZ, v)
        return base(face, x, y, w, h)
    return paint


def cape():
    """Kurzer Purpurmantel des Befehlshabers (zwei Glieder, Goldsaum unten), an den Schulterblättern befestigt."""
    def mantle(base, seed, hem):
        def paint(face, x, y, w, h):
            if hem and face in SIDES and y >= h - 2:
                return ramp(GOLDK, (0.62 if y == h - 2 else 0.44) + LIGHT[face] + (speckle(x, y, seed) - 0.5) * 0.06)
            v = base + LIGHT[face] + math.sin(x * 0.7 + seed) * 0.09 + math.sin(x * 1.9) * 0.03
            v += 0.1 * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.05
            if face in ('front', 'back') and x in (0, w - 1):
                v += 0.1
            return ramp(PURPLE, v)
        return paint
    tail = Part('cape_1', (0, 27, 0), kdeg('cape_1'), cubes=[Cube(-14, 0, -1, 28, 26, 2, mantle(0.3, 651, True))])
    return Part('cape', (0, -35, 12), kdeg('cape'), cubes=[
        Cube(-14, 0, -1, 28, 28, 2, mantle(0.34, 650, False)),
        Cube(-16, -2, -2, 6, 4, 3, gold(0.6, 652)),                                               # Spange rechts
        Cube(10, -2, -2, 6, 4, 3, gold(0.6, 653)),                                                # Spange links
    ], children=[tail])


def kratos():
    crest = Part('crest', (0, -12, 0), kdeg('crest'), cubes=[
        Cube(-11, -2, -1.5, 22, 2, 3, bronze(0.72, 420, round_x=0.0, seam=False)),                 # Bronzeschiene
        Cube(-8, -9, -1, 16, 7, 2, crest_paint(421, 0.06)),                                        # Kamm Mitte (8 hoch)
        Cube(-13, -7, -1, 5, 5, 2, crest_paint(422)),                                              # Kamm außen rechts
        Cube(8, -7, -1, 5, 5, 2, crest_paint(423)),                                                # Kamm außen links
        Cube(-5, -10, -1, 10, 1, 2, crest_paint(424, 0.12)),                                       # Wölbung oben
    ])
    head = Part('head', (0, -38, 0), kdeg('head'), cubes=[
        Cube(-4, -2, -4, 8, 4, 8, stone_skin(0.4, 400)),                                           # Hals
        Cube(-6, -12, -6, 12, 12, 12, helm_core),                                                  # Helmglocke
        Cube(-7, -11, -8, 14, 3, 2, helm_plate(402, brow=True)),                                   # Stirnband über dem Schlitz
        Cube(-7, -6, -8, 5, 7, 2, helm_plate(403)),                                                # Wangenplatte rechts
        Cube(2, -6, -8, 5, 7, 2, helm_plate(404)),                                                 # Wangenplatte links
        Cube(-7, -6, -6, 2, 6, 9, bronze(0.5, 407, round_x=0.0, seam=False)),                      # Wangenflügel rechts
        Cube(5, -6, -6, 2, 6, 9, bronze(0.5, 408, round_x=0.0, seam=False)),                       # Wangenflügel links
        Cube(-7, -5, 1, 14, 6, 6, bronze(0.5, 405, round_x=0.2, seam=True)),                       # Nackenschutz
        Cube(-6.5, -13, -6.5, 13, 2, 13, bronze(0.72, 406, round_x=0.18, seam=False)),             # Kalotte
        Cube(-5, -14, -5, 10, 1, 10, bronze(0.8, 409, round_x=0.0, seam=False)),                   # Kuppel
    ], children=[crest])

    chest = Part('chest', (0, -10, 0), kdeg('chest'), cubes=[
        Cube(-15, -12, -10, 30, 12, 20, cuirass('lower', 601)),                                    # Bauch (Taille 30)
        Cube(-17.5, -24, -10.5, 35, 13, 21, cuirass('mid', 602)),                                  # Rippenbogen
        Cube(-20, -36, -10, 40, 13, 20, cuirass('upper', 603)),                                    # Brust (40 + Keile = 44)
        Cube(-9, -38, -8, 18, 3, 16, bronze(0.46, 604, round_x=0.2)),                              # Halsring
        Cube(-16, 1, -11, 32, 3, 22, leather(0.42, 605, studs=True)),                              # Gürtel
        Cube(-3, 1, -12, 6, 3, 2, gold(0.6, 606)),                                                 # Schnalle
        Cube(-13, -34, 10, 26, 30, 2, bronze(0.44, 607, round_x=0.25, rivets=5)),                  # Rückenplatte
        Cube(-16, -33, -12, 14, 9, 2, pec(608, -1)),                                               # Brustplatte rechts
        Cube(2, -33, -12, 14, 9, 2, pec(609, 1)),                                                  # Brustplatte links
        Cube(-17, -1, -11.5, 34, 3, 23, purple(0.36, 610, folds=False)),                           # Feldherrenbinde
        Cube(9, -1, -13, 5, 3, 2, purple(0.44, 611, folds=False)),                                 # Knoten der Binde
        Cube(10, 2, -13, 3, 7, 2, purple(0.4, 612, folds=False)),                                  # Ende der Binde
    ], children=[
        head, k_arm(-1), k_arm(1), cape(),
        Part('skirt_f', (0, 3, -10), kdeg('skirt_f'), cubes=[
            Cube(-14.5 + 7.5 * i, 0, -1.5 + (0.5 if i % 3 else 0.0), 7, 17, 2, purple(0.4, 620 + i, hem=True))
            for i in range(4)
        ] + [Cube(-15, -1, -1.5, 30, 2, 3, bronze(0.6, 625, round_x=0.0, seam=False))]),
        Part('skirt_b', (0, 3, 10), kdeg('skirt_b'), cubes=[
            Cube(-14.5 + 7.5 * i, 0, -0.5 - (0.5 if i % 3 else 0.0), 7, 17, 2, purple(0.34, 630 + i, hem=True))
            for i in range(4)
        ] + [Cube(-15, -1, -1.5, 30, 2, 3, bronze(0.56, 635, round_x=0.0, seam=False))]),
    ])

    body = Part('body', (0, -16, 0), kdeg('body'), cubes=[
        Cube(-14, -10, -9, 28, 12, 18, leather(0.36, 590)),                                        # Becken
        Cube(-15.5, -4, -8, 5, 12, 16, purple(0.44, 591, hem=True)),                               # Seitenstreifen rechts
        Cube(10.5, -4, -8, 5, 12, 16, purple(0.44, 592, hem=True)),                                # Seitenstreifen links
    ], children=[chest, k_leg(-1), k_leg(1)])
    return Model('kratos', 512, 256, [body])


# =====================================================================================================================
# BIA
# =====================================================================================================================

B_IDLE = {
    'body': (0.0, 0.0, 0.0),
    'chest': (0.06, 0.0, 0.0),
    'head': (0.0, 0.0, 0.0),
    'band_l_0': (0.5, 0.0, -0.1), 'band_l_1': (0.2, 0.0, 0.0),
    'band_r_0': (0.5, 0.0, 0.1), 'band_r_1': (0.2, 0.0, 0.0),
    'arm_r': (0.0, 0.0, 0.1), 'fore_r': (-0.2, 0.0, 0.0), 'blade_r': (0.0, 0.0, 0.0),
    'arm_l': (0.0, 0.0, -0.1), 'fore_l': (-0.2, 0.0, 0.0), 'blade_l': (0.0, 0.0, 0.0),
    'leg_r': (0.0, 0.0, 0.04), 'shin_r': (0.0, 0.0, -0.04),
    'leg_l': (0.0, 0.0, -0.04), 'shin_l': (0.0, 0.0, 0.04),
}


def bdeg(name):
    return tuple(math.degrees(a) for a in B_IDLE[name][:3])


B_GRIP = (0, 19.5, 0)


def blade(side):
    """Gerade Klinge 40 lang (36 + Keilspitze 4), 4 breit, 2 dick; Parierstange, Griff, Knauf. Ursprung im Griff,
    die Spitze liegt bei -y, die leuchtende Schneide (x = 0 der Seitenflächen) an beiden Seiten."""
    s = 'r' if side < 0 else 'l'
    seed = 900 if side < 0 else 920
    return Part(f'blade_{s}', B_GRIP, bdeg(f'blade_{s}'), cubes=[
        Cube(-2, -40, -1, 4, 36, 2, blade_paint(seed)),                                            # Blatt
        Cube(-1.5, -43, -1, 3, 3, 2, tip_paint(seed + 1)),                                         # Keilspitze
        Cube(-1, -44, -1, 2, 1, 2, tip_paint(seed + 2)),
        Cube(-4, -5, -1.5, 8, 2, 3, bronze(0.66, seed + 3, round_x=0.0, seam=False)),               # Parierstange
        Cube(-1, -3, -1, 2, 7, 2, wrap(0.5, seed + 4)),                                            # Griff
        Cube(-1.5, 4, -1.5, 3, 2, 3, bronze(0.6, seed + 5, round_x=0.0, seam=False)),               # Knauf
    ])


def band(side):
    """Wehendes Ende der Stirnbinde: zwei Glieder (je 3 breit, 2 dick)."""
    s = 'l' if side > 0 else 'r'
    seed = 940 if side > 0 else 950
    tail = Part(f'band_{s}_1', (0, 7, 0), bdeg(f'band_{s}_1'), cubes=[
        Cube(-1.5, 0, -1, 3, 8, 2, purple(0.5, seed + 1, folds=False)),
    ])
    return Part(f'band_{s}_0', (side * 1.8, -10, 6.5), bdeg(f'band_{s}_0'), cubes=[
        Cube(-1.5, 0, -1, 3, 8, 2, purple(0.56, seed, folds=False)),
    ], children=[tail])


def b_arm(side):
    s = 'r' if side < 0 else 'l'
    seed = 960 if side < 0 else 980
    return Part(f'arm_{s}', (side * 13.5, -24, 0), bdeg(f'arm_{s}'), cubes=[
        Cube(-5 + side, -4, -5, 10, 4, 10, bronze(0.64, seed, round_x=0.2, rivets=0)),             # Schulterplatte
        Cube(-3.5, -1, -3.5, 7, 24, 7, stone_skin(0.56, seed + 1)),                               # Oberarm
        Cube(-4, 6, -4, 8, 2, 8, purple(0.5, seed + 2, folds=False)),                             # Purpurband
    ], children=[
        Part(f'fore_{s}', (0, 23, 0), bdeg(f'fore_{s}'), cubes=[
            Cube(-3.5, -1, -3.5, 7, 17, 7, leather(0.42, seed + 3)),                              # Lederschiene
            Cube(-4, 4, -4, 8, 9, 8, bronze(0.5, seed + 4, round_x=0.22)),                        # Armschiene
            Cube(-3.5, 16, -3.5, 7, 7, 7, stone_skin(0.5, seed + 5, round_x=0.1)),                # Faust
        ], children=[blade(side)]),
    ])


def b_leg(side):
    s = 'r' if side < 0 else 'l'
    seed = 1000 if side < 0 else 1020
    return Part(f'leg_{s}', (side * 5.5, 0, 0), bdeg(f'leg_{s}'), cubes=[
        Cube(-4.5, 0, -4.5, 9, 22, 9, stone_skin(0.52, seed)),                                    # Oberschenkel
        Cube(-4, 18, -5.5, 8, 5, 2, bronze(0.62, seed + 1, round_x=0.2, seam=False)),              # Kniekachel
    ], children=[
        Part(f'shin_{s}', (0, 22, 0), bdeg(f'shin_{s}'), cubes=[
            Cube(-4, 0, -4, 8, 18, 8, stone_skin(0.46, seed + 2)),                                # Wade
            Cube(-4.5, 1, -5, 9, 14, 6, bronze(0.54, seed + 3, round_x=0.26)),                     # Beinschiene
            Cube(-4.5, 18, -8, 9, 4, 12, leather(0.42, seed + 4)),                                 # Sandale/Fuß
            Cube(-4.5, 14, -5, 9, 4, 9, leather(0.5, seed + 5)),                                   # Riemen
        ]),
    ])


def bia():
    head = Part('head', (0, -28, 0), bdeg('head'), cubes=[
        Cube(-3, -3, -3, 6, 4, 6, stone_skin(0.44, 1040)),                                         # Hals
        Cube(-5.5, -14, -5.5, 11, 12, 11, bia_face),                                               # Kopf 11 x 12 x 11
        Cube(-5.5, -11, -7, 11, 2, 2, stone_skin(0.7, 1041, round_x=0.1, grad=0.3)),               # Brauenwulst
        Cube(-1, -8, -7, 2, 3, 2, stone_skin(0.74, 1042, round_x=0.0, grad=0.2)),                  # Nase
        Cube(-6, -13, -6, 12, 3, 12, purple(0.5, 1043, folds=False)),                             # Stirnbinde
        Cube(-2, -13, 6, 4, 4, 2, purple(0.4, 1044, folds=False)),                                # Knoten
    ], children=[band(-1), band(1)])

    chest = Part('chest', (0, -6, 0), bdeg('chest'), cubes=[
        Cube(-10, -12, -7, 20, 12, 14, baldric(cuirass('lower', 1060), -10, -12, 20, 1064)),       # Taille 20
        Cube(-13, -28, -7, 26, 16, 14, baldric(cuirass('upper', 1061), -13, -28, 26, 1065)),      # Brust (Schultern 32)
        Cube(-6, -29, -6, 12, 2, 12, bronze(0.46, 1062, round_x=0.2)),                             # Halsring
        Cube(-11, -1, -8, 22, 3, 16, purple(0.5, 1063, folds=False)),                             # Schärpe
        Cube(5, -2, -9, 5, 4, 2, purple(0.42, 1066, folds=False)),                                # Knoten der Schärpe
    ], children=[head, b_arm(-1), b_arm(1)])

    body = Part('body', (0, -20, 0), bdeg('body'), cubes=[
        Cube(-10, -6, -7, 20, 8, 14, leather(0.38, 1080)),                                        # Becken
        Cube(-6, 1, -8, 12, 12, 2, purple(0.46, 1081, hem=True)),                                 # Lendentuch vorn
        Cube(-7, 1, 6, 14, 10, 2, purple(0.4, 1082, hem=True)),                                   # Lendentuch hinten
    ], children=[chest, b_leg(-1), b_leg(1)])
    return Model('bia', 256, 256, [body])


# =====================================================================================================================
# Posen KRATOS (Radiant, absolute Knochendrehung; sechs Zahlen = Drehung + Versatz). Dieselben Werte stehen in
# KratosModel.kt. Arme greifen per IK, Beine stellen die Füße per IK auf den Boden (y = 24).
# =====================================================================================================================

K_RIG = Rig(kratos())
B_RIG = Rig(bia())
PROPS = ('hammer', 'shield')
SOLE_K = (0, 20, -2)          # Sohlenmitte im Schienbeinraum (Kratos)
SOLE_B = (0, 22, -2)          # dito Bia


def leg_ik(rig, pose, side, target, sole, knee=None):
    """Stellt die Sohle auf target (Kniebeuge nur nach hinten: Schienbein-pitch 0 … 2,6)."""
    s = 'r' if side < 0 else 'l'
    leg, shin = f'leg_{s}', f'shin_{s}'
    names = [(leg, 0), (leg, 1), (leg, 2), (shin, 0)]
    start = [pose[n][k] for n, k in names]

    def put(vals):
        for (n, k), v in zip(names, vals):
            cur = list(pose[n])
            cur[k] = v
            pose[n] = tuple(cur)

    def cost(vals):
        put(vals)
        if not -0.02 <= vals[3] <= 2.6:
            return 1e9
        d = _sub(rig.world(pose, shin, sole), target)
        c = _dot(d, d) + 0.05 * sum((a - b) ** 2 for a, b in zip(vals, start)) + 2.0 * vals[1] ** 2
        if knee is not None:
            e = _sub(rig.world(pose, shin), knee)
            c += 0.03 * _dot(e, e)
        return c

    best = list(start)
    best_c = cost(best)
    for step in (0.4, 0.15, 0.05, 0.015, 0.005):
        for _ in range(80):
            improved = False
            for i in range(4):
                for sgn in (1, -1):
                    trial = list(best)
                    trial[i] += sgn * step
                    c = cost(trial)
                    if c < best_c:
                        best, best_c, improved = trial, c, True
            if not improved:
                break
    put(best)


def _kdict():
    return {name: tuple(K_IDLE.get(name, (0.0, 0.0, 0.0))) for name in K_RIG.order()}


def hold_hammer(pose, grip, up, face, elbow=None, ew=0.04):
    """Rechte Faust bei grip; der Hammerkopf liegt in Richtung up, die Schlagfläche zeigt nach face."""
    K_RIG.ik(pose, 'arm_r', 'fore_r', GRIP_R, grip, elbow, elbow_weight=ew)
    K_RIG.aim(pose, 'hammer', _scale(_norm(up), -1), _scale(_norm(face), -1))


def hold_shield(pose, grip, down=(0, 1, 0), front=(0, 0, -1), elbow=None, ew=0.04):
    """Linke Faust am Schildgriff bei grip; +y des Schilds zeigt nach down, die Schauseite nach front."""
    K_RIG.ik(pose, 'arm_l', 'fore_l', GRIP_L, grip, elbow, elbow_weight=ew)
    K_RIG.aim(pose, 'shield', down, _scale(_norm(front), -1))


def pin(pose, name, origin, ydir, zhint, pins):
    """Requisit liegt oder steht frei: Ursprung auf origin, +y nach ydir, +z nach zhint. Merkt die Weltlage."""
    K_RIG.aim(pose, name, ydir, zhint)
    K_RIG.place(pose, name, origin)
    pins.add(name)


def left_on_hammer(pose, along, elbow=None):
    """Linke Faust greift den Stiel along Einheiten von der rechten Faust entfernt (+ zum Knauf, - zum Kopf)."""
    rot, pos = K_RIG.fk(pose, 'hammer')
    K_RIG.ik(pose, 'arm_l', 'fore_l', GRIP_L, _add(pos, _apply(rot, (0, along, 0))), elbow, elbow_weight=0.06)


def right_to(pose, target, elbow=None, ew=0.04):
    K_RIG.ik(pose, 'arm_r', 'fore_r', GRIP_R, target, elbow, elbow_weight=ew)


def feet(pose, r=(-9, 24, 0), l=(9, 24, 0), knee_r=None, knee_l=None):
    leg_ik(K_RIG, pose, -1, r, SOLE_K, knee_r)
    leg_ik(K_RIG, pose, 1, l, SOLE_K, knee_l)


K_POSES_RAW = {}
K_PINS = {}


def kpose(name, base=None, **bones):
    p = dict(K_POSES_RAW[base]) if base else _kdict()
    p.update(bones)
    K_POSES_RAW[name] = p
    K_PINS[name] = set()
    return p


HAMMER_HEAD = 39.0            # Abstand Faust -> Mitte des Hammerkopfs
HAMMER_TOP = 49.0             # Abstand Faust -> Spitze oben auf dem Kopf


def planted_hammer(pose, x, z, pins):
    """Hammer senkrecht abgestellt: Kopf auf dem Boden, Stiel nach oben."""
    pin(pose, 'hammer', (x, 24 - HAMMER_TOP, z), (0, -1, 0), (0, 0, -1), pins)


def lowered_shield(pose):
    """Schild hängt an der linken Seite, Unterkante kurz über dem Boden, Schauseite nach vorn-außen."""
    hold_shield(pose, (22, -12, -6), down=(0.08, 1, 0.0), front=(0.45, 0, -1), elbow=(30, -36, 4), ew=0.15)


# --- Ruhe -------------------------------------------------------------------------------------------------------------

def _idle():
    p = kpose('idle')
    # Hammer geschultert: Faust vor der rechten Brust, der Stiel liegt auf dem Schulterkeil, Kopf hinter der Schulter.
    hold_hammer(p, (-15, -44, -16), up=(-0.18, -0.74, 0.65), face=(-0.2, 0.6, 0.75), elbow=(-30, -42, -2), ew=0.2)
    # Schild vor der linken Körperhälfte, Unterkante knapp über dem Boden, leicht nach außen gedreht.
    hold_shield(p, (14, -24, -16), down=(0.0, 1, 0.08), front=(0.25, 0, -1), elbow=(30, -40, -4), ew=0.2)


def _idle_shieldless():
    p = kpose('idle_shieldless', 'idle', shield=None)
    # Phase II: Hammer schräg vor dem Leib, Kopf oben links, beide Fäuste am Stiel.
    hold_hammer(p, (-10, -28, -18), up=(0.55, -0.8, -0.2), face=(0.2, 0.0, -1), elbow=(-30, -36, -6), ew=0.1)
    left_on_hammer(p, -15, (30, -40, -8))


# --- Schildstoß ------------------------------------------------------------------------------------------------------

def _bash_windup():
    p = kpose('bash_windup', 'idle', body=(0.0, 0.35, 0.0, 0.0, 7.0, 3.0), chest=(0.32, 0.3, 0.0),
              head=(-0.1, -0.5, 0.0), skirt_f=(-0.45, 0.0, 0.0), skirt_b=(0.2, 0.0, 0.0))
    feet(p, r=(-12, 24, 10), l=(10, 24, -18))
    # Schild eng an die linke Schulter gezogen, Oberkante auf Augenhöhe; der Kopf duckt sich dahinter.
    hold_shield(p, (8, -44, -22), down=(0.0, 1, 0.12), front=(-0.25, 0, -1), elbow=(30, -40, -4), ew=0.15)
    # Hammer nach hinten weggestreckt.
    hold_hammer(p, (-26, -28, 22), up=(-0.3, -0.35, 0.89), face=(-0.95, 0, -0.3), elbow=(-34, -46, 10), ew=0.1)


def _bash_hit():
    p = kpose('bash_hit', 'idle', body=(0.0, 0.05, 0.0, 0.0, 4.0, -9.0), chest=(0.28, -0.12, 0.0),
              head=(-0.2, 0.05, 0.0), skirt_f=(-0.35, 0.0, 0.0), skirt_b=(0.45, 0.0, 0.0))
    feet(p, r=(-11, 24, 22), l=(10, 24, -26))
    hold_shield(p, (8, -42, -42), down=(0.0, 1, 0.05), front=(0.0, 0, -1), elbow=(26, -48, -18), ew=0.1)
    hold_hammer(p, (-28, -26, 14), up=(-0.4, -0.5, 0.77), face=(-0.9, 0, -0.4), elbow=(-34, -44, 4), ew=0.1)


def _bash_open():
    p = kpose('bash_open', 'idle', body=(0.0, 0.0, 0.0, 0.0, 4.0, -6.0), chest=(0.5, -0.05, 0.08),
              head=(0.15, 0.1, 0.0), skirt_f=(-0.3, 0.0, 0.0))
    feet(p, r=(-11, 24, 16), l=(10, 24, -20))
    # Der Schildarm hängt: die Unterkante schleift am Boden, die Brust ist offen.
    hold_shield(p, (26, -2, -22), down=(0.35, 1, -0.2), front=(0.8, 0.0, -0.6), elbow=(32, -26, -8), ew=0.15)
    hold_hammer(p, (-24, -6, -10), up=(-0.25, 0.25, -0.93), face=(0.0, 1, 0.0), elbow=(-30, -28, 2), ew=0.1)


# --- Hammerfall ------------------------------------------------------------------------------------------------------

def _smash_windup():
    p = kpose('smash_windup', 'idle', body=(0.0, 0.2, 0.0, 0.0, 2.0, 2.0), chest=(-0.32, 0.2, 0.0),
              head=(-0.25, -0.2, 0.0), skirt_f=(-0.2, 0.0, 0.0), skirt_b=(0.1, 0.0, 0.0))
    feet(p, r=(-11, 24, 12), l=(10, 24, -18))
    # Hammer einhändig hoch über dem Kopf, der Kopf hängt hinten herab.
    hold_hammer(p, (-8, -98, 4), up=(0.0, 0.2, 1.0), face=(0.0, -1, 0.2), elbow=(-30, -80, -6), ew=0.08)
    # Schild vorgestreckt zum Ausgleich.
    hold_shield(p, (14, -40, -26), down=(0.0, 1, 0.1), front=(0.3, 0, -1), elbow=(30, -44, -6), ew=0.1)


def _smash_hit():
    p = kpose('smash_hit', 'idle', body=(0.0, 0.1, 0.0, 0.0, 9.0, -4.0), chest=(0.85, 0.05, 0.0),
              head=(-0.55, 0.0, 0.0), skirt_f=(-0.75, 0.0, 0.0), skirt_b=(0.4, 0.0, 0.0))
    feet(p, r=(-12, 24, 14), l=(10, 24, -22))
    # Der Kopf schlägt vor ihm auf: Schlagfläche nach unten.
    up = _norm((0.0, 0.62, -0.78))
    head = (-6, 18, -50)
    hold_hammer(p, _sub(head, _scale(up, HAMMER_HEAD)), up=up, face=(0, 1, 0.1), elbow=(-30, -40, -14), ew=0.06)
    hold_shield(p, (22, -10, -20), down=(0.3, 1, 0.0), front=(0.7, 0.0, -0.7), elbow=(32, -34, -6), ew=0.1)


def _smash_stuck():
    p = kpose('smash_stuck', 'smash_hit', body=(-0.12, 0.25, 0.0, 0.0, 6.0, 5.0), chest=(0.22, 0.15, 0.0),
              head=(0.1, -0.15, 0.0), skirt_f=(-0.3, 0.0, 0.0), skirt_b=(0.1, 0.0, 0.0))
    feet(p, r=(-12, 24, 16), l=(10, 24, -16))
    # Der Hammer steckt, wo der Schlag ihn hingetrieben hat (frei, bleibt liegen); er zerrt mit der Rechten am Stiel.
    rot, pos = K_RIG.fk(K_POSES_RAW['smash_hit'], 'hammer')
    stuck = _add(pos, (0, 3, 0))
    right_to(p, stuck, (-30, -36, -10))
    K_RIG.set_world_rot(p, 'hammer', rot)
    K_RIG.place(p, 'hammer', stuck)
    K_PINS['smash_stuck'].add('hammer')
    hold_shield(p, (24, -12, -14), down=(0.3, 1, 0.0), front=(0.7, 0.0, -0.7), elbow=(32, -34, -2), ew=0.1)


def _smash_2h():
    """Phase II: dieselben Posen beidhändig (linke Faust 12 tiefer am Stiel)."""
    for name in ('smash_windup', 'smash_hit'):
        p = kpose(name + '_2h', name, shield=None)
        left_on_hammer(p, 12, (26, -50, -6) if name == 'smash_windup' else (28, -36, -18))
    p = kpose('smash_stuck_2h', 'smash_stuck', shield=None)
    K_PINS['smash_stuck_2h'].add('hammer')
    left_on_hammer(p, 12, (28, -36, -10))


# --- Befehle: Hammer abgestellt, die rechte Hand befiehlt --------------------------------------------------------------

def _cmd_back():
    p = kpose('cmd_back', 'idle', body=(0.0, 0.0, 0.0, 0.0, -1.0, 0.0), chest=(0.05, 0.25, 0.0),
              head=(-0.3, -0.2, 0.0), fingers_r=(0.0, 0.0, 0.0))
    feet(p, r=(-11, 24, -10), l=(10, 24, 8))
    # „WEICHT!": Arm gestreckt nach vorn, die Hand flach mit aufgestellten Fingern.
    right_to(p, (-12, -66, -44), (-22, -64, -24), ew=0.1)
    K_RIG.aim(p, 'fingers_r', (0, -1, 0.25), (0, 0, 1))
    lowered_shield(p)
    planted_hammer(p, -24, -14, K_PINS['cmd_back'])


def _cmd_come_wave():
    p = kpose('cmd_come_wave', 'idle', body=(0.0, -0.1, 0.0, 0.0, 0.0, 0.0), chest=(-0.05, -0.15, 0.0),
              head=(0.05, 0.1, 0.0), fingers_r=(-1.4, 0.0, 0.0))
    feet(p)
    # „HERAN!", erster Teil: Arm weit vorgestreckt, Handfläche nach oben, die Finger winken.
    right_to(p, (-18, -44, -48), (-30, -48, -22), ew=0.1)
    lowered_shield(p)
    planted_hammer(p, -24, -14, K_PINS['cmd_come_wave'])


def _cmd_come_fist():
    p = kpose('cmd_come_fist', 'idle', body=(0.0, -0.05, 0.0, 0.0, 0.0, 3.0), chest=(-0.24, -0.3, 0.0),
              head=(0.2, 0.25, 0.0), fingers_r=(-2.6, 0.0, 0.0))
    feet(p)
    # Zweiter Teil: die Faust an die Brust gerissen, Ellbogen weit nach außen.
    right_to(p, (2, -56, -16), (-38, -60, -2), ew=0.4)
    lowered_shield(p)
    planted_hammer(p, -24, -14, K_PINS['cmd_come_fist'])


def _cmd_kneel():
    p = kpose('cmd_kneel', 'idle', body=(0.12, 0.0, 0.0, 0.0, 4.0, 2.0), chest=(0.5, 0.1, 0.0),
              head=(0.45, -0.1, 0.0), fingers_r=(0.0, 0.0, 0.0), skirt_f=(-0.45, 0.0, 0.0))
    feet(p, r=(-11, 24, 4), l=(10, 24, -6))
    # „KNIET!": gestreckter Arm, die flache Hand zeigt senkrecht in den Boden vor ihm.
    right_to(p, (-12, -2, -40), (-16, -26, -34), ew=0.15)
    K_RIG.aim(p, 'fingers_r', (0, 1, 0.0), (0, 0, 1))
    lowered_shield(p)
    planted_hammer(p, -26, -10, K_PINS['cmd_kneel'])


# --- Taumeln, Schild weg, Konter, Aufgeben ---------------------------------------------------------------------------


def _stagger():
    p = kpose('stagger', 'idle', body=(0.0, 0.25, 0.18, 0.0, 15.0, 2.0), chest=(0.45, 0.1, -0.12),
              head=(0.3, 0.3, 0.1), skirt_f=(-0.9, 0.0, 0.0), skirt_b=(0.2, 0.0, 0.0))
    # Auf dem rechten Knie, links aufgestellt.
    feet(p, r=(-12, 24, 30), l=(12, 24, -16), knee_r=(-12, 20, 0), knee_l=(12, -2, -18))
    # Rechte Faust stützt sich auf den Boden, linke Hand auf dem Knie.
    right_to(p, (-26, 18, -10), (-34, -20, -4), ew=0.08)
    K_RIG.ik(p, 'arm_l', 'fore_l', GRIP_L, (14, -6, -24), (32, -30, -6), elbow_weight=0.06)
    # Schild liegt flach links neben ihm, Hammer vorn rechts am Boden.
    pin(p, 'shield', (40, 15, -14), (0.25, 0, -1), (0, 1, 0), K_PINS['stagger'])
    pin(p, 'hammer', (-30, 22.5, -26), (0.5, 0, 0.85), (0, 1, 0), K_PINS['stagger'])


def _drop_shield():
    # Ausholen: der Schild wird quer vor den Leib gerissen ...
    p = kpose('drop_shield_rip', 'idle', body=(0.0, -0.3, 0.0, 0.0, 3.0, 0.0), chest=(0.15, -0.45, 0.0),
              head=(0.0, 0.35, 0.0))
    feet(p, r=(-11, 24, -4), l=(11, 24, 6))
    hold_shield(p, (-8, -46, -20), down=(0.4, 1, 0.0), front=(-0.6, 0, -1), elbow=(20, -44, -22), ew=0.15)
    hold_hammer(p, (-24, -16, -8), up=(-0.2, -0.95, 0.2), face=(0, 0, -1), elbow=(-30, -36, 4), ew=0.1)
    # ... und zur Seite geschleudert (danach ist er aus, ab Tick 16).
    p = kpose('drop_shield', 'idle', body=(0.0, 0.35, 0.0, 0.0, 4.0, 0.0), chest=(0.1, 0.55, -0.1),
              head=(0.0, -0.6, 0.0))
    feet(p, r=(-11, 24, 4), l=(13, 24, -6))
    hold_shield(p, (46, -50, -6), down=(0.9, 0.2, 0.3), front=(0.3, -0.7, -0.6), elbow=(34, -60, -4), ew=0.1)
    hold_hammer(p, (-22, -18, -10), up=(-0.2, -0.95, 0.2), face=(0, 0, -1), elbow=(-30, -36, 4), ew=0.1)


def _shield_up():
    p = kpose('shield_up_windup', 'idle', body=(0.0, 0.0, 0.0, 0.0, 9.0, 0.0), chest=(0.12, 0.0, 0.0),
              head=(-0.3, 0.0, 0.0), skirt_f=(-0.5, 0.0, 0.0), skirt_b=(0.4, 0.0, 0.0))
    feet(p, r=(-14, 24, 2), l=(14, 24, -4), knee_r=(-18, 0, -14), knee_l=(18, 0, -14))
    # Schild waagerecht über dem Kopf, Schauseite nach oben.
    hold_shield(p, (8, -80, -6), down=(0, 0, 1), front=(0, -1, 0), elbow=(30, -66, -6), ew=0.1)
    hold_hammer(p, (-24, -20, -8), up=(-0.25, -0.9, 0.3), face=(0, 0, -1), elbow=(-30, -36, 4), ew=0.1)
    p = kpose('shield_up_hit', 'shield_up_windup', body=(0.0, 0.0, 0.0, 0.0, -3.0, 0.0), chest=(-0.08, 0.0, 0.0),
              head=(-0.45, 0.0, 0.0), skirt_f=(-0.05, 0.0, 0.0), skirt_b=(0.1, 0.0, 0.0))
    feet(p, r=(-13, 24, 2), l=(13, 24, -4))
    hold_shield(p, (6, -104, -4), down=(0, 0, 1), front=(0, -1, 0), elbow=(26, -86, -4), ew=0.1)
    hold_hammer(p, (-26, -34, -4), up=(-0.25, -0.9, 0.3), face=(0, 0, -1), elbow=(-32, -50, 4), ew=0.1)
    # Ohne Schild: der Hammer quer über dem Kopf, beidhändig.
    for name, y in (('shield_up_windup', -80), ('shield_up_hit', -100)):
        p = kpose(name + '_shieldless', name, shield=None)
        hold_hammer(p, (-12, y, -6), up=(1, 0, 0), face=(0, -1, 0), elbow=(-32, y + 16, -6), ew=0.1)
        left_on_hammer(p, -20, (32, y + 16, -6))


def _yield():
    p = kpose('yield', 'idle', body=(0.0, 0.1, 0.0, 4.0, 1.0, 2.0), chest=(0.12, -0.1, 0.0), head=(0.35, 0.0, 0.0),
              fingers_r=(-2.6, 0.0, 0.0))
    feet(p, r=(-6, 24, 0), l=(16, 24, 2))
    # Faust an die Brust (Gruß der Wache), der Schild ruht unten.
    right_to(p, (4, -50, -15), (-30, -44, -12), ew=0.2)
    lowered_shield(p)
    planted_hammer(p, -22, -16, K_PINS['yield'])


_idle()
for _fn in (_idle_shieldless, _bash_windup, _bash_hit, _bash_open, _smash_windup, _smash_hit, _smash_stuck,
            _smash_2h, _cmd_back, _cmd_come_wave, _cmd_come_fist, _cmd_kneel, _stagger, _drop_shield, _shield_up,
            _yield):
    _fn()

# Der Mantel hängt in jeder Pose nach der Schwerkraft (Weltrichtung je Glied); nach vorn gerissene Bewegungen lassen
# ihn nach hinten wehen.
CAPE_FALL = {
    'bash_hit': ((0, 1, 0.75), (0, 1, 0.55)), 'bash_windup': ((0, 1, 0.3), (0, 1, 0.2)),
    'smash_hit': ((0, 1, 0.5), (0, 1, 0.25)), 'smash_hit_2h': ((0, 1, 0.5), (0, 1, 0.25)),
    'smash_windup': ((0, 1, 0.35), (0, 1, 0.2)), 'smash_windup_2h': ((0, 1, 0.35), (0, 1, 0.2)),
    'drop_shield': ((-0.3, 1, 0.45), (-0.35, 1, 0.3)), 'stagger': ((0.3, 1, 0.3), (0.2, 1, 0.6)),
}
for _name, _p in K_POSES_RAW.items():
    _d0, _d1 = CAPE_FALL.get(_name, ((0, 1, 0.16), (0, 1, 0.08)))
    K_RIG.aim(_p, 'cape', _d0, (0, 0, 1))
    K_RIG.aim(_p, 'cape_1', _d1, (0, 0, 1))
K_IDLE.update(K_POSES_RAW['idle'])


def _rounded(v):
    return None if v is None else tuple(round(float(x), 4) for x in v)


def _finish(raw):
    return {name: {k: _rounded(v) for k, v in pose.items()} for name, pose in raw.items()}


POSES = _finish(K_POSES_RAW)

# =====================================================================================================================
# Posen BIA
# =====================================================================================================================

B_POSES_RAW = {}


def _bdict():
    return {name: tuple(B_IDLE.get(name, (0.0, 0.0, 0.0))) for name in B_RIG.order()}


def bpose(name, base=None, **bones):
    p = dict(B_POSES_RAW[base]) if base else _bdict()
    p.update(bones)
    B_POSES_RAW[name] = p
    return p


def bfeet(pose, r=(-6, 24, 0), l=(6, 24, 0), knee_r=None, knee_l=None):
    leg_ik(B_RIG, pose, -1, r, SOLE_B, knee_r)
    leg_ik(B_RIG, pose, 1, l, SOLE_B, knee_l)


def bhand(pose, side, target, elbow=None, ew=0.06):
    s = 'r' if side < 0 else 'l'
    B_RIG.ik(pose, f'arm_{s}', f'fore_{s}', B_GRIP, target, elbow, elbow_weight=ew)


def bblade(pose, side, tip_dir, edge=(0, 0, -1)):
    """Klinge zeigt mit der Spitze nach tip_dir; die Klingenfläche (+z) nach edge."""
    s = 'r' if side < 0 else 'l'
    B_RIG.aim(pose, f'blade_{s}', _scale(_norm(tip_dir), -1), edge)


def bbands(pose, d0, d1):
    """Stirnbinde: Weltrichtung der Glieder (+y des Glieds zeigt nach d)."""
    for s, sx in (('l', 1), ('r', -1)):
        B_RIG.aim(pose, f'band_{s}_0', _add(d0, (0.12 * sx, 0, 0)), (sx, 0, 0))
        B_RIG.aim(pose, f'band_{s}_1', _add(d1, (0.18 * sx, 0, 0)), (sx, 0, 0))


def _statue():
    p = bpose('statue', head=(0.42, 0.0, 0.0), chest=(0.02, 0.0, 0.0))
    # Klingen vor der Brust gekreuzt: die Handgelenke kreuzen sich, die Spitzen ragen über die Schultern.
    bhand(p, -1, (4, -44, -12), (-18, -36, -4), 0.1)
    bhand(p, 1, (-4, -45, -14), (18, -36, -4), 0.1)
    bblade(p, -1, (0.5, -0.86, 0.05))
    bblade(p, 1, (-0.5, -0.86, 0.05))
    bbands(p, (0, 0.9, 0.35), (0, 1, 0.15))


def _leap_windup():
    p = bpose('leap_windup', 'statue', body=(0.25, 0.0, 0.0, 0.0, 14.0, 4.0), chest=(0.55, 0.0, 0.0),
              head=(-0.95, 0.0, 0.0))
    bfeet(p, r=(-8, 24, 8), l=(8, 24, -10))
    # Tief in der Hocke, die Klingen weit nach hinten gestreckt.
    bhand(p, -1, (-18, -12, 20), (-18, -36, 6))
    bhand(p, 1, (18, -12, 20), (18, -36, 6))
    bblade(p, -1, (-0.2, 0.25, 1))
    bblade(p, 1, (0.2, 0.25, 1))
    bbands(p, (0, 0.6, 0.8), (0, 0.8, 0.6))


def _rush():
    p = bpose('rush', 'statue', body=(0.55, 0.0, 0.0, 0.0, 16.0, 4.0), chest=(0.85, 0.0, 0.0),
              head=(-1.15, 0.0, 0.0))
    bfeet(p, r=(-6, 24, 34), l=(6, 16, -14), knee_l=(6, -6, -24))
    # Fast waagerecht, beide Klingen nach vorn zusammengeführt wie eine Speerspitze.
    bhand(p, -1, (-3, -20, -58), (-14, -24, -34))
    bhand(p, 1, (3, -20, -58), (14, -24, -34))
    bblade(p, -1, (0.06, -0.08, -1), (1, 0, 0))
    bblade(p, 1, (-0.06, -0.08, -1), (-1, 0, 0))
    bbands(p, (0, -0.05, 1), (0, -0.02, 1))


def _sweep():
    p = bpose('sweep_windup', 'statue', body=(0.0, 0.0, 0.0, 0.0, 6.0, 0.0), chest=(0.2, 0.0, 0.0),
              head=(-0.1, 0.0, 0.0))
    bfeet(p, r=(-10, 24, 4), l=(10, 24, -4))
    # Klingen waagerecht auf Brusthöhe seitlich gestreckt, noch leicht nach hinten gezogen.
    bhand(p, -1, (-40, -40, 10), (-24, -44, 4))
    bhand(p, 1, (40, -40, 10), (24, -44, 4))
    bblade(p, -1, (-1, 0, 0.35), (0, -1, 0))
    bblade(p, 1, (1, 0, 0.35), (0, -1, 0))
    bbands(p, (0, 0.85, 0.5), (0, 1, 0.2))
    p = bpose('sweep_run', 'sweep_windup', body=(0.0, 0.0, 0.0, 0.0, 3.0, 0.0), chest=(0.3, 0.0, 0.0),
              head=(-0.2, 0.0, 0.0))
    bfeet(p, r=(-6, 24, 18), l=(6, 20, -16), knee_l=(6, 0, -18))
    bhand(p, -1, (-42, -42, -4), (-24, -46, 2))
    bhand(p, 1, (42, -42, -4), (24, -46, 2))
    bblade(p, -1, (-1, 0, -0.05), (0, -1, 0))
    bblade(p, 1, (1, 0, -0.05), (0, -1, 0))
    bbands(p, (0, 0.2, 1), (0, 0.1, 1))


def _bstagger():
    p = bpose('stagger', 'statue', body=(-0.35, 0.0, 0.0, 0.0, 38.0, 6.0), chest=(-0.25, 0.0, 0.0),
              head=(0.35, 0.0, 0.0))
    bfeet(p, r=(-8, 24, -36), l=(8, 22, -28), knee_l=(8, 6, -24))
    # Sitzt rücklings, stützt sich auf die Fäuste; die Klingen liegen flach neben ihr.
    bhand(p, -1, (-16, 20, 16), (-20, 0, 6))
    bhand(p, 1, (16, 20, 16), (20, 0, 6))
    bblade(p, -1, (-0.35, 0, -1), (0, -1, 0))
    bblade(p, 1, (0.35, 0, -1), (0, -1, 0))
    bbands(p, (0, 1, 0.2), (0, 1, 0))


def _land():
    p = bpose('land', 'statue', body=(0.0, 0.0, 0.0, 0.0, 10.0, 2.0), chest=(0.55, 0.0, 0.0), head=(-0.3, 0.0, 0.0))
    bfeet(p, r=(-8, 24, 6), l=(8, 24, -6))
    bhand(p, -1, (-22, -14, -10), (-20, -30, 4))
    bhand(p, 1, (22, -14, -10), (20, -30, 4))
    bblade(p, -1, (-0.3, 0.2, 1))
    bblade(p, 1, (0.3, 0.2, 1))
    bbands(p, (0, -0.4, 0.9), (0, -0.2, 1))


for _fn in (_statue, _leap_windup, _rush, _sweep, _bstagger, _land):
    _fn()
B_IDLE.update(B_POSES_RAW['statue'])
POSES_BIA = _finish(B_POSES_RAW)

# Für die Größenprüfung in preview.py: Zubehör, das über den Scheitel hinausragen darf.
HEIGHT_EXCLUDE = ('crest', 'hammer', 'shield')
HEIGHT_EXCLUDE_BIA = ('blade_r', 'blade_l', 'band_l_0', 'band_r_0')


# =====================================================================================================================
# Kotlin-Abgleich: schreibt Knochenliste, Posentabellen und frei liegende Requisiten nach KratosModel.kt
# =====================================================================================================================

KOTLIN = os.path.join(HERE, '..', '..', 'src', 'main', 'kotlin', 'dev', 'henny', 'hha', 'client', 'model',
                      'KratosModel.kt')
K_SKIP = ('idle',)            # Grundhaltung des Modells (steht als Grundrotation in den Modelldaten)
B_SKIP = ('statue',)


def _f(x):
    return f'{x:.4f}f'


def _table(rig, poses, base, skip, pins=None):
    order = rig.order()
    lines = [f'        val BONES = arrayOf({", ".join(chr(34) + b + chr(34) for b in order)})',
             f'        val PARENTS = intArrayOf({", ".join(str(order.index(rig.parent[b])) if rig.parent[b] else "-1" for b in order)})']
    if pins is not None:
        lines.append(f'        val PROPS = arrayOf({", ".join(chr(34) + p + chr(34) for p in PROPS)})')
    for name, pose in poses.items():
        if name in skip:
            continue
        vals = []
        for b in order:
            v = pose.get(b)
            if v is None or tuple(v) == tuple(base[b]):
                vals.append('N, N, N, N, N, N')
            else:
                v = tuple(v) + (0.0, 0.0, 0.0) if len(v) == 3 else tuple(v)
                vals.append(', '.join(_f(x) for x in v))
        lines.append(f'        val {name.upper()} = ThronePose(floatArrayOf(')
        for i in range(0, len(vals), 2):
            lines.append('            ' + ', '.join(vals[i:i + 2]) + ',')
        pinned = pins.get(name) if pins else None
        if pinned:
            raw = K_POSES_RAW[name]
            pv = []
            for prop in PROPS:
                if prop in pinned:
                    rot, pos = K_RIG.fk(raw, prop)
                    pv.append(', '.join(_f(x) for x in tuple(euler(rot)) + tuple(pos)))
                else:
                    pv.append('N, N, N, N, N, N')
            lines.append(f'        ), floatArrayOf({", ".join(pv)}))')
        else:
            lines.append('        ))')
    return '\n'.join(lines)


def _sync(src, tag, body):
    begin = f'        // {tag} BEGIN (generiert aus tools/hha_assets/kratos_model.py --kotlin)'
    end = f'        // {tag} END'
    pattern = re.compile(re.escape(begin) + '.*?' + re.escape(end), re.S)
    if not pattern.search(src):
        raise SystemExit(f'Marken {tag} fehlen in KratosModel.kt')
    return pattern.sub(lambda _: begin + '\n' + body + '\n' + end, src)


def sync_kotlin():
    with open(KOTLIN, encoding='utf-8') as f:
        src = f.read()
    src = _sync(src, 'KRATOS POSES', _table(K_RIG, POSES, POSES['idle'], K_SKIP, K_PINS))
    src = _sync(src, 'BIA POSES', _table(B_RIG, POSES_BIA, POSES_BIA['statue'], B_SKIP))
    with open(KOTLIN, 'w', encoding='utf-8', newline='\n') as f:
        f.write(src)
    print(f'Posentabellen geschrieben: Kratos {len(POSES) - 1} Posen / {len(K_RIG.order())} Knochen, '
          f'Bia {len(POSES_BIA) - 1} Posen / {len(B_RIG.order())} Knochen')


if __name__ == '__main__':
    if '--kotlin' in sys.argv:
        sync_kotlin()
