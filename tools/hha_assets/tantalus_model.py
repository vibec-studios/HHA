"""Tantalos (Wächter seiner Strafstätte): Modell, Textur, Leuchtmaske und Schlüsselposen.

Teil von boss_models.py (Aufruf dort mit --only tantalus). Brief: tmp/overhaul/models/tantalos.md.

Erkennungsmerkmal: der verdorrte Feigenast, auf den Rücken gebunden, der in hohem Bogen über den Kopf ragt; an seiner
Spitze baumelt eine pralle Frucht eine Handbreit vor dem Gesicht, für immer außer Reichweite. Darunter eine überlange,
ausgezehrte, gebeugte Gestalt mit eingefallenem Bauch, Rippen, überlangen Armen und dem leeren Kelch in der Linken.

Koordinaten: 1/16 Block vor dem Renderer-Faktor (GuardianScale.TANTALUS = 0,69), +y nach unten, -z ist vorn, Füße bei
y = 24. Die Grundhaltung der Knochen ist die Ruhepose `idle`. Alle Posen in POSES sind vollständig (jeder animierte
Knochen steht drin), damit Vorschau und Kotlin (TantalusModel.kt) dieselben Winkel benutzen:

    python tools/hha_assets/tantalus_model.py --sync-kotlin

schreibt die Posentabelle in TantalusModel.kt (Block zwischen den Marken). boss_models.py --only tantalus tut das über
`variants` ebenfalls, damit Modell und Animation nie auseinanderlaufen.
"""
import math
import os
import sys

from bosskit import Cube
from guardian_kit import FACE_LIGHT, tattered
from modelkit import Model, Part, hexc, mix, ramp, rgb, speckle

# =====================================================================================================================
# Farben: ausgedörrte Haut, verblichener Königspurpur, angelaufenes Gold; dazu Holz/Frucht und Wasser-Türkis
# =====================================================================================================================

SKIN = rgb('#2e2a22', '#4a4436', '#6b6350', '#8a8068', '#aaa086', '#c8bea0')
WET = rgb('#121816', '#202a28', '#33423e', '#4e605a', '#728680', '#a6bab2')
PURPLE = rgb('#0c0612', '#1c0e2a', '#311a46', '#4a2a64', '#663c84')
TARNISH = rgb('#1a1408', '#3a2e12', '#5e4c1e', '#86702e', '#ae9648', '#d6c27a')
VERDIGRIS = rgb('#0e2a24', '#1a4a3c', '#2c705a', '#4a9a7e')
WEED = rgb('#06140a', '#0c2814', '#184424', '#2a6436')
HAIR = rgb('#05080a', '#0d1416', '#172226', '#24363a', '#3a5052')
WOOD = rgb('#16120e', '#2c241c', '#463a2e', '#62564a', '#837868', '#a49a88')
LEAF = rgb('#1e1a0c', '#3a3216', '#5a4c22', '#7a6a32', '#9a8a4a')
FIG = rgb('#1c0206', '#3e060c', '#640c16', '#8e1824', '#b42c38', '#d85a62')
ROPE = rgb('#1c170e', '#322a1a', '#4c4128', '#6a5c3a', '#8a7a52')
MOUTH = rgb('#060404', '#140a0a', '#2a1410', '#46241c')
TIDE_TINT = (90, 220, 255)                     # #5adcff
TIDE = rgb('#05324a', '#0c6e8e', '#3cc8ec', '#bff4ff')

GRAIN = 0.035                                   # Korn höchstens ±4 % (Statuen-Ästhetik)
FACE_SEED = {'top': 3, 'bottom': 5, 'front': 7, 'back': 11, 'left': 13, 'right': 17}
SIDES = ('front', 'back', 'left', 'right')


def tide(t, strength=255):
    return (ramp(TIDE, t), (TIDE_TINT[0], TIDE_TINT[1], TIDE_TINT[2], strength))


def grain(x, y, seed):
    return (speckle(x, y, seed) - 0.5) * 2 * GRAIN


def lit(face, y, h, grad=0.16):
    """Flächenlicht + Verlauf von oben (hell) nach unten (dunkel)."""
    return FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad


def ridge(face, x, y, w, h, top=0.08, seam=0.1):
    """Harte Lichtkante oben an Seitenflächen, 1 Texel Schattensaum unten."""
    if face in SIDES and h > 2:
        if y == 0:
            return top
        if y == h - 1:
            return -seam
    return 0.0


def _hash(ix, iy, s):
    n = (ix * 374761393 + iy * 668265263 + s * 982451653) & 0xffffffff
    n = ((n ^ (n >> 13)) * 1274126177) & 0xffffffff
    return ((n ^ (n >> 16)) & 0xffff) / 65536.0


def crack(x, y, seed, cell=5.0):
    """Abstand (in Texeln) zur nächsten Schollenkante eines Voronoi-Musters: getrockneter Schlamm."""
    gx, gy = (x + 0.5) / cell, (y + 0.5) / cell
    ix, iy = int(math.floor(gx)), int(math.floor(gy))
    d1 = d2 = 9.0
    for oy in (-1, 0, 1):
        for ox in (-1, 0, 1):
            cx = ix + ox + 0.15 + 0.7 * _hash(ix + ox, iy + oy, seed)
            cy = iy + oy + 0.15 + 0.7 * _hash(ix + ox, iy + oy, seed + 1)
            d = math.hypot(gx - cx, gy - cy)
            if d < d1:
                d1, d2 = d, d1
            elif d < d2:
                d2 = d
    return (d2 - d1) * cell * 0.5


# =====================================================================================================================
# Maler
# =====================================================================================================================

def skin(base=0.55, seed=0, cracks=0.0, cell=5.0, grad=0.16):
    """Ausgedörrte Haut, grau-ocker; `cracks` (0..1) zeichnet Schollenkanten wie getrockneter Schlamm."""
    def paint(face, x, y, w, h):
        v = base + lit(face, y, h, grad) + grain(x, y, seed) + ridge(face, x, y, w, h)
        if cracks and face != 'bottom' and w > 2 and h > 2:
            e = crack(x, y, seed * 31 + FACE_SEED[face], cell)
            if e < 0.45:
                v -= 0.32 * cracks
            elif e < 1.2:
                v += 0.05 * cracks                     # aufgeworfene Schollenkante fängt Licht
        return ramp(SKIN, v)
    return paint


def wet_skin(base=0.5, seed=0):
    """Nasse Schienbeine und Füße: dunkler, kühler, mit Glanzstreif."""
    def paint(face, x, y, w, h):
        v = base + lit(face, y, h, 0.1) + grain(x, y, seed) + ridge(face, x, y, w, h, 0.12, 0.08)
        if face in ('front', 'left', 'right') and x == 1 and w > 3:
            v += 0.2
        if face == 'top':
            v += 0.08
        return ramp(WET, v)
    return paint


def ribcage(face, x, y, w, h):
    """Rumpf hinter den Rippenspangen: vorn tiefer im Schatten, damit die Spangen hervortreten."""
    v = 0.3 + lit(face, y, h, 0.12) + grain(x, y, 61)
    if face in ('front', 'left', 'right'):
        v -= 0.16
        if abs(x - (w - 1) / 2.0) < 1.0:
            v += 0.06                                  # Brustbein
    return ramp(SKIN, v)


def belly(face, x, y, w, h):
    """Der eingefallene Bauch: oben im Schatten des Brustkorbs, unten Risse."""
    v = 0.44 + lit(face, y, h, 0.0) + grain(x, y, 62)
    v += -0.16 * max(0.0, 1.0 - y / 4.0)              # Schatten unter dem Rippenbogen
    if face == 'front':
        e = crack(x, y, 77, 4.0)
        if e < 0.45:
            v -= 0.2
        if abs(x - (w - 1) / 2.0) < 0.6 and y > 3:
            v -= 0.08                                  # Bauchfalte
    return ramp(SKIN, v)


def rib(face, x, y, w, h):
    v = 0.5 + FACE_LIGHT[face] * 1.4 + grain(x, y, 63)
    if face in SIDES and y == 0:
        v += 0.14                                      # Lichtkante oben auf der Spange
    if face in SIDES and y == h - 1:
        v -= 0.14
    if face == 'top':
        v += 0.1
    return ramp(SKIN, v)


def head_paint(face, x, y, w, h):
    """Schädel: eingefallene Schläfen, tiefe Höhlen, Augen in Wasser-Türkis, rissige Stirn."""
    v = 0.56 + lit(face, y, h, 0.12) + grain(x, y, 71)
    if face == 'front':
        cx = (w - 1) / 2.0
        d = abs(x - cx)
        if y in (4, 5) and 1.0 < d < 3.6:
            if y == 5 and 1.4 < d < 3.2:
                return tide(0.85)                      # Augen
            return ramp(SKIN, 0.08)                    # tiefe Augenhöhle
        if y == 3 and 0.8 < d < 3.8:
            return ramp(SKIN, 0.18)                    # Schatten unter der Braue
        if y >= 6 and d > 2.6:
            v -= 0.18                                  # eingefallene Wangen
        if y <= 2:
            e = crack(x, y, 72, 3.5)
            if e < 0.4:
                v -= 0.22
    if face in ('left', 'right') and y >= 5 and 1 <= x <= w - 3:
        v -= 0.14                                      # hohle Schläfe / Wange
    return ramp(SKIN, v)


def brow(face, x, y, w, h):
    return ramp(SKIN, 0.62 + FACE_LIGHT[face] * 1.3 + grain(x, y, 73) + (0.06 if face == 'top' else 0.0))


def lip_paint(face, x, y, w, h):
    """Oberkiefer mit rissiger, weit geöffneter Lippe."""
    v = 0.5 + lit(face, y, h, 0.1) + grain(x, y, 74)
    if face == 'front':
        if y == h - 1:
            v = 0.3 + (0.12 if x % 2 == 0 else 0.0)    # spröde Lippe: Schollen
        if abs(x - (w - 1) / 2.0) > 2.2:
            v -= 0.14
    if face == 'bottom':
        return ramp(MOUTH, 0.5)
    return ramp(SKIN, v)


def jaw_paint(face, x, y, w, h):
    v = 0.5 + lit(face, y, h, 0.1) + grain(x, y, 75)
    if face == 'top':
        return ramp(MOUTH, 0.35 + (0.25 if y < 1 else 0.0))   # Zunge, ausgetrocknet
    if face == 'front' and y == 0:
        v = 0.32 + (0.12 if x % 2 == 1 else 0.0)              # Unterlippe, gesprungen
    return ramp(SKIN, v)


def mouth_paint(face, x, y, w, h):
    return ramp(MOUTH, 0.2 + (0.2 if face == 'bottom' else 0.0))


def hair_paint(face, x, y, w, h):
    v = 0.48 + FACE_LIGHT[face] + math.sin(x * 2.3 + y * 0.3) * 0.08 - y / max(h - 1, 1) * 0.2 + grain(x, y, 81)
    if face == 'top':
        v += 0.08
    return ramp(HAIR, v)


def strand(face, x, y, w, h):
    v = 0.5 + FACE_LIGHT[face] + math.sin(x * 2.6) * 0.08 - y / max(h - 1, 1) * 0.22 + grain(x, y, 82)
    if face in SIDES and y >= h - 2 and (x % 2 == 1):
        return None                                    # strähnige Spitzen
    return ramp(HAIR, v)


def gold(base=0.55, seed=0, edge=0.16):
    """Angelaufenes Gold (leuchtet nie): helle Grate, dunkle Mitte, Flecken in den Vertiefungen."""
    def paint(face, x, y, w, h):
        v = base + lit(face, y, h, 0.14) + grain(x, y, seed)
        if x == 0 or y == 0 or x == w - 1 or y == h - 1:
            v += edge
        if speckle(x * 0.5, y * 0.5, seed + 3) > 0.72:
            v -= 0.18                                  # Anlauf
        return ramp(TARNISH, v)
    return paint


def cup_inside(face, x, y, w, h):
    """Der Kelchboden: sichtbar leer, trocken, gesprungen."""
    v = 0.18 + grain(x, y, 91)
    if crack(x, y, 92, 2.5) < 0.4:
        v -= 0.1
    return ramp(TARNISH, v)


def robe(base=0.5, seed=0, wet=8, hem=True):
    """Verblichener Königspurpur in großen Falten; die untersten `wet` Reihen nass, dunkel, mit Algen; schmaler,
    nasser Saum in Wasser-Türkis (Leuchtmaske) am ausgefransten Rand."""
    depth = 4

    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + math.sin(x * 0.9 + seed) * 0.08 + (0.5 - y / max(h - 1, 1)) * 0.3
        v += grain(x, y, seed)
        if face in SIDES:
            cut = int(speckle(x, 0, seed + 1) * depth)
            if y >= h - cut:
                return None                            # ausgefranst
            wet_row = h - wet
            if y >= wet_row:
                k = (y - wet_row) / max(wet - 1, 1)
                v -= 0.18 + 0.14 * k
                if speckle(x * 0.6, y * 0.6, seed + 5) > 0.6:
                    return ramp(WEED, 0.35 + FACE_LIGHT[face] + 0.2 * (1 - k))
                if hem and y == h - cut - 1:
                    return tide(0.55, 190)             # nasser Saum
            elif y == wet_row - 1:
                v += 0.05                              # Wasserlinie
        return ramp(PURPLE, v)
    return paint


def mantle_paint(base=0.5, seed=0):
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + math.sin(x * 0.8 + seed) * 0.07 + (0.5 - y / max(h - 1, 1)) * 0.2 + grain(x, y, seed)
        if face == 'top':
            v += 0.06
        return ramp(PURPLE, v)
    return tattered(paint, 4, seed + 7)


def wood(base=0.5, seed=0, knots=True):
    """Verdorrtes Feigenholz: grau-braun, Maserung längs, ein paar dunkle Astlöcher."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] * 1.2 + math.sin(x * 2.1 + seed) * 0.05 + grain(x, y, seed)
        if face in SIDES:
            v += math.sin(y * 0.7 + x * 1.3 + seed) * 0.04
            if knots and h > 8 and (y * 7 + seed * 3) % 11 == 0 and 0 < x < w - 1:
                v -= 0.22
        return ramp(WOOD, v)
    return paint


def leaf(face, x, y, w, h):
    return ramp(LEAF, 0.5 + FACE_LIGHT[face] * 1.2 + grain(x, y, 95) - y * 0.05)


def fig(face, x, y, w, h):
    """Die pralle Frucht: sattes Dunkelrot, unten dunkler, ein heller Glanzpunkt oben vorn (leuchtet nicht)."""
    v = 0.6 + FACE_LIGHT[face] * 1.2 + (0.5 - y / max(h - 1, 1)) * 0.3 + grain(x, y, 96)
    if face == 'front' and 1 <= x <= 2 and 1 <= y <= 2:
        return mix(ramp(FIG, 0.95), hexc('#f6d2cc'), 0.55 if (x, y) == (1, 1) else 0.2)
    if face in ('left', 'right') and x == 1 and y == 1:
        v += 0.18
    return ramp(FIG, v)


def stalk(face, x, y, w, h):
    return ramp(WOOD, 0.45 + FACE_LIGHT[face])


def rope(face, x, y, w, h):
    v = 0.5 + FACE_LIGHT[face] + (0.12 if (x + y) % 3 == 0 else -0.04) + grain(x, y, 97)
    return ramp(ROPE, v)


def bronze_green(base=0.5, seed=0):
    """Dreizack: alte Bronze mit Grünspan."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] * 1.2 + grain(x, y, seed)
        if face in SIDES and x == 0:
            v += 0.12
        if speckle(x * 0.7, y * 0.35, seed + 4) > 0.38:
            return ramp(VERDIGRIS, v - 0.04)
        return ramp(TARNISH, v - 0.16)
    return paint


def prong(length):
    """Zinke: Grünspan-Bronze, die letzten drei Texel zur Spitze hin Wasser-Türkis (Leuchtmaske)."""
    body = bronze_green(0.5, 120 + length)

    def paint(face, x, y, w, h):
        if face == 'bottom' or (face in SIDES and y >= h - 3):
            k = (y - (h - 3)) / 2.0 if face in SIDES else 1.0
            return tide(0.55 + 0.35 * k)
        return body(face, x, y, w, h)
    return paint


def wrap(face, x, y, w, h):
    """Griffwicklung aus Leder am Dreizack."""
    return ramp(ROPE, 0.32 + FACE_LIGHT[face] + (0.1 if y % 2 == 0 else 0.0))


# =====================================================================================================================
# Ruhepose (Grundhaltung der Knochen) und Posen
# =====================================================================================================================
# Winkel hier in Grad (pitch, yaw, roll[, dx, dy, dz]); POSES am Ende in Radiant. Positives pitch kippt das obere Ende
# eines Knochens nach vorn (-z) bzw. schwingt ein hängendes Glied nach hinten; Arme heben sich mit negativem pitch.

PARENT = {}            # Knochen -> Elternknochen; wird in tantalus() gefüllt


def _p(*v):
    return tuple(float(a) for a in v) + (0.0,) * (6 - len(v))


IDLE = {
    'body': _p(6),
    'chest': _p(20),
    'head': _p(-18),
    'jaw': _p(14),
    'crown': _p(0, 8, -22),
    'hair_r': _p(-8, 0, 6), 'hair_r2': _p(-4), 'hair_l': _p(-8, 0, -6), 'hair_l2': _p(-4),
    'mantle': _p(4),
    'branch': _p(-16, 0, 15),
    'branch_1': _p(64, 38, 0),
    'branch_2': _p(70, 0, 0),
    'arm_r': _p(-22, 0, 6), 'fore_r': _p(-42), 'hand_r': _p(0),
    'arm_l': _p(-44, 0, -6), 'fore_l': _p(-34), 'hand_l': _p(0),
    'leg_r': _p(-16, 0, 3), 'shin_r': _p(28), 'leg_l': _p(-12, 0, -3), 'shin_l': _p(24),
    'robe_f': _p(-14), 'robe_b': _p(6), 'robe_r': _p(-4, 0, 6), 'robe_l': _p(-4, 0, -6),
}
ORDER = list(IDLE) + ['foot_r', 'foot_l', 'trident', 'goblet', 'fruit']


PARTS = {}             # Knochenname -> Part; wird in tantalus() gefüllt


def _mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _rot(p, y, r):
    """Wie ModelPart: Z * Y * X, Winkel in Grad."""
    cx, sx = math.cos(math.radians(p)), math.sin(math.radians(p))
    cy, sy = math.cos(math.radians(y)), math.sin(math.radians(y))
    cz, sz = math.cos(math.radians(r)), math.sin(math.radians(r))
    rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    return _mul(rz, _mul(ry, rx))


def _app(m, v):
    return tuple(sum(m[i][j] * v[j] for j in range(3)) for i in range(3))


def _euler(m):
    """Zerlegt eine Drehmatrix in (pitch, yaw, roll) in Grad (Z * Y * X)."""
    yaw = math.asin(max(-1.0, min(1.0, -m[2][0])))
    pitch = math.atan2(m[2][1], m[2][2])
    roll = math.atan2(m[1][0], m[0][0])
    return math.degrees(pitch), math.degrees(yaw), math.degrees(roll)


def _local(pose, name):
    v = pose.get(name)
    if v is None:
        part = PARTS[name]
        return tuple(part.rot) + (0.0, 0.0, 0.0)
    return v


def _fk(pose, upto=None):
    """Welt-Drehung und -Lage jedes Knochens (Vorwärtskinematik wie im Spiel)."""
    out = {}

    def walk(part, prot, ppos):
        v = _local(pose, part.name)
        piv = (part.pivot[0] + v[3], part.pivot[1] + v[4], part.pivot[2] + v[5])
        o = _app(prot, piv)
        pos = (ppos[0] + o[0], ppos[1] + o[1], ppos[2] + o[2])
        rot = _mul(prot, _rot(*v[:3]))
        out[part.name] = (rot, pos)
        for ch in part.children:
            walk(ch, rot, pos)
    walk(PARTS['body'], [[1, 0, 0], [0, 1, 0], [0, 0, 1]], (0, 0, 0))
    return out


def _world_pitch(pose, bone):
    total = 0.0
    while bone:
        total += pose[bone][0]
        bone = PARENT.get(bone)
    return total


def _aim(pose, bone, world):
    """Nur pitch: der Knochen steht in der Seitenansicht unter `world` Grad (Füße)."""
    old = pose.get(bone, _p())
    pose[bone] = (world - _world_pitch(pose, PARENT[bone]), 0.0, 0.0) + tuple(old[3:6])


def _aim_world(pose, bone, p, y=0.0, r=0.0):
    """Setzt die lokale Drehung so, dass der Knochen in Weltlage Rz(r)·Ry(y)·Rx(p) steht."""
    old = pose.get(bone, _p())
    prot = _fk(pose)[PARENT[bone]][0]
    pt = [[prot[j][i] for j in range(3)] for i in range(3)]
    pose[bone] = _euler(_mul(pt, _rot(p, y, r))) + tuple(old[3:6])


GROUNDED = ('leg_r', 'shin_r', 'foot_r', 'leg_l', 'shin_l', 'foot_l')


def _ground(pose):
    """Hebt/senkt das Becken so, dass der tiefste Punkt der Beine auf dem Boden (y = 24) steht."""
    fk = _fk(pose)
    low = -1e9
    for name in GROUNDED:
        rot, pos = fk[name]
        for c in PARTS[name].cubes:
            for dx in (0, c.w):
                for dy in (0, c.h):
                    for dz in (0, c.d):
                        w = _app(rot, (c.x + dx, c.y + dy, c.z + dz))
                        low = max(low, w[1] + pos[1])
    b = list(pose['body'])
    b[4] += 24.0 - low
    pose['body'] = tuple(b)


def _unwrap(pose, ref):
    """Wählt für pitch und roll den Wert (± 360°), der der Referenzpose am nächsten liegt (kein Drehen im Kreis)."""
    for k, v in pose.items():
        if v is None or ref.get(k) is None:
            continue
        v = list(v)
        for i in (0, 2):
            while v[i] - ref[k][i] > 180:
                v[i] -= 360
            while v[i] - ref[k][i] < -180:
                v[i] += 360
        pose[k] = tuple(v)


def pose(trident=(172.0,), goblet=(0.0,), feet=0.0, ref=None, ground=True, **bones):
    """Eine Pose = Ruhepose + Änderungen. trident/goblet: Weltlage (pitch, yaw, roll) in Grad. Zinkenrichtung:
    pitch 0 = nach unten, 180 = nach oben, -90 = nach vorn; Kelch: 0 = aufrecht, None = weg. Frucht hängt lotrecht,
    Füße stehen flach, das Becken wird auf den Boden gesetzt."""
    p = dict(IDLE)
    for k, v in bones.items():
        if len(v) == 1:                                 # nur pitch: Seitenlage der Ruhepose behalten
            v = (v[0], IDLE[k][1], IDLE[k][2])
        p[k] = _p(*v)
    for s in 'rl':
        p[f'foot_{s}'] = _p()
        _aim(p, f'foot_{s}', feet)
    if ground:
        _ground(p)
    _aim_world(p, 'fruit', 0.0)
    if goblet is None:
        p['goblet'] = None
    else:
        _aim_world(p, 'goblet', *goblet)
    _aim_world(p, 'trident', *trident)
    if ref is not None:
        _unwrap(p, ref)
    return p


def _build_poses():
    P = {}
    P['idle'] = pose(trident=(172, 0, 4))
    # Schnappen nach der Frucht: Hals und Kopf schießen vor und hoch, der Mund reißt auf; die Frucht ist schon weg.
    P['idle_snap'] = pose(
        trident=(172, 0, 4),
        chest=(32, 0, 0), head=(-44, 0, 0, 0, -3, -6), jaw=(46,),
        branch=(-22,), branch_1=(42,), branch_2=(80,),
        arm_l=(-52, 0, -6), fore_l=(-40,),
        hair_r=(14, 0, 6), hair_l=(14, 0, -6),
    )
    # Dreizackstoß: Dreizack unter die rechte Achsel geklemmt, Zinken waagerecht nach vorn, die Linke mit dem Kelch
    # als Gegengewicht nach hinten; Ausfallschritt mit links.
    P['thrust_windup'] = pose(
        trident=(-90, 0, 0),
        body=(10, 22, 0, 0, 0, 3), chest=(18, 14, 0), head=(-26, -30, 0), jaw=(10,),
        arm_r=(34, 0, 14), fore_r=(-104,),
        arm_l=(56, 0, -22), fore_l=(-26,),
        leg_r=(30, -20, 4), shin_r=(30,), leg_l=(-46, -20, -4), shin_l=(46,),
        robe_f=(-34,), robe_b=(30,), robe_r=(-10, 0, 8), robe_l=(-20, 0, -8),
        branch=(-30,), branch_1=(52,), branch_2=(110,),
    )
    P['thrust_hit'] = pose(
        trident=(-90, 0, 0),
        body=(18, -14, 0, 0, 0, -5), chest=(26, -18, 0), head=(-38, 18, 0), jaw=(30,),
        arm_r=(-66, 0, 6), fore_r=(-18,),
        arm_l=(72, 0, -26), fore_l=(-16,),
        leg_r=(40, 14, 4), shin_r=(26,), leg_l=(-58, 14, -4), shin_l=(52,),
        robe_f=(-46,), robe_b=(40,), robe_r=(-12, 0, 8), robe_l=(-26, 0, -8),
        branch=(-38,), branch_1=(62,), branch_2=(120,),
        hair_r=(16, 0, 8), hair_l=(16, 0, -8),
    )
    P['thrust_hit_3'] = pose(
        trident=(-86, 0, 0),
        body=(26, -18, 0, 0, 0, -8), chest=(38, -20, 0), head=(-52, 20, 0), jaw=(36,),
        arm_r=(-70, 0, 6), fore_r=(-14,),
        arm_l=(80, 0, -28), fore_l=(-10,),
        leg_r=(48, 14, 4), shin_r=(24,), leg_l=(-70, 14, -4), shin_l=(70,),
        robe_f=(-56,), robe_b=(46,), robe_r=(-14, 0, 8), robe_l=(-30, 0, -8),
        branch=(-48,), branch_1=(70,), branch_2=(128,),
        hair_r=(20, 0, 8), hair_l=(20, 0, -8),
    )
    # Nach dem dritten Stoß: hängt weit vorgebeugt über dem Dreizack, der mit den Zinken im Boden steckt (offen).
    P['thrust_recover'] = pose(
        trident=(-24, 0, 0), goblet=(40, 0, 0),
        body=(30, -10, 0, 0, 0, -6), chest=(44, -6, 0), head=(-14, 10, 8), jaw=(30,),
        arm_r=(-50, 0, 4), fore_r=(-30,),
        arm_l=(-12, 0, -4), fore_l=(-6,),
        leg_r=(34, 8, 4), shin_r=(34,), leg_l=(-52, 8, -4), shin_l=(64,),
        robe_f=(-44,), robe_b=(36,), robe_r=(-10, 0, 8), robe_l=(-24, 0, -8),
        branch=(-50,), branch_1=(80,), branch_2=(140,),
        hair_r=(-30, 0, 4), hair_l=(-30, 0, -4),
    )
    # Geysire: Dreizack senkrecht nach oben gereckt, Kopf im Nacken.
    P['geyser_raise'] = pose(
        trident=(180, 0, 0),
        body=(-2,), chest=(-6,), head=(-34,), jaw=(34,),
        arm_r=(-168, 0, -4), fore_r=(-8,),
        arm_l=(-30, 0, -44), fore_l=(-30,),
        leg_r=(-4, 0, 8), shin_r=(8,), leg_l=(-4, 0, -8), shin_l=(8,),
        robe_f=(-6,), robe_b=(6,), robe_r=(-2, 0, 10), robe_l=(-2, 0, -10),
        branch=(-10,), branch_1=(48,), branch_2=(104,),
        hair_r=(10, 0, 4), hair_l=(10, 0, -4),
    )
    # Flutwelle: beidhändig hoch über den Kopf und weit zurück, Zinken nach hinten oben, Rücken durchgebogen ...
    P['wave_windup'] = pose(
        trident=(108, 0, 0), goblet=(-80, 0, 0),
        body=(-10, 0, 0, 0, 0, 3), chest=(-24,), head=(-6,), jaw=(30,),
        arm_r=(-150, 0, 18), fore_r=(-36,),
        arm_l=(-150, 0, -18), fore_l=(-36,),
        leg_r=(12, 0, 8), shin_r=(14,), leg_l=(-30, 0, -8), shin_l=(22,),
        robe_f=(-18,), robe_b=(12,), robe_r=(-4, 0, 8), robe_l=(-12, 0, -8),
        branch=(-2,), branch_1=(44,), branch_2=(100,),
        hair_r=(24, 0, 4), hair_l=(24, 0, -4),
    )
    # ... dann im Bogen über den Kopf senkrecht in den Boden; er kniet auf dem rechten Knie.
    P['wave_hit'] = pose(
        trident=(0, 0, 0), goblet=(-30, 0, 0),
        body=(20, 0, 0, 0, 0, -4), chest=(40,), head=(-34,), jaw=(36,),
        arm_r=(-62, 0, 10), fore_r=(-16,),
        arm_l=(-62, 0, -10), fore_l=(-16,),
        leg_r=(10, 0, 4), shin_r=(80,), leg_l=(-82, 0, -4), shin_l=(86,),
        robe_f=(-60,), robe_b=(40,), robe_r=(-20, 0, 10), robe_l=(-40, 0, -10),
        branch=(-46,), branch_1=(72,), branch_2=(132,),
        hair_r=(-20, 0, 4), hair_l=(-20, 0, -4),
    )
    # Der Zinkenschlag läuft vorn über den Kopf: Brust und Arme tragen den Dreizack dabei um ~220° herum.
    # Der Durst: auf beiden Knien, der Dreizack liegt; schöpft mit dem Kelch vom Boden ...
    P['drink_scoop'] = pose(
        trident=(-92, -24, 0), goblet=(30, 0, 0), feet=70,
        body=(24, 0, 0, 0, 0, 2), chest=(34,), head=(-40,), jaw=(24,),
        arm_r=(-4, -17, 28), fore_r=(-76,),
        arm_l=(-52, 16, -31), fore_l=(-46,),
        leg_r=(-30, 0, 5), shin_r=(110,), leg_l=(-30, 0, -5), shin_l=(110,),
        robe_f=(-50,), robe_b=(30,), robe_r=(-30, 0, 10), robe_l=(-30, 0, -10),
        branch=(-50,), branch_1=(96,), branch_2=(150,),
        hair_r=(-30, 0, 4), hair_l=(-30, 0, -4),
    )
    # ... und führt ihn zum Mund: Kopf im Nacken, der Kelch kippt (verletzlicher Moment, Brust offen).
    P['drink_sip'] = pose(
        trident=(-92, -24, 0), goblet=(-100, 0, 0), feet=70,
        body=(0, 0, 0, 0, 0, 1), chest=(-4,), head=(-40,), jaw=(40,),
        arm_r=(-10, 0, 22), fore_r=(-26,),
        arm_l=(-102, 24, -12), fore_l=(-108,),
        leg_r=(0, 0, 5), shin_r=(90,), leg_l=(0, 0, -5), shin_l=(90,),
        robe_f=(-30,), robe_b=(60,), robe_r=(-10, 0, 14), robe_l=(-10, 0, -14),
        branch=(-22,), branch_1=(48,), branch_2=(92,),
        hair_r=(26, 0, 4), hair_l=(26, 0, -4),
    )
    # Kelch aus der Hand geschlagen: beide Hände an der Kehle, Oberkörper weit zurück, Mund weit offen.
    P['parched'] = pose(
        trident=(24, 0, 28), goblet=None,
        body=(-12, 0, 0, 0, 0, 3), chest=(-24,), head=(-30,), jaw=(52,),
        arm_r=(-64, -6, 28), fore_r=(-140,), hand_r=(-20,),
        arm_l=(-64, 6, -28), fore_l=(-140,), hand_l=(-20,),
        leg_r=(-26, 0, 6), shin_r=(30,), leg_l=(14, 0, -6), shin_l=(18,),
        robe_f=(-26,), robe_b=(2,), robe_r=(-8, 0, 10), robe_l=(0, 0, -10),
        branch=(4,), branch_1=(56,), branch_2=(110,),
        hair_r=(30, 0, 8), hair_l=(30, 0, -8),
    )
    # Wut: Dreizack quer vor die Brust gerissen, schreit die Frucht an.
    P['rage'] = pose(
        trident=(0, 0, -90),
        body=(-4,), chest=(-10,), head=(-42,), jaw=(54,),
        arm_r=(-9, -8, 15), fore_r=(-114,),
        arm_l=(-9, 8, -15), fore_l=(-114,),
        leg_r=(-8, 0, 10), shin_r=(14,), leg_l=(-8, 0, -10), shin_l=(14,),
        robe_f=(-10,), robe_b=(8,), robe_r=(-2, 0, 12), robe_l=(-2, 0, -12),
        branch=(-14,), branch_1=(50,), branch_2=(102,),
        hair_r=(16, 0, 6), hair_l=(16, 0, -6),
    )
    return P


# =====================================================================================================================
# Geometrie
# =====================================================================================================================

def _deg(name):
    v = IDLE_FULL.get(name)
    return (v[0], v[1], v[2]) if v else (0.0, 0.0, 0.0)


IDLE_FULL = {}


def tantalus():
    """Baut das Modell. Die Knochen stehen in der Ruhepose `idle`."""
    def R(name):
        return _deg(name)

    # ---------------------------------------------------------------- Kopf
    crown_parts = []
    for i in range(8):
        a = i * 45.0
        tine = []
        if i in (0, 1, 7, 2, 6):
            tall = 5 if i == 0 else 4
            tine = [Cube(-1, -2 - tall, -6.5, 2, tall, 2, gold(0.62, 140 + i))]
        crown_parts.append(Part(f'crown_{i}', (0, 0, 0), (0, -a, 0), cubes=[
            Cube(-2.5, -2, -6.5, 5, 2, 2, gold(0.55, 130 + i)),
        ] + tine))
    crown = Part('crown', (-1.4, -21.5, -4.5), R('crown'), children=crown_parts)

    hair_r = Part('hair_r', (-4.2, -20, -3), R('hair_r'), cubes=[Cube(-1, 0, -1.5, 2, 8, 3, strand)], children=[
        Part('hair_r2', (0, 7.5, 0), R('hair_r2'), cubes=[Cube(-1, 0, -1, 2, 7, 2, strand)]),
    ])
    hair_l = Part('hair_l', (4.2, -20, -3), R('hair_l'), cubes=[Cube(-1, 0, -1.5, 2, 8, 3, strand)], children=[
        Part('hair_l2', (0, 7.5, 0), R('hair_l2'), cubes=[Cube(-1, 0, -1, 2, 7, 2, strand)]),
    ])
    jaw = Part('jaw', (0, -13.5, -3), R('jaw'), cubes=[
        Cube(-3, 0, -5.5, 6, 2, 6, jaw_paint),                    # Unterkiefer
        Cube(-2, 1, -6, 4, 2, 3, skin(0.5, 76, 0.4, 3.0)),        # spitzes Kinn
    ])
    head = Part('head', (0, -25, -1.5), R('head'), cubes=[
        Cube(-2, -7, -2.5, 4, 8, 4, skin(0.46, 70, 0.3, 3.0)),    # Hals, lang und dürr
        Cube(-2, -12, -4.5, 4, 6, 4, skin(0.5, 69, 0.3, 3.0)),    # Hals, nach vorn gereckt
        Cube(-4.5, -23, -9, 9, 9, 9, head_paint),                 # Schädel
        Cube(-4.5, -20, -10, 9, 2, 1, brow),                      # Brauenwulst
        Cube(-1, -18, -10.5, 2, 4, 1, brow),                      # Nase, scharf
        Cube(-3.5, -14.5, -9, 7, 2, 7, lip_paint),                # Oberkiefer, Lippe
        Cube(-3, -14, -8.5, 6, 3, 5, mouth_paint),                # Mundhöhle
        Cube(-5.5, -19, -5, 1, 3, 2, skin(0.5, 79)),              # Ohren
        Cube(4.5, -19, -5, 1, 3, 2, skin(0.5, 79)),
        Cube(-5, -24, -6, 10, 10, 6, hair_paint),                 # nasses Haar, Hinterkopf
    ], children=[jaw, crown, hair_r, hair_l])

    # ---------------------------------------------------------------- Arme, Hände, Dreizack, Kelch
    trident = Part('trident', (1.5, 23.5, 0), R('trident'), cubes=[
        Cube(-1.5, -24, -1.5, 3, 3, 3, bronze_green(0.42, 101)),  # Schaftende
        Cube(-1, -22, -1, 2, 54, 2, bronze_green(0.5, 102)),      # Schaft
        Cube(-1.5, -4, -1.5, 3, 7, 3, wrap),                      # Griff
        Cube(-1.5, 30, -1.5, 3, 3, 3, bronze_green(0.56, 103)),   # Tülle
        Cube(-6, 32, -1, 12, 2, 2, bronze_green(0.6, 104)),       # Querjoch
        Cube(-1, 34, -1, 2, 14, 2, prong(14)),                    # mittlere Zinke (14)
        Cube(-6, 34, -1, 2, 11, 2, prong(11)),                    # äußere Zinken (11)
        Cube(4, 34, -1, 2, 11, 2, prong(12)),
        Cube(-7, 33, -1, 2, 3, 2, bronze_green(0.6, 105)),        # Widerhaken außen
        Cube(5, 33, -1, 2, 3, 2, bronze_green(0.6, 106)),
    ])
    goblet = Part('goblet', (-1.5, 23.5, -0.5), R('goblet'), cubes=[
        Cube(-2, 4, -2, 4, 1, 4, gold(0.5, 111)),                 # Fuß
        Cube(-1, 1, -1, 2, 3, 2, gold(0.56, 112)),                # Stiel
        Cube(-1.5, -0.5, -1.5, 3, 2, 3, gold(0.6, 113)),          # Nodus
        Cube(-2, -1, -2, 4, 1, 4, cup_inside),                    # Boden der Schale: leer
        Cube(-3, -3, -3, 6, 2, 1, gold(0.62, 114)),               # Schalenwand (6 im Durchmesser)
        Cube(-3, -3, 2, 6, 2, 1, gold(0.58, 115)),
        Cube(-3, -3, -2, 1, 2, 4, gold(0.6, 116)),
        Cube(2, -3, -2, 1, 2, 4, gold(0.6, 117)),
    ])

    def hand(s, side):
        """Lange, knochige Hand: Handteller 6, drei Finger + Daumen, je 2 dick, gekrümmt (greift Schaft/Kelch)."""
        inner = -side                                  # zur Körpermitte
        curl = 0.5 if inner > 0 else -2.5
        cubes = [Cube(-1.5, 0, -3, 3, 5, 6, skin(0.5, 150 + side, 0.6, 3.0))]
        for i, z in enumerate((-3, -1, 1)):
            cubes.append(Cube(-1.5, 4.5, z, 2, 5, 2, skin(0.52, 152 + i, 0.5, 2.5)))
            cubes.append(Cube(curl, 8, z, 2, 2, 2, skin(0.48, 155 + i)))
        cubes.append(Cube(curl, 1, -5, 2, 4, 2, skin(0.5, 158)))  # Daumen
        return Part(f'hand_{s}', (0, 18, 0), R(f'hand_{s}'), cubes=cubes)

    def arm(s, side):
        held = trident if s == 'r' else goblet
        return Part(f'arm_{s}', (side * 9.5, -22.5, 0.5), R(f'arm_{s}'), cubes=[
            Cube(-2.5, -2.5, -2.5, 5, 5, 5, skin(0.56, 160 + side, 0.12, 7.0)),   # Schulterknoten
            Cube(-2, 0, -2, 4, 18, 4, skin(0.52, 162 + side, 0.1, 7.0)),        # Oberarm 4
            Cube(-2.5, 16.5, -2.5, 5, 4, 5, skin(0.5, 164 + side, 0.15, 6.0)),    # Ellbogen als Knoten
        ], children=[
            Part(f'fore_{s}', (0, 18.5, 0), R(f'fore_{s}'), cubes=[
                Cube(-2, 0, -2, 4, 17, 4, skin(0.5, 166 + side, 0.14, 7.0)),     # Unterarm 4
                Cube(-2.5, 15.5, -2.5, 5, 3, 5, skin(0.48, 168 + side, 0.3, 4.0)),  # Handgelenk als Knoten
            ], children=[hand(s, side), held]),
        ])

    # ---------------------------------------------------------------- Ast mit Frucht (Erkennungsmerkmal)
    fruit = Part('fruit', (0, -10, 0), R('fruit'), cubes=[
        Cube(-0.5, 0, -0.5, 1, 4, 1, stalk),                       # Stiel (1 dick: erlaubte Ausnahme)
        Cube(-1.5, 3.5, -1.5, 3, 2, 3, fig),                       # Fruchthals
        Cube(-2.5, 5, -2.5, 5, 6, 5, fig),                         # die pralle Feige 5 × 6 × 5
        Cube(-2, 10.5, -2, 4, 1, 4, fig),                          # runder Boden
        Cube(-1, 3, -2.5, 2, 1, 3, leaf),                          # welkes Blatt am Stiel
    ])
    branch_2 = Part('branch_2', (0, -24, 0), R('branch_2'), cubes=[
        Cube(-1, -10, -1, 2, 10, 2, wood(0.5, 172, knots=False)),
        Cube(-1.5, -1.5, -1.5, 3, 3, 3, wood(0.44, 173)),          # Knoten
        Cube(1, -6, -1, 3, 1, 2, leaf),                            # welkes Blatt
    ], children=[fruit])
    branch_1 = Part('branch_1', (0, -30, 0), R('branch_1'), cubes=[
        Cube(-1, -24, -1, 2, 24, 2, wood(0.52, 174)),
        Cube(-1.5, -2, -1.5, 3, 4, 3, wood(0.46, 175)),            # Knoten am Knick
        Cube(-3, -15, -1, 2, 1, 3, leaf),                          # welkes Blatt
        Cube(1, -9, -1, 2, 2, 2, wood(0.44, 176)),                 # Aststummel
    ], children=[branch_2])
    branch = Part('branch', (5, -20, 6.5), R('branch'), cubes=[
        Cube(-1.5, -30, -1.5, 3, 34, 3, wood(0.48, 177)),          # Stamm, senkrecht 30 hoch
        Cube(-2, -2, -2, 4, 2, 4, rope),                           # Bindung
        Cube(-2, -13, -2, 4, 2, 4, rope),
        Cube(-2.5, 3, -2, 5, 2, 4, wood(0.4, 178)),                # Gabel unten im Gurt
        Cube(-3, -22, -1, 2, 2, 2, wood(0.44, 179)),               # Aststummel
    ], children=[branch_1])

    # ---------------------------------------------------------------- Rumpf
    def ribs(s, side):
        """Vier Rippenspangen je Seite (2 dick), vorn und seitlich; zur Mitte hin ansteigend (Rippenbogen)."""
        cubes = []
        for i, y in enumerate((-23, -19.5, -16, -12.5)):
            span = (5, 5, 4, 3)[i]
            x0 = -span if side < 0 else 0
            cubes.append(Cube(x0, y, -5.5, span, 2, 2, rib))                         # vorn
            fx = span + 0.5 - (1.5 if i == 3 else 0)
            cubes.append(Cube(-fx - 1 if side < 0 else fx - 1, y, -4.5, 2, 2, 6, rib))  # Flanke
        return Part(f'ribs_{s}', (side * 1, 0, 0), (0, 0, 14 * side), cubes=cubes)

    mantle = Part('mantle', (0, -26.5, 0.5), R('mantle'), cubes=[
        Cube(-9, -1, -2, 18, 3, 8, mantle_paint(0.56, 180)),       # Schultertuch, nach hinten gerutscht
        Cube(-7, 1, 4.5, 14, 14, 2, mantle_paint(0.48, 181)),       # hängt über den Rücken
        Cube(-10.5, 0, -2.5, 3, 7, 7, mantle_paint(0.5, 182)),      # über der rechten Schulter
        Cube(7.5, 0.5, -1.5, 3, 4, 5, mantle_paint(0.5, 183)),      # links nur ein Zipfel
    ])
    chest = Part('chest', (0, -3, 0.5), R('chest'), cubes=[
        Cube(-4.5, -9.5, -3.5, 9, 10, 7, belly),                   # eingefallener Bauch: 9 breit, 7 tief
        Cube(-6, -24, -4.5, 12, 15, 9, ribcage),                   # Brustkorb
        Cube(-9, -26, -3.5, 18, 3, 7, skin(0.58, 185, 0.3)),       # Schlüsselbeine / Schultern 20
        Cube(-1, -24, -5, 2, 13, 1, skin(0.4, 189)),               # Brustbein
        Cube(-5, -18, 4.5, 3, 6, 1, skin(0.5, 186)),               # Schulterblätter
        Cube(2, -18, 4.5, 3, 6, 1, skin(0.5, 187)),
        Cube(-1, -12, 4.5, 2, 12, 1, skin(0.46, 188)),             # Wirbelsäule
        Cube(-8.5, -20, -1, 17, 2, 7, rope),                       # Gurt um den Brustkorb (hält den Ast)
        Cube(-5, -8, 3, 10, 2, 2, rope),                           # Gurt unten am Rücken
    ], children=[ribs('r', -1), ribs('l', 1), head, arm('r', -1), arm('l', 1), mantle, branch])

    # ---------------------------------------------------------------- Becken, Beine, Gewand
    def leg(s, side):
        return Part(f'leg_{s}', (side * 3, 1.5, 0), R(f'leg_{s}'), cubes=[
            Cube(-2.5, 0, -2.5, 5, 19, 5, skin(0.5, 190 + side, 0.1, 7.0)),   # Oberschenkel 5
            Cube(-3, 16.5, -3.5, 6, 5, 6, skin(0.52, 192 + side, 0.15, 6.0)),   # Knie als Knoten 6
        ], children=[
            Part(f'shin_{s}', (0, 19, -0.5), R(f'shin_{s}'), cubes=[
                Cube(-2, 0, -2, 4, 18, 4, wet_skin(0.5, 194 + side)),     # Schienbein 4, nass
            ], children=[
                Part(f'foot_{s}', (0, 18, 0), R(f'foot_{s}'), cubes=[
                    Cube(-2.5, -1, -2.5, 5, 3, 5, wet_skin(0.46, 196 + side)),  # Ferse
                    Cube(-2.5, 0, -8, 5, 2, 6, wet_skin(0.5, 197 + side)),      # langer nackter Fuß
                    Cube(-2, 0.5, -10, 4, 1.5, 2, wet_skin(0.46, 198 + side)),  # Zehen
                ]),
            ]),
        ])

    def panel(name, pivot, cube):
        return Part(name, pivot, R(name), cubes=[cube])

    body = Part('body', (0, -16, 0), R('body'), cubes=[
        Cube(-5, -3, -3.5, 10, 6, 7, skin(0.42, 200)),             # Becken
        Cube(-5.5, -3.5, -4, 11, 3, 8, gold(0.52, 201)),           # Gürtel aus angelaufenem Gold
        Cube(-2, -4, -5, 4, 4, 1, gold(0.66, 202)),                # Schließe
    ], children=[
        chest,
        leg('r', -1), leg('l', 1),
        panel('robe_f', (0, -0.5, -4), Cube(-4.5, 0, -1, 9, 35, 2, robe(0.52, 210))),
        panel('robe_b', (0, -0.5, 3.5), Cube(-5.5, 0, -1, 11, 35, 2, robe(0.44, 212))),
        panel('robe_r', (-5, -0.5, 0), Cube(-1, 0, -3.5, 2, 34, 7, robe(0.48, 214))),
        panel('robe_l', (5, -0.5, 0), Cube(-1, 0, -3.5, 2, 34, 7, robe(0.48, 216))),
    ])

    PARENT.clear()
    PARTS.clear()
    for part in body.walk():
        PARTS[part.name] = part
        for ch in part.children:
            PARENT[ch.name] = part.name
    return Model('tantalus', 256, 256, [body])


# =====================================================================================================================
# Posen: erst nach dem Aufbau (braucht PARENT), dann die Grundhaltung nachziehen
# =====================================================================================================================

def _init():
    tantalus()                                          # füllt PARENT
    poses = _build_poses()
    IDLE_FULL.clear()
    IDLE_FULL.update(poses['idle'])
    return poses


_POSES_DEG = _init()
POSES = {
    name: {b: (None if v is None else tuple(math.radians(a) for a in v[:3]) + tuple(v[3:6])) for b, v in p.items()}
    for name, p in _POSES_DEG.items()
}
BONES = ORDER

# =====================================================================================================================
# Kotlin-Abgleich: Posentabelle in TantalusModel.kt
# =====================================================================================================================

KOTLIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'kotlin', 'dev', 'henny',
                      'hha', 'client', 'model', 'TantalusModel.kt')
BEGIN = '    // <poses> erzeugt von tantalus_model.py --sync-kotlin, nicht von Hand ändern'
END = '    // </poses>'


def _path(bone):
    chain = [bone]
    while chain[-1] in PARENT:
        chain.append(PARENT[chain[-1]])
    return '/'.join(reversed(chain))


def _num(v):
    s = f'{v:.4f}'.rstrip('0').rstrip('.')
    return (s if s not in ('-0', '') else '0') + 'f'


def kotlin_block():
    lines = [BEGIN]
    for i, b in enumerate(BONES):
        lines.append(f'    const val {b.upper()} = {i}')
    lines.append('    val PATHS = arrayOf(')
    for b in BONES:
        lines.append(f'        "{_path(b)}",')
    lines.append('    )')
    for name, p in POSES.items():
        vals = []
        for b in BONES:
            v = p.get(b)
            if v is None:
                vals.extend(['Float.NaN', '0f', '0f', '0f', '0f', '0f'])
            else:
                vals.extend(_num(a) for a in v)
        const = name.upper()
        lines.append(f'    val {const} = floatArrayOf(')
        for i in range(0, len(vals), 6):
            lines.append('        ' + ', '.join(vals[i:i + 6]) + ',  // ' + BONES[i // 6])
        lines.append('    )')
    lines.append(END)
    return '\n'.join(lines)


def sync_kotlin():
    with open(KOTLIN, encoding='utf8') as f:
        src = f.read()
    if BEGIN not in src or END not in src:
        print('TantalusModel.kt: Marken für die Posentabelle fehlen, nichts geschrieben')
        return
    a, b = src.index(BEGIN), src.index(END) + len(END)
    new = src[:a] + kotlin_block() + src[b:]
    if new != src:
        with open(KOTLIN, 'w', encoding='utf8', newline='\n') as f:
            f.write(new)
        print('TantalusModel.kt: Posentabelle aktualisiert')


def variants(model, albedo, glow):
    sync_kotlin()


if __name__ == '__main__' and '--sync-kotlin' in sys.argv:
    sync_kotlin()

# Für die Größenprüfung in preview.py: Diese Knochen (samt Kindern) zählen nicht zur Körperhöhe (Fuß bis Scheitel),
# weil sie Zubehör sind, das laut Brief darüber hinausragen darf (Hut, Waffe, Busch, Ast, Stachel, Ring).
HEIGHT_EXCLUDE = ('branch',)
