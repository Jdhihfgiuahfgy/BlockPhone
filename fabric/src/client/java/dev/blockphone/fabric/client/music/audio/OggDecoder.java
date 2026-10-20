package dev.blockphone.fabric.client.music.audio;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OGG Vorbis через stb_vorbis (LWJGL, уже есть в Minecraft). Файл читается в память целиком:
 * открытие по имени файла не работает с русскими буквами в пути на Windows.
 */
public final class OggDecoder implements AudioDecoder {
    private ByteBuffer data;
    private long handle;
    private final int sampleRate;
    private final int channels;
    private final long lengthSamples;
    private ShortBuffer scratch;

    public OggDecoder(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            handle = STBVorbis.stb_vorbis_open_memory(data, error, null);
            if (handle == 0) {
                MemoryUtil.memFree(data);
                data = null;
                throw new IOException("Bad OGG file (stb_vorbis error " + error.get(0) + ")");
            }
            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(handle, info);
            sampleRate = info.sample_rate();
            channels = info.channels();
        }
        lengthSamples = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public long durationMs() {
        return lengthSamples > 0 ? lengthSamples * 1000L / sampleRate : -1;
    }

    @Override
    public int read(short[] buffer) {
        if (scratch == null || scratch.capacity() < buffer.length) {
            if (scratch != null) {
                MemoryUtil.memFree(scratch);
            }
            scratch = MemoryUtil.memAllocShort(buffer.length);
        }
        scratch.clear();
        scratch.limit(buffer.length - buffer.length % channels);
        int perChannel = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, scratch);
        if (perChannel <= 0) {
            return -1;
        }
        int count = perChannel * channels;
        scratch.get(0, buffer, 0, count);
        return count;
    }

    @Override
    public boolean seek(long ms) {
        return STBVorbis.stb_vorbis_seek(handle, (int) Math.min(Integer.MAX_VALUE, ms * sampleRate / 1000L));
    }

    @Override
    public void close() {
        if (handle != 0) {
            STBVorbis.stb_vorbis_close(handle);
            handle = 0;
        }
        if (data != null) {
            MemoryUtil.memFree(data);
            data = null;
        }
        if (scratch != null) {
            MemoryUtil.memFree(scratch);
            scratch = null;
        }
    }
}
