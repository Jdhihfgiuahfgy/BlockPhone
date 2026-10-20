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
     * Звонок в чате (через группу Simple Voice Chat).
     *
     * @param participants кто в звонке сейчас
     * @param invited      кому звонят (ещё не ответили)
     * @param active       false — звонок завершён (пакет — последний для этого звонка)
     */
    public record CallInfo(long chatId, List<UserInfo> participants, List<UserInfo> invited, long startedAt, boolean active) {
        public static CallInfo read(PacketReader r) {
            return new CallInfo(r.readLong(), r.readList(MessengerData.MAX_GROUP_MEMBERS + 1, UserInfo::read),
                    r.readList(MessengerData.MAX_GROUP_MEMBERS + 1, UserInfo::read), r.readLong(), r.readBoolean());
        }

        public static void write(PacketWriter w, CallInfo c) {
            w.writeLong(c.chatId).writeList(c.participants, UserInfo::write).writeList(c.invited, UserInfo::write)
                    .writeLong(c.startedAt).writeBoolean(c.active);
        }
    }

    private VoiceData() {
    }
}
