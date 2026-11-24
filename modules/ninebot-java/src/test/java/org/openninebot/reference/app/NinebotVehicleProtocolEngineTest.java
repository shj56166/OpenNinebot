package org.openninebot.reference.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.openninebot.reference.app.protocolcore.NinebotVehicleGattProfile;
import org.openninebot.reference.vehicle.protocol.ProtocolEffect;
import org.openninebot.reference.vehicle.protocol.ProtocolField;
import org.openninebot.reference.vehicle.protocol.ProtocolRuntimeInput;
import org.openninebot.reference.vehicle.protocol.ProtocolSnapshot;
import org.openninebot.reference.vehicle.protocol.ProtocolValue;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class NinebotVehicleProtocolEngineTest {
  @Test
  public void telemetryTimeoutKeepsAuthenticatedSessionAndContinuesReadPlan() {
    Session session = authenticatedSession(21L);
    List<ProtocolEffect> firstRead = timer(session.engine, session.generation, "ninebot:poll", 1_000_000_000L);
    assertTrue(hasType(firstRead, ProtocolEffect.Type.SEND));
    List<ProtocolEffect> armed = timer(session.engine, session.generation, "ninebot:chunk", 1_001_000_000L);
    ProtocolEffect responseTimer = effectWithKey(armed, ProtocolEffect.Type.SCHEDULE_TIMER, "ninebot:response");
    List<ProtocolEffect> timeout = timer(
        session.engine, session.generation, responseTimer.key(), 2_801_000_000L);
    assertTrue(hasType(timeout, ProtocolEffect.Type.DIAGNOSTIC));
    assertFalse(hasType(timeout, ProtocolEffect.Type.ERROR));
    assertFalse(hasType(timeout, ProtocolEffect.Type.REQUEST_RECONNECT));

    List<ProtocolEffect> nextRead = timer(session.engine, session.generation, "ninebot:poll", 2_846_000_000L);
    assertTrue(hasType(nextRead, ProtocolEffect.Type.SEND));
    assertFalse(hasType(nextRead, ProtocolEffect.Type.REQUIRES_CREDENTIAL));
  }

  @Test
  public void manualVoltageReadPublishesTypedDiagnosticSnapshot() {
    Session session = authenticatedSession(22L);
    List<ProtocolEffect> request = session.engine.onInput(ProtocolRuntimeInput.manualReadRequest(
        session.generation, 2_000_000_000L, "manual-1", "ninebot-bms-voltage-07"));
    assertTrue(hasType(request, ProtocolEffect.Type.SEND));
    timer(session.engine, session.generation, "ninebot:chunk", 2_001_000_000L);

    byte[] response = session.peer.encrypt(responseFrame(
        0x07, NinebotEncryption2Codec.CMD_READ_RESP, NinebotTelemetryCodec.REG_BMS_VOLTAGE,
        new byte[] { 0x7e, 0x13 }));
    List<ProtocolEffect> result = session.engine.onInput(ProtocolRuntimeInput.bytes(
        session.generation, 2_010_000_000L, NinebotVehicleGattProfile.CHANNEL_NUS, response));
    assertTrue(hasType(result, ProtocolEffect.Type.DIAGNOSTIC));
    assertEquals(49.90d,
        effect(result, ProtocolEffect.Type.SNAPSHOT).snapshot().fields()
            .get("diagnostic.battery-voltage").value().asDouble(), 0.001d);
  }

  @Test
  public void authenticationTimeoutIsTerminalAndDoesNotInventReconnect() {
    long generation = 23L;
    String deviceName = "NB-TEST";
    NinebotVehicleProtocolEngine engine = new NinebotVehicleProtocolEngine(NinebotVehicleProfile.DEFAULT_ID, deviceName);
    NinebotEncryption2Codec.Crypto peer = initialPeer(deviceName);
    engine.onInput(ProtocolRuntimeInput.linkReady(
        generation, 1L, NinebotVehicleGattProfile.CHANNEL_NUS, 23, true));
    byte[] auth = sequence(16, 1);
    byte[] serial = "SN123456789012".getBytes(StandardCharsets.UTF_8);
    engine.onInput(ProtocolRuntimeInput.bytes(
        generation, 2L, NinebotVehicleGattProfile.CHANNEL_NUS,
        peer.encrypt(responseFrame(NinebotEncryption2Codec.BT_ID, NinebotEncryption2Codec.CMD_PRE_COMM, 1, concat(auth, serial)))));
    byte[] password = sequence(16, 33);
    List<ProtocolEffect> authRequest = engine.onInput(ProtocolRuntimeInput.credentialAvailable(
        generation, 3L, NinebotVehicleProtocolEngine.CREDENTIAL_REF, password));
    ProtocolEffect responseTimer = armResponseTimer(engine, generation, authRequest, 4L);
    List<ProtocolEffect> timeout = timer(engine, generation, responseTimer.key(), 5L);
    assertEquals("ninebot-auth-timeout", effect(timeout, ProtocolEffect.Type.ERROR).key());
    assertFalse(hasType(timeout, ProtocolEffect.Type.REQUEST_RECONNECT));
    assertFalse(hasType(timeout, ProtocolEffect.Type.SEND));
    assertTrue(hasType(authRequest, ProtocolEffect.Type.SEND));
  }

  @Test
  public void manualDiagnosticReadsQueueWhileAnotherReadIsActive() {
    Session session = authenticatedSession(24L);
    List<ProtocolEffect> voltage = session.engine.onInput(ProtocolRuntimeInput.manualReadRequest(
        session.generation, 1_000_000_000L, "manual-v", "ninebot-bms-voltage-07"));
    assertTrue(hasType(voltage, ProtocolEffect.Type.SEND));
    session.engine.onInput(ProtocolRuntimeInput.manualReadRequest(
        session.generation, 1_000_000_001L, "manual-s", "ninebot-bms-soc-07"));
    session.engine.onInput(ProtocolRuntimeInput.manualReadRequest(
        session.generation, 1_000_000_002L, "manual-t", "ninebot-bms-temperature-07"));

    respond(session, 0x07, NinebotTelemetryCodec.REG_BMS_VOLTAGE, new byte[] { 0x7e, 0x13 }, 1_010_000_000L);
    List<ProtocolEffect> soc = timer(session.engine, session.generation, "ninebot:poll", 1_055_000_000L);
    assertTrue(hasType(soc, ProtocolEffect.Type.SEND));
    respond(session, 0x07, NinebotTelemetryCodec.REG_BMS_SOC, new byte[] { 75, 0 }, 1_060_000_000L);
    List<ProtocolEffect> temperature = timer(session.engine, session.generation, "ninebot:poll", 1_105_000_000L);
    assertTrue(hasType(temperature, ProtocolEffect.Type.SEND));
    respond(session, 0x07, NinebotTelemetryCodec.REG_BMS_TEMPERATURES,
        new byte[] { 20, 0, 21, 0, 22, 0, 23, 0 }, 1_110_000_000L);

    assertTrue(session.engine.diagnosticStatus().results().containsKey("voltage"));
    assertTrue(session.engine.diagnosticStatus().results().containsKey("soc"));
    assertTrue(session.engine.diagnosticStatus().results().containsKey("temperature"));
  }

  @Test
  public void configuredDiagnosticsRotateAtThreeSecondIntervals() {
    Session session = authenticatedSession(25L);
    session.engine.onInput(ProtocolRuntimeInput.manualReadRequest(
        session.generation,
        2_000_000_000L,
        "configure",
        NinebotVehicleProtocolEngine.diagnosticConfigurationOperation(0x07, true, true, true)));

    List<ProtocolEffect> voltage = timer(session.engine, session.generation, "ninebot:poll", 2_000_000_000L);
    assertTrue(hasType(voltage, ProtocolEffect.Type.SEND));
    respond(session, 0x07, NinebotTelemetryCodec.REG_BMS_VOLTAGE, new byte[] { 0x7e, 0x13 }, 2_010_000_000L);

    List<ProtocolEffect> soc = timer(session.engine, session.generation, "ninebot:poll", 5_000_000_000L);
    assertTrue(hasType(soc, ProtocolEffect.Type.SEND));
    respond(session, 0x07, NinebotTelemetryCodec.REG_BMS_SOC, new byte[] { 80, 0 }, 5_010_000_000L);
    assertTrue(session.engine.diagnosticStatus().results().containsKey("soc"));
  }

  @Test
  public void captureUsesKnownSweepQueueAndYieldsToDueTelemetryAfterTwoRequests() {
    Session session = authenticatedSession(26L);
    session.engine.onInput(ProtocolRuntimeInput.manualReadRequest(
        session.generation,
        1_000_000_000L,
        "capture",
        NinebotVehicleProtocolEngine.diagnosticCaptureStartOperation(0x07, 60_000L)));

    timer(session.engine, session.generation, "ninebot:poll", 1_000_000_000L);
    respond(session, 0x07, 0x02, new byte[] { 1, 2, 3, 4 }, 1_010_000_000L);
    timer(session.engine, session.generation, "ninebot:poll", 1_055_000_000L);
    respond(session, 0x07, 0x02, new byte[14], 1_065_000_000L);
    assertEquals(2, session.engine.diagnosticStatus().capture().requestCount());

    timer(session.engine, session.generation, "ninebot:poll", 1_110_000_000L);
    assertEquals(2, session.engine.diagnosticStatus().capture().requestCount());
    assertEquals("known", session.engine.diagnosticStatus().capture().phase());
  }

  @Test
  public void captureQueueRunsKnownProbesThenFullAddressSweepThenRepeatsKnownProbes() {
    NinebotVehicleProtocolEngine.ProbeSpec[] known = NinebotVehicleProtocolEngine.knownBmsProbesForTest();
    NinebotVehicleProtocolEngine.DiagnosticCaptureQueue queue =
        new NinebotVehicleProtocolEngine.DiagnosticCaptureQueue();
    for (NinebotVehicleProtocolEngine.ProbeSpec expected : known) {
      NinebotVehicleProtocolEngine.ProbeSpec actual = queue.next();
      assertEquals(expected.register(), actual.register());
      assertEquals(expected.readLength(), actual.readLength());
      assertEquals("known", actual.phase());
    }
    for (int index = 0;
         index < NinebotVehicleProtocolEngine.DiagnosticCaptureQueue.SWEEP_BLOCK_COUNT;
         index += 1) {
      NinebotVehicleProtocolEngine.ProbeSpec sweep = queue.next();
      assertEquals(index * 0x10, sweep.register());
      assertEquals(16, sweep.readLength());
      assertEquals("sweep", sweep.phase());
    }
    NinebotVehicleProtocolEngine.ProbeSpec repeated = queue.next();
    assertEquals(known[0].register(), repeated.register());
    assertEquals(known[0].readLength(), repeated.readLength());
    assertEquals("repeat_known", repeated.phase());
  }

  @Test
  public void captureTimeoutIsRecordedAtSixHundredMilliseconds() {
    Session session = authenticatedSession(27L);
    session.engine.onInput(ProtocolRuntimeInput.manualReadRequest(
        session.generation,
        3_000_000_000L,
        "capture",
        NinebotVehicleProtocolEngine.diagnosticCaptureStartOperation(0x22, 60_000L)));
    List<ProtocolEffect> request = timer(session.engine, session.generation, "ninebot:poll", 3_000_000_000L);
    ProtocolEffect responseTimer = armResponseTimer(session.engine, session.generation, request, 3_001_000_000L);
    assertEquals(NinebotVehicleProtocolEngine.DIAGNOSTIC_CAPTURE_TIMEOUT_MS, responseTimer.delayMs());
    timer(session.engine, session.generation, responseTimer.key(), 3_600_000_000L);

    NinebotVehicleProtocolEngine.CaptureStatus status = session.engine.diagnosticStatus().capture();
    assertEquals(1, status.timeoutCount());
    assertEquals("timeout", status.lastSample().error());
    assertEquals(0x02, status.lastSample().register());
  }

  @Test
  public void captureHasDedicatedDurationTimerAndCompletesEvenWithReadInFlight() {
    Session session = authenticatedSession(30L);
    List<ProtocolEffect> started = session.engine.onInput(ProtocolRuntimeInput.manualReadRequest(
        session.generation,
        4_000_000_000L,
        "capture",
        NinebotVehicleProtocolEngine.diagnosticCaptureStartOperation(0x07, 60_000L)));
    assertEquals(60_000L,
        effectWithKey(started, ProtocolEffect.Type.SCHEDULE_TIMER, "ninebot:capture-end").delayMs());
    timer(session.engine, session.generation, "ninebot:poll", 4_000_000_000L);

    List<ProtocolEffect> completed = timer(
        session.engine, session.generation, "ninebot:capture-end", 64_000_000_000L);
    assertEquals("completed", session.engine.diagnosticStatus().capture().state());
    assertTrue(hasEffectWithKey(completed, ProtocolEffect.Type.CANCEL_TIMER, "ninebot:response"));
    assertTrue(hasEffectWithKey(completed, ProtocolEffect.Type.DIAGNOSTIC, "ninebot-diagnostic-capture-completed"));
  }

  @Test
  public void authenticationWriteFragmentsAreSeparatedByTwelveMilliseconds() {
    long generation = 28L;
    String deviceName = "NB-TEST";
    NinebotVehicleProtocolEngine engine = new NinebotVehicleProtocolEngine(NinebotVehicleProfile.DEFAULT_ID, deviceName);
    NinebotEncryption2Codec.Crypto peer = initialPeer(deviceName);
    engine.onInput(ProtocolRuntimeInput.linkReady(
        generation, 1L, NinebotVehicleGattProfile.CHANNEL_NUS, 23, true));
    engine.onInput(ProtocolRuntimeInput.bytes(
        generation, 2L, NinebotVehicleGattProfile.CHANNEL_NUS,
        peer.encrypt(responseFrame(
            NinebotEncryption2Codec.BT_ID,
            NinebotEncryption2Codec.CMD_PRE_COMM,
            1,
            concat(sequence(16, 1), "SN123456789012".getBytes(StandardCharsets.UTF_8))))));
    List<ProtocolEffect> current = engine.onInput(ProtocolRuntimeInput.credentialAvailable(
        generation, 3L, NinebotVehicleProtocolEngine.CREDENTIAL_REF, sequence(16, 33)));
    int chunks = 0;
    for (int attempt = 0; attempt < 8; attempt += 1) {
      if (hasType(current, ProtocolEffect.Type.SEND)) {
        chunks += 1;
        assertEquals(12L, effectWithKey(current, ProtocolEffect.Type.SCHEDULE_TIMER, "ninebot:chunk").delayMs());
      }
      if (hasEffectWithKey(current, ProtocolEffect.Type.SCHEDULE_TIMER, "ninebot:response")) break;
      current = timer(engine, generation, "ninebot:chunk", 4L + attempt);
    }
    assertTrue(chunks >= 2);
    assertTrue(hasEffectWithKey(current, ProtocolEffect.Type.SCHEDULE_TIMER, "ninebot:response"));
  }

  @Test
  public void appAndAgentEnginesProduceIdenticalEffectsForSameTimedInputs() {
    long generation = 29L;
    String deviceName = "NB-PARITY";
    NinebotVehicleProtocolEngine appEngine =
        new NinebotVehicleProtocolEngine(NinebotVehicleProfile.DEFAULT_ID, deviceName);
    NinebotVehicleProtocolEngine agentEngine =
        new NinebotVehicleProtocolEngine(NinebotVehicleProfile.DEFAULT_ID, deviceName);
    NinebotEncryption2Codec.Crypto appPeer = initialPeer(deviceName);
    NinebotEncryption2Codec.Crypto agentPeer = initialPeer(deviceName);

    ProtocolRuntimeInput link = ProtocolRuntimeInput.linkReady(
        generation, 1L, NinebotVehicleGattProfile.CHANNEL_NUS, 23, true);
    assertEquivalent(appEngine.onInput(link), agentEngine.onInput(link));

    byte[] auth = sequence(16, 1);
    byte[] serial = "SN123456789012".getBytes(StandardCharsets.UTF_8);
    byte[] precommPlaintext = responseFrame(
        NinebotEncryption2Codec.BT_ID,
        NinebotEncryption2Codec.CMD_PRE_COMM,
        1,
        concat(auth, serial));
    assertEquivalent(
        appEngine.onInput(ProtocolRuntimeInput.bytes(
            generation, 2L, NinebotVehicleGattProfile.CHANNEL_NUS, appPeer.encrypt(precommPlaintext))),
        agentEngine.onInput(ProtocolRuntimeInput.bytes(
            generation, 2L, NinebotVehicleGattProfile.CHANNEL_NUS, agentPeer.encrypt(precommPlaintext))));

    byte[] password = sequence(16, 33);
    assertEquivalent(
        appEngine.onInput(ProtocolRuntimeInput.credentialAvailable(
            generation, 3L, NinebotVehicleProtocolEngine.CREDENTIAL_REF, password)),
        agentEngine.onInput(ProtocolRuntimeInput.credentialAvailable(
            generation, 3L, NinebotVehicleProtocolEngine.CREDENTIAL_REF, password)));

    appPeer.setKey(password, auth);
    appPeer.setAuthParam(auth);
    appPeer.startSn();
    agentPeer.setKey(password, auth);
    agentPeer.setAuthParam(auth);
    agentPeer.startSn();
    byte[] authPlaintext = responseFrame(
        NinebotEncryption2Codec.BT_ID, NinebotEncryption2Codec.CMD_AUTH, 1, null);
    assertEquivalent(
        appEngine.onInput(ProtocolRuntimeInput.bytes(
            generation, 4L, NinebotVehicleGattProfile.CHANNEL_NUS, appPeer.encrypt(authPlaintext))),
        agentEngine.onInput(ProtocolRuntimeInput.bytes(
            generation, 4L, NinebotVehicleGattProfile.CHANNEL_NUS, agentPeer.encrypt(authPlaintext))));
    assertEquivalent(
        timer(appEngine, generation, "ninebot:poll", 1_000_000_000L),
        timer(agentEngine, generation, "ninebot:poll", 1_000_000_000L));
  }

  private static Session authenticatedSession(long generation) {
    String deviceName = "NB-TEST";
    NinebotVehicleProtocolEngine engine = new NinebotVehicleProtocolEngine(NinebotVehicleProfile.DEFAULT_ID, deviceName);
    NinebotEncryption2Codec.Crypto peer = initialPeer(deviceName);
    engine.onInput(ProtocolRuntimeInput.linkReady(
        generation, 1L, NinebotVehicleGattProfile.CHANNEL_NUS, 23, true));
    byte[] auth = sequence(16, 1);
    byte[] serial = "SN123456789012".getBytes(StandardCharsets.UTF_8);
    engine.onInput(ProtocolRuntimeInput.bytes(
        generation, 2L, NinebotVehicleGattProfile.CHANNEL_NUS,
        peer.encrypt(responseFrame(NinebotEncryption2Codec.BT_ID, NinebotEncryption2Codec.CMD_PRE_COMM, 1, concat(auth, serial)))));
    byte[] password = sequence(16, 33);
    engine.onInput(ProtocolRuntimeInput.credentialAvailable(
        generation, 3L, NinebotVehicleProtocolEngine.CREDENTIAL_REF, password));

    peer.setKey(password, auth);
    peer.setAuthParam(auth);
    peer.startSn();
    engine.onInput(ProtocolRuntimeInput.bytes(
        generation, 4L, NinebotVehicleGattProfile.CHANNEL_NUS,
        peer.encrypt(responseFrame(NinebotEncryption2Codec.BT_ID, NinebotEncryption2Codec.CMD_AUTH, 1, null))));
    return new Session(generation, engine, peer);
  }

  private static NinebotEncryption2Codec.Crypto initialPeer(String deviceName) {
    NinebotEncryption2Codec.Crypto peer = NinebotEncryption2Codec.Crypto.gen2();
    peer.setKey(deviceName.getBytes(StandardCharsets.UTF_8), NinebotEncryption2Codec.firmwareData());
    peer.resetSn();
    return peer;
  }

  private static byte[] responseFrame(int source, int command, int index, byte[] data) {
    byte[] body = data == null ? new byte[0] : data;
    byte[] frame = new byte[7 + body.length];
    frame[0] = 0x5a;
    frame[1] = (byte) 0xa5;
    frame[2] = (byte) body.length;
    frame[3] = (byte) source;
    frame[4] = (byte) NinebotEncryption2Codec.BT_ID;
    frame[5] = (byte) command;
    frame[6] = (byte) index;
    System.arraycopy(body, 0, frame, 7, body.length);
    return frame;
  }

  private static byte[] sequence(int length, int start) {
    byte[] value = new byte[length];
    for (int index = 0; index < length; index += 1) value[index] = (byte) (start + index);
    return value;
  }

  private static byte[] concat(byte[] left, byte[] right) {
    byte[] result = Arrays.copyOf(left, left.length + right.length);
    System.arraycopy(right, 0, result, left.length, right.length);
    return result;
  }

  private static List<ProtocolEffect> timer(NinebotVehicleProtocolEngine engine, long generation, String key, long at) {
    return engine.onInput(ProtocolRuntimeInput.event(ProtocolRuntimeInput.Kind.TIMER, generation, at, key));
  }

  private static List<ProtocolEffect> respond(
      Session session,
      int source,
      int register,
      byte[] data,
      long at) {
    return session.engine.onInput(ProtocolRuntimeInput.bytes(
        session.generation,
        at,
        NinebotVehicleGattProfile.CHANNEL_NUS,
        session.peer.encrypt(responseFrame(source, NinebotEncryption2Codec.CMD_READ_RESP, register, data))));
  }

  private static ProtocolEffect armResponseTimer(
      NinebotVehicleProtocolEngine engine,
      long generation,
      List<ProtocolEffect> effects,
      long at) {
    List<ProtocolEffect> current = effects;
    for (int attempt = 0; attempt < 8; attempt += 1) {
      for (ProtocolEffect effect : current) {
        if (effect.type() == ProtocolEffect.Type.SCHEDULE_TIMER && "ninebot:response".equals(effect.key())) return effect;
      }
      current = timer(engine, generation, "ninebot:chunk", at + attempt);
    }
    throw new AssertionError("Response timer was not armed");
  }

  private static ProtocolEffect effect(List<ProtocolEffect> effects, ProtocolEffect.Type type) {
    for (ProtocolEffect effect : effects) if (effect.type() == type) return effect;
    throw new AssertionError("Missing " + type + " in " + effects);
  }

  private static ProtocolEffect effectWithKey(List<ProtocolEffect> effects, ProtocolEffect.Type type, String key) {
    for (ProtocolEffect effect : effects) if (effect.type() == type && key.equals(effect.key())) return effect;
    throw new AssertionError("Missing " + type + "/" + key + " in " + effects);
  }

  private static boolean hasType(List<ProtocolEffect> effects, ProtocolEffect.Type type) {
    for (ProtocolEffect effect : effects) if (effect.type() == type) return true;
    return false;
  }

  private static boolean hasEffectWithKey(List<ProtocolEffect> effects, ProtocolEffect.Type type, String key) {
    for (ProtocolEffect effect : effects) if (effect.type() == type && key.equals(effect.key())) return true;
    return false;
  }

  private static void assertEquivalent(List<ProtocolEffect> expected, List<ProtocolEffect> actual) {
    assertEquals(expected.size(), actual.size());
    for (int index = 0; index < expected.size(); index += 1) {
      ProtocolEffect left = expected.get(index);
      ProtocolEffect right = actual.get(index);
      assertEquals(left.type(), right.type());
      assertEquals(left.key(), right.key());
      assertEquals(left.requestId(), right.requestId());
      assertEquals(left.characteristicId(), right.characteristicId());
      assertEquals(left.delayMs(), right.delayMs());
      assertEquals(left.writeMode(), right.writeMode());
      assertEquals(left.message(), right.message());
      assertTrue(Arrays.equals(left.bytes(), right.bytes()));
      assertSnapshotEquivalent(left.snapshot(), right.snapshot());
    }
  }

  private static void assertSnapshotEquivalent(ProtocolSnapshot expected, ProtocolSnapshot actual) {
    if (expected == null || actual == null) {
      assertEquals(expected, actual);
      return;
    }
    assertEquals(expected.role(), actual.role());
    assertEquals(expected.family(), actual.family());
    assertEquals(expected.variant(), actual.variant());
    assertEquals(expected.profileId(), actual.profileId());
    assertEquals(expected.linkGeneration(), actual.linkGeneration());
    assertEquals(expected.capabilities(), actual.capabilities());
    assertEquals(expected.rawEvidenceRefs(), actual.rawEvidenceRefs());
    assertEquals(expected.fields().keySet(), actual.fields().keySet());
    for (String key : expected.fields().keySet()) {
      ProtocolField left = expected.fields().get(key);
      ProtocolField right = actual.fields().get(key);
      assertEquals(left.id(), right.id());
      assertEquals(left.group(), right.group());
      assertEquals(left.unit(), right.unit());
      assertEquals(left.sourceMonotonicNanos(), right.sourceMonotonicNanos());
      assertEquals(left.acquisition(), right.acquisition());
      assertEquals(left.validity(), right.validity());
      assertEquals(left.evidenceRef(), right.evidenceRef());
      assertEquals(valueFingerprint(left.value()), valueFingerprint(right.value()));
    }
  }

  private static String valueFingerprint(ProtocolValue value) {
    if (value == null) return "null";
    switch (value.type()) {
      case DOUBLE: return "d:" + value.asDouble();
      case LONG: return "l:" + value.asLong();
      case BOOLEAN: return "b:" + value.asBoolean();
      case STRING: return "s:" + value.asString();
      case DOUBLE_LIST: return "dl:" + value.asDoubles();
      case LONG_LIST: return "ll:" + value.asLongs();
      case BYTES: return "x:" + Arrays.toString(value.asBytes());
      default: throw new AssertionError(value.type());
    }
  }

  private static final class Session {
    final long generation;
    final NinebotVehicleProtocolEngine engine;
    final NinebotEncryption2Codec.Crypto peer;
    Session(long generation, NinebotVehicleProtocolEngine engine, NinebotEncryption2Codec.Crypto peer) {
      this.generation = generation;
      this.engine = engine;
      this.peer = peer;
    }
  }
}
