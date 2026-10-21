"""Текстуры примера-игры «Дум» (docs/examples/scripts/doom/textures): стены, пол, монстры, оружие, предметы.
Пиксель-арт 64×64 рисуется простыми фигурами. Запуск из корня проекта: python tools/gen_doom.py
"""
import os
import random

from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docs", "examples", "scripts", "doom", "textures")
random.seed(7)


def save(img, name):
    os.makedirs(OUT, exist_ok=True)
    img.save(os.path.join(OUT, name + ".png"))


def noise(img, amount):
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            n = random.randint(-amount, amount)
            px[x, y] = (max(0, min(255, r + n)), max(0, min(255, g + n)), max(0, min(255, b + n)), a)
    return img


def bricks(base, mortar, bw=16, bh=8):
    img = Image.new("RGBA", (64, 64), mortar)
    d = ImageDraw.Draw(img)
    for row in range(64 // bh):
        off = (bw // 2) if row % 2 else 0
        for col in range(-1, 64 // bw + 1):
            x = col * bw + off
            shade = random.randint(-18, 18)
            c = tuple(max(0, min(255, v + shade)) for v in base) + (255,)
            d.rectangle([x + 1, row * bh + 1, x + bw - 1, row * bh + bh - 1], fill=c)
    return noise(img, 10)


def tech():
    img = Image.new("RGBA", (64, 64), (70, 76, 84, 255))
    d = ImageDraw.Draw(img)
    for y in (0, 32):
        d.rectangle([2, y + 2, 61, y + 29], fill=(92, 98, 108, 255), outline=(50, 54, 60, 255))
        d.rectangle([8, y + 10, 55, y + 14], fill=(40, 160, 200, 255))
        for x in (6, 57):
            d.ellipse([x - 2, y + 4, x + 2, y + 8], fill=(150, 150, 160, 255))
    return noise(img, 8)


def stone():
    img = Image.new("RGBA", (64, 64), (60, 40, 36, 255))
    d = ImageDraw.Draw(img)
    for _ in range(26):
        x, y, r = random.randint(0, 63), random.randint(0, 63), random.randint(6, 14)
        c = random.randint(80, 120)
        d.ellipse([x - r, y - r, x + r, y + r], fill=(c, c // 2 + 10, c // 2, 255), outline=(40, 24, 20, 255))
    return noise(img, 12)


def floor(c1, c2):
    img = Image.new("RGBA", (64, 64), c1)
    d = ImageDraw.Draw(img)
    for y in range(0, 64, 16):
        for x in range(0, 64, 16):
            if (x + y) // 16 % 2:
                d.rectangle([x, y, x + 15, y + 15], fill=c2)
    return noise(img, 9)


def exit_sign():
    img = bricks((90, 90, 96), (50, 50, 54))
    d = ImageDraw.Draw(img)
    d.rectangle([10, 22, 53, 40], fill=(20, 20, 20, 255), outline=(200, 30, 30, 255))
    # «EXIT» пиксельными буквами
    letters = {"E": ["111", "100", "110", "100", "111"], "X": ["101", "101", "010", "101", "101"],
               "I": ["111", "010", "010", "010", "111"], "T": ["111", "010", "010", "010", "010"]}
    x = 14
    for ch in "EXIT":
        for r, line in enumerate(letters[ch]):
            for c, bit in enumerate(line):
                if bit == "1":
                    d.rectangle([x + c * 2, 26 + r * 2, x + c * 2 + 1, 27 + r * 2], fill=(255, 60, 60, 255))
        x += 9
    return img


def creature(body, eye, horns=False, ball=False):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if ball:
        d.ellipse([6, 6, 58, 58], fill=body, outline=(60, 0, 0, 255))
        d.ellipse([22, 18, 42, 36], fill=(240, 240, 240, 255))
        d.ellipse([28, 22, 36, 32], fill=eye)
        d.chord([16, 34, 48, 54], 0, 180, fill=(40, 0, 0, 255))
        for x in range(18, 48, 6):
            d.polygon([(x, 40), (x + 3, 40), (x + 1, 46)], fill=(250, 250, 230, 255))
        return noise(img, 6)
    d.rectangle([22, 24, 42, 46], fill=body)  # тело
    d.rectangle([18, 26, 22, 42], fill=body)
    d.rectangle([42, 26, 46, 42], fill=body)
    d.rectangle([24, 46, 30, 62], fill=body)  # ноги
    d.rectangle([34, 46, 40, 62], fill=body)
    d.rectangle([24, 8, 40, 24], fill=body)  # голова
    d.rectangle([27, 13, 30, 16], fill=eye)
    d.rectangle([34, 13, 37, 16], fill=eye)
    d.rectangle([28, 19, 36, 21], fill=(30, 10, 10, 255))
    if horns:
        d.polygon([(24, 8), (20, 0), (27, 8)], fill=(230, 220, 200, 255))
        d.polygon([(40, 8), (44, 0), (37, 8)], fill=(230, 220, 200, 255))
    for x in (16, 44):  # когти
        d.polygon([(x, 42), (x + 4, 42), (x + 2, 47)], fill=(240, 240, 220, 255))
    return noise(img, 8)


def corpse():
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([6, 46, 58, 62], fill=(120, 0, 0, 255))
    d.ellipse([16, 44, 44, 56], fill=(90, 60, 50, 255))
    return noise(img, 8)


def fireball():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([2, 2, 30, 30], fill=(255, 120, 20, 255))
    d.ellipse([8, 8, 24, 24], fill=(255, 220, 80, 255))
    return img


def pickup(kind):
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if kind == "medkit":
        d.rectangle([4, 10, 28, 30], fill=(235, 235, 235, 255), outline=(120, 120, 120, 255))
        d.rectangle([14, 13, 18, 27], fill=(220, 30, 30, 255))
        d.rectangle([9, 18, 23, 22], fill=(220, 30, 30, 255))
    elif kind == "bullets":
        for x in (6, 12, 18, 24):
            d.rectangle([x, 14, x + 3, 30], fill=(200, 170, 60, 255))
            d.rectangle([x, 10, x + 3, 14], fill=(180, 120, 60, 255))
    elif kind == "shells":
        d.rectangle([4, 16, 28, 30], fill=(120, 30, 30, 255), outline=(60, 10, 10, 255))
        for x in (7, 13, 19, 25):
            d.rectangle([x - 1, 10, x + 1, 16], fill=(220, 40, 40, 255))
    elif kind == "armor":
        d.polygon([(6, 10), (26, 10), (24, 28), (16, 31), (8, 28)], fill=(60, 180, 70, 255), outline=(20, 90, 30, 255))
    return noise(img, 5)


def gun(kind, w=96, h=64):
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    skin = (200, 150, 120, 255)
    metal, dark = (110, 112, 120, 255), (60, 62, 68, 255)
    if kind == "pistol":
        d.rectangle([42, 10, 54, 40], fill=metal, outline=dark)
        d.rectangle([40, 38, 56, 64], fill=skin)
    elif kind == "shotgun":
        d.rectangle([38, 0, 46, 40], fill=dark)
        d.rectangle([48, 0, 56, 40], fill=dark)
        d.rectangle([34, 34, 60, 48], fill=(120, 80, 40, 255))
        d.rectangle([36, 46, 58, 64], fill=skin)
    elif kind == "chaingun":
        for x in (36, 42, 48, 54):
            d.rectangle([x, 0, x + 4, 36], fill=metal, outline=dark)
        d.rectangle([32, 30, 62, 46], fill=dark)
        d.rectangle([34, 44, 60, 64], fill=skin)
    elif kind == "flash":
        d.ellipse([28, 0, 68, 34], fill=(255, 200, 60, 255))
        d.ellipse([38, 6, 58, 26], fill=(255, 255, 200, 255))
    return noise(img, 5) if kind != "flash" else img


if __name__ == "__main__":
    save(bricks((140, 60, 40), (70, 60, 55)), "wall_brick")
    save(tech(), "wall_tech")
    save(stone(), "wall_stone")
    save(exit_sign(), "exit")
    save(floor((70, 70, 70, 255), (82, 82, 82, 255)), "floor")
    save(floor((50, 50, 60, 255), (58, 58, 70, 255)), "ceiling")
    save(creature((150, 100, 60, 255), (255, 200, 0, 255), horns=True), "imp")
    save(creature((210, 110, 140, 255), (255, 40, 40, 255), horns=True), "demon")
    save(creature((200, 30, 30, 255), (40, 200, 40, 255), ball=True), "caco")
    save(corpse(), "corpse")
    save(fireball(), "fireball")
    for k in ("medkit", "bullets", "shells", "armor"):
        save(pickup(k), k)
    for k in ("pistol", "shotgun", "chaingun", "flash"):
        save(gun(k), "gun_" + k)
    save(gun("shotgun").resize((48, 32)), "pickup_shotgun")
    save(gun("chaingun").resize((48, 32)), "pickup_chaingun")
    print("Doom textures written to", OUT)
