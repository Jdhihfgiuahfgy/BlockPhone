package dev.blockphone.fabric.client.screens;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockphone.common.protocol.packet.AppPackets;
import dev.blockphone.fabric.client.apps.custom.CloudRelay;
import dev.blockphone.fabric.client.browser.WebEngine;
import dev.blockphone.fabric.client.network.ClientNetwork;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Рассылка экранов и колонок другим игрокам с модом: через плагин BlockPhone (пакеты Relay), а без плагина — через
 * облачную комнату «этот сервер» ({@link CloudRelay}). Каждый телефон рассылает свои экраны раз в 5 с и при изменении;
 * новичок просит всех ответить («hello»). Владелец вышел (нет вестей 16 с или его нет в списке игроков) — его экраны и
 * колонки пропадают. Только поток рендера.
 */
public final class ScreenSync {
    /** Id «приложения» в пересылке (свои приложения игроков с таким id сообщений не получат). */
    public static final String APP = "blockphone-screens";
    private static final Gson GSON = new Gson();
    private static final long BROADCAST_MS = 5000;
    private static final long EXPIRE_MS = 16_000;

    /** Экраны другого игрока; skew — насколько его часы отстают от наших (по времени отправки). */
    public record Remote(String owner, ScreenData.Set data, long skew, long seen) {
    }

    private static final Map<String, Remote> REMOTES = new LinkedHashMap<>();
    private static long lastBroadcast;
    private static boolean dirty;
    private static boolean helloSent;
    private static int version;

    /** Экраны других игроков (по нику в нижнем регистре). */
    public static Map<String, Remote> remotes() {
        return REMOTES;
    }

    /** Растёт при каждом изменении чужих экранов. */
    public static int version() {
        return version;
    }

    static void ownChanged() {
        dirty = true;
    }

    /** Нужна ли облачная комната «этот сервер» (сервер без плагина BlockPhone). */
    public static boolean wantsCloud() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.getConnection() != null && !mc.hasSingleplayerServer() && ClientNetwork.serverHello() == null;
    }

    static boolean viaPlugin() {
        return ClientNetwork.serverHello() != null;
    }

    private static @Nullable CloudRelay cloud() {
        CloudRelay r = CloudRelay.system();
        return r.online() && r.code() == null ? r : null;
    }

    private static boolean canSend() {
        return viaPlugin() || cloud() != null;
    }

    private static void send(String payload) {
        if (viaPlugin()) {
            ClientNetwork.send(new AppPackets.Relay(APP, "", payload));
        } else {
            CloudRelay r = cloud();
            if (r != null) {
                r.send(APP, "", payload);
            }
        }
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null || mc.hasSingleplayerServer()) {
            if (!REMOTES.isEmpty()) {
                REMOTES.clear();
                version++;
            }
            helloSent = false;
            return;
        }
        if (!canSend()) {
            helloSent = false;
        } else {
            long now = System.currentTimeMillis();
            if (!helloSent) {
                helloSent = true;
                JsonObject o = new JsonObject();
                o.addProperty("t", "hello");
                send(o.toString());
                dirty = true;
            }
            ScreenData.Set own = ScreenStore.data();
            boolean any = !own.screens.isEmpty() || !own.speakers.isEmpty();
            if ((dirty && now - lastBroadcast > 400) || (any && now - lastBroadcast > BROADCAST_MS)) {
                dirty = false;
                lastBroadcast = now;
                JsonObject o = new JsonObject();
                o.addProperty("t", "state");
                o.addProperty("now", now);
                // Сжато: 8 экранов с длинными ссылками и 32 колонки иначе не влезают в одно сообщение (8 КБ).
                o.addProperty("z", pack(GSON.toJson(own)));
                send(o.toString());
            }
        }
        // Владелец ушёл: давно не было вестей или его нет в списке игроков (на прокси — на другом сервере сети).
        long now = System.currentTimeMillis();
        if (REMOTES.values().removeIf(r -> now - r.seen() > EXPIRE_MS || mc.getConnection().getPlayerInfo(r.owner()) == null)) {
            version++;
        }
    }

    /** Сообщение экранов от другого игрока (из пакета плагина или облачной комнаты). */
    public static void onMessage(String from, String payload) {
        JsonObject o;
        try {
            o = JsonParser.parseString(payload).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String t = o.has("t") ? o.get("t").getAsString() : "";
        if (t.equals("pm")) {
            PhoneMirror.onFrame(from, o);
            return;
        }
        if (t.equals("pa")) {
            PhoneSpeakers.onAudio(from, o);
            return;
        }
        if (t.equals("hello")) {
            dirty = true; // новичку — свои экраны
            return;
        }
        if (!t.equals("state") || !o.has("z")) {
            return;
        }
        ScreenData.Set set;
        try {
            set = GSON.fromJson(unpack(o.get("z").getAsString()), ScreenData.Set.class);
        } catch (RuntimeException | java.io.IOException e) {
            return;
        }
        if (set == null) {
            return;
        }
        set = sanitize(set);
        long now = System.currentTimeMillis();
        // Разница часов = время приёма − время отправки = (разница часов + задержка сети). Задержка скачет — берём самое
        // быстрое сообщение (минимум), медленно отпуская его вверх (часы могут уходить).
        long sample = o.has("now") ? now - o.get("now").getAsLong() : 0;
        Remote old = REMOTES.get(from.toLowerCase(Locale.ROOT));
        long skew = old == null ? sample : Math.min(sample, old.skew() + Math.max(1, (now - old.seen()) / 1000));
        String key = from.toLowerCase(Locale.ROOT);
        if (set.screens.isEmpty() && set.speakers.isEmpty()) {
            if (REMOTES.remove(key) != null) {
                version++;
            }
            return;
        }
        REMOTES.put(key, new Remote(from, set, skew, now));
        version++;
    }

    /** Проверка чужих данных: пределы размеров, ссылки только http(s). */
    private static ScreenData.Set sanitize(ScreenData.Set in) {
        ScreenData.Set out = new ScreenData.Set();
        Set<String> ids = new HashSet<>();
        if (in.screens != null) {
            for (ScreenData.Screen s : in.screens) {
                if (s == null || out.screens.size() >= ScreenData.MAX_SCREENS || s.id == null || s.id.isEmpty() || s.id.length() > 16
                        || !ids.add(s.id) || s.dim == null) {
                    continue;
                }
                if (Math.abs(s.x2 - s.x1) >= ScreenData.MAX_SIZE || Math.abs(s.y2 - s.y1) >= ScreenData.MAX_SIZE
                        || Math.abs(s.z2 - s.z1) >= ScreenData.MAX_SIZE) {
                    continue;
                }
                ScreenData.Screen c = s.copy();
                c.name = c.name == null ? "" : c.name.length() > ScreenData.MAX_NAME ? c.name.substring(0, ScreenData.MAX_NAME) : c.name;
                c.url = c.url == null || c.url.length() > ScreenData.MAX_URL ? "" : c.url;
                if (!c.url.isEmpty() && WebEngine.safeUrl(c.url).equals("about:blank")) {
                    c.url = "";
                }
                c.radius = Math.max(ScreenData.MIN_RADIUS, Math.min(ScreenData.MAX_RADIUS, c.radius));
                java.util.List<String> acts = new java.util.ArrayList<>();
                if (c.actions != null) {
                    for (String a : c.actions) {
                        if (a != null && a.length() <= 48 && a.matches("\\d{1,7},[duwk](,-?\\d{1,6}){3}") && acts.size() < ScreenPlayer.MAX_ACTIONS) {
                            acts.add(a);
                        }
                    }
                }
                c.actions = acts;
                c.volume = Math.max(0, Math.min(100, c.volume));
                c.app = c.app != null && c.app.matches("[a-z0-9_.-]{1,64}") ? c.app : "";
                c.appTitle = c.appTitle == null ? "" : c.appTitle.length() > 40 ? c.appTitle.substring(0, 40) : c.appTitle;
                out.screens.add(c);
            }
        }
        if (in.speakers != null) {
            for (ScreenData.Speaker sp : in.speakers) {
                if (sp == null || out.speakers.size() >= ScreenData.MAX_SPEAKERS || sp.screen == null
                        || !(ids.contains(sp.screen) || sp.screen.equals(ScreenData.PHONE)
                        || (sp.screen.startsWith(ScreenData.GROUP_PREFIX) && sp.screen.length() <= 24))
                        || sp.dim == null || sp.id == null) {
                    continue;
                }
                sp.name = sp.name == null ? "" : sp.name.length() > ScreenData.MAX_NAME ? sp.name.substring(0, ScreenData.MAX_NAME) : sp.name;
                sp.volume = Math.max(0, Math.min(100, sp.volume));
                sp.channel = ScreenAudio.Pick.of(sp.channel == null ? "" : sp.channel).name();
                out.speakers.add(sp);
            }
        }
        if (in.groups != null) {
            Set<String> all = new HashSet<>(ids);
            for (ScreenData.Speaker sp : out.speakers) {
                all.add(sp.id);
            }
            for (ScreenData.Group g : in.groups) {
                if (g == null || out.groups.size() >= ScreenData.MAX_GROUPS || g.id == null || g.id.isEmpty() || g.id.length() > 16
                        || g.members == null) {
                    continue;
                }
                ScreenData.Group c = new ScreenData.Group();
                c.id = g.id;
                c.name = g.name == null ? "" : g.name.length() > ScreenData.MAX_NAME ? g.name.substring(0, ScreenData.MAX_NAME) : g.name;
                c.kind = "screens".equals(g.kind) ? "screens" : "speakers";
                for (String m : g.members) {
                    if (m != null && all.contains(m) && c.members.size() < ScreenData.MAX_GROUP_MEMBERS) {
                        c.members.add(m);
                    }
                }
                c.on = g.on;
                c.volume = Math.max(0, Math.min(100, g.volume));
                c.sync = g.sync;
                out.groups.add(c);
            }
        }
        if (in.phone != null) {
            out.phone.on = in.phone.on;
            out.phone.radius = Math.max(ScreenData.MIN_RADIUS, Math.min(ScreenData.MAX_RADIUS, in.phone.radius));
            out.phone.volume = Math.max(0, Math.min(100, in.phone.volume));
        }
        return out;
    }

    /** Звук телефона (пакет Opus) — всем; не чаще 5 раз в секунду. */
    static void sendAudio(String payload) {
        if (canSend()) {
            send(payload);
        }
    }

    private static String pack(String json) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.util.zip.DeflaterOutputStream out = new java.util.zip.DeflaterOutputStream(bytes,
                    new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION))) {
                out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Распаковать (не больше 256 КБ — защита от «бомбы»). */
    private static String unpack(String base64) throws java.io.IOException {
        byte[] packed = java.util.Base64.getDecoder().decode(base64);
        try (java.util.zip.InflaterInputStream in = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(packed))) {
            byte[] out = in.readNBytes(256 * 1024 + 1);
            if (out.length > 256 * 1024) {
                throw new java.io.IOException("too big");
            }
            return new String(out, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Выход с сервера. */
    public static void reset() {
        REMOTES.clear();
        helloSent = false;
        version++;
    }

    /** Игроки, у которых есть экраны (для списка «Рядом»). */
    public static List<Remote> list() {
        return new ArrayList<>(REMOTES.values());
    }

    private ScreenSync() {
    }
}
