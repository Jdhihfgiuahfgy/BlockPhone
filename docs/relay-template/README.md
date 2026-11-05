# Свой сервер комнат (BlockPhone)

Это готовый сервер для `net.*` в ваших приложениях — тот же, что у Block Strike, но ваш собственный, на вашем
аккаунте Cloudflare. Подробная инструкция — в `CUSTOM_APPS.md`, раздел «Свой сервер для игры по сети».

Коротко:

1. Зарегистрируйтесь на https://dash.cloudflare.com (бесплатно) и установите Node.js (https://nodejs.org).
2. В этой папке: `npx wrangler login`, затем `npx wrangler deploy`.
3. Wrangler напишет адрес вида `https://my-blockphone-relay.<ваш-поддомен>.workers.dev`.
4. В `app.json` своего приложения: `"relay": "https://my-blockphone-relay.<ваш-поддомен>.workers.dev"`.
