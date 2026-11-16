"""Hermes' Requisiten: Herma-Stein, Götterpaket und Projektile (Lanze). Teil von boss_models.py (--only herma,parcel,projectiles)."""
import math

from modelkit import (
    BONE, CAPE, CLOTH, FEATHER, GOLD, HAIR, LEATHER, MARBLE, PARCEL, SCALE_A, SCALE_B, SILVER, SKIN, SOUL, STONE,
    Cube, Model, Part, Plume, glowing, hexc, mat, mix, ramp, rgb, speckle, trimmed, write_kotlin,
)
from feather_art import CELL_COVERT, CELL_LONG, CELL_TINT
from texkit import Image, flush, save

EYE_GOLD = hexc('#ffd75a')
FACE = {'top': 0.12, 'bottom': -0.22, 'front': 0.0, 'back': -0.08, 'left': -0.05, 'right': -0.05}


# -------------------------------------------------------------------------------------------------
# Herma: Grenz- und Wegstein mit dem Kopf des Hermes (Kampf: „Herma-Sprung", der glühende Stein ist das Ziel)
# -------------------------------------------------------------------------------------------------

SKY = (223, 244, 255)
MARBLE_W = rgb('#45434b', '#6f6c76', '#9a97a1', '#bdbac1', '#d6d3d6', '#e9e6e4')
GRIME = hexc('#4e5a4c')
GOLD_MATT = rgb('#4a2d0c', '#7a5218', '#a57a2c', '#c49c4a', '#dcc07a')

# Caduceus 6 x 18 (oben -> unten): Knauf, Flügel, zwei Schlangenschlaufen um den Stab, Fuß.
CADUCEUS = [
    '..##..',
    '#.##.#',
    '######',
    '.####.',
    '..##..',
    '.####.',
    '#.##.#',
    '#.##.#',
    '.####.',
    '..##..',
    '.####.',
    '#.##.#',
    '#.##.#',
    '.####.',
    '..##..',
    '..##..',
    '..##..',
    '.####.',
]
RELIEF_X, RELIEF_Y = -3, -31   # linke obere Ecke des Reliefs im Raum von `root` (Einheiten)


def caduceus_at(x, y):
    gx, gy = math.floor(x - RELIEF_X), math.floor(y - RELIEF_Y)
    return 0 <= gx < 6 and 0 <= gy < 18 and CADUCEUS[gy][gx] == '#'


def mcube(x, y, z, w, h, d, shader, dilate=0.0):
    """Quader, dessen Maler zusätzlich die Lage des Texels im Raum bekommt: shader(face, i, j, fw, fh, p)."""
    def paint(face, i, j, fw, fh):
        if face == 'front':
            p = (x + i + 0.5, y + j + 0.5, z)
        elif face == 'back':
            p = (x + w - i - 0.5, y + j + 0.5, z + d)
        elif face == 'right':
            p = (x, y + j + 0.5, z + d - i - 0.5)
        elif face == 'left':
            p = (x + w, y + j + 0.5, z + i + 0.5)
        elif face == 'top':
            p = (x + i + 0.5, y, z + j + 0.5)
        else:
            p = (x + i + 0.5, y + h, z + j + 0.5)
        return shader(face, i, j, fw, fh, p)
    return Cube(x, y, z, w, h, d, paint, dilate)


def marble(top=-40.0, bottom=0.0, base=0.5, offset=(0.0, 0.0)):
    """Marmor: glatter Verlauf (oben heller), helle gefaste Kanten, Verwitterung zum Sockel hin, feine Adern.
    top/bottom: Höhenbereich (y im Raum von `root`) für den Verlauf; offset rechnet Knochenräume (Büste) dorthin um."""
    def shade(face, i, j, fw, fh, p):
        x, y, z = p[0] + offset[0], p[1] + offset[1], p[2]
        t = (y - top) / max(bottom - top, 1.0)            # 0 oben, 1 unten
        v = base + 1.6 * FACE[face] + 0.16 * (0.5 - t)
        v += (speckle(int(x * 2 + 50), int(y * 2 + z * 3 + 50), 41) - 0.5) * 0.05
        # Feine Ader, schräg über den Stein.
        vein = abs(math.sin((x * 0.7 + y * 0.35 + z * 0.5) * 0.9))
        if vein < 0.06:
            v -= 0.07
        edge = face not in ('top', 'bottom') and (i == 0 or i == fw - 1)
        if edge or (face in ('front', 'back', 'left', 'right') and j == 0):
            v += 0.1                                         # Grat fängt das Licht
        c = ramp(MARBLE_W, v)
        # Verwitterung: Schmutz und Moos von unten, an Kanten stärker.
        grime = max(0.0, (y + 9) / 9.0) * (0.55 if edge else 0.38)
        grime *= 0.7 + 0.6 * speckle(int(x * 3 + 7), int(z * 3 + y + 9), 43)
        if grime > 0:
            c = mix(c, GRIME, min(0.55, grime))
        return c
    return shade


def shaft_front(base):
    """Schaft: vorn das eingetiefte Caduceus-Relief (dunkle Rille mit heller Unterkante)."""
    def shade(face, i, j, fw, fh, p):
        x, y, z = p
        if face == 'front' and z <= -4.99 and caduceus_at(x, y):
            v = 0.34 if not caduceus_at(x, y + 1) else 0.2
            return ramp(MARBLE_W, v)
        return base(face, i, j, fw, fh, p)
    return shade


def sigil_paint(face, i, j, fw, fh):
    """Leuchtendes Relief (eigener Knochen `sigil`): nur die Zeichenfläche, sonst durchsichtig."""
    if face not in ('front', 'back'):
        return None
    gx = i if face == 'front' else fw - 1 - i
    if CADUCEUS[j][gx] != '#':
        return None
    core = j in (0, 2) or gx in (2, 3)
    c = mix(SKY + (255,), hexc('#ffffff'), 0.45 if core else 0.15)
    return (c, SKY + (255,))


def head_paint(base):
    """Gesicht: Augenhöhlen mit schwach glimmenden Augen, Brauengrat, Mundspalt über dem Bart."""
    def shade(face, i, j, fw, fh, p):
        x, y, z = p
        if face == 'front':
            if -8.0 < y < -7.0 and 0.9 < abs(x) < 2.6:
                if 1.0 < abs(x) < 2.0:
                    return (mix(SKY + (255,), hexc('#9fb4c4'), 0.35), SKY + (110,))
                return ramp(MARBLE_W, 0.26)                  # Augenhöhle
            if -9.0 < y < -8.0 and 0.6 < abs(x) < 3.0:
                return ramp(MARBLE_W, 0.86)                  # Brauengrat
            if -6.0 < y < -5.0 and abs(x) < 1.6:
                return ramp(MARBLE_W, 0.28)                  # Mundspalt
        return base(face, i, j, fw, fh, p)
    return shade


def stone_wing(name, side):
    """Kleiner Steinflügel an der Hutkrone: drei gestufte Federn (2 dick), außen lang, nach oben-hinten geschwungen."""
    stone = marble(top=-58.0, bottom=-40.0, base=0.62, offset=(0.0, -35.0))
    s = side

    def feather(length, y, z):
        x0 = -length if s < 0 else 0
        return mcube(x0, y, z, length, 2, 2, stone)
    return Part(name, (s * 3.5, -14.0, 0.5), rot=(0, s * -32, s * -38), cubes=[
        feather(9, -2, -1.0),
        feather(7, 0, -0.5),
        feather(5, 2, 0.0),
    ])


def herma():
    """Herma: vierkantiger Schaft auf Sockel, oben die Büste des Hermes mit Petasos und Steinflügeln."""
    stone = marble()

    def plinth(face, i, j, fw, fh, p):
        if face in ('front', 'back', 'left', 'right') and j == 0:
            return ramp(GOLD_MATT, 0.5 + FACE[face])         # schmales, mattes Sockelband
        return stone(face, i, j, fw, fh, p)

    bust_stone = marble(top=-58.0, bottom=-35.0, base=0.54, offset=(0.0, -35.0))
    face = head_paint(bust_stone)
    bust = Part('bust', (0, -35, 0), cubes=[
        mcube(-2.5, -3, -2.5, 5, 3, 5, bust_stone),            # Hals
        mcube(-4, -11, -4, 8, 8, 8, face),                     # Kopf
        mcube(-4.5, -10, -1, 9, 6, 5, bust_stone),             # Haar im Nacken
        mcube(-1, -8, -5, 2, 3, 1, bust_stone),                # Nase
        mcube(-3.5, -5, -5, 7, 3, 3, bust_stone),              # Bart: Keil in drei Stufen
        mcube(-2.5, -2, -4.6, 5, 2, 3, bust_stone),
        mcube(-1.5, 0, -4.2, 3, 2, 2, bust_stone),
        mcube(-6, -12.5, -5, 12, 2, 10, bust_stone),           # Petasos: Krempe (Achteck, 12)
        mcube(-5, -12.5, -6, 10, 2, 12, bust_stone),
        mcube(-4, -15.5, -4, 8, 3, 8, bust_stone),             # Hutkrone
        mcube(-3, -16.5, -3, 6, 2, 6, bust_stone),
    ], children=[stone_wing('stone_wing_r', -1), stone_wing('stone_wing_l', 1)])

    top = shaft_front(stone)
    root = Part('root', (0, 24, 0), cubes=[
        mcube(-7, -3, -7, 14, 3, 14, plinth),                  # Sockel mit Goldband
        mcube(-6, -5, -6, 12, 2, 12, stone),                   # Sockelstufe
        mcube(-4, -13, -3, 8, 8, 6, stone),                    # Schaft unten (8, gefast)
        mcube(-3, -13, -4, 6, 8, 8, stone),
        mcube(-5, -33, -4, 10, 20, 8, top),                    # Schaft oben (10, gefast)
        mcube(-4, -33, -5, 8, 20, 10, top),
        mcube(-5.5, -35, -5.5, 11, 2, 11, stone),              # Deckplatte
        mcube(-8, -31, -1.5, 3, 4, 3, stone),                  # Schulterzapfen
        mcube(5, -31, -1.5, 3, 4, 3, stone),
    ], children=[
        bust,
        # Drehpunkt in der Mitte des Reliefs, damit das Pulsieren (Maßstab) es nicht verschiebt.
        Part('sigil', (0, RELIEF_Y + 9, -5), cubes=[Cube(RELIEF_X, -9, -0.4, 6, 18, 1, sigil_paint)]),
    ])
    return Model('herma', 128, 128, [root])


# Vorschau: Stein aus (Relief dunkel) und angesagt (Relief leuchtet, Büste zum Ziel gedreht). Dieselben Werte wie
# HermaModel in PropsModels.kt (BUST_TURN_MAX begrenzt die Drehung).
POSES = {
    'unlit': {'sigil': None},
    'lit': {'bust': (0.0, 0.6, 0.0)},
}


def parcel_wing(side):
    """Kleiner Flügel aus glatten Federn, der seitlich vom Deckel absteht."""
    return [
        Plume((0, -3 + i * 1.6, 0.1 * i), (0, 0, side * (18 - i * 11)), 9, 17 - i * 1.5, CELL_COVERT if i % 2 else CELL_LONG,
              'x+' if side > 0 else 'x-', flip=side < 0, bend=0.05, sweep=0.1 * side, shade=(0.8, 1.0))
        for i in range(6)
    ]


def parcel():
    """Das Götterpaket: goldverschnürte Truhe mit kleinen Flügeln."""
    wood = mat(PARCEL, 0.6, 0.16, 0.2, 50)

    def strap(face, x, y, w, h):
        if abs(x - (w - 1) / 2) < 1.1:
            return (ramp(GOLD, 0.8), (255, 226, 130, 200))
        c = wood(face, x, y, w, h)
        if y % 4 == 3:
            return mix(c, hexc('#000000'), 0.28)
        return c

    def seal(face, x, y, w, h):
        return (hexc('#fff6c8'), (255, 240, 170, 255))

    base = Part('base', (0, 24, 0), cubes=[
        Cube(-8, -10, -8, 16, 10, 16, strap),
        Cube(-9, -2, -9, 18, 2, 18, mat(GOLD, 0.58, 0.07, 0.0)),
    ], children=[
        Part('lid', (0, -10, 8), cubes=[
            Cube(-9, -6, -17, 18, 6, 18, strap),
            Cube(-2, -8, -10, 4, 2, 4, seal),
        ], children=[
            Part('parcel_wing_r', (-9, -4, -8), rot=(0, 0, 15), plumes=parcel_wing(-1)),
            Part('parcel_wing_l', (9, -4, -8), rot=(0, 0, -15), plumes=parcel_wing(1)),
        ]),
        Part('light', (0, -9, 0), cubes=[Cube(-7, -1, -7, 14, 1, 14, glowing(mat(GOLD, 0.95, 0.0, 0.0)))]),
    ])
    return Model('parcel', 128, 128, [base])


def projectiles():
    """Seelenlanze (zeigt im Modell entlang -z): Schaft 2 dick, vierkantige Spitze 4 -> 3 -> 2, zwei kurze Flügel am
    Schaftende. Der Federpfeil wird im Renderer aus dem Federn-Atlas gezeichnet."""
    def soul(base, strength):
        def shade(face, x, y, w, h):
            v = base + FACE[face] + 0.08 * (0.5 - y / max(h - 1, 1))
            c = ramp(SOUL, v)
            return (c, (c[0], c[1], c[2], strength))
        return shade

    def shaft(face, x, y, w, h):
        # Schaft: dunkler Seelenstahl mit hellem Grat oben; nur die Stirnseiten glimmen. So bleibt die Spitze das
        # hellste Teil, und die Lanze liest sich als Waffe statt als Lichtstrich.
        if face in ('front', 'back'):
            return soul(0.8, 220)(face, x, y, w, h)
        v = 0.3 + FACE[face] + (0.12 if face == 'top' else 0.0)
        return ramp(SOUL, v)

    lance = Part('lance', (0, 0, 0), cubes=[
        Cube(-1, -1, -9, 2, 2, 23, shaft),
        Cube(-2, -2, -13, 4, 4, 4, soul(0.66, 255)),
        Cube(-1.5, -1.5, -16, 3, 3, 3, soul(0.78, 255)),
        Cube(-1, -1, -19, 2, 2, 3, soul(0.92, 255)),
        Cube(-1.5, -1.5, 12, 3, 3, 2, soul(0.5, 200)),
    ], plumes=[
        Plume((side * 1.0, 0, 9), (0, side * -38, 0), 4.0, 8.0, CELL_LONG, 'x+' if side > 0 else 'x-', flip=side < 0,
              bend=0.04, shade=(0.8, 1.0))
        for side in (-1, 1)
    ])
    return Model('projectiles', 128, 64, [lance])
