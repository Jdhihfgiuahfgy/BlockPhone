package dev.blockphone.common.server;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.data.ModerationData;
import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.protocol.packet.ModerationPackets;
import dev.blockphone.common.protocol.packet.SessionPacket;
import dev.blockphone.common.protocol.packet.SocialPackets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Панель модерации: мут и бан, просмотр чатов модератором, журнал, лог сообщений в чат игры. */
class ModerationServiceTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000ad");
    /** Админ без мода — видит лог только в чате игры. */
    private static final UUID CONSOLE_ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000ae");

    @TempDir
    Path dir;
    FakePlatform platform;
    BlockPhoneServer server;

    @BeforeEach
    void setUp() throws Exception {
        platform = new FakePlatform();
        ServerSettings.Storage storage = new ServerSettings.Storage("h2", dir.resolve("messenger"), "", 0, "", "", "", "bp_");
        ModerationSettings moderation = new ModerationSettings();
        moderation.chatLog.enabled = true;
        moderation.banCommand = "tempban {player} {minutes}m {reason}";
        ServerSettings settings = new ServerSettings(true, true, storage, ServerSettings.Social.defaults(), List.of(),
                ServerSettings.Voice.defaults(), null, null, null, moderation);
        server = new BlockPhoneServer(platform, settings);
        server.start();
        for (Permission p : new Permission[]{Permission.MOD_MUTE, Permission.MOD_BAN, Permission.MOD_CHATS, Permission.CHAT_LOG}) {
            platform.grant(ADMIN, p);
        }
        platform.grant(CONSOLE_ADMIN, Permission.CHAT_LOG);
        platform.online.addAll(List.of(ALEX, STEVE, ADMIN, CONSOLE_ADMIN));
        join(ALEX, "Alex");
        join(STEVE, "Steve");
        join(ADMIN, "Admin");
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

    private void flush() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        server.messenger().runInThread(repo -> latch.countDown());
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    private long directChat() throws Exception {
        send(ALEX, "Alex", new MessengerPackets.OpenDirect(STEVE));
        return platform.last(ALEX, MessengerPackets.ChatOpened.class).chatId();
    }

    @Test
    void chatLogGoesToAdminsOnly() throws Exception {
        long chat = directChat();
        send(ALEX, "Alex", new MessengerPackets.SendMessage(chat, "n", "секрет §cкрасный", 0, 0));
        // Админы видят строку в чате игры (тёмно-серым), участники и обычные игроки — нет.
        assertEquals(List.of("§8[ЛС] Alex > Steve: секрет  cкрасный"), platform.chat.get(ADMIN));
        assertEquals(1, platform.chat.get(CONSOLE_ADMIN).size());
        assertFalse(platform.chat.containsKey(STEVE));
        assertFalse(platform.chat.containsKey(ALEX));
    }

    @Test
    void muteBlocksMessagesAndUnmuteRestores() throws Exception {
        long chat = directChat();
        // Обычный игрок наказывать не может.
        send(STEVE, "Steve", new ModerationPackets.Punish(ALEX, ModerationPackets.Action.MUTE, 60, "спам"));
        assertEquals("moderation.error.no_permission", platform.last(STEVE, SocialPackets.Failure.class).key());

        send(ADMIN, "Admin", new ModerationPackets.Punish(ALEX, ModerationPackets.Action.MUTE, 60, "спам"));
        ModerationData.PlayerStatus status = platform.last(ADMIN, ModerationPackets.PlayerUpdated.class).player();
        assertTrue(status.mutedUntil() > System.currentTimeMillis());
        assertEquals("спам", status.muteReason());
        assertTrue(platform.last(ALEX, SessionPacket.class).mutedUntil() > 0);

        platform.clear();
        send(ALEX, "Alex", new MessengerPackets.SendMessage(chat, "n", "привет", 0, 0));
        assertEquals("messenger.error.muted", platform.last(ALEX, MessengerPackets.Failure.class).key());
        assertTrue(platform.of(STEVE, MessengerPackets.NewMessage.class).isEmpty());

        send(ADMIN, "Admin", new ModerationPackets.Punish(ALEX, ModerationPackets.Action.UNMUTE, 0, ""));
        send(ALEX, "Alex", new MessengerPackets.SendMessage(chat, "n", "привет", 0, 0));
        assertEquals("привет", platform.last(STEVE, MessengerPackets.NewMessage.class).message().text());
    }

    @Test
    void banBlocksPhoneAndRunsCommand() throws Exception {
        long chat = directChat();
        send(ADMIN, "Admin", new ModerationPackets.Punish(ALEX, ModerationPackets.Action.BAN, -1, "читы"));
        SessionPacket session = platform.last(ALEX, SessionPacket.class);
        assertEquals(ModerationData.FOREVER, session.bannedUntil());
        assertEquals("читы", session.banReason());
        assertEquals(List.of("tempban Alex -1m читы"), platform.consoleCommands);

        platform.clear();
        send(ALEX, "Alex", new MessengerPackets.SendMessage(chat, "n", "я тут", 0, 0));
        assertEquals("messenger.error.banned", platform.last(ALEX, MessengerPackets.Failure.class).key());
        assertTrue(platform.of(STEVE, MessengerPackets.NewMessage.class).isEmpty());

        // Список наказанных и журнал.
        send(ADMIN, "Admin", new ModerationPackets.RequestPunished());
        ModerationPackets.Players punished = platform.last(ADMIN, ModerationPackets.Players.class);
        assertEquals(1, punished.players().size());
        assertEquals("Alex", punished.players().get(0).user().name());
        send(ADMIN, "Admin", new ModerationPackets.RequestLog(0));
        ModerationData.LogEntry entry = platform.last(ADMIN, ModerationPackets.Log.class).entries().get(0);
        assertEquals("ban", entry.action());
        assertEquals("Admin", entry.moderator());
        assertEquals("Alex", entry.target());

        send(ADMIN, "Admin", new ModerationPackets.Punish(ALEX, ModerationPackets.Action.UNBAN, 0, ""));
        assertEquals(0, platform.last(ALEX, SessionPacket.class).bannedUntil());
        send(ALEX, "Alex", new MessengerPackets.SendMessage(chat, "n", "вернулся", 0, 0));
        assertEquals("вернулся", platform.last(STEVE, MessengerPackets.NewMessage.class).message().text());
    }

    @Test
    void moderatorReadsChats() throws Exception {
        long chat = directChat();
        send(ALEX, "Alex", new MessengerPackets.SendMessage(chat, "n", "раз", 0, 0));
        send(STEVE, "Steve", new MessengerPackets.SendMessage(chat, "m", "два", 0, 0));

        send(STEVE, "Steve", new ModerationPackets.RequestUserChats(ALEX));
        assertEquals("moderation.error.no_permission", platform.last(STEVE, SocialPackets.Failure.class).key());

        send(ADMIN, "Admin", new ModerationPackets.RequestUserChats(ALEX));
        ModerationPackets.UserChats chats = platform.last(ADMIN, ModerationPackets.UserChats.class);
        assertEquals("Alex", chats.name());
        assertEquals(1, chats.chats().size());
        send(ADMIN, "Admin", new ModerationPackets.RequestChat(chat, 0));
        ModerationPackets.ChatHistory history = platform.last(ADMIN, ModerationPackets.ChatHistory.class);
        assertEquals(List.of("раз", "два"), history.messages().stream().map(m -> m.text()).toList());
        // Модератор не участник — новых сообщений ему не приходит.
        assertTrue(platform.of(ADMIN, MessengerPackets.NewMessage.class).isEmpty());
    }
}
