"""Kleine Pixel-Toolchain: Python rechnet Designs, mctex schreibt und validiert die PNGs.

`Image` hält RGBA-Pixel; `flush()` übersetzt alles in ein `mctex batch`-Manifest. Zusammenhängende
Spans gleicher Farbe werden als `rect` geschrieben, damit auch große Texturen schnell durchlaufen.
"""
import json
import os
import random
import shutil
import subprocess
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
ASSETS = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'hha')
_jobs = []


def hexc(h, a=255):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (a,)


def lerp(a, b, t):
    return a + (b - a) * t


def mix(c1, c2, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(lerp(c1[i], c2[i], t))) for i in range(4))


def ramp(colors, t):
    """Farbverlauf über eine Liste; t in 0..1."""
    t = max(0.0, min(0.9999, t)) * (len(colors) - 1)
    i = int(t)
    return mix(colors[i], colors[i + 1], t - i)


class Image:
    def __init__(self, w, h, fill=(0, 0, 0, 0)):
        self.w, self.h = w, h
        self.px = [fill] * (w * h)

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y * self.w + x] = c

    def get(self, x, y):
        return self.px[y * self.w + x]

    def fn(self, f):
        for y in range(self.h):
            for x in range(self.w):
                self.px[y * self.w + x] = f(x, y)
        return self

    def ops(self):
        ops = []
        for y in range(self.h):
            x = 0
            while x < self.w:
                c = self.px[y * self.w + x]
                run = 1
                while x + run < self.w and self.px[y * self.w + x + run] == c:
                    run += 1
                if c[3] > 0:
                    ops.append({'op': 'rect', 'x': x, 'y': y, 'width': run, 'height': 1,
                                'color': '#%02x%02x%02x%02x' % c})
                x += run
        return ops


def save(img, rel):
    """rel relativ zu assets/hha, z. B. textures/vfx/noise.png."""
    path = os.path.join(ASSETS, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    _jobs.append((img, path))


def run_mctex(*args):
    exe = shutil.which('mctex.cmd') or shutil.which('mctex')
    r = subprocess.run([exe, *args], capture_output=True, text=True, cwd=ROOT)
    if r.returncode != 0:
        raise SystemExit(f'mctex {args[:2]} fehlgeschlagen: {r.stderr.strip()}\n{r.stdout.strip()[:800]}')
    return r.stdout


def flush():
    tmp = tempfile.mkdtemp(prefix='hha_tex_', dir=os.path.join(ROOT, 'tmp'))
    try:
        jobs = []
        for img, path in _jobs:
            ops = [{'op': 'fill', 'color': '#00000000'}] + img.ops()
            jobs.append({'id': os.path.basename(path), 'source': 'preset:stone',
                         'options': {'width': img.w, 'height': img.h}, 'output': path, 'operations': ops})
        manifest = os.path.join(tmp, 'manifest.json')
        with open(manifest, 'w') as f:
            json.dump({'concurrency': 8, 'jobs': jobs}, f)
        run_mctex('batch', manifest, '--json', '--fail-fast')
        print(f'mctex batch: {len(jobs)} Texturen geschrieben')
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
        _jobs.clear()


def tile_noise(size, cells, seed, octaves=4):
    """Nahtlos kachelndes Value-Noise, liefert sample(x, y) -> 0..1."""
    rnd = random.Random(seed)
    layers = []
    for o in range(octaves):
        n = cells << o
        layers.append((n, [rnd.random() for _ in range(n * n)]))

    def smooth(t):
        return t * t * (3 - 2 * t)

    def sample(x, y):
        v, amp, total = 0.0, 1.0, 0.0
        for n, g in layers:
            fx, fy = x / size * n, y / size * n
            ix, iy = int(fx), int(fy)
            tx, ty = smooth(fx - ix), smooth(fy - iy)
            a = g[iy % n * n + ix % n]
            b = g[iy % n * n + (ix + 1) % n]
            c = g[(iy + 1) % n * n + ix % n]
            d = g[(iy + 1) % n * n + (ix + 1) % n]
            v += amp * lerp(lerp(a, b, tx), lerp(c, d, tx), ty)
            total += amp
            amp *= 0.5
        return v / total

    return sample
