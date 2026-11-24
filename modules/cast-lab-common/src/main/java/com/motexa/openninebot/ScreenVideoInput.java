package com.motexa.openninebot;

import android.hardware.display.VirtualDisplay;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.view.Surface;

final class ScreenVideoInput implements CastVideoInput,AutoCloseable {
    private final MediaProjection projection;
    private final int density;
    private final SessionLog log;
    private VirtualDisplay display;
    ScreenVideoInput(MediaProjection p,int density,SessionLog log){projection=p;this.density=density;this.log=log;}
    public synchronized boolean attach(Surface surface,int width,int height,int fps){
        try{if(display==null)display=projection.createVirtualDisplay("OpenNinebot screen",width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,null);
            else{display.resize(width,height,density);display.setSurface(surface);}
            log.diagnostic("projection_display","width",width,"height",height,"densityDpi",density,"ready",display!=null);return display!=null;
        }catch(RuntimeException e){log.diagnostic("projection_display_failed","category",e.getClass().getSimpleName());return false;}
    }
    public synchronized void detach(){if(display!=null)try{display.setSurface(null);}catch(RuntimeException ignored){}}
    public synchronized void close(){if(display!=null){display.release();display=null;log.diagnostic("projection_display_released","released",true);}}
}
