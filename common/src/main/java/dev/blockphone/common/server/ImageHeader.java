package dev.blockphone.common.server;

/**
 * Определяет формат и размер картинки по заголовку файла, не декодируя её (так не нужен java.desktop/ImageIO,
 * которого может не быть на прокси). Принимаются только PNG и JPEG.
 */
final class ImageHeader {
    record Info(String format, int width, int height) {
    }

    /** Сведения или null, если это не PNG/JPEG или заголовок битый. */
    static Info read(byte[] d) {
        if (d.length >= 24 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[12] == 'I' && d[13] == 'H' && d[14] == 'D' && d[15] == 'R') {
            return new Info("png", int32(d, 16), int32(d, 20));
        }
        if (d.length >= 4 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            return jpeg(d);
        }
        return null;
    }

    private static Info jpeg(byte[] d) {
        int pos = 2;
        while (pos + 9 < d.length) {
            if ((d[pos] & 0xFF) != 0xFF) {
                return null;
            }
            int marker = d[pos + 1] & 0xFF;
            if (marker == 0xFF) {
                pos++; // Заполнитель.
                continue;
            }
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) {
                pos += 2; // Маркеры без длины.
                continue;
            }
            int length = ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
            if (length < 2) {
                return null;
            }
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                int height = ((d[pos + 5] & 0xFF) << 8) | (d[pos + 6] & 0xFF);
                int width = ((d[pos + 7] & 0xFF) << 8) | (d[pos + 8] & 0xFF);
                return new Info("jpeg", width, height);
            }
            pos += 2 + length;
        }
        return null;
    }

    private static int int32(byte[] d, int i) {
        return (d[i] & 0xFF) << 24 | (d[i + 1] & 0xFF) << 16 | (d[i + 2] & 0xFF) << 8 | (d[i + 3] & 0xFF);
    }

    private ImageHeader() {
    }
}
