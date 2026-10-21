-- 3D: карта (стены, ящики, пол, точки A/B), игроки (блочные фигуры как в Minecraft), оружие на земле, бомба,
-- гранаты, дым, эффекты. Рисуется программно через g3d; попадания определяются по g3d.pick (id частей тела).
local D = require("data")
local M = require("maps")
local Sim = require("sim")

local R = {tex = {}, fx = {}, mapMeshes = nil, mapIndex = nil}
local floor, sin, cos, rad, min, max, sqrt = math.floor, math.sin, math.cos, math.rad, math.min, math.max, math.sqrt

function R.T(name)
  local t = R.tex[name]
  if not t then
    t = g3d.texture("textures/" .. name .. ".png")
    R.tex[name] = t
  end
  return t
end

-- --- Сборка сетки ---

local function newMesh() return {v = {}, i = {}} end

local function quad(m, x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, u1, v1, u2, v2)
  local n = #m.v / 5
  local vs = m.v
  local k = #vs
  vs[k + 1], vs[k + 2], vs[k + 3], vs[k + 4], vs[k + 5] = x1, y1, z1, u1, v1
  vs[k + 6], vs[k + 7], vs[k + 8], vs[k + 9], vs[k + 10] = x2, y2, z2, u2, v1
  vs[k + 11], vs[k + 12], vs[k + 13], vs[k + 14], vs[k + 15] = x3, y3, z3, u2, v2
  vs[k + 16], vs[k + 17], vs[k + 18], vs[k + 19], vs[k + 20] = x4, y4, z4, u1, v2
  local is = m.i
  local j = #is
  is[j + 1], is[j + 2], is[j + 3], is[j + 4], is[j + 5], is[j + 6] = n + 1, n + 2, n + 3, n + 1, n + 3, n + 4
end

local function finishMesh(m)
  if #m.v == 0 then return nil end
  return g3d.mesh({vertices = m.v, indices = m.i})
end

-- Боковые грани клетки высотой top над соседями ниже (от высоты соседа до top).
local function sides(m, map, cx, cz, top)
  local hs = {M.h(map, cx, cz - 1), M.h(map, cx, cz + 1), M.h(map, cx - 1, cz), M.h(map, cx + 1, cz)}
  local x1, x2, z1, z2 = cx, cx + 1, cz, cz + 1
  if hs[1] < top then local b = hs[1] quad(m, x1, top, z1, x2, top, z1, x2, b, z1, x1, b, z1, 0, 0, 1, top - b) end
  if hs[2] < top then local b = hs[2] quad(m, x2, top, z2, x1, top, z2, x1, b, z2, x2, b, z2, 0, 0, 1, top - b) end
  if hs[3] < top then local b = hs[3] quad(m, x1, top, z2, x1, top, z1, x1, b, z1, x1, b, z2, 0, 0, 1, top - b) end
  if hs[4] < top then local b = hs[4] quad(m, x2, top, z1, x2, top, z2, x2, b, z2, x2, b, z1, 0, 0, 1, top - b) end
end

local function flat(m, cx, cz, y)
  quad(m, cx, y, cz, cx + 1, y, cz, cx + 1, y, cz + 1, cx, y, cz + 1, 0, 0, 1, 1)
end

function R.buildMap(map)
  if R.mapMeshes then
    for _, id in pairs(R.mapMeshes) do if type(id) == "number" then g3d.free(id) end end
  end
  local walls, walls2, crates, boxes, floor2, sites = newMesh(), newMesh(), newMesh(), newMesh(), newMesh(), newMesh()
  for cz = 0, map.H - 1 do
    for cx = 0, map.W - 1 do
      local ch = M.cell(map, cx, cz)
      if ch == "#" then sides(walls, map, cx, cz, M.WALL_H)
      elseif ch == "%" then sides(walls2, map, cx, cz, M.WALL_H)
      elseif ch == "X" or ch == "x" then
        local m = ch == "X" and crates or boxes
        local h = M.HEIGHT[ch]
        sides(m, map, cx, cz, h)
        flat(m, cx, cz, h)
      elseif ch == "," then flat(floor2, cx, cz, 0.003)
      elseif ch == "A" or ch == "B" then flat(sites, cx, cz, 0.004) end
    end
  end
  local mm = {
    walls = finishMesh(walls), walls2 = finishMesh(walls2), crates = finishMesh(crates), boxes = finishMesh(boxes),
    floor2 = finishMesh(floor2), sites = finishMesh(sites), floor = g3d.plane(map.W, map.H, 1),
  }
  -- Большие буквы A и B на полу точек.
  local letter = newMesh()
  quad(letter, -1.5, 0, -1.5, 1.5, 0, -1.5, 1.5, 0, 1.5, -1.5, 0, 1.5, 0, 0, 1, 1)
  mm.letter = finishMesh(letter)
  R.mapMeshes = mm
  R.mapIndex = map.index
end

-- --- Фигура игрока: части с развёрткой как у скина Minecraft (64×64) ---

local PX = 1 / 32

local function cuboid(pw, ph, pd, u0, v0, oy)
  local m = newMesh()
  local w, h, d = pw * PX, ph * PX, pd * PX
  local x1, x2, y1, y2, z1, z2 = -w / 2, w / 2, oy * PX, oy * PX + h, -d / 2, d / 2
  local function r(u, v, uw, vh) return u / 64, v / 64, (u + uw) / 64, (v + vh) / 64 end
  local a, b, c, e
  a, b, c, e = r(u0 + pd, v0 + pd, pw, ph)
  quad(m, x2, y2, z2, x1, y2, z2, x1, y1, z2, x2, y1, z2, a, b, c, e)             -- перед
  a, b, c, e = r(u0 + 2 * pd + pw, v0 + pd, pw, ph)
  quad(m, x1, y2, z1, x2, y2, z1, x2, y1, z1, x1, y1, z1, a, b, c, e)             -- спина
  a, b, c, e = r(u0, v0 + pd, pd, ph)
  quad(m, x2, y2, z1, x2, y2, z2, x2, y1, z2, x2, y1, z1, a, b, c, e)             -- правый бок
  a, b, c, e = r(u0 + pd + pw, v0 + pd, pd, ph)
  quad(m, x1, y2, z2, x1, y2, z1, x1, y1, z1, x1, y1, z2, a, b, c, e)             -- левый бок
  a, b, c, e = r(u0 + pd, v0, pw, pd)
  quad(m, x2, y2, z1, x1, y2, z1, x1, y2, z2, x2, y2, z2, a, b, c, e)             -- верх
  a, b, c, e = r(u0 + pd + pw, v0, pw, pd)
  quad(m, x2, y1, z2, x1, y1, z2, x1, y1, z1, x2, y1, z1, a, b, c, e)             -- низ
  return finishMesh(m)
end

function R.init()
  R.body = {
    head = cuboid(8, 8, 8, 0, 0, 0),
    torso = cuboid(8, 12, 4, 16, 16, 0),
    arm = cuboid(4, 12, 4, 40, 16, -10),
    leg = cuboid(4, 12, 4, 0, 16, -12),
  }
  R.box = g3d.box(1, 1, 1, 0)
end

-- Точка тела (lx, ly, lz) → мир: падение назад (fall, градусы вокруг оси X), затем поворот yaw.
local function place(x, y, z, yaw, fall, lx, ly, lz)
  local cf, sf = cos(rad(fall)), sin(rad(fall))
  local y2 = cf * ly + sf * lz
  local z2 = -sf * ly + cf * lz
  local cy, sy = cos(rad(yaw)), sin(rad(yaw))
  return x + cy * lx + sy * z2, y + y2, z + (-sy * lx + cy * z2)
end

local function part(mesh, tex, x, y, z, yaw, fall, lx, ly, lz, pitch, id, sx, sy, sz, tint)
  local px, py, pz = place(x, y, z, yaw, fall, lx, ly, lz)
  g3d.draw(mesh, px, py, pz, {yaw = yaw, pitch = pitch + fall, texture = tex, id = id, sx = sx, sy = sy, sz = sz, tint = tint})
end

-- Нарисовать игрока p в точке (p.rx, p.ry, p.rz). id частей: p.id * 4 + 1 тело, + 2 голова, + 3 ноги.
function R.drawPlayer(p, now)
  local x, y, z, yaw, look = p.rx, p.ry, p.rz, p.ryaw, p.rpitch or 0
  local skin = R.T(p.team == D.T and "skin_t" or "skin_ct")
  local fall = 0
  if not p.alive then
    fall = min(90, (now - (p.deathT or now)) * 260)
    look = 0
    y = y + 0.13 * fall / 90
  end
  local tint = p.hurtT and now - p.hurtT < 0.12 and "#FFFF8080" or nil
  local speed = sqrt((p.vx or 0) ^ 2 + (p.vz or 0) ^ 2)
  local swing = p.alive and sin((p.walkT or 0) * 9) * 40 * min(1, speed / 3) or 0
  local crouch = p.alive and p.crouch
  local dy = crouch and -0.17 or 0
  local legS = crouch and 0.55 or 1
  local id = p.alive and p.id * 4 or 0
  local b = R.body
  -- ноги
  part(b.leg, skin, x, y, z, yaw, fall, 0.0625, 0.375 * legS, 0, swing, id + 3, 1, legS, 1, tint)
  part(b.leg, skin, x, y, z, yaw, fall, -0.0625, 0.375 * legS, 0, -swing, id + 3, 1, legS, 1, tint)
  -- туловище и голова
  part(b.torso, skin, x, y, z, yaw, fall, 0, 0.375 + dy, crouch and -0.04 or 0, crouch and 12 or 0, id + 1, 1, 1, 1, tint)
  part(b.head, skin, x, y, z, yaw, fall, 0, 0.75 + dy, 0, look, id + 2, 1, 1, 1, tint)
  -- руки: держат оружие перед собой
  local armP = p.alive and (85 + look * 0.9) or 0
  local w = D.WEAPONS[p.wpn or 1] or D.WEAPONS[1]
  part(b.arm, skin, x, y, z, yaw - 12, fall, 0.1875, 0.6875 + dy, 0, armP, id + 1, 1, 1, 1, tint)
  part(b.arm, skin, x, y, z, yaw + 28, fall, -0.1875, 0.6875 + dy, 0, armP * 0.95, id + 1, 1, 1, 1, tint)
  if p.alive then
    -- оружие в руке
    local len = w.len or (w.slot == 3 and 0.18 or 0.1)
    local fx, fy, fz = Sim.forward(yaw, look)
    local hx, hy, hz = place(x, y, z, yaw, 0, 0.06, 0.6875 + dy, 0.3)
    local gx, gy, gz = hx + fx * len * 0.4, hy + fy * len * 0.4 + 0.02, hz + fz * len * 0.4
    local gunTex = R.T(w.slot == 3 and "knife" or w.slot == 4 and "nade_" .. w.key or w.slot == 5 and "c4" or "gun")
    g3d.draw(R.box, gx, gy, gz, {yaw = yaw, pitch = look, sx = 0.05, sy = w.slot == 1 and 0.08 or 0.06, sz = len,
      texture = gunTex, id = id + 1})
    if p.flashT and now - p.flashT < 0.06 and w.clip then
      g3d.sprite(R.T("muzzle"), gx + fx * len * 0.6, gy + fy * len * 0.6, gz + fz * len * 0.6, 0.22, 0.22)
    end
    if p.bomb then
      local bx, by, bz = place(x, y, z, yaw, 0, 0, 0.55 + dy, -0.11)
      g3d.draw(R.box, bx, by, bz, {yaw = yaw, sx = 0.2, sy = 0.24, sz = 0.08, texture = R.T("c4"), id = id + 1})
    end
  end
end

-- --- Эффекты ---

function R.addFx(kind, x, y, z, life, size)
  R.fx[#R.fx + 1] = {kind = kind, x = x, y = y, z = z, t = 0, life = life, size = size or 1}
  if #R.fx > 80 then table.remove(R.fx, 1) end
end

function R.updateFx(dt)
  for k = #R.fx, 1, -1 do
    local f = R.fx[k]
    f.t = f.t + dt
    if f.t >= f.life then table.remove(R.fx, k) end
  end
end

-- Дым: облако из «клубов», положение клубов постоянное для каждого дыма.
local PUFFS = {}
for i = 1, 11 do
  local a = i * 2.39996
  local r = (i % 4) / 4 + 0.15
  PUFFS[i] = {cos(a) * r, 0.25 + (i % 5) * 0.32, sin(a) * r}
end

local function drawSmoke(s)
  local r = s.r
  if r < 0.1 then return end
  local tex = R.T("smoke")
  for _, o in ipairs(PUFFS) do
    g3d.sprite(tex, s.x + o[1] * r * 0.9, s.y + o[2] * r * 0.55, s.z + o[3] * r * 0.9, r * 1.15, r * 1.15, {tint = "#FFD8D8D8"})
  end
end

-- --- Кадр ---

-- cam = {x, y, z, yaw, pitch, fov}; hide — id игрока, которого не рисовать (свой персонаж / за кем следим).
function R.drawWorld(V, cam, sx, sy, sw, sh, quality, now, hide)
  local map = V.map
  local def = map.def
  local tx = def.tex
  local mm = R.mapMeshes
  g3d.camera(cam.x, cam.y, cam.z, cam.yaw, cam.pitch, cam.fov)
  g3d.fog(def.fog, def.fogNear, def.fogFar)
  local l = def.light or {-0.45, -1, -0.3, 0.55}
  g3d.light(l[1], l[2], l[3], l[4])
  g3d.begin(sx, sy, sw, sh, def.sky, quality)
  g3d.draw(mm.floor, map.W / 2, 0, map.H / 2, {texture = R.T(tx.floor), lit = false})
  if def.ceiling then g3d.draw(mm.floor, map.W / 2, M.WALL_H, map.H / 2, {texture = R.T(def.ceiling), lit = false}) end
  if mm.floor2 then g3d.draw(mm.floor2, 0, 0, 0, {texture = R.T(tx.floor2), lit = false}) end
  if mm.sites then g3d.draw(mm.sites, 0, 0, 0, {texture = R.T(tx.site), lit = false}) end
  for _, s in ipairs({"A", "B"}) do
    local c = map.siteCenter[s]
    g3d.draw(mm.letter, c[1], 0.006, c[2], {texture = R.T(s == "A" and "letter_a" or "letter_b"), lit = false})
  end
  if mm.walls then g3d.draw(mm.walls, 0, 0, 0, {texture = R.T(tx.wall)}) end
  if mm.walls2 then g3d.draw(mm.walls2, 0, 0, 0, {texture = R.T(tx.wall2)}) end
  if mm.crates then g3d.draw(mm.crates, 0, 0, 0, {texture = R.T(tx.crate)}) end
  if mm.boxes then g3d.draw(mm.boxes, 0, 0, 0, {texture = R.T(tx.box)}) end
  -- игроки
  for _, p in pairs(V.players) do
    if p.id ~= hide and p.rx then R.drawPlayer(p, now) end
  end
  -- оружие на земле
  for _, d in ipairs(V.drops) do
    local w = D.WEAPONS[d.wid]
    if w then g3d.sprite(R.T("i_" .. w.key), d.x, d.y + 0.1, d.z, 0.5, 0.2) end
  end
  -- бомба
  local b = V.bomb
  if b.state == "dropped" or b.state == "planted" or b.state == "defused" then
    g3d.draw(R.box, b.x, b.y + 0.06, b.z, {sx = 0.32, sy = 0.12, sz = 0.22, texture = R.T("c4")})
    if b.state == "planted" then
      local rate = b.timer > 10 and 1 or (b.timer > 3 and 0.5 or 0.2)
      if (now % rate) < rate * 0.4 then g3d.sprite(R.T("bomb_light"), b.x, b.y + 0.16, b.z, 0.14, 0.14) end
    end
  end
  for _, n in ipairs(V.nades) do
    local w = D.WEAPONS[n.kind]
    if w then g3d.sprite(R.T("nade_" .. w.key), n.x, n.y, n.z, 0.14, 0.14) end
  end
  for _, s in ipairs(V.smokes) do drawSmoke(s) end
  for _, f in ipairs(R.fx) do
    local k = f.t / f.life
    if f.kind == "impact" then
      g3d.sprite(R.T("impact"), f.x, f.y, f.z, 0.09, 0.09)
    elseif f.kind == "spark" then
      if k < 0.5 then g3d.sprite(R.T("spark"), f.x, f.y, f.z, 0.16, 0.16) end
    elseif f.kind == "blood" then
      g3d.sprite(R.T("blood"), f.x, f.y + k * 0.1, f.z, 0.25 + k * 0.2, 0.25 + k * 0.2)
    elseif f.kind == "boom" then
      local s = f.size * (0.6 + k * 0.8)
      g3d.sprite(R.T("explosion"), f.x, f.y + s * 0.4, f.z, s, s)
    elseif f.kind == "flash" then
      if k < 0.3 then g3d.sprite(R.T("spark"), f.x, f.y, f.z, 1.2, 1.2) end
    end
  end
  g3d.finish()
end

-- Камера внутри дыма — сплошная серая пелена (клубы вблизи не рисуются).
function R.smokeCover(V, x, y, z)
  local best = 0
  for _, s in ipairs(V.smokes) do
    if s.r > 0.3 then
      local d = sqrt((x - s.x) ^ 2 + (z - s.z) ^ 2 + ((y - s.y - 0.9) * 0.7) ^ 2)
      if d < s.r then best = max(best, min(1, (s.r - d) / 0.8 + 0.5)) end
    end
  end
  return best
end

return R
