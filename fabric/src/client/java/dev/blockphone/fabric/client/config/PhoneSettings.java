package dev.blockphone.fabric.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.blockphone.fabric.BlockPhoneFabric;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Настройки телефона игрока. Хранятся в {@code config/blockphone/settings.json}.
 * Поля публичные — их напрямую меняет приложение «Настройки», после чего вызывается {@link #save()}.
 */
public final class PhoneSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static PhoneSettings instance;

    /** "auto" — как в игре, иначе код языка: "ru_ru", "en_us". */
    public String language = "auto";
    /** Индекс обоев из {@link dev.blockphone.fabric.client.phone.PhoneTheme#WALLPAPERS}. */
    public int wallpaper = 0;
    /** Свои обои: имя файла в {@code blockphone/wallpapers/}; null — используются стандартные. */
    public String customWallpaper = null;
    /** Тёмная тема приложений (по умолчанию). */
    public boolean darkTheme = true;
    /** Акцентный цвет (ARGB): выделения, кнопки, свои сообщения. */
    public int accentColor = 0xFF4C8DF6;
    /** 24-часовой формат часов в строке состояния. */
    public boolean clock24h = true;
    /** Показывать в строке состояния игровое время вместо реального. */
    public boolean gameTimeClock = false;
    /** Анимация выезда телефона. */
    public boolean animations = true;
    /** Громкость музыкального плеера 0..1 (умножается на общую громкость игры). */
    public float musicVolume = 0.7f;
    /** Показывать виджет «сейчас играет» на экране игры. */
    public boolean musicHud = true;
    /** Оформление виджета музыки: card | compact | large | minimal | cover. */
    public String hudStyle = "card";
    /** Угол/край экрана: top_left, top_center, top_right, middle_left, middle_right, bottom_left, bottom_center, bottom_right. */
    public String hudAnchor = "top_left";
    /** Отступ от края, пикселей интерфейса. */
    public int hudOffsetX = 4;
    public int hudOffsetY = 4;
    /** Масштаб, % (50..200). */
    public int hudScale = 100;
    /** Непрозрачность подложки, % (0..100). */
    public int hudOpacity = 88;
    /** Цвет подложки: dark | light | accent. */
    public String hudColor = "dark";
    public boolean hudShowCover = true;
    public boolean hudShowArtist = true;
    public boolean hudShowProgress = true;
    public boolean hudShowTime = false;
    /** Текущая строка текста песни под виджетом. */
    public boolean hudShowLyrics = false;
    /** Если плеер телефона молчит — показывать в виджете, что играет на компьютере (Spotify, браузер…, только Windows). */
    public boolean hudWindowsMedia = true;
    /** Показывать на паузе (иначе — только когда играет). */
    public boolean hudShowPaused = true;
    /** Экран блокировки с уведомлениями при открытии телефона (если он был закрыт дольше 15 секунд). */
    public boolean lockScreen = true;
    /** Размер иконок рабочего стола, % (70..140; от него зависит число столбцов). */
    public int homeIconScale = 100;
    /** Подписи под иконками рабочего стола. */
    public boolean homeLabels = true;
    /** Порядок рабочего стола: id приложений и "folder:<id>" папок. Новые приложения добавляются в конец. */
    public java.util.List<String> homeOrder = new java.util.ArrayList<>();
    /** Папки рабочего стола: id → название и приложения. */
    public java.util.Map<String, HomeFolder> homeFolders = new java.util.LinkedHashMap<>();

    public static final class HomeFolder {
        public String name = "";
        public java.util.List<String> apps = new java.util.ArrayList<>();
    }

    /** Режим «на ходу»: телефон маленький в правом нижнем углу, можно ходить. */
    public boolean compactPhone = false;
    /** Уведомления: все вкл/выкл, всплывающие окошки, центр уведомлений и экран блокировки, звук звонка. */
    /** Источники магазина приложений: ссылки на catalog.json (docs/APP_STORE.md). */
    public java.util.List<String> storeSources = new java.util.ArrayList<>();
    public boolean notifyAll = true;
    /** Прокси Telegram: auto (если напрямую не подключается), custom (всегда свои), off (`telegram/TelegramProxy`). */
    public String telegramProxy = "auto";
    /** Свои прокси Telegram: ссылки tg://proxy?…, https://t.me/proxy?…, socks5://…, http://…. */
    public java.util.List<String> telegramProxies = new java.util.ArrayList<>();
    /** Звук в «кружочках» Telegram: микрофон и играющая музыка. */
    public boolean videoNoteMic = true;
    public boolean videoNoteMusic = true;
    public boolean notifyToasts = true;
    public boolean notifyCenter = true;
    public boolean notifySound = true;
    /** Приложения с выключенными уведомлениями (messenger, feed, telegram, calls). */
    public java.util.Set<String> notifyMuted = new java.util.HashSet<>();
    /** Текст песни в мире (если у трека есть текст). */
    public boolean lyricsWorld = true;
    /** "world" — строка остаётся там, где появилась; "view" — следует за взглядом. */
    public String lyricsMode = "world";
    /** Высота текста «в мире» над поверхностью блока (блоки, 0..3). */
    public float lyricsHeight = 0.25f;
    /** Размер текста 1..4. */
    public int lyricsSize = 2;
    /** Расстояние до текста, блоков. */
    public int lyricsDistance = 6;
    /** Слова разного размера и чуть вразброс. */
    public boolean lyricsScatter = true;
    /** Тёмная обводка букв текста песни в мире. */
    public boolean lyricsOutline = true;
    /** Показывать в мире и текст без времени строк (появляется примерно, может не совпадать с песней). */
    public boolean lyricsUnsynced = false;
    /** Последнее появившееся слово — акцентным цветом. */
    public boolean lyricsAccentWord = true;
    /** Сдвиг текста относительно музыки, мс (плюс — раньше). */
    public int lyricsOffsetMs = 0;
    /** Искать текст в интернете (LRCLIB), если его нет в файлах. */
    public boolean lyricsOnline = true;
    /** Пульт музыки (зажатая клавиша): угол/край как у виджета на HUD, отступы и размер в %. */
    public String remoteAnchor = "bottom_center";
    public int remoteOffsetX = 0;
    public int remoteOffsetY = 48;
    public int remoteScale = 100;
    /** Звуки телефона: вид (notify, ringtone, alarm, timer) → «builtin:имя» или «file:имя» (папка blockphone/sounds). */
    public java.util.Map<String, String> sounds = new java.util.HashMap<>();
    /** Громкость звуков телефона, %. */
    public int soundVolume = 100;
    /** Будильники приложения «Часы». */
    public java.util.List<Alarm> alarms = new java.util.ArrayList<>();

    /** Будильник: время, дни повтора (бит 0 — понедельник … бит 6 — воскресенье; 0 — один раз), подпись. */
    public static final class Alarm {
        public int hour = 7;
        public int minute;
        public boolean enabled = true;
        public int days;
        public String label = "";
    }

    public static PhoneSettings get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("blockphone");
    }

    private static Path file() {
        return dir().resolve("settings.json");
    }

    private static PhoneSettings load() {
        Path file = file();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                PhoneSettings loaded = GSON.fromJson(reader, PhoneSettings.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Failed to read {}, using defaults", file, e);
            }
        }
        return new PhoneSettings();
    }

    public void save() {
        try {
            Files.createDirectories(dir());
            try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            BlockPhoneFabric.LOGGER.warn("Failed to save phone settings", e);
        }
    }
}
