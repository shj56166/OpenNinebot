package com.motexa.openninebot;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Hardware H.264 encoder and two-AU pull queue for instrument media transports. */
final class CastEncoder {
    private static final int MAX_QUEUE = 2;
    private static final int MIN_FPS = 1;
    private static final int MAX_FPS = 30;
    private static final int MIN_BITRATE = 1;
    private static final int MAX_BITRATE = 8_000_000;
    private static final long PULL_WAIT_MS = 25L;
    private static final long STALL_RECOVERY_MS = 450L;
    private static final long SYNC_REQUEST_MS = 300L;

    private final SessionLog log;
    private final CastMetrics metrics;
    private final CastVideoInput videoInput;
    private final Object streamLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong epoch = new AtomicLong();
    private final LinkedBlockingDeque<EncodedFrame> frames = new LinkedBlockingDeque<>(MAX_QUEUE);

    private volatile MediaCodec codec;
    private volatile Surface inputSurface;

    private volatile Thread drainThread;
    private volatile byte[] codecConfig = new byte[0];
    private volatile boolean awaitKeyframe;
    private volatile boolean pullObserved;
    private volatile boolean recoveryPending;
    private volatile long lastPullAtMs;
    private volatile long lastSyncAtMs;
    private volatile long lastRecoveryAtMs;
    private volatile long droppedFrames;
    private volatile long syncRequests;
    private volatile long encodedCount;
    private volatile String encoderName = "unknown";
    private volatile String encoderProfile = "unknown";
    private volatile String encoderLevel = "unknown";
    private volatile String bitrateMode = "fallback";
    private volatile String lastError;
    private int configuredWidth,configuredHeight;
    private byte[] verifiedParameterSets=new byte[0];
    private String streamId;
    private volatile long lastOutputAtMs,lastQueuedAtMs,lastOutputPtsUs,maxOutputGapMs,rawOutputFrames;
    private final CastMetrics.WindowDurations dequeueWaits=new CastMetrics.WindowDurations(),
        outputProcessing=new CastMetrics.WindowDurations(),queueOffers=new CastMetrics.WindowDurations();

    CastEncoder(SessionLog log, CastMetrics metrics, CastVideoInput videoInput) {
        this.log = log;
        this.metrics = metrics;
        this.videoInput = videoInput;
    }

    synchronized boolean start(int requestedWidth, int requestedHeight, int requestedBitrate,
        int requestedFps) {
        stop();
        int width = requestedWidth;
        int height = requestedHeight;
        if(width<16||height<16||width>4096||height>4096||(width&1)!=0||(height&1)!=0){lastError="encoder_dimensions_invalid";return false;}
        int fps = Math.max(MIN_FPS, Math.min(MAX_FPS, requestedFps > 0 ? requestedFps : 30));
        int bitrate = Math.max(MIN_BITRATE, Math.min(MAX_BITRATE,
            requestedBitrate > 0 ? requestedBitrate : 2_097_152));
        MediaCodec configured = null;
        lastError = null;
        encodedCount = 0;
        droppedFrames=syncRequests=0;
        lastOutputAtMs=lastQueuedAtMs=maxOutputGapMs=rawOutputFrames=0;lastOutputPtsUs=-1;
        dequeueWaits.clear();outputProcessing.clear();queueOffers.clear();
        configuredWidth=width;configuredHeight=height;verifiedParameterSets=new byte[0];
        streamId=java.util.UUID.randomUUID().toString().replace("-","");
        metrics.identity(streamId,videoInput.sourceId());
        try {
            logCodecCandidates();
            configured = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            boolean baselineApplied = false;
            try {
                configured.configure(format(width, height, fps, bitrate, true, true), null, null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
                baselineApplied = true;
                bitrateMode = "cbr";
                encoderProfile = "baseline";
                encoderLevel = Integer.toString(avcLevel(width,height,fps));
            } catch (RuntimeException tunedFailure) {
                log.log("encoderConfigureFallback reason=" + tunedFailure.getClass().getSimpleName());
                configured.reset();
                configured.configure(format(width, height, fps, bitrate, false, false), null, null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
                bitrateMode = "fallback";
                encoderProfile = "codec-default";
                encoderLevel = "codec-default";
            }
            encoderName = configured.getName();
            inputSurface = configured.createInputSurface();
            if (inputSurface == null) throw new IOException("encoder_input_surface_unavailable");
            configured.start();
            codec = configured;
            videoInput.setFrameObserver(metrics::recordCaptured);
            if (!videoInput.attach(inputSurface, width, height, fps)) {
                throw new IOException("screen_input_start_failed");
            }
            frames.clear();
            awaitKeyframe = true;
            pullObserved = false;
            recoveryPending = false;
            lastPullAtMs = SystemClock.elapsedRealtime();
            lastSyncAtMs = 0;
            lastRecoveryAtMs = 0;
            epoch.incrementAndGet();
            running.set(true);
            metrics.configure(width, height, fps, bitrate, encoderName, encoderProfile, encoderLevel);
            drainThread = new Thread(this::drainLoop, "CastAvcDrain");
            drainThread.start();
            log.log("encoderReady codecName=" + encoderName + " hardware="
                + (!encoderName.toLowerCase(java.util.Locale.US).startsWith("omx.google")
                    && !encoderName.toLowerCase(java.util.Locale.US).contains("software"))
                + " profile=" + encoderProfile + " level=" + encoderLevel
                + " bitrateMode=" + bitrateMode + " width=" + width + " height=" + height
                + " fps=" + fps + " bitrate=" + bitrate + " baselineRequested=" + baselineApplied);
            return true;
        } catch (Throwable error) {
            lastError = error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage());
            log.log("encoderStartFailed error=" + error.getClass().getSimpleName());
            if (configured != null && configured != codec) {
                try { configured.release(); } catch (RuntimeException ignored) {}
            }
            stop();
            return false;
        }
    }

    synchronized void prepareForPull() {
        synchronized (streamLock) {
            epoch.incrementAndGet();
            frames.clear();
            awaitKeyframe = true;
            recoveryPending = false;
            pullObserved = false;
        }
        lastPullAtMs = SystemClock.elapsedRealtime();
        lastRecoveryAtMs = 0;
        requestSyncFrame("pullStart");
        log.log("encoderPrepareForPull=true");
    }

    EncodedFrame pollFrame(long timeoutMs) {
        if (!running.get()) return null;
        long now = SystemClock.elapsedRealtime();
        pullObserved = true;
        if (lastPullAtMs > 0 && now - lastPullAtMs > STALL_RECOVERY_MS) {
            synchronized (streamLock) {
                frames.clear();
                awaitKeyframe = true;
                recoveryPending = true;
                epoch.incrementAndGet();
            }
            lastRecoveryAtMs = now;
            requestSyncFrame("consumerStall");
            metrics.recordDrop();
            log.log("encoderQueueRecovered reason=consumerStall");
        }
        lastPullAtMs = now;
        long expectedEpoch = epoch.get();
        long deadline = now + Math.max(0, timeoutMs);
        while (running.get()) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) return null;
            try {
                EncodedFrame frame = frames.poll(remaining, TimeUnit.MILLISECONDS);
                if (frame == null) return null;
                synchronized (streamLock) {
                    long current = epoch.get();
                    if (frame.epoch != expectedEpoch || current != expectedEpoch) {
                        expectedEpoch = current;
                        continue;
                    }
                    if (!awaitKeyframe || frame.keyframe) return frame;
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    synchronized void stop() {
        running.set(false);
        Thread activeDrain = drainThread;
        drainThread = null;
        if (activeDrain != null) {
            activeDrain.interrupt();
            if (activeDrain != Thread.currentThread()) {
                try { activeDrain.join(1_000L); } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        videoInput.detach();
        MediaCodec activeCodec = codec;
        codec = null;
        if (activeCodec != null) {
            try { activeCodec.stop(); } catch (RuntimeException ignored) {}
            try { activeCodec.release(); } catch (RuntimeException ignored) {}
        }
        videoInput.awaitDetached();
        Surface activeSurface = inputSurface;
        inputSurface = null;
        if (activeSurface != null) {
            try { activeSurface.release(); } catch (RuntimeException ignored) {}
        }
        synchronized (streamLock) {
            frames.clear();
            codecConfig = new byte[0];
            awaitKeyframe = false;
            recoveryPending = false;
            pullObserved = false;
            epoch.incrementAndGet();
        }
    }

    boolean isReady() { return running.get(); }
    int queueDepth() { return frames.size(); }
    String encoderName() { return encoderName; }
    String encoderProfile() { return encoderProfile; }
    String encoderLevel() { return encoderLevel; }
    String bitrateMode() { return bitrateMode; }
    String lastError() { return lastError; }
    long droppedFrames() { return droppedFrames; }
    long syncRequests() { return syncRequests; }
    org.json.JSONObject inputState() { return videoInput.frameState(); }
    org.json.JSONObject diagnosticSnapshot() {
        org.json.JSONObject state=new org.json.JSONObject();long now=SystemClock.elapsedRealtime();
        EncodedFrame oldest=frames.peekFirst();
        try{state.put("ready",isReady()).put("rawOutputFrames",rawOutputFrames).put("queuedFrames",encodedCount)
            .put("lastCodecOutputAgeMs",rawOutputFrames==0?org.json.JSONObject.NULL:Math.max(0,now-lastOutputAtMs))
            .put("lastQueuedFrameAgeMs",encodedCount==0?org.json.JSONObject.NULL:Math.max(0,now-lastQueuedAtMs))
            .put("lastOutputPtsUs",lastOutputPtsUs).put("maxCodecOutputGapMs",maxOutputGapMs)
            .put("consumerPollAgeMs",pullObserved?Math.max(0,now-lastPullAtMs):org.json.JSONObject.NULL)
            .put("oldestQueuedFrameAgeMs",oldest==null?org.json.JSONObject.NULL:Math.max(0,now-oldest.readyAtElapsedMs))
            .put("awaitKeyframe",awaitKeyframe).put("recoveryPending",recoveryPending).put("syncRequests",syncRequests)
            .put("droppedFrames",droppedFrames).put("lastError",lastError==null?org.json.JSONObject.NULL:lastError)
            .put("codecDequeueWaitMs",dequeueWaits.json()).put("outputProcessingMs",outputProcessing.json())
            .put("queueOfferMs",queueOffers.json()).put("hardwareEncodeLatencyMs",org.json.JSONObject.NULL);
        }catch(org.json.JSONException ignored){}return state;
    }

    synchronized boolean changeBitrate(int bitrate) {
        MediaCodec active = codec;
        if (!running.get() || active == null || bitrate < 256_000 || bitrate > MAX_BITRATE) return false;
        try {
            Bundle parameters = new Bundle();
            parameters.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
            active.setParameters(parameters);
            metrics.updateTargetBitrate(bitrate);
            requestSyncFrame("bitrateChange");
            log.log("encoderBitrateChanged bitrate=" + bitrate + " mode=" + bitrateMode);
            return true;
        } catch (RuntimeException error) {
            log.log("encoderBitrateChangeFailed category=" + error.getClass().getSimpleName());
            return false;
        }
    }

    private void drainLoop() {
        MediaCodec active = codec;
        if (active == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running.get()) {
            int index;
            long dequeueStart=System.nanoTime();
            try { index = active.dequeueOutputBuffer(info, 100_000); }
            catch (RuntimeException error) {
                lastError = "drain:" + error.getClass().getSimpleName();
                running.set(false);
                log.log("encoderDrainFailed error=" + error.getClass().getSimpleName());
                return;
            }
            dequeueWaits.add((System.nanoTime()-dequeueStart)/1_000_000.0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat output = active.getOutputFormat();
                codecConfig = AvcAnnexB.joinConfig(output.getByteBuffer("csd-0"),
                    output.getByteBuffer("csd-1"));
                log.log("encoderOutputFormatChanged codecConfigBytes=" + codecConfig.length);
                log.diagnostic("encoder_output", "width", output.getInteger(MediaFormat.KEY_WIDTH),
                    "height", output.getInteger(MediaFormat.KEY_HEIGHT), "configBytes", codecConfig.length,
                    "sps", AvcAnnexB.hasNalType(codecConfig,7), "pps", AvcAnnexB.hasNalType(codecConfig,8));
                continue;
            }
            if (index < 0) continue;
            long processingStart=System.nanoTime();
            try {
                ByteBuffer buffer = active.getOutputBuffer(index);
                if (buffer == null || info.size <= 0) continue;
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                byte[] encoded = new byte[info.size];
                buffer.get(encoded);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    log.trace("h264_codec_config",encoded,rawOutputDetails(info));
                    byte[] parameterSets = AvcAnnexB.extractParameterSets(AvcAnnexB.convert(encoded));
                    if (parameterSets.length > 0) codecConfig = parameterSets;
                    continue;
                }
                byte[] accessUnit = AvcAnnexB.convert(encoded);
                long outputAt=SystemClock.elapsedRealtime();
                if(rawOutputFrames>0){long gap=outputAt-lastOutputAtMs;maxOutputGapMs=Math.max(maxOutputGapMs,gap);
                    if(gap>500)log.diagnostic("encoder_output_gap","streamId",streamId,"gapMs",gap,
                        "ptsDeltaUs",info.presentationTimeUs-lastOutputPtsUs,"queueDepth",frames.size(),
                        "consumerPollAgeMs",Math.max(0,outputAt-lastPullAtMs),"awaitKeyframe",awaitKeyframe);}
                rawOutputFrames++;lastOutputAtMs=outputAt;lastOutputPtsUs=info.presentationTimeUs;
                log.trace("codec_h264_output",accessUnit,rawOutputDetails(info));
                metrics.recordEncoded(accessUnit.length,(info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0||AvcAnnexB.hasNalType(accessUnit,5),SystemClock.elapsedRealtime());
                byte[] inBandConfig = AvcAnnexB.extractParameterSets(accessUnit);
                if (inBandConfig.length > 0) codecConfig = inBandConfig;
                boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    || AvcAnnexB.hasNalType(accessUnit, 5);
                if (keyframe && !isCompleteKeyframe(accessUnit) && codecConfig.length > 0) {
                    accessUnit = concat(codecConfig, accessUnit);
                }
                if (keyframe && !isCompleteKeyframe(accessUnit)) {
                    requestSyncFrame("incompleteKeyframe");
                    log.log("encodedFrameSkipped reason=incompleteKeyframe");
                    continue;
                }
                if (awaitKeyframe && !keyframe) continue;
                accessUnit = AvcAnnexB.ensureAud(accessUnit);
                if(!java.util.Arrays.equals(verifiedParameterSets,codecConfig)){
                    AvcStreamInfo actual=AvcStreamInfo.parse(codecConfig);AvcStreamInfo.validatePps(codecConfig);
                    org.json.JSONObject evidence=actual.json();metrics.actualStream(evidence);log.event("h264_actual_stream",evidence);
                    if(actual.width!=configuredWidth||actual.height!=configuredHeight)throw new IllegalStateException("h264_actual_dimensions_mismatch");
                    verifiedParameterSets=codecConfig.clone();
                }
                if(AvcStreamInfo.hasBSlice(accessUnit))throw new IllegalStateException("h264_b_frame_detected");
                long currentEpoch = epoch.get();
                EncodedFrame frame = new EncodedFrame(accessUnit, keyframe, info.presentationTimeUs,
                    SystemClock.elapsedRealtime(), currentEpoch);
                org.json.JSONObject details=frameDetails(frame);try{details.put("streamId",streamId).put("frameId",encodedCount+1);}catch(Exception ignored){}log.trace("encoded_h264",accessUnit,details);
                long offerStart=System.nanoTime();boolean offered=offerFrame(frame);
                queueOffers.add((System.nanoTime()-offerStart)/1_000_000.0);
                if (offered) {
                    lastQueuedAtMs=SystemClock.elapsedRealtime();
                    videoInput.encodedFrame();
                    synchronized (streamLock) {
                        if (keyframe && frame.epoch == epoch.get()) {
                            awaitKeyframe = false;
                            recoveryPending = false;
                        }
                    }
                    encodedCount++;
                    if (encodedCount <= 5 || encodedCount % 60 == 0) {
                        log.log("encodedFrameReady keyframe=" + keyframe + " bytes=" + accessUnit.length
                            + " queue=" + frames.size() + " aud=" + AvcAnnexB.hasNalType(accessUnit, 9));
                    }
                }
            } catch(RuntimeException error) {
                lastError=error.getMessage()==null?"h264_output_invalid":error.getMessage();running.set(false);
                log.diagnostic("encoder_stream_rejected","reason",lastError);return;
            } finally {
                try { active.releaseOutputBuffer(index, false); } catch (RuntimeException ignored) {}
                outputProcessing.add((System.nanoTime()-processingStart)/1_000_000.0);
            }
        }
    }

    private boolean offerFrame(EncodedFrame frame) {
        while (running.get()) {
            synchronized (streamLock) {
                if (frame.epoch != epoch.get()) return false;
                if (frames.offerLast(frame)) return true;
            }
            try { Thread.sleep(PULL_WAIT_MS); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
            long now = SystemClock.elapsedRealtime();
            long sincePull = lastPullAtMs == 0 ? Long.MAX_VALUE : now - lastPullAtMs;
            boolean recover = false;
            synchronized (streamLock) {
                if (!recoveryPending && pullObserved && sincePull >= STALL_RECOVERY_MS
                    && now - lastRecoveryAtMs >= STALL_RECOVERY_MS) {
                    frames.clear();
                    awaitKeyframe = true;
                    recoveryPending = true;
                    lastRecoveryAtMs = now;
                    epoch.incrementAndGet();
                    recover = true;
                }
            }
            if (recover) {
                droppedFrames++;
                metrics.recordDrop();
                requestSyncFrame("consumerStall");
                log.log("encodedFrameDropped reason=consumerStall dropped=" + droppedFrames);
                return false;
            }
        }
        return false;
    }

    private synchronized void requestSyncFrame(String reason) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSyncAtMs < SYNC_REQUEST_MS) return;
        MediaCodec active = codec;
        if (active == null) return;
        lastSyncAtMs = now;
        try {
            Bundle request = new Bundle();
            request.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            active.setParameters(request);
            syncRequests++;
            log.log("encoderSyncFrameRequested reason=" + reason + " count=" + syncRequests);
        } catch (RuntimeException error) {
            log.log("encoderSyncFrameRequestFailed reason=" + reason);
        }
    }

    private static MediaFormat format(int width, int height, int fps, int bitrate,
        boolean baseline, boolean tuned) {
        MediaFormat value = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        value.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        value.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        value.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        value.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (tuned) {
            value.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            value.setInteger(MediaFormat.KEY_PRIORITY, 0);
            value.setInteger(MediaFormat.KEY_OPERATING_RATE, fps);
        }
        if (tuned && android.os.Build.VERSION.SDK_INT >= 26) value.setInteger(MediaFormat.KEY_LATENCY, 1);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            value.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            value.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps);
            value.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
        }
        if (baseline) {
            value.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            value.setInteger(MediaFormat.KEY_LEVEL, avcLevel(width,height,fps));
        }
        return value;
    }

    static int avcLevel(int width,int height,int fps){
        if(width<1||height<1||width>4096||height>4096||fps<1||fps>60)throw new IllegalArgumentException("avc_level_configuration_invalid");
        long columns=(width+15L)/16,rows=(height+15L)/16,blocks=columns*rows,rate=blocks*fps;
        // H.264 Annex A also bounds each picture dimension by sqrt(MaxFS * 8).
        long dimensionSquared=Math.max(columns*columns,rows*rows);
        if(blocks<=3600&&dimensionSquared<=3600L*8&&rate<=108000)return MediaCodecInfo.CodecProfileLevel.AVCLevel31;
        if(blocks<=5120&&dimensionSquared<=5120L*8&&rate<=216000)return MediaCodecInfo.CodecProfileLevel.AVCLevel32;
        if(blocks<=8192&&dimensionSquared<=8192L*8&&rate<=245760)return MediaCodecInfo.CodecProfileLevel.AVCLevel4;
        if(blocks<=8192&&dimensionSquared<=8192L*8&&rate<=522240)return MediaCodecInfo.CodecProfileLevel.AVCLevel42;
        if(blocks<=22080&&dimensionSquared<=22080L*8&&rate<=589824)return MediaCodecInfo.CodecProfileLevel.AVCLevel5;
        if(blocks<=36864&&dimensionSquared<=36864L*8&&rate<=983040)return MediaCodecInfo.CodecProfileLevel.AVCLevel51;
        if(blocks<=36864&&dimensionSquared<=36864L*8&&rate<=2073600)return MediaCodecInfo.CodecProfileLevel.AVCLevel52;
        return MediaCodecInfo.CodecProfileLevel.AVCLevel6;
    }
    private static org.json.JSONObject frameDetails(EncodedFrame frame){org.json.JSONObject fields=new org.json.JSONObject();try{fields.put("keyframe",frame.keyframe).put("ptsUs",frame.presentationTimeUs).put("generation",frame.epoch).put("readyAtMs",frame.readyAtElapsedMs);}catch(Exception ignored){}return fields;}
    private org.json.JSONObject rawOutputDetails(MediaCodec.BufferInfo info){org.json.JSONObject fields=new org.json.JSONObject();try{fields.put("streamId",streamId).put("ptsUs",info.presentationTimeUs).put("flags",info.flags).put("generation",epoch.get());}catch(Exception ignored){}return fields;}

    private void logCodecCandidates() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                boolean avc = false;
                for (String type : info.getSupportedTypes()) {
                    if (MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) {
                        avc = true;
                        break;
                    }
                }
                if (avc) {
                    boolean hardware = Build.VERSION.SDK_INT < 29 || !info.isSoftwareOnly();
                    boolean vendor = Build.VERSION.SDK_INT >= 29 && info.isVendor();
                    log.log("encoderCandidate name=" + info.getName() + " hardware="
                        + hardware + " vendor=" + vendor);
                }
            }
        } catch (RuntimeException error) {
            log.log("encoderDiscoveryFailed error=" + error.getClass().getSimpleName());
        }
    }

    private static boolean isCompleteKeyframe(byte[] data) {
        return AvcAnnexB.hasNalType(data, 7) && AvcAnnexB.hasNalType(data, 8)
            && AvcAnnexB.hasNalType(data, 5);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    static final class EncodedFrame {
        final byte[] accessUnit;
        final boolean keyframe;
        final long presentationTimeUs;
        final long readyAtElapsedMs;
        final long epoch;

        EncodedFrame(byte[] accessUnit, boolean keyframe, long presentationTimeUs,
            long readyAtElapsedMs, long epoch) {
            this.accessUnit = accessUnit;
            this.keyframe = keyframe;
            this.presentationTimeUs = presentationTimeUs;
            this.readyAtElapsedMs = readyAtElapsedMs;
            this.epoch = epoch;
        }

        long ageMs() { return Math.max(0, SystemClock.elapsedRealtime() - readyAtElapsedMs); }
    }
}
