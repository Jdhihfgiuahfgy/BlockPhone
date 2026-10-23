"""Городские текстуры Block Strike (128×128 и др.): фасады, карнизы, окна, двери, витрины, крыши, дороги, тротуары,
интерьеры, балконы, кондиционеры, вывески — по темам карт (пустыня, «Оазис», итальянский городок, промзона, деревня,
горы, офис). Карта рисуется из «домов» (render.lua, THEMES) — эти текстуры дают ей вид настоящего города.
Запуск из корня репозитория: python tools/gen_strike_city.py (его же вызывает gen_strike.py).
"""
import math
import os
import random

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX = os.path.join(ROOT, "docs", "examples", "scripts", "blockstrike", "textures", "city")
S = 128


# --- Шум и заготовки ---

def value_noise(w, h, cx, cy, seed):
    """Плавный бесшовный шум: сетка cx×cy, гладкая интерполяция."""
    rng = np.random.default_rng(seed)
    grid = rng.random((cy, cx))
    tx = np.arange(w) * cx / w
    ty = np.arange(h) * cy / h
    x0 = np.floor(tx).astype(int) % cx
    y0 = np.floor(ty).astype(int) % cy
    x1, y1 = (x0 + 1) % cx, (y0 + 1) % cy
    fx, fy = tx - np.floor(tx), ty - np.floor(ty)
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    a = grid[y0][:, x0] * (1 - fx)[None, :] + grid[y0][:, x1] * fx[None, :]
    b = grid[y1][:, x0] * (1 - fx)[None, :] + grid[y1][:, x1] * fx[None, :]
    return a * (1 - fy)[:, None] + b * fy[:, None]


def fbm(w, h, seed, octaves=((4, 0.45), (8, 0.25), (16, 0.15), (32, 0.1), (64, 0.05))):
    out = np.zeros((h, w))
    total = 0
    for k, (c, wt) in enumerate(octaves):
        out += value_noise(w, h, max(1, c * w // S), max(1, c * h // S), seed * 97 + k) * wt
        total += wt
    return out / total


def grain(w, h, seed, amount):
    return 1 + (np.random.default_rng(seed).random((h, w)) - 0.5) * amount


def fill(color, w=S, h=S):
    return np.ones((h, w, 3)) * np.array(color, dtype=float)[None, None, :]


def to_img(rgb, alpha=None):
    a = np.clip(rgb, 0, 255).astype("uint8")
    if alpha is None:
        alpha = np.full(a.shape[:2], 255, dtype="uint8")
    else:
        alpha = np.clip(alpha, 0, 255).astype("uint8")
    return Image.fromarray(np.dstack([a, alpha]), "RGBA")


def save(img, name):
    os.makedirs(TEX, exist_ok=True)
    img.save(os.path.join(TEX, name + ".png"))


def streaks(w, h, seed, strength=0.18, count=10):
    """Подтёки сверху вниз (дождь, грязь с карниза): бесшовные по x."""
    rng = random.Random(seed)
    out = np.ones((h, w))
    n = fbm(w, h, seed + 5)
    for _ in range(count):
        x = rng.randrange(w)
        width = rng.randrange(2, 9)
        length = rng.uniform(0.2, 0.9) * h
        k = rng.uniform(0.4, 1) * strength
        for dx in range(-width, width + 1):
            xx = (x + dx) % w
            fall = 1 - abs(dx) / (width + 1)
            ys = np.arange(h)
            out[:, xx] -= k * fall * np.clip(1 - ys / length, 0, 1) * (0.6 + n[:, xx] * 0.8)
    return np.clip(out, 0.5, 1)


def cracks(img, seed, count=3, dark=0.55):
    rng = random.Random(seed)
    h, w = img.shape[:2]
    for _ in range(count):
        x, y = rng.uniform(0, w), rng.uniform(0, h)
        ang = rng.uniform(0, math.pi * 2)
        for _ in range(rng.randrange(20, 60)):
            ang += rng.uniform(-0.6, 0.6)
            x, y = x + math.cos(ang), y + math.sin(ang)
            img[int(y) % h, int(x) % w] *= dark
            img[int(y + 1) % h, int(x) % w] *= (1 + dark) / 2
    return img


# --- Стены ---

def plaster(color, seed, dirt=0.18, chips=1, brick_under=(160, 96, 70)):
    """Штукатурка: крупные пятна тона, мазки, подтёки, трещины, местами осыпалась до кирпича."""
    n = fbm(S, S, seed)
    strokes = value_noise(S, S, 3, 24, seed + 1) * 0.5 + value_noise(S, S, 24, 3, seed + 2) * 0.5
    img = fill(color) * (0.86 + n[:, :, None] * 0.24 + (strokes[:, :, None] - 0.5) * 0.06)
    img *= grain(S, S, seed, 0.06)[:, :, None]
    img *= streaks(S, S, seed, dirt)[:, :, None]
    rng = random.Random(seed)
    for _ in range(chips):
        cx, cy, r = rng.randrange(S), rng.randrange(S), rng.randrange(6, 11)
        ys, xs = np.mgrid[0:S, 0:S]
        dx = (xs - cx + S // 2) % S - S // 2
        dy = (ys - cy + S // 2) % S - S // 2
        blob = (dx ** 2 + (dy * 1.5) ** 2) < (r * r) * (0.5 + n * 0.9)
        row = (ys // 8)
        brick_x = (xs + (row % 2) * 10) // 20
        mortar = (ys % 8 == 0) | ((xs + (row % 2) * 10) % 20 == 0)
        b = np.array(brick_under, dtype=float)[None, None, :] * (0.85 + ((brick_x * 7 + row * 3) % 5) / 25)[:, :, None]
        under = np.where(mortar[:, :, None], np.array([150, 140, 125], dtype=float)[None, None, :], b)
        img = np.where(blob[:, :, None], under * (0.9 + n[:, :, None] * 0.2), img)
        edge = blob ^ np.roll(blob, 1, 0)
        img[edge] *= 0.7
    return to_img(cracks(img, seed + 3, 1, 0.82))


def blocks(color, mortar, rows, cols, seed, dirt=0.12):
    """Каменная кладка: блоки разного тона и размера, утопленный шов, светлый верх и тёмный низ, сколы."""
    rng = random.Random(seed)
    n = fbm(S, S, seed)
    img = fill(mortar) * (0.8 + n[:, :, None] * 0.3)
    bh = S // rows
    for r in range(rows):
        bw = S // cols
        off = rng.randrange(bw) if r % 2 else 0
        x = -off
        while x < S:
            w = bw + rng.randrange(-bw // 4, bw // 4 + 1)
            k = 0.82 + rng.random() * 0.3
            tint = np.array(color, dtype=float) * k
            y0, y1 = r * bh + 1, r * bh + bh - 1
            for xx in range(x + 1, x + w - 1):
                col = xx % S
                img[y0:y1, col] = tint * (0.86 + n[y0:y1, col, None] * 0.28)
            img[y0, [(xx % S) for xx in range(x + 1, x + w - 1)]] *= 1.18
            img[y1 - 1, [(xx % S) for xx in range(x + 1, x + w - 1)]] *= 0.72
            img[y0:y1, (x + 1) % S] *= 1.08
            img[y0:y1, (x + w - 2) % S] *= 0.8
            x += w
    img *= grain(S, S, seed, 0.08)[:, :, None]
    img *= streaks(S, S, seed, dirt)[:, :, None]
    for _ in range(12):
        x, y = rng.randrange(S), rng.randrange(S)
        img[y:y + 3, x:x + 3] *= 0.75
    return to_img(img)


def bricks(color, mortar, seed, dirt=0.1, rows=16):
    rng = random.Random(seed)
    n = fbm(S, S, seed)
    img = fill(mortar) * (0.85 + n[:, :, None] * 0.2)
    bh = S // rows
    bw = S // 4
    for r in range(rows):
        off = (bw // 2) if r % 2 else 0
        for c in range(-1, 5):
            x0 = c * bw + off
            k = 0.78 + rng.random() * 0.34
            tint = np.array(color, dtype=float) * k
            if rng.random() < 0.08:
                tint *= 0.7
            for xx in range(x0 + 1, x0 + bw - 1):
                col = xx % S
                img[r * bh + 1:r * bh + bh - 1, col] = tint * (0.88 + n[r * bh + 1:r * bh + bh - 1, col, None] * 0.24)
            img[r * bh + 1, [(xx % S) for xx in range(x0 + 1, x0 + bw - 1)]] *= 1.12
            img[r * bh + bh - 2, [(xx % S) for xx in range(x0 + 1, x0 + bw - 1)]] *= 0.8
    img *= grain(S, S, seed, 0.1)[:, :, None]
    img *= streaks(S, S, seed, dirt)[:, :, None]
    return to_img(img)


def siding(color, seed, boards=8):
    """Дощатая обшивка: горизонтальные доски с тенью нахлёста, волокна, облупившаяся краска."""
    rng = random.Random(seed)
    n = fbm(S, S, seed)
    ys, xs = np.mgrid[0:S, 0:S]
    bh = S // boards
    fib = np.sin((xs * 0.15 + n * 6) * 3) * 0.04
    img = fill(color) * (0.9 + fib[:, :, None] + n[:, :, None] * 0.15)
    for b in range(boards):
        k = 0.9 + rng.random() * 0.18
        img[b * bh:(b + 1) * bh] *= k
        img[b * bh + bh - 2:(b + 1) * bh] *= 0.6
        img[b * bh] *= 1.15
    peel = fbm(S, S, seed + 9) > 0.62
    img[peel] = img[peel] * 0.6 + np.array([110, 84, 60]) * 0.4
    return to_img(img * grain(S, S, seed, 0.06)[:, :, None])


def panels(color, seed, rust=0.25):
    """Бетонные панели: крупные плиты со швами, следы опалубки, ржавые подтёки от болтов."""
    n = fbm(S, S, seed)
    img = fill(color) * (0.85 + n[:, :, None] * 0.25)
    img *= grain(S, S, seed, 0.1)[:, :, None]
    for x in (0, 64):
        img[:, x:x + 2] *= 0.55
        img[:, x + 2] *= 1.1
    img[0:2, :] *= 0.55
    img[2, :] *= 1.08
    rng = random.Random(seed)
    for bx in (12, 52, 76, 116):
        for by in (14, 110):
            img[by:by + 3, bx:bx + 3] *= 0.5
            if rng.random() < 0.7:
                for y in range(by + 3, min(S, by + 3 + rng.randrange(10, 40))):
                    img[y, bx:bx + 3] = img[y, bx:bx + 3] * (1 - rust) + np.array([140, 80, 40]) * rust
    img *= streaks(S, S, seed, 0.12)[:, :, None]
    return to_img(img)


def corrugated(color, seed):
    n = fbm(S, S, seed)
    xs = np.arange(S)
    wave = (np.sin(xs / S * math.pi * 16) * 0.5 + 0.5)[None, :]
    img = fill(color) * (0.7 + wave[:, :, None] * 0.45) * (0.9 + n[:, :, None] * 0.2)
    rust = fbm(S, S, seed + 4) > 0.6
    img[rust] = img[rust] * 0.5 + np.array([130, 70, 36]) * 0.5
    img *= streaks(S, S, seed, 0.2)[:, :, None]
    return to_img(img)


def trim(color, seed):
    """Карниз (верхняя полоса фасада): профиль из полок и выкружки, снизу — тень."""
    n = fbm(S, S, seed)
    img = fill(color) * (0.92 + n[:, :, None] * 0.16)
    prof = np.ones(S)
    for y0, y1, k in ((0, 10, 1.18), (10, 14, 0.7), (14, 40, 1.05), (40, 46, 0.75), (46, 90, 1.0), (90, 96, 1.2),
                      (96, 112, 0.92), (112, 128, 0.6)):
        prof[y0:y1] = k
    img *= prof[:, None, None]
    dent = (np.arange(S) % 16 < 8)[None, :] & (np.arange(S)[:, None] >= 96) & (np.arange(S)[:, None] < 112)
    img[dent] *= 0.8
    return to_img(img * grain(S, S, seed, 0.05)[:, :, None])


# --- Окна, двери, витрины ---

def window(frame, kind, seed, w=64, h=128):
    """Окно: рама, переплёт, стекло с отражением неба, подоконник; kind: glass | shutters | arched | barred."""
    rng = random.Random(seed)
    ys, xs = np.mgrid[0:h, 0:w]
    sky = np.array([150, 176, 200], dtype=float)
    glass = sky[None, None, :] * (0.45 + (1 - ys[:, :, None] / h) * 0.35) + ((xs - ys * 0.6) % 40 < 8)[:, :, None] * 30
    img = glass.copy()
    if kind == "shutters":
        img = fill(frame, w, h) * (0.95 + ((ys % 6) < 2)[:, :, None] * -0.35)
        img[:, w // 2 - 1:w // 2 + 1] *= 0.5
    elif kind == "barred":
        for x in range(8, w, 12):
            img[:, x:x + 3] = np.array([40, 40, 42])
        img[h // 3:h // 3 + 3] = np.array([40, 40, 42])
        img[2 * h // 3:2 * h // 3 + 3] = np.array([40, 40, 42])
    else:
        img[:, w // 2 - 2:w // 2 + 2] = np.array(frame) * 0.9
        img[h // 2 - 2:h // 2 + 2, :] = np.array(frame) * 0.9
    alpha = np.full((h, w), 255.0)
    if kind == "arched":
        cy = w // 2
        mask = ((xs - w / 2) ** 2 + (ys - cy) ** 2 > (w / 2 - 1) ** 2) & (ys < cy)
        alpha[mask] = 0
    # рама
    b = 5
    fr = np.array(frame, dtype=float)
    img[:b, :] = fr * 1.1
    img[-b - 4:, :] = fr * 0.95
    img[:, :b] = fr
    img[:, -b:] = fr * 0.8
    img[-4:, :] = np.array([200, 196, 186]) * 0.9  # подоконник
    img[-5, :] *= 1.2
    n = fbm(w, h, seed)
    img *= (0.9 + n[:, :, None] * 0.2)
    return to_img(img, alpha)


def door(color, kind, seed, w=128, h=256):
    """Дверь (1×2,2 м): дощатая двустворчатая, филёнчатая или железная; рама, ручки, петли."""
    rng = random.Random(seed)
    ys, xs = np.mgrid[0:h, 0:w]
    n = fbm(w, h, seed)
    c = np.array(color, dtype=float)
    if kind == "planks":
        boards = (xs // 16)
        img = c[None, None, :] * (0.85 + ((boards * 7) % 5)[:, :, None] / 25 + n[:, :, None] * 0.2)
        img[xs % 16 == 0] *= 0.6
        img[(ys > 40) & (ys < 50)] *= 0.85
        img[(ys > 200) & (ys < 210)] *= 0.85
    elif kind == "metal":
        img = c[None, None, :] * (0.85 + n[:, :, None] * 0.25)
        for y in range(20, h, 40):
            img[y:y + 2] *= 0.7
        rust = fbm(w, h, seed + 3) > 0.62
        img[rust] = img[rust] * 0.5 + np.array([130, 72, 38]) * 0.5
    else:
        img = c[None, None, :] * (0.9 + n[:, :, None] * 0.15)
        for x0, x1 in ((14, 58), (70, 114)):
            for y0, y1 in ((20, 110), (130, 236)):
                img[y0:y1, x0:x0 + 3] *= 0.7
                img[y0:y0 + 3, x0:x1] *= 0.7
                img[y0:y1, x1 - 3:x1] *= 1.15
                img[y1 - 3:y1, x0:x1] *= 1.15
    img[:, w // 2 - 1:w // 2 + 1] *= 0.5
    fr = c * 0.6
    img[:8, :] = fr
    img[:, :8] = fr
    img[:, -8:] = fr * 0.8
    for x in (w // 2 - 10, w // 2 + 6):
        img[128:140, x:x + 4] = np.array([60, 56, 50])
    img *= streaks(w, h, seed, 0.1)[:, :, None]
    return to_img(img)


def shutter(seed, color=(150, 150, 146)):
    """Рольставня витрины (1 м): гофра, низ темнее, граффити-пятна."""
    ys, xs = np.mgrid[0:S, 0:S]
    n = fbm(S, S, seed)
    img = fill(color) * (0.8 + ((ys % 8) < 5)[:, :, None] * 0.25) * (0.9 + n[:, :, None] * 0.2)
    rng = random.Random(seed)
    if rng.random() < 0.6:
        cx, cy = rng.randrange(20, 108), rng.randrange(40, 100)
        col = np.array(rng.choice([(200, 60, 60), (60, 120, 200), (60, 170, 80), (230, 200, 60)]))
        mask = ((xs - cx) ** 2 / 600 + (ys - cy) ** 2 / 120) < (0.6 + n * 0.8)
        img[mask] = img[mask] * 0.3 + col * 0.7
    img *= streaks(S, S, seed, 0.15)[:, :, None]
    img[:6] = np.array([70, 70, 72])
    return to_img(img)


def sign(seed, bg, fg):
    """Вывеска 128×32: фон, рамка, «буквы» (блоки), болты."""
    rng = random.Random(seed)
    w, h = S, 32
    img = fill(bg, w, h) * (0.9 + fbm(w, h, seed)[:, :, None] * 0.2)
    x = 10
    while x < w - 14:
        cw = rng.randrange(6, 11)
        img[9:23, x:x + cw] = np.array(fg) * (0.9 + rng.random() * 0.1)
        img[12:20, x + 2:x + cw - 2] = np.array(bg) * 0.95 if rng.random() < 0.4 else img[12:20, x + 2:x + cw - 2]
        x += cw + rng.randrange(2, 5)
    img[:2] *= 0.6
    img[-2:] *= 0.6
    img[:, :2] *= 0.6
    img[:, -2:] *= 0.6
    return to_img(img)


def awning(c1, c2, seed):
    ys, xs = np.mgrid[0:S, 0:S]
    stripe = ((xs // 16) % 2 == 0)
    img = np.where(stripe[:, :, None], np.array(c1, dtype=float), np.array(c2, dtype=float))
    img = img * (0.85 + fbm(S, S, seed)[:, :, None] * 0.25) * (1 - (ys[:, :, None] / S) * 0.25)
    img[-10:] *= 0.8
    return to_img(img)


def railing(seed, color=(40, 40, 44)):
    """Перила балкона 128×64 с прозрачностью: стойки, поручень, завитки."""
    w, h = S, 64
    ys, xs = np.mgrid[0:h, 0:w]
    alpha = np.zeros((h, w))
    alpha[:5] = 255
    alpha[-4:] = 255
    alpha[:, (xs[0] % 16) < 3] = 255
    ring = ((xs % 16 - 8) ** 2 + (ys - 32) ** 2)
    alpha[(ring > 20) & (ring < 34) & (ys > 20) & (ys < 44)] = 255
    img = fill(color, w, h) * (0.9 + fbm(w, h, seed)[:, :, None] * 0.3)
    img[:5] *= 1.4
    return to_img(img, alpha)


def ac_unit(seed):
    w = h = 64
    ys, xs = np.mgrid[0:h, 0:w]
    img = fill((196, 198, 196), w, h) * (0.9 + fbm(w, h, seed)[:, :, None] * 0.15)
    grill = ((xs - 32) ** 2 + (ys - 32) ** 2) < 24 ** 2
    img[grill & (ys % 4 < 2)] *= 0.45
    img[grill & (ys % 4 >= 2)] *= 0.75
    img[:3] *= 1.2
    img[-3:] *= 0.6
    img *= streaks(w, h, seed, 0.25)[:, :, None]
    return to_img(img)


def pipe(seed, color=(120, 120, 116)):
    w, h = 16, 64
    xs = np.arange(w)
    shade = 0.6 + np.sin(xs / w * math.pi) * 0.6
    img = fill(color, w, h) * shade[None, :, None] * (0.9 + fbm(w, h, seed)[:, :, None] * 0.2)
    img[::16] *= 0.6
    return to_img(img)


def far_facade(color, seed, lit=False):
    """Дом вдали (горизонт): ряды окон на стене, 128×128 = 4×4 м."""
    rng = random.Random(seed)
    n = fbm(S, S, seed)
    img = fill(color) * (0.85 + n[:, :, None] * 0.25)
    for fy in range(0, S, 32):
        for fx in range(0, S, 32):
            if rng.random() < 0.85:
                g = np.array([70, 84, 100]) * (0.7 + rng.random() * 0.5)
                if lit and rng.random() < 0.15:
                    g = np.array([220, 190, 120])
                img[fy + 8:fy + 24, fx + 8:fx + 22] = g
                img[fy + 24:fy + 26, fx + 6:fx + 24] *= 1.2
    img *= streaks(S, S, seed, 0.15)[:, :, None]
    return to_img(img)


# --- Полы, дороги, крыши ---

def asphalt(seed, color=(78, 78, 80), lines=False):
    n = fbm(S, S, seed)
    img = fill(color) * (0.8 + n[:, :, None] * 0.35)
    rng = np.random.default_rng(seed)
    stones = rng.random((S, S))
    img[stones < 0.08] *= 1.35
    img[stones > 0.95] *= 0.7
    patch = fbm(S, S, seed + 7) > 0.66
    img[patch] *= 0.82
    oil = fbm(S, S, seed + 11) > 0.7
    img[oil] *= 0.7
    if lines:
        img[:, 60:68] = img[:, 60:68] * 0.25 + np.array([220, 210, 170]) * 0.75 * (0.8 + n[:, 60:68, None] * 0.3)
    return to_img(cracks(img, seed + 1, 4, 0.6))


def dirt_road(seed, color=(196, 170, 124)):
    n = fbm(S, S, seed)
    img = fill(color) * (0.82 + n[:, :, None] * 0.3)
    xs = np.arange(S)
    ruts = (np.exp(-((xs - 36) / 7.0) ** 2) + np.exp(-((xs - 92) / 7.0) ** 2))[None, :]
    img *= (1 - ruts[:, :, None] * 0.14)
    rng = np.random.default_rng(seed)
    pebbles = rng.random((S, S)) < 0.03
    img[pebbles] *= 0.7
    img[rng.random((S, S)) < 0.02] *= 1.25
    return to_img(img * grain(S, S, seed, 0.1)[:, :, None])


def paving(color, seed, n_=4, gap=(110, 104, 96)):
    """Тротуарная плитка: квадраты со скошенным краем, у каждой свой тон, трещины, трава в швах (иногда)."""
    rng = random.Random(seed)
    nz = fbm(S, S, seed)
    img = fill(gap) * (0.8 + nz[:, :, None] * 0.3)
    t = S // n_
    for ty in range(n_):
        for tx in range(n_):
            k = 0.85 + rng.random() * 0.25
            y0, x0 = ty * t + 2, tx * t + 2
            img[y0:y0 + t - 3, x0:x0 + t - 3] = np.array(color) * k * (0.9 + nz[y0:y0 + t - 3, x0:x0 + t - 3, None] * 0.2)
            img[y0, x0:x0 + t - 3] *= 1.12
            img[y0:y0 + t - 3, x0] *= 1.08
            img[y0 + t - 4, x0:x0 + t - 3] *= 0.8
    return to_img(cracks(img * grain(S, S, seed, 0.08)[:, :, None], seed + 2, 2, 0.7))


def cobble(color, seed, count=60):
    rng = np.random.default_rng(seed)
    pts = rng.random((count, 2)) * S
    shades = 0.78 + rng.random(count) * 0.4
    ys, xs = np.mgrid[0:S, 0:S]
    best = np.full((S, S), 1e9)
    second = np.full((S, S), 1e9)
    idx = np.zeros((S, S), dtype=int)
    for i, (px, py) in enumerate(pts):
        for ox in (-S, 0, S):
            for oy in (-S, 0, S):
                d = np.hypot(xs - px - ox, ys - py - oy)
                closer = d < best
                second = np.where(closer, best, np.minimum(second, d))
                idx = np.where(closer, i, idx)
                best = np.where(closer, d, best)
    edge = np.clip((second - best) / 4.0, 0, 1)
    n = fbm(S, S, seed)
    img = np.array(color, dtype=float)[None, None, :] * shades[idx][:, :, None] * (0.85 + n[:, :, None] * 0.3)
    img *= (0.4 + 0.6 * edge)[:, :, None]
    dome = np.clip(1.15 - best / 14, 0.85, 1.15)
    return to_img(img * dome[:, :, None])


def tiles_floor(c1, c2, seed, n_=8, pattern="checker"):
    rng = random.Random(seed)
    nz = fbm(S, S, seed)
    img = np.zeros((S, S, 3))
    t = S // n_
    for ty in range(n_):
        for tx in range(n_):
            if pattern == "checker":
                c = c1 if (tx + ty) % 2 == 0 else c2
            else:
                c = c1 if rng.random() < 0.8 else c2
            img[ty * t:(ty + 1) * t, tx * t:(tx + 1) * t] = np.array(c) * (0.9 + rng.random() * 0.15)
            img[ty * t, tx * t:(tx + 1) * t] *= 0.7
            img[ty * t:(ty + 1) * t, tx * t] *= 0.7
    img *= (0.9 + nz[:, :, None] * 0.18)
    return to_img(cracks(img, seed + 1, 2, 0.75))


def parquet(color, seed):
    rng = random.Random(seed)
    nz = fbm(S, S, seed)
    ys, xs = np.mgrid[0:S, 0:S]
    img = np.zeros((S, S, 3))
    for by in range(0, S, 16):
        off = rng.randrange(64)
        for bx in range(-64, S, 64):
            x0 = bx + off
            k = 0.82 + rng.random() * 0.3
            for x in range(max(0, x0), min(S, x0 + 64)):
                img[by:by + 16, x] = np.array(color) * k
            if 0 <= x0 < S:
                img[by:by + 16, x0] *= 0.6
        img[by] *= 0.65
    fib = np.sin(xs * 0.4 + nz * 8) * 0.05
    return to_img(img * (0.92 + fib[:, :, None] + nz[:, :, None] * 0.1))


def carpet(color, seed):
    nz = fbm(S, S, seed, ((16, 0.3), (32, 0.3), (64, 0.4)))
    rng = np.random.default_rng(seed)
    img = fill(color) * (0.85 + nz[:, :, None] * 0.25) * (1 + (rng.random((S, S)) - 0.5)[:, :, None] * 0.18)
    return to_img(img)


def roof_flat(color, seed):
    """Плоская крыша: гравий на битуме, швы рулонов, лужи."""
    n = fbm(S, S, seed)
    rng = np.random.default_rng(seed)
    img = fill(color) * (0.8 + n[:, :, None] * 0.3)
    img *= (1 + (rng.random((S, S)) - 0.5)[:, :, None] * 0.35)
    img[::32] *= 0.7
    return to_img(img)


def roof_tiles(color, seed):
    """Черепица (вид сверху/сбоку): ряды полукруглых плиток."""
    ys, xs = np.mgrid[0:S, 0:S]
    n = fbm(S, S, seed)
    row = ys // 16
    phase = (xs + (row % 2) * 8) % 16
    bump = np.sin(phase / 16 * math.pi)
    img = fill(color) * (0.65 + bump[:, :, None] * 0.4) * (0.85 + n[:, :, None] * 0.25)
    img[(ys % 16) > 13] *= 0.6
    return to_img(img)


def ceiling_beams(color, beam, seed):
    n = fbm(S, S, seed)
    img = fill(color) * (0.85 + n[:, :, None] * 0.2)
    for x0 in (0, 64):
        img[:, x0:x0 + 18] = np.array(beam) * (0.85 + n[:, x0:x0 + 18, None] * 0.3)
        img[:, x0 + 16:x0 + 18] *= 0.6
        img[:, x0:x0 + 2] *= 1.2
    return to_img(img)


def ceiling_tiles(seed):
    n = fbm(S, S, seed)
    img = fill((206, 206, 200)) * (0.92 + n[:, :, None] * 0.1)
    rng = np.random.default_rng(seed)
    img *= (1 + (rng.random((S, S)) < 0.05)[:, :, None] * -0.2)
    for k in (0, 64):
        img[k:k + 3, :] = np.array([150, 150, 150])
        img[:, k:k + 3] = np.array([150, 150, 150])
    img[16:48, 80:112] = np.array([240, 240, 230])
    img[16:18, 80:112] *= 0.8
    return to_img(img)


def interior_wall(color, base_color, seed):
    """Стена внутри (растягивается на высоту комнаты): штукатурка, плинтус снизу, тень у потолка, потёртости."""
    n = fbm(S, S, seed)
    ys = np.arange(S)[:, None, None]
    img = fill(color) * (0.9 + n[:, :, None] * 0.12) * grain(S, S, seed, 0.04)[:, :, None]
    img *= (0.82 + np.clip(ys / 16, 0, 1) * 0.18)
    img[-12:] = np.array(base_color) * (0.9 + n[-12:, :, None] * 0.2)
    img[-12] *= 1.2
    img[-20:-12] *= 0.85
    rng = random.Random(seed)
    for _ in range(4):
        x, y = rng.randrange(S), rng.randrange(60, 110)
        img[y:y + rng.randrange(3, 10), x:x + rng.randrange(6, 20)] *= 0.9
    return to_img(img)


# --- Темы ---

def main():
    # Пустыня (Каньон, Рынок, Пустыня, Переулок)
    save(plaster((214, 196, 160), 101), "des_plaster")
    save(plaster((222, 184, 136), 102, 0.22), "des_plaster2")
    save(blocks((206, 180, 132), (160, 136, 98), 6, 3, 103), "des_blocks")
    save(plaster((196, 160, 120), 104, 0.25, 1, (150, 120, 90)), "des_adobe")
    save(trim((214, 190, 146), 105), "des_trim")
    save(window((118, 82, 52), "shutters", 106), "des_window")
    save(window((150, 120, 84), "arched", 107), "des_window2")
    save(window((60, 60, 60), "barred", 108), "des_window3")
    save(door((112, 72, 40), "planks", 109), "des_door")
    save(door((60, 100, 120), "planks", 110), "des_door2")
    save(roof_flat((150, 132, 104), 111), "des_roof")
    save(dirt_road(112), "des_road")
    save(paving((200, 182, 146), 113), "des_paving")
    save(interior_wall((226, 216, 196), (120, 92, 66), 114), "des_inwall")
    save(tiles_floor((178, 104, 72), (160, 90, 62), 115, 8, "random"), "des_infloor")
    save(ceiling_beams((214, 202, 180), (110, 78, 50), 116), "des_ceiling")
    save(awning((200, 70, 50), (232, 220, 196), 117), "des_awning")
    save(far_facade((200, 178, 140), 118), "des_far")
    # Оазис (Mirage)
    save(blocks((214, 192, 150), (170, 150, 112), 4, 2, 121), "oas_blocks")
    save(plaster((230, 214, 182), 122, 0.15), "oas_plaster")
    save(blocks((196, 174, 136), (150, 132, 100), 8, 4, 123), "oas_bricks")
    save(trim((80, 132, 150), 124), "oas_trim")
    save(window((60, 120, 140), "arched", 125), "oas_window")
    save(window((80, 70, 60), "shutters", 126), "oas_window2")
    save(door((50, 110, 130), "panel", 127), "oas_door")
    save(tiles_floor((70, 130, 150), (226, 214, 186), 128, 8), "oas_infloor")
    save(paving((206, 190, 156), 129, 2), "oas_paving")
    save(awning((40, 120, 140), (230, 222, 200), 130), "oas_awning")
    save(interior_wall((230, 222, 204), (60, 110, 130), 131), "oas_inwall")
    # Италия (Вилла, Храм)
    save(plaster((214, 160, 96), 141), "ita_ochre")
    save(plaster((226, 206, 170), 142), "ita_cream")
    save(plaster((190, 110, 84), 143, 0.2), "ita_terra")
    save(blocks((168, 160, 146), (120, 114, 104), 4, 2, 144), "ita_stone")
    save(trim((222, 212, 190), 145), "ita_trim")
    save(window((70, 110, 70), "shutters", 146), "ita_window")
    save(window((200, 196, 184), "glass", 147), "ita_window2")
    save(door((96, 60, 36), "panel", 148), "ita_door")
    save(cobble((150, 140, 128), 149), "ita_cobble")
    save(paving((178, 168, 150), 150, 4), "ita_paving")
    save(roof_tiles((176, 86, 54), 151), "ita_roof")
    save(parquet((146, 100, 64), 152), "ita_infloor")
    save(interior_wall((232, 222, 200), (96, 66, 44), 153), "ita_inwall")
    save(ceiling_beams((226, 214, 192), (96, 66, 40), 154), "ita_ceiling")
    save(awning((40, 100, 60), (226, 216, 190), 155), "ita_awning")
    save(far_facade((210, 170, 120), 156), "ita_far")
    # Промзона (Порт, Завод)
    save(bricks((140, 68, 52), (96, 90, 84), 161), "ind_brick")
    save(panels((150, 150, 146), 162), "ind_concrete")
    save(corrugated((100, 120, 130), 163), "ind_metal")
    save(trim((120, 120, 118), 164), "ind_trim")
    save(window((70, 74, 78), "glass", 165), "ind_window")
    save(door((90, 100, 96), "metal", 166), "ind_door")
    save(shutter(167), "ind_shutter")
    save(asphalt(168), "ind_road")
    save(asphalt(169, lines=True), "ind_road_line")
    save(paving((150, 150, 146), 170, 2), "ind_paving")
    save(roof_flat((90, 90, 92), 171), "ind_roof")
    save(interior_wall((180, 182, 178), (70, 70, 72), 172), "ind_inwall")
    save(tiles_floor((120, 122, 120), (110, 112, 110), 173, 2), "ind_infloor")
    save(far_facade((130, 130, 130), 174, True), "ind_far")
    # Деревня (Деревня, Озеро)
    save(siding((146, 104, 70), 181), "vil_wood")
    save(siding((180, 170, 150), 182), "vil_wood2")
    save(bricks((150, 80, 60), (110, 100, 90), 183), "vil_brick")
    save(trim((90, 64, 44), 184), "vil_trim")
    save(window((236, 232, 220), "glass", 185), "vil_window")
    save(door((110, 70, 44), "planks", 186), "vil_door")
    save(dirt_road(187, (130, 112, 84)), "vil_road")
    save(parquet((130, 92, 60), 188), "vil_infloor")
    save(interior_wall((210, 196, 170), (90, 64, 44), 189), "vil_inwall")
    save(ceiling_beams((190, 170, 140), (90, 64, 40), 190), "vil_ceiling")
    save(roof_tiles((96, 70, 56), 191), "vil_roof")
    # Горы (Ледник)
    save(blocks((150, 150, 156), (110, 110, 116), 4, 2, 201), "alp_stone")
    save(siding((110, 76, 52), 202), "alp_wood")
    save(window((80, 56, 40), "glass", 203), "alp_window")
    save(door((90, 60, 40), "planks", 204), "alp_door")
    save(paving((190, 196, 204), 205, 4), "alp_paving")
    save(interior_wall((214, 200, 180), (100, 70, 50), 206), "alp_inwall")
    # Офис
    save(panels((196, 194, 188), 211, 0.05), "off_panel")
    save(window((150, 156, 160), "glass", 212), "off_window")
    save(door((150, 150, 150), "panel", 213), "off_door")
    save(carpet((70, 76, 96), 214), "off_carpet")
    save(ceiling_tiles(215), "off_ceiling")
    save(interior_wall((214, 214, 208), (90, 90, 96), 216), "off_inwall")
    # Общие: балкон, кондиционер, труба, вывески
    save(railing(221), "railing")
    save(ac_unit(222), "ac")
    save(pipe(223), "pipe")
    save(sign(224, (40, 90, 160), (240, 240, 230)), "sign1")
    save(sign(225, (180, 40, 40), (250, 230, 120)), "sign2")
    save(sign(226, (30, 120, 70), (240, 240, 230)), "sign3")
    save(sign(227, (60, 50, 40), (230, 200, 120)), "sign4")
    print("city ok")


if __name__ == "__main__":
    main()
