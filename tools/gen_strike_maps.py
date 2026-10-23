"""Многоуровневые карты Block Strike (docs/examples/scripts/blockstrike/mapdata.lua) и их радары.

Карта собирается из прямоугольников: комнаты и коридоры на своей высоте, лестницы и пандусы (пол растёт по 0,25 блока за
клетку — по ним ходят без прыжка), крыши (тоннели, балконы, дома), ящики. По мотивам классических карт CS: длинные
проходы, возвышенности, тоннели, «мид», окна и балконы над точками.

Запуск из корня репозитория: python tools/gen_strike_maps.py
"""
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "docs", "examples", "scripts", "blockstrike")
LEVEL = 0.25


def level_char(h):
    n = int(round(h / LEVEL))
    assert 0 <= n < 36, h
    return str(n) if n < 10 else chr(ord("a") + n - 10)


class Map:
    def __init__(self, key, name, w, h, wall_h):
        self.key, self.name, self.w, self.h, self.wall_h = key, name, w, h, wall_h
        self.c = [["#"] * w for _ in range(h)]
        self.base = [[0.0] * w for _ in range(h)]
        self.roof = [[None] * w for _ in range(h)]
        self.props = {}
        self.lanes = {}

    # Прямоугольник клеток x0..x1, z0..z1 включительно.
    def cells(self, x0, z0, x1, z1):
        for z in range(min(z0, z1), max(z0, z1) + 1):
            for x in range(min(x0, x1), max(x0, x1) + 1):
                yield x, z

    def room(self, x0, z0, x1, z1, h=0.0, ch="."):
        for x, z in self.cells(x0, z0, x1, z1):
            self.c[z][x] = ch
            self.base[z][x] = h

    def wall(self, x0, z0, x1, z1, ch="#"):
        for x, z in self.cells(x0, z0, x1, z1):
            self.c[z][x] = ch
            self.roof[z][x] = None

    def stairs(self, x0, z0, x1, z1, direction, h0, h1, ch="."):
        """Лестница: высота меняется от h0 до h1 вдоль direction ('x+', 'x-', 'z+', 'z-'), шаг — ровно 0,25 на клетку
        (длину берём по числу клеток; если ступеней меньше — последние клетки ровные)."""
        xs = range(min(x0, x1), max(x0, x1) + 1)
        zs = range(min(z0, z1), max(z0, z1) + 1)
        along = list(xs) if direction[0] == "x" else list(zs)
        if direction[1] == "-":
            along = along[::-1]
        steps = int(round(abs(h1 - h0) / LEVEL))
        sign = 1 if h1 >= h0 else -1
        for k, a in enumerate(along):
            hk = h0 + sign * min(k + 1, steps) * LEVEL if k < len(along) else h1
            if k >= steps:
                hk = h1
            for b in (zs if direction[0] == "x" else xs):
                x, z = (a, b) if direction[0] == "x" else (b, a)
                self.c[z][x] = ch
                self.base[z][x] = hk
        assert len(along) >= steps, "лестница короче перепада: %s" % ((x0, z0, x1, z1, h0, h1),)

    def roof_over(self, x0, z0, x1, z1, h):
        for x, z in self.cells(x0, z0, x1, z1):
            if self.c[z][x] not in "#%":
                self.roof[z][x] = h

    def window(self, x, z):
        """Окно-проём в стене между двумя открытыми клетками: подоконник 0,45 над более высоким полом, перемычка на 1,25 —
        видно и можно стрелять, но не пролезть (щель ниже роста игрока)."""
        sides = [(x - 1, z, x + 1, z), (x, z - 1, x, z + 1)]
        for ax, az, bx, bz in sides:
            if self.c[az][ax] not in "#%W" and self.c[bz][bx] not in "#%W":
                h = max(self.base[az][ax], self.base[bz][bx])
                self.c[z][x] = "W"
                self.base[z][x] = h
                self.roof[z][x] = h + 1.25
                return
        raise AssertionError("%s: окно %s без открытых клеток с двух сторон" % (self.key, (x, z)))

    def box(self, x, z, ch="x"):
        assert self.c[z][x] not in "#%", (self.key, x, z)
        self.c[z][x] = ch

    def site(self, x0, z0, x1, z1, letter):
        for x, z in self.cells(x0, z0, x1, z1):
            if self.c[z][x] in ".,":
                self.c[z][x] = letter

    def spawn(self, x0, z0, x1, z1, team):
        for x, z in self.cells(x0, z0, x1, z1):
            if self.c[z][x] in ".,":
                self.c[z][x] = team

    def check(self):
        """Каждая ходовая клетка достижима от баз (с учётом ступеней) — иначе ошибка в разметке."""
        walk = lambda x, z: self.c[z][x] not in "#%xXW"
        start = [(x, z) for z in range(self.h) for x in range(self.w) if self.c[z][x] == "T"]
        seen = set(start)
        stack = list(start)
        while stack:
            x, z = stack.pop()
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                if 0 <= nx < self.w and 0 <= nz < self.h and (nx, nz) not in seen and walk(nx, nz):
                    if self.base[nz][nx] - self.base[z][x] <= 0.3:
                        seen.add((nx, nz))
                        stack.append((nx, nz))
        for need in "CAB":
            cells = [(x, z) for z in range(self.h) for x in range(self.w) if self.c[z][x] == need]
            if cells:
                assert any(c in seen for c in cells), "%s: %s недостижима от T" % (self.key, need)
        lost = [(x, z) for z in range(self.h) for x in range(self.w) if walk(x, z) and (x, z) not in seen]
        if lost:
            print("  %s: недостижимых клеток %d (например %s)" % (self.key, len(lost), lost[:3]))
        # Базы не должны простреливаться друг с другом (команды не видят друг друга со спавна).
        ts = [(x, z) for z in range(self.h) for x in range(self.w) if self.c[z][x] == "T"]
        cs = [(x, z) for z in range(self.h) for x in range(self.w) if self.c[z][x] == "C"]
        for a in ts[::3]:
            for b in cs[::3]:
                assert not self.sight(a, b), "%s: база T видна с базы CT (%s → %s)" % (self.key, a, b)
        # Время пути (в клетках): для баланса — CT должны успевать на точки раньше T, мид — примерно поровну.
        dt, dc = self.distances("T"), self.distances("C")
        info = []
        for s_ in "AB":
            cells = [(x, z) for z in range(self.h) for x in range(self.w) if self.c[z][x] == s_]
            if cells:
                info.append("%s: T %d / CT %d" % (s_, min(dt.get(c, 999) for c in cells), min(dc.get(c, 999) for c in cells)))
        print("  %s: путь до точек — %s" % (self.key, ", ".join(info)))

    def sight(self, a, b):
        """Прямая видимость на плане: стены и большие ящики закрывают (шаг 0,25 клетки)."""
        (x1, z1), (x2, z2) = a, b
        n = int(max(abs(x2 - x1), abs(z2 - z1)) * 4) + 1
        for k in range(1, n):
            x = x1 + 0.5 + (x2 - x1) * k / n
            z = z1 + 0.5 + (z2 - z1) * k / n
            if self.c[int(z)][int(x)] in "#%X":
                return False
        return True

    def distances(self, start_ch):
        from collections import deque
        walk = lambda x, z: self.c[z][x] not in "#%xXW"
        start = [(x, z) for z in range(self.h) for x in range(self.w) if self.c[z][x] == start_ch]
        dist = {p: 0 for p in start}
        q = deque(start)
        while q:
            x, z = q.popleft()
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                if 0 <= nx < self.w and 0 <= nz < self.h and (nx, nz) not in dist and walk(nx, nz) \
                        and self.base[nz][nx] - self.base[z][x] <= 0.3:
                    dist[(nx, nz)] = dist[(x, z)] + 1
                    q.append((nx, nz))
        return dist

    def reachable(self):
        """Клетки, куда можно дойти от базы T (вверх — не больше 0,3 за шаг, ящики и стены не проходятся)."""
        walk = lambda x, z: self.c[z][x] not in "#%xXW"
        start = [(x, z) for z in range(self.h) for x in range(self.w) if self.c[z][x] == "T"]
        seen = set(start)
        stack = list(start)
        while stack:
            x, z = stack.pop()
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                if 0 <= nx < self.w and 0 <= nz < self.h and (nx, nz) not in seen and walk(nx, nz):
                    if self.base[nz][nx] - self.base[z][x] <= 0.3:
                        seen.add((nx, nz))
                        stack.append((nx, nz))
        return seen

    def furnish(self, seed, spacing=3, min_clear=2):
        """Укрытия на открытых местах: в каждой точке, где до стены не меньше min_clear клеток, ставится одна из фигур
        (стопка ящиков, ящик со ступенькой, колонна, низкая стенка, «уголок»), не ближе spacing к другой. Только на ровном
        полу (не на лестницах и краях уступов), не на базах; после каждой — проверка, что всё осталось достижимым."""
        import random as _r
        rng = _r.Random(seed)
        walkable = lambda x, z: 0 <= x < self.w and 0 <= z < self.h and self.c[z][x] in ".,AB"
        # расстояние до ближайшей непроходимой клетки (по Чебышёву)
        dist = {}
        frontier = []
        for z in range(self.h):
            for x in range(self.w):
                if not walkable(x, z) and self.c[z][x] not in "TC":
                    dist[(x, z)] = 0
                    frontier.append((x, z))
        d = 0
        while frontier:
            d += 1
            nxt = []
            for x, z in frontier:
                for dx in (-1, 0, 1):
                    for dz in (-1, 0, 1):
                        p = (x + dx, z + dz)
                        if 0 <= p[0] < self.w and 0 <= p[1] < self.h and p not in dist:
                            dist[p] = d
                            nxt.append(p)
            frontier = nxt

        def flat(x, z):
            b = self.base[z][x]
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    nx, nz = x + dx, z + dz
                    if not (0 <= nx < self.w and 0 <= nz < self.h):
                        return False
                    if self.c[nz][nx] in "TC":
                        return False
                    if walkable(nx, nz) and abs(self.base[nz][nx] - b) > 0.01:
                        return False
            return True

        shapes = [
            [(0, 0, "X"), (1, 0, "X"), (0, 1, "X"), (1, 1, "X")],      # стопка ящиков
            [(0, 0, "X"), (1, 0, "x")],                                 # ящик со ступенькой
            [(0, 0, "X"), (0, 1, "x")],
            [(0, 0, "%"), (1, 0, "%"), (0, 1, "%"), (1, 1, "%")],      # колонна
            [(0, 0, "x"), (1, 0, "x"), (2, 0, "x")],                    # низкая стенка
            [(0, 0, "x"), (0, 1, "x"), (0, 2, "x")],
            [(0, 0, "X"), (1, 0, "X"), (0, 1, "X")],                    # уголок
            [(0, 0, "X")],
        ]
        cand = [(x, z) for (x, z), dd in dist.items() if dd >= min_clear and walkable(x, z)]
        rng.shuffle(cand)
        placed = []
        before = self.reachable()
        count = 0
        for x, z in cand:
            # в широких местах — гуще, в коридорах (до стены 2 клетки) — редко, чтобы не загородить проход
            gap = spacing if dist[(x, z)] >= 3 else spacing * 2
            if any(max(abs(x - px), abs(z - pz)) < gap for px, pz in placed):
                continue
            shape = rng.choice(shapes)
            cells = [(x + dx, z + dz, ch) for dx, dz, ch in shape]
            if not all(walkable(cx, cz) and flat(cx, cz) and dist.get((cx, cz), 0) >= (2 if dist[(x, z)] >= 3 else 1)
                       for cx, cz, _ in cells):
                continue
            old = [(cx, cz, self.c[cz][cx]) for cx, cz, _ in cells]
            for cx, cz, ch in cells:
                self.c[cz][cx] = ch
            after = self.reachable()
            lost = before - after - {(cx, cz) for cx, cz, _ in cells}
            sites_ok = all(any(self.c[zz][xx] == s for zz in range(self.h) for xx in range(self.w)) for s in "AB"
                           if any(oc == s for _, _, oc in old)) if any(oc in "AB" for _, _, oc in old) else True
            if lost or not sites_ok:
                for cx, cz, ch in old:
                    self.c[cz][cx] = ch
                continue
            before = after
            placed.append((x, z))
            count += 1
        return count

    def lua(self):
        rows = ["".join(r) for r in self.c]
        levels = ["".join(level_char(self.base[z][x]) for x in range(self.w)) for z in range(self.h)]
        roofs = ["".join("." if self.roof[z][x] is None else level_char(self.roof[z][x]) for x in range(self.w))
                 for z in range(self.h)]
        p = self.props
        out = ['  {key = "%s", name = "%s", wallH = %s,%s' % (self.key, self.name, self.wall_h,
                                                            " wingman = true," if self.props.get("wingman") else "")]
        for k in ("sky", "fog"):
            out.append('    %s = "%s",' % (k, p[k]))
        out.append("    fogNear = %d, fogFar = %d," % (p["fogNear"], p["fogFar"]))
        out.append("    steps = {%s}," % ", ".join('%s = "%s"' % kv for kv in p["steps"].items()))
        out.append("    tex = {%s}," % ", ".join('%s = "%s"' % kv for kv in p["tex"].items()))
        if "light" in p:
            out.append("    light = {%s}," % ", ".join(str(v) for v in p["light"]))
        out.append("    lanes = {%s}," % ", ".join("%s = {%s}" % (s, ", ".join("{%d, %d}" % pt for pt in pts))
                                                   for s, pts in self.lanes.items()))
        for name, grid in (("rows", rows), ("levels", levels), ("roofs", roofs)):
            out.append("    %s = {" % name)
            out.extend('      "%s",' % r for r in grid)
            out.append("    },")
        out.append("  },")
        return "\n".join(out)

    def radar(self, k=4):
        """Радар: цвет по типу клетки, светлее — выше; крыши темнее (тоннели); A и B — буквы. Отражено по X (как hud.lua)."""
        img = Image.new("RGBA", (self.w * k, self.h * k))
        d = ImageDraw.Draw(img)
        colors = {"#": (46, 50, 56), "%": (64, 70, 78), "W": (90, 120, 140), "X": (150, 120, 80), "x": (176, 150, 104), ".": (120, 124, 120),
                  ",": (104, 108, 106), "A": (178, 104, 92), "B": (178, 104, 92), "T": (170, 150, 96), "C": (96, 130, 170)}
        for z in range(self.h):
            for x in range(self.w):
                ch = self.c[z][x]
                c = colors.get(ch, (120, 124, 120))
                if ch not in "#%W":
                    lift = 1 + self.base[z][x] * 0.12
                    c = tuple(min(255, int(v * lift)) for v in c)
                    if self.roof[z][x] is not None:
                        c = tuple(int(v * 0.72) for v in c)
                rx = self.w - 1 - x
                d.rectangle([rx * k, z * k, rx * k + k - 1, z * k + k - 1], fill=c + (255,))
        for s in "AB":
            cells = [(self.w - 1 - x, z) for z in range(self.h) for x in range(self.w) if self.c[z][x] == s]
            if cells:
                cx = sum(c[0] for c in cells) / len(cells) * k + k / 2
                cz = sum(c[1] for c in cells) / len(cells) * k + k / 2
                d.text((cx - 4, cz - 6), s, fill=(255, 240, 230, 255))
        os.makedirs(os.path.join(APP, "radar"), exist_ok=True)
        img.save(os.path.join(APP, "radar", self.key + ".png"))


# --- Общие правила новых карт ---
# Проходы — 3 клетки, точки — около 10×9 с ящиками; две точки, мид с изломами (базы не простреливаются друг с другом),
# шорт и лонг к одной точке, тёмный крытый проход (тёмка/тоннели) к другой, связки между мидом и точками, пути CT.

DESERT = {"sky": "#FF9CC7E8", "fog": "#FFDCD6C8", "fogNear": 24, "fogFar": 70,
          "steps": {"floor": "sand", "floor2": "stone", "box": "wood"},
          "tex": {"wall": "sandstone", "wall2": "sandbrick", "floor": "sand", "floor2": "tiles", "site": "site_sand",
                  "crate": "crate", "box": "box", "ledge": "sandbrick", "roof": "planks", "roofTop": "sandbrick"}}


def boxes(m, *cells):
    for x, z, ch in cells:
        m.box(x, z, ch)


# --- Карта 1: «Каньон» ---
# T внизу, CT вверху (выше). A — северо-восток: лонг (длинный путь с изломом у «дверей лонга» и подъёмом) и шорт (из
# мида лестница на кэтвок). B — северо-запад: тёмные тоннели (верхний от базы T, нижний из мида). Мид с двумя изломами,
# двери мида, CT-мид; CT ходят на A, на B через «двери B» и через окно B.

def canyon():
    m = Map("canyon", "Каньон", 64, 64, 5.0)
    m.props = dict(DESERT)
    m.room(27, 54, 37, 59, 0.5)
    m.spawn(28, 55, 36, 58, "T")
    m.room(27, 3, 37, 8, 1.5)
    m.spawn(28, 4, 36, 7, "C")
    # Точки.
    m.room(46, 7, 56, 16, 1.5)
    m.site(47, 8, 55, 15, "A")
    boxes(m, (50, 11, "X"), (51, 11, "X"), (48, 14, "x"), (54, 9, "X"), (55, 9, "x"))
    m.room(7, 7, 17, 16, 1.0)
    m.site(8, 8, 16, 15, "B")
    boxes(m, (11, 10, "X"), (11, 11, "X"), (15, 14, "x"), (9, 15, "X"))
    # CT → A и CT → B («двери B»), CT-мид.
    m.room(38, 8, 45, 10, 1.5)
    m.room(22, 8, 26, 10, 1.5)
    m.stairs(18, 8, 21, 10, "x-", 1.5, 1.0)
    m.room(24, 11, 28, 17, 0.5)
    m.stairs(26, 9, 28, 12, "z-", 0.5, 1.5)
    m.stairs(18, 13, 21, 15, "x-", 0.5, 1.0)  # окно B из CT-мида
    m.room(22, 13, 23, 15, 0.5)
    # Мид: от базы T вниз, излом на запад, мид, двери мида.
    m.room(31, 39, 33, 53, 0.0)
    m.stairs(31, 52, 33, 53, "z-", 0.5, 0.0)
    m.room(24, 36, 33, 38, 0.0)
    m.room(24, 20, 26, 35, 0.0)
    m.box(24, 28, "x")
    m.stairs(24, 18, 26, 19, "z-", 0.0, 0.5)
    m.wall(25, 18, 25, 18, "%")  # створка дверей мида
    # Лонг A: от базы T на восток, вниз, на север, излом у дверей лонга, подъём к точке.
    m.room(38, 55, 50, 57, 0.5)
    m.stairs(51, 55, 52, 57, "x+", 0.5, 0.0)
    m.room(53, 55, 57, 57, 0.0)
    m.room(55, 42, 57, 54, 0.0)
    m.box(56, 47, "X")
    m.room(51, 39, 57, 41, 0.0)
    m.wall(57, 39, 57, 39, "%")
    m.room(51, 23, 53, 38, 0.0)
    m.stairs(51, 17, 53, 22, "z-", 0.0, 1.5)
    m.box(52, 30, "x")
    # Шорт A: из излома мида на восток, лестница на кэтвок, кэтвок на север, вход на точку с юго-запада.
    m.room(34, 36, 41, 38, 0.0)
    m.stairs(39, 30, 41, 35, "z-", 0.0, 1.5)
    m.room(39, 20, 41, 29, 1.5)
    m.room(39, 17, 48, 19, 1.5)
    m.box(40, 24, "x")
    # B: тёмка — верхний тоннель от базы T, нижний тоннель из мида; вход на точку снизу.
    m.room(10, 55, 26, 57, 0.5)
    m.room(10, 32, 12, 54, 0.5)
    m.roof_over(10, 32, 12, 54, 2.4)
    m.room(8, 25, 14, 31, 0.5)
    m.roof_over(8, 25, 14, 31, 2.4)
    m.box(13, 27, "X")
    m.stairs(9, 17, 12, 24, "z-", 0.5, 1.0)
    m.room(15, 29, 23, 31, 0.0)
    m.stairs(13, 29, 14, 31, "x-", 0.0, 0.5)
    m.roof_over(13, 29, 23, 31, 2.2)
    # Дом у кэтвока: комната с окном на лонг (с кэтвока — дверь).
    m.room(42, 26, 49, 27, 1.5)
    m.roof_over(42, 26, 49, 27, 3.25)
    m.window(50, 26)
    m.lanes = {"A": [(56, 48), (40, 32)], "B": [(11, 40), (18, 30)]}
    return m


# --- Карта 2: «Оазис» ---
# T справа внизу, CT слева вверху. A — пандус от T, дворец (крытый подъём с балконом над A), «джунгли» из коннектора.
# B — квартиры (второй этаж, тёмные), «катакомбы» из мида (крытый короткий путь), рынок от CT. Мид с изломом,
# окно CT над мидом, выход из мида к CT.

def oasis():
    m = Map("oasis", "Оазис", 64, 64, 5.5)
    m.props = {"sky": "#FFA9C9E6", "fog": "#FFE2D9C6", "fogNear": 24, "fogFar": 72,
               "steps": {"floor": "stone", "floor2": "sand", "box": "wood"},
               "tex": {"wall": "plaster", "wall2": "sandbrick", "floor": "tiles", "floor2": "sand", "site": "site_sand",
                       "crate": "crate", "box": "box", "ledge": "sandstone", "roof": "wood_panel", "roofTop": "sandstone"}}
    m.room(46, 53, 57, 59, 0.0)
    m.spawn(47, 54, 56, 58, "T")
    m.room(5, 3, 15, 8, 1.0)
    m.spawn(6, 4, 14, 7, "C")
    # Точки.
    m.room(42, 9, 52, 18, 1.0)
    m.site(43, 10, 51, 17, "A")
    boxes(m, (46, 13, "X"), (47, 13, "X"), (44, 16, "x"), (50, 11, "x"), (51, 16, "X"))
    m.room(5, 40, 15, 48, 0.5)
    m.site(6, 41, 14, 47, "B")
    boxes(m, (9, 44, "X"), (10, 44, "X"), (13, 42, "x"), (7, 47, "x"))
    # Пандус A.
    m.room(51, 26, 53, 52, 0.0)
    m.stairs(51, 22, 53, 25, "z-", 0.0, 1.0)
    m.room(51, 19, 53, 21, 1.0)
    m.box(52, 36, "x")
    # Дворец: крытый подъём на 2 и балкон над A.
    m.room(58, 40, 60, 58, 0.0)
    m.stairs(58, 32, 60, 39, "z-", 0.0, 2.0)
    m.room(58, 12, 60, 31, 2.0)
    m.roof_over(58, 14, 60, 39, 4.4)
    m.room(53, 12, 57, 14, 2.0)
    # CT → A (касса под крышей).
    m.room(16, 5, 41, 7, 1.0)
    m.roof_over(26, 5, 33, 7, 3.6)
    m.room(38, 8, 41, 12, 1.0)
    # Мид: от базы T на запад, верх мида, излом, мид, выход к CT.
    m.room(30, 53, 45, 55, 0.0)
    m.room(30, 40, 32, 52, 0.0)
    m.room(21, 37, 32, 39, 0.0)
    m.room(21, 24, 23, 36, 0.0)
    m.box(22, 30, "x")
    m.room(21, 16, 23, 23, 0.0)
    m.stairs(21, 12, 23, 15, "z-", 0.0, 1.0)
    m.room(21, 8, 23, 11, 1.0)
    # Окно CT над мидом.
    m.room(15, 9, 17, 13, 1.0)
    m.stairs(15, 14, 17, 21, "z+", 1.0, 2.5)
    m.room(15, 22, 20, 27, 2.5)
    m.roof_over(15, 22, 20, 27, 4.6)
    m.wall(20, 22, 20, 27)
    m.window(20, 24)
    m.window(20, 25)
    # Коннектор (тёмный) и «джунгли» к A.
    m.room(24, 29, 36, 31, 0.0)
    m.roof_over(24, 29, 36, 31, 2.4)
    m.stairs(37, 27, 39, 31, "z-", 0.0, 1.0)
    m.room(37, 19, 41, 26, 1.0)
    m.room(40, 17, 41, 18, 1.0)
    m.box(38, 22, "x")
    # B: катакомбы из мида (крытые), квартиры от T (второй этаж), рынок от CT.
    m.room(16, 32, 20, 34, 0.25)
    m.roof_over(16, 32, 20, 34, 2.4)
    m.room(16, 35, 18, 41, 0.5)
    m.room(34, 57, 45, 59, 0.0)
    m.stairs(26, 57, 33, 59, "x-", 0.0, 2.0)
    m.room(16, 55, 25, 59, 2.0)
    m.roof_over(16, 55, 25, 59, 4.2)
    m.wall(20, 56, 21, 57, "%")
    m.stairs(16, 49, 18, 54, "z+", 0.5, 2.0)
    m.roof_over(16, 50, 18, 54, 4.2)
    m.room(5, 9, 7, 35, 1.0)
    m.roof_over(5, 14, 7, 30, 3.4)
    m.stairs(5, 36, 7, 39, "z+", 1.0, 0.5)
    m.lanes = {"A": [(52, 40), (30, 30)], "B": [(17, 52), (18, 36)]}
    return m


# --- Карта 3: «Вилла» ---
# T внизу слева (низко), CT вверху справа (высоко). B — на холме: «банан» (длинный подъём с изломом), арка к CT.
# A — терраса справа: второй мид с изломом и коротким подъёмом, квартиры (тёмные, второй этаж) с балконом и «бойлерной»
# над A, яма под A, библиотека от CT.

def villa():
    m = Map("villa", "Вилла", 64, 64, 6.0)
    m.props = {"sky": "#FF8FB8E0", "fog": "#FFC8D4DC", "fogNear": 22, "fogFar": 70,
               "steps": {"floor": "stone", "floor2": "gravel", "box": "wood"},
               "tex": {"wall": "temple_stone", "wall2": "plaster", "floor": "cobble", "floor2": "path", "site": "site_temple",
                       "crate": "crate", "box": "box", "ledge": "temple_stone", "roof": "planks", "roofTop": "brick"}}
    m.room(4, 53, 14, 59, 0.0)
    m.spawn(5, 54, 13, 58, "T")
    m.room(47, 3, 58, 8, 2.5)
    m.spawn(48, 4, 57, 7, "C")
    # Точки.
    m.room(5, 6, 15, 15, 2.0)
    m.site(6, 7, 14, 14, "B")
    m.wall(9, 10, 10, 11, "%")  # фонтан
    boxes(m, (13, 8, "X"), (13, 9, "X"), (7, 13, "x"), (14, 14, "x"))
    m.room(44, 22, 53, 30, 1.5)
    m.site(45, 23, 52, 29, "A")
    boxes(m, (48, 25, "X"), (49, 25, "X"), (46, 28, "x"), (51, 23, "x"))
    # Банан: от T на север, подъём на холм, излом, вход на B снизу.
    m.room(7, 44, 9, 52, 0.0)
    m.stairs(7, 36, 9, 43, "z-", 0.0, 2.0)
    m.room(7, 27, 9, 35, 2.0)
    m.room(7, 24, 12, 26, 2.0)
    m.room(10, 16, 12, 23, 2.0)
    m.box(8, 31, "x")
    m.box(11, 20, "X")
    # B → CT: арка и подъём.
    m.room(16, 9, 35, 11, 2.0)
    m.roof_over(20, 9, 27, 11, 4.5)
    m.stairs(36, 9, 37, 11, "x+", 2.0, 2.5)
    m.room(38, 9, 48, 11, 2.5)
    # Второй мид: от T на восток, на север, излом, подъём к A (короткий).
    m.room(15, 55, 29, 57, 0.0)
    m.room(27, 40, 29, 54, 0.0)
    m.room(27, 37, 38, 39, 0.0)
    m.box(28, 46, "x")
    m.stairs(36, 31, 38, 36, "z-", 0.0, 1.5)
    m.room(36, 29, 43, 30, 1.5)
    # Квартиры (тёмные): от T на второй этаж, коридоры, балкон и бойлерная над A.
    m.room(15, 53, 17, 54, 0.0)
    m.stairs(15, 43, 17, 52, "z-", 0.0, 2.5)
    m.room(15, 40, 25, 42, 2.5)
    m.window(26, 41)
    m.room(23, 27, 25, 39, 2.5)
    m.roof_over(15, 30, 25, 52, 4.8)
    m.room(26, 27, 33, 29, 2.5)
    m.room(34, 25, 43, 27, 2.5)
    m.roof_over(34, 25, 43, 27, 4.8)
    # Яма под A и библиотека от CT.
    m.room(48, 35, 53, 40, 0.5)
    m.stairs(48, 31, 53, 34, "z-", 0.5, 1.5)
    m.box(50, 38, "X")
    m.room(49, 9, 53, 17, 2.5)
    m.roof_over(49, 9, 53, 17, 4.6)
    m.stairs(49, 18, 53, 21, "z+", 2.5, 1.5)
    m.lanes = {"A": [(28, 47), (24, 35)], "B": [(8, 47), (11, 20)]}
    return m


# --- Карта «Напарников»: «Рынок» — компактный городок: узкие улочки, крытый рынок с прилавками, балкон над точкой ---

def market():
    m = Map("market", "Рынок", 44, 44, 4.5)
    m.props = {"wingman": True, "sky": "#FF9CCBEE", "fog": "#FFE6DCC4", "fogNear": 24, "fogFar": 60,
               "steps": {"floor": "stone", "floor2": "sand", "box": "wood"},
               "tex": {"wall": "d2_wall", "wall2": "d2_block", "floor": "d2_pave", "floor2": "d2_dirt", "site": "d2_site",
                       "crate": "d2_crate", "box": "d2_crate", "ledge": "d2_block", "roof": "wood_panel", "roofTop": "d2_block"},
               "light": [-0.5, -1, -0.3, 0.6]}
    # База T (юго-запад) и база CT (северо-восток, выше).
    m.room(3, 36, 14, 41, 0.5, ",")
    m.spawn(4, 37, 13, 40, "T")
    m.room(30, 2, 40, 6, 1.0)
    m.spawn(31, 3, 39, 5, "C")
    # Западная улочка: на север, лестница наверх, крытый дом и балкон над точкой, спуск на точку.
    m.room(5, 14, 8, 35, 0.5, ",")
    m.box(6, 22, "x")
    m.box(7, 29, "X")
    m.stairs(5, 10, 8, 13, "z-", 0.5, 1.5)
    m.room(5, 5, 16, 9, 1.5)
    m.roof_over(5, 5, 12, 9, 3.8)
    m.wall(9, 7, 10, 7, "%")
    m.room(17, 5, 20, 9, 1.5)
    m.stairs(21, 5, 24, 9, "x+", 1.5, 0.5)
    # Крытый рынок: из базы T на восток и на север, ряды прилавков.
    m.room(15, 36, 28, 40, 0.5, ",")
    m.room(20, 20, 28, 35, 0.5)
    m.roof_over(20, 21, 28, 35, 3.2)
    for x, z0, z1 in ((22, 23, 25), (26, 27, 29), (22, 31, 33), (26, 22, 23)):
        for z in range(z0, z1 + 1):
            m.box(x, z, "x")
    # Точка A — дворик с двумя проходами и выходом к CT.
    m.room(25, 8, 36, 19, 0.5)
    m.site(26, 9, 35, 18, "A")
    m.wall(30, 13, 31, 14, "%")  # колодец посреди двора
    m.stairs(30, 7, 35, 8, "z+", 1.0, 0.5)
    # Восточная улочка: от базы T к CT, дверь во двор.
    m.room(29, 36, 40, 40, 0.5, ",")
    m.room(37, 20, 40, 35, 0.25, ",")
    m.box(38, 27, "X")
    m.box(39, 31, "x")
    m.room(37, 7, 40, 16, 1.0)
    m.stairs(37, 17, 40, 19, "z+", 1.0, 0.25)
    m.stairs(36, 12, 37, 14, "x-", 1.0, 0.5)
    m.lanes = {"A": [(6, 25), (24, 28), (38, 28)]}
    return m


def main():
    maps = [canyon(), oasis(), villa(), market()]
    for m in maps:
        m.check()
        m.radar()
    with open(os.path.join(APP, "mapdata.lua"), "w", encoding="utf-8") as f:
        f.write("-- Сгенерировано tools/gen_strike_maps.py — не править руками.\n")
        f.write("-- rows — клетки (как в maps.lua), levels — высота пола (0–9, a–z: шаг 0,25), roofs — потолок («.» — нет).\n")
        f.write("return {\n")
        for m in maps:
            f.write(m.lua() + "\n")
        f.write("}\n")
    print("ok:", ", ".join(m.key for m in maps))


if __name__ == "__main__":
    main()
