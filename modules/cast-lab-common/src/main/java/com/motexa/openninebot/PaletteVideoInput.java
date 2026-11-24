package com.motexa.openninebot;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One input display, paced into the codec Surface, with an optional tiny color readback. */
final class PaletteVideoInput implements CastVideoInput {
    private final CastVideoInput source;
    private final CastVisualFeed.Source visual;
    private final SessionLog log;
    private Session session, retiring;
    private volatile Runnable frameObserver;

    PaletteVideoInput(CastVideoInput source, CastVisualFeed.Source visual, SessionLog log) {
        this.source = source; this.visual = visual; this.log = log;
    }

    public String sourceId() { return source.sourceId(); }
    public void setFrameObserver(Runnable observer) { frameObserver = observer; source.setFrameObserver(observer); }
    public void encodedFrame() { visual.encodedFrame(); }

    public synchronized org.json.JSONObject frameState() {
        org.json.JSONObject state = new org.json.JSONObject();
        Session current = session;
        try {
            long now = SystemClock.elapsedRealtime();
            state.put("pacerActive", current != null && current.pacing)
                .put("sourceFrames", current == null ? 0 : current.sourceFrames)
                .put("renderedFrames", current == null ? 0 : current.renderedFrames)
                .put("repeatedFrames", current == null ? 0 : current.repeatedFrames)
                .put("lastSourceAtMs", current == null ? 0 : current.lastSourceAtMs)
                .put("lastRenderedAtMs", current == null ? 0 : current.lastRenderedAtMs);
            if (current != null) {
                state.put("inputId",current.id).put("targetFps",current.fps)
                    .put("frameAvailableCallbacks",current.frameCallbacks)
                    .put("lastFrameAvailableAgeMs",current.frameCallbacks==0?org.json.JSONObject.NULL:Math.max(0,now-current.lastFrameAvailableAtMs))
                    .put("lastSourceAgeMs",current.sourceFrames==0?org.json.JSONObject.NULL:Math.max(0,now-current.lastSourceAtMs))
                    .put("lastRenderedAgeMs",current.renderedFrames==0?org.json.JSONObject.NULL:Math.max(0,now-current.lastRenderedAtMs))
                    .put("maxRenderGapMs",current.maxRenderGapMs).put("skippedRenderTicks",current.pacer.skippedTicks())
                    .put("schedulerLatenessMs",current.schedulerLateness.json()).put("textureLatchMs",current.latchTimes.json())
                    .put("surfaceSubmitMs",current.submitTimes.json()).put("colorSampleMs",current.sampleTimes.json());
            }
        } catch (org.json.JSONException ignored) {}
        return state;
    }

    public synchronized boolean attach(Surface target, int width, int height, int fps) {
        detach();
        awaitDetached();
        Session next = new Session(target, width, height, fps);
        session = next;
        next.handler.post(next::start);
        try {
            if (next.ready.await(5, TimeUnit.SECONDS) && next.attached) return true;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        detach();
        return false;
    }

    public synchronized void detach() {
        Session previous = session; session = null;
        if (previous == null) return;
        previous.active = false;
        // Disconnect the producer now; a codec stalled in swapBuffers is released by codec.stop().
        retiring = previous;
        try { source.detach(); }
        finally { previous.handler.post(previous::stop); }
    }

    public void awaitDetached() {
        Session previous;
        synchronized (this) { previous = retiring; }
        if (previous == null) return;
        // No resource is released while a render callback can still be using it.
        boolean interrupted = false;
        for (;;) try { previous.stopped.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        synchronized (this) { if (retiring == previous) retiring = null; }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public void close() { detach(); awaitDetached(); source.close(); }

    private final class Session {
        final Surface target;
        final int width, height, fps;
        final HandlerThread thread = new HandlerThread("cast-source-colors");
        final Handler handler;
        final CountDownLatch ready = new CountDownLatch(1), stopped = new CountDownLatch(1);
        volatile boolean active = true, attached;
        volatile boolean pacing;
        volatile long sourceFrames, renderedFrames, repeatedFrames, lastSourceAtMs, lastRenderedAtMs;
        volatile long maxRenderGapMs;
        volatile long frameCallbacks,lastFrameAvailableAtMs;
        final String id=java.util.UUID.randomUUID().toString().replace("-","");
        final long startedAtMs=SystemClock.elapsedRealtime();
        final CastMetrics.WindowDurations schedulerLateness=new CastMetrics.WindowDurations(),
            latchTimes=new CastMetrics.WindowDurations(),submitTimes=new CastMetrics.WindowDurations(),
            sampleTimes=new CastMetrics.WindowDurations();
        long lastSlowLogAtMs;
        boolean sourceIdle;
        final boolean mirror = "mirror".equals(source.sourceId());
        final VideoFramePacer pacer;
        boolean pendingFrame;
        final Runnable renderTick = this::render;
        Renderer renderer;

        Session(Surface target, int width, int height, int fps) {
            this.target = target; this.width = width; this.height = height; this.fps = fps;
            pacer = new VideoFramePacer(fps);
            thread.start(); handler = new Handler(thread.getLooper());
        }

        void start() {
            try {
                if (!active) return;
                try {
                    renderer = new Renderer(target, width, height);
                    renderer.texture.setOnFrameAvailableListener(ignored -> {
                        if (!active) return;
                        frameCallbacks++;lastFrameAvailableAtMs=SystemClock.elapsedRealtime();
                        pendingFrame = true;
                        if (!mirror || renderedFrames == 0) render();
                    }, handler);
                } catch (RuntimeException | LinkageError error) {
                    disabled("init", error); closeRenderer();
                }
                if (active) attached = source.attach(renderer == null ? target : renderer.input, width, height, fps);
                pacing = attached && renderer != null && mirror;
                log.diagnostic("source_frame_pacing", "inputId", id, "source", sourceId(), "active", pacing, "targetFps", fps,
                    "width", width, "height", height);
                if (!attached && renderer != null && active) fallback();
            } catch (RuntimeException | LinkageError error) {
                log.diagnostic("cast_color_source_attach_failed", "category", error.getClass().getSimpleName());
            } finally { ready.countDown(); }
        }

        void render() {
            if (!active || renderer == null) return;
            try {
                if (mirror && !pacer.take(System.nanoTime())) return;
                if (mirror) schedulerLateness.add(pacer.lastLatenessNs()/1_000_000.0);
                boolean fresh = false;
                if (pendingFrame) {
                    pendingFrame = false; long latchStart=System.nanoTime(); fresh = renderer.update();
                    latchTimes.add((System.nanoTime()-latchStart)/1_000_000.0);
                }
                if (!renderer.hasFrame() || (!mirror && !fresh)) return;
                if (fresh) {
                    long sourceAt=SystemClock.elapsedRealtime();
                    if(sourceFrames==0)log.diagnostic("source_first_frame","inputId",id,"elapsedMs",sourceAt-startedAtMs,"sourcePtsNs",renderer.lastTimestamp);
                    else if(sourceIdle)log.diagnostic("source_capture_resumed","inputId",id,"quietMs",sourceAt-lastSourceAtMs,"repeatedFrames",repeatedFrames);
                    sourceIdle=false;
                    sourceFrames++;lastSourceAtMs=sourceAt;
                    Runnable observer=frameObserver;
                    if(mirror&&observer!=null)observer.run();
                }
                // Reused textures still need a fresh monotonic codec presentation timestamp.
                long submitStart=System.nanoTime();
                renderer.forward(mirror ? pacer.presentationTimeNs() : renderer.lastTimestamp);
                double submitMs=(System.nanoTime()-submitStart)/1_000_000.0;submitTimes.add(submitMs);
                long now = SystemClock.elapsedRealtime();
                if(renderedFrames>0)maxRenderGapMs=Math.max(maxRenderGapMs,now-lastRenderedAtMs);
                renderedFrames++; lastRenderedAtMs = now;
                if (!fresh) {
                    repeatedFrames++;
                    if(!sourceIdle&&now-lastSourceAtMs>1000){sourceIdle=true;log.diagnostic("source_capture_idle","inputId",id,
                        "quietMs",now-lastSourceAtMs,"renderedFrames",renderedFrames,"repeatedFrames",repeatedFrames);}
                }
                if((submitMs>100||(mirror&&pacer.lastLatenessNs()>250_000_000L))&&now-lastSlowLogAtMs>1000){
                    lastSlowLogAtMs=now;log.diagnostic("source_render_slow","inputId",id,"surfaceSubmitMs",submitMs,
                        "schedulerLateMs",pacer.lastLatenessNs()/1_000_000.0,"skippedRenderTicks",pacer.skippedTicks());
                }
                if (fresh && visual.wantsSample(now)) {
                    long sampleStart=System.nanoTime();
                    try { renderer.sample(visual, now); }
                    catch (RuntimeException | LinkageError error) { disabled("sample", error); }
                    finally { sampleTimes.add((System.nanoTime()-sampleStart)/1_000_000.0); }
                }
            } catch (RuntimeException | LinkageError error) {
                if (active) { disabled("forward", error); fallback(); }
            } finally { if (active && pacing) scheduleRender(); }
        }

        void scheduleRender() {
            handler.removeCallbacks(renderTick);
            long delayNs = pacer.delayNs(System.nanoTime());
            handler.postDelayed(renderTick, Math.max(1, (delayNs + 999_999L) / 1_000_000L));
        }

        void fallback() {
            pacing = false;
            handler.removeCallbacks(renderTick);
            visual.disableSampling();
            try {
                try { source.detach(); } finally { closeRenderer(); }
                if (active) attached = source.attach(target, width, height, fps);
            } catch (RuntimeException | LinkageError error) {
                attached = false;
                log.diagnostic("cast_color_direct_fallback_failed", "category", error.getClass().getSimpleName());
            }
        }

        void disabled(String operation, Throwable error) {
            visual.disableSampling();
            log.diagnostic("cast_color_sampling_disabled", "operation", operation, "category", error.getClass().getSimpleName());
        }

        void closeRenderer() { if (renderer != null) { renderer.close(); renderer = null; } }

        void stop() {
            pacing = false;
            handler.removeCallbacks(renderTick);
            try { source.detach(); }
            finally {
                try { closeRenderer(); }
                finally {
                    log.diagnostic("source_render_stopped","inputId",id,"sourceFrames",sourceFrames,"renderedFrames",renderedFrames,
                        "repeatedFrames",repeatedFrames,"skippedRenderTicks",pacer.skippedTicks());
                    ready.countDown(); stopped.countDown(); thread.quitSafely();
                }
            }
        }
    }

    private static final class Renderer implements AutoCloseable {
        private static final String VERTEX = "attribute vec2 a;attribute vec2 u;uniform mat4 m;varying vec2 v;void main(){gl_Position=vec4(a,0.,1.);v=(m*vec4(u,0.,1.)).xy;}";
        private static final String FRAGMENT = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 v;uniform samplerExternalOES tex;void main(){gl_FragColor=texture2D(tex,v);}";
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface window = EGL14.EGL_NO_SURFACE;
        private SurfaceTexture texture;
        private Surface input;
        private int program, oes, framebuffer, sampleTexture, position, uv, transform;
        private long lastTimestamp = -1;
        private final int width, height;
        private final float[] matrix = new float[16];
        private final FloatBuffer vertices = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final ByteBuffer pixels = ByteBuffer.allocateDirect(CastPalette.SAMPLE_WIDTH * CastPalette.SAMPLE_HEIGHT * 4);

        Renderer(Surface target, int width, int height) {
            this.width = width; this.height = height;
            vertices.put(new float[]{-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1}).position(0);
            try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                int[] version = new int[2];
                if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("color_egl_init");
                EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
                int[] attrs = {EGL14.EGL_RED_SIZE,8, EGL14.EGL_GREEN_SIZE,8, EGL14.EGL_BLUE_SIZE,8,
                        EGL14.EGL_ALPHA_SIZE,8, EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,
                        EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT, 0x3142,1, EGL14.EGL_NONE};
                if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) throw new IllegalStateException("color_egl_config");
                context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE}, 0);
                window = EGL14.eglCreateWindowSurface(display, configs[0], target, new int[]{EGL14.EGL_NONE}, 0);
                if (!EGL14.eglMakeCurrent(display, window, window, context)) throw new IllegalStateException("color_egl_current");
                int vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX), fragment = 0;
                try {
                    fragment = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
                    program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program);
                } finally { GLES20.glDeleteShader(vertex); if (fragment != 0) GLES20.glDeleteShader(fragment); }
                int[] linked = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
                if (linked[0] == 0) throw new IllegalStateException("color_program_link");
                position = GLES20.glGetAttribLocation(program, "a"); uv = GLES20.glGetAttribLocation(program, "u"); transform = GLES20.glGetUniformLocation(program, "m");
                int[] names = new int[1]; GLES20.glGenTextures(1, names, 0); oes = names[0];
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes); textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
                texture = new SurfaceTexture(oes); texture.setDefaultBufferSize(width, height); input = new Surface(texture);
                // The optional FBO is allocated lazily when the header is actually visible.
            } catch (RuntimeException | LinkageError error) { close(); throw error; }
        }

        boolean update() {
            texture.updateTexImage(); texture.getTransformMatrix(matrix);
            long pts = texture.getTimestamp();
            if (pts == lastTimestamp) return false;
            lastTimestamp = pts;
            return true;
        }

        boolean hasFrame() { return lastTimestamp >= 0; }

        void forward(long pts) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); GLES20.glViewport(0, 0, width, height); draw();
            EGLExt.eglPresentationTimeANDROID(display, window, pts > 0 ? pts : System.nanoTime());
            if (!EGL14.eglSwapBuffers(display, window)) throw new IllegalStateException("color_surface_swap");
        }

        void sample(CastVisualFeed.Source visual, long now) {
            try {
                if (framebuffer == 0) {
                    int[] names = new int[1]; GLES20.glGenTextures(1, names, 0); sampleTexture = names[0];
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sampleTexture); textureParameters(GLES20.GL_TEXTURE_2D);
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, CastPalette.SAMPLE_WIDTH, CastPalette.SAMPLE_HEIGHT, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                    GLES20.glGenFramebuffers(1, names, 0); framebuffer = names[0];
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, sampleTexture, 0);
                    if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("color_framebuffer");
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
                GLES20.glViewport(0, 0, CastPalette.SAMPLE_WIDTH, CastPalette.SAMPLE_HEIGHT); draw();
                pixels.clear();
                GLES20.glReadPixels(0, 0, CastPalette.SAMPLE_WIDTH, CastPalette.SAMPLE_HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR) throw new IllegalStateException("color_readback");
                visual.sample(pixels, CastPalette.SAMPLE_WIDTH, CastPalette.SAMPLE_HEIGHT, CastPalette.SAMPLE_WIDTH * 4, 4, now);
            } finally { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); GLES20.glViewport(0, 0, width, height); }
        }

        private void draw() {
            GLES20.glUseProgram(program); GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "tex"), 0); GLES20.glUniformMatrix4fv(transform, 1, false, matrix, 0);
            vertices.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices); GLES20.glEnableVertexAttribArray(position);
            vertices.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices); GLES20.glEnableVertexAttribArray(uv);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(uv);
        }

        private static void textureParameters(int target) {
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        private static int shader(int type, String code) {
            int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, code); GLES20.glCompileShader(shader);
            int[] compiled = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] != 0) return shader;
            GLES20.glDeleteShader(shader); throw new IllegalStateException("color_shader_compile");
        }

        public void close() {
            if (texture != null) { texture.setOnFrameAvailableListener(null); texture.release(); texture = null; }
            if (input != null) { input.release(); input = null; }
            if (display == EGL14.EGL_NO_DISPLAY) return;
            if (context != EGL14.EGL_NO_CONTEXT) {
                if (oes != 0) GLES20.glDeleteTextures(1, new int[]{oes}, 0);
                if (sampleTexture != 0) GLES20.glDeleteTextures(1, new int[]{sampleTexture}, 0);
                if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
                if (program != 0) GLES20.glDeleteProgram(program);
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            }
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display); EGL14.eglReleaseThread();
            display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; window = EGL14.EGL_NO_SURFACE;
        }
    }
}
