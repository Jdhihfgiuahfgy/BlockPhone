package dev.blockphone.fabric.client.screens;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.phone.PhoneScreen;
import dev.blockphone.fabric.client.photo.ImageOps;
import dev.blockphone.fabric.client.photo.ImageUploads;
import dev.blockphone.fabric.client.photo.Picture;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Трансляция экрана телефона на экран в мире. У владельца: пока есть включённый экран с трансляцией и телефон виден (открыт
 * или «на ходу» в углу — так его можно «свернуть», картинка идёт дальше), раз в 0,25–1,5 с снимается кадр окна, из него
 * вырезается экран телефона, уменьшается и сжимается в JPEG (до ~11 КБ) → кусками по 6 КБ всем, той же дорогой, что
 * экраны (через плагин реже: у него предел 40 сообщений за 5 с). Телефон закрыт — на экране остаётся последний кадр.
 * У всех — текстура кадра на игрока. Только поток рендера (сжатие и разбор — в фоне).
 */
public final class PhoneMirror {
    private static final int TARGET_W = 200;
    private static final int MAX_JPEG = 11_000;
    private static final int CHUNK = 6000;
    /** Кадр без изменений всё равно повторяется раз в 3 с (для новичков). */
    private static final long REPEAT_MS = 3000;

    private static long lastCapture;
    private static boolean capturing;
    private static int lastHash;
    private static long lastSent;
    private static int frameId;
    private static int sentFrames;
    /** DevShots: свои кадры разбираются как чужие (проверка JPEG и кусков без второго игрока). */
    public static volatile boolean debugLoopback;

    /** Кадр игрока: текстура и размер. */
    private static final class Frame {
        DynamicTexture texture;
        Identifier id;
        int width;
        int height;
        long updated;
        int assembling = -1;
        String[] parts = new String[0];
    }

    private static final Map<String, Frame> FRAMES = new HashMap<>();

    /** Есть ли у владельца включённый экран с трансляцией. */
    private static boolean wanted() {
        for (ScreenData.Screen s : ScreenStore.data().screens) {
            if (ScreenStore.data().screenOn(s) && s.mirror) {
                return true;
            }
        }
        return false;
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            clear();
            return;
        }
        if (!wanted() || capturing) {
            return;
        }
        long now = System.currentTimeMillis();
        long interval = ScreenSync.viaPlugin() ? 1500 : 250;
        float[] rect = PhoneScreen.shownScreenRect();
        if (rect == null || now - lastCapture < interval) {
            return;
        }
        lastCapture = now;
        capturing = true;
        double gui = mc.getWindow().getGuiScale();
        int x = (int) Math.round(rect[0] * gui);
        int y = (int) Math.round(rect[1] * gui);
        int w = (int) Math.round(rect[2] * gui);
        int h = (int) Math.round(rect[3] * gui);
        Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
            try (image) {
                Picture picture = crop(image, x, y, w, h);
                if (picture != null) {
                    show(mc.getUser().getName(), picture);
                    send(picture);
                }
            } catch (RuntimeException e) {
                BlockPhoneFabric.LOGGER.debug("[screens] mirror capture: {}", e.toString());
            } finally {
                capturing = false;
            }
        });
    }

    /** Вырезать прямоугольник и уменьшить до ширины TARGET_W (среднее по клеткам — текст читается). */
    private static @Nullable Picture crop(NativeImage image, int x, int y, int w, int h) {
        int iw = image.getWidth();
        int ih = image.getHeight();
        x = Math.max(0, x);
        y = Math.max(0, y);
        w = Math.min(w, iw - x);
        h = Math.min(h, ih - y);
        if (w < 8 || h < 8) {
            return null;
        }
        int[] src = image.getPixels();
        int tw = Math.min(w, w > h ? Math.round(TARGET_W * 2.1f) : TARGET_W);
        int th = Math.max(1, Math.round(h * tw / (float) w));
        int[] out = new int[tw * th];
        for (int ty = 0; ty < th; ty++) {
            int y0 = y + ty * h / th;
            int y1 = Math.max(y0 + 1, y + (ty + 1) * h / th);
            for (int tx = 0; tx < tw; tx++) {
                int x0 = x + tx * w / tw;
                int x1 = Math.max(x0 + 1, x + (tx + 1) * w / tw);
                int r = 0;
                int g = 0;
                int b = 0;
                int n = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int c = src[sy * iw + sx];
                        r += (c >> 16) & 255;
                        g += (c >> 8) & 255;
                        b += c & 255;
                        n++;
                    }
                }
                out[ty * tw + tx] = 0xFF000000 | (r / n) << 16 | (g / n) << 8 | (b / n);
            }
        }
        return new Picture(tw, th, out);
    }

    private static void send(Picture picture) {
        long now = System.currentTimeMillis();
        int hash = Arrays.hashCode(picture.argb());
        if (hash == lastHash && now - lastSent < REPEAT_MS) {
            return; // ничего не поменялось
        }
        lastHash = hash;
        lastSent = now;
        int id = ++frameId;
        ClientFiles.IO.execute(() -> {
            try {
                byte[] jpeg = ImageUploads.encode(picture, MAX_JPEG);
                if (jpeg == null) {
                    return;
                }
                int parts = (jpeg.length + CHUNK - 1) / CHUNK;
                String[] payloads = new String[parts];
                for (int i = 0; i < parts; i++) {
                    JsonObject o = new JsonObject();
                    o.addProperty("t", "pm");
                    o.addProperty("f", id);
                    o.addProperty("i", i);
                    o.addProperty("n", parts);
                    o.addProperty("d", Base64.getEncoder().encodeToString(
                            Arrays.copyOfRange(jpeg, i * CHUNK, Math.min(jpeg.length, (i + 1) * CHUNK))));
                    payloads[i] = o.toString();
                }
                Minecraft.getInstance().execute(() -> {
                    for (String p : payloads) {
                        ScreenSync.sendAudio(p);
                        if (debugLoopback) {
                            onFrame("loopback", com.google.gson.JsonParser.parseString(p).getAsJsonObject());
                        }
                    }
                    sentFrames++;
                });
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.debug("[screens] mirror encode: {}", e.toString());
            }
        });
    }

    /** Кусок кадра другого игрока. */
    static void onFrame(String from, JsonObject o) {
        String key = from.toLowerCase(Locale.ROOT);
        Frame frame = FRAMES.computeIfAbsent(key, k -> new Frame());
        int id = o.get("f").getAsInt();
        int i = o.get("i").getAsInt();
        int n = o.get("n").getAsInt();
        if (n <= 0 || n > 8 || i < 0 || i >= n) {
            return;
        }
        if (frame.assembling != id) {
            frame.assembling = id;
            frame.parts = new String[n];
        }
        frame.parts[i] = o.get("d").getAsString();
        for (String p : frame.parts) {
            if (p == null) {
                return;
            }
        }
        String[] parts = frame.parts;
        frame.assembling = -1;
        ClientFiles.IO.execute(() -> {
            try {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                for (String p : parts) {
                    bytes.write(Base64.getDecoder().decode(p));
                }
                BufferedImage image = ImageOps.readImage(bytes.toByteArray(), "mirror");
                if (image.getWidth() > 1024 || image.getHeight() > 1024) {
                    return;
                }
                int w = image.getWidth();
                int h = image.getHeight();
                int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
                Picture picture = new Picture(w, h, argb);
                Minecraft.getInstance().execute(() -> show(from, picture));
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.debug("[screens] mirror frame from {}: {}", from, e.toString());
            }
        });
    }

    /** Новый кадр игрока → его текстура. */
    private static void show(String owner, Picture picture) {
        String key = owner.toLowerCase(Locale.ROOT);
        Frame frame = FRAMES.computeIfAbsent(key, k -> new Frame());
        Minecraft mc = Minecraft.getInstance();
        if (frame.texture == null || frame.width != picture.width() || frame.height != picture.height()) {
            if (frame.id != null) {
                mc.getTextureManager().release(frame.id);
            }
            frame.width = picture.width();
            frame.height = picture.height();
            frame.texture = new DynamicTexture(() -> "BlockPhone mirror", ImageOps.toNativeImage(picture));
            frame.id = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "dynamic/mirror/" + Integer.toHexString(key.hashCode()) + "_" + System.nanoTime());
            mc.getTextureManager().register(frame.id, frame.texture);
        } else {
            NativeImage pixels = frame.texture.getPixels();
            if (pixels != null) {
                for (int y = 0; y < picture.height(); y++) {
                    for (int x = 0; x < picture.width(); x++) {
                        pixels.setPixel(x, y, picture.argb()[y * picture.width() + x]);
                    }
                }
                frame.texture.upload();
            }
        }
        frame.updated = System.currentTimeMillis();
    }

    /** Текстура трансляции игрока и её размер (null — кадров ещё не было). */
    static @Nullable Identifier texture(String owner) {
        Frame f = FRAMES.get(owner.toLowerCase(Locale.ROOT));
        return f == null || f.id == null ? null : f.id;
    }

    /** Ширина / высота кадра игрока (0 — нет). */
    static float aspect(String owner) {
        Frame f = FRAMES.get(owner.toLowerCase(Locale.ROOT));
        return f == null || f.height == 0 ? 0 : f.width / (float) f.height;
    }

    static String debugStatus() {
        StringBuilder sb = new StringBuilder("sent=" + sentFrames + " frames:");
        FRAMES.forEach((k, f) -> sb.append(' ').append(k).append('=').append(f.width).append('x').append(f.height));
        return sb.toString();
    }

    static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (Frame f : FRAMES.values()) {
            if (f.id != null) {
                mc.getTextureManager().release(f.id);
            }
        }
        FRAMES.clear();
        capturing = false;
    }

    private PhoneMirror() {
    }
}
