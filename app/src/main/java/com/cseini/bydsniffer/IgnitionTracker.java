package com.cseini.bydsniffer;

/**
 * 시동(점화) 상태 판별기 — android.* 의존 없는 순수 Java.
 *
 * 배경: 릴레이는 AC_POWER_STATE 이벤트 하나를 "시동"과 "공조 전원" 양쪽에 써서,
 * 리모컨으로 공조만 꺼도 시동 꺼짐으로 오판(무탑승 마무리 오발화)한다.
 * 진짜 시동값은 bodywork.getPowerLevel()(2=ON, 0=OFF)이지만, 시동 OFF 순간
 * 릴레이 프로세스가 같이 죽어 폴링만으론 OFF 엣지를 못 본다.
 * → 하이브리드: AC_POWER_STATE 이벤트=트리거, getPowerLevel=판별기, 시동상태 영속화.
 *
 * 영속 상태(SharedPreferences 등)는 호출측이 담당한다 — 이 클래스는 Persisted를
 * 주입받아 판정만 수행하고, 다시 snapshot()으로 뽑아 저장하게 한다.
 */
public final class IgnitionTracker {

    /** getPowerLevel 읽기 실패(예외 등) */
    public static final int NA = Integer.MIN_VALUE;

    private static final long BOARD_WINDOW_MS = 120_000L;       // ON 직전 도어이벤트 유효창(2분)
    private static final long STALE_MS = 10 * 60 * 1000L;       // 부팅 시 "묵은 ON 상태" 판단 기준(10분)
    private static final int UNKNOWN_AC = -1;                   // AC_POWER_STATE 이전 값 모름

    public enum Edge { NONE, ON, OFF }

    /** 영속 상태 — SharedPreferences는 호출측이 담당, 이 클래스는 값만 주고받는다. */
    public static final class Persisted {
        public int lastIgn = -1;      // -1=모름, 0=OFF, 1=ON
        public long lastIgnTs = 0;
        public boolean sawPwr2 = false;
        public boolean boarded = false;
        public long lastSeenTs = 0;   // 마지막 "관측"(엣지 무관, NA 포함) 시각 — 릴레이 생존 증거
    }

    private int ign;
    private long lastIgnTs;
    private boolean sawPwr2;
    private boolean boarded;
    private long lastSeenTs;

    private int lastAcPower = UNKNOWN_AC;  // AC_POWER_STATE 이전 값(런타임 전용, 비영속)
    private long lastDoorTs;               // 마지막 도어 이벤트 시각(런타임 전용, 비영속)
    private int zeroStreak = 0;            // 폴링에서 pwrLevel==0 연속 관측 횟수
    private int darkStreak = 0;            // sawPwr2 전(SDK 미준비) 화면 꺼짐 연속 관측 — D5 백스톱(재검증 #3)
    private static final int DARK_OFF_N = 3;   // P기어 2s 폴 기준 ~6초

    public IgnitionTracker(Persisted p, long nowMs) {
        this.ign = p.lastIgn;
        this.lastIgnTs = p.lastIgnTs;
        this.sawPwr2 = p.sawPwr2;
        this.boarded = p.boarded;
        this.lastSeenTs = p.lastSeenTs;
        // 도어이벤트를 한 번도 못 받은 상태에서 곧바로 ON 엣지가 나도 boarded=true로
        // 잘못 판정되지 않도록, 충분히 과거로 초기화해둔다.
        this.lastDoorTs = nowMs - 24 * 60 * 60 * 1000L;
    }

    public Persisted snapshot() {
        Persisted p = new Persisted();
        p.lastIgn = ign;
        p.lastIgnTs = lastIgnTs;
        p.sawPwr2 = sawPwr2;
        p.boarded = boarded;
        p.lastSeenTs = lastSeenTs;
        return p;
    }

    public int ign() {
        return ign;
    }

    public boolean boarded() {
        return boarded;
    }

    public void onDoorEvent(long nowMs) {
        boarded = true;
        lastDoorTs = nowMs;
    }

    /**
     * AC_POWER_STATE 이벤트. pwrLevel = 이벤트 직후 동기로 읽은 getPowerLevel(2/0/NA).
     */
    public Edge onAcPowerEvent(int acPower, int pwrLevel, long nowMs) {
        lastSeenTs = nowMs;  // 엣지 유무·NA 무관 — 이 호출 자체가 릴레이 생존 증거
        if (pwrLevel == 2) {
            sawPwr2 = true;
        }

        int prevAc = lastAcPower;
        lastAcPower = acPower;

        if (prevAc == UNKNOWN_AC) {
            return Edge.NONE;  // 최초 이벤트 — 이전 값을 몰라 전이 판단 불가
        }
        if (prevAc == acPower) {
            return Edge.NONE;  // ALLSTATUS 재방송 등 동일값 중복 이벤트
        }
        if (prevAc == 1 && acPower == 0) {
            if (pwrLevel == 0) {
                return applyOff(nowMs);  // 게터가 명시적으로 OFF를 확인함
            }
            return Edge.NONE;  // pwr==2(패널 공조만 끔) 또는 NA(게터 삐끗) — 시동 유지, 폴링 백스톱에 맡김
        }
        if (prevAc == 0 && acPower == 1) {
            if (pwrLevel == 2) {
                return applyOn(nowMs);
            }
            return Edge.NONE;  // 보류 — 폴링 확인 대기(프리셋 오발동 방지)
        }
        return Edge.NONE;
    }

    /**
     * 주기 폴링(P기어 2s / 그외 500ms). screenOn=D5 화면 프록시(D3는 false 넘김).
     */
    public Edge onPowerLevelPoll(int pwrLevel, boolean screenOn, long nowMs) {
        return onPowerLevelPoll(pwrLevel, screenOn, false, nowMs);
    }

    /**
     * parked = 직전에 읽은 기어 P·정차(속도 0·주행 아님). ★10-11 FB2393: 씨라7은 시동을 꺼도 헤드유닛이 다시 부팅되며 화면이 켜져 있어,
     * 화면 프록시가 꺼진 차를 시동 ON으로 뒤집었다(센트리가 '시동 켜짐'으로 쉼). P·정차면 화면 프록시를 쓰지 않고 실제로 읽은 0을 꺼짐 쪽으로 센다.
     * 주행 중 재시작(기어 D)은 그대로 화면 프록시로 ON.
     */
    public Edge onPowerLevelPoll(int pwrLevel, boolean screenOn, boolean parked, long nowMs) {
        lastSeenTs = nowMs;  // 엣지 유무·NA 무관 — 이 호출 자체가 릴레이 생존 증거
        if (pwrLevel == NA) {
            if (screenOn) darkStreak = 0;
            if (!sawPwr2 && screenOn && !parked) {
                return applyOn(nowMs);  // SDK 미준비(NA) 구간의 D5 화면 프록시
            }
            return Edge.NONE;  // 유지
        }
        if (pwrLevel == 2) {
            sawPwr2 = true;
            zeroStreak = 0;
            return applyOn(nowMs);
        }
        if (pwrLevel == 0) {
            if (!sawPwr2) {
                // SDK 미준비로 인한 거짓 0 가능성 — D5는 화면 프록시로 보정, D3는 판정 보류.
                // ★재검증 #3: 화면 프록시로 ON 확정된 뒤 SDK가 끝내 2를 안 주고 0만 주면 OFF를 영영 못 잡던 경로 —
                //   화면 꺼짐이 연속 DARK_OFF_N회면 OFF(옛 D5 '화면=시동' 동작의 디바운스판). D3(screenOn 항상 false)는
                //   ign이 -1이라 applyOff가 NONE을 돌려 영향 없음.
                if (screenOn && !parked) {
                    darkStreak = 0;
                    return applyOn(nowMs);
                }
                darkStreak++;   // 화면 꺼짐, 또는 P·정차에서 실제로 읽은 0
                if (darkStreak >= DARK_OFF_N && ign == 1) { darkStreak = 0; return applyOff(nowMs); }
                return Edge.NONE;
            }
            darkStreak = 0;
            zeroStreak++;
            if (zeroStreak >= 2) {
                return applyOff(nowMs);
            }
            return Edge.NONE;  // 1회는 과도 상태로 보고 유지
        }
        return Edge.NONE;  // 기타 값 — 유지
    }

    /**
     * 부팅(릴레이 프로세스 시작) 시 첫 관측. persisted와 대조해 놓친 엣지/재시작을 구분한다.
     */
    public Edge onBoot(int pwrLevel, long nowMs) {
        boolean stale = (nowMs - lastSeenTs) >= STALE_MS;  // "마지막 엣지"가 아니라 "마지막 관측" 기준 — 잦은 재시작에 안 흔들림
        lastSeenTs = nowMs;  // 엣지 유무·NA 무관 — 이 호출 자체가 릴레이 생존 증거
        if (pwrLevel == NA) {
            return Edge.NONE;  // 상태 유지
        }
        if (pwrLevel == 2) {
            sawPwr2 = true;
            if (ign == 1 && !stale) {
                return Edge.NONE;  // 릴레이 재시작 — 시동은 계속 ON
            }
            // ign != 1 이거나, ign==1인데 묵은 상태(OFF→ON이 이미 다 지나감) → 새 ON 엣지
            boarded = (nowMs - lastDoorTs) < BOARD_WINDOW_MS;
            ign = 1;
            lastIgnTs = nowMs;
            zeroStreak = 0;
            return Edge.ON;
        }
        if (pwrLevel == 0) {
            return applyOff(nowMs);  // ign==1이면 놓친 엣지→OFF(boarded는 persisted 값 그대로), 이미 0이면 변화 없음
        }
        return Edge.NONE;
    }

    private Edge applyOn(long nowMs) {
        boolean wasOff = (ign != 1);
        if (wasOff) {
            boarded = (nowMs - lastDoorTs) < BOARD_WINDOW_MS;
        }
        ign = 1;
        zeroStreak = 0;
        if (!wasOff) {
            return Edge.NONE;  // 이미 ON — lastIgnTs는 실제 엣지 때만 갱신
        }
        lastIgnTs = nowMs;
        return Edge.ON;
    }

    private Edge applyOff(long nowMs) {
        boolean wasOn = (ign == 1);
        ign = 0;
        zeroStreak = 0;
        if (!wasOn) {
            return Edge.NONE;  // 이미 OFF(또는 모름→OFF 확정) — lastIgnTs는 실제 엣지 때만 갱신
        }
        lastIgnTs = nowMs;
        return Edge.OFF;
    }
}
