package com.motexa.openninebot;

/** A static or hidden mirror is different from a source that should be producing frames. */
final class FrameOutputWatchdog {
    static final long TIMEOUT_MS = 15_000;
    enum State { STARTING, ACTIVE, IDLE, PAUSED, STALLED }

    static State evaluate(boolean mirror, boolean hasOutput, long quietMs,
            boolean interactive, boolean visible, boolean paced) {
        if (quietMs <= TIMEOUT_MS) return hasOutput ? State.ACTIVE : State.STARTING;
        if (!hasOutput) return State.STALLED;
        if (mirror && (!interactive || !visible)) return State.PAUSED;
        if (mirror && !paced) return State.IDLE;
        return State.STALLED;
    }
}
