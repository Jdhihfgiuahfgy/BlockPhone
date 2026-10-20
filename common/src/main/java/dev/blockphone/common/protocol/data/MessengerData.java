package dev.blockphone.common.protocol.data;

import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;

import java.util.List;
import java.util.UUID;

/** Данные мессенджера, которые передаются в пакетах. */
public final class MessengerData {
    /** Ограничения (проверяются на сервере; клиент использует их для подсказок). */
    public static final int MAX_MESSAGE_LENGTH = 1000;
    public static final int MAX_TITLE_LENGTH = 32;
    public static final int MAX_GROUP_MEMBERS = 50;
    public static final int MAX_NAME_LENGTH = 16;
    public static final int HISTORY_PAGE = 40;
    public static final int MAX_LIST = 1000;

    public enum ChatType {
        DIRECT, GROUP;

        public static ChatType byId(int id) {
            return id == 1 ? GROUP : DIRECT;
        }
    }

    /** Игрок. online — сейчас на сервере (в сети серверов — где угодно в сети) и с модом. */
    public record UserInfo(UUID uuid, String name, boolean online) {
        public static UserInfo read(PacketReader r) {
            return new UserInfo(r.readUuid(), r.readString(MAX_NAME_LENGTH * 4), r.readBoolean());
        }

        public static void write(PacketWriter w, UserInfo u) {
            w.writeUuid(u.uuid).writeString(u.name).writeBoolean(u.online);
        }
    }

    /** Сообщение. image — фото или null; editedAt = 0 — не редактировалось; deleted — удалено (текст пустой). */
    public record MessageInfo(long id, long chatId, UserInfo sender, String text, ImageRef image, long sentAt, long editedAt,
                              boolean deleted) {
        public static MessageInfo read(PacketReader r) {
            return new MessageInfo(r.readLong(), r.readLong(), UserInfo.read(r), r.readString(MAX_MESSAGE_LENGTH * 2),
                    r.readNullable(ImageRef::read), r.readLong(), r.readLong(), r.readBoolean());
        }

        public static void write(PacketWriter w, MessageInfo m) {
            w.writeLong(m.id).writeLong(m.chatId);
            UserInfo.write(w, m.sender);
            w.writeString(m.text).writeNullable(m.image, ImageRef::write).writeLong(m.sentAt).writeLong(m.editedAt)
                    .writeBoolean(m.deleted);
        }
    }

    /**
     * Чат в списке чатов. Для личного чата title пустой — клиент показывает имя собеседника.
     *
     * @param lastMessage последнее сообщение или null
     * @param unread      непрочитанных сообщений для получателя пакета
     */
    public record ChatInfo(long id, ChatType type, String title, UUID owner, List<UserInfo> members,
                           MessageInfo lastMessage, int unread, long createdAt) {
        public static ChatInfo read(PacketReader r) {
            return new ChatInfo(r.readLong(), ChatType.byId(r.readVarInt()), r.readString(MAX_TITLE_LENGTH * 2), r.readUuid(),
                    r.readList(MAX_GROUP_MEMBERS + 1, UserInfo::read), r.readNullable(MessageInfo::read), r.readVarInt(), r.readLong());
        }

        public static void write(PacketWriter w, ChatInfo c) {
            w.writeLong(c.id).writeVarInt(c.type.ordinal()).writeString(c.title).writeUuid(c.owner)
                    .writeList(c.members, UserInfo::write)
                    .writeNullable(c.lastMessage, MessageInfo::write)
                    .writeVarInt(c.unread).writeLong(c.createdAt);
        }

        /** Время последней активности (для сортировки списка). */
        public long activity() {
            return lastMessage != null ? lastMessage.sentAt() : createdAt;
        }
    }

    private MessengerData() {
    }
}
