"""Der Greif (Hund des Zeus): Löwenleib, Adlerkopf mit Hakenschnabel, zwei große dreieckige Schwingen
(Brief tmp/overhaul2/models/olymp_gegner.md, Registry `griffin`). Modell, Textur, Leuchtmaske und POSES.

Erkennungsmerkmal: die Schwingen (je drei Glieder mit Federebene und Deckfedern-Leiste, im Sitzen als V aufgestellt)
und der goldene Hakenschnabel. Leuchtmaske nur in den zwei Augen (#dff1ff).

Koordinaten: 1/16 Block vor dem Renderer-Faktor 0,69, +y nach unten, -z vorn, Pfoten bei y = 24, Scheitel bei -18
(42 Einheiten → 1,8 Blöcke), 60 lang von der Schnabelspitze bis zur Quaste. Knochen (client/model/OlympMobModels.kt):

    body ─ chest ─ neck ── head ── beak_lower
    │        ├─ wing_l_0 ── wing_l_1 ── wing_l_2
    │        ├─ wing_r_0 ── wing_r_1 ── wing_r_2
    │        ├─ fore_l ── fore_l_low
    │        └─ fore_r ── fore_r_low
    ├─ hind_l ── hind_l_low
    ├─ hind_r ── hind_r_low
    └─ tail_0 ── tail_1 ── tail_2

    python tools/hha_assets/griffin_model.py --kotlin    # schreibt die Posentabelle nach OlympMobModels.kt
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from bosskit import Cube  # noqa: E402
from kurete_model import (  # noqa: E402
    FACE_LIGHT, SIDES, Rig, _add, _apply, _cross, _dot, _norm, _scale, _sub, kotlin_table, metal, rounded, surf,
    sync_kotlin,
)
from imp_model import SkylineModel  # noqa: E402  (dichter Atlas-Packer, nur gelesen)
from modelkit import Cube as RawCube, Model, Part, hexc, mix, ramp, rgb, speckle  # noqa: E402

# =====================================================================================================================
# Material
# =====================================================================================================================

FUR = rgb('#3a2610', '#6a4a22', '#94682c', '#b8863a', '#e6c070', '#f6e2a8')
PLUMAGE = rgb('#5e6676', '#8f97a6', '#b8c0cc', '#dfe6f0', '#ffffff')
STORM = rgb('#1e2632', '#3a4656', '#5a6a7a', '#7a8a9a', '#a4b2c0')
GOLD = rgb('#4a2c08', '#8a5a12', '#d9a02c', '#f2c858', '#fff0b0')
TUFT = rgb('#1e140a', '#3a2614', '#5a3c1e', '#7a5428')
EYE_GLOW = (223, 241, 255)


def fur(base=0.5, seed=0, grad=0.2, roundx=0.22, belly=False):
    """Löwenfell: weicher Verlauf, kurze Haarstriche, auf Wunsch hellerer Bauch an der Unterseite."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad + (speckle(x, y, seed) - 0.5) * 0.07
        if face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += roundx * (0.35 - k * k)
        if (x * 3 + y * 5 + seed) % 7 == 0:
            v -= 0.06                                                # Haarstrich
        if belly and face == 'bottom':
            v += 0.18
        if belly and face in ('left', 'right') and y >= h - 2:
            v += 0.1
        return ramp(FUR, v)
    return paint


def plumage(base=0.62, seed=0, grad=0.18, rows=3, roundx=0.2):
    """Deckgefieder: Schuppenreihen aus kleinen Federn (jede Reihe um eine halbe Feder versetzt), dunkler Saum
    unter jeder Feder."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad + (speckle(x, y, seed) - 0.5) * 0.05
        if face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += roundx * (0.35 - k * k)
        if face != 'top':
            row = y // rows
            col = (x + (row % 2) * 2) % 4
            if y % rows == rows - 1 and col in (0, 3):
                v -= 0.09
            elif y % rows == 0 and col in (1, 2):
                v += 0.04
        return ramp(PLUMAGE, v)
    return paint


def breast(base=0.62, seed=0, warm=0.35, y_off=0, total=None):
    """Brustgefieder: Reihen gerundeter Federspitzen (U-Form, jede Reihe versetzt), oben weiß, nach unten warm getönt,
    wo das Gefieder ins Löwenfell übergeht. y_off/total: Lage dieses Quaders in der ganzen Brust (für den Verlauf)."""
    def paint(face, x, y, w, h):
        tot = total or h
        gy = y + y_off
        v = base + FACE_LIGHT[face] + (0.5 - gy / max(tot - 1, 1)) * 0.2 + (speckle(x, y, seed) - 0.5) * 0.04
        if face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += 0.24 * (0.35 - k * k)
        if face in SIDES:
            row = gy // 3
            col = (x + (row % 2) * 2) % 4
            r = gy % 3
            if r == 2 and col in (1, 2):
                v -= 0.1                                             # Federspitze (Schatten darunter)
            elif r == 1 and col in (0, 3):
                v -= 0.07
            elif r == 0 and col in (1, 2):
                v += 0.05
        c = ramp(PLUMAGE, v)
        t = max(0.0, gy / max(tot - 1, 1) - 0.45) / 0.55 * warm
        return mix(c, ramp(FUR, v + 0.15), t) if face != 'top' else c
    return paint


def gold(base=0.6, seed=0, grad=0.3):
    return metal(GOLD, base, seed, grad=grad, roundx=0.3, rim=0.16, seam=0.12)


def scaly(seed):
    """Vogelbein: goldgelbe Hornschuppen in Querringen."""
    def paint(face, x, y, w, h):
        v = 0.52 + FACE_LIGHT[face] + (speckle(x, y, seed) - 0.5) * 0.06
        if y % 2 == 1:
            v -= 0.14
        if face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += 0.2 * (0.35 - k * k)
        return ramp(GOLD, v)
    return paint


def talons(face, x, y, w, h):
    """Kralle vorn: drei Zehen mit Spalt, Krallen dunkler an der Spitze."""
    if face in ('front', 'top', 'bottom') and x % 2 == 1 and w >= 5 and y >= 0:
        return None if face != 'top' else ramp(GOLD, 0.3)
    v = 0.5 + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.3
    return ramp(GOLD, v)


def paw(seed):
    """Löwenpranke: Zehenfugen vorn, dunkle Ballen unten, helle Krallenspitzen."""
    base = fur(0.48, seed, 0.16, 0.2)

    def paint(face, x, y, w, h):
        if face == 'bottom':
            return ramp(TUFT, 0.3)
        if face == 'front':
            if x in (1, 3) and w >= 5:
                return ramp(FUR, 0.18)
            if y == h - 1 and x in (0, 2, 4):
                return ramp(PLUMAGE, 0.7)                            # Krallenspitzen
        return base(face, x, y, w, h)
    return paint


# =====================================================================================================================
# Kopf
# =====================================================================================================================

EYE_ROWS = (2, 3)


def face_paint(face, x, y, w, h):
    """Vorderkopf (8 x 8 x 5): Augen seitlich unter dem Brauengrat, dahinter ein sturmgrauer Augenstreif; von vorn
    sieht man die Augen als zwei helle Punkte an den Kanten."""
    base = plumage(0.62, 501, grad=0.24, rows=2)
    if face in ('left', 'right'):
        fwd = x if face == 'right' else (w - 1 - x)                  # 0 = hinten, w-1 = vorn
        if y in EYE_ROWS:
            if fwd in (2, 3) and y == 2:
                return (hexc('#dff1ff'), EYE_GLOW + (255,))          # Auge (leuchtet)
            if fwd in (2, 3) and y == 3:
                return ramp(STORM, 0.55)                             # Unterlid
            return ramp(STORM, 0.22)                                 # Augenstreif
        if y == 1 and fwd >= 1:
            return ramp(STORM, 0.12)                                 # Schatten unter dem Brauengrat
    if face == 'front':
        if y == 2 and x in (0, w - 1):
            return (hexc('#dff1ff'), EYE_GLOW + (255,))
        if y in (1, 2, 3) and x in (0, 1, w - 2, w - 1):
            return ramp(STORM, 0.2)
        if y >= 3 and 2 <= x <= w - 3:
            return ramp(GOLD, 0.5)                                   # Wachshaut
    return base(face, x, y, w, h)


def skull_paint(face, x, y, w, h):
    """Hinterkopf: Gefieder, der Augenstreif läuft in den Nacken aus."""
    base = plumage(0.64, 504, grad=0.24, rows=2)
    if face in ('left', 'right'):
        fwd = x if face == 'right' else (w - 1 - x)
        if y == 3 + 1 and fwd >= w - 6:
            return ramp(STORM, 0.3 + (w - 1 - fwd) * 0.06)
    return base(face, x, y, w, h)


def beak_paint(face, x, y, w, h):
    """Oberschnabel: Gold mit hellem First und dunkler Schneide."""
    v = 0.6 + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.35 + (speckle(x, y, 502) - 0.5) * 0.04
    if face in ('front', 'back', 'left', 'right') and y == h - 1:
        v -= 0.25
    if face == 'top' and abs(x - (w - 1) / 2.0) < 1:
        v += 0.18
    return ramp(GOLD, v)


# =====================================================================================================================
# Schwingen: je Glied eine Deckfedern-Leiste (2 dick) und eine waagerechte Federebene mit gezackter Hinterkante.
# =====================================================================================================================

# (Länge, Schnittiefe am Ansatz, Schnitttiefe am Ende, Rücksprung der Vorderkante am Ende)
WING = [(20, 15, 15, 0), (24, 15, 12, 1), (18, 12, 3, 5)]


def wing_plane(seg, side):
    """Maler der Federebene eines Glieds. u läuft vom Gelenk nach außen, v von der Vorderkante nach hinten."""
    length, c0, c1, back = WING[seg]
    start = sum(w[0] for w in WING[:seg])                            # Abstand des Glieds vom Körper

    def chord(u):
        return c0 + (c1 - c0) * (u / length)

    def lead(u):
        return back * (u / length) ** 2

    def paint(face, i, j, fw, fh):
        if face not in ('top', 'bottom'):
            return None
        u = (i + 0.5) if side > 0 else (fw - i - 0.5)
        v = j + 0.5
        if v < lead(u):
            return None
        rel = (v - lead(u)) / max(chord(u), 1.0)
        # Schwungfedern: je 3 Texel eine Feder mit spitzem Ende
        g = start + u
        k = (g % 3.0) - 1.5
        tip = chord(u) + lead(u) - abs(k) * 0.9
        if v > tip:
            return None
        cov = 0.42 if seg < 2 else 0.3
        if rel < cov:
            row = int(v / 2.5)
            sc = ((g + row * 1.5) % 3.0) - 1.5
            val = 0.72 - abs(sc) * 0.05 - (0.12 if (v % 2.5) > 2.0 else 0.0)
            val += (speckle(i, j, 503 + seg) - 0.5) * 0.05
            c = ramp(PLUMAGE, val + (0.08 if face == 'top' else -0.12))
        else:
            t = (rel - cov) / (1 - cov)
            val = 0.75 - t * 0.4 + (0.1 if abs(k) < 0.6 else 0.0) - (0.12 if abs(k) > 1.2 else 0.0)
            val += (0.06 if face == 'top' else -0.14)
            if t > 0.55:
                c = ramp(STORM, 0.35 + (1.0 - t) * 0.9 + (0.12 if abs(k) < 0.6 else 0.0))   # Sturmgraue Spitzen
            else:
                c = ramp(PLUMAGE, val)
        return c
    return paint


def wing(side):
    s = 'l' if side > 0 else 'r'
    parts = []
    for seg, (length, c0, c1, back) in enumerate(WING):
        chord = int(max(c0, c1 + back)) + 1
        x0 = 0 if side > 0 else -length
        bar_len = length if seg < 2 else length - 4
        bx0 = 0 if side > 0 else -bar_len
        cubes = [
            RawCube(x0, 0.5, -1, length, 0, chord, wing_plane(seg, side)),
            Cube(bx0, -1, -1.5, bar_len, 2, 3, plumage(0.7, 510 + seg, grad=0.1, rows=2, roundx=0.0)),
        ]
        if seg == 0:
            cubes.append(Cube(-2 if side > 0 else -3, -2, -2.5, 5, 4, 6, plumage(0.64, 514, rows=2)))   # Schulter
        if seg == 1:
            cubes.append(Cube(-1.5, -1.5, -2, 3, 3, 4, plumage(0.72, 515, rows=2)))                   # Handgelenk
        pivot = (0, 0, 0) if seg == 0 else (side * WING[seg - 1][0], 0, 0)
        parts.append((f'wing_{s}_{seg}', pivot, cubes))
    # Verketten: 0 ── 1 ── 2
    tip = Part(parts[2][0], parts[2][1], _deg(parts[2][0]), cubes=parts[2][2])
    mid = Part(parts[1][0], parts[1][1], _deg(parts[1][0]), cubes=parts[1][2], children=[tip])
    return Part(parts[0][0], (side * 5.5, -11, 3), _deg(parts[0][0]), cubes=parts[0][2], children=[mid])


# =====================================================================================================================
# Modell
# =====================================================================================================================

_REST = {}


def _deg(name):
    v = _REST.get(name)
    return tuple(math.degrees(a) for a in v[:3]) if v else (0.0, 0.0, 0.0)


def griffin():
    beak_lower = Part('beak_lower', (0, -3, -10), _deg('beak_lower'), cubes=[
        Cube(-1.5, 0, -5, 3, 2, 6, beak_paint),
    ])
    head = Part('head', (0, -4, -1), _deg('head'), cubes=[
        Cube(-5, -11, -4, 10, 10, 9, skull_paint),                                         # Hinterkopf
        Cube(-4, -10, -9, 8, 8, 5, face_paint),                                            # Vorderkopf mit Augen
        Cube(-4.5, -11, -9.5, 9, 2, 7, plumage(0.46, 520, rows=2, roundx=0.0)),           # Brauengrat
        Cube(-2.5, -7, -11, 5, 4, 2, gold(0.66, 523, grad=0.2)),                           # Wachshaut
        Cube(-2, -7, -15, 4, 4, 4, beak_paint),                                            # Oberschnabel
        Cube(-1.5, -6, -17, 3, 6, 2, beak_paint),                                          # Haken
        Cube(-4.5, -13, 1, 3, 3, 4, plumage(0.6, 521, rows=2)),                            # Federohr rechts
        Cube(1.5, -13, 1, 3, 3, 4, plumage(0.6, 521, rows=2)),                             # Federohr links
        Cube(-4, -15, 3, 2, 3, 3, plumage(0.5, 522, rows=2)),
        Cube(2, -15, 3, 2, 3, 3, plumage(0.5, 522, rows=2)),
    ], children=[beak_lower])

    def jagged(paint, depth=2, seed=0):
        def wrapped(face, x, y, w, h):
            if face in SIDES and y >= h - depth:
                if ((x + seed) % 3) != 1 and y >= h - 1:
                    return None
                if ((x + seed) % 3) == 0 and y >= h - depth:
                    return None
            return paint(face, x, y, w, h)
        return wrapped

    neck = Part('neck', (0, -6, -10), _deg('neck'), cubes=[
        Cube(-4, -6, -5, 8, 8, 8, plumage(0.64, 530, rows=2)),
        # Federkragen: sechs Keile im Ring, unten gezackt
        Cube(-6, -2, -7, 12, 5, 3, jagged(plumage(0.7, 531), 2, 0)),
        Cube(-6, -2, 3, 12, 5, 3, jagged(plumage(0.6, 532), 2, 1)),
        Cube(-7, -2, -5, 3, 5, 8, jagged(plumage(0.62, 533), 2, 2)),
        Cube(4, -2, -5, 3, 5, 8, jagged(plumage(0.62, 534), 2, 0)),
        Cube(-5, -4, -6, 10, 3, 10, plumage(0.72, 535)),
        Cube(-4.5, 2, -6.5, 9, 3, 9, jagged(plumage(0.55, 536), 2, 1)),
    ], children=[head])

    def fore_leg(side):
        s = 'l' if side > 0 else 'r'
        low = Part(f'fore_{s}_low', (0, 8, 0), _deg(f'fore_{s}_low'), cubes=[
            Cube(-1.5, 0, -1.5, 3, 9, 3, scaly(540 + side)),
            Cube(-2.5, 9, -4, 5, 2, 5, scaly(542 + side)),                                 # Fuß
            Cube(-2.5, 9, -6, 5, 2, 2, talons),                                            # Krallen vorn
            Cube(-1, 9, 1, 2, 2, 2, gold(0.4, 544)),                                       # Hinterkralle
        ])
        return Part(f'fore_{s}', (side * 5, 1, -4), _deg(f'fore_{s}'), cubes=[
            Cube(-2.5, -2, -2.5, 5, 11, 5, plumage(0.62, 545 + side, rows=2)),            # befiederte Hose
        ], children=[low])

    chest = Part('chest', (0, 0, -4), _deg('chest'), cubes=[
        Cube(-8, -7, -8, 16, 9, 9, breast(0.62, 550, total=16)),                           # Brust oben
        Cube(-7, 2, -8, 14, 7, 9, breast(0.62, 553, y_off=9, total=16)),                   # Brust unten, schmaler
        Cube(-6, -6, -10.5, 12, 13, 4, breast(0.68, 554, y_off=1, total=16)),              # Brustbein vorn
        Cube(-7, -8, -8, 14, 3, 12, plumage(0.72, 551, rows=2, roundx=0.0)),              # Widerrist
        Cube(-6, 8, -9, 12, 2, 8, jagged(plumage(0.56, 552, rows=2), 2, 0)),              # Brustfedern hängen
    ], children=[neck, wing(1), wing(-1), fore_leg(1), fore_leg(-1)])

    def hind_leg(side):
        s = 'l' if side > 0 else 'r'
        low = Part(f'hind_{s}_low', (0, 8, 2), _deg(f'hind_{s}_low'), cubes=[
            Cube(-2.5, 0, -2.5, 5, 9, 5, fur(0.45, 560 + side, 0.18)),
            Cube(-3, 8, -5, 6, 3, 7, paw(562 + side)),
        ])
        return Part(f'hind_{s}', (side * 5, 1, 14), _deg(f'hind_{s}'), cubes=[
            Cube(-3, -4, -5, 6, 13, 9, fur(0.52, 565 + side, 0.22)),                      # Keule
        ], children=[low])

    tail = Part('tail_0', (0, -2, 22), _deg('tail_0'), cubes=[Cube(-1.5, -1.5, 0, 3, 3, 9, fur(0.5, 570))], children=[
        Part('tail_1', (0, 0, 8), _deg('tail_1'), cubes=[Cube(-1.5, -1.5, 0, 3, 3, 9, fur(0.46, 571))], children=[
            Part('tail_2', (0, 0, 8), _deg('tail_2'), cubes=[
                Cube(-1, -1, 0, 2, 2, 7, fur(0.42, 572)),
                Cube(-2, -2, 5, 4, 4, 6, surf(TUFT, 0.5, 0.2, 573, roundx=0.3, noise=0.06)),   # Quaste
            ]),
        ]),
    ])

    body = Part('body', (0, 4, 6), _deg('body'), cubes=[
        Cube(-7, -8, -8, 14, 12, 14, fur(0.54, 580, belly=True)),                         # Vorderleib
        Cube(-6, -7, 6, 12, 11, 11, fur(0.5, 581, belly=True)),                           # Hinterleib, schmaler
        Cube(-6.5, -9, -4, 13, 3, 18, fur(0.62, 582, grad=0.1)),                         # Rücken
    ], children=[chest, hind_leg(1), hind_leg(-1), tail])
    return SkylineModel('griffin', 256, 128, [body])


# =====================================================================================================================
# Posen
# =====================================================================================================================

_RIG = Rig(griffin())


def orient(p, name, xdir, zdir):
    """Dreht einen Knochen so, dass seine lokale +x-Achse nach xdir zeigt und +z möglichst nach zdir."""
    x = _norm(xdir)
    z = _norm(_sub(zdir, _scale(x, _dot(zdir, x))))
    y = _cross(z, x)
    _RIG.set_world_rot(p, name, [[x[i], y[i], z[i]] for i in range(3)])


def wings(p, dirs, chords, side_out=True):
    """Setzt beide Schwingen über Weltrichtungen je Glied (für die linke Seite; rechts gespiegelt).
    dirs: Spannrichtung der drei Glieder, chords: Richtung von der Vorderkante zur Hinterkante."""
    for side, s in ((1, 'l'), (-1, 'r')):
        for seg in range(3):
            d = dirs[seg]
            c = chords[seg]
            xd = (d[0] * side, d[1], d[2]) if side > 0 else (d[0] * side, d[1], d[2])
            cd = (c[0] * side, c[1], c[2])
            # Rechts zeigt die lokale +x-Achse zum Körper: Spannrichtung umkehren
            orient(p, f'wing_{s}_{seg}', xd if side > 0 else _scale(xd, -1), cd)


FOLD = dict(
    dirs=[(0.3, -0.22, 1.0), (0.12, 0.2, 1.0), (-0.02, 0.5, 1.0)],
    chords=[(0.7, 1.0, 0.0), (0.75, 1.0, 0.0), (0.6, 1.0, 0.2)],
)
PERCH_V = dict(
    dirs=[(0.75, -0.95, 0.35), (0.55, -0.55, 0.6), (0.25, 0.1, 1.0)],
    chords=[(0.3, 0.2, 1.0), (0.2, 0.5, 1.0), (0.1, 0.9, 0.4)],
)
SPREAD = dict(
    dirs=[(1.0, -0.12, 0.05), (1.0, -0.05, 0.15), (1.0, 0.08, 0.35)],
    chords=[(0.0, 0.1, 1.0), (0.0, 0.1, 1.0), (-0.2, 0.15, 1.0)],
)
TIGHT = dict(
    dirs=[(0.35, -0.3, 1.0), (0.1, -0.1, 1.0), (-0.05, 0.05, 1.0)],
    chords=[(0.5, 1.0, 0.0), (0.6, 1.0, 0.1), (0.5, 1.0, 0.3)],
)
LAID = dict(
    dirs=[(1.0, 0.25, 0.2), (1.0, 0.35, 0.35), (1.0, 0.3, 0.6)],
    chords=[(0.0, 0.15, 1.0), (-0.1, 0.2, 1.0), (-0.3, 0.2, 1.0)],
)
UP = dict(
    dirs=[(0.45, -1.0, 0.15), (0.25, -1.0, 0.3), (0.1, -1.0, 0.45)],
    chords=[(-0.3, 0.0, 1.0), (-0.2, 0.0, 1.0), (-0.1, 0.1, 1.0)],
)
DOWN = dict(
    dirs=[(1.0, 0.55, 0.0), (0.8, 0.9, 0.15), (0.4, 1.0, 0.35)],
    chords=[(0.0, 0.0, 1.0), (0.2, 0.0, 1.0), (0.3, 0.0, 1.0)],
)

FOOT = {'fore': [(sx, 11, sz) for sx in (-2.5, 2.5) for sz in (-6, 3)], 'hind': [(sx, 11, sz) for sx in (-3, 3) for sz in (-5, 2)]}


def _ground(p, lift=0.0):
    """Senkt bzw. hebt den Leib, bis die tiefste Pfote auf dem Boden (y = 24) steht."""
    low = max(_RIG.world(p, f'{k}_{s}_low', c)[1] for k in ('fore', 'hind') for s in ('l', 'r') for c in FOOT[k])
    v = tuple(p.get('body', (0.0, 0.0, 0.0)))
    v = v + (0.0, 0.0, 0.0) if len(v) == 3 else v
    p['body'] = v[:4] + (v[4] + 24.0 - low - lift,) + v[5:]
    return p


def _legs_stand(p):
    p.update(fore_l=(0.05, 0, 0), fore_l_low=(-0.05, 0, 0), fore_r=(-0.05, 0, 0), fore_r_low=(0.05, 0, 0),
             hind_l=(0.15, 0, 0), hind_l_low=(-0.25, 0, 0), hind_r=(0.05, 0, 0), hind_r_low=(-0.15, 0, 0))


def _base(**bones):
    p = dict(body=(0.0, 0.0, 0.0), chest=(0.0, 0.0, 0.0), neck=(0.0, 0.0, 0.0), head=(0.0, 0.0, 0.0),
             beak_lower=(0.1, 0.0, 0.0), tail_0=(0.6, 0.0, 0.0), tail_1=(-0.5, 0.0, 0.0), tail_2=(-0.4, 0.0, 0.0))
    _legs_stand(p)
    p.update(bones)
    return p


def _idle():
    p = _base()
    wings(p, **FOLD)
    return _ground(p)


def _perch():
    """Sitzt aufrecht wie eine Katze: Hinterbeine untergeschlagen, Vorderbeine senkrecht, Schwingen als V."""
    p = _base(body=(-0.55, 0.0, 0.0), chest=(-0.05, 0.0, 0.0), neck=(0.35, 0.0, 0.0), head=(0.2, 0.0, 0.0),
              fore_l=(0.62, 0, -0.04), fore_l_low=(-0.02, 0, 0), fore_r=(0.62, 0, 0.04), fore_r_low=(-0.02, 0, 0),
              hind_l=(-0.85, 0, 0.12), hind_l_low=(1.95, 0, 0), hind_r=(-0.85, 0, -0.12), hind_r_low=(1.95, 0, 0),
              tail_0=(0.95, 0.35, 0.0), tail_1=(0.25, 0.45, 0.0), tail_2=(-0.1, 0.4, 0.0))
    wings(p, **PERCH_V)
    return _ground(p)


def _dive_windup():
    """Auf der Säule: Schwingen ganz offen, Leib gestreckt und nach vorn geneigt, der Kopf zielt."""
    p = _base(body=(0.2, 0.0, 0.0), neck=(0.25, 0.0, 0.0), head=(0.25, 0.0, 0.0),
              beak_lower=(0.45, 0.0, 0.0),
              fore_l=(-0.35, 0, 0), fore_l_low=(0.3, 0, 0), fore_r=(-0.35, 0, 0), fore_r_low=(0.3, 0, 0),
              hind_l=(0.55, 0, 0), hind_l_low=(-0.45, 0, 0), hind_r=(0.55, 0, 0), hind_r_low=(-0.45, 0, 0),
              tail_0=(0.25, 0.0, 0.0), tail_1=(-0.15, 0.0, 0.0), tail_2=(-0.1, 0.0, 0.0))
    wings(p, **SPREAD)
    return _ground(p)


def _dive_fall():
    """Sturz: Schwingen eng an den Leib, Krallen voraus, Kopf und Leib eine Linie nach vorn unten."""
    p = _base(body=(0.45, 0.0, 0.0, 0.0, -6.0, 0.0), chest=(0.1, 0.0, 0.0), neck=(0.45, 0.0, 0.0), head=(-0.2, 0.0, 0.0),
              beak_lower=(0.6, 0.0, 0.0),
              fore_l=(-1.5, 0, -0.1), fore_l_low=(-0.4, 0, 0), fore_r=(-1.5, 0, 0.1), fore_r_low=(-0.4, 0, 0),
              hind_l=(1.0, 0, 0), hind_l_low=(0.2, 0, 0), hind_r=(1.0, 0, 0), hind_r_low=(0.2, 0, 0),
              tail_0=(-0.35, 0.0, 0.0), tail_1=(0.1, 0.0, 0.0), tail_2=(0.1, 0.0, 0.0))
    wings(p, **TIGHT)
    return p


def _lowest(p):
    """Tiefster Punkt des ganzen Modells in der Pose (für Posen, in denen der Leib aufliegt)."""
    from preview import world_points
    return max(q[1] for q in world_points(griffin(), p, 'silhouette'))


def _landed():
    """Aufgeschlagen: liegt flach auf dem Bauch, Vorderbeine nach vorn, Hinterbeine nach hinten gestreckt,
    Schwingen ausgebreitet am Boden: offen."""
    p = _base(body=(0.04, 0.0, 0.06), chest=(0.06, 0.0, 0.0), neck=(0.5, 0.0, 0.0), head=(0.3, 0.3, 0.25),
              beak_lower=(0.35, 0.0, 0.0),
              fore_l=(-1.45, 0.25, -0.2), fore_l_low=(0.15, 0, 0), fore_r=(-1.4, -0.2, 0.2), fore_r_low=(0.15, 0, 0),
              hind_l=(1.35, 0, -0.3), hind_l_low=(0.2, 0, 0), hind_r=(1.4, 0, 0.3), hind_r_low=(0.2, 0, 0),
              tail_0=(0.05, 0.3, 0.0), tail_1=(0.0, 0.4, 0.0), tail_2=(0.0, 0.3, 0.0))
    wings(p, **LAID)
    v = p['body'] + (0.0, 0.0, 0.0)
    p['body'] = v[:3] + (0.0, 24.0 - _lowest(p) + 0.5, 0.0)
    return p


def _swipe_windup():
    """Steigt auf die Hinterbeine, rechte Vorderkralle hoch und zurück, Schwingen halb offen zum Ausgleich."""
    p = _base(body=(-0.85, 0.25, 0.0), chest=(-0.15, 0.2, 0.0), neck=(0.6, -0.2, 0.0), head=(0.35, -0.3, 0.0),
              beak_lower=(0.5, 0.0, 0.0),
              fore_r=(-2.4, 0.0, 0.55), fore_r_low=(-0.9, 0, 0), fore_l=(-0.2, 0, -0.25), fore_l_low=(-0.9, 0, 0),
              hind_l=(-0.15, 0, 0.1), hind_l_low=(1.3, 0, 0), hind_r=(-0.05, 0, -0.1), hind_r_low=(1.2, 0, 0),
              tail_0=(-0.2, 0.0, 0.0), tail_1=(0.2, 0.0, 0.0), tail_2=(0.2, 0.0, 0.0))
    wings(p, **PERCH_V)
    return _ground(p)


def _swipe_hit():
    """Hieb quer nach vorn unten: die Kralle fährt von rechts oben nach links unten, der Leib fällt nach vorn."""
    p = _base(body=(-0.3, -0.35, 0.0), chest=(0.15, -0.3, 0.0), neck=(0.3, 0.25, 0.0), head=(0.1, 0.3, 0.0),
              beak_lower=(0.6, 0.0, 0.0),
              fore_r=(-1.0, -0.4, -0.55), fore_r_low=(-0.2, 0, 0), fore_l=(0.25, 0, -0.1), fore_l_low=(-0.3, 0, 0),
              hind_l=(-0.05, 0, 0.1), hind_l_low=(0.7, 0, 0), hind_r=(0.25, 0, -0.1), hind_r_low=(0.4, 0, 0),
              tail_0=(0.3, -0.4, 0.0), tail_1=(-0.2, -0.3, 0.0), tail_2=(-0.2, -0.2, 0.0))
    wings(p, dirs=[(0.9, -0.6, 0.2), (0.7, -0.2, 0.5), (0.3, 0.2, 1.0)],
          chords=[(0.1, 0.2, 1.0), (0.1, 0.4, 1.0), (0.0, 0.8, 0.4)])
    return _ground(p)


def _takeoff():
    """Duckt sich tief, die Schwingen weit nach oben gerissen (Anfang des ersten Schlags)."""
    p = _base(body=(0.1, 0.0, 0.0), neck=(-0.3, 0.0, 0.0), head=(-0.15, 0.0, 0.0), beak_lower=(0.3, 0.0, 0.0),
              fore_l=(0.55, 0, 0), fore_l_low=(-1.2, 0, 0), fore_r=(0.55, 0, 0), fore_r_low=(-1.2, 0, 0),
              hind_l=(-0.6, 0, 0), hind_l_low=(1.25, 0, 0), hind_r=(-0.6, 0, 0), hind_r_low=(1.25, 0, 0),
              tail_0=(0.15, 0.0, 0.0), tail_1=(-0.1, 0.0, 0.0), tail_2=(-0.1, 0.0, 0.0))
    wings(p, **UP)
    return _ground(p)


def _fly(wing_set):
    p = _base(body=(0.15, 0.0, 0.0, 0.0, -4.0, 0.0), neck=(0.1, 0.0, 0.0), head=(0.05, 0.0, 0.0),
              fore_l=(-0.9, 0, 0), fore_l_low=(1.3, 0, 0), fore_r=(-0.9, 0, 0), fore_r_low=(1.3, 0, 0),
              hind_l=(1.1, 0, 0), hind_l_low=(0.3, 0, 0), hind_r=(1.1, 0, 0), hind_r_low=(0.3, 0, 0),
              tail_0=(0.1, 0.0, 0.0), tail_1=(-0.05, 0.0, 0.0), tail_2=(-0.05, 0.0, 0.0))
    wings(p, **wing_set)
    return p


POSES_RAW = {'idle': _idle()}
for _name, _fn in (('perch', _perch), ('dive_windup', _dive_windup), ('dive_fall', _dive_fall), ('landed', _landed),
                   ('swipe_windup', _swipe_windup), ('swipe_hit', _swipe_hit), ('takeoff', _takeoff),
                   ('fly_up', lambda: _fly(UP)), ('fly_down', lambda: _fly(DOWN))):
    POSES_RAW[_name] = _fn()
POSES = {name: rounded(pose) for name, pose in POSES_RAW.items()}
_REST.update({k: v for k, v in POSES['idle'].items() if k != 'body'})
KOTLIN_POSES = ['perch', 'dive_windup', 'dive_fall', 'landed', 'swipe_windup', 'swipe_hit', 'takeoff', 'fly_up',
                'fly_down']
HEIGHT_EXCLUDE = ('wing_l_0', 'wing_r_0', 'tail_0')


def kotlin_block():
    return kotlin_table(griffin(), POSES, ['idle'] + KOTLIN_POSES, {})


if __name__ == '__main__':
    if '--kotlin' in sys.argv:
        sync_kotlin('GRIFFIN', kotlin_block())
