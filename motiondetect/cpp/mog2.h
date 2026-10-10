// MOG2 배경차감 (Zivkovic 2004) — grayscale(Y채널) 자체구현. OpenCV 무의존.
// 카메라 모션감지 모듈용. 일렉트로 MOG2BackgroundSubtractor 참조하되 표준 알고리즘 clean 재구현.
#ifndef MOTIONDETECT_MOG2_H
#define MOTIONDETECT_MOG2_H

#include <cstdint>
#include <vector>

// 픽셀당 가우시안 하나 (grayscale이라 mean/var 스칼라).
struct Gaussian {
    float weight;
    float mean;
    float var;
};

// 한 영역(ROI 다운샘플 프레임)에 대한 배경 모델. ROI마다 인스턴스 하나.
class Mog2 {
public:
    Mog2();
    // w,h = 이 모델이 처리할 프레임(다운샘플된 Y) 크기. 재호출 시 재할당.
    void initialize(int w, int h);
    void reset();

    void setLearningRate(float a)    { learningRate_ = a; }   // α (0~1). 클수록 배경 빨리 적응.
    void setVarThreshold(float t)    { varThreshold_ = t; }   // 매칭 판정 (마할라노비스², 보통 16 = 4σ).
    void setBackgroundRatio(float r) { backgroundRatio_ = r; }// 배경으로 볼 누적 weight (보통 0.9).
    // 새로 나타난 물체의 가우시안 초기 weight(기본 0.05). 크면 지나가는 물체가 1초 안에 배경으로 흡수돼 전경에서 사라진다.
    // 표준 MOG2는 학습률 수준(≈0.008)에서 시작한다 — 센트리가 지나가는 차를 놓친 원인(10-09 실영상: 3%↑ 판정 3번→34번).
    void setWeightInit(float w)      { weightInit_ = w; }

    // y = 프레임 Y플레인(w*h 바이트). 처리 후 내부 fgMask 갱신. 첫 호출들은 배경 학습.
    // mask != null이면 mask[p]!=0 픽셀만 전경 카운트(자유형 ROI 폴리곤). null이면 전체.
    // 반환: (마스크 내부) 전경 픽셀 비율(0~1).
    float apply(const uint8_t* y, const uint8_t* mask = nullptr);

    float foregroundPercentage() const;   // 마지막 apply의 전경 비율
    // 전경 픽셀 y좌표 가중평균(0~1, 0=위/1=아래). 전경 없으면 -1. (용도③ 근접판정)
    float motionVerticalCenter() const;

    // 마지막 apply의 전경 위치·크기 — out[0..3] = 무게중심 x·y, 전경 바운딩박스 폭·높이(모두 0~1, 프레임 대비). 전경이 없으면 false. 센트리 근접 미달 기록용.
    bool foregroundStats(float* out) const;

    int width()  const { return w_; }
    int height() const { return h_; }

private:
    int w_ = 0, h_ = 0;
    int nmixtures_ = 5;
    float learningRate_ = 0.01f;
    float varThreshold_ = 16.0f;
    float backgroundRatio_ = 0.9f;
    float varInit_ = 15.0f;    // 새 가우시안 초기 분산
    float varMin_ = 4.0f;
    float varMax_ = 75.0f;
    float weightInit_ = 0.05f; // 새 가우시안 초기 weight

    std::vector<Gaussian> models_;  // w*h*nmixtures
    std::vector<uint8_t> fgMask_;   // w*h (255=전경, 0=배경)
    bool primed_ = false;

    void ensureAllocated(int w, int h);
};

#endif
