package com.motexa.vehiclecast;

/** Selects the host library containing the Ninebot protocol and media core. */
public final class NativeRuntime {
    private static String library;
    private NativeRuntime(){}
    public static synchronized void load(String name){
        if(name==null||!name.matches("[A-Za-z0-9_]{1,80}"))throw new IllegalArgumentException("native_library_name");
        if(library!=null){if(!library.equals(name))throw new IllegalStateException("native_library_already_loaded");return;}
        System.loadLibrary(name);library=name;
    }
    static synchronized void ensureLoaded(){if(library==null)load("ninebot_cast");}
}
