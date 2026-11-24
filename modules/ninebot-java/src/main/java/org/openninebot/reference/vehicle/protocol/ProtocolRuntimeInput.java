package org.openninebot.reference.vehicle.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Timestamped event delivered by a host-owned link/session actor. */
public final class ProtocolRuntimeInput {
  public enum Kind {
    /** Legacy channel-ready event. Prefer {@link #linkReady(long, long, String, int, boolean)}. */
    CHANNEL_READY,
    LINK_READY,
    SUBSCRIPTION_CHANGED,
    MTU_CHANGED,
    /** An accepted acknowledged write is still waiting for its transport completion callback. */
    WRITE_PENDING,
    WRITE_COMPLETED,
    BYTES,
    TIMER,
    FRESH_REQUEST,
    MANUAL_READ_REQUEST,
    OPERATION_REQUEST,
    CREDENTIAL_AVAILABLE,
    WORKLOAD_CHANGED,
    VEHICLE_PARAMS_CHANGED,
    SUSPEND,
    CLOSE
  }

  private final Kind kind;
  private final long linkGeneration;
  private final long monotonicNanos;
  private final String key;
  private final byte[] bytes;
  private final String channelId;
  private final String characteristicId;
  private final String requestId;
  private final String operationId;
  private final String credentialRef;
  private final String workloadProfile;
  private final int mtu;
  private final long pollIntervalMs;
  private final boolean success;
  private final boolean subscribed;
  private final Map<String, ProtocolValue> vehicleParams;

  private ProtocolRuntimeInput(
      Kind kind,
      long linkGeneration,
      long monotonicNanos,
      String key,
      byte[] bytes,
      String channelId,
      String characteristicId,
      String requestId,
      String operationId,
      String credentialRef,
      String workloadProfile,
      int mtu,
      long pollIntervalMs,
      boolean success,
      boolean subscribed,
      Map<String, ProtocolValue> vehicleParams) {
    if (kind == null) throw new IllegalArgumentException("kind");
    if (linkGeneration < 1L || monotonicNanos < 0L) throw new IllegalArgumentException("time/generation");
    this.kind = kind;
    this.linkGeneration = linkGeneration;
    this.monotonicNanos = monotonicNanos;
    this.key = clean(key);
    this.bytes = bytes == null ? new byte[0] : bytes.clone();
    this.channelId = clean(channelId);
    this.characteristicId = clean(characteristicId);
    this.requestId = clean(requestId);
    this.operationId = clean(operationId);
    this.credentialRef = clean(credentialRef);
    this.workloadProfile = clean(workloadProfile);
    this.mtu = mtu;
    this.pollIntervalMs = pollIntervalMs;
    this.success = success;
    this.subscribed = subscribed;
    LinkedHashMap<String, ProtocolValue> params = new LinkedHashMap<>();
    if (vehicleParams != null) {
      for (Map.Entry<String, ProtocolValue> entry : vehicleParams.entrySet()) {
        String name = clean(entry.getKey());
        if (name.isEmpty() || entry.getValue() == null) throw new IllegalArgumentException("vehicleParams");
        params.put(name, entry.getValue());
      }
    }
    this.vehicleParams = Collections.unmodifiableMap(params);
  }

  public static ProtocolRuntimeInput event(Kind kind, long generation, long atNanos, String key) {
    return create(kind, generation, atNanos, key, null, "", "", "", "", "", "", 0, -1L, false, false, null);
  }

  public static ProtocolRuntimeInput bytes(long generation, long atNanos, String channel, byte[] value) {
    String channelId = requireText(channel, "channel");
    return create(Kind.BYTES, generation, atNanos, channelId, value, channelId, "", "", "", "", "", 0, -1L, false, false, null);
  }

  public static ProtocolRuntimeInput linkReady(
      long generation,
      long atNanos,
      String channelId,
      int actualMtu,
      boolean subscribed) {
    requireMtu(actualMtu);
    String channel = requireText(channelId, "channelId");
    return create(Kind.LINK_READY, generation, atNanos, channel, null, channel, "", "", "", "", "", actualMtu, -1L, true, subscribed, null);
  }

  public static ProtocolRuntimeInput subscriptionChanged(
      long generation,
      long atNanos,
      String channelId,
      boolean subscribed) {
    String channel = requireText(channelId, "channelId");
    return create(Kind.SUBSCRIPTION_CHANGED, generation, atNanos, channel, null, channel, "", "", "", "", "", 0, -1L, true, subscribed, null);
  }

  public static ProtocolRuntimeInput mtuChanged(long generation, long atNanos, int actualMtu) {
    requireMtu(actualMtu);
    return create(Kind.MTU_CHANGED, generation, atNanos, "", null, "", "", "", "", "", "", actualMtu, -1L, true, false, null);
  }

  public static ProtocolRuntimeInput writePending(
      long generation, long atNanos, String requestId, String characteristicId) {
    String request = requireText(requestId, "requestId");
    String characteristic = requireText(characteristicId, "characteristicId");
    return create(Kind.WRITE_PENDING, generation, atNanos, request, null, "", characteristic, request,
        "", "", "", 0, -1L, true, false, null);
  }

  public static ProtocolRuntimeInput writeCompleted(
      long generation,
      long atNanos,
      String requestId,
      String characteristicId,
      boolean success) {
    String request = requireText(requestId, "requestId");
    String characteristic = requireText(characteristicId, "characteristicId");
    return create(Kind.WRITE_COMPLETED, generation, atNanos, request, null, "", characteristic, request, "", "", "", 0, -1L, success, false, null);
  }

  public static ProtocolRuntimeInput freshRequest(long generation, long atNanos, String requestId) {
    String request = requireText(requestId, "requestId");
    return create(Kind.FRESH_REQUEST, generation, atNanos, request, null, "", "", request, "", "", "", 0, -1L, true, false, null);
  }

  public static ProtocolRuntimeInput manualReadRequest(
      long generation,
      long atNanos,
      String requestId,
      String operationId) {
    String request = requireText(requestId, "requestId");
    String operation = requireText(operationId, "operationId");
    return create(Kind.MANUAL_READ_REQUEST, generation, atNanos, operation, null, "", "", request, operation, "", "", 0, -1L, true, false, null);
  }

  public static ProtocolRuntimeInput credentialAvailable(
      long generation,
      long atNanos,
      String credentialRef,
      byte[] credential) {
    String reference = requireText(credentialRef, "credentialRef");
    if (credential == null || credential.length == 0) throw new IllegalArgumentException("credential");
    return create(Kind.CREDENTIAL_AVAILABLE, generation, atNanos, reference, credential, "", "", "", "", reference, "", 0, -1L, true, false, null);
  }

  public static ProtocolRuntimeInput operationRequest(long generation, long atNanos, String requestId,
      String operationId, Map<String, ProtocolValue> parameters) {
    return create(Kind.OPERATION_REQUEST, generation, atNanos, operationId, null, "", "",
        requireText(requestId, "requestId"), requireText(operationId, "operationId"), "", "", 0, -1L, true, false, parameters);
  }

  public Map<String, ProtocolValue> parameters() { return vehicleParams; }

  public static ProtocolRuntimeInput workloadChanged(
      long generation,
      long atNanos,
      String workloadProfile,
      long pollIntervalMs) {
    String workload = requireText(workloadProfile, "workloadProfile");
    if (pollIntervalMs < 0L) throw new IllegalArgumentException("pollIntervalMs");
    return create(Kind.WORKLOAD_CHANGED, generation, atNanos, workload, null, "", "", "", "", "", workload, 0, pollIntervalMs, true, false, null);
  }

  public static ProtocolRuntimeInput vehicleParamsChanged(
      long generation,
      long atNanos,
      Map<String, ProtocolValue> vehicleParams) {
    if (vehicleParams == null) throw new IllegalArgumentException("vehicleParams");
    return create(Kind.VEHICLE_PARAMS_CHANGED, generation, atNanos, "", null, "", "", "", "", "", "", 0, -1L, true, false, vehicleParams);
  }

  public Kind kind() { return kind; }
  public long linkGeneration() { return linkGeneration; }
  public long monotonicNanos() { return monotonicNanos; }
  public String key() { return key; }
  public byte[] bytes() { return bytes.clone(); }
  public String channelId() { return channelId; }
  public String characteristicId() { return characteristicId; }
  public String requestId() { return requestId; }
  public String operationId() { return operationId; }
  public String credentialRef() { return credentialRef; }
  public String workloadProfile() { return workloadProfile; }
  public int mtu() { return mtu; }
  public long pollIntervalMs() { return pollIntervalMs; }
  public boolean success() { return success; }
  public boolean subscribed() { return subscribed; }
  public Map<String, ProtocolValue> vehicleParams() { return vehicleParams; }

  /** Never renders payload or credential bytes. */
  @Override
  public String toString() {
    return "ProtocolRuntimeInput{" +
        "kind=" + kind +
        ", linkGeneration=" + linkGeneration +
        ", monotonicNanos=" + monotonicNanos +
        ", channelId='" + channelId + '\'' +
        ", characteristicId='" + characteristicId + '\'' +
        ", requestId='" + requestId + '\'' +
        ", operationId='" + operationId + '\'' +
        ", credentialPresent=" + (kind == Kind.CREDENTIAL_AVAILABLE && bytes.length > 0) +
        ", payloadLength=" + bytes.length +
        ", workloadProfile='" + workloadProfile + '\'' +
        ", mtu=" + mtu +
        ", pollIntervalMs=" + pollIntervalMs +
        ", success=" + success +
        ", subscribed=" + subscribed +
        ", vehicleParamKeys=" + vehicleParams.keySet() +
        '}';
  }

  private static ProtocolRuntimeInput create(
      Kind kind,
      long generation,
      long atNanos,
      String key,
      byte[] bytes,
      String channelId,
      String characteristicId,
      String requestId,
      String operationId,
      String credentialRef,
      String workloadProfile,
      int mtu,
      long pollIntervalMs,
      boolean success,
      boolean subscribed,
      Map<String, ProtocolValue> vehicleParams) {
    return new ProtocolRuntimeInput(
        kind, generation, atNanos, key, bytes, channelId, characteristicId,
        requestId, operationId, credentialRef, workloadProfile, mtu,
        pollIntervalMs, success, subscribed, vehicleParams);
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }

  private static String requireText(String value, String name) {
    String result = clean(value);
    if (result.isEmpty()) throw new IllegalArgumentException(name);
    return result;
  }

  private static void requireMtu(int mtu) {
    if (mtu < 23 || mtu > 517) throw new IllegalArgumentException("mtu");
  }
}
