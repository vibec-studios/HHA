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
    """Kreisförmiger Puffer: was über das Ende hinausragt, läuft am Anfang weiter. So schließt jede Schleife nahtlos."""

    def __init__(self, seconds):
        self.n = samples(seconds)
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

    def render(self, wet_gain=1.0, peak=0.89, target_rms=0.125):
        # Kreisfaltung: auch der Nachhall läuft über die Schleifengrenze hinweg.
        room = impulse()
        spectrum_l = np.fft.rfft(self.wet[:, 0]) * np.fft.rfft(room[:, 0], self.n)
        spectrum_r = np.fft.rfft(self.wet[:, 1]) * np.fft.rfft(room[:, 1], self.n)
        wet = np.stack([np.fft.irfft(spectrum_l, self.n), np.fft.irfft(spectrum_r, self.n)], axis=1)
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
