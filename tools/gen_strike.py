"""Графика примера-игры «Block Strike» (docs/examples/scripts/blockstrike): текстуры карт (9 карт), скины игроков (развёртка
как у скина Minecraft 64×64), оружие от первого лица, иконки оружия, эффекты, радары карт (из maps.lua) и иконка.
Запуск из корня репозитория: python tools/gen_strike.py
"""
import glob
import json
import math
import os
import random
import re
import subprocess

from PIL import Image, ImageDraw, ImageFilter

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


# --- Текстуры новых карт: Ледник, Офис, Храм, Озеро ---

def snowstone():
    img = bricks((168, 176, 186), (120, 126, 136), 16, 8, 8)
    d = ImageDraw.Draw(img)
    for x in range(32):  # снег на верху каждого ряда кладки
        for y0 in (0, 8, 16, 24):
            h = 1 + (x * 7 + y0) % 3 // 2
            d.line([x, y0 + 1, x, y0 + h], fill=(236, 242, 248, 255))
    return img


def wood_dark():
    c = (92, 66, 46)
    img = Image.new("RGBA", (32, 32))
    d = ImageDraw.Draw(img)
    for col in range(4):
        k = 0.85 + random.random() * 0.25
        d.rectangle([col * 8, 0, col * 8 + 7, 31], fill=tone(c, k))
        d.line([col * 8 + 7, 0, col * 8 + 7, 31], fill=tone(c, 0.55))
    return noise(img, 7)


def snow():
    img = base((232, 238, 244), 6)
    px = img.load()
    for _ in range(50):
        x, y = random.randrange(32), random.randrange(32)
        px[x, y] = (206, 216, 228, 255)
    return img


def ice():
    img = base((168, 206, 232), 5)
    d = ImageDraw.Draw(img)
    for _ in range(5):
        x, y = random.randrange(32), random.randrange(32)
        d.line([x, y, x + random.randint(-8, 8), y + random.randint(-8, 8)], fill=(214, 236, 250, 255))
    return img


def plaster():
    img = base((214, 208, 196), 5)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 26, 31, 31], fill=(96, 78, 62, 255))       # плинтус
    d.line([0, 26, 31, 26], fill=(130, 108, 88, 255))
    d.line([0, 4, 31, 4], fill=(196, 190, 178, 255))
    return noise(img, 3)


def wood_panel():
    c = (134, 94, 60)
    img = Image.new("RGBA", (32, 32), tone(c, 0.6))
    d = ImageDraw.Draw(img)
    for x0 in (1, 17):
        d.rectangle([x0, 1, x0 + 13, 30], fill=tone(c, 1.0))
        d.rectangle([x0 + 3, 4, x0 + 10, 27], fill=tone(c, 0.88))
        d.line([x0 + 3, 4, x0 + 10, 4], fill=tone(c, 0.7))
    return noise(img, 5)


def carpet():
    img = base((86, 66, 92), 9)
    d = ImageDraw.Draw(img)
    for i in range(0, 32, 8):
        d.point([i + 2, i % 16 + 3], fill=(120, 96, 128, 255))
        d.point([(i + 13) % 32, (i * 3) % 32], fill=(66, 50, 72, 255))
    return img


def office_tiles():
    return tiles((196, 198, 194), (178, 182, 180), 16)


def ceiling_office():
    c = (196, 196, 190)
    img = base(c, 4)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], outline=tone(c, 0.75))
    d.rectangle([4, 12, 27, 19], fill=(250, 250, 236, 255))
    return img


def temple_stone():
    img = Image.new("RGBA", (32, 32), (88, 92, 74, 255))
    d = ImageDraw.Draw(img)
    for row in range(4):
        off = 8 if row % 2 else 0
        for col in range(-1, 3):
            x = col * 16 + off
            k = 0.85 + random.random() * 0.3
            d.rectangle([x + 1, row * 8 + 1, x + 15, row * 8 + 7], fill=tone((150, 146, 116), k))
    px = img.load()
    for _ in range(90):  # мох
        x, y = random.randrange(32), random.randrange(32)
        px[x, y] = (86, 120, 60, 255) if random.random() < 0.7 else (110, 140, 70, 255)
    return noise(img, 6)


def temple_glyph():
    img = temple_stone()
    d = ImageDraw.Draw(img)
    d.rectangle([6, 6, 25, 25], fill=(130, 124, 96, 255), outline=(80, 76, 58, 255))
    d.rectangle([11, 11, 20, 20], outline=(80, 76, 58, 255))
    d.line([6, 15, 11, 15], fill=(80, 76, 58, 255))
    d.line([20, 15, 25, 15], fill=(80, 76, 58, 255))
    d.point([15, 15], fill=(160, 60, 40, 255))
    return img


def temple_floor():
    return tiles((150, 144, 118), (136, 130, 104), 16)


def moss():
    img = base((84, 118, 62), 14)
    px = img.load()
    for _ in range(60):
        x, y = random.randrange(32), random.randrange(32)
        px[x, y] = (120, 112, 92, 255)
    return img


def logs():
    c = (116, 84, 52)
    img = Image.new("RGBA", (32, 32))
    d = ImageDraw.Draw(img)
    for row in range(4):
        k = 0.85 + random.random() * 0.25
        d.rectangle([0, row * 8, 31, row * 8 + 7], fill=tone(c, k))
        d.line([0, row * 8, 31, row * 8], fill=tone(c, 1.25))
        d.line([0, row * 8 + 7, 31, row * 8 + 7], fill=tone(c, 0.5))
    return noise(img, 8)


def dock():
    c = (150, 116, 74)
    img = Image.new("RGBA", (32, 32))
    d = ImageDraw.Draw(img)
    for col in range(4):
        k = 0.85 + random.random() * 0.25
        d.rectangle([col * 8, 0, col * 8 + 7, 31], fill=tone(c, k))
        d.line([col * 8 + 7, 0, col * 8 + 7, 31], fill=(40, 34, 28, 255))
        d.point([col * 8 + 3, 4], fill=(70, 70, 70, 255))
        d.point([col * 8 + 3, 27], fill=(70, 70, 70, 255))
    return noise(img, 6)


# --- Иконки оружия для магазина и оружия на земле: рисуются из 3D-моделей viewmodel.lua ---

def load_models():
    """Модели из viewmodel.lua через LuaJ (jar из кэша Gradle; tools/strike_models.lua печатает JSON)."""
    jars = glob.glob(os.path.join(os.path.expanduser("~"), ".gradle", "caches", "modules-2", "files-2.1", "org.luaj",
                                  "luaj-jse", "*", "*", "luaj-jse-*.jar"))
    if not jars:
        raise SystemExit("LuaJ не найден в кэше Gradle — сначала соберите мод (./gradlew build)")
    out = subprocess.run(["java", "-cp", jars[0], "lua", os.path.join(ROOT, "tools", "strike_models.lua")], cwd=APP,
                         capture_output=True, text=True, encoding="utf-8", check=True).stdout
    return json.loads(out)


def part_corners(b):
    x1, y1, z1, x2, y2, z2, col, tilt = b
    cy, cz = (y1 + y2) / 2, (z1 + z2) / 2
    c, s_ = math.cos(math.radians(tilt)), math.sin(math.radians(tilt))

    def P(x, y, z):
        dy, dz = y - cy, z - cz
        return (x, cy + dy * c + dz * s_, cz + dz * c - dy * s_)
    # порядок как в viewmodel.lua hexa: перед (z+) лв, пв, пн, лн; зад — так же
    return [P(x1, y2, z2), P(x2, y2, z2), P(x2, y1, z2), P(x1, y1, z2), P(x1, y2, z1), P(x2, y2, z1), P(x2, y1, z1), P(x1, y1, z1)]


FACES = [(0, 1, 2, 3), (5, 4, 7, 6), (4, 0, 3, 7), (1, 5, 6, 2), (4, 5, 1, 0), (3, 2, 6, 7)]


def render_icon(parts, w=120, h=48, ss=4):
    """Вид сбоку справа (дуло влево), камера чуть сверху: многоугольники граней по глубине, свет, обводка."""
    yaw, pitch = math.radians(-14), math.radians(-24)

    def view(p):
        x, y, z = p
        x, z = x * math.cos(yaw) - z * math.sin(yaw), x * math.sin(yaw) + z * math.cos(yaw)
        x, y = x * math.cos(pitch) - y * math.sin(pitch), x * math.sin(pitch) + y * math.cos(pitch)
        return x, y, z  # смотрим из +X: экран x = -z, экран y = -y, глубина = x
    light = (0.55, 0.75, -0.35)
    ln = math.sqrt(sum(v * v for v in light))
    light = tuple(v / ln for v in light)
    polys = []
    for b in parts:
        corners = [view(p) for p in part_corners(b)]
        col = PALETTE[int(b[6])]
        for f in FACES:
            q = [corners[i] for i in f]
            ax, ay, az = (q[1][k] - q[0][k] for k in range(3))
            bx, by, bz = (q[2][k] - q[0][k] for k in range(3))
            nx, ny, nz = ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx
            nl = math.sqrt(nx * nx + ny * ny + nz * nz) or 1
            nx, ny, nz = nx / nl, ny / nl, nz / nl
            if nx <= 0.001:
                continue
            k = 0.5 + 0.6 * max(0.0, nx * light[0] + ny * light[1] + nz * light[2])
            depth = sum(p[0] for p in q) / 4
            polys.append((depth, [(-p[2], -p[1]) for p in q], tone(col, k)))
    xs = [pt[0] for _, pts, _ in polys for pt in pts]
    ys = [pt[1] for _, pts, _ in polys for pt in pts]
    minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
    pad = 0.08
    scale = min(w * (1 - pad * 2) / (maxx - minx), h * (1 - pad * 2) / (maxy - miny)) * ss
    ox = (w * ss - (maxx - minx) * scale) / 2 - minx * scale
    oy = (h * ss - (maxy - miny) * scale) / 2 - miny * scale
    img = Image.new("RGBA", (w * ss, h * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for _, pts, col in sorted(polys, key=lambda t: t[0]):
        d.polygon([(ox + x * scale, oy + y * scale) for x, y in pts], fill=col, outline=tone(col, 0.8))
    # обводка: тёмная подложка по расширенному силуэту
    alpha = img.getchannel("A").filter(ImageFilter.MaxFilter(ss * 2 + 1))
    back = Image.new("RGBA", img.size, (12, 12, 14, 0))
    back.putalpha(alpha.point(lambda a: 210 if a > 0 else 0))
    back.alpha_composite(img)
    return back.resize((w, h), Image.LANCZOS)


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


# Палитра 3D-оружия от первого лица (viewmodel.lua, таблица C — тот же порядок): клетки 8×8 на картинке 64×64.
PALETTE = [(30, 31, 34), (52, 54, 58), (92, 96, 102), (176, 180, 186), (140, 86, 44), (98, 58, 30), (78, 98, 62),
           (96, 104, 72), (176, 156, 116), (64, 72, 64), (40, 38, 36), (128, 104, 66), (44, 64, 102), (196, 150, 112),
           (70, 96, 60), (156, 162, 170), (92, 102, 112), (112, 98, 72), (40, 210, 90), (200, 40, 30), (150, 72, 32),
           (70, 120, 150), (206, 210, 216), (232, 232, 232), (84, 54, 36), (194, 196, 200), (58, 66, 58), (220, 180, 40)]


def palette():
    img = Image.new("RGBA", (64, 64), (255, 0, 255, 255))
    px = img.load()
    for i, c in enumerate(PALETTE):
        cx, cy = (i % 8) * 8, (i // 8) * 8
        for y in range(8):
            for x in range(8):
                n = random.randint(-6, 6)
                px[cx + x, cy + y] = (clamp(c[0] + n), clamp(c[1] + n), clamp(c[2] + n), 255)
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
        # Отражено по X: в мире +X слева, если смотреть сверху с Z вниз (как в hud.lua H.radarX).
        for z, row in enumerate(rows):
            for x, ch in enumerate(row):
                c = RADAR_COLORS.get(ch, (128, 132, 128))
                rx = w - 1 - x
                d.rectangle([rx * k, z * k, rx * k + k - 1, z * k + k - 1], fill=c + (255,))
        for s in "AB":
            cells = [(w - 1 - x, z) for z, row in enumerate(rows) for x, ch in enumerate(row) if ch == s]
            if not cells:
                continue
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



# --- Текстуры в высоком качестве (64×64): шум нескольких масштабов, швы, сколы, тени; для всех карт ---

def _np():
    import numpy as np
    return np


def value_noise(size, cells, seed):
    """Плавный шум: случайная сетка cells×cells, билинейная интерполяция, бесшовный по краям."""
    np = _np()
    rng = np.random.default_rng(seed)
    grid = rng.random((cells, cells))
    t = np.arange(size) * cells / size
    i0 = np.floor(t).astype(int) % cells
    i1 = (i0 + 1) % cells
    f = t - np.floor(t)
    f = f * f * (3 - 2 * f)
    rows = grid[i0][:, i0] * (1 - f)[None, :] + grid[i0][:, i1] * f[None, :]
    rows2 = grid[i1][:, i0] * (1 - f)[None, :] + grid[i1][:, i1] * f[None, :]
    return rows * (1 - f)[:, None] + rows2 * f[:, None]


def fractal(size, seed, octaves=((4, 0.5), (8, 0.3), (16, 0.15), (32, 0.05))):
    np = _np()
    out = np.zeros((size, size))
    for k, (cells, w) in enumerate(octaves):
        out += value_noise(size, cells, seed * 31 + k) * w
    return out / sum(w for _, w in octaves)


def from_array(rgb):
    np = _np()
    a = np.clip(rgb, 0, 255).astype("uint8")
    alpha = np.full(a.shape[:2] + (1,), 255, dtype="uint8")
    return Image.fromarray(np.concatenate([a, alpha], axis=2), "RGBA")


def colored(color, n, contrast):
    np = _np()
    c = np.array(color, dtype=float)
    return c[None, None, :] * (1 + (n[:, :, None] - 0.5) * contrast)


def hd_stone_blocks(color, mortar, rows=4, cols=2, seed=1, chips=True, size=64):
    """Кладка из блоков: у каждого свой оттенок, светлый верхний край, тёмный нижний, шум, сколы."""
    np = _np()
    rng = random.Random(seed)
    n = fractal(size, seed)
    img = colored(mortar, n, 0.25)
    bh = size // rows
    for r in range(rows):
        bw = size // cols
        off = (bw // 2) if r % 2 else 0
        for c in range(-1, cols + 1):
            x0 = c * bw + off
            k = 0.86 + rng.random() * 0.26
            tint = np.array(color, dtype=float) * k
            for y in range(r * bh + 1, r * bh + bh - 1):
                for x in range(x0 + 1, x0 + bw - 1):
                    xx = x % size
                    edge = 1.0
                    if y == r * bh + 1:
                        edge = 1.16
                    elif y >= r * bh + bh - 2:
                        edge = 0.78
                    elif x == x0 + 1:
                        edge = 1.08
                    elif x >= x0 + bw - 2:
                        edge = 0.85
                    img[y, xx] = tint * (1 + (n[y, xx] - 0.5) * 0.35) * edge
    if chips:
        for _ in range(10):
            x, y = rng.randrange(size), rng.randrange(size)
            img[y:y + 2, x:x + 2] *= 0.72
    return from_array(img)


def hd_sand(seed=3, color=(222, 198, 148), size=64):
    np = _np()
    n = fractal(size, seed, ((4, 0.4), (16, 0.3), (32, 0.2), (64, 0.1)))
    img = colored(color, n, 0.22)
    rng = np.random.default_rng(seed)
    grains = rng.random((size, size)) < 0.06
    img[grains] *= 0.85
    ripples = (np.sin(np.arange(size) / size * math.pi * 6 + n * 3) * 0.5 + 0.5)[:, :]
    img *= (0.96 + ripples[:, :, None] * 0.06)
    return from_array(img)


def hd_plaster(color=(214, 200, 176), seed=5, size=64):
    np = _np()
    n = fractal(size, seed)
    img = colored(color, n, 0.18)
    rng = random.Random(seed)
    # пятна осыпавшейся штукатурки с кирпичом под ней
    for _ in range(2):
        cx, cy, r = rng.randrange(size), rng.randrange(size), rng.randrange(5, 10)
        for y in range(cy - r, cy + r):
            for x in range(cx - r, cx + r):
                if (x - cx) ** 2 + ((y - cy) * 1.4) ** 2 < r * r * (0.6 + n[y % size, x % size] * 0.6):
                    yy, xx = y % size, x % size
                    brick = (168, 98, 70) if (yy // 5 + (xx // 10 if (yy // 5) % 2 else (xx + 5) // 10)) % 7 else (130, 120, 110)
                    img[yy, xx] = np.array(brick) * (0.9 + n[yy, xx] * 0.2)
    # грязь снизу
    grad = np.concatenate([np.ones(size // 2), np.linspace(1.0, 0.84, size - size // 2)])
    img *= grad[:, None, None]
    return from_array(img)


def hd_planks(color=(150, 104, 62), seed=7, size=64, boards=4):
    np = _np()
    img = np.zeros((size, size, 3))
    bw = size // boards
    rng = random.Random(seed)
    for b in range(boards):
        k = 0.85 + rng.random() * 0.3
        grain = value_noise(size, 4, seed + b)[:, :1] * 0 + 0
        for x in range(b * bw, b * bw + bw):
            for y in range(size):
                g = math.sin((x * 0.9 + math.sin(y * 0.11 + b) * 3) * 1.3) * 0.08
                img[y, x] = np.array(color) * k * (1 + g)
        img[:, b * bw] *= 0.55
        img[:, b * bw + bw - 1] *= 0.8
        for _ in range(2):
            ny = rng.randrange(size)
            nx = b * bw + rng.randrange(2, bw - 2)
            img[ny:ny + 2, nx:nx + 2] *= 0.6
    return from_array(img * (0.92 + fractal(size, seed)[:, :, None] * 0.16))


def hd_tiles(c1, c2, seed=9, size=64, n=4):
    np = _np()
    noise_ = fractal(size, seed)
    img = np.zeros((size, size, 3))
    t = size // n
    rng = random.Random(seed)
    for ty in range(n):
        for tx in range(n):
            c = np.array(c1 if (tx + ty) % 2 == 0 else c2, dtype=float) * (0.92 + rng.random() * 0.16)
            img[ty * t:(ty + 1) * t, tx * t:(tx + 1) * t] = c
            img[ty * t, tx * t:(tx + 1) * t] *= 1.12
            img[ty * t:(ty + 1) * t, tx * t] *= 1.08
            img[(ty + 1) * t - 1, tx * t:(tx + 1) * t] *= 0.7
            img[ty * t:(ty + 1) * t, (tx + 1) * t - 1] *= 0.75
    img *= (0.9 + noise_[:, :, None] * 0.2)
    return from_array(img)


def hd_cobble(color=(132, 128, 120), seed=11, size=64):
    """Брусчатка: ячейки Вороного — камни разного оттенка с тёмными швами."""
    np = _np()
    rng = np.random.default_rng(seed)
    pts = rng.random((26, 2)) * size
    shades = 0.8 + rng.random(26) * 0.35
    ys, xs = np.mgrid[0:size, 0:size]
    best = np.full((size, size), 1e9)
    second = np.full((size, size), 1e9)
    idx = np.zeros((size, size), dtype=int)
    for i, (px, py) in enumerate(pts):
        for ox in (-size, 0, size):
            for oy in (-size, 0, size):
                d = np.hypot(xs - px - ox, ys - py - oy)
                closer = d < best
                second = np.where(closer, best, np.minimum(second, d))
                idx = np.where(closer, i, idx)
                best = np.where(closer, d, best)
    edge = np.clip((second - best) / 3.0, 0, 1)
    n = fractal(size, seed)
    img = np.array(color, dtype=float)[None, None, :] * shades[idx][:, :, None] * (0.85 + n[:, :, None] * 0.3)
    img *= (0.45 + 0.55 * edge)[:, :, None]
    return from_array(img)


def hd_crate(seed=13, size=64, color=(150, 108, 60)):
    np = _np()
    img = np.array(hd_planks(color, seed, size, 4).convert("RGB"), dtype=float)
    # рамка и диагональ
    frame = (112, 78, 42)
    img[:5, :] = frame
    img[-5:, :] = frame
    img[:, :5] = frame
    img[:, -5:] = frame
    for i in range(size):
        for w in range(-2, 3):
            j = i + w
            if 5 <= j < size - 5 and 5 <= i < size - 5:
                img[i, j] = np.array(frame) * 1.05
    img[0, :] *= 1.2
    img[:, 0] *= 1.15
    img[-1, :] *= 0.6
    img[:, -1] *= 0.6
    return from_array(img)


def hd_site(base_color, stripe, seed=15, size=64):
    np = _np()
    n = fractal(size, seed)
    img = colored(base_color, n, 0.2)
    ys, xs = np.mgrid[0:size, 0:size]
    band = ((xs + ys) // 8) % 2 == 0
    worn = n > 0.38
    sel = band & worn
    img[sel] = np.array(stripe, dtype=float) * (0.85 + n[sel][:, None] * 0.3)
    img[:3, :] *= 0.8
    img[:, :3] *= 0.8
    return from_array(img)


def hd_world_textures():
    save(hd_stone_blocks((214, 188, 138), (176, 150, 106), 4, 2, 21), "sandstone")
    save(hd_stone_blocks((200, 168, 116), (150, 126, 86), 8, 4, 22), "sandbrick")
    save(hd_sand(), "sand")
    save(hd_tiles((190, 162, 120), (168, 140, 102)), "tiles")
    save(hd_plaster(), "plaster")
    save(hd_planks((122, 84, 52), 31, 64, 6), "wood_panel")
    save(hd_planks(), "planks")
    save(hd_stone_blocks((150, 144, 126), (104, 100, 90), 4, 2, 23), "temple_stone")
    save(hd_cobble(), "cobble")
    save(hd_sand(25, (170, 150, 112)), "path")
    save(hd_stone_blocks((150, 70, 54), (96, 90, 84), 8, 4, 24, chips=False), "brick")
    save(hd_crate(), "crate")
    save(hd_site((214, 190, 140), (190, 92, 70)), "site_sand")
    save(hd_site((150, 144, 118), (170, 70, 40), 16), "site_temple")


# --- Текстуры в стиле Dust II (карта Shortdust) ---

def d2_wall(size=64, seed=41):
    """Штукатурка песочного цвета: пятна, тёмный низ, редкие трещины и выбоины с камнем под ней."""
    np = _np()
    n = fractal(size, seed)
    n2 = fractal(size, seed + 1, ((2, 0.6), (4, 0.4)))
    img = colored((214, 190, 146), n, 0.2) * (0.92 + n2[:, :, None] * 0.14)
    rng = random.Random(seed)
    for _ in range(3):
        x, y = rng.randrange(size), rng.randrange(size)
        for k in range(rng.randrange(6, 14)):
            x, y = (x + rng.choice((-1, 0, 1))) % size, (y + 1) % size
            img[y, x] *= 0.72
    for _ in range(2):
        cx, cy, r = rng.randrange(size), rng.randrange(size), rng.randrange(3, 6)
        for y in range(cy - r, cy + r):
            for x in range(cx - r, cx + r):
                if (x - cx) ** 2 + (y - cy) ** 2 < r * r:
                    img[y % size, x % size] = np.array((168, 146, 108)) * (0.85 + n[y % size, x % size] * 0.3)
    grad = np.concatenate([np.ones(size * 2 // 3), np.linspace(1.0, 0.8, size - size * 2 // 3)])
    return from_array(img * grad[:, None, None])


def d2_door(size=64, seed=43):
    """Синие деревянные двери (как двери мида): вертикальные доски, рама, облезшая краска, железные петли."""
    np = _np()
    n = fractal(size, seed)
    img = np.zeros((size, size, 3))
    blue = np.array((58, 104, 150), dtype=float)
    wood = np.array((140, 104, 66), dtype=float)
    for x in range(size):
        board = x // 8
        k = 0.88 + (board * 37 % 7) / 30
        for y in range(size):
            grain = math.sin((y * 0.7 + math.sin(x * 0.4 + board) * 2) * 0.9) * 0.05
            c = blue * k * (1 + grain)
            if n[y, x] > 0.74:
                c = wood * (0.9 + n[y, x] * 0.2)  # краска облезла
            img[y, x] = c
        if x % 8 == 0:
            img[:, x] *= 0.6
    img[:4, :] = np.array((92, 70, 46))
    img[-4:, :] = np.array((92, 70, 46))
    img[:, :3] = np.array((92, 70, 46))
    img[:, -3:] = np.array((92, 70, 46))
    for hy in (12, 48):
        img[hy:hy + 4, 3:14] = np.array((52, 50, 48))
        img[hy, 3:14] = np.array((96, 92, 88))
    return from_array(img)


def d2_dirt(size=64, seed=45):
    """Утоптанный песок с камешками и следами."""
    np = _np()
    n = fractal(size, seed, ((4, 0.4), (8, 0.3), (32, 0.2), (64, 0.1)))
    img = colored((206, 178, 128), n, 0.26)
    rng = random.Random(seed)
    for _ in range(26):
        x, y, r = rng.randrange(size), rng.randrange(size), rng.choice((1, 1, 2))
        c = np.array((150, 134, 110)) * (0.85 + rng.random() * 0.3)
        img[y:y + r, x:x + r] = c
        if y + r < size:
            img[y + r, x:x + r] *= 0.75
    return from_array(img)


def d2_pave(size=64, seed=47, color=(190, 172, 140)):
    """Брусчатка из крупных прямоугольных плит разного размера, швы с песком."""
    np = _np()
    n = fractal(size, seed)
    img = np.zeros((size, size, 3))
    rng = random.Random(seed)
    joint = np.array((150, 128, 92), dtype=float)
    y = 0
    while y < size:
        h = rng.choice((10, 12, 16)) if size - y > 16 else size - y
        x = rng.randrange(0, 12)
        while x < size + 12:
            w = rng.choice((14, 18, 22))
            k = 0.86 + rng.random() * 0.24
            for yy in range(y, min(size, y + h)):
                for xx in range(x, x + w):
                    img[yy, xx % size] = np.array(color) * k * (0.9 + n[yy, xx % size] * 0.2)
            for xx in range(x, x + w):
                img[y, xx % size] = joint
                if y + 1 < size:
                    img[y + 1, xx % size] *= 1.08
            for yy in range(y, min(size, y + h)):
                img[yy, x % size] = joint
            x += w
        y += h
    return from_array(img)


def d2_site(size=64):
    """Плиты точки с полустёртой оранжевой краской."""
    np = _np()
    img = np.array(d2_pave(size, 49, (196, 176, 142)).convert("RGB"), dtype=float)
    n = fractal(size, 50)
    ys, xs = np.mgrid[0:size, 0:size]
    band = ((xs + ys) // 10) % 3 == 0
    sel = band & (n > 0.42)
    img[sel] = img[sel] * 0.35 + np.array((214, 104, 52)) * 0.65
    return from_array(img)


def d2_crate(size=64, seed=51):
    """Классический ящик CS: светлые доски, тёмная рама по краям и диагональ."""
    np = _np()
    img = np.array(hd_planks((188, 150, 92), seed, size, 5).convert("RGB"), dtype=float)
    frame = np.array((104, 74, 40), dtype=float)
    b = 6
    img[:b, :] = frame
    img[-b:, :] = frame
    img[:, :b] = frame
    img[:, -b:] = frame
    for i in range(b, size - b):
        for w in range(-3, 4):
            j = i + w
            if b <= j < size - b:
                img[i, j] = frame * 1.08
    for (cx, cy) in ((3, 3), (size - 4, 3), (3, size - 4), (size - 4, size - 4)):
        img[cy - 1:cy + 1, cx - 1:cx + 1] = (60, 56, 52)
    img[0, :] *= 1.25
    img[:, 0] *= 1.15
    img[-1, :] *= 0.55
    img[:, -1] *= 0.6
    return from_array(img * (0.94 + fractal(size, seed + 3)[:, :, None] * 0.12))


def d2_block(size=64):
    return hd_stone_blocks((222, 200, 156), (178, 156, 116), 2, 1, 53)


def sd_mosque(size=64, seed=55):
    """Стена мечети: крупный светлый песчаник и полоса синего орнамента (решётка) сверху каждого ряда."""
    np = _np()
    img = np.array(hd_stone_blocks((222, 198, 150), (188, 164, 120), 2, 1, seed).convert("RGB"), dtype=float)
    band = 12
    blue = np.array((52, 78, 128), dtype=float)
    gold = np.array((214, 188, 120), dtype=float)
    for y in range(band):
        for x in range(size):
            u, v = x % 8, y % 8
            lattice = (u == v or u == 7 - v or u == 0 or v == 0)
            img[y, x] = gold if lattice else blue * (0.9 + ((x * 7 + y * 3) % 5) / 25)
    img[band, :] = (120, 104, 80)
    img[band + 1, :] *= 1.15
    return from_array(img)


def d2_textures():
    save(sd_mosque(), "sd_mosque")
    save(d2_wall(), "d2_wall")
    save(d2_door(), "d2_door")
    save(d2_dirt(), "d2_dirt")
    save(d2_pave(), "d2_pave")
    save(d2_site(), "d2_site")
    save(d2_crate(), "d2_crate")
    save(d2_block(), "d2_block")


# --- Палитра 3D-оружия: клетки 32×32 на 256×256 — материал с фасками (металл, дерево, пластик, ткань) ---

MATERIAL = {0: "metal", 1: "metal", 2: "metal", 3: "metal", 4: "wood", 5: "wood", 6: "poly", 7: "poly", 8: "poly",
            9: "poly", 10: "cloth", 11: "cloth", 12: "cloth", 13: "skin", 14: "poly", 15: "metal", 16: "poly",
            17: "poly", 18: "flat", 19: "flat", 20: "poly", 21: "flat", 22: "metal", 23: "flat", 24: "wood",
            25: "metal", 26: "poly", 27: "flat"}


def palette_hd():
    np = _np()
    size, cell = 256, 32
    img = np.zeros((size, size, 3))
    ys, xs = np.mgrid[0:cell, 0:cell]
    for i, c in enumerate(PALETTE):
        kind = MATERIAL.get(i, "poly")
        base_c = np.array(c, dtype=float)
        n = fractal(cell, 100 + i, ((4, 0.5), (8, 0.3), (16, 0.2)))
        if kind == "metal":
            brush = value_noise(cell, 16, 200 + i)[:1, :].repeat(cell, 0) * 0.6 + n * 0.4
            t = base_c[None, None, :] * (0.9 + brush[:, :, None] * 0.2)
            scratch = (np.random.default_rng(i).random((cell, cell)) < 0.004)
            t[scratch] = np.minimum(255, t[scratch] * 1.25 + 12)
        elif kind == "wood":
            grain = np.sin((xs * 0.55 + n * 2.5) * 1.6) * 0.5 + 0.5
            t = base_c[None, None, :] * (0.82 + grain[:, :, None] * 0.3)
        elif kind == "cloth":
            weave = ((xs + ys) % 3 == 0) * 0.08 + ((xs - ys) % 3 == 0) * 0.05
            t = base_c[None, None, :] * (0.9 + n[:, :, None] * 0.15 - weave[:, :, None])
        elif kind == "skin":
            t = base_c[None, None, :] * (0.95 + n[:, :, None] * 0.1)
        elif kind == "flat":
            t = base_c[None, None, :] * (0.97 + n[:, :, None] * 0.06)
        else:
            stipple = np.random.default_rng(300 + i).random((cell, cell)) * 0.1
            t = base_c[None, None, :] * (0.9 + n[:, :, None] * 0.12 + stipple[:, :, None])
        # фаска: светлый верх и левый край, тёмный низ и правый — каждая грань выглядит как деталь с ребром
        bevel = np.ones((cell, cell))
        bevel[:2, :] = 1.28 if kind == "metal" else 1.18
        bevel[:, :2] = np.maximum(bevel[:, :2], 1.12)
        bevel[-2:, :] = 0.62
        bevel[:, -2:] = np.minimum(bevel[:, -2:], 0.72)
        if kind == "metal":
            bevel[2:4, 2:-2] = 1.1  # блик
        t = t * bevel[:, :, None]
        cx, cy = (i % 8) * cell, (i // 8) * cell
        img[cy:cy + cell, cx:cx + cell] = t
    return from_array(img)

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
    save(snowstone(), "snowstone")
    save(wood_dark(), "wood_dark")
    save(snow(), "snow")
    save(ice(), "ice")
    save(site((228, 234, 240), (60, 120, 200)), "site_snow")
    save(plaster(), "plaster")
    save(wood_panel(), "wood_panel")
    save(carpet(), "carpet")
    save(office_tiles(), "office_tiles")
    save(site((170, 172, 170), (200, 60, 50)), "site_office")
    save(ceiling_office(), "ceiling_office")
    save(temple_stone(), "temple_stone")
    save(temple_glyph(), "temple_glyph")
    save(temple_floor(), "temple_floor")
    save(moss(), "moss")
    save(site((150, 144, 118), (170, 70, 40)), "site_temple")
    save(logs(), "logs")
    save(dock(), "dock")
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
    save(puff(48, (200, 200, 200)), "smoke")
    save(explosion(), "explosion")
    save(small_sprite(8, (20, 18, 16)), "impact")
    save(muzzle_flash(16), "spark")
    save(puff(24, (150, 20, 20)), "blood")
    save(small_sprite(8, (255, 40, 30)), "bomb_light")
    models = load_models()
    for key in ["glock", "usp", "p250", "deagle", "nova", "mp9", "p90", "galil", "famas", "ak47", "m4a4", "awp", "knife",
                "he", "flash", "smoke", "c4"]:
        save(render_icon(models[key]), "i_" + key)
    save(palette_hd(), "vm_palette")
    hud_icons()
    save(scope(), "scope")
    hd_world_textures()
    d2_textures()
    radars()
    app_icon()
    print("ok")


if __name__ == "__main__":
    main()
