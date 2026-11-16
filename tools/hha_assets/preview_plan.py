"""Zeichnet die Baupläne aus `gradle runVerifyWorldgen` als Isometrie oder Ansicht von der Seite, ohne Spielstart.

Aufruf:
    python preview_plan.py build/run/verifyWorldgen/plan_palace.txt <ausgabe.png> [scale] [yaw 0..3] [ymin] [ymax]
        [--side] [--silhouette] [--no-terrain]

- Der Schnitt mit ymax zeigt das Innere; so lässt sich etwa der Thronsaal unter der Kuppel prüfen.
- `--side` zeichnet eine Ansicht von der Seite (Blick nach Norden, mit yaw gedreht), `--silhouette` dazu nur den
  Schattenriss schwarz auf hellem Dunst: So sieht ein Bauwerk aus 150 Blöcken Entfernung aus.
- Der Pseudoblock `terrain` (Gelände unter dem Plan) wird matt gezeichnet; `--no-terrain` blendet ihn aus.

Die Farben kommen aus den Vanilla-Texturen im Minecraft-Client-Jar (Mittelwert, getrennt nach Ober- und Seitenfläche)
und aus den Mod-Texturen; einmal berechnet, liegen sie in build/hha_block_colors.json.
"""
import glob
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
CACHE = os.path.join(ROOT, 'build', 'hha_block_colors.json')

# Feste Farben für alles, was eine Textur nicht gut beschreibt (eingefärbt, durchsichtig, Mod-Blöcke ohne Würfel).
FIXED = {
    'water': (38, 74, 140), 'green_fire': (120, 255, 130), 'fire': (255, 150, 40), 'soul_fire': (80, 220, 230),
    'hades_seal': (40, 220, 100), 'hades_portal': (30, 200, 90), 'stele': (150, 150, 160), 'chest': (150, 110, 50), 'spawner': (40, 60, 80),
    'olympus_altar': (236, 220, 170), 'offering_pedestal': (220, 214, 200), 'hades_altar': (60, 50, 70),
    'oak_leaves': (60, 100, 40), 'azalea_leaves': (80, 110, 50), 'flowering_azalea_leaves': (110, 120, 80), 'pale_hanging_moss': (170, 176, 168),
    'skeleton_skull': (220, 216, 200), 'wither_skeleton_skull': (40, 40, 40), 'skeleton_wall_skull': (220, 216, 200),
    'cave_vines': (90, 120, 40), 'cave_vines_plant': (90, 120, 40), 'short_dry_grass': (176, 150, 100), 'tall_dry_grass': (176, 150, 100),
    'dead_bush': (120, 86, 50), 'end_rod': (240, 236, 220),
}

# Blöcke, deren Textur anders heißt.
ALIAS = {
    'terrain': ('blackstone_top', 'blackstone'), 'smooth_quartz': ('quartz_block_bottom', 'quartz_block_bottom'), 'quartz_block': ('quartz_block_top', 'quartz_block_side'),
    'smooth_stone': ('smooth_stone', 'smooth_stone_slab_side'), 'smooth_sandstone': ('sandstone_top', 'sandstone_top'),
    'smooth_red_sandstone': ('red_sandstone_top', 'red_sandstone_top'), 'cut_sandstone': ('sandstone_top', 'cut_sandstone'),
    'snow': ('snow', 'snow'), 'snow_block': ('snow', 'snow'), 'glow_berries': ('cave_vines_lit', 'cave_vines_lit'),
    'cave_vines': ('cave_vines_lit', 'cave_vines_lit'), 'cave_vines_plant': ('cave_vines_plant_lit', 'cave_vines_plant_lit'),
    'lantern': ('lantern', 'lantern'), 'soul_lantern': ('soul_lantern', 'soul_lantern'),
    'waxed_copper_bulb': ('copper_bulb_lit', 'copper_bulb_lit'), 'copper_bulb': ('copper_bulb_lit', 'copper_bulb_lit'),
    'bone_block': ('bone_block_top', 'bone_block_side'), 'magma_block': ('magma', 'magma'),
}


def decode_png(data):
    """Kleiner PNG-Leser (8 Bit, alle Farbtypen außer Interlace); liefert Liste von (r, g, b, a)."""
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        return []
    pos = 8
    width = height = depth = ctype = 0
    palette = []
    alpha = []
    idat = b''
    while pos < len(data):
        length = struct.unpack('>I', data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b'IHDR':
            width, height, depth, ctype, _, _, interlace = struct.unpack('>IIBBBBB', body)
            if interlace:
                return []
        elif kind == b'PLTE':
            palette = [tuple(body[i:i + 3]) for i in range(0, len(body), 3)]
        elif kind == b'tRNS':
            alpha = list(body)
        elif kind == b'IDAT':
            idat += body
    if depth != 8 and ctype != 3:
        return []
    raw = zlib.decompress(idat)
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    bits = depth * channels
    stride = (width * bits + 7) // 8
    bpp = max(1, bits // 8)
    rows = []
    prev = bytearray(stride)
    i = 0
    for _ in range(height):
        f = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        prev = line
    out = []
    for line in rows:
        if ctype == 3:
            per = 8 // depth
            for x in range(width):
                byte = line[x // per]
                index = (byte >> (8 - depth * (x % per + 1))) & ((1 << depth) - 1)
                r, g, b = palette[index] if index < len(palette) else (0, 0, 0)
                out.append((r, g, b, alpha[index] if index < len(alpha) else 255))
        else:
            for x in range(width):
                px = line[x * channels:(x + 1) * channels]
                if ctype == 0:
                    out.append((px[0], px[0], px[0], 255))
                elif ctype == 4:
                    out.append((px[0], px[0], px[0], px[1]))
                elif ctype == 2:
                    out.append((px[0], px[1], px[2], 255))
                else:
                    out.append(tuple(px))
    return out


def average(pixels):
    total = [0.0, 0.0, 0.0]
    weight = 0.0
    for r, g, b, a in pixels:
        w = a / 255.0
        total[0] += r * w
        total[1] += g * w
        total[2] += b * w
        weight += w
    if weight < 1:
        return None
    return [int(v / weight) for v in total]


def texture_table():
    if os.path.exists(CACHE):
        with open(CACHE, encoding='utf8') as f:
            return json.load(f)
    table = {}
    jars = glob.glob(os.path.expanduser('~/.gradle/caches/fabric-loom/*/minecraft-client.jar'))
    for jar in sorted(jars)[-1:]:
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if name.startswith('assets/minecraft/textures/block/') and name.endswith('.png'):
                    color = average(decode_png(z.read(name)))
                    if color:
                        table[name.rsplit('/', 1)[1][:-4]] = color
    for path in glob.glob(os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'hha', 'textures', 'block', '*.png')):
        with open(path, 'rb') as f:
            color = average(decode_png(f.read()))
        if color:
            table.setdefault(os.path.basename(path)[:-4], color)
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    with open(CACHE, 'w', encoding='utf8') as f:
        json.dump(table, f)
    return table


TEX = None


def stems(name):
    """Mögliche Texturnamen für einen Block, vom genauesten zum gröbsten."""
    base = name[len('waxed_'):] if name.startswith('waxed_') else name
    out = [base]
    for suffix in ('_stairs', '_slab', '_wall', '_fence', '_fence_gate', '_pressure_plate', '_button', '_carpet'):
        if base.endswith(suffix):
            stem = base[:-len(suffix)]
            out += [stem, stem + 's', stem + '_block', stem + '_planks']
            if stem.endswith('brick'):
                out.append(stem + 's')
            if stem.endswith('tile'):
                out.append(stem + 's')
    if base.endswith('_wood'):
        out.append(base[:-5] + '_log')
    if base.endswith('_hyphae'):
        out.append(base[:-7] + '_stem')
    return out


def colors(name):
    """(Oberseite, Seite) als RGB."""
    global TEX
    if TEX is None:
        TEX = texture_table()
    if name in FIXED:
        c = FIXED[name]
        return c, c
    candidates = []
    if name in ALIAS:
        candidates.append(ALIAS[name])
    for stem in stems(name):
        if stem in ALIAS:
            candidates.append(ALIAS[stem])
        candidates.append((stem + '_top', stem + '_side'))
        candidates.append((stem + '_top', stem))
        candidates.append((stem, stem))
        candidates.append((stem + '_end', stem + '_side'))
    for top, side in candidates:
        if top in TEX or side in TEX:
            return tuple(TEX.get(top, TEX.get(side))), tuple(TEX.get(side, TEX.get(top)))
    if 'skull' in name:
        return (220, 216, 200), (220, 216, 200)
    return (150, 60, 170), (150, 60, 170)


# Blöcke, die selbst leuchten: im Bild etwas heller, damit Licht im dunklen Ödland lesbar bleibt.
GLOW = ('lantern', 'froglight', 'fire', 'shroomlight', 'magma', 'bulb', 'sea_lantern', 'glowstone', 'end_rod', 'cave_vines', 'crying_obsidian')


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    flags = {a for a in sys.argv[1:] if a.startswith('--')}
    src, out = args[0], args[1]
    scale = int(args[2]) if len(args) > 2 else 4
    yaw = int(args[3]) if len(args) > 3 else 0
    ymin = int(args[4]) if len(args) > 4 else -10**9
    ymax = int(args[5]) if len(args) > 5 else 10**9
    terrain = '--no-terrain' not in flags
    blocks = {}
    for line in open(src, encoding='utf8'):
        x, y, z, name = line.split()
        x, y, z = int(x), int(y), int(z)
        if name == 'terrain' and not terrain:
            continue
        if ymin <= y <= ymax:
            if yaw == 1:
                x, z = -z, x
            elif yaw == 2:
                x, z = -x, -z
            elif yaw == 3:
                x, z = z, -x
            blocks[(x, y, z)] = name
    if '--side' in flags:
        side_view(blocks, out, scale, '--silhouette' in flags)
        return
    # Isometrie: x nach rechts unten, z nach links unten, y nach oben.
    w = scale * 2
    h = scale
    drawn = []
    for (x, y, z), name in blocks.items():
        top = (x, y + 1, z) not in blocks
        left = (x, y, z + 1) not in blocks
        right = (x + 1, y, z) not in blocks
        if top or left or right:
            drawn.append((x + z + y, x, y, z, name, top, left, right))
    drawn.sort()
    xs = [(d[1] - d[3]) * w for d in drawn]
    ys = [(d[1] + d[3]) * h - d[2] * (scale * 2) for d in drawn]
    min_x, max_x = min(xs) - w * 2, max(xs) + w * 2
    min_y, max_y = min(ys) - h * 4, max(ys) + scale * 6
    width = max_x - min_x
    height = max_y - min_y
    limit = 2600
    if width > limit * 2 or height > limit * 2:
        raise SystemExit(f'Bild zu groß: {width}x{height}, kleinere Skala wählen')
    pix = bytearray(bytes((18, 8, 10)) * (width * height))

    def span(px, y0, y1, c):
        if 0 <= px < width:
            for py in range(max(y0, 0), min(y1, height)):
                i = (py * width + px) * 3
                pix[i:i + 3] = c

    cache = {}
    for d, sx, sy in zip(drawn, xs, ys):
        _, _, _, _, name, top, left, right = d
        if name not in cache:
            top_rgb, side_rgb = colors(name)
            lift = 1.25 if any(g in name for g in GLOW) else 1.0
            cache[name] = (
                bytes(min(255, int(v * 1.12 * lift)) for v in top_rgb),
                bytes(min(255, int(v * 0.78 * lift)) for v in side_rgb),
                bytes(min(255, int(v * 0.55 * lift)) for v in side_rgb),
            )
        top_c, left_c, right_c = cache[name]
        cx = sx - min_x
        cy = sy - min_y
        tall = scale * 2
        for i in range(w):
            depth = (i * h) // w
            if top:
                span(cx - i - 1, cy - h + depth, cy + h - depth, top_c)
                span(cx + i, cy - h + depth, cy + h - depth, top_c)
            if left:
                span(cx - i - 1, cy + h - depth, cy + h - depth + tall, left_c)
            if right:
                span(cx + i, cy + h - depth, cy + h - depth + tall, right_c)
    save(pix, width, height, out)
    print(f'{len(blocks)} Blöcke, {width}x{height}')


def side_view(blocks, out, scale, silhouette):
    """Ansicht von Süden: der vorderste Block je (x, y), nach Tiefe abgedunkelt; oder nur der Schattenriss."""
    front = {}
    for (x, y, z), name in blocks.items():
        if silhouette and name == 'terrain':
            continue
        key = (x, y)
        if key not in front or z > front[key][0]:
            front[key] = (z, name)
    xs = [k[0] for k in front]
    ys = [k[1] for k in front]
    zs = [v[0] for v in front.values()]
    min_x, max_x, min_y, max_y = min(xs) - 4, max(xs) + 4, min(ys) - 4, max(ys) + 6
    z_far, z_near = min(zs), max(zs)
    width = (max_x - min_x + 1) * scale
    height = (max_y - min_y + 1) * scale
    sky = (196, 178, 170) if silhouette else (18, 8, 10)
    pix = bytearray(bytes(sky) * (width * height))
    for (x, y), (z, name) in front.items():
        if silhouette:
            c = (20, 16, 18)
        else:
            _, side = colors(name)
            fade = 0.55 + 0.45 * (z - z_far) / max(1, z_near - z_far)
            c = tuple(min(255, int(v * fade)) for v in side)
        px = (x - min_x) * scale
        py = (max_y - y) * scale
        for dy in range(scale):
            i = ((py + dy) * width + px) * 3
            pix[i:i + scale * 3] = bytes(c) * scale
    save(pix, width, height, out)
    print(f'Seitenansicht {width}x{height}')


def save(pix, width, height, out):
    tmp = tempfile.mktemp(suffix='.ppm')
    with open(tmp, 'wb') as f:
        f.write(f'P6 {width} {height} 255\n'.encode())
        f.write(pix)
    subprocess.run([shutil.which('ffmpeg'), '-hide_banner', '-loglevel', 'error', '-y', '-i', tmp, out], check=True)
    os.remove(tmp)


if __name__ == '__main__':
    main()
