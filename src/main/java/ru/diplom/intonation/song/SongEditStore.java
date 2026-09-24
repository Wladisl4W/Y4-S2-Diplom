package ru.diplom.intonation.song;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Persists a user's corrected note line for one unchanged local audio file. */
public final class SongEditStore {
    private final Path directory;

    public SongEditStore(Path directory) { this.directory = directory; }

    public SongAnalyzer.Result load(Path song, SongAnalyzer.Result original) throws IOException {
        Path file = location(song);
        if (!Files.exists(file)) return original;
        List<SongAnalyzer.NoteEvent> notes = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] fields = line.split(",");
            if (fields.length != 3 && fields.length != 4) throw new IOException("Файл исправлений повреждён");
            try {
                double start = Double.parseDouble(fields[0]);
                double duration = Double.parseDouble(fields[1]);
                int midi = Integer.parseInt(fields[2]);
                double confidence = fields.length == 4 ? Double.parseDouble(fields[3]) : Double.NaN;
                if (!Double.isFinite(start) || !Double.isFinite(duration) || start < 0
                        || duration <= 0 || start + duration > original.durationSeconds() + 1
                        || midi < 0 || midi > 127 || !(Double.isNaN(confidence)
                        || (Double.isFinite(confidence) && confidence >= 0 && confidence <= 1)))
                    throw new IOException("Файл исправлений повреждён");
                notes.add(new SongAnalyzer.NoteEvent(start, duration, midi, confidence));
            } catch (NumberFormatException error) {
                throw new IOException("Файл исправлений повреждён", error);
            }
        }
        return new SongAnalyzer.Result(notes, original.durationSeconds(), original.alternatives());
    }

    public void save(Path song, SongAnalyzer.Result result) throws IOException {
        Files.createDirectories(directory);
        Path target = location(song);
        Path temp = Files.createTempFile(directory, "song-edits-", ".tmp");
        try {
            StringBuilder contents = new StringBuilder();
            for (var note : result.notes()) contents.append(note.startSeconds()).append(',')
                    .append(note.durationSeconds()).append(',').append(note.midi()).append(',')
                    .append(note.confidence()).append('\n');
            Files.writeString(temp, contents.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path location(Path song) throws IOException {
        String identity = song.toAbsolutePath().normalize() + ":" + Files.size(song) + ":"
                + Files.getLastModifiedTime(song).toMillis();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(hash) + ".csv");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 недоступен", error);
        }
    }
}
