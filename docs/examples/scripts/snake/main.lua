-- «Змейка»: экран набок и во весь экран (app.json: orientation, fullscreen).
-- Управление: стрелки или WASD (или кнопки на экране), пробел — пауза. Рекорд хранится в storage.

local CELL = 8
local cols, rows
local snake, dir, nextDir, food, alive, paused, score, best, stepTime, acc

local function placeFood()
  while true do
    local x, y = math.random(0, cols - 1), math.random(0, rows - 1)
    local free = true
    for _, p in ipairs(snake) do
      if p.x == x and p.y == y then free = false break end
    end
    if free then food = {x = x, y = y} return end
  end
end

local function restart()
  cols = math.floor(screen.width() / CELL)
  rows = math.floor((screen.height() - 12) / CELL)
  snake = {{x = 5, y = 5}, {x = 4, y = 5}, {x = 3, y = 5}}
  dir, nextDir = {x = 1, y = 0}, {x = 1, y = 0}
  alive, paused, score, stepTime, acc = true, false, 0, 0.14, 0
  placeFood()
end

function onStart()
  best = storage.get("best", 0)
  screen.captureKeys(true) -- клавиши — игре, а не ходьбе
  restart()
end

local function turn(x, y)
  if x ~= -dir.x or y ~= -dir.y then nextDir = {x = x, y = y} end
end

function onKey(key)
  if key == "up" or key == "w" then turn(0, -1)
  elseif key == "down" or key == "s" then turn(0, 1)
  elseif key == "left" or key == "a" then turn(-1, 0)
  elseif key == "right" or key == "d" then turn(1, 0)
  elseif key == "space" then paused = not paused
  elseif key == "enter" and not alive then restart() end
  return true
end

local function step()
  dir = nextDir
  local head = {x = snake[1].x + dir.x, y = snake[1].y + dir.y}
  if head.x < 0 or head.y < 0 or head.x >= cols or head.y >= rows then alive = false end
  for _, p in ipairs(snake) do
    if p.x == head.x and p.y == head.y then alive = false end
  end
  if not alive then
    sound.play("block.note_block.bass", 1, 0.6)
    if score > best then best = score storage.set("best", best) end
    return
  end
  table.insert(snake, 1, head)
  if head.x == food.x and head.y == food.y then
    score = score + 1
    stepTime = math.max(0.06, stepTime - 0.004)
    sound.play("entity.experience_orb.pickup", 0.5, 1.2)
    placeFood()
  else
    table.remove(snake)
  end
end

function onDraw(dt)
  if alive and not paused then
    acc = acc + dt
    while acc >= stepTime do acc = acc - stepTime step() end
  end
  local w, h = screen.width(), screen.height()
  gfx.clear("#0E1A12")
  local top = 12
  gfx.rect(0, 0, w, top, "#16281C")
  gfx.text("Счёт: " .. score .. "   Рекорд: " .. best, 4, 2, "#BFF5C8", 0.75)
  gfx.rect(food.x * CELL + 1, top + food.y * CELL + 1, CELL - 2, CELL - 2, "#FF5A5A")
  for i, p in ipairs(snake) do
    gfx.rect(p.x * CELL, top + p.y * CELL, CELL - 1, CELL - 1, i == 1 and "#7CFF8F" or "#3FB950")
  end
  if paused then gfx.textCenter("Пауза", w / 2, h / 2 - 4, "#FFFFFF", 1.2, true) end
  if not alive then
    gfx.roundRect(w / 2 - 60, h / 2 - 26, 120, 52, 4, 0xE0000000)
    gfx.textCenter("Конец игры", w / 2, h / 2 - 20, "#FFFFFF", 1)
    if ui.button(w / 2 - 40, h / 2 - 4, 80, 16, "Ещё раз", "primary") then restart() end
  end
  -- Кнопки-стрелки для мыши (справа снизу).
  local bx, by = w - 50, h - 50
  if ui.button(bx + 17, by, 16, 16, "^") then turn(0, -1) end
  if ui.button(bx, by + 17, 16, 16, "<") then turn(-1, 0) end
  if ui.button(bx + 34, by + 17, 16, 16, ">") then turn(1, 0) end
  if ui.button(bx + 17, by + 34, 16, 16, "v") then turn(0, 1) end
end
