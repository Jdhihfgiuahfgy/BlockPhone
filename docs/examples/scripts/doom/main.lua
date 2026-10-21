-- DOOM для BlockPhone: 3D-шутер на g3d. Три уровня, три монстра, три оружия, аптечки, патроны, броня.
-- Управление: мышь — обзор (нажмите на экран, чтобы захватить мышь; Esc — пауза), WASD/стрелки — ходьба,
-- ЛКМ — стрелять, 1/2/3 — оружие. Дойдите до двери EXIT, чтобы пройти уровень.
-- Карта уровня — строки: # стена, E выход, P старт, i бес, d демон, c какодемон,
-- h аптечка, a патроны, b дробь, r броня, s дробовик, g пулемёт.

local LEVELS = {
  {name = "E1M1: Ангар", wall = "wall_brick", fog = "#120806", map = {
    "################",
    "#P...#.........#",
    "#....#...i..h..#",
    "#.a..#.........#",
    "#....###.#######",
    "#..............#",
    "###.####...i...#",
    "#.....#........#",
    "#..i..#..###.b.#",
    "#.....#..#d....#",
    "#.s...d..#.....#",
    "#........#..a..E",
    "################"}},
  {name = "E1M2: Лаборатория", wall = "wall_tech", fog = "#04080C", map = {
    "####################",
    "#P.....#......c....#",
    "#......#...........#",
    "#..b...#..i....i...#",
    "#......####.####...#",
    "#.........d........#",
    "#####.#######.######",
    "#.....#.....#......#",
    "#..i..#..h..#..g...#",
    "#.....#.....#......#",
    "#..d......a.....i..#",
    "#.....#.....#......#",
    "####.###.#######.###",
    "#..r.......c.......#",
    "#..a....i......h...E",
    "####################"}},
  {name = "E1M3: Преисподняя", wall = "wall_stone", fog = "#1A0402", map = {
    "######################",
    "#P.......#...........#",
    "#..a.....#..c.....c..#",
    "#........#...........#",
    "#...g....####.########",
    "#....................#",
    "######.#####.######..#",
    "#......#...#......#..#",
    "#..d...#.h.#..i...#..#",
    "#......#...#......#..#",
    "#..d.......d......c..#",
    "#......#...#......#..#",
    "###.####...####.###..#",
    "#......i.........b...#",
    "#..c....r..d.........#",
    "#......h....a......d.E",
    "######################"}},
}

local MONSTERS = {
  i = {tex = "imp", hp = 40, speed = 1.4, y = 0.55, w = 0.9, h = 1.1, ranged = true, dmg = 10, cool = 2.2, range = 11},
  d = {tex = "demon", hp = 90, speed = 2.6, y = 0.5, w = 1.0, h = 1.0, melee = true, dmg = 14, cool = 1.0},
  c = {tex = "caco", hp = 120, speed = 1.0, y = 0.85, w = 0.95, h = 0.95, ranged = true, dmg = 18, cool = 3.0, range = 13},
}

local WEAPONS = {
  {name = "Пистолет", tex = "gun_pistol", ammo = "bullets", dmg = 15, pellets = 1, spread = 0, cool = 0.38,
   sound = "block.note_block.snare", pitch = 0.6},
  {name = "Дробовик", tex = "gun_shotgun", ammo = "shells", dmg = 11, pellets = 7, spread = 0.08, cool = 0.9,
   sound = "entity.generic.explode", pitch = 1.7},
  {name = "Пулемёт", tex = "gun_chaingun", ammo = "bullets", dmg = 12, pellets = 1, spread = 0.02, cool = 0.1,
   sound = "block.note_block.snare", pitch = 0.9},
}

local WALL_H, EYE, RADIUS = 1.25, 0.62, 0.25
local tex, meshes = {}, {}
local state = "title"       -- title | play | level | dead | win
local levelIndex = 1
local grid, W, H, exits
local player, monsters, items, balls
local cooldown, flashT, hurtT, walkT, msg, msgT = 0, 0, 0, 0, "", 0
local kills, total, best

-- --- Карта ---

local function cell(cx, cz)
  if cx < 0 or cz < 0 or cx >= W or cz >= H then return "#" end
  return grid[cz + 1][cx + 1]
end

local function solid(cx, cz)
  local ch = cell(cx, cz)
  return ch == "#" or ch == "E"
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
  local dist = math.sqrt(dx * dx + dz * dz)
  local steps = math.ceil(dist / 0.2)
  for k = 1, steps - 1 do
    local t = k / steps
    if solid(math.floor(x1 + dx * t), math.floor(z1 + dz * t)) then return false end
  end
  return true
end

-- Стены уровня — одна модель (только грани, видимые из коридоров) и отдельная — для двери EXIT.
local function buildMeshes(index)
  if meshes[index] then return meshes[index] end
  local walls, exitV = {v = {}, i = {}}, {v = {}, i = {}}
  local function quad(m, x1, z1, x2, z2)
    local n = #m.v / 5
    local vs = {x1, WALL_H, z1, 0, 0, x2, WALL_H, z2, 1, 0, x2, 0, z2, 1, 1, x1, 0, z1, 0, 1}
    for _, v in ipairs(vs) do m.v[#m.v + 1] = v end
    for _, k in ipairs({1, 2, 3, 1, 3, 4}) do m.i[#m.i + 1] = n + k end
  end
  for cz = 0, H - 1 do
    for cx = 0, W - 1 do
      local ch = cell(cx, cz)
      if ch == "#" or ch == "E" then
        local m = ch == "E" and exitV or walls
        if not solid(cx, cz - 1) then quad(m, cx, cz, cx + 1, cz) end
        if not solid(cx, cz + 1) then quad(m, cx + 1, cz + 1, cx, cz + 1) end
        if not solid(cx - 1, cz) then quad(m, cx, cz + 1, cx, cz) end
        if not solid(cx + 1, cz) then quad(m, cx + 1, cz, cx + 1, cz + 1) end
      end
    end
  end
  local set = {
    walls = g3d.mesh({vertices = walls.v, indices = walls.i}),
    exit = #exitV.v > 0 and g3d.mesh({vertices = exitV.v, indices = exitV.i}) or nil,
    floor = g3d.plane(W, H, 1),
  }
  meshes[index] = set
  return set
end

local function loadLevel(index, keepPlayer)
  levelIndex = index
  local L = LEVELS[index]
  grid, exits, monsters, items, balls = {}, {}, {}, {}, {}
  H, W = #L.map, #L.map[1]
  local start
  for r, row in ipairs(L.map) do
    grid[r] = {}
    for c = 1, W do
      local ch = row:sub(c, c)
      local x, z = c - 0.5, r - 0.5
      if ch == "P" then start = {x, z} ch = "."
      elseif MONSTERS[ch] then
        monsters[#monsters + 1] = {t = MONSTERS[ch], x = x, z = z, hp = MONSTERS[ch].hp, cool = math.random() * 2, hurt = 0}
        ch = "."
      elseif ch:match("[habrsg]") then
        items[#items + 1] = {kind = ch, x = x, z = z}
        ch = "."
      elseif ch == "E" then
        exits[#exits + 1] = {x = x, z = z}
      end
      grid[r][c] = ch
    end
  end
  if not keepPlayer then
    player = {health = 100, armor = 0, weapon = 1, has = {true, false, false}, ammo = {bullets = 50, shells = 0}}
  end
  player.x, player.z, player.yaw, player.pitch = start[1], start[2], 90, 0
  kills, total = 0, #monsters
  buildMeshes(index)
  msg, msgT = L.name, 3
end

-- --- Игрок ---

local function say(text) msg, msgT = text, 2.5 end

local function hurtPlayer(dmg)
  if state ~= "play" then return end
  local saved = math.min(player.armor, math.floor(dmg / 3))
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

local function pickup(it)
  local p = player
  if it.kind == "h" then
    if p.health >= 100 then return false end
    p.health = math.min(100, p.health + 25) say("Аптечка +25")
  elseif it.kind == "a" then p.ammo.bullets = p.ammo.bullets + 20 say("Патроны +20")
  elseif it.kind == "b" then p.ammo.shells = p.ammo.shells + 8 say("Дробь +8")
  elseif it.kind == "r" then
    if p.armor >= 100 then return false end
    p.armor = math.min(100, p.armor + 50) say("Броня +50")
  elseif it.kind == "s" then
    p.has[2] = true p.ammo.shells = p.ammo.shells + 8 p.weapon = 2 say("Дробовик!")
  elseif it.kind == "g" then
    p.has[3] = true p.ammo.bullets = p.ammo.bullets + 40 p.weapon = 3 say("Пулемёт!")
  end
  sound.play("entity.item.pickup", 0.8, 1.2)
  return true
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
    local speed = (input.down("shift") and 4.2 or 3.0) * dt / len
    move(player, mx * speed, mz * speed, RADIUS)
    walkT = walkT + dt
  end
  cooldown = math.max(0, cooldown - dt)
  flashT = math.max(0, flashT - dt)
  hurtT = math.max(0, hurtT - dt)
  msgT = math.max(0, msgT - dt)
end

-- Выстрел: попадание — что видно в прицеле (g3d.pick по готовому кадру), дробовик — несколько дробин с разбросом.
local function fire(sw, sh)
  local w = WEAPONS[player.weapon]
  if cooldown > 0 then return end
  if player.ammo[w.ammo] <= 0 then
    say("Нет патронов!")
    cooldown = 0.5
    return
  end
  player.ammo[w.ammo] = player.ammo[w.ammo] - 1
  cooldown, flashT = w.cool, 0.08
  sound.play(w.sound, 0.5, w.pitch)
  for _ = 1, w.pellets do
    local ox = (math.random() * 2 - 1) * w.spread * sw
    local oy = (math.random() * 2 - 1) * w.spread * sh * 0.5
    local id, dist = g3d.pick(sw / 2 + ox, sh / 2 + oy)
    local m = monsters[id]
    if m and not m.dead and dist < 30 then
      m.hp = m.hp - w.dmg
      m.hurt, m.awake = 0.15, true
      if m.hp <= 0 then
        m.dead = true
        kills = kills + 1
        sound.play("entity.villager.death", 0.7, 0.5)
      else
        sound.play("entity.villager.hurt", 0.5, 0.6)
      end
    end
  end
end

-- --- Монстры, огненные шары, предметы (20 раз в секунду) ---

function onTick()
  if state ~= "play" or not input.mouseLocked() then return end
  local dt = 0.05
  for _, m in ipairs(monsters) do
    m.hurt = math.max(0, m.hurt - dt)
    if not m.dead then
      local dx, dz = player.x - m.x, player.z - m.z
      local dist = math.sqrt(dx * dx + dz * dz)
      local see = dist < 20 and sight(m.x, m.z, player.x, player.z)
      if see then m.awake = true end
      if m.awake then
        local t = m.t
        local keep = t.melee and 0.75 or 2.5
        if dist > keep then move(m, dx / dist * t.speed * dt, dz / dist * t.speed * dt, 0.3) end
        m.cool = m.cool - dt
        if m.cool <= 0 then
          if t.melee and dist < 1.0 then
            hurtPlayer(t.dmg)
            m.cool = t.cool
          elseif t.ranged and see and dist < t.range then
            balls[#balls + 1] = {x = m.x, z = m.z, y = t.y, vx = dx / dist * 6, vz = dz / dist * 6, dmg = t.dmg}
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
  for k = #items, 1, -1 do
    local it = items[k]
    local dx, dz = player.x - it.x, player.z - it.z
    if dx * dx + dz * dz < 0.36 and pickup(it) then table.remove(items, k) end
  end
  for _, e in ipairs(exits) do
    local dx, dz = player.x - e.x, player.z - e.z
    if dx * dx + dz * dz < 1.0 then
      sound.play("entity.player.levelup", 1, 1)
      input.lockMouse(false)
      state = levelIndex < #LEVELS and "level" or "win"
      if state == "win" then
        best = math.max(best or 0, levelIndex)
        storage.set("best", best)
      end
      return
    end
  end
end

-- --- Экран ---

function onStart()
  screen.captureKeys(true)
  for _, name in ipairs({"wall_brick", "wall_tech", "wall_stone", "exit", "floor", "ceiling", "imp", "demon", "caco",
      "corpse", "fireball", "medkit", "bullets", "shells", "armor", "pickup_shotgun", "pickup_chaingun"}) do
    tex[name] = g3d.texture("textures/" .. name .. ".png")
  end
  best = storage.get("best", 0)
end

function onKey(key)
  local n = tonumber(key)
  if n and WEAPONS[n] and player and player.has[n] then player.weapon = n end
  return true
end

function onMouseUnlock() end

local ITEM_TEX = {h = "medkit", a = "bullets", b = "shells", r = "armor", s = "pickup_shotgun", g = "pickup_chaingun"}

local function drawWorld(sw, sh)
  local L = LEVELS[levelIndex]
  local set = meshes[levelIndex]
  local bob = math.sin(walkT * 10) * 0.025
  g3d.camera(player.x, EYE + bob, player.z, player.yaw, player.pitch, 78)
  g3d.fog(L.fog, 3, 13)
  g3d.light(-0.5, -1, -0.3, 0.5)
  g3d.begin(0, 0, sw, sh, L.fog)
  g3d.draw(set.floor, W / 2, 0, H / 2, {texture = tex.floor, lit = false})
  g3d.draw(set.floor, W / 2, WALL_H, H / 2, {texture = tex.ceiling, lit = false})
  g3d.draw(set.walls, 0, 0, 0, {texture = tex[L.wall]})
  if set.exit then g3d.draw(set.exit, 0, 0, 0, {texture = tex.exit}) end
  for _, it in ipairs(items) do
    g3d.sprite(tex[ITEM_TEX[it.kind]], it.x, 0.18, it.z, 0.42, 0.36)
  end
  for i, m in ipairs(monsters) do
    if m.dead then
      g3d.sprite(tex.corpse, m.x, 0.2, m.z, 0.9, 0.45)
    else
      g3d.sprite(tex[m.t.tex], m.x, m.t.y, m.z, m.t.w, m.t.h, {id = i, tint = m.hurt > 0 and "#FF7070" or nil})
    end
  end
  for _, b in ipairs(balls) do g3d.sprite(tex.fireball, b.x, b.y, b.z, 0.35, 0.35) end
  g3d.finish()
end

local function drawHud(sw, sh)
  local w = WEAPONS[player.weapon]
  local k = sh / 150
  local gx = sw / 2 - 48 * k + math.sin(walkT * 5) * 3 * k
  local gy = sh - 64 * k + math.abs(math.cos(walkT * 5)) * 3 * k + (cooldown > w.cool * 0.6 and 4 * k or 0)
  if flashT > 0 then gfx.image("textures/gun_flash.png", gx, gy - 26 * k, 96 * k, 64 * k) end
  gfx.image("textures/" .. w.tex .. ".png", gx, gy, 96 * k, 64 * k)
  gfx.rect(sw / 2 - 4, sh / 2, 3, 1, "#C0FFFFFF")
  gfx.rect(sw / 2 + 2, sh / 2, 3, 1, "#C0FFFFFF")
  gfx.rect(sw / 2, sh / 2 - 4, 1, 3, "#C0FFFFFF")
  gfx.rect(sw / 2, sh / 2 + 2, 1, 3, "#C0FFFFFF")
  if hurtT > 0 then gfx.rect(0, 0, sw, sh, string.format("#%02XFF0000", math.floor(hurtT * 300))) end
  gfx.rect(0, sh - 13, sw, 13, "#A0000000")
  local hc = player.health > 30 and "#FFE0E0E0" or "#FFFF5050"
  gfx.text("Здоровье " .. player.health, 4, sh - 10, hc, 0.7, true)
  gfx.text("Броня " .. player.armor, 74, sh - 10, "#FF80E080", 0.7, true)
  gfx.text(w.name .. ": " .. player.ammo[w.ammo], sw - 4 - gfx.textWidth(w.name .. ": " .. player.ammo[w.ammo], 0.7), sh - 10,
    "#FFFFD060", 0.7, true)
  gfx.text("Убито " .. kills .. "/" .. total, 4, 3, "#FFE0E0E0", 0.6, true)
  if msgT > 0 then gfx.textCenter(msg, sw / 2, 18, "#FFFFE080", 0.9, true) end
end

local function menu(sw, sh, title, subtitle, buttons)
  gfx.rect(0, 0, sw, sh, "#C0000000")
  gfx.textCenter(title, sw / 2, sh / 2 - 46, "#FFD02020", 2.6, true)
  if subtitle then gfx.textCenter(subtitle, sw / 2, sh / 2 - 16, "#FFE0E0E0", 0.75, true) end
  local y = sh / 2
  for _, b in ipairs(buttons) do
    if ui.button(sw / 2 - 55, y, 110, 16, b[1], b[3] or "primary") then b[2]() end
    y = y + 20
  end
end

local function start(index)
  loadLevel(index, index > 1 and player ~= nil and player.health > 0)
  state = "play"
  input.lockMouse(true)
end

function onDraw(dt)
  local sw, sh = screen.width(), screen.height()
  if state == "title" then
    gfx.gradient(0, 0, sw, sh, "#3A0A06", "#0A0202")
    menu(sw, sh, "DOOM", "BlockPhone edition  ·  пройдено уровней: " .. (best or 0) .. "/" .. #LEVELS, {
      {"Новая игра", function() start(1) end},
      {"Управление", function() say("Мышь — обзор, WASD — ходьба, ЛКМ — огонь, 1-3 — оружие, Shift — бег") msgT = 6 end, "normal"},
    })
    if msgT > 0 then gfx.textCenter(msg, sw / 2, sh - 16, "#FFE0E0E0", 0.6, true) msgT = msgT - dt end
    return
  end
  local playing = state == "play" and input.mouseLocked()
  if playing then updatePlayer(dt) end
  drawWorld(sw, sh)
  if playing and input.down("mouse1") then fire(sw, sh) end
  drawHud(sw, sh)
  if state == "play" and not input.mouseLocked() then
    menu(sw, sh, "Пауза", LEVELS[levelIndex].name, {
      {"Продолжить", function() input.lockMouse(true) end},
      {"В меню", function() state = "title" end, "normal"},
    })
  elseif state == "dead" then
    menu(sw, sh, "Вы погибли", LEVELS[levelIndex].name, {
      {"Заново", function() start(levelIndex) end},
      {"В меню", function() state = "title" end, "normal"},
    })
  elseif state == "level" then
    menu(sw, sh, "Уровень пройден", "Убито " .. kills .. " из " .. total, {
      {"Дальше", function() start(levelIndex + 1) end},
    })
  elseif state == "win" then
    menu(sw, sh, "Победа!", "Все уровни пройдены", {
      {"В меню", function() state = "title" end},
    })
  end
end
