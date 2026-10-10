package com.cseini.motiondetect;

// ★617 클러스터 측면캠 커스텀 뷰(영역·어안보정·좌우반전) — 릴레이(다른 패키지)용 공개 파사드.
//   LUT는 설정 변경 시 1회 생성, 매 프레임은 remap(표 조회)만. 좌표는 모두 '타일'(쿼드 한 칸) 픽셀 기준.
public final class ViewWarp {
    private long lut; private final int dstW, dstH, tileX, tileY; private final boolean mirror;

    /** dewarp=true면 등거리 어안→직선(가상카메라가 영역 중심을 바라봄, fovDeg=렌즈 화각 추정치), false면 선형 크롭. rollDeg=기울기(★656, 0=종전).
     *  ★712 lensCx/lensCy=렌즈 중심이 타일 가운데에서 벗어난 비율(타일 폭·높이 기준), k=렌즈 왜곡(r=f·(θ+kθ³)) — 셋 다 0이면 종전. */
    public ViewWarp(int tileX, int tileY, int tileW, int tileH, int roiX, int roiY, int roiW, int roiH,
                    boolean dewarp, float fovDeg, float rollDeg, boolean mirror, int dstW, int dstH, boolean pano, float tiltDeg,
                    float lensCx, float lensCy, float k) {
        this(tileX, tileY, tileW, tileH, roiX, roiY, roiW, roiH, dewarp, fovDeg, rollDeg, mirror, dstW, dstH, pano, tiltDeg, lensCx, lensCy, k, false);
    }
    /** ★718 transverse=true(dewarp일 때)면 가로 직선 — 범퍼·주차선 같은 가로선이 곧다(pano 무시, tiltDeg=세로 창 위로 이동).
     *  dstH를 transverseAspect 비율로 잡으면 영역 그대로, 다르면 가운데 기준으로 잘라 맞춘다(늘이지 않음). */
    public ViewWarp(int tileX, int tileY, int tileW, int tileH, int roiX, int roiY, int roiW, int roiH,
                    boolean dewarp, float fovDeg, float rollDeg, boolean mirror, int dstW, int dstH, boolean pano, float tiltDeg,
                    float lensCx, float lensCy, float k, boolean transverse) {
        this.tileX = tileX; this.tileY = tileY; this.mirror = mirror; this.dstW = dstW; this.dstH = dstH;
        float lcx = tileW * (0.5f + lensCx), lcy = tileH * (0.5f + lensCy);
        lut = !dewarp ? YuvNative.buildCropLut(roiX, roiY, roiW, roiH, dstW, dstH, rollDeg, tileW, tileH)
            : transverse ? YuvNative.buildDewarpLutT(tileW, tileH, roiX, roiY, roiW, roiH, fovDeg, lcx, lcy, dstW, dstH, rollDeg, tiltDeg, k)
            : YuvNative.buildDewarpLut(tileW, tileH, roiX, roiY, roiW, roiH, fovDeg, lcx, lcy, dstW, dstH, rollDeg, pano, tiltDeg, k);
    }
    private ViewWarp(int tileX, int tileY, boolean mirror, int dstW, int dstH, long lut) {
        this.tileX = tileX; this.tileY = tileY; this.mirror = mirror; this.dstW = dstW; this.dstH = dstH; this.lut = lut;
    }
    /** ★719 가로 직선을 각도 창(좌우 a0~a1°·위아래 b0~b1°, 아래 +)으로 직접 지정 — ★734 후진 화면 A·B·C의 후방 칸. */
    public static ViewWarp transverseWindow(int tileX, int tileY, int tileW, int tileH, float fovDeg, float rollDeg, float lensCx, float lensCy, float k,
                                            float a0Deg, float a1Deg, float b0Deg, float b1Deg, boolean mirror, int dstW, int dstH) {
        return new ViewWarp(tileX, tileY, mirror, dstW, dstH, YuvNative.buildDewarpLutTWin(tileW, tileH, fovDeg, tileW * (0.5f + lensCx), tileH * (0.5f + lensCy),
                rollDeg, k, a0Deg, a1Deg, b0Deg, b1Deg, dstW, dstH));
    }
    /** ★718 가로 직선 출력의 세로÷가로 비율(영역의 각도 범위 기준). */
    public static float transverseAspect(int tileW, int tileH, int roiX, int roiY, int roiW, int roiH, float fovDeg, float rollDeg, float lensCx, float lensCy, float k) {
        return YuvNative.dewarpAspectT(tileW, tileH, roiX, roiY, roiW, roiH, fovDeg, tileW * (0.5f + lensCx), tileH * (0.5f + lensCy), rollDeg, k);
    }
    public int width() { return dstW; }
    public int height() { return dstH; }
    // ★733 HdRender(같은 패키지)가 창 버퍼에 바로 그릴 때 쓴다.
    long lutHandle() { return lut; }
    public int tileX() { return tileX; }
    int tileY() { return tileY; }
    boolean mirrored() { return mirror; }
    /** out = dstW*dstH*3/2 바이트(NV21). 생성 실패(lut=0)면 false. */
    public boolean remap(byte[] nv21, int frameW, int frameH, byte[] out) {
        if (lut == 0) return false;
        YuvNative.remapNv21WithLut(lut, nv21, frameW, frameH, tileX, tileY, mirror ? 1 : 0, out);
        return true;
    }
    public void close() { if (lut != 0) { YuvNative.freeDewarpLut(lut); lut = 0; } }
}
