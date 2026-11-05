/*
 * Сопоставление с образцом Lua (string.find / match / gmatch / gsub) — код из LuaJ 3.0.1 (org.luaj.vm2.lib.StringLib),
 * дополненный счётчиком шагов.
 *
 * Copyright (c) 2009-2011 Luaj.org. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dev.blockphone.fabric.client.apps.custom;

import org.luaj.vm2.Buffer;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * Поиск по образцу для своих приложений. В LuaJ он выполняется внутри Java и не виден счётчику инструкций: образец
 * вроде {@code ".-b"} по строке в 1 МБ — это ~10¹² шагов, игра зависла бы на десятки минут. Здесь каждые
 * {@link #STEPS_PER_CHARGE} шагов списывается одна «инструкция» из бюджета вызова ({@link Meter}) — превышение
 * останавливает приложение так же, как бесконечный цикл. Обычная работа с текстом (сотни тысяч шагов) этого не замечает.
 */
final class LuaPatterns {
    /** Списать n инструкций (бросает исключение, если бюджет вызова исчерпан). */
    interface Meter {
        void charge(long instructions);
    }

    static final int STEPS_PER_CHARGE = 16;
    private static final int BATCH = 4096;

    private final Meter meter;
    private int steps;

    LuaPatterns(Meter meter) {
        this.meter = meter;
    }

    private void step() {
        if (++steps >= BATCH) {
            steps = 0;
            meter.charge(BATCH / STEPS_PER_CHARGE);
        }
    }

    /** Заменить find/match/gmatch/gsub в таблице string. */
    void install(LuaTable string) {
        string.set("find", fn(a -> findAux(a, true)));
        string.set("match", fn(a -> findAux(a, false)));
        string.set("gmatch", fn(this::gmatch));
        string.set("gsub", fn(this::gsub));
    }

    private interface Body {
        Varargs call(Varargs args);
    }

    private static LuaValue fn(Body body) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return body.call(args);
            }
        };
    }

    private Varargs gmatch(Varargs args) {
        LuaString src = args.checkstring(1);
        LuaString pat = args.checkstring(2);
        MatchState ms = new MatchState(src, pat);
        int srclen = src.length();
        int[] soffset = {0};
        return fn(a -> {
            for (; soffset[0] < srclen; soffset[0]++) {
                step();
                ms.reset();
                int res = ms.match(soffset[0], 0);
                if (res >= 0) {
                    int soff = soffset[0];
                    soffset[0] = res;
                    return ms.pushCaptures(true, soff, res);
                }
            }
            return LuaValue.NIL;
        });
    }

    private Varargs gsub(Varargs args) {
        LuaString src = args.checkstring(1);
        int srclen = src.length();
        LuaString p = args.checkstring(2);
        LuaValue repl = args.arg(3);
        int maxS = args.optint(4, srclen + 1);
        boolean anchor = p.length() > 0 && p.charAt(0) == '^';
        Buffer lbuf = new Buffer(srclen);
        MatchState ms = new MatchState(src, p);
        int soffset = 0;
        int n = 0;
        while (n < maxS) {
            step();
            ms.reset();
            int res = ms.match(soffset, anchor ? 1 : 0);
            if (res != -1) {
                n++;
                ms.addValue(lbuf, soffset, res, repl);
            }
            if (res != -1 && res > soffset) {
                soffset = res;
            } else if (soffset < srclen) {
                lbuf.append((byte) src.luaByte(soffset++));
            } else {
                break;
            }
            if (anchor) {
                break;
            }
        }
        lbuf.append(src.substring(soffset, srclen));
        return LuaValue.varargsOf(lbuf.tostring(), LuaValue.valueOf(n));
    }

    private static final LuaString SPECIALS = LuaValue.valueOf("^$*+?.([%-");

    private Varargs findAux(Varargs args, boolean find) {
        LuaString s = args.checkstring(1);
        LuaString pat = args.checkstring(2);
        int init = args.optint(3, 1);
        if (init > 0) {
            init = Math.min(init - 1, s.length());
        } else if (init < 0) {
            init = Math.max(0, s.length() + init);
        }
        boolean fastMatch = find && (args.arg(4).toboolean() || pat.indexOfAny(SPECIALS) == -1);
        if (fastMatch) {
            int result = s.indexOf(pat, init);
            if (result != -1) {
                return LuaValue.varargsOf(LuaValue.valueOf(result + 1), LuaValue.valueOf(result + pat.length()));
            }
        } else {
            MatchState ms = new MatchState(s, pat);
            boolean anchor = false;
            int poff = 0;
            if (pat.length() > 0 && pat.luaByte(0) == '^') {
                anchor = true;
                poff = 1;
            }
            int soff = init;
            do {
                step();
                int res;
                ms.reset();
                if ((res = ms.match(soff, poff)) != -1) {
                    if (find) {
                        return LuaValue.varargsOf(LuaValue.valueOf(soff + 1), LuaValue.valueOf(res), ms.pushCaptures(false, soff, res));
                    } else {
                        return ms.pushCaptures(true, soff, res);
                    }
                }
            } while (soff++ < s.length() && !anchor);
        }
        return LuaValue.NIL;
    }

    private static final int L_ESC = '%';
    private static final int MAX_CAPTURES = 32;
    private static final int CAP_UNFINISHED = -1;
    private static final int CAP_POSITION = -2;

    private static final byte MASK_ALPHA = 0x01;
    private static final byte MASK_LOWERCASE = 0x02;
    private static final byte MASK_UPPERCASE = 0x04;
    private static final byte MASK_DIGIT = 0x08;
    private static final byte MASK_PUNCT = 0x10;
    private static final byte MASK_SPACE = 0x20;
    private static final byte MASK_CONTROL = 0x40;
    private static final byte MASK_HEXDIGIT = (byte) 0x80;
    private static final byte[] CHAR_TABLE = new byte[256];

    static {
        for (int i = 0; i < 256; ++i) {
            char c = (char) i;
            CHAR_TABLE[i] = (byte) ((Character.isDigit(c) ? MASK_DIGIT : 0)
                    | (Character.isLowerCase(c) ? MASK_LOWERCASE : 0)
                    | (Character.isUpperCase(c) ? MASK_UPPERCASE : 0)
                    | ((c < ' ' || c == 0x7F) ? MASK_CONTROL : 0));
            if ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || (c >= '0' && c <= '9')) {
                CHAR_TABLE[i] |= MASK_HEXDIGIT;
            }
            if ((c >= '!' && c <= '/') || (c >= ':' && c <= '@')) {
                CHAR_TABLE[i] |= MASK_PUNCT;
            }
            if ((CHAR_TABLE[i] & (MASK_LOWERCASE | MASK_UPPERCASE)) != 0) {
                CHAR_TABLE[i] |= MASK_ALPHA;
            }
        }
        CHAR_TABLE[' '] = MASK_SPACE;
        CHAR_TABLE['\r'] |= MASK_SPACE;
        CHAR_TABLE['\n'] |= MASK_SPACE;
        CHAR_TABLE['\t'] |= MASK_SPACE;
        CHAR_TABLE[0x0C] |= MASK_SPACE;
        CHAR_TABLE['\f'] |= MASK_SPACE;
    }

    private final class MatchState {
        final LuaString s;
        final LuaString p;
        int level;
        final int[] cinit = new int[MAX_CAPTURES];
        final int[] clen = new int[MAX_CAPTURES];

        MatchState(LuaString s, LuaString pattern) {
            this.s = s;
            this.p = pattern;
        }

        void reset() {
            level = 0;
        }

        private void addS(Buffer lbuf, LuaString news, int soff, int e) {
            int l = news.length();
            for (int i = 0; i < l; ++i) {
                byte b = (byte) news.luaByte(i);
                if (b != L_ESC) {
                    lbuf.append(b);
                } else {
                    ++i;
                    b = (byte) news.luaByte(i);
                    if (!Character.isDigit((char) b)) {
                        lbuf.append(b);
                    } else if (b == '0') {
                        lbuf.append(s.substring(soff, e));
                    } else {
                        lbuf.append(pushOneCapture(b - '1', soff, e).strvalue());
                    }
                }
            }
        }

        void addValue(Buffer lbuf, int soffset, int end, LuaValue repl) {
            switch (repl.type()) {
                case LuaValue.TSTRING, LuaValue.TNUMBER -> {
                    addS(lbuf, repl.strvalue(), soffset, end);
                    return;
                }
                case LuaValue.TFUNCTION -> repl = repl.invoke(pushCaptures(true, soffset, end)).arg1();
                case LuaValue.TTABLE -> repl = repl.get(pushOneCapture(0, soffset, end));
                default -> {
                    LuaValue.error("bad argument: string/function/table expected");
                    return;
                }
            }
            if (!repl.toboolean()) {
                repl = s.substring(soffset, end);
            } else if (!repl.isstring()) {
                LuaValue.error("invalid replacement value (a " + repl.typename() + ")");
            }
            lbuf.append(repl.strvalue());
        }

        Varargs pushCaptures(boolean wholeMatch, int soff, int end) {
            int nlevels = (this.level == 0 && wholeMatch) ? 1 : this.level;
            switch (nlevels) {
                case 0:
                    return LuaValue.NONE;
                case 1:
                    return pushOneCapture(0, soff, end);
                default:
                    break;
            }
            LuaValue[] v = new LuaValue[nlevels];
            for (int i = 0; i < nlevels; ++i) {
                v[i] = pushOneCapture(i, soff, end);
            }
            return LuaValue.varargsOf(v);
        }

        private LuaValue pushOneCapture(int i, int soff, int end) {
            if (i >= this.level) {
                if (i == 0) {
                    return s.substring(soff, end);
                }
                return LuaValue.error("invalid capture index");
            }
            int l = clen[i];
            if (l == CAP_UNFINISHED) {
                return LuaValue.error("unfinished capture");
            }
            if (l == CAP_POSITION) {
                return LuaValue.valueOf(cinit[i] + 1);
            }
            int begin = cinit[i];
            return s.substring(begin, begin + l);
        }

        private int checkCapture(int l) {
            l -= '1';
            if (l < 0 || l >= level || this.clen[l] == CAP_UNFINISHED) {
                LuaValue.error("invalid capture index");
            }
            return l;
        }

        private int captureToClose() {
            int level = this.level;
            for (level--; level >= 0; level--) {
                if (clen[level] == CAP_UNFINISHED) {
                    return level;
                }
            }
            LuaValue.error("invalid pattern capture");
            return 0;
        }

        int classEnd(int poffset) {
            switch (p.luaByte(poffset++)) {
                case L_ESC:
                    if (poffset == p.length()) {
                        LuaValue.error("malformed pattern (ends with %)");
                    }
                    return poffset + 1;
                case '[':
                    if (poffset < p.length() && p.luaByte(poffset) == '^') {
                        poffset++;
                    }
                    do {
                        if (poffset == p.length()) {
                            LuaValue.error("malformed pattern (missing ])");
                        }
                        if (p.luaByte(poffset++) == L_ESC && poffset != p.length()) {
                            poffset++;
                        }
                    } while (poffset >= p.length() || p.luaByte(poffset) != ']');
                    return poffset + 1;
                default:
                    return poffset;
            }
        }

        static boolean matchClass(int c, int cl) {
            char lcl = Character.toLowerCase((char) cl);
            int cdata = c >= 0 && c < 256 ? CHAR_TABLE[c] : 0;
            boolean res;
            switch (lcl) {
                case 'a' -> res = (cdata & MASK_ALPHA) != 0;
                case 'd' -> res = (cdata & MASK_DIGIT) != 0;
                case 'l' -> res = (cdata & MASK_LOWERCASE) != 0;
                case 'u' -> res = (cdata & MASK_UPPERCASE) != 0;
                case 'c' -> res = (cdata & MASK_CONTROL) != 0;
                case 'p' -> res = (cdata & MASK_PUNCT) != 0;
                case 's' -> res = (cdata & MASK_SPACE) != 0;
                case 'w' -> res = (cdata & (MASK_ALPHA | MASK_DIGIT)) != 0;
                case 'x' -> res = (cdata & MASK_HEXDIGIT) != 0;
                case 'z' -> res = c == 0;
                default -> {
                    return cl == c;
                }
            }
            return (lcl == cl) == res;
        }

        boolean matchBracketClass(int c, int poff, int ec) {
            boolean sig = true;
            if (p.luaByte(poff + 1) == '^') {
                sig = false;
                poff++;
            }
            while (++poff < ec) {
                step();
                if (p.luaByte(poff) == L_ESC) {
                    poff++;
                    if (matchClass(c, p.luaByte(poff))) {
                        return sig;
                    }
                } else if (poff + 1 < p.length() && p.luaByte(poff + 1) == '-' && poff + 2 < ec) {
                    poff += 2;
                    if (p.luaByte(poff - 2) <= c && c <= p.luaByte(poff)) {
                        return sig;
                    }
                } else if (p.luaByte(poff) == c) {
                    return sig;
                }
            }
            return !sig;
        }

        boolean singleMatch(int c, int poff, int ep) {
            switch (p.luaByte(poff)) {
                case '.':
                    return true;
                case L_ESC:
                    return matchClass(c, p.luaByte(poff + 1));
                case '[':
                    return matchBracketClass(c, poff, ep - 1);
                default:
                    return p.luaByte(poff) == c;
            }
        }

        /** Сопоставление с позиции; конец совпадения или −1. Каждый проход цикла — шаг счётчика. */
        int match(int soffset, int poffset) {
            while (true) {
                step();
                if (poffset == p.length()) {
                    return soffset;
                }
                switch (p.luaByte(poffset)) {
                    case '(':
                        if (++poffset < p.length() && p.luaByte(poffset) == ')') {
                            return startCapture(soffset, poffset + 1, CAP_POSITION);
                        }
                        return startCapture(soffset, poffset, CAP_UNFINISHED);
                    case ')':
                        return endCapture(soffset, poffset + 1);
                    case L_ESC:
                        if (poffset + 1 == p.length()) {
                            LuaValue.error("malformed pattern (ends with '%')");
                        }
                        switch (p.luaByte(poffset + 1)) {
                            case 'b':
                                soffset = matchBalance(soffset, poffset + 2);
                                if (soffset == -1) {
                                    return -1;
                                }
                                poffset += 4;
                                continue;
                            case 'f': {
                                poffset += 2;
                                if (poffset >= p.length() || p.luaByte(poffset) != '[') {
                                    LuaValue.error("Missing [ after %f in pattern");
                                }
                                int ep = classEnd(poffset);
                                int previous = (soffset == 0) ? -1 : s.luaByte(soffset - 1);
                                int current = soffset < s.length() ? s.luaByte(soffset) : 0;
                                if (matchBracketClass(previous, poffset, ep - 1) || !matchBracketClass(current, poffset, ep - 1)) {
                                    return -1;
                                }
                                poffset = ep;
                                continue;
                            }
                            default: {
                                int c = p.luaByte(poffset + 1);
                                if (Character.isDigit((char) c)) {
                                    soffset = matchCapture(soffset, c);
                                    if (soffset == -1) {
                                        return -1;
                                    }
                                    return match(soffset, poffset + 2);
                                }
                            }
                        }
                        break;
                    case '$':
                        if (poffset + 1 == p.length()) {
                            return (soffset == s.length()) ? soffset : -1;
                        }
                        break;
                    default:
                        break;
                }
                int ep = classEnd(poffset);
                boolean m = soffset < s.length() && singleMatch(s.luaByte(soffset), poffset, ep);
                int pc = (ep < p.length()) ? p.luaByte(ep) : '\0';
                switch (pc) {
                    case '?':
                        int res;
                        if (m && ((res = match(soffset + 1, ep + 1)) != -1)) {
                            return res;
                        }
                        poffset = ep + 1;
                        continue;
                    case '*':
                        return maxExpand(soffset, poffset, ep);
                    case '+':
                        return m ? maxExpand(soffset + 1, poffset, ep) : -1;
                    case '-':
                        return minExpand(soffset, poffset, ep);
                    default:
                        if (!m) {
                            return -1;
                        }
                        soffset++;
                        poffset = ep;
                }
            }
        }

        int maxExpand(int soff, int poff, int ep) {
            int i = 0;
            while (soff + i < s.length() && singleMatch(s.luaByte(soff + i), poff, ep)) {
                step();
                i++;
            }
            while (i >= 0) {
                int res = match(soff + i, ep + 1);
                if (res != -1) {
                    return res;
                }
                i--;
            }
            return -1;
        }

        int minExpand(int soff, int poff, int ep) {
            for (;;) {
                int res = match(soff, ep + 1);
                if (res != -1) {
                    return res;
                } else if (soff < s.length() && singleMatch(s.luaByte(soff), poff, ep)) {
                    soff++;
                } else {
                    return -1;
                }
            }
        }

        int startCapture(int soff, int poff, int what) {
            int res;
            int level = this.level;
            if (level >= MAX_CAPTURES) {
                LuaValue.error("too many captures");
            }
            cinit[level] = soff;
            clen[level] = what;
            this.level = level + 1;
            if ((res = match(soff, poff)) == -1) {
                this.level--;
            }
            return res;
        }

        int endCapture(int soff, int poff) {
            int l = captureToClose();
            int res;
            clen[l] = soff - cinit[l];
            if ((res = match(soff, poff)) == -1) {
                clen[l] = CAP_UNFINISHED;
            }
            return res;
        }

        int matchCapture(int soff, int l) {
            l = checkCapture(l);
            int len = clen[l];
            if ((s.length() - soff) >= len && LuaString.equals(s, cinit[l], s, soff, len)) {
                return soff + len;
            }
            return -1;
        }

        int matchBalance(int soff, int poff) {
            int plen = p.length();
            if (poff == plen || poff + 1 == plen) {
                LuaValue.error("unbalanced pattern");
            }
            int slen = s.length();
            if (soff >= slen) {
                return -1;
            }
            int b = p.luaByte(poff);
            if (s.luaByte(soff) != b) {
                return -1;
            }
            int e = p.luaByte(poff + 1);
            int cont = 1;
            while (++soff < slen) {
                step();
                if (s.luaByte(soff) == e) {
                    if (--cont == 0) {
                        return soff + 1;
                    }
                } else if (s.luaByte(soff) == b) {
                    cont++;
                }
            }
            return -1;
        }
    }
}
