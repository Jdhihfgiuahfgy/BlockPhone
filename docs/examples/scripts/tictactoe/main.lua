-- «Крестики-нолики онлайн»: игра с другим игроком на этом же сервере (у него тоже должно стоять это приложение).
-- Как устроено: net.players — кто на сервере с BlockPhone; net.send — сообщение этому же приложению у игрока;
-- onMessage — пришло сообщение. Тот, кто пригласил, играет крестиками и ходит первым.
-- Так же делаются «Дурак онлайн» и другие игры: один игрок (пригласивший) ведёт игру и рассылает её состояние.

local state = "menu"   -- menu | waiting | invited | playing
local players = {}
local opponent, me, turn, board, winner, invitedBy

local function reset()
  board = {"", "", "", "", "", "", "", "", ""}
  winner = nil
end

local LINES = {{1,2,3},{4,5,6},{7,8,9},{1,4,7},{2,5,8},{3,6,9},{1,5,9},{3,5,7}}

local function checkWinner()
  for _, l in ipairs(LINES) do
    local a = board[l[1]]
    if a ~= "" and a == board[l[2]] and a == board[l[3]] then return a end
  end
  for i = 1, 9 do if board[i] == "" then return nil end end
  return "draw"
end

local function refresh()
  if not net.online() then return end
  net.players(function(list) players = list end)
end

function onStart()
  reset()
  refresh()
end

function onMessage(from, data)
  if data.type == "invite" and state ~= "playing" then
    invitedBy = from
    state = "invited"
    sound.play("block.note_block.bell", 1, 1.4)
  elseif data.type == "accept" and state == "waiting" and from == opponent then
    me, turn, state = "X", "X", "playing"
    reset()
  elseif data.type == "move" and state == "playing" and from == opponent then
    board[data.cell] = data.mark
    turn = me
    winner = checkWinner()
  elseif data.type == "decline" and from == opponent then
    state = "menu"
    toast(from .. " отказался")
  end
end

local function move(cell)
  if state ~= "playing" or winner or turn ~= me or board[cell] ~= "" then return end
  board[cell] = me
  turn = (me == "X") and "O" or "X"
  winner = checkWinner()
  net.send(opponent, {type = "move", cell = cell, mark = me})
end

function onBack()
  if state ~= "menu" then state = "menu" return true end
  return false
end

function onDraw()
  local w, h = screen.width(), screen.height()
  gfx.clear(theme.background())
  if state == "menu" then
    gfx.text("Выберите соперника:", 8, 6, theme.text(), 0.8)
    if not net.online() then
      gfx.text("Нужен сервер с BlockPhone", 8, 20, theme.secondary(), 0.7)
      return
    end
    if #players == 0 then gfx.text("Никого нет онлайн", 8, 20, theme.secondary(), 0.7) end
    for i, name in ipairs(players) do
      if ui.button(8, 4 + i * 20, w - 16, 16, name) then
        opponent = name
        net.send(name, {type = "invite"})
        state = "waiting"
      end
    end
    if ui.button(8, h - 24, w - 16, 18, "Обновить", "primary") then refresh() end
  elseif state == "waiting" then
    gfx.textCenter("Ждём ответа " .. opponent .. "…", w / 2, h / 2 - 10, theme.text(), 0.8)
    if ui.button(w / 2 - 40, h / 2 + 6, 80, 16, "Отмена") then state = "menu" end
  elseif state == "invited" then
    gfx.textCenter(invitedBy .. " зовёт сыграть", w / 2, h / 2 - 20, theme.text(), 0.8)
    if ui.button(w / 2 - 62, h / 2, 60, 18, "Играть", "primary") then
      opponent, me, turn, state = invitedBy, "O", "X", "playing"
      reset()
      net.send(opponent, {type = "accept"})
    end
    if ui.button(w / 2 + 2, h / 2, 60, 18, "Нет") then
      net.send(invitedBy, {type = "decline"})
      state = "menu"
    end
  else
    local size = math.min(w - 20, h - 60)
    local cell = math.floor(size / 3)
    local x0, y0 = math.floor((w - cell * 3) / 2), 30
    local status
    if winner == "draw" then status = "Ничья"
    elseif winner then status = (winner == me) and "Вы победили!" or "Победил " .. opponent
    else status = (turn == me) and "Ваш ход (" .. me .. ")" or "Ход " .. opponent end
    gfx.textCenter(status, w / 2, 12, theme.accent(), 0.9)
    for i = 1, 9 do
      local cx = x0 + ((i - 1) % 3) * cell
      local cy = y0 + math.floor((i - 1) / 3) * cell
      gfx.roundRect(cx + 2, cy + 2, cell - 4, cell - 4, 4, theme.card())
      if board[i] ~= "" then
        gfx.textCenter(board[i], cx + cell / 2, cy + cell / 2 - 12, board[i] == "X" and "#E5534B" or "#4C8DF6", 3)
      end
    end
    if winner and ui.button(w / 2 - 40, y0 + cell * 3 + 8, 80, 16, "В меню", "primary") then state = "menu" end
  end
end

function onClick(x, y)
  if state ~= "playing" then return end
  local w, h = screen.width(), screen.height()
  local cell = math.floor(math.min(w - 20, h - 60) / 3)
  local x0, y0 = math.floor((w - cell * 3) / 2), 30
  local col, row = math.floor((x - x0) / cell), math.floor((y - y0) / cell)
  if col >= 0 and col < 3 and row >= 0 and row < 3 then move(row * 3 + col + 1) end
end
