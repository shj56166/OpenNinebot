package com.motexa.openninebot;
public final class LabApplication extends android.app.Application {
    @Override public void onCreate() { super.onCreate(); LabRuntime.get(this); }
}
