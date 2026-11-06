"""Rendert ein Boss-Modell ohne Spielstart als PNG (Vorder-, Rück- und Schrägansicht in einer Pose).

Aufruf: python preview_model.py <ausgabe.png> [idle|open]
"""
import math
import os
import shutil
import subprocess
import sys
import tempfile

from feather_art import texel
from hermes_model import hermes

POSES = {
    'idle': {
        'wing_back_r': (0, 0.55, 0.28), 'wing_fore_r': (0, 0.56, -0.16), 'wing_hand_r': (0, 0.58, 0.09),
        'wing_back_l': (0, -0.55, -0.28), 'wing_fore_l': (0, -0.56, 0.16), 'wing_hand_l': (0, -0.58, -0.09),
        'arm_r': (-0.28, 0, 0.82), 'fore_r': (-0.62, 0, 0), 'caduceus': (0.55, 0, -1.2),
        'arm_l': (0.12, 0, -0.32), 'fore_l': (-0.25, 0, 0),
        'leg_r': (0.12, 0, 0.04), 'shin_r': (0.34, 0, 0), 'leg_l': (-0.14, 0, -0.04), 'shin_l': (0.42, 0, 0),
        'cape1': (0.22, 0, 0), 'cape2': (0.1, 0, 0), 'cape3': (0.12, 0, 0),
    },
    'open': {
        'wing_back_r': (0, 0.08, 0.45), 'wing_fore_r': (0, 0, 0), 'wing_hand_r': (0, 0, 0),
        'wing_back_l': (0, -0.08, -0.45), 'wing_fore_l': (0, 0, 0), 'wing_hand_l': (0, 0, 0),
        'arm_r': (-0.2, 0, 1.3), 'fore_r': (-0.15, 0, 0), 'caduceus': (0, 0, -1.7),
        'arm_l': (-0.2, 0, -1.3), 'fore_l': (-0.15, 0, 0),
        'leg_r': (0.05, 0, 0), 'shin_r': (0.15, 0, 0), 'leg_l': (0.05, 0, 0), 'shin_l': (0.15, 0, 0),
        'cape1': (0.5, 0, 0), 'cape2': (0.1, 0, 0), 'cape3': (0.12, 0, 0),
    },
}


def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def rot_zyx(pitch, yaw, roll):
    cx, sx = math.cos(pitch), math.sin(pitch)
    cy, sy = math.cos(yaw), math.sin(yaw)
    cz, sz = math.cos(roll), math.sin(roll)
    rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    return mat_mul(rz, mat_mul(ry, rx))


def apply(m, v):
    return tuple(sum(m[i][j] * v[j] for j in range(3)) for i in range(3))


def collect(part, pose, parent_rot, parent_pos, out):
    origin = apply(parent_rot, part.pivot)
    pos = (parent_pos[0] + origin[0], parent_pos[1] + origin[1], parent_pos[2] + origin[2])
    base = tuple(math.radians(a) for a in part.rot)
    pitch, yaw, roll = pose.get(part.name, base)
    rot = mat_mul(parent_rot, rot_zyx(pitch, yaw, roll))
    for cube in part.cubes:
        out.append((cube, rot, pos))
    for plume in part.plumes:
        out.append((plume, rot, pos))
    for child in part.children:
        collect(child, pose, rot, pos, out)


def texels(cube):
    """Liefert je Fläche und Texel die lokale Mittelposition und die Farbe aus dem Maler."""
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
                color = res[0] if isinstance(res[0], tuple) else res
                yield position(i, j), color


def plume_texels(plume, step=0.3):
    """Tastet einen Federstreifen fein ab; so sieht die Vorschau die glatte Feder statt einzelner Pixel."""
    rows = plume.rows()
    across = max(int(plume.width / step), 2)
    for segment in range(len(rows) - 1):
        (a0, a1), (b0, b1) = rows[segment], rows[segment + 1]
        length = math.dist(a0[:3], b0[:3])
        for j in range(max(int(length / step), 1)):
            t = (j + 0.5) / max(int(length / step), 1)
            for i in range(across):
                k = (i + 0.5) / across
                u = 1.0 - k if plume.flip else k
                color = texel(plume.cell, u, (segment + t) / (len(rows) - 1))
                if color is None:
                    continue
                shade = a0[5] + (b0[5] - a0[5]) * t
                pos = tuple(
                    (a0[n] + (a1[n] - a0[n]) * k) * (1 - t) + (b0[n] + (b1[n] - b0[n]) * k) * t for n in range(3)
                )
                yield pos, tuple(int(c * shade) for c in color[:3])


def render(model, pose, view_yaw, size=520, scale=5.0, center_y=-16):
    pix = [(58, 110, 165)] * (size * size)
    depth = [1e9] * (size * size)
    items = []
    for part in model.parts:
        collect(part, pose, [[1, 0, 0], [0, 1, 0], [0, 0, 1]], (0, 0, 0), items)
    cv, sv = math.cos(view_yaw), math.sin(view_yaw)
    radius = int(scale * 0.5) + 1
    for cube, rot, pos in items:
        points = plume_texels(cube) if hasattr(cube, 'rows') else texels(cube)
        for local, color in points:
            world = apply(rot, local)
            x, y, z = world[0] + pos[0], world[1] + pos[1], world[2] + pos[2]
            vx = x * cv + z * sv
            vz = -x * sv + z * cv
            sx = int(size / 2 + vx * scale)
            sy = int(size / 2 + (y - center_y) * scale)
            for dy in range(-radius, radius):
                for dx in range(-radius, radius):
                    px, py = sx + dx, sy + dy
                    if 0 <= px < size and 0 <= py < size:
                        index = py * size + px
                        if vz < depth[index]:
                            depth[index] = vz
                            pix[index] = color[:3]
    return pix, size


def main():
    out = sys.argv[1]
    name = sys.argv[2] if len(sys.argv) > 2 else 'idle'
    model = hermes()
    panels = [render(model, POSES[name], yaw) for yaw in (0.0, math.pi, 0.75)]
    size = panels[0][1]
    tmp = tempfile.mktemp(suffix='.ppm')
    with open(tmp, 'wb') as f:
        f.write(f'P6 {size * 3} {size} 255\n'.encode())
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
