package com.motexa.openninebot;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import org.json.JSONObject;

/** Process-owned visual data; retired sources cannot recolor a later session. */
final class CastVisualFeed {
    enum Family { NINEBOT }
    enum State { OFFLINE, CONNECTING, ONLINE }
    private final Executor ui;
    private volatile Source current;
    private volatile Snapshot snapshot = new Snapshot(null, false, CastPalette.EMPTY, 0);
    volatile boolean visible;
    volatile Runnable listener;

    CastVisualFeed(Executor ui) { this.ui = ui; }

    synchronized Source begin(Family family) {
        Source source = new Source(family);
        current = source;
        snapshot = new Snapshot(family, false, CastPalette.EMPTY, 0);
        changed(source);
        return source;
    }

    synchronized void clear() { clear(null); }
    synchronized void clear(Family family) {
        if (family != null && (current == null || current.family != family)) return;
        current = null;
        snapshot = new Snapshot(null, false, CastPalette.EMPTY, 0);
        changed(null);
    }

    Snapshot snapshot() { return snapshot; }

    private void changed(Source source) {
        ui.execute(() -> { if (current == source) { Runnable callback = listener; if (callback != null) callback.run(); } });
    }

    State state(LabTarget target, JSONObject ninebot) {
        if (!target.casting) return State.OFFLINE;
        if (ninebot == null || terminal(ninebot.optString("stage"))) return State.OFFLINE;
        // A GATT link or a prepared Wi-Fi route alone is not a running frame source.
        if (ninebot.optBoolean("casting")) return ready(Family.NINEBOT) ? State.ONLINE : State.CONNECTING;
        String stage = ninebot.optString("stage");
        return stage.startsWith("wait_") || "scanning".equals(stage) || "connecting".equals(stage)
                || "subscribing".equals(stage) || "gatt_connected".equals(stage) || "services_discovered".equals(stage)
                || "p2p_connecting".equals(stage) || "p2p_releasing".equals(stage) || "auth_key_selected".equals(stage)
                ? State.CONNECTING : State.OFFLINE;
    }

    private boolean ready(Family family) { Snapshot value = snapshot; return value.family == family && value.frameReady; }
    private static boolean terminal(String stage) {
        return stage.isEmpty() || "idle".equals(stage) || "stopped".equals(stage) || "failed".equals(stage)
                || "disconnected".equals(stage) || "released".equals(stage) || "stopping".equals(stage);
    }

    static final class Snapshot {
        final Family family;
        final boolean frameReady;
        final CastPalette palette;
        final long sampledAt;
        Snapshot(Family family, boolean frameReady, CastPalette palette, long sampledAt) {
            this.family = family; this.frameReady = frameReady; this.palette = palette; this.sampledAt = sampledAt;
        }
    }

    final class Source {
        final Family family;
        private volatile boolean sampling = true;
        private long lastSample = -500;
        Source(Family family) { this.family = family; }

        boolean wantsSample(long now) { return current == this && visible && sampling && now - lastSample >= 500; }

        void sample(ByteBuffer pixels, int width, int height, int rowStride, int pixelStride, long now) {
            if (!wantsSample(now)) return;
            lastSample = now;
            CastPalette palette = CastPalette.fromRgba(pixels, width, height, rowStride, pixelStride);
            synchronized (CastVisualFeed.this) {
                if (current != this) return;
                snapshot = new Snapshot(family, snapshot.frameReady, palette, now);
                changed(this);
            }
        }

        void encodedFrame() {
            synchronized (CastVisualFeed.this) {
                if (current != this || snapshot.frameReady) return;
                snapshot = new Snapshot(family, true, snapshot.palette, snapshot.sampledAt);
                changed(this);
            }
        }

        void disableSampling() {
            sampling = false;
            synchronized (CastVisualFeed.this) {
                if (current != this) return;
                snapshot = new Snapshot(family, snapshot.frameReady, CastPalette.EMPTY, 0);
                changed(this);
            }
        }

        void end() { synchronized (CastVisualFeed.this) { if (current == this) clear(family); } }
    }
}
