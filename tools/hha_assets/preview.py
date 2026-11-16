"""Vorschau für jedes Boss-Modell ohne Spielstart: Posen x Ansichten als ein PNG-Kontaktbogen.

Aufruf (aus dem Projektordner):
    python tools/hha_assets/preview.py <modell> <ausgabe.png> [--poses idle,swing] [--views 0,90,180,35] [--size 360]
                                       [--silhouette | --glow]

<modell> ist ein Name aus boss_models.py --list. Posen kommen aus dem Modul des Modells: ein Dict
`POSES = {'idle': {'knochen': (pitch, yaw, roll), ...}, ...}` in Radiant, wie ModelPart.pitch/yaw/roll. Fehlt ein
Knochen in einer Pose, gilt seine Grundrotation aus dem Modell. Ein Wert mit sechs Zahlen
(pitch, yaw, roll, dx, dy, dz) verschiebt den Knochen zusätzlich um dx/dy/dz Einheiten (wie ModelPart.originX/Y/Z im
Elternraum); None blendet den Knochen samt Kindern aus. Ohne --poses werden alle Posen gezeigt (oder nur die
Grundhaltung 'rest', falls das Modul keine hat). Ansichten sind Kamerawinkel in Grad um die Hochachse: 0 = von vorn,
180 = von hinten, 90 = von der Seite. Leuchtende Texel (Leuchtmaske) werden heller gezeichnet, so wie im Spiel.
--silhouette zeichnet schwarz auf weiß (Schattenriss-Prüfung), --glow nur die Leuchtmaske auf Schwarz.

Ausgabe: Gesamthöhe und Körperhöhe in der Grundhaltung (Körperhöhe ohne die Zubehör-Knochen aus
`HEIGHT_EXCLUDE` des Modellmoduls, z. B. Hut, erhobene Waffe, Ast oder Stachel), Quaderzahl, Atlasgröße, Leuchtanteil (Anteil leuchtender Oberflächentexel),
goldfarbene Leuchttexel (sollen 0 sein), Zahl dünner Quader (1 dick und mindestens 4 lang) und 0-dicker Ebenen.

Die Skalierung passt sich automatisch an, damit das Modell jedes Feld füllt; alle Felder eines Bogens teilen dieselbe
Skalierung, damit Größen vergleichbar bleiben. Ein Gitter am Boden zeigt Blockgrößen (16 Einheiten = 1 Block, vor dem
SCALE-Faktor des Renderers).
"""
import importlib
import math
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass
sys.path.insert(0, HERE)

import boss_models  # noqa: E402
from preview_model import apply, mat_mul, plume_texels, rot_zyx  # noqa: E402

BG_TOP, BG_BOTTOM = (30, 34, 46), (70, 76, 92)


def texels_glow(cube):
    """Wie preview_model.texels, liefert aber zusätzlich, ob der Texel leuchtet."""
    x0, y0, z0 = cube.x, cube.y, cube.z
    w, h, d = cube.w, cube.h, cube.d
    faces = {
        'front': (w, h, lambda i, j: (x0 + i + 0.5, y0 + j + 0.5, z0)),
        'back': (w, h, lambda i, j: (x0 + w - i - 0.5, y0 + j + 0.5, z0 + d)),
        'right': (d, h, lambda i, j: (x0, y0 + j + 0.5, z0 + d - i - 0.5)),
        'left': (d, h, lambda i, j: (x0 + w, y0 + j + 0.5, z0 + i + 0.5)),
        'top': (w, d, lambda i, j: (x0 + i + 0.5, y0, z0 + j + 0.5)),
        'bottom': (w, d, lambda i, j: (x0 + i + 0.5, y0 + h, z0 + j + 0.5)),
    }
    for name, (fw, fh, position) in faces.items():
        for j in range(fh):
            for i in range(fw):
                res = cube.paint(name, i, j, fw, fh)
                if res is None:
                    continue
                if isinstance(res[0], tuple):
                    color, glow = res
                    yield position(i, j), color, glow
                else:
                    yield position(i, j), res, None


def collect(part, pose, parent_rot, parent_pos, out):
    """Wie preview_model.collect, aber Posen dürfen 6 Zahlen haben (Drehung + Versatz) oder None (ausblenden)."""
    value = pose.get(part.name, ...)
    if value is None:
        return
    pivot = part.pivot
    if value is ...:
        rot_val = tuple(math.radians(a) for a in part.rot)
    else:
        rot_val = tuple(value[:3])
        if len(value) >= 6:
            pivot = (pivot[0] + value[3], pivot[1] + value[4], pivot[2] + value[5])
    origin = apply(parent_rot, pivot)
    pos = (parent_pos[0] + origin[0], parent_pos[1] + origin[1], parent_pos[2] + origin[2])
    rot = mat_mul(parent_rot, rot_zyx(*rot_val))
    for cube in part.cubes:
        out.append((cube, rot, pos))
    for plume in part.plumes:
        out.append((plume, rot, pos))
    for child in part.children:
        collect(child, pose, rot, pos, out)


def world_points(model, pose, mode='color'):
    """mode: 'color' (Farbe, Leuchttexel heller), 'silhouette' (schwarz), 'glow' (nur Leuchtmaske)."""
    items = []
    for part in model.parts:
        collect(part, pose, [[1, 0, 0], [0, 1, 0], [0, 0, 1]], (0, 0, 0), items)
    pts = []
    for cube, rot, pos in items:
        if hasattr(cube, 'rows'):
            source = ((p, c, None) for p, c in plume_texels(cube))
        else:
            source = texels_glow(cube)
        for local, color, glow in source:
            if len(color) > 3 and color[3] == 0:
                continue
            wv = apply(rot, local)
            lit = glow is not None and glow[3] > 0
            if mode == 'silhouette':
                rgb = (0, 0, 0)
            elif mode == 'glow':
                if lit:
                    k = glow[3] / 255.0
                    rgb = tuple(int(g * k) for g in glow[:3])
                else:
                    rgb = (14, 14, 18)
            else:
                rgb = color[:3]
                if lit:
                    k = glow[3] / 255.0 * 0.6
                    rgb = tuple(min(255, int(c + (g - c) * k + 40 * k)) for c, g in zip(rgb, glow[:3]))
            pts.append((wv[0] + pos[0], wv[1] + pos[1], wv[2] + pos[2], rgb))
    return pts


def stats(model):
    """Quaderzahl, Atlas, Leuchtanteil (Anteil leuchtender Oberflächentexel), goldene Leuchttexel, dünne Quader."""
    cubes = list(model.cubes())
    total = lit = gold = 0
    for cube in cubes:
        for _, color, glow in texels_glow(cube):
            if len(color) > 3 and color[3] == 0:
                continue
            total += 1
            if glow is not None and glow[3] > 0:
                lit += 1
                r, g, b = glow[:3]
                if r > 190 and 120 < g < 235 and b < 140 and r - b > 90:
                    gold += 1
    thin = sum(1 for c in cubes if min(c.w, c.h, c.d) == 1 and min(c.w, c.h, c.d) > 0 and max(c.w, c.h, c.d) >= 4)
    planes = sum(1 for c in cubes if min(c.w, c.h, c.d) == 0)
    return {
        'quader': len(cubes), 'atlas': f'{model.tex_w}x{model.tex_h}',
        'leuchtanteil': 100.0 * lit / max(total, 1), 'gold_leuchttexel': gold,
        'duenne_quader(1 dick, >=4 lang)': thin, 'ebenen(0 dick)': planes,
    }


def project(points, yaw):
    cv, sv = math.cos(yaw), math.sin(yaw)
    return [(x * cv + z * sv, y, -x * sv + z * cv, c) for x, y, z, c in points]


def draw(points, size, scale, cx, cy, ground_y, mode='color'):
    pix = []
    top, bottom = {'silhouette': ((255, 255, 255), (255, 255, 255)), 'glow': ((0, 0, 0), (0, 0, 0))}.get(mode, (BG_TOP, BG_BOTTOM))
    for y in range(size):
        t = y / max(size - 1, 1)
        pix.extend([tuple(int(a + (b - a) * t) for a, b in zip(top, bottom))] * size)
    # Bodenlinie und Blockraster (16 Einheiten).
    gy = int(size / 2 + (ground_y - cy) * scale)
    if 0 <= gy < size:
        for x in range(size):
            pix[gy * size + x] = (120, 128, 140)
        step = 16 * scale
        if step >= 6:
            k = 0
            while True:
                for sign in (1, -1):
                    gx = int(size / 2 + (sign * k * 16 - cx) * scale)
                    if 0 <= gx < size:
                        for dy in range(-3, 4):
                            if 0 <= gy + dy < size:
                                pix[(gy + dy) * size + gx] = (150, 158, 170)
                k += 1
                if k * step > size:
                    break
    depth = [1e9] * (size * size)
    radius = max(1, int(math.ceil(scale * 0.55)))
    for vx, vy, vz, color in points:
        sx = int(size / 2 + (vx - cx) * scale)
        sy = int(size / 2 + (vy - cy) * scale)
        for dy in range(-radius, radius):
            py = sy + dy
            if not 0 <= py < size:
                continue
            row = py * size
            for dx in range(-radius, radius):
                px = sx + dx
                if 0 <= px < size and vz < depth[row + px]:
                    depth[row + px] = vz
                    pix[row + px] = color
    return pix


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        raise SystemExit(1)
    name, out = argv[0], argv[1]

    def opt(key, default):
        return argv[argv.index(key) + 1] if key in argv else default

    model = boss_models.build(name)
    module = importlib.import_module(boss_models.REGISTRY[name][0])
    all_poses = getattr(module, 'POSES', None) or {'rest': {}}
    wanted = [p for p in opt('--poses', ','.join(all_poses)).split(',') if p]
    for p in wanted:
        if p not in all_poses and p != 'rest':
            raise SystemExit(f'Pose "{p}" fehlt in {module.__name__}.POSES (vorhanden: {", ".join(all_poses)})')
    views = [math.radians(float(v)) for v in opt('--views', '0,35,90,180').split(',')]
    size = int(opt('--size', '360'))
    mode = 'silhouette' if '--silhouette' in argv else 'glow' if '--glow' in argv else 'color'

    sheets = []
    for p in wanted:
        pts = world_points(model, all_poses.get(p, {}), mode)
        sheets.append([project(pts, v) for v in views])
    # Gemeinsame Skalierung über alle Felder.
    xs = [q[0] for row in sheets for panel in row for q in panel]
    ys = [q[1] for row in sheets for panel in row for q in panel]
    if not xs:
        raise SystemExit('Modell hat keine sichtbaren Texel')
    span = max(max(xs) - min(xs), max(ys) - min(ys), 1.0)
    scale = size * 0.86 / span
    cx, cy = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2
    ground = max(ys)

    cols, rows = len(views), len(sheets)
    W, H = size * cols, size * rows
    canvas = bytearray(W * H * 3)
    for r, row in enumerate(sheets):
        for c, panel in enumerate(row):
            pix = draw(panel, size, scale, cx, cy, ground, mode)
            for y in range(size):
                base = ((r * size + y) * W + c * size) * 3
                line = bytearray()
                for px in pix[y * size:(y + 1) * size]:
                    line.extend(px)
                canvas[base:base + size * 3] = line
            # Trennlinien
            for y in range(size):
                i = ((r * size + y) * W + c * size) * 3
                canvas[i:i + 3] = b'\x10\x10\x14'
        for x in range(W):
            i = ((r * size) * W + x) * 3
            canvas[i:i + 3] = b'\x10\x10\x14'
    tmp = tempfile.mktemp(suffix='.ppm')
    with open(tmp, 'wb') as f:
        f.write(f'P6 {W} {H} 255\n'.encode())
        f.write(bytes(canvas))
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    subprocess.run([shutil.which('ffmpeg'), '-hide_banner', '-loglevel', 'error', '-y', '-i', tmp, out], check=True)
    os.remove(tmp)
    rest = world_points(model, {}, 'silhouette')
    height = max(q[1] for q in rest) - min(q[1] for q in rest)
    exclude = tuple(getattr(module, 'HEIGHT_EXCLUDE', ()))
    body = world_points(model, {name: None for name in exclude}, 'silhouette') if exclude else rest
    body_height = max(q[1] for q in rest) - min(q[1] for q in body)
    st = stats(model)
    print(f'{out}: Posen {", ".join(wanted)} x Ansichten {opt("--views", "0,35,90,180")} ({mode})')
    print(f'  Gesamthöhe in Grundhaltung (alles): {height:.1f} Einheiten = {height / 16:.2f} Blöcke vor Renderer-Faktor')
    if exclude:
        print(f'  Körperhöhe Fuß bis Scheitel (ohne {", ".join(exclude)}): {body_height:.1f} Einheiten')
    print('  ' + ', '.join(f'{k}: {v:.1f} %' if k == 'leuchtanteil' else f'{k}: {v}' for k, v in st.items()))


if __name__ == '__main__':
    main(sys.argv[1:])
