"""Графика примера-игры «Block Strike» (docs/examples/scripts/blockstrike): текстуры карт, скины игроков (развёртка
как у скина Minecraft 64×64), оружие от первого лица, иконки оружия, эффекты, радары карт (из maps.lua) и иконка.
Запуск из корня репозитория: python tools/gen_strike.py
"""
import math
import os
import random
import re

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "docs", "examples", "scripts", "blockstrike")
TEX = os.path.join(APP, "textures")
RADAR = os.path.join(APP, "radar")
random.seed(42)


def save(img, name, folder=TEX):
    os.makedirs(folder, exist_ok=True)
    img.save(os.path.join(folder, name + ".png"))


def clamp(v):
    return max(0, min(255, int(v)))


def tone(c, k):
    return (clamp(c[0] * k), clamp(c[1] * k), clamp(c[2] * k), 255 if len(c) < 4 else c[3])


def mix(a, b, t):
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def noise(img, amount):
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            n = random.randint(-amount, amount)
            px[x, y] = (clamp(r + n), clamp(g + n), clamp(b + n), a)
    return img


def base(color, amount=10, size=32):
    return noise(Image.new("RGBA", (size, size), tuple(color) + (255,)), amount)


# --- Текстуры карт (32×32, 1 клетка = 1 блок) ---

def bricks(c, mortar, bw=16, bh=8, amount=12):
    img = Image.new("RGBA", (32, 32), tuple(mortar) + (255,))
    d = ImageDraw.Draw(img)
    for row in range(32 // bh):
        off = (bw // 2) if row % 2 else 0
        for col in range(-1, 32 // bw + 1):
            x = col * bw + off
            k = 0.88 + random.random() * 0.24
            d.rectangle([x + 1, row * bh + 1, x + bw - 1, row * bh + bh - 1], fill=tone(c, k))
            d.line([x + 1, row * bh + 1, x + bw - 2, row * bh + 1], fill=tone(c, k * 1.15))
    return noise(img, amount)


def sandstone():
    img = base((214, 190, 140), 8)
    d = ImageDraw.Draw(img)
    for y in (0, 10, 21):
        d.line([0, y, 31, y], fill=(180, 152, 104, 255))
        d.line([0, y + 1, 31, y + 1], fill=(232, 212, 168, 255))
    for x, y in ((6, 4), (22, 15), (12, 26), (27, 6)):
        d.point([x, y], fill=(170, 140, 95, 255))
    return img


def sand():
    img = base((222, 200, 150), 9)
    px = img.load()
    for _ in range(60):
        x, y = random.randrange(32), random.randrange(32)
        px[x, y] = (196, 172, 120, 255)
    return img


def tiles(c1, c2, size=16):
    img = Image.new("RGBA", (32, 32))
    d = ImageDraw.Draw(img)
    for ty in range(0, 32, size):
        for tx in range(0, 32, size):
            c = c1 if (tx // size + ty // size) % 2 == 0 else c2
            d.rectangle([tx, ty, tx + size - 1, ty + size - 1], fill=tuple(c) + (255,))
            d.line([tx, ty, tx + size - 1, ty], fill=tone(c, 1.15))
            d.line([tx, ty, tx, ty + size - 1], fill=tone(c, 1.1))
            d.line([tx, ty + size - 1, tx + size - 1, ty + size - 1], fill=tone(c, 0.75))
    return noise(img, 7)


def site(c, stripe):
    img = base(c, 7)
    d = ImageDraw.Draw(img)
    for i in range(-32, 32, 8):
        d.polygon([(i, 31), (i + 4, 31), (i + 36, -1), (i + 32, -1)], fill=tuple(stripe) + (255,))
    d.rectangle([0, 0, 31, 31], outline=tone(stripe, 0.8))
    return noise(img, 5)


def concrete(c=(150, 150, 146)):
    img = base(c, 9)
    d = ImageDraw.Draw(img)
    d.line([0, 15, 31, 15], fill=tone(c, 0.8))
    d.line([15, 0, 15, 31], fill=tone(c, 0.82))
    for _ in range(4):
        x, y = random.randrange(32), random.randrange(32)
        d.line([x, y, x + random.randint(-4, 4), y + random.randint(2, 5)], fill=tone(c, 0.72))
    return img


def container():
    c = (176, 64, 44)
    img = Image.new("RGBA", (32, 32))
    d = ImageDraw.Draw(img)
    for x in range(32):
        k = 1.12 if (x // 3) % 2 == 0 else 0.86
        d.line([x, 0, x, 31], fill=tone(c, k))
    d.rectangle([0, 0, 31, 2], fill=tone(c, 0.7))
    d.rectangle([0, 29, 31, 31], fill=tone(c, 0.7))
    return noise(img, 6)


def asphalt(lines=False):
    img = base((62, 64, 68), 12)
    if lines:
        d = ImageDraw.Draw(img)
        d.rectangle([14, 0, 17, 31], fill=(214, 186, 60, 255))
        noise(img, 6)
    return img


def crate():
    c = (164, 116, 62)
    img = base(c, 8)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], outline=tone(c, 0.55))
    d.rectangle([1, 1, 30, 30], outline=tone(c, 0.7))
    d.rectangle([2, 2, 29, 29], outline=tone(c, 1.2))
    d.line([3, 3, 28, 28], fill=tone(c, 0.68), width=3)
    d.line([3, 28, 28, 3], fill=tone(c, 0.82), width=2)
    return noise(img, 5)


def crate_metal():
    c = (96, 112, 104)
    img = base(c, 6)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], outline=tone(c, 0.55), width=2)
    for y in (8, 16, 24):
        d.line([2, y, 29, y], fill=tone(c, 0.8))
        d.line([2, y + 1, 29, y + 1], fill=tone(c, 1.2))
    for x, y in ((3, 3), (28, 3), (3, 28), (28, 28)):
        d.point([x, y], fill=(200, 200, 200, 255))
    return img


def box():
    c = (190, 160, 110)
    img = base(c, 6)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], outline=tone(c, 0.6))
    d.rectangle([13, 0, 18, 31], fill=tone(c, 0.82))
    return noise(img, 4)


def metal():
    c = (120, 126, 134)
    img = base(c, 7)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 15, 15], outline=tone(c, 0.7))
    d.rectangle([16, 16, 31, 31], outline=tone(c, 0.7))
    d.rectangle([16, 0, 31, 15], outline=tone(c, 0.75))
    d.rectangle([0, 16, 15, 31], outline=tone(c, 0.75))
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13), (18, 18), (29, 18), (18, 29), (29, 29)):
        d.point([x, y], fill=(190, 194, 200, 255))
    return img


def grate():
    c = (78, 82, 88)
    img = base(c, 5)
    d = ImageDraw.Draw(img)
    for i in range(0, 32, 4):
        d.line([i, 0, i, 31], fill=tone(c, 0.6))
        d.line([0, i, 31, i], fill=tone(c, 1.25))
    return img


def ceiling():
    c = (58, 60, 64)
    img = base(c, 6)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], outline=tone(c, 0.7))
    d.rectangle([10, 13, 21, 18], fill=(230, 228, 200, 255))
    return img


def cobble():
    img = Image.new("RGBA", (32, 32), (90, 90, 90, 255))
    d = ImageDraw.Draw(img)
    for _ in range(26):
        x, y = random.randrange(-4, 32), random.randrange(-4, 32)
        w, h = random.randint(6, 11), random.randint(5, 9)
        k = 0.85 + random.random() * 0.45
        d.rounded_rectangle([x, y, x + w, y + h], radius=2, fill=tone((128, 128, 128), k), outline=(70, 70, 70, 255))
    return noise(img, 8)


def planks():
    c = (166, 128, 76)
    img = Image.new("RGBA", (32, 32))
    d = ImageDraw.Draw(img)
    for row in range(4):
        k = 0.9 + random.random() * 0.2
        d.rectangle([0, row * 8, 31, row * 8 + 7], fill=tone(c, k))
        d.line([0, row * 8 + 7, 31, row * 8 + 7], fill=tone(c, 0.6))
        x = random.randint(4, 28)
        d.line([x, row * 8, x, row * 8 + 6], fill=tone(c, 0.65))
    return noise(img, 9)


def grass():
    img = base((92, 150, 60), 14)
    px = img.load()
    for _ in range(80):
        x, y = random.randrange(32), random.randrange(32)
        px[x, y] = (120, 178, 76, 255) if random.random() < 0.5 else (70, 120, 46, 255)
    return img


def path():
    img = base((150, 122, 84), 12)
    px = img.load()
    for _ in range(40):
        x, y = random.randrange(32), random.randrange(32)
        px[x, y] = (120, 96, 64, 255)
    return img


def hay():
    c = (206, 172, 62)
    img = Image.new("RGBA", (32, 32))
    d = ImageDraw.Draw(img)
    for x in range(32):
        d.line([x, 0, x, 31], fill=tone(c, 0.85 + random.random() * 0.3))
    d.rectangle([0, 6, 31, 8], fill=(120, 70, 40, 255))
    d.rectangle([0, 23, 31, 25], fill=(120, 70, 40, 255))
    return noise(img, 6)


def letter(ch):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    col = (230, 60, 40, 235)
    if ch == "A":
        d.polygon([(32, 6), (42, 6), (58, 58), (46, 58), (37, 26), (28, 58), (16, 58)], fill=col)
        d.polygon([(32, 6), (24, 30), (30, 30), (37, 12)], fill=col)
        d.rectangle([22, 38, 44, 45], fill=col)
    else:
        d.rectangle([14, 6, 26, 58], fill=col)
        d.rounded_rectangle([14, 6, 48, 30], radius=11, fill=col)
        d.rounded_rectangle([14, 32, 52, 58], radius=12, fill=col)
        d.rounded_rectangle([26, 13, 37, 23], radius=4, fill=(0, 0, 0, 0))
        d.rounded_rectangle([26, 39, 40, 51], radius=4, fill=(0, 0, 0, 0))
    return img


# --- Скины игроков: развёртка 64×64 как у Minecraft ---

def face_rect(d, u, v, w, h, color, k=1.0):
    d.rectangle([u, v, u + w - 1, v + h - 1], fill=tone(color, k))


def paint_part(d, u0, v0, pw, ph, pd, front, side=None, back=None, top=None, bottom=None):
    side = side or tone(front, 0.88)
    back = back or tone(front, 0.8)
    face_rect(d, u0 + pd, v0, pw, pd, top or tone(front, 1.1))
    face_rect(d, u0 + pd + pw, v0, pw, pd, bottom or tone(front, 0.6))
    face_rect(d, u0, v0 + pd, pd, ph, side)
    face_rect(d, u0 + pd, v0 + pd, pw, ph, front)
    face_rect(d, u0 + pd + pw, v0 + pd, pd, ph, side)
    face_rect(d, u0 + 2 * pd + pw, v0 + pd, pw, ph, back)


def skin(team):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    skin_c = (196, 150, 112)
    if team == "t":
        mask, jacket, pants, boots = (46, 44, 42), (150, 118, 72), (70, 64, 52), (40, 32, 26)
        paint_part(d, 0, 0, 8, 8, 8, mask)
        # прорезь для глаз
        d.rectangle([9, 11, 14, 12], fill=skin_c + (255,))
        d.point([10, 11], fill=(30, 30, 30, 255))
        d.point([13, 11], fill=(30, 30, 30, 255))
        paint_part(d, 16, 16, 8, 12, 4, jacket)
        d.rectangle([20, 20, 27, 21], fill=tone(jacket, 0.8))            # воротник
        d.rectangle([20, 26, 27, 27], fill=(60, 52, 40, 255))            # ремень
        d.rectangle([22, 22, 25, 25], fill=tone(jacket, 0.75))           # карман
        paint_part(d, 40, 16, 4, 12, 4, jacket)
        d.rectangle([40, 29, 55, 31], fill=(50, 40, 30, 255))            # перчатки
        paint_part(d, 0, 16, 4, 12, 4, pants)
        d.rectangle([0, 29, 15, 31], fill=boots + (255,))
    else:
        helmet, vest, shirt, pants, boots = (36, 48, 70), (40, 58, 92), (66, 86, 120), (34, 40, 58), (24, 24, 28)
        paint_part(d, 0, 0, 8, 8, 8, skin_c)
        d.rectangle([0, 0, 31, 10], fill=tone(helmet, 1.0))             # шлем: верх и края
        d.rectangle([8, 0, 15, 7], fill=tone(helmet, 1.15))
        d.rectangle([8, 10, 15, 11], fill=(30, 30, 34, 255))             # очки
        d.point([10, 10], fill=(120, 180, 220, 255))
        d.point([13, 10], fill=(120, 180, 220, 255))
        d.rectangle([24, 8, 31, 15], fill=tone(helmet, 0.85))            # затылок
        d.rectangle([8, 14, 15, 15], fill=(40, 40, 44, 255))             # подбородок (балаклава)
        paint_part(d, 16, 16, 8, 12, 4, vest)
        d.rectangle([21, 21, 26, 25], fill=tone(vest, 0.8))              # подсумки
        d.rectangle([20, 26, 27, 27], fill=(26, 26, 30, 255))
        d.text((21, 20), "", fill=(255, 255, 255, 255))
        paint_part(d, 40, 16, 4, 12, 4, shirt)
        d.rectangle([40, 29, 55, 31], fill=(30, 30, 34, 255))
        paint_part(d, 0, 16, 4, 12, 4, pants)
        d.rectangle([0, 29, 15, 31], fill=boots + (255,))
    return noise(img, 6)


# --- Оружие: профиль (вид сбоку) в единицах 0..100 по длине (0 — дуло), y вниз ---

DARK, GRAY, WOOD, BLACK, GREEN, TAN, STEEL = (52, 54, 58), (92, 96, 102), (140, 86, 44), (30, 31, 34), (70, 88, 60), (170, 150, 110), (176, 180, 186)

GUNS = {
    "glock": {"len": 0.42, "parts": [("r", 0, -6, 100, 6, BLACK), ("r", 0, 0, 100, 4, DARK), ("p", [(62, 4), (92, 4), (98, 34), (74, 34)], BLACK),
                                     ("r", 50, 4, 62, 12, DARK)]},
    "usp": {"len": 0.55, "parts": [("r", 0, -3, 40, 3, BLACK), ("r", 30, -6, 100, 6, DARK), ("r", 30, 0, 100, 4, BLACK),
                                   ("p", [(66, 4), (92, 4), (98, 34), (76, 34)], DARK)]},
    "p250": {"len": 0.44, "parts": [("r", 0, -6, 100, 6, GRAY), ("r", 0, 0, 100, 4, DARK), ("p", [(64, 4), (92, 4), (98, 34), (74, 34)], BLACK)]},
    "deagle": {"len": 0.55, "parts": [("r", 0, -8, 100, 8, STEEL), ("r", 0, 0, 100, 5, GRAY), ("r", 8, -10, 14, -8, STEEL),
                                      ("p", [(66, 5), (92, 5), (98, 36), (74, 36)], BLACK)]},
    "nova": {"len": 1.0, "parts": [("r", 0, -4, 60, 2, DARK), ("r", 10, 2, 45, 7, BLACK), ("r", 45, -6, 72, 6, DARK),
                                   ("p", [(72, -4), (100, -2), (100, 12), (80, 8), (72, 6)], BLACK)]},
    "mp9": {"len": 0.7, "parts": [("r", 0, -3, 30, 1, BLACK), ("r", 20, -7, 80, 5, DARK), ("r", 48, 5, 58, 30, BLACK),
                                  ("p", [(66, 5), (80, 5), (84, 24), (72, 24)], DARK), ("r", 80, -5, 100, -1, BLACK)]},
    "p90": {"len": 0.75, "parts": [("p", [(0, -2), (20, -8), (95, -8), (100, 10), (70, 14), (60, 4), (20, 4)], (60, 66, 60)),
                                   ("r", 30, -12, 80, -8, BLACK), ("r", 62, 4, 70, 14, (40, 44, 40))]},
    "galil": {"len": 0.95, "parts": [("r", 0, -2, 35, 1, BLACK), ("r", 28, -5, 55, 5, WOOD), ("r", 55, -6, 80, 6, DARK),
                                     ("p", [(58, 6), (68, 6), (72, 26), (62, 28)], BLACK), ("p", [(80, -4), (100, -2), (100, 9), (80, 5)], GRAY)]},
    "famas": {"len": 0.9, "parts": [("r", 0, -2, 25, 1, BLACK), ("p", [(18, -6), (90, -6), (100, 8), (40, 8), (18, 4)], (54, 60, 54)),
                                    ("r", 30, -14, 80, -8, BLACK), ("r", 70, 8, 80, 26, BLACK), ("r", 45, 8, 52, 18, DARK)]},
    "ak47": {"len": 1.0, "parts": [("r", 0, -2, 36, 1, BLACK), ("r", 2, -6, 5, -2, BLACK), ("r", 28, -5, 54, 5, WOOD),
                                   ("r", 54, -6, 80, 6, DARK), ("p", [(56, 6), (66, 6), (74, 30), (62, 32)], DARK),
                                   ("p", [(74, 6), (79, 6), (82, 20), (77, 20)], WOOD), ("p", [(80, -4), (100, -2), (100, 10), (80, 5)], WOOD)]},
    "m4a4": {"len": 1.0, "parts": [("r", 0, -2, 34, 1, BLACK), ("r", 22, -5, 50, 5, DARK), ("r", 50, -6, 78, 6, BLACK),
                                   ("r", 50, -10, 76, -6, DARK), ("r", 56, 6, 64, 26, BLACK), ("p", [(70, 6), (76, 6), (79, 20), (74, 20)], BLACK),
                                   ("p", [(78, -3), (100, -4), (100, 9), (78, 4)], DARK)]},
    "awp": {"len": 1.15, "parts": [("r", 0, -2, 50, 1, BLACK), ("r", 42, -6, 82, 6, GREEN), ("r", 50, -14, 76, -8, BLACK),
                                   ("r", 48, -12, 52, -6, BLACK), ("r", 74, -12, 78, -6, BLACK), ("r", 60, 6, 66, 18, BLACK),
                                   ("p", [(80, -5), (100, -3), (100, 12), (84, 8)], GREEN)]},
    "knife": {"len": 0.6, "parts": [("p", [(0, 0), (12, -5), (58, -5), (58, 4), (20, 4)], STEEL), ("r", 58, -6, 62, 6, DARK),
                                    ("r", 62, -4, 100, 4, BLACK)]},
}


def draw_profile(d, parts, tx):
    for part in parts:
        kind, color = part[0], part[-1]
        if kind == "r":
            x1, y1, x2, y2 = part[1:5]
            pts = [(x1, y1), (x2, y1), (x2, y2), (x1, y2)]
        else:
            pts = part[1]
        mapped = [tx(x, y) for x, y in pts]
        d.polygon(mapped, fill=tuple(color[:3]) + (255,))
        # блик сверху
        top = [tx(x, y) for x, y in pts[:2]]
        d.line(top, fill=tone(color, 1.45), width=1)


def icon(key):
    img = Image.new("RGBA", (60, 24), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if key in GUNS:
        g = GUNS[key]
        scale = 0.56 * (0.6 + 0.4 * min(1.0, g["len"]))
        w = 100 * scale
        ox = (60 - w) / 2
        draw_profile(d, g["parts"], lambda x, y: (ox + x * scale, 9 + y * scale * 0.9))
    elif key in ("he", "flash", "smoke"):
        col = {"he": (70, 96, 60), "flash": (150, 156, 164), "smoke": (90, 100, 110)}[key]
        d.ellipse([22, 6, 36, 22], fill=col + (255,))
        d.rectangle([26, 3, 32, 7], fill=(60, 60, 60, 255))
        d.line([32, 4, 37, 9], fill=(200, 200, 200, 255), width=1)
    elif key == "c4":
        d.rectangle([14, 6, 46, 20], fill=(90, 80, 60, 255))
        d.rectangle([22, 8, 38, 13], fill=(40, 200, 80, 255))
        d.rectangle([14, 15, 46, 17], fill=(170, 40, 30, 255))
    # тёмная обводка
    out = img.copy()
    px, po = img.load(), out.load()
    for y in range(24):
        for x in range(60):
            if px[x, y][3] == 0:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < 60 and 0 <= ny < 24 and px[nx, ny][3] > 0:
                        po[x, y] = (10, 10, 12, 200)
                        break
    return out


MUZZLES = {}


def viewmodel(key):
    W, H = 160, 100
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    sleeve, glove = (70, 74, 70), (38, 36, 34)
    if key in GUNS:
        g = GUNS[key]
        ln = min(1.0, g["len"])
        stock = (W * (0.86 + 0.18 * ln), H * (0.8 + 0.18 * ln))
        muzzle_full = (W * 0.27, H * 0.30)
        muzzle = (stock[0] + (muzzle_full[0] - stock[0]) * (0.45 + 0.55 * ln), stock[1] + (muzzle_full[1] - stock[1]) * (0.45 + 0.55 * ln))

        def tx(x, y):
            t = x / 100.0
            px = muzzle[0] + (stock[0] - muzzle[0]) * t
            py = muzzle[1] + (stock[1] - muzzle[1]) * t
            s = (0.5 + 1.1 * t) * (1.0 + 0.6 * (1 - ln))
            return (px - y * s * 0.7, py + y * s * 2.2)
        # тень/толщина: та же форма чуть ниже и темнее
        shade = [(p[0], p[1], p[2], p[3], p[4], tone(p[5], 0.55)) if p[0] == "r" else ("p", p[1], tone(p[2], 0.55)) for p in g["parts"]]
        draw_profile(d, shade, lambda x, y: (tx(x, y)[0] + 2, tx(x, y)[1] + 3))
        draw_profile(d, g["parts"], tx)
        mx, my = tx(0, 0)
        MUZZLES[key] = (mx / W, my / H)
        gx, gy = tx(70, 18)
        # рука: рукав из-за правого нижнего угла, перчатка на рукояти
        d.polygon([(gx - 4, gy + 2), (gx + 12, gy - 6), (W + 10, H - 6), (W + 10, H + 10), (gx + 14, H + 10)], fill=sleeve + (255,))
        d.polygon([(gx - 4, gy + 2), (gx + 12, gy - 6), (W + 10, H - 6)], fill=tone(sleeve, 1.2))
        d.rounded_rectangle([gx - 8, gy - 9, gx + 9, gy + 9], radius=4, fill=glove + (255,))
        d.line([(gx - 6, gy - 3), (gx + 6, gy - 6)], fill=tone(glove, 1.6), width=1)
        if ln > 0.7:
            fx, fy = tx(38, 6)
            d.polygon([(fx - 6, fy + 2), (fx + 6, fy - 2), (fx + 26, H + 10), (fx + 4, H + 10)], fill=tone(sleeve, 0.85))
            d.rounded_rectangle([fx - 8, fy - 6, fx + 7, fy + 7], radius=4, fill=glove + (255,))
    elif key in ("he", "flash", "smoke", "c4"):
        cx, cy = W * 0.66, H * 0.62
        d.polygon([(cx - 6, cy + 6), (cx + 14, cy), (W + 10, H - 10), (W + 10, H + 10), (cx + 4, H + 10)], fill=sleeve + (255,))
        if key == "c4":
            d.polygon([(cx - 24, cy - 14), (cx + 12, cy - 18), (cx + 18, cy + 2), (cx - 18, cy + 6)], fill=(96, 86, 64, 255))
            d.polygon([(cx - 14, cy - 12), (cx + 4, cy - 14), (cx + 6, cy - 6), (cx - 12, cy - 4)], fill=(40, 190, 80, 255))
            d.line([(cx - 18, cy), (cx + 16, cy - 4)], fill=(170, 40, 30, 255), width=2)
        else:
            col = {"he": (70, 96, 60), "flash": (150, 156, 164), "smoke": (90, 100, 110)}[key]
            d.ellipse([cx - 16, cy - 22, cx + 10, cy + 4], fill=col + (255,))
            d.ellipse([cx - 12, cy - 19, cx - 4, cy - 12], fill=tone(col, 1.4))
            d.rectangle([cx - 8, cy - 28, cx + 2, cy - 21], fill=(60, 60, 64, 255))
            d.line([(cx + 2, cy - 26), (cx + 12, cy - 16)], fill=(200, 200, 200, 255), width=2)
        d.ellipse([cx - 4, cy - 6, cx + 16, cy + 12], fill=glove + (255,))
    return img


def muzzle_flash(size=32, pale=False):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = size / 2
    for i in range(8):
        a = i * math.pi / 4 + random.random() * 0.3
        r = size * (0.32 + random.random() * 0.18)
        d.polygon([(c, c), (c + math.cos(a - 0.25) * r * 0.4, c + math.sin(a - 0.25) * r * 0.4), (c + math.cos(a) * r, c + math.sin(a) * r),
                   (c + math.cos(a + 0.25) * r * 0.4, c + math.sin(a + 0.25) * r * 0.4)], fill=(255, 200, 80, 255))
    d.ellipse([c - size * 0.2, c - size * 0.2, c + size * 0.2, c + size * 0.2], fill=(255, 240, 180, 255))
    return img


def puff(size, color, holes=0.25):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for _ in range(14):
        r = size * (0.16 + random.random() * 0.14)
        x = size / 2 + (random.random() - 0.5) * size * 0.45
        y = size / 2 + (random.random() - 0.5) * size * 0.45
        k = 0.85 + random.random() * 0.3
        d.ellipse([x - r, y - r, x + r, y + r], fill=tone(color, k))
    return noise(img, 6)


def explosion():
    img = Image.new("RGBA", (48, 48), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for i, (col, k) in enumerate((((200, 60, 20), 1.0), ((250, 140, 30), 0.75), ((255, 230, 120), 0.45))):
        for _ in range(9):
            r = 24 * k * (0.35 + random.random() * 0.3)
            x = 24 + (random.random() - 0.5) * 24 * k
            y = 24 + (random.random() - 0.5) * 24 * k
            d.ellipse([x - r, y - r, x + r, y + r], fill=col + (255,))
    return img


def small_sprite(size, color, shape="dot"):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if shape == "dot":
        d.ellipse([1, 1, size - 2, size - 2], fill=tuple(color) + (255,))
    else:
        d.rectangle([0, 0, size - 1, size - 1], fill=tuple(color) + (255,))
    return img


def flat_tex(color, size=16, amount=8, stripe=None):
    img = base(color, amount, size)
    if stripe:
        d = ImageDraw.Draw(img)
        d.rectangle([0, size // 2 - 2, size - 1, size // 2 + 1], fill=tuple(stripe) + (255,))
    return img


def c4_tex():
    img = Image.new("RGBA", (32, 32), (110, 96, 70, 255))
    d = ImageDraw.Draw(img)
    for y in range(4, 32, 7):
        d.rectangle([0, y, 31, y + 4], fill=(140, 124, 90, 255))
    d.rectangle([8, 8, 24, 16], fill=(30, 30, 30, 255))
    d.rectangle([10, 10, 22, 14], fill=(40, 210, 90, 255))
    d.line([0, 22, 31, 22], fill=(200, 40, 30, 255), width=2)
    return noise(img, 5)


def hud_icons():
    hp = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(hp)
    d.rectangle([6, 2, 9, 13], fill=(240, 240, 240, 255))
    d.rectangle([2, 6, 13, 9], fill=(240, 240, 240, 255))
    save(hp, "i_hp")
    ar = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(ar)
    d.polygon([(8, 1), (14, 3), (13, 10), (8, 15), (3, 10), (2, 3)], fill=(220, 226, 236, 255))
    d.polygon([(8, 3), (12, 4), (11, 9), (8, 12)], fill=(160, 170, 186, 255))
    save(ar, "i_armor")
    kit = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(kit)
    d.rectangle([2, 4, 13, 12], fill=(70, 140, 220, 255))
    d.line([4, 2, 11, 2], fill=(200, 200, 200, 255))
    d.line([5, 8, 10, 8], fill=(240, 240, 240, 255), width=2)
    save(kit, "i_kit")
    c4 = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(c4)
    d.rectangle([1, 4, 14, 12], fill=(120, 104, 74, 255))
    d.rectangle([4, 6, 11, 9], fill=(40, 210, 90, 255))
    d.line([1, 11, 14, 11], fill=(200, 40, 30, 255))
    save(c4, "i_c4")


def scope():
    size = 256
    img = Image.new("RGBA", (size, size), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    d.ellipse([4, 4, size - 5, size - 5], fill=(0, 0, 0, 0))
    d.ellipse([4, 4, size - 5, size - 5], outline=(0, 0, 0, 255), width=3)
    return img


# --- Радары: из строк карт в maps.lua ---

RADAR_COLORS = {
    "#": (46, 50, 56), "%": (64, 70, 78), "X": (150, 120, 80), "x": (176, 150, 104), ".": (128, 132, 128),
    ",": (112, 116, 114), "A": (178, 104, 92), "B": (178, 104, 92), "T": (170, 150, 96), "C": (96, 130, 170),
}


def radars():
    text = open(os.path.join(APP, "maps.lua"), encoding="utf-8").read()
    for block in re.finditer(r'key = "(\w+)".*?rows = \{(.*?)\}', text, re.S):
        key, body = block.group(1), block.group(2)
        rows = re.findall(r'"([^"]+)"', body)
        h, w = len(rows), len(rows[0])
        k = 4
        img = Image.new("RGBA", (w * k, h * k))
        d = ImageDraw.Draw(img)
        for z, row in enumerate(rows):
            for x, ch in enumerate(row):
                c = RADAR_COLORS.get(ch, (128, 132, 128))
                d.rectangle([x * k, z * k, x * k + k - 1, z * k + k - 1], fill=c + (255,))
        for s in "AB":
            cells = [(x, z) for z, row in enumerate(rows) for x, ch in enumerate(row) if ch == s]
            cx = sum(c[0] for c in cells) / len(cells)
            cz = sum(c[1] for c in cells) / len(cells)
            lt = letter(s).resize((20, 20), Image.NEAREST)
            px = lt.load()
            for yy in range(20):
                for xx in range(20):
                    if px[xx, yy][3] > 0:
                        px[xx, yy] = (255, 240, 230, 255)
            img.alpha_composite(lt, (int(cx * k + k / 2 - 10), int(cz * k + k / 2 - 10)))
        save(img, key, RADAR)


def app_icon():
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, 63, 63], radius=12, fill=(22, 28, 36, 255))
    d.rounded_rectangle([12, 26, 52, 50], radius=3, fill=(120, 104, 74, 255))
    d.rectangle([20, 30, 44, 37], fill=(30, 30, 30, 255))
    d.rectangle([22, 32, 42, 35], fill=(60, 230, 110, 255))
    d.line([12, 44, 52, 44], fill=(200, 40, 30, 255), width=2)
    d.ellipse([18, 6, 46, 34], outline=(255, 167, 38, 255), width=3)
    d.line([32, 2, 32, 14], fill=(255, 167, 38, 255), width=3)
    d.line([32, 26, 32, 38], fill=(255, 167, 38, 255), width=3)
    d.line([14, 20, 26, 20], fill=(255, 167, 38, 255), width=3)
    d.line([38, 20, 50, 20], fill=(255, 167, 38, 255), width=3)
    img.save(os.path.join(APP, "icon.png"))


def main():
    save(sandstone(), "sandstone")
    save(bricks((200, 170, 116), (150, 126, 86)), "sandbrick")
    save(sand(), "sand")
    save(tiles((186, 160, 120), (166, 138, 100)), "tiles")
    save(site((214, 190, 140), (190, 92, 70)), "site_sand")
    save(concrete(), "concrete")
    save(container(), "container")
    save(asphalt(), "asphalt")
    save(asphalt(True), "asphalt_lines")
    save(site((70, 72, 76), (200, 170, 50)), "site_port")
    save(crate(), "crate")
    save(crate_metal(), "crate_metal")
    save(box(), "box")
    save(bricks((140, 66, 52), (90, 84, 80)), "brick")
    save(metal(), "metal")
    save(concrete((120, 120, 118)), "concrete_floor")
    save(grate(), "grate")
    save(site((110, 110, 108), (200, 160, 40)), "site_factory")
    save(ceiling(), "ceiling")
    save(cobble(), "cobble")
    save(planks(), "planks")
    save(grass(), "grass")
    save(path(), "path")
    save(site((96, 140, 64), (190, 80, 60)), "site_village")
    save(hay(), "hay")
    save(letter("A"), "letter_a")
    save(letter("B"), "letter_b")
    save(skin("t"), "skin_t")
    save(skin("ct"), "skin_ct")
    save(flat_tex((44, 46, 50), 16, 6), "gun")
    save(flat_tex((180, 184, 190), 16, 5, (40, 40, 44)), "knife")
    save(c4_tex(), "c4")
    save(flat_tex((70, 96, 60), 16, 6), "nade_he")
    save(flat_tex((160, 166, 172), 16, 6), "nade_flash")
    save(flat_tex((90, 100, 110), 16, 6), "nade_smoke")
    save(muzzle_flash(), "muzzle")
    save(muzzle_flash(48), "v_flash")
    save(puff(48, (200, 200, 200)), "smoke")
    save(explosion(), "explosion")
    save(small_sprite(8, (20, 18, 16)), "impact")
    save(muzzle_flash(16), "spark")
    save(puff(24, (150, 20, 20)), "blood")
    save(small_sprite(8, (255, 40, 30)), "bomb_light")
    for key in list(GUNS.keys()) + ["he", "flash", "smoke", "c4"]:
        save(icon(key), "i_" + key)
        save(viewmodel(key), "v_" + key)
    with open(os.path.join(APP, "muzzles.lua"), "w", encoding="utf-8", newline="\n") as f:
        f.write("-- Где у картинок оружия от первого лица (textures/v_*.png) дуло — для вспышки. Создаёт tools/gen_strike.py.\n")
        f.write("return {\n")
        for k in sorted(MUZZLES):
            f.write("  %s = {%.3f, %.3f},\n" % (k, MUZZLES[k][0], MUZZLES[k][1]))
        f.write("}\n")
    hud_icons()
    save(scope(), "scope")
    radars()
    app_icon()
    print("ok")


if __name__ == "__main__":
    main()
