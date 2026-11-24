package org.openninebot.reference.app;

import org.openninebot.reference.app.protocolcore.NinebotVehicleGattProfile;
import org.openninebot.reference.vehicle.contract.ProtocolDescriptor;
import org.openninebot.reference.vehicle.protocol.ProtocolEffect;
import org.openninebot.reference.vehicle.protocol.ProtocolEngine;
import org.openninebot.reference.vehicle.protocol.ProtocolRuntimeInput;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure Ninebot Encryption2 handshake, credential and telemetry state machine. */
public final class NinebotVehicleProtocolEngine implements ProtocolEngine {
  public static final String PROTOCOL_FAMILY = "ninebot_gen2_nus";
  public static final String CREDENTIAL_REF = "ninebot-password";
  public static final long DIAGNOSTIC_INTERVAL_MS = 3_000L;
  public static final long DIAGNOSTIC_CAPTURE_DURATION_MS = 60_000L;
  public static final long DIAGNOSTIC_CAPTURE_LONG_DURATION_MS = 300_000L;
  public static final long DIAGNOSTIC_CAPTURE_TIMEOUT_MS = 600L;
  public static final int DIAGNOSTIC_CAPTURE_BURST_LIMIT = 2;

  private static final long PRECOMM_RETRY_MS = 2_000L;
  private static final int PRECOMM_MAX_ATTEMPTS = 10;
  private static final long SET_PASSWORD_TIMEOUT_MS = 10_000L;
  private static final long SET_PASSWORD_CONFIRM_TIMEOUT_MS = 60_000L;
  private static final long AUTH_TIMEOUT_MS = 5_000L;
  private static final long READ_TIMEOUT_MS = 1_800L;
  private static final long MIN_POLL_GAP_MS = 45L;
  private static final long WRITE_CHUNK_GAP_MS = 12L;
  private static final String POLL_TIMER = "ninebot:poll";
  private static final String RESPONSE_TIMER = "ninebot:response";
  private static final String PRECOMM_TIMER = "ninebot:precomm";
  private static final String CHUNK_TIMER = "ninebot:chunk";
  private static final String CAPTURE_END_TIMER = "ninebot:capture-end";
  private static final String DIAGNOSTIC_CONFIG_PREFIX = "ninebot-diagnostics-config-";
  private static final String CAPTURE_START_PREFIX = "ninebot-capture-start-";
  private static final String CAPTURE_STOP_OPERATION = "ninebot-capture-stop";
  private static final String[] DIAGNOSTIC_KINDS = { "voltage", "soc", "temperature" };
  private static final ProbeSpec[] KNOWN_BMS_PROBES = new ProbeSpec[] {
      probe(0x02, 4), probe(0x02, 14), probe(0x02, 16), probe(0x0A, 2), probe(0x0E, 2),
      probe(0x10, 14), probe(0x10, 16), probe(0x10, 18), probe(0x11, 2), probe(0x13, 2),
      probe(0x17, 4), probe(0x1A, 2), probe(0x1B, 2), probe(0x1B, 4), probe(0x20, 2),
      probe(0x26, 2), probe(0x27, 2), probe(0x27, 4), probe(0x2A, 4), probe(0x2C, 4),
      probe(0x2E, 4), probe(0x31, 8), probe(0x31, 10), probe(0x31, 12), probe(0x32, 2),
      probe(0x33, 2), probe(0x34, 2), probe(0x35, 2), probe(0x40, 20), probe(0x52, 2),
      probe(0x53, 2), probe(0x59, 2), probe(0x5A, 2), probe(0x5B, 2), probe(0x7A, 2),
      probe(0x80, 4), probe(0x82, 2), probe(0x82, 4), probe(0x85, 2), probe(0x86, 4),
      probe(0x88, 4), probe(0x89, 2), probe(0x8A, 2), probe(0x8B, 2), probe(0x8C, 2),
      probe(0x8C, 4), probe(0x8D, 2), probe(0x8E, 2), probe(0x8F, 2), probe(0x91, 2),
      probe(0x92, 4), probe(0x94, 2), probe(0x96, 4), probe(0x96, 16), probe(0x98, 2),
      probe(0x99, 2), probe(0x9A, 2), probe(0x9B, 2), probe(0x9C, 2), probe(0x9D, 2),
      probe(0xA0, 26), probe(0xE1, 4), probe(0xE3, 4), probe(0xEA, 2), probe(0xF5, 4),
      probe(0xF7, 4), probe(0xF9, 4)
  };
  private static final Set<String> CAPABILITIES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
      "read_snapshot", "authenticate", "vehicle_speed", "vehicle_power", "vehicle_soc",
      "vehicle_range", "vehicle_mileage", "read_only")));

  private final NinebotVehicleProfile profile;
  private final String deviceName;
  private final ProtocolDescriptor descriptor;
  private final NinebotEncryption2Codec.FrameAccumulator accumulator = new NinebotEncryption2Codec.FrameAccumulator();
  private final NinebotVehicleProtocolSnapshotMapper.State telemetry = new NinebotVehicleProtocolSnapshotMapper.State();
  private final NinebotTelemetryCodec.ReadSpec[] reads = NinebotTelemetryCodec.standardReads();
  private final ArrayDeque<byte[]> writeChunks = new ArrayDeque<>();
  private final ArrayDeque<ManualRead> manualReads = new ArrayDeque<>();
  private final long[] telemetryNextDueAtNanos = new long[reads.length];
  private final LinkedHashMap<String, DiagnosticResult> diagnosticResults = new LinkedHashMap<>();

  private NinebotEncryption2Codec.Crypto crypto = NinebotEncryption2Codec.Crypto.gen2();
  private long generation;
  private long requestSequence;
  private long snapshotSequence;
  private long pollIntervalMs = 500L;
  private long nextDiagnosticDueAtNanos = Long.MAX_VALUE;
  private long expectedReadStartedAtNanos;
  private long expectedReadTimeoutMs = READ_TIMEOUT_MS;
  private long captureSequence;
  private int mtu = NinebotEncryption2Codec.DEFAULT_ATT_MTU;
  private int precommAttempts;
  private int nextReadIndex;
  private int nextDiagnosticKindIndex;
  private int captureRequestsSinceTelemetry;
  private int consecutiveTimeouts;
  private boolean subscribed;
  private boolean suspended;
  private boolean closed;
  private boolean authenticated;
  private boolean saveCredentialOnSuccess;
  private String channelId = NinebotVehicleGattProfile.CHANNEL_NUS;
  private String activeWriteRequestId = "";
  private int expectedTarget = -1;
  private int expectedRegister = -1;
  private String expectedDiagnosticKind = "";
  private ProbeSpec expectedCaptureProbe;
  private int diagnosticTarget = NinebotTelemetryCodec.BOARD_BMS_DEFAULT;
  private boolean diagnosticVoltageEnabled;
  private boolean diagnosticSocEnabled;
  private boolean diagnosticTemperatureEnabled;
  private DiagnosticCapture diagnosticCapture = DiagnosticCapture.idle();
  private Phase phase = Phase.IDLE;
  private byte[] auth;
  private byte[] serialBytes;
  private byte[] password;

  public NinebotVehicleProtocolEngine(String profileId, String deviceName) {
    profile = NinebotVehicleProfile.resolve(profileId);
    if (profile == null) throw new IllegalArgumentException("profileId");
    this.deviceName = deviceName == null ? "" : deviceName;
    descriptor = new ProtocolDescriptor(
        PROTOCOL_FAMILY, "encryption2", profile.id, ProtocolDescriptor.Role.VEHICLE,
        ProtocolDescriptor.SamplingMode.NOTIFY, CAPABILITIES);
  }

  @Override public ProtocolDescriptor descriptor() { return descriptor; }

  public static String diagnosticConfigurationOperation(
      int target,
      boolean voltageEnabled,
      boolean socEnabled,
      boolean temperatureEnabled) {
    int flags = (voltageEnabled ? 1 : 0) | (socEnabled ? 2 : 0) | (temperatureEnabled ? 4 : 0);
    return DIAGNOSTIC_CONFIG_PREFIX + hexByte(normalizeDiagnosticTarget(target)) + "-" + flags;
  }

  public static String diagnosticCaptureStartOperation(int target, long durationMs) {
    return CAPTURE_START_PREFIX + hexByte(normalizeDiagnosticTarget(target)) + "-"
        + normalizeDiagnosticCaptureDuration(durationMs);
  }

  public static String diagnosticCaptureStopOperation() { return CAPTURE_STOP_OPERATION; }

  public static int normalizeDiagnosticTarget(int target) {
    return target == 0x07 || target == 0x22 ? target : NinebotTelemetryCodec.BOARD_BMS_DEFAULT;
  }

  public static long normalizeDiagnosticCaptureDuration(long durationMs) {
    return durationMs >= DIAGNOSTIC_CAPTURE_LONG_DURATION_MS
        ? DIAGNOSTIC_CAPTURE_LONG_DURATION_MS
        : DIAGNOSTIC_CAPTURE_DURATION_MS;
  }

  public DiagnosticStatus diagnosticStatus() {
    return new DiagnosticStatus(
        diagnosticTarget,
        diagnosticVoltageEnabled,
        diagnosticSocEnabled,
        diagnosticTemperatureEnabled,
        diagnosticResults,
        diagnosticCapture.snapshot());
  }

  @Override
  public List<ProtocolEffect> onInput(ProtocolRuntimeInput input) {
    if (input == null) return Collections.emptyList();
    if (input.kind() == ProtocolRuntimeInput.Kind.CLOSE) return closeEffects();
    if (closed) return Collections.emptyList();
    if (input.kind() == ProtocolRuntimeInput.Kind.LINK_READY) return linkReady(input);
    if (generation == 0L || input.linkGeneration() != generation) return Collections.emptyList();
    switch (input.kind()) {
      case SUBSCRIPTION_CHANGED:
        subscribed = input.success() && input.subscribed();
        suspended = !subscribed;
        return subscribed ? beginPrecomm() : cancelAll();
      case MTU_CHANGED:
        mtu = input.mtu();
        return Collections.emptyList();
      case CREDENTIAL_AVAILABLE:
        return credential(input);
      case WRITE_COMPLETED:
        if (input.requestId().equals(activeWriteRequestId) && !input.success()) return terminalFailure("ninebot-write-failed");
        return Collections.emptyList();
      case BYTES:
        return bytes(input);
      case TIMER:
        return timer(input);
      case FRESH_REQUEST:
        return authenticated && phase == Phase.IDLE
            ? sendScheduledRead(nextReadIndex, input.monotonicNanos())
            : Collections.<ProtocolEffect>emptyList();
      case WORKLOAD_CHANGED:
        boolean previousFastMode = usesFastTelemetryProfile();
        pollIntervalMs = Math.max(250L, input.pollIntervalMs());
        if (previousFastMode != usesFastTelemetryProfile()) resetTelemetrySchedule(input.monotonicNanos(), true);
        return authenticated && phase == Phase.IDLE ? schedulePoll(0L) : Collections.<ProtocolEffect>emptyList();
      case MANUAL_READ_REQUEST:
        return manualRead(input);
      case SUSPEND:
        suspended = true;
        return cancelAll();
      default:
        return Collections.emptyList();
    }
  }

  private List<ProtocolEffect> linkReady(ProtocolRuntimeInput input) {
    if (!NinebotVehicleGattProfile.isChannelId(input.channelId())) {
      return Collections.singletonList(ProtocolEffect.error("ninebot-channel-unsupported"));
    }
    reset(input.linkGeneration());
    channelId = input.channelId();
    mtu = input.mtu();
    subscribed = input.subscribed();
    suspended = !subscribed;
    return subscribed ? beginPrecomm() : Collections.<ProtocolEffect>emptyList();
  }

  private List<ProtocolEffect> beginPrecomm() {
    if (!linkUsable()) return Collections.emptyList();
    phase = Phase.WAIT_PRECOMM;
    precommAttempts = 0;
    return sendPrecomm();
  }

  private List<ProtocolEffect> sendPrecomm() {
    if (!linkUsable() || phase != Phase.WAIT_PRECOMM) return Collections.emptyList();
    if (precommAttempts >= PRECOMM_MAX_ATTEMPTS) return terminalFailure("ninebot-precomm-exhausted");
    precommAttempts += 1;
    crypto = NinebotEncryption2Codec.Crypto.gen2();
    crypto.setKey(deviceName.getBytes(StandardCharsets.UTF_8), NinebotEncryption2Codec.firmwareData());
    crypto.resetSn();
    ArrayList<ProtocolEffect> effects = new ArrayList<>(sendEncrypted(
        NinebotEncryption2Codec.buildFrame(profile.preCommTarget, NinebotEncryption2Codec.CMD_PRE_COMM, 0, null),
        "precomm", 0L));
    effects.add(ProtocolEffect.cancel(PRECOMM_TIMER));
    effects.add(ProtocolEffect.schedule(PRECOMM_TIMER, PRECOMM_RETRY_MS));
    return effects;
  }

  private List<ProtocolEffect> credential(ProtocolRuntimeInput input) {
    if (!CREDENTIAL_REF.equals(input.credentialRef()) || input.bytes().length != 16) {
      return Collections.singletonList(ProtocolEffect.error("ninebot-credential-invalid"));
    }
    clear(password);
    password = input.bytes();
    if (phase != Phase.WAIT_CREDENTIAL || serialBytes == null || auth == null) return Collections.emptyList();
    return Boolean.TRUE.equals(telemetry.vehicleHasPassword) ? sendAuth(false) : sendSetPassword();
  }

  private List<ProtocolEffect> bytes(ProtocolRuntimeInput input) {
    if (!linkUsable() || !channelId.equals(input.channelId())) return Collections.emptyList();
    ArrayList<ProtocolEffect> effects = new ArrayList<>();
    for (byte[] encrypted : accumulator.append(input.bytes())) {
      NinebotEncryption2Codec.Crypto.DecryptResult decrypted = crypto.decrypt(encrypted);
      if (decrypted.returnCode != NinebotEncryption2Codec.Crypto.SUCCESS) {
        effects.addAll(terminalFailure(decrypted.returnCode == NinebotEncryption2Codec.Crypto.REPLAY_FAILURE
            ? "ninebot-replay" : "ninebot-integrity"));
        return effects;
      }
      NinebotEncryption2Codec.Frame frame = NinebotEncryption2Codec.parseFrame(decrypted.plaintext);
      if (frame == null) {
        effects.addAll(terminalFailure("ninebot-frame-invalid"));
        return effects;
      }
      effects.addAll(frame(frame, input.monotonicNanos()));
    }
    if (accumulator.hasSyncMismatch()) {
      accumulator.consumeRejectedBuffer();
      effects.addAll(terminalFailure("ninebot-frame-sync"));
    }
    return effects;
  }

  private List<ProtocolEffect> frame(NinebotEncryption2Codec.Frame frame, long atNanos) {
    if (phase == Phase.WAIT_PRECOMM) return precommFrame(frame, atNanos);
    if (phase == Phase.WAIT_SET_PASSWORD || phase == Phase.WAIT_SET_PASSWORD_CONFIRM) return setPasswordFrame(frame);
    if (phase == Phase.WAIT_AUTH) return authFrame(frame, atNanos);
    if (phase == Phase.WAIT_READ && frame.cmd == NinebotEncryption2Codec.CMD_READ_RESP) return readFrame(frame, atNanos);
    return Collections.emptyList();
  }

  private List<ProtocolEffect> precommFrame(NinebotEncryption2Codec.Frame frame, long atNanos) {
    if (frame.cmd != NinebotEncryption2Codec.CMD_PRE_COMM || frame.data.length < 30) return terminalFailure("ninebot-precomm-invalid");
    ArrayList<ProtocolEffect> effects = new ArrayList<>();
    effects.add(ProtocolEffect.cancel(PRECOMM_TIMER));
    auth = Arrays.copyOfRange(frame.data, 0, 16);
    serialBytes = Arrays.copyOfRange(frame.data, 16, 30);
    telemetry.serialNumber = ascii(serialBytes);
    telemetry.vehicleHasPassword = frame.index != 0;
    crypto.setAuthParam(auth);
    crypto.startSn();
    consecutiveTimeouts = 0;
    effects.add(ProtocolEffect.snapshot(NinebotVehicleProtocolSnapshotMapper.map(
        profile.id,
        generation,
        atNanos,
        telemetry,
        CAPABILITIES,
        "ninebot-identity:" + generation)));
    if (password == null) {
      phase = Phase.WAIT_CREDENTIAL;
      effects.add(ProtocolEffect.requiresCredential(CREDENTIAL_REF));
      return effects;
    }
    effects.addAll(Boolean.TRUE.equals(telemetry.vehicleHasPassword) ? sendAuth(false) : sendSetPassword());
    return effects;
  }

  private List<ProtocolEffect> sendSetPassword() {
    if (password == null || password.length != 16) return Collections.singletonList(ProtocolEffect.requiresCredential(CREDENTIAL_REF));
    phase = Phase.WAIT_SET_PASSWORD;
    saveCredentialOnSuccess = true;
    crypto.setKey(deviceName.getBytes(StandardCharsets.UTF_8), auth);
    return sendEncrypted(
        NinebotEncryption2Codec.buildFrame(profile.setPwdTarget, NinebotEncryption2Codec.CMD_SET_PWD, 0, password),
        "set-password", SET_PASSWORD_TIMEOUT_MS);
  }

  private List<ProtocolEffect> setPasswordFrame(NinebotEncryption2Codec.Frame frame) {
    if (frame.cmd != NinebotEncryption2Codec.CMD_SET_PWD) return terminalFailure("ninebot-set-password-invalid");
    ArrayList<ProtocolEffect> effects = cancelResponseTimer();
    if (frame.index == 0) {
      phase = Phase.WAIT_SET_PASSWORD_CONFIRM;
      effects.add(ProtocolEffect.schedule(RESPONSE_TIMER, SET_PASSWORD_CONFIRM_TIMEOUT_MS));
      return effects;
    }
    if (frame.index != 1) {
      effects.addAll(terminalFailure("ninebot-set-password-rejected"));
      return effects;
    }
    effects.addAll(sendAuth(true));
    return effects;
  }

  private List<ProtocolEffect> sendAuth(boolean saveOnSuccess) {
    if (password == null || password.length != 16 || serialBytes == null) return terminalFailure("ninebot-auth-material-missing");
    phase = Phase.WAIT_AUTH;
    saveCredentialOnSuccess = saveOnSuccess;
    crypto.setKey(password, auth);
    return sendEncrypted(
        NinebotEncryption2Codec.buildFrame(profile.authTarget, NinebotEncryption2Codec.CMD_AUTH, 0, serialBytes),
        "auth", AUTH_TIMEOUT_MS);
  }

  private List<ProtocolEffect> authFrame(NinebotEncryption2Codec.Frame frame, long atNanos) {
    if (frame.cmd != NinebotEncryption2Codec.CMD_AUTH || frame.index != 1) return terminalFailure("ninebot-auth-rejected");
    ArrayList<ProtocolEffect> effects = cancelResponseTimer();
    authenticated = true;
    phase = Phase.IDLE;
    consecutiveTimeouts = 0;
    effects.add(ProtocolEffect.identityConfirmed(profile.id));
    effects.add(ProtocolEffect.ready(saveCredentialOnSuccess ? "credential-created" : "authenticated"));
    resetTelemetrySchedule(atNanos, true);
    nextDiagnosticDueAtNanos = diagnosticsEnabled() ? atNanos : Long.MAX_VALUE;
    effects.addAll(schedulePoll(0L));
    return effects;
  }

  private List<ProtocolEffect> sendScheduledRead(int readIndex, long atNanos) {
    if (readIndex < 0 || readIndex >= reads.length) return Collections.emptyList();
    NinebotTelemetryCodec.ReadSpec read = reads[readIndex];
    telemetryNextDueAtNanos[readIndex] = atNanos + millisToNanos(read.intervalMs(usesFastTelemetryProfile()));
    nextReadIndex = (readIndex + 1) % reads.length;
    captureRequestsSinceTelemetry = 0;
    return sendRead(ReadRequest.telemetry(read.target, read.register, read.readLength), atNanos);
  }

  private List<ProtocolEffect> sendRead(ReadRequest read, long atNanos) {
    if (!authenticated || phase != Phase.IDLE) return Collections.emptyList();
    phase = Phase.WAIT_READ;
    expectedTarget = read.target;
    expectedRegister = read.register;
    expectedDiagnosticKind = read.diagnosticKind;
    expectedCaptureProbe = read.captureProbe;
    expectedReadStartedAtNanos = atNanos;
    expectedReadTimeoutMs = read.responseTimeoutMs;
    if (read.captureProbe != null && diagnosticCapture.isRunning()) {
      diagnosticCapture.currentRegister = read.register;
      diagnosticCapture.currentLength = read.readLength;
      diagnosticCapture.requestCount += 1;
    }
    return sendEncrypted(
        NinebotEncryption2Codec.buildReadFrame(read.target, read.register, read.readLength),
        "read-" + Integer.toHexString(read.register), read.responseTimeoutMs);
  }

  private List<ProtocolEffect> readFrame(NinebotEncryption2Codec.Frame frame, long atNanos) {
    if (!NinebotTelemetryCodec.matchesReadResponse(expectedTarget, expectedRegister, frame.sourceBoard, frame.index)) {
      return Collections.emptyList();
    }
    int completedTarget = expectedTarget;
    int completedRegister = expectedRegister;
    String completedDiagnosticKind = expectedDiagnosticKind;
    ProbeSpec completedCaptureProbe = expectedCaptureProbe;
    long completedStartedAtNanos = expectedReadStartedAtNanos;
    ArrayList<ProtocolEffect> effects = cancelResponseTimer();
    boolean publishSnapshot = completedCaptureProbe == null;
    if (completedCaptureProbe != null) {
      recordCaptureResponse(completedCaptureProbe, frame.sourceBoard, frame.index, frame.data, atNanos, completedStartedAtNanos);
      effects.add(ProtocolEffect.diagnostic(
          "ninebot-diagnostic-capture-sample",
          "phase=" + completedCaptureProbe.phase + ",register=" + Integer.toHexString(completedRegister)
              + ",length=" + frame.data.length));
    } else if (completedDiagnosticKind.isEmpty()) {
      NinebotTelemetryCodec.TelemetryValue value = NinebotTelemetryCodec.decode(frame.index, frame.data);
      if (value != null) apply(value);
    } else {
      applyDiagnostic(completedDiagnosticKind, completedTarget, frame.sourceBoard, frame.data);
      diagnosticResults.put(completedDiagnosticKind, DiagnosticResult.response(
          completedTarget, frame.sourceBoard, frame.index, frame.data, atNanos));
      effects.add(ProtocolEffect.diagnostic(
          "ninebot-diagnostic-" + completedDiagnosticKind,
          "target=" + Integer.toHexString(completedTarget) + ",register=" + Integer.toHexString(completedRegister)
              + ",length=" + frame.data.length));
    }
    clearReadWait();
    consecutiveTimeouts = 0;
    if (publishSnapshot) {
      effects.add(ProtocolEffect.snapshot(NinebotVehicleProtocolSnapshotMapper.map(
          profile.id, generation, atNanos, telemetry, CAPABILITIES,
          "ninebot:" + generation + ":" + (++snapshotSequence))));
    }
    effects.addAll(schedulePoll(nextWorkDelayMs(atNanos, MIN_POLL_GAP_MS)));
    return effects;
  }

  private void applyDiagnostic(String kind, int target, int sourceBoard, byte[] data) {
    boolean knownMmax90Bms = target == 0x07 && sourceBoard == 0x07;
    if ("voltage".equals(kind) && data != null && data.length >= 2) {
      int raw = u16le(data, 0);
      telemetry.diagnosticBatteryVoltageV = knownMmax90Bms && NinebotTelemetryCodec.isPlausibleBmsVoltageRaw(raw)
          ? raw / 100d : null;
    } else if ("soc".equals(kind) && data != null && data.length >= 2) {
      int raw = u16le(data, 0);
      telemetry.diagnosticBatterySocPct = knownMmax90Bms && raw >= 0 && raw <= 100 ? raw : null;
    } else if ("temperature".equals(kind)) {
      telemetry.diagnosticBatteryTemperaturesC = knownMmax90Bms
          ? NinebotTelemetryCodec.decodeMmax90TemperatureValues(data) : null;
    }
  }

  private void apply(NinebotTelemetryCodec.TelemetryValue value) {
    switch (value.field) {
      case SPEED_KMH: telemetry.speedKmh = value.decimalValue; break;
      case POWER_W: telemetry.powerW = value.decimalValue; break;
      case NOS_RAW: telemetry.nosRaw = value.integerValue; break;
      case BATTERY_RAW: telemetry.batteryRaw = value.integerValue; break;
      case RANGE_LEFT_KM: telemetry.rangeLeftKm = value.decimalValue; break;
      case MILEAGE_KM: telemetry.mileageKm = value.decimalValue; break;
      case TRIP_MILEAGE_KM: telemetry.tripMileageKm = value.decimalValue; break;
      case RUNNING_TIME_SEC: telemetry.runningTimeSec = value.integerValue; break;
      default: break;
    }
  }

  private List<ProtocolEffect> timer(ProtocolRuntimeInput input) {
    if (CHUNK_TIMER.equals(input.key())) return sendNextChunk();
    if (CAPTURE_END_TIMER.equals(input.key()) && diagnosticCapture.isRunning()) {
      ArrayList<ProtocolEffect> effects = new ArrayList<>();
      if (phase == Phase.WAIT_READ && expectedCaptureProbe != null) {
        effects.addAll(cancelResponseTimer());
        clearReadWait();
      }
      effects.addAll(finishDiagnosticCapture("completed", "duration_elapsed", input.monotonicNanos()));
      if (authenticated && phase == Phase.IDLE) {
        effects.addAll(schedulePoll(nextWorkDelayMs(input.monotonicNanos(), MIN_POLL_GAP_MS)));
      }
      return effects;
    }
    if (PRECOMM_TIMER.equals(input.key()) && phase == Phase.WAIT_PRECOMM) return sendPrecomm();
    if (POLL_TIMER.equals(input.key()) && authenticated && phase == Phase.IDLE) return poll(input.monotonicNanos());
    if (RESPONSE_TIMER.equals(input.key()) && phase != Phase.IDLE) {
      if (phase == Phase.WAIT_READ) return readTimeout(input.monotonicNanos());
      if (phase == Phase.WAIT_SET_PASSWORD) return terminalFailure("ninebot-set-password-timeout");
      if (phase == Phase.WAIT_SET_PASSWORD_CONFIRM) return terminalFailure("ninebot-set-password-confirm-timeout");
      if (phase == Phase.WAIT_AUTH) return terminalFailure("ninebot-auth-timeout");
    }
    return Collections.emptyList();
  }

  private List<ProtocolEffect> poll(long atNanos) {
    ArrayList<ProtocolEffect> effects = new ArrayList<>();
    int readIndex = nextDueTelemetryReadIndex(atNanos);
    if (diagnosticCapture.isRunning() && atNanos >= diagnosticCapture.endsAtNanos) {
      effects.addAll(finishDiagnosticCapture("completed", "duration_elapsed", atNanos));
    }
    if (diagnosticCapture.isRunning()) {
      if (captureRequestsSinceTelemetry >= DIAGNOSTIC_CAPTURE_BURST_LIMIT && readIndex >= 0) {
        effects.addAll(sendScheduledRead(readIndex, atNanos));
        return effects;
      }
      ProbeSpec probe = diagnosticCapture.queue.next();
      captureRequestsSinceTelemetry += 1;
      effects.addAll(sendRead(ReadRequest.capture(diagnosticCapture.target, probe), atNanos));
      return effects;
    }
    if (!manualReads.isEmpty()) {
      effects.addAll(sendRead(manualReads.removeFirst().asRequest(), atNanos));
      return effects;
    }
    ReadRequest diagnostic = nextDiagnosticRead(atNanos);
    if (diagnostic != null) {
      effects.addAll(sendRead(diagnostic, atNanos));
      return effects;
    }
    if (readIndex >= 0) {
      effects.addAll(sendScheduledRead(readIndex, atNanos));
      return effects;
    }
    effects.addAll(schedulePoll(nextWorkDelayMs(atNanos, MIN_POLL_GAP_MS)));
    return effects;
  }

  private List<ProtocolEffect> readTimeout(long atNanos) {
    String diagnosticKind = expectedDiagnosticKind;
    ProbeSpec captureProbe = expectedCaptureProbe;
    int target = expectedTarget;
    int register = expectedRegister;
    long timeoutMs = expectedReadTimeoutMs;
    ArrayList<ProtocolEffect> effects = cancelResponseTimer();
    clearReadWait();
    if (captureProbe != null) {
      recordCaptureTimeout(captureProbe, timeoutMs, atNanos);
    } else if (!diagnosticKind.isEmpty()) {
      diagnosticResults.put(diagnosticKind, DiagnosticResult.timeout(target, register, atNanos));
    }
    effects.add(ProtocolEffect.diagnostic(
        captureProbe != null ? "ninebot-diagnostic-capture-timeout"
            : diagnosticKind.isEmpty() ? "ninebot-telemetry-timeout" : "ninebot-diagnostic-timeout",
        "register=" + Integer.toHexString(register)));
    effects.addAll(schedulePoll(nextWorkDelayMs(atNanos, MIN_POLL_GAP_MS)));
    return effects;
  }

  private List<ProtocolEffect> manualRead(ProtocolRuntimeInput input) {
    DiagnosticCommand command = DiagnosticCommand.parse(input.operationId());
    if (command != null) return applyDiagnosticCommand(command, input.monotonicNanos());
    if (!authenticated) return Collections.emptyList();
    ManualRead read = ManualRead.parse(input.operationId());
    if (read == null) return Collections.singletonList(ProtocolEffect.error("ninebot-manual-read-unsupported"));
    manualReads.addLast(read);
    return phase == Phase.IDLE
        ? poll(input.monotonicNanos())
        : Collections.singletonList(ProtocolEffect.diagnostic(
            "ninebot-manual-read-queued", "kind=" + read.kind + ",target=" + hexByte(read.target)));
  }

  private List<ProtocolEffect> applyDiagnosticCommand(DiagnosticCommand command, long atNanos) {
    if (command.kind == DiagnosticCommand.Kind.CONFIGURE) {
      boolean targetChanged = diagnosticTarget != command.target;
      diagnosticTarget = command.target;
      diagnosticVoltageEnabled = (command.flags & 1) != 0;
      diagnosticSocEnabled = (command.flags & 2) != 0;
      diagnosticTemperatureEnabled = (command.flags & 4) != 0;
      if (targetChanged || diagnosticTarget != 0x07) {
        diagnosticResults.clear();
        telemetry.diagnosticBatteryVoltageV = null;
        telemetry.diagnosticBatterySocPct = null;
        telemetry.diagnosticBatteryTemperaturesC = null;
      }
      if (!diagnosticVoltageEnabled) {
        diagnosticResults.remove("voltage");
        telemetry.diagnosticBatteryVoltageV = null;
      }
      if (!diagnosticSocEnabled) {
        diagnosticResults.remove("soc");
        telemetry.diagnosticBatterySocPct = null;
      }
      if (!diagnosticTemperatureEnabled) {
        diagnosticResults.remove("temperature");
        telemetry.diagnosticBatteryTemperaturesC = null;
      }
      nextDiagnosticKindIndex = 0;
      nextDiagnosticDueAtNanos = diagnosticsEnabled() ? atNanos : Long.MAX_VALUE;
      return authenticated && phase == Phase.IDLE
          ? schedulePoll(0L)
          : Collections.<ProtocolEffect>emptyList();
    }
    if (command.kind == DiagnosticCommand.Kind.START_CAPTURE) {
      if (!authenticated) {
        diagnosticCapture = DiagnosticCapture.failed(command.target, "not_authenticated", atNanos);
        return Collections.singletonList(ProtocolEffect.diagnostic(
            "ninebot-diagnostic-capture-failed", "reason=not_authenticated"));
      }
      if (diagnosticCapture.isRunning()) return Collections.emptyList();
      diagnosticCapture = DiagnosticCapture.start(
          command.target,
          command.durationMs,
          atNanos,
          "ninebot-capture-" + generation + "-" + (++captureSequence));
      captureRequestsSinceTelemetry = 0;
      ArrayList<ProtocolEffect> effects = new ArrayList<>();
      effects.add(ProtocolEffect.diagnostic(
          "ninebot-diagnostic-capture-started",
          "target=" + hexByte(command.target) + ",durationMs=" + command.durationMs));
      effects.add(ProtocolEffect.cancel(CAPTURE_END_TIMER));
      effects.add(ProtocolEffect.schedule(CAPTURE_END_TIMER, command.durationMs));
      effects.addAll(phase == Phase.IDLE ? schedulePoll(0L) : Collections.<ProtocolEffect>emptyList());
      return effects;
    }
    ArrayList<ProtocolEffect> effects = new ArrayList<>();
    if (diagnosticCapture.isRunning() && phase == Phase.WAIT_READ && expectedCaptureProbe != null) {
      effects.addAll(cancelResponseTimer());
      clearReadWait();
    }
    effects.addAll(finishDiagnosticCapture("cancelled", "user_requested", atNanos));
    if (authenticated && phase == Phase.IDLE) effects.addAll(schedulePoll(nextWorkDelayMs(atNanos, MIN_POLL_GAP_MS)));
    return effects;
  }

  private List<ProtocolEffect> sendEncrypted(byte[] plaintext, String operation, long responseTimeoutMs) {
    if (!linkUsable() || plaintext == null || plaintext.length == 0) return Collections.emptyList();
    byte[] encrypted = crypto.encrypt(plaintext);
    int writeMtu = NinebotEncryption2Codec.effectiveWriteMtu(authenticated, mtu);
    for (byte[] chunk : NinebotEncryption2Codec.fragmentForAtt(encrypted, writeMtu)) writeChunks.addLast(chunk);
    activeWriteRequestId = "ninebot:" + generation + ":" + (++requestSequence) + ":" + operation;
    pendingResponseTimeoutMs = responseTimeoutMs;
    return sendNextChunk();
  }

  private long pendingResponseTimeoutMs;

  private List<ProtocolEffect> sendNextChunk() {
    if (writeChunks.isEmpty()) {
      if (pendingResponseTimeoutMs <= 0L) return Collections.emptyList();
      long delay = pendingResponseTimeoutMs;
      pendingResponseTimeoutMs = 0L;
      return Arrays.asList(ProtocolEffect.cancel(RESPONSE_TIMER), ProtocolEffect.schedule(RESPONSE_TIMER, delay));
    }
    byte[] chunk = writeChunks.removeFirst();
    ArrayList<ProtocolEffect> effects = new ArrayList<>();
    effects.add(ProtocolEffect.send(activeWriteRequestId, channelId, "default", chunk, ProtocolEffect.WriteMode.WITHOUT_RESPONSE));
    effects.add(ProtocolEffect.cancel(CHUNK_TIMER));
    effects.add(ProtocolEffect.schedule(CHUNK_TIMER, WRITE_CHUNK_GAP_MS));
    return effects;
  }

  private ArrayList<ProtocolEffect> cancelResponseTimer() {
    ArrayList<ProtocolEffect> effects = new ArrayList<>();
    effects.add(ProtocolEffect.cancel(RESPONSE_TIMER));
    effects.add(ProtocolEffect.cancel(CHUNK_TIMER));
    writeChunks.clear();
    pendingResponseTimeoutMs = 0L;
    activeWriteRequestId = "";
    return effects;
  }

  private List<ProtocolEffect> schedulePoll(long delayMs) {
    return Arrays.asList(ProtocolEffect.cancel(POLL_TIMER), ProtocolEffect.schedule(POLL_TIMER, Math.max(0L, delayMs)));
  }

  private List<ProtocolEffect> terminalFailure(String code) {
    ArrayList<ProtocolEffect> effects = cancelResponseTimer();
    effects.add(ProtocolEffect.cancel(PRECOMM_TIMER));
    effects.add(ProtocolEffect.cancel(POLL_TIMER));
    effects.add(ProtocolEffect.cancel(CAPTURE_END_TIMER));
    effects.add(ProtocolEffect.error(code));
    phase = Phase.IDLE;
    authenticated = false;
    expectedTarget = -1;
    expectedRegister = -1;
    expectedDiagnosticKind = "";
    expectedCaptureProbe = null;
    manualReads.clear();
    if (diagnosticCapture.isRunning()) diagnosticCapture.finish("failed", code, 0L);
    return effects;
  }

  private List<ProtocolEffect> cancelAll() {
    ArrayList<ProtocolEffect> effects = cancelResponseTimer();
    effects.add(ProtocolEffect.cancel(PRECOMM_TIMER));
    effects.add(ProtocolEffect.cancel(POLL_TIMER));
    effects.add(ProtocolEffect.cancel(CAPTURE_END_TIMER));
    phase = Phase.IDLE;
    authenticated = false;
    expectedTarget = -1;
    expectedRegister = -1;
    expectedDiagnosticKind = "";
    expectedCaptureProbe = null;
    manualReads.clear();
    if (diagnosticCapture.isRunning()) diagnosticCapture.finish("cancelled", "link_suspended", 0L);
    return effects;
  }

  private List<ProtocolEffect> closeEffects() {
    if (closed) return Collections.emptyList();
    List<ProtocolEffect> effects = cancelAll();
    closed = true;
    clear(password);
    clear(auth);
    clear(serialBytes);
    password = null;
    auth = null;
    serialBytes = null;
    return effects;
  }

  private void reset(long nextGeneration) {
    generation = nextGeneration;
    requestSequence = 0L;
    snapshotSequence = 0L;
    precommAttempts = 0;
    nextReadIndex = 0;
    nextDiagnosticKindIndex = 0;
    nextDiagnosticDueAtNanos = Long.MAX_VALUE;
    expectedReadStartedAtNanos = 0L;
    expectedReadTimeoutMs = READ_TIMEOUT_MS;
    captureRequestsSinceTelemetry = 0;
    consecutiveTimeouts = 0;
    subscribed = false;
    suspended = false;
    authenticated = false;
    saveCredentialOnSuccess = false;
    phase = Phase.IDLE;
    accumulator.reset();
    writeChunks.clear();
    activeWriteRequestId = "";
    pendingResponseTimeoutMs = 0L;
    expectedTarget = -1;
    expectedRegister = -1;
    expectedDiagnosticKind = "";
    expectedCaptureProbe = null;
    manualReads.clear();
    diagnosticCapture = DiagnosticCapture.idle();
    clear(auth);
    clear(serialBytes);
    auth = null;
    serialBytes = null;
    telemetry.serialNumber = null;
    telemetry.vehicleHasPassword = null;
    telemetry.diagnosticBatteryVoltageV = null;
    telemetry.diagnosticBatterySocPct = null;
    telemetry.diagnosticBatteryTemperaturesC = null;
    resetTelemetrySchedule(0L, true);
  }

  private boolean usesFastTelemetryProfile() { return pollIntervalMs <= 500L; }

  private void resetTelemetrySchedule(long atNanos, boolean immediate) {
    for (int index = 0; index < telemetryNextDueAtNanos.length; index += 1) {
      telemetryNextDueAtNanos[index] = immediate
          ? atNanos
          : atNanos + millisToNanos(reads[index].intervalMs(usesFastTelemetryProfile()));
    }
    nextReadIndex = 0;
  }

  private int nextDueTelemetryReadIndex(long atNanos) {
    for (int offset = 0; offset < reads.length; offset += 1) {
      int index = (nextReadIndex + offset) % reads.length;
      if (telemetryNextDueAtNanos[index] <= atNanos) return index;
    }
    return -1;
  }

  private long nextTelemetryDelayMs(long atNanos, long minimumDelayMs) {
    long nextDue = Long.MAX_VALUE;
    for (long due : telemetryNextDueAtNanos) nextDue = Math.min(nextDue, due);
    return delayUntil(atNanos, nextDue, minimumDelayMs);
  }

  private long nextWorkDelayMs(long atNanos, long minimumDelayMs) {
    if (!manualReads.isEmpty() || diagnosticCapture.isRunning()) return minimumDelayMs;
    long nextDue = Long.MAX_VALUE;
    for (long due : telemetryNextDueAtNanos) nextDue = Math.min(nextDue, due);
    if (diagnosticsEnabled()) nextDue = Math.min(nextDue, nextDiagnosticDueAtNanos);
    return delayUntil(atNanos, nextDue, minimumDelayMs);
  }

  private static long delayUntil(long atNanos, long nextDueAtNanos, long minimumDelayMs) {
    if (nextDueAtNanos == Long.MAX_VALUE) return minimumDelayMs;
    long remaining = Math.max(0L, nextDueAtNanos - atNanos);
    long delayMs = remaining == 0L ? 0L : (remaining + 999_999L) / 1_000_000L;
    return Math.max(minimumDelayMs, delayMs);
  }

  private ReadRequest nextDiagnosticRead(long atNanos) {
    if (!diagnosticsEnabled() || atNanos < nextDiagnosticDueAtNanos) return null;
    for (int offset = 0; offset < DIAGNOSTIC_KINDS.length; offset += 1) {
      int index = (nextDiagnosticKindIndex + offset) % DIAGNOSTIC_KINDS.length;
      String kind = DIAGNOSTIC_KINDS[index];
      if (!diagnosticEnabled(kind)) continue;
      nextDiagnosticKindIndex = (index + 1) % DIAGNOSTIC_KINDS.length;
      nextDiagnosticDueAtNanos = addNanos(atNanos, millisToNanos(DIAGNOSTIC_INTERVAL_MS));
      return ReadRequest.diagnostic(
          diagnosticTarget,
          "voltage".equals(kind) ? NinebotTelemetryCodec.REG_BMS_VOLTAGE
              : "soc".equals(kind) ? NinebotTelemetryCodec.REG_BMS_SOC
              : NinebotTelemetryCodec.REG_BMS_TEMPERATURES,
          "temperature".equals(kind) ? 20 : 2,
          kind);
    }
    nextDiagnosticDueAtNanos = Long.MAX_VALUE;
    return null;
  }

  private boolean diagnosticsEnabled() {
    return diagnosticVoltageEnabled || diagnosticSocEnabled || diagnosticTemperatureEnabled;
  }

  private boolean diagnosticEnabled(String kind) {
    return "voltage".equals(kind) ? diagnosticVoltageEnabled
        : "soc".equals(kind) ? diagnosticSocEnabled
        : "temperature".equals(kind) && diagnosticTemperatureEnabled;
  }

  private void clearReadWait() {
    phase = Phase.IDLE;
    expectedTarget = -1;
    expectedRegister = -1;
    expectedDiagnosticKind = "";
    expectedCaptureProbe = null;
    expectedReadStartedAtNanos = 0L;
    expectedReadTimeoutMs = READ_TIMEOUT_MS;
  }

  private void recordCaptureResponse(
      ProbeSpec probe,
      int sourceBoard,
      int register,
      byte[] data,
      long atNanos,
      long startedAtNanos) {
    if (!diagnosticCapture.isRunning()) return;
    diagnosticCapture.responseCount += 1;
    diagnosticCapture.lastSample = CaptureSample.response(
        diagnosticCapture.sessionId,
        probe,
        diagnosticCapture.target,
        sourceBoard,
        register,
        data,
        nanosToMillis(Math.max(0L, atNanos - startedAtNanos)),
        atNanos,
        diagnosticCapture.requestCount);
  }

  private void recordCaptureTimeout(ProbeSpec probe, long timeoutMs, long atNanos) {
    if (!diagnosticCapture.isRunning()) return;
    diagnosticCapture.timeoutCount += 1;
    diagnosticCapture.lastSample = CaptureSample.timeout(
        diagnosticCapture.sessionId,
        probe,
        diagnosticCapture.target,
        timeoutMs,
        atNanos,
        diagnosticCapture.requestCount);
  }

  private List<ProtocolEffect> finishDiagnosticCapture(String state, String reason, long atNanos) {
    if (!diagnosticCapture.isRunning()) return Collections.emptyList();
    diagnosticCapture.finish(state, reason, atNanos);
    captureRequestsSinceTelemetry = 0;
    if (diagnosticsEnabled()) {
      nextDiagnosticDueAtNanos = Math.max(
          nextDiagnosticDueAtNanos,
          addNanos(atNanos, millisToNanos(DIAGNOSTIC_CAPTURE_TIMEOUT_MS)));
    }
    return Arrays.asList(
        ProtocolEffect.cancel(CAPTURE_END_TIMER),
        ProtocolEffect.diagnostic(
            "ninebot-diagnostic-capture-" + state,
            "reason=" + reason + ",requests=" + diagnosticCapture.requestCount
                + ",responses=" + diagnosticCapture.responseCount
                + ",timeouts=" + diagnosticCapture.timeoutCount));
  }

  private static long millisToNanos(long millis) {
    return millis >= Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : millis * 1_000_000L;
  }

  private static long nanosToMillis(long nanos) { return nanos / 1_000_000L; }

  private static long addNanos(long left, long right) {
    return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private static int u16le(byte[] value, int offset) {
    return (value[offset] & 0xff) | ((value[offset + 1] & 0xff) << 8);
  }

  private static int i16le(byte[] value, int offset) { return (short) u16le(value, offset); }

  private static long u32le(byte[] value, int offset) {
    return ((long) value[offset] & 0xffL)
        | (((long) value[offset + 1] & 0xffL) << 8)
        | (((long) value[offset + 2] & 0xffL) << 16)
        | (((long) value[offset + 3] & 0xffL) << 24);
  }

  private static String hexByte(int value) {
    String hex = Integer.toHexString(value & 0xff);
    return hex.length() < 2 ? "0" + hex : hex;
  }

  private static String bytesToHex(byte[] value) {
    if (value == null || value.length == 0) return "";
    StringBuilder result = new StringBuilder(value.length * 2);
    for (byte item : value) {
      int unsigned = item & 0xff;
      if (unsigned < 0x10) result.append('0');
      result.append(Integer.toHexString(unsigned));
    }
    return result.toString();
  }

  private boolean linkUsable() { return subscribed && !suspended && !closed; }
  private static String ascii(byte[] value) { return new String(value, StandardCharsets.UTF_8).replace("\u0000", "").trim(); }
  private static void clear(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }

  public static ProbeSpec[] knownBmsProbesForTest() {
    return Arrays.copyOf(KNOWN_BMS_PROBES, KNOWN_BMS_PROBES.length);
  }

  public static final class DiagnosticStatus {
    private final int target;
    private final boolean voltageEnabled;
    private final boolean socEnabled;
    private final boolean temperatureEnabled;
    private final Map<String, DiagnosticResult> results;
    private final CaptureStatus capture;

    private DiagnosticStatus(
        int target,
        boolean voltageEnabled,
        boolean socEnabled,
        boolean temperatureEnabled,
        Map<String, DiagnosticResult> results,
        CaptureStatus capture) {
      this.target = target;
      this.voltageEnabled = voltageEnabled;
      this.socEnabled = socEnabled;
      this.temperatureEnabled = temperatureEnabled;
      this.results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
      this.capture = capture;
    }

    public int target() { return target; }
    public boolean voltageEnabled() { return voltageEnabled; }
    public boolean socEnabled() { return socEnabled; }
    public boolean temperatureEnabled() { return temperatureEnabled; }
    public Map<String, DiagnosticResult> results() { return results; }
    public CaptureStatus capture() { return capture; }
  }

  public static final class DiagnosticResult {
    private final int target;
    private final Integer sourceBoard;
    private final int register;
    private final int dataLength;
    private final String rawHex;
    private final Integer rawU16;
    private final long atNanos;
    private final String error;

    private DiagnosticResult(
        int target,
        Integer sourceBoard,
        int register,
        int dataLength,
        String rawHex,
        Integer rawU16,
        long atNanos,
        String error) {
      this.target = target;
      this.sourceBoard = sourceBoard;
      this.register = register;
      this.dataLength = dataLength;
      this.rawHex = rawHex;
      this.rawU16 = rawU16;
      this.atNanos = atNanos;
      this.error = error;
    }

    static DiagnosticResult response(int target, int sourceBoard, int register, byte[] data, long atNanos) {
      return new DiagnosticResult(
          target,
          sourceBoard,
          register,
          data == null ? 0 : data.length,
          bytesToHex(data),
          data != null && data.length >= 2 ? u16le(data, 0) : null,
          atNanos,
          "");
    }

    static DiagnosticResult timeout(int target, int register, long atNanos) {
      return new DiagnosticResult(target, null, register, 0, "", null, atNanos, "timeout");
    }

    public int target() { return target; }
    public Integer sourceBoard() { return sourceBoard; }
    public int register() { return register; }
    public int dataLength() { return dataLength; }
    public String rawHex() { return rawHex; }
    public Integer rawU16() { return rawU16; }
    public long atNanos() { return atNanos; }
    public String error() { return error; }
  }

  public static final class CaptureStatus {
    private final String state;
    private final String sessionId;
    private final int target;
    private final long startedAtNanos;
    private final long endsAtNanos;
    private final long durationMs;
    private final long finishedAtNanos;
    private final String phase;
    private final int currentRegister;
    private final int currentLength;
    private final int requestCount;
    private final int responseCount;
    private final int timeoutCount;
    private final int knownCompleted;
    private final int sweepCompleted;
    private final int completedCycles;
    private final String lastError;
    private final CaptureSample lastSample;

    private CaptureStatus(DiagnosticCapture capture) {
      state = capture.state;
      sessionId = capture.sessionId;
      target = capture.target;
      startedAtNanos = capture.startedAtNanos;
      endsAtNanos = capture.endsAtNanos;
      durationMs = capture.durationMs;
      finishedAtNanos = capture.finishedAtNanos;
      phase = capture.queue == null ? "" : capture.queue.phase();
      currentRegister = capture.currentRegister;
      currentLength = capture.currentLength;
      requestCount = capture.requestCount;
      responseCount = capture.responseCount;
      timeoutCount = capture.timeoutCount;
      knownCompleted = capture.queue == null ? 0 : capture.queue.knownCompleted();
      sweepCompleted = capture.queue == null ? 0 : capture.queue.sweepCompleted();
      completedCycles = capture.queue == null ? 0 : capture.queue.completedCycles();
      lastError = capture.lastError;
      lastSample = capture.lastSample;
    }

    public String state() { return state; }
    public String sessionId() { return sessionId; }
    public int target() { return target; }
    public long startedAtNanos() { return startedAtNanos; }
    public long endsAtNanos() { return endsAtNanos; }
    public long durationMs() { return durationMs; }
    public long finishedAtNanos() { return finishedAtNanos; }
    public String phase() { return phase; }
    public int currentRegister() { return currentRegister; }
    public int currentLength() { return currentLength; }
    public int requestCount() { return requestCount; }
    public int responseCount() { return responseCount; }
    public int timeoutCount() { return timeoutCount; }
    public int knownCompleted() { return knownCompleted; }
    public int knownTotal() { return KNOWN_BMS_PROBES.length; }
    public int sweepCompleted() { return sweepCompleted; }
    public int sweepTotal() { return DiagnosticCaptureQueue.SWEEP_BLOCK_COUNT; }
    public int completedCycles() { return completedCycles; }
    public String lastError() { return lastError; }
    public CaptureSample lastSample() { return lastSample; }
  }

  public static final class CaptureSample {
    private final String sessionId;
    private final String phase;
    private final int target;
    private final Integer sourceBoard;
    private final int register;
    private final int requestedLength;
    private final int dataLength;
    private final String rawHex;
    private final long latencyMs;
    private final long atNanos;
    private final int requestCount;
    private final String error;
    private final Integer u16Le;
    private final Integer i16Le;
    private final Integer u16Be;
    private final Long u32Le;

    private CaptureSample(
        String sessionId,
        ProbeSpec probe,
        int target,
        Integer sourceBoard,
        int register,
        byte[] data,
        long latencyMs,
        long atNanos,
        int requestCount,
        String error) {
      this.sessionId = sessionId;
      phase = probe.phase;
      this.target = target;
      this.sourceBoard = sourceBoard;
      this.register = register;
      requestedLength = probe.readLength;
      dataLength = data == null ? 0 : data.length;
      rawHex = bytesToHex(data);
      this.latencyMs = latencyMs;
      this.atNanos = atNanos;
      this.requestCount = requestCount;
      this.error = error;
      u16Le = data != null && data.length >= 2 ? u16le(data, 0) : null;
      i16Le = data != null && data.length >= 2 ? i16le(data, 0) : null;
      u16Be = data != null && data.length >= 2 ? ((data[0] & 0xff) << 8) | (data[1] & 0xff) : null;
      u32Le = data != null && data.length >= 4 ? u32le(data, 0) : null;
    }

    static CaptureSample response(
        String sessionId,
        ProbeSpec probe,
        int target,
        int sourceBoard,
        int register,
        byte[] data,
        long latencyMs,
        long atNanos,
        int requestCount) {
      return new CaptureSample(
          sessionId, probe, target, sourceBoard, register, data, latencyMs, atNanos, requestCount, "");
    }

    static CaptureSample timeout(
        String sessionId,
        ProbeSpec probe,
        int target,
        long timeoutMs,
        long atNanos,
        int requestCount) {
      return new CaptureSample(
          sessionId, probe, target, null, probe.register, null, timeoutMs, atNanos, requestCount, "timeout");
    }

    public String sessionId() { return sessionId; }
    public String phase() { return phase; }
    public int target() { return target; }
    public Integer sourceBoard() { return sourceBoard; }
    public int register() { return register; }
    public int requestedLength() { return requestedLength; }
    public int dataLength() { return dataLength; }
    public String rawHex() { return rawHex; }
    public long latencyMs() { return latencyMs; }
    public long atNanos() { return atNanos; }
    public int requestCount() { return requestCount; }
    public String error() { return error; }
    public Integer u16Le() { return u16Le; }
    public Integer i16Le() { return i16Le; }
    public Integer u16Be() { return u16Be; }
    public Long u32Le() { return u32Le; }
  }

  public static final class ProbeSpec {
    private final int register;
    private final int readLength;
    private final String phase;

    ProbeSpec(int register, int readLength, String phase) {
      this.register = register;
      this.readLength = readLength;
      this.phase = phase;
    }

    ProbeSpec withPhase(String value) { return new ProbeSpec(register, readLength, value); }
    public int register() { return register; }
    public int readLength() { return readLength; }
    public String phase() { return phase; }
  }

  public static final class DiagnosticCaptureQueue {
    public static final int SWEEP_BLOCK_COUNT = 16;
    private int stage;
    private int knownIndex;
    private int sweepIndex;
    private int repeatIndex;
    private int completedCycles;

    public ProbeSpec next() {
      if (stage == 0) {
        if (knownIndex < KNOWN_BMS_PROBES.length) return KNOWN_BMS_PROBES[knownIndex++].withPhase("known");
        completedCycles = 1;
        stage = 1;
      }
      if (stage == 1) {
        if (sweepIndex < SWEEP_BLOCK_COUNT) return new ProbeSpec(sweepIndex++ * 0x10, 16, "sweep");
        stage = 2;
      }
      ProbeSpec result = KNOWN_BMS_PROBES[repeatIndex++].withPhase("repeat_known");
      if (repeatIndex >= KNOWN_BMS_PROBES.length) {
        repeatIndex = 0;
        completedCycles += 1;
      }
      return result;
    }

    public String phase() { return stage == 0 ? "known" : stage == 1 ? "sweep" : "repeat_known"; }
    public int knownCompleted() { return Math.min(knownIndex, KNOWN_BMS_PROBES.length); }
    public int sweepCompleted() { return Math.min(sweepIndex, SWEEP_BLOCK_COUNT); }
    public int completedCycles() { return completedCycles; }
  }

  private static final class DiagnosticCapture {
    String state = "idle";
    String sessionId = "";
    int target = -1;
    long startedAtNanos;
    long endsAtNanos;
    long durationMs;
    long finishedAtNanos;
    int currentRegister = -1;
    int currentLength;
    int requestCount;
    int responseCount;
    int timeoutCount;
    String lastError = "";
    CaptureSample lastSample;
    DiagnosticCaptureQueue queue;

    static DiagnosticCapture idle() { return new DiagnosticCapture(); }

    static DiagnosticCapture start(int target, long durationMs, long atNanos, String sessionId) {
      DiagnosticCapture result = new DiagnosticCapture();
      result.state = "running";
      result.sessionId = sessionId;
      result.target = target;
      result.startedAtNanos = atNanos;
      result.durationMs = durationMs;
      result.endsAtNanos = addNanos(atNanos, millisToNanos(durationMs));
      result.queue = new DiagnosticCaptureQueue();
      return result;
    }

    static DiagnosticCapture failed(int target, String reason, long atNanos) {
      DiagnosticCapture result = new DiagnosticCapture();
      result.state = "failed";
      result.target = target;
      result.finishedAtNanos = atNanos;
      result.lastError = reason;
      return result;
    }

    boolean isRunning() { return "running".equals(state); }

    void finish(String nextState, String reason, long atNanos) {
      state = nextState;
      finishedAtNanos = atNanos;
      currentRegister = -1;
      currentLength = 0;
      lastError = "failed".equals(nextState) ? reason : "";
    }

    CaptureStatus snapshot() { return new CaptureStatus(this); }
  }

  private static final class ReadRequest {
    final int target;
    final int register;
    final int readLength;
    final String diagnosticKind;
    final long responseTimeoutMs;
    final ProbeSpec captureProbe;

    private ReadRequest(
        int target,
        int register,
        int readLength,
        String diagnosticKind,
        long responseTimeoutMs,
        ProbeSpec captureProbe) {
      this.target = target;
      this.register = register;
      this.readLength = readLength;
      this.diagnosticKind = diagnosticKind == null ? "" : diagnosticKind;
      this.responseTimeoutMs = responseTimeoutMs;
      this.captureProbe = captureProbe;
    }

    static ReadRequest telemetry(int target, int register, int readLength) {
      return new ReadRequest(target, register, readLength, "", READ_TIMEOUT_MS, null);
    }

    static ReadRequest diagnostic(int target, int register, int readLength, String kind) {
      return new ReadRequest(target, register, readLength, kind, READ_TIMEOUT_MS, null);
    }

    static ReadRequest capture(int target, ProbeSpec probe) {
      return new ReadRequest(
          target, probe.register, probe.readLength, "", DIAGNOSTIC_CAPTURE_TIMEOUT_MS, probe);
    }
  }

  private static final class DiagnosticCommand {
    enum Kind { CONFIGURE, START_CAPTURE, STOP_CAPTURE }
    final Kind kind;
    final int target;
    final int flags;
    final long durationMs;

    private DiagnosticCommand(Kind kind, int target, int flags, long durationMs) {
      this.kind = kind;
      this.target = target;
      this.flags = flags;
      this.durationMs = durationMs;
    }

    static DiagnosticCommand parse(String operationId) {
      if (CAPTURE_STOP_OPERATION.equals(operationId)) {
        return new DiagnosticCommand(Kind.STOP_CAPTURE, -1, 0, 0L);
      }
      if (operationId != null && operationId.startsWith(DIAGNOSTIC_CONFIG_PREFIX)) {
        String[] parts = operationId.substring(DIAGNOSTIC_CONFIG_PREFIX.length()).split("-");
        if (parts.length != 2) return null;
        try {
          int target = Integer.parseInt(parts[0], 16);
          int flags = Integer.parseInt(parts[1]);
          if (target < 0 || target > 0xff || flags < 0 || flags > 7) return null;
          return new DiagnosticCommand(Kind.CONFIGURE, normalizeDiagnosticTarget(target), flags, 0L);
        } catch (NumberFormatException ignored) {
          return null;
        }
      }
      if (operationId != null && operationId.startsWith(CAPTURE_START_PREFIX)) {
        String[] parts = operationId.substring(CAPTURE_START_PREFIX.length()).split("-");
        if (parts.length != 2) return null;
        try {
          int target = Integer.parseInt(parts[0], 16);
          long durationMs = Long.parseLong(parts[1]);
          if (target < 0 || target > 0xff || durationMs < 0L) return null;
          return new DiagnosticCommand(
              Kind.START_CAPTURE,
              normalizeDiagnosticTarget(target),
              0,
              normalizeDiagnosticCaptureDuration(durationMs));
        } catch (NumberFormatException ignored) {
          return null;
        }
      }
      return null;
    }
  }

  private static final class ManualRead {
    final String kind;
    final int target;
    final int register;
    final int readLength;

    ManualRead(String kind, int target, int register, int readLength) {
      this.kind = kind;
      this.target = target;
      this.register = register;
      this.readLength = readLength;
    }

    static ManualRead parse(String operationId) {
      if (operationId == null) return null;
      String prefix = "ninebot-bms-";
      if (!operationId.startsWith(prefix) || operationId.length() < prefix.length() + 4) return null;
      int split = operationId.lastIndexOf('-');
      if (split <= prefix.length() || split + 1 >= operationId.length()) return null;
      String kind = operationId.substring(prefix.length(), split);
      int target;
      try { target = Integer.parseInt(operationId.substring(split + 1), 16); }
      catch (NumberFormatException ignored) { return null; }
      if (target < 0 || target > 0xff) return null;
      if ("voltage".equals(kind)) return new ManualRead(kind, target, NinebotTelemetryCodec.REG_BMS_VOLTAGE, 2);
      if ("soc".equals(kind)) return new ManualRead(kind, target, NinebotTelemetryCodec.REG_BMS_SOC, 2);
      if ("temperature".equals(kind)) return new ManualRead(kind, target, NinebotTelemetryCodec.REG_BMS_TEMPERATURES, 20);
      return null;
    }

    ReadRequest asRequest() { return ReadRequest.diagnostic(target, register, readLength, kind); }
  }

  private static ProbeSpec probe(int register, int readLength) {
    return new ProbeSpec(register, readLength, "known");
  }

  private enum Phase { IDLE, WAIT_PRECOMM, WAIT_CREDENTIAL, WAIT_SET_PASSWORD, WAIT_SET_PASSWORD_CONFIRM, WAIT_AUTH, WAIT_READ }
}
