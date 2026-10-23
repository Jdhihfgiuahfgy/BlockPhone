"""Звуки примера-игры «Block Strike» (docs/examples/scripts/blockstrike/sounds/*.wav) — синтез на numpy, без записей:
выстрелы (свой у каждого вида оружия), перезарядка, попадания (шаги — ванильные звуки блоков), гранаты, бомба, сигналы раунда.
Пишет ещё sounds.lua — длительность каждого звука (игра по ней следит, сколько звуков звучит одновременно).
Запуск из корня репозитория: python tools/gen_strike_sounds.py
"""
import os
import wave

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "docs", "examples", "scripts", "blockstrike")
OUT = os.path.join(APP, "sounds")
RATE = 44100
rng = np.random.default_rng(1337)
LENGTHS = {}


def t_axis(sec):
    return np.arange(int(sec * RATE)) / RATE


def noise(sec):
    return rng.uniform(-1, 1, int(sec * RATE))


def band(x, lo, hi, slope=1.0):
    """Полосовой фильтр через спектр: lo..hi Гц с мягкими краями."""
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    g = np.ones_like(f)
    if lo > 0:
        g *= 1 / (1 + (lo / np.maximum(f, 1)) ** (2 * slope))
    if hi > 0:
        g *= 1 / (1 + (f / hi) ** (2 * slope))
    return np.fft.irfft(spec * g, len(x))


def env(sec, attack, decay, curve=1.0):
    t = t_axis(sec)
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    return a * np.exp(-((t / decay) ** curve))


def sweep(sec, f0, f1, shape="exp"):
    t = t_axis(sec)
    if shape == "exp":
        f = f0 * (f1 / f0) ** (t / sec)
    else:
        f = f0 + (f1 - f0) * t / sec
    return np.sin(2 * np.pi * np.cumsum(f) / RATE)


def pad(x, sec):
    n = int(sec * RATE)
    if len(x) >= n:
        return x[:n]
    return np.concatenate([x, np.zeros(n - len(x))])


def mix(*parts):
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[:len(p)] += p
    return out


def at(x, sec, total):
    """Сдвинуть звук на sec секунд внутри дорожки длиной total."""
    out = np.zeros(int(total * RATE))
    s = int(sec * RATE)
    n = min(len(x), len(out) - s)
    out[s:s + n] = x[:n]
    return out


def tail(x, room=0.25, mixk=0.35):
    """Простое «эхо помещения»: несколько затухающих повторов через фильтр."""
    out = np.concatenate([x, np.zeros(int(room * 2 * RATE))])
    for d, k in ((0.031, 0.5), (0.047, 0.4), (0.071, 0.3), (0.113, 0.22), (0.167, 0.15)):
        s = int(d * RATE)
        out[s:s + len(x)] += band(x, 0, 2500) * k * mixk * 2
    return out


def save(name, x, gain=0.9):
    x = np.asarray(x, dtype=np.float64)
    peak = np.max(np.abs(x)) or 1
    x = np.tanh(x / peak * 1.4) / np.tanh(1.4) * gain
    # тихий хвост — обрезать
    idx = np.nonzero(np.abs(x) > 0.004)[0]
    if len(idx):
        x = x[:idx[-1] + int(0.01 * RATE)]
    fade = min(len(x), int(0.01 * RATE))
    x[-fade:] *= np.linspace(1, 0, fade)
    data = (x * 32767).astype("<i2").tobytes()
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data)
    LENGTHS[name] = len(x) / RATE


# --- Выстрелы ---

def reverb(x, seconds=0.7, wet=0.35, bright=4000, predelay=0.012):
    """Реверберация свёрткой: импульс помещения — затухающий шум, который со временем темнеет (стены глушат верх)."""
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    ir_noise = rng.normal(0, 1, n)
    early = band(ir_noise, 200, bright) * np.exp(-t / (seconds * 0.18))
    late = band(ir_noise, 120, bright * 0.35) * np.exp(-t / (seconds * 0.4))
    ir = early * 0.6 + late
    # ранние отражения — отдельные «щелчки» от ближних стен
    for d, k in ((0.009, 0.7), (0.017, 0.5), (0.026, 0.4), (0.041, 0.3)):
        i = int(d * RATE)
        if i < n:
            ir[i] += k * 3
    ir = np.concatenate([np.zeros(int(predelay * RATE)), ir])
    ir /= np.sqrt(np.sum(ir ** 2)) + 1e-9
    size = len(x) + len(ir)
    wet_sig = np.fft.irfft(np.fft.rfft(x, size) * np.fft.rfft(ir, size), size)
    dry_sig = np.concatenate([x, np.zeros(size - len(x))])
    return dry_sig + wet_sig * wet * 1.1


def tail(x, room=0.25, mixk=0.35):
    return reverb(x, 0.3 + room * 1.6, 0.18 + mixk * 0.3)


def damped(freq, sec, decay, attack=0.0008):
    t = t_axis(sec)
    return np.sin(2 * np.pi * freq * t) * env(sec, attack, decay)


def metal_ping(freqs, sec, decay):
    """Металлический звон (затвор, гильза): несколько негармонических резонансов."""
    out = np.zeros(int(sec * RATE))
    for i, f in enumerate(freqs):
        out += damped(f, sec, decay * (1 - i * 0.12), 0.0003) * (0.8 ** i)
    return out


def gunshot(boom_hz, crack_lo, crack_hi, length, body=1.0, crack=1.0, room=0.3, mech=0.0):
    """Выстрел из слоёв: сверхзвуковой щелчок, ударная волна (низкий удар), корпус (шум средних частот), затвор,
    звон гильзы и помещение (свёртка). Параметры подобраны под калибр: пистолет — сухо и коротко, винтовка — плотнее."""
    sec = length
    n = noise(sec)
    snap = band(n, crack_lo, crack_hi, 1.5) * env(sec, 0.0002, 0.006 + 0.006 * crack) * crack * 2.2
    blast = band(n, 150, 2200) * env(sec, 0.0006, 0.022 + 0.03 * body, 0.9) * body * 1.4
    thump = (sweep(sec, boom_hz * 2.2, boom_hz * 0.6) * env(sec, 0.0008, 0.035 + 0.04 * body)
             + damped(boom_hz * 0.55, sec, 0.06 + 0.05 * body) * 0.6) * body * 1.3
    out = mix(snap, blast, thump)
    out = np.tanh(out * 2.2)  # плотность, «панч»
    if mech > 0:
        bolt = (band(noise(0.04), 1800, 9000) * env(0.04, 0.0002, 0.006) + metal_ping((2300, 3700, 5900), 0.04, 0.008) * 0.4)
        out = mix(out, at(bolt * mech, 0.035, sec))
        casing = metal_ping((4200, 6100, 7900), 0.15, 0.035) * 0.18 * mech
        out = mix(out, at(casing, min(sec - 0.16, 0.22 + rng.uniform(0, 0.05)), sec))
    return reverb(out, 0.35 + room * 1.4, 0.16 + room * 0.25)


def suppressed():
    sec = 0.3
    n = noise(sec)
    puff = band(n, 250, 2200) * env(sec, 0.001, 0.025) * 1.2
    thump = damped(140, sec, 0.03) * 0.5
    click_ = band(noise(0.03), 2500, 9000) * env(0.03, 0.0002, 0.005) * 0.6
    slide = at(band(noise(0.05), 1500, 7000) * env(0.05, 0.001, 0.012) * 0.45 + metal_ping((2600, 4100), 0.05, 0.01) * 0.3,
               0.05, sec)
    return reverb(mix(puff, thump, click_, slide), 0.35, 0.12)


def shotgun():
    sec = 0.8
    out = gunshot(65, 500, 6500, sec, body=1.8, crack=1.1, room=0.45)
    pump1 = band(noise(0.07), 700, 5000) * env(0.07, 0.002, 0.02) + metal_ping((1900, 3100), 0.07, 0.015) * 0.3
    pump2 = band(noise(0.07), 900, 6000) * env(0.07, 0.002, 0.018) + metal_ping((2200, 3500), 0.07, 0.015) * 0.3
    return mix(out, at(pump1 * 0.35, 0.45, 0.95), at(pump2 * 0.4, 0.6, 0.95))


def awp():
    sec = 1.1
    out = gunshot(50, 900, 10000, sec, body=2.2, crack=1.8, room=0.7)
    bolt = mix(at(band(noise(0.05), 1200, 6000) * env(0.05, 0.001, 0.012) + metal_ping((1800, 2900), 0.05, 0.012) * 0.3,
                  0.0, 0.22),
               at(band(noise(0.05), 900, 5000) * env(0.05, 0.001, 0.014) + metal_ping((1500, 2600), 0.05, 0.012) * 0.3,
                  0.14, 0.22))
    return mix(out, at(bolt * 0.35, 0.75, 1.3))


# --- Остальное ---

def click(lo=2000, hi=8000, sec=0.04, decay=0.006, gain=1.0):
    return band(noise(sec), lo, hi) * env(sec, 0.0003, decay) * gain


def knife_swing():
    sec = 0.28
    n = band(noise(sec), 1500, 7000)
    e = np.sin(np.linspace(0, np.pi, len(n))) ** 2
    return n * e * 0.6


def knife_hit():
    sec = 0.25
    thud = band(noise(sec), 80, 700) * env(sec, 0.001, 0.05)
    slash = band(noise(sec), 2000, 6000) * env(sec, 0.002, 0.03) * 0.5
    return mix(thud, slash)


def hit_body():
    sec = 0.2
    return mix(band(noise(sec), 90, 600) * env(sec, 0.001, 0.035), sweep(sec, 180, 90) * env(sec, 0.001, 0.03) * 0.6)


def hit_head():
    sec = 0.45
    ding = (np.sin(2 * np.pi * 1850 * t_axis(sec)) + 0.5 * np.sin(2 * np.pi * 3700 * t_axis(sec))) * env(sec, 0.0005, 0.12)
    tink = click(3000, 9000, sec, 0.01, 0.8)
    return mix(ding * 0.7, tink, hit_body() * 0.6)


def hurt():
    sec = 0.3
    thump = band(noise(sec), 60, 400) * env(sec, 0.001, 0.06)
    grunt = sweep(sec, 220, 140) * env(sec, 0.01, 0.12) * 0.5
    grunt = band(grunt + band(noise(sec), 200, 900) * 0.25 * env(sec, 0.01, 0.1), 100, 1200)
    return mix(thump, grunt)


def death():
    sec = 0.7
    v = sweep(sec, 260, 110) + 0.4 * sweep(sec, 520, 220)
    v = band(v + noise(sec) * 0.15, 100, 1500) * env(sec, 0.02, 0.35)
    fall = at(band(noise(0.2), 60, 500) * env(0.2, 0.001, 0.05), 0.45, sec)
    return mix(v * 0.7, fall)


def reload_out():
    sec = 0.3
    return mix(click(1500, 6000, 0.05, 0.01), at(band(noise(0.15), 400, 3000) * env(0.15, 0.01, 0.04) * 0.6, 0.07, sec))


def reload_in():
    sec = 0.55
    slide = at(band(noise(0.1), 500, 3500) * env(0.1, 0.01, 0.03) * 0.5, 0.0, sec)
    seat = at(click(1200, 6000, 0.05, 0.01, 1.0), 0.1, sec)
    bolt1 = at(click(1500, 7000, 0.05, 0.012, 0.8), 0.3, sec)
    bolt2 = at(click(1000, 6000, 0.05, 0.012, 0.9), 0.4, sec)
    return mix(slide, seat, bolt1, bolt2)


def dry():
    return click(3000, 9000, 0.05, 0.004)


def deploy():
    sec = 0.25
    return mix(band(noise(sec), 600, 4000) * env(sec, 0.01, 0.05) * 0.4, at(click(1500, 6000, 0.04, 0.008, 0.7), 0.12, sec))


def nade_throw():
    sec = 0.35
    pin = click(3000, 9000, 0.03, 0.005, 0.8)
    whoosh = band(noise(sec), 400, 3000) * np.sin(np.linspace(0, np.pi, int(sec * RATE))) ** 2 * 0.5
    return mix(pin, at(whoosh, 0.06, 0.45))


def nade_bounce():
    sec = 0.12
    return mix(click(800, 4000, sec, 0.015), sweep(sec, 900, 600) * env(sec, 0.001, 0.02) * 0.4)


def he_explode():
    sec = 1.6
    n = noise(sec)
    blast = band(n, 30, 3000) * env(sec, 0.001, 0.25, 0.8)
    low = sweep(sec, 70, 30) * env(sec, 0.002, 0.4) * 1.2
    debris = band(noise(sec), 2000, 7000) * env(sec, 0.05, 0.5) * 0.15 * (rng.uniform(0, 1, int(sec * RATE)) > 0.97)
    return reverb(mix(np.tanh(mix(blast, low) * 1.8), debris), 1.4, 0.35)


def flash_bang():
    sec = 1.2
    pop = band(noise(sec), 500, 9000) * env(sec, 0.0005, 0.05)
    ring = np.sin(2 * np.pi * 3800 * t_axis(sec)) * env(sec, 0.05, 0.8) * 0.12
    return tail(mix(pop, ring), 0.4, 0.4)


def smoke_hiss():
    sec = 2.2
    t = t_axis(sec)
    e = np.clip(t / 0.15, 0, 1) * np.exp(-t / 1.1)
    return mix(band(noise(sec), 1500, 9000) * e * 0.6, click(800, 4000, 0.05, 0.01, 0.6))


def beep(freq=2600, sec=0.09):
    t = t_axis(sec)
    return np.sin(2 * np.pi * freq * t) * np.clip(t / 0.003, 0, 1) * np.clip((sec - t) / 0.01, 0, 1)


def bomb_plant():
    total = 1.0
    out = np.zeros(int(total * RATE))
    for i, f in enumerate((1900, 2200, 1700, 2400, 2000, 2300, 2100)):
        out = mix(out, at(beep(f, 0.06) * 0.6, i * 0.13, total))
    return out


def bomb_planted():
    total = 0.8
    return mix(at(click(1000, 5000, 0.05, 0.01), 0, total), at(beep(2800, 0.12), 0.2, total), at(beep(2800, 0.12), 0.45, total))


def defuse():
    total = 0.5
    return mix(at(click(1500, 6000, 0.04, 0.008), 0, total), at(click(1200, 5000, 0.04, 0.008, 0.8), 0.18, total),
               at(click(2000, 7000, 0.04, 0.006, 0.7), 0.33, total))


def defused():
    total = 0.9
    t = t_axis(0.25)
    a = np.sin(2 * np.pi * 880 * t) * env(0.25, 0.005, 0.12)
    b = np.sin(2 * np.pi * 1320 * t) * env(0.25, 0.005, 0.15)
    return mix(at(a, 0, total), at(b, 0.18, total))


def bomb_explode():
    sec = 3.0
    blast = band(noise(sec), 20, 2500) * env(sec, 0.002, 0.6, 0.7)
    low = sweep(sec, 55, 22) * env(sec, 0.003, 0.9) * 1.4
    crackle = band(noise(sec), 1500, 6000) * env(sec, 0.1, 1.2) * 0.25 * (rng.uniform(0, 1, int(sec * RATE)) > 0.95)
    return reverb(mix(np.tanh(mix(blast, low) * 1.8), crackle), 2.0, 0.4)


def chord(freqs, sec, decay, delay=0.0):
    t = t_axis(sec)
    out = np.zeros(len(t))
    for i, f in enumerate(freqs):
        tone = (np.sin(2 * np.pi * f * t) + 0.3 * np.sin(4 * np.pi * f * t)) * env(sec, 0.01, decay)
        out = mix(out, at(tone, i * delay, sec))
    return out


def ricochet():
    sec = 0.35
    return mix(sweep(sec, 3200, 1400) * env(sec, 0.001, 0.12) * 0.35, click(2000, 8000, 0.03, 0.004, 0.8))


def impact():
    sec = 0.12
    return mix(click(1200, 6000, sec, 0.01), band(noise(sec), 150, 900) * env(sec, 0.001, 0.02) * 0.6)


def buy():
    total = 0.45
    return mix(at(click(2500, 8000, 0.04, 0.006, 0.6), 0, total), at(chord([1568, 2093], 0.3, 0.1, 0.05) * 0.5, 0.05, total))


def pickup():
    return mix(click(800, 5000, 0.08, 0.02), at(click(1500, 6000, 0.05, 0.01, 0.7), 0.08, 0.2))


def kill():
    return chord([1046, 1568], 0.35, 0.1, 0.06) * 0.6


def main():
    # выстрелы: (бас Гц, полоса треска, длина, корпус, треск, помещение, затвор)
    save("shot_glock", gunshot(150, 1500, 8000, 0.35, body=0.6, crack=1.0, room=0.25, mech=0.4))
    save("shot_usp", suppressed())
    save("shot_p250", gunshot(130, 1300, 7500, 0.38, body=0.75, crack=1.0, room=0.28, mech=0.4))
    save("shot_deagle", gunshot(85, 900, 7000, 0.6, body=1.6, crack=1.5, room=0.45, mech=0.5))
    save("shot_nova", shotgun())
    save("shot_mp9", gunshot(170, 1700, 8000, 0.28, body=0.55, crack=0.8, room=0.22, mech=0.3))
    save("shot_p90", gunshot(160, 1600, 8000, 0.3, body=0.6, crack=0.85, room=0.22, mech=0.3))
    save("shot_galil", gunshot(105, 1100, 7500, 0.42, body=1.0, crack=1.1, room=0.3, mech=0.3))
    save("shot_famas", gunshot(115, 1200, 7800, 0.4, body=0.95, crack=1.1, room=0.3, mech=0.3))
    save("shot_ak47", gunshot(95, 1000, 7000, 0.45, body=1.2, crack=1.3, room=0.32, mech=0.3))
    save("shot_m4a4", gunshot(110, 1300, 8000, 0.42, body=1.0, crack=1.1, room=0.3, mech=0.3))
    save("shot_awp", awp())
    save("knife_swing", knife_swing(), 0.6)
    save("knife_hit", knife_hit())
    save("hit_body", hit_body(), 0.7)
    save("hit_head", hit_head(), 0.75)
    save("hurt", hurt(), 0.8)
    save("death", death(), 0.8)
    save("reload_out", reload_out(), 0.7)
    save("reload_in", reload_in(), 0.75)
    save("dry", dry(), 0.6)
    save("deploy", deploy(), 0.6)
    save("nade_throw", nade_throw(), 0.6)
    save("nade_bounce", nade_bounce(), 0.6)
    save("he_explode", he_explode())
    save("flash_bang", flash_bang())
    save("smoke_hiss", smoke_hiss(), 0.6)
    save("bomb_beep", beep(2600, 0.09), 0.55)
    save("bomb_plant", bomb_plant(), 0.6)
    save("bomb_planted", bomb_planted(), 0.6)
    save("defuse", defuse(), 0.7)
    save("defused", defused(), 0.7)
    save("bomb_explode", bomb_explode())
    save("round_start", chord([523, 659, 784], 0.6, 0.25, 0.07), 0.55)
    save("round_win", chord([523, 659, 784, 1046], 1.0, 0.4, 0.1), 0.6)
    save("round_lose", chord([392, 311, 262], 1.0, 0.4, 0.14), 0.6)
    save("ricochet", ricochet(), 0.5)
    save("impact", impact(), 0.5)
    save("buy", buy(), 0.6)
    save("pickup", pickup(), 0.6)
    save("kill", kill(), 0.6)
    with open(os.path.join(APP, "sounds.lua"), "w", encoding="utf-8", newline="\n") as f:
        f.write("-- Длительность звуков sounds/*.wav в секундах. Создаёт tools/gen_strike_sounds.py.\n")
        f.write("return {\n")
        for k in sorted(LENGTHS):
            f.write("  %s = %.2f,\n" % (k, LENGTHS[k]))
        f.write("}\n")
    total = sum(os.path.getsize(os.path.join(OUT, n)) for n in os.listdir(OUT))
    print("ok", len(LENGTHS), "sounds,", total // 1024, "KB")


if __name__ == "__main__":
    main()
