package ru.diplom.intonation;

import org.junit.jupiter.api.Test;
import ru.diplom.intonation.song.SongAnalyzer;
import ru.diplom.intonation.song.SongPipeline;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SongAnalysisControllerTest {
    private static final SongPipeline.Result RESULT = new SongPipeline.Result(
            Path.of("vocals.wav"), Path.of("instrumental.wav"),
            new SongAnalyzer.Result(List.of(), 2.0));

    private static final class FakeAnalysis implements SongAnalysisController.Analysis {
        boolean cancelled;
        boolean closed;
        Exception failure;
        @Override public SongPipeline.Result analyze(Path input, Consumer<String> progress) throws Exception {
            progress.accept("Separating " + input.getFileName());
            if (failure != null) throw failure;
            return RESULT;
        }
        @Override public void cancel() { cancelled = true; }
        @Override public void close() { closed = true; }
    }

    private static final class Events implements SongAnalysisController.Listener {
        final List<String> messages = new ArrayList<>();
        @Override public void onProgress(String message) { messages.add(message); }
        @Override public void onSuccess(SongPipeline.Result result) { messages.add("success"); }
        @Override public void onFailure(Exception error) { messages.add("failure: " + error.getMessage()); }
    }

    @Test void successRetainsStemsUntilNextSongOrClose() {
        ArrayDeque<Runnable> background = new ArrayDeque<>(), ui = new ArrayDeque<>();
        List<FakeAnalysis> created = new ArrayList<>();
        SongAnalysisController controller = new SongAnalysisController(model -> {
            FakeAnalysis fake = new FakeAnalysis();
            created.add(fake);
            return fake;
        }, background::add, ui::add);
        Events events = new Events();
        controller.start(Path.of("one.mp3"), "model", events);
        background.removeFirst().run();
        drain(ui);
        assertEquals(List.of("Separating one.mp3", "success"), events.messages);
        assertEquals(RESULT, controller.readySong());
        assertFalse(created.getFirst().closed);
        SongAnalyzer.Result corrected = new SongAnalyzer.Result(List.of(
                new SongAnalyzer.NoteEvent(0, 1, 60)), 2.0);
        controller.replaceTranscription(corrected);
        assertEquals(corrected, controller.readySong().transcription());
        controller.start(Path.of("two.mp3"), "model", new Events());
        assertTrue(created.getFirst().closed);
        assertNull(controller.readySong());
        controller.close();
        assertTrue(created.get(1).closed);
    }

    @Test void failureAndCancelSuppressObsoleteCallbacks() {
        ArrayDeque<Runnable> background = new ArrayDeque<>(), ui = new ArrayDeque<>();
        List<FakeAnalysis> created = new ArrayList<>();
        SongAnalysisController controller = new SongAnalysisController(model -> {
            FakeAnalysis fake = new FakeAnalysis();
            created.add(fake);
            return fake;
        }, background::add, ui::add);
        Events oldEvents = new Events(), newEvents = new Events();
        controller.start(Path.of("old.mp3"), "model", oldEvents);
        background.removeFirst().run(); // Callbacks are queued but have not reached the UI.
        controller.start(Path.of("new.mp3"), "model", newEvents);
        drain(ui);
        assertTrue(oldEvents.messages.isEmpty());
        assertTrue(created.getFirst().closed);
        created.get(1).failure = new IOException("decode failed");
        background.removeFirst().run();
        drain(ui);
        assertEquals(List.of("Separating new.mp3", "failure: decode failed"), newEvents.messages);
        assertNull(controller.readySong());
        assertTrue(created.get(1).closed);
        Events cancelledEvents = new Events();
        controller.start(Path.of("cancel.mp3"), "model", cancelledEvents);
        controller.cancel();
        background.removeFirst().run(); // A late worker result cannot revive a cancelled song.
        drain(ui);
        assertTrue(cancelledEvents.messages.isEmpty());
        assertTrue(created.get(2).cancelled);
        assertTrue(created.get(2).closed);
        controller.close();
    }

    private static void drain(ArrayDeque<Runnable> queue) {
        while (!queue.isEmpty()) queue.removeFirst().run();
    }
}
