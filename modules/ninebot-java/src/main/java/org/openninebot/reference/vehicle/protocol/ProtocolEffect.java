package org.openninebot.reference.vehicle.protocol;

/** Effect requested by a pure protocol engine; the host owns execution. */
public final class ProtocolEffect {
  public enum Type {
    SEND,
    SCHEDULE_TIMER,
    CANCEL_TIMER,
    SNAPSHOT,
    IDENTITY_CONFIRMED,
    READY,
    REQUIRES_CREDENTIAL,
    REQUEST_RECONNECT,
    DIAGNOSTIC,
    OPERATION_RESULT,
    ERROR
  }
  public enum WriteMode { WITH_RESPONSE, WITHOUT_RESPONSE }

  private final Type type;
  private final String key;
  private final byte[] bytes;
  private final long delayMs;
  private final WriteMode writeMode;
  private final ProtocolSnapshot snapshot;
  private final String requestId;
  private final String characteristicId;
  private final String message;
  private final ProtocolOperationResult operationResult;

  private ProtocolEffect(
      Type type,
      String key,
      byte[] bytes,
      long delayMs,
      WriteMode writeMode,
      ProtocolSnapshot snapshot,
      String requestId,
      String characteristicId,
      String message) {
    this(type, key, bytes, delayMs, writeMode, snapshot, requestId, characteristicId, message, null);
  }

  private ProtocolEffect(Type type, String key, byte[] bytes, long delayMs, WriteMode writeMode,
      ProtocolSnapshot snapshot, String requestId, String characteristicId, String message,
      ProtocolOperationResult operationResult) {
    if (type == null) throw new IllegalArgumentException("type");
    this.type = type;
    this.key = clean(key);
    this.bytes = bytes == null ? new byte[0] : bytes.clone();
    this.delayMs = delayMs;
    this.writeMode = writeMode;
    this.snapshot = snapshot;
    this.requestId = clean(requestId);
    this.characteristicId = clean(characteristicId);
    this.message = clean(message);
    this.operationResult = operationResult;
  }

  /** Legacy send factory. Prefer the overload with request and characteristic identifiers. */
  public static ProtocolEffect send(String channel, byte[] bytes, WriteMode writeMode) {
    if (bytes == null || bytes.length == 0 || writeMode == null) throw new IllegalArgumentException("send");
    return new ProtocolEffect(Type.SEND, requireText(channel, "channel"), bytes, 0L, writeMode, null, "", "", "");
  }

  public static ProtocolEffect send(
      String requestId,
      String channel,
      String characteristicId,
      byte[] bytes,
      WriteMode writeMode) {
    if (bytes == null || bytes.length == 0 || writeMode == null) throw new IllegalArgumentException("send");
    return new ProtocolEffect(
        Type.SEND,
        requireText(channel, "channel"),
        bytes,
        0L,
        writeMode,
        null,
        requireText(requestId, "requestId"),
        requireText(characteristicId, "characteristicId"),
        "");
  }

  public static ProtocolEffect schedule(String timerKey, long delayMs) {
    if (timerKey == null || timerKey.isEmpty() || delayMs < 0L) throw new IllegalArgumentException("timer");
    return new ProtocolEffect(Type.SCHEDULE_TIMER, timerKey, null, delayMs, null, null, "", "", "");
  }

  public static ProtocolEffect cancel(String timerKey) {
    return new ProtocolEffect(Type.CANCEL_TIMER, requireText(timerKey, "timerKey"), null, 0L, null, null, "", "", "");
  }

  public static ProtocolEffect snapshot(ProtocolSnapshot snapshot) {
    if (snapshot == null) throw new IllegalArgumentException("snapshot");
    return new ProtocolEffect(Type.SNAPSHOT, "", null, 0L, null, snapshot, "", "", "");
  }

  public static ProtocolEffect identityConfirmed(String variantOrProfile) {
    return new ProtocolEffect(Type.IDENTITY_CONFIRMED, requireText(variantOrProfile, "variantOrProfile"), null, 0L, null, null, "", "", "");
  }

  public static ProtocolEffect ready(String phase) {
    return new ProtocolEffect(Type.READY, requireText(phase, "phase"), null, 0L, null, null, "", "", "");
  }

  public static ProtocolEffect requiresCredential(String credentialRef) {
    return new ProtocolEffect(
        Type.REQUIRES_CREDENTIAL,
        requireText(credentialRef, "credentialRef"),
        null,
        0L,
        null,
        null,
        "",
        "",
        "");
  }

  public static ProtocolEffect requestReconnect(String reason) {
    return new ProtocolEffect(
        Type.REQUEST_RECONNECT,
        requireText(reason, "reason"),
        null,
        0L,
        null,
        null,
        "",
        "",
        "");
  }

  /** The caller must provide an already-sanitized summary; binary payloads are not accepted. */
  public static ProtocolEffect diagnostic(String stage, String sanitizedMessage) {
    return new ProtocolEffect(
        Type.DIAGNOSTIC,
        requireText(stage, "stage"),
        null,
        0L,
        null,
        null,
        "",
        "",
        requireText(sanitizedMessage, "sanitizedMessage"));
  }

  public static ProtocolEffect error(String errorCode) {
    return new ProtocolEffect(Type.ERROR, requireText(errorCode, "errorCode"), null, 0L, null, null, "", "", "");
  }

  public static ProtocolEffect error(String errorCode, String message) {
    return new ProtocolEffect(Type.ERROR, requireText(errorCode, "errorCode"), null, 0L, null, null, "", "", message);
  }

  public static ProtocolEffect operationResult(ProtocolOperationResult result) {
    if (result == null) throw new IllegalArgumentException("result");
    return new ProtocolEffect(Type.OPERATION_RESULT, result.operationId, null, 0L, null, null,
        result.requestId, "", "", result);
  }
  public ProtocolOperationResult operationResult() { return operationResult; }

  public Type type() { return type; }
  public String key() { return key; }
  public byte[] bytes() { return bytes.clone(); }
  public long delayMs() { return delayMs; }
  public WriteMode writeMode() { return writeMode; }
  public ProtocolSnapshot snapshot() { return snapshot; }
  public String requestId() { return requestId; }
  public String characteristicId() { return characteristicId; }
  public String message() { return message; }

  /** Never renders wire bytes or snapshot field values. */
  @Override
  public String toString() {
    return "ProtocolEffect{" +
        "type=" + type +
        ", key='" + key + '\'' +
        ", requestId='" + requestId + '\'' +
        ", characteristicId='" + characteristicId + '\'' +
        ", payloadLength=" + bytes.length +
        ", delayMs=" + delayMs +
        ", writeMode=" + writeMode +
        ", snapshotPresent=" + (snapshot != null) +
        ", message='" + message + '\'' +
        '}';
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }

  private static String requireText(String value, String name) {
    String result = clean(value);
    if (result.isEmpty()) throw new IllegalArgumentException(name);
    return result;
  }
}
