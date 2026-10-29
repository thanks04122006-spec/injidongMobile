package com.injidong.measurement;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Authenticated API reader for IPC1; no local or synthetic fallback. */
final class PointCloudApi {
    static final class Cloud {
        final float[] xyz, rgb;
        Cloud(float[] xyz, float[] rgb) { this.xyz = xyz; this.rgb = rgb; }
    }
    /** A null result means this uploaded session is still awaiting its server result. */
    static Cloud load(String sessionId) throws Exception {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IOException("세션 ID가 올바르지 않습니다.");
        URI origin = new URI(BuildConfig.API_ORIGIN);
        boolean debugLoopback = BuildConfig.DEBUG && "http".equals(origin.getScheme())
                && ("localhost".equals(origin.getHost()) || "127.0.0.1".equals(origin.getHost()));
        if (!("https".equals(origin.getScheme()) || debugLoopback) || origin.getHost() == null
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/")))
            throw new IOException("HTTPS 서버 주소를 확인하세요.");
        HttpURLConnection connection = (HttpURLConnection)new URI(origin.getScheme(), null, origin.getHost(),
                origin.getPort(), "/api/v1/sessions/" + sessionId + "/pointcloud", null, null).toURL().openConnection();
        java.util.Timer timer = new java.util.Timer("pointcloud-deadline", true);
        timer.schedule(new java.util.TimerTask() {
            @Override public void run() { connection.disconnect(); }
        }, 60000);
        try {
            connection.setConnectTimeout(10000); connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (!BuildConfig.API_TOKEN.isEmpty())
                connection.setRequestProperty("Authorization", "Bearer " + BuildConfig.API_TOKEN);
            int status = connection.getResponseCode();
            if (status == 202) return null;
            if (status == 401) throw new IOException("팀 토큰 인증에 실패했습니다.");
            if (status == 404) throw new IOException("서버 API 또는 검토 데이터가 준비되지 않았습니다.");
            if (status != 200) throw new IOException("서버 응답 오류 (HTTP " + status + ")");
            if (!sessionId.equals(connection.getHeaderField("X-Session-Id")))
                throw new IOException("요청한 세션과 3D 결과가 일치하지 않습니다.");
            String type = connection.getContentType();
            if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/octet-stream"))
                throw new IOException("포인트 클라우드 응답 형식이 올바르지 않습니다.");
            String encoding = connection.getHeaderField("Content-Encoding");
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("지원하지 않는 응답 압축입니다.");
            try (DataInputStream input = new DataInputStream(connection.getInputStream())) {
                if (input.readInt() != 0x49504331) throw new IOException("지원하지 않는 포인트 형식입니다.");
                int count = Integer.reverseBytes(input.readInt());
                if (count < 1 || count > 250000) throw new IOException("표시할 수 있는 점 개수를 초과했습니다.");
                byte[] bytes = new byte[count * 24]; input.readFully(bytes);
                if (input.read() != -1) throw new IOException("포인트 데이터 크기가 일치하지 않습니다.");
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                float[] xyz = new float[count*3], rgb = new float[count*3];
                for (int i = 0; i < count; i++) {
                    for (int a = 0; a < 3; a++) {
                        float value = buffer.getFloat();
                        if (!Float.isFinite(value)) throw new IOException("유효하지 않은 좌표입니다.");
                        xyz[i*3+a] = value;
                    }
                    for (int a = 0; a < 3; a++) {
                        float value = buffer.getFloat();
                        if (!Float.isFinite(value) || value < 0 || value > 1) throw new IOException("유효하지 않은 색상입니다.");
                        rgb[i*3+a] = value;
                    }
                }
                return new Cloud(xyz, rgb);
            }
        } finally { timer.cancel(); connection.disconnect(); }
    }
}
