package com.injidong.measurement;

import java.io.File;

/** Upload boundary for a complete RGB, Depth, and IMU capture session. */
public interface SessionTransport {
    interface Callback { void onComplete(); void onError(String code, String message); }
    void sendSession(File sessionDirectory, Callback callback);
}
