package dev.blockphone.common.server;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.protocol.data.ImageRef.PhotoPolicy;
import dev.blockphone.common.protocol.packet.ImagePackets;
import dev.blockphone.common.server.ImageRepository.ImageMeta;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Фото: приём загрузок кусками (с проверкой политики сервера, прав и размера) и выдача картинок
 * (только тем, кому их видно: пост — всем, сообщение — участникам чата). Только из потока {@link Backend}.
 */
final class ImageService {
    /** Загрузка в процессе. */
    private static final class Upload {
        final int id;
        final ImageRef.Purpose purpose;
        final byte[] data;
        int received;

        Upload(int id, ImageRef.Purpose purpose, int size) {
            this.id = id;
            this.purpose = purpose;
            this.data = new byte[size];
        }
    }

    private final Backend backend;
    private final Map<UUID, Upload> uploads = new HashMap<>();
    private final RateLimiter uploadLimit = new RateLimiter(10, 60_000);
    private final RateLimiter requestLimit = new RateLimiter(120, 10_000);

    ImageService(Backend backend) {
        this.backend = backend;
    }

    void forget(UUID player) {
        uploads.remove(player);
        uploadLimit.forget(player);
        requestLimit.forget(player);
    }

    void handle(UUID player, Packet packet) throws SQLException {
        switch (packet) {
            case ImagePackets.UploadBegin p -> begin(player, p);
            case ImagePackets.UploadChunk p -> chunk(player, p);
            case ImagePackets.RequestImage p -> request(player, p.imageId());
            default -> {
            }
        }
    }

    // --- Загрузка ---

    /** Можно ли игроку прикреплять фото (без учёта размера и происхождения). null — можно, иначе ключ ошибки. */
    String checkAllowed(UUID player, ImageRef.Purpose purpose) throws SQLException {
        if (backend.settings().social().photos() == PhotoPolicy.NONE) {
            return "photo.error.disabled";
        }
        if (!backend.has(player, Permission.PHOTO)) {
            return "photo.error.no_permission";
        }
        if (purpose == ImageRef.Purpose.POST && backend.social() != null && backend.social().mutedUntil(player) > 0) {
            return "social.error.muted";
        }
        return null;
    }

    private void begin(UUID player, ImagePackets.UploadBegin p) throws SQLException {
        uploads.remove(player);
        ServerSettings.Social s = backend.settings().social();
        String error = checkAllowed(player, p.purpose());
        if (error == null && (p.size() <= 0 || p.size() > s.maxPhotoBytes())) {
            error = "photo.error.too_big";
        }
        if (error == null && s.photos() == PhotoPolicy.SERVER && !backend.serverId().equals(p.serverId())) {
            error = "photo.error.not_this_server";
        }
        if (error == null && !uploadLimit.allow(player)) {
            error = "photo.error.rate_limit";
        }
        if (error != null) {
            backend.send(player, new ImagePackets.UploadResult(p.uploadId(), null, error));
            return;
        }
        uploads.put(player, new Upload(p.uploadId(), p.purpose(), p.size()));
    }

    private void chunk(UUID player, ImagePackets.UploadChunk p) throws SQLException {
        Upload upload = uploads.get(player);
        if (upload == null || upload.id != p.uploadId()) {
            return;
        }
        byte[] part = p.data();
        if (p.offset() != upload.received || upload.received + part.length > upload.data.length) {
            uploads.remove(player);
            backend.send(player, new ImagePackets.UploadResult(p.uploadId(), null, "photo.error.broken"));
            return;
        }
        System.arraycopy(part, 0, upload.data, upload.received, part.length);
        upload.received += part.length;
        if (upload.received < upload.data.length) {
            return;
        }
        uploads.remove(player);
        ImageHeader.Info info = ImageHeader.read(upload.data);
        int maxSide = backend.settings().social().maxPhotoSide();
        if (info == null || info.width() <= 0 || info.height() <= 0) {
            backend.send(player, new ImagePackets.UploadResult(p.uploadId(), null, "photo.error.broken"));
            return;
        }
        if (Math.max(info.width(), info.height()) > maxSide) {
            backend.send(player, new ImagePackets.UploadResult(p.uploadId(), null, "photo.error.too_big"));
            return;
        }
        long id = backend.imageRepo().insert(player, info.width(), info.height(), upload.data, System.currentTimeMillis());
        backend.send(player, new ImagePackets.UploadResult(p.uploadId(), new ImageRef(id, info.width(), info.height()), ""));
    }

    /**
     * Прикрепить загруженное игроком фото к посту или чату. null — фото нет, оно чужое или уже использовано.
     */
    ImageRef claim(UUID player, long imageId, int kind, long refId) throws SQLException {
        ImageMeta meta = backend.imageRepo().meta(imageId);
        if (meta == null || !meta.owner().equals(player) || meta.kind() != ImageRepository.KIND_UNUSED) {
            return null;
        }
        backend.imageRepo().attach(imageId, kind, refId);
        return meta.ref();
    }

    // --- Выдача ---

    private void request(UUID player, long imageId) throws SQLException {
        if (!requestLimit.allow(player)) {
            return;
        }
        ImageMeta meta = backend.imageRepo().meta(imageId);
        byte[] data = meta != null && canView(player, meta) ? backend.imageRepo().data(imageId) : null;
        if (data == null) {
            backend.send(player, new ImagePackets.ImageData(imageId, 0, 0, new byte[0]));
            return;
        }
        for (int offset = 0; offset < data.length; offset += ImageRef.CHUNK) {
            byte[] part = Arrays.copyOfRange(data, offset, Math.min(data.length, offset + ImageRef.CHUNK));
            backend.send(player, new ImagePackets.ImageData(imageId, offset, data.length, part));
        }
    }

    private boolean canView(UUID player, ImageMeta meta) throws SQLException {
        if (meta.owner().equals(player)) {
            return true;
        }
        return switch (meta.kind()) {
            case ImageRepository.KIND_POST -> backend.social() != null && backend.social().postVisible(meta.refId());
            case ImageRepository.KIND_MESSAGE -> backend.imageRepo().isChatMember(meta.refId(), player);
            default -> false;
        };
    }
}
