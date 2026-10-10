package com.cseini.bydsniffer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.cseini.motiondetect.PlateTracker;

public class FrontAlertTrackerTest {

    private static final long T0 = 1_000_000_000L;

    private static FrontAlertTracker.Box box(float conf, float cx, float cy, float h) {
        return new FrontAlertTracker.Box(conf, cx, cy, 0f, h);
    }

    private static FrontAlertTracker.Tick tick(long t, boolean stopped, FrontAlertTracker.Box[] veh) {
        FrontAlertTracker.Tick tk = new FrontAlertTracker.Tick();
        tk.nowMs = t; tk.stopped = stopped; tk.vehicles = veh; tk.motPct = 0;
        return tk;
    }

    private static PlateTracker.Plate plate(float cx, float wPx) {
        PlateTracker.Plate p = new PlateTracker.Plate();
        p.cx = cx; p.cy = 0.5f; p.wNorm = wPx / 1000f; p.score = 1f;
        return p;
    }

    // ── 1. 객체 스왑(2026-09-11 실측 오발화) ──────────────────────────

    @Test
    public void objectSwap_sideCarIgnored_thenOriginalCarRecede_fires() {
        FrontAlertTracker t = new FrontAlertTracker(new FrontAlertTracker.Config());

        // 무장(정지한 앞차 cx=0.5,cy=0.53,h=0.50)
        assertEquals(FrontAlertTracker.Event.NONE, t.onTick(tick(T0, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.53f, 0.50f)})).event);
        // 2.5s 경과 지점까지 같은 위치 유지 → 무장 확정
        assertEquals(FrontAlertTracker.Event.NONE, t.onTick(tick(T0 + 2600, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.53f, 0.50f)})).event);

        // 옆 차선 차가 더 높은 conf로 등장 — 원래 앞차는 그대로
        FrontAlertTracker.Box sideCar = box(0.95f, 0.85f, 0.29f, 0.57f);
        FrontAlertTracker.Box origCar = box(0.9f, 0.5f, 0.53f, 0.50f);
        FrontAlertTracker.Result swap = t.onTick(tick(T0 + 2700, true, new FrontAlertTracker.Box[]{sideCar, origCar}));
        assertEquals(FrontAlertTracker.Event.NONE, swap.event);

        // 원래 앞차가 실제로 멀어짐(cy 0.53→0.40→0.30, h 0.50→0.40→0.32)
        assertEquals(FrontAlertTracker.Event.NONE,
                t.onTick(tick(T0 + 2800, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.40f, 0.40f)})).event);
        FrontAlertTracker.Result depart = t.onTick(tick(T0 + 2900, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.30f, 0.32f)}));
        assertEquals(FrontAlertTracker.Event.LEAD_DEPART, depart.event);
    }

    // ── 2. 정상 출발 / 1틱 recede 후 복귀 ──────────────────────────────

    @Test
    public void normalDepart_twoConsecutiveRecedeTicks_fires() {
        FrontAlertTracker t = new FrontAlertTracker(new FrontAlertTracker.Config());
        t.onTick(tick(T0, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.53f, 0.50f)}));
        t.onTick(tick(T0 + 2600, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.53f, 0.50f)}));   // 무장 확정
        t.onTick(tick(T0 + 2700, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.40f, 0.40f)}));   // recede 1
        FrontAlertTracker.Result r = t.onTick(tick(T0 + 2800, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.30f, 0.32f)}));   // recede 2
        assertEquals(FrontAlertTracker.Event.LEAD_DEPART, r.event);
    }

    @Test
    public void oneTickRecedeThenRevert_none() {
        FrontAlertTracker t = new FrontAlertTracker(new FrontAlertTracker.Config());
        t.onTick(tick(T0, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.53f, 0.50f)}));
        t.onTick(tick(T0 + 2600, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.53f, 0.50f)}));   // 무장 확정
        t.onTick(tick(T0 + 2700, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.40f, 0.40f)}));   // recede 1
        // 원위치로 복귀(안정) — recedeN 리셋
        t.onTick(tick(T0 + 2800, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.53f, 0.50f)}));
        t.onTick(tick(T0 + 2900, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.53f, 0.50f)}));
        FrontAlertTracker.Result r = t.onTick(tick(T0 + 3000, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.40f, 0.40f)}));   // recede 1 (다시 처음부터)
        assertEquals(FrontAlertTracker.Event.NONE, r.event);
    }

    // ── 3. 도착(다가와 정착) ────────────────────────────────────────

    @Test
    public void arrival_approachingCarSettles_none() {
        FrontAlertTracker t = new FrontAlertTracker(new FrontAlertTracker.Config());
        // 멀리서 등장(작고 위쪽) → 점점 다가옴(cy·h 증가) → 정착
        assertEquals(FrontAlertTracker.Event.NONE, t.onTick(tick(T0, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.20f, 0.15f)})).event);
        assertEquals(FrontAlertTracker.Event.NONE, t.onTick(tick(T0 + 500, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.30f, 0.22f)})).event);
        assertEquals(FrontAlertTracker.Event.NONE, t.onTick(tick(T0 + 1000, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.42f, 0.30f)})).event);
        assertEquals(FrontAlertTracker.Event.NONE, t.onTick(tick(T0 + 1500, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.50f, 0.38f)})).event);
        // 정착 후 무장 경과까지 유지
        assertEquals(FrontAlertTracker.Event.NONE, t.onTick(tick(T0 + 2000, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.50f, 0.38f)})).event);
        FrontAlertTracker.Result r = t.onTick(tick(T0 + 4600, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.50f, 0.38f)}));
        assertEquals(FrontAlertTracker.Event.NONE, r.event);
    }

    // ── 4. 모순 게이트(cy↓ & h↑) ────────────────────────────────────

    @Test
    public void contradiction_cyDownButGrew_none() {
        FrontAlertTracker t = new FrontAlertTracker(new FrontAlertTracker.Config());
        t.onTick(tick(T0, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.53f, 0.40f)}));
        t.onTick(tick(T0 + 2600, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.53f, 0.40f)}));   // 무장 확정
        // cy는 내려갔지만(멀어짐처럼 보임) h는 커짐(다른 차로 바뀐 모순) → 발화 금지
        FrontAlertTracker.Result r1 = t.onTick(tick(T0 + 2700, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.35f, 0.50f)}));
        FrontAlertTracker.Result r2 = t.onTick(tick(T0 + 2800, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.30f, 0.55f)}));
        assertEquals(FrontAlertTracker.Event.NONE, r1.event);
        assertEquals(FrontAlertTracker.Event.NONE, r2.event);
    }

    // ── 5. stop-and-go — 무장 유지 ──────────────────────────────────

    @Test
    public void stopAndGo_briefUnstop_keepsArmTimer() {
        FrontAlertTracker t = new FrontAlertTracker(new FrontAlertTracker.Config());
        t.onTick(tick(T0, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.53f, 0.50f)}));   // 무장 시작
        // 1초간 정차 풀림(stopWin 3s 이내) — 검출 없음
        t.onTick(tick(T0 + 1000, false, null));
        t.onTick(tick(T0 + 1200, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.53f, 0.50f)}));   // 복귀
        // 무장 유지됐다면 T0+3000 시점(=원래 무장으로부터 2.5s+ 경과)엔 이미 armed → 2틱 recede로 바로 발화
        t.onTick(tick(T0 + 3000, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.40f, 0.40f)}));
        FrontAlertTracker.Result r = t.onTick(tick(T0 + 3100, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.30f, 0.32f)}));
        assertEquals(FrontAlertTracker.Event.LEAD_DEPART, r.event);
    }

    // ── 6. 먼 앞차(낮은 conf) — 적응형 미검출 허용창 ────────────────────

    @Test
    public void farLeadLowConf_3sGap_armPreserved_thenFires() {
        FrontAlertTracker t = new FrontAlertTracker(new FrontAlertTracker.Config());
        t.onTick(tick(T0, true, new FrontAlertTracker.Box[]{box(0.45f, 0.5f, 0.53f, 0.50f)}));   // 무장(낮은 conf)
        // 3초간 미검출(< missWindowLowConfMs=4000)
        t.onTick(tick(T0 + 1000, true, null));
        t.onTick(tick(T0 + 2000, true, null));
        t.onTick(tick(T0 + 3000, true, new FrontAlertTracker.Box[]{box(0.45f, 0.5f, 0.44f, 0.44f)}));   // 재검출 — recede 1
        FrontAlertTracker.Result r = t.onTick(tick(T0 + 3100, true, new FrontAlertTracker.Box[]{box(0.45f, 0.5f, 0.38f, 0.40f)}));   // recede 2
        assertEquals(FrontAlertTracker.Event.LEAD_DEPART, r.event);
    }

    @Test
    public void farLeadLowConf_5sGap_armReset_noImmediateFire() {
        FrontAlertTracker t = new FrontAlertTracker(new FrontAlertTracker.Config());
        t.onTick(tick(T0, true, new FrontAlertTracker.Box[]{box(0.45f, 0.5f, 0.53f, 0.50f)}));   // 무장(낮은 conf)
        // 5초간 미검출(> missWindowLowConfMs=4000) — 트랙 리셋
        t.onTick(tick(T0 + 5000, true, null));
        // 재검출 — 새 트랙(무장 재시작). 3s 테스트와 '매칭 가능한' 동일 recede 폭(0.53→0.44→0.38)을 주되
        // armWaitMs 전이라 발화 안 함 — 무장이 실제로 리셋 안 됐다면(버그) leadArmedTs가 T0(=여기서 5000ms+
        // 지남)에 그대로 남아 armed=true가 되어 이 시퀀스로도 발화한다. 즉 이 테스트는 리셋이 안 되면 실패한다.
        t.onTick(tick(T0 + 5000, true, new FrontAlertTracker.Box[]{box(0.45f, 0.5f, 0.53f, 0.50f)}));
        t.onTick(tick(T0 + 5100, true, new FrontAlertTracker.Box[]{box(0.45f, 0.5f, 0.44f, 0.44f)}));
        FrontAlertTracker.Result r = t.onTick(tick(T0 + 5200, true, new FrontAlertTracker.Box[]{box(0.45f, 0.5f, 0.38f, 0.40f)}));
        assertEquals(FrontAlertTracker.Event.NONE, r.event);
    }

    // ── 신호등 ──────────────────────────────────────────────────────

    private static FrontAlertTracker.Tick trafficTick(long t, float r, float g) {
        FrontAlertTracker.Tick tk = new FrontAlertTracker.Tick();
        tk.nowMs = t; tk.stopped = true; tk.r = r; tk.g = g;
        return tk;
    }

    @Test
    public void trafficLight_redThenGreen_fires() {
        FrontAlertTracker t = new FrontAlertTracker(new FrontAlertTracker.Config());
        for (int i = 0; i < 5; i++) t.onTick(trafficTick(T0 + i * 300, 0.02f, 0.00f));   // 적색 5틱
        FrontAlertTracker.Result r = t.onTick(trafficTick(T0 + 5 * 300, 0.00f, 0.05f));   // g 상승 → 녹색
        assertEquals(FrontAlertTracker.Event.LIGHT_GREEN, r.event);
    }

    @Test
    public void trafficLight_backgroundGreen_absorbedByGBase_thenRealJumpFires() {
        FrontAlertTracker t = new FrontAlertTracker(new FrontAlertTracker.Config());
        // 배경 초록(나무 등) g=0.06 상시 — 적색 중에도 존재
        for (int i = 0; i < 5; i++) t.onTick(trafficTick(T0 + i * 300, 0.20f, 0.06f));
        // g가 배경 수준(0.06)에 머물면 발화 안 함
        FrontAlertTracker.Result stay = t.onTick(trafficTick(T0 + 5 * 300, 0.02f, 0.06f));
        assertEquals(FrontAlertTracker.Event.NONE, stay.event);
        // g가 실제로 0.10까지 뛰면 발화
        FrontAlertTracker.Result jump = t.onTick(trafficTick(T0 + 6 * 300, 0.02f, 0.10f));
        assertEquals(FrontAlertTracker.Event.LIGHT_GREEN, jump.event);
    }

    @Test
    public void trafficLight_medianGBase_absorbsOutlier_thenRealJumpFires() {
        FrontAlertTracker.Config cfg = new FrontAlertTracker.Config();
        cfg.greenBaseMode = FrontAlertTracker.GreenBaseMode.MEDIAN;
        FrontAlertTracker t = new FrontAlertTracker(cfg);
        // 적색 구간 g 표본 [0.02,0.08,0.03,0.09,0.04] — 중앙값 0.04(바닥값 0.02였다면 임계가 더 낮았을 것)
        float[] gs = {0.02f, 0.08f, 0.03f, 0.09f, 0.04f};
        for (int i = 0; i < gs.length; i++) t.onTick(trafficTick(T0 + i * 300, 0.20f, gs[i]));
        // g=0.06은 중앙값(0.04)+0.03=0.07 문턱을 못 넘음 — 발화 없음
        assertEquals(FrontAlertTracker.Event.NONE, t.onTick(trafficTick(T0 + gs.length * 300, 0.02f, 0.06f)).event);
        // g=0.10은 문턱을 넘음 — 발화
        assertEquals(FrontAlertTracker.Event.LIGHT_GREEN, t.onTick(trafficTick(T0 + (gs.length + 1) * 300, 0.02f, 0.10f)).event);
    }

    @Test
    public void trafficLight_greenWithoutRedHistory_none() {
        FrontAlertTracker t = new FrontAlertTracker(new FrontAlertTracker.Config());
        FrontAlertTracker.Result r = t.onTick(trafficTick(T0, 0.00f, 0.50f));   // 적색이력 없이 g만 있음
        assertEquals(FrontAlertTracker.Event.NONE, r.event);
    }

    @Test
    public void trafficLight_cooldown4s_suppressesSecondFire() {
        FrontAlertTracker t = new FrontAlertTracker(new FrontAlertTracker.Config());
        for (int i = 0; i < 3; i++) t.onTick(trafficTick(T0 + i * 300, 0.20f, 0.00f));
        FrontAlertTracker.Result first = t.onTick(trafficTick(T0 + 3 * 300, 0.00f, 0.10f));
        assertEquals(FrontAlertTracker.Event.LIGHT_GREEN, first.event);
        // 곧바로 다시 적→녹 조건을 만들어도 4s 쿨다운 안에는 재발화 안 함
        long t2 = T0 + 3 * 300 + 500;
        for (int i = 0; i < 3; i++) t.onTick(trafficTick(t2 + i * 300, 0.20f, 0.00f));
        FrontAlertTracker.Result second = t.onTick(trafficTick(t2 + 3 * 300, 0.00f, 0.10f));
        assertEquals(FrontAlertTracker.Event.NONE, second.event);
    }

    // ── 번호판 보조 신호 ─────────────────────────────────────────────

    @Test
    public void plateShrink_overridesStableBbox_fires() {
        FrontAlertTracker t = new FrontAlertTracker(new FrontAlertTracker.Config());
        FrontAlertTracker.Box car = box(0.9f, 0.5f, 0.5f, 0.4f);   // bbox는 전 구간 동일(안정)
        FrontAlertTracker.Tick t0 = tick(T0, true, new FrontAlertTracker.Box[]{car});
        t0.plate = plate(0.5f, 40f);
        assertEquals(FrontAlertTracker.Event.NONE, t.onTick(t0).event);   // 무장

        FrontAlertTracker.Tick t1 = tick(T0 + 2600, true, new FrontAlertTracker.Box[]{car});
        t1.plate = plate(0.5f, 40f);
        assertEquals(FrontAlertTracker.Event.NONE, t.onTick(t1).event);   // 무장 확정(연속 2틱 번호판 확보)

        FrontAlertTracker.Tick t2 = tick(T0 + 2700, true, new FrontAlertTracker.Box[]{car});
        t2.plate = plate(0.5f, 34f);   // 15% 축소
        assertEquals(FrontAlertTracker.Event.NONE, t.onTick(t2).event);

        FrontAlertTracker.Tick t3 = tick(T0 + 2800, true, new FrontAlertTracker.Box[]{car});
        t3.plate = plate(0.5f, 32f);   // 추가 축소 — 2틱 연속
        assertEquals(FrontAlertTracker.Event.LEAD_DEPART, t.onTick(t3).event);
    }

    @Test
    public void plateStable_despiteBboxJitter_none() {
        FrontAlertTracker t = new FrontAlertTracker(new FrontAlertTracker.Config());
        FrontAlertTracker.Tick t0 = tick(T0, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.5f, 0.4f)});
        t0.plate = plate(0.5f, 40f);
        t.onTick(t0);

        FrontAlertTracker.Tick t1 = tick(T0 + 2600, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.5f, 0.4f)});
        t1.plate = plate(0.5f, 40f);
        t.onTick(t1);   // 무장 확정 + 번호판 연속 확보

        // bbox는 노이즈로 cy가 계속 튀지만(트랙 매칭은 유지되는 폭 안에서) 번호판 폭은 그대로(40) — 발화 없음
        FrontAlertTracker.Tick t2 = tick(T0 + 2700, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.42f, 0.4f)});
        t2.plate = plate(0.5f, 40f);
        assertEquals(FrontAlertTracker.Event.NONE, t.onTick(t2).event);

        FrontAlertTracker.Tick t3 = tick(T0 + 2800, true, new FrontAlertTracker.Box[]{box(0.9f, 0.5f, 0.58f, 0.4f)});
        t3.plate = plate(0.5f, 40f);
        assertEquals(FrontAlertTracker.Event.NONE, t.onTick(t3).event);
    }
}
