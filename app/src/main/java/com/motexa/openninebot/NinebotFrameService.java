package com.motexa.openninebot;

import android.app.Service;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.*;
import android.os.*;
import android.view.Surface;
import com.motexa.vehiclecast.Mpeg2Codec;
import org.json.JSONObject;

/** Process-owned capture; protocol transport remains in the controller. */
public final class NinebotFrameService extends Service {
    private LabRuntime runtime;
    private MediaProjection projection;
    private CastVideoInput input;
    private CastVisualFeed.Source visual;
    private CastEncoder h264;
    private ImageReader reader;
    private HandlerThread frameThread;
    private Mpeg2Codec mpeg2;
    private CastSessionPower sessionPower;
    private boolean mirror;
    private volatile boolean capturedVisible = true;
    private FrameOutputWatchdog.State outputState;
    private volatile boolean running;
    private Thread drain;
    private volatile long frameIndex;
    private long lastImagePts;
    private long generation=-1;
    private volatile long lastEncodedAt;
    private volatile boolean outputObserved;
    private volatile String stopReason="service_stop";
    private long sourceStartedAtMs,lastSummaryAtMs,lastProcessCpuMs,releaseStartedAtMs;
    private CastMetrics metrics;
    private final String streamId=java.util.UUID.randomUUID().toString().replace("-","");
    long generation(){return generation;}
    static void start(Context context,Intent consent,int result,JSONObject configuration){
        Intent intent=new Intent(context,NinebotFrameService.class).putExtra("config",configuration==null?"{}":configuration.toString()).putExtra("code",result);
        if(consent!=null)intent.putExtra("data",consent);
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);else context.startService(intent);
    }
    public int onStartCommand(Intent intent,int flags,int startId){
        runtime=LabRuntime.get(this);
        if(intent==null||!runtime.allowed()||running){if(!running)stopSelf();return START_NOT_STICKY;}
        try{
            mirror=intent.hasExtra("data");int type=mirror?ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION:ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if(Build.VERSION.SDK_INT>=29)startForeground(29043,LabConnectionService.notification(this,mirror?"九号手机镜像投屏中":"九号动态示例画面"),type);else startForeground(29043,LabConnectionService.notification(this,"九号投屏运行中"));
            JSONObject config=new JSONObject(intent.getStringExtra("config"));
            generation=config.getLong("generation");if(!runtime.ninebot.acceptsFrameSource(generation)){stopSelf();return START_NOT_STICKY;}
            int width=config.getInt("width"),height=config.getInt("height"),fps=config.getInt("fps"),bitrate=config.getInt("bitrate");
            if(width<16||height<16||width>4096||height>4096||fps<1||fps>60||bitrate<1||bitrate>8000000)throw new IllegalArgumentException("invalid_encoder_configuration");
            sessionPower=new CastSessionPower(this,runtime.log);sessionPower.start();
            if(mirror){Intent data=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra("data",Intent.class):intent.getParcelableExtra("data");
                projection=getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("code",0),data);
                if(projection==null)throw new IllegalStateException("projection_unavailable");
                projection.registerCallback(new MediaProjection.Callback(){public void onStop(){if(running)failSource("projection_revoked");}
                    public void onCapturedContentResize(int w,int h){runtime.log.diagnostic("projection_content_size","width",w,"height",h);}
                    public void onCapturedContentVisibilityChanged(boolean visible){capturedVisible=visible;runtime.log.diagnostic("projection_content_visibility","visible",visible);}},runtime.main);
                input=new ScreenVideoInput(projection,getResources().getDisplayMetrics().densityDpi,runtime.log);
            }else input=new TestPatternVideoInput(runtime.log);
            visual=runtime.castVisuals.begin(CastVisualFeed.Family.NINEBOT);
            metrics=new CastMetrics();running=true;runtime.frameSourceOwner=this;runtime.casting=true;lastEncodedAt=SystemClock.elapsedRealtime();
            sourceStartedAtMs=lastEncodedAt;
            input=new PaletteVideoInput(input,visual,runtime.log);
            input.setFrameObserver(metrics::recordCaptured);
            runtime.log.event("frame_source_start",new JSONObject(config.toString()).put("source",mirror?"mirror":"test_pattern"));
            if("mpeg2".equals(config.optString("codecName"))||config.optInt("codec",1)==0)startMpeg2(width,height,fps,bitrate,config.optInt("gop",5));
            else{h264=new CastEncoder(runtime.log,metrics,input);if(!h264.start(width,height,bitrate,fps))throw new IllegalStateException("h264_encoder_failed");drain=new Thread(this::drainH264,"ninebot-encoded-frames");drain.start();}
            runtime.main.post(summarize);
        }catch(Exception|LinkageError e){failSource(e.getMessage()==null?"frame_source_start_failed":e.getMessage());}
        return START_NOT_STICKY;
    }
    private void drainH264(){while(running){CastEncoder.EncodedFrame frame=h264.pollFrame(100);if(frame==null){if(!h264.isReady()){failSource("h264_drain_failed");return;}continue;}recordOutput();runtime.ninebot.acceptEncodedFrame(frame.accessUnit,frame.keyframe,frame.presentationTimeUs,generation,metrics,frame.readyAtElapsedMs);}}
    private void recordOutput(){
        long now=SystemClock.elapsedRealtime();lastEncodedAt=now;frameIndex++;
        if(!outputObserved){outputObserved=true;runtime.log.diagnostic("frame_source_first_output","sourceGeneration",generation,
            "elapsedMs",now-sourceStartedAtMs,"source",mirror?"mirror":"test_pattern");}
    }
    private void logSourceState(String event,String reason){
        runtime.log.diagnostic(event,"reason",reason,"sourceGeneration",generation,"frames",frameIndex,
            "lastOutputAgeMs",lastEncodedAt==0?JSONObject.NULL:Math.max(0,SystemClock.elapsedRealtime()-lastEncodedAt),
            "frameInput",input==null?JSONObject.NULL:input.frameState(),"encoder",h264==null?JSONObject.NULL:h264.diagnosticSnapshot(),
            "screenInteractive",sessionPower==null?JSONObject.NULL:sessionPower.interactive(),
            "keyguardLocked",sessionPower==null?JSONObject.NULL:sessionPower.locked(),"capturedVisible",capturedVisible);
    }
    private void failSource(String reason){
        if("service_stop".equals(stopReason))stopReason=reason;
        logSourceState("frame_source_failure",reason);
        runtime.ninebot.onFrameSourceFailure(generation,reason);stopSelf();
    }
    private void startMpeg2(int width,int height,int fps,int bitrate,int gop){
        mpeg2=new Mpeg2Codec(width,height,fps,bitrate,gop);metrics.identity(streamId,input.sourceId());metrics.configure(width,height,fps,bitrate,"ffmpeg-mpeg2video","mpeg2","configured");
        frameThread=new HandlerThread("mpeg2-encoder");frameThread.start();reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3);
        reader.setOnImageAvailableListener(source->{if(!running)return;try(Image image=source.acquireNextImage()){
            if(image==null)return;long pts=image.getTimestamp();if(lastImagePts!=0&&pts-lastImagePts<1_000_000_000L/fps)return;lastImagePts=pts;
            Image.Plane plane=image.getPlanes()[0];
            try{visual.sample(plane.getBuffer(),width,height,plane.getRowStride(),plane.getPixelStride(),SystemClock.elapsedRealtime());}
            catch(RuntimeException error){visual.disableSampling();runtime.log.diagnostic("cast_color_sampling_disabled","operation","mpeg2_sample","category",error.getClass().getSimpleName());}
            long started=SystemClock.elapsedRealtimeNanos();Mpeg2Codec.Packet packet=mpeg2.encode(plane.getBuffer(),plane.getRowStride(),plane.getPixelStride(),pts/1000);
            metrics.recordEncodeCall(SystemClock.elapsedRealtimeNanos()-started);if(packet==null)return;byte[] bytes=packet.bytes;boolean key=mpegKeyframe(bytes);metrics.recordEncoded(bytes.length,key,SystemClock.elapsedRealtime());
            visual.encodedFrame();
            recordOutput();
            JSONObject fields=new JSONObject().put("streamId",streamId).put("sourceGeneration",generation).put("frameId",frameIndex).put("ptsUs",packet.presentationTimeUs).put("keyframe",key).put("encodeCallElapsedNs",SystemClock.elapsedRealtimeNanos()-started).put("codec","mpeg2");
            runtime.log.trace("encoded_mpeg2",bytes,fields);runtime.ninebot.acceptEncodedFrame(bytes,key,packet.presentationTimeUs,generation,metrics,lastEncodedAt);
        }catch(Exception|LinkageError e){failSource("mpeg2_encode_failed");}},new Handler(frameThread.getLooper()));
        if(!input.attach(reader.getSurface(),width,height,fps))throw new IllegalStateException("mpeg2_source_attach_failed");
    }
    private static boolean mpegKeyframe(byte[] bytes){for(int i=0;i+5<bytes.length;i++)if(bytes[i]==0&&bytes[i+1]==0&&bytes[i+2]==1&&bytes[i+3]==0)return ((bytes[i+5]>>>3)&7)==1;return false;}
    private final Runnable summarize=new Runnable(){public void run(){
        if(!running)return;
        if(!runtime.ninebot.acceptsFrameSource(generation)){stopSelf();return;}
        long now=SystemClock.elapsedRealtime(),quietMs=now-lastEncodedAt;
        long sampleGapMs=lastSummaryAtMs==0?0:now-lastSummaryAtMs;
        long cpuMs=android.os.Process.getElapsedCpuTime(),cpuDeltaMs=lastSummaryAtMs==0?0:Math.max(0,cpuMs-lastProcessCpuMs);
        lastSummaryAtMs=now;lastProcessCpuMs=cpuMs;
        JSONObject frames=input.frameState();
        FrameOutputWatchdog.State next=FrameOutputWatchdog.evaluate(mirror,outputObserved,quietMs,
            sessionPower.interactive(),capturedVisible,frames.optBoolean("pacerActive"));
        if(next!=outputState){outputState=next;runtime.log.diagnostic("frame_output_state","state",next.name(),
            "quietMs",quietMs,"interactive",sessionPower.interactive(),"keyguardLocked",sessionPower.locked(),"capturedVisible",capturedVisible);}
        if(next==FrameOutputWatchdog.State.STALLED){failSource("encoder_no_output_15s");return;}
        JSONObject state=runtime.ninebot.snapshot();
        try{state.put("frameInput",frames).put("frameOutputState",next.name()).put("lastOutputAgeMs",quietMs)
            .put("frameDiagnosticsVersion",1).put("sourceGeneration",generation).put("summaryGapMs",sampleGapMs)
            .put("encoder",h264==null?JSONObject.NULL:h264.diagnosticSnapshot())
            .put("screenInteractive",sessionPower.interactive()).put("keyguardLocked",sessionPower.locked())
            .put("capturedVisible",capturedVisible).put("cpuWakeLockHeld",sessionPower.held());}catch(org.json.JSONException ignored){}
        runtime.log.metric(metrics.snapshot(state,h264==null?0:h264.queueDepth()));
        runtime.log.diagnostic("process_health","sourceGeneration",generation,"summaryGapMs",sampleGapMs,"processCpuDeltaMs",cpuDeltaMs,
            "processCpuPercentOfOneCore",sampleGapMs==0?JSONObject.NULL:cpuDeltaMs*100.0/sampleGapMs,
            "javaUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),"nativeHeapBytes",Debug.getNativeHeapAllocatedSize(),"thermalStatus",Build.VERSION.SDK_INT>=29?getSystemService(PowerManager.class).getCurrentThermalStatus():-1);
        runtime.main.postDelayed(this,1000);
    }};
    public void onTaskRemoved(Intent intent){stopReason="task_removed";if(runtime!=null){logSourceState("frame_source_stop_requested",stopReason);runtime.stopAll();}stopSelf();super.onTaskRemoved(intent);}
    public void onDestroy(){
        running=false;if(runtime!=null)runtime.main.removeCallbacks(summarize);
        releaseStartedAtMs=SystemClock.elapsedRealtime();if(runtime!=null)logSourceState("frame_source_release_start",stopReason);
        if(sessionPower!=null)releaseStep("session_power",sessionPower::close);
        if(visual!=null)visual.end();
        if(projection!=null)releaseStep("projection_stop",projection::stop);
        new Thread(()->releaseResources(),"ninebot-source-release").start();super.onDestroy();
    }
    private void releaseResources(){try{
        if(reader!=null)releaseStep("image_listener",()->reader.setOnImageAvailableListener(null,null));
        if(input!=null)releaseStep("input_detach",input::detach);
        if(drain!=null){drain.interrupt();awaitWorker(drain);}
        if(h264!=null)releaseStep("h264",h264::stop);
        if(frameThread!=null){releaseStep("frame_thread_quit",()->frameThread.quitSafely());awaitWorker(frameThread);}
        // Never close the native encoder while its image callback is still running.
        if(mpeg2!=null)releaseStep("mpeg2",mpeg2::close);
        if(reader!=null)releaseStep("image_reader",reader::close);
        if(input!=null)releaseStep("video_input",input::close);
        if(projection!=null)releaseStep("projection_release",projection::stop);
        }
        finally{if(runtime!=null)runtime.main.post(()->{if(runtime.frameSourceOwner==this){runtime.frameSourceOwner=null;runtime.casting=false;}runtime.log.diagnostic("frame_source_stopped","reason",stopReason,"releaseElapsedMs",SystemClock.elapsedRealtime()-releaseStartedAtMs,"frames",frameIndex,"frameCounterMeaning","frames_delivered_to_controller","generation",generation);runtime.notifyUi();});}
    }
    private void releaseStep(String resource,Runnable action){try{action.run();}catch(RuntimeException|LinkageError e){if(runtime!=null)runtime.log.diagnostic("source_release_failed","resource",resource,"generation",generation,"category",e.getClass().getSimpleName());}}
    private void awaitWorker(Thread worker){
        boolean interrupted=false;
        while(worker.isAlive())try{worker.join();}catch(InterruptedException e){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();
    }
    public IBinder onBind(Intent intent){return null;}
}
