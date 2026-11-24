package com.motexa.ninebotbletest;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BleRtpPacketizerTest {
    @Test public void matchesOriginalBleSessionVector() {
        byte[] packet = new BleRtpPacketizer(0x11223344, 0x1234)
                .packetize(NinebotAdvertisementTest.hex("000001657f"), true, 247, 0x01020304L).get(0);
        assertArrayEquals(NinebotAdvertisementTest.hex("9080123401020304112233440000000100010000000001657f"), packet);
    }
    @Test public void actualAtt247Uses217BytesAndPreservesFrame() {
        byte[] frame = new byte[1000];
        for (int i = 0; i < frame.length; i++) frame[i] = (byte) i;
        List<byte[]> packets = new BleRtpPacketizer(1, 0xfffe).packetize(frame, false, 247, 10);
        ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
        int sequence = 0xfffe;
        for (int i = 0; i < packets.size(); i++) {
            byte[] packet = packets.get(i);
            assertTrue(packet.length <= 237);
            assertEquals(sequence++ & 0xffff, ByteBuffer.wrap(packet).getShort(2) & 0xffff);
            assertEquals(i == packets.size() - 1 ? 0x80 : 0, packet[1] & 255);
            rebuilt.write(packet, 20, packet.length - 20);
        }
        assertArrayEquals(frame, rebuilt.toByteArray());
        assertEquals(237, packets.get(0).length);
    }
    @Test public void refusesUnnegotiatedMediaMtu() {
        assertThrows(IllegalArgumentException.class, () -> new BleRtpPacketizer(1, 1).packetize(new byte[]{1}, true, 23, 0));
    }
}
