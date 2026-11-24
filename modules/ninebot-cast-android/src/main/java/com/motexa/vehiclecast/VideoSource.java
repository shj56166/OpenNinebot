package com.motexa.vehiclecast;

import android.view.Surface;

/** Host-owned pixels feeding either codec, independent of target protocol. */
public interface VideoSource extends AutoCloseable {
    boolean attach(Surface surface,int width,int height,int fps);
    void detach();
    default void setFrameObserver(Runnable observer){}
    @Override default void close(){detach();}
}
