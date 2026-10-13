package com.injidong.measurement;

import java.io.File;

/** Prevents uploads to an invented URL or undocumented payload format. */
public final class UnconfiguredSessionTransport implements SessionTransport {
    @Override public void sendSession(File sessionDirectory, Callback callback) {
        callback.onError("SERVER_API_UNCONFIGURED", "서버 주소 및 RGB·Depth·IMU 세션 업로드 계약이 미정입니다.");
    }
}
