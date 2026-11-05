package dev.blockphone.fabric.client.screens;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.compat.WorldRenderHook;
import dev.blockphone.fabric.client.config.PhoneSettings;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Экраны в мире: какие видны (свои и других игроков в этом измерении), какие сейчас работают (страница в Chromium — не
 * больше {@link #MAX_ACTIVE}, ближние), рисование картинки на стороне блоков и звук. Только поток рендера.
 */
public final class Screens {
    /** Сколько экранов одновременно может проигрывать этот компьютер (каждый — вкладка Chromium). */
    public static final int MAX_ACTIVE = 4;
    /** Дальше этого экран не рисуется и не запускается (если его не слушают и колонки не рядом). */
    public static final double VIEW_DISTANCE = 96;
    static final int FULL_BRIGHT = 0xF000F0;
    public static final Identifier WHITE = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/misc/white.png");

    /** Экран, как его видит этот телефон: чей, разница часов с владельцем, колонки экрана, все данные владельца (группы). */
    public record Live(ScreenData.Screen def, String owner, boolean mine, long skew, List<ScreenData.Speaker> speakers,
                       ScreenData.Set set) {
        public String key() {
            return owner.toLowerCase(Locale.ROOT) + "/" + def.id;
        }

        /** Включён сам и во всех группах. */
        public boolean on() {
            return set.screenOn(def);
        }

        /**
         * Ключ того, что проигрывается: у объединённой группы экранов — одна вкладка браузера на всех («владелец/g:id»),
         * иначе — свой экран.
         */
        public String unitKey() {
            ScreenData.Group g = set.syncGroup(def);
            return g == null ? key() : owner.toLowerCase(Locale.ROOT) + "/" + ScreenData.GROUP_PREFIX + g.id;
        }
    }

    private static final Map<String, ScreenPlayer> PLAYERS = new LinkedHashMap<>();
    /** Экраны, к которым подключился телефон (звук «в ушах»). */
    private static final Set<String> LISTENING = new HashSet<>();
    private static boolean available;
    /** Подсветка своих колонок в мире (видно сквозь стены, с подписями) — кнопка в «Экранах». */
    private static boolean showSpeakers;

    public static boolean showSpeakers() {
        return showSpeakers;
    }

    public static void setShowSpeakers(boolean on) {
        showSpeakers = on;
    }

    public static void init() {
        available = WorldRenderHook.beforeEntities(Screens::render);
        ScreenPicker.init();
    }

    /** Рисование в мире возможно (на 1.21.9 — нет событий отрисовки мира). */
    public static boolean available() {
        return available;
    }

    /** Все экраны в текущем измерении: свои и (если включено) других игроков. */
    public static List<Live> all() {
        Minecraft mc = Minecraft.getInstance();
        List<Live> out = new ArrayList<>();
        if (mc.level == null || mc.player == null) {
            return out;
        }
        String dim = mc.level.dimension().identifier().toString();
        String self = mc.getUser().getName();
        ScreenData.Set own = ScreenStore.data();
        for (ScreenData.Screen s : own.screens) {
            if (dim.equals(s.dim)) {
                out.add(new Live(s, self, true, 0, speakersOf(own, s), own));
            }
        }
        if (PhoneSettings.get().screensOthers) {
            for (ScreenSync.Remote r : ScreenSync.remotes().values()) {
                for (ScreenData.Screen s : r.data().screens) {
                    if (dim.equals(s.dim)) {
                        out.add(new Live(s, r.owner(), false, r.skew(), speakersOf(r.data(), s), r.data()));
                    }
                }
            }
        }
        return out;
    }

    /** Колонки экрана: подключённые к нему самому и к его объединённой группе. */
    private static List<ScreenData.Speaker> speakersOf(ScreenData.Set set, ScreenData.Screen screen) {
        List<ScreenData.Speaker> out = new ArrayList<>();
        ScreenData.Group g = set.syncGroup(screen);
        String group = g == null ? null : ScreenData.GROUP_PREFIX + g.id;
        for (ScreenData.Speaker sp : set.speakers) {
            if ((sp.screen.equals(screen.id) || sp.screen.equals(group)) && sp.dim.equals(screen.dim)) {
                out.add(sp);
            }
        }
        return out;
    }

    public static boolean listening(Live live) {
        return LISTENING.contains(live.key());
    }

    public static void setListening(Live live, boolean on) {
        if (on) {
            LISTENING.add(live.key());
        } else {
            LISTENING.remove(live.key());
        }
    }

    /** Работающий плеер экрана (null — экран сейчас не проигрывается этим компьютером). */
    static @Nullable ScreenPlayer player(Live live) {
        return PLAYERS.get(live.unitKey());
    }

    /** Сколько каналов в звуке своего экрана или объединённой группы («g:id») сейчас (0 — не играет на этом компьютере). */
    public static int channels(String ownSource) {
        for (ScreenPlayer p : PLAYERS.values()) {
            for (Live m : p.members) {
                ScreenData.Group g = m.set().syncGroup(m.def());
                if (m.mine() && (m.def().id.equals(ownSource) || (g != null && ownSource.equals(ScreenData.GROUP_PREFIX + g.id)))) {
                    return p.channels();
                }
            }
        }
        return 0;
    }

    /**
     * Записать действие владельца на странице своего экрана («Управлять страницей»): d/u — кнопка мыши (x, y — пиксели
     * страницы), w — колесо (c — сила ×100), k — клавиша (x — код, y — скан-код, c — модификаторы). true — записано.
     */
    public static boolean record(Live live, char kind, double x, double y, int c) {
        ScreenPlayer p = PLAYERS.get(live.unitKey());
        ScreenData.Screen def = live.def();
        if (p == null || !live.mine() || def.actions.size() >= ScreenPlayer.MAX_ACTIONS) {
            return false;
        }
        String action = p.record(kind, x, y, c);
        if (action == null) {
            return false;
        }
        def.actions.add(action);
        return true;
    }

    /** Вкладка браузера экрана на этом компьютере (null — экран сейчас не проигрывается). */
    public static dev.blockphone.fabric.client.browser.WebEngine.@Nullable View view(Live live) {
        ScreenPlayer p = PLAYERS.get(live.unitKey());
        return p == null ? null : p.view();
    }

    /** Позиция и длина видео на странице этого компьютера (с; длина −1 — неизвестна или трансляция). */
    public static double[] pageTime(Live live) {
        ScreenPlayer p = PLAYERS.get(live.unitKey());
        return p == null || !p.hasVideo() ? new double[]{-1, -1} : new double[]{p.pagePosition(), p.pageDuration()};
    }

    private static long lastTick;

    public static void tick() {
        long tickAt = System.currentTimeMillis();
        if (lastTick != 0 && tickAt - lastTick > 600) {
            StreamSound.fadeAll(); // игра подвисла (шейдеры, ресурспак) — без громкого рывка после
        }
        lastTick = tickAt;
        ScreenSync.tick();
        ScreenPicker.tick();
        PhoneSpeakers.tick(); // звук, рисовать не нужно — работает и там, где экранов нет
        PhoneMirror.tick();
        StreamSound.tickAll();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !available) {
            for (ScreenPlayer p : PLAYERS.values()) {
                p.close();
            }
            PLAYERS.clear();
            return;
        }
        // Какие экраны должны работать: включены, есть ссылка, и рядом / слушаем / колонки рядом. Ближние — первыми.
        Vec3 eye = mc.player.getEyePosition();
        AppDisplays.tick();
        // Что проигрывать: экран или объединённая группа (одна вкладка на все её экраны).
        Map<String, List<Live>> units = new LinkedHashMap<>();
        for (Live live : all()) {
            ScreenData.Screen s = live.def();
            if (!live.on() || s.url.isEmpty() || s.mirror || !s.app.isEmpty()) {
                continue;
            }
            units.computeIfAbsent(live.unitKey(), k -> new ArrayList<>()).add(live);
        }
        List<String> wanted = new ArrayList<>();
        Map<String, Double> score = new LinkedHashMap<>();
        for (Map.Entry<String, List<Live>> unit : units.entrySet()) {
            double best = Double.MAX_VALUE;
            boolean listen = false;
            boolean speakerNear = false;
            for (Live live : unit.getValue()) {
                ScreenData.Screen s = live.def();
                double d = s.center().distanceTo(eye);
                best = Math.min(best, d);
                listen |= LISTENING.contains(live.key());
                if (!s.phone) {
                    double reach = s.radius + 8;
                    for (ScreenData.Speaker sp : live.speakers()) {
                        speakerNear |= sp.center().distanceTo(eye) < reach;
                    }
                    speakerNear |= live.speakers().isEmpty() && d < reach;
                }
            }
            if (best <= VIEW_DISTANCE || listen || speakerNear) {
                wanted.add(unit.getKey());
                score.put(unit.getKey(), listen ? -1 : best);
            }
        }
        wanted.sort(Comparator.comparingDouble(score::get));
        if (wanted.size() > MAX_ACTIVE) {
            wanted = wanted.subList(0, MAX_ACTIVE);
        }
        long now = System.currentTimeMillis();
        Set<String> keep = new HashSet<>(wanted);
        for (String key : wanted) {
            List<Live> members = units.get(key);
            ScreenPlayer p = PLAYERS.get(key);
            if (p == null) {
                p = new ScreenPlayer(key, members);
                PLAYERS.put(key, p);
            }
            p.members = members;
            p.live = members.get(0);
            p.unwantedSince = 0;
        }
        Set<String> present = new HashSet<>();
        for (List<Live> list : units.values()) {
            for (Live live : list) {
                present.add(live.key());
            }
        }
        LISTENING.removeIf(k -> !present.contains(k));
        double others = PhoneSettings.get().screensOthersVolume / 100.0;
        PLAYERS.values().removeIf(p -> {
            if (!keep.contains(p.key)) {
                // Не нужен 5 с (ушёл, выключили) — закрыть страницу.
                if (p.unwantedSince == 0) {
                    p.unwantedSince = now;
                }
                if (now - p.unwantedSince > 5000 || !exists(p.key)) {
                    p.close();
                    return true;
                }
                p.tick(Set.of(), 0);
                return false;
            }
            p.tick(LISTENING, others);
            return false;
        });
    }

    private static boolean exists(String unitKey) {
        for (Live live : all()) {
            if (live.unitKey().equals(unitKey) && live.on() && !live.def().url.isEmpty() && !live.def().mirror) {
                return true;
            }
        }
        return false;
    }

    /** Для DevShots: что сейчас с работающими экранами (видео, позиция, звук). */
    public static String debugStatus() {
        StringBuilder sb = new StringBuilder();
        for (ScreenPlayer p : PLAYERS.values()) {
            sb.append(p.debug()).append("; ");
        }
        return (sb.length() == 0 ? "no players" : sb.toString()) + " | phone: " + PhoneSpeakers.debugStatus() + " | mirror: "
                + PhoneMirror.debugStatus() + " | pulse: " + PULSE + " | al: " + StreamSound.ALL.stream()
                .map(x -> (x.playing ? "P" : "-") + x.restarts).toList();
    }

    /** Выход из мира — закрыть страницы и звук. */
    public static void closeAll() {
        for (ScreenPlayer p : PLAYERS.values()) {
            p.close();
        }
        PLAYERS.clear();
        PhoneSpeakers.stopAll();
        AppDisplays.closeAll();
    }

    // --- Рисование ---

    private static void render(PoseStack pose, SubmitNodeCollector queue) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        for (Live live : all()) {
            ScreenData.Screen s = live.def();
            boolean app = !s.app.isEmpty();
            if (!live.on() || (s.url.isEmpty() && !s.mirror && !app) || s.center().distanceTo(cam) > VIEW_DISTANCE + 32) {
                continue;
            }
            if (app) {
                AppDisplays.render(pose, queue, cam, live);
                continue;
            }
            if (s.mirror) {
                mirror(pose, queue, cam, live);
                continue;
            }
            ScreenPlayer p = PLAYERS.get(live.unitKey());
            Identifier tex = p != null ? p.texture() : null;
            Vec3[] c = s.corners(0.004);
            if (tex == null) {
                solid(pose, queue, cam, c, WHITE, 0xFF000000); // включён, но картинки ещё нет — чёрный экран
            } else {
                solid(pose, queue, cam, c, tex, 0xFFFFFFFF);
            }
        }
        if (showSpeakers) {
            renderSpeakers(pose, queue, cam);
        }
        renderPulse(pose, queue, cam);
        ScreenPicker.render(pose, queue, cam);
    }

    /** Трансляция телефона: чёрный экран и кадр по центру с сохранением пропорций. */
    private static void mirror(PoseStack pose, SubmitNodeCollector queue, Vec3 cam, Live live) {
        ScreenData.Screen s = live.def();
        Vec3[] c = s.corners(0.004);
        solid(pose, queue, cam, c, WHITE, 0xFF000000);
        Identifier tex = PhoneMirror.texture(live.owner());
        float aspect = PhoneMirror.aspect(live.owner());
        if (tex == null || aspect <= 0) {
            return;
        }
        // Поверх чёрной подложки (полупрозрачное рисуется по порядку).
        solid(pose, queue.order(1), cam, fitted(s, aspect), tex, 0xFFFFFFFF);
    }

    /** Углы картинки с пропорциями aspect по центру экрана (поля — чёрные). */
    private static Vec3[] fitted(ScreenData.Screen s, float aspect) {
        return fitted(s, aspect, 0.006);
    }

    static Vec3[] fitted(ScreenData.Screen s, float aspect, double lift) {
        float screen = s.widthBlocks() / (float) Math.max(1, s.heightBlocks());
        double fw = aspect < screen ? aspect / screen : 1;
        double fh = aspect < screen ? 1 : screen / aspect;
        Vec3[] inner = s.corners(lift);
        Vec3 center = inner[0].add(inner[2]).scale(0.5);
        Vec3 right = inner[1].subtract(inner[0]).scale(0.5 * fw);
        Vec3 down = inner[3].subtract(inner[0]).scale(0.5 * fh);
        return new Vec3[]{center.subtract(right).subtract(down), center.add(right).subtract(down), center.add(right).add(down),
                center.subtract(right).add(down)};
    }

    /** Сглаженная «раскачка» колонок по ключу «владелец/id». */
    private static final Map<String, Float> PULSE = new java.util.HashMap<>();
    private static long pulseFrame;
    /** Спад «качания» за этот кадр (по времени, а не по числу кадров: ~120 мс). */
    private static float pulseDecay = 0.8f;
    private static final java.util.Random SHAKE = new java.util.Random();

    /**
     * Колонки «качаются» от басов: поверх блока-колонки рисуется его копия, на миг увеличенная (до +8 %) и чуть сдвинутая —
     * как диффузор настоящего динамика. Сила — басы того звука, что колонка сейчас играет (с поправкой на задержку).
     */
    private static void renderPulse(PoseStack pose, SubmitNodeCollector queue, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        String dim = mc.level.dimension().identifier().toString();
        long nowMs = System.currentTimeMillis();
        pulseDecay = (float) Math.exp(-Math.min(500, nowMs - pulseFrame) / 120.0);
        pulseFrame = nowMs;
        java.util.Set<String> seen = new HashSet<>();
        pulseSet(pose, queue, cam, mc, dim, null, ScreenStore.data(), seen);
        if (PhoneSettings.get().screensOthers) {
            for (ScreenSync.Remote r : ScreenSync.remotes().values()) {
                pulseSet(pose, queue, cam, mc, dim, r.owner(), r.data(), seen);
            }
        }
        PULSE.keySet().retainAll(seen);
    }

    private static void pulseSet(PoseStack pose, SubmitNodeCollector queue, Vec3 cam, Minecraft mc, String dim, @Nullable String owner,
                                 ScreenData.Set set, java.util.Set<String> seen) {
        for (ScreenData.Speaker sp : set.speakers) {
            if (!sp.pulse || !dim.equals(sp.dim) || !set.speakerOn(sp) || sp.center().distanceTo(cam) > 48) {
                continue;
            }
            ScreenAudio audio = audioOf(owner, set, sp);
            float target = audio == null ? 0 : audio.bassNow();
            // Удар: только сильная часть баса, быстрый подъём и плавный спад.
            target = (float) Math.sqrt(Math.max(0, (target - 0.3f) / 0.7f));
            String key = (owner == null ? "" : owner) + "/" + sp.id;
            seen.add(key);
            float prev = PULSE.getOrDefault(key, 0f);
            float now = target > prev ? target : prev * pulseDecay;
            PULSE.put(key, now);
            if (now < 0.12f) {
                continue; // почти без увеличения грани копии совпали бы с блоком — мерцание точками
            }
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(sp.x, sp.y, sp.z);
            var state = mc.level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            float scale = 1.02f + 0.08f * now;
            float shake = 0.012f * now;
            pose.pushPose();
            pose.translate(sp.x + 0.5 - cam.x + (SHAKE.nextFloat() - 0.5f) * shake, sp.y + 0.5 - cam.y + (SHAKE.nextFloat() - 0.5f) * shake,
                    sp.z + 0.5 - cam.z + (SHAKE.nextFloat() - 0.5f) * shake);
            pose.scale(scale, scale, scale);
            pose.translate(-0.5, -0.5, -0.5);
            dev.blockphone.fabric.client.compat.BlockPulse.submit(pose, queue, mc.level, pos, state);
            pose.popPose();
        }
    }

    /** Звук, что играет колонка: экран (или объединённая группа) или телефон владельца. */
    private static @Nullable ScreenAudio audioOf(@Nullable String owner, ScreenData.Set set, ScreenData.Speaker sp) {
        if (sp.screen.equals(ScreenData.PHONE)) {
            return PhoneSpeakers.audioOf(owner);
        }
        String who = (owner == null ? Minecraft.getInstance().getUser().getName() : owner).toLowerCase(Locale.ROOT);
        String unit;
        if (sp.screen.startsWith(ScreenData.GROUP_PREFIX)) {
            unit = who + "/" + sp.screen;
        } else {
            ScreenData.Screen s = set.screen(sp.screen);
            ScreenData.Group g = s == null ? null : set.syncGroup(s);
            unit = who + "/" + (g == null ? sp.screen : ScreenData.GROUP_PREFIX + g.id);
        }
        ScreenPlayer p = PLAYERS.get(unit);
        return p == null ? null : p.audio();
    }

    /** Свои колонки: кубик (зелёный — играет, серый — выключен) и подпись «имя · канал · источник», сквозь стены. */
    private static void renderSpeakers(PoseStack pose, SubmitNodeCollector queue, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        String dim = mc.level.dimension().identifier().toString();
        ScreenData.Set data = ScreenStore.data();
        var font = mc.font;
        var front = queue.order(2);
        for (ScreenData.Speaker sp : data.speakers) {
            Vec3 c = sp.center();
            if (!dim.equals(sp.dim) || c.distanceTo(cam) > 96) {
                continue;
            }
            boolean on = data.speakerOn(sp);
            int color = on ? (dev.blockphone.fabric.client.phone.PhoneTheme.accent() & 0x00FFFFFF) | 0x90000000 : 0x80808080;
            ScreenPicker.cube(pose, front, cam, c, 0.53, color, true); // чуть больше блока — видна оболочка вокруг него
            String label = (sp.name.isEmpty() ? "?" : sp.name) + (sp.channel == null || sp.channel.equalsIgnoreCase("mix") ? "" : " · " + sp.channel)
                    + " · " + sourceLabel(data, sp.screen);
            pose.pushPose();
            pose.translate(c.x - cam.x, c.y + 0.7 - cam.y, c.z - cam.z);
            pose.mulPose(new org.joml.Quaternionf(mc.gameRenderer.getMainCamera().rotation()));
            float scale = 0.02f;
            pose.scale(scale, -scale, scale);
            var text = net.minecraft.util.FormattedCharSequence.forward(label, net.minecraft.network.chat.Style.EMPTY);
            float half = font.width(label) / 2f;
            front.submitText(pose, -half, 0, text, false, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, FULL_BRIGHT,
                    on ? 0xFFFFFFFF : 0xFFB0B0B0, 0x80000000, 0);
            pose.popPose();
        }
    }

    private static String sourceLabel(ScreenData.Set data, String source) {
        if (source.equals(ScreenData.PHONE)) {
            return "♪";
        }
        if (source.startsWith(ScreenData.GROUP_PREFIX)) {
            ScreenData.Group g = data.group(source.substring(ScreenData.GROUP_PREFIX.length()));
            return g == null ? "—" : g.name;
        }
        ScreenData.Screen s = data.screen(source);
        return s == null ? "—" : s.name;
    }

    /** Четырёхугольник по углам (левый верхний, правый верхний, правый нижний, левый нижний), текстура на весь. */
    static void quad(PoseStack pose, net.minecraft.client.renderer.OrderedSubmitNodeCollector queue, Vec3 cam, Vec3[] c, Identifier texture,
                     int color) {
        quad(pose, queue, cam, c, texture, color, false);
    }

    /** То же; seeThrough — видно сквозь блоки. */
    static void quad(PoseStack pose, net.minecraft.client.renderer.OrderedSubmitNodeCollector queue, Vec3 cam, Vec3[] c, Identifier texture,
                     int color, boolean seeThrough) {
        pose.pushPose();
        pose.translate(c[0].x - cam.x, c[0].y - cam.y, c[0].z - cam.z);
        Vec3 o = c[0];
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        queue.submitCustomGeometry(pose, seeThrough ? RenderTypes.textSeeThrough(texture) : RenderTypes.text(texture), (p, vc) -> {
            for (int i : new int[]{3, 2, 1, 0}) {
                vertex(vc, p, c[i].subtract(o), uv[i], color);
            }
        });
        pose.popPose();
    }

    /**
     * Непрозрачный четырёхугольник «как предмет» (картинка экрана): с нормалью вверх — полная яркость при обычном освещении,
     * и шейдеры (Iris) не портят его белыми точками, как полупрозрачный «текстовый».
     */
    static void solid(PoseStack pose, net.minecraft.client.renderer.OrderedSubmitNodeCollector queue, Vec3 cam, Vec3[] c, Identifier texture,
                      int color) {
        pose.pushPose();
        pose.translate(c[0].x - cam.x, c[0].y - cam.y, c[0].z - cam.z);
        Vec3 o = c[0];
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        queue.submitCustomGeometry(pose, RenderTypes.entityCutoutNoCull(texture), (p, vc) -> {
            for (int i : new int[]{3, 2, 1, 0}) {
                Vec3 v = c[i].subtract(o);
                vc.addVertex(p, (float) v.x, (float) v.y, (float) v.z).setColor(color).setUv(uv[i][0], uv[i][1])
                        .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT)
                        .setNormal(p, 0, 1, 0);
            }
        });
        pose.popPose();
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose p, Vec3 v, float[] uv, int color) {
        vc.addVertex(p, (float) v.x, (float) v.y, (float) v.z).setColor(color).setUv(uv[0], uv[1]).setLight(FULL_BRIGHT);
    }

    private Screens() {
    }
}
