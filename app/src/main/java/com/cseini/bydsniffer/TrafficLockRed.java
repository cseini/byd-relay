package com.cseini.bydsniffer;

import java.util.ArrayList;
import java.util.List;

/**
 * 신호등 "새 방식(lockred)" 판정기 — android/motiondetect 의존 없는 순수 Java(오프라인 하니스 재사용 목적).
 *
 * 배경(2026-09-28 운영자 승인, 오프라인 실험 lockred.py "1판"): 정차 첫 3장에서 보정본 6종(원본/밝게/어둡게/CLAHE/채도/노출)
 * 각각 빨강 덩어리를 뽑아 3장 연속 같은 자리에 잡히는 것만 "고정 후보"로 채택 → 합계 점수가 가장 높은 보정본 1개를 그 정차 구간
 * 내내 쓴다. 이후 프레임은 고정 자리 red 잔존율이 과반 이하로 떨어지고(+ 그 우측에 원래 없던 초록이 새로 생기면) 발화.
 * 입력은 신호등 ROI(r4b)를 이미 잘라낸 RGB 크롭(호출측이 nv21CropToRgb로 추출, YuvNative는 package-private이라 미사용) — 다운스케일 없음(원 알고리즘
 * 임계가 절대 픽셀 단위라 축소하면 원거리 램프가 area 하한에 걸려 재현율이 무너진다). 무거운 6종 검색은 lockN(3)장에서만,
 * 이후는 고정 자리 주변 작은 윈도만 본다.
 */
public final class TrafficLockRed {

    // ── 설정 ─────────────────────────────────────────────────────────
    public static final class Config {
        public int lockN = 3;                       // 고정에 쓰는 정차 초반 프레임 수(lockred.py LOCK_N)
        public int areaMin = 8, areaMax = 1500;      // 덩어리 면적(px) 상하한
        public float aspectMin = 0.45f, aspectMax = 2.2f;
        public float fillMin = 0.35f;                // 채움비(area / bbox area)
        public float tolK = 2f, tolMin = 6f;         // 매칭 허용거리 = max(tolK*r, tolMin)
        public float lockMin = 0.02f;                // 보정본 선택 최소 합계점수
        public float lockBlobMin = 0.05f;            // ★645 고정 1개당 최소 점수 — 첫 실주행 오알림(0.004짜리 약한 고정이 사라져 발화) 차단
        public float redOffFrac = 0.35f;             // 잔존율 이 미만이면 "꺼짐" 후보
        public float dxMinK = 0.8f, dxMaxK = 9f, dxMaxC = 6f;   // 새 초록 탐색창(가로, 고정 자리 우측)
        public float dyMaxK = 1.8f, dyMaxC = 3f;                // 새 초록 탐색창(세로)
        public long persistMs = 1200;                // ★실시간 환산 — 발화조건이 이만큼 연속 유지돼야 실제 발화
        // ★1순위 개선 — 브레이크등 배제(좌우 쌍 + 차량 bbox)
        public boolean pairExclude = true;
        public float pairDyMaxR = 1.0f, pairDxMinR = 3f, pairDxMaxR = 14f, pairRRatioMax = 1.5f;
        public boolean vehExclude = true;
    }

    // ── 공개 타입 ────────────────────────────────────────────────────
    public static final class Box { public float x, y, w, h; public Box() {} public Box(float x, float y, float w, float h) { this.x = x; this.y = y; this.w = w; this.h = h; } }

    static final class Blob { float cx, cy, r; int a; float score, chroma, contrast; }

    public static final class Lock { public float cx, cy, r; public int a; public float lockScore; }

    public static final class LockOutcome {
        public boolean locked;
        public String variant;
        public float totalScore;
        public Lock[] locks;
        public String diag;   // 로그용 한 줄
    }

    public static final class TrackOutcome {
        public boolean fired;
        public boolean candidate;   // 이번 틱 조건은 만족(발화는 지속시간 미달로 보류)
        public float redRetainMin;  // 고정 자리 중 가장 낮은 잔존율(진단용)
        public String greenAt;      // 새 초록 좌표(진단용, 없으면 null)
        public String diag;
    }

    private final Config cfg;
    public TrafficLockRed(Config cfg) { this.cfg = cfg != null ? cfg : new Config(); }

    // ── 세션 상태(정차 1구간) ───────────────────────────────────────
    private int lockFrameN = 0;
    private final List<byte[]> lockFrames = new ArrayList<>();
    private int lockW = -1, lockH = -1;
    private List<Box> lockVehBoxes;   // 첫 프레임 차량 bbox만 사용
    private boolean locked = false;
    private String pickedVariant = null;
    private Lock[] locks = null;
    private Blob[] baseGreen = null;  // 고정 자리별 새-초록 탐색창 안의 "원래 있던" 초록(윈도별로 이어붙임, index 공유)
    private int[] baseGreenOff;       // locks[i]의 baseGreen 시작 offset
    private int[] baseGreenCnt;
    private boolean fired = false;
    private long condSinceMs = -1;    // 발화조건이 연속으로 참이 된 시각(지속시간 게이트)

    public void reset() {
        lockFrameN = 0; lockFrames.clear(); lockW = -1; lockH = -1; lockVehBoxes = null;
        locked = false; pickedVariant = null; locks = null; baseGreen = null; baseGreenOff = null; baseGreenCnt = null;
        fired = false; condSinceMs = -1;
    }

    public boolean isLocked() { return locked; }
    public boolean isFired() { return fired; }
    public boolean isLockFailed() { return !locked && lockFrameN >= cfg.lockN; }   // ★645 고정 실패 — 호출부가 5초 뒤 새 인스턴스로 재고정

    // ★하니스 전용 — 고정(locks/baseGreen)은 유지한 채 발화·지속시간 상태만 되돌린다. persistMs 등 설정값을 바꿔가며
    //   같은 고정 결과로 추적 단계만 재현할 때 씀(3틱 전체 재검색 비용 절감). 프로덕션 경로는 호출 안 함.
    public void resetTrackState() { fired = false; condSinceMs = -1; }

    // ── 고정 단계 — 정차 시작 후 처음 lockN 프레임에 매 호출 ───────────
    // rgb: r4b 크롭 RGB(가공 없이), vehBoxes: 같은 크롭 좌표계의 차량 bbox(없으면 null, 첫 프레임만 사용)
    public LockOutcome addLockFrame(byte[] rgb, int w, int h, List<Box> vehBoxes) {
        LockOutcome out = new LockOutcome();
        if (locked || lockFrameN >= cfg.lockN) { out.diag = null; return out; }   // 이미 고정됐거나 고정 실패로 이번 정차는 포기 — 조용히 무시
        if (lockFrameN == 0) { lockW = w; lockH = h; lockVehBoxes = vehBoxes; }
        else if (w != lockW || h != lockH) { lockFrameN = cfg.lockN; lockFrames.clear(); out.diag = "[LR] 크롭 크기 변경 — 이번 정차 포기"; return out; }
        byte[] copy = new byte[rgb.length];
        System.arraycopy(rgb, 0, copy, 0, rgb.length);
        lockFrames.add(copy);
        lockFrameN++;
        if (lockFrameN < cfg.lockN) { out.diag = null; return out; }   // 수다 로그 금지 — 고정 성공/실패만 남긴다
        return buildLock(out);
    }

    private LockOutcome buildLock(LockOutcome out) {
        String bestVariant = null; float bestTot = -1f; Blob[] bestCands = null;
        for (String vn : VARIANTS) {
            Blob[][] reds = new Blob[lockFrames.size()][];
            for (int i = 0; i < lockFrames.size(); i++) {
                byte[] var = applyVariant(vn, lockFrames.get(i), lockW, lockH);
                reds[i] = blobs(var, lockW, lockH, true);
            }
            Blob[] f0 = filterExclude(reds[0]);
            List<Blob> cands = new ArrayList<>();
            for (Blob b : f0) {
                float tol = Math.max(cfg.tolK * b.r, cfg.tolMin);
                float minLock = b.score;
                boolean allHit = true;
                for (int i = 1; i < reds.length; i++) {
                    float h = maxScoreNear(reds[i], b.cx, b.cy, tol);
                    if (h <= 0) { allHit = false; break; }
                    if (h < minLock) minLock = h;
                }
                if (allHit && minLock >= cfg.lockBlobMin) { Blob c = clone(b); c.score = minLock; cands.add(c); }
            }
            cands.sort((a, c) -> Float.compare(c.score, a.score));
            float tot = 0f; int top = Math.min(3, cands.size());
            for (int i = 0; i < top; i++) tot += cands.get(i).score;
            if (!cands.isEmpty() && tot > bestTot) { bestTot = tot; bestVariant = vn; bestCands = cands.subList(0, top).toArray(new Blob[0]); }
        }
        if (bestVariant == null || bestTot < cfg.lockMin) {
            out.locked = false; out.diag = "[LR] 고정 실패 — best=" + bestVariant + " tot=" + fmt(bestTot) + " (이번 정차 재시도 안 함)";
            lockFrames.clear();   // ★lockFrameN은 cfg.lockN에 유지 — addLockFrame 가드가 다음 틱부터 재시도를 막는다(3틱마다 전체 재검색 방지)
            return out;
        }
        pickedVariant = bestVariant;
        locks = new Lock[bestCands.length];
        for (int i = 0; i < bestCands.length; i++) {
            Lock l = new Lock(); l.cx = bestCands[i].cx; l.cy = bestCands[i].cy; l.r = bestCands[i].r; l.a = bestCands[i].a; l.lockScore = bestCands[i].score;
            locks[i] = l;
        }
        // ★새-초록 탐색창별 base green(원래 있던 초록) — 3개 고정 프레임 전부에서, 각 lock의 탐색창만 조사
        baseGreenOff = new int[locks.length]; baseGreenCnt = new int[locks.length];
        List<Blob> bg = new ArrayList<>();
        for (int li = 0; li < locks.length; li++) {
            Lock l = locks[li];
            int[] win = greenWindow(l, lockW, lockH);
            baseGreenOff[li] = bg.size();
            if (win != null) {
                for (byte[] frame : lockFrames) {
                    byte[] var = applyVariant(pickedVariant, frame, lockW, lockH);
                    byte[] roi = crop(var, lockW, lockH, win[0], win[1], win[2], win[3]);
                    Blob[] gb = blobs(roi, win[2], win[3], false);
                    for (Blob g : gb) { g.cx += win[0]; g.cy += win[1]; bg.add(g); }
                }
            }
            baseGreenCnt[li] = bg.size() - baseGreenOff[li];
        }
        baseGreen = bg.toArray(new Blob[0]);
        locked = true; fired = false; condSinceMs = -1;
        StringBuilder sb = new StringBuilder();
        for (Lock l : locks) sb.append(String.format(java.util.Locale.US, "(%.0f,%.0f,r%.1f,%.3f)", l.cx, l.cy, l.r, l.lockScore));
        out.locked = true; out.variant = pickedVariant; out.totalScore = bestTot; out.locks = locks;
        out.diag = "[LR] 고정 성공 보정=" + pickedVariant + " 합계=" + fmt(bestTot) + " 개수=" + locks.length + " " + sb;
        lockFrames.clear();   // 원본 프레임 보관 불필요(고정 좌표 확정)
        return out;
    }

    // ── 추적 단계 — 고정 후 매 프레임 호출(작은 ROI만 검사) ───────────
    public TrackOutcome onTrackFrame(long nowMs, byte[] rgb, int w, int h) {
        TrackOutcome out = new TrackOutcome();
        if (!locked || fired || locks == null) { out.diag = null; return out; }
        // ★CLAHE는 타일 문맥(8x8)이 있어 "먼저 크롭 후 변형"이 "먼저 변형 후 크롭"과 다른 결과를 낸다(작은 ROI에 CLAHE를 걸면
        //   타일이 몇 px로 쪼그라들어 평활화가 왜곡). 고정 단계(buildLock)는 항상 전체 크롭 변형 후 자르므로, 여기도 CLAHE일 때만
        //   전체 크롭을 1회 변형해 재사용 — 나머지 5종은 픽셀단위 연산이라 크롭 순서가 결과에 영향 없음(비용 절감 위해 크롭 후 변형 유지).
        byte[] fullVar = "clahe".equals(pickedVariant) ? applyVariant(pickedVariant, rgb, w, h) : null;
        boolean[] off = new boolean[locks.length];
        float minRetain = Float.MAX_VALUE;
        String greenAt = null;
        for (int i = 0; i < locks.length; i++) {
            Lock l = locks[i];
            int r0 = Math.max(4, Math.round(l.r));
            int[] rw = clampBox(w, h, l.cx - 3 * r0, l.cy - 3 * r0, 6 * r0, 6 * r0);
            if (rw == null) { off[i] = true; continue; }   // ★고정 자리가 크롭 경계 밖으로 밀려난 경우(정차 중 미세 흔들림) — 잔존 0 취급, NPE 방지
            byte[] roiVar = fullVar != null ? crop(fullVar, w, h, rw[0], rw[1], rw[2], rw[3])
                    : applyVariant(pickedVariant, crop(rgb, w, h, rw[0], rw[1], rw[2], rw[3]), rw[2], rw[3]);
            Blob[] rb = blobs(roiVar, rw[2], rw[3], true);   // ★빨강 잔존 검사 — red=true(과거 false로 초록을 보던 버그, 2026-09-29 수정)
            float tol = Math.max(cfg.tolK * l.r, cfg.tolMin);
            float cur = maxScoreNear(rb, l.cx - rw[0], l.cy - rw[1], tol);
            float retain = l.lockScore > 0 ? cur / l.lockScore : 0f;
            if (retain < minRetain) minRetain = retain;
            off[i] = cur < cfg.redOffFrac * l.lockScore;
            int[] win = greenWindow(l, w, h);
            if (win != null) {
                byte[] gVar = fullVar != null ? crop(fullVar, w, h, win[0], win[1], win[2], win[3])
                        : applyVariant(pickedVariant, crop(rgb, w, h, win[0], win[1], win[2], win[3]), win[2], win[3]);
                Blob[] gb = blobs(gVar, win[2], win[3], false);
                for (Blob g : gb) {
                    float gx = g.cx + win[0], gy = g.cy + win[1];
                    if (!existsInBase(i, gx, gy, g.r)) { greenAt = String.format(java.util.Locale.US, "(%.0f,%.0f)", gx, gy); }
                }
            }
        }
        int offCount = 0; for (boolean b : off) if (b) offCount++;
        boolean redOff = offCount > 0 && offCount >= (locks.length + 1) / 2;
        boolean cond = redOff && greenAt != null;
        out.redRetainMin = minRetain == Float.MAX_VALUE ? 0 : minRetain;
        out.greenAt = greenAt;
        out.candidate = cond;
        if (!cond) { condSinceMs = -1; out.diag = null; return out; }
        if (condSinceMs < 0) condSinceMs = nowMs;
        long held = nowMs - condSinceMs;
        if (held >= cfg.persistMs) {
            fired = true;
            out.fired = true;
            out.diag = "[LR] 발화 잔존율=" + fmt(minRetain) + " 초록=" + greenAt + " 지연=" + held + "ms";
        } else {
            out.diag = null;   // 지속시간 미달 — 조용히 대기(수다 로그 금지)
        }
        return out;
    }

    // ── 브레이크등/차량 bbox 배제(고정 후보 생성 시 1회) ───────────────
    private Blob[] filterExclude(Blob[] cands) {
        boolean[] drop = new boolean[cands.length];
        if (cfg.vehExclude && lockVehBoxes != null) {
            for (int i = 0; i < cands.length; i++) {
                Blob b = cands[i];
                for (Box v : lockVehBoxes) {
                    if (b.cx >= v.x && b.cx <= v.x + v.w && b.cy >= v.y && b.cy <= v.y + v.h) { drop[i] = true; break; }
                }
            }
        }
        if (cfg.pairExclude) {
            for (int i = 0; i < cands.length; i++) {
                if (drop[i]) continue;
                for (int j = i + 1; j < cands.length; j++) {
                    if (drop[j]) continue;
                    Blob a = cands[i], c = cands[j];
                    float avgR = (a.r + c.r) / 2f;
                    float dy = Math.abs(a.cy - c.cy), dx = Math.abs(a.cx - c.cx);
                    float rRatio = Math.max(a.r, c.r) / Math.max(0.01f, Math.min(a.r, c.r));
                    if (dy <= cfg.pairDyMaxR * avgR && dx >= cfg.pairDxMinR * avgR && dx <= cfg.pairDxMaxR * avgR && rRatio <= cfg.pairRRatioMax) {
                        drop[i] = true; drop[j] = true;
                    }
                }
            }
        }
        int n = 0; for (boolean d : drop) if (!d) n++;
        Blob[] out = new Blob[n]; int k = 0;
        for (int i = 0; i < cands.length; i++) if (!drop[i]) out[k++] = cands[i];
        return out;
    }

    private boolean existsInBase(int lockIdx, float gx, float gy, float gr) {
        int off = baseGreenOff[lockIdx], cnt = baseGreenCnt[lockIdx];
        float tol = Math.max(2 * gr, 6);
        for (int i = off; i < off + cnt; i++) {
            Blob bg = baseGreen[i];
            if (Math.abs(bg.cx - gx) <= tol && Math.abs(bg.cy - gy) <= tol) return true;
        }
        return false;
    }

    private int[] greenWindow(Lock l, int w, int h) {
        float x0f = l.cx + cfg.dxMinK * l.r, x1f = l.cx + cfg.dxMaxK * l.r + cfg.dxMaxC;
        float y0f = l.cy - cfg.dyMaxK * l.r - cfg.dyMaxC, y1f = l.cy + cfg.dyMaxK * l.r + cfg.dyMaxC;
        return clampBox(w, h, x0f, y0f, x1f - x0f, y1f - y0f);
    }

    private static int[] clampBox(int w, int h, float x, float y, float bw, float bh) {
        int x0 = Math.max(0, Math.round(x)), y0 = Math.max(0, Math.round(y));
        int x1 = Math.min(w, Math.round(x + bw)), y1 = Math.min(h, Math.round(y + bh));
        if (x1 <= x0 || y1 <= y0) return null;
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    private static Blob clone(Blob b) { Blob c = new Blob(); c.cx = b.cx; c.cy = b.cy; c.r = b.r; c.a = b.a; c.score = b.score; c.chroma = b.chroma; c.contrast = b.contrast; return c; }
    private static float maxScoreNear(Blob[] list, float cx, float cy, float tol) {
        float m = 0f;
        for (Blob b : list) if (Math.abs(b.cx - cx) <= tol && Math.abs(b.cy - cy) <= tol) if (b.score > m) m = b.score;
        return m;
    }
    private static String fmt(float v) { return String.format(java.util.Locale.US, "%.4f", v); }

    // ── NV21(YUV_420SP) 크롭 → RGB(다운스케일 없음, 1:1) — SnifferService가 f(nv21)+r4b로 호출 ──
    public static byte[] nv21CropToRgb(byte[] nv21, int srcW, int srcH, int x0, int y0, int w, int h) {
        return nv21CropToRgb(nv21, srcW, srcH, x0, y0, w, h, null);
    }
    // reuse가 크롭 크기(w*h*3)와 정확히 같으면 그 배열에 덮어써 반환, 다르면 새로 만든다(틱마다 수 MB 할당 방지).
    public static byte[] nv21CropToRgb(byte[] nv21, int srcW, int srcH, int x0, int y0, int w, int h, byte[] reuse) {
        x0 = Math.max(0, Math.min(x0, srcW - 1)); y0 = Math.max(0, Math.min(y0, srcH - 1));
        w = Math.max(1, Math.min(w, srcW - x0)); h = Math.max(1, Math.min(h, srcH - y0));
        byte[] out = reuse != null && reuse.length == w * h * 3 ? reuse : new byte[w * h * 3];
        int ySize = srcW * srcH;
        for (int y = 0; y < h; y++) {
            int sy = y0 + y;
            int yRowOff = sy * srcW;
            int cRowOff = ySize + (sy / 2) * srcW;
            for (int x = 0; x < w; x++) {
                int sx = x0 + x;
                int Y = nv21[yRowOff + sx] & 0xFF;
                int cIdx = cRowOff + (sx / 2) * 2;
                int V = (nv21[cIdx] & 0xFF) - 128;
                int U = (nv21[cIdx + 1] & 0xFF) - 128;
                int r = Y + (1436 * V >> 10);
                int g = Y - ((352 * U + 731 * V) >> 10);
                int b = Y + (1814 * U >> 10);
                int p = (y * w + x) * 3;
                out[p] = (byte) clamp255(r);
                out[p + 1] = (byte) clamp255(g);
                out[p + 2] = (byte) clamp255(b);
            }
        }
        return out;
    }

    // ── RGB 크롭 유틸 ───────────────────────────────────────────────
    static byte[] crop(byte[] rgb, int w, int h, int x0, int y0, int cw, int ch) {
        byte[] out = new byte[cw * ch * 3];
        for (int y = 0; y < ch; y++) {
            int srcOff = ((y0 + y) * w + x0) * 3;
            int dstOff = y * cw * 3;
            System.arraycopy(rgb, srcOff, out, dstOff, cw * 3);
        }
        return out;
    }

    // ── 보정 변형(lockred.py VARIANTS) ─────────────────────────────
    private static final String[] VARIANTS = {"orig", "bright", "dark", "clahe", "sat", "expo"};
    private static final byte[] GAMMA_BRIGHT = gammaLut(0.6f);
    private static final byte[] GAMMA_DARK = gammaLut(1.6f);

    private static byte[] gammaLut(float gm) {
        byte[] lut = new byte[256];
        for (int i = 0; i < 256; i++) lut[i] = (byte) Math.round(Math.pow(i / 255.0, gm) * 255.0);
        return lut;
    }

    static byte[] applyVariant(String vn, byte[] rgb, int w, int h) {
        switch (vn) {
            case "bright": return applyLut(rgb, GAMMA_BRIGHT);
            case "dark": return applyLut(rgb, GAMMA_DARK);
            case "clahe": return clahe(rgb, w, h);
            case "sat": return satScale(rgb, 1.6f);
            case "expo": return expoScale(rgb, 0.6f);
            default: { byte[] c = new byte[rgb.length]; System.arraycopy(rgb, 0, c, 0, rgb.length); return c; }
        }
    }

    private static byte[] applyLut(byte[] rgb, byte[] lut) {
        byte[] out = new byte[rgb.length];
        for (int i = 0; i < rgb.length; i++) out[i] = lut[rgb[i] & 0xFF];
        return out;
    }

    private static byte[] expoScale(byte[] rgb, float k) {
        byte[] out = new byte[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            int v = Math.round((rgb[i] & 0xFF) * k);
            out[i] = (byte) Math.min(255, Math.max(0, v));
        }
        return out;
    }

    private static byte[] satScale(byte[] rgb, float k) {
        byte[] out = new byte[rgb.length];
        float[] hsv = new float[3];
        for (int i = 0; i < rgb.length; i += 3) {
            int r = rgb[i] & 0xFF, g = rgb[i + 1] & 0xFF, b = rgb[i + 2] & 0xFF;
            rgbToHsv(r, g, b, hsv);
            hsv[1] = Math.min(1f, hsv[1] * k);
            int[] o = hsvToRgb(hsv[0], hsv[1], hsv[2]);
            out[i] = (byte) o[0]; out[i + 1] = (byte) o[1]; out[i + 2] = (byte) o[2];
        }
        return out;
    }

    // H: 0~360, S/V: 0~1
    private static void rgbToHsv(int r, int g, int b, float[] out) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf));
        float d = max - min;
        float h;
        if (d < 1e-6f) h = 0f;
        else if (max == rf) h = 60f * (((gf - bf) / d) % 6f);
        else if (max == gf) h = 60f * ((bf - rf) / d + 2f);
        else h = 60f * ((rf - gf) / d + 4f);
        if (h < 0) h += 360f;
        out[0] = h; out[1] = max < 1e-6f ? 0f : d / max; out[2] = max;
    }

    private static int[] hsvToRgb(float h, float s, float v) {
        float c = v * s, x = c * (1 - Math.abs((h / 60f) % 2f - 1)), m = v - c;
        float rf, gf, bf;
        if (h < 60) { rf = c; gf = x; bf = 0; }
        else if (h < 120) { rf = x; gf = c; bf = 0; }
        else if (h < 180) { rf = 0; gf = c; bf = x; }
        else if (h < 240) { rf = 0; gf = x; bf = c; }
        else if (h < 300) { rf = x; gf = 0; bf = c; }
        else { rf = c; gf = 0; bf = x; }
        return new int[]{clamp255(Math.round((rf + m) * 255)), clamp255(Math.round((gf + m) * 255)), clamp255(Math.round((bf + m) * 255))};
    }
    private static int clamp255(int v) { return v < 0 ? 0 : Math.min(v, 255); }

    // ★CLAHE 근사(2026-09-28) — OpenCV Lab-L CLAHE의 근사치. 실제 Lab 변환 대신 luma(BT.601)를 타일 히스토그램
    //   평활화하고, 원본 대비 보정비를 R/G/B에 동일 적용(색상 유지). 타일 8x8·clip 2.5는 OpenCV 기본과 동일.
    private static byte[] clahe(byte[] rgb, int w, int h) {
        int n = w * h;
        int[] luma = new int[n];
        for (int i = 0, p = 0; i < n; i++, p += 3) {
            int r = rgb[p] & 0xFF, g = rgb[p + 1] & 0xFF, b = rgb[p + 2] & 0xFF;
            luma[i] = (299 * r + 587 * g + 114 * b) / 1000;
        }
        int tilesX = 8, tilesY = 8;
        int tileW = (w + tilesX - 1) / tilesX, tileH = (h + tilesY - 1) / tilesY;
        int[][][] mapping = new int[tilesY][tilesX][256];
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int x0 = tx * tileW, y0 = ty * tileH;
                int x1 = Math.min(w, x0 + tileW), y1 = Math.min(h, y0 + tileH);
                int area = Math.max(1, (x1 - x0) * (y1 - y0));
                int[] hist = new int[256];
                for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) hist[luma[y * w + x]]++;
                int clipLimit = Math.max(1, Math.round(2.5f * area / 256f));
                int excess = 0;
                for (int i = 0; i < 256; i++) if (hist[i] > clipLimit) { excess += hist[i] - clipLimit; hist[i] = clipLimit; }
                int addEach = excess / 256, rem = excess % 256;
                for (int i = 0; i < 256; i++) { hist[i] += addEach; if (i < rem) hist[i]++; }
                float scale = 255f / area;
                int cum = 0;
                for (int i = 0; i < 256; i++) { cum += hist[i]; mapping[ty][tx][i] = clamp255(Math.round(cum * scale)); }
            }
        }
        byte[] out = new byte[rgb.length];
        for (int y = 0; y < h; y++) {
            float fy = (y - tileH / 2f) / tileH; int tyl = (int) Math.floor(fy); float wy = fy - tyl;
            if (tyl < 0) { tyl = 0; wy = 0; } if (tyl >= tilesY - 1) { tyl = tilesY - 1; wy = 0; }
            int tyr = Math.min(tilesY - 1, tyl + 1);
            for (int x = 0; x < w; x++) {
                float fx = (x - tileW / 2f) / tileW; int txl = (int) Math.floor(fx); float wx = fx - txl;
                if (txl < 0) { txl = 0; wx = 0; } if (txl >= tilesX - 1) { txl = tilesX - 1; wx = 0; }
                int txr = Math.min(tilesX - 1, txl + 1);
                int v = luma[y * w + x];
                float m00 = mapping[tyl][txl][v], m01 = mapping[tyl][txr][v], m10 = mapping[tyr][txl][v], m11 = mapping[tyr][txr][v];
                float newLuma = m00 * (1 - wx) * (1 - wy) + m01 * wx * (1 - wy) + m10 * (1 - wx) * wy + m11 * wx * wy;
                float ratio = v > 0 ? newLuma / v : 1f;
                int p = (y * w + x) * 3;
                out[p] = (byte) clamp255(Math.round((rgb[p] & 0xFF) * ratio));
                out[p + 1] = (byte) clamp255(Math.round((rgb[p + 1] & 0xFF) * ratio));
                out[p + 2] = (byte) clamp255(Math.round((rgb[p + 2] & 0xFF) * ratio));
            }
        }
        return out;
    }

    // ── 색 마스크(HSV 임계, OpenCV 스케일 등가) + 5x5 모폴로지 클로즈 ───
    private static boolean[] colorMask(byte[] rgb, int w, int h, boolean red) {
        boolean[] m = new boolean[w * h];
        float[] hsv = new float[3];
        for (int i = 0, p = 0; i < m.length; i++, p += 3) {
            int r = rgb[p] & 0xFF, g = rgb[p + 1] & 0xFF, b = rgb[p + 2] & 0xFF;
            rgbToHsv(r, g, b, hsv);
            if (red) m[i] = (hsv[0] <= 20f || hsv[0] >= 330f) && hsv[1] >= 90f / 255f && hsv[2] >= 110f / 255f;
            else m[i] = hsv[0] >= 80f && hsv[0] <= 190f && hsv[1] >= 70f / 255f && hsv[2] >= 110f / 255f;
        }
        return morphClose5(m, w, h);
    }

    // 5x5 사각 커널 dilate→erode, 수평/수직 분리(min/max) — 정확도 동일·비용 1/5
    private static boolean[] morphClose5(boolean[] m, int w, int h) {
        boolean[] d = maxFilter5(m, w, h);
        return minFilter5(d, w, h);
    }
    private static boolean[] maxFilter5(boolean[] m, int w, int h) {
        boolean[] tmp = new boolean[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean v = false;
            for (int k = -2; k <= 2 && !v; k++) { int xx = x + k; if (xx >= 0 && xx < w) v = m[y * w + xx]; }
            tmp[y * w + x] = v;
        }
        boolean[] out = new boolean[w * h];
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) {
            boolean v = false;
            for (int k = -2; k <= 2 && !v; k++) { int yy = y + k; if (yy >= 0 && yy < h) v = tmp[yy * w + x]; }
            out[y * w + x] = v;
        }
        return out;
    }
    private static boolean[] minFilter5(boolean[] m, int w, int h) {
        boolean[] tmp = new boolean[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean v = true;
            for (int k = -2; k <= 2 && v; k++) { int xx = x + k; v = xx >= 0 && xx < w && m[y * w + xx]; }
            tmp[y * w + x] = v;
        }
        boolean[] out = new boolean[w * h];
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) {
            boolean v = true;
            for (int k = -2; k <= 2 && v; k++) { int yy = y + k; v = yy >= 0 && yy < h && tmp[yy * w + x]; }
            out[y * w + x] = v;
        }
        return out;
    }

    // ── 8-연결 라벨링 + 통계 + 스코어링(lockred.py blobs()) ─────────
    private Blob[] blobs(byte[] rgb, int w, int h, boolean red) {
        boolean[] m = colorMask(rgb, w, h, red);
        int[] lab = new int[w * h];
        int nextLabel = 0;
        int[] stackX = new int[w * h], stackY = new int[w * h];
        List<Blob> out = new ArrayList<>();
        int[] gray = new int[w * h];
        for (int i = 0, p = 0; i < gray.length; i++, p += 3) gray[i] = (299 * (rgb[p] & 0xFF) + 587 * (rgb[p + 1] & 0xFF) + 114 * (rgb[p + 2] & 0xFF)) / 1000;
        for (int y0 = 0; y0 < h; y0++) {
            for (int x0 = 0; x0 < w; x0++) {
                int idx0 = y0 * w + x0;
                if (!m[idx0] || lab[idx0] != 0) continue;
                nextLabel++;
                int sp = 0; stackX[sp] = x0; stackY[sp] = y0; sp++;
                lab[idx0] = nextLabel;
                int minx = x0, maxx = x0, miny = y0, maxy = y0, area = 0;
                double sumCx = 0, sumCy = 0;
                double sumChromaNum = 0;   // red: R-max(G,B) 합 / green: G-B 합
                while (sp > 0) {
                    sp--; int x = stackX[sp], y = stackY[sp];
                    area++; sumCx += x; sumCy += y;
                    int p = (y * w + x) * 3;
                    int r = rgb[p] & 0xFF, g = rgb[p + 1] & 0xFF, b = rgb[p + 2] & 0xFF;
                    sumChromaNum += red ? (r - Math.max(g, b)) : (g - b);
                    if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y;
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || xx >= w || yy < 0 || yy >= h) continue;
                        int idx = yy * w + xx;
                        if (m[idx] && lab[idx] == 0) { lab[idx] = nextLabel; stackX[sp] = xx; stackY[sp] = yy; sp++; }
                    }
                }
                int bw = maxx - minx + 1, bh = maxy - miny + 1;
                if (area < cfg.areaMin || area > cfg.areaMax) continue;
                float asp = (float) bw / bh;
                if (asp < cfg.aspectMin || asp > cfg.aspectMax) continue;
                float fill = area / (float) (bw * bh);
                if (fill < cfg.fillMin) continue;
                float r = Math.max(bw, bh) / 2f;
                float cx = (float) (sumCx / area), cy = (float) (sumCy / area);
                int rx0 = (int) Math.max(cx - 3 * r, 0), rx1 = (int) Math.min(cx + 3 * r, w);
                int ry0 = (int) Math.max(cy - 3 * r, 0), ry1 = (int) Math.min(cy + 3 * r, h);
                long ringSum = 0; int ringN = 0;
                for (int y = ry0; y < ry1; y++) for (int x = rx0; x < rx1; x++) { ringSum += gray[y * w + x]; ringN++; }
                double ringMean = ringN > 0 ? ringSum / (double) ringN : 0;
                double innerMean = area > 0 ? sumInnerGray(lab, gray, w, rx0, ry0, rx1, ry1, nextLabel) / (double) area : 0;
                float contrast = (float) ((innerMean - ringMean) / 255.0);
                float chroma = (float) (sumChromaNum / area / 255.0);
                float score = Math.max(chroma, 0f) * Math.max(contrast, 0.01f) * fill * (float) Math.sqrt(Math.min(area, 400));
                Blob bl = new Blob(); bl.cx = cx; bl.cy = cy; bl.r = r; bl.a = area; bl.score = score; bl.chroma = chroma; bl.contrast = contrast;
                out.add(bl);
            }
        }
        return out.toArray(new Blob[0]);
    }

    private static long sumInnerGray(int[] lab, int[] gray, int w, int x0, int y0, int x1, int y1, int label) {
        long s = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) if (lab[y * w + x] == label) s += gray[y * w + x];
        return s;
    }
}
