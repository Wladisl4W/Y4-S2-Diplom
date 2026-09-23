package ru.diplom.intonation.exercise;

import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Synthesizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Two independent piano layers, driven by the same logical clock as the graph. */
public final class PianoGuide implements AutoCloseable {
    private final ExecutorService audio = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "warmup-piano");
        worker.setDaemon(true);
        return worker;
    });
    private final Consumer<String> onError;
    private Synthesizer synth;
    private MidiChannel melody;
    private MidiChannel chords;
    private boolean failed;
    private int desiredMelody = -1;
    private int desiredChordRoot = -1;
    private boolean closed;

    public PianoGuide(Consumer<String> onError) { this.onError = onError; }

    /** Called from the UI thread; sends only changes to the audio worker. */
    public void update(WarmupRoute route, long nowNanos, long startNanos,
                       boolean playMelody, boolean playTransitions) {
        PianoCue cue = PianoCue.at(route, nowNanos, startNanos, playMelody, playTransitions);
        setDesired(cue.melodyNote(), cue.chordRoot());
    }

    public void silence() { setDesired(-1, -1); }

    private void setDesired(int nextMelody, int nextChord) {
        if (closed || (nextMelody == desiredMelody && nextChord == desiredChordRoot)) return;
        desiredMelody = nextMelody;
        desiredChordRoot = nextChord;
        audio.execute(() -> {
            if ((nextMelody >= 0 || nextChord >= 0) && !open()) return;
            if (melody == null) return;
            melody.allNotesOff();
            chords.allNotesOff();
            if (nextMelody >= 0) melody.noteOn(nextMelody, 64);
            if (nextChord >= 0) {
                chords.noteOn(nextChord, 43);
                chords.noteOn(nextChord + 4, 36);
                chords.noteOn(nextChord + 7, 36);
            }
        });
    }

    private boolean open() {
        if (failed) return false;
        if (synth != null) return true;
        try {
            synth = MidiSystem.getSynthesizer();
            synth.open();
            MidiChannel[] channels = synth.getChannels();
            melody = channels[0];
            chords = channels[1];
            melody.programChange(0);
            chords.programChange(0); // GM Acoustic Grand Piano on both layers
            return true;
        } catch (MidiUnavailableException | RuntimeException error) {
            failed = true;
            if (synth != null) synth.close();
            synth = null;
            onError.accept("Пианино недоступно: " + error.getMessage());
            return false;
        }
    }

    @Override public void close() {
        if (closed) return;
        silence();
        closed = true;
        audio.execute(() -> { if (synth != null) synth.close(); });
        audio.shutdown();
    }
}
