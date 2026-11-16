"""Hades, König der Toten (Plouton): ein strenger, reicher Herrscher in schwarzer Bronze, kein Teufel und kein Skelett.

Erkennungsmerkmal ist der korinthische Helm der Unsichtbarkeit (Kynee) mit hohem, nach hinten geschwungenem Helmbusch,
dazu der Bident mit zwei weit offenen Zinken. Kerberos lebt in der Rüstung weiter: beide Schulterstücke sind
Hundeköpfe, die Gürtelschnalle ist der dritte. In der Brust brennt hinter fünf Bronzestäben die Seelenlaterne.
Ab Form 1 wachsen ihm Schwingen aus Leichentuch.

Koordinaten wie überall: 1/16 Block, +y nach unten, -z ist vorn, Füße bei y = 24. Rechts ist -x.
Der Knochen `bident` ist in sich geschlossen (Ursprung am Griff, alles Zugehörige darunter), weil der
Geschoss-Renderer genau diesen Knochen als fliegenden Bident zeichnet (Plan 6.5).

Animation: Alle Schlüsselposen stehen in KEYS als Kanalwerte (Winkel in Radiant, Versätze in Einheiten). Daraus
entstehen POSES für die Vorschau und, beim Erzeugen über boss_models.py (Hook `variants`), der generierte Block
`HadesKeys` in client/model/HadesModel.kt. So stehen dieselben Winkel in Python und Kotlin. Die Hände werden über
einen kleinen IK-Löser an Ziele gesetzt (Griff, Schaft, Rücken); auch das Ergebnis landet als Zahl in beiden Welten.
"""
import math
import os
import re

from bosskit import BRONZE, ASHEN, Cube, FACE_LIGHT, GREEN, LINING, OBSIDIAN, SHROUD, ember
from modelkit import Model, Part, hexc, mix, ramp, rgb, speckle

SIDES = ('front', 'back', 'left', 'right')
HAIR = rgb('#06060a', '#0e0f16', '#191b26', '#262a3a', '#3a3f55')

# ---------------------------------------------------------------------------------------------------------------
# Maler: glatte Verläufe, harte Lichtkante oben an Graten, 1 Texel Schattensaum unten. Korn höchstens ± 2,5 %.
# ---------------------------------------------------------------------------------------------------------------


def _light(face, y, h, grad):
    return FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad


def _grain(x, y, seed, amp=0.022):
    return (speckle(x, y, seed) - 0.5) * 2 * amp


def blackbronze(base=0.42, seed=0, rim=0.2, sheen=0.07, grad=0.16, seam=0.09):
    """Schwarze Bronze, poliert: weicher Glanzstreifen, helle Oberkante, dunkler Saum unten."""
    base -= 0.07

    def paint(face, x, y, w, h):
        v = base + _light(face, y, h, grad) + _grain(x, y, seed)
        if face in SIDES:
            if w >= 4:
                v += sheen * math.exp(-((x / (w - 1) - 0.3) ** 2) / 0.03)
            if y == 0:
                v += rim
            elif y == h - 1 and h > 2:
                v -= seam
            if x == 0 or x == w - 1:
                v += rim * 0.3
        elif face == 'top' and (x in (0, w - 1) or y in (0, h - 1)):
            v += rim * 0.55
        return ramp(OBSIDIAN, v)
    return paint


def gilt(base=0.52, seed=0, rim=0.17):
    """Altgold, matt. Leuchtet nie."""
    def paint(face, x, y, w, h):
        v = base + _light(face, y, h, 0.18) + _grain(x, y, seed, 0.018)
        if face in SIDES:
            if y == 0:
                v += rim
            elif y == h - 1 and h > 2:
                v -= 0.1
        elif face == 'top' and (x in (0, w - 1) or y in (0, h - 1)):
            v += rim * 0.6
        return ramp(BRONZE, v)
    return paint


def drape(material=SHROUD, base=0.5, seed=0, folds=0.06, period=5.0, grad=0.14):
    """Schwerer Stoff: breite, weiche Falten statt Rauschen."""
    def paint(face, x, y, w, h):
        v = base + _light(face, y, h, grad) + _grain(x, y, seed, 0.015)
        v += folds * math.sin((x + seed * 0.37) * math.tau / period)
        return ramp(material, v)
    return paint


def skin(base=0.5, seed=0):
    def paint(face, x, y, w, h):
        return ramp(ASHEN, base + _light(face, y, h, 0.2) + _grain(x, y, seed, 0.015))
    return paint


def faint_soul(strength=95):
    """Schwach leuchtender Saum (Mantel, Schwingen, Helmbusch)."""
    return ember(0.32, strength)


def ridge(material=OBSIDIAN, base=0.42, seed=0, along='x'):
    """Grat in der Mitte einer Fläche: Lichtspalte und Schattenspalte nebeneinander (Muskelpanzer, Beinschiene)."""
    inner = blackbronze(base, seed)

    def paint(face, x, y, w, h):
        c = inner(face, x, y, w, h)
        if face == 'front':
            mid = (w - 1) / 2.0
            if abs(x - mid) < 0.6 or (w % 2 == 0 and abs(x - mid) < 1.0 and x < mid):
                return ramp(BRONZE, 0.66 + _light(face, y, h, 0.16))
            if abs(x - mid) < 1.6 and x > mid:
                return mix(c, hexc('#05060a'), 0.35)
        return c
    return paint


# ---------------------------------------------------------------------------------------------------------------
# Kanäle der Animation (Reihenfolge = Index im Kotlin-Feld)
# ---------------------------------------------------------------------------------------------------------------

CHANNELS = [
    'root_x', 'root_y', 'root_z', 'root_rx', 'root_ry', 'root_rz',
    'torso_rx', 'torso_ry', 'torso_rz',
    'chest_rx', 'chest_ry', 'chest_rz',
    'head_rx', 'head_ry', 'head_rz',
    'visor_rx', 'visor_dy',
    'arm_r_rx', 'arm_r_ry', 'arm_r_rz', 'fore_r_rx', 'fore_r_ry', 'hand_r_rx', 'hand_r_rz', 'grip_r',
    'bid_rx', 'bid_ry', 'bid_rz',
    'arm_l_rx', 'arm_l_ry', 'arm_l_rz', 'fore_l_rx', 'fore_l_ry', 'hand_l_rx', 'hand_l_rz', 'grip_l',
    'thigh_r_rx', 'thigh_r_ry', 'thigh_r_rz', 'shin_r_rx', 'foot_r_rx',
    'thigh_l_rx', 'thigh_l_ry', 'thigh_l_rz', 'shin_l_rx', 'foot_l_rx',
    'cape', 'cape_wrap', 'wings',
]
CH = {name: i for i, name in enumerate(CHANNELS)}

# Knochen -> (Kanal pitch, yaw, roll) und optionale Versatz-Kanäle (dx, dy, dz).
BONE_CHANNELS = {
    'root': (('root_rx', 'root_ry', 'root_rz'), ('root_x', 'root_y', 'root_z')),
    'torso': (('torso_rx', 'torso_ry', 'torso_rz'), None),
    'chest': (('chest_rx', 'chest_ry', 'chest_rz'), None),
    'head': (('head_rx', 'head_ry', 'head_rz'), None),
    'visor': (('visor_rx', None, None), (None, 'visor_dy', None)),
    'arm_r': (('arm_r_rx', 'arm_r_ry', 'arm_r_rz'), None),
    'fore_r': (('fore_r_rx', 'fore_r_ry', None), None),
    'hand_r': (('hand_r_rx', None, 'hand_r_rz'), None),
    'bident': (('bid_rx', 'bid_ry', 'bid_rz'), None),
    'arm_l': (('arm_l_rx', 'arm_l_ry', 'arm_l_rz'), None),
    'fore_l': (('fore_l_rx', 'fore_l_ry', None), None),
    'hand_l': (('hand_l_rx', None, 'hand_l_rz'), None),
    'thigh_r': (('thigh_r_rx', 'thigh_r_ry', 'thigh_r_rz'), None),
    'shin_r': (('shin_r_rx', None, None), None),
    'foot_r': (('foot_r_rx', None, None), None),
    'thigh_l': (('thigh_l_rx', 'thigh_l_ry', 'thigh_l_rz'), None),
    'shin_l': (('shin_l_rx', None, None), None),
    'foot_l': (('foot_l_rx', None, None), None),
}

CAPE_STRIPS, CAPE_LINKS, CAPE_LEN = 5, 4, 16
WING_COUNT = 5
WING_LENGTHS = (46, 55, 62, 56, 46)
WING_WIDTHS = (5, 7, 8)  # Ansatz 5 breit, zur Spitze breiter, damit der Fächer geschlossen wirkt
SKIRT_COUNT = 8
GRIP_Y = 17.5  # Faustmitte im Unterarm (Griffpunkt des Bidents)


def wing_visible(form, i):
    return form >= 2 or (form == 1 and 1 <= i <= 3)


WING_FORM_BASE = (0.0, 0.45, 0.85)


def wing_spread(form, w):
    base = WING_FORM_BASE[min(max(form, 0), 2)]
    return base * (1.0 + min(0.0, w)) + max(0.0, w) * (1.0 - base)


# ---------------------------------------------------------------------------------------------------------------
# Modell
# ---------------------------------------------------------------------------------------------------------------


def build_head():
    shell = blackbronze(0.44, 10, rim=0.22, grad=0.24)
    shell_low = blackbronze(0.36, 11, rim=0.16, grad=0.18)

    def face_paint(face, x, y, w, h):
        base = skin(0.42, 12)(face, x, y, w, h)
        if face != 'front':
            return base
        cx = (w - 1) / 2.0
        d = abs(x - cx)
        # Augen: glühende Schlitze tief unter der Braue.
        if y == 1 and 2.4 < d < 4.6:
            return ember(0.82, 255)
        if y in (0, 2) and 2.0 < d < 4.8:
            return ramp(ASHEN, 0.12)
        if y >= 7 and d < 2.2:  # Mund im Bartansatz
            return ramp(HAIR, 0.3)
        if y in (5, 6) and d > 2.6:  # hohle Wangen
            return ramp(ASHEN, 0.28)
        return base

    def brow_paint(face, x, y, w, h):
        c = shell(face, x, y, w, h)
        if face == 'front':
            cx = (w - 1) / 2.0
            if y == h - 1:
                return ramp(OBSIDIAN, 0.16)  # Schatten über den Augen
            if abs(x - cx) < 0.6:
                return ramp(OBSIDIAN, 0.7)
        return c

    def flap_paint(side):
        """Wangenklappe; der Augenschlitz sitzt oben innen und steigt nach außen an."""
        def paint(face, x, y, w, h):
            c = shell(face, x, y, w, h)
            if face != 'front':
                return c
            inner = (w - 1 - x) if side == 'r' else x  # 0 = innen am Nasenschutz
            if y == 0 and inner in (1, 2, 3):
                return ember(0.9, 255) if inner in (2, 3) else ember(0.7, 230)
            if y == 1 and inner == 1:
                return ember(0.7, 230)
            if y == 1 and inner in (2, 3):
                return ramp(OBSIDIAN, 0.08)
            if y == 0 and inner == 4:
                return ramp(OBSIDIAN, 0.1)
            if inner == 0:
                return ramp(OBSIDIAN, 0.22 + _light(face, y, h, 0.1))
            if y == h - 1:
                return ramp(BRONZE, 0.5)
            return c
        return paint

    visor = Part('visor', (0, -15, -6), cubes=[
        Cube(-7, 1, -1.6, 14, 3, 2, brow_paint),                       # Braue, Kopf y -14..-11
        Cube(-7, 4, -1.2, 5, 8, 2, flap_paint('r')),                   # Wangenklappen y -11..-3
        Cube(2, 4, -1.2, 5, 8, 2, flap_paint('l')),
        Cube(-1, 4, -1.9, 2, 6, 2, blackbronze(0.5, 13, rim=0.3)),     # Nasenschutz 2 breit
        Cube(-7.5, 3, 0.6, 2, 9, 4, shell),                            # Seitenteile vor den Ohren
        Cube(5.5, 3, 0.6, 2, 9, 4, shell),
    ])

    # Helmbusch: sechs Glieder vom Scheitel in den Nacken; Unterkante schwarz, oben ein Smaragdsaum.
    def crest_paint(seg):
        def paint(face, x, y, w, h):
            if face == 'top':
                return faint_soul(55) if (x == 1 and 1 <= seg <= 4) else ramp(LINING, 0.62 + _grain(x, y, 30 + seg, 0.02))
            if face in ('left', 'right'):
                strands = 0.05 * math.sin(x * 1.3 + seg * 2.1)
                if y == 0:
                    return ramp(LINING, 0.7)
                if y == 1:
                    return ramp(LINING, 0.45 + strands)
                return ramp(HAIR, 0.36 + (0.5 - y / max(h - 1, 1)) * 0.3 + strands + _grain(x, y, 31 + seg, 0.015))
            if face == 'bottom':
                return ramp(HAIR, 0.08)
            return ramp(HAIR, 0.3 + (0.5 - y / max(h - 1, 1)) * 0.3)
        return paint

    # (Länge, Höhe, Knick in Grad). Die Glieder überlappen, damit die Oberkante geschlossen bleibt.
    crest_segments = [(6, 5, 0), (6, 8, -22), (6, 8, -22), (6, 8, -30), (8, 7, -26), (9, 5, -18)]
    crest = None
    current = None
    for i, (length, height, bend) in enumerate(crest_segments):
        cube = Cube(-1.5, -height, -3, 3, height, length + 4, crest_paint(i))
        if current is None:
            current = crest = Part('crest', (0, -12.5, -6.2), (58, 0, 0), cubes=[cube])
        else:
            nxt = Part(f'crest_{i}', (0, 0, prev_len), (bend, 0, 0), cubes=[cube])
            current.add(nxt)
            current = nxt
        prev_len = length

    def braid(seed):
        def paint(face, x, y, w, h):
            v = 0.5 + _light(face, y, h, 0.22)
            v += 0.09 * math.sin((x + (y // 2) * 2) * 1.6 + seed)  # geflochtene Stränge, 2 Texel breit
            return ramp(HAIR, v)
        return paint

    ring = gilt(0.5, 14, rim=0.22)
    beard = Part('beard_a', (0, -3.5, -5.5), (-20, 0, 0), cubes=[
        Cube(-3, -1, -2, 6, 5, 4, braid(1)),
        Cube(-3.5, 2.5, -2.5, 7, 1, 5, ring),
    ], children=[
        Part('beard_b', (0, 4, 0), (6, 0, 0), cubes=[
            Cube(-2.5, 0, -1.8, 5, 3, 4, braid(2)),
            Cube(-3, 1.5, -2.3, 6, 1, 5, ring),
        ], children=[
            Part('beard_c', (0, 3, 0), (6, 0, 0), cubes=[
                Cube(-2, 0, -1.5, 4, 3, 3, braid(3)),
                Cube(-2.5, 1, -2, 5, 1, 4, ring),
                Cube(-1.5, 2.5, -1, 3, 2, 2, braid(4)),
            ]),
        ]),
    ])

    head = Part('head', (0, -21, 0), cubes=[
        Cube(-3, -4, -3, 6, 5, 6, skin(0.36, 15)),                     # Hals
        Cube(-4.5, -12, -5, 9, 10, 9, face_paint),                      # Gesicht unter dem Helm
        Cube(-6, -14, -6.5, 12, 7, 13, shell),                          # Kalotte
        Cube(-5, -16, -5.5, 10, 2, 11, shell),                          # Fase oben
        Cube(-6.5, -13, -5.5, 1, 5, 11, shell_low),                     # Fasen seitlich
        Cube(5.5, -13, -5.5, 1, 5, 11, shell_low),
        Cube(-6, -7, -3, 12, 5, 9.5, shell_low),                        # Wangen/Nacken hinten
        Cube(-6.5, -4, 3.5, 13, 3, 4, gilt(0.5, 16)),                   # Nackenschutz mit Goldsaum
    ], children=[visor, crest, beard])
    return head


def build_dog(side, sign):
    """Hundekopf als Schulterstück: Schnauze nach außen-vorn, Ohren angelegt, Fänge als helle Kante."""
    hide = blackbronze(0.42, 40, rim=0.22, grad=0.22)

    def cranium(face, x, y, w, h):
        c = hide(face, x, y, w, h)
        outer = (face == 'right' and sign < 0) or (face == 'left' and sign > 0)
        inner = (face == 'left' and sign < 0) or (face == 'right' and sign > 0)
        if face in ('left', 'right'):
            # Seiten: matte Goldscheibe als Auge nahe der Schnauze (vorn = kleines x bei 'right', großes bei 'left').
            fx = x if face == 'right' else w - 1 - x
            if fx in (2, 3) and y in (2, 3):
                return ramp(BRONZE, 0.86 if y == 2 else 0.68)
            if fx in (1, 4) and y in (2, 3):
                return ramp(OBSIDIAN, 0.12)
        return c

    def snout(face, x, y, w, h):
        c = hide(face, x, y, w, h)
        if face == 'front' and y == 0 and 1 <= x <= w - 2:
            return ramp(OBSIDIAN, 0.2)  # Nasenrücken-Schatten
        if face in SIDES and y == h - 1:
            return ramp(OBSIDIAN, 0.95)  # Fänge: helle Kante
        return c

    def jaw(face, x, y, w, h):
        if face in SIDES and y == 0:
            return ramp(OBSIDIAN, 0.95) if x % 2 == 0 or face in ('left', 'right') else ramp(OBSIDIAN, 0.05)
        return hide(face, x, y, w, h)

    def ear_paint(face, x, y, w, h):
        if face == 'top' and y < 2:
            return ramp(OBSIDIAN, 0.2)
        if face in SIDES and y == 0 and x in (0, w - 1):
            return ramp(OBSIDIAN, 0.15)
        return hide(face, x, y, w, h)

    plate = blackbronze(0.36, 41, rim=0.24)
    trim = gilt(0.5, 42)
    return Part(f'pauld_{side}', (sign * 2.5, -0.5, 0), (0, -sign * 45, sign * 14), cubes=[
        Cube(-5.5, -5, -5, 11, 9, 11, cranium),                         # Schädel 11 x 9 x 12 (mit Stirn)
        Cube(-4.5, -6, -6, 9, 3, 3, hide),                              # Stirnwulst über den Augen
        Cube(-3, -2.5, -10, 6, 4, 5, snout),                            # Schnauze 5 lang
        Cube(-2, -3, -11, 4, 2, 2, hide),                               # Nasenspiegel
        Cube(-1.5, -4, -8, 3, 2, 4, hide),                              # Nasenrücken
        Cube(-2.5, 2.5, -9.5, 5, 2, 5, jaw),                            # Unterkiefer, leicht offen
        Cube(-5, -7, 0, 3, 3, 6, ear_paint),                            # angelegte Ohren
        Cube(2, -7, 0, 3, 3, 6, ear_paint),
        Cube(-6, 3, -5, 12, 4, 12, plate),                              # Mähnenplatte über dem Oberarm
        Cube(-6.5, 6, -5.5, 13, 2, 13, trim),
    ])


def build_hand(side, sign):
    flesh = skin(0.24, 50)
    knuckle = skin(0.3, 51)
    fingers, tips = [], []
    for k, z in enumerate((-2.6, -0.9, 0.8, 2.5)):
        length = 3 if k in (0, 3) else 3.5
        fingers.append(Cube(-1.5, 0, z - 1, 3, round(length), 2, knuckle))
        tips.append(Cube(-1.0, 0, z - 1, 2, 3, 2, flesh))
    return Part(f'hand_{side}', (0, 14, 0), cubes=[
        Cube(-2, -0.5, -3.5, 4, 5, 7, flesh),
        Cube(-2.5, -1, -3.8, 5, 2, 7.6, gilt(0.48, 52)),               # Armreif am Handgelenk
    ], children=[
        Part(f'fingers_{side}', (sign * -0.5, 4.5, 0), cubes=fingers, children=[
            Part(f'fingers_{side}_tip', (0, 3, 0), cubes=tips),
        ]),
        Part(f'thumb_{side}', (sign * -0.5, 1.5, -3.2), (0, 0, 0), cubes=[
            Cube(-1, 0, -1, 2, 4, 2, knuckle),
        ]),
    ])


def build_arm(side, sign, extra=()):
    sleeve = drape(SHROUD, 0.42, 60 + sign, folds=0.05, period=4.0)
    vambrace = ridge(OBSIDIAN, 0.44, 61)
    fore = Part(f'fore_{side}', (0, 16, 0), cubes=[
        Cube(-4, -1.5, -4, 8, 4, 8, blackbronze(0.4, 62, rim=0.24)),   # Ellbogenkappe
        Cube(-4, 2, -4, 8, 12, 8, vambrace),                            # Schiene
        Cube(-4.5, 1.5, -4.5, 9, 2, 9, gilt(0.5, 63)),
    ], children=[build_hand(side, sign)] + list(extra))
    return Part(f'arm_{side}', (sign * 14.5, -17.5, 0), cubes=[
        Cube(-3.5, 0, -3.5, 7, 16, 7, sleeve),
        Cube(-4, 9, -4, 8, 2, 8, gilt(0.54, 64)),                       # Armring
    ], children=[build_dog(side, sign), fore])


def build_bident():
    def shaft(face, x, y, w, h):
        band = (y - 3) % 13
        if band in (0, 1):
            return ramp(BRONZE, 0.56 + (0.12 if band == 0 else 0.0) + FACE_LIGHT[face])
        v = 0.38 + FACE_LIGHT[face] + (0.12 if x == 0 else 0.0) + _grain(x, y, 70, 0.015)
        return ramp(OBSIDIAN, v)

    def wrap(face, x, y, w, h):
        v = 0.38 + FACE_LIGHT[face] + (0.1 if ((x + y) // 2) % 2 == 0 else -0.04)
        return ramp(LINING, v)

    def prong_paint(sign, tip):
        """Zinke: schwarze Bronze, die Innenkante (zur anderen Zinke) glüht."""
        body = blackbronze(0.48, 71, rim=0.26)
        inner_face = 'left' if sign < 0 else 'right'

        def paint(face, x, y, w, h):
            if face == inner_face:
                return ember(0.62 + 0.3 * (1 - y / max(h - 1, 1)) * (1 if tip else 0.5), 255)
            if face in ('front', 'back'):
                inner_col = (w - 1) if (sign < 0) == (face == 'front') else 0
                if x == inner_col:
                    return ember(0.5, 230)
            if tip and face == 'top':
                return ember(0.95, 255)
            return body(face, x, y, w, h)
        return paint

    def prong(name, sign):
        # 4 -> 3 -> 2 verjüngt, leicht nach innen gebogen; 22 lang.
        segs = [(4, 8, 3, -sign * 5), (3, 7, 3, sign * 9), (2, 7, 2, sign * 10)]
        root = None
        cur = None
        for i, (w, l, d, bend) in enumerate(segs):
            cube = Cube(-w / 2.0, -l - (1 if i else 0), -d / 2.0, w, l + (1 if i else 0), d, prong_paint(sign, i == 2))
            if cur is None:
                cur = root = Part(name, (sign * 7, -24, 0), (0, 0, bend), cubes=[cube])
            else:
                nxt = Part(f'{name}_{i}', (0, -prev, 0), (0, 0, bend), cubes=[cube])
                cur.add(nxt)
                cur = nxt
            prev = l
        return root

    yoke = blackbronze(0.46, 72, rim=0.26)
    gold = gilt(0.56, 73)
    return Part('bident', (0, GRIP_Y, 0), cubes=[
        Cube(-1.5, -19, -1.5, 3, 50, 3, shaft),                          # Schaft 3 dick
        Cube(-2, -5, -2, 4, 10, 4, wrap),                                # Griffwicklung
        Cube(-2.5, -21, -2.5, 5, 3, 5, gold),                            # Bund unter dem Joch
        Cube(-8, -24, -2, 16, 3, 4, yoke),                               # Querjoch 16 breit
        Cube(-9.5, -25, -2.5, 4, 4, 5, yoke),                            # Zinkenschuhe
        Cube(5.5, -25, -2.5, 4, 4, 5, yoke),
        Cube(-2, 31, -2, 4, 2, 4, gold),                                 # Zwinge
        Cube(-1.5, 33, -1.5, 3, 3, 3, blackbronze(0.5, 74, rim=0.3)),     # Dorn 3 -> 2
        Cube(-1, 36, -1, 2, 2, 2, blackbronze(0.58, 75, rim=0.3)),
    ], children=[prong('prong_a', -1), prong('prong_b', 1)])


def build_cape():
    strips = []

    def paint_for(strip, link):
        last = link == CAPE_LINKS - 1
        outer_edge = {0: 'right', CAPE_STRIPS - 1: 'left'}.get(strip)

        def paint(face, x, y, w, h):
            row = y / max(h - 1, 1)
            if last and face == 'bottom':
                return None
            if last and face in SIDES:
                # Zerfetzter Saum: Zähne 2 Texel breit, unterschiedlich lang; nur die äußersten Texel glimmen.
                col = (x // 2) if face in ('front', 'back') else 0
                cut = 0.7 + 0.26 * speckle(col * 9 + strip * 17, 3, 80)
                if row > cut:
                    return None
                if row > cut - 0.06 and face == 'back':
                    return faint_soul(80)
                if row > cut - 0.2:
                    return ramp(SHROUD, 0.2)
            if face == 'front':  # innen: Smaragd
                return ramp(LINING, 0.48 + 0.07 * math.sin((x + strip * 7) * 1.1) - link * 0.05 + _light('front', y, h, 0.1))
            if face == 'back':  # außen: Trauerstoff mit Bronzesaum an den Außenkanten
                if outer_edge == 'right' and x >= w - 2 or outer_edge == 'left' and x <= 1:
                    return ramp(BRONZE, 0.5 + _light('back', y, h, 0.1))
                return drape(SHROUD, 0.5 - link * 0.03, 81 + strip, folds=0.07, period=7.0)(face, x + strip * 7, y, w, h)
            if face == outer_edge or face in ('left', 'right'):
                return ramp(BRONZE, 0.42)
            return ramp(SHROUD, 0.4)
        return paint

    for s in range(CAPE_STRIPS):
        x = (s - (CAPE_STRIPS - 1) / 2.0) * 5.5
        cur = None
        root = None
        for k in range(CAPE_LINKS):
            cube = Cube(-3.5, 0, 0, 7, CAPE_LEN + 1, 2, paint_for(s, k))
            if cur is None:
                cur = root = Part(f'cape_{s}_0', (x, -18.5, 5.8), cubes=[cube])
            else:
                nxt = Part(f'cape_{s}_{k}', (0, CAPE_LEN, 0), cubes=[cube])
                cur.add(nxt)
                cur = nxt
        strips.append(root)
    return strips


def build_wings():
    """Schwingen aus Leichentuch: je Seite fünf Bahnen, die am Schulterblatt fächern."""
    wings = []

    def paint_for(i, link, sign):
        last = link == 2

        def paint(face, x, y, w, h):
            row = y / max(h - 1, 1)
            if last and face == 'bottom':
                return None
            if last and face in SIDES:
                col = (x // 2) if face in ('front', 'back') else 0
                cut = 0.62 + 0.32 * speckle(col * 11 + i * 23 + (sign > 0) * 5, 7, 90)
                if row > cut:
                    return None
                if row > cut - 0.07:
                    return faint_soul(100)
            if face == 'front':
                return ramp(LINING, 0.46 + 0.06 * math.sin(x * 1.4 + i) + _light('front', y, h, 0.12) - link * 0.04)
            if face == 'back':
                return ramp(SHROUD, 0.5 + 0.06 * math.sin(x * 1.4 + i * 2) + _light('back', y, h, 0.14) - link * 0.03)
            if face in ('left', 'right'):
                return ramp(BRONZE, 0.38 + _light(face, y, h, 0.1))
            return ramp(SHROUD, 0.42)
        return paint

    for side, sign in (('r', -1), ('l', 1)):
        for i in range(WING_COUNT):
            seg = WING_LENGTHS[i] / 3.0
            cur = None
            root = None
            for k in range(3):
                length = round(seg)
                width = WING_WIDTHS[k]
                cube = Cube(-width / 2.0, -1 if k else 0, -1, width, length + (1 if k else 0), 2, paint_for(i, k, sign))
                if cur is None:
                    cur = root = Part(f'wing_{side}_{i}', (sign * 4.5, -15 + i * 1.2, 6.6), cubes=[cube])
                else:
                    nxt = Part(f'wing_{side}_{i}_{k}', (0, length, 0), cubes=[cube])
                    cur.add(nxt)
                    cur = nxt
            wings.append(root)
    return wings


def build_chest():
    pec = blackbronze(0.46, 20, rim=0.24, grad=0.22)
    flank = blackbronze(0.38, 21, rim=0.2)
    gold = gilt(0.56, 22)

    def pec_relief(face, x, y, w, h):
        """Brustmuskel: oben Licht, unten weicher Schatten, Rand innen dunkel."""
        if face != 'front':
            return pec(face, x, y, w, h)
        v = 0.5 + (0.5 - y / max(h - 1, 1)) * 0.34 + _grain(x, y, 23)
        v += 0.05 * math.exp(-((x / (w - 1) - 0.4) ** 2) / 0.05)
        if y == h - 1:
            v -= 0.16
        return ramp(OBSIDIAN, v)

    def top_band(face, x, y, w, h):
        c = ridge(OBSIDIAN, 0.42, 24)(face, x, y, w, h)
        if face == 'front' and y == h - 1:
            return ramp(BRONZE, 0.62)
        return c

    def window_wall(face, x, y, w, h):
        # Innenwände des Fensters: grünlicher Widerschein (gemalt, nicht leuchtend).
        v = 0.34 + _light(face, y, h, 0.1)
        return mix(ramp(OBSIDIAN, v), ramp(LINING, 0.85), 0.55)

    def lantern_paint(face, x, y, w, h):
        d = math.hypot(x - (w - 1) / 2.0, (y - (h - 1) / 2.0) * 0.85)
        return ember(1.0 - d * 0.11)

    bars = [Cube(c - 0.5, -11, -7.0, 1, 11, 1, gilt(0.4, 25 + i, rim=0.3)) for i, c in enumerate((-5.6, -2.8, 0, 2.8, 5.6))]
    wall_l = Cube(-8, -11, -5.5, 1, 11, 5, window_wall)
    wall_r = Cube(7, -11, -5.5, 1, 11, 5, window_wall)

    lantern = Part('lantern', (0, -5.5, -4.5), cubes=[Cube(-4, -4.5, -2, 8, 9, 4, lantern_paint)])

    return Part('chest', (0, -12, 0), cubes=[
        Cube(-12, -19, -6, 5, 10, 12, flank),                         # Seiten des Panzers, oben breit
        Cube(7, -19, -6, 5, 10, 12, flank),
        Cube(-11, -9, -6, 3, 9, 12, flank),                           # unten zur Taille hin schmaler
        Cube(8, -9, -6, 3, 9, 12, flank),
        Cube(-7, -19, -6, 14, 8, 12, top_band),                       # über dem Fenster, Mittelgrat
        Cube(-8, -11, 0.5, 16, 11, 5.5, lambda f, x, y, w, h: window_wall(f, x, y, w, h) if f == 'front' else flank(f, x, y, w, h)),
        Cube(-11.5, -19, -7, 10, 7, 1, pec_relief),                   # Brustmuskeln
        Cube(1.5, -19, -7, 10, 7, 1, pec_relief),
        Cube(-13, -18, -5, 1, 9, 10, flank),                          # Fasen an den Flanken
        Cube(12, -18, -5, 1, 9, 10, flank),
        Cube(-10, -21, -5, 20, 2, 10, blackbronze(0.46, 27, rim=0.26)),  # Schulterlinie
        Cube(-5, -23, -4.5, 10, 3, 9, gold),                          # Halsberge
        Cube(-8, -12, -7.2, 16, 1, 1.5, gold),                         # Fensterrahmen oben
        Cube(-8, 0, -7.2, 16, 1, 1.5, gold),                           # Fensterrahmen unten
        wall_l, wall_r,
    ] + bars, children=[build_head(), lantern])


def build_belt_dog():
    hide = blackbronze(0.46, 45, rim=0.26)

    def face(face_, x, y, w, h):
        if face_ == 'front' and y == 1 and x in (1, w - 2):
            return ramp(BRONZE, 0.74)  # matte Goldaugen
        if face_ == 'top' and x in (0, 1, w - 2, w - 1):
            return ramp(OBSIDIAN, 0.62)  # angelegte Ohren
        return hide(face_, x, y, w, h)

    def muzzle(face_, x, y, w, h):
        if face_ in SIDES and y == h - 1:
            return ramp(OBSIDIAN, 0.95)
        return hide(face_, x, y, w, h)

    return [
        Cube(-3.5, -4, -8.5, 7, 6, 3, face),
        Cube(-2, -2, -10.5, 4, 3, 2, muzzle),
        Cube(-1, -2.5, -11.2, 2, 1, 1, hide),
    ]


def build_skirt():
    panels = []

    def paint_for(i, lower):
        def paint(face, x, y, w, h):
            if face == 'front':  # außen
                if lower and y >= h - 2:
                    return ramp(BRONZE, 0.56 if y == h - 2 else 0.4)
                if not lower and y < 2:
                    return ramp(BRONZE, 0.5)
                return drape(SHROUD, 0.5, 100 + i, folds=0.07, period=4.5)(face, x, y, w, h)
            if face == 'back':
                return ramp(LINING, 0.36 + 0.05 * math.sin(x * 1.3))
            if face in ('left', 'right'):
                return ramp(SHROUD, 0.3)
            return ramp(SHROUD, 0.38)
        return paint

    for i in range(1, SKIRT_COUNT):
        theta = i * math.tau / SKIRT_COUNT
        px = -math.sin(theta) * 8.0
        pz = -math.cos(theta) * 6.0
        panels.append(Part(
            f'skirt_{i}', (px, 0.5, pz), (-10, math.degrees(theta), 0),
            cubes=[Cube(-4.5, 0, -1, 9, 12, 2, paint_for(i, False))],
            children=[Part(f'skirt_{i}_low', (0, 11, 0), (4, 0, 0), cubes=[
                Cube(-5, 0, -1, 10, 11, 2, paint_for(i, True)),
            ])],
        ))
    return panels


def build_leg(side, sign):
    greave = ridge(OBSIDIAN, 0.44, 110 + sign)
    boot = blackbronze(0.38, 112 + sign, rim=0.24)
    return Part(f'thigh_{side}', (sign * 4.5, 1, 0), cubes=[
        Cube(-3.5, 0, -3.5, 7, 17, 7, drape(SHROUD, 0.42, 113 + sign, folds=0.05, period=4.0)),
    ], children=[
        Part(f'shin_{side}', (0, 17, 0), cubes=[
            Cube(-3.5, 0, -3.8, 7, 15, 7, greave),
            Cube(-4, -2.5, -4.6, 8, 5, 4, blackbronze(0.46, 114 + sign, rim=0.26)),  # Kniekappe
            Cube(-4, -0.5, -4.8, 8, 1, 1, gilt(0.6, 115)),
            Cube(-3, 2, 2.6, 6, 9, 2, blackbronze(0.36, 116 + sign)),                  # Wade
        ], children=[
            Part(f'foot_{side}', (0, 15, 0), cubes=[
                Cube(-4, 0, -9, 8, 3, 13, boot),                                          # Stiefel 8 x 3 x 13
                Cube(-4, -2, -4.5, 8, 2, 8, gilt(0.5, 117)),                              # Stulpe
                Cube(-3, -1, -9, 6, 1, 5, blackbronze(0.46, 118, rim=0.3)),               # Kappe
            ]),
        ]),
    ])


def hades():
    """Das Modell; seine Grundhaltung ist die Ruhepose (Kotlin setzt alle Winkel ohnehin absolut)."""
    model = _raw_model()
    _define_keys()
    ch = dict(KEYS['idle'])
    ch['wings'] = -1.0  # Grundhaltung: Schwingen angelegt
    rest = dict(bone_values(ch))
    secondary(ch, 2, rest)
    rest.pop('bident')  # der Bident bleibt in sich gerade (Grunddrehung 0), damit er allein sauber fliegt
    for part in model.parts[0].walk():
        v = rest.get(part.name)
        if v is not None:
            part.rot = tuple(math.degrees(a) for a in v[:3])
    return model


def _raw_model():
    belt = gilt(0.5, 120)
    root = Part('root', (0, -12, 0), cubes=[
        Cube(-8, -3, -5.5, 16, 6, 11, blackbronze(0.34, 121)),
        Cube(-8.5, -3.5, -6, 17, 4, 12, lambda f, x, y, w, h: belt(f, x, y, w, h) if y in (0, h - 1) else blackbronze(0.32, 122)(f, x, y, w, h)),
    ] + build_belt_dog(), children=[
        Part('torso', (0, -3, 0), cubes=[
            Cube(-7.5, -12, -5.5, 15, 12, 11, blackbronze(0.4, 123)),
            Cube(-5.5, -11, -6.5, 11, 10, 1, abs_relief),
            Cube(-8.5, -11, -4.5, 1, 10, 9, blackbronze(0.34, 124)),
            Cube(7.5, -11, -4.5, 1, 10, 9, blackbronze(0.34, 124)),
        ], children=[build_chest_full()]),
        build_leg('r', -1),
        build_leg('l', 1),
    ] + build_skirt())
    return Model('hades', 512, 512, [root])


def abs_relief(face, x, y, w, h):
    """Bauch des Muskelpanzers: weiche Wölbungen, Mittelgrat, keine Pixelmuster."""
    if face != 'front':
        return blackbronze(0.4, 125)(face, x, y, w, h)
    mid = (w - 1) / 2.0
    if abs(x - mid) < 0.6:
        return ramp(BRONZE, 0.6 + (0.5 - y / max(h - 1, 1)) * 0.2)
    band = (y % 3.5) / 3.5
    v = 0.44 + 0.08 * math.cos(band * math.tau) + (0.5 - y / max(h - 1, 1)) * 0.12
    v -= 0.06 * (abs(x - mid) / mid) ** 2
    return ramp(OBSIDIAN, v + _grain(x, y, 126))


def build_chest_full():
    chest = build_chest()
    chest.children += [build_arm('r', -1, [build_bident()]), build_arm('l', 1)]
    chest.children += build_cape()
    chest.children += build_wings()
    return chest


# ---------------------------------------------------------------------------------------------------------------
# Vorwärtskinematik, Löser und Schlüsselposen
# ---------------------------------------------------------------------------------------------------------------


def _mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _rot(pitch, yaw, roll):
    cx, sx = math.cos(pitch), math.sin(pitch)
    cy, sy = math.cos(yaw), math.sin(yaw)
    cz, sz = math.cos(roll), math.sin(roll)
    rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    return _mat_mul(rz, _mat_mul(ry, rx))


def _apply(m, v):
    return tuple(sum(m[i][j] * v[j] for j in range(3)) for i in range(3))


_MODEL = None
_PARENT = {}
_PARTS = {}


def _index():
    global _MODEL
    if _MODEL is None:
        _MODEL = _raw_model()

        def visit(part, parent):
            _PARTS[part.name] = part
            _PARENT[part.name] = parent
            for c in part.children:
                visit(c, part.name)
        for p in _MODEL.parts:
            visit(p, None)
    return _MODEL


def bone_values(ch):
    """Kanalwerte -> {knochen: (pitch, yaw, roll[, dx, dy, dz])} für die Hauptknochen (Grunddrehung 0)."""
    out = {}
    for bone, (rot, off) in BONE_CHANNELS.items():
        r = tuple(ch[c] if c else 0.0 for c in rot)
        if off:
            r = r + tuple(ch[c] if c else 0.0 for c in off)
        out[bone] = r
    return out


def frame(ch, bone):
    """Weltdrehung und -position eines Knochens (Modellraum) für einen Kanalsatz."""
    _index()
    chain = []
    name = bone
    while name is not None:
        chain.append(_PARTS[name])
        name = _PARENT[name]
    values = bone_values(ch)
    R = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    P = (0.0, 0.0, 0.0)
    for part in reversed(chain):
        v = values.get(part.name)
        pivot = part.pivot
        if v is None:
            rot = tuple(math.radians(a) for a in part.rot)
        else:
            rot = v[:3]
            if len(v) >= 6:
                pivot = (pivot[0] + v[3], pivot[1] + v[4], pivot[2] + v[5])
        o = _apply(R, pivot)
        P = (P[0] + o[0], P[1] + o[1], P[2] + o[2])
        R = _mat_mul(R, _rot(*rot))
    return R, P


def point(ch, bone, local):
    R, P = frame(ch, bone)
    o = _apply(R, local)
    return (P[0] + o[0], P[1] + o[1], P[2] + o[2])


def _minimize(f, x0, steps=(0.25, 0.12, 0.06, 0.03, 0.012, 0.005, 0.002), iters=60):
    x = list(x0)
    best = f(x)
    for st in steps:
        for _ in range(iters):
            improved = False
            for i in range(len(x)):
                for d in (st, -st):
                    x[i] += d
                    v = f(x)
                    if v < best - 1e-10:
                        best = v
                        improved = True
                    else:
                        x[i] -= d
            if not improved:
                break
    return x, best


def _dist2(a, b):
    return sum((a[i] - b[i]) ** 2 for i in range(3))


def reach(ch, side, target, reg=0.02, use_fore_ry=False):
    """Setzt Schulter- und Ellbogenkanäle so, dass die Faust (Griffpunkt) am Ziel liegt."""
    names = [f'arm_{side}_rx', f'arm_{side}_ry', f'arm_{side}_rz', f'fore_{side}_rx']
    if use_fore_ry:
        names.append(f'fore_{side}_ry')
    start = [ch[n] for n in names]

    def loss(x):
        trial = dict(ch)
        trial.update(zip(names, x))
        err = _dist2(point(trial, f'fore_{side}', (0, GRIP_Y, 0)), target)
        # Ellbogen beugt nur nach vorn (Unterarm-Pitch <= 0).
        bend = max(0.0, trial[f'fore_{side}_rx']) * 40
        return err + bend + reg * sum((a - b) ** 2 for a, b in zip(x, start))
    x, _ = _minimize(loss, start)
    ch.update(zip(names, x))
    return ch


def aim_bident(ch, direction, side_hint=None):
    """Dreht den Bident (relativ zum Unterarm), bis seine Spitze (lokal -y) in Weltrichtung `direction` zeigt.
    side_hint: Weltrichtung, in die die Zinkenebene (lokal +x) zeigen soll."""
    n = math.sqrt(sum(c * c for c in direction))
    d = tuple(c / n for c in direction)
    names = ['bid_rx', 'bid_ry', 'bid_rz']
    start = [ch[c] for c in names]

    def loss(x):
        trial = dict(ch)
        trial.update(zip(names, x))
        R, _ = frame(trial, 'bident')
        up = _apply(R, (0, -1, 0))
        err = _dist2(up, d)
        if side_hint is not None:
            sx = _apply(R, (1, 0, 0))
            err += 0.25 * _dist2(sx, side_hint)
        return err + 0.0005 * sum(v * v for v in x)
    x, _ = _minimize(loss, start, iters=80)
    ch.update(zip(names, x))
    return ch


def shaft_point(ch, s):
    """Weltpunkt auf dem Schaft, s Einheiten unter dem Griff (negativ = Richtung Zinken)."""
    return point(ch, 'bident', (0, s, 0))


KEYS = {}
KEY_ORDER = []


def key(name, base='idle', grip_r_at=None, bident_dir=None, bident_side=None, hand_l_on=None, hand_l_at=None,
        fore_ry_r=False, fore_ry_l=False, **values):
    """Legt eine Schlüsselpose an: Grundkanäle von `base`, dann Werte, dann IK (rechte Faust, Bident, linke Faust)."""
    ch = dict(KEYS[base]) if base else {c: 0.0 for c in CHANNELS}
    for k, v in values.items():
        if k not in CH:
            raise KeyError(f'unbekannter Kanal {k} in {name}')
        ch[k] = float(v)
    if grip_r_at is not None:
        reach(ch, 'r', grip_r_at, use_fore_ry=fore_ry_r)
    if bident_dir is not None:
        aim_bident(ch, bident_dir, bident_side)
    if hand_l_on is not None:
        reach(ch, 'l', shaft_point(ch, hand_l_on), use_fore_ry=fore_ry_l)
    elif hand_l_at is not None:
        reach(ch, 'l', hand_l_at, use_fore_ry=fore_ry_l)
    ch = {k: round(v, 4) for k, v in ch.items()}
    KEYS[name] = ch
    if name not in KEY_ORDER:
        KEY_ORDER.append(name)
    return ch


def _define_keys():
    if KEYS:
        return
    _index()
    # --- Ruhe: aufrecht, Bident mit dem Dorn am Boden in der Rechten, die Linke hinter dem Rücken. --------------
    key('idle', base=None, torso_rx=0.02, chest_rx=-0.03, head_rx=0.04,
        arm_r_rz=0.12, arm_r_rx=-0.1, fore_r_rx=-0.7, grip_r=1.0,
        arm_l_rx=0.35, arm_l_rz=0.25, fore_l_rx=-1.5, fore_l_ry=0.6, grip_l=0.7,
        thigh_r_rx=-0.04, thigh_r_rz=0.04, thigh_l_rx=0.05, thigh_l_rz=-0.04, shin_r_rx=0.04,
        cape=0.22, wings=0.0,
        grip_r_at=(-17, -12, -7), bident_dir=(0, -1, -0.04), bident_side=(1, 0, 0),
        hand_l_at=(3, -16, 6.5))

    # --- Hieb A: rechts hinten ausholen, rechts -> links -------------------------------------------------------
    key('sweep_a_windup', root_y=3, root_ry=0.35, torso_ry=0.35, chest_ry=0.55, chest_rx=0.05, chest_rz=-0.06,
        head_ry=-0.7, head_rx=0.05,
        thigh_l_rx=-0.65, shin_l_rx=0.45, foot_l_rx=0.2, thigh_r_rx=0.35, shin_r_rx=0.35, foot_r_rx=-0.3,
        thigh_r_rz=0.12, thigh_l_rz=-0.1, cape=0.3, grip_l=1.0,
        arm_r_rx=0.2, arm_r_rz=0.5, fore_r_rx=-1.2, arm_l_rx=-0.9, arm_l_rz=0.6,
        grip_r_at=(-15, -26, 8), bident_dir=(-0.35, 0.05, 1), bident_side=(0, -1, 0), hand_l_on=-15)
    key('sweep_a_hit', base='sweep_a_windup', root_y=4, root_ry=-0.3, torso_ry=-0.35, chest_ry=-0.65, chest_rz=0.05,
        head_ry=0.35, cape=0.75,
        thigh_l_rx=-0.7, shin_l_rx=0.4, thigh_r_rx=0.4, shin_r_rx=0.3,
        arm_r_rx=-1.3, arm_r_rz=0.2, fore_r_rx=-0.3, arm_l_rx=-1.2, arm_l_rz=-0.3,
        grip_r_at=(4, -26, -16), bident_dir=(1, 0.05, -0.55), bident_side=(0, -1, 0), hand_l_on=-15)
    key('sweep_a_follow', base='sweep_a_hit', root_ry=-0.55, torso_ry=-0.5, chest_ry=-0.85, head_ry=0.55, cape=0.6,
        grip_r_at=(12, -24, -9), bident_dir=(0.85, 0.15, 0.6), bident_side=(0, -1, 0), hand_l_on=-15)

    # --- Hieb B: Rückhand links -> rechts, Bident links hoch, Ellbogen führt ------------------------------------
    key('sweep_b_windup', base='sweep_a_follow', root_y=3, root_ry=-0.45, torso_ry=-0.4, chest_ry=-0.75, chest_rx=-0.05,
        head_ry=0.6, cape=0.45,
        thigh_r_rx=-0.55, shin_r_rx=0.4, foot_r_rx=0.15, thigh_l_rx=0.3, shin_l_rx=0.35, foot_l_rx=-0.25,
        arm_r_rx=-2.0, arm_r_rz=-0.3, fore_r_rx=-1.4, arm_l_rx=-0.5, arm_l_rz=-0.4,
        grip_r_at=(8, -50, -2), bident_dir=(0.55, -0.75, 0.35), bident_side=(0, 0, 1), hand_l_on=-16)
    key('sweep_b_hit', base='sweep_b_windup', root_y=4, root_ry=0.35, torso_ry=0.35, chest_ry=0.6, head_ry=-0.4, cape=0.8,
        arm_r_rx=-1.3, arm_r_rz=0.9, fore_r_rx=-0.3,
        grip_r_at=(-12, -28, -16), bident_dir=(-1, 0.1, -0.5), bident_side=(0, 1, 0), hand_l_on=-16)
    key('sweep_b_follow', base='sweep_b_hit', root_ry=0.5, torso_ry=0.5, chest_ry=0.8, head_ry=-0.55, cape=0.6,
        grip_r_at=(-18, -24, -4), bident_dir=(-0.8, 0.25, 0.7), bident_side=(0, 1, 0), hand_l_on=-16)

    # --- Schlusshieb von oben: beide Hände am Schaft, Bident senkrecht über dem Helm -----------------------------
    key('sweep_c_windup', root_y=-2, torso_rx=-0.18, chest_rx=-0.22, head_rx=-0.3, root_ry=0.1,
        thigh_r_rx=0.15, thigh_l_rx=-0.35, shin_l_rx=0.15, foot_r_rx=-0.35, foot_l_rx=0.2, cape=0.35, grip_l=1.0,
        arm_r_rx=-2.8, arm_r_rz=0.2, fore_r_rx=-0.3, arm_l_rx=-2.6, arm_l_rz=-0.3, fore_l_rx=-0.5,
        grip_r_at=(-2, -78, 3), bident_dir=(0, -1, 0.5), bident_side=(1, 0, 0), hand_l_on=12)
    key('sweep_c_hit', base='sweep_c_windup', root_y=8, torso_rx=0.4, chest_rx=0.45, head_rx=0.05, root_ry=0.0,
        thigh_l_rx=-0.95, shin_l_rx=0.65, foot_l_rx=0.3, thigh_r_rx=0.45, shin_r_rx=0.55, foot_r_rx=-0.5, cape=0.9,
        arm_r_rx=-1.0, fore_r_rx=-0.2, arm_l_rx=-1.0,
        grip_r_at=(-2, -26, -24), bident_dir=(0, 0.75, -1), bident_side=(1, 0, 0), hand_l_on=12)
    key('sweep_c_recover', base='sweep_c_hit', root_y=11, torso_rx=0.62, chest_rx=0.45, head_rx=0.35, cape=0.05, wings=-0.3,
        thigh_l_rx=-0.85, shin_l_rx=0.95, thigh_r_rx=0.35, shin_r_rx=0.85, foot_r_rx=-0.4, foot_l_rx=-0.1,
        grip_r_at=(-2, -20, -17), bident_dir=(0, 1, -0.3), bident_side=(1, 0, 0), hand_l_at=None, hand_l_on=-7)

    # --- Ausfallschritt: Bident wie eine Lanze unter dem rechten Arm --------------------------------------------
    key('thrust_windup', root_y=8, root_z=4, root_ry=0.3, torso_rx=0.1, torso_ry=0.15, chest_rx=0.1, chest_ry=0.3,
        head_ry=-0.35, head_rx=-0.05, cape=0.3, grip_l=1.0,
        thigh_l_rx=-0.8, shin_l_rx=0.7, foot_l_rx=0.1, thigh_r_rx=0.5, shin_r_rx=0.55, foot_r_rx=-0.55,
        arm_r_rx=0.5, arm_r_rz=0.3, fore_r_rx=-1.2, arm_l_rx=-0.9, arm_l_rz=-0.2,
        grip_r_at=(-12, -24, 10), bident_dir=(0.12, 0.05, -1), bident_side=(0, -1, 0), hand_l_on=-18)
    key('thrust', base='thrust_windup', root_y=10, root_z=-3, root_ry=0.15, torso_rx=0.3, chest_rx=0.2, chest_ry=0.15,
        head_ry=-0.15, head_rx=-0.15, cape=1.05,
        thigh_l_rx=-1.1, shin_l_rx=0.9, foot_l_rx=0.2, thigh_r_rx=0.75, shin_r_rx=0.3, foot_r_rx=-0.55,
        grip_r_at=(-9, -24, -12), bident_dir=(0.1, 0.06, -1), bident_side=(0, -1, 0), hand_l_on=-22)
    key('thrust_recover', base='thrust', root_y=9, root_z=-1, torso_rx=0.45, chest_rx=0.3, head_rx=0.2, cape=0.2,
        grip_r_at=(-12, -18, -10), bident_dir=(0.05, 0.6, -1), bident_side=(0, -1, 0), hand_l_at=(8, -16, -6))

    # --- Bodenschlag: springt leicht ab, Bident kopfüber mit beiden Händen über dem Kopf ------------------------
    key('slam_windup', root_y=-3, torso_rx=-0.2, chest_rx=-0.25, head_rx=-0.35, cape=0.5, grip_l=1.0,
        foot_r_rx=-0.45, foot_l_rx=-0.45, thigh_l_rx=-0.15, thigh_r_rx=0.1,
        arm_r_rx=-2.9, arm_r_rz=0.25, fore_r_rx=-0.4, arm_l_rx=-2.9, arm_l_rz=-0.25, fore_l_rx=-0.4,
        grip_r_at=(-3, -80, -2), bident_dir=(0, 1, -0.15), bident_side=(1, 0, 0), hand_l_on=-9)
    key('slam_hit', base='slam_windup', root_y=10, torso_rx=0.5, chest_rx=0.45, head_rx=0.0, cape=1.0,
        foot_r_rx=-0.3, foot_l_rx=0.2, thigh_l_rx=-1.0, shin_l_rx=0.8, thigh_r_rx=0.45, shin_r_rx=0.7,
        arm_r_rx=-1.0, fore_r_rx=-0.3, arm_l_rx=-1.0,
        grip_r_at=(-2, -20, -24), bident_dir=(0, 1, -0.12), bident_side=(1, 0, 0), hand_l_on=-9)

    # --- Feststecken: Zinken im Boden, er zerrt, ein Fuß gegen den Schaft -------------------------------------
    key('stuck', root_y=8, root_z=4, torso_rx=0.25, chest_rx=0.05, head_rx=-0.1, cape=0.2, grip_l=1.0,
        thigh_l_rx=-1.25, shin_l_rx=0.9, foot_l_rx=0.4, thigh_r_rx=0.25, shin_r_rx=0.6, foot_r_rx=-0.35,
        thigh_l_rz=0.1, arm_r_rx=-1.0, arm_l_rx=-1.0,
        grip_r_at=(-2, -18, -20), bident_dir=(0, 1, -0.45), bident_side=(1, 0, 0), hand_l_on=-6)
    key('stuck_tug', base='stuck', root_y=7, root_z=7, torso_rx=0.05, chest_rx=-0.2, head_rx=-0.3,
        grip_r_at=(-2, -22, -16), bident_dir=(0, 1, -0.45), bident_side=(1, 0, 0), hand_l_on=-6)

    # --- Bident-Wurf ----------------------------------------------------------------------------------------
    key('throw_windup', root_y=2, root_ry=0.45, torso_ry=0.3, chest_ry=0.45, chest_rx=-0.15, head_ry=-0.75, cape=0.4,
        thigh_l_rx=-0.55, shin_l_rx=0.3, thigh_r_rx=0.35, shin_r_rx=0.35, foot_r_rx=-0.3,
        arm_r_rx=-2.3, arm_r_rz=0.7, fore_r_rx=-0.9,
        arm_l_rx=-1.5, arm_l_rz=-0.35, fore_l_rx=-0.1, grip_l=0.0, hand_l_at=None,
        grip_r_at=(-14, -56, 12), bident_dir=(0.18, -0.35, -1), bident_side=(0, -1, 0))
    key('throw_release', base='throw_windup', root_y=5, root_ry=-0.35, torso_ry=-0.3, chest_ry=-0.4, chest_rx=0.35,
        torso_rx=0.25, head_ry=0.3, head_rx=-0.1, cape=0.85,
        thigh_l_rx=-0.85, shin_l_rx=0.5, thigh_r_rx=0.55, shin_r_rx=0.4,
        arm_l_rx=0.4, arm_l_rz=-0.4, fore_l_rx=-0.6, grip_l=0.8,
        grip_r_at=(-4, -40, -26), bident_dir=(0.1, -0.1, -1), bident_side=(0, -1, 0))
    key('throw_follow', base='throw_release', root_y=7, torso_rx=0.45, chest_rx=0.35, head_rx=0.0, cape=0.5, grip_r=0.25,
        arm_r_rx=-0.6, arm_r_rz=-0.2, fore_r_rx=-0.4, arm_l_rx=0.6, fore_l_rx=-0.4)

    # --- Leere Hand: ruft den Bident zurück ------------------------------------------------------------------
    key('empty', root_y=1, torso_rx=0.04, chest_rx=0.0, chest_ry=0.25, head_ry=-0.1, cape=0.25, grip_r=0.15,
        arm_r_rx=-1.45, arm_r_rz=0.25, fore_r_rx=-0.1, fore_r_ry=0.0, hand_r_rx=0.25,
        thigh_r_rx=-0.25, shin_r_rx=0.15, thigh_l_rx=0.2)
    key('empty_curl', base='empty', grip_r=0.75, hand_r_rx=-0.1, arm_r_rx=-1.35)

    # --- Blick: hebt den Kopf, schiebt das Visier hoch, der Mantel bläht sich -----------------------------------
    key('gaze_reach', root_y=0, chest_rx=-0.05, head_rx=-0.1, cape=0.4, grip_l=0.5, hand_l_at=(4, -64, -12))
    key('gaze_charge', base='gaze_reach', root_y=-1, torso_rx=-0.08, chest_rx=-0.16, head_rx=-0.32, cape=0.9,
        visor_rx=-1.25, visor_dy=-1.0, grip_l=0.6, hand_l_at=(3, -73, -10))
    key('gaze_fire', base='gaze_charge', torso_rx=0.1, chest_rx=0.12, head_rx=0.18, cape=1.2,
        arm_l_rx=-0.6, arm_l_rz=-0.9, fore_l_rx=-0.5, fore_l_ry=0.0, grip_l=0.2, hand_l_at=None)

    # --- Seelenketten: Linke reißt nach vorn, dann beide Fäuste an die Brust -----------------------------------
    key('chains_cast', root_y=3, root_ry=-0.2, torso_ry=-0.15, chest_ry=-0.35, chest_rx=0.15, head_rx=-0.05, head_ry=0.3,
        cape=0.7, thigh_l_rx=-0.6, shin_l_rx=0.4, thigh_r_rx=0.35, shin_r_rx=0.3, foot_r_rx=-0.3,
        arm_l_rx=-1.6, arm_l_rz=-0.1, fore_l_rx=-0.05, fore_l_ry=0.0, grip_l=0.0, hand_l_at=None)
    key('chains_pull', base='chains_cast', root_y=5, root_z=4, root_ry=0.0, torso_ry=0.0, chest_ry=0.0, torso_rx=-0.1,
        chest_rx=-0.2, head_rx=0.15, head_ry=0.0, cape=0.35, grip_l=1.0,
        thigh_l_rx=-0.75, shin_l_rx=0.75, foot_l_rx=0.15, thigh_r_rx=0.55, shin_r_rx=0.3,
        arm_l_rx=-0.9, arm_l_rz=0.35, fore_l_rx=-1.9,
        grip_r_at=(-9, -34, -11), bident_dir=(-0.55, -1, 0.45), bident_side=(0, 0, 1), hand_l_at=(4, -33, -12))

    # --- Verschwinden: Visier runter, Mantel vor den Körper -------------------------------------------------
    key('vanish', root_y=2, chest_rx=0.12, head_rx=0.3, visor_dy=4.0, cape=0.6, cape_wrap=1.0, grip_l=1.0,
        arm_l_rx=-1.1, arm_l_rz=0.95, fore_l_rx=-1.1, fore_l_ry=0.0, hand_l_at=(-8, -34, -11))

    # --- Hinterhalt: taucht mit waagerechtem Rundhieb auf ----------------------------------------------------
    key('ambush_hit', base='sweep_a_hit', root_y=6, chest_rx=0.15, cape=1.1, visor_dy=0.0)
    key('ambush_recover', base='sweep_a_follow', root_y=7, root_x=2, root_rz=-0.06, torso_rx=0.3, chest_rx=0.25,
        root_ry=-0.75, torso_ry=-0.4, head_ry=0.45, cape=0.3,
        thigh_l_rx=-0.45, shin_l_rx=0.6, thigh_r_rx=0.6, shin_r_rx=0.5, foot_r_rx=-0.4,
        grip_r_at=(12, -16, -10), bident_dir=(0.8, 0.6, 0.2), bident_side=(0, -1, 0), hand_l_at=(10, -24, 2))

    # --- Urteil: Bident waagerecht hoch über dem Kopf wie eine Waage, auf den Zehen ------------------------------
    key('judge_raise', root_y=-3, torso_rx=-0.1, chest_rx=-0.14, head_rx=-0.38, cape=0.7, grip_l=1.0,
        foot_r_rx=-0.55, foot_l_rx=-0.55, shin_r_rx=0.05, shin_l_rx=0.05,
        arm_r_rx=-2.7, arm_r_rz=0.7, fore_r_rx=-0.5, arm_l_rx=-2.7, arm_l_rz=-0.7, fore_l_rx=-0.5,
        grip_r_at=(-11, -86, -4), bident_dir=(1, 0, 0), bident_side=(0, 0, 1), hand_l_on=-21)
    key('judge_slam', root_y=10, torso_rx=0.42, chest_rx=0.4, head_rx=0.1, cape=1.0, grip_l=1.0,
        thigh_l_rx=-0.95, shin_l_rx=0.85, foot_l_rx=0.15, thigh_r_rx=0.4, shin_r_rx=0.85, foot_r_rx=-0.35,
        arm_r_rx=-1.1, arm_l_rx=-1.1,
        grip_r_at=(-1, -42, -20), bident_dir=(0, -1, 0.02), bident_side=(1, 0, 0), hand_l_on=-7)
    key('judge_spent', base='judge_slam', root_y=13, torso_rx=0.62, chest_rx=0.5, head_rx=0.45, cape=0.0, wings=-0.4,
        thigh_l_rx=-1.05, shin_l_rx=1.25, thigh_r_rx=0.25, shin_r_rx=1.2, foot_r_rx=-0.65, foot_l_rx=-0.2,
        grip_r_at=(-1, -32, -18), bident_dir=(0, -1, 0.12), bident_side=(1, 0, 0), hand_l_on=-7)

    # --- Taumeln: auf ein Knie, Helm gesenkt, am Bident gehalten (oder an nichts) -------------------------------
    key('stagger', root_y=15, root_z=2, torso_rx=0.42, chest_rx=0.3, head_rx=0.45, cape=0.0, wings=-0.7, grip_l=0.4,
        thigh_r_rx=0.15, shin_r_rx=1.5, foot_r_rx=-0.15, thigh_l_rx=-1.35, shin_l_rx=1.3, foot_l_rx=0.1,
        arm_l_rx=-0.5, arm_l_rz=-0.3, fore_l_rx=-0.6, fore_l_ry=0.0, hand_l_at=(7, -8, -12),
        grip_r_at=(-12, -20, -14), bident_dir=(0.08, -1, -0.15), bident_side=(1, 0, 0))
    key('stagger_empty', base='stagger', grip_r=0.4, arm_r_rx=-0.45, arm_r_rz=0.3, fore_r_rx=-0.4,
        torso_rx=0.5, chest_rx=0.38, grip_r_at=(-10, -4, -12))

    # --- Brüllen: Arme und Schwingen auf, Kopf zurück ---------------------------------------------------------
    key('roar', root_y=1, torso_rx=-0.12, chest_rx=-0.28, head_rx=-0.5, cape=0.9, wings=1.0, grip_l=0.3,
        arm_r_rx=-0.4, arm_r_rz=1.25, fore_r_rx=-0.55, arm_l_rx=-0.35, arm_l_rz=-1.35, fore_l_rx=-0.5, fore_l_ry=0.0,
        hand_l_at=None, thigh_r_rz=0.1, thigh_l_rz=-0.1, bident_dir=(-0.3, -1, -0.1), bident_side=(1, 0, 0))

    # --- Cutscenes ----------------------------------------------------------------------------------------
    key('cast', chest_rx=-0.08, head_rx=-0.12, cape=0.45, wings=0.5, grip_l=0.1,
        arm_l_rx=-1.55, arm_l_rz=-0.45, fore_l_rx=-0.3, fore_l_ry=0.0, hand_l_at=None)
    key('windup', root_y=3, root_ry=0.3, torso_ry=0.2, chest_ry=0.35, chest_rx=0.08, head_ry=-0.45, cape=0.35,
        thigh_l_rx=-0.5, shin_l_rx=0.35, thigh_r_rx=0.3, shin_r_rx=0.3, foot_r_rx=-0.25, grip_l=1.0,
        grip_r_at=(-14, -30, -6), bident_dir=(0.5, -0.6, -0.6), bident_side=(0, 0, 1), hand_l_on=-14)
    key('sit', root_y=17, root_z=2, torso_rx=-0.06, chest_rx=0.02, head_rx=0.08, cape=0.0, wings=-0.5, grip_l=0.4,
        thigh_r_rx=-1.5, thigh_l_rx=-1.5, thigh_r_rz=0.08, thigh_l_rz=-0.08, shin_r_rx=1.45, shin_l_rx=1.5,
        arm_l_rx=-0.75, arm_l_rz=-0.15, fore_l_rx=-0.55, fore_l_ry=0.0, hand_l_at=(9, -4, -16),
        grip_r_at=(-15, -14, -12), bident_dir=(0, -1, -0.05), bident_side=(1, 0, 0))
    key('kneel', root_y=15, root_z=2, torso_rx=0.25, chest_rx=0.22, head_rx=0.42, cape=0.0, wings=-0.7, grip_l=0.4,
        thigh_r_rx=0.15, shin_r_rx=1.5, foot_r_rx=-0.15, thigh_l_rx=-1.35, shin_l_rx=1.3, foot_l_rx=0.1,
        arm_l_rx=-0.5, arm_l_rz=-0.15, fore_l_rx=-0.65, fore_l_ry=0.0, hand_l_at=(7, -6, -13),
        grip_r_at=(-13, -18, -12), bident_dir=(0.06, -1, -0.1), bident_side=(1, 0, 0))
    key('fallen', root_rx=-1.5, root_y=33, root_z=0, torso_rx=0, chest_rx=0, head_rx=-0.2, cape=-0.95, wings=-0.9, grip_l=0.2, grip_r=0.5,
        arm_r_rx=-0.1, arm_r_rz=1.1, fore_r_rx=-0.15, arm_l_rx=-0.1, arm_l_rz=-1.15, fore_l_rx=-0.15, fore_l_ry=0.0,
        thigh_r_rx=-0.08, thigh_l_rx=0.1, thigh_r_rz=0.08, thigh_l_rz=-0.06, bid_rx=1.4, bid_ry=0, bid_rz=0.4)


# ---------------------------------------------------------------------------------------------------------------
# Sekundärbewegung (statischer Teil). HadesModel.kt rechnet dieselben Formeln und legt Zeit-Terme darüber.
# ---------------------------------------------------------------------------------------------------------------

WING_FOLD = (0.10, 0.16, 0.22, 0.28, 0.34)
WING_SPREAD = (2.25, 2.02, 1.8, 1.58, 1.36)


def secondary(ch, form=0, out=None):
    out = {} if out is None else out
    g = ch['root_rx'] + ch['torso_rx'] + ch['chest_rx']
    # Mantel: hängt der Schwerkraft nach, hebt sich mit `cape`, `cape_wrap` fächert ihn um den Körper.
    for s in range(CAPE_STRIPS):
        across = s - (CAPE_STRIPS - 1) / 2.0
        wrap = ch['cape_wrap']
        gc = min(1.0, max(-0.6, g))  # liegt er, fällt der Mantel nicht unter den Boden
        out[f'cape_{s}_0'] = (0.06 + ch['cape'] * 0.6 - gc * 0.85 - abs(across) * wrap * 0.12,
                              across * wrap * 0.55, -across * (0.085 - wrap * 0.05))
        for k in range(1, CAPE_LINKS):
            # Die unteren Glieder fallen gegen den Hub zurück: Der Stoff hängt durch statt wie ein Brett zu stehen.
            out[f'cape_{s}_{k}'] = (0.03 - ch['cape'] * 0.13, 0.0, 0.0)
    # Schwingen: Grundspreizung je Form, `wings` > 0 spreizt weiter (Brüllen), < 0 lässt sie hängen (Taumeln).
    sp = wing_spread(form, ch['wings'])
    for side, sign in (('r', -1), ('l', 1)):
        for i in range(WING_COUNT):
            name = f'wing_{side}_{i}'
            if not wing_visible(form, i):
                out[name] = None
                continue
            roll = WING_FOLD[i] + (WING_SPREAD[i] - WING_FOLD[i]) * sp
            out[name] = (0.22 + 0.12 * sp - g * 0.3, -sign * 0.2 * sp, -sign * roll)
            out[f'{name}_1'] = (0.06, 0.0, sign * 0.2 * sp)
            out[f'{name}_2'] = (0.08, 0.0, sign * 0.26 * sp)
    # Rock: vorn hebt er sich mit dem Oberschenkel, hinten schwingt er aus.
    for i in range(1, SKIRT_COUNT):
        theta = i * math.tau / SKIRT_COUNT
        front, side = math.cos(theta), -math.sin(theta)
        thigh = ch['thigh_r_rx'] if side < -0.1 else ch['thigh_l_rx'] if side > 0.1 else (ch['thigh_r_rx'] + ch['thigh_l_rx']) / 2
        follow = front * min(0.0, thigh) if front > 0 else front * max(0.0, thigh)
        follow *= 0.75 + 0.25 * abs(front)
        out[f'skirt_{i}'] = (math.radians(-10) + follow, theta, 0.0)
        out[f'skirt_{i}_low'] = (math.radians(4) + max(0.0, -follow) * 0.35, 0.0, 0.0)
    # Bart pendelt zur Schwerkraft.
    out['beard_a'] = (-0.38 - (g + ch['head_rx']) * 0.7, 0.0, 0.0)
    # Finger: grip 0 = offen, 1 = Faust.
    for side, sign in (('r', -1), ('l', 1)):
        gr = ch[f'grip_{side}']
        out[f'fingers_{side}'] = (0.0, 0.0, sign * gr * 1.45)
        out[f'fingers_{side}_tip'] = (0.0, 0.0, sign * gr * 1.4)
        out[f'thumb_{side}'] = (-0.3 - gr * 0.5, 0.0, sign * (0.35 + gr * 0.5))
    return out


EMPTY_HAND = ('throw_follow', 'empty', 'empty_curl', 'stagger_empty')


def pose_for(name, form=0, hide_wings=True):
    ch = KEYS[name]
    out = dict(bone_values(ch))
    secondary(ch, form, out)
    if name in EMPTY_HAND:
        out['bident'] = None
    if hide_wings and form == 0:
        for side in ('r', 'l'):
            for i in range(WING_COUNT):
                out[f'wing_{side}_{i}'] = None
    return out


def _build_poses():
    _define_keys()
    poses = {name: pose_for(name) for name in KEY_ORDER}
    # Formen: Ruhe ohne, mit drei halb gespreizten und mit fünf weit offenen Schwingen.
    for form in (0, 1, 2):
        ch = dict(KEYS['idle'])
        out = dict(bone_values(ch))
        secondary(ch, form, out)
        if form == 0:
            for side in ('r', 'l'):
                for i in range(WING_COUNT):
                    out[f'wing_{side}_{i}'] = None
        poses[f'form{form}'] = out
    ch = dict(KEYS['roar'])
    out = dict(bone_values(ch))
    secondary(ch, 2, out)
    poses['roar_form2'] = out
    # Nur der Bident, groß (alles andere ausgeblendet, Knochen in Grundlage).
    only = {n: None for n in ('thigh_r', 'thigh_l', 'torso') + tuple(f'skirt_{i}' for i in range(1, SKIRT_COUNT))}
    only['root'] = (0, 0, 0)
    poses['bident_only'] = only
    upper = pose_for('idle')
    for n in ('thigh_r', 'thigh_l', 'bident') + tuple(f'skirt_{i}' for i in range(1, SKIRT_COUNT)) + tuple(f'cape_{i}_0' for i in range(CAPE_STRIPS)):
        upper[n] = None
    poses['zoom_upper'] = upper
    head = dict(upper)
    for n in ('arm_r', 'arm_l', 'lantern') + tuple(f'wing_{s}_{i}' for s in 'rl' for i in range(WING_COUNT)):
        head[n] = None
    head['root'] = (0, 0, 0)
    poses['zoom_head'] = head
    return poses


class _LazyPoses(dict):
    def _fill(self):
        if not dict.__len__(self):
            dict.update(self, _build_poses())

    def __getitem__(self, k):
        self._fill()
        return dict.__getitem__(self, k)

    def get(self, k, d=None):
        self._fill()
        return dict.get(self, k, d)

    def __iter__(self):
        self._fill()
        return dict.__iter__(self)

    def __contains__(self, k):
        self._fill()
        return dict.__contains__(self, k)

    def __len__(self):
        self._fill()
        return dict.__len__(self)

    def keys(self):
        self._fill()
        return dict.keys(self)

    def items(self):
        self._fill()
        return dict.items(self)


POSES = _LazyPoses()


# ---------------------------------------------------------------------------------------------------------------
# Kotlin: generierter Block HadesKeys in HadesModel.kt
# ---------------------------------------------------------------------------------------------------------------

KOTLIN_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'kotlin', 'dev', 'henny',
                           'hha', 'client', 'model', 'HadesModel.kt')
BEGIN = '// region HadesKeys (GENERIERT von tools/hha_assets/hades_model.py, nicht von Hand ändern)'
END = '// endregion HadesKeys'


def kotlin_keys():
    _define_keys()
    lines = [BEGIN, '/** Kanäle und Schlüsselposen von Hades; dieselben Zahlen stehen in `POSES` der Python-Datei. */',
             'internal object HadesKeys {']
    for i, c in enumerate(CHANNELS):
        lines.append(f'    const val {c.upper()} = {i}')
    lines.append(f'    const val COUNT = {len(CHANNELS)}')
    lines.append(f'    const val CAPE_STRIPS = {CAPE_STRIPS}')
    lines.append(f'    const val CAPE_LINKS = {CAPE_LINKS}')
    lines.append(f'    const val WING_COUNT = {WING_COUNT}')
    lines.append(f'    const val SKIRT_COUNT = {SKIRT_COUNT}')
    lines.append(f'    const val GRIP_Y = {GRIP_Y}f')
    lines.append(f'    const val CAPE_LEN_F = {CAPE_LEN}f')
    lines.append('    @JvmField val WING_FORM_BASE = floatArrayOf(' + ', '.join(f'{v}f' for v in WING_FORM_BASE) + ')')
    lines.append('    @JvmField val WING_FOLD = floatArrayOf(' + ', '.join(f'{v}f' for v in WING_FOLD) + ')')
    lines.append('    @JvmField val WING_SPREAD = floatArrayOf(' + ', '.join(f'{v}f' for v in WING_SPREAD) + ')')
    for name in KEY_ORDER:
        vals = ', '.join(f'{KEYS[name][c]:.4f}f' for c in CHANNELS)
        lines.append(f'    @JvmField val {name.upper()} = floatArrayOf({vals})')
    lines.append('}')
    lines.append(END)
    return '\n'.join(lines)


def write_kotlin_keys(path=KOTLIN_FILE):
    path = os.path.abspath(path)
    with open(path, encoding='utf8') as f:
        src = f.read()
    block = kotlin_keys()
    pattern = re.compile(re.escape(BEGIN) + r'.*?' + re.escape(END), re.S)
    if pattern.search(src):
        src = pattern.sub(lambda m: block, src)
    else:
        src = src.rstrip('\n') + '\n\n' + block + '\n'
    with open(path, 'w', encoding='utf8', newline='\n') as f:
        f.write(src)
    print('Hades-Schlüsselposen:', path)


def variants(model, albedo, glow):
    """Hook von boss_models.py: schreibt nach dem Erzeugen die Schlüsselposen in HadesModel.kt."""
    write_kotlin_keys()


if __name__ == '__main__':
    write_kotlin_keys()

# Für die Größenprüfung in preview.py: Diese Knochen (samt Kindern) zählen nicht zur Körperhöhe (Fuß bis Scheitel),
# weil sie Zubehör sind, das laut Brief darüber hinausragen darf (Hut, Waffe, Busch, Ast, Stachel, Ring).
HEIGHT_EXCLUDE = ('crest', 'bident') + tuple(f'wing_{s}_{i}' for s in 'rl' for i in range(5))
