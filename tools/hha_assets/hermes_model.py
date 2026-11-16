"""Hermes, der Götterbote, und sein Schatten: ein Skelett, zwei Varianten.

Läuferkörper (Höhe 64 Einheiten von der Sohle bis zum Scheitel, Renderer-Faktor 0,675 → 2,7 Blöcke), Petasos mit
breiter Krempe und Hutflügeln, kurze azurblaue Chlamys über der linken Schulter, schmaler Goldharnisch mit
Sonnenscheibe, Caduceus mit zwei plastischen Schlangen. Die Rückenflügel bleiben glatte Federn aus dem Federn-Atlas
(feather_art.py, modelkit.Plume).

Varianten: Teile mit Präfix `light_` (und `hat`, die Hut-, Sandalen- und Stabflügel) gehören nur zu Hermes, Teile mit
`shade_` nur zum Schatten des Boten; HermesModel.kt schaltet sie über `corrupted`. Jeder Maler kann über das Attribut
`.shade` einen eigenen Schatten-Maler tragen, `variants()` malt damit den zweiten Atlas (gleiche UVs).

Koordinaten: 1/16 Block vor dem Renderer-Faktor, +y nach unten, -z vorn, Sohlen bei y = 24.
`python tools/hha_assets/hermes_model.py --sync-kotlin` schreibt POSES als Schlüsselposen nach HermesModel.kt.
"""
import math
import os
import re
import sys

from feather_art import CELL_COVERT, CELL_LONG, CELL_PLUME, CELL_TINT
from texkit import Image, save
from modelkit import (
    CAPE, CLOTH, GOLD, HAIR, LEATHER, SKIN,
    Cube, Model, Part, Plume, hexc, mix, ramp, rgb, speckle,
)

# --- Paletten ----------------------------------------------------------------------------------------------
CHITON = CLOTH                    # #bdb9cb → #ffffff
AURUM = GOLD[1:5]                 # #94560f → #ffe27a, poliertes Gold
AURUM_DEEP = GOLD[0:4]
AZURE = CAPE                      # #22357e → #7cbcf5
IVORY = rgb('#9a8f7c', '#c9bfa8', '#e9e2d0', '#faf6ea', '#ffffff')
SERPENT = rgb('#3e1f05', '#7a4510', '#b0721c', '#dca23a', '#f6d26a')
SILVER_SCALE = rgb('#39405e', '#5f6a8c', '#8f9bbb', '#bcc6e0', '#e6ecf8')

# Schatten: verkohlt, aschgrau, Giftgrün als einzige Leuchtfarbe.
CHAR = rgb('#060708', '#101215', '#1b1e22', '#292d32', '#3b4046')
ASH = rgb('#24272a', '#3d4144', '#5d6262', '#848883', '#acafa8')
SMOKE = rgb('#0c0e11', '#1a1d22', '#2b3036', '#41474e', '#5c636a')
OBOL = rgb('#1f1c17', '#36312a', '#514a3c', '#6b6450')
POISON = (120, 255, 140)

FACE_LIGHT = {'top': 0.13, 'bottom': -0.22, 'front': 0.0, 'back': -0.1, 'left': -0.06, 'right': -0.06}
SIDES = ('front', 'back', 'left', 'right')


# --- Maler ------------------------------------------------------------------------------------------------
def surf(pal, base=0.55, grad=0.16, seed=0, roundx=0.0, ridge=0.0, noise=0.03):
    """Glatte Fläche: Flächenlicht, Verlauf von oben nach unten, leichtes Korn (± 3 %), optional Rundung und Lichtkante."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad + (speckle(x, y, seed) - 0.5) * noise * 2
        if roundx and face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += roundx * (0.35 - k * k)
        if ridge and face in SIDES and y == 0:
            v += ridge
        return ramp(pal, v)
    return paint


def to_ash(c, pal=CHAR, lift=0.0):
    """Schatten-Grundumwandlung: Helligkeit bleibt als Form erhalten, Farbe wird Ruß."""
    if c is None:
        return None
    if isinstance(c[0], tuple):
        c = c[0]
    luma = (c[0] * 0.3 + c[1] * 0.59 + c[2] * 0.11) / 255.0
    return ramp(pal, 0.12 + luma * 0.78 + lift)


def ember(t, strength=230):
    color = ramp(rgb('#0a5a26', '#1fbf4a', '#78ff8c', '#e8ffe6'), t)
    return (color, (POISON[0], POISON[1], POISON[2], strength))


_CRACKS = {}
_UV = [(0, 0)]   # Atlas-Position des gerade gemalten Quaders: jeder Quader bekommt eigene Risse


def crack_mask(face, w, h, seed, density):
    """Glutrisse als zusammenhängende Linien: ein Zufallsweg quer über die Fläche, mit kurzer Abzweigung.
    Liefert {(x, y): 2} für den glühenden Kern und {(x, y): 1} für den dunklen Saum daneben."""
    k = (face, w, h, seed, density, _UV[0])
    if k in _CRACKS:
        return _CRACKS[k]
    import random
    rnd = random.Random(hash(k) & 0xFFFFFFFF)
    mask = {}
    if face not in ('top', 'bottom') and w >= 3 and h >= 5 and rnd.random() < density:
        x = rnd.uniform(0.25, 0.75) * (w - 1)
        y = 0.0
        dx = rnd.uniform(-0.7, 0.7)
        branch = rnd.randint(2, max(2, h - 3))
        path = []
        while y < h:
            path.append((int(round(x)), int(y)))
            if int(y) == branch and w >= 4:
                bx = x
                for step in range(rnd.randint(2, 3)):
                    bx += 1 if dx < 0 else -1
                    path.append((int(round(bx)), int(y) + step + 1))
            x = min(w - 1, max(0, x + dx + rnd.uniform(-0.6, 0.6)))
            if rnd.random() < 0.3:
                dx = -dx * 0.8
            y += 1
            if rnd.random() < 0.12 and y > h * 0.5:
                break
        for px, py in path:
            if 0 <= px < w and 0 <= py < h:
                mask[(px, py)] = 2
        for (px, py) in list(mask):
            for nx in (px - 1, px + 1):
                if 0 <= nx < w and (nx, py) not in mask:
                    mask[(nx, py)] = 1
    _CRACKS[k] = mask
    return mask


def crack_at(face, x, y, w, h, seed, density=1.0):
    return crack_mask(face, w, h, seed, density).get((x, y), 0)


def shaded(light, shade):
    light.shade = shade
    return light


def charred(light, pal=CHAR, lift=0.0, cracks=0.0, seed=0, holes=0.0, hole_from=0.0):
    """Schatten-Maler aus einem Licht-Maler: Ruß, Glutrisse und ausgefranste Löcher (Kante unten)."""
    def paint(face, x, y, w, h):
        if holes and face in SIDES:
            depth = (y + 0.5) / h
            if depth > hole_from:
                n = speckle(x * 2 + seed, y, seed % 9)
                if n < holes * (depth - hole_from) / max(1.0 - hole_from, 0.01) + 0.05 * (x % 3 == 0):
                    return None
        if cracks:
            k = crack_at(face, x, y, w, h, seed, cracks)
            if k == 2:
                return ember(0.55 + 0.3 * speckle(x, y, seed), 235)
            if k == 1:
                return ramp(pal, 0.04)
        return to_ash(light(face, x, y, w, h), pal, lift)
    return shaded(light, paint)


def Cb(x, y, z, w, h, d, paint, dilate=0.0):
    """Atlas rechnet in ganzen Texeln; Positionen dürfen halbe Einheiten haben."""
    return Cube(x, y, z, max(1, round(w)), max(1, round(h)), max(1, round(d)), paint, dilate)


def block(x, y, z, w, h, d, paint, bevel=1.0, side_paint=None):
    """Quader mit gefasten senkrechten Kanten: Kreuz aus zwei Quadern (der zweite minimal gedehnt gegen Flimmern)."""
    return [
        Cb(x + bevel, y, z, w - 2 * bevel, h, d, paint),
        Cb(x, y, z + bevel, w, h, d - 2 * bevel, side_paint or paint, dilate=0.02),
    ]


# --- Federn -----------------------------------------------------------------------------------------------
def fan(name, pivot, rot, side, count, length, spread, plane='x', width=10, cell=CELL_PLUME):
    """Kleiner Flügel aus Einzelfedern (Hut, Sandalen, Stab)."""
    plumes = []
    for i in range(count):
        angle = spread / 2.0 - spread * i / max(count - 1, 1)
        reach = length * (1.0 - 0.12 * abs(i - count * 0.3))
        if plane == 'x':
            plumes.append(Plume((0, 0, 0.12 * i), (0, 0, side * -angle), width, reach, cell,
                                'x+' if side > 0 else 'x-', flip=side < 0, bend=0.06, sweep=-0.1, shade=(0.8, 1.0)))
        else:
            plumes.append(Plume((0.12 * i * side, 0, 0), (angle, 0, 0), width, reach, cell, 'z', flip=side < 0,
                                bend=0.06 * side, sweep=-0.1, shade=(0.8, 1.0)))
    return Part(name, pivot, rot=rot, plumes=plumes)


def feather_row(side, bone_length, lengths, rolls, width, z, tilt, cell, bend=0.07, sweep=0.0, shade=(0.72, 1.0), down=0.6, skip=None):
    out = []
    count = len(lengths)
    for i, (length, roll) in enumerate(zip(lengths, rolls)):
        if skip is not None and skip(i):
            continue
        along = (i + 0.5) / count * bone_length
        out.append(Plume(
            (side * along, down, z + (i % 2) * 0.25),
            (tilt + (i % 2) * 2, 0, -side * roll),
            width, length, cell, 'y', flip=side < 0, bend=bend, sweep=side * sweep, shade=shade,
        ))
    return out


def wing_layers(side, bone, long_lengths, rolls, greater, lesser, burnt=False):
    """Schwungfedern hinten, Decken vorn und hinten, kleine Decken am Knochen. burnt: Schatten (lückig, ausgefranst)."""
    greater_rolls = [r * 0.8 for r in rolls[:: max(1, len(rolls) // max(len(greater), 1))]][:len(greater)]
    greater_rolls += [greater_rolls[-1] if greater_rolls else 0] * (len(greater) - len(greater_rolls))
    lesser_rolls = [r * 0.6 for r in greater_rolls[:len(lesser)]]
    lesser_rolls += [lesser_rolls[-1] if lesser_rolls else 0] * (len(lesser) - len(lesser_rolls))
    if not burnt:
        return (
            feather_row(side, bone, long_lengths, rolls, 13, 0.5, 4, CELL_LONG, bend=0.09, sweep=0.03)
            + feather_row(side, bone, greater, greater_rolls, 10, -0.8, 0, CELL_COVERT, bend=-0.05, shade=(0.78, 1.0))
            + feather_row(side, bone, greater, greater_rolls, 10, 1.7, 8, CELL_COVERT, bend=0.1, shade=(0.7, 0.96))
            + feather_row(side, bone, lesser, lesser_rolls, 8, -1.4, -4, CELL_COVERT, bend=-0.06, shade=(0.86, 1.0), down=-0.5)
        )
    # Verbrannt: nur jede zweite Schwungfeder, kürzer und ausgefranst (LOOSE-Zelle), Decken nur noch in Fetzen.
    burnt_long = [length * (0.62 + 0.18 * ((i * 7) % 3) / 2.0) for i, length in enumerate(long_lengths)]
    return (
        feather_row(side, bone, burnt_long, rolls, 11, 0.5, 6, CELL_TINT, bend=0.12, sweep=0.06, shade=(0.6, 0.9), skip=lambda i: i % 2 == 1)
        + feather_row(side, bone, [g * 0.7 for g in greater], greater_rolls, 9, -0.8, 0, CELL_TINT, bend=-0.04, shade=(0.7, 0.95), skip=lambda i: i % 3 == 2)
    )


# --- Haltungen der Flügel (für POSES und Kotlin) -----------------------------------------------------------
def wings(raise_=0.28, back=0.55, fold=0.45, beat=0.0, beat_late=0.0, side_only=None):
    """Absolute Knochenwinkel beider Rückenflügel aus Heben, Anlegen nach hinten und Falten (wie applyWing in Kotlin)."""
    out = {}
    for s, side in (('r', 1.0), ('l', -1.0)):
        if side_only and s != side_only:
            continue
        out[f'wing_back_{s}'] = (0.0, side * back, side * (raise_ + beat))
        out[f'wing_fore_{s}'] = (0.0, side * 1.25 * fold, side * (-0.35 * fold + beat_late * 0.5))
        out[f'wing_hand_{s}'] = (0.0, side * 1.3 * fold, side * (0.2 * fold + beat_late * 0.7))
    return out


# --- Bau ----------------------------------------------------------------------------------------------------
LIGHT_ONLY = ['hat', 'wing_head_r', 'wing_head_l', 'light_calf_r', 'light_calf_l', 'wing_foot_r', 'wing_foot_l',
              'light_serpent', 'light_orb', 'caduceus_wing_l', 'light_hand_l',
              'light_feathers_back_r', 'light_feathers_fore_r', 'light_feathers_hand_r',
              'light_feathers_back_l', 'light_feathers_fore_l', 'light_feathers_hand_l']
SHADE_ONLY = ['shade_cowl', 'shade_smoke_r', 'shade_smoke_l', 'shade_coins', 'shade_orb', 'shade_hand_l',
              'shade_feathers_back_r', 'shade_feathers_fore_r', 'shade_feathers_hand_r',
              'shade_feathers_back_l', 'shade_feathers_fore_l', 'shade_feathers_hand_l']
PROPS = ['loot_orb', 'scroll', 'letter']


def back_wing(s, side):
    """Rückenflügel: Oberarm, Unterarm und Hand als Gelenke; die Federn hängen an Kindknochen je Variante."""
    bone = charred(surf(IVORY, 0.7, 0.1, 70, roundx=0.3), ASH, -0.4)
    knuckle = charred(surf(AURUM, 0.62, 0.1, 71, ridge=0.15), CHAR, 0.1)

    def segment(length, thick):
        return Cb(0 if side > 0 else -length, -thick / 2.0, -thick / 2.0, length, thick, thick, bone)

    def feathers(bone_name, length, long_lengths, rolls, greater, lesser):
        return [
            Part(f'light_feathers_{bone_name}_{s}', plumes=wing_layers(side, length, long_lengths, rolls, greater, lesser)),
            Part(f'shade_feathers_{bone_name}_{s}', plumes=wing_layers(side, length, long_lengths, rolls, greater, lesser, burnt=True)),
        ]

    hand = Part(f'wing_hand_{s}', (side * 15, 0, 0), cubes=[segment(14, 2), Cb(-1.5, -1.5, -1.5, 3, 3, 3, knuckle)],
                children=feathers('hand', 14, [31, 36, 40, 44, 46, 44, 40, 35], [2, 7, 13, 20, 28, 37, 47, 58],
                                  [17, 18, 18, 17, 15], [9, 9, 9, 8]))
    fore = Part(f'wing_fore_{s}', (side * 14, 0, 0), cubes=[segment(16, 3), Cb(-2, -2, -2, 4, 4, 4, knuckle)],
                children=[hand] + feathers('fore', 16, [32, 33, 34, 35, 36, 36, 37], [0, 0, 1, 1, 1, 2, 2],
                                           [16, 16, 17, 17, 17, 16], [9, 9, 10, 10]))
    return Part(f'wing_back_{s}', (side * 3.0, -13, 6.0), cubes=[
        segment(14, 3),
        Cb(-2.5, -2.5, -2.5, 5, 5, 5, knuckle),
    ], children=[fore] + feathers('back', 14, [22, 25, 28, 30, 31], [0, 0, 0, 0, 0], [12, 13, 14, 14], [8, 9, 9]))


def hermes():
    # ---- Materialien ----
    skin = charred(surf(SKIN, 0.6, 0.12, 1, roundx=0.25), CHAR, 0.08, cracks=0.5, seed=1)
    skin_dark = charred(surf(SKIN, 0.5, 0.12, 2, roundx=0.25), CHAR, 0.05, cracks=0.5, seed=2)
    hair = charred(surf(HAIR, 0.6, 0.22, 5, roundx=0.3, noise=0.04), CHAR, 0.0)
    chiton = charred(surf(CHITON, 0.62, 0.18, 3, roundx=0.2), ASH, -0.4)
    gold = charred(surf(AURUM, 0.6, 0.2, 4, roundx=0.3, ridge=0.18), CHAR, 0.06, cracks=0.6, seed=4)
    gold_dark = charred(surf(AURUM_DEEP, 0.5, 0.16, 7, roundx=0.3, ridge=0.15), CHAR, 0.04)
    leather = charred(surf(LEATHER, 0.55, 0.12, 6, roundx=0.2), CHAR, 0.0)
    azure = surf(AZURE, 0.5, 0.2, 8, roundx=0.15)

    def pleats(seed, hem=True):
        """Chiton in Falten: senkrechte weiche Bahnen, dunkler Saum, im Schatten zerfetzt."""
        def paint(face, x, y, w, h):
            v = 0.62 + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.14 + (speckle(x, y, seed) - 0.5) * 0.05
            if face in SIDES:
                v += 0.12 * math.sin(x * 1.9 + seed)
                if hem and y == h - 1:
                    v -= 0.22
            return ramp(CHITON, v)
        return charred(paint, ASH, -0.55, seed=seed, holes=0.8, hole_from=0.55)

    def cloak(seed, top_trim=False, bottom_trim=False):
        """Chlamys: azurblaue Wolle mit weichen Falten und einer schmalen Goldborte; im Schatten ein zerfetzter Aschelappen."""
        def paint(face, x, y, w, h):
            v = 0.5 + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.16 + 0.12 * math.sin(x * 1.3 + seed) + (speckle(x, y, seed) - 0.5) * 0.05
            if face in SIDES and ((bottom_trim and y == h - 1) or (top_trim and y == 0)):
                return ramp(AURUM, 0.7 + FACE_LIGHT[face])
            return ramp(AZURE, v)

        def shade(face, x, y, w, h):
            depth = (y + 0.5) / h
            if face in SIDES and speckle(x * 2 + seed, y, seed % 9) < (0.7 * depth if bottom_trim else 0.22 * depth):
                return None
            return to_ash(paint(face, x, y, w, h), ASH, -0.3)
        return shaded(paint, shade)

    def face_paint(face, x, y, w, h):
        """Gesicht 7 x 8: Stirnlocken, Brauen, Augen mit Seitenblick, Wangen, schiefes Lächeln."""
        if face in ('top', 'back'):
            return hair(face, x, y, w, h)
        if face != 'front':
            return hair(face, x, y, w, h) if y < 3 else skin(face, x, y, w, h)
        cheek = ramp(SKIN, 0.66 - (0.12 if x in (0, w - 1) else 0.0) + (0.5 - y / 7.0) * 0.08)
        if y == 0:
            return ramp(HAIR, 0.6 if x % 3 else 0.45)
        if y == 2 and x in (1, 2, 4, 5):
            return ramp(HAIR, 0.3)                       # Brauen
        if y == 3:
            if x in (1, 4):
                return (hexc('#7fc4ff'), (215, 238, 255, 255))   # Iris: Blick zur Seite, verschmitzt
            if x in (2, 5):
                return hexc('#f6f2ea')
        if y == 4 and x in (1, 2, 4, 5):
            return ramp(SKIN, 0.56)
        if y == 5 and x == 5:
            return hexc('#9a4e3c')                       # hochgezogener Mundwinkel
        if y == 6:
            if x in (2, 3, 4):
                return hexc('#9a4e3c') if x == 2 else hexc('#b0604a')
            if x == 5:
                return ramp(SKIN, 0.54)                  # Grübchen
        if y == 7:
            return ramp(SKIN, 0.58 if x in (2, 3, 4) else 0.5)
        return cheek

    def face_shade(face, x, y, w, h):
        if face == 'front':
            if y == 3 and x in (1, 2, 4, 5):
                return ember(0.75 if x in (2, 4) else 0.45, 255)
            return ramp(CHAR, 0.05 + 0.04 * (y / 7.0))
        return to_ash(face_paint(face, x, y, w, h), CHAR, 0.0)
    shaded(face_paint, face_shade)

    def sun(face, x, y, w, h):
        """Sonnenscheibe des Harnischs: rund, gedämpftes Leuchten (≤ 140)."""
        cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
        if face == 'front':
            d = math.hypot(x - cx, y - cy)
            if d > 2.2:
                return None
            if d < 1.0:
                return (hexc('#fffbe8'), (255, 250, 232, 140))
            return (ramp(AURUM, 0.9), (255, 240, 214, 80))
        if face in ('top', 'bottom') and (x in (0, w - 1)):
            return None
        return ramp(AURUM, 0.7 + FACE_LIGHT[face])

    def sun_shade(face, x, y, w, h):
        res = sun(face, x, y, w, h)
        if res is None:
            return None
        if face == 'front':
            d = math.hypot(x - (w - 1) / 2.0, y - (h - 1) / 2.0)
            return ember(0.35, 120) if d < 1.0 else ramp(CHAR, 0.4)   # erloschen bis auf einen Funken
        return to_ash(res, CHAR, 0.1)
    shaded(sun, sun_shade)

    def cuirass_paint(lower):
        """Muskelharnisch: Mittelgrat, Bogen unter den Brustmuskeln, Strahlen um die Sonnenscheibe."""
        def paint(face, x, y, w, h):
            v = 0.6 + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.22 + (speckle(x, y, 80) - 0.5) * 0.05
            if face in SIDES and w > 2:
                k = (x + 0.5) / w * 2 - 1
                v += 0.3 * (0.35 - k * k)
            if face in SIDES and y == 0:
                v += 0.18
            if face == 'front':
                cx = (w - 1) / 2.0
                dx = abs(x - cx)
                if not lower:
                    if dx < 0.6:
                        v -= 0.2                                   # Mittelgrat
                    elif y == h - 2 and 1 < dx < cx - 0.5:
                        v -= 0.2                                   # Unterkante der Brustmuskeln
                    elif y < h - 2:
                        v += 0.12 * (1.0 - abs(dx - cx * 0.5) / (cx * 0.5))   # gewölbte Brust
                else:
                    if y in (1, 2) and dx > 2.6 and (int(dx) + y) % 2 == 0:
                        v -= 0.16                                  # Strahlen
            return ramp(AURUM, v)
        return charred(paint, CHAR, 0.06, cracks=0.7, seed=80 + lower)

    # =================================================================== Kopf
    brow = charred(surf(SKIN, 0.7, 0.0, 12), CHAR, 0.12)
    nose = charred(surf(SKIN, 0.68, 0.2, 11), CHAR, 0.12)
    curl = charred(surf(HAIR, 0.66, 0.4, 14, noise=0.04), CHAR, 0.0)
    curls = [Cb(cx, cy, cz, 2, 2, 2, curl) for cx, cy, cz in [
        (-5, -7.5, -3.5), (3, -7.5, -3.5), (-5.2, -6, -1), (3.2, -6, -1), (-5.2, -6.5, 1.5), (3.2, -6.5, 1.5),
        (-4, -5, 3.6), (-1, -4.5, 3.8), (2, -5, 3.6), (-3, -2.5, 3.2), (1, -2.5, 3.2),
    ]]

    # Petasos: Krone mit Goldband, Krempe als Ring aus 8 Segmenten (Durchmesser 15), leicht nach unten geneigt.
    felt = shaded(surf(AZURE, 0.52, 0.22, 21, roundx=0.3, ridge=0.12), lambda *a: ramp(CHAR, 0.2))
    felt_top = shaded(surf(AZURE, 0.6, 0.1, 22, roundx=0.2), lambda *a: ramp(CHAR, 0.2))

    def brim_paint(face, x, y, w, h):
        if face == 'top':
            return ramp(AZURE, 0.62 + 0.1 * (y / max(h - 1, 1)) + (speckle(x, y, 23) - 0.5) * 0.04)
        if face == 'bottom':
            return ramp(AZURE, 0.3)
        return ramp(AURUM, 0.68 + FACE_LIGHT[face])
    shaded(brim_paint, lambda *a: ramp(CHAR, 0.2))
    band = shaded(surf(AURUM, 0.66, 0.2, 24, roundx=0.3, ridge=0.2), lambda *a: ramp(CHAR, 0.2))

    brim_segments = []
    for k in range(8):
        brim_segments.append(Part(f'hat_brim_{k}', (0, 0, 0), rot=(6, k * 45, 0), cubes=[
            Cb(-3.25, -1, -7.5, 6.5, 2, 4.5, brim_paint),
        ]))
    hat = Part('hat', (0, -9.7, 0), cubes=[
        Cb(-4.5, -1, -4.5, 9, 2, 9, felt),                   # Innenkrempe (deckt die Mitte des Rings)
        *block(-4, -2.5, -4, 8, 2, 8, band, bevel=1.5),      # Goldband
        *block(-3.5, -4.5, -3.5, 7, 2, 7, felt, bevel=1.5),  # flache Krone
        *block(-2.5, -5.5, -2.5, 5, 1, 5, felt_top, bevel=1),  # gerundeter Deckel
    ], children=brim_segments)

    # Kapuze des Schattens: zerfetzt, vorn offen, nur die Augen glimmen darunter.
    def cowl_paint(face, x, y, w, h):
        v = 0.4 + FACE_LIGHT[face] + 0.08 * math.sin(x * 1.4) + (speckle(x, y, 31) - 0.5) * 0.06
        if face == 'bottom':
            return None
        if face == 'front':
            if 2 <= x <= w - 3 and y >= 2:
                return None  # Öffnung fürs Gesicht
        if face in ('left', 'right', 'back') and y >= h - 3:
            if speckle(x * 3, y, 32) < 0.25 + 0.2 * (y - (h - 3)):
                return None
        return ramp(SMOKE, v)
    cowl_cloth = shaded(cowl_paint, cowl_paint)

    def tatters(seed):
        def paint(face, x, y, w, h):
            if face in ('top', 'bottom'):
                return ramp(SMOKE, 0.3)
            depth = (y + 0.5) / h
            if speckle(x * 3 + seed, y * 2, seed % 7) < depth * 0.75 - 0.05 + 0.25 * (x % 2):
                return None
            return ramp(SMOKE, 0.42 + FACE_LIGHT[face] - depth * 0.15 + 0.06 * math.sin(x * 1.7 + seed))
        return shaded(paint, paint)

    shade_cowl = Part('shade_cowl', (0, 0, 0), cubes=[
        Cb(-5.5, -9.5, -5.8, 11, 9.5, 10.5, cowl_cloth),
        Cb(-4.5, -10.5, -4.6, 9, 1, 8.5, cowl_cloth),
        Cb(-3, -11.5, -2.5, 6, 2, 6.5, cowl_cloth),          # die Kapuze läuft hinten spitz zu
        Cb(-1.5, -12, 1.5, 3, 2, 4, cowl_cloth),
        Cb(-1.5, -10.4, -7, 3, 2, 2, cowl_cloth),            # Spitze über der Stirn
        Cb(-7, 0, -3.5, 14, 3, 8, tatters(33)),              # Schulterkragen, zerfetzt
        Cb(-3, 4, 2, 6, 6, 3, tatters(34)),                  # Zipfel im Nacken
    ])

    head = Part('head', (0, -19, -0.5), cubes=[
        *block(-4.5, -9, -4.5, 9, 8, 9, face_paint),         # Schädel 9 x 9 x 9 mit Kiefer
        Cb(-3, -1, -4, 6, 2, 7, skin),                        # Kiefer
        Cb(-1.5, -1.2, -4.6, 3, 2, 1, nose),                  # Kinn
        Cb(-3, -7.6, -5.0, 6, 1, 1, brow),                    # Brauenwulst
        Cb(-0.5, -5.6, -5.5, 1, 2, 1, nose),                  # Nase
        Cb(-2, 0, -2, 4, 3, 4, skin_dark),                    # Hals
        *block(-4.5, -8.5, 1, 9, 7, 4.5, hair, bevel=1),       # Hinterkopf-Locken
    ] + curls, children=[
        hat,
        fan('wing_head_r', (-3.4, -12.0, 0.5), (-22, -30, 22), -1, 5, 9.5, 40, width=5),
        fan('wing_head_l', (3.4, -12.0, 0.5), (-22, 30, -22), 1, 5, 9.5, 40, width=5),
        shade_cowl,
    ])

    # =================================================================== Stab
    shaft = charred(lambda f, x, y, w, h: ramp(AURUM, 0.56 + FACE_LIGHT[f] + 0.18 * (1 - abs(x - 0.5)) - 0.1 * (y / h)), CHAR, 0.08, cracks=0.4, seed=40)
    grip = charred(surf(LEATHER, 0.5, 0.0, 41), CHAR, 0.0)
    ring = charred(surf(AURUM, 0.72, 0.2, 42, ridge=0.2), CHAR, 0.1)
    def scales(pal, seed):
        def paint(f, x, y, w, h):
            return ramp(pal, 0.58 + FACE_LIGHT[f] + (0.1 if y == 0 else -0.06 if y == h - 1 else 0.0) + (speckle(x, y, seed) - 0.5) * 0.05)
        return charred(paint, CHAR, 0.12)

    def snake_head(pal):
        def paint(face, x, y, w, h):
            if face in ('left', 'right') and y == 0 and x == 1:
                return hexc('#120a04')
            if face == 'front' and y == h - 1:
                return ramp(pal, 0.25)
            return ramp(pal, 0.7 + FACE_LIGHT[face] - 0.1 * (y / max(h - 1, 1)))
        return charred(paint, CHAR, 0.15)

    def helix(name, mirror, light):
        """Schlange, 1,5 Umdrehungen um den Stab, vom Schwanz (unten) zum Kopf (oben, außen)."""
        pal = SERPENT if light else SILVER_SCALE
        scale_paint = scales(pal, 44 if light else 45)
        cubes = []
        steps = 18
        r = 1.9
        for i in range(steps + 1):
            t = i / steps
            a = math.pi / 2 + 3 * math.pi * t
            x = mirror * r * math.sin(a)
            z = r * math.cos(a)
            y = -8 - 20 * t
            cubes.append(Cb(x - 1, y - 1, z - 1, 2, 2, 2, scale_paint))
        # Hals und Kopf: steigen nach außen und schauen zur Kugel.
        hx = -mirror * 3.2
        cubes.append(Cb(hx - 1, -31, -1, 2, 3, 2, scale_paint))
        cubes.append(Cb(hx - 1.5 + mirror * 0.6, -34, -2, 3, 3, 4, snake_head(pal)))
        return Part(name, (0, 0, 0), cubes=cubes) if light else cubes

    def orb_paint(face, x, y, w, h):
        cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
        d = max(abs(x - cx), abs(y - cy)) / max(cx, 0.5)
        if d > 0.75:
            return (hexc('#e8f4ff'), (235, 245, 255, 200))
        return (hexc('#ffffff'), (255, 252, 240, 255))

    def orb_cracked(face, x, y, w, h):
        cx = (w - 1) / 2.0
        if abs(x - cx - (y - (h - 1) / 2.0) * 0.6) < 0.6:
            return ember(0.85, 255)  # der Sprung
        return ramp(SMOKE, 0.55 + FACE_LIGHT[face] + 0.1 * (1 - abs(x - cx) / max(cx, 1)))
    shaded(orb_cracked, orb_cracked)

    def orb_cubes(paint):
        return [
            Cb(-2.5, -2.5, -1.5, 5, 5, 3, paint),
            Cb(-1.5, -2.5, -2.5, 3, 5, 5, paint),
            Cb(-2.5, -1.5, -2.5, 5, 3, 5, paint),
        ]

    caduceus = Part('caduceus', (0, 12, 0), rot=(90, 0, 0), cubes=[
        Cb(-1, -32, -1, 2, 52, 2, shaft),                     # Schaft
        Cb(-1.5, -3, -1.5, 3, 6, 3, grip),                    # Griff (in der Faust)
        *block(-2, -8, -2, 4, 2, 4, ring),                    # Ring unter den Schlangenschwänzen
        *block(-2, 5, -2, 4, 2, 4, ring),
        *block(-2, 18, -2, 4, 2, 4, ring),                    # Fußring
        Cb(-1, 20, -1, 2, 3, 2, gold_dark),                   # Spitze unten
        *block(-2, -34, -2, 4, 2, 4, ring),                   # Kelch unter der Kugel
        *helix(None, -1, False),                              # zweite Schlange (bleibt auch beim Schatten)
    ], children=[
        Part('light_orb', (0, -36.5, 0), cubes=orb_cubes(orb_paint)),
        Part('shade_orb', (0, -36.5, 0), cubes=orb_cubes(orb_cracked)),
        helix('light_serpent', 1, True),
        fan('caduceus_wing_r', (-1.5, -33, 0.5), (0, 0, 40), -1, 4, 9, 50, width=6),
        fan('caduceus_wing_l', (1.5, -33, 0.5), (0, 0, -40), 1, 4, 9, 50, width=6),
    ])

    # =================================================================== Arme
    def bracelet():
        return charred(surf(AURUM, 0.66, 0.0, 50, roundx=0.4, ridge=0.2), CHAR, 0.12, cracks=0.5, seed=50)

    def fist_paint(face, x, y, w, h):
        v = 0.6 + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.1
        if face in ('front', 'left', 'right') and y in (1, 3) and x % 2 == 1:
            v -= 0.12  # Fingerfugen
        return ramp(SKIN, v)
    charred(fist_paint, CHAR, 0.08, cracks=0.8, seed=51)

    sleeve = charred(surf(CHITON, 0.66, 0.2, 52, roundx=0.3), ASH, -0.55, holes=0.7, hole_from=0.5, seed=52)

    def arm(s, sx, hand_cubes, kids):
        return Part(f'arm_{s}', (sx * 9, -15, 0), cubes=[
            *block(-2.5, -2.5, -2.5, 5, 5, 5, sleeve),         # Chiton-Ärmel über dem Deltamuskel
            *block(-2, 0, -2, 4, 11, 4, skin),                 # Oberarm
        ], children=[
            Part(f'fore_{s}', (0, 11, 0), cubes=[
                *block(-2, 0, -2, 4, 5, 4, skin),
                *block(-1.5, 5, -1.5, 3, 5, 3, skin, bevel=0.5),
                *block(-2, 6.5, -2, 4, 2, 4, bracelet()),
            ] + hand_cubes, children=kids),
        ])

    fist_r = [*block(-2, 10, -2, 4, 4, 4, fist_paint), Cb(-1, 9.6, -2.9, 2, 2, 2, fist_paint)]  # Faust, Daumen vorn
    hand_l_light = Part('light_hand_l', cubes=[
        *block(-2, 10, -2, 4, 3, 4, fist_paint),               # Handteller
        Cb(-1.5, 13, -2, 3, 2, 4, fist_paint),                 # Finger
        Cb(-2.8, 10.5, -2.6, 2, 2, 2, fist_paint),             # Daumen (zum Schnippen)
    ])
    claw = charred(surf(SKIN, 0.4, 0.1, 53), CHAR, 0.0, cracks=1.0, seed=53)
    hand_l_shade = Part('shade_hand_l', cubes=[
        *block(-2, 10, -2, 4, 3, 4, claw),
        Cb(-1.5, 13, -2, 3, 3, 3, claw),                        # längere, krallige Finger
        Cb(-2.8, 10.5, -2.6, 2, 2, 2, claw),
    ])

    def loot_paint(face, x, y, w, h):
        """Beute-Kugel: gestohlenes Licht, perlmuttartig, wirbelnd."""
        swirl = 0.5 + 0.5 * math.sin(x * 1.7 + y * 1.1 + {'front': 0, 'back': 2, 'left': 1, 'right': 3, 'top': 4, 'bottom': 5}[face])
        c = mix(hexc('#e6f0ff'), hexc('#ffd8f4'), swirl)
        return (c, (255, 244, 250, 210))

    def loot_shade(face, x, y, w, h):
        swirl = 0.5 + 0.5 * math.sin(x * 1.7 + y * 1.1)
        return ember(0.4 + 0.4 * swirl, 220)
    shaded(loot_paint, loot_shade)

    loot_orb = Part('loot_orb', (0, 16, -1), cubes=block(-2, -2, -2, 4, 4, 4, loot_paint, bevel=1))
    paper = charred(surf(rgb('#b39d7c', '#d4c19f', '#f6eedb'), 0.72, 0.1, 56), ASH, -0.2)
    scroll = Part('scroll', (0, 12, -1.5), rot=(0, 0, 90), cubes=[
        Cb(-1, -6, -1, 2, 12, 2, paper),
        Cb(-1.5, -2, -1.5, 3, 4, 3, charred(surf(AURUM, 0.6, 0.0, 57), CHAR)),
    ])

    def black_letter(face, x, y, w, h):
        if face == 'front':
            cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
            d = math.hypot(x - cx, y - cy)
            if d < 1.3:
                return (hexc('#c4fbff'), (196, 251, 255, 255))
            if d < 2.2:
                return (hexc('#14a6c4'), (60, 214, 239, 230))
        return hexc('#130e19') if (x + y) % 5 else hexc('#1f1828')
    shaded(black_letter, black_letter)
    letter = Part('letter', (0, 13, -2.5), cubes=[Cb(-4, -3, -0.5, 8, 6, 1, black_letter)])

    arm_r = arm('r', -1, fist_r, [caduceus])
    arm_l = arm('l', 1, [], [hand_l_light, hand_l_shade, loot_orb, scroll, letter])

    # =================================================================== Chlamys
    fibula = shaded(lambda f, x, y, w, h: ramp(AURUM, 0.8 + FACE_LIGHT[f] - 0.15 * (abs(x - (w - 1) / 2) > 0.6)), lambda *a: ramp(CHAR, 0.35))
    tassel = shaded(surf(AURUM, 0.7, 0.2, 63), lambda *a: ramp(CHAR, 0.3))

    def columns(spec, seed, top=False, bottom=False):
        """Stoffbahnen nebeneinander: (x, y, Länge, z-Versatz) je 3 breite Bahn; ergibt Falten und eine schräge Kante."""
        return [Cb(x, y, z, 3, length, 2, cloak(seed + i, top_trim=top, bottom_trim=bottom)) for i, (x, y, length, z) in enumerate(spec)]

    # Schräg: an der linken Schulter (+x) hoch angesetzt, zur rechten Hüfte hin fallend; die lange Ecke hängt links.
    cape3 = Part('cape3', (0, 10, 0), cubes=columns([(-1, 0, 5, 0.6), (2, 0, 8, 0), (5, 0, 6, 0.4)], 70, bottom=True) + [
        Cb(3, 8, -0.5, 2, 2, 2, tassel),
    ])
    cape2 = Part('cape2', (0, 11, 0), cubes=columns([(-13, 0, 3, 0.2), (-10, 0, 5, 0.6), (-7, 0, 7, 0), (-4, 0, 9, 0.5), (-1, 0, 10, 0), (2, 0, 10, 0.5), (5, 0, 10, 0.1)], 60, bottom=True), children=[cape3])
    cape1 = Part('cape1', (2, -16, 5.0), cubes=columns([(-13, 7, 4, 0.2), (-10, 5, 6, 0.5), (-7, 3, 8, 0), (-4, 1.5, 9.5, 0.4), (-1, 0, 11, 0), (2, 0, 11, 0.4), (5, 0, 11, 0)], 50, top=True) + [
        # Über die linke Schulter geworfen, vorn mit einer Fibel gehalten.
        Cb(5.5, -0.5, -11.4, 3, 3, 1, fibula),
    ], children=[cape2, Part('cape_drape', (7, -1.5, 0), rot=(0, 0, 16), cubes=[
        # Über die linke Schulter geworfen; vorn hängt ein schräger Zipfel bis auf die Brust.
        Cb(-3.5, -1, -10.5, 7, 2, 11.5, cloak(57)),
        Cb(-3.5, 0.5, -11, 3, 7, 2, cloak(58, bottom_trim=True)),
        Cb(-0.5, 0.5, -11, 3, 4.5, 2, cloak(59, bottom_trim=True)),
    ])])

    # =================================================================== Rumpf
    chest_cloth = charred(surf(CHITON, 0.6, 0.18, 82, roundx=0.25), ASH, -0.55)
    torso = Part('torso', (0, -5, 0), cubes=[
        *block(-2, -19.5, -2.5, 4, 4, 4.5, skin_dark),         # Nacken
        *block(-5, -17, -2.5, 10, 2, 6, chest_cloth),            # Trapez
        *block(-7.5, -16, -4.5, 15, 6, 9, chest_cloth, bevel=1.5),   # Brustkorb oben (Schulterbreite)
        *block(-6.5, -10, -4, 13, 5, 8, chest_cloth, bevel=1.5),     # Rippen
        *block(-5, -5, -3.5, 10, 6, 7, pleats(83, hem=False), bevel=1.5),  # Taille
        # Schmaler Brustharnisch: oben breit, unten eingezogen (Keil), mit Brustmuskeln und Sonnenscheibe.
        *block(-7.5, -15.5, -5, 15, 6, 10, cuirass_paint(0), bevel=2),
        *block(-6, -9.5, -4.5, 12, 4, 9, cuirass_paint(1), bevel=2),
        Cb(-2.5, -10.5, -5.3, 5, 5, 1, sun),
    ], children=[head, arm_r, arm_l, cape1, back_wing('r', -1), back_wing('l', 1)])

    # =================================================================== Beine
    def strapped(face, x, y, w, h):
        """Wade mit Sandalenriemen: ab der Wadenmitte kreuzen sich Lederbänder."""
        base = ramp(SKIN, 0.58 + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.1 + (0.1 * (0.35 - ((x + 0.5) / w * 2 - 1) ** 2) if face in SIDES else 0))
        if face in SIDES and y >= h * 0.45:
            if (x + y) % 4 == 0 or (x - y) % 4 == 0:
                return ramp(LEATHER, 0.6 + FACE_LIGHT[face])
        return base

    def foot_paint(face, x, y, w, h):
        if face in ('top', 'front') and (x + y) % 3 == 0:
            return ramp(LEATHER, 0.62)
        return ramp(SKIN, 0.6 + FACE_LIGHT[face])

    def sole(face, x, y, w, h):
        if face in SIDES and y == 0:
            return ramp(AURUM, 0.7 + FACE_LIGHT[face])
        return ramp(LEATHER, 0.45 + FACE_LIGHT[face])

    def smoke(seed, density):
        def paint(face, x, y, w, h):
            if face == 'top':
                return ramp(SMOKE, 0.35)
            depth = (y + 0.5) / h
            n = speckle(x * 2 + seed, y * 2, seed % 9)
            if face == 'bottom' or n < density * (0.25 + 0.75 * depth) + 0.06 * ((x + y) % 2):
                return None
            return ramp(SMOKE, 0.5 + FACE_LIGHT[face] - 0.3 * depth + 0.1 * math.sin(x * 1.3 + y * 0.6 + seed))
        return shaded(paint, paint)

    def leg(s, sx):
        calf = Part(f'light_calf_{s}', cubes=[
            *block(-2, 0, -2, 4, 14, 4, shaded(strapped, strapped)),
            Cb(-2, 1.5, 0.5, 4, 6, 2, shaded(surf(SKIN, 0.56, 0.2, 90 + sx, roundx=0.4), lambda *a: None)),  # Wade
            Cb(-2, 12, -6, 4, 2, 6, shaded(foot_paint, foot_paint)),
            Cb(-2.5, 14, -6.5, 5, 2, 9, shaded(sole, sole)),
            *block(-2.5, 9, -2.5, 5, 2, 5, shaded(surf(AURUM, 0.66, 0.0, 92, roundx=0.4, ridge=0.2), lambda *a: None)),  # Knöchelring
        ])
        smoke_r = Part(f'shade_smoke_{s}', cubes=[
            *block(-2.5, -1, -2.5, 5, 6, 5, smoke(100 + sx, 0.2)),
            Cb(-2, 4, -1.5, 4, 5, 4, smoke(102 + sx, 0.45)),
            Cb(-1.5, 8, -0.5, 3, 4, 3, smoke(104 + sx, 0.7)),
            Cb(-1, 3, 2, 2, 8, 3, smoke(106 + sx, 0.6)),       # Fetzen, der nach hinten wegzieht
        ])
        wing = fan(f'wing_foot_{s}', (sx * 2.4, 10.5, 1.0), (-30, 0, sx * 12), sx, 4, 11, 40, plane='z', width=7)
        shin = Part(f'shin_{s}', (0, 15, 0), cubes=[], children=[calf, wing, smoke_r])
        return Part(f'leg_{s}', (sx * 3.3, 0, 0), cubes=[
            *block(-2.5, 0, -2.5, 5, 15, 5, skin, bevel=1),      # Oberschenkel 5
            Cb(-2, 12.5, -3.2, 4, 3, 2, skin),                    # Knie
        ], children=[shin])

    # =================================================================== Becken, Gürtel, Chiton-Rock
    belt = charred(surf(AURUM, 0.64, 0.2, 110, roundx=0.3, ridge=0.2), CHAR, 0.05)

    def skirt(name, pivot, rot, cube):
        return Part(name, pivot, rot=rot, cubes=[cube])

    def obol(face, x, y, w, h):
        cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
        if face in ('front', 'back') and (x - cx) ** 2 + (y - cy) ** 2 > 2.3:
            return None
        return ramp(OBOL, 0.55 + FACE_LIGHT[face] - 0.15 * (abs(x - cx) + abs(y - cy) > 1.4))
    coin = shaded(obol, obol)
    coins = Part('shade_coins', cubes=[
        Cb(cx, cy, -5.5, 3, 3, 1, coin) for cx, cy in [(-6, -4.2), (-3.5, -3.2), (0.5, -3.0), (3.4, -3.8)]
    ] + [Cb(5.6, -4, cz, 1, 3, 3, coin) for cz in (-2.5,)] + [Cb(-6.6, -3.5, cz, 1, 3, 3, coin) for cz in (0.5,)])

    root = Part('root', (0, -7, 0), cubes=[
        *block(-5.5, -6, -4, 11, 7, 8, pleats(111, hem=False), bevel=1.5),   # Hüfte
        *block(-6, -6.5, -4.5, 12, 2, 9, belt, bevel=1.5),                   # Gürtel
        Cb(-1.5, -7, -5.2, 3, 3, 1, fibula),                                 # Schnalle
    ], children=[
        torso,
        skirt('skirt_f', (0, -5, -3.6), (-7, 0, 0), Cb(-5.5, 0, -1, 11, 11, 2, pleats(112))),
        skirt('skirt_b', (0, -5, 3.6), (8, 0, 0), Cb(-5.5, 0, -1, 11, 12, 2, pleats(113))),
        skirt('skirt_r', (-5.0, -5, 0), (0, 0, 7), Cb(-1, 0, -3, 2, 10, 6, pleats(114))),
        skirt('skirt_l', (5.0, -5, 0), (0, 0, -7), Cb(-1, 0, -3, 2, 10, 6, pleats(115))),
        coins,
        leg('r', -1), leg('l', 1),
    ])
    return Model('hermes', 512, 512, [root])


# --- Schatten-Atlas ------------------------------------------------------------------------------------------
def shade_paint_of(paint, uv=(0, 0)):
    """Maler für den Schatten: eigener (.shade) oder die Grundumwandlung in Ruß. uv macht die Glutrisse je Quader verschieden."""
    shade = getattr(paint, 'shade', None)

    def wrapped(face, x, y, w, h):
        _UV[0] = uv
        if shade is not None:
            return shade(face, x, y, w, h)
        return to_ash(paint(face, x, y, w, h))
    return wrapped


def shade_textures(model):
    albedo = Image(model.tex_w, model.tex_h)
    glow = Image(model.tex_w, model.tex_h)
    for i, c in enumerate(model.cubes()):
        paint = shade_paint_of(c.paint, (i, 0))
        u, v, w, h, d = c.u, c.v, c.w, c.h, c.d
        faces = [
            ('top', u + d, v, w, d), ('bottom', u + d + w, v, w, d), ('right', u, v + d, d, h),
            ('front', u + d, v + d, w, h), ('left', u + d + w, v + d, d, h), ('back', u + d + w + d, v + d, w, h),
        ]
        for name, fu, fv, fw, fh in faces:
            for iy in range(fh):
                for ix in range(fw):
                    res = paint(name, ix, iy, fw, fh)
                    if res is None:
                        continue
                    if isinstance(res[0], tuple):
                        albedo.set(fu + ix, fv + iy, res[0])
                        if res[1] is not None:
                            glow.set(fu + ix, fv + iy, res[1])
                    else:
                        albedo.set(fu + ix, fv + iy, res)
    return albedo, glow


# --- Posen ------------------------------------------------------------------------------------------------
# Knochen, deren Winkel die Kotlin-Animation aus den Schlüsselposen liest (Reihenfolge = Datenlayout in Kotlin).
BONES = ['root', 'torso', 'head', 'arm_r', 'fore_r', 'caduceus', 'arm_l', 'fore_l', 'leg_r', 'shin_r', 'leg_l', 'shin_l',
         'cape1', 'cape2', 'cape3', 'wing_back_r', 'wing_fore_r', 'wing_hand_r', 'wing_back_l', 'wing_fore_l', 'wing_hand_l',
         'loot_orb']

KEYS = {}


def key(name, base=None, **bones):
    """Schlüsselpose: geht von base (Name oder None = Grundhaltung) aus und ersetzt die genannten Knochen."""
    pose = dict(KEYS[base]) if base else {b: (0.0, 0.0, 0.0) for b in BONES}
    for bone, value in bones.items():
        if bone == 'wings':
            pose.update(value)
        else:
            pose[bone] = value
    KEYS[name] = pose
    return pose


key('rest')

# Flügelhaltungen: offen, halb gefaltet (Ruhe) und eng gefaltet (Arch über den Schultern, Spitzen an der Wade).
WINGS_OPEN = wings(0.45, 0.08, 0.0)
WINGS_REST = wings(0.6, 0.35, 0.0) | {'wing_fore_r': (0.0, 0.3, -0.9), 'wing_hand_r': (0.0, 0.2, -0.5),
                                      'wing_fore_l': (0.0, -0.3, 0.9), 'wing_hand_l': (0.0, -0.2, 0.5)}
WINGS_FOLDED = wings(0.9, 0.5, 0.0) | {'wing_fore_r': (0.0, 0.5, -1.6), 'wing_hand_r': (0.0, 0.3, -0.4),
                                       'wing_fore_l': (0.0, -0.5, 1.6), 'wing_hand_l': (0.0, -0.3, 0.4)}


def _rot(p, y, r):
    cx, sx, cy, sy, cz, sz = math.cos(p), math.sin(p), math.cos(y), math.sin(y), math.cos(r), math.sin(r)
    return [
        [cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx],
        [sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx],
        [-sy, cy * sx, cy * cx],
    ]


def _mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _euler(m):
    pitch = math.atan2(m[2][1], m[2][2])
    yaw = math.asin(max(-1.0, min(1.0, -m[2][0])))
    roll = math.atan2(m[1][0], m[0][0])
    return (round(pitch, 3), round(yaw, 3), round(roll, 3))


def aim(pose, chain, direction, twist=(0.0, 0.0, 1.0)):
    """Absolute Drehung eines Knochens, damit seine -y-Achse in Weltrichtung direction zeigt.
    chain: Knochen von root bis zum Elternknochen. twist: wohin die lokale z-Achse ungefähr zeigen soll."""
    m = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    for bone in chain:
        m = _mul(m, _rot(*pose[bone][:3]))
    n = math.sqrt(sum(c * c for c in direction))
    col_y = [-c / n for c in direction]
    ref = list(twist)
    dot = sum(r * c for r, c in zip(ref, col_y))
    if abs(dot) > 0.95:
        ref = [1.0, 0.0, 0.0]
        dot = sum(r * c for r, c in zip(ref, col_y))
    col_z = [r - dot * c for r, c in zip(ref, col_y)]
    nz = math.sqrt(sum(c * c for c in col_z))
    col_z = [c / nz for c in col_z]
    col_x = [col_y[1] * col_z[2] - col_y[2] * col_z[1], col_y[2] * col_z[0] - col_y[0] * col_z[2],
             col_y[0] * col_z[1] - col_y[1] * col_z[0]]
    target = [[col_x[i], col_y[i], col_z[i]] for i in range(3)]
    mt = [[m[j][i] for j in range(3)] for i in range(3)]
    return _euler(_mul(mt, target))


STAFF_CHAIN = ['root', 'torso', 'arm_r', 'fore_r']
UP, DOWN, FWD, BACK = (0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1)


def staff(name, direction, twist=(0.0, 0.0, 1.0)):
    """Richtet den Caduceus einer Schlüsselpose aus: die Kugel zeigt in Weltrichtung direction."""
    KEYS[name]['caduceus'] = aim(KEYS[name], STAFF_CHAIN, direction, twist)


def wing_side(side, raise_, back, fold, fore=None, hand=None):
    """Nur ein Flügel (für asymmetrische Haltungen); fore/hand: eigene (yaw, roll) statt aus fold."""
    w = wings(raise_, back, fold, side_only=side)
    sign = 1.0 if side == 'r' else -1.0
    if fore is not None:
        w[f'wing_fore_{side}'] = (0.0, sign * fore[0], sign * fore[1])
    if hand is not None:
        w[f'wing_hand_{side}'] = (0.0, sign * hand[0], sign * hand[1])
    return w


def folded(raise_=0.9, back=0.5, fore=(0.5, -1.6), hand=(0.3, -0.4)):
    return wing_side('r', raise_, back, 0.0, fore, hand) | wing_side('l', raise_, back, 0.0, fore, hand)


def droop(raise_, back, fore_roll, hand_roll, fore_yaw=0.2, hand_yaw=0.1):
    return wing_side('r', raise_, back, 0.0, (fore_yaw, fore_roll), (hand_yaw, hand_roll)) | \
        wing_side('l', raise_, back, 0.0, (fore_yaw, fore_roll), (hand_yaw, hand_roll))


# ---------------------------------------------------------------------------------------------------- Ruhe
key('idle', 'rest',
    torso=(0.04, 0.08, 0.0), head=(0.05, -0.1, 0.04),
    arm_r=(-0.2, 0.0, 0.18), fore_r=(-0.7, 0.0, 0.0),
    arm_l=(0.1, 0.0, -0.22), fore_l=(-0.35, 0.0, 0.0),
    leg_r=(0.05, 0.0, 0.03), shin_r=(0.1, 0.0, 0.0), leg_l=(-0.25, 0.0, -0.05), shin_l=(0.5, 0.0, 0.0),
    cape1=(0.12, 0.0, 0.0), cape2=(0.08, 0.0, 0.0), cape3=(0.06, 0.0, 0.0),
    wings=WINGS_REST)
staff('idle', (0.08, -1, -0.12))
key('open', 'idle', wings=WINGS_OPEN)

# ---------------------------------------------------------------------------------------------------- Kurierstoß
key('dash_windup', 'idle',
    root=(0.62, 0.0, 0.0, 0.0, 7.0, 0.0), torso=(0.3, -0.1, 0.0), head=(-0.75, 0.0, 0.0),
    arm_r=(-2.35, 0.0, 0.12), fore_r=(-0.15, 0.0, 0.0),
    arm_l=(0.85, 0.0, -0.15), fore_l=(-1.3, 0.0, 0.0),
    leg_r=(-1.55, 0.0, 0.05), shin_r=(1.75, 0.0, 0.0), leg_l=(0.15, 0.0, -0.05), shin_l=(1.55, 0.0, 0.0),
    cape1=(0.35, 0.0, 0.0), cape2=(0.2, 0.0, 0.0), cape3=(0.15, 0.0, 0.0),
    wings=folded(0.55, 0.85, (0.45, -1.3), (0.35, -0.3)))
staff('dash_windup', (0.05, 0.05, -1))
key('dash', 'idle',
    root=(1.38, 0.0, 0.0), torso=(0.0, 0.0, 0.0), head=(-1.15, 0.0, 0.0),
    arm_r=(-2.95, 0.0, 0.1), fore_r=(-0.05, 0.0, 0.0),
    arm_l=(0.25, 0.0, -0.18), fore_l=(-0.1, 0.0, 0.0),
    leg_r=(0.02, 0.0, 0.04), shin_r=(0.12, 0.0, 0.0), leg_l=(0.18, 0.0, -0.04), shin_l=(0.45, 0.0, 0.0),
    cape1=(0.45, 0.0, 0.0), cape2=(0.12, 0.0, 0.0), cape3=(0.1, 0.0, 0.0),
    wings=folded(-0.75, 0.55, (0.25, -0.3), (0.15, -0.2)))
staff('dash', (0, 0.12, -1))
key('skid_brake', 'idle',
    root=(-0.55, 0.0, 0.0, 0.0, 3.0, 0.0), torso=(0.25, 0.0, 0.0), head=(0.15, 0.0, 0.0),
    arm_r=(0.35, 0.0, 0.85), fore_r=(-0.3, 0.0, 0.0),
    arm_l=(-1.2, 0.0, -0.75), fore_l=(-0.2, 0.0, 0.0),
    leg_r=(-1.05, 0.0, 0.08), shin_r=(0.25, 0.0, 0.0), leg_l=(-0.55, 0.0, -0.08), shin_l=(0.75, 0.0, 0.0),
    cape1=(-0.55, 0.0, 0.0), cape2=(-0.25, 0.0, 0.0), cape3=(-0.15, 0.0, 0.0),
    wings=wings(0.85, -0.45, 0.0))
staff('skid_brake', (-0.35, -0.5, 0.8))
key('skid_kneel', 'idle',
    root=(0.12, 0.0, 0.0, 0.0, 15.0, 0.0), torso=(0.42, 0.06, 0.0), head=(0.1, 0.0, 0.0),
    arm_r=(-0.35, 0.0, 0.85), fore_r=(-0.35, 0.0, 0.0),
    arm_l=(-0.75, 0.0, -0.1), fore_l=(-0.35, 0.0, 0.0),
    leg_r=(-1.69, 0.0, 0.08), shin_r=(1.66, 0.0, 0.0), leg_l=(0.22, 0.0, -0.06), shin_l=(1.5, 0.0, 0.0),
    cape1=(0.05, 0.0, 0.0), cape2=(0.05, 0.0, 0.0), cape3=(0.05, 0.0, 0.0),
    wings=droop(-0.15, 0.5, -0.45, -0.35, 0.25))
staff('skid_kneel', (-0.85, -1, 0.1))
key('stumble_fall', 'idle',
    root=(1.95, 0.0, 0.45, 0.0, 6.0, 0.0), torso=(0.2, 0.0, 0.0), head=(-0.3, 0.0, 0.3),
    arm_r=(-0.6, 0.0, 1.4), fore_r=(-0.4, 0.0, 0.0),
    arm_l=(-2.4, 0.0, -0.4), fore_l=(-0.1, 0.0, 0.0),
    leg_r=(-0.5, 0.0, 0.3), shin_r=(1.0, 0.0, 0.0), leg_l=(0.6, 0.0, -0.2), shin_l=(0.4, 0.0, 0.0),
    cape1=(-0.6, 0.0, 0.0), cape2=(-0.3, 0.0, 0.0), cape3=(-0.2, 0.0, 0.0),
    wings=wing_side('r', 1.1, 0.0, 0.0) | wing_side('l', -0.4, 0.9, 0.0, (0.6, 0.6), (0.5, 0.4)))
staff('stumble_fall', (-1, 0.3, 0.2))
key('stumble', 'idle',
    root=(0.95, 0.0, 0.18, 0.0, 16.0, 0.0), torso=(0.1, 0.25, 0.1), head=(-0.75, 0.2, 0.15),
    arm_r=(-0.35, 0.0, 1.25), fore_r=(-0.3, 0.0, 0.0),
    arm_l=(-1.15, 0.0, -0.25), fore_l=(-0.05, 0.0, 0.0),
    leg_r=(-0.25, 0.0, 0.35), shin_r=(1.6, 0.0, 0.0), leg_l=(0.35, 0.0, -0.15), shin_l=(0.55, 0.0, 0.0),
    cape1=(-0.2, 0.0, 0.0), cape2=(0.1, 0.0, 0.0), cape3=(0.1, 0.0, 0.0),
    wings=wing_side('r', 0.95, -0.15, 0.0, (0.1, -0.2), (0.0, 0.3)) | wing_side('l', -0.35, 0.8, 0.0, (0.7, 0.5), (0.6, 0.6)))
staff('stumble', (-1, 0.55, -0.1))

# ---------------------------------------------------------------------------------------------------- Federfächer
key('fan_windup', 'idle',
    root=(-0.12, 0.0, 0.0), torso=(-0.12, -0.55, 0.0), head=(0.05, 0.45, 0.0),
    arm_r=(-1.25, 0.0, -0.1), fore_r=(-0.25, 0.0, 0.0),
    arm_l=(-2.75, 0.0, -0.45), fore_l=(-1.35, 0.0, 0.0),
    leg_r=(-0.55, 0.0, 0.05), shin_r=(0.35, 0.0, 0.0), leg_l=(0.35, 0.0, -0.08), shin_l=(0.55, 0.0, 0.0),
    cape1=(0.3, 0.0, 0.0), cape2=(0.12, 0.0, 0.0), cape3=(0.1, 0.0, 0.0),
    wings=wing_side('r', 0.55, 0.1, 0.0, (0.0, 0.0), (0.0, 0.1)) | wing_side('l', 0.9, 0.75, 0.0, (0.2, 0.0), (0.3, 0.2)))
staff('fan_windup', (-0.2, -0.55, -1))
key('fan_throw', 'idle',
    root=(0.12, 0.0, 0.0), torso=(0.28, 0.55, 0.0), head=(0.1, -0.45, 0.0),
    arm_r=(-0.35, 0.0, 0.55), fore_r=(-0.4, 0.0, 0.0),
    arm_l=(-1.45, 0.0, 0.45), fore_l=(-0.1, 0.0, 0.0),
    leg_r=(0.3, 0.0, 0.05), shin_r=(0.6, 0.0, 0.0), leg_l=(-0.6, 0.0, -0.05), shin_l=(0.3, 0.0, 0.0),
    cape1=(0.1, 0.0, 0.0), cape2=(0.2, 0.0, 0.0), cape3=(0.25, 0.0, 0.0),
    wings=wing_side('r', 0.6, 0.35, 0.0, (0.0, -0.2), (0.0, -0.1)) | wing_side('l', 0.25, -0.75, 0.0, (-0.3, -0.1), (-0.3, -0.2)))
staff('fan_throw', (0.3, -1, 0.25))
key('fan_recover', 'idle',
    root=(0.1, 0.0, 0.0), torso=(0.2, 0.2, 0.0), head=(0.2, -0.1, 0.0),
    arm_r=(-0.1, 0.0, 0.35), fore_r=(-0.3, 0.0, 0.0),
    arm_l=(-0.3, 0.0, -0.1), fore_l=(-0.2, 0.0, 0.0),
    wings=droop(0.15, 0.3, -0.5, -0.4))
staff('fan_recover', (0.25, -1, 0.1))

# ---------------------------------------------------------------------------------------------------- Sturmschlag
key('ascend', 'idle',
    root=(-0.06, 0.0, 0.0), torso=(-0.05, 0.0, 0.0), head=(-0.65, 0.0, 0.0),
    arm_r=(0.05, 0.0, 0.1), fore_r=(-0.1, 0.0, 0.0),
    arm_l=(0.05, 0.0, -0.1), fore_l=(-0.1, 0.0, 0.0),
    leg_r=(0.04, 0.0, 0.02), shin_r=(0.12, 0.0, 0.0), leg_l=(0.08, 0.0, -0.02), shin_l=(0.18, 0.0, 0.0),
    cape1=(0.03, 0.0, 0.0), cape2=(0.03, 0.0, 0.0), cape3=(0.03, 0.0, 0.0),
    wings=wings(0.75, 0.05, 0.0))
staff('ascend', UP)
key('slam_windup', 'idle',
    root=(-0.32, 0.0, 0.0), torso=(-0.38, 0.0, 0.0), head=(-0.45, 0.0, 0.0),
    arm_r=(-2.85, 0.0, -0.32), fore_r=(-0.35, 0.0, 0.0),
    arm_l=(-2.85, 0.0, 0.32), fore_l=(-0.35, 0.0, 0.0),
    leg_r=(0.25, 0.0, 0.06), shin_r=(1.35, 0.0, 0.0), leg_l=(0.45, 0.0, -0.06), shin_l=(1.1, 0.0, 0.0),
    cape1=(0.6, 0.0, 0.0), cape2=(0.3, 0.0, 0.0), cape3=(0.2, 0.0, 0.0),
    wings=wings(1.05, 0.25, 0.0))
staff('slam_windup', (0, -1, 0.12))
key('stuck', 'idle',
    root=(0.05, 0.0, 0.0, 0.0, 15.0, 0.0), torso=(-0.2, 0.0, 0.0), head=(0.25, 0.0, 0.0),
    arm_r=(-0.62, 0.0, -0.2), fore_r=(-0.12, 0.0, 0.0),
    arm_l=(-0.68, 0.0, 0.26), fore_l=(-0.05, 0.0, 0.0),
    leg_r=(-1.69, 0.0, 0.1), shin_r=(1.69, 0.0, 0.0), leg_l=(0.25, 0.0, -0.06), shin_l=(1.5, 0.0, 0.0),
    cape1=(0.05, 0.0, 0.0), cape2=(0.05, 0.0, 0.0), cape3=(0.05, 0.0, 0.0),
    wings=droop(0.35, 0.3, -0.6, -0.4, 0.25))
staff('stuck', (0.05, -1, -0.05))

# ---------------------------------------------------------------------------------------------------- Zauber, Zwillinge
key('cast', 'idle',
    root=(-0.04, 0.0, 0.0), torso=(-0.04, 0.0, 0.0), head=(0.12, 0.0, 0.0),
    arm_r=(-0.75, 0.0, -0.32), fore_r=(-1.05, 0.0, 0.0),
    arm_l=(-1.35, 0.0, -0.75), fore_l=(-0.45, 0.0, 0.0),
    leg_r=(-0.05, 0.0, 0.03), shin_r=(0.2, 0.0, 0.0), leg_l=(0.05, 0.0, -0.03), shin_l=(0.3, 0.0, 0.0),
    wings=WINGS_OPEN)
staff('cast', UP)
key('plant_raise', 'idle',
    root=(0.1, 0.0, 0.0, 0.0, 4.0, 0.0), torso=(-0.15, 0.0, 0.0), head=(-0.25, 0.0, 0.0),
    arm_r=(-1.45, 0.0, -0.35), fore_r=(-1.05, 0.0, 0.0),
    arm_l=(-1.45, 0.0, 0.35), fore_l=(-1.05, 0.0, 0.0),
    leg_r=(-0.95, 0.0, 0.1), shin_r=(1.1, 0.0, 0.0), leg_l=(0.3, 0.0, -0.08), shin_l=(0.9, 0.0, 0.0),
    cape1=(0.3, 0.0, 0.0), cape2=(0.12, 0.0, 0.0), cape3=(0.1, 0.0, 0.0),
    wings=wings(0.85, 0.4, 0.0))
staff('plant_raise', (0, -1, -0.15))
key('plant_hold', 'idle',
    root=(0.25, 0.0, 0.0, 0.0, 12.0, 0.0), torso=(0.35, 0.0, 0.0), head=(0.55, 0.0, 0.0),
    arm_r=(-1.0, 0.0, -0.3), fore_r=(-0.55, 0.0, 0.0),
    arm_l=(-1.0, 0.0, 0.3), fore_l=(-0.55, 0.0, 0.0),
    leg_r=(-1.6, 0.0, 0.12), shin_r=(1.75, 0.0, 0.0), leg_l=(0.0, 0.0, -0.08), shin_l=(1.65, 0.0, 0.0),
    cape1=(0.0, 0.0, 0.0), cape2=(0.05, 0.0, 0.0), cape3=(0.05, 0.0, 0.0),
    wings=droop(0.55, 0.15, -0.35, -0.2, -0.1, -0.1))
staff('plant_hold', UP)

# ---------------------------------------------------------------------------------------------------- Diebesgriff, Spott, Wurf
key('snap_windup', 'idle',
    root=(0.3, 0.0, 0.0, 0.0, 4.0, 0.0), torso=(0.35, -0.45, 0.0), head=(-0.45, 0.4, 0.0),
    arm_r=(0.55, 0.0, 0.55), fore_r=(-0.5, 0.0, 0.0),
    arm_l=(0.75, 0.0, -0.35), fore_l=(-1.45, 0.0, 0.0),
    leg_r=(-1.15, 0.0, 0.06), shin_r=(1.25, 0.0, 0.0), leg_l=(0.45, 0.0, -0.06), shin_l=(1.0, 0.0, 0.0),
    cape1=(0.35, 0.0, 0.0), cape2=(0.15, 0.0, 0.0), cape3=(0.1, 0.0, 0.0),
    wings=folded(0.7, 0.7, (0.45, -1.4), (0.3, -0.35)))
staff('snap_windup', (0.3, -0.25, 1))
key('snap_grab', 'idle',
    root=(0.45, 0.0, 0.0, 0.0, 4.0, 0.0), torso=(0.35, 0.4, 0.0), head=(-0.75, -0.35, 0.0),
    arm_r=(0.65, 0.0, 0.5), fore_r=(-0.3, 0.0, 0.0),
    arm_l=(-2.15, 0.0, 0.1), fore_l=(-0.05, 0.0, 0.0),
    leg_r=(0.35, 0.0, 0.06), shin_r=(0.8, 0.0, 0.0), leg_l=(-1.1, 0.0, -0.06), shin_l=(0.9, 0.0, 0.0),
    cape1=(0.5, 0.0, 0.0), cape2=(0.2, 0.0, 0.0), cape3=(0.15, 0.0, 0.0),
    wings=wings(0.75, 0.35, 0.0))
staff('snap_grab', (0.25, -0.3, 1))
key('taunt', 'idle',
    root=(-0.3, 0.0, 0.08), torso=(-0.1, 0.15, 0.0), head=(-0.15, -0.2, 0.12),
    arm_r=(-0.5, 0.0, 0.55), fore_r=(-1.95, 0.0, 0.0),
    arm_l=(-0.85, 0.0, -0.35), fore_l=(-1.05, 0.0, 0.0),
    leg_r=(-0.55, 0.0, -0.12), shin_r=(0.9, 0.0, 0.0), leg_l=(-0.05, 0.0, -0.05), shin_l=(0.25, 0.0, 0.0),
    cape1=(0.2, 0.0, 0.0), cape2=(0.1, 0.0, 0.0), cape3=(0.08, 0.0, 0.0),
    wings=WINGS_REST)
staff('taunt', (-0.55, -0.6, 0.6))
key('throw', 'idle',
    torso=(-0.05, 0.4, 0.0), head=(0.0, -0.35, 0.0),
    arm_r=(-2.4, 0.0, 0.42), fore_r=(-1.25, 0.0, 0.0),
    arm_l=(-1.45, 0.0, -0.1), fore_l=(-0.1, 0.0, 0.0),
    leg_r=(0.3, 0.0, 0.05), shin_r=(0.55, 0.0, 0.0), leg_l=(-0.55, 0.0, -0.05), shin_l=(0.35, 0.0, 0.0),
    cape1=(0.3, 0.0, 0.0), cape2=(0.12, 0.0, 0.0), cape3=(0.1, 0.0, 0.0),
    wings=wings(0.7, 0.3, 0.0))
staff('throw', FWD)
key('throw_release', 'throw',
    torso=(0.2, -0.3, 0.0), head=(0.05, 0.25, 0.0),
    arm_r=(-1.55, 0.0, 0.05), fore_r=(-0.15, 0.0, 0.0),
    arm_l=(-0.6, 0.0, -0.3), fore_l=(-0.4, 0.0, 0.0))
staff('throw_release', (0, 0.12, -1))

# ---------------------------------------------------------------------------------------------------- Sturzflug, Erschöpfung
key('dive_windup', 'idle',
    root=(0.35, 0.0, 0.0), torso=(0.55, 0.0, 0.0), head=(0.5, 0.0, 0.0),
    arm_r=(-0.75, 0.0, -0.25), fore_r=(-1.5, 0.0, 0.0),
    arm_l=(-0.75, 0.0, 0.3), fore_l=(-1.6, 0.0, 0.0),
    leg_r=(-1.95, 0.0, 0.08), shin_r=(2.3, 0.0, 0.0), leg_l=(-1.85, 0.0, -0.08), shin_l=(2.35, 0.0, 0.0),
    cape1=(0.15, 0.0, 0.0), cape2=(0.1, 0.0, 0.0), cape3=(0.1, 0.0, 0.0),
    wings=droop(0.45, -0.75, -0.5, -0.4, -0.6, -0.5))
staff('dive_windup', (0, -1, -0.25))
key('dive', 'idle',
    root=(2.85, 0.0, 0.0), torso=(0.0, 0.0, 0.0), head=(-0.55, 0.0, 0.0),
    arm_r=(-2.95, 0.0, -0.15), fore_r=(-0.05, 0.0, 0.0),
    arm_l=(-2.95, 0.0, 0.15), fore_l=(-0.05, 0.0, 0.0),
    leg_r=(0.04, 0.0, 0.02), shin_r=(0.1, 0.0, 0.0), leg_l=(0.1, 0.0, -0.02), shin_l=(0.25, 0.0, 0.0),
    cape1=(0.25, 0.0, 0.0), cape2=(0.1, 0.0, 0.0), cape3=(0.1, 0.0, 0.0),
    wings=folded(-0.8, 0.45, (0.2, -0.25), (0.1, -0.2)))
staff('dive', (0, 1, -0.15))
key('kneel', 'idle',
    root=(0.18, 0.0, 0.0, 0.0, 15.0, 0.0), torso=(0.5, 0.0, 0.0), head=(0.6, 0.0, 0.0),
    arm_r=(-1.42, 0.0, -0.12), fore_r=(-0.6, 0.0, 0.0),
    arm_l=(-1.42, 0.0, 0.3), fore_l=(-0.7, 0.0, 0.0),
    leg_r=(-1.75, 0.0, 0.08), shin_r=(1.66, 0.0, 0.0), leg_l=(0.22, 0.0, -0.06), shin_l=(1.5, 0.0, 0.0),
    cape1=(0.0, 0.0, 0.0), cape2=(0.05, 0.0, 0.0), cape3=(0.05, 0.0, 0.0),
    wings=droop(-0.95, 0.3, -0.25, -0.15))
staff('kneel', (0.05, -1, -0.3))
key('stagger', 'idle',
    root=(-0.3, 0.0, 0.18), torso=(-0.15, -0.2, 0.08), head=(0.3, 0.25, 0.15),
    arm_r=(0.1, 0.0, 0.95), fore_r=(-0.3, 0.0, 0.0),
    arm_l=(-2.35, 0.0, -0.55), fore_l=(-1.85, 0.0, 0.0),
    leg_r=(-0.5, 0.0, 0.15), shin_r=(0.6, 0.0, 0.0), leg_l=(0.4, 0.0, -0.1), shin_l=(0.3, 0.0, 0.0),
    cape1=(0.5, 0.0, 0.0), cape2=(0.2, 0.0, 0.0), cape3=(0.1, 0.0, 0.0),
    wings=wing_side('r', 0.8, 0.1, 0.0, (0.0, -0.1), (0.0, 0.0)) | wing_side('l', -0.7, 0.5, 0.0, (0.3, 0.2), (0.2, 0.3)))
staff('stagger', (-0.6, 0.6, 0.2))

# ---------------------------------------------------------------------------------------------------- Schatten
key('smoke_emerge', 'idle',
    root=(0.25, 0.0, 0.0, 0.0, 5.0, 0.0), torso=(0.25, 0.65, 0.0), head=(-0.25, -0.55, 0.0),
    arm_r=(0.75, 0.0, 0.7), fore_r=(-0.45, 0.0, 0.0),
    arm_l=(-0.9, 0.0, -0.5), fore_l=(-0.7, 0.0, 0.0),
    leg_r=(0.35, 0.0, 0.08), shin_r=(1.1, 0.0, 0.0), leg_l=(-1.05, 0.0, -0.08), shin_l=(1.15, 0.0, 0.0),
    cape1=(0.15, 0.0, 0.0), cape2=(0.1, 0.0, 0.0), cape3=(0.1, 0.0, 0.0),
    wings=folded(0.65, 0.75, (0.45, -1.45), (0.3, -0.35)))
staff('smoke_emerge', (-0.6, -0.2, 1))
key('smoke_slash', 'smoke_emerge',
    root=(0.35, 0.0, 0.0, 0.0, 5.0, 0.0), torso=(0.3, -0.7, 0.0), head=(-0.3, 0.6, 0.0),
    arm_r=(-1.45, 0.0, -0.85), fore_r=(-0.1, 0.0, 0.0),
    arm_l=(0.5, 0.0, -0.6), fore_l=(-0.4, 0.0, 0.0),
    leg_r=(-1.0, 0.0, 0.08), shin_r=(1.0, 0.0, 0.0), leg_l=(0.4, 0.0, -0.08), shin_l=(0.9, 0.0, 0.0),
    cape1=(0.45, 0.0, 0.0), cape2=(0.3, 0.0, 0.0), cape3=(0.25, 0.0, 0.0),
    wings=wings(0.7, 0.35, 0.0))
staff('smoke_slash', (1, 0.15, -0.35))
key('coin_flip', 'idle',
    torso=(0.0, -0.15, 0.0), head=(-0.2, 0.25, 0.0),
    arm_l=(-1.05, 0.0, -0.05), fore_l=(-1.45, 0.0, 0.0))
staff('coin_flip', (0.08, -1, -0.12))
key('coin_look', 'coin_flip',
    root=(-0.12, 0.0, 0.0), head=(-0.85, 0.15, 0.0),
    arm_l=(-1.25, 0.0, -0.2), fore_l=(-1.2, 0.0, 0.0))
staff('coin_look', (0.08, -1, -0.12))
key('coin_catch', 'coin_flip',
    torso=(0.12, 0.1, 0.0), head=(0.3, -0.1, 0.0),
    arm_l=(-0.85, 0.0, 0.35), fore_l=(-1.15, 0.0, 0.0))
staff('coin_catch', (0.08, -1, -0.12))

# ---------------------------------------------------------------------------------------------------- Cutscenes
key('read', 'idle',
    head=(0.42, -0.1, 0.0), arm_l=(-0.75, 0.0, -0.05), fore_l=(-1.05, 0.0, 0.0),
    wings=folded())
staff('read', (0.08, -1, -0.12))
key('letter', 'idle',
    torso=(0.0, -0.12, 0.0), head=(0.05, 0.18, 0.0), arm_l=(-1.75, 0.0, -0.15), fore_l=(-0.85, 0.0, 0.0),
    wings=folded())
staff('letter', (0.08, -1, -0.12))
key('fall', 'idle',
    root=(-1.35, 0.0, 0.0), head=(0.4, 0.0, 0.0),
    arm_r=(-0.35, 0.0, 1.2), fore_r=(-0.6, 0.0, 0.0),
    arm_l=(-0.35, 0.0, -1.25), fore_l=(-0.3, 0.0, 0.0),
    leg_r=(-0.3, 0.0, 0.1), shin_r=(0.4, 0.0, 0.0), leg_l=(0.1, 0.0, -0.1), shin_l=(0.2, 0.0, 0.0),
    cape1=(-0.2, 0.0, 0.0), cape2=(0.0, 0.0, 0.0), cape3=(0.0, 0.0, 0.0),
    wings=wings(0.15, -0.15, 0.0))
staff('fall', (-0.9, 0.2, -0.3))


def visible(pose, variant='light', props=()):
    """Vorschau-Pose: blendet die Teile der anderen Variante und nicht benutzte Requisiten aus."""
    out = dict(pose)
    for name in (SHADE_ONLY if variant == 'light' else LIGHT_ONLY):
        out[name] = None
    for name in PROPS:
        if name not in props:
            out[name] = None
    return out


POSE_PROPS = {'taunt': ('loot_orb',), 'snap_grab': ('loot_orb',), 'read': ('scroll',), 'letter': ('letter',)}
POSES = {name: visible(p, 'light', POSE_PROPS.get(name, ())) for name, p in KEYS.items() if name != 'rest'}


# --- Abgleich mit Kotlin ------------------------------------------------------------------------------------
KOTLIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'kotlin', 'dev', 'henny',
                      'hha', 'client', 'model', 'HermesModel.kt')
BEGIN, END = '// <hermes-keys>', '// </hermes-keys>'


def _paths(model):
    """Pfad (Knochennamen ab dem Modell-Wurzelteil) für jeden Teil des Modells."""
    out = {}

    def visit(part, path):
        here = path + [part.name]
        out[part.name] = here
        for child in part.children:
            visit(child, here)
    for part in model.parts:
        visit(part, [])
    return out


def kotlin_keys():
    """Kotlin-Objekt mit allen Schlüsselposen (je Knochen pitch, yaw, roll, dx, dy, dz) und den Varianten-Pfaden."""
    paths = _paths(hermes())

    def arr(names):
        return 'arrayOf(' + ', '.join('arrayOf(' + ', '.join(f'"{n}"' for n in paths[name]) + ')' for name in names) + ')'

    def num(v):
        return f'{v:.4f}f'

    lines = [BEGIN, '// Generiert von tools/hha_assets/hermes_model.py --sync-kotlin aus KEYS (= POSES); nicht von Hand ändern.',
             'internal object HermesKeys {',
             f'    /** Knochen in Datenreihenfolge: {", ".join(BONES)}. */',
             f'    val BONES: Array<Array<String>> = {arr(BONES)}',
             f'    /** Nur Hermes (aus bei corrupted). */',
             f'    val LIGHT: Array<Array<String>> = {arr(LIGHT_ONLY)}',
             f'    /** Nur der Schatten. */',
             f'    val SHADE: Array<Array<String>> = {arr(SHADE_ONLY)}',
             '']
    for name, pose in KEYS.items():
        if name == 'rest':
            continue
        values = []
        for bone in BONES:
            v = tuple(pose[bone]) + (0.0, 0.0, 0.0)
            values.extend(v[:6])
        lines.append(f'    val {name.upper()} = floatArrayOf({", ".join(num(v) for v in values)})')
    lines.append('}')
    lines.append(END)
    return '\n'.join(lines)


def sync_kotlin():
    with open(KOTLIN, encoding='utf8') as f:
        src = f.read()
    block = kotlin_keys()
    if BEGIN in src:
        src = re.sub(re.escape(BEGIN) + '.*?' + re.escape(END), lambda m: block, src, flags=re.S)
    else:
        src = src.rstrip('\n') + '\n\n' + block + '\n'
    with open(KOTLIN, 'w', encoding='utf8', newline='\n') as f:
        f.write(src)
    print('Schlüsselposen geschrieben:', os.path.abspath(KOTLIN))


if __name__ == '__main__' and '--sync-kotlin' in sys.argv:
    sync_kotlin()

# Für die Größenprüfung in preview.py: Diese Knochen (samt Kindern) zählen nicht zur Körperhöhe (Fuß bis Scheitel),
# weil sie Zubehör sind, das laut Brief darüber hinausragen darf (Hut, Waffe, Busch, Ast, Stachel, Ring).
HEIGHT_EXCLUDE = ('hat', 'wing_head_r', 'wing_head_l', 'caduceus', 'wing_back_r', 'wing_back_l')
