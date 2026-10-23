-- Сеть: поиск игр, вход, ввод игрока → хосту, снимок мира → игрокам.
-- Сервер пересылает не больше 40 сообщений за 5 секунд от игрока, поэтому:
--   * хост шлёт снимок 5 раз в секунду (одному гостю — лично, нескольким — одним сообщением всем);
--   * гость шлёт свой ввод 5 раз в секунду; покупки, броски гранат и попадания идут внутри ввода с номерами
--     и повторяются, пока хост не подтвердит их в снимке (ничего не теряется).
local Sim = require("sim")
local M = require("maps")

local N = {}
N.RATE, N.WINDOW, N.MAX = 0.2, 5, 36

local sent = {}

-- Можно ли отправить ещё одно сообщение, не превысив лимит сервера.
function N.canSend(now)
  while #sent > 0 and now - sent[1] > N.WINDOW do table.remove(sent, 1) end
  return #sent < N.MAX
end

function N.mark(now) sent[#sent + 1] = now end

function N.send(now, to, data)
  if not N.canSend(now) then return false end
  N.mark(now)
  if to then net.send(to, data) else net.broadcast(data) end
  return true
end

-- --- Снимок → состояние на телефоне гостя (те же поля, что у Sim, чтобы рисовать одинаково) ---

local function bit(f, k) return math.floor(f / k) % 2 == 1 end

function N.newView(mapIndex)
  return {map = M.load(mapIndex), mapIndex = mapIndex, players = {}, phase = "warmup", timer = 0, round = 0, score = {0, 0},
    firstTo = 8, winner = 0, buyLeft = 0, bomb = {state = "none", x = 0, y = 0, z = 0, timer = 0, carrier = 0, progress = 0,
    defuser = 0}, nades = {}, smokes = {}, drops = {}, events = {}}
end

function N.decode(V, s)
  V.phase = Sim.PHASES[s.ph] or "warmup"
  V.timer = s.tm / 10
  V.round, V.firstTo, V.winner, V.buyLeft = s.rd, s.ft, s.w, s.bt / 10
  V.wingman = s.wm == 1
  V.score = {s.sc[1], s.sc[2]}
  local b = s.b
  V.bomb = {state = Sim.BOMB_STATES[b[1]] or "none", x = b[2] / 100, y = b[3] / 100, z = b[4] / 100, timer = b[5] / 10,
    carrier = b[6], progress = b[7] / 100, defuser = b[8]}
  local seen = {}
  for _, a in ipairs(s.p) do
    local id = a[1]
    seen[id] = true
    local p = V.players[id]
    if not p then
      p = {id = id}
      V.players[id] = p
    end
    local f = a[4]
    p.name, p.team = a[2], a[3]
    p.alive, p.bot, p.crouch, p.helmet, p.kit, p.bomb, p.using, p.spot =
      bit(f, 1), bit(f, 2), bit(f, 4), bit(f, 8), bit(f, 16), bit(f, 32), bit(f, 64), bit(f, 128)
    p.afk = bit(f, 256)
    p.x, p.y, p.z, p.vx, p.vz = a[5] / 100, a[6] / 100, a[7] / 100, a[8] / 100, a[9] / 100
    p.yaw, p.pitch, p.hp, p.armor, p.wpn, p.pri, p.sec = a[10], a[11], a[12], a[13], a[14], a[15], a[16]
    p.he, p.fl, p.sm = math.floor(a[17] / 100), math.floor(a[17] / 10) % 10, a[17] % 10
    p.money, p.kills, p.deaths, p.shots, p.flash = a[18], a[19], a[20], a[21], a[22] / 10
    p.tp, p.ack, p.hitAck, p.nextTeam = a[23], a[24], a[25], a[26] or 0
  end
  for id in pairs(V.players) do
    if not seen[id] then V.players[id] = nil end
  end
  V.nades = {}
  for i, g in ipairs(s.g) do V.nades[i] = {kind = g[1], x = g[2] / 100, y = g[3] / 100, z = g[4] / 100} end
  V.smokes = {}
  for i, m in ipairs(s.s) do V.smokes[i] = {x = m[1] / 100, y = m[2] / 100, z = m[3] / 100, r = m[4] / 100, t = m[5] / 10} end
  V.drops = {}
  for i, d in ipairs(s.d) do V.drops[i] = {id = d[1], wid = d[2], x = d[3] / 100, y = d[4] / 100, z = d[5] / 100} end
  V.events = s.e
end

-- Список игроков по порядку (players у гостя — таблица по id).
function N.list(V)
  local out = {}
  for _, p in pairs(V.players) do out[#out + 1] = p end
  table.sort(out, function(a, b) return a.id < b.id end)
  return out
end

return N
