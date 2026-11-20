"""Alle Dateien der Deko- und Geräte-Blöcke (Überarbeitung 4, PLAN 2.1/7.1; P00, danach eingefroren).

Aufruf (aus dem Projektordner):
    python tools/hha_assets/deco_registry.py           # schreibt alles, was fehlt oder abweicht (zweiter Lauf: keine Änderung)
    python tools/hha_assets/deco_registry.py --check   # prüft nur: jede Datei vorhanden, IDs = HhaDecoBlocks.kt, Sprache de/en

Schreibt je Block: Blockstate, Blockmodelle, `items/<id>.json`, Loot (`survives_explosion`, Stufe doppelt = 2; Geräte und
`storm_cloud` ohne), Tags (`mineable/pickaxe`, `stairs`, `slabs`, `walls` als Block- und Item-Tag), Steinsäge-Rezepte je
Familie (Natur → Ziegel/poliert/gemeißelt/Säule und deren Varianten; Ziegel/poliert/Bronze → eigene Varianten) und die
Blocknamen de/en. Treppe, Stufe und Mauer kommen aus dem Minecraft-Jar (Vorlage `stone_brick_*`, nur Namen ersetzt).

**Platzhalter-Texturen** (32 × 32, Grundton der Rampe, 1 px dunklerer Rand; animierte als Streifen mit `.mcmeta`) werden
nur geschrieben, wenn die Datei fehlt: P01 (Grundblöcke) und P05 (Geräte, Wolke) ersetzen sie. Ebenso schreibt das
Skript Blockstates und Modelle der Geräte und der Wolke nur, wenn sie fehlen (P05 ersetzt sie).
"""
import glob
import json
import os
import re
import sys
import zipfile

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RES = os.path.join(ROOT, 'src', 'main', 'resources')
ASSETS = os.path.join(RES, 'assets', 'hha')
DATA = os.path.join(RES, 'data')
KOTLIN = os.path.join(ROOT, 'src', 'main', 'kotlin', 'dev', 'henny', 'hha', 'HhaDecoBlocks.kt')

# Rampen aus PLAN 2.2 (Index 0 dunkel … 7 hell, 4 = Grundton; CLOUD hat 5 Stufen).
RAMPS = {
    'MARBLE': '6f6a7c 8d8898 aaa5b0 c3bec4 d8d3cf e7e2da f3efe6 fffbf2',
    'GOLD': '4a2408 6e3a0c 935414 b8761e d69a2c ecbd48 f8db7c fff3c4',
    'BRONZE': '2e1a0c 4a2a12 6b3e18 8c5622 ad7230 c9924a e0b56e f3d9a0',
    'STORM': '10131f 1a2033 26304a 344363 465a80 5d769f 7e9bc0 b3cdea',
    'CLOUD': '3b4152 525a6e 6c7689 8892a3 a9b2c0',
    'BASALT': '0a080d 15121a 1f1c25 2b2632 383140 453d4e 54495c 685a71',   # wie stonekit.RAMPS (P01: Buntheit −30 %)
    'SOUL': '04240e 0b4a1c 0e7a2c 22a83e 2fd14c 5cf07a 9dffb0 e8ffd0',
    'EMBER': '2a0a06 5a1408 8c2a0a c2480e e8731c ff9c3a ffc878 fff0c8',
    'DARK_BRONZE': '0d1210 1a211c 2a332a 3d4a3a 55634a 74805c 98a078 c2c8a0',
}
RAMPS = {k: [tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) for h in v.split()] for k, v in RAMPS.items()}

SOUL_PULSE = [0.22, 1.0, 0.6, 0.88, 0.5, 0.32, 0.25, 0.22]
EMBER_PULSE = [0.55, 1.0, 0.75, 0.92, 0.7, 0.6, 0.56, 0.55]
STORM_PULSE = [0.15, 0.2, 1.0, 0.45, 0.7, 0.3, 0.2, 0.15]      # wie deco_textures.py („Wetterleuchten“)
CLOUD_PULSE = [0.5 + 0.5 * __import__('math').sin(i / 16 * 6.283185307) for i in range(16)]

# Grundblöcke: id, Familie, Form, Rampe, Akzent, Animation (Bilder, frametime, Puls), Varianten
BASES = [
    ('olympus_marble', 'olymp', 'cube', 'MARBLE', None, None, []),
    ('olympus_marble_bricks', 'olymp', 'cube', 'MARBLE', None, None, ['stairs', 'slab', 'wall']),
    ('polished_olympus_marble', 'olymp', 'cube', 'MARBLE', None, None, ['stairs', 'slab']),
    ('chiseled_olympus_marble', 'olymp', 'cube', 'MARBLE', 'GOLD', None, []),
    ('olympus_marble_pillar', 'olymp', 'pillar', 'MARBLE', None, None, []),
    ('gilded_olympus_marble', 'olymp', 'cube', 'MARBLE', 'GOLD', None, []),
    ('storm_marble', 'olymp', 'cube', 'STORM', 'STORM', (8, 3, STORM_PULSE), []),
    ('olympus_bronze', 'olymp', 'cube', 'BRONZE', None, None, ['stairs', 'slab']),
    ('storm_cloud', 'olymp', 'cloud', 'CLOUD', None, (16, 4, CLOUD_PULSE), []),
    ('tartarus_basalt', 'tartaros', 'cube', 'BASALT', None, None, []),
    ('tartarus_basalt_bricks', 'tartaros', 'cube', 'BASALT', None, None, ['stairs', 'slab', 'wall']),
    ('polished_tartarus_basalt', 'tartaros', 'cube', 'BASALT', None, None, ['stairs', 'slab']),
    ('chiseled_tartarus_basalt', 'tartaros', 'cube', 'BASALT', 'SOUL', None, []),
    ('tartarus_basalt_pillar', 'tartaros', 'pillar', 'BASALT', None, None, []),
    ('soul_vein_basalt', 'tartaros', 'cube', 'BASALT', 'SOUL', (8, 3, SOUL_PULSE), []),
    ('ember_basalt', 'tartaros', 'cube', 'BASALT', 'EMBER', (8, 3, EMBER_PULSE), []),
    ('tartarus_bronze', 'tartaros', 'cube', 'DARK_BRONZE', None, None, ['stairs', 'slab']),
]
DEVICES = [
    ('messenger_bell', 'GOLD'),
    ('grounding_rod', 'BRONZE'),
    ('chain_post', 'DARK_BRONZE'),
    ('sluice_wheel', 'DARK_BRONZE'),
]

NAMES = {
    'olympus_marble': ('Olymp-Marmor', 'Olympus Marble'),
    'olympus_marble_bricks': ('Olymp-Marmorziegel', 'Olympus Marble Bricks'),
    'olympus_marble_brick_stairs': ('Olymp-Marmorziegeltreppe', 'Olympus Marble Brick Stairs'),
    'olympus_marble_brick_slab': ('Olymp-Marmorziegelstufe', 'Olympus Marble Brick Slab'),
    'olympus_marble_brick_wall': ('Olymp-Marmorziegelmauer', 'Olympus Marble Brick Wall'),
    'polished_olympus_marble': ('Polierter Olymp-Marmor', 'Polished Olympus Marble'),
    'polished_olympus_marble_stairs': ('Polierte Olymp-Marmortreppe', 'Polished Olympus Marble Stairs'),
    'polished_olympus_marble_slab': ('Polierte Olymp-Marmorstufe', 'Polished Olympus Marble Slab'),
    'chiseled_olympus_marble': ('Gemeißelter Olymp-Marmor', 'Chiseled Olympus Marble'),
    'olympus_marble_pillar': ('Olymp-Marmorsäule', 'Olympus Marble Pillar'),
    'gilded_olympus_marble': ('Vergoldeter Olymp-Marmor', 'Gilded Olympus Marble'),
    'storm_marble': ('Sturmmarmor', 'Storm Marble'),
    'olympus_bronze': ('Olymp-Bronze', 'Olympus Bronze'),
    'olympus_bronze_stairs': ('Olymp-Bronzetreppe', 'Olympus Bronze Stairs'),
    'olympus_bronze_slab': ('Olymp-Bronzestufe', 'Olympus Bronze Slab'),
    'storm_cloud': ('Gewitterwolke', 'Storm Cloud'),
    'tartarus_basalt': ('Tartaros-Basalt', 'Tartarus Basalt'),
    'tartarus_basalt_bricks': ('Tartaros-Basaltziegel', 'Tartarus Basalt Bricks'),
    'tartarus_basalt_brick_stairs': ('Tartaros-Basaltziegeltreppe', 'Tartarus Basalt Brick Stairs'),
    'tartarus_basalt_brick_slab': ('Tartaros-Basaltziegelstufe', 'Tartarus Basalt Brick Slab'),
    'tartarus_basalt_brick_wall': ('Tartaros-Basaltziegelmauer', 'Tartarus Basalt Brick Wall'),
    'polished_tartarus_basalt': ('Polierter Tartaros-Basalt', 'Polished Tartarus Basalt'),
    'polished_tartarus_basalt_stairs': ('Polierte Tartaros-Basalttreppe', 'Polished Tartarus Basalt Stairs'),
    'polished_tartarus_basalt_slab': ('Polierte Tartaros-Basaltstufe', 'Polished Tartarus Basalt Slab'),
    'chiseled_tartarus_basalt': ('Gemeißelter Tartaros-Basalt', 'Chiseled Tartarus Basalt'),
    'tartarus_basalt_pillar': ('Tartaros-Basaltsäule', 'Tartarus Basalt Pillar'),
    'soul_vein_basalt': ('Seelenader-Basalt', 'Soul Vein Basalt'),
    'ember_basalt': ('Glutbasalt', 'Ember Basalt'),
    'tartarus_bronze': ('Tartaros-Bronze', 'Tartarus Bronze'),
    'tartarus_bronze_stairs': ('Tartaros-Bronzetreppe', 'Tartarus Bronze Stairs'),
    'tartarus_bronze_slab': ('Tartaros-Bronzestufe', 'Tartarus Bronze Slab'),
    'messenger_bell': ('Botenglocke', 'Messenger Bell'),
    'grounding_rod': ('Erdungsstab', 'Grounding Rod'),
    'chain_post': ('Kettenpfahl', 'Chain Post'),
    'sluice_wheel': ('Schleusenrad', 'Sluice Wheel'),
}
TAB = ('itemGroup.hha.world', 'H&H Welt', 'H&H World')

# Steinsäge: (Zutat, Ergebnis, Anzahl)
STONECUT = []
for nat, fam in (('olympus_marble', 'olymp'), ('tartarus_basalt', 'tartaros')):
    pre = 'olympus_marble' if fam == 'olymp' else 'tartarus_basalt'
    bricks, polished = f'{pre}_bricks', f'polished_{pre}'
    targets = [(bricks, 1), (polished, 1), (f'chiseled_{pre}', 1), (f'{pre}_pillar', 1),
               (f'{pre}_brick_stairs', 1), (f'{pre}_brick_slab', 2), (f'{pre}_brick_wall', 1),
               (f'{polished}_stairs', 1), (f'{polished}_slab', 2)]
    STONECUT += [(nat, t, n) for t, n in targets]
    STONECUT += [(bricks, f'{pre}_brick_stairs', 1), (bricks, f'{pre}_brick_slab', 2), (bricks, f'{pre}_brick_wall', 1)]
    STONECUT += [(polished, f'{polished}_stairs', 1), (polished, f'{polished}_slab', 2)]
for bronze in ('olympus_bronze', 'tartarus_bronze'):
    STONECUT += [(bronze, f'{bronze}_stairs', 1), (bronze, f'{bronze}_slab', 2)]


def variant_id(base, kind):
    stem = base[:-1] if base.endswith('_bricks') else base   # Ziegel: Vanilla-Schreibweise (stone_brick_stairs)
    return f'{stem}_{kind}'


def all_blocks():
    """(id, form, Grundblock, Rampe, Akzent, Animation) in der Reihenfolge von HhaDecoBlocks.ALL."""
    out = []
    for bid, fam, form, ramp, accent, anim, variants in BASES:
        out.append((bid, form, None, ramp, accent, anim))
        for v in variants:
            out.append((variant_id(bid, v), v, bid, ramp, accent, anim))
    for bid, ramp in DEVICES:
        out.append((bid, 'device', None, ramp, None, None))
    return out


# ---------------------------------------------------------------------------------------------
# Vorlagen aus dem Minecraft-Jar
# ---------------------------------------------------------------------------------------------

def jar():
    paths = glob.glob(os.path.expanduser('~/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar'))
    if not paths:
        raise SystemExit('Minecraft-Jar 1.21.11 nicht gefunden (~/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar)')
    return zipfile.ZipFile(paths[0])


_JAR = None


def vanilla(path):
    global _JAR
    if _JAR is None:
        _JAR = jar()
    return _JAR.read(path).decode('utf8')


def renamed(path, pairs):
    text = vanilla(path)
    for a, b in pairs:
        text = text.replace(a, b)
    return json.loads(text)


# ---------------------------------------------------------------------------------------------
# Schreiben (nur bei Abweichung) und Prüfen
# ---------------------------------------------------------------------------------------------

CHANGED = []
EXPECTED = []


def put_json(path, obj, only_if_missing=False):
    EXPECTED.append(path)
    text = json.dumps(obj, indent=2, ensure_ascii=False) + '\n'
    if os.path.exists(path):
        if only_if_missing:
            return
        if open(path, encoding='utf8').read() == text:
            return
    if CHECK:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf8', newline='\n') as f:
        f.write(text)
    CHANGED.append(path)


def put_png(path, make, mcmeta=None):
    EXPECTED.append(path)
    if mcmeta is not None:
        put_json(path + '.mcmeta', mcmeta, only_if_missing=True)
    if os.path.exists(path) or CHECK:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    make().save(path)
    CHANGED.append(path)


def blend(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def plate(ramp, accent=None, glow=0.0, alpha=255, ring=False, centre=None):
    """Platzhalter 32 × 32: Grundton (Index 4 bzw. 3 bei CLOUD), Rand 1 px Index 2, optional Akzent in der Mitte."""
    r = RAMPS[ramp]
    base = r[4] if len(r) > 4 and ramp != 'CLOUD' else r[3]
    edge = r[2]
    img = Image.new('RGBA', (32, 32), base + (alpha,))
    px = img.load()
    for i in range(32):
        for j in (0, 31):
            px[i, j] = edge + (alpha,)
            px[j, i] = edge + (alpha,)
    if ring:
        for i in range(6, 26):
            for j in (6, 25):
                px[i, j] = r[3] + (alpha,)
                px[j, i] = r[3] + (alpha,)
    if accent is not None or centre is not None:
        a = RAMPS[accent] if accent else None
        col = centre if centre is not None else blend(a[3], a[6], glow)
        for x in range(12, 20):
            for y in range(12, 20):
                px[x, y] = col + (alpha,)
    return img


def strip(frames, ramp, accent, pulse, alpha=255):
    img = Image.new('RGBA', (32, 32 * frames))
    for k in range(frames):
        if ramp == 'CLOUD':
            r = RAMPS['CLOUD']
            tile = plate('CLOUD', alpha=round(150 + 65 * pulse[k]), centre=blend(r[3], r[4], pulse[k]))
        else:
            tile = plate(ramp, accent, glow=pulse[k])
        img.paste(tile, (0, 32 * k))
    return img


def write_all():
    bs = os.path.join(ASSETS, 'blockstates')
    md = os.path.join(ASSETS, 'models', 'block')
    it = os.path.join(ASSETS, 'items')
    tx = os.path.join(ASSETS, 'textures', 'block')
    loot = os.path.join(DATA, 'hha', 'loot_table', 'blocks')
    tags = {'mineable/pickaxe': [], 'stairs': [], 'slabs': [], 'walls': []}
    for bid, form, base, ramp, accent, anim in all_blocks():
        hid = f'hha:{bid}'
        if form in ('cube', 'cloud'):
            p05 = form == 'cloud'
            put_json(f'{bs}/{bid}.json', {'variants': {'': {'model': f'hha:block/{bid}'}}}, only_if_missing=p05)
            put_json(f'{md}/{bid}.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'hha:block/{bid}'}}, only_if_missing=p05)
            put_json(f'{it}/{bid}.json', {'model': {'type': 'minecraft:model', 'model': f'hha:block/{bid}'}})
            if anim:
                frames, ft, pulse = anim
                put_png(f'{tx}/{bid}.png', lambda r=ramp, a=accent, f=frames, p=pulse: strip(f, r, a, p),
                        {'animation': {'frametime': ft, 'interpolate': True}})
            else:
                put_png(f'{tx}/{bid}.png', lambda r=ramp, a=accent: plate(r, a, glow=0.4))
        elif form == 'pillar':
            put_json(f'{bs}/{bid}.json', renamed('assets/minecraft/blockstates/quartz_pillar.json', [('minecraft:block/quartz_pillar', f'hha:block/{bid}')]))
            put_json(f'{md}/{bid}.json', {'parent': 'minecraft:block/cube_column', 'textures': {'end': f'hha:block/{bid}_top', 'side': f'hha:block/{bid}'}})
            put_json(f'{md}/{bid}_horizontal.json', {'parent': 'minecraft:block/cube_column_horizontal', 'textures': {'end': f'hha:block/{bid}_top', 'side': f'hha:block/{bid}'}})
            put_json(f'{it}/{bid}.json', {'model': {'type': 'minecraft:model', 'model': f'hha:block/{bid}'}})
            put_png(f'{tx}/{bid}.png', lambda r=ramp: plate(r))
            put_png(f'{tx}/{bid}_top.png', lambda r=ramp: plate(r, ring=True))
        elif form == 'stairs':
            pairs = [('minecraft:block/stone_brick_stairs', f'hha:block/{bid}'), ('minecraft:block/stone_bricks', f'hha:block/{base}')]
            put_json(f'{bs}/{bid}.json', renamed('assets/minecraft/blockstates/stone_brick_stairs.json', pairs))
            for suffix in ('', '_inner', '_outer'):
                put_json(f'{md}/{bid}{suffix}.json', renamed(f'assets/minecraft/models/block/stone_brick_stairs{suffix}.json', pairs))
            put_json(f'{it}/{bid}.json', {'model': {'type': 'minecraft:model', 'model': f'hha:block/{bid}'}})
            tags['stairs'].append(hid)
        elif form == 'slab':
            pairs = [('minecraft:block/stone_brick_slab', f'hha:block/{bid}'), ('minecraft:block/stone_bricks', f'hha:block/{base}')]
            put_json(f'{bs}/{bid}.json', renamed('assets/minecraft/blockstates/stone_brick_slab.json', pairs))
            for suffix in ('', '_top'):
                put_json(f'{md}/{bid}{suffix}.json', renamed(f'assets/minecraft/models/block/stone_brick_slab{suffix}.json', pairs))
            put_json(f'{it}/{bid}.json', {'model': {'type': 'minecraft:model', 'model': f'hha:block/{bid}'}})
            tags['slabs'].append(hid)
        elif form == 'wall':
            pairs = [('minecraft:block/stone_brick_wall', f'hha:block/{bid}'), ('minecraft:block/stone_bricks', f'hha:block/{base}')]
            put_json(f'{bs}/{bid}.json', renamed('assets/minecraft/blockstates/stone_brick_wall.json', pairs))
            for suffix in ('_post', '_side', '_side_tall', '_inventory'):
                put_json(f'{md}/{bid}{suffix}.json', renamed(f'assets/minecraft/models/block/stone_brick_wall{suffix}.json', pairs))
            put_json(f'{it}/{bid}.json', {'model': {'type': 'minecraft:model', 'model': f'hha:block/{bid}_inventory'}})
            tags['walls'].append(hid)
        elif form == 'device':
            variants = {}
            for facing, rot in (('north', 0), ('east', 90), ('south', 180), ('west', 270)):
                for mode in range(3):
                    v = {'model': f'hha:block/{bid}_mode{mode}'}
                    if rot:
                        v['y'] = rot
                    variants[f'facing={facing},mode={mode}'] = v
            put_json(f'{bs}/{bid}.json', {'variants': variants}, only_if_missing=True)
            for mode in range(3):
                put_json(f'{md}/{bid}_mode{mode}.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'hha:block/{bid}_mode{mode}'}}, only_if_missing=True)
                centre = [RAMPS['MARBLE'][2], RAMPS['GOLD'][6], RAMPS['BASALT'][1]][mode]
                put_png(f'{tx}/{bid}_mode{mode}.png', lambda r=ramp, c=centre: plate(r, centre=c))
            put_json(f'{it}/{bid}.json', {'model': {'type': 'minecraft:model', 'model': f'hha:block/{bid}_mode0'}})
        # Loot: Geräte und Wolke ohne (dropsNothing)
        if form in ('cube', 'pillar', 'stairs', 'wall'):
            put_json(f'{loot}/{bid}.json', renamed('data/minecraft/loot_table/blocks/stone_bricks.json', [('minecraft:stone_bricks', hid), ('minecraft:blocks/stone_bricks', f'hha:blocks/{bid}')]))
        elif form == 'slab':
            put_json(f'{loot}/{bid}.json', renamed('data/minecraft/loot_table/blocks/stone_brick_slab.json', [('minecraft:stone_brick_slab', hid), ('minecraft:blocks/stone_brick_slab', f'hha:blocks/{bid}')]))
        if form not in ('device', 'cloud'):
            tags['mineable/pickaxe'].append(hid)
    for tag, values in tags.items():
        put_json(os.path.join(DATA, 'minecraft', 'tags', 'block', f'{tag}.json'), {'replace': False, 'values': values})
        if tag != 'mineable/pickaxe':
            put_json(os.path.join(DATA, 'minecraft', 'tags', 'item', f'{tag}.json'), {'replace': False, 'values': values})
    for ing, res, n in STONECUT:
        put_json(os.path.join(DATA, 'hha', 'recipe', f'{res}_from_{ing}_stonecutting.json'),
                 {'type': 'minecraft:stonecutting', 'ingredient': f'hha:{ing}', 'result': {'count': n, 'id': f'hha:{res}'}})
    write_lang()


def write_lang():
    for idx, name in ((0, 'de_de.json'), (1, 'en_us.json')):
        path = os.path.join(ASSETS, 'lang', name)
        raw = open(path, encoding='utf8').read()
        data = json.loads(raw)
        before = json.dumps(data, ensure_ascii=False)
        data[TAB[0]] = TAB[1 + idx]
        for bid, pair in NAMES.items():
            data[f'block.hha.{bid}'] = pair[idx]
        if CHECK:
            missing = [k for k in [TAB[0]] + [f'block.hha.{b}' for b in NAMES] if k not in json.loads(raw)]
            if missing:
                PROBLEMS.append(f'{name}: fehlende Schlüssel {missing[:5]} …')
            continue
        if json.dumps(data, ensure_ascii=False) != before:
            with open(path, 'w', encoding='utf8', newline='\n') as f:
                f.write(json.dumps(data, indent=2, ensure_ascii=False) + '\n')
            CHANGED.append(path)


PROBLEMS = []
CHECK = '--check' in sys.argv


def check_kotlin():
    text = open(KOTLIN, encoding='utf8').read()
    block = re.search(r'val DECO_IDS: Set<String> = linkedSetOf\((.*?)\)\n', text, re.S).group(1)
    kotlin_ids = re.findall(r'"([a-z_]+)"', block)
    ids = [b[0] for b in all_blocks()]
    if kotlin_ids != ids:
        PROBLEMS.append(f'HhaDecoBlocks.DECO_IDS weicht ab: nur Kotlin {sorted(set(kotlin_ids) - set(ids))}, nur Python {sorted(set(ids) - set(kotlin_ids))}, Reihenfolge {"gleich" if sorted(kotlin_ids) == sorted(ids) else "-"}')
    registered = re.findall(r'(?:cube|pillar|stairs|slab|wall|device|register)\(\s*"([a-z_]+)"', text)
    if sorted(registered) != sorted(ids):
        PROBLEMS.append(f'HhaDecoBlocks registriert {len(registered)} IDs, erwartet {len(ids)}: {sorted(set(ids) ^ set(registered))}')


def main():
    write_all()
    check_kotlin()
    missing = [p for p in EXPECTED if not os.path.exists(p)]
    if CHECK:
        for p in missing:
            PROBLEMS.append('fehlt: ' + os.path.relpath(p, ROOT))
        n = len(all_blocks())
        if PROBLEMS:
            print(f'deco_registry --check: {len(PROBLEMS)} Probleme')
            for p in PROBLEMS[:40]:
                print('  ' + p)
            sys.exit(1)
        print(f'deco_registry --check: vollständig ({n} Blöcke, {len(EXPECTED)} Dateien, Sprache de/en, IDs = HhaDecoBlocks.kt)')
        return
    for p in PROBLEMS:
        print('WARNUNG ' + p)
    print(f'deco_registry: {len(all_blocks())} Blöcke, {len(EXPECTED)} Dateien geprüft, {len(CHANGED)} geschrieben')
    for p in CHANGED[:10]:
        print('  ' + os.path.relpath(p, ROOT))
    if len(CHANGED) > 10:
        print(f'  … und {len(CHANGED) - 10} weitere')


if __name__ == '__main__':
    main()
