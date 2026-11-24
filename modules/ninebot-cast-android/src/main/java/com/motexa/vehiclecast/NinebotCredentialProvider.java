package com.motexa.vehiclecast;

import org.json.JSONObject;

/** Vehicle material is separate from the test application's signed access policy. */
public interface NinebotCredentialProvider {
    /** Logical slot bound by a host adapter to its explicitly selected vehicle. */
    String ACTIVE_VEHICLE = "active";
    /** Returns a complete host material snapshot for one selected vehicle, or null. Never log it. */
    JSONObject load(String vehicleId);
    /** Save a newly authorized material set using the host's encrypted, non-backup vault. */
    void save(String vehicleId, JSONObject material);
    void forget(String vehicleId);

    /** Authorized cloud operations are asynchronous and stay outside the native protocol engine. */
    interface Authorization extends AutoCloseable {
        void execute(String operation, String requestId, JSONObject request, Callback callback);
        @Override void close();
    }
    interface AuthorizationFactory {
        Authorization open(JSONObject v3Material);
    }
    interface Callback {
        void completed(JSONObject result);
        void failed(String code);
    }
}
