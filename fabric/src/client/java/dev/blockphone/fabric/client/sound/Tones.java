package dev.blockphone.fabric.client.sound;

import java.util.HashMap;
import java.util.Map;

/**
 * Встроенные мелодии телефона — синтез (моно, 16 бит): ноты с огибающей (быстрая атака, затухание), гармоники для
 * «колокольного» или «деревянного» звука. Громкие (до ~85 % шкалы), без щелчков на краях.
 */
final class Tones {
    private static final Map<String, short[]> CACHE = new HashMap<>();

    private Tones() {
    }

    static synchronized short[] make(String name, int rate) {
        return CACHE.computeIfAbsent(name, n -> render(n, rate));
    }

    /** Нота: начало (с), частота (Гц), длина (с), вид звука. */
    private record Note(double at, double freq, double len, int kind) {
    }

    private static final int BELL = 0;
    private static final int BEEP = 1;
    private static final int WOOD = 2;
    private static final int PAD = 3;

    private static double hz(int midi) {
        return 440 * Math.pow(2, (midi - 69) / 12.0);
    }

    private static Note[] notes(String name) {
        return switch (name) {
            // Короткие — для уведомлений.
            case "chime" -> new Note[]{n(0, 88, 0.5, BELL), n(0.11, 91, 0.5, BELL), n(0.22, 96, 0.8, BELL)};
            case "pop" -> new Note[]{n(0, 79, 0.18, WOOD), n(0.09, 86, 0.3, WOOD)};
            case "marimba" -> new Note[]{n(0, 72, 0.4, WOOD), n(0.14, 76, 0.4, WOOD), n(0.28, 79, 0.4, WOOD), n(0.42, 84, 0.7, WOOD)};
            // Длинные — для звонка, будильника, таймера (повторяются).
            case "classic" -> {
                Note[] r = new Note[16];
                for (int i = 0; i < 16; i++) {
                    r[i] = n((i / 8) * 1.0 + (i % 8) * 0.05, i % 2 == 0 ? hz(88) : hz(91), 0.05, BEEP);
                }
                yield r;
            }
            case "digital" -> {
                Note[] r = new Note[8];
                for (int i = 0; i < 8; i++) {
                    r[i] = n((i / 4) * 0.9 + (i % 4) * 0.14, hz(93), 0.08, BEEP);
                }
                yield r;
            }
            case "radar" -> {
                Note[] r = new Note[6];
                for (int i = 0; i < 6; i++) {
                    r[i] = n(i * 0.22, hz(84 + (i % 3) * 3), 0.16, BELL);
                }
                yield r;
            }
            case "soft" -> new Note[]{n(0, 72, 1.1, PAD), n(0, 76, 1.1, PAD), n(0.6, 79, 1.2, PAD), n(1.2, 84, 1.4, PAD)};
            case "bells" -> new Note[]{n(0, 84, 0.9, BELL), n(0.25, 79, 0.9, BELL), n(0.5, 81, 0.9, BELL), n(0.75, 74, 1.2, BELL),
                    n(1.3, 74, 0.9, BELL), n(1.55, 81, 0.9, BELL), n(1.8, 84, 0.9, BELL), n(2.05, 79, 1.4, BELL)};
            default -> new Note[]{n(0, 88, 0.5, BELL)};
        };
    }

    /** Нота по номеру MIDI (или уже частота, если > 200). */
    private static Note n(double at, double midiOrHz, double len, int kind) {
        return new Note(at, midiOrHz > 200 ? midiOrHz : hz((int) midiOrHz), len, kind);
    }

    private static short[] render(String name, int rate) {
        Note[] notes = notes(name);
        double end = 0;
        for (Note note : notes) {
            end = Math.max(end, note.at() + note.len() + (note.kind() == BELL ? 0.6 : 0.05));
        }
        float[] mix = new float[(int) (end * rate) + 1];
        for (Note note : notes) {
            int start = (int) (note.at() * rate);
            int length = (int) ((note.len() + (note.kind() == BELL ? 0.6 : 0.05)) * rate);
            for (int i = 0; i < length && start + i < mix.length; i++) {
                double t = i / (double) rate;
                double w = 2 * Math.PI * note.freq() * t;
                double attack = Math.min(1, t / 0.004);
                double v = switch (note.kind()) {
                    case BELL -> (Math.sin(w) + 0.45 * Math.sin(2.76 * w) + 0.2 * Math.sin(5.4 * w)) * Math.exp(-t * 4.5);
                    case WOOD -> (Math.sin(w) + 0.3 * Math.sin(4 * w)) * Math.exp(-t * 11);
                    case PAD -> (Math.sin(w) + 0.25 * Math.sin(2 * w)) * Math.min(1, t / 0.15) * Math.exp(-t * 1.6);
                    default -> { // BEEP: мягкий «прямоугольник» (нечётные гармоники), ровная громкость
                        double s = Math.sin(w) + Math.sin(3 * w) / 3 + Math.sin(5 * w) / 5;
                        double release = Math.min(1, (note.len() + 0.05 - t) / 0.01);
                        yield s * Math.max(0, release);
                    }
                };
                mix[start + i] += (float) (v * attack);
            }
        }
        float peak = 0;
        for (float v : mix) {
            peak = Math.max(peak, Math.abs(v));
        }
        float scale = peak > 0 ? 0.85f * 32767 / peak : 0;
        short[] pcm = new short[mix.length];
        int fade = Math.min(pcm.length / 2, rate / 100);
        for (int i = 0; i < pcm.length; i++) {
            float edge = i >= pcm.length - fade ? (pcm.length - i) / (float) fade : 1;
            pcm[i] = (short) Math.round(mix[i] * scale * edge);
        }
        return pcm;
    }
}
