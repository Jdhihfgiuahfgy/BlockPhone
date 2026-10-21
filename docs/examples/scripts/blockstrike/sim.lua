-- Игра на хосте: раунды, экономика, бомба, гранаты, урон, боты. Без графики и сети — их делает main.lua.
-- Хост — телефон игрока, который создал игру: он считает всё, остальные присылают ему свои действия.
local D = require("data")
local M = require("maps")
local Bots = require("bots")

local Sim = {}
local floor, sqrt, max, min, abs = math.floor, math.sqrt, math.max, math.min, math.abs
local atan2 = math.atan2 or function(y, x) return math.atan(y, x) end

Sim.RADIUS, Sim.EYE, Sim.EYE_CROUCH, Sim.GRAVITY, Sim.JUMP = 0.25, 0.85, 0.6, 14, 4.6
Sim.BASE_SPEED = 3.4
Sim.PHASES = {"warmup", "freeze", "live", "planted", "end", "over"}
Sim.PHASE_CODE = {warmup = 1, freeze = 2, live = 3, planted = 4, ["end"] = 5, over = 6}
Sim.BOMB_STATES = {"none", "carried", "dropped", "planted", "defused", "exploded"}
Sim.BOMB_CODE = {none = 1, carried = 2, dropped = 3, planted = 4, defused = 5, exploded = 6}
local NADE_FUSE = {[14] = 1.6, [15] = 1.5, [16] = 2.5}

function Sim.yawTo(dx, dz) return math.deg(atan2(dx, dz)) end

function Sim.forward(yaw, pitch)
  local y, p = math.rad(yaw), math.rad(pitch or 0)
  return math.sin(y) * math.cos(p), math.sin(p), math.cos(y) * math.cos(p)
end

function Sim.eye(p) return p.y + (p.crouch and Sim.EYE_CROUCH or Sim.EYE) end

function Sim.new(o)
  local S = {map = M.load(o.map), mapIndex = o.map, firstTo = o.firstTo or 8, botLevel = o.botLevel or 2,
    teamSize = o.teamSize or 5, players = {}, phase = "warmup", timer = 3, round = 0, score = {0, 0},
    lossStreak = {0, 0}, bomb = {state = "none", x = 0, y = 0, z = 0, timer = 0, progress = 0, carrier = 0, defuser = 0},
    nades = {}, smokes = {}, drops = {}, events = {}, eventSeq = 0, time = 0, nextDrop = 1, swapped = false,
    matchId = o.matchId or 1, winner = 0, buyUntil = 0, visT = 0, vis = {}, botNames = {}, plan = {}}
  return S
end

-- --- События (для звуков, ленты убийств и сообщений у всех игроков) ---

function Sim.event(S, kind, a, b, c, d)
  S.eventSeq = S.eventSeq + 1
  S.events[#S.events + 1] = {S.eventSeq, kind, a or 0, b or 0, c or 0, d or 0, time = S.time}
  while #S.events > 40 do table.remove(S.events, 1) end
end

-- --- Игроки ---

local function newPlayer(S, name, bot, team)
  local p = {id = #S.players + 1, name = name, bot = bot, team = team, alive = false, hp = 0, armor = 0, helmet = false,
    kit = false, money = D.MONEY_START, kills = 0, deaths = 0, x = 0, y = 0, z = 0, vx = 0, vz = 0, vy = 0, yaw = 0, pitch = 0,
    crouch = false, wpn = D.KNIFE, pri = 0, sec = 0, he = 0, fl = 0, sm = 0, bomb = false, using = false, flash = 0,
    shots = 0, tp = 0, ack = 0, hitAck = 0, plantT = 0, spot = false, ammo = {}, lastInput = S.time, nextTeam = 0}
  if bot then p.ai = Bots.newAI() end
  S.players[p.id] = p
  return p
end

function Sim.botName(S)
  for _ = 1, 50 do
    local n = "BOT " .. D.BOT_NAMES[math.random(1, #D.BOT_NAMES)]
    if not S.botNames[n] then
      S.botNames[n] = true
      return n
    end
  end
  return "BOT " .. math.random(100, 999)
end

function Sim.count(S, team, onlyAlive, onlyHumans)
  local n = 0
  for _, p in ipairs(S.players) do
    if p.team == team and (not onlyAlive or p.alive) and (not onlyHumans or not p.bot) then n = n + 1 end
  end
  return n
end

-- Добрать ботов до нужного размера команд.
function Sim.fillBots(S)
  for team = 1, 2 do
    while Sim.count(S, team) < S.teamSize and #S.players < 10 do
      newPlayer(S, Sim.botName(S), true, team)
    end
  end
end

-- Человек входит в игру: занимает место бота в команде, где людей меньше (бот уходит, его тело остаётся игроку).
function Sim.addHuman(S, name, wantTeam)
  for _, p in ipairs(S.players) do
    if not p.bot and p.name == name then return p end
  end
  local team = wantTeam
  if not team then
    local h1, h2 = Sim.count(S, 1, false, true), Sim.count(S, 2, false, true)
    team = h1 <= h2 and 1 or 2
  end
  for _, t in ipairs({team, 3 - team}) do
    for _, p in ipairs(S.players) do
      if p.bot and p.team == t then
        S.botNames[p.name] = nil
        p.bot, p.name, p.ai, p.lastInput = false, name, nil, S.time
        p.kills, p.deaths = 0, 0
        Sim.event(S, "join", p.id)
        return p
      end
    end
  end
  if #S.players < 10 then
    local p = newPlayer(S, name, false, team)
    if S.phase == "freeze" or S.phase == "warmup" then Sim.spawn(S, p, true) end
    Sim.event(S, "join", p.id)
    return p
  end
  return nil
end

-- Человек вышел: его место снова занимает бот.
function Sim.removeHuman(S, p)
  if p.bot then return end
  Sim.event(S, "left", p.id)
  p.bot, p.name, p.ai, p.using = true, Sim.botName(S), Bots.newAI(), false
end

-- --- Раунды ---

local function defaultPistol(team) return team == D.T and D.GLOCK or D.USP end

function Sim.giveAmmo(p, wid)
  local w = D.WEAPONS[wid]
  if w and w.clip then p.ammo[wid] = {w.clip, w.reserve} end
end

function Sim.strip(p)
  p.pri, p.sec, p.armor, p.helmet, p.kit, p.he, p.fl, p.sm = 0, defaultPistol(p.team), 0, false, false, 0, 0, 0
  p.ammo = {}
end

function Sim.bestWeapon(p)
  if p.pri > 0 then return p.pri end
  if p.sec > 0 then return p.sec end
  return D.KNIFE
end

function Sim.spawn(S, p, reset)
  if reset or not p.alive then Sim.strip(p) end
  local m = S.map
  local list = m.spawns[p.team]
  local x, z
  for _ = 1, 40 do
    x, z = M.xz(m, list[math.random(1, #list)])
    local free = M.walkable(m.c[M.idx(m, x, z)])
    for _, o in ipairs(S.players) do
      if o ~= p and o.alive and o.team == p.team and abs(o.x - x) < 0.9 and abs(o.z - z) < 0.9 then free = false end
    end
    if free then break end
  end
  p.x, p.z, p.y, p.vx, p.vz, p.vy = x, z, 0, 0, 0, 0
  local cx, cz = m.W / 2, m.H / 2
  p.yaw, p.pitch = Sim.yawTo(cx - x, cz - z), 0
  p.alive, p.hp, p.flash, p.using, p.crouch, p.plantT = true, 100, 0, false, false, 0
  p.tp = p.tp + 1
  p.wpn = Sim.bestWeapon(p)
  if p.pri > 0 and not p.ammo[p.pri] then Sim.giveAmmo(p, p.pri) end
  if p.sec > 0 then Sim.giveAmmo(p, p.sec) end
  if p.pri > 0 then Sim.giveAmmo(p, p.pri) end
  if p.ai then Bots.reset(p) end
end

function Sim.startRound(S)
  local half = not S.swapped and S.round == S.firstTo - 1
  S.round = S.round + 1
  if half then
    S.swapped = true
    S.score[1], S.score[2] = S.score[2], S.score[1]
    S.lossStreak = {0, 0}
    for _, p in ipairs(S.players) do
      p.team = 3 - p.team
      p.money = D.MONEY_START
      p.alive = false
      p.nextTeam = 0
    end
    Sim.event(S, "half")
  end
  for _, p in ipairs(S.players) do
    if p.nextTeam > 0 and p.nextTeam ~= p.team then
      p.team, p.alive = p.nextTeam, false
    end
    p.nextTeam = 0
    p.bomb = false
    Sim.spawn(S, p, S.round == 1)
  end
  S.nades, S.smokes, S.drops = {}, {}, {}
  -- Бомба — случайному террористу.
  local ts = {}
  for _, p in ipairs(S.players) do if p.team == D.T then ts[#ts + 1] = p end end
  S.bomb = {state = "none", x = 0, y = 0, z = 0, timer = 0, progress = 0, carrier = 0, defuser = 0}
  if #ts > 0 then
    local c = ts[math.random(1, #ts)]
    c.bomb = true
    S.bomb.state, S.bomb.carrier = "carried", c.id
  end
  S.phase, S.timer = "freeze", D.FREEZE_TIME
  S.buyUntil = S.time + D.BUY_TIME
  S.roundStart = S.time
  Bots.roundStart(S, Sim)
  Sim.event(S, "round", S.round)
end

function Sim.restartMatch(S)
  S.round, S.score, S.lossStreak, S.swapped, S.winner = 0, {0, 0}, {0, 0}, false, 0
  for _, p in ipairs(S.players) do
    p.money, p.kills, p.deaths, p.alive = D.MONEY_START, 0, 0, false
  end
  Sim.startRound(S)
end

local function addMoney(p, amount) p.money = max(0, min(D.MONEY_MAX, p.money + amount)) end

function Sim.endRound(S, winner, reason)
  if S.phase == "end" or S.phase == "over" then return end
  local loser = 3 - winner
  S.score[winner] = S.score[winner] + 1
  local streak = min(S.lossStreak[loser] + 1, #D.LOSS_BONUS)
  for _, p in ipairs(S.players) do
    if p.team == winner then
      addMoney(p, D.WIN_REWARD[reason])
    else
      addMoney(p, D.LOSS_BONUS[streak])
      if p.team == D.T and (S.bomb.state == "planted" or S.bomb.state == "defused") then addMoney(p, D.PLANTED_LOSS_BONUS) end
    end
  end
  S.lossStreak[loser] = S.lossStreak[loser] + 1
  S.lossStreak[winner] = max(0, S.lossStreak[winner] - 1)
  S.phase, S.timer = "end", D.END_TIME
  for _, p in ipairs(S.players) do p.using = false end
  Sim.event(S, "end", winner, reason)
  if S.score[winner] >= S.firstTo then
    S.winner = winner
    Sim.event(S, "over", winner)
  end
end

function Sim.checkRound(S)
  if S.phase ~= "live" and S.phase ~= "planted" then return end
  local t, ct = Sim.count(S, D.T, true), Sim.count(S, D.CT, true)
  if ct == 0 then
    Sim.endRound(S, D.T, "elim")
  elseif t == 0 and S.phase == "live" then
    Sim.endRound(S, D.CT, "elim")
  end
end

-- --- Урон ---

function Sim.damage(S, v, a, wid, part, dist)
  if not v.alive or S.phase == "end" or S.phase == "over" then return end
  if a and a ~= v and a.team == v.team then return end -- по своим урона нет
  local w = D.WEAPONS[wid]
  local dmg = (w.dmg or 0) * D.PART_MUL[part]
  if w.range and dist then dmg = dmg * w.range ^ (dist / 10) end
  if v.armor > 0 and (part == D.BODY or (part == D.HEAD and v.helmet)) then
    local take = dmg * (w.pen or 0.5)
    v.armor = max(0, v.armor - floor((dmg - take) / 2))
    dmg = take
  end
  Sim.hurt(S, v, a, wid, floor(dmg + 0.5), part == D.HEAD)
end

function Sim.hurt(S, v, a, wid, dmg, head)
  if not v.alive or dmg <= 0 then return end
  v.hp = v.hp - dmg
  if v.ai and a and a ~= v then Bots.hurt(v, a) end
  if v.hp <= 0 then Sim.kill(S, v, a, wid, head) end
end

function Sim.dropWeapon(S, p, wid, toss)
  if not wid or wid <= 0 then return end
  local fx, _, fz = Sim.forward(p.yaw, 0)
  local d = {id = S.nextDrop, wid = wid, x = p.x, z = p.z, y = p.y, by = p.id, t = S.time}
  if toss then
    local tx, tz = p.x + fx * 1.2, p.z + fz * 1.2
    if M.ground(S.map, tx, tz, 0.1) <= p.y + 0.5 then d.x, d.z = tx, tz end
    d.y = M.ground(S.map, d.x, d.z, 0.1)
  end
  S.nextDrop = S.nextDrop + 1
  S.drops[#S.drops + 1] = d
  while #S.drops > 24 do table.remove(S.drops, 1) end
end

function Sim.dropBomb(S, p)
  if not p.bomb then return end
  p.bomb = false
  S.bomb.state, S.bomb.carrier = "dropped", 0
  S.bomb.x, S.bomb.y, S.bomb.z = p.x, p.y, p.z
  if p.wpn == D.C4 then p.wpn = Sim.bestWeapon(p) end
end

function Sim.kill(S, v, a, wid, head)
  v.alive, v.hp, v.using, v.deaths, v.flash = false, 0, false, v.deaths + 1, 0
  if v.pri > 0 then Sim.dropWeapon(S, v, v.pri) v.pri = 0
  elseif v.sec > 0 then Sim.dropWeapon(S, v, v.sec) v.sec = 0 end
  Sim.dropBomb(S, v)
  if S.bomb.defuser == v.id then S.bomb.defuser, S.bomb.progress = 0, 0 end
  if a and a ~= v then
    if a.team ~= v.team then
      a.kills = a.kills + 1
      addMoney(a, D.WEAPONS[wid].reward or 300)
    else
      a.kills = a.kills - 1
      addMoney(a, -300)
    end
  end
  Sim.event(S, "k", a and a.id or 0, v.id, wid, head and 1 or 0)
  Sim.checkRound(S)
end

-- --- Действия игроков (покупка, выброс, гранаты, смена команды) ---

function Sim.canBuy(S, p)
  return p.alive and (S.phase == "freeze" or ((S.phase == "live") and S.time < S.buyUntil))
      and M.inBuyZone(S.map, p.team, p.x, p.z)
end

-- Можно ли купить предмет; возвращает цену или nil и причину.
function Sim.buyCheck(S, p, item)
  local team = D.itemTeam(item)
  if team and team ~= p.team then return nil, "Не для вашей команды" end
  local price = D.itemPrice(item)
  if item == "helmet" and p.armor >= 100 and not p.helmet then price = 350 end
  if item == "kevlar" and p.armor >= 100 then return nil, "Уже есть" end
  if item == "helmet" and p.armor >= 100 and p.helmet then return nil, "Уже есть" end
  if item == "kit" and p.kit then return nil, "Уже есть" end
  if type(item) == "number" then
    local w = D.WEAPONS[item]
    if w.slot == 1 and p.pri == item then return nil, "Уже есть" end
    if w.slot == 2 and p.sec == item then return nil, "Уже есть" end
    if w.slot == 4 then
      local f = D.NADE_FIELD[item]
      if p[f] >= w.max then return nil, "Больше нельзя" end
      if p.he + p.fl + p.sm >= 4 then return nil, "Не больше 4 гранат" end
    end
  end
  if p.money < price then return nil, "Не хватает денег" end
  return price
end

function Sim.buy(S, p, item)
  if not Sim.canBuy(S, p) then return false end
  local price = Sim.buyCheck(S, p, item)
  if not price then return false end
  p.money = p.money - price
  if item == "kevlar" then p.armor = 100
  elseif item == "helmet" then p.armor, p.helmet = 100, true
  elseif item == "kit" then p.kit = true
  else
    local w = D.WEAPONS[item]
    if w.slot == 1 then
      if p.pri > 0 then Sim.dropWeapon(S, p, p.pri, true) end
      p.pri = item
      Sim.giveAmmo(p, item)
      p.wpn = item
    elseif w.slot == 2 then
      if p.sec > 0 then Sim.dropWeapon(S, p, p.sec, true) end
      p.sec = item
      Sim.giveAmmo(p, item)
      p.wpn = item
    else
      local f = D.NADE_FIELD[item]
      p[f] = p[f] + 1
    end
  end
  return true
end

function Sim.throw(S, p, wid, x, y, z, vx, vy, vz)
  local f = D.NADE_FIELD[wid]
  if not f or p[f] <= 0 or not p.alive then return end
  p[f] = p[f] - 1
  local sp = sqrt(vx * vx + vy * vy + vz * vz)
  if sp > 16 then vx, vy, vz = vx / sp * 16, vy / sp * 16, vz / sp * 16 end
  S.nades[#S.nades + 1] = {kind = wid, x = x, y = y, z = z, vx = vx, vy = vy, vz = vz, t = 0, owner = p.id}
  if p.wpn == wid and p[f] <= 0 then p.wpn = Sim.bestWeapon(p) end
end

function Sim.action(S, p, a)
  local kind = a[2]
  if kind == "buy" then
    Sim.buy(S, p, tonumber(a[3]) or a[3])
  elseif kind == "drop" then
    if not p.alive then return end
    if p.wpn == D.C4 and p.bomb then
      Sim.dropBomb(S, p)
      local fx, _, fz = Sim.forward(p.yaw, 0)
      S.bomb.x, S.bomb.z = p.x + fx * 0.8, p.z + fz * 0.8
      if M.ground(S.map, S.bomb.x, S.bomb.z, 0.1) > p.y + 0.5 then S.bomb.x, S.bomb.z = p.x, p.z end
      S.bomb.y = M.ground(S.map, S.bomb.x, S.bomb.z, 0.1)
      p.bombBlock = S.time + 1
    elseif p.wpn == p.pri and p.pri > 0 then
      Sim.dropWeapon(S, p, p.pri, true)
      p.pri = 0
      p.wpn = Sim.bestWeapon(p)
    elseif p.wpn == p.sec and p.sec > 0 then
      Sim.dropWeapon(S, p, p.sec, true)
      p.sec = 0
      p.wpn = Sim.bestWeapon(p)
    end
  elseif kind == "nade" then
    Sim.throw(S, p, a[3], a[4] / 100, a[5] / 100, a[6] / 100, a[7] / 100, a[8] / 100, a[9] / 100)
  elseif kind == "team" then
    local t = tonumber(a[3])
    if t == 1 or t == 2 then
      if S.phase == "warmup" or (S.phase == "freeze" and S.round <= 1) then
        p.team = t
        Sim.spawn(S, p, true)
      else
        p.nextTeam = t
      end
    end
  end
end

-- Ввод человека (каждые ~0,2 с по сети или каждый тик — у хоста): позиция, взгляд, оружие, попадания, действия.
function Sim.applyInput(S, p, inp)
  p.lastInput = S.time
  if p.bot then return end
  if p.alive and inp.tp == p.tp and S.phase ~= "freeze" then
    local x, z = inp.x / 100, inp.z / 100
    -- Простая проверка: позиция внутри карты и не в стене.
    if M.ground(S.map, x, z, 0.2) < 50 then
      p.x, p.y, p.z = x, inp.y / 100, z
      p.vx, p.vz = (inp.vx or 0) / 100, (inp.vz or 0) / 100
    end
  end
  if p.alive then
    p.yaw, p.pitch, p.crouch = inp.yaw or p.yaw, inp.pitch or p.pitch, inp.c == 1
    local w = inp.w
    if w == D.KNIFE or (w == p.pri and w > 0) or (w == p.sec and w > 0) or (w == D.C4 and p.bomb)
        or (D.NADE_FIELD[w] and p[D.NADE_FIELD[w]] > 0) then
      p.wpn = w
    end
    p.using = inp.u == 1
    if inp.sh and inp.sh > p.shots then p.shots = inp.sh end
  end
  if inp.h then
    for _, h in ipairs(inp.h) do
      if h[1] > p.hitAck then
        p.hitAck = h[1]
        local v = S.players[h[2]]
        if v and p.alive and (h[4] == p.wpn or h[4] == p.pri or h[4] == p.sec or h[4] == D.KNIFE) then
          Sim.damage(S, v, p, h[4], h[3], h[5] / 10)
        end
      end
    end
  end
  if inp.a then
    for _, a in ipairs(inp.a) do
      if a[1] > p.ack then
        p.ack = a[1]
        Sim.action(S, p, a)
      end
    end
  end
end

-- --- Гранаты и дым ---

local function explodeHE(S, n)
  Sim.event(S, "he", floor(n.x * 100), floor(n.y * 100), floor(n.z * 100))
  local owner = S.players[n.owner]
  for _, p in ipairs(S.players) do
    if p.alive then
      local dx, dy, dz = p.x - n.x, p.y + 0.5 - n.y, p.z - n.z
      local d = sqrt(dx * dx + dy * dy + dz * dz)
      if d < 4.5 and M.los(S.map, n.x, n.y + 0.2, n.z, p.x, p.y + 0.5, p.z) then
        local dmg = 98 * (1 - d / 4.5)
        if p.armor > 0 then
          p.armor = max(0, p.armor - floor(dmg * 0.25))
          dmg = dmg * 0.5
        end
        if owner and owner.team == p.team and owner ~= p then dmg = 0 end
        Sim.hurt(S, p, owner, D.HE, floor(dmg), false)
      end
    end
  end
end

local function explodeFlash(S, n)
  Sim.event(S, "fl", floor(n.x * 100), floor(n.y * 100), floor(n.z * 100))
  for _, p in ipairs(S.players) do
    if p.alive then
      local ey = Sim.eye(p)
      local dx, dy, dz = n.x - p.x, n.y - ey, n.z - p.z
      local d = sqrt(dx * dx + dy * dy + dz * dz)
      if d < 24 and M.los(S.map, n.x, n.y, n.z, p.x, ey, p.z, S.smokes) then
        local fx, fy, fz = Sim.forward(p.yaw, p.pitch)
        local dot = d > 0.01 and (dx * fx + dy * fy + dz * fz) / d or 1
        local k = dot > 0.5 and 1 or (dot > 0 and 0.55 or 0.2)
        local dur = 4.5 * k * (1 - d / 26)
        if dur > p.flash then p.flash = dur end
        if p.ai then Bots.flashed(p) end
      end
    end
  end
end

local function updateNades(S, dt)
  local m = S.map
  for k = #S.nades, 1, -1 do
    local n = S.nades[k]
    n.t = n.t + dt
    for _ = 1, 2 do
      local h = dt / 2
      n.vy = n.vy - Sim.GRAVITY * h
      local nx = n.x + n.vx * h
      if M.ground(m, nx, n.z, 0.05) > n.y then n.vx = -n.vx * 0.45 else n.x = nx end
      local nz = n.z + n.vz * h
      if M.ground(m, n.x, nz, 0.05) > n.y then n.vz = -n.vz * 0.45 else n.z = nz end
      n.y = n.y + n.vy * h
      local g = M.ground(m, n.x, n.z, 0.05)
      if n.y < g + 0.05 then
        n.y = g + 0.05
        if n.vy < 0 then
          n.vy = -n.vy * 0.35
          n.vx, n.vz = n.vx * 0.7, n.vz * 0.7
        end
      end
      if m.def.ceiling and n.y > M.WALL_H - 0.1 then n.vy = -abs(n.vy) * 0.5 end
    end
    local speed = abs(n.vx) + abs(n.vy) + abs(n.vz)
    local boom = n.t >= NADE_FUSE[n.kind] and (n.kind ~= D.SMOKE or speed < 1.5 or n.t > 4)
    if boom then
      table.remove(S.nades, k)
      if n.kind == D.HE then explodeHE(S, n)
      elseif n.kind == D.FLASH then explodeFlash(S, n)
      else
        S.smokes[#S.smokes + 1] = {x = n.x, y = n.y, z = n.z, t = 18, r = 0}
        Sim.event(S, "sm", floor(n.x * 100), floor(n.z * 100))
      end
    end
  end
  for k = #S.smokes, 1, -1 do
    local s = S.smokes[k]
    s.t = s.t - dt
    s.r = 2.4 * min(1, (18 - s.t) / 1.2, s.t / 1.5)
    if s.t <= 0 then table.remove(S.smokes, k) end
  end
end

-- --- Бомба ---

local function updateBomb(S, dt)
  local b = S.bomb
  if b.state == "carried" then
    local c = S.players[b.carrier]
    if c then b.x, b.y, b.z = c.x, c.y, c.z end
  elseif b.state == "dropped" then
    for _, p in ipairs(S.players) do
      if p.alive and p.team == D.T and not (p.bombBlock and S.time < p.bombBlock) then
        local dx, dz = p.x - b.x, p.z - b.z
        if dx * dx + dz * dz < 0.64 and abs(p.y - b.y) < 0.8 then
          p.bomb = true
          b.state, b.carrier = "carried", p.id
          Sim.event(S, "bomb", p.id)
          break
        end
      end
    end
  elseif b.state == "planted" then
    b.timer = b.timer - dt
    -- Обезвреживание: один спецназовец рядом с бомбой держит E.
    local d = S.players[b.defuser]
    if d and not (d.alive and d.using and (d.x - b.x) ^ 2 + (d.z - b.z) ^ 2 < 1.7) then
      b.defuser, b.progress, d = 0, 0, nil
    end
    if not d then
      for _, p in ipairs(S.players) do
        if p.alive and p.team == D.CT and p.using and (p.x - b.x) ^ 2 + (p.z - b.z) ^ 2 < 1.7 then
          b.defuser, b.progress, d = p.id, 0, p
          Sim.event(S, "defusing", p.id, p.kit and 1 or 0)
          break
        end
      end
    end
    if d then
      b.progress = b.progress + dt
      if b.progress >= (d.kit and D.DEFUSE_KIT_TIME or D.DEFUSE_TIME) and b.timer > 0 then
        b.state = "defused"
        addMoney(d, D.DEFUSE_REWARD)
        Sim.event(S, "defuse", d.id)
        Sim.endRound(S, D.CT, "defuse")
        return
      end
    end
    if b.timer <= 0 then
      b.state = "exploded"
      Sim.event(S, "boom", floor(b.x * 100), floor(b.z * 100))
      for _, p in ipairs(S.players) do
        if p.alive then
          local dist = sqrt((p.x - b.x) ^ 2 + (p.z - b.z) ^ 2)
          if dist < 11 then Sim.hurt(S, p, nil, D.C4, floor(500 * (1 - dist / 11) ^ 1.5), false) end
        end
      end
      if S.phase == "planted" then Sim.endRound(S, D.T, "bomb") end
    end
  end
  -- Закладка: у игрока бомба, он на точке, стоит и держит E.
  if S.phase == "live" and b.state == "carried" then
    local p = S.players[b.carrier]
    local site = p and M.site(S.map, p.x, p.z)
    if p and p.alive and p.using and site and p.vx * p.vx + p.vz * p.vz < 0.5 then
      if p.plantT == 0 then Sim.event(S, "planting", p.id) end
      p.plantT = p.plantT + dt
      b.progress = p.plantT
      if p.plantT >= D.PLANT_TIME then
        p.bomb, p.plantT = false, 0
        if p.wpn == D.C4 then p.wpn = Sim.bestWeapon(p) end
        b.state, b.carrier, b.timer, b.progress = "planted", 0, D.BOMB_TIME, 0
        b.x, b.z, b.y = p.x, p.z, M.ground(S.map, p.x, p.z, 0.1)
        b.site = site
        addMoney(p, D.PLANT_REWARD)
        S.phase = "planted"
        Sim.event(S, "plant", p.id, site == "A" and 1 or 2)
      end
    elseif p then
      p.plantT, b.progress = 0, 0
    end
  end
end

-- --- Видимость (радар и боты): кто кого видит, раз в 0,25 с ---

local function updateVisibility(S)
  local vis = {}
  for _, a in ipairs(S.players) do
    vis[a.id] = {}
    a.spotNow = false
  end
  for _, a in ipairs(S.players) do
    if a.alive then
      local ey = Sim.eye(a)
      local fx, _, fz = Sim.forward(a.yaw, 0)
      for _, v in ipairs(S.players) do
        if v.alive and v.team ~= a.team then
          local dx, dz = v.x - a.x, v.z - a.z
          local d2 = dx * dx + dz * dz
          if d2 < 60 * 60 then
            local vy = v.y + (v.crouch and 0.55 or 0.75)
            local seen = M.los(S.map, a.x, ey, a.z, v.x, vy, v.z, S.smokes)
                or M.los(S.map, a.x, ey, a.z, v.x, v.y + 0.35, v.z, S.smokes)
            if seen then
              vis[a.id][v.id] = sqrt(d2)
              local d = sqrt(d2)
              if d < 2 or (dx * fx + dz * fz) / d > 0.35 then v.spotNow = true end
            end
          end
        end
      end
    end
  end
  for _, p in ipairs(S.players) do p.spot = p.spotNow end
  S.vis = vis
end

-- --- Такт (20 раз в секунду) ---

function Sim.tick(S, dt)
  S.time = S.time + dt
  S.timer = S.timer - dt
  if S.phase == "warmup" then
    if S.timer <= 0 then Sim.startRound(S) end
    return
  elseif S.phase == "freeze" then
    if S.timer <= 0 then
      S.phase, S.timer = "live", D.ROUND_TIME
      Sim.event(S, "go")
    end
  elseif S.phase == "live" then
    if S.timer <= 0 then
      S.timer = 0
      Sim.endRound(S, D.CT, "time")
    end
  elseif S.phase == "end" then
    if S.timer <= 0 then
      if S.winner > 0 then
        S.phase, S.timer = "over", 12
      else
        Sim.startRound(S)
        return
      end
    end
  elseif S.phase == "over" then
    if S.timer <= 0 then Sim.restartMatch(S) end
    return
  end
  if S.phase == "planted" then S.timer = max(0, S.bomb.timer) end
  for _, p in ipairs(S.players) do
    if p.flash > 0 then p.flash = max(0, p.flash - dt) end
  end
  S.visT = S.visT - dt
  if S.visT <= 0 then
    S.visT = 0.25
    updateVisibility(S)
  end
  updateNades(S, dt)
  updateBomb(S, dt)
  -- Подбор оружия с земли: если слот пуст.
  for _, p in ipairs(S.players) do
    if p.alive then
      for k = #S.drops, 1, -1 do
        local d = S.drops[k]
        if not (d.by == p.id and S.time - d.t < 1.5) and (p.x - d.x) ^ 2 + (p.z - d.z) ^ 2 < 0.5 and abs(p.y - d.y) < 0.8 then
          local slot = D.WEAPONS[d.wid].slot
          if (slot == 1 and p.pri == 0) or (slot == 2 and p.sec == 0) then
            if slot == 1 then p.pri = d.wid else p.sec = d.wid end
            Sim.giveAmmo(p, d.wid)
            if p.bot or p.wpn == D.KNIFE then p.wpn = d.wid end
            table.remove(S.drops, k)
            Sim.event(S, "pick", p.id, d.wid)
          end
        end
      end
    end
  end
  for _, p in ipairs(S.players) do
    if p.bot and p.alive then Bots.think(S, p, dt, Sim) end
  end
  Sim.checkRound(S)
end

-- --- Снимок состояния для телефонов игроков (компактно: числа ×100) ---

local function flags(p)
  local f = 0
  if p.alive then f = f + 1 end
  if p.bot then f = f + 2 end
  if p.crouch then f = f + 4 end
  if p.helmet then f = f + 8 end
  if p.kit then f = f + 16 end
  if p.bomb then f = f + 32 end
  if p.using then f = f + 64 end
  if p.spot then f = f + 128 end
  return f
end

function Sim.snapshot(S)
  local ps = {}
  for i, p in ipairs(S.players) do
    ps[i] = {p.id, p.name, p.team, flags(p), floor(p.x * 100), floor(p.y * 100), floor(p.z * 100), floor(p.vx * 100),
      floor(p.vz * 100), floor(p.yaw), floor(p.pitch), max(0, p.hp), p.armor, p.wpn, p.pri, p.sec,
      p.he * 100 + p.fl * 10 + p.sm, p.money, p.kills, p.deaths, p.shots, floor(p.flash * 10), p.tp, p.ack, p.hitAck,
      p.nextTeam}
  end
  local b = S.bomb
  local snap = {t = "s", m = S.matchId, ph = Sim.PHASE_CODE[S.phase], tm = floor(max(0, S.timer) * 10), rd = S.round,
    sc = {S.score[1], S.score[2]}, ft = S.firstTo, mp = S.mapIndex, w = S.winner, bt = floor(max(0, S.buyUntil - S.time) * 10),
    b = {Sim.BOMB_CODE[b.state], floor(b.x * 100), floor(b.y * 100), floor(b.z * 100), floor(max(0, b.timer) * 10), b.carrier,
      floor(b.progress * 100), b.defuser},
    p = ps, g = {}, s = {}, d = {}, e = {}}
  for _, n in ipairs(S.nades) do
    snap.g[#snap.g + 1] = {n.kind, floor(n.x * 100), floor(n.y * 100), floor(n.z * 100)}
  end
  for _, s in ipairs(S.smokes) do
    snap.s[#snap.s + 1] = {floor(s.x * 100), floor(s.y * 100), floor(s.z * 100), floor(s.r * 100), floor(s.t * 10)}
  end
  for _, d in ipairs(S.drops) do
    snap.d[#snap.d + 1] = {d.id, d.wid, floor(d.x * 100), floor(d.y * 100), floor(d.z * 100)}
  end
  for _, e in ipairs(S.events) do
    if S.time - e.time < 3 then snap.e[#snap.e + 1] = {e[1], e[2], e[3], e[4], e[5], e[6]} end
  end
  return snap
end

return Sim
