package com.injidong.measurement;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RealSense Android-wrapper adapter. Reflection keeps the demo buildable without
 * the proprietary/unpublished AAR; place librealsense.aar in app/libs to enable it.
 */
public final class RealSenseDepthCameraAdapter implements DepthCameraAdapter {
    private static final String SDK = "com.intel.realsense.librealsense.";
    private static final String TAG = "InjidongRealSense";
    private final Context appContext;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService worker;
    private Object context, pipeline, config;
    private Listener listener;
    private String lastRgbDiagnostic;

    public RealSenseDepthCameraAdapter(Context context) { this.appContext = context.getApplicationContext(); }
    @Override public String name() { return "Intel RealSense SDK 2.0 Android wrapper"; }
    @Override public boolean supports(UsbDevice device) { return device != null && device.getVendorId() == 0x8086; }
    public boolean isSdkAvailable() {
        try { Class.forName(SDK + "RsContext"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    @Override public synchronized void connect(UsbDevice usb, Listener callback) {
        disconnect();
        listener = callback;
        if (usb == null || !supports(usb)) {
            callback.onError("USB_DEVICE_UNSUPPORTED", "선택된 USB 장치가 Intel(VID 8086) 장치가 아닙니다.");
            return;
        }
        running.set(true);
        lastRgbDiagnostic = null;
        postRgbDiagnostic("USB 장치 확인됨 · VID:PID " + String.format(Locale.US, "%04X:%04X", usb.getVendorId(), usb.getProductId())
                + " · SDK 초기화 중");
        worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "realsense-stream"); t.setDaemon(true); return t; });
        worker.execute(() -> stream(usb));
    }

    private void stream(UsbDevice usb) {
        try {
            postStatus("RealSense SDK 초기화 중…");
            Log.i(TAG, "Initializing SDK for USB " + usb.getVendorId() + ":" + usb.getProductId());
            Class<?> rsContext = Class.forName(SDK + "RsContext");
            rsContext.getMethod("init", Context.class).invoke(null, appContext);
            context = rsContext.getConstructor().newInstance();
            Class<?> deviceListener = Class.forName(SDK + "DeviceListener");
            Object callback = java.lang.reflect.Proxy.newProxyInstance(deviceListener.getClassLoader(), new Class<?>[]{deviceListener}, (proxy, method, args) -> {
                if ("onDeviceDetach".equals(method.getName())) {
                    running.set(false);
                    postError("REALSENSE_USB_DETACHED", "RealSense USB 연결이 끊겼습니다.");
                }
                return null;
            });
            context.getClass().getMethod("setDevicesChangedCallback", deviceListener).invoke(context, callback);
            UsbManager usbManager = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
            boolean hasPermission = usbManager != null && usbManager.hasPermission(usb);
            postRgbDiagnostic("USB 장치 확인됨 · VID:PID " + String.format(Locale.US, "%04X:%04X", usb.getVendorId(), usb.getProductId())
                    + " · Android USB 권한 " + (hasPermission ? "허용됨" : "없음/SDK 응답 대기") + "\nSDK 장치 목록 확인 중");
            postStatus(hasPermission
                    ? "Android USB 권한 허용됨 · SDK 장치 등록 확인 중…"
                    : "USB 장치는 확인됨 · Android USB 권한 응답 대기 중…");
            Log.i(TAG, "USB permission=" + hasPermission + ", waiting for SDK device enumeration");
            awaitSdkDevice(usb, 60_000);
            postStatus("SDK 장치 확인됨 · D405 RGB/Depth 프로파일 설정 중…");
            pipeline = Class.forName(SDK + "Pipeline").getConstructor().newInstance();
            config = Class.forName(SDK + "Config").getConstructor().newInstance();
            Class<?> streamType = Class.forName(SDK + "StreamType");
            Class<?> streamFormat = Class.forName(SDK + "StreamFormat");
            Object depth = enumValue(streamType, "DEPTH");
            Object color = enumValue(streamType, "COLOR");
            Object z16 = enumValue(streamFormat, "Z16");
            Object rgb8 = enumValue(streamFormat, "RGB8");
            Method enable = config.getClass().getMethod("enableStream", streamType, int.class, int.class, int.class, streamFormat, int.class);
            int[][] profileCandidates = {
                    {640, 480, 640, 480, 15},
                    {640, 480, 848, 480, 15},
                    {848, 480, 848, 480, 15},
                    {640, 480, 640, 480, 30},
                    {848, 480, 848, 480, 30},
                    {640, 480, 848, 480, 30}
            };
            StringBuilder candidateFailures = new StringBuilder();
            Object profile = null;
            boolean colorEnabled = false;
            String selectedRgbProfile = null;
            for (int i = 0; i < profileCandidates.length && running.get(); i++) {
                int[] candidate = profileCandidates[i];
                String candidateName = "Depth " + candidate[0] + "×" + candidate[1] + " Z16@" + candidate[4]
                        + " + RGB " + candidate[2] + "×" + candidate[3] + " RGB8@" + candidate[4];
                postRgbDiagnostic("D405 RGB·Depth 프로파일 후보 " + (i + 1) + "/" + profileCandidates.length + " 확인 중\n" + candidateName);
                closeQuietly(config);
                config = Class.forName(SDK + "Config").getConstructor().newInstance();
                try {
                    enable.invoke(config, depth, -1, candidate[0], candidate[1], z16, candidate[4]);
                    enable.invoke(config, color, -1, candidate[2], candidate[3], rgb8, candidate[4]);
                    boolean canResolve = (boolean) config.getClass().getMethod("canResolve", pipeline.getClass()).invoke(config, pipeline);
                    if (!canResolve) throw new IllegalStateException("SDK canResolve=false");
                    postStatus("SDK 장치 확인됨 · RGB·Depth 스트림 시작 중…");
                    profile = pipeline.getClass().getMethod("start", config.getClass()).invoke(pipeline, config);
                    colorEnabled = true;
                    selectedRgbProfile = candidateName;
                    Log.i(TAG, "Started D405 RGB/Depth profile: " + candidateName);
                    break;
                } catch (Throwable candidateError) {
                    String reason = rootMessage(candidateError);
                    if (candidateFailures.length() > 0) candidateFailures.append("\n");
                    candidateFailures.append(candidateName).append(" → ").append(reason);
                    Log.w(TAG, "D405 RGB/Depth profile rejected: " + candidateName + " / " + reason, candidateError);
                    try { pipeline.getClass().getMethod("stop").invoke(pipeline); } catch (Throwable ignored) { }
                    closeQuietly(pipeline);
                    pipeline = Class.forName(SDK + "Pipeline").getConstructor().newInstance();
                }
            }
            if (!colorEnabled) {
                postStatus("RGB 프로파일을 사용할 수 없음 · Depth 전용 스트림 시작 중…");
                closeQuietly(config);
                pipeline = Class.forName(SDK + "Pipeline").getConstructor().newInstance();
                config = Class.forName(SDK + "Config").getConstructor().newInstance();
                enable.invoke(config, depth, -1, 640, 480, z16, 30);
                profile = pipeline.getClass().getMethod("start", config.getClass()).invoke(pipeline, config);
            }
            closeQuietly(profile);

            String sdkVersion = (String) rsContext.getMethod("getVersion").invoke(null);
            String serial = readDeviceInfo(usb, "SERIAL_NUMBER");
            String firmware = readDeviceInfo(usb, "FIRMWARE_VERSION");
            Log.i(TAG, colorEnabled ? "RGB/Depth pipeline started; waiting for frames" : "Depth-only fallback pipeline started");
            postConnected("Intel", "RealSense D405", firmware, sdkVersion);
            postStatus(colorEnabled ? "D405 RGB/Depth 연결됨 · 첫 프레임 대기 중…"
                    : "D405 Depth 연결됨 · RGB 프로파일을 열지 못해 Depth 미리보기만 사용 중");
            if (colorEnabled) postRgbDiagnostic("RGB+Depth 프로파일 시작 성공\n선택: " + selectedRgbProfile + "\n실제 프레임 정보 수신 대기 중");
            else postRgbDiagnostic("Depth 전용 연결 성공 · 지원되는 RGB·Depth 조합을 찾지 못했습니다.\n시도 결과:\n"
                    + (candidateFailures.length() == 0 ? "시도되지 않음" : candidateFailures));

            Class<?> streamTypeClass = streamType;
            long lastUiFrameMs = 0;
            while (running.get()) {
                Object frames = pipeline.getClass().getMethod("waitForFrames", int.class).invoke(pipeline, 2500);
                try {
                    long nowMs = android.os.SystemClock.elapsedRealtime();
                    if (nowMs - lastUiFrameMs < 200) continue; // bound conversion/bitmap allocations to 5 previews per second
                    lastUiFrameMs = nowMs;
                    Object depthFrame = frames.getClass().getMethod("first", streamTypeClass).invoke(frames, depth);
                    Object colorFrame = colorEnabled
                            ? frames.getClass().getMethod("first", streamTypeClass).invoke(frames, color) : null;
                    if (depthFrame == null) throw new IllegalStateException("D405 Depth 프레임이 FrameSet에 없습니다.");
                    if (colorEnabled && colorFrame == null)
                        postRgbDiagnostic("Depth 프레임 수신 중 · 같은 FrameSet에 COLOR 프레임 없음\n선택 프로파일: " + selectedRgbProfile
                                + "\n가능 원인: 프로파일 누락, USB 대역폭·전원, SDK FrameSet 구성");
                    try { deliverFrame(depthFrame, colorFrame, serial, firmware, sdkVersion); }
                    finally { closeQuietly(depthFrame); closeQuietly(colorFrame); }
                } finally { closeQuietly(frames); }
            }
        } catch (Throwable error) {
            if (running.get()) {
                postError("REALSENSE_STREAM_FAILED", rootMessage(error));
                postRgbDiagnostic("SDK/스트림 오류 · " + rootMessage(error)
                        + "\n확인: USB 권한, SDK 장치 검색, D405 지원 프로파일, OTG 전원");
            }
        } finally {
            running.set(false);
            closeQuietly(config); config = null;
            try { if (pipeline != null) pipeline.getClass().getMethod("stop").invoke(pipeline); } catch (Throwable ignored) { }
            closeQuietly(pipeline); pipeline = null;
            closeQuietly(context); context = null;
        }
    }

    private void awaitSdkDevice(UsbDevice usb, long timeoutMs) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (running.get() && android.os.SystemClock.elapsedRealtime() < deadline) {
            Object devices = context.getClass().getMethod("queryDevices").invoke(context);
            int count;
            try { count = (int) devices.getClass().getMethod("getDeviceCount").invoke(devices); }
            finally { closeQuietly(devices); }
                    if (count > 0) {
                        Log.i(TAG, "SDK enumerated " + count + " Intel device(s)");
                        postRgbDiagnostic("SDK 장치 검색 성공 · Intel 장치 " + count + "개\nRGB/Depth 프로파일 설정으로 진행");
                        return;
                    }
                    if ((android.os.SystemClock.elapsedRealtime() + 300) % 3000 < 300) {
                        UsbManager manager = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
                        Log.i(TAG, "SDK device count=0; Android USB permission=" + (manager != null && manager.hasPermission(usb)));
                        postStatus(manager != null && manager.hasPermission(usb)
                                ? "USB 권한 허용됨 · SDK 장치 등록 대기 중…"
                                : "Android USB 권한 응답 대기 중…");
                        postRgbDiagnostic("SDK 장치 검색 결과 0개 · USB 권한 "
                                + (manager != null && manager.hasPermission(usb) ? "허용됨" : "없음")
                                + "\n가능 원인: SDK USB 등록 지연, Android USB 권한, OTG/전원 또는 SDK 장치 검색 실패");
                    }
            Thread.sleep(300); // the first attach notification precedes the Android USB permission response
        }
        if (running.get()) {
            UsbManager manager = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
            boolean hasPermission = manager != null && manager.hasPermission(usb);
            throw new IllegalStateException(hasPermission
                    ? "Android USB 권한은 허용됐지만 SDK 장치 목록에 D405가 없습니다. 로그에서 USB 열기/SDK 초기화 오류를 확인하세요."
                    : "Android USB 장치 권한이 아직 허용되지 않았습니다.");
        }
        throw new IllegalStateException("RealSense USB 장치가 연결 해제되었습니다.");
    }

    private void deliverFrame(Object depthFrame, Object colorFrame, String serial, String firmware, String sdkVersion) throws Exception {
        Object videoExt = enumValue(Class.forName(SDK + "Extension"), "VIDEO_FRAME");
        Object depthExt = enumValue(Class.forName(SDK + "Extension"), "DEPTH_FRAME");
        Object typedDepth = depthFrame.getClass().getMethod("as", Class.forName(SDK + "Extension")).invoke(depthFrame, depthExt);
        Object videoDepth = depthFrame.getClass().getMethod("as", Class.forName(SDK + "Extension")).invoke(depthFrame, videoExt);
        int width = (int) videoDepth.getClass().getMethod("getWidth").invoke(videoDepth);
        int height = (int) videoDepth.getClass().getMethod("getHeight").invoke(videoDepth);
        int depthStride = (int) videoDepth.getClass().getMethod("getStride").invoke(videoDepth);
        byte[] z16 = readBytes(depthFrame);
        int pixelCount = Math.multiplyExact(width, height);
        float scale = (float) typedDepth.getClass().getMethod("getUnits").invoke(typedDepth);
        if (!(scale > 0) || width <= 0 || height <= 0)
            throw new IllegalStateException("Depth profile 크기 또는 raw scale이 올바르지 않습니다.");
        IntrinsicsData intrinsics = readIntrinsics(videoDepth);
        float[] depthMeters = new float[pixelCount];
        boolean[] valid = new boolean[pixelCount];
        rectifyDepth(z16, depthStride, width, height, scale, intrinsics, depthMeters, valid);
        Bitmap rgbBitmap = null;
        long rgbTimestampNs = 0L;
        String rgbDomainName = null;
        String rgbDepthCalibrationJson = null;
        if (colorFrame != null) {
            Object videoColor = null;
            try {
                videoColor = colorFrame.getClass().getMethod("as", Class.forName(SDK + "Extension"))
                        .invoke(colorFrame, videoExt);
                int colorWidth = (int) videoColor.getClass().getMethod("getWidth").invoke(videoColor);
                int colorHeight = (int) videoColor.getClass().getMethod("getHeight").invoke(videoColor);
                int colorStride = (int) videoColor.getClass().getMethod("getStride").invoke(videoColor);
                byte[] colorBytes = readBytes(colorFrame);
                rgbBitmap = rgb8ToBitmap(colorBytes, colorStride, colorWidth, colorHeight);
                rgbTimestampNs = Math.round(((double) colorFrame.getClass().getMethod("getTimestamp").invoke(colorFrame)) * 1_000_000d);
                Object rgbDomain = colorFrame.getClass().getMethod("getTimestampDomain").invoke(colorFrame);
                rgbDomainName = "realsense_" + rgbDomain.toString().toLowerCase(Locale.US);
                try {
                    rgbDepthCalibrationJson = readRgbDepthCalibration(videoDepth, videoColor);
                } catch (Exception calibrationError) {
                    Log.w(TAG, "RGB/Depth calibration unavailable; retaining unverified capture", calibrationError);
                }
                postRgbDiagnostic("RGB 프레임 수신·변환 성공 · " + colorWidth + "×" + colorHeight + " RGB8"
                        + " · stride " + colorStride + " B · 버퍼 " + colorBytes.length + " B\n시각 "
                        + String.format(Locale.US, "%.3f ms", (double) colorFrame.getClass().getMethod("getTimestamp").invoke(colorFrame))
                        + " · clock " + rgbDomainName + "\nDepth 픽셀 정렬·시각 대응은 미검증");
            } catch (Throwable rgbError) {
                Log.e(TAG, "D405 RGB conversion failed; continuing with Depth", rgbError);
                postStatus("Depth 미리보기 수신 중 · D405 RGB 변환 오류: " + rootMessage(rgbError));
                postRgbDiagnostic("COLOR 프레임은 도착했지만 RGB 변환 실패\n원인: " + rootMessage(rgbError)
                        + "\n확인: 픽셀 포맷, 크기, stride, 버퍼 크기, SDK API 호환성");
            } finally {
                closeQuietly(videoColor);
            }
        }
        Bitmap depthBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] depthPixels = new int[pixelCount];
        for (int i = 0; i < pixelCount; i++) depthPixels[i] = valid[i] ? depthColor(depthMeters[i]) : Color.BLACK;
        depthBitmap.setPixels(depthPixels, 0, width, 0, 0, width, height);
        String calibrationId = String.format(Locale.US, "d405_%s_%dx%d_fx%.4f_fy%.4f_cx%.3f_cy%.3f",
                serial == null || serial.trim().isEmpty() ? "unknown" : serial.replaceAll("[^A-Za-z0-9._-]", "_"),
                width, height, intrinsics.fx, intrinsics.fy, intrinsics.cx, intrinsics.cy);
        calibrationId += "_rectified_from_" + intrinsics.model + "_" + coeffId(intrinsics.coeffs);
        double timestampMs = (double) depthFrame.getClass().getMethod("getTimestamp").invoke(depthFrame);
        Object domain = depthFrame.getClass().getMethod("getTimestampDomain").invoke(depthFrame);
        String domainName = "realsense_" + domain.toString().toLowerCase(Locale.US);
        String frameId = String.format(Locale.US, "d405_%010d", (int) depthFrame.getClass().getMethod("getNumber").invoke(depthFrame));
        CameraFrame frame = new CameraFrame(frameId, Math.round(timestampMs * 1_000_000d), domainName,
                width, height, depthStride, intrinsics.fx, intrinsics.fy, intrinsics.cx, intrinsics.cy, scale,
                calibrationId, firmware, sdkVersion, serial,
                z16, depthMeters, valid, depthBitmap, rgbBitmap, rgbTimestampNs, rgbDomainName,
                rgbDepthCalibrationJson);
        Listener current = listener;
        if (current != null && running.get()) current.onFrame(frame);
        else { depthBitmap.recycle(); if (rgbBitmap != null) rgbBitmap.recycle(); }
        closeQuietly(typedDepth); closeQuietly(videoDepth);
    }

    private static Bitmap rgb8ToBitmap(byte[] data, int stride, int width, int height) {
        if (width <= 0 || height <= 0 || stride < width * 3 || data.length < stride * height)
            throw new IllegalStateException("D405 RGB profile의 크기·stride·버퍼 길이가 올바르지 않습니다.");
        int[] pixels = new int[Math.multiplyExact(width, height)];
        for (int y = 0; y < height; y++) {
            int row = y * stride;
            for (int x = 0; x < width; x++) {
                int offset = row + x * 3;
                pixels[y * width + x] = Color.rgb(data[offset] & 255, data[offset + 1] & 255, data[offset + 2] & 255);
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        // RGB8 has no transparency. Mark opaque before sharing with preview/storage
        // so Android's PNG encoder writes RGB instead of an unused alpha channel.
        bitmap.setHasAlpha(false);
        return bitmap;
    }

    private static byte[] readBytes(Object frame) throws Exception {
        int n = (int) frame.getClass().getMethod("getDataSize").invoke(frame);
        byte[] data = new byte[n]; frame.getClass().getMethod("getData", byte[].class).invoke(frame, (Object) data); return data;
    }

    /** Capture actual active-stream calibration; never infer identity from equal resolutions. */
    private String readRgbDepthCalibration(Object depthVideo, Object colorVideo) throws Exception {
        Object depthProfile = depthVideo.getClass().getMethod("getProfile").invoke(depthVideo);
        Object colorProfile = null;
        try {
            colorProfile = colorVideo.getClass().getMethod("getProfile").invoke(colorVideo);
            Object extrinsic = depthProfile.getClass().getMethod("getExtrinsicTo", Class.forName(SDK + "StreamProfile"))
                    .invoke(depthProfile, colorProfile);
            org.json.JSONObject result = new org.json.JSONObject();
            result.put("format", "mobile-rgb-depth-calibration-v1");
            result.put("source", "active_realsense_stream_profiles");
            result.put("validation_status", "unverified");
            result.put("depth_source_intrinsics", profileIntrinsics(depthProfile));
            result.put("color_intrinsics", profileIntrinsics(colorProfile));
            org.json.JSONObject transform = new org.json.JSONObject();
            transform.put("from", "depth_camera"); transform.put("to", "color_camera");
            transform.put("rotation_layout", "column_major");
            transform.put("rotation", finiteArray(arrayField(extrinsic, "mRotation"), 9));
            transform.put("translation_m", finiteArray(arrayField(extrinsic, "mTranslation"), 3));
            result.put("depth_to_color", transform);
            return result.toString();
        } finally { closeQuietly(colorProfile); closeQuietly(depthProfile); }
    }

    private org.json.JSONObject profileIntrinsics(Object profile) throws Exception {
        Object ext = enumValue(Class.forName(SDK + "Extension"), "VIDEO_PROFILE");
        Object video = profile.getClass().getMethod("as", Class.forName(SDK + "Extension")).invoke(profile, ext);
        // The wrapper's as() view borrows the profile handle; close only the owning profile.
        Object intrinsic = video.getClass().getMethod("getIntrinsic").invoke(video);
        org.json.JSONObject result = new org.json.JSONObject();
        result.put("width", intrinsic.getClass().getMethod("getWidth").invoke(intrinsic));
        result.put("height", intrinsic.getClass().getMethod("getHeight").invoke(intrinsic));
        result.put("fx", field(intrinsic, "mFx")); result.put("fy", field(intrinsic, "mFy"));
        result.put("cx", field(intrinsic, "mPpx")); result.put("cy", field(intrinsic, "mPpy"));
        result.put("model", intrinsic.getClass().getMethod("getModel").invoke(intrinsic).toString());
        result.put("coeffs", finiteArray(arrayField(intrinsic, "mCoeffs"), 5));
        result.put("profile_uid", profile.getClass().getMethod("getUniqueId").invoke(profile));
        result.put("fps", profile.getClass().getMethod("getFrameRate").invoke(profile));
        result.put("pixel_format", profile.getClass().getMethod("getFormat").invoke(profile).toString());
        return result;
    }

    private static org.json.JSONArray finiteArray(float[] values, int length) throws Exception {
        if (values.length != length) throw new IllegalStateException("Unexpected calibration array length");
        org.json.JSONArray result = new org.json.JSONArray();
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite calibration value");
            result.put((double) value);
        }
        return result;
    }

    private IntrinsicsData readIntrinsics(Object videoFrame) throws Exception {
        Object profile = videoFrame.getClass().getMethod("getProfile").invoke(videoFrame);
        try {
            Object videoExt = enumValue(Class.forName(SDK + "Extension"), "VIDEO_PROFILE");
            Object videoProfile = profile.getClass().getMethod("as", Class.forName(SDK + "Extension")).invoke(profile, videoExt);
            Object intrinsic = videoProfile.getClass().getMethod("getIntrinsic").invoke(videoProfile);
            Object distortion = intrinsic.getClass().getMethod("getModel").invoke(intrinsic);
            if (distortion == null || !("NONE".equals(distortion.toString()) || "BROWN_CONRADY".equals(distortion.toString())))
                throw new IllegalStateException("Depth 보정 왜곡 모델 " + distortion + "은 rectified_pinhole 변환에서 지원하지 않습니다.");
            float[] coeffs = arrayField(intrinsic, "mCoeffs");
            if (coeffs.length < 5) throw new IllegalStateException("Depth 왜곡 계수 5개를 읽을 수 없습니다.");
            return new IntrinsicsData(field(intrinsic, "mFx"), field(intrinsic, "mFy"),
                    field(intrinsic, "mPpx"), field(intrinsic, "mPpy"), distortion.toString(), coeffs);
        } finally { closeQuietly(profile); }
    }

    /** Remap a Brown-Conrady source image onto an undistorted pinhole pixel grid. */
    private static void rectifyDepth(byte[] z16, int stride, int width, int height, float scale,
                                     IntrinsicsData k, float[] depthMeters, boolean[] valid) {
        if (stride < width * 2 || z16.length < stride * height)
            throw new IllegalStateException("Depth 프레임 stride/data 크기가 올바르지 않습니다.");
        float k1 = k.coeffs[0], k2 = k.coeffs[1], p1 = k.coeffs[2], p2 = k.coeffs[3], k3 = k.coeffs[4];
        boolean brown = "BROWN_CONRADY".equals(k.model);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double sourceX = x, sourceY = y;
            if (brown) {
                double nx = (x - k.cx) / k.fx, ny = (y - k.cy) / k.fy;
                double r2 = nx * nx + ny * ny;
                double radial = 1 + k1 * r2 + k2 * r2 * r2 + k3 * r2 * r2 * r2;
                double distortedX = nx * radial + 2 * p1 * nx * ny + p2 * (r2 + 2 * nx * nx);
                double distortedY = ny * radial + 2 * p2 * nx * ny + p1 * (r2 + 2 * ny * ny);
                sourceX = distortedX * k.fx + k.cx;
                sourceY = distortedY * k.fy + k.cy;
            }
            int sx = (int) Math.round(sourceX), sy = (int) Math.round(sourceY);
            if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue;
            int offset = sy * stride + sx * 2;
            int raw = (z16[offset] & 255) | ((z16[offset + 1] & 255) << 8);
            float z = raw * scale;
            int index = y * width + x;
            if (Float.isFinite(z) && z > 0) { depthMeters[index] = z; valid[index] = true; }
        }
    }

    private static String coeffId(float[] coeffs) {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i > 0) id.append('_');
            id.append(String.format(Locale.US, "%.7g", coeffs[i]).replace('-', 'm').replace('.', 'p'));
        }
        return id.toString();
    }

    private static float[] arrayField(Object instance, String name) throws Exception {
        Field f = instance.getClass().getDeclaredField(name); f.setAccessible(true); return (float[]) f.get(instance);
    }

    private static final class IntrinsicsData {
        final float fx, fy, cx, cy;
        final String model;
        final float[] coeffs;
        IntrinsicsData(float fx, float fy, float cx, float cy, String model, float[] coeffs) {
            if (!(fx > 0) || !(fy > 0)) throw new IllegalStateException("Depth 내참수 fx/fy가 올바르지 않습니다.");
            this.fx = fx; this.fy = fy; this.cx = cx; this.cy = cy; this.model = model; this.coeffs = coeffs;
        }
    }

    private String readDeviceInfo(UsbDevice usb, String info) {
        try {
            Object devices = context.getClass().getMethod("queryDevices").invoke(context);
            try {
                int count = (int) devices.getClass().getMethod("getDeviceCount").invoke(devices);
                if (count == 0) return "";
                Object device = devices.getClass().getMethod("createDevice", int.class).invoke(devices, 0);
                try {
                    Object enumInfo = enumValue(Class.forName(SDK + "CameraInfo"), info);
                    return (String) device.getClass().getMethod("getInfo", Class.forName(SDK + "CameraInfo")).invoke(device, enumInfo);
                } finally { closeQuietly(device); }
            } finally { closeQuietly(devices); }
        } catch (Throwable ignored) { return ""; }
    }

    private static float field(Object instance, String name) throws Exception { Field f = instance.getClass().getDeclaredField(name); f.setAccessible(true); return f.getFloat(instance); }
    private static Object enumValue(Class<?> cls, String value) {
        Object[] values = cls.getEnumConstants();
        if (values != null) for (Object item : values) if (((Enum<?>) item).name().equals(value)) return item;
        throw new IllegalArgumentException("RealSense SDK enum missing: " + cls.getSimpleName() + "." + value);
    }
    private static int depthColor(float z) {
        float t = Math.max(0f, Math.min(1f, (z - 0.10f) / 1.40f));
        return Color.rgb((int) (255 * t), (int) (255 * (1f - Math.abs(2f * t - 1f))), (int) (255 * (1f - t)));
    }
    private void postConnected(String vendor, String model, String firmware, String sdk) {
        Listener l = listener; if (l != null) l.onConnected(vendor, model, firmware, sdk);
    }
    private void postStatus(String status) { Listener l = listener; if (l != null && running.get()) l.onStatus(status); }
    private void postRgbDiagnostic(String diagnostic) {
        if (diagnostic == null || diagnostic.equals(lastRgbDiagnostic)) return;
        lastRgbDiagnostic = diagnostic;
        Listener l = listener;
        if (l != null && running.get()) l.onRgbDiagnostic(diagnostic);
    }
    private void postError(String code, String message) {
        Log.e(TAG, code + ": " + message);
        Listener l = listener; if (l != null) l.onError(code, message);
    }
    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
    private static void closeQuietly(Object object) { if (object instanceof AutoCloseable) try { ((AutoCloseable) object).close(); } catch (Exception ignored) { } }

    @Override public synchronized void disconnect() {
        running.set(false);
        if (worker != null) { worker.shutdownNow(); worker = null; }
    }
}
