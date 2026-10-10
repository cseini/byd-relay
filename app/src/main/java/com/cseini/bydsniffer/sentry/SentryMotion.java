package com.cseini.bydsniffer.sentry;

import com.cseini.motiondetect.MotionDetector;
import com.cseini.motiondetect.Roi;

import java.util.List;

// 센트리 움직임 감지 — 기존 MotionDetector 재사용. 감지 영역을 안 그렸으면 네 칸을 각각 따로(칸마다 자기 배경·자기 비율) 본다(10-08: 합친 화면 한 장으로 보면 한 칸의 작은 물체는 못 잡고 한 칸의 조명 깜빡임은 크게 잡혔다).
// 지속 판정은 여기서 한다(SentrySustain: 최근 창에서 60% 이상 임계 이상). MotionDetector의 연속 지속 판정은 0으로 꺼 둔다.
// light_change_filter: ① 기존 camDetect 713/710 규칙 — 화면 전체 평균 밝기가 한 번에 12 이상 바뀌면 전부 배경 다시 잡고 1.5초, 감지 시작 직후 5초는 triggered=false.
//   ② 칸별(SentryLightGuard) — 한 칸의 밝기·움직임 면적이 한꺼번에 바뀌면(형광등 깜빡임) 그 칸만 배경 다시 잡고 1.5초.
public final class SentryMotion {
    private static final int LIGHT_JUMP = 12;
    private static final long LIGHT_QUIET_MS = 1500;
    private static final long START_QUIET_MS = 5000;
    private static final long LIGHT_LOG_GAP_MS = 10000;
    private static final float WEIGHT_INIT = 0.008f;   // 새 물체 가우시안 초기 weight(기본 0.05) — 지나가는 차가 1초 안에 배경으로 흡수되던 것을 막는다(10-09 실영상: 3%↑ 판정 3번→34번, 조명 영상은 0.3% 이하 유지)
    private static final float LEARNING_RATE = 0.005f;   // 배경 적응 속도(이전 0.008)
    private static final float HP_SIGMA = 4f;   // 조명 성분 제거 흐림 크기(96x72 기준 픽셀) — 실영상·합성 물체로 고름
    private static final int DS_W = 96, DS_H = 72;   // 칸(또는 영역) 하나당 판정 해상도 — 예전엔 합친 화면 전체를 64x48로 줄여 칸 하나가 32x24였다

    private final int w, h;
    private final SentrySettings settings;
    private final boolean lightChangeFilter;
    private final List<Roi> rois;
    private final SentryLightGuard[] guards;
    private final SentrySustain[] sustains;
    private final SentryNearMiss[] nearMiss;
    private final long[] quietUntil;
    private final int[] logOrder;      // 로그에 찍는 순서 — 영상에서 보이는 칸(좌상·우상·좌하·우하)마다 어느 영역(원본 칸)인지. 감지 영역을 직접 그렸으면 그린 순서 그대로
    private final boolean cellMode;
    private final Object hitLock = new Object();
    private final java.util.ArrayDeque<long[]> ring = new java.util.ArrayDeque<>();   // 최근 12초의 칸별 임계 이상 판정 {시각, 칸, 세기%} — 이벤트 시작 때 사전 녹화 구간 표시를 채우는 용도
    private final java.util.ArrayList<long[]> confirmed = new java.util.ArrayList<>();   // 지속 확정된 판정 — drainConfirmed로 가져간다
    private final int[] displayOf;     // 영역(원본 칸) → 영상에서 보이는 칸 번호(0~3), 직접 그린 영역이면 영역 번호
    private volatile MotionDetector md;
    private volatile int lastMaxPct;
    private volatile boolean closed;
    private int frameN;
    private boolean lastTriggered;
    private int prevMeanY = -1;
    private long lightQuietUntil;
    private long startTs = -1;
    private long lastLightLog;
    private volatile java.util.function.Consumer<String> logger;
    private static final long STAT_LOG_MS = 300000;   // 5분마다 감시 상태 한 줄 — 움직임이 안 잡힐 때 감지기가 살아 있는지·임계에 얼마나 못 미치는지 보려는 진단
    private long statSince;
    private float statPeak;
    private int statEval, statAbove, statLightReset;
    private final float[] statPeakRoi;
    private final int[] statAboveRoi, statLightRoi;

    public SentryMotion(int w, int h, SentrySettings s) {
        this(w, h, s, null);
    }

    // roiOverride가 있으면 그 영역(칸 4개 등)을 쓴다 — 재생 시험이 녹화 영상 아래의 시각·배터리 표시줄을 빼고 보려고
    public SentryMotion(int w, int h, SentrySettings s, List<Roi> roiOverride) {
        this.w = w;
        this.h = h;
        this.settings = s;
        this.lightChangeFilter = s.lightChangeFilter;
        this.rois = roiOverride != null ? roiOverride : s.detectRois();
        int n = rois.size();
        guards = new SentryLightGuard[n];
        sustains = new SentrySustain[n];
        nearMiss = new SentryNearMiss[n];
        quietUntil = new long[n];
        statPeakRoi = new float[n];
        statAboveRoi = new int[n];
        statLightRoi = new int[n];
        logOrder = new int[n];
        displayOf = new int[n];
        cellMode = n == 4 && (roiOverride != null || !s.hasUserRois());
        System.arraycopy(logOrderFor(cellMode, s.layoutMap, n), 0, logOrder, 0, n);
        for (int k = 0; k < n; k++) displayOf[logOrder[k]] = k;   // 칸 배치(layout_map)를 바꿨으면 영상에서 보이는 칸 번호로 말해야 운영자가 영상과 맞춰 볼 수 있다(10-09: 원본 우하 = 영상 좌상인데 로그는 "칸4"라 혼동)
        for (int i = 0; i < n; i++) {
            guards[i] = new SentryLightGuard();
            sustains[i] = new SentrySustain(s.sustainMs());
            nearMiss[i] = new SentryNearMiss();
        }
        md = newDetector();
    }

    public void setLogger(java.util.function.Consumer<String> l) { logger = l; }

    private MotionDetector newDetector() {
        MotionDetector d = new MotionDetector(w, h, DS_W, DS_H);
        d.setWeightInit(WEIGHT_INIT);   // setRois 앞에 — 만들어지는 영역마다 적용
        d.setRois(rois);
        d.setParams(LEARNING_RATE, settings.shadowFilter ? 24f : 16f, 0.9f);
        d.setIlluminationHighPass(settings.lightChangeFilter ? HP_SIGMA : 0f);   // 조명 변화 무시를 켰을 때만
        d.setThreshold(settings.minMotionPct(), 0L);   // 지속 판정은 SentrySustain이 한다
        return d;
    }

    // Y 평면을 64바이트 간격으로 샘플한 평균 밝기(0~255). 프레임이 Y 평면보다 짧으면 있는 만큼만 본다.
    static int meanLuma(byte[] nv21, int w, int h) {
        long sum = 0;
        int n = 0, len = Math.min(nv21.length, w * h);
        for (int k = 0; k < len; k += 64) {
            sum += (nv21[k] & 0xFF);
            n++;
        }
        return n > 0 ? (int) (sum / n) : 0;
    }

    // 어느 ROI든 triggered면 true
    public boolean onFrame(byte[] nv21, long tsMs) {
        if (closed) return false;
        // 호스트 튜닝(학습률 0.008·조명 12단계)이 3프레임 간격(skip=3) 기준이라 3프레임에 1장만 판정
        if (++frameN % 3 != 0) return lastTriggered;
        boolean quiet = false;
        if (lightChangeFilter) {
            if (startTs < 0) startTs = tsMs;
            int meanY = meanLuma(nv21, w, h);
            if (prevMeanY >= 0 && Math.abs(meanY - prevMeanY) >= LIGHT_JUMP) {
                synchronized (this) {
                    if (closed) return false;
                    md.close();
                    md = newDetector();
                }
                lastMaxPct = 0;
                resetPerRoi();
                statLightReset++;
                lightQuietUntil = tsMs + LIGHT_QUIET_MS;
                if (lastLightLog == 0 || tsMs - lastLightLog > LIGHT_LOG_GAP_MS) {
                    lastLightLog = tsMs;
                    java.util.function.Consumer<String> lg = logger;
                    if (lg != null) lg.accept("조명 변화(평균 밝기 " + prevMeanY + "→" + meanY + ") — 배경 다시 잡음");
                }
            }
            prevMeanY = meanY;
            quiet = tsMs - startTs < START_QUIET_MS || tsMs < lightQuietUntil;
        }
        MotionDetector.Result[] rs = md.onFrame(nv21, tsMs);
        float minPct = settings.minMotionPct();
        boolean trig = false;
        float max = 0f;
        for (int i = 0; i < rs.length && i < sustains.length; i++) {
            MotionDetector.Result r = rs[i];
            if (lightChangeFilter && guards[i].check(r.meanY, r.motionPct)) {
                md.resetRoi(i);
                guards[i].reset();
                sustains[i].reset();
                nearMiss[i].reset();
                quietUntil[i] = tsMs + LIGHT_QUIET_MS;
                statLightRoi[i]++;
                if (lastLightLog == 0 || tsMs - lastLightLog > LIGHT_LOG_GAP_MS) {
                    lastLightLog = tsMs;
                    java.util.function.Consumer<String> lg = logger;
                    if (lg != null) lg.accept(String.format(java.util.Locale.US, "%s 조명 변화(움직임 %.0f%%·평균 밝기 %d) — 그 칸 배경 다시 잡음", cellName(i), r.motionPct * 100f, r.meanY));
                }
                continue;
            }
            SentryNearMiss.Run nm = nearMiss[i].feed(tsMs, r.motionPct, r.cx, r.cy, r.bw, r.bh, r.meanY, minPct);
            if (nm != null) {
                java.util.function.Consumer<String> lg = logger;
                if (lg != null) lg.accept(nm.describe(cellName(i)));
            }
            if (r.motionPct > max) max = r.motionPct;
            if (r.motionPct > statPeakRoi[i]) statPeakRoi[i] = r.motionPct;
            boolean hit = r.motionPct >= minPct && tsMs >= quietUntil[i];
            if (r.motionPct >= minPct) statAboveRoi[i]++;
            int cellOut = cellMode ? displayOf[i] : -1;
            if (hit) recordRing(tsMs, cellOut, Math.round(r.motionPct * 100f));
            if (sustains[i].update(hit, tsMs)) {
                trig = true;
                synchronized (hitLock) { if (confirmed.size() < 400) confirmed.add(new long[]{tsMs, cellOut, Math.round(r.motionPct * 100f)}); }
            }
        }
        lastMaxPct = Math.round(max * 100);
        lastTriggered = trig && !quiet;
        if (statSince == 0) statSince = tsMs;
        statEval++;
        if (max > statPeak) statPeak = max;
        if (max >= minPct) statAbove++;
        if (tsMs - statSince >= STAT_LOG_MS) {
            java.util.function.Consumer<String> lg = logger;
            if (lg != null) lg.accept(String.format(java.util.Locale.US, "움직임 감시 5분 — 최고 %.2f%%(임계 %.2f%%) · 판정 %d장 · 임계 넘은 %d장 · 조명 초기화 %d회 · %s 최고 %s%% · 넘음 %s · 조명 %s",
                    statPeak * 100f, minPct * 100f, statEval, statAbove, statLightReset + sum(statLightRoi), cellLegend(), peaks(ordered(statPeakRoi)), java.util.Arrays.toString(ordered(statAboveRoi)), java.util.Arrays.toString(ordered(statLightRoi))));
            statSince = tsMs;
            statPeak = 0f;
            statEval = statAbove = statLightReset = 0;
            java.util.Arrays.fill(statPeakRoi, 0f);
            java.util.Arrays.fill(statAboveRoi, 0);
            java.util.Arrays.fill(statLightRoi, 0);
        }
        return lastTriggered;
    }

    // 로그 순서: 영상의 k번째 칸(좌상·우상·좌하·우하)에 놓인 영역(원본 칸) 번호. 칸 모드가 아니거나 배치가 이상하면 그대로(0..n-1)
    static int[] logOrderFor(boolean cellMode, int[] layoutMap, int n) {
        int[] o = new int[n];
        for (int i = 0; i < n; i++) o[i] = i;
        if (cellMode && n == 4 && validMap(layoutMap)) System.arraycopy(layoutMap, 0, o, 0, 4);
        return o;
    }

    static boolean validMap(int[] m) {
        if (m == null || m.length != 4) return false;
        boolean[] seen = new boolean[4];
        for (int v : m) { if (v < 0 || v > 3 || seen[v]) return false; seen[v] = true; }
        return true;
    }

    private static final String[] POS = {"좌상", "우상", "좌하", "우하"};

    // 영상에서 보이는 칸 이름(좌상·우상·좌하·우하). 직접 그린 영역이면 "영역N"
    private String cellName(int roi) {
        return logOrder.length == 4 && roi < 4 && rois.size() == 4 && !settings.hasUserRois() ? "영상 " + POS[displayOf[roi]] + "칸" : "영역" + (roi + 1);
    }

    private String cellLegend() {
        return rois.size() == 4 && !settings.hasUserRois() ? "영상 칸별[좌상,우상,좌하,우하]" : "영역별";
    }

    private float[] ordered(float[] a) { float[] o = new float[a.length]; for (int k = 0; k < a.length; k++) o[k] = a[logOrder[k]]; return o; }
    private int[] ordered(int[] a) { int[] o = new int[a.length]; for (int k = 0; k < a.length; k++) o[k] = a[logOrder[k]]; return o; }

    private void recordRing(long ts, int cell, int pct) {
        synchronized (hitLock) {
            ring.addLast(new long[]{ts, cell, pct});
            while (!ring.isEmpty() && ts - ring.peekFirst()[0] > 12000L) ring.pollFirst();
        }
    }

    // 지속 확정된 판정을 비우며 가져온다 — {시각(elapsedRealtime ms), 칸(영상 0~3, 직접 그린 영역은 -1), 세기%}
    public long[][] drainConfirmed() {
        synchronized (hitLock) {
            if (confirmed.isEmpty()) return new long[0][];
            long[][] out = confirmed.toArray(new long[0][]);
            confirmed.clear();
            return out;
        }
    }

    // sinceTs 이후 임계 이상 판정 중 같은 칸에서 3번 이상 나온 칸의 것만(잡음 한두 번은 제외)
    public long[][] recentHits(long sinceTs) {
        synchronized (hitLock) {
            java.util.Map<Long, Integer> cnt = new java.util.HashMap<>();
            for (long[] h : ring) if (h[0] >= sinceTs) { Long c = h[1]; Integer v = cnt.get(c); cnt.put(c, v == null ? 1 : v + 1); }
            java.util.ArrayList<long[]> out = new java.util.ArrayList<>();
            for (long[] h : ring) if (h[0] >= sinceTs && cnt.get(h[1]) >= 3) out.add(h);
            return out.toArray(new long[0][]);
        }
    }

    private void resetPerRoi() {
        for (int i = 0; i < guards.length; i++) {
            guards[i].reset();
            sustains[i].reset();
        }
    }

    private static int sum(int[] a) {
        int t = 0;
        for (int v : a) t += v;
        return t;
    }

    private static String peaks(float[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) sb.append(i > 0 ? ", " : "").append(String.format(java.util.Locale.US, "%.1f", a[i] * 100f));
        return sb.append("]").toString();
    }

    public int lastMaxPct() { return lastMaxPct; }

    public synchronized void close() {
        closed = true;
        md.close();
    }
}
