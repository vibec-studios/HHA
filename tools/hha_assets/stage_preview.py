"""Vorschau eines Bühnen-Skripts ohne Spiel (Überarbeitung 3, P05).

Aufruf (aus dem Projektordner):
    python tools/hha_assets/gradle_locked.py dumpStages          # einmal: alle Skripte als JSON exportieren
    python tools/hha_assets/stage_preview.py stage.demo          # ein Skript → tmp/overhaul3/previews/stage/stage.demo.png
    python tools/hha_assets/stage_preview.py --all               # alle Skripte
    python tools/hha_assets/stage_preview.py --list              # IDs im Export

Zeichnet oben die Kamerabahn in der Draufsicht (Anker-Koordinaten: „vor" nach oben, „rechts" nach rechts, Raster
1 Block, Farbe je Einstellung, Punkte alle 10 Ticks, Blicklinien alle 20 Ticks), daneben die Kamerahöhe über die Zeit,
unten die Spuren als Zeitstreifen (Einstellungen, Zeilen, Karten, Blenden, Effekte, Posen, Puppen, Marken).

Annahmen der Vorschau (das Spiel löst die Schlüssel live auf): Anker = Ursprung mit Blick yaw 0; Darsteller 0 steht am
Anker (Spieler, wie `/hha stage play`), Darsteller i > 0 steht i·4 Blöcke vor dem Anker; die Gruppe ist Darsteller 0;
Augen = Anker + 1,62 hoch, Blick 16 Blöcke nach vorn. Puppen laufen ihre Wege wie am Client (Catmull-Rom, Trapez).
Folge-Blick, Handkamera und Kollisionsschutz fehlen in der Vorschau.

Ausgabe als SVG, in ein PNG gewandelt per Headless-Chrome/Edge (wie `glcheck.py`); ohne Browser bleibt das SVG.
"""
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'tmp', 'overhaul3', 'previews', 'stage')
DUMP = os.path.join(OUT, 'stages.json')
CHROMES = [r'C:\Program Files\Google\Chrome\Application\chrome.exe',
           r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe']

EYE_HEIGHT = 1.62
SPLINE_SAMPLES = 16
SHOT_COLORS = ['#ffd75e', '#3fd8ff', '#8b6cff', '#3fe0c5', '#ff8a3d', '#e0483c', '#6be38c', '#c9b8ff']
CUE_ROWS = [
    ('Zeilen', ('Caption',)), ('Karten', ('Card', 'Chapter')), ('Blende/Blitz', ('Fade', 'Flash')),
    ('Ruck/Rütteln', ('Kick', 'Shake')), ('Klang/Musik', ('Sound', 'Music')), ('Effekte', ('FxCue', 'ScreenCue')),
    ('Pose/Kostüm', ('PoseCue', 'Costume')), ('Puppen', ('PuppetSpawn', 'PuppetMove', 'PuppetAct', 'PuppetRemove')),
    ('Marken', ('Mark',)),
]
CUE_COLORS = {
    'Caption': '#f4f4f5', 'Card': '#ffd75e', 'Chapter': '#3fd8ff', 'Fade': '#6b6b75', 'Flash': '#ffffff', 'Kick': '#ff6b5e',
    'Shake': '#e0483c', 'Sound': '#a1a1aa', 'Music': '#8b6cff', 'FxCue': '#3fe0c5', 'ScreenCue': '#c9b8ff',
    'PoseCue': '#6be38c', 'Costume': '#ffd75e', 'PuppetSpawn': '#ff8a3d', 'PuppetMove': '#ff8a3d', 'PuppetAct': '#ffb27a',
    'PuppetRemove': '#a1a1aa', 'Mark': '#ffd75e',
}


# --------------------------------------------------------------------------------------------- Mathematik

def add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def mul(a, k):
    return (a[0] * k, a[1] * k, a[2] * k)


def lerp(a, b, k):
    return add(a, mul(sub(b, a), k))


def dist(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def ease(kind, t):
    t = max(0.0, min(1.0, t))
    if kind == 'LINEAR':
        return t
    if kind == 'IN':
        return t * t * t
    if kind == 'OUT':
        return 1 - (1 - t) ** 3
    if kind == 'OUT_BACK':
        s, u = 1.70158, t - 1
        return 1 + (s + 1) * u ** 3 + s * u * u
    return t * t * (3 - 2 * t)


def catmull(p0, p1, p2, p3, u):
    def c(a, b, c_, d):
        return 0.5 * (2 * b + (-a + c_) * u + (2 * a - 5 * b + 4 * c_ - d) * u * u + (-a + 3 * b - 3 * c_ + d) * u ** 3)
    return tuple(c(p0[i], p1[i], p2[i], p3[i]) for i in range(3))


def samples(points):
    if len(points) < 2:
        return points, [0.0]
    out = []
    for seg in range(len(points) - 1):
        p0 = points[max(seg - 1, 0)]
        p3 = points[min(seg + 2, len(points) - 1)]
        for k in range(SPLINE_SAMPLES):
            out.append(catmull(p0, points[seg], points[seg + 1], p3, k / SPLINE_SAMPLES))
    out.append(points[-1])
    acc = [0.0]
    for i in range(1, len(out)):
        acc.append(acc[-1] + dist(out[i], out[i - 1]))
    return out, acc


def along(pts, acc, s):
    if len(pts) == 1 or acc[-1] < 1e-9:
        return pts[0]
    target = acc[-1] * max(0.0, min(1.0, s))
    for i in range(len(acc) - 1):
        if acc[i + 1] >= target:
            seg = max(acc[i + 1] - acc[i], 1e-9)
            return lerp(pts[i], pts[i + 1], (target - acc[i]) / seg)
    return pts[-1]


def trapezoid(x, r=0.2):
    x = max(0.0, min(1.0, x))
    v = 1.0 / (1.0 - r)
    if x < r:
        return 0.5 * v * x * x / r
    if x > 1 - r:
        return 1.0 - 0.5 * v * (1 - x) ** 2 / r
    return 0.5 * v * r + v * (x - r)


def arc_around(a, b, c, k):
    ax, az, bx, bz = a[0] - c[0], a[1] - c[1], b[0] - c[0], b[1] - c[1]
    ra, rb = math.hypot(ax, az), math.hypot(bx, bz)
    if ra < 1e-3 or rb < 1e-3:
        return lerp(a, b, k)
    fa = math.atan2(az, ax)
    d = math.atan2(bz, bx) - fa
    while d > math.pi:
        d -= 2 * math.pi
    while d < -math.pi:
        d += 2 * math.pi
    r = ra + (rb - ra) * k
    ang = fa + d * k
    return (c[0] + math.cos(ang) * r, c[1] + math.sin(ang) * r, a[2] + (b[2] - a[2]) * k)


# --------------------------------------------------------------------------------------------- Simulation
# Koordinaten der Vorschau: (f, r, u) relativ zum Anker.

class Sim:
    def __init__(self, script):
        self.s = script
        self.puppets = {}       # Name → [pos, yaw]
        self.moves = {}         # Name → (from, until, pts, acc, lift)
        self.puppet_tracks = {}  # Name → [(t, pos)]

    def actor(self, i):
        return (0.0, 0.0, 0.0) if i == 0 else (4.0 * i, 0.0, 0.0)

    def actor_yaw(self, i):
        return 0.0 if i == 0 else 180.0

    def rel(self, base, yaw, f, r, u):
        # yaw relativ zum Anker: 0 = gleiche Blickrichtung.
        # Spiel: vorn = (−sin yaw, cos yaw), rechts = (−cos yaw, −sin yaw) in (x, z); Vorschau: f = z, r = −x.
        a = math.radians(yaw)
        fwd = (math.cos(a), math.sin(a))
        rgt = (-math.sin(a), math.cos(a))
        return (base[0] + fwd[0] * f + rgt[0] * r, base[1] + fwd[1] * f + rgt[1] * r, base[2] + u)

    def resolve(self, k, snapshot):
        kind = k['k']
        if kind == 'At':
            return (k['f'], k['r'], k['u'])
        if kind == 'World':
            return (k['z'], -k['x'], k['y'])
        if kind == 'Actor':
            return add(self.actor(k['i']), (0, 0, k['up']))
        if kind == 'ActorRel':
            return self.rel(self.actor(k['i']), self.actor_yaw(k['i']), k['f'], k['r'], k['u'])
        if kind in ('Puppet', 'PuppetRel'):
            p = self.puppets.get(k['name'], [(0.0, 0.0, 0.0), 0.0])
            if kind == 'Puppet':
                return add(p[0], (0, 0, k['up']))
            return self.rel(p[0], p[1], k['f'], k['r'], k['u'])
        if kind == 'Group':
            return (0.0, 0.0, k['up'])
        if kind == 'Eye':
            return (0.0, 0.0, EYE_HEIGHT)
        if kind == 'EyeLook':
            return (16.0, 0.0, EYE_HEIGHT)
        return snapshot

    def run(self):
        s = self.s
        cues = sorted(s['cues'], key=lambda c: c['at'])
        ci = 0
        frames = []
        last_cam, last_look = (0.0, 0.0, EYE_HEIGHT), (16.0, 0.0, EYE_HEIGHT)
        snap_cam, snap_look = last_cam, last_look
        current = None
        for t in range(s['length'] + 1):
            while ci < len(cues) and cues[ci]['at'] <= t:
                self.fire(cues[ci], t)
                ci += 1
            self.tick_moves(t)
            shot = next((sh for sh in s['shots'] if sh['from'] <= min(t, s['length'] - 1) < sh['to']), s['shots'][-1])
            if shot is not current:
                current = shot
                snap_cam, snap_look = last_cam, last_look
            raw = (t - shot['from']) / max(1, shot['to'] - shot['from'])
            k = ease(shot['ease'], raw)
            look = self.path(shot['look'], k, snap_look, None, False)
            cam = self.path(shot['cam'], k, snap_cam, look, shot['arc'])
            last_cam, last_look = cam, look
            frames.append((t, shot, cam, look))
        return frames

    def path(self, keys, k, snapshot, center, arc):
        pts = [self.resolve(key, snapshot) for key in keys]
        if len(pts) == 1:
            return pts[0]
        if len(pts) == 2:
            if arc and center is not None:
                return arc_around(pts[0], pts[1], center, k)
            return lerp(pts[0], pts[1], k)
        p, a = samples(pts)
        return along(p, a, max(0.0, min(1.0, k)))

    def fire(self, c, t):
        if c['type'] == 'PuppetSpawn':
            self.puppets[c['name']] = [self.resolve(c['pos'], (0, 0, 0)), c['yaw']]
            self.puppet_tracks.setdefault(c['name'], []).append((t, self.puppets[c['name']][0]))
        elif c['type'] == 'PuppetMove' and c['name'] in self.puppets:
            pts = [self.puppets[c['name']][0]] + [self.resolve(k, (0, 0, 0)) for k in c['path']]
            p, a = samples(pts)
            self.moves[c['name']] = (t, c['end'], p, a, c['lift'])
        elif c['type'] == 'PuppetRemove':
            self.puppets.pop(c['name'], None)
            self.moves.pop(c['name'], None)

    def tick_moves(self, t):
        for name, (t0, t1, p, a, lift) in list(self.moves.items()):
            if name not in self.puppets:
                continue
            x = (t - t0) / max(1, t1 - t0)
            pos = along(p, a, trapezoid(x))
            pos = (pos[0], pos[1], pos[2] + lift * 4 * x * (1 - x))
            self.puppets[name][0] = pos
            self.puppet_tracks[name].append((t, pos))
            if t >= t1:
                del self.moves[name]


# --------------------------------------------------------------------------------------------- Zeichnung

def esc(s):
    return str(s).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def render(script):
    sim = Sim(script)
    frames = sim.run()
    length = script['length']
    W = 1400
    map_w, map_h = 900, 620
    pts = [(c[0], c[1]) for _, _, c, _ in frames] + [(l[0], l[1]) for _, _, _, l in frames]
    for track in sim.puppet_tracks.values():
        pts += [(p[0], p[1]) for _, p in track]
    pts += [(0, 0)]
    fmin = min(p[0] for p in pts) - 2
    fmax = max(p[0] for p in pts) + 2
    rmin = min(p[1] for p in pts) - 2
    rmax = max(p[1] for p in pts) + 2
    scale = min((map_w - 60) / max(1e-3, rmax - rmin), (map_h - 60) / max(1e-3, fmax - fmin))
    ox, oy = 30, 50

    def X(r):
        return ox + 30 + (r - rmin) * scale

    def Y(f):
        return oy + 30 + (fmax - f) * scale

    out = []
    rows = len(CUE_ROWS) + 1
    strip_top = oy + map_h + 40
    H = strip_top + rows * 26 + 60
    out.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" '
               f'font-family="Consolas, monospace" font-size="12">')
    out.append(f'<rect width="{W}" height="{H}" fill="#0b0b0e"/>')
    out.append(f'<text x="30" y="30" fill="#f4f4f5" font-size="18">{esc(script["id"])}</text>')
    out.append(f'<text x="260" y="30" fill="#a1a1aa">{length} Ticks ({length / 20:.1f} s), '
               f'{len(script["shots"])} Einstellungen, {len(script["cues"])} Spuren'
               f'{", überspringbar" if script["skippable"] else ""}</text>')
    # Raster
    out.append(f'<rect x="{ox}" y="{oy}" width="{map_w}" height="{map_h}" fill="#121217" stroke="#29ffffff"/>')
    for f in range(math.floor(fmin), math.ceil(fmax) + 1):
        w = '#2a2a33' if f % 5 else '#3a3a46'
        out.append(f'<line x1="{X(rmin):.1f}" y1="{Y(f):.1f}" x2="{X(rmax):.1f}" y2="{Y(f):.1f}" stroke="{w}" stroke-width="0.6"/>')
    for r in range(math.floor(rmin), math.ceil(rmax) + 1):
        w = '#2a2a33' if r % 5 else '#3a3a46'
        out.append(f'<line x1="{X(r):.1f}" y1="{Y(fmin):.1f}" x2="{X(r):.1f}" y2="{Y(fmax):.1f}" stroke="{w}" stroke-width="0.6"/>')
    # Anker und Darsteller 0
    out.append(f'<circle cx="{X(0):.1f}" cy="{Y(0):.1f}" r="6" fill="none" stroke="#f4f4f5" stroke-width="1.5"/>')
    out.append(f'<line x1="{X(0):.1f}" y1="{Y(0):.1f}" x2="{X(0):.1f}" y2="{Y(1.5):.1f}" stroke="#f4f4f5" stroke-width="1.5"/>')
    out.append(f'<text x="{X(0) + 9:.1f}" y="{Y(0) + 4:.1f}" fill="#a1a1aa">Anker / Darsteller 0</text>')
    # Puppen-Wege
    for name, track in sim.puppet_tracks.items():
        d = ' '.join(f'{"M" if i == 0 else "L"}{X(p[1]):.1f},{Y(p[0]):.1f}' for i, (_, p) in enumerate(track))
        out.append(f'<path d="{d}" fill="none" stroke="#ff8a3d" stroke-width="1.5" stroke-dasharray="4 3"/>')
        t0, p0 = track[0]
        out.append(f'<rect x="{X(p0[1]) - 4:.1f}" y="{Y(p0[0]) - 4:.1f}" width="8" height="8" fill="#ff8a3d"/>')
        out.append(f'<text x="{X(p0[1]) + 7:.1f}" y="{Y(p0[0]) - 6:.1f}" fill="#ffb27a">{esc(name)} ({t0})</text>')
    # Blicklinien und Kamerabahn
    shot_index = {id(sh): i for i, sh in enumerate(script['shots'])}
    for t, sh, cam, look in frames:
        if t % 20 == 0:
            col = SHOT_COLORS[shot_index[id(sh)] % len(SHOT_COLORS)]
            out.append(f'<line x1="{X(cam[1]):.1f}" y1="{Y(cam[0]):.1f}" x2="{X(look[1]):.1f}" y2="{Y(look[0]):.1f}" '
                       f'stroke="{col}" stroke-opacity="0.25" stroke-width="1"/>')
            out.append(f'<circle cx="{X(look[1]):.1f}" cy="{Y(look[0]):.1f}" r="2" fill="{col}" fill-opacity="0.6"/>')
    for i in range(1, len(frames)):
        t, sh, cam, _ = frames[i]
        p = frames[i - 1][2]
        col = SHOT_COLORS[shot_index[id(sh)] % len(SHOT_COLORS)]
        dash = ' stroke-dasharray="2 3"' if sh['cut'] and t == sh['from'] else ''
        out.append(f'<line x1="{X(p[1]):.1f}" y1="{Y(p[0]):.1f}" x2="{X(cam[1]):.1f}" y2="{Y(cam[0]):.1f}" stroke="{col}" stroke-width="2.4"{dash}/>')
        if t % 10 == 0:
            out.append(f'<circle cx="{X(cam[1]):.1f}" cy="{Y(cam[0]):.1f}" r="2.2" fill="{col}"/>')
    for i, sh in enumerate(script['shots']):
        _, _, cam, _ = frames[sh['from']]
        col = SHOT_COLORS[i % len(SHOT_COLORS)]
        out.append(f'<text x="{X(cam[1]) + 6:.1f}" y="{Y(cam[0]) + 14:.1f}" fill="{col}">#{i} {sh["from"]}{" ✂" if sh["cut"] else ""}</text>')
    # Höhe über die Zeit
    hx, hy, hw, hh = ox + map_w + 30, oy, W - (ox + map_w + 30) - 30, 260
    us = [f[2][2] for f in frames]
    umin, umax = min(us + [0]) - 0.5, max(us + [2]) + 0.5
    out.append(f'<rect x="{hx}" y="{hy}" width="{hw}" height="{hh}" fill="#121217" stroke="#29ffffff"/>')
    out.append(f'<text x="{hx + 8}" y="{hy + 16}" fill="#a1a1aa">Kamerahöhe (Blöcke über dem Anker) und FOV</text>')
    for i in range(1, len(frames)):
        t, sh, cam, _ = frames[i]
        col = SHOT_COLORS[shot_index[id(sh)] % len(SHOT_COLORS)]
        x1 = hx + (t - 1) / length * hw
        x2 = hx + t / length * hw
        y1 = hy + hh - (frames[i - 1][2][2] - umin) / (umax - umin) * (hh - 30) - 6
        y2 = hy + hh - (cam[2] - umin) / (umax - umin) * (hh - 30) - 6
        out.append(f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" stroke="{col}" stroke-width="2"/>')
    out.append(f'<text x="{hx + 8}" y="{hy + hh - 8}" fill="#6b6b75">{umin:.1f}</text>')
    out.append(f'<text x="{hx + 8}" y="{hy + 34}" fill="#6b6b75">{umax:.1f}</text>')
    fy = hy + hh + 20
    out.append(f'<text x="{hx}" y="{fy}" fill="#a1a1aa">FOV je Einstellung</text>')
    for i, sh in enumerate(script['shots']):
        col = SHOT_COLORS[i % len(SHOT_COLORS)]
        out.append(f'<text x="{hx}" y="{fy + 18 + i * 16}" fill="{col}">#{i} {sh["from"]}–{sh["to"]}: {sh["fov"][0]:g}→{sh["fov"][1]:g}'
                   f' {sh["ease"]}{" arc" if sh["arc"] else ""} follow {sh["follow"]:g} hand {sh["handheld"]:g}'
                   f' cam {"/".join(k["k"] for k in sh["cam"])}</text>')
    # Zeitstreifen
    sx, sw = 150, W - 180

    def TX(t):
        return sx + t / length * sw

    out.append(f'<text x="30" y="{strip_top - 12}" fill="#a1a1aa">Zeitleiste (Ticks)</text>')
    for t in range(0, length + 1, 20):
        out.append(f'<line x1="{TX(t):.1f}" y1="{strip_top}" x2="{TX(t):.1f}" y2="{strip_top + rows * 26}" stroke="#22222a"/>')
        out.append(f'<text x="{TX(t) - 8:.1f}" y="{strip_top + rows * 26 + 14}" fill="#6b6b75" font-size="10">{t}</text>')
    y = strip_top
    out.append(f'<text x="30" y="{y + 16}" fill="#f4f4f5">Einstellungen</text>')
    for i, sh in enumerate(script['shots']):
        col = SHOT_COLORS[i % len(SHOT_COLORS)]
        out.append(f'<rect x="{TX(sh["from"]):.1f}" y="{y + 4}" width="{TX(sh["to"]) - TX(sh["from"]):.1f}" height="16" fill="{col}" fill-opacity="0.35" stroke="{col}"/>')
        out.append(f'<text x="{TX(sh["from"]) + 3:.1f}" y="{y + 16}" fill="#f4f4f5" font-size="10">#{i}</text>')
    for label, types in CUE_ROWS:
        y += 26
        out.append(f'<text x="30" y="{y + 16}" fill="#a1a1aa">{label}</text>')
        for c in script['cues']:
            if c['type'] not in types:
                continue
            col = CUE_COLORS.get(c['type'], '#a1a1aa')
            span = c['end'] - c['at']
            if span <= 0 and c.get('ticks', 0) and c['type'] not in ('Kick',):
                span = c['ticks']
            if span > 0:
                w = max(2.0, TX(min(length, c['at'] + span)) - TX(c['at']))
                out.append(f'<rect x="{TX(c["at"]):.1f}" y="{y + 6}" width="{w:.1f}" height="12" fill="{col}" fill-opacity="0.3" stroke="{col}" stroke-width="0.8"/>')
            else:
                out.append(f'<rect x="{TX(c["at"]) - 1.5:.1f}" y="{y + 3}" width="3" height="18" fill="{col}"/>')
            name = c.get('name') or c.get('pose') or c.get('id') or c.get('key', '').split('.')[-1] or c['type']
            out.append(f'<text x="{TX(c["at"]) + 3:.1f}" y="{y + 16}" fill="{col}" font-size="9">{esc(name)}</text>')
    out.append('</svg>')
    return '\n'.join(out), W, H


def to_png(svg_path, png_path, w, h):
    exe = next((c for c in CHROMES if os.path.exists(c)), None) or shutil.which('chrome') or shutil.which('msedge')
    if not exe:
        return False
    tmp = tempfile.mkdtemp()
    html = os.path.join(tmp, 'p.html')
    with open(svg_path, encoding='utf-8') as f:
        svg = f.read()
    with open(html, 'w', encoding='utf-8') as f:
        f.write(f'<!doctype html><html><body style="margin:0;background:#0b0b0e">{svg}</body></html>')
    subprocess.run([exe, '--headless=new', '--disable-gpu', '--hide-scrollbars', f'--window-size={w},{h}',
                    '--screenshot=' + png_path, 'file:///' + html.replace('\\', '/')],
                   capture_output=True, timeout=120)
    shutil.rmtree(tmp, ignore_errors=True)
    return os.path.exists(png_path)


def main(argv):
    if not os.path.exists(DUMP):
        raise SystemExit(f'Kein Export unter {DUMP}: zuerst  python tools/hha_assets/gradle_locked.py dumpStages')
    with open(DUMP, encoding='utf-8') as f:
        data = json.load(f)
    scripts = {s['id']: s for s in data['scripts']}
    if data.get('failed'):
        print('Nicht exportiert:', ', '.join(f'{k} ({v})' for k, v in data['failed'].items()))
    if not argv or argv[0] == '--list':
        print(', '.join(scripts) or '(keine Skripte)')
        return
    ids = list(scripts) if argv[0] == '--all' else argv
    os.makedirs(OUT, exist_ok=True)
    for sid in ids:
        if sid not in scripts:
            print('Unbekannt:', sid)
            continue
        svg, w, h = render(scripts[sid])
        svg_path = os.path.join(OUT, sid + '.svg')
        png_path = os.path.join(OUT, sid + '.png')
        with open(svg_path, 'w', encoding='utf-8') as f:
            f.write(svg)
        print(png_path if to_png(svg_path, png_path, w, h) else svg_path + ' (kein Browser für PNG)')


if __name__ == '__main__':
    main(sys.argv[1:])
