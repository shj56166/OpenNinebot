package com.motexa.vehiclecast;

/** JNI seam only. Wire formats and mutable authentication state live in native code. */
final class NativeCast {
    static { NativeRuntime.ensureLoaded(); }
    private NativeCast() {}
    static native long create(String options, String credentials);
    static native String dispatch(long handle, String operation, String arguments, byte[] bytes,
                                  boolean flag, long value, long elapsedMillis, long wallMillis);
    static native void destroy(long handle);
}
