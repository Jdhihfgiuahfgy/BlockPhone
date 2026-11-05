package dev.blockphone.fabric.client.telegram;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.photo.ImageOps;
import dev.blockphone.fabric.client.photo.Picture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.PictureWithMetadata;
import org.jcodec.codecs.aac.AACDecoder;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.AudioBuffer;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.jcodec.scale.ColorUtil;
import org.jcodec.scale.Transform;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Видео Telegram (MP4: H.264 + AAC) — для просмотрщика: видео декодирует JCodec в своём потоке (кадры уменьшаются до
 * maxSide), звук AAC — тоже JCodec (декодер JAAD) в другом потоке через javax.sound. Часы — по звуку (если он есть),
 * иначе по времени. Кадр — одна текстура, обновляется в потоке рендера ({@link #frame()}). Перемотка — перезапуск с места.
 * Не получилось декодировать (редкий профиль H.264) — {@link #error()}.
 */
public final class VideoPlayer implements AutoCloseable {
    private record Frame(Picture picture, double time) {
    }

    private final Path file;
    private final int maxSide;
    private volatile boolean closed;
    private volatile boolean paused;
    private volatile double duration;
    private volatile @Nullable String error;
    private volatile int generation;
    private volatile long startedAt;
    private volatile double startPos;
    private volatile double audioClock = -1;
    private volatile boolean audioEnded;
    private volatile boolean videoEnded;
    private double pausedAt = -1;
    private final BlockingQueue<Frame> frames = new ArrayBlockingQueue<>(6);
    private @Nullable Frame pending;
    private @Nullable DynamicTexture texture;
    private @Nullable Identifier textureId;
    private int texW;
    private int texH;
    private double shown;
    private final boolean loop;

    public VideoPlayer(Path file, int maxSide, boolean loop) {
        this.file = file;
        this.maxSide = maxSide;
        this.loop = loop;
        start(0);
    }

    private void start(double from) {
        int gen = ++generation;
        frames.clear();
        pending = null;
        audioClock = -1;
        audioEnded = false;
        videoEnded = false;
        startPos = from;
        startedAt = System.nanoTime();
        Thread video = new Thread(() -> decodeVideo(gen, from), "BlockPhone Video");
        video.setDaemon(true);
        video.start();
        Thread audio = new Thread(() -> playAudio(gen, from), "BlockPhone Video audio");
        audio.setDaemon(true);
        audio.start();
    }

    private boolean alive(int gen) {
        return !closed && gen == generation;
    }

    private void decodeVideo(int gen, double from) {
        try (SeekableByteChannel ch = NIOUtils.readableChannel(file.toFile())) {
            FrameGrab grab = FrameGrab.createFrameGrab(ch);
            duration = grab.getVideoTrack().getMeta().getTotalDuration();
            if (from > 0) {
                grab.seekToSecondSloppy(from);
            }
            Transform transform = null;
            org.jcodec.common.model.Picture rgb = null;
            while (alive(gen)) {
                PictureWithMetadata pm = grab.getNativeFrameWithMetadata();
                if (pm == null || pm.getPicture() == null) {
                    break;
                }
                org.jcodec.common.model.Picture src = pm.getPicture();
                if (transform == null) {
                    transform = ColorUtil.getTransform(src.getColor(), ColorSpace.RGB);
                    rgb = org.jcodec.common.model.Picture.create(src.getWidth(), src.getHeight(), ColorSpace.RGB);
                }
                transform.transform(src, rgb);
                Picture picture = ImageOps.downscale(toPicture(rgb, src.getCroppedWidth(), src.getCroppedHeight()), maxSide, maxSide);
                while (alive(gen) && !frames.offer(new Frame(picture, pm.getTimestamp()), 50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    // очередь полна — ждём, пока покажут
                }
            }
        } catch (Throwable e) {
            if (alive(gen)) {
                BlockPhoneFabric.LOGGER.info("Video {} is not decodable: {}", file.getFileName(), e.toString());
                error = e.getClass().getSimpleName();
            }
        }
        if (alive(gen)) {
            videoEnded = true;
        }
    }

    /** Звук Opus («кружочки» BlockPhone и некоторых клиентов): Concentus, 48 кГц. true — дорожка была. */
    private boolean playOpus(int gen, double from) throws Exception {
        Mp4Audio.OpusTrack track = Mp4Audio.readOpus(file);
        if (track == null) {
            return false;
        }
        int ch = track.channels();
        io.github.jaredmdobson.concentus.OpusDecoder decoder = new io.github.jaredmdobson.concentus.OpusDecoder(48_000, ch);
        AudioFormat format = new AudioFormat(48_000, 16, ch, true, false);
        SourceDataLine line = AudioSystem.getSourceDataLine(format);
        try {
            line.open(format, 48_000 * ch * 2 / 4);
            line.start();
            short[] pcm = new short[48_000 * 120 / 1000 * ch];
            byte[] bytes = new byte[pcm.length * 2];
            long skip = track.preSkip() + Math.round(from * 48_000);
            long played = 0;
            for (byte[] packet : track.packets()) {
                if (!alive(gen)) {
                    break;
                }
                int n = decoder.decode(packet, 0, packet.length, pcm, 0, pcm.length / ch, false);
                int start = (int) Math.min(n, Math.max(0, skip));
                skip -= start;
                if (start >= n) {
                    continue;
                }
                float gain = volume();
                int count = 0;
                for (int i = start * ch; i < n * ch; i++) {
                    int v = Math.max(-32768, Math.min(32767, Math.round(pcm[i] * gain)));
                    bytes[count++] = (byte) v;
                    bytes[count++] = (byte) (v >> 8);
                }
                while (paused && alive(gen)) {
                    line.stop();
                    Thread.sleep(20);
                }
                if (!line.isRunning()) {
                    line.start();
                }
                line.write(bytes, 0, count);
                played += n - start;
                long buffered = (line.getBufferSize() - line.available()) / (2L * ch);
                audioClock = from + Math.max(0, played - buffered) / 48_000.0;
            }
            if (alive(gen)) {
                line.drain();
            }
        } finally {
            line.stop();
            line.close();
        }
        return true;
    }

    private void playAudio(int gen, double from) {
        try {
            if (playOpus(gen, from)) {
                if (alive(gen)) {
                    audioEnded = true;
                    audioClock = -1;
                }
                return;
            }
        } catch (Throwable e) {
            BlockPhoneFabric.LOGGER.info("Video Opus audio {} skipped: {}", file.getFileName(), e.toString());
        }
        SourceDataLine line = null;
        try (SeekableByteChannel ch = NIOUtils.readableChannel(file.toFile())) {
            MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(ch);
            if (demuxer.getAudioTracks().isEmpty()) {
                return;
            }
            DemuxerTrack track = demuxer.getAudioTracks().get(0);
            ByteBuffer codecPrivate = track.getMeta().getCodecPrivate();
            if (codecPrivate == null) {
                return;
            }
            AACDecoder decoder = new AACDecoder(codecPrivate);
            ByteBuffer out = ByteBuffer.allocate(1 << 16);
            long played = 0;
            int rate = 0;
            int channels = 0;
            int frameBytes = 0;
            byte[] bytes = new byte[0];
            Packet packet;
            while (alive(gen) && (packet = track.nextFrame()) != null) {
                double t = packet.getPts() / (double) Math.max(1, packet.getTimescale());
                out.clear();
                AudioBuffer buf = decoder.decodeFrame(packet.getData(), out);
                if (t + 0.05 < from) {
                    continue; // перемотка: звук до этого места пропускаем
                }
                org.jcodec.common.AudioFormat f = buf.getFormat();
                if (line == null) {
                    rate = f.getSampleRate();
                    channels = f.getChannels();
                    frameBytes = channels * 2;
                    AudioFormat format = new AudioFormat(rate, 16, channels, true, false);
                    line = AudioSystem.getSourceDataLine(format);
                    line.open(format, rate * frameBytes / 4);
                    line.start();
                }
                ByteBuffer data = buf.getData();
                data.order(f.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
                int samples = data.remaining() / 2;
                if (bytes.length < samples * 2) {
                    bytes = new byte[samples * 2];
                }
                float gain = volume();
                for (int i = 0; i < samples; i++) {
                    int v = Math.max(-32768, Math.min(32767, Math.round(data.getShort() * gain)));
                    bytes[i * 2] = (byte) v;
                    bytes[i * 2 + 1] = (byte) (v >> 8);
                }
                while (paused && alive(gen)) {
                    line.stop();
                    Thread.sleep(20);
                }
                if (!line.isRunning()) {
                    line.start();
                }
                line.write(bytes, 0, samples * 2);
                played += samples / channels;
                long buffered = (line.getBufferSize() - line.available()) / frameBytes;
                audioClock = from + Math.max(0, played - buffered) / (double) rate;
            }
            if (line != null && alive(gen)) {
                line.drain();
            }
        } catch (Throwable e) {
            BlockPhoneFabric.LOGGER.info("Video audio {} skipped: {}", file.getFileName(), e.toString());
        } finally {
            if (line != null) {
                line.stop();
                line.close();
            }
            if (alive(gen)) {
                audioEnded = true;
                audioClock = -1;
            }
        }
    }

    private static float volume() {
        return dev.blockphone.fabric.client.voice.VoiceMessages.mediaGain();
    }

    /** Текущее время (секунды): по звуку, если он идёт, иначе по часам. */
    public double position() {
        if (pausedAt >= 0) {
            return pausedAt;
        }
        double clock = audioClock;
        if (clock >= 0) {
            return clock;
        }
        return startPos + (System.nanoTime() - startedAt) / 1e9;
    }

    public double duration() {
        return duration;
    }

    public boolean paused() {
        return paused;
    }

    public @Nullable String error() {
        return error;
    }

    public void togglePause() {
        if (paused) {
            double at = pausedAt;
            pausedAt = -1;
            paused = false;
            startPos = at;
            startedAt = System.nanoTime();
        } else {
            pausedAt = position();
            paused = true;
        }
    }

    public void seek(double seconds) {
        boolean wasPaused = paused;
        pausedAt = -1;
        paused = false;
        start(Math.max(0, Math.min(duration > 0 ? duration - 0.1 : seconds, seconds)));
        if (wasPaused) {
            togglePause();
        }
    }

    /** Кадр для показа сейчас (поток рендера) или null — первого кадра ещё нет. */
    public @Nullable Identifier frame() {
        double now = position();
        if (!paused) {
            while (true) {
                if (pending == null) {
                    pending = frames.poll();
                }
                if (pending == null || pending.time() > now + 0.02) {
                    break;
                }
                upload(pending.picture());
                shown = pending.time();
                pending = null;
            }
        }
        if (loop && videoEnded && frames.isEmpty() && pending == null && (audioEnded || audioClock < 0)) {
            seek(0);
        }
        return textureId;
    }

    public int width() {
        return texW;
    }

    public int height() {
        return texH;
    }

    public boolean finished() {
        return !loop && videoEnded && frames.isEmpty() && pending == null && position() >= duration - 0.2;
    }

    private void upload(Picture p) {
        Minecraft mc = Minecraft.getInstance();
        if (texture == null || texW != p.width() || texH != p.height()) {
            if (textureId != null) {
                mc.getTextureManager().release(textureId);
            }
            texW = p.width();
            texH = p.height();
            texture = new DynamicTexture(() -> "BlockPhone video", texW, texH, false);
            textureId = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "dynamic/video_" + System.nanoTime());
            mc.getTextureManager().register(textureId, texture);
        }
        com.mojang.blaze3d.platform.NativeImage img = texture.getPixels();
        int[] argb = p.argb();
        for (int y = 0; y < texH; y++) {
            for (int x = 0; x < texW; x++) {
                img.setPixel(x, y, argb[y * texW + x]);
            }
        }
        texture.upload();
    }

    private static Picture toPicture(org.jcodec.common.model.Picture rgb, int w, int h) {
        byte[] data = rgb.getPlaneData(0);
        int stride = rgb.getWidth() * 3;
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int o = y * stride + x * 3;
                argb[y * w + x] = 0xFF000000 | (data[o] + 128) << 16 | (data[o + 1] + 128) << 8 | (data[o + 2] + 128);
            }
        }
        return new Picture(w, h, argb);
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        if (textureId != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            textureId = null;
        }
    }
}
