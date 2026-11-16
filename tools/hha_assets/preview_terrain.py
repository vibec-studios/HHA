"""Zeichnet das Tartaros-Gelände aus `gradle runVerifyWorldgen -Dhha.mapTartaros=true`.

Aufruf: python preview_terrain.py build/run/verifyWorldgen <biomkarte.png> [bodenkarte.png] [--compare alte_biomkarte.png]

- Biomkarte: 1600 x 1600 Blöcke (ein Pixelquadrat je 8 Blöcke), Biomfarben mit Schattierung nach Höhe.
- Bodenkarte: 384 x 384 Blöcke Block für Block, aus den Rauschwerten in tartaros_surface.txt und denselben Schwellen wie
  die Oberflächenregeln (tartaros_world.SURFACES); Farben aus den Vanilla-Texturen (preview_plan.colors).
- Mit --compare zählt das Skript die zusammenhängenden Biomflächen beider Karten (je weniger, desto größer und ruhiger).
"""
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import preview_plan  # noqa: E402
import tartaros_world  # noqa: E402

COLORS = {
    'tartaros': (70, 42, 44), 'asphodel': (150, 165, 145), 'phlegethon': (160, 72, 28),
    'kokytos': (150, 190, 220), 'erebos': (58, 32, 92), 'styx_marsh': (50, 110, 72),
}


def save(pix, width, height, out):
    tmp = tempfile.mktemp(suffix='.ppm')
    with open(tmp, 'wb') as f:
        f.write(f'P6 {width} {height} 255\n'.encode())
        f.write(pix)
    subprocess.run([shutil.which('ffmpeg'), '-hide_banner', '-loglevel', 'error', '-y', '-i', tmp, out], check=True)
    os.remove(tmp)


def regions(grid):
    """Zahl der zusammenhängenden Flächen gleicher Kennung (4er-Nachbarschaft)."""
    size_z = len(grid)
    size_x = len(grid[0])
    seen = [[False] * size_x for _ in range(size_z)]
    count = 0
    for z0 in range(size_z):
        for x0 in range(size_x):
            if seen[z0][x0]:
                continue
            count += 1
            key = grid[z0][x0]
            stack = [(x0, z0)]
            seen[z0][x0] = True
            while stack:
                x, z = stack.pop()
                for nx, nz in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
                    if 0 <= nx < size_x and 0 <= nz < size_z and not seen[nz][nx] and grid[nz][nx] == key:
                        seen[nz][nx] = True
                        stack.append((nx, nz))
    return count


def png_grid(path, cell):
    """Liest eine alte Biomkarte (PNG) und ordnet jedem Feld die nächste Biomfarbe zu."""
    with open(path, 'rb') as f:
        data = f.read()
    import struct
    width, height = struct.unpack('>II', data[16:24])
    pixels = preview_plan.decode_png(data)
    grid = []
    for z in range(cell // 2, height, cell):
        row = []
        for x in range(cell // 2, width, cell):
            r, g, b, _ = pixels[z * width + x]
            best = min(COLORS, key=lambda k: min(sum((a - c * f) ** 2 for a, c in zip((r, g, b), COLORS[k])) for f in (0.6, 0.8, 1.0, 1.2)))
            row.append(best)
        grid.append(row)
    return grid


def biome_map(folder, out):
    heights = [[int(v) for v in line.split()] for line in open(os.path.join(folder, 'tartaros_heights.txt'))]
    biomes = [line.split() for line in open(os.path.join(folder, 'tartaros_biomes.txt'))]
    size = len(heights)
    scale = 4
    pix = bytearray()
    for z in range(size):
        row = bytearray()
        for x in range(size):
            h = heights[z][x]
            west = heights[z][x - 1] if x else h
            north = heights[z - 1][x] if z else h
            shade = max(0.45, min(1.6, 1.0 + (h - west + h - north) * 0.03))
            base = COLORS.get(biomes[z][x], (255, 0, 255))
            lift = 0.55 + (h - 40) / 130.0
            row.extend(bytes(int(min(255, c * shade * lift)) for c in base) * scale)
        pix.extend(bytes(row) * scale)
    save(pix, size * scale, size * scale, out)
    flat = [v for row in heights for v in row]
    print(f'Höhen {min(flat)} bis {max(flat)}, Mittel {sum(flat) // len(flat)}; unter 68: {sum(1 for v in flat if v < 68)} Felder, über 76: {sum(1 for v in flat if v > 76)} Felder')
    counts = {}
    for row in biomes:
        for b in row:
            counts[b] = counts.get(b, 0) + 1
    print('Anteile %:', {k: v * 100 // len(flat) for k, v in sorted(counts.items())})
    print(f'Zusammenhängende Biomflächen (neu): {regions(biomes)}')
    return biomes


def surface_map(folder, out):
    rows = [line.split() for line in open(os.path.join(folder, 'tartaros_surface.txt'), encoding='utf8')]
    size = len(rows)
    scale = 2
    tally = {}
    pix = bytearray()
    for row in rows:
        line = bytearray()
        for cell in row:
            biome, patch, speckle, crack = cell.split(':')
            block = tartaros_world.surface_block(biome, {'patch': float(patch), 'speckle': float(speckle), 'crack': float(crack)}) or 'minecraft:barrier'
            name = block.split(':')[1]
            tally.setdefault(biome, {}).setdefault(name, 0)
            tally[biome][name] += 1
            top, _ = preview_plan.colors(name)
            line.extend(bytes(top) * scale)
        pix.extend(bytes(line) * scale)
    save(pix, size * scale, size * scale, out)
    for biome, blocks in sorted(tally.items()):
        total = sum(blocks.values())
        share = ', '.join(f'{k} {v * 100 / total:.1f}' for k, v in sorted(blocks.items(), key=lambda kv: -kv[1]))
        print(f'{biome} ({total} Blöcke): {share}')


def main():
    argv = sys.argv[1:]
    old = None
    if '--compare' in argv:
        i = argv.index('--compare')
        old = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    folder, out = argv[0], argv[1]
    biome_map(folder, out)
    if len(argv) > 2 and os.path.exists(os.path.join(folder, 'tartaros_surface.txt')):
        surface_map(folder, argv[2])
    if old:
        print(f'Zusammenhängende Biomflächen (alt, aus {os.path.basename(old)}): {regions(png_grid(old, 4))}')


if __name__ == '__main__':
    main()
