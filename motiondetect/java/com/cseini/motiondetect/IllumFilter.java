package com.cseini.motiondetect;

// 조명 변화 제거 — 영상에서 천천히·넓게 바뀌는 밝기 성분(조명 점멸·밝기 서서히 바뀜·자동 노출)을 가우시안 흐림으로 구해 빼고 128을 더한다.
// 움직이는 사람·차의 가장자리·무늬 같은 좁은 변화만 남아서 MOG2가 조명 변화를 움직임으로 보지 않는다.
// 센트리 10-08 실영상(지하주차장, 움직임 없이 조명만 바뀐 8.5초): 칸별 움직임 비율 최고 43~60% → 3% 미만. 합성 이동 물체(사람·차)는 원본과 비슷하거나 더 크게 잡힘.
public final class IllumFilter {
    private IllumFilter() {}

    // y(w*h) → dst. sigmaPx<=0이면 그대로 복사. 순수 계산이라 JVM에서 시험한다.
    // 가우시안 흐림을 박스 흐림 3번(표준 근사: 폭 [7,7,9], σ≈4)으로 한다 — 화소당 비용이 폭과 무관(누적합)이라 예전 21탭 가우시안보다 ~7배 빠르다
    // (재생 시험에서 감지 한 번이 29ms 걸려 대부분이 이 필터였다, 10-09 실차).
    public static void highPass(byte[] y, int w, int h, float sigmaPx, byte[] dst) {
        if (sigmaPx <= 0f) {
            System.arraycopy(y, 0, dst, 0, w * h);
            return;
        }
        int[] sizes = boxSizes(sigmaPx, 3);
        float[] a = new float[w * h], b = new float[w * h];
        for (int i = 0; i < a.length; i++) a[i] = y[i] & 0xFF;
        for (int k = 0; k < 3; k++) {
            int r = (sizes[k] - 1) / 2;
            boxH(a, b, w, h, r);
            boxV(b, a, w, h, r);
        }
        for (int i = 0; i < a.length; i++) {
            int v = Math.round((y[i] & 0xFF) - a[i] + 128f);
            dst[i] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
        }
    }

    // 박스 3번으로 가우시안 σ를 근사하는 폭(홀수) — 표준 "boxes for gauss"
    static int[] boxSizes(float sigma, int n) {
        double wIdeal = Math.sqrt(12.0 * sigma * sigma / n + 1);
        int wl = (int) Math.floor(wIdeal);
        if (wl % 2 == 0) wl--;
        int wu = wl + 2;
        double mIdeal = (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0);
        int m = (int) Math.round(mIdeal);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = i < m ? wl : wu;
        return out;
    }

    // 가장자리는 끝 값을 되풀이(예전 필터와 같은 경계 처리). 누적합이라 화소당 연산이 폭과 무관
    private static void boxH(float[] src, float[] dst, int w, int h, int r) {
        float inv = 1f / (2 * r + 1);
        for (int row = 0; row < h; row++) {
            int o = row * w;
            float sum = (r + 1) * src[o];
            for (int i = 1; i <= r; i++) sum += src[o + Math.min(i, w - 1)];
            for (int x = 0; x < w; x++) {
                dst[o + x] = sum * inv;
                sum += src[o + Math.min(x + r + 1, w - 1)] - src[o + Math.max(x - r, 0)];
            }
        }
    }

    private static void boxV(float[] src, float[] dst, int w, int h, int r) {
        float inv = 1f / (2 * r + 1);
        for (int x = 0; x < w; x++) {
            float sum = (r + 1) * src[x];
            for (int i = 1; i <= r; i++) sum += src[Math.min(i, h - 1) * w + x];
            for (int row = 0; row < h; row++) {
                dst[row * w + x] = sum * inv;
                sum += src[Math.min(row + r + 1, h - 1) * w + x] - src[Math.max(row - r, 0) * w + x];
            }
        }
    }
}
