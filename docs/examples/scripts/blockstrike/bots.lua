-- Боты: занимают места игроков, которых не хватает. Считаются только у хоста.
-- Террористы идут к выбранной точке (через случайный проход), несут и ставят бомбу, охраняют её.
-- Спецназ держит точки, после закладки идёт к бомбе и обезвреживает. Увидев врага — стреляют (с задержкой реакции
-- и точностью по уровню сложности), услышав выстрел — поворачиваются.
local D = require("data")
local M = require("maps")

local Bots = {}
local floor, sqrt, max, min, abs, random = math.floor, math.sqrt, math.max, math.min, math.abs, math.random
local atan2 = math.atan2 or function(y, x) return math.atan(y, x) end

-- Уровни: лёгкие, обычные, сложные.
local REACT = {0.6, 0.35, 0.2}         -- задержка до первого выстрела, с
local TURN = {220, 380, 650}           -- поворот, градусов в секунду
local SIGMA = {0.05, 0.026, 0.013}     -- разброс прицеливания, радианы
local HEAD = {0.1, 0.22, 0.42}         -- доля попаданий в голову
local BURST = {3, 4, 6}

local function yawTo(dx, dz) return math.deg(atan2(dx, dz)) end

local function angleDiff(a, b) return (a - b + 180) % 360 - 180 end

local function turn(p, yaw, pitch, speed, dt)
  local d = angleDiff(yaw, p.yaw)
  local step = speed * dt
  if abs(d) <= step then p.yaw = yaw else p.yaw = p.yaw + (d > 0 and step or -step) end
  if pitch then
    local dp = pitch - p.pitch
    if abs(dp) <= step then p.pitch = pitch else p.pitch = p.pitch + (dp > 0 and step or -step) end
  end
  p.yaw = (p.yaw + 180) % 360 - 180
end

function Bots.newAI()
  return {heard = {}}
end

function Bots.reset(p)
  local h = p.ai.heard
  p.ai = {heard = h, fireT = 0, burst = 0, pause = 0, reload = 0, strafe = 1, strafeT = 0, stuckT = 0, lastX = p.x,
    lastZ = p.z, nadeT = 2 + random() * 6}
end

function Bots.hurt(p, a)
  local ai = p.ai
  ai.alert = {x = a.x, z = a.z, t = 0}
  if not ai.enemy then ai.turnTo = a.id end
end

function Bots.flashed(p)
  p.ai.react = (p.ai.react or 0) + 1
end

-- --- Покупка ---

local function try(S, p, Sim, item)
  if Sim.buyCheck(S, p, item) then Sim.buy(S, p, item) return true end
  return false
end

function Bots.buy(S, p, Sim)
  local t = p.team
  local rifle = t == D.T and 11 or 12
  local cheap = t == D.T and 9 or 10
  if p.pri == 0 then
    if p.money >= 5750 and random() < 0.2 then try(S, p, Sim, D.AWP)
    elseif p.money >= D.WEAPONS[rifle].price + 650 then try(S, p, Sim, rifle)
    elseif p.money >= D.WEAPONS[cheap].price + 650 and random() < 0.6 then try(S, p, Sim, cheap)
    elseif p.money >= 2000 and S.round > 1 then try(S, p, Sim, random() < 0.5 and 7 or 6) end
  end
  if p.money >= 1000 then try(S, p, Sim, "helmet")
  elseif p.money >= 650 and (p.pri > 0 or S.round == 1 or random() < 0.4) then try(S, p, Sim, "kevlar") end
  if p.pri == 0 and p.money >= 700 and random() < 0.35 then try(S, p, Sim, 5)
  elseif p.pri == 0 and p.money >= 300 and random() < 0.3 then try(S, p, Sim, 4) end
  if t == D.CT and p.money >= 900 and random() < 0.6 then try(S, p, Sim, "kit") end
  for _ = 1, 2 do
    if p.money >= 500 then
      local r = random()
      try(S, p, Sim, r < 0.4 and D.HE or r < 0.75 and D.FLASH or D.SMOKE)
    end
  end
  p.wpn = p.pri > 0 and p.pri or p.sec
end

-- --- Начало раунда: план команды ---

function Bots.roundStart(S, Sim)
  local m = S.map
  local sites = m.sites
  local function pick() return sites[random(1, #sites)] end
  local function other(s)
    for _, o in ipairs(sites) do if o ~= s then return o end end
    return s
  end
  S.plan = {site = pick()}
  local ctSite = pick()
  for _, p in ipairs(S.players) do
    if p.bot then
      Bots.buy(S, p, Sim)
      local ai = p.ai
      if p.team == D.T then
        local site = S.plan.site
        if not p.bomb and random() < 0.25 then site = other(site) end
        ai.site = site
        local lanes = m.def.lanes[site]
        if lanes and random() < 0.8 then
          local l = lanes[random(1, #lanes)]
          ai.lane = {l[1] + 0.5, l[2] + 0.5}
        end
        ai.hold = M.randomCell(m, m.site[site], random)
        ai.waitT = random() * 4 -- не все выходят сразу
      else
        ai.site = ctSite
        ctSite = other(ctSite)
        ai.hold = M.randomCell(m, m.site[ai.site], random)
        ai.waitT = random() * 2
      end
    end
  end
end

-- --- Стрельба ---

local function gauss()
  return sqrt(-2 * math.log(max(1e-6, random()))) * math.cos(2 * math.pi * random())
end

local function shoot(S, p, v, dist, Sim, lvl)
  local w = D.WEAPONS[p.wpn]
  local ai = p.ai
  if p.wpn == D.KNIFE then
    ai.fireT = w.cool
    p.shots = p.shots + 1
    if dist < w.reach then Sim.damage(S, v, p, D.KNIFE, D.BODY, dist) end
    return
  end
  local am = p.ammo[p.wpn]
  if not am or am[1] <= 0 then return end
  am[1] = am[1] - 1
  p.shots = p.shots + 1
  ai.fireT = w.cool
  ai.burst = ai.burst + 1
  local moving = p.vx * p.vx + p.vz * p.vz > 1
  local spread = (p.wpn == D.AWP and w.scoped or w.spread) + (moving and w.move or 0) + min(ai.burst, 10) * 0.004
  local sigma = SIGMA[lvl] + spread
  local size = (v.crouch and 0.22 or 0.28) / max(dist, 0.5)
  if v.vx * v.vx + v.vz * v.vz > 4 then sigma = sigma * 1.25 end
  for _ = 1, w.pellets or 1 do
    local err = abs(gauss()) * sigma
    if err < size then
      local r = random()
      local head = HEAD[lvl] * (dist < 20 and 1 or 0.6)
      local part = r < head and D.HEAD or (r > 0.88 and D.LEGS or D.BODY)
      Sim.damage(S, v, p, p.wpn, part, dist)
      if not v.alive then break end
    end
  end
end

-- Бросок гранаты в точку (дуга за ~0,9 с).
local function throwAt(S, p, Sim, wid, tx, ty, tz)
  local ey = Sim.eye(p)
  local dx, dz = tx - p.x, tz - p.z
  local d = sqrt(dx * dx + dz * dz)
  local t = min(1.3, max(0.5, d / 13))
  local vx, vz = dx / t, dz / t
  local vy = (ty - ey + 0.5 * Sim.GRAVITY * t * t) / t
  Sim.throw(S, p, wid, p.x, ey, p.z, vx, vy, vz)
end

-- --- Ходьба по полю расстояний ---

local function stop(p)
  p.vx, p.vz = 0, 0
end

local function walk(S, p, flow, dt, Sim, faceMove, speedMul)
  local m = S.map
  local nx, nz = M.nextPoint(m, flow, p.x, p.z)
  if not nx then
    stop(p)
    return true
  end
  local dx, dz = nx - p.x, nz - p.z
  local d = sqrt(dx * dx + dz * dz)
  if d < 0.05 then
    stop(p)
    return false
  end
  local w = D.WEAPONS[p.wpn]
  local speed = Sim.BASE_SPEED * (w.speed or 1) * (speedMul or 1)
  local vx, vz = dx / d * speed, dz / d * speed
  local ox, oz = p.x, p.z
  M.move(m, p, vx * dt, vz * dt, Sim.RADIUS, 0.05)
  p.vx, p.vz = (p.x - ox) / dt, (p.z - oz) / dt
  if faceMove then turn(p, Sim.yawTo(dx, dz), 0, 300, dt) end
  -- Застрял — шаг в сторону.
  local ai = p.ai
  if (p.x - ai.lastX) ^ 2 + (p.z - ai.lastZ) ^ 2 < 0.0004 then
    ai.stuckT = ai.stuckT + dt
    if ai.stuckT > 0.6 then
      local i = M.nearestWalkable(m, p.x, p.z)
      local cx, cz = M.xz(m, i)
      p.x, p.z = p.x + (cx - p.x) * 0.5, p.z + (cz - p.z) * 0.5
      ai.stuckT = 0
    end
  else
    ai.stuckT = 0
  end
  ai.lastX, ai.lastZ = p.x, p.z
  return false
end

local function flowToCell(S, i)
  return M.flow(S.map, "c" .. i, {i})
end

local function atCell(S, p, i)
  local x, z = M.xz(S.map, i)
  return (p.x - x) ^ 2 + (p.z - z) ^ 2 < 0.36
end

-- Куда смотреть, стоя на месте: туда, откуда придёт враг (по полю расстояний до его базы).
local function threatYaw(S, p)
  local m = S.map
  local key = p.team == D.CT and "spawnT" or "spawnCT"
  local flow = M.flow(m, key, m.spawns[p.team == D.CT and 1 or 2])
  local x, z = p.x, p.z
  for _ = 1, 4 do
    local nx, nz = M.nextPoint(m, flow, x, z)
    if not nx then break end
    x, z = nx, nz
  end
  if (x - p.x) ^ 2 + (z - p.z) ^ 2 < 0.5 then return p.yaw end
  return yawTo(x - p.x, z - p.z)
end

local function bestGun(p)
  for _, wid in ipairs({p.pri, p.sec}) do
    local am = wid > 0 and p.ammo[wid]
    if am and (am[1] > 0 or am[2] > 0) then return wid end
  end
  return D.KNIFE
end

local function reload(p, w, ai)
  local am = p.ammo[p.wpn]
  if not am or am[2] <= 0 or am[1] >= w.clip then return end
  ai.reload = w.reload
end

local function finishReload(p)
  local w = D.WEAPONS[p.wpn]
  local am = p.ammo[p.wpn]
  if am and w.clip then
    local take = min(w.clip - am[1], am[2])
    am[1], am[2] = am[1] + take, am[2] - take
  end
end

-- --- Решение бота (каждый такт) ---

function Bots.think(S, p, dt, Sim)
  local ai = p.ai
  local lvl = S.botLevel
  local m = S.map
  p.using = false
  if S.phase == "freeze" or S.phase == "end" or S.phase == "over" or S.phase == "warmup" then
    stop(p)
    return
  end
  ai.fireT = max(0, (ai.fireT or 0) - dt)
  ai.pause = max(0, ai.pause - dt)
  ai.waitT = max(0, (ai.waitT or 0) - dt)
  ai.nadeT = (ai.nadeT or 5) - dt
  if ai.reload > 0 then
    ai.reload = ai.reload - dt
    if ai.reload <= 0 then finishReload(p) end
  end
  -- Слух: выстрелы врагов поблизости.
  for _, v in ipairs(S.players) do
    if v.team ~= p.team and v.alive then
      if ai.heard[v.id] and v.shots > ai.heard[v.id] then
        local d2 = (v.x - p.x) ^ 2 + (v.z - p.z) ^ 2
        if d2 < 28 * 28 and not ai.enemy then ai.alert = {x = v.x, z = v.z, t = 0} end
      end
      ai.heard[v.id] = v.shots
    end
  end
  -- Кого видим (ослеплённый — никого).
  local target, tdist = nil, 1e9
  local seen = S.vis[p.id]
  if seen and p.flash < 0.7 then
    for vid, d in pairs(seen) do
      local v = S.players[vid]
      if v and v.alive and d < tdist then
        local dyaw = abs(angleDiff(yawTo(v.x - p.x, v.z - p.z), p.yaw))
        if dyaw < 70 or d < 3 or vid == ai.enemy or vid == ai.turnTo then target, tdist = v, d end
      end
    end
  end
  if target then
    if ai.enemy ~= target.id then
      ai.enemy = target.id
      ai.react = (ai.react or 0) + REACT[lvl] * (0.7 + random() * 0.6)
      ai.burst = 0
    end
    ai.turnTo = nil
    ai.alert = {x = target.x, z = target.z, t = 0}
    Bots.fight(S, p, target, tdist, dt, Sim, lvl)
    return
  end
  ai.enemy = nil
  ai.react = 0
  ai.burst = 0
  if p.wpn ~= bestGun(p) and ai.reload <= 0 then p.wpn = bestGun(p) end
  local w = D.WEAPONS[p.wpn]
  if w.clip and ai.reload <= 0 then
    local am = p.ammo[p.wpn]
    if am and am[1] < w.clip * 0.4 then reload(p, w, ai) end
  end
  if ai.turnTo then
    local a = S.players[ai.turnTo]
    if a then turn(p, yawTo(a.x - p.x, a.z - p.z), 0, TURN[lvl], dt) end
  end
  if ai.alert then
    ai.alert.t = ai.alert.t + dt
    if ai.alert.t > 6 then ai.alert = nil end
  end
  Bots.objective(S, p, dt, Sim, lvl)
end

function Bots.fight(S, p, v, dist, dt, Sim, lvl)
  local ai = p.ai
  if p.wpn ~= bestGun(p) and ai.reload <= 0 then p.wpn = bestGun(p) end
  local w = D.WEAPONS[p.wpn]
  local ey = Sim.eye(p)
  local ty = v.y + (v.crouch and 0.5 or 0.7)
  local dx, dz = v.x - p.x, v.z - p.z
  local wantYaw = yawTo(dx, dz)
  local wantPitch = math.deg(atan2(ty - ey, max(0.1, dist)))
  turn(p, wantYaw, wantPitch, TURN[lvl], dt)
  ai.react = ai.react - dt
  -- Граната во врага на средней дистанции.
  if p.he > 0 and dist > 7 and dist < 18 and ai.nadeT <= 0 and random() < 0.02 then
    ai.nadeT = 8
    throwAt(S, p, Sim, D.HE, v.x, v.y, v.z)
    return
  end
  -- Движение: с ножом — к врагу; с винтовкой при стрельбе — стоп; иначе — шаг вбок.
  if p.wpn == D.KNIFE then
    local flow = M.flowTo(S.map, v.x, v.z)
    walk(S, p, flow, dt, Sim, false, 1)
  else
    local still = (w.auto or p.wpn == D.AWP) and ai.react <= 0.1
    ai.strafeT = ai.strafeT - dt
    if ai.strafeT <= 0 then
      ai.strafeT = 0.4 + random() * 0.8
      ai.strafe = random() < 0.5 and -1 or 1
    end
    if still or ai.reload > 0 and random() < 0.5 then
      stop(p)
    else
      local rad = math.rad(p.yaw)
      local rx, rz = math.cos(rad) * ai.strafe, -math.sin(rad) * ai.strafe
      local speed = Sim.BASE_SPEED * (w.speed or 1) * 0.6
      local ox, oz = p.x, p.z
      local hx, hz = M.move(S.map, p, rx * speed * dt, rz * speed * dt, Sim.RADIUS, 0.05)
      if hx or hz then ai.strafe = -ai.strafe end
      p.vx, p.vz = (p.x - ox) / dt, (p.z - oz) / dt
    end
  end
  if ai.reload > 0 then return end
  local am = p.ammo[p.wpn]
  if w.clip and (not am or am[1] <= 0) then
    if am and am[2] > 0 then reload(p, w, ai) else p.wpn = bestGun(p) end
    return
  end
  local err = abs(angleDiff(wantYaw, p.yaw))
  local burst = BURST[lvl]
  if ai.react <= 0 and ai.fireT <= 0 and ai.pause <= 0 and err < 5 + 40 / (dist + 2) then
    if p.wpn == D.KNIFE and dist > 1.6 then return end
    shoot(S, p, v, dist, Sim, lvl)
    if w.auto and dist > 10 and ai.burst >= burst then
      ai.burst = 0
      ai.pause = 0.25 + random() * 0.3
    end
  end
end

function Bots.objective(S, p, dt, Sim, lvl)
  local ai = p.ai
  local m = S.map
  local b = S.bomb
  if ai.waitT > 0 then
    stop(p)
    return
  end
  if p.team == D.T then
    if p.bomb then
      -- Несём бомбу на точку и ставим.
      if ai.lane and (p.x - ai.lane[1]) ^ 2 + (p.z - ai.lane[2]) ^ 2 < 2.5 then ai.lane = nil end
      if ai.lane then
        local flow = M.flowTo(m, ai.lane[1], ai.lane[2])
        walk(S, p, flow, dt, Sim, true)
        return
      end
      local site = M.site(m, p.x, p.z)
      if site and S.phase == "live" then
        stop(p)
        p.using = true
        turn(p, p.yaw, -40, 200, dt)
        return
      end
      walk(S, p, flowToCell(S, ai.hold), dt, Sim, true)
      return
    end
    if b.state == "dropped" then
      -- Ближайший террорист поднимает бомбу.
      local closest, cd = nil, 1e9
      for _, o in ipairs(S.players) do
        if o.alive and o.team == D.T and o.bot then
          local d = (o.x - b.x) ^ 2 + (o.z - b.z) ^ 2
          if d < cd then closest, cd = o, d end
        end
      end
      if closest == p then
        walk(S, p, (M.flowTo(m, b.x, b.z)), dt, Sim, true)
        return
      end
    end
    if b.state == "planted" then
      if not ai.guard then
        local near = {}
        for _, i in ipairs(m.site[b.site or m.sites[1]]) do
          local x, z = M.xz(m, i)
          if M.walkable(m.c[i]) and (x - b.x) ^ 2 + (z - b.z) ^ 2 < 40 then near[#near + 1] = i end
        end
        ai.guard = #near > 0 and near[random(1, #near)] or M.nearestWalkable(m, b.x, b.z)
      end
      if atCell(S, p, ai.guard) then
        stop(p)
        turn(p, threatYaw(S, p), 0, 200, dt)
      else
        walk(S, p, flowToCell(S, ai.guard), dt, Sim, true)
      end
      return
    end
    if ai.lane and (p.x - ai.lane[1]) ^ 2 + (p.z - ai.lane[2]) ^ 2 < 2.5 then
      ai.lane = nil
      -- Перед выходом на точку — граната.
      if ai.nadeT <= 0 and (p.fl > 0 or p.sm > 0) then
        ai.nadeT = 6
        local c = m.siteCenter[ai.site]
        throwAt(S, p, Sim, p.sm > 0 and random() < 0.5 and D.SMOKE or (p.fl > 0 and D.FLASH or D.SMOKE), c[1], 0.3, c[2])
      end
    end
    if ai.lane then
      walk(S, p, (M.flowTo(m, ai.lane[1], ai.lane[2])), dt, Sim, true)
    elseif atCell(S, p, ai.hold) then
      stop(p)
      turn(p, threatYaw(S, p), 0, 150, dt)
    else
      walk(S, p, flowToCell(S, ai.hold), dt, Sim, true)
    end
    return
  end
  -- Спецназ.
  if b.state == "planted" then
    local d2 = (p.x - b.x) ^ 2 + (p.z - b.z) ^ 2
    if d2 < 1.0 then
      stop(p)
      p.using = true
      turn(p, p.yaw, -45, 200, dt)
    else
      walk(S, p, (M.flowTo(m, b.x, b.z)), dt, Sim, true)
    end
    return
  end
  if ai.alert and random() < 0.002 * lvl then
    -- иногда идут проверить шум
    ai.hold = M.nearestWalkable(m, ai.alert.x, ai.alert.z)
  end
  if atCell(S, p, ai.hold) then
    stop(p)
    if ai.alert then
      turn(p, yawTo(ai.alert.x - p.x, ai.alert.z - p.z), 0, TURN[lvl] * 0.5, dt)
    else
      turn(p, threatYaw(S, p), 0, 150, dt)
    end
  else
    walk(S, p, flowToCell(S, ai.hold), dt, Sim, true)
  end
end

return Bots
