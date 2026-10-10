package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class SentryMarksTest {

    @Test
    public void mergesHitsWithinThreeSecondsOfTheSameCell() {
        SentryMarks m = new SentryMarks();
        m.reset(10_000L, 6_000L);   // 영상 0초 = 사전 6초 전
        m.add(10_000L, 0, 4, 0);    // 영상 6.0초
        m.add(11_000L, 0, 9, 0);    // 이어짐 → 합침(최고 9%)
        m.add(12_500L, 0, 5, 0);    // 이어짐(마지막 감지 1.5초 뒤)
        List<SentryIndex.Mark> l = m.snapshot();
        assertEquals(1, l.size());
        assertEquals(6000L, l.get(0).tMs);
        assertEquals(9, l.get(0).pct);
        assertEquals(2500L, l.get(0).durMs);
    }

    @Test
    public void separateCellsAndGapsMakeSeparateMarks() {
        SentryMarks m = new SentryMarks();
        m.reset(0L, 0L);
        m.add(1000L, 0, 5, 0);
        m.add(1200L, 3, 7, 0);       // 다른 칸
        m.add(9000L, 0, 6, 0);       // 같은 칸이지만 3초 넘게 비어 있다가 다시
        List<SentryIndex.Mark> l = m.snapshot();
        assertEquals(3, l.size());
        assertEquals(1000L, l.get(0).tMs);
        assertEquals(1200L, l.get(1).tMs);
        assertEquals(9000L, l.get(2).tMs);
        assertEquals(3, l.get(1).cell);
    }

    @Test
    public void hitsBeforeEventStartAreMapped_andNeverNegative() {
        SentryMarks m = new SentryMarks();
        m.reset(10_000L, 6_000L);
        m.add(5_000L, 1, 5, 0);      // 사전 녹화 구간 안: 10000-5000=5초 전 → 영상 1초
        m.add(1_000L, 1, 5, 0);      // 사전 구간보다 더 앞(6초 넘게 전) → 0으로
        List<SentryIndex.Mark> l = m.snapshot();
        assertEquals(2, l.size());
        assertEquals(0L, l.get(0).tMs);      // 사전 구간보다 앞선 것은 0초로
        assertEquals(1000L, l.get(1).tMs);   // 사전 6초 구간 안(5초 전)은 영상 1초
    }

    @Test
    public void userRoiHitsUseCellMinusOne_andCapIsEnforced() {
        SentryMarks m = new SentryMarks();
        m.reset(0L, 0L);
        for (int i = 0; i < 300; i++) m.add(i * 10_000L, -1, 5, 0);   // 서로 멀리 떨어진 300개
        List<SentryIndex.Mark> l = m.snapshot();
        assertEquals(SentryMarks.MAX_MARKS, l.size());
        assertEquals(-1, l.get(0).cell);
    }

    @Test
    public void objectKindIsAppliedToExistingAndFutureMarksOfThatCell() {
        SentryMarks m = new SentryMarks();
        m.reset(0L, 0L);
        m.add(1000L, 0, 5, 0);
        m.add(1200L, 3, 5, 0);
        m.setCellKind(0, 2);                 // 칸0에서 사람 판정
        m.add(9000L, 0, 6, 0);               // 이후 같은 칸 표시도 사람
        m.setCellKind(0, 1);                 // 사람이 이미 정해진 칸은 차량으로 덮지 않는다
        java.util.List<SentryIndex.Mark> l = m.snapshot();
        assertEquals(3, l.size());
        assertEquals(2, l.get(0).kind);
        assertEquals(0, l.get(1).kind);      // 다른 칸은 그대로 움직임
        assertEquals(2, l.get(2).kind);
    }

    @Test
    public void lightSuspectKindDoesNotOverrideObjectKinds() {
        SentryMarks m = new SentryMarks();
        m.reset(0L, 0L);
        m.add(1000L, 0, 5, 0);
        m.add(1200L, 1, 5, 0);
        m.setCellKind(0, 2);   // 칸0 사람
        m.setCellKind(0, 3);   // 조명 의심이 사람을 덮지 않는다
        m.setCellKind(1, 3);   // 칸1은 조명 의심
        java.util.List<SentryIndex.Mark> l = m.snapshot();
        assertEquals(2, l.get(0).kind);
        assertEquals(3, l.get(1).kind);
        m.setCellKind(1, 1);   // 이후 차량이 판정되면 조명 의심은 바뀐다
        assertEquals(1, m.snapshot().get(1).kind);
    }

    @Test
    public void resetClearsPreviousEvent() {
        SentryMarks m = new SentryMarks();
        m.reset(0L, 0L);
        m.add(1000L, 0, 5, 0);
        m.reset(0L, 0L);
        assertTrue(m.snapshot().isEmpty());
    }
}
