package dev.blockphone.fabric.client.apps.custom;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Поиск по образцу со счётчиком: те же ответы, что у LuaJ, и остановка «вечного» образца. */
class LuaPatternsTest {
    private static final String CASES = """
            local out = {}
            local function add(...) local t = table.pack(...) for i = 1, t.n do out[#out+1] = tostring(t[i]) end out[#out+1] = '|' end
            add(string.find("hello world", "o w"))
            add(string.find("hello world", "o", 6))
            add(string.find("hello world", "l+"))
            add(string.find("hello", "^h(.)(l+)"))
            add(string.find("a.b", ".", 1, true))
            add(string.find("abc", "", 10))
            add(string.find("abc", "b", -1))
            add(string.match("key = value", "(%w+)%s*=%s*(%w+)"))
            add(string.match("  trim  ", "^%s*(.-)%s*$"))
            add(string.match("2026-10-27", "(%d+)-(%d+)-(%d+)"))
            add(string.match("f(a(b)c)d", "%b()"))
            add(string.match("hello", "()ll()"))
            add(string.match("abc", "[^%a]"))
            add(string.match("x=[1,2]", "%[(.-)%]"))
            add(string.match("aaa", "a-b"))
            add(string.match("abab", "(ab)%1"))
            add(string.match("hex: 0x1F", "0x(%x+)"))
            add(string.match("A-z", "[A-Z]%-[a-z]"))
            add(string.gsub("hello world", "o", "0"))
            add(string.gsub("hello world", "(%w+)", "<%1>"))
            add(string.gsub("hello world", "%w+", "%0 %0", 1))
            add(string.gsub("abc", "", "-"))
            add(string.gsub("$name is $age", "%$(%w+)", {name = "Steve", age = 7}))
            add(string.gsub("1 2 3", "%d", function(d) return d * 2 end))
            add(string.gsub("keep", "e", function() return nil end))
            add(string.gsub("^anchor", "^%^", "!"))
            for k, v in string.gmatch("a=1, b=2, c=3", "(%w+)=(%w+)") do add(k, v) end
            for w in ("one two  three"):gmatch("%a+") do add(w) end
            add(("method call"):find("call"))
            add((pcall(string.find, "x", "[a")))
            add(pcall(string.gsub, "x", "(", ""))
            return table.concat(out, ",")
            """;

    private static String run(boolean ours) {
        Globals g = JsePlatform.standardGlobals();
        if (ours) {
            new LuaPatterns(n -> { }).install((LuaTable) g.get("string"));
        }
        return g.load(CASES).call().tojstring();
    }

    @Test
    void sameResultsAsLuaJ() {
        assertEquals(run(false), run(true));
    }

    /** %f (граница) у LuaJ падает с ArrayIndexOutOfBounds; у нас — как в настоящем Lua. */
    @Test
    void frontier() {
        Globals g = JsePlatform.standardGlobals();
        new LuaPatterns(n -> { }).install((LuaTable) g.get("string"));
        assertEquals("THE", g.load("return string.match('THE (quick) fox', '%f[%a]%a+')").call().tojstring());
        assertEquals("fox", g.load("return string.match('THE (quick) fox', '%f[%a]%a+$')").call().tojstring());
        assertEquals(3, g.load("local n = 0 for w in ('one two three'):gmatch('%f[%w]%w+') do n = n + 1 end return n").call().toint());
    }

    @Test
    void runawayPatternIsStopped() {
        long[] charged = {0};
        Globals g = JsePlatform.standardGlobals();
        new LuaPatterns(n -> {
            charged[0] += n;
            if (charged[0] > 3_000_000) {
                throw new IllegalStateException("budget");
            }
        }).install((LuaTable) g.get("string"));
        long start = System.nanoTime();
        assertThrows(IllegalStateException.class,
                () -> g.load("return string.find(string.rep('a', 1000000), '.-b')").call());
        assertTrue(System.nanoTime() - start < 5_000_000_000L, "остановка должна быть быстрой");
        charged[0] = 0;
        LuaValue ok = g.load("return (string.gsub(string.rep('ab ', 100000), '%s+', ''))").call();
        assertEquals(200000, ok.length());
    }
}
