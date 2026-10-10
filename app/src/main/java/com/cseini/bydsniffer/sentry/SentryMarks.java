package com.cseini.bydsniffer.sentry;

import java.util.ArrayList;
import java.util.List;

// 녹화 시간축 표시 — 이벤트 안에서 "움직임이 있었던 순간"을 모아 둔다(운영자 10-09 "녹화 스트림 라인에 표시하고 눌러서 그 시점 영상을 보게").
// 같은 칸에서 3초 안에 이어지는 감지는 하나로 합쳐 시작 시각·지속·최고 세기만 남긴다. 순수 계산이라 JVM에서 시험한다.
final class SentryMarks {
    static final long MERGE_MS = 3000L;
    static final int MAX_MARKS = 100;

    private long zeroElapsed;
    private long offsetMs;
    private final List<SentryIndex.Mark> list = new ArrayList<>();
    private final long[] lastHit = new long[5];   // 칸 -1(영역)·0~3 → 마지막 감지 시각(영상 기준 ms), 없으면 -1
    private final SentryIndex.Mark[] open = new SentryIndex.Mark[5];
    private final int[] cellKind = new int[5];   // 객체 판정으로 알게 된 칸의 종류(0=모름) — 이후 그 칸의 표시와 이미 만든 표시에 적용

    // zeroElapsed = 이벤트 시작 순간의 elapsedRealtime, offsetMs = 영상 0초가 그보다 얼마 앞인지(움직임 이벤트는 사전 녹화 길이, 연속은 0)
    synchronized void reset(long zeroElapsed, long offsetMs) {
        this.zeroElapsed = zeroElapsed;
        this.offsetMs = offsetMs;
        list.clear();
        for (int i = 0; i < 5; i++) { lastHit[i] = -1; open[i] = null; cellKind[i] = 0; }
    }

    // cell = 영상에서 보이는 칸 0~3(좌상·우상·좌하·우하), 감지 영역을 직접 그렸으면 -1. kind 0=움직임 1=차량 2=사람
    // 새 표시를 만들었으면 true(이어진 감지를 합쳤거나 한도라 버렸으면 false)
    synchronized boolean add(long elapsedMs, int cell, int pct, int kind) {
        long t = Math.max(0L, elapsedMs - zeroElapsed + offsetMs);
        int c = (cell < 0 || cell > 3) ? 0 : cell + 1;
        if (cellKind[c] > 0) kind = cellKind[c];
        SentryIndex.Mark cur = open[c];
        if (cur != null && cur.kind == kind && t - lastHit[c] < MERGE_MS && t >= cur.tMs) {
            if (pct > cur.pct) cur.pct = pct;
            cur.durMs = t - cur.tMs;
            lastHit[c] = t;
            return false;
        }
        if (list.size() >= MAX_MARKS) return false;
        SentryIndex.Mark m = new SentryIndex.Mark(t, kind, (cell < 0 || cell > 3) ? -1 : cell, pct);
        list.add(m);
        open[c] = m;
        lastHit[c] = t;
        return true;
    }

    // 객체 판정 결과(사람 2·차량 1)를 그 칸에 적용 — 이미 만든 표시의 종류도 바꾼다. 사람이 이미 정해진 칸은 차량으로 덮지 않는다.
    synchronized void setCellKind(int cell, int kind) {
        int c = (cell < 0 || cell > 3) ? 0 : cell + 1;
        if (kind <= 0 || (cellKind[c] == 2 && kind != 2) || (cellKind[c] == 1 && kind == 3) || (cellKind[c] == 2 && kind == 3)) return;   // 조명 의심(3)은 사람·차량 판정을 덮지 않는다
        cellKind[c] = kind;
        for (SentryIndex.Mark m : list) if (((m.cell < 0 || m.cell > 3) ? 0 : m.cell + 1) == c) m.kind = kind;
    }

    synchronized List<SentryIndex.Mark> snapshot() {
        List<SentryIndex.Mark> out = new ArrayList<>(list.size());
        for (SentryIndex.Mark m : list) out.add(new SentryIndex.Mark(m.tMs, m.kind, m.cell, m.pct, m.durMs));
        java.util.Collections.sort(out, (a, b) -> Long.compare(a.tMs, b.tMs));
        return out;
    }
}
