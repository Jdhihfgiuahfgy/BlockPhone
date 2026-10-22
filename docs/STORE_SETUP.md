# Официальный каталог BlockPhone: настройка (для владельца)

Каталог: **https://github.com/Jdhihfgiuahfgy/blockphone-apps**. Мод у всех игроков сам берёт приложения отсюда:
`https://raw.githubusercontent.com/Jdhihfgiuahfgy/blockphone-apps/main/catalog.json`.

Как это работает:

```
Игрок: Магазин → Установленные → ↑ (Опубликовать)
   → «приёмник» (Cloudflare Worker, бесплатно; ключ GitHub — только там)
   → заявка (Pull Request) в репозитории → автоматическая проверка архива («Check submission»)
   → вы нажимаете Merge → GitHub сам пересобирает catalog.json → приложение в Магазине у всех (через 1–5 минут)
```

Все файлы для репозитория лежат в папке мода **`store-repo/`**.

> Токен и пароли никому не отправляйте (и в чат с Claude тоже). Они вводятся только на сайтах GitHub и Cloudflare.

---

## Шаг 1. Загрузите файлы в репозиторий

1. Откройте https://github.com/Jdhihfgiuahfgy/blockphone-apps (репозиторий должен быть **Public**).
2. **Add file → Upload files**.
3. Откройте на компьютере папку `store-repo` из папки мода, выделите **всё её содержимое** (`.github`, `apps`, `apps-src`,
   `icons`, `catalog.json`, `build_catalog.py`, `worker.js`, `README.md`) и перетащите в окно браузера.
   Папка `.github` важна — в ней автоматика. Если Windows её не показывает: «Вид → Показать → Скрытые элементы».
4. Внизу — **Commit changes**.
5. Проверьте: откройте ссылку
   https://raw.githubusercontent.com/Jdhihfgiuahfgy/blockphone-apps/main/catalog.json — должен открыться текст со
   списком из 4 приложений (DOOM, Змейка, Крестики-нолики, Погода).

С этого момента «Магазин» в телефоне у всех показывает ваш каталог.

### Если папка `.github` не загрузилась (приложения не появляются в Магазине)

Браузер часто пропускает папки с точкой. Проверка: в репозитории есть вкладка **Actions** с запусками «Build catalog».
Нет — создайте два файла вручную: **Add file → Create new file**, в поле имени введите путь целиком
`.github/workflows/catalog.yml`, вставьте содержимое одноимённого файла из `store-repo/.github/workflows/` →
**Commit changes**. Так же — `.github/workflows/check.yml`. Затем **Actions → Build catalog → Run workflow** —
каталог соберётся сразу (иначе — при следующем одобрении).

## Шаг 2. Разрешите автоматике сохранять каталог

Репозиторий → **Settings → Actions → General** → внизу **Workflow permissions** → **Read and write permissions** → **Save**.

## Шаг 3. Создайте токен GitHub (ключ только для этого репозитория)

1. GitHub → аватар справа вверху → **Settings** → внизу слева **Developer settings** → **Personal access tokens →
   Fine-grained tokens** → **Generate new token**.
2. Token name: `blockphone-store`. Expiration: 1 год (потом создадите новый).
3. **Repository access → Only select repositories →** `blockphone-apps`.
4. **Permissions → Repository permissions**:
   * **Contents** — Read and write;
   * **Pull requests** — Read and write.
5. **Generate token** → скопируйте токен (начинается с `github_pat_`). Он показывается один раз — сразу переходите к шагу 4.

## Шаг 4. Создайте «приёмник» на Cloudflare (бесплатно)

1. Зарегистрируйтесь на https://dash.cloudflare.com (бесплатный план).
2. **Workers & Pages → Create → Create Worker** (шаблон «Hello World»), имя: `blockphone-store` → **Deploy**.
3. **Edit code** → удалите всё в редакторе → вставьте содержимое файла `store-repo/worker.js` → **Deploy**.
4. Вернитесь к worker → **Settings → Variables and Secrets → Add**:
   * `REPO_OWNER` — тип **Text**, значение `Jdhihfgiuahfgy`;
   * `REPO_NAME` — тип **Text**, значение `blockphone-apps`;
   * `GITHUB_TOKEN` — тип **Secret**, значение — токен из шага 3.
   Нажмите **Deploy** (сохранить).
5. Скопируйте адрес worker (вида `https://blockphone-store.<ваше-имя>.workers.dev`) и откройте его в браузере —
   должно быть `BlockPhone app store receiver: OK`.
6. **Пришлите этот адрес** (он не секретный) — я впишу его в мод (`AppStore.PUBLISH_URL`), и кнопка «Опубликовать»
   заработает у всех в следующей версии.

## Шаг 4б. Оценки и отзывы

Отзывы хранятся в этом же репозитории (папка `reviews`), отдельный хостинг не нужен. Чтобы включить:

1. Cloudflare → worker `blockphone-store` → **Edit code** → замените код на новый `store-repo/worker.js` → **Deploy**.
2. В репозитории замените `build_catalog.py` и `.github/workflows/catalog.yml` на новые из `store-repo` (на сайте GitHub:
   откройте файл → карандаш «Edit» → вставьте новый текст → **Commit changes**).

Как это работает: игрок ставит 1–5 звёзд и пишет отзыв на странице приложения (только установив его). Приёмник принимает
один отзыв на приложение с одного ника (новый заменяет старый) и с одной сети (IP-подсети), и записывает его в
`reviews/<id>.json` — сразу, без заявки. Сам IP в файл не попадает — только его зашифрованный отпечаток.
(Проверить лицензию у Mojang нельзя: Mojang не принимает запросы с серверов Cloudflare.) GitHub Actions пересчитывает средние оценки (`reviews/summary.json`).
Удалить плохой отзыв: откройте `reviews/<id>.json` → «Edit» → удалите его блок `{ … }` (вместе с запятой) → Commit.

## Шаг 5. Одобрение приложений

Когда игрок публикует приложение, в репозитории появляется заявка: **Pull requests**.

1. Откройте заявку. Внизу — проверка **Check submission**: зелёная галочка — архив в порядке, красный крестик — ошибка
   (подробности — Details).
2. Вкладка **Files changed** — что прислали (архив `apps-src/<id>.zip`). Можно скачать и посмотреть код.
3. Подходит — **Merge pull request → Confirm merge**. Через 1–5 минут приложение появится в Магазине у всех.
   Не подходит — **Close pull request**.

Обновление приложения игроком приходит такой же заявкой (тот же `<id>.zip` с новой версией).

## Авторство (кто может обновлять приложение)

* При первой публикации телефон автора создаёт секретный **ключ автора** для этого приложения
  (`config/blockphone/publish_keys.json` в папке игры). В репозиторий (`owners.json`) попадает только его отпечаток.
* Обновление с тем же id приёмник примет только с тем же ключом. Скачавший приложение ключа не имеет — отправить его
  «обратно» (даже изменённым) не получится: «this app belongs to another author».
* Стартовые приложения и загруженные вами вручную в `apps-src` (их нет в `owners.json`) с телефона не обновить никому.
* Копию чужого приложения под новым id выдаст проверка заявки: «… share code — looks like a copy» (красный крестик).
* **Не загружайте `owners.json` из папки мода поверх файла в репозитории** — там пустой шаблон, авторы всех приложений
  «потеряются» и обновить их с телефона будет нельзя («this app belongs to the catalog owner»).
* Автор потерял ключ (переустановил игру)? Удалите строку его приложения из `owners.json` в репозитории — следующая
  публикация запишет новый ключ. Ключ можно и перенести: скопировать `publish_keys.json` на новый компьютер.

## Своё приложение без телефона

Упакуйте папку приложения в zip (файлы `app.json` и остальные — в корне архива), назовите `<id>.zip` и загрузите в
папку `apps-src` репозитория (**Add file → Upload files**). Каталог пересоберётся сам.

## Если что-то не так

* Вкладка **Actions** в репозитории — журнал автоматики; красный значок — откройте и посмотрите ошибку.
* «Check submission» красный — в архиве нет `app.json`, id не совпадает с именем файла или нет файла скрипта.
* Телефон пишет «HTTP 502: github: …» — проверьте токен (не истёк ли, есть ли права Contents и Pull requests) и
  переменные `REPO_OWNER` / `REPO_NAME` в Cloudflare.
* Кто-то шлёт мусор — просто закрывайте заявки. При желании в Cloudflare можно временно выключить worker.
