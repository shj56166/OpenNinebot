package org.openninebot.reference.app;

import java.util.Arrays;

/** Pure Ninebot read plan, response matching and telemetry value decoding. */
public final class NinebotTelemetryCodec {
    public static final int BOARD_DISPLAY = 0x01;
    public static final int BOARD_BMS_DEFAULT = 0x22;

    public static final int REG_RANGE_LEFT = 0x25;
    public static final int REG_SPEED = 0x26;
    public static final int REG_NOS = 0x5E;
    public static final int REG_BATTERY = 0xB5;
    public static final int REG_MILEAGE = 0xB7;
    public static final int REG_TRIP_MILEAGE = 0xB9;
    public static final int REG_RUNNING_TIME = 0xBA;
    public static final int REG_POWER = 0xBD;
    public static final int REG_BMS_VOLTAGE = 0x34;
    public static final int REG_BMS_SOC = 0x32;
    public static final int REG_BMS_TEMPERATURES = 0x40;

    private static final int NOS_RAW_EMPTY = 256;
    private static final int NOS_PERCENT_MAX = 100;
    private static final ReadSpec[] STANDARD_READS = new ReadSpec[] {
        new ReadSpec(REG_SPEED, 2, 500L),
        new ReadSpec(REG_POWER, 2, 250L),
        new ReadSpec(REG_NOS, 2, 500L),
        new ReadSpec(REG_RUNNING_TIME, 2, 1_000L),
        new ReadSpec(REG_BATTERY, 2, 2_500L),
        new ReadSpec(REG_RANGE_LEFT, 2, 2_500L),
        new ReadSpec(REG_TRIP_MILEAGE, 2, 2_500L),
        new ReadSpec(REG_MILEAGE, 4, 5_000L)
    };

    private NinebotTelemetryCodec() {}

    public static ReadSpec[] standardReads() {
        return Arrays.copyOf(STANDARD_READS, STANDARD_READS.length);
    }

    public static long intervalMsForRegister(int register, boolean fastMode) {
        for (ReadSpec read : STANDARD_READS) {
            if (read.register == register) return read.intervalMs(fastMode);
        }
        return -1L;
    }

    public static boolean matchesReadResponse(int expectedTarget, int expectedRegister, int sourceBoard, int register) {
        return expectedTarget >= 0 && expectedRegister >= 0 && sourceBoard == expectedTarget && register == expectedRegister;
    }

    public static TelemetryValue decode(int register, byte[] data) {
        if (data == null) return null;
        if (register == REG_SPEED && data.length >= 2) {
            return TelemetryValue.decimal(Field.SPEED_KMH, u16le(data, 0) * 0.1d);
        }
        if (register == REG_POWER && data.length >= 2) {
            return TelemetryValue.decimal(Field.POWER_W, i16le(data, 0));
        }
        if (register == REG_NOS && data.length >= 2) {
            return TelemetryValue.integer(Field.NOS_RAW, normalizeNosRaw(u16le(data, 0)));
        }
        if (register == REG_BATTERY && data.length >= 1) {
            return TelemetryValue.integer(Field.BATTERY_RAW, data[0] & 0xFF);
        }
        if (register == REG_RANGE_LEFT && data.length >= 2) {
            return TelemetryValue.decimal(Field.RANGE_LEFT_KM, u16le(data, 0) * 0.1d);
        }
        if (register == REG_MILEAGE && data.length >= 4) {
            return TelemetryValue.decimal(Field.MILEAGE_KM, u32le(data, 0) * 0.001d);
        }
        if (register == REG_TRIP_MILEAGE && data.length >= 2) {
            // Captured MMAX90 data uses 0.1 km for this field.
            return TelemetryValue.decimal(Field.TRIP_MILEAGE_KM, u16le(data, 0) * 0.1d);
        }
        if (register == REG_RUNNING_TIME && data.length >= 2) {
            return TelemetryValue.integer(Field.RUNNING_TIME_SEC, u16le(data, 0));
        }
        return null;
    }

    public static int normalizeNosRaw(int rawValue) {
        return Math.max(0, Math.min(NOS_PERCENT_MAX, rawValue - NOS_RAW_EMPTY));
    }

    public static boolean isPlausibleBmsVoltageRaw(int rawValue) {
        return rawValue >= 3_000 && rawValue <= 8_000;
    }

    public static double[] decodeMmax90TemperatureValues(byte[] data) {
        if (data == null || data.length != 20) return null;
        double[] values = new double[data.length / 2];
        for (int index = 0; index < values.length; index += 1) {
            int raw = u16le(data, index * 2);
            if (raw < 2_000 || raw > 7_000) return null;
            values[index] = raw / 100d;
        }
        return values;
    }

    private static int u16le(byte[] value, int offset) {
        return (value[offset] & 0xFF) | ((value[offset + 1] & 0xFF) << 8);
    }

    private static int i16le(byte[] value, int offset) {
        int raw = u16le(value, offset);
        return raw > 0x7FFF ? raw - 0x10000 : raw;
    }

    private static long u32le(byte[] value, int offset) {
        return ((long) value[offset] & 0xFF) |
            (((long) value[offset + 1] & 0xFF) << 8) |
            (((long) value[offset + 2] & 0xFF) << 16) |
            (((long) value[offset + 3] & 0xFF) << 24);
    }

    public enum Field {
        SPEED_KMH,
        POWER_W,
        NOS_RAW,
        BATTERY_RAW,
        RANGE_LEFT_KM,
        MILEAGE_KM,
        TRIP_MILEAGE_KM,
        RUNNING_TIME_SEC
    }

    public static final class ReadSpec {
        public final int target;
        public final int register;
        public final int readLength;
        public final long fastIntervalMs;

        private ReadSpec(int register, int readLength, long fastIntervalMs) {
            this.target = BOARD_DISPLAY;
            this.register = register;
            this.readLength = readLength;
            this.fastIntervalMs = fastIntervalMs;
        }

        public long intervalMs(boolean fastMode) {
            return fastMode ? fastIntervalMs : Math.max(500L, fastIntervalMs * 2L);
        }
    }

    public static final class TelemetryValue {
        public final Field field;
        public final Double decimalValue;
        public final Integer integerValue;

        private TelemetryValue(Field field, Double decimalValue, Integer integerValue) {
            this.field = field;
            this.decimalValue = decimalValue;
            this.integerValue = integerValue;
        }

        private static TelemetryValue decimal(Field field, double value) {
            return new TelemetryValue(field, value, null);
        }

        private static TelemetryValue integer(Field field, int value) {
            return new TelemetryValue(field, null, value);
        }
    }
}
