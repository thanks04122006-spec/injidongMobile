package com.injidong.measurement;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Blocking upload primitive. Run on a worker; never retries or follows redirects. */
public final class SessionArchiveUpload {
    public static final long MAX_ZIP_BYTES = 128L * 1024 * 1024;
    private final URL endpoint;
    private final String token;
    private final int deadlineMs;
    public static final class Result {
        public final int status;
        public final String body, sha256;
        public final long size;
        Result(int status, String body, String sha256, long size) {
            this.status = status; this.body = body; this.sha256 = sha256; this.size = size;
        }
    }
    public SessionArchiveUpload(String origin, String token) throws Exception {
        this(origin, token, 120000);
    }
    public SessionArchiveUpload(String origin, String token, int deadlineMs) throws Exception {
        if (deadlineMs < 1) throw new IllegalArgumentException("Invalid deadline");
        this.deadlineMs = deadlineMs;
        URI uri = new URI(origin);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Server origin required");
        boolean local = uri.getHost().equalsIgnoreCase("localhost") || uri.getHost().equals("127.0.0.1")
                || uri.getHost().equals("[::1]") || uri.getHost().equals("::1");
        if (!(uri.getScheme().equals("https") || (uri.getScheme().equals("http") && local)))
            throw new IllegalArgumentException("HTTP is allowed only on loopback");
        if (token != null) for (int i = 0; i < token.length(); i++)
            if (token.charAt(i) < 33 || token.charAt(i) > 126) throw new IllegalArgumentException("Invalid token");
        this.token = token;
        this.endpoint = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/api/v1/sessions", null, null).toURL();
    }
    public Result upload(File archive, String sessionId) throws Exception {
        if (!sessionId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid session ID");
        long size = archive.length();
        if (!archive.isFile() || size < 1 || size > MAX_ZIP_BYTES) throw new IOException("Invalid ZIP size");
        String hash = sha256(archive);
        String boundary = "Injidong" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder prefix = new StringBuilder();
        field(prefix, boundary, "session_id", sessionId);
        field(prefix, boundary, "client_zip_size_bytes", Long.toString(size));
        field(prefix, boundary, "client_zip_sha256", hash);
        prefix.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"zip\"; filename=\"session.zip\"\r\nContent-Type: application/zip\r\n\r\n");
        byte[] head = prefix.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection(Proxy.NO_PROXY);
        java.util.Timer deadline = new java.util.Timer("session-upload-deadline", true);
        deadline.schedule(new java.util.TimerTask() {
            @Override public void run() { connection.disconnect(); }
        }, deadlineMs);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Math.min(3000, deadlineMs)); connection.setReadTimeout(deadlineMs);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setFixedLengthStreamingMode(size + head.length + tail.length);
            MessageDigest sentHash = MessageDigest.getInstance("SHA-256");
            long sent = 0;
            try (OutputStream out = connection.getOutputStream(); InputStream in = new FileInputStream(archive)) {
                out.write(head); byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) {
                    sent += n;
                    if (sent > size) throw new IOException("ZIP changed during upload");
                    sentHash.update(buffer, 0, n); out.write(buffer, 0, n);
                }
                if (sent != size || !hex(sentHash.digest()).equals(hash)) throw new IOException("ZIP changed during upload");
                out.write(tail);
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new IOException("Redirect rejected");
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
                throw new IOException("Expected JSON response");
            String encoding = connection.getHeaderField("Content-Encoding");
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("Encoded response rejected");
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) throw new IOException("Missing response");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = stream) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (body.size() + n > 1024*1024) throw new IOException("Response exceeds limit");
                    body.write(buffer, 0, n);
                }
            }
            return new Result(status, new String(body.toByteArray(), StandardCharsets.UTF_8), hash, size);
        } finally { deadline.cancel(); connection.disconnect(); }
    }
    private static void field(StringBuilder out, String boundary, String name, String value) {
        out.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(name)
                .append("\"\r\n\r\n").append(value).append("\r\n");
    }
    public static String sha256(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) hash.update(buffer, 0, n);
        }
        return hex(hash.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(Locale.US, "%02x", value & 255));
        return out.toString();
    }
}
