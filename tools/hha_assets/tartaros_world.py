"""Schreibt die Weltdaten des Tartaros: Rauschen, Gelände, Oberflächen, Biome und ihre Dekoration.

Der Tartaros ist ein ödes Ödland: eine fast flache Ascheebene (Höhe 68 bis 76) mit leichten Wellen, ohne Berge,
Schluchten, Flüsse, schwebende Felsen oder Lava. Was auffällt, sind die Bauwerke. Die sechs Biome sind große, ruhige
Flächen mit je eigenem Boden, eigenem Nebel und höchstens einem seltenen Merkmal.

Jede Oberfläche setzt sich aus drei Rauschen zusammen:
- `tartaros_patch`: große Flecken (Verwehungen, Schollen, Moosfelder), etwa 60 Blöcke je Welle,
- `tartaros_speckle`: feine Sprenkel, wenige Blöcke groß,
- `tartaros_crack`: Höhenlinien eines dritten Rauschens; ein schmales Band um null ergibt 1 Block breite Adern und Risse.

Die Tabelle SURFACES ist die einzige Quelle: Aus ihr entstehen die Oberflächenregeln im JSON, und preview_terrain.py setzt
mit denselben Schwellen eine Bodenkarte zusammen.

Aufruf: python tartaros_world.py
"""
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', 'src', 'main', 'resources', 'data', 'hha'))


def write(rel, data):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf8', newline='\n') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def remove(rel):
    path = os.path.join(ROOT, rel)
    if os.path.exists(path):
        os.remove(path)


# --- Dichte-Bausteine --------------------------------------------------------------------------------------

def add(a, b):
    return {'type': 'minecraft:add', 'argument1': a, 'argument2': b}


def mul(a, b):
    return {'type': 'minecraft:mul', 'argument1': a, 'argument2': b}


def clamp(a, lo, hi):
    return {'type': 'minecraft:clamp', 'input': a, 'min': lo, 'max': hi}


def noise(name, xz=1.0, y=0.0):
    return {'type': 'minecraft:noise', 'noise': name, 'xz_scale': xz, 'y_scale': y}


def height(y0, y1, v0, v1):
    return {'type': 'minecraft:y_clamped_gradient', 'from_y': y0, 'to_y': y1, 'from_value': v0, 'to_value': v1}


def total(*parts):
    out = parts[0]
    for p in parts[1:]:
        out = add(out, p)
    return out


CONTINENT = noise('hha:tartaros_continent')
# Bestimmt nur noch, wo der Erebos liegt (Weirdness der Biomwahl); der Name stammt aus der Zeit der Schluchten.
SHADE_MASK = noise('hha:tartaros_chasm_mask')

BIOMES = ['tartaros', 'asphodel', 'phlegethon', 'kokytos', 'erebos', 'styx_marsh']

# Biome werden größer und ruhiger: 0,27 statt 0,4 (kleiner heißt größere Flächen).
CLIMATE_SCALE = 0.27


def terrain():
    # Der Boden liegt im Mittel auf y = 72, je Block ändert sich die Dichte um 1/70.
    base = height(2, 142, 1.0, -1.0)
    # Fast flach: nur ein paar Blöcke Wellen, Dünen und Risse.
    rolling = total(
        mul(0.05, CONTINENT),
        mul(0.04, noise('hha:tartaros_dunes')),
        mul(0.018, noise('hha:tartaros_ridges')),
        mul(0.006, noise('hha:tartaros_ripple')),
    )
    # Ganz selten eine flache Anhöhe, damit der Horizont nicht völlig leer ist.
    rise = mul(0.12, clamp(add(CONTINENT, -0.35), 0.0, 1.0))
    return {'type': 'minecraft:interpolated', 'argument': total(base, rolling, rise)}


# --- Oberflächen -------------------------------------------------------------------------------------------

HUGE = 1.7976931348623157e+308

# Je Biom: Grund (wenn keine Regel greift), Untergrund (die Lagen darunter) und Regeln in Rangfolge. Eine Regel ist
# (Block, [(Rauschen, von, bis), ...]); alle Bedingungen müssen stimmen, die erste passende Regel gewinnt.
SURFACES = {
    # Aschenöde: graue Ascheverwehungen auf Schwarzstein unter rotem Dunst.
    'tartaros': {
        'base': 'minecraft:blackstone', 'soil': 'minecraft:blackstone',
        'rules': [
            ('minecraft:soul_soil', [('speckle', 0.5, HUGE)]),
            ('minecraft:basalt', [('speckle', -HUGE, -0.52)]),
            ('minecraft:light_gray_concrete_powder', [('patch', 0.34, HUGE)]),
            ('minecraft:gray_concrete_powder', [('patch', 0.12, 0.34)]),
            ('minecraft:gravel', [('patch', -HUGE, -0.36)]),
        ],
    },
    # Asphodeloswiesen: bleiche Geisterwiese aus Tuff, Blassmoos und Kalk.
    'asphodel': {
        'base': 'minecraft:tuff', 'soil': 'minecraft:tuff',
        'rules': [
            ('minecraft:calcite', [('speckle', 0.5, HUGE)]),
            ('minecraft:pale_moss_block', [('patch', 0.1, HUGE)]),
            ('minecraft:gravel', [('patch', -HUGE, -0.38)]),
        ],
    },
    # Phlegethon: verbrannte, rissige Erde; die Glut zeigt sich nur in schmalen Adern.
    'phlegethon': {
        'base': 'minecraft:basalt', 'soil': 'minecraft:blackstone',
        'rules': [
            ('minecraft:magma_block', [('crack', -0.012, 0.012), ('patch', -0.25, HUGE)]),
            ('minecraft:cracked_nether_bricks', [('speckle', 0.58, HUGE)]),
            ('minecraft:smooth_basalt', [('patch', 0.26, HUGE)]),
            ('minecraft:blackstone', [('patch', -HUGE, -0.3)]),
        ],
    },
    # Kokytos: gefrorene Klage. Eisschollen mit Rissen aus Blaueis, Reif aus Kalk.
    'kokytos': {
        'base': 'minecraft:cobbled_deepslate', 'soil': 'minecraft:cobbled_deepslate',
        'rules': [
            ('minecraft:blue_ice', [('crack', -0.016, 0.016), ('patch', 0.16, HUGE)]),
            ('minecraft:calcite', [('speckle', 0.52, HUGE)]),
            ('minecraft:packed_ice', [('patch', 0.16, HUGE)]),
            ('minecraft:deepslate', [('patch', -HUGE, -0.32)]),
        ],
    },
    # Erebos: fast schwarze Finsternis aus Schwarzstein, Obsidian und Sculk.
    'erebos': {
        'base': 'minecraft:blackstone', 'soil': 'minecraft:blackstone',
        'rules': [
            ('minecraft:crying_obsidian', [('speckle', 0.7, HUGE)]),
            ('minecraft:obsidian', [('patch', 0.28, HUGE)]),
            ('minecraft:sculk', [('patch', -HUGE, -0.32)]),
        ],
    },
    # Sümpfe des Styx: ausgetrockneter Sumpf. Schlamm, Trockenrisse aus Lehm, Seelenerde.
    'styx_marsh': {
        'base': 'minecraft:mud', 'soil': 'minecraft:mud',
        'rules': [
            ('minecraft:rooted_dirt', [('speckle', 0.52, HUGE)]),
            ('minecraft:mud', [('crack', -0.03, 0.03), ('patch', 0.14, HUGE)]),
            ('minecraft:packed_mud', [('patch', 0.14, HUGE)]),
            ('minecraft:soul_soil', [('patch', -HUGE, -0.34)]),
        ],
    },
}

NOISE_IDS = {'patch': 'hha:tartaros_patch', 'speckle': 'hha:tartaros_speckle', 'crack': 'hha:tartaros_crack'}


def condition(test, then):
    return {'type': 'minecraft:condition', 'if_true': test, 'then_run': then}


def block(name):
    state = {'Name': name}
    if name == 'minecraft:basalt':
        state['Properties'] = {'axis': 'y'}
    return {'type': 'minecraft:block', 'result_state': state}


def sequence(*rules):
    return {'type': 'minecraft:sequence', 'sequence': list(rules)}


def threshold(name, lo, hi):
    return {'type': 'minecraft:noise_threshold', 'noise': NOISE_IDS[name], 'min_threshold': lo, 'max_threshold': hi}


def rule(name, tests):
    out = block(name)
    for test in reversed(tests):
        out = condition(threshold(*test), out)
    return out


def top():
    return {'type': 'minecraft:stone_depth', 'add_surface_depth': False, 'offset': 0, 'secondary_depth_range': 0, 'surface_type': 'floor'}


def under():
    return {'type': 'minecraft:stone_depth', 'add_surface_depth': True, 'offset': 1, 'secondary_depth_range': 2, 'surface_type': 'floor'}


def in_biome(name):
    return {'type': 'minecraft:biome', 'biome_is': [f'hha:{name}']}


def surface():
    rules = [condition({
        'type': 'minecraft:vertical_gradient', 'false_at_and_above': {'above_bottom': 5}, 'random_name': 'minecraft:bedrock_floor',
        'true_at_and_below': {'above_bottom': 0},
    }, block('minecraft:bedrock'))]
    for name, spec in SURFACES.items():
        surface_rule = sequence(*[rule(b, tests) for b, tests in spec['rules']], block(spec['base']))
        rules.append(condition(in_biome(name), sequence(condition(top(), surface_rule), condition(under(), block(spec['soil'])))))
    return sequence(*rules)


def surface_block(biome, values):
    """Dieselbe Auswahl wie die Oberflächenregeln, für die Vorschau: values = {'patch': .., 'speckle': .., 'crack': ..}."""
    spec = SURFACES.get(biome)
    if not spec:
        return None
    for name, tests in spec['rules']:
        if all(lo <= values[n] <= hi for n, lo, hi in tests):
            return name
    return spec['base']


def noise_settings():
    return {
        'aquifers_enabled': False,
        'default_block': {'Name': 'minecraft:blackstone'},
        # Kein Wasser, keine Lava: Der Meeresspiegel liegt tief unter dem Boden, Höhlen gibt es keine.
        'default_fluid': {'Name': 'minecraft:water', 'Properties': {'level': '0'}},
        'disable_mob_generation': False,
        'legacy_random_source': True,
        'noise': {'min_y': 0, 'height': 256, 'size_horizontal': 2, 'size_vertical': 2},
        'noise_router': {
            'barrier': 0.0,
            'continents': CONTINENT,
            'depth': 0.0,
            'erosion': 0.0,
            'final_density': terrain(),
            'fluid_level_floodedness': 0.0,
            'fluid_level_spread': 0.0,
            'lava': 0.0,
            'preliminary_surface_level': 0.0,
            'ridges': SHADE_MASK,
            'temperature': {'type': 'minecraft:shifted_noise', 'noise': 'minecraft:temperature', 'shift_x': 'minecraft:shift_x', 'shift_y': 0.0, 'shift_z': 'minecraft:shift_z', 'xz_scale': CLIMATE_SCALE, 'y_scale': 0.0},
            'vegetation': {'type': 'minecraft:shifted_noise', 'noise': 'minecraft:vegetation', 'shift_x': 'minecraft:shift_x', 'shift_y': 0.0, 'shift_z': 'minecraft:shift_z', 'xz_scale': CLIMATE_SCALE, 'y_scale': 0.0},
            'vein_gap': 0.0,
            'vein_ridged': 0.0,
            'vein_toggle': 0.0,
        },
        'ore_veins_enabled': False,
        'sea_level': 15,
        'spawn_target': [],
        'surface_rule': surface(),
    }


def noises():
    # firstOctave -9 heißt rund 512 Blöcke je Welle.
    for name, octave, amps in (
        ('dunes', -7, [1.0, 0.6, 0.3]), ('ridges', -9, [1.0, 0.4]), ('ripple', -5, [1.0]),
        ('continent', -10, [1.0, 0.5, 0.25]), ('chasm_mask', -10, [1.0, 0.3]),
        ('patch', -6, [1.0, 0.6, 0.35]), ('speckle', -2, [1.0, 0.5]), ('crack', -4, [1.0, 0.25]),
    ):
        write(f'worldgen/noise/tartaros_{name}.json', {'firstOctave': octave, 'amplitudes': amps})
    # Was nichts mehr tut, fliegt raus: Gipfel, Flüsse, Schluchten, Überhänge, schwebende Inseln.
    for name in ('peaks', 'river', 'chasm', 'overhang', 'island'):
        remove(f'worldgen/noise/tartaros_{name}.json')


def box(t=(-1.0, 1.0), h=(-1.0, 1.0), c=(-1.0, 1.0), w=(-1.0, 1.0)):
    return {'temperature': list(t), 'humidity': list(h), 'continentalness': list(c), 'erosion': [-1.0, 1.0], 'weirdness': list(w), 'depth': [-1.0, 1.0], 'offset': 0.0}


def dimension():
    plain = (-1.0, 0.25)
    land = (-0.25, 1.0)
    entries = [
        ('erebos', box(w=(0.45, 1.0))),
        ('styx_marsh', box(c=(-1.0, -0.25), w=plain)),
        ('phlegethon', box(t=(0.2, 1.0), c=land, w=plain)),
        ('kokytos', box(t=(-1.0, -0.25), c=land, w=plain)),
        ('asphodel', box(t=(-0.25, 0.2), h=(0.05, 1.0), c=land, w=plain)),
        ('tartaros', box(t=(-0.25, 0.2), h=(-1.0, 0.05), c=land, w=plain)),
    ]
    write('dimension/tartaros.json', {
        'type': 'hha:tartaros',
        'generator': {
            'type': 'minecraft:noise',
            'biome_source': {'type': 'minecraft:multi_noise', 'biomes': [{'biome': f'hha:{n}', 'parameters': p} for n, p in entries]},
            'settings': 'hha:tartaros',
        },
    })


# Reihenfolge gilt für alle Biome. Minecraft stürzt ab, wenn zwei Biome Features widersprüchlich sortieren.
VEGETAL = [
    'hha:tartaros_ruin', 'hha:tartaros_carcass', 'hha:tartaros_dead_tree', 'hha:phlegethon_vent', 'hha:kokytos_shard', 'hha:cold_shade',
    'hha:erebos_stele', 'hha:marsh_stump', 'hha:asphodel_flowers', 'hha:marsh_plants', 'hha:erebos_veins',
]

SOUNDS = {
    'tartaros': 'soul_sand_valley', 'asphodel': 'soul_sand_valley', 'phlegethon': 'basalt_deltas',
    'kokytos': 'warped_forest', 'erebos': 'crimson_forest', 'styx_marsh': 'warped_forest',
}
MUSIC = {
    'tartaros': 'soul_sand_valley', 'asphodel': 'soul_sand_valley', 'phlegethon': 'basalt_deltas',
    'kokytos': 'soul_sand_valley', 'erebos': 'crimson_forest', 'styx_marsh': 'warped_forest',
}
# Nebel, Himmel, Wasser, Partikel, Dekoration, Sichtweite. Weite Sicht und wenig Deko: Die Leere soll man sehen.
LOOK = {
    'tartaros': ('#6e3428', '#260b07', '#39ff5a', [('minecraft:ash', 0.02)],
                 ['hha:tartaros_ruin', 'hha:tartaros_carcass', 'hha:tartaros_dead_tree'], 170.0),
    'asphodel': ('#8a938c', '#23282b', '#6adfa0', [('minecraft:white_ash', 0.014)],
                 ['hha:tartaros_ruin', 'hha:tartaros_dead_tree', 'hha:asphodel_flowers'], 160.0),
    'phlegethon': ('#86472a', '#2a1006', '#ff7a2a', [('minecraft:ash', 0.03), ('minecraft:small_flame', 0.003)],
                   ['hha:tartaros_ruin', 'hha:phlegethon_vent'], 150.0),
    'kokytos': ('#7f8fa3', '#1c242e', '#7fd4ff', [('minecraft:white_ash', 0.016), ('minecraft:snowflake', 0.006)],
                ['hha:tartaros_ruin', 'hha:kokytos_shard', 'hha:cold_shade'], 160.0),
    'erebos': ('#2a2030', '#0a0610', '#3a7a5a', [('minecraft:reverse_portal', 0.004), ('minecraft:ash', 0.008)],
               ['hha:tartaros_ruin', 'hha:erebos_stele', 'hha:erebos_veins'], 110.0),
    'styx_marsh': ('#4a5040', '#151a12', '#39ff5a', [('minecraft:ash', 0.012)],
                   ['hha:tartaros_ruin', 'hha:marsh_stump', 'hha:marsh_plants'], 140.0),
}


def biome(name):
    fog, sky, water, particles, features, fog_end = LOOK[name]
    sound = SOUNDS[name]
    vegetal = [f for f in VEGETAL if f in features]
    steps = [[] for _ in range(11)]
    steps[9] = vegetal
    return {
        'attributes': {
            'minecraft:audio/ambient_sounds': {
                'additions': {'sound': f'minecraft:ambient.{sound}.additions', 'tick_chance': 0.0111},
                'loop': f'minecraft:ambient.{sound}.loop',
                'mood': {'block_search_extent': 8, 'offset': 2.0, 'sound': f'minecraft:ambient.{sound}.mood', 'tick_delay': 6000},
            },
            'minecraft:audio/background_music': {'default': {'max_delay': 24000, 'min_delay': 12000, 'sound': f'minecraft:music.nether.{MUSIC[name]}'}},
            'minecraft:visual/ambient_particles': [{'particle': {'type': p}, 'probability': v} for p, v in particles if v > 0],
            'minecraft:visual/fog_color': fog,
            'minecraft:visual/fog_end_distance': fog_end,
            'minecraft:visual/sky_color': sky,
        },
        'carvers': [],
        'downfall': 0.0,
        'effects': {'water_color': water},
        'features': steps,
        'has_precipitation': False,
        'spawn_costs': {},
        'spawners': {k: [] for k in ('ambient', 'axolotls', 'creature', 'misc', 'monster', 'underground_water_creature', 'water_ambient', 'water_creature')},
        'temperature': 2.0,
    }


def simple_feature(name):
    return {'type': f'hha:{name}', 'config': {}}


def placed(feature, chance=None, extra=()):
    placement = []
    if chance:
        placement.append({'type': 'minecraft:rarity_filter', 'chance': chance})
    placement += [{'type': 'minecraft:in_square'}, {'type': 'minecraft:heightmap', 'heightmap': 'WORLD_SURFACE_WG'}, {'type': 'minecraft:biome'}]
    placement += list(extra)
    return {'feature': feature, 'placement': placement}


def state(name):
    """'minecraft:sculk_vein[down=true]' -> Blockzustand fürs JSON."""
    if '[' not in name:
        return {'Name': name}
    base, props = name[:-1].split('[')
    return {'Name': base, 'Properties': dict(p.split('=') for p in props.split(','))}


def patch_of(states, tries, spread=7):
    """Streut Pflanzen auf freien Boden, wo sie wachsen können."""
    entries = [{'data': state(n), 'weight': w} for n, w in states]
    return {
        'type': 'minecraft:random_patch',
        'config': {
            'feature': {
                'feature': {'type': 'minecraft:simple_block', 'config': {'to_place': {'type': 'minecraft:weighted_state_provider', 'entries': entries}}},
                'placement': [{'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:all_of', 'predicates': [
                    {'type': 'minecraft:matching_blocks', 'blocks': 'minecraft:air'},
                    {'type': 'minecraft:would_survive', 'state': state(states[0][0])},
                ]}}],
            },
            'tries': tries,
            'xz_spread': spread,
            'y_spread': 3,
        },
    }


def scattered(feature, count=None, chance=None):
    placement = []
    if count:
        placement.append({'type': 'minecraft:count', 'count': count})
    if chance:
        placement.append({'type': 'minecraft:rarity_filter', 'chance': chance})
    placement += [{'type': 'minecraft:in_square'}, {'type': 'minecraft:heightmap', 'heightmap': 'WORLD_SURFACE_WG'}, {'type': 'minecraft:biome'}]
    return {'feature': feature, 'placement': placement}


def features():
    # Seltene Merkmale aus Code; die Zahl ist „einmal in so vielen Chunks“.
    for name, chance in (
        ('tartaros_ruin', 48), ('tartaros_carcass', 80), ('tartaros_dead_tree', 16), ('cold_shade', 14),
        ('phlegethon_vent', 10), ('kokytos_shard', 9), ('erebos_stele', 12), ('marsh_stump', 8),
    ):
        write(f'worldgen/configured_feature/{name}.json', simple_feature(name))
        write(f'worldgen/placed_feature/{name}.json', placed(f'hha:{name}', chance))
    write('worldgen/configured_feature/asphodel_flowers.json', patch_of([
        ('minecraft:closed_eyeblossom', 5), ('minecraft:open_eyeblossom', 3), ('minecraft:lily_of_the_valley', 3),
        ('minecraft:white_tulip', 2), ('minecraft:azure_bluet', 2), ('minecraft:short_dry_grass', 4),
    ], 56))
    write('worldgen/placed_feature/asphodel_flowers.json', scattered('hha:asphodel_flowers', count=3))
    # Kein brauner Pilz: Unter freiem Himmel (Himmelslicht 15) überlebt er in Vanilla nicht.
    write('worldgen/configured_feature/marsh_plants.json', patch_of([
        ('minecraft:dead_bush', 3), ('minecraft:short_dry_grass', 5), ('minecraft:tall_dry_grass', 1),
    ], 16))
    write('worldgen/placed_feature/marsh_plants.json', scattered('hha:marsh_plants', chance=3))
    write('worldgen/configured_feature/erebos_veins.json', patch_of([('minecraft:sculk_vein[down=true]', 1)], 24, 6))
    write('worldgen/placed_feature/erebos_veins.json', scattered('hha:erebos_veins', chance=2))
    # Alte Features ohne Aufgabe: Flüsse, grüne Kristallnadeln, Feuerfelder.
    for name in ('tartaros_river', 'green_spire', 'tartaros_green_fire'):
        remove(f'worldgen/configured_feature/{name}.json')
    for name in ('tartaros_river', 'tartaros_spire', 'tartaros_green_fire'):
        remove(f'worldgen/placed_feature/{name}.json')


def tags():
    write('tags/worldgen/biome/has_tartarus_structure.json', {'values': [f'hha:{n}' for n in BIOMES]})


def main():
    noises()
    write('worldgen/noise_settings/tartaros.json', noise_settings())
    dimension()
    features()
    tags()
    for name in BIOMES:
        write(f'worldgen/biome/{name}.json', biome(name))
    print('Tartaros-Weltdaten geschrieben')


if __name__ == '__main__':
    main()
