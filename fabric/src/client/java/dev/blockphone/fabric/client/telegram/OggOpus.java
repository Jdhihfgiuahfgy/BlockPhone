package dev.blockphone.fabric.client.telegram;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Голосовые Telegram — Opus в контейнере OGG (RFC 7845). Кодек — Concentus (Opus на чистой Java), контейнер — свой:
 * разбор страниц и пакетов, запись с CRC. Звук на нашей стороне — моно 16 кГц (как у записи с микрофона).
 * Здесь же «волна» Telegram: 100 значений по 5 бит.
 */
public final class OggOpus {
    public static final int RATE = 16_000;
    /** 20 мс при 16 кГц. */
    private static final int FRAME = 320;

    // --- Чтение ---

    /** OGG Opus → PCM моно 16 кГц. */
    public static short[] decode(byte[] ogg) throws IOException {
        List<byte[]> packets = packets(ogg);
        if (packets.size() < 2 || !startsWith(packets.get(0), "OpusHead")) {
            throw new IOException("Not an Opus file");
        }
        byte[] head = packets.get(0);
        int channels = Math.max(1, head[9] & 0xFF);
        int preSkip = ((head[10] & 0xFF) | (head[11] & 0xFF) << 8) * RATE / 48_000;
        try {
            OpusDecoder decoder = new OpusDecoder(RATE, Math.min(2, channels));
            int ch = Math.min(2, channels);
            short[] frame = new short[RATE * 120 / 1000 * ch];
            short[] out = new short[RATE * 10];
            int length = 0;
            for (int i = 2; i < packets.size(); i++) {
                byte[] packet = packets.get(i);
                if (packet.length == 0) {
                    continue;
                }
                int n = decoder.decode(packet, 0, packet.length, frame, 0, frame.length / ch, false);
                if (length + n > out.length) {
                    out = Arrays.copyOf(out, Math.max(out.length * 2, length + n));
                }
                for (int s = 0; s < n; s++) {
                    out[length++] = ch == 1 ? frame[s] : (short) ((frame[s * 2] + frame[s * 2 + 1]) / 2);
                }
            }
            int from = Math.min(length, preSkip);
            return Arrays.copyOfRange(out, from, length);
        } catch (OpusException e) {
            throw new IOException("Opus: " + e.getMessage(), e);
        }
    }

    /** Пакеты из страниц OGG (первого логического потока). */
    static List<byte[]> packets(byte[] ogg) throws IOException {
        List<byte[]> result = new ArrayList<>();
        ByteArrayOutputStream current = new ByteArrayOutputStream();
        int pos = 0;
        while (pos + 27 <= ogg.length) {
            if (ogg[pos] != 'O' || ogg[pos + 1] != 'g' || ogg[pos + 2] != 'g' || ogg[pos + 3] != 'S') {
                throw new IOException("Broken OGG page at " + pos);
            }
            int segments = ogg[pos + 26] & 0xFF;
            int data = pos + 27 + segments;
            if (data > ogg.length) {
                throw new IOException("Truncated OGG");
            }
            for (int i = 0; i < segments; i++) {
                int len = ogg[pos + 27 + i] & 0xFF;
                if (data + len > ogg.length) {
                    throw new IOException("Truncated OGG");
                }
                current.write(ogg, data, len);
                data += len;
                if (len < 255) {
                    result.add(current.toByteArray());
                    current.reset();
                }
            }
            pos = data;
        }
        return result;
    }

    private static boolean startsWith(byte[] data, String magic) {
        byte[] m = magic.getBytes(StandardCharsets.US_ASCII);
        return data.length >= m.length && Arrays.equals(Arrays.copyOf(data, m.length), m);
    }

    // --- Запись ---

    /** Пакеты Opus по 20 мс и pre-skip (в отсчётах 48 кГц). */
    public record Encoded(List<byte[]> packets, int preSkip) {
    }

    /** PCM моно с частотой rate (8/12/16/24/48 кГц) → пакеты Opus по 20 мс (для дорожки MP4). */
    public static Encoded encodePackets(short[] pcm, int rate, int bitrate, boolean voice) throws IOException {
        try {
            OpusEncoder encoder = new OpusEncoder(rate, 1, voice ? OpusApplication.OPUS_APPLICATION_VOIP : OpusApplication.OPUS_APPLICATION_AUDIO);
            encoder.setBitrate(bitrate);
            encoder.setSignalType(voice ? OpusSignal.OPUS_SIGNAL_VOICE : OpusSignal.OPUS_SIGNAL_AUTO);
            encoder.setComplexity(8);
            int frameSize = rate / 50;
            int frames = Math.max(1, (pcm.length + frameSize - 1) / frameSize);
            short[] frame = new short[frameSize];
            byte[] packet = new byte[4000];
            List<byte[]> packets = new ArrayList<>();
            for (int f = 0; f < frames; f++) {
                Arrays.fill(frame, (short) 0);
                int from = f * frameSize;
                System.arraycopy(pcm, Math.min(from, pcm.length), frame, 0, Math.max(0, Math.min(frameSize, pcm.length - from)));
                int n = encoder.encode(frame, 0, frameSize, packet, 0, packet.length);
                packets.add(Arrays.copyOf(packet, n));
            }
            return new Encoded(packets, encoder.getLookahead() * 48_000 / rate);
        } catch (OpusException e) {
            throw new IOException("Opus: " + e.getMessage(), e);
        }
    }

    /** PCM моно 16 кГц → OGG Opus (голос, ~24 кбит/с, кадры по 20 мс). */
    public static byte[] encode(short[] pcm) throws IOException {
        try {
            OpusEncoder encoder = new OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            encoder.setBitrate(24_000);
            encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
            encoder.setComplexity(8);
            int preSkip = encoder.getLookahead() * 48_000 / RATE;
            OggWriter ogg = new OggWriter();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            head.writeBytes("OpusHead".getBytes(StandardCharsets.US_ASCII));
            head.write(1); // версия
            head.write(1); // каналы
            head.write(preSkip & 0xFF);
            head.write(preSkip >> 8);
            writeIntLE(head, RATE);
            head.write(0); // усиление
            head.write(0);
            head.write(0); // раскладка каналов
            ogg.page(List.of(head.toByteArray()), 0, 2); // начало потока
            ByteArrayOutputStream tags = new ByteArrayOutputStream();
            tags.writeBytes("OpusTags".getBytes(StandardCharsets.US_ASCII));
            byte[] vendor = "BlockPhone".getBytes(StandardCharsets.US_ASCII);
            writeIntLE(tags, vendor.length);
            tags.writeBytes(vendor);
            writeIntLE(tags, 0);
            ogg.page(List.of(tags.toByteArray()), 0, 0);

            int frames = Math.max(1, (pcm.length + FRAME - 1) / FRAME);
            short[] frame = new short[FRAME];
            byte[] packet = new byte[1500];
            List<byte[]> page = new ArrayList<>();
            long granule = preSkip;
            for (int f = 0; f < frames; f++) {
                Arrays.fill(frame, (short) 0);
                int from = f * FRAME;
                System.arraycopy(pcm, Math.min(from, pcm.length), frame, 0, Math.max(0, Math.min(FRAME, pcm.length - from)));
                int n = encoder.encode(frame, 0, FRAME, packet, 0, packet.length);
                page.add(Arrays.copyOf(packet, n));
                granule += FRAME * 48_000L / RATE;
                boolean last = f == frames - 1;
                if (page.size() == 50 || last) { // ~1 с на страницу
                    ogg.page(page, last ? Math.min(granule, preSkip + pcm.length * 3L) : granule, last ? 4 : 0);
                    page = new ArrayList<>();
                }
            }
            return ogg.bytes();
        } catch (OpusException e) {
            throw new IOException("Opus: " + e.getMessage(), e);
        }
    }

    private static void writeIntLE(ByteArrayOutputStream out, int v) {
        out.write(v);
        out.write(v >> 8);
        out.write(v >> 16);
        out.write(v >> 24);
    }

    /** Страницы OGG одного потока с CRC (многочлен 0x04C11DB7, без отражения). */
    private static final class OggWriter {
        private static final int[] CRC = new int[256];

        static {
            for (int i = 0; i < 256; i++) {
                int r = i << 24;
                for (int k = 0; k < 8; k++) {
                    r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
                }
                CRC[i] = r;
            }
        }

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final int serial = (int) System.nanoTime();
        private int sequence;

        void page(List<byte[]> packets, long granule, int flags) {
            ByteArrayOutputStream lacing = new ByteArrayOutputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (byte[] p : packets) {
                int left = p.length;
                while (left >= 255) {
                    lacing.write(255);
                    left -= 255;
                }
                lacing.write(left);
                body.writeBytes(p);
            }
            byte[] lace = lacing.toByteArray();
            byte[] page = new byte[27 + lace.length + body.size()];
            page[0] = 'O';
            page[1] = 'g';
            page[2] = 'g';
            page[3] = 'S';
            page[5] = (byte) flags;
            for (int i = 0; i < 8; i++) {
                page[6 + i] = (byte) (granule >> (8 * i));
            }
            for (int i = 0; i < 4; i++) {
                page[14 + i] = (byte) (serial >> (8 * i));
                page[18 + i] = (byte) (sequence >> (8 * i));
            }
            page[26] = (byte) lace.length;
            System.arraycopy(lace, 0, page, 27, lace.length);
            System.arraycopy(body.toByteArray(), 0, page, 27 + lace.length, body.size());
            int crc = 0;
            for (byte b : page) {
                crc = (crc << 8) ^ CRC[((crc >>> 24) ^ (b & 0xFF)) & 0xFF];
            }
            for (int i = 0; i < 4; i++) {
                page[22 + i] = (byte) (crc >> (8 * i));
            }
            out.writeBytes(page);
            sequence++;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    // --- «Волна» ---

    /** «Волна» Telegram: 100 значений 0..31, упакованы по 5 бит (младшие биты первыми). */
    public static byte[] waveform(short[] pcm) {
        int count = 100;
        int[] peaks = new int[count];
        int max = 1;
        for (int i = 0; i < count; i++) {
            int from = (int) ((long) pcm.length * i / count);
            int to = (int) ((long) pcm.length * (i + 1) / count);
            int peak = 0;
            for (int s = from; s < to; s++) {
                peak = Math.max(peak, Math.abs(pcm[s]));
            }
            peaks[i] = peak;
            max = Math.max(max, peak);
        }
        byte[] packed = new byte[(count * 5 + 7) / 8];
        for (int i = 0; i < count; i++) {
            int v = Math.min(31, peaks[i] * 31 / max);
            for (int b = 0; b < 5; b++) {
                if ((v >> b & 1) != 0) {
                    int bit = i * 5 + b;
                    packed[bit / 8] |= (byte) (1 << (bit % 8));
                }
            }
        }
        return packed;
    }

    /** Распаковать «волну» в значения 0..31. */
    public static int[] unpackWaveform(byte[] packed) {
        if (packed == null) {
            return new int[0];
        }
        int count = packed.length * 8 / 5;
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            int v = 0;
            for (int b = 0; b < 5; b++) {
                int bit = i * 5 + b;
                if ((packed[bit / 8] >> (bit % 8) & 1) != 0) {
                    v |= 1 << b;
                }
            }
            values[i] = v;
        }
        return values;
    }

    private OggOpus() {
    }
}
