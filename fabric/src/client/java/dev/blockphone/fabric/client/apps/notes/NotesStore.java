package dev.blockphone.fabric.client.apps.notes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.storage.ClientFiles;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Заметки игрока: {@code blockphone/notes.json}. Общие для всех миров и серверов. */
public final class NotesStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Note {
        public String id = UUID.randomUUID().toString();
        public String text = "";
        public long created = System.currentTimeMillis();
        public long updated = created;

        /** Первая непустая строка — заголовок. */
        public String title() {
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    return line.strip();
                }
            }
            return "";
        }

        /** Текст после заголовка одной строкой — для превью в списке. */
        public String preview() {
            String title = title();
            int index = text.indexOf(title);
            String rest = index >= 0 ? text.substring(index + title.length()) : text;
            return rest.replace('\n', ' ').strip();
        }
    }

    private static List<Note> notes;

    public static List<Note> all() {
        if (notes == null) {
            notes = load();
        }
        notes.sort(Comparator.comparingLong((Note n) -> n.updated).reversed());
        return notes;
    }

    public static Note create() {
        Note note = new Note();
        all().add(note);
        return note;
    }

    public static void delete(Note note) {
        all().remove(note);
        save();
    }

    /** Удаляет пустые заметки и сохраняет файл. */
    public static void save() {
        if (notes == null) {
            return;
        }
        notes.removeIf(n -> n.text.isBlank());
        List<Note> snapshot = new ArrayList<>();
        for (Note n : notes) {
            Note copy = new Note();
            copy.id = n.id;
            copy.text = n.text;
            copy.created = n.created;
            copy.updated = n.updated;
            snapshot.add(copy);
        }
        ClientFiles.IO.execute(() -> write(snapshot));
    }

    private static List<Note> load() {
        Path file = ClientFiles.notesFile();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                List<Note> loaded = GSON.fromJson(reader, new TypeToken<List<Note>>() {
                }.getType());
                if (loaded != null) {
                    return new ArrayList<>(loaded);
                }
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Failed to read notes {}", file, e);
            }
        }
        return new ArrayList<>();
    }

    private static void write(List<Note> snapshot) {
        Path file = ClientFiles.notesFile();
        try {
            Files.createDirectories(file.getParent());
            // Пишем во временный файл и заменяем — чтобы не потерять заметки при сбое посреди записи.
            Path tmp = file.resolveSibling("notes.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(snapshot, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("Failed to save notes", e);
        }
    }

    private NotesStore() {
    }
}
