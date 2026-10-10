package com.cseini.motiondetect;

// ★733 헤드유닛 창 직접 그리기(운영자 10-04 "헤드유닛은 고프레임 고해상도") — 카메라 NV21을 ViewWarp LUT로 펴면서 Surface 창 버퍼(RGBX)에 바로 쓴다.
//   폰용 JPEG(작게 압축→다시 풀기)를 거치지 않아 창 크기 그대로·카메라 프레임 그대로 그린다. 한 스레드에서만 쓴다(호출측 동기화).
public final class HdRender {
    private long win;

    public boolean attach(android.view.Surface s) { release(); win = YuvNative.winAcquire(s); return win != 0; }
    public void release() { if (win != 0) { YuvNative.winRelease(win); win = 0; } }
    public boolean attached() { return win != 0; }

    /** warps=칸(왼쪽부터), xs=내용 안 칸의 x. 내용(contentW×contentH)은 버퍼(bufW×bufH) 가운데.
     *  반환 0=그림 · -1=창 없음 · -2=잠금 실패 · -3=내용이 버퍼를 넘음 · -4=프레임 배열 못 얻음. */
    public int render(byte[] nv21, int frameW, int frameH, ViewWarp[] warps, int[] xs, int contentW, int contentH, int bufW, int bufH) {
        if (win == 0) return -1;
        long[] luts = new long[warps.length];
        int[] meta = new int[warps.length * 4];
        for (int i = 0; i < warps.length; i++) {
            ViewWarp w = warps[i];
            luts[i] = w.lutHandle();
            meta[i * 4] = w.tileX(); meta[i * 4 + 1] = w.tileY(); meta[i * 4 + 2] = w.mirrored() ? 1 : 0; meta[i * 4 + 3] = xs[i];
        }
        return YuvNative.winRender(win, nv21, frameW, frameH, luts, meta, contentW, contentH, bufW, bufH);
    }

    public int clear(int bufW, int bufH) { return win == 0 ? -1 : YuvNative.winClear(win, bufW, bufH); }

    /** ★764 지난 호출 뒤 그린 장들의 구간별 평균(ms) "잠금 a · 지우기 b · 펴기 c · 올리기 d", 그린 장이 없으면 "". */
    public static String stats() {
        long[] v = YuvNative.winStats();
        if (v == null || v.length < 5 || v[0] <= 0) return "";
        long n = v[0];
        return String.format(java.util.Locale.US, "잠금 %.1f · 지우기 %.1f · 펴기 %.1f · 올리기 %.1fms", v[1] / 1000.0 / n, v[2] / 1000.0 / n, v[3] / 1000.0 / n, v[4] / 1000.0 / n);
    }
}
