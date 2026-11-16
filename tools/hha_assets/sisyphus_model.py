"""Sisyphos (Wächter seiner Strafstätte): Modell, Textur, Leuchtmaske und Schlüsselposen.

Teil von boss_models.py (Aufruf dort mit --only sisyphus). Brief: tmp/overhaul/models/sisyphos.md.

Erkennungsmerkmal: der Fels auf Nacken und Schultern, größer als sein Oberkörper, darunter ein tief gebeugter Mann
mit riesigen Unterarmen und Waden, dessen Nacken und rechte Schulter selbst zu Stein geworden sind.

Koordinaten: 1/16 Block vor dem Renderer-Faktor (GuardianScale.SISYPHUS = 0,69), +y nach unten, -z ist vorn, Füße bei
y = 24. Die Grundhaltung der Knochen ist die Ruhepose `idle`; alle Posen in POSES sind vollständig (jeder animierte
Knochen steht drin), damit Vorschau und Kotlin (SisyphusModel.kt) dieselben Winkel benutzen.

    python tools/hha_assets/sisyphus_model.py --sync-kotlin

schreibt die Posentabelle in SisyphusModel.kt (Block zwischen den Marken), damit die Winkel nie auseinanderlaufen.
"""
import math
import os
import sys

from bosskit import Cube
from guardian_kit import FACE_LIGHT, tattered
from modelkit import Model, Part, hexc, mix, ramp, rgb, speckle

# =====================================================================================================================
# Farben (Brief: Haut, Fels, Leinen/Leder/Eisen) und Leuchtfarbe
# =====================================================================================================================

SKIN = rgb('#24140b', '#432818', '#6a4430', '#946446', '#bb8a64', '#dcb48e')
DUST = hexc('#b9a68a')
ROCK = rgb('#17140f', '#2c2720', '#47403a', '#675f56', '#8c847a', '#b2aaa0')
LINEN = rgb('#211b12', '#3a3122', '#584c36', '#7a6d50', '#9e926e')
LEATHER = rgb('#1a0f07', '#301d0f', '#4a2e18', '#664226')
IRON = rgb('#0f0e0d', '#24211e', '#3d3832', '#5d564c', '#857d70')
HAIR = rgb('#3a3834', '#5e5b54', '#8a867c', '#b4b0a4', '#d8d4c8')
# Glut im Fels. Brief: #ff8c32. In der Maske etwas röter (#ff7428), weil reines #ff8c32 vom Gold-Prüfer der Vorschau
# als „goldenes Leuchten" gezählt wird und Gold im Spiel „sicher/offen" bedeutet. Im Albedo bleibt der Brief-Ton.
GLOW_TINT = (255, 116, 40)
EMBER = rgb('#3a0e04', '#9c3208', '#ff8c32', '#ffd49a')

GRAIN = 0.035          # Korn höchstens ±4 % (Statuen-Ästhetik)
FACES = ('top', 'bottom', 'front', 'back', 'left', 'right')


def ember(t, strength=255):
    return (ramp(EMBER, t), (GLOW_TINT[0], GLOW_TINT[1], GLOW_TINT[2], strength))


def grain(x, y, seed):
    return (speckle(x, y, seed) - 0.5) * 2 * GRAIN


def lit(face, y, h, grad=0.16):
    """Flächenlicht + Verlauf von oben (hell) nach unten (dunkel)."""
    return FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad


def edge_terms(face, x, y, w, h, top_hi=0.04, seam=0.07):
    """Harte Lichtkante oben an Seitenflächen, 1 Texel Schattensaum unten (Fuge zum Nachbarn)."""
    v = 0.0
    if face in ('front', 'back', 'left', 'right'):
        if y == 0 and h > 2:
            v += top_hi
        if y == h - 1 and h > 2:
            v -= seam
    elif face == 'top' and (x == 0 or x == w - 1 or y == 0 or y == h - 1):
        v += top_hi * 0.6
    return v


# =====================================================================================================================
# Maler
# =====================================================================================================================

def skin(base=0.5, seed=0, dust=0.0, grad=0.07, ribs=False, abs_=False, pecs=False):
    """Sonnengegerbte, staubige Haut. dust: Staubschleier zum unteren Rand hin (Knie, Hände, Füße)."""
    def paint(face, x, y, w, h):
        v = base + lit(face, y, h, grad) + grain(x, y, seed) + edge_terms(face, x, y, w, h)
        cx = (w - 1) / 2.0
        if ribs and face in ('left', 'right', 'front'):
            # Rippen: schräge dunkle Bänder an den Flanken, sehnig.
            span = w if face != 'front' else w
            edge = min(x, span - 1 - x) if face == 'front' else 99
            if (face != 'front' or edge < w * 0.28) and (y + (x if face == 'left' else -x) // 3) % 3 == 0 and 1 <= y < h - 1:
                v -= 0.12
        if abs_ and face == 'front':
            if abs(x - cx) < 0.6:
                v -= 0.1
            if y % 4 == 3 and abs(x - cx) < w * 0.32:
                v -= 0.08
        if pecs and face == 'front':
            if abs(x - cx) < 0.6:
                v -= 0.12                                    # Brustbeinrinne
            if y == h - 1 or (y == h - 2 and abs(x - cx) > 2):
                v -= 0.12                                    # Unterkante des Brustmuskels
        v = max(0.02, v)
        col = ramp(SKIN, v)
        if dust > 0:
            d = dust * max(0.0, (y / max(h - 1, 1)) - 0.25) / 0.75
            if face == 'bottom':
                d = dust
            col = mix(col, DUST, min(0.55, d * 0.6))
        return col
    return paint


def stone_to_skin(skin_paint, seed, rule):
    """Steinschwiele: rule(face, x, y, w, h) > 0 heißt Fels, Rand mit Bruchkante (heller Grat, dunkle Fuge)."""
    def paint(face, x, y, w, h):
        r = rule(face, x, y, w, h) + (speckle(x, y, seed) - 0.5) * 0.5
        if r > 0.35:
            v = 0.5 + lit(face, y, h, 0.14) + grain(x, y, seed + 3) + facet(face, x, y, w, h, seed)
            if r < 0.6:
                v += 0.16                                  # heller Bruchgrat am Übergang
            return ramp(ROCK, v)
        if r > 0.18:
            return mix(skin_paint(face, x, y, w, h), ramp(ROCK, 0.18), 0.65)   # dunkle Fuge zur Haut
        return skin_paint(face, x, y, w, h)
    return paint


def facet(face, x, y, w, h, seed):
    """Große Bruchflächen: Jede Fläche wird von einer schrägen Linie in zwei Facetten geteilt."""
    a = (speckle(seed, 1, seed) - 0.5) * 2.2
    b = 0.35 + speckle(seed, 2, seed + 1) * 0.3
    u = x / max(w - 1, 1) - 0.5
    t = y / max(h - 1, 1)
    side = t - (b + a * u)
    if abs(side) * max(h, 1) < 0.8:
        return 0.1                                        # heller Grat zwischen den Facetten
    return -0.07 if side > 0 else 0.04


def crack_hit(cracks, face, x, y, w, h):
    """Abstand eines Texels zum nächsten Riss (Polylinien in 0..1-Koordinaten der Fläche)."""
    best = 99.0
    for f, pts in cracks:
        if f != face:
            continue
        for (ax, ay), (bx, by) in zip(pts, pts[1:]):
            ax, ay, bx, by = ax * (w - 1), ay * (h - 1), bx * (w - 1), by * (h - 1)
            dx, dy = bx - ax, by - ay
            ll = dx * dx + dy * dy
            t = 0.0 if ll == 0 else max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / ll))
            px, py = ax + dx * t, ay + dy * t
            best = min(best, math.hypot(x - px, y - py))
    return best


def rock(base=0.5, seed=0, cracks=(), worn=None):
    """Der Fels: große Facetten, helle Kanten, glatte Verläufe. Breite Glutrisse (cracks), ausgeschliffene Mulde (worn)."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] * 0.75 + (0.5 - y / max(h - 1, 1)) * 0.12 + grain(x, y, seed) + facet(face, x, y, w, h, seed + FACES.index(face))
        if face != 'bottom' and y == 0:
            v += 0.08                                      # heller Grat oben an der Bruchkante
        if face == 'bottom':
            v = max(v, 0.24)                               # Unterseiten dunkel, aber nicht schwarz
        if worn and face in worn:
            # glattgeschliffen, wo er seit Jahrtausenden auf dem Nacken liegt: heller, ohne Facetten.
            v = 0.55 + FACE_LIGHT[face] * 0.6 + grain(x, y, seed) * 0.5
        if cracks:
            d = crack_hit(cracks, face, x, y, w, h)
            if d < 1.05:
                return ember(0.62 + (1.05 - d) * 0.35, 245)
            if d < 1.7:
                return ember(0.28, 150)
            if d < 2.6:
                v -= 0.22                                  # verkohlter Rand
        return ramp(ROCK, max(0.02, v))
    return paint


def linen(base=0.5, seed=0):
    def paint(face, x, y, w, h):
        v = base + lit(face, y, h, 0.14) + grain(x, y, seed) + edge_terms(face, x, y, w, h, 0.05, 0.08)
        if face in ('front', 'back') and x % 4 == 1:
            v -= 0.06                                      # grobe Falten
        return ramp(LINEN, v)
    return paint


def leather(base=0.5, seed=0):
    def paint(face, x, y, w, h):
        v = base + lit(face, y, h, 0.1) + grain(x, y, seed) + edge_terms(face, x, y, w, h, 0.08, 0.1)
        return ramp(LEATHER, v)
    return paint


def iron(base=0.5, seed=0):
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] * 1.2 + grain(x, y, seed) + edge_terms(face, x, y, w, h, 0.16, 0.12)
        return ramp(IRON, v)
    return paint


def link_paint(face, x, y, w, h):
    """Kettenglied: Rand hell, Mitte als dunkles Loch."""
    v = 0.62 + FACE_LIGHT[face] * 1.2
    if face in ('front', 'back') and 0 < x < w - 1 and 1 < y < h - 2:
        return ramp(IRON, 0.12)
    if y == 0:
        v += 0.15
    return ramp(IRON, v)


def hair(base=0.6, seed=0):
    def paint(face, x, y, w, h):
        v = base + lit(face, y, h, 0.2) + grain(x, y, seed) + math.sin(x * 1.3 + seed) * 0.05
        if face in ('front', 'back', 'left', 'right') and y == h - 1:
            v -= 0.12
        return ramp(HAIR, v)
    return paint


def face_paint(face, x, y, w, h):
    """Gesicht 11 x 11: kahler Scheitel, tiefe Stirnfalten, Augen als zwei schwache Glutpunkte unter dem Brauenwulst."""
    base = skin(0.56, 601, grad=0.1)
    if face != 'front':
        if face == 'top':
            # kahler, sonnenverbrannter Scheitel: glänzender Fleck
            d = math.hypot(x - (w - 1) / 2, y - (h - 1) / 2)
            return ramp(SKIN, 0.78 - d * 0.035 + grain(x, y, 602))
        return base(face, x, y, w, h)
    cx = (w - 1) / 2.0
    d = abs(x - cx)
    if y in (1, 3) and d < 4:
        return ramp(SKIN, 0.3)                              # tiefe Stirnfalten
    if y == 5 and 1.5 < d < 4.0:
        if 1.6 < d < 3.6:
            return ember(0.55, 150)                          # Glutaugen, schwach
        return ramp(SKIN, 0.1)                               # tiefliegende Augenhöhlen
    if y == 6 and 1 < d < 4:
        return ramp(SKIN, 0.32)
    if y >= 7 and 2.5 < d < 3.5:
        return ramp(SKIN, 0.3)                               # Nasolabialfalten
    return base(face, x, y, w, h)


# =====================================================================================================================
# Ruhepose (Grundrotation der Knochen, Grad) und Posen
# =====================================================================================================================

BONES = ['hips', 'body', 'head', 'jaw', 'beard', 'beard_tip', 'boulder',
         'arm_r', 'fore_r', 'hand_r', 'chain_r', 'arm_l', 'fore_l', 'hand_l', 'chain_l',
         'loin', 'loin_back', 'leg_r', 'shin_r', 'foot_r', 'leg_l', 'shin_l', 'foot_l']

BODY_LEAN = 40

# Werte: (pitch, yaw, roll) in Grad, optional + (dx, dy, dz) Versatz in Einheiten.
IDLE = {
    'hips': (0, 0, 0),
    'body': (BODY_LEAN, 0, 0),
    'head': (-52, 0, 0),
    'jaw': (6, 0, 0),
    'beard': (0, 0, 0),
    'beard_tip': (10, 0, 0),
    'boulder': (0, 0, 0),
    # rechter Arm (Steinschulter) hängt schwer nach vorn, linker greift hinauf in den Nacken und stützt den Fels
    'arm_r': (-38, 0, 14),
    'fore_r': (-28, 0, 0),
    'hand_r': (-10, 0, 0),
    'chain_r': (0, 0, 0),
    'arm_l': (-130, -20, -70),
    'fore_l': (-100, 0, 0),
    'hand_l': (-30, 0, 0),
    'chain_l': (0, 0, 0),
    'loin': (0, 0, 0),
    'loin_back': (0, 0, 0),
    'leg_r': (-32, 0, 6),
    'shin_r': (46, 0, 0),
    'foot_r': (-14, 0, -6),
    'leg_l': (-24, 0, -5),
    'shin_l': (38, 0, 0),
    'foot_l': (-14, 0, 5),
}


def pose(**over):
    p = dict(IDLE)
    p.update(over)
    return p


def _mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _rot(p, y, r):
    p, y, r = math.radians(p), math.radians(y), math.radians(r)
    cx, sx, cy, sy, cz, sz = math.cos(p), math.sin(p), math.cos(y), math.sin(y), math.cos(r), math.sin(r)
    rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    return _mul(rz, _mul(ry, rx))


def _app(m, v):
    return tuple(sum(m[i][j] * v[j] for j in range(3)) for i in range(3))


def _euler_zyx(m):
    """Euler-Winkel (pitch, yaw, roll) in Grad zu m = Rz(roll) * Ry(yaw) * Rx(pitch), wie JOML getEulerAnglesZYX."""
    yaw = math.asin(max(-1.0, min(1.0, -m[2][0])))
    pitch = math.atan2(m[2][1], m[2][2])
    roll = math.atan2(m[1][0], m[0][0])
    return tuple(math.degrees(a) for a in (pitch, yaw, roll))


def _hanging(p, *chain):
    """Drehung, die die Weltdrehung der Elternkette aufhebt: Das Teil hängt senkrecht (Transponierte = Inverse)."""
    m = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    for b in chain:
        m = _mul(m, _rot(*p[b][:3]))
    return _euler_zyx([[m[j][i] for j in range(3)] for i in range(3)])


def hang(p):
    """Ketten, Bart und Lendentuch hängen mit der Schwerkraft: Sie heben die Neigung ihrer Eltern auf.
    Dieselbe Regel rechnet SisyphusModel.kt zur Laufzeit (dort plus Pendeln). Die Ketten hängen exakt senkrecht
    (inverse Weltdrehung von Hüfte, Rumpf, Ober- und Unterarm), Bart und Tuch nur gedämpft über die Neigung."""
    hips, body, head = p['hips'], p['body'], p['head']
    out = dict(p)
    out['chain_r'] = _hanging(p, 'hips', 'body', 'arm_r', 'fore_r')
    out['chain_l'] = _hanging(p, 'hips', 'body', 'arm_l', 'fore_l')
    beard = -(hips[0] + body[0] + head[0]) * 0.85
    out['beard'] = (beard, 0, -(hips[2] + body[2] + head[2]) * 0.8)
    loin = -(hips[0] + body[0])
    leg_front = min(p['leg_r'][0], p['leg_l'][0])
    out['loin'] = (loin + min(0.0, leg_front) * 0.6, 0, -body[2])
    leg_back = max(p['leg_r'][0], p['leg_l'][0])
    out['loin_back'] = (loin + max(0.0, leg_back) * 0.6 + 4, 0, -body[2])
    for k in ('loin', 'loin_back', 'chain_r', 'chain_l', 'beard'):
        if len(p[k]) == 6:
            out[k] = out[k] + tuple(p[k][3:])
    return out


IDLE = hang(IDLE)


# =====================================================================================================================
# Geometrie
# =====================================================================================================================

def R(name):
    return IDLE[name][:3]


def rounded(x, y, z, w, h, d, paint, c=2, axis='y', paint2=None):
    """Gefaster Block aus zwei Quadern: Die vier Kanten entlang axis sind um c abgeschrägt (achteckiger Querschnitt)."""
    p2 = paint2 or paint
    # Der zweite Quader ist entlang axis um 1 kürzer (je 0,5 eingerückt), damit keine Flächen deckungsgleich
    # aufeinanderliegen (kein Z-Fighting im Spiel).
    if axis == 'y':
        return [Cube(x, y, z + c, w, h, d - 2 * c, paint), Cube(x + c, y + 0.5, z, w - 2 * c, h - 1, d, p2)]
    if axis == 'x':
        return [Cube(x, y, z + c, w, h, d - 2 * c, paint), Cube(x + 0.5, y + c, z, w - 1, h - 2 * c, d, p2)]
    return [Cube(x + c, y, z, w - 2 * c, h, d, paint), Cube(x, y + c, z + 0.5, w, h - 2 * c, d - 1, p2)]


def chunk(name, offset, rot, size, seed, cracks=(), worn=None, chamfer=2, full=True):
    """Ein Felsbrocken: gefaster Quader, um seinen eigenen Mittelpunkt gedreht. full: alle zwölf Kanten gefast
    (drei Quader), sonst nur vier (zwei Quader)."""
    w, h, d = size
    c = chamfer
    cubes = [Cube(-w / 2, -(h - 2 * c) / 2, -(d - 2 * c) / 2, w, h - 2 * c, d - 2 * c, rock(0.54, seed, cracks, worn)),
             Cube(-(w - 2 * c) / 2, -h / 2, -(d - 2 * c) / 2 + (0 if full else 0.5), w - 2 * c, h, d - 2 * c - (0 if full else 1),
                  rock(0.54, seed + 1, cracks, worn))]
    if full:
        cubes.append(Cube(-(w - 2 * c) / 2, -(h - 2 * c) / 2, -d / 2, w - 2 * c, h - 2 * c, d, rock(0.54, seed + 2, cracks, worn)))
    return Part(name, offset, rot, cubes=cubes)


def boulder_part():
    """Fels, Durchmesser ~34, in sich geschlossen: Ursprung in der Mitte, alle Brocken darunter. Sieben gegeneinander
    gedrehte, gefaste Brocken, damit der Umriss aus jeder Richtung ein unregelmäßiges Vieleck ist. Unten zwei Wülste
    links und rechts, dazwischen die glattgeschliffene Mulde, in der der Nacken liegt."""
    kids = [
        chunk('boulder_core', (0, -1, 0), (6, 28, -4), (27, 25, 27), 610, chamfer=4,
              cracks=[('back', [(0.95, 0.1), (0.62, 0.32), (0.7, 0.6), (0.4, 1.0)])]),
        chunk('boulder_a', (8, -9, -6), (34, -12, 22), (18, 16, 18), 620,
              cracks=[('top', [(0.25, 0.0), (0.4, 0.35), (0.3, 0.65), (0.55, 1.0)]),
                      ('left', [(0.0, 0.3), (0.4, 0.45), (0.6, 0.8), (1.0, 0.9)])]),
        chunk('boulder_b', (-9, -7, 6), (-22, 44, 12), (20, 15, 16), 630, full=False,
              cracks=[('right', [(0.1, 0.0), (0.35, 0.4), (0.25, 0.7), (0.5, 1.0)])]),
        chunk('boulder_c', (-9, 3, -8), (16, -32, 46), (15, 15, 17), 640, full=False,
              cracks=[('right', [(0.1, 0.3), (0.45, 0.5), (0.6, 0.9)])]),
        chunk('boulder_d', (10, 4, 8), (42, 58, -8), (16, 14, 15), 650, full=False,
              cracks=[('right', [(0.2, 0.1), (0.55, 0.5), (0.45, 0.95)])]),
        chunk('boulder_e', (2, -13, 3), (12, 45, 8), (16, 8, 15), 655, full=False,
              cracks=[('back', [(0.0, 0.2), (0.5, 0.6), (1.0, 0.5)])]),
        chunk('boulder_lr', (-10, 10, 1), (8, 10, 28), (13, 11, 16), 660, worn=('bottom', 'left', 'right'), full=False),
        chunk('boulder_ll', (10, 10, 1), (-6, -14, -24), (13, 11, 16), 670, worn=('bottom', 'left', 'right'), full=False),
    ]
    return Part('boulder', (0, -26, 20), R('boulder'), children=kids)


def head_part():
    jaw = Part('jaw', (0, -2, -3), R('jaw'), cubes=[
        Cube(-4.5, -0.5, -3.5, 9, 3, 7, skin(0.5, 605)),                   # Kiefer
        Cube(-4.5, -1.5, -5, 9, 2, 2, hair(0.38, 606)),                      # Schnurrbart
    ])
    beard = Part('beard', (0, -1, -5.5), R('beard'), cubes=[
        Cube(-5, 0, -2, 10, 8, 5, hair(0.34, 607)),
        Cube(-6, -1, -1, 12, 4, 4, hair(0.3, 617)),                        # Backenbart
    ], children=[
        Part('beard_tip', (0, 7.5, 0.5), R('beard_tip'), cubes=[
            Cube(-4, 0, -2, 8, 7, 4, hair(0.3, 608)),
            Cube(-2.5, 6.5, -1.5, 5, 4, 3, hair(0.26, 609)),
        ]),
    ])
    return Part('head', (0, -28, -8), R('head'), cubes=[
        Cube(-5.5, -11, -6.5, 11, 11, 11, face_paint),                      # Schädel 11 x 11 x 11
        Cube(-5.5, -8.5, -8, 11, 2, 2, skin(0.4, 603, grad=0.0)),           # Brauenwulst, tief über den Augen
        Cube(-1.5, -7, -9, 3, 5, 3, nose_paint),                            # Nase
        Cube(-6, -10, -7, 12, 2, 12, leather(0.5, 611)),                    # Stirnband
        Cube(-6, -8, -2.5, 12, 6, 8, hair(0.5, 612)),                       # grauer Haarkranz hinten/seitlich
        Cube(-6.5, -6, -3, 2, 3, 2, skin(0.5, 613)), Cube(4.5, -6, -3, 2, 3, 2, skin(0.5, 614)),   # Ohren
        Cube(-5, -4, -7, 2, 2, 2, skin(0.6, 618)), Cube(3, -4, -7, 2, 2, 2, skin(0.6, 619)),       # Wangenknochen
    ], children=[jaw, beard])


def nose_paint(face, x, y, w, h):
    v = 0.62 + FACE_LIGHT[face] + (0.08 if face == 'front' and x == 1 else 0) - (0.2 if face == 'bottom' else 0)
    return ramp(SKIN, v)


def arm_part(side, s):
    """Oberarm 7, Unterarm 10 (dicker als der Oberarm), Hand 10 mit breiten Fingern; Schelle mit zwei Kettengliedern."""
    stone = side < 0
    delt_skin = skin(0.56, 620 + (side > 0))
    if stone:
        # Die rechte Schulter ist zu Stein geworden: oben Fels, nach unten mit Bruchkante in Haut übergehend.
        delt = stone_to_skin(delt_skin, 625, lambda f, x, y, w, h: 1.45 - y / max(h - 1, 1) * 1.25 if f != 'bottom' else -1)
    else:
        delt = delt_skin
    hand = Part(f'hand_{s}', (0, 12, 0), R(f'hand_{s}'), cubes=[
        Cube(-5, 0, -2.5, 10, 6, 5, skin(0.52, 630, dust=0.5)),            # Handteller
        Cube(-5, 5, -3, 10, 3, 6, finger_paint(631)),                     # Fingerglieder, breit
        Cube(-4.5, 7.5, -2.5, 9, 3, 5, finger_paint(637)),                # Fingerspitzen
        Cube(-2, 0.5, -5.5, 4, 5, 3, skin(0.5, 632, dust=0.4)),           # Daumen
    ])
    chain = Part(f'chain_{s}', (side * 5.5, 10, 0), R(f'chain_{s}'), cubes=[
        Cube(-1.5, 0, -2, 3, 6, 4, link_paint),
        Cube(-2, 5, -1.5, 4, 6, 3, link_paint),
    ])
    fore = Part(f'fore_{s}', (0, 12, 0), R(f'fore_{s}'), cubes=[
        Cube(-3.5, -1.5, -3.5, 7, 4, 7, skin(0.5, 633)),                  # Ellenbogen
        *rounded(-5, 1.5, -5, 10, 8, 10, skin(0.54, 634, grad=0.1), 2),  # Unterarm 10
        Cube(-4, 8, -4, 8, 4, 8, skin(0.48, 635)),
        *rounded(-6, 8.5, -6, 12, 4, 12, iron(0.56, 636), 2),            # Schelle
    ], children=[hand, chain])
    cubes = [
        *rounded(-5, -4.5, -5, 10, 8, 10, delt, 2),                       # Schulterkappe
        *rounded(-3.5, 2, -3.5, 7, 11, 7, skin(0.52, 622), 1),            # Oberarm 7
        Cube(-2.5, 3, -4.5, 5, 7, 2, skin(0.56, 623)),                    # Bizeps
    ]
    kids = [fore]
    if stone:
        # Steinplatten auf der rechten Schulter, schräg übereinander wie Schorf aus Fels
        kids += [
            Part('stone_r1', (-1, -5, 0), (0, 0, 18), cubes=[Cube(-6, -2, -5, 11, 4, 10, rock(0.46, 626))]),
            Part('stone_r2', (-5, -1, 1), (10, 0, 48), cubes=[Cube(-3, -3, -4, 6, 6, 8, rock(0.4, 627))]),
        ]
    return Part(f'arm_{s}', (side * 13.5, -24, 1), R(f'arm_{s}'), cubes=cubes, children=kids)


def finger_paint(seed):
    base = skin(0.5, seed, dust=0.6)

    def paint(face, x, y, w, h):
        if face in ('front', 'back', 'bottom') and x in (w // 4, w // 2, (3 * w) // 4) and 0 < x < w - 1:
            return mix(ramp(SKIN, 0.18), DUST, 0.15)                       # Fugen zwischen vier breiten Fingern
        return base(face, x, y, w, h)
    return paint


def toe_paint(seed):
    base = skin(0.52, seed, dust=0.8)

    def paint(face, x, y, w, h):
        if face in ('front', 'top') and x in (3, 5, 7):
            return mix(ramp(SKIN, 0.22), DUST, 0.3)                       # Fugen zwischen den Zehen
        return base(face, x, y, w, h)
    return paint


def leg_part(side, s):
    """Bein 36: Oberschenkel 9, Wade 10, breiter nackter Fuß 10 x 4 x 15."""
    foot = Part(f'foot_{s}', (0, 14, 0), R(f'foot_{s}'), cubes=[
        Cube(-5, 0, -8, 10, 4, 12, skin(0.5, 650, dust=0.8)),
        Cube(-5, 1, -11, 10, 3, 3, toe_paint(652)),                       # Zehen
        Cube(-3.5, -2, -2, 7, 3, 6, skin(0.46, 651, dust=0.6)),           # Knöchel/Ferse
    ])
    shin = Part(f'shin_{s}', (0, 17, 0), R(f'shin_{s}'), cubes=[
        Cube(-4, -1.5, -4.5, 8, 4, 8, skin(0.58, 640, dust=0.9)),         # Knie, staubig
        *rounded(-5, 2, -3.5, 10, 8, 10, skin(0.52, 641, grad=0.1), 2),  # Wade 10, nach hinten gewölbt
        Cube(-3.5, 9, -3.5, 7, 6, 7, skin(0.48, 642, dust=0.6)),
    ], children=[foot])
    return Part(f'leg_{s}', (side * 5, 1, 0), R(f'leg_{s}'), cubes=[
        *rounded(-4.5, -1, -5, 9, 10, 10, skin(0.5, 643), 2),
        *rounded(-4, 8, -4.5, 8, 10, 8, skin(0.52, 644), 1),
    ], children=[shin])


def chest_rule(face, x, y, w, h):
    """Brustkorb: Die Steinschwiele greift von der rechten Schulter (−x) auf Schlüsselbein und obere Brust über."""
    u = x / max(w - 1, 1)
    t = y / max(h - 1, 1)
    if face in ('front', 'back'):
        k = u if face == 'front' else 1 - u
        return 1.15 - k * 2.6 - t * 1.0
    if face == 'top':
        return 1.1 - u * 1.6
    if face == 'right':
        return 1.0 - t * 1.4
    return -1


def neck_rule(face, x, y, w, h):
    """Nacken: oben und hinten Stein (dort liegt der Fels), vorn und unten Haut."""
    t = y / max(h - 1, 1)
    if face in ('top', 'back'):
        return 1.2 - (x / max(w - 1, 1)) * 0.5
    if face in ('left', 'right'):
        return 0.9 - t * 1.0 + (z_back(x, w) if face else 0)
    return -1


def z_back(x, w):
    return (x / max(w - 1, 1) - 0.5) * 0.6


def trap_rule(face, x, y, w, h):
    """Nacken und rechte Rückenseite (−x) sind Stein; vorn bleibt Haut."""
    u = x / max(w - 1, 1)
    t = y / max(h - 1, 1)
    if face == 'front':
        return -1
    if face == 'top':
        return 1.1 - u * 0.7
    if face == 'back':
        return 1.2 - t * 0.5 - u * 0.75
    if face == 'right':                         # −x-Seite
        return 1.1 - t * 0.7
    return 0.25 - t


def back_rule(face, x, y, w, h):
    """Oberer Rücken: rechts oben Stein, nach unten und links in Haut auslaufend."""
    u = x / max(w - 1, 1)
    t = y / max(h - 1, 1)
    if face == 'back':
        return 1.0 - t * 1.1 - u * 0.5
    if face == 'right':
        return 0.9 - t * 1.2
    if face == 'top':
        return 1.0 - u * 0.8
    return -1


def callus_parts():
    """Steinschwiele: Platten mit Bruchkanten auf Nacken und rechter Schulter (Fels, glüht nicht)."""
    return [
        Part('callus_a', (-7, -29, 4), (-18, 10, 14), cubes=[Cube(-6, -2, -4, 12, 4, 9, rock(0.42, 680))]),
        Part('callus_b', (0, -31, 5), (-30, -6, -4), cubes=[Cube(-5, -2, -3, 10, 3, 7, rock(0.48, 681))]),
        Part('callus_c', (-13, -25, 5), (10, 20, 30), cubes=[Cube(-3, -3, -3, 6, 6, 7, rock(0.4, 682))]),
    ]


def strap_parts():
    return [
        Part('strap_a', (0, 0, 0), (0, 0, 33), cubes=[Cube(-1.5, -12, -1, 3, 24, 2, leather(0.62, 690))]),
        Part('strap_b', (0, 0, 0), (0, 0, -33), cubes=[Cube(-1.5, -12, -1.5, 3, 24, 2, leather(0.56, 691))]),
    ]


def sisyphus():
    loin = Part('loin', (0, -2.6, -6.4), R('loin'), cubes=[
        Cube(-5, 0, -1, 10, 13, 2, tattered(linen(0.58, 700), 3, 701)),
    ])
    loin_back = Part('loin_back', (0, 5.7, 3.8), R('loin_back'), cubes=[
        Cube(-6, 0, -1, 12, 11, 2, tattered(linen(0.5, 702), 3, 703)),
    ])
    body = Part('body', (0, -3, 0), R('body'), cubes=[
        *rounded(-9, -10, -5.5, 18, 11, 11, skin(0.5, 710, abs_=True), 2),                # Bauch, Taille 18
        *rounded(-11.5, -17, -6.5, 23, 8, 13, skin(0.52, 711, ribs=True), 2),             # Rippenbogen, sehnig
        *rounded(-14, -26, -7, 28, 10, 14, stone_to_skin(skin(0.56, 712, grad=0.1), 717, chest_rule), 2),   # Brustkorb, rechts oben Stein
        Cube(-12, -25.5, -8.5, 24, 7, 3, skin(0.6, 716, pecs=True, grad=0.1)),           # Brustmuskeln
        *rounded(-11, -30, -2, 22, 7, 10, stone_to_skin(skin(0.54, 713), 714, back_rule), 2),   # Trapez: Steinschwiele
        Cube(-4.5, -31, -8.5, 9, 7, 8, stone_to_skin(skin(0.5, 715), 718, neck_rule)),   # Hals, Nacken versteint
    ], children=[
        head_part(),
        boulder_part(),
        arm_part(-1, 'r'),
        arm_part(1, 'l'),
        loin,
        loin_back,
        Part('strap', (0, -17, -7.5), cubes=[], children=strap_parts()),
        *callus_parts(),
    ])
    hips = Part('hips', (0, -10, 6), R('hips'), cubes=[
        *rounded(-9.5, -4, -6, 19, 8, 12, linen(0.5, 720), 2),                            # Lendenwickel
        Cube(-10, -4.5, -6.5, 20, 2, 13, leather(0.56, 721)),                              # Strick/Gürtel
    ], children=[body, leg_part(-1, 'r'), leg_part(1, 'l')])
    return Model('sisyphus', 512, 256, [hips])


# =====================================================================================================================
# Schlüsselposen (Grad). Löser: Füße auf den Boden (y = 24), Fels an einen Weltpunkt.
# =====================================================================================================================

_PARTS = {}


def _parts():
    if not _PARTS:
        def walk(part, parent):
            _PARTS[part.name] = (part, parent)
            for c in part.children:
                walk(c, part.name)
        for root in sisyphus().parts:
            walk(root, None)
    return _PARTS


def frame(p, bone):
    """Weltdrehung und -lage eines Knochens in Pose p (wie preview.py / ModelPart)."""
    part, parent = _parts()[bone]
    rot0, pos0 = ([[1, 0, 0], [0, 1, 0], [0, 0, 1]], (0, 0, 0)) if parent is None else frame(p, parent)
    v = p.get(bone) or tuple(part.rot)
    pivot = part.pivot
    if len(v) >= 6:
        pivot = (pivot[0] + v[3], pivot[1] + v[4], pivot[2] + v[5])
    o = _app(rot0, pivot)
    rot = _mul(rot0, _rot(*v[:3]))
    return rot, (pos0[0] + o[0], pos0[1] + o[1], pos0[2] + o[2])


def _lowest(p, bones):
    low = -1e9
    for b in bones:
        part, _ = _parts()[b]
        rot, pos = frame(p, b)
        for c in part.cubes:
            for dx in (0, c.w):
                for dy in (0, c.h):
                    for dz in (0, c.d):
                        q = _app(rot, (c.x + dx, c.y + dy, c.z + dz))
                        low = max(low, pos[1] + q[1])
    return low


LEG_BONES = ['leg_r', 'shin_r', 'foot_r', 'leg_l', 'shin_l', 'foot_l']


def _six(v):
    return tuple(v) + (0, 0, 0)[len(v) - 3:] if len(v) < 6 else tuple(v)


def grounded(p, bones=LEG_BONES):
    """Verschiebt die Hüfte senkrecht, bis der tiefste Punkt von bones genau auf dem Boden (y = 24) steht."""
    h = _six(p['hips'])
    p = dict(p, hips=h)
    dy = 24 - _lowest(p, bones)
    p['hips'] = h[:4] + (round(h[4] + dy, 2),) + h[5:]
    return p


def boulder_at(p, world, rot=(0, 0, 0)):
    """Setzt den Fels (Ursprung = Mitte) an einen Weltpunkt; rot ist seine Drehung relativ zum Rumpf."""
    part, _ = _parts()['boulder']
    rb, pb = frame(p, 'body')
    d = (world[0] - pb[0], world[1] - pb[1], world[2] - pb[2])
    local = tuple(sum(rb[j][i] * d[j] for j in range(3)) for i in range(3))   # Rb^T * d
    off = tuple(round(local[i] - part.pivot[i], 2) for i in range(3))
    return dict(p, boulder=tuple(rot) + off)


def flat_feet(p, sides=('r', 'l')):
    """Füße flach auf den Boden: Die Fußdrehung hebt Hüft-, Bein- und Schienbeinneigung auf."""
    out = dict(p)
    for s in sides:
        f = p[f'foot_{s}']
        out[f'foot_{s}'] = (-(p['hips'][0] + p[f'leg_{s}'][0] + p[f'shin_{s}'][0]), f[1], f[2]) + tuple(f[3:])
    return out


def build_pose(over, flat=('r', 'l'), ground=LEG_BONES):
    p = flat_feet(pose(**over), flat)
    if ground:
        p = grounded(p, ground)
    return hang(p)


# Ohne Fels (REGROW 0-36): aufgerichtet, die Arme hängen, er keucht mit offenem Mund. Die offene Haltung.
REGROW_EMPTY = build_pose(dict(
    body=(10, 0, 0), head=(-28, 0, 0, 0, -3, 2), jaw=(24, 0, 0),
    arm_r=(-6, 0, 12), fore_r=(-12, 0, 0), hand_r=(0, 0, 0),
    arm_l=(-4, 0, -10), fore_l=(-10, 0, 0), hand_l=(0, 0, 0),
    leg_r=(-14, 0, 6), shin_r=(22, 0, 0), leg_l=(-10, 0, -5), shin_l=(18, 0, 0),
))

# THROW 0-14 stemmen, halten bis 24: Fels senkrecht über dem Kopf, Arme gestreckt, Knie durchgedrückt, zurückgelehnt.
THROW_LIFT = boulder_at(build_pose(dict(
    body=(-14, 0, 0), head=(-26, 0, 0, 0, -3, 2), jaw=(20, 0, 0),
    arm_r=(-168, 0, -12), fore_r=(-6, 0, 0), hand_r=(-40, 0, 0),
    arm_l=(-168, 0, 12), fore_l=(-6, 0, 0), hand_l=(-40, 0, 0),
    leg_r=(-6, 0, 7), shin_r=(6, 0, 0), leg_l=(-4, 0, -7), shin_l=(5, 0, 0),
)), (0, -80, 13), (14, 20, 0))

# THROW 24: Wurf mit dem ganzen Körper, er fällt dabei fast auf die Knie (Fels weg).
THROW_RELEASE = build_pose(dict(
    body=(74, 0, 0), head=(-58, 0, 0), jaw=(16, 0, 0),
    arm_r=(-118, 0, 10), fore_r=(-12, 0, 0), hand_r=(-10, 0, 0),
    arm_l=(-118, 0, -10), fore_l=(-12, 0, 0), hand_l=(-10, 0, 0),
    leg_r=(-14, 0, 6), shin_r=(96, 0, 0), foot_r=(-60, 0, -6),
    leg_l=(-82, 0, -6), shin_l=(84, 0, 0),
), flat=('l',))

# BOWL 0-10 absetzen, halten bis 22: Fels vor ihm am Boden, er tief in der Hocke dahinter, Hände und Schulter am Stein.
BOWL_SET = boulder_at(build_pose(dict(
    body=(56, -14, 0), head=(-40, 10, 0), jaw=(10, 0, 0),
    arm_r=(-60, 10, 10), fore_r=(-40, 0, 0), hand_r=(-30, 0, 0),
    arm_l=(-82, -20, -24), fore_l=(-34, 0, 0), hand_l=(-30, 0, 0),
    leg_r=(-104, 0, 10), shin_r=(128, 0, 0), leg_l=(-84, 0, -12), shin_l=(118, 0, 0),
)), (1, 7, -22), (-30, 10, 0))

# BOWL 22: Stoß mit einem Satz nach vorn, Arme gestreckt, hinteres Bein lang (Fels weg).
BOWL_PUSH = build_pose(dict(
    body=(62, 0, 0), head=(-52, 0, 0), jaw=(18, 0, 0),
    arm_r=(-150, 0, 10), fore_r=(-6, 0, 0), hand_r=(-40, 0, 0),
    arm_l=(-150, 0, -10), fore_l=(-6, 0, 0), hand_l=(-40, 0, 0),
    leg_r=(48, 0, 6), shin_r=(8, 0, 0), foot_r=(-34, 0, 0),
    leg_l=(-70, 0, -6), shin_l=(76, 0, 0),
), flat=('l',))

# STOMP 0-20 ausholen: Fels auf die linke Schulter, rechtes Knie bis zur Brust, Gewicht auf dem linken Bein.
STOMP_WINDUP = boulder_at(build_pose(dict(
    body=(22, 0, 12), head=(-30, 0, -8), jaw=(12, 0, 0),
    arm_r=(-30, 0, 62), fore_r=(-30, 0, 0), hand_r=(0, 0, 0),
    arm_l=(-168, 0, -42), fore_l=(-96, 0, 0), hand_l=(-20, 0, 0),
    leg_r=(-112, 0, 8), shin_r=(104, 0, 0), foot_r=(10, 0, 0),
    leg_l=(-12, 0, -6), shin_l=(18, 0, 0),
), flat=('l',), ground=['leg_l', 'shin_l', 'foot_l']), (8, -48, 7), (0, 0, -16))

# STOMP 20: der rechte Fuß kracht herab, er geht tief in die Knie, der Fels sackt zurück in den Nacken.
STOMP_HIT = build_pose(dict(
    body=(52, 0, 0), head=(-46, 0, 0), jaw=(22, 0, 0),
    arm_r=(-20, 0, 50), fore_r=(-20, 0, 0),
    leg_r=(-62, 0, 8), shin_r=(66, 0, 0), leg_l=(-40, 0, -8), shin_l=(56, 0, 0),
))

# CURSE 0-40: lässt den Fels hinter sich fallen, breitet die Arme aus, Kopf in den Nacken, schreit zum Gipfel.
CURSE = boulder_at(build_pose(dict(
    body=(-12, 0, 0), head=(-56, 0, 0, 0, -3, 3), jaw=(38, 0, 0),
    arm_r=(-28, 0, 118), fore_r=(-8, 0, 0), hand_r=(10, 0, 0),
    arm_l=(-28, 0, -118), fore_l=(-8, 0, 0), hand_l=(10, 0, 0),
    leg_r=(-10, 0, 14), shin_r=(12, 0, 0), leg_l=(-6, 0, -14), shin_l=(10, 0, 0),
)), (6, 7, 30), (34, 30, 12))

# STAGGER: vom eigenen Fels getroffen, liegt halb auf der Seite, der rechte Arm schützt den Kopf; der Fels ist ihm
# von der Schulter gerollt und liegt hinter seinen Füßen.
STAGGER = boulder_at(build_pose(dict(
    hips=(0, 0, 72), body=(30, 0, 0), head=(-20, 0, 0), jaw=(14, 0, 0),
    arm_r=(-160, 0, -35), fore_r=(-105, 0, 0), hand_r=(-20, 0, 0),
    arm_l=(-80, 0, 10), fore_l=(-30, 0, 0), hand_l=(0, 0, 0),
    leg_r=(-60, 0, 0), shin_r=(80, 0, 0), leg_l=(-40, 0, 0), shin_l=(70, 0, 0),
), flat=(), ground=LEG_BONES + ['arm_l', 'fore_l', 'body', 'hips', 'head']), (-30, 7, 18), (20, 40, 30))

POSES_DEG = {
    'idle': IDLE,
    'throw_lift': THROW_LIFT,
    'throw_release': THROW_RELEASE,
    'bowl_set': BOWL_SET,
    'bowl_push': BOWL_PUSH,
    'stomp_windup': STOMP_WINDUP,
    'stomp_hit': STOMP_HIT,
    'regrow_empty': REGROW_EMPTY,
    'curse': CURSE,
    'stagger': STAGGER,
}
# Ohne Fels in der Vorschau (Kotlin blendet ihn über die Zeit aus bzw. lässt ihn wachsen).
HIDDEN = {'throw_release': ('boulder',), 'bowl_push': ('boulder',), 'regrow_empty': ('boulder',)}


def _rad(p, hidden=()):
    return {k: (None if k in hidden else tuple(math.radians(a) for a in v[:3]) + tuple(v[3:])) for k, v in p.items()}


POSES = {name: _rad(p, HIDDEN.get(name, ())) for name, p in POSES_DEG.items()}


# =====================================================================================================================
# Kotlin-Abgleich: Posentabelle in SisyphusModel.kt schreiben
# =====================================================================================================================

KOTLIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'kotlin', 'dev', 'henny',
                      'hha', 'client', 'model', 'SisyphusModel.kt')
BEGIN, END = '    // <poses> erzeugt von sisyphus_model.py --sync-kotlin, nicht von Hand ändern', '    // </poses>'


def kotlin_table():
    parents = {name: (parent or '') for name, (part, parent) in _parts().items()}
    lines = [BEGIN, '    val BONES = arrayOf(' + ', '.join(f'"{b}"' for b in BONES) + ')',
             '    val PARENTS = arrayOf(' + ', '.join(f'"{parents[b]}"' for b in BONES) + ')']
    for name, p in POSES_DEG.items():
        vals = []
        for b in BONES:
            v = tuple(p[b]) + (0, 0, 0)[len(p[b]) - 3:] if len(p[b]) < 6 else tuple(p[b])
            vals.extend([f'{math.radians(v[0]):.4f}f', f'{math.radians(v[1]):.4f}f', f'{math.radians(v[2]):.4f}f',
                         f'{v[3]:.2f}f', f'{v[4]:.2f}f', f'{v[5]:.2f}f'])
        rows = [', '.join(vals[i:i + 6]) for i in range(0, len(vals), 6)]
        lines.append(f'    val {name.upper()} = floatArrayOf(')
        lines.extend(f'        {r},  // {b}' for r, b in zip(rows, BONES))
        lines.append('    )')
    lines.append(END)
    return '\n'.join(lines)


def sync_kotlin():
    with open(KOTLIN, encoding='utf8') as f:
        src = f.read()
    a, b = src.index(BEGIN), src.index(END) + len(END)
    with open(KOTLIN, 'w', encoding='utf8', newline='\n') as f:
        f.write(src[:a] + kotlin_table() + src[b:])
    print('SisyphusModel.kt: Posentabelle aktualisiert')


if __name__ == '__main__' and '--sync-kotlin' in sys.argv:
    sync_kotlin()
