package com.motexa.ninebotbletest;

import org.openninebot.reference.app.NinebotEncryption2Codec;
import org.openninebot.reference.app.NinebotVehicleProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Bounded Encryption2 probe for a positively identified NUS control link.
 * The host owns ATT fragmentation, timeouts and serialization of callbacks.
 * This class never creates a credential, retries, polls or starts casting.
 */
public final class NinebotProbeSession {
    public interface Listener {
        void onSend(byte[] encrypted, String stage);
        void onState(String stage, String detail);
        void onFrame(NinebotEncryption2Codec.Frame frame);
        /** Complete MD5-verified file, including any terminal NUL. */
        default void onCastConfig(byte[] rawBytes) {}
        default void onModeAcknowledged(int mode) {}
    }

    private final NinebotVehicleProfile profile;
    private final Listener listener;
    private final byte[] name;
    private final NinebotEncryption2Codec.FrameAccumulator accumulator =
            new NinebotEncryption2Codec.FrameAccumulator();
    private byte[] password;
    private byte[] auth;
    private byte[] serial;
    private NinebotEncryption2Codec.Crypto crypto = NinebotEncryption2Codec.Crypto.gen2();
    private String state = "idle";
    private boolean authenticated;
    private int expectedTarget = -1;
    private int expectedRegister = -1;
    private byte[] configBytes;
    private int configOffset;
    private int configGroup;
    private int configSmallIndex;
    private int configPacketCount;
    private int configPacketIndex;
    private int expectedMode = -1;
    public static final int VERIFIED_TFT_TARGET = 0x23;
    public static final int MAX_CAST_CONFIG_BYTES = 256 * 1024;

    public NinebotProbeSession(String name, String profileId, byte[] password16, Listener listener) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("device-name-required");
        profile = NinebotVehicleProfile.resolve(profileId);
        if (profile == null) throw new IllegalArgumentException("unsupported-gen2-profile");
        if (profile.channelPreference == NinebotVehicleProfile.ChannelPreference.CUSTOM
                || profile.channelPreference == NinebotVehicleProfile.ChannelPreference.AUTO_CUSTOM_FIRST) {
            throw new IllegalArgumentException("probe-requires-nus-control-channel");
        }
        if (password16 != null && password16.length != 16) throw new IllegalArgumentException("password-length");
        if (listener == null) throw new IllegalArgumentException("listener");
        this.name = name.getBytes(StandardCharsets.UTF_8);
        this.password = password16 == null ? null : password16.clone();
        this.listener = listener;
    }

    public String state() { return state; }
    public boolean isAuthenticated() { return authenticated; }

    /** Call once, only after NUS notifications have been enabled successfully. */
    public void start() {
        if (!"idle".equals(state)) throw new IllegalStateException("session-already-started");
        crypto.setKey(name, NinebotEncryption2Codec.firmwareData());
        crypto.resetSn();
        move("wait_precomm", "single-precomm-no-credential-change");
        send(NinebotEncryption2Codec.buildFrame(profile.preCommTarget,
                NinebotEncryption2Codec.CMD_PRE_COMM, 0, null), "precomm");
    }

    public void accept(byte[] fragment) {
        if (!isWaiting() || fragment == null || fragment.length == 0) return;
        for (byte[] encrypted : accumulator.append(fragment)) {
            if (!isWaiting()) break;
            int counter = ((encrypted[encrypted.length - 2] & 0xff) << 8)
                    | (encrypted[encrypted.length - 1] & 0xff);
            if (("wait_precomm".equals(state) && counter != 0)
                    || (!"wait_precomm".equals(state) && counter == 0)) {
                fail("unexpected-crypto-counter-mode");
                return;
            }
            NinebotEncryption2Codec.Crypto.DecryptResult result = crypto.decrypt(encrypted);
            if (result.returnCode != NinebotEncryption2Codec.Crypto.SUCCESS) {
                fail(result.returnCode == NinebotEncryption2Codec.Crypto.REPLAY_FAILURE
                        ? "replay-rejected" : "integrity-rejected");
                return;
            }
            NinebotEncryption2Codec.Frame frame = NinebotEncryption2Codec.parseFrame(result.plaintext);
            if (frame == null) { fail("invalid-response-frame"); return; }
            if ("wait_precomm".equals(state)) handlePrecomm(frame);
            else if ("wait_auth".equals(state)) handleAuth(frame);
            else if ("wait_read".equals(state)) handleRead(frame);
            else if ("wait_config_begin".equals(state)) handleConfigBegin(frame);
            else if ("wait_config_data".equals(state)) handleConfigData(frame);
            else if ("wait_config_verify".equals(state)) handleConfigVerify(frame);
            else if ("wait_mode".equals(state)) handleMode(frame);
        }
        if (accumulator.hasSyncMismatch() && isWaiting()) {
            accumulator.consumeRejectedBuffer();
            fail("unexpected-frame-sync");
        }
    }

    private void handlePrecomm(NinebotEncryption2Codec.Frame frame) {
        if (frame.cmd != NinebotEncryption2Codec.CMD_PRE_COMM
                || frame.sourceBoard != profile.preCommTarget || frame.data.length < 30) {
            fail("unexpected-precomm-response");
            return;
        }
        auth = Arrays.copyOfRange(frame.data, 0, 16);
        serial = Arrays.copyOfRange(frame.data, 16, 30);
        // Identity bytes are deliberately not included in state-detail logs.
        listener.onFrame(frame);
        if (frame.index == 0) {
            move("unpaired", "vehicle-has-no-password-stop-without-set-pwd");
            discardSecrets();
            return;
        }
        if (password == null) {
            move("credential_required", "existing-16-byte-password-required-no-set-pwd");
            discardSecrets();
            return;
        }
        crypto.setAuthParam(auth);
        crypto.startSn();
        crypto.setKey(password, auth);
        move("wait_auth", "authenticate-with-existing-credential");
        send(NinebotEncryption2Codec.buildFrame(profile.authTarget,
                NinebotEncryption2Codec.CMD_AUTH, 0, serial), "auth");
    }

    private void handleAuth(NinebotEncryption2Codec.Frame frame) {
        if (frame.cmd != NinebotEncryption2Codec.CMD_AUTH
                || frame.sourceBoard != profile.authTarget || frame.index != 1) {
            fail("auth-rejected-or-unexpected-response");
            return;
        }
        authenticated = true;
        listener.onFrame(frame);
        move("authenticated", "auth-accepted-preparation-not-yet-validated");
    }

    /** One ordinary register read. This is not a generic command/raw-write API. */
    public void read(int target, int index, int length) {
        if (!authenticated || !"authenticated".equals(state)) throw new IllegalStateException("read-not-ready");
        if (target < 0 || target > 255 || index < 0 || index > 255 || length < 1 || length > 255) {
            throw new IllegalArgumentException("read-range");
        }
        expectedTarget = target;
        expectedRegister = index;
        move("wait_read", "single-read");
        send(NinebotEncryption2Codec.buildReadFrame(target, index, length), "read");
    }

    private void handleRead(NinebotEncryption2Codec.Frame frame) {
        if (frame.cmd != NinebotEncryption2Codec.CMD_READ_RESP
                || frame.sourceBoard != expectedTarget || frame.index != expectedRegister) {
            fail("unexpected-read-response");
            return;
        }
        expectedTarget = expectedRegister = -1;
        listener.onFrame(frame);
        move("authenticated", "read-response-verified");
    }

    /** Only for a separately selected model table verified to use TFT 0x23. */
    public void readCastConfig(int tftTarget) {
        requireControlReady(tftTarget);
        configBytes = null;
        configOffset = configGroup = configSmallIndex = configPacketIndex = configPacketCount = 0;
        move("wait_config_begin", "read-type1-file-length");
        send(NinebotEncryption2Codec.buildFrame(tftTarget, 0x82, 1,
                new byte[] {1, 8, 0}), "config-begin");
    }

    private void handleConfigBegin(NinebotEncryption2Codec.Frame frame) {
        // The official callback accepts loosely sized input. The probe deliberately
        // requires its expected LE32 length and fails instead of allocating blindly.
        if (frame.sourceBoard != VERIFIED_TFT_TARGET || frame.cmd != 0x82 || frame.data.length != 4) {
            fail("unexpected-config-begin-response");
            return;
        }
        long length = (frame.data[0] & 255L) | ((frame.data[1] & 255L) << 8)
                | ((frame.data[2] & 255L) << 16) | ((frame.data[3] & 255L) << 24);
        if (length == 0 || length > MAX_CAST_CONFIG_BYTES) { fail("config-length-out-of-range"); return; }
        configBytes = new byte[(int) length];
        configPacketCount = (configBytes.length + 127) / 128;
        listener.onFrame(frame);
        requestConfigGroup();
    }

    private void requestConfigGroup() {
        configSmallIndex = 0;
        move("wait_config_data", "read-config-group-" + configGroup);
        send(NinebotEncryption2Codec.buildFrame(VERIFIED_TFT_TARGET, 0x83, configGroup, null), "config-data");
    }

    private void handleConfigData(NinebotEncryption2Codec.Frame frame) {
        int remaining = configBytes.length - configOffset;
        if (frame.sourceBoard != VERIFIED_TFT_TARGET || frame.cmd != 0x83
                || frame.index != configSmallIndex || frame.data.length == 0
                || frame.data.length > 128 || frame.data.length > remaining) {
            fail("unexpected-config-data-index-or-length");
            return;
        }
        System.arraycopy(frame.data, 0, configBytes, configOffset, frame.data.length);
        configOffset += frame.data.length;
        configSmallIndex++;
        configPacketIndex++;
        listener.onFrame(frame);
        if (configPacketIndex == configPacketCount) {
            if (configOffset != configBytes.length) { fail("config-final-size-mismatch"); return; }
            move("wait_config_verify", "read-raw-file-md5");
            send(NinebotEncryption2Codec.buildFrame(VERIFIED_TFT_TARGET, 0x84, 0, null), "config-verify");
        } else if (configSmallIndex == 8) {
            configGroup++;
            requestConfigGroup();
        }
    }

    private void handleConfigVerify(NinebotEncryption2Codec.Frame frame) {
        if (frame.sourceBoard != VERIFIED_TFT_TARGET || frame.cmd != 0x84
                || frame.index != 0 || frame.data.length != 16) {
            fail("unexpected-config-md5-response");
            return;
        }
        try {
            if (!MessageDigest.isEqual(MessageDigest.getInstance("MD5").digest(configBytes), frame.data)) {
                fail("config-md5-mismatch");
                return;
            }
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        listener.onFrame(frame);
        // Host string conversion removes at most one NUL. Preserve the complete
        // file here so it cannot accidentally be trimmed twice across layers.
        byte[] rawBytes = configBytes.clone();
        configBytes = null;
        move("authenticated", "config-md5-verified-json-needs-host-validation");
        listener.onCastConfig(rawBytes);
    }

    /** Modes: 0 stop, 1 simple navigation, 2 map navigation, 3 cruise. */
    public void setNaviMode(int tftTarget, int mode) {
        requireControlReady(tftTarget);
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("mode-range");
        expectedMode = mode;
        move("wait_mode", "set-navi-mode-" + mode);
        send(NinebotEncryption2Codec.buildFrame(tftTarget, 0x71, 0, new byte[] {(byte) mode}), "mode");
    }

    private void handleMode(NinebotEncryption2Codec.Frame frame) {
        if (frame.sourceBoard != VERIFIED_TFT_TARGET || frame.cmd != 0x71 || frame.index != 0) {
            fail("unexpected-mode-response");
            return;
        }
        int acknowledged = expectedMode;
        expectedMode = -1;
        listener.onFrame(frame);
        move("authenticated", "matching-mode-response-not-display-confirmation");
        listener.onModeAcknowledged(acknowledged);
    }

    private void requireControlReady(int target) {
        if (!authenticated || !"authenticated".equals(state)) throw new IllegalStateException("control-not-ready");
        if (target != VERIFIED_TFT_TARGET) throw new IllegalArgumentException("unverified-tft-target");
    }

    /** Host timeout is terminal; it cannot silently progress to a later stage. */
    public void onTimeout() { if (isWaiting()) fail("stage-timeout-" + state); }

    public void close() {
        if ("closed".equals(state)) return;
        authenticated = false;
        accumulator.reset();
        discardSecrets();
        Arrays.fill(name, (byte) 0);
        move("closed", "session-closed");
    }

    private boolean isWaiting() {
        return state.startsWith("wait_");
    }

    private void send(byte[] plaintext, String stage) { listener.onSend(crypto.encrypt(plaintext), stage); }
    private void move(String stage, String detail) { state = stage; listener.onState(stage, detail); }
    private void fail(String detail) {
        authenticated = false;
        accumulator.reset();
        discardSecrets();
        move("failed", detail);
    }

    private void discardSecrets() {
        if (password != null) Arrays.fill(password, (byte) 0);
        if (auth != null) Arrays.fill(auth, (byte) 0);
        if (serial != null) Arrays.fill(serial, (byte) 0);
        password = auth = serial = null;
        configBytes = null;
        configOffset = configGroup = configSmallIndex = configPacketIndex = configPacketCount = 0;
        expectedMode = -1;
        crypto = null;
    }
}
