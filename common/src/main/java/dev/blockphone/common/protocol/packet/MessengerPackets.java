package dev.blockphone.common.protocol.packet;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;
import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.MessageInfo;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;

import java.util.List;
import java.util.UUID;

import static dev.blockphone.common.protocol.data.MessengerData.MAX_GROUP_MEMBERS;
import static dev.blockphone.common.protocol.data.MessengerData.MAX_LIST;
import static dev.blockphone.common.protocol.data.MessengerData.MAX_MESSAGE_LENGTH;
import static dev.blockphone.common.protocol.data.MessengerData.MAX_TITLE_LENGTH;

/**
 * Пакеты серверного мессенджера. C2S — клиент → сервер, S2C — сервер → клиент.
 * Id назначаются в {@link dev.blockphone.common.protocol.Packets}.
 */
public final class MessengerPackets {

    // ---------- Сервер → клиент ----------

    /** Весь список чатов игрока (после входа). */
    public record ChatList(List<ChatInfo> chats) implements Packet {
        public static ChatList read(PacketReader r) {
            return new ChatList(r.readList(MAX_LIST, ChatInfo::read));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeList(chats, ChatInfo::write);
        }
    }

    /** Чат появился или изменился (название, участники, последнее сообщение, непрочитанные). */
    public record ChatUpdate(ChatInfo chat) implements Packet {
        public static ChatUpdate read(PacketReader r) {
            return new ChatUpdate(ChatInfo.read(r));
        }

        @Override
        public void write(PacketWriter w) {
            ChatInfo.write(w, chat);
        }
    }

    /** Игрок больше не участник чата (вышел или исключён). */
    public record ChatRemoved(long chatId) implements Packet {
        public static ChatRemoved read(PacketReader r) {
            return new ChatRemoved(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId);
        }
    }

    /**
     * Страница истории (от старых к новым).
     *
     * @param older   true — ответ на подгрузку более старых сообщений
     * @param hasMore есть ли ещё более старые сообщения
     */
    public record History(long chatId, List<MessageInfo> messages, boolean older, boolean hasMore) implements Packet {
        public static History read(PacketReader r) {
            return new History(r.readLong(), r.readList(MAX_LIST, MessageInfo::read), r.readBoolean(), r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId).writeList(messages, MessageInfo::write).writeBoolean(older).writeBoolean(hasMore);
        }
    }

    /** Новое сообщение в чате. nonce — то, что прислал отправитель (у остальных пустой). */
    public record NewMessage(MessageInfo message, String nonce) implements Packet {
        public static NewMessage read(PacketReader r) {
            return new NewMessage(MessageInfo.read(r), r.readString(64));
        }

        @Override
        public void write(PacketWriter w) {
            MessageInfo.write(w, message);
            w.writeString(nonce);
        }
    }

    /** Сообщение отредактировано или удалено. */
    public record MessageUpdated(MessageInfo message) implements Packet {
        public static MessageUpdated read(PacketReader r) {
            return new MessageUpdated(MessageInfo.read(r));
        }

        @Override
        public void write(PacketWriter w) {
            MessageInfo.write(w, message);
        }
    }

    /** Результат поиска игроков. */
    public record UserList(int requestId, List<UserInfo> users) implements Packet {
        public static UserList read(PacketReader r) {
            return new UserList(r.readVarInt(), r.readList(MAX_LIST, UserInfo::read));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(requestId).writeList(users, UserInfo::write);
        }
    }

    /** Открыть этот чат (ответ на «написать игроку» / «создать группу»). */
    public record ChatOpened(long chatId) implements Packet {
        public static ChatOpened read(PacketReader r) {
            return new ChatOpened(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId);
        }
    }

    /** Кто-то печатает в чате. */
    public record TypingState(long chatId, UserInfo user) implements Packet {
        public static TypingState read(PacketReader r) {
            return new TypingState(r.readLong(), UserInfo.read(r));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId);
            UserInfo.write(w, user);
        }
    }

    /** Игрок с модом зашёл или вышел. */
    public record Presence(UserInfo user) implements Packet {
        public static Presence read(PacketReader r) {
            return new Presence(UserInfo.read(r));
        }

        @Override
        public void write(PacketWriter w) {
            UserInfo.write(w, user);
        }
    }

    /** Ошибка: ключ перевода (messenger.error.*) и необязательный аргумент. */
    public record Failure(String key, String arg) implements Packet {
        public static Failure read(PacketReader r) {
            return new Failure(r.readString(128), r.readString(256));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeString(key).writeString(arg);
        }
    }

    // ---------- Клиент → сервер ----------

    /** Загрузить историю: beforeId = 0 — последние сообщения, иначе — сообщения старше beforeId. */
    public record RequestHistory(long chatId, long beforeId) implements Packet {
        public static RequestHistory read(PacketReader r) {
            return new RequestHistory(r.readLong(), r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId).writeLong(beforeId);
        }
    }

    /**
     * Новое сообщение; imageId — загруженное фото, voiceId — загруженное голосовое (0 — нет).
     * Без фото и голосового текст обязателен.
     */
    /** stickerId > 0 — сообщение-стикер (текст, фото и голос тогда не нужны). */
    public record SendMessage(long chatId, String nonce, String text, long imageId, long voiceId, long stickerId) implements Packet {
        public SendMessage(long chatId, String nonce, String text, long imageId, long voiceId) {
            this(chatId, nonce, text, imageId, voiceId, 0);
        }

        public static SendMessage read(PacketReader r) {
            return new SendMessage(r.readLong(), r.readString(64), r.readString(MAX_MESSAGE_LENGTH), r.readLong(), r.readLong(),
                    r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId).writeString(nonce).writeString(text).writeLong(imageId).writeLong(voiceId).writeLong(stickerId);
        }
    }

    public record EditMessage(long messageId, String text) implements Packet {
        public static EditMessage read(PacketReader r) {
            return new EditMessage(r.readLong(), r.readString(MAX_MESSAGE_LENGTH));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(messageId).writeString(text);
        }
    }

    public record DeleteMessage(long messageId) implements Packet {
        public static DeleteMessage read(PacketReader r) {
            return new DeleteMessage(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(messageId);
        }
    }

    /** Прочитаны все сообщения чата до messageId включительно. */
    public record MarkRead(long chatId, long messageId) implements Packet {
        public static MarkRead read(PacketReader r) {
            return new MarkRead(r.readLong(), r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId).writeLong(messageId);
        }
    }

    /** Поиск игроков по началу ника (пустой запрос — игроки онлайн). */
    public record SearchUsers(int requestId, String query) implements Packet {
        public static SearchUsers read(PacketReader r) {
            return new SearchUsers(r.readVarInt(), r.readString(32));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(requestId).writeString(query);
        }
    }

    /** Открыть (создать при необходимости) личный чат с игроком. */
    public record OpenDirect(UUID target) implements Packet {
        public static OpenDirect read(PacketReader r) {
            return new OpenDirect(r.readUuid());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeUuid(target);
        }
    }

    public record CreateGroup(String title, List<UUID> members) implements Packet {
        public static CreateGroup read(PacketReader r) {
            return new CreateGroup(r.readString(MAX_TITLE_LENGTH), r.readList(MAX_GROUP_MEMBERS, PacketReader::readUuid));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeString(title).writeList(members, PacketWriter::writeUuid);
        }
    }

    /** Управление группой. */
    public record GroupAction(long chatId, Action action, String title, List<UUID> users) implements Packet {
        public enum Action { RENAME, ADD, REMOVE, LEAVE }

        public static GroupAction read(PacketReader r) {
            long chatId = r.readLong();
            int action = r.readVarInt();
            Action[] values = Action.values();
            if (action < 0 || action >= values.length) {
                throw new dev.blockphone.common.protocol.ProtocolException("Bad group action " + action);
            }
            return new GroupAction(chatId, values[action], r.readString(MAX_TITLE_LENGTH),
                    r.readList(MAX_GROUP_MEMBERS, PacketReader::readUuid));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId).writeVarInt(action.ordinal()).writeString(title).writeList(users, PacketWriter::writeUuid);
        }
    }

    /** «Я печатаю» (клиент шлёт не чаще раза в 3 секунды). */
    public record Typing(long chatId) implements Packet {
        public static Typing read(PacketReader r) {
            return new Typing(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId);
        }
    }

    /** C→S: переслать сообщение (из чата, где игрок участник) в другой свой чат. */
    public record ForwardMessage(long messageId, long toChatId, String nonce) implements Packet {
        public static ForwardMessage read(PacketReader r) {
            return new ForwardMessage(r.readLong(), r.readLong(), r.readString(64));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(messageId).writeLong(toChatId).writeString(nonce);
        }
    }

    /** C→S: поделиться постом ленты в чате (comment — свой текст к нему, может быть пустым). */
    public record SharePost(long postId, long toChatId, String nonce, String comment) implements Packet {
        public static SharePost read(PacketReader r) {
            return new SharePost(r.readLong(), r.readLong(), r.readString(64), r.readString(MAX_MESSAGE_LENGTH));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(postId).writeLong(toChatId).writeString(nonce).writeString(comment);
        }
    }

    private MessengerPackets() {
    }
}
