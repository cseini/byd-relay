package com.cseini.motiondetect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlateTrackerTest {

    private static final int W = 320, H = 240;
    private static final int VX = 60, VY = 40, VW = 200, VH = 150;   // 차량 bbox(픽셀)

    private static byte[] solidY(int w, int h, int val) {
        byte[] y = new byte[w * h];
        java.util.Arrays.fill(y, (byte) val);
        return y;
    }

    // 세로 줄무늬(문자 획 흉내) — [x0,x0+w)×[y0,y0+h) 범위에 stripeW 간격으로 lo/hi 번갈아 채움(가로 에지 생성).
    private static void drawStripes(byte[] y, int frameW, int x0, int y0, int w, int h, int stripeW, int lo, int hi) {
        for (int yy = y0; yy < y0 + h; yy++) {
            for (int xx = x0; xx < x0 + w; xx++) {
                boolean on = ((xx - x0) / stripeW) % 2 == 0;
                y[yy * frameW + xx] = (byte) (on ? hi : lo);
            }
        }
    }

    @Test
    public void singleLinePlate_whiteBgDarkChar_detected() {
        byte[] y = solidY(W, H, 130);
        int bx = 130, by = 145, bw = 50, bh = 12;   // 검색영역(x 90~230, y 123~191) 내부
        drawStripes(y, W, bx, by, bw, bh, 5, 40, 220);   // 밝은 바탕에 어두운 획

        PlateTracker.Plate p = PlateTracker.find(y, W, H, VX, VY, VW, VH);

        assertNotNull(p);
        assertEquals(bx + bw / 2f, p.cx * W, 15f);
        assertEquals(by + bh / 2f, p.cy * H, 8f);
        assertTrue("wNorm=" + p.wNorm, p.wNorm * W > 20 && p.wNorm * W < 90);
    }

    @Test
    public void singleLinePlate_darkBgLightChar_detectedRegardlessOfPolarity() {
        byte[] y = solidY(W, H, 130);
        int bx = 130, by = 145, bw = 50, bh = 12;
        drawStripes(y, W, bx, by, bw, bh, 5, 220, 40);   // 어두운 바탕에 밝은 획(반대 극성)

        PlateTracker.Plate p = PlateTracker.find(y, W, H, VX, VY, VW, VH);

        assertNotNull(p);
        assertEquals(bx + bw / 2f, p.cx * W, 15f);
        assertTrue("wNorm=" + p.wNorm, p.wNorm * W > 20 && p.wNorm * W < 90);
    }

    @Test
    public void twoLinePlate_oldStyle_mergedIntoOnePlate() {
        byte[] y = solidY(W, H, 130);
        int bx = 130, bw = 50;
        int by1 = 140, bh1 = 8;    // 윗줄(지역명)
        int gap = 4;
        int by2 = by1 + bh1 + gap, bh2 = 8;   // 아랫줄(숫자)
        drawStripes(y, W, bx, by1, bw, bh1, 5, 40, 220);
        drawStripes(y, W, bx, by2, bw, bh2, 5, 40, 220);

        PlateTracker.Plate p = PlateTracker.find(y, W, H, VX, VY, VW, VH);

        assertNotNull(p);
        // 병합된 사각형 중심은 두 줄 전체의 세로 중앙 근처
        float expectCy = by1 + (bh1 + gap + bh2) / 2f;
        assertEquals(expectCy, p.cy * H, 10f);
        assertTrue("wNorm=" + p.wNorm, p.wNorm * W > 20 && p.wNorm * W < 90);
    }

    @Test
    public void flatRegion_noPlate_returnsNull() {
        byte[] y = solidY(W, H, 130);   // 균일 배경 — 에지 없음

        PlateTracker.Plate p = PlateTracker.find(y, W, H, VX, VY, VW, VH);

        assertNull(p);
    }

    @Test
    public void randomNoise_returnsNull() {
        byte[] y = new byte[W * H];
        java.util.Random rnd = new java.util.Random(42);
        rnd.nextBytes(y);   // 전면 랜덤 — 검색영역 전체가 '핫'해져 유효 띠 높이 범위를 벗어남

        PlateTracker.Plate p = PlateTracker.find(y, W, H, VX, VY, VW, VH);

        assertNull(p);
    }

    @Test
    public void tooSmallVehicleBox_returnsNull() {
        byte[] y = solidY(W, H, 130);
        drawStripes(y, W, 40, 35, 10, 3, 2, 40, 220);

        PlateTracker.Plate p = PlateTracker.find(y, W, H, 30, 30, 20, 10);   // 검색영역이 너무 작음(8px 미만)

        assertNull(p);
    }
}
