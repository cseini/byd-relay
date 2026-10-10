package com.cseini.motiondetect;

// 감지 영역 하나 — 자유형 폴리곤. 정점은 프레임 대비 정규화(0~1)라 카메라 해상도 무관.
// nx/ny 길이가 같아야 하며 폴리곤 정점 순서대로. minMotionPct 이상이 sustainMs 지속되면 triggered.
public final class Roi {
    public final int id;
    public final float[] nx, ny;        // 정규화(0~1) 폴리곤 정점
    public final float minMotionPct;
    public final long  sustainMs;

    public Roi(int id, float[] nx, float[] ny, float minMotionPct, long sustainMs) {
        this.id = id; this.nx = nx; this.ny = ny;
        this.minMotionPct = minMotionPct; this.sustainMs = sustainMs;
    }
}
