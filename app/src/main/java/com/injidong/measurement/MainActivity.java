package com.injidong.measurement;

import android.content.Context;
import android.app.AlertDialog;
import android.Manifest;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.content.pm.PackageManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

/** Native Android measurement screen with D405 RGB/Depth and phone IMU input. */
public final class MainActivity extends ComponentActivity implements SensorEventListener {
    private static final int CAMERA_PERMISSION_REQUEST = 31;
    private static final long FRAME_SAVE_INTERVAL_MS = 250L;
    private static final int BG = Color.rgb(11, 17, 27);
    private static final int PANEL = Color.rgb(22, 31, 45);
    private static final int MUTED = Color.rgb(157, 173, 193);
    private android.widget.ImageView rgbPreview;
    private android.widget.ImageView depthPreview;
    private TextView rgbStatus;
    private TextView rgbDiagnosticsView;
    private TextView cameraStatus;
    private TextView depthStatus;
    private TextView weightView;
    private TextView stateView;
    private TextView frameView;
    private TextView sourceView;
    private TextView modelView;
    private TextView featureView;
    private TextView qualityView;
    private TextView errorView;
    private TextView usbInfoView;
    private TextView imuStatusView;
    private TextView imuValuesView;
    private Button streamButton;
    private Button exportButton;
    private TextView exportStatus;
    private Button uploadButton;
    private TextView uploadStatus;
    private File uploadableSession;
    private boolean uploading;
    private File exportableSession;
    private File pendingExportSession;
    private boolean exporting;
    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
                File session = pendingExportSession;
                pendingExportSession = null;
                if (uri == null || session == null) {
                    exporting = false;
                    updateExportButton();
                    return;
                }
                exportStatus.setText("ZIP 내보내는 중… " + session.getName());
                this.saveExecutor.execute(() -> {
                    String result;
                    try {
                        java.io.OutputStream output = getContentResolver().openOutputStream(uri, "w");
                        if (output == null) throw new IOException("선택한 저장 위치를 열 수 없습니다.");
                        SessionZipExporter.write(session, output);
                        result = "ZIP 저장 완료 · " + session.getName() + ".zip";
                    } catch (Exception e) {
                        result = "ZIP 저장 실패: " + e.getMessage() + " · 불완전한 ZIP은 삭제하고 다시 시도하세요.";
                    }
                    String message = result;
                    runOnUiThread(() -> {
                        exporting = false;
                        exportStatus.setText(message);
                        updateExportButton();
                    });
                });
            });
    private SensorManager sensorManager;
    private BufferedWriter imuWriter;
    private File activeSessionDirectory;
    private String sessionId;
    private long imuSampleIndex;
    private boolean imuRecording;
    private final Map<Integer, float[]> latestImuValues = new HashMap<>();
    private DepthCameraAdapter depthCameraAdapter;
    private CaptureSessionWriter captureWriter;
    private ExecutorService saveExecutor;
    private boolean streaming;
    private boolean cameraConnected;
    private long lastPersistedFrameMs;
    private int persistedFrameCount;
    private int persistedRgbCount;
    private boolean pendingRealSenseStart;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildNativeScreen();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        refreshUsbDevices();
        renderNoResult("서버의 실제 예측 결과를 아직 받지 못했습니다.");
        depthCameraAdapter = new RealSenseDepthCameraAdapter(this);
        saveExecutor = Executors.newSingleThreadExecutor();
    }

    private void buildNativeScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                topInset = bars.top;
                bottomInset = bars.bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            page.setPadding(dp(18), dp(18) + topInset, dp(18), dp(24) + bottomInset);
            return insets;
        });

        TextView title = text("인지동 측정", 24, Color.WHITE, true);
        page.addView(title, matchWrap());
        TextView subtitle = text("Depth 입력 및 예측 결과", 14, MUTED, false);
        subtitle.setPadding(0, dp(5), 0, dp(14));
        page.addView(subtitle, matchWrap());
        Button viewerButton = button("3D 포인트 클라우드 뷰어");
        viewerButton.setOnClickListener(v -> startActivity(new android.content.Intent(this, PointCloudActivity.class)));
        page.addView(viewerButton, matchWrap());

        LinearLayout cameraBox = card();
        cameraStatus = text("D405 연결 대기", 13, MUTED, true);
        cameraBox.addView(cameraStatus, matchWrap());
        FrameLayout rgbBox = new FrameLayout(this); rgbBox.setBackgroundColor(Color.BLACK);
        rgbPreview = new android.widget.ImageView(this); rgbPreview.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        rgbBox.addView(rgbPreview, new FrameLayout.LayoutParams(-1, -1));
        TextView rgbTag = text("RealSense D405 RGB 미리보기", 12, Color.WHITE, true); rgbTag.setPadding(dp(8), dp(5), dp(8), dp(5)); rgbTag.setBackgroundColor(0x990B111B);
        FrameLayout.LayoutParams rgbTagLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START); rgbTagLp.setMargins(dp(8), dp(8), 0, 0); rgbBox.addView(rgbTag, rgbTagLp);
        LinearLayout.LayoutParams viewLp = new LinearLayout.LayoutParams(-1, dp(210)); viewLp.topMargin = dp(8); cameraBox.addView(rgbBox, viewLp);
        FrameLayout depthBox = new FrameLayout(this); depthBox.setBackgroundColor(Color.BLACK);
        depthPreview = new android.widget.ImageView(this); depthPreview.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        depthBox.addView(depthPreview, new FrameLayout.LayoutParams(-1, -1));
        TextView depthTag = text("RealSense Depth 미리보기", 12, Color.WHITE, true); depthTag.setPadding(dp(8), dp(5), dp(8), dp(5)); depthTag.setBackgroundColor(0x990B111B);
        FrameLayout.LayoutParams depthTagLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START); depthTagLp.setMargins(dp(8), dp(8), 0, 0); depthBox.addView(depthTag, depthTagLp);
        LinearLayout.LayoutParams depthLp = new LinearLayout.LayoutParams(-1, dp(210)); depthLp.topMargin = dp(8); cameraBox.addView(depthBox, depthLp);
        rgbStatus = text("D405 RGB 프레임을 기다리고 있습니다.", 12, MUTED, false); rgbStatus.setPadding(0, dp(7), 0, 0); cameraBox.addView(rgbStatus, matchWrap());
        TextView diagnosticTitle = text("RGB 수신 진단", 12, Color.WHITE, true); diagnosticTitle.setPadding(0, dp(8), 0, dp(3)); cameraBox.addView(diagnosticTitle, matchWrap());
        rgbDiagnosticsView = text("진단 대기 · ‘데이터 수집’을 누르면 USB/SDK/RGB 단계별 상태가 표시됩니다.", 11, MUTED, false);
        rgbDiagnosticsView.setPadding(dp(9), dp(8), dp(9), dp(8));
        rgbDiagnosticsView.setBackgroundColor(PANEL);
        rgbDiagnosticsView.setTextIsSelectable(true);
        cameraBox.addView(rgbDiagnosticsView, matchWrap());
        depthStatus = text("D405 Depth 프레임을 기다리고 있습니다.", 12, MUTED, false); depthStatus.setPadding(0, dp(4), 0, 0); cameraBox.addView(depthStatus, matchWrap());
        streamButton = button("데이터 수집"); streamButton.setOnClickListener(v -> { if (streaming) stopRealSense(); else startRealSense(); });
        LinearLayout.LayoutParams streamLp = new LinearLayout.LayoutParams(-1, dp(50)); streamLp.topMargin = dp(8); cameraBox.addView(streamButton, streamLp);
        exportButton = button("수집 데이터 ZIP 내보내기");
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(v -> beginValidatedExport());
        cameraBox.addView(exportButton, matchWrap());
        exportStatus = text("수집을 중지하고 저장이 끝나면 세션 전체를 ZIP으로 내보낼 수 있습니다.", 12, MUTED, false);
        cameraBox.addView(exportStatus, matchWrap());
        uploadButton = button("수집 데이터 API 업로드");
        uploadButton.setEnabled(false);
        uploadButton.setOnClickListener(v -> beginManualUpload());
        cameraBox.addView(uploadButton, matchWrap());
        uploadStatus = text("수집 종료·저장 완료 후 버튼을 눌러 전송합니다. 자동 업로드는 하지 않습니다.\n서버: " + BuildConfig.API_ORIGIN, 12, MUTED, false);
        cameraBox.addView(uploadStatus, matchWrap());
        TextView limitation = text("‘데이터 수집’ 버튼 하나로 D405 RGB·Depth와 휴대폰 IMU 수집 및 로컬 저장을 함께 시작·중지합니다. RGB/Depth 픽셀 정렬과 시각 대응은 검증 전까지 unverified로 기록합니다. API 전송은 별도의 업로드 버튼을 눌렀을 때만 실행됩니다.", 12, Color.rgb(255, 211, 117), false);
        limitation.setPadding(0, dp(8), 0, 0); cameraBox.addView(limitation, matchWrap());
        page.addView(cameraBox, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout deviceCard = card();
        deviceCard.addView(text("USB Depth 카메라", 16, Color.WHITE, true), matchWrap());
        usbInfoView = text("USB 장치 정보를 확인하는 중…", 13, MUTED, false);
        usbInfoView.setPadding(0, dp(8), 0, dp(8));
        deviceCard.addView(usbInfoView, matchWrap());
        Button refreshUsb = button("USB 장치 다시 검색");
        refreshUsb.setOnClickListener(v -> refreshUsbDevices());
        deviceCard.addView(refreshUsb, matchWrap());
        TextView usbNote = text("USB VID/PID와 제품 descriptor를 읽습니다. 이 정보만으로 RGB/Depth 스트림을 열 수는 없습니다.", 12, MUTED, false);
        usbNote.setPadding(0, dp(6), 0, 0);
        deviceCard.addView(usbNote, matchWrap());
        LinearLayout.LayoutParams deviceCardParams = new LinearLayout.LayoutParams(-1, -2);
        deviceCardParams.topMargin = dp(12);
        page.addView(deviceCard, deviceCardParams);

        LinearLayout imuCard = card();
        imuCard.addView(text("휴대폰 IMU 기록", 16, Color.WHITE, true), matchWrap());
        imuStatusView = text("기록 중지", 13, MUTED, false);
        imuStatusView.setPadding(0, dp(8), 0, dp(3));
        imuCard.addView(imuStatusView, matchWrap());
        imuValuesView = text("가속도·각속도·방향 데이터 대기", 12, MUTED, false);
        imuValuesView.setPadding(0, dp(4), 0, dp(8));
        imuCard.addView(imuValuesView, matchWrap());
        TextView imuNote = text("가속도 원시값은 중력을 포함합니다. 선형 가속도(중력 제외)도 별도 수집합니다. 휴대폰 센서 값이며 D405 자체 IMU가 아닙니다. 위의 데이터 수집 버튼으로 RGB·Depth와 함께 시작·중지합니다.", 12, MUTED, false);
        imuNote.setPadding(0, dp(6), 0, 0);
        imuCard.addView(imuNote, matchWrap());
        LinearLayout.LayoutParams imuCardParams = new LinearLayout.LayoutParams(-1, -2);
        imuCardParams.topMargin = dp(12);
        page.addView(imuCard, imuCardParams);

        LinearLayout resultCard = card();
        TextView resultCaption = text("측정 중량", 14, MUTED, true);
        resultCard.addView(resultCaption, matchWrap());
        weightView = text("중량 데이터 없음", 30, Color.WHITE, true);
        weightView.setPadding(0, dp(8), 0, dp(5));
        resultCard.addView(weightView, matchWrap());
        stateView = text("측정 대기", 15, MUTED, true);
        resultCard.addView(stateView, matchWrap());
        frameView = detailLine(resultCard, "frame_id", "—");
        sourceView = detailLine(resultCard, "출처", "—");
        modelView = detailLine(resultCard, "model_id", "—");
        featureView = detailLine(resultCard, "feature_version", "—");
        qualityView = detailLine(resultCard, "quality", "unknown");
        errorView = text("오류 없음", 13, MUTED, false);
        errorView.setPadding(0, dp(10), 0, 0);
        resultCard.addView(errorView, matchWrap());
        page.addView(resultCard, new LinearLayout.LayoutParams(-1, -2));

        TextView note = text("중량은 서버의 실제 예측 결과가 수신되기 전까지 표시하지 않습니다. 수집 정보는 세션 JSON/JSONL과 원본 이미지·Depth 배열 파일로 저장합니다. 서버 API가 정해지면 전송을 연결할 수 있습니다.", 12, MUTED, false);
        note.setPadding(0, dp(12), 0, 0);
        page.addView(note, matchWrap());
        setContentView(scroll);
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setBackgroundColor(PANEL);
        return box;
    }

    private TextView detailLine(LinearLayout parent, String label, String initial) {
        TextView v = text(label + "  " + initial, 13, MUTED, false);
        v.setPadding(0, dp(7), 0, 0);
        parent.addView(v, matchWrap());
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(40, 101, 220)));
        return b;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private void startRealSense() {
        if (uploading || exporting) return;
        if (depthCameraAdapter instanceof RealSenseDepthCameraAdapter && !((RealSenseDepthCameraAdapter) depthCameraAdapter).isSdkAvailable()) {
            cameraStatus.setText("RealSense Android SDK가 앱에 포함되지 않았습니다. librealsense.aar를 android-app/app/libs에 넣고 다시 빌드하세요.");
            return;
        }
        // Keep the 0.4.0 SDK startup gate. The RealSense Android wrapper handles
        // USB device enumeration/permission after its context is initialized.
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingRealSenseStart = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            cameraStatus.setText("RealSense SDK 시작을 위해 Android 카메라 접근 권한을 허용해 주세요.");
            return;
        }
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice target = null;
        if (manager != null) for (UsbDevice candidate : manager.getDeviceList().values()) {
            if (depthCameraAdapter.supports(candidate)) { target = candidate; break; }
        }
        if (target == null) {
            cameraStatus.setText("Intel RealSense USB 장치를 찾지 못했습니다. OTG 연결/전원을 확인하세요.");
            refreshUsbDevices(); return;
        }
        try {
            File documents = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (documents == null) throw new IOException("앱 전용 문서 저장소에 접근할 수 없습니다.");
            sessionId = "d405_" + System.currentTimeMillis();
            activeSessionDirectory = new File(new File(documents, "sessions"), sessionId);
            if (!activeSessionDirectory.mkdirs() && !activeSessionDirectory.isDirectory()) throw new IOException("세션 폴더를 만들 수 없습니다.");
            captureWriter = new CaptureSessionWriter(activeSessionDirectory, sessionId);
            uploadableSession = null;
            uploadStatus.setText("수집 중 · 저장이 완료되면 API 업로드 버튼이 활성화됩니다.");
            persistedFrameCount = 0; persistedRgbCount = 0; lastPersistedFrameMs = 0; streaming = true;
            updateExportButton();
            streamButton.setText("데이터 수집 중지");
            cameraStatus.setText("USB 장치 확인됨 · RealSense SDK 스트림 여는 중…");
            depthCameraAdapter.connect(target, new DepthCameraAdapter.Listener() {
                @Override public void onStatus(String status) {
                    runOnUiThread(() -> cameraStatus.setText(status));
                }
                @Override public void onRgbDiagnostic(String diagnostic) {
                    runOnUiThread(() -> rgbDiagnosticsView.setText(diagnostic));
                }
                @Override public void onConnected(String vendor, String model, String firmware, String sdkVersion) {
                    runOnUiThread(() -> {
                        cameraConnected = true;
                        cameraStatus.setText(vendor + " " + model + " · FW " + (firmware == null ? "확인 불가" : firmware) + " · SDK " + sdkVersion);
                        rgbStatus.setText("D405 RGB/Depth 스트림 연결됨 · 첫 프레임 대기 중");
                        if (usbInfoView != null) refreshUsbDevices();
                    });
                }
                @Override public void onFrame(DepthCameraAdapter.CameraFrame frame) {
                    runOnUiThread(() -> {
                        if (!streaming) return;
                        depthPreview.setImageBitmap(frame.depthPreview);
                        if (frame.rgbPreview != null) {
                            rgbPreview.setImageBitmap(frame.rgbPreview);
                            rgbStatus.setText("D405 RGB 미리보기 · " + frame.rgbPreview.getWidth() + "×" + frame.rgbPreview.getHeight()
                                    + " · 저장 " + persistedRgbCount + "장 · Depth 정렬 unverified");
                        } else rgbStatus.setText("D405 RGB 프레임을 받지 못했습니다. Depth 미리보기는 계속됩니다.");
                        int validCount = 0; for (boolean valid : frame.validMask) if (valid) validCount++;
                        float center = frame.depthMeters[(frame.height / 2) * frame.width + frame.width / 2];
                        depthStatus.setText(String.format(Locale.US, "%s · %d×%d · 중심 Depth %.3f m · 유효 %.1f%% · 저장 %d프레임",
                                frame.frameId, frame.width, frame.height, center, validCount * 100.0 / frame.validMask.length, persistedFrameCount));
                        long now = android.os.SystemClock.elapsedRealtime();
                        CaptureSessionWriter writer = captureWriter;
                        if (writer != null && now - lastPersistedFrameMs >= FRAME_SAVE_INTERVAL_MS) {
                            lastPersistedFrameMs = now;
                            saveExecutor.execute(() -> {
                                try {
                                    boolean rgbSaved = writer.writeDepth(frame);
                                    persistedFrameCount++;
                                    if (rgbSaved) persistedRgbCount++;
                                }
                                catch (Exception e) { runOnUiThread(() -> depthStatus.setText("세션 저장 오류: " + e.getMessage())); }
                            });
                        }
                    });
                }
                @Override public void onError(String code, String message) {
                    runOnUiThread(() -> stopRealSense(code + ": " + message));
                }
            });
            startImuRecording(sessionId, activeSessionDirectory);
            captureWriter.writeSessionSummary("collecting", imuSampleIndex);
        } catch (Exception e) {
            streaming = false;
            updateExportButton();
            if (captureWriter != null) { try { captureWriter.close(); } catch (Exception ignored) { } captureWriter = null; }
            streamButton.setText("데이터 수집");
            cameraStatus.setText("수집을 시작하지 못했습니다: " + e.getMessage());
        }
    }

    private void stopRealSense() {
        stopRealSense(null);
    }

    private void stopRealSense(String finalStatus) {
        if (!streaming && captureWriter == null) return;
        streaming = false;
        cameraConnected = false;
        depthCameraAdapter.disconnect();
        if (imuRecording) stopImuRecording();
        CaptureSessionWriter writer = captureWriter; captureWriter = null;
        File finishedSession = activeSessionDirectory;
        String sessionStatus = finalStatus == null ? "complete" : "failed";
        long finishedImuCount = imuSampleIndex;
        streamButton.setEnabled(false);
        exportButton.setEnabled(false);
        exportStatus.setText("수집 파일 저장 마무리 중…");
        if (writer != null && saveExecutor != null && !saveExecutor.isShutdown()) saveExecutor.execute(() -> {
            try {
                writer.writeSessionSummary(sessionStatus, finishedImuCount);
                writer.close();
                runOnUiThread(() -> {
                    exportableSession = finishedSession;
                    uploadableSession = "complete".equals(sessionStatus) ? finishedSession : null;
                    uploadStatus.setText(uploadableSession != null
                            ? "전송 대기 · " + finishedSession.getName() + "\n전송 서버: " + BuildConfig.API_ORIGIN
                            : "수집이 정상 완료되지 않아 API 업로드할 수 없습니다.");
                    streamButton.setEnabled(true);
                    exportStatus.setText("내보내기 준비 완료 · " + finishedSession.getName()
                            + " · JSON/JSONL, raw/, rgb/, 프레임별 metadata.json·arrays.npz 포함");
                    updateExportButton();
                });
            }
            catch (Exception e) { runOnUiThread(() -> {
                streamButton.setEnabled(true);
                exportStatus.setText("세션 종료 오류: " + e.getMessage());
                depthStatus.setText("세션 종료 오류: " + e.getMessage());
            }); }
        });
        if (streamButton != null) streamButton.setText("데이터 수집");
        if (cameraStatus != null) cameraStatus.setText(finalStatus == null
                ? "수집 중지 · 저장 Depth " + persistedFrameCount + "프레임"
                : finalStatus + " · 저장 Depth " + persistedFrameCount + "프레임");
    }

    private void beginValidatedExport() {
        if (exportableSession == null || streaming || exporting || uploading) return;
        File session = exportableSession;
        exporting = true;
        updateExportButton();
        exportStatus.setText("ZIP 저장 전 RGB 파일을 확인하는 중…");
        saveExecutor.execute(() -> {
            try {
                RgbExportValidator.validate(session);
            } catch (Exception error) {
                String reason = error.getMessage() == null ? "RGB 파일 검사를 완료하지 못했습니다." : error.getMessage();
                runOnUiThread(() -> {
                    exporting = false;
                    pendingExportSession = null;
                    updateExportButton();
                    exportStatus.setText("RGB 검사 실패 · ZIP을 저장하지 않았습니다.");
                    if (!isFinishing() && !isDestroyed()) new AlertDialog.Builder(this)
                            .setTitle("RGB 데이터 확인 필요")
                            .setMessage(reason + "\n\nZIP 저장을 중단했습니다. 수집 데이터는 유지됩니다. RGB8 저장이 가능한 앱으로 다시 수집해 주세요.")
                            .setPositiveButton("확인", null)
                            .show();
                });
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    exporting = false;
                    return;
                }
                pendingExportSession = session;
                exportStatus.setText("RGB 검사 완료 · ZIP 저장 위치를 선택해 주세요.");
                try { exportLauncher.launch(session.getName() + ".zip"); }
                catch (Exception error) {
                    exporting = false;
                    pendingExportSession = null;
                    exportStatus.setText("저장 위치를 열지 못했습니다: " + error.getMessage());
                    updateExportButton();
                }
            });
        });
    }

    private void updateExportButton() {
        if (exportButton != null) exportButton.setEnabled(exportableSession != null && !streaming && !exporting && !uploading);
        if (uploadButton != null) uploadButton.setEnabled(uploadableSession != null && !streaming && !exporting && !uploading);
        if (streamButton != null && !streaming) streamButton.setEnabled(!exporting && !uploading);
    }

    /** The only HTTP upload entry point: an explicit user button click. */
    private void beginManualUpload() {
        if (uploadableSession == null || streaming || exporting || uploading
                || saveExecutor == null || saveExecutor.isShutdown()) return;
        File session = uploadableSession;
        uploading = true;
        uploadButton.setText("API 업로드 중…");
        uploadStatus.setText("RGB 검사·ZIP 준비·업로드 중… " + session.getName());
        updateExportButton();
        saveExecutor.execute(() -> {
            try {
                RgbExportValidator.validate(session);
                new HttpSessionTransport(BuildConfig.API_ORIGIN, BuildConfig.API_TOKEN)
                        .sendSession(session, new SessionTransport.Callback() {
                            @Override public void onComplete() {
                                finishManualUpload("API 업로드 완료 · " + session.getName()
                                        + "\n서버 저장 정보의 ID·크기·SHA-256을 확인했습니다.");
                            }
                            @Override public void onError(String code, String message) {
                                finishManualUpload("API 업로드 확인 실패 (" + code + ")\n"
                                        + ("UNAUTHORIZED".equals(code) ? "팀 토큰 설정을 확인하세요."
                                        : "인터넷 연결과 PC 서버·HTTPS 터널 실행 상태 및 서버 주소를 확인하세요.")
                                        + " 동일 ZIP으로 재시도합니다.");
                            }
                        });
            } catch (Exception error) {
                finishManualUpload("업로드 준비 실패 · 수집 파일과 RGB 형식을 확인하고 다시 시도하세요. 로컬 데이터는 유지됩니다.");
            }
        });
    }

    private void finishManualUpload(String message) {
        runOnUiThread(() -> {
            uploading = false;
            if (isFinishing() || isDestroyed()) return;
            uploadButton.setText("수집 데이터 API 업로드");
            uploadStatus.setText(message);
            updateExportButton();
        });
    }

    private void refreshUsbDevices() {
        if (usbInfoView == null) return;
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        Map<String, UsbDevice> devices = manager == null ? Collections.emptyMap() : manager.getDeviceList();
        if (devices.isEmpty()) {
            usbInfoView.setText("USB 장치가 검색되지 않았습니다. USB OTG 연결과 전원을 확인한 뒤 다시 검색하세요.\n\nD405 RGB/Depth 스트림: SDK 연결 전");
            return;
        }
        StringBuilder info = new StringBuilder();
        for (UsbDevice device : devices.values()) {
            if (info.length() > 0) info.append("\n\n");
            String manufacturer = device.getManufacturerName();
            String product = device.getProductName();
            info.append(product == null || product.trim().isEmpty() ? "USB 장치" : product.trim());
            if (manufacturer != null && !manufacturer.trim().isEmpty()) info.append(" · ").append(manufacturer.trim());
            info.append("\nVID:PID ").append(String.format(Locale.US, "%04X:%04X", device.getVendorId(), device.getProductId()));
            info.append(" · interfaces ").append(device.getInterfaceCount());
            info.append("\nUSB 권한: ").append(manager.hasPermission(device) ? "허용됨" : "미요청");
            info.append(" · 장치 경로: ").append(device.getDeviceName());
            try {
                String serial = device.getSerialNumber();
                if (serial != null && !serial.trim().isEmpty()) info.append("\nserial: ").append(serial.trim());
            } catch (SecurityException ignored) {
                info.append("\nserial: USB 권한 후 확인 가능");
            }
            info.append("\nRealSense D405 Depth SDK: ").append(cameraConnected ? "연결됨" : streaming ? "연결 시도 중" : "연결 대기");
        }
        usbInfoView.setText(info.toString());
    }

    private void startImuRecording(String sharedSessionId, File sharedSessionDirectory) {
        if (imuRecording) return;
        if (sensorManager == null) {
            imuStatusView.setText("센서 서비스를 사용할 수 없습니다.");
            return;
        }
        try {
            File documents = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (documents == null) throw new IOException("앱 전용 문서 저장소를 사용할 수 없습니다.");
            sessionId = sharedSessionId == null ? "phone_imu_" + System.currentTimeMillis() : sharedSessionId;
            activeSessionDirectory = sharedSessionDirectory == null ? new File(new File(documents, "sessions"), sessionId) : sharedSessionDirectory;
            if (!activeSessionDirectory.mkdirs() && !activeSessionDirectory.isDirectory()) throw new IOException("세션 폴더를 만들 수 없습니다.");
            File output = new File(activeSessionDirectory, "imu.jsonl");
            imuWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(output, false), StandardCharsets.UTF_8));
            imuSampleIndex = 0;
            latestImuValues.clear();
            int registered = 0;
            registered += registerImuSensor(Sensor.TYPE_ACCELEROMETER);
            registered += registerImuSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            registered += registerImuSensor(Sensor.TYPE_GYROSCOPE);
            registered += registerImuSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (registered == 0) throw new IOException("사용 가능한 가속도계·자이로스코프·회전 벡터 센서가 없습니다.");
            imuRecording = true;
            imuStatusView.setText("기록 중 · 수집 센서 " + registered + "개 · 파일: " + output.getAbsolutePath());
        } catch (Exception e) {
            closeImuWriter();
            imuStatusView.setText("기록을 시작하지 못했습니다: " + e.getMessage());
        }
    }

    private int registerImuSensor(int type) {
        Sensor sensor = sensorManager.getDefaultSensor(type);
        if (sensor == null) return 0;
        return sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME) ? 1 : 0;
    }

    private void stopImuRecording() {
        if (!imuRecording) return;
        imuRecording = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        closeImuWriter();
        String path = activeSessionDirectory == null ? "저장 위치 확인 불가" : new File(activeSessionDirectory, "imu.jsonl").getAbsolutePath();
        imuStatusView.setText("기록 완료 · " + imuSampleIndex + " samples\n" + path);
    }

    private void closeImuWriter() {
        if (imuWriter == null) return;
        try { imuWriter.flush(); imuWriter.close(); }
        catch (IOException e) { if (imuStatusView != null) imuStatusView.setText("IMU 파일 저장 오류: " + e.getMessage()); }
        finally { imuWriter = null; }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null) return;
        latestImuValues.put(event.sensor.getType(), Arrays.copyOf(event.values, event.values.length));
        if (imuValuesView != null) imuValuesView.setText(formatImuSummary());
        if (!imuRecording || imuWriter == null) return;
        try {
            JSONObject row = new JSONObject();
            row.put("schema_version", "1.0.0");
            row.put("session_id", sessionId);
            row.put("sample_id", sessionId + "_" + String.format(Locale.US, "%08d", ++imuSampleIndex));
            row.put("device_id", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
            row.put("source", "phone");
            row.put("sensor_type", sensorTypeName(event.sensor.getType()));
            row.put("frame", "android_phone_default_axes");
            JSONObject time = new JSONObject();
            time.put("ns", Long.toString(event.timestamp));
            time.put("clock_domain", "android_elapsed_realtime");
            time.put("origin", "sensor");
            row.put("time", time);
            row.put("accel_m_s2", event.sensor.getType() == Sensor.TYPE_ACCELEROMETER || event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION ? vectorJson(event.values, 3) : JSONObject.NULL);
            row.put("gyro_rad_s", event.sensor.getType() == Sensor.TYPE_GYROSCOPE ? vectorJson(event.values, 3) : JSONObject.NULL);
            row.put("orientation_xyzw", event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR ? quaternionXyzw(event.values) : JSONObject.NULL);
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) row.put("gravity_included", true);
            else if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) row.put("gravity_included", false);
            else row.put("gravity_included", false);
            row.put("provenance_kind", isEmulator() ? "synthetic" : "real");
            imuWriter.write(row.toString());
            imuWriter.newLine();
            if ((imuSampleIndex % 30) == 0) imuWriter.flush();
            if (imuRecording && imuStatusView != null && (imuSampleIndex % 30) == 0) {
                imuStatusView.setText("기록 중 · " + imuSampleIndex + " samples · " + sessionId);
            }
        } catch (Exception e) {
            imuStatusView.setText("IMU 기록 오류: " + e.getMessage());
            stopImuRecording();
        }
    }

    private JSONArray vectorJson(float[] values, int length) throws JSONException {
        JSONArray array = new JSONArray();
        for (int i = 0; i < length; i++) array.put(Float.isFinite(values[i]) ? (double) values[i] : JSONObject.NULL);
        return array;
    }

    private JSONArray quaternionXyzw(float[] rotationVector) throws JSONException {
        float[] wxyz = new float[4];
        SensorManager.getQuaternionFromVector(wxyz, rotationVector);
        JSONArray xyzw = new JSONArray();
        xyzw.put((double) wxyz[1]); xyzw.put((double) wxyz[2]); xyzw.put((double) wxyz[3]); xyzw.put((double) wxyz[0]);
        return xyzw;
    }

    private String sensorTypeName(int type) {
        if (type == Sensor.TYPE_ACCELEROMETER) return "TYPE_ACCELEROMETER";
        if (type == Sensor.TYPE_LINEAR_ACCELERATION) return "TYPE_LINEAR_ACCELERATION";
        if (type == Sensor.TYPE_GYROSCOPE) return "TYPE_GYROSCOPE";
        if (type == Sensor.TYPE_ROTATION_VECTOR) return "TYPE_ROTATION_VECTOR";
        return "SENSOR_" + type;
    }

    private boolean isEmulator() {
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT.toLowerCase(Locale.US);
        String model = Build.MODEL == null ? "" : Build.MODEL.toLowerCase(Locale.US);
        String product = Build.PRODUCT == null ? "" : Build.PRODUCT.toLowerCase(Locale.US);
        return fingerprint.startsWith("generic") || fingerprint.contains("emulator") ||
                model.contains("emulator") || model.contains("sdk_gphone") || product.contains("sdk_gphone");
    }

    private String formatImuSummary() {
        StringBuilder summary = new StringBuilder();
        float[] accel = latestImuValues.get(Sensor.TYPE_ACCELEROMETER);
        float[] linearAccel = latestImuValues.get(Sensor.TYPE_LINEAR_ACCELERATION);
        float[] gyro = latestImuValues.get(Sensor.TYPE_GYROSCOPE);
        float[] rotation = latestImuValues.get(Sensor.TYPE_ROTATION_VECTOR);
        if (accel != null) summary.append("가속도 m/s²: ").append(formatVector(accel, 3)).append('\n');
        if (linearAccel != null) summary.append("선형 가속도 m/s²: ").append(formatVector(linearAccel, 3)).append('\n');
        if (gyro != null) summary.append("각속도 rad/s: ").append(formatVector(gyro, 3)).append('\n');
        if (rotation != null) summary.append("회전 벡터: 수신 중");
        return summary.length() == 0 ? "가속도·각속도·방향 데이터 대기" : summary.toString().trim();
    }

    private String formatVector(float[] values, int count) {
        return String.format(Locale.US, "[%.3f, %.3f, %.3f]", values[0], values[1], values[2]);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void renderNoResult(String reason) {
        weightView.setText("중량 데이터 없음"); weightView.setTextColor(Color.WHITE);
        stateView.setText("측정 대기"); stateView.setTextColor(MUTED);
        frameView.setText("frame_id  —"); sourceView.setText("출처  —"); modelView.setText("model_id  —");
        featureView.setText("feature_version  —"); qualityView.setText("quality  unknown");
        errorView.setText(reason); errorView.setTextColor(Color.rgb(255, 211, 117));
    }

    @Override protected void onResume() { super.onResume(); refreshUsbDevices(); }
    // USB permission UI can temporarily pause this Activity. Keep the pending SDK
    // connection alive; the user can stop explicitly or onDestroy will clean up.
    @Override protected void onPause() { super.onPause(); }
    @Override protected void onDestroy() {
        stopRealSense();
        if (saveExecutor != null) { saveExecutor.shutdown(); saveExecutor = null; }
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            boolean startDepth = pendingRealSenseStart;
            pendingRealSenseStart = false;
            if (granted && startDepth) startRealSense();
            else if (!granted && startDepth)
                cameraStatus.setText("Android 카메라 접근 권한이 거부되어 RealSense SDK를 시작하지 않았습니다.");
        }
    }

}
