package com.motexa.ninebotbletest;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CastConfigurationTest {
    @Test public void routePriorityAndDimensionsMatchOfficialSelection() throws Exception {
        CastConfiguration config = new CastConfiguration(("{\"rtpChannel\":\"2\",\"channel\":\"0\",\"codecType\":\"1\","
                + "\"codecWidth\":480,\"codecHeight\":800,\"dimensionWidth\":240,\"dimensionHeight\":320}\0").getBytes(StandardCharsets.UTF_8));
        assertEquals(2, config.channel); assertEquals(1, config.codec);
        assertEquals(480, config.width); assertEquals(800, config.height);
    }
    @Test public void doesNotInventInstrumentDimensions() throws Exception {
        CastConfiguration config = new CastConfiguration("{\"codecType\":\"0\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, config.width); assertEquals(0, config.height);
        CastConfiguration invalid = new CastConfiguration("{\"codecWidth\":0,\"dimensionWidth\":240}".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, invalid.width);
    }
    @Test public void appliesOfficialConfigMajorVersionGate() throws Exception {
        String[] rejected = {"6", "6.0", "+6.1"};
        String[] accepted = {"5.9", "", "unknown", " 6.0", "9999999999999999.0"};
        for (String version : rejected)
            assertFalse(new CastConfiguration(("{\"version\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8)).supportedVersion);
        for (String version : accepted)
            assertTrue(new CastConfiguration(("{\"version\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8)).supportedVersion);
    }
    @Test public void codecNameFallbackUsesExactOfficialNames() throws Exception {
        assertEquals(0, new CastConfiguration("{\"codecName\":\"MPEG2\"}".getBytes(StandardCharsets.UTF_8)).codec);
        assertEquals(-1, new CastConfiguration("{\"codecName\":\"video/mpeg2\"}".getBytes(StandardCharsets.UTF_8)).codec);
        assertEquals(-1, new CastConfiguration("{\"codecName\":\"h264-extra\"}".getBytes(StandardCharsets.UTF_8)).codec);
    }
    @Test public void fixtureIsRealMpeg2AndOnlyMatchesItsVerifiedRoute() throws Exception {
        Mpeg2ProbeClip clip = new Mpeg2ProbeClip(getClass().getResourceAsStream("/probe-240x320.json"));
        assertEquals(240, clip.width); assertEquals(320, clip.height);
        assertEquals(10, clip.frames.size()); assertTrue(clip.keys.get(0));
        CastConfiguration right = new CastConfiguration("{\"codecType\":\"0\",\"channel\":\"0\",\"codecWidth\":240,\"codecHeight\":320}".getBytes(StandardCharsets.UTF_8));
        CastConfiguration wrong = new CastConfiguration("{\"codecType\":\"0\",\"channel\":\"1\",\"codecWidth\":240,\"codecHeight\":320}".getBytes(StandardCharsets.UTF_8));
        assertTrue(right.accepts(clip)); assertFalse(wrong.accepts(clip));
    }
}
