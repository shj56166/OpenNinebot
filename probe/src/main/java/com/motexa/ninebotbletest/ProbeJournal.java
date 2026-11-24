package com.motexa.ninebotbletest;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Private, append-only local evidence. No account login or credential storage. */
final class ProbeJournal implements AutoCloseable {
    final File file;
    private final FileOutputStream output;

    ProbeJournal(Context context) throws Exception {
        File directory = new File(context.getFilesDir(), "captures");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("capture-directory");
        file = new File(directory, "probe-" + System.currentTimeMillis() + ".ndjson");
        output = new FileOutputStream(file);
        event("journal", "appVersion", "0.1.0", "realVehicleValidated", false);
    }

    synchronized void event(String type, Object... pairs) {
        try {
            JSONObject json = new JSONObject();
            json.put("timeMs", System.currentTimeMillis());
            json.put("elapsedMs", android.os.SystemClock.elapsedRealtime());
            json.put("event", type);
            for (int i = 0; i < pairs.length; i += 2) json.put(String.valueOf(pairs[i]), pairs[i + 1]);
            output.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (Exception error) {
            android.util.Log.e("NinebotProbe", "journal-write-failed", error);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    synchronized void exportTo(OutputStream destination) throws Exception {
        output.flush();
        java.nio.file.Files.copy(file.toPath(), destination);
    }

    @Override public synchronized void close() {
        try { output.close(); } catch (Exception ignored) { }
    }
}
