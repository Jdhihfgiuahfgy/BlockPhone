package dev.blockphone.fabric.client.voice;

/**
 * IMA ADPCM: 16-битный PCM → 4 бита на отсчёт (в 4 раза меньше), качество как у телефона.
 * Формат: 4 байта число отсчётов (big-endian), затем по два отсчёта в байте (младшие 4 бита — первый).
 * Состояние кодера в начале — нулевое, блоков нет (голосовые короткие).
 */
public final class Adpcm {
    private static final int[] INDEX_TABLE = {-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8};
    private static final int[] STEP_TABLE = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060,
            1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
            7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767};

    public static byte[] encode(short[] pcm) {
        byte[] out = new byte[4 + (pcm.length + 1) / 2];
        out[0] = (byte) (pcm.length >>> 24);
        out[1] = (byte) (pcm.length >>> 16);
        out[2] = (byte) (pcm.length >>> 8);
        out[3] = (byte) pcm.length;
        int predictor = 0;
        int index = 0;
        for (int i = 0; i < pcm.length; i++) {
            int step = STEP_TABLE[index];
            int diff = pcm[i] - predictor;
            int code = 0;
            if (diff < 0) {
                code = 8;
                diff = -diff;
            }
            int delta = step >> 3;
            if (diff >= step) {
                code |= 4;
                diff -= step;
                delta += step;
            }
            step >>= 1;
            if (diff >= step) {
                code |= 2;
                diff -= step;
                delta += step;
            }
            step >>= 1;
            if (diff >= step) {
                code |= 1;
                delta += step;
            }
            predictor = clamp((code & 8) != 0 ? predictor - delta : predictor + delta);
            index = Math.max(0, Math.min(88, index + INDEX_TABLE[code]));
            int pos = 4 + i / 2;
            out[pos] = (byte) ((i & 1) == 0 ? code : (out[pos] & 0x0F) | code << 4);
        }
        return out;
    }

    public static short[] decode(byte[] data) {
        if (data.length < 4) {
            return new short[0];
        }
        int count = (data[0] & 0xFF) << 24 | (data[1] & 0xFF) << 16 | (data[2] & 0xFF) << 8 | (data[3] & 0xFF);
        count = Math.max(0, Math.min(count, (data.length - 4) * 2));
        short[] pcm = new short[count];
        int predictor = 0;
        int index = 0;
        for (int i = 0; i < count; i++) {
            int b = data[4 + i / 2] & 0xFF;
            int code = (i & 1) == 0 ? b & 0x0F : b >> 4;
            int step = STEP_TABLE[index];
            int delta = step >> 3;
            if ((code & 4) != 0) {
                delta += step;
            }
            if ((code & 2) != 0) {
                delta += step >> 1;
            }
            if ((code & 1) != 0) {
                delta += step >> 2;
            }
            predictor = clamp((code & 8) != 0 ? predictor - delta : predictor + delta);
            index = Math.max(0, Math.min(88, index + INDEX_TABLE[code]));
            pcm[i] = (short) predictor;
        }
        return pcm;
    }

    /** Пересэмплировать моно-сигнал (линейная интерполяция; при понижении частоты — со сглаживанием окном). */
    public static short[] resample(short[] input, int fromRate, int toRate) {
        if (fromRate == toRate || input.length == 0) {
            return input;
        }
        int outLength = (int) ((long) input.length * toRate / fromRate);
        short[] out = new short[outLength];
        double ratio = fromRate / (double) toRate;
        int window = ratio > 1 ? (int) Math.ceil(ratio) : 1;
        for (int i = 0; i < outLength; i++) {
            double pos = i * ratio;
            if (window > 1) {
                int start = (int) pos;
                long sum = 0;
                int n = 0;
                for (int j = start; j < Math.min(input.length, start + window); j++) {
                    sum += input[j];
                    n++;
                }
                out[i] = (short) (n == 0 ? 0 : sum / n);
            } else {
                int a = (int) pos;
                int b = Math.min(input.length - 1, a + 1);
                double t = pos - a;
                out[i] = (short) Math.round(input[a] * (1 - t) + input[b] * t);
            }
        }
        return out;
    }

    private static int clamp(int v) {
        return Math.max(-32768, Math.min(32767, v));
    }

    private Adpcm() {
    }
}
