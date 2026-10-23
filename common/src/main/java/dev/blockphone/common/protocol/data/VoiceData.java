package dev.blockphone.common.protocol.data;

import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;

import java.util.List;

/** Голосовые сообщения и звонки: данные в пакетах. */
public final class VoiceData {
    /** Кодек голосовых: IMA ADPCM, 16 кГц, моно (4 бита на отсчёт, ~8 КБ/с). */
    public static final int CODEC_ADPCM16 = 1;
    public static final int SAMPLE_RATE = 16_000;
    /** Абсолютный предел длительности голосового (настройка сервера может быть меньше). */
    public static final int HARD_MAX_SECONDS = 300;
    /** Звук звонка: Opus, 16 кГц, моно, кадры по 20 мс (в пакете — несколько кадров: VarInt длина + байты). */
    public static final int CODEC_CALL_OPUS16 = 1;
    /** Голос игрока рядом с «громкой связью» из Simple Voice Chat: Opus 48 кГц, моно, кадр 20 мс (один кадр в пакете). */
    public static final int CODEC_SVC_OPUS48 = 2;
    /** Предел байт звука в одном пакете звонка. */
    public static final int MAX_CALL_AUDIO = 2000;
    /** «Нет игрока» (в поле at пакета звука — звук не из точки мира). */
    public static final java.util.UUID NOBODY = new java.util.UUID(0, 0);

    /** Голосовое сообщение на сервере: id и длительность (для отрисовки до загрузки). */
    public record VoiceRef(long id, int durationMs) {
        public static VoiceRef read(PacketReader r) {
            return new VoiceRef(r.readLong(), r.readVarInt());
        }

        public static void write(PacketWriter w, VoiceRef v) {
            w.writeLong(v.id).writeVarInt(v.durationMs);
        }
    }

    /**
     * Звонок в чате (голос передаёт сам BlockPhone через сервер).
     *
     * @param participants кто в звонке сейчас
     * @param invited      кому звонят (ещё не ответили)
     * @param active       false — звонок завершён (пакет — последний для этого звонка)
     * @param muted        у кого выключен микрофон
     * @param speaker      у кого громкая связь (игроки рядом слышат звонок, звонок слышит голоса рядом)
     */
    public record CallInfo(long chatId, List<UserInfo> participants, List<UserInfo> invited, long startedAt, boolean active,
                           List<java.util.UUID> muted, List<java.util.UUID> speaker) {
        public CallInfo(long chatId, List<UserInfo> participants, List<UserInfo> invited, long startedAt, boolean active) {
            this(chatId, participants, invited, startedAt, active, List.of(), List.of());
        }

        public static CallInfo read(PacketReader r) {
            return new CallInfo(r.readLong(), r.readList(MessengerData.MAX_GROUP_MEMBERS + 1, UserInfo::read),
                    r.readList(MessengerData.MAX_GROUP_MEMBERS + 1, UserInfo::read), r.readLong(), r.readBoolean(),
                    r.readList(MessengerData.MAX_GROUP_MEMBERS + 1, PacketReader::readUuid),
                    r.readList(MessengerData.MAX_GROUP_MEMBERS + 1, PacketReader::readUuid));
        }

        public static void write(PacketWriter w, CallInfo c) {
            w.writeLong(c.chatId).writeList(c.participants, UserInfo::write).writeList(c.invited, UserInfo::write)
                    .writeLong(c.startedAt).writeBoolean(c.active).writeList(c.muted, PacketWriter::writeUuid)
                    .writeList(c.speaker, PacketWriter::writeUuid);
        }

        public boolean muted(java.util.UUID player) {
            return muted.contains(player);
        }

        public boolean speaker(java.util.UUID player) {
            return speaker.contains(player);
        }
    }

    private VoiceData() {
    }
}
