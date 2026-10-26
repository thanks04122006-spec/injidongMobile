package com.injidong.measurement;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/** Checks the actual PNG header rather than trusting the RGB8 index label. */
final class RgbPngValidator {
    static void validate(File file, int expectedWidth, int expectedHeight) throws IOException {
        byte[] header = new byte[33];
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            in.readFully(header);
        } catch (IOException error) {
            throw new IOException("RGB PNG를 읽을 수 없습니다: " + file.getName(), error);
        }
        ByteBuffer fields = ByteBuffer.wrap(header);
        if (fields.getLong(0) != 0x89504E470D0A1A0AL
                || fields.getInt(8) != 13 || fields.getInt(12) != 0x49484452)
            throw new IOException("PNG 헤더가 올바르지 않습니다: " + file.getName());
        CRC32 crc = new CRC32();
        crc.update(header, 12, 17);
        if (crc.getValue() != (fields.getInt(29) & 0xffffffffL))
            throw new IOException("PNG 헤더가 손상되었습니다: " + file.getName());
        int width = fields.getInt(16), height = fields.getInt(20);
        int depth = header[24] & 255, colorType = header[25] & 255;
        if (depth != 8 || colorType != 2) {
            String format = colorType == 6 ? "RGBA 4채널" : "PNG color type " + colorType;
            throw new IOException(file.getName() + "의 실제 형식은 " + depth + "비트 " + format
                    + "입니다. RGB8은 알파 채널 없는 8비트 RGB 3채널이어야 합니다.");
        }
        if (expectedWidth <= 0 || expectedHeight <= 0
                || width != expectedWidth || height != expectedHeight)
            throw new IOException(file.getName() + "의 PNG 해상도(" + width + "×" + height
                    + ")가 RGB 인덱스(" + expectedWidth + "×" + expectedHeight + ")와 다릅니다.");
        if (header[26] != 0 || header[27] != 0 || (header[28] != 0 && header[28] != 1))
            throw new IOException("PNG 헤더 설정이 올바르지 않습니다: " + file.getName());
    }
}
