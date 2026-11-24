package com.motexa.openninebot;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class AvcAnnexB {
    private AvcAnnexB() {}

    static byte[] convert(byte[] sample) {
        if (sample == null || sample.length == 0) {
            return new byte[0];
        }
        if (hasStartCode(sample, 0)) {
            return sample;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(sample.length + 32);
        int offset = 0;
        while (offset + 4 <= sample.length) {
            int length = ByteBuffer.wrap(sample, offset, 4)
                .order(ByteOrder.BIG_ENDIAN).getInt();
            offset += 4;
            if (length <= 0 || length > sample.length - offset) {
                return sample;
            }
            output.write(0);
            output.write(0);
            output.write(0);
            output.write(1);
            output.write(sample, offset, length);
            offset += length;
        }
        return offset == sample.length ? output.toByteArray() : sample;
    }

    static byte[] joinConfig(ByteBuffer sps, ByteBuffer pps) {
        if (sps == null && pps == null) {
            return new byte[0];
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        append(output, sps);
        append(output, pps);
        return output.toByteArray();
    }

    /** Adds an H.264 access-unit delimiter for receivers that require one. */
    static byte[] ensureAud(byte[] accessUnit) {
        if (accessUnit == null || accessUnit.length == 0
            || startsWithNalType(accessUnit, 9)) {
            return accessUnit == null ? new byte[0] : accessUnit;
        }
        byte[] aud = new byte[] {0, 0, 0, 1, 0x09, (byte) 0xf0};
        byte[] result = new byte[aud.length + accessUnit.length];
        System.arraycopy(aud, 0, result, 0, aud.length);
        System.arraycopy(accessUnit, 0, result, aud.length, accessUnit.length);
        return result;
    }

    static boolean hasNalType(byte[] data, int type) {
        if (data == null || data.length == 0) {
            return false;
        }
        int start = findStartCode(data, 0);
        while (start >= 0) {
            int codeLength = startCodeLength(data, start);
            int nalOffset = start + codeLength;
            if (nalOffset < data.length && (data[nalOffset] & 0x1f) == type) {
                return true;
            }
            start = findStartCode(data, nalOffset);
        }
        return false;
    }

    /** Extracts SPS/PPS NAL units while retaining Annex-B start codes. */
    static byte[] extractParameterSets(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int start = findStartCode(data, 0);
        while (start >= 0) {
            int codeLength = startCodeLength(data, start);
            int nalOffset = start + codeLength;
            int next = findStartCode(data, nalOffset);
            int end = next >= 0 ? next : data.length;
            if (nalOffset < end && ((data[nalOffset] & 0x1f) == 7
                || (data[nalOffset] & 0x1f) == 8)) {
                output.write(0);
                output.write(0);
                output.write(0);
                output.write(1);
                output.write(data, nalOffset, end - nalOffset);
            }
            start = next;
        }
        return output.toByteArray();
    }

    private static void append(ByteArrayOutputStream output, ByteBuffer source) {
        if (source == null) {
            return;
        }
        ByteBuffer copy = source.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        byte[] converted = convert(bytes);
        output.write(converted, 0, converted.length);
    }

    private static boolean hasStartCode(byte[] data, int offset) {
        return data.length - offset >= 4
            && data[offset] == 0 && data[offset + 1] == 0
            && ((data[offset + 2] == 0 && data[offset + 3] == 1)
                || (data[offset + 2] == 1));
    }

    private static boolean startsWithNalType(byte[] data, int type) {
        if (!hasStartCode(data, 0)) {
            return false;
        }
        int offset = startCodeLength(data, 0);
        return offset < data.length && (data[offset] & 0x1f) == type;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i;
            }
            if (i + 3 < data.length && data[i] == 0 && data[i + 1] == 0
                && data[i + 2] == 0 && data[i + 3] == 1) {
                return i;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] data, int offset) {
        return offset + 4 <= data.length && data[offset] == 0 && data[offset + 1] == 0
            && data[offset + 2] == 0 && data[offset + 3] == 1 ? 4 : 3;
    }
}
