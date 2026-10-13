package com.injidong.measurement;

import org.json.JSONObject;

/** Transport boundary. Bind endpoint, auth, request envelope and array encoding once the server API is agreed. */
public interface PredictionTransport {
    interface Callback { void onResult(JSONObject predictionResult); void onError(String code, String message); }
    void submitFrame(JSONObject frameMetadata, float[] depthMeters, boolean[] validMask, Callback callback);
}
