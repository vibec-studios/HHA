"""Brontes, der Donnerer (Kyklop, Schmied der Blitze): Modell, Textur, Leuchtmaske und Schlüsselposen.

Teil von boss_models.py (Aufruf dort mit --only brontes). Brief: tmp/overhaul2/models/brontes.md, Kampf: PLAN 5.3.
Direkt aufgerufen schreibt diese Datei die Posentabelle in client/model/BrontesModel.kt (zwischen den Marken), damit
Vorschau und Spiel dieselben Winkel benutzen:

    python tools/hha_assets/brontes_model.py --kotlin

Erkennungsmerkmal: ein einziges großes Glutauge unter einem schweren Brauenwulst, der Schmiedehammer (Kopf so groß wie
sein Kopf) und der Buckel aus Schultern, zwischen denen der Kopf halslos und tief nach vorn sitzt. Die Arme reichen bis
fast an die Knie. Ein Handwerker: Lederschürze mit Brandflecken, Ruß auf Händen und Unterarmen, barfuß.

Koordinaten: 1/16 Block vor dem Renderer-Faktor 0,70, +y nach unten, -z vorn, Füße bei y = 24, Buckel bei etwa -98.
Die rechte Körperseite liegt bei -x.
"""
import math
import os
import re
import sys

from bosskit import Cube
from modelkit import Model, Part, hexc, mix, ramp, rgb, speckle

# =====================================================================================================================
# Material (Leitfarben aus dem Brief, zu schattierten Rampen erweitert)
# =====================================================================================================================

SKIN = rgb('#160d0a', '#2f1f18', '#4b3225', '#6a4834', '#8a6248', '#a87e60', '#c49a7e', '#ddb898')
SOOT = hexc('#17120f')
SCAR = hexc('#b08a74')
LEATHER = rgb('#140b07', '#2a1810', '#3f2416', '#5a3420', '#71432a', '#8a5632', '#a46c42')
IRON = rgb('#0b0d11', '#1c1f26', '#2c3039', '#3e434e', '#4a505c', '#6d7482', '#9aa2b0', '#c8ced8')
WOOD = rgb('#160d08', '#2a1a12', '#3e2818', '#5a3a22', '#704a2c', '#8a6038')
BEARD = rgb('#0b0908', '#171310', '#26201b', '#383028', '#4a4036')
GLUT = rgb('#4a1204', '#a4360a', '#f7841f', '#ffb04a', '#ffe59a', '#fff7e0')
SCLERA = rgb('#5a4a3a', '#a8946e', '#d8c8a0', '#efe2c0')

# Leuchtmaske. Brief: #ffb347. Der Gold-Prüfer der Vorschau zählt diesen Ton als „goldenes Leuchten" (Gold heißt im
# Spiel sicher/offen), deshalb zwei Töne ohne Goldanteil: Glutrand röter, Kern fast weiß (wie Sisyphos' Glut).
GLOW_RIM = (255, 118, 40)
GLOW_CORE = (255, 214, 160)

FACE_LIGHT = {'top': 0.14, 'bottom': -0.22, 'front': 0.0, 'back': -0.08, 'left': -0.05, 'right': -0.05}
SIDES = ('front', 'back', 'left', 'right')


def ember(t, strength=255):
    """Glut: Albedo aus der Glutrampe, Maske rot am Rand (t < 0,8) und fast weiß im Kern."""
    tint = GLOW_CORE if t >= 0.8 else GLOW_RIM
    return (ramp(GLUT, t), (tint[0], tint[1], tint[2], strength))


def _clamp(v, a=0.0, b=1.0):
    return max(a, min(b, v))


def _round(face, x, w, k):
    """Zylindrische Schattierung quer über eine Seitenfläche (Mitte hell, Kanten dunkler)."""
    if face in SIDES and w > 3 and k:
        cx = (w - 1) / 2.0
        return -k * ((x - cx) / cx) ** 2
    return 0.0


def _edges(face, x, y, w, h, hi=0.06, seam=0.12):
    """Lichtkante oben an den Seitenflächen, 1 Texel Schattenfuge unten."""
    if face in SIDES:
        if y == 0 and h > 2:
            return hi
        if y == h - 1 and h > 2:
            return -seam
    elif face == 'top' and (x in (0, w - 1) or y in (0, h - 1)):
        return hi * 0.6
    return 0.0


# =====================================================================================================================
# Maler
# =====================================================================================================================

def skin(base=0.55, seed=0, grad=0.14, round_x=0.16, soot=0.0, smudge=0.0, scars=(), split=None, veins=False):
    """Rußige Haut. soot: Ruß nimmt nach unten zu (Unterarme, Hände, Schienbeine); smudge: fleckiger Ruß überall;
    scars: helle Brandnarben als (Fläche, x0, y0, Länge, Richtung)."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + grad * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.08
        v += _round(face, x, w, round_x * 1.25) + _edges(face, x, y, w, h, hi=0.1)
        if face == 'top' and speckle(x * 2, y * 2, seed + 21) > 0.82:
            v += 0.06                                                 # Schweißglanz
        if split is not None and face in ('front', 'back') and abs(x - split * (w - 1)) < 0.6 and 1 < y < h - 2:
            v -= 0.12                                                 # Muskelfuge
        if veins and face in ('front', 'left', 'right') and abs(x - (w * 0.45 + math.sin(y * 0.55 + seed) * 1.6)) < 0.55                 and 2 < y < h - 3:
            v += 0.07                                                 # Ader
        for f, x0, y0, n, d in scars:
            if f == face:
                for k in range(n):
                    if (x, y) == (x0 + k * d[0], y0 + k * d[1]):
                        return mix(ramp(SKIN, v + 0.18), SCAR, 0.45)
                    if (x, y) == (x0 + k * d[0], y0 + k * d[1] + 1):
                        v -= 0.1
        col = ramp(SKIN, v)
        s = 0.0
        if soot:
            t = y / max(h - 1, 1) if face != 'bottom' else 1.0
            s = soot * (0.25 + 0.75 * t) * (0.7 + 0.6 * speckle(x * 2 + 5, y * 2, seed + 7))
        if smudge and speckle(x + 17, y * 3 + 4, seed + 11) > 0.62:
            s += smudge * (speckle(x + 17, y * 3 + 4, seed + 11) - 0.62) * 2.6
        if s > 0:
            col = mix(col, SOOT, min(0.72, s))
        return col
    return paint


def chest_skin(seed):
    """Brustkorb: Rippenbögen an den Flanken, Brustbeinrinne, Haarflaum vorn, Ruß unten."""
    base = skin(0.58, seed, grad=0.12, round_x=0.18, smudge=0.25)

    def paint(face, x, y, w, h):
        col = base(face, x, y, w, h)
        if face in ('left', 'right') and 4 <= y < h - 2 and (y + x // 3) % 4 == 0:
            return mix(col, ramp(SKIN, 0.18), 0.45)                  # Rippen
        if face == 'front':
            cx = (w - 1) / 2.0
            if abs(x - cx) < 0.6 and y > 2:
                return mix(col, ramp(SKIN, 0.2), 0.5)                 # Brustbein
            if abs(x - cx) < 5 and 3 < y < 12 and speckle(x * 3, y * 3, seed + 3) > 0.7:
                return mix(col, BEARD[2], 0.5)                       # Brusthaar
        return col
    return paint


def pec_skin(seed, side):
    """Eine der zwei großen Brustfacetten: Licht oben, harte Unterkante, Brustwarze, Narben."""
    base = skin(0.66, seed, grad=0.22, round_x=0.1, smudge=0.22,
                scars=(('front', 4 if side < 0 else 12, 4, 6, (1, 1)),) if side > 0 else ())

    def paint(face, x, y, w, h):
        col = base(face, x, y, w, h)
        if face == 'front':
            if y >= h - 2:
                return mix(col, ramp(SKIN, 0.16), 0.55 if y == h - 1 else 0.3)
            nx = w - 6 if side < 0 else 5
            if y == h - 5 and x in (nx, nx + 1):
                return ramp(SKIN, 0.3)
            inner = w - 1 if side < 0 else 0
            if x == inner:
                return mix(col, ramp(SKIN, 0.2), 0.5)
        return col
    return paint


def belly_skin(seed):
    """Der Fassbauch: rund schattiert, Nabel, Haarstrich, Ruß an der Unterkante."""
    base = skin(0.56, seed, grad=0.16, round_x=0.24, smudge=0.2)

    def paint(face, x, y, w, h):
        col = base(face, x, y, w, h)
        if face == 'front':
            cx = (w - 1) / 2.0
            if abs(x - cx) < 1 and y in (h - 9, h - 8):
                return ramp(SKIN, 0.16)                               # Nabel
            if abs(x - cx) < 1.5 and y > h - 8 and speckle(x * 5, y * 2, seed) > 0.4:
                return mix(col, BEARD[2], 0.55)                      # Haarstrich
            if y >= h - 3:
                return mix(col, SOOT, 0.25)
        return col
    return paint


def back_skin(seed):
    """Der Buckel: gewaltige Rückenwölbung mit zwei gekreuzten Schürzenriemen und Wirbelreihe."""
    base = skin(0.6, seed, grad=0.24, round_x=0.22, smudge=0.3,
                scars=(('back', 6, 5, 5, (1, 1)), ('top', 20, 6, 6, (1, 0))))
    strap = leather(0.48, seed + 1)

    def paint(face, x, y, w, h):
        if face == 'back':
            u = x / max(w - 1, 1)
            t = y / max(h - 1, 1)
            if abs(u - (0.15 + t * 0.7)) < 0.065 or abs(u - (0.85 - t * 0.7)) < 0.065:
                return strap(face, x, y, w, h)
            cx = (w - 1) / 2.0
            if abs(x - cx) < 0.6 and y % 3 == 1:
                return ramp(SKIN, 0.3)                                # Wirbel
        if face == 'top':
            for xs in (w * 0.28, w * 0.72):
                if abs(x - xs) < 1.6:
                    return strap(face, x, y, w, h)
        return base(face, x, y, w, h)
    return paint


def leather(base=0.5, seed=0, stitch=False, burns=0, folds=0.05, worn=True):
    """Leder: Falten, abgegriffene Kanten, auf Wunsch Steppnaht am Rand und Brandflecken (3 x 3)."""
    spots = []
    for k in range(burns):
        spots.append((speckle(k * 13 + 3, seed, seed + k), speckle(seed, k * 11 + 5, seed + 2 * k)))

    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + 0.1 * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.07
        v += math.sin(x * 0.85 + seed) * folds
        if worn and face in SIDES and (x in (0, w - 1)) and w > 4:
            v += 0.08
        if face in SIDES and y == 0 and h > 3:
            v += 0.07
        if stitch and face in ('front', 'back') and w > 6 and h > 6:
            on_x = x in (1, w - 2) and 1 <= y <= h - 2
            on_y = y in (1, h - 2) and 1 <= x <= w - 2
            if (on_x and y % 3 == 0) or (on_y and x % 3 == 0):
                return ramp(LEATHER, v + 0.32)
        if burns and face == 'front':
            for k, (sx, sy) in enumerate(spots):
                bx = 2 + int(sx * (w - 6))
                by = 3 + int(sy * (h - 7))
                dx, dy = x - bx, y - by
                if 0 <= dx < 3 and 0 <= dy < 3:
                    if (dx in (0, 2)) and (dy in (0, 2)) and speckle(dx + k, dy, seed) > 0.5:
                        v -= 0.1                                      # ausgefranste Ecke
                    else:
                        return mix(ramp(LEATHER, 0.18 if (dx, dy) == (1, 1) else 0.28), SOOT, 0.18)
                if -1 <= dx <= 3 and -1 <= dy <= 3:
                    v -= 0.1                                          # versengter Rand
        return ramp(LEATHER, v)
    return paint


def apron_paint(seed):
    """Lederschürze: Steppnaht, senkrechte Falten, Brandflecken, Rußschleier unten, aufgenähte Tasche."""
    base = leather(0.52, seed, stitch=True, burns=4, folds=0.07)

    def paint(face, x, y, w, h):
        col = base(face, x, y, w, h)
        if face == 'front':
            # Tasche rechts unten (Steppnaht), damit die große Fläche nicht leer wirkt.
            px0, py0 = 3, int(h * 0.55)
            if px0 <= x <= px0 + 8 and py0 <= y <= py0 + 7:
                if x in (px0, px0 + 8) or y in (py0, py0 + 7):
                    return ramp(LEATHER, 0.32)
                if y == py0 + 1:
                    return ramp(LEATHER, 0.62)
            if y > h * 0.7:
                return mix(col, SOOT, (y / h - 0.7) * 1.1)
        return col
    return paint


def pad_paint(seed):
    """Schulterleder gegen Funkenflug: dickes Leder mit Eisennieten oben."""
    base = leather(0.46, seed, stitch=True, folds=0.03)

    def paint(face, x, y, w, h):
        if face == 'top' and x % 5 == 2 and y % 5 == 2:
            return ramp(IRON, 0.8)
        if face in SIDES and y == h - 1:
            return ramp(LEATHER, 0.18)
        return base(face, x, y, w, h)
    return paint


def breeches(seed):
    """Kurze Lederhose: dunkler, mit Saum und Flicken."""
    base = leather(0.36, seed, folds=0.06)

    def paint(face, x, y, w, h):
        if face in SIDES and y >= h - 2:
            return ramp(LEATHER, 0.62 if y == h - 2 else 0.2)
        if face == 'left' and 4 <= x <= 9 and 5 <= y <= 10:
            return ramp(LEATHER, 0.5 if x in (4, 9) or y in (5, 10) else 0.42)   # Flicken
        return base(face, x, y, w, h)
    return paint


def belt_paint(face, x, y, w, h):
    v = 0.44 + FACE_LIGHT[face] + (speckle(x, y, 911) - 0.5) * 0.06
    if face in SIDES and y in (0, h - 1):
        v += 0.12
    if face in SIDES and y == 2 and x % 4 == 1:
        return ramp(IRON, 0.62)                                       # Nieten
    return ramp(LEATHER, v)


def iron(base=0.5, seed=0, rim=True, marks=True, rivets=False):
    """Geschmiedetes Eisen: Hammerschlag-Dellen, heller Grat an den Kanten, dunkle Mitte."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] * 1.2 + 0.12 * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.08
        if rim and (x in (0, w - 1) or y in (0, h - 1)):
            v += 0.2
        elif rim and (x in (1, w - 2) or y in (1, h - 2)):
            v -= 0.05
        if marks:
            m = speckle(x // 2 * 5 + 3, y // 2 * 7 + 1, seed + 1)
            v += (m - 0.5) * 0.18                                     # Schlagfacetten in 2er-Flecken
        if rivets and face in SIDES and w >= 5 and h >= 5 and x in (1, w - 2) and y in (1, h - 2):
            return ramp(IRON, 0.85)
        return ramp(IRON, v)
    return paint


def wood(seed=0, base=0.5):
    """Hammerstiel: Maserung längs, abgegriffen in der Mitte."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + (speckle(x, y // 3, seed) - 0.5) * 0.12
        if face in SIDES:
            if (x + int(speckle(0, y // 5, seed + 1) * 3)) % 3 == 0:
                v -= 0.12
            if x in (0, w - 1):
                v -= 0.08
        return ramp(WOOD, v)
    return paint


def wrap_paint(seed):
    """Griffwicklung aus Leder: schräge Bahnen."""
    def paint(face, x, y, w, h):
        v = 0.48 + FACE_LIGHT[face] + (speckle(x, y, seed) - 0.5) * 0.06
        band = (x + y * 2) % 5
        v += 0.1 if band in (0, 1) else (-0.12 if band == 4 else 0.0)
        return ramp(LEATHER, v)
    return paint


def hammer_face(face, x, y, w, h):
    """Schlagfläche: blank geschlagenes Eisen, 2 Texel Glutrand vorn (Brief), Seiten mit Hitzefarben."""
    if face == 'front':
        edge = min(x, y, w - 1 - x, h - 1 - y)
        if edge < 2:
            return ember(0.48 if edge == 0 else 0.4, 235)
        cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
        r = math.hypot(x - cx, y - cy) / max(cx, 1)
        return ramp(IRON, 0.78 - r * 0.3 + (speckle(x, y, 931) - 0.5) * 0.08)
    v = 0.5 + FACE_LIGHT[face] * 1.2 + (speckle(x, y, 932) - 0.5) * 0.08
    if face in ('left', 'right', 'top', 'bottom'):
        # Anlauffarben zur heißen Schlagfläche hin (vorn = kleiner x auf left/right? siehe Atlas: x läuft über d).
        k = x / max(w - 1, 1) if face == 'left' else 1 - x / max(w - 1, 1) if face == 'right' else y / max(h - 1, 1) if face == 'top' else 1 - y / max(h - 1, 1)
        if face in ('top', 'bottom'):
            k = 1 - y / max(h - 1, 1) if face == 'top' else y / max(h - 1, 1)
        if k < 0.3:
            return mix(ramp(IRON, v), hexc('#6a3a2a'), 0.35)
    if x in (0, w - 1) or y in (0, h - 1):
        v += 0.18
    return ramp(IRON, v)


def hand_paint(seed, side):
    """Faust: vier breite Finger mit Fugen, Knöchel hell, Ruß in den Falten."""
    base = skin(0.52, seed, grad=0.1, round_x=0.1, soot=0.45, smudge=0.2)

    def paint(face, x, y, w, h):
        col = base(face, x, y, w, h)
        if face in ('front', 'bottom'):
            if x % 4 == 3 and 0 < x < w - 1:
                return mix(ramp(SKIN, 0.14), SOOT, 0.4)               # Fingerfugen
            if face == 'front' and y < 2:
                return mix(col, ramp(SKIN, 0.85), 0.35)               # Knöchel
        return col
    return paint


def eye_paint(face, x, y, w, h):
    """Das Auge (8 x 6): Oberlid als dunkle Kante, cremige Lederhaut in den Winkeln, runde Glut-Iris und ein fast weißer
    Kern (die hellste Stelle der Figur)."""
    if face != 'front':
        return ramp(SKIN, 0.32 + FACE_LIGHT[face])
    if y == 0:
        return ramp(SKIN, 0.16 if x in (0, w - 1) else 0.26)              # Oberlid
    if y == h - 1:
        return ramp(SKIN, 0.2 if x in (0, w - 1) else 0.4)               # Unterlid
    if x in (0, w - 1):
        return ramp(SKIN, 0.14)                                       # Augenwinkel
    cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
    dx, dy = abs(x - cx), abs(y - cy)
    if dx < 1 and dy < 1:
        return ember(0.96, 255)                                       # Kern
    if dx < 2 and dy < 2 and not (dx > 1 and dy > 1):
        return ember(0.6 if y == 1 else 0.7, 245)                     # Iris, oben vom Lid beschattet
    v = 0.66 - (0.22 if y == 1 else 0.0) + (speckle(x, y, 941) - 0.5) * 0.1
    col = ramp(SCLERA, v)
    if speckle(x * 3, y * 5, 942) > 0.7:
        col = mix(col, hexc('#9a3020'), 0.35)                          # Äderchen
    return col


def head_paint(face, x, y, w, h):
    """Kahler Schädel 16 x 14 x 16. Vorn: Augenhöhle mit geschlossenem Lid (sichtbar, wenn das Auge zu ist),
    Stirnfalten über dem Wulst, Rußflecken. Oben: glänzende Glatze mit Brandnarbe."""
    base = skin(0.6, 951, grad=0.12, round_x=0.14, smudge=0.25)
    if face == 'top':
        cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
        d = math.hypot(x - cx, y - cy)
        v = 0.74 - d * 0.03 + (speckle(x, y, 952) - 0.5) * 0.06
        if (x - 3) == (y - 2) and 3 <= x <= 8:
            return mix(ramp(SKIN, v + 0.1), SCAR, 0.5)
        return ramp(SKIN, v)
    if face == 'front':
        cx = (w - 1) / 2.0
        d = abs(x - cx)
        if y in (0, 2) and d < 5:
            return ramp(SKIN, 0.36)                                   # Stirnfalten über dem Wulst
        if 5 <= y <= 10 and d < 4.5:
            # Lid (unter dem Augenwürfel): gewölbt, Wimpernlinie, Lidfalte.
            if y == 8 and d < 3.6:
                return ramp(SKIN, 0.1)
            if y == 6 and d < 3.0:
                return ramp(SKIN, 0.34)
            return ramp(SKIN, 0.58 - d * 0.04 - (0.08 if y > 8 else 0.0))
        if 4 <= y <= 11 and d < 5.5:
            return ramp(SKIN, 0.22)                                   # tiefe Augenhöhle
        if y >= 11 and 4 < d < 7:
            return ramp(SKIN, 0.4)                                    # Wangenfalte
    if face in ('left', 'right'):
        if 6 <= y <= 9 and x in (4, 5, 6) and False:
            return ramp(SKIN, 0.3)
    return base(face, x, y, w, h)


def brow_paint(face, x, y, w, h):
    """Brauenwulst: schwerer Knochen, darunter ein buschiger, versengter Brauenstrich, tiefer Schatten unten."""
    if face == 'bottom':
        return ramp(SKIN, 0.1)
    cx = (w - 1) / 2.0
    if face == 'front':
        if y == h - 1:
            return ramp(BEARD, 0.28 + speckle(x, 2, 962) * 0.35)        # Braue
        v = 0.7 - abs(x - cx) * 0.03 - y * 0.05 + (speckle(x, y, 963) - 0.5) * 0.06
        if abs(x - cx) < 0.6:
            v -= 0.2                                                  # Zornesfalte
        return ramp(SKIN, v)
    if face in ('left', 'right') and y == h - 1:
        return ramp(BEARD, 0.3)
    return skin(0.62, 964, grad=0.1)(face, x, y, w, h)


def nose_paint(face, x, y, w, h):
    """Breite, plattgeschlagene Nase: heller Rücken, dunkle Nasenlöcher unten."""
    if face == 'bottom':
        return ramp(SKIN, 0.08) if x in (1, w - 2) else ramp(SKIN, 0.24)
    v = 0.6 + FACE_LIGHT[face] + (0.1 if face == 'front' and x in (2,) else 0.0) - (0.08 if y == h - 1 else 0.0)
    return ramp(SKIN, v + (speckle(x, y, 967) - 0.5) * 0.05)


def jaw_paint(face, x, y, w, h):
    """Kiefer: grimmiger Mund unter einem Schnurrbart aus Ruß-Stoppeln."""
    base = skin(0.54, 971, grad=0.1, round_x=0.18, smudge=0.4)
    if face == 'front':
        cx = (w - 1) / 2.0
        d = abs(x - cx)
        if y == 0 and d < 5:
            return ramp(BEARD, 0.45 + (speckle(x, y, 972) - 0.5) * 0.3)
        if y == 2 and d < 4:
            return ramp(SKIN, 0.08)                                   # Mund
        if y == 1 and d < 5:
            return ramp(BEARD, 0.3)
    if face in ('left', 'right', 'bottom') and speckle(x * 3, y * 2, 973) > 0.45:
        return ramp(BEARD, 0.35)                                     # Stoppeln
    return base(face, x, y, w, h)


def beard_paint(seed, strands=0.08):
    def paint(face, x, y, w, h):
        v = 0.5 + FACE_LIGHT[face] + (speckle(x, y, seed) - 0.5) * 0.1 + math.sin(x * 1.7 + seed) * strands
        if y == h - 1 and face in SIDES:
            v -= 0.15
        return ramp(BEARD, v)
    return paint


def toe_paint(seed):
    base = skin(0.5, seed, soot=0.55, round_x=0.0)

    def paint(face, x, y, w, h):
        if face in ('front', 'top') and x in (4, 8, 12) or (face in ('front', 'top') and x == 15):
            return mix(ramp(SKIN, 0.16), SOOT, 0.4)
        return base(face, x, y, w, h)
    return paint


def ingot_paint(face, x, y, w, h):
    """Glühender Rohling: weißgelber Kern, Glutrand, eine dunklere Zunderhaut-Fleckung."""
    edge = min(x, y, w - 1 - x, h - 1 - y)
    t = 0.86 if edge >= 1 else 0.6
    if speckle(x * 4, y * 4, 981) > 0.8:
        t -= 0.24
    return ember(t, 255)


def wedge_paint(face, x, y, w, h):
    """Der fertige Keil: schwarz geschmiedetes Eisen mit glühenden Kanten, die Spitze weißglühend."""
    t = y / max(h - 1, 1)
    if face in SIDES and (x in (0, w - 1)):
        return ember(0.55, 230)
    v = 0.36 + FACE_LIGHT[face] + (speckle(x, y, 991) - 0.5) * 0.1 + 0.1 * (1 - t)
    return ramp(IRON, v)


def wedge_tip(face, x, y, w, h):
    return ember(0.9, 255)


# =====================================================================================================================
# Formhelfer
# =====================================================================================================================

def rounded(x, y, z, w, h, d, paint, c=2, axis='y', paint2=None):
    """Gefaster Block aus zwei Quadern (achteckiger Querschnitt entlang axis). Der zweite Quader ist entlang axis um 1
    kürzer, damit keine Flächen deckungsgleich aufeinanderliegen (kein Z-Fighting)."""
    p2 = paint2 or paint
    if axis == 'y':
        return [Cube(x, y, z + c, w, h, d - 2 * c, paint), Cube(x + c, y + 0.5, z, w - 2 * c, h - 1, d, p2)]
    if axis == 'x':
        return [Cube(x, y, z + c, w, h, d - 2 * c, paint), Cube(x + 0.5, y + c, z, w - 1, h - 2 * c, d, p2)]
    return [Cube(x + c, y, z, w - 2 * c, h, d, paint), Cube(x, y + c, z + 0.5, w, h - 2 * c, d - 1, p2)]


# =====================================================================================================================
# Grundhaltung (Ruhe): Diese Winkel stehen als Grundrotation im Modell und sind POSES['idle'] (Radiant).
# =====================================================================================================================

IDLE = {
    'body': (0.0, 0.0, 0.0),
    'chest': (0.3, 0.0, 0.0),
    'head': (-0.26, 0.0, 0.0),
    'brow': (0.0, 0.0, 0.0),
    'eye': (0.0, 0.0, 0.0),
    'arm_r': (-0.9, 0.0, 0.25),
    'fore_r': (-1.5, 0.0, 0.0),
    'hammer': (0.0, 0.0, 0.0),
    'wedge': (0.0, 0.0, 0.0),
    'arm_l': (-0.12, 0.0, -0.16),
    'fore_l': (-0.3, 0.0, 0.05),
    'tongs': (0.0, 0.0, 0.0),
    'tongs_jaw': (0.0, 0.0, 0.0),
    'ingot': (0.0, 0.0, 0.0),
    'apron': (-0.105, 0.0, 0.0),
    'tongs_belt': (0.12, 0.0, -0.12),
    'leg_r': (0.0, 0.0, 0.1),
    'shin_r': (0.0, 0.0, -0.1),
    'leg_l': (0.0, 0.0, -0.1),
    'shin_l': (0.0, 0.0, 0.1),
    # Abgelegter Hammer: liegt flach vorn rechts, Kopf nach vorn (Lage siehe DROP_PIVOT).
    'hammer_dropped': (math.radians(85.5), math.radians(-22.0), 0.0),
}
# Pivot-Korrekturen: Versätze, die die Ruhe braucht, werden in die Grundlage der Knochen eingebacken (siehe _bake).
PIVOT_FIX = {}


def deg(name):
    return tuple(math.degrees(a) for a in IDLE[name][:3])


def pv(name, pivot):
    fix = PIVOT_FIX.get(name, (0.0, 0.0, 0.0))
    return (pivot[0] + fix[0], pivot[1] + fix[1], pivot[2] + fix[2])


# =====================================================================================================================
# Modell
# =====================================================================================================================

FIST = (0.0, 34.5, -1.0)        # Faustmitte im Unterarm (Griffpunkt)
HAMMER_GRIP = 7.0               # Faustmitte liegt 7 Einheiten vom Griffende (Ursprung des Hammers) entfernt


def hammer_cubes():
    """Schmiedehammer, Ursprung am Griffende (Knauf), Stiel nach -y. Kopf 18 lang (z), 14 x 14: vorn (-z) die flache
    Schlagfläche mit Glutrand, hinten (+z) ein stumpfer Keil."""
    return [
        Cube(-2.5, -2, -2.5, 5, 4, 5, iron(0.55, 801)),                                   # Knauf
        Cube(-2, -62, -2, 4, 60, 4, wood(802)),                                          # Stiel (64 mit Knauf)
        Cube(-2.5, -16, -2.5, 5, 13, 5, wrap_paint(803)),                                # Griffwicklung
        Cube(-3, -57, -3, 6, 4, 6, iron(0.5, 804)),                                      # Eisenband unter dem Kopf
        Cube(-7, -70, -5, 14, 14, 10, iron(0.5, 805, rivets=True)),                      # Kopf
        Cube(-7.5, -70.5, -9, 15, 15, 4, hammer_face),                                   # Schlagfläche
        Cube(-6.5, -68.5, 5, 13, 11, 3, iron(0.46, 806)),                                # Keil, Stufe 1
        Cube(-6, -66.5, 8, 12, 7, 2, iron(0.42, 807)),                                   # Keil, Stufe 2 (stumpf)
        Cube(-3.5, -72, -3.5, 7, 2, 7, iron(0.6, 808, marks=False)),                     # Keilzapfen oben
    ]


def tongs_cubes(jaw=True):
    """Schmiedezange, 30 lang: Griffe (in der Faust), Niet, feste Backe; die bewegliche Backe ist ein eigener Knochen."""
    cubes = [
        Cube(-3, -6, -1.5, 6, 20, 3, iron(0.46, 821, marks=False)),                       # beide Griffe
        Cube(-3.5, 13, -2, 7, 4, 4, iron(0.6, 822, rivets=False)),                        # Niet
        Cube(-3.5, 16, -1.5, 3, 13, 3, iron(0.5, 823)),                                   # feste Backe
        Cube(-3.5, 27, -1.5, 4, 3, 3, iron(0.56, 824)),                                   # Backenspitze
    ]
    if not jaw:
        cubes += [Cube(0.5, 16, -1.5, 3, 13, 3, iron(0.5, 825)), Cube(-0.5, 27, -1.5, 4, 3, 3, iron(0.56, 826))]
    return cubes


def head_part():
    eye = Part('eye', pv('eye', (0, 1, 0)), deg('eye'), cubes=[
        Cube(-4, 0, -0.5, 8, 6, 1, eye_paint),
    ])
    brow = Part('brow', pv('brow', (0, -11, -9)), deg('brow'), cubes=[
        Cube(-8, -3, -4, 16, 4, 4, brow_paint),                                          # Brauenwulst 16 x 4 x 4
    ], children=[eye])
    return Part('head', pv('head', (0, -58, -13)), deg('head'), cubes=[
        Cube(-8, -15, -9, 16, 15, 16, head_paint),                                       # Schädel 16 x 15 x 16
        Cube(-7, -16, -7, 14, 1, 12, skin(0.72, 953, grad=0.0, round_x=0.3)),            # Scheitelwölbung
        Cube(-7.5, -2, -10.5, 15, 5, 12, jaw_paint),                                      # schwerer Kiefer
        Cube(-2.5, -4, -11.5, 5, 3, 3, nose_paint),                                       # breite Nase
        Cube(-9.5, -10, -1, 2, 5, 4, skin(0.56, 955, round_x=0.0)),                       # Ohr rechts
        Cube(7.5, -10, -1, 2, 5, 4, skin(0.56, 956, round_x=0.0)),                        # Ohr links
        Cube(-5, 2.5, -11.5, 10, 4, 6, beard_paint(957)),                                 # Kinnbart
        Cube(-3, 6, -11, 6, 4, 4, beard_paint(958)),                                      # Keilspitze
        Cube(-8.5, -5, -9, 3, 6, 6, beard_paint(959, 0.04)), Cube(5.5, -5, -9, 3, 6, 6, beard_paint(960, 0.04)),   # Backenbart
        Cube(-7.5, -5.5, -10, 3, 2, 2, skin(0.66, 961, round_x=0.0)), Cube(4.5, -5.5, -10, 3, 2, 2, skin(0.66, 962, round_x=0.0)),   # Wangenknochen
    ], children=[brow])


def arm_part(side):
    """Oberarm 14 dick, 30 lang; Unterarm 16 dick, 30 lang (dicker als der Oberarm); Faust 15. side -1 = rechts."""
    s = 'r' if side < 0 else 'l'
    seed = 500 if side < 0 else 540
    inner = -side                                   # Innenseite (zum Körper)
    fore_cubes = [
        Cube(-6, -3, -6.5, 12, 6, 12, skin(0.56, seed + 4, round_x=0.1)),                 # Ellbogen
        *rounded(-8, 0, -8, 16, 28, 16, skin(0.56, seed + 5, soot=0.42, smudge=0.25, veins=True), 3),   # Unterarm 16
        Cube(-6.5, 27, -7.5, 13, 3, 13, skin(0.46, seed + 6, soot=0.6, round_x=0.0)),     # Handgelenk
        *rounded(-7.5, 28.5, -8.5, 15, 14, 15, hand_paint(seed + 7, side), 2),            # Faust
        Cube(inner * 7.5 - 2.5, 29, -10, 5, 8, 5, skin(0.5, seed + 8, soot=0.5)),          # Daumen
    ]
    if side > 0:
        fore_cubes.append(Cube(-9, 13, -9, 18, 13, 18, leather(0.5, seed + 9, stitch=True)))   # Ledermanschette
        fore_cubes.append(Cube(-9.5, 14.5, -9.5, 19, 2, 19, belt_paint))                    # Riemen der Manschette
    else:
        fore_cubes.append(Cube(-8.5, 21, -8.5, 17, 5, 17, wrap_paint(seed + 9)))           # Handgelenkwickel
    fore_children = []
    if side < 0:
        fore_children.append(Part('hammer', pv('hammer', (0, 41.5, -1)), deg('hammer'), cubes=hammer_cubes()))
        fore_children.append(Part('wedge', pv('wedge', FIST), deg('wedge'), cubes=[
            Cube(-2, -24, -2, 4, 26, 4, wedge_paint),                                    # Schaft
            Cube(-4.5, -20, -1.5, 3, 6, 3, wedge_paint), Cube(1.5, -14, -1.5, 3, 6, 3, wedge_paint),   # Zacken
            Cube(-4.5, -8, -1.5, 3, 5, 3, wedge_paint),
            Cube(-1.5, -30, -1.5, 3, 6, 3, wedge_tip),                                   # weißglühende Spitze
        ]))
    else:
        ingot = Part('ingot', pv('ingot', (0, 28.5, 0)), deg('ingot'), cubes=[Cube(-4, 0, -2, 8, 4, 4, ingot_paint)])
        jaw = Part('tongs_jaw', pv('tongs_jaw', (2, 15, 0)), deg('tongs_jaw'), cubes=[
            Cube(-1.5, 1, -1.5, 3, 13, 3, iron(0.5, 827)), Cube(-2.5, 12, -1.5, 4, 3, 3, iron(0.56, 828)),
        ])
        fore_children.append(Part('tongs', pv('tongs', FIST), deg('tongs'), cubes=tongs_cubes(), children=[jaw, ingot]))
    fore = Part(f'fore_{s}', pv(f'fore_{s}', (0, 30, 0)), deg(f'fore_{s}'), cubes=fore_cubes, children=fore_children)
    return Part(f'arm_{s}', pv(f'arm_{s}', (side * 31, -58, 1)), deg(f'arm_{s}'), cubes=[
        *rounded(-9.5, -10, -9.5, 19, 17, 19, skin(0.62, seed, grad=0.24, round_x=0.18, smudge=0.2,
                                                   scars=(('front', 5, 4, 5, (1, 1)),) if side > 0 else ()), 4),  # Schulterkugel
        *rounded(-7, 4, -7, 14, 27, 14, skin(0.56, seed + 1, smudge=0.25, split=0.42), 3),  # Oberarm 14
        Cube(-5, 8, -9, 10, 13, 3, skin(0.62, seed + 2, round_x=0.2)),                    # Bizeps
        *([Cube(-10.5, -12, -10.5, 21, 8, 21, pad_paint(seed + 3)),                        # Schulterleder (links)
           Cube(-7.5, 12, -7.5, 15, 3, 15, belt_paint)] if side > 0 else []),              # Riemen am Oberarm
    ], children=[fore])


def leg_part(side):
    """Kurze Beine: 40 lang, 16 dick (Lederhose bis zum Knie), barfuß: Fuß 18 x 6 x 22."""
    s = 'r' if side < 0 else 'l'
    seed = 600 if side < 0 else 640
    shin = Part(f'shin_{s}', pv(f'shin_{s}', (0, 20, 0)), deg(f'shin_{s}'), cubes=[
        Cube(-6.5, -2, -9, 13, 6, 5, skin(0.6, seed + 3, soot=0.3, round_x=0.1)),        # Knie
        *rounded(-7.5, 0, -7.5, 15, 15, 15, skin(0.54, seed + 4, soot=0.5, smudge=0.2), 3),   # Schienbein
        *rounded(-9, 14, -15, 18, 6, 22, skin(0.5, seed + 5, soot=0.6, round_x=0.0), 3),   # Fuß 18 x 6 x 22
        Cube(-8.5, 16, -17, 16, 4, 3, toe_paint(seed + 6)),                               # Zehen
    ])
    return Part(f'leg_{s}', pv(f'leg_{s}', (side * 11, 0, 0)), deg(f'leg_{s}'), cubes=[
        *rounded(-8.5, -3, -8.5, 17, 23, 17, breeches(seed), 3),                           # Oberschenkel 16
    ], children=[shin])


def chest_part():
    pec = lambda side, seed: Part(f'pec_{"r" if side < 0 else "l"}', (side * 11.5, -38, -14), (4, side * -8, 0),   # noqa: E731
                                  cubes=[Cube(-12, -8, -3, 24, 16, 4, pec_skin(seed, side))])
    trap = lambda side, seed: Part(f'trap_{"r" if side < 0 else "l"}', (side * 15, -64, 0), (0, 0, side * 24), cubes=[   # noqa: E731
        *rounded(-11, -8, -11, 22, 15, 22, skin(0.66, seed, grad=0.28, round_x=0.18, smudge=0.25), 4)])
    return Part('chest', pv('chest', (0, -10, 0)), deg('chest'), cubes=[
        *rounded(-21, -26, -15, 42, 28, 28, belly_skin(701), 5),                          # Fassbauch 44
        *rounded(-26, -48, -14, 52, 24, 28, chest_skin(702), 5),                          # Brustkorb
        *rounded(-20, -70, -2, 40, 28, 20, back_skin(703), 6),                            # Buckel
        Cube(-10, -64, -9, 20, 14, 14, skin(0.5, 706, round_x=0.1)),                      # Nacken zwischen den Schultern
        Cube(-14, -60, -18, 4, 20, 2, leather(0.46, 707)), Cube(10, -60, -18, 4, 20, 2, leather(0.46, 708)),   # Riemen
    ], children=[
        pec(-1, 711), pec(1, 712),
        trap(-1, 704), trap(1, 705),                                                      # Schultermassen (60 breit)
        head_part(),
        arm_part(-1),
        arm_part(1),
        Part('apron', pv('apron', (0, -41, -17.5)), deg('apron'), cubes=[
            Cube(-12, 0, -1.5, 24, 17, 3, leather(0.5, 731, stitch=True, burns=2)),        # Latz
            Cube(-17, 16, -1.5, 34, 52, 3, apron_paint(732)),                              # Schurz bis zum Knie
            Cube(-18, 15, -2, 36, 3, 4, belt_paint),                                       # Bund
            Cube(-13, 40, -4.5, 3, 7, 3, wood(733, 0.6)),                                  # Meißelgriff in der Tasche
            Cube(-9, 41, -4, 3, 6, 3, iron(0.6, 734, marks=False)),                       # Feile
        ]),
    ])


def body_part():
    return Part('body', pv('body', (0, -16, 0)), deg('body'), cubes=[
        *rounded(-19, -10, -13, 38, 17, 26, breeches(751), 4),                            # Becken
        Cube(-20.5, -13, -14.5, 41, 6, 29, belt_paint),                                   # Gürtel
        Cube(-4.5, -14, -16, 9, 8, 2, iron(0.58, 752, rivets=True)),                      # Schnalle
        Cube(-25, -8, -7, 5, 9, 12, leather(0.44, 753, stitch=True)),                     # Werkzeugtasche rechts
    ], children=[
        chest_part(),
        leg_part(-1),
        leg_part(1),
        Part('tongs_belt', pv('tongs_belt', (22, -9, 3)), deg('tongs_belt'), cubes=tongs_cubes(jaw=False)),
    ])


def dropped_part():
    """Der Hammer allein am Boden (BLINDED: aus der Hand gefallen; WEDGE_THROW: kopfüber abgestellt). Eigener Knochen
    an der Wurzel, damit er liegen bleibt, während Brontes taumelt. Im Spiel nur sichtbar, solange er dort liegt."""
    return Part('hammer_dropped', DROP_PIVOT, DROP_ROT, cubes=hammer_cubes())


# Lage des abgelegten Hammers (Welt): Griffende vorn rechts neben ihm, Kopf liegt flach weiter vorn.
DROP_PIVOT = (-24.0, 21.5, -20.0)
DROP_ROT = deg('hammer_dropped')


def face_model():
    """Nur der Kopf (für final_face.png)."""
    head = head_part()
    head.pivot = (0, 0, 0)
    head.rot = (0, 0, 0)
    return Model('brontes', 512, 512, [head])


def brontes():
    return Model('brontes', 512, 512, [body_part(), dropped_part()])


# =====================================================================================================================
# Kinematik (wie beim Kerkermeister): Vorwärtsrechnung, Ausrichten, kleiner IK-Löser. Damit greifen beide Hände
# wirklich den Stiel. Gerechnet wird nur hier; BrontesModel.kt bekommt die fertigen Zahlen.
# =====================================================================================================================

from preview_model import apply as _apply, mat_mul as _mul, rot_zyx as _rot  # noqa: E402

_EYE = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]


def _tr(m):
    return [[m[j][i] for j in range(3)] for i in range(3)]


def _add(a, b):
    return tuple(x + y for x, y in zip(a, b))


def _sub(a, b):
    return tuple(x - y for x, y in zip(a, b))


def _scale(a, k):
    return tuple(x * k for x in a)


def _dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def _norm(a):
    n = math.sqrt(_dot(a, a)) or 1.0
    return tuple(x / n for x in a)


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def frame(ydir, zhint):
    y = _norm(ydir)
    z = _sub(zhint, _scale(y, _dot(zhint, y)))
    if _dot(z, z) < 1e-6:
        z = _sub((1, 0, 0), _scale(y, y[0]))
    z = _norm(z)
    x = _cross(y, z)
    return [[x[i], y[i], z[i]] for i in range(3)]


def _euler(m):
    yaw = math.asin(max(-1.0, min(1.0, -m[2][0])))
    pitch = math.atan2(m[2][1], m[2][2])
    roll = math.atan2(m[1][0], m[0][0])
    return pitch, yaw, roll


class Rig:
    """Knochenbaum des Modells für Rechnungen an einer Pose (Dict Name -> 3 oder 6 Zahlen)."""

    def __init__(self, model):
        self.parts, self.parent = {}, {}

        def walk(p, par):
            self.parts[p.name] = p
            self.parent[p.name] = par
            for c in p.children:
                walk(c, p.name)
        for p in model.parts:
            walk(p, None)

    def _val(self, pose, name):
        v = pose.get(name)
        if v is None:
            v = tuple(math.radians(a) for a in self.parts[name].rot)
        v = tuple(v)
        return v[:3], (v[3:6] if len(v) >= 6 else (0.0, 0.0, 0.0))

    def fk(self, pose, name):
        if name is None:
            return _EYE, (0.0, 0.0, 0.0)
        chain = []
        n = name
        while n is not None:
            chain.append(n)
            n = self.parent[n]
        rot, pos = _EYE, (0.0, 0.0, 0.0)
        for n in reversed(chain):
            r, off = self._val(pose, n)
            pos = _add(pos, _apply(rot, _add(self.parts[n].pivot, off)))
            rot = _mul(rot, _rot(*r))
        return rot, pos

    def world(self, pose, name, local=(0, 0, 0)):
        rot, pos = self.fk(pose, name)
        return _add(pos, _apply(rot, local))

    def set_world_rot(self, pose, name, rw):
        rp, _ = self.fk(pose, self.parent[name])
        p, y, r = _euler(_mul(_tr(rp), rw))
        _, off = self._val(pose, name)
        pose[name] = (p, y, r) + (tuple(off) if any(off) else ())

    def place(self, pose, name, target):
        rp, pp = self.fk(pose, self.parent[name])
        local = _apply(_tr(rp), _sub(target, pp))
        r, _ = self._val(pose, name)
        pose[name] = tuple(r) + _sub(local, self.parts[name].pivot)

    def aim(self, pose, name, ydir, zhint=(0, 0, -1)):
        self.set_world_rot(pose, name, frame(ydir, zhint))

    def ik(self, pose, arm, fore, grip, target, elbow=None, weight=0.06, elbow_weight=0.04):
        """Zwei-Knochen-IK: Oberarm (pitch, yaw, roll) und Unterarm (pitch) so, dass der Griffpunkt target trifft."""
        names = [(arm, 0), (arm, 1), (arm, 2), (fore, 0)]
        for n in (arm, fore):
            if n not in pose:
                pose[n] = IDLE[n]
        start = [pose[n][k] for n, k in names]

        def put(vals):
            for (n, k), v in zip(names, vals):
                cur = list(pose[n])
                cur[k] = v
                pose[n] = tuple(cur)

        def cost(vals):
            put(vals)
            if not -2.7 <= vals[3] <= 0.05:
                return 1e9
            d = _sub(self.world(pose, fore, grip), target)
            c = _dot(d, d)
            if elbow is not None:
                e = _sub(self.world(pose, fore), elbow)
                c += elbow_weight * _dot(e, e)
            c += weight * sum((a - b) ** 2 for a, b in zip(vals, start))
            return c

        best = [pose[n][k] for n, k in names]
        best_c = cost(best)
        for step in (0.4, 0.15, 0.05, 0.015, 0.005):
            for _ in range(80):
                improved = False
                for i in range(len(best)):
                    for sgn in (1, -1):
                        trial = list(best)
                        trial[i] += sgn * step
                        c = cost(trial)
                        if c < best_c:
                            best, best_c, improved = trial, c, True
                if not improved:
                    break
        put(best)
        miss = _sub(self.world(pose, fore, grip), target)
        return math.sqrt(_dot(miss, miss))

    def lowest(self, pose, names):
        low = -1e9
        for n in names:
            rot, pos = self.fk(pose, n)
            for c in self.parts[n].cubes:
                for dx in (0, c.w):
                    for dy in (0, c.h):
                        for dz in (0, c.d):
                            q = _apply(rot, (c.x + dx, c.y + dy, c.z + dz))
                            low = max(low, pos[1] + q[1])
        return low


LEGS = ('leg_r', 'shin_r', 'leg_l', 'shin_l')


def ground(rig, pose, bones=LEGS):
    """Verschiebt den Körper senkrecht, bis die Fußsohlen genau auf dem Boden (y = 24) stehen."""
    b = tuple(pose.get('body', IDLE['body']))
    b = b + (0.0, 0.0, 0.0) if len(b) == 3 else b
    pose['body'] = b
    dy = 24.0 - rig.lowest(pose, bones)
    pose['body'] = b[:4] + (b[4] + dy,) + b[5:]
    drape(pose)


def drape(pose):
    """Die Schürze liegt oben am Bauch an und hängt unten schwer: Sie gleicht ein gutes Drittel der Rumpfneigung aus,
    ein vorschreitendes Bein schiebt sie nach vorn. Dieselbe Regel rechnet BrontesModel.kt zur Laufzeit."""
    lean = pose['body'][0] + pose['chest'][0]
    thigh = min(pose['leg_r'][0], pose['leg_l'][0]) + pose['body'][0]
    pitch = -lean * APRON_FOLLOW + min(0.0, thigh) * APRON_LEG
    pose['apron'] = (pitch, -pose['chest'][1] * 0.5, -pose['chest'][2] * 0.5)


APRON_FOLLOW = 0.35
APRON_LEG = 0.45


def hold_hammer(rig, pose, grip, up, face, left_at=None, elbows=(None, None), ew=0.04, left_grip=None):
    """Rechte Faust am Stiel bei grip (7 vom Griffende), Kopf zeigt nach up, Schlagfläche nach face. Die linke Faust
    greift left_at Einheiten weiter oben am Stiel (oder an left_grip, Weltpunkt)."""
    up = _norm(up)
    rig.ik(pose, 'arm_r', 'fore_r', FIST, grip, elbows[0], elbow_weight=ew)
    if left_at is not None:
        rig.ik(pose, 'arm_l', 'fore_l', FIST, _add(grip, _scale(up, left_at)), elbows[1], weight=0.005, elbow_weight=ew * 0.25)
    elif left_grip is not None:
        rig.ik(pose, 'arm_l', 'fore_l', FIST, left_grip, elbows[1], elbow_weight=ew)
    rig.aim(pose, 'hammer', _scale(up, -1), _scale(_norm(face), -1))
    fist = rig.world(pose, 'fore_r', FIST)
    rig.place(pose, 'hammer', _sub(fist, _scale(up, HAMMER_GRIP)))


def hammer_world(rig, pose):
    rot, pos = rig.fk(pose, 'hammer')
    return rot, pos


def pin_hammer(rig, pose, rot, pos):
    """Legt den Hammer (in der Hand) genau auf eine Weltlage, z. B. die des abgelegten Hammers."""
    rig.set_world_rot(pose, 'hammer', rot)
    rig.place(pose, 'hammer', pos)


# =====================================================================================================================
# Posen (Radiant, absolute Knochendrehung; sechs Zahlen = Drehung + Versatz). Dieselben Werte stehen in BrontesModel.kt.
# =====================================================================================================================

# Knochen, die nur in bestimmten Aktionen sichtbar sind. Kotlin schaltet sie über die Zeitleiste, die Vorschau blendet
# sie über None aus.
PROPS = ('tongs', 'ingot', 'wedge', 'hammer_dropped', 'tongs_belt', 'hammer')
SHOWN = {
    'idle': ('tongs_belt', 'hammer'),
    'hammer_windup': ('tongs_belt', 'hammer'), 'hammer_hit': ('tongs_belt', 'hammer'),
    'hammer_stuck': ('tongs_belt', 'hammer'),
    'swing_windup': ('tongs_belt', 'hammer'), 'swing_hit': ('tongs_belt', 'hammer'),
    'toss_reach': ('tongs', 'hammer'), 'toss_windup': ('tongs', 'ingot', 'hammer'), 'toss_release': ('tongs', 'hammer'),
    'bellows_windup': ('tongs_belt', 'hammer'), 'bellows_hit': ('tongs_belt', 'hammer'),
    'blinded': ('tongs_belt', 'hammer_dropped'),
    'forge_windup': ('tongs', 'ingot', 'hammer'), 'forge_hit': ('tongs', 'ingot', 'hammer'),
    'wedge_windup': ('tongs_belt', 'wedge', 'hammer_dropped'), 'wedge_empty': ('tongs_belt', 'hammer_dropped'),
    'tongs_windup': ('tongs', 'hammer'), 'tongs_hit': ('tongs', 'hammer'),
    'rise': ('tongs_belt', 'hammer'), 'yield': ('tongs_belt', 'hammer'),
}


def _derive(base=None, **bones):
    pose = dict(base or IDLE)
    pose.update(bones)
    return pose


def _legs(p, r, l):
    """Beine über Weltwinkel: r/l = (Oberschenkel-Pitch in der Welt, Knie, Spreizung). Der Oberschenkel gleicht die
    Beckenneigung aus."""
    bp = p.get('body', (0, 0, 0))[0]
    for s, (thigh, knee, spread) in (('r', r), ('l', l)):
        sign = 1 if s == 'r' else -1
        p[f'leg_{s}'] = (thigh - bp, 0.0, sign * spread)
        p[f'shin_{s}'] = (knee, 0.0, -sign * spread)


def _drop_rot():
    return _rot(*(math.radians(a) for a in DROP_ROT))


def _solve_idle(rig):
    p = dict(IDLE)
    # Hammer auf der rechten Schulter: Faust vor der Schulter, Stiel über die Schulterkugel, Kopf hinter dem Buckel.
    _carry(rig, p)
    ground(rig, p)
    return p


def _hammer_windup(rig):
    p = _derive(body=(-0.1, 0.0, 0.0), chest=(-0.18, 0.0, 0.0), head=(0.12, 0.0, 0.0), apron=(0.12, 0.0, 0.0))
    _legs(p, (-0.32, 0.2, 0.12), (0.26, 0.18, 0.12))
    hold_hammer(rig, p, grip=(-3, -120, 8), up=(0, -0.3, 0.95), face=(0, -0.95, -0.3), left_at=15,
                elbows=((-34, -104, -8), (34, -104, -8)), ew=0.08)
    ground(rig, p)
    return p


def _hammer_hit(rig):
    p = _derive(body=(0.3, 0.0, 0.0), chest=(0.72, 0.0, 0.0), head=(-0.62, 0.0, 0.0), apron=(-0.75, 0.0, 0.0))
    _legs(p, (-0.85, 0.75, 0.12), (0.42, 0.25, 0.12))
    hold_hammer(rig, p, grip=(-2, -14, -44), up=(0, 0.5, -0.87), face=(0, 0.87, 0.5), left_at=15,
                elbows=((-30, -40, -28), (30, -40, -28)))
    ground(rig, p)
    return p


def _hammer_stuck(rig, hit):
    p = _derive(body=(0.24, 0.0, 0.0), chest=(0.58, 0.0, 0.0), head=(-0.42, 0.0, 0.0), apron=(-0.5, 0.0, 0.0))
    _legs(p, (-0.62, 0.62, 0.16), (0.5, 0.3, 0.16))
    ground(rig, p)
    # Der Hammer steckt dort, wo der Schlag ihn hingetrieben hat; beide Fäuste zerren am Stiel.
    rot, pos = rig.fk(hit, 'hammer')
    up = _apply(rot, (0, -1, 0))
    face = _apply(rot, (0, 0, -1))
    grip = _add(pos, _scale(up, HAMMER_GRIP))
    hold_hammer(rig, p, grip=grip, up=up, face=face, left_at=14,
                elbows=((-30, -44, -18), (30, -44, -18)))
    return p


def _swing_windup(rig):
    p = _derive(body=(0.08, 0.35, 0.0), chest=(0.3, 0.55, 0.1), head=(-0.2, -0.75, 0.0), apron=(-0.38, -0.2, 0.0))
    _legs(p, (-0.55, 0.85, 0.32), (-0.35, 0.75, 0.38))
    hold_hammer(rig, p, grip=(-16, -48, 2), up=(-0.72, 0.05, 0.69), face=(-0.69, 0, -0.72), left_at=12,
                elbows=((-40, -56, -6), (4, -56, -30)))
    ground(rig, p)
    return p


def _swing_hit(rig):
    p = _derive(body=(0.08, -0.4, 0.0), chest=(0.3, -0.8, -0.1), head=(-0.2, 0.6, 0.0), apron=(-0.38, 0.25, 0.0))
    _legs(p, (-0.2, 0.55, 0.32), (-0.6, 0.85, 0.38))
    hold_hammer(rig, p, grip=(10, -44, -30), up=(0.75, 0.05, -0.66), face=(0.66, 0, 0.75), left_at=12,
                elbows=((-12, -50, -40), (40, -54, -6)))
    ground(rig, p)
    return p


def _carry(rig, p):
    """Rechte Hand trägt den Hammer auf der Schulter wie in der Ruhe."""
    hold_hammer(rig, p, grip=CARRY_GRIP, up=CARRY_UP, face=(-1, 0, 0), elbows=((-40, -56, -26), None), ew=0.2)


def _tongs_to(rig, p, fist, direction, zhint=(1, 0, 0), elbow=None):
    rig.ik(p, 'arm_l', 'fore_l', FIST, fist, elbow, elbow_weight=0.06)
    rig.aim(p, 'tongs', direction, zhint)


def _toss_reach(rig):
    p = _derive(body=(0.1, -0.15, 0.0), chest=(0.4, -0.5, 0.05), head=(-0.2, 0.55, 0.0), apron=(-0.5, 0.15, 0.0))
    _legs(p, (-0.3, 0.4, 0.14), (0.2, 0.3, 0.14))
    ground(rig, p)
    _carry(rig, p)
    _tongs_to(rig, p, (32, -28, 34), (0.2, 0.55, 0.8), (1, 0, 0), elbow=(40, -56, 12))
    return p


def _toss_windup(rig):
    p = _derive(body=(0.0, 0.1, 0.0), chest=(0.1, 0.3, -0.06), head=(-0.15, -0.3, 0.0), apron=(-0.1, 0.0, 0.0))
    _legs(p, (-0.3, 0.3, 0.14), (0.3, 0.2, 0.14))
    ground(rig, p)
    _carry(rig, p)
    _tongs_to(rig, p, (28, -82, -30), (-0.2, -0.78, -0.6), (1, 0, 0), elbow=(46, -66, -8))
    return p


def _toss_release(rig):
    p = _derive(body=(0.15, -0.15, 0.0), chest=(0.35, -0.4, 0.06), head=(-0.3, 0.45, 0.0), apron=(-0.5, 0.15, 0.0))
    _legs(p, (-0.55, 0.45, 0.14), (0.4, 0.2, 0.14))
    ground(rig, p)
    _carry(rig, p)
    _tongs_to(rig, p, (14, -80, -52), (-0.1, -0.3, -0.95), (1, 0, 0), elbow=(36, -74, -26))
    return p


def _hang_hammer(rig, p, up):
    fist = rig.world(p, 'fore_r', FIST)
    up = _norm(up)
    rig.aim(p, 'hammer', _scale(up, -1), (0, 0, 1))
    rig.place(p, 'hammer', _sub(fist, _scale(up, HAMMER_GRIP)))


def _bellows_windup(rig):
    p = _derive(body=(-0.08, 0.0, 0.0), chest=(-0.16, 0.0, 0.0), head=(0.0, 0.0, 0.0), apron=(0.24, 0.0, 0.0))
    _legs(p, (-0.2, 0.2, 0.2), (0.15, 0.15, 0.2))
    ground(rig, p)
    # Arme weit um einen gedachten Balg vor der Brust gelegt.
    rig.ik(p, 'arm_r', 'fore_r', FIST, (-20, -70, -44), (-46, -64, -20), elbow_weight=0.08)
    rig.ik(p, 'arm_l', 'fore_l', FIST, (20, -70, -44), (46, -64, -20), elbow_weight=0.08)
    _hang_hammer(rig, p, (-0.25, 1, -0.2))
    return p


def _bellows_hit(rig):
    p = _derive(body=(0.2, 0.0, 0.0), chest=(0.62, 0.0, 0.0), head=(-0.5, 0.0, 0.0), apron=(-0.7, 0.0, 0.0))
    _legs(p, (-0.6, 0.55, 0.2), (0.4, 0.2, 0.2))
    ground(rig, p)
    rig.ik(p, 'arm_r', 'fore_r', FIST, (-8, -46, -56), (-34, -52, -36), elbow_weight=0.08)
    rig.ik(p, 'arm_l', 'fore_l', FIST, (8, -46, -56), (34, -52, -36), elbow_weight=0.08)
    _hang_hammer(rig, p, (-0.3, 1, 0.1))
    return p


def _blinded(rig):
    p = _derive(body=(0.25, 0.1, 0.06), chest=(0.55, 0.1, 0.1), head=(0.1, 0.0, 0.12), apron=(-0.7, 0.0, 0.0))
    _legs(p, (0.25, 0.35, 0.18), (-0.5, 0.55, 0.2))
    ground(rig, p)
    eye = rig.world(p, 'eye', (0, 3, -1))
    rig.ik(p, 'arm_r', 'fore_r', FIST, _add(eye, (-6, 2, -8)), (-34, -60, -30), elbow_weight=0.06)
    rig.ik(p, 'arm_l', 'fore_l', FIST, _add(eye, (6, -1, -9)), (34, -60, -30), elbow_weight=0.06)
    # Der Hammer in der Hand liegt genau dort, wo der abgelegte Knochen liegt (Kotlin tauscht nach dem Fallen).
    pin_hammer(rig, p, _drop_rot(), DROP_PIVOT)
    return p


def _forge_windup(rig):
    p = _derive(body=(0.08, 0.15, 0.0), chest=(0.2, 0.2, 0.08), head=(0.1, -0.2, 0.0), apron=(-0.28, 0.0, 0.0))
    _legs(p, (-0.3, 0.45, 0.34), (0.1, 0.45, 0.34))
    ground(rig, p)
    hold_hammer(rig, p, grip=(-22, -118, -6), up=(-0.1, -0.45, 0.88), face=(0, -0.88, -0.45),
                elbows=((-44, -90, -6), None), ew=0.1)
    _tongs_to(rig, p, (14, -30, -34), (-0.35, 0.18, -0.92), (0, -1, 0), elbow=(38, -46, -10))
    return p


def _forge_hit(rig):
    p = _derive(body=(0.2, 0.05, 0.0), chest=(0.55, 0.05, 0.0), head=(-0.3, -0.05, 0.0), apron=(-0.62, 0.0, 0.0))
    _legs(p, (-0.45, 0.6, 0.34), (0.05, 0.6, 0.34))
    ground(rig, p)
    _tongs_to(rig, p, (14, -30, -34), (-0.35, 0.18, -0.92), (0, -1, 0), elbow=(38, -46, -10))
    target = rig.world(p, 'ingot', (0, 0, 0))
    up = _norm((0.1, 0.62, -0.78))
    # Schlagfläche liegt auf dem Rohling: Kopfmitte über dem Rohling, Faust 56 davor.
    face = _norm(_cross((1, 0, 0), up))
    face = face if face[1] > 0 else _scale(face, -1)
    head = _sub(target, _scale(face, 9))
    grip = _sub(head, _scale(up, 56))
    hold_hammer(rig, p, grip=grip, up=up, face=face, elbows=((-40, -50, -20), None), ew=0.06)
    return p


def _planted():
    """Hammer kopfüber rechts neben ihm abgestellt (Schlagfläche nach außen), als Versatz des abgelegten Knochens."""
    pitch, yaw, roll = _euler(PLANT_ROT)
    return (pitch, yaw, roll) + _sub(PLANT_POS, DROP_PIVOT)


PLANT_ROT = frame((0, -1, 0), (1, 0, 0.3))
PLANT_POS = (-40, 24 - 70.5, -14)


def _wedge_windup(rig):
    p = _derive(body=(-0.05, 0.3, 0.0), chest=(-0.12, 0.45, -0.1), head=(0.0, -0.5, 0.0), apron=(0.17, -0.2, 0.0))
    _legs(p, (0.3, 0.2, 0.2), (-0.45, 0.35, 0.2))
    ground(rig, p)
    rig.ik(p, 'arm_r', 'fore_r', FIST, (-30, -118, 22), (-48, -96, 10), elbow_weight=0.06)
    rig.ik(p, 'arm_l', 'fore_l', FIST, (22, -84, -52), (38, -82, -26), elbow_weight=0.04)
    rig.aim(p, 'wedge', (0.05, 0.42, 0.9), (1, 0, 0))
    pin_hammer(rig, p, PLANT_ROT, PLANT_POS)
    p['hammer_dropped'] = _planted()
    return p


def _wedge_empty(rig):
    p = _derive(body=(0.24, -0.2, 0.0), chest=(0.62, -0.35, 0.0), head=(-0.45, 0.3, 0.0), apron=(-0.8, 0.1, 0.0))
    _legs(p, (-0.75, 0.6, 0.18), (0.45, 0.3, 0.2))
    ground(rig, p)
    rig.ik(p, 'arm_r', 'fore_r', FIST, (-10, -36, -50), (-34, -52, -26), elbow_weight=0.05)
    rig.ik(p, 'arm_l', 'fore_l', FIST, (30, -20, -24), (36, -50, -12), elbow_weight=0.05)
    pin_hammer(rig, p, PLANT_ROT, PLANT_POS)
    p['hammer_dropped'] = _planted()
    return p


def _tongs_windup(rig):
    p = _derive(body=(-0.06, -0.1, 0.0), chest=(-0.2, -0.2, -0.12), head=(-0.62, 0.15, 0.0), apron=(0.26, 0.0, 0.0))
    _legs(p, (-0.2, 0.3, 0.2), (0.2, 0.25, 0.2))
    ground(rig, p)
    _carry(rig, p)
    _tongs_to(rig, p, (24, -150, -8), (-0.05, -1, -0.25), (1, 0, 0), elbow=(44, -120, 0))
    p['tongs_jaw'] = (0.0, 0.0, -0.55)
    return p


def _tongs_hit(rig):
    p = _derive(body=(0.12, -0.1, 0.0), chest=(0.42, -0.2, 0.0), head=(-0.3, 0.1, 0.0), apron=(-0.54, 0.0, 0.0))
    _legs(p, (-0.45, 0.45, 0.2), (0.35, 0.25, 0.2))
    ground(rig, p)
    _carry(rig, p)
    _tongs_to(rig, p, (18, -58, -44), (-0.05, 0.6, -0.8), (1, 0, 0), elbow=(40, -60, -16))
    p['tongs_jaw'] = (0.0, 0.0, 0.0)
    return p


def _rise(rig):
    p = _derive(body=(0.55, 0.0, 0.0), chest=(0.5, 0.0, 0.0), head=(-0.2, 0.0, 0.0), apron=(-1.05, 0.0, 0.0))
    _legs(p, (-1.35, 1.6, 0.3), (-0.9, 1.75, 0.3))
    ground(rig, p)
    # Die rechte Faust hält den Hammer, Kopf vorn auf dem Boden; die linke stützt sich aufs Knie.
    hold_hammer(rig, p, grip=(-22, -4, -30), up=(-0.1, 0.45, -0.89), face=(0, 0.89, 0.45),
                elbows=((-40, -40, -12), None), ew=0.08)
    rig.ik(p, 'arm_l', 'fore_l', FIST, (16, -10, -34), (38, -36, -12), elbow_weight=0.06)
    return p


def _yield(rig):
    p = _derive(body=(0.12, 0.0, 0.0), chest=(0.42, 0.0, 0.0), head=(0.35, 0.0, 0.0), apron=(-0.54, 0.0, 0.0))
    _legs(p, (-0.15, 0.2, 0.16), (-0.05, 0.15, 0.16))
    ground(rig, p)
    pommel = (0, 24 - 70.5, -40)
    pin_hammer(rig, p, frame((0, -1, 0), (0, 0, 1)), pommel)
    rig.ik(p, 'arm_r', 'fore_r', FIST, _add(pommel, (0, -9, 0)), (-34, -54, -24), elbow_weight=0.05)
    rig.ik(p, 'arm_l', 'fore_l', FIST, _add(pommel, (0, -22, -1)), (34, -60, -24), elbow_weight=0.05)
    return p


BUILDERS = (
    ('hammer_windup', _hammer_windup), ('hammer_hit', _hammer_hit), ('hammer_stuck', None),
    ('swing_windup', _swing_windup), ('swing_hit', _swing_hit),
    ('toss_reach', _toss_reach), ('toss_windup', _toss_windup), ('toss_release', _toss_release),
    ('bellows_windup', _bellows_windup), ('bellows_hit', _bellows_hit),
    ('blinded', _blinded),
    ('forge_windup', _forge_windup), ('forge_hit', _forge_hit),
    ('wedge_windup', _wedge_windup), ('wedge_empty', _wedge_empty),
    ('tongs_windup', _tongs_windup), ('tongs_hit', _tongs_hit),
    ('rise', _rise), ('yield', _yield),
)

CARRY_GRIP = (-30, -52, -30)
CARRY_UP = (-0.17, -0.85, 0.5)


def _solve_all():
    """Erst die Ruhe lösen und ihre Versätze in die Grundlage einbacken, dann alle Posen auf dem fertigen Modell."""
    rig = Rig(brontes())
    idle = _solve_idle(rig)
    for name, v in idle.items():
        if len(v) >= 6 and any(abs(x) > 1e-6 for x in v[3:6]):
            PIVOT_FIX[name] = tuple(v[3:6])
        IDLE[name] = tuple(v[:3])
    rig = Rig(brontes())
    idle = _solve_idle(rig)
    for name, v in idle.items():
        IDLE[name] = tuple(v)
    raw = {'idle': dict(IDLE)}
    for name, fn in BUILDERS:
        if fn is None:
            continue
        raw[name] = fn(rig)
        if name == 'hammer_hit':
            raw['hammer_stuck'] = _hammer_stuck(rig, raw['hammer_hit'])
    return rig, raw


_RIG, POSES_RAW = _solve_all()


def _rounded(v):
    return tuple(round(float(x), 4) for x in v)


def _preview(name, pose):
    shown = SHOWN.get(name, ())
    out = {k: _rounded(v) for k, v in pose.items()}
    for prop in PROPS:
        if prop not in shown:
            out[prop] = None
    if name == 'blinded':
        out['eye'] = None
    return out


POSES = {name: _preview(name, pose) for name, pose in POSES_RAW.items()}
HEIGHT_EXCLUDE = ('hammer', 'hammer_dropped', 'wedge')


# =====================================================================================================================
# Kotlin-Abgleich: Posentabelle in BrontesModel.kt (zwischen den Marken) schreiben
# =====================================================================================================================

KOTLIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'kotlin', 'dev', 'henny',
                      'hha', 'client', 'model', 'BrontesModel.kt')
BEGIN = '        // POSES BEGIN (generiert aus tools/hha_assets/brontes_model.py --kotlin)'
END = '        // POSES END'


def bone_order():
    return [p.name for root in brontes().parts for p in root.walk()]


def _delta(pose):
    """Nur die Knochen, die sich gegenüber der Ruhe ändern (der Rest bleibt NaN = Ruhe/Gang)."""
    return {k: v for k, v in pose.items() if _rounded(_six(v)) != _rounded(_six(IDLE[k]))}


def _six(v):
    v = tuple(v)
    return v + (0.0, 0.0, 0.0) if len(v) == 3 else v


def kotlin_table():
    order = bone_order()
    parents = []
    for b in order:
        par = _RIG.parent[b]
        parents.append(str(order.index(par)) if par else '-1')
    lines = [BEGIN,
             f'        val BONES = arrayOf({", ".join(chr(34) + b + chr(34) for b in order)})',
             f'        val PARENTS = intArrayOf({", ".join(parents)})']
    for name, pose in POSES_RAW.items():
        if name == 'idle':
            continue
        delta = _delta(pose)
        vals = []
        for b in order:
            v = delta.get(b)
            vals.append('N, N, N, N, N, N' if v is None else ', '.join(f'{x:.4f}f' for x in _six(v)))
        lines.append(f'        val {name.upper()} = floatArrayOf(')
        for i in range(0, len(vals), 2):
            lines.append('            ' + ', '.join(vals[i:i + 2]) + ',')
        lines.append('        )')
    lines.append(END)
    return '\n'.join(lines)


def sync_kotlin(path=KOTLIN):
    with open(path, encoding='utf-8') as f:
        src = f.read()
    pattern = re.compile(re.escape(BEGIN) + '.*?' + re.escape(END), re.S)
    if not pattern.search(src):
        raise SystemExit('Marken für die Posentabelle fehlen in BrontesModel.kt')
    src = pattern.sub(lambda _: kotlin_table(), src)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(src)
    print(f'Posentabelle geschrieben: {len(POSES_RAW) - 1} Posen, {len(bone_order())} Knochen')


if __name__ == '__main__':
    if '--kotlin' in sys.argv:
        sync_kotlin(sys.argv[sys.argv.index('--kotlin') + 1] if len(sys.argv) > sys.argv.index('--kotlin') + 1 else KOTLIN)
