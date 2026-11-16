"""Der Kerkermeister (Wächter der Bronzefeste): ein Hundertarmiger in Bronze, vier Arme, Rückenfächer aus sieben
greifenden Händen und die Laterne als Schwachstelle. Modell, Textur, Leuchtmaske und POSES.

Teil von boss_models.py (Aufruf dort mit --only warden_of_chains). Direkt aufgerufen schreibt diese Datei die
Posentabelle in client/model/WardenModel.kt (zwischen den Marken), damit Vorschau und Spiel dieselben Winkel benutzen:

    python tools/hha_assets/warden_model.py --kotlin

Koordinaten: 1/16 Block vor dem Renderer-Faktor 0,70, +y nach unten, -z vorn, Füße bei y = 24, Scheitel bei -72.
Die rechte Körperseite liegt bei -x.
"""
import math
import os
import re
import sys

from bosskit import IRON, Cube  # noqa: F401
from guardian_kit import FACE_LIGHT, VIOLET
from modelkit import Model, Part, hexc, mix, ramp, rgb, speckle

# =====================================================================================================================
# Material
# =====================================================================================================================

BRZ = rgb('#2a1608', '#4a2c12', '#7a4a1c', '#9a6a32', '#c98a3c', '#f0c878')
VERD = rgb('#14261f', '#24493c', '#3a6e58')                      # Grünspan, nur in Fugen
LEATH = rgb('#1e0709', '#330c0e', '#4c1315', '#621a1a', '#701e1e')
IRN = rgb('#0b0c11', '#16181f', '#23262f', '#363a45', '#555a66', '#80868f')
SIDES = ('front', 'back', 'left', 'right')


def _base(face, x, y, w, h, base, seed, grad, round_x):
    v = base + FACE_LIGHT[face] + grad * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.07
    if face in SIDES and w > 3 and round_x:
        cx = (w - 1) / 2.0
        v -= round_x * ((x - cx) / cx) ** 2
    return v


def bronze(base=0.55, seed=0, grad=0.16, round_x=0.14, rim=True, seam=True, rivets=0):
    """Warme Bronze: Licht oben, heller Grat an der Oberkante, Fuge mit Grünspan an der Unterkante, Nietreihe."""
    def paint(face, x, y, w, h):
        v = _base(face, x, y, w, h, base - 0.08, seed, grad, round_x)
        if face == 'top':
            if x in (0, w - 1) or y in (0, h - 1):
                v += 0.12
        elif face in SIDES:
            if rim and y == 0:
                v += 0.22
            elif seam and y == h - 1 and h >= 4:
                if speckle(x, 7, seed + 5) > 0.55:
                    return ramp(VERD, 0.35 + FACE_LIGHT[face])
                return ramp(BRZ, 0.06)
            if rivets and h >= 6 and w >= 6 and y in (2, 3) and (x - 2) % rivets in (0, 1) and 1 < x < w - 2:
                return ramp(BRZ, 0.92 if (y == 2 and (x - 2) % rivets == 0) else 0.7)
        return ramp(BRZ, v)
    return paint


def iron(base=0.45, seed=0, grad=0.14, rim=True):
    def paint(face, x, y, w, h):
        v = _base(face, x, y, w, h, base, seed, grad, 0.1)
        if face in SIDES and rim and y == 0:
            v += 0.2
        if face == 'top' and (x in (0, w - 1) or y in (0, h - 1)):
            v += 0.1
        return ramp(IRN, v)
    return paint


def leather(base=0.5, seed=0, studs=False):
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + 0.1 * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.06
        v += math.sin(x * 0.9 + seed) * 0.05
        if face in SIDES and (x in (0, w - 1)) and w > 4:
            v -= 0.12
        if studs and face == 'front' and w >= 8 and x in (2, w - 3) and y % 5 == 2 and y < h - 4:
            return ramp(BRZ, 0.85)
        return ramp(LEATH, v)
    return paint


def wrap(base=0.45, seed=0):
    """Armwickel aus Leder: schräge Bahnen."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + (speckle(x, y, seed) - 0.5) * 0.06
        band = (x + y * 2) % 6
        v += 0.06 if band in (0, 1) else (-0.07 if band == 5 else 0.0)
        return ramp(LEATH, v)
    return paint


def tattered(paint, depth=4, seed=0):
    def wrapped(face, x, y, w, h):
        if face in SIDES and y >= h - depth:
            if y >= h - 1 - int(speckle(x * 3, 0, seed) * depth):
                return None
        return paint(face, x, y, w, h)
    return wrapped


# --- besondere Maler ----------------------------------------------------------------------------------------------

def helm_bell(face, x, y, w, h):
    """Henkershaube: glatte Glocke, Nietband, vorn ein senkrechter leuchtender Sehschlitz (2 x 6)."""
    base = bronze(0.6, 701, grad=0.22, round_x=0.2, rim=False, seam=False)
    cx = (w - 1) / 2.0
    if face in SIDES:
        d = abs(x - cx)
        if face == 'front':
            if d < 1 and 1 <= y <= 6:
                return VIOLET(0.92 if 2 <= y <= 5 else 0.75)
            if d < 2 and 0 <= y <= 7:
                return ramp(BRZ, 0.04)                              # Rahmen des Schlitzes, tief im Schatten
        if y == h - 2:                                              # Nietband über dem Rand
            return ramp(BRZ, 0.9 if x % 3 == 1 else 0.38)
        if face in ('front', 'back') and d < 1 and y < h - 2:     # Schmiedenaht als Grat
            return ramp(BRZ, 0.78 - y * 0.02)
    return base(face, x, y, w, h)


def lock_plate(face, x, y, w, h):
    """Brustschild des Kerkermeisters: ein Schloss mit Schlüsselloch aus schwarzem Eisen."""
    base = bronze(0.66, 709, grad=0.2, round_x=0.25, rim=True, seam=True)
    if face == 'front':
        cx = (w - 1) / 2.0
        d = abs(x - cx)
        if 2 <= y <= 5 and 2.5 <= d < 4.5:                         # Bügel
            return ramp(IRN, 0.55 if y == 2 else 0.4)
        if 2 <= y <= 3 and d < 2.5:
            return ramp(IRN, 0.55 if y == 2 else 0.4)
        if 6 <= y <= h - 3 and d < 5:                              # Schlosskasten
            if (d < 1 and 8 <= y <= 9) or (d < 0.6 and 10 <= y <= 12):
                return ramp(IRN, 0.02)                              # Schlüsselloch
            return ramp(IRN, 0.62 if y == 6 else 0.38 + 0.04 * (d < 4))
    return base(face, x, y, w, h)


def axe_blade(face, x, y, w, h):
    v = 0.5 + FACE_LIGHT[face] + 0.18 * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, 721) - 0.5) * 0.06
    return ramp(IRN, v)


def axe_edge(face, x, y, w, h):
    if face == 'front':
        return VIOLET(0.55 + 0.15 * (1 - abs(y - (h - 1) / 2.0) / ((h - 1) / 2.0)), 230)
    return ramp(IRN, 0.95 + FACE_LIGHT[face])


def lantern_core(face, x, y, w, h):
    """Laternenkern: hellste Stelle der Figur. Ein Querriegel aus Eisen teilt jedes Fenster."""
    if face in SIDES:
        if y == h // 2:
            return ramp(IRN, 0.4)
        cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
        r = math.hypot((x - cx) / max(cx, 1), (y - cy) / max(cy, 1))
        return VIOLET(1.0 - 0.18 * r)
    return VIOLET(0.9)


def chain_link(face, x, y, w, h):
    """Kettenglied: Eisen mit dunklem Loch in der Mitte."""
    if face in SIDES and 1 <= y <= h - 2 and 0 < x < w - 1 and w >= 3:
        return ramp(IRN, 0.05)
    v = 0.48 + FACE_LIGHT[face] + (0.15 if y in (0, h - 1) else 0.0)
    return ramp(IRN, v)


def palm(seed):
    """Flache Bronzehand des Rückenfächers: Handfläche mit Fingerfugen."""
    base = bronze(0.62, seed, grad=0.2, round_x=0.0, rim=True, seam=False)

    def paint(face, x, y, w, h):
        if face in ('front', 'back') and y < 2 and x in (2, 5):
            return ramp(BRZ, 0.12)
        return base(face, x, y, w, h)
    return paint


def gauntlet(seed):
    """Faust: Fingerreihen als breite Bahnen, Knöchel hell."""
    def paint(face, x, y, w, h):
        v = 0.55 + FACE_LIGHT[face] + (speckle(x, y, seed) - 0.5) * 0.06
        if face in ('front', 'bottom', 'left', 'right') and h >= 6:
            if face == 'front' and y < 2:
                v += 0.22                                           # Knöchel
            if face == 'bottom' or face == 'front':
                if x % 3 == 2:
                    return ramp(BRZ, 0.14)                          # Fingerfugen
        return ramp(BRZ, v + 0.1 * (0.5 - y / max(h - 1, 1)))
    return paint


# =====================================================================================================================
# Grundhaltung (Ruhe): Diese Winkel stehen als Grundrotation im Modell und sind POSES['idle'].
# =====================================================================================================================

IDLE = {
    'body': (0.0, 0.0, 0.0),
    'chest': (0.06, 0.0, 0.0),
    'head': (0.08, 0.0, 0.0),
    # Oben rechts: Beil auf der Schulter
    'arm_ur': (-0.55, 0.15, 0.6),
    'fore_ur': (-1.55, 0.0, -0.25),
    'axe': (0.25, 0.0, -1.15),
    # Oben links: greift den Stiel weiter unten
    'arm_ul': (-0.35, 0.0, -0.55),
    'fore_ul': (-1.2, 0.0, 0.85),
    # Unten rechts: Schlüsselring
    'arm_lr': (-0.1, 0.0, 0.42),
    'fore_lr': (-0.45, 0.0, -0.12),
    'keys': (0.45, 0.0, -0.3),
    # Unten links: Kette hängt
    'arm_ll': (-0.05, 0.0, -0.45),
    'fore_ll': (-0.5, 0.0, 0.1),
    'chain_0': (0.55, 0.0, 0.32),
    'chain_1': (0.0, 0.0, 0.0),
    'chain_2': (0.0, 0.0, 0.0),
    'chain_3': (0.0, 0.0, 0.0),
    'chain_4': (0.0, 0.0, 0.0),
    'hook': (0.0, 0.0, 0.0),
    'hands_fan': (0.25, 0.0, 0.0),
    'lantern': (0.0, 0.0, 0.0),
    'leg_r': (0.0, 0.0, 0.1),
    'shin_r': (0.0, 0.0, -0.1),
    'leg_l': (0.0, 0.0, -0.1),
    'shin_l': (0.0, 0.0, 0.1),
    'apron': (0.0, 0.0, 0.0),
    'drape': (0.12, 0.0, 0.0),
}
FAN_ROLL = [-1.38, -0.92, -0.46, 0.0, 0.46, 0.92, 1.38]
FAN_LEN = [18, 19, 20, 20, 20, 19, 18]
for _i, _r in enumerate(FAN_ROLL):
    IDLE[f'fan_{_i}'] = (0.0, 0.0, _r)


def deg(name):
    return tuple(math.degrees(a) for a in IDLE[name])


# =====================================================================================================================
# Modell
# =====================================================================================================================

def fan_hand(i):
    """Eine Hand des Rückenfächers: Armstumpf, Manschette, flache Hand, drei greifende Finger, Daumen."""
    length = FAN_LEN[i]
    s = 760 + i * 7
    top = -length - 3                          # Unterkante der Handfläche
    cubes = [
        Cube(-2, -length, -1.5, 4, length, 3, bronze(0.26, s, grad=0.25, round_x=0.0, seam=False)),
        Cube(-2.5, -length - 3, -2, 5, 3, 4, bronze(0.66, s + 1, round_x=0.0, seam=False)),
        Cube(-4, top - 6, -1, 8, 6, 2, palm(s + 2)),
    ]
    for k, (fx, fl) in enumerate(((-4, 5), (-1, 7), (2, 5))):
        cubes.append(Cube(fx, top - 6 - fl, -1, 2, fl, 2, bronze(0.7, s + 3 + k, grad=0.3, round_x=0.0, seam=False)))
    side = -1 if i < 3 else 1
    cubes.append(Cube(4 if side > 0 else -6, top - 4, -1.5, 2, 4, 2, bronze(0.64, s + 9, round_x=0.0, seam=False)))
    return Part(f'fan_{i}', (0, 0, 0), deg(f'fan_{i}'), cubes=cubes)


def upper_arm(s, side):
    """Oberes Armpaar: schwer gepanzert. side = -1 rechts, +1 links."""
    sd = 'ur' if side < 0 else 'ul'
    seed = 610 if side < 0 else 640
    fore_children = []
    if side < 0:
        fore_children.append(axe())
    return Part(f'arm_{sd}', (side * 23, -38, 0), deg(f'arm_{sd}'), cubes=[
        Cube(-6.5, -5, -7.5, 13, 8, 15, bronze(0.62, seed, round_x=0.18, rivets=4)),         # Schulterglocke
        Cube(-6, 2, -7, 12, 4, 14, bronze(0.5, seed + 1, round_x=0.18)),                    # zweite Lamelle
        Cube(-4, 0, -4, 8, 21, 8, wrap(0.48, seed + 2)),                                      # Oberarm (Wickel)
        Cube(-4.5, 17, -4.5, 9, 6, 9, bronze(0.55, seed + 3, round_x=0.2)),                  # Ellbogenkachel
    ], children=[
        Part(f'fore_{sd}', (0, 22, 0), deg(f'fore_{sd}'), cubes=[
            Cube(-4.5, 0, -4.5, 9, 20, 9, bronze(0.5, seed + 4, round_x=0.2, rivets=4)),     # Unterarmschiene
            Cube(-5, 15, -5, 10, 4, 10, iron(0.5, seed + 5)),                                # Schelle
            Cube(-4.5, 20, -4.5, 9, 9, 9, gauntlet(seed + 6)),                               # Faust
            Cube(side * 4.5 - 1.5, 20, -4, 3, 5, 3, bronze(0.58, seed + 7, round_x=0.0)),   # Daumen
        ], children=fore_children),
    ])


def lower_arm(side):
    """Unteres Armpaar: 12 tiefer, 3 weiter vorn, schlanker."""
    sd = 'lr' if side < 0 else 'll'
    seed = 670 if side < 0 else 690
    hand_item = keys() if side < 0 else chain_arm()
    return Part(f'arm_{sd}', (side * 21, -26, -3), deg(f'arm_{sd}'), cubes=[
        Cube(-5, -4, -5, 10, 8, 10, bronze(0.58, seed, round_x=0.2)),                         # Schulterkachel
        Cube(-3.5, 0, -3.5, 7, 21, 7, wrap(0.44, seed + 1)),
        Cube(-4, 17, -4, 8, 5, 8, bronze(0.5, seed + 2, round_x=0.2)),
    ], children=[
        Part(f'fore_{sd}', (0, 22, 0), deg(f'fore_{sd}'), cubes=[
            Cube(-4, 0, -4, 8, 20, 8, bronze(0.46, seed + 3, round_x=0.2)),
            Cube(-4.5, 14, -4.5, 9, 4, 9, iron(0.5, seed + 4)),
            Cube(-4, 20, -4, 8, 8, 8, gauntlet(seed + 5)),
            Cube(side * 4 - 1.5, 20, -3.5, 3, 4, 3, bronze(0.58, seed + 6, round_x=0.0)),
        ], children=[hand_item]),
    ])


def axe():
    """Richtbeil: Stiel 62, Halbmondblatt 22 hoch und 14 tief, Gegendorn. Ursprung im Griff der Faust."""
    blade = iron(0.5, 723)
    return Part('axe', (0, 24.5, 0), deg('axe'), cubes=[
        Cube(-1.5, -44, -1.5, 3, 62, 3, iron(0.38, 720, grad=0.0)),                         # Stiel
        Cube(-2, -6, -2, 4, 10, 4, wrap(0.5, 724)),                                          # Griffwicklung
        Cube(-2.5, 14, -2.5, 5, 4, 5, bronze(0.65, 725, round_x=0.0)),                      # Knauf
        Cube(-2.5, -44, -3, 5, 10, 6, bronze(0.66, 726, round_x=0.0, rivets=0)),            # Blattfassung
        Cube(-1, -43, -9, 2, 8, 6, axe_blade),                                               # Blatt innen
        Cube(-1, -46, -13, 2, 14, 4, axe_blade),                                             # Blatt Mitte
        Cube(-1, -48, -15, 2, 18, 2, axe_blade),                                             # Blatt außen
        Cube(-1, -52, -17, 2, 22, 2, axe_edge),                                              # Schneide (leuchtet vorn)
        Cube(-1, -52, -15, 2, 3, 2, axe_blade),                                              # Horn oben
        Cube(-1, -33, -15, 2, 3, 2, axe_blade),                                              # Horn unten
        Cube(-1, -42, 3, 2, 5, 4, blade),                                                    # Gegendorn
        Cube(-1, -41, 7, 2, 3, 3, blade),
        Cube(-1, -48, -1, 2, 4, 2, bronze(0.7, 727, round_x=0.0, seam=False)),              # Spitze
    ])


def chain_arm():
    """Fünf Glieder als eigene Knochen (je 7 lang, 3 dick), am Ende der Haken (9 x 9, 3 dick)."""
    hook = Part('hook', (0, 6, 0), deg('hook'), cubes=[
        Cube(-1.5, 0, -1.5, 3, 4, 3, iron(0.5, 740)),                                        # Öse
        Cube(-1.5, 3, -1.5, 3, 6, 3, iron(0.55, 741)),                                       # Schaft
        Cube(-1.5, 8, -1.5, 9, 3, 3, iron(0.5, 742)),                                        # Bogen
        Cube(4.5, 3, -1.5, 3, 5, 3, iron(0.6, 743)),                                         # aufsteigende Spitze
        Cube(5, 1, -1, 2, 2, 2, iron(0.8, 744)),
    ])
    current = hook
    for i in reversed(range(5)):
        flat = i % 2 == 0
        cube = Cube(-1.5, 0, -1, 3, 7, 2, chain_link) if flat else Cube(-1, 0, -1.5, 2, 7, 3, chain_link)
        pivot = (0, 26, 0) if i == 0 else (0, 6, 0)
        current = Part(f'chain_{i}', pivot, deg(f'chain_{i}'), cubes=[cube], children=[current])
    return current


def keys():
    """Schlüsselring aus schwarzem Eisen mit drei großen Schlüsseln."""
    ring = iron(0.5, 750)
    cubes = [
        Cube(-5, 0, -1, 10, 2, 2, ring),
        Cube(-5, 2, -1, 2, 6, 2, ring),
        Cube(3, 2, -1, 2, 6, 2, ring),
        Cube(-5, 8, -1, 10, 2, 2, ring),
    ]
    for k, (kx, kl) in enumerate(((-4, 9), (-0.5, 11), (3, 8))):
        cubes.append(Cube(kx, 9, -1, 2, kl, 2, iron(0.55, 751 + k)))
        cubes.append(Cube(kx - (0 if k == 2 else 1), 9 + kl - 3, -1, 3, 3, 2, iron(0.62, 755 + k)))
    return Part('keys', (0, 26, 0), deg('keys'), cubes=cubes)


def lantern():
    """Laterne: Eisengitter, Bronzekappen, Kern leuchtet. Pendelt am Haken."""
    frame = bronze(0.6, 730, round_x=0.0, seam=False)
    post = iron(0.45, 731)
    cubes = [
        Cube(-2, 0, -1, 4, 2, 2, iron(0.55, 732)),                                           # Ring
        Cube(-5, 2, -5, 10, 2, 10, frame),                                                   # Kappe
        Cube(-4, 4, -4, 8, 10, 8, lantern_core),                                             # Kern
        Cube(-5, 14, -5, 10, 2, 10, frame),                                                  # Boden
        Cube(-2, 16, -2, 4, 2, 4, bronze(0.5, 733, round_x=0.0, seam=False)),               # Zapfen
    ]
    for px, pz in ((-5, -5), (3, -5), (-5, 3), (3, 3)):
        cubes.append(Cube(px, 4, pz, 2, 10, 2, post))
    return Part('lantern', (0, -15, 15), deg('lantern'), cubes=cubes)


def warden():
    head = Part('head', (0, -44, 0), deg('head'), cubes=[
        Cube(-6, -9, -6, 12, 9, 12, helm_bell),
        Cube(-5, -11, -5, 10, 2, 10, bronze(0.72, 702, round_x=0.15, seam=False)),
        Cube(-3, -12, -3, 6, 1, 6, bronze(0.82, 703, round_x=0.0, seam=False)),
        Cube(-7, -2, -7, 14, 2, 14, bronze(0.5, 704, round_x=0.15, rim=True, seam=False)),   # ausgestellter Rand
    ])

    fan = Part('hands_fan', (0, -25, 14), deg('hands_fan'), cubes=[
        Cube(-5, -3, -2, 10, 6, 4, bronze(0.5, 759, round_x=0.15)),                         # Nabe
        # Rückenschild: gestufte Halbscheibe, aus der die Arme wachsen.
        Cube(-14, -6, 0, 28, 6, 2, bronze(0.42, 761, round_x=0.3, rivets=5)),
        Cube(-11, -11, 0, 22, 5, 2, bronze(0.46, 762, round_x=0.3)),
        Cube(-7, -15, 0, 14, 4, 2, bronze(0.5, 763, round_x=0.3)),
    ], children=[fan_hand(i) for i in range(7)])

    plate = lambda b, s: bronze(b, s, round_x=0.22, rivets=5)  # noqa: E731
    chest = Part('chest', (0, -8, 0), deg('chest'), cubes=[
        # Drei Reihen überlappender Platten, unten schmal (Bauch 30), oben breit (Schultern 40): ein Fass.
        Cube(-15, -14, -8, 30, 14, 16, plate(0.44, 601)),
        Cube(-13, -14, -10, 26, 14, 20, plate(0.46, 602)),
        Cube(-18, -28, -9, 36, 15, 18, plate(0.5, 603)),
        Cube(-16, -28, -11, 32, 15, 22, plate(0.52, 604)),
        Cube(-20, -42, -9, 40, 15, 18, plate(0.56, 605)),
        Cube(-18, -42, -11, 36, 15, 22, plate(0.58, 606)),
        Cube(-14, -44, -9, 28, 3, 18, bronze(0.5, 607, round_x=0.2, seam=False)),           # Schulterjoch
        Cube(-9, -45, -9, 18, 3, 18, bronze(0.42, 608, round_x=0.2)),                       # Kragen
        Cube(-7, -40, -12.5, 14, 18, 2, lock_plate),                                         # Schloss-Schild
        Cube(-3, -18, 10, 6, 4, 6, iron(0.45, 611)),                                         # Laternenhaken
        Cube(-11, -40, 10, 22, 12, 2, leather(0.42, 612)),                                   # Tragegurt Rücken
    ], children=[
        head,
        upper_arm('ur', -1),
        upper_arm('ul', 1),
        lower_arm(-1),
        lower_arm(1),
        fan,
        lantern(),
    ])

    def leg(side):
        s = 'r' if side < 0 else 'l'
        seed = 800 if side < 0 else 820
        return Part(f'leg_{s}', (side * 9, 0, 0), deg(f'leg_{s}'), cubes=[
            Cube(-5.5, 0, -5.5, 11, 17, 11, wrap(0.4, seed)),
            Cube(-5, 13, -6.5, 10, 6, 4, bronze(0.6, seed + 1, round_x=0.2)),                # Kniekachel
        ], children=[
            Part(f'shin_{s}', (0, 16, 0), deg(f'shin_{s}'), cubes=[
                Cube(-5, 0, -5, 10, 14, 10, bronze(0.5, seed + 2, round_x=0.22)),
                Cube(-5.5, 1, -5.5, 11, 9, 4, bronze(0.6, seed + 3, round_x=0.22, rivets=4)),  # Beinschiene
                Cube(-6, 14, -9, 12, 4, 16, bronze(0.42, seed + 4, round_x=0.15)),             # Stiefel
                Cube(-5, 12, -10, 10, 3, 5, bronze(0.55, seed + 5, round_x=0.15, seam=False)),  # Kappe
            ]),
        ])

    body = Part('body', (0, -10, 0), deg('body'), cubes=[
        Cube(-15, -6, -9, 30, 12, 18, bronze(0.38, 590, round_x=0.2)),                      # Becken
        Cube(-16, -9, -10, 32, 6, 20, leather(0.5, 591, studs=False)),                      # Gurt
        Cube(-4, -10, -11, 8, 8, 2, bronze(0.75, 592, round_x=0.0)),                        # Schnalle
        Cube(-16, -3, -8, 6, 12, 16, bronze(0.5, 593, round_x=0.1, rivets=0)),              # Hüftlamelle rechts
        Cube(10, -3, -8, 6, 12, 16, bronze(0.5, 594, round_x=0.1, rivets=0)),               # Hüftlamelle links
    ], children=[
        chest,
        leg(-1),
        leg(1),
        Part('apron', (0, -3, -10), deg('apron'), cubes=[
            Cube(-7, 0, -1.5, 14, 24, 2, tattered(leather(0.55, 595, studs=True), 4, 596)),
            Cube(-8, 0, -2, 16, 3, 3, bronze(0.6, 597, round_x=0.0, seam=False)),
        ]),
        Part('drape', (0, -3, 9.5), deg('drape'), cubes=[
            Cube(-10, 0, -0.5, 20, 13, 2, tattered(leather(0.42, 598), 6, 599)),
        ]),
    ])
    return Model('warden_of_chains', 512, 256, [body])


# =====================================================================================================================
# Kinematik: Vorwärtsrechnung, Ausrichten und ein kleiner IK-Löser. Damit greifen beide Hände wirklich den Stiel und
# die Kettenglieder liegen auf einer gewollten Bahn. Gerechnet wird nur hier; WardenModel.kt bekommt die fertigen Zahlen.
# =====================================================================================================================

from preview_model import apply as _apply, mat_mul as _mul, rot_zyx as _rot  # noqa: E402

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


class Rig:
    """Knochenbaum des Modells für Rechnungen an einer Pose (Dict Name -> 3 oder 6 Zahlen)."""

    def __init__(self, model):
        self.parts, self.parent = {}, {}

        def walk(p, par):
            self.parts[p.name] = p
            self.parent[p.name] = par
            for c in p.children:
                walk(c, p.name)
        for p in model.parts:
            walk(p, None)

    @staticmethod
    def _val(pose, name):
        v = tuple(pose[name])
        return v[:3], (v[3:6] if len(v) >= 6 else (0.0, 0.0, 0.0))

    def fk(self, pose, name):
        """Weltdrehung und Weltlage des Knochenursprungs."""
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
        yaw = math.asin(max(-1.0, min(1.0, -m[2][0])))
        pitch = math.atan2(m[2][1], m[2][2])
        roll = math.atan2(m[1][0], m[0][0])
        _, off = self._val(pose, name)
        pose[name] = (pitch, yaw, roll) + (tuple(off) if any(off) else ())

    def place(self, pose, name, target):
        """Verschiebt den Knochenursprung (Versatz im Elternraum) auf den Weltpunkt target."""
        rp, pp = self.fk(pose, self.parent[name])
        local = _apply(_tr(rp), _sub(target, pp))
        r, _ = self._val(pose, name)
        pose[name] = tuple(r) + _sub(local, self.parts[name].pivot)

    def aim(self, pose, name, ydir, zhint=(0, 0, -1)):
        """Dreht den Knochen so, dass seine lokale +y-Achse in Weltrichtung ydir zeigt (+z möglichst nach zhint)."""
        pose_rot = frame(ydir, zhint)
        self.set_world_rot(pose, name, pose_rot)

    def ik(self, pose, arm, fore, grip, target, elbow=None, weight=0.08, elbow_weight=0.04):
        """Zwei-Knochen-IK: Oberarm (pitch, yaw, roll) und Unterarm (pitch) so, dass der Griffpunkt target trifft.
        elbow ist ein Weltpunkt, zu dem der Ellbogen leicht gezogen wird (Ellbogen nach außen)."""
        names = [(arm, 0), (arm, 1), (arm, 2), (fore, 0)]
        start = [pose[n][k] for n, k in names]

        def get():
            return [pose[n][k] for n, k in names]

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

        best = get()
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
        return math.sqrt(max(0.0, _dot(*(2 * [_sub(self.world(pose, fore, grip), target)]))))


def frame(ydir, zhint):
    y = _norm(ydir)
    z = _sub(zhint, _scale(y, _dot(zhint, y)))
    if _dot(z, z) < 1e-6:
        z = _sub((1, 0, 0), _scale(y, y[0]))
    z = _norm(z)
    x = _cross(y, z)
    return [[x[i], y[i], z[i]] for i in range(3)]


UPPER_GRIP = (0, 24.5, 0)     # Faustmitte im Unterarm der oberen Arme
LOWER_GRIP = (0, 24.0, 0)     # dito untere Arme
CHAIN = ['chain_0', 'chain_1', 'chain_2', 'chain_3', 'chain_4', 'hook']


def hold_axe(rig, pose, grip, up, blade, left_at=12.0, lower=(), elbows=(None, None), ew=0.04):
    """Rechte obere Faust greift den Stiel bei grip, der Kopf zeigt nach up, die Schneide nach blade.
    Die linke obere Faust greift left_at Einheiten tiefer am Stiel; lower = [(arm, Abstand)] für die unteren Hände."""
    up = _norm(up)
    rig.ik(pose, 'arm_ur', 'fore_ur', UPPER_GRIP, grip, elbows[0], elbow_weight=ew)
    if left_at is not None:
        rig.ik(pose, 'arm_ul', 'fore_ul', UPPER_GRIP, _sub(grip, _scale(up, left_at)), elbows[1], elbow_weight=0.01)
    for arm, at, elbow in lower:
        side = arm[-2:]
        rig.ik(pose, arm, f'fore_{side}', LOWER_GRIP, _sub(grip, _scale(up, at)), elbow)
    # Stiel genau durch die rechte Faust legen: +y des Beils zeigt zum Knauf, +z gegen die Schneide.
    rig.aim(pose, 'axe', _scale(up, -1), _scale(blade, -1))


def lay_chain(rig, pose, dirs, hook_dir=None):
    """Legt die fünf Glieder nacheinander in die Weltrichtungen dirs (eine je Glied)."""
    for name, d in zip(CHAIN, dirs):
        rig.aim(pose, name, d, (1, 0, 0) if abs(d[0]) < 0.8 else (0, 0, -1))
    rig.aim(pose, 'hook', hook_dir or dirs[-1], (1, 0, 0))


def arc(start, turn, count=5, axis='y', tilt=0.0):
    """Richtungen einer gebogenen Kette: beginnt bei start, dreht je Glied um turn (Radiant) um die Hochachse."""
    out = []
    ang = math.atan2(start[0], -start[2])
    for i in range(count):
        a = ang + turn * i
        out.append(_norm((math.sin(a), tilt, -math.cos(a))))
    return out


# =====================================================================================================================
# Posen (Radiant, absolute Knochendrehung; sechs Zahlen = Drehung + Versatz). Dieselben Werte stehen in WardenModel.kt.
# =====================================================================================================================

_RIG = Rig(warden())


def _solve_idle():
    p = dict(IDLE)
    # Beil auf der rechten Schulter, beide oberen Fäuste am Stiel.
    hold_axe(_RIG, p, grip=(-14, -44, -15), up=(-0.42, -0.72, 0.55), blade=(-0.86, 0.5, 0.0), left_at=13,
             elbows=((-46, -42, -2), (30, -36, -16)), ew=0.4)
    lay_chain(_RIG, p, [(0.15, 1, -0.1)] * 2 + [(0.1, 1, 0.05)] * 3, (0.05, 1, 0.15))
    p['hook'] = (0.0, 0.0, 0.0)
    return p


IDLE.update(_solve_idle())


def _derive(base=None, **bones):
    pose = dict(base or IDLE)
    pose.update(bones)
    return pose


def _delta(pose):
    """Nur die Knochen, die sich gegenüber der Ruhe ändern (für die Kotlin-Tabelle)."""
    return {k: v for k, v in pose.items() if tuple(v) != tuple(IDLE[k])}


def _fan(spread=1.0, pitch=0.2):
    out = {'hands_fan': (pitch, 0.0, 0.0)}
    for i, r in enumerate(FAN_ROLL):
        out[f'fan_{i}'] = (0.0, 0.0, r * spread)
    return out


def _chop_windup():
    p = _derive(
        body=(0.0, 0.0, 0.0, 0.0, 2.0, 1.0), chest=(-0.38, 0.0, 0.0), head=(-0.25, 0.0, 0.0),
        leg_r=(-0.3, 0.0, 0.22), shin_r=(0.35, 0.0, -0.15), leg_l=(0.25, 0.0, -0.22), shin_l=(0.2, 0.0, 0.15),
        arm_lr=(-0.3, 0.0, 1.05), fore_lr=(-0.7, 0.0, 0.0), arm_ll=(-0.3, 0.0, -1.05), fore_ll=(-0.7, 0.0, 0.0),
        apron=(-0.15, 0.0, 0.0), drape=(0.35, 0.0, 0.0), **_fan(1.1, 0.35))
    hold_axe(_RIG, p, grip=(-1, -96, 10), up=(0, -0.82, 0.57), blade=(0, -0.57, -0.82), left_at=10,
             elbows=((-26, -86, 0), (26, -82, 0)))
    lay_chain(_RIG, p, [(0.35, 1, 0)] * 2 + [(0.2, 1, 0.1)] * 3)
    return p


def _chop_hit():
    p = _derive(
        body=(0.0, 0.0, 0.0, 0.0, 6.0, -3.0), chest=(1.05, 0.0, 0.0), head=(-0.55, 0.0, 0.0),
        leg_r=(-0.75, 0.0, 0.18), shin_r=(0.75, 0.0, -0.1), leg_l=(0.45, 0.0, -0.18), shin_l=(0.35, 0.0, 0.1),
        arm_lr=(-0.6, 0.0, 0.7), fore_lr=(-0.6, 0.0, 0.0), arm_ll=(-0.6, 0.0, -0.7), fore_ll=(-0.6, 0.0, 0.0),
        apron=(-0.6, 0.0, 0.0), drape=(0.55, 0.0, 0.0), **_fan(1.0, 0.0))
    hold_axe(_RIG, p, grip=(-1, -4, -44), up=(0, 0.75, -0.66), blade=(0, 0.66, 0.75), left_at=10,
             elbows=((-26, -18, -24), (26, -16, -24)))
    lay_chain(_RIG, p, [(0.1, 1, 0.3)] * 5)
    return p


def _chop_stuck():
    hit = POSES_RAW['chop_hit']
    p = _derive(
        body=(0.0, 0.0, 0.0, 0.0, 6.0, 1.0), chest=(0.62, 0.0, 0.0), head=(-0.15, 0.0, 0.0),
        leg_r=(-0.55, 0.0, 0.25), shin_r=(0.6, 0.0, -0.2), leg_l=(0.45, 0.0, -0.25), shin_l=(0.3, 0.0, 0.2),
        apron=(-0.4, 0.0, 0.0), drape=(0.3, 0.0, 0.0), **_fan(0.95, 0.15))
    # Das Beil steckt dort, wo der Hieb es hingetrieben hat; alle vier Hände zerren am Stiel.
    rot, pos = _RIG.fk(hit, 'axe')
    up = _apply(rot, (0, -1, 0))
    blade = _apply(rot, (0, 0, -1))
    grip = _add(pos, _scale(up, -4))
    hold_axe(_RIG, p, grip=grip, up=up, blade=blade, left_at=8,
             lower=[('arm_lr', 15, (-30, -20, -14)), ('arm_ll', 15, (30, -20, -14))],
             elbows=((-28, -30, -10), (28, -30, -10)))
    lay_chain(_RIG, p, [(0.0, 1, 0.2)] * 2 + [(0.0, 1, 0.5)] * 3, (0, 0.3, 1))
    return p


def _chain_windup():
    p = _derive(
        body=(0.0, 0.25, 0.0), chest=(-0.15, 0.45, 0.0), head=(-0.15, -0.5, 0.0),
        leg_r=(0.25, 0.0, 0.15), shin_r=(0.2, 0.0, -0.1), leg_l=(-0.25, 0.0, -0.18), shin_l=(0.35, 0.0, 0.1),
        arm_ll=(-2.6, 0.0, -0.75), fore_ll=(-0.45, 0.0, 0.0), drape=(0.2, 0.0, 0.0),
        arm_lr=(0.25, 0.0, 0.5), fore_lr=(-0.9, 0.0, 0.0))
    hand = _RIG.world(p, 'fore_ll', LOWER_GRIP)
    # Kette kreist waagerecht über der Haube (Bahn um die Faust), Haken außen.
    lay_chain(_RIG, p, arc((1, 0, 0.35), -0.7, 5, tilt=0.35), None)
    del hand
    return p


def _chain_throw():
    p = _derive(
        body=(0.0, -0.3, 0.0, 0.0, 2.0, -2.0), chest=(0.3, -0.45, 0.0), head=(-0.2, 0.55, 0.0),
        leg_r=(0.45, 0.0, 0.12), shin_r=(0.3, 0.0, -0.1), leg_l=(-0.7, 0.0, -0.12), shin_l=(0.6, 0.0, 0.1),
        arm_ll=(-1.75, 0.35, -0.05), fore_ll=(-0.15, 0.0, 0.0),
        arm_lr=(0.45, 0.0, 0.55), fore_lr=(-0.7, 0.0, 0.0), apron=(-0.45, 0.0, 0.0), drape=(0.45, 0.0, 0.0))
    lay_chain(_RIG, p, [(0.0, 0.08, -1)] * 5, (0.0, 0.08, -1))
    return p


def _chain_pull():
    p = _derive(
        body=(0.0, 0.35, 0.0, 0.0, 2.0, 2.0), chest=(-0.3, 0.6, 0.0), head=(0.0, -0.45, 0.0),
        leg_r=(-0.45, 0.0, 0.15), shin_r=(0.3, 0.0, -0.1), leg_l=(0.4, 0.0, -0.15), shin_l=(0.4, 0.0, 0.1),
        arm_ll=(0.75, 0.0, -0.55), fore_ll=(-1.5, 0.0, 0.0),
        arm_lr=(-0.2, 0.0, 0.7), fore_lr=(-0.5, 0.0, 0.0), apron=(0.2, 0.0, 0.0), drape=(-0.1, 0.0, 0.0))
    lay_chain(_RIG, p, [(0.25, 0.1, -1), (0.2, 0.25, -1), (0.1, 0.45, -1), (0.05, 0.6, -1), (0.0, 0.75, -1)], (0, 0.5, -1))
    return p


def _hooked():
    p = _derive(
        body=(0.0, 0.0, 0.0, 0.0, 5.0, 6.0), chest=(-0.5, 0.15, 0.0), head=(0.25, -0.1, 0.0),
        leg_r=(0.55, 0.0, 0.2), shin_r=(0.15, 0.0, -0.1), leg_l=(-0.85, 0.0, -0.2), shin_l=(0.95, 0.0, 0.1),
        apron=(-0.3, 0.0, 0.0), drape=(-0.25, 0.0, 0.0), **_fan(0.9, 0.05))
    # Unten links hält die gestraffte Kette, unten rechts greift weiter vorn mit an.
    _RIG.ik(p, 'arm_ll', 'fore_ll', LOWER_GRIP, (6, -30, -40), (32, -30, -20))
    hand = _RIG.world(p, 'fore_ll', LOWER_GRIP)
    taut = _norm(_sub((0, -10, -200), hand))
    lay_chain(_RIG, p, [taut] * 5, taut)
    _RIG.ik(p, 'arm_lr', 'fore_lr', LOWER_GRIP, _add(_RIG.world(p, 'chain_1'), (0, 0, -2)), (-30, -26, -24))
    # Das Beil hoch und aus dem Weg gezogen: oben rechts hält es schräg über die Schulter nach hinten.
    hold_axe(_RIG, p, grip=(-18, -64, -6), up=(-0.25, -0.5, 0.83), blade=(-0.95, 0, -0.3), left_at=None,
             elbows=((-34, -52, 6), None))
    p['arm_ul'] = (-0.9, 0.0, -0.75)
    p['fore_ul'] = (-0.9, 0.0, 0.0)
    return p


def _round_windup():
    p = _derive(
        body=(0.0, 0.45, 0.0, 0.0, 7.0, 2.0), chest=(0.25, 0.75, 0.12), head=(0.0, -0.95, 0.0),
        leg_r=(-0.55, 0.0, 0.45), shin_r=(0.85, 0.0, -0.2), leg_l=(-0.15, 0.0, -0.5), shin_l=(0.75, 0.0, 0.2),
        arm_ll=(-0.6, 0.0, -1.1), fore_ll=(-0.5, 0.0, 0.0), arm_lr=(0.35, 0.0, 0.3), fore_lr=(-1.2, 0.0, 0.0),
        apron=(-0.35, 0.0, 0.0), drape=(0.3, 0.0, 0.0), **_fan(1.0, 0.25))
    # Beil waagerecht weit nach rechts hinten, Schneide nach vorn-außen.
    hold_axe(_RIG, p, grip=(-30, -24, 14), up=(-0.55, 0.05, 0.83), blade=(-0.83, 0, -0.55), left_at=11,
             elbows=((-34, -40, -6), (0, -30, -24)))
    lay_chain(_RIG, p, [(0.6, 1, 0.1)] * 2 + [(0.3, 1, 0.1)] * 3)
    return p


def _round_hit():
    p = _derive(
        body=(0.0, -0.45, 0.0, 0.0, 6.0, -2.0), chest=(0.25, -0.85, -0.1), head=(0.0, 0.55, 0.0),
        leg_r=(0.2, 0.0, 0.4), shin_r=(0.6, 0.0, -0.2), leg_l=(-0.65, 0.0, -0.4), shin_l=(0.7, 0.0, 0.2),
        arm_ll=(-0.3, 0.0, -0.4), fore_ll=(-1.3, 0.0, 0.0), arm_lr=(-0.8, 0.0, 1.0), fore_lr=(-0.4, 0.0, 0.0),
        apron=(-0.5, 0.0, 0.25), drape=(0.45, 0.0, -0.3), **_fan(1.05, 0.25))
    # Halbe Drehung nach links: das Beil ist durchgezogen und zeigt nach links vorn.
    hold_axe(_RIG, p, grip=(20, -26, -30), up=(0.72, 0.05, -0.69), blade=(0.69, 0, 0.72), left_at=11,
             elbows=((-10, -30, -34), (36, -36, -6)))
    lay_chain(_RIG, p, [(-0.5, 1, 0.3)] * 2 + [(-0.6, 0.7, 0.4)] * 3)
    return p


def _rage():
    p = _derive(
        body=(0.0, 0.0, 0.0, 0.0, 1.0, 0.0), chest=(-0.3, 0.0, 0.0), head=(-0.65, 0.0, 0.0),
        arm_ur=(-0.4, 0.0, 1.35), fore_ur=(-0.9, 0.0, 0.0), arm_ul=(-0.4, 0.0, -1.35), fore_ul=(-0.9, 0.0, 0.0),
        arm_lr=(-0.1, 0.0, 1.15), fore_lr=(-0.35, 0.0, 0.0), arm_ll=(-0.1, 0.0, -1.15), fore_ll=(-0.35, 0.0, 0.0),
        leg_r=(0.0, 0.0, 0.28), shin_r=(0.2, 0.0, -0.2), leg_l=(0.0, 0.0, -0.28), shin_l=(0.2, 0.0, 0.2),
        drape=(0.25, 0.0, 0.0), **_fan(1.3, 0.45))
    _RIG.aim(p, 'axe', (-0.3, 1, 0.1), (0, 0, 1))
    lay_chain(_RIG, p, [(0.5, 1, 0)] * 2 + [(0.3, 1, 0)] * 3)
    return p


def _stagger():
    p = _derive(
        body=(0.0, 0.15, 0.0, 0.0, 15.0, 2.0), chest=(0.7, 0.0, 0.1), head=(0.35, 0.2, 0.0),
        leg_r=(-1.45, 0.0, 0.2), shin_r=(1.45, 0.0, 0.0), leg_l=(0.1, 0.0, -0.15), shin_l=(1.5, 0.0, 0.0),
        arm_ur=(-0.5, 0.0, 0.85), fore_ur=(-0.5, 0.0, 0.0), arm_ul=(-0.2, 0.0, -0.3), fore_ul=(-0.6, 0.0, 0.0),
        apron=(-1.0, 0.0, 0.0), drape=(0.6, 0.0, 0.0), **_fan(0.85, 0.0))
    # Zwei Hände stützen sich auf den Boden.
    _RIG.ik(p, 'arm_lr', 'fore_lr', LOWER_GRIP, (-20, 19, -30), (-34, -10, -14))
    _RIG.ik(p, 'arm_ll', 'fore_ll', LOWER_GRIP, (22, 19, -26), (34, -10, -14))
    # Das Beil fällt zur Seite und liegt flach rechts neben ihm.
    _RIG.aim(p, 'axe', (0.25, 0.0, -1), (0, -1, 0))
    _RIG.place(p, 'axe', (-38, 22, -4))
    lay_chain(_RIG, p, [(0.4, 0.1, -1)] * 2 + [(0.6, 0.0, -0.8)] * 3)
    return p


POSES_RAW = {'idle': dict(IDLE)}
for _name, _fn in (('chop_windup', _chop_windup), ('chop_hit', _chop_hit), ('chop_stuck', _chop_stuck),
                   ('chain_windup', _chain_windup), ('chain_throw', _chain_throw), ('chain_pull', _chain_pull),
                   ('hooked', _hooked), ('round_windup', _round_windup), ('round_hit', _round_hit),
                   ('rage', _rage), ('stagger', _stagger)):
    POSES_RAW[_name] = _fn()
# Die Laterne hängt in jeder Pose senkrecht (Schwerkraft); im Spiel pendelt sie nur noch um diese Lage.
for _p in POSES_RAW.values():
    _RIG.aim(_p, 'lantern', (0, 1, 0), (0, 0, 1))


def _rounded(v):
    return tuple(round(float(x), 4) for x in v)


POSES = {name: {k: _rounded(v) for k, v in pose.items()} for name, pose in POSES_RAW.items()}
IDLE.update(POSES['idle'])
POSE_DELTAS = {name: ({} if name == 'idle' else _delta(pose)) for name, pose in POSES.items()}


# =====================================================================================================================
# Kotlin-Abgleich
# =====================================================================================================================

KOTLIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'kotlin', 'dev', 'henny',
                      'hha', 'client', 'model', 'WardenModel.kt')


def bone_order():
    return [p.name for p in warden().parts[0].walk()]


def kotlin_table():
    order = bone_order()
    parents = []
    for b in order:
        par = _RIG.parent[b]
        parents.append(str(order.index(par)) if par else '-1')
    lines = [f'        val BONES = arrayOf({", ".join(chr(34) + b + chr(34) for b in order)})',
             f'        val PARENTS = intArrayOf({", ".join(parents)})']
    for name, delta in POSE_DELTAS.items():
        if name == 'idle':
            continue
        vals = []
        for b in order:
            v = delta.get(b)
            if v is None:
                vals.append('N, N, N, N, N, N')
            else:
                v = tuple(v) + (0.0, 0.0, 0.0) if len(v) == 3 else tuple(v)
                vals.append(', '.join(f'{x:.4f}f' for x in v))
        lines.append(f'        val {name.upper()} = floatArrayOf(')
        for i in range(0, len(vals), 2):
            lines.append('            ' + ', '.join(vals[i:i + 2]) + ',')
        lines.append('        )')
    return '\n'.join(lines)


def sync_kotlin():
    with open(KOTLIN, encoding='utf-8') as f:
        src = f.read()
    begin, end = '        // POSES BEGIN (generiert aus tools/hha_assets/warden_model.py --kotlin)', '        // POSES END'
    pattern = re.compile(re.escape(begin) + '.*?' + re.escape(end), re.S)
    if not pattern.search(src):
        raise SystemExit('Marken für die Posentabelle fehlen in WardenModel.kt')
    src = pattern.sub(lambda _: begin + '\n' + kotlin_table() + '\n' + end, src)
    with open(KOTLIN, 'w', encoding='utf-8', newline='\n') as f:
        f.write(src)
    print(f'Posentabelle geschrieben: {len(POSE_DELTAS) - 1} Posen, {len(bone_order())} Knochen')


if __name__ == '__main__':
    if '--kotlin' in sys.argv:
        sync_kotlin()

# Für die Größenprüfung in preview.py: Diese Knochen (samt Kindern) zählen nicht zur Körperhöhe (Fuß bis Scheitel),
# weil sie Zubehör sind, das laut Brief darüber hinausragen darf (Hut, Waffe, Busch, Ast, Stachel, Ring).
HEIGHT_EXCLUDE = ('axe', 'chain_0', 'hands_fan')
