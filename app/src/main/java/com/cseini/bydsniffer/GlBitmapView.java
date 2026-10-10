package com.cseini.bydsniffer;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

// ★855 씨라7 카메라 보조 창 — 비틀런처(1.74 CamOverlay·FisheyeCamView)처럼 GLSurfaceView에 GPU로 그린다(운영자 10-09 "비틀런처 방식이 검증됐으니 따라가").
//   씨라7은 창 버퍼를 CPU로 잠가 직접 쓰는 길(HdRender·ANativeWindow_lock)에서 surfaceflinger 그래픽 버퍼 크래시로 재부팅됐다.
//   받은 Bitmap을 텍스처로 올려 비율 그대로(가운데 맞춤) 한 장 그린다. show()는 아무 스레드에서나 불러도 된다.
final class GlBitmapView extends GLSurfaceView {
    private final Rend rend = new Rend();

    GlBitmapView(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setRenderer(rend);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /** null이면 검정 화면. */
    void show(Bitmap b) { rend.pending = b; rend.clearReq = (b == null); requestRender(); }

    private static final class Rend implements Renderer {
        volatile Bitmap pending;
        volatile boolean clearReq;
        private int prog, tex, aPos, aUv, uTex, vw, vh, bw, bh;
        private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

        private static int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
            prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER,
                    "attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){ vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }"));
            GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER,
                    "precision mediump float; varying vec2 vUv; uniform sampler2D uTex; void main(){ gl_FragColor = texture2D(uTex, vUv); }"));
            GLES20.glLinkProgram(prog);
            aPos = GLES20.glGetAttribLocation(prog, "aPos");
            aUv = GLES20.glGetAttribLocation(prog, "aUv");
            uTex = GLES20.glGetUniformLocation(prog, "uTex");
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            tex = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            bw = bh = 0;
        }

        @Override public void onSurfaceChanged(GL10 gl, int w, int h) { vw = w; vh = h; GLES20.glViewport(0, 0, w, h); }

        @Override public void onDrawFrame(GL10 gl) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            Bitmap b = pending;
            if (clearReq) { clearReq = false; bw = bh = 0; return; }
            if (b != null && !b.isRecycled()) {
                pending = null;
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
                bw = b.getWidth(); bh = b.getHeight();
            }
            if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) return;
            float s = Math.min((float) vw / bw, (float) vh / bh);   // 가운데 맞춤(비율 유지)
            float x = bw * s / vw, y = bh * s / vh;
            quad.clear();
            quad.put(new float[]{-x, -y, 0f, 1f,  x, -y, 1f, 1f,  -x, y, 0f, 0f,  x, y, 1f, 0f});
            GLES20.glUseProgram(prog);
            quad.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(aPos);
            quad.position(2); GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(aUv);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glUniform1i(uTex, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
    }
}
