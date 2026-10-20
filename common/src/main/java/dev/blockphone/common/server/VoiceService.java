package dev.blockphone.common.server;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.protocol.data.VoiceData;
import dev.blockphone.common.protocol.data.VoiceData.VoiceRef;
import dev.blockphone.common.protocol.packet.VoicePackets;
import dev.blockphone.common.server.VoiceRepository.VoiceMeta;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Голосовые сообщения: приём загрузок кусками (право, длительность, размер) и выдача участникам чата.
 * Только из потока {@link Backend}.
 */
final class VoiceService {
    /** ADPCM 16 кГц — 8000 байт/с, плюс запас на заголовок. */
    private static final int BYTES_PER_SECOND = 8000;
    private static final long UNUSED_TTL_MS = 24 * 3600_000L;

    private static final class Upload {
        final int id;
        final int codec;
        final int durationMs;
        final byte[] data;
        int received;

        Upload(int id, int codec, int durationMs, int size) {
            this.id = id;
            this.codec = codec;
            this.durationMs = durationMs;
            this.data = new byte[size];
        }
    }

    private final Backend backend;
    private final VoiceRepository repo;
    private final Map<UUID, Upload> uploads = new HashMap<>();
    private final RateLimiter uploadLimit = new RateLimiter(20, 60_000);
    private final RateLimiter requestLimit = new RateLimiter(120, 10_000);

    VoiceService(Backend backend) {
        this.backend = backend;
        this.repo = new VoiceRepository(backend.database());
    }

    VoiceRepository repo() {
        return repo;
    }

    void cleanup() throws SQLException {
        int removed = repo.deleteUnused(System.currentTimeMillis() - UNUSED_TTL_MS);
        if (removed > 0) {
            backend.platform().info("Removed " + removed + " unsent voice messages");
        }
    }

    void forget(UUID player) {
        uploads.remove(player);
        uploadLimit.forget(player);
        requestLimit.forget(player);
    }

    /** Предел длины голосового для игрока (0 — нельзя). */
    int maxSeconds(UUID player) {
        ServerSettings.Voice voice = backend.settings().voice();
        return voice.messages() && backend.messenger() != null && backend.has(player, Permission.VOICE) ? voice.maxSeconds() : 0;
    }

    void handle(UUID player, Packet packet) throws SQLException {
        switch (packet) {
            case VoicePackets.UploadBegin p -> begin(player, p);
            case VoicePackets.UploadChunk p -> chunk(player, p);
            case VoicePackets.RequestVoice p -> request(player, p.voiceId());
            default -> {
            }
        }
    }

    private void begin(UUID player, VoicePackets.UploadBegin p) {
        uploads.remove(player);
        int maxSeconds = maxSeconds(player);
        String error = null;
        if (maxSeconds == 0) {
            error = backend.settings().voice().messages() ? "voice.error.no_permission" : "voice.error.disabled";
        } else if (p.codec() != VoiceData.CODEC_ADPCM16) {
            error = "voice.error.broken";
        } else if (p.durationMs() <= 0 || p.durationMs() > maxSeconds * 1000 + 1000) {
            error = "voice.error.too_long";
        } else if (p.size() <= 0 || p.size() > (maxSeconds + 1) * BYTES_PER_SECOND + 4096) {
            error = "voice.error.too_long";
        } else if (!uploadLimit.allow(player)) {
            error = "voice.error.rate_limit";
        } else {
            try {
                error = backend.quotaError(player, p.size(), "voice.");
            } catch (java.sql.SQLException e) {
                backend.platform().warn("BlockPhone quota check failed", e);
            }
        }
        if (error != null) {
            backend.send(player, new VoicePackets.UploadResult(p.uploadId(), null, error));
            return;
        }
        uploads.put(player, new Upload(p.uploadId(), p.codec(), p.durationMs(), p.size()));
    }

    private void chunk(UUID player, VoicePackets.UploadChunk p) throws SQLException {
        Upload upload = uploads.get(player);
        if (upload == null || upload.id != p.uploadId()) {
            return;
        }
        byte[] part = p.data();
        if (p.offset() != upload.received || upload.received + part.length > upload.data.length) {
            uploads.remove(player);
            backend.send(player, new VoicePackets.UploadResult(p.uploadId(), null, "voice.error.broken"));
            return;
        }
        System.arraycopy(part, 0, upload.data, upload.received, part.length);
        upload.received += part.length;
        if (upload.received < upload.data.length) {
            return;
        }
        uploads.remove(player);
        long id = repo.insert(player, upload.codec, upload.durationMs, upload.data, System.currentTimeMillis());
        backend.send(player, new VoicePackets.UploadResult(p.uploadId(), new VoiceRef(id, upload.durationMs), ""));
    }

    /** Прикрепить загруженное голосовое к чату. null — нет, чужое или уже отправлено. */
    VoiceRef claim(UUID player, long voiceId, long chatId) throws SQLException {
        VoiceMeta meta = repo.meta(voiceId);
        if (meta == null || !meta.owner().equals(player) || meta.chatId() != 0) {
            return null;
        }
        repo.attach(voiceId, chatId);
        return meta.ref();
    }

    private void request(UUID player, long voiceId) throws SQLException {
        if (!requestLimit.allow(player)) {
            return;
        }
        VoiceMeta meta = repo.meta(voiceId);
        boolean allowed = meta != null && (meta.owner().equals(player)
                || (meta.chatId() > 0 && backend.imageRepo().isChatMember(meta.chatId(), player)));
        byte[] data = allowed ? repo.data(voiceId) : null;
        if (data == null) {
            backend.send(player, new VoicePackets.VoiceChunk(voiceId, 0, 0, 0, new byte[0]));
            return;
        }
        for (int offset = 0; offset < data.length; offset += ImageRef.CHUNK) {
            byte[] part = Arrays.copyOfRange(data, offset, Math.min(data.length, offset + ImageRef.CHUNK));
            backend.send(player, new VoicePackets.VoiceChunk(voiceId, meta.codec(), offset, data.length, part));
        }
    }
}
