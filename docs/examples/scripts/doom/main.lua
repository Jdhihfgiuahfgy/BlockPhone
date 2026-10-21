-- DOOM для BlockPhone 2.0: 3D-шутер на g3d с бесконечными случайными уровнями.
-- Каждый уровень строится заново (комнаты, коридоры, колонны, ящики, лава), локации сменяются: база, склад, шахты,
-- лесопилка, ледник, преисподняя. Чем дальше — тем больше и злее монстры, появляется новое оружие.
-- Управление: мышь — обзор (нажмите на экран, чтобы захватить мышь; Esc — пауза), WASD/стрелки — ходьба, Shift — бег,
-- ЛКМ — огонь, 1–6 или колесо — оружие, M — карта. Дойдите до двери EXIT.

local THEMES = {
  {name = "База", wall = "wall_tech", floor = "floor_metal", ceil = "ceiling", fog = "#05080B"},
  {name = "Склад", wall = "wall_brick", floor = "floor_tile", ceil = "ceiling", fog = "#0E0806"},
  {name = "Шахты", wall = "wall_stone", floor = "floor_dirt", ceil = "ceiling_rock", fog = "#080604", lava = 0.25},
  {name = "Лесопилка", wall = "wall_wood", floor = "floor_wood", ceil = "ceiling", fog = "#0A0704"},
  {name = "Ледник", wall = "wall_ice", floor = "floor_ice", ceil = "ceiling_ice", fog = "#0A1420"},
  {name = "Цех", wall = "wall_metal", floor = "floor_metal", ceil = "ceiling", fog = "#06060A"},
  {name = "Преисподняя", wall = "wall_hell", floor = "floor_hell", ceil = "ceiling_rock", fog = "#1A0402", lava = 0.6},
}

-- Монстры: from — с какого уровня появляются, weight — как часто.
local MONSTERS = {
  z = {name = "Зомби", tex = "zombie", hp = 25, speed = 1.2, y = 0.5, w = 0.85, h = 1.0, hitscan = true, dmg = 6, cool = 1.7, range = 12, from = 1, weight = 5},
  i = {name = "Бес", tex = "imp", hp = 55, speed = 1.4, y = 0.52, w = 0.9, h = 1.05, ball = "fireball", dmg = 10, cool = 2.2, range = 12, from = 1, weight = 4},
  d = {name = "Демон", tex = "demon", hp = 110, speed = 2.7, y = 0.55, w = 1.05, h = 1.1, melee = true, dmg = 15, cool = 1.0, from = 2, weight = 3},
  c = {name = "Какодемон", tex = "caco", hp = 170, speed = 1.0, y = 0.85, w = 0.95, h = 0.95, ball = "fireball", dmg = 18, cool = 2.8, range = 14, from = 3, weight = 2},
  b = {name = "Барон", tex = "baron", hp = 480, speed = 1.2, y = 0.75, w = 1.25, h = 1.5, ball = "greenball", dmg = 28, cool = 2.0, range = 16, from = 5, weight = 1},
}

-- Оружие: hitscan — мгновенно (по прицелу), иначе снаряд (proj) со взрывом (splash).
local WEAPONS = {
  {name = "Пистолет", tex = "gun_pistol", ammo = "bullets", dmg = 15, pellets = 1, spread = 0, cool = 0.38, sound = "block.note_block.snare", pitch = 0.6, from = 1, tip = 0.5},
  {name = "Дробовик", tex = "gun_shotgun", ammo = "shells", dmg = 11, pellets = 7, spread = 0.08, cool = 0.9, sound = "entity.generic.explode", pitch = 1.8, from = 2, tip = 0.5},
  {name = "Пулемёт", tex = "gun_chaingun", ammo = "bullets", dmg = 12, pellets = 1, spread = 0.02, cool = 0.1, sound = "block.note_block.snare", pitch = 0.9, from = 3, tip = 0.5},
  {name = "Ракетница", tex = "gun_rocket", ammo = "rockets", proj = "rocket", speed = 11, dmg = 70, splash = 2.4, cool = 0.85, sound = "entity.generic.explode", pitch = 1.3, from = 4, tip = 0.5},
  {name = "Плазмаган", tex = "gun_plasma", ammo = "cells", proj = "plasma", speed = 17, dmg = 22, splash = 0, cool = 0.12, sound = "block.note_block.chime", pitch = 1.8, from = 6, tip = 0.5},
  {name = "BFG 9000", tex = "gun_bfg", ammo = "cells", cost = 40, proj = "bfgball", speed = 9, dmg = 260, splash = 4.5, cool = 2.2, sound = "entity.generic.explode", pitch = 0.5, from = 9, tip = 0.5},
}
local AMMO_MAX = {bullets = 300, shells = 60, rockets = 30, cells = 400}
local ITEMS = {
  h = {tex = "medkit"}, r = {tex = "armor"}, a = {tex = "bullets"}, s = {tex = "shells"}, k = {tex = "rockets"}, p = {tex = "cells"},
}

local WALL_H, EYE, RADIUS = 1.3, 0.62, 0.25
local tex, meshes, crateMesh = {}, nil, nil
local state = "title"                  -- title | play | level | dead
local level, runSeed = 1, 0
local grid, W, H, exits, theme
local player, monsters, items, balls, shots, booms
local cooldown, flashT, hurtT, walkT, lavaT, msg, msgT = 0, 0, 0, 0, 0, "", 0
local kills, total, best, showMap = 0, 0, 0, false

-- --- Карта ---

local function cell(cx, cz)
  if cx < 0 or cz < 0 or cx >= W or cz >= H then return "#" end
  return grid[cz + 1][cx + 1]
end

local function setCell(cx, cz, ch) grid[cz + 1][cx + 1] = ch end

local function solid(cx, cz)
  local ch = cell(cx, cz)
  return ch == "#" or ch == "E" or ch == "X"
end

local function blocked(x, z, r)
  local f = math.floor
  return solid(f(x - r), f(z - r)) or solid(f(x + r), f(z - r)) or solid(f(x - r), f(z + r)) or solid(f(x + r), f(z + r))
end

local function move(e, dx, dz, r)
  if not blocked(e.x + dx, e.z, r) then e.x = e.x + dx end
  if not blocked(e.x, e.z + dz, r) then e.z = e.z + dz end
end

local function sight(x1, z1, x2, z2)
  local dx, dz = x2 - x1, z2 - z1
  local steps = math.ceil(math.sqrt(dx * dx + dz * dz) / 0.25)
  for k = 1, steps - 1 do
    local t = k / steps
    if solid(math.floor(x1 + dx * t), math.floor(z1 + dz * t)) then return false end
  end
  return true
end

-- --- Генератор уровня: комнаты, коридоры, препятствия, выход в самой дальней комнате ---

local function rnd(a, b) return math.random(a, b) end

local function bfs(sx, sz)
  local dist, queue, head = {}, {{sx, sz}}, 1
  dist[sz * W + sx] = 0
  while head <= #queue do
    local x, z = queue[head][1], queue[head][2]
    head = head + 1
    local d = dist[z * W + x]
    for _, o in ipairs({{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) do
      local nx, nz = x + o[1], z + o[2]
      if not solid(nx, nz) and dist[nz * W + nx] == nil then
        dist[nz * W + nx] = d + 1
        queue[#queue + 1] = {nx, nz}
      end
    end
  end
  return dist
end

local function carveCorridor(x1, z1, x2, z2, wide)
  local x, z = x1, z1
  local horizontalFirst = math.random() < 0.5
  local function dig(cx, cz)
    setCell(cx, cz, ".")
    if wide and cx + 1 < W - 1 and cz + 1 < H - 1 then setCell(cx + 1, cz + 1, ".") end
  end
  while x ~= x2 or z ~= z2 do
    dig(x, z)
    if (horizontalFirst and x ~= x2) or z == z2 then
      x = x + (x2 > x and 1 or -1)
    else
      z = z + (z2 > z and 1 or -1)
    end
  end
  dig(x, z)
end

local function generate(n, seed)
  math.randomseed(seed)
  W = math.min(24 + n * 2, 48)
  H = math.min(20 + n * 2, 42)
  grid = {}
  for z = 1, H do
    grid[z] = {}
    for x = 1, W do grid[z][x] = "#" end
  end
  local rooms = {}
  local want = math.min(6 + n, 16)
  for _ = 1, 200 do
    if #rooms >= want then break end
    local rw, rh = rnd(4, 9), rnd(4, 8)
    local rx, rz = rnd(1, W - rw - 2), rnd(1, H - rh - 2)
    local ok = true
    for _, r in ipairs(rooms) do
      if rx <= r.x + r.w + 1 and rx + rw + 1 >= r.x and rz <= r.z + r.h + 1 and rz + rh + 1 >= r.z then ok = false break end
    end
    if ok then
      rooms[#rooms + 1] = {x = rx, z = rz, w = rw, h = rh, cx = rx + math.floor(rw / 2), cz = rz + math.floor(rh / 2)}
      for z = rz, rz + rh - 1 do for x = rx, rx + rw - 1 do setCell(x, z, ".") end end
    end
  end
  table.sort(rooms, function(a, b) return a.cx + a.cz < b.cx + b.cz end)
  for i = 2, #rooms do
    carveCorridor(rooms[i - 1].cx, rooms[i - 1].cz, rooms[i].cx, rooms[i].cz, math.random() < 0.35)
  end
  for _ = 1, math.floor(#rooms / 3) do -- лишние проходы — чтобы были круги
    local a, b = rooms[rnd(1, #rooms)], rooms[rnd(1, #rooms)]
    if a ~= b then carveCorridor(a.cx, a.cz, b.cx, b.cz, false) end
  end
  local start = rooms[1]
  -- Колонны и ящики в больших комнатах (не в первой).
  for i = 2, #rooms do
    local r = rooms[i]
    if r.w >= 6 and r.h >= 6 then
      local kind = math.random() < 0.5 and "#" or "X"
      for _ = 1, rnd(1, 3) do
        local x, z = rnd(r.x + 2, r.x + r.w - 3), rnd(r.z + 2, r.z + r.h - 3)
        if x ~= r.cx or z ~= r.cz then setCell(x, z, kind) end
      end
    end
  end
  -- Выход: в самой дальней (по пути) комнате, на её стене.
  local dist = bfs(start.cx, start.cz)
  local far, farD = rooms[#rooms], -1
  for _, r in ipairs(rooms) do
    local d = dist[r.cz * W + r.cx]
    if d and d > farD then far, farD = r, d end
  end
  local placed = false
  for _ = 1, 40 do
    local side = rnd(1, 4)
    local x, z
    if side == 1 then x, z = rnd(far.x, far.x + far.w - 1), far.z - 1
    elseif side == 2 then x, z = rnd(far.x, far.x + far.w - 1), far.z + far.h
    elseif side == 3 then x, z = far.x - 1, rnd(far.z, far.z + far.h - 1)
    else x, z = far.x + far.w, rnd(far.z, far.z + far.h - 1) end
    if cell(x, z) == "#" and x > 0 and z > 0 and x < W - 1 and z < H - 1 then
      setCell(x, z, "E")
      placed = true
      break
    end
  end
  -- Лава (в некоторых локациях): лужи в комнатах, не у выхода и не в первой комнате.
  local th = THEMES[(n - 1) % #THEMES + 1]
  if th.lava then
    for i = 2, #rooms - 1 do
      local r = rooms[i]
      if math.random() < th.lava and r.w >= 5 and r.h >= 5 then
        local lw, lh = rnd(1, r.w - 4), rnd(1, r.h - 4)
        local lx, lz = rnd(r.x + 1, r.x + r.w - lw - 1), rnd(r.z + 1, r.z + r.h - lh - 1)
        for z = lz, lz + lh - 1 do for x = lx, lx + lw - 1 do
          if cell(x, z) == "." then setCell(x, z, "L") end
        end end
      end
    end
  end
  -- Проверка: до выхода можно дойти (иначе убрать препятствия).
  dist = bfs(start.cx, start.cz)
  local reachable = false
  for z = 0, H - 1 do for x = 0, W - 1 do
    if cell(x, z) == "E" then
      for _, o in ipairs({{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) do
        if dist[(z + o[2]) * W + x + o[1]] then reachable = true end
      end
    end
  end end
  if not placed or not reachable then
    return generate(n, seed + 1)
  end
  return rooms, start, th
end

-- --- Модели уровня ---

local function quad(m, x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, u, v)
  local n = #m.v / 5
  local vs = {x1, y1, z1, 0, 0, x2, y2, z2, u, 0, x3, y3, z3, u, v, x4, y4, z4, 0, v}
  for _, val in ipairs(vs) do m.v[#m.v + 1] = val end
  for _, k in ipairs({1, 2, 3, 1, 3, 4}) do m.i[#m.i + 1] = n + k end
end

local function buildMeshes()
  if meshes then
    for _, id in pairs(meshes) do g3d.free(id) end
  end
  local walls, exitM, lava = {v = {}, i = {}}, {v = {}, i = {}}, {v = {}, i = {}}
  for cz = 0, H - 1 do
    for cx = 0, W - 1 do
      local ch = cell(cx, cz)
      if ch == "#" or ch == "E" then
        local m = ch == "E" and exitM or walls
        local function open(x, z) return not solid(x, z) or cell(x, z) == "X" end
        if open(cx, cz - 1) then quad(m, cx, WALL_H, cz, cx + 1, WALL_H, cz, cx + 1, 0, cz, cx, 0, cz, 1, 1) end
        if open(cx, cz + 1) then quad(m, cx + 1, WALL_H, cz + 1, cx, WALL_H, cz + 1, cx, 0, cz + 1, cx + 1, 0, cz + 1, 1, 1) end
        if open(cx - 1, cz) then quad(m, cx, WALL_H, cz + 1, cx, WALL_H, cz, cx, 0, cz, cx, 0, cz + 1, 1, 1) end
        if open(cx + 1, cz) then quad(m, cx + 1, WALL_H, cz, cx + 1, WALL_H, cz + 1, cx + 1, 0, cz + 1, cx + 1, 0, cz, 1, 1) end
      elseif ch == "L" then
        quad(lava, cx, 0.02, cz, cx + 1, 0.02, cz, cx + 1, 0.02, cz + 1, cx, 0.02, cz + 1, 1, 1)
      end
    end
  end
  meshes = {
    walls = g3d.mesh({vertices = walls.v, indices = walls.i}),
    floor = g3d.plane(W, H, 1),
  }
  if #exitM.v > 0 then meshes.exit = g3d.mesh({vertices = exitM.v, indices = exitM.i}) end
  if #lava.v > 0 then meshes.lava = g3d.mesh({vertices = lava.v, indices = lava.i}) end
end

-- --- Наполнение уровня ---

local function pickMonster(n)
  local pool, sum = {}, 0
  for key, m in pairs(MONSTERS) do
    if n >= m.from then
      local w = m.weight + (key == "z" and -math.min(4, math.floor(n / 3)) or 0)
      if w > 0 then pool[#pool + 1] = {key, w} sum = sum + w end
    end
  end
  local r = math.random() * sum
  for _, p in ipairs(pool) do
    r = r - p[2]
    if r <= 0 then return p[1] end
  end
  return pool[1][1]
end

local function freeSpot(r)
  for _ = 1, 20 do
    local x, z = rnd(r.x, r.x + r.w - 1), rnd(r.z, r.z + r.h - 1)
    if cell(x, z) == "." then return x + 0.5, z + 0.5 end
  end
  return r.cx + 0.5, r.cz + 0.5
end

local function newPlayer()
  return {health = 100, armor = 0, weapon = 1, has = {true}, ammo = {bullets = 60, shells = 0, rockets = 0, cells = 0}}
end

-- Снаряжение, как будто игрок прошёл предыдущие уровни (для «Продолжить»).
local function loadoutFor(n)
  local p = newPlayer()
  for i, w in ipairs(WEAPONS) do
    if n > w.from then p.has[i] = true p.weapon = i end
  end
  p.ammo = {bullets = 120, shells = 20, rockets = n > 4 and 8 or 0, cells = n > 6 and 100 or 0}
  p.armor = math.min(100, (n - 1) * 15)
  return p
end

local function startLevel(n, keep)
  level = n
  local rooms, start
  rooms, start, theme = generate(n, runSeed + n * 7919)
  math.randomseed(runSeed + n * 31 + 7)
  monsters, items, balls, shots, booms, exits = {}, {}, {}, {}, {}, {}
  for z = 0, H - 1 do for x = 0, W - 1 do
    if cell(x, z) == "E" then exits[#exits + 1] = {x = x + 0.5, z = z + 0.5} end
  end end
  if not keep then player = player or newPlayer() end
  player.x, player.z, player.yaw, player.pitch = start.cx + 0.5, start.cz + 0.5, 90, 0
  local count = math.min(5 + n * 3, 48)
  for k = 1, count do
    local r = rooms[rnd(2, #rooms)]
    local x, z = freeSpot(r)
    local key = pickMonster(n)
    local t = MONSTERS[key]
    local hp = math.floor(t.hp * (1 + (n - 1) * 0.06))
    monsters[k] = {t = t, x = x, z = z, hp = hp, cool = math.random() * 2, hurt = 0}
  end
  for i = 2, #rooms do
    local r = rooms[i]
    for _ = 1, rnd(0, 2) do
      local roll = math.random()
      local kind = roll < 0.3 and "h" or roll < 0.42 and "r" or roll < 0.65 and "a" or roll < 0.8 and "s"
          or roll < 0.9 and "k" or "p"
      local x, z = freeSpot(r)
      items[#items + 1] = {kind = kind, x = x, z = z}
    end
  end
  -- Новое оружие, если его ещё нет и пора.
  for i, w in ipairs(WEAPONS) do
    if n >= w.from and not player.has[i] then
      local x, z = freeSpot(rooms[rnd(2, #rooms)])
      items[#items + 1] = {kind = "w" .. i, x = x, z = z}
    end
  end
  buildMeshes()
  kills, total = 0, #monsters
  msg, msgT = "Уровень " .. n .. ": " .. theme.name, 3
  player.saved = {health = player.health, armor = player.armor, ammo = {}, has = {}, weapon = player.weapon}
  for k, v in pairs(player.ammo) do player.saved.ammo[k] = v end
  for k, v in pairs(player.has) do player.saved.has[k] = v end
end

-- --- Игрок ---

local function say(text) msg, msgT = text, 2.5 end

local function hurtPlayer(dmg, noArmor)
  if state ~= "play" then return end
  local saved = noArmor and 0 or math.min(player.armor, math.floor(dmg / 3))
  player.armor = player.armor - saved
  player.health = player.health - (dmg - saved)
  hurtT = 0.35
  sound.play("entity.arrow.hit_player", 0.6, 0.8)
  if player.health <= 0 then
    player.health = 0
    state = "dead"
    input.lockMouse(false)
    sound.play("entity.villager.death", 1, 0.6)
  end
end

local function giveAmmo(kind, amount)
  player.ammo[kind] = math.min(AMMO_MAX[kind], player.ammo[kind] + amount)
end

local function pickup(it)
  local p = player
  local k = it.kind
  if k == "h" then
    if p.health >= 100 then return false end
    p.health = math.min(100, p.health + 25) say("Аптечка +25")
  elseif k == "r" then
    if p.armor >= 100 then return false end
    p.armor = math.min(100, p.armor + 50) say("Броня +50")
  elseif k == "a" then giveAmmo("bullets", 25) say("Патроны +25")
  elseif k == "s" then giveAmmo("shells", 8) say("Дробь +8")
  elseif k == "k" then giveAmmo("rockets", 4) say("Ракеты +4")
  elseif k == "p" then giveAmmo("cells", 40) say("Батареи +40")
  else
    local i = tonumber(k:sub(2))
    local w = WEAPONS[i]
    p.has[i] = true
    p.weapon = i
    giveAmmo(w.ammo, w.ammo == "cells" and 80 or w.ammo == "rockets" and 6 or 20)
    say(w.name .. "!")
  end
  sound.play("entity.item.pickup", 0.8, 1.2)
  return true
end

local function switchWeapon(dir)
  local i = player.weapon
  for _ = 1, #WEAPONS do
    i = (i - 1 + dir) % #WEAPONS + 1
    if player.has[i] then player.weapon = i return end
  end
end

local function updatePlayer(dt)
  local dx, dy = input.mouseDelta()
  player.yaw = player.yaw + dx * 0.22
  player.pitch = math.max(-35, math.min(35, player.pitch - dy * 0.22))
  if input.down("left") then player.yaw = player.yaw - 120 * dt end
  if input.down("right") then player.yaw = player.yaw + 120 * dt end
  local fx, _, fz = g3d.forward(player.yaw, 0)
  local rx, _, rz = g3d.right(player.yaw)
  local mx, mz = 0, 0
  if input.down("w") or input.down("up") then mx, mz = mx + fx, mz + fz end
  if input.down("s") or input.down("down") then mx, mz = mx - fx, mz - fz end
  if input.down("d") then mx, mz = mx + rx, mz + rz end
  if input.down("a") then mx, mz = mx - rx, mz - rz end
  local len = math.sqrt(mx * mx + mz * mz)
  if len > 0 then
    local speed = (input.down("shift") and 4.3 or 3.0) * dt / len
    move(player, mx * speed, mz * speed, RADIUS)
    walkT = walkT + dt
  end
  cooldown = math.max(0, cooldown - dt)
  flashT = math.max(0, flashT - dt)
  hurtT = math.max(0, hurtT - dt)
  msgT = math.max(0, msgT - dt)
end

local function damageMonster(m, dmg)
  if m.dead then return end
  m.hp = m.hp - dmg
  m.hurt, m.awake = 0.15, true
  if m.hp <= 0 then
    m.dead = true
    kills = kills + 1
    sound.play("entity.villager.death", 0.7, 0.5)
  else
    sound.play("entity.villager.hurt", 0.4, 0.6)
  end
end

local function explode(x, z, w)
  booms[#booms + 1] = {x = x, z = z, t = 0.35, size = w.splash > 3 and 2.2 or 1.2}
  sound.play("entity.generic.explode", 0.7, w.splash > 3 and 0.6 or 1.1)
  for _, m in ipairs(monsters) do
    local dx, dz = m.x - x, m.z - z
    local d = math.sqrt(dx * dx + dz * dz)
    if d < w.splash then damageMonster(m, w.dmg * (1 - d / w.splash)) end
  end
  local dx, dz = player.x - x, player.z - z
  local d = math.sqrt(dx * dx + dz * dz)
  if d < w.splash * 0.7 then hurtPlayer(math.floor(w.dmg * 0.4 * (1 - d / w.splash))) end
end

local function fire(sw, sh)
  local w = WEAPONS[player.weapon]
  if cooldown > 0 then return end
  local cost = w.cost or 1
  if player.ammo[w.ammo] < cost then
    say("Нет патронов!")
    cooldown = 0.4
    switchWeapon(-1)
    return
  end
  player.ammo[w.ammo] = player.ammo[w.ammo] - cost
  cooldown, flashT = w.cool, 0.06
  sound.play(w.sound, 0.5, w.pitch)
  if w.proj then
    local fx, fy, fz = g3d.forward(player.yaw, player.pitch)
    shots[#shots + 1] = {x = player.x + fx * 0.4, z = player.z + fz * 0.4, y = EYE - 0.1 + fy * 0.4, vx = fx * w.speed,
      vz = fz * w.speed, vy = fy * w.speed, w = w}
    return
  end
  for _ = 1, w.pellets do
    local ox = (math.random() * 2 - 1) * w.spread * sw
    local oy = (math.random() * 2 - 1) * w.spread * sh * 0.5
    local id, dist = g3d.pick(sw / 2 + ox, sh / 2 + oy)
    local m = monsters[id]
    if m and dist < 40 then damageMonster(m, w.dmg) end
  end
end

-- --- Мир (20 раз в секунду) ---

function onTick()
  if state ~= "play" or not input.mouseLocked() then return end
  local dt = 0.05
  for _, m in ipairs(monsters) do
    m.hurt = math.max(0, m.hurt - dt)
    if not m.dead then
      local dx, dz = player.x - m.x, player.z - m.z
      local dist = math.sqrt(dx * dx + dz * dz)
      local see = dist < 22 and sight(m.x, m.z, player.x, player.z)
      if see then m.awake = true end
      if m.awake then
        local t = m.t
        local keep = t.melee and 0.75 or (t.hitscan and 4 or 2.5)
        if dist > keep then move(m, dx / dist * t.speed * dt, dz / dist * t.speed * dt, 0.3) end
        m.cool = m.cool - dt
        if m.cool <= 0 then
          if t.melee and dist < 1.05 then
            hurtPlayer(t.dmg)
            m.cool = t.cool
          elseif t.hitscan and see and dist < t.range then
            if math.random() < 0.75 - dist * 0.035 then hurtPlayer(t.dmg) end
            sound.play("block.note_block.snare", 0.4, 0.7)
            m.cool = t.cool * (0.7 + math.random() * 0.6)
          elseif t.ball and see and dist < t.range then
            balls[#balls + 1] = {x = m.x, z = m.z, y = t.y, vx = dx / dist * 6.5, vz = dz / dist * 6.5, dmg = t.dmg, tex = t.ball}
            sound.play("block.note_block.bass", 0.5, 0.5)
            m.cool = t.cool * (0.7 + math.random() * 0.6)
          end
        end
      end
    end
  end
  for k = #balls, 1, -1 do
    local b = balls[k]
    b.x, b.z = b.x + b.vx * dt, b.z + b.vz * dt
    local dx, dz = player.x - b.x, player.z - b.z
    if dx * dx + dz * dz < 0.2 then
      hurtPlayer(b.dmg)
      table.remove(balls, k)
    elseif solid(math.floor(b.x), math.floor(b.z)) then
      table.remove(balls, k)
    end
  end
  for k = #shots, 1, -1 do
    local s = shots[k]
    local hit = false
    for _ = 1, 2 do -- два шага за тик, чтобы быстрые снаряды не пролетали насквозь
      s.x, s.z, s.y = s.x + s.vx * dt / 2, s.z + s.vz * dt / 2, s.y + s.vy * dt / 2
      if solid(math.floor(s.x), math.floor(s.z)) or s.y < 0 or s.y > WALL_H then hit = true break end
      for _, m in ipairs(monsters) do
        if not m.dead then
          local dx, dz = m.x - s.x, m.z - s.z
          if dx * dx + dz * dz < 0.25 and math.abs(s.y - m.t.y) < m.t.h * 0.6 then
            hit = true
            if s.w.splash == 0 then damageMonster(m, s.w.dmg) end
            break
          end
        end
      end
      if hit then break end
    end
    if hit then
      if s.w.splash > 0 then explode(s.x, s.z, s.w) end
      table.remove(shots, k)
    end
  end
  for k = #booms, 1, -1 do
    booms[k].t = booms[k].t - dt
    if booms[k].t <= 0 then table.remove(booms, k) end
  end
  for k = #items, 1, -1 do
    local it = items[k]
    local dx, dz = player.x - it.x, player.z - it.z
    if dx * dx + dz * dz < 0.36 and pickup(it) then table.remove(items, k) end
  end
  if cell(math.floor(player.x), math.floor(player.z)) == "L" then
    lavaT = lavaT + dt
    if lavaT >= 0.5 then lavaT = 0 hurtPlayer(6, true) end
  end
  for _, e in ipairs(exits) do
    local dx, dz = player.x - e.x, player.z - e.z
    if dx * dx + dz * dz < 1.0 then
      sound.play("entity.player.levelup", 1, 1)
      input.lockMouse(false)
      state = "level"
      best = math.max(best, level)
      storage.set("best", best)
      return
    end
  end
end

-- --- Экран ---

function onStart()
  screen.captureKeys(true)
  local names = {"exit", "crate", "lava", "corpse", "fireball", "greenball", "plasma", "bfgball", "rocket", "explosion",
    "medkit", "armor", "bullets", "shells", "rockets", "cells"}
  for _, t in ipairs(THEMES) do names[#names + 1] = t.wall names[#names + 1] = t.floor names[#names + 1] = t.ceil end
  for _, m in pairs(MONSTERS) do names[#names + 1] = m.tex end
  for i = 1, #WEAPONS do names[#names + 1] = "pickup_" .. WEAPONS[i].tex:sub(5) end
  for _, name in ipairs(names) do
    if not tex[name] then tex[name] = g3d.texture("textures/" .. name .. ".png") end
  end
  crateMesh = g3d.box(1, 0.9, 1)
  best = storage.get("best", 0)
end

function onKey(key)
  local n = tonumber(key)
  if n and WEAPONS[n] and player and player.has[n] then player.weapon = n end
  if key == "m" then showMap = not showMap end
  return true
end

function onScroll(amount)
  if player and state == "play" then switchWeapon(amount > 0 and -1 or 1) end
end

local function drawWorld(sw, sh)
  local bob = math.sin(walkT * 10) * 0.025
  g3d.camera(player.x, EYE + bob, player.z, player.yaw, player.pitch, 78)
  g3d.fog(theme.fog, 3, 14)
  g3d.light(-0.5, -1, -0.3, 0.5)
  g3d.begin(0, 0, sw, sh, theme.fog)
  g3d.draw(meshes.floor, W / 2, 0, H / 2, {texture = tex[theme.floor], lit = false})
  g3d.draw(meshes.floor, W / 2, WALL_H, H / 2, {texture = tex[theme.ceil], lit = false})
  g3d.draw(meshes.walls, 0, 0, 0, {texture = tex[theme.wall]})
  if meshes.exit then g3d.draw(meshes.exit, 0, 0, 0, {texture = tex.exit}) end
  if meshes.lava then g3d.draw(meshes.lava, 0, 0, 0, {texture = tex.lava, lit = false}) end
  for cz = 0, H - 1 do for cx = 0, W - 1 do
    if cell(cx, cz) == "X" then
      local dx, dz = cx + 0.5 - player.x, cz + 0.5 - player.z
      if dx * dx + dz * dz < 200 then g3d.draw(crateMesh, cx + 0.5, 0.45, cz + 0.5, {texture = tex.crate}) end
    end
  end end
  for _, it in ipairs(items) do
    local t = ITEMS[it.kind] and ITEMS[it.kind].tex or ("pickup_" .. WEAPONS[tonumber(it.kind:sub(2))].tex:sub(5))
    local wide = it.kind:sub(1, 1) == "w"
    g3d.sprite(tex[t], it.x, wide and 0.22 or 0.18, it.z, wide and 0.6 or 0.42, wide and 0.44 or 0.36)
  end
  for i, m in ipairs(monsters) do
    if m.dead then
      g3d.sprite(tex.corpse, m.x, 0.2, m.z, 0.9, 0.45)
    else
      g3d.sprite(tex[m.t.tex], m.x, m.t.y, m.z, m.t.w, m.t.h, {id = i, tint = m.hurt > 0 and "#FF7070" or nil})
    end
  end
  for _, b in ipairs(balls) do g3d.sprite(tex[b.tex], b.x, b.y, b.z, 0.35, 0.35) end
  for _, s in ipairs(shots) do
    local size = s.w.proj == "bfgball" and 0.7 or 0.25
    g3d.sprite(tex[s.w.proj], s.x, s.y, s.z, size, size)
  end
  for _, b in ipairs(booms) do g3d.sprite(tex.explosion, b.x, 0.5, b.z, b.size, b.size) end
  g3d.finish()
end

local function drawMap(sw, sh)
  local cellSize = math.max(1, math.floor(math.min((sw - 20) / W, (sh - 30) / H)))
  local ox, oy = math.floor((sw - W * cellSize) / 2), math.floor((sh - H * cellSize) / 2)
  gfx.rect(ox - 2, oy - 2, W * cellSize + 4, H * cellSize + 4, "#C0000000")
  for z = 0, H - 1 do for x = 0, W - 1 do
    local ch = cell(x, z)
    local c = ch == "#" and "#FF6A6A6A" or ch == "E" and "#FF40FF60" or ch == "X" and "#FF8A6030" or ch == "L" and "#FFE05010" or nil
    if c then gfx.rect(ox + x * cellSize, oy + z * cellSize, cellSize, cellSize, c) end
  end end
  gfx.rect(ox + player.x * cellSize - 1, oy + player.z * cellSize - 1, 3, 3, "#FFFFFF40")
end

local function drawHud(sw, sh)
  local w = WEAPONS[player.weapon]
  local k = sh / 160
  local gw, gh = 128 * k * 0.8, 80 * k * 0.8
  local gx = sw / 2 - gw / 2 + math.sin(walkT * 5) * 3 * k
  local gy = sh - gh - 12 + math.abs(math.cos(walkT * 5)) * 3 * k + (cooldown > w.cool * 0.6 and 3 * k or 0)
  if flashT > 0 then
    local fs = 14 * k -- маленькая вспышка у дула
    gfx.image("textures/gun_flash.png", sw / 2 - fs / 2, gy - fs * 0.6, fs, fs)
  end
  gfx.image("textures/" .. w.tex .. ".png", gx, gy, gw, gh)
  gfx.rect(sw / 2 - 4, sh / 2, 3, 1, "#C0FFFFFF")
  gfx.rect(sw / 2 + 2, sh / 2, 3, 1, "#C0FFFFFF")
  gfx.rect(sw / 2, sh / 2 - 4, 1, 3, "#C0FFFFFF")
  gfx.rect(sw / 2, sh / 2 + 2, 1, 3, "#C0FFFFFF")
  if hurtT > 0 then gfx.rect(0, 0, sw, sh, string.format("#%02XFF0000", math.floor(hurtT * 280))) end
  -- Нижняя панель, как в DOOM.
  gfx.rect(0, sh - 13, sw, 13, "#C0201010")
  gfx.rect(0, sh - 13, sw, 1, "#FF6A3A2A")
  local hc = player.health > 30 and "#FFF0F0F0" or "#FFFF5050"
  gfx.text("ЗДОР " .. player.health .. "%", 4, sh - 10, hc, 0.7, true)
  gfx.text("БРОНЯ " .. player.armor .. "%", 68, sh - 10, "#FF80E080", 0.7, true)
  local slots = ""
  for i = 1, #WEAPONS do slots = slots .. (player.has[i] and (i == player.weapon and "[" .. i .. "]" or i) or "·") .. " " end
  gfx.text(slots, sw / 2 + 8, sh - 10, "#FFFFD060", 0.6, true)
  local ammo = w.name .. ": " .. player.ammo[w.ammo]
  gfx.text(ammo, sw - 4 - gfx.textWidth(ammo, 0.7), sh - 10, "#FFFFD060", 0.7, true)
  gfx.text("Уровень " .. level .. " · " .. theme.name .. " · убито " .. kills .. "/" .. total, 4, 3, "#FFE0E0E0", 0.6, true)
  if msgT > 0 then gfx.textCenter(msg, sw / 2, 18, "#FFFFE080", 0.9, true) end
  if showMap then drawMap(sw, sh) end
end

local function menu(sw, sh, title, subtitle, buttons)
  gfx.rect(0, 0, sw, sh, "#C0000000")
  gfx.textCenter(title, sw / 2, sh / 2 - 50, "#FFD02020", 2.6, true)
  if subtitle then gfx.textCenter(subtitle, sw / 2, sh / 2 - 20, "#FFE0E0E0", 0.75, true) end
  local y = sh / 2 - 4
  for _, b in ipairs(buttons) do
    if ui.button(sw / 2 - 60, y, 120, 16, b[1], b[3] or "primary") then b[2]() end
    y = y + 20
  end
end

local function play(n, fresh)
  if fresh then
    runSeed = math.floor(time.now() % 1000000)
    player = n == 1 and newPlayer() or loadoutFor(n)
  end
  startLevel(n, true)
  state = "play"
  input.lockMouse(true)
end

local function retry()
  local s = player.saved
  player.health, player.armor, player.weapon = math.max(s.health, 50), s.armor, s.weapon
  player.ammo, player.has = {}, {}
  for k, v in pairs(s.ammo) do player.ammo[k] = v end
  for k, v in pairs(s.has) do player.has[k] = v end
  startLevel(level, true)
  state = "play"
  input.lockMouse(true)
end

function onDraw(dt)
  local sw, sh = screen.width(), screen.height()
  if state == "title" then
    gfx.gradient(0, 0, sw, sh, "#3A0A06", "#0A0202")
    local buttons = {{"Новая игра", function() play(1, true) end}}
    if best > 0 then buttons[2] = {"Продолжить: уровень " .. (best + 1), function() play(best + 1, true) end, "normal"} end
    buttons[#buttons + 1] = {"Управление", function() say("Мышь — обзор, WASD — ходьба, ЛКМ — огонь, 1–6/колесо — оружие, M — карта") msgT = 6 end, "normal"}
    menu(sw, sh, "DOOM", "BlockPhone edition · рекорд: уровень " .. best, buttons)
    if msgT > 0 then gfx.textCenter(msg, sw / 2, sh - 14, "#FFE0E0E0", 0.55, true) msgT = msgT - dt end
    return
  end
  local playing = state == "play" and input.mouseLocked()
  if playing then updatePlayer(dt) end
  drawWorld(sw, sh)
  if playing and input.down("mouse1") then fire(sw, sh) end
  drawHud(sw, sh)
  if state == "play" and not input.mouseLocked() then
    menu(sw, sh, "Пауза", "Уровень " .. level .. ": " .. theme.name, {
      {"Продолжить", function() input.lockMouse(true) end},
      {"В меню", function() state = "title" end, "normal"},
    })
  elseif state == "dead" then
    menu(sw, sh, "Вы погибли", "Уровень " .. level .. " · рекорд: " .. best, {
      {"Заново с уровня " .. level, retry},
      {"В меню", function() state = "title" end, "normal"},
    })
  elseif state == "level" then
    menu(sw, sh, "Уровень " .. level .. " пройден", "Убито " .. kills .. " из " .. total, {
      {"Дальше: уровень " .. (level + 1), function()
        startLevel(level + 1, true)
        state = "play"
        input.lockMouse(true)
      end},
      {"В меню", function() state = "title" end, "normal"},
    })
  end
end
