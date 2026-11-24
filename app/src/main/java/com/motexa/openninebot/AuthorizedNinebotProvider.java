package com.motexa.openninebot;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import com.motexa.vehiclecast.NinebotCredentialProvider;
import com.motexa.vehiclecast.NinebotCredentialProvider.Callback;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/**
 * Explicit contract for a user-authorized HTTPS material service, not an inferred official cloud API.
 * Request/response: {schemaVersion:1,operation,requestId,sn,request} /
 * {schemaVersion:1,operation,requestId,success,result}. The configured service must implement this contract.
 */
public final class AuthorizedNinebotProvider implements NinebotCredentialProvider.Authorization {
    public static final String ADAPTER = "authorized-ninebot-v1";
    private static final Set<String> OPERATIONS = new HashSet<>(Arrays.asList("challenge", "response", "verify", "refresh"));
    private final Context context;
    private final JSONObject api;
    private final String sn;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean closed;
    private volatile HttpsURLConnection connection;

    public AuthorizedNinebotProvider(Context context, JSONObject v3) {
        this.context = context.getApplicationContext();
        JSONObject configured = v3 == null ? null : v3.optJSONObject("authApi");
        if (configured == null || !ADAPTER.equals(configured.optString("adapter"))) throw new IllegalArgumentException("authorized_provider_adapter_required");
        try { api = new JSONObject(configured.toString()); }
        catch (Exception invalid) { throw new IllegalArgumentException("authorized_provider_config_invalid"); }
        sn = v3.optString("sn");
        String token = api.optString("token");
        if (sn.isEmpty() || token.isEmpty() || token.length() > 8192 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("authorized_provider_identity_or_token_invalid");
        endpoint(api.optString("endpoint"));
    }
    @Override public void execute(String operation, String requestId, JSONObject request, Callback callback) {
        if (!OPERATIONS.contains(operation) || requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,128}")) {
            callback.failed("authorized_provider_operation_invalid"); return;
        }
        if (closed || !busy.compareAndSet(false, true)) { callback.failed("authorized_provider_busy_or_closed"); return; }
        JSONObject body = NinebotTestProfile.json("schemaVersion", 1, "operation", operation, "requestId", requestId, "sn", sn,
                "request", request == null ? new JSONObject() : request);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65536) { busy.set(false); callback.failed("authorized_provider_request_too_large"); return; }
        try { io.execute(() -> {
            JSONObject result = null; String failure = null;
            try {
                if (closed) return;
                long began = SystemClock.elapsedRealtime();
                URI uri = endpoint(api.optString("endpoint"));
                Network network = internetNetwork();
                if (network == null) throw new ProviderFailure("authorized_provider_internet_network_unavailable");
                HttpsURLConnection active = (HttpsURLConnection) network.openConnection(uri.toURL());
                connection = active;
                if (closed) return;
                active.setRequestMethod("POST"); active.setInstanceFollowRedirects(false);
                active.setConnectTimeout(8000); active.setReadTimeout(10000); active.setDoOutput(true);
                active.setUseCaches(false); active.setFixedLengthStreamingMode(bytes.length);
                active.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                active.setRequestProperty("Accept", "application/json"); active.setRequestProperty("Authorization", "Bearer " + api.optString("token"));
                try (OutputStream output = active.getOutputStream()) { output.write(bytes); }
                if (closed || SystemClock.elapsedRealtime() - began > 20000) throw new ProviderFailure("authorized_provider_request_timeout");
                int status = active.getResponseCode();
                if (status != 200) throw new ProviderFailure(status >= 300 && status < 400 ? "authorized_provider_redirect_rejected" : "authorized_provider_http_" + status);
                byte[] response;
                try (InputStream input = active.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (closed || SystemClock.elapsedRealtime() - began > 20000 || out.size() + count > 65536)
                            throw new ProviderFailure("authorized_provider_response_limit");
                        out.write(buffer, 0, count);
                    }
                    response = out.toByteArray();
                }
                try { result = validateResponse(new String(response, StandardCharsets.UTF_8), operation, requestId); }
                finally { Arrays.fill(response, (byte) 0); }
                if (!operation.equals("refresh") && !sn.equals(result.opt("sn"))) throw new ProviderFailure("authorized_provider_serial_mismatch");
            } catch (ProviderFailure error) { failure = error.code; }
            catch (Exception error) { failure = "authorized_provider_" + error.getClass().getSimpleName(); }
            finally { Arrays.fill(bytes, (byte) 0); HttpsURLConnection active = connection; connection = null; if (active != null) active.disconnect(); busy.set(false); }
            if (!closed) { if (failure == null) callback.completed(result); else callback.failed(failure); }
        }); } catch (RejectedExecutionException stopped) {
            Arrays.fill(bytes, (byte) 0); busy.set(false);
            if (!closed) callback.failed("authorized_provider_closed");
        }
    }
    static JSONObject validateResponse(String response, String operation, String requestId) throws Exception {
        JSONObject envelope = NinebotTestProfile.strictObject(response);
        Object version = envelope.opt("schemaVersion"), success = envelope.opt("success");
        if (!(version instanceof Integer) || (Integer) version != 1 || !(success instanceof Boolean)
                || !operation.equals(envelope.opt("operation")) || !requestId.equals(envelope.opt("requestId")))
            throw new ProviderFailure("authorized_provider_response_binding_invalid");
        if (!(Boolean) success) throw new ProviderFailure("authorized_provider_denied");
        if (!(envelope.opt("result") instanceof JSONObject)) throw new ProviderFailure("authorized_provider_result_invalid");
        return envelope.getJSONObject("result");
    }
    private static URI endpoint(String text) {
        try {
            URI value = new URI(text);
            if (!"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null || value.getUserInfo() != null || value.getFragment() != null)
                throw new IllegalArgumentException();
            return value;
        } catch (Exception invalid) { throw new IllegalArgumentException("authorized_provider_https_endpoint_required"); }
    }
    private Network internetNetwork() {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return null;
        Network active = manager.getActiveNetwork();
        if (hasInternet(manager, active)) return active;
        for (Network candidate : manager.getAllNetworks()) if (hasInternet(manager, candidate)) return candidate;
        return null;
    }
    private static boolean hasInternet(ConnectivityManager manager, Network network) {
        NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
    @Override public void close() { closed = true; HttpsURLConnection active = connection; if (active != null) active.disconnect(); io.shutdownNow(); }
    private static final class ProviderFailure extends Exception {
        final String code; ProviderFailure(String code) { super(code); this.code = code; }
    }
}
