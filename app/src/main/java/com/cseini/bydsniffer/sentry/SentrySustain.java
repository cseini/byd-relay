package com.cseini.bydsniffer.sentry;

import java.util.ArrayDeque;

// 움직임 확정 — "끊김 없이 N초" 대신 "최근 N초 창에서 60% 이상이 임계 이상"이면 확정한다.
// 지나가는 차가 잠깐 가려지거나 비율이 임계 근처에서 흔들려도 놓치지 않고, 창이 거의 차야(80%) 하므로 순간 잡음은 거른다.
final class SentrySustain {
    static final float MIN_FILL = 0.8f;    // 창 길이의 80% 이상 관찰돼야
    static final float MIN_HIT_RATIO = 0.6f;   // 그중 60% 이상이 임계 이상
    static final int MIN_SAMPLES = 3;

    private final long windowMs;
    private final ArrayDeque<long[]> q = new ArrayDeque<>();   // {시각, 임계 이상이면 1}
    private int hits;

    SentrySustain(long windowMs) {
        this.windowMs = Math.max(500L, windowMs);
    }

    boolean update(boolean hit, long tsMs) {
        q.addLast(new long[]{tsMs, hit ? 1 : 0});
        if (hit) hits++;
        while (!q.isEmpty() && tsMs - q.peekFirst()[0] > windowMs) {
            if (q.pollFirst()[1] == 1) hits--;
        }
        int n = q.size();
        long span = tsMs - q.peekFirst()[0];
        return hit && n >= MIN_SAMPLES && span >= windowMs * MIN_FILL && hits >= n * MIN_HIT_RATIO;
    }

    void reset() {
        q.clear();
        hits = 0;
    }
}
