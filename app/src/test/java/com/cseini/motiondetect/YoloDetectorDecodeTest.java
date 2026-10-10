package com.cseini.motiondetect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YoloDetectorDecodeTest {
    private static final int N = 6;

    private static float[][] blank() { return new float[84][N]; }

    // 앵커 a에 박스(cx,cy,w,h)와 클래스 점수를 넣는다
    private static void put(float[][] o, int a, float cx, float cy, float w, float h, int cls, float score) {
        o[0][a] = cx; o[1][a] = cy; o[2][a] = w; o[3][a] = h; o[4 + cls][a] = score;
    }

    @Test
    public void decodesNormalizedCenterBoxes_andPicksKind() {
        float[][] o = blank();
        put(o, 0, 0.5f, 0.5f, 0.2f, 0.4f, 0, 0.87f);   // 사람
        put(o, 1, 0.3f, 0.3f, 0.2f, 0.2f, 2, 0.60f);   // 자동차(클래스 2)
        put(o, 2, 0.9f, 0.9f, 0.1f, 0.1f, 15, 0.99f);  // 고양이 — 무시
        float[][] d = YoloDetector.decode(o, 84, N, 0.30f, 416);
        assertEquals(2, d.length);
        assertEquals(2f, d[0][0], 0f);                  // 사람이 점수 높아 먼저
        assertEquals(0.87f, d[0][1], 1e-6);
        assertEquals(0.4f, d[0][2], 1e-5);              // x0 = 0.5 - 0.2/2 = 0.4
        assertEquals(0.3f, d[0][3], 1e-5);              // y0 = 0.5 - 0.4/2
        assertEquals(0.6f, d[0][4], 1e-5);
        assertEquals(0.7f, d[0][5], 1e-5);
        assertEquals(1f, d[1][0], 0f);                  // 차량
    }

    @Test
    public void mergesOverlappingSameKind_nms() {
        float[][] o = blank();
        put(o, 0, 0.5f, 0.5f, 0.4f, 0.4f, 2, 0.90f);
        put(o, 1, 0.51f, 0.5f, 0.4f, 0.4f, 2, 0.80f);   // 거의 같은 자리 → 제거
        put(o, 2, 0.9f, 0.9f, 0.1f, 0.1f, 2, 0.70f);    // 멀리 → 유지
        float[][] d = YoloDetector.decode(o, 84, N, 0.30f, 416);
        assertEquals(2, d.length);
        assertEquals(0.90f, d[0][1], 1e-6);
        assertEquals(0.70f, d[1][1], 1e-6);
    }

    @Test
    public void belowMinScoreAndPixelScaleModels() {
        float[][] o = blank();
        put(o, 0, 0.5f, 0.5f, 0.2f, 0.2f, 0, 0.20f);    // 기준 미만
        assertEquals(0, YoloDetector.decode(o, 84, N, 0.30f, 416).length);
        float[][] px = blank();
        put(px, 0, 208f, 208f, 104f, 104f, 0, 0.80f);   // 픽셀 단위로 나오는 모델(입력 416)
        float[][] d = YoloDetector.decode(px, 84, N, 0.30f, 416);
        assertEquals(1, d.length);
        assertEquals(0.375f, d[0][2], 1e-4);   // (208-52)/416
        assertEquals(0.625f, d[0][4], 1e-4);   // (208+52)/416
    }

    @Test
    public void tooFewRowsReturnsEmpty() {
        assertTrue(YoloDetector.decode(new float[8][N], 8, N, 0.3f, 416).length == 0);
    }
}
