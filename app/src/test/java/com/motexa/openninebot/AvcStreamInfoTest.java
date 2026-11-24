package com.motexa.openninebot;

import org.junit.Test;
import static org.junit.Assert.*;

public class AvcStreamInfoTest {
    private byte[] hex(String value){byte[] b=new byte[value.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return b;}
    @Test public void ffmpegBaselineCroppedSevenInchDimensions(){
        // FFmpeg/libx264 baseline, 1024x600 at 5 fps; SPS stores 608 then crops.
        byte[] bytes=hex("000000016742c01fd9004004df970110000003001000000300a0f18324800000000168cb83cb20");
        AvcStreamInfo info=AvcStreamInfo.parse(bytes);assertEquals(1024,info.width);assertEquals(600,info.height);assertEquals(66,info.profile);assertEquals(31,info.level);
        AvcStreamInfo.validatePps(bytes);
    }
    @Test public void sliceTypeEvidenceDetectsB(){
        assertTrue(AvcStreamInfo.hasBSlice(hex("00000161a0"))); // ue(0), ue(1)
        assertFalse(AvcStreamInfo.hasBSlice(hex("00000161e0"))); // ue(0), ue(0)
    }
    @Test(expected=IllegalArgumentException.class) public void truncatedSpsRejected(){AvcStreamInfo.parse(hex("0000016742c0"));}
    @Test(expected=IllegalArgumentException.class) public void ppsMissingRejected(){AvcStreamInfo.validatePps(hex("00000109f0"));}
}
