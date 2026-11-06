"""Die vier Kampf-Themen von Hermes als orchestrale Schleifen. Aufruf: python boss_music.py [messenger thief guide finale]

Alle vier teilen dasselbe Leitmotiv in D-Moll (aufsteigender Dreiklang, fallende Antwort): als Fanfare beim Boten,
chromatisch verzerrt beim Dieb, zeitgedehnt im Blech beim Seelenführer und als volles Tutti beim Götterboten.
Die Stücke laufen in einem kreisförmigen Puffer, Nachhall und Ausklänge schließen also nahtlos an den Anfang an.
"""
import os
import sys

import numpy as np

from synth import (
    Mix, bell, brass, choir, cymbal, encode, harp, horn, hz, pizz, reed, rim, shaker, staccato, strings, swell, taiko, tom,
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

    def __init__(self, bpm, beats):
        self.beat = 60.0 / bpm
        self.mix = Mix(beats * self.beat)
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

    def render(self):
        return self.mix.render()


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


THEMES = {'messenger': messenger, 'thief': thief, 'guide': guide, 'finale': finale, 'shade': shade}


def main():
    names = sys.argv[1:] or list(THEMES)
    for name in names:
        data = THEMES[name]()
        encode(os.path.join(OUT, f'{name}.ogg'), data, quality=3)
        rms = float(np.sqrt(np.mean(data ** 2)))
        print(f'Thema {name}: {len(data) / 44100.0:.1f} s, RMS {rms:.3f}')


if __name__ == '__main__':
    main()
