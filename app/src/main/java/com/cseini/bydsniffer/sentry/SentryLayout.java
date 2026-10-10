package com.cseini.bydsniffer.sentry;

import org.json.JSONArray;

// 녹화 영상의 4분할 칸 배치·뒤집기 — 링크락 실시간 보기의 "배치"·"조정(뒤집기)"과 같은 규칙(워커 카메라 페이지 quadmap·camlive 합성과 1:1).
//   원본 프레임(AVM 4분할, 2x2)의 칸 번호 q = 0좌상·1우상·2좌하·3우하(행 우선, 카메라 a1~a4). 돌핀 실측: a1 후방·a2 왼쪽·a3 오른쪽·a4 전방.
//   map[d] = 도착 칸 d에 놓을 원본 칸 q (기본 [0,1,2,3] = 그대로). flip[q] = 원본 칸 q 카메라의 뒤집기 비트(1=가로, 2=세로, 3=둘 다) — 뒤집기는 원본 카메라에 붙는다.
//   감지(움직임)는 항상 원본 프레임에서 돌아 감지영역(ROI) 좌표는 배치와 무관하다. 순수 로직이라 JVM에서 검증한다.
public final class SentryLayout {
    private final int[] map;
    private final int[] flip;
    private final boolean identity;

    public SentryLayout(int[] map, int[] flip) {
        this.map = map.clone();
        this.flip = flip.clone();
        this.identity = isIdentity(map, flip);
    }

    public boolean isIdentity() { return identity; }

    // 도착 칸 d에 놓이는 원본 칸 번호 / 원본 칸 q의 뒤집기 비트(1=가로·2=세로) — 펴기(SentryDewarp)가 배치를 같은 규칙으로 적용한다
    public int sourceOf(int d) { return map[d]; }
    public int flipOf(int q) { return flip[q]; }

    public static boolean isIdentity(int[] map, int[] flip) {
        for (int i = 0; i < 4; i++) if (map[i] != i || flip[i] != 0) return false;
        return true;
    }

    // 설정 저장소(prefs)에는 배열이 문자열("[3,1,2,0]")로 저장돼 읽을 때 문자열로 돌아온다 — 배열이든 그 문자열이든 받는다(감지영역 rois와 같은 방식)
    private static JSONArray asArray(Object v) {
        if (v instanceof JSONArray) return (JSONArray) v;
        if (v instanceof String) {
            try {
                return new JSONArray((String) v);
            } catch (org.json.JSONException e) {
                return null;
            }
        }
        return null;
    }

    // JSON → 0~3의 순열(4개, 서로 다른 값)만 인정, 그 외는 그대로(기본)
    public static int[] normalizeMap(Object v) {
        int[] out = {0, 1, 2, 3};
        JSONArray a = asArray(v);
        if (a == null) return out;
        if (a.length() != 4) return out;
        int[] m = new int[4];
        boolean[] seen = new boolean[4];
        for (int i = 0; i < 4; i++) {
            int n = a.optInt(i, -1);
            if (n < 0 || n > 3 || seen[n]) return out;
            seen[n] = true;
            m[i] = n;
        }
        return m;
    }

    // JSON → 4개 각각 0~3, 이상하면 모두 0(뒤집기 없음)
    public static int[] normalizeFlip(Object v) {
        int[] out = new int[4];
        JSONArray a = asArray(v);
        if (a == null) return out;
        if (a.length() != 4) return out;
        int[] f = new int[4];
        for (int i = 0; i < 4; i++) {
            int n = a.optInt(i, -1);
            if (n < 0 || n > 3) return out;
            f[i] = n;
        }
        return f;
    }

    public static JSONArray toJson(int[] a) {
        JSONArray j = new JSONArray();
        for (int n : a) j.put(n);
        return j;
    }

    // 프레임 크기가 칸 단위로 나뉘는지(가로·세로 4의 배수) — 아니면 변환하지 않는다
    static boolean fits(int w, int h) { return w >= 8 && h >= 8 && w % 4 == 0 && h % 4 == 0; }

    // NV21 → NV12(인코더 입력) — 배치·뒤집기를 적용하며 한 번에 변환한다. 변환 불가(크기 안 맞음)면 false
    public boolean toNv12(byte[] src, byte[] dst, int w, int h) { return run(src, dst, w, h, true); }

    // NV21 → NV21(썸네일용) — 같은 배치·뒤집기, 색 순서는 그대로
    public boolean toNv21(byte[] src, byte[] dst, int w, int h) { return run(src, dst, w, h, false); }

    private boolean run(byte[] src, byte[] dst, int w, int h, boolean swapUv) {
        if (!fits(w, h) || src == null || dst == null || src.length < w * h * 3 / 2 || dst.length < w * h * 3 / 2) return false;
        final int hw = w / 2, hh = h / 2, ySize = w * h, ch = hh / 2, pairs = hw / 2;
        for (int d = 0; d < 4; d++) {
            final int q = map[d];
            final boolean hf = (flip[q] & 1) != 0, vf = (flip[q] & 2) != 0;
            final int qx = (q % 2) * hw, qy = (q / 2) * hh, dx = (d % 2) * hw, dy = (d / 2) * hh;
            for (int r = 0; r < hh; r++) {
                int so = (qy + (vf ? hh - 1 - r : r)) * w + qx, dOff = (dy + r) * w + dx;
                if (!hf) System.arraycopy(src, so, dst, dOff, hw);
                else for (int i = 0; i < hw; i++) dst[dOff + i] = src[so + hw - 1 - i];
            }
            for (int r = 0; r < ch; r++) {
                int so = ySize + ((qy / 2) + (vf ? ch - 1 - r : r)) * w + qx, dOff = ySize + ((dy / 2) + r) * w + dx;
                for (int p = 0; p < pairs; p++) {
                    int sp = so + 2 * (hf ? pairs - 1 - p : p), dp = dOff + 2 * p;
                    if (swapUv) {
                        dst[dp] = src[sp + 1];
                        dst[dp + 1] = src[sp];
                    } else {
                        dst[dp] = src[sp];
                        dst[dp + 1] = src[sp + 1];
                    }
                }
            }
        }
        return true;
    }
}
