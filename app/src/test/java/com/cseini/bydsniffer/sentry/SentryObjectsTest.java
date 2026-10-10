package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SentryObjectsTest {
    // {종류(1차량 2사람), 점수, x0, y0, x1, y1}
    private static float[] car(float s, float x0, float y0, float x1, float y1) { return new float[]{1, s, x0, y0, x1, y1}; }
    private static float[] person(float s, float x0, float y0, float x1, float y1) { return new float[]{2, s, x0, y0, x1, y1}; }

    @Test
    public void parkedCarPresentInBaselineIsIgnored_movingCarIsKept() {
        float[][] base = {car(0.6f, 0.48f, 0.37f, 0.60f, 0.47f)};                       // 주차된 차(10-09 영상: 점수 0.5~0.65)
        float[][] cur = {car(0.62f, 0.48f, 0.37f, 0.60f, 0.47f), car(0.73f, 0.55f, 0.25f, 0.95f, 0.55f)};   // 같은 자리 + 새로 들어온 차
        List<SentryObjects.Found> n = SentryObjects.newOnly(cur, base, 0);
        assertEquals(1, n.size());
        assertEquals(1, n.get(0).kind);
        assertEquals(73, n.get(0).pct());
    }

    @Test
    public void sameSpotButDifferentKindIsNew() {
        float[][] base = {car(0.6f, 0.4f, 0.4f, 0.6f, 0.6f)};
        float[][] cur = {person(0.8f, 0.4f, 0.4f, 0.6f, 0.6f)};
        assertEquals(1, SentryObjects.newOnly(cur, base, 2).size());
    }

    @Test
    public void noBaselineMeansNoJudgement() {
        assertTrue(SentryObjects.newOnly(new float[][]{car(0.9f, 0f, 0f, 1f, 1f)}, null, 0).isEmpty());
        assertTrue(SentryObjects.newOnly(null, new float[0][], 0).isEmpty());
    }

    @Test
    public void personWinsOverHigherScoringVehicle_andThresholdsApply() {
        List<SentryObjects.Found> l = new ArrayList<>();
        l.add(new SentryObjects.Found(1, 0.80f, 0, 0, 0, 1, 1));
        l.add(new SentryObjects.Found(2, 0.45f, 1, 0, 0, 1, 1));
        SentryObjects.Found f = SentryObjects.pick(l);
        assertNotNull(f);
        assertEquals(2, f.kind);
        assertEquals("사람 45%", f.label());
        assertEquals(1, f.cell);
    }

    @Test
    public void belowThresholdIsNull() {
        List<SentryObjects.Found> l = new ArrayList<>();
        l.add(new SentryObjects.Found(2, 0.35f, 0, 0, 0, 1, 1));
        l.add(new SentryObjects.Found(1, 0.40f, 0, 0, 0, 1, 1));
        assertNull(SentryObjects.pick(l));
    }

    @Test
    public void cellMeanYSamplesOnlyTheCellAndLightStepNeedsSixLevels() {
        int w = 64, h = 32;
        byte[] f = new byte[w * h * 3 / 2];
        java.util.Arrays.fill(f, (byte) 100);
        for (int y = 0; y < h; y++) for (int x = 32; x < 64; x++) f[y * w + x] = (byte) 140;   // 오른쪽 칸만 밝음
        assertEquals(100f, SentryObjects.cellMeanY(f, w, 0, 0, 32, 32), 0.01f);
        assertEquals(140f, SentryObjects.cellMeanY(f, w, 32, 0, 32, 32), 0.01f);
        assertTrue(SentryObjects.lightStep(107f, 98f));        // 10-09 02:31 영상: 98→107
        assertTrue(SentryObjects.lightStep(98f, 107f));
        assertTrue(SentryObjects.lightStep(105.7f, 100f));     // 번쩍임 영상의 표본 평균 차 5.7
        assertFalse(SentryObjects.lightStep(101f, 100f));      // 작은 변화는 조명 아님
        assertFalse(SentryObjects.lightStep(104.4f, 100f));
        assertTrue(SentryObjects.lightStep(104.6f, 100f));
    }

    @Test
    public void iouIdenticalAndDisjoint() {
        assertEquals(1f, SentryObjects.iou(car(1, 0, 0, 1, 1), car(1, 0, 0, 1, 1)), 1e-6);
        assertEquals(0f, SentryObjects.iou(car(1, 0, 0, 0.4f, 0.4f), car(1, 0.6f, 0.6f, 1, 1)), 1e-6);
    }

    @Test
    public void describe_explainsWhyEachCandidateWasDropped() {
        float[][] cur = {
                {1, 0.52f, 0.5f, 0.4f, 0.8f, 0.7f},    // 차량, 기준에 같은 자리 → 겹침 제외
                {2, 0.73f, 0.1f, 0.1f, 0.2f, 0.4f},    // 사람, 기준에 없음 → 새것
                {2, 0.35f, 0.6f, 0.1f, 0.7f, 0.4f},    // 사람, 점수 미달
        };
        float[][] base = {{1, 0.6f, 0.5f, 0.4f, 0.8f, 0.7f}};
        String d = SentryObjects.describe(cur, base, 4);
        assertTrue(d, d.contains("차량52%[겹침1.00→제외]"));
        assertTrue(d, d.contains("사람73%[새것]"));
        assertTrue(d, d.contains("사람35%[점수미달]"));
        assertEquals("후보 없음", SentryObjects.describe(new float[0][], base, 4));
        assertEquals("후보 없음", SentryObjects.describe(null, base, 4));
        assertEquals(2, SentryObjects.describe(cur, base, 2).split("\\] ").length);
    }

    @Test
    public void cameraCode_followsLayoutMapAndDolphinCameraOrder() {
        int[] operator = {3, 0, 1, 2};   // 운영자 차 배치: 좌상=전방(a4)·우상=후방(a1)·좌하=좌측(a2)·우하=우측(a3)
        assertEquals('F', SentryObjects.cameraCode(operator, 0));
        assertEquals('B', SentryObjects.cameraCode(operator, 1));
        assertEquals('L', SentryObjects.cameraCode(operator, 2));
        assertEquals('R', SentryObjects.cameraCode(operator, 3));
        assertEquals('B', SentryObjects.cameraCode(new int[]{0, 1, 2, 3}, 0));   // 기본 배치는 원본 그대로
        assertEquals('B', SentryObjects.cameraCode(null, 0));
        assertEquals('?', SentryObjects.cameraCode(operator, 4));
        assertEquals('?', SentryObjects.cameraCode(operator, -1));
    }

    @Test
    public void objectEvent_fitsWorkerNameLimitAndNamesKindAndCamera() {
        String e = SentryObjects.objectEvent("2026-10-09_09h08m02s", 2, new int[]{3, 0, 1, 2}, 3);
        assertEquals("sobj:2026-10-09_09h08m02s:PR", e);
        assertTrue(e.length() <= 32);
        assertEquals("sobj:2026-10-09_09h08m02s:VF", SentryObjects.objectEvent("2026-10-09_09h08m02s", 1, new int[]{3, 0, 1, 2}, 0));
    }
}
