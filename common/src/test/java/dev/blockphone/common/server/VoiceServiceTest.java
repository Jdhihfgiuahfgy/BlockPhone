package dev.blockphone.common.server;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.protocol.data.VoiceData;
import dev.blockphone.common.protocol.data.VoiceData.CallInfo;
import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.protocol.packet.SessionPacket;
import dev.blockphone.common.protocol.packet.VoicePackets;
import dev.blockphone.common.voice.VoiceBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Голосовые сообщения и звонки (с заглушкой Simple Voice Chat). */
class VoiceServiceTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID NOTCH = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    /** Заглушка голосового чата: кто «подключён» и кто в какой группе. */
    static final class FakeBridge implements VoiceBridge {
        final Set<UUID> connected = new HashSet<>();
        final Map<UUID, UUID> groupOf = new HashMap<>();
        final Set<UUID> groups = new HashSet<>();
        final Set<UUID> removed = new HashSet<>();

        @Override
        public synchronized boolean connected(UUID player) {
            return connected.contains(player);
        }

        @Override
        public synchronized UUID createGroup(String name, boolean isolated) {
            UUID id = UUID.randomUUID();
            groups.add(id);
            return id;
        }

        @Override
        public synchronized void join(UUID groupId, UUID player) {
            groupOf.put(player, groupId);
        }

        @Override
        public synchronized void leave(UUID groupId, UUID player) {
            groupOf.remove(player, groupId);
        }

        @Override
        public synchronized void removeGroup(UUID groupId) {
            removed.add(groupId);
        }
    }

    @TempDir
    Path dir;
    FakePlatform platform;
    FakeBridge bridge;
    BlockPhoneServer server;

    @BeforeEach
    void setUp() throws Exception {
        platform = new FakePlatform();
        bridge = new FakeBridge();
        ServerSettings.Storage storage = new ServerSettings.Storage("h2", dir.resolve("db"), "", 0, "", "", "", "bp_");
        ServerSettings settings = new ServerSettings(true, true, storage, ServerSettings.Social.defaults(), List.of(),
                new ServerSettings.Voice(true, 10, true, "normal"));
        server = new BlockPhoneServer(platform, settings, () -> bridge);
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

    private String name(UUID uuid) {
        return uuid.equals(ALEX) ? "Alex" : uuid.equals(STEVE) ? "Steve" : "Notch";
    }

    private void send(UUID uuid, Packet packet) throws Exception {
        server.onMessage(uuid, name(uuid), Packets.encode(packet));
        flush();
    }

    private void flush() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        server.backend().execute(latch::countDown);
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    private long directChat(UUID a, UUID b) throws Exception {
        send(a, new MessengerPackets.OpenDirect(b));
        return platform.last(a, MessengerPackets.ChatOpened.class).chatId();
    }

    private VoicePackets.UploadResult upload(UUID player, byte[] data, int durationMs) throws Exception {
        send(player, new VoicePackets.UploadBegin(5, data.length, VoiceData.CODEC_ADPCM16, durationMs));
        for (int offset = 0; offset < data.length; offset += ImageRef.CHUNK) {
            server.onMessage(player, name(player), Packets.encode(new VoicePackets.UploadChunk(5, offset,
                    Arrays.copyOfRange(data, offset, Math.min(data.length, offset + ImageRef.CHUNK)))));
        }
        flush();
        return platform.last(player, VoicePackets.UploadResult.class);
    }

    private byte[] download(UUID player, long id) throws Exception {
        platform.inbox.remove(player);
        send(player, new VoicePackets.RequestVoice(id));
        List<VoicePackets.VoiceChunk> parts = platform.of(player, VoicePackets.VoiceChunk.class);
        if (parts.isEmpty() || parts.get(0).total() == 0) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        parts.forEach(p -> out.writeBytes(p.data()));
        return out.toByteArray();
    }

    @Test
    void sessionAdvertisesVoiceAndCalls() {
        SessionPacket session = platform.last(ALEX, SessionPacket.class);
        assertEquals(10, session.maxVoiceSeconds());
        assertTrue(session.callsAvailable());
    }

    @Test
    void voiceMessageRoundTrip() throws Exception {
        long chat = directChat(ALEX, STEVE);
        byte[] audio = new byte[40_000]; // 5 секунд ADPCM.
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (byte) (i * 31);
        }
        assertEquals("voice.error.too_long", upload(ALEX, new byte[200_000], 25_000).error());
        VoicePackets.UploadResult result = upload(ALEX, audio, 5_000);
        assertNotNull(result.voice(), result.error());
        long voiceId = result.voice().id();
        // Пока не отправлено — только автору.
        assertNull(download(STEVE, voiceId));

        send(ALEX, new MessengerPackets.SendMessage(chat, "v1", "", 0, voiceId));
        MessengerPackets.NewMessage message = platform.last(STEVE, MessengerPackets.NewMessage.class);
        assertEquals(5_000, message.message().voice().durationMs());
        assertArrayEquals(audio, download(STEVE, voiceId));
        assertNull(download(NOTCH, voiceId), "не участник чата не слышит");

        // Повторно отправить то же голосовое нельзя.
        send(ALEX, new MessengerPackets.SendMessage(chat, "v2", "", 0, voiceId));
        assertEquals("voice.error.not_found", platform.last(ALEX, MessengerPackets.Failure.class).key());

        // История возвращает голосовое; удаление убирает его.
        send(STEVE, new MessengerPackets.RequestHistory(chat, 0));
        assertNotNull(platform.last(STEVE, MessengerPackets.History.class).messages().get(0).voice());
        send(ALEX, new MessengerPackets.DeleteMessage(message.message().id()));
        assertNull(platform.last(STEVE, MessengerPackets.MessageUpdated.class).message().voice());
        assertNull(download(ALEX, voiceId));
    }

    @Test
    void directCallRingAnswerHangUp() throws Exception {
        long chat = directChat(ALEX, STEVE);
        // Без голосового чата позвонить нельзя.
        send(ALEX, new VoicePackets.StartCall(chat));
        assertEquals("call.error.no_voicechat", platform.last(ALEX, MessengerPackets.Failure.class).key());

        bridge.connected.add(ALEX);
        bridge.connected.add(STEVE);
        send(ALEX, new VoicePackets.StartCall(chat));
        CallInfo ringing = platform.last(STEVE, VoicePackets.CallState.class).call();
        assertTrue(ringing.active());
        assertEquals(List.of("Alex"), ringing.participants().stream().map(u -> u.name()).toList());
        assertEquals(List.of("Steve"), ringing.invited().stream().map(u -> u.name()).toList());
        UUID group = bridge.groupOf.get(ALEX);
        assertNotNull(group);

        send(STEVE, new VoicePackets.AnswerCall(chat, true));
        CallInfo talking = platform.last(ALEX, VoicePackets.CallState.class).call();
        assertEquals(2, talking.participants().size());
        assertTrue(talking.invited().isEmpty());
        assertEquals(group, bridge.groupOf.get(STEVE));

        send(STEVE, new VoicePackets.LeaveCall(chat));
        assertFalse(platform.last(ALEX, VoicePackets.CallState.class).call().active(), "один остался — звонок завершён");
        assertNull(bridge.groupOf.get(ALEX));
        assertTrue(bridge.removed.contains(group));
    }

    @Test
    void declineEndsCallAndQuitLeaves() throws Exception {
        bridge.connected.add(ALEX);
        bridge.connected.add(STEVE);
        bridge.connected.add(NOTCH);
        long chat = directChat(ALEX, STEVE);
        send(ALEX, new VoicePackets.StartCall(chat));
        send(STEVE, new VoicePackets.AnswerCall(chat, false));
        assertFalse(platform.last(ALEX, VoicePackets.CallState.class).call().active(), "отклонили — звонок завершён");

        // Группа: звонят двоим, один принимает, звонящий выходит из игры — остаётся один → конец.
        send(ALEX, new MessengerPackets.CreateGroup("Строители", List.of(STEVE, NOTCH)));
        long group = platform.last(ALEX, MessengerPackets.ChatOpened.class).chatId();
        send(ALEX, new VoicePackets.StartCall(group));
        assertEquals(2, platform.last(NOTCH, VoicePackets.CallState.class).call().invited().size());
        send(NOTCH, new VoicePackets.AnswerCall(group, true));
        send(STEVE, new VoicePackets.AnswerCall(group, false));
        assertEquals(2, platform.last(NOTCH, VoicePackets.CallState.class).call().participants().size());
        server.onQuit(ALEX);
        flush();
        assertFalse(platform.last(NOTCH, VoicePackets.CallState.class).call().active());
        assertNull(bridge.groupOf.get(NOTCH));
    }
}
