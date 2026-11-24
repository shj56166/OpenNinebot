package org.openninebot.reference.app;

import java.util.UUID;

/**
 * Explicitly supported Ninebot Encryption2 connection variants.  Encryption3
 * and legacy variants are deliberately rejected until their framing and auth
 * implementations have been verified on BLE hardware.
 */
public final class NinebotVehicleProfile {
    public static final String DEFAULT_ID = "encryption2_standard_auto";
    public static final String ENCRYPTION2_STANDARD_AUTO = "encryption2_standard_auto";
    public static final String ENCRYPTION2_STANDARD_NUS = "encryption2_standard_nus";
    public static final String ENCRYPTION2_STANDARD_CUSTOM = "encryption2_standard_custom";
    public static final String ENCRYPTION2_CUSTOM_FIRST_AUTO = "encryption2_custom_first_auto";
    public static final String ENCRYPTION2_STANDARD_NUS_IMMEDIATE = "encryption2_standard_nus_immediate";
    public static final String ENCRYPTION2_VCU_NUS_SETTLE = "encryption2_vcu_nus_settle";
    public static final String ENCRYPTION2_MMAX90_CAPTURE_NUS = "encryption2_mmax90_capture_nus";

    public enum ChannelPreference { AUTO_NUS_FIRST, AUTO_CUSTOM_FIRST, NUS, CUSTOM }

    public static final UUID NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID NUS_WRITE_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID NUS_NOTIFY_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID CUSTOM_SERVICE_UUID = UUID.fromString("6e400001-0000-0000-006e-696e65626f74");
    public static final UUID CUSTOM_WRITE_UUID = UUID.fromString("6e400002-0000-0000-006e-696e65626f74");
    public static final UUID CUSTOM_NOTIFY_UUID = UUID.fromString("6e400004-0000-0000-006e-696e65626f74");

    public final String id;
    public final ChannelPreference channelPreference;
    public final int preCommTarget;
    public final int setPwdTarget;
    public final int authTarget;
    public final long settleDelayMs;

    private NinebotVehicleProfile(
        String id,
        ChannelPreference channelPreference,
        int preCommTarget,
        int setPwdTarget,
        int authTarget,
        long settleDelayMs
    ) {
        this.id = id;
        this.channelPreference = channelPreference;
        this.preCommTarget = preCommTarget;
        this.setPwdTarget = setPwdTarget;
        this.authTarget = authTarget;
        this.settleDelayMs = settleDelayMs;
    }

    public static NinebotVehicleProfile resolve(String requestedId) {
        String id = requestedId == null || requestedId.trim().isEmpty() ? DEFAULT_ID : requestedId.trim();
        switch (id) {
            case ENCRYPTION2_STANDARD_AUTO:
                return new NinebotVehicleProfile(id, ChannelPreference.AUTO_NUS_FIRST, 0x04, 0x04, 0x04, 300L);
            case ENCRYPTION2_STANDARD_NUS:
                return new NinebotVehicleProfile(id, ChannelPreference.NUS, 0x04, 0x04, 0x04, 300L);
            case ENCRYPTION2_STANDARD_CUSTOM:
                return new NinebotVehicleProfile(id, ChannelPreference.CUSTOM, 0x04, 0x04, 0x04, 300L);
            case ENCRYPTION2_CUSTOM_FIRST_AUTO:
                return new NinebotVehicleProfile(id, ChannelPreference.AUTO_CUSTOM_FIRST, 0x04, 0x04, 0x04, 300L);
            case ENCRYPTION2_STANDARD_NUS_IMMEDIATE:
                return new NinebotVehicleProfile(id, ChannelPreference.NUS, 0x04, 0x04, 0x04, 0L);
            case ENCRYPTION2_VCU_NUS_SETTLE:
                return new NinebotVehicleProfile(id, ChannelPreference.NUS, 0x09, 0x04, 0x09, 300L);
            case ENCRYPTION2_MMAX90_CAPTURE_NUS:
                return new NinebotVehicleProfile(id, ChannelPreference.NUS, 0x09, 0x04, 0x09, 0L);
            default:
                return null;
        }
    }

    public static boolean isImplemented(String requestedId) {
        return resolve(requestedId) != null;
    }
}
