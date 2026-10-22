-- Оружие от первого лица в 3D: модели из деталей-параллелепипедов (ствол, ствольная коробка, магазин, приклад…)
-- и руки в перчатках с рукавами цвета команды. Цвета — клетки палитры textures/vm_palette.png (gen_strike.py).
-- Модель рисуется в том же 3D-кадре, что и мир, но очень близко к глазу и уменьшенной (масштаб S): так она
-- не уходит в стены (игрок не подходит к стене ближе 0,25), а на экране выглядит обычного размера.
-- Единицы модели — метры: +Z вперёд (дуло), +Y вверх, +X вправо; начало координат — у спускового крючка.
local D = require("data")
local Sim = require("sim")

local VM = {}
local sin, cos, rad, sqrt, min, max, abs = math.sin, math.cos, math.rad, math.sqrt, math.min, math.max, math.abs

local S = 0.25 -- во сколько раз модель меньше «настоящей»

-- Палитра: номер клетки 8×8 на текстуре 64×64 (порядок — как в gen_strike.py PALETTE).
local C = {black = 0, dark = 1, gray = 2, steel = 3, wood = 4, wood2 = 5, green = 6, olive = 7, tan = 8, p90 = 9,
  glove = 10, sleeve_t = 11, sleeve_ct = 12, skin = 13, he = 14, flash = 15, smoke = 16, c4 = 17, screen = 18, red = 19,
  mag = 20, glass = 21, blade = 22, white = 23, leather = 24, silver = 25, famas = 26, yellow = 27}

local function uv(col)
  local cx, cy = col % 8, math.floor(col / 8)
  return (cx * 8 + 2) / 64, (cy * 8 + 2) / 64, (cx * 8 + 6) / 64, (cy * 8 + 6) / 64
end

local function quad(m, a, b, c, d, col)
  local u1, v1, u2, v2 = uv(col)
  local n = #m.v / 5
  for _, p in ipairs({{a, u1, v1}, {b, u2, v1}, {c, u2, v2}, {d, u1, v2}}) do
    local q = p[1]
    local k = #m.v
    m.v[k + 1], m.v[k + 2], m.v[k + 3], m.v[k + 4], m.v[k + 5] = q[1], q[2], q[3], p[2], p[3]
  end
  for _, k in ipairs({1, 2, 3, 1, 3, 4}) do m.i[#m.i + 1] = n + k end
end

-- 8 углов → 6 граней.
local function hexa(m, p, col)
  -- p[1..4] — передняя грань (z+), p[5..8] — задняя; порядок: лв, пв, пн, лн
  quad(m, p[1], p[2], p[3], p[4], col)
  quad(m, p[6], p[5], p[8], p[7], col)
  quad(m, p[5], p[1], p[4], p[8], col)
  quad(m, p[2], p[6], p[7], p[3], col)
  quad(m, p[5], p[6], p[2], p[1], col)
  quad(m, p[4], p[3], p[7], p[8], col)
end

-- Деталь: {x1, y1, z1, x2, y2, z2, цвет, наклон}. Наклон (градусы) — поворот вокруг оси X через центр детали:
-- положительный уводит низ детали вперёд (магазин АК, рукоять пистолета).
local function box(m, b)
  local x1, y1, z1, x2, y2, z2, col, tilt = b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8] or 0
  local cy, cz = (y1 + y2) / 2, (z1 + z2) / 2
  local c, s = cos(rad(tilt)), sin(rad(tilt))
  local function P(x, y, z)
    local dy, dz = y - cy, z - cz
    return {x, cy + dy * c + dz * s, cz + dz * c - dy * s}
  end
  hexa(m, {P(x1, y2, z2), P(x2, y2, z2), P(x2, y1, z2), P(x1, y1, z2), P(x1, y2, z1), P(x2, y2, z1), P(x2, y1, z1), P(x1, y1, z1)}, col)
end

-- Брусок от точки a до точки b сечением w×h (рука, ремень).
local function beam(m, a, b, w, h, col)
  local dx, dy, dz = b[1] - a[1], b[2] - a[2], b[3] - a[3]
  local l = sqrt(dx * dx + dy * dy + dz * dz)
  dx, dy, dz = dx / l, dy / l, dz / l
  -- «вправо» = ось × вверх, «вверх» = вправо × ось
  local rx, ry, rz = dz, 0, -dx
  local rl = sqrt(rx * rx + rz * rz)
  if rl < 0.01 then rx, ry, rz, rl = 1, 0, 0, 1 end
  rx, rz = rx / rl, rz / rl
  local ux, uy, uz = ry * dz - rz * dy, rz * dx - rx * dz, rx * dy - ry * dx
  local function P(base, sx, sy)
    return {base[1] + rx * sx * w + ux * sy * h, base[2] + ry * sx * w + uy * sy * h, base[3] + rz * sx * w + uz * sy * h}
  end
  hexa(m, {P(b, -0.5, 0.5), P(b, 0.5, 0.5), P(b, 0.5, -0.5), P(b, -0.5, -0.5),
    P(a, -0.5, 0.5), P(a, 0.5, 0.5), P(a, 0.5, -0.5), P(a, -0.5, -0.5)}, col)
end

-- Ствол «восьмигранником» (круглее, чем брусок): две детали в список parts; r — радиус, от z1 до z2 на высоте y.
local function tube(parts, x, y, z1, z2, r, col)
  local k = r * 0.42
  parts[#parts + 1] = {x - r, y - k, z1, x + r, y + k, z2, col}
  parts[#parts + 1] = {x - k, y - r, z1, x + k, y + r, z2, col}
end

-- --- Модели: детали, хват правой руки (grip) и левой (fore), смещение модели на экране (pos) ---

local B = C.black
local MODELS = {}

local function pistol(o)
  local parts = {}
  local sw, sh, sl, sz = o.w or 0.014, o.h or 0.032, o.len or 0.19, o.back or -0.06
  local slide = o.slide or C.dark
  parts[#parts + 1] = {-sw, 0, sz, sw, sh, sz + sl, slide}                         -- затвор
  parts[#parts + 1] = {-sw * 0.85, -0.014, sz + 0.01, sw * 0.85, 0, sz + sl - 0.01, o.frame or B} -- рамка
  parts[#parts + 1] = {-sw * 0.95, -0.12, -0.075, sw * 0.95, -0.012, -0.03, o.grip or B, 14}     -- рукоять
  parts[#parts + 1] = {-0.004, -0.04, -0.03, 0.004, -0.034, 0.03, B}             -- спусковая скоба
  parts[#parts + 1] = {-0.004, -0.04, 0.025, 0.004, -0.014, 0.031, B}
  parts[#parts + 1] = {-0.003, sh, sz + sl - 0.02, 0.003, sh + 0.008, sz + sl - 0.01, B} -- мушка
  parts[#parts + 1] = {-0.009, sh, sz + 0.005, 0.009, sh + 0.008, sz + 0.016, B}       -- целик
  parts[#parts + 1] = {-sw - 0.001, sh * 0.35, sz + 0.02, sw + 0.001, sh * 0.75, sz + 0.07, o.serr or C.black} -- насечка
  tube(parts, 0, sh * 0.55, sz + sl, sz + sl + 0.006, 0.006, B)                   -- срез ствола
  if o.suppressor then tube(parts, 0, sh * 0.5, sz + sl, sz + sl + 0.15, 0.017, B) end
  return {parts = parts, grip = {0, -0.075, -0.06}, fore = {-0.03, -0.085, -0.035}, pos = {0.085, -0.09, 0.37},
    muzzle = {0, sh * 0.55, sz + sl + (o.suppressor and 0.15 or 0.006)}, pistol = true}
end

MODELS.glock = pistol({})
MODELS.usp = pistol({len = 0.2, suppressor = true, slide = C.dark})
MODELS.p250 = pistol({len = 0.18, slide = C.gray})
MODELS.deagle = pistol({w = 0.018, h = 0.042, len = 0.25, back = -0.08, slide = C.silver, frame = C.steel, grip = C.black})

-- Автомат АК: деревянное цевьё и приклад, изогнутый магазин.
MODELS.ak47 = {parts = {
  {-0.026, -0.035, -0.17, 0.026, 0.035, 0.08, C.dark},          -- ствольная коробка
  {-0.022, 0.035, -0.16, 0.022, 0.045, 0.06, C.gray},           -- крышка
  {-0.024, -0.03, 0.08, 0.024, 0.014, 0.31, C.wood},            -- цевьё
  {-0.013, 0.014, 0.08, 0.013, 0.034, 0.3, C.wood2},            -- газовая трубка
  {-0.01, 0.035, 0.06, 0.01, 0.05, 0.09, B},                    -- целик
  {-0.005, 0.0, 0.48, 0.005, 0.04, 0.495, B},                   -- мушка
  {-0.018, -0.13, -0.005, 0.018, -0.035, 0.06, C.mag, 12},      -- магазин
  {-0.018, -0.23, 0.035, 0.018, -0.125, 0.1, C.mag, 30},
  {-0.016, -0.13, -0.15, 0.016, -0.035, -0.105, C.wood2, -18},  -- рукоять
  {-0.004, -0.056, -0.1, 0.004, -0.05, -0.02, B},               -- скоба
  {-0.021, -0.09, -0.52, 0.021, 0.025, -0.17, C.wood, -6},      -- приклад
  {-0.022, -0.1, -0.53, 0.022, 0.03, -0.5, C.wood2, -6},
}, grip = {0, -0.085, -0.13}, fore = {0, -0.028, 0.19}, pos = {0.12, -0.115, 0.24}}
tube(MODELS.ak47.parts, 0, -0.008, 0.31, 0.56, 0.009, B)
tube(MODELS.ak47.parts, 0, -0.008, 0.53, 0.58, 0.013, B)        -- дульный тормоз

-- M4A4: чёрная, планка сверху, прямой магазин, приклад на трубе.
MODELS.m4a4 = {parts = {
  {-0.025, -0.035, -0.16, 0.025, 0.033, 0.07, B},
  {-0.012, 0.033, -0.15, 0.012, 0.05, 0.07, C.dark},            -- планка
  {-0.026, -0.032, 0.07, 0.026, 0.03, 0.3, C.dark},             -- цевьё
  {-0.027, -0.02, 0.09, 0.027, 0.02, 0.28, B},                  -- рёбра цевья
  {-0.004, 0.0, 0.42, 0.004, 0.065, 0.435, B},                  -- мушка
  {-0.012, 0.03, 0.41, 0.012, 0.04, 0.445, B},
  {-0.017, -0.16, -0.02, 0.017, -0.035, 0.045, C.dark, 6},      -- магазин
  {-0.015, -0.13, -0.15, 0.015, -0.035, -0.105, B, -18},        -- рукоять
  {-0.004, -0.056, -0.1, 0.004, -0.05, -0.02, B},
  {-0.012, -0.02, -0.4, 0.012, 0.01, -0.16, C.dark},            -- труба приклада
  {-0.02, -0.08, -0.48, 0.02, 0.025, -0.33, B, -4},             -- приклад
}, grip = {0, -0.085, -0.13}, fore = {0, -0.03, 0.2}, pos = {0.12, -0.115, 0.24}}
tube(MODELS.m4a4.parts, 0, 0, 0.3, 0.55, 0.009, B)
tube(MODELS.m4a4.parts, 0, 0, 0.52, 0.58, 0.012, C.dark)

-- Galil: оливковый, деревянное цевьё, прямой магазин.
MODELS.galil = {parts = {
  {-0.026, -0.036, -0.16, 0.026, 0.034, 0.08, C.olive},
  {-0.022, 0.034, -0.15, 0.022, 0.044, 0.06, C.dark},
  {-0.024, -0.03, 0.08, 0.024, 0.014, 0.29, C.wood},
  {-0.005, 0.0, 0.46, 0.005, 0.045, 0.475, B},
  {-0.017, -0.17, -0.01, 0.017, -0.036, 0.05, B, 8},
  {-0.015, -0.13, -0.15, 0.015, -0.036, -0.105, B, -18},
  {-0.004, -0.056, -0.1, 0.004, -0.05, -0.02, B},
  {-0.008, -0.07, -0.46, 0.008, -0.05, -0.16, C.dark},          -- рамка складного приклада
  {-0.008, 0.0, -0.46, 0.008, 0.02, -0.16, C.dark},
  {-0.02, -0.08, -0.48, 0.02, 0.025, -0.45, C.dark},
}, grip = {0, -0.085, -0.13}, fore = {0, -0.028, 0.19}, pos = {0.12, -0.115, 0.24}}
tube(MODELS.galil.parts, 0, -0.008, 0.29, 0.54, 0.009, B)

-- FAMAS: булл-пап с ручкой для переноски.
MODELS.famas = {parts = {
  {-0.028, -0.055, -0.4, 0.028, 0.03, 0.2, C.famas},            -- корпус
  {-0.026, -0.06, 0.05, 0.026, -0.04, 0.25, C.famas},
  {-0.006, 0.03, 0.12, 0.006, 0.075, 0.15, B},                  -- стойки ручки
  {-0.006, 0.03, -0.18, 0.006, 0.075, -0.15, B},
  {-0.01, 0.07, -0.2, 0.01, 0.085, 0.17, B},                    -- ручка
  {-0.016, -0.14, 0.03, 0.016, -0.055, 0.07, B, -15},           -- рукоять
  {-0.004, -0.075, 0.07, 0.004, -0.07, 0.14, B},
  {-0.017, -0.17, -0.18, 0.017, -0.055, -0.12, C.dark, 4},      -- магазин за рукоятью
  {-0.03, -0.07, -0.42, 0.03, 0.035, -0.39, B},                 -- затыльник
}, grip = {0, -0.1, 0.05}, fore = {0, -0.06, 0.2}, pos = {0.12, -0.1, 0.16}}
tube(MODELS.famas.parts, 0, -0.01, 0.2, 0.42, 0.009, B)

-- P90: «гладкий» корпус, магазин сверху.
MODELS.p90 = {parts = {
  {-0.032, -0.06, -0.3, 0.032, 0.03, 0.18, C.p90},
  {-0.028, -0.09, -0.32, 0.028, -0.06, -0.12, C.p90},            -- приклад-низ
  {-0.024, 0.03, -0.16, 0.024, 0.055, 0.14, C.dark},             -- магазин
  {-0.02, 0.055, -0.04, 0.02, 0.075, 0.08, B},                  -- прицел
  {-0.016, -0.14, -0.02, 0.016, -0.06, 0.025, C.p90, -10},       -- рукоять
  {-0.03, -0.06, 0.1, 0.03, -0.02, 0.2, C.p90},
}, grip = {0, -0.1, 0.0}, fore = {-0.01, -0.05, 0.15}, pos = {0.11, -0.09, 0.21}}
tube(MODELS.p90.parts, 0, -0.01, 0.18, 0.26, 0.009, B)

-- MP9: компактный, складной приклад.
MODELS.mp9 = {parts = {
  {-0.022, -0.03, -0.1, 0.022, 0.03, 0.16, B},
  {-0.01, 0.03, -0.08, 0.01, 0.042, 0.14, C.dark},
  {-0.014, -0.2, -0.07, 0.014, -0.03, -0.03, C.dark, 4},         -- рукоять с магазином
  {-0.014, -0.11, 0.07, 0.014, -0.03, 0.1, B, 10},               -- передняя рукоятка
  {-0.004, -0.05, -0.03, 0.004, -0.044, 0.05, B},
  {-0.006, -0.02, -0.33, 0.006, 0.0, -0.1, C.dark},              -- приклад
  {-0.016, -0.06, -0.35, 0.016, 0.01, -0.32, B},
}, grip = {0, -0.09, -0.05}, fore = {0, -0.08, 0.085}, pos = {0.11, -0.1, 0.28}}
tube(MODELS.mp9.parts, 0, 0, 0.16, 0.24, 0.009, B)

-- Nova: помповое ружьё.
MODELS.nova = {parts = {
  {-0.025, -0.035, -0.12, 0.025, 0.035, 0.1, C.dark},
  {-0.026, -0.04, 0.17, 0.026, 0.0, 0.33, C.wood2},             -- цевьё (помпа)
  {-0.016, -0.13, -0.13, 0.016, -0.035, -0.09, B, -18},
  {-0.004, -0.056, -0.09, 0.004, -0.05, -0.01, B},
  {-0.022, -0.09, -0.5, 0.022, 0.025, -0.12, B, -7},             -- приклад
  {-0.004, 0.012, 0.62, 0.004, 0.03, 0.63, C.steel},             -- мушка
}, grip = {0, -0.085, -0.12}, fore = {0, -0.04, 0.25}, pos = {0.12, -0.115, 0.24}}
tube(MODELS.nova.parts, 0, 0.012, 0.1, 0.64, 0.012, C.dark)       -- ствол
tube(MODELS.nova.parts, 0, -0.02, 0.1, 0.55, 0.011, B)            -- трубчатый магазин

-- AWP: зелёная ложа, длинный ствол, оптика, рукоять затвора.
MODELS.awp = {parts = {
  {-0.03, -0.045, -0.18, 0.03, 0.03, 0.26, C.green},             -- ложа
  {-0.028, -0.06, -0.55, 0.028, 0.03, -0.18, C.green, -4},       -- приклад
  {-0.03, -0.11, -0.43, 0.03, -0.06, -0.3, C.green, -4},
  {-0.016, -0.12, -0.17, 0.016, -0.045, -0.12, C.green, -20},    -- рукоять
  {-0.004, -0.065, -0.12, 0.004, -0.06, -0.04, B},
  {-0.015, -0.11, -0.04, 0.015, -0.045, 0.02, B},               -- магазин
  {-0.015, 0.03, -0.12, 0.015, 0.06, -0.1, B},                  -- крепления прицела
  {-0.015, 0.03, 0.08, 0.015, 0.06, 0.1, B},
  {0.03, 0.01, -0.13, 0.065, 0.02, -0.12, C.steel},             -- ручка затвора
  {0.058, 0.0, -0.135, 0.075, 0.03, -0.115, B},
  {-0.016, 0.06, 0.16, 0.016, 0.104, 0.167, C.glass},          -- линза
}, grip = {0, -0.09, -0.15}, fore = {0, -0.05, 0.14}, pos = {0.12, -0.12, 0.22}}
tube(MODELS.awp.parts, 0, 0.082, -0.18, 0.165, 0.022, B)           -- прицел
tube(MODELS.awp.parts, 0, 0.0, 0.26, 0.72, 0.011, C.dark)          -- ствол
tube(MODELS.awp.parts, 0, 0.0, 0.69, 0.75, 0.015, B)

-- Нож: рукоять, гарда, клинок со скосом.
MODELS.knife = {parts = {
  {-0.013, -0.02, -0.11, 0.013, 0.018, 0.0, B},
  {-0.016, -0.026, 0.0, 0.016, 0.026, 0.012, C.dark},
  {-0.003, -0.016, 0.012, 0.003, 0.022, 0.13, C.blade},
  {-0.003, -0.006, 0.13, 0.003, 0.022, 0.17, C.blade},
  {-0.0035, 0.004, 0.17, 0.0035, 0.02, 0.19, C.steel},
}, grip = {0, -0.0, -0.06}, pos = {0.1, -0.1, 0.34}, knife = true}

local function grenade(col)
  return {parts = {
    {-0.03, -0.035, -0.03, 0.03, 0.035, 0.03, col},
    {-0.024, -0.042, -0.024, 0.024, 0.042, 0.024, col},
    {-0.012, 0.035, -0.012, 0.012, 0.055, 0.012, C.gray},       -- запал
    {0.01, 0.0, -0.006, 0.016, 0.05, 0.006, C.steel},          -- рычаг
    {0.006, 0.05, -0.004, 0.022, 0.06, 0.004, C.yellow},       -- кольцо
  }, grip = {0.01, -0.03, -0.02}, pos = {0.1, -0.09, 0.34}, held = true}
end
MODELS.he = grenade(C.he)
MODELS.flash = grenade(C.flash)
MODELS.smoke = grenade(C.smoke)

MODELS.c4 = {parts = {
  {-0.07, -0.03, -0.05, 0.07, 0.02, 0.05, C.c4},
  {-0.068, -0.025, -0.045, 0.068, 0.015, 0.045, C.tan},
  {-0.03, 0.02, -0.02, 0.03, 0.026, 0.02, C.black},
  {-0.025, 0.024, -0.015, 0.025, 0.028, 0.008, C.screen},
  {-0.072, -0.005, -0.05, 0.072, 0.003, 0.05, C.red},
}, grip = {0.04, -0.04, -0.03}, fore = {-0.05, -0.04, -0.02}, pos = {0.03, -0.12, 0.33}, held = true}

-- --- Сборка: модель + руки ---

local cache = {}

local function addArms(parts, mdl, team)
  local sleeve = team == D.T and C.sleeve_t or C.sleeve_ct
  local g = mdl.grip
  -- правая рука: из-за правого нижнего края к рукояти
  local elbowR = {g[1] + 0.2, g[2] - 0.32, g[3] - 0.32}
  local wristR = {g[1] + 0.03, g[2] - 0.05, g[3] - 0.04}
  beam(parts, elbowR, wristR, 0.06, 0.055, sleeve)
  beam(parts, {wristR[1] + 0.005, wristR[2] + 0.01, wristR[3] + 0.0}, {g[1] + 0.008, g[2] + 0.01, g[3] + 0.02}, 0.045, 0.06, C.glove)
  box(parts, {g[1] - 0.022, g[2] - 0.03, g[3] - 0.02, g[1] + 0.022, g[2] + 0.025, g[3] + 0.03, C.glove})
  local f = mdl.fore
  if f then
    local elbowL = {f[1] - 0.24, f[2] - 0.3, f[3] - 0.3}
    if mdl.pistol then elbowL = {f[1] - 0.12, f[2] - 0.3, f[3] - 0.35} end
    local wristL = {f[1] - 0.02, f[2] - 0.05, f[3] - 0.04}
    beam(parts, elbowL, wristL, 0.06, 0.055, sleeve)
    box(parts, {f[1] - 0.025, f[2] - 0.03, f[3] - 0.03, f[1] + 0.02, f[2] + 0.02, f[3] + 0.03, C.glove})
  end
end

local function build(key, team)
  local id = key .. team
  if cache[id] then return cache[id] end
  local mdl = MODELS[key] or MODELS.knife
  local m = {v = {}, i = {}}
  for _, b in ipairs(mdl.parts) do box(m, b) end
  addArms(m, mdl, team)
  cache[id] = {mesh = g3d.mesh({vertices = m.v, indices = m.i}), mdl = mdl}
  return cache[id]
end

function VM.init()
  VM.tex = g3d.texture("textures/vm_palette.png")
  VM.flashTex = g3d.texture("textures/muzzle.png")
end

-- Точка модели (в метрах, относительно экрана) → мир.
local function toWorld(cam, yaw, pitch, roll, ox, oy, oz)
  -- поворот камеры: Ry(yaw) · Rx(pitch) · Rz(roll), как в g3d
  local cr, sr = cos(rad(roll)), sin(rad(roll))
  local x1, y1 = ox * cr - oy * sr, ox * sr + oy * cr
  local z1 = oz
  local cp, sp = cos(rad(pitch)), sin(rad(pitch))
  local y2, z2 = y1 * cp + z1 * sp, -y1 * sp + z1 * cp
  local cy, sy = cos(rad(yaw)), sin(rad(yaw))
  local x3, z3 = x1 * cy + z2 * sy, -x1 * sy + z2 * cy
  return cam.x + x3 * S, cam.y + y2 * S, cam.z + z3 * S
end

-- Нарисовать оружие игрока me (в 3D-кадре, после мира и после выстрелов-pick). st — состояние анимации.
function VM.draw(cam, me, team, now, st)
  local w = D.WEAPONS[me.wpn]
  local key = w.key
  local entry = build(key, team)
  local mdl = entry.mdl
  local sp = sqrt(me.vx * me.vx + me.vz * me.vz)
  local walk = min(1, sp / 3)
  -- смещения: покачивание при ходьбе, отдача, смена оружия, перезарядка, бросок гранаты, удар ножом
  local bx = sin(me.walkT * 3.2) * 0.012 * walk
  local by = -abs(cos(me.walkT * 3.2)) * 0.01 * walk
  local px, py, pz = mdl.pos[1] + bx, mdl.pos[2] + by, mdl.pos[3]
  local pitch, yaw, roll = 0, -2, 0
  if w.clip and me.cool > 0 then
    local k = me.cool / w.cool
    k = k * k
    pz = pz - 0.04 * k * min(2, 0.6 + w.recoil * 0.3)
    pitch = pitch + 4 * k * min(2, 0.5 + w.recoil * 0.25)
  end
  if me.switchT > 0 then
    local k = me.switchT / 0.35
    py = py - 0.25 * k
    pitch = pitch - 30 * k
  end
  if me.reload > 0 then
    local wr = D.WEAPONS[me.reloadFor]
    local k = sin(math.pi * (1 - me.reload / (wr.reload or 1)))
    py = py - 0.07 * k
    pitch = pitch - 18 * k
    roll = roll + 25 * k
    px = px - 0.03 * k
  end
  if mdl.knife and me.cool > 0 then
    local k = sin(math.pi * (1 - me.cool / w.cool))
    px, pz = px - 0.08 * k, pz + 0.12 * k
    yaw, pitch = yaw + 35 * k, pitch - 15 * k
  end
  if mdl.held and me.nadeCool > 0 then
    local k = sin(math.pi * (1 - me.nadeCool / 0.8))
    py, pz = py + 0.08 * k, pz + 0.1 * k
    pitch = pitch + 30 * k
  end
  if me.crouch then py = py + 0.01 end
  local gyaw, gpitch = cam.yaw + yaw, max(-89, min(89, cam.pitch + pitch))
  local wx, wy, wz = toWorld(cam, cam.yaw, cam.pitch, 0, px, py, pz)
  g3d.draw(entry.mesh, wx, wy, wz, {yaw = gyaw, pitch = gpitch, roll = roll, scale = S, texture = VM.tex})
  -- вспышка у дула
  if w.clip and now - me.flashT < 0.05 then
    local mz = mdl.muzzle or {0, 0, 0}
    if not mdl.muzzle then
      -- дуло — самая дальняя деталь модели
      local far = 0
      for _, b in ipairs(mdl.parts) do far = max(far, b[6]) end
      mz = {0, 0, far + 0.03}
      mdl.muzzle = mz
    end
    local fx, fy, fz = toWorld({x = 0, y = 0, z = 0}, gyaw, gpitch, roll, mz[1], mz[2], mz[3])
    local size = (mdl.pistol and 0.09 or 0.13) * S * (0.8 + math.random() * 0.4)
    g3d.sprite(VM.flashTex, wx + fx, wy + fy, wz + fz, size, size)
  end
end

return VM
