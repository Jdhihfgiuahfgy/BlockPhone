package dev.blockphone.fabric.client.telegram;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Звук в «кружочке»: дописывает в готовый MP4 (видео от JCodec: ftyp, mdat, moov в конце) дорожку Opus
 * (ISO BMFF: sample entry «Opus» + dOps). Файл становится: ftyp | mdat (видео) | moov (+ звуковая дорожка) | mdat (звук).
 * Смещения видео не меняются — меняется только то, что после старого moov.
 */
public final class Mp4Audio {
    private static final int OPUS_RATE = 48_000;
    private static final int PACKET = 960; // 20 мс при 48 кГц

    /**
     * @param packets пакеты Opus по 20 мс (моно)
     * @param preSkip pre-skip в отсчётах 48 кГц
     */
    public static void addOpusTrack(Path mp4, List<byte[]> packets, int preSkip) throws IOException {
        byte[] file = Files.readAllBytes(mp4);
        int moovAt = -1;
        int moovSize = 0;
        for (int pos = 0; pos + 8 <= file.length; ) {
            long size = u32(file, pos);
            String type = new String(file, pos + 4, 4, StandardCharsets.US_ASCII);
            if (size == 1) {
                size = ByteBuffer.wrap(file, pos + 8, 8).getLong();
            } else if (size == 0) {
                size = file.length - pos;
            }
            if (size < 8 || pos + size > file.length) {
                throw new IOException("Broken MP4 box " + type);
            }
            if (type.equals("moov")) {
                moovAt = pos;
                moovSize = (int) size;
            }
            pos += (int) size;
        }
        if (moovAt < 0 || moovAt + moovSize != file.length) {
            throw new IOException("moov is not the last box");
        }
        // Дочерние боксы moov: mvhd правим (next_track_ID, длительность), остальное — как есть.
        ByteArrayOutputStream children = new ByteArrayOutputStream();
        int movieTimescale = 1000;
        long movieDuration = 0;
        int maxTrackId = 1;
        for (int pos = moovAt + 8; pos + 8 <= moovAt + moovSize; ) {
            int size = (int) u32(file, pos);
            String type = new String(file, pos + 4, 4, StandardCharsets.US_ASCII);
            byte[] box = java.util.Arrays.copyOfRange(file, pos, pos + size);
            if (type.equals("mvhd")) {
                int v = box[8];
                int ts = v == 1 ? 28 : 20;
                movieTimescale = (int) u32(box, ts);
                movieDuration = v == 1 ? ByteBuffer.wrap(box, ts + 4, 8).getLong() : u32(box, ts + 4);
            } else if (type.equals("trak")) {
                maxTrackId = Math.max(maxTrackId, trackId(box));
            }
            children.writeBytes(box);
            pos += size;
        }
        long samples = (long) packets.size() * PACKET;
        long audioDuration = samples * movieTimescale / OPUS_RATE;
        int trackId = maxTrackId + 1;
        byte[] moovBody = children.toByteArray();
        byte[] trakTemplate = trak(trackId, audioDuration, samples, packets, preSkip, 0);
        int newMoovSize = 8 + moovBody.length + trakTemplate.length;
        long audioOffset = moovAt + newMoovSize + 8L; // данные звука — сразу после заголовка нового mdat
        byte[] trak = trak(trackId, audioDuration, samples, packets, preSkip, audioOffset);
        patchMvhd(moovBody, trackId + 1, Math.max(movieDuration, audioDuration));

        ByteArrayOutputStream out = new ByteArrayOutputStream(file.length + 64 * 1024);
        out.write(file, 0, moovAt);
        writeU32(out, newMoovSize);
        out.writeBytes("moov".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(moovBody);
        out.writeBytes(trak);
        int audioBytes = 0;
        for (byte[] p : packets) {
            audioBytes += p.length;
        }
        writeU32(out, 8 + audioBytes);
        out.writeBytes("mdat".getBytes(StandardCharsets.US_ASCII));
        for (byte[] p : packets) {
            out.writeBytes(p);
        }
        Path tmp = mp4.resolveSibling(mp4.getFileName() + ".tmp");
        Files.write(tmp, out.toByteArray());
        Files.move(tmp, mp4, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** track_ID из tkhd внутри trak. */
    private static int trackId(byte[] trak) {
        for (int pos = 8; pos + 8 <= trak.length; ) {
            int size = (int) u32(trak, pos);
            if (size < 8) {
                break;
            }
            if (new String(trak, pos + 4, 4, StandardCharsets.US_ASCII).equals("tkhd")) {
                int v = trak[pos + 8];
                return (int) u32(trak, pos + 8 + 4 + (v == 1 ? 16 : 8));
            }
            pos += size;
        }
        return 1;
    }

    private static void patchMvhd(byte[] moovBody, int nextTrackId, long duration) {
        for (int pos = 0; pos + 8 <= moovBody.length; ) {
            int size = (int) u32(moovBody, pos);
            if (new String(moovBody, pos + 4, 4, StandardCharsets.US_ASCII).equals("mvhd")) {
                int v = moovBody[pos + 8];
                int durationAt = pos + 8 + 4 + (v == 1 ? 20 : 12);
                if (v == 1) {
                    ByteBuffer.wrap(moovBody, durationAt, 8).putLong(duration);
                } else {
                    ByteBuffer.wrap(moovBody, durationAt, 4).putInt((int) duration);
                }
                ByteBuffer.wrap(moovBody, pos + size - 4, 4).putInt(nextTrackId);
                return;
            }
            pos += size;
        }
    }

    private static byte[] trak(int trackId, long movieDuration, long samples, List<byte[]> packets, int preSkip, long offset) {
        ByteBuffer tkhd = ByteBuffer.allocate(84);
        tkhd.putInt(0x00000007); // версия 0, включена и в фильме
        tkhd.putInt(0).putInt(0); // создание, изменение
        tkhd.putInt(trackId).putInt(0);
        tkhd.putInt((int) movieDuration);
        tkhd.putLong(0); // reserved
        tkhd.putShort((short) 0).putShort((short) 1); // layer, alternate_group
        tkhd.putShort((short) 0x0100).putShort((short) 0); // громкость 1.0
        int[] matrix = {0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000};
        for (int m : matrix) {
            tkhd.putInt(m);
        }
        tkhd.putInt(0).putInt(0); // ширина, высота

        ByteBuffer mdhd = ByteBuffer.allocate(24);
        mdhd.putInt(0).putInt(0).putInt(0);
        mdhd.putInt(OPUS_RATE).putInt((int) samples);
        mdhd.putShort((short) 0x55C4).putShort((short) 0); // язык «und»

        ByteArrayOutputStream hdlr = new ByteArrayOutputStream();
        writeU32(hdlr, 0);
        writeU32(hdlr, 0);
        hdlr.writeBytes("soun".getBytes(StandardCharsets.US_ASCII));
        writeU32(hdlr, 0);
        writeU32(hdlr, 0);
        writeU32(hdlr, 0);
        hdlr.writeBytes("SoundHandler\0".getBytes(StandardCharsets.US_ASCII));

        ByteBuffer dops = ByteBuffer.allocate(11);
        dops.put((byte) 0).put((byte) 1).putShort((short) preSkip).putInt(OPUS_RATE).putShort((short) 0).put((byte) 0);
        ByteBuffer entry = ByteBuffer.allocate(28);
        entry.put(new byte[6]).putShort((short) 1); // reserved, data_reference_index
        entry.putLong(0); // reserved
        entry.putShort((short) 1).putShort((short) 16); // каналы, разрядность
        entry.putInt(0); // pre_defined, reserved
        entry.putInt(OPUS_RATE << 16);
        byte[] opus = box("Opus", entry.array(), box("dOps", dops.array()));

        ByteBuffer stsdHead = ByteBuffer.allocate(8).putInt(0).putInt(1);
        ByteBuffer stts = ByteBuffer.allocate(16).putInt(0).putInt(1).putInt(packets.size()).putInt(PACKET);
        ByteBuffer stsc = ByteBuffer.allocate(20).putInt(0).putInt(1).putInt(1).putInt(packets.size()).putInt(1);
        ByteBuffer stsz = ByteBuffer.allocate(12 + 4 * packets.size()).putInt(0).putInt(0).putInt(packets.size());
        for (byte[] p : packets) {
            stsz.putInt(p.length);
        }
        ByteBuffer stco = ByteBuffer.allocate(12).putInt(0).putInt(1).putInt((int) offset);
        // Edit list: pre-skip не проигрывается (как велит спецификация Opus в MP4).
        ByteBuffer elst = ByteBuffer.allocate(20).putInt(0).putInt(1).putInt((int) movieDuration).putInt(preSkip).putInt(0x00010000);

        byte[] stbl = box("stbl", box("stsd", stsdHead.array(), opus), box("stts", stts.array()), box("stsc", stsc.array()),
                box("stsz", stsz.array()), box("stco", stco.array()));
        byte[] dinf = box("dinf", box("dref", ByteBuffer.allocate(8).putInt(0).putInt(1).array(),
                box("url ", ByteBuffer.allocate(4).putInt(1).array())));
        byte[] minf = box("minf", box("smhd", new byte[8]), dinf, stbl);
        byte[] mdia = box("mdia", box("mdhd", mdhd.array()), box("hdlr", hdlr.toByteArray()), minf);
        return box("trak", box("tkhd", tkhd.array()), box("edts", box("elst", elst.array())), mdia);
    }

    private static byte[] box(String type, byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int size = 8;
        for (byte[] p : parts) {
            size += p.length;
        }
        writeU32(out, size);
        out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static long u32(byte[] b, int at) {
        return ((b[at] & 0xFFL) << 24) | ((b[at + 1] & 0xFFL) << 16) | ((b[at + 2] & 0xFFL) << 8) | (b[at + 3] & 0xFFL);
    }

    private static void writeU32(ByteArrayOutputStream out, long v) {
        out.write((int) (v >> 24));
        out.write((int) (v >> 16));
        out.write((int) (v >> 8));
        out.write((int) v);
    }

    private Mp4Audio() {
    }
}
