-- Выгрузка 3D-моделей оружия Block Strike (viewmodel.lua) в JSON для tools/gen_strike.py — по ним рисуются иконки
-- магазина. Запускает gen_strike.py через LuaJ: java -cp luaj-jse.jar lua <этот файл> (в папке примера).
g3d = setmetatable({}, {__index = function() return function() return 1 end end})
local VM = require("viewmodel")

local function num(v)
  if v == math.floor(v) then return string.format("%d", v) end
  return string.format("%.5f", v)
end

local out = {}
local keys = {}
for k in pairs(VM.MODELS) do keys[#keys + 1] = k end
table.sort(keys)
for _, k in ipairs(keys) do
  local parts = {}
  for _, b in ipairs(VM.MODELS[k].parts) do
    local f = {}
    for i = 1, 8 do f[i] = num(b[i] or 0) end
    parts[#parts + 1] = "[" .. table.concat(f, ",") .. "]"
  end
  out[#out + 1] = string.format('"%s":[%s]', k, table.concat(parts, ","))
end
print("{" .. table.concat(out, ",") .. "}")
