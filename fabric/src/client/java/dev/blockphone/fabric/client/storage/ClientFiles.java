package dev.blockphone.fabric.client.storage;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Пользовательские данные телефона: {@code <папка игры>/blockphone/}
 * (заметки, галерея). Настройки лежат отдельно — в {@code config/blockphone/}.
 */
public final class ClientFiles {
    /** Фоновый поток для чтения/записи файлов и обработки картинок, чтобы игра не подвисала. */
    public static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BlockPhone IO");
        thread.setDaemon(true);
        return thread;
    });

    public static Path root() {
        return FabricLoader.getInstance().getGameDir().resolve("blockphone");
    }

    public static Path gallery() {
        return root().resolve("gallery");
    }

    public static Path notesFile() {
        return root().resolve("notes.json");
    }

    private ClientFiles() {
    }
}
