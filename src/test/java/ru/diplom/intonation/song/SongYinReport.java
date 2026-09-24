package ru.diplom.intonation.song;

import java.nio.file.Path;

/** Local benchmark entry point: Gradle songYinReport -PaudioPath=/path/to/vocals.wav */
public final class SongYinReport {
    public static void main(String[] args) throws Exception {
        var result = new SongAnalyzer().analyze(Path.of(args[0]));
        for (var note : result.notes())
            System.out.println(note.startSeconds() + "\t" + note.durationSeconds()
                    + "\t" + note.midi());
    }
}
