"""Текстуры примера-игры «Дум» (docs/examples/scripts/doom/textures): стены и полы шести локаций, монстры, снаряды,
оружие, предметы. Пиксель-арт с объёмом (свет сверху, тень снизу, контур). Запуск из корня: python tools/gen_doom.py
"""
import math
import os
import random

from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docs", "examples", "scripts", "doom", "textures")
random.seed(7)


def save(img, name):
    os.makedirs(OUT, exist_ok=True)
    img.save(os.path.join(OUT, name + ".png"))


def clamp(v):
    return max(0, min(255, int(v)))


def tone(c, k):
    return (clamp(c[0] * k), clamp(c[1] * k), clamp(c[2] * k), 255)


def noise(img, amount, only_opaque=True):
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if only_opaque and a == 0:
                continue
            n = random.randint(-amount, amount)
            px[x, y] = (clamp(r + n), clamp(g + n), clamp(b + n), a)
    return img


def bevel(d, box, base, light=1.25, dark=0.6, radius=0):
    """Прямоугольник с объёмом: светлый верх/лево, тёмный низ/право."""
    x1, y1, x2, y2 = box
    d.rounded_rectangle(box, radius=radius, fill=tone(base, dark))
    d.rounded_rectangle([x1, y1, x2 - 1, y2 - 1], radius=radius, fill=tone(base, light))
    d.rounded_rectangle([x1 + 1, y1 + 1, x2 - 1, y2 - 1], radius=radius, fill=tuple(base) + (255,))


def ball(d, box, base, light=(1.45, -0.3, -0.35)):
    """Шар/овал с бликом (концентрические эллипсы)."""
    x1, y1, x2, y2 = box
    steps = 7
    for i in range(steps):
        t = i / (steps - 1)
        k = 0.55 + 0.75 * t
        ox = (x2 - x1) * light[1] * t * 0.5
        oy = (y2 - y1) * light[2] * t * 0.5
        sx = (x2 - x1) * (1 - t * 0.75) / 2
        sy = (y2 - y1) * (1 - t * 0.75) / 2
        cx, cy = (x1 + x2) / 2 + ox, (y1 + y2) / 2 + oy
        d.ellipse([cx - sx, cy - sy, cx + sx, cy + sy], fill=tone(base, k))


def outline(img, color=(20, 10, 10, 255)):
    """Контур вокруг непрозрачных пикселей."""
    src = img.copy().load()
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            if src[x, y][3] == 0:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < img.width and 0 <= ny < img.height and src[nx, ny][3] > 0:
                        px[x, y] = color
                        break
    return img


# --- Стены ---

def bricks(base, mortar, bw=16, bh=8):
    img = Image.new("RGBA", (64, 64), tone(mortar, 1))
    d = ImageDraw.Draw(img)
    for row in range(64 // bh):
        off = (bw // 2) if row % 2 else 0
        for col in range(-1, 64 // bw + 1):
            x = col * bw + off
            c = tone(base, random.uniform(0.82, 1.12))
            bevel(d, [x + 1, row * bh + 1, x + bw - 1, row * bh + bh - 1], c[:3], 1.2, 0.65)
    return noise(img, 8)


def tech():
    img = Image.new("RGBA", (64, 64), (52, 58, 66, 255))
    d = ImageDraw.Draw(img)
    for y in (0, 32):
        bevel(d, [1, y + 1, 63, y + 31], (88, 96, 108), 1.3, 0.55)
        d.rectangle([8, y + 11, 55, y + 16], fill=(16, 22, 28, 255))
        d.rectangle([9, y + 12, 54, y + 15], fill=(40, 190, 230, 255) if y == 0 else (230, 160, 40, 255))
        for x in (5, 58):
            ball(d, [x - 2, y + 3, x + 2, y + 7], (170, 170, 180))
            ball(d, [x - 2, y + 24, x + 2, y + 28], (170, 170, 180))
        for x in range(10, 54, 6):
            d.line([x, y + 21, x + 3, y + 21], fill=(60, 66, 76, 255))
    return noise(img, 6)


def stone(base=(110, 92, 80), dark=(40, 30, 26)):
    img = Image.new("RGBA", (64, 64), dark + (255,))
    d = ImageDraw.Draw(img)
    for _ in range(30):
        x, y, r = random.randint(-4, 67), random.randint(-4, 67), random.randint(7, 14)
        ball(d, [x - r, y - r * 0.8, x + r, y + r * 0.8], tone(base, random.uniform(0.75, 1.1))[:3])
    return noise(img, 10)


def wood():
    img = Image.new("RGBA", (64, 64), (60, 36, 18, 255))
    d = ImageDraw.Draw(img)
    for i, x in enumerate(range(0, 64, 16)):
        c = tone((128, 82, 44), random.uniform(0.85, 1.1))[:3]
        bevel(d, [x, 0, x + 15, 63], c, 1.15, 0.6)
        for y in range(2, 64, 5):
            d.line([x + 3, y, x + 12, y + random.randint(-1, 1)], fill=tone(c, 0.85))
        for y in (6, 56):
            ball(d, [x + 6, y - 2, x + 10, y + 2], (60, 60, 64))
    return noise(img, 7)


def metal():
    img = Image.new("RGBA", (64, 64), (70, 74, 80, 255))
    d = ImageDraw.Draw(img)
    for y in (0, 32):
        for x in (0, 32):
            bevel(d, [x + 1, y + 1, x + 31, y + 31], (112, 118, 126), 1.25, 0.55)
            for cx, cy in ((x + 4, y + 4), (x + 27, y + 4), (x + 4, y + 27), (x + 27, y + 27)):
                ball(d, [cx - 2, cy - 2, cx + 2, cy + 2], (160, 165, 175))
    d.line([0, 40, 63, 44], fill=(120, 60, 30, 200), width=2)
    return noise(img, 6)


def ice():
    img = Image.new("RGBA", (64, 64), (120, 170, 210, 255))
    d = ImageDraw.Draw(img)
    for _ in range(14):
        x, y = random.randint(0, 63), random.randint(0, 63)
        d.line([x, y, x + random.randint(-20, 20), y + random.randint(5, 25)], fill=(220, 240, 255, 255), width=1)
    for _ in range(8):
        x, y, r = random.randint(0, 63), random.randint(0, 63), random.randint(4, 10)
        d.ellipse([x - r, y - r, x + r, y + r], outline=(90, 140, 190, 255))
    return noise(img, 6)


def hell():
    img = stone((120, 40, 30), (30, 6, 4))
    d = ImageDraw.Draw(img)
    for _ in range(6):
        x, y = random.randint(0, 63), random.randint(0, 63)
        pts = [(x, y)]
        for _ in range(5):
            x += random.randint(-6, 6)
            y += random.randint(3, 8)
            pts.append((x, y))
        d.line(pts, fill=(255, 120, 20, 255), width=2)
        d.line(pts, fill=(255, 220, 90, 255), width=1)
    return img


def exit_sign():
    img = metal()
    d = ImageDraw.Draw(img)
    bevel(d, [8, 20, 56, 42], (30, 30, 30), 1.2, 0.5)
    letters = {"E": ["111", "100", "110", "100", "111"], "X": ["101", "101", "010", "101", "101"],
               "I": ["111", "010", "010", "010", "111"], "T": ["111", "010", "010", "010", "010"]}
    x = 14
    for ch in "EXIT":
        for r, line in enumerate(letters[ch]):
            for c, bit in enumerate(line):
                if bit == "1":
                    d.rectangle([x + c * 2, 26 + r * 2, x + c * 2 + 1, 27 + r * 2], fill=(90, 255, 110, 255))
        x += 9
    return img


def crate():
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    bevel(d, [0, 0, 63, 63], (140, 100, 50), 1.25, 0.5)
    bevel(d, [6, 6, 57, 57], (120, 84, 40), 0.7, 1.2)
    d.line([8, 8, 55, 55], fill=(150, 108, 56, 255), width=5)
    d.line([8, 55, 55, 8], fill=(150, 108, 56, 255), width=5)
    return noise(img, 8)


# --- Полы и потолки ---

def tiles(c1, c2, size=16):
    img = Image.new("RGBA", (64, 64), c1 + (255,))
    d = ImageDraw.Draw(img)
    for y in range(0, 64, size):
        for x in range(0, 64, size):
            c = c2 if (x + y) // size % 2 else c1
            bevel(d, [x, y, x + size - 1, y + size - 1], c, 1.12, 0.75)
    return noise(img, 7)


def grate():
    img = Image.new("RGBA", (64, 64), (30, 32, 36, 255))
    d = ImageDraw.Draw(img)
    for i in range(0, 64, 8):
        d.rectangle([i, 0, i + 2, 63], fill=(96, 100, 108, 255))
        d.rectangle([0, i, 63, i + 2], fill=(84, 88, 96, 255))
    return noise(img, 6)


def dirt():
    img = Image.new("RGBA", (64, 64), (86, 66, 46, 255))
    d = ImageDraw.Draw(img)
    for _ in range(40):
        x, y, r = random.randint(0, 63), random.randint(0, 63), random.randint(1, 3)
        d.ellipse([x - r, y - r, x + r, y + r], fill=tone((86, 66, 46), random.uniform(0.6, 1.3)))
    return noise(img, 10)


def planks():
    img = Image.new("RGBA", (64, 64), (90, 56, 28, 255))
    d = ImageDraw.Draw(img)
    for y in range(0, 64, 8):
        bevel(d, [0, y, 63, y + 7], tone((140, 92, 50), random.uniform(0.85, 1.1))[:3], 1.15, 0.6)
    return noise(img, 6)


def lava():
    img = Image.new("RGBA", (64, 64), (200, 50, 10, 255))
    d = ImageDraw.Draw(img)
    for _ in range(18):
        x, y, r = random.randint(0, 63), random.randint(0, 63), random.randint(3, 9)
        ball(d, [x - r, y - r, x + r, y + r], (255, 150, 40))
    return noise(img, 12)


# --- Монстры (64×64, вид спереди) ---

def humanoid(skin, armor, eye, horns=False, belly=None, big=False, spikes=False):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = 1.15 if big else 1.0
    cx = 32
    # ноги
    for lx in (cx - 9, cx + 3):
        bevel(d, [lx, 42, lx + 6, 62], armor, 1.2, 0.55, 2)
    # тело
    ball(d, [cx - 13 * s, 22, cx + 13 * s, 46], skin)
    if belly:
        ball(d, [cx - 7, 30, cx + 7, 44], belly)
    # руки
    for ax, side in ((cx - 18 * s, -1), (cx + 12 * s, 1)):
        ball(d, [ax, 24, ax + 7, 44], tone(skin, 0.9)[:3])
        d.polygon([(ax + 1, 43), (ax + 6, 43), (ax + 3 + side, 49)], fill=(235, 230, 210, 255))
    # голова
    ball(d, [cx - 9, 6, cx + 9, 24], skin)
    d.rectangle([cx - 6, 12, cx - 2, 15], fill=eye + (255,))
    d.rectangle([cx + 2, 12, cx + 6, 15], fill=eye + (255,))
    d.rectangle([cx - 5, 18, cx + 5, 20], fill=(40, 6, 6, 255))
    for tx in range(cx - 4, cx + 5, 2):
        d.point((tx, 18), fill=(240, 240, 220, 255))
    if horns:
        d.polygon([(cx - 8, 9), (cx - 14, 0), (cx - 5, 7)], fill=(230, 220, 200, 255))
        d.polygon([(cx + 8, 9), (cx + 14, 0), (cx + 5, 7)], fill=(230, 220, 200, 255))
    if spikes:
        for sx in range(cx - 10, cx + 11, 5):
            d.polygon([(sx - 2, 24), (sx + 2, 24), (sx, 19)], fill=(220, 200, 170, 255))
    return outline(noise(img, 6))


def zombie():
    img = humanoid((150, 120, 100), (60, 80, 50), (255, 60, 40))
    d = ImageDraw.Draw(img)
    d.rectangle([36, 30, 54, 34], fill=(50, 50, 56, 255))  # винтовка
    d.rectangle([52, 29, 58, 31], fill=(30, 30, 34, 255))
    return img


def caco():
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for sx in range(10, 56, 8):
        d.polygon([(sx - 3, 12), (sx + 3, 12), (sx, 3)], fill=(240, 220, 200, 255))
    ball(d, [5, 6, 59, 60], (200, 30, 30))
    ball(d, [21, 15, 43, 35], (245, 245, 235))
    ball(d, [27, 19, 37, 31], (40, 200, 60))
    d.ellipse([30, 22, 34, 28], fill=(10, 10, 10, 255))
    d.chord([16, 34, 48, 56], 0, 180, fill=(50, 0, 0, 255))
    for x in range(18, 47, 5):
        d.polygon([(x, 44), (x + 3, 44), (x + 1, 50)], fill=(250, 250, 230, 255))
    return outline(noise(img, 6))


def corpse():
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    ball(d, [4, 44, 60, 62], (130, 0, 0))
    ball(d, [16, 42, 46, 56], (110, 70, 56))
    return noise(img, 8)


def orb(base, core, size=32):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    ball(d, [1, 1, size - 1, size - 1], base)
    ball(d, [size * 0.28, size * 0.28, size * 0.72, size * 0.72], core)
    return img


def rocket_proj():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    ball(d, [6, 6, 26, 26], (255, 170, 40))
    ball(d, [11, 11, 21, 21], (130, 130, 140))
    return img


def explosion():
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for _ in range(9):
        x, y, r = random.randint(16, 48), random.randint(16, 48), random.randint(9, 18)
        ball(d, [x - r, y - r, x + r, y + r], random.choice([(255, 140, 30), (255, 200, 60), (230, 70, 20)]))
    return img


# --- Предметы ---

def pickup(kind):
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if kind == "medkit":
        bevel(d, [3, 9, 29, 30], (230, 230, 230), 1.1, 0.6, 2)
        d.rectangle([14, 12, 18, 27], fill=(220, 30, 30, 255))
        d.rectangle([8, 17, 24, 22], fill=(220, 30, 30, 255))
    elif kind == "bullets":
        bevel(d, [3, 16, 29, 30], (90, 90, 60), 1.2, 0.5)
        for x in (6, 12, 18, 24):
            bevel(d, [x, 8, x + 4, 18], (210, 180, 70), 1.3, 0.6)
    elif kind == "shells":
        bevel(d, [3, 16, 29, 30], (130, 30, 30), 1.2, 0.5)
        for x in (6, 12, 18, 24):
            bevel(d, [x, 9, x + 4, 17], (230, 50, 50), 1.3, 0.6)
    elif kind == "rockets":
        for x in (7, 17):
            bevel(d, [x, 6, x + 8, 30], (110, 115, 120), 1.3, 0.55, 3)
            d.polygon([(x, 8), (x + 8, 8), (x + 4, 1)], fill=(200, 50, 40, 255))
    elif kind == "cells":
        bevel(d, [5, 8, 27, 30], (50, 70, 90), 1.2, 0.5, 2)
        d.rectangle([9, 12, 23, 26], fill=(60, 200, 255, 255))
    elif kind == "armor":
        d.polygon([(4, 8), (28, 8), (26, 26), (16, 31), (6, 26)], fill=(40, 120, 50, 255))
        d.polygon([(7, 10), (25, 10), (23, 24), (16, 28), (9, 24)], fill=(80, 200, 90, 255))
    return outline(noise(img, 4))


# --- Оружие (вид от первого лица, 128×80) ---

def hand(d, box):
    """Кисть: ладонь снизу и пальцы, обхватившие рукоять."""
    x1, y1, x2, y2 = box
    skin = (210, 160, 125)
    bevel(d, [x1 + 4, y1 + 10, x2 - 4, y2], skin, 1.15, 0.6, 6)
    fw = (x2 - x1 - 8) // 4
    for i in range(4):
        fx = x1 + 4 + i * fw
        bevel(d, [fx, y1 + 2, fx + fw - 1, y1 + 16], tone(skin, 1.05 - i * 0.04)[:3], 1.2, 0.55, 3)
    bevel(d, [x1 - 2, y1 + 12, x1 + 8, y1 + 26], skin, 1.2, 0.6, 3)


def gun(kind):
    img = Image.new("RGBA", (128, 80), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    steel, dark, wood_c = (120, 124, 134), (60, 62, 70), (130, 82, 40)
    if kind == "pistol":
        bevel(d, [54, 18, 74, 54], steel, 1.3, 0.5, 2)
        bevel(d, [58, 10, 70, 22], dark, 1.3, 0.5)
        hand(d, [46, 46, 82, 80])
    elif kind == "shotgun":
        bevel(d, [52, 0, 62, 50], dark, 1.4, 0.5, 2)
        bevel(d, [64, 0, 74, 50], dark, 1.4, 0.5, 2)
        bevel(d, [46, 40, 80, 58], wood_c, 1.3, 0.5, 3)
        hand(d, [44, 52, 84, 80])
    elif kind == "chaingun":
        for x in (48, 56, 64, 72):
            bevel(d, [x, 0, x + 6, 44], steel, 1.4, 0.5, 2)
        bevel(d, [42, 36, 86, 56], dark, 1.3, 0.5, 3)
        hand(d, [44, 50, 84, 80])
    elif kind == "rocket":
        bevel(d, [44, 4, 84, 56], (90, 100, 80), 1.3, 0.5, 4)
        d.ellipse([52, 2, 76, 16], fill=(20, 20, 20, 255))
        hand(d, [46, 50, 86, 80])
    elif kind == "plasma":
        bevel(d, [46, 6, 82, 54], (70, 90, 120), 1.35, 0.5, 4)
        for y in range(14, 48, 8):
            d.rectangle([52, y, 76, y + 3], fill=(60, 220, 255, 255))
        hand(d, [44, 50, 84, 80])
    elif kind == "bfg":
        bevel(d, [36, 8, 92, 60], (60, 110, 60), 1.35, 0.5, 6)
        ball(d, [52, 14, 76, 38], (120, 255, 120))
        hand(d, [40, 52, 88, 80])
    return outline(noise(img, 4), (10, 10, 12, 255))


def flash():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for i in range(8):
        a = i * math.pi / 4
        d.line([16, 16, 16 + math.cos(a) * 15, 16 + math.sin(a) * 15], fill=(255, 210, 80, 255), width=3)
    ball(d, [7, 7, 25, 25], (255, 230, 120))
    return img


def weapon_pickup(kind):
    return gun(kind).crop((30, 0, 98, 60)).resize((48, 42))


if __name__ == "__main__":
    for name, img in {
        "wall_brick": bricks((150, 64, 42), (70, 60, 55)), "wall_tech": tech(), "wall_stone": stone(), "wall_wood": wood(),
        "wall_metal": metal(), "wall_ice": ice(), "wall_hell": hell(), "exit": exit_sign(), "crate": crate(),
        "floor_tile": tiles((86, 86, 90), (70, 70, 76)), "floor_metal": grate(), "floor_dirt": dirt(), "floor_wood": planks(),
        "floor_ice": tiles((170, 205, 230), (150, 190, 220), 32), "floor_hell": stone((90, 30, 24), (24, 6, 4)), "lava": lava(),
        "ceiling": tiles((52, 54, 62), (46, 48, 56), 32), "ceiling_rock": stone((70, 60, 54), (26, 20, 18)),
        "ceiling_ice": tiles((130, 160, 190), (120, 150, 180), 32),
        "zombie": zombie(), "imp": humanoid((150, 96, 56), (110, 70, 40), (255, 210, 0), spikes=True),
        "demon": humanoid((220, 110, 140), (180, 80, 110), (255, 40, 40), horns=True, belly=(240, 150, 170), big=True),
        "caco": caco(), "baron": humanoid((200, 120, 90), (60, 130, 60), (90, 255, 90), horns=True, big=True),
        "corpse": corpse(), "fireball": orb((255, 110, 20), (255, 230, 90)), "greenball": orb((60, 200, 60), (200, 255, 150)),
        "plasma": orb((40, 140, 255), (200, 240, 255), 24), "bfgball": orb((60, 255, 60), (230, 255, 230), 48),
        "rocket": rocket_proj(), "explosion": explosion(),
        "medkit": pickup("medkit"), "bullets": pickup("bullets"), "shells": pickup("shells"), "rockets": pickup("rockets"),
        "cells": pickup("cells"), "armor": pickup("armor"), "gun_flash": flash(),
    }.items():
        save(img, name)
    for k in ("pistol", "shotgun", "chaingun", "rocket", "plasma", "bfg"):
        save(gun(k), "gun_" + k)
        save(weapon_pickup(k), "pickup_" + k)
    # Старые файлы прошлой версии.
    for old in ("floor.png", "pickup_chaingun.png"):
        pass
    print("Doom textures written to", OUT)
