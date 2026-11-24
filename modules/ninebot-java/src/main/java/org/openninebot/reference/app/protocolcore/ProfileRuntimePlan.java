package org.openninebot.reference.app.protocolcore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Formal runner roles for operations declared by one controller profile. */
public final class ProfileRuntimePlan {
    public final String freshOperationId;
    public final String pollOperationId;
    public final String readyOperationId;
    public final boolean publishEmptyReadySnapshot;
    /** Zero disables notification liveness monitoring for this profile. */
    public final long notificationLivenessTimeoutMs;
    /** Fresh reads wait for the push stream without repeating its startup command. */
    public final boolean freshWaitsForNotification;
    /** Optional independent polling rates; an empty list retains the legacy runner. */
    public final List<PollOperation> pollingOperations;
    /** Optional connection-once read batch; polling always has priority. */
    public final ReadBatch backgroundReadBatch;
    /** Zero retains response-driven polling; otherwise pace a persistent monitor from actual writes. */
    public final long writeDrivenPollIntervalMs;

    public static ProfileRuntimePlan writeDrivenMonitor(String operationId, long intervalMs, long livenessMs) {
        if (intervalMs < 50L || intervalMs > 60_000L || livenessMs < intervalMs) {
            throw new IllegalArgumentException("Invalid monitor cadence.");
        }
        return new ProfileRuntimePlan(operationId, operationId, operationId, true,
            livenessMs, false, Collections.emptyList(), null, intervalMs);
    }

    public static final class ReadBatch {
        public final String id;
        public final List<String> operationIds;
        public final boolean reconnectOnFailure;

        public ReadBatch(String id, List<String> operationIds, boolean reconnectOnFailure) {
            this.id = required(id, "batch id");
            if (operationIds == null || operationIds.isEmpty()) throw new IllegalArgumentException("Empty read batch.");
            List<String> ids = new ArrayList<>();
            for (String operation : operationIds) {
                String value = required(operation, "batch operation");
                if (ids.contains(value)) throw new IllegalArgumentException("Duplicate batch operation.");
                ids.add(value);
            }
            this.operationIds = Collections.unmodifiableList(ids);
            this.reconnectOnFailure = reconnectOnFailure;
        }
    }

    public static final class PollOperation {
        public final String operationId;
        public final long intervalMs;
        public final boolean critical;

        public PollOperation(String operationId, long intervalMs, boolean critical) {
            if (intervalMs < 50L || intervalMs > 60_000L) throw new IllegalArgumentException("Invalid polling interval.");
            this.operationId = required(operationId, "operationId");
            this.intervalMs = intervalMs;
            this.critical = critical;
        }
    }

    public ProfileRuntimePlan(
        String freshOperationId,
        String pollOperationId,
        String readyOperationId,
        boolean publishEmptyReadySnapshot
    ) {
        this(
            freshOperationId,
            pollOperationId,
            readyOperationId,
            publishEmptyReadySnapshot,
            0L
        );
    }

    public ProfileRuntimePlan(
        String freshOperationId,
        String pollOperationId,
        String readyOperationId,
        boolean publishEmptyReadySnapshot,
        long notificationLivenessTimeoutMs
    ) {
        this(freshOperationId, pollOperationId, readyOperationId, publishEmptyReadySnapshot,
            notificationLivenessTimeoutMs, false);
    }

    public ProfileRuntimePlan(String freshOperationId, String pollOperationId, String readyOperationId,
        boolean publishEmptyReadySnapshot, long notificationLivenessTimeoutMs, boolean freshWaitsForNotification) {
        this(freshOperationId, pollOperationId, readyOperationId, publishEmptyReadySnapshot,
            notificationLivenessTimeoutMs, freshWaitsForNotification, Collections.emptyList());
    }

    public ProfileRuntimePlan(String freshOperationId, String pollOperationId, String readyOperationId,
        boolean publishEmptyReadySnapshot, long notificationLivenessTimeoutMs, boolean freshWaitsForNotification,
        List<PollOperation> pollingOperations) {
        this(freshOperationId, pollOperationId, readyOperationId, publishEmptyReadySnapshot,
            notificationLivenessTimeoutMs, freshWaitsForNotification, pollingOperations, null);
    }

    public ProfileRuntimePlan(String freshOperationId, String pollOperationId, String readyOperationId,
        boolean publishEmptyReadySnapshot, long notificationLivenessTimeoutMs, boolean freshWaitsForNotification,
        List<PollOperation> pollingOperations, ReadBatch backgroundReadBatch) {
        this(freshOperationId, pollOperationId, readyOperationId, publishEmptyReadySnapshot,
            notificationLivenessTimeoutMs, freshWaitsForNotification, pollingOperations, backgroundReadBatch, 0L);
    }

    private ProfileRuntimePlan(String freshOperationId, String pollOperationId, String readyOperationId,
        boolean publishEmptyReadySnapshot, long notificationLivenessTimeoutMs, boolean freshWaitsForNotification,
        List<PollOperation> pollingOperations, ReadBatch backgroundReadBatch, long writeDrivenPollIntervalMs) {
        if (notificationLivenessTimeoutMs != 0L &&
            (notificationLivenessTimeoutMs < 50L || notificationLivenessTimeoutMs > 60_000L)) {
            throw new IllegalArgumentException(
                "Notification liveness timeout must be zero or between 50 ms and 60 seconds."
            );
        }
        this.freshOperationId = required(freshOperationId, "freshOperationId");
        this.pollOperationId = optional(pollOperationId);
        this.readyOperationId = required(readyOperationId, "readyOperationId");
        this.publishEmptyReadySnapshot = publishEmptyReadySnapshot;
        this.notificationLivenessTimeoutMs = notificationLivenessTimeoutMs;
        this.freshWaitsForNotification = freshWaitsForNotification;
        this.pollingOperations = Collections.unmodifiableList(new ArrayList<>(pollingOperations));
        if (backgroundReadBatch != null && pollingOperations.isEmpty()) throw new IllegalArgumentException("Background reads require scheduled polling.");
        this.backgroundReadBatch = backgroundReadBatch;
        this.writeDrivenPollIntervalMs = writeDrivenPollIntervalMs;
    }

    private static String required(String value, String name) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " is required.");
        }
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}

