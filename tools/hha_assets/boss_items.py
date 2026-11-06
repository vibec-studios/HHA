"""Item-Sprites des Bossfights (16x16, über mctex): versiegelter Brief und Botenfeder."""
from texkit import Image, flush, hexc, save

PAPER = ['#8a7458', '#b39d7c', '#d4c19f', '#e9dcc0', '#f6eedb', '#fffaf0']
GOLD = ['#7c4210', '#b06a16', '#f2c13c', '#ffe27a', '#fff4c2']
WAX = ['#4a0e16', '#8a1c26', '#c8323a', '#f06a5a']
WHITE = ['#8d88a0', '#c9c5d4', '#efecf2', '#ffffff']


def letter():
    img = Image(16, 16)
    for y in range(3, 13):
        for x in range(1, 15):
            edge = x in (1, 14) or y in (3, 12)
            img.set(x, y, hexc(PAPER[1] if edge else PAPER[4 - (y % 3 == 0)]))
    # Umschlagfalten: V von den oberen Ecken zur Mitte.
    for i in range(7):
        img.set(2 + i, 4 + i // 1 if i < 6 else 9, hexc(PAPER[2]))
        img.set(13 - i, 4 + i // 1 if i < 6 else 9, hexc(PAPER[2]))
    for y in range(11, 13):
        for x in range(1, 15):
            if abs(x - 7.5) > (12 - y) * 3 + 1:
                img.set(x, y, hexc(PAPER[3]))
    # Wachssiegel mit Goldkern.
    for (x, y), c in {
        (7, 7): 1, (8, 7): 1, (6, 8): 1, (7, 8): 2, (8, 8): 2, (9, 8): 1, (6, 9): 0, (7, 9): 2, (8, 9): 2, (9, 9): 0, (7, 10): 0, (8, 10): 0,
    }.items():
        img.set(x, y, hexc(WAX[c]))
    img.set(7, 8, hexc(GOLD[3]))
    img.set(8, 9, hexc(GOLD[2]))
    # Kleine Flügel am Umschlag.
    for x, y, c in ((0, 5, 2), (0, 6, 3), (0, 7, 2), (15, 5, 2), (15, 6, 3), (15, 7, 2), (1, 2, 3), (2, 2, 2), (14, 2, 3), (13, 2, 2)):
        img.set(x, y, hexc(WHITE[c]))
    save(img, 'textures/item/hermes_summons.png')


def feather():
    img = Image(16, 16)
    # Kiel diagonal von unten links nach oben rechts.
    shaft = [(2 + i, 13 - i) for i in range(11)]
    for i, (x, y) in enumerate(shaft):
        img.set(x, y, hexc(GOLD[3 if i > 2 else 1]))
    for i, (x, y) in enumerate(shaft[2:], 2):
        width = 1 + min(i - 1, 3) - max(0, i - 8)
        for w in range(1, width + 1):
            shade = 3 if w == 1 else (2 if w == 2 else 1)
            img.set(x - w, y - w, hexc(WHITE[shade]))
            img.set(x + w, y + w, hexc(WHITE[shade - 1]))
    for x, y in ((12, 1), (13, 2), (13, 1), (11, 0), (14, 2)):
        img.set(x, y, hexc(GOLD[4]))
    save(img, 'textures/item/hermes_feather.png')


if __name__ == '__main__':
    letter()
    feather()
    flush()
