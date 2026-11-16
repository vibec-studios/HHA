"""Requisiten des Olymp (`olymp_props`): ein Modell, mehrere unabhängige Knochen. Der BoltRenderer zeichnet je
Geschossart genau einen davon (siehe client/model/OlympPropsModel.kt); Schild und Kranz zeichnen die Kampfszenen.

| Knochen | Art | Was |
| --- | --- | --- |
| `keraunos` | KERAUNOS | Zeus' Blitz, 70 lang, vier Segmente mit Knicken ± 35°, Keilspitze; Ursprung am Griff, Spitze bei -y |
| `eagle` (+ `eagle_wing_r`, `eagle_wing_l`) | EAGLE | Adler im Sturzflug, 48 Spannweite, fliegt nach -z |
| `ingot` / `ingot_cold` | INGOT | Brontes' Rohling 10 × 5 × 5, glühend bzw. abgekühlt |
| `wedge` | HAMMER_BOLT | der fertige Donnerkeil: Doppelkeil 26 lang mit Griffring, Längsachse y |
| `torch` | TORCH | Hekates Fackel: Schaft 34, Kopf 6, Flammenkeil 8; Ursprung Schaftmitte, Flamme bei -y |
| `blade` | BLADE | Bias Klingenbogen: Halbmond 60 breit (x), Band 6 hoch, 2 tief, wölbt sich nach -z |
| `shield` | – | Kratos' Turmschild 30 × 62 × 3, Vorderseite -z |
| `wreath` | – | Nikes Kranz: Ring Ø 12 waagerecht, acht Blattkeile |

Stil wie die Bestandsmodelle: schattierte Maler mit Verlauf, Lichtkante, Korn; nur Blitzader, Adlerauge, Glut,
Keilschneiden, Flamme und Klingenschneide leuchten. Gold leuchtet nie.

Koordinaten: 1/16 Block vor dem Renderer-Faktor, +y nach unten, -z vorn. Jeder Knochen hat seinen Ursprung in sich.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from bosskit import Cube  # noqa: E402  (rundet Maße auf ganze Texel)
from modelkit import Model, Part, hexc, mix, ramp, rgb, speckle  # noqa: E402

LIGHT = {'top': 0.14, 'bottom': -0.22, 'front': 0.0, 'back': -0.1, 'left': -0.06, 'right': -0.06}
SIDES = ('front', 'back', 'left', 'right')

# ---------------------------------------------------------------------------------------------------------------------
# Paletten (Leitfarben aus den Briefs, zu Rampen erweitert)
# ---------------------------------------------------------------------------------------------------------------------
BOLT = rgb('#0b2160', '#1d4fb8', '#4a86df', '#8fc7ff', '#d2ebff')
BOLT_CORE = hexc('#e4f3ff')
THUNDER_GLOW = (223, 241, 255)                      # #dff1ff
GOLD = rgb('#5a3608', '#8a5a12', '#c08422', '#d9a02c', '#f0c24a', '#ffd96a')
BROWN = rgb('#1e1209', '#3a2412', '#5e3b1a', '#8a5a26', '#b07c3c')
WHITE = rgb('#6f7684', '#a9b0bd', '#d7dce4', '#f1f3f6', '#ffffff')
TALON = rgb('#2a2010', '#7a5a18', '#c09a30', '#e8c860')
HEAT = rgb('#5a1a04', '#b4440a', '#f7841f', '#ffb84a', '#ffe59a', '#fff6d8')
HEAT_GLOW = (255, 118, 40)                          # tiefes Orange (statt #ffb347: die Vorschau würde es als Gold werten)
IRON = rgb('#14161c', '#2a2e37', '#4a505c', '#717887', '#9aa2b0', '#c4cad4')
WOOD = rgb('#170e08', '#2a1a12', '#432a1a', '#5a3a22', '#76502e')
LEATHER = rgb('#1a0e08', '#2a1810', '#43271a', '#5a3420', '#7a4a2c')
SILVER = rgb('#2c2f3a', '#4b4f5e', '#727789', '#9aa0b4', '#c4c9d8', '#e2e6f0')
FLAME = rgb('#4a3a9a', '#9a86e8', '#c4b8f6', '#f1edff', '#ffffff')
MOON_GLOW = (207, 196, 255)                         # #cfc4ff
STEEL = rgb('#2a2f3a', '#4c5462', '#7a8494', '#a9b2c0', '#d8dee8', '#f4f6fa')
CRIMSON = rgb('#4a0c10', '#9a1c1c', '#e0483c', '#ff9a8a')
CRIMSON_GLOW = (255, 112, 120)                      # Rot der Schneide (#ff9a8a wäre für die Vorschau „Gold“)
BRONZE = rgb('#1e1209', '#3a2412', '#5e3b1a', '#8a5a26', '#b47a38', '#d39a4a', '#ecc27a')


def lit(color, tint, strength=255):
    return (color, (tint[0], tint[1], tint[2], strength))


# ---------------------------------------------------------------------------------------------------------------------
# Maler
# ---------------------------------------------------------------------------------------------------------------------

def surf(pal, base=0.55, grad=0.16, seed=0, roundx=0.0, ridge=0.0, noise=0.03, edge=0.0):
    """Glatte Fläche wie bei Hermes: Flächenlicht, Verlauf oben → unten, leichtes Korn, optional Rundung, Lichtkante
    oben (ridge) und hellere Seitenkanten (edge)."""
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad + (speckle(x, y, seed) - 0.5) * noise * 2
        if roundx and face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += roundx * (0.35 - k * k)
        if ridge and face in SIDES and y == 0:
            v += ridge
        if edge and (x == 0 or x == w - 1):
            v += edge
        return ramp(pal, v)
    return paint


def bolt(seed=0, base=0.6, vein=True, strength=235):
    """Blitzkörper: blauer Kristall, an den Kanten dunkel (#1d4fb8), zur Mitte hell (#8fc7ff); in der Mitte jeder
    Längsfläche eine 2 Texel breite Ader. Sie leuchtet (#dff1ff) auf den breiten Flächen (vorn/hinten, Zickzack-Ebene);
    auf den Schmalseiten ist sie nur hell gemalt, damit der Leuchtanteil klein bleibt."""
    def paint(face, x, y, w, h):
        if face in SIDES and w >= 3:
            cx = (w - 1) / 2.0
            d = abs(x - cx)
            if vein and d < 1.0:
                c = mix(BOLT_CORE, hexc('#bfe2ff'), 0.25 * ((y + seed) % 5 == 0))
                if face in ('left', 'right'):
                    return mix(c, hexc('#8fc7ff'), 0.35)      # Schmalseite: helle Ader ohne Leuchten
                return lit(c, THUNDER_GLOW, strength)
            k = d / max(cx, 1)
            v = base + 0.22 * (1 - k) - 0.28 * k * k + LIGHT[face] * 0.6 + (0.5 - y / max(h - 1, 1)) * 0.08
            v += (speckle(x, y, seed) - 0.5) * 0.06
            if x == 0 or x == w - 1:
                v -= 0.12
            return ramp(BOLT, v)
        # Stirnflächen: dunkles Blau mit hellem Kern
        cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
        k = max(abs(x - cx) / max(cx, 1), abs(y - cy) / max(cy, 1))
        return ramp(BOLT, 0.72 - 0.4 * k + LIGHT[face] * 0.5)
    return paint


def gold(base=0.62, seed=0, bands=0):
    """Poliertes Gold: Lichtgrat oben, Rundung, Korn; bands > 0 legt umlaufende Rillen (Wicklung)."""
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.18 + (speckle(x, y, seed) - 0.5) * 0.05
        if face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += 0.3 * (0.35 - k * k)
        if face in SIDES and (y == 0 or y == h - 1):
            v += 0.16 if y == 0 else -0.12
        if bands and face in SIDES and 0 < y < h - 1 and y % bands == 0:
            v -= 0.22
        return ramp(GOLD, v)
    return paint


# ---------------------------------------------------------------------------------------------------------------------
# Keraunos (auch von zeus_model.py benutzt, damit Hand und Flug genau gleich aussehen)
# ---------------------------------------------------------------------------------------------------------------------

KERAUNOS_SEG = 15          # Länge der zwei Knick-Segmente
KERAUNOS_KINK = 35         # Grad


def keraunos_part(name='keraunos', pivot=(0, 0, 0), rot=(0, 0, 0)):
    """Blitz entlang -y, Ursprung in der Mitte des Griffs. Unten ein kurzer Gegendorn mit Goldknauf, oben
    Griffsegment, zwei geknickte Segmente (+35°, -35°) und das Spitzensegment mit Keil. Länge 70.
    Querschnitt 6 (Zickzack-Ebene x) × 4, zur Spitze hin 4 × 4; die Ader ist auf jeder Längsfläche 2 Texel breit."""
    seg = KERAUNOS_SEG
    tip = Part(f'{name}_3', (0, -seg, 0), (0, 0, KERAUNOS_KINK), cubes=[
        Cube(-2.5, -9, -2, 5, 11, 4, bolt(34)),
        Cube(-2, -12, -1.5, 4, 3, 3, bolt(35, 0.66)),           # Keilspitze in drei Stufen
        Cube(-1, -15, -1, 2, 3, 2, bolt(36, 0.8, vein=False)),
        Cube(-3.5, -2, -1.5, 2, 3, 3, bolt(37, 0.62, vein=False)),   # Funke außen am Knick
    ])
    seg2 = Part(f'{name}_2', (0, -seg, 0), (0, 0, -2 * KERAUNOS_KINK), cubes=[
        Cube(-3, -seg, -2, 6, seg + 2, 4, bolt(32)),
        Cube(2.5, -seg + 2, -1, 4, 3, 2, bolt(33, 0.6, vein=False)),    # kurze Gabel nach außen
        Cube(5.5, -seg + 0.5, -1, 2, 2, 2, bolt(39, 0.7, vein=False)),
    ], children=[tip])
    seg1 = Part(f'{name}_1', (0, -12, 0), (0, 0, KERAUNOS_KINK), cubes=[
        Cube(-3, -seg, -2, 6, seg + 2, 4, bolt(30)),
    ], children=[seg2])
    return Part(name, pivot, rot, cubes=[
        Cube(-3, -13, -2, 6, 14, 4, bolt(28)),                  # Griffsegment nach oben
        Cube(-3.5, -5, -2.5, 7, 10, 5, gold(0.56, 20, bands=2)),   # Griffwicklung (Gold, Rillen)
        Cube(-4, -7, -3, 8, 2, 6, gold(0.72, 21)),              # Zwinge oben
        Cube(-4, 5, -3, 8, 2, 6, gold(0.64, 22)),               # Zwinge unten
        Cube(-2.5, 7, -2, 5, 5, 4, bolt(29)),                   # Gegendorn
        Cube(-1.5, 12, -1.5, 3, 2, 3, bolt(38, 0.72, vein=False)),
    ], children=[seg1])


# ---------------------------------------------------------------------------------------------------------------------
# Adler
# ---------------------------------------------------------------------------------------------------------------------

def plumage(pal, base=0.5, seed=0, rows=3, bars=False):
    """Gefieder: Federreihen quer (Schuppen), jede Reihe oben heller; bars legt dunkle Querbinden."""
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] + (speckle(x, y, seed) - 0.5) * 0.08
        r = (y + (x // 2) % 2) % rows
        v += 0.1 if r == 0 else (-0.08 if r == rows - 1 else 0.0)
        if bars and (x + y // 2) % 5 == 0:
            v -= 0.1
        return ramp(pal, v)
    return paint


def wing_paint(seed, outer):
    """Schwinge von oben: vorn (Flügelbug, front) helle Deckfedern, nach hinten die dunklen Schwungfedern als Streifen."""
    def paint(face, x, y, w, h):
        if face in ('top', 'bottom'):
            depth = y / max(h - 1, 1)                           # 0 Vorderkante, 1 Hinterkante
            under = face == 'bottom'
            if depth < 0.3:
                v = 0.62 - depth * 0.4 + (0.08 if (x + y) % 3 == 0 else 0.0)
            else:
                v = 0.42 - 0.18 * depth + (0.1 if x % 3 == 0 else 0.0) - (0.06 if x % 3 == 2 else 0.0)
            if under:
                v += 0.14
                if depth > 0.3 and (y + x // 3) % 3 == 0:
                    v -= 0.14                                   # Binden auf der Unterseite
            if outer and depth > 0.8 and x % 3 == 1:
                return None                                     # ausgefranste Hinterkante
            return ramp(BROWN, v + (speckle(x, y, seed) - 0.5) * 0.06)
        if face == 'front':
            return ramp(BROWN, 0.68 + (speckle(x, y, seed) - 0.5) * 0.06)
        return ramp(BROWN, 0.32)
    return paint


def eagle_head_paint(face, x, y, w, h):
    """Weißer Kopf: Brauenwulst über einem leuchtenden Auge auf beiden Seiten, Schattenfuge zum Hals."""
    base = surf(WHITE, 0.62, 0.2, 61, roundx=0.2)
    if face in ('left', 'right'):
        ex = 1 if face == 'right' else w - 2              # Auge nach vorn gesetzt (vorn = kleines x bei 'right')
        if y == 2 and x == ex:
            return lit(hexc('#eaf6ff'), THUNDER_GLOW, 255)
        if y == 2 and abs(x - ex) == 1:
            return ramp(WHITE, 0.18)
        if y == 1 and abs(x - ex) <= 1:
            return ramp(WHITE, 0.4)                       # Braue
    return base(face, x, y, w, h)


def eagle_part():
    brown = plumage(BROWN, 0.5, 40)
    belly = plumage(BROWN, 0.58, 41, bars=True)
    white = surf(WHITE, 0.6, 0.16, 42, roundx=0.25)
    beak = gold(0.66, 43)
    talon = surf(TALON, 0.55, 0.2, 44)

    def tail_paint(face, x, y, w, h):
        v = 0.66 + LIGHT[face] + (0.08 if x % 2 == 0 else -0.04) - 0.15 * (y / max(h - 1, 1)) * (face == 'top')
        return ramp(WHITE, v + (speckle(x, y, 45) - 0.5) * 0.05)

    def wing(s, sx):
        """Schwinge: breiter Arm-Keil am Rumpf (3 dick), Hand-Keil nach hinten gepfeilt (2 dick), vier gespreizte
        Handschwingen. Spannweite gesamt 48."""
        def at(x, w):
            return x if sx > 0 else -x - w
        fingers = []
        for i in range(4):
            length = 7 - i
            fingers.append(Part(f'eagle_finger_{s}{i}', (sx * 8.5, 0, -3 + i * 2.6), (0, sx * (-10 + i * 13), 0), cubes=[
                Cube(at(0, length), -1, -1, length, 2, 2, wing_paint(50 + i, False))]))
        hand = Part(f'eagle_hand_{s}', (sx * 10.5, 0, 0), (0, sx * -18, sx * -5), cubes=[
            Cube(at(-0.5, 9.5), -1, -4, 9.5, 2, 11, wing_paint(48, True)),
        ], children=fingers)
        return Part(f'eagle_wing_{s}', (sx * 3.5, -2, -2), (0, 0, 0), cubes=[
            Cube(at(-0.5, 11.5), -1.5, -5, 11.5, 3, 13, wing_paint(46, False)),
        ], children=[hand])

    tail = Part('eagle_tail', (0, -1, 8), (-8, 0, 0), cubes=[
        Cube(-3, -1, 0, 6, 2, 9, tail_paint),
    ], children=[
        Part('eagle_tail_r', (-2, 0, 1), (0, 22, 0), cubes=[Cube(-2.5, -1, 0, 4, 2, 8, tail_paint)]),
        Part('eagle_tail_l', (2, 0, 1), (0, -22, 0), cubes=[Cube(-1.5, -1, 0, 4, 2, 8, tail_paint)]),
    ])
    head = Part('eagle_head', (0, -3, -10), (0, 0, 0), cubes=[
        Cube(-2.5, -2, -4, 5, 5, 5, white),                     # Hals
        Cube(-2.5, -3.5, -8.5, 5, 5, 6, eagle_head_paint),      # Kopf
        Cube(-1.5, -3, -11.5, 3, 3, 3, beak),                   # Oberschnabel
        Cube(-1, -1, -12, 2, 2, 2, gold(0.48, 49)),             # Haken nach unten
    ])
    return Part('eagle', (0, 0, 0), cubes=[
        Cube(-4, -3, -10, 8, 7, 18, brown),                     # Rumpf (Keil: vorn breit)
        Cube(-3, 1, -9, 6, 4, 12, belly),                       # Brust/Bauch
        Cube(-2.5, -4, -6, 5, 2, 12, plumage(BROWN, 0.42, 51)), # Rücken
        Cube(-3.5, 3, -6, 2, 3, 3, talon),                      # angelegte Fänge
        Cube(1.5, 3, -6, 2, 3, 3, talon),
        Cube(-3.5, 5, -8, 2, 2, 2, surf(TALON, 0.25, 0.0, 52)),
        Cube(1.5, 5, -8, 2, 2, 2, surf(TALON, 0.25, 0.0, 53)),
    ], children=[head, tail, wing('r', -1), wing('l', 1)])


# ---------------------------------------------------------------------------------------------------------------------
# Rohling und Donnerkeil (Brontes)
# ---------------------------------------------------------------------------------------------------------------------

def molten(seed):
    """Glühender Rohling: ganze Fläche leuchtet; Kern hell, Kanten orange, ein paar dunklere Krustenflecken."""
    def paint(face, x, y, w, h):
        cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
        k = max(abs(x - cx) / max(cx, 1), abs(y - cy) / max(cy, 1))
        v = 0.86 - 0.36 * k + LIGHT[face] * 0.5 + (speckle(x, y, seed) - 0.5) * 0.12
        crust = speckle(x * 2 + 5, y * 2 + 3, seed + 7) > 0.72
        if crust:
            v -= 0.3
        return lit(ramp(HEAT, v), HEAT_GLOW, 170 if crust else 255)
    return paint


def forged(seed):
    """Abgekühltes Eisen: Zunder, Hammerschläge, ein letzter dunkelroter Schimmer in der Mitte der Oberseite."""
    def paint(face, x, y, w, h):
        v = 0.5 + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.1 + (speckle(x, y, seed) - 0.5) * 0.14
        if x == 0 or x == w - 1 or y == 0 or y == h - 1:
            v += 0.1
        if (x * 3 + y * 5 + seed) % 11 == 0:
            v -= 0.18                                           # Hammerdelle
        c = ramp(IRON, v)
        if face == 'top' and abs(x - (w - 1) / 2) < 2 and abs(y - (h - 1) / 2) < 1:
            c = mix(c, hexc('#7a2a12'), 0.45)
        return c
    return paint


def ingot_part(name, paint):
    """Barren 10 × 5 × 5 mit gefasten Längskanten (Kreuz aus zwei Quadern)."""
    return Part(name, (0, 0, 0), cubes=[
        Cube(-5, -2.5, -1.5, 10, 5, 3, paint(70 if 'cold' not in name else 72)),
        Cube(-5, -1.5, -2.5, 10, 3, 5, paint(71 if 'cold' not in name else 73), dilate=0.02),
    ])


def wedge_edge(seed, base=0.5):
    """Keilflanke aus Schmiedeeisen; die Schmalseiten (links/rechts) sind die Schneiden: blaue Leuchtlinie."""
    def paint(face, x, y, w, h):
        if face in ('left', 'right'):
            cx = (w - 1) / 2.0
            if abs(x - cx) < 1.0:
                return lit(mix(BOLT_CORE, hexc('#9fd2ff'), 0.3), THUNDER_GLOW, 235)
            return ramp(BOLT, 0.5 + LIGHT[face])
        v = base + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.14 + (speckle(x, y, seed) - 0.5) * 0.1
        if x == 0 or x == w - 1:
            v += 0.16
        return ramp(IRON, v)
    return paint


def wedge_part():
    """Donnerkeil: zwei Keile Spitze nach außen (Achse y), dazwischen Kern mit Griffring. Länge 26."""
    def half(sign):
        # sign -1: oberer Keil (nach -y), +1: unterer Keil
        def y(top, h):
            return top if sign < 0 else -top - h
        return [
            Cube(-3.5, y(-7, 4), -1.5, 7, 4, 3, wedge_edge(80 + sign)),
            Cube(-2.5, y(-10, 3), -1, 5, 3, 2, wedge_edge(82 + sign, 0.56)),
            Cube(-1.5, y(-12, 2), -1, 3, 2, 2, wedge_edge(84 + sign, 0.62)),
            Cube(-1, y(-13, 1), -0.5, 2, 1, 1, surf(BOLT, 0.8, 0.0, 86)),
        ]
    ring = surf(IRON, 0.62, 0.2, 87, ridge=0.18)
    return Part('wedge', (0, 0, 0), cubes=[
        Cube(-3.5, -3, -3, 7, 6, 6, wedge_edge(88, 0.42)),       # Kern
        Cube(-4.5, -1, -4.5, 9, 2, 2, ring),                     # Griffring (vier Stäbe um den Kern)
        Cube(-4.5, -1, 2.5, 9, 2, 2, ring),
        Cube(-4.5, -1, -2.5, 2, 2, 5, ring),
        Cube(2.5, -1, -2.5, 2, 2, 5, ring),
    ] + half(-1) + half(1))


# ---------------------------------------------------------------------------------------------------------------------
# Fackel (Hekate)
# ---------------------------------------------------------------------------------------------------------------------

def flame(seed, base):
    def paint(face, x, y, w, h):
        cx = (w - 1) / 2.0
        k = abs(x - cx) / max(cx, 1) if face in SIDES else 0.4
        t = base + 0.35 * (1 - k) + 0.12 * (y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.1
        return lit(ramp(FLAME, t), MOON_GLOW, 230 if k < 0.6 else 190)
    return paint


def torch_part():
    """Fackel entlang -y: Holzschaft 34 (zwei Lederwicklungen, Silberzwinge), Kopf 6 × 6 × 6 als Altsilber-Korb,
    Flammenkeil 8 hoch aus zwei um 45° versetzten Facetten. Ursprung in der Schaftmitte."""
    wood = surf(WOOD, 0.55, 0.1, 90, roundx=0.3)

    def shaft(face, x, y, w, h):
        if face in SIDES and (8 <= y <= 12 or 20 <= y <= 24) and (x + y) % 3 != 0:
            return ramp(LEATHER, 0.55 + LIGHT[face])
        c = wood(face, x, y, w, h)
        if face in SIDES and (y * 3 + x) % 7 == 0:
            c = mix(c, hexc('#120a06'), 0.3)                     # Maserung
        return c

    def basket(face, x, y, w, h):
        v = 0.56 + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.2 + (speckle(x, y, 91) - 0.5) * 0.08
        if face in SIDES and (x in (0, w - 1) or y in (0, h - 1)):
            v += 0.16
        elif face in SIDES and x % 2 == 1 and 0 < y < h - 1:
            return ramp(FLAME, 0.3)                              # Flammenschein zwischen den Stäben (ohne Leuchten)
        return ramp(SILVER, v)

    facet = Part('torch_flame_b', (0, -20, 0), (0, 45, 0), cubes=[
        Cube(-2, -4, -2, 4, 4, 4, flame(93, 0.45)),
        Cube(-1, -7, -1, 2, 3, 2, flame(94, 0.6)),
    ])
    return Part('torch', (0, 0, 0), cubes=[
        Cube(-1.5, -17, -1.5, 3, 34, 3, shaft),
        Cube(-2, 15, -2, 4, 2, 4, surf(SILVER, 0.5, 0.1, 95, ridge=0.2)),     # Endkappe
        Cube(-2, -17, -2, 4, 2, 4, surf(SILVER, 0.6, 0.1, 96, ridge=0.2)),    # Zwinge unter dem Kopf
        Cube(-3, -20, -3, 6, 3, 6, basket),                                    # Korb 6 × 6 × 6 (Unterteil)
        Cube(-3, -23, -3, 6, 3, 6, flame(97, 0.4)),                            # Kopf: Flamme im Korb
        Cube(-2.5, -26, -2.5, 5, 3, 5, flame(98, 0.5)),                        # Flammenkeil, Facette A
        Cube(-1.5, -29, -1.5, 3, 3, 3, flame(99, 0.62)),
        Cube(-0.5, -31, -1, 1, 2, 2, flame(100, 0.75)),
    ], children=[facet])


# ---------------------------------------------------------------------------------------------------------------------
# Klingenbogen (Bia)
# ---------------------------------------------------------------------------------------------------------------------

BLADE_R = 40.0
BLADE_SEGMENTS = 7


def blade_steel(seed, k):
    """Stahlband: oben und unten Lichtkante, Hohlkehle in der Mitte; die Vorderseite (konvex, -z) trägt die rote
    Schneide (1 Texel Leuchtlinie in der Mitte)."""
    def paint(face, x, y, w, h):
        if face == 'front':
            cy = (h - 1) / 2.0
            if abs(y - cy) < 0.6:
                return lit(hexc('#ffd2c8'), CRIMSON_GLOW, 255)
            if abs(y - cy) < 1.6:
                return ramp(CRIMSON, 0.66)
            return ramp(STEEL, 0.78 - 0.1 * abs(y - cy) + (speckle(x, y, seed) - 0.5) * 0.06)
        v = 0.5 + LIGHT[face] + (speckle(x, y, seed) - 0.5) * 0.06 - 0.1 * k
        if face in ('back',) and (y == 0 or y == h - 1):
            v += 0.2
        if face in ('top', 'bottom'):
            v += 0.12 if y == 0 else 0.0
        return ramp(STEEL, v)
    return paint


def blade_part():
    """Halbmond aus sieben Gliedern auf einem Kreis (R 40), Sehne 60, Mitte vorn; Band 6 hoch, an den Enden 4 und 2."""
    half_angle = math.degrees(math.asin(30.0 / BLADE_R))
    sag = BLADE_R - math.sqrt(BLADE_R ** 2 - 30.0 ** 2)
    cz = BLADE_R - sag / 2.0                                    # Kreismitte hinten, Bogen mittig um z = 0
    segs = []
    step = 2 * half_angle / BLADE_SEGMENTS
    seg_w = 2 * BLADE_R * math.tan(math.radians(step / 2)) + 0.6
    for i in range(BLADE_SEGMENTS):
        a = -half_angle + step * (i + 0.5)
        k = abs(i - (BLADE_SEGMENTS - 1) / 2) / ((BLADE_SEGMENTS - 1) / 2)   # 0 Mitte, 1 Ende
        hgt = 6 if k < 0.4 else (4 if k < 0.9 else 2)
        cubes = [Cube(-seg_w / 2, -hgt / 2, -BLADE_R - 1, seg_w, hgt, 2, blade_steel(110 + i, k))]
        if k < 0.9:
            cubes.append(Cube(-seg_w / 2 + 0.5, -1, -BLADE_R - 2, seg_w - 1, 2, 1, blade_steel(120 + i, k)))
        segs.append(Part(f'blade_{i}', (0, 0, cz), (0, -a, 0), cubes=cubes))
    return Part('blade', (0, 0, 0), children=segs)


# ---------------------------------------------------------------------------------------------------------------------
# Turmschild (Kratos)
# ---------------------------------------------------------------------------------------------------------------------

FIST = [
    '..####........',
    '.######.......',
    '.#######......',
    '..#######.....',
    '...#######....',
    '....########..',
    '....#########.',
    '...##########.',
    '..###########.',
    '..##.#.#.####.',
    '..###########.',
    '..##.#.#.###..',
    '...#########..',
    '....#######...',
    '.....#####....',
    '......###.....',
]


def shield_plate(seed, rim=True):
    """Dunkle Bronze, getrieben: Lichtkante, Hammerschlag-Korn, goldener Rand (2 Texel) auf der Vorderseite."""
    def paint(face, x, y, w, h):
        v = 0.48 + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.12 + (speckle(x, y, seed) - 0.5) * 0.1
        if face == 'front' and rim and (x < 2 or x >= w - 2):
            return ramp(GOLD, 0.66 + (0.12 if x in (0, w - 1) else -0.04) + (speckle(x, y, seed) - 0.5) * 0.06)
        if face in ('top', 'bottom', 'left', 'right'):
            return ramp(GOLD, 0.58 + LIGHT[face])
        if face == 'back':
            return ramp(BRONZE, 0.3 + (speckle(x, y, seed) - 0.5) * 0.08)
        if (x * 7 + y * 3 + seed) % 23 == 0:
            v += 0.12
        return ramp(BRONZE, v)
    return paint


def shield_cap(seed):
    """Abgeschrägte Stufe oben/unten: die ganze Vorderseite ist Goldrand."""
    def paint(face, x, y, w, h):
        if face == 'back':
            return ramp(BRONZE, 0.3)
        return ramp(GOLD, 0.62 + LIGHT[face] + (0.1 if x in (0, w - 1) else 0.0) + (speckle(x, y, seed) - 0.5) * 0.06)
    return paint


def fist_relief(face, x, y, w, h):
    if face == 'front' and 0 <= y < len(FIST) and 0 <= x < len(FIST[0]):
        if FIST[y][x] == '#':
            edge = (y == 0 or FIST[y - 1][x] != '#') or (x == 0 or FIST[y][x - 1] != '#')
            return ramp(GOLD, 0.8 if edge else 0.6 + (speckle(x, y, 140) - 0.5) * 0.06)
        return ramp(BRONZE, 0.36 + (speckle(x, y, 141) - 0.5) * 0.06)
    return ramp(GOLD, 0.56 + LIGHT[face])


def shield_part():
    cubes = [Cube(-15, -25, -1.5, 30, 50, 3, shield_plate(130))]
    for i, (w, seed) in enumerate(((26, 131), (22, 132), (18, 133))):
        cubes.append(Cube(-w / 2, -27 - 2 * i, -1.5, w, 2, 3, shield_cap(seed)))
        cubes.append(Cube(-w / 2, 25 + 2 * i, -1.5, w, 2, 3, shield_cap(seed + 3)))
    cubes += [
        Cube(-7, -9, -2.5, 14, 16, 1, fist_relief),                                 # Faust-Relief, erhaben um 1
        Cube(-13, -24, -2.5, 26, 1, 1, surf(GOLD, 0.7, 0.0, 142)),                  # Querband oben
        Cube(-13, 23, -2.5, 26, 1, 1, surf(GOLD, 0.6, 0.0, 143)),
        Cube(-2, -14, 1.5, 4, 28, 2, surf(LEATHER, 0.5, 0.1, 144, ridge=0.15)),     # Armriemen hinten
        Cube(-6, -2, 1.5, 12, 4, 2, surf(LEATHER, 0.55, 0.1, 145, ridge=0.15)),     # Griff
    ]
    return Part('shield', (0, 0, 0), cubes=cubes)


# ---------------------------------------------------------------------------------------------------------------------
# Kranz (Nike)
# ---------------------------------------------------------------------------------------------------------------------

def leaf_paint(seed, base=0.66):
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.2 + (speckle(x, y, seed) - 0.5) * 0.06
        if face in ('front', 'back') and abs(x - (w - 1) / 2) < 0.6:
            v -= 0.18                                           # Blattrippe
        return ramp(GOLD, v)
    return paint


def wreath_part():
    segs = []
    for k in range(8):
        segs.append(Part(f'wreath_{k}', (0, 0, 0), (0, k * 45, 0), cubes=[
            Cube(-2.5, -1, -6.5, 5, 2, 2, gold(0.6, 150 + k, bands=0)),
        ], children=[Part(f'wreath_leaf_{k}', (0, -0.5, -6.5), (-30, 0, 62), cubes=[
            Cube(-1, -4, -1, 2, 4, 2, leaf_paint(160 + k)),
        ]), Part(f'wreath_leaf_{k}b', (0, -0.5, -4.5), (30, 0, 62), cubes=[
            Cube(-1, -3, -1, 2, 3, 2, leaf_paint(170 + k, 0.48)),
        ])]))
    return Part('wreath', (0, 0, 0), children=segs)


# ---------------------------------------------------------------------------------------------------------------------
# Modell
# ---------------------------------------------------------------------------------------------------------------------

PROPS = ['keraunos', 'eagle', 'ingot', 'ingot_cold', 'wedge', 'torch', 'blade', 'shield', 'wreath']


def olymp_props():
    parts = [
        keraunos_part(),
        eagle_part(),
        ingot_part('ingot', molten),
        ingot_part('ingot_cold', forged),
        wedge_part(),
        torch_part(),
        blade_part(),
        shield_part(),
        wreath_part(),
    ]
    return Model('olymp_props', 256, 256, parts)


def _only(name, extra=None):
    pose = {p: None for p in PROPS if p != name}
    pose.update(extra or {})
    return pose


# Vorschau: je Requisit eine Pose, in der nur dieser Knochen sichtbar ist. Die Flügelstellungen des Adlers sind
# dieselben Winkel wie in OlympPropsModel.kt (EAGLE_SPREAD / EAGLE_TUCK).
EAGLE_SPREAD = {'eagle_wing_r': (0.0, 0.0, 0.0), 'eagle_wing_l': (0.0, 0.0, 0.0)}
EAGLE_TUCK = {'eagle_wing_r': (0.0, -0.55, 0.35), 'eagle_wing_l': (0.0, 0.55, -0.35)}
POSES = {name: _only(name) for name in PROPS}
POSES['eagle_dive'] = _only('eagle', EAGLE_TUCK)
POSES['eagle_top'] = _only('eagle', {'eagle': (-1.1, 0.0, 0.0)})
POSES['blade_top'] = _only('blade', {'blade': (-1.1, 0.0, 0.0)})
POSES['wreath_top'] = _only('wreath', {'wreath': (-0.8, 0.0, 0.0)})
POSES['all'] = {}
