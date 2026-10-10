package com.cseini.bydsniffer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 신호등 새 방식(FLIP) 판정기 — 안드로이드 의존 없는 순수 Java. 정본은 피드백분석 세션의 파이썬 기준 코드(flip_core_ref.py).
 * 최근 스냅샷 6장 중앙값(기준)과 현재 프레임의 (빨강, 초록) 색 맵을 비교해 "왼쪽 빨강이 꺼지고 바로 오른쪽에 초록이 새로 켜진" 짝의 점수를 낸다.
 * 입력 RGB는 R,G,B 순서 바이트 배열이고 가로·세로는 짝수여야 한다(2×2 평균 축소가 정수 배수로 떨어져야 파이썬 INTER_AREA와 같다).
 */
public final class TrafficFlipDetector {
    public static final int S = 2;
    public static final float RED_THR = 15f, GRN_THR = 12f;
    public static final int SNAP_CAP = 8, REF_N = 6, REF_MIN = 2;
    public static final long SNAP_EVERY_MS = 1200, REF_AGE_MS = 1200, SNAP_GAP_MAX_MS = 8000;   // ★668 2400→1200 (첫 판정까지 단축)

    private static final float[] K_MAP = {0.0002639f, 0.1064508f, 0.7865707f, 0.1064508f, 0.0002639f};
    private static final float[] K_PK = {0.0025663f, 0.1655246f, 0.6638183f, 0.1655246f, 0.0025663f};
    private static final int LOC = 2, HALF = 10, TOPN = 60, MIN_DIM = 8;
    private static final int GV = 200, DMIN = 6, DMAX = 90;
    private static final double ASP = 2.2;

    public static final class Maps {
        public final float[] red, grn;
        public Maps(float[] red, float[] grn) { this.red = red; this.grn = grn; }
    }

    public static final class Cand {
        public double score;
        public int rx, ry, rw, rh;
        public float rpk;
        public int gx, gy, gw, gh, gv;
        public float gpk;
        public int rightSame = -1;
    }

    public static final class Result {
        public double best;
        public Cand bestCand;
        public final List<Cand> cands = new ArrayList<>();
        public int nRed, nGrn;
        public float maxRpk, maxGpk;
        public int maxGv;
    }

    public static final class Tick {
        public boolean ready;
        public int refCount, snapSeq, snapCount, persist;
        public boolean hit;
        public Result result;
    }

    private static final class Snap {
        final float[] red, grn;
        long ts;
        int seq;
        Snap(int n) { red = new float[n]; grn = new float[n]; }
    }

    private static final class Peak { int x, y; float v; }

    private int hw, hh;
    private float[] half, curRed, curGrn, s1, s2, s3, s4, refRed, refGrn;
    private final ArrayList<Snap> snaps = new ArrayList<>();
    private final ArrayList<Snap> pool = new ArrayList<>();
    private long lastSnapMs;
    private int snapSeq, refKeySeq = -1, refKeyN = 0, persist;
    private final int[] stack = new int[(2 * HALF + 1) * (2 * HALF + 1)];
    private final boolean[] seen = new boolean[(2 * HALF + 1) * (2 * HALF + 1)];
    private final int[] blobOut = new int[2];

    // ── 라이브 진입 ────────────────────────────────────────────────

    public void reset() {
        pool.addAll(snaps);
        snaps.clear();
        refKeySeq = -1; refKeyN = 0; persist = 0;
    }

    public void release() {
        reset();
        pool.clear();
        hw = 0; hh = 0;
        half = curRed = curGrn = s1 = s2 = s3 = s4 = refRed = refGrn = null;
    }

    public int snapshotCount() { return snaps.size(); }

    /** 정차 중 판정 틱 1회. rgb는 크롭(가로 w·세로 h 짝수). x0·y0=크롭 원점(원해상도), top·ybot=탐지 행 범위(원해상도). */
    public Tick onFrame(byte[] rgb, int w, int h, int x0, int y0, int top, int ybot, long nowMs, double thr, int persistN, double strongThr) {
        ensureSize(w / S, h / S);
        halfResInto(rgb, w, h, half);
        colorMapsInto(half, curRed, curGrn, null);
        if (!snaps.isEmpty() && nowMs - lastSnapMs > SNAP_GAP_MAX_MS) reset();
        Tick t = new Tick();
        boolean capture = snaps.isEmpty() || nowMs - lastSnapMs >= SNAP_EVERY_MS;
        List<Snap> refs = new ArrayList<>(REF_N);
        for (Snap s : snaps) if (nowMs - s.ts >= REF_AGE_MS) refs.add(s);
        while (refs.size() > REF_N) refs.remove(0);
        t.refCount = refs.size();
        if (refs.size() >= REF_MIN) {
            int key = refs.get(refs.size() - 1).seq;
            if (key != refKeySeq || refs.size() != refKeyN) {
                List<float[]> rl = new ArrayList<>(refs.size()), gl = new ArrayList<>(refs.size());
                for (Snap s : refs) { rl.add(s.red); gl.add(s.grn); }
                medianInto(rl, refRed);
                medianInto(gl, refGrn);
                refKeySeq = key; refKeyN = refs.size();
            }
            t.ready = true;
            t.result = scoreCore(half, curRed, curGrn, refRed, refGrn, x0, y0, top, ybot, null);
            if (t.result.best >= thr) persist++; else persist = 0;
        } else {
            persist = 0;
        }
        if (capture) {
            Snap s;
            if (snaps.size() >= SNAP_CAP) s = snaps.remove(0);
            else if (!pool.isEmpty()) s = pool.remove(pool.size() - 1);
            else s = new Snap(hw * hh);
            System.arraycopy(curRed, 0, s.red, 0, hw * hh);
            System.arraycopy(curGrn, 0, s.grn, 0, hw * hh);
            s.ts = nowMs; s.seq = ++snapSeq;
            snaps.add(s);
            lastSnapMs = nowMs;
        }
        t.persist = persist;
        // ★666 매우 강한 후보는 한 번만 넘어도 발화(운영자 차 09-30 밤 로그: 점수 6800~11337이 '2번 연속' 때문에 3건 놓침, 오프라인 사진 155건에서 5000↑ 오발 0/127)
        //   ★738 기준을 고정 5000에서 문턱×배수(기본 1.2=3000)로 — 10-04 18:15 점수 3013이 지속 1/2로 놓침(피드백분석 대조). 호출측 prefs front_flip_strong_x.
        t.hit = t.ready && (persist >= persistN || (t.result != null && t.result.best >= strongThr));
        t.snapSeq = snapSeq;
        t.snapCount = snaps.size();
        return t;
    }

    private void ensureSize(int nhw, int nhh) {
        if (nhw < MIN_DIM || nhh < MIN_DIM) throw new IllegalArgumentException("크롭이 너무 작음 " + nhw * S + "x" + nhh * S);
        if (nhw == hw && nhh == hh && half != null) return;
        pool.clear();
        snaps.clear();
        refKeySeq = -1; refKeyN = 0; persist = 0;
        hw = nhw; hh = nhh;
        int n = hw * hh;
        half = new float[n * 3];
        curRed = new float[n]; curGrn = new float[n];
        s1 = new float[n]; s2 = new float[n]; s3 = new float[n]; s4 = new float[n];
        refRed = new float[n]; refGrn = new float[n];
    }

    // ── 시험·공용 진입 ─────────────────────────────────────────────

    /** RGB 바이트(R,G,B 순) → 절반 해상도 float RGB(2×2 평균, 반올림 없음). 가로·세로 짝수 필수. */
    public static float[] halfRes(byte[] rgb, int w, int h) {
        float[] out = new float[(w / S) * (h / S) * 3];
        halfResInto(rgb, w, h, out);
        return out;
    }

    static void halfResInto(byte[] rgb, int w, int h, float[] out) {
        if ((w & 1) != 0 || (h & 1) != 0) throw new IllegalArgumentException("가로·세로는 짝수여야 함 " + w + "x" + h);
        if (rgb.length < w * h * 3) throw new IllegalArgumentException("RGB 길이 부족 " + rgb.length + " < " + (w * h * 3));
        int hw = w / S, hh = h / S;
        for (int y = 0; y < hh; y++) {
            int r0 = (2 * y) * w * 3, r1 = r0 + w * 3;
            int o = y * hw * 3;
            for (int x = 0; x < hw; x++) {
                int a = r0 + x * 6, b = r1 + x * 6;
                out[o] = ((rgb[a] & 255) + (rgb[a + 3] & 255) + (rgb[b] & 255) + (rgb[b + 3] & 255)) * 0.25f;
                out[o + 1] = ((rgb[a + 1] & 255) + (rgb[a + 4] & 255) + (rgb[b + 1] & 255) + (rgb[b + 4] & 255)) * 0.25f;
                out[o + 2] = ((rgb[a + 2] & 255) + (rgb[a + 5] & 255) + (rgb[b + 2] & 255) + (rgb[b + 5] & 255)) * 0.25f;
                o += 3;
            }
        }
    }

    /** 절반 해상도 RGB → (빨강, 초록) 색 맵. valid(절반 해상도, true=유효)가 있으면 무효 자리는 0. */
    public static Maps colorMaps(float[] half, int hw, int hh, boolean[] valid) {
        TrafficFlipDetector d = new TrafficFlipDetector();
        d.ensureSize(hw, hh);
        float[] red = new float[hw * hh], grn = new float[hw * hh];
        d.colorMapsInto(half, red, grn, valid);
        return new Maps(red, grn);
    }

    /** 크롭 크기 제외 마스크(true=제외) → 절반 해상도 유효 마스크. 원래 2×2 네 화소가 모두 유효일 때만 유효. */
    public static boolean[] foldValid(boolean[] exclude, int w, int h) {
        int hw = w / S, hh = h / S;
        boolean[] v = new boolean[hw * hh];
        for (int y = 0; y < hh; y++)
            for (int x = 0; x < hw; x++) {
                int a = (2 * y) * w + 2 * x, b = a + w;
                v[y * hw + x] = !exclude[a] && !exclude[a + 1] && !exclude[b] && !exclude[b + 1];
            }
        return v;
    }

    /** 시험용: 현재 프레임(절반 해상도 RGB) + 기준 스냅샷 여러 장(valid 처리가 끝난 맵) → 점수와 후보 짝 목록. */
    public static Result frameScore(float[] curHalf, int hw, int hh, List<Maps> refs, int x0, int y0, int top, int ybot, boolean[] valid) {
        if (refs.isEmpty()) throw new IllegalArgumentException("기준 스냅샷이 없음");
        TrafficFlipDetector d = new TrafficFlipDetector();
        d.ensureSize(hw, hh);
        d.colorMapsInto(curHalf, d.curRed, d.curGrn, valid);
        List<float[]> rl = new ArrayList<>(refs.size()), gl = new ArrayList<>(refs.size());
        for (Maps m : refs) { rl.add(m.red); gl.add(m.grn); }
        medianInto(rl, d.refRed);
        medianInto(gl, d.refGrn);
        return d.scoreCore(curHalf, d.curRed, d.curGrn, d.refRed, d.refGrn, x0, y0, top, ybot, valid);
    }

    // ── 색 맵 ──────────────────────────────────────────────────────

    private void colorMapsInto(float[] halfRgb, float[] outRed, float[] outGrn, boolean[] valid) {
        int n = hw * hh;
        for (int i = 0; i < n; i++) {
            float r = halfRgb[3 * i], g = halfRgb[3 * i + 1], b = halfRgb[3 * i + 2];
            s1[i] = Math.max(r - Math.max(g, b), 0f);
            s3[i] = Math.max(g - Math.max(r, 0.6f * b), 0f);
        }
        blur(s1, outRed, s2, hw, hh, K_MAP);
        blur(s3, outGrn, s2, hw, hh, K_MAP);
        if (valid != null) {
            for (int i = 0; i < n; i++) if (!valid[i]) { outRed[i] = 0f; outGrn[i] = 0f; }
        }
    }

    private static void medianInto(List<float[]> maps, float[] out) {
        int k = maps.size(), n = out.length;
        float[][] m = maps.toArray(new float[0][]);
        float[] v = new float[k];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < k; j++) {
                float x = m[j][i];
                int p = j;
                while (p > 0 && v[p - 1] > x) { v[p] = v[p - 1]; p--; }
                v[p] = x;
            }
            out[i] = (k & 1) == 1 ? v[k / 2] : (v[k / 2 - 1] + v[k / 2]) * 0.5f;
        }
    }

    // ── 점수 ───────────────────────────────────────────────────────

    private Result scoreCore(float[] halfRgb, float[] red, float[] grn, float[] rRed, float[] rGrn,
                             int x0, int y0, int top, int ybot, boolean[] valid) {
        int n = hw * hh;
        int lo = Math.floorDiv(top - y0, S), hi = Math.floorDiv(ybot - y0, S);
        for (int i = 0; i < n; i++) s1[i] = rRed[i] - red[i];
        List<Peak> pr = findPeaks(s1, RED_THR, lo, hi, valid);
        for (int i = 0; i < n; i++) s1[i] = grn[i] - rGrn[i];
        List<Peak> pg = findPeaks(s1, GRN_THR, lo, hi, valid);

        Result res = new Result();
        res.nRed = pr.size(); res.nGrn = pg.size();
        int nr = pr.size(), ng = pg.size();
        if (nr > 0) res.maxRpk = pr.get(0).v;
        if (ng > 0) res.maxGpk = pg.get(0).v;
        int[] rx = new int[nr], ry = new int[nr], rw = new int[nr], rh = new int[nr];
        float[] rpk = new float[nr];
        boolean[] rOk = new boolean[nr];
        for (int i = 0; i < nr; i++) {
            Peak p = pr.get(i);
            blobSize(rRed, p.x, p.y);
            rx[i] = p.x * S + x0; ry[i] = p.y * S + y0; rpk[i] = p.v; rw[i] = blobOut[0]; rh[i] = blobOut[1];
            rOk[i] = aspectOk(rw[i], rh[i]);
        }
        int[] gx = new int[ng], gy = new int[ng], gw = new int[ng], gh = new int[ng], gv = new int[ng];
        float[] gpk = new float[ng];
        boolean[] gOk = new boolean[ng];
        for (int i = 0; i < ng; i++) {
            Peak p = pg.get(i);
            int q = (p.y * hw + p.x) * 3;
            gv[i] = (int) Math.max(halfRgb[q], Math.max(halfRgb[q + 1], halfRgb[q + 2]));
            blobSize(grn, p.x, p.y);
            gx[i] = p.x * S + x0; gy[i] = p.y * S + y0; gpk[i] = p.v; gw[i] = blobOut[0]; gh[i] = blobOut[1];
            gOk[i] = gv[i] >= GV && aspectOk(gw[i], gh[i]);
            if (i == 0) res.maxGv = gv[i];
        }
        for (int i = 0; i < nr; i++) {
            if (!rOk[i]) continue;
            for (int j = 0; j < ng; j++) {
                if (!gOk[j]) continue;
                int dx = gx[j] - rx[i], dy = gy[j] - ry[i];
                if (!(dx >= DMIN && dx <= DMAX && Math.abs(dy) <= Math.max(14.0, 0.25 * dx))) continue;
                if (Math.min(ry[i], gy[j]) < top || Math.max(ry[i], gy[j]) > ybot) continue;
                Cand c = new Cand();
                c.score = (double) rpk[i] * (double) gpk[j];
                c.rx = rx[i]; c.ry = ry[i]; c.rw = rw[i]; c.rh = rh[i]; c.rpk = rpk[i];
                c.gx = gx[j]; c.gy = gy[j]; c.gw = gw[j]; c.gh = gh[j]; c.gv = gv[j]; c.gpk = gpk[j];
                res.cands.add(c);
                if (c.score > res.best) { res.best = c.score; res.bestCand = c; }
            }
        }
        if (res.bestCand != null) {
            Cand bc = res.bestCand;
            List<Peak> pc = findPeaks(red, RED_THR, lo, hi, valid);
            int same = 0;
            for (Peak q : pc) {
                int qx = q.x * S + x0, qy = q.y * S + y0;
                if (qx > bc.rx + bc.rw && Math.abs(qy - bc.ry) <= Math.max(14.0, 0.25 * Math.abs(qx - bc.rx))) same++;
            }
            bc.rightSame = same;
        }
        return res;
    }

    private static boolean aspectOk(int w, int h) {
        return (double) Math.max(w, h) / Math.max(1, Math.min(w, h)) <= ASP;
    }

    private List<Peak> findPeaks(float[] diff, float thr, int lo, int hi, boolean[] valid) {
        int n = hw * hh;
        blur(diff, s3, s2, hw, hh, K_PK);
        if (valid != null) for (int i = 0; i < n; i++) if (!valid[i]) s3[i] = 0f;
        int zTop = Math.min(Math.max(lo, 0), hh), zBot = Math.max(Math.min(hi + 1, hh), 0);
        Arrays.fill(s3, 0, zTop * hw, 0f);
        Arrays.fill(s3, zBot * hw, n, 0f);
        dilate5(s3, s4, s2, hw, hh);
        List<Peak> out = new ArrayList<>();
        int yEnd = Math.min(hi, hh - 1);
        for (int y = Math.max(lo, 0); y <= yEnd; y++) {
            int o = y * hw;
            for (int x = 0; x < hw; x++) {
                float v = s3[o + x];
                if (v >= thr && v == s4[o + x]) {
                    Peak p = new Peak();
                    p.x = x; p.y = y; p.v = v;
                    out.add(p);
                }
            }
        }
        out.sort((a, b) -> Float.compare(b.v, a.v));
        return out.size() > TOPN ? new ArrayList<>(out.subList(0, TOPN)) : out;
    }

    /** 정점에서 ±2 안 최댓값으로 옮긴 뒤 ±10 창에서 값 ≥ 0.5×정점인 8방향 연결 덩어리의 폭·높이(원해상도 px)를 blobOut에 담는다. */
    private void blobSize(float[] val, int x, int y) {
        int sy0 = Math.max(0, y - LOC), sy1 = Math.min(hh, y + LOC + 1), sx0 = Math.max(0, x - LOC), sx1 = Math.min(hw, x + LOC + 1);
        int by = sy0, bx = sx0;
        float bv = val[sy0 * hw + sx0];
        for (int yy = sy0; yy < sy1; yy++)
            for (int xx = sx0; xx < sx1; xx++) {
                float v = val[yy * hw + xx];
                if (v > bv) { bv = v; by = yy; bx = xx; }
            }
        y = by; x = bx;
        float pk = val[y * hw + x];
        if (pk <= 0f) { blobOut[0] = 1; blobOut[1] = 1; return; }
        int y0 = Math.max(0, y - HALF), y1 = Math.min(hh, y + HALF + 1), x0 = Math.max(0, x - HALF), x1 = Math.min(hw, x + HALF + 1);
        int sw = x1 - x0, sh = y1 - y0;
        float th = 0.5f * pk;
        Arrays.fill(seen, 0, sw * sh, false);
        int sp = 0;
        int seed = (y - y0) * sw + (x - x0);
        stack[sp++] = seed;
        seen[seed] = true;
        int minx = sw, maxx = -1, miny = sh, maxy = -1;
        while (sp > 0) {
            int idx = stack[--sp];
            int cy = idx / sw, cx = idx - cy * sw;
            if (cx < minx) minx = cx;
            if (cx > maxx) maxx = cx;
            if (cy < miny) miny = cy;
            if (cy > maxy) maxy = cy;
            for (int dy = -1; dy <= 1; dy++) {
                int ny = cy + dy;
                if (ny < 0 || ny >= sh) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = cx + dx;
                    if (nx < 0 || nx >= sw) continue;
                    int ni = ny * sw + nx;
                    if (seen[ni] || val[(y0 + ny) * hw + x0 + nx] < th) continue;
                    seen[ni] = true;
                    stack[sp++] = ni;
                }
            }
        }
        blobOut[0] = (maxx - minx + 1) * S;
        blobOut[1] = (maxy - miny + 1) * S;
    }

    // ── 필터 ───────────────────────────────────────────────────────

    private static int refl(int i, int n) {
        if (i < 0) return -i;
        if (i >= n) return 2 * n - 2 - i;
        return i;
    }

    /** 분리형 5탭 가우시안, 가장자리 reflect101. */
    private static void blur(float[] src, float[] dst, float[] tmp, int w, int h, float[] k) {
        float k0 = k[0], k1 = k[1], k2 = k[2], k3 = k[3], k4 = k[4];
        for (int y = 0; y < h; y++) {
            int o = y * w;
            for (int x = 0; x < 2; x++)
                tmp[o + x] = k0 * src[o + refl(x - 2, w)] + k1 * src[o + refl(x - 1, w)] + k2 * src[o + x] + k3 * src[o + x + 1] + k4 * src[o + x + 2];
            for (int x = 2; x < w - 2; x++)
                tmp[o + x] = k0 * src[o + x - 2] + k1 * src[o + x - 1] + k2 * src[o + x] + k3 * src[o + x + 1] + k4 * src[o + x + 2];
            for (int x = w - 2; x < w; x++)
                tmp[o + x] = k0 * src[o + x - 2] + k1 * src[o + x - 1] + k2 * src[o + x] + k3 * src[o + refl(x + 1, w)] + k4 * src[o + refl(x + 2, w)];
        }
        for (int y = 0; y < h; y++) {
            int r0 = refl(y - 2, h) * w, r1 = refl(y - 1, h) * w, r2 = y * w, r3 = refl(y + 1, h) * w, r4 = refl(y + 2, h) * w;
            for (int x = 0; x < w; x++)
                dst[r2 + x] = k0 * tmp[r0 + x] + k1 * tmp[r1 + x] + k2 * tmp[r2 + x] + k3 * tmp[r3 + x] + k4 * tmp[r4 + x];
        }
    }

    /** 5×5 최댓값 필터(범위 밖은 무시). */
    private static void dilate5(float[] src, float[] dst, float[] tmp, int w, int h) {
        for (int y = 0; y < h; y++) {
            int o = y * w;
            for (int x = 0; x < w; x++) {
                float m = src[o + x];
                int xa = Math.max(0, x - 2), xb = Math.min(w - 1, x + 2);
                for (int xx = xa; xx <= xb; xx++) if (src[o + xx] > m) m = src[o + xx];
                tmp[o + x] = m;
            }
        }
        for (int y = 0; y < h; y++) {
            int ya = Math.max(0, y - 2), yb = Math.min(h - 1, y + 2);
            int o = y * w;
            for (int x = 0; x < w; x++) {
                float m = tmp[o + x];
                for (int yy = ya; yy <= yb; yy++) if (tmp[yy * w + x] > m) m = tmp[yy * w + x];
                dst[o + x] = m;
            }
        }
    }
}
