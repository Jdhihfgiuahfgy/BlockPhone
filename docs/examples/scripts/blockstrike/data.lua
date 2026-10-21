-- Данные игры: команды, оружие, снаряжение, экономика, имена ботов.
local D = {}

D.T, D.CT = 1, 2
D.TEAM_NAME = {"Террористы", "Спецназ"}
D.TEAM_SHORT = {"Т", "КТ"}
D.TEAM_COLOR = {"#FFE8B04A", "#FF6FA8FF"}
D.TEAM_DARK = {"#C05A3A10", "#C0102A5A"}

-- Слоты: 1 — основное, 2 — пистолет, 3 — нож, 4 — гранаты, 5 — бомба.
-- dmg — урон в тело без брони; pen — сколько урона проходит через броню; cool — пауза между выстрелами (с);
-- spread — разброс стоя (радианы), move — добавка на бегу; recoil — отдача (градусы вверх за выстрел);
-- range — множитель урона за каждые 10 клеток; speed — скорость бега с этим оружием; reward — награда за убийство.
D.WEAPONS = {
  {id = 1, key = "knife", name = "Нож", slot = 3, dmg = 40, cool = 0.5, reach = 1.6, speed = 1.0, reward = 1500,
    sound = "entity.villager.hurt", pitch = 1.6},
  {id = 2, key = "glock", name = "Glock-18", slot = 2, team = 1, price = 200, clip = 20, reserve = 120, dmg = 30, pen = 0.47,
    cool = 0.15, spread = 0.010, move = 0.035, recoil = 1.2, reload = 2.2, range = 0.85, speed = 0.97, reward = 300,
    sound = "block.note_block.snare", pitch = 1.3, len = 0.16},
  {id = 3, key = "usp", name = "USP-S", slot = 2, team = 2, price = 200, clip = 12, reserve = 24, dmg = 35, pen = 0.5,
    cool = 0.17, spread = 0.006, move = 0.03, recoil = 1.5, reload = 2.2, range = 0.88, speed = 0.97, reward = 300,
    sound = "block.note_block.hat", pitch = 0.8, len = 0.22},
  {id = 4, key = "p250", name = "P250", slot = 2, price = 300, clip = 13, reserve = 26, dmg = 38, pen = 0.64,
    cool = 0.15, spread = 0.009, move = 0.035, recoil = 1.8, reload = 2.2, range = 0.85, speed = 0.97, reward = 300,
    sound = "block.note_block.snare", pitch = 1.1, len = 0.16},
  {id = 5, key = "deagle", name = "Desert Eagle", slot = 2, price = 700, clip = 7, reserve = 35, dmg = 63, pen = 0.93,
    cool = 0.25, spread = 0.008, move = 0.07, recoil = 4.5, reload = 2.2, range = 0.9, speed = 0.95, reward = 300,
    sound = "block.note_block.basedrum", pitch = 0.7, len = 0.2},
  {id = 6, key = "nova", name = "Nova", slot = 1, price = 1050, clip = 8, reserve = 32, dmg = 26, pen = 0.5, pellets = 9,
    cool = 0.88, spread = 0.055, move = 0.02, recoil = 3.0, reload = 3.4, range = 0.6, speed = 0.92, reward = 900,
    sound = "entity.generic.explode", pitch = 2.0, len = 0.42},
  {id = 7, key = "mp9", name = "MP9", slot = 1, price = 1250, clip = 30, reserve = 120, dmg = 26, pen = 0.6, auto = true,
    cool = 0.07, spread = 0.012, move = 0.02, recoil = 0.7, reload = 2.1, range = 0.8, speed = 0.96, reward = 600,
    sound = "block.note_block.hat", pitch = 1.4, len = 0.28},
  {id = 8, key = "p90", name = "P90", slot = 1, price = 2350, clip = 50, reserve = 100, dmg = 26, pen = 0.69, auto = true,
    cool = 0.07, spread = 0.014, move = 0.02, recoil = 0.6, reload = 3.3, range = 0.86, speed = 0.94, reward = 300,
    sound = "block.note_block.hat", pitch = 1.2, len = 0.32},
  {id = 9, key = "galil", name = "Galil AR", slot = 1, team = 1, price = 1800, clip = 35, reserve = 90, dmg = 30, pen = 0.775,
    auto = true, cool = 0.09, spread = 0.006, move = 0.07, recoil = 0.85, reload = 2.9, range = 0.95, speed = 0.9, reward = 300,
    sound = "block.note_block.snare", pitch = 0.75, len = 0.44},
  {id = 10, key = "famas", name = "FAMAS", slot = 1, team = 2, price = 2050, clip = 25, reserve = 90, dmg = 30, pen = 0.7,
    auto = true, cool = 0.09, spread = 0.006, move = 0.07, recoil = 0.8, reload = 3.3, range = 0.95, speed = 0.9, reward = 300,
    sound = "block.note_block.snare", pitch = 0.85, len = 0.4},
  {id = 11, key = "ak47", name = "AK-47", slot = 1, team = 1, price = 2700, clip = 30, reserve = 90, dmg = 36, pen = 0.775,
    auto = true, cool = 0.1, spread = 0.004, move = 0.09, recoil = 1.0, reload = 2.5, range = 0.98, speed = 0.86, reward = 300,
    sound = "block.note_block.basedrum", pitch = 1.1, len = 0.48},
  {id = 12, key = "m4a4", name = "M4A4", slot = 1, team = 2, price = 3100, clip = 30, reserve = 90, dmg = 33, pen = 0.7,
    auto = true, cool = 0.09, spread = 0.004, move = 0.085, recoil = 0.85, reload = 3.1, range = 0.97, speed = 0.88, reward = 300,
    sound = "block.note_block.snare", pitch = 0.65, len = 0.48},
  {id = 13, key = "awp", name = "AWP", slot = 1, price = 4750, clip = 5, reserve = 30, dmg = 115, pen = 0.975,
    cool = 1.45, spread = 0.07, scoped = 0.0015, move = 0.2, recoil = 5.0, reload = 3.6, range = 0.99, speed = 0.8, reward = 100,
    sound = "entity.generic.explode", pitch = 1.5, len = 0.6, scope = {38, 14}},
  {id = 14, key = "he", name = "Осколочная граната", slot = 4, price = 300, max = 1, speed = 0.98, reward = 300},
  {id = 15, key = "flash", name = "Световая граната", slot = 4, price = 200, max = 2, speed = 0.98, reward = 300},
  {id = 16, key = "smoke", name = "Дымовая граната", slot = 4, price = 300, max = 1, speed = 0.98, reward = 300},
  {id = 17, key = "c4", name = "Бомба C4", slot = 5, speed = 0.97},
}
D.KNIFE, D.GLOCK, D.USP, D.AWP, D.HE, D.FLASH, D.SMOKE, D.C4 = 1, 2, 3, 13, 14, 15, 16, 17
D.NADE_FIELD = {[14] = "he", [15] = "fl", [16] = "sm"}

-- Меню покупки (B): категории → предметы. Числа — id оружия, строки — снаряжение.
D.BUY = {
  {name = "Пистолеты", items = {2, 3, 4, 5}},
  {name = "Дробовики", items = {6}},
  {name = "Пистолеты-пулемёты", items = {7, 8}},
  {name = "Винтовки", items = {9, 10, 11, 12, 13}},
  {name = "Снаряжение", items = {"kevlar", "helmet", "kit"}},
  {name = "Гранаты", items = {14, 15, 16}},
}
D.GEAR = {
  kevlar = {name = "Бронежилет", price = 650},
  helmet = {name = "Бронежилет + шлем", price = 1000},
  kit = {name = "Набор сапёра", price = 400, team = 2},
}

D.MONEY_START, D.MONEY_MAX = 800, 16000
D.WIN_REWARD = {elim = 3250, time = 3250, bomb = 3500, defuse = 3500}
D.LOSS_BONUS = {1400, 1900, 2400, 2900, 3400}
D.PLANT_REWARD, D.DEFUSE_REWARD, D.PLANTED_LOSS_BONUS = 300, 300, 800

D.FREEZE_TIME, D.BUY_TIME, D.ROUND_TIME, D.END_TIME = 6, 25, 115, 5
D.BOMB_TIME, D.PLANT_TIME, D.DEFUSE_TIME, D.DEFUSE_KIT_TIME = 40, 3.2, 7, 4

D.HEAD, D.BODY, D.LEGS = 2, 1, 3
D.PART_MUL = {1, 4, 0.75}

D.BOT_NAMES = {"Вася", "Петя", "Гоша", "Сеня", "Миша", "Лёша", "Дима", "Костя", "Федя", "Женя", "Толя", "Боря",
  "Слава", "Рома", "Ваня", "Гриша", "Егор", "Стёпа", "Тёма", "Кеша"}
D.BOT_LEVELS = {"Лёгкие", "Обычные", "Сложные"}

D.ROUND_REASON = {
  elim = "Все противники уничтожены",
  time = "Время вышло",
  bomb = "Бомба взорвана",
  defuse = "Бомба обезврежена",
}

function D.weapon(id) return D.WEAPONS[id] end

function D.itemName(item)
  if type(item) == "number" then return D.WEAPONS[item].name end
  return D.GEAR[item].name
end

function D.itemPrice(item)
  if type(item) == "number" then return D.WEAPONS[item].price or 0 end
  return D.GEAR[item].price
end

function D.itemTeam(item)
  if type(item) == "number" then return D.WEAPONS[item].team end
  return D.GEAR[item].team
end

return D
