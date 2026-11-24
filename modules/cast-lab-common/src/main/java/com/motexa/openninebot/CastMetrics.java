package com.motexa.openninebot;

import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;

/** Thread-safe counters and rolling traffic windows used by the unattended test. */
final class CastMetrics {
    private static final long WINDOW_MS = 60_000L;
    private final Object lock = new Object();
    private final ArrayDeque<Sample> captured = new ArrayDeque<>();
    private final ArrayDeque<Sample> sent = new ArrayDeque<>();
    private final ArrayDeque<Sample> encoded = new ArrayDeque<>();
    private long startedAtMs = SystemClock.elapsedRealtime();
    private long capturedFrames;
    private long lastCapturedAtMs, lastEncodedAtMs, lastSentAtMs;
    private long encodedFrames;
    private long encodedBytes,lastKeyframeAtMs,keyframeIntervalMs,maxKeyframeIntervalMs;
    private final WindowDurations writes=new WindowDurations(),ages=new WindowDurations(),pullWaits=new WindowDurations(),encodeCalls=new WindowDurations();
    private final WindowDurations actorQueueWaits=new WindowDurations();
    private JSONObject actualStream=new JSONObject();
    private String streamId="unknown",sourceName="unknown";
    void identity(String streamId,String source){synchronized(lock){this.streamId=streamId;sourceName=source;}}
    private long sentFrames;
    private long sentBytes;
    private long keyframes;
    private long droppedFrames;
    private long emptyPulls;
    private long pulls;
    private long pullWaitMs;
    private long maxPullWaitMs;
    private long writeMs;
    private long maxWriteMs;
    private long frameAgeMs;
    private long maxFrameAgeMs;
    private long peakAuBytes;
    private int width;
    private int height;
    private int targetFps;
    private int targetBitrate;
    private String codecName = "unknown";
    private String codecProfile = "unknown";
    private String codecLevel = "unknown";
    private int renderFrame;
    private long renderElapsedMs;
    private double renderFps1s;
    private long renderIntervalMs;
    private long renderMaxIntervalMs;

    void configure(int width, int height, int fps, int bitrate, String codecName,
        String codecProfile, String codecLevel) {
        synchronized (lock) {
            this.width = width;
            this.height = height;
            this.targetFps = fps;
            this.targetBitrate = bitrate;
            if (codecName != null) this.codecName = codecName;
            if (codecProfile != null) this.codecProfile = codecProfile;
            if (codecLevel != null) this.codecLevel = codecLevel;
        }
    }

    void recordCaptured() {
        long now = SystemClock.elapsedRealtime();
        synchronized (lock) {
            capturedFrames++;
            lastCapturedAtMs=now;
            captured.addLast(new Sample(now, 1, 0));
            purge(now);
        }
    }

    void recordPatternStats(int frame, long elapsedMs, double fps1s,
        long intervalMs, long maxIntervalMs) {
        synchronized (lock) {
            renderFrame = frame;
            renderElapsedMs = Math.max(0L, elapsedMs);
            renderFps1s = Math.max(0.0, fps1s);
            renderIntervalMs = Math.max(0L, intervalMs);
            renderMaxIntervalMs = Math.max(0L, maxIntervalMs);
        }
    }

    void recordEncoded(boolean keyframe, long nowMs) {
        recordEncoded(0,keyframe,nowMs);
    }
    void recordEncoded(int bytes,boolean keyframe,long nowMs) {
        synchronized (lock) {
            encodedFrames++;
            lastEncodedAtMs=nowMs;
            encodedBytes+=Math.max(0,bytes);peakAuBytes=Math.max(peakAuBytes,bytes);
            if (keyframe) {keyframes++;if(lastKeyframeAtMs>0){keyframeIntervalMs=nowMs-lastKeyframeAtMs;maxKeyframeIntervalMs=Math.max(maxKeyframeIntervalMs,keyframeIntervalMs);}lastKeyframeAtMs=nowMs;}
            encoded.addLast(new Sample(nowMs, 1, Math.max(0,bytes)));
            purge(nowMs);
        }
    }
    void actualStream(JSONObject info){synchronized(lock){actualStream=info;}}
    void recordEncodeCall(long durationNs){synchronized(lock){encodeCalls.add(durationNs/1_000_000.0);}}

    void recordSent(int bytes, long ageMs, long writeDurationMs, boolean keyframe, long nowMs) {
        synchronized (lock) {
            sentFrames++;
            lastSentAtMs=nowMs;
            sentBytes += Math.max(0, bytes);
            peakAuBytes = Math.max(peakAuBytes, bytes);
            frameAgeMs += Math.max(0, ageMs);
            maxFrameAgeMs = Math.max(maxFrameAgeMs, Math.max(0, ageMs));
            writeMs += Math.max(0, writeDurationMs);
            maxWriteMs = Math.max(maxWriteMs, Math.max(0, writeDurationMs));
            writes.add(Math.max(0,writeDurationMs));ages.add(Math.max(0,ageMs));
            sent.addLast(new Sample(nowMs, 1, Math.max(0, bytes)));
            purge(nowMs);
        }
    }

    void recordPull(long waitDurationMs, boolean empty) {
        synchronized (lock) {
            pulls++;
            pullWaitMs += Math.max(0, waitDurationMs);
            maxPullWaitMs = Math.max(maxPullWaitMs, Math.max(0, waitDurationMs));
            if (empty) emptyPulls++;
            pullWaits.add(Math.max(0,waitDurationMs));
        }
    }

    void recordDrop() { synchronized (lock) { droppedFrames++; } }
    void recordActorQueueWait(long durationMs) { actorQueueWaits.add(Math.max(0,durationMs)); }
    void updateTargetBitrate(int bitrate) { synchronized (lock) { targetBitrate = bitrate; } }

    JSONObject snapshot(JSONObject state, int queueDepth) {
        long now = SystemClock.elapsedRealtime();
        synchronized (lock) {
            purge(now);
            JSONObject value = state == null ? new JSONObject() : state;
            put(value, "type", "metrics");
            put(value,"streamId",streamId);put(value,"source",sourceName);
            put(value, "tsEpochMs", System.currentTimeMillis());
            put(value, "uptimeMs", now);
            put(value, "sessionElapsedMs", Math.max(0, now - startedAtMs));
            put(value, "captureCounterSource", capturedFrames==0?"unavailable_use_encoded_fps":
                "mirror".equals(sourceName)?"surface_texture_latched":"renderer");
            put(value,"capturedFrames",capturedFrames);put(value,"encodedFrames",encodedFrames);put(value,"sentFrames",sentFrames);
            put(value,"lastCapturedAgeMs",capturedFrames==0?JSONObject.NULL:Math.max(0,now-lastCapturedAtMs));
            put(value,"lastEncodedAgeMs",encodedFrames==0?JSONObject.NULL:Math.max(0,now-lastEncodedAtMs));
            put(value,"lastSentAgeMs",sentFrames==0?JSONObject.NULL:Math.max(0,now-lastSentAtMs));
            put(value,"captureFrameMeaning","mirror".equals(sourceName)?"new_latched_texture_excludes_repeated_frames":"source_rendered_frame");
            put(value, "width", width);
            put(value, "height", height);
            put(value, "targetFps", targetFps);
            put(value, "captureFps", fpsFor(captured, 1_000L, now));
            put(value, "captureFps10s", fpsFor(captured, 10_000L, now));
            put(value, "captureFps60s", fpsFor(captured, WINDOW_MS, now));
            put(value, "encodedFps", fpsFor(encoded, 1_000L, now));
            put(value, "encodedFps10s", fpsFor(encoded, 10_000L, now));
            put(value, "encodedFps60s", fpsFor(encoded, WINDOW_MS, now));
            put(value,"encodedBytes",encodedBytes);
            put(value,"encodedBitrate1s",bitrateFor(encoded,1_000,now));
            put(value,"encodedBitrate10s",bitrateFor(encoded,10_000,now));
            put(value,"encodedBitrate60s",bitrateFor(encoded,60_000,now));
            put(value,"actualStream",actualStream);
            put(value,"keyframeIntervalMs",keyframeIntervalMs);
            put(value,"maxKeyframeIntervalMs",maxKeyframeIntervalMs);
            put(value,"writeDurationMs",writes.json());put(value,"queueAgeMs",ages.json());
            put(value,"pullDurationMs",pullWaits.json());put(value,"softwareEncodeCallMs",encodeCalls.json());
            put(value,"actorQueueWaitMs",actorQueueWaits.json());
            put(value,"hardwareEncodeLatencyMs",JSONObject.NULL);put(value,"displayLatencyMs",JSONObject.NULL);
            put(value,"sentCounterMeaning","local_transport_submission_not_display_confirmation");
            put(value, "sentFps", fpsFor(sent, 1_000L, now));
            put(value, "sentFps10s", fpsFor(sent, 10_000L, now));
            put(value, "sentFps60s", fpsFor(sent, WINDOW_MS, now));
            put(value, "targetBitrate", targetBitrate);
            put(value, "actualBitrate", bitrateFor(60_000L, now));
            put(value, "actualBitrate1s", bitrateFor(1_000L, now));
            put(value, "actualBitrate10s", bitrateFor(10_000L, now));
            put(value, "actualBitrate60s", bitrateFor(60_000L, now));
            put(value, "peakAuBytes", peakAuBytes);
            put(value, "queueDepth", queueDepth);
            put(value, "droppedFrames", droppedFrames);
            put(value, "emptyPulls", emptyPulls);
            put(value, "pulls", pulls);
            put(value, "keyframes", keyframes);
            put(value, "averageFrameAgeMs", sentFrames == 0 ? 0 : frameAgeMs * 1.0 / sentFrames);
            put(value, "maxFrameAgeMs", maxFrameAgeMs);
            put(value, "pullWaitMs", pulls == 0 ? 0 : pullWaitMs * 1.0 / pulls);
            put(value, "maxPullWaitMs", maxPullWaitMs);
            put(value, "writeMs", sentFrames == 0 ? 0 : writeMs * 1.0 / sentFrames);
            put(value, "maxWriteMs", maxWriteMs);
            put(value, "codecName", codecName);
            put(value, "codecProfile", codecProfile);
            put(value, "codecLevel", codecLevel);
            put(value, "renderFrame", renderFrame);
            put(value, "renderElapsedMs", renderElapsedMs);
            put(value, "renderFps1s", renderFps1s);
            put(value, "renderIntervalMs", renderIntervalMs);
            put(value, "renderMaxIntervalMs", renderMaxIntervalMs);
            return value;
        }
    }

    String codecName() { synchronized (lock) { return codecName; } }
    long sentBytes() { synchronized (lock) { return sentBytes; } }
    long sentFrames() { synchronized (lock) { return sentFrames; } }
    int width() { synchronized (lock) { return width; } }
    int height() { synchronized (lock) { return height; } }
    int targetFps() { synchronized (lock) { return targetFps; } }

    private int bitrateFor(long windowMs, long now) {
        return bitrateFor(sent,windowMs,now);
    }
    private int bitrateFor(ArrayDeque<Sample> samples,long windowMs,long now) {
        long cutoff = now - windowMs;
        long bytes = 0;
        for (Sample item : samples) {
            if (item.atMs >= cutoff) {
                bytes += item.bytes;
            }
        }
        long elapsed = Math.max(1L, Math.min(windowMs, now - startedAtMs));
        return (int) Math.min(Integer.MAX_VALUE, bytes * 8_000L / elapsed);
    }

    private double fpsFor(ArrayDeque<Sample> samples, long windowMs, long now) {
        long cutoff = now - windowMs;
        long count = 0L;
        for (Sample item : samples) {
            if (item.atMs >= cutoff) {
                count += item.frames;
            }
        }
        if (count == 0L) return 0.0;
        long elapsed = Math.max(1L, Math.min(windowMs, now - startedAtMs));
        return count * 1_000.0 / elapsed;
    }

    private void purge(long now) {
        long cutoff = now - WINDOW_MS;
        while (!captured.isEmpty() && captured.peekFirst().atMs < cutoff) captured.removeFirst();
        while (!sent.isEmpty() && sent.peekFirst().atMs < cutoff) sent.removeFirst();
        while (!encoded.isEmpty() && encoded.peekFirst().atMs < cutoff) encoded.removeFirst();
    }

    private static void put(JSONObject value, String key, Object item) {
        try { value.put(key, item); } catch (JSONException ignored) {}
    }

    /** Bounded recent sample distribution, with the window stated in every report. */
    static final class WindowDurations {
        final double[] values=new double[600];int cursor,count;
        synchronized void clear(){cursor=count=0;}
        synchronized void add(double value){values[cursor]=value;cursor=(cursor+1)%values.length;count=Math.min(values.length,count+1);}
        synchronized JSONObject json(){JSONObject out=new JSONObject();try{double[] sorted=java.util.Arrays.copyOf(values,count);java.util.Arrays.sort(sorted);out.put("sampleCount",count).put("window","last_600_operations");for(int p:new int[]{50,95,99})out.put("p"+p,count==0?JSONObject.NULL:sorted[Math.max(0,(int)Math.ceil(count*p/100.0)-1)]);}catch(Exception ignored){}return out;}
    }

    private static final class Sample {
        final long atMs;
        final long frames;
        final long bytes;
        Sample(long atMs, long frames, long bytes) {
            this.atMs = atMs;
            this.frames = frames;
            this.bytes = bytes;
        }
    }
}
