package dev.blockphone.common.protocol.data;

import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;

/** Данные панели модерации: состояние игрока (мут, бан), журнал действий модераторов. */
public final class ModerationData {
    /** «Навсегда» для времени окончания мута и бана. */
    public static final long FOREVER = Long.MAX_VALUE;
    public static final int MAX_REASON = 100;
    public static final int MAX_LIST = 200;
    public static final int LOG_PAGE = 40;

    /**
     * Игрок в панели модерации.
     *
     * @param mutedUntil  заглушён (не может писать сообщения, посты, комментарии) до этого времени; 0 — нет
     * @param bannedUntil заблокирован в телефоне (мессенджер, лента, звонки) до этого времени; 0 — нет
     */
    public record PlayerStatus(UserInfo user, long mutedUntil, String muteReason, long bannedUntil, String banReason, long lastSeen) {
        public static PlayerStatus read(PacketReader r) {
            return new PlayerStatus(UserInfo.read(r), r.readLong(), r.readString(MAX_REASON * 2), r.readLong(), r.readString(MAX_REASON * 2),
                    r.readLong());
        }

        public static void write(PacketWriter w, PlayerStatus s) {
            UserInfo.write(w, s.user);
            w.writeLong(s.mutedUntil).writeString(s.muteReason).writeLong(s.bannedUntil).writeString(s.banReason).writeLong(s.lastSeen);
        }
    }

    /** Запись журнала модерации: кто, что, над кем, подробности, когда. */
    public record LogEntry(long id, String moderator, String action, String target, String details, long time) {
        public static LogEntry read(PacketReader r) {
            return new LogEntry(r.readLong(), r.readString(64), r.readString(64), r.readString(160), r.readString(512), r.readLong());
        }

        public static void write(PacketWriter w, LogEntry e) {
            w.writeLong(e.id).writeString(e.moderator).writeString(e.action).writeString(e.target).writeString(e.details).writeLong(e.time);
        }
    }

    private ModerationData() {
    }
}
