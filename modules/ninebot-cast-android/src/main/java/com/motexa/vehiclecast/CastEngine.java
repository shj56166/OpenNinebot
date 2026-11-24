package com.motexa.vehiclecast;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Serialized, event-driven native protocol session. The host executes returned actions.
 * Every tx must be completed exactly once through transportResult; it is only local I/O
 * completion, never a claim that the instrument displayed a frame.
 */
public final class CastEngine implements AutoCloseable {
    private long handle;

    public CastEngine(JSONObject options, JSONObject credentials) {
        if (options == null) throw new IllegalArgumentException("options");
        handle = NativeCast.create(options.toString(), credentials == null ? "{}" : credentials.toString());
        if (handle == 0) throw new IllegalStateException("native_session_unavailable_or_unauthorized");
    }

    public synchronized JSONArray start() { return call("start", null, null, false, 0); }
    public synchronized JSONArray configure(JSONObject options) { return call("configure", options, null, false, 0); }
    public synchronized JSONArray command(String command, JSONObject arguments) {
        if (command == null) throw new IllegalArgumentException("command");
        JSONObject request = new JSONObject();
        try { request.put("name", command).put("args", arguments == null ? new JSONObject() : arguments); }
        catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
        return call("command", request, null, false, 0);
    }
    public synchronized JSONArray onControl(byte[] bytes) { return call("control", null, bytes, false, 0); }
    public synchronized JSONArray onDatagram(byte[] bytes, boolean control) { return call("datagram", null, bytes, control, 0); }
    public synchronized JSONArray tick() { return call("tick", null, null, false, 0); }
    public synchronized JSONArray submitFrame(byte[] frame, boolean keyframe, long presentationTimeUs) {
        if (frame == null || frame.length == 0) throw new IllegalArgumentException("empty_frame");
        return call("frame", null, frame, keyframe, presentationTimeUs);
    }
    public synchronized JSONArray transportResult(long actionId, int status) {
        JSONObject result = new JSONObject();
        try { result.put("id", actionId).put("status", status); }
        catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
        return call("transport_result", result, null, false, 0);
    }
    private JSONArray call(String operation, JSONObject arguments, byte[] bytes, boolean flag, long value) {
        if (handle == 0) throw new IllegalStateException("session_closed");
        String result = NativeCast.dispatch(handle, operation, arguments == null ? "{}" : arguments.toString(),
                bytes, flag, value, SystemClock.elapsedRealtime(), System.currentTimeMillis());
        try { return new JSONArray(result); }
        catch (JSONException invalidNativeResult) { throw new IllegalStateException("native_action_invalid", invalidNativeResult); }
    }
    @Override public synchronized void close() {
        if (handle != 0) { NativeCast.destroy(handle); handle = 0; }
    }
}
