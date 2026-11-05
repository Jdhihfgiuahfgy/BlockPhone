package dev.blockphone.common.server;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.data.PrivacyData.Audience;
import dev.blockphone.common.protocol.data.PrivacyData.Privacy;
import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.protocol.packet.PrivacyPackets;
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

/** Приватность лички и оформление профиля (протокол + логика + SQL на H2). */
class PrivacyServiceTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID NOTCH = UUID.fromString("00000000-0000-0000-0000-00000000000c");

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
        join(NOTCH, "Notch");
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

    private void send(UUID uuid, Packet packet) throws Exception {
        server.onMessage(uuid, uuid == ALEX ? "Alex" : uuid == STEVE ? "Steve" : "Notch", Packets.encode(packet));
        flush();
    }

    private void flush() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        server.messenger().runInThread(repo -> latch.countDown());
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    private String lastError(UUID uuid) {
        MessengerPackets.Failure f = platform.last(uuid, MessengerPackets.Failure.class);
        return f == null ? "" : f.key();
    }

    @Test
    void closedDirectMessagesAndAllowList() throws Exception {
        Privacy closed = Privacy.DEFAULT.with(dev.blockphone.common.protocol.data.PrivacyData.Kind.MESSAGES, Audience.NOBODY);
        send(ALEX, new PrivacyPackets.Update(closed));
        assertEquals(Audience.NOBODY, platform.last(ALEX, PrivacyPackets.State.class).privacy().messages());

        send(STEVE, new MessengerPackets.OpenDirect(ALEX));
        assertEquals("messenger.error.privacy_messages", lastError(STEVE));

        // Разрешённым можно, даже если личка закрыта.
        send(ALEX, new PrivacyPackets.ListAction(STEVE, false, true));
        assertEquals(1, platform.last(ALEX, PrivacyPackets.State.class).allowed().size());
        send(STEVE, new MessengerPackets.OpenDirect(ALEX));
        long chatId = platform.last(STEVE, MessengerPackets.ChatOpened.class).chatId();
        send(STEVE, new MessengerPackets.SendMessage(chatId, "n1", "привет", 0, 0));
        assertEquals("привет", platform.last(ALEX, MessengerPackets.NewMessage.class).message().text());

        // Заблокировал — больше не пишет и в уже открытый чат.
        send(ALEX, new PrivacyPackets.ListAction(STEVE, true, true));
        PrivacyPackets.State state = platform.last(ALEX, PrivacyPackets.State.class);
        assertTrue(state.allowed().isEmpty());
        assertEquals(1, state.blocked().size());
        int before = platform.of(ALEX, MessengerPackets.NewMessage.class).size();
        send(STEVE, new MessengerPackets.SendMessage(chatId, "n2", "ещё", 0, 0));
        assertEquals(before, platform.of(ALEX, MessengerPackets.NewMessage.class).size());
        assertEquals("messenger.error.privacy_messages", lastError(STEVE));
    }

    @Test
    void groupInvitesRespectPrivacy() throws Exception {
        send(ALEX, new PrivacyPackets.Update(Privacy.DEFAULT.with(dev.blockphone.common.protocol.data.PrivacyData.Kind.INVITES,
                Audience.FOLLOWING)));
        send(STEVE, new MessengerPackets.CreateGroup("Строители", List.of(ALEX, NOTCH)));
        MessengerPackets.Failure f = platform.last(STEVE, MessengerPackets.Failure.class);
        assertEquals("messenger.error.privacy_invites", f.key());
        assertEquals("1", f.arg());
        assertTrue(platform.of(ALEX, MessengerPackets.ChatUpdate.class).isEmpty());
        assertFalse(platform.of(NOTCH, MessengerPackets.ChatUpdate.class).isEmpty());
    }

    @Test
    void profileLookIsSavedAndShown() throws Exception {
        send(ALEX, new SocialPackets.UpdateLook(1, 3, 5, 2, 0, 0));
        SocialPackets.Profile profile = platform.last(ALEX, SocialPackets.Profile.class);
        assertEquals(1, profile.profile().look().avatarType());
        assertEquals(3, profile.profile().look().pose());
        assertEquals(5, profile.profile().look().background());
        assertEquals(2, profile.profile().look().bannerStyle());
        send(STEVE, new SocialPackets.RequestProfile(ALEX));
        assertTrue(platform.last(STEVE, SocialPackets.Profile.class).profile().canMessage());
    }
}
