package dev.blockphone.fabric.client.apps.telegram;

import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.telegram.Telegram;
import dev.blockphone.fabric.client.telegram.VideoPlayer;
import it.tdlight.jni.TdApi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Просмотр фото, видео, GIF и «кружочков» Telegram на весь экран телефона (поверх приложения): листание альбома,
 * видео со звуком (пауза — нажатие, перемотка — полоска), поворот набок, подпись. Закрыть — «×» или «назад».
 */
final class TgMediaViewer {
    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private final List<Hit> hits = new ArrayList<>();
    private List<TdApi.Message> items = List.of();
    private int index;
    private @Nullable VideoPlayer player;
    private boolean landscape;
    private boolean open;

    boolean isOpen() {
        return open;
    }

    boolean landscape() {
        return open && landscape;
    }

    void open(List<TdApi.Message> messages, int start) {
        List<TdApi.Message> list = new ArrayList<>();
        for (TdApi.Message m : messages) {
            if (viewable(m)) {
                list.add(m);
            }
        }
        if (list.isEmpty()) {
            return;
        }
        items = list;
        index = Math.max(0, Math.min(list.size() - 1, start));
        open = true;
        landscape = false;
        stop();
    }

    static boolean viewable(TdApi.Message m) {
        return m.content instanceof TdApi.MessagePhoto || m.content instanceof TdApi.MessageVideo
                || m.content instanceof TdApi.MessageAnimation || m.content instanceof TdApi.MessageVideoNote;
    }

    void close() {
        open = false;
        stop();
    }

    private void stop() {
        if (player != null) {
            player.close();
            player = null;
        }
    }

    private void go(int delta) {
        int next = index + delta;
        if (next >= 0 && next < items.size()) {
            index = next;
            stop();
        }
    }

    private static @Nullable TdApi.File videoFile(TdApi.MessageContent c) {
        return switch (c) {
            case TdApi.MessageVideo v -> v.video.video;
            case TdApi.MessageAnimation a -> a.animation.animation;
            case TdApi.MessageVideoNote n -> n.videoNote.video;
            default -> null;
        };
    }

    void render(GuiGraphics g, int mx, int my, int w, int h) {
        hits.clear();
        g.fill(0, 0, w, h, 0xFF000000);
        TdApi.Message m = items.get(index);
        TdApi.File video = videoFile(m.content);
        boolean note = m.content instanceof TdApi.MessageVideoNote;
        int areaTop = 20;
        int areaH = h - areaTop - (video != null ? 28 : 6);
        if (video == null) {
            renderPhoto(g, (TdApi.MessagePhoto) m.content, w, areaTop, areaH);
        } else {
            Path path = Telegram.localFile(video);
            if (path == null) {
                renderThumb(g, m.content, w, areaTop, areaH, note);
                PhoneDraw.centeredText(g, PhoneDraw.ellipsize(tr("telegram.viewer.loading", Math.round(Telegram.progress(video) * 100)), w - 10,
                        0.75f), w / 2f, areaTop + areaH / 2f + 30, 0xFFFFFFFF, 0.75f, true);
            } else {
                if (player == null) {
                    player = new VideoPlayer(path, 640, m.content instanceof TdApi.MessageAnimation);
                }
                Identifier frame = player.frame();
                if (frame != null) {
                    if (note) {
                        int size = Math.min(w - 20, areaH);
                        int x = (w - size) / 2;
                        int y = areaTop + (areaH - size) / 2;
                        PhoneDraw.textureCover(g, frame, x, y, size, size, player.width(), player.height());
                        PhoneDraw.roundCornersMask(g, x, y, x + size, y + size, size / 2, 0xFF000000);
                    } else {
                        fit(g, frame, player.width(), player.height(), 0, areaTop, w, areaH);
                    }
                } else {
                    renderThumb(g, m.content, w, areaTop, areaH, note);
                }
                if (player.error() != null && frame == null) {
                    PhoneDraw.wrapped(g, tr("telegram.viewer.unsupported"), 10, areaTop + areaH / 2f + 30, w - 20, 0xFFFF9090, 0.7f, true);
                }
                hits.add(new Hit(0, areaTop, w, areaH, player::togglePause));
                renderVideoBar(g, mx, my, w, h);
            }
        }
        // Верхняя полоса: закрыть, номер в альбоме, поворот.
        g.fill(0, 0, w, 18, 0x90000000);
        button(g, 3, 2, 16, 14, "×", mx, my, this::close);
        if (items.size() > 1) {
            PhoneDraw.centeredText(g, (index + 1) + " / " + items.size(), w / 2f, 5, 0xFFFFFFFF, 0.75f, false);
        }
        button(g, w - 19, 2, 16, 14, "⟳", mx, my, () -> landscape = !landscape);
        if (index > 0) {
            button(g, 3, areaTop + areaH / 2 - 9, 14, 18, "‹", mx, my, () -> go(-1));
        }
        if (index < items.size() - 1) {
            button(g, w - 17, areaTop + areaH / 2 - 9, 14, 18, "›", mx, my, () -> go(1));
        }
        String caption = switch (m.content) {
            case TdApi.MessagePhoto p -> p.caption.text;
            case TdApi.MessageVideo v -> v.caption.text;
            default -> "";
        };
        if (!caption.isEmpty()) {
            int ch = Math.min(40, PhoneDraw.wrappedHeight(caption, w - 16, 0.7f));
            int cy = h - (video != null ? 30 : 8) - ch - 4;
            g.fill(0, cy - 3, w, cy + ch + 3, 0x90000000);
            g.enableScissor(0, cy - 3, w, cy + ch + 3);
            PhoneDraw.wrapped(g, caption, 8, cy, w - 16, 0xFFFFFFFF, 0.7f, false);
            g.disableScissor();
        }
    }

    private void renderPhoto(GuiGraphics g, TdApi.MessagePhoto p, int w, int top, int areaH) {
        TdApi.PhotoSize big = null;
        for (TdApi.PhotoSize s : p.photo.sizes) {
            if (big == null || s.width > big.width) {
                big = s;
            }
        }
        Path path = big != null ? Telegram.localFile(big.photo) : null;
        PhotoTextures.Image image = path != null ? PhotoTextures.preview(new PhotoStore.Photo(path, 0), 1280) : null;
        if (image != null) {
            fit(g, image.id(), image.width(), image.height(), 0, top, w, areaH);
        } else {
            renderThumb(g, p, w, top, areaH, false);
            if (big != null) {
                PhoneDraw.centeredText(g, tr("telegram.viewer.loading", Math.round(Telegram.progress(big.photo) * 100)), w / 2f,
                        top + areaH / 2f - 3, 0xFFFFFFFF, 0.75f, true);
            }
        }
    }

    /** Пока качается — маленькая картинка (если уже есть). */
    private void renderThumb(GuiGraphics g, TdApi.MessageContent c, int w, int top, int areaH, boolean round) {
        TdApi.File file = switch (c) {
            case TdApi.MessagePhoto p -> p.photo.sizes.length > 0 ? p.photo.sizes[0].photo : null;
            case TdApi.MessageVideo v -> v.video.thumbnail != null ? v.video.thumbnail.file : null;
            case TdApi.MessageAnimation a -> a.animation.thumbnail != null ? a.animation.thumbnail.file : null;
            case TdApi.MessageVideoNote n -> n.videoNote.thumbnail != null ? n.videoNote.thumbnail.file : null;
            default -> null;
        };
        Path path = Telegram.localFile(file);
        PhotoTextures.Image image = path != null ? PhotoTextures.preview(new PhotoStore.Photo(path, 0), 320) : null;
        if (image != null) {
            fit(g, image.id(), image.width(), image.height(), 0, top, w, areaH);
            g.fill(0, top, w, top + areaH, 0x60000000);
        }
    }

    private void renderVideoBar(GuiGraphics g, int mx, int my, int w, int h) {
        VideoPlayer p = player;
        int y = h - 24;
        g.fill(0, y - 4, w, h, 0x90000000);
        button(g, 6, y, 18, 16, p.paused() ? "▶" : "❚❚", mx, my, p::togglePause);
        double dur = Math.max(0.1, p.duration());
        double pos = Math.min(dur, p.position());
        int bx = 30;
        int bw = w - bx - 44;
        g.fill(bx, y + 7, bx + bw, y + 9, 0x60FFFFFF);
        g.fill(bx, y + 7, bx + (int) (bw * pos / dur), y + 9, 0xFFFFFFFF);
        PhoneDraw.rawRoundedRect(g, bx + (int) (bw * pos / dur) - 2, y + 5, bx + (int) (bw * pos / dur) + 3, y + 11, 2, 0xFFFFFFFF);
        hits.add(new Hit(bx - 2, y, bw + 4, 16, null));
        PhoneDraw.rightText(g, time(pos) + " / " + time(dur), w - 4, y + 5, 0xFFDDDDDD, 0.55f);
    }

    private static String time(double s) {
        int t = (int) s;
        return t / 60 + ":" + String.format("%02d", t % 60);
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, Runnable action) {
        boolean hover = PhoneDraw.inside(mx, my, x, y, w, h);
        PhoneDraw.rawRoundedRect(g, x, y, x + w, y + h, 4, hover ? 0x50FFFFFF : 0x30FFFFFF);
        PhoneDraw.centeredText(g, label, x + w / 2f, y + h / 2f - 3.5f, 0xFFFFFFFF, 0.8f, false);
        hits.add(new Hit(x, y, w, h, action));
    }

    private static void fit(GuiGraphics g, Identifier id, int tw, int th, int x, int y, int w, int h) {
        float k = Math.min(w / (float) tw, h / (float) th);
        int dw = Math.max(1, Math.round(tw * k));
        int dh = Math.max(1, Math.round(th * k));
        g.pose().pushMatrix();
        g.pose().translate(x + (w - dw) / 2f, y + (h - dh) / 2f);
        g.pose().scale(dw / (float) tw, dh / (float) th);
        PhoneDraw.texture(g, id, 0, 0, tw, th, tw, th);
        g.pose().popMatrix();
    }

    boolean mouseClicked(double x, double y, int w, int h) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                if (hit.action() == null && player != null) {
                    // полоска перемотки
                    double t = Math.max(0, Math.min(1, (x - hit.x() - 2) / (hit.w() - 4)));
                    player.seek(t * player.duration());
                } else if (hit.action() != null) {
                    hit.action().run();
                }
                return true;
            }
        }
        return true;
    }

    private static String tr(String key, Object... args) {
        return TgView.tr(key, args);
    }
}
