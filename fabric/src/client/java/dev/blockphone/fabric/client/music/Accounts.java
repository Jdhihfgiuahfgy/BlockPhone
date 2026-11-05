package dev.blockphone.fabric.client.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.storage.SecretBox;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Входы в сервисы (музыка, Telegram): под Windows — {@code config/blockphone/accounts.dat}, зашифрован ключом Windows
 * ({@link SecretBox}: на другом компьютере не читается); иначе — {@code accounts.json}. Старый открытый accounts.json
 * при первом запуске переносится в зашифрованный и удаляется. Только поток рендера и потоки, которым поля переданы копией.
 */
public final class Accounts {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Accounts instance;

    /** OAuth-токен Яндекс Музыки ("" — не вошли). */
    public String yandexToken = "";
    /** Client ID приложения Spotify, созданного игроком в Spotify for Developers. */
    public String spotifyClientId = "";
    public String spotifyAccessToken = "";
    public String spotifyRefreshToken = "";
    /** Когда истекает spotifyAccessToken (мс). */
    public long spotifyExpiresAt;
    /** Telegram: api_id и api_hash приложения игрока (my.telegram.org → API development tools). */
    public int telegramApiId;
    public String telegramApiHash = "";

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("blockphone").resolve("accounts.json");
    }

    private static Path secureFile() {
        return file().resolveSibling("accounts.dat");
    }

    public static synchronized Accounts get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Accounts load() {
        if (SecretBox.available()) {
            SecretBox.Read read = SecretBox.read(secureFile());
            if (read.data() != null) {
                try {
                    Accounts loaded = GSON.fromJson(new String(read.data(), StandardCharsets.UTF_8), Accounts.class);
                    if (loaded != null) {
                        return loaded;
                    }
                } catch (Exception e) {
                    BlockPhoneFabric.LOGGER.warn("Failed to parse {}", secureFile(), e);
                }
            } else if (read.unreadable()) {
                // Файл с другого компьютера или другого пользователя Windows: входить заново.
                SecretBox.moveAside(secureFile());
            }
        }
        Path file = file();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Accounts loaded = GSON.fromJson(reader, Accounts.class);
                if (loaded != null) {
                    if (SecretBox.available()) {
                        instance = loaded;
                        save(); // перенести в зашифрованный файл, открытый удалить
                    }
                    return loaded;
                }
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Failed to read {}", file, e);
            }
        }
        return new Accounts();
    }

    public static synchronized void save() {
        Path file = file();
        if (SecretBox.available()) {
            try {
                SecretBox.write(secureFile(), GSON.toJson(get()).getBytes(StandardCharsets.UTF_8));
                Files.deleteIfExists(file);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Failed to write {}", secureFile(), e);
            }
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("accounts.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(get(), writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("Failed to write {}", file, e);
        }
    }
}
