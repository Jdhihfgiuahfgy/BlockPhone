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
        walk = lambda x, z: self.c[z][x] not in "#%xX"
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

    def reachable(self):
        """Клетки, куда можно дойти от базы T (вверх — не больше 0,3 за шаг, ящики и стены не проходятся)."""
        walk = lambda x, z: self.c[z][x] not in "#%xX"
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
        colors = {"#": (46, 50, 56), "%": (64, 70, 78), "X": (150, 120, 80), "x": (176, 150, 104), ".": (120, 124, 120),
                  ",": (104, 108, 106), "A": (178, 104, 92), "B": (178, 104, 92), "T": (170, 150, 96), "C": (96, 130, 170)}
        for z in range(self.h):
            for x in range(self.w):
                ch = self.c[z][x]
                c = colors.get(ch, (120, 124, 120))
                if ch not in "#%":
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


# --- Карта 1: «Каньон» — по мотивам Dust II ---

def canyon():
    m = Map("canyon", "Каньон", 64, 62, 5.0)
    m.props = {"sky": "#FF9CC7E8", "fog": "#FFDCD6C8", "fogNear": 26, "fogFar": 80,
               "steps": {"floor": "sand", "floor2": "stone", "box": "wood"},
               "tex": {"wall": "sandstone", "wall2": "sandbrick", "floor": "sand", "floor2": "tiles", "site": "site_sand",
                       "crate": "crate", "box": "box", "ledge": "sandbrick", "roof": "planks", "roofTop": "sandbrick"}}
    # База террористов (внизу) и спецназа (вверху, на возвышении).
    m.room(20, 50, 44, 58, 0.5)
    m.spawn(23, 52, 41, 57, "T")
    m.room(28, 2, 44, 10, 1.5)
    m.spawn(31, 3, 41, 8, "C")
    # Мид: длинный коридор от T к CT, «двери» в середине, ящик.
    m.room(30, 12, 34, 49, 0.5)
    m.wall(30, 26, 31, 27)  # двери мида — узкий проход
    m.wall(33, 26, 34, 27)
    m.box(33, 36, "X")
    m.box(31, 18, "x")
    m.stairs(30, 11, 34, 11, "z-", 0.5, 0.75)
    m.room(30, 9, 34, 10, 1.5)
    m.stairs(30, 10, 34, 13, "z-", 0.5, 1.5)
    # Длинный A: из базы T направо, вниз в «яму», вверх по пандусу к точке.
    m.room(45, 50, 58, 55, 0.5)
    m.room(54, 22, 59, 49, 0.0)
    m.room(54, 46, 59, 49, 0.25)
    m.stairs(54, 22, 59, 29, "z-", 0.0, 1.5)
    m.box(57, 40, "X")
    m.box(56, 33, "x")
    m.roof_over(45, 50, 52, 55, 2.6)  # ворота длинного — под крышей
    # Точка A: возвышение, «стена» с ящиками, выход к CT.
    m.room(44, 8, 59, 21, 1.5)
    m.site(46, 9, 58, 19, "A")
    m.box(50, 12, "X")
    m.box(51, 12, "X")
    m.box(53, 16, "x")
    m.box(47, 17, "x")
    m.room(42, 6, 44, 10, 1.5)
    # Короткий A (кэтвок): из мида по лестнице на высоту 2,5 и к точке сверху.
    m.room(35, 22, 39, 25, 0.5)
    m.stairs(35, 14, 39, 21, "z-", 0.5, 2.5)
    m.room(35, 12, 43, 13, 2.5)
    m.stairs(40, 14, 43, 17, "x+", 2.5, 1.5)
    m.room(40, 18, 43, 21, 1.5)
    m.box(38, 12, "x")
    # B: верхний тоннель от базы T, нижний тоннель к миду, точка B, двери к CT.
    m.room(6, 30, 11, 52, 0.5)
    m.room(12, 50, 19, 55, 0.5)
    m.roof_over(6, 30, 11, 45, 2.4)
    m.room(12, 36, 29, 39, 0.0)
    m.stairs(12, 36, 13, 39, "x-", 0.0, 0.5)
    m.stairs(28, 36, 29, 39, "x+", 0.0, 0.5)
    m.roof_over(12, 36, 29, 39, 2.0)
    m.room(4, 6, 19, 29, 1.0)
    m.stairs(6, 30, 11, 31, "z-", 0.5, 1.0)
    m.site(5, 7, 18, 22, "B")
    m.box(9, 12, "X")
    m.box(10, 12, "X")
    m.box(14, 18, "x")
    m.box(6, 20, "x")
    m.box(16, 9, "X")
    m.wall(12, 24, 13, 27, "%")  # «машина» у выхода из тоннеля
    # Окно над B (стена с уступом) и двери к CT.
    m.room(20, 4, 27, 8, 1.5)
    m.stairs(18, 4, 19, 8, "x-", 1.5, 1.0)
    m.room(16, 4, 17, 8, 1.0)
    m.room(26, 4, 29, 8, 1.5)
    m.roof_over(20, 4, 25, 8, 3.8)
    # Мид ↔ B: переход через «ящики» (xbox) к нижнему тоннелю.
    m.room(26, 40, 29, 44, 0.5)
    m.lanes = {"A": [(56, 36), (37, 18)], "B": [(9, 40), (20, 38)]}
    return m


# --- Карта 2: «Оазис» — по мотивам Mirage ---

def oasis():
    m = Map("oasis", "Оазис", 64, 64, 5.5)
    m.props = {"sky": "#FFA9C9E6", "fog": "#FFE2D9C6", "fogNear": 24, "fogFar": 80,
               "steps": {"floor": "stone", "floor2": "sand", "box": "wood"},
               "tex": {"wall": "plaster", "wall2": "sandbrick", "floor": "tiles", "floor2": "sand", "site": "site_sand",
                       "crate": "crate", "box": "box", "ledge": "sandstone", "roof": "wood_panel", "roofTop": "sandstone"}}
    # Базы: T — внизу справа, CT — вверху слева.
    m.room(36, 52, 58, 60, 0.0)
    m.spawn(40, 54, 56, 59, "T")
    m.room(8, 2, 26, 9, 1.0)
    m.spawn(10, 3, 24, 8, "C")
    # A: площадь справа вверху на высоте 1; пандус от T (A ramp).
    m.room(36, 10, 55, 25, 1.0)
    m.site(38, 12, 53, 23, "A")
    m.box(44, 16, "X")
    m.box(45, 16, "X")
    m.box(40, 20, "x")
    m.box(50, 13, "x")
    m.box(51, 13, "x")
    m.box(48, 22, "x")
    m.room(48, 30, 54, 51, 0.0)
    m.stairs(48, 26, 54, 29, "z-", 0.0, 1.0)
    m.box(50, 40, "x")
    m.box(53, 34, "X")
    # Дворец: из базы T по лестнице на 2, крытый коридор, балкон над A (спрыгнуть на точку).
    m.room(57, 46, 61, 51, 0.0)
    m.stairs(57, 38, 61, 45, "z-", 0.0, 2.0)
    m.room(57, 14, 61, 37, 2.0)
    m.roof_over(57, 22, 61, 45, 4.4)
    m.room(56, 14, 56, 18, 2.0)
    m.box(59, 30, "x")
    # CT → A: «касса» и проход вдоль верха.
    m.room(27, 3, 35, 9, 1.0)
    m.room(36, 3, 44, 9, 1.0)
    m.roof_over(27, 3, 33, 9, 3.6)
    # Коннектор (крытый) от A к миду и окно спецназа над мидом.
    m.room(28, 14, 35, 18, 1.0)
    m.roof_over(28, 14, 35, 18, 3.2)
    m.stairs(28, 19, 32, 22, "z+", 1.0, 0.0)
    m.stairs(19, 10, 24, 14, "z+", 1.0, 2.25)
    m.room(19, 15, 25, 27, 2.5)
    m.roof_over(19, 15, 25, 27, 4.6)
    m.box(20, 26, "x")
    # Мид: от верхнего мида (у базы T) до окна.
    m.room(26, 23, 33, 51, 0.0)
    m.room(26, 46, 35, 51, 0.0)
    m.room(34, 52, 35, 56, 0.0)
    m.box(29, 38, "X")
    m.box(31, 30, "x")
    m.box(27, 44, "x")
    # B «квартиры»: от базы T налево и вверх на 2, крытые комнаты, спуск на B.
    m.room(28, 56, 35, 60, 0.0)
    m.stairs(20, 56, 27, 60, "x-", 0.0, 2.0)
    m.room(9, 50, 19, 60, 2.0)
    m.roof_over(9, 50, 27, 60, 4.2)
    m.wall(14, 53, 15, 57, "%")
    m.room(9, 44, 13, 49, 2.0)
    m.roof_over(9, 44, 13, 49, 4.2)
    m.stairs(9, 38, 13, 43, "z+", 0.5, 2.0)
    # B: точка слева, «короткий» из мида, рынок (крытый) к CT.
    m.room(3, 22, 21, 37, 0.5)
    m.site(5, 24, 19, 35, "B")
    m.box(9, 28, "X")
    m.box(10, 28, "X")
    m.box(15, 32, "x")
    m.box(6, 25, "x")
    m.box(18, 26, "x")
    m.room(22, 30, 25, 35, 0.25)
    m.room(3, 10, 9, 21, 0.75)
    m.roof_over(3, 12, 9, 19, 3.4)
    m.stairs(3, 10, 9, 10, "z-", 0.75, 1.0)
    m.room(3, 3, 7, 9, 1.0)
    m.lanes = {"A": [(51, 38), (31, 33)], "B": [(11, 47), (24, 32)]}
    return m


# --- Карта 3: «Вилла» — по мотивам Inferno ---

def villa():
    m = Map("villa", "Вилла", 62, 64, 6.0)
    m.props = {"sky": "#FF8FB8E0", "fog": "#FFC8D4DC", "fogNear": 22, "fogFar": 74,
               "steps": {"floor": "stone", "floor2": "gravel", "box": "wood"},
               "tex": {"wall": "temple_stone", "wall2": "plaster", "floor": "cobble", "floor2": "path", "site": "site_temple",
                       "crate": "crate", "box": "box", "ledge": "temple_stone", "roof": "planks", "roofTop": "brick"}}
    # Базы: T — внизу слева (низко), CT — вверху справа (высоко).
    m.room(4, 52, 22, 61, 0.0)
    m.spawn(6, 54, 20, 60, "T")
    m.room(40, 2, 58, 9, 2.5)
    m.spawn(42, 3, 56, 8, "C")
    # «Банан»: длинный подъём слева к B.
    m.room(6, 42, 12, 51, 0.0)
    m.stairs(6, 34, 12, 41, "z-", 0.0, 2.0)
    m.room(6, 26, 12, 33, 2.0)
    m.box(8, 46, "x")
    m.box(11, 30, "X")
    # B: точка вверху слева (2), фонтан, выход к CT через арку и библиотеку.
    m.room(3, 6, 25, 25, 2.0)
    m.site(5, 8, 23, 23, "B")
    m.wall(13, 14, 15, 16, "%")
    m.box(7, 20, "X")
    m.box(8, 20, "X")
    m.box(20, 10, "x")
    m.box(22, 18, "x")
    m.room(26, 4, 39, 9, 2.0)
    m.stairs(36, 4, 39, 9, "x+", 2.0, 2.25)
    m.roof_over(28, 4, 35, 9, 4.5)
    m.room(26, 12, 33, 20, 2.0)
    m.roof_over(26, 12, 33, 20, 4.4)
    # Мид: от базы T вправо и вверх.
    m.room(23, 53, 34, 59, 0.0)
    m.room(23, 30, 28, 52, 0.0)
    m.room(23, 30, 31, 33, 0.0)
    m.box(25, 44, "X")
    m.box(27, 36, "x")
    # Короткий A: из верхнего мида по лестнице к A.
    m.stairs(32, 28, 37, 33, "x+", 0.0, 1.5)
    m.room(38, 26, 39, 33, 1.5)
    # Квартиры: от T вправо на второй этаж (2,5), крытые комнаты, балкон над A.
    m.room(35, 54, 41, 59, 0.0)
    m.stairs(42, 50, 47, 59, "z-", 0.0, 2.5)
    m.room(42, 40, 52, 49, 2.5)
    m.roof_over(42, 40, 52, 59, 4.8)
    m.wall(46, 43, 47, 46, "%")
    m.room(44, 34, 50, 39, 2.5)
    # A: терраса (1,5) справа, яма ниже (0,5), подъём к CT.
    m.room(38, 16, 58, 33, 1.5)
    m.site(40, 18, 56, 31, "A")
    m.box(46, 22, "X")
    m.box(47, 22, "X")
    m.box(42, 28, "x")
    m.box(54, 20, "x")
    m.box(52, 29, "x")
    m.room(53, 38, 59, 46, 0.5)
    m.stairs(53, 34, 59, 37, "z-", 0.5, 1.5)
    m.box(56, 44, "X")
    m.stairs(44, 10, 52, 15, "z+", 2.5, 1.5)
    m.room(53, 10, 58, 15, 2.5)
    m.roof_over(53, 10, 58, 15, 4.6)
    m.lanes = {"A": [(25, 40), (48, 44)], "B": [(9, 44), (9, 30)]}
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
    for k, m in enumerate(maps):
        n = m.furnish(100 + k)
        print("  %s: укрытий %d" % (m.key, n))
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
