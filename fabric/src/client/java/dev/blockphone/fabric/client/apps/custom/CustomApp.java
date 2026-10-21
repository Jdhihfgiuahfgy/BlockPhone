package dev.blockphone.fabric.client.apps.custom;

import dev.blockphone.fabric.client.ui.Scrollbar;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockphone.common.apps.AppDefinition;
import dev.blockphone.common.apps.AppLoader;
import dev.blockphone.common.apps.AppPermission;
import dev.blockphone.common.apps.AppText;
import dev.blockphone.common.apps.InputRules;
import dev.blockphone.common.protocol.packet.AppPackets;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Клиентское приложение из папки игрока: показывает страницы из app.json и выполняет действия из фиксированного набора.
 * <p>
 * Защита: приложение — данные, а не код. Оно видит только свои страницы, своё хранилище, ответы своей серверной части и
 * несколько безопасных переменных ({player} — свой ник, {lang}, {time}, {date}). Мира, сущностей, блоков, координат, файлов,
 * мессенджера, ленты, музыки, Telegram и аккаунтов приложению не видно — читерских возможностей (xray и т. п.) нет.
 * Ссылки и команды выполняются только после подтверждения игрока, каждая; таймеров и автоповторов нет.
 * Запрещённое сервером приложение не открывается.
 */
public final class CustomApp extends PhoneApp {
    private static final long TOAST_MS = 3500;
    private static final long COMMAND_GAP_MS = 1000;

    private final String appId;
    private final Deque<String> stack = new ArrayDeque<>();
    private final Map<String, TextArea> inputs = new LinkedHashMap<>();
    private final Map<String, String> serverVars = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private AppDefinition app;
    private AppStorage storage;
    private String page;
    /** Скрипт приложения (app.json: "script") или null — приложение из страниц. */
    private @org.jetbrains.annotations.Nullable ScriptHost host;
    private boolean loading;
    private JsonObject pendingAction;
    private double scroll;
    private int contentHeight;
    private String toast = "";
    private boolean toastError;
    private long toastAt;
    private long lastCommandAt;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    public CustomApp(String phoneId) {
        super(phoneId);
        this.appId = phoneId.substring(CustomApps.PREFIX.length());
    }

    private boolean ready() {
        AppDefinition latest = CustomApps.get(appId);
        if (latest != app) {
            // Файл приложения изменился — начать с первой страницы.
            app = latest;
            stack.clear();
            page = null;
            storage = latest != null && latest.has(AppPermission.STORAGE) ? new AppStorage(appId) : null;
            if (host != null) {
                host.release();
            }
            host = latest != null && latest.script() != null ? new ScriptHost(this, latest, storage) : null;
            if (host != null && phone != null) {
                host.onShow();
            }
        }
        if (app == null || !CustomApps.allowedHere(app)) {
            return false;
        }
        if (host != null) {
            return true;
        }
        if (page == null) {
            open(app.start(), false);
        }
        return true;
    }

    @Override
    public String title() {
        if (app == null) {
            return PhoneLang.tr("app.custom");
        }
        if (host != null) {
            return host.title() != null ? host.title() : CustomApps.name(app);
        }
        JsonObject p = page != null ? app.pages().getAsJsonObject(page) : null;
        return p != null && p.has("title") ? text(p.get("title")) : CustomApps.name(app);
    }

    @Override
    public void onShown() {
        if (ready() && host != null) {
            host.onShow();
            return;
        }
        if (ready() && page != null && serverPage(page)) {
            requestVariables();
        }
    }

    @Override
    public void onHidden() {
        inputs.values().forEach(t -> t.setFocused(false));
        if (host != null) {
            host.onHide();
        }
    }

    @Override
    public void tick() {
        if (host != null && ready()) {
            host.tick();
        }
    }

    @Override
    public boolean landscape() {
        return host != null && app != null && CustomApps.allowedHere(app) && host.landscape;
    }

    @Override
    public boolean immersive() {
        return host != null && app != null && CustomApps.allowedHere(app) && host.fullscreen && !host.failed();
    }

    @Override
    public boolean capturesMouse() {
        return host != null && host.mouseLocked();
    }

    @Override
    public boolean hasHeader() {
        return host == null || host.failed() || !host.fullscreen || app == null || !CustomApps.allowedHere(app);
    }

    /** Телефон, на котором открыто приложение (для скрипта: диалоги, «закрыть»). */
    @org.jetbrains.annotations.Nullable dev.blockphone.fabric.client.phone.PhoneScreen phone() {
        return phone;
    }

    /** «Перезапустить» после ошибки скрипта: новое окружение, главный файл заново. */
    void restartScript() {
        if (host != null) {
            host.release();
        }
        host = app != null && app.script() != null ? new ScriptHost(this, app, storage) : null;
        if (host != null) {
            host.onShow();
        }
    }

    /** Сообщение от этого же приложения другого игрока (net). */
    void onRelayed(String from, String payload) {
        if (host != null && ready()) {
            host.start();
            host.deliver(from, payload);
        } else {
            ScriptHost.queue(appId, from, payload);
        }
    }

    void onPlayers(java.util.List<String> names) {
        if (host != null) {
            host.onPlayers(names);
        }
    }

    // --- Навигация ---

    private void open(String target, boolean push) {
        if (push && page != null) {
            stack.push(page);
        }
        page = target;
        scroll = 0;
        inputs.clear();
        JsonObject p = app.pages().getAsJsonObject(target);
        collectInputs(p);
        if (serverPage(target)) {
            requestVariables();
        }
    }

    private void navigate(String nav) {
        if (nav == null || nav.isEmpty()) {
            return;
        }
        switch (nav) {
            case "back" -> onBack();
            case "close" -> {
                if (phone != null) {
                    phone.goHome();
                }
            }
            case "refresh" -> {
                if (serverPage(page)) {
                    requestVariables();
                }
            }
            default -> {
                if (app.pages().has(nav)) {
                    open(nav, true);
                }
            }
        }
    }

    @Override
    public boolean onBack() {
        if (host != null) {
            return host.onBack();
        }
        if (!stack.isEmpty()) {
            page = null;
            open(stack.pop(), false);
            return true;
        }
        return false;
    }

    private boolean serverPage(String pageId) {
        JsonObject p = app.pages().getAsJsonObject(pageId);
        return p != null && p.has("server") && p.get("server").getAsBoolean() && app.has(AppPermission.SERVER);
    }

    private void requestVariables() {
        if (CustomApps.serverPart(appId)) {
            loading = true;
            ClientNetwork.send(new AppPackets.RequestVariables(appId, page));
        }
    }

    void onPolicyChanged() {
        if (host != null) {
            return;
        }
        if (app != null && page != null && serverPage(page)) {
            requestVariables();
        }
    }

    void onServerVariables(String forPage, Map<String, String> values) {
        if (host != null) {
            host.onServerVariables(values);
            return;
        }
        serverVars.putAll(values);
        if (forPage.equals(page)) {
            loading = false;
        }
    }

    void onServerResult(AppPackets.CallResult r) {
        if (host != null) {
            host.onServerResult(r);
            return;
        }
        serverVars.putAll(r.values());
        JsonObject action = pendingAction;
        pendingAction = null;
        if (r.error()) {
            showToast(r.toast(), true);
            return;
        }
        String message = !r.toast().isEmpty() ? r.toast() : action != null && action.has("toast") ? text(action.get("toast")) : "";
        if (!message.isEmpty()) {
            showToast(message, false);
        }
        navigate(!r.navigate().isEmpty() ? r.navigate() : action != null ? localNavigation(action) : "");
    }

    // --- Переменные ---

    /** Безопасные переменные: свой ник, язык, время; хранилище приложения; ответы серверной части. */
    private Map<String, String> vars() {
        Map<String, String> v = new HashMap<>();
        v.put("player", Minecraft.getInstance().getUser().getName());
        v.put("lang", PhoneLang.current());
        LocalDateTime now = LocalDateTime.now();
        v.put("time", now.format(DateTimeFormatter.ofPattern("HH:mm")));
        v.put("date", now.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        if (storage != null) {
            v.putAll(storage.values());
        }
        v.putAll(serverVars);
        return v;
    }

    private String text(JsonElement e) {
        return AppText.substitute(AppText.pick(e, PhoneLang.current()), vars());
    }

    private String text(JsonObject o, String key) {
        return o.has(key) ? text(o.get(key)) : "";
    }

    /** Условие показа элемента: "if": "{var}" — показывать, если не пусто и не 0/false; "unless" — наоборот. */
    private boolean shown(JsonObject el) {
        if (el.has("if") && !truthy(text(el, "if"))) {
            return false;
        }
        return !el.has("unless") || !truthy(text(el, "unless"));
    }

    private static boolean truthy(String value) {
        String v = value.strip();
        return !v.isEmpty() && !v.equals("0") && !v.equalsIgnoreCase("false") && !(v.startsWith("{") && v.endsWith("}"));
    }

    // --- Действия ---

    private void act(String actionId) {
        JsonObject action = app.actions().get(actionId);
        if (action == null || pendingAction != null) {
            return;
        }
        // Поля текущей страницы — проверить сразу (сервер всё равно проверит заново).
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> def : app.inputs().getOrDefault(page, Map.of()).entrySet()) {
            TextArea area = inputs.get(def.getKey());
            String raw = area != null ? area.text() : "";
            String error = InputRules.validate(def.getValue(), raw);
            if (error != null) {
                String label = def.getValue().has("label") ? text(def.getValue().get("label")) : def.getKey();
                showToast(error + "|" + label, true);
                return;
            }
            values.put(def.getKey(), InputRules.normalize(def.getValue(), raw));
        }
        Map<String, String> all = vars();
        all.putAll(values);

        List<String> commands = new ArrayList<>();
        if (action.has("command")) {
            commands.add(action.get("command").getAsString());
        }
        if (action.has("commands")) {
            for (JsonElement c : action.getAsJsonArray("commands")) {
                commands.add(c.getAsString());
            }
        }
        List<String> resolved = new ArrayList<>();
        for (String c : commands) {
            String cmd = AppText.substitute(c, all).strip();
            resolved.add(cmd.startsWith("/") ? cmd.substring(1) : cmd);
        }
        String url = action.has("url") ? AppText.substitute(AppText.pick(action.get("url"), PhoneLang.current()), all).strip() : null;
        if (url != null && !(url.startsWith("https://") || url.startsWith("http://"))) {
            showToast("@app.error.url", true);
            return;
        }

        StringBuilder confirm = new StringBuilder();
        if (action.has("confirm")) {
            confirm.append(AppText.substitute(AppText.pick(action.get("confirm"), PhoneLang.current()), all));
        }
        if (!resolved.isEmpty()) {
            appendLine(confirm, PhoneLang.tr("app.confirm_command"));
            resolved.forEach(c -> appendLine(confirm, "/" + c));
        }
        if (url != null) {
            appendLine(confirm, PhoneLang.tr("app.confirm_url"));
            appendLine(confirm, url);
        }
        Runnable run = () -> perform(action, values, all, resolved, url);
        if (confirm.length() > 0 && phone != null) {
            phone.showDialog(new PhoneDialog(CustomApps.name(app), confirm.toString(), PhoneLang.tr("app.confirm"), false, run));
        } else {
            run.run();
        }
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(line);
    }

    private void perform(JsonObject action, Map<String, String> values, Map<String, String> all, List<String> commands, String url) {
        Minecraft mc = Minecraft.getInstance();
        if (!commands.isEmpty()) {
            long now = System.currentTimeMillis();
            if (now - lastCommandAt < COMMAND_GAP_MS || mc.player == null) {
                showToast("@app.error.rate_limit", true);
                return;
            }
            lastCommandAt = now;
            for (String command : commands) {
                if (!command.isEmpty()) {
                    mc.player.connection.sendCommand(command);
                }
            }
        }
        if (url != null) {
            net.minecraft.util.Util.getPlatform().openUri(url);
        }
        if (action.has("copy")) {
            mc.keyboardHandler.setClipboard(AppText.substitute(AppText.pick(action.get("copy"), PhoneLang.current()), all));
            showToast("@app.copied", false);
        }
        if (storage != null && (action.has("set") || action.has("add"))) {
            boolean ok = true;
            if (action.has("set")) {
                for (Map.Entry<String, JsonElement> e : action.getAsJsonObject("set").entrySet()) {
                    ok &= storage.put(e.getKey(), AppText.substitute(AppText.pick(e.getValue(), PhoneLang.current()), all));
                }
            }
            if (action.has("add")) {
                for (Map.Entry<String, JsonElement> e : action.getAsJsonObject("add").entrySet()) {
                    BigDecimal current = number(storage.values().getOrDefault(e.getKey(), "0"));
                    BigDecimal delta = number(AppText.substitute(e.getValue().getAsString(), all));
                    ok &= storage.put(e.getKey(), current.add(delta).stripTrailingZeros().toPlainString());
                }
            }
            storage.save();
            if (!ok) {
                showToast("@app.error.storage_full", true);
            }
        }
        if (action.has("server")) {
            if (!CustomApps.serverPart(appId)) {
                showToast("@app.error.no_server", true);
                return;
            }
            pendingAction = action;
            ClientNetwork.send(new AppPackets.Call(appId, action.get("server").getAsString(), page, values));
            return; // сообщение и переход — по ответу сервера
        }
        if (action.has("toast")) {
            showToast(AppText.substitute(AppText.pick(action.get("toast"), PhoneLang.current()), all), false);
        }
        navigate(localNavigation(action));
    }

    private static String localNavigation(JsonObject action) {
        if (action.has("then")) {
            return action.get("then").getAsString();
        }
        if (action.has("open")) {
            return action.get("open").getAsString();
        }
        if (action.has("back")) {
            return "back";
        }
        if (action.has("refresh")) {
            return "refresh";
        }
        return action.has("close") ? "close" : "";
    }

    private static BigDecimal number(String value) {
        try {
            return new BigDecimal(value.strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private void showToast(String message, boolean error) {
        toast = message;
        toastError = error;
        toastAt = System.currentTimeMillis();
    }

    /** «@ключ|аргумент» — перевод телефона; JSON-объект — перевод от сервера; иначе — как есть. */
    private String localize(String message) {
        if (message.startsWith("@")) {
            int bar = message.indexOf('|');
            String key = message.substring(1, bar >= 0 ? bar : message.length());
            return bar >= 0 ? PhoneLang.tr(key, message.substring(bar + 1)) : PhoneLang.tr(key);
        }
        if (message.startsWith("{")) {
            try {
                return AppText.pick(JsonParser.parseString(message), PhoneLang.current());
            } catch (RuntimeException ignored) {
                return message;
            }
        }
        return message;
    }

    // --- Отрисовка ---

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        hits.clear();
        int w = contentWidth();
        int h = contentHeight();
        if (!ready()) {
            String key = app == null ? "app.error.missing" : "app.blocked";
            PhoneDraw.wrapped(g, PhoneLang.tr(key), 12, h / 2f - 12, w - 24, PhoneTheme.secondaryText(), 0.75f, false);
            return;
        }
        if (host != null) {
            host.render(g, mouseX, mouseY, w, h);
            return;
        }
        JsonObject p = app.pages().getAsJsonObject(page);
        scroll = Math.max(0, Math.min(Math.max(0, contentHeight - h), scroll));
        Scrollbar.report(0, h, scroll, Math.max(0, contentHeight - h), false, v -> scroll = v);
        g.enableScissor(0, 0, w, h);
        int y = 6 - (int) scroll;
        JsonArray elements = p.has("elements") ? p.getAsJsonArray("elements") : new JsonArray();
        int end = layout(g, elements, 8, y, w - 16, mouseX, mouseY);
        contentHeight = end + (int) scroll + 10;
        g.disableScissor();
        if (loading) {
            PhoneDraw.centeredText(g, PhoneLang.tr("messenger.loading"), w / 2f, h - 12, PhoneTheme.secondaryText(), 0.6f, false);
        }
        if (!toast.isEmpty() && System.currentTimeMillis() - toastAt < TOAST_MS) {
            String message = localize(toast);
            int tw = Math.min(w - 16, (int) PhoneDraw.width(message, 0.7f) + 16);
            int lines = Math.max(1, PhoneDraw.wrappedHeight(message, tw - 12, 0.7f) / 8);
            int th = 8 + lines * 8;
            int tx = (w - tw) / 2;
            int ty = h - th - 8;
            PhoneDraw.rawRoundedRect(g, tx, ty, tx + tw, ty + th, 4, toastError ? 0xF0B3363F : 0xF0202838);
            PhoneDraw.wrapped(g, message, tx + 6, ty + 4, tw - 12, 0xFFFFFFFF, 0.7f, false);
        }
    }

    /** Разложить и нарисовать элементы; вернуть y под последним. */
    private int layout(GuiGraphics g, JsonArray elements, int x, int y, int w, int mx, int my) {
        for (JsonElement e : elements) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject el = e.getAsJsonObject();
            if (!shown(el)) {
                continue;
            }
            String type = el.has("type") ? el.get("type").getAsString() : "text";
            String actionId = el.has("_a") ? el.get("_a").getAsString() : null;
            switch (type) {
                case "text", "title" -> {
                    float scale = "title".equals(type) ? 1.15f : size(el);
                    String value = text(el, "text");
                    int color = color(el, PhoneTheme.text());
                    String align = el.has("align") ? el.get("align").getAsString() : "left";
                    List<String> lines = wrap(value, w, scale);
                    int lh = Math.round(10 * scale);
                    for (String line : lines) {
                        switch (align) {
                            case "center" -> PhoneDraw.centeredText(g, line, x + w / 2f, y, color, scale, false);
                            case "right" -> PhoneDraw.rightText(g, line, x + w, y, color, scale);
                            default -> PhoneDraw.text(g, line, x, y, color, scale, false);
                        }
                        y += lh;
                    }
                    y += 3;
                }
                case "card" -> {
                    int top = y;
                    JsonArray inner = el.has("elements") ? el.getAsJsonArray("elements") : new JsonArray();
                    // Сначала размер (без отрисовки), потом подложка и содержимое.
                    int bottom = measure(inner, x + 6, y + 6, w - 12);
                    PhoneDraw.card(g, x, top, x + w, bottom + 3, false);
                    layout(g, inner, x + 6, y + 6, w - 12, mx, my);
                    y = bottom + 8;
                }
                case "row" -> {
                    boolean hover = actionId != null && PhoneDraw.inside(mx, my, x, y, w, 18);
                    if (hover) {
                        PhoneDraw.roundedRect(g, x - 2, y, x + w + 2, y + 18, 4, PhoneTheme.cardHover());
                    }
                    String label = text(el, "label");
                    String value = text(el, "value");
                    float vw = PhoneDraw.width(value, 0.75f);
                    PhoneDraw.text(g, PhoneDraw.ellipsize(label, w - vw - 8, 0.72f), x, y + 5, PhoneTheme.secondaryText(), 0.72f, false);
                    PhoneDraw.rightText(g, value, x + w, y + 5, color(el, PhoneTheme.text()), 0.75f);
                    if (actionId != null) {
                        String id = actionId;
                        hits.add(new Hit(x, y, w, 18, () -> act(id)));
                    }
                    y += 20;
                }
                case "button" -> {
                    button(g, el, x, y, w, mx, my);
                    y += 22;
                }
                case "buttons" -> {
                    JsonArray items = el.has("items") ? el.getAsJsonArray("items") : new JsonArray();
                    int n = Math.max(1, items.size());
                    int bw = (w - (n - 1) * 4) / n;
                    for (int i = 0; i < items.size(); i++) {
                        if (items.get(i).isJsonObject()) {
                            button(g, items.get(i).getAsJsonObject(), x + i * (bw + 4), y, bw, mx, my);
                        }
                    }
                    y += 22;
                }
                case "input" -> {
                    String id = el.get("id").getAsString();
                    if (el.has("label")) {
                        PhoneDraw.text(g, text(el, "label"), x, y, PhoneTheme.secondaryText(), 0.62f, false);
                        y += 9;
                    }
                    TextArea area = inputs.get(id);
                    if (area != null) {
                        area.setBounds(x, y, w, 16);
                        if (el.has("placeholder")) {
                            area.setPlaceholder(text(el, "placeholder"));
                        }
                        area.render(g, mx, my);
                    }
                    y += 20;
                }
                case "divider" -> {
                    g.fill(x, y + 3, x + w, y + 4, PhoneTheme.separator());
                    y += 8;
                }
                case "spacer" -> y += el.has("height") ? Math.max(0, Math.min(200, el.get("height").getAsInt())) : 8;
                case "progress" -> {
                    BigDecimal value = number(text(el, "value"));
                    BigDecimal max = number(el.has("max") ? text(el, "max") : "100");
                    float fraction = max.signum() > 0 ? Math.max(0, Math.min(1, value.floatValue() / max.floatValue())) : 0;
                    if (el.has("label")) {
                        PhoneDraw.text(g, text(el, "label"), x, y, PhoneTheme.secondaryText(), 0.62f, false);
                        y += 9;
                    }
                    PhoneDraw.roundedRect(g, x, y, x + w, y + 5, 2, PhoneTheme.field());
                    PhoneDraw.roundedRect(g, x, y, x + Math.max(3, Math.round(w * fraction)), y + 5, 2, color(el, PhoneTheme.accent()));
                    y += 10;
                }
                case "list" -> {
                    JsonArray items = el.has("items") ? el.getAsJsonArray("items") : new JsonArray();
                    List<JsonObject> rows = new ArrayList<>();
                    for (JsonElement item : items) {
                        if (item.isJsonObject() && shown(item.getAsJsonObject())) {
                            rows.add(item.getAsJsonObject());
                        }
                    }
                    if (rows.isEmpty()) {
                        break;
                    }
                    PhoneDraw.card(g, x, y, x + w, y + rows.size() * 24, false);
                    for (int i = 0; i < rows.size(); i++) {
                        JsonObject item = rows.get(i);
                        int ry = y + i * 24;
                        String itemAction = item.has("_a") ? item.get("_a").getAsString() : null;
                        if (itemAction != null && PhoneDraw.inside(mx, my, x, ry, w, 24)) {
                            g.fill(x + 1, ry + 1, x + w - 1, ry + 23, PhoneTheme.cardHover());
                        }
                        if (i > 0) {
                            g.fill(x + 6, ry, x + w - 6, ry + 1, PhoneTheme.separator());
                        }
                        String value = text(item, "value");
                        float vw = value.isEmpty() ? 0 : PhoneDraw.width(value, 0.72f) + 6;
                        PhoneDraw.text(g, PhoneDraw.ellipsize(text(item, "title"), w - 12 - vw, 0.75f), x + 6, ry + 4, PhoneTheme.text(),
                                0.75f, false);
                        PhoneDraw.text(g, PhoneDraw.ellipsize(text(item, "subtitle"), w - 12 - vw, 0.6f), x + 6, ry + 14,
                                PhoneTheme.secondaryText(), 0.6f, false);
                        if (!value.isEmpty()) {
                            PhoneDraw.rightText(g, value, x + w - 6, ry + 8, color(item, PhoneTheme.accent()), 0.72f);
                        }
                        if (itemAction != null) {
                            hits.add(new Hit(x, ry, w, 24, () -> act(itemAction)));
                        }
                    }
                    y += rows.size() * 24 + 6;
                }
                default -> {
                }
            }
        }
        return y;
    }

    /** Высота блока без отрисовки (для подложки карточки). */
    private int measure(JsonArray elements, int x, int y, int w) {
        return layoutMeasure(elements, y, w);
    }

    private int layoutMeasure(JsonArray elements, int y, int w) {
        for (JsonElement e : elements) {
            if (!e.isJsonObject() || !shown(e.getAsJsonObject())) {
                continue;
            }
            JsonObject el = e.getAsJsonObject();
            String type = el.has("type") ? el.get("type").getAsString() : "text";
            switch (type) {
                case "text", "title" -> {
                    float scale = "title".equals(type) ? 1.15f : size(el);
                    y += wrap(text(el, "text"), w, scale).size() * Math.round(10 * scale) + 3;
                }
                case "card" -> y = layoutMeasure(el.has("elements") ? el.getAsJsonArray("elements") : new JsonArray(), y + 6, w - 12) + 8;
                case "row" -> y += 20;
                case "button", "buttons" -> y += 22;
                case "input" -> y += (el.has("label") ? 9 : 0) + 20;
                case "divider" -> y += 8;
                case "spacer" -> y += el.has("height") ? Math.max(0, Math.min(200, el.get("height").getAsInt())) : 8;
                case "progress" -> y += (el.has("label") ? 9 : 0) + 10;
                case "list" -> {
                    int n = 0;
                    for (JsonElement item : el.has("items") ? el.getAsJsonArray("items") : new JsonArray()) {
                        if (item.isJsonObject() && shown(item.getAsJsonObject())) {
                            n++;
                        }
                    }
                    y += n == 0 ? 0 : n * 24 + 6;
                }
                default -> {
                }
            }
        }
        return y;
    }

    private void button(GuiGraphics g, JsonObject el, int x, int y, int w, int mx, int my) {
        String style = el.has("style") ? el.get("style").getAsString() : "normal";
        PhoneDraw.ButtonStyle s = switch (style) {
            case "primary" -> PhoneDraw.ButtonStyle.PRIMARY;
            case "danger" -> PhoneDraw.ButtonStyle.DANGER;
            case "selected" -> PhoneDraw.ButtonStyle.SELECTED;
            default -> PhoneDraw.ButtonStyle.NORMAL;
        };
        boolean busy = pendingAction != null;
        PhoneDraw.button(g, x, y, w, 18, PhoneDraw.ellipsize(text(el, "label"), w - 6, 0.72f), busy ? -1000 : mx, my, s);
        if (el.has("_a") && !busy) {
            String id = el.get("_a").getAsString();
            hits.add(new Hit(x, y, w, 18, () -> act(id)));
        }
    }

    private static float size(JsonObject el) {
        String size = el.has("size") ? el.get("size").getAsString() : "normal";
        return switch (size) {
            case "small" -> 0.6f;
            case "large" -> 0.95f;
            case "huge" -> 1.4f;
            default -> 0.75f;
        };
    }

    private int color(JsonObject el, int fallback) {
        if (!el.has("color")) {
            return fallback;
        }
        String c = el.get("color").getAsString();
        return switch (c) {
            case "accent" -> PhoneTheme.accent();
            case "secondary" -> PhoneTheme.secondaryText();
            case "danger" -> PhoneTheme.danger();
            case "success" -> PhoneTheme.online();
            case "text" -> PhoneTheme.text();
            default -> AppLoader.parseColor(c);
        };
    }

    /** Перенос по словам (длинные слова режутся). */
    private static List<String> wrap(String text, int width, float scale) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (PhoneDraw.width(candidate, scale) <= width || line.length() == 0) {
                    line.setLength(0);
                    line.append(candidate);
                    while (PhoneDraw.width(line.toString(), scale) > width && line.length() > 1) {
                        int cut = line.length() - 1;
                        while (cut > 1 && PhoneDraw.width(line.substring(0, cut), scale) > width) {
                            cut--;
                        }
                        lines.add(line.substring(0, cut));
                        line.delete(0, cut);
                    }
                } else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private void collectInputs(JsonElement e) {
        if (e == null) {
            return;
        }
        if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(this::collectInputs);
            return;
        }
        if (!e.isJsonObject()) {
            return;
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("type") && "input".equals(o.get("type").getAsString()) && o.has("id")) {
            TextArea area = new TextArea(false, 0.75f);
            boolean number = "number".equals(InputRules.kind(o));
            area.setMaxLength(o.has("max") && !number ? Math.min(InputRules.MAX_INPUT, o.get("max").getAsInt()) : InputRules.MAX_INPUT);
            if (o.has("value")) {
                area.setText(text(o.get("value")));
            }
            inputs.put(o.get("id").getAsString(), area);
        }
        for (Map.Entry<String, JsonElement> child : o.entrySet()) {
            if (child.getValue().isJsonObject() || child.getValue().isJsonArray()) {
                collectInputs(child.getValue());
            }
        }
    }

    // --- Для автоскриншотов (DevShots) ---

    /** Нажать элемент с действием (id вида «страница:номер»). */
    /** Для DevShots: клавиша для скрипта (onKey). */
    public void debugKey(String name) {
        if (host != null) {
            host.debugKey(name);
        }
    }

    /** Для DevShots: клик в содержимом приложения. */
    public void debugClick(double x, double y) {
        mouseClicked(x, y, 0);
    }

    public void debugAct(String actionId) {
        if (ready()) {
            act(actionId);
        }
    }

    /** Ввести текст в поле текущей страницы. */
    public void debugInput(String id, String value) {
        TextArea area = inputs.get(id);
        if (area != null) {
            area.setText(value);
        }
    }

    // --- Ввод ---

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (host != null) {
            return ready() && host.mouseClicked(x, y, button, contentWidth(), contentHeight());
        }
        boolean handled = false;
        for (TextArea area : inputs.values()) {
            if (area.mouseClicked(x, y, button)) {
                handled = true;
            } else {
                area.setFocused(false);
            }
        }
        if (handled) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h()) && y >= 0 && y <= contentHeight()) {
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (host != null) {
            return ready() && host.mouseDragged(x, y, dx, dy);
        }
        for (TextArea area : inputs.values()) {
            if (area.isFocused() && area.mouseDragged(x, y)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (host != null) {
            return ready() && host.mouseReleased(x, y, button);
        }
        for (TextArea area : inputs.values()) {
            area.mouseReleased();
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (host != null) {
            return ready() && host.mouseScrolled(x, y, amount);
        }
        scroll -= amount * 20;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (host != null) {
            return ready() && host.keyPressed(event);
        }
        for (TextArea area : inputs.values()) {
            if (area.isFocused()) {
                return area.keyPressed(event);
            }
        }
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (host != null) {
            return ready() && host.charTyped(event);
        }
        for (TextArea area : inputs.values()) {
            if (area.isFocused()) {
                return area.charTyped(event);
            }
        }
        return false;
    }

    @Override
    public boolean capturesKeyboard() {
        if (host != null) {
            return host.capturesKeyboard();
        }
        return inputs.values().stream().anyMatch(TextArea::isFocused);
    }
}
