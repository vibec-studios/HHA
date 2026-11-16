"""Gemeinsame Helfer der Wächter-Modelle (Leuchtfarben, Schattierung, Ketten, ausgefranste Säume).

Jeder Wächter hat seine eigene Datei (warden_model.py, sisyphus_model.py, ixion_model.py, tantalus_model.py).
Koordinaten wie überall: 1/16 Block, +y nach unten, -z ist vorn, die Füße stehen bei y = 24. Die Größe im Spiel kommt
aus dem SCALE-Attribut der Wesen. Die Renderer stehen in client/model/GuardianModels.kt.
"""
import math

from bosskit import BONE, BRONZE, IRON, Cube, aged_bronze, curved, plated
from modelkit import Model, Part, hexc, mix, ramp, rgb, speckle

FACE_LIGHT = {'top': 0.14, 'bottom': -0.22, 'front': 0.0, 'back': -0.08, 'left': -0.05, 'right': -0.05}


def glow(colors, tint):
    """Leuchtfarbe: Verlauf in der Farbkarte, feste Farbe in der Leuchtmaske."""
    def make(t, strength=255):
        return (ramp(colors, t), (tint[0], tint[1], tint[2], strength))
    return make


VIOLET = glow(rgb('#2a0a4a', '#6a2ab4', '#b48aff', '#f2e8ff'), (190, 150, 255))
FIRE = glow(rgb('#5a1404', '#c24a0c', '#ff9a2a', '#fff0b4'), (255, 140, 50))
TIDE = glow(rgb('#03263a', '#0a6a8a', '#46d6f0', '#e0fbff'), (90, 220, 255))


def shaded(material, base=0.5, seed=0, grad=0.16, noise=0.18):
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad + (speckle(x, y, seed) - 0.5) * noise
        return ramp(material, v)
    return paint


def chain_paint(face, x, y, w, h):
    v = 0.42 + FACE_LIGHT[face] + (0.32 if y % 3 == 0 else 0.0) - (0.15 if x == 0 else 0.0)
    return ramp(rgb('#0e0d0c', '#24211e', '#433d36', '#6c6458', '#9a9284'), v)


def tattered(paint, depth=3, seed=0):
    """Ausgefranster Saum: Die unteren Reihen haben Löcher und Zacken."""
    def wrapped(face, x, y, w, h):
        if face in ('front', 'back', 'left', 'right') and y >= h - depth:
            cut = speckle(x, 0, seed) * depth
            if y >= h - cut:
                return None
        return paint(face, x, y, w, h)
    return wrapped


def chain(name, pivot, rot, links, end=None):
    cubes = []
    for i in range(links):
        cubes.append(Cube(-0.5, i * 4, -0.5, 1, 3, 1, chain_paint))
        cubes.append(Cube(-1, i * 4 + 3, -0.5, 2, 1, 1, chain_paint))
    return Part(name, pivot, rot, cubes=cubes, children=[end] if end else [])


__all__ = [n for n in dir() if not n.startswith("_")]
