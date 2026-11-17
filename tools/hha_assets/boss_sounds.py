"""Synthetisiert die Boss- und RPG-Klangeffekte. Die Musik liegt in boss_music.py.

Aufruf: python boss_sounds.py [--only boss.thud,rpg.rest] [--spectrogram ordner] [--snippets]   (numpy nötig, siehe
synth.py). Schreibt seit Überarbeitung 3 nicht mehr sounds.json, sondern Schnipsel nach tmp/overhaul3/snippets/P04/.
Positionsklänge bleiben mono, sonst kann Minecraft sie nicht im Raum platzieren.
"""
import json
import os
import sys

import numpy as np

from synth import (
    RATE, Mix, adsr, anvil, bandpass, bell, brass, check, choir, cymbal, distort, encode, harp, highpass, hz, lowpass, noise,
    phase_of, samples, spectral, spectrogram_png, sweep, taiko, timeline,
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


def chain():
    """Schwere Ketten: viele kurze, unharmonische Metallschläge, die sich zu einem Rasseln verdichten."""
    seconds = 1.5
    out = np.zeros(samples(seconds))
    gen = np.random.default_rng(52)
    for _ in range(46):
        length = samples(gen.uniform(0.04, 0.13))
        start = int(gen.uniform(0.0, 0.9) ** 1.6 * len(out))
        n = np.arange(length) / RATE
        f = gen.uniform(900, 3400)
        piece = sum(np.sin(2.0 * np.pi * f * r * n) * g for r, g in ((1.0, 1.0), (1.51, 0.6), (2.37, 0.4))) * np.exp(-n * gen.uniform(35, 90))
        end = min(start + length, len(out))
        out[start:end] += piece[:end - start] * gen.uniform(0.25, 0.8)
    return room(out, 0.7, 0.2)


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
    'portal_hum': portal_hum, 'portal_enter': portal_enter, 'chain': chain,
}
THEMES = ('messenger', 'thief', 'guide', 'finale', 'shade', 'hades')



# =================================================================================================================
# Überarbeitung 3 (P04): eigene Dateien für die acht Verweis-Töne, neue Boss-Töne und die RPG-Töne.
# Alle mono und kurz; der Raumanteil bleibt klein, damit zwei Töne je Tick nicht verschwimmen.
# =================================================================================================================


def env_ar(n, attack, release_rate):
    t = np.arange(n) / RATE
    return np.minimum(1.0, t / max(attack, 1e-4)) * np.exp(-t * release_rate)


def clinks(seconds, count, low, high, seed, decay=(40, 90)):
    """Viele kleine Metallklänge (Ketten, Schlüssel, Splitter)."""
    out = np.zeros(samples(seconds))
    gen = np.random.default_rng(seed)
    for _ in range(count):
        length = samples(gen.uniform(0.04, 0.12))
        start = int(gen.uniform(0.0, 0.85) * len(out))
        m = np.arange(length) / RATE
        f = gen.uniform(low, high)
        piece = sum(np.sin(2.0 * np.pi * f * r * m) * g for r, g in ((1.0, 1.0), (1.51, 0.55), (2.37, 0.3))) * np.exp(-m * gen.uniform(*decay))
        end = min(start + length, len(out))
        out[start:end] += piece[:end - start] * gen.uniform(0.25, 0.8)
    return out


def thunder_call():
    """Zeus ruft den Blitz: Luft lädt sich auf, Knistern verdichtet sich, ein trockener Peitschenknall, kurzes Grollen."""
    seconds = 2.0
    t = timeline(seconds)
    k = t / seconds
    hit = 1.15
    rise = sweep(noise(seconds, 201), lambda f, x: np.exp(-(((f - (300 + 4200 * x ** 2)) / (400 + 1400 * x)) ** 2))) * np.minimum(1.0, k / 0.55) ** 2 * (t < hit)
    gen = np.random.default_rng(202)
    crackle = lowpass(bandpass(noise(seconds, 203), 3000, 1800) * (gen.uniform(0, 1, len(t)) ** 6 > 0.25 - 0.2 * k), 5500) * np.minimum(1.0, k / 0.5) * (t < hit) * 0.5
    after = np.maximum(t - hit, 0)
    crack = highpass(noise(seconds, 204), 1200) * np.exp(-after * 45.0) * (t >= hit) * 1.4
    rumble = lowpass(noise(seconds, 205), 220) * np.exp(-after * 2.6) * (t >= hit) * 2.4
    return room(fade_out(distort(rise * 0.9 + crackle + crack + rumble, 1.5)), 0.8, 0.18, 3.0)


def anvil_hit():
    """Hammer auf Amboss: harter Anschlag, singende Stabmoden, ein dumpfer Körper darunter."""
    ring = anvil(820.0, 1.3, 211)[:, 0]
    t = timeline(1.3)
    body = tone_sweep(160, 70, 1.3) * np.exp(-t * 20.0) * 0.8
    return room(fade_out(ring * 1.4 + body), 0.4, 0.12)


def forge():
    """Esse: der Blasebalg drückt Luft ins Feuer, es faucht auf und knistert."""
    seconds = 1.6
    t = timeline(seconds)
    k = t / seconds
    puff = sweep(noise(seconds, 221), lambda f, x: 1.0 / (1.0 + (f / (300 + 1400 * np.sin(np.pi * x))) ** 2)) * np.sin(np.pi * k) ** 0.8 * 1.6
    roar = lowpass(noise(seconds, 222), 160) * np.sin(np.pi * k) * 1.8
    gen = np.random.default_rng(223)
    pops = np.zeros(len(t))
    for _ in range(22):
        at = int(gen.uniform(0.15, 0.95) * len(t))
        length = samples(0.012)
        pops[at:at + length] += highpass(gen.uniform(-1, 1, length), 1800)[:max(0, len(t) - at)] * gen.uniform(0.2, 0.6)
    return room(fade_out(distort(puff + roar + pops, 1.3)), 0.5, 0.14)


def command():
    """Kratos befiehlt: ein kurzer, rauer Ruf mit Formanten, dazu ein Blechstoß."""
    seconds = 0.75
    t = timeline(seconds)
    pitch = 128.0 - 34.0 * (t / seconds) + 4.0 * np.sin(t * 40.0)
    phase = phase_of(pitch)
    glottal = sum(np.sin(2.0 * np.pi * h * phase) / h ** 0.8 for h in range(1, 40))
    voice = sweep(glottal, lambda f, x: (np.exp(-(((f - 700.0) / 160.0) ** 2)) + 0.8 * np.exp(-(((f - 1150.0) / 200.0) ** 2))
                                         + 0.35 * np.exp(-(((f - 2600.0) / 360.0) ** 2))))
    env = np.minimum(1.0, t / 0.025) * np.minimum(1.0, (seconds - t) / 0.25) ** 1.4
    stab = brass(hz('A2'), 0.45, 1.0)[:len(t), 0]
    stab = np.pad(stab, (0, len(t) - len(stab)))
    return room(distort(voice * env * 1.2 + stab * 0.5, 1.8), 0.5, 0.14)


def shield():
    """Schild hebt sich und fängt: dumpfer Plattenschlag, kurzer Metallklang."""
    t = timeline(0.7)
    thunk = tone_sweep(220, 85, 0.7) * np.exp(-t * 14.0) * 1.1
    plate = sum(g * np.exp(-t * d) * np.sin(2.0 * np.pi * f * t) for f, g, d in ((410.0, 0.6, 9.0), (655.0, 0.45, 12.0), (1180.0, 0.3, 16.0), (2010.0, 0.18, 22.0)))
    scrape = bandpass(noise(0.7, 231), 2400, 900) * np.exp(-t * 30.0) * 0.5
    return room(fade_out(distort(thunk + plate + scrape, 1.3)), 0.4, 0.12)


def torch():
    """Fackel flammt auf: ein Luftstoß, der zu Feuer wird, dann leises Knistern."""
    seconds = 0.9
    t = timeline(seconds)
    k = t / seconds
    whoomp = sweep(noise(seconds, 241), lambda f, x: np.exp(-(((f - (250 + 1600 * x ** 0.5)) / 700.0) ** 2))) * np.minimum(1.0, k / 0.12) * np.exp(-t * 3.5) * 1.6
    gen = np.random.default_rng(242)
    crackle = highpass(noise(seconds, 243), 2500) * (gen.uniform(0, 1, len(t)) ** 10 > 0.3) * k * 0.4
    return room(fade_out(whoomp + crackle), 0.4, 0.12)


def howl():
    """Hekates Hunde heulen: eine Stimme gleitet hoch und sinkt, Formanten wandern von U nach O."""
    seconds = 1.8
    t = timeline(seconds)
    k = t / seconds
    pitch = 330.0 + 260.0 * np.sin(np.pi * np.minimum(1.0, k * 1.15)) ** 1.5 + 5.0 * np.sin(t * 33.0)
    phase = phase_of(pitch)
    raw = sum(np.sin(2.0 * np.pi * h * phase) / h ** 1.2 for h in range(1, 18))
    formed = sweep(raw, lambda f, x: np.exp(-(((f - (380 + 320 * x)) / 180.0) ** 2)) + 0.4 * np.exp(-(((f - (900 + 300 * x)) / 260.0) ** 2)))
    breath = bandpass(noise(seconds, 251), 1800, 900) * 0.12
    env = np.minimum(1.0, t / 0.12) * np.minimum(1.0, (seconds - t) / 0.6) ** 1.3
    return room((formed + breath) * env, 0.9, 0.22, 2.6)


def key():
    """Schlüssel dreht sich: zwei Klicks im Schloss, dann ein heller, kurzer Schimmer."""
    t = timeline(1.3)
    clicks = sum(bandpass(noise(1.3, 261 + i), 3200, 1400) * np.exp(-np.maximum(t - at, 0) * 140.0) * (t >= at) * g
                 for i, (at, g) in enumerate(((0.0, 1.0), (0.16, 0.8))))
    shimmer = sum(g * np.exp(-np.maximum(t - 0.2, 0) * d) * np.sin(2.0 * np.pi * f * t) * (t >= 0.2)
                  for f, g, d in ((1568.0, 0.4, 3.5), (2349.0, 0.3, 4.5), (3136.0, 0.2, 6.0)))
    return room(fade_out(clicks + shimmer), 0.5, 0.18)


def crack():
    """Boden bricht: trockene Splitterknalle hintereinander über einem kurzen Grollen."""
    seconds = 0.9
    t = timeline(seconds)
    out = np.zeros(len(t))
    for i, (at, g) in enumerate(((0.0, 1.0), (0.07, 0.7), (0.15, 0.85), (0.26, 0.5), (0.4, 0.35))):
        m = np.maximum(t - at, 0)
        out += bandpass(noise(seconds, 271 + i), 1800 + 500 * i, 1300) * np.exp(-m * 70.0) * (t >= at) * g
    rumble = lowpass(noise(seconds, 279), 180) * np.exp(-t * 5.0) * 1.6
    return room(fade_out(distort(out * 1.4 + rumble, 1.4)), 0.4, 0.12)


def debris():
    """Trümmer: Steine prasseln und rollen aus, gedämpft."""
    seconds = 1.2
    out = np.zeros(samples(seconds))
    gen = np.random.default_rng(281)
    for _ in range(30):
        length = samples(gen.uniform(0.02, 0.07))
        start = int(gen.uniform(0.0, 0.8) ** 1.5 * len(out))
        m = np.arange(length) / RATE
        tone = np.sin(2.0 * np.pi * gen.uniform(300, 1100) * m) * 0.5 + gen.uniform(-1, 1, length)
        piece = lowpass(tone, 2600) * np.exp(-m * gen.uniform(50, 110))
        end = min(start + length, len(out))
        out[start:end] += piece[:end - start] * gen.uniform(0.2, 0.7) * (1.0 - start / len(out)) ** 0.5
    t = timeline(seconds)
    body = lowpass(noise(seconds, 282), 300) * np.exp(-t * 6.0) * 0.9
    return room(fade_out(out + body), 0.4, 0.12)


def thud():
    """Tiefer Schlag zum Kamera-Ruck: Körper unter 80 Hz, kurz, nur ein Hauch Anschlag."""
    seconds = 0.5
    t = timeline(seconds)
    body = np.sin(2.0 * np.pi * phase_of(38.0 + 34.0 * np.exp(-t * 18.0))) * np.exp(-t * 9.0)
    knock = lowpass(noise(seconds, 291), 400) * np.exp(-t * 90.0) * 0.35
    x = distort(body * 1.1 + knock, 1.2)
    return fade_out(lowpass(x, 240), 0.06)


def lens():
    """Die Luft verzerrt sich: gläserner Ton, der sich biegt, mit schwebendem Schimmer."""
    seconds = 1.5
    t = timeline(seconds)
    k = t / seconds
    bend = 1.0 - 0.18 * np.sin(np.pi * k)
    tone = sum(g * np.sin(2.0 * np.pi * phase_of(f * bend * (1.0 + 0.004 * np.sin(t * 7.0 + f)))) for f, g in ((520.0, 0.5), (523.5, 0.5), (1040.0, 0.3), (1567.0, 0.18)))
    air = bandpass(noise(seconds, 301), 3200, 1500) * 0.1
    env = np.sin(np.pi * k) ** 0.7
    return room((tone * 0.6 + air) * env, 0.7, 0.2, 2.4)


def time_stop():
    """Kronos hält die Zeit an: ein Ticken, ein rückwärts anschwellender Sog, ein Schlag, dann klingt nur Glas nach."""
    seconds = 1.8
    t = timeline(seconds)
    stop = 0.7
    ticks_ = sum(bandpass(noise(seconds, 311 + i), 2600 if i % 2 == 0 else 1900, 700) * np.exp(-np.maximum(t - at, 0) * 160.0) * (t >= at) * 0.7
                 for i, at in enumerate((0.0, 0.18, 0.36, 0.52)))
    suck = sweep(noise(seconds, 315), lambda f, x: np.exp(-(((f - (4000 - 3500 * x)) / 900.0) ** 2))) * np.minimum(1.0, t / stop) ** 3 * (t < stop) * 1.2
    m = np.maximum(t - stop, 0)
    hit = np.sin(2.0 * np.pi * phase_of(55.0 + 60.0 * np.exp(-m * 25.0))) * np.exp(-m * 8.0) * (t >= stop) * 1.0
    glass = sum(g * np.exp(-m * d) * np.sin(2.0 * np.pi * f * m) * (t >= stop) for f, g, d in ((1318.0, 0.35, 2.4), (1975.0, 0.25, 3.0), (2793.0, 0.15, 4.0)))
    return room(fade_out(ticks_ + suck + hit + glass), 0.6, 0.16)


def tear():
    """Ein Riss geht durch den Raum: reißendes Rauschen, das nach oben fährt, und knisternde Ränder."""
    seconds = 1.2
    t = timeline(seconds)
    k = t / seconds
    rip = sweep(noise(seconds, 321), lambda f, x: np.exp(-(((f - (600 + 4200 * x)) / (500 + 1200 * x)) ** 2))) * 1.6
    gen = np.random.default_rng(322)
    grain = (gen.uniform(0, 1, len(t)) ** 4) * np.sign(np.sin(t * 300.0 + np.sin(t * 41.0) * 5.0))
    edge = highpass(grain, 1500) * 0.25
    env = np.minimum(1.0, k / 0.08) * np.minimum(1.0, (1.0 - k) / 0.35)
    return room(distort((rip + edge) * env, 1.4), 0.5, 0.14)


def swirl():
    """Ein Wirbel: ein Rauschband kreist (Filter und Pegel im Wechsel), darunter ein tiefer Sog."""
    seconds = 1.6
    t = timeline(seconds)
    k = t / seconds
    spin = 3.0 + 7.0 * k
    band = sweep(noise(seconds, 331), lambda f, x: np.exp(-(((f - (900 + 700 * np.sin(2.0 * np.pi * (3.0 * x + 3.5 * x * x)))) / 600.0) ** 2))) * 1.5
    pulse = 0.6 + 0.4 * np.sin(2.0 * np.pi * phase_of(spin))
    sub = np.sin(2.0 * np.pi * phase_of(70.0 + 30.0 * k)) * 0.35
    env = np.sin(np.pi * k) ** 0.6
    return room((band * pulse + sub) * env, 0.5, 0.16)


def wave():
    """Druckwelle rollt vorbei: tiefer Stoß, dann rauschende Luft, die vorbeizieht."""
    seconds = 1.4
    t = timeline(seconds)
    k = t / seconds
    boom = tone_sweep(90, 34, seconds, 2.5) * np.exp(-t * 5.0) * 1.2
    air = sweep(noise(seconds, 341), lambda f, x: 1.0 / (1.0 + (f / (4500 * (1.0 - x) ** 1.5 + 250)) ** 2)) * np.sin(np.pi * np.minimum(1.0, k * 1.4)) * 1.3
    return room(fade_out(distort(boom + air, 1.4)), 0.6, 0.14)


# ---- RPG -------------------------------------------------------------------------------------------------------


def tunnel_loop():
    """Das Rauschen des Tunnels als nahtlose Schleife: ganze Schwingungszahlen je Durchlauf, Luft kreisförmig gefiltert."""
    seconds = 8.0
    n = samples(seconds)
    t = np.arange(n) / RATE
    hum = sum(g * np.sin(2.0 * np.pi * c * t / seconds) for c, g in ((587, 0.5), (590, 0.45), (881, 0.25), (1175, 0.15), (293, 0.35)))
    breathe = 0.7 + 0.3 * np.sin(2.0 * np.pi * 2 * t / seconds)
    air = spectral(np.random.default_rng(351).uniform(-1, 1, n), lambda f: np.exp(-(((f - 900.0) / 700.0) ** 2)))
    air = air / np.max(np.abs(air)) * (0.55 + 0.45 * np.sin(2.0 * np.pi * 3 * t / seconds + 1.0))
    return hum * breathe * 0.4 + air * 0.32


def depart():
    """Abfahrt: ein Ton steigt und wird schneller, Luft reißt mit, alles zieht nach vorn."""
    seconds = 1.4
    t = timeline(seconds)
    k = t / seconds
    rise = np.sin(2.0 * np.pi * phase_of(180.0 + 900.0 * k ** 2.2)) * k * 0.5 + np.sin(2.0 * np.pi * phase_of(270.0 + 1350.0 * k ** 2.2)) * k * 0.3
    rush = sweep(noise(seconds, 361), lambda f, x: np.exp(-(((f - (500 + 5000 * x ** 2)) / (500 + 1500 * x)) ** 2))) * (0.3 + k) * 1.1
    env = np.minimum(1.0, (seconds - t) / 0.12)
    return room((rise + rush) * env, 0.6, 0.16)


def arrive():
    """Ankunft: die Luft legt sich, ein heller Akkord klingt an."""
    seconds = 1.6
    t = timeline(seconds)
    settle = sweep(noise(seconds, 371), lambda f, x: np.exp(-(((f - (3800 - 3200 * x)) / 1100.0) ** 2))) * np.exp(-t * 3.5) * 0.9
    chord = sum(g * np.exp(-t * d) * np.sin(2.0 * np.pi * f * t) for f, g, d in ((587.3, 0.4, 2.0), (740.0, 0.3, 2.4), (880.0, 0.3, 2.6), (1174.7, 0.18, 3.2)))
    return room(fade_out(settle + chord * np.minimum(1.0, t / 0.01)), 0.8, 0.22)


def chapter():
    """Titelkarte eines Kapitels: tiefe Glocke und ein leiser Chorakkord darüber."""
    seconds = 3.0
    gong = bell(hz('D3'), seconds)[:, 0]
    voices = sum(choir(hz(n), 2.2, 'o')[:, 0] for n in ('D4', 'A4', 'F#5'))
    voices = np.pad(voices, (0, max(0, len(gong) - len(voices))))[:len(gong)]
    return room(fade_out(gong * 1.2 + voices * 0.12, 0.4), 1.0, 0.22, 2.4)


def objective():
    """Neues Ziel: zwei kurze, helle Töne aufwärts (Quarte)."""
    t = timeline(1.0)
    out = np.zeros(len(t))
    for at, f in ((0.0, 880.0), (0.12, 1174.7)):
        m = np.maximum(t - at, 0)
        out += (np.sin(2.0 * np.pi * f * m) + 0.3 * np.sin(2.0 * np.pi * f * 2.0 * m)) * np.exp(-m * 5.0) * np.minimum(1.0, m / 0.004) * (t >= at) * 0.5
    return room(fade_out(out), 0.5, 0.18)


def journal():
    """Tagebuch: Papier raschelt, eine Seite schlägt um."""
    seconds = 0.55
    t = timeline(seconds)
    k = t / seconds
    rustle = bandpass(noise(seconds, 381), 3500, 2200) * (0.5 + 0.5 * np.abs(np.sin(t * 60.0 + np.sin(t * 23.0) * 3.0)))
    flip = bandpass(noise(seconds, 382), 1400, 900) * np.exp(-np.maximum(t - 0.3, 0) * 30.0) * (t >= 0.3) * 1.2
    env = np.sin(np.pi * np.minimum(1.0, k * 1.1)) ** 0.8
    return fade_out((rustle * 0.6 * env + flip), 0.04)


def rest():
    """Rast: eine warme Harfe gleitet einen Dur-Akkord hinauf, ein Schimmer bleibt."""
    out = np.zeros(samples(2.2))
    for i, name in enumerate(('D4', 'F#4', 'A4', 'D5', 'F#5')):
        tone = harp(hz(name), 2.0)[:, 0]
        at = samples(i * 0.07)
        end = min(at + len(tone), len(out))
        out[at:end] += tone[:end - at] * 0.5
    t = timeline(2.2)
    shimmer = highpass(noise(2.2, 391), 6000) * np.exp(-t * 2.0) * 0.04
    return room(fade_out(out + shimmer, 0.3), 0.9, 0.2)


def fallen():
    """Gefallen: eine gedämpfte tiefe Glocke, darunter sinkt ein Ton ab."""
    seconds = 2.8
    t = timeline(seconds)
    toll = lowpass(bell(hz('D2'), seconds)[:, 0], 1800) * 1.4
    sink = np.sin(2.0 * np.pi * phase_of(220.0 * 2 ** (-t / seconds * 1.2))) * np.exp(-t * 1.2) * np.minimum(1.0, t / 0.2) * 0.35
    return room(fade_out(toll + sink, 0.4), 1.2, 0.2, 2.0)


def dissolve():
    """Auflösen: feine Körner zerstieben und steigen, bis nichts bleibt."""
    seconds = 1.6
    out = np.zeros(samples(seconds))
    gen = np.random.default_rng(401)
    for i in range(70):
        length = samples(gen.uniform(0.02, 0.05))
        start = int((i / 70.0) ** 0.8 * 0.92 * len(out))
        m = np.arange(length) / RATE
        f = 1500 + 5000 * (start / len(out)) + gen.uniform(-400, 400)
        piece = np.sin(2.0 * np.pi * f * m) * np.sin(np.pi * m / (length / RATE)) * gen.uniform(0.2, 0.5)
        end = min(start + length, len(out))
        out[start:end] += piece[:end - start] * (1.0 - start / len(out)) ** 0.7
    t = timeline(seconds)
    air = highpass(noise(seconds, 402), 3000) * np.exp(-t * 2.0) * 0.12
    return room(fade_out(out + air), 0.5, 0.16)


# Neue Töne: ID → (Funktion, Unterordner, Dateiname). Die acht Verweis-Töne behalten ihre IDs, bekommen aber Dateien.
NEW_SOUNDS = {
    'boss.thunder_call': (thunder_call, 'boss', 'thunder_call'), 'boss.anvil': (anvil_hit, 'boss', 'anvil'),
    'boss.forge': (forge, 'boss', 'forge'), 'boss.command': (command, 'boss', 'command'),
    'boss.shield': (shield, 'boss', 'shield'), 'boss.torch': (torch, 'boss', 'torch'), 'boss.howl': (howl, 'boss', 'howl'),
    'boss.key': (key, 'boss', 'key'),
    'boss.crack': (crack, 'boss', 'crack'), 'boss.debris': (debris, 'boss', 'debris'), 'boss.thud': (thud, 'boss', 'thud'),
    'boss.lens': (lens, 'boss', 'lens'), 'boss.time_stop': (time_stop, 'boss', 'time_stop'), 'boss.tear': (tear, 'boss', 'tear'),
    'boss.swirl': (swirl, 'boss', 'swirl'), 'boss.wave': (wave, 'boss', 'wave'),
    'rpg.tunnel_loop': (tunnel_loop, 'rpg', 'tunnel_loop'), 'rpg.depart': (depart, 'rpg', 'depart'),
    'rpg.arrive': (arrive, 'rpg', 'arrive'), 'rpg.chapter': (chapter, 'rpg', 'chapter'),
    'rpg.objective': (objective, 'rpg', 'objective'), 'rpg.journal': (journal, 'rpg', 'journal'),
    'rpg.rest': (rest, 'rpg', 'rest'), 'rpg.fallen': (fallen, 'rpg', 'fallen'), 'rpg.dissolve': (dissolve, 'rpg', 'dissolve'),
}

# Untertitel der wirklich neuen IDs (die acht Verweis-Töne haben schon Untertitel in lang/*.json).
SUBTITLES = {
    'boss.crack': ('Boden bricht', 'Ground cracks'),
    'boss.debris': ('Trümmer prasseln', 'Debris clatters'),
    'boss.thud': ('Schwerer Schlag', 'Heavy thud'),
    'boss.lens': ('Luft verzerrt sich', 'Air warps'),
    'boss.time_stop': ('Zeit steht still', 'Time stops'),
    'boss.tear': ('Riss im Raum', 'Space tears'),
    'boss.swirl': ('Wirbel kreist', 'Vortex swirls'),
    'boss.wave': ('Druckwelle rollt', 'Shockwave rolls'),
    'rpg.tunnel_loop': ('Tunnel rauscht', 'Tunnel hums'),
    'rpg.depart': ('Reise beginnt', 'Journey begins'),
    'rpg.arrive': ('Ankunft', 'Arrival'),
    'rpg.chapter': ('Neues Kapitel', 'New chapter'),
    'rpg.objective': ('Neues Ziel', 'New objective'),
    'rpg.journal': ('Tagebuch blättert', 'Journal pages turn'),
    'rpg.rest': ('Rast', 'Resting'),
    'rpg.fallen': ('Gefallen', 'Fallen'),
    'rpg.dissolve': ('Gestalt löst sich auf', 'Form dissolves'),
}

SNIPPETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'tmp', 'overhaul3', 'snippets', 'P04')


def write_snippets():
    """Schnipsel für das Integrations-Paket: sounds.json-Einträge (Musik und Töne) und Untertitel."""
    from boss_music import THEMES
    sounds = {}
    for name in ('olympus', 'hekate', 'brontes', 'kratos', 'zeus'):
        assert name in THEMES
    for name in THEMES:
        sounds[f'music.{name}'] = {'sounds': [{'name': f'hha:music/{name}', 'stream': True}]}
    for sid, (_make, folder, file) in NEW_SOUNDS.items():
        sounds[sid] = {'subtitle': f'subtitles.hha.{sid}', 'sounds': [f'hha:{folder}/{file}']}
    os.makedirs(SNIPPETS, exist_ok=True)
    with open(os.path.join(SNIPPETS, 'sounds.json'), 'w', encoding='utf8') as f:
        json.dump(sounds, f, indent=2, ensure_ascii=False)
        f.write('\n')
    for index, lang in ((0, 'lang_de.json'), (1, 'lang_en.json')):
        with open(os.path.join(SNIPPETS, lang), 'w', encoding='utf8') as f:
            json.dump({f'subtitles.hha.{sid}': text[index] for sid, text in SUBTITLES.items()}, f, indent=2, ensure_ascii=False)
            f.write('\n')


def main():
    """python boss_sounds.py [--only id,id] [--spectrogram ordner] [--snippets]

    Ohne --only: alle Töne aus NEW_SOUNDS plus die Schnipsel. Bestandstöne (EFFECTS) nur, wenn man sie mit --only
    ausdrücklich nennt (z. B. --only boss.whoosh). sounds.json selbst wird nicht mehr geschrieben (Integrations-Paket).
    """
    args = sys.argv[1:]
    names, spec, snippets_only = [], None, False
    i = 0
    while i < len(args):
        if args[i] == '--only':
            names += [n for n in args[i + 1].split(',') if n]
            i += 2
        elif args[i] == '--spectrogram':
            spec = args[i + 1]
            i += 2
        elif args[i] == '--snippets':
            snippets_only = True
            i += 1
        else:
            names.append(args[i])
            i += 1
    if not names and not snippets_only:
        names = list(NEW_SOUNDS)
    for sid in names:
        if sid in NEW_SOUNDS:
            make, folder, file = NEW_SOUNDS[sid]
        else:
            legacy = sid[len('boss.'):] if sid.startswith('boss.') else sid
            make, folder, file = EFFECTS[legacy], 'boss', legacy
        data = make()
        path = os.path.join(ASSETS, 'sounds', folder, f'{file}.ogg')
        encode(path, data, quality=4, peak=0.9)
        scaled = data / max(float(np.max(np.abs(data))), 1e-6) * 0.9
        c = check(scaled)
        print(f'{sid}: {c["seconds"]:.2f} s, RMS {c["rms"]:.3f}, {os.path.getsize(path) / 1024:.0f} kB')
        if spec:
            spectrogram_png(os.path.join(spec, f'{sid}.png'), scaled, 800, 240)
    write_snippets()


if __name__ == '__main__':
    main()
