package com.injidong.measurement;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Optional adapter. No default activation; invoke on a background worker. */
public final class HttpSessionTransport implements SessionTransport {
    public static final String LOCAL_TEST_ORIGIN = "https://lawyers-specific-allied-cigarette.trycloudflare.com";
    private final SessionArchiveUpload uploader;
    public HttpSessionTransport(String origin, String bearerToken) throws Exception {
        uploader = new SessionArchiveUpload(origin, bearerToken);
    }
    @Override public void sendSession(File directory, Callback callback) {
        String code = null;
        try {
            File archive = frozenArchive(directory);
            SessionArchiveUpload.Result result = uploader.upload(archive, directory.getName());
            if (result.status != 201 && result.status != 200) {
                code = result.status == 401 ? "UNAUTHORIZED" : result.status == 409 ? "SESSION_CONFLICT"
                        : result.status == 413 ? "LIMIT_EXCEEDED" : result.status == 422 ? "ZIP_REJECTED" : "HTTP_ERROR";
            } else {
                validateReceipt(result, directory.getName());
            }
        } catch (Exception failure) {
            code = "UPLOAD_FAILED";
        }
        // Callbacks are outside the catch: a UI callback failure cannot cause a second callback.
        if (code == null) callback.onComplete();
        else callback.onError(code, "업로드를 확인하지 못했습니다. 보관된 동일 ZIP으로 재시도할 수 있습니다.");
    }
    private static synchronized File frozenArchive(File directory) throws Exception {
        if (!directory.isDirectory() || !directory.getName().matches("[A-Za-z0-9_-]{1,128}"))
            throw new IOException("Invalid session directory");
        File manifest = new File(directory, "session.json");
        if (manifest.length() > 1024*1024) throw new IOException("Manifest exceeds limit");
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(manifest)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (content.size()+n > 1024*1024) throw new IOException("Manifest exceeds limit");
                content.write(buffer, 0, n);
            }
        }
        JSONObject session = new JSONObject(new String(content.toByteArray(), StandardCharsets.UTF_8));
        if (!"complete".equals(session.getString("status")) || !directory.getName().equals(session.getString("session_id")))
            throw new IOException("Only completed matching sessions can be uploaded");
        File cache = new File(directory.getParentFile(), ".upload-cache");
        if (!cache.mkdirs() && !cache.isDirectory()) throw new IOException("Cannot create upload cache");
        File archive = new File(cache, directory.getName()+".zip");
        if (archive.isFile()) return archive;
        File pending = File.createTempFile(directory.getName()+"-", ".part", cache);
        try {
            try (OutputStream out = new FileOutputStream(pending)) { SessionZipExporter.write(directory, out); }
            if (pending.length() < 1 || pending.length() > SessionArchiveUpload.MAX_ZIP_BYTES)
                throw new IOException("ZIP exceeds limit");
            if (!pending.renameTo(archive)) throw new IOException("Cannot finalize upload ZIP");
            return archive;
        } finally { if (pending.exists()) pending.delete(); }
    }
    private static void validateReceipt(SessionArchiveUpload.Result result, String sid) throws Exception {
        JSONObject receipt = new JSONObject(result.body);
        String expected = result.status == 201 ? "created" : "existing";
        JSONObject session = receipt.getJSONObject("session");
        Object size = session.get("zip_size_bytes");
        if (!expected.equals(receipt.getString("disposition")) || !sid.equals(session.getString("session_id"))
                || !"stored".equals(session.getString("storage_status"))
                || !(size instanceof Integer || size instanceof Long) || ((Number)size).longValue() != result.size
                || !result.sha256.equals(session.getString("zip_sha256"))
                || !("/api/v1/sessions/"+sid+"/archive").equals(session.getString("download_path"))
                || !session.getString("stored_at").matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?Z"))
            throw new IOException("Upload receipt mismatch");
        session.getJSONArray("warnings");
    }
}
