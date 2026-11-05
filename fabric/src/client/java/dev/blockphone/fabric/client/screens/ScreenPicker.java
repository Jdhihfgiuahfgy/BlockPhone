package dev.blockphone.fabric.client.screens;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhonePin;
import dev.blockphone.fabric.client.phone.PhoneScreen;
import dev.blockphone.fabric.client.phone.PhoneState;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Выбор места прицелом: телефон прячется, игрок наводится на блоки и нажимает ЛКМ (блок не ломается). Экран — два угла
 * (картинка — на той стороне, по которой нажал), колонка — один блок. Esc / телефон — отмена. Потом телефон снова
 * открывается в «Экранах».
 */
public final class ScreenPicker {
    public enum Mode { NONE, SCREEN, SPEAKER }

    private static final double PICK_DISTANCE = 64;
    private static Mode mode = Mode.NONE;
    private static @Nullable BlockPos first;
    private static Direction firstFace = Direction.NORTH;
    private static String speakerFor = "";
    /** Переставляемая колонка (null — новая). */
    private static @Nullable String moveSpeaker;
    /** Новая колонка сразу входит в эту группу (null — ни в какую). */
    private static @Nullable String intoGroup;
    /** Префикс в {@link #takeDone()}: выбрана колонка (иначе — экран). */
    public static final String SPEAKER_PREFIX = "sp:";
    private static String error = "";
    private static long errorUntil;
    private static int graceTicks;
    /** Что открыть в «Экранах» после выбора (id экрана). */
    private static @Nullable String done;

    static void init() {
        ClientPreAttackCallback.EVENT.register((client, player, clicks) -> {
            if (mode == Mode.NONE) {
                return false;
            }
            if (clicks > 0) {
                click(client);
            }
            return true; // блок не ломаем
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "screen_picker"), ScreenPicker::hud);
    }

    public static Mode mode() {
        return mode;
    }

    /** Начать выбор: экрана (screenId не нужен) или колонки экрана screenId. */
    public static void start(Mode what, String screenId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        mode = what;
        speakerFor = screenId;
        moveSpeaker = null;
        intoGroup = null;
        first = null;
        error = "";
        graceTicks = 5;
        if (mc.screen != null) {
            mc.setScreen(null);
        }
    }

    /** Новая колонка группы колонок: источник как у группы, сама — в группе. */
    public static void startForGroup(String source, String groupId) {
        start(Mode.SPEAKER, source);
        intoGroup = groupId;
    }

    /** Переставить колонку на другой блок. */
    public static void startMove(ScreenData.Speaker speaker) {
        start(Mode.SPEAKER, speaker.screen);
        moveSpeaker = speaker.id;
    }

    /** Для DevShots: нажатие ЛКМ в режиме выбора. */
    public static void debugClick() {
        click(Minecraft.getInstance());
    }

    /** id экрана, выбранного последним (приложение откроет его), и сброс. */
    public static @Nullable String takeDone() {
        String d = done;
        done = null;
        return d;
    }

    static void tick() {
        if (mode == Mode.NONE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            mode = Mode.NONE;
            return;
        }
        if (graceTicks > 0) {
            graceTicks--;
            return;
        }
        if (mc.screen != null) {
            mode = Mode.NONE; // Esc (меню), телефон и т. п. — отмена
        }
    }

    private static void click(Minecraft mc) {
        if (!(target(mc) instanceof BlockHitResult hit) || mc.level == null || mc.player == null) {
            fail("screens.pick.aim");
            return;
        }
        BlockPos pos = hit.getBlockPos();
        String dim = mc.level.dimension().identifier().toString();
        ScreenData.Set data = ScreenStore.data();
        if (mode == Mode.SPEAKER) {
            ScreenData.Speaker sp = null;
            for (ScreenData.Speaker existing : data.speakers) {
                if (existing.id.equals(moveSpeaker)) {
                    sp = existing; // переставить колонку
                }
            }
            if (sp == null) {
                if (data.speakers.size() >= ScreenData.MAX_SPEAKERS) {
                    fail("screens.error.speakers");
                    return;
                }
                sp = new ScreenData.Speaker();
                sp.id = ScreenStore.newId();
                sp.name = PhoneLang.tr("screens.speaker_default", data.speakers.size() + 1);
                sp.screen = speakerFor;
                data.speakers.add(sp);
                ScreenData.Group grp = intoGroup == null ? null : data.group(intoGroup);
                if (grp != null && grp.members.size() < ScreenData.MAX_GROUP_MEMBERS) {
                    grp.members.add(sp.id);
                }
            }
            sp.dim = dim;
            sp.x = pos.getX();
            sp.y = pos.getY();
            sp.z = pos.getZ();
            ScreenStore.changed();
            finish(SPEAKER_PREFIX + sp.id);
            return;
        }
        if (first == null) {
            first = pos;
            firstFace = hit.getDirection();
            return;
        }
        Direction.Axis axis = firstFace.getAxis();
        if (axis.choose(pos.getX(), pos.getY(), pos.getZ()) != axis.choose(first.getX(), first.getY(), first.getZ())) {
            fail("screens.pick.plane");
            return;
        }
        if (Math.abs(pos.getX() - first.getX()) >= ScreenData.MAX_SIZE || Math.abs(pos.getY() - first.getY()) >= ScreenData.MAX_SIZE
                || Math.abs(pos.getZ() - first.getZ()) >= ScreenData.MAX_SIZE) {
            fail("screens.pick.size");
            return;
        }
        if (data.screens.size() >= ScreenData.MAX_SCREENS) {
            fail("screens.error.screens");
            return;
        }
        ScreenData.Screen s = make(first, pos, firstFace, mc.player.getDirection(), dim);
        s.id = ScreenStore.newId();
        s.name = PhoneLang.tr("screens.default_name", data.screens.size() + 1);
        s.paused = true;
        s.at = System.currentTimeMillis();
        data.screens.add(s);
        ScreenStore.changed();
        finish(s.id);
    }

    /** Экран по двум углам; facing — куда смотрит игрок (верх картинки на полу и потолке). */
    private static ScreenData.Screen make(BlockPos a, BlockPos b, Direction face, Direction facing, String dim) {
        ScreenData.Screen s = new ScreenData.Screen();
        s.dim = dim;
        s.x1 = a.getX();
        s.y1 = a.getY();
        s.z1 = a.getZ();
        s.x2 = b.getX();
        s.y2 = b.getY();
        s.z2 = b.getZ();
        s.face = face.get3DDataValue();
        // На полу верх картинки — от игрока (он смотрит вниз), на потолке — к нему.
        s.up = (face == Direction.DOWN ? facing.getOpposite() : facing).get3DDataValue();
        return s;
    }

    private static void finish(String screenId) {
        mode = Mode.NONE;
        first = null;
        done = screenId;
        Minecraft mc = Minecraft.getInstance();
        PhoneState.setCurrent("screens");
        if (!PhonePin.active()) {
            mc.setScreen(new PhoneScreen());
        }
    }

    /** Блок под прицелом дальше обычной досягаемости руки (экран строят издалека). */
    private static @Nullable BlockHitResult target(Minecraft mc) {
        if (mc.player == null) {
            return null;
        }
        HitResult hit = mc.player.pick(PICK_DISTANCE, 1, false);
        return hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
    }

    private static void fail(String key) {
        error = PhoneLang.tr(key);
        errorUntil = System.currentTimeMillis() + 3000;
    }

    // --- Подсказка и предпросмотр ---

    private static void hud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mode == Mode.NONE || mc.options.hideGui) {
            return;
        }
        String hint = PhoneLang.tr(mode == Mode.SPEAKER ? "screens.pick.speaker" : first == null ? "screens.pick.first" : "screens.pick.second");
        int w = g.guiWidth();
        int y = g.guiHeight() - 90;
        for (var line : mc.font.split(net.minecraft.network.chat.Component.literal(hint), Math.max(100, w - 40))) {
            g.drawCenteredString(mc.font, line, w / 2, y, 0xFFFFFFFF);
            y += 11;
        }
        g.drawCenteredString(mc.font, PhoneLang.tr("screens.pick.cancel"), w / 2, y + 1, 0xFFB0B8C8);
        if (!error.isEmpty() && System.currentTimeMillis() < errorUntil) {
            g.drawCenteredString(mc.font, error, w / 2, y + 15, 0xFFFF6B6B);
        }
    }

    static void render(PoseStack pose, SubmitNodeCollector collector, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        if (mode == Mode.NONE || mc.level == null || mc.player == null) {
            return;
        }
        // Поверх экранов (полупрозрачное рисуется по порядку, а не по глубине).
        var queue = collector.order(1);
        int accent = (PhoneTheme.accent() & 0x00FFFFFF) | 0x70000000;
        // Свои колонки (обычно невидимы) — кубиками.
        String dim = mc.level.dimension().identifier().toString();
        for (ScreenData.Speaker sp : ScreenStore.data().speakers) {
            if (dim.equals(sp.dim)) {
                cube(pose, queue, cam, sp.center(), 0.3, sp.screen.equals(speakerFor) ? 0xC0FFFFFF : 0x80FFFFFF);
            }
        }
        if (!(target(mc) instanceof BlockHitResult hit)) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        if (mode == Mode.SPEAKER) {
            cube(pose, queue, cam, Vec3.atCenterOf(pos), 0.51, accent);
            return;
        }
        BlockPos a = first != null ? first : pos;
        Direction face = first != null ? firstFace : hit.getDirection();
        BlockPos b = pos;
        if (first != null) {
            // Второй угол — в той же плоскости, что и первый.
            Direction.Axis axis = face.getAxis();
            int fixed = axis.choose(a.getX(), a.getY(), a.getZ());
            b = new BlockPos(axis == Direction.Axis.X ? fixed : pos.getX(), axis == Direction.Axis.Y ? fixed : pos.getY(),
                    axis == Direction.Axis.Z ? fixed : pos.getZ());
        }
        ScreenData.Screen preview = make(a, b, face, mc.player.getDirection(), dim);
        Screens.quad(pose, queue, cam, preview.corners(0.01), Screens.WHITE, accent);
    }

    private static void cube(PoseStack pose, net.minecraft.client.renderer.OrderedSubmitNodeCollector queue, Vec3 cam, Vec3 c, double half,
                             int color) {
        cube(pose, queue, cam, c, half, color, false);
    }

    /** Кубик со стороной 2·half вокруг c; seeThrough — виден сквозь блоки. */
    static void cube(PoseStack pose, net.minecraft.client.renderer.OrderedSubmitNodeCollector queue, Vec3 cam, Vec3 c, double half,
                     int color, boolean seeThrough) {
        for (Direction d : Direction.values()) {
            ScreenData.Screen face = new ScreenData.Screen();
            BlockPos p = BlockPos.containing(c);
            face.x1 = face.x2 = p.getX();
            face.y1 = face.y2 = p.getY();
            face.z1 = face.z2 = p.getZ();
            face.face = d.get3DDataValue();
            Vec3[] corners = face.corners(0);
            // Сжать грань блока к центру до куба со стороной 2·half.
            for (int i = 0; i < 4; i++) {
                corners[i] = c.add(corners[i].subtract(c).scale(half * 2));
            }
            Screens.quad(pose, queue, cam, corners, Screens.WHITE, color, seeThrough);
        }
    }

    private ScreenPicker() {
    }
}
