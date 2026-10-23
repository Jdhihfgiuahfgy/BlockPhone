-- BLOCK STRIKE — командный 3D-шутер по мотивам Counter-Strike для BlockPhone.
-- Режим «закладка бомбы»: террористы ставят бомбу на точке A или B, спецназ мешает или обезвреживает.
-- Раунды, фаза покупки, экономика, 13 видов оружия, броня, гранаты, 4 карты, боты на свободных местах.
-- По сети: один игрок создаёт сервер (его телефон — хост, считает игру и ботов), остальные входят через «Найти игру».
--
-- Файлы: data.lua — оружие и правила; maps.lua — карты; sim.lua — игра у хоста; bots.lua — боты;
-- netcode.lua — сеть; render.lua — 3D; hud.lua — интерфейс. Текстуры — textures/, радары — radar/
-- (рисует скрипт tools/gen_strike.py в репозитории BlockPhone).

local D = require("data")
local M = require("maps")
local Sim = require("sim")
local N = require("netcode")
local R = require("render")
local H = require("hud")
local SND = require("sounds")
local VM = require("viewmodel")
-- dev.lua кладёт только сценарий автоснимков (DevShots) — выдаёт оружие для снимков; у игроков его нет.
local hasDev, DEV = pcall(require, "dev")

local floor, min, max, abs, sqrt, random = math.floor, math.min, math.max, math.abs, math.sqrt, math.random
local sin, cos, rad, tan = math.sin, math.cos, math.rad, math.tan

local DEFAULTS = {sens = 1.0, quality = 2, fov = 64, volume = 1.0, cross = 1, map = 1, bots = 5, level = 2, firstTo = 8, team = 0,
  wingman = false}
local QUALITY = {0.45, 0.65, 0.85, 1.0}
local QUALITY_NAME = {"Низкое", "Среднее", "Высокое", "Максимум"}
local CROSS = {"#FF4CFF6A", "#FFFFFFFF", "#FF00E5FF", "#FFFFE040", "#FFFF4FD8"}
local CROSS_NAME = {"Зелёный", "Белый", "Голубой", "Жёлтый", "Розовый"}
local ROUNDS = {3, 5, 8, 9, 10, 13}

local G = {state = "title", set = {}, lobbies = {}, feed = {}, flash = 0, hurtT = 0, smoke = 0, hitT = 0, hitHead = false,
  moneyDelta = 0, moneyT = 0, now = 0, lastHp = 100, lastMoney = 0, beepT = 0, invitePlayers = nil}

-- --- Настройки ---

local function loadSettings()
  local s = storage.get("settings", {})
  for k, v in pairs(DEFAULTS) do
    if type(s[k]) ~= type(v) then s[k] = v end
  end
  G.set = s
  G.set.cross = G.set.cross or 1
end

local function saveSettings() storage.set("settings", G.set) end

-- --- Звук ---

-- Свои звуки (sounds/*.wav). Телефон играет не больше 8 одновременно: следим сами — тихие и частые (шаги,
-- попадания в стену) пропускаем, если звучит много, а важный звук вытесняет самый старый.
local LOW = {impact = true, ricochet = true, nade_bounce = true}
local playing = {}

-- pan — панорама −1..1 (слева/справа), muffle — 0..1 «за стеной» (объёмный звук телефона).
local function sfx(name, vol, pan, muffle)
  vol = (vol or 1) * (G.set.volume or 1)
  if vol < 0.03 then return end
  local now = time.clock()
  for i = #playing, 1, -1 do
    if playing[i].t < now then table.remove(playing, i) end
  end
  if #playing >= 7 then
    if LOW[name] then return end
    local victim = 1
    for i, p in ipairs(playing) do
      if LOW[p.name] then victim = i break end
    end
    sound.stop(playing[victim].id)
    table.remove(playing, victim)
  end
  local id
  if pan then
    id = sound.play("sounds/" .. name .. ".wav", min(1, vol), 0.97 + random() * 0.06, pan, muffle or 0)
  else
    id = sound.play("sounds/" .. name .. ".wav", min(1, vol))
  end
  if id and id > 0 then playing[#playing + 1] = {id = id, name = name, t = now + (SND[name] or 1)} end
end

local function camPos()
  local c = G.cam
  if c then return c.x, c.z, c.y, c.yaw end
  return G.me.x, G.me.z, G.me.y + Sim.EYE, G.me.yaw
end

-- Где звук относительно слушателя: панорама (−1 слева … 1 справа), глухость (за стеной, другой этаж, сзади) и
-- множитель громкости. Так по звуку понятно, откуда стреляют и где ходят.
local function placeSound(x, y, z)
  local cx, cz, cy, yaw = camPos()
  local dx, dz = x - cx, z - cz
  local d = sqrt(dx * dx + dz * dz)
  if d < 0.6 then return 0, 0, 1, d end
  local r = rad(yaw or 0)
  local pan = (dx * cos(r) - dz * sin(r)) / d
  local front = (dx * sin(r) + dz * cos(r)) / d
  local muffle, k = 0, 1
  if front < -0.2 then
    muffle, k = 0.18, 0.88 -- сзади — чуть глуше
  end
  local map = G.V and G.V.map
  if map and y and not M.los(map, cx, cy, cz, x, y + 0.6, z) then
    muffle, k = 0.72, k * 0.6 -- за стеной или на другом этаже
  end
  return pan * 0.9, muffle, k, d
end

-- Звук в мире: тише с расстоянием (k² — как на слух), объёмный.
local function sfx3d(name, x, z, vol, range, y)
  local pan, muffle, kk, d = placeSound(x, y, z)
  local k = 1 - d / (range or 40)
  if k > 0 then sfx(name, vol * kk * (0.25 + 0.75 * k * k), pan, muffle) end
end

-- Шаги — ванильные звуки блоков (block.<материал>.step) по тому, на чём стоит игрок; материалы — steps в maps.lua.
local function material(x, z)
  local m = G.V.map
  local ch = M.cell(m, floor(x), floor(z))
  local st = m.def.steps or {}
  if ch == "," then return st.floor2 or st.floor or "stone" end
  if ch == "x" or ch == "X" then return st.box or "wood" end
  return st.floor or "stone"
end

local function footstep(x, z, vol, kind, range, y)
  local pan, muffle, kk, d = placeSound(x, y, z)
  local k = range and (1 - d / range) or 1
  vol = vol * k * kk * (1 - muffle * 0.4) * (G.set.volume or 1)
  if vol > 0.03 then
    local name = "block." .. material(x, z) .. "." .. (kind or "step")
    if range then
      sound.play(name, min(1, vol), (0.9 + random() * 0.2) * (1 - muffle * 0.15), pan)
    else
      sound.play(name, min(1, vol), 0.9 + random() * 0.2)
    end
  end
end

local function shotSound(w) return w.slot == 3 and "knife_swing" or "shot_" .. w.key end

-- --- Свой игрок (управление на этом телефоне) ---

local function newMe(id)
  return {id = id, x = 0, y = 0, z = 0, vx = 0, vz = 0, vy = 0, yaw = 0, pitch = 0, crouch = false, ground = true, tp = -1,
    wpn = D.KNIFE, last = D.KNIFE, ammo = {}, reload = 0, reloadFor = 0, cool = 0, switchT = 0, recoilAcc = 0, punchP = 0,
    punchY = 0, zoom = 0, walkT = 0, trigger = false, shots = 0, actions = {}, actSeq = 0, hits = {}, hitSeq = 0,
    nadeCool = 0, lastShot = 0, flashT = -1, stepT = 0, using = false, spec = nil}
end

local function owned(P, wid)
  if not P then return false end
  if wid == D.KNIFE then return true end
  if wid == D.C4 then return P.bomb end
  local f = D.NADE_FIELD[wid]
  if f then return (P[f] or 0) > 0 end
  return wid > 0 and (wid == P.pri or wid == P.sec)
end

local function best(P)
  if P.pri > 0 then return P.pri end
  if P.sec > 0 then return P.sec end
  return D.KNIFE
end

local function selectWeapon(wid)
  local me = G.me
  if wid == me.wpn then return end
  me.last = me.wpn
  me.wpn = wid
  me.switchT = 0.35
  me.reload, me.zoom = 0, 0
  sfx("deploy", 0.5)
end

local function pushAction(...)
  local me = G.me
  me.actSeq = me.actSeq + 1
  me.actions[#me.actions + 1] = {me.actSeq, ...}
end

local function fillAmmo(wid)
  local w = D.WEAPONS[wid]
  if w and w.clip then G.me.ammo[wid] = {w.clip, w.reserve} end
end

-- Сверка с хостом: телепорт в начале раунда, купленное оружие, отобранное оружие.
local function syncMe(P)
  local me = G.me
  if not P then return end
  if P.tp ~= me.tp then
    me.tp = P.tp
    me.x, me.y, me.z, me.vx, me.vz, me.vy = P.x, P.y, P.z, 0, 0, 0
    me.yaw, me.pitch = P.yaw, 0
    me.ammo, me.reload, me.zoom, me.punchP, me.punchY, me.recoilAcc = {}, 0, 0, 0, 0, 0
    me.wpn = best(P)
    me.spec = nil
    G.lastHp = P.hp
  end
  for _, wid in ipairs({P.pri, P.sec}) do
    if wid > 0 and not me.ammo[wid] then fillAmmo(wid) end
  end
  if P.pri > 0 and me.knownPri ~= P.pri and me.knownPri ~= nil then selectWeapon(P.pri) end
  if P.sec > 0 and me.knownSec ~= P.sec and me.knownSec ~= nil and P.pri == 0 then selectWeapon(P.sec) end
  me.knownPri, me.knownSec = P.pri, P.sec
  if not owned(P, me.wpn) and me.nadeCool <= 0 then
    me.wpn = best(P)
    me.zoom = 0
  end
  -- Деньги: показать изменение.
  if P.money ~= G.lastMoney then
    G.moneyDelta, G.moneyT = P.money - G.lastMoney, G.now
    G.lastMoney = P.money
  end
  if P.hp < G.lastHp and P.alive then
    G.hurtT = 0.5
    sfx("hurt", 0.8)
  end
  G.lastHp = P.hp
  if P.flash > G.flash + 0.25 then G.flash = P.flash end
end

-- Ввод для хоста (в сети — сообщение).
local function buildInput()
  local me, V = G.me, G.V
  local P = V.players[me.id]
  if P then
    while #me.actions > 0 and me.actions[1][1] <= P.ack do table.remove(me.actions, 1) end
    while #me.hits > 0 and me.hits[1][1] <= P.hitAck do table.remove(me.hits, 1) end
  end
  local acts = {}
  for i = 1, min(#me.actions, 6) do acts[i] = me.actions[i] end
  local hits = {}
  for i = 1, min(#me.hits, 24) do hits[i] = me.hits[i] end
  return {t = "i", m = G.matchId, tp = me.tp, x = floor(me.x * 100), y = floor(me.y * 100), z = floor(me.z * 100),
    vx = floor(me.vx * 100), vz = floor(me.vz * 100), yaw = floor(me.yaw), pitch = floor(me.pitch),
    c = me.crouch and 1 or 0, w = me.wpn, u = me.using and 1 or 0, sh = me.shots, h = hits, a = acts}
end

-- --- Движение ---

local function updateMove(dt, P)
  local me, V = G.me, G.V
  local map = V.map
  local w = D.WEAPONS[me.wpn]
  local frozen = V.phase == "freeze" or V.phase == "warmup" or V.phase == "over" or G.menu == "pause"
  local fx, _, fz = Sim.forward(me.yaw, 0)
  local rx, rz = cos(rad(me.yaw)), -sin(rad(me.yaw))
  local mx, mz = 0, 0
  if not frozen then
    if input.down("w") then mx, mz = mx + fx, mz + fz end
    if input.down("s") then mx, mz = mx - fx, mz - fz end
    if input.down("d") then mx, mz = mx + rx, mz + rz end
    if input.down("a") then mx, mz = mx - rx, mz - rz end
  end
  me.crouch = input.down("ctrl")
  -- E: закладка / обезвреживание / (с бомбой в руках — ЛКМ тоже).
  me.using = (input.down("e") or (me.wpn == D.C4 and input.down("mouse1") and G.menu == nil)) and V.phase ~= "freeze"
  local b = V.bomb
  local site = M.site(map, me.x, me.z)
  G.hint = nil
  local busy = false
  if P.bomb and site and V.phase == "live" then
    G.hint = "Зажмите E — заложить бомбу"
    busy = me.using
  elseif P.team == D.CT and b.state == "planted" and (me.x - b.x) ^ 2 + (me.z - b.z) ^ 2 < 1.7 then
    G.hint = "Зажмите E — обезвредить бомбу"
    busy = me.using
  elseif P.bomb and V.phase == "live" then
    G.hint = nil
  end
  if busy then mx, mz = 0, 0 end
  local len = sqrt(mx * mx + mz * mz)
  local speed = Sim.BASE_SPEED * (w.speed or 1) * (me.crouch and 0.45 or (input.down("shift") and 0.52 or 1)) * (me.zoom > 0 and 0.6 or 1)
  local tvx, tvz = 0, 0
  if len > 0 then tvx, tvz = mx / len * speed, mz / len * speed end
  local k = me.ground and min(1, dt * 14) or min(1, dt * 2.5)
  me.vx = me.vx + (tvx - me.vx) * k
  me.vz = me.vz + (tvz - me.vz) * k
  local hx, hz = M.move(map, me, me.vx * dt, me.vz * dt, Sim.RADIUS, M.STEP)
  if hx then me.vx = 0 end
  if hz then me.vz = 0 end
  local g = M.ground(map, me.x, me.z, Sim.RADIUS)
  if me.y > g + 0.001 or me.vy > 0 then
    me.vy = me.vy - Sim.GRAVITY * dt
    me.y = me.y + me.vy * dt
    -- Потолок (тоннель, балкон над головой): прыжок упирается.
    local rf = M.roofAt(map, me.x, me.z, Sim.RADIUS)
    if me.y + M.HEAD > rf then
      me.y = max(g, rf - M.HEAD)
      if me.vy > 0 then me.vy = 0 end
    end
    if me.y <= g then
      if me.vy < -4 then footstep(me.x, me.z, 0.7, "fall") end
      me.y, me.vy = g, 0
    end
  else
    me.y, me.vy = g, 0
    if input.down("space") and not frozen and not busy then
      me.vy = Sim.JUMP
    end
  end
  me.ground = me.y <= g + 0.001
  local sp = sqrt(me.vx * me.vx + me.vz * me.vz)
  me.walkT = me.walkT + sp * dt
  if sp > 2.4 and me.ground then
    me.stepT = me.stepT + dt
    if me.stepT > 0.36 then
      me.stepT = 0
      footstep(me.x, me.z, 0.45)
    end
  end
  -- Подсказка про оружие на земле.
  if not G.hint then
    for _, d in ipairs(V.drops) do
      if (d.x - me.x) ^ 2 + (d.z - me.z) ^ 2 < 1.2 then
        local slot = D.WEAPONS[d.wid].slot
        if (slot == 1 and P.pri > 0) or (slot == 2 and P.sec > 0) then
          G.hint = D.WEAPONS[d.wid].name .. ": G — выбросить своё, чтобы подобрать"
        end
        break
      end
    end
  end
end

-- --- Стрельба ---

local function startReload()
  local me = G.me
  local w = D.WEAPONS[me.wpn]
  local am = me.ammo[me.wpn]
  if not w.clip or not am or am[2] <= 0 or am[1] >= w.clip or me.reload > 0 then return end
  me.reload, me.reloadFor, me.zoom = w.reload, me.wpn, 0
  me.reloadSnd = false
  sfx("reload_out", 0.7)
end

local function finishReload()
  local me = G.me
  local w = D.WEAPONS[me.reloadFor]
  local am = me.ammo[me.reloadFor]
  if w and am then
    local take = min(w.clip - am[1], am[2])
    am[1], am[2] = am[1] + take, am[2] - take
  end
end

local function rayDir(yaw, pitch, ax, ay)
  local fx, fy, fz = Sim.forward(yaw, pitch)
  local rx, rz = cos(rad(yaw)), -sin(rad(yaw))
  -- вверх = вперёд × вправо
  local ux, uy, uz = fy * rz, fz * rx - fx * rz, -fy * rx
  local dx, dy, dz = fx + rx * tan(ax) + ux * tan(ay), fy + uy * tan(ay), fz + rz * tan(ax) + uz * tan(ay)
  local l = sqrt(dx * dx + dy * dy + dz * dz)
  return dx / l, dy / l, dz / l
end

local function registerHit(pid, part, dist, wid)
  local me = G.me
  me.hitSeq = me.hitSeq + 1
  me.hits[#me.hits + 1] = {me.hitSeq, pid, part, wid, floor(dist * 10)}
  G.hitT, G.hitHead = 0.18, part == D.HEAD
  sfx(wid == D.KNIFE and "knife_hit" or (part == D.HEAD and "hit_head" or "hit_body"), 0.7)
end

local function currentSpread(w)
  local me = G.me
  local sp = sqrt(me.vx * me.vx + me.vz * me.vz)
  local s = (w.id == D.AWP and me.zoom > 0) and w.scoped or (w.spread or 0)
  s = s + (w.move or 0) * min(1, sp / 3) + (me.ground and 0 or 0.12) + me.recoilAcc * 0.0045 * (w.auto and 1 or 0.6)
  if me.crouch then s = s * 0.75 end
  return s
end

local function throwNade(cam)
  local me = G.me
  local P = G.V.players[me.id]
  local f = D.NADE_FIELD[me.wpn]
  if not f or P[f] <= 0 or me.nadeCool > 0 then return end
  local fx, fy, fz = Sim.forward(me.yaw, me.pitch + 8)
  local v = 12.5
  pushAction("nade", me.wpn, floor(cam.x * 100), floor(cam.y * 100), floor(cam.z * 100),
    floor((fx * v + me.vx * 0.5) * 100), floor((fy * v) * 100), floor((fz * v + me.vz * 0.5) * 100))
  me.nadeCool = 0.8
  sfx("nade_throw", 0.7)
  -- после броска — к последнему оружию (граната кончится у хоста)
  local left = P[f] - 1
  if left <= 0 then
    for _, wid in ipairs({D.HE, D.FLASH, D.SMOKE}) do
      if wid ~= me.wpn and owned(P, wid) then
        me.wpn = wid
        return
      end
    end
    me.wpn = best(P)
  end
end

local function fire(sw, sh, now, cam)
  local me, V = G.me, G.V
  local P = V.players[me.id]
  local w = D.WEAPONS[me.wpn]
  local down = input.down("mouse1")
  if not down then
    me.trigger = false
    return
  end
  if me.cool > 0 or me.reload > 0 or me.switchT > 0 then return end
  if V.phase == "freeze" or V.phase == "over" then return end
  if not w.auto and me.trigger then return end
  me.trigger = true
  if w.slot == 5 then return end
  if w.slot == 4 then
    throwNade(cam)
    return
  end
  local fov = cam.fov
  local focal = (sh / 2) / tan(rad(fov / 2))
  if w.slot == 3 then
    me.cool = w.cool
    me.shots = me.shots + 1
    sfx("knife_swing", 0.6)
    local id, dist = g3d.pick(sw / 2, sh / 2)
    if id and id >= 4 and dist and dist < w.reach then
      local pid, part = floor(id / 4), id % 4
      local v = V.players[pid]
      if v and v.alive and v.team ~= P.team then registerHit(pid, D.BODY, dist, D.KNIFE) end
    end
    return
  end
  local am = me.ammo[me.wpn]
  if not am or am[1] <= 0 then
    me.cool = 0.25
    sfx("dry", 0.6)
    if am and am[2] > 0 then startReload() end
    return
  end
  am[1] = am[1] - 1
  me.shots = me.shots + 1
  me.cool = w.cool
  me.lastShot = now
  me.flashT = now
  sfx(shotSound(w), 0.75)
  local spread = currentSpread(w)
  for _ = 1, w.pellets or 1 do
    local a = random() * math.pi * 2
    local r = sqrt(random()) * spread
    local ax, ay = cos(a) * r, sin(a) * r
    local px, py = sw / 2 + tan(ax) * focal, sh / 2 - tan(ay) * focal
    local id, dist = g3d.pick(px, py)
    local dx, dy, dz = rayDir(cam.yaw, cam.pitch, ax, ay)
    if id and id >= 4 and dist and dist > 0 then
      local pid, part = floor(id / 4), id % 4
      local v = V.players[pid]
      if v and v.alive and v.team ~= P.team then
        registerHit(pid, part, dist, me.wpn)
        R.addFx("blood", cam.x + dx * dist, cam.y + dy * dist, cam.z + dz * dist, 0.35)
        v.hurtT = now
      end
    elseif dist and dist > 0 and dist < 80 then
      R.addFx("impact", cam.x + dx * dist * 0.985, cam.y + dy * dist * 0.985, cam.z + dz * dist * 0.985, 6)
      R.addFx("spark", cam.x + dx * dist * 0.98, cam.y + dy * dist * 0.98, cam.z + dz * dist * 0.98, 0.12)
    end
  end
  -- Отдача: прицел уводит вверх и в стороны, разброс копится при длинной очереди.
  me.recoilAcc = min(me.recoilAcc + 1, 12)
  me.punchP = me.punchP + w.recoil * (0.6 + min(me.recoilAcc, 8) * 0.08)
  me.punchY = me.punchY + (random() * 2 - 1) * w.recoil * (me.recoilAcc > 5 and 0.7 or 0.25)
  if w.id == D.AWP then me.zoom = 0 end
  if am[1] == 0 and am[2] > 0 then me.autoReload = 0.3 end
end

-- --- Остальные игроки: плавное движение, звуки выстрелов и шагов ---

local function angleLerp(a, b, k)
  local d = (b - a + 180) % 360 - 180
  return a + d * k
end

local function updateOthers(dt, now)
  local V, me = G.V, G.me
  for _, p in pairs(V.players) do
    if p.id == me.id then
      p.rx, p.ry, p.rz, p.ryaw, p.rpitch = me.x, me.y, me.z, me.yaw, me.pitch
    elseif not p.rx or p.tpSeen ~= p.tp then
      p.rx, p.ry, p.rz, p.ryaw, p.rpitch = p.x, p.y, p.z, p.yaw, p.pitch
      p.tpSeen = p.tp
    else
      local k = min(1, dt * 14)
      p.rx = p.rx + (p.x - p.rx) * k
      p.ry = p.ry + (p.y - p.ry) * k
      p.rz = p.rz + (p.z - p.rz) * k
      p.ryaw = angleLerp(p.ryaw, p.yaw, min(1, dt * 16))
      p.rpitch = p.rpitch + (p.pitch - p.rpitch) * k
    end
    local sp = sqrt((p.vx or 0) ^ 2 + (p.vz or 0) ^ 2)
    p.walkT = (p.walkT or 0) + sp * dt
    if p.wasAlive and not p.alive then p.deathT = now end
    if p.alive then p.deathT = nil end
    p.wasAlive = p.alive
    if p.id ~= me.id then
      if p.lastShots and p.shots > p.lastShots and p.alive then
        local w = D.WEAPONS[p.wpn] or D.WEAPONS[1]
        sfx3d(shotSound(w), p.rx, p.rz, w.slot == 3 and 0.4 or 0.75, w.slot == 3 and 12 or 60, p.ry)
        p.flashT = now
      end
      p.lastShots = p.shots
      if p.alive and sp > 2.4 then
        p.stepT = (p.stepT or 0) + dt
        if p.stepT > 0.38 then
          p.stepT = 0
          footstep(p.rx, p.rz, 0.6, "step", 22, p.ry)
        end
      end
    end
  end
end

-- --- События игры (общие для хоста и гостей) ---

local function feedName(id)
  local p = G.V.players[id]
  return p and p.name or "?", p and p.team or 0
end

local function onEvent(e, now)
  local V, me = G.V, G.me
  local P = V.players[me.id]
  local myTeam = P and P.team or 0
  local kind = e[2]
  if kind == "k" then
    local kn, kt = feedName(e[3])
    local vn, vt = feedName(e[4])
    if e[3] == 0 then kn, kt = "", 0 end
    local w = D.WEAPONS[e[5]]
    G.feed[#G.feed + 1] = {killer = e[3], victim = e[4], kn = kn, kt = kt, vn = vn, vt = vt, wn = w and w.name or "?",
      head = e[6] == 1, t = now}
    while #G.feed > 6 do table.remove(G.feed, 1) end
    if e[3] == me.id and e[4] ~= me.id then sfx("kill", 0.6) end
    if e[4] == me.id then
      G.center = {text = e[3] > 0 and e[3] ~= me.id and ("Вас убил " .. kn) or "Вы погибли", color = "#FFFF6060", t = now}
      me.spec, me.zoom = nil, 0
      sfx("death", 0.8)
    end
  elseif kind == "round" then
    G.center = {text = "Раунд " .. e[3], sub = "Покупка — B · " .. D.TEAM_NAME[myTeam ~= 0 and myTeam or 1], t = now, life = 2.5}
    G.buyCat = nil
  elseif kind == "go" then
    local two = #V.map.sites > 1
    G.small = {text = myTeam == D.T and (two and "Вперёд! Заложите бомбу на A или B" or "Вперёд! Заложите бомбу на A")
      or (two and "Вперёд! Защитите точки A и B" or "Вперёд! Защитите точку A"), t = now}
    sfx("round_start", 0.6)
  elseif kind == "end" then
    local win = e[3]
    G.center = {text = D.TEAM_NAME[win] .. " победили", sub = D.ROUND_REASON[e[4]] or "", color = D.TEAM_COLOR[win], t = now, life = 4.5}
    sfx(win == myTeam and "round_win" or "round_lose", 0.6)
  elseif kind == "plant" then
    G.center = {text = "Бомба заложена на точке " .. (e[4] == 1 and "A" or "B"), color = "#FFFF5050", t = now, life = 2.5}
    sfx("bomb_planted", 0.8)
  elseif kind == "defuse" then
    sfx("defused", 0.8)
  elseif kind == "boom" then
    local x, z = e[3] / 100, e[4] / 100
    R.addFx("boom", x, 0, z, 1.2, 6)
    sfx3d("bomb_explode", x, z, 1, 120)
    local cx, cz = camPos()
    if (cx - x) ^ 2 + (cz - z) ^ 2 < 400 then G.flash = max(G.flash, 1.2) end
  elseif kind == "he" then
    local x, y, z = e[3] / 100, e[4] / 100, e[5] / 100
    R.addFx("boom", x, y, z, 0.5, 2.6)
    sfx3d("he_explode", x, z, 1, 70, y)
  elseif kind == "fl" then
    local x, y, z = e[3] / 100, e[4] / 100, e[5] / 100
    R.addFx("flash", x, y, z, 0.3)
    sfx3d("flash_bang", x, z, 1, 60, y)
  elseif kind == "sm" then
    sfx3d("smoke_hiss", e[3] / 100, e[4] / 100, 0.8, 35)
  elseif kind == "half" then
    G.center = {text = "Смена сторон", sub = "Теперь вы — " .. D.TEAM_NAME[myTeam ~= 0 and myTeam or 1], t = now, life = 4}
  elseif kind == "over" then
    local win = e[3]
    G.center = {text = win == myTeam and "ПОБЕДА!" or "Поражение", sub = "Матч окончен: " .. V.score[1] .. " : " .. V.score[2] ..
      " · новый матч через 15 с", color = win == myTeam and "#FF8CF08C" or "#FFFF6060", t = now, life = 15}
  elseif kind == "join" then
    local n = feedName(e[3])
    G.small = {text = n .. " в игре", t = now}
    sfx("pickup", 0.4)
  elseif kind == "left" then
    G.small = {text = "Игрок вышел, его место занял бот", t = now}
  elseif kind == "pick" then
    if e[3] == me.id then sfx("pickup", 0.6) end
  elseif kind == "bomb" then
    if e[3] == me.id then G.small = {text = "Вы подобрали бомбу (5 — в руки, E на точке — заложить)", t = now} end
  elseif kind == "planting" or kind == "defusing" then
    local p = V.players[e[3]]
    if p and p.rx then sfx3d(kind == "planting" and "bomb_plant" or "defuse", p.rx, p.rz, 0.8, 30, p.ry) end
  end
end

local function processEvents(now)
  local V = G.V
  for _, e in ipairs(V.events) do
    if e[1] > G.lastEvent then
      G.lastEvent = e[1]
      onEvent(e, now)
    end
  end
end

-- --- Запуск и выход из игры ---

local function startGame(role, o)
  local now = time.clock()
  G.role = role
  G.feed, G.center, G.small, G.menu, G.hint = {}, nil, nil, nil, nil
  G.lastEvent, G.flash, G.hurtT = 0, 0, 0
  if role ~= "client" then
    G.matchId = random(1, 999999999)
    local S = Sim.new({map = o.map, firstTo = o.firstTo, botLevel = o.level, teamSize = o.bots, matchId = G.matchId,
      wingman = o.wingman})
    Sim.fillBots(S)
    local p = Sim.addHuman(S, app.player or "Игрок", o.team > 0 and o.team or nil)
    S.events = {}
    G.S, G.V = S, S
    G.me = newMe(p.id)
    G.clients = {}
    G.lastSnapSent, G.lastTick = now, now
    if role == "host" then
      N.send(now, nil, {t = "lobby", m = G.matchId, map = o.map, host = app.player, n = 1, ft = o.firstTo,
        wm = o.wingman and 1 or 0})
    end
  else
    G.matchId, G.hostName = o.m, o.host
    G.S, G.V = nil, N.newView(o.map)
    G.me = newMe(o.id)
    G.lastSnap, G.gotSnap, G.lastInputSent = now, false, now
  end
  G.lastMoney, G.lastHp = D.MONEY_START, 100
  R.buildMap(G.V.map)
  R.fx = {}
  G.state = "game"
  input.lockMouse(true)
end

local function leaveGame(reason)
  local now = time.clock()
  if G.role == "host" and G.clients and next(G.clients) then N.send(now, nil, {t = "end", m = G.matchId}) end
  if G.role == "client" then N.send(now, G.hostName, {t = "bye", m = G.matchId}) end
  input.lockMouse(false)
  G.state, G.role, G.S, G.V, G.menu, G.joining = "title", nil, nil, nil, nil, nil
  if reason then toast(reason) end
end

-- --- Меню ---

local function button(x, y, w, h, label, style)
  return ui.button(floor(x), floor(y), floor(w), floor(h), label, style)
end

local function background(sw, sh)
  gfx.gradient(0, 0, sw, sh, "#FF1B2632", "#FF06080B")
  gfx.rect(0, sh - 1, sw, 1, "#FF2A3440")
end

local function logo(sw, y)
  local s = 2.2
  local w1 = gfx.textWidth("BLOCK ", s)
  local w2 = gfx.textWidth("STRIKE", s)
  local x = floor((sw - w1 - w2) / 2)
  gfx.text("BLOCK ", x, y, "#FFF2F2F2", s, true)
  gfx.text("STRIKE", x + w1, y, "#FFFFA726", s, true)
end

local function online() return net.online() end

local function titleScreen(sw, sh)
  background(sw, sh)
  logo(sw, 14)
  H.center("закладка бомбы · напарники 2×2 · 9 карт · 13 видов оружия · боты · игра по сети", sw / 2, 36, "#FFAAB4C0", 0.5)
  local bw, bh = 150, 17
  local x, y = (sw - bw) / 2, 50
  if button(x, y, bw, bh, "Игра с ботами", "primary") then G.state, G.createOnline = "create", false end
  y = y + 20
  if online() then
    if button(x, y, bw, bh, "Создать сервер") then G.state, G.createOnline = "create", true end
    y = y + 20
    if button(x, y, bw, bh, "Найти игру") then
      G.state, G.lobbies, G.findT = "find", {}, 0
      N.send(time.clock(), nil, {t = "find"})
    end
    y = y + 20
  end
  if button(x, y, bw / 2 - 2, bh, "Настройки") then G.state = "settings" end
  if button(x + bw / 2 + 2, y, bw / 2 - 2, bh, "Как играть") then G.state = "help" end
  local note = online() and "На сервере есть BlockPhone — можно играть с друзьями (у них тоже должно быть это приложение)"
      or "Одиночная игра: только с ботами. Для игры по сети нужен сервер с BlockPhone."
  local lines = gfx.wrap(note, sw - 20, 0.45)
  for i, l in ipairs(lines) do H.center(l, sw / 2, sh - 8 - (#lines - i + 1) * 6 + 2, "#FF7A8696", 0.45) end
end

local function optionRow(x, y, w, label, value, onPrev, onNext)
  gfx.rect(x, y, w, 16, "#80202833")
  H.text(label, x + 5, y + 5, "#FFB8C2CE", 0.55)
  local vx = x + w - 110
  if button(vx, y + 1, 14, 14, "<") then onPrev() end
  H.center(value, vx + 55, y + 5, "#FFFFFFFF", 0.55)
  if button(vx + 96, y + 1, 14, 14, ">") then onNext() end
end

local function cycle(v, n, d) return (v - 1 + d) % n + 1 end

-- Карты выбранного режима; номер карты в настройках — из M.LIST, его поправляем, если не подходит режиму.
local function modeMaps(s)
  local list = M.forMode(s.wingman)
  local pos = 1
  for i, idx in ipairs(list) do if idx == s.map then pos = i end end
  s.map = list[pos]
  return list, pos
end

local function createScreen(sw, sh)
  background(sw, sh)
  local s = G.set
  H.center(G.createOnline and "Создать сервер" or "Игра с ботами", sw / 2, 5, "#FFFFFFFF", 0.8)
  local size = sh - 46
  local maps, pos = modeMaps(s)
  local def = M.LIST[s.map]
  gfx.rect(8, 20, size + 4, size + 4, "#FF0B0E12")
  gfx.image("radar/" .. def.key .. ".png", 10, 22, size, size)
  H.center(def.name, 10 + size / 2, 24 + size + 3, "#FFFFFFFF", 0.6)
  local x, w = size + 20, sw - size - 28
  local y = 20
  local function toggleMode()
    s.wingman = not s.wingman
    s.firstTo = s.wingman and 9 or 8
    modeMaps(s)
  end
  optionRow(x, y, w, "Режим", s.wingman and "Напарники 2×2" or "Классика", toggleMode, toggleMode)
  y = y + 18
  optionRow(x, y, w, "Карта", def.name, function() s.map = maps[cycle(pos, #maps, -1)] end,
    function() s.map = maps[cycle(pos, #maps, 1)] end)
  y = y + 18
  if not s.wingman then
    optionRow(x, y, w, "Команды", s.bots .. " на " .. s.bots, function() s.bots = cycle(s.bots, 5, -1) end,
      function() s.bots = cycle(s.bots, 5, 1) end)
    y = y + 18
  end
  optionRow(x, y, w, "Боты", D.BOT_LEVELS[s.level], function() s.level = cycle(s.level, 3, -1) end, function() s.level = cycle(s.level, 3, 1) end)
  y = y + 18
  local ri = 3
  for i, r in ipairs(ROUNDS) do if r == s.firstTo then ri = i end end
  optionRow(x, y, w, "Победа", "до " .. s.firstTo .. " раундов", function() s.firstTo = ROUNDS[cycle(ri, #ROUNDS, -1)] end,
    function() s.firstTo = ROUNDS[cycle(ri, #ROUNDS, 1)] end)
  y = y + 18
  local teams = {"Авто", "Террористы", "Спецназ"}
  optionRow(x, y, w, "Моя команда", teams[s.team + 1], function() s.team = (s.team + 2) % 3 end, function() s.team = (s.team + 1) % 3 end)
  y = y + 20
  if s.wingman then
    H.text("Напарники: 2 на 2, одна точка закладки, раунд 1:30. Вдвоём с другом — против ботов или друг против друга.", x, y,
      "#FF8A96A6", 0.42)
    y = y + 8
  end
  if G.createOnline then
    H.text("Друзья входят через «Найти игру». Не закрывайте телефон — игра идёт на нём.", x, y, "#FF8A96A6", 0.42)
    y = y + 8
  end
  if button(x, sh - 22, w / 2 - 2, 17, "Назад") then G.state = "title" end
  if button(x + w / 2 + 2, sh - 22, w / 2 - 2, 17, "Начать", "primary") then
    saveSettings()
    startGame(G.createOnline and "host" or "offline", {map = s.map, firstTo = s.firstTo, level = s.level, bots = s.bots, team = s.team,
      wingman = s.wingman})
  end
end

local function findScreen(sw, sh)
  background(sw, sh)
  local now = time.clock()
  H.center("Найти игру", sw / 2, 5, "#FFFFFFFF", 0.8)
  local list = {}
  for host, l in pairs(G.lobbies) do
    if now - l.t < 20 then list[#list + 1] = l else G.lobbies[host] = nil end
  end
  table.sort(list, function(a, b) return a.host < b.host end)
  local y = 22
  if G.joining then
    H.center("Подключение к " .. G.joining.host .. "…", sw / 2, sh / 2 - 10, "#FFFFFFFF", 0.7)
    if now - G.joining.t > 12 then
      G.joining = nil
      toast("Хост не ответил")
    elseif now - G.joining.sent > 2 then
      G.joining.sent = now
      N.send(now, G.joining.host, {t = "join", m = G.joining.m})
    end
    if button(sw / 2 - 40, sh / 2 + 6, 80, 16, "Отмена") then G.joining = nil end
    return
  end
  if #list == 0 then
    H.center("Ищем игры на сервере…", sw / 2, y + 20, "#FFB0B8C0", 0.6)
    H.center("Попросите друга нажать «Создать сервер» или пригласить вас из игры.", sw / 2, y + 32, "#FF7A8696", 0.45)
  end
  for _, l in ipairs(list) do
    local def = M.LIST[l.map] or M.LIST[1]
    gfx.rect(10, y, sw - 20, 20, "#A0202833")
    H.text(l.host, 16, y + 3, "#FFFFFFFF", 0.65)
    H.text((l.wm == 1 and "Напарники · " or "") .. def.name .. " · игроков: " .. (l.n or 1) .. " · до " .. (l.ft or 8)
      .. " побед" .. (l.invite and " · вас пригласили" or ""),
      16, y + 12, "#FFAAB4C0", 0.45)
    if button(sw - 70, y + 2, 56, 16, "Войти", "primary") then
      G.joining = {host = l.host, m = l.m, map = l.map, t = now, sent = now}
      N.send(now, l.host, {t = "join", m = l.m})
    end
    y = y + 23
    if y > sh - 40 then break end
  end
  if button(10, sh - 22, 80, 17, "Назад") then G.state = "title" end
  if button(sw - 90, sh - 22, 80, 17, "Обновить") then
    N.send(now, nil, {t = "find"})
  end
end

local function settingsScreen(sw, sh)
  background(sw, sh)
  local s = G.set
  H.center("Настройки", sw / 2, 5, "#FFFFFFFF", 0.8)
  local x, w, y = 30, sw - 60, 22
  optionRow(x, y, w, "Чувствительность мыши", string.format("%.1f", s.sens),
    function() s.sens = max(0.2, s.sens - 0.1) end, function() s.sens = min(4, s.sens + 0.1) end)
  y = y + 18
  optionRow(x, y, w, "Качество 3D (меньше — быстрее)", QUALITY_NAME[s.quality],
    function() s.quality = cycle(s.quality, #QUALITY, -1) end, function() s.quality = cycle(s.quality, #QUALITY, 1) end)
  y = y + 18
  optionRow(x, y, w, "Поле зрения", s.fov .. "°", function() s.fov = max(50, s.fov - 2) end, function() s.fov = min(90, s.fov + 2) end)
  y = y + 18
  optionRow(x, y, w, "Громкость", floor(s.volume * 100 + 0.5) .. "%",
    function() s.volume = max(0, s.volume - 0.1) end, function() s.volume = min(1, s.volume + 0.1) end)
  y = y + 18
  optionRow(x, y, w, "Цвет прицела", CROSS_NAME[s.cross],
    function() s.cross = cycle(s.cross, #CROSS, -1) end, function() s.cross = cycle(s.cross, #CROSS, 1) end)
  if button(sw / 2 - 50, sh - 22, 100, 17, "Готово", "primary") then
    saveSettings()
    G.state = "title"
  end
end

local HELP = {
  "Цель: террористы закладывают бомбу на точке A или B (зажать E на точке, 3 с), спецназ мешает или обезвреживает "
    .. "её (зажать E у бомбы: 7 с, с набором сапёра — 4 с). Раунд выигрывает и команда, уничтожившая всех противников.",
  "Мышь — обзор (нажмите на экран, чтобы захватить мышь), ЛКМ — огонь, ПКМ — прицел AWP, WASD — ходьба, "
    .. "Shift — тихий шаг, Ctrl — присесть, Пробел — прыжок, R — перезарядка.",
  "1 — основное оружие, 2 — пистолет, 3 — нож, 4 — гранаты (ещё раз — следующая), 5 — бомба, Q — прошлое оружие, "
    .. "G — выбросить оружие, B — покупка (на базе в начале раунда), Tab — счёт, M — сменить команду, Esc — пауза.",
  "Деньги: за победу $3250, за поражение $1400 и больше при серии, за убийство — по оружию (нож $1500, AWP $100). "
    .. "Бронежилет со шлемом спасает от выстрела в голову. Стрельба на бегу и длинной очередью — неточная.",
  "Режим «Напарники»: 2 на 2 на маленьких картах с одной точкой A, раунд 1:30, до 9 побед. Вдвоём с другом можно "
    .. "играть против двух ботов или друг против друга (выберите разные команды).",
  "По сети: игрок, создавший сервер, — хост; на свободных местах играют боты. Хосту нельзя закрывать телефон.",
}

local function helpScreen(sw, sh)
  background(sw, sh)
  H.center("Как играть", sw / 2, 5, "#FFFFFFFF", 0.8)
  local y = 18
  for _, para in ipairs(HELP) do
    for _, l in ipairs(gfx.wrap(para, sw - 24, 0.48)) do
      H.text(l, 12, y, "#FFD0D8E0", 0.48)
      y = y + 6
    end
    y = y + 3
  end
  if button(sw / 2 - 50, sh - 20, 100, 16, "Назад", "primary") then G.state = "title" end
end

-- --- Меню в игре ---

local function closeMenu()
  G.menu = nil
  input.lockMouse(true)
end

local function panel(sw, sh, w, h, title)
  local x, y = floor((sw - w) / 2), floor((sh - h) / 2)
  gfx.rect(0, 0, sw, sh, "#80000000")
  gfx.rect(x, y, w, h, "#F0101418")
  gfx.border(x, y, w, h, "#FF3A4250")
  H.center(title, sw / 2, y + 5, "#FFFFFFFF", 0.75)
  return x, y
end

local function pauseMenu(sw, sh)
  local x, y = panel(sw, sh, 160, 118, "Пауза")
  local bw = 140
  local bx = x + 10
  y = y + 18
  if button(bx, y, bw, 16, "Продолжить", "primary") then closeMenu() end
  y = y + 19
  if button(bx, y, bw, 16, "Сменить команду (M)") then G.menu = "team" end
  y = y + 19
  if G.role == "host" then
    if button(bx, y, bw, 16, "Пригласить игроков") then
      G.menu, G.invitePlayers = "invite", nil
      net.players(function(names) G.invitePlayers = names end)
    end
    y = y + 19
  end
  if button(bx, y, bw, 16, "Выйти в меню", "danger") then leaveGame() end
  local info = G.role == "offline" and "Игра с ботами (на паузе)" or (G.role == "host" and "Вы — хост: не закрывайте телефон"
    or "Сервер: " .. (G.hostName or ""))
  H.center(info, sw / 2, y + 22, "#FF8A96A6", 0.45)
end

local function teamMenu(sw, sh)
  local x, y = panel(sw, sh, 180, 84, "Сменить команду")
  if button(x + 10, y + 20, 78, 18, "1  Террористы") then
    pushAction("team", 1)
    closeMenu()
  end
  if button(x + 92, y + 20, 78, 18, "2  Спецназ") then
    pushAction("team", 2)
    closeMenu()
  end
  H.center("Переход — со следующего раунда", sw / 2, y + 44, "#FF8A96A6", 0.45)
  if button(x + 50, y + 60, 80, 16, "Отмена") then closeMenu() end
end

local function inviteMenu(sw, sh)
  local x, y = panel(sw, sh, 200, sh - 20, "Пригласить игроков")
  local now = time.clock()
  local list = G.invitePlayers
  local yy = y + 18
  if not list then
    H.center("Загрузка…", sw / 2, yy + 10, "#FFB0B8C0", 0.55)
  elseif #list == 0 then
    H.center("Никого с BlockPhone на сервере", sw / 2, yy + 10, "#FFB0B8C0", 0.55)
  else
    for _, name in ipairs(list) do
      if yy > y + sh - 60 then break end
      if button(x + 10, yy, 180, 15, name) then
        N.send(now, name, {t = "inv", m = G.matchId, map = G.S.mapIndex, wm = G.S.wingman and 1 or 0, host = app.player,
          n = Sim.count(G.S, 1, false, true)
          + Sim.count(G.S, 2, false, true), ft = G.S.firstTo})
        toast("Приглашение отправлено: " .. name)
      end
      yy = yy + 17
    end
  end
  if button(x + 60, y + sh - 42, 80, 16, "Назад") then G.menu = "pause" end
end

local function buy(item)
  pushAction("buy", item)
  sfx("buy", 0.6)
end

-- --- Кадр игры ---

local function spectateTarget()
  local V, me = G.V, G.me
  local P = V.players[me.id]
  if not P then return nil end
  local list = N.list(V)
  local cur = me.spec and V.players[me.spec]
  if cur and cur.alive and cur.team == P.team then return cur end
  for _, p in ipairs(list) do
    if p.alive and p.team == P.team then
      me.spec = p.id
      return p
    end
  end
  return nil
end

local function nextSpectate()
  local V, me = G.V, G.me
  local P = V.players[me.id]
  if not P then return end
  local list = N.list(V)
  local start = 0
  for i, p in ipairs(list) do if p.id == me.spec then start = i end end
  for k = 1, #list do
    local p = list[(start + k - 1) % #list + 1]
    if p.alive and p.team == P.team then
      me.spec = p.id
      return
    end
  end
end

local function gameFrame(dt, sw, sh)
  local V, me, now = G.V, G.me, G.now
  if G.role == "client" and not G.gotSnap then
    gfx.clear("#FF0B0E12")
    H.center("Подключение к " .. (G.hostName or "") .. "…", sw / 2, sh / 2 - 4, "#FFFFFFFF", 0.7)
    return
  end
  if G.S then V.buyLeft = max(0, G.S.buyUntil - G.S.time) end
  local P = V.players[me.id]
  if not P or (G.role == "client" and (P.bot or P.name ~= app.player)) then
    leaveGame("Вас отключили от игры")
    return
  end
  syncMe(P)
  local alive = P.alive
  local locked = input.mouseLocked()
  local dx, dy = input.mouseDelta()
  local w = D.WEAPONS[me.wpn]
  local fov = G.set.fov
  if me.zoom > 0 and w.scope then fov = w.scope[me.zoom] end
  if alive and locked then
    local k = 0.22 * G.set.sens * (fov / G.set.fov)
    me.yaw = (me.yaw + dx * k + 180) % 360 - 180
    me.pitch = max(-85, min(85, me.pitch - dy * k))
  end
  if alive then updateMove(dt, P) else G.hint = nil end
  -- таймеры
  me.cool = max(0, me.cool - dt)
  me.switchT = max(0, me.switchT - dt)
  me.nadeCool = max(0, me.nadeCool - dt)
  if me.reload > 0 then
    local wr = D.WEAPONS[me.reloadFor]
    if not me.reloadSnd and me.reload < (wr.reload or 1) * 0.45 then
      me.reloadSnd = true
      sfx("reload_in", 0.7)
    end
    me.reload = me.reload - dt
    if me.reload <= 0 then
      me.reload = 0
      finishReload()
    end
  end
  if me.autoReload then
    me.autoReload = me.autoReload - dt
    if me.autoReload <= 0 then
      me.autoReload = nil
      startReload()
    end
  end
  local decay = min(1, dt * 5)
  me.punchP = me.punchP - me.punchP * decay
  me.punchY = me.punchY - me.punchY * decay
  if now - me.lastShot > 0.25 then me.recoilAcc = max(0, me.recoilAcc - dt * 15) end
  G.flash = max(0, G.flash - dt)
  G.hurtT = max(0, G.hurtT - dt)
  G.hitT = max(0, G.hitT - dt)
  updateOthers(dt, now)
  processEvents(now)
  R.updateFx(dt)
  -- Пищалка бомбы.
  local b = V.bomb
  if b.state == "planted" and V.phase == "planted" then
    G.beepT = G.beepT - dt
    if G.beepT <= 0 then
      G.beepT = max(0.12, min(1, b.timer / 30))
      sfx3d("bomb_beep", b.x, b.z, 0.8, 50, b.y)
    end
  end
  -- Камера: своя или того, за кем наблюдаем.
  local cam, hide
  if alive then
    cam = {x = me.x, y = me.y + (me.crouch and Sim.EYE_CROUCH or Sim.EYE), z = me.z, yaw = me.yaw + me.punchY,
      pitch = max(-89, min(89, me.pitch + me.punchP)), fov = fov}
    hide = me.id
    G.specName = nil
  else
    local t = spectateTarget()
    if t and t.rx then
      cam = {x = t.rx, y = t.ry + (t.crouch and Sim.EYE_CROUCH or Sim.EYE), z = t.rz, yaw = t.ryaw, pitch = t.rpitch, fov = G.set.fov}
      hide = t.id
      G.specName = t.name
    else
      local fx, _, fz = Sim.forward(me.yaw, 0)
      cam = {x = me.x - fx * 1.5, y = me.y + 1.6, z = me.z - fz * 1.5, yaw = me.yaw, pitch = -35, fov = G.set.fov}
      G.specName = nil
    end
  end
  G.cam = cam
  G.smoke = R.smokeCover(V, cam.x, cam.y, cam.z)
  -- Кадр: мир → выстрелы (g3d.pick по миру, пока оружие ещё не нарисовано) → оружие в руках → вывод кадра.
  R.drawWorld(V, cam, 0, 0, sw, sh, QUALITY[G.set.quality], now, hide)
  local enemyName
  if alive then
    if locked and G.menu == nil then fire(sw, sh, now, cam) end
    local id = g3d.pick(sw / 2, sh / 2)
    if id and id >= 4 then
      local v = V.players[floor(id / 4)]
      if v and v.team ~= P.team and v.alive then enemyName = v.name end
    end
    if me.zoom == 0 then VM.draw(cam, me, P.team, now) end
  end
  g3d.finish()
  if alive then
    if me.zoom > 0 then H.scope(sw, sh) end
    G.inBuy = M.inBuyZone(V.map, P.team, me.x, me.z)
    H.crosshair({me = me, set = {cross = CROSS[G.set.cross]}, hitT = G.hitT, hitHead = G.hitHead}, sw, sh, currentSpread(w), enemyName)
  end
  H.draw(G, sw, sh)
  if input.down("tab") and G.menu == nil then H.scoreboard(G, sw, sh, N.list(V)) end
  if G.menu == "buy" then
    if not (alive and G.inBuy and V.buyLeft > 0 and (V.phase == "freeze" or V.phase == "live")) then
      closeMenu()
    else
      H.buyMenu(G, sw, sh, buy)
    end
  elseif G.menu == "pause" then pauseMenu(sw, sh)
  elseif G.menu == "team" then teamMenu(sw, sh)
  elseif G.menu == "invite" then inviteMenu(sw, sh)
  elseif not locked then
    H.center("Нажмите, чтобы продолжить", sw / 2, sh / 2 + 40, "#FFFFFFFF", 0.6)
  end
end

-- --- Сеть (хост и гость) ---

local function hostNet(now)
  local S = G.S
  -- Снимок 5 раз в секунду.
  if now - G.lastSnapSent >= N.RATE then
    local names = {}
    for name in pairs(G.clients) do names[#names + 1] = name end
    if #names > 0 then
      local snap = Sim.snapshot(S)
      if #names == 1 then N.send(now, names[1], snap) else N.send(now, nil, snap) end
    end
    G.lastSnapSent = now
  end
  -- Гости, пропавшие на 15 с, — заменить ботами.
  for name, c in pairs(G.clients) do
    if now - c.last > 15 then
      local p = S.players[c.id]
      if p then Sim.removeHuman(S, p) end
      G.clients[name] = nil
    end
  end
end

local function clientNet(now)
  if now - G.lastInputSent >= N.RATE and G.gotSnap then
    G.lastInputSent = now
    N.send(now, G.hostName, buildInput())
  end
  if now - G.lastSnap > 10 then leaveGame("Связь с хостом потеряна") end
end

function onMessage(from, data)
  if type(data) ~= "table" then return end
  local t = data.t
  local now = time.clock()
  if t == "find" then
    if G.role == "host" then
      N.send(now, from, {t = "lobby", m = G.matchId, map = G.S.mapIndex, wm = G.S.wingman and 1 or 0, host = app.player,
        n = Sim.count(G.S, 1, false, true) + Sim.count(G.S, 2, false, true), ft = G.S.firstTo})
    end
  elseif t == "lobby" or t == "inv" then
    if G.role == nil and data.host then
      G.lobbies[data.host] = {host = data.host, m = data.m, map = data.map, n = data.n, ft = data.ft, wm = data.wm, t = now,
        invite = t == "inv"}
      if t == "inv" then
        toast(data.host .. " зовёт в Block Strike — «Найти игру»")
        sfx("round_start", 0.8)
      end
    end
  elseif t == "join" then
    if G.role == "host" and data.m == G.matchId then
      local p = Sim.addHuman(G.S, from)
      if p then
        G.clients[from] = {id = p.id, last = now}
        N.send(now, from, {t = "welcome", m = G.matchId, id = p.id, map = G.S.mapIndex})
      else
        N.send(now, from, {t = "full", m = G.matchId})
      end
    end
  elseif t == "welcome" then
    local j = G.joining
    if j and from == j.host and data.m == j.m and G.role == nil then
      G.joining = nil
      startGame("client", {m = data.m, host = from, map = data.map, id = data.id})
    end
  elseif t == "full" then
    if G.joining and from == G.joining.host then
      G.joining = nil
      toast("На сервере нет мест")
    end
  elseif t == "i" then
    local c = G.clients and G.clients[from]
    if G.role == "host" and c and data.m == G.matchId then
      c.last = now
      local p = G.S.players[c.id]
      if p and not p.bot then Sim.applyInput(G.S, p, data) end
    end
  elseif t == "bye" then
    local c = G.clients and G.clients[from]
    if G.role == "host" and c then
      Sim.removeHuman(G.S, G.S.players[c.id])
      G.clients[from] = nil
    end
  elseif t == "s" then
    if G.role == "client" and from == G.hostName and data.m == G.matchId then
      if data.mp ~= G.V.mapIndex then
        G.V = N.newView(data.mp)
        R.buildMap(G.V.map)
      end
      N.decode(G.V, data)
      G.lastSnap = now
      if not G.gotSnap then
        G.gotSnap = true
        for _, e in ipairs(G.V.events) do G.lastEvent = max(G.lastEvent, e[1]) end
        local P = G.V.players[G.me.id]
        if P then G.lastMoney = P.money end
      end
    end
  elseif t == "end" then
    if G.role == "client" and from == G.hostName then leaveGame("Хост завершил игру") end
  end
end

-- --- Обратные вызовы телефона ---

function onStart()
  screen.captureKeys(true)
  math.randomseed(time.now() % 1000000)
  loadSettings()
  R.init()
  VM.init()
end

function onTick()
  if G.state ~= "game" then return end
  local now = time.clock()
  if G.role == "client" then
    clientNet(now)
    return
  end
  local S = G.S
  local dt = max(0, min(0.1, now - G.lastTick))
  G.lastTick = now
  if G.role == "offline" and G.menu == "pause" then return end
  local p = S.players[G.me.id]
  if p then Sim.applyInput(S, p, buildInput()) end
  Sim.tick(S, dt)
  if G.role == "host" then hostNet(now) end
end

function onDraw(dt)
  local sw, sh = screen.width(), screen.height()
  G.now = time.clock()
  dt = min(dt or 0.016, 0.1)
  if G.state == "game" then
    gameFrame(dt, sw, sh)
  elseif G.state == "create" then createScreen(sw, sh)
  elseif G.state == "find" then findScreen(sw, sh)
  elseif G.state == "settings" then settingsScreen(sw, sh)
  elseif G.state == "help" then helpScreen(sw, sh)
  else titleScreen(sw, sh) end
end

function onKey(key)
  if G.state ~= "game" then return false end
  if hasDev and type(DEV) == "table" and DEV.key and DEV.key(G, key, Sim, {start = startGame}) then return true end
  local me, V = G.me, G.V
  local P = V and V.players[me.id]
  if not P then return true end
  if G.menu == "buy" then
    local n = tonumber(key)
    if n then
      if not G.buyCat then
        if D.BUY[n] then G.buyCat = n end
      else
        local item = D.BUY[G.buyCat].items[n]
        if item then buy(item) end
        if n == 0 then G.buyCat = nil end
      end
    elseif key == "b" then closeMenu()
    elseif key == "backspace" then G.buyCat = nil end
    return true
  end
  if G.menu == "team" then
    if key == "1" or key == "2" then
      pushAction("team", tonumber(key))
      closeMenu()
    end
    return true
  end
  if G.menu then return true end
  if key == "m" then
    G.menu = "team"
    input.lockMouse(false)
    return true
  end
  if not P.alive then return true end
  if key == "1" and P.pri > 0 then selectWeapon(P.pri)
  elseif key == "2" and P.sec > 0 then selectWeapon(P.sec)
  elseif key == "3" then selectWeapon(D.KNIFE)
  elseif key == "4" then
    local order = {D.HE, D.FLASH, D.SMOKE}
    local start = 0
    for i, wid in ipairs(order) do if wid == me.wpn then start = i end end
    for k = 1, 3 do
      local wid = order[(start + k - 1) % 3 + 1]
      if owned(P, wid) then
        selectWeapon(wid)
        break
      end
    end
  elseif key == "5" and P.bomb then selectWeapon(D.C4)
  elseif key == "q" then
    if owned(P, me.last) then selectWeapon(me.last) end
  elseif key == "r" then startReload()
  elseif key == "g" then
    if me.wpn == P.pri or me.wpn == P.sec or me.wpn == D.C4 then pushAction("drop") end
  elseif key == "b" then
    if M.inBuyZone(V.map, P.team, me.x, me.z) and V.buyLeft > 0 then
      G.menu, G.buyCat = "buy", nil
      input.lockMouse(false)
    else
      G.small = {text = V.buyLeft > 0 and "Покупать можно только на своей базе" or "Время покупки вышло", t = G.now}
    end
  end
  return true
end

function onClick(x, y, button)
  if G.state ~= "game" then return end
  local locked = input.mouseLocked()
  if not locked then
    if G.menu == nil then input.lockMouse(true) end
    return
  end
  local me = G.me
  local P = G.V.players[me.id]
  if not P then return end
  if not P.alive then
    if button == 1 then nextSpectate() end
    return
  end
  if button == 2 then
    local w = D.WEAPONS[me.wpn]
    if w.scope and me.switchT <= 0 and me.reload <= 0 then
      me.zoom = (me.zoom + 1) % (#w.scope + 1)
      sfx("deploy", 0.4)
    end
  end
end

function onMouseUnlock()
  if G.state == "game" and G.menu == nil then G.menu = "pause" end
end

function onBack()
  if G.state == "game" then
    if G.menu == "pause" then closeMenu()
    elseif G.menu == "invite" or G.menu == "team" then G.menu = "pause"
    elseif G.menu then closeMenu()
    else G.menu = "pause" end
    return true
  end
  if G.state ~= "title" then
    if G.state == "find" then G.joining = nil end
    G.state = "title"
    return true
  end
  return false
end

function onHide()
  if G.state == "game" and G.role == "offline" then G.menu = "pause" end
end
