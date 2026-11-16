"""Exportiert die Boss-Modelle für die Website (site/): Geometrie, Posen und Texturen als JSON + PNG.

Aufruf (aus dem Projektordner):
    python tools/hha_assets/export_web.py [ausgabeordner]     # Standard: site/assets

Je Modell entsteht models/<name>.json mit
    texW, texH, texture        Atlasgröße und Texturname (textures/entity/boss/<texture>.png)
    parts                      Knochenbaum: n Name, p Pivot, r Grundrotation (Radiant, Reihenfolge Z*Y*X wie ModelPart),
                               c Quader [x, y, z, w, h, d, dilation, u, v], f Federstreifen (wie Feathers.kt), k Kinder
    poses                      Schlüsselposen aus dem Modellmodul: Knochen -> [pitch, yaw, roll(, dx, dy, dz)] oder null
Die Texturen werden unverändert aus src/main/resources kopiert; die UVs stimmen, weil pack() deterministisch ist.
"""
import importlib
import json
import math
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)

import boss_models  # noqa: E402

TEX = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'hha', 'textures', 'entity', 'boss')

# Posen, die nur Kamerahilfen der Vorschau sind.
SKIP_POSES = {'top_view', 'spokes_top', 'wobble_top'}


def r4(v):
    return round(float(v), 4)


def export_part(part):
    out = {
        'n': part.name,
        'p': [r4(v) for v in part.pivot],
        'r': [r4(math.radians(a)) for a in part.rot],
    }
    if part.cubes:
        out['c'] = [[r4(c.x), r4(c.y), r4(c.z), r4(c.w), r4(c.h), r4(c.d), r4(c.dilate), c.u, c.v] for c in part.cubes]
    if part.plumes:
        values = []
        for plume in part.plumes:
            for left, right in plume.rows():
                values.extend(r4(v) for v in left)
                values.extend(r4(v) for v in right)
        out['f'] = values
    if part.children:
        out['k'] = [export_part(ch) for ch in part.children]
    return out


def export_poses(module):
    poses = getattr(module, 'POSES', None) or {}
    result = {}
    for name in list(poses.keys()):
        if name in SKIP_POSES:
            continue
        pose = poses[name]
        result[name] = {bone: (None if v is None else [r4(x) for x in v]) for bone, v in pose.items()}
    return result


def main():
    out_dir = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, 'site', 'assets'))
    models_dir = os.path.join(out_dir, 'models')
    tex_dir = os.path.join(out_dir, 'tex')
    os.makedirs(models_dir, exist_ok=True)
    os.makedirs(tex_dir, exist_ok=True)
    textures = {'feathers'}
    for name, (module_name, _) in boss_models.REGISTRY.items():
        module = importlib.import_module(module_name)
        model = boss_models.build(name)
        model.pack()
        data = {
            'name': name,
            'texture': model.texture,
            'texW': model.tex_w,
            'texH': model.tex_h,
            'parts': [export_part(p) for p in model.parts],
            'poses': export_poses(module),
        }
        with open(os.path.join(models_dir, f'{name}.json'), 'w', encoding='utf8', newline='\n') as f:
            json.dump(data, f, separators=(',', ':'))
        textures.add(model.texture)
    textures |= {'hermes_shade', 'serpent_shade'}
    for tex in sorted(textures):
        for suffix in ('', '_glow'):
            src = os.path.join(TEX, f'{tex}{suffix}.png')
            if os.path.exists(src):
                shutil.copyfile(src, os.path.join(tex_dir, f'{tex}{suffix}.png'))
    print('Export:', out_dir)


if __name__ == '__main__':
    main()
