"""Kleiner Software-Synthesizer für die Boss-Klänge und -Musik (numpy, ffmpeg kodiert zu OGG).

numpy ist nur für diese Generatoren nötig, nicht für den Mod: pip install numpy --target <ordner>, dann PYTHONPATH setzen.
Alles rechnet in 44,1 kHz. Die Instrumente sind additiv aufgebaut, deshalb gibt es kein Aliasing und jede Note
lässt sich zwischenspeichern; stereo entsteht durch mehrere leicht verstimmte Stimmen im Panorama.
"""
import math
import os
import shutil
import subprocess
import tempfile
import wave

import numpy as np

RATE = 44100
LIMIT_HZ = 17000.0
_rng = np.random.default_rng(1234)


# --- Grundlagen -----------------------------------------------------------------------------------------------


def hz(name):
    """Tonname wie D3, F#4 oder Bb2 in Hertz."""
    letters = {'C': -9, 'D': -7, 'E': -5, 'F': -4, 'G': -2, 'A': 0, 'B': 2}
    semis = letters[name[0]]
    rest = name[1:]
    if rest[0] == '#':
        semis += 1
        rest = rest[1:]
    elif rest[0] == 'b':
        semis -= 1
        rest = rest[1:]
    return 440.0 * 2 ** ((semis + (int(rest) - 4) * 12) / 12.0)


def samples(seconds):
    return max(1, int(seconds * RATE))


def timeline(seconds):
    return np.arange(samples(seconds)) / RATE


def noise(seconds, seed=None):
    gen = np.random.default_rng(seed) if seed is not None else _rng
    return gen.uniform(-1.0, 1.0, samples(seconds))


def adsr(n, attack, decay, sustain, release):
    """Hüllkurve über n Samples; alle Zeiten in Sekunden."""
    env = np.full(n, float(sustain))
    a, d, r = int(attack * RATE), int(decay * RATE), int(release * RATE)
    a = min(a, n)
    if a > 0:
        env[:a] = np.linspace(0.0, 1.0, a, endpoint=False)
    d = min(d, n - a)
    if d > 0:
        env[a:a + d] = np.linspace(1.0, sustain, d, endpoint=False)
    r = min(r, n)
    if r > 0:
        env[n - r:] *= np.linspace(1.0, 0.0, r) ** 1.5
    return env


def spectral(x, gain):
    """Statischer Filter im Frequenzraum; gain(frequenzen) liefert den Verstärkungsfaktor."""
    spectrum = np.fft.rfft(x)
    return np.fft.irfft(spectrum * gain(np.fft.rfftfreq(len(x), 1.0 / RATE)), len(x))


def lowpass(x, cutoff):
    return spectral(x, lambda f: 1.0 / (1.0 + (f / cutoff) ** 4))


def highpass(x, cutoff):
    return spectral(x, lambda f: 1.0 - 1.0 / (1.0 + (f / max(cutoff, 1.0)) ** 4))


def bandpass(x, center, width):
    return spectral(x, lambda f: np.exp(-(((f - center) / width) ** 2)))


def sweep(x, gain, frame=2048, hop=512):
    """Zeitlich veränderlicher Filter (Overlap-Add): gain(frequenzen, t in 0..1)."""
    window = np.hanning(frame)
    freqs = np.fft.rfftfreq(frame, 1.0 / RATE)
    padded = np.concatenate([np.zeros(frame), x, np.zeros(frame)])
    out = np.zeros(len(padded))
    norm = np.zeros(len(padded))
    for start in range(0, len(padded) - frame, hop):
        t = min(1.0, max(0.0, (start - frame) / max(len(x), 1)))
        chunk = np.fft.rfft(padded[start:start + frame] * window) * gain(freqs, t)
        out[start:start + frame] += np.fft.irfft(chunk, frame) * window
        norm[start:start + frame] += window * window
    return (out / np.maximum(norm, 1e-6))[frame:frame + len(x)]


def phase_of(freq):
    """Phase (in Schwingungen) aus einem Frequenzverlauf pro Sample."""
    return np.cumsum(freq) / RATE


def additive(phase, amps, top_hz):
    """Summiert Harmonische. amps(k) liefert Skalar oder Verlauf; alles über top_hz wird weggelassen."""
    out = np.zeros(len(phase))
    for k in range(1, 64):
        if k * top_hz > LIMIT_HZ:
            break
        a = amps(k)
        if np.isscalar(a) and a < 0.004:
            continue
        out += a * np.sin(2.0 * np.pi * k * phase)
    return out


def distort(x, drive):
    return np.tanh(x * drive) / np.tanh(drive)


def stereo(mono, pan=0.0):
    angle = (pan + 1.0) * math.pi / 4.0
    return np.stack([mono * math.cos(angle), mono * math.sin(angle)], axis=1)


def _voices(freq, n, spread, count, build, vibrato=(5.0, 0.0), rate_seed=7):
    """Baut ein Ensemble aus leicht verstimmten Stimmen; build(phase, voice) liefert die Stimme."""
    t = np.arange(n) / RATE
    gen = np.random.default_rng(int(freq * 13) + rate_seed)
    out = np.zeros((n, 2))
    for v in range(count):
        cents = spread * (v / max(count - 1, 1) * 2.0 - 1.0) if count > 1 else 0.0
        rate = vibrato[0] * gen.uniform(0.85, 1.15)
        wobble = 1.0 + vibrato[1] * np.sin(2.0 * np.pi * rate * t + gen.uniform(0, 6.28)) * np.minimum(1.0, t * 2.5)
        f = freq * 2 ** (cents / 1200.0) * wobble
        phase = phase_of(f) + gen.uniform(0, 1)
        pan = (v / max(count - 1, 1) * 2.0 - 1.0) * 0.7 if count > 1 else 0.0
        out += stereo(build(phase, v), pan)
    return out / math.sqrt(count)


# --- Instrumente -----------------------------------------------------------------------------------------------

_cache = {}


def _cached(key, make):
    if key not in _cache:
        _cache[key] = make()
    return _cache[key]


def strings(freq, seconds, bright=1.0):
    """Lang gehaltene Streicher: Sägezahn-Spektrum, weich einsetzend, mit Vibrato."""
    def make():
        n = samples(seconds + 0.5)
        env = adsr(n, 0.14, 0.2, 0.9, 0.5)
        roll = 0.72 + 0.12 * bright

        def build(phase, _v):
            return additive(phase, lambda k: roll ** k / k ** 0.6, freq)
        return _voices(freq, n, 11.0, 5, build, (5.2, 0.0035)) * env[:, None] * 0.34
    return _cached(('strings', round(freq, 2), round(seconds, 3), bright), make)


def staccato(freq, seconds=0.2):
    """Kurz gestrichen: treibt die Ostinati."""
    def make():
        n = samples(seconds + 0.12)
        env = adsr(n, 0.006, seconds * 0.6, 0.4, 0.1)

        def build(phase, _v):
            return additive(phase, lambda k: 0.82 ** k / k ** 0.5, freq)
        return _voices(freq, n, 9.0, 3, build) * env[:, None] * 0.5
    return _cached(('staccato', round(freq, 2), round(seconds, 3)), make)


def pizz(freq, seconds=0.5):
    def make():
        n = samples(seconds)
        t = np.arange(n) / RATE

        def build(phase, _v):
            return additive(phase, lambda k: np.exp(-t * (5.0 + k * 3.5)) / k ** 0.9, freq)
        return _voices(freq, n, 6.0, 2, build) * 0.9
    return _cached(('pizz', round(freq, 2), round(seconds, 3)), make)


def brass(freq, seconds, power=1.0):
    """Blech: der Klang öffnet sich beim Einsatz und wird im Forte hell und rau."""
    def make():
        n = samples(seconds + 0.25)
        t = np.arange(n) / RATE
        env = adsr(n, 0.035, 0.12, 0.84, 0.18)
        opening = np.minimum(1.0, t / 0.11) * (0.84 + 0.16 * np.exp(-t * 3.0))
        shape = (1.5 - 0.72 * power) * (1.25 - opening)

        def build(phase, _v):
            return additive(phase, lambda k: np.exp(-(k - 1) * shape * 0.75) / k ** 0.75, freq)
        return distort(_voices(freq, n, 7.0, 3, build, (5.4, 0.0022)) * env[:, None] * 0.55, 1.5) * 0.65
    return _cached(('brass', round(freq, 2), round(seconds, 3), power), make)


def horn(freq, seconds):
    """Weiche Hörner für die Mittellage."""
    def make():
        n = samples(seconds + 0.3)
        env = adsr(n, 0.07, 0.1, 0.9, 0.26)

        def build(phase, _v):
            return additive(phase, lambda k: 0.55 ** k / k ** 0.4, freq)
        return _voices(freq, n, 6.0, 3, build, (4.8, 0.002)) * env[:, None] * 0.55
    return _cached(('horn', round(freq, 2), round(seconds, 3)), make)


def choir(freq, seconds, vowel='a'):
    """Chor: harmonisches Spektrum durch Formanten eines Vokals geformt."""
    formants = {'a': ((750, 110, 1.0), (1150, 120, 0.55), (2650, 170, 0.28)),
                'o': ((480, 90, 1.0), (850, 110, 0.4), (2500, 180, 0.12)),
                'u': ((340, 80, 1.0), (760, 100, 0.22), (2400, 180, 0.06))}[vowel]

    def make():
        n = samples(seconds + 0.7)
        env = adsr(n, 0.35, 0.3, 0.92, 0.7)

        def weight(k):
            h = k * freq
            return sum(g * math.exp(-((h - c) / w) ** 2) for c, w, g in formants) + 0.008

        def build(phase, _v):
            return additive(phase, weight, freq)
        return _voices(freq, n, 13.0, 6, build, (5.6, 0.0065)) * env[:, None] * 0.75
    return _cached(('choir', round(freq, 2), round(seconds, 3), vowel), make)


def reed(freq, seconds):
    """Klarinette: fast nur ungerade Teiltöne, dazu ein Hauch Atem."""
    def make():
        n = samples(seconds + 0.1)
        env = adsr(n, 0.03, 0.06, 0.8, 0.08)

        def build(phase, _v):
            return additive(phase, lambda k: (1.0 / k if k % 2 else 0.05 / k) * 0.9 ** k, freq)
        tone = _voices(freq, n, 3.0, 2, build, (5.0, 0.0028)) * env[:, None] * 0.6
        breath = stereo(bandpass(noise(n / RATE, 5), min(freq * 3, 6000), 900) * env * 0.05)
        return tone + breath
    return _cached(('reed', round(freq, 2), round(seconds, 3)), make)


def harp(freq, seconds=1.4):
    def make():
        n = samples(seconds)
        t = np.arange(n) / RATE

        def build(phase, _v):
            return additive(phase, lambda k: np.exp(-t * (1.6 + k * 1.1)) / k ** 1.2, freq)
        attack = np.minimum(1.0, t / 0.002)
        return _voices(freq, n, 2.0, 2, build) * attack[:, None] * 0.8
    return _cached(('harp', round(freq, 2), round(seconds, 3)), make)


def bell(freq, seconds=4.0):
    """Glocke mit unharmonischen Teiltönen: der Klang, der nach Unterwelt und Feierlichkeit klingt."""
    def make():
        n = samples(seconds)
        t = np.arange(n) / RATE
        out = np.zeros(n)
        for ratio, gain, decay in ((0.5, 0.7, 0.8), (1.0, 1.0, 1.1), (1.19, 0.55, 1.5), (1.5, 0.5, 1.8), (2.0, 0.55, 2.2), (2.76, 0.32, 3.4), (5.4, 0.2, 6.0), (8.9, 0.1, 9.0)):
            if freq * ratio < LIMIT_HZ:
                out += gain * np.exp(-t * decay) * np.sin(2.0 * np.pi * freq * ratio * t)
        return stereo(out * np.minimum(1.0, t / 0.002) * 0.5)
    return _cached(('bell', round(freq, 2), seconds), make)


def taiko(pitch=62.0, seconds=1.1, seed=11):
    """Große Trommel: Fellschlag, Druck im Bass und ein kurzer Raum."""
    def make():
        n = samples(seconds)
        t = np.arange(n) / RATE
        f = pitch * (1.0 + 1.3 * np.exp(-t * 26.0))
        body = np.sin(2.0 * np.pi * phase_of(f)) * np.exp(-t * 4.5)
        over = 0.35 * np.sin(2.0 * np.pi * phase_of(f * 1.59)) * np.exp(-t * 11.0)
        slap = lowpass(noise(seconds, seed), 2200) * np.exp(-t * 55.0) * 0.8
        return stereo(distort(body + over + slap, 1.3) * 0.9)
    return _cached(('taiko', pitch, seconds, seed), make)


def tom(pitch=140.0):
    return taiko(pitch, 0.5, 13)


def rim():
    def make():
        t = timeline(0.09)
        return stereo((bandpass(noise(0.09, 17), 1900, 700) + 0.6 * np.sin(2 * np.pi * 820 * t)) * np.exp(-t * 62.0) * 0.8)
    return _cached('rim', make)


def shaker(seconds=0.07, seed=19):
    def make():
        t = timeline(seconds)
        return stereo(highpass(noise(seconds, seed), 6500) * np.minimum(1.0, t / 0.008) * np.exp(-t * 62.0) * 0.6)
    return _cached(('shaker', seconds, seed), make)


def cymbal(seconds=3.2):
    """Beckenschlag: dichtes, helles Rauschen mit langem Ausklang."""
    def make():
        t = timeline(seconds)
        left = highpass(noise(seconds, 23), 3200) * np.exp(-t * 1.7) * (0.75 + 0.25 * np.exp(-t * 16.0))
        right = highpass(noise(seconds, 29), 3200) * np.exp(-t * 1.6) * (0.75 + 0.25 * np.exp(-t * 16.0))
        return np.stack([left, right], axis=1) * 0.55
    return _cached(('cymbal', seconds), make)


def swell(seconds=2.0):
    """Rückwärts anschwellendes Becken vor einem großen Einsatz."""
    def make():
        return cymbal(seconds + 0.2)[::-1][:samples(seconds)] * 0.85
    return _cached(('swell', seconds), make)


def flute(freq, seconds):
    """Flöte: fast reiner Grundton, wenig Obertöne, hörbarer Atem und weiches Vibrato."""
    def make():
        n = samples(seconds + 0.15)
        env = adsr(n, 0.07, 0.1, 0.85, 0.14)
        weights = (1.0, 0.22, 0.1, 0.045, 0.02)

        def build(phase, _v):
            return additive(phase, lambda k: weights[k - 1] if k <= len(weights) else 0.0, freq)
        tone = _voices(freq, n, 4.0, 2, build, (5.2, 0.0045)) * env[:, None] * 0.62
        breath = stereo(bandpass(np.random.default_rng(61).uniform(-1.0, 1.0, n), min(freq * 2.2, 7000), 1400) * env * 0.045)
        return tone + breath
    return _cached(('flute', round(freq, 2), round(seconds, 3)), make)


def anvil(freq=780.0, seconds=1.8, seed=71):
    """Amboss: unharmonische Stabmoden in schwebenden Paaren und ein harter Anschlag."""
    def make():
        n = samples(seconds)
        t = np.arange(n) / RATE
        out = np.zeros(n)
        for ratio, gain, decay in ((1.0, 1.0, 2.6), (2.76, 0.6, 4.0), (5.40, 0.34, 6.5), (8.93, 0.16, 10.0)):
            f = freq * ratio
            if f * 1.004 >= LIMIT_HZ:
                continue
            out += gain * np.exp(-t * decay) * (np.sin(2.0 * np.pi * f * t) + 0.7 * np.sin(2.0 * np.pi * f * 1.004 * t + 1.0))
        click = highpass(noise(seconds, seed), 2500) * np.exp(-t * 260.0) * 1.6
        thump = np.sin(2.0 * np.pi * phase_of(140.0 * (1.0 + np.exp(-t * 40.0)))) * np.exp(-t * 18.0) * 0.8
        return stereo((out * 0.32 + click + thump) * np.minimum(1.0, t / 0.0008) * 0.55)
    return _cached(('anvil', freq, seconds, seed), make)


def snare(seconds=0.32, seed=73):
    """Kleine Trommel für den Marsch: Fell, Teppich und ein kurzer Ton."""
    def make():
        t = timeline(seconds)
        wires = bandpass(noise(seconds, seed), 4200, 2600) * np.exp(-t * 17.0)
        head = np.sin(2.0 * np.pi * phase_of(185.0 * (1.0 + 0.4 * np.exp(-t * 60.0)))) * np.exp(-t * 26.0)
        return stereo(distort(wires * 0.9 + head * 0.7, 1.4) * np.minimum(1.0, t / 0.001) * 0.62)
    return _cached(('snare', seconds, seed), make)


def woodblock(freq=1250.0, seconds=0.16):
    """Holzblock: das Ticken des Uhrwerks."""
    def make():
        t = timeline(seconds)
        x = np.sin(2.0 * np.pi * freq * t) * np.exp(-t * 48.0) + 0.45 * np.sin(2.0 * np.pi * freq * 2.71 * t) * np.exp(-t * 80.0)
        x += bandpass(noise(seconds, 77), freq * 1.6, 900) * np.exp(-t * 150.0) * 0.6
        return stereo(x * np.minimum(1.0, t / 0.0005) * 0.6)
    return _cached(('woodblock', freq, seconds), make)


def timpani(pitch=73.0, seconds=2.2, seed=79):
    """Pauke: gestimmter Kessel mit langem Nachklang, weicher als die Taiko."""
    def make():
        n = samples(seconds)
        t = np.arange(n) / RATE
        f = pitch * (1.0 + 0.08 * np.exp(-t * 18.0))
        body = sum(g * np.sin(2.0 * np.pi * phase_of(f * r)) * np.exp(-t * d) for r, g, d in ((1.0, 1.0, 1.9), (1.5, 0.45, 3.0), (1.98, 0.3, 4.0), (2.44, 0.15, 6.0)))
        mallet = lowpass(noise(seconds, seed), 1500) * np.exp(-t * 70.0) * 0.5
        return stereo(distort(body + mallet, 1.2) * np.minimum(1.0, t / 0.001) * 0.75)
    return _cached(('timpani', round(pitch, 2), seconds, seed), make)


def drone(freq, seconds, cutoff=600.0, voices=4, seed=83):
    """Dunkle Fläche: Sägezahn-Ensemble hinter einem Tiefpass, sehr langsam ein- und ausblendend."""
    def make():
        n = samples(seconds)
        env = adsr(n, min(2.5, seconds * 0.3), 0.0, 1.0, min(2.5, seconds * 0.3))

        def build(phase, _v):
            return additive(phase, lambda k: 1.0 / k, freq)
        x = _voices(freq, n, 9.0, voices, build, (0.18, 0.004), seed)
        x = np.stack([lowpass(x[:, 0], cutoff), lowpass(x[:, 1], cutoff)], axis=1)
        return x * env[:, None] * 0.4
    return _cached(('drone', round(freq, 2), round(seconds, 3), cutoff, voices, seed), make)


def wind(seconds, low=200.0, high=1400.0, seed=89):
    """Ferner Wind: ein Rauschband, das langsam auf und ab wandert (links und rechts verschieden)."""
    def make():
        chans = []
        for c in range(2):
            phase0 = c * 1.7 + seed

            def gain(f, k, phase0=phase0):
                centre = low + (high - low) * (0.5 + 0.5 * math.sin(2.0 * math.pi * k * 2.0 + phase0))
                return np.exp(-(((f - centre) / (0.35 * high)) ** 2))
            x = sweep(noise(seconds, seed + c), gain)
            k = np.linspace(0.0, 1.0, len(x))
            chans.append(x * (0.6 + 0.4 * np.sin(2.0 * np.pi * k * 3.0 + phase0)))
        out = np.stack(chans, axis=1)
        return out / max(float(np.max(np.abs(out))), 1e-6) * 0.5
    return _cached(('wind', round(seconds, 3), low, high, seed), make)


def chains(seconds=1.6, seed=97, density=26):
    """Ferne Ketten: kleine unharmonische Metallklänge, gedämpft, wie aus einem anderen Saal."""
    def make():
        out = np.zeros(samples(seconds))
        gen = np.random.default_rng(seed)
        for _ in range(density):
            length = samples(gen.uniform(0.05, 0.14))
            start = int(gen.uniform(0.0, 0.88) ** 1.3 * len(out))
            m = np.arange(length) / RATE
            f = gen.uniform(900, 2600)
            piece = sum(np.sin(2.0 * np.pi * f * r * m) * g for r, g in ((1.0, 1.0), (1.51, 0.55), (2.37, 0.35))) * np.exp(-m * gen.uniform(30, 70))
            end = min(start + length, len(out))
            out[start:end] += piece[:end - start] * gen.uniform(0.2, 0.7)
        out = lowpass(out, 2600)
        return stereo(out * 0.5, float(gen.uniform(-0.6, 0.6)))
    return _cached(('chains', seconds, seed, density), make)


def reverse(sound):
    """Rückwärts gespielt (für das rückwärts geschichtete Uhrwerk)."""
    return sound[::-1].copy()


# --- Mischpult -------------------------------------------------------------------------------------------------


def impulse(seconds=2.9, seed=41):
    """Stereo-Raum eines großen Saals: frühe Reflexionen, darunter ein Nachhall, dessen Höhen schneller sterben."""
    n = samples(seconds)
    t = np.arange(n) / RATE
    out = np.zeros((n, 2))
    gen = np.random.default_rng(seed)
    for channel in range(2):
        bright = highpass(gen.uniform(-1, 1, n), 1800) * np.exp(-t * 4.2)
        mid = gen.uniform(-1, 1, n) * np.exp(-t * 2.5)
        dark = lowpass(gen.uniform(-1, 1, n), 700) * np.exp(-t * 1.6) * 1.4
        tail = (bright * 0.55 + lowpass(mid, 3800) * 0.7 + dark) * np.minimum(1.0, t / 0.025)
        for _ in range(9):
            at = gen.integers(int(0.008 * RATE), int(0.07 * RATE))
            tail[at] += gen.uniform(-1.4, 1.4)
        out[:, channel] = tail
    return out / np.sqrt(np.sum(out * out, axis=0))


class Mix:
    """Kreisförmiger Puffer: was über das Ende hinausragt, läuft am Anfang weiter. So schließt jede Schleife nahtlos.

    Mit loop=False (Stinger, Abspann) wird abgeschnitten statt gefaltet, der Hall läuft linear und am Ende blendet
    das Stück in `tail` Sekunden aus.
    """

    def __init__(self, seconds, loop=True, tail=0.6):
        self.n = samples(seconds)
        self.loop = loop
        self.tail = tail
        self.dry = np.zeros((self.n, 2))
        self.wet = np.zeros((self.n, 2))

    def add(self, seconds, sound, gain=1.0, send=0.2, pan=0.0):
        start = int(seconds * RATE) % self.n
        data = sound * gain
        if pan:
            angle = (pan + 1.0) * math.pi / 4.0
            data = data * np.array([math.cos(angle) * 1.414, math.sin(angle) * 1.414])
        for buffer, amount in ((self.dry, 1.0), (self.wet, send)):
            if amount <= 0:
                continue
            at = start
            remaining = data
            while len(remaining):
                room = self.n - at
                piece = remaining[:room]
                buffer[at:at + len(piece)] += piece * amount
                remaining = remaining[room:]
                at = 0
                if not self.loop:
                    break

    def render(self, wet_gain=1.0, peak=0.89, target_rms=0.125):
        # Kreisfaltung: auch der Nachhall läuft über die Schleifengrenze hinweg.
        room = impulse()
        size = self.n if self.loop else self.n + len(room)
        spectrum_l = np.fft.rfft(self.wet[:, 0], size) * np.fft.rfft(room[:, 0], size)
        spectrum_r = np.fft.rfft(self.wet[:, 1], size) * np.fft.rfft(room[:, 1], size)
        wet = np.stack([np.fft.irfft(spectrum_l, size)[:self.n], np.fft.irfft(spectrum_r, size)[:self.n]], axis=1)
        out = self.dry + wet * wet_gain
        out -= np.mean(out, axis=0)
        # Tiefen zurücknehmen und Präsenz anheben, sonst deckt der Bass die Melodie auf kleinen Boxen und Kopfhörern zu.
        def tilt(f):
            low = 0.6 + 0.4 * np.minimum(1.0, np.maximum(0.0, (f - 90.0) / 260.0))
            high = 1.0 + 0.6 * np.minimum(1.0, np.maximum(0.0, (f - 1600.0) / 3400.0))
            return low * high
        out = np.stack([spectral(out[:, 0], tilt), spectral(out[:, 1], tilt)], axis=1)
        # Sanft begrenzen, damit Taiko und Blech gemeinsam nicht clippen.
        out = np.tanh(out / np.max(np.abs(out)) * 1.5) / np.tanh(1.5)
        # Auf gleiche Lautheit bringen: laut genug für einen Kampf, aber die Klangeffekte müssen durchkommen.
        if not self.loop:
            fade = min(samples(self.tail), self.n)
            out[-fade:] *= (np.linspace(1.0, 0.0, fade) ** 1.6)[:, None]
            out[:samples(0.004)] *= np.linspace(0.0, 1.0, samples(0.004))[:, None]
        rms = float(np.sqrt(np.mean(out * out)))
        return out * min(peak, target_rms / rms)


def encode(path, data, quality=4, peak=None):
    """Schreibt mono (1D) oder stereo (n, 2) als OGG Vorbis."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if peak is not None:
        data = data / max(float(np.max(np.abs(data))), 1e-6) * peak
    pcm = (np.clip(data, -1.0, 1.0) * 32767.0).astype(np.int16)
    channels = 1 if data.ndim == 1 else 2
    handle, tmp = tempfile.mkstemp(suffix='.wav')
    os.close(handle)
    with wave.open(tmp, 'wb') as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())
    subprocess.run([shutil.which('ffmpeg'), '-hide_banner', '-loglevel', 'error', '-y', '-i', tmp, '-c:a', 'libvorbis', '-q:a', str(quality), path], check=True)
    os.remove(tmp)


def encode_budget(path, data, max_bytes, qualities=(3, 2, 1, 0, -1)):
    """Kodiert wie `encode` und senkt die Vorbis-Qualität, bis die Datei höchstens max_bytes groß ist.

    Liefert (Qualität, Bytes). Passt auch die niedrigste Stufe nicht, bleibt sie stehen (der Aufrufer meldet es).
    """
    for q in qualities:
        encode(path, data, quality=q)
        size = os.path.getsize(path)
        if size <= max_bytes:
            return q, size
    return qualities[-1], os.path.getsize(path)


def check(data):
    """Kennzahlen zum Prüfen: Länge (s), Spitze, RMS, Anteil geclippter Samples, Sprung an der Schleifennaht."""
    peak = float(np.max(np.abs(data)))
    rms = float(np.sqrt(np.mean(data * data)))
    clipped = float(np.mean(np.abs(data) >= 0.999))
    # Naht: Sprung vom letzten zum ersten Sample im Verhältnis zum größten Sprung zwischen Nachbarn im ganzen Stück
    # (< 1: die Naht ist nicht größer als jeder Einsatz im Stück, etwa ein Trommelschlag auf der Eins).
    steps = np.abs(np.diff(data, axis=0))
    seam = float(np.max(np.abs(data[0] - data[-1]))) / (float(np.max(steps)) + 1e-9)
    return {'seconds': len(data) / RATE, 'peak': peak, 'rms': rms, 'clipped': clipped, 'seam': seam}


def spectrogram_png(path, data, width=1200, height=360):
    """Spektrogramm als PNG über ffmpeg (showspectrumpic), zum Ansehen ohne Abspielen."""
    pcm = (np.clip(data, -1.0, 1.0) * 32767.0).astype(np.int16)
    channels = 1 if data.ndim == 1 else 2
    handle, tmp = tempfile.mkstemp(suffix='.wav')
    os.close(handle)
    with wave.open(tmp, 'wb') as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())
    os.makedirs(os.path.dirname(path), exist_ok=True)
    subprocess.run([shutil.which('ffmpeg'), '-hide_banner', '-loglevel', 'error', '-y', '-i', tmp, '-lavfi',
                    f'showspectrumpic=s={width}x{height}:mode=combined:scale=log:legend=1', path], check=True)
    os.remove(tmp)
