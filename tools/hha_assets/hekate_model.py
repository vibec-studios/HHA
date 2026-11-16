"""Hekate, die Dreigestaltige: Hüterin der Schwelle (Insel C). Modell, Textur, Leuchtmaske, Trugbild-Variante, POSES.

Eine schlanke, schwebende Säule von einer Gestalt, wie eine Hekataion-Statue, die sich in Bewegung gesetzt hat: ein
Körper, drei Gesichter. Erkennungsmerkmal ist der sechseckige Kopf mit drei Gesichtern (vorn, links hinten, rechts
hinten) unter dem hohen Polos mit Mondsichel und die zwei langen Fackeln, gekreuzt vor der Brust. Keine Beine: Der
Peplos läuft in einer dreistufigen Glocke mit sechs Zacken aus und schwebt eine Handbreit über dem Boden.

Teil von boss_models.py (Aufruf dort mit --only hekate). Direkt aufgerufen schreibt diese Datei die Posentabelle in
client/model/HekateModel.kt (zwischen den Marken), damit Vorschau und Spiel dieselben Winkel benutzen:

    python tools/hha_assets/hekate_model.py --kotlin

Koordinaten: 1/16 Block vor dem Renderer-Faktor 0,69, +y nach unten, -z vorn. Der Saum schwebt bei y = 18 (6 über dem
Boden bei 24), der Kronenrand liegt bei y = -56 (74 Einheiten). Die rechte Körperseite liegt bei -x.

Gesichter: Der Kopf ist eine Sechseck-Säule aus drei um 60° gedrehten Quadern (head, head_b, head_c). Phase 0 (Fackel)
schaut von head nach vorn, Phase 1 (Schlüssel) sitzt auf der Rückseite von head_b und kommt bei head.yaw = 120° nach
vorn, Phase 2 (Dolch) auf der Vorderseite von head_c bei 240°. Über jedem Gesicht trägt der Polos sein Zeichen.

Trugbild (`hekate_echo`): gleiche UVs, die Fackeln sind erloschen (Kopf in Altsilber-Schatten, keine Flamme), das
Gewand eine Stufe heller und kühler; die Augen leuchten weiter.
"""
import math
import os
import re
import sys

from bosskit import Cube, FACE_LIGHT
from modelkit import Model, Part, hexc, mix, ramp, rgb, speckle
from texkit import Image, save

SIDES = ('front', 'back', 'left', 'right')

# =====================================================================================================================
# Material (Leitfarben aus dem Brief, als Rampen mit Licht und Schatten erweitert)
# =====================================================================================================================

MOONSKIN = rgb('#3e3a58', '#6f6a8a', '#9d98bd', '#b3aed0', '#d6d3ea', '#ecebf7')
NIGHT = rgb('#07050f', '#15102a', '#211a40', '#2b2150', '#3b2e6c', '#4a3b86', '#6553a8')
NIGHT_ECHO = rgb('#151a2e', '#232c4c', '#2f3c66', '#3a4a7c', '#4e62a0', '#6478bc', '#8a9cd8')
LINING = rgb('#05030a', '#0d0919', '#171028', '#221837')
SILVER = rgb('#1c1e27', '#2e313d', '#4b4f5e', '#727789', '#9aa0b4', '#c3c8d6', '#e2e6f0', '#f6f8fc')
TARNISH = rgb('#1a1d26', '#2b3038', '#3d4450', '#535b68')
WOOD = rgb('#120a06', '#2a1a12', '#432a19', '#5a3a22', '#7a5434')
HAIR = rgb('#06050b', '#0f0b18', '#1a1428', '#271f3c', '#382d55', '#4d4170')
FLAME = rgb('#4a3896', '#7d68d6', '#9a86e8', '#c2b6f6', '#e4ddff', '#f1edff', '#ffffff')
SMOKE = rgb('#16151c', '#24232c', '#33323e', '#45444f')
MOON_GLOW = (207, 196, 255)                 # Leuchtfarbe #cfc4ff

# Trugbild-Schalter: Die Maler fragen ihn beim Malen ab; variants() malt den Atlas ein zweites Mal mit ECHO = True.
ECHO = [False]


def _v(face, x, y, w, h, base, seed, grad, roundx=0.0, noise=0.025):
    v = base + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad + (speckle(x, y, seed) - 0.5) * noise * 2
    if roundx and face in SIDES and w > 2:
        k = (x + 0.5) / w * 2 - 1
        v += roundx * (0.35 - k * k)
    return v


# =====================================================================================================================
# Maler
# =====================================================================================================================

def skin(base=0.55, seed=0, roundx=0.28, grad=0.14):
    """Mondstein-Haut: kühl, glatt, mit weicher Rundung quer zur Fläche."""
    def paint(face, x, y, w, h):
        return ramp(MOONSKIN, _v(face, x, y, w, h, base, seed, grad, roundx, 0.02))
    return paint


def silver(base=0.5, seed=0, rim=0.2, grad=0.18, roundx=0.2, sheen=0.08, tarnish=0.78):
    """Altsilber: Glanzstreifen, helle Oberkante, dunkle Fuge unten, Anlauf in den Vertiefungen."""
    def paint(face, x, y, w, h):
        v = _v(face, x, y, w, h, base, seed, grad, roundx, 0.03)
        if face in SIDES:
            if w >= 4:
                v += sheen * math.exp(-((x / (w - 1) - 0.3) ** 2) / 0.03)
            if y == 0:
                v += rim
            elif y == h - 1 and h > 2:
                v -= rim * 0.5
        elif face == 'top' and (x in (0, w - 1) or y in (0, h - 1)):
            v += rim * 0.6
        if speckle(x + 11, y + 5, seed + 7) > tarnish:
            return ramp(TARNISH, v + 0.2)
        return ramp(SILVER, v)
    return paint


def robe(base=0.5, seed=0, folds=0.1, period=4.6, grad=0.16, hem=0, trim_top=0, stars=0.0):
    """Nachtgewand: breite weiche Falten, Silbersaum unten (hem Reihen), oben (trim_top Reihen), vereinzelte
    Sternpunkte. Im Trugbild eine Stufe heller und kühler."""
    trim = silver(0.56, seed + 1, rim=0.12, roundx=0.0)

    def paint(face, x, y, w, h):
        if face in SIDES and ((hem and y >= h - hem) or (trim_top and y < trim_top)):
            return trim(face, x, y, w, h)
        v = base + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad + (speckle(x, y, seed) - 0.5) * 0.03
        if face in SIDES:
            v += folds * math.sin((x + seed * 0.37) * math.tau / period)
            if stars and speckle(x * 3 + 1, y * 3 + 2, seed + 13) > 1.0 - stars and 0 < y < h - 2:
                return ramp(SILVER, 0.62 + FACE_LIGHT[face])
        pal = NIGHT_ECHO if ECHO[0] else NIGHT
        return ramp(pal, v + (0.06 if ECHO[0] else 0.0))
    return paint


def lining(base=0.4, seed=0):
    def paint(face, x, y, w, h):
        return ramp(LINING, base + 0.08 * math.sin(x * 1.3 + seed) + (speckle(x, y, seed) - 0.5) * 0.05)
    return paint


def wood(base=0.5, seed=0):
    """Fackelschaft: dunkles Holz mit Maserung längs (die Schäfte stehen in y)."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + 0.12 * math.sin(y * 0.9 + x * 2.1 + seed) + (speckle(x, y, seed) - 0.5) * 0.08
        if face in SIDES and w > 2:
            v += 0.12 * (1 - abs(x - (w - 1) / 2.0))
        return ramp(WOOD, v)
    return paint


def hair(base=0.45, seed=0):
    """Haar zwischen den Gesichtern: dunkle, senkrechte Strähnen mit violettem Glanz."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + 0.14 * math.sin(x * 2.3 + seed) + (speckle(x, y, seed) - 0.5) * 0.08
        v += 0.12 * (0.5 - y / max(h - 1, 1))
        return ramp(HAIR, v)
    return paint


def flame_color(k, t):
    """k: Abstand von der Mittellinie (0 Kern … 1 Rand), t: Höhe (0 Spitze … 1 Fuß). Liefert Farbe und Leuchtwert."""
    c = ramp(FLAME, 0.95 - k * 0.7 - (1 - t) * 0.15)
    strength = int(255 - k * 70)
    return (c, (MOON_GLOW[0], MOON_GLOW[1], MOON_GLOW[2], strength))


def flame(seed=0, width=6):
    """Flammenkeil: zungenförmig, Spitze oben; Kern fast weiß, Rand violett. Im Trugbild erloschen (nur Rauchrest)."""
    def half(y, h):
        t = (y + 0.5) / h
        hw = (width / 2.0 + 0.2) * min(1.0, (t / 0.72) ** 0.85)
        hw *= 1.0 - 0.18 * max(0.0, t - 0.8) / 0.2
        return hw, t

    def paint(face, x, y, w, h):
        if face in ('top', 'bottom'):
            return None
        hw, t = half(y, h)
        if ECHO[0]:
            # Erloschen: nur ein schmaler, dunkler Rauchfaden über dem kalten Kopf.
            if t < 0.6:
                return None
            if face in ('front', 'back') and abs(x - (w - 1) / 2.0) < 0.6 + 0.6 * (t - 0.6) / 0.4 and speckle(x, y, seed) > 0.25:
                return ramp(SMOKE, 0.3 + 0.5 * t)
            return None
        if face in ('front', 'back'):
            cx = (w - 1) / 2.0 + 0.6 * math.sin(y * 0.9 + seed) * (1 - t)
            d = abs(x - cx)
            if d > hw:
                return None
            return flame_color(d / max(hw, 0.6), t)
        # Schmale Seiten (2 breit): nur dort, wo die Flamme ihre volle Breite hat.
        if hw >= width / 2.0 - 0.2:
            return flame_color(0.85, t)
        return None
    return paint


def torch_head(seed=0):
    """Fackelkopf: Silberkorb mit drei Spangen, dazwischen die Glut (nicht in der Leuchtmaske; nur die Flamme leuchtet).
    Im Trugbild kalter Altsilber-Schatten."""
    cage = silver(0.5, seed, rim=0.18, roundx=0.0, tarnish=0.7)

    def paint(face, x, y, w, h):
        if ECHO[0]:
            v = 0.26 + FACE_LIGHT[face] + (speckle(x, y, seed) - 0.5) * 0.1
            if face in SIDES and x in (0, w - 1):
                v += 0.12
            return ramp(SILVER, v)
        if face == 'top':
            d = max(abs(x - (w - 1) / 2.0), abs(y - (h - 1) / 2.0))
            return ramp(FLAME, 0.85 - d * 0.18)
        if face == 'bottom':
            return cage(face, x, y, w, h)
        if y in (0, h - 1) or x in (0, w - 1) or (x == (w - 1) // 2 and face in ('front', 'back')):
            return cage(face, x, y, w, h)
        return ramp(FLAME, 0.55 + 0.2 * (1 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.2)
    return paint


def glow_silver(strength=210):
    """Mondsichel: helles Silberweiß mit violettem Schein (Leuchtmaske)."""
    def paint(face, x, y, w, h):
        v = 0.86 + FACE_LIGHT[face] * 0.6 + (speckle(x, y, 77) - 0.5) * 0.05
        return (ramp(SILVER, v), (MOON_GLOW[0], MOON_GLOW[1], MOON_GLOW[2], strength))
    return paint


# --- Gesichter ---------------------------------------------------------------------------------------------------------

# Je Gesicht 7 x 12 Texel: Reihe 0-1 Haar unter dem Polos, 2 Silberband, 4 Brauen, 5 Augen, 6-7 Nase, 9 Mund.
FACE_FEATURES = {
    0: {  # Fackel: die Junge, ruhig, Mundwinkel leicht gehoben
        'brow': [(1, 4), (2, 4), (4, 4), (5, 4)],
        'mouth': [(2, 9), (3, 9), (4, 9)], 'corner_up': [(1, 8), (5, 8)], 'lines': [], 'look': 0,
    },
    1: {  # Schlüssel: die Mütterliche, gerade Brauen, voller Mund
        'brow': [(1, 4), (2, 4), (4, 4), (5, 4)],
        'mouth': [(2, 9), (3, 9), (4, 9)], 'corner_up': [], 'lines': [(1, 8), (5, 8)], 'look': 0,
    },
    2: {  # Dolch: die Alte, strenge Brauen, Mundwinkel nach unten, Zornesfalte
        'brow': [(1, 3), (2, 4), (4, 4), (5, 3)],
        'mouth': [(2, 9), (3, 9), (4, 9), (1, 10), (5, 10)], 'corner_up': [], 'lines': [(3, 3), (1, 7), (5, 7)], 'look': 1,
    },
}


def face_paint(k, which):
    """Gesicht k auf der Fläche which; alle anderen Seiten sind Haar, das Silberband (Reihe 2) läuft ringsum."""
    sk = skin(0.62, 300 + k, roundx=0.3, grad=0.12)
    hr = hair(0.4, 310 + k)
    band = silver(0.66, 320 + k, rim=0.0, roundx=0.0, tarnish=0.9)
    f = FACE_FEATURES[k]
    socket = hexc('#2a2440')

    def paint(face, x, y, w, h):
        if face in ('top', 'bottom', 'left', 'right'):
            return hr(face, x, y, w, h)          # oben unter dem Polos, seitlich innen (unsichtbar)
        if y == 2:
            if x == (w - 1) // 2 and face == which:
                return ramp(SILVER, 0.95)         # Stirnstein über dem Gesicht
            return band(face, x, y, w, h)
        if face != which or y < 2:
            return hr(face, x, y, w, h)
        if x in (0, w - 1) and 3 <= y <= 5:
            return hr(face, x, y, w, h)          # Haar an den Schläfen
        if (x, y) in f['brow']:
            return ramp(HAIR, 0.3)
        if y == 5 and x in (1, 2, 4, 5):
            inner = x in (2, 4)
            if f['look']:
                inner = not inner                # die Alte blickt nach außen
            c = hexc('#ffffff') if inner else hexc('#b9aaf4')
            return (c, (MOON_GLOW[0], MOON_GLOW[1], MOON_GLOW[2], 255 if inner else 190))
        if y == 4 and x in (1, 2, 4, 5):
            return socket                         # Augenhöhle unter der Braue
        if (x, y) in f['mouth']:
            return hexc('#241834')
        if (x, y) in f['corner_up']:
            return ramp(MOONSKIN, 0.4)
        if (x, y) in f['lines']:
            return ramp(MOONSKIN, 0.38)
        c = sk(face, x, y, w, h)
        if y == 6 and x in (1, 2, 4, 5):
            return mix(c, socket, 0.3)            # Schatten unter den Augen
        if y == 7 and x == 3:
            return ramp(MOONSKIN, 0.36)           # Nasenschatten
        if y == 10 and x == 3:
            return ramp(MOONSKIN, 0.78)           # Licht auf dem Kinn
        if y == 11:
            return mix(c, socket, 0.45)           # Kiefer im Schatten
        if x in (0, w - 1):
            return mix(c, socket, 0.25)           # Wangen drehen weg
        return c
    return paint


# --- Zeichen auf dem Polos -------------------------------------------------------------------------------------------

SIGNS = {
    'key': [".#.", "#.#", ".#.", ".#.", ".##"],
    'dagger': [".#.", ".#.", ".#.", "###", ".#."],
    'rosette': [".#.", "#.#", ".#."],
}


def polos_panel(seed, sign='rosette'):
    """Polos-Wand: Altsilber mit Kannelur, helles Band oben mit Mäander, dunkle Fuge unten; in der Mitte ein
    eingraviertes Zeichen (Schlüssel, Dolch, sonst eine Rosette)."""
    base = silver(0.44, seed, rim=0.2, roundx=0.0, tarnish=0.8)

    def paint(face, x, y, w, h):
        c = base(face, x, y, w, h)
        if face == 'top':
            return ramp(SILVER, 0.85)
        if face == 'back':
            return ramp(SILVER, 0.2 + 0.05 * (y / h))
        if face != 'front':
            return c
        if y == 0:
            return ramp(SILVER, 0.9)
        if y == 1:
            return ramp(SILVER, 0.34 if x % 3 == 0 else 0.68)      # Mäander im Band
        if y == h - 1:
            return ramp(SILVER, 0.3)
        if y == h - 2:
            return ramp(SILVER, 0.6)
        rows = SIGNS[sign]
        sx = x - (w - len(rows[0])) // 2
        sy = y - 2 - (h - 4 - len(rows)) // 2
        inside = 0 <= sy < len(rows)
        if inside and 0 <= sx < len(rows[0]) and rows[sy][sx] == '#':
            return ramp(SILVER, 0.95)
        if inside and 0 <= sx - 1 < len(rows[0]) and rows[sy][sx - 1] == '#':
            return ramp(SILVER, 0.18)                               # Schattenkante der Gravur
        if x % 2 == 1:
            return mix(c, hexc('#1c1e27'), 0.22)                    # Kannelur
        return c
    return paint


# =====================================================================================================================
# Rechenhilfen (Vorwärtskinematik und kleine IK, wie in warden_model.py)
# =====================================================================================================================

_EYE = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]


def _rot(pitch, yaw, roll):
    cx, sx = math.cos(pitch), math.sin(pitch)
    cy, sy = math.cos(yaw), math.sin(yaw)
    cz, sz = math.cos(roll), math.sin(roll)
    rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    return _mul(rz, _mul(ry, rx))


def _mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _apply(m, v):
    return tuple(sum(m[i][j] * v[j] for j in range(3)) for i in range(3))


def _tr(m):
    return [[m[j][i] for j in range(3)] for i in range(3)]


def _add(a, b):
    return tuple(x + y for x, y in zip(a, b))


def _sub(a, b):
    return tuple(x - y for x, y in zip(a, b))


def _scale(a, k):
    return tuple(x * k for x in a)


def _dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def _norm(a):
    n = math.sqrt(_dot(a, a)) or 1.0
    return tuple(x / n for x in a)


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def frame(ydir, zhint):
    """Drehmatrix, deren lokale +y-Achse nach ydir zeigt und deren +z möglichst nach zhint."""
    y = _norm(ydir)
    z = _sub(zhint, _scale(y, _dot(zhint, y)))
    if _dot(z, z) < 1e-6:
        z = _sub((1, 0, 0), _scale(y, y[0]))
    z = _norm(z)
    x = _cross(y, z)
    return [[x[i], y[i], z[i]] for i in range(3)]


class Rig:
    """Knochenbaum des Modells für Rechnungen an einer Pose (Dict Name -> 3 oder 6 Zahlen)."""

    def __init__(self, model):
        self.parts, self.parent = {}, {}

        def walk(p, par):
            self.parts[p.name] = p
            self.parent[p.name] = par
            for c in p.children:
                walk(c, p.name)
        for p in model.parts:
            walk(p, None)

    def _val(self, pose, name):
        v = pose.get(name)
        if v is None:
            v = tuple(math.radians(a) for a in self.parts[name].rot)
        v = tuple(v)
        return v[:3], (v[3:6] if len(v) >= 6 else (0.0, 0.0, 0.0))

    def fk(self, pose, name):
        if name is None:
            return _EYE, (0.0, 0.0, 0.0)
        chain = []
        n = name
        while n is not None:
            chain.append(n)
            n = self.parent[n]
        rot, pos = _EYE, (0.0, 0.0, 0.0)
        for n in reversed(chain):
            r, off = self._val(pose, n)
            pos = _add(pos, _apply(rot, _add(self.parts[n].pivot, off)))
            rot = _mul(rot, _rot(*r))
        return rot, pos

    def world(self, pose, name, local=(0, 0, 0)):
        rot, pos = self.fk(pose, name)
        return _add(pos, _apply(rot, local))

    def set_world_rot(self, pose, name, rw):
        rp, _ = self.fk(pose, self.parent[name])
        m = _mul(_tr(rp), rw)
        yaw = math.asin(max(-1.0, min(1.0, -m[2][0])))
        pitch = math.atan2(m[2][1], m[2][2])
        roll = math.atan2(m[1][0], m[0][0])
        _, off = self._val(pose, name)
        pose[name] = (pitch, yaw, roll) + (tuple(off) if any(off) else ())

    def place(self, pose, name, target, local=(0, 0, 0)):
        """Verschiebt den Knochen (Versatz im Elternraum), bis sein Punkt local auf dem Weltpunkt target liegt."""
        rp, pp = self.fk(pose, self.parent[name])
        r, _ = self._val(pose, name)
        offset_local = _apply(_rot(*r), local)
        origin = _apply(_tr(rp), _sub(target, pp))
        pose[name] = tuple(r) + _sub(_sub(origin, offset_local), self.parts[name].pivot)

    def aim(self, pose, name, ydir, zhint=(0, 0, -1)):
        self.set_world_rot(pose, name, frame(ydir, zhint))

    def ik(self, pose, arm, fore, grip, target, elbow=None, weight=0.05, elbow_weight=0.05):
        """Zwei-Knochen-IK: Oberarm (pitch, yaw, roll) und Unterarm (pitch, yaw) so, dass der Griffpunkt target trifft."""
        names = [(arm, 0), (arm, 1), (arm, 2), (fore, 0), (fore, 1)]
        for n in (arm, fore):
            r, off = self._val(pose, n)
            pose[n] = tuple(r) + (tuple(off) if any(off) else ())
        start = [pose[n][k] for n, k in names]

        def put(vals):
            for (n, k), v in zip(names, vals):
                cur = list(pose[n])
                cur[k] = v
                pose[n] = tuple(cur)

        def cost(vals):
            put(vals)
            if not -2.8 <= vals[3] <= 0.05 or abs(vals[4]) > 1.2:
                return 1e9
            d = _sub(self.world(pose, fore, grip), target)
            c = _dot(d, d)
            if elbow is not None:
                e = _sub(self.world(pose, fore), elbow)
                c += elbow_weight * _dot(e, e)
            c += weight * sum((a - b) ** 2 for a, b in zip(vals, start))
            return c

        best = [pose[n][k] for n, k in names]
        best_c = cost(best)
        for step in (0.4, 0.15, 0.05, 0.015, 0.005):
            for _ in range(80):
                improved = False
                for i in range(len(best)):
                    for sgn in (1, -1):
                        trial = list(best)
                        trial[i] += sgn * step
                        c = cost(trial)
                        if c < best_c:
                            best, best_c, improved = trial, c, True
                if not improved:
                    break
        put(best)
        d = _sub(self.world(pose, fore, grip), target)
        return math.sqrt(_dot(d, d))


# =====================================================================================================================
# Modell
# =====================================================================================================================

GRIP = (0.0, 19.0, 0.0)          # Griffpunkt in der Faust (Unterarm-Raum)
KEY_GRIP = (0.0, 3.0, 0.0)       # Ring des Schlüssels
DAGGER_GRIP = (0.0, 3.0, 0.0)    # Heft des Dolchs
FLAME_TIP = (0.0, -34.0, 0.0)    # Mitte der Flamme im Fackel-Raum (Anker für BossGlow)

# Glocke: (Radius oben, Radius unten, Höhe, Plattenbreite) je Stufe; sechs Platten je Stufe, Flächen vorn.
SKIRT = [(7.3, 9.3, 12, 11), (8.6, 11.4, 12, 14), (10.8, 14.0, 13, 17)]
HEM_POINT = 4                    # Tiefe der Zacken am Saum

IDLE = {}


def deg(name):
    v = IDLE.get(name)
    if v is None:
        return (0, 0, 0)
    return tuple(math.degrees(a) for a in v[:3])


def block(x, y, z, w, h, d, paint, bevel=1.0, side_paint=None):
    """Quader mit gefasten senkrechten Kanten (Kreuz aus zwei Quadern, wie bei Hermes)."""
    return [
        Cube(x + bevel, y, z, w - 2 * bevel, h, d, paint),
        Cube(x, y, z + bevel, w, h, d - 2 * bevel, side_paint or paint, dilate=0.02),
    ]


def skirt_panel(stage, i, r_top, r_bot, height, width):
    """Eine Platte der Glocke: oben schmal, unten breit (der Rest durchsichtig), außen Nachtstoff, innen Futter.
    Die unterste Stufe läuft in der Mitte jeder Platte in eine Zacke aus."""
    last = stage == len(SKIRT) - 1
    seed = 500 + stage * 10 + i
    cloth = robe(0.5 - stage * 0.04, seed, folds=0.1, period=3.6, hem=0, stars=0.035)
    trim = silver(0.55, seed + 3, rim=0.1, roundx=0.0)
    inner = lining(0.4, seed)
    tan30 = math.tan(math.radians(30))

    def halfw(row):
        r = r_top + (r_bot - r_top) * (row + 0.5) / height
        return r * tan30 + 0.35

    def bottom_row(x, w):
        if not last:
            return height - 1
        cx = (w - 1) / 2.0
        return height - 1 - int(round(HEM_POINT * abs(x - cx) / max(cx, 1)))

    def paint(face, x, y, w, h):
        cx = (w - 1) / 2.0
        if face in ('front', 'back'):
            xx = x if face == 'front' else w - 1 - x
            if abs(xx - cx) > halfw(y):
                return None
            low = bottom_row(xx, w)
            if y > low:
                return None
            if face == 'back':
                return inner(face, x, y, w, h)
            if y >= low - (1 if last else 0):
                return trim(face, x, y, w, h)         # Silbersaum (unten als Zickzack)
            if last and y == low - 3 and (xx - int(cx)) % 4 == 0:
                return ramp(SILVER, 0.7)             # Mondpunkte über dem Saum
            if i in (0, 3):
                # Mittelbahn vorn und hinten: hellerer Stoff zwischen zwei Silberlinien, Rauten im Wechsel.
                d = abs(xx - cx)
                if d < 2.6:
                    if d >= 1.6:
                        return ramp(SILVER, 0.56 + FACE_LIGHT[face])
                    if (y + stage * 2) % 4 == 1 and d < 0.6:
                        return ramp(SILVER, 0.8)
                    if (y + stage * 2) % 4 in (0, 2) and 0.4 < d < 1.6:
                        return ramp(SILVER, 0.5)
                    pal = NIGHT_ECHO if ECHO[0] else NIGHT
                    return ramp(pal, 0.74 - 0.1 * (y / h))
            return cloth(face, x, y, w, h)
        if face in ('left', 'right'):
            if halfw(y) < w / 2.0 - 0.6:
                return None
            if y > bottom_row(0, w):
                return None
            return ramp(NIGHT_ECHO if ECHO[0] else NIGHT, 0.3)
        if face == 'top':
            if abs(x - cx) > halfw(0):
                return None
            return ramp(NIGHT, 0.45)
        # Unterseite: nur unter der Zackenspitze (sonst schwebt ein Streifen unter dem Saum)
        if abs(x - cx) > (0.6 if last else halfw(height - 1)):
            return None
        return ramp(SILVER, 0.4) if last else ramp(LINING, 0.3)
    return paint


def skirt_stage(stage, pivot):
    r_top, r_bot, height, width = SKIRT[stage]
    tilt = math.degrees(math.atan2(r_bot - r_top, height))
    panels = []
    for i in range(6):
        yaw = i * 60.0
        a = math.radians(yaw)
        # Die Platte sitzt am oberen Rand der Stufe im Abstand r_top und kippt um tilt nach außen.
        panels.append(Part(f'skirt_{stage}_p{i}', (-math.sin(a) * r_top, 0, -math.cos(a) * r_top), (-tilt, yaw, 0),
                           cubes=[Cube(-width / 2.0, 0, -1, width, height, 2, skirt_panel(stage, i, r_top, r_bot, height, width))]))
    return Part(f'skirt_{stage}', pivot, deg(f'skirt_{stage}'), children=panels)


def torch(name, side):
    """Fackel: in sich geschlossener Knochen (fliegt als Geschoss TORCH). Ursprung im Griff, Schaft nach -y."""
    s = 400 + (0 if side < 0 else 20)
    band = silver(0.6, s + 1, rim=0.2, roundx=0.3)
    flame_part = Part(f'{name}_flame', (0, -30, 0), deg(f'{name}_flame'), cubes=[
        Cube(-3, -9, -1, 6, 9, 2, flame(s + 2, 6)),
        Cube(-1, -9, -3, 2, 9, 6, flame(s + 3, 6)),
    ])
    return Part(name, GRIP, deg(name), cubes=[
        *block(-2, 9, -2, 4, 2, 4, band, bevel=1),           # Fußkappe
        Cube(-1.5, -23, -1.5, 3, 32, 3, wood(0.5, s)),       # Schaft, 34 mit Kappe
        *block(-2, 3.5, -2, 4, 1.5, 4, band, bevel=1),        # Band unter der Faust
        *block(-2.5, -24, -2.5, 5, 2, 5, band, bevel=1),      # Kragen unter dem Kopf
        Cube(-3, -30, -3, 6, 6, 6, torch_head(s + 4)),        # Kopf 6 x 6 x 6
    ], children=[flame_part])


def key_part():
    metal = silver(0.62, 610, rim=0.22, roundx=0.0)
    bit = silver(0.56, 611, rim=0.2, roundx=0.0)
    return Part('key', (9.6, -1.5, -3.5), deg('key'), cubes=[
        Cube(-3, 0, -1, 6, 2, 2, metal),          # Ring 6 x 6 mit Loch
        Cube(-3, 4, -1, 6, 2, 2, metal),
        Cube(-3, 2, -1, 2, 2, 2, metal),
        Cube(1, 2, -1, 2, 2, 2, metal),
        Cube(-1.5, 6, -1.5, 3, 1, 3, bit),        # Bund
        Cube(-1, 7, -1, 2, 9, 2, metal),          # Halm, 16 mit Ring
        Cube(1, 10, -1, 3, 2, 2, bit),            # Bart: zwei Zinken
        Cube(1, 13.5, -1, 3, 2, 2, bit),
    ])


def dagger_part():
    pommel = silver(0.64, 620, rim=0.22, roundx=0.0)
    grip = lambda f, x, y, w, h: ramp(NIGHT, 0.42 + FACE_LIGHT[f] + (0.15 if y % 2 == 0 else 0.0))

    def blade(face, x, y, w, h):
        if face in ('front', 'back'):
            cx = (w - 1) / 2.0
            if y >= h - 2 and abs(x - cx) > (h - 1 - y) * 0.8 + 0.2:
                return None                     # Spitze
            if abs(x - cx) < 0.6:
                return ramp(SILVER, 0.86)       # Grat
            return ramp(SILVER, 0.62 + FACE_LIGHT[face] - 0.1 * (y / h))
        if face in ('left', 'right') and y >= h - 2:
            return None
        return ramp(SILVER, 0.7 + FACE_LIGHT[face])

    def guard(face, x, y, w, h):
        c = pommel(face, x, y, w, h)
        if face in ('front', 'back') and y == h - 1 and x in (0, w - 1):
            return None                          # Sichel: Enden nach oben
        return c
    return Part('dagger', (-9.6, -1.5, -3.5), deg('dagger'), cubes=[
        Cube(-1.5, -1, -1.5, 3, 2, 3, pommel),
        Cube(-1, 1, -1, 2, 4, 2, grip),
        Cube(-3, 5, -1.5, 6, 2, 3, guard),
        Cube(-1.5, 7, -1, 3, 7, 2, blade),
    ])


def head_part():
    """Kopf: Sechseck-Säule 12 breit, 12 hoch; jede zweite Fläche ein Gesicht. Darüber der Polos und die Schleier."""
    def nose(z, seed):
        return Cube(-0.5, -6, z, 1, 2, 1, skin(0.68, seed, roundx=0.0, grad=0.2))

    def mouth(name, z):
        def paint(face, x, y, w, h):
            if face in ('front', 'back') and y == 0:
                return ramp(MOONSKIN, 0.35)
            return hexc('#0c0816')
        return Part(name, (0, 0, 0), cubes=[Cube(-1.5, -3, z, 3, 2, 1, paint)])

    # Polos: sechs Wände (Zeichen über den Gesichtern: Mondsichel vorn, Schlüssel bei 240°, Dolch bei 120°).
    walls = []
    for k in range(6):
        sign = {4: 'key', 2: 'dagger'}.get(k, 'rosette')
        walls.append(Part(f'polos_{k}', (0, 0, 0), (6, k * 60.0, 0), cubes=[
            Cube(-4, -8, -7, 8, 8, 2, polos_panel(700 + k, sign)),
        ]))
    moon = glow_silver(200)
    crescent = Part('crescent', (0, -5, -7.6), cubes=[
        Cube(-2, 0, -1, 4, 2, 2, moon),
        Cube(-4, -2, -1, 2, 3, 2, moon),
        Cube(2, -2, -1, 2, 3, 2, moon),
        Cube(-4.5, -4, -1, 2, 2, 2, moon),
        Cube(2.5, -4, -1, 2, 2, 2, moon),
    ])
    polos = Part('polos', (0, -12, 0), deg('polos'), cubes=[
        Cube(-5, -1, -5, 10, 1, 10, hair(0.3, 730)),                # Haarkrone im offenen Polos
    ], children=walls + [crescent])

    def veil(side):
        s = 'l' if side > 0 else 'r'
        x0 = 0.5 if side > 0 else -5.5
        cloth = robe(0.46, 740 + side, folds=0.12, period=2.6, hem=1, stars=0.05)
        cloth2 = robe(0.42, 750 + side, folds=0.12, period=2.6, hem=1, stars=0.05)
        low = Part(f'veil_{s}_1', (x0 + 2.5, 14, 7.2), deg(f'veil_{s}_1'), cubes=[
            Cube(-2.5, 0, -1, 5, 16, 2, cloth2),
        ])
        # Ursprung auf der Kopfachse: Kotlin dreht die Schleier gegen den Kopf zurück, damit sie hinten bleiben.
        return Part(f'veil_{s}_0', (0, -11, 0), deg(f'veil_{s}_0'), cubes=[
            Cube(x0, 0, 6.2, 5, 14, 2, cloth),
        ], children=[low])

    head_b = Part('head_b', (0, 0, 0), (0, 60, 0), cubes=[
        Cube(-3.5, -12, -6, 7, 12, 12, face_paint(1, 'back'), dilate=0.02),
        nose(5.8, 331),
    ], children=[mouth('mouth_1', 5.05)])
    head_c = Part('head_c', (0, 0, 0), (0, 120, 0), cubes=[
        Cube(-3.5, -12, -6, 7, 12, 12, face_paint(2, 'front'), dilate=0.04),
        nose(-6.8, 332),
    ], children=[mouth('mouth_2', -6.05)])
    return Part('head', (0, -20, 0), deg('head'), cubes=[
        Cube(-3.5, -12, -6, 7, 12, 12, face_paint(0, 'front')),
        nose(-6.8, 330),
    ], children=[head_b, head_c, mouth('mouth_0', -6.05), polos, veil(1), veil(-1)])


def arm(s, side):
    """Lange, schmale Arme (Oberarm 5 x 17, Unterarm 5 auf 4 verjüngt, Hand 6); Kappenärmel, Armreif, Silberschiene."""
    sk = skin(0.48, 800 + side, roundx=0.38)
    sk2 = skin(0.46, 802 + side, roundx=0.38)
    sleeve = robe(0.5, 804 + side, folds=0.12, period=2.4, hem=1)
    ring = silver(0.52, 806 + side, rim=0.2, roundx=0.4)
    bracer = silver(0.44, 808 + side, rim=0.22, roundx=0.4, tarnish=0.72)

    def hand(face, x, y, w, h):
        v = 0.5 + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.12
        if face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += 0.3 * (0.35 - k * k)
        if face in ('front', 'back', 'left', 'right') and y in (2, 4) and x % 2 == 1:
            v -= 0.16                       # Fingerfugen
        if face == 'front' and y == 1:
            v += 0.12                       # Knöchel
        return ramp(MOONSKIN, v)

    fore = Part(f'fore_{s}', (0, 17, 0), deg(f'fore_{s}'), cubes=[
        *block(-2.5, 0, -2.5, 5, 8, 5, sk2, bevel=1),
        *block(-2, 7, -2, 4, 6, 4, sk2, bevel=0.5),           # verjüngt zum Handgelenk
        *block(-2.5, 11, -2.5, 5, 4, 5, bracer, bevel=1),     # Silberschiene
        *block(-2.5, 16, -2.5, 5, 6, 5, hand, bevel=1),       # Faust um den Schaft
        Cube(-1, 16.5, -3.6, 2, 3, 2, hand),                  # Daumen
    ], children=[torch(f'torch_{s}', side)])
    return Part(f'arm_{s}', (side * 11.5, -16, 0), deg(f'arm_{s}'), cubes=[
        *block(-3, -2.5, -3, 6, 4.5, 6, sleeve, bevel=1.5),   # Kappenärmel
        *block(-2.5, 0, -2.5, 5, 17, 5, sk, bevel=1),
        *block(-3, 5, -3, 6, 2, 6, ring, bevel=1.5),          # Armreif
    ], children=[fore])


def torso():
    over = robe(0.52, 900, folds=0.1, period=3.4, hem=1, stars=0.02)
    waist = robe(0.46, 901, folds=0.1, period=3.0)
    sk = skin(0.58, 902, roundx=0.3)
    belt = silver(0.58, 903, rim=0.22, roundx=0.3)
    torc = silver(0.66, 904, rim=0.25, roundx=0.4)

    def buckle(face, x, y, w, h):
        cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
        if face in ('front', 'back') and math.hypot(x - cx, y - cy) > 1.7:
            return None
        return ramp(SILVER, 0.84 + FACE_LIGHT[face] - 0.2 * (math.hypot(x - cx, y - cy) > 0.8))

    def brooch(face, x, y, w, h):
        c = torc(face, x, y, w, h)
        if face in SIDES and 0 < x < w - 1 and 0 < y < h - 1:
            return (ramp(SILVER, 0.92), None) if (x + y) % 2 == 0 else ramp(SILVER, 0.5)
        return mix(c, hexc('#1c1e27'), 0.3)

    def pendant(face, x, y, w, h):
        cx = (w - 1) / 2.0
        if face in ('front', 'back'):
            if y == 0 and abs(x - cx) < 1:
                return None                      # Mondsichel: Hörner nach oben
            return ramp(SILVER, 0.82 - 0.12 * y + FACE_LIGHT[face])
        return ramp(SILVER, 0.6 + FACE_LIGHT[face])

    return Part('chest', (0, 0, 0), deg('chest'), cubes=[
        *block(-7, -7, -4.5, 14, 7, 9, waist, bevel=2),        # Taille 14
        *block(-10, -16, -5, 20, 9, 10, over, bevel=2),        # Überschlag des Peplos über der Brust
        *block(-6.5, -18, -3.5, 13, 3, 7, sk, bevel=1.5),      # Schulteransatz
        *block(-2.5, -21, -2.5, 5, 5, 5, sk, bevel=1),         # Hals
        *block(-4.5, -18.5, -4.5, 9, 2, 9, torc, bevel=2),     # Halsreif
        *block(-7.5, -2.5, -5, 15, 3, 10, belt, bevel=2),      # Gürtel
        Cube(-1.5, -3, -5.6, 3, 4, 1, buckle),                 # Mondschnalle
        Cube(-11, -17.5, -3, 3, 3, 6, brooch),                 # Schulterfibeln (Schulterbreite 22)
        Cube(8, -17.5, -3, 3, 3, 6, brooch),
        Cube(-2, -16.5, -5.9, 4, 3, 1, pendant),               # Mondsichel am Halsreif
    ], children=[head_part(), arm('r', -1), arm('l', 1), key_part(), dagger_part()])


def build():
    skirt2 = skirt_stage(2, (0, SKIRT[1][2] - 1, 0))
    skirt1 = skirt_stage(1, (0, SKIRT[0][2] - 1, 0))
    skirt1.children.append(skirt2)
    skirt0 = skirt_stage(0, (0, -1, 0))
    skirt0.children.append(skirt1)
    body = Part('body', (0, -16, 0), deg('body'), children=[torso(), skirt0])
    return Model('hekate', 512, 128, [body])


def hekate():
    return build()


# =====================================================================================================================
# Trugbild
# =====================================================================================================================

def variants(model, albedo, glow):
    """hekate_echo: gleiche UVs, Maler mit ECHO = True (Fackeln erloschen, Gewand heller und kühler)."""
    ECHO[0] = True
    try:
        a = Image(model.tex_w, model.tex_h)
        g = Image(model.tex_w, model.tex_h)
        for c in model.cubes():
            u, v, w, h, d = c.u, c.v, c.w, c.h, c.d
            faces = [
                ('top', u + d, v, w, d), ('bottom', u + d + w, v, w, d), ('right', u, v + d, d, h),
                ('front', u + d, v + d, w, h), ('left', u + d + w, v + d, d, h), ('back', u + d + w + d, v + d, w, h),
            ]
            for name, fu, fv, fw, fh in faces:
                for iy in range(fh):
                    for ix in range(fw):
                        res = c.paint(name, ix, iy, fw, fh)
                        if res is None:
                            continue
                        if isinstance(res[0], tuple):
                            a.set(fu + ix, fv + iy, res[0])
                            if res[1] is not None:
                                g.set(fu + ix, fv + iy, res[1])
                        else:
                            a.set(fu + ix, fv + iy, res)
        save(a, 'textures/entity/boss/hekate_echo.png')
        save(g, 'textures/entity/boss/hekate_echo_glow.png')
    finally:
        ECHO[0] = False


# =====================================================================================================================
# Posen
# =====================================================================================================================

_RIG = Rig(build())


def _zero():
    return {name: (0.0, 0.0, 0.0) for name in _RIG.parts}


def hold_torch(rig, p, s, target, up, elbow, zhint=(0, 0, -1)):
    """Hand s an target, Fackel zeigt mit dem Kopf nach up."""
    rig.ik(p, f'arm_{s}', f'fore_{s}', GRIP, target, elbow)
    rig.aim(p, f'torch_{s}', _scale(up, -1), zhint)


def _solve_idle():
    p = _zero()
    for name in list(p):
        part = _RIG.parts[name]
        if name.startswith(('skirt_', 'polos_', 'head_b', 'head_c')) and name not in ('skirt_0', 'skirt_1', 'skirt_2'):
            p[name] = tuple(math.radians(a) for a in part.rot)
    p['veil_l_0'] = (0.04, 0.0, -0.06)
    p['veil_r_0'] = (0.04, 0.0, 0.06)
    p['veil_l_1'] = (0.22, 0.0, -0.05)
    p['veil_r_1'] = (0.22, 0.0, 0.05)
    p['key'] = (0.0, 0.0, -0.16)
    p['dagger'] = (0.0, 0.0, 0.16)
    hold_torch(_RIG, p, 'r', (-3.4, -23, -10), (0.7, -0.71, -0.08), (-16, -15, -2))
    hold_torch(_RIG, p, 'l', (3.4, -23, -10.6), (-0.7, -0.71, -0.08), (16, -15, -2))
    return p


IDLE.update(_solve_idle())
_RIG = Rig(build())

MOUTHS = ('mouth_0', 'mouth_1', 'mouth_2')
PI = math.pi


def _derive(base=None, **bones):
    pose = dict(base or IDLE)
    pose.update(bones)
    return pose


def _torches_in_left(p, spread=0.28, up=None):
    """Beide Fackeln in der linken Hand: torch_r wandert an den Griff der linken Faust, leicht gefächert."""
    rot_l, _ = _RIG.fk(p, 'torch_l')
    if up is None:
        up = _apply(rot_l, (0, -1, 0))
    side = _norm(_cross(up, (0, 0, -1))) if abs(up[2]) < 0.9 else (1, 0, 0)
    fan = _norm(_add(up, _scale(side, -spread)))
    _RIG.aim(p, 'torch_r', _scale(fan, -1), (0, 0, -1))
    _RIG.place(p, 'torch_r', _RIG.world(p, 'fore_l', (0.0, GRIP[1] - 1.5, -0.8)))


def _in_right_hand(p, name, grip, ydir, zhint=(0, 0, -1)):
    """Schlüssel oder Dolch (Kinder der Brust) in die rechte Faust: Griffpunkt auf den Griff, +y nach ydir."""
    _RIG.aim(p, name, ydir, zhint)
    _RIG.place(p, name, _RIG.world(p, 'fore_r', GRIP), grip)


def _skirt(p, s0=(0, 0, 0), s1=(0, 0, 0), s2=(0, 0, 0)):
    p['skirt_0'], p['skirt_1'], p['skirt_2'] = s0, s1, s2


def _flare(p, extra):
    """Saum-Platten der untersten Stufe weiter nach außen kippen (extra in Radiant)."""
    for i in range(6):
        pitch, yaw, roll = IDLE[f'skirt_2_p{i}'][:3]
        p[f'skirt_2_p{i}'] = (pitch - extra, yaw, roll)


# --- 1 TORCH_SWEEP -------------------------------------------------------------------------------------------------
def _sweep_windup_r():
    p = _derive(body=(0.0, 0.25, 0.0, 0.0, -1.0, 1.0), chest=(-0.08, 0.55, -0.06), head=(0.05, -0.55, 0.0))
    _skirt(p, (0.0, -0.12, 0.0), (0.04, -0.12, 0.02), (0.06, -0.14, 0.03))
    hold_torch(_RIG, p, 'r', (-24, -30, 12), (-0.45, -0.65, 0.6), (-20, -22, 4))
    return p


def _sweep_hit_r():
    p = _derive(body=(0.08, -0.3, 0.0, 0.0, 0.0, -1.5), chest=(0.18, -0.6, 0.08), head=(0.1, 0.45, 0.0))
    _skirt(p, (0.0, 0.14, 0.0), (-0.05, 0.16, -0.03), (-0.08, 0.18, -0.05))
    hold_torch(_RIG, p, 'r', (12, -26, -22), (0.7, -0.15, -0.7), (-6, -22, -16))
    return p


def _sweep_windup_l():
    p = _derive(body=(0.0, -0.25, 0.0, 0.0, -1.0, 1.0), chest=(-0.08, -0.55, 0.06), head=(0.05, 0.55, 0.0))
    _skirt(p, (0.0, 0.12, 0.0), (0.04, 0.12, -0.02), (0.06, 0.14, -0.03))
    hold_torch(_RIG, p, 'l', (24, -30, 12), (0.45, -0.65, 0.6), (20, -22, 4))
    return p


def _sweep_hit_l():
    p = _derive(body=(0.08, 0.3, 0.0, 0.0, 0.0, -1.5), chest=(0.18, 0.6, -0.08), head=(0.1, -0.45, 0.0))
    _skirt(p, (0.0, -0.14, 0.0), (-0.05, -0.16, 0.03), (-0.08, -0.18, 0.05))
    hold_torch(_RIG, p, 'l', (-12, -26, -22), (-0.7, -0.15, -0.7), (6, -22, -16))
    return p


# --- 2 TORCH_THROW -------------------------------------------------------------------------------------------------
def _throw_windup():
    p = _derive(body=(-0.12, -0.15, 0.0, 0.0, -2.0, 2.0), chest=(-0.28, -0.2, 0.08), head=(-0.15, 0.2, 0.0))
    _skirt(p, (0.06, 0.0, 0.0), (0.08, 0.0, 0.0), (0.1, 0.0, 0.0))
    hold_torch(_RIG, p, 'l', (9, -62, 10), (0.15, -0.45, 0.88), (18, -44, 6))
    return p


def _throw_empty():
    p = _derive(body=(0.14, 0.2, 0.0, 0.0, 0.0, -2.0), chest=(0.3, 0.25, -0.06), head=(0.05, -0.2, 0.0))
    _skirt(p, (-0.04, 0.0, 0.0), (-0.1, 0.0, 0.0), (-0.14, 0.0, 0.0))
    hold_torch(_RIG, p, 'l', (6, -20, -24), (0.1, 0.4, -0.9), (16, -22, -10))
    p['torch_l'] = None
    return p


# --- 3 PATH --------------------------------------------------------------------------------------------------------
def _path_windup():
    p = _derive(body=(-0.06, 0.0, 0.0, 0.0, -3.0, 1.0), chest=(-0.16, 0.0, 0.0), head=(-0.2, 0.0, 0.0))
    _skirt(p, (0.03, 0.0, 0.0), (0.05, 0.0, 0.0), (0.06, 0.0, 0.0))
    hold_torch(_RIG, p, 'r', (-7, -60, -4), (-0.12, -1, 0.05), (-20, -40, 0))
    hold_torch(_RIG, p, 'l', (7, -60, -4), (0.12, -1, 0.05), (20, -40, 0))
    return p


def _path_hit():
    p = _derive(body=(0.12, 0.0, 0.0, 0.0, 3.0, -1.5), chest=(0.42, 0.0, 0.0), head=(0.4, 0.0, 0.0))
    _skirt(p, (-0.05, 0.0, 0.0), (-0.08, 0.0, 0.0), (-0.1, 0.0, 0.0))
    _flare(p, 0.15)
    hold_torch(_RIG, p, 'r', (-6, -10, -18), (-0.05, 1, -0.15), (-16, -20, -8))
    hold_torch(_RIG, p, 'l', (6, -10, -18), (0.05, 1, -0.15), (16, -20, -8))
    return p


# --- 4 KEY_TURN ----------------------------------------------------------------------------------------------------
def _key_raise():
    p = _derive(body=(-0.04, 0.0, 0.0, 0.0, -2.0, 0.0), chest=(-0.1, 0.1, 0.04), head=(-0.3, -0.05, 0.0))
    hold_torch(_RIG, p, 'l', (4, -24, -11), (-0.35, -0.94, -0.05), (16, -16, -4))
    _RIG.ik(p, 'arm_r', 'fore_r', GRIP, (-4, -64, -3), (-18, -44, 0))
    _in_right_hand(p, 'key', KEY_GRIP, (0, -1, 0))
    _torches_in_left(p)
    return p


def _key_turn():
    p = _key_raise()
    b = p['body']
    p['body'] = (b[0], b[1] + PI / 2, b[2]) + tuple(b[3:])
    _skirt(p, (0.0, -0.2, 0.0), (0.0, -0.18, 0.0), (0.0, -0.16, 0.0))
    return p


# --- 5 STEP --------------------------------------------------------------------------------------------------------
def _step_fold():
    p = _derive(body=(0.0, 0.3, 0.0, 0.0, -6.0, 0.0), chest=(0.1, 0.0, 0.0), head=(0.3, 0.0, 0.0))
    _skirt(p, (0.0, 0.3, 0.0), (0.0, 0.6, 0.0, 0.0, -9.0, 0.0), (0.0, 0.7, 0.0, 0.0, -9.5, 0.0))
    _flare(p, -0.2)
    hold_torch(_RIG, p, 'r', (6, -34, -9), (0.2, -0.98, 0.0), (-6, -24, -10))
    hold_torch(_RIG, p, 'l', (-6, -34, -10), (-0.2, -0.98, 0.0), (6, -24, -10))
    return p


def _step_lunge():
    p = _derive(body=(0.18, -0.15, 0.0, 0.0, 1.0, -4.0), chest=(0.32, -0.25, 0.0), head=(0.0, 0.3, 0.0))
    _skirt(p, (-0.12, 0.0, 0.0), (-0.22, 0.0, 0.0), (-0.28, 0.0, 0.0))
    hold_torch(_RIG, p, 'l', (16, -18, 8), (0.3, -0.9, 0.3), (18, -24, 2))
    _RIG.ik(p, 'arm_r', 'fore_r', GRIP, (-3, -28, -32), (-14, -28, -14))
    _in_right_hand(p, 'dagger', DAGGER_GRIP, (0, 0.08, -1), (0, -1, 0))
    _torches_in_left(p)
    return p


def _step_draw():
    """Nach dem Erscheinen: Dolch schon vorgestreckt, Arm angewinkelt zurückgenommen, Körper eingedreht."""
    p = _derive(body=(0.02, 0.3, 0.0, 0.0, -1.0, 2.0), chest=(0.05, 0.35, 0.0), head=(0.05, -0.4, 0.0))
    _skirt(p, (0.0, -0.08, 0.0), (0.03, -0.1, 0.0), (0.05, -0.12, 0.0))
    hold_torch(_RIG, p, 'l', (14, -20, 2), (0.35, -0.92, 0.1), (18, -22, 0))
    _RIG.ik(p, 'arm_r', 'fore_r', GRIP, (-12, -24, -6), (-18, -20, 6))
    _in_right_hand(p, 'dagger', DAGGER_GRIP, (0.05, 0.05, -1), (0, -1, 0))
    _torches_in_left(p)
    return p


# --- 6 TRIFORM -----------------------------------------------------------------------------------------------------
def _triform_windup():
    p = _derive(body=(0.0, 0.0, 0.0, 0.0, -2.0, 0.0), chest=(-0.1, 0.0, 0.0), head=(-0.08, 0.0, 0.0))
    _flare(p, 0.1)
    hold_torch(_RIG, p, 'r', (-32, -33, -2), (-1, -0.05, -0.05), (-22, -34, 4))
    hold_torch(_RIG, p, 'l', (32, -33, -2), (1, -0.05, -0.05), (22, -34, 4))
    return p


def _triform_torch():
    p = _derive(body=(0.12, 0.0, 0.0, 0.0, 0.0, -2.0), chest=(0.22, 0.0, 0.0), head=(0.0, 0.0, 0.0))
    _skirt(p, (-0.04, 0.0, 0.0), (-0.08, 0.0, 0.0), (-0.1, 0.0, 0.0))
    hold_torch(_RIG, p, 'r', (-5, -30, -28), (-0.1, -0.15, -1), (-16, -28, -12))
    hold_torch(_RIG, p, 'l', (5, -30, -28), (0.1, -0.15, -1), (16, -28, -12))
    return p


def _triform_key():
    p = _derive(body=(0.12, 0.1, 0.0, 0.0, 0.0, -2.0), chest=(0.22, 0.2, 0.0), head=(0.0, -0.2, 0.0))
    _skirt(p, (-0.04, 0.0, 0.0), (-0.08, 0.0, 0.0), (-0.1, 0.0, 0.0))
    hold_torch(_RIG, p, 'l', (24, -26, 6), (0.6, -0.8, 0.1), (20, -22, 2))
    _RIG.ik(p, 'arm_r', 'fore_r', GRIP, (-2, -30, -32), (-14, -30, -14))
    _in_right_hand(p, 'key', KEY_GRIP, (0, 0.0, -1), (0, -1, 0))
    _torches_in_left(p)
    return p


def _triform_dagger():
    p = _derive(body=(0.2, 0.0, 0.0, 0.0, 2.0, -2.0), chest=(0.55, 0.0, 0.0), head=(0.35, 0.0, 0.0))
    _skirt(p, (-0.06, 0.0, 0.0), (-0.12, 0.0, 0.0), (-0.14, 0.0, 0.0))
    _flare(p, 0.12)
    hold_torch(_RIG, p, 'l', (18, -20, 4), (0.5, -0.85, 0.15), (18, -24, 2))
    _RIG.ik(p, 'arm_r', 'fore_r', GRIP, (-2, -6, -18), (-14, -18, -10))
    _in_right_hand(p, 'dagger', DAGGER_GRIP, (0, 1, -0.15))
    _torches_in_left(p)
    return p


# --- 7 ECLIPSE -----------------------------------------------------------------------------------------------------
def _eclipse():
    p = _derive(body=(-0.05, 0.0, 0.0, 0.0, -8.0, 0.0), chest=(-0.14, 0.0, 0.0), head=(-0.5, 0.0, 0.0))
    _skirt(p, (0.02, 0.0, 0.0), (0.04, 0.0, 0.0), (0.06, 0.0, 0.0))
    _flare(p, -0.06)
    hold_torch(_RIG, p, 'r', (-22, -8, -6), (-0.55, 0.83, -0.05), (-20, -24, 0))
    hold_torch(_RIG, p, 'l', (22, -8, -6), (0.55, 0.83, -0.05), (20, -24, 0))
    return p


# --- 8 STAGGER -----------------------------------------------------------------------------------------------------
def _stagger():
    p = _derive(body=(0.1, 0.12, 0.06, 0.0, 8.0, 0.0), chest=(0.5, 0.0, 0.1), head=(0.55, 0.15, 0.0))
    _skirt(p, (0.0, 0.0, 0.0), (0.0, 0.0, 0.0, 0.0, -2.0, 0.0), (0.0, 0.0, 0.0))
    _flare(p, 0.45)
    hold_torch(_RIG, p, 'r', (-17, 0, -8), (-0.95, 0.3, -0.1), (-18, -16, 0))
    hold_torch(_RIG, p, 'l', (17, 0, -8), (0.95, 0.3, -0.1), (18, -16, 0))
    return p


# --- 9 HOWL --------------------------------------------------------------------------------------------------------
def _howl_windup():
    p = _derive(body=(0.08, 0.0, 0.0, 0.0, 1.5, 0.0), chest=(0.3, 0.0, 0.0), head=(0.35, 0.0, 0.0))
    _flare(p, -0.05)
    # Sammeln: Fackeln gesenkt und nach außen gekippt, Flammen unten; daraus reißt das Heulen sie nach oben.
    hold_torch(_RIG, p, 'r', (-4, -12, -11), (-0.42, 0.88, -0.2), (-15, -14, -4))
    hold_torch(_RIG, p, 'l', (4, -12, -11.6), (0.42, 0.88, -0.2), (15, -14, -4))
    return p


def _howl_hit():
    p = _derive(body=(-0.08, 0.0, 0.0, 0.0, -3.0, 1.0), chest=(-0.3, 0.0, 0.0), head=(-0.6, 0.0, 0.0))
    _flare(p, 0.2)
    hold_torch(_RIG, p, 'r', (-10, -60, 0), (-0.08, -1, 0.04), (-22, -42, 4))
    hold_torch(_RIG, p, 'l', (10, -60, 0), (0.08, -1, 0.04), (22, -42, 4))
    return p


# --- 10 YIELD ------------------------------------------------------------------------------------------------------
def _yield():
    p = _derive(body=(0.08, -0.35, 0.0, 6.0, 1.0, 0.0), chest=(0.32, 0.15, 0.0), head=(0.3, 0.1, 0.0))
    hold_torch(_RIG, p, 'l', (10, -14, -6), (0.1, -1, 0.05), (18, -18, 0))
    _RIG.ik(p, 'arm_r', 'fore_r', GRIP, (-10, -24, -26), (-18, -26, -10))
    _in_right_hand(p, 'key', KEY_GRIP, (0, 0.1, -1), (0, -1, 0))
    _torches_in_left(p, spread=0.18)
    return p


POSES_RAW = {'idle': dict(IDLE)}
for _name, _fn in (('sweep_windup_r', _sweep_windup_r), ('sweep_hit_r', _sweep_hit_r),
                   ('sweep_windup_l', _sweep_windup_l), ('sweep_hit_l', _sweep_hit_l),
                   ('throw_windup', _throw_windup), ('throw_empty', _throw_empty),
                   ('path_windup', _path_windup), ('path_hit', _path_hit),
                   ('key_raise', _key_raise), ('key_turn', _key_turn),
                   ('step_fold', _step_fold), ('step_draw', _step_draw), ('step_lunge', _step_lunge),
                   ('triform_windup', _triform_windup), ('triform_torch', _triform_torch),
                   ('triform_key', _triform_key), ('triform_dagger', _triform_dagger),
                   ('eclipse', _eclipse), ('stagger', _stagger),
                   ('howl_windup', _howl_windup), ('howl_hit', _howl_hit), ('yield', _yield)):
    POSES_RAW[_name] = _fn()


def _rounded(v):
    return None if v is None else tuple(round(float(x), 4) for x in v)


POSES = {}
for _name, _pose in POSES_RAW.items():
    _p = {k: _rounded(v) for k, v in _pose.items()}
    if _name != 'howl_hit':
        for _m in MOUTHS:
            _p[_m] = None          # Der offene Mund ist nur beim Heulen sichtbar (Kotlin schaltet visible).
    POSES[_name] = _p

HEIGHT_EXCLUDE = ('torch_r', 'torch_l', 'crescent')

# =====================================================================================================================
# Kotlin-Abgleich: Posentabelle für HekateModel.kt (gleiche Winkel wie POSES)
# =====================================================================================================================

KOTLIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'kotlin', 'dev', 'henny',
                      'hha', 'client', 'model', 'HekateModel.kt')
# Knochen, die die Schlüsselposen bewegen (Reihenfolge = Datenlayout der Tabelle in Kotlin).
TABLE_BONES = ['body', 'chest', 'head', 'veil_l_0', 'veil_l_1', 'veil_r_0', 'veil_r_1',
               'arm_r', 'fore_r', 'torch_r', 'arm_l', 'fore_l', 'torch_l', 'key', 'dagger',
               'skirt_0', 'skirt_1', 'skirt_2'] + [f'skirt_2_p{i}' for i in range(6)]


def _delta(pose):
    """Nur die Knochen, die sich gegenüber der Ruhe ändern."""
    out = {}
    for b in TABLE_BONES:
        v = pose.get(b)
        if v is None:
            continue
        v = tuple(v) + (0.0, 0.0, 0.0) if len(v) == 3 else tuple(v)
        r = tuple(IDLE[b]) + (0.0, 0.0, 0.0) if len(IDLE[b]) == 3 else tuple(IDLE[b])
        if any(abs(a - c) > 1e-4 for a, c in zip(v, r)):
            out[b] = v
    return out


def kotlin_table():
    parents = []
    for b in TABLE_BONES:
        par = _RIG.parent[b]
        while par is not None and par not in TABLE_BONES:
            par = _RIG.parent[par]
        parents.append(str(TABLE_BONES.index(par)) if par else '-1')
    paths = []
    for b in TABLE_BONES:
        chain, n = [], b
        while n is not None:
            chain.append(n)
            n = _RIG.parent[n]
        paths.append('/'.join(reversed(chain)))
    lines = ['        val BONES = arrayOf(' + ', '.join('"' + p + '"' for p in paths) + ')']
    for name, pose in POSES.items():
        if name == 'idle':
            continue
        delta = _delta(pose)
        vals = []
        for b in TABLE_BONES:
            v = delta.get(b)
            vals.append('N, N, N, N, N, N' if v is None else ', '.join(f'{x:.4f}f' for x in v))
        lines.append(f'        val {name.upper()} = floatArrayOf(')
        for i in range(0, len(vals), 2):
            lines.append('            ' + ', '.join(vals[i:i + 2]) + ',')
        lines.append('        )')
    return '\n'.join(lines)


def sync_kotlin():
    with open(KOTLIN, encoding='utf-8') as f:
        src = f.read()
    begin, end = '        // POSES BEGIN (generiert aus tools/hha_assets/hekate_model.py --kotlin)', '        // POSES END'
    pattern = re.compile(re.escape(begin) + '.*?' + re.escape(end), re.S)
    if not pattern.search(src):
        raise SystemExit('Marken für die Posentabelle fehlen in HekateModel.kt')
    src = pattern.sub(lambda _: begin + '\n' + kotlin_table() + '\n' + end, src)
    with open(KOTLIN, 'w', encoding='utf-8', newline='\n') as f:
        f.write(src)
    print(f'Posentabelle geschrieben: {len(POSES) - 1} Posen, {len(TABLE_BONES)} Knochen')


if __name__ == '__main__' and '--kotlin' in sys.argv:
    sync_kotlin()
