# BlockPhone — заметки для разработки

Мод-«смартфон» для Minecraft: телефон открывается поверх игры как интерфейс (не предмет).
Клиент — Fabric 1.21.11; сервер — тот же Fabric-мод **или** Paper-плагин 1.21.11.
Пользователь (владелец проекта) — новичок: код пишет Claude, пользователь тестирует в игре.
Общение — на русском. **После каждого изменения дописывать `CHANGELOG.md`.**

## Сборка и запуск

| Команда | Что делает |
|---|---|
| `./gradlew build` | Собирает всё. Jar-ы: `fabric/build/libs/BlockPhone-fabric-*.jar`, `paper/build/libs/BlockPhone-paper-*.jar` |
| `./gradlew :fabric:runClient` | Клиент Minecraft с модом (папка игры — `fabric/run/`) |
| `./gradlew :fabric:runServer` | Выделенный Fabric-сервер с модом |
| `./gradlew :paper:runServer` | Paper-сервер с плагином (папка `paper/run/`) |
| `./gradlew :fabric:genSources` | Декомпилирует Minecraft (имена Mojang) — для сверки API |
| `python tools/gen_icons.py` | Перерисовывает PNG-иконки приложений/погоды |

- Gradle **9.8.0** (wrapper). Loom 1.19 требует **Java 25 для демона Gradle** — он скачивается автоматически
  (`gradle/gradle-daemon-jvm.properties` + foojay в `settings.gradle`). Сам мод компилируется под Java 21.
- Маппинги — **официальные Mojang** (`loom.officialMojangMappings()`), не Yarn.
- Исходники игры после genSources: `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-*/…/*-sources.jar`,
  Fabric API: `.gradle/loom-cache/remapped_mods/remapped/net/fabricmc/fabric-api/**/*-sources.jar`.
- **Автоскриншоты UI (для Claude):** переменная окружения `BLOCKPHONE_DEV_SHOTS=<папка>` + `runClient` →
  в главном меню открывается телефон, снимаются все приложения (светлая тема — EN, тёмная — RU), игра закрывается.
  Паузы между шагами большие: окно игры в фоне рисует мало кадров, иначе снимок отстаёт на шаг.
  Код: `fabric/src/client/.../dev/DevShots.java`.
- Папки `PhoneMod/` и `PhoneModPlugin/` — старые шаблоны IntelliJ, в сборку не входят (в .gitignore).

## Модули

```
common/   чистая Java без Minecraft: протокол, (позже) модели и логика мессенджера, хранилище
fabric/   мод: src/main = общий+серверный код, src/client = клиент (телефон)
paper/    плагин Paper
tools/    скрипты (генерация иконок)
```
Классы `common` копируются внутрь jar-ов fabric и paper (`jar { from(project(':common')...) }`).

Пакет: `dev.blockphone`. Mod id: `blockphone`.

### common (`dev.blockphone.common`)
- `BlockPhone` — константы: `MOD_ID`, `CHANNEL = "blockphone:main"`, `PROTOCOL_VERSION`.
- `protocol/Packets` — реестр пакетов (id ↔ класс). Формат сообщения: `VarInt id` + тело.
- `protocol/PacketWriter`/`PacketReader` — VarInt, long, boolean, String (UTF-8), byte[]; reader бросает `ProtocolException`.
- `protocol/packet/*` — пакеты-record'ы с `read(PacketReader)` и `write(PacketWriter)`.
  - `ServerHelloPacket` (S→C, id 0): protocolVersion, serverPlatform, messengerEnabled, phoneInHandVisible.
  - `ClientHelloPacket` (C→S, id 1): protocolVersion, modVersion.

**Как добавить пакет:** record в `protocol/packet` → `register(<следующий id>, …)` в `Packets` →
обработка в `ClientNetwork.handle` (клиент), `FabricServerHandler` (Fabric-сервер) и `BlockPhonePaper.onPluginMessageReceived` (Paper).
При несовместимом изменении — увеличить `PROTOCOL_VERSION`.

### fabric — общий/серверный код (`fabric/src/main`, `dev.blockphone.fabric`)
- `BlockPhoneFabric` — `ModInitializer`; `LOGGER`, `VERSION`; регистрирует `RawPayload` для S2C и C2S.
- `network/RawPayload` — `CustomPacketPayload` с сырыми байтами канала (тот же формат, что plugin message у Paper).
- `server/FabricServerHandler` — только на выделенном сервере: при входе шлёт `ServerHelloPacket`, принимает `ClientHelloPacket`, хранит игроков с модом.
- `server/FabricServerConfig` — `config/blockphone/server.json` (аналог config.yml Paper).

### fabric — клиент (`fabric/src/client`, `dev.blockphone.fabric.client`)
- `BlockPhoneClient` — клавиша `openPhoneKey` (по умолчанию ↑, категория `key.category.blockphone.main`), открывает `PhoneScreen`.
- `network/ClientNetwork` — приём пакетов; `serverHello()`, `messengerAvailable()`; при выходе с сервера сбрасывает `PhoneState`.
- `config/PhoneSettings` — `config/blockphone/settings.json` (Gson, публичные поля: language, wallpaper, darkTheme, clock24h, gameTimeClock, animations).
- `i18n/PhoneLang` — `tr(key, args...)`: свой выбор языка (настройка телефона: auto/ru_ru/en_us), читает `assets/blockphone/lang/*.json`.
  **Новые строки добавлять в оба файла: `ru_ru.json` и `en_us.json`.**
- `phone/` — каркас телефона:
  - `PhoneLayout` — размеры в виртуальных единицах: корпус 176×356, рамка 8, экран 160×340, статус-бар 14, навбар 12, заголовок 20. Область приложения `APP_W×APP_H` = 160×314.
  - `PhoneScreen` — `Screen` поверх игры (не ставит на паузу). Масштабирует виртуальные координаты под окно, анимация выезда, статус-бар, заголовок с «<», полоска «домой». Навигация: `openApp(id)`, `goHome()`, `back()`, `closePhone()`. ESC = назад.
  - `PhoneApp` — базовый класс приложения: `render`, `mouseClicked/Released/Dragged/Scrolled`, `keyPressed`, `charTyped`, `tick`, `onShown/onHidden`, `onBack`, `capturesKeyboard` (не закрывать телефон клавишей при вводе текста), внешний вид (`hasHeader`, `transparentBackground`, `backgroundColor`, `headerColor`, `lightStatusBar`). Координаты — локальные для области содержимого.
  - `AppRegistry` — список приложений на рабочем столе (id, фабрика, условие видимости). Иконка: `textures/gui/app/<id>.png`.
  - `PhoneState` — экземпляры приложений и текущее приложение между открытиями телефона.
  - `PhoneDraw` — `roundedRect`, `roundCornersMask`, `text` (с масштабом), `centeredText`, `rightText`, `ellipsize`, `texture`, `inside`.
  - `PhoneTheme` — цвета (светлая/тёмная тема), обои `WALLPAPERS`.
  - `PhoneClock` — время (реальное/игровое, 12/24ч) и дата с локалью телефона.
- `apps/` — приложения: `home/HomeApp` (рабочий стол), `PlaceholderApp` (заглушка «скоро»),
  `calculator/` (`Calculator` — логика на BigDecimal, `CalculatorApp` — UI), `weather/WeatherApp`, `settings/SettingsApp`.
- `dev/DevShots` — автоскриншоты (см. выше).

### paper (`dev.blockphone.paper`)
- `BlockPhonePaper` — `JavaPlugin`: канал `blockphone:main` (incoming/outgoing), на `PlayerRegisterChannelEvent` шлёт `ServerHelloPacket`, принимает `ClientHelloPacket`.
- `config.yml`: `messenger.enabled`, `phone-in-hand.visible`.

## Особенности API Minecraft 1.21.11 (Mojang mappings)
- `ResourceLocation` переименован в **`Identifier`** (`Identifier.fromNamespaceAndPath(ns, path)`); у `ResourceKey` — `identifier()`.
- Клавиши: `new KeyMapping(name, InputConstants.Type.KEYSYM, key, KeyMapping.Category)`; категория — `KeyMapping.Category.register(Identifier)`, перевод `key.category.<ns>.<path>`.
- Ввод в `Screen`: `mouseClicked(MouseButtonEvent, boolean doubleClick)`, `mouseReleased(MouseButtonEvent)`,
  `mouseDragged(MouseButtonEvent, dx, dy)`, `mouseScrolled(x, y, scrollX, scrollY)`, `keyPressed(KeyEvent)`, `charTyped(CharacterEvent)`.
  `KeyEvent.key()`, `isEscape()`; `CharacterEvent.codepoint()`; `KeyMapping.matches(KeyEvent)`.
- `GuiGraphics`: **цвета ARGB с альфой обязательно** (0xFFxxxxxx), иначе текст невидим. `pose()` — `Matrix3x2fStack` (`pushMatrix/popMatrix/translate/scale`).
  `enableScissor` учитывает текущую матрицу и пересекается с внешним scissor. `blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, regionW, regionH, texW, texH)`.
- `Screen.renderBackground` вызывается движком отдельно перед `render` (не вызывать вручную).
- HUD-оверлеи — `HudElementRegistry` (Fabric API), не `HudRenderCallback`.
- Сеть Fabric: `PayloadTypeRegistry.playS2C()/playC2S().register(type, codec)`; для больших данных есть `registerLarge(...)`.
  Пакет клиент→сервер в ванилле ограничен ~32 КБ — большие данные (фото) резать на куски.
- Скриншот: `Screenshot.takeScreenshot(mc.getMainRenderTarget(), NativeImage -> ...)`.

## План (этапы)
1. ✅ Каркас: модули, протокол-рукопожатие, экран телефона, рабочий стол, калькулятор, погода, настройки.
2. Заметки, камера (фильтры, зум, ISO/выдержка), галерея (папка мода, редактирование/удаление).
3. Серверный мессенджер: личные и групповые чаты (Fabric + Paper, хранилище SQLite/MySQL), поддержка Velocity/Bungee.
4. Посты, лента, паблики, профили; фото с политикой админа (скриншоты этого сервера / любые / никакие); модерация, права (LuckPerms / fabric-permissions-api).
5. Музыка: локальные файлы, виджет на HUD, Яндекс Музыка (неоф. API), управление Spotify.
6. Simple Voice Chat: голосовые сообщения, групповые звонки.
7. Telegram через TDLib (только Windows), кружочки из кадров игры.

Принятые доп. функции: режим «на ходу» (маленький телефон, можно ходить); телефон в руке виден другим (с выключателем в конфиге сервера, без поломки предмета в руке, работает и на Paper); уведомления/экран блокировки.
Отклонено пока: геометки в чатах, команды для игроков без мода.
Одиночная игра = только клиентские функции (мессенджер шлётся только с выделенного сервера).
