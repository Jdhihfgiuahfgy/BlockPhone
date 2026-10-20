package dev.blockphone.fabric.client.messenger;

import dev.blockphone.common.protocol.data.StickerData.Limits;
import dev.blockphone.common.protocol.data.StickerData.PackInfo;
import dev.blockphone.common.protocol.packet.StickerPackets;
import dev.blockphone.fabric.client.network.ClientNetwork;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Стикеры на текущем сервере: ограничения, мои наборы (свои и добавленные), просмотренные чужие наборы. */
public final class StickerClient {
    private static @Nullable Limits limits;
    private static List<PackInfo> mine = List.of();
    private static final Map<Long, PackInfo> PACKS = new HashMap<>();
    private static boolean requested;

    public static void handle(Object packet) {
        if (packet instanceof StickerPackets.Mine m) {
            limits = m.limits();
            mine = m.packs();
            for (PackInfo pack : m.packs()) {
                PACKS.put(pack.id(), pack);
            }
        } else if (packet instanceof StickerPackets.Pack p) {
            PACKS.put(p.pack().id(), p.pack());
        }
    }

    /** Запросить мои наборы (один раз за сессию, дальше сервер присылает изменения сам). */
    public static void ensureLoaded() {
        if (!requested && ClientNetwork.messengerAvailable()) {
            requested = true;
            ClientNetwork.send(new StickerPackets.RequestMine());
        }
    }

    public static boolean enabled() {
        ensureLoaded();
        return limits != null && limits.enabled() && ClientNetwork.messengerAvailable();
    }

    public static @Nullable Limits limits() {
        return limits;
    }

    public static List<PackInfo> mine() {
        return mine;
    }

    public static long ownCount() {
        return mine.stream().filter(PackInfo::own).count();
    }

    public static @Nullable PackInfo pack(long id) {
        return PACKS.get(id);
    }

    public static void requestPack(long id) {
        ClientNetwork.send(new StickerPackets.RequestPack(id));
    }

    public static void edit(StickerPackets.PackAction action, long packId, String title) {
        ClientNetwork.send(new StickerPackets.EditPack(action, packId, title));
    }

    public static void addSticker(long packId, long imageId) {
        ClientNetwork.send(new StickerPackets.AddSticker(packId, imageId));
    }

    public static void removeSticker(long stickerId) {
        ClientNetwork.send(new StickerPackets.RemoveSticker(stickerId));
    }

    public static void reset() {
        limits = null;
        mine = List.of();
        PACKS.clear();
        requested = false;
    }

    private StickerClient() {
    }
}
