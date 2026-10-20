package dev.blockphone.fabric.client.music.audio;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

/**
 * Теги аудиофайла: название, исполнитель, альбом и обложка. MP3 — ID3v2.2/2.3/2.4 и ID3v1, OGG — Vorbis comments
 * (обложка — METADATA_BLOCK_PICTURE). Русские теги в однобайтовой кодировке читаются как Windows-1251.
 */
public final class TrackTags {
    private static final Charset CP1251 = Charset.forName("windows-1251");
    private static final int MAX_TAG = 8 * 1024 * 1024;

    /** Теги; поля могут быть пустыми, cover — null. */
    /** @param lyrics текст песни (USLT / LYRICS, может быть в формате LRC) или {@code null} */
    public record Tags(String title, String artist, String album, byte @Nullable [] cover, @Nullable String lyrics) {
        static final Tags EMPTY = new Tags("", "", "", null, null);
    }

    public static Tags read(Path file, boolean withCover) {
        try {
            return switch (Decoders.extension(file)) {
                case "mp3" -> readMp3(file, withCover);
                case "ogg", "oga" -> readOgg(file, withCover);
                default -> Tags.EMPTY;
            };
        } catch (IOException | RuntimeException e) {
            return Tags.EMPTY;
        }
    }

    // --- ID3 ---

    static Tags readMp3(Path file, boolean withCover) throws IOException {
        Tags v2 = null;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] header = in.readNBytes(10);
            if (header.length == 10 && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
                int size = syncsafe(header, 6);
                if (size > 0 && size <= MAX_TAG) {
                    v2 = parseId3v2(header[3], header[5], in.readNBytes(size), withCover);
                }
            }
        }
        Tags v1 = readId3v1(file);
        if (v2 == null) {
            return v1;
        }
        return new Tags(or(v2.title(), v1.title()), or(v2.artist(), v1.artist()), or(v2.album(), v1.album()), v2.cover(), v2.lyrics());
    }

    static Tags parseId3v2(int version, int flags, byte[] tag, boolean withCover) {
        String title = "";
        String artist = "";
        String album = "";
        byte[] cover = null;
        String lyrics = null;
        int pos = 0;
        if ((flags & 0x40) != 0 && version >= 3 && tag.length >= 4) {
            // Расширенный заголовок — пропустить.
            int ext = version == 4 ? syncsafe(tag, 0) : int32(tag, 0) + 4;
            pos = Math.max(0, Math.min(tag.length, ext));
        }
        boolean v22 = version == 2;
        int headerSize = v22 ? 6 : 10;
        while (pos + headerSize <= tag.length) {
            String id = new String(tag, pos, v22 ? 3 : 4, StandardCharsets.ISO_8859_1);
            if (id.charAt(0) == 0) {
                break; // Заполнитель.
            }
            int size;
            if (v22) {
                size = (tag[pos + 3] & 0xFF) << 16 | (tag[pos + 4] & 0xFF) << 8 | (tag[pos + 5] & 0xFF);
            } else if (version == 4) {
                size = syncsafe(tag, pos + 4);
            } else {
                size = int32(tag, pos + 4);
            }
            int start = pos + headerSize;
            if (size <= 0 || start + size > tag.length) {
                break;
            }
            switch (id) {
                case "TIT2", "TT2" -> title = text(tag, start, size);
                case "TPE1", "TP1" -> artist = text(tag, start, size);
                case "TALB", "TAL" -> album = text(tag, start, size);
                case "USLT", "ULT" -> {
                    if (lyrics == null && size > 4) {
                        // кодировка, язык (3 байта), описание до \0, текст
                        String value = decode(tag, start + 4, size - 4, tag[start]);
                        lyrics = value.substring(value.indexOf('\0') + 1).replace("\uFEFF", "").replace("\0", "").strip();
                    }
                }
                case "APIC", "PIC" -> {
                    if (withCover && cover == null) {
                        cover = picture(tag, start, size, v22);
                    }
                }
                default -> {
                }
            }
            pos = start + size;
        }
        return new Tags(title, artist, album, cover, lyrics);
    }

    /** Текстовый кадр: байт кодировки + строка (возможно, несколько через \0 — берём первую). */
    private static String text(byte[] d, int start, int size) {
        int encoding = d[start];
        String value = decode(d, start + 1, size - 1, encoding);
        int nul = value.indexOf('\0');
        return (nul >= 0 ? value.substring(0, nul) : value).strip();
    }

    private static String decode(byte[] d, int start, int length, int encoding) {
        if (length <= 0) {
            return "";
        }
        return switch (encoding) {
            case 1 -> new String(d, start, length, StandardCharsets.UTF_16);
            case 2 -> new String(d, start, length, StandardCharsets.UTF_16BE);
            case 3 -> new String(d, start, length, StandardCharsets.UTF_8);
            default -> singleByte(d, start, length);
        };
    }

    /** Однобайтовый текст: формально ISO-8859-1, но русские файлы обычно в Windows-1251. */
    private static String singleByte(byte[] d, int start, int length) {
        for (int i = start; i < start + length; i++) {
            if ((d[i] & 0xFF) >= 0xC0) {
                return new String(d, start, length, CP1251);
            }
        }
        return new String(d, start, length, StandardCharsets.ISO_8859_1);
    }

    /** Картинка APIC/PIC: кодировка, MIME (или 3 буквы формата), тип, описание до \0, данные. */
    private static byte @Nullable [] picture(byte[] d, int start, int size, boolean v22) {
        int end = start + size;
        int encoding = d[start];
        int pos = start + 1;
        if (v22) {
            pos += 3;
        } else {
            while (pos < end && d[pos] != 0) {
                pos++;
            }
            pos++;
        }
        pos++; // Тип картинки.
        boolean wide = encoding == 1 || encoding == 2;
        while (pos < end) {
            if (wide) {
                if (pos + 1 < end && d[pos] == 0 && d[pos + 1] == 0) {
                    pos += 2;
                    break;
                }
                pos += 2;
            } else {
                if (d[pos++] == 0) {
                    break;
                }
            }
        }
        if (pos >= end) {
            return null;
        }
        byte[] image = new byte[end - pos];
        System.arraycopy(d, pos, image, 0, image.length);
        return image;
    }

    static Tags readId3v1(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < 128) {
                return Tags.EMPTY;
            }
            byte[] tag = new byte[128];
            raf.seek(raf.length() - 128);
            raf.readFully(tag);
            if (tag[0] != 'T' || tag[1] != 'A' || tag[2] != 'G') {
                return Tags.EMPTY;
            }
            return new Tags(fixed(tag, 3, 30), fixed(tag, 33, 30), fixed(tag, 63, 30), null, null);
        }
    }

    private static String fixed(byte[] d, int start, int length) {
        int end = start;
        while (end < start + length && d[end] != 0) {
            end++;
        }
        return singleByte(d, start, end - start).strip();
    }

    // --- Ogg Vorbis ---

    static Tags readOgg(Path file, boolean withCover) throws IOException {
        byte[] packet = oggCommentPacket(file);
        if (packet == null || packet.length < 7 || packet[0] != 3 || packet[1] != 'v') {
            return Tags.EMPTY;
        }
        int pos = 7;
        int vendor = int32le(packet, pos);
        pos += 4 + vendor;
        int count = int32le(packet, pos);
        pos += 4;
        String title = "";
        String artist = "";
        String album = "";
        byte[] cover = null;
        String lyrics = null;
        for (int i = 0; i < count && pos + 4 <= packet.length; i++) {
            int length = int32le(packet, pos);
            pos += 4;
            if (length < 0 || pos + length > packet.length) {
                break;
            }
            String comment = new String(packet, pos, length, StandardCharsets.UTF_8);
            pos += length;
            int eq = comment.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = comment.substring(0, eq).toUpperCase(Locale.ROOT);
            String value = comment.substring(eq + 1).strip();
            switch (key) {
                case "TITLE" -> title = value;
                case "ARTIST" -> artist = value;
                case "ALBUM" -> album = value;
                case "LYRICS", "UNSYNCEDLYRICS" -> lyrics = value;
                case "METADATA_BLOCK_PICTURE" -> {
                    if (withCover && cover == null) {
                        cover = flacPicture(Base64.getDecoder().decode(value));
                    }
                }
                default -> {
                }
            }
        }
        return new Tags(title, artist, album, cover, lyrics);
    }

    /** Второй пакет Ogg-потока (комментарии), собранный из страниц. */
    private static byte @Nullable [] oggCommentPacket(Path file) throws IOException {
        java.io.ByteArrayOutputStream packet = new java.io.ByteArrayOutputStream();
        int packetIndex = 0;
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file))) {
            while (packet.size() < MAX_TAG) {
                byte[] header = in.readNBytes(27);
                if (header.length < 27 || header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S') {
                    return null;
                }
                int segments = header[26] & 0xFF;
                byte[] table = in.readNBytes(segments);
                for (int i = 0; i < segments; i++) {
                    int length = table[i] & 0xFF;
                    byte[] data = in.readNBytes(length);
                    if (packetIndex == 1) {
                        packet.write(data);
                    }
                    if (length < 255) {
                        if (packetIndex == 1) {
                            return packet.toByteArray();
                        }
                        packetIndex++;
                    }
                }
            }
        }
        return null;
    }

    /** Картинка в формате FLAC PICTURE: тип, MIME, описание, размеры, данные. */
    private static byte @Nullable [] flacPicture(byte[] d) {
        int pos = 4;
        pos += 4 + int32(d, pos);
        pos += 4 + int32(d, pos);
        pos += 16;
        int length = int32(d, pos);
        pos += 4;
        if (length <= 0 || pos + length > d.length) {
            return null;
        }
        byte[] image = new byte[length];
        System.arraycopy(d, pos, image, 0, length);
        return image;
    }

    // --- Числа ---

    private static int syncsafe(byte[] d, int i) {
        return (d[i] & 0x7F) << 21 | (d[i + 1] & 0x7F) << 14 | (d[i + 2] & 0x7F) << 7 | (d[i + 3] & 0x7F);
    }

    private static int int32(byte[] d, int i) {
        return (d[i] & 0xFF) << 24 | (d[i + 1] & 0xFF) << 16 | (d[i + 2] & 0xFF) << 8 | (d[i + 3] & 0xFF);
    }

    private static int int32le(byte[] d, int i) {
        return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8 | (d[i + 2] & 0xFF) << 16 | (d[i + 3] & 0xFF) << 24;
    }

    private static String or(String a, String b) {
        return a.isEmpty() ? b : a;
    }

    private TrackTags() {
    }
}
