"""Die Caduceus-Schlangen: zwei schlanke Metallschlangen (A golden, B silberblau) mit je einem kleinen Flügelpaar hinter
dem Kopf, dazu die Schattenfarbe (`serpent_shade*`) für den Schatten des Boten. Teil von boss_models.py (--only serpent).

Der Renderer (client/model/SerpentModel.kt) reiht Kopf, Rumpfglieder und Schwanzstück entlang des Körperpfads auf.
Alle Flächen werden über ihre 3D-Lage gemalt (`scube`): So laufen Glanzband, Schuppenmuster und der leuchtende Rückengrat
ohne Bruch über die Kanten der Quader, und der achteckige Leib wirkt rund.
"""
import math

from feather_art import CELL_LONG
from modelkit import SILVER, Cube, Model, Part, Plume, hexc, mix, ramp, rgb, speckle

# Materialien je Stil: Körper (Metall), Bauch, Innenraum (Maul, Zunge), Leuchtfarbe, Saum der Schuppen.
STYLES = {
    'gold': {
        'metal': rgb('#3e1f05', '#94560f', '#c98a1c', '#efbb36', '#ffe27a', '#fff6d2'),
        'belly': rgb('#6e5c42', '#b5a284', '#e3d6bb', '#f8f0dc', '#fffdf6'),
        'inner': rgb('#12060a', '#2c0f16', '#521c26', '#7c2e38'),
        'glow': (223, 244, 255),
        'seam': None,
    },
    'silver': {
        'metal': SILVER,
        'bias': -0.1,
        'spec': 0.2,
        'belly': rgb('#6c7282', '#aab2c2', '#dce2ec', '#f4f7fb', '#ffffff'),
        'inner': rgb('#06081a', '#111633', '#222a52', '#363f70'),
        'glow': (223, 244, 255),
        'seam': None,
    },
    # Der Schatten: verkohltes Schwarzgrau, die Schuppen mit Ascherand, Bauch knochengrau, Gift-Grün als Licht.
    'shade': {
        'metal': rgb('#060607', '#121214', '#1f1f23', '#303036', '#47474e', '#64646b'),
        'belly': rgb('#2c2a27', '#4a4741', '#6f6a62', '#948e84', '#b4ada2'),
        'inner': rgb('#020403', '#07100a', '#0f2214', '#1a3a20'),
        'glow': (120, 255, 140),
        'seam': hexc('#9a958c'),
    },
    'shade_b': {
        'metal': rgb('#050608', '#101216', '#1b1e25', '#2b2f38', '#40444f', '#5c606b'),
        'belly': rgb('#2a2a29', '#474644', '#6b6966', '#8f8c87', '#afaba5'),
        'inner': rgb('#020403', '#07100a', '#0f2214', '#1a3a20'),
        'glow': (120, 255, 140),
        'seam': hexc('#8e8c88'),
    },
}

NORMALS = {
    'front': (0, 0, -1), 'back': (0, 0, 1), 'right': (-1, 0, 0), 'left': (1, 0, 0), 'top': (0, -1, 0), 'bottom': (0, 1, 0),
}


def smooth(a, b, x):
    t = max(0.0, min(1.0, (x - a) / (b - a)))
    return t * t * (3 - 2 * t)


def rotation(rot):
    """Drehmatrix wie ModelPart (Z * Y * X), Winkel in Grad."""
    rx, ry, rz = (math.radians(a) for a in rot)
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    return [
        [cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx],
        [sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx],
        [-sy, cy * sx, cy * cx],
    ]


def placed(pivot, rot, outer=None):
    """Abbildung Knochenraum -> Raum des Elternknochens (für Malen nach Lage)."""
    m = rotation(rot)

    def point(p):
        q = tuple(sum(m[r][i] * p[i] for i in range(3)) + pivot[r] for r in range(3))
        return outer[0](q) if outer else q

    def direction(n):
        q = tuple(sum(m[r][i] * n[i] for i in range(3)) for r in range(3))
        return outer[1](q) if outer else q
    return point, direction


def scube(x, y, z, w, h, d, shader, xform=None, dilate=0.0):
    """Quader, dessen Texel nach ihrer Lage im Raum gemalt werden: shader(face, p, n) mit Punkt p und Normale n."""
    def paint(face, i, j, fw, fh):
        if face == 'front':
            p = (x + i + 0.5, y + j + 0.5, z)
        elif face == 'back':
            p = (x + w - i - 0.5, y + j + 0.5, z + d)
        elif face == 'right':
            p = (x, y + j + 0.5, z + d - i - 0.5)
        elif face == 'left':
            p = (x + w, y + j + 0.5, z + i + 0.5)
        elif face == 'top':
            p = (x + i + 0.5, y, z + j + 0.5)
        else:
            p = (x + i + 0.5, y + h, z + j + 0.5)
        n = NORMALS[face]
        if xform is not None:
            p, n = xform[0](p), xform[1](n)
        return shader(face, p, n)
    return Cube(x, y, z, w, h, d, paint, dilate)


# -------------------------------------------------------------------------------------------------
# Maler
# -------------------------------------------------------------------------------------------------

def metal_value(theta, n, spec=0.30):
    """Poliertes Metall um eine Längsachse: helles Glanzband oben seitlich, dunkles Spiegelband an der Flanke,
    etwas Rücklicht von unten. theta = 0 oben, ±pi unten. Die Flächennormale schärft die Kanten."""
    a = abs(theta)
    v = 0.46 + 0.16 * math.cos(a)
    v += spec * math.exp(-((a - 0.55) / 0.32) ** 2)
    v -= 0.22 * math.exp(-((a - 1.8) / 0.38) ** 2)
    v += 0.05 * (-n[1])
    return v


def scale_tone(s, z, seam):
    """Ruhiges, versetztes Rautenmuster (Periode 4 entlang des Leibs, damit sich überlappende Glieder decken).
    Liefert (Helligkeit, Saumanteil)."""
    cu = z / 4.0 + s / 6.0
    cv = z / 4.0 - s / 6.0
    fu, fv = cu - math.floor(cu), cv - math.floor(cv)
    edge = min(fu, 1 - fu, fv, 1 - fv)
    line = 1.0 - smooth(0.04, 0.2, edge)
    # Jede Schuppe wird nach hinten (zum Schwanz) heller, so liegen sie wie Dachziegel übereinander.
    lift = 0.03 * (fu + fv - 1.0)
    return lift - 0.05 * line, line


def body_shader(style, spine_from=-99.0, spine_to=99.0, cy=0.0, half_w=None, half_h=4.0, belly_from=2.25, spine=((-4.0, 0.5),)):
    """Maler für einen Leib um die z-Achse (Mittelpunkt y = cy). half_w(z) erlaubt flache Köpfe. spine nennt die
    Oberseiten (y), auf denen der Rückengrat leuchtet, mit seiner halben Breite."""
    S = STYLES[style]

    def shade(face, p, n):
        x, y, z = p
        hw = half_w(z) if half_w else 4.0
        theta = math.atan2(x / hw, -(y - cy) / half_h)
        a = abs(theta)
        grain = (speckle(int(x * 3 + 40), int(z * 3 + y * 5 + 40), 7) - 0.5) * 0.04
        if a > belly_from:
            # Bauch: breite Querschilde mit weichem Schattensaum (Periode 4 wie die Schuppen).
            k = (z / 4.0) - math.floor(z / 4.0)
            v = 0.62 + 0.1 * (1 - abs(a - math.pi)) - 0.16 * (1 - smooth(0.0, 0.3, k)) + grain
            return ramp(S['belly'], v)
        v = metal_value(theta, n, S.get('spec', 0.30)) + grain + S.get('bias', 0.0)
        tone, line = scale_tone(theta * 4.0, z, S['seam'])
        c = ramp(S['metal'], v + tone)
        if S['seam'] is not None and line > 0.5:
            # Schatten: Ascherand an den Schuppen.
            c = mix(c, S['seam'], 0.14 * line)
        width = next((hw_ for sy, hw_ in spine if abs(y - sy) < 0.01), None)
        if face == 'top' and width is not None and spine_from <= z <= spine_to:
            if abs(x) < width:
                g = S['glow']
                return (mix(c, g + (255,), 0.85), g + (255,))
            if abs(x) < width + 1.0:
                # Gratkante neben der Leuchtlinie fängt das Licht.
                return ramp(S['metal'], v + 0.12)
        if a > belly_from - 0.25:
            # Weicher Übergang zum Bauch mit dunklem Saum.
            return mix(c, ramp(S['metal'], 0.12), 0.5)
        return c
    return shade


def head_shader(style):
    """Kopf: wie der Leib, aber flach (halbe Breite folgt dem Keil), dazu Lippenlinie, Nasenlöcher und Gaumen."""
    S = STYLES[style]
    base = body_shader(style, spine_from=-5.0, spine_to=3.0, cy=-0.5, half_w=lambda z: 2.5 + 2.0 * max(0.0, min(1.0, (z + 12) / 14)),
                       half_h=3.0, belly_from=9.0, spine=((-3.5, 0.5),))

    def shade(face, p, n):
        x, y, z = p
        if face == 'bottom' and z < 0.5 and y < 1.2:
            # Gaumen: dunkler Innenraum, sichtbar beim Zuschnappen.
            return ramp(S['inner'], 0.35 + 0.25 * (1 - (z + 12) / 12))
        if face == 'front' and z < -11.5 and -2.6 < y < -1.4 and 0.4 < abs(x) < 1.6:
            return ramp(S['inner'], 0.1)  # Nasenlöcher
        c = base(face, p, n)
        if face in ('left', 'right', 'front') and y > -0.6 and z < 0.5:
            # Lippensaum: 1 Texel dunkler Rand über dem Unterkiefer.
            return mix(c if not isinstance(c[0], tuple) else c[0], ramp(S['metal'], 0.05), 0.55)
        return c
    return shade


def jaw_shader(style):
    """Unterkiefer: Metall außen, Kinn und Kehle elfenbeinfarben, oben (im Maul) dunkler Innenraum."""
    S = STYLES[style]
    base = body_shader(style, cy=0.0, half_w=lambda z: 2.5 + 1.5 * max(0.0, min(1.0, (z + 12) / 12)), half_h=2.0, belly_from=2.0)

    def shade(face, p, n):
        x, y, z = p
        if face == 'top':
            return ramp(S['inner'], 0.45 + 0.2 * math.cos(x * 0.6))
        return base(face, (x, y - 1.0, z), n)
    return shade


def plain(material, v):
    def shade(face, p, n):
        return ramp(material, v + 0.08 * (-n[1]) - 0.04 * n[2])
    return shade


def eye_shader(style, around):
    """Auge: nur die Außenseite leuchtet, die übrigen Flächen gehören zum Kopf."""
    S = STYLES[style]

    def shade(face, p, n):
        if face in ('left', 'right'):
            g = S['glow']
            return (mix(g + (255,), hexc('#ffffff'), 0.3), g + (255,))
        return around(face, p, n)
    return shade


# -------------------------------------------------------------------------------------------------
# Teile
# -------------------------------------------------------------------------------------------------

# Grundstellung der Kopfflügel im Modell = gespreizt (dieselben Werte wie FIN_OPEN / FAN_OPEN unten).
FIN_BASE = (0.0, 0.55, 0.85)
FAN_BASE = (-0.4, -0.8)
FEATHERS = ((18.0, 6.0), (16.0, 6.0), (14.0, 5.0))


def _mirror(r):
    return (r[0], -r[1], -r[2])


def fin(name, side):
    """Kopfflügel aus drei glatten Federn (Flügel des Heroldsstabs). Jede Feder hat einen eigenen Knochen
    (`fin_r`, `fin_r_1`, `fin_r_2`), damit sich der Flügel im Flug zusammenlegen und beim Zuschnappen fächern kann:
    Die Federn wachsen in der lokalen x-y-Ebene nach außen, der Fächer entsteht über roll der beiden Kinder."""
    axis = 'x-' if side < 0 else 'x+'

    def plume(i):
        length, width = FEATHERS[i]
        return Plume((0.0, 0.0, 0.35 * i), (0, 0, 0), width, length, CELL_LONG, axis, flip=side > 0, bend=0.06,
                     sweep=-0.05, shade=(0.78, 1.0))
    open_ = FIN_BASE if side < 0 else _mirror(FIN_BASE)
    kids = [Part(f'{name}_{i}', (0, 0.4 * i, 0), rot=tuple(math.degrees(a) * (1 if side < 0 else -1) for a in (0, 0, FAN_BASE[i - 1])),
                 plumes=[plume(i)]) for i in (1, 2)]
    return Part(name, (side * 4.2, -2.6, 1.6), rot=tuple(math.degrees(a) for a in open_), plumes=[plume(0)], children=kids)


def head(name, jaw_name, style, sfx):
    """Nattern-Kopf: flacher Keil 9 -> 5 breit, 6 hoch, 14 lang; Giftzähne klappbar, gespaltene Zunge, Kopfflügel."""
    S = STYLES[style]
    shade = head_shader(style)
    ivory = plain(S['belly'], 0.78)
    inner = plain(S['inner'], 0.7)

    cubes = [
        scube(-2.5, -3.5, -6, 5, 4, 8, shade),              # Hinterkopf
        scube(-3.5, -3.0, 1, 7, 6, 4, body_shader(style, spine=((-3.0, 0.5),))),  # Nacken, Übergang zum Leib
        scube(-3.6, -4.0, -9, 2, 2, 5, shade),              # Augenschild rechts
        scube(1.6, -4.0, -9, 2, 2, 5, shade),               # Augenschild links
        scube(-3.7, -2.5, -8, 1, 2, 2, eye_shader(style, shade)),  # Augen
        scube(2.7, -2.5, -8, 1, 2, 2, eye_shader(style, shade)),
    ]

    # Schnauze: fällt nach vorn leicht ab (pitch), endet in einem runden Nasenpolster.
    snout_pivot, snout_rot = (0, -3.5, -5.5), (9, 0, 0)
    sx = placed(snout_pivot, snout_rot)
    snout = Part(f'snout{sfx}', snout_pivot, rot=snout_rot, cubes=[
        scube(-2.5, 0, -7, 5, 3, 7, shade, xform=sx),
        scube(-1.5, 0.5, -8, 3, 2, 1, shade, xform=sx),
    ])

    # Wangen: schräge Platten von hinten (9 breit) zur Schnauze (5 breit), oben leicht abfallend; der Spalt unter
    # ihnen ist die Lippenlinie.
    def cheek(side):
        pivot, rot = (side * 4.5, -3.0, 2.0), (6, 8.2 * side, 0)
        x0 = 0 if side < 0 else -3
        return Part(f'cheek_{"r" if side < 0 else "l"}{sfx}', pivot, rot=rot,
                    cubes=[scube(x0, 0, -14, 3, 3, 14, shade, xform=placed(pivot, rot))])

    def fang(side):
        return Part(f'fang_{"r" if side < 0 else "l"}{sfx}', (side * 1.2, 0.3, -10.0), rot=(80, 0, 0),
                    cubes=[scube(-1, 0, -1, 2, 4, 2, ivory)])

    # Unterkiefer: Mittelstück plus zwei schräge Äste, hinten 8 breit.
    jshade = jaw_shader(style)

    def jaw_side(side):
        pivot, rot = (side * 2.5, 0, -12), (0, -7.2 * side, 0)
        x0 = 0 if side < 0 else -2
        return Part(f'jaw_side_{"r" if side < 0 else "l"}{sfx}', pivot, rot=rot,
                    cubes=[scube(x0, 0, 0, 2, 2, 12, jshade, xform=placed(pivot, rot))])

    # Zunge: liegt im geschlossenen Maul im Oberkopf verborgen und schnellt geradeaus nach vorn.
    tongue = Part(f'tongue{sfx}', (0, 0.6, -2), cubes=[
        scube(-1, -1, -6, 2, 1, 6, inner),
        scube(-1.5, -1, -8, 1, 1, 2, inner),
        scube(0.5, -1, -8, 1, 1, 2, inner),
    ])
    jaw = Part(jaw_name, (0, 0.5, 0), cubes=[scube(-2.5, 0, -12, 5, 2, 12, jshade)],
               children=[jaw_side(-1), jaw_side(1)])

    return Part(name, (0, 12, 0), cubes=cubes, children=[
        jaw, snout, cheek(-1), cheek(1), fang(-1), fang(1),
        fin(f'fin_r{sfx}', -1), fin(f'fin_l{sfx}', 1), tongue,
    ])


def segment(name, style):
    """Rumpfglied: achteckig-runder Querschnitt (Durchmesser 8) aus drei überlappenden Quadern, Länge 10."""
    shade = body_shader(style)
    return Part(name, (0, 0, 0), cubes=[
        scube(-4.0, -2.0, -5, 8, 4, 10, shade),
        scube(-3.5, -3.0, -5, 7, 6, 10, shade),
        scube(-2.5, -4.0, -5, 5, 8, 10, shade),
    ])


def tail(name, style):
    """Schwanzstück: verjüngt 6 -> 4 -> 2, endet in einer kleinen Kugel."""
    shade = body_shader(style, spine=((-3.0, 1.0), (-2.0, 1.0)))
    return Part(name, (0, 0, 0), cubes=[
        scube(-3, -3, -5, 6, 6, 8, shade),
        scube(-2, -2, 3, 4, 4, 7, shade),
        scube(-1, -1, 10, 2, 2, 5, shade),
        scube(-1.5, -1.5, 14, 3, 3, 3, plain(STYLES[style]['metal'], 0.62)),
    ])


def build(style_a='gold', style_b='silver', texture=None):
    return Model('serpent', 256, 128, [
        head('head', 'jaw', style_a, ''),
        head('head_b', 'jaw_b', style_b, '_b'),
        segment('segment_a', style_a),
        segment('segment_b', style_b),
        tail('tail_a', style_a),
        tail('tail_b', style_b),
    ], texture=texture)


def serpent():
    return build()


# -------------------------------------------------------------------------------------------------
# Posen für die Vorschau (dieselben Winkel wie SerpentModel.kt). Die Teile liegen im Modell übereinander; für die
# Vorschau reihen die Versätze Kopf, ein Glied und Schwanz jeder Variante hintereinander (A rechts, B links).
# -------------------------------------------------------------------------------------------------

JAW_REST = 0.06
JAW_BITE = 0.873           # 50°
FANG_REST = 1.40           # angelegt
FANG_BITE = -0.15          # aufgerichtet
TONGUE_OUT = -11.0         # Versatz nach vorn in Einheiten
# Diese Werte stehen identisch in SerpentModel.kt (object SerpentPose).
# Kopfflügel (rechte Seite; links gespiegelt: yaw und roll negiert). Ganzer Flügel (pitch, yaw, roll) und Fächer
# der beiden unteren Federn (roll relativ zum Flügel).
FIN_FOLD = (-1.25, 1.2, 0.1)
FIN_OPEN = (0.0, 0.55, 0.85)
FAN_FOLD = (-0.06, -0.12)
FAN_OPEN = (-0.4, -0.8)


def mirror(r):
    return (r[0], -r[1], -r[2])


def _layout(bite):
    fins = FIN_OPEN if bite else FIN_FOLD
    fan = FAN_OPEN if bite else FAN_FOLD
    jaw = JAW_BITE if bite else JAW_REST
    fang = FANG_BITE if bite else FANG_REST
    tongue = TONGUE_OUT if bite else 0.0
    pose = {}
    for sfx, head_name, jaw_name, seg, tl, x in (('', 'head', 'jaw', 'segment_a', 'tail_a', 9.0),
                                                ('_b', 'head_b', 'jaw_b', 'segment_b', 'tail_b', -9.0)):
        pose[head_name] = (0, 0, 0, x, 0, -10)
        pose[jaw_name] = (jaw, 0, 0)
        pose[f'fang_r{sfx}'] = (fang, 0, 0)
        pose[f'fang_l{sfx}'] = (fang, 0, 0)
        pose[f'tongue{sfx}'] = (0, 0, 0, 0, 0, tongue)
        pose[f'fin_r{sfx}'] = fins
        pose[f'fin_l{sfx}'] = mirror(fins)
        for i in (1, 2):
            pose[f'fin_r{sfx}_{i}'] = (0, 0, fan[i - 1])
            pose[f'fin_l{sfx}_{i}'] = (0, 0, -fan[i - 1])
        pose[seg] = (0, 0, 0, x, 12, 4)
        pose[tl] = (0, 0, 0, x, 12, 16)
    return pose


POSES = {
    'rest': _layout(False),
    'bite': _layout(True),
}


def _close(bite):
    """Nahansicht nur des goldenen Kopfes (zum Prüfen der Details)."""
    pose = dict(_layout(bite))
    pose['head'] = (0, 0, 0, 0, 0, 0)
    for hidden in ('head_b', 'segment_a', 'segment_b', 'tail_a', 'tail_b'):
        pose[hidden] = None
    return pose


POSES['close_rest'] = _close(False)
POSES['close_bite'] = _close(True)
