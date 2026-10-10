package com.cseini.motiondetect;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class YoloDetectorTest {
    // 출력 [84][N]: 행 0~3 상자, 행 4+클래스. 사람=클래스0(행 4), 차량=1·2·3·5·7(행 5·6·7·9·11)
    @Test
    public void best_picksMaxPerGroup() {
        int n = 5;
        float[][] o = new float[84][n];
        o[4][2] = 0.61f; o[4][4] = 0.30f;             // 사람 최고 0.61
        o[6][1] = 0.55f; o[11][3] = 0.72f;            // 자동차 0.55, 트럭 0.72 → 차량 0.72
        o[8][0] = 0.99f;                              // 클래스 4(비행기)는 어느 쪽에도 안 센다
        float[] r = YoloDetector.best(o, 84, n);
        assertEquals(0.61f, r[0], 1e-6f);
        assertEquals(0.72f, r[1], 1e-6f);
    }

    @Test
    public void best_tooFewRows_returnsZero() {
        float[] r = YoloDetector.best(new float[6][3], 6, 3);
        assertEquals(0f, r[0], 0f);
        assertEquals(0f, r[1], 0f);
    }
}
