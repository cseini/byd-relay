package com.cseini.motiondetect;

// NV21 Y채널 ROI 추출+다운샘플 native 바인딩.
final class YuvNative {
    static { System.loadLibrary("motiondetect"); }
    private YuvNative() {}

    // nv21의 Y플레인에서 소스픽셀 ROI(rx,ry,rw,rh)를 dstW×dstH 박스평균 다운샘플 → dst(dstW*dstH).
    static native void extractYRoiDownsample(byte[] nv21, int srcW, int srcH,
                                             int rx, int ry, int rw, int rh,
                                             int dstW, int dstH, byte[] dst);

    // nv21 ROI → RGB(dstW*dstH*3, RGB순서) 크롭+리사이즈. 킥 트렁크 TFLite 전처리용.
    static native void extractRgbRoi(byte[] nv21, int srcW, int srcH,
                                     int rx, int ry, int rw, int rh,
                                     int dstW, int dstH, byte[] rgbOut);

    // ★de-warp(2026-09-03) — 등거리 어안→직선 remap LUT. buildDewarpLut은 ROI 확정 시 1회(네이티브 핸들 반환),
    //   remapRgbWithLut은 킥 crop마다 호출(LUT 조회만), freeDewarpLut으로 해제. cx,cy,fovDeg는 풀프레임 기준.
    static native long buildDewarpLut(int srcW, int srcH, int roiX, int roiY, int roiW, int roiH,
                                      float fovDeg, float cx, float cy, int dstW, int dstH, float rollDeg, boolean pano, float tiltDeg, float k);   // ★664 pano=원통 파노라마 ★656 rollDeg=기울기(광축 둘레 회전, 0=종전) ★712 k=렌즈 왜곡(0=등거리)
    static native long buildDewarpLutT(int srcW, int srcH, int roiX, int roiY, int roiW, int roiH,
                                       float fovDeg, float cx, float cy, int dstW, int dstH, float rollDeg, float tiltDeg, float k);   // ★718 가로 직선(차 좌우축 둘레 원통)
    static native float dewarpAspectT(int srcW, int srcH, int roiX, int roiY, int roiW, int roiH, float fovDeg, float cx, float cy, float rollDeg, float k);   // ★718 가로 직선 출력 세로÷가로
    static native long buildDewarpLutTWin(int srcW, int srcH, float fovDeg, float cx, float cy, float rollDeg, float k,
                                          float a0Deg, float a1Deg, float b0Deg, float b1Deg, int dstW, int dstH);   // ★719 각도 창 가로 직선(★734 후진 화면 후방 칸)
    static native void remapRgbWithLut(long lutHandle, byte[] nv21, int srcW, int srcH, byte[] rgbOut);
    static native void freeDewarpLut(long lutHandle);
    // ★617 클러스터 측면캠 커스텀 — 선형 크롭 LUT + NV21 출력 remap(offX/offY=타일 원점, mirror=좌우반전)
    static native long buildCropLut(int roiX, int roiY, int roiW, int roiH, int dstW, int dstH, float rollDeg, int clampW, int clampH);   // ★656 rollDeg=기울기, clampW/H=타일 크기(0=클램프 안 함)
    static native void remapNv21WithLut(long lutHandle, byte[] nv21, int frameW, int frameH, int offX, int offY, int mirror, byte[] out);
    // ★733 헤드유닛 창 직접 그리기(HdRender) — Surface 창 버퍼에 LUT 칸들을 RGBX로 바로 쓴다.
    static native long winAcquire(android.view.Surface surface);
    static native void winRelease(long win);
    static native int winRender(long win, byte[] nv21, int frameW, int frameH, long[] luts, int[] meta, int contentW, int contentH, int bufW, int bufH);
    static native int winClear(long win, int bufW, int bufH);
    static native void setWarpThreads(int n);   // ★877+ 창 어안 펴기 보조 스레드 수(1~6, 기본 2)
    static native long[] winStats();   // ★764 {장 수, 잠금, 지우기, 펴기, 올리기 마이크로초 합} — 읽으면 0으로
}
