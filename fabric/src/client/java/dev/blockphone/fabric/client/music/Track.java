package dev.blockphone.fabric.client.music;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Трек в очереди плеера.
 *
 * @param key        уникальный ключ (путь файла или «yandex:id»)
 * @param durationMs длительность (0 — неизвестна, уточнится при воспроизведении)
 * @param file       файл (локальный трек) или null
 * @param coverUrl   адрес обложки (Яндекс) или null; у локальных обложка берётся из тегов файла
 * @param remoteId   id трека в сервисе (Яндекс: «trackId:albumId»)
 */
public record Track(String key, Source source, String title, String artist, String album, long durationMs,
                    @Nullable Path file, @Nullable String coverUrl, @Nullable String remoteId) {

    public enum Source { LOCAL, YANDEX }

    /** «Исполнитель — Название» для подписей. */
    public String label() {
        return artist.isEmpty() ? title : artist + " — " + title;
    }
}
