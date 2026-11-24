package org.openninebot.reference.app.protocolcore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Declarative, read-only collection policy for temporary protocol-test sessions. */
public final class AutomaticCapturePlan {
    public final List<String> startupOperations;
    public final List<String> fullOperations;
    public final String realtimeOperation;
    public final long realtimeIntervalMs;
    public final long fullIntervalMs;
    public final long nonRealtimeGapMs;

    public AutomaticCapturePlan(List<String> startup, List<String> full, String realtime,
        long realtimeIntervalMs, long fullIntervalMs, long nonRealtimeGapMs) {
        if (startup == null || startup.isEmpty() || full == null || full.isEmpty() || realtime == null
            || !realtime.equals(startup.get(startup.size() - 1)) || realtimeIntervalMs < 50
            || fullIntervalMs < realtimeIntervalMs || nonRealtimeGapMs < 0) {
            throw new IllegalArgumentException("Invalid automatic capture plan.");
        }
        this.startupOperations = Collections.unmodifiableList(new ArrayList<>(startup));
        this.fullOperations = Collections.unmodifiableList(new ArrayList<>(full));
        this.realtimeOperation = realtime;
        this.realtimeIntervalMs = realtimeIntervalMs;
        this.fullIntervalMs = fullIntervalMs;
        this.nonRealtimeGapMs = nonRealtimeGapMs;
    }
}
