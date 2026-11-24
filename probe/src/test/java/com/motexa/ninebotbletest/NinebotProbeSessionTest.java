package com.motexa.ninebotbletest;

import static org.junit.Assert.*;

import org.openninebot.reference.app.NinebotEncryption2Codec;
import org.openninebot.reference.app.NinebotVehicleProfile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Wire literals are independently generated with Python cryptography, not a Java peer. */
public final class NinebotProbeSessionTest {
    private static final String NAME = "NB-PROBE-SYNTH";
    private static final String PASSWORD = "2122232425262728292a2b2c2d2e2f30";
    private static final String PRE_REQ = "5aa5007a1f7e1a000062ff0000";
    private static final String PRE_RESP = "5aa51e40257e1be872f82f22de77cfe50aeca249152a0aba3eca1914ec45f1db38de9e75290000c8fb0000";
    private static final String AUTH_REQ = "5aa50ee9aaec493dde7cea7833e7da2a76fcaa5bcc30d672240002";
    private static final String AUTH_RESP = "5aa500d390ec48981634a40002";
    private static final String READ_REQ = "5aa501da1453485fdf8f26af0003";
    private static final String READ_RESP = "5aa502e52b564826e8dad3db730003";

    private static class Observer implements NinebotProbeSession.Listener {
        final List<byte[]> sends = new ArrayList<>();
        final List<String> details = new ArrayList<>();
        final List<NinebotEncryption2Codec.Frame> frames = new ArrayList<>();
        final List<byte[]> configs = new ArrayList<>();
        final List<Integer> modes = new ArrayList<>();
        @Override public void onSend(byte[] bytes, String stage) { sends.add(bytes.clone()); }
        @Override public void onState(String stage, String detail) { details.add(detail); }
        @Override public void onFrame(NinebotEncryption2Codec.Frame frame) { frames.add(frame); }
        @Override public void onCastConfig(byte[] raw) { configs.add(raw.clone()); }
        @Override public void onModeAcknowledged(int mode) { modes.add(mode); }
    }

    private static NinebotProbeSession session(Observer o, boolean credential) {
        return new NinebotProbeSession(NAME, NinebotVehicleProfile.ENCRYPTION2_STANDARD_NUS,
                credential ? hex(PASSWORD) : null, o);
    }

    private static NinebotProbeSession authenticated(Observer o) {
        NinebotProbeSession s = session(o, true);
        s.start(); s.accept(hex(PRE_RESP)); s.accept(hex(AUTH_RESP));
        assertTrue(s.isAuthenticated());
        return s;
    }

    @Test public void bootstrapMatchesIndependentWireAndDoesNotPoll() {
        Observer o = new Observer(); NinebotProbeSession s = session(o, false); s.start();
        assertArrayEquals(hex(PRE_REQ), o.sends.get(0));
        assertEquals("wait_precomm", s.state());
        assertEquals(1, o.sends.size());
    }

    @Test public void fragmentedIdentityStopsForExistingCredential() {
        Observer o = new Observer(); NinebotProbeSession s = session(o, false); s.start();
        byte[] reply = hex(PRE_RESP);
        s.accept(Arrays.copyOfRange(reply, 0, 20));
        s.accept(Arrays.copyOfRange(reply, 20, 40));
        assertEquals("wait_precomm", s.state());
        s.accept(Arrays.copyOfRange(reply, 40, reply.length));
        assertEquals("credential_required", s.state());
        assertEquals(1, o.sends.size());
        assertFalse(s.isAuthenticated());
    }

    @Test public void authAndSingleReadMatchIndependentWire() {
        Observer o = new Observer(); NinebotProbeSession s = authenticated(o);
        assertArrayEquals(hex(AUTH_REQ), o.sends.get(1));
        assertEquals(2, o.sends.size()); // no automatic telemetry or mode command
        s.read(1, 0x26, 2);
        assertArrayEquals(hex(READ_REQ), o.sends.get(2));
        s.accept(hex(READ_RESP));
        assertEquals("authenticated", s.state());
        assertEquals(0x26, o.frames.get(2).index);
        assertArrayEquals(new byte[] {123, 0}, o.frames.get(2).data);
    }

    @Test public void unpairedVehicleNeverReceivesSetPasswordEvenWhenCredentialWasSupplied() {
        Observer o = new Observer(); NinebotProbeSession s = session(o, true); s.start();
        s.accept(hex("5aa51e40257e1ae872f82f22de77cfe50aeca249152a0aba3eca1914ec45f1db38de9e75290000c9fb0000"));
        assertEquals("unpaired", s.state());
        assertEquals(1, o.sends.size());
        assertFalse(s.isAuthenticated());
    }

    @Test public void profileUsesObservedMmax90TargetNine() {
        Observer o = new Observer();
        NinebotProbeSession s = new NinebotProbeSession(NAME,
                NinebotVehicleProfile.ENCRYPTION2_MMAX90_CAPTURE_NUS, hex(PASSWORD), o);
        s.start();
        assertArrayEquals(hex("5aa5007a127e1a00005dff0000"), o.sends.get(0));
        s.accept(hex("5aa51e4d257e1be872f82f22de77cfe50aeca249152a0aba3eca1914ec45f1db38de9e75290000c3fb0000"));
        assertArrayEquals(hex("5aa50ee9a7ec493dde7cea7833e7da2a76fcaa5bcc26ae49060002"), o.sends.get(1));
        s.accept(hex("5aa500de90ec4849e2c8a20002"));
        assertTrue(s.isAuthenticated());
    }

    @Test public void wrongBoardCannotAdvanceBootstrap() {
        Observer o = new Observer(); NinebotProbeSession s = session(o, true); s.start();
        s.accept(hex("5aa51e4d257e1be872f82f22de77cfe50aeca249152a0aba3eca1914ec45f1db38de9e75290000c3fb0000"));
        assertEquals("failed", s.state()); assertEquals(1, o.sends.size());
    }

    @Test public void rejectedAuthDoesNotRetryOrSetPassword() {
        Observer o = new Observer(); NinebotProbeSession s = session(o, true); s.start(); s.accept(hex(PRE_RESP));
        s.accept(hex("5aa500d390ec498234f9360002"));
        assertEquals("failed", s.state()); assertEquals(2, o.sends.size());
    }

    @Test public void corruptAuthenticationTagIsTerminal() {
        Observer o = new Observer(); NinebotProbeSession s = session(o, true); s.start(); s.accept(hex(PRE_RESP));
        byte[] bad = hex(AUTH_RESP); bad[bad.length - 6] ^= 1; s.accept(bad);
        assertEquals("failed", s.state()); assertFalse(s.isAuthenticated());
        assertTrue(o.details.contains("integrity-rejected"));
    }

    @Test public void zeroCounterCannotDowngradeAuthenticatedRead() {
        Observer o = new Observer(); NinebotProbeSession s = authenticated(o); s.read(1, 0x26, 2);
        s.accept(hex("5aa502c8af507ec05700001bff0000"));
        assertEquals("failed", s.state());
        assertTrue(o.details.contains("unexpected-crypto-counter-mode"));
    }

    @Test public void replayCounterCannotCompleteRead() {
        Observer o = new Observer(); NinebotProbeSession s = authenticated(o); s.read(1, 0x26, 2);
        s.accept(hex("5aa502d690b56f1590d51703530002"));
        assertEquals("failed", s.state()); assertTrue(o.details.contains("replay-rejected"));
    }

    @Test public void readMustMatchRequestedBoardAndRegister() {
        Observer o = new Observer(); NinebotProbeSession s = authenticated(o); s.read(1, 0x26, 2);
        s.accept(hex("5aa502e52b564926e8f1255f790003"));
        assertEquals("failed", s.state()); assertEquals(2, o.frames.size());
    }

    @Test public void onlyOneRequestCanBeInFlight() {
        Observer o = new Observer(); NinebotProbeSession s = authenticated(o); s.read(1, 0x26, 2);
        try { s.read(1, 0x27, 2); fail("second in-flight request"); }
        catch (IllegalStateException expected) { assertEquals("read-not-ready", expected.getMessage()); }
        assertEquals(3, o.sends.size());
    }

    @Test public void timeoutAndCloseCannotSendMoreBytes() {
        Observer o = new Observer(); NinebotProbeSession s = session(o, true); s.start(); s.onTimeout();
        s.accept(hex(PRE_RESP)); s.onTimeout();
        assertEquals("failed", s.state()); assertEquals(1, o.sends.size());
        s.close(); s.close(); s.accept(hex(PRE_RESP));
        assertEquals("closed", s.state()); assertFalse(s.isAuthenticated());
        assertEquals(1, o.sends.size());
    }

    @Test public void mediaCustomUuidMustNotBeSelectedAsGen2Control() {
        try { new NinebotProbeSession(NAME, NinebotVehicleProfile.ENCRYPTION2_STANDARD_CUSTOM, null, new Observer());
            fail("custom media UUID was accepted as control"); }
        catch (IllegalArgumentException expected) { assertEquals("probe-requires-nus-control-channel", expected.getMessage()); }
    }

    @Test public void credentialFormatIsCheckedBeforeAnySend() {
        Observer o = new Observer();
        try { new NinebotProbeSession(NAME, NinebotVehicleProfile.ENCRYPTION2_STANDARD_NUS, new byte[15], o);
            fail("short password"); }
        catch (IllegalArgumentException expected) { assertEquals("password-length", expected.getMessage()); }
        assertTrue(o.sends.isEmpty());
    }

    @Test public void smallConfigMatchesWireAndVerifiesRawNulBeforeCallback() {
        Observer o = new Observer(); NinebotProbeSession s = authenticated(o);
        s.readCastConfig(0x23);
        assertArrayEquals(wire("config-small-begin-request"), o.sends.get(2));
        s.accept(wire("config-small-begin-response"));
        assertArrayEquals(wire("config-small-group-0-request"), o.sends.get(3));
        s.accept(wire("config-small-group-0-part-0"));
        assertArrayEquals(wire("config-small-verify-request"), o.sends.get(4));
        assertTrue(o.configs.isEmpty());
        s.accept(wire("config-small-verify-response"));
        assertEquals("authenticated", s.state());
        assertArrayEquals("{\"channel\":0,\"codecType\":0}\0".getBytes(StandardCharsets.UTF_8), o.configs.get(0));
        assertEquals(5, o.sends.size()); // config does not automatically enable casting
    }

    @Test public void fileLargerThanOneGroupResetsSmallIndexAndChecksMd5() {
        Observer o = new Observer(); NinebotProbeSession s = authenticated(o);
        s.readCastConfig(0x23); s.accept(wire("config-long-begin-response"));
        for (int i = 0; i < 8; i++) s.accept(wire("config-long-group-0-part-" + i));
        assertEquals("wait_config_data", s.state());
        assertArrayEquals(wire("config-long-group-1-request"), o.sends.get(4));
        s.accept(wire("config-long-group-1-part-0"));
        assertArrayEquals(wire("config-long-verify-request"), o.sends.get(5));
        s.accept(wire("config-long-verify-response"));
        byte[] expected = ("{\"padding\":\"" + "x".repeat(1100) + "\"}\0").getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, o.configs.get(0));
        assertEquals("authenticated", s.state());
    }

    @Test public void mismatchMd5NeverPublishesConfiguration() {
        Observer o = new Observer(); NinebotProbeSession s = authenticated(o);
        s.readCastConfig(0x23); s.accept(wire("config-small-begin-response"));
        s.accept(wire("config-small-group-0-part-0")); s.accept(wire("config-small-wrong-md5"));
        assertEquals("failed", s.state()); assertTrue(o.configs.isEmpty());
        assertTrue(o.details.contains("config-md5-mismatch"));
    }

    @Test public void nominal128ByteFilePacketsMayHaveVariableLengths() {
        Observer o = new Observer(); NinebotProbeSession s = authenticated(o);
        s.readCastConfig(0x23); s.accept(wire("config-variable-begin-response"));
        s.accept(wire("config-variable-group-0-part-0")); // 64 of declared 129 bytes
        assertEquals("wait_config_data", s.state());
        s.accept(wire("config-variable-group-0-part-1")); // 65 more; packet count ceil(129/128)=2
        assertEquals("wait_config_verify", s.state());
        s.accept(wire("config-variable-verify-response"));
        assertEquals(129, o.configs.get(0).length);
        assertEquals("authenticated", s.state());
    }

    @Test public void lastPacketMustReachDeclaredFileSizeWithoutGuessingPadding() {
        for (String fixture : new String[] {"config-variable-short-final", "config-variable-padded-final"}) {
            Observer o = new Observer(); NinebotProbeSession s = authenticated(o);
            s.readCastConfig(0x23); s.accept(wire("config-variable-begin-response"));
            s.accept(wire("config-variable-group-0-part-0")); s.accept(wire(fixture));
            assertEquals("failed", s.state()); assertTrue(o.configs.isEmpty());
            assertEquals(4, o.sends.size()); // neither verify nor mode is sent
        }
    }

    @Test public void outOfOrderConfigChunkFailsWithoutRetry() {
        Observer o = new Observer(); NinebotProbeSession s = authenticated(o);
        s.readCastConfig(0x23); s.accept(wire("config-long-begin-response"));
        s.accept(wire("config-wrong-first-index"));
        assertEquals("failed", s.state()); assertTrue(o.configs.isEmpty());
        assertEquals(4, o.sends.size());
    }

    @Test public void excessiveFileLengthDoesNotAllocateOrReadData() {
        Observer o = new Observer(); NinebotProbeSession s = authenticated(o);
        s.readCastConfig(0x23); s.accept(wire("config-oversize"));
        assertEquals("failed", s.state()); assertEquals(3, o.sends.size());
        assertTrue(o.details.contains("config-length-out-of-range"));
    }

    @Test public void unverifiedTftCannotReceiveFileOrModeCommand() {
        Observer o = new Observer(); NinebotProbeSession s = authenticated(o);
        try { s.readCastConfig(0x24); fail("unverified target"); }
        catch (IllegalArgumentException expected) { assertEquals("unverified-tft-target", expected.getMessage()); }
        try { s.setNaviMode(0x01, 2); fail("unverified target"); }
        catch (IllegalArgumentException expected) { assertEquals("unverified-tft-target", expected.getMessage()); }
        assertEquals(2, o.sends.size());
    }

    @Test public void modeOnlyAcknowledgesMatchingCommandAndDoesNotStartMedia() {
        Observer o = new Observer(); NinebotProbeSession s = authenticated(o);
        s.setNaviMode(0x23, 2);
        assertArrayEquals(wire("mode-map-request"), o.sends.get(2));
        assertTrue(o.modes.isEmpty());
        s.accept(wire("mode-map-response"));
        assertEquals(Integer.valueOf(2), o.modes.get(0));
        assertEquals("authenticated", s.state());
        assertTrue(o.details.contains("matching-mode-response-not-display-confirmation"));
        assertEquals(3, o.sends.size());
    }

    @Test public void wrongModeReplyBoardIsRejected() {
        Observer o = new Observer(); NinebotProbeSession s = authenticated(o); s.setNaviMode(0x23, 2);
        s.accept(wire("mode-wrong-target"));
        assertEquals("failed", s.state()); assertTrue(o.modes.isEmpty());
    }

    @Test public void modeCallbackMayCloseSessionWithoutStateBeingOverwritten() {
        NinebotProbeSession[] holder = new NinebotProbeSession[1];
        Observer o = new Observer() {
            @Override public void onModeAcknowledged(int mode) { holder[0].close(); }
        };
        holder[0] = authenticated(o); holder[0].setNaviMode(0x23, 2); holder[0].accept(wire("mode-map-response"));
        assertEquals("closed", holder[0].state()); assertFalse(holder[0].isAuthenticated());
    }

    @Test public void configCallbackMayCloseSessionWithoutStateBeingOverwritten() {
        NinebotProbeSession[] holder = new NinebotProbeSession[1];
        Observer o = new Observer() {
            @Override public void onCastConfig(byte[] raw) { holder[0].close(); }
        };
        holder[0] = authenticated(o); holder[0].readCastConfig(0x23);
        holder[0].accept(wire("config-small-begin-response"));
        holder[0].accept(wire("config-small-group-0-part-0"));
        holder[0].accept(wire("config-small-verify-response"));
        assertEquals("closed", holder[0].state()); assertFalse(holder[0].isAuthenticated());
    }

    private static byte[] wire(String id) {
        try (java.io.InputStream in = NinebotProbeSessionTest.class.getResourceAsStream("/probe-wire.properties")) {
            if (in == null) throw new AssertionError("missing independent wire fixtures");
            Properties properties = new Properties(); properties.load(in);
            String value = properties.getProperty(id);
            if (value == null) throw new AssertionError("missing fixture " + id);
            return hex(value);
        } catch (java.io.IOException ex) { throw new AssertionError(ex); }
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(value.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
