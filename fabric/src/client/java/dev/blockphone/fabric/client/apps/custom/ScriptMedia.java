package dev.blockphone.fabric.client.apps.custom;

import dev.blockphone.common.apps.AppPermission;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.photo.ImageOps;
import dev.blockphone.fabric.client.photo.ImageUploads;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.photo.Picture;
import dev.blockphone.fabric.client.photo.StickerMaker;
import dev.blockphone.fabric.client.storage.ClientFiles;
import dev.blockphone.fabric.client.telegram.OggOpus;
import dev.blockphone.fabric.client.ui.PhotoPicker;
import dev.blockphone.fabric.client.voice.VoiceMessages;
import dev.blockphone.fabric.client.voice.VoiceRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.awt.image.BufferedImage;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Фото, картинки по адресу и голос для скриптов своих приложений (часть {@link ScriptHost}):
 * <ul>
 *   <li>{@code photos.pick(cb)} — игрок сам выбирает фото в галерее (разрешение photos); приложение получает только
 *   «ручку» {photo = n}: показать ({@code photos.draw}) и отправить на свой сайт ({@code http.upload});</li>
 *   <li>{@code gfx.urlImage(url, …)} — картинка с сайта из "hosts" (скачивание и текстура — здесь, LRU 48);</li>
 *   <li>{@code mic.*} — запись голоса (разрешение microphone; значок записи рисует телефон; при сворачивании запись
 *   прекращается) → «ручка» {clip = n, duration = мс} в OGG Opus 16 кГц;</li>
 *   <li>{@code audio.*} — проигрывание голосовых OGG Opus с сайта или своей записи (общий проигрыватель голосовых),
 *   {@code audio.volume} — усиление 0..5;</li>
 *   <li>{@code photos.sticker} — стикер из выбранного фото (квадрат, форма, обводка, прозрачный фон) → «ручка»
 *   {sticker = n}: {@code photos.drawSticker}, отправка PNG через {@code http.upload}.</li>
 * </ul>
 * Только поток рендера.
 */
final class ScriptMedia {
    private static final int MAX_HANDLES = 32;
    private static final int MAX_REMOTE = 48;
    private static final int MAX_IMAGE_BYTES = 4 << 20;
    private static final int MAX_AUDIO_BYTES = 4 << 20;
    private static final int MAX_RECORD_SECONDS = 300;
    /** Ключи проигрывателя голосовых для приложений — далеко от номеров голосовых сервера. */
    private static final long AUDIO_KEY_BASE = 0x5A00_0000_0000_0000L;

    private final ScriptHost host;

    // --- фото, выбранные игроком ---
    private final Map<Integer, PhotoStore.Photo> photos = new LinkedHashMap<>();
    private int nextHandle = 1;
    private @Nullable PhotoPicker picker;
    private @Nullable LuaValue pickCallback;
    private boolean picked;

    // --- картинки по адресу ---
    private static final class Remote {
        @Nullable PhotoTextures.Image image;
        boolean failed;
    }

    private final LinkedHashMap<String, Remote> remote = new LinkedHashMap<>(16, 0.75f, true);

    // --- голос ---
    private record Clip(byte[] ogg, short[] pcm, long durationMs) {
    }

    private final Map<Integer, Clip> clips = new LinkedHashMap<>();
    private @Nullable VoiceRecorder recorder;
    private final Map<String, short[]> audioCache = new LinkedHashMap<>(8, 0.75f, true);
    private final Map<String, Boolean> audioLoading = new LinkedHashMap<>();
    private final Map<String, String> audioFailed = new LinkedHashMap<>();
    private float volume = 1f;
    private long lastAudioKey = -1;

    // --- стикеры из фото ---
    private static final class Sticker {
        final Picture picture;
        @Nullable PhotoTextures.Image texture;

        Sticker(Picture picture) {
            this.picture = picture;
        }
    }

    private record StickerJob(PhotoStore.Photo photo, int side, StickerMaker.Shape shape, boolean outline, float zoom,
                              float x, float y, LuaValue cb) {
    }

    private final Map<Integer, Sticker> stickers = new LinkedHashMap<>();
    private @Nullable StickerJob stickerPending;
    private boolean stickerBusy;
    /** Последнее прочитанное фото (по нему двигают и приближают — не читать файл заново). */
    private @Nullable PhotoStore.Photo sourcePhoto;
    private @Nullable Picture sourcePicture;

    ScriptMedia(ScriptHost host) {
        this.host = host;
    }

    // --- Lua ---

    void install(LuaTable G) {
        LuaTable photosT = new LuaTable();
        photosT.set("pick", host.fn(a -> {
            host.need(AppPermission.PHOTOS);
            if (picker != null && picker.isOpen()) {
                throw new LuaError("photos.pick: the picker is already open");
            }
            pickCallback = a.checkfunction(1);
            picked = false;
            picker = new PhotoPicker(photo -> {
                picked = true;
                picker.close();
                LuaValue cb = pickCallback;
                pickCallback = null;
                host.call(cb, photoHandle(photo));
            }, true, null);
            picker.open();
            return LuaValue.NONE;
        }));
        photosT.set("draw", host.fn(a -> {
            host.need(AppPermission.PHOTOS);
            PhotoStore.Photo photo = photos.get(handle(a.arg(1), "photo"));
            if (photo == null) {
                return LuaValue.FALSE;
            }
            PhotoTextures.Image image = PhotoTextures.preview(photo, 512);
            if (image == null) {
                return LuaValue.FALSE;
            }
            draw(image, ScriptHost.i(a, 2), ScriptHost.i(a, 3), ScriptHost.i(a, 4), ScriptHost.i(a, 5), a.optjstring(6, "cover"));
            return LuaValue.TRUE;
        }));
        photosT.set("forget", host.fn(a -> {
            if (a.arg(1).istable() && !a.arg(1).get("sticker").isnil()) {
                releaseSticker(stickers.remove(handle(a.arg(1), "sticker")));
            } else {
                photos.remove(handle(a.arg(1), "photo"));
            }
            return LuaValue.NONE;
        }));
        photosT.set("sticker", host.fn(a -> {
            host.need(AppPermission.PHOTOS);
            PhotoStore.Photo photo = photos.get(handle(a.arg(1), "photo"));
            if (photo == null) {
                throw new LuaError("photos.sticker: unknown photo");
            }
            LuaTable o = a.opttable(2, new LuaTable());
            StickerMaker.Shape shape = switch (o.get("shape").optjstring("rounded")) {
                case "square" -> StickerMaker.Shape.SQUARE;
                case "circle" -> StickerMaker.Shape.CIRCLE;
                default -> StickerMaker.Shape.ROUNDED;
            };
            stickerPending = new StickerJob(photo, Math.max(32, Math.min(512, o.get("size").optint(512))), shape,
                    o.get("outline").optboolean(true), (float) Math.max(1, Math.min(4, o.get("zoom").optdouble(1))),
                    (float) Math.max(-1, Math.min(1, o.get("x").optdouble(0))),
                    (float) Math.max(-1, Math.min(1, o.get("y").optdouble(0))), a.checkfunction(3));
            runStickerJob();
            return LuaValue.NONE;
        }));
        photosT.set("drawSticker", host.fn(a -> {
            Sticker st = stickers.get(handle(a.arg(1), "sticker"));
            if (st == null) {
                return LuaValue.FALSE;
            }
            if (st.texture == null) {
                st.texture = PhotoTextures.upload(st.picture, "app");
            }
            draw(st.texture, ScriptHost.i(a, 2), ScriptHost.i(a, 3), ScriptHost.i(a, 4), ScriptHost.i(a, 5), "fit");
            return LuaValue.TRUE;
        }));
        G.set("photos", photosT);

        LuaTable mic = new LuaTable();
        mic.set("start", host.fn(a -> {
            host.need(AppPermission.MICROPHONE);
            if (recorder != null && !recorder.finished()) {
                return LuaValue.FALSE;
            }
            int seconds = Math.max(1, Math.min(MAX_RECORD_SECONDS, a.optint(1, 60)));
            VoiceMessages.stop();
            recorder = VoiceRecorder.start(seconds);
            return LuaValue.TRUE;
        }));
        mic.set("recording", host.fn(a -> LuaValue.valueOf(recording())));
        mic.set("level", host.fn(a -> LuaValue.valueOf(recorder != null ? recorder.level() : 0)));
        mic.set("time", host.fn(a -> LuaValue.valueOf(recorder != null ? recorder.recordedMs() : 0)));
        mic.set("error", host.fn(a -> recorder != null && recorder.error() != null
                ? LuaValue.valueOf(PhoneLang.tr(recorder.error())) : LuaValue.NIL));
        mic.set("cancel", host.fn(a -> {
            stopRecording();
            return LuaValue.NONE;
        }));
        mic.set("stop", host.fn(a -> {
            host.need(AppPermission.MICROPHONE);
            LuaValue cb = a.checkfunction(1);
            VoiceRecorder r = recorder;
            recorder = null;
            if (r == null) {
                host.call(cb, LuaValue.NIL, LuaValue.valueOf("not recording"));
                return LuaValue.NONE;
            }
            r.stop();
            waitAndEncode(r, cb);
            return LuaValue.NONE;
        }));
        G.set("mic", mic);

        LuaTable audio = new LuaTable();
        audio.set("play", host.fn(a -> {
            LuaValue what = a.arg(1);
            if (what.istable()) {
                Clip clip = clips.get(handle(what, "clip"));
                if (clip != null) {
                    playPcm(clipKey(handle(what, "clip")), clip.pcm());
                }
                return LuaValue.NONE;
            }
            String url = what.checkjstring();
            short[] pcm = audioCache.get(url);
            if (pcm != null) {
                playPcm(urlKey(url), pcm);
                return LuaValue.NONE;
            }
            if (audioLoading.containsKey(url)) {
                return LuaValue.NONE;
            }
            audioLoading.put(url, true);
            audioFailed.remove(url);
            host.fetchBytes(url, MAX_AUDIO_BYTES, bytes -> ClientFiles.IO.execute(() -> {
                short[] decoded;
                String error = null;
                try {
                    decoded = OggOpus.decode(bytes);
                } catch (Exception e) {
                    decoded = null;
                    error = "bad audio";
                }
                short[] result = decoded;
                String err = error;
                Minecraft.getInstance().execute(() -> {
                    audioLoading.remove(url);
                    if (result == null) {
                        audioFailed.put(url, err);
                        return;
                    }
                    audioCache.put(url, result);
                    while (audioCache.size() > 8) {
                        audioCache.remove(audioCache.keySet().iterator().next());
                    }
                    playPcm(urlKey(url), result);
                });
            }), error -> {
                audioLoading.remove(url);
                audioFailed.put(url, error);
            });
            return LuaValue.NONE;
        }));
        audio.set("stop", host.fn(a -> {
            VoiceMessages.stop();
            return LuaValue.NONE;
        }));
        audio.set("state", host.fn(a -> {
            long key = audioKey(a.arg(1));
            String url = a.arg(1).isstring() ? a.arg(1).tojstring() : null;
            if (url != null && audioLoading.containsKey(url)) {
                return LuaValue.valueOf("loading");
            }
            if (url != null && audioFailed.containsKey(url)) {
                return LuaValue.valueOf("error");
            }
            if (VoiceMessages.playing(key)) {
                return LuaValue.valueOf("playing");
            }
            return LuaValue.valueOf(VoiceMessages.active(key) ? "paused" : "idle");
        }));
        audio.set("volume", host.fn(a -> {
            if (a.arg(1).isnumber()) {
                volume = (float) Math.max(0, Math.min(5, a.arg(1).todouble()));
                VoiceMessages.setBoost(lastAudioKey, volume);
            }
            return LuaValue.valueOf(volume);
        }));
        audio.set("progress", host.fn(a -> LuaValue.valueOf(VoiceMessages.progress(audioKey(a.arg(1))))));
        G.set("audio", audio);
    }

    /** gfx.urlImage(url, x, y, w, h[, "cover"|"fit"]) → true, если картинка уже есть и нарисована. */
    LuaValue drawUrlImage(Varargs a) {
        String url = a.checkjstring(1);
        Remote r = remote(url);
        if (r.image == null) {
            return LuaValue.FALSE;
        }
        draw(r.image, ScriptHost.i(a, 2), ScriptHost.i(a, 3), ScriptHost.i(a, 4), ScriptHost.i(a, 5), a.optjstring(6, "cover"));
        return LuaValue.TRUE;
    }

    /** images.size(url) → ширина, высота (или nil, пока грузится); images.failed(url) → причина или nil. */
    LuaTable imagesTable() {
        LuaTable t = new LuaTable();
        t.set("size", host.fn(a -> {
            Remote r = remote(a.checkjstring(1));
            return r.image == null ? LuaValue.NIL
                    : LuaValue.varargsOf(LuaValue.valueOf(r.image.width()), LuaValue.valueOf(r.image.height()));
        }));
        t.set("failed", host.fn(a -> LuaValue.valueOf(remote(a.checkjstring(1)).failed)));
        return t;
    }

    /** http.upload(url, ручка, {maxSide, maxBytes, headers}, cb) — отправить фото (JPEG) или запись голоса (OGG Opus). */
    LuaValue upload(Varargs a) {
        String url = a.checkjstring(1);
        host.checkedUri(url);
        LuaValue what = a.arg(2);
        LuaTable opts = a.opttable(3, new LuaTable());
        LuaValue cb = a.optfunction(4, null);
        LuaTable headers = new LuaTable();
        LuaValue userHeaders = opts.get("headers");
        if (userHeaders.istable()) {
            for (LuaValue k = userHeaders.next(LuaValue.NIL).arg1(); !k.isnil(); k = userHeaders.next(k).arg1()) {
                if (!k.tojstring().equalsIgnoreCase("content-type")) {
                    headers.set(k, userHeaders.get(k));
                }
            }
        }
        if (what.istable() && !what.get("clip").isnil()) {
            host.need(AppPermission.MICROPHONE);
            Clip clip = clips.get(handle(what, "clip"));
            if (clip == null) {
                throw new LuaError("http.upload: unknown recording");
            }
            headers.set("X-Duration-Ms", String.valueOf(clip.durationMs()));
            host.http("POST", url, clip.ogg(), "audio/ogg", cb, headers);
            return LuaValue.NONE;
        }
        host.need(AppPermission.PHOTOS);
        if (what.istable() && !what.get("sticker").isnil()) {
            Sticker st = stickers.get(handle(what, "sticker"));
            if (st == null) {
                throw new LuaError("http.upload: unknown sticker");
            }
            uploadSticker(url, st.picture, Math.max(8 * 1024, Math.min(1 << 20, opts.get("maxBytes").optint(256 * 1024))),
                    headers, cb);
            return LuaValue.NONE;
        }
        PhotoStore.Photo photo = photos.get(handle(what, "photo"));
        if (photo == null) {
            throw new LuaError("http.upload: unknown photo");
        }
        int maxSide = Math.max(64, Math.min(4096, opts.get("maxSide").optint(1280)));
        int maxBytes = Math.max(16 * 1024, Math.min(4 << 20, opts.get("maxBytes").optint(600 * 1024)));
        ClientFiles.IO.execute(() -> {
            byte[] jpeg = null;
            int w = 0;
            int h = 0;
            try {
                Picture picture = ImageOps.downscale(ImageOps.read(photo.file()), maxSide, maxSide);
                w = picture.width();
                h = picture.height();
                jpeg = ImageUploads.encode(picture, maxBytes);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("[apps] photo encode failed", e);
            }
            byte[] body = jpeg;
            int width = w;
            int height = h;
            Minecraft.getInstance().execute(() -> {
                if (body == null) {
                    host.call(cb, LuaValue.FALSE, LuaValue.valueOf(0), LuaValue.valueOf("photo could not be encoded"));
                    return;
                }
                headers.set("X-Width", String.valueOf(width));
                headers.set("X-Height", String.valueOf(height));
                try {
                    host.http("POST", url, body, "image/jpeg", cb, headers);
                } catch (LuaError e) {
                    host.call(cb, LuaValue.FALSE, LuaValue.valueOf(0), LuaValue.valueOf(e.getMessage()));
                }
            });
        });
        return LuaValue.NONE;
    }

    // --- Телефон (CustomApp) ---

    boolean pickerOpen() {
        return picker != null && picker.isOpen();
    }

    void renderOverlay(GuiGraphics g, int mx, int my, int w, int h) {
        if (picker != null) {
            if (picker.isOpen()) {
                picker.render(g, mx, my, w, h);
            } else if (!picked && pickCallback != null) {
                // Игрок закрыл выбор, ничего не выбрав.
                LuaValue cb = pickCallback;
                pickCallback = null;
                host.call(cb, LuaValue.NIL);
            }
        }
        if (recording()) {
            // Значок записи: игрок всегда видит, что приложение слушает микрофон.
            boolean blink = System.currentTimeMillis() / 500 % 2 == 0;
            String label = "REC " + VoiceMessages.time(recorder.recordedMs());
            int lw = (int) PhoneDraw.width(label, 0.6f) + 12;
            PhoneDraw.rawRoundedRect(g, w - lw - 3, 3, w - 3, 13, 4, 0xD0000000);
            PhoneDraw.rawRoundedRect(g, w - lw + 1, 6, w - lw + 5, 10, 2, blink ? 0xFFFF3B30 : 0xFF802020);
            PhoneDraw.text(g, label, w - lw + 7, 5.5f, 0xFFFFFFFF, 0.6f, false);
        }
    }

    boolean mouseClicked(double x, double y, int button) {
        return picker != null && picker.isOpen() && picker.mouseClicked(x, y, button);
    }

    boolean mouseScrolled(double amount) {
        return picker != null && picker.isOpen() && picker.mouseScrolled(amount);
    }

    /** «Назад»: закрыть выбор фото. */
    boolean onBack() {
        if (picker != null && picker.isOpen()) {
            picker.close();
            return true;
        }
        return false;
    }

    /** Приложение свёрнуто: запись голоса прекращается (микрофон — только пока приложение на экране). */
    void onHide() {
        stopRecording();
        if (picker != null) {
            picker.close();
        }
    }

    void release() {
        stopRecording();
        remote.values().forEach(r -> {
            if (r.image != null) {
                PhotoTextures.release(r.image);
            }
        });
        remote.clear();
        photos.clear();
        stickers.values().forEach(this::releaseSticker);
        stickers.clear();
        sourcePicture = null;
        sourcePhoto = null;
        clips.clear();
        audioCache.clear();
    }

    // --- Внутреннее ---

    private void playPcm(long key, short[] pcm) {
        lastAudioKey = key;
        VoiceMessages.togglePcm(key, pcm, volume);
    }

    /** Собрать стикер в фоне; пока собирается прошлый, новые запросы заменяют друг друга (берётся последний). */
    private void runStickerJob() {
        if (stickerBusy || stickerPending == null) {
            return;
        }
        StickerJob job = stickerPending;
        stickerPending = null;
        stickerBusy = true;
        PhotoStore.Photo cachedPhoto = sourcePhoto;
        Picture cached = sourcePicture;
        ClientFiles.IO.execute(() -> {
            Picture src = cachedPhoto == job.photo() ? cached : null;
            Picture result = null;
            try {
                if (src == null) {
                    src = ImageOps.downscale(ImageOps.read(job.photo().file()), 1024, 1024);
                }
                result = StickerMaker.make(src, job.side(), job.shape(), job.outline(), job.zoom(), job.x(), job.y());
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("[apps] sticker failed", e);
            }
            Picture source = src;
            Picture sticker = result;
            Minecraft.getInstance().execute(() -> {
                stickerBusy = false;
                if (source != null) {
                    sourcePhoto = job.photo();
                    sourcePicture = source;
                }
                if (stickerPending == null) {
                    if (sticker == null) {
                        host.call(job.cb(), LuaValue.NIL, LuaValue.valueOf("photo could not be read"));
                    } else {
                        int id = nextHandle++;
                        stickers.put(id, new Sticker(sticker));
                        while (stickers.size() > 8) {
                            Integer eldest = stickers.keySet().iterator().next();
                            releaseSticker(stickers.remove(eldest));
                        }
                        LuaTable t = new LuaTable();
                        t.set("sticker", id);
                        t.set("size", sticker.width());
                        host.call(job.cb(), t);
                    }
                }
                runStickerJob();
            });
        });
    }

    private void releaseSticker(@Nullable Sticker st) {
        if (st != null && st.texture != null) {
            PhotoTextures.release(st.texture);
            st.texture = null;
        }
    }

    private void uploadSticker(String url, Picture sticker, int maxBytes, LuaTable headers, @Nullable LuaValue cb) {
        ClientFiles.IO.execute(() -> {
            byte[] data = null;
            Picture current = sticker;
            try {
                for (int attempt = 0; attempt < 8 && current.width() >= 32; attempt++) {
                    byte[] png = ImageUploads.png(current);
                    if (png.length <= maxBytes) {
                        data = png;
                        break;
                    }
                    int side = Math.round(current.width() * 0.85f);
                    current = ImageOps.downscale(current, side, side);
                }
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("[apps] sticker encode failed", e);
            }
            byte[] body = data;
            int side = current.width();
            Minecraft.getInstance().execute(() -> {
                if (body == null) {
                    host.call(cb, LuaValue.FALSE, LuaValue.valueOf(0), LuaValue.valueOf("sticker is too big"));
                    return;
                }
                headers.set("X-Width", String.valueOf(side));
                headers.set("X-Height", String.valueOf(side));
                try {
                    host.http("POST", url, body, "image/png", cb, headers);
                } catch (LuaError e) {
                    host.call(cb, LuaValue.FALSE, LuaValue.valueOf(0), LuaValue.valueOf(e.getMessage()));
                }
            });
        });
    }

    private boolean recording() {
        return recorder != null && !recorder.finished();
    }

    private void stopRecording() {
        if (recorder != null) {
            recorder.stop();
            recorder = null;
        }
    }

    private void waitAndEncode(VoiceRecorder r, LuaValue cb) {
        ClientFiles.IO.execute(() -> {
            long deadline = System.currentTimeMillis() + 3000;
            while (!r.finished() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            byte[] ogg = null;
            short[] pcm = null;
            String error = r.error() != null ? PhoneLang.tr(r.error()) : null;
            if (error == null) {
                try {
                    pcm = r.samples();
                    if (pcm.length < OggOpus.RATE / 5) {
                        error = "too short";
                    } else {
                        ogg = OggOpus.encode(pcm);
                    }
                } catch (Exception e) {
                    error = "encode failed";
                }
            }
            byte[] bytes = ogg;
            short[] samples = pcm;
            String err = error;
            Minecraft.getInstance().execute(() -> {
                if (bytes == null) {
                    host.call(cb, LuaValue.NIL, LuaValue.valueOf(err));
                    return;
                }
                int id = nextHandle++;
                clips.put(id, new Clip(bytes, samples, samples.length * 1000L / OggOpus.RATE));
                trim(clips);
                LuaTable t = new LuaTable();
                t.set("clip", id);
                t.set("duration", LuaValue.valueOf(samples.length * 1000L / OggOpus.RATE));
                t.set("size", bytes.length);
                host.call(cb, t);
            });
        });
    }

    private LuaTable photoHandle(PhotoStore.Photo photo) {
        int id = nextHandle++;
        photos.put(id, photo);
        trim(photos);
        LuaTable t = new LuaTable();
        t.set("photo", id);
        return t;
    }

    private static int handle(LuaValue v, String field) {
        if (!v.istable() || !v.get(field).isnumber()) {
            throw new LuaError("expected a " + field + " handle ({" + field + " = n})");
        }
        return v.get(field).toint();
    }

    private static <V> void trim(Map<Integer, V> map) {
        Iterator<Integer> it = map.keySet().iterator();
        while (map.size() > MAX_HANDLES && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private long urlKey(String url) {
        return AUDIO_KEY_BASE | (url.hashCode() & 0xFFFF_FFFFL) << 8;
    }

    private long clipKey(int id) {
        return AUDIO_KEY_BASE | 0xFF | (long) id << 8 & 0xFF_FFFF_FF00L;
    }

    private long audioKey(LuaValue v) {
        return v.istable() ? clipKey(handle(v, "clip")) : urlKey(v.checkjstring());
    }

    private Remote remote(String url) {
        Remote r = remote.get(url);
        if (r != null) {
            return r;
        }
        Remote created = new Remote();
        remote.put(url, created);
        while (remote.size() > MAX_REMOTE) {
            Map.Entry<String, Remote> eldest = remote.entrySet().iterator().next();
            if (eldest.getValue().image != null) {
                PhotoTextures.release(eldest.getValue().image);
            }
            remote.remove(eldest.getKey());
        }
        host.fetchBytes(url, MAX_IMAGE_BYTES, bytes -> ClientFiles.IO.execute(() -> {
            Picture picture = null;
            try {
                BufferedImage image = ImageOps.readImage(bytes, "app image");
                if (image != null) {
                    int w = image.getWidth();
                    int h = image.getHeight();
                    int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
                    if (!image.getColorModel().hasAlpha()) {
                        for (int i = 0; i < argb.length; i++) {
                            argb[i] |= 0xFF000000;
                        }
                    }
                    picture = ImageOps.downscale(new Picture(w, h, argb), 1024, 1024);
                }
            } catch (Exception e) {
                picture = null;
            }
            Picture result = picture;
            Minecraft.getInstance().execute(() -> {
                if (remote.get(url) != created) {
                    return;
                }
                if (result == null) {
                    created.failed = true;
                } else {
                    created.image = PhotoTextures.upload(result, "app");
                }
            });
        }), error -> created.failed = true);
        return created;
    }

    private void draw(PhotoTextures.Image image, int x, int y, int w, int h, String mode) {
        GuiGraphics g = host.gfx();
        if ("fit".equals(mode)) {
            float scale = Math.min(w / (float) image.width(), h / (float) image.height());
            int dw = Math.max(1, Math.round(image.width() * scale));
            int dh = Math.max(1, Math.round(image.height() * scale));
            PhoneDraw.texture(g, image.id(), x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, image.width(), image.height());
        } else {
            PhoneDraw.textureCover(g, image.id(), x, y, w, h, image.width(), image.height());
        }
    }
}
