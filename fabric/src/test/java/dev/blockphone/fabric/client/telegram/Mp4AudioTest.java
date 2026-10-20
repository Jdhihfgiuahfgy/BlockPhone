package dev.blockphone.fabric.client.telegram;

import io.github.jaredmdobson.concentus.OpusDecoder;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Звук в «кружочке»: дорожка Opus дописывается в MP4, видео остаётся целым, звук читается обратно. */
class Mp4AudioTest {
    @TempDir
    Path dir;

    @Test
    void addsReadableOpusTrack() throws Exception {
        Path file = dir.resolve("note.mp4");
        int size = 64;
        try (VideoNoteEncoder encoder = new VideoNoteEncoder(file, size, 15)) {
            for (int f = 0; f < 30; f++) {
                int[] frame = new int[size * size];
                java.util.Arrays.fill(frame, 0xFF20A040);
                encoder.addFrame(frame);
            }
        }
        short[] tone = new short[48_000 * 2];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (short) (Math.sin(2 * Math.PI * 300 * i / 48_000.0) * 9000);
        }
        OggOpus.Encoded encoded = OggOpus.encodePackets(tone, 48_000, 64_000, false);
        Mp4Audio.addOpusTrack(file, encoded.packets(), encoded.preSkip());

        // Видео декодируется как раньше.
        MediaFrames.Decoded video = MediaFrames.decode(file, 32, 15, 8);
        assertEquals(30, video.frames().size());

        try (var channel = NIOUtils.readableChannel(file.toFile())) {
            MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(channel);
            assertEquals(2, demuxer.getTracks().size());
            assertEquals(1, demuxer.getAudioTracks().size());
            DemuxerTrack audio = demuxer.getAudioTracks().get(0);
            OpusDecoder decoder = new OpusDecoder(48_000, 1);
            short[] pcm = new short[960];
            int packets = 0;
            long energy = 0;
            int crossings = 0;
            short prev = 0;
            Packet p;
            while ((p = audio.nextFrame()) != null) {
                ByteBuffer data = p.getData();
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                assertTrue(java.util.Arrays.equals(bytes, encoded.packets().get(packets)), "packet " + packets);
                int n = decoder.decode(bytes, 0, bytes.length, pcm, 0, 960, false);
                if (packets >= 10 && packets < 60) {
                    for (int i = 0; i < n; i++) {
                        energy += (long) pcm[i] * pcm[i];
                        if ((prev < 0) != (pcm[i] < 0)) {
                            crossings++;
                        }
                        prev = pcm[i];
                    }
                }
                packets++;
            }
            assertEquals(encoded.packets().size(), packets);
            // 1 с тона 300 Гц → ~600 переходов через ноль.
            assertTrue(crossings > 560 && crossings < 640, "crossings " + crossings);
            assertTrue(Math.sqrt(energy / 48_000.0) > 4000, "energy");
        }
        byte[] bytes = Files.readAllBytes(file);
        assertTrue(new String(bytes, 0, bytes.length, java.nio.charset.StandardCharsets.ISO_8859_1).contains("dOps"));
    }

    @Test
    void soundtrackMixesAndKeepsLength() {
        assertNull(VideoNoteRecorder.soundtrack(new short[0], new short[0], 4800));
        short[] voice = new short[1600];
        java.util.Arrays.fill(voice, (short) 1000);
        short[] music = new short[2400];
        java.util.Arrays.fill(music, (short) 1000);
        short[] mixed = VideoNoteRecorder.soundtrack(voice, music, 9600);
        assertNotNull(mixed);
        assertEquals(9600, mixed.length);
        assertEquals(2000, mixed[100]); // 1000·1,2 + 1000·0,8
        assertEquals(1200, mixed[3000]); // музыка кончилась
        assertEquals(0, mixed[9000]); // и голос тоже
    }
}
