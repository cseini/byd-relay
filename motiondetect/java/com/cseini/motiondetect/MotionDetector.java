package com.cseini.motiondetect;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

// ★공개 API — 릴레이/오토전비가 가져다 쓰는 모션감지 진입점.
//   카메라 NV21 프레임 + 자유형 폴리곤 ROI → ROI별 움직임%·트리거. ROI마다 독립 MOG2 배경모델.
//   각 ROI = 폴리곤 바운딩박스를 Y채널 고정 다운샘플(DS_W×DS_H) → MOG2, 폴리곤 마스크로 내부만 카운트.
//
// 사용:
//   MotionDetector md = new MotionDetector(frameW, frameH);
//   md.setRois(rois);
//   for each frame: Result[] r = md.onFrame(nv21, SystemClock.elapsedRealtime());
//   md.close();
public final class MotionDetector {
    private final int DS_W, DS_H;   // 기본 64x48 — 센트리 칸별 판정은 더 촘촘히(생성자로 지정)

    private final int srcW, srcH;
    private final byte[] dsBuf;
    private List<Roi> rois = new ArrayList<>();
    private long[] handles = new long[0];
    private long[] aboveSince = new long[0];
    private int[][] bbox = new int[0][];        // ROI별 바운딩박스 픽셀 [rx,ry,rw,rh]
    private byte[][] masks = new byte[0][];      // ROI별 폴리곤 마스크(DS_W*DS_H)
    private float learningRate = 0.03f, varThreshold = 16f, backgroundRatio = 0.9f;
    private final float[] fgStats = new float[4];
    private float weightInit = -1f;   // >0이면 새 물체 가우시안 초기 weight(Mog2 기본 0.05 대신) — 센트리는 0.008
    private float hpSigma = 0f; private byte[] hpBuf;   // >0이면 MOG2 입력에서 느린 조명 성분을 뺀다(IllumFilter)
    private float ovMinPct = -1f; private long ovSustain = -1L;   // >=0이면 ROI값 대신 전역 override(튜닝 슬라이더용)

    // 실시간 튜닝 — ROI별 minMotionPct/sustainMs 대신 전역 임계 적용. -1로 주면 다시 ROI값 사용.
    public synchronized void setThreshold(float minPct, long sustainMs) { ovMinPct = minPct; ovSustain = sustainMs; }

    public static final class Result {
        public final int roiId;
        public final float motionPct;
        public final float verticalCenter;
        public final boolean triggered;
        public final int meanY;   // 이 영역(폴리곤 안) 평균 밝기 0~255 — 조명 변화 거르기용
        public final float cx, cy, bw, bh;   // 전경 무게중심·바운딩박스 폭·높이(0~1, 영역 대비). 전경 없으면 -1 — 센트리 근접 미달 기록용
        Result(int id, float pct, float vc, boolean t, int meanY, float cx, float cy, float bw, float bh) {
            roiId = id; motionPct = pct; verticalCenter = vc; triggered = t; this.meanY = meanY;
            this.cx = cx; this.cy = cy; this.bw = bw; this.bh = bh;
        }
    }

    public MotionDetector(int frameW, int frameH) { this(frameW, frameH, 64, 48); }

    public MotionDetector(int frameW, int frameH, int dsW, int dsH) {
        this.srcW = frameW; this.srcH = frameH; this.DS_W = dsW; this.DS_H = dsH;
        this.dsBuf = new byte[dsW * dsH];
    }

    // 조명 변화(점멸·서서히 밝기 바뀜) 제거 켜기 — sigmaPx는 다운샘플 해상도 기준 픽셀. 0이면 끔(기본). 평균 밝기(Result.meanY)는 제거 전 값.
    public synchronized void setIlluminationHighPass(float sigmaPx) { hpSigma = sigmaPx; hpBuf = sigmaPx > 0f ? new byte[DS_W * DS_H] : null; }

    // 새로 나타난 물체가 배경으로 흡수되는 속도 — 작을수록 지나가는 물체가 오래 전경으로 남는다. 0 이하면 기본값.
    public synchronized void setWeightInit(float w) {
        weightInit = w;
        if (w > 0f) for (long h : handles) Mog2Native.setWeightInit(h, w);
    }

    public synchronized void setParams(float learningRate, float varThreshold, float backgroundRatio) {
        this.learningRate = learningRate; this.varThreshold = varThreshold; this.backgroundRatio = backgroundRatio;
        for (long h : handles) Mog2Native.setParams(h, learningRate, varThreshold, backgroundRatio);
    }

    public synchronized void setRois(List<Roi> newRois) {
        releaseHandles();
        rois = new ArrayList<>(newRois);
        int n = rois.size();
        handles = new long[n]; aboveSince = new long[n]; bbox = new int[n][]; masks = new byte[n][];
        for (int i = 0; i < n; i++) {
            long h = Mog2Native.create();
            Mog2Native.initialize(h, DS_W, DS_H);
            Mog2Native.setParams(h, learningRate, varThreshold, backgroundRatio);
            if (weightInit > 0f) Mog2Native.setWeightInit(h, weightInit);
            handles[i] = h;
            buildBBoxAndMask(i, rois.get(i));
        }
    }

    // 폴리곤 → 바운딩박스(픽셀) + DS 해상도 마스크(폴리곤 내부 255).
    private void buildBBoxAndMask(int i, Roi roi) {
        float minx = 1f, miny = 1f, maxx = 0f, maxy = 0f;
        for (int k = 0; k < roi.nx.length; k++) {
            minx = Math.min(minx, roi.nx[k]); maxx = Math.max(maxx, roi.nx[k]);
            miny = Math.min(miny, roi.ny[k]); maxy = Math.max(maxy, roi.ny[k]);
        }
        float bw = Math.max(1e-4f, maxx - minx), bh = Math.max(1e-4f, maxy - miny);
        bbox[i] = new int[]{ (int)(minx * srcW), (int)(miny * srcH), (int)(bw * srcW), (int)(bh * srcH) };

        Bitmap bmp = Bitmap.createBitmap(DS_W, DS_H, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(bmp);
        Path p = new Path();
        for (int k = 0; k < roi.nx.length; k++) {
            float px = (roi.nx[k] - minx) / bw * DS_W;
            float py = (roi.ny[k] - miny) / bh * DS_H;
            if (k == 0) p.moveTo(px, py); else p.lineTo(px, py);
        }
        p.close();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL);
        c.drawPath(p, paint);
        byte[] m = new byte[DS_W * DS_H];
        bmp.copyPixelsToBuffer(ByteBuffer.wrap(m));   // ALPHA_8 → 1byte/px(내부 255)
        bmp.recycle();
        masks[i] = m;
    }

    public synchronized Result[] onFrame(byte[] nv21, long tsMs) {
        Result[] out = new Result[rois.size()];
        for (int i = 0; i < rois.size(); i++) {
            int[] b = bbox[i];
            YuvNative.extractYRoiDownsample(nv21, srcW, srcH, b[0], b[1], b[2], b[3], DS_W, DS_H, dsBuf);
            int meanY = maskedMean(dsBuf, masks[i]);
            byte[] in = dsBuf;
            if (hpSigma > 0f) { IllumFilter.highPass(dsBuf, DS_W, DS_H, hpSigma, hpBuf); in = hpBuf; }
            float pct = Mog2Native.apply(handles[i], in, masks[i]);
            float vc = Mog2Native.motionVerticalCenter(handles[i]);

            boolean trig = false;
            Roi roi = rois.get(i);
            float minPct = ovMinPct >= 0 ? ovMinPct : roi.minMotionPct;
            long sustain = ovSustain >= 0 ? ovSustain : roi.sustainMs;
            if (pct >= minPct) {
                if (aboveSince[i] == 0) aboveSince[i] = tsMs;
                if (tsMs - aboveSince[i] >= sustain) trig = true;
            } else {
                aboveSince[i] = 0;
            }
            float[] st = fgStats;
            boolean has = pct > 0f && Mog2Native.foregroundStats(handles[i], st);
            out[i] = new Result(roi.id, pct, vc, trig, meanY, has ? st[0] : -1f, has ? st[1] : -1f, has ? st[2] : -1f, has ? st[3] : -1f);
        }
        return out;
    }

    // 폴리곤 안 픽셀의 평균 밝기
    private static int maskedMean(byte[] y, byte[] mask) {
        long sum = 0; int n = 0;
        for (int k = 0; k < y.length; k++) {
            if (mask == null || mask[k] != 0) { sum += (y[k] & 0xFF); n++; }
        }
        return n > 0 ? (int) (sum / n) : 0;
    }

    // 영역 하나만 배경을 다시 잡는다(칸별 조명 변화 대응). 지속 판정도 처음부터.
    public synchronized void resetRoi(int i) {
        if (i < 0 || i >= handles.length) return;
        Mog2Native.reset(handles[i]);
        aboveSince[i] = 0;
    }

    public synchronized void reset() {
        for (long h : handles) Mog2Native.reset(h);
        for (int i = 0; i < aboveSince.length; i++) aboveSince[i] = 0;
    }

    public synchronized void close() { releaseHandles(); rois = new ArrayList<>(); }

    private void releaseHandles() {
        for (long h : handles) if (h != 0) Mog2Native.destroy(h);
        handles = new long[0]; aboveSince = new long[0]; bbox = new int[0][]; masks = new byte[0][];
    }
}
