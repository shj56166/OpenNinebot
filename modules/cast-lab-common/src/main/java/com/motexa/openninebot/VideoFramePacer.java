package com.motexa.openninebot;

/** Monotonic frame deadlines: keep cadence without replaying missed ticks after a stall. */
final class VideoFramePacer {
    private final long periodNs;
    private long nextNs = Long.MIN_VALUE;
    private long presentationNs;
    private volatile long lastLatenessNs, skippedTicks;

    VideoFramePacer(int fps) {
        if (fps < 1 || fps > 60) throw new IllegalArgumentException("frame_rate_invalid");
        periodNs = 1_000_000_000L / fps;
    }

    long delayNs(long nowNs) {
        return nextNs == Long.MIN_VALUE ? 0 : Math.max(0, nextNs - nowNs);
    }

    boolean take(long nowNs) {
        if (delayNs(nowNs) > 0) return false;
        lastLatenessNs = nextNs == Long.MIN_VALUE ? 0 : nowNs - nextNs;
        skippedTicks += lastLatenessNs / periodNs;
        presentationNs = nextNs == Long.MIN_VALUE ? nowNs
            : nextNs + ((nowNs - nextNs) / periodNs) * periodNs;
        nextNs = presentationNs + periodNs;
        return true;
    }

    long presentationTimeNs() { return presentationNs; }
    long lastLatenessNs() { return lastLatenessNs; }
    long skippedTicks() { return skippedTicks; }
}
