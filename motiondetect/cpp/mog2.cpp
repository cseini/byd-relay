#include "mog2.h"
#include <algorithm>
#include <cmath>

Mog2::Mog2() {}

void Mog2::ensureAllocated(int w, int h) {
    if (w == w_ && h == h_ && (int)models_.size() == w * h * nmixtures_) return;
    w_ = w; h_ = h;
    models_.assign((size_t)w * h * nmixtures_, Gaussian{0.f, 0.f, varInit_});
    fgMask_.assign((size_t)w * h, 0);
    primed_ = false;
}

void Mog2::initialize(int w, int h) { ensureAllocated(w, h); primed_ = false; }

void Mog2::reset() {
    std::fill(models_.begin(), models_.end(), Gaussian{0.f, 0.f, varInit_});
    std::fill(fgMask_.begin(), fgMask_.end(), 0);
    primed_ = false;
}

float Mog2::apply(const uint8_t* y, const uint8_t* mask) {
    if (w_ <= 0 || h_ <= 0 || !y) return 0.f;
    const int nmix = nmixtures_;
    const float alpha = learningRate_;

    // 첫 프레임: 배경을 현재 프레임으로 프라이밍(각 픽셀 첫 가우시안=관측값, weight=1).
    // 이렇게 하면 초기 전면 전경 오탐을 피하고 즉시 감지 가능.
    if (!primed_) {
        for (int p = 0; p < w_ * h_; ++p) {
            Gaussian* g = &models_[(size_t)p * nmix];
            g[0] = Gaussian{1.f, (float)y[p], varInit_};
            for (int k = 1; k < nmix; ++k) g[k] = Gaussian{0.f, 0.f, varInit_};
        }
        std::fill(fgMask_.begin(), fgMask_.end(), 0);
        primed_ = true;
        return 0.f;
    }

    long fgCount = 0, maskTotal = 0;
    for (int p = 0; p < w_ * h_; ++p) {
        Gaussian* g = &models_[(size_t)p * nmix];
        const float x = (float)y[p];

        // 1) 매치 탐색 (weight>0 가우시안 중 마할라노비스² < varThreshold).
        int matchIdx = -1;
        for (int k = 0; k < nmix; ++k) {
            if (g[k].weight <= 0.f) continue;
            const float d = x - g[k].mean;
            if (d * d < varThreshold_ * g[k].var) { matchIdx = k; break; }
        }

        // 2) 갱신.
        if (matchIdx >= 0) {
            for (int k = 0; k < nmix; ++k) {
                if (k == matchIdx) {
                    const float w = g[k].weight;
                    const float rho = alpha / (w > 1e-4f ? w : 1e-4f); // 표준 근사 ρ=α/w
                    const float d = x - g[k].mean;
                    g[k].mean += rho * d;
                    g[k].var  += rho * (d * d - g[k].var);
                    if (g[k].var < varMin_) g[k].var = varMin_;
                    if (g[k].var > varMax_) g[k].var = varMax_;
                    g[k].weight = w + alpha * (1.f - w);
                } else {
                    g[k].weight *= (1.f - alpha);
                }
            }
        } else {
            // 미매치: 최약(가장 낮은 weight) 가우시안을 새 관측으로 교체.
            int weakest = 0;
            for (int k = 1; k < nmix; ++k) if (g[k].weight < g[weakest].weight) weakest = k;
            g[weakest] = Gaussian{weightInit_, x, varInit_};
            for (int k = 0; k < nmix; ++k) if (k != weakest) g[k].weight *= (1.f - alpha);
        }

        // 3) weight 정규화.
        float sum = 0.f;
        for (int k = 0; k < nmix; ++k) sum += g[k].weight;
        if (sum > 1e-6f) for (int k = 0; k < nmix; ++k) g[k].weight /= sum;

        // 4) weight/σ 내림차순 정렬(배경성 큰 것 먼저). nmix 작아 삽입정렬.
        for (int a = 1; a < nmix; ++a) {
            Gaussian key = g[a];
            const float ks = key.weight / std::sqrt(key.var > 1e-6f ? key.var : 1e-6f);
            int b = a - 1;
            while (b >= 0 && (g[b].weight / std::sqrt(g[b].var > 1e-6f ? g[b].var : 1e-6f)) < ks) {
                g[b + 1] = g[b]; --b;
            }
            g[b + 1] = key;
        }

        // 5) 전경 판정: 누적 weight가 backgroundRatio 넘기 전 가우시안들이 '배경'.
        //    x가 그 배경 가우시안 중 하나에 매치되면 배경, 아니면 전경.
        bool fg = true;
        float cum = 0.f;
        for (int k = 0; k < nmix; ++k) {
            if (g[k].weight <= 0.f) break;
            const float d = x - g[k].mean;
            if (d * d < varThreshold_ * g[k].var) { fg = false; break; } // 배경집합 내 매치
            cum += g[k].weight;
            if (cum >= backgroundRatio_) break; // 여기부터는 전경 가우시안 → 매치돼도 전경
        }

        // 마스크 밖은 전경에서 제외(자유형 ROI). fgMask도 마스크 내부 전경만 → vc/percentage 자동 정확.
        const bool inMask = (mask == nullptr) || (mask[p] != 0);
        fgMask_[p] = (fg && inMask) ? 255 : 0;
        if (inMask) { ++maskTotal; if (fg) ++fgCount; }
    }

    return maskTotal ? (float)fgCount / (float)maskTotal : 0.f;
}

float Mog2::foregroundPercentage() const {
    if (fgMask_.empty()) return 0.f;
    long c = 0;
    for (uint8_t m : fgMask_) if (m) ++c;
    return (float)c / (float)fgMask_.size();
}

bool Mog2::foregroundStats(float* out) const {
    if (w_ <= 0 || h_ <= 0 || fgMask_.empty()) return false;
    long c = 0; double sx = 0, sy = 0; int x0 = w_, y0 = h_, x1 = -1, y1 = -1;
    for (int r = 0; r < h_; ++r)
        for (int col = 0; col < w_; ++col)
            if (fgMask_[(size_t)r * w_ + col]) {
                ++c; sx += col; sy += r;
                if (col < x0) x0 = col; if (col > x1) x1 = col;
                if (r < y0) y0 = r; if (r > y1) y1 = r;
            }
    if (c == 0) return false;
    out[0] = (float)(sx / c) / (float)w_;
    out[1] = (float)(sy / c) / (float)h_;
    out[2] = (float)(x1 - x0 + 1) / (float)w_;
    out[3] = (float)(y1 - y0 + 1) / (float)h_;
    return true;
}

float Mog2::motionVerticalCenter() const {
    if (w_ <= 0 || h_ <= 0) return -1.f;
    long c = 0; double rowSum = 0;
    for (int r = 0; r < h_; ++r)
        for (int col = 0; col < w_; ++col)
            if (fgMask_[(size_t)r * w_ + col]) { ++c; rowSum += r; }
    if (c == 0) return -1.f;
    return (float)(rowSum / c) / (float)(h_ - 1);
}
