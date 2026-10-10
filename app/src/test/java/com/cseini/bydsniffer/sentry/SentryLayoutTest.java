package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.junit.Test;

public class SentryLayoutTest {
    private static final int W = 16, H = 8;   // 칸 8x4, 색 칸은 4x2쌍

    // 칸마다 값이 다른 NV21: 휘도 = 10×(q+1) + 칸 안 열(0~7)로 가로 방향을 구분, 색(V,U)=(q*2+100, q*2+101)
    private static byte[] frame() {
        byte[] f = new byte[W * H * 3 / 2];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int q = (y / (H / 2)) * 2 + (x / (W / 2));
            f[y * W + x] = (byte) (10 * (q + 1) + (x % (W / 2)));
        }
        int ySize = W * H;
        for (int r = 0; r < H / 2; r++) for (int p = 0; p < W / 2; p++) {
            int q = (r / (H / 4)) * 2 + (p / (W / 4));
            f[ySize + r * W + 2 * p] = (byte) (q * 2 + 100);
            f[ySize + r * W + 2 * p + 1] = (byte) (q * 2 + 101);
        }
        return f;
    }

    @Test
    public void identity_isDetected_andConvertsLikeNv21ToNv12() {
        SentryLayout lo = new SentryLayout(new int[]{0, 1, 2, 3}, new int[]{0, 0, 0, 0});
        assertTrue(lo.isIdentity());
        byte[] src = frame(), dst = new byte[src.length], ref = new byte[src.length];
        assertTrue(lo.toNv12(src, dst, W, H));
        System.arraycopy(src, 0, ref, 0, W * H);
        for (int i = W * H; i + 1 < src.length; i += 2) { ref[i] = src[i + 1]; ref[i + 1] = src[i]; }
        assertArrayEquals(ref, dst);
    }

    @Test
    public void swap_movesQuadrants_toNv21KeepsColorOrder() {
        // 좌상(d0)에 원본 우하(q3), 우하(d3)에 원본 좌상(q0)
        SentryLayout lo = new SentryLayout(new int[]{3, 1, 2, 0}, new int[]{0, 0, 0, 0});
        assertFalse(lo.isIdentity());
        byte[] src = frame(), dst = new byte[src.length];
        assertTrue(lo.toNv21(src, dst, W, H));
        assertEquals(10 * 4 + 0, dst[0] & 0xFF);               // d0 첫 칸 첫 열 = q3
        assertEquals(10 * 1 + 7, dst[(H / 2) * W + W / 2 + 7] & 0xFF);   // d3 = q0
        assertEquals(10 * 2 + 0, dst[W / 2] & 0xFF);            // d1 = q1 그대로
        assertEquals(3 * 2 + 100, dst[W * H] & 0xFF);           // d0 색 V = q3의 V, 순서 유지(NV21)
        assertEquals(3 * 2 + 101, dst[W * H + 1] & 0xFF);
    }

    @Test
    public void swap_toNv12SwapsVuToUv() {
        SentryLayout lo = new SentryLayout(new int[]{3, 1, 2, 0}, new int[]{0, 0, 0, 0});
        byte[] src = frame(), dst = new byte[src.length];
        assertTrue(lo.toNv12(src, dst, W, H));
        assertEquals(3 * 2 + 101, dst[W * H] & 0xFF);           // NV12는 U 먼저
        assertEquals(3 * 2 + 100, dst[W * H + 1] & 0xFF);
    }

    @Test
    public void flips_followSourceCamera_notDestination() {
        // 원본 칸 0(좌상)만 가로 뒤집기, 배치는 그대로: 좌상 칸 안에서 열 순서가 거꾸로
        SentryLayout lo = new SentryLayout(new int[]{0, 1, 2, 3}, new int[]{1, 0, 0, 0});
        byte[] src = frame(), dst = new byte[src.length];
        assertTrue(lo.toNv21(src, dst, W, H));
        for (int x = 0; x < W / 2; x++) assertEquals(10 + (W / 2 - 1 - x), dst[x] & 0xFF);
        assertEquals(10 * 2 + 0, dst[W / 2] & 0xFF);             // 우상은 안 뒤집힘
        // 색도 같은 방향으로: 좌상 칸 색 쌍 4개 모두 q0 값(쌍이 깨지지 않음)
        for (int p = 0; p < W / 4; p++) {
            assertEquals(100, dst[W * H + 2 * p] & 0xFF);
            assertEquals(101, dst[W * H + 2 * p + 1] & 0xFF);
        }
    }

    @Test
    public void verticalFlip_reversesRows_andMovesWithCameraWhenSwapped() {
        // 원본 칸 3 세로 뒤집기 + 칸 3을 d0으로 이동: d0 칸의 첫 줄이 q3의 마지막 줄이 됨(행마다 같은 값이라 열로 구분)
        byte[] src = frame();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (y >= H / 2 && x >= W / 2) src[y * W + x] = (byte) (200 + (y - H / 2));   // q3: 줄 번호
        SentryLayout lo = new SentryLayout(new int[]{3, 1, 2, 0}, new int[]{0, 0, 0, 2});
        byte[] dst = new byte[src.length];
        assertTrue(lo.toNv21(src, dst, W, H));
        assertEquals(200 + (H / 2 - 1), dst[0] & 0xFF);          // d0 첫 줄 = q3 마지막 줄
        assertEquals(200, dst[(H / 2 - 1) * W] & 0xFF);          // d0 마지막 줄 = q3 첫 줄
    }

    @Test
    public void unfitSize_orBadBuffers_returnFalse() {
        SentryLayout lo = new SentryLayout(new int[]{3, 1, 2, 0}, new int[]{0, 0, 0, 0});
        assertFalse(lo.toNv12(new byte[18 * 6 * 3 / 2], new byte[18 * 6 * 3 / 2], 18, 6));   // 4의 배수 아님
        assertFalse(lo.toNv12(new byte[10], new byte[W * H * 3 / 2], W, H));                 // 입력이 짧음
    }

    @Test
    public void normalize_acceptsOnlyPermutationsAndRangeFlags() throws Exception {
        assertArrayEquals(new int[]{3, 1, 2, 0}, SentryLayout.normalizeMap(new JSONArray("[3,1,2,0]")));
        assertArrayEquals(new int[]{0, 1, 2, 3}, SentryLayout.normalizeMap(new JSONArray("[3,3,2,0]")));   // 중복
        assertArrayEquals(new int[]{0, 1, 2, 3}, SentryLayout.normalizeMap(new JSONArray("[0,1,2]")));     // 길이
        assertArrayEquals(new int[]{0, 1, 2, 3}, SentryLayout.normalizeMap(new JSONArray("[0,1,2,4]")));   // 범위
        assertArrayEquals(new int[]{3, 1, 2, 0}, SentryLayout.normalizeMap("[3,1,2,0]"));                   // prefs에서 읽힌 문자열 — 배열과 똑같이
        assertArrayEquals(new int[]{0, 1, 2, 3}, SentryLayout.normalizeMap("[3,3,2,0]"));
        assertArrayEquals(new int[]{0, 1, 2, 3}, SentryLayout.normalizeMap("배열 아님"));
        assertArrayEquals(new int[]{0, 1, 2, 3}, SentryLayout.normalizeMap(7));
        assertArrayEquals(new int[]{1, 2, 3, 0}, SentryLayout.normalizeFlip("[1,2,3,0]"));
        assertArrayEquals(new int[]{0, 0, 0, 0}, SentryLayout.normalizeFlip("[1,2,3]"));
        assertArrayEquals(new int[]{1, 2, 3, 0}, SentryLayout.normalizeFlip(new JSONArray("[1,2,3,0]")));
        assertArrayEquals(new int[]{0, 0, 0, 0}, SentryLayout.normalizeFlip(new JSONArray("[1,2,4,0]")));
        assertArrayEquals(new int[]{0, 0, 0, 0}, SentryLayout.normalizeFlip(null));
    }
}
