package dev.blockphone.fabric.client.apps.feedback;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.ImageOps;
import dev.blockphone.fabric.client.photo.ImageUploads;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.storage.ClientFiles;
import dev.blockphone.fabric.client.store.AppStore;
import dev.blockphone.fabric.client.ui.PhotoPicker;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * «Жалобы и предложения»: вид (жалоба / предложение / ошибка), текст и до 4 скриншотов из галереи → «приёмник»
 * ({@link AppStore#PUBLISH_URL}/feedback) → папка feedback/ в репозитории владельца мода.
 */
public final class FeedbackApp extends PhoneApp {
    private static final String[] KINDS = {"bug", "idea", "complaint"};
    private static final int MAX_PHOTOS = 4;
    private static final int MAX_TEXT = 3000;
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private final List<Hit> hits = new ArrayList<>();
    private final List<PhotoStore.Photo> photos = new ArrayList<>();
    private final PhotoPicker picker = new PhotoPicker(this::togglePhoto, true, photos::contains);
    private final TextArea text = new TextArea(true, 0.7f);
    private int kind;
    private boolean sending;
    private String notice = "";
    private boolean noticeError;

    public FeedbackApp() {
        super("feedback");
        text.setMaxLength(MAX_TEXT);
        text.setPlaceholder(PhoneLang.tr("feedback.placeholder"));
    }

    @Override
    public boolean capturesKeyboard() {
        return text.isFocused();
    }

    @Override
    public boolean onBack() {
        if (picker.isOpen()) {
            picker.close();
            return true;
        }
        return false;
    }

    private void togglePhoto(PhotoStore.Photo photo) {
        if (!photos.remove(photo) && photos.size() < MAX_PHOTOS) {
            photos.add(photo);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        hits.clear();
        int w = contentWidth();
        int h = contentHeight();
        if (picker.isOpen()) {
            picker.render(g, mouseX, mouseY, w, h);
            return;
        }
        int y = 6;
        PhoneDraw.wrapped(g, PhoneLang.tr("feedback.intro"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.6f, false);
        y += PhoneDraw.wrappedHeight(PhoneLang.tr("feedback.intro"), w - 16, 0.6f) + 6;
        // Вид обращения.
        int cw = (w - 16 - 8) / 3;
        for (int i = 0; i < KINDS.length; i++) {
            int x = 8 + i * (cw + 4);
            boolean on = i == kind;
            boolean hover = PhoneDraw.inside(mouseX, mouseY, x, y, cw, 16);
            PhoneDraw.rawRoundedRect(g, x, y, x + cw, y + 16, 8, on ? PhoneTheme.accent() : hover ? PhoneTheme.cardHover() : PhoneTheme.field());
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(PhoneLang.tr("feedback.kind." + KINDS[i]), cw - 6, 0.6f), x + cw / 2f, y + 5,
                    on ? PhoneTheme.onAccent() : PhoneTheme.text(), 0.6f, false);
            int index = i;
            hits.add(new Hit(x, y, cw, 16, () -> kind = index));
        }
        y += 22;
        int textH = Math.max(60, h - y - 92);
        text.setBounds(8, y, w - 16, textH);
        text.render(g, mouseX, mouseY);
        y += textH + 4;
        PhoneDraw.rightText(g, text.text().length() + "/" + MAX_TEXT, w - 8, y, PhoneTheme.secondaryText(), 0.5f);
        y += 8;
        // Скриншоты.
        int size = 30;
        int x = 8;
        for (PhotoStore.Photo photo : List.copyOf(photos)) {
            PhotoTextures.Image thumb = PhotoTextures.thumbnail(photo);
            if (thumb != null) {
                PhoneDraw.textureCoverRounded(g, thumb.id(), x, y, size, size, thumb.width(), thumb.height(), 4);
            } else {
                PhoneDraw.rawRoundedRect(g, x, y, x + size, y + size, 4, PhoneTheme.field());
            }
            PhoneDraw.rawRoundedRect(g, x + size - 10, y + 1, x + size - 1, y + 10, 4, 0xC0000000);
            PhoneDraw.centeredText(g, "×", x + size - 5.5f, y + 2, 0xFFFFFFFF, 0.6f, false);
            hits.add(new Hit(x + size - 11, y, 11, 11, () -> photos.remove(photo)));
            x += size + 4;
        }
        if (photos.size() < MAX_PHOTOS) {
            boolean hover = PhoneDraw.inside(mouseX, mouseY, x, y, size, size);
            PhoneDraw.rawRoundedRect(g, x, y, x + size, y + size, 4, hover ? PhoneTheme.cardHover() : PhoneTheme.field());
            PhoneDraw.centeredText(g, "+", x + size / 2f, y + 9, PhoneTheme.accent(), 1.2f, false);
            hits.add(new Hit(x, y, size, size, picker::open));
            PhoneDraw.text(g, PhoneLang.tr("feedback.screens", photos.size(), MAX_PHOTOS), x + size + 6, y + 11,
                    PhoneTheme.secondaryText(), 0.55f, false);
        }
        y += size + 6;
        boolean ready = !sending && text.text().strip().length() >= 10;
        PhoneDraw.button(g, 8, y, w - 16, 18, PhoneLang.tr(sending ? "feedback.sending" : "feedback.send"), mouseX, mouseY,
                ready ? PhoneDraw.ButtonStyle.PRIMARY : PhoneDraw.ButtonStyle.NORMAL);
        if (ready) {
            hits.add(new Hit(8, y, w - 16, 18, this::send));
        }
        y += 22;
        if (!notice.isEmpty()) {
            PhoneDraw.wrapped(g, notice, 8, y, w - 16, noticeError ? PhoneTheme.danger() : PhoneTheme.online(), 0.6f, false);
        } else if (!sending && text.text().strip().length() < 10) {
            PhoneDraw.wrapped(g, PhoneLang.tr("feedback.too_short"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.55f, false);
        }
    }

    private void send() {
        sending = true;
        notice = "";
        String kindName = KINDS[kind];
        String message = text.text().strip();
        List<PhotoStore.Photo> shots = List.copyOf(photos);
        Minecraft mc = Minecraft.getInstance();
        String player = mc.getUser().getName();
        ClientFiles.IO.execute(() -> {
            String error = null;
            try {
                JsonObject body = new JsonObject();
                body.addProperty("kind", kindName);
                body.addProperty("text", message);
                body.addProperty("player", player);
                body.addProperty("mod", BlockPhoneFabric.VERSION);
                body.addProperty("minecraft", net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft")
                        .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
                body.addProperty("lang", dev.blockphone.fabric.client.config.PhoneSettings.get().language);
                JsonArray images = new JsonArray();
                for (PhotoStore.Photo photo : shots) {
                    // Скриншот — JPEG до 1600 px и ~1,5 МБ.
                    var picture = ImageOps.downscale(ImageOps.read(photo.file()), 1600, 1600);
                    byte[] jpeg = ImageUploads.encode(picture, 1_500_000);
                    if (jpeg != null) {
                        images.add(Base64.getEncoder().encodeToString(jpeg));
                    }
                }
                body.add("images", images);
                HttpRequest request = HttpRequest.newBuilder(URI.create(AppStore.PUBLISH_URL + "/feedback")).timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    String reason = response.body();
                    try {
                        reason = JsonParser.parseString(reason).getAsJsonObject().get("error").getAsString();
                    } catch (RuntimeException ignored) {
                        // как есть
                    }
                    error = response.statusCode() == 429 ? PhoneLang.tr("feedback.too_often")
                            : response.statusCode() == 404 ? PhoneLang.tr("feedback.off") : reason;
                }
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Feedback not sent", e);
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String result = error;
            mc.execute(() -> {
                sending = false;
                noticeError = result != null;
                notice = result == null ? PhoneLang.tr("feedback.sent") : PhoneLang.tr("feedback.failed", result);
                if (result == null) {
                    text.setText("");
                    photos.clear();
                }
            });
        });
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (picker.isOpen()) {
            return picker.mouseClicked(x, y, button);
        }
        if (text.mouseClicked(x, y, button)) {
            return true;
        }
        text.setFocused(false);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        return picker.isOpen() && picker.mouseScrolled(amount);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return text.isFocused() && text.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return text.isFocused() && text.charTyped(event);
    }
}
