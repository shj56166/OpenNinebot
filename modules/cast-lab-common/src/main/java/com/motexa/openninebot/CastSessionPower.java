package com.motexa.openninebot;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/** Keep an active media session scheduled; never keep the screen on or override projection revocation. */
final class CastSessionPower implements AutoCloseable {
    private static final long LEASE_MS = 120_000, RENEW_MS = 30_000;
    private final Context context;
    private final SessionLog log;
    private final PowerManager power;
    private final KeyguardManager keyguard;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private boolean registered;
    private final Runnable renew = new Runnable() {
        public void run() {
            if (wakeLock == null) return;
            wakeLock.acquire(LEASE_MS);
            handler.postDelayed(this, RENEW_MS);
        }
    };
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        public void onReceive(Context ignored, Intent intent) { record(intent.getAction()); }
    };

    CastSessionPower(Context context, SessionLog log) {
        this.context = context; this.log = log;
        power = context.getSystemService(PowerManager.class);
        keyguard = context.getSystemService(KeyguardManager.class);
    }

    void start() {
        if (wakeLock != null) return;
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motexa:cast-media");
        wakeLock.setReferenceCounted(false);
        renew.run();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(screen, filter);
        registered = true;
        record("session_start");
    }

    boolean interactive() { return power.isInteractive(); }
    boolean locked() { return keyguard != null && keyguard.isKeyguardLocked(); }
    boolean held() { return wakeLock != null && wakeLock.isHeld(); }

    private void record(String reason) {
        log.diagnostic("casting_screen_state", "reason", reason, "interactive", interactive(),
            "keyguardLocked", locked(), "cpuWakeLockHeld", held());
    }

    public void close() {
        handler.removeCallbacks(renew);
        try { if (registered) { registered = false; context.unregisterReceiver(screen); } }
        finally {
            PowerManager.WakeLock previous = wakeLock; wakeLock = null;
            if (previous != null && previous.isHeld()) previous.release();
            record("session_stop");
        }
    }
}
