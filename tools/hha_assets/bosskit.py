"""Gemeinsamer Werkzeugkasten aller Boss-Modelle: Paletten, Maler (plated, aged_bronze, bone, cloth, ember …) und
Formhelfer (curved, small_skull). Früher lag das in hades_model.py; jetzt teilen es alle Modelle, damit jedes
Modell seine eigene Datei frei umbauen kann, ohne die anderen zu brechen.

Nicht umbauen oder umbenennen, ohne alle Modell-Dateien anzupassen; neue Helfer für ein einzelnes Modell gehören in
dessen eigene Datei.
"""
import math

from modelkit import Cube as _Cube, Model, Part, hexc, mix, ramp, rgb, speckle


def Cube(x, y, z, w, h, d, paint, dilate=0.0):
    """Der Atlas rechnet in ganzen Texeln, also sind die Maße ganzzahlig. Die Position darf halbe Einheiten haben."""
    return _Cube(x, y, z, max(1, round(w)), max(1, round(h)), max(1, round(d)), paint, dilate)


OBSIDIAN = rgb('#090a12', '#141624', '#20243a', '#323854', '#4f587c', '#8089ad')
IRON = rgb('#0c0d13', '#191b24', '#2a2d39', '#3f4350', '#60656f')
SHROUD = rgb('#040406', '#0a0b11', '#13141d', '#1e202c', '#2d3040')
LINING = rgb('#02100a', '#052318', '#0a3a28', '#10583c', '#1b7c54')
BRONZE = rgb('#1e120a', '#39240f', '#5c4019', '#7f6227', '#a78a44', '#d4bd7c')
PATINA = rgb('#10362c', '#1c5c4a', '#2f8a6e', '#58b796')
BONE = rgb('#2c2823', '#4d473d', '#7a7365', '#a39c8a', '#c8c1ac', '#e6e0cd')
ASHEN = rgb('#2a2f33', '#454c50', '#6b7374', '#969e9b', '#c0c7c1')
GREEN = (120, 255, 140)
FACE_LIGHT = {'top': 0.12, 'bottom': -0.2, 'front': 0.0, 'back': -0.08, 'left': -0.05, 'right': -0.05}


def rune(strength=230):
    """Leuchtende Einlage: hell in der Farbkarte, grün in der Leuchtmaske."""
    return (hexc('#d4ffd8'), (GREEN[0], GREEN[1], GREEN[2], strength))


def ember(t, strength=255):
    """Seelenglut von tiefgrün (0) bis fast weiß (1)."""
    color = ramp(rgb('#0a5a26', '#1fbf4a', '#6cff86', '#e8ffe6'), t)
    return (color, (GREEN[0], GREEN[1], GREEN[2], strength))


def plated(material, base=0.42, seed=0, edge=0.2, rivets=False, grad=0.14):
    """Geschmiedete Platte: heller Grat an den Kanten, dunkle Mitte, feine Kratzer und auf Wunsch Nieten."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad
        v += (speckle(x, y, seed) - 0.5) * 0.12
        if x == 0 or y == 0 or x == w - 1 or y == h - 1:
            v += edge
        elif x == 1 or y == 1 or x == w - 2 or y == h - 2:
            v -= edge * 0.25
        if (x * 11 + y * 7 + seed) % 37 == 0:
            v += 0.12
        if rivets and face in ('front', 'back', 'left', 'right') and w >= 5 and h >= 5 and x in (1, w - 2) and y in (1, h - 2):
            return ramp(BRONZE, 0.86)
        return ramp(material, v)
    return paint


def aged_bronze(base=0.6, seed=0):
    """Alte Bronze mit Grünspan in den Vertiefungen."""
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.2 + (speckle(x, y, seed) - 0.5) * 0.14
        if x == 0 or y == 0 or x == w - 1 or y == h - 1:
            v += 0.16
        if speckle(x + 9, y + 3, seed + 3) > 0.74:
            return ramp(PATINA, v)
        return ramp(BRONZE, v)
    return paint


def bone(base=0.62, seed=0):
    def paint(face, x, y, w, h):
        v = base + FACE_LIGHT[face] * 1.2 + (0.5 - y / max(h - 1, 1)) * 0.22 + (speckle(x, y, seed) - 0.5) * 0.14
        if (x * 5 + y * 13 + seed) % 29 == 0:
            v -= 0.25
        return ramp(BONE, v)
    return paint


def cloth(material=SHROUD, base=0.5, seed=0):
    def paint(face, x, y, w, h):
        fold = math.sin(x * 1.25 + seed) * 0.08
        return ramp(material, base + FACE_LIGHT[face] + fold + (speckle(x, y, seed) - 0.5) * 0.1 + (0.5 - y / max(h - 1, 1)) * 0.1)
    return paint


def glow_paint(t=0.6, strength=255):
    def paint(face, x, y, w, h):
        return ember(t + (speckle(x, y, 3) - 0.5) * 0.3, strength)
    return paint


def skull_face(face, x, y, w, h):
    """Kleiner Schädel: zwei Höhlen und Zahnreihe, passt auf jeden Würfel ab 5 x 5."""
    base = bone(0.55, 40)
    if face != 'front':
        return base(face, x, y, w, h)
    cx = (w - 1) / 2.0
    row = y / max(h - 1, 1)
    if 0.3 < row < 0.58 and 0.45 < abs(x - cx) / max(cx, 1) * 1.0 + 0.2 < 1.0 and abs(x - cx) >= 1:
        return rune(235) if abs(x - cx) < 1.6 and 0.36 < row < 0.5 else hexc('#07070a')
    if 0.56 < row < 0.72 and abs(x - cx) < 1:
        return hexc('#0e0e12')
    if row > 0.8:
        return hexc('#141417') if x % 2 == 0 else ramp(BONE, 0.82)
    return base(face, x, y, w, h)


def small_skull(pivot, size=6, rot=(0, 0, 0), name='skull'):
    half = size // 2
    return Part(name, pivot, rot, cubes=[
        Cube(-half, -size + 1, -half, size, size - 1, size, skull_face),
        Cube(-half + 1, 0, -half + 1, size - 2, 2, size - 2, bone(0.5, 41)),
    ])


def curved(name, pivot, rot, segments, paint, tip_paint=None):
    """Gebogenes Horn oder Stachel: Jedes Glied sitzt am Ende des vorigen und dreht ein Stück weiter.

    segments ist eine Liste aus (Breite, Länge, Tiefe, Biegung um x, Biegung um z).
    Wächst entlang -y, also nach oben.
    """
    root = None
    current = None
    for i, (w, l, d, bx, bz) in enumerate(segments):
        l = max(1, round(l))
        last = i == len(segments) - 1
        painter = tip_paint if (last and tip_paint) else paint
        cube = Cube(-w / 2.0, -l, -d / 2.0, w, l, d, painter)
        if current is None:
            part = Part(name, pivot, rot, cubes=[cube])
            root = part
        else:
            part = Part(f'{name}_{i}', (0, -previous, 0), (bx, 0, bz), cubes=[cube])
            current.add(part)
        current = part
        previous = l
    return root


__all__ = [n for n in dir() if not n.startswith("_")]
