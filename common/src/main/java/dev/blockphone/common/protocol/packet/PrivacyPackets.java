package dev.blockphone.common.protocol.packet;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.PrivacyData;
import dev.blockphone.common.protocol.data.PrivacyData.Privacy;

import java.util.List;
import java.util.UUID;

/** Приватность (обрабатывает мессенджер на сервере). */
public final class PrivacyPackets {

    /** C→S: прислать мои настройки и списки. */
    public record Request() implements Packet {
        public static Request read(PacketReader r) {
            return new Request();
        }

        @Override
        public void write(PacketWriter w) {
        }
    }

    /** S→C: мои настройки, разрешённые и заблокированные. */
    public record State(Privacy privacy, List<UserInfo> allowed, List<UserInfo> blocked) implements Packet {
        public static State read(PacketReader r) {
            return new State(Privacy.read(r), r.readList(PrivacyData.MAX_LIST, UserInfo::read),
                    r.readList(PrivacyData.MAX_LIST, UserInfo::read));
        }

        @Override
        public void write(PacketWriter w) {
            Privacy.write(w, privacy);
            w.writeList(allowed, UserInfo::write).writeList(blocked, UserInfo::write);
        }
    }

    /** C→S: сохранить настройки. */
    public record Update(Privacy privacy) implements Packet {
        public static Update read(PacketReader r) {
            return new Update(Privacy.read(r));
        }

        @Override
        public void write(PacketWriter w) {
            Privacy.write(w, privacy);
        }
    }

    /** C→S: добавить/убрать игрока в списке разрешённых ({@code blockList = false}) или заблокированных. */
    public record ListAction(UUID user, boolean blockList, boolean add) implements Packet {
        public static ListAction read(PacketReader r) {
            return new ListAction(r.readUuid(), r.readBoolean(), r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeUuid(user).writeBoolean(blockList).writeBoolean(add);
        }
    }

    private PrivacyPackets() {
    }
}
