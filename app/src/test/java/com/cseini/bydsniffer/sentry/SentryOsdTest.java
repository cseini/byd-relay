package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SentryOsdTest {
    private static final int W = 1280, H = 960;

    private static byte[] gray(int w, int h, int y) {
        byte[] f = new byte[w * h * 3 / 2];
        java.util.Arrays.fill(f, 0, w * h, (byte) y);
        java.util.Arrays.fill(f, w * h, f.length, (byte) 90);
        return f;
    }

    private static int count(byte[] f, int w, int y0, int y1, int min) {
        int n = 0;
        for (int y = y0; y < y1; y++) for (int x = 0; x < w; x++) if ((f[y * w + x] & 0xFF) >= min) n++;
        return n;
    }

    @Test
    public void top_dimsBarAndDrawsWhiteOnlyInsideBar() {
        byte[] f = gray(W, H, 200);
        new SentryOsd(true, true, "top", () -> 74).apply(f, W, H, 1_791_190_345_000L);
        int barH = Math.round(H * 0.0375f) & ~1;
        assertTrue("띠 안에 흰 글씨가 있어야 함", count(f, W, 0, barH, 230) > 200);
        assertEquals("띠 밖은 그대로", 200, f[(barH + 5) * W + 100] & 0xFF);
        assertEquals("띠 안 글씨 없는 곳은 어두워짐", (200 * 100) >> 8, f[(barH - 1) * W + (W - 5)] & 0xFF);
        assertEquals("띠 안 색은 중립", (byte) 128, f[W * H + 3]);
    }

    @Test
    public void positions_barMovesAndRestUntouched() {
        byte[] top = gray(W, H, 200), mid = gray(W, H, 200), bot = gray(W, H, 200);
        new SentryOsd(true, false, "top_left", () -> -1).apply(top, W, H, 0L);
        new SentryOsd(true, false, "top_center", () -> -1).apply(mid, W, H, 0L);
        new SentryOsd(true, false, "bottom_right", () -> -1).apply(bot, W, H, 0L);
        int barH = Math.round(H * 0.0375f) & ~1;
        assertTrue(count(top, W, 0, barH, 230) > 0 && count(top, W, H - barH, H, 230) == 0);
        assertTrue(count(mid, W, 0, barH, 230) > 0 && count(mid, W, H - barH, H, 230) == 0);
        assertTrue(count(bot, W, H - barH, H, 230) > 0 && count(bot, W, 0, barH, 230) == 0);
    }

    // 띠 안에서 글자 덩어리만 좌·중·우로 맞춘다(열 범위별 흰 점 수)
    private static int whiteInCols(byte[] f, int w, int y0, int y1, int x0, int x1) {
        int n = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) if ((f[y * w + x] & 0xFF) >= 230) n++;
        return n;
    }

    @Test
    public void horizontalAlignment_leftCenterRight_onlyTextBlockMoves() {
        int barH = Math.round(H * 0.0375f) & ~1;
        byte[] l = gray(W, H, 100), c = gray(W, H, 100), r = gray(W, H, 100);
        new SentryOsd(true, true, "top_left", () -> 74).apply(l, W, H, 0L);
        new SentryOsd(true, true, "top_center", () -> 74).apply(c, W, H, 0L);
        new SentryOsd(true, true, "top_right", () -> 74).apply(r, W, H, 0L);
        int q = W / 4;
        assertTrue(whiteInCols(l, W, 0, barH, 0, q) > 0 && whiteInCols(l, W, 0, barH, W - q, W) == 0);
        assertTrue(whiteInCols(r, W, 0, barH, W - q, W) > 0 && whiteInCols(r, W, 0, barH, 0, q) == 0);
        assertTrue(whiteInCols(c, W, 0, barH, W / 2 - 40, W / 2 + 40) > 0
                && whiteInCols(c, W, 0, barH, 0, W / 8) == 0 && whiteInCols(c, W, 0, barH, W - W / 8, W) == 0);
        // 띠는 가로 전체가 어두워짐(정렬과 무관)
        assertEquals((100 * 100) >> 8, c[(barH - 1) * W + 3] & 0xFF);
        assertEquals((100 * 100) >> 8, c[(barH - 1) * W + (W - 4)] & 0xFF);
    }

    @Test
    public void legacyOrUnknownPosition_behavesLikeTopLeft() {
        byte[] a = gray(W, H, 100), b = gray(W, H, 100);
        new SentryOsd(true, true, "top", () -> 74).apply(a, W, H, 0L);
        new SentryOsd(true, true, "top_left", () -> 74).apply(b, W, H, 0L);
        assertTrue(java.util.Arrays.equals(a, b));
    }

    @Test
    public void bothOff_leavesFrameUntouched() {
        byte[] f = gray(W, H, 77);
        byte[] ref = f.clone();
        new SentryOsd(false, false, "top", () -> 50).apply(f, W, H, 0L);
        assertTrue(java.util.Arrays.equals(ref, f));
    }

    @Test
    public void batteryUnknown_orHidden_omitsBatteryDrawing() {
        byte[] withBat = gray(W, H, 100), noBat = gray(W, H, 100), unknown = gray(W, H, 100);
        new SentryOsd(true, true, "top", () -> 74).apply(withBat, W, H, 0L);
        new SentryOsd(true, false, "top", () -> 74).apply(noBat, W, H, 0L);
        new SentryOsd(true, true, "top", () -> -1).apply(unknown, W, H, 0L);
        int barH = Math.round(H * 0.0375f) & ~1;
        assertTrue(count(withBat, W, 0, barH, 230) > count(noBat, W, 0, barH, 230));
        assertTrue(java.util.Arrays.equals(noBat, unknown));
    }

    @Test
    public void timeText_formatAndCachedPerSecond() {
        SentryOsd o = new SentryOsd(true, false, "top", () -> -1);
        String a = o.timeText(1_791_190_345_000L);
        assertTrue(a.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$"));
        assertTrue(a == o.timeText(1_791_190_345_500L));   // 같은 초는 같은 문자열 객체
        assertTrue(!a.equals(o.timeText(1_791_190_346_000L)));
    }

    @Test
    public void tooSmallOrShortBuffer_isIgnored() {
        byte[] f = new byte[100];
        new SentryOsd(true, true, "top", () -> 10).apply(f, W, H, 0L);
        assertEquals(0, f[0]);
    }
}
