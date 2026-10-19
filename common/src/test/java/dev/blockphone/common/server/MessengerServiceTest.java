package dev.blockphone.common.server;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.data.MessengerData;
import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.protocol.packet.ServerHelloPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверка мессенджера целиком (протокол + логика + SQL на H2), без Minecraft. */
class MessengerServiceTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID NOTCH = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    /** Платформа-заглушка: складывает отправленные пакеты по игрокам. */
    static final class FakePlatform implements ServerPlatform {
        final Map<UUID, List<Packet>> inbox = new ConcurrentHashMap<>();

        @Override
        public String name() {
            return "test";
        }

        @Override
        public void send(UUID player, byte[] data) {
            // Через кодирование/декодирование — заодно проверяем формат пакетов.
            inbox.computeIfAbsent(player, k -> new CopyOnWriteArrayList<>()).add(Packets.decode(data));
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message, Throwable error) {
            throw new AssertionError(message, error);
        }

        <T extends Packet> List<T> of(UUID player, Class<T> type) {
            List<T> result = new ArrayList<>();
            for (Packet p : inbox.getOrDefault(player, List.of())) {
                if (type.isInstance(p)) {
                    result.add(type.cast(p));
                }
            }
            return result;
        }

        <T extends Packet> T last(UUID player, Class<T> type) {
            List<T> all = of(player, type);
            assertFalse(all.isEmpty(), "no " + type.getSimpleName() + " for " + player);
            return all.get(all.size() - 1);
        }
    }

    @TempDir
    Path dir;
    FakePlatform platform;
    BlockPhoneServer server;

    @BeforeEach
    void setUp() throws Exception {
        platform = new FakePlatform();
        ServerSettings.Storage storage = new ServerSettings.Storage("h2", dir.resolve("messenger"), "", 0, "", "", "", "bp_");
        server = new BlockPhoneServer(platform, new ServerSettings(true, true, storage));
        server.start();
        join(ALEX, "Alex");
        join(STEVE, "Steve");
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private void join(UUID uuid, String name) throws Exception {
        server.onChannelReady(uuid);
        server.onMessage(uuid, name, Packets.encode(new ClientHelloPacket(BlockPhone.PROTOCOL_VERSION, "test")));
        flush();
    }

    private void send(UUID uuid, String name, Packet packet) throws Exception {
        server.onMessage(uuid, name, Packets.encode(packet));
        flush();
    }

    /** Дождаться, пока поток мессенджера выполнит всё, что уже поставлено в очередь. */
    private void flush() throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        server.messenger().runInThread(repo -> latch.countDown());
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    @Test
    void helloAndEmptyChatList() {
        assertTrue(platform.last(ALEX, ServerHelloPacket.class).messengerEnabled());
        assertTrue(platform.last(ALEX, MessengerPackets.ChatList.class).chats().isEmpty());
        // Alex получил уведомление, что Steve зашёл.
        assertEquals("Steve", platform.last(ALEX, MessengerPackets.Presence.class).user().name());
    }

    @Test
    void directChatMessageEditDelete() throws Exception {
        send(ALEX, "Alex", new MessengerPackets.OpenDirect(STEVE));
        long chatId = platform.last(ALEX, MessengerPackets.ChatOpened.class).chatId();
        assertEquals(chatId, platform.last(STEVE, MessengerPackets.ChatUpdate.class).chat().id());

        send(ALEX, "Alex", new MessengerPackets.SendMessage(chatId, "n1", "  Привет, Стив!  "));
        MessengerPackets.NewMessage forAlex = platform.last(ALEX, MessengerPackets.NewMessage.class);
        MessengerPackets.NewMessage forSteve = platform.last(STEVE, MessengerPackets.NewMessage.class);
        assertEquals("n1", forAlex.nonce());
        assertEquals("", forSteve.nonce());
        assertEquals("Привет, Стив!", forSteve.message().text());
        assertEquals("Alex", forSteve.message().sender().name());

        // Повторное открытие — тот же чат.
        send(STEVE, "Steve", new MessengerPackets.OpenDirect(ALEX));
        assertEquals(chatId, platform.last(STEVE, MessengerPackets.ChatOpened.class).chatId());
        assertEquals(1, platform.last(STEVE, MessengerPackets.ChatUpdate.class).chat().unread());

        long messageId = forSteve.message().id();
        send(STEVE, "Steve", new MessengerPackets.MarkRead(chatId, messageId));
        send(ALEX, "Alex", new MessengerPackets.EditMessage(messageId, "Привет!"));
        assertEquals("Привет!", platform.last(STEVE, MessengerPackets.MessageUpdated.class).message().text());
        // Чужое сообщение удалить нельзя.
        send(STEVE, "Steve", new MessengerPackets.DeleteMessage(messageId));
        assertFalse(platform.last(STEVE, MessengerPackets.MessageUpdated.class).message().deleted());
        send(ALEX, "Alex", new MessengerPackets.DeleteMessage(messageId));
        assertTrue(platform.last(STEVE, MessengerPackets.MessageUpdated.class).message().deleted());

        send(STEVE, "Steve", new MessengerPackets.RequestHistory(chatId, 0));
        MessengerPackets.History history = platform.last(STEVE, MessengerPackets.History.class);
        assertEquals(1, history.messages().size());
        assertFalse(history.hasMore());
    }

    @Test
    void historyPaging() throws Exception {
        send(ALEX, "Alex", new MessengerPackets.OpenDirect(STEVE));
        long chatId = platform.last(ALEX, MessengerPackets.ChatOpened.class).chatId();
        // Обходим ограничение частоты: пишем напрямую в базу.
        server.messenger().runInThread(repo -> {
            for (int i = 0; i < 50; i++) {
                repo.insertMessage(chatId, ALEX, "msg " + i, 1000 + i);
            }
        });
        flush();
        send(STEVE, "Steve", new MessengerPackets.RequestHistory(chatId, 0));
        MessengerPackets.History page1 = platform.last(STEVE, MessengerPackets.History.class);
        assertEquals(MessengerData.HISTORY_PAGE, page1.messages().size());
        assertTrue(page1.hasMore());
        assertEquals("msg 49", page1.messages().get(page1.messages().size() - 1).text());
        send(STEVE, "Steve", new MessengerPackets.RequestHistory(chatId, page1.messages().get(0).id()));
        MessengerPackets.History page2 = platform.last(STEVE, MessengerPackets.History.class);
        assertTrue(page2.older());
        assertFalse(page2.hasMore());
        assertEquals(50 - MessengerData.HISTORY_PAGE, page2.messages().size());
        assertEquals("msg 0", page2.messages().get(0).text());
    }

    @Test
    void rateLimit() throws Exception {
        send(ALEX, "Alex", new MessengerPackets.OpenDirect(STEVE));
        long chatId = platform.last(ALEX, MessengerPackets.ChatOpened.class).chatId();
        for (int i = 0; i < 10; i++) {
            send(ALEX, "Alex", new MessengerPackets.SendMessage(chatId, "n" + i, "spam " + i));
        }
        assertEquals(8, platform.of(STEVE, MessengerPackets.NewMessage.class).size());
        assertEquals("messenger.error.rate_limit", platform.last(ALEX, MessengerPackets.Failure.class).key());
    }

    @Test
    void groupLifecycle() throws Exception {
        join(NOTCH, "Notch");
        send(ALEX, "Alex", new MessengerPackets.CreateGroup("Строители", List.of(STEVE)));
        long chatId = platform.last(ALEX, MessengerPackets.ChatOpened.class).chatId();
        MessengerData.ChatInfo info = platform.last(STEVE, MessengerPackets.ChatUpdate.class).chat();
        assertEquals("Строители", info.title());
        assertEquals(2, info.members().size());

        // Steve не владелец — переименовать нельзя, но добавить участника можно.
        send(STEVE, "Steve", new MessengerPackets.GroupAction(chatId, MessengerPackets.GroupAction.Action.RENAME, "Хаос", List.of()));
        assertEquals("messenger.error.not_owner", platform.last(STEVE, MessengerPackets.Failure.class).key());
        send(STEVE, "Steve", new MessengerPackets.GroupAction(chatId, MessengerPackets.GroupAction.Action.ADD, "", List.of(NOTCH)));
        assertEquals(3, platform.last(NOTCH, MessengerPackets.ChatUpdate.class).chat().members().size());

        // Владелец выходит — владение переходит к следующему.
        send(ALEX, "Alex", new MessengerPackets.GroupAction(chatId, MessengerPackets.GroupAction.Action.LEAVE, "", List.of()));
        assertEquals(chatId, platform.last(ALEX, MessengerPackets.ChatRemoved.class).chatId());
        assertEquals(STEVE, platform.last(NOTCH, MessengerPackets.ChatUpdate.class).chat().owner());

        send(STEVE, "Steve", new MessengerPackets.GroupAction(chatId, MessengerPackets.GroupAction.Action.REMOVE, "", List.of(NOTCH)));
        assertNotNull(platform.last(NOTCH, MessengerPackets.ChatRemoved.class));
    }

    @Test
    void searchAndPresence() throws Exception {
        send(ALEX, "Alex", new MessengerPackets.SearchUsers(7, "st"));
        MessengerPackets.UserList list = platform.last(ALEX, MessengerPackets.UserList.class);
        assertEquals(7, list.requestId());
        assertEquals(1, list.users().size());
        assertEquals("Steve", list.users().get(0).name());
        assertTrue(list.users().get(0).online());

        server.onQuit(STEVE);
        flush();
        assertFalse(platform.last(ALEX, MessengerPackets.Presence.class).user().online());
        send(ALEX, "Alex", new MessengerPackets.SearchUsers(8, ""));
        assertTrue(platform.last(ALEX, MessengerPackets.UserList.class).users().isEmpty());
    }
}
