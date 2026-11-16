"""Der Kurete (Himmelssoldat des Olymp): junger Krieger mit großem Rundschild und doppelt mannshohem Speer
(Brief tmp/overhaul2/models/olymp_gegner.md, Registry `kurete`). Modell, Textur und POSES.

Erkennungsmerkmal: achteckiger Schild links (Durchmesser 20, größer als der Rumpf), Speer rechts (84 lang), offener
Bronzehelm mit nach vorn gebogenem blauem Busch. Keine Leuchtmaske.

Koordinaten: 1/16 Block vor dem Renderer-Faktor 0,70, +y nach unten, -z vorn, Füße bei y = 24, Helmscheitel bei -24
(48 Einheiten → 2,1 Blöcke). Die rechte Körperseite liegt bei -x. Knochen (client/model/OlympMobModels.kt):

    body ─ chest ─ head ── plume
    │        ├─ arm_r ── fore_r ── spear
    │        └─ arm_l ── fore_l ── shield
    ├─ leg_r ── shin_r
    └─ leg_l ── shin_l

Diese Datei enthält außerdem die kleinen Werkzeuge, die alle drei Olymp-Gegner teilen (Kinematik zum Ausrichten von
Knochen, Kotlin-Abgleich der Posentabellen). `griffin_model.py` und `automaton_model.py` importieren sie von hier.

    python tools/hha_assets/kurete_model.py --kotlin    # schreibt die Posentabelle nach OlympMobModels.kt
"""
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from bosskit import Cube  # noqa: E402
from modelkit import LEATHER, Model, Part, hexc, mix, ramp, rgb, speckle  # noqa: E402
from imp_model import SkylineModel  # noqa: E402  (dichter Atlas-Packer, nur gelesen)
from preview_model import apply as _apply, mat_mul as _mul, rot_zyx as _rot  # noqa: E402

FACE_LIGHT = {'top': 0.13, 'bottom': -0.22, 'front': 0.0, 'back': -0.1, 'left': -0.06, 'right': -0.06}
SIDES = ('front', 'back', 'left', 'right')

# =====================================================================================================================
# Gemeinsame Maler-Bausteine (auch für Greif und Wächterin)
# =====================================================================================================================


def surf(pal, base=0.55, grad=0.16, seed=0, roundx=0.0, ridge=0.0, noise=0.03, seam=0.0):
    """Glatte Fläche wie bei Hermes: Flächenlicht, Verlauf von oben nach unten, leichtes Korn, optional Rundung
    (heller in der Mitte, dunkler an den senkrechten Kanten), Lichtkante oben und dunkle Fuge unten."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad + (speckle(x, y, seed) - 0.5) * noise * 2
        if roundx and face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += roundx * (0.35 - k * k)
        if face in SIDES and h > 2:
            if ridge and y == 0:
                v += ridge
            elif seam and y == h - 1:
                v -= seam
        return ramp(pal, v)
    return paint


def metal(pal, base=0.55, seed=0, grad=0.18, roundx=0.22, rim=0.2, seam=0.18, rivets=0, glint=True):
    """Getriebenes Metall: Rundung, heller Grat oben, dunkle Fuge unten, Glanzpunkte, optional Nietreihe."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + grad * (0.5 - y / max(h - 1, 1)) + (speckle(x, y, seed) - 0.5) * 0.08
        if face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += roundx * (0.35 - k * k)
        if face == 'top' and (x in (0, w - 1) or y in (0, h - 1)):
            v += rim * 0.6
        if face in SIDES and h > 2:
            if y == 0:
                v += rim
            elif y == h - 1 and h >= 4:
                v -= seam
            if rivets and h >= 5 and w >= 5 and y == 1 and (x - 1) % rivets == 0 and 0 < x < w - 1:
                return ramp(pal, min(0.97, v + 0.32))
        if glint and (x * 7 + y * 11 + seed) % 41 == 0:
            v += 0.14
        return ramp(pal, v)
    return paint


# =====================================================================================================================
# Kinematik (gemeinsam): Vorwärtsrechnung und Ausrichten einzelner Knochen. Gerechnet wird nur hier; Kotlin bekommt
# die fertigen Winkel.
# =====================================================================================================================

_EYE = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]


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
    y = _norm(ydir)
    z = _sub(zhint, _scale(y, _dot(zhint, y)))
    if _dot(z, z) < 1e-6:
        z = _sub((1, 0, 0), _scale(y, y[0]))
    z = _norm(z)
    x = _cross(y, z)
    return [[x[i], y[i], z[i]] for i in range(3)]


class Rig:
    """Knochenbaum eines Modells für Rechnungen an einer Pose (Dict Name -> 3 oder 6 Zahlen)."""

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

    def aim(self, pose, name, ydir, zhint=(0, 0, -1)):
        """Dreht den Knochen so, dass seine lokale +y-Achse in Weltrichtung ydir zeigt (+z möglichst nach zhint)."""
        self.set_world_rot(pose, name, frame(ydir, zhint))

    def ik(self, pose, arm, fore, grip, target, elbow=None, weight=0.05, elbow_weight=0.04):
        """Zwei-Knochen-IK (wie beim Kerkermeister): Oberarm (pitch, yaw, roll) und Unterarm (pitch), bis der
        Griffpunkt grip (im Unterarm) den Weltpunkt target trifft; elbow zieht den Ellbogen leicht zu einem Punkt."""
        names = [(arm, 0), (arm, 1), (arm, 2), (fore, 0)]
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
            if not -2.7 <= vals[3] <= 0.05:
                return 1e9
            d = _sub(self.world(pose, fore, grip), target)
            c = _dot(d, d)
            if elbow is not None:
                e = _sub(self.world(pose, fore), elbow)
                c += elbow_weight * _dot(e, e)
            return c + weight * sum((a - b) ** 2 for a, b in zip(vals, start))

        best = list(start)
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


def rounded(pose):
    return {k: (None if v is None else tuple(round(float(x), 4) for x in v)) for k, v in pose.items()}


# =====================================================================================================================
# Kotlin-Abgleich (gemeinsam): Jedes Modell schreibt seine Tabelle zwischen eigene Marken in OlympMobModels.kt.
# =====================================================================================================================

KOTLIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'kotlin', 'dev', 'henny',
                      'hha', 'client', 'model', 'OlympMobModels.kt')


def kotlin_table(model, poses, names, rest):
    """BONES, PARENTS und je Pose ein FloatArray (6 Zahlen je Knochen, NaN = Knochen folgt der Ruhe/dem Gang)."""
    order = [p.name for root in model.parts for p in root.walk()]
    rig = Rig(model)
    parents = [str(order.index(rig.parent[b])) if rig.parent[b] else '-1' for b in order]
    lines = [f'        val BONES = arrayOf({", ".join(chr(34) + b + chr(34) for b in order)})',
             f'        val PARENTS = intArrayOf({", ".join(parents)})']
    for name in names:
        pose = poses[name]
        vals = []
        for b in order:
            v = pose.get(b)
            if v is None or tuple(v) == tuple(rest.get(b, ())):
                vals.append('N, N, N, N, N, N')
            else:
                v = tuple(v) + (0.0, 0.0, 0.0) if len(v) == 3 else tuple(v)
                vals.append(', '.join(f'{x:.4f}f' for x in v))
        lines.append(f'        val {name.upper()} = floatArrayOf(')
        for i in range(0, len(vals), 2):
            lines.append('            ' + ', '.join(vals[i:i + 2]) + ',')
        lines.append('        )')
    return '\n'.join(lines)


def sync_kotlin(tag, table):
    with open(KOTLIN, encoding='utf-8') as f:
        src = f.read()
    begin = f'        // {tag} POSES BEGIN (generiert aus tools/hha_assets/{tag.lower()}_model.py --kotlin)'
    end = f'        // {tag} POSES END'
    pattern = re.compile(re.escape(begin) + '.*?' + re.escape(end), re.S)
    if not pattern.search(src):
        raise SystemExit(f'Marken für die {tag}-Posentabelle fehlen in OlympMobModels.kt')
    src = pattern.sub(lambda _: begin + '\n' + table + '\n' + end, src)
    with open(KOTLIN, 'w', encoding='utf-8', newline='\n') as f:
        f.write(src)
    print(f'{tag}: Posentabelle nach {os.path.abspath(KOTLIN)}')


# =====================================================================================================================
# Kurete: Material
# =====================================================================================================================

BRONZE = rgb('#3a2410', '#5a3a16', '#8a5a22', '#b47c32', '#e6b85a', '#fff2c8')
TUNIC = rgb('#5e6a80', '#9aa6b8', '#c4ccda', '#dfe6f0', '#ffffff')
SKIN = rgb('#6a4630', '#9a6a4c', '#c08a66', '#dcae88', '#f2d0ae')
PLUME = rgb('#0e2450', '#1c4a9a', '#2f6fd0', '#6fb6ff', '#c8e6ff')
WOOD = rgb('#2a1a0c', '#4e3218', '#74502a', '#9a7040', '#c09a62')
LEATH = LEATHER


def pleats(seed, hem_rows=2):
    """Tunika: senkrechte weiche Falten, unten eine blaue Saumborte mit hellem Goldfaden."""
    def paint(face, x, y, w, h):
        if face in SIDES and y >= h - hem_rows:
            if y == h - hem_rows:
                return ramp(BRONZE, 0.78 + FACE_LIGHT[face])
            return ramp(PLUME, 0.42 + FACE_LIGHT[face] + (0.08 if (x + y) % 4 == 0 else 0.0))
        v = 0.6 + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.16 + (speckle(x, y, seed) - 0.5) * 0.05
        if face in SIDES:
            v += 0.12 * math.sin(x * 1.9 + seed)
        return ramp(TUNIC, v)
    return paint


def pteruges(seed):
    """Lederstreifen über der Tunika: Streifen 2 breit mit Spalt, Bronzeniete oben, unten abgerundet."""
    def paint(face, x, y, w, h):
        if face in ('top', 'bottom'):
            return ramp(LEATH, 0.35 + FACE_LIGHT[face])
        strip = x % 3
        if strip == 2 and y >= 1:
            return None                                              # Spalt zwischen den Streifen
        if y == h - 1 and strip == 1 and (x // 3) % 2 == 0:
            return None                                              # abwechselnd kürzer
        if y == 0:
            return ramp(BRONZE, 0.66 + FACE_LIGHT[face])             # Bronzeleiste
        if y == 1 and strip == 0:
            return ramp(BRONZE, 0.9)                                 # Niet
        v = 0.55 + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.2 + (speckle(x, y, seed) - 0.5) * 0.06
        if strip == 1:
            v -= 0.08
        return ramp(LEATH, v)
    return paint


def linen(seed, base=0.62):
    """Leinenpanzer (Linothorax): weißes, in Lagen verleimtes Leinen, feine waagerechte Lagenlinien."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.16 + (speckle(x, y, seed) - 0.5) * 0.05
        if face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += 0.2 * (0.35 - k * k)
        if face in SIDES and y % 3 == 2:
            v -= 0.05
        return ramp(TUNIC, v)
    return paint


def cuirass(face, x, y, w, h):
    """Oberteil des Leinenpanzers: Schulterklappen (Epomiden) als blaue Bahnen mit Goldkante, Brust mit Bronzeschild
    (Gorgoneion-Scheibe), Mittelnaht."""
    base = linen(401, 0.64)
    if face in ('front', 'back'):
        cx = (w - 1) / 2.0
        d = abs(x - cx)
        if y <= 1 and 2.5 <= d <= 5.5 and face == 'front' or (face == 'back' and y <= 1 and 2.5 <= d <= 5.5):
            return ramp(PLUME, 0.5 + FACE_LIGHT[face] - y * 0.06)   # Epomiden über den Schultern
        if y == 2 and 2.5 <= d <= 5.5:
            return ramp(BRONZE, 0.8 + FACE_LIGHT[face])            # Goldkante der Epomiden
        if face == 'front':
            r = math.hypot(x - cx, (y - 4.2) * 1.1)
            if r < 1.4:
                return ramp(PLUME, 0.62)                             # Scheibe: blauer Kern
            if r < 2.6:
                return ramp(BRONZE, 0.82 - r * 0.08)                 # Bronzescheibe auf der Brust
        if y == h - 1:
            return ramp(PLUME, 0.4 + FACE_LIGHT[face])
    if face == 'top' and 2 <= abs(x - (w - 1) / 2.0) <= 5.5:
        return ramp(PLUME, 0.6)
    if face in ('left', 'right') and y == h - 1:
        return ramp(PLUME, 0.4 + FACE_LIGHT[face])
    return base(face, x, y, w, h)


def abdomen(face, x, y, w, h):
    """Unterteil des Leinenpanzers: Mäanderborte in Blau oben, darunter Leinen, unten eine Bronzekante."""
    base = linen(402, 0.58)
    if face in SIDES:
        if y == 1:
            return ramp(PLUME, 0.45 + FACE_LIGHT[face]) if (x + (y // 1)) % 4 != 0 else ramp(TUNIC, 0.85)
        if y in (0, 2):
            return ramp(PLUME, 0.36 + FACE_LIGHT[face])
        if y == h - 1:
            return ramp(BRONZE, 0.62 + FACE_LIGHT[face])
    return base(face, x, y, w, h)


def belt(face, x, y, w, h):
    base = surf(LEATH, 0.42, 0.1, 403, roundx=0.1, ridge=0.1)
    if face == 'front' and abs(x - (w - 1) / 2.0) < 1.6:
        return ramp(BRONZE, 0.86 if y == 0 else 0.62)                # Schnalle
    if face in SIDES and y == 1 and x % 3 == 1:
        return ramp(BRONZE, 0.7)
    return base(face, x, y, w, h)


def helmet(face, x, y, w, h):
    """Offener Bronzehelm auf dem Kopf (8 x 8 x 8): vorn das Gesicht mit Augenbalken, Nasenschutz und Wangenklappen,
    oben und hinten die Helmglocke mit Mittelgrat."""
    bronze = metal(BRONZE, 0.6, 404, grad=0.28, roundx=0.26, rim=0.12, seam=0.12)
    skin = surf(SKIN, 0.62, 0.1, 405, roundx=0.2)
    if face == 'front':
        if y <= 1:
            return ramp(BRONZE, 0.78 - y * 0.12 + (0.06 if x in (3, 4) else 0.0))   # Stirnband der Glocke
        if y == 2:
            return ramp(BRONZE, 0.46 if x not in (3, 4) else 0.7)    # Augenbalken (Brauenschutz)
        if x in (3, 4) and y <= 5:
            return ramp(BRONZE, 0.66 - (y - 2) * 0.06 + (0.06 if x == 3 else 0.0))  # Nasenschutz
        if x in (0, 7):
            return ramp(BRONZE, 0.56 - (y - 3) * 0.04)               # Wangenklappen
        if y == 3:
            if x in (1, 6):
                return hexc('#f2eee6')
            if x in (2, 5):
                return hexc('#203a6a')                               # Augen (dunkelblau)
        if y == 4 and x in (1, 2, 5, 6):
            return ramp(SKIN, 0.42)                                  # Schatten unter den Augen
        if y == 6 and x in (3, 4):
            return ramp(SKIN, 0.3)                                   # Mund
        if y == 7 and x in (1, 6):
            return ramp(BRONZE, 0.5)                                 # Wangenklappen-Spitze
        return skin(face, x, y, w, h)
    if face == 'top' and x in (3, 4):
        return ramp(BRONZE, 0.86)                                    # Mittelgrat
    if face in ('left', 'right') and y >= 4 and ((face == 'right' and x <= 1) or (face == 'left' and x >= w - 2)):
        return ramp(BRONZE, 0.42 + FACE_LIGHT[face])                 # Kante der Wangenklappe
    if face == 'back' and y >= h - 2:
        return ramp(SKIN, 0.4)                                       # Nacken unter dem Helmrand
    return bronze(face, x, y, w, h)


def horsehair(seed, dark=0.0):
    """Rosshaarbusch: gekämmte Strähnen längs, oben hell, unten dunkel."""
    def paint(face, x, y, w, h):
        v = 0.52 + FACE_LIGHT[face] * 1.4 + (0.5 - y / max(h - 1, 1)) * 0.25 - dark
        if face in ('left', 'right'):
            v += 0.1 * math.sin(y * 2.1 + x * 0.4 + seed)
        else:
            v += 0.1 * math.sin(x * 2.3 + seed)
        v += (speckle(x, y, seed) - 0.5) * 0.08
        return ramp(PLUME, v)
    return paint


def hand(seed):
    """Faust: Knöchel hell, Fingerfugen, Daumen als hellerer Fleck."""
    base = surf(SKIN, 0.56, 0.12, seed, roundx=0.15)

    def paint(face, x, y, w, h):
        if face in ('front', 'left', 'right', 'back') and y == 0:
            return ramp(SKIN, 0.68 + FACE_LIGHT[face])
        if face in ('front', 'bottom') and x in (1, 3) and y >= 1:
            return ramp(SKIN, 0.36)
        return base(face, x, y, w, h)
    return paint


def greave(seed):
    """Beinschiene: vorn getriebene Bronze mit Wadenwölbung, hinten frei (Haut)."""
    bronze = metal(BRONZE, 0.58, seed, grad=0.26, roundx=0.3, rim=0.2, seam=0.1)
    skin = surf(SKIN, 0.5, 0.12, seed + 1)

    def paint(face, x, y, w, h):
        if face == 'back':
            return skin(face, x, y, w, h)
        if face == 'front' and 2 <= y <= 4:
            return ramp(BRONZE, 0.78 - abs(x - 1.5) * 0.08)          # Knie-/Wadenwölbung
        return bronze(face, x, y, w, h)
    return paint


def sandal(face, x, y, w, h):
    base = surf(LEATH, 0.42, 0.1, 406)
    if face == 'top':
        return ramp(SKIN, 0.55) if x in (0, w - 1) or y % 2 == 0 else ramp(LEATH, 0.55)
    if face == 'front' and y == 0 and x % 2 == 0:
        return ramp(SKIN, 0.6)                                       # Zehen
    return base(face, x, y, w, h)


# Grundhaltung: Nach dem Lösen der Ruhepose stehen ihre Winkel hier und als Grundrotation im Modell.
_REST = {}


def _deg(name):
    v = _REST.get(name)
    return tuple(math.degrees(a) for a in v[:3]) if v else (0.0, 0.0, 0.0)


# --- Schild ----------------------------------------------------------------------------------------------------------
SHIELD_R = 10.0


def shield_face(x0, y0):
    """Vorderseite des Schildes als ein Bild über alle drei Quader: polierter Rand, blauer Ring mit Mäanderpunkten,
    Bronzefeld mit himmelblauem Blitz als Wappen. Die Ränder der Quader tragen den Randgrat."""
    rim = metal(BRONZE, 0.66, 410, grad=0.1, roundx=0.0, rim=0.25, seam=0.15, glint=False)

    def paint(face, x, y, w, h):
        if face == 'back':
            # Innenseite: Holz mit Lederfutter und Armschlaufe
            px, py = x0 + w - x - 0.5, y0 + y + 0.5
            if abs(py) < 1.5 and abs(px) < 6:
                return ramp(LEATH, 0.4)
            return ramp(WOOD, 0.45 + (speckle(x, y, 411) - 0.5) * 0.12 + 0.06 * math.sin(px * 1.3))
        if face != 'front':
            return rim(face, x, y, w, h)
        px, py = x0 + x + 0.5, y0 + y + 0.5
        # Achteck-Abstand (wie die Stufen der Quader): max aus Achsabstand und Diagonale
        r = max(abs(px), abs(py), (abs(px) + abs(py)) / 1.41)
        light = (0.5 - py / 20.0) * 0.18 - px * 0.006
        if r > SHIELD_R - 1.2:
            return ramp(BRONZE, 0.86 + light)                        # Randgrat
        if r > SHIELD_R - 2.2:
            return ramp(BRONZE, 0.42 + light)                        # Kehle
        if r > SHIELD_R - 4.2:
            return ramp(PLUME, 0.48 + light + (0.08 if r > SHIELD_R - 3.0 else 0.0))   # blauer Ring
        if r > SHIELD_R - 5.0:
            return ramp(TUNIC, 0.8 + light)                          # heller Innenfaden
        # Wappen: achtstrahliger Himmelsstern um den Buckel (Strahlen spitz zulaufend)
        ang = math.atan2(py, px) / (math.pi / 4)
        ray = abs(ang - round(ang))                                  # 0 auf dem Strahl, 0,5 dazwischen
        rr = math.hypot(px, py)
        if rr < 5.6 and ray < 0.5 * (1.0 - rr / 6.2) + 0.06:
            return ramp(TUNIC, 0.92 + light - rr * 0.02)
        return ramp(BRONZE, 0.5 + light + (speckle(x, y, 412) - 0.5) * 0.08)
    return paint


def boss_paint(face, x, y, w, h):
    return metal(BRONZE, 0.8, 413, grad=0.3, roundx=0.4, rim=0.25, seam=0.1)(face, x, y, w, h)


def shield():
    """Achteck aus drei Quadern (20 x 8, 8 x 20, 14 x 14), 2 dick, Buckel in der Mitte. Ursprung in der Armschlaufe;
    die Vorderseite zeigt nach -z."""
    cubes = []
    for (w, h) in ((20, 8), (8, 20), (14, 14)):
        x0, y0 = -w / 2.0, -h / 2.0
        cubes.append(Cube(x0, y0, -5, w, h, 2, shield_face(x0, y0), dilate=0.0 if w != 14 else 0.01))
    cubes.append(Cube(-2, -2, -7, 4, 4, 2, boss_paint))
    return Part('shield', (0, 4, 0), _deg('shield'), cubes=cubes)


# --- Speer ------------------------------------------------------------------------------------------------------------
def spear():
    """Speer 85 lang, 2 dick, Blattspitze 8 lang. Ursprung im Griff; die Spitze zeigt nach -y."""
    shaft = surf(WOOD, 0.55, 0.0, 420, roundx=0.4, noise=0.05)

    def blade(face, x, y, w, h):
        v = 0.62 + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.3
        if face in ('front', 'back') and abs(x - (w - 1) / 2.0) < 0.6:
            v += 0.2                                                 # Mittelrippe
        return ramp(BRONZE, v)

    return Part('spear', (0, 9, 0), _deg('spear'), cubes=[
        Cube(-1, -53, -1, 2, 53, 2, shaft),                          # Schaft oben
        Cube(-1, 0, -1, 2, 18, 2, surf(WOOD, 0.5, 0.0, 421, roundx=0.4, noise=0.05)),   # Schaft unten
        Cube(-1.5, -3, -1.5, 3, 7, 3, surf(LEATH, 0.5, 0.1, 422, roundx=0.3)),          # Griffwicklung
        Cube(-1.5, -56, -1.5, 3, 4, 3, metal(BRONZE, 0.6, 423, roundx=0.3)),            # Tülle
        Cube(-2, -61, -1, 4, 6, 2, blade),                                               # Blatt
        Cube(-1, -64, -1, 2, 3, 2, blade),                                               # Spitze
        Cube(-1, 18, -1, 2, 3, 2, metal(BRONZE, 0.5, 424, roundx=0.3)),                  # Schuh (Sauroter)
    ])


# =====================================================================================================================
# Kurete: Modell
# =====================================================================================================================

def kurete():
    plume = Part('plume', (0, -8, 0.5), _deg('plume'), cubes=[
        Cube(-1, -2, -3, 2, 2, 7, metal(BRONZE, 0.66, 430, roundx=0.0)),             # Halter
        Cube(-1.5, -6, 0, 3, 5, 5, horsehair(431, 0.06)),                            # hinten
        Cube(-1.5, -7, -4, 3, 5, 5, horsehair(432)),                                 # Scheitel
        Cube(-1.5, -6, -7, 3, 4, 3, horsehair(433)),                                 # biegt nach vorn
        Cube(-1.5, -3, -9, 3, 3, 2, horsehair(434, 0.04)),                           # Spitze vorn unten
        Cube(-1.5, -4, 5, 3, 6, 2, horsehair(435, 0.12)),                            # Schweif hinten
        Cube(-1.5, 2, 6, 3, 4, 2, horsehair(436, 0.2)),                              # Schweif hängt in den Nacken
    ])
    head = Part('head', (0, -14, -0.5), _deg('head'), cubes=[
        Cube(-4, -8, -4, 8, 8, 8, helmet),
        Cube(-4.5, -2.5, 1, 9, 2, 4, metal(BRONZE, 0.52, 436, roundx=0.2)),          # Nackenschirm
    ], children=[plume])

    def arm(side):
        s = 'r' if side < 0 else 'l'
        seed = 440 if side < 0 else 460
        item = spear() if side < 0 else shield()
        fore = Part(f'fore_{s}', (0, 9, 0), _deg(f'fore_{s}'), cubes=[
            Cube(-2, 0, -2, 4, 7, 4, surf(SKIN, 0.55, 0.12, seed + 1, roundx=0.25)),
            Cube(-2.5, 1, -2.5, 5, 4, 5, metal(BRONZE, 0.52, seed + 2, roundx=0.3, rivets=0)),   # Armschiene
            Cube(-2.5, 7, -2.5, 5, 4, 5, hand(seed + 3)),
        ], children=[item])
        return Part(f'arm_{s}', (side * 8.5, -12, 0), _deg(f'arm_{s}'), cubes=[
            Cube(-2, -1, -2, 4, 10, 4, surf(SKIN, 0.58, 0.14, seed, roundx=0.25)),
            Cube(-3, -2, -3, 6, 4, 6, metal(BRONZE, 0.62, seed + 4, roundx=0.3, rivets=2)),     # Schulterklappe
        ], children=[fore])

    chest = Part('chest', (0, -2, 0), _deg('chest'), cubes=[
        Cube(-7, -13, -4, 14, 8, 8, cuirass),
        Cube(-5, -6, -3.5, 10, 6, 7, abdomen),
        Cube(-1.5, -15, -1.5, 3, 2, 3, surf(SKIN, 0.5, 0.1, 437)),                   # Hals
    ], children=[head, arm(-1), arm(1)])

    def leg(side):
        s = 'r' if side < 0 else 'l'
        seed = 480 if side < 0 else 490
        shin = Part(f'shin_{s}', (0, 12, 0), _deg(f'shin_{s}'), cubes=[
            Cube(-2, 0, -2, 4, 10, 4, greave(seed + 1)),
            Cube(-2.5, -1, -2.5, 5, 3, 5, metal(BRONZE, 0.6, seed + 2, roundx=0.3)),  # Knieschutz
            Cube(-2, 10, -3.5, 4, 2, 6, sandal),
        ])
        return Part(f'leg_{s}', (side * 2.5, 0, 0), _deg(f'leg_{s}'), cubes=[
            Cube(-2, 0, -2, 4, 12, 4, surf(SKIN, 0.55, 0.14, seed, roundx=0.25)),
        ], children=[shin])

    body = Part('body', (0, 0, 0), _deg('body'), cubes=[
        Cube(-5.5, -1, -3.5, 11, 8, 7, pleats(450)),                                  # Tunika
        Cube(-6.5, -2, -4.5, 13, 6, 9, pteruges(451)),                                # Lederstreifen
        Cube(-5.5, -4, -4, 11, 3, 8, belt),                                           # Gürtel
    ], children=[chest, leg(-1), leg(1)])
    return SkylineModel('kurete', 128, 128, [body])


# =====================================================================================================================
# Posen (Radiant, absolute Knochendrehung; sechs Zahlen = Drehung + Versatz). Dieselben Werte stehen in
# OlympMobModels.kt (KureteModel).
# =====================================================================================================================

_RIG = Rig(kurete())
SHIELD_GRIP = (0, 4, 0)      # Armschlaufe des Schildes im Unterarm (= Ursprung des Knochens shield)


def _upright_spear(p, lean=(0.0, -1.0, -0.12)):
    _RIG.aim(p, 'spear', _scale(_norm(lean), -1), (0, 0, -1))


def _shield_facing(p, normal=(0, 0, -1)):
    """Schild so drehen, dass seine Vorderseite (-z) möglichst nach normal zeigt. Der Unterarm liegt immer in der
    Schildebene (Armschlaufe), damit die Faust nie durch den Schild sticht."""
    rot, _ = _RIG.fk(p, 'fore_l')
    f = _norm(_apply(rot, (0, 1, 0)))
    n = _norm(normal)
    n = _norm(_sub(n, _scale(f, _dot(n, f))))
    z = _scale(n, -1)
    x = _cross(f, z)
    _RIG.set_world_rot(p, 'shield', [[x[i], f[i], z[i]] for i in range(3)])


def _idle():
    p = dict(
        body=(0.0, 0.0, 0.0), chest=(0.04, 0.12, 0.0), head=(0.0, -0.12, 0.0), plume=(0.0, 0.0, 0.0),
        arm_r=(-0.35, 0.0, 0.18), fore_r=(-0.75, 0.0, 0.0),
        arm_l=(-0.12, 0.0, -0.2), fore_l=(-0.2, 0.0, 0.0),
        leg_r=(-0.08, 0.0, 0.06), shin_r=(0.12, 0.0, 0.0), leg_l=(0.12, 0.0, -0.08), shin_l=(0.06, 0.0, 0.0),
    )
    _upright_spear(p, (0.0, -1.0, -0.08))
    _shield_facing(p, (0.25, 0.0, -1.0))
    return p


def _clash():
    """Ansage: Speerschaft quer vor dem Leib an den Schildrand geschlagen (Treffpunkt der Schläge bei 3 und 8)."""
    p = dict(
        body=(0.0, 0.25, 0.0, 0.0, 0.8, 0.0), chest=(-0.1, 0.2, 0.0), head=(-0.08, -0.35, 0.0),
        arm_r=(-0.9, 0.0, 0.55), fore_r=(-1.0, 0.0, 0.0),
        arm_l=(-1.0, 0.0, -0.35), fore_l=(-0.8, 0.0, 0.0),
        leg_r=(-0.25, 0.0, 0.12), shin_r=(0.35, 0.0, 0.0), leg_l=(0.3, 0.0, -0.12), shin_l=(0.3, 0.0, 0.0),
    )
    _RIG.aim(p, 'spear', _norm((-0.85, 0.35, 0.4)), (0, -1, 0))
    _shield_facing(p, (0.45, 0.0, -1.0))
    return p


def _thrust_windup():
    """Speer waagerecht neben dem Kopf zurückgezogen, Schild vor, Gewicht auf dem hinteren Bein."""
    p = dict(
        body=(0.0, 0.45, 0.0, 0.0, 1.5, 2.0), chest=(-0.12, 0.35, 0.0), head=(-0.05, -0.7, 0.0),
        arm_r=(-1.0, 0.0, 1.25), fore_r=(-1.35, 0.0, 0.0),
        arm_l=(-1.25, 0.0, -0.3), fore_l=(-0.45, 0.0, 0.0),
        leg_r=(0.35, 0.0, 0.2), shin_r=(0.55, 0.0, 0.0), leg_l=(-0.55, 0.0, -0.12), shin_l=(0.45, 0.0, 0.0),
    )
    _RIG.aim(p, 'spear', (0.0, 0.08, 1.0), (0, -1, 0))
    _shield_facing(p, (0.55, 0.0, -1.0))
    return p


def _thrust_hit():
    """Ausfallschritt, Arm gestreckt: Speer, Arm und Rumpf eine Linie nach vorn."""
    p = dict(
        body=(0.18, -0.25, 0.0, 0.0, 3.5, -5.0), chest=(0.3, -0.35, 0.0), head=(-0.45, 0.5, 0.0),
        arm_r=(-1.6, 0.0, 0.25), fore_r=(-0.05, 0.0, 0.0),
        arm_l=(-0.4, 0.0, -0.55), fore_l=(-0.9, 0.0, 0.0),
        leg_r=(0.75, 0.0, 0.1), shin_r=(0.2, 0.0, 0.0), leg_l=(-1.15, 0.0, -0.08), shin_l=(1.0, 0.0, 0.0),
    )
    _RIG.aim(p, 'spear', (0.0, 0.05, 1.0), (0, -1, 0))
    _shield_facing(p, (0.9, 0.0, -0.5))
    return p


def _bash_windup():
    """Schild an die Schulter gezogen, Körper eingedreht, Speer nach hinten aufgestellt."""
    p = dict(
        body=(0.0, -0.6, 0.0, 0.0, 1.2, 1.5), chest=(0.05, -0.45, 0.0), head=(0.0, 0.85, 0.0),
        arm_r=(0.35, 0.0, 0.45), fore_r=(-0.5, 0.0, 0.0),
        arm_l=(-0.55, 0.0, -0.95), fore_l=(-1.55, 0.0, 0.0),
        leg_r=(0.35, 0.0, 0.12), shin_r=(0.45, 0.0, 0.0), leg_l=(-0.4, 0.0, -0.12), shin_l=(0.5, 0.0, 0.0),
    )
    _upright_spear(p, (0.0, -1.0, 0.5))
    _shield_facing(p, (1.0, 0.0, -0.35))
    return p


def _bash_hit():
    """Kurzer Schritt, Schild flach nach vorn gestoßen, Schulter dahinter."""
    p = dict(
        body=(0.12, 0.3, 0.0, 0.0, 2.5, -4.0), chest=(0.25, 0.4, 0.0), head=(-0.3, -0.5, 0.0),
        arm_r=(0.6, 0.0, 0.35), fore_r=(-0.35, 0.0, 0.0),
        arm_l=(-1.45, 0.0, -0.1), fore_l=(-0.25, 0.0, 0.0),
        leg_r=(0.55, 0.0, 0.1), shin_r=(0.25, 0.0, 0.0), leg_l=(-0.95, 0.0, -0.1), shin_l=(0.85, 0.0, 0.0),
    )
    _RIG.ik(p, 'arm_l', 'fore_l', SHIELD_GRIP, (3.0, -6.0, -17.0), (12.0, -9.0, -8.0))
    _upright_spear(p, (0.0, -1.0, 0.75))
    _shield_facing(p, (0.15, 0.0, -1.0))
    return p


def _guard():
    """Deckung: Schild hoch vor Kopf und Rumpf, Speer neben sich aufgestellt, leicht in die Knie."""
    p = dict(
        body=(0.0, 0.3, 0.0, 0.0, 1.5, 0.5), chest=(0.12, 0.25, 0.0), head=(0.22, -0.3, 0.0),
        arm_r=(-0.4, 0.0, 0.3), fore_r=(-0.7, 0.0, 0.0),
        arm_l=(-1.2, -0.25, -0.55), fore_l=(-1.15, 0.0, 0.0),
        leg_r=(-0.3, 0.0, 0.18), shin_r=(0.55, 0.0, 0.0), leg_l=(0.2, 0.0, -0.15), shin_l=(0.5, 0.0, 0.0),
    )
    _RIG.ik(p, 'arm_l', 'fore_l', SHIELD_GRIP, (1.0, -12.5, -10.0), (13.0, -8.0, -5.0))
    _upright_spear(p, (0.0, -1.0, -0.1))
    _shield_facing(p, (0.08, 0.1, -1.0))
    return p


FEET = [(sx, 12, sz) for sx in (-2, 2) for sz in (-3.5, 2.5)]


def _ground(p, feet=('shin_r', 'shin_l')):
    """Verschiebt den Körper senkrecht, bis der tiefste Fußpunkt auf dem Boden (y = 24) steht."""
    low = max(_RIG.world(p, f, c)[1] for f in feet for c in FEET)
    v = tuple(p.get('body', (0.0, 0.0, 0.0)))
    v = v + (0.0, 0.0, 0.0) if len(v) == 3 else v
    p['body'] = v[:4] + (v[4] + 24.0 - low,) + v[5:]
    return p


POSES_RAW = {}
for _name, _fn in (('idle', _idle), ('clash', _clash), ('thrust_windup', _thrust_windup), ('thrust_hit', _thrust_hit),
                   ('bash_windup', _bash_windup), ('bash_hit', _bash_hit), ('guard', _guard)):
    POSES_RAW[_name] = _ground(_fn())
POSES = {name: rounded(pose) for name, pose in POSES_RAW.items()}
_REST.update({k: v for k, v in POSES['idle'].items() if k != 'body'})
KOTLIN_POSES = ['clash', 'thrust_windup', 'thrust_hit', 'bash_windup', 'bash_hit', 'guard']

# Für die Größenprüfung in preview.py: Busch und Speer ragen über den Scheitel hinaus.
HEIGHT_EXCLUDE = ('plume', 'spear')


def kotlin_block():
    return kotlin_table(kurete(), POSES, ['idle'] + KOTLIN_POSES, {})


def variants(model, albedo, glow):
    """Der Kurete hat keine Leuchtmaske. Der gemeinsame Renderer zeichnet trotzdem eine Leuchtschicht: leeres Bild."""
    from texkit import Image, save
    save(Image(model.tex_w, model.tex_h), f'textures/entity/boss/{model.texture}_glow.png')


if __name__ == '__main__':
    if '--kotlin' in sys.argv:
        sync_kotlin('KURETE', kotlin_block())
