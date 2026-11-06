"""Hermes in doppelter Pixeldichte: heldenhafte Proportionen, Prunkrüstung, leuchtende Einlagen und echte Flügel.

Eine Modell-Einheit ist hier halb so groß wie sonst (der Renderer skaliert entsprechend), damit Gesicht,
Muskelpanzer und Stab sichtbar mehr Details tragen. Die Füße stehen bei y = 24.

Federn sind keine Pixel-Ebenen mehr, sondern glatte, gebogene Streifen aus dem Federn-Atlas (siehe feather_art.py
und modelkit.Plume). Die Lichteffekte um das Modell, auch die Schlangen am Stab, zeichnet client/vfx/BossGlow.kt.
Die Ankerpunkte dafür stehen in client/model/HermesModel.kt und müssen zu den Koordinaten hier passen (Orb, Augen, Panzer-Sonne).
"""
import math

from feather_art import CELL_COVERT, CELL_LONG, CELL_PLUME
from modelkit import (
    CAPE, CLOTH, GOLD, HAIR, LEATHER, SILVER, SKIN,
    Cube, Model, Part, Plume, hexc, mat, mix, ramp, rgb, speckle, trimmed,
)

EYE_GOLD = hexc('#ffd75a')
IVORY = rgb('#9a8f7c', '#c9bfa8', '#e9e2d0', '#faf6ea', '#ffffff')
INLAY = (255, 232, 150)


def inlay(strength=200):
    """Leuchtende Goldeinlage."""
    return (ramp(GOLD, 0.96), (INLAY[0], INLAY[1], INLAY[2], strength))


def fan(name, pivot, rot, side, count, length, spread, plane='x', width=12):
    """Kleiner Flügel aus Einzelfedern (Helm, Sandalen, Caduceus)."""
    plumes = []
    for i in range(count):
        angle = spread / 2.0 - spread * i / max(count - 1, 1)
        reach = length * (1.0 - 0.1 * abs(i - count * 0.3))
        if plane == 'x':
            plumes.append(Plume((0, 0, 0.1 * i), (0, 0, side * -angle), width, reach, CELL_PLUME,
                                'x+' if side > 0 else 'x-', flip=side < 0, bend=0.05, sweep=-0.08, shade=(0.8, 1.0)))
        else:
            plumes.append(Plume((0.1 * i * side, 0, 0), (angle, 0, 0), width, reach, CELL_PLUME, 'z', flip=side < 0,
                                bend=0.05 * side, sweep=-0.08, shade=(0.8, 1.0)))
    return Part(name, pivot, rot=rot, plumes=plumes)


def feather_row(side, bone_length, lengths, rolls, width, z, tilt, cell, bend=0.07, sweep=0.0, shade=(0.72, 1.0), down=0.6):
    """Federreihe entlang eines Flügelknochens, mit etwas Fächerung und Wechsel in der Neigung."""
    out = []
    count = len(lengths)
    for i, (length, roll) in enumerate(zip(lengths, rolls)):
        along = (i + 0.5) / count * bone_length
        out.append(Plume(
            (side * along, down, z + (i % 2) * 0.25),
            (tilt + (i % 2) * 2, 0, -side * roll),
            width, length, cell, 'y', flip=side < 0, bend=bend, sweep=side * sweep, shade=shade,
        ))
    return out


def wing_layers(side, bone, long_lengths, rolls, greater, lesser):
    """Schwungfedern hinten, darüber je eine Lage Decken nach vorn und hinten sowie kleine Decken am Knochen."""
    greater_rolls = [r * 0.8 for r in rolls[:: max(1, len(rolls) // max(len(greater), 1))]][:len(greater)]
    greater_rolls += [greater_rolls[-1] if greater_rolls else 0] * (len(greater) - len(greater_rolls))
    lesser_rolls = [r * 0.6 for r in greater_rolls[:len(lesser)]]
    lesser_rolls += [lesser_rolls[-1] if lesser_rolls else 0] * (len(lesser) - len(lesser_rolls))
    return (
        feather_row(side, bone, long_lengths, rolls, 14, 0.5, 4, CELL_LONG, bend=0.09, sweep=0.03)
        + feather_row(side, bone, greater, greater_rolls, 11, -0.8, 0, CELL_COVERT, bend=-0.05, shade=(0.78, 1.0))
        + feather_row(side, bone, greater, greater_rolls, 11, 1.7, 8, CELL_COVERT, bend=0.1, shade=(0.7, 0.96))
        + feather_row(side, bone, lesser, lesser_rolls, 9, -1.4, -4, CELL_COVERT, bend=-0.06, shade=(0.86, 1.0), down=-0.5)
    )


def back_wing(s, side):
    """Echter Flügel: Oberarm, Unterarm und Hand als Gelenke, darunter glatte Federn in mehreren Lagen."""
    bone = mat(IVORY, 0.72, 0.06, 0.15, 70)
    gold = mat(GOLD, 0.66, 0.05, 0.1, 71)

    def segment(length, thick):
        return Cube(0 if side > 0 else -length, -thick / 2.0, -thick / 2.0, length, thick, thick, bone)

    hand = Part(f'wing_hand_{s}', (side * 18, 0, 0), cubes=[segment(16, 3)], plumes=wing_layers(
        side, 16,
        [36, 42, 47, 51, 53, 51, 47, 41], [2, 7, 13, 20, 28, 37, 47, 58],
        [20, 21, 21, 20, 18], [11, 11, 10, 10],
    ))
    fore = Part(f'wing_fore_{s}', (side * 16, 0, 0), cubes=[segment(18, 3)], children=[hand], plumes=wing_layers(
        side, 18,
        [36, 37, 38, 39, 40, 41, 42], [0, 0, 1, 1, 1, 2, 2],
        [18, 18, 19, 19, 19, 18], [10, 10, 11, 11],
    ))
    return Part(f'wing_back_{s}', (side * 4, -17, 5.4), cubes=[
        segment(16, 4),
        Cube(-3, -3, -2.5, 6, 6, 5, gold),
    ], children=[fore], plumes=wing_layers(
        side, 16,
        [25, 28, 31, 33, 35], [0, 0, 0, 0, 0],
        [14, 15, 16, 16], [9, 10, 10],
    ))


def hermes():
    skin = mat(SKIN, 0.58, 0.035, 0.12, 1)
    cloth = mat(CLOTH, 0.7, 0.04, 0.25, 2)
    gold = mat(GOLD, 0.62, 0.06, 0.28, 3)
    silver = mat(SILVER, 0.58, 0.05, 0.25, 4)
    hair = mat(HAIR, 0.64, 0.12, 0.2, 5)
    leather = mat(LEATHER, 0.55, 0.1, 0.1, 6)
    dark_gold = mat(GOLD, 0.42, 0.06, 0.12, 7)

    def gem(face, x, y, w, h):
        return (hexc('#fff6c8'), (255, 240, 170, 255))

    def face_paint(face, x, y, w, h):
        if face != 'front':
            if face in ('top', 'back') or (face in ('left', 'right') and y < 6):
                return hair(face, x, y, w, h)
            return skin(face, x, y, w, h)
        # Gesicht auf 10x11: Stirnlocken, Brauen, Augen, Nase, schiefes Grinsen.
        cheek = ramp(SKIN, 0.62 + (speckle(x, y, 9) - 0.5) * 0.05 - (0.14 if x in (0, 9) else 0.0))
        if y < 2 or (y == 2 and x in (0, 1, 8, 9)):
            return hair(face, x, y, w, h)
        if y == 3:
            if x in (1, 2, 3):
                return ramp(HAIR, 0.28)
            if x in (6, 7, 8):
                return ramp(SKIN, 0.5)
        if y == 2 and x in (6, 7, 8):
            return ramp(HAIR, 0.28)  # die rechte Braue ist frech hochgezogen
        if y in (4, 5):
            if x in (1, 8):
                return hexc('#fbfaf5') if y == 4 else ramp(SKIN, 0.42)
            if x in (2, 3, 6, 7):
                if y == 5:
                    return ramp(SKIN, 0.42) if x in (2, 7) else (ramp(GOLD, 0.5), (255, 200, 90, 220))
                return (EYE_GOLD, (255, 225, 120, 255)) if x in (3, 6) else hexc('#fbfaf5')
            if x in (4, 5) and y == 5:
                return ramp(SKIN, 0.68)
        if y in (6, 7):
            if x in (4, 5):
                return ramp(SKIN, 0.7) if y == 6 else ramp(SKIN, 0.45)
        if y == 8:
            if x in (3, 4, 5):
                return hexc('#9a4e3c')
            if x == 6:
                return hexc('#7c3a2c')
            if x == 7:
                return ramp(SKIN, 0.48)
        if y == 9 and x in (3, 4, 5):
            return ramp(SKIN, 0.48)
        return cheek

    def cuirass(face, x, y, w, h):
        """Muskelpanzer: Brustlinien, Bauchkanten und eine strahlende Sonne, deren Strahlen über das Gold laufen."""
        base = gold(face, x, y, w, h)
        if face == 'front':
            cx, cy = (w - 1) / 2.0, 3.5
            d = math.hypot(x - cx, y - cy)
            if d < 1.7:
                return (hexc('#fff6c8'), (255, 240, 170, 255))
            if d < 2.9:
                return (ramp(GOLD, 0.95), (255, 226, 130, 200))
            angle = math.atan2(y - cy, x - cx)
            if d < 7.2 and abs(math.sin(angle * 4.0)) < 0.2 / max(d * 0.3, 0.6):
                return inlay(int(190 - d * 16))
            if abs(x - cx) < 0.6:
                return ramp(GOLD, 0.3)
            if y == 6 and abs(x - cx) > 1:
                return ramp(GOLD, 0.34)
            if abs(x - cx) < 6.0 and y in (4, 5) and abs(abs(x - cx) - 4.0) < 1.2:
                return ramp(GOLD, 0.82)
            if y >= h - 2:
                return inlay(120) if (x // 2) % 2 == 0 else ramp(GOLD, 0.42)
        elif face == 'back':
            if abs(x - (w - 1) / 2.0) < 0.6:
                return ramp(GOLD, 0.3)
            if y >= h - 2 and (x // 2) % 2 == 0:
                return inlay(110)
        elif face in ('left', 'right') and y == h // 2:
            return inlay(120)
        return base

    def abdomen(face, x, y, w, h):
        c = cloth(face, x, y, w, h)
        if face in ('front', 'back'):
            if x % 3 == 1:
                return mix(c, hexc('#8d88a0'), 0.3)
        return c

    def meander(face, x, y, w, h):
        if face not in ('front', 'back', 'left', 'right'):
            return gold(face, x, y, w, h)
        # Griechisches Band, die hellen Felder glimmen.
        if ((x // 2) + (y // 2)) % 2 == 0:
            return (ramp(GOLD, 0.84), (255, 220, 130, 90))
        return ramp(GOLD, 0.34)

    def pteryx(seed):
        base = mat(LEATHER, 0.6, 0.1, 0.25, seed)

        def paint(face, x, y, w, h):
            if face in ('front', 'back', 'left', 'right') and y >= h - 2:
                return (ramp(GOLD, 0.76), (255, 220, 130, 110))
            if face in ('front', 'back') and x in (0, w - 1):
                return ramp(GOLD, 0.48)
            return base(face, x, y, w, h)
        return paint

    def cape_paint(face, x, y, w, h):
        if face not in ('front', 'back'):
            return None
        fold = 0.14 * math.sin(x * 0.8)
        if x in (0, 1, w - 2, w - 1):
            return (ramp(GOLD, 0.7), (255, 220, 130, 80))
        # Gestickte Sterne, die im Dunkeln leuchten.
        if face == 'back' and (x * 17 + y * 31) % 67 == 0:
            return (hexc('#fff6c8'), (255, 240, 190, 235))
        return ramp(CAPE, 0.5 + fold + 0.15 * (0.5 - y / max(h - 1, 1)))

    def cape_tail(face, x, y, w, h):
        if face not in ('front', 'back'):
            return None
        if y >= h - 2:
            return (ramp(GOLD, 0.74), (255, 226, 140, 170)) if x % 4 != 3 else None
        return cape_paint(face, x, y, w, h)

    def petasos(face, x, y, w, h):
        if face in ('front', 'left', 'right', 'back') and y >= h - 2:
            return inlay(170)
        return silver(face, x, y, w, h)

    def brim(face, x, y, w, h):
        if face in ('top', 'bottom'):
            d = max(abs(x - (w - 1) / 2.0), abs(y - (h - 1) / 2.0))
            if d >= (w - 1) / 2.0 - 1.2:
                return ramp(GOLD, 0.7)
        return silver(face, x, y, w, h)

    def orb(face, x, y, w, h):
        """Kristallkugel des Stabs: Facetten, innen gleißend, am Rand etwas kühler."""
        centre = max(abs(x - (w - 1) / 2.0), abs(y - (h - 1) / 2.0)) / max((w - 1) / 2.0, 0.5)
        if face in ('top', 'bottom') or centre > 0.8:
            return (ramp(GOLD, 0.92), (255, 236, 150, 255))
        return (hexc('#fffdf0'), (255, 248, 210, 255))

    def staff(face, x, y, w, h):
        return ramp(GOLD, 0.45 + 0.3 * ((y // 4) % 2))

    # --- Kopf ---------------------------------------------------------------------------------------------
    curls = [Cube(cx, -10, cz, 2, 3, 2, hair) for cx, cz in
             [(-5, -5), (-2, -6), (1, -6), (4, -5), (-6, -2), (5, -2), (-6, 1), (5, 1), (-4, 4), (0, 5), (3, 4)]]
    head = Part('head', (0, -22, 0), cubes=[
        Cube(-5, -11, -5, 10, 11, 10, face_paint),
        Cube(-2, -2, -2, 4, 3, 4, skin),
        # Plastik im Gesicht: Nase, Ohren und Haar, das unter dem Helm hervorquillt.
        Cube(-1, -6, -6, 2, 3, 1, mat(SKIN, 0.68, 0.02, 0.1, 11)),
        Cube(-6, -7, -1, 1, 3, 2, skin),
        Cube(5, -7, -1, 1, 3, 2, skin),
        Cube(-5, -9, 4, 10, 7, 2, hair),
        Cube(-6, -9, -3, 1, 4, 3, hair),
        Cube(5, -9, -3, 1, 4, 3, hair),
    ] + curls, children=[
        Part('petasos', (0, -9, 0), cubes=[
            Cube(-7, -1, -7, 14, 1, 14, brim),
            Cube(-5, -5, -5, 10, 4, 10, petasos, dilate=0.2),
            Cube(-3, -7, -3, 6, 2, 6, silver),
            Cube(-1, -5, -6, 2, 2, 1, gem),
        ]),
        fan('wing_head_r', (-5, -10, 1), (0, 0, 42), -1, 5, 20, 40, width=11),
        fan('wing_head_l', (5, -10, 1), (0, 0, -42), 1, 5, 20, 40, width=11),
    ])

    # --- Stab ---------------------------------------------------------------------------------------------
    ring = trimmed(mat(GOLD, 0.8, 0.03, 0.0, 12), mat(GOLD, 0.96, 0.02, 0.0, 13), rows=1, top=True)
    caduceus = Part('caduceus', (0, 12.5, -0.5), cubes=[
        Cube(-1, -32, -1, 2, 62, 2, staff),
        # Griff und Ringe.
        Cube(-1, -5, -1, 2, 10, 2, leather, dilate=0.3),
        Cube(-2, -7, -2, 4, 2, 4, ring),
        Cube(-2, 5, -2, 4, 2, 4, ring),
        Cube(-2, -24, -2, 4, 2, 4, ring),
        Cube(-2, 20, -2, 4, 2, 4, ring),
        # Fuß.
        Cube(-2, 30, -2, 4, 3, 4, dark_gold),
        Cube(-1, 33, -1, 2, 4, 2, lambda f, x, y, w, h: inlay(220)),
        # Krone und Kristall: der Orb sitzt bei y = -43.
        Cube(-2, -35, -2, 4, 3, 4, gold),
        Cube(-4, -38, -4, 8, 3, 8, ring),
        Cube(-4, -46, -4, 1, 8, 1, gold),
        Cube(3, -46, -4, 1, 8, 1, gold),
        Cube(-4, -46, 3, 1, 8, 1, gold),
        Cube(3, -46, 3, 1, 8, 1, gold),
        Cube(-3, -46, -3, 6, 6, 6, orb),
        Cube(-1, -51, -1, 2, 5, 2, gem),
    ], children=[
        # Klein und steil nach oben: Breite Flügel würden sonst vor das Gesicht ragen.
        fan('caduceus_wing_r', (-2, -36, 0), (0, 0, 58), -1, 5, 13, 36, width=9),
        fan('caduceus_wing_l', (2, -36, 0), (0, 0, -58), 1, 5, 13, 36, width=9),
    ])
    scroll = Part('scroll', (0, 12.5, -1.5), rot=(0, 0, 90), cubes=[
        Cube(-1, -7, -1, 2, 14, 2, mat(rgb('#b39d7c', '#d4c19f', '#f6eedb'), 0.75, 0.05, 0.0), dilate=0.2),
        Cube(-1, -2, -1, 2, 4, 2, mat(GOLD, 0.6, 0.05, 0.0), dilate=0.5),
    ])

    def black_letter(face, x, y, w, h):
        """Hades' Brief: fast schwarz, mit glimmenden Seelenrissen und einem kalten Siegel."""
        if face == 'front':
            cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
            d = math.hypot(x - cx, y - cy)
            if d < 1.3:
                return (hexc('#c4fbff'), (196, 251, 255, 255))
            if d < 2.2:
                return (hexc('#14a6c4'), (60, 214, 239, 230))
            if abs(abs(x - cx) * 0.75 - y) < 0.5:
                return (hexc('#0b7690'), (20, 166, 196, 170))
        if face == 'back' and abs(abs(x - (w - 1) / 2.0) * 0.75 - y) < 0.5:
            return hexc('#2c2338')
        return hexc('#130e19') if (x + y) % 5 else hexc('#1f1828')

    letter = Part('letter', (0, 13.5, -2.5), cubes=[Cube(-4.5, -3, -0.5, 9, 6, 1, black_letter)])

    # --- Arme ---------------------------------------------------------------------------------------------
    def arm(s, sx, *kids):
        pauldron_layers = [
            Cube(-3.5, -3, -3.5, 7, 6, 7, gold),
            Cube(-4, 1, -4, 8, 2, 8, meander),
            Cube(-1, -2, -4.5, 2, 2, 1, gem),
        ]

        def bracer(face, x, y, w, h):
            if face in ('front', 'back', 'left', 'right') and y in (0, h - 1):
                return ramp(GOLD, 0.84)
            if face in ('front', 'left', 'right') and x == w // 2:
                return inlay(190)
            return dark_gold(face, x, y, w, h)

        return Part(f'arm_{s}', (sx * 10, -18, 0), cubes=pauldron_layers + [
            Cube(-2.5, 0, -2.5, 5, 11, 5, skin),
        ], children=[
            Part(f'fore_{s}', (0, 11, 0), cubes=[
                Cube(-2, 0, -2, 4, 11, 4, skin),
                Cube(-2, 2, -2, 4, 7, 4, bracer, dilate=0.4),
                Cube(-2, 11, -2, 4, 3, 4, skin),
            ], children=list(kids)),
        ])

    cape = Part('cape1', (0, -19, 5.2), cubes=[
        Cube(-8, 0, 0, 16, 14, 0, cape_paint),
    ], children=[
        Part('cape2', (0, 14, 0), cubes=[Cube(-8, 0, 0, 16, 14, 0, cape_paint)], children=[
            Part('cape3', (0, 14, 0), cubes=[Cube(-8, 0, 0, 16, 16, 0, cape_tail)]),
        ]),
    ])

    # --- Rumpf --------------------------------------------------------------------------------------------
    torso = Part('torso', (0, -3, 0), cubes=[
        Cube(-6, -8, -4, 12, 8, 8, abdomen),
        Cube(-8, -20, -5, 16, 12, 10, cuirass),
        Cube(-7, -22, -4, 14, 2, 8, meander),
    ], children=[head, arm('r', -1, caduceus), arm('l', 1, scroll, letter), cape, back_wing('r', -1), back_wing('l', 1)])

    # --- Beine --------------------------------------------------------------------------------------------
    def leg(s, sx):
        greave = mat(GOLD, 0.6, 0.05, 0.2, 30)

        def strapped(face, x, y, w, h):
            if y < 11:
                if face == 'front' and x == w // 2 and y > 0:
                    return inlay(180)
                return greave(face, x, y, w, h)
            return leather(face, x, y, w, h) if y % 2 == 0 else skin(face, x, y, w, h)

        wing = fan(f'wing_foot_{s}', (sx * 2.8, 12, 2.5), (-26, 0, 0), sx, 4, 17, 36, plane='z', width=10)
        shin = Part(f'shin_{s}', (0, 17, 0), cubes=[
            Cube(-2.5, 0, -2.5, 5, 16, 5, strapped),
            Cube(-2.5, 15, -6, 5, 2, 8, leather),
            Cube(-2.5, 16, -6, 5, 1, 8, gold, dilate=0.1),
        ], children=[wing])
        return Part(f'leg_{s}', (sx * 3.4, 2, 0), cubes=[
            Cube(-3, 0, -3, 6, 17, 6, skin),
            Cube(-3, 0, -3, 6, 4, 6, cloth, dilate=0.4),
        ], children=[shin])

    skirt = []
    fronts = [(-7.5, -5.2), (-4.5, -5.2), (-1.5, -5.2), (1.5, -5.2), (4.5, -5.2), (-7.5, 4.2), (-4.5, 4.2), (-1.5, 4.2), (1.5, 4.2), (4.5, 4.2)]
    for i, (sx, sz) in enumerate(fronts):
        skirt.append(Cube(sx, 2, sz, 3, 9, 1, pteryx(40 + i)))

    # Kurier bleibt Kurier: Tasche an der Hüfte, aus der gesiegelte Briefe schauen.
    paper = mat(rgb('#b39d7c', '#d4c19f', '#f6eedb'), 0.72, 0.04, 0.0, 56)
    bag = mat(LEATHER, 0.62, 0.1, 0.2, 57)

    def buckle(face, x, y, w, h):
        return (hexc('#fff6c8'), (255, 240, 170, 240)) if abs(x - (w - 1) / 2.0) < 1.1 and abs(y - (h - 1) / 2.0) < 1.1 else ramp(GOLD, 0.8)

    satchel = [
        Cube(7, -1, -3, 4, 8, 6, bag),
        Cube(7, -2, -3, 4, 3, 6, mat(LEATHER, 0.78, 0.06, 0.1, 58), dilate=0.2),
        Cube(11, -1, -1, 1, 2, 2, gem),
        Cube(8, -4, -2, 2, 3, 2, paper),
        Cube(8, -5, 0, 2, 4, 2, paper),
    ]

    root = Part('root', (0, -12, 0), cubes=[
        Cube(-7, -3, -4.5, 14, 6, 9, meander),
        Cube(-3, -3.5, -5.5, 6, 6, 1, buckle),
    ] + skirt + satchel, children=[torso, leg('r', -1), leg('l', 1)])
    return Model('hermes', 512, 512, [root])
