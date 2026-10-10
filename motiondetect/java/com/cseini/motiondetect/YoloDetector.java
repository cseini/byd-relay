package com.cseini.motiondetect;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

// 센트리 '사람·차량 거르기'용 물체 인식 — YOLO11n(COCO 80종, Ultralytics, AGPL-3.0) TFLite, 입력 416x416 RGB(0~1, NCHW), 출력 [1,84,N](앞 4행=상자, 뒤 80행=클래스 점수).
//   센트리는 '그 칸에 사람(0)·차량(자전거1·자동차2·오토바이3·버스5·트럭7)이 있는가'의 최고 점수만 필요해 상자 해석·NMS 없이 클래스별 최댓값만 본다.
//   EfficientDet-Lite0(구)보다 어안 영상에서 차량 점수가 2~3배 높았다(10-07 맥 시험, 주차된 차 0.2→0.4~0.7). 소스 공개(AGPL) 조건은 THIRD_PARTY_NOTICES.md 참고.
public final class YoloDetector {
    private static final String TAG = "YoloDetector";
    public static final String MODEL = "models/yolo11n_416.tflite";
    private static final int[] VEHICLE = {1, 2, 3, 5, 7};

    private Interpreter itp;
    private int size = 416, nAnchors = 0, nOut = 84;
    private ByteBuffer inBuf;
    private FloatBuffer inF;
    private final byte[] rgb;
    private float[] chw;
    private float[][] out;   // [84][N]
    private volatile long lastMs;

    public YoloDetector(Context ctx) {
        byte[] rgbTmp;
        try {
            Interpreter.Options o = new Interpreter.Options();
            o.setNumThreads(4);
            itp = new Interpreter(loadModel(ctx, MODEL), o);
            int[] in = itp.getInputTensor(0).shape();       // [1,3,S,S]
            size = in[2];
            int[] os = itp.getOutputTensor(0).shape();       // [1,84,N]
            nOut = os[1];
            nAnchors = os[2];
            inBuf = ByteBuffer.allocateDirect(4 * 3 * size * size).order(ByteOrder.nativeOrder());
            inF = inBuf.asFloatBuffer();
            out = new float[nOut][nAnchors];
            rgbTmp = new byte[size * size * 3];
            chw = new float[3 * size * size];
            Log.i(TAG, "모델 로드 OK 입력 " + size + " 출력 " + nOut + "x" + nAnchors);
        } catch (Throwable t) {
            Log.e(TAG, "모델 로드 실패", t);
            rgbTmp = new byte[0];
            close();
        }
        rgb = rgbTmp;
    }

    public boolean isReady() { return itp != null; }

    public long lastInferMs() { return lastMs; }

    private static MappedByteBuffer loadModel(Context ctx, String path) throws Exception {
        AssetFileDescriptor afd = ctx.getAssets().openFd(path);
        try (FileInputStream fis = new FileInputStream(afd.getFileDescriptor())) {
            FileChannel fc = fis.getChannel();
            return fc.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getDeclaredLength());
        } finally {
            afd.close();
        }
    }

    // NV21 프레임의 한 영역(칸)에서 {사람 최고 점수, 차량 최고 점수}. 실패하면 null
    public synchronized float[] scores(byte[] nv21, int srcW, int srcH, int rx, int ry, int rw, int rh) {
        if (!infer(nv21, srcW, srcH, rx, ry, rw, rh)) return null;
        return best(out, nOut, nAnchors);
    }

    // 박스까지: {종류(1=차량 2=사람), 점수, x0, y0, x1, y1}(영역 대비 0~1) 목록, 점수 높은 순. 실패하면 null.
    // 센트리가 "무엇이 어디서 움직였나"를 알림·썸네일·표시에 쓰려는 용도(운영자 10-09, 오버드라이브처럼 박스+종류+%).
    public synchronized float[][] detect(byte[] nv21, int srcW, int srcH, int rx, int ry, int rw, int rh, float minScore) {
        if (!infer(nv21, srcW, srcH, rx, ry, rw, rh)) return null;
        return decode(out, nOut, nAnchors, minScore, size);
    }

    private boolean infer(byte[] nv21, int srcW, int srcH, int rx, int ry, int rw, int rh) {
        if (itp == null) return false;
        try {
            long t0 = System.nanoTime();
            YuvNative.extractRgbRoi(nv21, srcW, srcH, rx, ry, rw, rh, size, size, rgb);
            int plane = size * size;
            inF.clear();
            // RGB(HWC, 바이트) → CHW 실수 0~1
            for (int i = 0, p = 0; i < plane; i++, p += 3) {
                chw[i] = (rgb[p] & 0xFF) / 255f;
                chw[plane + i] = (rgb[p + 1] & 0xFF) / 255f;
                chw[2 * plane + i] = (rgb[p + 2] & 0xFF) / 255f;
            }
            inF.put(chw);
            inBuf.rewind();
            java.util.Map<Integer, Object> outs = new java.util.HashMap<>();
            float[][][] o3 = new float[1][][];
            o3[0] = out;
            outs.put(0, o3);
            itp.runForMultipleInputsOutputs(new Object[]{inBuf}, outs);
            lastMs = (System.nanoTime() - t0) / 1_000_000L;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "추론 실패 " + t);
            return false;
        }
    }

    // 출력 [84][N] → 박스 목록. 앵커마다 사람·차량 중 높은 쪽만 후보로, minScore 이상만, 같은 종류끼리 NMS(IoU 0.5), 최대 8개. 순수 계산이라 JVM에서 시험한다.
    // 박스 행(0~3)은 이 모델에서 0~1 정규화 중심 좌표(cx,cy,w,h)다(맥에서 같은 tflite로 확인 10-09). 픽셀(>1.5)로 나오는 모델이면 입력 크기로 나눈다.
    static float[][] decode(float[][] o, int rows, int n, float minScore, int inputSize) {
        if (rows < 4 + 8) return new float[0][];
        java.util.ArrayList<float[]> cand = new java.util.ArrayList<>();
        float maxBox = 0f;
        for (int a = 0; a < n; a++) {
            float person = o[4][a], vehicle = 0f;
            for (int c : VEHICLE) vehicle = Math.max(vehicle, o[4 + c][a]);
            int kind = person >= vehicle ? 2 : 1;
            float sc = Math.max(person, vehicle);
            if (sc < minScore) continue;
            float cx = o[0][a], cy = o[1][a], w = o[2][a], h = o[3][a];
            maxBox = Math.max(maxBox, Math.max(cx, cy));
            cand.add(new float[]{kind, sc, cx, cy, w, h});
        }
        float k = maxBox > 1.5f ? 1f / inputSize : 1f;
        java.util.ArrayList<float[]> boxes = new java.util.ArrayList<>();
        for (float[] c : cand) {
            float cx = c[2] * k, cy = c[3] * k, w = c[4] * k, h = c[5] * k;
            boxes.add(new float[]{c[0], c[1], clamp01(cx - w / 2), clamp01(cy - h / 2), clamp01(cx + w / 2), clamp01(cy + h / 2)});
        }
        java.util.Collections.sort(boxes, (x, y) -> Float.compare(y[1], x[1]));
        java.util.ArrayList<float[]> keep = new java.util.ArrayList<>();
        for (float[] b : boxes) {
            boolean dup = false;
            for (float[] kp : keep) if (kp[0] == b[0] && iou(kp, b) > 0.5f) { dup = true; break; }
            if (!dup) keep.add(b);
            if (keep.size() >= 8) break;
        }
        return keep.toArray(new float[0][]);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    static float iou(float[] a, float[] b) {
        float ix = Math.max(0f, Math.min(a[4], b[4]) - Math.max(a[2], b[2]));
        float iy = Math.max(0f, Math.min(a[5], b[5]) - Math.max(a[3], b[3]));
        float inter = ix * iy;
        float ua = (a[4] - a[2]) * (a[5] - a[3]) + (b[4] - b[2]) * (b[5] - b[3]) - inter;
        return ua <= 0f ? 0f : inter / ua;
    }

    // 출력 [84][N]에서 사람(행 4)·차량(행 4+1,2,3,5,7)의 최고 점수. 순수 계산이라 JVM에서 시험한다
    static float[] best(float[][] o, int rows, int n) {
        float person = 0f, vehicle = 0f;
        if (rows < 4 + 8) return new float[]{0f, 0f};
        for (int a = 0; a < n; a++) {
            float p = o[4][a];
            if (p > person) person = p;
            for (int c : VEHICLE) {
                float v = o[4 + c][a];
                if (v > vehicle) vehicle = v;
            }
        }
        return new float[]{person, vehicle};
    }

    public synchronized void close() {
        if (itp != null) { try { itp.close(); } catch (Throwable ignored) {} itp = null; }
    }
}
