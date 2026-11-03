package ru.diplom.intonation;

import ru.diplom.intonation.song.SongAnalyzer;
import ru.diplom.intonation.song.SongPipeline;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Owns one local analysis and its temporary stems until replaced or closed. */
final class SongAnalysisController implements AutoCloseable {
    interface Analysis extends AutoCloseable {
        SongPipeline.Result analyze(Path input, Consumer<String> progress) throws Exception;
        void cancel();
        @Override void close();
    }

    interface Factory { Analysis create(String model); }

    interface Listener {
        void onProgress(String message);
        void onSuccess(SongPipeline.Result result);
        void onFailure(Exception error);
    }

    private final Factory factory;
    private final Executor worker;
    private final Consumer<Runnable> dispatch;
    private Analysis active;
    private SongPipeline.Result ready;
    private long generation;
    private boolean closed;

    SongAnalysisController() {
        this(model -> new PipelineAnalysis(new SongPipeline(model)), task -> {
            Thread thread = new Thread(task, "song-analysis");
            thread.setDaemon(true);
            thread.start();
        }, javafx.application.Platform::runLater);
    }

    SongAnalysisController(Factory factory, Executor worker, Consumer<Runnable> dispatch) {
        this.factory = Objects.requireNonNull(factory);
        this.worker = Objects.requireNonNull(worker);
        this.dispatch = Objects.requireNonNull(dispatch);
    }

    SongPipeline.Result readySong() { return ready; }

    void replaceTranscription(SongAnalyzer.Result transcription) {
        if (ready == null) throw new IllegalStateException("No analyzed song");
        ready = new SongPipeline.Result(ready.vocals(), ready.instrumental(), transcription);
    }

    void start(Path input, String model, Listener listener) {
        if (closed) throw new IllegalStateException("Analysis controller is closed");
        cancel();
        long run = generation;
        Analysis analysis;
        try { analysis = factory.create(model); }
        catch (RuntimeException error) { listener.onFailure(error); return; }
        active = analysis;
        try {
            worker.execute(() -> analyze(run, analysis, input, listener));
        } catch (RuntimeException error) {
            active = null;
            analysis.close();
            listener.onFailure(error);
        }
    }

    private void analyze(long run, Analysis analysis, Path input, Listener listener) {
        try {
            SongPipeline.Result result = analysis.analyze(input, message ->
                    dispatch.accept(() -> {
                        if (isCurrent(run, analysis)) listener.onProgress(message);
                    }));
            dispatch.accept(() -> {
                if (isCurrent(run, analysis)) {
                    ready = result;
                    listener.onSuccess(result);
                } else analysis.close();
            });
        } catch (Exception error) {
            dispatch.accept(() -> {
                if (isCurrent(run, analysis)) {
                    active = null;
                    analysis.close();
                    listener.onFailure(error);
                } else analysis.close();
            });
        }
    }

    private boolean isCurrent(long run, Analysis analysis) {
        return !closed && generation == run && active == analysis;
    }

    void cancel() {
        generation++;
        ready = null;
        Analysis previous = active;
        active = null;
        if (previous != null) {
            previous.cancel();
            previous.close();
        }
    }

    @Override public void close() {
        closed = true;
        cancel();
    }

    private record PipelineAnalysis(SongPipeline pipeline) implements Analysis {
        @Override public SongPipeline.Result analyze(Path input, Consumer<String> progress) throws Exception {
            return pipeline.analyze(input, progress);
        }
        @Override public void cancel() { pipeline.cancel(); }
        @Override public void close() { pipeline.close(); }
    }
}
