"""Die Goldene Wächterin (Automat des Hephaistos): eine goldene Statue, die sich bewegt
(Brief tmp/overhaul2/models/olymp_gegner.md, Registry `automaton`). Modell, Textur, Leuchtmaske und POSES.

Erkennungsmerkmal: säulenförmiger, kannelierter Unterleib ohne Beine (sie gleitet), ein rechter Arm, der in einem
kupferbeschlagenen Hammerblock endet, ein glattes Maskengesicht ohne Mund, auf dem Rücken ein achteckiges Ventilrad.
Gold ist hier Material, nicht Kampfsignal: Es leuchtet nie. Die Leuchtmaske (Glut) liegt nur in den zwei
Augenschlitzen und im Spalt des Ventils; der Renderer zeichnet sie nur, solange sie wach ist.

Koordinaten: 1/16 Block vor dem Renderer-Faktor 0,70, +y nach unten, -z vorn, Säulenfuß bei y = 24, Scheitel bei -40
(64 Einheiten → 2,8 Blöcke). Knochen (client/model/OlympMobModels.kt):

    body (Rock) ─ chest ─ head
                    ├─ arm_r ── fore_r ── hammer
                    ├─ arm_l ── fore_l
                    └─ valve

    python tools/hha_assets/automaton_model.py --kotlin    # schreibt die Posentabelle nach OlympMobModels.kt
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from bosskit import Cube  # noqa: E402
from kurete_model import FACE_LIGHT, SIDES, Rig, kotlin_table, metal, rounded, surf, sync_kotlin  # noqa: E402
from imp_model import SkylineModel  # noqa: E402  (dichter Atlas-Packer, nur gelesen)
from modelkit import Model, Part, hexc, mix, ramp, rgb, speckle  # noqa: E402

# =====================================================================================================================
# Material
# =====================================================================================================================

GOLD = rgb('#3a2808', '#6a4a12', '#9a6c1c', '#c8922a', '#f6d06a', '#fff4c8')
SEAM = hexc('#3a2808')
COPPER = rgb('#3a1608', '#7a3a1c', '#a4542a', '#c8703a', '#e89a64')
STEAM = rgb('#8a8c90', '#c4c6ca', '#eceef0')
# Glut der Augen und des Ventils. #ffb347 aus dem Brief fiele in den Gold-Prüfbereich der Vorschau (Gold leuchtet nie):
# eine Spur röter.
EMBER = (255, 118, 48)
EMBER_ALBEDO = hexc('#ffc890')


def ember(strength=255):
    """Glutfleck: in der Farbkarte dunkel (schlafend sieht man nur einen Schlitz), in der Leuchtmaske Glut."""
    return (hexc('#2a1206'), EMBER + (strength,))


def gold(base=0.55, seed=0, grad=0.2, roundx=0.24, rim=0.18, seam=0.16, rivets=0):
    """Gegossenes Gold: weicher Glanz in der Mitte, Grat oben, Fuge unten (wie die Bronze des Kerkermeisters)."""
    return metal(GOLD, base, seed, grad=grad, roundx=roundx, rim=rim, seam=seam, rivets=rivets)


def copper(base=0.55, seed=0, rivets=0):
    return metal(COPPER, base, seed, grad=0.22, roundx=0.22, rim=0.2, seam=0.18, rivets=rivets)


def fluted(base, seed, step=4, x0=0.0):
    """Säulenschaft: senkrechte Kanneluren als 1 Texel dunkle Linien, dazwischen gewölbte Stege mit hellem Grat."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.22 + (speckle(x, y, seed) - 0.5) * 0.05
        if face in ('top', 'bottom'):
            return ramp(GOLD, v)
        k = (x + x0) % step
        if k == 0:
            return ramp(GOLD, v - 0.26)                              # Kannelur
        if k == 1:
            v += 0.1                                                 # Steg im Licht
        return ramp(GOLD, v)
    return paint


def octagon(x_lo, y0, h, width, paint_for, seed, z=0.0):
    """Achteckiger Säulenquerschnitt aus zwei gekreuzten Quadern (Breite width, Kreuzarm 0,62 · width): von vorn,
    von der Seite und schräg liest er sich als achtkantiger Schaft. Senkrecht von y0 nach unten, h hoch."""
    side = round(width * 0.62)
    return [
        Cube(-width / 2.0 + x_lo, y0, -side / 2.0 + z, width, h, side, paint_for(seed)),
        Cube(-side / 2.0 + x_lo, y0, -width / 2.0 + z, side, h, width, paint_for(seed + 1), dilate=0.01),
    ]


# =====================================================================================================================
# Besondere Maler
# =====================================================================================================================

def mask(face, x, y, w, h):
    """Maske (7 x 7, 2 dick) vor dem Gesicht: zwei Flächen (links heller, rechts dunkler) mit Grat in der Mitte,
    zwei schmale Augenschlitze (Glut, nur wach sichtbar), kein Mund, unten eine leicht dunklere Kinnkante."""
    if face != 'front':
        return ramp(GOLD, 0.5 + FACE_LIGHT[face])
    cx = (w - 1) / 2.0
    if y == 2 and 1 <= abs(x - cx) <= 2.5:
        return ember()                                                # Augenschlitze
    if y == 1 and 1 <= abs(x - cx) <= 2.5:
        return ramp(GOLD, 0.42)                                       # Lidschatten über dem Schlitz
    if abs(x - cx) < 0.6:
        return ramp(GOLD, 0.88 - y * 0.03)                            # Grat der Maske
    v = 0.7 + (0.08 if x < cx else -0.1) - y * 0.02
    if y == h - 1:
        v -= 0.12                                                     # Kinnkante
    if x in (0, w - 1):
        v -= 0.08
    return ramp(GOLD, v)


def hair(face, x, y, w, h):
    """Gegossenes Haar: gescheitelte Wellen nach hinten, vorn über der Stirn ein Diadem."""
    v = 0.5 + FACE_LIGHT[face] + 0.08 * math.sin(x * 1.4 + y * 0.6) + (speckle(x, y, 602) - 0.5) * 0.05
    v += (0.5 - y / max(h - 1, 1)) * 0.14
    if face == 'top' and abs(x - (w - 1) / 2.0) < 0.6:
        return ramp(GOLD, 0.3)                                        # Scheitel
    if face == 'front':
        if y == 2:
            return ramp(GOLD, 0.86)                                   # Diadem
        if y > 2:
            return ramp(GOLD, 0.28)                                   # Schatten hinter der Maske
    return ramp(GOLD, v)


def hair_fall(face, x, y, w, h):
    """Haar, das in den Nacken fällt: senkrechte Strähnen, unten gelockt."""
    v = 0.46 + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.2 + 0.1 * math.sin(x * 1.9)
    if face in SIDES and y == h - 1 and x % 2 == 0:
        return None
    return ramp(GOLD, v)


def drapery(seed, base=0.56, band=None, diagonal=False, collar=False):
    """Gegossenes Gewand wie bei einer Kore: weiche senkrechte Falten, auf Wunsch ein Kragen mit Kupferperlen, ein
    Band unter der Brust (Zeile band) und die schräge Bahn des Mantels (Himation) von der linken Schulter."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.2 + (speckle(x, y, seed) - 0.5) * 0.04
        if face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += 0.2 * (0.35 - k * k)
            v += 0.09 * math.sin(x * 1.6 + seed)                     # Falten
        if face in SIDES:
            if collar and y == 0:
                return ramp(COPPER, 0.8) if x % 2 == 0 else ramp(GOLD, 0.82)
            if band is not None and y == band:
                return ramp(GOLD, 0.84)
            if band is not None and y == band + 1:
                return SEAM
            if diagonal and face == 'front':
                d = (w - 1 - x) - y * 1.4                            # von oben links (Bildseite rechts) nach unten
                if -1.5 <= d <= 1.5:
                    return ramp(GOLD, 0.74 + (0.1 if d > 0.5 else 0.0) - (0.18 if d < -0.8 else 0.0))
        return ramp(GOLD, v)
    return paint


def girdle(face, x, y, w, h):
    """Gürtel über dem Säulenhals: Kupferband mit Mäander-Punkten."""
    base = copper(0.5, 611)
    if face in SIDES and y == 1 and x % 3 == 1:
        return ramp(GOLD, 0.85)
    return base(face, x, y, w, h)


def joint(seed):
    """Gelenk: dunkles Kugelgelenk mit Fuge, damit sie mechanisch wirkt."""
    def paint(face, x, y, w, h):
        v = 0.42 + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.3
        if face in SIDES and y == h // 2:
            return SEAM
        return ramp(GOLD, v)
    return paint


def hammer_paint(face, x, y, w, h):
    """Hammerblock: gegossenes Gold, Kupferbänder oben und unten, Nieten, die Schlagfläche unten blank."""
    if face == 'bottom':
        v = 0.66 + (speckle(x, y, 620) - 0.5) * 0.1 - (0.12 if x in (0, w - 1) or y in (0, h - 1) else 0.0)
        return ramp(GOLD, v)
    if face in SIDES and (y <= 1 or y >= h - 2):
        if y in (1, h - 2) and x % 3 == 1:
            return ramp(COPPER, 0.92)                                 # Niet
        return ramp(COPPER, 0.5 + FACE_LIGHT[face] + (0.12 if y in (0, h - 1) else 0.0))
    v = 0.56 + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.2 + (speckle(x, y, 621) - 0.5) * 0.06
    if face in SIDES and w > 3:
        k = (x + 0.5) / w * 2 - 1
        v += 0.12 * (0.35 - k * k)
        if face in ('front', 'back') and abs(x - (w - 1) / 2.0) < 1.6 and 3 <= y <= h - 4:
            return ramp(GOLD, 0.82 if y == 3 else 0.68)               # Zierrippe
    return ramp(GOLD, v)


def open_hand(face, x, y, w, h):
    """Offene Hand: vier Finger als Fugen, Fingerspitzen etwas dunkler."""
    base = gold(0.6, 630, grad=0.2, roundx=0.1)
    if face in ('front', 'back') and x % 2 == 1 and y >= 2:
        return ramp(GOLD, 0.3)
    if face in SIDES and y == h - 1:
        return ramp(GOLD, 0.44)
    return base(face, x, y, w, h)


VALVE_R = 6.0


def valve_face(x0, y0):
    """Ventilrad auf dem Rücken (Fläche nach +z): Kupferkranz, vier Speichen, Nabe; zwischen den Speichen ein
    schmaler glühender Spalt (Glut, nur wach sichtbar). Die Rückseite (-z) liegt am Rumpf."""
    rim = copper(0.6, 640)

    def paint(face, x, y, w, h):
        if face != 'back':
            return rim(face, x, y, w, h)
        px, py = x0 + w - x - 0.5, y0 + y + 0.5
        r = max(abs(px), abs(py), (abs(px) + abs(py)) / 1.41)
        light = (0.5 - py / 12.0) * 0.16
        if r > VALVE_R - 1.4:
            return ramp(COPPER, 0.78 + light)                         # Kranz
        if r < 1.6:
            return ramp(GOLD, 0.86 + light)                           # Nabe
        if abs(px) < 0.8 or abs(py) < 0.8:
            return ramp(COPPER, 0.62 + light)                         # Speichen
        if VALVE_R - 2.4 < r <= VALVE_R - 1.4:
            return ember(220)                                         # Spalt innen am Kranz
        return ramp(COPPER, 0.18)                                     # dunkle Kammer
    return paint


# =====================================================================================================================
# Modell
# =====================================================================================================================

_REST = {}


def _deg(name):
    v = _REST.get(name)
    return tuple(math.degrees(a) for a in v[:3]) if v else (0.0, 0.0, 0.0)


def automaton():
    head = Part('head', (0, -20, 0), _deg('head'), cubes=[
        Cube(-4.5, -10, -4.5, 9, 10, 9, hair),
        Cube(-3.5, -7, -6, 7, 7, 2, mask),                                              # Maske
        Cube(-2.5, -10, 4, 5, 5, 4, gold(0.5, 603, roundx=0.3)),                       # Haarknoten
        Cube(-4, -4, 3, 8, 8, 3, hair_fall),                                            # Haar im Nacken
    ])

    hammer = Part('hammer', (0, 10, 0), _deg('hammer'), cubes=[
        Cube(-2.5, -1, -2.5, 5, 3, 5, copper(0.45, 622)),                             # Manschette
        Cube(-5, 2, -6, 10, 10, 12, hammer_paint),                                      # Hammerblock
    ])
    arm_r = Part('arm_r', (-13, -16, 0), _deg('arm_r'), cubes=[
        Cube(-4, -3.5, -4, 8, 6, 8, gold(0.62, 650, roundx=0.3, rivets=3)),           # Schulterkugel
        Cube(-3, 0, -3, 6, 11, 6, gold(0.52, 651)),
        Cube(-3.5, 10, -3.5, 7, 3, 7, joint(652)),                                      # Ellbogen
    ], children=[Part('fore_r', (0, 12, 0), _deg('fore_r'), cubes=[
        Cube(-3, 0, -3, 6, 10, 6, gold(0.5, 653, rivets=3)),
        Cube(-3.5, 6, -3.5, 7, 3, 7, copper(0.5, 654)),                                # Kupferring
    ], children=[hammer])])

    arm_l = Part('arm_l', (12, -16, 0), _deg('arm_l'), cubes=[
        Cube(-3, -3, -3, 6, 5, 6, gold(0.62, 660, roundx=0.3)),
        Cube(-2.5, 0, -2.5, 5, 11, 5, gold(0.54, 661)),
        Cube(-3, 10, -3, 6, 3, 6, joint(662)),
    ], children=[Part('fore_l', (0, 12, 0), _deg('fore_l'), cubes=[
        Cube(-2.5, 0, -2.5, 5, 9, 5, gold(0.52, 663)),
        Cube(-3, 2, -3, 6, 2, 6, copper(0.55, 664)),                                    # Armreif
        Cube(-2.5, 9, -1, 5, 6, 2, open_hand),                                          # offene Hand
        Cube(-3.5, 9, -1.5, 2, 4, 3, gold(0.5, 665, roundx=0.0)),                      # Daumen
    ])])

    valve_cubes = []
    for i, (w, h) in enumerate(((12, 5), (5, 12), (9, 9))):
        x0, y0 = -w / 2.0, -h / 2.0
        valve_cubes.append(Cube(x0, y0, 0, w, h, 2, valve_face(x0, y0), dilate=0.01 * i))
    valve_cubes.append(Cube(-1.5, -1.5, -2, 3, 3, 2, copper(0.4, 641)))              # Welle zum Rumpf
    valve = Part('valve', (0, -13, 6.5), _deg('valve'), cubes=valve_cubes)

    chest = Part('chest', (0, -34, 0), _deg('chest'), cubes=[
        Cube(-6, -7, -4.5, 12, 7, 9, drapery(670, 0.5)),                                # Taille
        Cube(-8, -13, -5, 16, 7, 10, drapery(671, 0.54, band=0)),                       # Brust, Band darunter
        Cube(-11, -19, -5, 22, 6, 10, drapery(672, 0.6, collar=True, diagonal=True)),   # Schultern
        Cube(-7, -18, -6, 14, 7, 2, drapery(673, 0.62, diagonal=True)),                 # Brust vorn (Mantelbahn)
        Cube(-2, -21, -2, 4, 3, 4, joint(674)),                                         # Hals
        Cube(-6.5, -1, -5, 13, 2, 10, girdle),                                          # Gürtel
    ], children=[head, arm_r, arm_l, valve])

    skirt = []
    skirt += octagon(0, -34, 8, 14, lambda s: fluted(0.6, s, 4, 1), 680)              # Säulenhals (oben 14)
    skirt += octagon(0, -26, 14, 17, lambda s: fluted(0.55, s, 4, 0), 684)
    skirt += octagon(0, -12, 10, 20, lambda s: fluted(0.5, s, 4, 2), 688)             # unten 20
    skirt += [
        Cube(-10, -2, -10, 20, 2, 20, gold(0.62, 696, roundx=0.0, rim=0.25)),           # Wulst (Torus)
    ]
    body = Part('body', (0, 24, 0), _deg('body'), cubes=skirt, children=[chest])
    return SkylineModel('automaton', 128, 256, [body])


# =====================================================================================================================
# Posen: Die Wächterin bewegt sich in Rucken; die Kotlin-Seite fährt zwischen diesen Schlüsselbildern in 2 Ticks.
# =====================================================================================================================

_RIG = Rig(automaton())


def _p(**bones):
    base = dict(body=(0.0, 0.0, 0.0), chest=(0.0, 0.0, 0.0), head=(0.0, 0.0, 0.0),
                arm_r=(0.0, 0.0, 0.1), fore_r=(0.0, 0.0, 0.0), hammer=(0.0, 0.0, 0.0),
                arm_l=(0.0, 0.0, -0.08), fore_l=(-0.15, 0.0, 0.0), valve=(0.0, 0.0, 0.0))
    base.update(bones)
    return base


POSES_RAW = {
    # Statue: völlig reglos, Arme angelegt, Kopf geradeaus.
    'statue': _p(),
    # Wach (Gleiten): Oberkörper leicht vorgeneigt, Arme gelöst, der Hammer hängt schwer.
    'idle': _p(chest=(0.14, 0.0, 0.0), head=(-0.08, 0.0, 0.0), arm_r=(-0.12, 0.0, 0.22), fore_r=(-0.25, 0.0, 0.0),
               arm_l=(-0.2, 0.0, -0.3), fore_l=(-0.65, 0.0, 0.0)),
    # Erwachen (Endstand des zweiten Rucks): Kopf ruckartig zur Seite, Arme vom Körper gelöst.
    'wake': _p(chest=(0.05, 0.12, 0.0), head=(0.1, -0.55, 0.0), arm_r=(-0.05, 0.0, 0.42), fore_r=(-0.3, 0.0, 0.0),
               arm_l=(-0.1, 0.0, -0.5), fore_l=(-0.5, 0.0, 0.0)),
    # Hammerarm: senkrecht nach oben gestreckt, Rumpf 40° aufgedreht, Kopf schaut zum Ziel.
    'hammer_windup': _p(chest=(-0.15, 0.7, 0.0), head=(-0.05, -0.65, 0.0), arm_r=(-3.0, 0.0, 0.12),
                        fore_r=(-0.06, 0.0, 0.0), hammer=(0.0, 0.0, 0.0), arm_l=(-0.4, 0.0, -0.55),
                        fore_l=(-0.9, 0.0, 0.0)),
    # Dampf: vorgebeugt, die linke Hand an der Brust, Kopf in den Nacken.
    'steam_windup': _p(chest=(0.5, 0.0, 0.0), head=(-0.75, 0.0, 0.0), arm_r=(-0.2, 0.0, 0.5), fore_r=(-0.4, 0.0, 0.0),
                       arm_l=(-0.75, -0.55, -0.15), fore_l=(-1.85, 0.0, 0.0)),
    # Stoß: der Rumpf ruckt zurück, Kopf nach vorn, die Arme schleudern auseinander.
    'steam_hit': _p(body=(-0.06, 0.0, 0.0), chest=(-0.3, 0.0, 0.0), head=(0.25, 0.0, 0.0), arm_r=(0.1, 0.0, 0.7),
                    fore_r=(-0.2, 0.0, 0.0), arm_l=(0.05, 0.0, -0.75), fore_l=(-0.35, 0.0, 0.0)),
}
HAMMER_GRIP = (0, 17, 0)     # Mitte des Hammerblocks im Unterarm


def _hammer_hit():
    """Schlag: der Hammerblock liegt vor ihr auf dem Boden, Rumpf weit vorgebeugt und zurückgedreht."""
    p = _p(chest=(1.25, -0.3, 0.0), head=(-1.0, 0.2, 0.0), arm_l=(0.5, 0.0, -0.75), fore_l=(-0.4, 0.0, 0.0))
    _RIG.ik(p, 'arm_r', 'fore_r', HAMMER_GRIP, (-6.0, 18.0, -19.0), (-17.0, -4.0, -14.0))
    p['hammer'] = (0.0, 0.0, 0.0)
    _RIG.aim(p, 'hammer', (0, 1, 0), (0, 0, 1))                 # Schlagfläche flach auf dem Boden
    return p


POSES_RAW['hammer_hit'] = _hammer_hit()


def _partial(src, k, bones=None):
    """Zwischenstand eines Rucks: Anteil k der Pose src gegenüber der Statue (nur bones, sonst alle)."""
    st = POSES_RAW['statue']
    out = dict(st)
    for b in (bones or src):
        out[b] = tuple(s0 + (s1 - s0) * k for s0, s1 in zip(st[b], src[b]))
    return out


# Erwachen in Rucken: erst nur der Kopf, dann die Arme halb, dann ganz (Kotlin: WAKE_HEAD → WAKE_HALF → WAKE).
POSES_RAW['wake_head'] = _partial(POSES_RAW['wake'], 1.0, ['head', 'chest'])
POSES_RAW['wake_half'] = dict(_partial(POSES_RAW['wake'], 0.5), head=POSES_RAW['wake']['head'], chest=POSES_RAW['wake']['chest'])
POSES = {name: rounded(pose) for name, pose in POSES_RAW.items()}
_REST.update(POSES['statue'])
KOTLIN_POSES = ['idle', 'wake_head', 'wake_half', 'wake', 'hammer_windup', 'hammer_hit', 'steam_windup', 'steam_hit']
HEIGHT_EXCLUDE = ()


def kotlin_block():
    return kotlin_table(automaton(), POSES, ['statue'] + KOTLIN_POSES, {})


def variants(model, albedo, glow):
    """Schlafende Statue: gleiche Farbkarte, leere Leuchtmaske (die Augen sind dunkel, bis sie erwacht). Der Renderer
    wählt sie über `variant = { if (it.awake) null else "automaton_sleep" }`."""
    from texkit import Image, save
    save(albedo, 'textures/entity/boss/automaton_sleep.png')
    save(Image(model.tex_w, model.tex_h), 'textures/entity/boss/automaton_sleep_glow.png')


if __name__ == '__main__':
    if '--kotlin' in sys.argv:
        sync_kotlin('AUTOMATON', kotlin_block())
