package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SentryNearMissTest {
    private static final float MIN = 0.045f;   // 임계 4.5% → near 2.25%

    // pct 열을 131ms 간격으로 먹이고, 끝난 구간 하나를 돌려준다(끝내려고 near 미만 프레임을 덧붙임)
    private static SentryNearMiss.Run run(SentryNearMiss nm, float[] pct, float[] cx, float[] cy, int[] y) {
        SentryNearMiss.Run out = null;
        long t = 1000;
        for (int i = 0; i < pct.length; i++, t += 131) {
            SentryNearMiss.Run r = nm.feed(t, pct[i], cx == null ? -1f : cx[i], cy == null ? -1f : cy[i], 0.3f, 0.2f, y == null ? 100 : y[i], MIN);
            if (r != null) out = r;
        }
        for (int k = 0; k < SentryNearMiss.END_GAP; k++, t += 131) {
            SentryNearMiss.Run r = nm.feed(t, 0f, -1f, -1f, -1f, -1f, 100, MIN);
            if (r != null) out = r;
        }
        return out;
    }

    @Test
    public void staticFlickerLikeRunReportsZeroTravelAndBrightnessSwing() {
        SentryNearMiss nm = new SentryNearMiss();
        float[] pct = {0.03f, 0.04f, 0.035f, 0.03f, 0.04f};
        float[] cx = {0.5f, 0.5f, 0.51f, 0.5f, 0.5f}, cy = {0.4f, 0.4f, 0.4f, 0.41f, 0.4f};
        int[] y = {100, 108, 98, 109, 100};
        SentryNearMiss.Run r = run(nm, pct, cx, cy, y);
        assertNotNull(r);
        assertEquals(5, r.frames);
        assertEquals(0.04f, r.peak, 1e-6);
        assertTrue("제자리라 이동이 작다 " + r.travel, r.travel < 0.03f);
        assertEquals(98, r.meanYMin);
        assertEquals(109, r.meanYMax);
        String d = r.describe("영상 좌상칸");
        assertTrue(d, d.contains("근접 미달 영상 좌상칸") && d.contains("최고 4.0%(임계 4.5%)") && d.contains("5장"));
    }

    @Test
    public void movingRunReportsLargeTravel() {
        SentryNearMiss nm = new SentryNearMiss();
        float[] pct = {0.03f, 0.035f, 0.04f, 0.04f};
        float[] cx = {0.1f, 0.3f, 0.5f, 0.7f}, cy = {0.5f, 0.5f, 0.5f, 0.5f};
        SentryNearMiss.Run r = run(nm, pct, cx, cy, null);
        assertNotNull(r);
        assertEquals(0.6f, r.travel, 1e-5);
        assertEquals(0.6f, r.path, 1e-5);
    }

    @Test
    public void runThatReachedThresholdIsNotReportedBecauseItBecameAnEvent() {
        assertNull(run(new SentryNearMiss(), new float[]{0.03f, 0.05f, 0.04f, 0.03f}, null, null, null));
    }

    @Test
    public void tooShortRunIsIgnored() {
        assertNull(run(new SentryNearMiss(), new float[]{0.03f, 0.04f}, null, null, null));
    }

    @Test
    public void belowNearNeverStartsARun() {
        assertNull(run(new SentryNearMiss(), new float[]{0.01f, 0.02f, 0.01f, 0.02f}, null, null, null));
    }

    @Test
    public void shortGapsInsideARunAreBridged() {
        SentryNearMiss nm = new SentryNearMiss();
        SentryNearMiss.Run out = null;
        float[] pct = {0.03f, 0.03f, 0f, 0.03f, 0f, 0f, 0.03f};   // 끊김은 최대 2장까지 이어 본다
        long t = 0;
        for (float p : pct) { SentryNearMiss.Run r = nm.feed(t += 131, p, 0.5f, 0.5f, 0.2f, 0.2f, 100, MIN); if (r != null) out = r; }
        assertNull(out);
        for (int k = 0; k < SentryNearMiss.END_GAP; k++) { SentryNearMiss.Run r = nm.feed(t += 131, 0f, -1f, -1f, -1f, -1f, 100, MIN); if (r != null) out = r; }
        assertNotNull(out);
        assertEquals(4, out.frames);   // near 이상인 프레임만 센다(끊긴 칸 제외)
    }

    @Test
    public void hourlyBudgetLimitsLogLines() {
        SentryNearMiss nm = new SentryNearMiss();
        int reported = 0;
        long t = 0;
        for (int k = 0; k < SentryNearMiss.HOURLY_BUDGET + 10; k++) {
            for (int i = 0; i < 4; i++) nm.feed(t += 131, 0.03f, 0.5f, 0.5f, 0.2f, 0.2f, 100, MIN);
            for (int i = 0; i < SentryNearMiss.END_GAP; i++) { if (nm.feed(t += 131, 0f, -1f, -1f, -1f, -1f, 100, MIN) != null) reported++; }
        }
        assertEquals(SentryNearMiss.HOURLY_BUDGET, reported);
    }
}
