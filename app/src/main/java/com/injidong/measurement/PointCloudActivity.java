package com.injidong.measurement;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Displays existing review points received from the authenticated server API. */
public final class PointCloudActivity extends Activity {
    private PointCloudView viewer;
    private TextView empty, status;
    private Button reload;
    private final java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int background = Color.rgb(11, 17, 27);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(background);
        page.setPadding(dp(16), dp(12), dp(16), dp(12));
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            page.setPadding(dp(16), dp(12) + insets.getSystemWindowInsetTop(),
                    dp(16), dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        Button back = new Button(this);
        back.setText("측정 화면으로 돌아가기");
        back.setOnClickListener(v -> finish());
        page.addView(back);
        TextView title = label("3D 포인트 클라우드", 22);
        page.addView(title);
        page.addView(label("한 손가락 회전 · 두 손가락 이동 · 핀치 확대/축소", 13));
        FrameLayout viewport = new FrameLayout(this);
        viewer = new PointCloudView(this);
        viewport.addView(viewer, new FrameLayout.LayoutParams(-1, -1));
        empty = label("포인트 클라우드를 불러오는 중…", 16);
        empty.setGravity(Gravity.CENTER);
        viewport.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        page.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        status = label("서버 연결 대기", 13);
        page.addView(status);
        reload = new Button(this);
        reload.setText("서버에서 다시 불러오기");
        reload.setOnClickListener(v -> loadCloud());
        page.addView(reload);
        Button reset = new Button(this);
        reset.setText("시점 초기화");
        reset.setOnClickListener(v -> viewer.resetView());
        page.addView(reset);
        setContentView(page);
        loadCloud();
    }

    private void loadCloud() {
        reload.setEnabled(false);
        status.setText("HTTPS API에서 기존 검토 데이터 수신 중…");
        loader.execute(() -> {
            try {
                PointCloudApi.Cloud cloud = PointCloudApi.load();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    viewer.setPointCloud(cloud.xyz, cloud.rgb);
                    empty.setVisibility(android.view.View.GONE);
                    status.setText("INJIDONG Review · " + java.text.NumberFormat.getIntegerInstance().format(cloud.xyz.length/3)
                            + " points · 원본 좌표 단위 m");
                    reload.setEnabled(true);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (empty.getVisibility() == android.view.View.VISIBLE)
                        empty.setText("포인트 클라우드를 불러오지 못했습니다\n서버 연결 후 다시 시도하세요");
                    status.setText(failure instanceof java.io.IOException
                            && failure.getClass() == java.io.IOException.class ? failure.getMessage()
                            : "인터넷 연결과 서버·HTTPS 터널 주소를 확인하세요.");
                    reload.setEnabled(true);
                });
            }
        });
    }

    private TextView label(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); text.setTextColor(Color.rgb(200, 213, 231));
        text.setPadding(0, dp(8), 0, dp(8));
        return text;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override protected void onResume() { super.onResume(); viewer.onResume(); }
    @Override protected void onPause() { viewer.onPause(); super.onPause(); }
    @Override protected void onDestroy() { loader.shutdownNow(); super.onDestroy(); }
}
