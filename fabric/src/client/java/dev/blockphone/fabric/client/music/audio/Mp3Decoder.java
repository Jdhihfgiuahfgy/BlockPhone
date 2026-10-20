package dev.blockphone.fabric.client.music.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MP3 через JLayer. Перемотка — пропуском кадров по заголовкам (без декодирования), это быстро.
 */
public final class Mp3Decoder implements AudioDecoder {
    private final Path file;
    private final long fileSize;
    private InputStream input;
    private Bitstream bitstream;
    private Decoder decoder;
    private int sampleRate;
    private int channels;
    private long durationMs = -1;
    /** Уже декодированный, но ещё не отданный кадр. */
    private short[] pending;
    private int pendingOffset;
    private int pendingLength;

    public Mp3Decoder(Path file) throws IOException {
        this.file = file;
        this.fileSize = Files.size(file);
        open();
        // Первый кадр: частота, каналы, длительность.
        if (!decodeNext()) {
            close();
            throw new IOException("No MP3 frames in " + file.getFileName());
        }
    }

    private void open() throws IOException {
        close();
        input = new BufferedInputStream(Files.newInputStream(file), 64 * 1024);
        bitstream = new Bitstream(input);
        decoder = new Decoder();
        pending = null;
        pendingLength = 0;
        pendingOffset = 0;
    }

    /** Декодировать следующий кадр в pending. false — конец файла. */
    private boolean decodeNext() throws IOException {
        try {
            while (true) {
                Header header = bitstream.readFrame();
                if (header == null) {
                    return false;
                }
                try {
                    SampleBuffer out = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    if (sampleRate == 0) {
                        sampleRate = out.getSampleFrequency();
                        channels = out.getChannelCount();
                        durationMs = Math.round(header.total_ms((int) Math.min(Integer.MAX_VALUE, fileSize)));
                    }
                    pending = out.getBuffer();
                    pendingLength = out.getBufferLength();
                    pendingOffset = 0;
                    return true;
                } catch (DecoderException | ArrayIndexOutOfBoundsException e) {
                    // Битый кадр — пропускаем.
                } finally {
                    bitstream.closeFrame();
                }
            }
        } catch (BitstreamException e) {
            throw new IOException("Bad MP3 stream", e);
        }
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
        int written = 0;
        while (written < buffer.length) {
            if (pending == null || pendingOffset >= pendingLength) {
                if (!decodeNext()) {
                    return written == 0 ? -1 : written;
                }
            }
            int n = Math.min(buffer.length - written, pendingLength - pendingOffset);
            System.arraycopy(pending, pendingOffset, buffer, written, n);
            pendingOffset += n;
            written += n;
        }
        return written;
    }

    @Override
    public boolean seek(long ms) throws IOException {
        open();
        double skipped = 0;
        try {
            while (skipped < ms) {
                Header header = bitstream.readFrame();
                if (header == null) {
                    break;
                }
                skipped += header.ms_per_frame();
                bitstream.closeFrame();
            }
        } catch (BitstreamException e) {
            throw new IOException("Bad MP3 stream", e);
        }
        return true;
    }

    @Override
    public void close() {
        try {
            if (bitstream != null) {
                bitstream.close();
            }
        } catch (BitstreamException ignored) {
        }
        try {
            if (input != null) {
                input.close();
            }
        } catch (IOException ignored) {
        }
        bitstream = null;
        input = null;
    }
}
