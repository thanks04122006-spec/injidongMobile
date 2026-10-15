package com.injidong.measurement;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
            postStatus(hasPermission
                    ? "Android USB 권한 허용됨 · SDK 장치 등록 확인 중…"
                    : "USB 장치는 확인됨 · Android USB 권한 응답 대기 중…");
            Log.i(TAG, "USB permission=" + hasPermission + ", waiting for SDK device enumeration");
            awaitSdkDevice(usb, 60_000);
            postStatus("SDK 장치 확인됨 · D405 Depth 프로파일 설정 중…");
            pipeline = Class.forName(SDK + "Pipeline").getConstructor().newInstance();
            config = Class.forName(SDK + "Config").getConstructor().newInstance();
            Class<?> streamType = Class.forName(SDK + "StreamType");
            Class<?> streamFormat = Class.forName(SDK + "StreamFormat");
            Object depth = enumValue(streamType, "DEPTH");
            Object z16 = enumValue(streamFormat, "Z16");
            Method enable = config.getClass().getMethod("enableStream", streamType, int.class, int.class, int.class, streamFormat, int.class);
            enable.invoke(config, depth, -1, 640, 480, z16, 30);
            postStatus("SDK 장치 확인됨 · D405 Depth 스트림 시작 중…");
            Log.i(TAG, "Starting pipeline: depth Z16 640x480@30; RGB comes from the Android phone camera");
            Object profile = pipeline.getClass().getMethod("start", config.getClass()).invoke(pipeline, config);
            closeQuietly(profile);

            String sdkVersion = (String) rsContext.getMethod("getVersion").invoke(null);
            String serial = readDeviceInfo(usb, "SERIAL_NUMBER");
            String firmware = readDeviceInfo(usb, "FIRMWARE_VERSION");
            postStatus("D405 Depth 연결됨 · 첫 프레임 대기 중…");
            Log.i(TAG, "Depth pipeline started; waiting for frames");
            postConnected("Intel", "RealSense D405", firmware, sdkVersion);

            Class<?> streamTypeClass = streamType;
            long lastUiFrameMs = 0;
            while (running.get()) {
                Object frames = pipeline.getClass().getMethod("waitForFrames", int.class).invoke(pipeline, 2500);
                try {
                    long nowMs = android.os.SystemClock.elapsedRealtime();
                    if (nowMs - lastUiFrameMs < 200) continue; // bound conversion/bitmap allocations to 5 previews per second
                    lastUiFrameMs = nowMs;
                    Object depthFrame = frames.getClass().getMethod("first", streamTypeClass).invoke(frames, depth);
                    if (depthFrame == null) throw new IllegalStateException("D405 Depth 프레임이 FrameSet에 없습니다.");
                    try { deliverFrame(depthFrame, serial, firmware, sdkVersion); }
                    finally { closeQuietly(depthFrame); }
                } finally { closeQuietly(frames); }
            }
        } catch (Throwable error) {
            if (running.get()) postError("REALSENSE_STREAM_FAILED", rootMessage(error));
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
                        return;
                    }
                    if ((android.os.SystemClock.elapsedRealtime() + 300) % 3000 < 300) {
                        UsbManager manager = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
                        Log.i(TAG, "SDK device count=0; Android USB permission=" + (manager != null && manager.hasPermission(usb)));
                        postStatus(manager != null && manager.hasPermission(usb)
                                ? "USB 권한 허용됨 · SDK 장치 등록 대기 중…"
                                : "Android USB 권한 응답 대기 중…");
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

    private void deliverFrame(Object depthFrame, String serial, String firmware, String sdkVersion) throws Exception {
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
                z16, depthMeters, valid, depthBitmap);
        Listener current = listener;
        if (current != null && running.get()) current.onFrame(frame);
        else depthBitmap.recycle();
        closeQuietly(typedDepth); closeQuietly(videoDepth);
    }

    private static byte[] readBytes(Object frame) throws Exception {
        int n = (int) frame.getClass().getMethod("getDataSize").invoke(frame);
        byte[] data = new byte[n]; frame.getClass().getMethod("getData", byte[].class).invoke(frame, (Object) data); return data;
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
