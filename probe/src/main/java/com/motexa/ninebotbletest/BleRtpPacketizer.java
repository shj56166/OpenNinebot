package com.motexa.ninebotbletest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Official Java BLE PT0 layout; input is raw MPEG2 packet data, not an MPEG container. */
public final class BleRtpPacketizer {
    private final int ssrc;
    private int sequence;

    public BleRtpPacketizer(int ssrc, int firstSequence) {
        this.ssrc = ssrc;
        sequence = firstSequence & 0xffff;
    }

    public List<byte[]> packetize(byte[] frame, boolean keyframe, int actualAttMtu, long wallClockMillis) {
        if (frame == null || frame.length == 0) throw new IllegalArgumentException("empty_frame");
        if (actualAttMtu <= 30 || actualAttMtu > 517) throw new IllegalArgumentException("media_mtu_unusable");
        int payloadSize = actualAttMtu - 30;
        List<byte[]> packets = new ArrayList<>();
        for (int start = 0; start < frame.length; start += payloadSize) {
            int size = Math.min(payloadSize, frame.length - start);
            boolean last = start + size == frame.length;
            ByteBuffer packet = ByteBuffer.allocate(20 + size).order(ByteOrder.BIG_ENDIAN);
            packet.put((byte) 0x90).put((byte) (last ? 0x80 : 0x00));
            packet.putShort((short) sequence++).putInt((int) wallClockMillis).putInt(ssrc);
            packet.putInt(1).putInt(keyframe ? 0x00010000 : 0x00020000);
            packet.put(frame, start, size);
            packets.add(packet.array());
        }
        return packets;
    }
}
