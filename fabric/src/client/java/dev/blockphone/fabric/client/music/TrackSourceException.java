package dev.blockphone.fabric.client.music;

/** Не удалось получить файл трека. Сообщение — ключ перевода (music.error.*). */
public final class TrackSourceException extends Exception {
    public TrackSourceException(String key, Throwable cause) {
        super(key, cause);
    }
}
