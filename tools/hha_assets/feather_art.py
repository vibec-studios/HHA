"""Glatte Federn als gemeinsame Grundlage für Effekte, Partikel, Hermes' Flügel und die Schlangen.

Eine Feder ist hier eine Funktion statt einer Pixelmaske: Kiel, asymmetrische Fahne, schräge Äste, Risse in der
Kante und ein flaumiger Ansatz. Jede Textur rastert sie mit Supersampling in ihrer eigenen Auflösung.
u läuft quer zur Feder (0..1), v vom Ansatz (0) zur Spitze (1).
"""
import math

from texkit import Image, hexc, mix, ramp, save

GOLD_TIP = [hexc('#c98a1c'), hexc('#efbb36'), hexc('#ffe27a'), hexc('#fff6c8')]
IVORY = [hexc('#8f8676'), hexc('#c4bcaa'), hexc('#ece6d8'), hexc('#fbf8f0'), hexc('#ffffff')]


def smooth(a, b, x):
    t = max(0.0, min(1.0, (x - a) / (b - a)))
    return t * t * (3.0 - 2.0 * t)


class Shape:
    """Bauplan einer Feder. wl/wr: halbe Breite der Innen- und Außenfahne, curve: Krümmung des Kiels."""

    def __init__(self, wl=0.2, wr=0.32, curve=0.035, tip=0.66, slope=0.55, barbs=46.0, splits=(), seed=1.0, down=0.2):
        self.wl, self.wr, self.curve, self.tip, self.slope = wl, wr, curve, tip, slope
        self.barbs, self.splits, self.seed, self.down = barbs, splits, seed, down


LONG = Shape(0.15, 0.27, 0.03, 0.62, 0.42, 58.0, ((0.52, 0.014, 1), (0.71, 0.012, -1), (0.36, 0.01, -1)), 1.0)
COVERT = Shape(0.24, 0.34, 0.025, 0.5, 0.5, 34.0, ((0.58, 0.016, 1),), 2.0, 0.26)
PLUME = Shape(0.12, 0.2, 0.04, 0.55, 0.36, 42.0, ((0.46, 0.012, -1),), 3.0, 0.14)
LOOSE = Shape(0.19, 0.3, 0.05, 0.6, 0.5, 40.0, ((0.42, 0.018, 1), (0.63, 0.016, -1), (0.28, 0.014, 1)), 4.0)


def evaluate(shape, u, v):
    """Liefert (Deckung, Helligkeit, Kiel-Anteil, Randnähe) an einem Punkt der Feder."""
    if v < 0.0 or v > 1.0:
        return 0.0, 0.0, 0.0, 0.0
    center = 0.5 + shape.curve * math.sin(v * math.pi) - shape.curve * 0.5
    d = u - center
    # Der Kiel läuft bis fast in die Spitze und steht unten frei.
    quill = 0.016 * (1.25 - v)
    if abs(d) < quill and v < 0.96:
        return 1.0, 1.0, 1.0, 0.0
    profile = smooth(shape.down * 0.15, shape.down, v) ** 0.55 * (1.0 - smooth(shape.tip, 1.0, v) ** 2.2)
    width = (shape.wr if d > 0 else shape.wl) * profile
    if width <= 0.002:
        return 0.0, 0.0, 0.0, 0.0
    barb = v - abs(d) * shape.slope
    # Die Äste enden nie ganz gleich lang.
    fray = 0.5 + 0.5 * math.sin(barb * 91.0 + shape.seed) * math.sin(barb * 37.0 + shape.seed * 3.0)
    width *= 1.0 - 0.09 * fray
    edge = abs(d) / width
    if edge >= 1.0:
        return 0.0, 0.0, 0.0, 0.0
    side = 1 if d > 0 else -1
    for start, gap, which in shape.splits:
        if which == side and abs(barb - start) < gap * edge ** 1.6 and edge > 0.3:
            return 0.0, 0.0, 0.0, 0.0
    cover = 1.0
    if v < shape.down:
        wisp = 0.62 + 0.38 * math.sin(barb * 310.0 + shape.seed)
        cover = (0.3 + 0.7 * smooth(shape.down * 0.2, shape.down, v)) * wisp
    # Plastik: die Fahne wölbt sich vom Kiel weg, die Äste zeichnen feine Linien.
    lines = 0.5 + 0.5 * math.sin(barb * shape.barbs * math.tau)
    shade = 0.84 + 0.08 * lines + 0.1 * (1.0 - edge) - 0.14 * edge ** 3 + 0.06 * v
    if abs(d) < quill * 2.6:
        shade -= 0.13
    return cover, max(0.0, min(1.0, shade)), 0.0, edge


def rasterize(shape, width, height, shader, samples=3, angle=0.0, margin=0.0):
    """Liefert je Pixel das Ergebnis von shader(Schattierung, Kiel, Randnähe, v) und die gemittelte Deckung."""
    img = Image(width, height)
    cos_a, sin_a = math.cos(angle), math.sin(angle)
    for y in range(height):
        for x in range(width):
            cover = shade = quill = edge = along = 0.0
            for sy in range(samples):
                for sx in range(samples):
                    u = (x + (sx + 0.5) / samples) / width
                    v = 1.0 - (y + (sy + 0.5) / samples) / height
                    if angle:
                        cu, cv = u - 0.5, v - 0.5
                        u, v = cu * cos_a - cv * sin_a + 0.5, cu * sin_a + cv * cos_a + 0.5
                    if margin:
                        v = (v - margin) / (1.0 - 2.0 * margin)
                    a, s, q, e = evaluate(shape, u, v)
                    if a > 0:
                        cover += a
                        shade += s * a
                        quill += q * a
                        edge += e * a
                        along += v * a
            n = samples * samples
            if cover <= 0:
                img.set(x, y, shader(None))
                continue
            img.set(x, y, shader((shade / cover, quill / cover, edge / cover, along / cover), cover / n))
    return img


def vfx_sprite():
    """Weiße Feder für die eingefärbten Effekte. Auch transparente Pixel bleiben weiß, sonst säumt der lineare Filter sie dunkel."""
    def shader(data, cover=0.0):
        if data is None:
            return (255, 255, 255, 1)
        s = int(data[0] * 255)
        return (s, s, s, max(1, int(cover * 255)))

    save(rasterize(LOOSE, 96, 192, shader, 4), 'textures/vfx/feather.png')


def particles():
    """Vier leicht verschiedene Partikel-Federn; mehr Auflösung, keine eckige Blattform mehr."""
    for i, (shape, angle) in enumerate(((LOOSE, 0.5), (LONG, -0.42), (COVERT, 0.85), (LOOSE, -0.2))):
        def shader(data, cover=0.0):
            if data is None:
                return (0, 0, 0, 0)
            shade, quill, edge, v = data
            c = ramp(IVORY, shade * 0.9 + 0.08)
            tip = smooth(0.78, 1.0, v) * 0.6
            if quill > 0.5:
                c = ramp(GOLD_TIP, 0.7)
            elif tip > 0:
                c = mix(c, ramp(GOLD_TIP, 0.7), tip)
            alpha = int(cover * 255)
            return (c[0], c[1], c[2], alpha if alpha > 20 else 0)

        save(rasterize(shape, 32, 32, shader, 4, angle, 0.04), f'textures/particle/feather_{i}.png')


# Zellen des Federn-Atlas für Modelle: je 64 x 256 Texel.
CELL_LONG, CELL_COVERT, CELL_PLUME, CELL_TINT = 0, 1, 2, 3
ATLAS_W, ATLAS_H, CELL_W = 256, 256, 64
_ATLAS_SHAPES = {CELL_LONG: LONG, CELL_COVERT: COVERT, CELL_PLUME: PLUME, CELL_TINT: LOOSE}


def _albedo(cell, shade, quill, edge, v):
    """Grundfarbe im Atlas: elfenbeinweiß, goldene Spitzen und Kiele; die Tönungs-Zelle bleibt weiß."""
    if cell == CELL_TINT:
        s = int(shade * 255)
        return (s, s, s, 255)
    c = ramp(IVORY, shade * 0.86 + 0.1)
    start = {CELL_LONG: 0.74, CELL_COVERT: 0.6, CELL_PLUME: 0.5}[cell]
    tip = smooth(start, 1.0, v) * 0.85 + smooth(0.8, 1.0, edge) * 0.3
    if quill > 0.5:
        return ramp(GOLD_TIP, 0.55 + 0.4 * v)
    return mix(c, ramp(GOLD_TIP, 0.45 + shade * 0.5), min(1.0, tip))


def _glow(cell, quill, edge, v):
    """Stärke der Leuchtmaske: Kiel, Saum und Spitze glimmen, die Fläche nur ganz leicht."""
    strength = 0.1 + 0.8 * quill + 0.5 * smooth(0.7, 1.0, edge) + 0.75 * smooth(0.68, 1.0, v)
    if cell == CELL_TINT:
        strength *= 0.8
    return min(1.0, strength)


def texel(cell, u, v):
    """Farbe an einem Punkt einer Zelle, für die Vorschau ohne Spiel. None = durchsichtig."""
    a, shade, quill, edge = evaluate(_ATLAS_SHAPES[cell], u, v)
    if a < 0.5:
        return None
    return _albedo(cell, shade, quill, edge, v)


def atlas():
    """Der Atlas für Flügel-Geometrie plus Leuchtmaske."""
    albedo = Image(ATLAS_W, ATLAS_H)
    glow = Image(ATLAS_W, ATLAS_H)
    samples = 3
    n = samples * samples
    for cell, shape in _ATLAS_SHAPES.items():
        for y in range(ATLAS_H):
            for x in range(CELL_W):
                cover = shade = quill = edge = along = 0.0
                for sy in range(samples):
                    for sx in range(samples):
                        u = (x + (sx + 0.5) / samples) / CELL_W
                        v = 1.0 - (y + (sy + 0.5) / samples) / ATLAS_H
                        a, s, q, e = evaluate(shape, u, v)
                        if a > 0:
                            cover += a
                            shade += s * a
                            quill += q * a
                            edge += e * a
                            along += v * a
                px = cell * CELL_W + x
                if cover <= 0:
                    # Weiß statt Schwarz, damit der lineare Filter keinen dunklen Saum zieht.
                    albedo.set(px, y, (235, 228, 212, 1))
                    glow.set(px, y, (255, 232, 170, 1))
                    continue
                shade, quill, edge, along = shade / cover, quill / cover, edge / cover, along / cover
                c = _albedo(cell, shade, quill, edge, along)
                albedo.set(px, y, (c[0], c[1], c[2], max(1, int(cover / n * 255))))
                glow.set(px, y, (255, 232, 170, max(1, int(_glow(cell, quill, edge, along) * cover / n * 255))))
    save(albedo, 'textures/entity/boss/feathers.png')
    save(glow, 'textures/entity/boss/feathers_glow.png')


def uv_rect(cell, flip=False):
    """UV-Grenzen einer Zelle im Atlas (u0, u1) mit etwas Abstand zur Nachbarzelle."""
    u0 = (cell * CELL_W + 1.0) / ATLAS_W
    u1 = ((cell + 1) * CELL_W - 1.0) / ATLAS_W
    return (u1, u0) if flip else (u0, u1)
