"""Генерирует текстуры BlockPhone: символы приложений, погоду, шум камеры, иконку мода.

Символы приложений (textures/gui/glyph/<id>.png) — белые маски на прозрачном фоне: игра окрашивает
их в цвет приложения и рисует на тёмной плитке (см. AppRegistry.Entry.drawIcon).

Запуск из корня проекта:  python tools/gen_icons.py
Рисует в 4x разрешении и уменьшает со сглаживанием.
"""
import math
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "fabric", "src", "main", "resources", "assets", "blockphone")
APP_DIR = os.path.join(ASSETS, "textures", "gui", "app")
GLYPH_DIR = os.path.join(ASSETS, "textures", "gui", "glyph")
WEATHER_DIR = os.path.join(ASSETS, "textures", "gui", "weather")

S = 256  # рабочее разрешение
OUT = 64
WHITE = (255, 255, 255, 255)


def hex_rgb(value, alpha=255):
    return ((value >> 16) & 255, (value >> 8) & 255, value & 255, alpha)


def gradient_square(top, bottom, radius=58):
    grad = Image.new("RGBA", (S, S))
    d = ImageDraw.Draw(grad)
    t, b = hex_rgb(top), hex_rgb(bottom)
    for y in range(S):
        k = y / (S - 1)
        d.line([(0, y), (S, y)], fill=tuple(int(t[i] + (b[i] - t[i]) * k) for i in range(4)))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=radius, fill=255)
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    img.paste(grad, (0, 0), mask)
    return img


def save(img, folder, name, size=OUT):
    os.makedirs(folder, exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(os.path.join(folder, name + ".png"))


def cloud(d, cx, cy, scale, fill):
    r = 34 * scale
    d.ellipse([cx - 2.2 * r, cy - 0.6 * r, cx - 0.4 * r, cy + r], fill=fill)
    d.ellipse([cx - 1.2 * r, cy - 1.4 * r, cx + 0.8 * r, cy + 0.6 * r], fill=fill)
    d.ellipse([cx + 0.1 * r, cy - 0.9 * r, cx + 2.0 * r, cy + r], fill=fill)
    d.rounded_rectangle([cx - 1.8 * r, cy - 0.1 * r, cx + 1.6 * r, cy + r], radius=r * 0.5, fill=fill)


def sun(d, cx, cy, r, fill, rays=True):
    if rays:
        for i in range(8):
            a = i * math.pi / 4
            x1, y1 = cx + math.cos(a) * r * 1.35, cy + math.sin(a) * r * 1.35
            x2, y2 = cx + math.cos(a) * r * 1.8, cy + math.sin(a) * r * 1.8
            d.line([(x1, y1), (x2, y2)], fill=fill, width=int(r * 0.28))
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=fill)


# ---------------- Иконки приложений ----------------

def icon_messenger():
    img = gradient_square(0x5FE07A, 0x248A3D)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([46, 58, 210, 178], radius=50, fill=WHITE)
    d.polygon([(78, 160), (64, 212), (122, 172)], fill=WHITE)
    for x in (88, 128, 168):
        d.ellipse([x - 12, 106, x + 12, 130], fill=hex_rgb(0x34C759))
    return img


def icon_telegram():
    img = gradient_square(0x5AC8FA, 0x1E88C8)
    d = ImageDraw.Draw(img)
    d.polygon([(52, 124), (204, 62), (178, 198), (126, 160)], fill=WHITE)
    d.polygon([(110, 148), (178, 92), (126, 160), (118, 196)], fill=hex_rgb(0xD6ECFA))
    return img


def icon_camera():
    img = gradient_square(0xA1A1A6, 0x58585D)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([40, 82, 216, 196], radius=26, fill=hex_rgb(0x2C2C2E))
    d.rounded_rectangle([96, 64, 160, 92], radius=10, fill=hex_rgb(0x2C2C2E))
    d.ellipse([90, 102, 166, 178], fill=hex_rgb(0xD1D1D6))
    d.ellipse([104, 116, 152, 164], fill=hex_rgb(0x1C3D5A))
    d.ellipse([116, 126, 130, 140], fill=(255, 255, 255, 200))
    d.ellipse([180, 98, 196, 114], fill=hex_rgb(0xFFD60A))
    return img


def icon_gallery():
    img = gradient_square(0xFFFFFF, 0xE5E5EA)
    d = ImageDraw.Draw(img)
    colors = [0xFF3B30, 0xFF9500, 0xFFCC00, 0x34C759, 0x5AC8FA, 0x007AFF, 0xAF52DE, 0xFF2D55]
    for i, c in enumerate(colors):
        a = i * math.pi / 4
        cx, cy = 128 + math.cos(a) * 46, 128 + math.sin(a) * 46
        d.ellipse([cx - 34, cy - 34, cx + 34, cy + 34], fill=hex_rgb(c, 200))
    return img


def icon_music():
    img = gradient_square(0xFF6482, 0xE0103A)
    d = ImageDraw.Draw(img)
    d.ellipse([58, 160, 110, 202], fill=WHITE)
    d.ellipse([146, 140, 198, 182], fill=WHITE)
    d.rectangle([96, 64, 110, 184], fill=WHITE)
    d.rectangle([184, 46, 198, 164], fill=WHITE)
    d.polygon([(96, 64), (198, 40), (198, 78), (96, 102)], fill=WHITE)
    return img


def icon_notes():
    img = gradient_square(0xFFFFFF, 0xF2F2F7)
    d = ImageDraw.Draw(img)
    mask_top = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(mask_top).rectangle([0, 0, S, 70], fill=hex_rgb(0xFFD60A))
    img = Image.alpha_composite(img, Image.composite(mask_top, Image.new("RGBA", (S, S)), img.split()[3]))
    d = ImageDraw.Draw(img)
    for y in (106, 144, 182):
        d.line([(44, y), (212, y)], fill=hex_rgb(0xC7C7CC), width=6)
    d.line([(44, 106), (150, 106)], fill=hex_rgb(0x3A3A3C), width=8)
    return img


def icon_calculator():
    img = gradient_square(0x3A3A3C, 0x1C1C1E)
    d = ImageDraw.Draw(img)
    for row in range(3):
        for col in range(3):
            cx, cy = 70 + col * 58, 70 + row * 58
            color = 0xFF9F0A if col == 2 else (0xA5A5A5 if row == 0 else 0x636366)
            d.ellipse([cx - 22, cy - 22, cx + 22, cy + 22], fill=hex_rgb(color))
    return img


def icon_weather():
    img = gradient_square(0x56A8F5, 0x2563C9)
    d = ImageDraw.Draw(img)
    sun(d, 104, 104, 40, hex_rgb(0xFFD60A))
    cloud(d, 146, 166, 1.0, WHITE)
    return img


def icon_settings():
    img = gradient_square(0xAEAEB2, 0x5A5A5F)
    d = ImageDraw.Draw(img)
    cx = cy = 128
    teeth = 10
    points = []
    for i in range(teeth * 2):
        a = i * math.pi / teeth
        r = 92 if i % 2 == 0 else 72
        for da in (-0.12, 0.12):
            points.append((cx + math.cos(a + da) * r, cy + math.sin(a + da) * r))
    d.polygon(points, fill=hex_rgb(0x3A3A3C))
    d.ellipse([cx - 64, cy - 64, cx + 64, cy + 64], fill=hex_rgb(0x3A3A3C))
    d.ellipse([cx - 30, cy - 30, cx + 30, cy + 30], fill=hex_rgb(0xD1D1D6))
    return img


# ---------------- Символы приложений (белые маски) ----------------

def glyph_canvas():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def glyph_messenger():
    img, d = glyph_canvas()
    d.rounded_rectangle([40, 56, 216, 176], radius=40, outline=WHITE, width=16)
    d.polygon([(70, 168), (58, 214), (112, 172)], fill=WHITE)
    for x in (92, 128, 164):
        d.ellipse([x - 11, 105, x + 11, 127], fill=WHITE)
    return img


def glyph_telegram():
    img, d = glyph_canvas()
    d.polygon([(40, 124), (214, 52), (184, 204), (124, 164)], fill=WHITE)
    d.polygon([(112, 152), (184, 84), (124, 164), (116, 204)], fill=(255, 255, 255, 150))
    return img


def glyph_camera():
    img, d = glyph_canvas()
    d.rounded_rectangle([36, 78, 220, 200], radius=24, outline=WHITE, width=16)
    d.rounded_rectangle([92, 54, 164, 86], radius=10, fill=WHITE)
    d.ellipse([88, 98, 168, 178], outline=WHITE, width=16)
    d.ellipse([182, 98, 198, 114], fill=WHITE)
    return img


def glyph_gallery():
    img, d = glyph_canvas()
    d.rounded_rectangle([36, 52, 220, 204], radius=22, outline=WHITE, width=16)
    d.polygon([(52, 188), (110, 118), (150, 162), (174, 138), (204, 188)], fill=WHITE)
    d.ellipse([150, 76, 182, 108], fill=WHITE)
    return img


def glyph_music():
    img, d = glyph_canvas()
    d.ellipse([52, 160, 108, 206], fill=WHITE)
    d.ellipse([148, 140, 204, 186], fill=WHITE)
    d.rectangle([92, 62, 108, 186], fill=WHITE)
    d.rectangle([188, 42, 204, 166], fill=WHITE)
    d.polygon([(92, 62), (204, 36), (204, 76), (92, 102)], fill=WHITE)
    return img


def glyph_notes():
    img, d = glyph_canvas()
    d.rounded_rectangle([56, 36, 200, 220], radius=20, outline=WHITE, width=16)
    for y in (88, 124, 160):
        d.line([(86, y), (170, y)], fill=WHITE, width=14)
    return img


def glyph_calculator():
    img, d = glyph_canvas()
    d.rounded_rectangle([56, 32, 200, 224], radius=20, outline=WHITE, width=16)
    d.rectangle([84, 60, 172, 92], fill=WHITE)
    for row in range(2):
        for col in range(3):
            cx, cy = 92 + col * 36, 132 + row * 40
            d.rounded_rectangle([cx - 12, cy - 12, cx + 12, cy + 12], radius=5, fill=WHITE)
    return img


def glyph_weather():
    img, d = glyph_canvas()
    sun(d, 104, 100, 34, WHITE)
    # Облако «вырезано» поверх солнца — тёмный контур не нужен, просто сплошная форма.
    cloud(d, 150, 170, 0.95, WHITE)
    return img


def glyph_settings():
    img, d = glyph_canvas()
    cx = cy = 128
    teeth = 8
    points = []
    for i in range(teeth * 2):
        a = i * math.pi / teeth
        r = 96 if i % 2 == 0 else 76
        for da in (-0.14, 0.14):
            points.append((cx + math.cos(a + da) * r, cy + math.sin(a + da) * r))
    d.polygon(points, fill=WHITE)
    d.ellipse([cx - 32, cy - 32, cx + 32, cy + 32], fill=(0, 0, 0, 0))
    return img


def glyph_feed():
    img, d = glyph_canvas()
    # Лента: карточка поста с фото и строками текста, сверху сердечко.
    d.rounded_rectangle([40, 40, 216, 216], radius=26, outline=WHITE, width=16)
    d.rounded_rectangle([68, 68, 188, 132], radius=10, fill=WHITE)
    d.line([(70, 160), (186, 160)], fill=WHITE, width=14)
    d.line([(70, 188), (146, 188)], fill=WHITE, width=14)
    return img


def heart(d, cx, cy, r, fill):
    """Сердечко: два круга и треугольник, ширина ~4r."""
    d.ellipse([cx - 2 * r, cy - r, cx, cy + r], fill=fill)
    d.ellipse([cx, cy - r, cx + 2 * r, cy + r], fill=fill)
    d.polygon([(cx - 2 * r + r * 0.12, cy + r * 0.35), (cx + 2 * r - r * 0.12, cy + r * 0.35), (cx, cy + 2.3 * r)], fill=fill)


CLEAR = (0, 0, 0, 0)


def social_like():
    img, d = glyph_canvas()
    heart(d, 128, 100, 50, WHITE)
    heart(d, 128, 104, 32, CLEAR)
    return img


def social_liked():
    img, d = glyph_canvas()
    heart(d, 128, 100, 50, WHITE)
    return img


def social_comment():
    img, d = glyph_canvas()
    d.rounded_rectangle([36, 48, 220, 180], radius=44, outline=WHITE, width=20)
    d.polygon([(70, 170), (56, 222), (122, 174)], fill=WHITE)
    return img


def social_more():
    img, d = glyph_canvas()
    for x in (64, 128, 192):
        d.ellipse([x - 20, 108, x + 20, 148], fill=WHITE)
    return img


def social_photo():
    img, d = glyph_canvas()
    d.rounded_rectangle([30, 52, 226, 204], radius=22, outline=WHITE, width=18)
    d.polygon([(50, 188), (110, 112), (150, 160), (176, 132), (208, 188)], fill=WHITE)
    d.ellipse([150, 74, 184, 108], fill=WHITE)
    return img


# ---------------- Погода (прозрачный фон) ----------------

def blank():
    return Image.new("RGBA", (S, S), (0, 0, 0, 0))


def weather_sun():
    img = blank()
    sun(ImageDraw.Draw(img), 128, 128, 58, hex_rgb(0xFFD60A))
    return img


def weather_moon():
    img = blank()
    d = ImageDraw.Draw(img)
    d.ellipse([50, 40, 210, 200], fill=hex_rgb(0xF2F2F7))
    d.ellipse([92, 20, 250, 178], fill=(0, 0, 0, 0))
    return img


def weather_rain():
    img = blank()
    d = ImageDraw.Draw(img)
    cloud(d, 128, 100, 1.2, hex_rgb(0xE5E5EA))
    for x in (78, 128, 178):
        d.line([(x + 10, 170), (x - 6, 220)], fill=hex_rgb(0x5AC8FA), width=14)
    return img


def weather_thunder():
    img = blank()
    d = ImageDraw.Draw(img)
    cloud(d, 128, 96, 1.2, hex_rgb(0x8E8E93))
    d.polygon([(136, 140), (96, 200), (126, 200), (110, 244), (166, 178), (134, 178), (152, 140)],
              fill=hex_rgb(0xFFD60A))
    return img


def weather_snow():
    img = blank()
    d = ImageDraw.Draw(img)
    cloud(d, 128, 100, 1.2, WHITE)
    for x, y in ((80, 190), (128, 212), (176, 190)):
        for i in range(3):
            a = i * math.pi / 3
            dx, dy = math.cos(a) * 16, math.sin(a) * 16
            d.line([(x - dx, y - dy), (x + dx, y + dy)], fill=WHITE, width=6)
    return img


def camera_noise():
    """Текстура шума для превью ISO в видоискателе (128x128, серый шум)."""
    import random
    rnd = random.Random(42)
    img = Image.new("RGBA", (128, 128))
    px = img.load()
    for y in range(128):
        for x in range(128):
            v = max(0, min(255, int(rnd.gauss(128, 70))))
            px[x, y] = (v, v, v, 255)
    return img


def mod_icon():
    img = blank()
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([60, 8, 196, 248], radius=30, fill=hex_rgb(0x1B1B1F))
    grad = gradient_square(0x1E3C72, 0x6A3093, radius=20).resize((120, 224))
    mask = Image.new("L", (120, 224), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 119, 223], radius=22, fill=255)
    img.paste(grad, (68, 16), mask)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([108, 22, 148, 32], radius=5, fill=hex_rgb(0x000000))
    colors = [0x34C759, 0x5AC8FA, 0xFF9F0A, 0xFF2D55, 0xFFD60A, 0x8E8E93]
    for i, c in enumerate(colors):
        x, y = 80 + (i % 3) * 34, 60 + (i // 3) * 36
        d.rounded_rectangle([x, y, x + 26, y + 26], radius=7, fill=hex_rgb(c))
    return img


if __name__ == "__main__":
    glyphs = {
        "messenger": glyph_messenger, "telegram": glyph_telegram, "camera": glyph_camera,
        "gallery": glyph_gallery, "music": glyph_music, "notes": glyph_notes,
        "calculator": glyph_calculator, "weather": glyph_weather, "settings": glyph_settings,
        "feed": glyph_feed,
    }
    for name, fn in glyphs.items():
        save(fn(), GLYPH_DIR, name)
    # Старые цветные иконки (дизайн «как айфон») больше не используются.
    if os.path.isdir(APP_DIR):
        for f in os.listdir(APP_DIR):
            os.remove(os.path.join(APP_DIR, f))
        os.rmdir(APP_DIR)
    weather = {"sun": weather_sun, "moon": weather_moon, "rain": weather_rain,
               "thunder": weather_thunder, "snow": weather_snow}
    for name, fn in weather.items():
        save(fn(), WEATHER_DIR, name)
    social = {"like": social_like, "liked": social_liked, "comment": social_comment, "more": social_more,
              "photo": social_photo}
    for name, fn in social.items():
        save(fn(), os.path.join(ASSETS, "textures", "gui", "social"), name, 32)
    save(mod_icon(), ASSETS, "icon", 128)
    os.makedirs(os.path.join(ASSETS, "textures", "gui", "camera"), exist_ok=True)
    camera_noise().save(os.path.join(ASSETS, "textures", "gui", "camera", "noise.png"))
    print("Icons written to", ASSETS)
