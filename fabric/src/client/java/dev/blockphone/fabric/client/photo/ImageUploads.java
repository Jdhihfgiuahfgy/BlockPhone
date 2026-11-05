package dev.blockphone.fabric.client.photo;

import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.protocol.packet.ImagePackets;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Загрузка фото из галереи на сервер: в фоне уменьшается до предела сервера и сжимается в JPEG,
 * затем отправляется кусками. Ответ сервера — id картинки для поста или сообщения.
 */
public final class ImageUploads {
    public enum State { PREPARING, UPLOADING, DONE, FAILED }

    /** Загрузка одного фото. Поля меняются на потоке рендера. */
    public static final class Upload {
        public final int id;
        public final PhotoStore.Photo photo;
        public final ImageRef.Purpose purpose;
        private final Consumer<Upload> onDone;
        private State state = State.PREPARING;
        private @Nullable ImageRef image;
        private @Nullable String error;

        private Upload(int id, PhotoStore.Photo photo, ImageRef.Purpose purpose, Consumer<Upload> onDone) {
            this.id = id;
            this.photo = photo;
            this.purpose = purpose;
            this.onDone = onDone;
        }

        public State state() {
            return state;
        }

        /** Картинка на сервере (после DONE). */
        public @Nullable ImageRef image() {
            return image;
        }

        /** Ключ перевода ошибки (после FAILED). */
        public @Nullable String error() {
            return error;
        }

        public boolean busy() {
            return state == State.PREPARING || state == State.UPLOADING;
        }
    }

    private static final Map<Integer, Upload> ACTIVE = new HashMap<>();
    private static int counter;

    /** Начать загрузку. onDone вызывается на потоке рендера при успехе и при ошибке. */
    public static Upload start(PhotoStore.Photo photo, ImageRef.Purpose purpose, Consumer<Upload> onDone) {
        Upload upload = new Upload(++counter, photo, purpose, onDone);
        ACTIVE.put(upload.id, upload);
        int maxBytes = ClientSession.maxPhotoBytes();
        int maxSide = ClientSession.maxPhotoSide();
        ClientFiles.IO.execute(() -> {
            byte[] data;
            String serverId;
            try {
                PhotoMeta meta = PhotoStore.readMeta(photo);
                serverId = meta != null && meta.serverId != null ? meta.serverId : "";
                Picture picture = ImageOps.downscale(ImageOps.read(photo.file()), maxSide, maxSide);
                data = encode(picture, maxBytes);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Failed to prepare photo {}", photo.file(), e);
                Minecraft.getInstance().execute(() -> finish(upload, null, "photo.error.read"));
                return;
            }
            Minecraft.getInstance().execute(() -> send(upload, data, serverId));
        });
        return upload;
    }

    /**
     * Загрузить готовый стикер (PNG с прозрачностью). Если файл больше предела сервера — картинка уменьшается.
     * photo — исходное фото (для подписи загрузки).
     */
    public static Upload startSticker(PhotoStore.Photo photo, Picture sticker, int maxBytes, Consumer<Upload> onDone) {
        Upload upload = new Upload(++counter, photo, ImageRef.Purpose.STICKER, onDone);
        ACTIVE.put(upload.id, upload);
        ClientFiles.IO.execute(() -> {
            byte[] data = null;
            try {
                Picture current = sticker;
                for (int attempt = 0; attempt < 8 && current.width() >= 48; attempt++) {
                    byte[] png = png(current);
                    if (png.length <= maxBytes) {
                        data = png;
                        break;
                    }
                    int side = Math.round(current.width() * 0.85f);
                    current = ImageOps.downscale(current, side, side);
                }
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Failed to prepare sticker", e);
                Minecraft.getInstance().execute(() -> finish(upload, null, "photo.error.read"));
                return;
            }
            byte[] result = data;
            Minecraft.getInstance().execute(() -> send(upload, result, ""));
        });
        return upload;
    }

    public static byte[] png(Picture picture) throws IOException {
        BufferedImage image = new BufferedImage(picture.width(), picture.height(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, picture.width(), picture.height(), picture.argb(), 0, picture.width());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static void send(Upload upload, byte[] data, String serverId) {
        if (ACTIVE.get(upload.id) != upload) {
            return; // Отменена.
        }
        if (data == null) {
            finish(upload, null, "photo.error.too_big");
            return;
        }
        upload.state = State.UPLOADING;
        ClientNetwork.send(new ImagePackets.UploadBegin(upload.id, data.length, upload.purpose, serverId));
        for (int offset = 0; offset < data.length; offset += ImageRef.CHUNK) {
            byte[] part = Arrays.copyOfRange(data, offset, Math.min(data.length, offset + ImageRef.CHUNK));
            ClientNetwork.send(new ImagePackets.UploadChunk(upload.id, offset, part));
        }
    }

    /** Ответ сервера. uploadId = -1 — внутренняя ошибка сервера: отменяем все текущие загрузки. */
    public static void onResult(ImagePackets.UploadResult result) {
        if (result.uploadId() == -1) {
            for (Upload upload : ACTIVE.values().toArray(Upload[]::new)) {
                finish(upload, null, result.error());
            }
            return;
        }
        Upload upload = ACTIVE.get(result.uploadId());
        if (upload != null) {
            finish(upload, result.image(), result.image() == null ? result.error() : null);
        }
    }

    private static void finish(Upload upload, @Nullable ImageRef image, @Nullable String error) {
        if (ACTIVE.remove(upload.id) == null) {
            return;
        }
        upload.image = image;
        upload.error = image == null ? (error == null || error.isEmpty() ? "photo.error.internal" : error) : null;
        upload.state = image != null ? State.DONE : State.FAILED;
        upload.onDone.accept(upload);
    }

    /** Отменить (результат будет проигнорирован). */
    public static void cancel(@Nullable Upload upload) {
        if (upload != null) {
            ACTIVE.remove(upload.id);
        }
    }

    public static void reset() {
        ACTIVE.clear();
    }

    /**
     * JPEG не больше maxBytes: качество снижается от 0.88 до 0.45, затем картинка уменьшается.
     * null — не удалось уложиться.
     */
    public static byte @Nullable [] encode(Picture picture, int maxBytes) throws IOException {
        Picture current = picture;
        for (int attempt = 0; attempt < 4; attempt++) {
            BufferedImage image = new BufferedImage(current.width(), current.height(), BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, current.width(), current.height(), current.argb(), 0, current.width());
            for (float quality = 0.88f; quality >= 0.45f; quality -= 0.1f) {
                byte[] data = jpeg(image, quality);
                if (data.length <= maxBytes) {
                    return data;
                }
            }
            current = ImageOps.downscale(current, Math.round(current.width() * 0.75f), Math.round(current.height() * 0.75f));
        }
        return null;
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private ImageUploads() {
    }
}
