#include "yuv.h"
#include <cstddef>
#include <cmath>

static inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

void extractYRoiDownsample(const uint8_t* nv21, int srcW, int srcH,
                           int rx, int ry, int rw, int rh,
                           int dstW, int dstH, uint8_t* dst) {
    if (!nv21 || !dst || srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return;
    // ROI를 프레임 경계 안으로 클램프.
    int x0 = clampi(rx, 0, srcW - 1);
    int y0 = clampi(ry, 0, srcH - 1);
    int x1 = clampi(rx + rw, x0 + 1, srcW);   // exclusive
    int y1 = clampi(ry + rh, y0 + 1, srcH);
    const int roiW = x1 - x0;
    const int roiH = y1 - y0;

    for (int dy = 0; dy < dstH; ++dy) {
        // 이 출력행이 커버하는 소스 행 범위 [sy0, sy1).
        int sy0 = y0 + (int)((long)dy * roiH / dstH);
        int sy1 = y0 + (int)((long)(dy + 1) * roiH / dstH);
        if (sy1 <= sy0) sy1 = sy0 + 1;
        if (sy1 > y1) sy1 = y1;
        for (int dx = 0; dx < dstW; ++dx) {
            int sx0 = x0 + (int)((long)dx * roiW / dstW);
            int sx1 = x0 + (int)((long)(dx + 1) * roiW / dstW);
            if (sx1 <= sx0) sx1 = sx0 + 1;
            if (sx1 > x1) sx1 = x1;

            unsigned sum = 0, cnt = 0;
            for (int sy = sy0; sy < sy1; ++sy) {
                const uint8_t* row = nv21 + (size_t)sy * srcW;
                for (int sx = sx0; sx < sx1; ++sx) { sum += row[sx]; ++cnt; }
            }
            dst[(size_t)dy * dstW + dx] = cnt ? (uint8_t)(sum / cnt) : 0;
        }
    }
}

// NV21 ROI → RGB 리사이즈(nearest). 고정소수(65536) BT.601 풀레인지.
void extractRgbRoi(const uint8_t* nv21, int srcW, int srcH,
                   int rx, int ry, int rw, int rh,
                   int dstW, int dstH, uint8_t* rgbOut) {
    if (!nv21 || !rgbOut || srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return;
    int x0 = clampi(rx, 0, srcW - 1);
    int y0 = clampi(ry, 0, srcH - 1);
    int x1 = clampi(rx + rw, x0 + 1, srcW);   // exclusive
    int y1 = clampi(ry + rh, y0 + 1, srcH);
    const int roiW = x1 - x0;
    const int roiH = y1 - y0;
    const uint8_t* yP = nv21;
    const uint8_t* vuP = nv21 + (size_t)srcW * srcH;   // NV21: V,U 인터리브
    for (int dy = 0; dy < dstH; ++dy) {
        int sy = y0 + (int)(((long)dy * roiH + roiH / 2) / dstH);
        if (sy >= y1) sy = y1 - 1;
        int cyRow = (sy >> 1) * srcW;   // 크로마 행 오프셋(스트라이드=srcW 바이트)
        const uint8_t* yRow = yP + (size_t)sy * srcW;
        for (int dx = 0; dx < dstW; ++dx) {
            int sx = x0 + (int)(((long)dx * roiW + roiW / 2) / dstW);
            if (sx >= x1) sx = x1 - 1;
            int Y = yRow[sx];
            size_t vuIdx = (size_t)cyRow + ((sx >> 1) << 1);
            int V = vuP[vuIdx];
            int U = vuP[vuIdx + 1];
            int cV = V - 128, cU = U - 128;
            int R = Y + ((91881 * cV) >> 16);                  // 1.402
            int G = Y - ((22554 * cU + 46802 * cV) >> 16);     // 0.344, 0.714
            int B = Y + ((116130 * cU) >> 16);                 // 1.772
            size_t o = ((size_t)dy * dstW + dx) * 3;
            rgbOut[o]     = (uint8_t)clampi(R, 0, 255);
            rgbOut[o + 1] = (uint8_t)clampi(G, 0, 255);
            rgbOut[o + 2] = (uint8_t)clampi(B, 0, 255);
        }
    }
}

// ---------------------------------------------------------------------------
// ★de-warp — 등거리 어안 → rectilinear (DEWARP-00-PLAN §4.2)
// 좌표계: 이미지 x=우, y=하, z=광축(전방). 우손 좌표계(x×y=z).
// ---------------------------------------------------------------------------
// 풀프레임 픽셀 → 단위 방향벡터. 렌즈 모델 r = f·(θ + k·θ³)(★712 k=0이면 종전 등거리 θ=r/f) — θ는 뉴턴법 4회.
//   cluster.html renderCamPrev의 dir()·FishCalib.makeDir와 같은 식.
static inline void fishUnitDir(float px, float py, float cx, float cy, float fFish, float k, float out[3]) {
    float dx = px - cx, dy = py - cy;
    float r = sqrtf(dx * dx + dy * dy);
    if (r < 1e-6f) { out[0] = 0.f; out[1] = 0.f; out[2] = 1.f; return; }
    float rho = r / fFish, theta = rho;
    if (k != 0.f) for (int it = 0; it < 4; ++it) theta -= (theta + k * theta * theta * theta - rho) / (1.f + 3.f * k * theta * theta);
    float st = sinf(theta);
    out[0] = st * dx / r; out[1] = st * dy / r; out[2] = cosf(theta);
}
static inline float dot3(const float a[3], const float b[3]) { return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]; }
static inline void cross3(const float a[3], const float b[3], float o[3]) {
    o[0] = a[1]*b[2] - a[2]*b[1]; o[1] = a[2]*b[0] - a[0]*b[2]; o[2] = a[0]*b[1] - a[1]*b[0];
}
static inline bool norm3(float v[3]) {
    float n = sqrtf(dot3(v, v)); if (n < 1e-6f) return false;
    v[0] /= n; v[1] /= n; v[2] /= n; return true;
}

DewarpLut* buildFisheyeLut(int srcW, int srcH, int roiX, int roiY, int roiW, int roiH,
                            float fovDeg, float cx, float cy, int dstW, int dstH, float rollDeg, bool pano, float tiltDeg, float k) {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0 || roiW <= 0 || roiH <= 0) return nullptr;
    if (fovDeg < 30.f) fovDeg = 30.f;
    if (fovDeg > 200.f) fovDeg = 200.f;
    const float PI = 3.14159265358979f;
    // 등거리 초점거리: 이미지 서클 반경 R_max가 FOV/2에 대응(원형 서클 가정, 시각검증 대상)
    const float rMax = 0.5f * (float)(srcW < srcH ? srcW : srcH);
    const float fFish = rMax / (0.5f * fovDeg * PI / 180.f);

    // 0) ROI 중심 방향 = 가상카메라 광축(ez). 이 회전이 없으면 ROI를 무시하고 이미지 중앙을 펴게 됨.
    float ez[3]; fishUnitDir(roiX + roiW * 0.5f, roiY + roiH * 0.5f, cx, cy, fFish, k, ez);
    // ROI 4모서리가 커버해야 하는 반각(halfFov) → 확대율 f_rect 역산(손튜닝 아님)
    float halfFov = 0.f;
    const float cxs[4] = {(float)roiX, (float)(roiX + roiW), (float)roiX, (float)(roiX + roiW)};
    const float cys[4] = {(float)roiY, (float)roiY, (float)(roiY + roiH), (float)(roiY + roiH)};
    for (int i = 0; i < 4; ++i) {
        float dc[3]; fishUnitDir(cxs[i], cys[i], cx, cy, fFish, k, dc);
        float c = dot3(ez, dc); if (c > 1.f) c = 1.f; if (c < -1.f) c = -1.f;
        float a = acosf(c); if (a > halfFov) halfFov = a;
    }
    if (halfFov < 2.f * PI / 180.f) halfFov = 2.f * PI / 180.f;
    if (halfFov > 80.f * PI / 180.f) halfFov = 80.f * PI / 180.f;   // rectilinear는 90° 미만만 가능
    const float fRect = (0.5f * (float)dstW) / tanf(halfFov);

    // 가상카메라 기저: ex≈이미지+x(우), ey≈이미지+y(하), ez=ROI방향. down 힌트로 우손 기저 구성.
    //   검증: ez=(0,0,1)이면 ex=(1,0,0), ey=(0,1,0) = 항등(광축 중심 ROI일 때 기존 크롭과 동일 방향).
    float down[3] = {0.f, 1.f, 0.f};
    float ex[3]; cross3(down, ez, ex);
    if (!norm3(ex)) { float alt[3] = {1.f, 0.f, 0.f}; cross3(alt, ez, ex); norm3(ex); }   // ez∥down 퇴화 방어
    float ey[3]; cross3(ez, ex, ey); norm3(ey);
    // ★656 기울기(운영자 09-29 "사각형도 회전") — 광축(ez) 둘레로 가상카메라를 rollDeg 돌린다. 타일 한쪽 절반처럼 광축에서 벗어난
    //   영역은 피치+요 조합 탓에 수평선이 기울어 보이므로 사용자가 슬라이더로 맞춘다(자동 수평은 장착 각도를 알아야 해 미도입).
    //   cluster.html renderCamPrev의 JS와 같은 식(ex'=cos·ex+sin·ey, ey'=-sin·ex+cos·ey).
    if (rollDeg != 0.f) {
        const float cr = cosf(rollDeg * PI / 180.f), sr = sinf(rollDeg * PI / 180.f);
        float ex2[3], ey2[3];
        for (int i = 0; i < 3; ++i) { ex2[i] = cr * ex[i] + sr * ey[i]; ey2[i] = -sr * ex[i] + cr * ey[i]; }
        for (int i = 0; i < 3; ++i) { ex[i] = ex2[i]; ey[i] = ey2[i]; }
    }

    // ★664 원통 파노라마(운영자 09-30 "후진할 때 좌우후가 실제와 같이 이어져 보이게") — 직선 투영은 넓은 영역에서 가장자리가 무한히 늘어나므로,
    //   가로는 광축 둘레 각도(α)에 비례해 균등하게 펴고 세로는 원통 높이(tan)로 편다. 가로 폭은 ROI 좌·우 가장자리 중점이 광축에서 벌어진 각도로 정한다.
    //   기저(ex,ey,ez)·기울기(roll)는 위와 같아 ±180° 회전도 된다. cluster.html renderCamPrev의 JS와 같은 식.
    if (pano) {
        float hs = 0.f;
        const float mx[2] = {(float)roiX, (float)(roiX + roiW)};
        for (int i = 0; i < 2; ++i) {
            float dc[3]; fishUnitDir(mx[i], roiY + roiH * 0.5f, cx, cy, fFish, k, dc);
            float c = dot3(ez, dc); if (c > 1.f) c = 1.f; if (c < -1.f) c = -1.f;
            float a = acosf(c); if (a > hs) hs = a;
        }
        float hspan = 2.f * hs;
        if (hspan < 10.f * PI / 180.f) hspan = 10.f * PI / 180.f;
        if (hspan > 190.f * PI / 180.f) hspan = 190.f * PI / 180.f;
        const float fCyl = (float)dstW / hspan;
        // ROI 가로 폭(hspan)은 원래 광축 기준으로 잰 뒤에 기울인다(기울인 광축으로 재면 폭이 달라짐).
        // 위아래 각도(tilt): 후방 카메라는 아래로 기울어 달려 있어 그대로 펴면 수평선이 휘어 보인다 → 가상카메라를 ex 둘레로 위로 tiltDeg 든다.
        if (tiltDeg != 0.f) {
            const float cp = cosf(tiltDeg * PI / 180.f), sp = sinf(tiltDeg * PI / 180.f);
            float ezN[3], eyN[3];
            for (int i = 0; i < 3; ++i) { ezN[i] = ez[i] * cp - ey[i] * sp; eyN[i] = ey[i] * cp + ez[i] * sp; }
            for (int i = 0; i < 3; ++i) { ez[i] = ezN[i]; ey[i] = eyN[i]; }
        }
        DewarpLut* pl = new DewarpLut();
        pl->dstW = dstW; pl->dstH = dstH;
        pl->srcX = new int16_t[(size_t)dstW * dstH];
        pl->srcY = new int16_t[(size_t)dstW * dstH];
        const float phw = 0.5f * (float)dstW, phh = 0.5f * (float)dstH;
        for (int dy = 0; dy < dstH; ++dy) {
            float yn = ((float)dy + 0.5f - phh) / fCyl;
            for (int dx = 0; dx < dstW; ++dx) {
                float al = ((float)dx + 0.5f - phw) / fCyl;
                float v[3] = {sinf(al), yn, cosf(al)}; norm3(v);
                float w[3] = { ex[0]*v[0] + ey[0]*v[1] + ez[0]*v[2],
                               ex[1]*v[0] + ey[1]*v[1] + ez[1]*v[2],
                               ex[2]*v[0] + ey[2]*v[1] + ez[2]*v[2] };
                float cz = w[2]; if (cz > 1.f) cz = 1.f; if (cz < -1.f) cz = -1.f;
                float theta = acosf(cz);
                float phi = atan2f(w[1], w[0]);
                float rs = fFish * (theta + k * theta * theta * theta);   // ★712 렌즈 왜곡 k
                int sx = clampi((int)lroundf(cx + rs * cosf(phi)), 0, srcW - 1);
                int sy = clampi((int)lroundf(cy + rs * sinf(phi)), 0, srcH - 1);
                size_t i = (size_t)dy * dstW + dx;
                pl->srcX[i] = (int16_t)sx; pl->srcY[i] = (int16_t)sy;
            }
        }
        return pl;
    }

    DewarpLut* lut = new DewarpLut();
    lut->dstW = dstW; lut->dstH = dstH;
    lut->srcX = new int16_t[(size_t)dstW * dstH];
    lut->srcY = new int16_t[(size_t)dstW * dstH];
    const float hw = 0.5f * (float)dstW, hh = 0.5f * (float)dstH;
    for (int dy = 0; dy < dstH; ++dy) {
        float yn = ((float)dy + 0.5f - hh) / fRect;
        for (int dx = 0; dx < dstW; ++dx) {
            float xn = ((float)dx + 0.5f - hw) / fRect;
            float v[3] = {xn, yn, 1.f}; norm3(v);                   // 가상카메라 ray
            float w[3] = { ex[0]*v[0] + ey[0]*v[1] + ez[0]*v[2],     // 렌즈 좌표계로 회전 (R·v)
                           ex[1]*v[0] + ey[1]*v[1] + ez[1]*v[2],
                           ex[2]*v[0] + ey[2]*v[1] + ez[2]*v[2] };
            float cz = w[2]; if (cz > 1.f) cz = 1.f; if (cz < -1.f) cz = -1.f;
            float theta = acosf(cz);                                 // 렌즈 광축과의 각도
            float phi = atan2f(w[1], w[0]);
            float rs = fFish * (theta + k * theta * theta * theta);  // 순방향: r = f·(θ + k·θ³)(★712, k=0이면 등거리 r = f·θ)
            int sx = clampi((int)lroundf(cx + rs * cosf(phi)), 0, srcW - 1);
            int sy = clampi((int)lroundf(cy + rs * sinf(phi)), 0, srcH - 1);
            size_t i = (size_t)dy * dstW + dx;
            lut->srcX[i] = (int16_t)sx; lut->srcY[i] = (int16_t)sy;
        }
    }
    return lut;
}

// ★718 가로 직선(가로축 원통·등각, 운영자 10-04 "파노라마가 제일 나은데 V자가 —— 직선이 되도록·범퍼 라인까지 보이게").
//   원통 파노라마(★664)는 세로축 둘레로 감아 먼 벽만 곧고 아래쪽 가까운 선(범퍼·주차선)은 V자로 휜다. 여기선 카메라 x축(차 좌우축,
//   기울기 rollDeg만큼 돌린 축)을 축으로 감는다 — 가로 위치=좌우각 α(x 성분의 asin), 세로 위치=x축 둘레 회전각 β.
//   땅 위 가로선(범퍼·주차 뒷선·벽 띠·벽과 바닥 경계)은 β가 높이·깊이로만 정해져 곧은 가로선이 되고, 세로는 각도 그대로라 위 벽부터 아래 범퍼까지 한 화면.
//   대신 화면 좌우 끝의 세로 기둥은 안쪽으로 휜다. 영역: 좌·우 가장자리 중점의 α가 가로 범위, 위·아래 가장자리 중점의 β가 세로 범위.
//   tiltDeg=세로 창을 위로 옮김(도). 출력 비율이 각도 범위 비와 다르면 가운데 기준으로 넘치는 쪽을 잘라 맞춘다(늘이지 않음, 비율은 dewarpAspectT).
//   워커 /camrtc 페이지 셰이더(pano=2)와 같은 식.
static float fishFocal(int srcW, int srcH, float fovDeg) {
    if (fovDeg < 30.f) fovDeg = 30.f;
    if (fovDeg > 200.f) fovDeg = 200.f;
    return 0.5f * (float)(srcW < srcH ? srcW : srcH) / (0.5f * fovDeg * 3.14159265358979f / 180.f);
}
static inline float clamp1(float v) { return v > 1.f ? 1.f : (v < -1.f ? -1.f : v); }
static void transverseRange(int roiX, int roiY, int roiW, int roiH, float cx, float cy, float fFish, float k, float cr, float sr, float g[4]) {
    const float ex[3] = {cr, sr, 0.f}, ey[3] = {-sr, cr, 0.f};
    float d[3];
    fishUnitDir((float)roiX, roiY + roiH * 0.5f, cx, cy, fFish, k, d);            g[0] = asinf(clamp1(dot3(d, ex)));
    fishUnitDir((float)(roiX + roiW), roiY + roiH * 0.5f, cx, cy, fFish, k, d);   g[1] = asinf(clamp1(dot3(d, ex)));
    fishUnitDir(roiX + roiW * 0.5f, (float)roiY, cx, cy, fFish, k, d);            g[2] = atan2f(dot3(d, ey), d[2]);
    fishUnitDir(roiX + roiW * 0.5f, (float)(roiY + roiH), cx, cy, fFish, k, d);   g[3] = atan2f(dot3(d, ey), d[2]);
}
float dewarpAspectT(int srcW, int srcH, int roiX, int roiY, int roiW, int roiH, float fovDeg, float cx, float cy, float rollDeg, float k) {
    if (srcW <= 0 || srcH <= 0 || roiW <= 0 || roiH <= 0) return 1.f;
    const float r = rollDeg * 3.14159265358979f / 180.f;
    float g[4]; transverseRange(roiX, roiY, roiW, roiH, cx, cy, fishFocal(srcW, srcH, fovDeg), k, cosf(r), sinf(r), g);
    const float a = fabsf(g[1] - g[0]), b = fabsf(g[3] - g[2]);
    return a < 1e-3f ? 1.f : b / a;
}
static DewarpLut* transverseFill(int srcW, int srcH, float fFish, float cx, float cy, float cr, float sr, float k,
                                 float a0, float A, float b0, float B, int dstW, int dstH);
DewarpLut* buildFisheyeLutT(int srcW, int srcH, int roiX, int roiY, int roiW, int roiH,
                             float fovDeg, float cx, float cy, int dstW, int dstH, float rollDeg, float tiltDeg, float k) {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0 || roiW <= 0 || roiH <= 0) return nullptr;
    const float PI = 3.14159265358979f;
    const float fFish = fishFocal(srcW, srcH, fovDeg);
    const float cr = cosf(rollDeg * PI / 180.f), sr = sinf(rollDeg * PI / 180.f);
    float g[4]; transverseRange(roiX, roiY, roiW, roiH, cx, cy, fFish, k, cr, sr, g);
    const float t = tiltDeg * PI / 180.f; g[2] -= t; g[3] -= t;
    float A = g[1] - g[0], B = g[3] - g[2];
    if (fabsf(A) < 1e-3f) A = A < 0.f ? -1e-3f : 1e-3f;
    const float asp = fabsf(B / A), want = (float)dstH / (float)dstW;
    if (asp < want) A *= asp / want; else if (asp > want) B *= want / asp;
    const float a0 = 0.5f * (g[0] + g[1]) - 0.5f * A, b0 = 0.5f * (g[2] + g[3]) - 0.5f * B;
    return transverseFill(srcW, srcH, fFish, cx, cy, cr, sr, k, a0, A, b0, B, dstW, dstH);
}
// 가로 직선 표 채우기 — α = a0 + (dx+0.5)/dstW·A, β = b0 + (dy+0.5)/dstH·B (라디안). buildFisheyeLutT·buildFisheyeLutTWin 공용.
static DewarpLut* transverseFill(int srcW, int srcH, float fFish, float cx, float cy, float cr, float sr, float k,
                                 float a0, float A, float b0, float B, int dstW, int dstH) {
    DewarpLut* lut = new DewarpLut();
    lut->dstW = dstW; lut->dstH = dstH;
    lut->srcX = new int16_t[(size_t)dstW * dstH];
    lut->srcY = new int16_t[(size_t)dstW * dstH];
    for (int dy = 0; dy < dstH; ++dy) {
        const float be = b0 + ((float)dy + 0.5f) / (float)dstH * B;
        const float sb = sinf(be), cb = cosf(be);
        for (int dx = 0; dx < dstW; ++dx) {
            const float al = a0 + ((float)dx + 0.5f) / (float)dstW * A;
            const float sa = sinf(al), ca = cosf(al);
            const float v1 = ca * sb;
            const float w0 = cr * sa - sr * v1, w1 = sr * sa + cr * v1, w2 = ca * cb;   // ex·sinα + ey·cosα·sinβ + ez·cosα·cosβ
            const float theta = acosf(clamp1(w2));
            const float phi = atan2f(w1, w0);
            const float rs = fFish * (theta + k * theta * theta * theta);
            int sx = clampi((int)lroundf(cx + rs * cosf(phi)), 0, srcW - 1);
            int sy = clampi((int)lroundf(cy + rs * sinf(phi)), 0, srcH - 1);
            size_t i = (size_t)dy * dstW + dx;
            lut->srcX[i] = (int16_t)sx; lut->srcY[i] = (int16_t)sy;
        }
    }
    return lut;
}

// ★719 후진 파노라마(좌·후·우 이어 붙이기, 운영자 10-04) — 이음새를 맞추려면 칸마다 같은 각 축척이어야 해서 영역 대신 각도 창을 직접 받는다.
//   후방 칸: 가로 직선을 좌우 a0~a1°·위아래 b0~b1°(β, 아래 +) 창으로.
DewarpLut* buildFisheyeLutTWin(int srcW, int srcH, float fovDeg, float cx, float cy, float rollDeg, float k,
                               float a0Deg, float a1Deg, float b0Deg, float b1Deg, int dstW, int dstH) {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return nullptr;
    const float D = 3.14159265358979f / 180.f;
    return transverseFill(srcW, srcH, fishFocal(srcW, srcH, fovDeg), cx, cy, cosf(rollDeg * D), sinf(rollDeg * D), k,
                          a0Deg * D, (a1Deg - a0Deg) * D, b0Deg * D, (b1Deg - b0Deg) * D, dstW, dstH);
}
void freeFisheyeLut(DewarpLut* lut) {
    if (!lut) return;
    delete[] lut->srcX; delete[] lut->srcY; delete lut;
}

// LUT 조회 + extractRgbRoi와 동일한 NV21 Y/VU 인덱싱·BT.601 변환(nearest).
void remapRgbWithLut(const uint8_t* nv21, int srcW, int srcH, const DewarpLut* lut, uint8_t* rgbOut) {
    if (!nv21 || !rgbOut || !lut || !lut->srcX || !lut->srcY || srcW <= 0 || srcH <= 0) return;
    const uint8_t* yP = nv21;
    const uint8_t* vuP = nv21 + (size_t)srcW * srcH;
    const int n = lut->dstW * lut->dstH;
    for (int i = 0; i < n; ++i) {
        int sx = lut->srcX[i], sy = lut->srcY[i];
        if (sx < 0) sx = 0; if (sx >= srcW) sx = srcW - 1;          // LUT은 빌드 해상도 기준 — 다른 해상도 프레임 방어
        if (sy < 0) sy = 0; if (sy >= srcH) sy = srcH - 1;
        int Y = yP[(size_t)sy * srcW + sx];
        size_t vuIdx = (size_t)((sy >> 1) * srcW) + ((sx >> 1) << 1);
        int V = vuP[vuIdx], U = vuP[vuIdx + 1];
        int cV = V - 128, cU = U - 128;
        int R = Y + ((91881 * cV) >> 16);
        int G = Y - ((22554 * cU + 46802 * cV) >> 16);
        int B = Y + ((116130 * cU) >> 16);
        size_t o = (size_t)i * 3;
        rgbOut[o]     = (uint8_t)clampi(R, 0, 255);
        rgbOut[o + 1] = (uint8_t)clampi(G, 0, 255);
        rgbOut[o + 2] = (uint8_t)clampi(B, 0, 255);
    }
}

// ★617 클러스터 측면캠 커스텀(운영자 09-27 "어디를 어떻게 보여줄지 커스텀 + 어안보정")
DewarpLut* buildCropLut(int roiX, int roiY, int roiW, int roiH, int dstW, int dstH, float rollDeg, int clampW, int clampH) {
    if (roiW <= 0 || roiH <= 0 || dstW <= 0 || dstH <= 0) return nullptr;
    DewarpLut* lut = new DewarpLut();
    lut->dstW = dstW; lut->dstH = dstH;
    lut->srcX = new int16_t[(size_t)dstW * dstH];
    lut->srcY = new int16_t[(size_t)dstW * dstH];
    if (rollDeg != 0.f) {   // ★656 기울기 — 영역 중심 둘레로 샘플 격자를 돌린다(보정 없는 크롭도 같은 슬라이더). 타일 밖은 가장자리로 클램프.
        const float PI = 3.14159265358979f;
        const float cr = cosf(rollDeg * PI / 180.f), sr = sinf(rollDeg * PI / 180.f);
        const float ccx = (float)roiX + 0.5f * (float)roiW, ccy = (float)roiY + 0.5f * (float)roiH;
        for (int dy = 0; dy < dstH; ++dy) {
            const float v = ((float)dy + 0.5f) * (float)roiH / (float)dstH - 0.5f * (float)roiH;
            for (int dx = 0; dx < dstW; ++dx) {
                const float u = ((float)dx + 0.5f) * (float)roiW / (float)dstW - 0.5f * (float)roiW;
                int sx = (int)floorf(ccx + cr * u - sr * v), sy = (int)floorf(ccy + sr * u + cr * v);
                if (clampW > 0) sx = clampi(sx, 0, clampW - 1);
                if (clampH > 0) sy = clampi(sy, 0, clampH - 1);
                size_t i = (size_t)dy * dstW + dx;
                lut->srcX[i] = (int16_t)sx; lut->srcY[i] = (int16_t)sy;
            }
        }
        return lut;
    }
    for (int dy = 0; dy < dstH; ++dy) {
        int sy = roiY + (int)(((long)dy * roiH) / dstH);
        for (int dx = 0; dx < dstW; ++dx) {
            size_t i = (size_t)dy * dstW + dx;
            lut->srcX[i] = (int16_t)(roiX + (int)(((long)dx * roiW) / dstW));
            lut->srcY[i] = (int16_t)sy;
        }
    }
    return lut;
}

void remapNv21WithLut(const uint8_t* nv21, int frameW, int frameH, int offX, int offY,
                      const DewarpLut* lut, int mirror, uint8_t* out) {
    if (!nv21 || !lut || !out) return;
    const int W = lut->dstW, H = lut->dstH;
    const uint8_t* vu = nv21 + (size_t)frameW * frameH;
    uint8_t* oy = out; uint8_t* ovu = out + (size_t)W * H;
    for (int dy = 0; dy < H; ++dy) {
        for (int dx = 0; dx < W; ++dx) {
            int lx = mirror ? (W - 1 - dx) : dx;
            size_t i = (size_t)dy * W + lx;
            int sx = clampi(offX + lut->srcX[i], 0, frameW - 1);
            int sy = clampi(offY + lut->srcY[i], 0, frameH - 1);
            oy[(size_t)dy * W + dx] = nv21[(size_t)sy * frameW + sx];
            if (((dx | dy) & 1) == 0) {   // 2x2 블록당 크로마 1쌍(VU)
                size_t c = (size_t)(sy >> 1) * frameW + (sx & ~1);
                size_t o = (size_t)(dy >> 1) * W + dx;
                ovu[o] = vu[c]; ovu[o + 1] = vu[c + 1];
            }
        }
    }
}

// ★733 헤드유닛 창 직접 그리기 — remapNv21WithLut과 같은 표 조회·반전, 색 변환은 remapRgbWithLut과 같은 BT.601 풀레인지(폰 JPEG와 같은 색).
void renderLutRgbx(const uint8_t* nv21, int frameW, int frameH, int offX, int offY, const DewarpLut* lut, int mirror,
                   uint32_t* dst, int dstStride, int dstX, int dstY) {
    if (lut) renderLutRgbxRows(nv21, frameW, frameH, offX, offY, lut, mirror, dst, dstStride, dstX, dstY, 0, lut->dstH);
}
void renderLutRgbxRows(const uint8_t* nv21, int frameW, int frameH, int offX, int offY, const DewarpLut* lut, int mirror,
                       uint32_t* dst, int dstStride, int dstX, int dstY, int y0, int y1) {
    if (!nv21 || !lut || !dst || !lut->srcX || !lut->srcY) return;
    const int W = lut->dstW, H = lut->dstH;
    const uint8_t* vu = nv21 + (size_t)frameW * frameH;
    if (y0 < 0) y0 = 0;
    if (y1 > H) y1 = H;
    // ★765 32×32칸 순서 — 측면 화면은 ±50° 돌아가 있어 출력 한 줄이 원본을 비스듬히 지나가 화소마다 새 캐시 줄을 읽었다
    //   (764 실측 펴기 23~30ms가 한 장의 대부분). 작은 칸 단위로 돌면 원본 읽는 범위가 좁아 캐시에 남는다. 화소 계산은 같고 순서만 다름
    //   (맥 시험: 결과 화소 단위 동일, 측면 2.36→1.61ms·반대 1.91→1.53·후방 1.78→1.43).
    const int TW = 32, TH = 32;
    for (int ty = y0; ty < y1; ty += TH) {
        const int ty1 = ty + TH < y1 ? ty + TH : y1;
        for (int tx = 0; tx < W; tx += TW) {
            const int tx1 = tx + TW < W ? tx + TW : W;
            for (int dy = ty; dy < ty1; ++dy) {
                uint32_t* row = dst + (size_t)(dstY + dy) * dstStride + dstX;
                const int16_t* lx = lut->srcX + (size_t)dy * W;
                const int16_t* ly = lut->srcY + (size_t)dy * W;
                for (int dx = tx; dx < tx1; ++dx) {
                    const int li = mirror ? (W - 1 - dx) : dx;
                    const int sx = clampi(offX + lx[li], 0, frameW - 1);
                    const int sy = clampi(offY + ly[li], 0, frameH - 1);
                    const int Y = nv21[(size_t)sy * frameW + sx];
                    const size_t c = (size_t)(sy >> 1) * frameW + (sx & ~1);
                    const int cV = vu[c] - 128, cU = vu[c + 1] - 128;
                    const int R = clampi(Y + ((91881 * cV) >> 16), 0, 255);
                    const int G = clampi(Y - ((22554 * cU + 46802 * cV) >> 16), 0, 255);
                    const int B = clampi(Y + ((116130 * cU) >> 16), 0, 255);
                    row[dx] = 0xFF000000u | ((uint32_t)B << 16) | ((uint32_t)G << 8) | (uint32_t)R;
                }
            }
        }
    }
}
