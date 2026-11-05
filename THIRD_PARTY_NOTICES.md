# Third-party software in BlockPhone / Сторонние компоненты BlockPhone

BlockPhone's own code is © its author (see the mod's license). The mod ships with, or downloads, the open-source
components listed below. Each keeps its own license; the full texts are in the `licenses/` folder next to this file
(inside the mod `.jar` — `licenses/` and `THIRD_PARTY_NOTICES.md` at its root).

Собственный код BlockPhone принадлежит его автору (см. лицензию мода). Вместе с модом поставляются (или скачиваются им)
перечисленные ниже открытые компоненты. У каждого — своя лицензия; полные тексты лежат в папке `licenses/` рядом с этим
файлом (внутри `.jar` мода — `licenses/` и `THIRD_PARTY_NOTICES.md` в корне).

## Bundled inside the mod (Fabric) / Встроены в мод (Fabric)

Libraries are bundled as separate nested jars (`META-INF/jars/`), unmodified, so each one can be replaced.
Библиотеки вложены отдельными jar-файлами (`META-INF/jars/`) без изменений, поэтому каждую можно заменить.

| Component | Version | Used for | License | Text |
|---|---|---|---|---|
| [H2 Database](https://h2database.com) | 2.3.232 | messenger database | MPL 2.0 or EPL 1.0 (used under MPL 2.0) | `licenses/MPL-2.0.txt` |
| [MariaDB Connector/J](https://github.com/mariadb-corporation/mariadb-connector-j) | 3.5.1 | MySQL / MariaDB storage | LGPL 2.1 or later | `licenses/LGPL-2.1.txt` |
| [JLayer](http://www.javazoom.net/javalayer/javalayer.html) (JavaZOOM) | 1.0.1 | MP3 decoding | LGPL 2.1 | `licenses/LGPL-2.1.txt` |
| [TDLight Java](https://github.com/tdlight-team/tdlight-java) (tdlight-java, tdlight-api) | 3.5.9 / 4.0.565 | Telegram | LGPL 3.0 | `licenses/LGPL-3.0.txt`, `licenses/GPL-3.0.txt` |
| [TDLib](https://github.com/tdlib/td) (native library in tdlight-natives, Windows x64) | 4.0.597 | Telegram | Boost Software License 1.0 | `licenses/BSL-1.0.txt` |
| OpenSSL 3.5.2 and zlib 1.3.1 (statically linked into the TDLib native library) | — | encryption, compression | Apache 2.0 / zlib | `licenses/Apache-2.0.txt`, `licenses/Zlib.txt` |
| [Reactive Streams](https://www.reactive-streams.org) | 1.0.4 | Telegram (dependency of TDLight) | MIT No Attribution | `licenses/reactive-streams.txt` |
| [atlassian-util-concurrent](https://bitbucket.org/atlassian/atlassian-util-concurrent) | 4.0.1 | Telegram (dependency of TDLight) | Apache 2.0 | `licenses/Apache-2.0.txt` |
| [JCodec](https://github.com/jcodec/jcodec) | 0.2.5 | video notes, video frames | BSD 2-Clause (FreeBSD) | `licenses/jcodec.txt` |
| [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) (imageio-webp, -core, -metadata, common-lang, -io, -image) | 3.12.0 | WEBP stickers | BSD 3-Clause | `licenses/twelvemonkeys.txt` |
| [Concentus](https://github.com/lostromb/concentus) (jaredmdobson build) | 1.0.2 | Opus audio (voice, calls, speakers) | BSD 3-Clause (Opus) | `licenses/concentus.txt` |
| [LuaJ](https://github.com/luaj/luaj) | 3.0.1 | scripts of custom apps | MIT | `licenses/luaj.txt` |
| [jcefmaven](https://github.com/jcefmaven/jcefmaven) | 135.0.20 | browser (downloads Chromium) | Apache 2.0 | `licenses/jcefmaven.txt` |
| [JCEF](https://bitbucket.org/chromiumembedded/java-cef) (jcef-api) | CEF 135.0.20 | browser | BSD 3-Clause | `licenses/jcef.txt` |
| [DejaVu Sans](https://dejavu-fonts.github.io) font | 2.35 | smooth font in the Telegram app | Bitstream Vera / Arev license + public domain | `assets/blockphone/font/LICENSE_DEJAVU.txt` |

## Downloaded on first use / Скачивается при первом запуске

| Component | Used for | License |
|---|---|---|
| Chromium Embedded Framework and Chromium (via jcefmaven, into `blockphone/browser/jcef`) | built-in browser, screens in the world | BSD 3-Clause and the licenses of Chromium's components; the full list opens inside the browser at `chrome://credits` |

## Server plugins / Плагины для сервера

- **Velocity and BungeeCord plugins** include H2 and MariaDB Connector/J (see above).
  **Плагины для Velocity и BungeeCord** содержат H2 и MariaDB Connector/J (см. выше).
- **Paper plugin** contains no third-party code; Paper downloads H2 and MariaDB Connector/J itself (`libraries:` in plugin.yml).
  **Плагин для Paper** не содержит чужого кода: H2 и MariaDB Connector/J скачивает сам Paper.

## Not bundled / Не входят в мод

Minecraft, Fabric Loader, Fabric API, Gson, LWJGL (OpenAL, stb) and Apache Commons are provided by the game or the
loader. Simple Voice Chat is optional and installed separately. Sound Physics Remastered is supported through
reflection and is not included.

Minecraft, Fabric Loader, Fabric API, Gson, LWJGL (OpenAL, stb) и Apache Commons предоставляет сама игра или загрузчик.
Simple Voice Chat необязателен и ставится отдельно. Sound Physics Remastered поддерживается без встраивания его кода.

## Source code of LGPL components / Исходный код LGPL-компонентов

The LGPL libraries are used unmodified. Their source code is available from the projects above:
[MariaDB Connector/J](https://github.com/mariadb-corporation/mariadb-connector-j),
[JLayer](http://www.javazoom.net/javalayer/sources.html),
[TDLight Java](https://github.com/tdlight-team/tdlight-java). You may replace any of these nested jars with your own
build of the same library.

LGPL-библиотеки используются без изменений, их исходный код доступен по ссылкам выше. Любой из этих вложенных jar можно
заменить своей сборкой той же библиотеки.
