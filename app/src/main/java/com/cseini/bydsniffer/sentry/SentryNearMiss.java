package com.cseini.bydsniffer.sentry;

import java.util.ArrayDeque;
import java.util.Locale;

// "임계 바로 아래까지 올라갔다가 이벤트가 안 된" 움직임의 특징 기록(운영자 10-10 "깜빡임은 아예 감지 안 되게" — 실제 깜빡임 자료가 없어 규칙을 추측하지 않고 자료를 모은다).
// 한 칸(영역)의 판정을 프레임마다 받아, near 이상으로 올라간 구간(구간 사이 2번까지 끊겨도 이어 봄)이 끝났을 때 임계(minPct)에 한 번도 못 미쳤고 3장 이상이면 한 줄로 설명한다.
// 깜빡임이면 "제자리·넓게·밝기가 오르내림", 지나가는 물체면 "무게중심이 움직임"이어야 구분 규칙을 세울 수 있다. 순수 계산이라 JVM에서 시험한다.
final class SentryNearMiss {
    static final int END_GAP = 3;          // 연속 이 장수만큼 near 미만이면 구간 끝
    static final int MIN_FRAMES = 3;
    static final int HOURLY_BUDGET = 40;   // 한 시간에 최대 이만큼만 기록(로그 폭주 방지)
    static final long HOUR_MS = 3_600_000L;

    static float nearPct(float minPct) { return Math.max(0.015f, 0.5f * minPct); }

    static final class Run {
        final float peak, minPct, areaAvg, travel, path, bw, bh;
        final int frames, meanY0, meanY1, meanYMin, meanYMax;
        final long durMs;

        Run(float peak, float minPct, float areaAvg, float travel, float path, float bw, float bh, int frames, long durMs, int y0, int y1, int ymin, int ymax) {
            this.peak = peak; this.minPct = minPct; this.areaAvg = areaAvg; this.travel = travel; this.path = path; this.bw = bw; this.bh = bh;
            this.frames = frames; this.durMs = durMs; this.meanY0 = y0; this.meanY1 = y1; this.meanYMin = ymin; this.meanYMax = ymax;
        }

        String describe(String cell) {
            return String.format(Locale.US, "근접 미달 %s — 최고 %.1f%%(임계 %.1f%%) · %d장 %.1f초 · 무게중심 이동 %.2f(경로 %.2f) · 평균 면적 %.1f%% · 전경 폭×높이 %.2f×%.2f · 밝기 %d→%d(범위 %d~%d)",
                    cell, peak * 100f, minPct * 100f, frames, durMs / 1000f, travel, path, areaAvg * 100f, bw, bh, meanY0, meanY1, meanYMin, meanYMax);
        }
    }

    private final ArrayDeque<Long> sent = new ArrayDeque<>();
    private boolean active;
    private int n, below;
    private long firstTs, lastTs;
    private float peak, sumPct, sumBw, sumBh;
    private boolean crossed;
    private int cxN;
    private float minX, maxX, minY, maxY, prevX, prevY, path;
    private int meanY0, meanY1, meanYMin, meanYMax;

    // 프레임 하나. 구간이 끝나 기록할 것이 있으면 그 설명을, 아니면 null
    Run feed(long ts, float pct, float cx, float cy, float bw, float bh, int meanY, float minPct) {
        float near = nearPct(minPct);
        if (pct >= near) {
            if (!active) start(ts, meanY);
            below = 0;
            n++; lastTs = ts; sumPct += pct; if (pct > peak) peak = pct;
            if (pct >= minPct) crossed = true;
            if (bw >= 0f) { sumBw += bw; sumBh += bh; }
            if (cx >= 0f) {
                if (cxN == 0) { minX = maxX = prevX = cx; minY = maxY = prevY = cy; }
                else {
                    minX = Math.min(minX, cx); maxX = Math.max(maxX, cx); minY = Math.min(minY, cy); maxY = Math.max(maxY, cy);
                    path += (float) Math.hypot(cx - prevX, cy - prevY);
                    prevX = cx; prevY = cy;
                }
                cxN++;
            }
            meanY1 = meanY; meanYMin = Math.min(meanYMin, meanY); meanYMax = Math.max(meanYMax, meanY);
            return null;
        }
        if (!active) return null;
        if (++below < END_GAP) return null;
        return finish(ts, minPct);
    }

    private void start(long ts, int meanY) {
        active = true; n = 0; below = 0; firstTs = ts; lastTs = ts; peak = 0f; sumPct = 0f; sumBw = sumBh = 0f; crossed = false;
        cxN = 0; path = 0f; meanY0 = meanY1 = meanYMin = meanYMax = meanY;
    }

    private Run finish(long ts, float minPct) {
        active = false;
        if (crossed || n < MIN_FRAMES) return null;
        sent.addLast(ts);
        while (!sent.isEmpty() && ts - sent.peekFirst() > HOUR_MS) sent.pollFirst();
        if (sent.size() > HOURLY_BUDGET) { sent.pollLast(); return null; }
        float travel = cxN >= 2 ? (float) Math.hypot(maxX - minX, maxY - minY) : 0f;
        int c = Math.max(1, cxN);
        return new Run(peak, minPct, sumPct / n, travel, path, sumBw / c, sumBh / c, n, lastTs - firstTs, meanY0, meanY1, meanYMin, meanYMax);
    }

    void reset() { active = false; n = below = 0; }
}
