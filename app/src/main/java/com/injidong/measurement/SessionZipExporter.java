package com.injidong.measurement;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streams a finalized session without loading its images or arrays into memory. */
final class SessionZipExporter {
    static void write(File session, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
            zip.setLevel(1);
            add(session, session.getName() + "/", zip, new byte[65536]);
        }
    }

    private static void add(File file, String path, ZipOutputStream zip, byte[] buffer) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        if (file.isDirectory()) {
            zip.closeEntry();
            File[] children = file.listFiles();
            if (children == null) throw new IOException("세션 폴더를 읽을 수 없습니다: " + file.getName());
            for (File child : children) add(child, path + child.getName() + (child.isDirectory() ? "/" : ""), zip, buffer);
        } else {
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
                int count;
                while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
            }
            zip.closeEntry();
        }
    }
}
