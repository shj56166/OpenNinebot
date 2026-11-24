package com.motexa.openninebot;

import android.media.MediaCodecInfo;
import org.junit.Test;
import static org.junit.Assert.*;

public class EncoderConfigurationTest {
    @Test public void choosesLevelForNegotiatedMacroblocksInsteadOfFixed31(){
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel31,CastEncoder.avcLevel(848,480,20));
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel31,CastEncoder.avcLevel(1024,600,25));
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel4,CastEncoder.avcLevel(2048,960,30));
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel5,CastEncoder.avcLevel(2560,1440,30));
    }
    @Test public void coversUhdRateAndPictureDimensionLimits(){
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel51,CastEncoder.avcLevel(4096,2160,28));
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel52,CastEncoder.avcLevel(4096,2160,29));
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel6,CastEncoder.avcLevel(4096,4096,60));
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel4,CastEncoder.avcLevel(4096,16,30));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsUnsupportedLevelInputs(){
        CastEncoder.avcLevel(Integer.MAX_VALUE,4096,60);
    }
}
