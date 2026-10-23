-- Карты: клетки 1×1, строки сверху вниз (z), символы:
--   #  стена        %  стена другого вида (контейнер, дом, колонна)
--   X  большой ящик (не перепрыгнуть)     x  маленький ящик (можно запрыгнуть)
--   .  пол          ,  пол другого вида (дорога, плитка)
--   A, B  точки закладки бомбы (у карт «Напарников» — только A)   T, C  базы террористов и спецназа (там же покупка)
-- lanes — промежуточные точки маршрутов ботов-террористов к каждой точке (боты выбирают случайно);
-- steps — материалы шагов (ванильные звуки block.<материал>.step): пол «.», пол «,», ящики; wingman — карта «Напарников».
local M = {}

M.WALL_H = 2.6
M.HEIGHT = {["#"] = 99, ["%"] = 99, X = 1.1, x = 0.5}

M.LIST = {
  {key = "dust", name = "Пустыня", steps = {floor = "sand", floor2 = "stone"}, sky = "#FF9CC7E8", fog = "#FFD9DDD6", fogNear = 20, fogFar = 70,
    tex = {wall = "sandstone", wall2 = "sandbrick", floor = "sand", floor2 = "tiles", site = "site_sand", crate = "crate", box = "box"},
    lanes = {A = {{41, 22}, {30, 18}}, B = {{6, 24}, {13, 21}}},
    rows = {
      "################################################",
      "################################################",
      "###BBBBBBBBBBBB####CCCCCCCCCC####AAAAAAAAAAAA###",
      "###BBBBBBBBBBBB,,,,CxCCCCCCCC,,,,AAAAAAAAAAAA###",
      "###BBxBBBBBBBBB,,,,CCCCCCCCCC,,,,AAAAAAAAxxAA###",
      "###BBBBBBBBBBBB,,,,CCCCCCCCCC,,,,AAAAAAAAAAAA###",
      "###BBBBBBXXBBBB####CCCCCCCCCC####AAAAXXAAAAAA###",
      "###BBBBBxBBBBBB#######,,,,#######AAAxXAAAAAAA###",
      "###BBBBBBBBBBBB#######,,,,#######AAAAAAAAAAAA###",
      "###BBBBBBBBBBBB#######,,,,#######AAAAAAAAAAAA###",
      "###BBBBBBBBBBBB#########,,#######AAAAAAAAAAAA###",
      "###BBBBBBBBBBBB#######,,,,#######AAAAAAAAAAAA###",
      "#####.........########,,,,######,,,#####....####",
      "#####.........########,,,,######,,,#####....####",
      "#####.........########,x,,######,,,#####....####",
      "#####.X..#############,,,,######,,,#####....####",
      "#####....#############,,,,######,,,#####....####",
      "#####....#############,,,,,,,,,,,,,#####....####",
      "#####....#############,,,,,,X,x,,,,#####....####",
      "#####....#############,,,,,,,,,,,,,#####....####",
      "#####....,,,,,,,#,,,,,,,,,##############.X..####",
      "#####....,,,x,,,,,,,,,,,,,##############....####",
      "#####....,,,,,,,#,,,,,,,,,##############....####",
      "#####....#############,,,,##############....####",
      "#####....#############,,X,##########........####",
      "#####....#############,,,,##########........####",
      "#####..x.#############,,,,##########....###x####",
      "#####....#############,,############........####",
      "#######..#############,,,,##########........####",
      "#####....#############,,,,##############....####",
      "#####....#############,,,,##############..X.####",
      "#####....#############,,,,##############....####",
      "#####....########TTTTTTTTTTTTTT#########....####",
      "######...........TTTTTTTTTTTTTT.............####",
      "######...........TTTxTTTTTTTTTT.............####",
      "######...........TTTTTTTTTTxTTT.............####",
      "#################TTTTTTTTTTTTTT#################",
      "#################TTTTTTTTTTTTTT#################",
      "################################################",
      "################################################",
    }},
  {key = "port", name = "Порт", steps = {floor = "stone", floor2 = "stone", box = "metal"}, sky = "#FF8FA9C0", fog = "#FFAEBBC6", fogNear = 16, fogFar = 60,
    tex = {wall = "concrete", wall2 = "container", floor = "asphalt", floor2 = "asphalt_lines", site = "site_port", crate = "crate_metal", box = "box"},
    lanes = {A = {{5, 22}, {10, 17}}, B = {{37, 28}, {33, 19}}},
    rows = {
      "##############################################",
      "##############################################",
      "##############################CCCCCCCCCCCC####",
      "###AAAAAAAAAAAA###############CCCCCCCCCCCC####",
      "###AAAAAAAAAxAA###############CCCCCCCCCCCC####",
      "###AAAAAAAAAAAA...............CCCCCCCCCCCC####",
      "###AAAXXxAAAAAA..........x....CCCCCCCCCCCC####",
      "###AAAAAAAAAAAA...............######....######",
      "###AAAAAAAxXAAA#########,,,#########....######",
      "###AAAAAAAAAAAA#########,,,#########....######",
      "###AAAAAAAAAAAA#########,,,#########....######",
      "###AAAAAAAAAAAA#########,,,#########....######",
      "####....######,,,,,,,,,,,,,,,,,,####....######",
      "####....######,,,,,,,,,,,,,,,x,,##BBBBBBBBB###",
      "####..x.######,X,,,,,,,,,,,,,,,,##BBBBBBBBB###",
      "####....######,,,%%%%,,,,%%%%,,,##BBBBBBBxB###",
      "####..........,,,,,,,,,,,,,,,,,,##BBBXXBBBB###",
      "####..........,,,,,,,,,,,,,,,,,,##BBxXBBBBB###",
      "####..........,,,,,,,,%%,,,,,,,,..BBBBBBBBB###",
      "####....######,,,,,,,,%%,,,,,,,,..BBBBBBBBB###",
      "####....######,,,,,,,,,,,,,,,,,,..BBBBBBBBB###",
      "####....######,,,%%%%,,,,%%%%,,,##BBBBBxXBB###",
      "####.X..######,,,,,,,,,,,,,,,,,,##BBBBBBBBB###",
      "####....######,,,,,,,,,,,,,,,,X,##BBBBBBBBB###",
      "####....######,,x,,,,,,,,,,,,,,,####....######",
      "####....######,,,,,,,,,,,,,,,,,,####....######",
      "####....##########,,,,##############....######",
      "####....##########,,,,##############....######",
      "####....##########,,,,##############....######",
      "####....##########,,,,##############....######",
      "####....##########,,,,##############....######",
      "####....##########,,,,##############....######",
      "####TTTTTTTTTTTT##,,,,##############....######",
      "####TTTTTTTTTTTT......................########",
      "####TxTTTTTTTTTT..........X...x.......########",
      "####TTTTTTTTTTTT......................########",
      "####TTTTTTTTTTTT##############################",
      "####TTTTTTTTTTTT##############################",
      "##############################################",
      "##############################################",
    }},
  {key = "factory", name = "Завод", steps = {floor = "stone", floor2 = "metal", box = "metal"}, sky = "#FF1A1C20", fog = "#FF15171B", fogNear = 10, fogFar = 40, ceiling = "ceiling",
    tex = {wall = "brick", wall2 = "metal", floor = "concrete_floor", floor2 = "grate", site = "site_factory", crate = "crate_metal", box = "box"},
    light = {-0.3, -1, -0.5, 0.42},
    lanes = {A = {{35, 26}, {28, 18}}, B = {{8, 26}, {15, 18}}},
    rows = {
      "############################################",
      "############################################",
      "#################CCCCCCCCCC#################",
      "#################CCCCCCCCCC#################",
      "#########........CCCxCCCCCC........#########",
      "#########........CCCCCCCCCC......x.#########",
      "#########........CCCCCCCCCC........#########",
      "#########...#########,,#########...#########",
      "#########...#########,,#########...#########",
      "#########...#########,,#########...#########",
      "###BBBBBBBBBBB#######,,#######AAAAAAAAAAA###",
      "###BBBBBBBBBBB#######,,#######AAAAAAAAAAA###",
      "###BXBBBBBBBxB#######,,#######AAAAAAXXAAA###",
      "###BBBBBBBBBBB#######,,#######AAA%AxXAAAA###",
      "###BBBBBBBBBBB###,,,,,,,,,,###AAA%AAAAAAA###",
      "###BBBBBB%%BBB###,,,,,,,,,x###AAAAAAAAAAA###",
      "###BBBXBBBBBBB###,,%,,,,%,,###AAAAAAAAAAA###",
      "###BBBXxBBBBBB...,,,,,,,,,,...AAAAAAAAAXA###",
      "###BBBBBBBBBBB...,x,,,,,,,,...AxAAAAAAAAA###",
      "###BBBBBBBBBBB###,,,,,,,,X,###AAAAAAAAAAA###",
      "#######...#######,,,,,,,,,,#######...#######",
      "#######...#######,,%,,,,%,,#######...#######",
      "#######...#######,,,,,,,,,,#######...#######",
      "#######...#######,,,,,,,,,,#######...#######",
      "###.......###########,,###########.......###",
      "###.....x.###########,,###########....%..###",
      "###.......###########,,###########.......###",
      "###.......###########,,###########.......###",
      "#######...###########,,###########.X.#######",
      "#######...###########,,###########...#######",
      "#######...###########,,###########...#######",
      "#######...#######TTTTTTTTTT#######...#######",
      "#######..........TTTTTTTTTT..........#######",
      "#######..........TTTTTTxTTT..........#######",
      "#######..........TTTTTTTTTT..........#######",
      "#################TTTTTTTTTT#################",
      "############################################",
      "############################################",
    }},
  {key = "village", name = "Деревня", steps = {floor = "grass", floor2 = "gravel"}, sky = "#FF88B6F0", fog = "#FFB8D2EE", fogNear = 20, fogFar = 70,
    tex = {wall = "cobble", wall2 = "planks", floor = "grass", floor2 = "path", site = "site_village", crate = "hay", box = "crate"},
    lanes = {A = {{6, 22}, {22, 8}}, B = {{20, 33}, {33, 20}}},
    rows = {
      "############################################",
      "############################################",
      "################################CCCCCCCCCC##",
      "###AAAAAAAAAAA##################CCCCCCCCCC##",
      "###AAAAAAAAAAA..................CCCCCCCCCC##",
      "###AAAAAAAxXAA.............x....CCCCCCCCCC##",
      "###AAAA%%AAAAA..................CCCCCCCCCC##",
      "###AAAA%%AAAAA######,,,,########CCCCCCCCCC##",
      "###AAAAAAAAAAA######,,,,########CCCCCCCCCC##",
      "###AXxAAAAAAAA######,,,,########CCCCCCCCCC##",
      "###AAAAAAAAAAA######,,,,###########....#####",
      "###AAAAAAAAAAA######,,,,###########....#####",
      "#####....######....#,,,,###########....#####",
      "#####....######.%...,,,,###########....#####",
      "#####....######.....,,,,###########....#####",
      "#####....######....#,xX,###########.x..#####",
      "#####....###########,,,,###########....#####",
      "#####....###########,,,,###########....#####",
      "#####....###,,,,,,,,,,,,,,,,,,,,###....#####",
      "#####.......,,,,,,,,,,,,,,,,,,,,.......#####",
      "#####..X....,X,,,,,,,,,,,,,,,,X,.......#####",
      "#####....###,,,,,,,,,,,,,,,,,,,,###....#####",
      "#####....###########,,,,###########....#####",
      "#####....###########,,,,#.....#####....#####",
      "#####....###########,,,x......#####....#####",
      "#####.x..###########,,,,..%%..#####....#####",
      "#####....###########,,,,#.....#####....#####",
      "#####....###########,,,,#.....#####....#####",
      "#####....###########,,,,######BBBBBBBBBBB###",
      "#####....###########,,,,######BXxBBBBBBBB###",
      "##TTTTTTTTTT########,,,,######BBBBBBBBBBB###",
      "##TTTTTTTTTT########,,,,######BBBB%%BBBBB###",
      "##TTTTTTTTTT..................BBBB%%BBBBB###",
      "##TTTTTTTTTT....X........x....BBBBBBBxXBB###",
      "##TTTTTTTTTT..................BBBBBBBBBBB###",
      "##TTTTTTTTTT##################BBBBBBBBBBB###",
      "##TTTTTTTTTT##################BBBBBBBBBBB###",
      "##TTTTTTTTTT################################",
      "############################################",
      "############################################",
    }},
  {key = "snow", name = "Ледник", steps = {floor = "snow", floor2 = "stone"}, sky = "#FFB4C8DC", fog = "#FFDCE6F0",
    fogNear = 14, fogFar = 55,
    tex = {wall = "snowstone", wall2 = "wood_dark", floor = "snow", floor2 = "ice", site = "site_snow", crate = "crate", box = "box"},
    light = {-0.4, -1, -0.2, 0.6},
    lanes = {A = {{22, 33}, {33, 19}}, B = {{6, 20}, {23, 10}}},
    rows = {
      "##############################################",
      "##############################################",
      "##############################################",
      "###BBBBBBBBBBBB##################CCCCCCCCCC###",
      "###BBBBBBBBBBBB##################CCCCCCCCCC###",
      "###BBXxBBBBBBBB,,,,,,,,,,,,,,,,,,CCCCCCCCCC###",
      "###BBBBBBBBBBBB,x,,,,,,,,,,,,,,,,CCCCCCCCCC###",
      "###BBBB%%BBBBBB,,,,,,,,,,,,,,,,,,CCCCCCCCCC###",
      "###BBBBBBBBBBBB#######...########CCCCCCCCCC###",
      "###BBBBBBBBBBBB#######...########CCCCCCCCCC###",
      "###BBBBBBBBxXBB#######...############,,,,#####",
      "###BBBBBBBBBBBB#######...############,,,,#####",
      "###BBBBBBBBBBBB#######...############,,,,#####",
      "#####....#############...############,,,,#####",
      "#####....#######..............#######,,x,#####",
      "#####....#######.Xx...........#######,,,,#####",
      "#####....#######..............#######,,,,#####",
      "#####....#######...%%....%%...#######,,,,#####",
      "#####....#######.....................,,,,#####",
      "#####....#######......%%.............,,,,#####",
      "#####.X..#######.....................,X,,#####",
      "#####....#######..............#######,,,,#####",
      "#####..............%......%...#######,,,,#####",
      "#####.......x.................#######,,,,#####",
      "#####......................xX.#######,,,,#####",
      "#####....#######..............#######,,,,#####",
      "#####..x.###########....#######AAAAAAAAAAAA###",
      "#####....###########....#######AAAAAAAAAAAA###",
      "#####....###########....#######AAXXAAAAAAAA###",
      "#####....###########....#######AAAxAAAAAAAA###",
      "###TTTTTTTTTT#######....#######AAAA%%AAAAAA###",
      "###TTTTTTTTTT#######....#######AAAAAAAAAAAA###",
      "###TTTTTTTTTT..................AAAAAAAAAAAA###",
      "###TTTTTTTTTT.X.........x......AAAAAAAAAXxA###",
      "###TTTTTTTTTT..................AAAAAAAAAAAA###",
      "###TTTTTTTTTT##################AAAAAAAAAAAA###",
      "###TTTTTTTTTT#################################",
      "##############################################",
      "##############################################",
      "##############################################",
    }},
  {key = "office", name = "Офис", steps = {floor = "wool", floor2 = "stone"}, sky = "#FF202428", fog = "#FF1C1F24",
    fogNear = 12, fogFar = 45, ceiling = "ceiling_office",
    tex = {wall = "plaster", wall2 = "wood_panel", floor = "carpet", floor2 = "office_tiles", site = "site_office",
      crate = "crate_metal", box = "box"},
    light = {-0.2, -1, -0.4, 0.5},
    lanes = {A = {{6, 6}, {22, 13}}, B = {{6, 29}, {22, 22}}},
    rows = {
      "##############################################",
      "##############################################",
      "#################AAAAAAAAAAAA#################",
      "#################AXxAAAAAAAAA#################",
      "#################AAAAAAAAAAAA#################",
      "#####............AAA%AAAA%AAA,,,,,,,,,,,,#####",
      "#####...X........AAAAAAAAAAAA,,,,,,,,,,,x#####",
      "#####............AAAAAAAAAAAA,,,,,,,,,,,,#####",
      "#####...###.....#AAAAAAAAAxXA#########,,,#####",
      "#####...###.....#AAAAAAAAAAAA#########,,,#####",
      "#####...###.x...#AAAAAAAAAAAA#########,,,#####",
      "#####...###.....######..##############,,,#####",
      "#####...##############..##############,,,#####",
      "##TTTTTTTT############..############CCCCCCCC##",
      "##TTTTTTTT############..############CCCCCCCC##",
      "##TTTTTTTT###,,,,,,,,,,,,,,,,,,,,###CCCCCCCC##",
      "##TTTTTTTT###,X,,,,,%,,,,,,,,,,x,###CCCCCCCC##",
      "##TTTTTTTT...,,,%,,,,,,,,,,,,%,,,...CCCCCCCC##",
      "##TTTTTTTT...,,,,,,,,,,,,,,,,,,,,...CCCCCCCC##",
      "##TTTTTTTT###,x,,,,,,,,,,%,,,,,X,###CCCCCCCC##",
      "##TTTTTTTT###,,,,,,,,,,,,,,,,,,,,###CCCCCCCC##",
      "##TTTTTTTT############..############CCCCCCCC##",
      "##TTTTTTTT############..############CCCCCCCC##",
      "#####...##############..##############,,,#####",
      "#####...##############..##############,,,#####",
      "#####...#########BBBBBBBBBBBB#########,,,#####",
      "#####...#########BBBBBBBBBBBB#########,,,#####",
      "#####...#########BBBBBBBBBxXB#########,,,#####",
      "#####............BBBBBBBBBBBB,,,,,,,,,,,,#####",
      "#####.x..........BBBBBBBBBBBB,,,,,,,,,,X,#####",
      "#####............BBB%BBBB%BBB,,,,,,,,,,,,#####",
      "#################BXxBBBBBBBBB#################",
      "#################BBBBBBBBBBBB#################",
      "#################BBBBBBBBBBBB#################",
      "##############################################",
      "##############################################",
    }},
  {key = "temple", name = "Храм", steps = {floor = "stone", floor2 = "grass"}, sky = "#FF8EC4B0", fog = "#FFA8C8B4",
    fogNear = 16, fogFar = 60,
    tex = {wall = "temple_stone", wall2 = "temple_glyph", floor = "temple_floor", floor2 = "moss", site = "site_temple",
      crate = "crate", box = "crate"},
    lanes = {A = {{5, 28}, {14, 19}}, B = {{31, 34}, {29, 23}}},
    rows = {
      "############################################",
      "############################################",
      "################CCCCCCCCCCCC################",
      "###AAAAAAAAAAA##CCCCCCCCCCCC################",
      "###AXxAAAAAAAA,,CCCCCCCCCCCC,,,,,,,,########",
      "###AAAAAAAAAAA,,CCCCCCCCCCCC,,,,,,,,########",
      "###AAAAAAAAAAA,,CCCCCCCCCCCC,,,,,,,,########",
      "###AAAAAAAAAAA##CCCCCCCCCCCC#######,,,######",
      "###AAAAA%%AAAA######....###########,,,######",
      "###AAAAAAAAAAA######....###########,,,######",
      "###AAAAAAAAAAA######....###########,,,######",
      "###AAAAAAAAxXA######....###########,,,######",
      "###AAAAAAAAAAA######....###########,x,######",
      "###AAAAAAAAAAA######....###########,,,######",
      "####...#############....###########,,,######",
      "####...#####,,,,,,,,,,,,,,,,#######,,,######",
      "####...#####,,,,,,,,,,,,,xX,#######,,,######",
      "####...#####,,,%%,,,,,,%%,,,#######,,,######",
      "####........,,,,,,,,,,,,,,,,#######,,,######",
      "####........,,,,,,,%%,,,,,,,#######,,,######",
      "####........,,,,,,,,,,,,,,,,###BBBBBBBBBBB##",
      "####...#####,,,,,,,,,,,,,,,,###BXxBBBBBBBB##",
      "####...#####,,,%,,,,,,,,%,,,...BBBBBBBBBBB##",
      "####...#####,Xx,,,,,,,,,,,,,...BBBBBBBBBBB##",
      "####.x.#####,,,,,,,,,,,,,,,,...BBBBB%BBBBB##",
      "####...#############....#######BBBBBBBBBBB##",
      "####...#############....#######BBBBBBBBBBB##",
      "####...#############....#######BBBBBBBxXBB##",
      "####...#############....#######BBBBBBBBBBB##",
      "####...#############....#######BBBBBBBBBBB##",
      "####.X.#############....#########...########",
      "####...#########TTTTTTTTTTTT#####...########",
      "####...#########TTTTTTTTTTTT#####...########",
      "####............TTTTTxTTTTTT........########",
      "####............TTTTTTTTTTTT..X.....########",
      "####............TTTTTTTTTTTT........########",
      "################TTTTTTTTTTTT################",
      "################TTTTTTTTTTTT################",
      "############################################",
      "############################################",
    }},
  -- Карты режима «Напарники» (2 на 2): маленькие, одна точка A.
  {key = "lake", name = "Озеро", wingman = true, steps = {floor = "grass", floor2 = "wood"}, sky = "#FF8CC0F0",
    fog = "#FFB8D4F0", fogNear = 18, fogFar = 60,
    tex = {wall = "logs", wall2 = "planks", floor = "grass", floor2 = "dock", site = "site_village", crate = "hay", box = "crate"},
    lanes = {A = {{15, 23}, {14, 13}}},
    rows = {
      "################################",
      "################################",
      "######################CCCCCCCC##",
      "######################CCCCCCCC##",
      "######################CCCCCCCC##",
      "######################CCCCCCCC##",
      "######################CCCCCCCC##",
      "#####,,,,,,,,,,,,,,,,,CCCCCCCC##",
      "#####,X,,,,,,,,,,,,,,,###...####",
      "#####,,,,,,,,,,,,,,,,,###...####",
      "#####,,,#################.x.####",
      "#####,,,#################...####",
      "#####,,,###.........#####...####",
      "#####,,,.....x......#####...####",
      "#####,,,......%%....#####...####",
      "#####,,,###.........#####...####",
      "#####,,,###.........#####...####",
      "#####,,,########....AAAAAAAAAA##",
      "#####,,,########....AXxAAAAAAA##",
      "#####,,,########....AAAAAAAAAA##",
      "##TTTTTTTT##########AAA%%AAAAA##",
      "##TTTTTTTT##########AAAAAAAAAA##",
      "##TTTTTTTT..........AAAAAAAAAA##",
      "##TTTTTTTT..X....x..AAAAAAxXAA##",
      "##TTTTTTTT..........AAAAAAAAAA##",
      "##TTTTTTTT##########AAAAAAAAAA##",
      "################################",
      "################################",
    }},
  {key = "alley", name = "Переулок", wingman = true, steps = {floor = "sand", floor2 = "stone"}, sky = "#FF9CC7E8",
    fog = "#FFD9DDD6", fogNear = 18, fogFar = 60,
    tex = {wall = "sandbrick", wall2 = "sandstone", floor = "sand", floor2 = "tiles", site = "site_sand", crate = "crate", box = "box"},
    lanes = {A = {{5, 20}, {16, 12}}},
    rows = {
      "##############################",
      "############CCCCCCCCC#########",
      "############CCCCCCCCC#########",
      "############CCCCCCCCC......###",
      "############CCCCCCCCC......###",
      "############CCCCCCCCC......###",
      "##AAAAAAAAAA###...######...###",
      "##AXxAAAAAAA###...######...###",
      "##AAAAAAAAAA###...######...###",
      "##AAAAAAAAAA#,,,,,,,,###...###",
      "##AAA%%AAAAA.,x,,,,,,###...###",
      "##AAAAAAAAAA.,,,%%,,,###...###",
      "##AAAAAAAAAA.,,,,,,,,###.X.###",
      "##AAAAAAAAAA#,,,,,,X,###...###",
      "##AAAAAAAxXA#,,,,,,,,###...###",
      "##AAAAAAAAAA###,,,######...###",
      "####...########,,,######...###",
      "####...########,,,######...###",
      "####...########,,,######.x.###",
      "####...########,,,######...###",
      "####...########,,,######...###",
      "####...########,,,######...###",
      "####.x.########,,,######...###",
      "####...#####TTTTTTTTT###...###",
      "####........TTTTTTTTT###...###",
      "####........TTTTTTTTT......###",
      "#######.....TTTTTTTTT......###",
      "############TTTTTTTTT......###",
      "############TTTTTTTTT#########",
      "##############################",
    }},
}

local floor, sqrt, ceil = math.floor, math.sqrt, math.ceil

-- Номера карт режима: обычные (wingman = false) или «Напарников».
function M.forMode(wingman)
  local out = {}
  for i, def in ipairs(M.LIST) do
    if (def.wingman == true) == (wingman == true) then out[#out + 1] = i end
  end
  return out
end

function M.load(index)
  local def = M.LIST[index]
  local m = {def = def, index = index, W = #def.rows[1], H = #def.rows, c = {}, spawns = {{}, {}}, site = {A = {}, B = {}},
    flows = {}, flowCount = 0}
  local W = m.W
  for z = 0, m.H - 1 do
    local row = def.rows[z + 1]
    for x = 0, W - 1 do
      local ch = row:sub(x + 1, x + 1)
      local i = z * W + x + 1
      m.c[i] = ch
      if ch == "T" then m.spawns[1][#m.spawns[1] + 1] = i
      elseif ch == "C" then m.spawns[2][#m.spawns[2] + 1] = i
      elseif ch == "A" or ch == "B" then m.site[ch][#m.site[ch] + 1] = i end
    end
  end
  m.siteCenter = {}
  m.sites = {}
  for _, s in ipairs({"A", "B"}) do
    if #m.site[s] > 0 then m.sites[#m.sites + 1] = s end
  end
  for _, s in ipairs(m.sites) do
    local sx, sz = 0, 0
    for _, i in ipairs(m.site[s]) do
      local x, z = M.xz(m, i)
      sx, sz = sx + x, sz + z
    end
    m.siteCenter[s] = {sx / #m.site[s], sz / #m.site[s]}
  end
  -- Зона покупки: база и 3 клетки от неё.
  m.buy = {}
  for team = 1, 2 do
    local dist = M.bfs(m, m.spawns[team], function(ch) return M.HEIGHT[ch] == nil or ch == "x" end)
    local zone = {}
    for i, d in pairs(dist) do if d <= 3 then zone[i] = true end end
    m.buy[team] = zone
  end
  return m
end

function M.xz(m, i)
  local k = i - 1
  return k % m.W + 0.5, floor(k / m.W) + 0.5
end

function M.idx(m, x, z)
  local cx, cz = floor(x), floor(z)
  if cx < 0 or cz < 0 or cx >= m.W or cz >= m.H then return nil end
  return cz * m.W + cx + 1
end

function M.cell(m, cx, cz)
  if cx < 0 or cz < 0 or cx >= m.W or cz >= m.H then return "#" end
  return m.c[cz * m.W + cx + 1]
end

function M.h(m, cx, cz)
  if cx < 0 or cz < 0 or cx >= m.W or cz >= m.H then return 99 end
  return M.HEIGHT[m.c[cz * m.W + cx + 1]] or 0
end

-- Высота опоры под кругом радиуса r (самая высокая клетка под ним).
function M.ground(m, x, z, r)
  local a = M.h(m, floor(x - r), floor(z - r))
  local b = M.h(m, floor(x + r), floor(z - r))
  local c = M.h(m, floor(x - r), floor(z + r))
  local d = M.h(m, floor(x + r), floor(z + r))
  return math.max(a, b, c, d)
end

-- Сдвинуть сущность (x, y, z) с учётом стен и ящиков; step — на сколько можно шагнуть вверх.
function M.move(m, e, dx, dz, r, step)
  local top = e.y + (step or 0.05)
  local hitX, hitZ = false, false
  if dx ~= 0 then
    if M.ground(m, e.x + dx, e.z, r) <= top then e.x = e.x + dx else hitX = true end
  end
  if dz ~= 0 then
    if M.ground(m, e.x, e.z + dz, r) <= top then e.z = e.z + dz else hitZ = true end
  end
  return hitX, hitZ
end

function M.site(m, x, z)
  local i = M.idx(m, x, z)
  local ch = i and m.c[i]
  if ch == "A" or ch == "B" then return ch end
  return nil
end

function M.inBuyZone(m, team, x, z)
  local i = M.idx(m, x, z)
  return i ~= nil and m.buy[team][i] == true
end

-- Прямая видимость между точками (с высотой): стены, ящики выше линии и дымы закрывают.
function M.los(m, x1, y1, z1, x2, y2, z2, smokes)
  local dx, dy, dz = x2 - x1, y2 - y1, z2 - z1
  local len = sqrt(dx * dx + dz * dz)
  local steps = ceil(len / 0.25)
  local HEIGHT, c, W, H = M.HEIGHT, m.c, m.W, m.H
  for k = 1, steps - 1 do
    local t = k / steps
    local cx, cz = floor(x1 + dx * t), floor(z1 + dz * t)
    if cx < 0 or cz < 0 or cx >= W or cz >= H then return false end
    local h = HEIGHT[c[cz * W + cx + 1]]
    if h and h > y1 + dy * t then return false end
  end
  if smokes then
    for _, s in ipairs(smokes) do
      if s.r > 0.5 then
        -- расстояние от центра дыма до отрезка на плоскости
        local t = len > 0 and (((s.x - x1) * dx + (s.z - z1) * dz) / (len * len)) or 0
        t = math.max(0, math.min(1, t))
        local px, pz = x1 + dx * t - s.x, z1 + dz * t - s.z
        if px * px + pz * pz < s.r * s.r * 0.8 then return false end
      end
    end
  end
  return true
end

-- Клетки, по которым ходят боты (без ящиков).
function M.walkable(ch) return M.HEIGHT[ch] == nil end

function M.bfs(m, starts, pass)
  pass = pass or M.walkable
  local dist, queue, head = {}, {}, 1
  for _, i in ipairs(starts) do
    if dist[i] == nil then
      dist[i] = 0
      queue[#queue + 1] = i
    end
  end
  local W, c, size = m.W, m.c, m.W * m.H
  while head <= #queue do
    local i = queue[head]
    head = head + 1
    local d = dist[i] + 1
    local x = (i - 1) % W
    local n1, n2, n3, n4 = (x > 0) and i - 1 or nil, (x < W - 1) and i + 1 or nil, i - W, i + W
    if n1 and dist[n1] == nil and pass(c[n1]) then dist[n1] = d queue[#queue + 1] = n1 end
    if n2 and dist[n2] == nil and pass(c[n2]) then dist[n2] = d queue[#queue + 1] = n2 end
    if n3 > 0 and dist[n3] == nil and pass(c[n3]) then dist[n3] = d queue[#queue + 1] = n3 end
    if n4 <= size and dist[n4] == nil and pass(c[n4]) then dist[n4] = d queue[#queue + 1] = n4 end
  end
  return dist
end

-- Поле расстояний до цели (кэш по ключу): боты идут туда, где расстояние меньше.
function M.flow(m, key, targets)
  local f = m.flows[key]
  if f then return f end
  if m.flowCount > 40 then
    m.flows, m.flowCount = {}, 0
  end
  f = M.bfs(m, targets)
  m.flows[key] = f
  m.flowCount = m.flowCount + 1
  return f
end

function M.flowTo(m, x, z)
  local i = M.nearestWalkable(m, x, z)
  return M.flow(m, "c" .. i, {i}), i
end

function M.nearestWalkable(m, x, z)
  local cx, cz = floor(x), floor(z)
  for r = 0, 4 do
    for oz = -r, r do
      for ox = -r, r do
        if math.abs(ox) == r or math.abs(oz) == r then
          local ch = M.cell(m, cx + ox, cz + oz)
          if M.walkable(ch) then return (cz + oz) * m.W + cx + ox + 1 end
        end
      end
    end
  end
  return m.spawns[1][1]
end

-- Можно ли пройти по прямой от (x1, z1) до (x2, z2) кругом радиуса r (для сглаживания пути).
function M.clear(m, x1, z1, x2, z2, r)
  local dx, dz = x2 - x1, z2 - z1
  local steps = ceil(sqrt(dx * dx + dz * dz) / 0.3)
  for k = 1, steps do
    local t = k / steps
    local x, z = x1 + dx * t, z1 + dz * t
    if not (M.walkable(M.cell(m, floor(x - r), floor(z - r))) and M.walkable(M.cell(m, floor(x + r), floor(z - r)))
        and M.walkable(M.cell(m, floor(x - r), floor(z + r))) and M.walkable(M.cell(m, floor(x + r), floor(z + r)))) then
      return false
    end
  end
  return true
end

-- Следующая точка пути по полю расстояний: спуск по соседям, затем как можно дальше по прямой.
function M.nextPoint(m, dist, x, z)
  local i = M.idx(m, x, z)
  if not i or dist[i] == nil then
    i = M.nearestWalkable(m, x, z)
    if dist[i] == nil then return nil end
    return M.xz(m, i)
  end
  if dist[i] == 0 then return nil end
  local W = m.W
  local best, bestX, bestZ = i, nil, nil
  for _ = 1, 6 do
    local d = dist[best]
    local nb = nil
    for _, n in ipairs({best - 1, best + 1, best - W, best + W}) do
      local nd = dist[n]
      if nd and nd < d then d = nd nb = n end
    end
    if not nb then break end
    local nx, nz = M.xz(m, nb)
    if bestX == nil or M.clear(m, x, z, nx, nz, 0.3) then
      best, bestX, bestZ = nb, nx, nz
    else
      break
    end
  end
  return bestX, bestZ
end

-- Случайная клетка, по которой ходят боты, из списка.
function M.randomCell(m, list, rnd)
  for _ = 1, 30 do
    local i = list[rnd(1, #list)]
    if M.walkable(m.c[i]) then return i end
  end
  return list[1]
end

return M
