"""Zeus, König des Himmels (Endboss des Olymp): eine wandelnde Marmorstatue mit Sturm im Leib.

Erkennungsmerkmale (alle im Schattenriss):
1. Der Keraunos: ein gezackter Blitz in Speerlänge in der rechten Hand (dieselbe Form wie das fliegende Requisit aus
   olymp_props_model.py, Ursprung am Griff).
2. Die Aigis links: ein achteckiger Schild (Ø 30) mit erhabenem Goldrand und dem Gorgonengesicht in der Mitte.
3. Der Strahlenkranz: fünf keilförmige Wolkenzacken fächerförmig hinter dem Kopf.
Dazu nackte Marmorbrust, königsblaues Himation (über die linke Schulter, hinten bis zu den Waden, um die Hüften
geschlagen), Goldgürtel und Goldsandalen. Weißes Haar und ein großer Keilbart.

Material: Marmorhaut mit feinen sturmblauen Adern, Wolkenweiß (Haar, Bart, Kranz), Königsblau (Mantel), Gold (leuchtet
nie), Blitz. Leuchtmaske (#dff1ff): Mittelader des Keraunos, zwei Augenschlitze, die Augen der Gorgo.

Koordinaten: 1/16 Block vor dem Renderer-Faktor (0,69), +y nach unten, -z vorn, Sohlen bei y = 24, Scheitel bei
y = -92 (116 Einheiten). Knochen siehe tmp/overhaul2/models/zeus.md.

`POSES` enthält jede Schlüsselpose der Kotlin-Animation (client/model/ZeusModel.kt). Der Block zwischen `// <keys>` und
`// </keys>` in ZeusModel.kt wird mit `python tools/hha_assets/zeus_model.py --kotlin` aus genau diesen Posen erzeugt.
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
from olymp_props_model import THUNDER_GLOW, keraunos_part  # noqa: E402

# ---------------------------------------------------------------------------------------------------------------------
# Paletten und Licht
# ---------------------------------------------------------------------------------------------------------------------

MARBLE = rgb('#3a404d', '#6c7484', '#8f97a6', '#b4bcc8', '#c9d0da', '#e4e9f0', '#f4f6fa')
VEIN = hexc('#6f86b8')                                  # sturmblaue Ader im Marmor
CLOUD = rgb('#4f5a70', '#8a97ad', '#b9c4d6', '#e6ecf5', '#ffffff')
ROYAL = rgb('#0a1430', '#16264f', '#2a4a9a', '#4f79d6', '#80a8ee')
GOLD = rgb('#4a2c08', '#8a5a12', '#c08422', '#d9a02c', '#f0c24a', '#ffd96a', '#fff0b8')
LEATHER = rgb('#1a0e08', '#2e1a0f', '#4a2c18', '#6a4024')
ENAMEL = rgb('#08122c', '#122452', '#203c80', '#3a62b4')
LIGHT = {'top': 0.15, 'bottom': -0.24, 'front': 0.0, 'back': -0.1, 'left': -0.06, 'right': -0.06}
SIDES = ('front', 'back', 'left', 'right')


def lit(color, strength=255):
    return (color, (THUNDER_GLOW[0], THUNDER_GLOW[1], THUNDER_GLOW[2], strength))


EYE = hexc('#eef8ff')


# ---------------------------------------------------------------------------------------------------------------------
# Maler
# ---------------------------------------------------------------------------------------------------------------------

def _hash(*v):
    h = 2166136261
    for n in v:
        h = ((h ^ (int(n) & 0xffffffff)) * 16777619) & 0xffffffff
    return h / 0xffffffff


def marble(base=0.44, seed=0, roundx=0.36, grad=0.2, veins=True, ridge=0.1):
    """Marmorhaut: weicher Verlauf (oben hell, unten im Schatten), gerundete Seiten mit dunklen Kanten, Lichtgrat oben,
    sehr feines Korn und schräge sturmblaue Adern."""
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] * 1.4 + (0.5 - y / max(h - 1, 1)) * grad + (speckle(x, y, seed) - 0.5) * 0.05
        if roundx and face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += roundx * (0.35 - k * k)
        if face in SIDES and w > 3 and (x == 0 or x == w - 1):
            v -= 0.06
        if ridge and face in SIDES and y == 0:
            v += ridge
        c = ramp(MARBLE, v)
        if veins and face != 'bottom':
            a = math.sin((x * 0.55 + y * 0.9 + seed * 1.7) * 0.8 + math.sin(y * 0.35 + seed) * 1.6)
            if abs(a) < 0.07 and _hash(x // 3, y // 3, seed) > 0.35:
                c = mix(c, VEIN, 0.42)
        return c
    return paint


def cloud(base=0.62, seed=0, strands=True, grad=0.2):
    """Wolkenweiß für Haar, Bart und Kranz: Strähnen der Länge nach (y), oben hell, zu den Spitzen bläulich."""
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad + (speckle(x, y, seed) - 0.5) * 0.06
        if strands and face in SIDES:
            v += 0.09 if (x + seed) % 3 == 0 else (-0.06 if (x + seed) % 3 == 2 else 0.0)
        if face in SIDES and y == h - 1:
            v -= 0.1
        return ramp(CLOUD, v)
    return paint


def ray_paint(seed):
    """Wolkenzacke: an der Wurzel blaugrau wie eine Gewitterwolke, zur Spitze weiß."""
    def paint(face, x, y, w, h):
        t = 1 - y / max(h - 1, 1)                               # 1 an der Spitze (oben)
        v = 0.3 + 0.55 * t + LIGHT[face] + (speckle(x, y, seed) - 0.5) * 0.06
        if face in SIDES and (x == 0 or x == w - 1):
            v -= 0.08
        return ramp(CLOUD, v)
    return paint


def gold(base=0.6, seed=0, bands=0, meander=False):
    """Poliertes Gold: Rundung, Lichtgrat oben, dunkle Unterkante; bands = umlaufende Rillen; meander = Mäanderband."""
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.18 + (speckle(x, y, seed) - 0.5) * 0.05
        if face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += 0.25 * (0.35 - k * k)
        if face in SIDES and h > 2 and (y == 0 or y == h - 1):
            v += 0.16 if y == 0 else -0.14
        if bands and face in SIDES and 0 < y < h - 1 and y % bands == 0:
            v -= 0.22
        if meander and face in SIDES and h >= 4 and 0 < y < h - 1:
            m = x % 4
            row = y - 1
            if (m == 0 and row < h - 2) or (row == 0 and m in (0, 1, 2)) or (m == 2 and row > 0) or (row == h - 3 and m in (2, 3)):
                v -= 0.24
        return ramp(GOLD, v)
    return paint


def royal(seed=0, base=0.5, hem=0, top_hem=0, folds=1.0):
    """Königsblaue Wolle: weiche senkrechte Falten, Verlauf, Goldborte unten (hem Reihen) und/oder oben."""
    def paint(face, x, y, w, h):
        if face in SIDES and hem and y >= h - hem:
            return ramp(GOLD, 0.62 + LIGHT[face] + (0.12 if y == h - hem else -0.06) + (0.0 if x % 3 else -0.14))
        if face in SIDES and top_hem and y < top_hem:
            return ramp(GOLD, 0.66 + LIGHT[face] - (0.0 if x % 3 else 0.14))
        fold = (math.sin(x * 0.9 + seed) * 0.12 + math.sin(x * 2.1 + seed * 0.7) * 0.04) * folds if face in SIDES else 0.0
        v = base + LIGHT[face] + fold + (0.5 - y / max(h - 1, 1)) * 0.14 + (speckle(x, y, seed) - 0.5) * 0.05
        if face in SIDES and hem and y == h - hem - 1:
            v -= 0.12
        return ramp(ROYAL, v)
    return paint


def leather(base=0.5, seed=0):
    def paint(face, x, y, w, h):
        return ramp(LEATHER, base + LIGHT[face] + (speckle(x, y, seed) - 0.5) * 0.1 + (0.1 if y == 0 else 0.0))
    return paint


def torso_front(lower):
    """Marmorbrust vorn. lower=False: Brustkasten mit Brustbein und Unterkante der Brustmuskeln (die zwei großen
    Facetten sitzen als eigene Platten davor). lower=True: Bauch mit sechs Feldern und Lendenbogen."""
    base = marble(0.48, 300 + lower, roundx=0.32)

    def paint(face, x, y, w, h):
        c = base(face, x, y, w, h)
        if face != 'front':
            return c
        cx = (w - 1) / 2.0
        dx = abs(x - cx)
        if dx < 0.6:
            return mix(c, hexc('#5c6474'), 0.4)                # Mittellinie
        if lower:
            if dx < cx * 0.55 and y < h - 2 and y % 4 == 3:
                return mix(c, hexc('#5c6474'), 0.32)           # Bauchfelder
            if dx < cx * 0.55 and y % 4 == 0:
                return mix(c, hexc('#ffffff'), 0.18)
            if abs(dx - cx * 0.6) < 0.6:
                return mix(c, hexc('#5c6474'), 0.25)           # seitlicher Bogen
        return c
    return paint


def pec(seed, side):
    """Brustmuskel als Platte: zur Mitte und nach oben heller, Unterkante dunkel (liegt auf dem Bauch)."""
    base = marble(0.54, seed, roundx=0.0, grad=0.24)

    def paint(face, x, y, w, h):
        c = base(face, x, y, w, h)
        if face == 'front':
            inner = (x / max(w - 1, 1)) if side > 0 else (1 - x / max(w - 1, 1))   # 0 außen, 1 zur Mitte
            c = mix(c, hexc('#ffffff'), 0.14 * (1 - abs(inner - 0.55) * 1.6))
            if y == h - 1:
                c = mix(c, hexc('#4f5664'), 0.35)
        if face == 'bottom':
            c = mix(c, hexc('#3c4250'), 0.3)
        return c
    return paint


def face_paint(face, x, y, w, h):
    """Kopf 14 × 14: Stirn, tiefe Augenhöhlen mit zwei leuchtenden Schlitzen, Wangen; Seiten und Rücken unter dem Haar."""
    skin = marble(0.36, 401, roundx=0.18, veins=False)
    if face == 'front':
        cx = (w - 1) / 2.0
        d = abs(x - cx)
        if y == 6 and 1.5 <= d <= 4.5:
            return lit(EYE if 2.0 <= d <= 4.0 else hexc('#a9d6ff'), 255 if 2.0 <= d <= 4.0 else 190)
        if y in (5, 7) and 1.0 <= d <= 5.0:
            return ramp(MARBLE, 0.08 if y == 5 else 0.2)      # Höhle über/unter dem Schlitz
        if y in (4,) and 1.0 <= d <= 5.5:
            return ramp(MARBLE, 0.3)
        if y >= 8 and 2.5 <= d <= 5.0:
            return ramp(MARBLE, 0.5 - (y - 8) * 0.04)          # Wangen
        if y >= 8 and d >= 5.5:
            return ramp(MARBLE, 0.36)                          # Kieferschatten
    if face in ('left', 'right') and 5 <= y <= 8 and 4 <= x <= 6:
        return ramp(MARBLE, 0.36)                              # Ohr
    return skin(face, x, y, w, h)


def gorgo(face, x, y, w, h):
    """Gesichtsscheibe der Gorgo: Marmor, Ecken als Schlangenlocken (Gold), damit sie rund wirkt."""
    base = marble(0.5, 520, roundx=0.0, veins=False)
    if face == 'front':
        cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
        if ((x - cx) / (cx + 0.5)) ** 2 + ((y - cy) / (cy + 0.5)) ** 2 > 0.82:
            return ramp(ENAMEL, 0.22)
    return base(face, x, y, w, h)


def gorgo_eye(face, x, y, w, h):
    if face == 'front':
        return lit(EYE if y == 0 or x == 1 else hexc('#a9d6ff'), 255 if x == 1 else 200)
    return ramp(MARBLE, 0.3)


def gorgo_mouth(face, x, y, w, h):
    if face == 'front':
        if y == 1 and x in (1, w - 2):
            return ramp(MARBLE, 0.8)                            # Hauer
        return hexc('#120812') if y == 0 or 0 < x < w - 1 else ramp(MARBLE, 0.3)
    return ramp(MARBLE, 0.3)


def aegis_plate(seed, roll, side):
    """Vorderseite der Aigis, nur nach dem Abstand zur Mitte gemalt (alle vier Platten zeigen dasselbe Bild, keine
    Nähte): innen dunkles Email unter der Gorgo, dann ein Kranz aus goldenen Schlangenwindungen, außen Ringe aus
    Email-Schuppen mit feinen Goldfugen, zum Rand dunkler."""
    def paint(face, x, y, w, h):
        if face == 'front':
            px, py = x - w / 2.0 + 0.5, y - h / 2.0 + 0.5
            r = math.hypot(px, py)
            if r < 6.6:
                return ramp(ENAMEL, 0.2)
            if r < 10.4:
                coil = (r - 6.6) / 1.27
                k = coil - int(coil)
                return ramp(GOLD, 0.3 + 0.42 * math.sin((k - 0.35) / 0.65 * math.pi)) if k > 0.35 else ramp(ENAMEL, 0.12)
            band = (r - 10.4) / 1.6
            k = band - int(band)
            if k < 0.2:
                return ramp(GOLD, 0.4)
            return ramp(ENAMEL, 0.62 - 0.08 * int(band) - 0.18 * k)
        if face == 'back':
            return ramp(LEATHER, 0.45 + (speckle(x, y, seed) - 0.5) * 0.1)
        return ramp(GOLD, 0.52 + LIGHT[face])
    return paint


def sandal(face, x, y, w, h):
    if face in SIDES and y == 0:
        return ramp(GOLD, 0.72 + LIGHT[face])
    if face == 'top' and (x + y) % 4 < 2:
        return ramp(GOLD, 0.6)
    if face == 'top':
        return ramp(MARBLE, 0.62)
    return ramp(LEATHER, 0.42 + LIGHT[face])


def strapped(seed):
    """Wade mit goldenen Sandalenriemen: unten gekreuzt."""
    skin = marble(0.46, seed, roundx=0.34)

    def paint(face, x, y, w, h):
        if face in SIDES and y >= h * 0.5:
            if (x + y) % 5 == 0 or (x - y) % 5 == 0:
                return ramp(GOLD, 0.62 + LIGHT[face])
        return skin(face, x, y, w, h)
    return paint


# ---------------------------------------------------------------------------------------------------------------------
# Hilfen
# ---------------------------------------------------------------------------------------------------------------------

def block(x, y, z, w, h, d, paint, bevel=1.0, side=None):
    """Quader mit gefasten senkrechten Kanten (Kreuz aus zwei Quadern, der zweite minimal gedehnt gegen Flimmern)."""
    return [
        Cube(x + bevel, y, z, w - 2 * bevel, h, d, paint),
        Cube(x, y, z + bevel, w, h, d - 2 * bevel, side or paint, dilate=0.02),
    ]


def rel(a, b):
    return tuple(p - q for p, q in zip(a, b))


# Absolute Drehpunkte (Ruhe)
P_BODY = (0, -26, 0)
P_CHEST = (0, -32, 0)
P_HEAD = (0, -77, -1)
P_ARM_R = (-19, -69, 0.5)
P_ARM_L = (19, -69, 0.5)
P_LEG_R = (-6.5, -24, 0)
P_LEG_L = (6.5, -24, 0)
P_MANTLE = (5, -72, 9.5)
P_SCALES = (21, -94, -16)       # Waage frei im Raum: genau über der linken Hand in `scales_hold`
UPPER_ARM = 26
FOREARM = 24
THIGH = 22
SHIN = 22


# ---------------------------------------------------------------------------------------------------------------------
# Teile
# ---------------------------------------------------------------------------------------------------------------------

def head_part():
    hair = cloud(0.5, 410)
    hair_top = cloud(0.6, 411, strands=True, grad=0.25)
    brow = marble(0.5, 412, roundx=0.0, veins=False, grad=0.0)
    nose = marble(0.48, 413, roundx=0.4, veins=False)

    rays = []
    for i, (roll, length) in enumerate(((-78, 17), (-39, 20), (0, 22), (39, 20), (78, 17))):
        rays.append(Part(f'crown_{i}', (0, 0, 0), (0, 0, roll), cubes=[
            Cube(-2, -length + 4, -1.5, 4, length - 7, 3, ray_paint(420 + i)),
            Cube(-1, -length, -1, 2, 4, 2, cloud(0.86, 430 + i, strands=False, grad=0.0)),
            Cube(-1.5, -length + 3, -1, 3, 1, 2, cloud(0.8, 435 + i, strands=False, grad=0.0)),
        ]))
    crown = Part('crown', (0, -8, 9), cubes=[], children=[Part('crown_fan', (0, 0, 0), (-8, 0, 0), cubes=[
        Cube(-4, -4, -1, 8, 8, 2, ray_paint(440)),                        # Nabe hinter dem Hinterkopf
    ], children=rays)])

    beard = Part('beard', (0, -2.5, -6), cubes=[], children=[
        # ein großer Keil nach vorn-unten aus zwei Facetten, die vorn in einem Grat zusammenlaufen
        Part('beard_r', (0, 0, 0), (-16, -16, 0), cubes=[
            Cube(-4.5, -1, -3, 4.5, 6, 5, cloud(0.5, 450)),
            Cube(-3.5, 5, -2.5, 3.5, 5, 4, cloud(0.54, 451)),
            Cube(-2, 10, -2, 2, 3, 3, cloud(0.58, 452)),
        ]),
        Part('beard_l', (0, 0, 0), (-16, 16, 0), cubes=[
            Cube(0, -1, -3, 4.5, 6, 5, cloud(0.48, 453)),
            Cube(0, 5, -2.5, 3.5, 5, 4, cloud(0.52, 454)),
            Cube(0, 10, -2, 2, 3, 3, cloud(0.56, 455)),
        ]),
    ])
    curls = [Cube(x, y, -8.4, 3, 2, 2.5, cloud(0.58 + 0.06 * (i % 2), 460 + i)) for i, (x, y) in enumerate(
        ((-7, -14.5), (-4.5, -15.2), (-1.5, -14.6), (1.5, -15.2), (4, -14.5)))]
    return Part('head', rel(P_HEAD, P_CHEST), cubes=[
        *block(-7, -14, -7, 14, 14, 14, face_paint, bevel=1.5),
        Cube(-6, -10, -8.5, 12, 2, 2, brow),                              # Brauenbalken
        Cube(-1, -8.5, -9, 2, 5, 2, nose),                                # Nasengrat
        Cube(-5, -4.5, -8.6, 4.5, 2, 2, cloud(0.5, 456)),                # Schnurrbart, zwei hängende Hälften
        Cube(0.5, -4.5, -8.6, 4.5, 2, 2, cloud(0.48, 457)),
        Cube(-6, -3, -8.2, 2, 3, 2, cloud(0.46, 458)),
        Cube(4, -3, -8.2, 2, 3, 2, cloud(0.44, 459)),
        Cube(-7.8, -8, -6, 2, 9, 5, cloud(0.46, 466)),                    # Backenbart rechts
        Cube(5.8, -8, -6, 2, 9, 5, cloud(0.46, 467)),                     # links
        Cube(-5, -3, 0, 10, 4, 7, marble(0.42, 468)),                      # Nacken
        # Haar: Haube, Stirnlocken, Hinterkopf mit Strähnen bis in den Nacken
        *block(-7.5, -16, -7.5, 15, 3, 15, hair_top, bevel=1.5),
        Cube(-8, -14, -2, 16, 14, 10, hair),                              # Hinterkopf
        Cube(-8.5, -13, -6, 2, 6, 5, hair),                               # Schläfenlocke rechts
        Cube(6.5, -13, -6, 2, 6, 5, hair),                                # links
        Cube(-7, 0, 2, 14, 6, 6, cloud(0.5, 469)),                        # Nackenhaar
    ] + curls, children=[beard, crown])


def arm(s):
    sx = -1 if s == 'r' else 1
    skin = marble(0.46, 500 + (s == 'l'), roundx=0.34)
    skin2 = marble(0.50, 502 + (s == 'l'), roundx=0.34)

    def fist(face, x, y, w, h):
        c = skin(face, x, y, w, h)
        if face in ('front', 'left', 'right') and y in (3, 6) and x % 3 == 1:
            c = mix(c, hexc('#4f5664'), 0.35)                  # Fingerfugen
        return c

    hand = Part(f'hand_{s}', (0, FOREARM, 0), cubes=[
        *block(-5, 0, -5, 10, 9, 10, fist, bevel=1.5),
        Cube(-sx * 1 - 1.5, 1, -6.5, 3, 5, 3, skin2),                     # Daumen
    ])
    if s == 'r':
        # Der Blitz liegt quer in der Faust: Ursprung am Griff, Spitze zeigt bei hängendem Arm nach vorn.
        hand.children.append(keraunos_part('keraunos', (0, 4.5, 0), (90, 0, 0)))
    else:
        hand.children.append(aegis_part())
    fore = Part(f'fore_{s}', (0, UPPER_ARM, 0), cubes=[
        *block(-5, 0, -5, 10, 12, 10, skin, bevel=1.5),
        *block(-4.5, 12, -4.5, 9, 12, 9, skin2, bevel=1.5),
        Cube(-5.5, 13, -5.5, 11, 8, 11, gold(0.58, 510 + (s == 'l'), bands=3)),   # Armschiene
    ], children=[hand])
    return Part(f'arm_{s}', rel(P_ARM_R if s == 'r' else P_ARM_L, P_CHEST), cubes=[
        *block(-6, -4, -6, 12, 11, 12, marble(0.52, 512 + (s == 'l'), roundx=0.34), bevel=2),   # Deltamuskel
        *block(-4.5, 6, -4.5, 9, 20, 9, skin, bevel=1.5),
        Cube(-4, 8, -6, 8, 10, 2, skin2),                                  # Bizeps
        Cube(-5, 9, -5, 10, 3, 10, gold(0.62, 514 + (s == 'l'))),          # Armreif
    ], children=[fore])


def aegis_part():
    """Aigis: Achteck Ø 30 aus vier Platten (zwei davon um 45° gedreht), 3 dick; Goldrand 2 breit, 1 erhaben, aus acht
    Segmenten; Gorgonengesicht in der Mitte (Stirn, zwei Augen, Mund, Kinn). Vorderseite -z; Griff am Ursprung."""
    side = 12.4
    plate = []
    for i, roll in enumerate((0, 90, 45, -45)):
        # leicht gestaffelte Dehnung gegen Flimmern der deckungsgleichen Vorderflächen
        plate.append(Part(f'aegis_plate_{i}', (0, 0, 0), (0, 0, roll), cubes=[
            Cube(-15, -side / 2, -3, 30, side, 3, aegis_plate(530 + i, roll, side), dilate=0.015 * i)]))
    rim = []
    for k in range(8):
        rim.append(Part(f'aegis_rim_{k}', (0, 0, 0), (0, 0, k * 45 + 22.5), cubes=[
            Cube(-side / 2 - 0.5, -15.2, -4, side + 1, 2.5, 2, gold(0.66, 540 + k))]))
    gorgo_part = Part('aegis_gorgo', (0, 0, 0), cubes=[
        Cube(-6, -6, -4, 12, 12, 1, gorgo),                                # Gesichtsscheibe
        Cube(-4, -5, -5, 8, 2, 1, marble(0.6, 550, roundx=0.0, veins=False)),   # Stirn
        Cube(-4, -2.5, -5, 3, 2, 1, lambda f, x, y, w, h: lit(EYE, 255) if f == 'front' else ramp(MARBLE, 0.3)),
        Cube(1, -2.5, -5, 3, 2, 1, lambda f, x, y, w, h: lit(EYE, 255) if f == 'front' else ramp(MARBLE, 0.3)),
        Cube(-3, 1, -5, 6, 2, 1, gorgo_mouth),                              # Mund mit zwei Hauern
        Cube(-2, 3.5, -5, 4, 2, 1, marble(0.50, 551, roundx=0.0, veins=False)),  # Kinn
    ])
    return Part('aegis', (0, 4.5, -5.5), cubes=[
        Cube(-1.5, -4, -1, 3, 8, 2, leather(0.5, 560)),                    # Griff
    ], children=plate + rim + [gorgo_part])


def chest_part():
    front_hi = torso_front(False)
    front_lo = torso_front(True)
    back = marble(0.42, 320, roundx=0.3)
    cubes = [
        *block(-13, -14, -8, 26, 14, 16, front_lo, bevel=2),               # Taille 26
        *block(-17, -27, -9, 34, 14, 18, front_hi, bevel=2.5),             # Rippen
        *block(-20, -39, -9, 40, 13, 18, front_hi, bevel=3),               # Brustkorb, Schulterbreite 40 (+ Deltas 44+)
        Cube(-11, -42, -6, 22, 4, 13, marble(0.50, 321)),                  # Trapez
        Cube(-4.5, -46, -5.5, 9, 6, 9, marble(0.40, 322, roundx=0.3)),     # Hals
        Cube(-18, -24, -5, 3, 12, 10, marble(0.42, 323)),                   # seitliche Rippenmuskeln
        Cube(15, -24, -5, 3, 12, 10, marble(0.42, 324)),
        Cube(-15, -36, 8, 30, 20, 3, back),                                # Rückenmuskeln
        # Himation über der linken Schulter: vorn eine Bahn über Deltamuskel und äußere Brust, oben darüber.
        Cube(12, -42, -11, 11, 25, 3, royal(325, 0.5, hem=2)),
        Cube(12, -43, -10, 11, 3, 21, royal(326, 0.56, folds=0.6)),
        Cube(21, -41, -9, 3, 18, 19, royal(327, 0.48, hem=2)),
    ]
    pecs = [
        Part('pec_r', (-9, -33, -9), (0, 14, 0), cubes=[Cube(-8, -5, -3, 16, 11, 3, pec(330, -1))]),
        Part('pec_l', (9, -33, -9), (0, -14, 0), cubes=[Cube(-8, -5, -3, 13, 11, 3, pec(331, 1))]),
    ]
    return Part('chest', rel(P_CHEST, P_BODY), cubes=cubes, children=pecs + [
        head_part(), arm('r'), arm('l'), mantle_parts()])


def mantle_parts():
    m2 = Part('mantle_2', (0, 24, 0), cubes=[Cube(-13, -1, -0.5, 28, 26, 3, royal(602, 0.44, hem=3))])
    m1 = Part('mantle_1', (0, 24, 0), cubes=[Cube(-14, -1, -0.5, 29, 25, 3, royal(601, 0.47))], children=[m2])
    return Part('mantle_0', rel(P_MANTLE, P_CHEST), cubes=[
        Cube(-15, -1, -1, 30, 25, 3, royal(600, 0.5, top_hem=0)),
        Cube(-16, -2, -1.5, 32, 4, 4, royal(603, 0.56, folds=0.5)),          # Wulst über dem Nacken
    ], children=[m1])


def leg(s):
    sx = -1 if s == 'r' else 1
    shin = Part(f'shin_{s}', (0, THIGH, 0), cubes=[
        *block(-5.5, -1, -5.5, 11, 10, 11, marble(0.44, 700 + (s == 'l'), roundx=0.34), bevel=1.5),
        *block(-5, 9, -5, 10, 11, 10, strapped(702 + (s == 'l')), bevel=1.5),
        Cube(-4.5, 1, 3.5, 9, 9, 3, marble(0.42, 704, roundx=0.4)),         # Wade
        Cube(-5.5, 13, -5.5, 11, 3, 11, gold(0.6, 705)),                   # Knöchelring
        Cube(-6, 18, -12, 12, 4, 18, sandal),                              # Sandale 12 × 4 × 18
        Cube(-5, 16, -11, 10, 3, 9, marble(0.50, 706, roundx=0.2, veins=False)),   # Fußrücken
    ])
    return Part(f'leg_{s}', rel(P_LEG_R if s == 'r' else P_LEG_L, P_BODY), cubes=[
        *block(-6, -2, -6, 12, 23, 12, marble(0.43, 710 + (s == 'l'), roundx=0.34), bevel=2),
        Cube(-4, 18, -7.5, 8, 5, 3, marble(0.50, 712)),                     # Knie
    ], children=[shin])


def body_part():
    skirt = []
    # Himation um die Hüften: vorn und hinten eigene Knochen (schwingen mit den Beinen), seitlich fest.
    skirt.append(Part('skirt_f', (0, -4, -8.5), cubes=[], children=[Part('skirt_f_cloth', (0, 0, 0), (-6, 0, 0), cubes=[
        Cube(-13, 0, -2.5, 26, 19, 3, royal(620, 0.52, hem=2)),
        Cube(-4, 0, -3.5, 8, 18, 2, royal(621, 0.6, hem=2)),                 # Mittelfalte
    ])]))
    skirt.append(Part('skirt_b', (0, -4, 8.5), cubes=[], children=[Part('skirt_b_cloth', (0, 0, 0), (6, 0, 0), cubes=[
        Cube(-13, 0, -0.5, 26, 21, 3, royal(622, 0.46, hem=2)),
    ])]))
    return Part('body', P_BODY, cubes=[
        *block(-13, -6, -8, 26, 10, 16, marble(0.42, 640), bevel=2),        # Becken
        *block(-14.5, -8, -9.5, 29, 5, 19, gold(0.62, 641, meander=True), bevel=2),   # Gürtel
        Cube(-3, -8.5, -10.5, 6, 6, 2, gold(0.78, 642)),                   # Schließe
        Cube(-15, -5, -7, 3, 17, 14, royal(643, 0.48, hem=2)),              # Tuch rechts
        Cube(12, -5, -7, 3, 19, 14, royal(644, 0.5, hem=2)),                # Tuch links (länger: der Knoten)
    ], children=[chest_part(), leg('r'), leg('l')] + skirt)


def scales_part():
    """Schicksalswaage: Griffring oben, Säule, Balken 40 breit, zwei Schalen an je einer Stange (eigene Knochen)."""
    g = gold(0.62, 800)

    def pan(name, x):
        """Waagschale an einer Stange: flache Schüssel (Rand 12, Boden 8), darunter ein Knauf."""
        return Part(name, (x, 0, 0), cubes=[
            Cube(-1, 0, -1, 2, 15, 2, gold(0.7, 801, bands=3)),            # Stange
            Cube(-3, 14, -3, 6, 2, 6, gold(0.66, 805)),                    # Bügel über der Schale
            Cube(-6.5, 16, -4, 13, 2, 8, gold(0.62, 802)),                 # Schale: Rand (Kreuz)
            Cube(-4, 16, -6.5, 8, 2, 13, gold(0.64, 803), dilate=0.02),
            Cube(-5, 18, -5, 10, 2, 10, gold(0.48, 804)),                  # Schüsselboden
            Cube(-2, 20, -2, 4, 2, 4, gold(0.4, 806)),                     # Knauf
        ])
    return Part('scales', P_SCALES, cubes=[
        Cube(-3, -6, -1, 6, 2, 2, g), Cube(-3, -6, -1, 2, 6, 2, g), Cube(1, -6, -1, 2, 6, 2, g),   # Ring
        Cube(-1.5, -1, -1.5, 3, 12, 3, gold(0.66, 807, bands=3)),          # Säule
        Cube(-20, 10, -1.5, 40, 3, 3, gold(0.6, 808)),                     # Balken
        Cube(-3, 9, -3, 6, 5, 6, gold(0.72, 809)),                         # Nabe
    ], children=[pan('scales_pan_r', -19), pan('scales_pan_l', 19)])


def zeus():
    return Model('zeus', 512, 512, [body_part(), scales_part()])


# ---------------------------------------------------------------------------------------------------------------------
# Posen
# ---------------------------------------------------------------------------------------------------------------------

# Knochen, die die Animation bewegt (Reihenfolge = Kotlin-Kanäle). Die Werte sind absolut (ersetzen die Grunddrehung);
# fehlt ein Knochen in einer Pose, gilt seine Grunddrehung aus dem Modell.
BONES = ['body', 'chest', 'head', 'beard', 'crown', 'crown_0', 'crown_1', 'crown_2', 'crown_3', 'crown_4',
         'arm_r', 'fore_r', 'hand_r', 'arm_l', 'fore_l', 'hand_l', 'aegis',
         'mantle_0', 'mantle_1', 'mantle_2', 'skirt_f', 'skirt_b',
         'leg_r', 'shin_r', 'leg_l', 'shin_l', 'scales', 'scales_pan_r', 'scales_pan_l']
# Nur über `visible` geschaltet (nicht in den Kanälen): keraunos.


def _base_rotations():
    out = {}
    for root in zeus().parts:
        for part in root.walk():
            out[part.name] = tuple(math.radians(a) for a in part.rot) + (0.0, 0.0, 0.0)
    return {b: out[b] for b in BONES}


DEFAULT = _base_rotations()
CROWN_ROLL = [DEFAULT[f'crown_{i}'][2] for i in range(5)]


def P(*bases, **k):
    """Pose: Grundhaltung, darauf die Basis-Posen (dicts) und dann die Teilwinkel (Radiant, 3 oder 6 Zahlen; None
    blendet aus). `scales` ist ausgeblendet, solange es nicht ausdrücklich gesetzt wird."""
    out = dict(DEFAULT)
    out['scales'] = None
    for b in bases:
        out.update({n: (None if v is None else tuple(v) + (0.0,) * (6 - len(v))) for n, v in b.items()})
    for name, v in k.items():
        out[name] = None if v is None else (v if isinstance(v, W) else tuple(v) + (0.0,) * (6 - len(v)))
    return out


def spread(f, lift=0.0):
    """Kranz gespreizt: alle Zacken um den Faktor f weiter auseinander, dazu ein Stück angehoben."""
    out = {f'crown_{i}': (0.0, 0.0, CROWN_ROLL[i] * f) for i in range(5)}
    out['crown'] = (0.0, 0.0, 0.0, 0.0, -lift, 0.0)
    return out


# --- Vorwärtskinematik (wie preview.collect), um Teile in Weltlage auszurichten ------------------------------------

from preview_model import apply as _apply, mat_mul as _mul, rot_zyx as _rot  # noqa: E402

_PARENT = {}


def _parents():
    if not _PARENT:
        def walk(part, parent):
            _PARENT[part.name] = (parent, part)
            for ch in part.children:
                walk(ch, part.name)
        for top in zeus().parts:
            walk(top, None)
    return _PARENT


def chain(pose, name):
    """Weltdrehung und -lage eines Knochens in einer Pose."""
    parents = _parents()
    path, n = [], name
    while n:
        path.append(parents[n][1])
        n = parents[n][0]
    rot, pos = [[1, 0, 0], [0, 1, 0], [0, 0, 1]], (0.0, 0.0, 0.0)
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


def euler(m):
    """Zerlegt R = Rz(roll) · Ry(yaw) · Rx(pitch) (ModelPart-Reihenfolge) in (pitch, yaw, roll)."""
    sy = max(-1.0, min(1.0, -m[2][0]))
    yaw = math.asin(sy)
    if abs(sy) < 0.9999:
        return (math.atan2(m[2][1], m[2][2]), yaw, math.atan2(m[1][0], m[0][0]))
    return (math.atan2(-m[1][2], m[1][1]), yaw, 0.0)


class W(tuple):
    """Drehung in Weltlage (pitch, yaw, roll): wird nach dem Aufbau der Pose in eine lokale Drehung umgerechnet."""


def resolve(pose):
    """Ersetzt W-Werte durch lokale Winkel, die den Knochen in die gewünschte Weltlage bringen."""
    parents = _parents()
    for name, v in list(pose.items()):
        if isinstance(v, W):
            parent = parents[name][0]
            prot, _ = chain(pose, parent)
            local = _mul([[prot[j][i] for j in range(3)] for i in range(3)], _rot(*v[:3]))
            pose[name] = tuple(round(a, 4) for a in euler(local)) + (0.0, 0.0, 0.0)
    return pose


H = math.pi / 2
# Haltungen der Teile, die viele Posen teilen
BOLT_ALONG = 1.5      # hand_r-Nickwinkel: der Blitz setzt den Unterarm fort (Spitze weg von der Schulter)
CROUCH = dict(leg_r=(-0.55, 0.06, 0.08), shin_r=(0.95, 0, 0), leg_l=(-0.4, -0.06, -0.08), shin_l=(0.8, 0, 0))

IDLE = dict(body=(0, 0, 0), chest=(-0.04, 0.05, 0), head=(0.04, -0.05, 0),
            arm_r=(0.12, 0, 0.24), fore_r=(-1.55, 0.1, 0), hand_r=(0.05, 0, -0.1),
            arm_l=(-0.12, 0, -0.16), fore_l=(-0.42, 0, 0), aegis=(0.22, 0.28, 0),
            leg_r=(0.02, 0, 0.05), leg_l=(-0.08, -0.12, -0.05), shin_l=(0.1, 0, 0))

POSES = {
    # Ruhe: aufrecht, Blitz wie ein Szepter senkrecht in der erhobenen Rechten, Aigis vor der linken Hüfte.
    'idle': P(IDLE),

    # 1 THROW: Blitz weit zurück über der Schulter, Aigis vorn als Gegengewicht, Schritt mit links.
    'throw_windup': P(IDLE, body=(0.05, 0.42, 0, 0, 2, 0), chest=(-0.16, 0.3, 0.08), head=(-0.05, -0.62, 0),
                      arm_r=(-2.2, 0.3, 0.55), fore_r=(-1.25, 0, 0), hand_r=(0.5, 0, 0),
                      arm_l=(-1.35, 0.2, -0.25), fore_l=(-0.35, 0, 0), aegis=(1.2, 0.3, 0),
                      leg_l=(-0.62, 0, -0.1), shin_l=(0.35, 0, 0), leg_r=(0.42, 0, 0.12), shin_r=(0.25, 0, 0),
                      mantle_0=(0.1, 0, 0), mantle_1=(0.05, 0, 0)),
    # Wurf: ganze Körperdrehung nach links, der Arm schnellt nach vorn-unten, der Blitz ist fort.
    'throw_release': P(IDLE, body=(0.28, -0.42, 0, 0, 3, -2), chest=(0.22, -0.38, -0.06), head=(-0.15, 0.55, 0),
                       arm_r=(-1.25, -0.2, 0.15), fore_r=(-0.15, 0, 0), hand_r=(0.2, 0, 0), keraunos=None,
                       arm_l=(0.55, 0, -0.35), fore_l=(-0.5, 0, 0), aegis=(0.4, 0.3, 0),
                       leg_l=(-0.75, 0, -0.08), shin_l=(0.55, 0, 0), leg_r=(0.55, 0, 0.1), shin_r=(0.2, 0, 0),
                       mantle_0=(-0.2, 0, 0), mantle_1=(-0.15, 0, 0), mantle_2=(-0.1, 0, 0)),
    # 9 EMPTY: offen. Die leere rechte Hand vor dem Körper, Aigis gesenkt, Schultern fallen.
    'empty': P(IDLE, body=(0.08, 0, 0, 0, 1, 0), chest=(0.18, 0, 0), head=(0.3, 0.15, 0),
               arm_r=(-0.7, 0.1, 0.12), fore_r=(-0.75, 0, 0), hand_r=(-0.3, 0, 0.2), keraunos=None,
               arm_l=(0.12, 0, -0.1), fore_l=(-0.12, 0, 0), aegis=(0.55, 0.4, 0.1)),

    # 2 BASH: Aigis vor die Brust gezogen, tief in die Knie; Stoß mit dem ganzen Körper.
    'bash_windup': P(IDLE, CROUCH, body=(0.22, -0.3, 0, 0, 6, 2), chest=(0.12, -0.25, 0), head=(-0.12, 0.35, 0),
                     arm_l=(-0.75, 0.45, 0.1), fore_l=(-1.15, 0, 0), aegis=W((-0.1, -0.35, 0)),
                     arm_r=(0.45, 0, 0.35), fore_r=(-0.9, 0, 0), hand_r=(0.3, 0, 0)),
    'bash_hit': P(IDLE, body=(0.38, 0.1, 0, 0, 4, -6), chest=(0.15, 0.15, 0), head=(-0.3, -0.1, 0),
                  arm_l=(-1.5, 0.15, -0.05), fore_l=(-0.2, 0, 0), aegis=W((-0.05, 0.1, 0)),
                  arm_r=(0.6, 0, 0.3), fore_r=(-0.7, 0, 0), hand_r=(0.3, 0, 0),
                  leg_l=(-0.95, 0, -0.06), shin_l=(0.75, 0, 0), leg_r=(0.45, 0, 0.08), shin_r=(0.35, 0, 0),
                  mantle_0=(0.35, 0, 0), mantle_1=(0.2, 0, 0), mantle_2=(0.15, 0, 0)),

    # 3 SLAM: beide Hände heben den Blitz senkrecht über den Kopf (Spitze nach unten), Kranz gespreizt; er rammt ihn
    # vor sich in den Boden.
    'slam_windup': P(IDLE, spread(1.22, 2), body=(-0.02, 0, 0, 0, -1, 0), chest=(-0.08, 0, 0), head=(-0.22, 0, 0),
                     arm_r=(-2.95, 0, 0.16), fore_r=(-0.25, 0, 0), hand_r=(BOLT_ALONG, 0, 0),
                     arm_l=(-2.95, 0, -0.24), fore_l=(-0.25, 0, 0), hand_l=(0, 0, 0), aegis=W((0.0, -1.5, 0.0)),
                     leg_r=(0.05, 0, 0.1), leg_l=(0.05, 0, -0.1)),
    'slam_hit': P(IDLE, CROUCH, spread(1.22, 2), body=(0.3, 0, 0, 0, 7, -2), chest=(0.28, 0, 0), head=(-0.15, 0, 0),
                  arm_r=(-0.8, 0.3, 0.0), fore_r=(-0.15, 0, 0), hand_r=(BOLT_ALONG, 0, 0),
                  arm_l=(-0.8, -0.38, 0.0), fore_l=(-0.15, 0, 0), aegis=W((0.2, -1.4, 0.0)),
                  mantle_0=(0.3, 0, 0), mantle_1=(0.18, 0, 0), mantle_2=(0.1, 0, 0)),
    # 10 STUCK: auf ein Knie gestützt, der Blitz steckt vor ihm im Boden; offen.
    'stuck': P(IDLE, body=(0.22, 0, 0, 0, 15, 0), chest=(0.22, 0, 0), head=(0.3, 0, 0),
               leg_l=(-1.25, 0, -0.05), shin_l=(1.25, 0, 0), leg_r=(0.25, 0, 0.08), shin_r=(1.35, 0, 0),
               arm_r=(-0.75, 0, -0.2), fore_r=(-0.2, 0, 0), hand_r=(BOLT_ALONG, 0, 0.2),
               arm_l=(-0.3, 0, -0.15), fore_l=(-0.9, 0, 0), aegis=W((-0.2, 0.4, 0)),
               mantle_0=(0.3, 0, 0), mantle_1=(0.1, 0, 0), skirt_f=(-0.5, 0, 0)),

    # 4 CALL: Aigis gesenkt, der Blitz zeigt steil in den Himmel, Kopf im Nacken.
    'call_windup': P(IDLE, spread(1.12, 1), body=(-0.06, 0, 0), chest=(-0.22, 0.1, 0.05), head=(-0.55, 0.05, 0),
                     arm_r=(-2.95, 0, 0.22), fore_r=(-0.12, 0, 0), hand_r=(BOLT_ALONG, 0, 0),
                     arm_l=(0.12, 0, -0.32), fore_l=(-0.15, 0, 0), aegis=(0.3, 0.6, 0)),
    'call_hit': P(IDLE, body=(0.12, 0, 0, 0, 2, -1), chest=(0.18, -0.1, 0), head=(0.12, 0, 0),
                  arm_r=(-1.45, 0.05, 0.12), fore_r=(-0.1, 0, 0), hand_r=(BOLT_ALONG, 0, 0),
                  arm_l=(0.2, 0, -0.32), fore_l=(-0.2, 0, 0), aegis=(0.3, 0.6, 0),
                  leg_l=(-0.4, 0, -0.06), shin_l=(0.3, 0, 0), leg_r=(0.2, 0, 0.06)),

    # 5 STEP: er duckt sich, der Mantel schlägt um ihn, dann verschwindet er; er erscheint mit ausgebreiteten Armen.
    'step_crouch': P(IDLE, CROUCH, body=(0.35, 0, 0, 0, 7, 0), chest=(0.45, 0, 0), head=(0.35, 0, 0),
                     arm_r=(-0.7, 0.4, -0.55), fore_r=(-1.5, 0, 0), hand_r=(0.3, 0, 0),
                     arm_l=(-0.7, -0.4, 0.55), fore_l=(-1.5, 0, 0), aegis=(0.2, -0.8, 0),
                     mantle_0=(-0.25, 0, 0), mantle_1=(-0.2, 0, 0), mantle_2=(-0.15, 0, 0)),
    'step_arrive': P(IDLE, spread(1.15, 1), body=(-0.05, 0, 0, 0, -1, 0), chest=(-0.15, 0, 0), head=(-0.2, 0, 0),
                     arm_r=(-0.3, 0, 1.25), fore_r=(-0.35, 0, 0), hand_r=(0.4, 0, 0),
                     arm_l=(-0.3, 0, -1.25), fore_l=(-0.35, 0, 0), aegis=(0.2, 0.9, 0),
                     leg_r=(0.05, 0, 0.18), leg_l=(0.05, 0, -0.18),
                     mantle_0=(0.55, 0, 0), mantle_1=(0.35, 0, 0), mantle_2=(0.25, 0, 0)),

    # 6 GAZE: die Aigis mit beiden Händen vor dem Gesicht, die Gorgo blickt nach vorn, der Kopf dahinter verborgen.
    'gaze_windup': P(IDLE, body=(0.0, 0, 0, 0, 1, 0), chest=(-0.06, 0, 0), head=(0.25, 0, 0),
                     arm_l=(-1.25, 0.85, 0.25), fore_l=(-1.05, 0, 0), aegis=W((-0.05, 0, 0)),
                     arm_r=(-1.1, -0.5, -0.2), fore_r=(-1.25, 0, 0), hand_r=(0.6, 0, 0),
                     leg_l=(-0.3, 0, -0.08), shin_l=(0.2, 0, 0), leg_r=(0.15, 0, 0.1)),
    'gaze_hit': P(IDLE, body=(0.1, 0, 0, 0, 2, -2), chest=(0.08, 0, 0), head=(0.25, 0, 0),
                  arm_l=(-1.4, 0.85, 0.25), fore_l=(-0.85, 0, 0), aegis=W((-0.12, 0, 0)),
                  arm_r=(-1.25, -0.5, -0.2), fore_r=(-1.05, 0, 0), hand_r=(0.6, 0, 0),
                  leg_l=(-0.45, 0, -0.08), shin_l=(0.3, 0, 0), leg_r=(0.25, 0, 0.1)),

    # 7 SCALES: der Blitz steckt neben ihm im Boden (die Rechte ruht darauf), die Linke hält die Waage hoch.
    'scales_hold': P(IDLE, chest=(-0.08, -0.08, 0), head=(-0.3, 0.2, 0),
                     arm_l=(-2.75, 0, 0.42), fore_l=(-0.2, 0, 0), hand_l=(0, 0, 0), aegis=W((0.0, -1.45, 0.0)),
                     arm_r=(-0.12, 0, 0.42), fore_r=(-0.35, 0, 0), hand_r=(BOLT_ALONG, 0, 0),
                     scales=(0, 0, 0), scales_pan_r=(0, 0, 0), scales_pan_l=(0, 0, 0)),
    # je Himmelsblitz: der Blitz fährt hoch und zeigt auf die Schale
    'scales_cast': P(IDLE, chest=(-0.04, -0.2, 0), head=(-0.2, 0.0, 0),
                     arm_l=(-2.75, 0, 0.42), fore_l=(-0.2, 0, 0), hand_l=(0, 0, 0), aegis=W((0.0, -1.45, 0.0)),
                     arm_r=(-1.7, -0.2, 0.25), fore_r=(-0.1, 0, 0), hand_r=(BOLT_ALONG, 0, 0),
                     scales=(0, 0, 0.06), scales_pan_r=(0, 0, -0.06), scales_pan_l=(0, 0, -0.06)),

    # 8 STAGGER: taumelt zurück, sinkt auf ein Knie, Aigis fällt zur Seite, Kopf gesenkt.
    'stagger': P(IDLE, body=(-0.12, 0.15, 0.08, 0, 14, 3), chest=(0.45, 0.1, 0.12), head=(0.6, -0.2, 0.1),
                 leg_r=(0.35, 0, 0.12), shin_r=(1.45, 0, 0), leg_l=(-1.05, 0, -0.15), shin_l=(1.2, 0, 0),
                 arm_l=(0.25, 0, -0.55), fore_l=(-0.1, 0, 0), aegis=W((0.35, -0.9, -0.6)),
                 arm_r=(-0.55, 0, 0.25), fore_r=(-0.6, 0, 0), hand_r=(1.2, 0, 0),
                 mantle_0=(0.2, 0, 0), mantle_1=(0.15, 0, 0), skirt_f=(-0.4, 0, 0)),

    # 11 GUARD_UP: Aigis waagerecht über den Kopf gerissen, Blick nach oben; dann Stoß nach oben.
    'guard_windup': P(IDLE, CROUCH, body=(0.05, 0, 0, 0, 5, 0), chest=(-0.05, 0, 0), head=(-0.5, 0, 0),
                      arm_l=(-2.35, 0.4, 0.35), fore_l=(-1.0, 0, 0), aegis=W((-1.5, 0.0, 0.0)),
                      arm_r=(0.3, 0, 0.45), fore_r=(-0.8, 0, 0), hand_r=(0.3, 0, 0)),
    'guard_hit': P(IDLE, body=(-0.05, 0, 0, 0, -2, 0), chest=(-0.12, 0, 0), head=(-0.55, 0, 0),
                   arm_l=(-2.95, 0.3, 0.12), fore_l=(-0.3, 0, 0), aegis=W((-1.57, 0.0, 0.0)),
                   arm_r=(0.2, 0, 0.5), fore_r=(-0.6, 0, 0), hand_r=(0.3, 0, 0),
                   leg_r=(0.0, 0, 0.12), leg_l=(0.0, 0, -0.12)),

    # 12 RISE: Auftritt; Startpose sitzend auf dem Thron (die Animation blendet daraus in 40 Ticks zu idle).
    'rise': P(IDLE, body=(-0.08, 0, 0, 0, 20, 4), chest=(0.06, 0, 0), head=(0.3, 0, 0),
              leg_r=(-1.5, 0, 0.12), shin_r=(1.45, 0, 0), leg_l=(-1.5, 0, -0.12), shin_l=(1.45, 0, 0),
              arm_r=(-0.55, 0, 0.18), fore_r=(-0.85, 0, 0), hand_r=(0.4, 0, 0),
              arm_l=(-0.6, 0, -0.15), fore_l=(-0.4, 0, 0), aegis=(1.1, 0.2, 0),
              skirt_f=(-1.2, 0, 0), skirt_b=(0.0, 0, 0), mantle_0=(0.1, 0, 0), mantle_1=(-0.25, 0, 0)),
    # 13 PHASE: beide Arme auseinander, Kranz spreizt sich.
    'phase': P(IDLE, spread(1.25, 3), body=(-0.05, 0, 0), chest=(-0.2, 0, 0), head=(-0.3, 0, 0),
               arm_r=(-0.5, 0, 1.75), fore_r=(-0.3, 0, 0), hand_r=(0.6, 0, 0),
               arm_l=(-0.5, 0, -1.75), fore_l=(-0.3, 0, 0), aegis=(0.3, 0.9, 0),
               leg_r=(0, 0, 0.14), leg_l=(0, 0, -0.14),
               mantle_0=(0.45, 0, 0), mantle_1=(0.25, 0, 0), mantle_2=(0.2, 0, 0)),
    # 14 YIELD: senkt den Blitz, Spitze zum Boden, Kopf leicht geneigt.
    'yield': P(IDLE, chest=(0.06, 0, 0), head=(0.38, 0.12, 0.06),
               arm_r=(-0.28, 0, 0.12), fore_r=(-0.3, 0, 0), hand_r=(BOLT_ALONG, 0, 0),
               arm_l=(0.05, 0, -0.1), fore_l=(-0.15, 0, 0), aegis=(0.4, 0.3, 0)),
}
POSES = {name: resolve(pose) for name, pose in POSES.items()}


def hang_scales(pose):
    """Setzt die Waage so, dass ihr Griffring in der linken Faust hängt (Ring oben bei lokal y = -6)."""
    rot, pos = chain(pose, 'hand_l')
    fist = _apply(rot, (0, 4.5, 0))
    target = (pos[0] + fist[0], pos[1] + fist[1] + 7.0, pos[2] + fist[2])
    v = pose['scales']
    pose['scales'] = tuple(v[:3]) + tuple(round(t - b, 3) for t, b in zip(target, P_SCALES))
    return pose


for _n in ('scales_hold', 'scales_cast'):
    hang_scales(POSES[_n])

HEIGHT_EXCLUDE = ('crown', 'keraunos', 'scales')


# ---------------------------------------------------------------------------------------------------------------------
# Kotlin-Block für ZeusModel.kt
# ---------------------------------------------------------------------------------------------------------------------

def bone_paths():
    paths = {}

    def walk(part, prefix):
        path = f'{prefix}/{part.name}' if prefix else part.name
        paths[part.name] = path
        for ch in part.children:
            walk(ch, path)
    for p in zeus().parts:
        walk(p, '')
    return paths


def kotlin_block():
    paths = bone_paths()
    lines = ['    // <keys> erzeugt von tools/hha_assets/zeus_model.py --kotlin; nicht von Hand ändern', '    object Keys {']
    lines.append('        /** Knochenpfade in Kanalreihenfolge (je 6 Werte: pitch, yaw, roll, dx, dy, dz). */')
    lines.append('        val BONES = arrayOf(' + ', '.join(f'"{paths[b]}"' for b in BONES) + ')')
    lines.append(f'        const val KERAUNOS_PATH = "{paths["keraunos"]}"')
    for i, b in enumerate(BONES):
        lines.append(f'        const val {b.upper()} = {i}')
    lines.append(f'        const val COUNT = {len(BONES)}')
    lines.append('        /** Schlüsselposen: je Knochen 6 Werte; die Liste nennt Knochen, die in der Pose ausgeblendet sind. */')
    for name, pose in POSES.items():
        vals, hidden = [], []
        for i, b in enumerate(BONES):
            v = pose.get(b, DEFAULT[b])
            if v is None:
                hidden.append(i)
                v = (0.0,) * 6
            vals.extend(v)
        data = ','.join(('%.4f' % x).rstrip('0').rstrip('.') if x else '0' for x in vals)
        lines.append(f'        val {name.upper()} = ZeusKey.of("{data}", intArrayOf({", ".join(map(str, hidden))}))')
    lines.append('    }')
    lines.append('    // </keys>')
    return '\n'.join(lines)


def write_kotlin():
    path = os.path.join(HERE, '..', '..', 'src', 'main', 'kotlin', 'dev', 'henny', 'hha', 'client', 'model', 'ZeusModel.kt')
    with open(path, encoding='utf8') as f:
        text = f.read()
    if '// <keys>' not in text:
        raise SystemExit('Marker // <keys> fehlt in ZeusModel.kt')
    new = re.sub(r'    // <keys>.*?// </keys>', lambda m: kotlin_block(), text, flags=re.S)
    with open(path, 'w', encoding='utf8', newline='\n') as f:
        f.write(new)
    print(f'ZeusModel.kt: {len(POSES)} Schlüsselposen geschrieben')


if __name__ == '__main__':
    if '--kotlin' in sys.argv:
        write_kotlin()
