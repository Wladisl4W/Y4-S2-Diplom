package ru.diplom.intonation;

/** Logical time shared by the scrolling canvas and exercise scoring. */
public final class PlaybackClock {
    private volatile long pausedAt = -1;
    private volatile long pausedDuration;

    public long time(long realNanos) {
        return (pausedAt < 0 ? realNanos : pausedAt) - pausedDuration;
    }

    public boolean isPaused() { return pausedAt >= 0; }

    public void pause(long realNanos) {
        if (!isPaused()) pausedAt = realNanos;
    }

    public void resume(long realNanos) {
        if (isPaused()) {
            pausedDuration += realNanos - pausedAt;
            pausedAt = -1;
        }
    }
}
