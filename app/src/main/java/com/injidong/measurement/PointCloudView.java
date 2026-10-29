package com.injidong.measurement;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.MotionEvent;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** OpenGL ES point renderer. Starts empty; accepts XYZ and RGB arrays from a future data source. */
public final class PointCloudView extends GLSurfaceView {
    private final CloudRenderer cloud = new CloudRenderer();
    private float lastX, lastY, lastSpan;
    private int lastCount;

    public PointCloudView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setRenderer(cloud);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setContentDescription("3D 포인트 클라우드 뷰어. 한 손가락 회전, 두 손가락 이동, 핀치 확대 및 축소");
    }

    /** Copies supplied data. XYZ is centered and scaled for display; original arrays remain untouched. */
    public void setPointCloud(float[] xyz, float[] rgb) {
        if (xyz == null || xyz.length % 3 != 0 || (rgb != null && rgb.length != xyz.length))
            throw new IllegalArgumentException("XYZ and RGB must contain matching triples");
        for (float value : xyz) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite XYZ");
        if (rgb != null) for (float value : rgb)
            if (!Float.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("RGB must be in 0..1");
        float[] points = xyz.clone(), colors = rgb == null ? null : rgb.clone();
        queueEvent(() -> { cloud.setPoints(points, colors); cloud.reset(); });
        requestRender();
    }

    public void resetView() { queueEvent(cloud::reset); requestRender(); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            getParent().requestDisallowInterceptTouchEvent(false);
            lastCount = 0;
            if (action == MotionEvent.ACTION_UP) performClick();
            return true;
        }
        int count = event.getPointerCount();
        float x = event.getX(), y = event.getY(), span = 0;
        if (count >= 2) {
            x = (x + event.getX(1)) / 2; y = (y + event.getY(1)) / 2;
            span = (float)Math.hypot(event.getX() - event.getX(1), event.getY() - event.getY(1));
        }
        if (action == MotionEvent.ACTION_MOVE && count == lastCount) {
            float dx = (x - lastX) / Math.max(1, getWidth());
            float dy = (y - lastY) / Math.max(1, getHeight());
            if (count == 1) queueEvent(() -> {
                cloud.yaw = (cloud.yaw + dx * 240) % 360;
                cloud.pitch = Math.max(-89, Math.min(89, cloud.pitch + dy * 180));
            });
            else {
                float ratio = lastSpan > 0 && span > 0 ? lastSpan / span : 1;
                queueEvent(() -> {
                    cloud.distance = Math.max(1.3f, Math.min(15, cloud.distance * ratio));
                    cloud.panX += dx * cloud.distance * cloud.aspect;
                    cloud.panY -= dy * cloud.distance;
                });
            }
            requestRender();
        }
        lastX = x; lastY = y; lastSpan = span;
        lastCount = action == MotionEvent.ACTION_POINTER_UP ? 0 : count;
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }

    private static final class CloudRenderer implements Renderer {
        private FloatBuffer vertices;
        private int count, program, position, color, transform;
        private float yaw, pitch, panX, panY, distance = 3.5f, aspect = 1;
        private final float[] projection = new float[16], view = new float[16], model = new float[16];
        private final float[] vp = new float[16], mvp = new float[16];
        void reset() { yaw = pitch = panX = panY = 0; distance = 3.5f; }
        void setPoints(float[] xyz, float[] rgb) {
            count = xyz.length / 3;
            if (count == 0) { vertices = null; return; }
            double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int i = 0; i < xyz.length; i++) { int a = i % 3; min[a] = Math.min(min[a], xyz[i]); max[a] = Math.max(max[a], xyz[i]); }
            double scale = Math.max(max[0]-min[0], Math.max(max[1]-min[1], max[2]-min[2]));
            if (scale == 0) scale = 1;
            vertices = ByteBuffer.allocateDirect(Math.multiplyExact(count, 24)).order(ByteOrder.nativeOrder()).asFloatBuffer();
            for (int i = 0; i < count; i++) {
                for (int a = 0; a < 3; a++) vertices.put((float)((xyz[i*3+a] - (min[a]+max[a])/2) / scale * 1.8));
                for (int a = 0; a < 3; a++) vertices.put(rgb == null ? (a == 0 ? .3f : .8f) : rgb[i*3+a]);
            }
            vertices.position(0);
        }
        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(.035f, .055f, .09f, 1);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            int vertex = shader(GLES20.GL_VERTEX_SHADER, "uniform mat4 uMvp; attribute vec3 aPosition; attribute vec3 aColor; varying vec3 vColor; void main(){ gl_Position=uMvp*vec4(aPosition,1.0); gl_PointSize=3.0; vColor=aColor; }");
            int fragment = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec3 vColor; void main(){ vec2 p=gl_PointCoord-vec2(0.5); if(dot(p,p)>0.25) discard; gl_FragColor=vec4(vColor,1.0); }");
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program);
            int[] ok = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("Point renderer link failed");
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
            position = GLES20.glGetAttribLocation(program, "aPosition");
            color = GLES20.glGetAttribLocation(program, "aColor");
            transform = GLES20.glGetUniformLocation(program, "uMvp");
        }
        private static int shader(int type, String source) {
            int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
            int[] ok = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("Point renderer shader failed");
            return shader;
        }
        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height); aspect = (float)width / Math.max(1, height);
            Matrix.perspectiveM(projection, 0, 45, aspect, .1f, 50);
        }
        @Override public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            if (vertices == null || count == 0) return;
            Matrix.setLookAtM(view, 0, 0, 0, distance, 0, 0, 0, 0, 1, 0);
            Matrix.setIdentityM(model, 0); Matrix.translateM(model, 0, panX, panY, 0);
            Matrix.rotateM(model, 0, pitch, 1, 0, 0); Matrix.rotateM(model, 0, yaw, 0, 1, 0);
            Matrix.multiplyMM(vp, 0, projection, 0, view, 0); Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
            GLES20.glUseProgram(program); GLES20.glUniformMatrix4fv(transform, 1, false, mvp, 0);
            vertices.position(0); GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 24, vertices);
            vertices.position(3); GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 24, vertices);
            GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(color);
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count);
            GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(color);
        }
    }
}
