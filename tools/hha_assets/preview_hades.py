"""Rendert Hades ohne Spielstart in den wichtigsten Posen. Aufruf: python preview_hades.py <ausgabe.png> [pose ...]"""
import math
import os
import shutil
import subprocess
import sys
import tempfile

import preview_model as pm
from hades_model import hades

CAPE = {f'cape_{i}_{s}': (0.10 + s * 0.02 + 0.02 * math.sin(i * 1.7 + s), 0, -0.012 * (i - 3)) for i in range(7) for s in range(5)}
CAPE.update({f'cape_{i}_0': (0.2, 0, -0.03 * (i - 3)) for i in range(7)})
HIDE_WINGS = {f'wing_{s}_{i}': None for s in 'rl' for i in range(3)}

POSES = {
    'idle': {
        **CAPE,
        'arm_r': (-0.22, 0, 0.38), 'fore_r': (-0.72, 0, 0), 'bident': (0.72, 0, -0.34),
        'arm_l': (0.12, 0, -0.22), 'fore_l': (-0.28, 0, 0),
    },
    'cast': {
        **CAPE,
        'chest': (-0.1, 0, 0), 'head': (-0.15, 0, 0),
        'arm_r': (-0.5, 0, 0.45), 'fore_r': (-0.7, 0, 0), 'bident': (0.7, 0, -0.34),
        'arm_l': (-1.55, 0, -0.55), 'fore_l': (-0.35, 0, 0),
    },
    'slam': {
        **CAPE,
        'torso': (0.25, 0, 0), 'chest': (0.25, 0, 0), 'head': (-0.2, 0, 0),
        'arm_r': (-1.5, 0, 0.32), 'fore_r': (-0.35, 0, 0), 'bident': (1.3, 0, -0.12),
        'arm_l': (-1.0, 0, -0.35), 'fore_l': (-0.6, 0, 0),
        'thigh_r': (-0.8, 0, 0), 'shin_r': (0.7, 0, 0), 'thigh_l': (0.45, 0, 0), 'shin_l': (0.45, 0, 0),
    },
    'wings': {
        **CAPE,
        'arm_r': (-0.22, 0, 0.38), 'fore_r': (-0.72, 0, 0), 'bident': (0.72, 0, -0.34),
        'arm_l': (-0.4, 0, -0.5), 'fore_l': (-0.5, 0, 0),
    },
}


def strip_hidden(model, hidden):
    def visit(part):
        part.children = [c for c in part.children if c.name not in hidden]
        for c in part.children:
            visit(c)
    for p in model.parts:
        visit(p)


def main():
    out = sys.argv[1]
    names = sys.argv[2:] or ['idle']
    rows = []
    for name in names:
        model = hades()
        if name != 'wings':
            strip_hidden(model, HIDE_WINGS)
        rows.append([pm.render(model, POSES[name], yaw, size=640, scale=4.9, center_y=-28) for yaw in (0.0, math.pi, 0.8)])
    size = 640
    tmp = tempfile.mktemp(suffix='.ppm')
    with open(tmp, 'wb') as f:
        f.write(f'P6 {size * 3} {size * len(rows)} 255\n'.encode())
        for panels in rows:
            for y in range(size):
                row = bytearray()
                for pix, _ in panels:
                    for x in range(size):
                        row.extend(pix[y * size + x])
                f.write(row)
    subprocess.run([shutil.which('ffmpeg'), '-hide_banner', '-loglevel', 'error', '-y', '-i', tmp, out], check=True)
    os.remove(tmp)


if __name__ == '__main__':
    main()
