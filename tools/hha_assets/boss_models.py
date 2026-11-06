"""Hermes und seine Begleiter: Modelle und Texturen. Aufruf: python boss_models.py"""
import math

from modelkit import (
    BONE, CAPE, CLOTH, FEATHER, GOLD, HAIR, LEATHER, MARBLE, PARCEL, SCALE_A, SCALE_B, SILVER, SKIN, SOUL, STONE,
    Cube, Model, Part, Plume, glowing, hexc, mat, mix, ramp, rgb, speckle, trimmed, write_kotlin,
)
from feather_art import CELL_COVERT, CELL_LONG, CELL_TINT
from hermes_model import hermes
from texkit import Image, flush, save

EYE_GOLD = hexc('#ffd75a')


def feather_plane(tip_glow=True, mirror=False, material=FEATHER, tip=GOLD):
    """Flügel als Ebene: einzelne Federn fächern von der Wurzel (links) nach außen, die Unterkante ist gezackt."""
    def paint(face, x, y, w, h):
        if face not in ('front', 'back', 'left', 'right'):
            return None
        u = (w - 1 - x) if mirror else x
        f = u / max(w - 1, 1)
        # Federkante: Länge je Spalte, oben kurz am Knochen, nach außen schmaler.
        reach = h * (1.0 - 0.55 * f ** 1.6)
        comb = 1.2 if u % 2 == 0 else 0.0
        if y > reach - comb:
            return None
        # Knochen oben, darunter Federn mit dunkler Trennlinie.
        if y == 0:
            return (ramp(tip, 0.72), (255, 240, 170, 200) if tip_glow else None)
        sep = u % 3 == 2
        v = 0.86 - 0.3 * (y / max(reach, 1)) - (0.22 if sep else 0.0)
        c = ramp(material, max(0.0, v))
        if y > reach - 2.5 or f > 0.82:
            c = mix(c, ramp(tip, 0.65), 0.65)
            if tip_glow:
                return (c, (255, 226, 130, 170))
        return c
    return paint


def dragon_scales(material, seed):
    """Versetzte Schuppenreihen mit dunkler Naht und hellem Glanz; der Bauch trägt Knochenplatten."""
    base = mat(material, 0.56, 0.06, 0.0, seed)

    def paint(face, x, y, w, h):
        if face == 'bottom':
            return ramp(BONE, 0.78 - (0.26 if y % 3 == 0 else 0.0))
        c = base(face, x, y, w, h)
        cell = (x + (y // 2)) % 2
        if y % 2 == 1 and cell == 0:
            return mix(c, hexc('#000000'), 0.32)
        if y % 2 == 0 and cell == 1:
            return mix(c, hexc('#ffffff'), 0.12)
        return c
    return paint


def dragon_head(name, jaw_name, material, seed):
    """Drachenkopf: lange Schnauze, Hörner, Zahnreihen, Bartfäden und eine Federmähne im Nacken."""
    scales = dragon_scales(material, seed)
    bright = dragon_scales(material, seed + 1)
    fang = mat(BONE, 0.92, 0.02, 0.0)
    horn = mat(GOLD, 0.56, 0.04, 0.34, seed + 2)
    mouth = mat(rgb('#2a070e', '#5c1420', '#942a34'), 0.5, 0.1, 0.0)
    tongue = mat(rgb('#7a1730', '#b82c4c', '#ee5c74'), 0.6, 0.05, 0.0)

    def eye(face, x, y, w, h):
        return (hexc('#fff0a0'), (255, 240, 150, 255))

    def nostril(face, x, y, w, h):
        return (hexc('#ffe27a'), (255, 226, 120, 230))

    cubes = [
        Cube(-7, -7, -6, 14, 10, 14, scales),            # Schädel
        Cube(-8, -2, -4, 16, 5, 8, bright),              # Wangen
        Cube(-5, -5, -20, 10, 7, 14, scales),            # Schnauze
        Cube(-6, -6, -24, 12, 5, 5, bright),             # Nasenrücken
        Cube(-4, -7, -22, 2, 1, 2, nostril),
        Cube(2, -7, -22, 2, 1, 2, nostril),
        Cube(-8, -9, -8, 5, 3, 7, bright),               # Brauenwülste
        Cube(3, -9, -8, 5, 3, 7, bright),
        Cube(-8, -6, -7, 1, 2, 3, eye, dilate=0.15),     # Augen
        Cube(7, -6, -7, 1, 2, 3, eye, dilate=0.15),
        Cube(-8, -7, 7, 16, 13, 4, trimmed(scales, mat(GOLD, 0.74, 0.04, 0.0), rows=2, top=True)),  # Nackenring
    ]
    # Obere Zahnreihe mit zwei großen Fängen vorn.
    for i, z in enumerate((-23, -19, -15, -11)):
        size = 4 if i == 0 else 2
        cubes.append(Cube(-5, 2, z, 1, size, 1, fang))
        cubes.append(Cube(4, 2, z, 1, size, 1, fang))

    jaw_cubes = [
        Cube(-5, 0, -22, 10, 3, 24, scales),
        Cube(-4, -0.5, -21, 8, 1, 22, mouth),
        Cube(-1, -1.5, -19, 2, 1, 14, tongue),
    ]
    for i, z in enumerate((-21, -17, -13, -9)):
        size = 3 if i == 0 else 2
        jaw_cubes.append(Cube(-5, -size, z, 1, size, 1, fang))
        jaw_cubes.append(Cube(4, -size, z, 1, size, 1, fang))

    def curved_horn(side, suffix):
        tip = Part(f'horn_tip_{suffix}', (0, 0, 8), rot=(-16, 0, 0), cubes=[Cube(-0.5, -0.5, 0, 1, 1, 7, glowing(mat(GOLD, 0.92, 0.02, 0.0), 210))])
        mid = Part(f'horn_mid_{suffix}', (0, 0, 9), rot=(-14, side * -6, 0), cubes=[Cube(-1, -1, 0, 2, 2, 8, horn)], children=[tip])
        return Part(f'horn_{suffix}', (side * 5, -7, 2), rot=(34, side * 18, 0), cubes=[Cube(-2, -2, 0, 4, 4, 9, horn)], children=[mid])

    # Mähne: Federn fächern vom Nacken nach hinten; die mittleren stehen höher.
    mane = []
    for i in range(11):
        t = i / 5.0 - 1.0
        mane.append(Plume(
            (t * 6.0, -6.0 + abs(t) * 5.0, 6.0), (24.0 - abs(t) * 30.0, t * 58.0, 0),
            13.0, 30.0 - abs(t) * 10.0, CELL_TINT, 'z', flip=t < 0, bend=0.1 * (1 if t >= 0 else -1), shade=(0.7, 1.0),
        ))
    # Bartfäden am Kiefer.
    for side in (-1, 1):
        for i in range(3):
            mane.append(Plume(
                (side * 7.0, 1.0, -2.0 + i * 3.0), (-30.0 + i * 12.0, side * 30.0, 0),
                8.0, 16.0 - i * 2.0, CELL_TINT, 'z', flip=side < 0, bend=0.06 * side, shade=(0.72, 1.0),
            ))

    return Part(name, (0, 12, 0), cubes=cubes, plumes=mane, children=[
        Part(jaw_name, (0, 3, 2), cubes=jaw_cubes),
        curved_horn(-1, f'r_{name}'),
        curved_horn(1, f'l_{name}'),
    ])


def serpent():
    """Drachenkopf, ein Rumpfglied und ein Schwanzstück; der Renderer reiht die Glieder entlang des Körperpfads."""
    def body(name, material, seed):
        scales = dragon_scales(material, seed)
        side = dragon_scales(material, seed + 1)

        def flank(face, x, y, w, h):
            # Leuchtpunkte an den Flanken, wie bei Tiefseewesen.
            if face in ('left', 'right') and x == w // 2 and y == h // 2:
                return (hexc('#fff6c8'), (255, 240, 170, 240))
            return side(face, x, y, w, h)

        def belly(face, x, y, w, h):
            return ramp(BONE, 0.8 - (0.28 if y % 4 == 0 else 0.0))

        def fin(face, x, y, w, h):
            if face not in ('left', 'right'):
                return None
            # Dreieckige Rückenflosse, die Spitze zeigt zum Schwanz.
            f = x / max(w - 1, 1)
            reach = h * (1.0 - abs(f - 0.62) / 0.62)
            if h - 1 - y > reach:
                return None
            c = ramp(material, 0.72)
            if h - 1 - y > reach - 2:
                return (ramp(GOLD, 0.85), (255, 226, 130, 220))
            return c

        return Part(name, (0, 0, 0), cubes=[
            Cube(-4, -7, -5, 8, 14, 10, scales),
            Cube(-7, -4, -5, 14, 8, 10, flank),
            Cube(-4, 7, -5, 8, 1, 10, belly),
            Cube(0, -13, -4, 0, 6, 9, fin),
        ])

    def tail(name, material, seed):
        scales = dragon_scales(material, seed)
        return Part(name, (0, 0, 0), cubes=[
            Cube(-4, -4, -5, 8, 8, 10, scales),
            Cube(-2, -2, 5, 4, 4, 6, scales),
            Cube(-1, -1, 11, 2, 2, 5, glowing(mat(GOLD, 0.9, 0.02, 0.0), 220)),
        ])

    return Model('serpent', 256, 256, [
        dragon_head('head', 'jaw', SCALE_A, 31),
        dragon_head('head_b', 'jaw_b', SCALE_B, 36),
        body('segment_a', SCALE_A, 34),
        body('segment_b', SCALE_B, 38),
        tail('tail_a', SCALE_A, 42),
        tail('tail_b', SCALE_B, 44),
    ])


def herma():
    """Herma: Grenzstein mit Hermes-Büste, wie sie an griechischen Wegkreuzungen stand."""
    stone = mat(MARBLE, 0.55, 0.14, 0.2, 40)

    def shaft(face, x, y, w, h):
        c = stone(face, x, y, w, h)
        if face in ('front', 'back') and 8 < y < h - 3:
            # Leuchtende Rune in der Mitte.
            u = abs(x - (w - 1) / 2)
            yy = y - 8
            if (yy % 6 == 0 and u < 3) or (u < 0.6) or (yy % 6 == 3 and 1 < u < 3):
                return (hexc('#ffe27a'), (255, 226, 120, 255))
        if (y + x * 3) % 17 == 0:
            return mix(c, hexc('#3c3a42'), 0.5)
        return c

    def head_face(face, x, y, w, h):
        c = stone(face, x, y, w, h)
        if face == 'front':
            if y == 4 and x in (1, 2, 5, 6):
                return (hexc('#ffe27a'), (255, 226, 120, 255))
            if y == 6 and 2 <= x <= 5:
                return mix(c, hexc('#3c3a42'), 0.6)
        return c

    root = Part('root', (0, 24, 0), cubes=[
        Cube(-7, -3, -7, 14, 3, 14, trimmed(stone, mat(GOLD, 0.6, 0.06, 0.0))),
        Cube(-5, -31, -5, 10, 28, 10, shaft),
        Cube(-6, -33, -6, 12, 2, 12, stone),
        Cube(-7, -35, -4, 14, 2, 8, mat(GOLD, 0.58, 0.06, 0.1)),
    ], children=[
        Part('bust', (0, -35, 0), cubes=[
            Cube(-4, -8, -4, 8, 8, 8, head_face),
            Cube(-5, -9, -5, 10, 2, 10, mat(GOLD, 0.62, 0.05, 0.0)),
        ], children=[
            Part('stone_wing_r', (-5, -7, 0), rot=(0, -10, 10), cubes=[Cube(-11, -6, 0, 11, 8, 0, feather_plane(mirror=True, material=MARBLE))]),
            Part('stone_wing_l', (5, -7, 0), rot=(0, 10, -10), cubes=[Cube(0, -6, 0, 11, 8, 0, feather_plane(material=MARBLE))]),
        ]),
    ])
    return Model('herma', 128, 128, [root])


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
    """Federpfeil und Seelenlanze; zeigen im Modell entlang -z."""
    tip = mat(SOUL, 0.72, 0.1, 0.0, 61)
    lance = Part('lance', (0, 0, 0), cubes=[
        Cube(-0.5, -0.5, -10, 1, 1, 26, glowing(mat(SOUL, 0.7, 0.1, 0.0, 60))),
        Cube(-2, -2, -16, 4, 4, 6, glowing(tip)),
        Cube(-1, -1, -20, 2, 2, 4, glowing(tip)),
        Cube(-3, -0.5, -11, 6, 1, 2, mat(BONE, 0.7, 0.1, 0.0)),
        Cube(-0.5, -3, 14, 1, 6, 2, mat(BONE, 0.7, 0.1, 0.0)),
    ])
    return Model('projectiles', 128, 64, [lance])


def shade_textures(albedo, glow):
    """Der Schatten des Boten: dasselbe Modell, aber wie aus Asche. Gold wird zu Ruß, jedes Leuchten wird grün."""
    def dark(x, y):
        r, g, b, a = albedo.get(x, y)
        if a == 0:
            return (0, 0, 0, 0)
        luma = (r * 0.3 + g * 0.59 + b * 0.11) / 255.0
        warm = max(0, r - b) / 255.0
        value = int(18 + luma * 62)
        return (value, int(value * 1.12 + warm * 22), int(value * 1.02), a)

    def poison(x, y):
        r, g, b, a = glow.get(x, y)
        if a == 0:
            return (0, 0, 0, 0)
        luma = (r * 0.3 + g * 0.59 + b * 0.11) / 255.0
        return (int(40 + 120 * luma ** 2), int(170 + 85 * luma), int(60 + 70 * luma ** 2), a)

    save(Image(albedo.w, albedo.h).fn(dark), 'textures/entity/boss/hermes_shade.png')
    save(Image(glow.w, glow.h).fn(poison), 'textures/entity/boss/hermes_shade_glow.png')


def main():
    models = [hermes(), serpent(), herma(), parcel(), projectiles()]
    for m in models:
        m.pack()
        images = m.paint()
        if m.name == 'hermes':
            shade_textures(*images)
    write_kotlin(models)
    flush()


if __name__ == '__main__':
    main()
