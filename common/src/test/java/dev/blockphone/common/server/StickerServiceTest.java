package dev.blockphone.common.server;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.protocol.data.StickerData;
import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.ImagePackets;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.protocol.packet.StickerPackets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Стикеры (наборы, загрузка, отправка, история) и ограничения загрузок от админа. */
class StickerServiceTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;
    FakePlatform platform;
    BlockPhoneServer server;
    UploadSettings uploads;

    @BeforeEach
    void setUp() throws Exception {
        platform = new FakePlatform();
        uploads = new UploadSettings();
        uploads.stickers.maxPerPack = 2;
        uploads.quota.uploadsPerDay = 3;
        ServerSettings.Storage storage = new ServerSettings.Storage("h2", dir.resolve("db"), "", 0, "", "", "", "bp_");
        ServerSettings settings = new ServerSettings(true, true, storage, ServerSettings.Social.defaults(), List.of(),
                ServerSettings.Voice.defaults(), null, null, uploads);
        server = new BlockPhoneServer(platform, settings);
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

    private void send(UUID uuid, Packet packet) throws Exception {
        server.onMessage(uuid, uuid.equals(ALEX) ? "Alex" : "Steve", Packets.encode(packet));
        flush();
    }

    private void flush() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        server.messenger().runInThread(repo -> latch.countDown());
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    private static byte[] png(int side) throws Exception {
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(side / 2, side / 2, 0xFFFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private ImagePackets.UploadResult upload(UUID player, byte[] data) throws Exception {
        int id = (int) (System.nanoTime() & 0xFFFF);
        send(player, new ImagePackets.UploadBegin(id, data.length, ImageRef.Purpose.STICKER, ""));
        for (int offset = 0; offset < data.length; offset += ImageRef.CHUNK) {
            byte[] part = Arrays.copyOfRange(data, offset, Math.min(data.length, offset + ImageRef.CHUNK));
            send(player, new ImagePackets.UploadChunk(id, offset, part));
        }
        return platform.last(player, ImagePackets.UploadResult.class);
    }

    @Test
    void createPackAddStickerAndSendIt() throws Exception {
        send(ALEX, new StickerPackets.EditPack(StickerPackets.PackAction.CREATE, 0, "Котики"));
        StickerPackets.Mine mine = platform.last(ALEX, StickerPackets.Mine.class);
        assertTrue(mine.limits().enabled());
        assertEquals(1, mine.packs().size());
        long packId = mine.packs().get(0).id();
        assertTrue(mine.packs().get(0).own());

        ImagePackets.UploadResult result = upload(ALEX, png(128));
        assertNotNull(result.image(), result.error());
        send(ALEX, new StickerPackets.AddSticker(packId, result.image().id()));
        StickerData.StickerRef sticker = platform.last(ALEX, StickerPackets.Mine.class).packs().get(0).stickers().get(0);

        send(ALEX, new MessengerPackets.OpenDirect(STEVE));
        long chatId = platform.last(ALEX, MessengerPackets.ChatOpened.class).chatId();
        send(ALEX, new MessengerPackets.SendMessage(chatId, "s1", "", 0, 0, sticker.id()));
        MessengerPackets.NewMessage received = platform.last(STEVE, MessengerPackets.NewMessage.class);
        assertNotNull(received.message().sticker());
        assertEquals(packId, received.message().sticker().packId());

        // Стив смотрит набор и добавляет его себе.
        send(STEVE, new StickerPackets.RequestPack(packId));
        assertFalse(platform.last(STEVE, StickerPackets.Pack.class).pack().installed());
        send(STEVE, new StickerPackets.EditPack(StickerPackets.PackAction.INSTALL, packId, ""));
        assertEquals(1, platform.last(STEVE, StickerPackets.Mine.class).packs().size());

        // История: стикер остаётся стикером и после удаления набора (packId 0).
        send(ALEX, new StickerPackets.EditPack(StickerPackets.PackAction.DELETE, packId, ""));
        assertTrue(platform.last(STEVE, StickerPackets.Mine.class).packs().size() <= 1);
        send(STEVE, new MessengerPackets.RequestHistory(chatId, 0));
        MessengerPackets.History history = platform.last(STEVE, MessengerPackets.History.class);
        assertEquals(0, history.messages().get(history.messages().size() - 1).sticker().packId());
    }

    @Test
    void adminLimits() throws Exception {
        send(ALEX, new StickerPackets.EditPack(StickerPackets.PackAction.CREATE, 0, "Набор"));
        long packId = platform.last(ALEX, StickerPackets.Mine.class).packs().get(0).id();
        // Сторона больше max-side (320).
        assertEquals("photo.error.too_big", upload(ALEX, png(400)).error());
        // Стикеров в наборе не больше max-per-pack (2).
        for (int i = 0; i < 2; i++) {
            send(ALEX, new StickerPackets.AddSticker(packId, upload(ALEX, png(64)).image().id()));
        }
        // Отклонённая (слишком большая) не считается: третья загрузка ещё можно, четвёртая — уже нет (uploads-per-day: 3).
        assertNotNull(upload(ALEX, png(64)).image());
        ImagePackets.UploadResult limited = upload(ALEX, png(64));
        assertNull(limited.image());
        assertEquals("photo.error.daily_limit|3", limited.error());
        // Набор уже полный (max-per-pack: 2).
        send(ALEX, new StickerPackets.AddSticker(packId, 999));
        assertEquals("sticker.error.full", platform.last(ALEX, MessengerPackets.Failure.class).key());
        assertEquals(2, platform.last(ALEX, StickerPackets.Mine.class).packs().get(0).stickers().size());
    }
}
