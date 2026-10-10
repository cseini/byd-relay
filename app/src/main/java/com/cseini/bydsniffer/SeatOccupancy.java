package com.cseini.bydsniffer;

/**
 * ★845 돌핀 뒷좌석 3자리 착좌 판정(운영자판 시험용, 운영자 10-08 "ㄱㄱ").
 * 근거: 안전벨트 장치 이벤트 0x4FA00018(왼쪽)·0x4FA0001A(가운데)·0x4FA0001C(오른쪽), 값 1=앉음+벨트 안 맴 · 2=경고 없음.
 * 자리는 featureId 숫자로 매핑한다 — 프레임워크 이름표(idToName)는 가운데·오른쪽이 바뀌어 있다는 외부 실측 보고 때문.
 * 경고 1→2는 "벨트 맴"과 "일어남"이 같은 값이라, 체결값 의미가 확정되기 전엔 보수적으로 '아마 있음'으로 둔다(미탐=방치가 더 위험).
 */
final class SeatOccupancy {
    static final int L = 0, C = 1, R = 2;
    static final String[] NAME = {"왼쪽", "가운데", "오른쪽"};
    static final int V_EMPTY = 0, V_UNBELTED = 1, V_SURE = 2, V_MAYBE = 3;
    static final String[] VNAME = {"빈자리", "있음(벨트 안 맴)", "있음(확실)", "아마 있음"};

    static int seatOf(int featureId) {
        switch (featureId) {
            case 1335885848: return L;
            case 1335885850: return C;
            case 1335885852: return R;
            default: return -1;
        }
    }

    interface Sink { void log(String msg); }

    private final Sink sink;
    private final int[] remind = {-1, -1, -1};
    private final int[] verdict = {V_EMPTY, V_EMPTY, V_EMPTY};
    private final String[] reason = {"아직 이벤트 없음", "아직 이벤트 없음", "아직 이벤트 없음"};
    private final java.util.ArrayDeque<String> events = new java.util.ArrayDeque<>();
    private String beltRaw = "";

    SeatOccupancy(Sink sink) { this.sink = sink; }

    synchronized void onRemind(int seat, int val, String hhmmss) {
        if (seat < 0) return;
        int prev = remind[seat];
        remind[seat] = val;
        addEvent(hhmmss + " " + NAME[seat] + " 경고 " + (prev < 0 ? "" : prev + "→") + val);
        int old = verdict[seat];
        if (val == 1) {
            set(seat, V_UNBELTED, "경고 1 — 앉았는데 벨트 안 맴 (" + hhmmss + ")");
        } else if (val == 2) {
            if (prev == 1) set(seat, V_MAYBE, "경고 1→2 — 벨트 맴 또는 일어남(체결값으로 확정 예정) (" + hhmmss + ")");
            else set(seat, V_EMPTY, "경고 없음 — 빈자리 또는 벨트 맨 채 앉음(구분 불가)");
        } else {
            set(seat, verdict[seat], "알 수 없는 경고값 " + val + " (" + hhmmss + ")");
        }
        if (verdict[seat] != old) sink.log(NAME[seat] + " " + VNAME[old] + "→" + VNAME[verdict[seat]] + " · " + reason[seat]);
    }

    synchronized void setBeltRaw(String raw) {
        if (raw == null || raw.equals(beltRaw)) return;
        String hh = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(new java.util.Date());
        if (!beltRaw.isEmpty()) addEvent(hh + " 체결 원값 " + raw);
        beltRaw = raw;
        sink.log("체결 원값 " + raw);
    }

    /** 화면용: 자리별 판정·근거·체결 원값·최근 이벤트 10개. */
    synchronized Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.verdict = verdict.clone();
        s.reason = reason.clone();
        s.remind = remind.clone();
        s.beltRaw = beltRaw;
        s.events = new java.util.ArrayList<>(events);
        return s;
    }

    static final class Snapshot {
        int[] verdict; String[] reason; int[] remind; String beltRaw; java.util.List<String> events;
    }

    private void set(int seat, int v, String why) { verdict[seat] = v; reason[seat] = why; }

    private void addEvent(String e) {
        events.addFirst(e);
        while (events.size() > 10) events.removeLast();
    }
}
