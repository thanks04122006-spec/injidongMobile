package com.injidong.measurement;

import org.json.JSONObject;

/** Deliberately prevents accidental requests to a guessed endpoint. */
public final class UnconfiguredPredictionTransport implements PredictionTransport {
    @Override public void submitFrame(JSONObject metadata, float[] depth, boolean[] valid, Callback callback) {
        callback.onError("SERVER_API_UNCONFIGURED", "서버 주소와 FramePacket 전송 형식이 정해지면 전송 어댑터를 연결합니다.");
    }
}
