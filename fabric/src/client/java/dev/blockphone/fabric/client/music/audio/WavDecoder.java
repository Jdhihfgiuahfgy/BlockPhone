package dev.blockphone.fabric.client.music.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** WAV (и всё, что понимает javax.sound: AIFF, AU) с переводом в 16-бит PCM. */
public final class WavDecoder implements AudioDecoder {
    private final AudioInputStream stream;
    private final int sampleRate;
    private final int channels;
    private final long durationMs;
    private byte[] bytes = new byte[0];

    public WavDecoder(Path file) throws IOException {
        AudioInputStream source;
        try {
            source = AudioSystem.getAudioInputStream(new BufferedInputStream(Files.newInputStream(file)));
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported audio file " + file.getFileName(), e);
        }
        AudioFormat in = source.getFormat();
        AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, in.getSampleRate(), 16, in.getChannels(),
                in.getChannels() * 2, in.getSampleRate(), false);
        stream = in.matches(pcm) ? source : AudioSystem.getAudioInputStream(pcm, source);
        sampleRate = Math.round(in.getSampleRate());
        channels = in.getChannels();
        long frames = source.getFrameLength();
        durationMs = frames > 0 ? frames * 1000L / Math.max(1, sampleRate) : -1;
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
        return durationMs;
    }

    @Override
    public int read(short[] buffer) throws IOException {
        int want = (buffer.length - buffer.length % channels) * 2;
        if (bytes.length < want) {
            bytes = new byte[want];
        }
        int n = stream.read(bytes, 0, want);
        if (n <= 0) {
            return -1;
        }
        int samples = n / 2;
        for (int i = 0; i < samples; i++) {
            buffer[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
        }
        return samples;
    }

    @Override
    public void close() {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }
}
