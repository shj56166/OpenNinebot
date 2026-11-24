package com.motexa.vehiclecast;

import java.nio.ByteBuffer;

/** Minimal FFmpeg AVPacket encoder. No container or alternate codec is enabled. */
public final class Mpeg2Codec implements AutoCloseable {
    static { NativeRuntime.ensureLoaded(); }
    private long handle;
    public Mpeg2Codec(int width,int height,int fps,int bitrate,int gop){
        handle=create(width,height,fps,bitrate,gop);if(handle==0)throw new IllegalStateException("mpeg2_encoder_initialization_failed");
    }
    public static final class Packet {
        public final byte[] bytes;
        public final long presentationTimeUs;
        Packet(byte[] bytes,long pts){this.bytes=bytes;presentationTimeUs=pts;}
    }
    public synchronized Packet encode(ByteBuffer rgba,int rowStride,int pixelStride,long presentationTimeUs){
        if(handle==0)throw new IllegalStateException("mpeg2_encoder_closed");
        byte[] result=encodeRgba(handle,rgba,rowStride,pixelStride,presentationTimeUs);if(result==null)throw new IllegalStateException("mpeg2_encode_failed");
        if(result.length==0)return null;if(result.length<8)throw new IllegalStateException("mpeg2_packet_invalid");
        return new Packet(java.util.Arrays.copyOfRange(result,8,result.length),ByteBuffer.wrap(result).getLong());
    }
    public synchronized void close(){if(handle!=0){destroy(handle);handle=0;}}
    private static native long create(int width,int height,int fps,int bitrate,int gop);
    private static native byte[] encodeRgba(long handle,ByteBuffer pixels,int rowStride,int pixelStride,long presentationTimeUs);
    private static native void destroy(long handle);
}
