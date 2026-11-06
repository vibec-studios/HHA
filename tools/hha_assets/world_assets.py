"""Texturen für Olymp, Hades und Tartaros (über mctex). Aufruf: python world_assets.py"""
import json
import math
import os
import random

from texkit import ASSETS, Image, flush, hexc, mix, ramp, save, tile_noise

GOLD = [hexc('#5a2c0a'), hexc('#c98a1c'), hexc('#efbb36'), hexc('#ffe27a'), hexc('#fff6c8')]
MARBLE = [hexc('#8f8c96'), hexc('#c4c1c8'), hexc('#e6e3e4'), hexc('#fbf9f6')]
BLACK = [hexc('#08070c'), hexc('#15131c'), hexc('#262330'), hexc('#3a3648')]
GREEN = [hexc('#04240e'), hexc('#0e7a2c'), hexc('#2fd14c'), hexc('#7bff8e'), hexc('#e8ffd0')]


def write_mcmeta(rel, frametime, interpolate=True):
    with open(os.path.join(ASSETS, rel + '.mcmeta'), 'w', encoding='utf8') as f:
        json.dump({'animation': {'frametime': frametime, 'interpolate': interpolate}}, f)


def stone_face(base, seed, inlay=None):
    """16x16-Fläche mit feinem Korn; inlay(x, y) kann eine Farbe liefern."""
    noise = tile_noise(16, 3, seed, octaves=2)

    def paint(x, y):
        if inlay:
            color = inlay(x, y)
            if color is not None:
                return color
        edge = x in (0, 15) or y in (0, 15)
        return ramp(base, noise(x, y) * 0.5 + 0.28 - (0.12 if edge else 0.0))
    return Image(16, 16).fn(paint)


def pedestals():
    gold_edge = lambda x, y: ramp(GOLD, 0.5 + (x + y) % 3 * 0.1) if x in (0, 15) or y in (0, 15) else None

    def rune(lit):
        def paint(x, y):
            if x in (0, 15) or y in (0, 15):
                return ramp(GOLD, 0.6)
            r = math.hypot(x - 7.5, y - 7.5)
            if r < 2.4:
                return ramp(GOLD, 0.98) if lit else ramp(GOLD, 0.4)
            if 4.0 < r < 5.2:
                return ramp(GOLD, 0.82) if lit else ramp(GOLD, 0.3)
            return None
        return paint

    save(stone_face(MARBLE, 201, gold_edge), 'textures/block/offering_pedestal_side.png')
    save(stone_face(MARBLE, 202, rune(False)), 'textures/block/offering_pedestal_top.png')
    save(stone_face(MARBLE, 203, rune(True)), 'textures/block/offering_pedestal_top_lit.png')


def altars():
    def sun(x, y):
        r = math.hypot(x - 7.5, y - 7.5)
        a = math.atan2(y - 7.5, x - 7.5)
        if r < 2.6:
            return ramp(GOLD, 0.98)
        if r < 7.0 and abs(math.sin(a * 4.0)) < 0.24:
            return ramp(GOLD, 0.8)
        return None

    def column(x, y):
        if y in (0, 1, 14, 15):
            return ramp(GOLD, 0.7)
        if x % 4 == 1:
            return ramp(MARBLE, 0.2)
        return None

    save(stone_face(MARBLE, 204, sun), 'textures/block/olympus_altar_top.png')
    save(stone_face(MARBLE, 205, column), 'textures/block/olympus_altar_side.png')

    def eye(x, y):
        dx, dy = x - 7.5, (y - 7.5) * 1.7
        r = math.hypot(dx, dy)
        if r < 1.7:
            return ramp(GREEN, 0.98)
        if 3.4 < r < 4.6 or abs(dy) < 0.7 and abs(dx) < 7:
            return ramp(GREEN, 0.6)
        return None

    def bars(x, y):
        if y in (0, 15):
            return ramp(GREEN, 0.4)
        if x % 5 == 2 and 2 < y < 13:
            return ramp(GREEN, 0.3 + 0.03 * y)
        return None

    save(stone_face(BLACK, 206, eye), 'textures/block/hades_altar_top.png')
    save(stone_face(BLACK, 207, bars), 'textures/block/hades_altar_side.png')


def green_fire():
    """Flammen als scrollendes Rauschen; die Höhe sinkt zu den Rändern. Jeder Frame kachelt nahtlos in den nächsten."""
    size = 16
    frames = 24
    for variant in range(2):
        noise = tile_noise(64, 6, 300 + variant, octaves=3)
        wobble = tile_noise(64, 10, 340 + variant, octaves=2)
        img = Image(size, size * frames)
        for frame in range(frames):
            scroll = frame * 64 // frames
            for x in range(size):
                # Jede Spalte ist eine Flammenzunge, deren Höhe mit der Zeit wandert.
                tongue = 0.28 + 0.72 * noise((x * 4 + variant * 9) % 64, scroll)
                for y in range(size):
                    height = 1.0 - y / (size - 1.0)
                    limit = tongue + (wobble((x * 5) % 64, (y * 4 + scroll * 2) % 64) - 0.5) * 0.3
                    if height > limit:
                        continue
                    # Unten heiß und hell, zur Spitze hin dunkles Grün.
                    heat = 1.0 - height / max(limit, 0.05)
                    img.set(x, y + frame * size, ramp(GREEN, 0.26 + heat * 0.62))
        rel = f'textures/block/green_fire_{variant}.png'
        save(img, rel)
    return frames


def portal():
    """Das Tor: langsam kreisende Schlieren aus grünem Licht, halb durchsichtig."""
    size = 16
    frames = 32
    a = tile_noise(64, 5, 311, octaves=3)
    b = tile_noise(64, 7, 312, octaves=2)
    img = Image(size, size * frames)
    for frame in range(frames):
        t = frame / frames
        for y in range(size):
            for x in range(size):
                ox = int(math.sin(t * math.tau) * 10)
                oy = int(t * 64)
                v = a((x * 4 + ox) % 64, (y * 4 - oy) % 64) * 0.65 + b((x * 4 - ox) % 64, (y * 4 + oy) % 64) * 0.55
                value = max(0.0, min(1.0, (v - 0.32) * 1.5))
                color = ramp(GREEN, 0.16 + value * 0.84)
                alpha = int(130 + value * 115)
                img.set(x, y + frame * size, (color[0], color[1], color[2], alpha))
    save(img, 'textures/block/hades_portal.png')
    return frames


def hades_map():
    """Karte zu Hades: dunkles, angesengtes Pergament mit einer grünen Spur zu einem Kreuz."""
    parchment = [hexc('#2a2118'), hexc('#4a3a28'), hexc('#6c583c'), hexc('#8a7350')]
    rnd = random.Random(9)
    noise = tile_noise(16, 4, 320, octaves=2)
    trail = {(3, 12), (4, 11), (5, 11), (6, 10), (7, 9), (7, 8), (8, 7), (9, 7), (10, 6)}
    cross = {(10, 3), (12, 3), (11, 4), (10, 5), (12, 5)}

    def paint(x, y):
        # Ausgefranster Rand.
        if x in (1, 14) or y in (1, 14):
            if rnd.random() < 0.3:
                return (0, 0, 0, 0)
        if x < 1 or x > 14 or y < 1 or y > 14:
            return (0, 0, 0, 0)
        if (x, y) in cross:
            return ramp(GREEN, 0.95)
        if (x, y) in trail:
            return ramp(GREEN, 0.62)
        if x in (1, 14) or y in (1, 14):
            return mix(ramp(parchment, noise(x, y)), hexc('#0c0a08'), 0.55)
        return ramp(parchment, noise(x, y) * 0.8 + 0.2)

    save(Image(16, 16).fn(paint), 'textures/item/hades_map.png')


def zeus_eye():
    """Zornig geformtes Auge: Mandelform, gleißende Iris, senkrechte Pupille aus Licht, feine Risse."""
    w, h = 256, 128
    rnd = random.Random(21)
    cracks = [(rnd.uniform(0, math.tau), rnd.uniform(0.4, 1.0), rnd.uniform(0.01, 0.03)) for _ in range(26)]

    def f(px, py):
        u = (px + 0.5) / w * 2.0 - 1.0
        v = (py + 0.5) / h * 2.0 - 1.0
        # Mandelform: oben flacher (schweres Lid), unten runder.
        limit = (1.0 - u * u) ** 0.8
        upper = limit * (0.72 - 0.16 * u)
        lower = limit * 0.92
        edge = (upper + v) / max(upper, 0.001) if v < 0 else (lower - v) / max(lower, 0.001)
        if edge <= 0.0 or limit <= 0.0:
            return (255, 255, 255, 1)
        mask = min(1.0, edge * 4.0)
        r = math.hypot(u * 2.0, v * 1.15)
        a = math.atan2(v, u)
        iris = max(0.0, 1.0 - r / 0.95)
        value = 0.26 + 0.74 * iris ** 0.6
        # Risse strahlen aus der Iris.
        for angle, length, width in cracks:
            if r < length and abs(math.sin(a - angle)) < width / max(r, 0.08):
                value += 0.35 * (1.0 - r / length)
        # Senkrechte Pupille: der hellste Punkt.
        pupil = max(0.0, 1.0 - abs(u) * 11.0) * max(0.0, 1.0 - abs(v) * 1.2)
        value = min(1.0, value + pupil)
        return (255, 255, 255, max(1, int(mask * min(1.0, value) * 255)))

    save(Image(w, h).fn(f), 'textures/vfx/zeus_eye.png')


def wasteland():
    """Blöcke des Tartaros: Kohlenschwarze Oberseiten mit fast schwarzen Flanken ergeben die Höhenlinien im Ödland."""
    ash = [hexc('#1d1c20'), hexc('#2a292e'), hexc('#37363c'), hexc('#44434a')]
    flank = [hexc('#0e0d10'), hexc('#161519'), hexc('#1e1d22')]
    deep = [hexc('#0a0a0d'), hexc('#121116'), hexc('#1a191f')]
    rust = [hexc('#3e2412'), hexc('#5a3518'), hexc('#74471f'), hexc('#8a5a2a')]
    grey = [hexc('#3a3a40'), hexc('#54545c'), hexc('#6e6e76'), hexc('#8a8a92')]
    pale = [hexc('#5f8f63'), hexc('#7fae80'), hexc('#a3c9a0'), hexc('#c6e2c0'), hexc('#e6f6dc')]
    lichen = [hexc('#6c9a68'), hexc('#8fb985'), hexc('#a9cf9f'), hexc('#c4e2b6')]
    gate = [hexc('#08080c'), hexc('#101018'), hexc('#191924')]

    def textured(colors, seed, base=0.1, span=0.85, extra=None):
        noise = tile_noise(16, 4, seed, octaves=2)

        def paint(x, y):
            if extra:
                color = extra(x, y)
                if color is not None:
                    return color
            return ramp(colors, base + noise(x, y) * span)
        return Image(16, 16).fn(paint)

    crack = {(3, 2), (4, 3), (4, 4), (5, 5), (11, 9), (12, 10), (12, 11), (13, 12), (2, 12), (3, 13)}
    save(textured(ash, 401, 0.05, 0.95, lambda x, y: hexc('#141317') if (x, y) in crack else None), 'textures/block/ash_stone_top.png')
    save(textured(flank, 402), 'textures/block/ash_stone_side.png')
    save(textured(deep, 403), 'textures/block/deep_ash.png')
    save(textured(rust, 404), 'textures/block/rust_soil.png')
    flecks = {(2, 4), (7, 2), (12, 5), (5, 9), (10, 11), (14, 13), (3, 14)}
    save(textured(grey, 405, 0.05, 0.9, lambda x, y: hexc('#ffd75a') if (x, y) in flecks else None), 'textures/block/gilded_ash.png')
    save(textured(lichen, 406), 'textures/block/crystal_moss.png')

    def facet(x, y):
        # Schräge Schliffkanten: helle Grate, dunklere Flächen dazwischen.
        band = ((x + y) % 8)
        if band == 0:
            return ramp(pale, 0.98)
        if band == 1:
            return ramp(pale, 0.82)
        if (x - y) % 11 == 0:
            return ramp(pale, 0.2)
        return None

    save(textured(pale, 407, 0.2, 0.5, facet), 'textures/block/pale_crystal.png')

    def veins(x, y):
        if (x, y) in {(2, 3), (3, 4), (4, 4), (5, 5), (5, 6), (10, 9), (11, 10), (11, 11), (12, 12), (8, 2), (8, 3), (3, 12), (4, 13)}:
            return ramp(GREEN, 0.7)
        return None

    save(textured(gate, 408, 0.1, 0.85, veins), 'textures/block/gate_stone.png')


def main():
    wasteland()
    pedestals()
    altars()
    hades_map()
    zeus_eye()
    fire_frames = green_fire()
    portal_frames = portal()
    flush()
    for variant in range(2):
        write_mcmeta(f'textures/block/green_fire_{variant}.png', 1, True)
    write_mcmeta('textures/block/hades_portal.png', 2, True)
    print('Fertig:', fire_frames, 'Feuer-Frames,', portal_frames, 'Portal-Frames')


if __name__ == '__main__':
    main()
