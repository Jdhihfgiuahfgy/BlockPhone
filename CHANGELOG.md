# Журнал изменений

Формат: новые записи сверху. В каждой записи — дата, версия/этап и список изменений с файлами,
чтобы при поиске бага было видно, где и когда он мог появиться.
Подробная история — в git (`git log -p -- <файл>`).

## [0.1.0] — 2026-10-19 — Этап 1: каркас

### Проект
- Шаблоны `PhoneMod/` и `PhoneModPlugin/` заменены единым Gradle-проектом `BlockPhone` с модулями
  `common`, `fabric`, `paper` (`settings.gradle`, `build.gradle`, `*/build.gradle`). Старые папки не удалены, исключены из git.
- Gradle 9.8.0 (wrapper), демон Gradle на Java 25 (автоскачивание, `gradle/gradle-daemon-jvm.properties`).
- Маппинги переведены с Yarn на официальные Mojang.
- Mod id `blockphone`, пакет `dev.blockphone`.

### Протокол (common)
- Канал `blockphone:main`, формат «VarInt id + тело» (`Packets`, `PacketWriter`, `PacketReader`, `ProtocolException`).
- Пакеты `ServerHelloPacket` (S→C) и `ClientHelloPacket` (C→S). `PROTOCOL_VERSION = 1`.

### Сервер
- Fabric: `FabricServerHandler` — рукопожатие на выделенном сервере; конфиг `config/blockphone/server.json` (`FabricServerConfig`).
- Paper: `BlockPhonePaper` — то же через plugin messaging; `config.yml` (`messenger.enabled`, `phone-in-hand.visible`).

### Клиент
- Клавиша «Открыть телефон» (по умолчанию ↑), категория BlockPhone в настройках управления (`BlockPhoneClient`).
- Экран телефона `PhoneScreen`: корпус со скруглёнными углами, «островок» камеры, анимация выезда/закрытия,
  статус-бар (время, индикатор «связи» = есть ли BlockPhone на сервере, батарея), заголовок с кнопкой «назад»,
  полоска «домой». ESC — назад; клавиша телефона — закрыть. Игра не ставится на паузу.
- Каркас приложений: `PhoneApp`, `AppRegistry`, `PhoneState`, `PhoneDraw`, `PhoneTheme`, `PhoneLayout`, `PhoneClock`.
- Рабочий стол `HomeApp`: большие часы, дата, сетка иконок.
- Приложения: Калькулятор (`Calculator`, `CalculatorApp`, ввод мышью и с клавиатуры), Погода (`WeatherApp`: биом,
  осадки, «температура», игровое время, день, фаза луны, высота), Настройки (`SettingsApp`: язык, часы реальные/игровые,
  12/24ч, тема, обои, анимации, информация о версии/сервере/клавише).
- Заглушки «скоро»: Мессенджер (виден только на сервере с BlockPhone), Telegram, Камера, Галерея, Музыка, Заметки.
- Собственная локализация телефона `PhoneLang` (ru_ru / en_us, выбор в настройках); настройки игрока `PhoneSettings`
  (`config/blockphone/settings.json`).
- Иконки приложений и погоды генерируются `tools/gen_icons.py`.
- Dev-инструмент `DevShots` (переменная `BLOCKPHONE_DEV_SHOTS`) — автоскриншоты интерфейса.
- Погода вне мира (главное меню) показывает подсказку «Войдите в мир…» вместо пустого экрана.

### Проверено
- Сборка `./gradlew build` (fabric + paper).
- Клиент запускается, мод загружается без ошибок; через `DevShots` проверены рабочий стол, калькулятор,
  настройки и заглушки в светлой (EN) и тёмной (RU) теме.

### Не проверено (нужен ручной тест)
- Погода внутри мира, клавиша ↑ в игре, анимации.
- Рукопожатие с сервером: Paper-плагин и Fabric-сервер (иконка «Мессенджер» должна появиться, деления «связи» — полные).
