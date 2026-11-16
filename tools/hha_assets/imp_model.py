"""Das Teufelchen: ein geduckter Aschekobold des Tartaros (Brief tmp/overhaul/models/teufelchen.md, Registry `imp`).

Erkennungsmerkmal: riesiger Kopf mit Fledermausohren, glühender Bauch, Hakenschwanz. Im Dunkeln liest man ihn an
drei Glutpunkten (zwei Augen, Bauch); seine Flughäute glimmen zum Körper hin von innen.

Vorn ist -z, +y nach unten, die Füße stehen bei y = 24, die Ohrspitzen bei y = -14 (38 Einheiten, Renderer-Faktor
0,40 → 0,95 Blöcke). Knochen (client/model/ImpModel.kt):

    body
    ├─ head ── jaw, ear_r, ear_l, brow_r, brow_l, horn_r, horn_l
    ├─ arm_r ── fork, ember (── ember_spin)
    ├─ arm_l
    ├─ leg_r, leg_l
    ├─ wing_r ── wing_r_tip ── wing_r_f1, wing_r_f2, wing_r_f3
    ├─ wing_l ── wing_l_tip ── wing_l_f1, wing_l_f2, wing_l_f3
    └─ tail_0 ── tail_1 ── tail_2

`python tools/hha_assets/imp_model.py --kotlin` schreibt die Posen-Tabelle (POSES) in den markierten Block von
ImpModel.kt, damit Vorschau und Spiel dieselben Winkel benutzen.
"""
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from bosskit import BONE, Cube  # noqa: E402
from modelkit import Cube as RawCube, Model, Part, hexc, mix, ramp, rgb, speckle  # noqa: E402

# --- Materialien (Brief: Aschehaut, Flughaut, Horn/Kralle/Gabel) ---------------------------------------------------
ASH = rgb('#0e0c0c', '#2e2826', '#5a4e48', '#8a7a70')
MEMBRANE = rgb('#2a0a06', '#521408', '#7a1c0c', '#a8320f', '#c2441a')
IRON = rgb('#0b0b10', '#181a22', '#2a2d38', '#444a58', '#6c7486')
MOUTH = rgb('#120304', '#2c0808', '#4e1010', '#7a2018')
# Glut. #ff7a2a aus dem Brief liegt mit g = 122 knapp im Gold-Prüfbereich der Vorschau (g > 120); eine Spur röter.
GLOW = (255, 112, 40)
GLOW_HOT = (255, 120, 64)
GLOW_ALBEDO = hexc('#ffb070')
HEAT = hexc('#c2441a')

FACE_LIGHT = {'top': 0.12, 'bottom': -0.2, 'front': 0.0, 'back': -0.08, 'left': -0.05, 'right': -0.05}


def glow(strength=255, hot=False, albedo=GLOW_ALBEDO):
    c = GLOW_HOT if hot else GLOW
    return (albedo, (c[0], c[1], c[2], strength))


def ash(base=0.42, seed=0, grad=0.16, rim=0.1, soot=True):
    """Aschehaut: glatter Verlauf von oben hell nach unten dunkel, große rußige Flecken, heller Grat oben,
    dunkler Saum unten."""
    def paint(face, x, y, w, h):
        g = grad if face not in ('top', 'bottom') else grad * 0.3
        v = base + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * g
        v += (speckle(x, y, seed) - 0.5) * 0.05
        if soot:
            s = speckle(x // 2 + 11, y // 2 + 7, seed + 5)
            if s > 0.6:
                v -= (s - 0.6) * 0.55
        if face not in ('top', 'bottom') and h > 2:
            if y == 0:
                v += rim
            elif y == h - 1:
                v -= rim * 0.8
        return ramp(ASH, v)
    return paint


def horn(base=0.6, seed=0):
    """Knochenbleiche Hörnchen und Krallen, zur Spitze heller."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] * 1.2 + (0.5 - y / max(h - 1, 1)) * 0.2 + (speckle(x, y, seed) - 0.5) * 0.06
        return ramp(BONE, v)
    return paint


def iron(base=0.42, seed=0, edge=0.16):
    """Geschwärztes Eisen: dunkle Mitte, heller Grat an den Kanten."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] * 1.4 + (0.5 - y / max(h - 1, 1)) * 0.1 + (speckle(x, y, seed) - 0.5) * 0.06
        if w > 2 and (x == 0 or x == w - 1):
            v += edge
        return ramp(IRON, v)
    return paint


def iron_tip(face, x, y, w, h):
    """Zinkenspitze: blank geschliffen (kein Leuchten)."""
    return ramp(IRON, 0.9 + FACE_LIGHT[face])


# --- Kopf ----------------------------------------------------------------------------------------------------------
EYE_COLS = ((2, 3, 4), (9, 10, 11))   # Spalten der Augen auf der Schädelfront (14 breit)
EYE_ROWS = (4, 5, 6)                   # Zeilen (Schädel y -9 … 0)


def cranium(face, x, y, w, h):
    base = ash(0.5, 501)
    if face == 'front':
        for cols in EYE_COLS:
            if x in cols and y in EYE_ROWS:
                hot = x == cols[1] and y == 5
                return glow(255, hot, hexc('#ffd0a0') if hot else GLOW_ALBEDO)
            # dunkle Augenhöhle um das Auge
            if cols[0] - 1 <= x <= cols[2] + 1 and EYE_ROWS[0] - 1 <= y <= EYE_ROWS[2] + 1:
                return ramp(ASH, 0.12)
        # breites Grinsen: Lippenlinie, Zahnreihe oben, Mundwinkel nach oben gezogen
        if y == h - 1 and 1 <= x <= w - 2:
            return ramp(BONE, 0.82) if (x % 3) != 0 else ramp(MOUTH, 0.1)
        if y == h - 2 and (x in (1, w - 2)):
            return ramp(MOUTH, 0.15)
        if y == h - 3 and (x in (0, w - 1)):
            return ramp(MOUTH, 0.2)
    if face == 'bottom':
        # Gaumen: vorn die obere Zahnreihe, dahinter dunkles Maul
        if y == 0:
            return ramp(BONE, 0.85)
        if y < 10:
            return ramp(MOUTH, 0.35 - y * 0.03)
    return base(face, x, y, w, h)


def jaw_paint(face, x, y, w, h):
    base = ash(0.4, 502)
    if face == 'front':
        if y == 0:
            return ramp(BONE, 0.85) if (x % 3) != 1 else ramp(MOUTH, 0.1)
        return base(face, x, y, w, h)
    if face == 'top':
        if y == 0:
            return ramp(BONE, 0.8)
        if 2 <= x <= w - 3 and y < 7:
            return ramp(MOUTH, 0.75 - y * 0.06)   # Zunge
        return ramp(MOUTH, 0.18)
    return base(face, x, y, w, h)


def inner_ear(seed):
    """Ohr: vorn die rotbraune Ohrmuschel mit Aschekante, sonst Haut, zur Spitze dunkler."""
    skin = ash(0.4, seed)

    def paint(face, x, y, w, h):
        if face == 'front' and 0 < y < h - 1:
            return ramp(MEMBRANE, 0.62 - abs(y - (h - 1) / 2) / h * 0.7)
        return skin(face, x, y, w, h)
    return paint


def ear_tip(side):
    """Ohrspitze mit Kerbe: ein V von oben in die äußere Kante geschnitten (dunkel, die Kante bleibt hell)."""
    skin = ash(0.3, 503)

    def paint(face, x, y, w, h):
        outer = (x == 0) if side < 0 else (x == w - 1)
        if face in ('front', 'back') and outer and y == 0:
            return ramp(ASH, 0.02)
        if face == ('right' if side < 0 else 'left') and y == 0:
            return ramp(ASH, 0.02)
        return skin(face, x, y, w, h)
    return paint


# --- Bauch ---------------------------------------------------------------------------------------------------------
def _star(w, h):
    """Sternförmige Glutrisse (11 Texel) um die Mitte der Bauchplatte: vier gezackte Strahlen."""
    cx, cy = w // 2, h // 2
    rel = [(0, 0), (-1, 0), (-2, 0), (-3, -1), (1, 0), (2, 0), (3, 1), (0, -1), (1, -2), (0, 1), (-1, 2)]
    return {(cx + dx, cy + dy) for dx, dy in rel}


def belly_front(face, x, y, w, h):
    skin = ash(0.5, 504, rim=0.05)
    if face != 'front':
        return skin(face, x, y, w, h)
    star = _star(w, h)
    if (x, y) in star:
        hot = (x, y) == (w // 2, h // 2)
        return glow(255, hot, hexc('#ffc890') if hot else GLOW_ALBEDO)
    # Wärmehof um die Risse (kein Leuchten): die Haut ist dort dünn und glutrot
    d = min(abs(x - px) + abs(y - py) for px, py in star)
    c = skin(face, x, y, w, h)
    if d == 1:
        return mix(c, HEAT, 0.55)
    if d == 2:
        return mix(c, HEAT, 0.22)
    return c


def wing_bone(seed):
    """Flügelknochen: Asche, mit der Flughaut dunkelrot unterlaufen; Oberkante als heller Grat."""
    skin = ash(0.36, seed, rim=0.14)

    def paint(face, x, y, w, h):
        return mix(skin(face, x, y, w, h), ramp(MEMBRANE, 0.3), 0.3)
    return paint


# --- Flughaut -----------------------------------------------------------------------------------------------------
def _scallop_inside(p, a, b, bow):
    """Liegt p auf der Gelenkseite der Sehne a→b, mit einem nach innen gewölbten Bogen (Tiefe bow)?"""
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    ln2 = dx * dx + dy * dy
    s = ((p[0] - ax) * dx + (p[1] - ay) * dy) / ln2
    s = max(0.0, min(1.0, s))
    # Normale zur Mitte (0, 0) hin
    nx, ny = -dy, dx
    if nx * (0 - ax) + ny * (0 - ay) < 0:
        nx, ny = -nx, -ny
    ln = math.sqrt(ln2)
    d = ((p[0] - ax) * nx + (p[1] - ay) * ny) / ln
    return d >= bow * 4 * s * (1 - s), d - bow * 4 * s * (1 - s)


def membrane_paint(x0, y0, w, mirror, inside, heat):
    """Maler für eine 0-dicke Flughaut-Ebene. inside(u, v) → (drin?, Abstand zur Hinterkante); u läuft vom Gelenk
    nach außen, v nach unten. heat(u, v) → 0..1 Glutanteil (zum Körper hin)."""
    def paint(face, i, j, fw, fh):
        if face == 'front':
            px = x0 + i + 0.5
        elif face == 'back':
            px = x0 + w - i - 0.5
        else:
            return None
        u = px if mirror else -px
        v = y0 + j + 0.5
        ok, edge = inside(u, v)
        if not ok:
            return None
        t = heat(u, v)
        # Spannfalten: weiche, vom Gelenk ausstrahlende Wellen (kein Rauschen)
        fold = math.sin(math.atan2(v, max(u, 0.01)) * 9.0) * 0.035
        val = 0.3 + 0.7 * t + fold + (speckle(i, j, 505) - 0.5) * 0.04
        if face == 'back':
            val -= 0.06
        if edge < 1.0:
            val -= 0.16   # dunkler Saum an der gezackten Hinterkante
        return ramp(MEMBRANE, val)
    return paint


WING_IN = 8          # Länge des inneren Flügelglieds (Wurzel → Handgelenk)
FINGERS = ((24, 14), (-38, 14), (-82, 11))   # (Rollwinkel rechts in Grad, Länge) der drei Fingerknochen


def _finger_tips():
    tips = []
    for deg, length in FINGERS:
        a = math.radians(deg)
        tips.append((length * math.cos(a), -length * math.sin(a)))   # (u, v): u nach außen, v nach unten
    return tips


def wing(side, s):
    """Fledermausflügel in zwei Gliedern. side = -1 rechts (x negativ), +1 links."""
    mirror = side > 0
    t1, t2, t3 = _finger_tips()

    def inner_inside(u, v):
        if v < 0 or u < 0:
            return False, 0
        if u > WING_IN + 0.5 + max(v, 0) * math.tan(math.radians(10)):
            return False, 0
        return _scallop_inside((u, v), (0, 12.5), (WING_IN + t3[0], t3[1]), 2.6)

    def inner_heat(u, v):
        return max(0.0, 1.0 - u / 8.0) ** 1.3 * 0.9 + 0.1

    def tip_inside(u, v):
        ang = math.degrees(math.atan2(v, u))
        if ang < -FINGERS[0][0] - 1 or ang > -FINGERS[2][0] + 1:
            return False, 0
        if ang <= -FINGERS[1][0]:
            return _scallop_inside((u, v), t1, t2, 2.6)
        return _scallop_inside((u, v), t2, t3, 2.6)

    def tip_heat(u, v):
        return max(0.0, 0.3 - math.hypot(u, v) / 45)

    skin_bar = wing_bone(506)
    inner_x0 = 0 if mirror else -(WING_IN + 2)
    tip_x0 = 0 if mirror else -15
    fingers = []
    for k, (deg, length) in enumerate(FINGERS):
        roll = -deg if mirror else deg
        if mirror:
            cubes = [Cube(0, -1.5, -1.5, length - 2, 3, 3, wing_bone(510 + k)),
                     Cube(length - 2, -1, -1, 2, 2, 2, ash(0.2, 513, rim=0))]
        else:
            cubes = [Cube(-(length - 2), -1.5, -1.5, length - 2, 3, 3, wing_bone(510 + k)),
                     Cube(-length, -1, -1, 2, 2, 2, ash(0.2, 513, rim=0))]
        fingers.append(Part(f'wing_{s}_f{k + 1}', (0, 0, 0), (0, 0, roll), cubes=cubes))

    def glow_strip(paint):
        """Schmaler Glutstreifen an der Flügelwurzel (Spalte am Körper)."""
        def wrapped(face, i, j, fw, fh):
            c = paint(face, i, j, fw, fh)
            if c is None:
                return None
            col = (fw - 1 - i) if not mirror else i
            if face == 'back':
                col = i if not mirror else (fw - 1 - i)
            if col == 0 and 2 <= j <= 10:
                return (mix(c, HEAT, 0.6), (GLOW[0], GLOW[1], GLOW[2], 210 - abs(j - 6) * 12))
            return c
        return wrapped

    inner_paint = glow_strip(membrane_paint(inner_x0, 0, WING_IN + 2, mirror, inner_inside, inner_heat))
    bar_x = 0 if mirror else -WING_IN
    tip = Part(f'wing_{s}_tip', (side * WING_IN, 0, 0), (0, side * -6, 0), cubes=[
        RawCube(tip_x0, -8, 0, 15, 19, 0, membrane_paint(tip_x0, -8, 15, mirror, tip_inside, tip_heat)),
        Cube(-1.5, -2, -2, 3, 4, 4, ash(0.36, 516)),   # Handgelenk
    ], children=fingers)
    return Part(f'wing_{s}', (side * 2.5, -6, 4), (0, side * -22, side * -12), cubes=[
        Cube(bar_x, -1.5, -1.5, WING_IN, 3, 3, skin_bar),
        RawCube(inner_x0, 0, 0, WING_IN + 2, 13, 0, inner_paint),
    ], children=[tip])


class SkylineModel(Model):
    """Model mit dichterem Atlas, damit 128 x 128 reicht:

    * Quader, deren Textur Texel für Texel gleich ist (linke und rechte Gliedmaßen mit demselben Maler), teilen sich
      einen Platz im Atlas;
    * Skyline-Packer (jeder Quader an die niedrigste freie Stelle) statt Regalreihen."""

    @staticmethod
    def _signature(c):
        w, h, d = c.w, c.h, c.d
        faces = (('top', w, d), ('bottom', w, d), ('right', d, h), ('front', w, h), ('left', d, h), ('back', w, h))
        return (w, h, d, tuple(tuple(c.paint(n, x, y, fw, fh) for y in range(fh) for x in range(fw)) for n, fw, fh in faces))

    def pack(self):
        unique, shared = {}, []
        for c in self.cubes():
            key = self._signature(c)
            if key in unique:
                shared.append((c, unique[key]))
            else:
                unique[key] = c
        cubes = sorted(unique.values(), key=lambda c: (-c.uv_size[1], -c.uv_size[0]))
        sky = [0] * self.tex_w
        for c in cubes:
            cw, ch = c.uv_size[0] + 1, c.uv_size[1] + 1
            best = None
            for x in range(0, self.tex_w - cw + 2):
                y = max(sky[x:min(x + cw, self.tex_w)])
                if best is None or y < best[1]:
                    best = (x, y)
            x, y = best
            if y + ch - 1 > self.tex_h:
                raise SystemExit(f'Atlas {self.name} zu klein ({self.tex_w}x{self.tex_h})')
            c.u, c.v = x, y
            for i in range(x, min(x + cw, self.tex_w)):
                sky[i] = y + ch
        for c, twin in shared:
            c.u, c.v = twin.u, twin.v
        print(f'{self.name}: {len(cubes) + len(shared)} Quader ({len(shared)} teilen Texturen), '
              f'Atlas belegt bis y={max(sky)}/{self.tex_h}')


# --- Gesamtmodell --------------------------------------------------------------------------------------------------
def imp():
    # Kopf: Drehpunkt am Hals, absolut (0, 2, -1). Würfel unten in Kopf-Koordinaten (absolut minus Drehpunkt).
    hy, hz = 2, -1

    def H(x0, y0, z0, x1, y1, z1, paint):
        return Cube(x0, y0 - hy, z0 - hz, x1 - x0, y1 - y0, z1 - z0, paint)

    jaw = Part('jaw', (0, 0 - hy, 3 - hz), cubes=[
        Cube(-7, 0, -10, 14, 3, 11, jaw_paint),
        Cube(-5, 2, -9, 10, 2, 8, ash(0.32, 520)),
    ])

    def ear(side, s):
        ext = side   # +1 links (x+), -1 rechts (x-)

        def X(a, b):
            return (a, b) if ext > 0 else (-b, -a)
        cubes = []
        for (a, b), (y0, y1), paint in (
            ((0, 4), (-3, 3), inner_ear(521)),
            ((4, 7), (-3, 2), inner_ear(522)),
            ((7, 10), (-3.5, -0.5), ear_tip(side)),
        ):
            xa, xb = X(a, b)
            cubes.append(Cube(xa, y0, -1.5, xb - xa, y1 - y0, 3, paint))
        return Part(f'ear_{s}', (side * 7.5, -6 - hy, 0.5 - hz), (0, side * -12, side * -40), cubes=cubes)

    def brow(side, s):
        return Part(f'brow_{s}', (side * 3.5, -5.6 - hy, -7.2 - hz), (0, 0, side * -16), cubes=[
            Cube(-3, -1, -1, 6, 2, 2, ash(0.3, 523, rim=0.18)),
        ])

    def horn_stub(side, s):
        return Part(f'horn_{s}', (side * 3.5, -8.5 - hy, -2 - hz), (-28, 0, side * 22), cubes=[
            Cube(-1.5, -4, -1.5, 3, 4, 3, horn(0.32, 524)),
        ])

    head = Part('head', (0, hy - 10, hz), cubes=[
        H(-7, -9, -7, 7, 0, 6, cranium),
        H(-8, -7, -6, 8, -1, 5, ash(0.48, 526)),
        H(-1.5, -3.5, -9, 1.5, -1.5, -7, ash(0.55, 527, rim=0.15)),   # Stupsnase
    ], children=[jaw, ear(-1, 'r'), ear(1, 'l'), brow(-1, 'r'), brow(1, 'l'), horn_stub(-1, 'r'), horn_stub(1, 'l')])

    # Rechte Hand: Zweizack (Hades' Zeichen) und Glutball. Gabel aufrecht, Zinken nach oben (-y).
    fork = Part('fork', (0, 13, -0.5), (12, 0, -24), cubes=[
        Cube(-1.5, -11, -1.5, 3, 14, 3, iron(0.42, 530)),
        Cube(-4.5, -13, -1.5, 9, 3, 3, iron(0.55, 531)),
        Cube(-4.5, -18, -1.5, 3, 5, 3, iron(0.55, 532)),
        Cube(1.5, -18, -1.5, 3, 5, 3, iron(0.55, 532)),
        Cube(-4, -20, -1, 2, 2, 2, iron_tip),
        Cube(2, -20, -1, 2, 2, 2, iron_tip),
    ])

    def ember_paint(face, x, y, w, h):
        return glow(255, x == w // 2 and y == h // 2, hexc('#ffd8a0'))

    ember = Part('ember', (0, 19.5, -1.5), cubes=[Cube(-1.5, -1.5, -1.5, 3, 3, 3, ember_paint, dilate=1.2)], children=[
        Part('ember_spin', (0, 0, 0), (45, 45, 0), cubes=[Cube(-1.5, -1.5, -1.5, 3, 3, 3, ember_paint, dilate=0.5)]),
    ])

    def arm(side, s):
        children = [fork, ember] if s == 'r' else []
        return Part(f'arm_{s}', (side * 5.5, 3.5 - 10, 0), (0, 0, side * -16), cubes=[
            Cube(-1.5, -1.5, -1.5, 3, 7, 3, ash(0.42, 540)),
            Cube(-1.5, 5.5, -2.5, 3, 6, 3, ash(0.36, 541)),
            Cube(-2.5, 11.5, -3, 5, 3, 5, ash(0.3, 542)),
            Cube(-2.5, 14.5, -3, 5, 3, 3, hand_fingers),
        ], children=children)

    def leg(side, s):
        return Part(f'leg_{s}', (side * 3.5, 13 - 10, 0), (0, 0, side * -10), cubes=[
            Cube(-2, -1, -3, 4, 5, 4, ash(0.4, 545)),
            Cube(-2, 3.5, -1, 4, 5, 4, ash(0.34, 546)),
            Cube(-2.5, 8, -5, 5, 3, 7, foot),
        ])

    tail = Part('tail_0', (0, 14 - 10, 3.5), (-35, 0, 0), cubes=[Cube(-1.5, -1.5, 0, 3, 3, 7.5, ash(0.38, 550))], children=[
        Part('tail_1', (0, 0, 7), (25, 0, 0), cubes=[Cube(-1.5, -1.5, 0, 3, 3, 7.5, ash(0.34, 551))], children=[
            Part('tail_2', (0, 0, 7), (50, 0, 0), cubes=[
                Cube(-1.5, -1.5, 0, 3, 3, 5, ash(0.3, 552)),
                Cube(-1.5, -3, 4, 3, 6, 2, barb(0.45)),
                Cube(-1.5, -2, 6, 3, 4, 2, barb(0.55)),
                Cube(-1, -1, 8, 2, 2, 2, barb(0.65)),
                Cube(-1.5, -3.5, 2.5, 3, 2, 2, barb(0.4)),
                Cube(-1.5, 1.5, 2.5, 3, 2, 2, barb(0.4)),
            ]),
        ]),
    ])

    def B(x0, y0, z0, x1, y1, z1, paint):
        return Cube(x0, y0 - 10, z0, x1 - x0, y1 - y0, z1 - z0, paint)

    body = Part('body', (0, 10, 0), cubes=[
        B(-6, 8, -5, 6, 15, 4, ash(0.42, 560)),
        B(-5, 7, -4, 5, 16, 3, ash(0.4, 561)),
        B(-4.5, 9, -6, 4.5, 14, -4, belly_front),
        B(-4.5, 2, -3, 4.5, 8, 4, ash(0.46, 562)),
        B(-6.5, 2.5, -1.5, -3.5, 5.5, 1.5, ash(0.5, 563)),
        # (Schulterkappen: gleicher Maler, teilen sich den Atlasplatz)
        B(3.5, 2.5, -1.5, 6.5, 5.5, 1.5, ash(0.5, 563)),
        B(-2.5, 0, -2.5, 2.5, 3, 1.5, ash(0.36, 565)),
        B(-3.5, 3, 2, 3.5, 8, 5, ash(0.44, 566)),
    ], children=[head, arm(-1, 'r'), arm(1, 'l'), leg(-1, 'r'), leg(1, 'l'), wing(-1, 'r'), wing(1, 'l'), tail])
    return SkylineModel('imp', 128, 128, [body])


def hand_fingers(face, x, y, w, h):
    """Drei dicke Finger mit Krallen: weiche Fugen zwischen den Fingern, bleiche Krallen in der untersten Reihe."""
    if face in ('front', 'back') and x in (1, 3):
        return ramp(ASH, 0.14)
    if face in ('front', 'back', 'bottom') and (y == h - 1 or face == 'bottom'):
        return ramp(BONE, 0.34)
    return ash(0.3, 543)(face, x, y, w, h)


def foot(face, x, y, w, h):
    """Krallenfuß: drei Zehen (weiche Fugen), bleiche Krallen nur an den Zehenspitzen, dunkle Sohle."""
    skin = ash(0.3, 547)
    if face == 'front':
        if x in (1, 3):
            return ramp(ASH, 0.16)
        if y == h - 1:
            return ramp(BONE, 0.32)
    if face == 'top' and y <= 1 and x in (1, 3):
        return ramp(ASH, 0.2)
    if face == 'bottom':
        return ramp(ASH, 0.1)
    return skin(face, x, y, w, h)


def barb(base):
    """Widerhaken am Schwanzende: knochenbleiche Pfeilspitze, oben hell, unten dunkel."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] * 1.3 + (0.5 - y / max(h - 1, 1)) * 0.12 + (speckle(x, y, 553) - 0.5) * 0.04
        return ramp(BONE, v - 0.15)
    return paint


# --- Posen (Radiant, absolute Knochendrehung; 6 Zahlen = + Versatz in Einheiten) ------------------------------------
# Jede Schlüsselpose aus ImpModel.kt steht hier mit denselben Zahlen. Zum Übertragen:
#   python tools/hha_assets/imp_model.py --kotlin
PI = math.pi


def _pose(**bones):
    """Posen-Helfer: `wing=(…)` setzt wing_r und spiegelt auf wing_l (yaw und roll negiert), ebenso arm/leg/ear/tip,
    wenn nur die rechte Seite angegeben ist. Ein ausdrücklich angegebener linker Wert gewinnt."""
    out = {}
    pairs = {'wing': ('wing_r', 'wing_l'), 'tip': ('wing_r_tip', 'wing_l_tip'), 'ear': ('ear_r', 'ear_l'),
             'arms': ('arm_r', 'arm_l'), 'legs': ('leg_r', 'leg_l')}
    for key, value in bones.items():
        if key in pairs:
            r, l = pairs[key]
            out[r] = value
            out[l] = (value[0], -value[1], -value[2]) + tuple(value[3:])
        else:
            out[key] = value
    for key, value in bones.items():
        if key not in pairs:
            out[key] = value
    return out


# Fliegen: zwei Endlagen des Flügelschlags (8 Ticks). Der Körper hängt vornüber (pitch 0,5), sackt beim Aufschlag
# etwas ab und hebt sich beim Abschlag; die Flügelspitzen schlagen nach (in Kotlin phasenverschoben gemischt).
FLY_COMMON = dict(
    head=(-0.36, 0, 0), jaw=(0.12, 0, 0),
    arm_r=(-0.35, 0, 0.30), arm_l=(-0.15, 0, -0.38), fork=(0.10, 0, -0.42),
    leg_r=(-0.12, 0, 0.18), leg_l=(0.05, 0, -0.22),
    tail_0=(-0.55, 0, 0), tail_1=(0.40, 0, 0), tail_2=(0.95, 0, 0),
)
POSES = {
    'rest': {'ember': None},
    'fly_up': _pose(body=(0.50, 0, 0, 0, 0.7, 0), wing=(0, 0.55, 0.60), tip=(0, 0.30, -0.30),
                    ember=None, **FLY_COMMON),
    'fly_down': _pose(body=(0.50, 0, 0, 0, -0.7, 0), wing=(0, -0.15, -0.45), tip=(0, -0.20, 0.40),
                      ember=None, **FLY_COMMON),
    # Stich: Gabel mit beiden Händen hinter die rechte Schulter, Flügel angelegt, Beine angezogen (gehalten bis 10) …
    'stab_windup': _pose(
        body=(-0.25, 0.25, 0, 0, -1.0, 2.5), head=(0.10, -0.25, 0), jaw=(0.05, 0, 0),
        arm_r=(2.75, 0, -0.30), fork=(-0.93, 0, 0), arm_l=(2.10, 0, -0.55),
        wing=(0, 1.25, 0.10), tip=(0, 1.20, -0.30), ear=(0, 0.60, 0.45),
        leg_r=(-0.95, 0, 0.25), leg_l=(-0.70, 0, -0.25),
        tail_0=(-0.10, 0, 0), tail_1=(0.70, 0, 0), tail_2=(1.10, 0, 0), ember=None),
    # … dann Stoß mit gestrecktem Körper: Arm, Gabel und Rumpf bilden eine Linie nach vorn (Treffer bei 10).
    'stab_hit': _pose(
        body=(1.15, -0.10, 0, 0, 0.5, -4.0), head=(-0.95, 0.10, 0), jaw=(0.40, 0, 0),
        arm_r=(3.56, 0, 0.12), fork=(-3.14, 0, 0), arm_l=(3.35, 0, -0.30),
        wing=(0, 0.85, 0.30), tip=(0, 0.80, -0.10), ear=(0, 0.75, 0.35),
        leg_r=(0.55, 0, 0.15), leg_l=(0.75, 0, -0.15),
        tail_0=(-1.05, 0, 0), tail_1=(0.10, 0, 0), tail_2=(0.40, 0, 0), ember=None),
    # Feuerball: steht in der Luft, rechte Hand über dem Kopf, darin wächst die Glut; Maul weit offen.
    'throw_charge': _pose(
        body=(-0.12, -0.20, 0, 0, -1.5, 0), head=(-0.10, 0.20, 0), jaw=(0.70, 0, 0),
        arm_r=(3.05, 0, -0.62), fork=(0.95, 0, 0), arm_l=(-0.30, 0, -1.10),
        wing=(0, 0.20, 0.30), tip=(0, 0.0, 0.15), ear=(0, 0.10, 0.85),
        leg_r=(-0.75, 0, 0.30), leg_l=(-0.30, 0, -0.30),
        tail_0=(-0.10, 0, 0), tail_1=(0.60, 0, 0), tail_2=(0.95, 0, 0)),
    # Wurf (bei 16): Schleuder über oben nach vorn-unten, Körper kippt nach vorn; danach Salto rückwärts.
    'throw_release': _pose(
        body=(0.55, 0.15, 0, 0, -0.5, -1.0), head=(-0.55, -0.10, 0), jaw=(0.55, 0, 0),
        arm_r=(4.98, 0, 0.25), fork=(2.60, 0, 0), arm_l=(0.80, 0, -0.60),
        wing=(0, -0.25, -0.20), tip=(0, -0.15, 0.35), ear=(0, 0.45, 0.55),
        leg_r=(0.40, 0, 0.20), leg_l=(0.15, 0, -0.20),
        tail_0=(-0.80, 0, 0), tail_1=(0.20, 0, 0), tail_2=(0.60, 0, 0), ember=None),
    # Getroffen: Flügel verkrampfen nach vorn, Ohren legen sich an, Körper trudelt.
    'hurt': _pose(
        body=(-0.20, 0, 0.45, 0, -0.5, 1.0), head=(0.25, 0, -0.20), jaw=(0.30, 0, 0),
        arm_r=(-0.80, 0, 0.60), arm_l=(-0.60, 0, -0.70), fork=(0.30, 0, -0.42),
        wing=(0, -0.55, -0.45), tip=(0, -0.95, 0.50), ear=(0, 1.15, 0.12),
        leg_r=(-0.55, 0, 0.30), leg_l=(0.30, 0, -0.25),
        tail_0=(-0.20, 0, 0), tail_1=(0.90, 0, 0), tail_2=(1.20, 0, 0), ember=None),
}
KOTLIN_POSES = ['fly_up', 'fly_down', 'stab_windup', 'stab_hit', 'throw_charge', 'throw_release', 'hurt']


# --- Kotlin-Übertragung --------------------------------------------------------------------------------------------
KOTLIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'kotlin', 'dev', 'henny',
                      'hha', 'client', 'model', 'ImpModel.kt')
ANIMATED = ['body', 'head', 'jaw', 'ear_r', 'ear_l', 'arm_r', 'arm_l', 'fork', 'leg_r', 'leg_l',
            'wing_r', 'wing_r_tip', 'wing_l', 'wing_l_tip', 'tail_0', 'tail_1', 'tail_2']



def _defaults():
    model = imp()
    out = {}
    for root in model.parts:
        for part in root.walk():
            out[part.name] = tuple(math.radians(a) for a in part.rot) + (0.0, 0.0, 0.0)
    return out


def kotlin_block():
    base = _defaults()
    lines = ['    // <POSES> generiert von tools/hha_assets/imp_model.py (--kotlin); nicht von Hand ändern.',
             '    val BONES = arrayOf(' + ', '.join(f'"{b}"' for b in ANIMATED) + ')']
    for name in KOTLIN_POSES:
        pose = POSES[name]
        vals = []
        for b in ANIMATED:
            v = pose.get(b, base[b])
            v = tuple(v) + (0.0,) * (6 - len(v))
            vals.extend(v)
        const = name.upper()
        nums = ', '.join(f'{x:.4f}f' for x in vals)
        lines.append(f'    val {const} = floatArrayOf({nums})')
    lines.append('    // </POSES>')
    return '\n'.join(lines)


def write_kotlin():
    with open(KOTLIN, encoding='utf8') as f:
        src = f.read()
    new, n = re.subn(r'    // <POSES>.*?// </POSES>', lambda _: kotlin_block(), src, flags=re.S)
    if n != 1:
        raise SystemExit('Markierter POSES-Block in ImpModel.kt nicht gefunden')
    with open(KOTLIN, 'w', encoding='utf8', newline='\n') as f:
        f.write(new)
    print('Posen nach', os.path.abspath(KOTLIN))


if __name__ == '__main__':
    if '--kotlin' in sys.argv:
        write_kotlin()
