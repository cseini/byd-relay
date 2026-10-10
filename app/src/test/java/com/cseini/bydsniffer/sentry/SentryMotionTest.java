package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class SentryMotionTest {

    @Test
    public void meanLuma_uniformFrames() {
        int w = 64, h = 64;
        byte[] dark = new byte[w * h * 3 / 2];
        byte[] bright = new byte[w * h * 3 / 2];
        Arrays.fill(dark, (byte) 40);
        Arrays.fill(bright, (byte) 200);
        assertEquals(40, SentryMotion.meanLuma(dark, w, h));
        assertEquals(200, SentryMotion.meanLuma(bright, w, h));
        assertTrue(Math.abs(SentryMotion.meanLuma(bright, w, h) - SentryMotion.meanLuma(dark, w, h)) >= 12);
    }

    @Test
    public void meanLuma_samplesEvery64thByteOfYPlaneOnly() {
        int w = 64, h = 2;
        byte[] f = new byte[w * h * 3 / 2];
        f[0] = (byte) 100;
        f[64] = (byte) 200;
        f[1] = (byte) 255;
        Arrays.fill(f, w * h, f.length, (byte) 255);
        assertEquals(150, SentryMotion.meanLuma(f, w, h));
    }

    @Test
    public void logOrder_followsLayoutMapSoLogsMatchTheVideo() {
        // 운영자 차 layout_map [3,0,1,2]: 영상 좌상=원본 4번째 칸, 우상=1번째, 좌하=2번째, 우하=3번째
        assertTrue(Arrays.equals(new int[]{3, 0, 1, 2}, SentryMotion.logOrderFor(true, new int[]{3, 0, 1, 2}, 4)));
        assertTrue(Arrays.equals(new int[]{0, 1, 2, 3}, SentryMotion.logOrderFor(true, new int[]{0, 1, 2, 3}, 4)));
    }

    @Test
    public void logOrder_ignoresLayoutForUserRoisOrBadMap() {
        assertTrue(Arrays.equals(new int[]{0, 1, 2, 3}, SentryMotion.logOrderFor(false, new int[]{3, 0, 1, 2}, 4)));
        assertTrue(Arrays.equals(new int[]{0, 1, 2, 3}, SentryMotion.logOrderFor(true, new int[]{3, 3, 1, 2}, 4)));
        assertTrue(Arrays.equals(new int[]{0, 1, 2, 3}, SentryMotion.logOrderFor(true, null, 4)));
        assertTrue(Arrays.equals(new int[]{0, 1}, SentryMotion.logOrderFor(true, new int[]{3, 0, 1, 2}, 2)));
    }

    @Test
    public void meanLuma_shortFrameAndEmpty() {
        assertEquals(0, SentryMotion.meanLuma(new byte[0], 64, 64));
        byte[] f = new byte[64];
        Arrays.fill(f, (byte) 128);
        assertEquals(128, SentryMotion.meanLuma(f, 64, 64));
    }
}
