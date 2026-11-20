"""Synthetisiert die Klänge der Wege und Arena-Geräte (Überarbeitung 4, P06; PLAN 5.4, 7.10).

Aufruf: python approach_sounds.py [--only ambient.approach_heart,arena.bell] [--spectrogram ordner]
(numpy nötig, siehe synth.py: pip install numpy --target <ordner>, dann PYTHONPATH setzen; ffmpeg im Pfad).

- Neun Anmarsch-Pulse `ambient.approach_*`: kurze Einzelschläge (0,6–1,4 s), mono, tief, leise ausklingend. Approach.kt
  spielt sie je Spieler im Takt `34 − 20 · k` Ticks, Lautstärke `0,07 + 0,38 · k²`; die Dateien selbst sind darum auf
  einen festen Spitzenpegel normiert, die Lautheit kommt aus dem Spiel.
- Zwölf Arena-Klänge `arena.*` (Geräte, Teilziele, Deckung), mono, damit Minecraft sie im Raum platzieren kann.

Schreibt die OGG-Dateien nach assets/hha/sounds/ambient/ bzw. sounds/arena/ und die Schnipsel nach
tmp/overhaul4/snippets/P06/ (sounds.json ersetzt die Platzhalter-Zuordnung aus P00; lang_de/lang_en mit Untertiteln).
Am Ende eine Tabelle: ID, Länge, Spitzenpegel (dBFS), RMS.
"""
import json
import math
import os
import sys

import numpy as np

from boss_sounds import fade_out, layer, room, tone_sweep
from synth import RATE, anvil, bandpass, bell, check, distort, encode, highpass, lowpass, noise, phase_of, samples, spectrogram_png, sweep, taiko, timeline
from texkit import ASSETS

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SNIPPETS = os.path.join(ROOT, 'tmp', 'overhaul4', 'snippets', 'P06')


def mono(x):
    return x.mean(axis=1) if x.ndim == 2 else x


def env_exp(t, at, rate):
    """Einschwingen bei `at`, danach exponentiell abklingend."""
    return np.exp(-np.maximum(t - at, 0.0) * rate) * (t >= at)


def thump(t, at, f0, f1, decay, glide=22.0):
    """Tiefer Körperschlag: Sinus, der von f0 nach f1 gleitet."""
    local = np.maximum(t - at, 0.0)
    pitch = f1 + (f0 - f1) * np.exp(-local * glide)
    return np.sin(2.0 * np.pi * np.cumsum(pitch) / RATE) * env_exp(t, at, decay)


# --- Anmarsch-Pulse (ambient.approach_*) ---------------------------------------------------------------------


def approach_wind():
    """Weg der Boten: ein Windstoß schwillt an und fällt, darin zwei, drei feine Glöckchen (Windspiel)."""
    seconds = 1.3
    t = timeline(seconds)
    k = t / seconds
    gust = sweep(noise(seconds, 601), lambda f, x: np.exp(-(((f - (300 + 900 * np.sin(np.pi * x))) / (260 + 500 * x)) ** 2)))
    gust *= np.sin(np.pi * np.minimum(1.0, k * 1.1)) ** 1.6
    chimes = sum(g * np.sin(2.0 * np.pi * f * t) * env_exp(t, at, 7.0) for f, at, g in ((2093.0, 0.22, 0.10), (2637.0, 0.41, 0.08), (3136.0, 0.58, 0.06)))
    low = lowpass(noise(seconds, 602), 140) * np.sin(np.pi * k) * 0.5
    return room(fade_out(gust * 0.9 + chimes + low, 0.2), 0.8, 0.22, 3.0)


def approach_howl():
    """Weg der Schwelle: fernes Heulen eines Hundes, gedämpft, wie über das Tal hinweg."""
    seconds = 1.4
    t = timeline(seconds)
    k = t / seconds
    pitch = 260.0 + 150.0 * np.sin(np.pi * np.minimum(1.0, k * 1.2)) ** 1.4 + 4.0 * np.sin(t * 29.0)
    phase = phase_of(pitch)
    raw = sum(np.sin(2.0 * np.pi * h * phase) / h ** 1.3 for h in range(1, 12))
    formed = sweep(raw, lambda f, x: np.exp(-(((f - (340 + 220 * x)) / 160.0) ** 2)) + 0.3 * np.exp(-(((f - (800 + 200 * x)) / 220.0) ** 2)))
    env = np.minimum(1.0, t / 0.18) * np.minimum(1.0, (seconds - t) / 0.7) ** 1.4
    return room(lowpass(formed * env, 1500), 1.1, 0.35, 2.4)


def approach_anvil():
    """Schlackenweg: ferner Ambossschlag mit langem Hall aus der Schmiede."""
    seconds = 1.4
    ring = mono(anvil(640.0, seconds, 611))
    t = timeline(seconds)
    body = tone_sweep(130, 60, seconds) * np.exp(-t * 16.0) * 0.9
    return room(lowpass(fade_out(ring * 0.9 + body, 0.3), 2600), 1.2, 0.4, 2.2)


def approach_drums():
    """Heerstraße: ein einzelner Kriegstrommelschlag, tief und trocken, mit kurzem Fell-Nachklang."""
    seconds = 1.0
    hit = mono(taiko(58.0, seconds, 621))
    t = timeline(seconds)
    skin = bandpass(noise(seconds, 622), 220, 160) * np.exp(-t * 30.0) * 0.6
    return room(fade_out(lowpass(hit + skin, 900), 0.2), 0.6, 0.15, 3.5)


def approach_thunder():
    """Himmelstreppe: fernes Donnergrollen, das kurz anrollt und wegsackt."""
    seconds = 1.4
    t = timeline(seconds)
    k = t / seconds
    rumble = lowpass(noise(seconds, 631), 160) * 2.2
    crackle = lowpass(noise(seconds, 632), 700) * (np.abs(np.sin(t * 23.0)) ** 6) * 0.5
    shape = np.minimum(1.0, t / 0.08) * np.exp(-np.maximum(t - 0.15, 0.0) * 2.4)
    flicker = 0.75 + 0.25 * np.sin(2.0 * np.pi * phase_of(np.full(len(t), 7.0)))
    return room(fade_out((rumble + crackle) * shape * flicker * (1.0 - 0.3 * k), 0.3), 0.9, 0.3, 2.0)


def approach_heart():
    """Palast: Herzschlag „lub-dub“, zwei tiefe Schläge, der zweite leiser und etwas höher."""
    seconds = 0.8
    t = timeline(seconds)
    lub = thump(t, 0.0, 70.0, 42.0, 11.0)
    dub = thump(t, 0.26, 82.0, 50.0, 13.0) * 0.7
    knock = lowpass(noise(seconds, 641), 300) * (env_exp(t, 0.0, 70.0) + env_exp(t, 0.26, 80.0) * 0.6) * 0.3
    return fade_out(lowpass(distort(lub + dub + knock, 1.15), 200), 0.12)


def approach_ticking():
    """Palast nach Hades: Uhrwerk, ein trockener Tick und ein tiefes Tock eines großen Rades, leise nachhallend."""
    seconds = 0.7
    t = timeline(seconds)
    tick = bandpass(noise(seconds, 651), 2600, 1200) * env_exp(t, 0.0, 160.0)
    tock = thump(t, 0.09, 220.0, 150.0, 22.0, 40.0) * 0.6 + bandpass(noise(seconds, 652), 700, 300) * env_exp(t, 0.09, 60.0) * 0.5
    gear = sum(g * np.sin(2.0 * np.pi * f * t) * env_exp(t, 0.09, 14.0) for f, g in ((587.0, 0.05), (881.0, 0.03)))
    return room(fade_out(tick + tock + gear, 0.1), 0.6, 0.25, 4.0)


def approach_chains():
    """Feste und Kerker: kurzes, schweres Kettenrasseln, gedämpft hinter Mauern."""
    seconds = 1.0
    out = np.zeros(samples(seconds))
    gen = np.random.default_rng(661)
    for _ in range(26):
        length = samples(gen.uniform(0.04, 0.12))
        start = int(gen.uniform(0.0, 0.75) ** 1.5 * len(out))
        m = np.arange(length) / RATE
        f = gen.uniform(700, 2200)
        piece = sum(np.sin(2.0 * np.pi * f * r * m) * g for r, g in ((1.0, 1.0), (1.51, 0.55), (2.37, 0.3))) * np.exp(-m * gen.uniform(35, 80))
        end = min(start + length, len(out))
        out[start:end] += piece[:end - start] * gen.uniform(0.2, 0.7)
    t = timeline(seconds)
    weight = thump(t, 0.02, 90.0, 55.0, 14.0) * 0.5
    return room(fade_out(lowpass(out, 2000) + weight, 0.2), 0.8, 0.3, 3.0)


def approach_dread():
    """Strafstätten: dumpfer Atem, ein langes Ausatmen über einem tiefen Summen."""
    seconds = 1.3
    t = timeline(seconds)
    k = t / seconds
    breath = sweep(noise(seconds, 671), lambda f, x: np.exp(-(((f - (700 - 300 * x)) / 420.0) ** 2)))
    shape = np.minimum(1.0, t / 0.25) * np.minimum(1.0, (seconds - t) / 0.8) ** 1.5
    hum = sum(g * np.sin(2.0 * np.pi * f * t) for f, g in ((55.0, 0.35), (82.5, 0.15), (110.5, 0.08))) * np.sin(np.pi * k) ** 1.2
    return room(fade_out(lowpass(breath * shape * 0.9 + hum, 1600), 0.25), 0.9, 0.3, 2.6)


# --- Arena (arena.*) -----------------------------------------------------------------------------------------


def arena_bell():
    """Botenglocke: heller Bronzeschlag mit langen Teiltönen."""
    return fade_out(mono(bell(659.0, 2.2)) * 0.9, 0.3)


def arena_rod():
    """Erdungsstab: Hebel rastet ein, dann eine knisternde Entladung, die in den Boden fährt."""
    seconds = 1.1
    t = timeline(seconds)
    lever = bandpass(noise(seconds, 701), 1800, 900) * env_exp(t, 0.0, 120.0) + thump(t, 0.0, 300.0, 180.0, 40.0) * 0.4
    crackle = highpass(noise(seconds, 702), 2500) * (np.abs(np.sin(t * 61.0)) ** 8 + 0.2) * env_exp(t, 0.12, 4.0) * (t >= 0.12) * 0.7
    zap = np.sin(2.0 * np.pi * phase_of(1200.0 * np.exp(-np.maximum(t - 0.12, 0) * 3.0) + 80.0)) * env_exp(t, 0.12, 6.0) * 0.25
    ground = thump(t, 0.14, 110.0, 45.0, 6.0) * 0.6
    return room(fade_out(lever + crackle + zap + ground, 0.2), 0.6, 0.2)


def arena_post():
    """Kettenpfahl stürzt: Holz und Bronze schlagen auf, die Kette rasselt hinterher."""
    seconds = 1.3
    t = timeline(seconds)
    impact = thump(t, 0.05, 140.0, 50.0, 9.0) + lowpass(noise(seconds, 711), 600) * env_exp(t, 0.05, 30.0) * 0.6
    chain = approach_chains()
    out = layer(impact, np.concatenate([np.zeros(samples(0.12)), chain * 0.6]))
    return room(fade_out(out, 0.2), 0.6, 0.18)


def arena_sluice():
    """Schleusenrad: das Rad klackt mehrmals, dann rauscht Wasser durch."""
    seconds = 1.6
    t = timeline(seconds)
    clicks = sum(bandpass(noise(seconds, 721 + i), 1500, 700) * env_exp(t, 0.12 * i, 90.0) * (0.9 - 0.1 * i) for i in range(5))
    water = sweep(noise(seconds, 726), lambda f, x: 1.0 / (1.0 + (f / (500 + 1500 * x)) ** 2)) * np.minimum(1.0, np.maximum(t - 0.45, 0) / 0.3) * 0.7
    return room(fade_out(clicks + water, 0.4), 0.6, 0.2)


def arena_collapse():
    """Boden stürzt ab: tiefes Krachen, Grollen, prasselnde Trümmer."""
    seconds = 2.0
    t = timeline(seconds)
    boom = thump(t, 0.0, 90.0, 32.0, 3.0) * 1.2
    rumble = lowpass(noise(seconds, 731), 180) * np.exp(-t * 1.5) * 1.6
    out = np.zeros(len(t))
    gen = np.random.default_rng(732)
    for _ in range(40):
        at = gen.uniform(0.1, 1.5)
        out += bandpass(noise(seconds, int(gen.integers(1 << 20))), gen.uniform(400, 1800), 400) * env_exp(t, at, gen.uniform(40, 90)) * gen.uniform(0.1, 0.4)
    return room(fade_out(distort(boom + rumble, 1.2) + out, 0.4), 1.0, 0.25)


def arena_crumble():
    """Stein bröckelt: ein paar trockene Brocken rieseln."""
    seconds = 0.8
    t = timeline(seconds)
    out = np.zeros(len(t))
    gen = np.random.default_rng(741)
    for i in range(12):
        at = gen.uniform(0.0, 0.55)
        out += bandpass(noise(seconds, 742 + i), gen.uniform(900, 2600), 700) * env_exp(t, at, gen.uniform(60, 120)) * gen.uniform(0.2, 0.6)
    return room(fade_out(out + lowpass(noise(seconds, 760), 250) * np.exp(-t * 6.0) * 0.4, 0.15), 0.4, 0.15)


def arena_gate():
    """Fallgitter: Metall schleift kurz, dann ein schwerer Schlag mit Nachschwingen."""
    seconds = 1.4
    t = timeline(seconds)
    grind = bandpass(noise(seconds, 751), 1400, 900) * np.minimum(1.0, t / 0.05) * (t < 0.3) * 0.4
    clang = mono(anvil(310.0, seconds, 752)) * env_exp(t, 0.3, 3.5) * (t >= 0.3)
    shifted = np.zeros(len(t))
    hit = thump(t, 0.3, 120.0, 45.0, 8.0)
    shifted += clang * 0.8 + hit
    return room(fade_out(grind + shifted, 0.3), 0.8, 0.2)


def arena_sub_break():
    """Teilziel bricht: Stein und Bronze zerspringen, helle Splitter fallen."""
    seconds = 1.2
    t = timeline(seconds)
    snap = highpass(noise(seconds, 761), 1500) * env_exp(t, 0.0, 35.0) * 0.9
    body = thump(t, 0.0, 180.0, 60.0, 10.0) * 0.8
    shards = sum(g * np.sin(2.0 * np.pi * f * t) * env_exp(t, at, 18.0) for f, at, g in ((2400.0, 0.05, 0.15), (3100.0, 0.12, 0.12), (1900.0, 0.2, 0.1), (2750.0, 0.31, 0.08)))
    return room(fade_out(snap + body + shards + mono(arena_crumble_tail()), 0.25), 0.5, 0.18)


def arena_crumble_tail():
    x = arena_crumble()
    out = np.zeros(samples(1.2))
    n = min(len(x), len(out) - samples(0.1))
    out[samples(0.1):samples(0.1) + n] = x[:n] * 0.5
    return out


def arena_sub_hit():
    """Treffer am Teilziel: kurzer Schlag auf Stein, ein metallischer Kern klingt mit."""
    seconds = 0.6
    t = timeline(seconds)
    knock = lowpass(noise(seconds, 771), 1200) * env_exp(t, 0.0, 55.0) + thump(t, 0.0, 200.0, 90.0, 20.0) * 0.6
    ring = sum(g * np.sin(2.0 * np.pi * f * t) * np.exp(-t * d) for f, g, d in ((880.0, 0.18, 9.0), (1320.0, 0.1, 12.0)))
    return room(fade_out(knock + ring, 0.1), 0.3, 0.12)


def arena_stuck():
    """Waffe steckt: dumpfes Einschlagen in Stein, dann singt die Klinge nach."""
    seconds = 1.2
    t = timeline(seconds)
    thunk = thump(t, 0.0, 160.0, 55.0, 14.0) + lowpass(noise(seconds, 781), 700) * env_exp(t, 0.0, 40.0) * 0.7
    sing = sum(g * np.sin(2.0 * np.pi * f * t * (1.0 + 0.004 * np.sin(t * 37.0))) * np.exp(-t * d) for f, g, d in ((523.0, 0.2, 2.8), (1046.0, 0.1, 4.0), (1567.0, 0.05, 5.0)))
    return room(fade_out(thunk + sing, 0.3), 0.5, 0.15)


def arena_fetter():
    """Fessel schnappt: ein harter Metallklick, dann das Klirren des Rings."""
    seconds = 0.8
    t = timeline(seconds)
    snap = bandpass(noise(seconds, 791), 3000, 1500) * env_exp(t, 0.0, 140.0) + bandpass(noise(seconds, 792), 1800, 800) * env_exp(t, 0.06, 90.0) * 0.8
    ring = sum(g * np.sin(2.0 * np.pi * f * t) * env_exp(t, 0.06, d) for f, g, d in ((1180.0, 0.18, 10.0), (1790.0, 0.12, 13.0), (2630.0, 0.07, 16.0)))
    weight = thump(t, 0.06, 160.0, 90.0, 25.0) * 0.4
    return room(fade_out(snap + ring + weight, 0.15), 0.4, 0.15)


def arena_cover():
    """Deckung: ein leiser, weicher Doppelton, kaum mehr als ein Hinweis."""
    seconds = 0.6
    t = timeline(seconds)
    tone = sum(g * np.sin(2.0 * np.pi * f * t) * env_exp(t, at, 9.0) for f, at, g in ((784.0, 0.0, 0.5), (1175.0, 0.07, 0.35)))
    return fade_out(tone * np.minimum(1.0, t / 0.01), 0.15)


# ID → (Funktion, Ordner, Datei, Spitzenpegel, Untertitel de, Untertitel en)
SOUNDS = {
    'ambient.approach_wind': (approach_wind, 'ambient', 'approach_wind', 0.8, 'Wind auf dem Weg', 'Wind on the path'),
    'ambient.approach_howl': (approach_howl, 'ambient', 'approach_howl', 0.8, 'Fernes Heulen', 'Distant howl'),
    'ambient.approach_anvil': (approach_anvil, 'ambient', 'approach_anvil', 0.8, 'Ferner Amboss', 'Distant anvil'),
    'ambient.approach_drums': (approach_drums, 'ambient', 'approach_drums', 0.85, 'Kriegstrommel', 'War drum'),
    'ambient.approach_thunder': (approach_thunder, 'ambient', 'approach_thunder', 0.85, 'Fernes Donnergrollen', 'Distant thunder'),
    'ambient.approach_heart': (approach_heart, 'ambient', 'approach_heart', 0.9, 'Herzschlag', 'Heartbeat'),
    'ambient.approach_ticking': (approach_ticking, 'ambient', 'approach_ticking', 0.8, 'Uhrwerk tickt', 'Clockwork ticks'),
    'ambient.approach_chains': (approach_chains, 'ambient', 'approach_chains', 0.8, 'Rasselnde Ketten', 'Chains rattle'),
    'ambient.approach_dread': (approach_dread, 'ambient', 'approach_dread', 0.8, 'Dumpfer Atem', 'Hollow breath'),
    'arena.bell': (arena_bell, 'arena', 'bell', 0.9, 'Glocke schlägt', 'Bell tolls'),
    'arena.rod': (arena_rod, 'arena', 'rod', 0.9, 'Erdungsstab entlädt sich', 'Grounding rod discharges'),
    'arena.post': (arena_post, 'arena', 'post', 0.9, 'Kettenpfahl stürzt', 'Chain post falls'),
    'arena.sluice': (arena_sluice, 'arena', 'sluice', 0.85, 'Schleuse öffnet', 'Sluice turns'),
    'arena.collapse': (arena_collapse, 'arena', 'collapse', 0.95, 'Boden stürzt ab', 'Floor collapses'),
    'arena.crumble': (arena_crumble, 'arena', 'crumble', 0.8, 'Stein bröckelt', 'Stone crumbles'),
    'arena.gate': (arena_gate, 'arena', 'gate', 0.9, 'Gitter fällt', 'Portcullis drops'),
    'arena.sub_break': (arena_sub_break, 'arena', 'sub_break', 0.9, 'Ziel bricht', 'Target breaks'),
    'arena.sub_hit': (arena_sub_hit, 'arena', 'sub_hit', 0.8, 'Treffer am Ziel', 'Target hit'),
    'arena.stuck': (arena_stuck, 'arena', 'stuck', 0.85, 'Waffe steckt fest', 'Weapon stuck'),
    'arena.fetter': (arena_fetter, 'arena', 'fetter', 0.85, 'Fessel schnappt zu', 'Shackle snaps'),
    'arena.cover': (arena_cover, 'arena', 'cover', 0.5, 'Deckung wechselt', 'Cover changes'),
}


AMBIENT_MAX = 1.4


def limit(x, seconds, fade=0.35):
    """Kürzt auf höchstens `seconds` (Anmarsch-Pulse 0,6–1,4 s) und blendet den Hall weich aus."""
    n = min(len(x), samples(seconds))
    out = x[:n].copy()
    f = min(samples(fade), n)
    out[-f:] *= np.linspace(1.0, 0.0, f) ** 2
    return out


def write_snippets():
    """sounds.json-Einträge (ersetzen die Platzhalter von P00) und Untertitel de/en."""
    os.makedirs(SNIPPETS, exist_ok=True)
    sounds = {sid: {'subtitle': f'subtitles.hha.{sid}', 'sounds': [f'hha:{folder}/{file}']} for sid, (_m, folder, file, *_r) in SOUNDS.items()}
    with open(os.path.join(SNIPPETS, 'sounds.json'), 'w', encoding='utf8') as f:
        json.dump(sounds, f, indent=2, ensure_ascii=False)
        f.write('\n')
    for index, name in ((4, 'lang_de.json'), (5, 'lang_en.json')):
        with open(os.path.join(SNIPPETS, name), 'w', encoding='utf8') as f:
            json.dump({f'subtitles.hha.{sid}': v[index] for sid, v in SOUNDS.items()}, f, indent=2, ensure_ascii=False)
            f.write('\n')


def main():
    args = sys.argv[1:]
    names, spec = [], None
    i = 0
    while i < len(args):
        if args[i] == '--only':
            names += [n for n in args[i + 1].split(',') if n]
            i += 2
        elif args[i] == '--spectrogram':
            spec = args[i + 1]
            i += 2
        else:
            names.append(args[i])
            i += 1
    if not names:
        names = list(SOUNDS)
    rows = []
    for sid in names:
        make, folder, file, peak, *_ = SOUNDS[sid]
        data = mono(make())
        if folder == 'ambient':
            data = limit(data, AMBIENT_MAX)
        data = data - float(np.mean(data))
        ramp = samples(0.003)
        data[:ramp] *= np.linspace(0.0, 1.0, ramp)
        scaled = data / max(float(np.max(np.abs(data))), 1e-6) * peak
        path = os.path.join(ASSETS, 'sounds', folder, f'{file}.ogg')
        encode(path, scaled, quality=4)
        c = check(scaled)
        rows.append((sid, c['seconds'], 20.0 * math.log10(c['peak']), c['rms'], os.path.getsize(path)))
        if spec:
            spectrogram_png(os.path.join(spec, f'{sid}.png'), scaled, 800, 240)
    write_snippets()
    print('| ID | Länge (s) | Spitze (dBFS) | RMS | Datei (kB) |')
    print('| --- | --- | --- | --- | --- |')
    for sid, seconds, peak_db, rms, size in rows:
        print(f'| `{sid}` | {seconds:.2f} | {peak_db:.1f} | {rms:.3f} | {size / 1024:.0f} |')


if __name__ == '__main__':
    main()
