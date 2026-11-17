"""Die Musik der Mod als orchestrale Schleifen. Aufruf: python boss_music.py [--only tunnel,zeus_2] [--spectrogram ordner]

Bestand: die vier Kampf-Themen von Hermes und Hades (nur auf ausdrücklichen Namen neu erzeugt). Überarbeitung 3: alle
Stücke aus THEMES weiter unten.


Alle vier teilen dasselbe Leitmotiv in D-Moll (aufsteigender Dreiklang, fallende Antwort): als Fanfare beim Boten,
chromatisch verzerrt beim Dieb, zeitgedehnt im Blech beim Seelenführer und als volles Tutti beim Götterboten.
Die Stücke laufen in einem kreisförmigen Puffer, Nachhall und Ausklänge schließen also nahtlos an den Anfang an.
"""
import os
import sys

import numpy as np

from synth import (
    Mix, anvil, bell, brass, chains, check, choir, cymbal, drone, encode, encode_budget, flute, harp, horn, hz, pizz, reed,
    reverse, rim, shaker, snare, spectrogram_png, staccato, strings, swell, taiko, timpani, tom, wind, woodblock,
)
from texkit import ASSETS

OUT = os.path.join(ASSETS, 'sounds', 'music')

# Akkorde: Grundton und Dreiklang.
DM = ('D', 'F', 'A')
C = ('C', 'E', 'G')
BB = ('Bb', 'D', 'F')
A = ('A', 'C#', 'E')
AM = ('A', 'C', 'E')
F = ('F', 'A', 'C')
G = ('G', 'Bb', 'D')
GMAJ = ('G', 'B', 'D')
EB = ('Eb', 'G', 'Bb')
EM = ('E', 'G', 'B')
DMAJ = ('D', 'F#', 'A')
CMIN = ('C', 'Eb', 'G')

# Das Leitmotiv: (Schlag, Ton, Länge).
MOTIF_A = [(0, 'D5', .5), (.5, 'E5', .5), (1, 'F5', 1), (2, 'A5', 1.5), (3.5, 'G5', .5),
           (4, 'F5', .5), (4.5, 'E5', .5), (5, 'D5', 1), (6, 'C5', 1), (7, 'A4', 1)]
MOTIF_B = [(0, 'Bb4', .5), (.5, 'C5', .5), (1, 'D5', 1), (2, 'F5', 1.5), (3.5, 'E5', .5),
           (4, 'D5', 1), (5, 'E5', 1), (6, 'A5', 2)]
BRIDGE = [(0, 'A5', 1.5), (1.5, 'G5', .5), (2, 'F5', 1), (3, 'C6', 1),
          (4, 'Bb5', 1.5), (5.5, 'A5', .5), (6, 'G5', 2),
          (8, 'F5', 1.5), (9.5, 'G5', .5), (10, 'A5', 1), (11, 'D6', 1),
          (12, 'C#6', 2), (14, 'E6', 2)]


def shift(melody, semis):
    """Transponiert eine Melodie um Halbtöne (für Modulationen und Oktavdopplungen in Hertz gerechnet)."""
    return [(b, hz(n) * 2 ** (semis / 12.0), d) for b, n, d in melody]


def freq_of(note):
    return note if isinstance(note, float) else hz(note)


class Score:
    """Schreibt in Schlägen statt Sekunden und kennt die wiederkehrenden Begleitfiguren."""

    def __init__(self, bpm, beats, loop=True, tail=0.6):
        self.beat = 60.0 / bpm
        self.mix = Mix(beats * self.beat, loop, tail)
        self.beats = beats

    def at(self, beat):
        return beat * self.beat

    def melody(self, start, notes, voice, gain, send=0.28, octave=0, pan=0.0):
        for b, note, length in notes:
            f = freq_of(note) * 2.0 ** octave
            self.mix.add(self.at(start + b), voice(f, length * self.beat), gain * 1.3, send, pan)

    def pad(self, start, chords, beats_each, octave=3, gain=0.2, bright=1.0):
        """Liegende Streicher: Grundton unten, Dreiklang darüber."""
        for i, chord in enumerate(chords):
            t = start + i * beats_each
            length = beats_each * self.beat
            for j, name in enumerate(chord):
                self.mix.add(self.at(t), strings(hz(f'{name}{octave + (1 if j and self._above(name, chord[0]) else 0)}'), length, bright), gain, 0.34)

    @staticmethod
    def _above(name, root):
        return hz(f'{name}4') < hz(f'{root}4')

    def choir_pad(self, start, chords, beats_each, octave=4, gain=0.17, vowel='a'):
        for i, chord in enumerate(chords):
            t = start + i * beats_each
            for j, name in enumerate(chord):
                self.mix.add(self.at(t), choir(hz(f'{name}{octave + (1 if j and self._above(name, chord[0]) else 0)}'), beats_each * self.beat, vowel), gain, 0.55)

    def ostinato(self, start, chords, beats_each, step=0.5, octave=2, gain=0.3, pattern=(0, 0, 1, 0, 2, 0, 1, 0)):
        """Treibende, kurz gestrichene Bässe: das, was einen Kampf nach vorn schiebt."""
        for i, chord in enumerate(chords):
            t = start + i * beats_each
            for s in range(int(beats_each / step)):
                name = chord[pattern[s % len(pattern)]]
                f = hz(f'{name}{octave + (1 if self._above(name, chord[0]) else 0)}')
                accent = 1.0 if s % 4 == 0 else (0.8 if s % 2 == 0 else 0.62)
                self.mix.add(self.at(t + s * step), staccato(f, min(0.26, step * self.beat * 0.9)), gain * accent, 0.1)

    def bass(self, start, chords, beats_each, octave=1, gain=0.34):
        for i, chord in enumerate(chords):
            self.mix.add(self.at(start + i * beats_each), strings(hz(f'{chord[0]}{octave}'), beats_each * self.beat, 0.4), gain * 0.6, 0.1)

    def harp_run(self, start, chords, beats_each, step=0.25, octave=4, gain=0.15):
        for i, chord in enumerate(chords):
            t = start + i * beats_each
            for s in range(int(beats_each / step)):
                index = s % (len(chord) * 2 - 2)
                index = index if index < len(chord) else len(chord) * 2 - 2 - index
                register = octave + (s // (len(chord) * 2 - 2)) % 2
                self.mix.add(self.at(t + s * step), harp(hz(f'{chord[index]}{register}')), gain, 0.35)

    def drums(self, start, bars, pattern, gain=1.0, beats_per_bar=4):
        """pattern: (Schlag im Takt, Klang, Lautstärke)."""
        for bar in range(bars):
            t = start + bar * beats_per_bar
            for pos, sound, level in pattern:
                self.mix.add(self.at(t + pos), sound() if callable(sound) else sound, level * gain, 0.2)

    def crash(self, beat, gain=0.5):
        self.mix.add(self.at(beat), cymbal(), gain, 0.3)

    def rise(self, beat, seconds=None, gain=0.5):
        length = seconds or self.beat * 4
        self.mix.add(self.at(beat) - length, swell(length), gain, 0.15)

    def hit(self, beat, chord, octave=3, gain=0.5):
        """Tutti-Schlag: Blech, Pauke und Becken zugleich."""
        for name in chord:
            self.mix.add(self.at(beat), brass(hz(f'{name}{octave + 1}'), self.beat * 1.2, 1.0), gain, 0.3)
        self.mix.add(self.at(beat), taiko(50.0), 0.6, 0.3)
        self.crash(beat, gain * 0.8)

    def render(self, target_rms=0.125):
        return self.mix.render(target_rms=target_rms)


def low():
    return taiko(58.0) * 0.6


def mid():
    return taiko(92.0, 0.7, 12) * 0.62


def small():
    return tom() * 0.8


DRIVE_PATTERN = [(0, low, 0.8), (1.5, mid, 0.4), (2, low, 0.65), (2.75, small, 0.3), (3, mid, 0.5), (3.5, small, 0.34)]
SHAKE_PATTERN = [(i * 0.5, shaker, 0.13 if i % 2 else 0.2) for i in range(8)]


def messenger():
    """Stage 1, Der Bote: 160 bpm, hell und schnell. Ein Bote, der noch glaubt, er sei uneinholbar."""
    s = Score(160, 48 * 4)
    i_chords = [DM, C, BB, A]
    # Einleitung: nur das Ostinato und die Harfe, die Trommeln kommen dazu.
    s.ostinato(0, [DM, DM, C, A], 4, gain=0.26)
    s.harp_run(0, [DM, DM, C, A], 4, gain=0.13)
    s.drums(8, 2, SHAKE_PATTERN)
    s.rise(16, gain=0.5)

    # A: das Motiv zweimal, erst Hörner, dann Trompeten.
    for bar, voice, gain, oct_ in ((4, horn, 0.3, 0), (12, brass, 0.3, 0)):
        beat = bar * 4
        s.melody(beat, MOTIF_A, voice, gain, octave=oct_)
        s.melody(beat + 8, MOTIF_B, voice, gain, octave=oct_)
        s.melody(beat + 16, MOTIF_A, voice, gain * 1.05, octave=oct_)
        s.melody(beat + 24, MOTIF_B, voice, gain * 1.05, octave=oct_)
    s.ostinato(16, i_chords * 8, 4, gain=0.29)
    s.pad(16, i_chords * 8, 4, gain=0.15)
    s.bass(16, i_chords * 8, 4, gain=0.28)
    s.harp_run(16, i_chords * 8, 4, step=0.5, gain=0.1)
    s.drums(16, 32, DRIVE_PATTERN)
    s.drums(16, 32, SHAKE_PATTERN)
    s.crash(16)
    s.crash(48, 0.4)

    # B: nach F-Dur gewendet, der Bote genießt die Jagd.
    b_chords = [F, C, DM, BB, F, C, G, A]
    s.pad(80, b_chords, 4, gain=0.17, bright=1.4)
    s.choir_pad(80, b_chords, 4, gain=0.1)
    s.ostinato(80, b_chords, 4, gain=0.3)
    s.bass(80, b_chords, 4, gain=0.28)
    s.melody(80, BRIDGE, brass, 0.32)
    s.melody(96, BRIDGE, brass, 0.32, octave=0)
    s.melody(96, BRIDGE, reed, 0.16, octave=1, pan=0.3)
    s.harp_run(80, b_chords * 2, 4, gain=0.12)
    s.pad(112 - 16, b_chords, 4, gain=0.15, bright=1.4)
    s.ostinato(96, b_chords, 4, gain=0.3)
    s.bass(96, b_chords, 4, gain=0.28)
    s.drums(80, 8, DRIVE_PATTERN, 1.1)
    s.drums(96, 8, DRIVE_PATTERN, 1.2)
    s.drums(80, 16, SHAKE_PATTERN, 1.2)
    s.crash(80, 0.5)
    s.crash(96, 0.45)

    # A': Tutti mit Chor und Oktaven.
    for offset in (112, 144):
        s.melody(offset, MOTIF_A, brass, 0.34)
        s.melody(offset, MOTIF_A, brass, 0.2, octave=1)
        s.melody(offset + 8, MOTIF_B, brass, 0.34)
        s.melody(offset + 8, MOTIF_B, brass, 0.2, octave=1)
        s.melody(offset + 16, MOTIF_A, brass, 0.36)
        s.melody(offset + 16, MOTIF_A, brass, 0.2, octave=1)
        s.melody(offset + 24, MOTIF_B, brass, 0.36)
        s.melody(offset + 24, MOTIF_B, brass, 0.2, octave=1)
    s.pad(112, i_chords * 16, 4, gain=0.17, bright=1.3)
    s.choir_pad(112, i_chords * 16, 4, gain=0.1)
    s.ostinato(112, i_chords * 16, 4, gain=0.32)
    s.bass(112, i_chords * 16, 4, gain=0.3)
    s.drums(112, 16, DRIVE_PATTERN, 1.25)
    s.drums(112, 16, SHAKE_PATTERN, 1.3)
    s.crash(112, 0.6)
    s.crash(144, 0.5)
    s.rise(192, gain=0.35)
    return s.render()


def thief():
    """Stage 2, Der Dieb: 128 bpm, gezupft und schleichend. Das Motiv wird chromatisch verbogen."""
    s = Score(128, 44 * 4)
    prowl = [DM, EB, DM, CMIN]
    walk = ['D2', 'F2', 'A2', 'Ab2', 'G2', 'F2', 'E2', 'Eb2', 'Eb2', 'G2', 'Bb2', 'A2', 'D2', 'E2', 'F2', 'G2',
            'D2', 'F2', 'A2', 'Ab2', 'G2', 'F2', 'E2', 'Eb2', 'C2', 'Eb2', 'G2', 'Gb2', 'F2', 'E2', 'Eb2', 'Db2']
    sly = [(0, 'D5', .5), (1, 'F5', .5), (1.5, 'E5', .5), (2, 'Eb5', .5), (3, 'D5', .5), (3.5, 'Db5', .5),
           (4, 'C5', 1), (5.5, 'Eb5', .5), (6, 'D5', 2)]
    answer = [(0, 'A4', .5), (.5, 'Bb4', .5), (1.5, 'D5', .5), (2, 'Db5', 1), (3.5, 'E5', .5),
              (4, 'Eb5', .5), (4.5, 'D5', .5), (5, 'C#5', 1), (6, 'D5', 2)]

    def walking(start, bars, gain=0.42):
        for b in range(bars * 2):
            for i in range(0, 16, 1):
                note = walk[(i + b * 16) % len(walk)]
                s.mix.add(s.at(start + b * 8 + i * 0.5), pizz(hz(note)), gain, 0.22)

    tick = [(0.5, rim, 0.28), (1.5, rim, 0.2), (2.5, rim, 0.28), (3.25, rim, 0.14), (3.5, rim, 0.24)]
    snares = [(0, low, 0.5), (2.5, mid, 0.34), (3, small, 0.3)]

    # Einleitung: nur der Bass schleicht und die Klarinette schaut sich um.
    walking(0, 2, 0.4)
    s.melody(8, sly, reed, 0.2, pan=-0.2)
    s.drums(0, 4, tick, 0.8)

    # A: Klarinette, dann gestopfte Hörner als Antwort.
    walking(16, 8)
    for offset in (16, 32):
        s.melody(offset, sly, reed, 0.26, pan=-0.25)
        s.melody(offset + 8, answer, horn, 0.24, pan=0.25)
    s.pad(16, prowl * 8, 4, octave=3, gain=0.09, bright=0.4)
    s.drums(16, 16, tick)
    s.drums(16, 16, snares)
    for beat in range(16, 48, 8):
        s.mix.add(s.at(beat + 2.5), harp(hz('A5')), 0.14, 0.5, 0.5)
        s.mix.add(s.at(beat + 3), harp(hz('Eb6')), 0.14, 0.5, -0.5)

    # B: der Griff in die Tasche. Tremolo, stechendes Blech, alles zieht an.
    walking(80 - 32 + 0, 8)
    lift = [DM, EB, F, EB, DM, CMIN, BB, A]
    s.pad(48, lift * 2, 4, gain=0.15, bright=0.9)
    s.ostinato(48, lift * 2, 4, step=0.25, gain=0.17, pattern=(0, 0, 0, 0))
    s.melody(48, shift(sly, 0), brass, 0.25)
    s.melody(56, shift(answer, 0), brass, 0.25)
    s.melody(64, shift(sly, 5), brass, 0.27)
    s.melody(72, shift(answer, 5), brass, 0.27)
    s.drums(48, 16, tick)
    s.drums(48, 16, DRIVE_PATTERN, 0.85)
    s.crash(48, 0.4)

    # Gestohlene Stille: zwei Takte fast nichts, dann ein Tutti-Schlag.
    s.mix.add(s.at(80), harp(hz('D6')), 0.2, 0.7)
    s.mix.add(s.at(81), harp(hz('Db6')), 0.17, 0.7)
    s.mix.add(s.at(82), harp(hz('C6')), 0.15, 0.7)
    s.drums(80, 2, tick, 0.7)
    s.hit(88, DM, gain=0.3)

    # A': volles Orchester, der Dieb ist aufgeflogen und findet das großartig.
    walking(88, 11)
    for offset in (88, 104, 120, 136):
        s.melody(offset, sly, brass, 0.26)
        s.melody(offset, sly, reed, 0.14, octave=1, pan=0.3)
        s.melody(offset + 8, answer, brass, 0.26)
    s.pad(88, prowl * 22, 4, gain=0.15, bright=1.0)
    s.drums(88, 22, tick, 1.1)
    s.drums(88, 22, DRIVE_PATTERN, 1.0)
    s.crash(120, 0.45)
    s.crash(152, 0.35)
    s.rise(176, gain=0.28)
    return s.render()


def guide():
    """Stage 3, Der Seelenführer: 84 bpm, phrygisch und schwer. Männerchor, Glocken, das Motiv ganz tief im Blech."""
    s = Score(84, 32 * 4)
    dirge = [DM, EB, DM, CMIN, BB, EB, DM, A]
    slow = [(0, 'D3', 2), (2, 'F3', 2), (4, 'A3', 3), (7, 'G3', 1), (8, 'F3', 2), (10, 'Eb3', 2), (12, 'D3', 4)]
    lament = [(0, 'A5', 2), (2, 'Bb5', 1), (3, 'A5', 1), (4, 'F5', 3), (7, 'E5', 1), (8, 'Eb5', 2), (10, 'D5', 2), (12, 'C#5', 2), (14, 'D5', 2)]
    toll = lambda beat: s.mix.add(s.at(beat), bell(hz('D3'), 5.0), 0.34, 0.55)

    # Einleitung: Glocke, tiefer Chor auf U.
    s.choir_pad(0, [DM, DM, EB, DM], 4, octave=2, gain=0.3, vowel='u')
    s.bass(0, [DM, DM, EB, DM], 4, octave=1, gain=0.3)
    for beat in range(0, 128, 8):
        toll(beat)

    deep = [(0, low, 1.0), (1.5, mid, 0.35), (2, low, 0.6), (3, mid, 0.4), (3.5, low, 0.5)]
    # A: das Motiv in Posaunenlage.
    s.choir_pad(16, dirge, 4, octave=2, gain=0.3, vowel='o')
    s.pad(16, dirge, 4, octave=2, gain=0.15, bright=0.4)
    s.bass(16, dirge, 4, gain=0.34)
    s.melody(16, slow, brass, 0.36)
    s.melody(32, slow, brass, 0.36)
    s.drums(16, 8, deep)

    # B: Seelen über dem Grund, hohe Stimmen klagen.
    s.choir_pad(48, dirge, 4, octave=3, gain=0.22, vowel='a')
    s.pad(48, dirge, 4, octave=3, gain=0.14, bright=0.6)
    s.bass(48, dirge, 4, gain=0.34)
    s.melody(48, lament, lambda f, d: choir(f, d, 'a'), 0.22)
    s.melody(64, lament, lambda f, d: choir(f, d, 'o'), 0.22)
    s.ostinato(48, dirge, 4, step=0.5, octave=2, gain=0.2, pattern=(0, 0, 0, 1, 0, 0, 2, 0))
    s.drums(48, 8, deep, 1.1)

    # C: Der Styx steigt. Alles zusammen, immer schwerer.
    s.choir_pad(80, dirge, 4, octave=3, gain=0.26)
    s.choir_pad(80, dirge, 4, octave=2, gain=0.25, vowel='o')
    s.pad(80, dirge, 4, gain=0.17, bright=0.9)
    s.bass(80, dirge, 4, gain=0.36)
    s.melody(80, slow, brass, 0.38)
    s.melody(80, slow, brass, 0.24, octave=1)
    s.melody(96, lament, brass, 0.26, octave=-1)
    s.ostinato(80, dirge, 4, step=0.25, octave=2, gain=0.17, pattern=(0, 0, 0, 0))
    s.drums(80, 8, deep, 1.25)
    s.crash(80, 0.45)
    s.crash(112, 0.3)

    # Ausklang: nur Chor und Glocke, bevor es von vorn beginnt.
    s.choir_pad(112, [DM, EB, DM, DM], 4, octave=2, gain=0.3, vowel='u')
    s.bass(112, [DM, EB, DM, DM], 4, gain=0.3)
    return s.render()


def finale():
    """Stage 4, Der Götterbote: 172 bpm, alles auf einmal. Das Motiv im Tutti, Chor, Becken und Modulation nach oben."""
    s = Score(172, 72 * 4)
    war = [DM, BB, G, A]
    heroic = [F, C, DM, BB, GMAJ, DMAJ, EM, A]

    fury = [(0, low, 1.0), (0.5, small, 0.3), (1, mid, 0.6), (1.5, small, 0.4), (2, low, 0.85), (2.5, mid, 0.4), (3, mid, 0.65), (3.25, small, 0.34), (3.5, small, 0.44), (3.75, small, 0.5)]

    def tutti(start, chords, each=4, gain=1.0):
        count = len(chords)
        s.pad(start, chords, each, gain=0.19 * gain, bright=1.5)
        s.choir_pad(start, chords, each, gain=0.13 * gain)
        s.ostinato(start, chords, each, gain=0.33 * gain, pattern=(0, 0, 0, 1, 0, 0, 2, 1))
        s.ostinato(start, chords, each, octave=3, gain=0.17 * gain, pattern=(0, 1, 2, 1, 0, 1, 2, 1))
        s.bass(start, chords, each, gain=0.34 * gain)
        s.drums(start, count * each // 4, fury, 1.25 * gain)
        s.drums(start, count * each // 4, SHAKE_PATTERN, 1.2 * gain)

    def anthem(start, semis=0, gain=0.38):
        for b, melody in ((0, MOTIF_A), (8, MOTIF_B), (16, MOTIF_A), (24, MOTIF_B)):
            notes = shift(melody, semis)
            s.melody(start + b, notes, brass, gain)
            s.melody(start + b, notes, brass, gain * 0.62, octave=1)
            s.melody(start + b, notes, lambda f, d: choir(f, d, 'a'), gain * 0.42, octave=-1)

    # Einleitung: drei Tutti-Schläge, dazwischen rast das Ostinato los.
    s.ostinato(0, [DM, DM, BB, A], 4, gain=0.3)
    s.hit(0, DM, gain=0.36)
    s.hit(6, DM, gain=0.36)
    s.hit(8, BB, gain=0.36)
    s.hit(12, A, gain=0.38)
    s.harp_run(0, [DM, DM, BB, A], 4, gain=0.12)
    s.rise(16, gain=0.55)

    # A: die Hymne, das Leitmotiv in voller Pracht.
    tutti(16, war * 8)
    anthem(16)
    anthem(48)
    s.crash(16, 0.65)
    s.crash(48, 0.55)
    s.rise(80, gain=0.5)

    # B: Dur bricht durch, der Kampf wird schön.
    tutti(80, heroic * 2, 4, 1.05)
    s.melody(80, BRIDGE, brass, 0.4)
    s.melody(80, BRIDGE, brass, 0.25, octave=1)
    s.melody(96, shift(BRIDGE, 0), brass, 0.4)
    s.melody(96, shift(BRIDGE, 0), lambda f, d: choir(f, d, 'a'), 0.2, octave=-1)
    s.harp_run(80, heroic * 2, 4, gain=0.14)
    s.crash(80, 0.65)
    s.crash(96, 0.55)
    s.rise(144, gain=0.6)

    # C: Rückung einen Ganzton nach oben. Nach E-Moll, mit allem, was da ist.
    em_war = [EM, ('C', 'E', 'G'), AM, ('B', 'D#', 'F#')]
    tutti(112, em_war * 2, 4, 0.9)
    anthem(112, 2, 0.34)
    # Leise Stelle: nur Trommeln und Chor, das letzte Luftholen vor der Rückkehr.
    s.choir_pad(144, [DM, BB, G, A], 4, gain=0.28, vowel='o')
    s.drums(144, 4, fury, 0.9)
    s.rise(160, gain=0.7)

    # A': Rückkehr nach D-Moll, höchste Lage.
    tutti(160, war * 8, 4, 1.12)
    anthem(160, 0, 0.42)
    anthem(192, 0, 0.42)
    s.crash(160, 0.72)
    s.crash(192, 0.65)
    s.crash(224, 0.65)

    # Schluss zur Schleife: Stakkato-Akkorde, die in den Anfang zurückfallen.
    tutti(224, war * 4, 4, 1.1)
    anthem(224, 0, 0.42)
    s.melody(256, [(0, 'A5', 1), (1, 'A5', 1), (2, 'A5', 1), (3, 'A5', 1)], brass, 0.42)
    tutti(256, [A, A, A, A, DM, BB, G, A], 4, 1.0)
    s.rise(288, gain=0.45)
    return s.render()


def shade():
    """Der Schatten des Boten: 148 bpm, phrygisch. Sein Leitmotiv, aber verdorben und im Keller einer Kathedrale."""
    s = Score(148, 52 * 4)
    doom = [DM, EB, DM, CMIN, BB, EB, ('A', 'C#', 'F'), DM]
    twist = [(b, hz(n) * 2 ** (-1 / 12.0) if n in ('E5', 'A4') else hz(n), d) for b, n, d in MOTIF_A]
    grind = [(0, low, 1.0), (0.75, small, 0.34), (1.5, mid, 0.5), (2, low, 0.8), (2.5, small, 0.4), (3, mid, 0.6), (3.5, small, 0.5), (3.75, small, 0.5)]

    # Einleitung: Glocke und Chor, das Ostinato kriecht herein.
    for beat in range(0, 16, 4):
        s.mix.add(s.at(beat), bell(hz('D3'), 4.0), 0.3, 0.55)
    s.choir_pad(0, [DM, EB, DM, DM], 4, octave=2, gain=0.26, vowel='u')
    s.ostinato(8, [DM, EB], 4, gain=0.26, pattern=(0, 0, 0, 0, 0, 0, 1, 0))
    s.rise(16, gain=0.5)

    # A: das verdorbene Motiv im Blech über mahlendem Bass.
    for start in (16, 48):
        s.pad(start, doom * 2 if start == 16 else doom * 2, 4, octave=2, gain=0.15, bright=0.7)
        s.choir_pad(start, doom, 8, octave=3, gain=0.14)
        s.ostinato(start, doom * 2, 4, gain=0.3, pattern=(0, 0, 1, 0, 0, 2, 0, 1))
        s.bass(start, doom * 2, 4, gain=0.32)
        s.drums(start, 8, grind, 1.1)
        for offset in (0, 16):
            s.melody(start + offset, twist, brass, 0.32, octave=-1)
            s.melody(start + offset + 8, shift(MOTIF_B, -1), brass, 0.3, octave=-1)
        s.crash(start, 0.5)

    # B: Hohe Stimmen klagen, das Blech antwortet.
    lament = [(0, 'A5', 2), (2, 'Bb5', 1), (3, 'A5', 1), (4, 'F5', 3), (7, 'E5', 1), (8, 'Eb5', 2), (10, 'D5', 2), (12, 'C#5', 2), (14, 'D5', 2)]
    s.choir_pad(80, doom * 2, 4, octave=3, gain=0.2)
    s.pad(80, doom * 2, 4, gain=0.15, bright=0.9)
    s.bass(80, doom * 2, 4, gain=0.32)
    s.ostinato(80, doom * 2, 4, step=0.25, gain=0.18, pattern=(0, 0, 0, 0))
    s.melody(80, lament, lambda f, d: choir(f, d, 'a'), 0.28)
    s.melody(96, lament, brass, 0.26)
    s.drums(80, 8, grind, 1.0)
    s.drums(80, 8, SHAKE_PATTERN, 0.9)

    # Klimax: alles, eine Oktave höher.
    s.rise(112, gain=0.6)
    s.pad(112, doom * 4, 4, gain=0.18, bright=1.2)
    s.choir_pad(112, doom * 4, 4, gain=0.16)
    s.ostinato(112, doom * 4, 4, gain=0.34, pattern=(0, 0, 1, 0, 0, 2, 0, 1))
    s.bass(112, doom * 4, 4, gain=0.34)
    s.drums(112, 16, grind, 1.25)
    s.drums(112, 16, SHAKE_PATTERN, 1.2)
    for offset in (112, 144):
        s.melody(offset, twist, brass, 0.38)
        s.melody(offset, twist, brass, 0.22, octave=-1)
        s.melody(offset + 8, shift(MOTIF_B, -1), brass, 0.38)
        s.melody(offset + 16, twist, brass, 0.38)
        s.melody(offset + 16, twist, brass, 0.22, octave=-1)
        s.melody(offset + 24, shift(MOTIF_B, -1), brass, 0.38)
    s.crash(112, 0.65)
    s.crash(144, 0.6)

    # Abstieg zurück in die Glocke.
    s.choir_pad(176, [DM, EB, CMIN, DM, DM, EB, DM, DM], 4, octave=2, gain=0.28, vowel='o')
    s.bass(176, [DM, EB, CMIN, DM, DM, EB, DM, DM], 4, gain=0.3)
    s.ostinato(176, [DM, EB, CMIN, DM], 4, gain=0.24, pattern=(0, 0, 0, 0, 0, 0, 1, 0))
    for beat in range(176, 208, 8):
        s.mix.add(s.at(beat), bell(hz('D3'), 5.0), 0.34, 0.55)
    return s.render()


def hades():
    """Hades: 104 bpm und unerschütterlich. Kein Leitmotiv von Hermes, sondern ein eigener, absteigender Viertonschritt."""
    s = Score(104, 44 * 4)
    throne = [DM, BB, CMIN, DM, EB, BB, A, DM]
    descent = [(0, 'D4', 2), (2, 'C4', 2), (4, 'Bb3', 2), (6, 'A3', 2)]
    reply = [(0, 'F4', 1.5), (1.5, 'E4', .5), (2, 'D4', 2), (4, 'Eb4', 2), (6, 'D4', 2)]
    high = [(0, 'A5', 3), (3, 'Bb5', 1), (4, 'A5', 2), (6, 'F5', 2), (8, 'E5', 3), (11, 'F5', 1), (12, 'D5', 4)]
    march = [(0, low, 1.0), (1, mid, 0.4), (2, low, 0.75), (3, mid, 0.5), (3.5, small, 0.35)]

    # Einleitung: eine Glocke, tiefer Chor, dann setzt der Schritt ein.
    for beat in range(0, 176, 8):
        s.mix.add(s.at(beat), bell(hz('D2'), 6.0), 0.34, 0.6)
    s.choir_pad(0, [DM, DM, BB, DM], 4, octave=2, gain=0.3, vowel='u')
    s.bass(0, [DM, DM, BB, DM], 4, gain=0.32)
    s.drums(8, 2, march, 0.6)
    s.rise(16, gain=0.45)

    # A: Blech in der Tiefe, der Abstieg wiederholt sich wie ein Urteil.
    for start in (16, 48):
        s.choir_pad(start, throne * 2, 4 if start == 16 else 4, octave=2, gain=0.24, vowel='o')
        s.pad(start, throne * 2, 4, octave=2, gain=0.15, bright=0.5)
        s.bass(start, throne * 2, 4, gain=0.34)
        s.ostinato(start, throne * 2, 4, step=1.0, gain=0.3, pattern=(0, 0, 2, 0))
        s.drums(start, 8, march)
        for offset in (0, 16):
            s.melody(start + offset, descent, brass, 0.36, octave=-1)
            s.melody(start + offset + 8, reply, brass, 0.34, octave=-1)
        s.crash(start, 0.4)

    # B: Hoch über allem klagen die Stimmen, die er besitzt.
    s.choir_pad(80, throne * 2, 4, octave=3, gain=0.2)
    s.pad(80, throne * 2, 4, gain=0.15, bright=0.8)
    s.bass(80, throne * 2, 4, gain=0.34)
    s.ostinato(80, throne * 2, 4, step=0.5, gain=0.24, pattern=(0, 0, 0, 2, 0, 0, 1, 0))
    s.melody(80, high, lambda f, d: choir(f, d, 'a'), 0.26)
    s.melody(96, high, lambda f, d: choir(f, d, 'a'), 0.26)
    s.melody(96, high, brass, 0.2, octave=-1)
    s.drums(80, 8, march, 1.1)

    # Klimax: alles zugleich, in voller Schwere.
    s.rise(112, gain=0.55)
    s.choir_pad(112, throne * 3, 4, octave=3, gain=0.2)
    s.choir_pad(112, throne * 3, 4, octave=2, gain=0.22, vowel='o')
    s.pad(112, throne * 3, 4, gain=0.18, bright=1.1)
    s.bass(112, throne * 3, 4, gain=0.36)
    s.ostinato(112, throne * 3, 4, step=0.5, gain=0.3, pattern=(0, 0, 2, 0, 0, 1, 2, 0))
    s.drums(112, 12, march, 1.25)
    for offset in (112, 128, 144):
        s.melody(offset, descent, brass, 0.4)
        s.melody(offset, descent, brass, 0.3, octave=-1)
        s.melody(offset + 8, reply, brass, 0.38)
    s.crash(112, 0.6)
    s.crash(144, 0.55)
    s.rise(176, gain=0.3)
    return s.render()




# =================================================================================================================
# Überarbeitung 3 (P04): RPG-Hauptthema, Gebiete, Olymp- und Tartaros-Bosse, Stinger, Abspann.
# Jedes Stück hat ein eigenes Leitmotiv; die Phasen-Fassungen verwandeln es (Verkürzung, tiefere Lage, dichteres
# Schlagwerk, zweite Stimme). Schleifen laufen im Kreispuffer (Hall und Ausklänge falten in den Anfang),
# Stinger und Abspann mit loop=False (linearer Hall, Ausblende).
# =================================================================================================================

BM = ('B', 'D', 'F#')
FSM = ('F#', 'A', 'C#')
CSM = ('C#', 'E', 'G#')
EMAJ = ('E', 'G#', 'B')
AMAJ = A
BMAJ = ('B', 'D#', 'F#')
FMIN = ('F', 'Ab', 'C')
DB = ('Db', 'F', 'Ab')
BBM = ('Bb', 'Db', 'F')
CMAJ = C
AB = ('Ab', 'C', 'Eb')
GM = G            # g-Moll (Bestandsname G ist Moll)
DMIN = DM
EBMAJ = EB
FMAJ = F


def retro(melody, span):
    """Krebs: dieselbe Melodie rückwärts innerhalb von span Schlägen."""
    return sorted(((span - b - d, n, d) for b, n, d in melody), key=lambda x: x[0])


def dimin(melody, factor=0.5):
    """Verkürzung: alle Zeiten mal factor."""
    return [(b * factor, n, d * factor) for b, n, d in melody]


def flute_v(f, d):
    return flute(f, d)


def choir_a(f, d):
    return choir(f, d, 'a')


def choir_o(f, d):
    return choir(f, d, 'o')


def choir_u(f, d):
    return choir(f, d, 'u')


def strings_v(f, d):
    return strings(f, d, 1.1)


def soft_horn(f, d):
    return horn(f, d) * 0.8


def deep():
    return taiko(46.0, 1.4, 21) * 0.7


# ---- RPG-Hauptthema (D-Dur): kehrt in Tunnel, Olymp, Sieg und Abspann wieder ------------------------------------

MAIN_A = [(0, 'A4', 1.5), (1.5, 'D5', .5), (2, 'E5', 1), (3, 'F#5', 1), (4, 'A5', 2.5), (6.5, 'G5', .5), (7, 'F#5', 1),
          (8, 'E5', 1.5), (9.5, 'D5', .5), (10, 'B4', 1), (11, 'D5', 1), (12, 'E5', 4)]
MAIN_B = [(0, 'A4', 1.5), (1.5, 'D5', .5), (2, 'E5', 1), (3, 'F#5', 1), (4, 'B5', 2.5), (6.5, 'A5', .5), (7, 'G5', 1),
          (8, 'F#5', 1.5), (9.5, 'E5', .5), (10, 'C#5', 1), (11, 'E5', 1), (12, 'D5', 4)]
MAIN_A_CH = [DMAJ, BM, EM, AMAJ]
MAIN_B_CH = [DMAJ, GMAJ, AMAJ, DMAJ]
LYDIAN = [DMAJ, EMAJ, DMAJ, EMAJ]


def tunnel():
    """music.tunnel: 60 bpm, schwebend und weit. Das Hauptthema einmal leise, sonst Flächen; steigert sich nicht."""
    s = Score(60, 64)
    plan = [(0, LYDIAN), (16, MAIN_A_CH), (32, [DMAJ, EMAJ, BM, GMAJ]), (48, MAIN_B_CH)]
    for start, chords in plan:
        s.pad(start, chords, 4, octave=3, gain=0.13, bright=0.7)
        s.choir_pad(start, chords, 4, octave=4, gain=0.07, vowel='o')
        s.harp_run(start, chords, 4, step=0.5, octave=4, gain=0.07)
    s.mix.add(0, drone(hz('D2'), 32.0, 420.0), 0.5, 0.3)
    s.mix.add(s.at(32), drone(hz('D2'), 32.0, 420.0), 0.5, 0.3)
    s.melody(16, MAIN_A, flute_v, 0.16, send=0.5)
    s.melody(48, MAIN_B, choir_u, 0.12, send=0.6)
    for beat in (0, 16, 32, 48):
        s.mix.add(s.at(beat + 2), bell(hz('A5'), 4.0), 0.06, 0.7, -0.4)
        s.mix.add(s.at(beat + 10), bell(hz('F#5'), 4.0), 0.05, 0.7, 0.4)
    return s.render(0.1)


def olympus():
    """music.olympus: 72 bpm, hell und ruhig. Das Hauptthema in Dur im Horn, dann in den Streichern mit Flöte."""
    s = Score(72, 96)
    intro = [DMAJ, GMAJ, DMAJ, AMAJ]
    s.pad(0, intro, 4, gain=0.13, bright=1.0)
    s.harp_run(0, intro, 4, step=0.5, gain=0.1)
    for start in (16, 64):
        s.pad(start, MAIN_A_CH + MAIN_B_CH, 4, gain=0.14, bright=1.1)
        s.bass(start, MAIN_A_CH + MAIN_B_CH, 4, gain=0.22)
        s.harp_run(start, MAIN_A_CH + MAIN_B_CH, 4, step=0.5, gain=0.08)
    s.melody(16, MAIN_A, soft_horn, 0.24, send=0.45)
    s.melody(32, MAIN_B, soft_horn, 0.24, send=0.45)
    inter = [GMAJ, DMAJ, EM, AMAJ]
    s.pad(48, inter, 4, gain=0.13, bright=1.2)
    s.choir_pad(48, inter, 4, gain=0.08, vowel='o')
    s.bass(48, inter, 4, gain=0.2)
    s.melody(48, [(0, 'B5', 3), (3, 'A5', 1), (4, 'F#5', 3), (7, 'E5', 1), (8, 'G5', 2), (10, 'F#5', 2), (12, 'E5', 4)], flute_v, 0.16, send=0.5)
    s.melody(64, MAIN_A, strings_v, 0.2, send=0.4)
    s.melody(64, MAIN_A, flute_v, 0.1, octave=1, send=0.5)
    s.melody(80, MAIN_B, strings_v, 0.2, send=0.4)
    s.melody(80, MAIN_B, flute_v, 0.1, octave=1, send=0.5)
    s.choir_pad(64, MAIN_A_CH + MAIN_B_CH, 4, gain=0.07, vowel='a')
    for beat in range(0, 96, 8):
        s.mix.add(s.at(beat), timpani(hz('D2'), 2.0), 0.18, 0.3)
    return s.render(0.105)


def victory_olymp():
    """music.victory_olymp: 8 s, keine Schleife. Der Kopf des Hauptthemas als Fanfare, Kadenz G-A-D."""
    s = Score(90, 12, loop=False, tail=1.6)
    head = [(0, 'A4', .5), (.5, 'D5', .5), (1, 'E5', .5), (1.5, 'F#5', .5), (2, 'A5', 2)]
    s.melody(0, head, brass, 0.34)
    s.melody(0, head, brass, 0.2, octave=-1)
    for name in DMAJ:
        s.mix.add(0, strings(hz(f'{name}4'), s.beat * 4, 1.3), 0.12, 0.35)
    s.mix.add(0, timpani(hz('D2')), 0.5, 0.3)
    for beat, chord in ((4, GMAJ), (5, AMAJ)):
        for name in chord:
            s.mix.add(s.at(beat), brass(hz(f'{name}4'), s.beat * 0.9, 1.0), 0.18, 0.3)
        s.mix.add(s.at(beat), timpani(hz('A1') if chord is AMAJ else hz('G2')), 0.4, 0.3)
    for name, octave in (('D', 3), ('A', 3), ('D', 4), ('F#', 4), ('A', 4), ('D', 5)):
        s.mix.add(s.at(6), brass(hz(f'{name}{octave}'), s.beat * 4.5, 1.0), 0.16, 0.35)
        s.mix.add(s.at(6), choir(hz(f'{name}{octave}'), s.beat * 5.0, 'a'), 0.1, 0.5)
    s.mix.add(s.at(6), timpani(hz('D2'), 2.6), 0.6, 0.3)
    s.mix.add(s.at(6), cymbal(), 0.4, 0.35)
    s.mix.add(s.at(6), bell(hz('D5'), 4.0), 0.14, 0.6)
    s.rise(6, s.beat * 2, 0.35)
    return s.render(0.13)


def credits():
    """music.credits: 80 bpm, 90 s, keine Schleife. Das Hauptthema voll: Blech, Chor, Rückung nach E-Dur, Schluss."""
    s = Score(80, 120, loop=False, tail=4.0)
    intro = [DMAJ, GMAJ, BM, AMAJ]
    s.pad(0, intro, 4, gain=0.15, bright=1.1)
    s.choir_pad(0, intro, 4, gain=0.1, vowel='o')
    s.harp_run(0, intro, 4, gain=0.1)
    s.melody(4, [(0, 'A4', 1.5), (1.5, 'D5', .5), (2, 'E5', 1), (3, 'F#5', 5)], soft_horn, 0.22)
    s.rise(16, gain=0.35)
    # A: Thema im Blech
    chords = MAIN_A_CH + MAIN_B_CH
    s.pad(16, chords * 2, 4, gain=0.16, bright=1.3)
    s.bass(16, chords * 2, 4, gain=0.28)
    s.ostinato(16, chords * 2, 4, step=0.5, gain=0.2, pattern=(0, 1, 2, 1))
    s.melody(16, MAIN_A, brass, 0.3)
    s.melody(32, MAIN_B, brass, 0.3)
    s.drums(16, 8, [(0, low, 0.6), (2, mid, 0.35), (3.5, small, 0.2)])
    s.crash(16, 0.4)
    # B: Brücke in h-Moll, der Chor singt
    bridge_ch = [BM, GMAJ, DMAJ, AMAJ]
    bridge = [(0, 'F#5', 2), (2, 'E5', 1), (3, 'D5', 1), (4, 'B4', 3), (7, 'C#5', 1), (8, 'D5', 2), (10, 'E5', 1),
              (11, 'F#5', 1), (12, 'A5', 4)]
    s.pad(48, bridge_ch, 4, gain=0.16, bright=1.0)
    s.choir_pad(48, bridge_ch, 4, gain=0.1)
    s.bass(48, bridge_ch, 4, gain=0.26)
    s.harp_run(48, bridge_ch, 4, gain=0.1)
    s.melody(48, bridge, choir_a, 0.24)
    s.melody(48, bridge, strings_v, 0.12, octave=-1)
    s.rise(64, gain=0.5)
    # A': Rückung nach E-Dur, Tutti mit Chor
    up = [EMAJ, CSM, FSM, BMAJ, EMAJ, AMAJ, BMAJ, EMAJ]
    s.pad(64, up * 2, 4, gain=0.18, bright=1.4)
    s.choir_pad(64, up * 2, 4, gain=0.12)
    s.bass(64, up * 2, 4, gain=0.3)
    s.ostinato(64, up * 2, 4, step=0.5, gain=0.24, pattern=(0, 0, 1, 0, 2, 0, 1, 0))
    for off, mel in ((64, MAIN_A), (80, MAIN_B)):
        s.melody(off, shift(mel, 2), brass, 0.34)
        s.melody(off, shift(mel, 2), brass, 0.2, octave=1)
        s.melody(off, shift(mel, 2), choir_a, 0.14, octave=-1)
    s.drums(64, 8, [(0, low, 0.8), (1.5, mid, 0.4), (2, low, 0.6), (3, mid, 0.5), (3.5, small, 0.3)], 1.1)
    s.crash(64, 0.55)
    s.crash(80, 0.45)
    # Schluss: der Kopf noch einmal im Horn, dann der letzte Akkord in E-Dur
    s.pad(96, [EMAJ, AMAJ, EMAJ], 4, gain=0.16, bright=1.0)
    s.choir_pad(96, [EMAJ, AMAJ, EMAJ], 4, gain=0.1, vowel='o')
    s.melody(96, shift([(0, 'A4', 1.5), (1.5, 'D5', .5), (2, 'E5', 1), (3, 'F#5', 1), (4, 'A5', 4)], 2), soft_horn, 0.24)
    for name, octave in (('E', 2), ('B', 2), ('E', 3), ('G#', 3), ('B', 3), ('E', 4), ('G#', 4), ('B', 4)):
        s.mix.add(s.at(108), strings(hz(f'{name}{octave}'), s.beat * 10, 1.0), 0.1, 0.4)
        s.mix.add(s.at(108), choir(hz(f'{name}{octave}'), s.beat * 10, 'a'), 0.06, 0.55)
    s.mix.add(s.at(108), timpani(hz('E2'), 3.0), 0.5, 0.35)
    s.mix.add(s.at(108), bell(hz('E5'), 5.0), 0.15, 0.6)
    s.crash(108, 0.4)
    return s.render(0.12)


# ---- Tartaros: tiefe Flächen, ferne Ketten, fast ohne Melodie ----------------------------------------------------


def tartaros():
    """music.tartaros: 60 bpm, 80 s. D-phrygisch, Flächen in der Tiefe, Wind, ferne Ketten, eine einzige Figur."""
    s = Score(60, 80)
    roots = [('D', DMIN), ('Eb', EBMAJ), ('D', DMIN), ('Bb', BB), ('D', DMIN)]
    for i, (root, chord) in enumerate(roots):
        start = i * 16
        s.mix.add(s.at(start), drone(hz(f'{root}1'), 18.0, 360.0), 0.55, 0.3)
        s.mix.add(s.at(start), drone(hz(f'{root}2') * 1.5, 18.0, 520.0, 3, 85), 0.22, 0.4)
        s.choir_pad(start, [chord], 16, octave=2, gain=0.12, vowel='u')
    s.mix.add(0, wind(80.0, 160.0, 900.0), 0.16, 0.4)
    gen = np.random.default_rng(5)
    for k in range(9):
        beat = 4 + k * 8 + float(gen.uniform(-1.5, 1.5))
        s.mix.add(s.at(beat), chains(1.6, 97 + k, 18), 0.22, 0.8, float(gen.uniform(-0.8, 0.8)))
    for beat in (0, 32, 64):
        s.mix.add(s.at(beat), bell(hz('D2'), 6.0), 0.16, 0.7)
    figure = [(0, 'D3', 3), (3, 'Eb3', 2), (5, 'D3', 5)]
    s.melody(20, figure, soft_horn, 0.16, send=0.6)
    s.melody(52, figure, soft_horn, 0.14, octave=-1, send=0.6)
    for beat in range(0, 80, 8):
        s.mix.add(s.at(beat), deep(), 0.25, 0.5)
    return s.render(0.09)


def victory_tartaros():
    """music.victory_tartaros: 8 s, keine Schleife. Der Abstieg des Hades löst sich nach D-Dur auf."""
    s = Score(75, 10, loop=False, tail=1.8)
    descent = [(0, 'D4', 1), (1, 'C4', 1), (2, 'Bb3', 1), (3, 'A3', 1)]
    s.melody(0, descent, brass, 0.3, octave=-1)
    s.melody(0, descent, choir_o, 0.18)
    s.mix.add(0, bell(hz('D3'), 5.0), 0.2, 0.6)
    for beat, root in ((0, 'D'), (2, 'Bb')):
        s.mix.add(s.at(beat), timpani(hz(f'{root}2')), 0.35, 0.3)
    for name, octave in (('D', 2), ('A', 2), ('D', 3), ('F#', 3), ('A', 3), ('D', 4), ('F#', 4)):
        s.mix.add(s.at(4), choir(hz(f'{name}{octave}'), s.beat * 5.5, 'o' if octave < 3 else 'a'), 0.1, 0.55)
        s.mix.add(s.at(4), strings(hz(f'{name}{octave}'), s.beat * 5.5, 0.9), 0.08, 0.4)
    s.mix.add(s.at(4), timpani(hz('D2'), 2.8), 0.55, 0.3)
    s.mix.add(s.at(4), bell(hz('F#4'), 5.0), 0.14, 0.6)
    s.mix.add(s.at(4), cymbal(), 0.3, 0.4)
    return s.render(0.12)


# ---- Hekate (g-Moll): drei Stimmen im Wechsel --------------------------------------------------------------------

HEK = [(0, 'G4', 1), (1, 'Bb4', .5), (1.5, 'A4', .5), (2, 'D5', 1.5), (3.5, 'C5', .5),
       (4, 'Bb4', 1), (5, 'A4', 1), (6, 'F#4', 2)]
HEK_CH = [GM, DMAJ, EBMAJ, DMAJ]
HEK_VOICES = ((flute_v, 0.2, 1, -0.35), (choir_a, 0.2, 0, 0.0), (soft_horn, 0.22, -1, 0.35))
HEK_DRUM = [(0, deep, 0.9), (2.5, mid, 0.3), (3, low, 0.5)]


def hekate():
    """music.hekate: 96 bpm. Das Motiv wandert durch drei Stimmen (Flöte, Chor, Horn), tiefe Trommel."""
    s = Score(96, 96)
    s.pad(0, HEK_CH * 6, 4, octave=2, gain=0.12, bright=0.6)
    s.bass(0, HEK_CH * 6, 4, gain=0.24)
    s.drums(0, 24, HEK_DRUM)
    s.melody(4, [(0, 'G5', 2), (2, 'F#5', 2), (4, 'D5', 8)], flute_v, 0.12, send=0.5)
    for k in range(6):
        voice, gain, octave, pan = HEK_VOICES[k % 3]
        s.melody(16 + k * 8, HEK, voice, gain, octave=octave, pan=pan)
    # Alle drei zugleich, um einen Takt versetzt
    for k, (voice, gain, octave, pan) in enumerate(HEK_VOICES):
        s.melody(64 + k * 4, HEK, voice, gain * 0.8, octave=octave, pan=pan)
        s.melody(80 + k * 4, HEK, voice, gain * 0.8, octave=octave, pan=pan)
    s.harp_run(64, HEK_CH * 2, 4, step=0.5, gain=0.07)
    for beat in range(0, 96, 16):
        s.mix.add(s.at(beat), bell(hz('G3'), 4.0), 0.12, 0.6)
    return s.render()


def hekate_2():
    """music.hekate_2: 120 bpm, dichter. Stimmen setzen im Abstand von zwei Schlägen ein, Ostinato, Blech."""
    s = Score(120, 120)
    chords = HEK_CH * 8
    s.hit(0, GM, gain=0.3)
    s.ostinato(0, chords[:30], 4, step=0.5, gain=0.24, pattern=(0, 0, 1, 0, 2, 0, 1, 0))
    s.pad(8, HEK_CH * 7, 4, gain=0.14, bright=0.9)
    s.bass(0, chords[:30], 4, gain=0.28)
    s.drums(0, 30, [(0, deep, 0.9), (1, small, 0.3), (1.5, mid, 0.35), (2, low, 0.6), (3, mid, 0.45), (3.5, small, 0.3)])
    s.drums(8, 22, SHAKE_PATTERN, 0.9)
    for start in (8, 24):
        for k, (voice, gain, octave, pan) in enumerate(HEK_VOICES):
            s.melody(start + k * 2, HEK, voice, gain, octave=octave, pan=pan)
            s.melody(start + 8 + k * 2, HEK, voice, gain, octave=octave, pan=pan)
    s.choir_pad(56, HEK_CH * 2, 4, octave=4, gain=0.12)
    s.melody(56, HEK, brass, 0.3, octave=-1)
    s.melody(64, dimin(HEK) + [(b + 4, n, d) for b, n, d in dimin(HEK)], brass, 0.26)
    s.melody(72, HEK, brass, 0.3)
    s.melody(72, HEK, flute_v, 0.16, octave=1)
    s.crash(56, 0.45)
    s.rise(88, gain=0.45)
    for k, (voice, gain, octave, pan) in enumerate(HEK_VOICES):
        s.melody(88 + k * 2, HEK, voice, gain, octave=octave, pan=pan)
        s.melody(104 + k * 2, HEK, voice, gain, octave=octave, pan=pan)
    s.melody(88, HEK, brass, 0.22, octave=-1)
    s.melody(104, HEK, brass, 0.22, octave=-1)
    s.harp_run(88, HEK_CH * 2, 4, gain=0.09)
    s.crash(88, 0.5)
    return s.render()


# ---- Brontes (c-Moll): Schmiede, Amboss auf den Zählzeiten -------------------------------------------------------

BRON = [(0, 'C3', 1.5), (1.5, 'Eb3', .5), (2, 'G3', 1), (3, 'F3', 1), (4, 'Eb3', 1), (5, 'D3', 1), (6, 'C3', 2)]
BRON_ANS = [(0, 'G3', 1.5), (1.5, 'Ab3', .5), (2, 'G3', 1), (3, 'F3', 1), (4, 'Eb3', 1), (5, 'F3', 1), (6, 'G3', 2)]
BRON_CH = [CMIN, AB, EBMAJ, GMAJ]


def bellows(s, beat, seconds=2.4, gain=0.12):
    """Blasebalg: ein tiefes Rauschen, das anschwillt und sinkt."""
    s.mix.add(s.at(beat), wind(seconds, 90.0, 420.0, 101), gain, 0.3)


def brontes():
    """music.brontes: 80 bpm, schwer. Amboss auf jeder Zählzeit, das Motiv in den Posaunen, der Chor antwortet."""
    s = Score(80, 80)
    s.pad(0, BRON_CH * 5, 4, octave=2, gain=0.13, bright=0.5)
    s.bass(0, BRON_CH * 5, 4, gain=0.3)
    for beat in range(80):
        s.mix.add(s.at(beat), anvil(780.0 if beat % 4 else 700.0), 0.34 if beat % 4 == 0 else 0.2, 0.25)
    s.drums(0, 20, [(0, low, 0.9), (2, low, 0.6), (3.5, mid, 0.3)])
    for beat in range(0, 80, 8):
        bellows(s, beat + 5)
    for start in (16, 32):
        s.melody(start, BRON, brass, 0.34)
        s.melody(start + 8, BRON_ANS, brass, 0.32)
    s.choir_pad(48, BRON_CH, 4, octave=3, gain=0.14, vowel='o')
    s.melody(48, BRON, choir_o, 0.22, octave=1)
    s.melody(56, BRON_ANS, horn, 0.26, octave=1)
    s.melody(64, BRON, brass, 0.36)
    s.melody(64, BRON, choir_a, 0.16, octave=1)
    s.melody(72, BRON_ANS, brass, 0.36)
    s.crash(64, 0.4)
    return s.render()


def brontes_2():
    """music.brontes_2: 96 bpm. Doppelschlag auf dem Amboss, Blech-Tutti, das Motiv verkürzt über dem vollen."""
    s = Score(96, 96)
    chords = BRON_CH * 6
    s.pad(0, chords, 4, gain=0.15, bright=0.9)
    s.bass(0, chords, 4, gain=0.32)
    s.ostinato(0, chords, 4, step=0.5, gain=0.26, pattern=(0, 0, 0, 2, 0, 0, 1, 0))
    for beat in range(96):
        s.mix.add(s.at(beat), anvil(780.0), 0.3 if beat % 2 == 0 else 0.2, 0.25)
        s.mix.add(s.at(beat + 0.25), anvil(870.0, 1.2, 72), 0.16, 0.25)
    s.drums(0, 24, [(0, low, 1.0), (0.75, small, 0.3), (1.5, mid, 0.45), (2, low, 0.8), (3, mid, 0.55), (3.5, small, 0.4)])
    s.hit(0, CMIN, gain=0.32)
    for start in (16, 48):
        s.melody(start, BRON, brass, 0.36)
        s.melody(start, BRON, brass, 0.2, octave=1)
        s.melody(start + 8, BRON_ANS, brass, 0.36)
        s.melody(start + 16, dimin(BRON) + [(b + 4, n, d) for b, n, d in dimin(BRON_ANS)], brass, 0.3, octave=1)
        s.melody(start + 24, BRON_ANS, brass, 0.36)
        s.crash(start, 0.45)
    s.choir_pad(48, BRON_CH * 8, 4, octave=3, gain=0.12)
    for beat in range(0, 96, 8):
        bellows(s, beat + 6, 2.0, 0.1)
    s.hit(80, AB, gain=0.28)
    s.hit(88, GMAJ, gain=0.3)
    return s.render()


# ---- Kratos (a-Moll): Marsch, Befehl aus zwei Tönen --------------------------------------------------------------

CMD = [(0, 'E4', 1.5), (1.5, 'A3', 2.5)]
KRA = CMD + [(4, 'C4', 1), (5, 'B3', 1), (6, 'A3', .5), (6.5, 'G#3', .5), (7, 'A3', 1),
             (8, 'E4', 1.5), (9.5, 'A3', 1.5), (11, 'D4', 1), (12, 'C4', 1), (13, 'B3', 1), (14, 'E4', 2)]
KRA_CH = [AM, EMAJ, AM, FMAJ, DMIN, AM, FMAJ, EMAJ]
MARCH = [(0, low, 0.8), (0, snare, 0.25), (1, snare, 0.4), (1.75, snare, 0.2), (2, low, 0.55), (2, snare, 0.28),
         (3, snare, 0.42), (3.5, snare, 0.22), (3.75, snare, 0.26)]


def kratos():
    """music.kratos: 112 bpm, Marsch. Der Befehl (Quarte abwärts) im Blech, Chor antwortet, kleine Trommel."""
    s = Score(112, 112)
    s.drums(0, 28, MARCH)
    for beat in range(0, 16, 4):
        s.melody(beat, CMD, brass, 0.3 if beat % 8 == 0 else 0.22, octave=-1 if beat % 8 else 0)
    s.bass(0, [AM] * 4, 4, gain=0.26)
    s.pad(16, KRA_CH * 3, 4, gain=0.13, bright=0.8)
    s.bass(16, KRA_CH * 3, 4, gain=0.3)
    s.ostinato(16, KRA_CH * 3, 4, step=1.0, gain=0.3, pattern=(0, 2, 0, 2))
    s.melody(16, KRA, horn, 0.3)
    s.melody(32, KRA, horn, 0.3)
    s.melody(48, CMD, brass, 0.34)
    s.melody(52, CMD, choir_a, 0.22)
    s.melody(56, CMD, brass, 0.34, octave=-1)
    s.melody(60, CMD, choir_o, 0.22, octave=-1)
    s.choir_pad(48, [AM, EMAJ, AM, EMAJ], 4, gain=0.12)
    s.melody(64, KRA, brass, 0.34)
    s.melody(64, KRA, brass, 0.2, octave=-1)
    s.melody(80, KRA, brass, 0.34)
    s.melody(80, KRA, choir_a, 0.16)
    s.pad(64, KRA_CH * 3, 4, gain=0.15, bright=1.1)
    s.choir_pad(96, [AM, FMAJ, EMAJ, EMAJ], 4, gain=0.12)
    s.melody(96, CMD + [(4, 'E4', 1.5), (5.5, 'A3', 2.5), (8, 'F4', 1.5), (9.5, 'B3', 2.5), (12, 'E4', 4)], brass, 0.34)
    s.crash(64, 0.4)
    s.crash(96, 0.4)
    return s.render()


def kratos_2():
    """music.kratos_2: 132 bpm, ohne Maß: 7/8 und 5/8 im Wechsel, das Schlagwerk führt, der Befehl zerfällt."""
    s = Score(132, 132)
    cycle = [(0, low, 1.0), (0, snare, 0.3), (0.5, snare, 0.18), (1, snare, 0.2), (1.5, mid, 0.55), (1.5, snare, 0.32),
             (2, snare, 0.18), (2.5, low, 0.7), (2.5, snare, 0.3), (3, snare, 0.2),
             (3.5, deep, 0.9), (3.5, snare, 0.34), (4, small, 0.3), (4.5, snare, 0.2), (5, mid, 0.6), (5, snare, 0.3),
             (5.5, small, 0.36)]
    s.drums(0, 22, cycle, 1.15, beats_per_bar=6)
    accents = (0, 1.5, 2.5, 3.5, 5)
    chords = [AM, AM, FMAJ, EMAJ] * 6
    for c in range(22):
        chord = chords[c]
        for a in accents:
            name = chord[0] if a in (0, 3.5) else chord[2]
            s.mix.add(s.at(c * 6 + a), staccato(hz(f'{name}2' if name in ('A', 'F', 'E') else f'{name}3'), 0.22), 0.32, 0.1)
        s.pad(c * 6, [chord], 6, gain=0.12, bright=0.9)
        s.bass(c * 6, [chord], 6, gain=0.3)
    for c in range(0, 22, 2):
        s.melody(c * 6, CMD, brass, 0.34, octave=-1 if c % 4 else 0)
    for c in range(3, 22, 4):
        s.melody(c * 6 + 2.5, [(0, 'E4', .5), (.5, 'A3', 1)], brass, 0.26)
    s.choir_pad(66, chords[11:19], 6, gain=0.12)
    s.melody(66, KRA, choir_a, 0.18)
    s.melody(96, KRA, brass, 0.3)
    s.crash(0, 0.4)
    s.crash(66, 0.45)
    s.rise(132, gain=0.35)
    return s.render()


# ---- Zeus (B-Dur): Majestät, Sturm, alles zusammen ---------------------------------------------------------------

ZEUS_A = [(0, 'Bb3', 1), (1, 'F4', 1), (2, 'Bb4', 1.5), (3.5, 'C5', .5), (4, 'D5', 2), (6, 'C5', 1), (7, 'Bb4', 1),
          (8, 'A4', 1.5), (9.5, 'Bb4', .5), (10, 'C5', 1), (11, 'F4', 1), (12, 'Bb4', 4)]
ZEUS_M = [(0, 'G3', 1), (1, 'D4', 1), (2, 'G4', 1.5), (3.5, 'A4', .5), (4, 'Bb4', 2), (6, 'A4', 1), (7, 'G4', 1),
          (8, 'F#4', 1.5), (9.5, 'G4', .5), (10, 'A4', 1), (11, 'D4', 1), (12, 'G4', 4)]
BBMAJ = BB
ZEUS_CH = [BBMAJ, GM, FMAJ, BBMAJ]
ZEUS_M_CH = [GM, EBMAJ, DMAJ, GM]


def timp_roll(s, beat, beats, pitch, gain=0.3):
    """Paukenwirbel: schnelle, anschwellende Schläge."""
    count = int(beats * 6)
    for i in range(count):
        s.mix.add(s.at(beat + i / 6.0), timpani(pitch, 0.6, 80 + i % 5), gain * (0.4 + 0.6 * i / count), 0.25)


def zeus():
    """music.zeus: 90 bpm, Majestät. Steigende Fanfare in B-Dur, Pauken, Chor in der Mitte."""
    s = Score(90, 96)
    timp_roll(s, 0, 3.5, hz('Bb1'), 0.3)
    s.hit(4, BBMAJ, gain=0.3)
    s.choir_pad(0, [BBMAJ, BBMAJ, FMAJ, FMAJ], 4, gain=0.12, vowel='o')
    s.pad(16, ZEUS_CH * 5, 4, gain=0.15, bright=1.2)
    s.bass(0, ZEUS_CH * 6, 4, gain=0.3)
    s.ostinato(16, ZEUS_CH * 5, 4, step=0.5, gain=0.22, pattern=(0, 0, 1, 0, 2, 0, 1, 0))
    s.melody(16, ZEUS_A, horn, 0.32)
    s.melody(32, ZEUS_A, brass, 0.34)
    s.melody(32, ZEUS_A, brass, 0.18, octave=1)
    b_ch = [EBMAJ, CMIN, FMAJ, FMAJ]
    s.choir_pad(48, b_ch, 4, gain=0.16)
    s.melody(48, [(0, 'G4', 2), (2, 'Bb4', 2), (4, 'C5', 3), (7, 'D5', 1), (8, 'Eb5', 2), (10, 'D5', 1), (11, 'C5', 1), (12, 'F5', 4)], choir_a, 0.24)
    s.melody(64, ZEUS_A, brass, 0.36)
    s.melody(64, ZEUS_A, choir_a, 0.18)
    s.melody(80, ZEUS_A, brass, 0.36)
    s.melody(80, ZEUS_A, brass, 0.22, octave=1)
    for beat in range(16, 96, 4):
        s.mix.add(s.at(beat), timpani(hz('Bb1') if (beat // 4) % 2 == 0 else hz('F2')), 0.34, 0.3)
    s.drums(64, 8, [(0, low, 0.7), (2, mid, 0.35), (3, low, 0.45)])
    s.crash(16, 0.35)
    s.crash(64, 0.5)
    s.rise(96, gain=0.35)
    return s.render()


def zeus_2():
    """music.zeus_2: 120 bpm, Sturm. Das Motiv in g-Moll über Streichertremolo, Paukenwirbel, Donnerschläge."""
    s = Score(120, 128)
    chords = ZEUS_M_CH * 8
    s.ostinato(0, chords, 4, step=0.25, gain=0.17, pattern=(0, 0, 0, 0))
    s.ostinato(0, chords, 4, step=0.5, octave=3, gain=0.14, pattern=(0, 1, 2, 1))
    s.pad(0, chords, 4, gain=0.13, bright=1.0)
    s.bass(0, chords, 4, gain=0.32)
    s.drums(0, 32, [(0, low, 1.0), (0.5, small, 0.3), (1, mid, 0.55), (1.5, small, 0.35), (2, low, 0.85), (2.5, mid, 0.4),
                    (3, mid, 0.6), (3.5, small, 0.45), (3.75, small, 0.4)])
    for beat in (0, 32, 64, 96):
        timp_roll(s, beat + 12, 4, hz('G1'), 0.32)
        s.crash(beat + 16, 0.5)
        s.mix.add(s.at(beat + 16), taiko(38.0, 1.8, 31), 0.8, 0.35)
    s.melody(16, ZEUS_M, brass, 0.36)
    s.melody(32, ZEUS_M, brass, 0.36, octave=-1)
    s.melody(32, ZEUS_M, horn, 0.2)
    s.choir_pad(48, ZEUS_M_CH * 2, 4, gain=0.14)
    s.melody(48, dimin(ZEUS_M) + [(b + 8, n, d) for b, n, d in dimin(ZEUS_M)], brass, 0.3, octave=0)
    s.melody(64, ZEUS_M, brass, 0.38)
    s.melody(64, ZEUS_M, brass, 0.22, octave=1)
    s.melody(80, ZEUS_M, choir_a, 0.22, octave=1)
    s.melody(80, ZEUS_M, brass, 0.36)
    s.melody(96, ZEUS_A, brass, 0.3, octave=-1)
    s.melody(112, ZEUS_M, brass, 0.38)
    s.rise(128, gain=0.4)
    return s.render()


def zeus_3():
    """music.zeus_3: 135 bpm, alles zusammen. Moll-Sturm und Dur-Fanfare im Wechsel, Chor trägt das Motiv."""
    s = Score(135, 144)
    s.hit(0, GM, gain=0.32)
    s.hit(2, GM, gain=0.3)
    s.hit(3, EBMAJ, gain=0.3)
    storm = ZEUS_M_CH * 2
    major = ZEUS_CH * 2

    def section(start, chords, melody, choir_gain=0.16):
        s.ostinato(start, chords, 4, step=0.25, gain=0.16, pattern=(0, 0, 0, 0))
        s.ostinato(start, chords, 4, step=0.5, octave=3, gain=0.14, pattern=(0, 1, 2, 1))
        s.pad(start, chords, 4, gain=0.15, bright=1.3)
        s.choir_pad(start, chords, 4, gain=0.12)
        s.bass(start, chords, 4, gain=0.32)
        s.melody(start, melody, brass, 0.36)
        s.melody(start, melody, choir_a, choir_gain, octave=0)
        s.melody(start + 16, melody, brass, 0.36)
        s.melody(start + 16, melody, brass, 0.2, octave=1)
        s.crash(start, 0.5)

    section(16, storm, ZEUS_M)
    section(48, major, ZEUS_A, 0.2)
    s.ostinato(80, storm, 4, step=0.25, gain=0.18, pattern=(0, 0, 0, 0))
    s.pad(80, storm, 4, gain=0.15, bright=1.2)
    s.bass(80, storm, 4, gain=0.34)
    s.choir_pad(80, storm, 4, gain=0.14, vowel='o')
    for k in range(4):
        s.melody(80 + k * 8, dimin(ZEUS_M if k % 2 == 0 else ZEUS_A), brass, 0.32, octave=k % 2)
    section(112, major, ZEUS_A, 0.24)
    s.ostinato(0, [GM, GM, EBMAJ, DMAJ], 4, step=0.5, gain=0.26)
    s.drums(0, 36, [(0, low, 1.0), (0.5, small, 0.32), (1, mid, 0.6), (1.5, small, 0.4), (2, low, 0.9), (2.5, mid, 0.45),
                    (3, mid, 0.65), (3.25, small, 0.35), (3.5, small, 0.45), (3.75, small, 0.5)], 1.15)
    s.drums(16, 32, SHAKE_PATTERN, 1.1)
    for beat in (16, 48, 80, 112):
        s.mix.add(s.at(beat), taiko(38.0, 1.8, 31), 0.8, 0.35)
    s.rise(16, gain=0.5)
    s.rise(144, gain=0.45)
    return s.render()


# ---- Hades, späte Phasen: dasselbe Thema wie music.hades, schneller und dichter -----------------------------------

H_THRONE = [DM, BB, CMIN, DM, EB, BB, A, DM]
H_DESCENT = [(0, 'D4', 2), (2, 'C4', 2), (4, 'Bb3', 2), (6, 'A3', 2)]
H_REPLY = [(0, 'F4', 1.5), (1.5, 'E4', .5), (2, 'D4', 2), (4, 'Eb4', 2), (6, 'D4', 2)]
H_HIGH = [(0, 'A5', 3), (3, 'Bb5', 1), (4, 'A5', 2), (6, 'F5', 2), (8, 'E5', 3), (11, 'F5', 1), (12, 'D5', 4)]


def hades_2():
    """music.hades_2: 120 bpm. Der Abstieg des Hades doppelt so dicht, Sechzehntel im Bass, Klage im Chor."""
    s = Score(120, 120)
    chords = (H_THRONE * 4)[:30]
    for beat in range(0, 120, 8):
        s.mix.add(s.at(beat), bell(hz('D2'), 6.0), 0.3, 0.6)
    s.hit(0, DM, gain=0.3)
    s.bass(0, chords, 4, gain=0.34)
    s.ostinato(0, chords, 4, step=0.25, gain=0.17, pattern=(0, 0, 0, 0))
    s.ostinato(8, chords[2:], 4, step=0.5, gain=0.24, pattern=(0, 0, 2, 0, 0, 1, 2, 0))
    s.pad(0, chords, 4, octave=2, gain=0.15, bright=0.7)
    s.choir_pad(0, chords, 4, octave=2, gain=0.18, vowel='o')
    march = [(0, low, 1.0), (0.5, small, 0.3), (1, mid, 0.5), (2, low, 0.8), (2.5, small, 0.35), (3, mid, 0.6), (3.5, small, 0.45)]
    s.drums(0, 30, march, 1.15)
    for start in (8, 24):
        s.melody(start, H_DESCENT, brass, 0.38)
        s.melody(start, H_DESCENT, brass, 0.3, octave=-1)
        s.melody(start + 8, H_REPLY, brass, 0.36)
    s.choir_pad(56, chords[14:22], 4, octave=3, gain=0.16)
    s.melody(56, H_HIGH, choir_a, 0.26)
    s.melody(56, H_DESCENT + [(b + 8, n, d) for b, n, d in H_DESCENT], brass, 0.3, octave=-1)
    s.melody(72, H_HIGH, choir_a, 0.26)
    s.melody(72, H_HIGH, brass, 0.2, octave=-1)
    s.crash(56, 0.45)
    s.rise(88, gain=0.5)
    for start in (88, 104):
        s.melody(start, dimin(H_DESCENT) + [(b + 4, n, d) for b, n, d in dimin(H_REPLY)], brass, 0.36)
        s.melody(start, H_DESCENT, brass, 0.32, octave=-1)
        s.melody(start + 8, H_REPLY, brass, 0.36)
        s.melody(start + 8, H_REPLY, choir_a, 0.16, octave=1)
    s.crash(88, 0.55)
    s.crash(104, 0.45)
    return s.render()


# ---- Kronos (f-Moll): Uhrwerk ------------------------------------------------------------------------------------

CLOCK = ['F3', 'C4', 'Ab3', 'C4', 'Db4', 'C4']
KRO = [(0, 'F4', 2), (2, 'G4', 1), (3, 'Ab4', 1), (4, 'C5', 3), (7, 'Bb4', 1), (8, 'Ab4', 2), (10, 'G4', 2),
       (12, 'E4', 2), (14, 'F4', 2)]
KRO_CH = [FMIN, DB, BBM, CMAJ]


def clockwork(s, start, beats, step, octave=0, gain=0.2, backwards=False, pan=0.0):
    """Ein Sechs-Ton-Kreis gegen den Viervierteltakt: die Zahnräder der Uhr; rückwärts für die zweite Fassung."""
    pattern = CLOCK[::-1] if backwards else CLOCK
    for i in range(int(beats / step)):
        f = hz(pattern[i % len(pattern)]) * 2.0 ** octave
        s.mix.add(s.at(start + i * step), pizz(f, 0.45), gain * (1.0 if i % 6 == 0 else 0.75), 0.25, pan)


def ticks(s, start, beats, step=1.0, gain=0.22):
    for i in range(int(beats / step)):
        s.mix.add(s.at(start + i * step), woodblock(1250.0 if i % 2 == 0 else 940.0), gain, 0.2)


def kronos():
    """music.kronos: 60 bpm, langsam. Ein Ostinato wie ein Uhrwerk, das Motiv in den Hörnern, tiefe Glocke."""
    s = Score(60, 64)
    ticks(s, 0, 64)
    clockwork(s, 0, 64, 0.5, gain=0.2)
    s.pad(0, KRO_CH * 4, 4, octave=2, gain=0.13, bright=0.6)
    s.bass(0, KRO_CH * 4, 4, gain=0.28)
    s.choir_pad(32, KRO_CH * 2, 4, octave=3, gain=0.1, vowel='u')
    s.melody(16, KRO, horn, 0.28)
    s.melody(32, KRO, horn, 0.28, octave=-1)
    s.melody(48, KRO, strings_v, 0.2)
    s.melody(48, KRO, choir_o, 0.14, octave=-1)
    for beat in range(0, 64, 8):
        s.mix.add(s.at(beat), bell(hz('F2'), 6.0), 0.2, 0.6)
        s.mix.add(s.at(beat), timpani(hz('F1') if beat % 16 == 0 else hz('C2'), 2.4), 0.28, 0.3)
    return s.render()


def kronos_2():
    """music.kronos_2: 90 bpm, drängend. Das Uhrwerk vorwärts und rückwärts zugleich, das Motiv gegen seinen Krebs."""
    s = Score(90, 96)
    ticks(s, 0, 96, 0.5, 0.18)
    clockwork(s, 0, 96, 0.25, gain=0.16, pan=-0.35)
    clockwork(s, 0, 96, 0.25, octave=1, gain=0.12, backwards=True, pan=0.35)
    chords = KRO_CH * 6
    s.pad(0, chords, 4, gain=0.14, bright=0.9)
    s.bass(0, chords, 4, gain=0.3)
    s.ostinato(16, chords[4:], 4, step=0.5, gain=0.22, pattern=(0, 0, 2, 0, 1, 0, 2, 0))
    s.drums(16, 20, [(0, low, 0.9), (1, mid, 0.4), (1.5, small, 0.3), (2, low, 0.7), (3, mid, 0.5), (3.5, small, 0.4)])
    for beat in range(0, 96, 4):
        # Rückwärts geschichtete Glocke: schwillt in jede Eins hinein.
        s.mix.add(s.at(beat) - 2.5, reverse(bell(hz('F3'), 2.5)), 0.14, 0.4)
    for start in (16, 48):
        s.melody(start, KRO, brass, 0.32)
        s.melody(start, retro(KRO, 16), horn, 0.22, octave=-1)
        s.melody(start + 16, KRO, brass, 0.34, octave=-1)
        s.melody(start + 16, retro(KRO, 16), choir_a, 0.18, octave=0)
        s.crash(start, 0.4)
    s.melody(80, dimin(KRO) + [(b + 8, n, d) for b, n, d in dimin(retro(KRO, 16))], brass, 0.32)
    s.choir_pad(80, KRO_CH, 4, gain=0.14)
    s.rise(96, gain=0.45)
    return s.render()


# ---- Tartaros-Wächter (e-Moll): ein gemeinsames Thema ------------------------------------------------------------

GUARD = [(0, 'E4', 1.5), (1.5, 'G4', .5), (2, 'F#4', 1), (3, 'B3', 1), (4, 'E4', 2), (6, 'D4', 1), (7, 'B3', 1),
         (8, 'C4', 1.5), (9.5, 'B3', .5), (10, 'A3', 1), (11, 'F#3', 1), (12, 'B3', 4)]
GUARD_CH = [EM, EM, AM, BMAJ]


def guardian():
    """music.guardian: 80 bpm, karg. Tiefe Streicher, das Thema im Horn, Trommel nur auf jeder zweiten Zählzeit."""
    s = Score(80, 64)
    s.pad(0, GUARD_CH * 4, 4, octave=2, gain=0.13, bright=0.5)
    s.bass(0, GUARD_CH * 4, 4, gain=0.28)
    s.drums(0, 16, [(0, deep, 0.8), (2, low, 0.4)])
    s.melody(16, GUARD, horn, 0.3)
    s.melody(32, GUARD, horn, 0.28, octave=-1)
    s.melody(32, GUARD, choir_u, 0.12)
    s.melody(48, GUARD, strings_v, 0.2)
    for beat in range(0, 64, 16):
        s.mix.add(s.at(beat), bell(hz('E2'), 6.0), 0.18, 0.6)
        s.mix.add(s.at(beat + 9), chains(1.4, 131 + beat, 14), 0.14, 0.7, 0.5)
    return s.render()


def guardian_2():
    """music.guardian_2: 120 bpm, getrieben. Ostinato, Blech, das Thema verkürzt gegen das volle."""
    s = Score(120, 96)
    chords = GUARD_CH * 6
    s.pad(0, chords, 4, gain=0.14, bright=0.9)
    s.bass(0, chords, 4, gain=0.32)
    s.ostinato(0, chords, 4, step=0.5, gain=0.26, pattern=(0, 0, 1, 0, 0, 2, 0, 1))
    s.drums(0, 24, DRIVE_PATTERN, 1.1)
    s.drums(16, 16, SHAKE_PATTERN, 0.9)
    s.hit(0, EM, gain=0.3)
    for start in (16, 48):
        s.melody(start, GUARD, brass, 0.36)
        s.melody(start + 16, dimin(GUARD) + [(b + 8, n, d) for b, n, d in dimin(GUARD)], brass, 0.3, octave=1)
        s.melody(start + 16, GUARD, brass, 0.3, octave=-1)
        s.crash(start, 0.45)
    s.choir_pad(48, GUARD_CH * 4, 4, gain=0.12, vowel='o')
    s.melody(80, GUARD, brass, 0.38)
    s.melody(80, GUARD, choir_a, 0.18)
    s.rise(96, gain=0.4)
    return s.render()


# ---- Aufruf ------------------------------------------------------------------------------------------------------

LEGACY = {'messenger': messenger, 'thief': thief, 'guide': guide, 'finale': finale, 'shade': shade, 'hades': hades}

# Neue Stücke der Überarbeitung 3: Name = Dateiname = ID ohne "music.". (Funktion, Schleife?)
THEMES = {
    'tunnel': (tunnel, True), 'olympus': (olympus, True), 'tartaros': (tartaros, True),
    'hekate': (hekate, True), 'hekate_2': (hekate_2, True), 'brontes': (brontes, True), 'brontes_2': (brontes_2, True),
    'kratos': (kratos, True), 'kratos_2': (kratos_2, True),
    'zeus': (zeus, True), 'zeus_2': (zeus_2, True), 'zeus_3': (zeus_3, True), 'hades_2': (hades_2, True),
    'kronos': (kronos, True), 'kronos_2': (kronos_2, True), 'guardian': (guardian, True), 'guardian_2': (guardian_2, True),
    'victory_olymp': (victory_olymp, False), 'victory_tartaros': (victory_tartaros, False), 'credits': (credits, False),
}

MAX_LOOP_BYTES = 800 * 1024
BYTES_PER_SECOND = 9800      # ≈ 78 kbit/s Obergrenze; tatsächlich liegen die Stücke meist darunter (Summe im Bericht)
STINGER_BYTES_PER_SECOND = 16000   # kurze Stinger (≤ 20 s) dürfen besser klingen, sie kosten kaum Platz


def main():
    """python boss_music.py [--only id,id] [--spectrogram ordner]   (ohne --only: alle neuen Stücke; die fünf
    Bestandsstücke messenger/thief/guide/finale/hades nur, wenn man sie ausdrücklich nennt)."""
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
    names = [n[len('music.'):] if n.startswith('music.') else n for n in names] or list(THEMES)
    for name in names:
        if name in LEGACY:
            data = LEGACY[name]()
            encode(os.path.join(OUT, f'{name}.ogg'), data, quality=3)
            print(f'Bestand {name}: {len(data) / 44100.0:.1f} s')
            continue
        make, loop = THEMES[name]
        data = make()
        path = os.path.join(OUT, f'{name}.ogg')
        rate = BYTES_PER_SECOND if len(data) > 20 * 44100 else STINGER_BYTES_PER_SECOND
        budget = min(MAX_LOOP_BYTES, int(len(data) / 44100.0 * rate))
        quality, size = encode_budget(path, data, budget)
        c = check(data)
        flag = '' if size <= budget else '  ÜBER BUDGET'
        print(f'music.{name}: {c["seconds"]:.1f} s, Spitze {c["peak"]:.3f}, RMS {c["rms"]:.3f}, '
              f'Clipping {c["clipped"] * 100:.3f} %, Naht {c["seam"]:.2f}, q{quality}, {size / 1024:.0f} kB{flag}')
        if spec:
            spectrogram_png(os.path.join(spec, f'music_{name}.png'), data)


if __name__ == '__main__':
    main()
