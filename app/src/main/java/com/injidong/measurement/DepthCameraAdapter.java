package com.injidong.measurement;

import android.hardware.usb.UsbDevice;

/** Vendor SDK boundary for synchronized RGB and optical-axis Depth frames. */
public interface DepthCameraAdapter {
    final class CameraFrame {
        public final String frameId;
        public final long captureTimeNs;
        public final String clockDomain;
        public final int width;
        public final int height;
        /** SDK row stride of the original Z16 buffer, including any padding, in bytes. */
        public final int rawDepthStrideBytes;
        public final float fx, fy, cx, cy, depthScaleToMeters;
        public final String calibrationId, firmware, sdkVersion, serial;
        public final byte[] rawDepthZ16;
        public final float[] depthMeters;
        public final boolean[] validMask;
        public final android.graphics.Bitmap depthPreview;

        public CameraFrame(String frameId, long captureTimeNs, String clockDomain,
                           int width, int height, int rawDepthStrideBytes, float fx, float fy, float cx, float cy,
                           float depthScaleToMeters, String calibrationId, String firmware,
                           String sdkVersion, String serial, byte[] rawDepthZ16, float[] depthMeters,
                           boolean[] validMask, android.graphics.Bitmap depthPreview) {
            this.frameId = frameId; this.captureTimeNs = captureTimeNs; this.clockDomain = clockDomain;
            this.width = width; this.height = height; this.fx = fx; this.fy = fy; this.cx = cx; this.cy = cy;
            this.rawDepthStrideBytes = rawDepthStrideBytes;
            this.depthScaleToMeters = depthScaleToMeters; this.calibrationId = calibrationId;
            this.firmware = firmware; this.sdkVersion = sdkVersion; this.serial = serial;
            this.rawDepthZ16 = rawDepthZ16; this.depthMeters = depthMeters; this.validMask = validMask;
            this.depthPreview = depthPreview;
        }
    }

    interface Listener {
        void onStatus(String status);
        void onConnected(String vendor, String model, String firmware, String sdkVersion);
        void onFrame(CameraFrame frame);
        void onError(String code, String message);
    }
    String name();
    boolean supports(UsbDevice device);
    void connect(UsbDevice device, Listener listener);
    void disconnect();
}
