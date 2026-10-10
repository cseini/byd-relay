package com.cseini.motiondetect;

// ★번호판 기반 거리 보조 신호(2026-09-11, 운영자 지적) — 앞차 bbox의 h/cy는 검출기 노이즈가 커서
//   (정지 중에도 cy 0.38↔0.55 튐) 멀어짐 판정이 흔들린다. 번호판은 물리 폭이 고정(신형 520mm/구형 335mm)
//   이라 픽셀 폭이 곧 거리의 역수(distance ∝ 1/plateWidthPx) — 정지 중엔 폭이 거의 안 변하고 출발하면 단조 감소.
//   ★색을 보지 않는다 — 한국 번호판은 흰/검정(일반)·파랑/흰(전기차)·녹색/흰(법인)·노랑/검정(영업용) 등
//   바탕색이 다양해 "밝은 사각형"으로 찾으면 파랑·녹색을 놓친다. 네 종류 모두 바탕↔글자 명암 대비는 강하므로
//   NV21 Y(휘도) 평면만으로 글자 획의 세로 경계(가로 방향 에지)가 밀집한 가로띠를 찾는다(색 변환 불필요).
//   ★판정은 항상 같은 차의 plateW(now)/plateW(base) 비율만 쓴다 — 절대 mm나 번호판 종류(신형/구형) 식별이
//   필요 없으므로 Plate에 종류 필드를 두지 않는다(오분류 리스크만 생김). 순수 Java, android/native 의존 0.
public final class PlateTracker {
    private PlateTracker() {}

    public static final class Plate {
        public float cx, cy;   // 프레임 정규화(0~1)
        public float wNorm;    // 프레임 폭 대비 번호판 폭(0~1)
        public float score;
    }

    // 튜닝 파라미터
    private static final int DS_W = 96, DS_H = 48;
    private static final float EDGE_THRESH = 14f;         // Y(0~255) 이웃 픽셀 차 임계 — 글자 획 경계
    private static final float ROW_DENSITY_MIN = 0.05f;   // 띠로 인정할 최소 가로 에지 밀도(검색영역 전체 폭 대비 — 번호판이 폭 일부만 차지해 희석됨을 감안)
    private static final float COL_DENSITY_FRAC = 0.30f;  // 열 경계 = 피크 밀도의 30% 이상인 연속 구간
    private static final float BAND_MIN_H_FRAC = 0.06f, BAND_MAX_H_FRAC = 0.40f;   // 검색영역 높이 대비 띠 높이(2단 병합 포함)
    private static final float ASPECT_MIN = 1.8f, ASPECT_MAX = 6.0f;               // 구형 2.2:1 ~ 신형 4.7:1
    private static final float WIDTH_VS_VEHICLE_MIN = 0.12f, WIDTH_VS_VEHICLE_MAX = 0.70f;
    private static final float BAND_CONTRAST_MIN = 2.0f;   // 띠 내부 에지 밀도가 주변보다 최소 이 배수는 높아야

    // 차량 bbox(픽셀, vx,vy,vw,vh) 하단 45%·중앙 70% 영역만 검색 → Y평면에서 번호판 후보 1개(최고 score)를 반환. 없으면 null.
    public static Plate find(byte[] nv21, int srcW, int srcH, int vx, int vy, int vw, int vh) {
        if (nv21 == null || vw <= 0 || vh <= 0 || srcW <= 0 || srcH <= 0) return null;
        int sx0 = vx + Math.round(vw * 0.15f);
        int sw0 = Math.round(vw * 0.70f);
        int sy0 = vy + Math.round(vh * 0.55f);
        int sh0 = Math.round(vh * 0.45f);
        int x0 = Math.max(0, sx0), y0 = Math.max(0, sy0);
        int x1 = Math.min(srcW, sx0 + sw0), y1 = Math.min(srcH, sy0 + sh0);
        int sw = x1 - x0, sh = y1 - y0;
        if (sw < 8 || sh < 4) return null;
        int sx = x0, sy = y0;

        // Y 그리드 리샘플(최근접) — NV21은 앞 srcW*srcH 바이트가 Y평면.
        float[][] ys = new float[DS_H][DS_W];
        for (int gy = 0; gy < DS_H; gy++) {
            int py = sy + (gy * sh) / DS_H; if (py >= srcH) py = srcH - 1;
            for (int gx = 0; gx < DS_W; gx++) {
                int px = sx + (gx * sw) / DS_W; if (px >= srcW) px = srcW - 1;
                ys[gy][gx] = nv21[py * srcW + px] & 0xff;
            }
        }

        // 가로 방향 에지(이웃 열 차) → 행별 밀도 프로파일
        float[] rowDensity = new float[DS_H];
        for (int gy = 0; gy < DS_H; gy++) {
            int cnt = 0;
            for (int gx = 0; gx < DS_W - 1; gx++) if (Math.abs(ys[gy][gx + 1] - ys[gy][gx]) >= EDGE_THRESH) cnt++;
            rowDensity[gy] = cnt / (float) (DS_W - 1);
        }

        // 고밀도 행 구간(띠) 수집
        java.util.List<int[]> bands = new java.util.ArrayList<>();   // [start,end] inclusive
        int bs = -1;
        for (int gy = 0; gy < DS_H; gy++) {
            boolean hot = rowDensity[gy] >= ROW_DENSITY_MIN;
            if (hot && bs < 0) bs = gy;
            else if (!hot && bs >= 0) { bands.add(new int[]{bs, gy - 1}); bs = -1; }
        }
        if (bs >= 0) bands.add(new int[]{bs, DS_H - 1});
        if (bands.isEmpty()) return null;

        // ★2단 번호판(구형: 윗줄 지역명·아랫줄 숫자) 병합 — 갭이 자기 높이 이하인 띠는 하나로 합친다.
        java.util.List<int[]> merged = new java.util.ArrayList<>();
        int[] cur = bands.get(0);
        for (int i = 1; i < bands.size(); i++) {
            int[] nx = bands.get(i);
            int gap = nx[0] - cur[1] - 1;
            int curH = cur[1] - cur[0] + 1;
            if (gap <= curH) cur = new int[]{cur[0], nx[1]};
            else { merged.add(cur); cur = nx; }
        }
        merged.add(cur);

        Plate best = null; float bestScore = -1f;
        for (int[] b : merged) {
            int bandH = b[1] - b[0] + 1;
            float bandHFrac = bandH / (float) DS_H;
            if (bandHFrac < BAND_MIN_H_FRAC || bandHFrac > BAND_MAX_H_FRAC) continue;

            // 띠 내부 열별 에지 밀도(좌우 경계 = 병합된 사각형 기준)
            float[] colDensity = new float[DS_W - 1];
            float peak = 0f;
            for (int gx = 0; gx < DS_W - 1; gx++) {
                int cnt = 0;
                for (int gy = b[0]; gy <= b[1]; gy++) if (Math.abs(ys[gy][gx + 1] - ys[gy][gx]) >= EDGE_THRESH) cnt++;
                colDensity[gx] = cnt / (float) bandH;
                if (colDensity[gx] > peak) peak = colDensity[gx];
            }
            if (peak <= 0f) continue;
            // ★가로 줄무늬(문자 획)는 전환 경계에서만 에지가 잡혀 '듬성듬성'한 패턴이 된다(연속 고밀도 구간이 아님).
            //   peak 근처 연속 구간이 아니라, 임계를 넘는 모든 열의 최소~최대 인덱스로 번호판 폭을 잡는다.
            float colThresh = Math.max(0.4f, peak * COL_DENSITY_FRAC);
            int left = -1, right = -1;
            for (int gx = 0; gx < DS_W - 1; gx++) {
                if (colDensity[gx] >= colThresh) { if (left < 0) left = gx; right = gx; }
            }
            if (left < 0) continue;
            int colSpan = right - left + 1;

            // 띠 내부 밀도 vs 주변(위/아래 각 최대 2행) 밀도 대비
            float outside = 0f; int outN = 0;
            for (int gy = Math.max(0, b[0] - 2); gy < b[0]; gy++) { outside += rowDensity[gy]; outN++; }
            for (int gy = b[1] + 1; gy <= Math.min(DS_H - 1, b[1] + 2); gy++) { outside += rowDensity[gy]; outN++; }
            float outsideAvg = outN > 0 ? outside / outN : 0f;
            float bandAvg = 0f; for (int gy = b[0]; gy <= b[1]; gy++) bandAvg += rowDensity[gy]; bandAvg /= bandH;
            float contrast = bandAvg / Math.max(0.02f, outsideAvg);
            if (contrast < BAND_CONTRAST_MIN) continue;

            // 실제 픽셀 치수로 검증(종횡비·차량폭比) — 다운샘플 그리드 왜곡과 무관하게 검색영역 실치수 기준.
            float widthPx = colSpan / (float) (DS_W - 1) * sw;
            float heightPx = bandHFrac * sh;
            if (heightPx < 1f) continue;
            float aspect = widthPx / heightPx;
            if (aspect < ASPECT_MIN || aspect > ASPECT_MAX) continue;
            float widthVsVeh = widthPx / (float) vw;
            if (widthVsVeh < WIDTH_VS_VEHICLE_MIN || widthVsVeh > WIDTH_VS_VEHICLE_MAX) continue;

            float score = bandAvg * contrast;
            if (score > bestScore) {
                bestScore = score;
                Plate p = new Plate();
                float cxPx = sx + (left + right + 1) / 2f / (DS_W - 1) * sw;
                float cyPx = sy + (b[0] + b[1] + 1) / 2f / (float) DS_H * sh;
                p.cx = cxPx / srcW;
                p.cy = cyPx / srcH;
                p.wNorm = widthPx / srcW;
                p.score = score;
                best = p;
            }
        }
        return best;
    }
}
