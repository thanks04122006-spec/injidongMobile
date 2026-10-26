package com.injidong.measurement;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** RGB file/index preflight, run off the UI thread before opening the ZIP picker. */
final class RgbExportValidator {
    static void validate(File session) throws Exception {
        File index = new File(session, "images.jsonl");
        if (!index.isFile()) throw new IOException("RGB 인덱스 images.jsonl이 없습니다.");
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(index), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JSONObject row = new JSONObject(line);
                if (!"RGB8".equals(row.getString("encoding")))
                    throw new IOException("RGB 인덱스의 encoding이 RGB8이 아닙니다.");
                File image = new File(session, row.getString("file")).getCanonicalFile();
                if (!image.getPath().startsWith(session.getCanonicalPath() + File.separator))
                    throw new IOException("RGB 파일 경로가 세션 폴더를 벗어납니다.");
                RgbPngValidator.validate(image, positiveInteger(row, "width"), positiveInteger(row, "height"));
                if (!sha256(image).equals(row.getString("sha256")))
                    throw new IOException(image.getName() + "의 파일 해시가 RGB 인덱스와 다릅니다.");
                count++;
            }
        }
        StringBuilder manifestText = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(session, "session.json")), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) manifestText.append(line);
        }
        Object declared = new JSONObject(manifestText.toString()).getJSONObject("counts").get("rgb_images");
        if (!(declared instanceof Integer || declared instanceof Long)
                || ((Number) declared).longValue() != count)
            throw new IOException("세션의 RGB 저장 개수와 images.jsonl의 이미지 수가 다릅니다.");
    }

    private static int positiveInteger(JSONObject row, String key) throws Exception {
        Object value = row.get(key);
        if (!(value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() <= 0 || ((Number) value).longValue() > Integer.MAX_VALUE)
            throw new IOException("RGB 인덱스의 " + key + "는 양의 정수여야 합니다.");
        return ((Number) value).intValue();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        try (FileInputStream input = new FileInputStream(file)) {
            int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.US, "%02x", b & 255));
        return hex.toString();
    }
}
