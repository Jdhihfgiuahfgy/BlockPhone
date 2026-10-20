package dev.blockphone.fabric.client.social;

import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.PrivacyData.Audience;
import dev.blockphone.common.protocol.data.PrivacyData.Kind;
import dev.blockphone.common.protocol.data.PrivacyData.Privacy;
import dev.blockphone.common.protocol.packet.PrivacyPackets;
import dev.blockphone.fabric.client.network.ClientNetwork;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Мои настройки приватности на текущем сервере (приходят по запросу и после каждого изменения). */
public final class PrivacyClient {
    private static @Nullable PrivacyPackets.State state;

    public static void onState(PrivacyPackets.State next) {
        state = next;
    }

    public static void request() {
        ClientNetwork.send(new PrivacyPackets.Request());
    }

    public static @Nullable Privacy privacy() {
        return state != null ? state.privacy() : null;
    }

    public static List<UserInfo> allowed() {
        return state != null ? state.allowed() : List.of();
    }

    public static List<UserInfo> blocked() {
        return state != null ? state.blocked() : List.of();
    }

    /** Следующее/предыдущее значение настройки. */
    public static void cycle(Kind kind, int dir) {
        Privacy p = privacy();
        if (p == null) {
            return;
        }
        Audience[] values = Audience.values();
        Audience next = values[Math.floorMod(p.get(kind).ordinal() + dir, values.length)];
        ClientNetwork.send(new PrivacyPackets.Update(p.with(kind, next)));
    }

    public static void list(UUID user, boolean blockList, boolean add) {
        ClientNetwork.send(new PrivacyPackets.ListAction(user, blockList, add));
    }

    public static void reset() {
        state = null;
    }

    private PrivacyClient() {
    }
}
