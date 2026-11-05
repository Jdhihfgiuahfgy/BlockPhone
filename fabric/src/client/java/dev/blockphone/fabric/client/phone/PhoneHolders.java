package dev.blockphone.fabric.client.phone;

import dev.blockphone.common.protocol.packet.PhoneUsePackets;
import dev.blockphone.common.protocol.packet.ServerHelloPacket;
import dev.blockphone.fabric.client.camera.CameraMode;
import dev.blockphone.fabric.client.network.ClientNetwork;
import net.minecraft.client.Minecraft;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * «Телефон в руке»: кто из игроков сейчас с телефоном. Свой телефон (открыт экран телефона или включена камера)
 * сообщаем серверу, о других узнаём от него. Рисует {@code PhoneInHandLayer}; настоящий предмет в руке не меняется.
 */
public final class PhoneHolders {
    private static final Set<UUID> REMOTE = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> REMOTE_SIDEWAYS = ConcurrentHashMap.newKeySet();
    /** Что последний раз отправили серверу. */
    private static boolean sentUsing;
    private static boolean sentSideways;
    /** Держит телефон как джойстик (управляет игрой на экране) — набок, двумя руками. */
    public static volatile boolean gamepad;
    /** DevShots: держать набок. */
    public static volatile boolean debugSideways;

    /** Каждый такт: если своё состояние изменилось — сообщить серверу (если он это поддерживает). */
    public static void tick(Minecraft mc) {
        boolean using = mc.player != null && localUsing(mc);
        boolean sideways = using && localSideways(mc);
        ServerHelloPacket hello = ClientNetwork.serverHello();
        if ((using != sentUsing || sideways != sentSideways) && hello != null && hello.phoneInHandVisible()
                && hello.protocolVersion() == dev.blockphone.common.BlockPhone.PROTOCOL_VERSION) {
            ClientNetwork.send(new PhoneUsePackets.InUse(using, sideways));
            sentUsing = using;
            sentSideways = sideways;
        }
    }

    public static void handle(PhoneUsePackets.State state) {
        if (state.using()) {
            REMOTE.add(state.player());
        } else {
            REMOTE.remove(state.player());
        }
        if (state.using() && state.sideways()) {
            REMOTE_SIDEWAYS.add(state.player());
        } else {
            REMOTE_SIDEWAYS.remove(state.player());
        }
    }

    /** Новый сервер (или выход): забыть чужие телефоны, своё состояние отправить заново. */
    public static void reset() {
        REMOTE.clear();
        REMOTE_SIDEWAYS.clear();
        sentUsing = false;
        sentSideways = false;
    }

    /** Держит ли игрок телефон (для себя — по своему экрану, видно с камеры от третьего лица). */
    public static boolean holding(UUID player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && player.equals(mc.player.getUUID())) {
            return localUsing(mc);
        }
        return REMOTE.contains(player);
    }

    /** Держит ли игрок телефон набок двумя руками (приложение набок, игра на экране). */
    public static boolean sideways(UUID player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && player.equals(mc.player.getUUID())) {
            return localUsing(mc) && localSideways(mc);
        }
        return REMOTE_SIDEWAYS.contains(player);
    }

    private static boolean localUsing(Minecraft mc) {
        return mc.screen instanceof PhoneScreen || CameraMode.active() || gamepad;
    }

    private static boolean localSideways(Minecraft mc) {
        return gamepad || debugSideways || (mc.screen instanceof PhoneScreen && PhoneLayout.landscape);
    }

    private PhoneHolders() {
    }
}
