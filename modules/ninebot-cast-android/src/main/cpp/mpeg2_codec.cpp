#include <jni.h>
#include <cstdint>
#include <memory>
#include <mutex>
#include <map>
#include <vector>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>

}
namespace {
struct Encoder {
    AVCodecContext* codec=nullptr; AVFrame* frame=nullptr; AVPacket* packet=nullptr; SwsContext* scale=nullptr;
    int width=0,height=0;
    int64_t nextFrame=0;std::map<int64_t,int64_t> captureTimes;
    ~Encoder(){sws_freeContext(scale);av_packet_free(&packet);av_frame_free(&frame);avcodec_free_context(&codec);}
};
std::mutex mutex;std::map<jlong,std::unique_ptr<Encoder>> encoders;jlong next=1;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_motexa_vehiclecast_Mpeg2Codec_create(JNIEnv*,jclass,jint width,jint height,jint fps,jint bitrate,jint gop){
    if(width<16||height<16||width>4096||height>4096||(width&1)||(height&1)||fps<1||fps>60||bitrate<1||bitrate>8000000||gop<1||gop>300)return 0;
    auto e=std::make_unique<Encoder>();e->width=width;e->height=height;
    const AVCodec* codec=avcodec_find_encoder(AV_CODEC_ID_MPEG2VIDEO);if(!codec)return 0;
    e->codec=avcodec_alloc_context3(codec);e->frame=av_frame_alloc();e->packet=av_packet_alloc();if(!e->codec||!e->frame||!e->packet)return 0;
    e->codec->width=width;e->codec->height=height;e->codec->pix_fmt=AV_PIX_FMT_YUV420P;e->codec->time_base={1,fps};e->codec->framerate={fps,1};e->codec->bit_rate=bitrate;
    e->codec->gop_size=gop;e->codec->max_b_frames=0;e->codec->thread_count=1;e->codec->flags|=AV_CODEC_FLAG_LOW_DELAY;
    e->codec->strict_std_compliance=FF_COMPLIANCE_UNOFFICIAL;
    if(avcodec_open2(e->codec,codec,nullptr)<0)return 0;
    e->frame->width=width;e->frame->height=height;e->frame->format=AV_PIX_FMT_YUV420P;
    if(av_frame_get_buffer(e->frame,32)<0)return 0;
    e->scale=sws_getContext(width,height,AV_PIX_FMT_RGBA,width,height,AV_PIX_FMT_YUV420P,SWS_FAST_BILINEAR,nullptr,nullptr,nullptr);if(!e->scale)return 0;
    std::lock_guard<std::mutex> guard(mutex);jlong handle=next++;encoders.emplace(handle,std::move(e));return handle;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_motexa_vehiclecast_Mpeg2Codec_encodeRgba(JNIEnv* env,jclass,jlong handle,jobject buffer,jint rowStride,jint pixelStride,jlong pts){
    std::lock_guard<std::mutex> guard(mutex);auto i=encoders.find(handle);if(i==encoders.end())return nullptr;auto& e=*i->second;
    auto* pixels=static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));jlong capacity=env->GetDirectBufferCapacity(buffer);
    if(!pixels||pixelStride!=4||rowStride<e.width*4||capacity<int64_t(rowStride)*(e.height-1)+e.width*4||av_frame_make_writable(e.frame)<0)return nullptr;
    const uint8_t* inputs[]={pixels,nullptr,nullptr,nullptr};int lines[]={rowStride,0,0,0};
    if(sws_scale(e.scale,inputs,lines,0,e.height,e.frame->data,e.frame->linesize)!=e.height)return nullptr;
    if(e.captureTimes.size()>512)return nullptr;
    e.frame->pts=e.nextFrame++;e.captureTimes.emplace(e.frame->pts,pts);if(avcodec_send_frame(e.codec,e.frame)<0)return nullptr;
    std::vector<uint8_t> bytes;int status;
    while((status=avcodec_receive_packet(e.codec,e.packet))>=0){
        if(!bytes.empty())return nullptr;
        auto capture=e.captureTimes.find(e.packet->pts);if(capture==e.captureTimes.end())return nullptr;
        uint64_t timestamp=static_cast<uint64_t>(capture->second);e.captureTimes.erase(capture);
        for(int shift=56;shift>=0;shift-=8)bytes.push_back(static_cast<uint8_t>(timestamp>>shift));
        bytes.insert(bytes.end(),e.packet->data,e.packet->data+e.packet->size);av_packet_unref(e.packet);
    }
    if(status!=AVERROR(EAGAIN)&&status!=AVERROR_EOF)return nullptr;
    jbyteArray result=env->NewByteArray(static_cast<jsize>(bytes.size()));if(result&&!bytes.empty())env->SetByteArrayRegion(result,0,static_cast<jsize>(bytes.size()),reinterpret_cast<const jbyte*>(bytes.data()));return result;
}
extern "C" JNIEXPORT void JNICALL Java_com_motexa_vehiclecast_Mpeg2Codec_destroy(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> guard(mutex);encoders.erase(handle);}
