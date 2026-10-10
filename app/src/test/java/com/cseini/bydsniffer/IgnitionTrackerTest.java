package com.cseini.bydsniffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IgnitionTrackerTest {

    private static final long T0 = 1_000_000_000L;  // 임의의 기준 시각(ms)

    // lastSeenTs는 기본적으로 lastIgnTs와 같은 시각으로 맞춘다(엣지 직후 바로 관측한 통상 케이스).
    // stale 판정이 lastSeenTs만 따로 보는 테스트는 onIgnSeen()으로 둘을 분리해서 구성한다.
    private static IgnitionTracker.Persisted onIgn(long lastIgnTs) {
        return onIgnSeen(lastIgnTs, lastIgnTs);
    }

    private static IgnitionTracker.Persisted onIgnSeen(long lastIgnTs, long lastSeenTs) {
        IgnitionTracker.Persisted p = new IgnitionTracker.Persisted();
        p.lastIgn = 1;
        p.lastIgnTs = lastIgnTs;
        p.sawPwr2 = true;
        p.boarded = true;
        p.lastSeenTs = lastSeenTs;
        return p;
    }

    // ── onAcPowerEvent ──────────────────────────────────────────────

    @Test
    public void acEvent_1to0_pwr2_keepsIgnitionOn() {
        IgnitionTracker t = new IgnitionTracker(onIgn(T0), T0);
        t.onAcPowerEvent(1, 2, T0);  // 이전값 확립(NONE)

        IgnitionTracker.Edge e = t.onAcPowerEvent(0, 2, T0 + 1000);

        assertEquals(IgnitionTracker.Edge.NONE, e);
        assertEquals(1, t.ign());
    }

    @Test
    public void acEvent_1to0_pwr0_isOff() {
        IgnitionTracker t = new IgnitionTracker(onIgn(T0), T0);
        t.onAcPowerEvent(1, 2, T0);

        IgnitionTracker.Edge e = t.onAcPowerEvent(0, 0, T0 + 1000);

        assertEquals(IgnitionTracker.Edge.OFF, e);
        assertEquals(0, t.ign());
    }

    @Test
    public void acEvent_1to0_pwrNA_isNONE_thenPollZeroTwice_isOff() {
        IgnitionTracker t = new IgnitionTracker(onIgn(T0), T0);
        t.onAcPowerEvent(1, 2, T0);  // 이전값 확립(NONE)

        IgnitionTracker.Edge e = t.onAcPowerEvent(0, IgnitionTracker.NA, T0 + 1000);
        assertEquals(IgnitionTracker.Edge.NONE, e);  // 게터 삐끗(NA) — 즉시 OFF로 기울지 않고 시동 유지
        assertEquals(1, t.ign());

        IgnitionTracker.Edge p1 = t.onPowerLevelPoll(0, false, T0 + 2000);
        assertEquals(IgnitionTracker.Edge.NONE, p1);  // 폴링 백스톱 1회차 — 과도 상태로 유지
        assertEquals(1, t.ign());

        IgnitionTracker.Edge p2 = t.onPowerLevelPoll(0, false, T0 + 3000);
        assertEquals(IgnitionTracker.Edge.OFF, p2);  // 0 연속 2회 — 폴링이 확정
        assertEquals(0, t.ign());
    }

    @Test
    public void acEvent_0to1_pwrNA_thenPoll2_confirmsOn() {
        IgnitionTracker.Persisted p = new IgnitionTracker.Persisted();  // 모름/OFF, sawPwr2=false
        IgnitionTracker t = new IgnitionTracker(p, T0);
        t.onAcPowerEvent(0, IgnitionTracker.NA, T0);  // 이전값 확립(NONE)

        IgnitionTracker.Edge e1 = t.onAcPowerEvent(1, IgnitionTracker.NA, T0 + 1000);
        assertEquals(IgnitionTracker.Edge.NONE, e1);

        IgnitionTracker.Edge e2 = t.onPowerLevelPoll(2, false, T0 + 2000);
        assertEquals(IgnitionTracker.Edge.ON, e2);
        assertEquals(1, t.ign());
    }

    @Test
    public void acEvent_0to1_pwr2_isOn() {
        IgnitionTracker.Persisted p = new IgnitionTracker.Persisted();
        IgnitionTracker t = new IgnitionTracker(p, T0);
        t.onAcPowerEvent(0, 2, T0);  // 이전값 확립(NONE)

        IgnitionTracker.Edge e = t.onAcPowerEvent(1, 2, T0 + 1000);

        assertEquals(IgnitionTracker.Edge.ON, e);
        assertEquals(1, t.ign());
    }

    @Test
    public void acEvent_sameValueRebroadcast_isNone() {
        IgnitionTracker t = new IgnitionTracker(onIgn(T0), T0);
        t.onAcPowerEvent(1, 2, T0);  // 이전값 확립(NONE)

        IgnitionTracker.Edge e = t.onAcPowerEvent(1, 2, T0 + 1000);

        assertEquals(IgnitionTracker.Edge.NONE, e);
    }

    @Test
    public void acEvent_panelAcToggle4Times_pwrAlways2_noEdgeIgnitionStaysOn() {
        IgnitionTracker t = new IgnitionTracker(onIgn(T0), T0);
        boolean boardedBefore = t.boarded();
        t.onAcPowerEvent(1, 2, T0);  // 이전값 확립(NONE)

        assertEquals(IgnitionTracker.Edge.NONE, t.onAcPowerEvent(0, 2, T0 + 1000));  // OFF
        assertEquals(IgnitionTracker.Edge.NONE, t.onAcPowerEvent(1, 2, T0 + 2000));  // ON
        assertEquals(IgnitionTracker.Edge.NONE, t.onAcPowerEvent(0, 2, T0 + 3000));  // OFF
        assertEquals(IgnitionTracker.Edge.NONE, t.onAcPowerEvent(1, 2, T0 + 4000));  // ON

        assertEquals(1, t.ign());
        assertEquals(boardedBefore, t.boarded());
    }

    // ── onBoot ──────────────────────────────────────────────────────

    @Test
    public void boot_persistedOn5MinAgo_poll2_isNone() {
        IgnitionTracker t = new IgnitionTracker(onIgn(T0 - 5 * 60 * 1000L), T0);

        IgnitionTracker.Edge e = t.onBoot(2, T0);

        assertEquals(IgnitionTracker.Edge.NONE, e);
        assertEquals(1, t.ign());
    }

    @Test
    public void boot_persistedOn15MinAgo_poll2_isOnStale() {
        IgnitionTracker t = new IgnitionTracker(onIgn(T0 - 15 * 60 * 1000L), T0);

        IgnitionTracker.Edge e = t.onBoot(2, T0);

        assertEquals(IgnitionTracker.Edge.ON, e);
        assertEquals(1, t.ign());
    }

    @Test
    public void boot_onFor15min_polledEvery2sUntil1sAgo_isRestart_NONE() {
        // 시동 15분 전 ON, 하지만 2s 폴링이 계속 돌아 1초 전까지 관측됨(=주행 중 릴레이 1~2초 재시작)
        // → stale 판정은 lastIgnTs가 아니라 lastSeenTs 기준이어야 하므로 NONE(프리셋 재발동 금지)
        IgnitionTracker t = new IgnitionTracker(
                onIgnSeen(T0 - 15 * 60 * 1000L, T0 - 1000L), T0);

        IgnitionTracker.Edge e = t.onBoot(2, T0);

        assertEquals(IgnitionTracker.Edge.NONE, e);
        assertEquals(1, t.ign());
    }

    @Test
    public void boot_onFor15min_notSeenFor12min_isStale_ON() {
        // 15분 전 ON, 마지막 관측도 12분 전(릴레이가 그 뒤로 실제로 죽어 있었음) → 진짜 stale, ON 엣지
        IgnitionTracker t = new IgnitionTracker(
                onIgnSeen(T0 - 15 * 60 * 1000L, T0 - 12 * 60 * 1000L), T0);

        IgnitionTracker.Edge e = t.onBoot(2, T0);

        assertEquals(IgnitionTracker.Edge.ON, e);
        assertEquals(1, t.ign());
    }

    @Test
    public void boot_persistedOn_poll0_isOffAndKeepsPersistedBoarded() {
        IgnitionTracker.Persisted p = onIgn(T0 - 5 * 60 * 1000L);
        p.boarded = true;
        IgnitionTracker t = new IgnitionTracker(p, T0);

        IgnitionTracker.Edge e = t.onBoot(0, T0);

        assertEquals(IgnitionTracker.Edge.OFF, e);
        assertEquals(0, t.ign());
        assertTrue(t.boarded());
    }

    @Test
    public void boot_noPersisted_poll2_isOn() {
        IgnitionTracker.Persisted p = new IgnitionTracker.Persisted();  // lastIgn=-1
        IgnitionTracker t = new IgnitionTracker(p, T0);

        IgnitionTracker.Edge e = t.onBoot(2, T0);

        assertEquals(IgnitionTracker.Edge.ON, e);
        assertEquals(1, t.ign());
    }

    // ── onPowerLevelPoll — sawPwr2 이전 ─────────────────────────────

    @Test
    public void poll_d3_beforeSawPwr2_zero3Times_allNoneAndIgnUnknown() {
        IgnitionTracker.Persisted p = new IgnitionTracker.Persisted();  // sawPwr2=false, lastIgn=-1
        IgnitionTracker t = new IgnitionTracker(p, T0);

        assertEquals(IgnitionTracker.Edge.NONE, t.onPowerLevelPoll(0, false, T0 + 1000));
        assertEquals(IgnitionTracker.Edge.NONE, t.onPowerLevelPoll(0, false, T0 + 2000));
        assertEquals(IgnitionTracker.Edge.NONE, t.onPowerLevelPoll(0, false, T0 + 3000));

        assertEquals(-1, t.ign());
    }

    @Test
    public void poll_d5_beforeSawPwr2_screenOnProxy_isOn() {
        IgnitionTracker.Persisted p = new IgnitionTracker.Persisted();  // sawPwr2=false, lastIgn=-1
        IgnitionTracker t = new IgnitionTracker(p, T0);

        IgnitionTracker.Edge e = t.onPowerLevelPoll(0, true, T0 + 1000);

        assertEquals(IgnitionTracker.Edge.ON, e);
        assertEquals(1, t.ign());
    }

    @Test
    public void poll_d5_NA_beforeSawPwr2_screenOn_isOn() {
        // SDK 미준비 구간은 pwrLevel==0이 아니라 NA로 나타날 수도 있다 — 화면 프록시는 NA에도 적용돼야 한다.
        IgnitionTracker.Persisted p = new IgnitionTracker.Persisted();  // sawPwr2=false, lastIgn=-1
        IgnitionTracker t = new IgnitionTracker(p, T0);

        IgnitionTracker.Edge e = t.onPowerLevelPoll(IgnitionTracker.NA, true, T0 + 1000);

        assertEquals(IgnitionTracker.Edge.ON, e);
        assertEquals(1, t.ign());
    }

    @Test
    public void poll_d3_NA_screenOff_NONE() {
        IgnitionTracker.Persisted p = new IgnitionTracker.Persisted();  // sawPwr2=false, lastIgn=-1
        IgnitionTracker t = new IgnitionTracker(p, T0);

        IgnitionTracker.Edge e = t.onPowerLevelPoll(IgnitionTracker.NA, false, T0 + 1000);

        assertEquals(IgnitionTracker.Edge.NONE, e);
        assertEquals(-1, t.ign());
    }

    // ── onPowerLevelPoll — sawPwr2 이후 ─────────────────────────────

    @Test
    public void poll_zeroOnceThenTwo_firstKeepsIgnitionSecondNoneAlreadyOn() {
        IgnitionTracker t = new IgnitionTracker(onIgn(T0), T0);

        IgnitionTracker.Edge e1 = t.onPowerLevelPoll(0, false, T0 + 1000);
        assertEquals(IgnitionTracker.Edge.NONE, e1);
        assertEquals(1, t.ign());

        IgnitionTracker.Edge e2 = t.onPowerLevelPoll(2, false, T0 + 2000);
        assertEquals(IgnitionTracker.Edge.NONE, e2);
        assertEquals(1, t.ign());
    }

    @Test
    public void poll_zeroTwiceInARow_secondIsOff() {
        IgnitionTracker t = new IgnitionTracker(onIgn(T0), T0);

        IgnitionTracker.Edge e1 = t.onPowerLevelPoll(0, false, T0 + 1000);
        assertEquals(IgnitionTracker.Edge.NONE, e1);

        IgnitionTracker.Edge e2 = t.onPowerLevelPoll(0, false, T0 + 2000);
        assertEquals(IgnitionTracker.Edge.OFF, e2);
        assertEquals(0, t.ign());
    }

    // ── onDoorEvent / boarded ────────────────────────────────────────

    @Test
    public void doorEvent60sBeforeOnEdge_boardedTrue() {
        IgnitionTracker.Persisted p = new IgnitionTracker.Persisted();
        IgnitionTracker t = new IgnitionTracker(p, T0);

        t.onDoorEvent(T0);
        IgnitionTracker.Edge e = t.onPowerLevelPoll(2, false, T0 + 60_000L);

        assertEquals(IgnitionTracker.Edge.ON, e);
        assertTrue(t.boarded());
    }

    @Test
    public void noDoorEvent_onEdge_boardedFalse() {
        IgnitionTracker.Persisted p = new IgnitionTracker.Persisted();
        IgnitionTracker t = new IgnitionTracker(p, T0);

        IgnitionTracker.Edge e = t.onPowerLevelPoll(2, false, T0 + 1000);

        assertEquals(IgnitionTracker.Edge.ON, e);
        assertFalse(t.boarded());
    }

    // ── lastIgnTs는 실제 엣지 때만 갱신 ──────────────────────────────

    @Test
    public void repeatedPoll2_alreadyOn_doesNotBumpLastIgnTs() {
        long staleTs = T0 - 5 * 60 * 1000L;
        IgnitionTracker t = new IgnitionTracker(onIgn(staleTs), T0);

        IgnitionTracker.Edge e = t.onPowerLevelPoll(2, false, T0);

        assertEquals(IgnitionTracker.Edge.NONE, e);
        assertEquals(staleTs, t.snapshot().lastIgnTs);
    }

    // ── snapshot() / sawPwr2 영속 ────────────────────────────────────

    @Test
    public void onEdge_reflectedInSnapshot() {
        IgnitionTracker.Persisted p = new IgnitionTracker.Persisted();
        IgnitionTracker t = new IgnitionTracker(p, T0);

        IgnitionTracker.Edge e = t.onPowerLevelPoll(2, false, T0);

        assertEquals(IgnitionTracker.Edge.ON, e);
        IgnitionTracker.Persisted snap = t.snapshot();
        assertEquals(1, snap.lastIgn);
        assertEquals(T0, snap.lastIgnTs);
        assertTrue(snap.sawPwr2);
    }

    // ★재검증 #3 잔여 — D5: 화면 프록시로 ON 확정 후 SDK가 2를 끝내 안 주고(0만) 화면이 꺼지면, 연속 3회에 OFF
    @Test public void poll_d5_screenOnProxyOn_thenZeroWithScreenOff3x_isOff() {
        IgnitionTracker t = new IgnitionTracker(new IgnitionTracker.Persisted(), 0L);
        assertEquals(IgnitionTracker.Edge.ON, t.onPowerLevelPoll(0, true, 1000L));     // 화면 프록시 ON
        assertEquals(IgnitionTracker.Edge.NONE, t.onPowerLevelPoll(0, false, 3000L));  // 1회
        assertEquals(IgnitionTracker.Edge.NONE, t.onPowerLevelPoll(0, false, 5000L));  // 2회
        assertEquals(IgnitionTracker.Edge.OFF, t.onPowerLevelPoll(0, false, 7000L));   // 3회 → OFF
        assertEquals(0, t.ign());
    }
    // 화면이 중간에 켜지면 카운트 리셋
    @Test public void poll_d5_darkStreakResetsOnScreenOn() {
        IgnitionTracker t = new IgnitionTracker(new IgnitionTracker.Persisted(), 0L);
        t.onPowerLevelPoll(0, true, 1000L);
        t.onPowerLevelPoll(0, false, 3000L);
        t.onPowerLevelPoll(0, false, 5000L);
        assertEquals(IgnitionTracker.Edge.NONE, t.onPowerLevelPoll(0, true, 7000L));   // 리셋
        assertEquals(IgnitionTracker.Edge.NONE, t.onPowerLevelPoll(0, false, 9000L));
        assertEquals(IgnitionTracker.Edge.NONE, t.onPowerLevelPoll(0, false, 11000L));
        assertEquals(1, t.ign());
    }
    // D3(screenOn 항상 false, ign 미확정)는 이 경로에 영향 없음
    @Test public void poll_d3_zeroScreenOff3x_beforeSawPwr2_staysUnknown() {
        IgnitionTracker t = new IgnitionTracker(new IgnitionTracker.Persisted(), 0L);
        for (int i = 0; i < 5; i++) assertEquals(IgnitionTracker.Edge.NONE, t.onPowerLevelPoll(0, false, 1000L * (i + 1)));
        assertEquals(-1, t.ign());
    }
}
