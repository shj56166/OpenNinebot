package com.motexa.openninebot;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.json.JSONObject;

/** Reads actual H.264 syntax; codec configuration requests are not stream evidence. */
final class AvcStreamInfo {
    final int profile, level, width, height, maxReferenceFrames;
    private AvcStreamInfo(int profile,int level,int width,int height,int refs){
        this.profile=profile;this.level=level;this.width=width;this.height=height;maxReferenceFrames=refs;
    }
    JSONObject json(){JSONObject v=new JSONObject();try{v.put("profileIdc",profile).put("levelIdc",level).put("width",width).put("height",height).put("maxReferenceFrames",maxReferenceFrames);}catch(Exception ignored){}return v;}
    static AvcStreamInfo parse(byte[] annexB){
        for(byte[] nal:nals(annexB))if((nal[0]&31)==7)return sps(nal);
        throw new IllegalArgumentException("h264_sps_missing");
    }
    static boolean hasBSlice(byte[] annexB){
        for(byte[] nal:nals(annexB)){int type=nal[0]&31;if(type==1||type==5){Bits b=rbsp(nal);b.ue();if(b.ue()%5==1)return true;}}
        return false;
    }
    static void validatePps(byte[] annexB){
        for(byte[] nal:nals(annexB))if((nal[0]&31)==8){Bits b=rbsp(nal);if(b.ue()>255||b.ue()>31)throw new IllegalArgumentException("h264_pps_invalid");return;}
        throw new IllegalArgumentException("h264_pps_missing");
    }
    private static AvcStreamInfo sps(byte[] nal){
        Bits b=rbsp(nal);int profile=b.read(8);b.read(8);int level=b.read(8);if(b.ue()>31)throw new IllegalArgumentException("h264_sps_id");
        int chroma=1;boolean separate=false;
        if(Arrays.asList(100,110,122,244,44,83,86,118,128,138,139,134,135).contains(profile)){
            chroma=b.ue();if(chroma>3)throw new IllegalArgumentException("h264_chroma");if(chroma==3)separate=b.bit();
            if(b.ue()>6||b.ue()>6)throw new IllegalArgumentException("h264_bit_depth");b.bit();
            if(b.bit())for(int i=0;i<(chroma==3?12:8);i++)if(b.bit()){
                int last=8,next=8;for(int j=0;j<(i<6?16:64);j++){if(next!=0)next=(last+b.se()+256)%256;if(next!=0)last=next;}
            }
        }
        if(b.ue()>12)throw new IllegalArgumentException("h264_frame_num");int poc=b.ue();
        if(poc==0){if(b.ue()>12)throw new IllegalArgumentException("h264_poc");}
        else if(poc==1){b.bit();b.se();b.se();int count=b.ue();if(count>255)throw new IllegalArgumentException("h264_poc_cycle");for(int i=0;i<count;i++)b.se();}
        else if(poc!=2)throw new IllegalArgumentException("h264_poc_type");
        int refs=b.ue();b.bit();int mbw=b.ue()+1,mbh=b.ue()+1;boolean frameOnly=b.bit();if(!frameOnly)b.bit();b.bit();
        int left=0,right=0,top=0,bottom=0;if(b.bit()){left=b.ue();right=b.ue();top=b.ue();bottom=b.ue();}
        int arrayType=separate?0:chroma;int cropX=arrayType==0||arrayType==3?1:2;
        int cropY=(arrayType==1?2:1)*(frameOnly?1:2);
        long width=mbw*16L-(left+(long)right)*cropX,height=mbh*16L*(frameOnly?1:2)-(top+(long)bottom)*cropY;
        if(width<16||height<16||width>4096||height>4096)throw new IllegalArgumentException("h264_dimensions");
        return new AvcStreamInfo(profile,level,(int)width,(int)height,refs);
    }
    private static Bits rbsp(byte[] nal){
        ByteArrayOutputStream out=new ByteArrayOutputStream();int zeros=0;
        for(int i=1;i<nal.length;i++){int v=nal[i]&255;if(zeros>=2&&v==3){if(i+1>=nal.length||(nal[i+1]&255)>3)throw new IllegalArgumentException("h264_emulation_prevention");zeros=0;continue;}out.write(v);zeros=v==0?zeros+1:0;}
        return new Bits(out.toByteArray());
    }
    private static java.util.List<byte[]> nals(byte[] data){
        java.util.List<byte[]> result=new java.util.ArrayList<>();int from=-1;
        for(int i=0;i+2<data.length;i++)if(data[i]==0&&data[i+1]==0&&(data[i+2]==1||(i+3<data.length&&data[i+2]==0&&data[i+3]==1))){
            if(from>=0&&i>from)result.add(Arrays.copyOfRange(data,from,i));int len=data[i+2]==1?3:4;from=i+len;i=from-1;
        }
        if(from>=0&&from<data.length)result.add(Arrays.copyOfRange(data,from,data.length));return result;
    }
    private static final class Bits {
        final byte[] bytes;int position;
        Bits(byte[] bytes){this.bytes=bytes;}
        boolean bit(){return read(1)==1;}
        int read(int count){if(count<0||count>31||position+count>bytes.length*8)throw new IllegalArgumentException("h264_truncated");int v=0;for(int i=0;i<count;i++)v=(v<<1)|((bytes[position>>3]>>(7-(position++&7)))&1);return v;}
        int ue(){int zero=0;while(!bit())if(++zero>24)throw new IllegalArgumentException("h264_exp_golomb");return (1<<zero)-1+read(zero);}
        int se(){int v=ue();return (v&1)==1?(v+1)/2:-v/2;}
    }
}
