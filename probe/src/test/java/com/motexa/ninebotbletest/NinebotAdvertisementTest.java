package com.motexa.ninebotbletest;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NinebotAdvertisementTest {
    @Test public void matchesOfficialRuntimeVectors() {
        String[] vectors = {"07ff4e42430300b9", "07ff4e43010203f9", "08ff1f0f03020102f7", "0dff1f0f03020102245445535493"};
        for (String vector : vectors) {
            NinebotAdvertisement result = NinebotAdvertisement.parse(hex(vector));
            assertTrue(result.valid);
            assertEquals(3, result.authVersion);
        }
        assertFalse(NinebotAdvertisement.parse(hex("07ff4e4243030000")).valid);
    }
    @Test public void rejectsConflictingOrTruncatedEnvelopes() {
        assertFalse(NinebotAdvertisement.parse(hex("07ff4e42430300b907ff4e42430200ba")).valid);
        assertFalse(NinebotAdvertisement.parse(hex("07ff4e4243")).valid);
        assertFalse(NinebotAdvertisement.parse(hex("07ff1f0f0305ff" )).valid);
        assertFalse(NinebotAdvertisement.parse(new byte[0]).valid);
    }
    static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
