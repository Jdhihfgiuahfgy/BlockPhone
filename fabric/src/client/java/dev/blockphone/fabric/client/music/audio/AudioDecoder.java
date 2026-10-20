package dev.blockphone.fabric.client.music.audio;

import java.io.IOException;

/**
 * Декодер аудиофайла в 16-битный PCM (сэмплы каналов чередуются: L R L R ...).
 * Используется только из потока воспроизведения.
 */
public interface AudioDecoder extends AutoCloseable {
    int sampleRate();

    int channels();

    /**
     * Заполнить buffer сэмплами. Возвращает число записанных short (кратно числу каналов), 0 — пока ничего,
     * -1 — конец файла.
     */
    int read(short[] buffer) throws IOException;

    /** Длительность в мс или -1, если неизвестна. */
    long durationMs();

    /**
     * Перемотать на позицию. false — декодер не умеет (тогда плеер открывает файл заново и пропускает сэмплы).
     */
    default boolean seek(long ms) throws IOException {
        return false;
    }

    @Override
    void close();
}
