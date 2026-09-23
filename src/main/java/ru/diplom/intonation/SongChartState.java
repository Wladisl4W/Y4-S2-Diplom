package ru.diplom.intonation;

import ru.diplom.intonation.song.SongAnalyzer;
import java.util.List;

/** Song targets use the same logical clock and central playhead as live singing. */
public record SongChartState(List<SongAnalyzer.NoteEvent> notes, long startNanos,
                             double durationSeconds) {
    public SongChartState { notes = List.copyOf(notes); }

    public long endNanos() { return startNanos + (long) (durationSeconds * 1e9); }

    public boolean expired(long nowNanos) {
        return nowNanos >= endNanos() + PitchTimeline.WINDOW_NANOS / 2;
    }

    public int targetAt(long nowNanos) {
        double seconds = (nowNanos - startNanos) / 1e9;
        for (SongAnalyzer.NoteEvent note : notes) {
            if (note.startSeconds() <= seconds && seconds < note.startSeconds() + note.durationSeconds())
                return note.midi();
        }
        return -1;
    }
}
