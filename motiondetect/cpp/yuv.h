// NV21 프레임 유틸 — 모션감지용 Y채널 ROI 추출 + 다운샘플. OpenCV 무의존.
// 카메라 onPreview는 NV21(Y plane + 인터리브 VU). 모션감지엔 밝기(Y)만 필요 → 크로마 무시.
#ifndef MOTIONDETECT_YUV_H
#define MOTIONDETECT_YUV_H

#include <cstdint>

// NV21의 Y플레인(앞 srcW*srcH 바이트)에서 ROI 사각형 영역을 dstW×dstH로 박스평균 다운샘플.
// rx,ry,rw,rh = 소스 픽셀좌표 ROI(경계 자동 클램프). dst = dstW*dstH 바이트(호출측 할당).
// 박스평균이라 다운샘플 시 에일리어싱/노이즈 감소 → MOG2 오탐 저감.
void extractYRoiDownsample(const uint8_t* nv21, int srcW, int srcH,
                           int rx, int ry, int rw, int rh,
                           int dstW, int dstH, uint8_t* dst);

// NV21 ROI → RGB(uint8, dstW*dstH*3) 크롭+리사이즈(TFLite 사람게이트/포즈용).
// nv21 = Y플레인(srcW*srcH) + VU 인터리브 크로마. BT.601 풀레인지 변환, [0,255] 클리핑.
// rgbOut = dstW*dstH*3 바이트(호출측 할당), RGB 순서. nearest 샘플링.
void extractRgbRoi(const uint8_t* nv21, int srcW, int srcH,
                   int rx, int ry, int rw, int rh,
                   int dstW, int dstH, uint8_t* rgbOut);

// ★de-warp(2026-09-03, docs/DEWARP-00-PLAN.md) — 등거리(equidistant, r=f·θ) 어안 → rectilinear 직선화.
//   ROI 중심 방향을 바라보는(off-axis) 가상카메라의 ray를 렌즈 좌표계로 회전해 원본 픽셀을 역매핑.
//   렌즈모델(cx,cy,fovDeg)은 항상 "풀프레임" 기준 — ROI는 가상카메라를 어느 쪽으로 돌리고 얼마나 확대할지만 결정.
//   LUT = destination 픽셀 → 원본 풀프레임 절대좌표(nearest, int16). ROI 확정 시 1회 생성(프레임 루프 밖).
struct DewarpLut { int dstW, dstH; int16_t* srcX; int16_t* srcY; };
DewarpLut* buildFisheyeLut(int srcW, int srcH, int roiX, int roiY, int roiW, int roiH,
                            float fovDeg, float cx, float cy, int dstW, int dstH, float rollDeg, bool pano = false, float tiltDeg = 0.f, float k = 0.f);   // ★656 rollDeg=기울기(광축 둘레 회전, 0=종전) ★664 pano=원통 파노라마(가로 각도 균등, 넓은 영역이 늘어나지 않음) ★712 k=렌즈 왜곡(r=f·(θ+kθ³), 0=등거리)
// ★718 가로 직선(차 좌우축 둘레 원통·등각) — 범퍼·주차선 같은 가로선이 높이와 무관하게 곧다. 출력 비율이 각도 범위 비와 다르면 가운데 기준으로 잘라 맞춤.
DewarpLut* buildFisheyeLutT(int srcW, int srcH, int roiX, int roiY, int roiW, int roiH,
                             float fovDeg, float cx, float cy, int dstW, int dstH, float rollDeg, float tiltDeg, float k);
float dewarpAspectT(int srcW, int srcH, int roiX, int roiY, int roiW, int roiH, float fovDeg, float cx, float cy, float rollDeg, float k);   // 세로 각도 범위 ÷ 가로 각도 범위
// ★719 각도 창 직접 지정 가로 직선 — 좌우 a0~a1°·위아래 b0~b1°(아래 +). ★734 후진 화면 A·B·C의 후방 칸.
DewarpLut* buildFisheyeLutTWin(int srcW, int srcH, float fovDeg, float cx, float cy, float rollDeg, float k,
                               float a0Deg, float a1Deg, float b0Deg, float b1Deg, int dstW, int dstH);
void freeFisheyeLut(DewarpLut* lut);
// LUT로 NV21 → RGB(dstW*dstH*3, 호출측 할당) 직선화 크롭. 크로마 인덱싱은 extractRgbRoi와 동일 패턴.
void remapRgbWithLut(const uint8_t* nv21, int srcW, int srcH, const DewarpLut* lut, uint8_t* rgbOut);
// ★617 클러스터 측면캠 커스텀 — 보정 없는 선형 크롭 LUT(동일 remap 경로 재사용) + NV21 출력 remap.
//   LUT 좌표는 '타일' 기준, remap 시 (offX,offY)만큼 풀프레임으로 평행이동(쿼드 4분할의 한 칸을 독립 어안으로 취급).
//   mirror=1이면 좌우 반전(사이드미러식). out = dstW*dstH*3/2(NV21, dstW·dstH 짝수).
DewarpLut* buildCropLut(int roiX, int roiY, int roiW, int roiH, int dstW, int dstH, float rollDeg, int clampW, int clampH);   // ★656 rollDeg=기울기, clampW/H=타일 크기(0=클램프 안 함)
void remapNv21WithLut(const uint8_t* nv21, int frameW, int frameH, int offX, int offY,
                      const DewarpLut* lut, int mirror, uint8_t* out);
// ★733 헤드유닛 창 직접 그리기 — LUT로 편 NV21을 RGBX(R,G,B,X 바이트 순) 창 버퍼의 (dstX,dstY)에 바로 쓴다(JPEG 없음).
//   dstStride=버퍼 한 줄 픽셀 수.
void renderLutRgbx(const uint8_t* nv21, int frameW, int frameH, int offX, int offY, const DewarpLut* lut, int mirror,
                   uint32_t* dst, int dstStride, int dstX, int dstY);
void renderLutRgbxRows(const uint8_t* nv21, int frameW, int frameH, int offX, int offY, const DewarpLut* lut, int mirror,
                       uint32_t* dst, int dstStride, int dstX, int dstY, int y0, int y1);   // 줄 범위만(여러 스레드가 나눠 그림)

#endif
