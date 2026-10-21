-- «Погода в мире»: настоящая погода с сайта (пример интеграции со своим сайтом или API).
-- Ходить можно только на сайты из "hosts" в app.json; ответ приходит в функцию-обработчик.

local cities = {
  {name = "Москва", lat = 55.75, lon = 37.62},
  {name = "Санкт-Петербург", lat = 59.94, lon = 30.31},
  {name = "Новосибирск", lat = 55.03, lon = 82.92},
  {name = "Лондон", lat = 51.51, lon = -0.13},
}
local selected = 1
local result, loading, failure

local function load()
  local c = cities[selected]
  loading, failure = true, nil
  http.get("https://api.open-meteo.com/v1/forecast?latitude=" .. c.lat .. "&longitude=" .. c.lon
      .. "&current=temperature_2m,wind_speed_10m", function(ok, status, body)
    loading = false
    if not ok then failure = "Ошибка " .. status return end
    result = json.decode(body).current
    storage.set("city", selected)
  end)
end

function onStart()
  selected = storage.get("city", 1)
  load()
end

function onDraw()
  local w = screen.width()
  gfx.clear(theme.background())
  for i, c in ipairs(cities) do
    if ui.button(8, 6 + (i - 1) * 20, w - 16, 16, c.name, i == selected and "selected" or "normal") then
      selected = i
      load()
    end
  end
  local y = 6 + #cities * 20 + 10
  gfx.panel(8, y, w - 16, 60)
  if loading then
    gfx.textCenter("Загрузка…", w / 2, y + 26, theme.secondary(), 0.8)
  elseif failure then
    gfx.textCenter(failure, w / 2, y + 26, theme.danger(), 0.8)
  elseif result then
    gfx.textCenter(string.format("%.0f°C", result.temperature_2m), w / 2, y + 8, theme.accent(), 2.5)
    gfx.textCenter("Ветер " .. result.wind_speed_10m .. " км/ч", w / 2, y + 42, theme.secondary(), 0.7)
  end
end
