package dev.blockphone.common.server;

/**
 * Ограничения загружаемых файлов (задаёт админ; поля открыты — для Gson, Paper заполняет из config.yml).
 * Размер фото — {@code social.maxPhotoKb/maxPhotoSide}, длина голосовых — {@code voice.maxSeconds}; здесь — стикеры и
 * общая квота игрока на всё, что он загружает (фото, стикеры, голосовые).
 */
public final class UploadSettings {
    public Stickers stickers = new Stickers();
    public Quota quota = new Quota();

    public static final class Stickers {
        public boolean enabled = true;
        /** Предел файла стикера, КБ. */
        public int maxKb = 128;
        /** Предел стороны стикера, пикселей (телефон уменьшает сам). */
        public int maxSide = 320;
        /** Своих наборов на игрока. */
        public int maxPacks = 10;
        /** Стикеров в наборе. */
        public int maxPerPack = 30;
    }

    public static final class Quota {
        /** Сколько всего МБ может занимать всё загруженное игроком (0 — без ограничения). */
        public int perPlayerMb = 50;
        /** Сколько загрузок (фото, стикеров, голосовых) в сутки (0 — без ограничения). */
        public int uploadsPerDay = 300;
    }

    public UploadSettings fixNulls() {
        if (stickers == null) {
            stickers = new Stickers();
        }
        if (quota == null) {
            quota = new Quota();
        }
        stickers.maxKb = Math.max(8, Math.min(dev.blockphone.common.protocol.data.ImageRef.HARD_MAX_BYTES / 1024, stickers.maxKb));
        stickers.maxSide = Math.max(64, Math.min(1024, stickers.maxSide));
        stickers.maxPacks = Math.max(0, Math.min(dev.blockphone.common.protocol.data.StickerData.HARD_MAX_PACKS, stickers.maxPacks));
        stickers.maxPerPack = Math.max(1, Math.min(dev.blockphone.common.protocol.data.StickerData.HARD_MAX_PER_PACK, stickers.maxPerPack));
        quota.perPlayerMb = Math.max(0, quota.perPlayerMb);
        quota.uploadsPerDay = Math.max(0, quota.uploadsPerDay);
        return this;
    }
}
