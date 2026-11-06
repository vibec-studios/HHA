"""Erzeugt die Effekt-Texturen unter assets/hha/textures/vfx/ (über mctex)."""
import math
import random

from feather_art import atlas as feather_atlas, particles as feather_particles, vfx_sprite as feather
from texkit import Image, flush, hexc, lerp, ramp, save, tile_noise


def noise_tile():
    """RGB = drei unabhängige, kachelnde Noise-Kanäle für den Energie-/Flow-Shader."""
    size = 128
    r = tile_noise(size, 4, 11)
    g = tile_noise(size, 6, 23)
    b = tile_noise(size, 8, 37)

    def contrast(v):
        return max(0.0, min(1.0, (v - 0.5) * 2.1 + 0.5))

    img = Image(size, size).fn(lambda x, y: (
        int(contrast(r(x, y)) * 255), int(contrast(g(x, y)) * 255), int(contrast(b(x, y)) * 255), 255))
    save(img, 'textures/vfx/noise.png')


def glow():
    """Weicher Lichtpunkt."""
    size = 64

    def f(x, y):
        d = math.hypot(x + 0.5 - size / 2, y + 0.5 - size / 2) / (size / 2)
        a = max(0.0, 1.0 - d) ** 2.2
        return (255, 255, 255, int(a * 255))

    save(Image(size, size).fn(f), 'textures/vfx/glow.png')


def flare():
    """Lichtstern mit dünnen Strahlen — Mündung, Einschlag, Blitzkern."""
    size = 128
    rnd = random.Random(7)
    rays_list = [(rnd.uniform(0, math.pi * 2), rnd.uniform(0.35, 0.8), rnd.uniform(0.008, 0.022)) for _ in range(14)]

    def f(x, y):
        dx, dy = x + 0.5 - size / 2, y + 0.5 - size / 2
        d = math.hypot(dx, dy) / (size / 2)
        ang = math.atan2(dy, dx)
        v = max(0.0, 1.0 - d * 3.2) ** 1.5
        for a0 in (0, math.pi / 2):
            diff = abs(math.sin(ang - a0))
            v += max(0.0, 1.0 - d) ** 2 * max(0.0, 1.0 - diff * 22)
        for a0, length, width in rays_list:
            diff = abs(math.sin(ang - a0))
            if d < length:
                v += (1.0 - d / length) * max(0.0, 1.0 - diff / width) * 0.55
        return (255, 255, 255, int(min(1.0, v) * 255))

    save(Image(size, size).fn(f), 'textures/vfx/flare.png')


def rays():
    """Horizontal kachelnde Lichtstreifen (Säulen, Wände): u kachelt, v verblasst nach oben."""
    w, h = 128, 64
    n = tile_noise(w, 8, 41, octaves=2)
    rnd = random.Random(5)
    columns = [rnd.random() ** 2 for _ in range(16)]

    def f(x, y):
        streak = lerp(columns[int(x / w * 16)], columns[(int(x / w * 16) + 1) % 16], (x / w * 16) % 1)
        fade = (1.0 - y / (h - 1)) ** 1.4
        v = (0.35 + streak * 0.65 * (0.6 + n(x, 0) * 0.8)) * fade
        return (255, 255, 255, int(max(0.0, min(1.0, v)) * 255))

    save(Image(w, h).fn(f), 'textures/vfx/rays.png')


def rune_circle():
    """Magischer Kreis für Telegraphen und Beschwörungen — kreisrund, weiß, wird eingefärbt."""
    size = 256
    c = size / 2
    rnd = random.Random(99)
    glyphs = []
    for i in range(24):
        ang = i / 24 * math.pi * 2
        strokes = []
        for _ in range(rnd.randint(2, 4)):
            strokes.append(tuple(rnd.uniform(-1, 1) for _ in range(4)))
        glyphs.append((ang, strokes))

    def seg_dist(px, py, ax, ay, bx, by):
        vx, vy = bx - ax, by - ay
        t = max(0.0, min(1.0, ((px - ax) * vx + (py - ay) * vy) / max(vx * vx + vy * vy, 1e-6)))
        return math.hypot(px - (ax + vx * t), py - (ay + vy * t))

    def ring(d, r, w):
        return max(0.0, 1.0 - abs(d - r) / w)

    def f(x, y):
        dx, dy = (x + 0.5 - c) / c, (y + 0.5 - c) / c
        d = math.hypot(dx, dy)
        ang = math.atan2(dy, dx)
        v = 0.0
        v = max(v, ring(d, 0.975, 0.016) * 1.0)
        v = max(v, ring(d, 0.93, 0.008) * 0.8)
        v = max(v, ring(d, 0.70, 0.012) * 0.9)
        v = max(v, ring(d, 0.34, 0.010) * 0.8)
        # Runenband zwischen den Außenringen
        if 0.72 < d < 0.92:
            seg = ang / (math.pi * 2) * 24
            i = int(math.floor(seg)) % 24
            local_a = (seg - math.floor(seg) - 0.5) * 1.5
            local_r = (d - 0.82) / 0.085
            for ax, ay, bx, by in glyphs[i][1]:
                dist = seg_dist(local_a, local_r, ax * 0.6, ay * 0.7, bx * 0.6, by * 0.7)
                v = max(v, max(0.0, 1.0 - dist / 0.11))
        # Achtzackiger Stern im Inneren
        for k in range(8):
            a = k / 8 * math.pi * 2
            b = (k + 3) / 8 * math.pi * 2
            dist = seg_dist(dx, dy, math.cos(a) * 0.68, math.sin(a) * 0.68, math.cos(b) * 0.68, math.sin(b) * 0.68)
            v = max(v, max(0.0, 1.0 - dist / 0.011) * 0.75)
        # Zarte Füllung
        if d < 0.97:
            v = max(v, 0.10 + 0.08 * d)
        if d > 1.0:
            v = 0.0
        return (255, 255, 255, int(min(1.0, v) * 255))

    save(Image(size, size).fn(f), 'textures/vfx/rune_circle.png')


def rune_ring():
    """Nur der Runenring (für drehende Ringe und Heiligenschein-Bänder), u kachelt."""
    w, h = 256, 32
    rnd = random.Random(1337)
    cols = []
    x = 0
    while x < w:
        cols.append((x, rnd.randint(8, 14)))
        x += cols[-1][1] + 4
    glyph = {}
    for start, width in cols:
        for _ in range(3):
            ax, ay = rnd.randint(0, width), rnd.randint(8, 23)
            bx, by = rnd.randint(0, width), rnd.randint(8, 23)
            steps = max(abs(ax - bx), abs(ay - by)) + 1
            for s in range(steps + 1):
                t = s / steps
                glyph[(start + int(lerp(ax, bx, t)), int(lerp(ay, by, t)))] = True

    def f(x, y):
        v = 0.08
        if y in (2, 3, 28, 29):
            v = 0.95
        elif y in (1, 4, 27, 30):
            v = 0.35
        if glyph.get((x, y)):
            v = 1.0
        elif glyph.get((x - 1, y)) or glyph.get((x + 1, y)) or glyph.get((x, y - 1)) or glyph.get((x, y + 1)):
            v = max(v, 0.45)
        return (255, 255, 255, int(v * 255))

    save(Image(w, h).fn(f), 'textures/vfx/rune_ring.png')


def smoke():
    """Weiche Rauchwolke, kachelt nicht — Sprite für Staub und Seelennebel."""
    size = 64
    n = tile_noise(size, 4, 73)

    def f(x, y):
        d = math.hypot(x + 0.5 - size / 2, y + 0.5 - size / 2) / (size / 2)
        a = max(0.0, 1.0 - d ** 1.6) * max(0.0, min(1.0, (n(x, y) - 0.25) * 2.0))
        return (255, 255, 255, int(a * 255))

    save(Image(size, size).fn(f), 'textures/vfx/smoke.png')


def cloud_sampler():
    """Dichte aus vielen weichen Kugeln (Blumenkohl-Form) plus feinem Rauschen; oben heller, unten dunkler."""
    size = 128
    rnd = random.Random(91)
    blobs = []
    for _ in range(26):
        ang = rnd.uniform(0, math.tau)
        dist = rnd.uniform(0.0, 0.6) ** 1.2
        blobs.append((math.cos(ang) * dist, math.sin(ang) * dist, rnd.uniform(0.14, 0.32)))
    fine = tile_noise(size, 8, 91, octaves=3)

    def sample(x, y):
        u, v = (x + 0.5) / size * 2 - 1, (y + 0.5) / size * 2 - 1
        density = 0.0
        light = 0.0
        for bx, by, br in blobs:
            q = ((u - bx) ** 2 + (v - by) ** 2) / (br * br)
            g = math.exp(-q)
            density += g
            # Jede Kugel ist auf ihrer Oberseite heller: das ergibt die Wölbungen.
            light += g * (0.5 - (v - by) / br * 0.5)
        edge = max(0.0, 1.0 - (u * u + v * v) ** 1.5)
        density = density * edge + (fine(x, y) - 0.5) * 0.85 * edge
        alpha = max(0.0, min(1.0, (density - 0.18) / 1.5))
        alpha = alpha * alpha * (3.0 - 2.0 * alpha)
        shade = 0.76 + 0.24 * max(0.0, min(1.0, light / max(density, 0.25))) + (0.5 - (y / (size - 1))) * 0.14
        value = int(max(0.0, min(1.0, shade)) * 255)
        return (value, value, value, int(alpha * 255))

    return size, sample


def cloud():
    """Wolkenbausch für die 3D-Wolken: mehrere überlagert ergeben das Volumen."""
    size, sample = cloud_sampler()
    save(Image(size, size).fn(sample), 'textures/vfx/cloud.png')


def shard():
    """Marmorsplitter/Trümmer, leicht fleckig."""
    size = 16
    n = tile_noise(size, 2, 3, octaves=3)

    def f(x, y):
        v = int(lerp(150, 255, n(x, y)))
        return (v, v, v, 255)

    save(Image(size, size).fn(f), 'textures/vfx/shard.png')


def chain_link():
    """Metallverlauf für die 3D-Kettenglieder."""
    steel = [hexc('#2a2c33'), hexc('#4c5059'), hexc('#7f8590'), hexc('#c0c6d0'), hexc('#7f8590'), hexc('#3a3d45')]
    n = tile_noise(16, 2, 19, octaves=2)
    save(Image(16, 16).fn(lambda x, y: ramp(steel, y / 15 * 0.85 + n(x, y) * 0.15)), 'textures/vfx/chain.png')


if __name__ == '__main__':
    noise_tile()
    glow()
    flare()
    rays()
    feather()
    feather_particles()
    feather_atlas()
    rune_circle()
    rune_ring()
    smoke()
    cloud()
    shard()
    chain_link()
    flush()
