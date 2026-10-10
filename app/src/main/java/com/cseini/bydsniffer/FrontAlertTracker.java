package com.cseini.bydsniffer;

import com.cseini.motiondetect.PlateTracker;

/**
 * 전방 대시캠 주행 알림(앞차 출발/신호 변경) 판정기 — android.* 의존 없는 순수 Java.
 *
 * SnifferService.handleFrontAlert(2026-09-08~11)의 상태머신을 그대로 옮기고 다중 객체 추적을 추가했다.
 * 배경(2026-09-11 실차 오발화 07:29:15): 검출기(analyzeClass)가 ROI 안에서 '가장 확실한 차' 하나만
 * 돌려주면, 옆 차선 차가 더 크게/높은 conf로 잡히는 순간 추적 대상이 소리 없이 그 차로 바뀌어
 * "앞차는 그대로 있는데 옆 차가 지나가면 출발로 오판"하는 문제가 생긴다. 이 클래스는 여러 후보
 * (analyzeClassAll) 중 **기존 추적 박스와 연속성 있는 후보만** 채택하고, 매칭 실패는 미검출과 동일하게
 * 취급해(트랙을 다른 차로 재배정하지 않음) 스왑을 원천 차단한다.
 *
 * 배선(호출측)은 이 클래스 밖의 책임이다 — SnifferService.handleFrontAlert는 그대로 두고
 * 나중에 코디네이터가 이 클래스 결과를 사운드 재생 등에 연결한다.
 */
public final class FrontAlertTracker {

    // ── 입출력 타입 ──────────────────────────────────────────────────

    /** 검출 bbox — ROI(또는 프레임) 정규화 0~1, cy 1=하단(가까움). cx가 Float.NaN이면 "모름"(가로위치 미로깅
     *  구간 등) — 매칭/안정성 판정에서 cx 게이트를 건너뛴다(cy·h만으로 판단). */
    public static final class Box {
        public float conf, cx, cy, w, h;
        public Box() {}
        public Box(float conf, float cx, float cy, float w, float h) {
            this.conf = conf; this.cx = cx; this.cy = cy; this.w = w; this.h = h;
        }
    }

    /** 한 틱의 입력 — 호출측이 프레임마다 채워 onTick에 넘긴다. */
    public static final class Tick {
        public long nowMs;
        public boolean stopped;                 // 정차 판정(≤3km/h && 전진기어)
        public Box[] vehicles;                  // 앞차 후보들(conf 내림차순 권장, null/빈 배열=미검출)
        public Box light;                       // 신호등 객체(앵커) — null 가능
        public float lightAsp;                  // 신호등 bbox 종횡비(가로/세로)
        public float r, g;                      // 신호 ROI(앵커 or 타일 폴백) 빨강/초록 비율
        public int motPct;                      // 앞차 ROI 움직임 비율(%)
        public PlateTracker.Plate plate;        // 번호판(호출측이 추적 중인 앞차 bbox로 PlateTracker.find 호출) — null 가능
    }

    // ★한 틱에 앞차 출발·신호 변경이 동시에 조건을 만족하면 앞차 쪽이 우선하고 신호 이벤트는 그 틱에서
    //   유실된다(원 코드는 사운드를 둘 다 재생했음). 실차에서 같은 틱에 둘 다 맞아떨어질 확률은 낮지만,
    //   필요해지면 Result를 이벤트 리스트로 바꿔야 한다 — 지금은 설계상 알려진 제약으로만 남겨둔다.
    public enum Event { NONE, LEAD_DEPART, LIGHT_GREEN }

    public static final class Result {
        public final Event event;
        public final String why;   // 진단 문자열(발화 사유 또는 NONE 설명)
        Result(Event event, String why) { this.event = event; this.why = why; }
    }

    public enum GreenBaseMode { FLOOR, MEDIAN }

    /** 임계 전부 여기로 — 튜닝/테스트 대상. */
    public static final class Config {
        public float leadConfMin = 0.4f;                         // 앞차 후보 최소 conf

        // 트랙 연속성 매칭(스왑 방지) — 직전 매칭 틱(curCx/Cy/H) 대비 이 안에 들어야 '같은 차'.
        // cy는 recede 중 틱당 이동폭보다 넉넉해야(옆 차선 판별은 주로 cx·h비율이 담당).
        public float trackMatchCx = 0.13f, trackMatchCy = 0.20f;
        public float trackMatchHRatioMin = 0.7f, trackMatchHRatioMax = 1.4f;
        // ★일관된 멀어짐(cy↓, h 비증가)일 때만 쓰는 넓은 허용치 — 1.5s 틱 간 진짜 출발(E009 cy Δ0.25·h 0.74배) 수용
        public float trackMatchCyReceding = 0.40f, trackMatchHRatioMinReceding = 0.45f;

        // 정지 안정성 판정(직전 틱 대비) — base 갱신 여부
        public float stableCy = 0.06f, stableH = 0.10f, stableCx = 0.10f;

        public long armWaitMs = 2500;      // 무장까지 대기(정지한 앞차 확인)
        public long stopWinMs = 3000;      // 정차 풀림 유예창(stop-and-go 방어)

        public float growThresh = 1.05f;   // h가 base*이 값 넘으면 '커짐'(모순 게이트)
        public float recedeCy = 0.08f, recedeHFrac = 0.30f;   // 멀어짐 판정(cy 하강 또는 h 축소)
        public int recedeNThresh = 2;      // 연속(가산/감산) 카운트 임계
        public long leadCooldownMs = 4000;

        public int vanishMotMin = 8;            // 사라짐 판정 보조 — ROI 움직임 최소치(%)
        public float vanishBaseCyMin = 0.35f;   // 근~중거리에 있던 차만 '사라짐=출발'로 인정
        public long vanishHoldMs = 500;         // 마지막 매칭 후 최소 이 시간은 지나야 사라짐 인정

        // 미검출 허용 창 — 마지막 conf가 낮으면(먼 차) 더 오래 참는다(적응형)
        public long missWindowNormalMs = 2500, missWindowLowConfMs = 4000;
        public float lowConfThresh = 0.5f;

        // 번호판 보조 신호(2026-09-11) — 물리 폭 고정이라 거리에 반비례, bbox보다 노이즈에 강함
        public boolean platePriority = true;    // 연속 2틱+ 확보되면 bbox 대신 번호판 폭 비율로 판정
        public float plateShrinkRatio = 0.85f;  // now/base가 이 이하면 멀어짐(15%+ 축소)
        public float plateCxMatch = 0.10f;      // 번호판 동일차량 판정

        // ★이 두 값은 onTick 내부에서 직접 쓰지 않는다(r,g는 호출측이 이미 앵커/타일 크롭에서 뽑아 넘겨줌) —
        //   호출측(SnifferService 배선)이 t.light/t.lightAsp로 앵커 여부를 판단할 때 참조하는 공유 기준값.
        public float lightConfMin = 0.25f, lightAspMin = 1.2f;   // 신호등 앵커(가로형만, 횡단보도 배제)
        public float redRMin = 0.015f, redRatio = 2.0f;
        public float greenDelta = 0.03f, greenRatio = 2.0f;
        public long trafficCooldownMs = 4000;
        public GreenBaseMode greenBaseMode = GreenBaseMode.FLOOR;   // 적색구간 g 기준선 — 바닥값 vs 중앙값
    }

    private final Config cfg;

    public FrontAlertTracker(Config cfg) { this.cfg = cfg; }

    // ── 앞차 트랙 상태 ───────────────────────────────────────────────
    private long lastStoppedTs = 0;
    private long leadArmedTs = 0;     // 0=트랙 없음(미무장)
    private long leadSeenTs = 0;      // 마지막으로 후보 매칭된 시각
    private float baseCx = -1, baseCy = -1, baseH = -1;   // 무장 기준(가장 가까울 때 = 최대 cy/h)
    private float curCx = -1, curCy = -1, curH = -1;      // 마지막 매칭 틱 값(안정성 비교용)
    private int recedeN = 0;
    private float lastConf = 0;
    private long lastLeadAlertTs = 0;
    // 번호판
    private float basePlateW = -1, basePlateCx = -1;
    private int plateStreak = 0;
    private float lastPlateW = -1;

    // ── 신호등 상태 ──────────────────────────────────────────────────
    private boolean prevStopped = false;
    private boolean lastRed = false;
    private float greenBase = 1f;
    private final java.util.List<Float> redGSamples = new java.util.ArrayList<>();
    private long lastTrafficAlertTs = 0;
    private float lastR = -1, lastG = -1;
    private long lastNowMs = 0;   // diag()용 — 마지막 onTick의 now(실시간이 아니라 틱 시각 기준으로 보여야 재생/테스트에서도 맞음

    public Result onTick(Tick t) {
        long now = t.nowMs;
        lastNowMs = now;
        Event event = Event.NONE;
        String why = "";

        // ── 앞차 출발 ──
        if (t.stopped) lastStoppedTs = now;
        boolean stopWin = t.stopped || (now - lastStoppedTs < cfg.stopWinMs);
        if (!stopWin) resetLeadTrack();

        if (stopWin) {
            Box cand = pickVehicleCandidate(t.vehicles, t.stopped);
            boolean armed = leadArmedTs > 0 && now - leadArmedTs >= cfg.armWaitMs;

            if (cand == null) {
                if (leadArmedTs > 0) {
                    if (armed && t.motPct >= cfg.vanishMotMin && baseCy > cfg.vanishBaseCyMin
                            && now - leadSeenTs > cfg.vanishHoldMs && now - lastLeadAlertTs > cfg.leadCooldownMs) {
                        event = Event.LEAD_DEPART; why = "앞차 출발(사라짐 baseCy=" + baseCy + ")";
                        lastLeadAlertTs = now; resetLeadTrack();
                    } else {
                        long missWindow = lastConf < cfg.lowConfThresh ? cfg.missWindowLowConfMs : cfg.missWindowNormalMs;
                        if (now - leadSeenTs > missWindow) resetLeadTrack();
                    }
                }
            } else {
                lastConf = cand.conf;
                if (leadArmedTs == 0) {
                    // 새 트랙 시작(정지 중에만 pickVehicleCandidate가 후보를 준다)
                    leadArmedTs = now; leadSeenTs = now;
                    baseCx = cand.cx; baseCy = cand.cy; baseH = cand.h; recedeN = 0;
                    curCx = cand.cx; curCy = cand.cy; curH = cand.h;
                    if (t.plate != null) { basePlateW = t.plate.wNorm; basePlateCx = t.plate.cx; plateStreak = 1; lastPlateW = t.plate.wNorm; }
                    else { basePlateW = -1; basePlateCx = -1; plateStreak = 0; lastPlateW = -1; }
                } else {
                    leadSeenTs = now;
                    boolean stable = Math.abs(cand.cy - curCy) < cfg.stableCy && Math.abs(cand.h - curH) < cfg.stableH
                            && withinOrUnknown(cand.cx, curCx, cfg.stableCx);
                    curCx = cand.cx; curCy = cand.cy; curH = cand.h;

                    // 번호판 연속성/기준 갱신 — bbox와 별개로 매 틱 추적
                    if (t.plate != null) {
                        boolean plateSameCar = basePlateCx < 0 || Math.abs(t.plate.cx - basePlateCx) < cfg.plateCxMatch;
                        if (plateSameCar) {
                            plateStreak++;
                            lastPlateW = t.plate.wNorm;
                            if (!armed || (stable && t.stopped)) {
                                if (t.plate.wNorm > basePlateW) basePlateW = t.plate.wNorm;
                                if (basePlateCx < 0) basePlateCx = t.plate.cx;
                            }
                        } else plateStreak = 0;
                    } else plateStreak = 0;

                    // ★번호판 연속 2틱+ 확보되면 bbox 안정성 여부와 무관하게 번호판 비율이 우선한다
                    //   (운영자 요구: bbox가 노이즈로 '안정'처럼 보여도 번호판이 줄면 그게 진짜 신호).
                    boolean plateActive = cfg.platePriority && plateStreak >= 2 && t.plate != null && basePlateW > 0;
                    if (armed && plateActive) {
                        // ★wNorm은 프레임 폭 대비 0~1의 작은 값이라 절대 오차 대신 상대 오차(0.1%)로 부동소수점 경계를 흡수한다.
                        boolean receding = t.plate.wNorm <= basePlateW * cfg.plateShrinkRatio * 1.001f;
                        if (receding) recedeN++; else recedeN = Math.max(0, recedeN - 1);
                        if (recedeN >= cfg.recedeNThresh && now - lastLeadAlertTs > cfg.leadCooldownMs) {
                            event = Event.LEAD_DEPART;
                            why = "앞차 출발(멀어짐 plate " + basePlateW + "→" + t.plate.wNorm + ")";
                            lastLeadAlertTs = now; resetLeadTrack();
                        }
                    } else if (stable && t.stopped) {
                        if (cand.cy > baseCy) baseCy = cand.cy;
                        if (cand.h > baseH) baseH = cand.h;
                        baseCx = cand.cx;
                        recedeN = 0;
                    } else if (armed) {
                        boolean grew = cand.h > baseH * cfg.growThresh;
                        boolean receding = !grew && ((baseCy - cand.cy) > cfg.recedeCy || (baseH - cand.h) > baseH * cfg.recedeHFrac);
                        if (receding) recedeN++; else recedeN = Math.max(0, recedeN - 1);
                        if (recedeN >= cfg.recedeNThresh && now - lastLeadAlertTs > cfg.leadCooldownMs) {
                            event = Event.LEAD_DEPART;
                            why = "앞차 출발(멀어짐 bbox cy " + baseCy + "→" + cand.cy + " h " + baseH + "→" + cand.h + ")";
                            lastLeadAlertTs = now; resetLeadTrack();
                        }
                    }
                }
            }
        }

        // ── 신호 변경(적→녹) — 앞차 이벤트가 이미 발화했어도 상태는 갱신한다 ──
        boolean stopStarted = t.stopped && !prevStopped;
        if (stopStarted) { lastRed = false; greenBase = 1f; redGSamples.clear(); }
        if (t.stopped) {
            boolean nowRed = t.r > cfg.redRMin && t.r > t.g * cfg.redRatio;
            boolean nowGreen = lastRed && t.g > greenBase + cfg.greenDelta && t.g > t.r * cfg.greenRatio;
            if (event == Event.NONE && lastRed && nowGreen && now - lastTrafficAlertTs > cfg.trafficCooldownMs) {
                event = Event.LIGHT_GREEN; why = "신호 변경(적→녹) r=" + t.r + " g=" + t.g;
                lastTrafficAlertTs = now;
            }
            if (nowRed) {
                redGSamples.add(t.g);
                if (cfg.greenBaseMode == GreenBaseMode.FLOOR) { if (!lastRed || t.g < greenBase) greenBase = t.g; }
                else greenBase = median(redGSamples);
                lastRed = true;
            } else if (nowGreen) { lastRed = false; greenBase = 1f; redGSamples.clear(); }
            lastR = t.r; lastG = t.g;
        }
        prevStopped = t.stopped;

        return new Result(event, why.isEmpty() ? "NONE" : why);
    }

    // 트랙 있으면 연속성 매칭 후보(없으면 null=미검출 취급), 트랙 없으면 정차 중일 때만 차선 중앙에 가장 가까운 후보로 새 트랙 시작.
    private Box pickVehicleCandidate(Box[] vehicles, boolean stopped) {
        if (vehicles == null) return null;
        java.util.List<Box> cands = new java.util.ArrayList<>();
        for (Box b : vehicles) if (b != null && b.conf >= cfg.leadConfMin) cands.add(b);
        if (cands.isEmpty()) return null;

        if (leadArmedTs > 0) {
            Box best = null; float bestD = Float.MAX_VALUE;
            for (Box b : cands) {
                if (!matchTrack(b)) continue;
                float dx = (Float.isNaN(b.cx) || Float.isNaN(curCx)) ? 0f : Math.abs(b.cx - curCx);
                float d = dx + Math.abs(b.cy - curCy);
                if (d < bestD) { bestD = d; best = b; }
            }
            return best;   // 옆 차선 등 매칭 실패 후보뿐이면 null(미검출과 동일 — 발화 금지, 트랙 유지)
        }
        if (!stopped) return null;   // 트랙 없고 주행 중이면 무장 시작 안 함
        Box best = null; float bestScore = -Float.MAX_VALUE;
        for (Box b : cands) {
            // cx를 알면 ROI 중앙 하단(내 차선)에 가까울수록 우선, 모르면(cx=NaN) conf로 대체 선택.
            float score = !Float.isNaN(b.cx) ? -Math.abs(b.cx - 0.5f) : b.conf;
            if (score > bestScore) { bestScore = score; best = b; }
        }
        return best;
    }

    // cx가 둘 중 하나라도 NaN(모름)이면 게이트를 건너뛴다(true) — 가로위치 미로깅 구간에서도 cy·h만으로 계속 추적.
    private static boolean withinOrUnknown(float a, float b, float eps) {
        if (Float.isNaN(a) || Float.isNaN(b)) return true;
        return Math.abs(a - b) < eps;
    }

    // ★기준은 baseCx/Cy/H(무장 시점 고정값)가 아니라 curCx/Cy/H(직전 매칭 틱)로 본다 — 앞차가
    //   실제로 멀어지는 중엔 틱마다 위치가 계속 바뀌므로, 고정 기준과 비교하면 몇 틱 만에 '다른 차'로
    //   오판해 recede 평가 자체에 못 들어간다. 직전 틱 대비 연속성만 확인하면 점진적 이동은 계속 같은
    //   차로 인정하면서도, 옆 차선 차처럼 한 틱에 크게 튀는 후보는 여전히 걸러낸다.
    private boolean matchTrack(Box b) {
        if (!withinOrUnknown(b.cx, curCx, cfg.trackMatchCx)) return false;
        // ★실측 재튜닝(2026-09-11 재생 E009·E018 회귀) — 진단 틱은 1.5s 스로틀이라 진짜 출발 중엔 틱 간 cy가 0.25까지
        //   뛴다(E009: cy 0.61→0.36 h 0.46→0.34). 0.20 고정이면 진짜 출발을 '다른 차'로 오인해 발화 못 한다.
        //   물리적으로 일관된 멀어짐(cy↓ 그리고 h 비증가)일 때만 넓은 허용치를 쓴다 — 스왑의 전형(cy↓인데 h↑, E006·E031)은
        //   h 조건에서 떨어져 좁은 규칙으로 가고, 거기서 튕기면 모순 게이트가 다시 막는다.
        boolean consistentRecede = b.cy < curCy && (curH <= 0 || b.h <= curH * 1.02f);
        float cyTol = consistentRecede ? cfg.trackMatchCyReceding : cfg.trackMatchCy;
        if (Math.abs(b.cy - curCy) >= cyTol) return false;
        if (curH <= 0) return true;
        float ratio = b.h / curH;
        float hMin = consistentRecede ? cfg.trackMatchHRatioMinReceding : cfg.trackMatchHRatioMin;
        return ratio >= hMin && ratio <= cfg.trackMatchHRatioMax;
    }

    private void resetLeadTrack() {
        leadArmedTs = 0; leadSeenTs = 0; recedeN = 0;
        baseCx = -1; baseCy = -1; baseH = -1;
        curCx = -1; curCy = -1; curH = -1;
        basePlateW = -1; basePlateCx = -1; plateStreak = 0; lastPlateW = -1;
    }

    private static float median(java.util.List<Float> vals) {
        java.util.List<Float> copy = new java.util.ArrayList<>(vals);
        java.util.Collections.sort(copy);
        int n = copy.size();
        if (n == 0) return 1f;
        return n % 2 == 1 ? copy.get(n / 2) : (copy.get(n / 2 - 1) + copy.get(n / 2)) / 2f;
    }

    /** 현재 상태 한 줄 진단(운영자 실주행 로그용). */
    public String diag() {
        boolean armed = leadArmedTs > 0 && lastNowMs - leadArmedTs >= cfg.armWaitMs;
        return "무장" + (leadArmedTs == 0 ? "無" : armed ? "✓" : (lastNowMs - leadArmedTs) + "ms")
                + " baseCy=" + baseCy + " baseH=" + baseH + " baseCx=" + baseCx
                + " recedeN=" + recedeN + " plateStreak=" + plateStreak + " basePlateW=" + basePlateW + " lastPlateW=" + lastPlateW
                + " 적색이력=" + lastRed + " gBase=" + greenBase + " r=" + lastR + " g=" + lastG;
    }
}
