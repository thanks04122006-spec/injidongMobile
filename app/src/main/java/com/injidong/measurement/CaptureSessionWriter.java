package com.injidong.measurement;

import android.graphics.Bitmap;
import org.json.JSONObject;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a contract-v1.0.0 session plus the images.jsonl RGB sidecar. */
final class CaptureSessionWriter implements AutoCloseable {
    private final File root;
    private final String sessionId;
    private final BufferedWriter frames, images;
    private int rgbImageCount;
    private int depthFrameCount;

    CaptureSessionWriter(File root, String sessionId) throws Exception {
        this.root = root; this.sessionId = sessionId;
        mkdir(root);
        frames = writer(new File(root, "frames.jsonl"));
        images = writer(new File(root, "images.jsonl"));
        mkdir(new File(root, "raw")); mkdir(new File(root, "rgb"));
    }

    synchronized boolean writeDepth(DepthCameraAdapter.CameraFrame frame) throws Exception {
        File frameDir = new File(root, frame.frameId);
        mkdir(frameDir);
        File raw = new File(new File(root, "raw"), frame.frameId + "_depth_z16.bin");
        writeRawZ16(frame, raw);
        File npz = new File(frameDir, "arrays.npz");
        writeNpz(frame, npz);
        String rawRel = relative(raw);
        String rawHash = sha256(raw);
        JSONObject metadata = buildMetadata(frame, rawRel, rawHash, sha256(npz));
        File metadataFile = new File(frameDir, "metadata.json");
        writeText(metadataFile, metadata.toString(2));
        writeRawCaptureEvidence(frame, new File(frameDir, "raw_capture.json"),
                metadataFile, rawRel, rawHash);
        JSONObject frameRow = new JSONObject();
        frameRow.put("frame_id", frame.frameId); frameRow.put("metadata_path", relative(new File(frameDir, "metadata.json")));
        frames.write(frameRow.toString()); frames.newLine(); frames.flush();

        depthFrameCount++;
        return writeD405Rgb(frame);
    }

    synchronized void writeSessionSummary(String status, long imuSampleCount) throws Exception {
        JSONObject session = new JSONObject();
        session.put("manifest_version", "1.0.0"); session.put("session_id", sessionId); session.put("status", status);
        JSONObject sources = new JSONObject(); sources.put("rgb", "intel_realsense_d405_color");
        sources.put("depth", "intel_realsense_d405"); sources.put("imu", "android_phone_sensors");
        session.put("sources", sources);
        JSONObject counts = new JSONObject(); counts.put("depth_frames", depthFrameCount);
        counts.put("rgb_images", rgbImageCount); counts.put("imu_samples", imuSampleCount);
        session.put("counts", counts);
        JSONObject files = new JSONObject(); files.put("depth_index", "frames.jsonl");
        files.put("images_index", "images.jsonl"); files.put("rgb_images", "rgb/");
        files.put("imu_samples", "imu.jsonl"); files.put("depth_arrays", "<frame_id>/arrays.npz");
        files.put("raw_depth", "raw/"); session.put("files", files);
        JSONObject alignment = new JSONObject(); alignment.put("status", "unverified");
        alignment.put("aligned_to_depth", false); alignment.put("evidence", JSONObject.NULL);
        session.put("rgb_depth_alignment", alignment);
        JSONObject transmission = new JSONObject(); transmission.put("status", "not_configured");
        transmission.put("sent", false); transmission.put("reason", "서버 주소 및 RGB·Depth·IMU 요청 계약 미확정");
        session.put("transmission", transmission);
        writeText(new File(root, "session.json"), session.toString(2));
    }

    private synchronized boolean writeD405Rgb(DepthCameraAdapter.CameraFrame frame) throws Exception {
        Bitmap bitmap = frame.rgbPreview;
        if (bitmap == null) return false;
        String imageId = String.format(Locale.US, "d405_rgb_%06d", ++rgbImageCount);
        File image = new File(new File(root, "rgb"), imageId + ".png");
        writePng(bitmap, image);
        JSONObject rgb = new JSONObject();
        rgb.put("frame_id", frame.frameId); rgb.put("session_id", sessionId); rgb.put("file", relative(image));
        rgb.put("sha256", sha256(image)); rgb.put("width", bitmap.getWidth()); rgb.put("height", bitmap.getHeight());
        rgb.put("encoding", "RGB8"); rgb.put("image_kind", "original_rgb");
        JSONObject time = new JSONObject();
        boolean hasRgbTime = frame.rgbCaptureTimeNs > 0 && frame.rgbClockDomain != null;
        time.put("ns", hasRgbTime ? Long.toString(frame.rgbCaptureTimeNs) : JSONObject.NULL);
        time.put("clock_domain", hasRgbTime ? frame.rgbClockDomain : JSONObject.NULL);
        time.put("origin", hasRgbTime ? "sensor" : JSONObject.NULL); rgb.put("time", time);
        rgb.put("pair_status", "unverified"); rgb.put("pair_evidence", JSONObject.NULL);
        rgb.put("aligned_to_depth", false); rgb.put("alignment_method", JSONObject.NULL); rgb.put("calibration_id", JSONObject.NULL);
        rgb.put("provenance_kind", "real"); images.write(rgb.toString()); images.newLine(); images.flush();
        return true;
    }

    private JSONObject buildMetadata(DepthCameraAdapter.CameraFrame f, String rawRel, String rawHash, String npzHash) throws Exception {
        JSONObject m = new JSONObject(); m.put("schema_version", "1.0.0"); m.put("frame_id", f.frameId);
        m.put("session_id", sessionId); m.put("specimen_id", JSONObject.NULL); m.put("source_path", "mobile_device");
        m.put("capture_source", "mobile_device");
        JSONObject provenance = new JSONObject(); provenance.put("kind", "real");
        JSONObject camera = new JSONObject(); camera.put("vendor", "Intel"); camera.put("model", "RealSense D405");
        camera.put("serial", emptyNull(f.serial)); camera.put("firmware", emptyNull(f.firmware));
        camera.put("sdk", emptyNull(f.sdkVersion)); camera.put("depth_profile_id", f.calibrationId);
        provenance.put("camera", camera); provenance.put("raw_file", rawRel); provenance.put("raw_sha256", rawHash);
        provenance.put("raw_unit", "Z16"); provenance.put("raw_scale_to_m", (double) f.depthScaleToMeters);
        provenance.put("synthetic_seed", JSONObject.NULL); provenance.put("synthetic_config", JSONObject.NULL); m.put("provenance", provenance);
        JSONObject storage = new JSONObject(); storage.put("file", "arrays.npz"); storage.put("sha256", npzHash);
        storage.put("depth_key", "depth_m"); storage.put("valid_mask_key", "valid_mask"); storage.put("target_mask_key", JSONObject.NULL); m.put("storage", storage);
        JSONObject depth = new JSONObject(); depth.put("unit", "m"); depth.put("semantics", "optical_axis_z"); depth.put("dtype", "float32");
        depth.put("height", f.height); depth.put("width", f.width); m.put("depth", depth);
        JSONObject intr = new JSONObject(); intr.put("width", f.width); intr.put("height", f.height); intr.put("fx", f.fx); intr.put("fy", f.fy);
        intr.put("cx", f.cx); intr.put("cy", f.cy); intr.put("model", "rectified_pinhole"); intr.put("calibration_id", f.calibrationId); m.put("intrinsics", intr);
        JSONObject time = new JSONObject(); time.put("ns", Long.toString(f.captureTimeNs)); time.put("clock_domain", f.clockDomain); time.put("origin", "sensor");
        m.put("capture_time", time);
        JSONObject receive = new JSONObject(); receive.put("ns", Long.toString(android.os.SystemClock.elapsedRealtimeNanos()));
        receive.put("clock_domain", "android_elapsed_realtime"); receive.put("origin", "host_receive"); m.put("host_receive_time", receive);
        JSONObject replay = new JSONObject(); replay.put("ns", JSONObject.NULL); replay.put("clock_domain", JSONObject.NULL); replay.put("origin", JSONObject.NULL); m.put("replay_time", replay);
        m.put("pose", JSONObject.NULL); m.put("imu", JSONObject.NULL);
        JSONObject sync = new JSONObject(); sync.put("status", "unverified"); sync.put("offset_ns", JSONObject.NULL);
        sync.put("uncertainty_ns", JSONObject.NULL); sync.put("tolerance_ns", JSONObject.NULL); sync.put("evidence", JSONObject.NULL); m.put("sync", sync);
        JSONObject conditions = new JSONObject(); conditions.put("camera_distance_m", JSONObject.NULL); conditions.put("lighting", JSONObject.NULL);
        conditions.put("material", JSONObject.NULL); conditions.put("notes", "Depth arrays.npz is rectified to pinhole pixels with nearest-neighbor remapping when source distortion is BROWN_CONRADY; original Z16 is retained unchanged under raw/. Source distortion model and coefficients are encoded in calibration_id. D405 RGB and Depth are read from the same SDK frameset; RGB/Depth pixel alignment and cross-stream timestamp correspondence remain unverified."); m.put("capture_conditions", conditions);
        m.put("target_mask_info", JSONObject.NULL); m.put("support_plane", JSONObject.NULL); m.put("volume_request", JSONObject.NULL);
        return m;
    }

    /** App-specific evidence sidecar; not part of the FramePacket v1.0.0 schema. */
    private void writeRawCaptureEvidence(DepthCameraAdapter.CameraFrame f, File file,
                                         File metadataFile, String rawRel, String rawHash) throws Exception {
        JSONObject evidence = new JSONObject();
        evidence.put("evidence_format", "mobile-raw-capture-evidence-v1");
        evidence.put("session_id", sessionId); evidence.put("frame_id", f.frameId);
        evidence.put("paths_relative_to", "session_root");
        evidence.put("metadata_file", relative(metadataFile));
        evidence.put("metadata_sha256", sha256(metadataFile));
        evidence.put("raw_file", rawRel); evidence.put("raw_sha256", rawHash);
        evidence.put("raw_stride_bytes", f.rawDepthStrideBytes);
        evidence.put("raw_stride_source", "sdk_getStride");
        evidence.put("raw_width", f.width); evidence.put("raw_height", f.height);
        evidence.put("raw_bytes_per_pixel", 2);
        evidence.put("raw_byte_order", "little_endian");
        evidence.put("raw_byte_order_source", "decoder_convention_not_sdk_query");
        evidence.put("raw_data_size_bytes", f.rawDepthZ16.length);
        writeText(file, evidence.toString(2));
    }

    private void writeNpz(DepthCameraAdapter.CameraFrame f, File file) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            zip.setLevel(Deflater.BEST_SPEED);
            zip.putNextEntry(new ZipEntry("depth_m.npy")); writeNpyHeader(zip, "<f4", f.height, f.width);
            ByteBuffer floats = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
            for (float v : f.depthMeters) { if (floats.remaining() < 4) { zip.write(floats.array(), 0, floats.position()); floats.clear(); } floats.putFloat(v); }
            zip.write(floats.array(), 0, floats.position()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("valid_mask.npy")); writeNpyHeader(zip, "|b1", f.height, f.width);
            byte[] mask = new byte[f.validMask.length]; for (int i = 0; i < mask.length; i++) mask[i] = (byte) (f.validMask[i] ? 1 : 0);
            zip.write(mask); zip.closeEntry();
        }
    }

    private void writeNpyHeader(ZipOutputStream out, String dtype, int h, int w) throws Exception {
        String header = String.format(Locale.US, "{'descr': '%s', 'fortran_order': False, 'shape': (%d, %d), }", dtype, h, w);
        int padding = (16 - ((10 + header.length() + 1) % 16)) % 16;
        byte[] bytes = (header + repeat(' ', padding) + "\n").getBytes(StandardCharsets.US_ASCII);
        out.write(new byte[]{(byte) 0x93, 'N', 'U', 'M', 'P', 'Y', 1, 0, (byte) (bytes.length & 255), (byte) ((bytes.length >>> 8) & 255)});
        out.write(bytes);
    }
    private void writeRawZ16(DepthCameraAdapter.CameraFrame f, File file) throws Exception {
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file))) { out.write(f.rawDepthZ16); }
    }
    private void writePng(Bitmap bitmap, File file) throws Exception { try (FileOutputStream out = new FileOutputStream(file)) { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new java.io.IOException("PNG 인코딩 실패"); } }
    private static BufferedWriter writer(File file) throws Exception { return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)); }
    private static void writeText(File f, String s) throws Exception { try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(s); } }
    private static void mkdir(File d) throws Exception { if (!d.mkdirs() && !d.isDirectory()) throw new java.io.IOException("폴더 생성 실패: " + d); }
    private String relative(File f) { return root.toURI().relativize(f.toURI()).getPath().replace('\\', '/'); }
    private static String sha256(File file) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256"); byte[] b = new byte[65536]; int n;
        try (java.io.InputStream in = new java.io.FileInputStream(file)) { while ((n = in.read(b)) > 0) d.update(b, 0, n); }
        StringBuilder s = new StringBuilder(); for (byte v : d.digest()) s.append(String.format(Locale.US, "%02x", v & 255)); return s.toString();
    }
    private static Object emptyNull(String value) { return value == null || value.trim().isEmpty() ? JSONObject.NULL : value; }
    private static String repeat(char c, int n) { StringBuilder s = new StringBuilder(); for (int i = 0; i < n; i++) s.append(c); return s.toString(); }
    @Override public synchronized void close() throws Exception { frames.close(); images.close(); }
}
