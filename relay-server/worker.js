// Сетевые игры своих приложений BlockPhone без плагина на игровом сервере: комнаты в облаке Cloudflare
// (Worker + Durable Object на комнату, WebSocket). Сервер только пересылает сообщения — ничего не разбирает и не хранит.
//
// GET /ws/<комната>?name=<ник>&k=<ключ сессии> — WebSocket. Комната:
//   s<32 hex> — «этот сервер Minecraft» (хэш адреса сервера считает телефон: все с модом на одном сервере — вместе);
//   c<код из 6 символов> — комната по коду (можно играть с разных серверов и из одиночной игры).
// Ник не проверить (Mojang не отвечает Cloudflare). Чтобы чужой не занял ник того, кто уже в комнате, у телефона свой
// случайный ключ сессии: тот же ник с тем же ключом заменяет старое подключение (переподключился), с другим — 409.
// Сообщения — JSON-текст:
//   телефон → {t:"send", a:<id приложения>, to:<ник или "">, d:<строка ≤ 8192>};
//   сервер  → {t:"hello", room, you, members}, {t:"members", members}, {t:"msg", a, f:<от кого>, d}, {t:"err", e}.
// Не больше 100 сообщений за 5 с от игрока (у плагина — 40; через облако игры шлют чаще). Текст «ping» — ответ «pong» без пробуждения комнаты.

const VERSION = 1;
const ROOM_RE = /^(s[0-9a-f]{32}|c[A-HJ-NP-Z2-9]{6})$/;
const NAME_RE = /^[A-Za-z0-9_]{1,16}$/;
const APP_RE = /^[a-z0-9_-]{1,32}$/;
const KEY_RE = /^[A-Za-z0-9]{16,64}$/;
const MAX_MEMBERS = 200;
const MAX_PAYLOAD = 8192;
const RATE_COUNT = 100;
const RATE_WINDOW_MS = 5000;
const enc = new TextEncoder();

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

async function sha256(text) {
  const hash = await crypto.subtle.digest("SHA-256", enc.encode(text));
  return [...new Uint8Array(hash)].map(b => b.toString(16).padStart(2, "0")).join("");
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/status")) {
      return json({ ok: true, service: "blockphone-relay", version: VERSION });
    }
    const m = url.pathname.match(/^\/ws\/([A-Za-z0-9]{7,33})$/);
    if (m && request.headers.get("Upgrade") === "websocket") {
      if (!ROOM_RE.test(m[1])) return json({ error: "bad_room" }, 404);
      return env.RELAY.get(env.RELAY.idFromName(m[1])).fetch(request);
    }
    return json({ error: "not_found" }, 404);
  },
};

export class RelayRoom {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.rate = new Map();
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request) {
    const url = new URL(request.url);
    const name = url.searchParams.get("name") || "";
    const key = url.searchParams.get("k") || "";
    if (!NAME_RE.test(name) || !KEY_RE.test(key)) return json({ error: "bad_request" }, 400);
    const keyHash = await sha256(key);
    const open = this.open();
    const same = open.filter(ws => ws.deserializeAttachment()?.name.toLowerCase() === name.toLowerCase());
    if (same.some(ws => ws.deserializeAttachment().keyHash !== keyHash)) return json({ error: "name_taken" }, 409);
    if (same.length === 0 && open.length >= MAX_MEMBERS) return json({ error: "full" }, 403);
    // Переподключился (обрыв, смена мира) — старое подключение того же телефона закрыть.
    for (const ws of same) { try { ws.close(4000, "replaced"); } catch { /* уже */ } }
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1]);
    pair[1].serializeAttachment({ name, keyHash });
    const room = url.pathname.slice(4);
    pair[1].send(JSON.stringify({ t: "hello", room, you: name, members: this.members(), version: VERSION }));
    this.broadcast({ t: "members", members: this.members() }, pair[1]);
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  open() {
    return this.ctx.getWebSockets().filter(ws => ws.readyState === 1);
  }

  members() {
    const names = [];
    for (const ws of this.open()) {
      const a = ws.deserializeAttachment();
      if (a && !names.includes(a.name)) names.push(a.name);
    }
    return names;
  }

  broadcast(msg, except) {
    const text = JSON.stringify(msg);
    for (const ws of this.open()) {
      if (ws !== except) { try { ws.send(text); } catch { /* закрыт */ } }
    }
  }

  allow(name) {
    const now = Date.now();
    const list = (this.rate.get(name) || []).filter(t => now - t < RATE_WINDOW_MS);
    const ok = list.length < RATE_COUNT;
    if (ok) list.push(now);
    this.rate.set(name, list);
    return ok;
  }

  async webSocketMessage(ws, data) {
    if (typeof data !== "string" || data.length > MAX_PAYLOAD + 512) return;
    const a = ws.deserializeAttachment();
    if (!a) return;
    let msg;
    try { msg = JSON.parse(data); } catch { return; }
    if (!msg || msg.t !== "send" || typeof msg.a !== "string" || !APP_RE.test(msg.a)) return;
    if (typeof msg.d !== "string" || msg.d.length > MAX_PAYLOAD) return;
    const to = typeof msg.to === "string" ? msg.to.toLowerCase() : "";
    if (!this.allow(a.name)) {
      try { ws.send(JSON.stringify({ t: "err", e: "rate_limit" })); } catch { /* закрыт */ }
      return;
    }
    const text = JSON.stringify({ t: "msg", a: msg.a, f: a.name, d: msg.d });
    for (const other of this.open()) {
      if (other === ws) continue;
      const b = other.deserializeAttachment();
      if (!b || (to !== "" && b.name.toLowerCase() !== to)) continue;
      try { other.send(text); } catch { /* закрыт */ }
    }
  }

  async webSocketClose(ws) {
    this.left(ws);
  }

  async webSocketError(ws) {
    this.left(ws);
  }

  left(ws) {
    // Закрывающееся соединение уже не «открыто» (readyState ≠ 1) — в списке его нет.
    this.broadcast({ t: "members", members: this.members() }, ws);
    if (this.open().length === 0) this.rate.clear();
  }
}
