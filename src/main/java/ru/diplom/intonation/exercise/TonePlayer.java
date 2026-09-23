package ru.diplom.intonation.exercise;

import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Synthesizer;

/** Plays a separate acoustic-grand-piano preview of a selected exercise. */
public final class TonePlayer {
    private static volatile Thread previewThread;
    private TonePlayer() {}

    public static synchronized void playAsync(Exercise exercise) {
        stop();
        Thread worker = new Thread(() -> play(exercise), "piano-preview");
        worker.setDaemon(true);
        previewThread = worker;
        worker.start();
    }

    public static synchronized void stop() {
        if (previewThread != null) previewThread.interrupt();
        previewThread = null;
    }

    private static void play(Exercise exercise) {
        Synthesizer synth = null;
        try {
            synth = MidiSystem.getSynthesizer();
            synth.open();
            MidiChannel piano = synth.getChannels()[0];
            piano.programChange(0); // General MIDI Acoustic Grand Piano
            for (int midi : exercise.notes()) {
                if (Thread.currentThread().isInterrupted()) break;
                if (midi != Exercise.REST) piano.noteOn(midi, 70);
                Thread.sleep((long) (Math.min(0.9, exercise.secondsPerNote() * 0.65) * 1000));
                if (midi != Exercise.REST) piano.noteOff(midi);
                Thread.sleep(110);
            }
            piano.allNotesOff();
        } catch (MidiUnavailableException ignored) {
            // Preview is optional when the operating system has no audio output.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (synth != null) synth.close();
        }
    }
}
