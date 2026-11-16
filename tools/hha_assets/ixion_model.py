"""Ixion, ins brennende Rad gespannt: Modell, Textur, Leuchtmaske und Vorschau-Posen.

Teil von boss_models.py (Aufruf dort mit --only ixion). Brief: tmp/overhaul/models/ixion.md.

Er IST das Rad: ein schweres, geschwärztes Speichenrad (16 Felgensegmente, 8 Speichen), in das ein verkohlter Mann
mit Hand- und Fußgelenken X-förmig gespannt ist. Die vier Schellen leuchten (Ziel der Kampfmechanik), an der Nabe
züngeln zwei kurze Flammenflügel. Koordinaten wie überall: 1/16 Block, +y nach unten, -z ist vorn.

Aufbau (Knochen, Namen exakt wie im Brief und in IxionModel.kt):
    root (Boden, Schweben) > tilt (Radmitte, kippt) > wheel (dreht um die Achse)
        rim (Felge, Beschläge, Speichen; statisch), hub, flame_wing_r/_l (je 4 Platten wing_*_0..3),
        shackle_ar/_al/_lr/_ll (Anker an der Felge, Kettenstück, Schelle),
        body (Nabel = Radmitte) > head > jaw, arm_r > fore_r, arm_l > fore_l, leg_r > shin_r, leg_l > shin_l
"""
import math

from bosskit import BRONZE, Cube
from guardian_kit import glow
from modelkit import Model, Part, ramp, rgb, speckle

# ---------------------------------------------------------------------------------------------------------------------
# Paletten
# ---------------------------------------------------------------------------------------------------------------------

CHAR = rgb('#060403', '#120a07', '#22130c', '#361e12', '#4e2c1a')          # verkohltes Fleisch
IRON = rgb('#0a0806', '#1a1612', '#2e2820', '#4a4034', '#6e6250')          # geschwärztes Eisen
TEMPER = rgb('#120e1e', '#231c3c', '#38306a', '#55509a', '#7c78c0')        # Anlauffarben (blau-violett)
HOTIRON = rgb('#1e0804', '#4a1206', '#7a2408', '#a83c0c')                  # glühendes Eisen ohne Leuchten
EMBER = rgb('#1a0604', '#3c0c06', '#6a1a08', '#9a300c', '#c84a12')          # Flammenrand ohne Leuchten
LIGHT = {'top': 0.12, 'bottom': -0.2, 'front': 0.0, 'back': -0.08, 'left': -0.05, 'right': -0.05}

# Leuchtfarbe Feuer. Bewusst etwas röter als #ff8c32: Die Goldprüfung der Vorschau (Gold leuchtet nie) zählt jedes
# Orange mit Grün über 120 als Gold; #ff7426 liest sich im Spiel als Feuer und bleibt klar vom Gold getrennt.
HOT = glow(rgb('#5a1404', '#b8400c', '#ff8a2a', '#ffd890', '#fff4d0'), (255, 116, 38))


def grain(x, y, seed, amount=0.04):
    """Korn höchstens ± amount (Statuen-Ästhetik: glatte Flächen)."""
    return (speckle(x, y, seed) - 0.5) * 2 * amount


def vgrad(y, h, amount):
    """Oben heller, unten dunkler."""
    return (0.5 - y / max(h - 1, 1)) * amount


# ---------------------------------------------------------------------------------------------------------------------
# Maler
# ---------------------------------------------------------------------------------------------------------------------

def iron(base=0.42, seed=0, edge=0.16, seam=True):
    """Geschwärztes, geschmiedetes Eisen: Grat hell, Mitte dunkel, Fugen mit Schattensaum."""
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] + vgrad(y, h, 0.12) + grain(x, y, seed)
        if y == 0 or (face in ('top', 'bottom') and (x == 0 or x == w - 1)):
            v += edge
        if seam and face in ('front', 'back', 'top', 'bottom') and (x == 0 or x == w - 1) and w > 6:
            v -= edge * 1.4
        return ramp(IRON, v)
    return paint


ARCS = (22.5, 112.5, 202.5, 292.5)   # Mitte der vier Feuerbögen (Grad, 0 = oben, im Uhrzeigersinn von vorn)
ARC_HALF = 17.0                      # halbe Bogenweite; die Beschläge bei k * 45° bleiben frei


def rim_segment(ang, seed):
    """Felgensegment (Quader 15 x 5 x 6, außen = 'top', Mitte bei ang Grad).

    In den vier Feuerbögen läuft auf Vorder- und Rückseite eine durchgehende Glutrinne mit Flammenzungen; daneben
    geht das Eisen über Glührot in blau-violette Anlauffarben über. Außen: dunkle Lauffläche, über dem Bogen glührot.
    """
    cold = iron(0.48, seed, edge=0.18)
    r_mid = R_OUT - 2.5

    def heat_at(face, x, w):
        k = x + 0.5 - w / 2.0
        a = ang + math.degrees((k if face in ('front', 'top') else -k) / r_mid)
        best = 0.0
        for c in ARCS:
            d = abs((a - c + 180) % 360 - 180)
            best = max(best, 1.0 - d / ARC_HALF)
        return best, a

    def paint(face, x, y, w, h):
        if face not in ('front', 'back', 'top'):
            return cold(face, x, y, w, h)
        reach, a = heat_at(face, x, w)
        if face == 'top':
            if reach > 0.05 and 2 <= y <= 3:
                return ramp(HOTIRON, 0.4 + 0.4 * reach)
            return cold(face, x, y, w, h)
        if reach <= 0:
            return cold(face, x, y, w, h)
        mid = abs(y - (h - 1) / 2.0)                                     # 0 in der Rinnenmitte
        tongue = 0.5 + 0.5 * math.sin(a * 0.55 + (0 if y < 2 else 2.1))
        if reach > 0.08 and (mid < 0.5 or (mid < 1.5 and tongue * reach > 0.32)):
            t = 0.5 + 0.38 * min(1.0, reach * 1.5) + (0.08 if mid < 0.5 else -0.12)
            return HOT(t, 235)
        if mid < 2.0 and reach > 0.04:
            return ramp(HOTIRON, 0.35 + 0.45 * reach)
        heat = reach * (1.0 - mid / 3.0)
        if heat > 0.1:
            return ramp(TEMPER, 0.3 + heat * 0.6 + grain(x, y, seed + 3, 0.04))
        return cold(face, x, y, w, h)
    return paint


def spoke_paint(seed):
    """Speiche: geschmiedeter Vierkant, Grat hell; am Nabenende ein Bund."""
    def paint(face, x, y, w, h):
        v = 0.38 + LIGHT[face] + grain(x, y, seed)
        if face in ('front', 'back', 'left', 'right'):
            if x == 0:
                v += 0.14
            if x == w - 1:
                v -= 0.08
            v += 0.06 * (y / max(h - 1, 1))                             # außen etwas heller (Felge spiegelt)
        return ramp(IRON, v)
    return paint


def bronze(base=0.5, seed=0, rivets=False):
    """Matte, warme Bronze ohne Grünspan; auf Wunsch Nieten (2 x 2) auf den großen Seiten."""
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] * 1.2 + vgrad(y, h, 0.16) + grain(x, y, seed)
        if x == 0 or y == 0:
            v += 0.12
        if x == w - 1 or y == h - 1:
            v -= 0.1
        if rivets and face in ('front', 'back') and h >= 6 and w >= 4:
            rx = 1 <= x <= 2 or w - 3 <= x <= w - 2
            ry = 1 <= y <= 2 or h - 3 <= y <= h - 2
            if rx and ry:
                return ramp(BRONZE, 0.86 if (x + y) % 2 == 0 else 0.74)
        return ramp(BRONZE, v)
    return paint


def cuff_paint(seed):
    """Schelle 7 x 4 x 7: Bronzeränder oben und unten, dazwischen ein glühendes Band (das Ziel)."""
    rim = bronze(0.55, seed)

    def paint(face, x, y, w, h):
        if face in ('top', 'bottom'):
            # Ring: außen Bronze, darin ein glühender Rand, innen (wo das Gelenk steckt) dunkel.
            if 2 <= x <= w - 3 and 2 <= y <= h - 3:
                return ramp(CHAR, 0.2)
            if 1 <= x <= w - 2 and 1 <= y <= h - 2:
                return HOT(0.7, 240)
            return rim(face, x, y, w, h)
        if y == 0 or y == h - 1:
            return rim(face, x, y, w, h)
        edge = x == 0 or x == w - 1
        return HOT(0.72 + (0.0 if edge else 0.2) + grain(x, y, seed, 0.04), 255)
    return paint


def chain_link(seed):
    def paint(face, x, y, w, h):
        v = 0.36 + LIGHT[face] + grain(x, y, seed)
        if y == 0 or y == h - 1:
            v += 0.14
        return ramp(IRON, v)
    return paint


CHAR_LIFT = 0.08                    # hebt das Fleisch vom gleich dunklen Eisen ab


def charred(base=0.4, seed=0, cracks=(), ribs=False, arch=False):
    """Verkohltes Fleisch: fast schwarz, glatter Verlauf, harte Lichtkante oben.

    cracks: Liste von Glutrissen auf der Vorderseite, je ((x0, y0), (x1, y1), ...) in Flächen-Texeln. Ein Riss ist
    2 Texel breit und leuchtet, gesäumt von einem dunkelroten, nicht leuchtenden Rand.
    ribs: weiche Rippenschatten (Periode 3) auf Vorder- und Seitenflächen. arch: Rippenbogen (umgekehrtes V) unten.
    """
    def crack_dist(x, y):
        best = 99.0
        for path in cracks:
            for (ax, ay), (bx, by) in zip(path, path[1:]):
                dx, dy = bx - ax, by - ay
                L = dx * dx + dy * dy
                t = 0 if L == 0 else max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / L))
                px, py = ax + t * dx, ay + t * dy
                best = min(best, math.hypot(x - px, y - py))
        return best

    def paint(face, x, y, w, h):
        v = base + CHAR_LIFT + LIGHT[face] + vgrad(y, h, 0.14) + grain(x, y, seed)
        if y == 0 and face != 'bottom':
            v += 0.12                                                   # harte Lichtkante am Grat
        if face == 'front' and cracks:
            d = crack_dist(x, y)
            if d < 0.75:
                return HOT(0.72 + grain(x, y, seed + 5, 0.06), 230)
            if d < 1.3:
                return HOT(0.42, 170)
            if d < 2.2:
                return ramp(EMBER, 0.45)
        if ribs and face in ('front', 'left', 'right'):
            v += 0.07 * math.cos(2 * math.pi * y / 3.0)
        if arch and face == 'front':
            cx = (w - 1) / 2.0
            line = h - 1 - abs(x - cx) * 0.9                               # Rippenbogen: Spitze oben in der Mitte
            if y > line:
                v -= 0.12
            elif y > line - 1:
                v += 0.08
        return ramp(CHAR, v)
    return paint


def tattered_cloth(seed):
    """Verkohlter Stoffrest: unten ausgefranst (Löcher), Falten als weiche Bänder."""
    def paint(face, x, y, w, h):
        if face in ('front', 'back', 'left', 'right') and y >= h - 3:
            if y >= h - 3 + speckle(x, 0, seed) * 3.2:
                return None
        v = 0.3 + LIGHT[face] + vgrad(y, h, 0.12) + 0.05 * math.sin(x * 1.4 + seed) + grain(x, y, seed)
        return ramp(CHAR, v)
    return paint


def face_paint(face, x, y, w, h):
    """Schädel 9 x 7 x 9: glühende Augenschlitze, tiefe Augenhöhlen, verkohlt. Unterseite vorn = Gaumen (glüht)."""
    base = charred(0.42, 731)
    if face == 'bottom':
        if y < 5 and 1 <= x <= w - 2:                                   # Gaumen, sichtbar nur bei offenem Mund
            return HOT(0.55 + 0.08 * (y % 2), 220)
        return base(face, x, y, w, h)
    if face != 'front':
        return base(face, x, y, w, h)
    if y in (3, 4) and x in (1, 2, 6, 7):
        return HOT(0.95 if y == 3 else 0.75, 255)
    if y in (2, 5) and x in (1, 2, 6, 7):
        return ramp(CHAR, 0.04)                                         # Augenhöhle
    if y == 6 and 2 <= x <= 6:
        return ramp(CHAR, 0.08)                                         # Oberlippe/Zahnreihe im Schatten
    return base(face, x, y, w, h)


def jaw_paint(face, x, y, w, h):
    base = charred(0.36, 741)
    if face == 'top' and y < 4 and 1 <= x <= w - 2:                      # Zunge/Schlund
        return HOT(0.6 + 0.1 * (y % 2), 220)
    if face == 'front' and y == 0 and 1 <= x <= w - 2:
        return ramp(CHAR, 0.06)
    return base(face, x, y, w, h)


def hair_paint(seed):
    """Kurzes, verkohltes Haar: dunkle Strähnen als weiche Bänder, keine Flammen."""
    def paint(face, x, y, w, h):
        v = 0.28 + LIGHT[face] + vgrad(y, h, 0.1) + 0.05 * math.sin(x * 1.3 + y * 0.4 + seed) + grain(x, y, seed)
        return ramp(CHAR, v)
    return paint


def flame_plate(length, width, seed):
    """Flammenplatte (Quader length x width x 2, entlang +x): gezackter Umriss über Löcher, heißer Kern leuchtet."""
    def half(x):
        t = x / max(length - 1, 1)
        wave = 0.8 + 0.2 * math.sin(x * 0.6 + seed * 1.7)
        return (width / 2.0) * (1.0 - t ** 1.6) * wave + 0.35

    def inside(x, y):
        return abs(y + 0.5 - width / 2.0) <= half(x)

    def paint(face, x, y, w, h):
        if face in ('front', 'back'):
            fx = x if face == 'front' else w - 1 - x
            if not inside(fx, y):
                return None
            t = fx / max(length - 1, 1)
            core = abs(y + 0.5 - width / 2.0) / max(half(fx), 0.5)
            if t < 0.62 and core < 0.5:
                return HOT(0.85 - t * 0.5, 235)
            return ramp(EMBER, 0.75 - t * 0.5 - core * 0.15 + grain(x, y, seed, 0.04))
        if face in ('top', 'bottom'):
            row = 0 if face == 'top' else width - 1
            if not inside(x, row):
                return None
            return ramp(EMBER, 0.55 - x / max(length - 1, 1) * 0.4)
        if face == 'right':                                             # Wurzel an der Nabe
            return ramp(EMBER, 0.6)
        return None
    return paint


# ---------------------------------------------------------------------------------------------------------------------
# Geometrie
# ---------------------------------------------------------------------------------------------------------------------

R_OUT, R_IN = 37, 32               # Felge außen/innen
BODY_Z = -8.5                      # Körpermitte vor der Radebene (z -5 bis -12)
# Gelenke im Körperraum (Körperknochen sitzt in der Radmitte = Nabel).
SHOULDER = (6.5, -11.5)
HIP = (4.0, 5.0)
ARM_LEN, LEG_LEN = 15.0, 22.0
ANCHOR_R = 34.0                    # Ankeröse der Ketten auf der Felgen-Vorderseite

# Grundrotationen (Grad) der gespreizten Glieder; dieselben Zahlen stehen als Ausgangswerte in IxionModel.kt.
ARM_ROLL = 135.0                   # arm_r; arm_l = -ARM_ROLL (Arme 45° schräg nach oben)
LEG_ROLL = 45.0                    # leg_r; leg_l = -LEG_ROLL (Beine 45° schräg nach unten)

BASE = {}                          # Knochenname -> Grundrotation (Grad), für die Posen


def part(name, pivot, rot=(0, 0, 0), cubes=(), children=()):
    BASE[name] = rot
    return Part(name, pivot, rot, cubes, children)


def limb_dir(roll_deg):
    """Richtung von lokalem +y nach einer Drehung um z (wie ModelPart.roll)."""
    r = math.radians(roll_deg)
    return (-math.sin(r), math.cos(r))


def build_rim(eyes=()):
    children = list(eyes)
    for i in range(16):
        ang = 11.25 + i * 22.5                                          # Segmentmitte; Fugen bei k * 22,5°
        children.append(part(f'rim_{i}', (0, 0, 0), (0, 0, ang), cubes=[
            Cube(-7.5, -R_OUT, -3, 15, 5, 6, rim_segment(ang, 800 + i)),
        ]))
    for k in range(8):
        ang = k * 45.0
        cubes = [
            Cube(-1.5, -R_IN - 0.5, -1.5, 3, 26, 3, spoke_paint(820 + k)),          # Speiche r 6..32,5
            Cube(-2, -9.5, -2, 4, 3, 4, iron(0.46, 830 + k, seam=False)),            # Bund an der Nabe
            Cube(-2, -R_OUT - 1, -4, 4, 7, 8, bronze(0.5, 840 + k, rivets=True)),   # Beschlag um die Felge
        ]
        children.append(part(f'spoke_{k}', (0, 0, 0), (0, 0, ang), cubes=cubes))
    return part('rim', (0, 0, 0), children=children)


def build_hub():
    return part('hub', (0, 0, 0), cubes=[
        Cube(-5.5, -5.5, -4, 11, 11, 8, iron(0.44, 860, seam=False)),
        Cube(-3, -3, 4, 6, 6, 2, bronze(0.55, 861)),                     # Bronzekappe hinten
    ], children=[
        part('hub_b', (0, 0, 0), (0, 0, 45), cubes=[
            Cube(-5.5, -5.5, -3.5, 11, 11, 7, iron(0.4, 862, seam=False)),
            Cube(-2.5, -2.5, 4.5, 5, 5, 2, bronze(0.62, 863)),
        ]),
    ])


WING_PLATES = [   # (Neigung in Grad nach oben, Länge, Breite)
    (-50, 17, 7),
    (-28, 22, 8),
    (-6, 20, 7),
    (16, 16, 6),
]


def build_wing(side):
    s = 'r' if side < 0 else 'l'
    plates = []
    for i, (lift, length, width) in enumerate(WING_PLATES):
        # Platte wächst entlang +x; für die rechte Seite (−x) spiegelt die Drehung um y (180°) die Richtung.
        rot = (0, 0, lift) if side > 0 else (0, 180, -lift)
        plates.append(part(f'wing_{s}_{i}', (0, 0, 0), rot, cubes=[
            Cube(0, -width / 2.0, -1, length, width, 2, flame_plate(length, width, 870 + i + (side > 0) * 4)),
        ]))
    # Wurzel hinter der Felgenebene neben der Nabe, leicht nach hinten gefegt (Tiefe in der Seitenansicht).
    return part(f'flame_wing_{s}', (side * 5.0, -1.0, 6.0), (0, -side * 28.0, 0), children=plates)


def build_shackle(name, limb_roll, joint, length):
    """Schelle als eigener Knochen am Rad: Drehpunkt an der Ankeröse der Felge, lokales +y zeigt nach außen.

    Gelöst/gerissen dreht der Knochen um die Öse (die Schelle schwingt mit dem Kettenstück vom Gelenk weg).
    """
    dx, dy = limb_dir(limb_roll)
    wrist = (joint[0] + dx * length, joint[1] + dy * length)
    rc = math.hypot(*wrist)                                             # Radius der Schelle
    seed = 880 + len(BASE)
    yc = rc - ANCHOR_R                                                  # Schellenmitte lokal (negativ = innen)
    cubes = [
        Cube(-1, -2.5, -2.5, 2, 3, 2, chain_link(seed)),                # Glied an der Öse
        Cube(-1.5, -4.5, -4.5, 3, 3, 3, chain_link(seed + 1)),          # Querglied
        Cube(-3.5, yc - 2, -5 - 3.5, 7, 4, 7, cuff_paint(seed + 2)),    # die Schelle
    ]
    # Öse auf der Verlängerung des Glieds; ihre Bronzeplatte sitzt fest auf der Felge (Teil von rim).
    pivot = (wrist[0] + dx * (ANCHOR_R - rc), wrist[1] + dy * (ANCHOR_R - rc), -3.5)
    eye = part('eye_' + name[-2:], pivot, (0, 0, limb_roll), cubes=[
        Cube(-2.5, -2, -2, 5, 4, 2, bronze(0.62, seed + 3)),
    ])
    # Grundrotation: lokales +y zeigt wie das Glied nach außen.
    return part(name, pivot, (0, 0, limb_roll), cubes=cubes), eye


def build_body():
    # ---- Kopf: 9 x 10 x 9, in den Nacken geworfen, Mund zum Schrei geöffnet ----
    jaw = part('jaw', (0, -3, 1.5), (24, 0, 0), cubes=[
        Cube(-3.5, 0, -6, 7, 3, 7, jaw_paint),
        Cube(-2.5, 2, -6.5, 5, 2, 3, charred(0.4, 742)),                  # Kinn
    ])
    head = part('head', (0, -15, 0.5), (-26, 0, 0), cubes=[
        Cube(-4.5, -10, -4.5, 9, 7, 9, face_paint),
        Cube(-4.5, -7.5, -5.5, 9, 2, 2, charred(0.5, 732)),              # Stirnwulst
        Cube(-1, -6, -6, 2, 3, 2, charred(0.48, 733)),                   # Nase
        Cube(-4, -3.5, -4, 8, 2, 7, charred(0.38, 734)),                 # Wangen/Oberkiefer
        Cube(-5, -11, -5, 10, 3, 10, hair_paint(735)),                   # kurzes Haar oben
        Cube(-5, -9, 2.5, 10, 6, 3, hair_paint(736)),                    # Hinterkopf
    ], children=[jaw])

    def arm(side):
        s = 'r' if side < 0 else 'l'
        roll = ARM_ROLL if side < 0 else -ARM_ROLL
        fore = part(f'fore_{s}', (0, 7.5, 0), cubes=[
            Cube(-2, -0.5, -2, 4, 4, 4, charred(0.4, 760 + side)),
            Cube(-1.5, 3, -1.5, 3, 5, 3, charred(0.38, 762 + side)),
            Cube(-2, 7, -2, 4, 4, 3, charred(0.42, 764 + side)),          # Faust
            Cube(-2.5 if side > 0 else 0.5, 7.5, -2.5, 2, 2, 2, charred(0.4, 766 + side)),   # Daumen
        ])
        return part(f'arm_{s}', (side * SHOULDER[0], SHOULDER[1], 0), (0, 0, roll), cubes=[
            Cube(-2.5, -2, -2.5, 5, 4, 5, charred(0.46, 750 + side)),     # Schulter
            Cube(-2, 1, -2, 4, 7, 4, charred(0.42, 752 + side)),
            Cube(-1.5, 2, -3, 3, 4, 2, charred(0.48, 754 + side)),        # Bizeps, sehnig
        ], children=[fore])

    def leg(side):
        s = 'r' if side < 0 else 'l'
        roll = LEG_ROLL if side < 0 else -LEG_ROLL
        thigh_crack = [((1, 1), (2, 3), (1, 6))] if side < 0 else [((3, 0), (2, 3), (3, 6))]
        shin = part(f'shin_{s}', (0, 11, 0), cubes=[
            Cube(-2.5, -1, -2.5, 5, 3, 5, charred(0.44, 780 + side)),     # Knie
            Cube(-2, 1, -2, 4, 10, 4, charred(0.4, 782 + side)),
            Cube(-1.5, 2, 1, 3, 5, 2, charred(0.36, 786 + side)),         # Wade
            Cube(-2, 10.5, -3, 4, 5, 3, charred(0.38, 784 + side)),       # gestreckter Fuß
        ])
        return part(f'leg_{s}', (side * HIP[0], HIP[1], 0), (0, 0, roll), cubes=[
            Cube(-2.5, -1, -2.5, 5, 7, 5, charred(0.44, 770 + side, cracks=thigh_crack)),
            Cube(-2, 5, -2.2, 4, 6, 4.5, charred(0.42, 772 + side)),       # Oberschenkel verjüngt zum Knie
        ], children=[shin])

    rib_cracks = [((0, 4), (3, 5), (5, 7), (8, 8))]                    # quer über die Rippen nach unten
    pec_crack = [((1, 0), (2, 2), (4, 3))]                              # kurzer Riss im linken Brustmuskel
    return part('body', (0, 0, BODY_Z), cubes=[
        Cube(-6, 2, -3.5, 12, 6, 7, charred(0.4, 720)),                   # Becken
        Cube(-5, 4, -4.5, 10, 6, 2, tattered_cloth(725)),                 # verkohlter Lendenschurz
        Cube(-5, -4, -3, 10, 7, 6, charred(0.36, 721)),                   # Bauch, eingefallen
        Cube(-7, -12, -3.5, 14, 9, 7, charred(0.44, 722, cracks=rib_cracks, ribs=True, arch=True)),   # Brustkorb
        Cube(-6.5, -12.5, -4.5, 6, 4, 2, charred(0.5, 726)),              # Brustmuskel rechts
        Cube(0.5, -12.5, -4.5, 6, 4, 2, charred(0.5, 727, cracks=pec_crack)),   # Brustmuskel links
        Cube(-8, -14, -3, 16, 3, 6, charred(0.5, 723)),                   # Schulterlinie
        Cube(-2.5, -17, -2.5, 5, 4, 5, charred(0.42, 724)),               # Hals
    ], children=[head, arm(-1), arm(1), leg(-1), leg(1)])


def ixion():
    BASE.clear()
    shackles, eyes = [], []
    for name, roll, joint, length in (
        ('shackle_ar', ARM_ROLL, (-SHOULDER[0], SHOULDER[1]), ARM_LEN),
        ('shackle_al', -ARM_ROLL, (SHOULDER[0], SHOULDER[1]), ARM_LEN),
        ('shackle_lr', LEG_ROLL, (-HIP[0], HIP[1]), LEG_LEN),
        ('shackle_ll', -LEG_ROLL, (HIP[0], HIP[1]), LEG_LEN),
    ):
        p, eye = build_shackle(name, roll, joint, length)
        shackles.append(p)
        eyes.append(eye)
    wheel = part('wheel', (0, 0, 0), children=[
        build_rim(eyes), build_hub(), build_wing(-1), build_wing(1), *shackles, build_body(),
    ])
    tilt = part('tilt', (0, -R_OUT, 0), children=[wheel])
    root = part('root', (0, 24, 0), children=[tilt])
    return Model('ixion', 512, 128, [root])


# ---------------------------------------------------------------------------------------------------------------------
# Posen (Radiant, absolute Knochendrehung). Werte entstehen aus Grundrotation + denselben Abweichungen wie in Kotlin.
# ---------------------------------------------------------------------------------------------------------------------

def _pose(**deltas):
    """Abweichungen (pitch, yaw, roll[, dx, dy, dz]) je Knochen -> absolute Pose (Grundrotation + Abweichung)."""
    if not BASE:
        ixion()
    out = {}
    for name, d in deltas.items():
        if d is None:
            out[name] = None
            continue
        b = [math.radians(a) for a in BASE.get(name, (0, 0, 0))]
        val = (b[0] + d[0], b[1] + d[1], b[2] + d[2])
        if len(d) >= 6:
            val = val + tuple(d[3:6])
        out[name] = val
    return out


# Schlüsselwerte, identisch mit IxionModel.kt (dort als Konstanten im companion object).
HOVER = -4.0                       # Ruhe: Felge schwebt 4 Einheiten über dem Boden
ROLL_BACK = -0.26                  # ROLL_WINDUP: das Rad rollt 15° zurück, bevor es anzieht
FALL_BODY = (0.0, 39.5, 2.5)       # FALLEN: Versatz des Körpers (Nabel liegt vorn über der Felgenkante)
FALL_DY = 33.5                     # FALLEN: Radmitte 3,5 über dem Boden
SPOKES_DY = -6.0                   # SPOKES: steigt etwas höher


def wobble_dy(tilt):
    """Senkt das gekippte Rad so weit, dass die Felge den Boden berührt (wie eine auslaufende Münze)."""
    return 36.0 - 37.0 * math.cos(tilt) - 3.0 * math.sin(tilt)


def wings(fold=0.0, spread=0.0):
    """fold: anlegen (Spitzen nach hinten), spread: ausbreiten (Fächer auf, flach). Werte wie in Kotlin."""
    out = {}
    for side, s in ((-1, 'r'), (1, 'l')):
        out[f'flame_wing_{s}'] = (0, side * (-0.75 * fold + 0.45 * spread), side * (-0.25 * fold))
        for i in range(4):
            fan = (i - 1.5) * 0.16 * spread - 0.12 * fold * (i - 1.5)
            out[f'wing_{s}_{i}'] = (0, 0, side * fan)
    return out


def _poses():
    p = {}
    p['idle'] = _pose(root=(0, 0, 0, 0, HOVER, 0), head=(0, 0.1, 0.12), **wings())
    p['roll_windup'] = _pose(
        root=(0, math.pi / 2, 0, 0, -1.0, 0), wheel=(0, 0, ROLL_BACK), body=(0, 0, 0, 0, 0, 1.0),
        head=(0.45, 0, 0), jaw=(-0.3, 0, 0),
        arm_r=(0.15, 0, -0.06), arm_l=(0.15, 0, 0.06), leg_r=(0.12, 0, 0.05), leg_l=(0.12, 0, -0.05),
        fore_r=(0.1, 0, 0), fore_l=(0.1, 0, 0), **wings(fold=1.0))
    p['roll'] = _pose(
        root=(0, math.pi / 2, 0, 0, -1.5, 0), wheel=(0, 0, 2.1), body=(0, 0, 0, 0, 0, 1.0),
        head=(0.3, 0, 0), jaw=(0.3, 0, 0),
        arm_r=(0.15, 0, -0.06), arm_l=(0.15, 0, 0.06), leg_r=(0.12, 0, 0.05), leg_l=(0.12, 0, -0.05),
        **wings(fold=1.0))
    t = math.radians(65)
    p['wobble'] = _pose(
        root=(0, math.pi / 2 + 0.4, 0, 0, wobble_dy(t), 0), tilt=(-t, 0, 0), wheel=(0, 0, 0.5),
        head=(0.55, 0, 0.4), jaw=(0.35, 0, 0), arm_r=(-0.1, 0, 0.1), arm_l=(-0.1, 0, -0.1),
        fore_r=(0.45, 0, 0), fore_l=(0.4, 0, 0), shin_r=(0.3, 0, 0), shin_l=(0.25, 0, 0),
        **wings(fold=0.4))
    for name, spin, look in (('spokes_windup', 0.0, -0.3), ('spokes', 1.2, -0.3)):
        p[name] = _pose(
            root=(0, 0, 0, 0, SPOKES_DY, 0), tilt=(-math.pi / 2, 0, 0), wheel=(0, 0, spin),
            head=(look, 0, 0), jaw=(0.3, 0, 0), arm_r=(-0.12, 0, 0.08), arm_l=(-0.12, 0, -0.08),
            **wings(spread=1.0))
    p['sparks_windup'] = _pose(
        root=(0, 0, 0, 0, HOVER, 0), tilt=(-0.17, 0, 0), body=(-0.08, 0, 0, 0, 0, -1.0), head=(-0.45, 0, 0), jaw=(-0.35, 0, 0),
        arm_r=(0.1, 0, 0), arm_l=(0.1, 0, 0), **wings(spread=0.6))
    p['sparks_spit'] = _pose(
        root=(0, 0, 0, 0, HOVER, 0), tilt=(0.1, 0, 0), body=(0.12, 0, 0, 0, 0, -2.5), head=(0.75, 0, 0),
        jaw=(0.55, 0, 0), arm_r=(-0.12, 0, 0), arm_l=(-0.12, 0, 0), fore_r=(0.2, 0, 0), fore_l=(0.2, 0, 0),
        **wings(spread=0.7))
    fall = dict(
        root=(0, 0, 0, 0, FALL_DY, 0), tilt=(-math.pi / 2, 0, 0),
        body=(math.pi, 0, 0) + FALL_BODY, head=(-1.15, 0, 0), jaw=(0.4, 0, 0),
        shackle_ar=(-0.6, 0, 0.3), shackle_al=(-0.6, 0, -0.3),
        flame_wing_r=None, flame_wing_l=None)                         # die Flammen erlöschen (Kotlin: Skalierung 0)
    p['fallen'] = _pose(**fall)
    # Nur für die Vorschau „von oben“ (final_tilt.png): dieselbe Pose, root zusätzlich 90° nach vorn gedreht.
    for name in ('spokes', 'wobble'):
        top = dict(p[name])
        r = top['root']
        top['root'] = (math.pi / 2, 0.0, 0.0) + tuple(r[3:6])
        p[name + '_top'] = top
    return p


def _absolute_fallen(p):
    """FALLEN: Arme und Beine absolut setzen (die Glieder lösen sich ganz aus dem X)."""
    f = p['fallen']
    f['arm_r'] = (-math.pi / 2, 0, -0.15)
    f['arm_l'] = (-math.pi / 2, 0, 0.15)
    f['fore_r'] = (-math.pi / 2 + 0.1, 0.0, 0.0)
    f['fore_l'] = (-math.pi / 2 + 0.1, 0.0, 0.0)
    f['leg_r'] = (0.12, 0, 0.93)
    f['leg_l'] = (0.12, 0, -0.93)
    f['shin_r'] = (0.1, 0, 0)
    f['shin_l'] = (0.1, 0, 0)
    return p


POSES = _absolute_fallen(_poses())
