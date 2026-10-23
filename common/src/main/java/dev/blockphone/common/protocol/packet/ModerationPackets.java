package dev.blockphone.common.protocol.packet;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;
import dev.blockphone.common.protocol.data.MessengerData;
import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.MessageInfo;
import dev.blockphone.common.protocol.data.ModerationData;
import dev.blockphone.common.protocol.data.ModerationData.LogEntry;
import dev.blockphone.common.protocol.data.ModerationData.PlayerStatus;

import java.util.List;
import java.util.UUID;

/**
 * Панель модерации (обрабатывает {@code ModerationService}): поиск игроков с состоянием мута/бана, наказания (мут, бан,
 * снятие), список наказанных, журнал модераторов, просмотр чатов игрока (право blockphone.moderate.chats).
 * Ошибки — {@link SocialPackets.Failure} с ключами moderation.error.*, успех — {@link SocialPackets.Done} (moderation.done.*).
 */
public final class ModerationPackets {
    /** Что за список игроков: результаты поиска или все наказанные сейчас. */
    public enum ListKind { SEARCH, PUNISHED }

    /** WARN — предупреждение (без ограничений: запись в журнал, сообщение игроку и объявление). */
    public enum Action { MUTE, UNMUTE, BAN, UNBAN, WARN }

    /** C→S: найти игроков по началу ника (пусто — игроки онлайн). */
    public record SearchPlayers(String query) implements Packet {
        public static SearchPlayers read(PacketReader r) {
            return new SearchPlayers(r.readString(32));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeString(query);
        }
    }

    /** C→S: все, у кого сейчас мут или бан. */
    public record RequestPunished() implements Packet {
        public static RequestPunished read(PacketReader r) {
            return new RequestPunished();
        }

        @Override
        public void write(PacketWriter w) {
        }
    }

    /** S→C: список игроков. */
    public record Players(ListKind kind, List<PlayerStatus> players) implements Packet {
        public static Players read(PacketReader r) {
            return new Players(r.readEnum(ListKind.values()), r.readList(ModerationData.MAX_LIST, PlayerStatus::read));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeEnum(kind).writeList(players, PlayerStatus::write);
        }
    }

    /**
     * C→S: наказать или снять наказание.
     *
     * @param minutes длительность мута/бана в минутах; -1 — навсегда (для UNMUTE/UNBAN не важна)
     */
    public record Punish(UUID user, Action action, int minutes, String reason) implements Packet {
        public static Punish read(PacketReader r) {
            return new Punish(r.readUuid(), r.readEnum(Action.values()), r.readVarInt(), r.readString(ModerationData.MAX_REASON));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeUuid(user).writeEnum(action).writeVarInt(minutes).writeString(reason);
        }
    }

    /** S→C: новое состояние игрока (после наказания). */
    public record PlayerUpdated(PlayerStatus player) implements Packet {
        public static PlayerUpdated read(PacketReader r) {
            return new PlayerUpdated(PlayerStatus.read(r));
        }

        @Override
        public void write(PacketWriter w) {
            PlayerStatus.write(w, player);
        }
    }

    /** C→S: журнал модерации (beforeId = 0 — с самого нового). */
    public record RequestLog(long beforeId) implements Packet {
        public static RequestLog read(PacketReader r) {
            return new RequestLog(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(beforeId);
        }
    }

    /** S→C: страница журнала. */
    public record Log(List<LogEntry> entries, boolean append, boolean hasMore) implements Packet {
        public static Log read(PacketReader r) {
            return new Log(r.readList(ModerationData.LOG_PAGE, LogEntry::read), r.readBoolean(), r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeList(entries, LogEntry::write).writeBoolean(append).writeBoolean(hasMore);
        }
    }

    /** C→S: чаты игрока (личные и группы) — для просмотра модератором. */
    public record RequestUserChats(UUID user) implements Packet {
        public static RequestUserChats read(PacketReader r) {
            return new RequestUserChats(r.readUuid());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeUuid(user);
        }
    }

    /** S→C: чаты игрока. */
    public record UserChats(UUID user, String name, List<ChatInfo> chats) implements Packet {
        public static UserChats read(PacketReader r) {
            return new UserChats(r.readUuid(), r.readString(32), r.readList(ModerationData.MAX_LIST, ChatInfo::read));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeUuid(user).writeString(name).writeList(chats, ChatInfo::write);
        }
    }

    /** C→S: переписка чата (только чтение; beforeId = 0 — последние сообщения). */
    public record RequestChat(long chatId, long beforeId) implements Packet {
        public static RequestChat read(PacketReader r) {
            return new RequestChat(r.readLong(), r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId).writeLong(beforeId);
        }
    }

    /** S→C: страница переписки (от старых к новым). */
    public record ChatHistory(long chatId, List<MessageInfo> messages, boolean append, boolean hasMore) implements Packet {
        public static ChatHistory read(PacketReader r) {
            return new ChatHistory(r.readLong(), r.readList(MessengerData.HISTORY_PAGE, MessageInfo::read), r.readBoolean(),
                    r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId).writeList(messages, MessageInfo::write).writeBoolean(append).writeBoolean(hasMore);
        }
    }

    private ModerationPackets() {
    }
}
