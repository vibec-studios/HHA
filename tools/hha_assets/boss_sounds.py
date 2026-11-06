"""Synthetisiert die Boss-Klangeffekte und schreibt sounds.json. Die Musik liegt in boss_music.py.

Aufruf: python boss_sounds.py   (numpy nötig, siehe synth.py)
Positionsklänge bleiben mono, sonst kann Minecraft sie nicht im Raum platzieren.
"""
import json
import os

import numpy as np

from synth import (
    RATE, Mix, adsr, bandpass, bell, brass, cymbal, distort, encode, highpass, hz, lowpass, noise, phase_of, samples, spectral, sweep,
    taiko, timeline,
)
from texkit import ASSETS

OUT = os.path.join(ASSETS, 'sounds', 'boss')


def room(x, seconds=1.2, wet=0.25, decay=3.5, seed=3):
    """Kurzer Faltungshall für die Effekte, damit sie in der Arena stehen statt am Ohr kleben."""
    n = len(x) + samples(seconds)
    t = np.arange(samples(seconds)) / RATE
    tail = lowpass(np.random.default_rng(seed).uniform(-1, 1, len(t)), 4200) * np.exp(-t * decay)
    tail /= np.sqrt(np.sum(tail * tail))
    size = n + len(tail)
    wet_signal = np.fft.irfft(np.fft.rfft(x, size) * np.fft.rfft(tail, size), size)[:n]
    dry = np.zeros(n)
    dry[:len(x)] = x
    return dry + wet_signal * wet * 6.0


def fade_out(x, seconds=0.05):
    n = min(samples(seconds), len(x))
    x = x.copy()
    x[-n:] *= np.linspace(1.0, 0.0, n)
    return x


def tone_sweep(start, end, seconds, power=2.0):
    t = np.linspace(0.0, 1.0, samples(seconds))
    return np.sin(2.0 * np.pi * phase_of(end + (start - end) * (1.0 - t) ** power))


def layer(*parts):
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[:len(p)] += p
    return out


def whoosh():
    """Vorbeizischen: ein Band Rauschen fährt hoch und wieder runter, die Federn flattern darin."""
    x = sweep(noise(0.62, 2), lambda f, t: np.exp(-(((f - (350 + 3400 * np.sin(np.pi * t) ** 2)) / (500 + 1500 * t)) ** 2)))
    t = np.linspace(0.0, 1.0, len(x))
    flutter = 0.72 + 0.28 * np.sin(2.0 * np.pi * phase_of(np.full(len(x), 34.0)))
    return room(x * np.sin(np.pi * t) ** 1.4 * flutter, 0.5, 0.15)


def dash():
    """Überschallknall vorweg, dahinter der lange Sog."""
    t = timeline(0.9)
    crack = highpass(noise(0.9, 3), 1500) * np.exp(-t * 60.0) * 1.2
    wind = sweep(noise(0.9, 4), lambda f, k: 1.0 / (1.0 + (f / (6500 * (1.0 - k) ** 2 + 300)) ** 2)) * np.exp(-t * 3.4)
    sub = tone_sweep(150, 38, 0.9) * np.exp(-t * 6.0) * 0.9
    return room(distort(crack + wind * 1.3 + sub, 1.4), 0.9, 0.2)


def impact():
    """Einschlag: Druck im Bass, Krachen, Trümmer und Nachhall der Arena."""
    t = timeline(2.0)
    sub = tone_sweep(120, 28, 2.0, 3.0) * np.exp(-t * 2.8) * 1.3
    crack = lowpass(noise(2.0, 5), 3400) * np.exp(-t * 18.0) * 1.1
    debris = bandpass(noise(2.0, 6), 900, 700) * np.exp(-t * 4.0) * 0.32 * (0.6 + 0.4 * np.sign(np.sin(t * 90 + np.sin(t * 37) * 4)))
    ring = np.sin(2.0 * np.pi * 412.0 * t) * np.exp(-t * 7.0) * 0.14
    return room(distort(sub + crack + debris + ring, 1.5), 1.6, 0.26, 2.6)


def thunder():
    """Peitschender Riss, dann rollendes Grollen in mehreren Wellen."""
    seconds = 5.2
    t = timeline(seconds)
    gen = np.random.default_rng(7)
    rumble = lowpass(noise(seconds, 8), 240) * 2.2
    swells = sum(g * np.exp(-np.maximum(t - s, 0) * d) * (t >= s) for s, g, d in ((0.05, 1.0, 1.1), (0.75, 0.75, 1.3), (1.7, 0.6, 1.0), (3.0, 0.42, 1.1)))
    crackle = bandpass(noise(seconds, 9), 1700, 1400) * np.exp(-t * 7.0) * (gen.uniform(0, 1, len(t)) ** 8 > 0.05) * 0.8
    crack = highpass(noise(seconds, 10), 900) * np.exp(-t * 34.0) * 1.3
    return room(distort(rumble * swells + crackle + crack, 1.6), 1.6, 0.3, 2.0)


def rift():
    """Der Himmel reißt: unheimlich steigender Klangteppich, darüber berstendes Glas."""
    seconds = 3.4
    t = timeline(seconds)
    k = t / seconds
    drone = np.zeros(len(t))
    for ratio, gain in ((1.0, 1.0), (1.498, 0.55), (2.13, 0.42), (3.17, 0.24), (4.41, 0.14)):
        drone += gain * np.sin(2.0 * np.pi * phase_of(ratio * (52 + 46 * k ** 2)))
    tear = sweep(noise(seconds, 11), lambda f, x: np.exp(-(((f - (500 + 3800 * x ** 2)) / 900) ** 2))) * 1.5
    gen = np.random.default_rng(12)
    shards = np.zeros(len(t))
    for _ in range(46):
        start = int(gen.uniform(0.25, 0.98) * len(t))
        length = samples(gen.uniform(0.03, 0.16))
        piece = np.sin(2.0 * np.pi * gen.uniform(1800, 6800) * np.arange(length) / RATE) * np.exp(-np.arange(length) / RATE * gen.uniform(25, 80))
        shards[start:start + length] += piece[:max(0, len(t) - start)] * gen.uniform(0.04, 0.2)
    env = np.sin(np.pi * k) ** 0.6
    return room(distort((drone * 0.5 + tear) * env + shards, 1.4), 1.4, 0.3, 2.4)


def soul():
    """Flüsternder Seelenchor: Formanten über Rauschen, dazu ein gläserner, leicht schiefer Klang."""
    seconds = 2.8
    t = timeline(seconds)
    k = t / seconds
    voices = np.zeros(len(t))
    for i, (center, width, rate) in enumerate(((620, 110, 2.1), (1120, 150, 2.9), (2400, 240, 1.7), (3300, 300, 3.3))):
        band = bandpass(noise(seconds, 20 + i), center, width)
        voices += band / max(np.max(np.abs(band)), 1e-6) * (0.55 + 0.45 * np.sin(t * rate * 6.28 + i)) * (0.9 - i * 0.12)
    moan = sum(g * np.sin(2.0 * np.pi * phase_of(f + 6 * np.sin(t * 5.0 + f))) for f, g in ((174.0, 0.5), (233.0, 0.36), (349.0, 0.2), (466.0, 0.12)))
    return room((voices * 0.7 + moan * 0.8) * np.sin(np.pi * k) ** 0.8, 1.7, 0.42, 1.9)


def chime():
    """Göttliche Glocke mit Schimmer."""
    out = np.zeros(samples(3.2))
    for i, f in enumerate((880.0, 1174.66, 1760.0)):
        tone = bell(f, 3.0)[:, 0]
        out[int(i * 0.07 * RATE):int(i * 0.07 * RATE) + len(tone)] += tone[:len(out) - int(i * 0.07 * RATE)] * (1.0 - i * 0.2)
    return room(out, 1.5, 0.3, 2.2)


def sting():
    """Stage-Fanfare: Blech-Tutti in d-Moll, Pauke und Becken."""
    mix = Mix(4.0)
    for name, gain in (('D3', 0.5), ('A3', 0.42), ('D4', 0.42), ('F4', 0.38), ('A4', 0.34), ('D5', 0.28)):
        mix.add(0.03, brass(hz(name), 1.7, 1.0), gain, 0.3)
    mix.add(0.0, taiko(46.0, 1.8), 1.4, 0.3)
    mix.add(0.0, cymbal(), 0.55, 0.3)
    return np.mean(mix.render(), axis=1)


def voice():
    """Kurze Silbe für die Dialoge; die Tonhöhe setzt der Client."""
    t = timeline(0.09)
    glottal = sum(np.sin(2.0 * np.pi * phase_of(k * (280.0 - 500.0 * t))) / k for k in range(1, 16))
    formed = spectral(glottal, lambda f: np.exp(-(((f - 720.0) / 240.0) ** 2)) + 0.7 * np.exp(-(((f - 1400.0) / 340.0) ** 2)) + 0.3 * np.exp(-(((f - 2700.0) / 400.0) ** 2)))
    return formed * adsr(len(t), 0.006, 0.02, 0.8, 0.04)


def charge():
    """Energie sammelt sich: zwei steigende Töne und schneller werdendes Flirren."""
    seconds = 1.5
    t = timeline(seconds)
    k = t / seconds
    a = np.sin(2.0 * np.pi * phase_of(180.0 + 760.0 * k ** 2))
    b = np.sin(2.0 * np.pi * phase_of(271.0 + 1170.0 * k ** 2)) * 0.6
    fizz = highpass(noise(seconds, 30), 3200) * k ** 2 * 0.6
    pulse = 0.65 + 0.35 * np.sin(2.0 * np.pi * phase_of(5.0 + 34.0 * k ** 2))
    return room(fade_out((a + b) * pulse * k ** 1.3 + fizz, 0.04), 0.6, 0.25)


def blink():
    """Teleport: Luft wird angesaugt, ein Lichtton schnellt hoch, leise Implosion."""
    seconds = 0.55
    t = timeline(seconds)
    k = t / seconds
    rise = np.sin(2.0 * np.pi * phase_of(300.0 + 5200.0 * k ** 3)) * np.sin(np.pi * k) ** 1.5 * 0.55
    suck = sweep(noise(seconds, 31), lambda f, x: np.exp(-(((f - (5200.0 * (1.0 - x) + 500.0)) / 1100.0) ** 2))) * k * 1.2
    pop = np.sin(2.0 * np.pi * 110.0 * np.maximum(t - 0.43, 0)) * np.exp(-np.maximum(t - 0.43, 0) * 22.0) * (t >= 0.43) * 0.9
    return room(rise + suck + pop, 0.7, 0.28)


def deflect():
    """Klinge prallt ab: heller Metallklang, der kurz nachsingt."""
    t = timeline(0.9)
    tone = sum(g * np.exp(-t * d) * np.sin(2.0 * np.pi * f * t) for f, g, d in ((1720.0, 1.0, 6.0), (2460.0, 0.7, 8.0), (3910.0, 0.5, 11.0), (6130.0, 0.32, 16.0)))
    click = highpass(noise(0.9, 33), 2500) * np.exp(-t * 110.0) * 0.8
    return room(tone * 0.7 + click, 0.5, 0.2)


def hit():
    """Treffer auf Hermes: dumpfer Schlag auf Goldrüstung, dazu ein kurzer Klang."""
    t = timeline(0.6)
    thud = tone_sweep(190, 62, 0.6) * np.exp(-t * 15.0) * 1.1
    clank = sum(g * np.exp(-t * d) * np.sin(2.0 * np.pi * f * t) for f, g, d in ((840.0, 0.5, 13.0), (1370.0, 0.42, 16.0), (2230.0, 0.25, 22.0)))
    crunch = lowpass(noise(0.6, 35), 3600) * np.exp(-t * 44.0) * 0.8
    return room(distort(thud + clank + crunch, 1.4), 0.4, 0.14)


def feather_volley():
    """Federsalve: viele kleine Pfeiltöne und Flattern kurz nacheinander."""
    seconds = 1.1
    out = np.zeros(samples(seconds))
    gen = np.random.default_rng(36)
    for _ in range(13):
        length = samples(gen.uniform(0.14, 0.3))
        start = int(gen.uniform(0.0, 0.72) * len(out))
        k = np.linspace(0, 1, length)
        centre = gen.uniform(1700, 4200)
        whistle = np.sin(2.0 * np.pi * phase_of(centre * (1.1 - 0.42 * k))) * 0.28
        flap = bandpass(gen.uniform(-1, 1, length), centre * 0.7, 1200) * 1.4
        out[start:start + length] += (whistle + flap) * np.sin(np.pi * k) ** 1.4 * gen.uniform(0.5, 1.0)
    return room(out, 0.5, 0.16)


def spear():
    """Lanze aus Licht: fallender Pfeifton und ein tiefer Einschlag."""
    t = timeline(1.0)
    k = t / 1.0
    whistle = np.sin(2.0 * np.pi * phase_of(2900.0 - 2300.0 * k)) * np.exp(-t * 3.0) * 0.45
    air = sweep(noise(1.0, 37), lambda f, x: np.exp(-(((f - (3000.0 - 2300.0 * x)) / 800.0) ** 2))) * np.exp(-t * 3.0)
    thunk = tone_sweep(160, 44, 1.0) * np.exp(-np.maximum(t - 0.22, 0) * 8.0) * (t >= 0.22) * 1.2
    return room(whistle + air + thunk, 0.7, 0.24)


def roar():
    """Drachenbrüllen: raue Stimmbänder, ein wandernder Rachenraum und viel Atem."""
    seconds = 2.6
    t = timeline(seconds)
    k = t / seconds
    pitch = 62.0 + 40.0 * np.sin(np.pi * k ** 0.7) + 6.0 * np.sin(t * 37.0) + 3.0 * np.sin(t * 91.0)
    phase = phase_of(pitch)
    voice_raw = sum(np.sin(2.0 * np.pi * h * phase + h) / h ** 0.7 for h in range(1, 48))
    growl = 0.65 + 0.35 * np.sin(2.0 * np.pi * phase_of(26.0 + 16.0 * np.sin(t * 3.0)))
    throat = sweep(voice_raw * growl, lambda f, x: (
        np.exp(-(((f - (340.0 + 500.0 * np.sin(np.pi * x))) / 240.0) ** 2))
        + 0.75 * np.exp(-(((f - (1050.0 + 900.0 * np.sin(np.pi * x))) / 420.0) ** 2))
        + 0.3 * np.exp(-(((f - 2800.0) / 900.0) ** 2))
    ))
    breath = bandpass(noise(seconds, 38), 2300, 1800) * 0.55
    env = np.minimum(1.0, t / 0.12) * np.minimum(1.0, (seconds - t) / 0.9) ** 1.2
    sub = np.sin(2.0 * np.pi * phase_of(44.0 + 12.0 * np.sin(np.pi * k))) * 0.7
    return room(distort((throat * 1.2 + breath + sub) * env, 2.4), 1.2, 0.28, 2.8)


def hiss():
    """Drohendes Zischen vor dem Biss."""
    seconds = 1.0
    t = timeline(seconds)
    k = t / seconds
    x = sweep(noise(seconds, 39), lambda f, a: (1.0 - np.exp(-((f / 3400.0) ** 2))) * np.exp(-(((f - (6200.0 + 2100.0 * a)) / 3200.0) ** 2)))
    rattle = 0.72 + 0.28 * np.sign(np.sin(2.0 * np.pi * phase_of(30.0 + 18.0 * k)))
    return room(x * rattle * np.minimum(1.0, t / 0.08) * np.minimum(1.0, (seconds - t) / 0.2), 0.4, 0.14)


def bite():
    """Zuschnappen: zwei harte Klacke, Knirschen, ein tiefer Druck."""
    t = timeline(0.5)
    clack = bandpass(noise(0.5, 40), 2100, 1100) * (np.exp(-t * 95.0) + np.exp(-np.maximum(t - 0.06, 0) * 80.0) * (t >= 0.06)) * 1.3
    crunch = lowpass(noise(0.5, 41), 2800) * np.exp(-np.maximum(t - 0.05, 0) * 15.0) * (t >= 0.05) * 0.8
    thump = tone_sweep(180, 58, 0.5) * np.exp(-t * 12.0) * 0.9
    return room(distort(clack + crunch + thump, 1.5), 0.3, 0.14)


def flood():
    """Der Styx steigt: tiefes Wälzen, darin dunkles Gluckern."""
    seconds = 3.6
    t = timeline(seconds)
    k = t / seconds
    swell_low = lowpass(noise(seconds, 42), 300) * (0.5 + 0.5 * np.sin(t * 2.1) * np.sin(t * 0.9)) * 2.6
    wash = bandpass(noise(seconds, 43), 900, 750) * (0.45 + 0.55 * np.sin(t * 3.4 + 1.0) ** 2) * 0.55
    gen = np.random.default_rng(44)
    bubbles = np.zeros(len(t))
    for _ in range(30):
        length = samples(gen.uniform(0.06, 0.18))
        start = int(gen.uniform(0.0, 0.94) * len(t))
        n = np.arange(length) / RATE
        f = gen.uniform(180, 560)
        piece = np.sin(2.0 * np.pi * (f * n + 900.0 * n * n)) * np.exp(-n * 24.0)
        bubbles[start:start + length] += piece[:max(0, len(t) - start)] * gen.uniform(0.1, 0.34)
    return room((swell_low + wash + bubbles) * np.sin(np.pi * k) ** 0.5, 1.2, 0.3, 2.4)


def portal_hum():
    """Das Summen des Tors: tiefer Akkord mit langsamem Schweben. Kreisförmig erzeugt, schließt also nahtlos."""
    seconds = 6.0
    n = samples(seconds)
    t = np.arange(n) / RATE
    tone = np.zeros(n)
    # Nur ganzzahlige Schwingungen pro Schleife: Anfang und Ende passen exakt aneinander.
    for cycles, gain in ((330, 1.0), (332, 0.8), (495, 0.45), (660, 0.3), (992, 0.16), (1320, 0.09)):
        tone += gain * np.sin(2.0 * np.pi * cycles * t / seconds)
    beat = 0.72 + 0.28 * np.sin(2.0 * np.pi * 3 * t / seconds)
    air = spectral(noise(seconds, 50), lambda f: np.exp(-(((f - 700.0) / 420.0) ** 2)))
    air = air / np.max(np.abs(air)) * (0.5 + 0.5 * np.sin(2.0 * np.pi * 2 * t / seconds))
    return tone * beat * 0.75 + air * 0.28


def portal_enter():
    """Der Sog des Tors: Luft wird angezogen, ein Ton steigt und wird immer enger."""
    seconds = 1.8
    t = timeline(seconds)
    k = t / seconds
    rise = np.sin(2.0 * np.pi * phase_of(70.0 + 620.0 * k ** 2.4)) * k * 0.7
    second = np.sin(2.0 * np.pi * phase_of(105.0 + 930.0 * k ** 2.4)) * k * 0.4
    wind = sweep(noise(seconds, 51), lambda f, x: np.exp(-(((f - (400.0 + 3200.0 * x ** 2)) / (500.0 + 900.0 * x)) ** 2))) * (0.3 + 1.2 * k)
    sub = np.sin(2.0 * np.pi * 44.0 * t) * k ** 2 * 0.7
    return room(distort(rise + second + wind + sub, 1.4), 0.9, 0.22)


EFFECTS = {
    'whoosh': whoosh, 'dash': dash, 'impact': impact, 'thunder': thunder, 'rift': rift, 'soul': soul, 'chime': chime,
    'sting': sting, 'voice': voice, 'charge': charge, 'blink': blink, 'deflect': deflect, 'hit': hit,
    'feather_volley': feather_volley, 'spear': spear, 'roar': roar, 'hiss': hiss, 'bite': bite, 'flood': flood,
    'portal_hum': portal_hum, 'portal_enter': portal_enter,
}
THEMES = ('messenger', 'thief', 'guide', 'finale', 'shade')


def main():
    for name, make in EFFECTS.items():
        data = make()
        encode(os.path.join(OUT, f'{name}.ogg'), data, quality=4, peak=0.9)
        print('Klang', name, f'{len(data) / RATE:.2f} s')
    sounds = {}
    for name in EFFECTS:
        if name == 'voice':
            sounds['boss.voice'] = {'sounds': ['hha:boss/voice']}
        else:
            sounds[f'boss.{name}'] = {'subtitle': f'subtitles.hha.boss.{name}', 'sounds': [f'hha:boss/{name}']}
    for name in THEMES:
        sounds[f'music.{name}'] = {'sounds': [{'name': f'hha:music/{name}', 'stream': True}]}
    # Sprachzeilen verwaltet voice_lines.py; hier nur erhalten.
    path = os.path.join(ASSETS, 'sounds.json')
    if os.path.exists(path):
        with open(path, encoding='utf8') as f:
            sounds.update({k: v for k, v in json.load(f).items() if k.startswith('voice.')})
    with open(path, 'w', encoding='utf8') as f:
        json.dump(sounds, f, indent=2)


if __name__ == '__main__':
    main()
