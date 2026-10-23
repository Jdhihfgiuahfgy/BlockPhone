-- Карта как город (движок 3D 2.0): из клеток карты строятся дома, улицы и интерьеры.
-- Твёрдое в клетке — «столбики»: стена — до крыши дома; окно-проём (W) — подоконник и стена над проёмом; крытая клетка —
-- пол и масса дома над потолком; открытая — только пол. Видимые грани — то, что у клетки твёрже, чем у соседа.
-- Стены — дома: карта нарезана на участки, у каждого своя высота и фасад; на фасадах окна по этажам, двери, вывески,
-- навесы, балконы, кондиционеры, трубы, сверху карниз и крыша. У крытых проходов — интерьер (стены с плинтусом,
-- плитка или паркет, потолок). Улицы — дорога и тротуар у домов. За краем карты — дома-«горизонт».
-- Игровая логика (maps.lua) не меняется: всё это — только вид. Сборка идёт по частям (City.step), чтобы не превысить
-- лимит инструкций за один вызов.
local M = require("maps")

local City = {}
local floor, min, max, abs = math.floor, math.min, math.max, math.abs

City.SILL = 0.45 -- подоконник окна-проёма W (верх проёма — крыша клетки: щель ниже роста, не пролезть)
-- Масштаб: игрок ростом 1 блок (≈ 1,8 м) — двери, окна и этажи соразмерны ему.
local STOREY = 1.7

-- Темы: текстуры textures/city/*.png (tools/gen_strike_city.py).
local THEMES = {
  desert = {facades = {"des_plaster", "des_plaster2", "des_blocks", "des_adobe"}, trim = "des_trim",
    windows = {"des_window", "des_window2", "des_window3"}, doors = {"des_door", "des_door2"}, roof = "des_roof",
    road = "des_road", side = "des_paving", inWall = "des_inwall", inFloor = "des_infloor", ceiling = "des_ceiling",
    awning = "des_awning", far = "des_far", signs = {"sign2", "sign4"}},
  oasis = {facades = {"oas_blocks", "oas_plaster", "oas_bricks", "des_plaster"}, trim = "oas_trim",
    windows = {"oas_window", "oas_window2", "des_window2"}, doors = {"oas_door", "des_door"}, roof = "des_roof",
    road = "des_road", side = "oas_paving", inWall = "oas_inwall", inFloor = "oas_infloor", ceiling = "des_ceiling",
    awning = "oas_awning", far = "des_far", signs = {"sign1", "sign4"}},
  italian = {facades = {"ita_ochre", "ita_cream", "ita_terra", "ita_stone"}, trim = "ita_trim",
    windows = {"ita_window", "ita_window2"}, doors = {"ita_door", "vil_door"}, roof = "ita_roof", road = "ita_cobble",
    side = "ita_paving", inWall = "ita_inwall", inFloor = "ita_infloor", ceiling = "ita_ceiling", awning = "ita_awning",
    far = "ita_far", signs = {"sign3", "sign4"}},
  industrial = {facades = {"ind_brick", "ind_concrete", "ind_metal", "ind_brick"}, trim = "ind_trim",
    indoorWalls = {"ind_brick", "ind_concrete", "ind_metal"},
    windows = {"ind_window"}, doors = {"ind_door"}, shop = "ind_shutter", roof = "ind_roof", road = "ind_road",
    side = "ind_paving", inWall = "ind_inwall", inFloor = "ind_infloor", ceiling = "ind_roof", awning = nil,
    far = "ind_far", signs = {"sign1", "sign2"}},
  village = {facades = {"vil_wood", "vil_wood2", "vil_brick", "ita_cream"}, trim = "vil_trim", windows = {"vil_window"},
    doors = {"vil_door"}, roof = "vil_roof", road = nil, side = nil, inWall = "vil_inwall", inFloor = "vil_infloor",
    ceiling = "vil_ceiling", awning = "ita_awning", far = "ita_far", signs = {"sign3"}},
  alpine = {facades = {"alp_stone", "alp_wood", "alp_stone", "vil_wood2"}, trim = "vil_trim", windows = {"alp_window"},
    doors = {"alp_door"}, roof = "vil_roof", road = nil, side = "alp_paving", inWall = "alp_inwall",
    inFloor = "vil_infloor", ceiling = "vil_ceiling", awning = nil, far = "ind_far", signs = {"sign1"}},
  office = {facades = {"off_panel"}, indoorWalls = {"off_inwall", "off_panel", "off_inwall"}, trim = "ind_trim", windows = {"off_window"}, doors = {"off_door"}, roof = "ind_roof",
    inWall = "off_inwall", inFloor = "off_carpet", ceiling = "off_ceiling", far = "ind_far", signs = {"sign1"}},
}
local MAP_THEME = {canyon = "desert", market = "desert", dust = "desert", alley = "desert", oasis = "oasis",
  villa = "italian", temple = "italian", port = "industrial", factory = "industrial", village = "village", lake = "village",
  snow = "alpine", office = "office"}

-- Детерминированный «случайный» 0..1 по числам (одинаковый у всех игроков).
local SEED = 0
local function hash(a, b, c, d)
  local v = (a * 127 + b * 311 + (c or 0) * 53 + (d or 0) * 97 + SEED) * 2654435761 % 4294967296
  v = (v * 2246822519 + 3266489917) % 4294967296
  return v / 4294967296
end

-- Направления граней: 1 — z−, 2 — z+, 3 — x−, 4 — x+. Для каждой: сосед, наружная нормаль, «право» для зрителя
-- снаружи (u текстуры растёт слева направо), начало (t = 0) относительно угла клетки.
local DIRS = {
  {0, -1, nx = 0, nz = -1, ax = 1, az = 0, bx = 0, bz = 0},
  {0, 1, nx = 0, nz = 1, ax = -1, az = 0, bx = 1, bz = 1},
  {-1, 0, nx = -1, nz = 0, ax = 0, az = -1, bx = 0, bz = 1},
  {1, 0, nx = 1, nz = 0, ax = 0, az = 1, bx = 1, bz = 0},
}

-- --- Сетки ---

local function bucket(B, key)
  local m = B[key]
  if not m then
    m = {v = {}, i = {}, n = 0}
    B[key] = m
  end
  return m
end

-- Четырёхугольник: p1 — левый верх, p2 — правый верх, p3 — правый низ, p4 — левый низ.
local function quad(m, x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, u1, v1, u2, v2)
  local vs, n = m.v, m.n
  local k = #vs
  vs[k + 1], vs[k + 2], vs[k + 3], vs[k + 4], vs[k + 5] = x1, y1, z1, u1, v1
  vs[k + 6], vs[k + 7], vs[k + 8], vs[k + 9], vs[k + 10] = x2, y2, z2, u2, v1
  vs[k + 11], vs[k + 12], vs[k + 13], vs[k + 14], vs[k + 15] = x3, y3, z3, u2, v2
  vs[k + 16], vs[k + 17], vs[k + 18], vs[k + 19], vs[k + 20] = x4, y4, z4, u1, v2
  local is = m.i
  local j = #is
  is[j + 1], is[j + 2], is[j + 3], is[j + 4], is[j + 5], is[j + 6] = n + 1, n + 2, n + 3, n + 1, n + 3, n + 4
  m.n = n + 4
end

-- Вертикальный прямоугольник на грани клетки (cx, cz) в сторону d: вдоль грани от t0 до t1 (доли клетки, слева
-- направо для зрителя снаружи), по высоте y0..y1, отступ наружу off. u/v — как задано.
local function faceRect(m, cx, cz, d, t0, t1, y0, y1, off, u0, u1, v0, v1)
  local D = DIRS[d]
  local bx, bz = cx + D.bx + D.nx * off, cz + D.bz + D.nz * off
  local x0, z0 = bx + D.ax * t0, bz + D.az * t0
  local x1, z1 = bx + D.ax * t1, bz + D.az * t1
  quad(m, x0, y1, z0, x1, y1, z1, x1, y0, z1, x0, y0, z0, u0, v0, u1, v1)
end

-- Горизонтальный прямоугольник (x0..x1, z0..z1) на высоте y; текстура по миру с шагом rep метров.
local function flatRect(m, x0, z0, x1, z1, y, rep)
  local k = 1 / rep
  quad(m, x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1, x0 * k, z0 * k, x1 * k, z1 * k)
end

-- Коробка (балкон, кондиционер, навес-вывеска): центр, размеры, одна текстура на все грани.
local function boxMesh(m, x0, y0, z0, x1, y1, z1)
  quad(m, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1, 0, 0, 1, 1)
  quad(m, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0, 0, 0, 1, 1)
  quad(m, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0, 0, 0, 1, 1)
  quad(m, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1, 0, 0, 1, 1)
  quad(m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0, 0, 1, 1)
  quad(m, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, 0, 0, 1, 1)
end

-- --- Подготовка: виды клеток, высоты домов ---

local K_WALL, K_WINDOW, K_ROOFED, K_OPEN = 1, 2, 3, 4

local function prepare(map)
  local def = map.def
  local W, H = map.W, map.H
  SEED = (map.index or 1) * 7919
  local themeName = def.theme or MAP_THEME[def.key] or "desert"
  local th = THEMES[themeName] or THEMES.desert
  local indoor = def.ceiling ~= nil
  local wallH = map.wallH or M.WALL_H
  -- Участки: разрезы по x и z через 4–8 клеток.
  local function cuts(n, salt)
    local list, x = {}, 0
    local k = 0
    while x < n do
      list[#list + 1] = x
      k = k + 1
      x = x + 4 + floor(hash(k, salt, 3) * 5)
    end
    return list
  end
  local cx, cz = cuts(W, 11), cuts(H, 23)
  local lotX, lotZ = {}, {}
  for i = 1, #cx do for x = cx[i], (cx[i + 1] or W) - 1 do lotX[x] = i end end
  for i = 1, #cz do for z = cz[i], (cz[i + 1] or H) - 1 do lotZ[z] = i end end
  local HEIGHTS = {-1, 0, 0, 1, 2, 3, 4.5}
  local c, base, roof = map.c, map.base, map.roof
  local kind, top, low, upB, upT, lot = {}, {}, {}, {}, {}, {}
  for z = 0, H - 1 do
    for x = 0, W - 1 do
      local i = z * W + x + 1
      local ch = c[i]
      local k
      if ch == "#" or ch == "%" then k = K_WALL
      elseif ch == "W" then k = K_WINDOW
      elseif roof[i] then k = K_ROOFED
      else k = K_OPEN end
      kind[i] = k
      local lx, lz = lotX[x], lotZ[z]
      lot[i] = lx * 1000 + lz
      if k ~= K_OPEN then
        local h = wallH + HEIGHTS[1 + floor(hash(lx, lz, 5) * #HEIGHTS)]
        if x == 0 or z == 0 or x == W - 1 or z == H - 1 then h = h + 3 end
        -- выше самого высокого пола рядом (с крыши не заглянуть за стену) и выше потолка
        local around = 0
        for dz = -1, 1 do
          for dx = -1, 1 do
            local nx, nz = x + dx, z + dz
            if nx >= 0 and nz >= 0 and nx < W and nz < H then
              local j = nz * W + nx + 1
              if c[j] ~= "#" and c[j] ~= "%" then around = max(around, base[j]) end
            end
          end
        end
        h = max(h, around + 2.2, 2.5)
        if roof[i] then h = max(h, roof[i] + 0.5) end
        if indoor then h = wallH end
        top[i] = floor(h * 2 + 0.5) / 2
      end
      if k == K_WALL then
        low[i] = top[i]
      elseif k == K_WINDOW then
        low[i] = base[i] + City.SILL
        upB[i], upT[i] = roof[i], top[i]
      elseif k == K_ROOFED then
        low[i] = base[i]
        upB[i], upT[i] = roof[i], indoor and roof[i] + 0.01 or max(top[i], roof[i] + 0.35)
      else
        low[i] = base[i]
      end
    end
  end
  return {map = map, th = th, theme = themeName, indoor = indoor, kind = kind, top = top, low = low, upB = upB,
    upT = upT, lot = lot, B = {}, D = {}, faces = {}, row = 0, done = false}
end

-- Текстура фасада участка.
local function facadeOf(S, i)
  local th, map = S.th, S.map
  local tx = map.def.tex
  if map.c[i] == "%" and not S.indoor then return tx.wall2 end
  if S.indoor and th.indoorWalls then
    local list = th.indoorWalls
    return "city/" .. list[1 + floor(hash(S.lot[i], 7, 1) * #list)]
  end
  local l = S.lot[i]
  local list = th.facades
  return "city/" .. list[1 + floor(hash(l, 7, 1) * #list)]
end

-- Видимые куски [a, b] минус твёрдое соседа j. Возвращает до двух кусков.
local function visible(S, a, b, j)
  local low = S.low[j] or 99
  local p = max(a, low)
  if p >= b - 0.001 then return nil end
  local ub = S.upB[j]
  if ub and ub < b and S.upT[j] > p then
    local r1, r2
    if ub > p + 0.001 then r1 = {p, ub} end
    if S.upT[j] < b - 0.001 then r2 = {max(p, S.upT[j]), b} end
    return r1, r2
  end
  return {p, b}
end

-- Грань стены к соседу: в журнал (потом склеим соседние клетки в одну полосу) — ключ: текстура, вид, направление,
-- линия, высоты.
local function addFace(S, cx, cz, d, a, b, tex, mode, ref0, ref1)
  local D = DIRS[d]
  local line = (d <= 2) and (cz + D.bz) or (cx + D.bx)
  local along = (d <= 2) and cx or cz
  local key = tex .. "|" .. mode .. "|" .. d .. "|" .. line .. "|" .. a .. "|" .. b .. "|" .. (ref0 or 0) .. "|" .. (ref1 or 0)
  local f = S.faces[key]
  if not f then
    f = {tex = tex, mode = mode, d = d, line = line, a = a, b = b, ref0 = ref0, ref1 = ref1, cells = {}}
    S.faces[key] = f
  end
  f.cells[#f.cells + 1] = along
end

-- --- Украшения фасада ---

local function decorate(S, i, cx, cz, d, p, q, street)
  local th = S.th
  if S.indoor then return end
  local Bd = S.D
  local D = DIRS[d]
  local along = (d <= 2) and cx or cz
  local ground = p
  local lotId = S.lot[i]
  local winTex = "city/" .. th.windows[1 + floor(hash(lotId, 3, 9) * #th.windows)]
  local parity = floor(hash(lotId, 4, 2) * 2)
  local rows = floor((q - 0.3 - ground) / STOREY + 0.001)
  local function rnd(k, salt) return hash(cx * 4 + d, cz, k, salt) end
  -- первый этаж (только на улицу)
  if street and q - ground >= 1.6 then
    local r = rnd(0, 1)
    if r < 0.13 then
      local door = "city/" .. th.doors[1 + floor(rnd(0, 2) * #th.doors)]
      faceRect(bucket(Bd, door), cx, cz, d, 0.18, 0.82, ground, ground + 1.2, 0.012, 0, 1, 0, 1)
      if th.awning and rnd(0, 3) < 0.6 then
        -- навес: наклонная ткань над дверью
        local m = bucket(Bd, "city/" .. th.awning)
        local bx, bz = cx + D.bx, cz + D.bz
        local ox, oz = D.nx * 0.45, D.nz * 0.45
        local x0, z0 = bx + D.ax * 0.05 + D.nx * 0.02, bz + D.az * 0.05 + D.nz * 0.02
        local x1, z1 = bx + D.ax * 0.95 + D.nx * 0.02, bz + D.az * 0.95 + D.nz * 0.02
        local y0, y1 = ground + 1.45, ground + 1.27
        quad(m, x0, y0, z0, x1, y0, z1, x1 + ox, y1, z1 + oz, x0 + ox, y1, z0 + oz, 0, 0, 1, 1)
      elseif th.signs and rnd(0, 4) < 0.5 then
        local sg = "city/" .. th.signs[1 + floor(rnd(0, 5) * #th.signs)]
        faceRect(bucket(Bd, sg), cx, cz, d, -0.1, 1.1, ground + 1.3, ground + 1.55, 0.025, 0, 1, 0, 1)
      end
    elseif th.shop and r < 0.3 then
      faceRect(bucket(Bd, "city/" .. th.shop), cx, cz, d, 0.05, 0.95, ground, ground + 1.3, 0.012, 0, 1, 0, 1)
    elseif (along + parity) % 2 == 0 and r < 0.75 then
      faceRect(bucket(Bd, winTex), cx, cz, d, 0.3, 0.7, ground + 0.5, ground + 1.25, 0.012, 0, 1, 0, 1)
    end
  end
  -- этажи выше
  for k = 1, rows do
    local yb = ground + STOREY * k
    if yb + 1.3 > q - 0.25 then break end
    if (along + parity) % 2 == 0 and rnd(k, 1) < 0.85 then
      faceRect(bucket(Bd, winTex), cx, cz, d, 0.3, 0.7, yb + 0.45, yb + 1.25, 0.012, 0, 1, 0, 1)
      local r = rnd(k, 2)
      if r < 0.14 and street then
        -- балкон: плита и перила
        local slab = bucket(Bd, "city/" .. th.trim)
        local bx, bz = cx + D.bx, cz + D.bz
        local xa, za = bx + D.ax * 0.15, bz + D.az * 0.15
        local xb, zb = bx + D.ax * 0.85 + D.nx * 0.35, bz + D.az * 0.85 + D.nz * 0.35
        boxMesh(slab, min(xa, xb), yb + 0.38, min(za, zb), max(xa, xb), yb + 0.45, max(za, zb))
        local rail = bucket(Bd, "city/railing")
        faceRect(rail, cx, cz, d, 0.15, 0.85, yb + 0.45, yb + 0.95, 0.35, 0, 1, 0, 1)
      elseif r < 0.26 then
        -- кондиционер под окном
        local ac = bucket(Bd, "city/ac")
        local bx, bz = cx + D.bx, cz + D.bz
        local xa, za = bx + D.ax * 0.35, bz + D.az * 0.35
        local xb, zb = bx + D.ax * 0.65 + D.nx * 0.18, bz + D.az * 0.65 + D.nz * 0.18
        boxMesh(ac, min(xa, xb), yb + 0.15, min(za, zb), max(xa, xb), yb + 0.4, max(za, zb))
      end
    end
  end
  -- водосточная труба на краю участка
  if street and rnd(9, 9) < 0.05 then
    local pm = bucket(Bd, "city/pipe")
    local bx, bz = cx + D.bx, cz + D.bz
    local xa, za = bx + D.ax * 0.03, bz + D.az * 0.03
    local xb, zb = bx + D.ax * 0.08 + D.nx * 0.05, bz + D.az * 0.08 + D.nz * 0.05
    boxMesh(pm, min(xa, xb), ground, min(za, zb), max(xa, xb), q - 0.1, max(za, zb))
  end
end

-- --- Сборка по строкам ---

local function cellFaces(S, x, z)
  local map = S.map
  local W, H = map.W, map.H
  local i = z * W + x + 1
  local k = S.kind[i]
  local th, tx = S.th, map.def.tex
  local base = map.base
  -- интервалы твёрдого клетки: нижний [−1, low] и верхний [upB, upT]
  local parts = {{-1, S.low[i], "low"}}
  if S.upB[i] then parts[2] = {S.upB[i], S.upT[i], "up"} end
  for d = 1, 4 do
    local D = DIRS[d]
    local nx, nz = x + D[1], z + D[2]
    if nx >= 0 and nz >= 0 and nx < W and nz < H then
      local j = nz * W + nx + 1
      local nk = S.kind[j]
      for _, pr in ipairs(parts) do
        local r1, r2 = visible(S, pr[1], pr[2], j)
        for _, r in ipairs({r1, r2}) do
          if r then
            local p, q = r[1], r[2]
            if pr[3] == "low" and (k == K_OPEN or k == K_ROOFED) then
              -- уступ пола (ступень, край площадки)
              addFace(S, x, z, d, p, q, tx.ledge or tx.wall2, "world")
            elseif nk == K_ROOFED and not S.indoor and p < S.upB[j] then
              -- стена комнаты: от пола до потолка соседа — интерьер, выше (если есть) — фасад
              local rf, fb = S.upB[j], base[j]
              addFace(S, x, z, d, p, min(q, rf), "city/" .. th.inWall, "room", fb, rf)
              if q > rf + 0.001 then addFace(S, x, z, d, rf, q, facadeOf(S, i), "facade") end
            else
              local tex = facadeOf(S, i)
              local street = nk == K_OPEN and abs(p - base[j]) < 0.01
              local topEdge = (pr[3] == "up" and abs(q - S.upT[i]) < 0.01) or (k == K_WALL and abs(q - S.top[i]) < 0.01)
              if S.indoor and nk == K_ROOFED then
                -- закрытая карта: стена от пола до потолка одной картинкой (плинтус внизу, тень у потолка)
                addFace(S, x, z, d, p, q, tex, "room", base[j], S.upB[j])
              elseif S.indoor then
                addFace(S, x, z, d, p, q, tex, "world1")
              elseif topEdge and q - p > 0.8 then
                addFace(S, x, z, d, p, q - 0.25, tex, "facade")
                addFace(S, x, z, d, q - 0.25, q, "city/" .. th.trim, "band", q - 0.25, q)
              else
                addFace(S, x, z, d, p, q, tex, "facade")
              end
              if nk == K_OPEN or (nk == K_WALL and q - p > 1.6) then decorate(S, i, x, z, d, p, q, street) end
            end
          end
        end
      end
    end
  end
end

-- Горизонтальные поверхности: полы, крыши, потолки, подоконники — полосами по строке.
local function rowFlats(S, z)
  local map = S.map
  local W = map.W
  local th, tx = S.th, map.def.tex
  local B = S.B
  local function emit(key, x0, x1, y, rep)
    flatRect(bucket(B, key), x0, z, x1, z + 1, y, rep)
  end
  local runs = {}
  local function push(list, key, x, y, rep)
    local r = list[#list]
    if r and r.key == key and r.y == y and r.x1 == x then
      r.x1 = x + 1
    else
      list[#list + 1] = {key = key, x0 = x, x1 = x + 1, y = y, rep = rep}
    end
  end
  local floors, tops, ceils, extra = {}, {}, {}, {}
  for x = 0, W - 1 do
    local i = z * W + x + 1
    local k, ch = S.kind[i], map.c[i]
    if k == K_OPEN or k == K_ROOFED then
      local mat
      if ch == "A" or ch == "B" then mat = tx.site
      elseif k == K_ROOFED and not S.indoor then mat = "city/" .. th.inFloor
      elseif ch == "," then mat = tx.floor2
      else
        local nearWall = false
        for d = 1, 4 do
          local nx, nz = x + DIRS[d][1], z + DIRS[d][2]
          local nch = M.cell(map, nx, nz)
          if nch == "#" or nch == "%" or nch == "W" then nearWall = true end
        end
        if th.side and nearWall and k == K_OPEN then mat = "city/" .. th.side
        elseif th.road and not S.indoor then mat = "city/" .. th.road
        else mat = tx.floor end
      end
      push(floors, mat, x, map.base[i], 1)
      if k == K_ROOFED then
        push(ceils, S.indoor and (map.def.ceiling or tx.roof or "ceiling") or "city/" .. th.ceiling, x, S.upB[i], 1)
        if not S.indoor then push(tops, "city/" .. th.roof, x, S.upT[i], 2) end
      end
    elseif k == K_WALL then
      if not S.indoor then push(tops, "city/" .. th.roof, x, S.top[i], 2) end
    elseif k == K_WINDOW then
      push(extra, "city/" .. th.trim, x, map.base[i] + City.SILL, 1)
      push(ceils, "city/" .. th.trim, x, S.upB[i], 1)
      if not S.indoor then push(tops, "city/" .. th.roof, x, S.top[i], 2) end
    end
  end
  for _, list in ipairs({floors, tops, extra}) do
    for _, r in ipairs(list) do emit(r.key, r.x0, r.x1, r.y, r.rep) end
  end
  for _, r in ipairs(ceils) do
    -- потолок смотрит вниз: обход в обратную сторону (для запекания света — та же плоскость, обе стороны)
    local m = bucket(B, r.key)
    quad(m, r.x0, r.y, z + 1, r.x1, r.y, z + 1, r.x1, r.y, z, r.x0, r.y, z, r.x0, z + 1, r.x1, z)
  end
end

-- Ящики (как раньше): боковые грани выше соседей и верх.
local function crates(S, x, z)
  local map = S.map
  local i = z * map.W + x + 1
  local ch = map.c[i]
  local h = M.HEIGHT[ch]
  if not h or h >= 99 or ch == "W" then return end
  local tx = map.def.tex
  local m = bucket(S.B, ch == "X" and tx.crate or tx.box)
  local b0, t = map.base[i], map.base[i] + h
  for d = 1, 4 do
    local nx, nz = x + DIRS[d][1], z + DIRS[d][2]
    local nt = M.h(map, nx, nz)
    if nt < t then
      local yb = max(nt, b0)
      faceRect(m, x, z, d, 0, 1, yb, t, 0, 0, 1, 0, (t - yb) / h)
    end
  end
  flatRect(m, x, z, x + 1, z + 1, t, 1)
end

-- Дома за краем карты (горизонт): участки вдоль каждой стороны, видна грань к карте и крыша.
local function skyline(S)
  local map = S.map
  if S.indoor then return end
  local W, H = map.W, map.H
  local far = "city/" .. (S.th.far or "des_far")
  local roof = "city/" .. S.th.roof
  local mf, mr = bucket(S.D, far), bucket(S.D, roof)
  local function strip(n, salt, place)
    local a = 0
    local k = 0
    while a < n do
      k = k + 1
      local len = 4 + floor(hash(k, salt, 1) * 6)
      local b = min(n, a + len)
      local h = (map.wallH or M.WALL_H) + 4 + floor(hash(k, salt, 2) * 8)
      local depth = 4 + floor(hash(k, salt, 3) * 4)
      place(a, b, h, depth)
      a = b
    end
  end
  -- сторона z < 0 (смотрит на +z)
  strip(W, 31, function(a, b, h, dep)
    quad(mf, b, h, -1, a, h, -1, a, 0, -1, b, 0, -1, 0, 0, (b - a) / 2.2, h / 2.2)
    flatRect(mr, a, -1 - dep, b, -1, h, 2)
  end)
  strip(W, 37, function(a, b, h, dep)
    quad(mf, a, h, H + 1, b, h, H + 1, b, 0, H + 1, a, 0, H + 1, 0, 0, (b - a) / 2.2, h / 2.2)
    flatRect(mr, a, H + 1, b, H + 1 + dep, h, 2)
  end)
  strip(H, 41, function(a, b, h, dep)
    quad(mf, -1, h, a, -1, h, b, -1, 0, b, -1, 0, a, 0, 0, (b - a) / 2.2, h / 2.2)
    flatRect(mr, -1 - dep, a, -1, b, h, 2)
  end)
  strip(H, 43, function(a, b, h, dep)
    quad(mf, W + 1, h, b, W + 1, h, a, W + 1, 0, a, W + 1, 0, b, 0, 0, (b - a) / 2.2, h / 2.2)
    flatRect(mr, W + 1, a, W + 1 + dep, b, h, 2)
  end)
end

-- Склеить грани по линиям в полосы и выдать в сетки.
local function flushFaces(S)
  local B = S.B
  for _, f in pairs(S.faces) do
    table.sort(f.cells)
    local m = bucket(B, f.tex)
    local d, D = f.d, DIRS[f.d]
    local cells = f.cells
    local s = 1
    while s <= #cells do
      local e = s
      while e < #cells and cells[e + 1] == cells[e] + 1 do e = e + 1 end
      local c0, c1 = cells[s], cells[e]
      -- координаты полосы на линии: от c0 до c1 + 1
      local x0, z0, x1, z1
      if d <= 2 then
        x0, x1 = c0, c1 + 1
        z0, z1 = f.line, f.line
      else
        z0, z1 = c0, c1 + 1
        x0, x1 = f.line, f.line
      end
      -- «лево» и «право» для зрителя снаружи
      local lx, lz, rx, rz
      if D.ax + D.az > 0 then
        lx, lz, rx, rz = x0, z0, x1, z1
      else
        lx, lz, rx, rz = x1, z1, x0, z0
      end
      local a, b = f.a, f.b
      local uL = (lx * D.ax + lz * D.az)
      local uR = (rx * D.ax + rz * D.az)
      local u0, u1, v0, v1
      if f.mode == "facade" then
        u0, u1, v0, v1 = uL, uR, -b, -a
      elseif f.mode == "room" then
        local span = f.ref1 - f.ref0
        u0, u1, v0, v1 = uL, uR, (f.ref1 - b) / span, (f.ref1 - a) / span
      elseif f.mode == "band" then
        u0, u1, v0, v1 = uL, uR, 0, 1
      else
        u0, u1, v0, v1 = uL, uR, -b, -a
      end
      quad(m, lx, b, lz, rx, b, rz, rx, a, rz, lx, a, lz, u0, v0, u1, v1)
      s = e + 1
    end
  end
end

-- --- Публично ---

-- Начать сборку карты; потом City.step(job) до job.done.
function City.begin(map)
  return prepare(map)
end

-- Шаг сборки: несколько строк клеток (не больше ~1 млн инструкций), в конце — склейка и выдача сеток.
function City.step(S, rows)
  local map = S.map
  local W, H = map.W, map.H
  local stop = min(H, S.row + (rows or 6))
  for z = S.row, stop - 1 do
    for x = 0, W - 1 do
      cellFaces(S, x, z)
      crates(S, x, z)
    end
    rowFlats(S, z)
  end
  S.row = stop
  if S.row >= H and not S.flushed then
    S.flushed = true
    flushFaces(S)
    skyline(S)
    return false
  end
  if S.flushed then S.done = true end
  return S.done
end

-- Сетки → статическая сцена. T(name) — номер текстуры. Возвращает номера моделей (освободить при смене карты)
-- и число треугольников.
function City.commit(S, T)
  local tris, ids = 0, {}
  for _, set in ipairs({{S.B, true}, {S.D, false}}) do
    for key, m in pairs(set[1]) do
      if #m.i > 0 then
        local id = g3d.mesh({vertices = m.v, indices = m.i})
        ids[#ids + 1] = id
        g3d.static(id, 0, 0, 0, {texture = T(key), solid = set[2]})
        tris = tris + #m.i / 3
      end
    end
  end
  S.B, S.D, S.faces = nil, nil, nil
  return ids, tris
end

-- Звуки карты: пол в домах (шаги) и фон.
local INDOOR_SURFACE = {desert = "tile", oasis = "tile", italian = "wood", industrial = "concrete", village = "wood",
  alpine = "wood", office = "carpet"}
local AMBIENT = {desert = "amb_desert", oasis = "amb_desert", italian = "amb_city", industrial = "amb_city",
  village = "amb_nature", alpine = "amb_snow", office = "amb_indoor"}
function City.sounds(map)
  local def = map.def
  local name = def.theme or MAP_THEME[def.key] or "desert"
  local amb = AMBIENT[name] or "amb_desert"
  if def.ceiling then amb = "amb_indoor" end
  return INDOOR_SURFACE[name] or "tile", amb
end

City.THEMES = THEMES
return City
