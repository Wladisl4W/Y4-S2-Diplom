package ru.diplom.intonation.exercise;

import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Synthesizer;

/** Plays a separate acoustic-grand-piano preview of a selected exercise. */
public final class TonePlayer {
    private static volatile Thread previewThread;
    private TonePlayer() {}

    public static synchronized void playAsync(WarmupRoute route) {
        stop();
        Thread worker = new Thread(() -> play(route), "piano-preview");
        worker.setDaemon(true);
        previewThread = worker;
        worker.start();
    }

    public static synchronized void stop() {
        if (previewThread != null) previewThread.interrupt();
        previewThread = null;
    }

    private static void play(WarmupRoute route) {
        Synthesizer synth = null;
        try {
            synth = MidiSystem.getSynthesizer();
            synth.open();
            MidiChannel melody = synth.getChannels()[0];
            MidiChannel chords = synth.getChannels()[1];
            melody.programChange(0);
            chords.programChange(0);
            long start = System.nanoTime();
            long end = start + (long) (route.option().exercise().durationSeconds() * 1e9);
            PianoCue previous = PianoCue.SILENCE;
            while (System.nanoTime() < end && !Thread.currentThread().isInterrupted()) {
                PianoCue next = PianoCue.at(route, System.nanoTime(), start, true, true);
                if (next.melodyNote() != previous.melodyNote()) {
                    melody.allNotesOff();
                    if (next.melodyNote() >= 0) melody.noteOn(next.melodyNote(), 70);
                }
                if (next.chordRoot() != previous.chordRoot()) {
                    chords.allNotesOff();
                    if (next.chordRoot() >= 0) {
                        chords.noteOn(next.chordRoot(), 43);
                        chords.noteOn(next.chordRoot() + 4, 36);
                        chords.noteOn(next.chordRoot() + 7, 36);
                    }
                }
                previous = next;
                Thread.sleep(10);
            }
            melody.allNotesOff();
            chords.allNotesOff();
        } catch (MidiUnavailableException ignored) {
            // Preview is optional when the operating system has no audio output.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (synth != null) synth.close();
        }
    }
}
