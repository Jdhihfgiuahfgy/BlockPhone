package dev.blockphone.common.protocol.packet;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;
import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.protocol.data.VoiceData;
import dev.blockphone.common.protocol.data.VoiceData.CallInfo;

import java.util.UUID;
import dev.blockphone.common.protocol.data.VoiceData.VoiceRef;

/**
 * Голосовые сообщения (загрузка и скачивание кусками, как фото) и звонки через группы Simple Voice Chat.
 * <pre>
 * голосовое: UploadBegin → UploadChunk × N → (сервер) UploadResult(voice) → SendMessage(voiceId)
 *            RequestVoice → (сервер) VoiceChunk × N
 * звонок:    StartCall → (всем участникам чата) CallState; AnswerCall / LeaveCall → CallState; конец — CallState(active=false)
 * </pre>
 */
public final class VoicePackets {

    public record UploadBegin(int uploadId, int size, int codec, int durationMs) implements Packet {
        public static UploadBegin read(PacketReader r) {
            return new UploadBegin(r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readVarInt());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(uploadId).writeVarInt(size).writeVarInt(codec).writeVarInt(durationMs);
        }
    }

    public record UploadChunk(int uploadId, int offset, byte[] data) implements Packet {
        public static UploadChunk read(PacketReader r) {
            return new UploadChunk(r.readVarInt(), r.readVarInt(), r.readBytes(ImageRef.CHUNK));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(uploadId).writeVarInt(offset).writeBytes(data);
        }
    }

    /** Сервер → клиент: голосовое принято (voice) или отклонено (voice = null, error — ключ перевода). uploadId = -1 — ошибка сервера. */
    public record UploadResult(int uploadId, VoiceRef voice, String error) implements Packet {
        public static UploadResult read(PacketReader r) {
            return new UploadResult(r.readVarInt(), r.readNullable(VoiceRef::read), r.readString(128));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(uploadId).writeNullable(voice, VoiceRef::write).writeString(error);
        }
    }

    public record RequestVoice(long voiceId) implements Packet {
        public static RequestVoice read(PacketReader r) {
            return new RequestVoice(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(voiceId);
        }
    }

    /** Сервер → клиент: кусок голосового. total = 0 — нет или нет доступа. */
    public record VoiceChunk(long voiceId, int codec, int offset, int total, byte[] data) implements Packet {
        public static VoiceChunk read(PacketReader r) {
            return new VoiceChunk(r.readLong(), r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readBytes(ImageRef.CHUNK));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(voiceId).writeVarInt(codec).writeVarInt(offset).writeVarInt(total).writeBytes(data);
        }
    }

    // ---------- Звонки ----------

    /** Сервер → клиент: состояние звонка в чате. */
    public record CallState(CallInfo call) implements Packet {
        public static CallState read(PacketReader r) {
            return new CallState(CallInfo.read(r));
        }

        @Override
        public void write(PacketWriter w) {
            CallInfo.write(w, call);
        }
    }

    /** Позвонить в чат (или присоединиться к идущему звонку). */
    public record StartCall(long chatId) implements Packet {
        public static StartCall read(PacketReader r) {
            return new StartCall(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId);
        }
    }

    /** Ответить на входящий звонок (accept = false — отклонить). */
    public record AnswerCall(long chatId, boolean accept) implements Packet {
        public static AnswerCall read(PacketReader r) {
            return new AnswerCall(r.readLong(), r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId).writeBoolean(accept);
        }
    }

    public record LeaveCall(long chatId) implements Packet {
        public static LeaveCall read(PacketReader r) {
            return new LeaveCall(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId);
        }
    }

    /** Клиент → сервер: звук своего микрофона в звонке (кадры Opus 16 кГц, {@link VoiceData#CODEC_CALL_OPUS16}). */
    public record CallAudio(long chatId, int seq, byte[] data) implements Packet {
        public static CallAudio read(PacketReader r) {
            return new CallAudio(r.readLong(), r.readVarInt(), r.readBytes(VoiceData.MAX_CALL_AUDIO));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId).writeVarInt(seq).writeBytes(data);
        }
    }

    /**
     * Сервер → клиент: звук звонка. from — чей голос (своё состояние декодера на каждого); at — где в мире звучит
     * (участник с громкой связью; {@link VoiceData#NOBODY} — в телефоне, не из точки мира); codec — {@link VoiceData}.
     */
    public record CallAudioFrom(long chatId, UUID from, UUID at, int seq, int codec, byte[] data) implements Packet {
        public static CallAudioFrom read(PacketReader r) {
            return new CallAudioFrom(r.readLong(), r.readUuid(), r.readUuid(), r.readVarInt(), r.readVarInt(),
                    r.readBytes(VoiceData.MAX_CALL_AUDIO));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId).writeUuid(from).writeUuid(at).writeVarInt(seq).writeVarInt(codec).writeBytes(data);
        }
    }

    /** Клиент → сервер: свой микрофон выключен / громкая связь в звонке. */
    public record CallControl(long chatId, boolean muted, boolean speaker) implements Packet {
        public static CallControl read(PacketReader r) {
            return new CallControl(r.readLong(), r.readBoolean(), r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(chatId).writeBoolean(muted).writeBoolean(speaker);
        }
    }

    private VoicePackets() {
    }
}
