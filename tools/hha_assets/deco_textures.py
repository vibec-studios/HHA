"""Endgültige 32-px-Texturen der 17 Deko-Grundblöcke (Überarbeitung 4, PLAN 2; P01).

Aufruf (aus dem Projektordner):
    python tools/hha_assets/deco_textures.py                    # schreibt alle (nur, was sich ändert; zweiter Lauf: nichts)
    python tools/hha_assets/deco_textures.py --only storm_marble  # nur einen Block (mehrfach möglich)
    python tools/hha_assets/deco_textures.py --check            # prüft nur (Rampen, Naht, ΔE, Puls, Größen); Exit 1 bei Fehler
    python tools/hha_assets/deco_textures.py --preview <ordner> # schreibt die Belege (×8, beschriftet) in den Ordner

Je Block eine Funktion (`TEXTURES`), alle aus `stonekit` (Index-Malerei auf 8-Stufen-Rampen, Licht oben links, nahtlos).
Treppen, Stufen und Mauern benutzen die Textur ihres Grundblocks. `storm_cloud` und die Geräte gehören P05.
Dateinamen und Größen kommen aus `deco_registry.py` (nur gelesen).
"""
import json
import math
import os
import sys
import zipfile
import io

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stonekit as sk  # noqa: E402
import deco_registry as reg  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
TEX = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'hha', 'textures', 'block')
CLIENT_JAR = os.path.expanduser('~/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar')
N = sk.N

# Hüllkurven (PLAN 2.1 bzw. Briefing P01): 8 Bilder, frametime 3, interpolate.
STORM_PULSE = [0.15, 0.2, 1.0, 0.45, 0.7, 0.3, 0.2, 0.15]      # „Wetterleuchten“
SOUL_PULSE = [0.22, 1.0, 0.6, 0.88, 0.5, 0.32, 0.25, 0.22]     # lub-dub
EMBER_PULSE = [0.55, 1.0, 0.75, 0.92, 0.7, 0.6, 0.56, 0.55]
MCMETA = {'animation': {'frametime': 3, 'interpolate': True}}

# Schwellen der Prüfung
MAX_PULSE_STEP = 0.35          # größte relative Änderung der mittleren Helligkeit (L*) zwischen zwei Bildern
MARBLE_DE = 8.0                # ΔE Grundton MARBLE zu quartz_block_side oder calcite
BASALT_DE = 10.0               # ΔE Grundton BASALT zu polished_blackstone oder deepslate_tiles


# =============================================================================================
# Olymp
# =============================================================================================

# Marmor sitzt eine Stufe über dem Grundton (Fläche ≈ Stufe 5 = #e7e2da, ΔE 1,3 zu Quarz): neben Quarz und Kalzit
# wirkt er sonst grau-violett und fleckig (Wandprobe). Die Schatten bleiben kühl, nur seltener.
MARBLE_LIFT = 1


def olympus_marble():
    """Naturmarmor: Klumpen-Voronoi mit Licht oben links, Korn, drei Haarrisse, eine weiche Ader; angehoben."""
    lv = sk.stone_idx(101, lo=2, hi=6, pits=4, grains=10, cracks=3)
    sk.marble_veins(lv, 104, strength=1, width=0.45, cells=2, warp=6.0)
    return sk.colour(sk.lift(lv, MARBLE_LIFT), 'MARBLE')


def olympus_marble_bricks():
    """Ziegel 15 × 7 auf hellem Mörtel (Stufe 2 + Anhebung), damit die Wand neben Quarzziegeln nicht fleckig wird."""
    return sk.colour(sk.lift(sk.bricks(113, mortar=2), MARBLE_LIFT), 'MARBLE')


def polished_olympus_marble():
    """Polierte Platte mit Fase und einer kaum sichtbaren Ader (bleibt als Boden ruhig)."""
    lv = sk.polished_idx(121)
    sk.marble_veins(lv, 124, strength=1, width=0.4, cells=2, warp=6.0, lit=False)
    for y in range(N):
        for x in range(N):
            lv[y][x] = sk.lim(lv[y][x], 3, 5)
    sk.bevel(lv, outer=(6, 2), inner=(5, 3))
    return sk.colour(sk.lift(lv, MARBLE_LIFT), 'MARBLE')


# Mäander: Einheit 6 × 5 (Zeile 0 außen, Zeile 4 = Grundlinie innen); 1 = Rille.
MEANDER = ('11111.',
           '1...1.',
           '1.111.',
           '1.....',
           '111111')


def _meander_mask():
    """Mäanderrahmen im eingelassenen Feld: Bänder x/y 5…9 und 22…26, Eckquadrate als kleine Spirale."""
    m = [[False] * N for _ in range(N)]

    def put(x, y):
        m[y][x] = True
    for k in range(2):                                             # zwei Einheiten je Seite (12 px zwischen den Ecken)
        for r, row in enumerate(MEANDER):
            for c, ch in enumerate(row):
                if ch != '1':
                    continue
                u = 10 + k * 6 + c
                put(u, 5 + r)                                      # oben (Grundlinie innen)
                put(31 - u, 26 - r)                                # unten (gedreht)
                put(5 + r, 31 - u)                                 # links
                put(26 - r, u)                                     # rechts
    corner = ('11111',
              '1...1',
              '1.1.1',
              '1...1',
              '11111')
    for ox, oy in ((5, 5), (22, 5), (5, 22), (22, 22)):
        for r, row in enumerate(corner):
            for c, ch in enumerate(row):
                if ch == '1':
                    put(ox + c, oy + r)
    return m


# Lorbeerkranz 12 × 12 (Mitte des Felds, x/y 10…21), linke Hälfte; die rechte ist gespiegelt.
# L = Blatt (paarweise, Spitzen nach oben/außen), S = Zweig; oben offen, unten gebunden mit Bandenden.
LAUREL_LEFT = ('..LL..',
               '.L.S..',
               'LLS...',
               '..SL..',
               'LS....',
               'L.SL..',
               'LLS...',
               '..SL..',
               '.LLS..',
               '...SS.',
               '....SS',
               '...S..')
LAUREL = tuple(row + row[::-1] for row in LAUREL_LEFT)


def _laurel_mask(x0=10, y0=10):
    m = [[False] * N for _ in range(N)]
    stem = [[False] * N for _ in range(N)]
    for r, row in enumerate(LAUREL):
        for c, ch in enumerate(row):
            if ch in 'LS':
                m[y0 + r][x0 + c] = True
                stem[y0 + r][x0 + c] = ch == 'S'
    return m, stem


def _emblem_marble(lv, acc, accent):
    meander = _meander_mask()
    for y in range(N):
        for x in range(N):
            if meander[y][x]:
                lv[y][x] = 2                                       # gravierte Rille
            elif meander[sk.w(y - 1)][x] or meander[y][sk.w(x - 1)]:
                lv[y][x] = max(lv[y][x], 5) if lv[y][x] >= 3 else lv[y][x]   # Lippe unten/rechts
    mask, stem = _laurel_mask()
    for y in range(10, 22):                                        # Feld hinter dem Kranz ruhig (Stufe 4)
        for x in range(10, 22):
            lv[y][x] = 4
    sk.carve(lv, acc, mask, accent, levels=(2, 3, 4, 4))
    for y in range(N):
        for x in range(N):
            if stem[y][x]:
                acc[y][x] = (accent, 2)                            # Zweig dunkler als die Blätter
    acc[10][12] = (accent, 6)                                      # einziger Glanzpixel (Blattspitze oben links)


def chiseled_olympus_marble():
    """Gemeißelt: Mäanderrahmen als Rille, in der Mitte Lorbeerkranz mit stumpfer Goldeinlage und einem Glanzpixel."""
    lv, acc = sk.chiseled(131, _emblem_marble, 'GOLD')
    return sk.colour(sk.lift(lv, MARBLE_LIFT), 'MARBLE', acc)


def olympus_marble_pillar():
    return sk.colour(sk.lift(sk.column_idx(141), MARBLE_LIFT), 'MARBLE')


def olympus_marble_pillar_top():
    return sk.colour(sk.lift(sk.column_top_idx(142), MARBLE_LIFT), 'MARBLE')


def _gold_joint(lv, acc):
    """Goldfuge am Rand: oben/links Stufe 4, unten/rechts Stufe 2, Ecken 3; innen eine Steinfase (5/3)."""
    for i in range(N):
        acc[0][i] = ('GOLD', 4)
        acc[i][0] = ('GOLD', 4)
        acc[N - 1][i] = ('GOLD', 2)
        acc[i][N - 1] = ('GOLD', 2)
    acc[0][N - 1] = ('GOLD', 3)
    acc[N - 1][0] = ('GOLD', 3)
    for i in range(1, N - 1):
        lv[1][i] = 7
        lv[i][1] = 7
        lv[N - 2][i] = 4
        lv[i][N - 2] = 4
    lv[1][N - 2] = 5
    lv[N - 2][1] = 5
    acc[0][0] = ('GOLD', 6)                                        # Glanz am Kreuzungspunkt der Fugen


def gilded_olympus_marble():
    """Polierter Marmor mit eingelegter Goldfuge: gekachelt ein ruhiges Goldraster für Kanten und Leitlinien."""
    lv = sk.polished_idx(151, light_grains=5, pits=2)
    for y in range(N):
        for x in range(N):
            lv[y][x] = sk.lim(lv[y][x], 3, 5)
    sk.lift(lv, MARBLE_LIFT)
    acc = sk.empty_acc()
    _gold_joint(lv, acc)
    return sk.colour(lv, 'MARBLE', acc)


def _storm_base():
    """Dunkler Marmor: Klumpen auf Stufen 1–3, ruhig, mit einer feinen hellen Marmorader (Stufe 5–6)."""
    lv = sk.stone_idx(161, lo=1, hi=4, pits=3, grains=4, cracks=0)
    sk.lift(lv, -1, 1, 4)
    sk.marble_veins(lv, 163, strength=-2, width=0.3, cells=2, warp=7.0, lit=False)
    return lv


_STORM_VEIN = None


def _storm_vein():
    """Zwei Blitze als freie, verästelte Zickzack-Linien (1 px), je etwa eine Kachel lang; wickeln über den Rand."""
    global _STORM_VEIN
    if _STORM_VEIN is None:
        bolts = [(9.0, 0.0, 1.3, 40, 2), (27.0, 13.0, 1.95, 22, 1)]   # fallen nach unten, wickeln über den Rand
        _STORM_VEIN = sk.lightning(171, bolts)
    return _STORM_VEIN


def storm_marble_frame(intensity):
    """Ein Bild Sturmmarmor. Ruhe: Ader nur als Rille mit gedämpftem Kern (STORM 4/6); Wetterleuchten: Ader in der
    BOLT-Rampe, Rille glimmt kühl-blau (STORM 3/4). Form fest, nur Helligkeit."""
    lv = _storm_base()
    mask, side, halo = _storm_vein()
    acc = sk.empty_acc()
    for y in range(N):
        for x in range(N):
            m = mask[y][x]
            if m > 0:
                k = intensity * (0.45 + 0.55 * m)
                if k < 0.2:
                    lv[y][x] = 4
                elif k < 0.32:
                    lv[y][x] = 6
                else:
                    acc[y][x] = ('BOLT', sk.lim(round((k - 0.32) / 0.68 * 5), 0, 5))
            elif halo[y][x] > 0:
                g = halo[y][x] * intensity
                lv[y][x] = 0 if g < 0.45 else 3 if g < 0.75 else 4
    return sk.colour(lv, 'STORM', acc)


def storm_marble():
    return sk.frames(storm_marble_frame, STORM_PULSE)


def olympus_bronze():
    """Bronzeplatten 2 × 2 (Reihen um 8 versetzt) mit Nieten, gehämmert, Fuge Stufe 1."""
    return sk.colour(sk.plates(184, cols=2, rows=2, offset=8), 'BRONZE')


# =============================================================================================
# Tartaros
# =============================================================================================

def tartarus_basalt():
    return sk.colour(sk.stone_idx(201), 'BASALT')


def tartarus_basalt_bricks():
    return sk.colour(sk.bricks(215, mortar=1), 'BASALT')


def polished_tartarus_basalt():
    lv = sk.polished_idx(221)
    sk.bevel(lv)
    return sk.colour(lv, 'BASALT')


# Bident 10 × 14 (x 11…20, y 9…22): zwei Zinken mit Widerhaken außen, Querjoch, Schaft, Fuß.
BIDENT = ('.#......#.',
          '###....###',
          '.#......#.',
          '.#......#.',
          '.#......#.',
          '.#......#.',
          '.########.',
          '....##....',
          '....##....',
          '....##....',
          '....##....',
          '....##....',
          '....##....',
          '...####...')


def _bident_mask(x0=11, y0=9):
    m = [[False] * N for _ in range(N)]
    for r, row in enumerate(BIDENT):
        for c, ch in enumerate(row):
            if ch == '#':
                m[y0 + r][x0 + c] = True
    return m


def _emblem_basalt(lv, acc, accent):
    sk.engrave_ring(lv, 16.0, 16.0, 9.0, 10.8)
    for y in range(7, 25):                                         # Grund im Ring ruhig
        for x in range(7, 25):
            if math.hypot(x + 0.5 - 16, y + 0.5 - 16) < 8.0:
                lv[y][x] = sk.lim(lv[y][x], 3, 4)
    for i in (4, 5):                                               # vier Kerben an den Himmelsrichtungen
        lv[i][15] = lv[i][16] = 2
        lv[i][17] = 5
        lv[31 - i][15] = lv[31 - i][16] = 2
        lv[31 - i][17] = 5
        lv[15][i] = lv[16][i] = 2
        lv[17][i] = 5
        lv[15][31 - i] = lv[16][31 - i] = 2
        lv[17][31 - i] = 5
    sk.studs(lv, ((5, 5), (25, 5), (5, 25), (25, 25)))
    sk.carve(lv, acc, _bident_mask(), accent, levels=(0, 1, 2, 2), glint=(12, 11))


def chiseled_tartarus_basalt():
    """Gemeißelt: gravierter Ring mit Kerben und Nieten, darin ein Bident mit stumpfer Seelengrün-Einlage."""
    lv, acc = sk.chiseled(231, _emblem_basalt, 'SOUL')
    return sk.colour(lv, 'BASALT', acc)


def tartarus_basalt_pillar():
    return sk.colour(sk.column_idx(241), 'BASALT')


def tartarus_basalt_pillar_top():
    return sk.colour(sk.column_top_idx(242), 'BASALT')


_SOUL_VEIN = None


def _soul_vein():
    global _SOUL_VEIN
    if _SOUL_VEIN is None:
        paths = [sk.sine_path_h(11.0, 4.0, 0.4, 1.6, 1.9), sk.sine_path_v(21.0, 6.0, 2.1, 1.6, 0.3)]   # A nicht an der Naht
        branches = [[(2, -1.0, 11, 1), (12, 0.9, 13, 0), (23, -0.8, 9, 0)],
                    [(5, -1.1, 10, 0), (16, 1.0, 12, 1), (27, -0.9, 8, 0)]]
        _SOUL_VEIN = (sk.stone_idx(251),) + sk.vein(253, paths, branches)
    return _SOUL_VEIN


def soul_vein_frame(intensity):
    """Ein Bild Seelenader-Basalt: Ader als Röhre (Lichtseite heller), sitzt in einer Rille, die beim Schlag grün glimmt."""
    base, mask, side, halo = _soul_vein()
    lv = sk.copy(base)
    acc = sk.empty_acc()
    for y in range(N):
        for x in range(N):
            m = mask[y][x]
            if m > 0:
                k = intensity * (0.35 + 0.65 * m)
                if side[y][x] > 0.05:
                    k += 0.12 * intensity + 0.05
                elif side[y][x] < -0.05:
                    k -= 0.08
                idx = 2 + sk.lim(round(k * 4), 0, 4)                # Ruhe Stufe 2–3, Schlag bis 6 (Sprung ≤ 35 %)
                if m >= 1.0 and intensity > 0.75 and side[y][x] > 0.05:
                    idx = 7                                        # Spitzlicht beim stärksten Schlag
                acc[y][x] = ('SOUL', idx)
            elif halo[y][x] > 0:
                g = halo[y][x] * intensity
                if g < 0.3:
                    lv[y][x] = max(1, base[y][x] - 2)              # Rille
                else:
                    acc[y][x] = ('SOUL', 0 if g < 0.6 else 1)
    return sk.colour(lv, 'BASALT', acc)


def soul_vein_basalt():
    return sk.frames(soul_vein_frame, SOUL_PULSE)


_EMBER = None


def _ember():
    """Basalt-Grund und Rissmaske. Die Risse sind Zickzack-Linien mit Abzweigen (wie die Blitzadern, nur flacher
    gestreut und kürzer): Isolinien von Rauschen schließen sich auf 32 px zu Ringen und Kringeln (Vorschau)."""
    global _EMBER
    if _EMBER is None:
        cracks = [(1.0, 4.0, 0.35, 13, 1), (18.0, 3.0, 1.2, 12, 0), (6.0, 18.0, 1.0, 11, 0), (20.0, 21.0, 0.2, 14, 1)]
        mask, _, halo = sk.lightning(262, cracks, seg=(3, 6), spread=0.55)
        _EMBER = (sk.stone_idx(261), mask, halo)
    return _EMBER


def ember_frame(intensity):
    """Ein Bild Glutbasalt: Rissmitte EMBER 4–7, gerade Nachbarn 2–4 (Glut im Spalt), schräge Nachbarn: unten rechts
    warme Wand (EMBER 0/1), oben links Schattenwand (Basalt −2). Form fest, nur Helligkeit."""
    base, mask, halo = _ember()
    lv = sk.copy(base)
    acc = sk.empty_acc()
    heat = (intensity - 0.55) / 0.45                               # 0 … 1
    for y in range(N):
        for x in range(N):
            if mask[y][x] > 0:
                hot = heat * (0.6 + 0.4 * mask[y][x])
                acc[y][x] = ('EMBER', 4 + sk.lim(round(hot * 3), 0, 3))
            elif halo[y][x] >= 0.7:
                acc[y][x] = ('EMBER', 2 + sk.lim(round(heat * 2 * halo[y][x]), 0, 2))
            elif halo[y][x] > 0:
                lower_right = mask[sk.w(y - 1)][sk.w(x - 1)] > 0
                if lower_right:
                    acc[y][x] = ('EMBER', 1 if heat >= 0.5 else 0)  # warme Wand, die zum Licht zeigt
                else:
                    lv[y][x] = max(0, base[y][x] - 2)              # Schattenwand der Spalte
    return sk.colour(lv, 'BASALT', acc)


def ember_basalt():
    return sk.frames(ember_frame, EMBER_PULSE)


def tartarus_bronze():
    """Dunkle Bronze: zwei breite Platten mit Nietreihen, Patina-Flecken und Läufer unter den Nieten."""
    lv = sk.plates(281, cols=1, rows=2, rivets=False, hammer=False)
    for y in range(N):
        for x in range(N):
            p = sk.pn(x + 0.5, y + 0.5, 4, 283) * 0.6 + sk.pn(x + 0.5, y + 0.5, 16, 284) * 0.4
            if p > 0.7 and 1 < lv[y][x] < 6:
                lv[y][x] += 1                                      # Patina-Fleck (heller, grünlich)
            elif p < 0.28 and 2 < lv[y][x] < 6:
                lv[y][x] -= 1                                      # dunkle Bronze unter der Patina
    for ry in (2, 18):                                             # Nietreihe oben in jeder Platte
        for rx in (3, 11, 19, 27):
            lv[ry][rx] = 7
            lv[ry][rx + 1] = 5
            lv[ry + 1][rx] = 5
            lv[ry + 1][rx + 1] = 2
            run = 3 + (rx * 7 + ry) % 5                            # Läufer: Patina rinnt nach unten
            for k in range(run):
                y = ry + 3 + k
                if y % 16 >= 14:
                    break
                lv[y][rx] = sk.lim(lv[y][rx] + (1 if k < run - 1 else 0), 1, 6)
    return sk.colour(lv, 'DARK_BRONZE')


# =============================================================================================
# Liste
# =============================================================================================

# Textur-Name → (Funktion, Rampe, Akzentrampen, animiert)
TEXTURES = {
    'olympus_marble': (olympus_marble, 'MARBLE', (), False),
    'olympus_marble_bricks': (olympus_marble_bricks, 'MARBLE', (), False),
    'polished_olympus_marble': (polished_olympus_marble, 'MARBLE', (), False),
    'chiseled_olympus_marble': (chiseled_olympus_marble, 'MARBLE', ('GOLD',), False),
    'olympus_marble_pillar': (olympus_marble_pillar, 'MARBLE', (), False),
    'olympus_marble_pillar_top': (olympus_marble_pillar_top, 'MARBLE', (), False),
    'gilded_olympus_marble': (gilded_olympus_marble, 'MARBLE', ('GOLD',), False),
    'storm_marble': (storm_marble, 'STORM', ('BOLT',), True),
    'olympus_bronze': (olympus_bronze, 'BRONZE', (), False),
    'tartarus_basalt': (tartarus_basalt, 'BASALT', (), False),
    'tartarus_basalt_bricks': (tartarus_basalt_bricks, 'BASALT', (), False),
    'polished_tartarus_basalt': (polished_tartarus_basalt, 'BASALT', (), False),
    'chiseled_tartarus_basalt': (chiseled_tartarus_basalt, 'BASALT', ('SOUL',), False),
    'tartarus_basalt_pillar': (tartarus_basalt_pillar, 'BASALT', (), False),
    'tartarus_basalt_pillar_top': (tartarus_basalt_pillar_top, 'BASALT', (), False),
    'soul_vein_basalt': (soul_vein_basalt, 'BASALT', ('SOUL',), True),
    'ember_basalt': (ember_basalt, 'BASALT', ('EMBER',), True),
    'tartarus_bronze': (tartarus_bronze, 'DARK_BRONZE', (), False),
}
PULSES = {'storm_marble': STORM_PULSE, 'soul_vein_basalt': SOUL_PULSE, 'ember_basalt': EMBER_PULSE}

# Blöcke mit gezeichneter Fuge oder Fase am Rand: dort ist die Kachelnaht gewollt sichtbar (Plattenfuge, Fase wie
# Vanilla-„polished“). Für diese Richtungen (lr = linker/rechter Rand, ou = oben/unten) entfällt die Nahtprobe; statt
# dessen muss der Rand ein glatter Fasen-/Fugenzug sein (jede Randzeile höchstens 4 Rampenfarben).
FRAMED = {'polished_olympus_marble': 'lr ou', 'chiseled_olympus_marble': 'lr ou', 'olympus_marble_pillar_top': 'lr ou',
          'gilded_olympus_marble': 'lr ou', 'polished_tartarus_basalt': 'lr ou', 'chiseled_tartarus_basalt': 'lr ou',
          'tartarus_basalt_pillar_top': 'lr ou', 'tartarus_bronze': 'lr'}

# Flächenblöcke für das 3×3-Kachelbild (ohne Stirnseiten)
SURFACES = [n for n in TEXTURES if not n.endswith('_top')]


def registry_names():
    """Texturnamen der Grundblöcke laut `deco_registry.py` (ohne `storm_cloud`, gehört P05)."""
    out = []
    for bid, fam, form, ramp, accent, anim, variants in reg.BASES:
        if form == 'cloud':
            continue
        out.append(bid)
        if form == 'pillar':
            out.append(bid + '_top')
    return out


def family(name):
    for bid, fam, *_ in reg.BASES:
        if name == bid or name == bid + '_top':
            return fam
    return '?'


# =============================================================================================
# Schreiben
# =============================================================================================

_CACHE = {}


def render(name):
    if name not in _CACHE:
        _CACHE[name] = TEXTURES[name][0]()
    return _CACHE[name]


def write(name):
    """Schreibt PNG (und bei animierten `.mcmeta`) nur, wenn sich der Inhalt ändert. Gibt geänderte Pfade zurück."""
    changed = []
    path = os.path.join(TEX, name + '.png')
    img = render(name)
    old = None
    if os.path.exists(path):
        with Image.open(path) as f:
            old = f.convert('RGBA')
    if old is None or old.size != img.size or old.tobytes() != img.tobytes():
        img.save(path, optimize=True)
        changed.append(path)
    if TEXTURES[name][3]:
        mpath = path + '.mcmeta'
        text = json.dumps(MCMETA, indent=2) + '\n'
        if not os.path.exists(mpath) or open(mpath, encoding='utf8').read() != text:
            with open(mpath, 'w', encoding='utf8', newline='\n') as f:
                f.write(text)
            changed.append(mpath)
    return changed


# =============================================================================================
# Prüfen
# =============================================================================================

_JAR = None


def vanilla(name):
    """Vanilla-Blocktextur (erstes 16er-Bild) aus dem Client-Jar."""
    global _JAR
    if _JAR is None:
        _JAR = zipfile.ZipFile(CLIENT_JAR)
    with Image.open(io.BytesIO(_JAR.read(f'assets/minecraft/textures/block/{name}.png'))) as f:
        img = f.convert('RGBA')
    return img.crop((0, 0, img.width, img.width))


def pixels(img):
    return list(img.get_flattened_data() if hasattr(img, 'get_flattened_data') else img.getdata())


def mean_rgb(img):
    px = pixels(img.convert('RGB'))
    return tuple(sum(p[i] for p in px) / len(px) for i in range(3))


def lightness(img):
    """L*-Raster (0–100) eines Bildes."""
    rgb = img.convert('RGB')
    cache = {}
    out = []
    for y in range(rgb.height):
        row = []
        for x in range(rgb.width):
            c = rgb.getpixel((x, y))
            if c not in cache:
                cache[c] = sk.srgb_lab(c)[0]
            row.append(cache[c])
        out.append(row)
    return out


def seam_report(img):
    """Kachelnaht: mittlere L*-Differenz über die Naht (rechts→links, unten→oben) gegen die Spalten-/Zeilengrenzen im
    Inneren. Bestanden, wenn die Naht nicht stärker ist als die stärkste Grenze im Inneren (gleiches Muster)."""
    lum = lightness(img)
    n = len(lum)

    def col_diff(a, b):
        return sum(abs(lum[y][a] - lum[y][b]) for y in range(n)) / n

    def row_diff(a, b):
        return sum(abs(lum[a][x] - lum[b][x]) for x in range(n)) / n
    cols = [col_diff(x, x + 1) for x in range(n - 1)]
    rows = [row_diff(y, y + 1) for y in range(n - 1)]
    return (col_diff(n - 1, 0), max(cols), sorted(cols)[len(cols) // 2]), (row_diff(n - 1, 0), max(rows), sorted(rows)[len(rows) // 2])


def split_frames(img):
    return [img.crop((0, k * N, N, (k + 1) * N)) for k in range(img.height // N)]


def check():
    problems = []
    notes = []
    names = registry_names()
    if sorted(names) != sorted(TEXTURES):
        problems.append(f'Texturliste weicht von deco_registry ab: nur Registry {sorted(set(names) - set(TEXTURES))}, '
                        f'nur hier {sorted(set(TEXTURES) - set(names))}')
    for name in names:
        if name not in TEXTURES:
            continue
        fn, ramp, accents, anim = TEXTURES[name]
        path = os.path.join(TEX, name + '.png')
        if not os.path.exists(path):
            problems.append(f'{name}: Datei fehlt')
            continue
        with Image.open(path) as f:
            img = f.convert('RGBA')
        if img.tobytes() != render(name).tobytes():
            problems.append(f'{name}: Datei weicht vom Generator ab (Skript laufen lassen)')
        want = (N, N * 8) if anim else (N, N)
        if img.size != want:
            problems.append(f'{name}: Größe {img.size}, erwartet {want}')
        if anim:
            mpath = path + '.mcmeta'
            meta = json.load(open(mpath, encoding='utf8')) if os.path.exists(mpath) else None
            if meta != MCMETA:
                problems.append(f'{name}: .mcmeta {meta}, erwartet {MCMETA}')
        allowed = set(sk.RAMPS[ramp])
        for a in accents:
            allowed |= set(sk.RAMPS[a])
        alien = {p[:3] for p in pixels(img) if p[:3] not in allowed or p[3] != 255}
        if alien:
            problems.append(f'{name}: {len(alien)} Farben außerhalb Rampe ∪ Akzent, z. B. {sorted(alien)[:3]}')
        framed = FRAMED.get(name, '')
        edges = []
        if 'lr' in framed:
            edges += [('links', [img.getpixel((0, y)) for y in range(1, N - 1)]),
                      ('rechts', [img.getpixel((N - 1, y)) for y in range(1, N - 1)])]
        if 'ou' in framed:
            edges += [('oben', [img.getpixel((x, 0)) for x in range(1, N - 1)]),
                      ('unten', [img.getpixel((x, N - 1)) for x in range(1, N - 1)])]
        for edge, line in edges:
            if len(set(line)) > 4:
                problems.append(f'{name}: Rand {edge} ist kein glatter Fugenzug ({len(set(line))} Farben)')
        if framed:
            notes.append(f'{name:28s} gewollte Fuge/Fase am Rand ({framed}), Randzeilen glatt')
        for k, frame in enumerate(split_frames(img)):
            (hs, hmax, hmed), (vs, vmax, vmed) = seam_report(frame)
            tag = f'{name}' + (f' Bild {k}' if anim else '')
            if hs > hmax + 1e-9 and 'lr' not in framed:
                problems.append(f'{tag}: senkrechte Naht {hs:.1f} > stärkste Innengrenze {hmax:.1f}')
            if vs > vmax + 1e-9 and 'ou' not in framed:
                problems.append(f'{tag}: waagerechte Naht {vs:.1f} > stärkste Innengrenze {vmax:.1f}')
            if k == 0:
                notes.append(f'{name:28s} Naht L* li/re {hs:4.1f} (innen Median {hmed:4.1f}, max {hmax:4.1f})  '
                             f'ob/un {vs:4.1f} (Median {vmed:4.1f}, max {vmax:4.1f})')
        if anim:
            means = [sum(map(sum, lightness(fr))) / (N * N) for fr in split_frames(img)]
            steps = [abs(means[(k + 1) % 8] - means[k]) / max(means[(k + 1) % 8], means[k]) for k in range(8)]
            worst = max(steps)
            notes.append(f'{name:28s} Puls: mittleres L* {" ".join(f"{m:.1f}" for m in means)}; größter Schritt {worst * 100:.0f} %')
            if worst > MAX_PULSE_STEP:
                problems.append(f'{name}: Helligkeitssprung {worst * 100:.0f} % > {MAX_PULSE_STEP * 100:.0f} %')
    # ΔE der Grundtöne gegen Vanilla
    for ramp, refs, limit in (('MARBLE', ('quartz_block_side', 'calcite'), MARBLE_DE),
                              ('BASALT', ('polished_blackstone', 'deepslate_tiles'), BASALT_DE)):
        base = sk.RAMPS[ramp][4]
        des = {r: sk.delta_e(base, mean_rgb(vanilla(r))) for r in refs}
        best = min(des.values())
        notes.append(f'{ramp} Grundton #{"%02x%02x%02x" % base}: ΔE ' + ', '.join(f'{r} {d:.1f}' for r, d in des.items()))
        if best > limit:
            problems.append(f'{ramp}: Grundton ΔE {best:.1f} > {limit} zu {refs}')
    for name, refs in (('olympus_marble', ('quartz_block_side', 'calcite')), ('polished_olympus_marble', ('quartz_block_side', 'calcite')),
                       ('olympus_marble_bricks', ('quartz_bricks', 'calcite')),
                       ('tartarus_basalt', ('polished_blackstone', 'deepslate_tiles')),
                       ('polished_tartarus_basalt', ('polished_blackstone', 'deepslate_tiles')),
                       ('tartarus_basalt_bricks', ('polished_blackstone_bricks', 'deepslate_tiles'))):
        m = mean_rgb(render(name))
        notes.append(f'{name:28s} Mittel #{"%02x%02x%02x" % tuple(round(v) for v in m)}: ΔE ' +
                     ', '.join(f'{r} {sk.delta_e(m, mean_rgb(vanilla(r))):.1f}' for r in refs))
    return problems, notes


# =============================================================================================
# Belege
# =============================================================================================

SCALE = 8
BG = (14, 14, 18)
FG = (226, 226, 230)


def font(size=14):
    for name in ('arial.ttf', 'segoeui.ttf', 'DejaVuSans.ttf'):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def up(img, scale=SCALE):
    return img.resize((img.width * scale, img.height * scale), Image.NEAREST)


def sheet(items, cols, cell, out, title=None, pad=16, label_h=22):
    """Raster aus (Beschriftung, Bild) mit Titelzeile."""
    rows = (len(items) + cols - 1) // cols
    head = 30 if title else 0
    W = pad + cols * (cell[0] + pad)
    H = head + pad + rows * (cell[1] + label_h + pad)
    img = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(img)
    if title:
        d.text((pad, 8), title, fill=FG, font=font(16))
    for i, (label, tile) in enumerate(items):
        x = pad + (i % cols) * (cell[0] + pad)
        y = head + pad + (i // cols) * (cell[1] + label_h + pad)
        d.text((x, y), label, fill=FG, font=font(13))
        img.paste(tile.convert('RGB'), (x, y + label_h))
    img.save(out)
    return out


def tiled(img, n=3):
    t = Image.new('RGBA', (img.width * n, img.height * n))
    for j in range(n):
        for i in range(n):
            t.paste(img, (i * img.width, j * img.height))
    return t


def first(name):
    img = render(name)
    return img.crop((0, 0, N, N))


# --- DecoPalettes (Port aus K:world/DecoPalettes.kt und Kit.noise, nur für den Wandbeleg) -----------------------

def _i32(v):
    v &= 0xFFFFFFFF
    return v - (1 << 32) if v & 0x80000000 else v


def kit_noise(x, y, z):
    h = _i32(_i32(x * 73856093) ^ _i32(y * 19349663) ^ _i32(z * 83492791))
    h = _i32(h ^ ((h & 0xFFFFFFFF) >> 13))
    h = _i32(h * 0x5bd1e995)
    h = _i32(h ^ ((h & 0xFFFFFFFF) >> 15))
    return h & 0xFFFF


def _pick(x, y, z, mod):
    return kit_noise(x, y, z) % mod


def _patch(x, y, z, size, mod):
    return kit_noise(x // size, y // size, z // size) % mod


def _worn(x, y, z, amount):
    return amount > 0 and kit_noise(x + 7, y + 13, z + 29) % 1000 < amount * 1000


def marble_wall(x, y, z, wear=0.08):
    if _worn(x, y, z, wear):
        return 'v:diorite' if _pick(x, y, z, 2) == 0 else 'v:calcite'
    if _patch(x, y, z, 3, 7) == 0:
        return 'v:quartz_bricks'
    if _pick(x, y, z, 13) == 0:
        return 'olympus_marble'
    return 'olympus_marble_bricks'


def marble_floor(x, y, z, wear=0.05):
    if _worn(x, y, z, wear):
        return 'v:polished_diorite'
    if _patch(x, y, z, 4, 6) == 0:
        return 'v:quartz_block_bottom'
    return 'polished_olympus_marble'


def basalt_wall(x, y, z, wear=0.10):
    if _worn(x, y, z, wear):
        return 'v:cracked_polished_blackstone_bricks' if _pick(x, y, z, 2) == 0 else 'v:blackstone'
    if _patch(x, y, z, 3, 6) == 0:
        return 'v:polished_blackstone_bricks'
    if _pick(x, y, z, 15) == 0:
        return 'tartarus_basalt'
    return 'tartarus_basalt_bricks'


def basalt_floor(x, y, z, wear=0.06):
    if _worn(x, y, z, wear):
        return 'v:cracked_deepslate_tiles'
    if _patch(x, y, z, 4, 5) == 0:
        return 'v:deepslate_tiles'
    return 'polished_tartarus_basalt'


def bronze(x, y, z, wear=0.06):
    if _worn(x, y, z, wear):
        return 'v:exposed_cut_copper'
    if _pick(x, y, z, 9) == 0:
        return 'v:cut_copper'
    return 'olympus_bronze'


def dark_bronze(x, y, z, wear=0.08):
    if _worn(x, y, z, wear):
        return 'v:weathered_cut_copper'
    if _pick(x, y, z, 9) == 0:
        return 'v:oxidized_cut_copper'
    return 'tartarus_bronze'


def block_face(key):
    """32-px-Fläche eines Blocks: Deko aus dem Generator, Vanilla (16 px) nächstnachbarlich verdoppelt."""
    if key.startswith('v:'):
        return vanilla(key[2:]).resize((N, N), Image.NEAREST)
    return first(key)


def probe(pal, floor=False, cols=8, rows=6, ox=40, oy=70, oz=-12):
    img = Image.new('RGBA', (cols * N, rows * N))
    deco = 0
    for j in range(rows):
        for i in range(cols):
            x, y, z = (ox + i, 64, oz + j) if floor else (ox + i, oy + rows - 1 - j, oz)
            key = pal(x, y, z)
            deco += not key.startswith('v:')
            img.paste(block_face(key), (i * N, j * N))
    return img, deco / (cols * rows)


def previews(out):
    os.makedirs(out, exist_ok=True)
    written = []
    cell = (N * SCALE, N * SCALE)
    for fam, fname, title in (('olymp', 'sheet_olymp.png', 'Olymp: alle Grundblöcke (32 px, ×8; animierte: Bild mit Höchstwert)'),
                              ('tartaros', 'sheet_tartaros.png', 'Tartaros: alle Grundblöcke (32 px, ×8; animierte: Bild mit Höchstwert)')):
        items = []
        for name in TEXTURES:
            if family(name) != fam:
                continue
            img = render(name)
            if TEXTURES[name][3]:
                k = PULSES[name].index(max(PULSES[name]))
                img = split_frames(img)[k]
                name = f'{name} (Bild {k})'
            items.append((name, up(img)))
        written.append(sheet(items, 4, cell, os.path.join(out, fname), title))
    # 3×3: je Flächenblock (×4, damit das Blatt handlich bleibt; ein Block = 128 px)
    items = [(n, up(tiled(split_frames(render(n))[0]), 4)) for n in SURFACES]
    written.append(sheet(items, 4, (N * 3 * 4, N * 3 * 4), os.path.join(out, 'tiling_3x3.png'),
                         'Kachelung 3×3 je Flächenblock (×4; animierte: Bild 0)'))
    # Puls-Bilder
    items = []
    for name in PULSES:
        for k, fr in enumerate(split_frames(render(name))):
            items.append((f'{name} f{k}  I={PULSES[name][k]}', up(fr, 6)))
    written.append(sheet(items, 8, (N * 6, N * 6), os.path.join(out, 'anim_frames.png'),
                         'Puls-Bilder (×6), frametime 3, interpolate: nur Helligkeit ändert sich'))
    # Neben Vanilla: Deko 32 px und Vanilla 16 px in gleicher Blockgröße (×8 bzw. ×16)
    rows = [
        ('Marmor', ['olympus_marble', 'polished_olympus_marble', 'olympus_marble_bricks'],
         ['quartz_block_side', 'calcite', 'polished_diorite', 'quartz_bricks']),
        ('Basalt', ['tartarus_basalt', 'polished_tartarus_basalt', 'tartarus_basalt_bricks'],
         ['polished_blackstone', 'deepslate_tiles', 'polished_blackstone_bricks']),
        ('Bronze', ['olympus_bronze', 'tartarus_bronze'],
         ['cut_copper', 'copper_block', 'exposed_cut_copper', 'oxidized_cut_copper']),
    ]
    items = []
    for _, decos, vans in rows:
        for d in decos:
            items.append((d, up(first(d))))
        for v in vans:
            items.append((f'Vanilla {v}', up(vanilla(v), 16)))
        while len(items) % 7:
            items.append(('', Image.new('RGB', cell, BG)))
    written.append(sheet(items, 7, cell, os.path.join(out, 'neben_vanilla.png'),
                         'Neben Vanilla (gleiche Blockgröße; Deko 32 px ×8, Vanilla 16 px ×16)'))
    # Wandprobe 8 × 6 je Palette
    items = []
    for label, pal, floor in (('marbleWall', marble_wall, False), ('marbleFloor (Draufsicht)', marble_floor, True),
                              ('bronze', bronze, False), ('basaltWall', basalt_wall, False),
                              ('basaltFloor (Draufsicht)', basalt_floor, True), ('darkBronze', dark_bronze, False)):
        img, share = probe(pal, floor)
        items.append((f'DecoPalettes.{label}: Deko-Anteil {share * 100:.0f} %', up(img, 2)))
    written.append(sheet(items, 3, (8 * N * 2, 6 * N * 2), os.path.join(out, 'wand_probe.png'),
                         'Wandprobe 8×6 aus DecoPalettes (Port von Kit.noise), ×2'))
    # Stufenkante: Seitenansicht Block | Stufe (untere Texturhälfte) | Treppe (untere Hälfte + Viertel oben rechts),
    # je zweimal nebeneinander, damit die Kante zwischen gleichen Stufen sichtbar wird
    items = []
    for base in ('olympus_marble_bricks', 'polished_olympus_marble', 'olympus_bronze', 'tartarus_basalt_bricks',
                 'polished_tartarus_basalt', 'tartarus_bronze'):
        t = first(base)
        panel = Image.new('RGBA', (N * 6, N), BG + (255,))
        for i in range(2):
            panel.paste(t, (i * N, 0))
            panel.paste(t.crop((0, 16, N, N)), ((2 + i) * N, 16))
            panel.paste(t.crop((0, 16, N, N)), ((4 + i) * N, 16))
            panel.paste(t.crop((16, 0, N, 16)), ((4 + i) * N + 16, 0))
        items.append((f'{base}: Block | Stufe | Treppe', up(panel, 3)))
    written.append(sheet(items, 2, (N * 6 * 3, N * 3), os.path.join(out, 'stufen_kante.png'),
                         'Seitenansicht Block, Stufe, Treppe (je zweimal; Stufen nutzen die Textur des Grundblocks), ×3'))
    return written


# =============================================================================================

def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    args = sys.argv[1:]
    if '--check' in args:
        problems, notes = check()
        for n in notes:
            print('  ' + n)
        if problems:
            print(f'deco_textures --check: {len(problems)} Probleme')
            for p in problems:
                print('  FEHLER ' + p)
            sys.exit(1)
        print(f'deco_textures --check: grün ({len(TEXTURES)} Texturen)')
        return
    if '--preview' in args:
        out = args[args.index('--preview') + 1]
        for p in previews(out):
            print('  ' + os.path.relpath(p, ROOT))
        return
    only = [args[i + 1] for i, a in enumerate(args) if a == '--only']
    for o in only:
        if o not in TEXTURES:
            raise SystemExit(f'unbekannt: {o} (bekannt: {", ".join(TEXTURES)})')
    names = only or list(TEXTURES)
    changed = []
    for name in names:
        changed += write(name)
    print(f'deco_textures: {len(names)} Texturen, {len(changed)} Dateien geschrieben')
    for p in changed:
        print('  ' + os.path.relpath(p, ROOT))


if __name__ == '__main__':
    main()
