package com.motexa.openninebot;

import android.opengl.*;
import android.os.SystemClock;
import android.view.Surface;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Deterministic GLES pattern with frame number, elapsed seconds, motion and fine detail. */
final class TestPatternVideoInput implements CastVideoInput {
    private final SessionLog log;
    private volatile boolean running;
    private volatile RuntimeException failure;
    private volatile Thread thread;
    private volatile Runnable frameObserver;
    TestPatternVideoInput(SessionLog log){this.log=log;}
    public String sourceId(){return "test_pattern";}
    public synchronized boolean attach(Surface surface,int width,int height,int fps){
        detach();running=true;failure=null;CountDownLatch ready=new CountDownLatch(1);
        thread=new Thread(()->render(surface,width,height,fps,ready),"cast-test-pattern");thread.start();
        try{return ready.await(3,TimeUnit.SECONDS)&&failure==null;}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
    }
    private void render(Surface target,int width,int height,int fps,CountDownLatch ready){
        EGLDisplay display=EGL14.EGL_NO_DISPLAY;EGLContext context=EGL14.EGL_NO_CONTEXT;EGLSurface surface=EGL14.EGL_NO_SURFACE;
        try{
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] v=new int[2];if(!EGL14.eglInitialize(display,v,0,v,1))throw new IllegalStateException("pattern_egl_init");
            EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE};
            if(!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)||count[0]==0)throw new IllegalStateException("pattern_egl_config");
            context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            surface=EGL14.eglCreateWindowSurface(display,configs[0],target,new int[]{EGL14.EGL_NONE},0);
            if(!EGL14.eglMakeCurrent(display,surface,surface,context))throw new IllegalStateException("pattern_egl_current");
            GLES20.glViewport(0,0,width,height);GLES20.glEnable(GLES20.GL_SCISSOR_TEST);ready.countDown();
            long start=SystemClock.elapsedRealtimeNanos(),frame=0,period=1_000_000_000L/Math.max(1,fps);
            while(running&&Thread.currentThread()==thread){
                rect(0,0,width,height,.04f,.04f,.04f);
                for(int band=0;band<8;band++)rect(band*width/8,height*2/3,width/8+1,height/3,(band&4)!=0?1:0,(band&2)!=0?1:0,(band&1)!=0?1:0);
                for(int x=0;x<width;x+=4)rect(x,height/4,1,height/6,1,1,1);
                int motion=(int)((frame*7)%Math.max(1,width));rect(motion,height/2,Math.max(2,width/12),Math.max(2,height/8),0,1,1);
                for(int x=0;x<16;x++){float gray=x/15f;rect(x*width/16,height/12,width/16+1,height/12,gray,gray,gray);}
                number(frame,Math.max(2,width/40),height/2,Math.max(2,height/35));
                number((SystemClock.elapsedRealtimeNanos()-start)/1_000_000_000L,Math.max(2,width/40),height/3,Math.max(2,height/45));
                long pts=SystemClock.elapsedRealtimeNanos();EGLExt.eglPresentationTimeANDROID(display,surface,pts);
                if(!EGL14.eglSwapBuffers(display,surface))throw new IllegalStateException("pattern_swap_failed");
                Runnable observer=frameObserver;if(observer!=null)observer.run();
                log.diagnostic("source_frame","source","test_pattern","frameId",frame,"presentationTimeNs",pts,"width",width,"height",height);
                frame++;long wait=start+frame*period-SystemClock.elapsedRealtimeNanos();if(wait>0)TimeUnit.NANOSECONDS.sleep(wait);
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(RuntimeException e){failure=e;log.diagnostic("pattern_failed","code",e.getMessage());}
        finally{ready.countDown();if(display!=EGL14.EGL_NO_DISPLAY){EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface);if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);}}
    }
    private static void rect(int x,int y,int w,int h,float r,float g,float b){GLES20.glScissor(x,y,w,h);GLES20.glClearColor(r,g,b,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);}
    private static final int[] DIGITS={0x3f,6,0x5b,0x4f,0x66,0x6d,0x7d,7,0x7f,0x6f};
    private static void number(long value,int x,int y,int s){String text=Long.toString(value);for(int i=0;i<text.length();i++){int mask=DIGITS[text.charAt(i)-'0'],left=x+i*s*5;for(int p=0;p<7;p++)if((mask&(1<<p))!=0){int[][] seg={{1,6,2,1},{3,4,1,2},{3,1,1,2},{1,0,2,1},{0,1,1,2},{0,4,1,2},{1,3,2,1}};int[] a=seg[p];rect(left+a[0]*s,y+a[1]*s,a[2]*s,a[3]*s,1,1,1);}}}
    public synchronized void detach(){running=false;Thread active=thread;thread=null;if(active!=null){active.interrupt();if(active!=Thread.currentThread())try{active.join(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
    public void setFrameObserver(Runnable observer){frameObserver=observer;}
}
