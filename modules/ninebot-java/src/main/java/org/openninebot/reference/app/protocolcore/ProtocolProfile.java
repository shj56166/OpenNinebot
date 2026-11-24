package org.openninebot.reference.app.protocolcore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Platform-neutral restricted protocol contract shared by formal controller
 * sessions and the protocol-test UI adapter. Each profile owns its fixed,
 * reviewed request allowlist; this layer deliberately contains no Android,
 * Capacitor, GATT-object, or persistence dependency.
 */
public interface ProtocolProfile {
    /** Matches Android's acknowledged characteristic write value without importing Android. */
    int WRITE_TYPE_WITH_RESPONSE = 2;
    /** Matches Android's write-without-response value without importing Android. */
    int WRITE_TYPE_WITHOUT_RESPONSE = 1;

    Definition definition();

    /**
     * Returns one approved request from this profile's read plan. A null
     * request id starts the operation; continuation ids must be locally
     * declared by the profile and are never derived from remote input.
     */
    ReadRequest buildRequest(String operationId, String requestId, Credential credential);

    /** Supplies bounded, session-only context derived by the transport. */
    default void setConnectionContext(ConnectionContext context) {}

    /** Drops buffered bytes at a transport transaction boundary. */
    default void invalidatePendingResponse() {}

    /**
     * Drops bytes received after a response has already scheduled a delayed
     * continuation. Unlike {@link #invalidatePendingResponse()}, this must
     * preserve any connection-local discovery state needed by that next read.
     */
    default void discardStaleResponseBytes() {
        invalidatePendingResponse();
    }

    /** Whether an operation failure requires a fresh temporary GATT connection. */
    default boolean requiresReconnectAfterOperationFailure() {
        return false;
    }

    default boolean requiresReconnectAfterOperationFailure(String operationId) {
        return requiresReconnectAfterOperationFailure();
    }

    /** A fixed grace window from operation start; notifications cannot extend it. */
    default long notificationLivenessGraceMs(String operationId) { return 0L; }

    /**
     * Legacy compatibility hook. Diagnostics retain original notification payloads.
     */
    default boolean shouldRedactWireNotifications() {
        return false;
    }

    DecodeResult consume(
        byte[] bytes,
        String channelId,
        String activeOperationId,
        String activeRequestId,
        long observedAtEpochMs
    );

    default void reset() {}

    final class ConnectionContext {
        private final int customerId;
        private final int negotiatedMtu;
        private final boolean legacyBroadcast;

        public ConnectionContext(int customerId) {
            this(customerId, 23);
        }

        public ConnectionContext(int customerId, int negotiatedMtu) {
            this(customerId, negotiatedMtu, customerId == 0);
        }

        public ConnectionContext(int customerId, int negotiatedMtu, boolean legacyBroadcast) {
            this.customerId = customerId & 0xFFFF;
            this.negotiatedMtu = Math.max(23, negotiatedMtu);
            this.legacyBroadcast = legacyBroadcast;
        }

        public int customerId() {
            return customerId;
        }

        public int negotiatedMtu() {
            return negotiatedMtu;
        }

        public boolean legacyBroadcast() { return legacyBroadcast; }
    }

    final class Definition {
        public final String id;
        public final String role;
        public final String displayName;
        public final String subtype;
        public final String maturity;
        public final List<String> scanHints;
        public final List<ReadOperation> operations;
        public final boolean needsCredential;
        public final String safetyNotice;
        public final ProfileRuntimePlan runtimePlan;
        /** Optional protocol-test collector; does not register a production runner. */
        public final AutomaticCapturePlan automaticCapturePlan;

        public Definition(
            String id,
            String role,
            String displayName,
            String subtype,
            String maturity,
            List<String> scanHints,
            List<ReadOperation> operations,
            boolean needsCredential,
            String safetyNotice
        ) {
            this(
                id,
                role,
                displayName,
                subtype,
                maturity,
                scanHints,
                operations,
                needsCredential,
                safetyNotice,
                null
            );
        }

        public Definition(
            String id,
            String role,
            String displayName,
            String subtype,
            String maturity,
            List<String> scanHints,
            List<ReadOperation> operations,
            boolean needsCredential,
            String safetyNotice,
            ProfileRuntimePlan runtimePlan
        ) {
            this(id, role, displayName, subtype, maturity, scanHints, operations,
                needsCredential, safetyNotice, runtimePlan, null);
        }

        public Definition(String id, String role, String displayName, String subtype,
            String maturity, List<String> scanHints, List<ReadOperation> operations,
            boolean needsCredential, String safetyNotice, ProfileRuntimePlan runtimePlan,
            AutomaticCapturePlan automaticCapturePlan) {
            this.id = id;
            this.role = role;
            this.displayName = displayName;
            this.subtype = subtype;
            this.maturity = maturity;
            this.scanHints = scanHints == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(scanHints));
            this.operations = operations == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(operations));
            this.needsCredential = needsCredential;
            this.safetyNotice = safetyNotice;
            this.runtimePlan = runtimePlan;
            this.automaticCapturePlan = automaticCapturePlan;
        }
    }

    final class ReadOperation {
        public final String id;
        public final String label;
        public final String maturity;
        public final boolean automatic;
        public final String safetyNote;
        /** Whether the operation may be started explicitly instead of by its automatic sequence. */
        public final boolean manualStartAllowed;
        /** Delay before the first automatic request after notification subscription. */
        public final long automaticStartDelayMs;
        /** Minimum interval between the first requests of repeated automatic operation cycles. */
        public final long automaticRepeatIntervalMs;

        public ReadOperation(String id, String label, String maturity, boolean automatic, String safetyNote) {
            this(id, label, maturity, automatic, safetyNote, 0L, 0L, true);
        }

        public ReadOperation(
            String id,
            String label,
            String maturity,
            boolean automatic,
            String safetyNote,
            boolean manualStartAllowed
        ) {
            this(id, label, maturity, automatic, safetyNote, 0L, 0L, manualStartAllowed);
        }

        public ReadOperation(
            String id,
            String label,
            String maturity,
            boolean automatic,
            String safetyNote,
            long automaticStartDelayMs
        ) {
            this(id, label, maturity, automatic, safetyNote, automaticStartDelayMs, 0L, true);
        }

        public ReadOperation(
            String id,
            String label,
            String maturity,
            boolean automatic,
            String safetyNote,
            long automaticStartDelayMs,
            long automaticRepeatIntervalMs
        ) {
            this(id, label, maturity, automatic, safetyNote, automaticStartDelayMs, automaticRepeatIntervalMs, true);
        }

        public ReadOperation(
            String id,
            String label,
            String maturity,
            boolean automatic,
            String safetyNote,
            long automaticStartDelayMs,
            long automaticRepeatIntervalMs,
            boolean manualStartAllowed
        ) {
            if (automaticStartDelayMs < 0L || automaticStartDelayMs > 10_000L) {
                throw new IllegalArgumentException("Automatic start delays must be between 0 and 10 seconds.");
            }
            if (automaticRepeatIntervalMs < 0L || automaticRepeatIntervalMs > 60_000L) {
                throw new IllegalArgumentException("Automatic repeat intervals must be between 0 and 60 seconds.");
            }
            if (!automatic && (automaticStartDelayMs != 0L || automaticRepeatIntervalMs != 0L)) {
                throw new IllegalArgumentException("Only automatic operations may declare a start delay or repeat interval.");
            }
            if (!automatic && !manualStartAllowed) {
                throw new IllegalArgumentException("Only automatic operations may prohibit manual starts.");
            }
            this.id = id;
            this.label = label;
            this.maturity = maturity;
            this.automatic = automatic;
            this.safetyNote = safetyNote;
            this.manualStartAllowed = manualStartAllowed;
            this.automaticStartDelayMs = automaticStartDelayMs;
            this.automaticRepeatIntervalMs = automaticRepeatIntervalMs;
        }
    }

    final class ReadRequest {
        public final String id;
        private final byte[] bytes;
        /** Null uses the channel's default write characteristic. */
        public final String writeCharacteristicUuid;
        public final int writeType;
        /** A fixed read-plan step that completes after the local BLE write is accepted. */
        public final boolean completeOnWrite;
        /** A fixed, locally declared continuation sent after the local BLE write is accepted. */
        public final String nextRequestIdOnWrite;
        /** Delay before a locally accepted write may send its fixed continuation. */
        public final long writeContinuationDelayMs;
        /** Zero disables retries; every retry is the same locally declared request. */
        public final long retryIntervalMs;
        /** -1 retries while the session stays active; otherwise this is the retry count. */
        public final int maxRetryCount;
        /** Delay before a matched response may send its locally declared continuation. */
        public final long responseContinuationDelayMs;
        /** Optional per-request response deadline; zero uses the runner default. */
        public final long responseTimeoutMs;
        /** A fixed, locally declared continuation allowed only after this request times out. */
        public final String nextRequestIdOnTimeout;

        public ReadRequest(String id, byte[] bytes) {
            this(id, bytes, null, WRITE_TYPE_WITH_RESPONSE, false, null, 0L, 0, 0L);
        }

        public ReadRequest(String id, byte[] bytes, String writeCharacteristicUuid, int writeType) {
            this(id, bytes, writeCharacteristicUuid, writeType, false, null, 0L, 0, 0L);
        }

        public ReadRequest(
            String id,
            byte[] bytes,
            String writeCharacteristicUuid,
            int writeType,
            boolean completeOnWrite
        ) {
            this(id, bytes, writeCharacteristicUuid, writeType, completeOnWrite, null, 0L, 0, 0L);
        }

        public ReadRequest(
            String id,
            byte[] bytes,
            String writeCharacteristicUuid,
            int writeType,
            boolean completeOnWrite,
            String nextRequestIdOnWrite
        ) {
            this(id, bytes, writeCharacteristicUuid, writeType, completeOnWrite, nextRequestIdOnWrite, 0L, 0, 0L);
        }

        public ReadRequest(
            String id,
            byte[] bytes,
            String writeCharacteristicUuid,
            int writeType,
            boolean completeOnWrite,
            String nextRequestIdOnWrite,
            long writeContinuationDelayMs
        ) {
            this(
                id,
                bytes,
                writeCharacteristicUuid,
                writeType,
                completeOnWrite,
                nextRequestIdOnWrite,
                writeContinuationDelayMs,
                0L,
                0,
                0L,
                0L,
                null
            );
        }

        public ReadRequest(
            String id,
            byte[] bytes,
            String writeCharacteristicUuid,
            int writeType,
            boolean completeOnWrite,
            String nextRequestIdOnWrite,
            long retryIntervalMs,
            int maxRetryCount
        ) {
            this(
                id,
                bytes,
                writeCharacteristicUuid,
                writeType,
                completeOnWrite,
                nextRequestIdOnWrite,
                retryIntervalMs,
                maxRetryCount,
                0L
            );
        }

        public ReadRequest(
            String id,
            byte[] bytes,
            String writeCharacteristicUuid,
            int writeType,
            boolean completeOnWrite,
            String nextRequestIdOnWrite,
            long retryIntervalMs,
            int maxRetryCount,
            long responseContinuationDelayMs
        ) {
            this(
                id,
                bytes,
                writeCharacteristicUuid,
                writeType,
                completeOnWrite,
                nextRequestIdOnWrite,
                retryIntervalMs,
                maxRetryCount,
                responseContinuationDelayMs,
                0L,
                null
            );
        }

        public ReadRequest(
            String id,
            byte[] bytes,
            String writeCharacteristicUuid,
            int writeType,
            boolean completeOnWrite,
            String nextRequestIdOnWrite,
            long retryIntervalMs,
            int maxRetryCount,
            long responseContinuationDelayMs,
            long responseTimeoutMs,
            String nextRequestIdOnTimeout
        ) {
            this(
                id,
                bytes,
                writeCharacteristicUuid,
                writeType,
                completeOnWrite,
                nextRequestIdOnWrite,
                0L,
                retryIntervalMs,
                maxRetryCount,
                responseContinuationDelayMs,
                responseTimeoutMs,
                nextRequestIdOnTimeout
            );
        }

        private ReadRequest(
            String id,
            byte[] bytes,
            String writeCharacteristicUuid,
            int writeType,
            boolean completeOnWrite,
            String nextRequestIdOnWrite,
            long writeContinuationDelayMs,
            long retryIntervalMs,
            int maxRetryCount,
            long responseContinuationDelayMs,
            long responseTimeoutMs,
            String nextRequestIdOnTimeout
        ) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("Read request id is required.");
            }
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("Read request bytes are required.");
            }
            if (writeType != WRITE_TYPE_WITH_RESPONSE && writeType != WRITE_TYPE_WITHOUT_RESPONSE) {
                throw new IllegalArgumentException("Only standard BLE write types are allowed.");
            }
            if (nextRequestIdOnWrite != null && nextRequestIdOnWrite.trim().isEmpty()) {
                throw new IllegalArgumentException("A write continuation requires a request id.");
            }
            if (completeOnWrite && nextRequestIdOnWrite != null) {
                throw new IllegalArgumentException("A request cannot both complete and continue on write.");
            }
            if (writeContinuationDelayMs < 0L || writeContinuationDelayMs > 10_000L) {
                throw new IllegalArgumentException("Write continuation delays must be between 0 and 10 seconds.");
            }
            if (writeContinuationDelayMs > 0L && (completeOnWrite || nextRequestIdOnWrite == null)) {
                throw new IllegalArgumentException("A write continuation delay requires a fixed write continuation.");
            }
            if (retryIntervalMs < 0L || retryIntervalMs > 60_000L || (retryIntervalMs > 0L && retryIntervalMs < 50L)) {
                throw new IllegalArgumentException("Retry intervals must be between 50 ms and 60 seconds.");
            }
            if (maxRetryCount < -1 || (retryIntervalMs == 0L && maxRetryCount != 0)) {
                throw new IllegalArgumentException("Retry count requires a valid retry interval.");
            }
            if (
                retryIntervalMs > 0L &&
                (writeType != WRITE_TYPE_WITH_RESPONSE || completeOnWrite || nextRequestIdOnWrite != null)
            ) {
                throw new IllegalArgumentException("Retrying requests must wait for an acknowledged response without a write continuation.");
            }
            if (responseContinuationDelayMs < 0L || responseContinuationDelayMs > 10_000L) {
                throw new IllegalArgumentException("Response continuation delays must be between 0 and 10 seconds.");
            }
            if (responseContinuationDelayMs > 0L && (completeOnWrite || nextRequestIdOnWrite != null)) {
                throw new IllegalArgumentException("A response continuation delay requires a response-driven request chain.");
            }
            if (responseTimeoutMs != 0L && (responseTimeoutMs < 50L || responseTimeoutMs > 60_000L)) {
                throw new IllegalArgumentException("Response timeouts must be zero or between 50 ms and 60 seconds.");
            }
            if (
                nextRequestIdOnTimeout != null &&
                (nextRequestIdOnTimeout.trim().isEmpty() || responseTimeoutMs == 0L)
            ) {
                throw new IllegalArgumentException("A timeout continuation requires a fixed response timeout and request id.");
            }
            if (
                nextRequestIdOnTimeout != null &&
                (completeOnWrite || nextRequestIdOnWrite != null || retryIntervalMs != 0L || maxRetryCount != 0)
            ) {
                throw new IllegalArgumentException("A timeout continuation cannot be combined with write continuations or retries.");
            }
            this.id = id;
            this.bytes = bytes.clone();
            this.writeCharacteristicUuid = writeCharacteristicUuid == null || writeCharacteristicUuid.trim().isEmpty()
                ? null
                : writeCharacteristicUuid.trim();
            this.writeType = writeType;
            this.completeOnWrite = completeOnWrite;
            this.nextRequestIdOnWrite = nextRequestIdOnWrite == null ? null : nextRequestIdOnWrite.trim();
            this.writeContinuationDelayMs = writeContinuationDelayMs;
            this.retryIntervalMs = retryIntervalMs;
            this.maxRetryCount = maxRetryCount;
            this.responseContinuationDelayMs = responseContinuationDelayMs;
            this.responseTimeoutMs = responseTimeoutMs;
            this.nextRequestIdOnTimeout = nextRequestIdOnTimeout == null ? null : nextRequestIdOnTimeout.trim();
        }

        public byte[] bytes() {
            return bytes.clone();
        }
    }

    final class Credential {
        private byte[] bytes;

        public Credential(byte[] value) {
            bytes = value == null ? new byte[0] : value.clone();
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        public boolean isPresent() {
            return bytes.length > 0;
        }

        public void clear() {
            Arrays.fill(bytes, (byte) 0);
            bytes = new byte[0];
        }
    }

    final class Field {
        public enum Exposure {
            PUBLIC,
            DIAGNOSTIC
        }

        public final String key;
        public final String group;
        public final String label;
        public final Object value;
        public final String unit;
        public final String raw;
        public final String maturity;
        public final String source;
        public final long observedAtEpochMs;
        public final String warning;
        public final Exposure exposure;

        public Field(
            String key,
            String group,
            String label,
            Object value,
            String unit,
            String raw,
            String maturity,
            String source,
            long observedAtEpochMs,
            String warning
        ) {
            this(key, group, label, value, unit, raw, maturity, source, observedAtEpochMs, warning, Exposure.PUBLIC);
        }

        public Field(
            String key,
            String group,
            String label,
            Object value,
            String unit,
            String raw,
            String maturity,
            String source,
            long observedAtEpochMs,
            String warning,
            Exposure exposure
        ) {
            this.key = key;
            this.group = group;
            this.label = label;
            this.value = value;
            this.unit = unit;
            this.raw = raw;
            this.maturity = maturity;
            this.source = source;
            this.observedAtEpochMs = observedAtEpochMs;
            this.warning = warning;
            this.exposure = exposure == null ? Exposure.PUBLIC : exposure;
        }
    }

    final class Frame {
        public final String direction;
        public final String bytesHex;
        public final boolean valid;
        public final String validation;
        public final String operationId;
        public final String channelId;
        public final long observedAtEpochMs;
        public final boolean redacted;

        public Frame(
            String direction,
            String bytesHex,
            boolean valid,
            String validation,
            String operationId,
            String channelId,
            long observedAtEpochMs,
            boolean redacted
        ) {
            this.direction = direction;
            this.bytesHex = bytesHex;
            this.valid = valid;
            this.validation = validation;
            this.operationId = operationId;
            this.channelId = channelId;
            this.observedAtEpochMs = observedAtEpochMs;
            this.redacted = redacted;
        }
    }

    final class DecodeResult {
        public final List<Field> fields;
        public final List<Frame> frames;
        /** True only when the active request's response was positively matched. */
        public final boolean matched;
        /** True only after the current read operation is fully complete. */
        public final boolean operationComplete;
        /** A locally declared follow-up request for the current operation. */
        public final String nextRequestId;
        /** A positively matched response that must fail the current operation. */
        public final String failureMessage;

        public DecodeResult(
            List<Field> fields,
            List<Frame> frames,
            boolean matched,
            boolean operationComplete,
            String nextRequestId
        ) {
            this(fields, frames, matched, operationComplete, nextRequestId, null);
        }

        private DecodeResult(
            List<Field> fields,
            List<Frame> frames,
            boolean matched,
            boolean operationComplete,
            String nextRequestId,
            String failureMessage
        ) {
            if (operationComplete && !matched) {
                throw new IllegalArgumentException("An operation cannot complete without a matched response.");
            }
            if (nextRequestId != null && (!matched || operationComplete)) {
                throw new IllegalArgumentException("A continuation requires a matched incomplete response.");
            }
            if (failureMessage != null && (!matched || operationComplete || nextRequestId != null || failureMessage.trim().isEmpty())) {
                throw new IllegalArgumentException("A failure must be a matched incomplete response without a continuation.");
            }
            this.fields = fields == null ? Collections.emptyList() : fields;
            this.frames = frames == null ? Collections.emptyList() : frames;
            this.matched = matched;
            this.operationComplete = operationComplete;
            this.nextRequestId = nextRequestId;
            this.failureMessage = failureMessage;
        }

        public static DecodeResult empty() {
            return pending(Collections.emptyList(), Collections.emptyList());
        }

        public static DecodeResult pending(List<Field> fields, List<Frame> frames) {
            return new DecodeResult(fields, frames, false, false, null);
        }

        public static DecodeResult matched(List<Field> fields, List<Frame> frames) {
            return new DecodeResult(fields, frames, true, false, null);
        }

        public static DecodeResult complete(List<Field> fields, List<Frame> frames) {
            return new DecodeResult(fields, frames, true, true, null);
        }

        public static DecodeResult continueWith(List<Field> fields, List<Frame> frames, String nextRequestId) {
            return new DecodeResult(fields, frames, true, false, nextRequestId);
        }

        public static DecodeResult failure(List<Field> fields, List<Frame> frames, String failureMessage) {
            return new DecodeResult(fields, frames, true, false, null, failureMessage);
        }
    }
}

