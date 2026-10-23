-- Интерфейс поверх 3D: здоровье, броня, деньги, патроны, счёт и время, радар, лента убийств, сообщения,
-- меню покупки (B), таблица счёта (Tab), прицел AWP.
local D = require("data")
local M = require("maps")
local Sim = require("sim")

local H = {}
local floor, min, max = math.floor, math.min, math.max

local function a(color, alpha)
  -- "#RRGGBB" / "#AARRGGBB" → с нужной прозрачностью (0..1)
  local rgb = #color == 9 and color:sub(4) or color:sub(2)
  return string.format("#%02X%s", floor(max(0, min(1, alpha)) * 255), rgb)
end
H.alpha = a

local function text(s, x, y, color, scale) gfx.text(s, floor(x), floor(y), color or "#FFFFFFFF", scale or 0.6, true) end
local function center(s, x, y, color, scale) gfx.textCenter(s, floor(x), floor(y), color or "#FFFFFFFF", scale or 0.6, true) end
local function right(s, x, y, color, scale)
  scale = scale or 0.6
  gfx.text(s, floor(x - gfx.textWidth(s, scale)), floor(y), color or "#FFFFFFFF", scale, true)
end
H.text, H.center, H.right = text, center, right

local function clock(t)
  t = max(0, math.ceil(t))
  return string.format("%d:%02d", floor(t / 60), t % 60)
end

-- --- Радар (вид сверху, север вверху) ---

-- Мир: если смотреть вдоль +Z, то +X — справа (так рисует g3d). На виде сверху с Z вниз это значит, что X идёт
-- справа налево — иначе карта зеркальна миру. Картинки radar/*.png уже отражены (gen_strike.py).
function H.radarX(map, x) return map.W - x end

function H.radar(G, x, y, size)
  local V, me = G.V, G.me
  local map = V.map
  local scale = size / max(map.W, map.H)
  local w, h = map.W * scale, map.H * scale
  local ox, oy = x + (size - w) / 2, y + (size - h) / 2
  local function px(wx) return ox + H.radarX(map, wx) * scale end
  gfx.rect(x - 1, y - 1, size + 2, size + 2, "#90000000")
  gfx.image("radar/" .. map.def.key .. ".png", ox, oy, w, h)
  local my = V.players[me.id]
  local myTeam = my and my.team or 0
  local b = V.bomb
  if b.state == "dropped" or b.state == "planted" or (b.state == "carried" and myTeam == D.T) then
    local blink = b.state ~= "planted" or (G.now % 0.6) < 0.35
    if blink then gfx.rect(px(b.x) - 1.5, oy + b.z * scale - 1.5, 3, 3, "#FFFF4040") end
  end
  for _, p in pairs(V.players) do
    if p.alive and p.id ~= me.id and (p.team == myTeam or p.spot) then
      local c = p.team == myTeam and D.TEAM_COLOR[p.team] or "#FFFF3B30"
      gfx.rect(px(p.rx or p.x) - 1, oy + (p.rz or p.z) * scale - 1, 2, 2, c)
    end
  end
  -- я: точка и направление взгляда
  local mx, mz = px(me.x), oy + me.z * scale
  local fx, _, fz = Sim.forward(me.yaw, 0)
  gfx.line(mx, mz, mx - fx * 4, mz + fz * 4, "#FFFFFFFF", 1)
  gfx.rect(mx - 1, mz - 1, 3, 3, "#FFFFEA00")
end

-- --- Основной HUD ---

local function teamAlive(V, team)
  local n = 0
  for _, p in pairs(V.players) do if p.team == team and p.alive then n = n + 1 end end
  return n
end

function H.draw(G, sw, sh)
  local V, me, now = G.V, G.me, G.now
  local P = V.players[me.id]
  local alive = P and P.alive
  -- Ослепление и попадания.
  if G.flash > 0 then gfx.rect(0, 0, sw, sh, a("#FFFFFF", min(1, G.flash / 2.2))) end
  if G.hurtT > 0 then
    local al = min(0.45, G.hurtT)
    gfx.rect(0, 0, sw, 8, a("#FF0000", al))
    gfx.rect(0, sh - 8, sw, 8, a("#FF0000", al))
    gfx.rect(0, 0, 8, sh, a("#FF0000", al))
    gfx.rect(sw - 8, 0, 8, sh, a("#FF0000", al))
  end
  if G.smoke > 0 then gfx.rect(0, 0, sw, sh, a("#C8C8C8", min(0.97, G.smoke))) end
  -- Счёт и время.
  local my = P and P.team or 1
  local s1, s2 = V.score[1], V.score[2]
  local cx = sw / 2
  gfx.rect(cx - 34, 0, 68, 13, "#A0101418")
  gfx.rect(cx - 52, 0, 18, 13, a(D.TEAM_COLOR[1], 0.75))
  gfx.rect(cx + 34, 0, 18, 13, a(D.TEAM_COLOR[2], 0.75))
  center(tostring(s1), cx - 43, 3, "#FFFFFFFF", 0.7)
  center(tostring(s2), cx + 43, 3, "#FFFFFFFF", 0.7)
  local t = V.phase == "freeze" and V.timer or V.timer
  if V.phase == "planted" then
    local blink = (now % 0.8) < 0.5
    center(blink and "БОМБА" or "", cx, 3, "#FFFF4040", 0.65)
  else
    center(clock(t), cx, 3, V.phase == "freeze" and "#FF9FE0FF" or (t < 10 and V.phase == "live" and "#FFFF6060" or "#FFFFFFFF"), 0.7)
  end
  -- живые в командах: точки
  for team = 1, 2 do
    local n = teamAlive(V, team)
    for i = 1, n do
      local x = team == 1 and (cx - 36 - i * 4) or (cx + 33 + i * 4)
      gfx.rect(x, 15, 3, 3, D.TEAM_COLOR[team])
    end
  end
  -- Радар и деньги.
  H.radar(G, 3, 3, 48)
  if P then
    local money = "$" .. P.money
    text(money, 4, 54, "#FF8CF08C", 0.7)
    if G.moneyDelta ~= 0 and now - G.moneyT < 2.5 then
      text((G.moneyDelta > 0 and "+" or "") .. G.moneyDelta, 6 + gfx.textWidth(money, 0.7), 54, G.moneyDelta > 0 and "#FF8CF08C" or "#FFFF7070", 0.55)
    end
    if G.inBuy and V.buyLeft > 0 and alive then text("B — покупка (" .. math.ceil(V.buyLeft) .. ")", 4, 62, "#C0FFFFFF", 0.5) end
  end
  -- Лента убийств.
  local y = 3
  for i = #G.feed, 1, -1 do
    local f = G.feed[i]
    if now - f.t < 6 then
      local mine = f.killer == me.id or f.victim == me.id
      local line = f.kn .. "  [" .. f.wn .. "]" .. (f.head and " ✜" or "") .. "  " .. f.vn
      local w = gfx.textWidth(line, 0.5)
      gfx.rect(sw - w - 8, y - 1, w + 6, 8, mine and "#B0602020" or "#90000000")
      local x = sw - w - 5
      text(f.kn, x, y, D.TEAM_COLOR[f.kt] or "#FFFFFFFF", 0.5)
      x = x + gfx.textWidth(f.kn .. "  ", 0.5)
      local mid = "[" .. f.wn .. "]" .. (f.head and " ✜" or "") .. "  "
      text(mid, x, y, "#FFE0E0E0", 0.5)
      x = x + gfx.textWidth(mid, 0.5)
      text(f.vn, x, y, D.TEAM_COLOR[f.vt] or "#FFFFFFFF", 0.5)
      y = y + 9
    end
  end
  if alive then
    -- Здоровье и броня.
    gfx.rect(2, sh - 14, 74, 12, "#90000000")
    gfx.image("textures/i_hp.png", 4, sh - 12, 8, 8)
    text(tostring(P.hp), 14, sh - 11, P.hp > 25 and "#FFFFFFFF" or "#FFFF5050", 0.75)
    gfx.image("textures/i_armor.png", 38, sh - 12, 8, 8)
    text(tostring(P.armor), 48, sh - 11, "#FFFFFFFF", 0.75)
    if P.helmet then text("+", 64, sh - 11, "#FFA0D0FF", 0.6) end
    -- Патроны и оружие.
    local w = D.WEAPONS[me.wpn]
    local am = me.ammo[me.wpn]
    gfx.rect(sw - 92, sh - 14, 90, 12, "#90000000")
    if am then
      local c = am[1] > 0 and "#FFFFFFFF" or "#FFFF5050"
      right(am[1] .. " / " .. am[2], sw - 5, sh - 11, c, 0.75)
    elseif w.slot == 4 then
      local f = D.NADE_FIELD[me.wpn]
      right("× " .. (P[f] or 0), sw - 5, sh - 11, "#FFFFFFFF", 0.75)
    end
    text(w.name, sw - 89, sh - 10, "#C0FFFFFF", 0.5)
    -- Слоты (что есть).
    local slots = {}
    if P.pri > 0 then slots[#slots + 1] = "1 " .. D.WEAPONS[P.pri].name end
    if P.sec > 0 then slots[#slots + 1] = "2 " .. D.WEAPONS[P.sec].name end
    slots[#slots + 1] = "3 Нож"
    local n = P.he + P.fl + P.sm
    if n > 0 then slots[#slots + 1] = "4 Гранаты ×" .. n end
    if P.bomb then slots[#slots + 1] = "5 C4" end
    local sy = sh - 18 - #slots * 7
    for _, s in ipairs(slots) do
      right(s, sw - 4, sy, "#B0FFFFFF", 0.45)
      sy = sy + 7
    end
    if P.kit then gfx.image("textures/i_kit.png", 80, sh - 12, 8, 8) end
    if P.bomb then gfx.image("textures/i_c4.png", 80, sh - 12, 8, 8) end
  end
  -- Закладка / обезвреживание.
  local b = V.bomb
  local prog, label = 0, nil
  if b.state == "carried" and b.carrier == me.id and b.progress > 0 then
    prog, label = b.progress / D.PLANT_TIME, "Закладка бомбы…"
  elseif b.state == "planted" and b.defuser == me.id then
    prog, label = b.progress / (P and P.kit and D.DEFUSE_KIT_TIME or D.DEFUSE_TIME), "Обезвреживание…"
  end
  if label then
    gfx.rect(cx - 50, sh / 2 + 22, 100, 14, "#B0000000")
    center(label, cx, sh / 2 + 24, "#FFFFFFFF", 0.5)
    gfx.rect(cx - 46, sh / 2 + 31, 92, 3, "#FF303030")
    gfx.rect(cx - 46, sh / 2 + 31, floor(92 * min(1, prog)), 3, "#FFFFD040")
  elseif G.hint and alive then
    center(G.hint, cx, sh / 2 + 26, "#E0FFFFFF", 0.5)
  end
  -- Наблюдение.
  if not alive and G.specName then
    gfx.rect(cx - 70, sh - 16, 140, 13, "#A0000000")
    center("Наблюдение: " .. G.specName .. "  (ЛКМ — следующий)", cx, sh - 13, "#FFFFFFFF", 0.5)
  elseif not alive and V.phase ~= "over" then
    center("Вы погибли — ждите следующего раунда", cx, sh - 13, "#FFFFFFFF", 0.5)
  end
  -- Крупное сообщение.
  if G.center and now - G.center.t < (G.center.life or 3) then
    local c = G.center
    gfx.rect(0, sh * 0.28 - 4, sw, c.sub and 26 or 16, "#90000000")
    center(c.text, cx, sh * 0.28, c.color or "#FFFFFFFF", 0.9)
    if c.sub then center(c.sub, cx, sh * 0.28 + 12, "#FFE0E0E0", 0.55) end
  end
  if G.small and now - G.small.t < 2.5 then center(G.small.text, cx, 22, "#FFFFE080", 0.55) end
end

-- --- Прицел ---

function H.crosshair(G, sw, sh, spread, enemyName)
  local cx, cy = floor(sw / 2), floor(sh / 2)
  local w = D.WEAPONS[G.me.wpn]
  local c = G.set.cross or "#FF4CFF6A"
  if G.me.zoom > 0 then return end
  -- Рисуем в четвертях точки экрана: тонкие линии и маленький зазор (раскрывается от разброса, но немного).
  gfx.push()
  gfx.translate(cx, cy)
  gfx.scale(0.25)
  if w.id == D.AWP then
    gfx.rect(-1, -1, 3, 3, c)
  else
    local gap = 3 + min(12, floor(spread * 120))
    local len = 6
    gfx.rect(-gap - len, 0, len, 2, c)
    gfx.rect(gap + 2, 0, len, 2, c)
    gfx.rect(0, -gap - len, 2, len, c)
    gfx.rect(0, gap + 2, 2, len, c)
  end
  gfx.pop()
  if G.hitT > 0 then
    local hc = G.hitHead and "#FFFF4040" or "#FFFFFFFF"
    gfx.push()
    gfx.translate(cx, cy)
    gfx.scale(0.25)
    gfx.line(-18, -18, -8, -8, hc, 2)
    gfx.line(18, -18, 8, -8, hc, 2)
    gfx.line(-18, 18, -8, 8, hc, 2)
    gfx.line(18, 18, 8, 8, hc, 2)
    gfx.pop()
  end
  if enemyName then center(enemyName, cx, cy + 10, "#FFFF5050", 0.5) end
end

function H.scope(sw, sh)
  local d = sh
  local x = floor((sw - d) / 2)
  gfx.rect(0, 0, x + 1, sh, "#FF000000")
  gfx.rect(x + d - 1, 0, sw - x - d + 1, sh, "#FF000000")
  gfx.image("textures/scope.png", x, 0, d, d)
  gfx.rect(x, floor(sh / 2), d, 1, "#FF000000")
  gfx.rect(floor(sw / 2), 0, 1, sh, "#FF000000")
end

-- --- Меню покупки ---

function H.buyMenu(G, sw, sh, buyFn)
  local V, me = G.V, G.me
  local P = V.players[me.id]
  if not P then return end
  local w, h = 250, 132
  local x, y = floor((sw - w) / 2), floor((sh - h) / 2)
  gfx.rect(x, y, w, h, "#E0101418")
  gfx.border(x, y, w, h, "#FF3A4250")
  text("Покупка", x + 6, y + 4, "#FFFFFFFF", 0.7)
  right("$" .. P.money .. "   осталось " .. math.ceil(V.buyLeft) .. " с", x + w - 6, y + 5, "#FF8CF08C", 0.55)
  local cat = G.buyCat
  for i, c in ipairs(D.BUY) do
    local by = y + 16 + (i - 1) * 18
    local style = cat == i and "selected" or "normal"
    if ui.button(x + 4, by, 86, 16, i .. "  " .. c.name, style) then G.buyCat = i end
  end
  if cat then
    local items = D.BUY[cat].items
    for i, item in ipairs(items) do
      local by = y + 16 + (i - 1) * 18
      local price, why = Sim.buyCheck(V, P, item)
      local team = D.itemTeam(item)
      local label = i .. "  " .. D.itemName(item)
      if team and team ~= P.team then
        label = label .. " (" .. D.TEAM_SHORT[team] .. ")"
      end
      local cost = item == "helmet" and P.armor >= 100 and not P.helmet and 350 or D.itemPrice(item)
      local can = price ~= nil
      if ui.button(x + 94, by, w - 98, 16, "", "normal") and can then buyFn(item) end
      if not can then gfx.rect(x + 94, by, w - 98, 16, "#90101418") end
      local key = type(item) == "number" and D.WEAPONS[item].key or nil
      if key then gfx.image("textures/i_" .. key .. ".png", x + 96, by + 1, 35, 14) end
      text(label, x + 134, by + 4, can and "#FFFFFFFF" or "#FF808080", 0.55)
      right("$" .. cost, x + w - 8, by + 4, can and "#FF8CF08C" or (why == "Не хватает денег" and "#FFFF6060" or "#FF808080"), 0.55)
    end
  else
    text("Выберите раздел: 1–6 или мышью", x + 98, y + 20, "#FFB0B0B0", 0.55)
    text("Esc или B — закрыть", x + 98, y + 30, "#FF808080", 0.5)
  end
end

-- --- Таблица счёта ---

-- Колонки — по правому краю, заголовки над своими числами; высота строки подстраивается под число игроков (5×5 и
-- больше помещаются на экране целиком).
function H.scoreboard(G, sw, sh, list)
  local V = G.V
  local my = V.players[G.me.id]
  local counts = {0, 0}
  for _, p in ipairs(list) do
    if counts[p.team] then counts[p.team] = counts[p.team] + 1 end
  end
  local rows = counts[1] + counts[2]
  local top, bottom = 6, sh - 6
  local fixed = 13 + 2 * (11 + 3) + 2
  local rowH = max(6, min(10, floor((bottom - top - fixed) / max(1, rows))))
  local ts = min(0.5, rowH * 0.058)
  local w = min(sw - 20, 260)
  local x = floor((sw - w) / 2)
  local h = fixed + rows * rowH
  local y = max(top, floor((sh - h) / 2))
  gfx.rect(x, y, w, h, "#E0101418")
  center((V.wingman and "Напарники · " or "") .. V.map.def.name .. " · раунд " .. V.round .. " · до " .. V.firstTo .. " побед",
    sw / 2, y + 3, "#FFE0E0E0", 0.55)
  y = y + 13
  local colMoney, colD, colK = x + w - 6, x + w - 46, x + w - 64
  for _, team in ipairs({2, 1}) do
    gfx.rect(x + 2, y, w - 4, 10, a(D.TEAM_COLOR[team], 0.35))
    text(D.TEAM_NAME[team] .. "  " .. V.score[team], x + 5, y + 2, "#FFFFFFFF", 0.55)
    right("У", colK, y + 2, "#FFE0E0E0", 0.5)
    right("С", colD, y + 2, "#FFE0E0E0", 0.5)
    right("$", colMoney, y + 2, "#FFE0E0E0", 0.5)
    y = y + 11
    for _, p in ipairs(list) do
      if p.team == team then
        local c = p.alive and "#FFFFFFFF" or "#FF808080"
        if p.id == G.me.id then gfx.rect(x + 2, y - 1, w - 4, rowH, "#40FFFFFF") end
        local name = (p.alive and "" or "✝ ") .. p.name .. (p.afk and "  [AFK]" or "")
          .. (p.bomb and my and my.team == D.T and "  [C4]" or "")
        text(name, x + 6, y, c, ts)
        right(tostring(p.kills), colK, y, c, ts)
        right(tostring(p.deaths), colD, y, c, ts)
        if my and my.team == p.team then right("$" .. p.money, colMoney, y, "#FF8CF08C", ts) end
        y = y + rowH
      end
    end
    y = y + 3
  end
end

return H
