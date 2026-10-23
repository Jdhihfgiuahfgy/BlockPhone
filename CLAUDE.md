# BlockPhone — заметки для разработки

Мод-«смартфон» для Minecraft: телефон открывается поверх игры как интерфейс (не предмет).
Клиент — Fabric 1.21.11 и 1.21.10 (две сборки из общих исходников); сервер — тот же Fabric-мод **или** Paper-плагин
(один файл для 1.21.10 и 1.21.11); сеть серверов — плагин Velocity или BungeeCord.
Дизайн — **свой стиль** (по образцу скриншота мода-плеера от пользователя): тёмно-синие панели с тонкой рамкой,
скругление 4, плоские кнопки, акцентный цвет (игрок выбирает сам), тёмная основа по умолчанию + светлая.
Отвергнуты: копия айфона и ванильный стиль Minecraft (последний лежит в `git stash`). Новые экраны — только через
`PhoneTheme`/`PhoneDraw` (никаких зашитых iOS-цветов).
Пользователь (владелец проекта) — новичок: код пишет Claude, пользователь тестирует в игре.
Общение — на русском. **После каждого изменения дописывать `CHANGELOG.md`; после каждого этапа — git-коммит «Stage N: …» (без push).**

## Сборка и запуск

| Команда | Что делает |
|---|---|
| `./gradlew build` | Собирает всё. Jar-ы: `fabric/build/libs/BlockPhone-fabric-<версия>+mc1.21.11.jar`, `fabric-1.21.10/build/libs/BlockPhone-fabric-<версия>+mc1.21.10.jar`, `paper/build/libs/BlockPhone-paper-*.jar` |
| `./gradlew :fabric-1.21.10:runClient` | Клиент Minecraft **1.21.10** с модом (папка `fabric-1.21.10/run/`; DevShots работают так же) |
| `./gradlew :fabric:runClient` | Клиент Minecraft с модом (папка игры — `fabric/run/`) |
| `./gradlew :fabric:runServer` | Выделенный Fabric-сервер с модом |
| `./gradlew :paper:runServer` | Paper-сервер с плагином (папка `paper/run/`; EULA и online-mode — принимает/меняет пользователь) |
| `./gradlew :velocity:build` | Плагин Velocity: `velocity/build/libs/BlockPhone-velocity-*.jar` |
| `./gradlew :bungee:build` | Плагин BungeeCord/Waterfall: `bungee/build/libs/BlockPhone-bungee-*.jar` |
| `./gradlew :common:test` | Тесты сервера: мессенджер, лента, фото, модерация, голосовые и звонки (заглушка SVC) — протокол + логика + SQL на H2 |
| `./gradlew :fabric:test` | Тесты клиента без игры: теги MP3/OGG, ответы Яндекса, названия плееров Windows, декодер OGG, кодек ADPCM, запуск TDLib (Windows), кодирование «кружочка», WEBP, OGG Opus, кадры MP4, дорожка Opus в MP4 |
| `./gradlew :fabric:genSources` | Декомпилирует Minecraft (имена Mojang) — для сверки API |
| `python tools/gen_icons.py` | Символы приложений (белые маски `textures/gui/glyph/`), погода, шум камеры, иконка мода |
| `python tools/make_catalog.py <приложения> <каталог> ["имя"]` | Каталог для «Магазина»: zip каждого приложения, иконки, catalog.json с sha256 (docs/APP_STORE.md) |
| `python tools/gen_doom.py` | Текстуры примера DOOM (`docs/examples/scripts/doom/textures`) |
| `python tools/gen_strike.py` | Графика примера Block Strike (`docs/examples/scripts/blockstrike`: textures, палитра 3D-оружия `vm_palette`, radar из maps.lua, icon) |
| `python tools/gen_strike_sounds.py` | Звуки Block Strike (`sounds/*.wav`, синтез numpy) + `sounds.lua` (длительности) |
| `python tools/gen_filters.py` | Генерирует постэффекты фильтров камеры `post_effect/filter_*.json` (параметры фильтров — только здесь) |

- Gradle **9.8.0** (wrapper). Loom 1.19 требует **Java 25 для демона Gradle** — он скачивается автоматически
  (`gradle/gradle-daemon-jvm.properties` + foojay в `settings.gradle`). Сам мод компилируется под Java 21.
- Маппинги — **официальные Mojang** (`loom.officialMojangMappings()`), не Yarn.
- Исходники игры после genSources: `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-*/…/*-sources.jar`,
  Fabric API: `.gradle/loom-cache/remapped_mods/remapped/net/fabricmc/fabric-api/**/*-sources.jar`.
- **Автоскриншоты UI (для Claude):** переменная окружения `BLOCKPHONE_DEV_SHOTS=<папка>` + `runClient` →
  в главном меню снимаются все приложения (светлая тема — EN, тёмная — RU), затем создаётся/грузится мир
  `blockphone_dev` (`fabric/run/saves/`) и снимаются: игра, телефон, погода, камера (фильтр, зум), фото (обычное и
  длинная выдержка), галерея (просмотр, редактор, сетка), заметки; игра закрывается. Выключает pauseOnLostFocus.
  Паузы между шагами большие: окно игры в фоне рисует мало кадров, иначе снимок отстаёт на шаг.
  Код: `fabric/src/client/.../dev/DevShots.java`. Запуск (bash): `BLOCKPHONE_DEV_SHOTS=<папка> ./gradlew :fabric:runClient`.
  Сценарий мессенджера: `BLOCKPHONE_DEV_SCENARIO=messenger BLOCKPHONE_DEV_MESSENGER=1` (+ SHOTS) — мессенджер запускается на
  встроенном сервере одиночной игры, создаются тестовые Steve/Alex, личный чат и группа «Строители», снимаются уведомление,
  значок, список, переписка, отправка, группа, инфо, новый чат, тёмная тема.
  Сценарий ленты: `BLOCKPHONE_DEV_SCENARIO=social BLOCKPHONE_DEV_MESSENGER=1` — паблик «Новости сервера», посты, лайки,
  комментарии и жалоба прямо в базе; снимок камерой (с id сервера) → пост с фото через настоящую загрузку; снимаются лента,
  пост, «Новый пост», стикеры (набор, редактор `sticker_editor`, `stickers_mine`, панель `sticker_panel`, `sticker_sent`),
  профили (Стив — фигура «машет»), «Оформление» (`profile_look`), своя страница после сохранения
  (`profile_self_look`), «Приватность» (`privacy`), паблики, модерация, фото в чате, тёмная тема. Dev-игрок каждый запуск новый (случайный ник).
  Сценарий музыки: `BLOCKPHONE_DEV_SCENARIO=music` — копирует 3 трека Minecraft (из ресурсов игры) в `blockphone/music`,
  играет их на громкости 5% (звук будет!), снимает список, плеер, перемотку, вкладки Яндекс/«На ПК», тёмную тему и виджет на HUD;
  в лог пишет позицию воспроизведения. Пишет рядом с треками придуманный тестовый `.lrc` и снимает текст в телефоне
  (`music_lyrics`), в мире (`music_lyrics_world`) и «перед глазами» (`music_lyrics_view`); поиск в сети на время выключен.
  Затем пять оформлений виджета в разных местах (`hud_<стиль>`) и страница настроек виджета (`settings_hud`).
  Сценарий голоса: `BLOCKPHONE_DEV_SCENARIO=voice BLOCKPHONE_DEV_MESSENGER=1` — голосовое из тихого тестового сигнала
  (микрофон не трогается) → загрузка → пузырь → проигрывание; интерфейс звонка на поддельных состояниях (входящий, полоса, HUD);
  настоящий звонок в группу через SVC (в dev голосовой чат SVC выключен до первоначальной настройки — сервер отвечает ошибкой).
  В среде разработки подключён сам мод Simple Voice Chat (`modLocalRuntime`, версия `voicechat_mod_version`).
  Сценарий Telegram: `BLOCKPHONE_DEV_SCENARIO=telegram` — экран ключей, запуск TDLib с ненастоящими ключами до шага «номер
  телефона», запись «кружочка» из мира (3 с, микрофон выключен) и проверка файла MP4 (в логе); настоящие ключи из accounts.json
  восстанавливаются. Затем поддельный чат (`Telegram.debugInject`): `telegram_chat`, `_menu`, `_reply`, `_stickers`, `_gifs`;
  `fabric/run/blockphone/dev_sticker.webp` (если есть) — WEBP-стикер; в лог — проход Opus внутри игры.
  Сценарий снимает только свою часть: общие снимки (все приложения в меню, мир, «на ходу») — только `all`/`design`/`hand`.
  Telegram дополнительно: `telegram_list`, `telegram_viewer`, `telegram_profile`.
  Сценарий `store` — только магазин (каталог-пример, полоса хода, выдуманные отзывы `AppStore.debugFakeReviews`).
  Сценарий `strike`: копирует пример `blockstrike`, заставка → «Игра с ботами» → покупка → бой → пауза (`strike_*`);
  кладёт в копию `dev.lua` (клавиша «0» выдаёт оружие по очереди) — снимки 3D-оружия `strike_w_<оружие>`, `strike_reload`.
  Логику Block Strike можно гонять без игры: LuaJ-jar из кэша Gradle, `java -cp luaj-jse.jar lua <скрипт>` в папке примера
  (`require("sim")` — матч ботов; main.lua — только с заглушками gfx/g3d/ui/net).
  Сценарий дизайна: `BLOCKPHONE_DEV_SCENARIO=design` — все приложения в тёмной (RU) и светлой (EN) теме, другой акцент
  (оранжевый), страницы «Акцентный цвет» и «Обои», без загрузки мира.
- `BLOCKPHONE_DEV_MESSENGER=1` — включить серверную часть (мессенджер + лента) в одиночной игре (только для разработки;
  база в `fabric/run/blockphone/`). Dev-игрок в мире с читами — оператор, поэтому у него права модератора.
- **Данные игрока** (dev: `fabric/run/`): настройки `config/blockphone/settings.json`; заметки `blockphone/notes.json`;
  галерея `blockphone/gallery/IMG_*.png` + `IMG_*.json` (сведения о снимке, `PhotoMeta`); свои обои `blockphone/wallpapers/`;
  кэш фото с серверов `blockphone/cache/<id сервера>/<id>.img`; своя музыка `blockphone/music/` (MP3/OGG/WAV, подпапки),
  кэш треков Яндекса `blockphone/cache/music/` (последние 40); входы в сервисы `config/blockphone/accounts.json` (секреты!);
  Telegram: база и файлы TDLib `blockphone/telegram/db`, `/files`, записанные «кружочки» `blockphone/telegram/videonotes/`.
- Длинные heredoc в Bash-инструменте иногда ломаются, а `\n` внутри heredoc превращается в настоящий перевод строки —
  правки больших текстов делать скриптом, записанным через Write, или Edit.
- Папки `PhoneMod/` и `PhoneModPlugin/` — старые шаблоны IntelliJ, в сборку не входят (в .gitignore).

## Модули

```
fabric-1.21.10/  тот же мод для Minecraft 1.21.10: своих исходников почти нет (см. «Две версии Minecraft»)
common/    чистая Java без Minecraft: протокол, серверная логика (рукопожатие, мессенджер, лента, фото, SQL), тесты
fabric/    мод: src/main = общий+серверный код, src/client = клиент (телефон)
paper/     плагин Paper
velocity/  плагин Velocity (мессенджер и лента на прокси для всей сети)
bungee/    плагин BungeeCord/Waterfall (то же, что velocity)
tools/     скрипты (генерация иконок и фильтров)
```
Классы `common` копируются внутрь jar-ов fabric, paper, velocity и bungee (`jar { from(project(':common')...) }`).
JDBC-драйверы H2 и MariaDB: в Fabric — jar-in-jar (`include`, без транзитивных), в Velocity и BungeeCord — встраиваются (`shade`),
Paper скачивает их сам по `libraries:` в plugin.yml. Версии — в `gradle.properties` (`h2_version`, `mariadb_version`).
Декодер MP3 JLayer (`javazoom:jlayer`, LGPL) — jar-in-jar в Fabric (`jlayer_version`); OGG — stb_vorbis из LWJGL (есть в игре).
Тестовый source set `fabric/test` видит клиентский код (настроено в `fabric/build.gradle`).
Telegram: TDLight (`it.tdlight:tdlight-java` + `tdlight-api` + `tdlight-natives:windows_amd64`, репозиторий mvn.mchv.eu, LGPL-3.0;
TDLib — Boost) и JCodec (`org.jcodec:jcodec`, BSD) — jar-in-jar; мод из-за нативной библиотеки весит ~25 МБ.
Стикеры WEBP — TwelveMonkeys ImageIO (`imageio-webp` + core/metadata/common-*, BSD; плагин регистрируется вручную в `ImageOps`),
голосовые и звук «кружочков» — Concentus (`io.github.jaredmdobson:concentus`, Opus на чистой Java, BSD), скрипты своих
приложений — LuaJ (`org.luaj:luaj-jse`, MIT, `luaj_version`); jar-in-jar в обоих
модулях fabric (список зависимостей у `fabric-1.21.10/build.gradle` свой — новые библиотеки добавлять в оба).
Версии — `tdlight_*_version`, `jcodec_version`, `twelvemonkeys_version`, `concentus_version` в gradle.properties. Обновлять TDLight целиком (java, api и natives из одного BOM).
API Simple Voice Chat (`de.maxhenkel.voicechat:voicechat-api`, репозиторий maven.maxhenkel.de) — `compileOnly` в common, fabric и paper;
сам SVC ставит игрок/админ. Классы с типами SVC (`SvcPlugin`, `SvcBridge`, `PaperVoicechat`) загружаются только при наличии SVC.
**При смене версии мода:** `mod_version` в gradle.properties и `version` в `@Plugin` (BlockPhoneVelocity); bungee.yml берёт версию сам.

Пакет: `dev.blockphone`. Mod id: `blockphone`.

### common (`dev.blockphone.common`)
- `BlockPhone` — константы: `MOD_ID`, `CHANNEL = "blockphone:main"`, `PROTOCOL_VERSION`.
- `protocol/Packets` — реестр пакетов (id ↔ класс). Формат сообщения: `VarInt id` + тело.
- `protocol/PacketWriter`/`PacketReader` — VarInt, int, long, boolean, String (UTF-8), byte[], enum (`writeEnum/readEnum`);
  reader бросает `ProtocolException`.
- `protocol/packet/*` — пакеты-record'ы с `read(PacketReader)` и `write(PacketWriter)`.
  - `ServerHelloPacket` (S→C, id 0): protocolVersion, serverPlatform, messengerEnabled, phoneInHandVisible, socialEnabled.
  - `ClientHelloPacket` (C→S, id 1): protocolVersion, modVersion.
  - `MessengerPackets.*` (id 2–22): S→C ChatList, ChatUpdate, ChatRemoved, History, NewMessage, MessageUpdated, UserList,
    ChatOpened, TypingState, Presence, Failure(ключ messenger.error.*); C→S RequestHistory, SendMessage(nonce), EditMessage,
    DeleteMessage, MarkRead, SearchUsers, OpenDirect, CreateGroup, GroupAction(RENAME/ADD/REMOVE/LEAVE), Typing.
    SendMessage несёт imageId и voiceId (0 — нет), MessageInfo — `ImageRef image` и `VoiceRef voice` (null — нет).
  - `SessionPacket` (S→C, id 23; C→S `Refresh` id 24): serverId (постоянный, из таблицы meta), socialEnabled, photoPolicy,
    maxPhotoBytes, maxPhotoSide, rights (битовая маска `SocialData.Rights`), mutedUntil, openReports, maxVoiceSeconds (0 — голосовых нет),
    callsAvailable. Приходит после рукопожатия.
  - `StickerPackets` (id 87–93, обрабатывает `StickerService`): C→S RequestMine, RequestPack, EditPack(CREATE/RENAME/DELETE/
    INSTALL/UNINSTALL), AddSticker(pack, imageId — загрузка с Purpose STICKER), RemoveSticker; S→C Mine(Limits, packs), Pack.
    `SendMessage.stickerId`, `MessageInfo.sticker` (StickerRef: id, packId (0 — набор удалён), картинка). Ошибки — sticker.error.*.
  - `AppPackets` (id 94–97): C→S `Relay`(app, target — ник или «» всем, payload ≤ 8 КБ) → S→C `Relayed`(app, from, fromName,
    payload) — `AppService.relay`: пересылка игрокам с модом, 40 за 5 с, приложение, запрещённое по id, не пересылается;
    C→S `RequestPlayers` → S→C `Players` (ники с BlockPhone, кроме себя). Сервер содержимое не разбирает.
  - `AppPackets` (id 82–86): S→C `Policy`(mode, blacklist, whitelist, serverApps — у каких приложений есть серверная часть;
    после рукопожатия и при изменении), C→S `RequestVariables`(app, page) → S→C `Variables`, C→S `Call`(app, function, page,
    inputs) → S→C `CallResult`(toast, error, navigate, values). Телефон не сообщает серверу список своих приложений.
  - `PrivacyPackets` (id 77–80, обрабатывает `PrivacyService`): C→S Request, Update(Privacy), ListAction(user, blockList, add);
    S→C State(privacy, allowed, blocked). `SocialPackets.UpdateLook` (id 81): avatarType, pose, background, bannerStyle,
    bannerImageId (0 — без фото, −1 — оставить, иначе — загруженное фото, Purpose POST). ProfileInfo несёт `ProfileLook look`,
    `canMessage` (можно ли получателю писать этому игроку) и `blocked` (получатель его заблокировал).
  - `PhoneUsePackets` (id 75–76): C→S `InUse(using)` (открыл/закрыл телефон или камеру), S→C `State(player, using)`.
    Обрабатывает сам `BlockPhoneServer` (без базы): набор «с телефоном», рассылка игрокам с модом, новому — текущий набор,
    при выходе — снятие. Только при `phoneInHandVisible`.
  - `VoicePackets.*` (id 66–74): голосовые — UploadBegin(uploadId, size, codec, durationMs) → UploadChunk × N → UploadResult(VoiceRef |
    voice.error.*), RequestVoice → VoiceChunk × N; звонки — S→C CallState(CallInfo: участники, кому звонят, начало, active),
    C→S StartCall (позвонить или войти), AnswerCall(accept), LeaveCall. Ошибки звонков — `MessengerPackets.Failure` (call.error.*).
  - `ImagePackets.*` (id 25–29): UploadBegin(uploadId, size, purpose POST/CHAT, serverId снимка) → UploadChunk × N (по 30 000 байт)
    → UploadResult(ImageRef | ключ ошибки photo.error.*); RequestImage → ImageData × N (total = 0 — нет доступа).
  - `SocialPackets.*` (id 30–65): S→C FeedPage, PostUpdated(post, nonce), PostRemoved, Comments, CommentAdded/Removed, Profile,
    PublicData, PublicRemoved, PublicList, UserList, SearchResults, Reports, Notice(LIKE/COMMENT/FOLLOW/NEW_POST/REPORT),
    Failure(social.error.*), Done(social.done.*); C→S RequestFeed(FOLLOWING/ALL/USER/PUBLIC, beforeId), CreatePost(nonce, publicId,
    text, imageId), DeletePost, LikePost, RequestComments, AddComment, DeleteComment, RequestProfile, UpdateBio, Follow,
    RequestFollows, RequestPublic, CreatePublic, PublicAction(EDIT/SUBSCRIBE/UNSUBSCRIBE/ADD_ADMIN/REMOVE_ADMIN/DELETE),
    RequestPublics(SUBSCRIBED/MANAGED/POPULAR), Search, Report, RequestReports, ResolveReport(DISMISS/DELETE/DELETE_AND_MUTE), Mute.
- `protocol/data/MessengerData` — UserInfo, MessageInfo, ChatInfo, ChatType и ограничения (1000 символов, 32 на название,
  50 участников, страница истории 40).
- `protocol/data/StickerData` — StickerRef, PackInfo (own, installed), Limits (enabled, maxBytes, maxSide, maxPacks, maxPerPack).
- `protocol/data/PrivacyData` — `Audience` (EVERYONE, FOLLOWING — на кого подписан владелец, MUTUAL, NOBODY), `Kind` (MESSAGES,
  VOICE, PHOTOS, INVITES, CALLS), `Privacy` (по Audience на каждый Kind; `with`, `get`), MAX_LIST 200.
  `SocialData.ProfileLook` — avatarType (0 — голова, 1 — фигура), pose (0..7), background (0..11), bannerStyle (0..9), bannerImage.
- `protocol/data/VoiceData` — `VoiceRef` (id, длительность), `CallInfo`, кодек `CODEC_ADPCM16` (IMA ADPCM 16 кГц моно, ~8 КБ/с).
- `protocol/data/ImageRef` — фото на сервере (id, ширина, высота), `PhotoPolicy` (ANY/SERVER/NONE), `Purpose` (POST/CHAT), CHUNK.
- `protocol/data/SocialData` — PostInfo, CommentInfo, ProfileInfo, PublicInfo (role получателя), PublicRef, ReportInfo, перечисления
  FeedKind/Role/ReportTarget/NoticeType, `Rights` (биты прав), лимиты (пост 2000, комментарий 500, «о себе» 200, паблик 32/300,
  страница ленты 12, комментариев 30, 10 админов), `PUBLIC_COLORS`.
- `PacketWriter/Reader` также: UUID, списки (`writeList/readList` с лимитом), `writeNullable/readNullable`, `readString(max)`.
- `apps/` — свои приложения (инструкция для авторов — **docs/CUSTOM_APPS.md**, копируется в jar и в папки apps):
  - `AppLoader` → `AppDefinition` — клиентское приложение (папка с app.json + icon.png или `<имя>.json`): id, name (перевод),
    permissions (`AppPermission`: storage, links, clipboard, commands, server — действие без разрешения = ошибка загрузки),
    pages, действия (у элемента "_a" = «страница:n»), поля ввода, sha256 файла. `AppText` — переводы `{"ru_ru":…}` и
    подстановки `{имя}` / `{имя|по умолчанию}`. `InputRules` — проверка полей (text/number/player, required, min, max).
  - `ServerPart` — серверная часть (`<id>.json` или `<id>/server.json` в папке apps сервера): permission, variables (шаблоны
    с {player}, {uuid}, {online}, %placeholderapi%), functions (inputs — проверка на сервере, command(s), as player|console,
    toast, error, then). `BlockPhoneApps` — Java-API: `handler(appId, AppHandler{variables, call})`, `register(json)`.
  - `AppScript` (AppDefinition.script, null — без скрипта): app.json `"script"` (главный .lua), все `*.lua` и `*.png` папки
    (пределы MAX_*), `orientation` (landscape), `fullscreen`, `hosts` (`hostAllowed`, «*.домен»); sha256 приложения со скриптом
    включает код. Разрешения `INTERNET` (нужны hosts) и `MULTIPLAYER`. `AppLoader.parse(text, icon, id, folder)`.
  - `AppsSettings` — политика сервера: mode blacklist|whitelist|none, списки «uid:…» (уникальный id приложения из
    app.json `uid` — создаётся при первой публикации, `AppDefinition.newUid`), id («id:…» или просто) или «sha256:…»;
    `allowed(...)` — общая для клиента и сервера. `AppDefinition.category` — категория магазина (`CATEGORIES`). `AppResult` — итог функции (toast, error, navigate).
- `server/` — серверная логика для всех платформ:
  - `BlockPhoneServer` — фасад: `onChannelReady(uuid)` → ServerHello; `onMessage(uuid, name, bytes)` (ClientHello → `Backend.onJoin`,
    остальное → `Backend.handle`); `onQuit`. Платформа реализует `ServerPlatform` (name, send(uuid, bytes) — потокобезопасно,
    `hasPermission(uuid, Permission)`, info/warn).
  - `Backend` — один фоновый поток «BlockPhone Server» + одно подключение к базе для мессенджера, ленты и фото; игроки онлайн;
    serverId (таблица meta); права `has(uuid, Permission)` (узел, плюс `blockphone.moderator`, плюс список `moderators` в настройках),
    `rights()` → маска для клиента; `sendSession`. Пакеты раскладывает по группе (`getEnclosingClass`): MessengerPackets → мессенджер,
    SocialPackets → лента, ImagePackets → фото. Ошибка в обработчике → Failure соответствующей группы. `execute` — для тестов/отладки.
  - `Permission` — узлы: `blockphone.post`, `.photo`, `.public.create`, `.voice`, `.call` (у всех); `blockphone.moderate.posts/.publics/.mute/.reports`
    и `blockphone.moderator` (все сразу) — по умолчанию у операторов.
  - `ServerSettings` (+ `Storage`: h2 | mysql; `Social`: enabled, photos any|server|none, maxPhotoBytes, maxPhotoSide,
    maxPublicsPerPlayer; `moderators` — ники/UUID; `Voice`: messages, maxSeconds, calls, callGroupType). Короткие конструкторы — для тестов.
  - `Database` — одно JDBC-соединение, драйвер создаётся напрямую (без DriverManager); SQL в диалекте MySQL, H2 в `MODE=MySQL`.
    Таблицы `<prefix>users/chats/members/messages(+image_id)/meta/images(MEDIUMBLOB)/profiles/follows/publics/public_admins/
    subscriptions/posts/likes/comments/reports/mutes/modlog`. Новые колонки — `column()` (ADD COLUMN, «уже есть» игнорируется).
  - `UserRepository` (users), `ImageRepository` (фото: kind 0 — не прикреплено (удаляется через сутки), 1 — пост, 2 — сообщение),
    `MessengerRepository`, `SocialRepository` (помощники `list/one/update/insert` с параметрами-объектами).
  - `MessengerService` — чаты, лимит 8 сообщений/10 с, личные (direct_key), группы, непрочитанные, поиск, «печатает», присутствие,
    фото в сообщениях. `runInThread`/`injectMessage` — для отладки.
  - `SocialService` — лента (подписки: свои посты + на кого подписан + паблики), посты (лимит 5/мин), лайки, комментарии (10/мин;
    открытый пост «смотрят» — им приходят новые комментарии), профили и подписки, паблики (владелец, до 10 админов, уникальное
    название, лимит на игрока), жалобы (5 за 10 мин) и модерация (удаление, заглушение, журнал modlog + лог сервера).
  - `ImageService` — приём загрузок (политика сервера, право PHOTO, заглушение для постов, размер, 10/мин; формат и размер по
    заголовку — `ImageHeader`, без ImageIO), `claim` (прикрепить), выдача кусками с проверкой доступа (пост — всем, сообщение — участникам).
  - `VoiceService` + `VoiceRepository` (таблица voices, chat_id = 0 — не отправлено, удаляется через сутки) — приём голосовых
    (право VOICE, длительность ≤ maxSeconds, размер), `claim` к чату, выдача только участникам чата.
  - `CallService` — свои звонки (без SVC): звонящий сразу в звонке, остальным участникам чата онлайн приходит вызов; не
    ответили за 45 с — вызов снят; остался один и никто не ждёт — звонок завершён (тик 5 с). Звук: C→S `CallAudio`
    (кадры Opus 16 кГц с длиной-байтом) → S→C `CallAudioFrom(from, at, codec)` остальным участникам; `CallControl` — свой
    микрофон выключен (звук не пересылается) / громкая связь: игрокам с модом в `voice.speakerRadius` от участника — его
    звонок (`at` = участник), а голоса из SVC (`VoiceBridges` микрофон-слушатель, `CODEC_SVC_OPUS48`) рядом с ним — в
    звонок. Позиции — `ServerPlatform.positions()` снимком раз в 0,5 с. Лимит 80 пакетов / 2 с.
  - `RateLimiter` — «не больше N за окно» по игроку.
  - `AppService` (в `BlockPhoneServer`, без базы) — серверные части из папки apps (`setAppsDirectory` до start; проверка папки
    каждые 5 с, README.txt + CUSTOM_APPS.md при создании) и из `BlockPhoneApps`; политика `settings.apps()`; переменные и
    функции — в главном потоке (`platform.runOnMain`), лимит 12 вызовов / 6 с, команды пишутся в лог `[apps]`.
    `ServerPlatform`: `hasPermissionNode(uuid, node, default)`, `runCommand(uuid, command, console)`, `placeholders(uuid, text)`
    (PlaceholderAPI на Paper через отражение; softdepend).
  - `UploadSettings` (POJO; `uploads` в настройках) — стикеры (enabled, maxKb, maxSide, maxPacks, maxPerPack) и квота игрока
    (perPlayerMb — всё загруженное: images + voices по owner; uploadsPerDay). `Backend.quotaError(player, size, "photo."|"voice.")`
    → «photo.error.quota|50» / «…daily_limit|300» (телефон переводит «ключ|аргумент» в `PhoneLang.tr`).
  - `StickerService` — таблицы sticker_packs, stickers, sticker_installs; картинки — images.kind 4 (KIND_STICKER, видны всем);
    messages.sticker_id; своё право `blockphone.stickers`. Удалённый набор пропадает у всех, отправленные стикеры остаются.
    В личке стикер считается фото (приватность «фото»).
  - `PrivacyService` — таблицы `privacy` и `privacy_lists` (kind 0 — «всегда можно», 1 — заблокирован; игрок только в одном списке);
    `allows(owner, other, Kind)`: списки важнее настроек, FOLLOWING/MUTUAL — по таблице follows. Проверки: новая личка
    (OpenDirect), сообщение/фото/голосовое в личном чате (`MessengerService.directAllowed`), приглашения в группу при создании и
    ADD (отказавшиеся пропускаются, ошибка `privacy_invites` с числом), звонок в личном чате (`call.error.privacy`).
    Оформление страницы — колонки profiles.avatar_type/avatar_pose/avatar_bg/banner_style/banner_image (`SocialRepository.look/setLook`),
    фото баннера — images.kind 3 (KIND_BANNER, видно всем); старое фото баннера отвязывается и удаляется через сутки.
  - `ModerationService` + `ModerationRepository` (таблицы mutes, bans, modlog) — панель модерации (`ModerationPackets` id 98–108:
    поиск игроков, наказанные, Punish MUTE/UNMUTE/BAN/UNBAN с минутами (-1 — навсегда) и причиной, журнал, чаты игрока и
    переписка только для чтения — право MOD_CHATS, пишется в журнал); бан в телефоне — `Backend.handle` проверяет каждый
    пакет (кроме Refresh/LeaveCall; кэш 30 с) и отвечает *.error.banned; мут — и в мессенджере (`messenger.error.muted`).
    `ModerationSettings` (`moderation` в настройках): `chatLog` (лог сообщений в чат игры игрокам с правом CHAT_LOG,
    формат с &-цветами, `ModerationSettings.colors`), `banCommand`/`unbanCommand` (через `ServerPlatform.runConsoleCommand`). `announce` — объявления о MUTE/UNMUTE/BAN/UNBAN/WARN в чат
    игры (всем или staff, личное сообщение наказанному); WARN — только журнал (`ModerationRepository.warnings`).
    `ServerPlatform.onlinePlayers/sendChat` — для лога. Клиент: `social/ModerationClient`, экраны `apps/social/ModerationView`
    (разделы Жалобы/Игроки/Наказ./Журнал), `PlayerModView`, `ModChatsView`, `ModChatView`.
  - `filter/FilterSettings` (POJO для Gson; Paper заполняет из `filter.*` в config.yml; `fixNulls()`) и `filter/ContentFilter` —
    автомодерация: слова (чёрный/белый список; «слово», «слово*», «*слово», «*слово*», «regex:…»; сравнение по «скелету»:
    похожие буквы/цифры сведены, повторы схлопнуты, буквы через разделители склеены; censor → звёздочки или block) и ссылки
    (форматы url, scheme:&lt;имя&gt;, www, domain (TLD из списка или любая 2-буквенная латинская), ip, email, invite, obfuscated
    («[.]», «(dot)», «точка», « . »), свои форматы-регулярки; решение: белый список доменов → чёрный → чёрный форматов → белый форматов →
    mode allow|block). Автомьют: N нарушений за окно → мьют (в ленте — обычный мьют от UUID 0, в мессенджере — в памяти).
    `Backend.filter(player, Context, text)` (право `blockphone.filter.bypass`, лог `[filter]`) вызывают MessengerService
    (сообщение, правка, название группы) и SocialService (пост, комментарий, «о себе», паблик). Ошибки: `*.error.filter_words`,
    `filter_links`, `messenger.error.filter_muted` (минуты).
- `voice/` — Simple Voice Chat: `VoiceBridge` (интерфейс: connected, createGroup, join, leave, removeGroup), `VoiceBridges` (текущий мост,
  главный поток сервера, слушатель выхода из группы — без типов SVC), `SvcBridge` (реализация на `VoicechatServerApi`: скрытые
  непостоянные группы с паролем, тип normal/isolated), `SvcPlugin` (плагин SVC: события старта/остановки сервера SVC и выхода из группы).

**Перезагрузка настроек:** `BlockPhoneServer.restart(old, platform, settings)` (останавливает старое ядро, запускает новое,
заново здоровается с игроками с модом); команды — Paper/Fabric `/blockphone reload`, Velocity `/bpv reload`, Bungee
`/bpb reload`, право `BlockPhoneServer.RELOAD_PERMISSION` (`blockphone.admin.reload`, операторы/консоль).

**Как добавить пакет:** record в `protocol/packet` → `register(<следующий id>, …)` в `Packets` →
обработка на клиенте (`ClientNetwork.handle` → `MessengerClient`/`SocialClient`/…) и на сервере (`Backend.handle` → сервис;
пакет попадает в сервис по классу-группе, в котором он объявлен). Платформы пакеты не разбирают.
При несовместимом изменении — увеличить `PROTOCOL_VERSION`.

### fabric — общий/серверный код (`fabric/src/main`, `dev.blockphone.fabric`)
- `BlockPhoneFabric` — `ModInitializer`; `LOGGER`, `VERSION`; регистрирует `RawPayload` для S2C и C2S.
- `network/RawPayload` — `CustomPacketPayload` с сырыми байтами канала (тот же формат, что plugin message у Paper).
- `server/FabricServerHandler` — адаптер `BlockPhoneServer` (только выделенный сервер или dev-флаг). Держит ссылки на игроков
  с события JOIN (в JOIN игрок ещё не в списке игроков сервера — отправка через `getPlayerList()` теряла приветствие).
- `server/FabricServerHandler` — права: fabric-permissions-api (LuckPerms) через отражение, иначе обычные — у всех,
  модераторские — у операторов уровня 2+ (`player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)`).
- `server/FabricServerConfig` — `config/blockphone/server.json`: uploads{stickers{...}, quota{...}}, apps{mode, blacklist, whitelist}, filter{...} (как FilterSettings), messengerEnabled, phoneInHandVisible, social{enabled, photos,
  maxPhotoKb, maxPhotoSide, maxPublicsPerPlayer}, moderators[], voice{messages, maxSeconds, calls, callGroupType}, storage{type,host,port,...}.
- Simple Voice Chat: точка входа `voicechat` в fabric.mod.json → `SvcPlugin` (из common); главный поток для SVC — `server::execute`.
  База H2: `<папка сервера>/blockphone/messenger.mv.db`.

### fabric — клиент (`fabric/src/client`, `dev.blockphone.fabric.client`)
- `BlockPhoneClient` — клавиши `openPhoneKey` (по умолчанию ↑, категория `key.category.blockphone.main`), `cameraKey`
  (камера сразу), `compactKey` («на ходу», J), `cursorKey` (курсор «на ходу», левый Alt) и музыка `musicPlayKey/musicNextKey/musicPrevKey` (плеер телефона, а если он пуст —
  Spotify; не назначены); `passMovementThrough` — в режиме «на ходу» клавиши ходьбы/прыжка/приседа/бега передаются игроку
  (`KeyMapping.setDown` по состоянию клавиатуры), пока приложение не ждёт текст;
  каждый тик `MusicPlayer.tick()` и `WindowsMedia.tick()`; `CameraMode.init()`, `MusicHud.init()`; при закрытии игры музыка останавливается.
- `network/ClientNetwork` — приём пакетов (Session → `ClientSession`, SocialPackets → `SocialClient`, фото → `ImageUploads`/
  `RemoteImages`, остальное → `MessengerClient`); `serverHello()`, `messengerAvailable()`, `socialAvailable()`; при выходе с сервера
  (в потоке рендера!) сбрасывает всё серверное состояние. `send` шлёт и без объявленного канала, если сервер поздоровался (прокси).
- `network/ClientSession` — сведения о сессии: `serverId()` (камера пишет его в `PhotoMeta.serverId`), `has(right)`,
  `photosAllowed()`, `photoAllowed(meta)` (политика сервера), `mutedUntil()`, `openReports()`.
- `social/` — клиент ленты: `SocialClient` (ленты по ключу, посты, комментарии, профили, паблики, поиск, жалобы, новые посты для
  значка, nonce своего поста, сообщения сервера), `SocialNotifications` (тосты: лайк, комментарий, подписка, жалоба модераторам),
  `SocialFormat` («5 мин», даты, 1.2K).
- `messenger/` — клиент мессенджера: `MessengerClient` (состояние: чаты, сообщения, неподтверждённые по nonce, онлайн, «печатает»,
  результаты поиска, ошибки; `unreadTotal` для значка), `MessengerNotifications` (тост о новом сообщении, если мессенджер не открыт),
  `Avatars` (лицо скина: PlayerInfo с сервера или стандартный скин по UUID; группа — цветной квадрат с буквой).
- `config/PhoneSettings` — `config/blockphone/settings.json` (Gson, публичные поля: language, wallpaper, customWallpaper,
  darkTheme (по умолчанию true), accentColor, clock24h, gameTimeClock, animations, musicVolume, musicHud, lockScreen, compactPhone,
  hudStyle, hudAnchor, hudOffsetX/Y, hudScale, hudOpacity, hudColor, hudShowCover/Artist/Progress/Time/Lyrics/Paused,
  lyricsWorld, lyricsMode (world|view), lyricsSize 1..4, lyricsDistance 3..30, lyricsScatter, lyricsAccentWord, lyricsOutline, lyricsUnsynced, lyricsOffsetMs, lyricsOnline).
- `i18n/PhoneLang` — `tr(key, args...)`: свой выбор языка (настройка телефона: auto/ru_ru/en_us), читает `assets/blockphone/lang/*.json`.
  **Новые строки добавлять в оба файла: `ru_ru.json` и `en_us.json`.**
- `phone/` — каркас телефона:
  - `PhoneLayout` — размеры в виртуальных единицах: корпус 176×356 (скругление 14), рамка 8, экран 160×340 (скругление 8),
    статус-бар 14, навигация 14 («назад» и «домой»), заголовок 20. Область приложения `APP_W×APP_H` = 160×312.
    Текущая ориентация — `phoneW()/phoneH()/screenW()/screenH()/statusH()/navH()/appW()/appH()` (набок — 356×176;
    полный экран — без строки состояния и навигации); их ставит `PhoneScreen.applyOrientation()` по
    `PhoneApp.landscape()/immersive()` перед отрисовкой и вводом, смена ориентации — анимация поворота корпуса;
    в полном экране «назад/домой» всплывают у нижнего края.
  - `PhoneScreen` — `Screen` поверх игры (не ставит на паузу). Открывается и в главном меню / меню паузы (клавиша через
    `ScreenKeyboardEvents.allowKeyPress`), `withParent(screen)` — куда вернуться при закрытии. Приложения общие с телефоном
    «на ходу»: `ensureAttached()` перед отрисовкой и вводом. Масштабирует виртуальные координаты под окно, анимация выезда, статус-бар, заголовок с «<» и кнопкой действия справа, полоска «домой». Навигация: `openApp(id)`, `goHome()`, `back()`, `closePhone()`, `closeImmediately()`; диалоги `showDialog(PhoneDialog)`. ESC = закрыть диалог / назад.
  - `PhoneDialog` — модальный диалог (заголовок, текст, «Отмена» + действие); `PhoneDialog.confirmDelete(...)`.
  - `PhoneApp` — базовый класс приложения: `render`, `mouseClicked/Released/Dragged/Scrolled`, `keyPressed`, `charTyped`, `tick`, `onShown/onHidden`, `onBack`, `capturesKeyboard` (не закрывать телефон клавишей при вводе текста), внешний вид (`hasHeader`, `transparentBackground`, `backgroundColor`, `headerColor`, `lightStatusBar`), кнопка в заголовке (`headerAction`, `headerActionColor`, `onHeaderAction`). Координаты — локальные для области содержимого.
  - `AppRegistry` — список приложений на рабочем столе (id, фабрика, условие видимости, значок-число, цвет приложения).
    Иконка (`Entry.drawIcon`) — плитка-градиент в оттенке цвета приложения + белый символ `textures/gui/glyph/<id>.png`, окрашенный.
  - `PhoneState` — экземпляры приложений и текущее приложение между открытиями телефона; `LOCK` — экран блокировки,
    `markClosed()`/`shouldLock()` (включён в настройках и телефон был закрыт > 15 с или открывается впервые).
  - Режим «на ходу» — `phone/PhonePin`: свой экземпляр `PhoneScreen` прикреплён в правом нижнем углу (~60 % высоты) и рисуется
    в HUD (`HudElementRegistry.addLast`), пока мышь у игры (камера крутится, можно ходить); пока зажата `cursorKey` (по умолчанию
    левый Alt) — тот же экран открыт как `Screen` (курсор), отпустил — `setScreen(null)` с флагом `hiding` (приложения не
    закрываются, `removed()` пропускается); пока поле ввода в фокусе или открыт диалог — курсор остаётся. Вкл/выкл — `compactKey`
    (по умолчанию J), «назад» с рабочего стола / клавиша телефона — снять. `PhoneScreen.compact()` = прикреплён.
    Размер экрана задаётся `ClientCompat.initScreen` (1.21.11 `init(w, h)`, 1.21.10 `init(mc, w, h)`).
  - Уведомления — `phone/NotifyPrefs`: `allowed/toast/center/ring` по настройкам `notifyAll`, `notifyToasts`, `notifyCenter`,
    `notifySound`, `notifyMuted` (messenger, feed, telegram, calls); проверяют `MessengerNotifications`, `SocialNotifications`,
    `TelegramNotifications`, `CallClient`. Страница настроек NOTIFY.
  - `PhoneNotifications` — центр уведомлений (до 30, ключ для замены/снятия, «что открыть» при нажатии). Добавляют
    `MessengerNotifications`, `SocialNotifications`, `TelegramNotifications`, `CallClient` (пропущенный звонок); снимаются при
    открытии чата/ленты. `PhoneScreen.unlock()`/`locked()`; Esc на экране блокировки закрывает телефон.
  - `PhoneDraw` — `roundedRect` (радиус > 4 у прямоугольников автоматически уменьшается до `PhoneTheme.RADIUS`; круги/«таблетки»
    остаются круглыми), `rawRoundedRect` (точный радиус), `panel`/`card` (заливка + рамка), `field` (подложка поля ввода,
    в фокусе — рамка акцента), `gradientPill`, `button(..., ButtonStyle NORMAL|PRIMARY|SELECTED|DANGER)` и совместимый
    `button(..., bg, fg)`, `tinted` (белая маска в цвет), `textureCover` (обрезка по центру), текст, `wrapped`, `inside`.
  - `PhoneTheme` — палитра: `accent()` (из настроек), `accentTint(alpha)`, `accentHover()`, `onAccent()` (текст на акценте),
    `background/card/cardHover/border/field/header/text/secondaryText/separator/danger/online`, корпус `body()`/`bodyEdge()`
    (окантовка подсвечена акцентом), `mix()`, `ACCENTS` (12 готовых), `WALLPAPERS` (градиенты).
  - `Wallpapers` — обои: градиент или своя картинка из `blockphone/wallpapers/` (обрезка по центру, через `PhotoTextures`);
    `setCustom(name)`, `importAndSet(path)` (из галереи), `list()`.
  - `PhoneClock` — время (реальное/игровое, 12/24ч) и дата с локалью телефона.
- Стикеры: `messenger/StickerClient` (ограничения, мои наборы, просмотренные наборы), `photo/StickerMaker` (квадрат со сдвигом
  и приближением, форма SQUARE/ROUNDED/CIRCLE с прозрачным фоном, белая обводка), `ImageUploads.startSticker` (PNG, уменьшение
  до предела), экраны `StickersView` (наборы: создать/переименовать/удалить, «+» — новый стикер, ПКМ — убрать), `StickerEditorView`
  (фото из галереи, форма, обводка, «−/+», перетаскивание), `StickerPackView` (набор по стикеру из чата, «Добавить себе»).
  В `ChatView`: кнопка «☺» → панель (вкладки наборов, «⚙» — мои стикеры, сетка), стикер в переписке — без пузыря (72 px),
  нажатие — набор. `ImageOps` сохраняет прозрачность (read, downscale, toNativeImage).
- `social/ProfileLooks` — аватар по `ProfileLook`: фон-градиент (12) + голова или фигура из скина (`submitSkinRenderState`, своя
  модель на каждую из 8 поз, широкая и тонкая; координаты переводятся через `g.pose()` — модель рисуется отдельной картинкой в
  экранных координатах) и баннер (фото или один из 10 градиентов). `social/PrivacyClient` — мои настройки приватности.
  Экраны ленты: `ProfileView` (баннер, аватар, «Оформление», «Приватность», «Личка закрыта», меню «Заблокировать»),
  `ProfileLookView` (предпросмотр, голова/фигура, поза, фон, баннер, фото из галереи), `PrivacyView` (5 настроек — ЛКМ/ПКМ,
  списки «Всегда можно»/«Заблокированы» через `UserPickView`). `SocialView.hitDir` — клик с направлением (ЛКМ +1, ПКМ −1).
- `ui/PhotoPicker` (выбор фото из галереи поверх приложения; неподходящие под политику — затемнены; «С компьютера» —
  `storage/FileChooser` (tinyfd, свой поток) импортирует картинку в галерею, если политика сервера `any` или это Telegram), `ui/ImageViewer`
  (просмотр фото на весь экран), `ui/Images` (фото с сервера: `draw` с обрезкой, `drawFit`, `height`), `ui/PopupMenu` (меню-пункты).
- `ui/Scrollbar` — общий ползунок прокрутки: приложение во время отрисовки вызывает `Scrollbar.report(top, bottom, scroll, max,
  inverted, setter)` (обычно из своего clampScroll; вне отрисовки — ничего), `PhoneScreen` рисует полосу у правого края
  и ловит нажатие/перетаскивание (зона — 6 px справа). Новые прокручиваемые экраны — тоже через него.
- `ui/TextArea` — поле ввода (одно-/многострочное), **само рисует подложку** (`PhoneDraw.field`) по своим границам:
  перенос слов, курсор, выделение мышью и Shift, Ctrl+A/C/X/V, прокрутка, placeholder.
  Использовать для любого ввода текста (заметки, позже — мессенджер). Пока поле в фокусе, приложение возвращает `capturesKeyboard() = true`.
- `sound/PhoneSounds` — звуки телефона мимо игры (javax.sound, свой поток, `Handle.stop()`): `Kind` NOTIFY/RINGTONE/ALARM/TIMER,
  выбор `PhoneSettings.sounds` («builtin:имя» — синтез `sound/Tones`, «file:имя» — `blockphone/sounds`, MP3/OGG/WAV через
  `music/audio/Decoders`), громкость `soundVolume` × общая; `NotifyPrefs.sound(app)` — звук уведомления; `playFile` — звуки
  своих приложений (`ScriptHost.playOwnSound`, кэш `cache/appsounds`). Страница настроек SOUND (родитель NOTIFY).
- `storage/ClientFiles` — пути к данным (`root()`, `gallery()`, `notesFile()`) и фоновый поток `IO` для файлов/картинок.
- «Магазин» (id `store`): `store/AppStore` (источники — `PhoneSettings.storeSources`, ссылки https на catalog.json;
  `refresh`, `parseCatalog` (ссылки относительно каталога), `install` — скачать zip (≤ 30 МБ), сверить sha256, распаковать
  во временную папку (только .json/.lua/.png/.obj/.txt/.md, без «../», ≤ 400 файлов, ≤ 40 МБ), проверить app.json
  (`AppLoader.parse`, id = id каталога), заменить `blockphone/apps/<id>`; `uninstall`; `installed` — версия; иконки — кэш
  `blockphone/cache/store/`; `allowFiles` — file: только для DevShots).
  Ход установки — `progress(id)` (0..1, 1 — распаковка), `formatSize`. Оценки и отзывы (только `OFFICIAL`, `reviewable`):
  Каталог: `uid`, `category`, `changelog`/`updated` («В последнем обновлении»; при публикации — страница чейнджлога,
  заголовки `X-App-Uid`, `X-Changelog` (base64) → приёмник пишет `changelogs/<id>.json` → build_catalog.py); в каталоге —
  фильтр по категории и сортировка (`StoreApp.category/sort`). `rating(id)` (reviews/summary.json, грузится в refresh), `reviews(id)` (reviews/<id>.json, raw с меткой ?t= мимо кэша),
  `sendReview` → POST `PUBLISH_URL/review` (app, stars, text, player, version); приёмник: один отзыв на приложение с ника
  и с подсети (IPv4 /24, IPv6 /64, в файле — HMAC), пишет в main; сводку собирает `build_catalog.py` (Actions на reviews/**).
  Проверка Mojang невозможна: Mojang отвечает 403 на запросы с Cloudflare. Выкладка приёмника — `wrangler deploy` в
  store-repo (wrangler.toml, keep_vars); wrangler и gh стоят в `%LOCALAPPDATA%\Programs` (вход владельца). `apps/store/StoreApp` — вкладки Каталог (поиск) /
  Установленные / Источники, страница приложения, подтверждение с разрешениями. Официальный каталог владельца — `AppStore.OFFICIAL` (github.com/Jdhihfgiuahfgy/blockphone-apps,
  всегда в источниках), публикация с телефона — «↑» во вкладке «Установленные» → `AppStore.publish` (zip папки → POST на
  `PUBLISH_URL` — «приёмник» Cloudflare Worker `store-repo/worker.js`, ключ GitHub только в нём → Pull Request; пока URL
  пуст — «не настроено»). Авторство: ключ автора на приложение (`AppStore.authorKey`, config/blockphone/publish_keys.json,
  заголовок X-Author-Key), приёмник сверяет sha256 ключа с `owners.json` репозитория (новое приложение — записывает в той же
  заявке; есть в apps-src без записи — принадлежит владельцу каталога); установленные из магазина помечены `.store.json`
  (без ключа — «только автор»); `build_catalog.py --check` ищет копии (одинаковые .lua/.obj ≥ 300 байт). Файлы репозитория — `store-repo/` (build_catalog.py, workflows catalog/check, стартовые
  apps-src), настройка владельцем — docs/STORE_SETUP.md. Инструкция авторам — docs/APP_STORE.md,
  пример каталога — docs/examples/store (DevShots `apps`: `store_catalog`, `store_detail`, `store_installed`).
- `storage/ClipboardImage` — `paste` — файлы или картинка из буфера (Telegram: «+» → «Из буфера обмена», Ctrl+V без текста →
  прикрепляются к сообщению (`TgChatView.photos`/`files`), Windows: FileDrop → PNG → Bitmap → текст-путь, пути в Base64); картинка в буфер обмена (в игре AWT headless): Windows — PowerShell (картинка + файл), macOS —
  osascript, Linux — wl-copy/xclip; путь — через переменную окружения. В галерее — кнопка «Копировать».
- `PhoneClock`: в 12-часовом формате — «AM/PM» (`time()`; крупные часы — `drawBig`, AM/PM мельче). `PhoneScreen.signalBars()` —
  деления связи по пингу (`PlayerInfo.getLatency`: <80 — 4, <150 — 3, <300 — 2, <600 — 1), в одиночной игре — 4.
  Выход из мира (`ClientNetwork`, DISCONNECT) останавливает музыку и голосовые.
- `apps/messenger/` — приложение: `MessengerApp` (стек экранов `MessengerView`), `ChatListView`, `NewChatView`, `UserPickerView`
  (создать группу / добавить участников), `ChatView` (пузыри, разделители дней, подгрузка истории, Enter — отправить,
  Shift+Enter — перенос, ПКМ — меню изменить/копировать/удалить), `ChatInfoView` (участники, переименование, исключение, выход),
  `UserRows`, `MessengerFormat`. `AppRegistry.Entry.badge` — число на значке иконки (мессенджер — непрочитанные).
  Фото в чате: «+» слева от поля → `PhotoPicker` → загрузка сразу (полоска над полем) → отправка с imageId; клик по фото — просмотр.
- `voice/` — голос на клиенте: `Adpcm` (кодек и пересэмплирование), `VoiceRecorder` (микрофон по умолчанию через javax.sound,
  16/48/44,1 кГц → 16 кГц, уровень, предел длины), `VoiceMessages` (сжатие и загрузка, скачивание, проигрывание по одному — музыка на
  паузе), `CallClient` (звонки: свой, входящий, рингтон, тосты, микрофон/громкая связь/звук), `CallAudio` (звук звонка:
  поток микрофона → Opus 16 кГц, порог тишины; приём — декодер и очередь на голос, буфер 3–15 кадров, поток-микшер
  16 кГц; голоса из мира тише с расстоянием). Меню звонка — `apps/messenger/CallView`. `phone/CallOverlay` — входящий звонок
  поверх любого приложения телефона. Полоска звонка на HUD — в `MusicHud` (под виджетом музыки).
- Голос в `ChatView`: пустое поле → справа микрофон (запись → «➤» отправить / «×» отмена), пузыри голосовых («волна», проигрывание),
  трубка в строке статуса (позвонить), зелёная полоса идущего звонка (Войти/Ответить/Отклонить/Выйти).
- `telegram/` — настоящий Telegram через TDLib (только Windows x64): `Telegram` (запуск `Init.init()` + `ClientFactory`, обновления
  пересылаются в поток рендера; вход: SetTdlibParameters → номер → код → облачный пароль; чаты основного списка по `order`,
  пользователи, файлы с докачкой (`localFile`), история по 40, отправка текста и «кружочков», «прочитано», выход, `shutdown` при
  закрытии игры; подписи вложений), `TelegramAvatars`, `TelegramNotifications` (тосты), `VideoNoteEncoder` (JCodec: квадрат ARGB →
  MP4 H.264 без звука; своё управление качеством `TargetRate` ~1 Мбит/с через отражение в SinkImpl.videoEncoder; `cropSquare`), `VideoNoteRecorder` (кадры мира — первым элементом HUD, 320×320, 30 к/с, до 60 с; кольцо и таймер
  на экране; клавиша телефона — отправить, меню — отмена; звук — микрофон (`VoiceRecorder`) + копия играющей музыки
  (`music/AudioTap`: `Playback` отдаёт записанные куски, моно 48 кГц по часам) по настройкам `videoNoteMic`/`videoNoteMusic`,
  `soundtrack()` сводит, Opus 64 кбит/с), `Mp4Audio` (дописывает в MP4 от JCodec дорожку Opus: sample entry «Opus» + dOps,
  edts; новый moov + mdat звука после старого moov — смещения видео не меняются), `OggOpus` (голосовые: OGG Opus ↔ PCM 16 кГц,
  свой разбор/запись страниц с CRC, `encodePackets` для MP4, «волна» Telegram 100×5 бит), `TelegramVoice` (проигрывание —
  скачать, распаковать, общий проигрыватель `VoiceMessages.togglePcm`; отправка записи), `MediaFrames` (GIF/«кружочки»:
  JCodec FrameGrab → до 8 с по 15 к/с, уменьшенные кадры-текстуры, своя очередь «BlockPhone Animation decoder»; `poster` —
  первый кадр для панели; не декодируется — обложка). Стикеры: `stickerImage` (WEBP — сам файл, TGS/WEBM — обложка),
  `loadStickerPanel` (наборы, недавние, сохранённые GIF), `stickerSet`, `sendSticker/sendAnimation/sendVoice`.
- `telegram/TelegramProxy` — прокси (MTProto/SOCKS5/HTTP) для TDLib: режимы auto/custom/off (`PhoneSettings.telegramProxy`),
  свои ссылки `telegramProxies`, общий список `LIST_URL` (proxies.json каталога, кэш `cache/telegram_proxies.json`);
  подбор — AddProxy + PingProxy для всех, EnableProxy самого быстрого; запросы — через отражение по именам полей
  (у версий TDLib разный состав). docs/TELEGRAM_PROXY.md.
- `apps/telegram/` — приложение в стиле официального клиента: гладкий шрифт DejaVu Sans (`assets/blockphone/font/smooth.json`,
  `PhoneDraw.smoothText` — включает только `TelegramApp` на время отрисовки и ввода; остальной телефон — шрифтом игры),
  цвета `TgTheme` (Night/Classic по теме телефона), своя шапка `TelegramApp.HEADER` («‹», круглый аватар, название, статус —
  `TgView.subtitle/headerChat/onTitleClick`), `TgMediaViewer` (фото/видео/GIF/«кружочки» на весь экран, альбом, поворот —
  `landscape()`, полный экран — `immersive()`), `TgProfileView` (телефон, @имя, «о себе»/описание — `Telegram.fullInfo`, нажатие —
  копировать, уведомления — `setMuted`). `telegram/VideoPlayer` — JCodec: видео в своём потоке + звук AAC (JAAD) через
  javax.sound, часы по звуку, перемотка — перезапуск с места. В `Telegram`: статусы (`status`, `userStatus`, `online`, `typing` по
  UpdateChatAction), группы (UpdateSupergroup/BasicGroup — участники), `read` (lastReadOutboxMessageId — галочки ✓/✓✓, ◷, !),
  `muted` (свои настройки чата или по умолчанию для личных/групп/каналов из UpdateScopeNotificationSettings) и `inMain`
  (архив не уведомляет), `reactionIcon` (GetEmojiReaction → staticIcon), `availableReactions`, `progress(file)`.
  Структура: `TelegramApp` (корень — вход или список чатов по состоянию), `TgView`, `TgLoginView` (ключи api_id/api_hash
  с my.telegram.org, номер, код, пароль — поле с маской `TextArea.setMasked`), `TgChatListView`, `TgChatView` (пузыри; альбомы — сообщения
  подряд с одним `mediaAlbumId` одним пузырём с сеткой; ответ (цитата) и «Переслано от»; реакции-«таблетки» (клик — поставить/
  снять); ПКМ — полоса быстрых реакций + меню Ответить/Копировать/Изменить/Удалить; полосы над полем — ответ/правка и выбранные фото;
  поле: «+» (меню: фото — `PhotoPicker` с выбором нескольких, до 10 → `Telegram.sendPhotos`, альбом через `SendMessageAlbum`;
  «кружочек»; микрофон/музыка в «кружочке»), «☺» — панель стикеров и GIF (вкладки: недавние, GIF, наборы; колесо по вкладкам —
  листать), пустое поле — микрофон (запись голосового до 5 мин: «×»/«➤», Enter — отправить); стикер — без пузыря (80 px),
  голосовое — ▶ + «волна», GIF и «кружочки» — анимацией). Значки 8 быстрых реакций — свои цветные `textures/gui/reaction/*.png` (gen_icons.py), прочие эмодзи —
  шрифтом игры. `Telegram.debugInject` — поддельный чат для DevShots (сценарий telegram: `telegram_chat`, `_menu`, `_reply`). Ошибки TDLib → ключи `telegram.error.<сообщение в нижнем регистре>`.
- `apps/hub/HubApp` — приложение «Мессенджер» (id `messenger`): общее для чатов и ленты, внизу вкладки Лента/Чаты/Профиль;
  внутри — `SocialApp` (`embed(openChat, openStickers)`: без своей панели вкладок; паблики и модерация — кнопками в своём
  профиле) и `MessengerApp` (`setReservedBottom` под панель на корне). `HubApp.get()`, `openChat`, `showFeed`,
  `showModeration`; уведомления открывают его. Отдельного приложения `feed` на рабочем столе больше нет.
- `apps/social/` — лента (внутри хаба; раньше — приложение «Лента», id `feed`): `SocialApp` (вкладки Лента/Паблики/Профиль/Модерация внизу, стек `SocialView`,
  общий `ImageViewer`, сообщения сервера), `SocialView` (клики через `hit()`, записываемые при отрисовке; `beginList/endList` —
  прокручиваемая область с обрезкой кликов; `showMenu`), `PostCard` (карточка поста, меню «⋯», жалоба), `FeedView`, `PostView`
  (комментарии), `ComposeView` (от себя/от паблика, текст, фото), `ProfileView` («о себе», подписчики, подписка, «Написать»,
  заглушение), `UserListView`, `UserPickView`, `PublicsView` (поиск, мои/подписки/популярные), `PublicView`, `CreatePublicView`,
  `PublicManageView` (название, админы, удаление), `ModerationView` (жалобы), `Rows`. `debug*`-методы — для DevShots.
- `apps/` — приложения: `home/HomeApp` (рабочий стол: экраны со свайпом, папки, режим правки с перетаскиванием;
  порядок и папки — `home/HomeLayout` по `PhoneSettings.homeOrder/homeFolders`, размер — `homeIconScale`), `lock/LockApp` (экран блокировки: часы, дата, карточка музыки
  (обложка, название, перемотка перетаскиванием, назад/пауза/вперёд), уведомления;
  нажатие на уведомление — открыть, в другом месте — разблокировать),
  `calculator/` (`Calculator` — логика на BigDecimal, `CalculatorApp` — UI), `weather/WeatherApp`,
  `settings/SettingsApp` (MAIN — меню разделов-плиток (`categories()`: значок, название, кратко — что выбрано);
  числовые настройки — ползунки `slider(key, value, min, max, step, get, set)`; разделы GENERAL / LOOK / NOTIFY / MUSIC / LYRICS / TELEGRAM / APPS / ABOUT (`pageSections`); вложенные ACCENT — готовые
  цвета + HEX / WALLPAPER — градиенты + свои картинки (родитель LOOK) / HUD — виджет музыки: предпросмотр «экрана игры» 16:9
  (родитель MUSIC); «назад» — `parent(page)`; `openPage(name)`; ЛКМ по строке — вперёд, ПКМ — назад),
  `clock/ClockApp` («Часы»: вкладки Будильник/Секундомер/Таймер; работа в фоне — `clock/ClockService.tick()` из
  BlockPhoneClient: таймер, секундомер, будильники `PhoneSettings.alarms` (Alarm: hour, minute, enabled, days — биты Пн..Вс,
  0 — один раз, label), звонок до 60 с + тост + `PhoneNotifications` «clock:»; DevShots `clock`),
  `notes/` (`NotesStore` — JSON, атомарная запись; `NotesApp` — список с поиском + редактор, удаление через диалог),
  `camera/CameraApp` (иконка: закрывает телефон и включает `CameraMode`),
  `gallery/` (`GalleryApp` — режимы GRID/VIEWER/EDITOR, клики через список `Hit`, записываемый при отрисовке;
  `PhotoEdit` — правки: поворот → отражение → кадр → фильтр → яркость/контраст/насыщенность).
- `photo/` — фото: `Picture` (ARGB-пиксели), `ImageOps` (фильтр, экспозиция в линейном пространстве, шум, поворот, кадр,
  уменьшение, чтение/запись PNG через ImageIO, `toNativeImage`), `CameraFilter` (enum; параметры читаются из `post_effect/filter_<id>.json`),
  `PhotoMeta` (сведения о снимке), `PhotoStore` (список/сохранение/удаление; `version()` растёт при изменениях),
  `PhotoTextures` (миниатюры и превью как `DynamicTexture` с линейной фильтрацией, LRU на 120, `invalidate`),
  `ImageUploads` (загрузка на сервер: уменьшение до maxPhotoSide, JPEG с подбором качества до maxPhotoBytes, куски),
  `RemoteImages` (фото с сервера: скачивание кусками, кэш на диске, текстуры нужного размера, LRU на 80).
- `music/` — музыка (работает и с закрытым телефоном):
  - `MusicPlayer` — очередь, текущий трек, пауза, перемотка, перемешивание, повтор (OFF/ALL/ONE), история «назад»,
    подгружаемая очередь (`more` — для «Моей волны»), громкость = `musicVolume²` × общая громкость игры. Поток рендера.
  - Время трека: `Playback.setPosition` после каждой записи = записанные кадры − буфер линии (`getBufferSize − available`),
    `positionNow()` дополняет часами (не дальше 300 мс), `MusicPlayer.positionMs()` не шагает назад меньше чем на 400 мс
    (кроме перемотки). DevShots music пишет в лог «music timing» (шаги назад, замирания, расхождение с часами).
  - `Playback` — один трек в своём потоке: файл (`TrackSources`: локальный или скачанный из Яндекса) → `AudioDecoder` →
    `javax.sound` SourceDataLine (буфер 0,25 с); пауза/перемотка/стоп — volatile-полями. `Track` — запись трека.
  - `audio/` — `Mp3Decoder` (JLayer; перемотка пропуском кадров), `OggDecoder` (stb_vorbis из памяти — пути с кириллицей),
    `WavDecoder` (javax.sound), `Decoders` (по подписи файла), `TrackTags` (ID3v2.2–2.4/ID3v1 с Windows-1251, Vorbis comments,
    обложки APIC/METADATA_BLOCK_PICTURE).
  - `LocalLibrary` (сканирование `blockphone/music`, кэш по времени изменения; «Исполнитель - Название» из имени файла),
    `Covers` (обложки из тегов или по адресу → текстуры, LRU 40), `Accounts` (accounts.json), `Http` (HttpURLConnection).
  - `yandex/YandexApi` — неофициальный API (заголовок `Authorization: OAuth`): account/status, likes, tracks, search,
    rotor «Моя волна» (продолжение по `queue=<последний трек>`), лайки, download-info → XML → ссылка
    `https://host/get-mp3/md5(соль + path[1:] + s)/ts + path`. Заголовки: OAuth, X-Yandex-Music-Client, User-Agent «Yandex-Music-API»
    (и при скачивании файла). Скачанное проверяет `audio/AudioSniffer` (MP3 или что пришло — в журнал), не MP3 → следующий
    вариант качества; испорченный файл кэша удаляется в `Playback`. `Http.download` → `Download(code, bytes, type, finalUrl)`: перенаправления
    вручную (до 5, https↔http; Authorization на другой хост не уходит), пустой ответ — не файл; при неудаче Яндекс пробует
    ещё раз без токена. «Моя волна»: `waveBatch` (batchId) + отзывы `waveFeedback` (radioStarted, trackStarted,
    trackFinished/skip — `YandexMusic.tick` по смене трека), без них станция повторяет одну пачку; подгрузка отбрасывает уже
    бывшие треки и пробует от другого трека (до 3 раз). `yandex/YandexMusic` — состояние, вход вставкой адреса
    со страницы OAuth (`access_token=...`), кэш треков.
  - `media/WindowsMedia` — пульт плеера компьютера через системные медиа Windows (SMTC; вместо Spotify Web API — Spotify,
    Яндекс Музыка, браузеры, без входа и Premium): процесс PowerShell со скриптом `assets/blockphone/scripts/media.ps1`
    (копируется в `blockphone/cache/media.ps1`) раз в 0,4 с пишет JSON (название, исполнитель, обложка base64 — один раз на
    трек → `cache/media/*.img`, позиция, длительность, пауза, перемешивание, повтор, что поддерживает приложение), читает
    команды (toggle/next/prev/seek/shuffle/repeat). Запускается по `watch()` (вкладка «На ПК», HUD при `hudWindowsMedia`,
    клавиши музыки при пустом плеере телефона), закрывается через 30 с без `watch`. Только Windows.
  - `lyrics/Lyrics` — разбор текста: LRC (`[мм:сс.хх]`, несколько меток, `[offset:]`), расширенный LRC (`<мм:сс.хх>` у слов),
    обычный текст (строки равномерно по треку); слова без времени — пропорционально длине (к 75 % строки); строка не дольше 8 с;
    `lineAt(ms)`. `lyrics/LyricsService` — текст текущего трека (смена трека в тике): `<трек>.lrc` рядом → тег USLT/LYRICS
    (`TrackTags.Tags.lyrics`) → LRCLIB: `/api/get-cached` → `/api/search` по полям → `/api/search?q=` → `/api/get` (медленный,
    внешние источники); название чистится (`cleanTitle`), исполнитель — первый (`mainArtist`); текст с временем строк и длиной ±4 с
    (`bestSynced`), иначе обычный; кэш `blockphone/cache/lyrics/<sha1>.v3.lrc`, пустой файл — нет текста; кэшируется, только если есть время строк или
    ответили все запросы; «не нашли» и текст без времени ищутся заново через 12 ч; ошибка сети — повторы 3/8/20/45 с, обычный текст
    виден сразу; треки Яндекса — сначала текст самого Яндекса: `YandexApi.lyrics` (`/tracks/<id>/lyrics?format=LRC`, подпись
    HMAC-SHA256 «id+ts» ключом из yandex-music-api; кэш `yandex_<id>.v1.lrc`); свой пул потоков «BlockPhone Lyrics»; следующий трек очереди — заранее; `seekTo(line)`); сдвиг `lyricsOffsetMs`. `lyrics/LyricsWorld` — вывод в мире (`WorldRenderEvents.BEFORE_ENTITIES`,
    `commandQueue().submitText`, полная яркость, обводка): «world» — над поверхностью блока (`groundBelow`) на высоте
    `lyricsHeight` (0..3, по умолчанию 0,25); «view» и если земли нет — `place()`: от `lyricsDistance` ближе по 0,5 блока и выше
    (`LIFTS`), пока прямоугольник текста свободен от блоков с хитбоксом (`free`) и виден (`clip`); масштаб — по фактическому
    расстоянию; строка шире 190 px — в два ряда (`layout`); строка встаёт перед игроком (взгляд, наклон −25..20°, +0,4 блока) и
    остаётся или следует за взглядом, всегда повёрнута к камере (`camera.rotation()`); текст без времени строк в мире — только
    при `lyricsUnsynced`; обводка — `lyricsOutline`; слова «выпрыгивают» по времени, последнее — акцентом, разброс размеров по хэшу; прошлая строка
    гаснет и уплывает вверх. Прячется при F1 и остановке музыки. Только режимы «world»/«view».
  - `lyrics/LyricsHud` — текст на экране (`HudElementRegistry`), режимы `MODES` (настройка `lyricsMode`): left/right — столбик
    строк сбоку (плавная прокрутка), actionbar — над хотбаром, subtitles — снизу с подложкой и следующей строкой, top — крупно
    сверху (под виджетом музыки, если тот вверху); слова загораются по времени (караоке), размер — `lyricsSize`, обводка.
  - `MusicRemote` — пульт музыки (Screen, не телефон): открыт, пока зажата `musicRemoteKey` (по умолчанию M; движение
    проходит игроку), назад/пауза/вперёд, перемотка, громкость, перемешивание/повтор; место/размер — `remoteAnchor`,
    `remoteOffsetX/Y`, `remoteScale` (Настройки → Музыка). DevShots music: `settings_music`, `music_remote`.
  - `MusicHud` — виджет «сейчас играет»: оформления `STYLES` (card, compact — строка-«таблетка», large, minimal — без фона,
    cover — квадрат-обложка), цвет `COLORS` (dark/light/accent) и непрозрачность подложки, угол/край `ANCHORS` (8 мест) + отступы,
    масштаб 50–200 %, что показывать (обложка, исполнитель, полоска, время, строка текста песни, показ на паузе). `current()` →
    `Info`, `measure()`/`draw()` рисуют в точке (0, 0) — их же использует предпросмотр в настройках (страница `HUD`).
    Полоска звонка — всегда слева сверху (под виджетом, если он там). Было: слева сверху (`HudElementRegistry`, перед чатом); прячется при F1, F3, в камере и с телефоном.
- `apps/music/` — приложение «Музыка»: `MusicApp` (вкладки «Моя музыка»/«Яндекс»/«На ПК», мини-плеер, стек `MusicView`),
  `MusicView` (клики `hit()`, ползунки `slider()`, `iconButton`, `bar`), `TrackRows`, `LocalView`, `YandexView` (вход, «Моя волна»,
  поиск, «Мне нравится»), `MediaView` (вкладка «На ПК»: пульт плеера компьютера), `PlayerView` (обложка, перемотка, кнопки, громкость, лайк),
  `QueueView`, `YandexPlaylistView` (треки плейлиста, «Играть»; в `YandexView` переключатель «Мне нравится»/«Мои плейлисты»,
  `YandexApi.playlists/playlistTracks`, `YandexMusic.loadPlaylists/loadPlaylist`), `LyricsView` (текст в телефоне, текущая
  строка по центру, длинные — в два ряда, нажатие — перемотка к строке; в заголовке — «В мире: вкл/выкл»; `MusicApp.openLyrics()`).
  В плеере под громкостью — кнопки «Текст» и «В мире». Иконки `textures/gui/music/*` (gen_icons.py).
- `camera/` — режим камеры:
  - `CameraMode` — вход/выход, такт (ЛКМ снимок, ПКМ следующий фильтр), клавиши (Esc, ←→↑↓, H, R, G), зум колесом,
    `renderOverlay` (видоискатель вместо HUD; тут же берётся кадр для снимка — мир уже нарисован, интерфейс ещё нет).
  - `CameraSettings` — ISO/выдержка/EV/фильтр/сетка/таймер/зум и расчёт экспозиции: 0 ступеней = ISO 400 + 1/125;
    AUTO-параметр компенсирует ручной; выдержка от 1/15 — накопление кадров (смаз), шум растёт выше ISO 400.
  - `PhotoCapture` — копит кадры `Screenshot.takeScreenshot`, усредняет, на `IO`: экспозиция + шум → `PhotoStore.save`.
  - Фильтр в видоискателе — постэффект (шейдер `shaders/post/filter.fsh`, та же формула в `ImageOps.applyFilter`);
    яркость и шум в видоискателе — приблизительное превью оверлеем, точная обработка — на сохранённом кадре.
- `mixin/` (`blockphone.client.mixins.json`, действуют только пока `CameraMode.active()`):
  `GuiMixin` (HUD → видоискатель), `GameRendererMixin` (FOV/зум, без руки, без обводки блока), `GameRendererAccessor`
  (`setPostEffect`), `MinecraftMixin` (отключает `handleKeybinds`), `KeyboardHandlerMixin` (клавиши камеры),
  `MouseHandlerMixin` (колесо = зум, чувствительность делится на зум), `ToastManagerMixin` (без тостов).
- Телефон в руке: `phone/PhoneHolders` (свой — экран телефона или камера, шлётся серверу при изменении; чужие — из `State`),
  `phone/PhoneInHandLayer` (слой игрока через `LivingEntityFeatureRendererRegistrationCallback`; флаг в состоянии отрисовки —
  `RenderStateDataKey`; модель-коробка 3×6×0,5 пикс., текстура `textures/entity/phone_in_hand.png` из gen_icons.py),
  миксины `AvatarRendererMixin` (флаг, предмет главной руки не рисуется) и `HumanoidModelMixin` (рука поднята) — действуют всегда.
- `apps/custom/` — клиентские приложения: `CustomApps` (папка `blockphone/apps/`, перечитывается при изменении, не чаще раза
  в 2 с; политика сервера; иконки как DynamicTexture; id на телефоне `app.<id>`), `CustomApp` (страницы и элементы: text, title,
  card, row, button, buttons, input, list, progress, divider, spacer; "if"/"unless"; действия open/back/refresh/close/toast/
  confirm/then, set/add (хранилище), copy, url и command — всегда с подтверждением, command не чаще 1/с, server → Call),
  `AppStorage` (`blockphone/appdata/<id>.json`, 200 значений, 64 КБ). Скрипт — `ScriptHost` (если у приложения `script`;
  CustomApp передаёт ему всё): песочница LuaJ (Base без load/dofile/loadfile/collectgarbage, Package/Debug — только при
  загрузке и потом убраны, String без dump, rep ≤ 1 МБ, Table, Math, Bit32; без io/os/coroutine/luajava; свой `require` из
  файлов приложения); лимит инструкций через `debug.sethook` (счётчик каждые 1000, бюджет 3 млн на вызов, 20 млн на
  загрузку) — превышение бросает `Error` (`Timeout`), который `pcall` не ловит; ошибка → экран «Приложение остановлено» +
  «Перезапустить». API: app, gfx (только в onDraw; image — PNG из папки, DynamicTexture без сглаживания), ui (button —
  immediate-mode по кликам кадра, input — TextArea по id), theme, screen (landscape/fullscreen/title/captureKeys/close),
  input.down (GLFW, только когда телефон в фокусе), time, timer, json, storage, toast, sound (белый список), clipboard,
  openUrl/runCommand (подтверждение), http (java.net.http, только hosts, без редиректов, 60/мин, 1 МБ), net
  (Relay/Players; входящие при закрытом приложении — очередь `ScriptHost.queue` + уведомление), server (Call/RequestVariables
  со страницей "script"), g3d — `Raster3D` (программная растеризация на Java: текстура с перспективной
  коррекцией, буфер глубины 1/z, свет граней, туман, отсечение по ближней плоскости, спрайты, буфер id для `pick`; кадр —
  DynamicTexture по реальным пикселям, до 360 тыс.; модели — box/plane/mesh/obj, 2000 шт., 400 тыс. вершин; `g3d.free`), захват мыши
  (`input.lockMouse`: GLFW CURSOR_DISABLED, дельты по glfwGetCursorPos, `PhoneApp.capturesMouse` — клики в центр;
  Esc/закрытие — отпустить), `gfx.nine`/`ui.imageButton` (свои картинки, «девятка»). Координаты мыши для ui — относительно
  базовой матрицы кадра (`basePose`). Обратные вызовы (+ onMouseMove, onMouseUnlock): onStart/onShow/onHide/onDraw/onTick/onClick/onRelease/onDrag/onScroll/onKey/
  onChar/onSubmit/onBack/onMessage. Примеры — `docs/examples/scripts/` (snake, tictactoe, weather-web, doom — текстуры `tools/gen_doom.py`;
  blockstrike — шутер по сети: data/maps/sim/bots/netcode/render/hud/viewmodel.lua, хост считает игру и ботов; оружие от
  первого лица — 3D-модели из деталей рядом с глазом (масштаб 0,25 — не уходят в стены), рисуются после выстрелов-pick;
  звуки — свои WAV через `sound.play`, не больше 7 сразу; графика `tools/gen_strike.py`, звуки `tools/gen_strike_sounds.py`); DevShots `apps`
  снимает их и приложение с бесконечным циклом (`apps_script_timeout`). Защита: приложению видны только свои страницы,
  хранилище, ответы своей серверной части и {player}, {lang}, {time}, {date} — ни мира, ни файлов, ни аккаунтов/мессенджера/
  ленты/музыки/Telegram. `AppRegistry.visible()` добавляет их на рабочий стол (Entry.custom, `label()`); «Настройки →
  Свои приложения» (страница APPS: разрешения, статус на сервере, хэш — копируется, ошибки, «Открыть папку»).
- `dev/DevShots` — автоскриншоты (см. выше). Сценарий `apps` (+ BLOCKPHONE_DEV_MESSENGER=1): «Кликер», «Банк» из docs/examples
  с Java-обработчиком на встроенном сервере, подтверждения, отключённое приложение, страница настроек. Сценарий `hand` — только общая часть с миром (снимок `world_phone_in_hand`). Экран блокировки на время сценариев выключается (снимок — `lock_screen` в design).

### paper (`dev.blockphone.paper`)
- `BlockPhonePaper` — `JavaPlugin` + `ServerPlatform`: канал `blockphone:main`; приветствие один раз — на `PlayerJoinEvent`
  (если канал уже объявлен) или на `PlayerRegisterChannelEvent`; отправка в главный поток через планировщик.
- `config.yml`: `uploads.*` (stickers.enabled/max-kb/max-side/max-packs/max-per-pack, quota.per-player-mb/uploads-per-day), `apps.*` (mode, blacklist, whitelist), `filter.*` (автомодерация, с комментариями), `messenger.enabled`, `phone-in-hand.visible`, `social.*` (enabled, photos, max-photo-kb, max-photo-side,
  max-publics-per-player), `voice.*` (messages, max-seconds, calls, call-group-type), `moderators`, `storage.type` (h2/mysql),
  `storage.table-prefix`, `storage.mysql.*`. Simple Voice Chat: `softdepend: [voicechat]`, регистрация через `PaperVoicechat`
  (`BukkitVoicechatService`); главный поток для SVC — планировщик Bukkit (`runOnMain`).
  Новые ключи дописываются в существующий config.yml (`copyDefaults`). Права регистрируются из `Permission`
  (обычные — default true, модераторские — op; `blockphone.moderator` — родитель всех модераторских).

### velocity (`dev.blockphone.velocity`)
- `BlockPhoneVelocity` — `@Plugin`: регистрирует канал, на `ServerPostConnectEvent` (каждый переход между серверами) — приветствие,
  `PluginMessageEvent` на нашем канале обрабатывает сам и **не пропускает** ни к серверам, ни от серверов к клиенту.
  Настройки: `plugins/blockphone/config.json` (messengerEnabled, social, voice{messages, maxSeconds}, moderators, storage).
  Звонков через прокси нет (SVC работает на серверах сети, прокси его группами управлять не может). Права — LuckPerms Velocity
  (`getPermissionValue`; не задано → по умолчанию, у прокси нет операторов — модераторов задавать в `moderators`).
  BungeeCord пока не поддержан.

### bungee (`dev.blockphone.bungee`)
- `BlockPhoneBungee` — `Plugin` + `Listener` + `ServerPlatform`: канал `blockphone:main`, приветствие на `ServerSwitchEvent`
  (вход и каждый переход), `PluginMessageEvent` на нашем канале обрабатывает сам и отменяет (дальше не идёт).
  Настройки — `plugins/BlockPhone/config.json` (как у Velocity). Права: модераторские — `hasPermission` (LuckPerms) или
  `moderators`; обычные — у всех (у BungeeCord нет «не задано»). Звонков нет. API `net.md-5:bungeecord-api` (+ репозиторий
  libraries.minecraft.net для brigadier). Не проверено на живом прокси.

## Две версии Minecraft (1.21.11 и 1.21.10)

- Пишем код только в `fabric/` (под 1.21.11). Модуль `fabric-1.21.10` при сборке копирует исходники `fabric/src/{main,client}/java`
  в `fabric-1.21.10/build/ported/` и заменяет имена, которые в 1.21.11 переименовали (список `LEGACY_RENAMES` в
  `fabric-1.21.10/build.gradle`: Identifier→ResourceLocation, `net.minecraft.util.Util`→`net.minecraft.Util`,
  `model.player.PlayerModel`→`model.PlayerModel`, `RenderTypes`→`RenderType`, `.identifier()`→`.location()`,
  `camera.yRot()`→`getYRot()`, `renderOutline`→`submitOutline` (не использовать: в 1.21.10 рисуется без матрицы — только
  `PhoneDraw.outline`), `rightHandItemState`→`rightHandItem`,
  `hasControlDownWithQuirk`→`hasControlDown`, пакет gamerules). Ресурсы — общие (fabric.mod.json: minecraft 1.21.10).
- Если различие не сводится к переименованию — маленький класс-«переходник» в `fabric/`, а его вариант для 1.21.10 — по тому
  же пути в `fabric-1.21.10/src/` (подменяет общий при сборке): `fabric/compat/Compat` (оператор: `permissions()` /
  `hasPermissions(2)`), `client/photo/SmoothTexture` (линейный фильтр: `sampler` / `setFilter(true, false)`), `client/compat/ClientCompat`
  (`initScreen`).
- После правок в `fabric/` проверять обе: `./gradlew :fabric:compileClientJava :fabric-1.21.10:compileClientJava`. Миксины
  проверяются только запуском (`:fabric-1.21.10:runClient`, сценарий DevShots `hand` — быстрый).
- Сверка имён 1.21.10: `javap -cp <minecraft-clientOnly-…1.21.10…jar>;<minecraft-common-…jar> <класс>` (jar-ы — в
  `.gradle/loom-cache/minecraftMaven/net/minecraft/`).
- Paper: компиляция против API 1.21.10, `api-version: '1.21.10'` — один jar работает на 1.21.10 и 1.21.11. Velocity и
  BungeeCord от версии Minecraft не зависят. Версии — `legacy_minecraft_version`, `legacy_fabric_version`, `paper_api_version`.

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
- Скриншот: `Screenshot.takeScreenshot(mc.getMainRenderTarget(), NativeImage -> ...)` — копия ставится в очередь GPU сразу,
  callback приходит позже на потоке рендера. Порядок кадра в `GameRenderer.render`: мир → постэффект → `gui.render` → экраны.
- Постэффекты: `assets/<ns>/post_effect/<id>.json` (passes; uniform-блоки со статичными значениями: float, vec2..4, int, ivec3, matrix4x4),
  шейдеры `assets/<ns>/shaders/post/*.fsh` (`#version 330`, `layout(std140) uniform <Блок>`). Включение — приватный
  `GameRenderer.setPostEffect(Identifier)` (через accessor), выключение — `clearPostEffect()`.
- `DynamicTexture(Supplier<String>, NativeImage)` сразу загружает на GPU (только поток рендера); регистрация —
  `TextureManager.register(Identifier, texture)`, освобождение — `release(Identifier)`. Сэмплер — protected-поле `sampler`.
- `NativeImage.getPixels()` отдаёт ARGB, `setPixel(x, y, argb)`.
- `SoundEvents.UI_BUTTON_CLICK` — `Holder.Reference`, для `SimpleSoundInstance.forUI` нужен `.value()`.
- Создание мира из кода: `mc.createWorldOpenFlows().createFreshLevel(id, LevelSettings, WorldOptions, WorldPresets::createNormalWorldDimensions, screen)`,
  загрузка — `openWorld(id, onCancel)`.

## План (этапы)
1. ✅ Каркас: модули, протокол-рукопожатие, экран телефона, рабочий стол, калькулятор, погода, настройки.
2. ✅ Заметки, камера (фильтры, зум, ISO/выдержка/EV, таймер, сетка, длинная выдержка), галерея (просмотр, редактор, удаление).
   Не сделано из камеры: глубина резкости (диафрагма) — нужен буфер глубины; можно добавить позже.
3. ✅ Серверный мессенджер: личные и групповые чаты (Fabric + Paper + Velocity, H2/MySQL), уведомления, значок непрочитанных.
   BungeeCord-адаптер — модуль `bungee` (0.7.1).
4. ✅ Лента: посты (от себя и от пабликов), лайки, комментарии, профили, подписки, паблики; фото в постах и чатах с политикой
   админа (только снятые на этом сервере / любые / никакие); жалобы, модерация, заглушение, права (LuckPerms / fabric-permissions-api).
   Не сделано: редактирование постов, упоминания, проверка «снято на этом сервере» опирается на честный клиент.
5. ✅ Музыка: свои файлы (MP3/OGG/WAV), плеер с очередью, виджет на HUD, клавиши; Яндекс Музыка (неоф. API: Моя волна,
   Мне нравится, поиск, лайки); пульт плеера компьютера (системные медиа Windows; был Spotify Web API). Не сделано: FLAC, плейлисты Яндекса, эквалайзер.
6. ✅ Голос: голосовые сообщения (свой захват микрофона и ADPCM — работают без других модов) и звонки в личных и групповых чатах
   (с 0.10.0 — свой голос через сервер/прокси, Opus; громкая связь; SVC не нужен). Не сделано: выбор микрофона (берётся по умолчанию).
7. ✅ Telegram через TDLib (TDLight, только Windows x64): вход, чаты, переписка, фото, альбомы, ответы, реакции, правка/удаление,
   стикеры и GIF (показ и отправка), голосовые (проигрывание и запись, Opus), «кружочки» из кадров игры со звуком (микрофон + музыка).
   Не сделано: звук у присланных видео и «кружочков» (там AAC), анимация TGS/WEBM-стикеров (показывается обложка), видео целиком.

Принятые доп. функции (✅ все сделаны, 0.7.1–0.7.4): BungeeCord, экран блокировки и уведомления, режим «на ходу», телефон в руке
виден другим (выключатель `phoneInHandVisible`/`phone-in-hand.visible`, предмет в руке не меняется, работает и на Paper/прокси).
Доп. после плана: текст песни в мире (0.7.5), оформления виджета музыки (0.7.6), автомодерация (0.7.7), приватность и
личная страница (0.7.8), свои приложения с серверными частями (0.8.0, docs/CUSTOM_APPS.md),
стикеры и ограничения загрузок (0.8.1).
Отклонено пока: геометки в чатах, команды для игроков без мода.
Одиночная игра = только клиентские функции (мессенджер шлётся только с выделенного сервера).
