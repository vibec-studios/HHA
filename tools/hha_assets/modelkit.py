"""Modell-Baukasten: beschreibt Knochen und Quader in Python und erzeugt daraus gemeinsam

* den Textur-Atlas (über mctex, siehe texkit) inklusive Leuchtmaske und
* die Kotlin-Modelldaten (TexturedModelData) mit passenden UV-Koordinaten.

So können Textur und Geometrie nicht auseinanderlaufen. Koordinaten wie in Minecraft: 1/16 Block, +y nach unten.
"""
import math
import os

from feather_art import uv_rect
from texkit import ASSETS, Image, hexc, mix, ramp, save, tile_noise

KOTLIN_OUT = os.path.join(ASSETS, '..', '..', '..', 'kotlin', 'dev', 'henny', 'hha', 'client', 'model', 'HhaModelData.kt')
_noise = tile_noise(64, 4, 1234, octaves=3)


def speckle(x, y, seed=0):
    return _noise((x * 7 + seed * 13) % 64, (y * 5 + seed * 29) % 64)


def rgb(*colors):
    return [hexc(c) for c in colors]


# Materialien (dunkel -> hell)
MARBLE = rgb('#6f6c78', '#a4a1ab', '#cfccd2', '#ece9e8', '#ffffff')
GOLD = rgb('#5a2c0a', '#94560f', '#c98a1c', '#efbb36', '#ffe27a', '#fff6c8')
SILVER = rgb('#39405e', '#5f6a8c', '#8f9bbb', '#bcc6e0', '#e6ecf8', '#ffffff')
SKIN = rgb('#7d4a2c', '#ad7048', '#d59a6c', '#ecba8e', '#f8d6b0')
HAIR = rgb('#5c3a10', '#8e5f18', '#c4902c', '#ecc25a', '#fbe79a')
CLOTH = rgb('#8d88a0', '#bdb9cb', '#e2dfe8', '#f6f4f6', '#ffffff')
CAPE = rgb('#141c4c', '#22357e', '#2f58b4', '#4a86dc', '#7cbcf5')
LEATHER = rgb('#2d1a0c', '#4e2f14', '#74491f', '#9a6a32')
FEATHER = rgb('#a79c86', '#d6cfbe', '#f1ecde', '#ffffff')
STONE = rgb('#3c3a42', '#5e5b65', '#85828b', '#adaab0', '#cfccd0')
SCALE_A = rgb('#0b2a22', '#14503d', '#1f8060', '#39b98a', '#7fe6c0')
SCALE_B = rgb('#1c2a6a', '#28479e', '#3d70d6', '#62a3f2', '#a4d4ff')
BONE = rgb('#4a4038', '#7a6c5e', '#b0a292', '#ddd4c6', '#f8f4ea')
SOUL = rgb('#032a36', '#086478', '#14a6c4', '#4ee0f5', '#c4fbff')
PARCEL = rgb('#3a2412', '#6b4522', '#9c6d38', '#c79a58')

FACE_LIGHT = {'top': 0.14, 'bottom': -0.22, 'front': 0.0, 'back': -0.1, 'left': -0.05, 'right': -0.05}


def mat(material, base=0.55, noise=0.08, grad=0.12, seed=0):
    """Grundmaterial: Flächenlicht, leichter Verlauf von oben nach unten und feines Korn."""
    def paint(face, x, y, w, h):
        g = (0.5 - y / max(h - 1, 1)) * grad
        n = (speckle(x, y, seed) - 0.5) * noise * 2
        return ramp(material, base + FACE_LIGHT[face] + g + n)
    return paint


def trimmed(inner, trim=None, rows=1, top=False, bottom=True):
    """Stoff oder Rüstung mit Goldkante und Mäander oben/unten."""
    trim = trim or mat(GOLD, 0.62, 0.05, 0.0)

    def paint(face, x, y, w, h):
        if face in ('front', 'back', 'left', 'right'):
            if (bottom and y >= h - rows) or (top and y < rows):
                return trim(face, x, y, w, h)
        return inner(face, x, y, w, h)
    return paint


def glowing(paint, strength=255):
    """Markiert das Ergebnis eines Materials zusätzlich als selbstleuchtend."""
    def wrapped(face, x, y, w, h):
        c = paint(face, x, y, w, h)
        if c is None:
            return None
        return (c, (c[0], c[1], c[2], strength))
    return wrapped


class Cube:
    def __init__(self, x, y, z, w, h, d, paint, dilate=0.0):
        self.x, self.y, self.z = x, y, z
        self.w, self.h, self.d = w, h, d
        self.paint = paint
        self.dilate = dilate
        self.u = self.v = 0

    @property
    def uv_size(self):
        return (2 * (self.d + self.w), self.d + self.h)


class Plume:
    """Glatte Feder als gebogener Streifen aus dem Federn-Atlas statt als Pixel-Ebene.

    Sie sitzt am Knochen bei pivot mit der Drehung rot (Grad) und wächst entlang axis:
    'y' nach unten (Flügelfedern), 'x+' / 'x-' seitlich, 'z' nach hinten.
    bend wölbt sie aus der Ebene, sweep biegt die Spitze zur Seite (beides relativ zur Länge).
    """
    ROWS = 4

    def __init__(self, pivot, rot, width, length, cell=0, axis='y', flip=False, bend=0.06, sweep=0.0, shade=(0.74, 1.0)):
        self.pivot, self.rot = pivot, rot
        self.width, self.length = width, length
        self.cell, self.axis, self.flip = cell, axis, flip
        self.bend, self.sweep, self.shade = bend, sweep, shade

    def _rotation(self):
        rx, ry, rz = (math.radians(a) for a in self.rot)
        cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
        # Gleiche Reihenfolge wie ModelPart: Z * Y * X.
        return [
            [cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx],
            [sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx],
            [-sy, cy * sx, cy * cx],
        ]

    def rows(self):
        """Liefert Reihen vom Ansatz zur Spitze: je zwei Punkte (x, y, z, u, v, Helligkeit) im Knochenraum."""
        along, across, normal = {
            'y': ((0, 1, 0), (1, 0, 0), (0, 0, 1)),
            'x+': ((1, 0, 0), (0, 1, 0), (0, 0, 1)),
            'x-': ((-1, 0, 0), (0, 1, 0), (0, 0, 1)),
            'z': ((0, 0, 1), (0, 1, 0), (1, 0, 0)),
        }[self.axis]
        m = self._rotation()
        u0, u1 = uv_rect(self.cell, self.flip)

        def world(a, c, n):
            local = [along[i] * a + across[i] * c + normal[i] * n for i in range(3)]
            return [sum(m[r][i] * local[i] for i in range(3)) + self.pivot[r] for r in range(3)]

        out = []
        for row in range(self.ROWS):
            t = row / (self.ROWS - 1)
            a = self.length * t
            n = self.bend * self.length * t * t
            c = self.sweep * self.length * t * t
            half = self.width / 2.0
            shade = self.shade[0] + (self.shade[1] - self.shade[0]) * t
            out.append((
                tuple(world(a, c - half, n)) + (u0, 1.0 - t, shade),
                tuple(world(a, c + half, n)) + (u1, 1.0 - t, shade),
            ))
        return out


class Part:
    def __init__(self, name, pivot=(0, 0, 0), rot=(0, 0, 0), cubes=(), children=(), plumes=()):
        self.name = name
        self.pivot = pivot
        self.rot = rot
        self.cubes = list(cubes)
        self.children = list(children)
        self.plumes = list(plumes)

    def add(self, child):
        self.children.append(child)
        return child

    def walk(self):
        yield self
        for c in self.children:
            yield from c.walk()


class Model:
    def __init__(self, name, tex_w, tex_h, parts, texture=None):
        self.name = name
        self.tex_w, self.tex_h = tex_w, tex_h
        self.parts = parts
        self.texture = texture or name

    def cubes(self):
        for p in self.parts:
            for part in p.walk():
                yield from part.cubes

    def pack(self):
        cubes = sorted(self.cubes(), key=lambda c: (-c.uv_size[1], -c.uv_size[0]))
        x = y = row = 0
        for c in cubes:
            cw, ch = c.uv_size
            if x + cw > self.tex_w:
                x = 0
                y += row + 1
                row = 0
            if y + ch > self.tex_h:
                raise SystemExit(f'Atlas {self.name} zu klein ({self.tex_w}x{self.tex_h})')
            c.u, c.v = x, y
            x += cw + 1
            row = max(row, ch)
        print(f'{self.name}: {len(cubes)} Quader, Atlas belegt bis y={y + row}/{self.tex_h}')

    def paint(self):
        albedo = Image(self.tex_w, self.tex_h)
        glow = Image(self.tex_w, self.tex_h)
        any_glow = False
        for c in self.cubes():
            u, v, w, h, d = c.u, c.v, c.w, c.h, c.d
            faces = [
                ('top', u + d, v, w, d),
                ('bottom', u + d + w, v, w, d),
                ('right', u, v + d, d, h),
                ('front', u + d, v + d, w, h),
                ('left', u + d + w, v + d, d, h),
                ('back', u + d + w + d, v + d, w, h),
            ]
            for name, fu, fv, fw, fh in faces:
                for iy in range(fh):
                    for ix in range(fw):
                        res = c.paint(name, ix, iy, fw, fh)
                        if res is None:
                            continue
                        if isinstance(res[0], tuple):
                            col, g = res
                            albedo.set(fu + ix, fv + iy, col)
                            if g is not None:
                                glow.set(fu + ix, fv + iy, g)
                                any_glow = True
                        else:
                            albedo.set(fu + ix, fv + iy, res)
        save(albedo, f'textures/entity/boss/{self.texture}.png')
        if any_glow:
            save(glow, f'textures/entity/boss/{self.texture}_glow.png')
        return albedo, glow

    def kotlin(self):
        lines = [f'    fun {self.name}(): TexturedModelData {{', '        val data = ModelData()', '        val root = data.root']
        counter = [0]

        def num(v):
            return f'{float(v):.4f}f'

        def emit(part, parent):
            idx = counter[0]
            counter[0] += 1
            var = f'p{idx}'
            builder = 'ModelPartBuilder.create()'
            for c in part.cubes:
                builder += f'.uv({c.u}, {c.v}).cuboid({num(c.x)}, {num(c.y)}, {num(c.z)}, {num(c.w)}, {num(c.h)}, {num(c.d)}, Dilation({num(c.dilate)}))'
            rx, ry, rz = (math.radians(a) for a in part.rot)
            px, py, pz = part.pivot
            lines.append(f'        val {var} = {parent}.addChild("{part.name}", {builder}, ModelTransform.of({num(px)}, {num(py)}, {num(pz)}, {num(rx)}, {num(ry)}, {num(rz)}))')
            for ch in part.children:
                emit(ch, var)

        for p in self.parts:
            emit(p, 'root')
        lines.append(f'        return TexturedModelData.of(data, {self.tex_w}, {self.tex_h})')
        lines.append('    }')
        return '\n'.join(lines)


def plume_tables(models):
    """Eine Kotlin-Tabelle je Modell: Knochenpfad plus Vertex-Daten aller Federn dieses Knochens."""
    helpers, tables = [], []
    counter = [0]

    def num(v):
        return f'{float(v):.3f}f'

    for model in models:
        entries = []

        def visit(part, path):
            here = path + [part.name]
            if part.plumes:
                values = []
                for plume in part.plumes:
                    for left, right in plume.rows():
                        values.extend(left)
                        values.extend(right)
                idx = counter[0]
                counter[0] += 1
                # Eigene Funktion je Knochen, sonst sprengen große Tabellen die Methodengrenze der JVM.
                helpers.append(f'    private fun plumes{idx}() = floatArrayOf({", ".join(num(v) for v in values)})')
                names = ', '.join(f'"{n}"' for n in here)
                entries.append(f'        FeatherBone(arrayOf({names}), plumes{idx}()),')
            for child in part.children:
                visit(child, here)

        for part in model.parts:
            visit(part, [])
        if entries:
            tables.append(f'    fun {model.name}Feathers(): List<FeatherBone> = listOf(\n' + '\n'.join(entries) + '\n    )')
    return '\n\n'.join(tables + helpers)


def write_kotlin(models):
    body = '\n\n'.join(m.kotlin() for m in models) + '\n\n' + plume_tables(models)
    src = f'''package dev.henny.hha.client.model

import net.minecraft.client.model.Dilation
import net.minecraft.client.model.ModelData
import net.minecraft.client.model.ModelPartBuilder
import net.minecraft.client.model.ModelTransform
import net.minecraft.client.model.TexturedModelData

/**
 * GENERIERT von tools/hha_assets/boss_models.py — nicht von Hand ändern.
 * UVs passen exakt zu den Texturen unter textures/entity/boss/.
 */
object HhaModelData {{

{body}
}}
'''
    path = os.path.abspath(KOTLIN_OUT)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf8', newline='\n') as f:
        f.write(src)
    print('Kotlin:', path)


__all__ = [name for name in dir() if not name.startswith('_')]
