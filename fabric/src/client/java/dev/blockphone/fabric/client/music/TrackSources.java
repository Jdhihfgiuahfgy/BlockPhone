package dev.blockphone.fabric.client.music;

import dev.blockphone.fabric.client.music.yandex.YandexMusic;

import java.nio.file.Files;
import java.nio.file.Path;

/** Где взять файл трека: локальный — как есть, из Яндекса — скачать в кэш. Вызывается из потока воспроизведения. */
final class TrackSources {
    static Path resolve(Track track) throws TrackSourceException {
        return switch (track.source()) {
            case LOCAL -> {
                if (track.file() == null || !Files.isRegularFile(track.file())) {
                    throw new TrackSourceException("music.error.file_missing", null);
                }
                yield track.file();
            }
            case YANDEX -> YandexMusic.download(track);
        };
    }

    private TrackSources() {
    }
}
