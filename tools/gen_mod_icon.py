"""Иконка мода BlockPhone (fabric/src/main/resources/assets/blockphone/icon.png, 128×128).

Рисуется крупно (512×512) и уменьшается — края гладкие. Варианты:
  block   — тёмно-синяя плитка, телефон с акцентной рамкой, на экране — блок травы (изометрия из пикселей);
  minimal — акцентная плитка-градиент, белый контур телефона и кубик внутри (плоско, линиями);
  pixel   — пиксель-арт 32×32: телефон и сообщение-«облачко» с сердцем.
Запуск: python tools/gen_mod_icon.py [вариант] [--preview <папка>]  (без варианта — block; --preview — все три в папку).
"""
import os
import random
import sys

from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..", "fabric", "src", "main", "resources", "assets", "blockphone", "icon.png")
BIG = 512


def rgb(h):
    return (h >> 16 & 255, h >> 8 & 255, h & 255, 255)


def vgrad(w, h, top, bottom):
    img = Image.new("RGBA", (w, h))
    t, b = rgb(top), rgb(bottom)
    for y in range(h):
        k = y / max(1, h - 1)
        img.paste(tuple(int(t[i] + (b[i] - t[i]) * k) for i in range(4)), (0, y, w, y + 1))
    return img


def diag_grad(w, h, a, b):
    img = Image.new("RGBA", (w, h))
    ca, cb = rgb(a), rgb(b)
    px = img.load()
    for y in range(h):
        for x in range(w):
            k = (x + y) / (w + h - 2)
            px[x, y] = tuple(int(ca[i] + (cb[i] - ca[i]) * k) for i in range(4))
    return img


def rounded_mask(w, h, r):
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], radius=r, fill=255)
    return m


def tile(img, top, bottom, border):
    bg = vgrad(BIG, BIG, top, bottom)
    img.paste(bg, (0, 0), rounded_mask(BIG, BIG, 104))
    ImageDraw.Draw(img).rounded_rectangle([4, 4, BIG - 5, BIG - 5], radius=100, outline=rgb(border), width=8)


def grass_block(img, cx, cy, size):
    """Изометрический блок травы из «пикселей» 8×8 на каждой грани."""
    rnd = random.Random(7)
    n = 8
    s = size / n  # шаг пикселя по оси
    top_cols = [0x6CC24A, 0x5DB33E, 0x7ED957, 0x58A83A]
    dirt = [0x8B5E3C, 0x7A5133, 0x9C6B45, 0x6E482D]
    side_grass = [0x5DB33E, 0x4E9A33]
    d = ImageDraw.Draw(img)
    # Верх: ромб. Точки: левая, верх, правая, низ.
    def top_quad(i, j):
        # i — вдоль правой оси, j — вдоль левой
        x0 = cx + (i - j) * s * 0.866
        y0 = cy - size * 0.5 + (i + j) * s * 0.5
        return [(x0, y0), (x0 + s * 0.866, y0 + s * 0.5), (x0, y0 + s), (x0 - s * 0.866, y0 + s * 0.5)]

    for j in range(n):
        for i in range(n):
            d.polygon(top_quad(i, j), fill=rgb(rnd.choice(top_cols)))
    # Левая грань (темнее) и правая грань.
    for face, shade in ((-1, 0.72), (1, 0.86)):
        for row in range(n):
            for col in range(n):
                if face < 0:
                    x0 = cx - size * 0.866 + col * s * 0.866
                    y0 = cy + col * s * 0.5 + row * s
                    pts = [(x0, y0), (x0 + s * 0.866, y0 + s * 0.5), (x0 + s * 0.866, y0 + s * 1.5), (x0, y0 + s)]
                else:
                    x0 = cx + col * s * 0.866
                    y0 = cy + size * 0.5 - col * s * 0.5 + row * s
                    pts = [(x0, y0), (x0 + s * 0.866, y0 - s * 0.5), (x0 + s * 0.866, y0 + s * 0.5), (x0, y0 + s)]
                base = rnd.choice(side_grass) if row < 2 or (row == 2 and rnd.random() < 0.5) else rnd.choice(dirt)
                c = rgb(base)
                d.polygon(pts, fill=(int(c[0] * shade), int(c[1] * shade), int(c[2] * shade), 255))


def variant_block():
    img = Image.new("RGBA", (BIG, BIG), (0, 0, 0, 0))
    tile(img, 0x1C2746, 0x0C1224, 0x2E3C66)
    d = ImageDraw.Draw(img)
    # Телефон: корпус с акцентной рамкой и тенью.
    x0, y0, x1, y1 = 150, 56, 362, 456
    d.rounded_rectangle([x0 + 10, y0 + 16, x1 + 10, y1 + 16], radius=46, fill=(0, 0, 0, 90))
    d.rounded_rectangle([x0, y0, x1, y1], radius=46, fill=rgb(0x4F8CFF))
    d.rounded_rectangle([x0 + 12, y0 + 12, x1 - 12, y1 - 12], radius=36, fill=rgb(0x0A0F1C))
    screen = diag_grad(x1 - x0 - 44, y1 - y0 - 44, 0x2B4C9B, 0x6A3FB5)
    img.paste(screen, (x0 + 22, y0 + 22), rounded_mask(screen.width, screen.height, 26))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([226, 84, 286, 98], radius=7, fill=rgb(0x0A0F1C))  # «чёлка»
    grass_block(img, 256, 252, 92)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([216, 414, 296, 424], radius=5, fill=(255, 255, 255, 170))  # полоска «домой»
    return img


def variant_minimal():
    img = Image.new("RGBA", (BIG, BIG), (0, 0, 0, 0))
    bg = diag_grad(BIG, BIG, 0x4F8CFF, 0x7B4FE0)
    img.paste(bg, (0, 0), rounded_mask(BIG, BIG, 112))
    d = ImageDraw.Draw(img)
    w = 26
    d.rounded_rectangle([156, 70, 356, 442], radius=52, outline=(255, 255, 255, 255), width=w)
    d.rounded_rectangle([226, 104, 286, 118], radius=7, fill=(255, 255, 255, 255))
    # Кубик линиями.
    cx, cy, r = 256, 262, 66
    top = [(cx, cy - r), (cx + r * 0.866, cy - r * 0.5), (cx, cy), (cx - r * 0.866, cy - r * 0.5)]
    d.polygon(top, fill=(255, 255, 255, 255))
    left = [(cx - r * 0.866, cy - r * 0.5), (cx, cy), (cx, cy + r), (cx - r * 0.866, cy + r * 0.5)]
    right = [(cx, cy), (cx + r * 0.866, cy - r * 0.5), (cx + r * 0.866, cy + r * 0.5), (cx, cy + r)]
    d.polygon(left, fill=(255, 255, 255, 150))
    d.polygon(right, fill=(255, 255, 255, 205))
    return img


PIXEL = [
    "................................",
    "................................",
    "................................",
    ".......##################.......",
    "......#oooooooooooooooooo#......",
    "......#o################o#......",
    "......#o#######..#######o#......",
    "......#o################o#......",
    "......#o#SSSSSSSSSSSSSS#o#......",
    "......#o#SSSHHSSSSHHSSS#o#......",
    "......#o#SSHHHHSSHHHHSS#o#......",
    "......#o#SSHHHHHHHHHHSS#o#......",
    "......#o#SSHHHHHHHHHHSS#o#......",
    "......#o#SSSHHHHHHHHSSS#o#......",
    "......#o#SSSSHHHHHHSSSS#o#......",
    "......#o#SSSSSHHHHSSSSS#o#......",
    "......#o#SSSSSSHHSSSSSS#o#......",
    "......#o#SSSSSSSSSSSSSS#o#......",
    "......#o#GGGGGGGGGGGGGG#o#......",
    "......#o#GgGGgGGGgGGgGG#o#......",
    "......#o#DDdDDDdDDDdDDD#o#......",
    "......#o#DdDDDdDDdDDDdD#o#......",
    "......#o#DDDdDDDDDdDDDD#o#......",
    "......#o#DdDDDDdDDDDdDD#o#......",
    "......#o################o#......",
    "......#o#####wwwwww#####o#......",
    "......#o################o#......",
    "......#oooooooooooooooooo#......",
    ".......##################.......",
    "................................",
    "................................",
    "................................",
]
PAL = {"#": 0x0A0F1C, "o": 0x4F8CFF, ".": None, "S": 0x2B4C9B, "H": 0xF0384F, "G": 0x5DB33E, "g": 0x7ED957,
       "D": 0x8B5E3C, "d": 0x6E482D, "w": 0xE8ECF5}


def variant_pixel():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    px = img.load()
    for y, row in enumerate(PIXEL):
        for x, ch in enumerate(row):
            c = PAL.get(ch)
            if c is not None:
                px[x, y] = rgb(c)
    big = img.resize((BIG, BIG), Image.NEAREST)
    out = Image.new("RGBA", (BIG, BIG), (0, 0, 0, 0))
    tile(out, 0x1C2746, 0x0C1224, 0x2E3C66)
    out.alpha_composite(big)
    return out


VARIANTS = {"block": variant_block, "minimal": variant_minimal, "pixel": variant_pixel}


def small(img):
    return img.resize((128, 128), Image.LANCZOS)


def main():
    args = sys.argv[1:]
    if "--preview" in args:
        folder = args[args.index("--preview") + 1]
        os.makedirs(folder, exist_ok=True)
        sheet = Image.new("RGBA", (3 * 300 + 40, 340), (40, 44, 52, 255))
        for i, (name, fn) in enumerate(VARIANTS.items()):
            img = fn()
            small(img).save(os.path.join(folder, f"icon_{name}.png"))
            sheet.alpha_composite(img.resize((256, 256), Image.LANCZOS), (20 + i * 300 + 12, 20))
            sheet.alpha_composite(small(img).resize((64, 64), Image.LANCZOS), (20 + i * 300 + 108, 270))
        sheet.save(os.path.join(folder, "icons_sheet.png"))
        print("preview:", folder)
        return
    name = args[0] if args else "block"
    small(VARIANTS[name]()).save(OUT)
    print("icon:", name, "->", OUT)


if __name__ == "__main__":
    main()
