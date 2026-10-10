// JNI 브릿지 — com.cseini.motiondetect.Mog2Native / YuvNative ↔ C++ Mog2 / yuv.
#include <jni.h>
#include <cstring>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <functional>
#include <atomic>
#include <time.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "mog2.h"
#include "yuv.h"

namespace {
// ★764 창 그리기 보조 스레드 2개를 한 번 만들어 계속 쓴다(예전엔 매 장 std::thread 2개를 만들고 join — 헤드유닛 CPU 92% 사용 중엔
//   새 스레드가 늦게 잡혀 한 장이 길어졌다, 10-05 17:3x 실측). 스레드는 처음 그리는 스레드(우선순위 올린 hu-assist-hd)에서 만들어져 그 우선순위를 물려받는다.
struct BandPool {
    std::mutex runMu, mu;
    std::condition_variable cv, doneCv;
    std::function<void(int)> job;
    int gen = 0, pending = 0;
    bool started = false;
    void worker(int t) {
        int seen = 0;
        for (;;) {
            std::function<void(int)> j;
            {
                std::unique_lock<std::mutex> lk(mu);
                cv.wait(lk, [&] { return gen != seen; });
                seen = gen; j = job;
            }
            j(t);
            { std::lock_guard<std::mutex> lk(mu); if (--pending == 0) doneCv.notify_one(); }
        }
    }
    void run(const std::function<void(int)>& f) {
        std::lock_guard<std::mutex> one(runMu);
        {
            std::lock_guard<std::mutex> lk(mu);
            if (!started) { for (int t = 1; t <= 2; ++t) std::thread(&BandPool::worker, this, t).detach(); started = true; }
            job = f; pending = 2; ++gen;
        }
        cv.notify_all();
        f(0);
        std::unique_lock<std::mutex> lk(mu);
        doneCv.wait(lk, [&] { return pending == 0; });
    }
};
BandPool gBands;
// ★764 한 장 그리기 구간별 시간 누적(마이크로초) — 잠금(버퍼 받기)·지우기·펴기·올리기. winStats가 읽고 0으로 되돌린다.
std::atomic<long long> gN{0}, gLockUs{0}, gClearUs{0}, gWarpUs{0}, gPostUs{0};
long long nowUs() { timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return ts.tv_sec * 1000000LL + ts.tv_nsec / 1000; }
}

extern "C" {

// ── Mog2Native ──
JNIEXPORT jlong JNICALL
Java_com_cseini_motiondetect_Mog2Native_create(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new Mog2());
}

JNIEXPORT void JNICALL
Java_com_cseini_motiondetect_Mog2Native_initialize(JNIEnv*, jclass, jlong h, jint w, jint hgt) {
    if (h) reinterpret_cast<Mog2*>(h)->initialize(w, hgt);
}

JNIEXPORT void JNICALL
Java_com_cseini_motiondetect_Mog2Native_setParams(JNIEnv*, jclass, jlong h, jfloat lr, jfloat vt, jfloat br) {
    if (!h) return;
    Mog2* m = reinterpret_cast<Mog2*>(h);
    m->setLearningRate(lr); m->setVarThreshold(vt); m->setBackgroundRatio(br);
}

JNIEXPORT void JNICALL
Java_com_cseini_motiondetect_Mog2Native_setWeightInit(JNIEnv*, jclass, jlong h, jfloat w) {
    if (!h) return;
    reinterpret_cast<Mog2*>(h)->setWeightInit(w);
}

JNIEXPORT jfloat JNICALL
Java_com_cseini_motiondetect_Mog2Native_apply(JNIEnv* env, jclass, jlong h, jbyteArray y, jbyteArray mask) {
    if (!h || !y) return 0.f;
    jbyte* p = env->GetByteArrayElements(y, nullptr);
    jbyte* mp = mask ? env->GetByteArrayElements(mask, nullptr) : nullptr;
    float r = reinterpret_cast<Mog2*>(h)->apply(reinterpret_cast<const uint8_t*>(p),
                                                reinterpret_cast<const uint8_t*>(mp));
    if (mp) env->ReleaseByteArrayElements(mask, mp, JNI_ABORT);
    env->ReleaseByteArrayElements(y, p, JNI_ABORT);   // 읽기만 → 되쓰기 안 함
    return r;
}

JNIEXPORT jfloat JNICALL
Java_com_cseini_motiondetect_Mog2Native_foregroundPercentage(JNIEnv*, jclass, jlong h) {
    return h ? reinterpret_cast<Mog2*>(h)->foregroundPercentage() : 0.f;
}

JNIEXPORT jfloat JNICALL
Java_com_cseini_motiondetect_Mog2Native_motionVerticalCenter(JNIEnv*, jclass, jlong h) {
    return h ? reinterpret_cast<Mog2*>(h)->motionVerticalCenter() : -1.f;
}

JNIEXPORT jboolean JNICALL
Java_com_cseini_motiondetect_Mog2Native_foregroundStats(JNIEnv* env, jclass, jlong h, jfloatArray out) {
    if (!h || !out || env->GetArrayLength(out) < 4) return JNI_FALSE;
    float v[4];
    if (!reinterpret_cast<Mog2*>(h)->foregroundStats(v)) return JNI_FALSE;
    env->SetFloatArrayRegion(out, 0, 4, v);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_cseini_motiondetect_Mog2Native_reset(JNIEnv*, jclass, jlong h) {
    if (h) reinterpret_cast<Mog2*>(h)->reset();
}

JNIEXPORT void JNICALL
Java_com_cseini_motiondetect_Mog2Native_destroy(JNIEnv*, jclass, jlong h) {
    if (h) delete reinterpret_cast<Mog2*>(h);
}

// ── YuvNative ──
JNIEXPORT void JNICALL
Java_com_cseini_motiondetect_YuvNative_extractYRoiDownsample(
        JNIEnv* env, jclass, jbyteArray nv21, jint srcW, jint srcH,
        jint rx, jint ry, jint rw, jint rh, jint dstW, jint dstH, jbyteArray dst) {
    if (!nv21 || !dst) return;
    jbyte* s = env->GetByteArrayElements(nv21, nullptr);
    jbyte* d = env->GetByteArrayElements(dst, nullptr);
    extractYRoiDownsample(reinterpret_cast<const uint8_t*>(s), srcW, srcH,
                          rx, ry, rw, rh, dstW, dstH, reinterpret_cast<uint8_t*>(d));
    env->ReleaseByteArrayElements(nv21, s, JNI_ABORT);   // 소스 읽기만
    env->ReleaseByteArrayElements(dst, d, 0);            // dst 되쓰기(0=커밋)
}

JNIEXPORT void JNICALL
Java_com_cseini_motiondetect_YuvNative_extractRgbRoi(
        JNIEnv* env, jclass, jbyteArray nv21, jint srcW, jint srcH,
        jint rx, jint ry, jint rw, jint rh, jint dstW, jint dstH, jbyteArray rgbOut) {
    if (!nv21 || !rgbOut) return;
    jbyte* s = env->GetByteArrayElements(nv21, nullptr);
    jbyte* d = env->GetByteArrayElements(rgbOut, nullptr);
    extractRgbRoi(reinterpret_cast<const uint8_t*>(s), srcW, srcH,
                  rx, ry, rw, rh, dstW, dstH, reinterpret_cast<uint8_t*>(d));
    env->ReleaseByteArrayElements(nv21, s, JNI_ABORT);   // 소스 읽기만
    env->ReleaseByteArrayElements(rgbOut, d, 0);         // rgb 되쓰기(0=커밋)
}

// ★de-warp(2026-09-03, DEWARP-00-PLAN) — LUT 핸들(jlong=DewarpLut*) 생성/조회/해제.
JNIEXPORT jlong JNICALL
Java_com_cseini_motiondetect_YuvNative_buildDewarpLut(
        JNIEnv*, jclass, jint srcW, jint srcH, jint roiX, jint roiY, jint roiW, jint roiH,
        jfloat fovDeg, jfloat cx, jfloat cy, jint dstW, jint dstH, jfloat rollDeg, jboolean pano, jfloat tiltDeg, jfloat k) {
    return reinterpret_cast<jlong>(buildFisheyeLut(srcW, srcH, roiX, roiY, roiW, roiH, fovDeg, cx, cy, dstW, dstH, rollDeg, pano != JNI_FALSE, tiltDeg, k));
}

// ★718 가로 직선 LUT·출력 비율.
JNIEXPORT jlong JNICALL
Java_com_cseini_motiondetect_YuvNative_buildDewarpLutT(
        JNIEnv*, jclass, jint srcW, jint srcH, jint roiX, jint roiY, jint roiW, jint roiH,
        jfloat fovDeg, jfloat cx, jfloat cy, jint dstW, jint dstH, jfloat rollDeg, jfloat tiltDeg, jfloat k) {
    return reinterpret_cast<jlong>(buildFisheyeLutT(srcW, srcH, roiX, roiY, roiW, roiH, fovDeg, cx, cy, dstW, dstH, rollDeg, tiltDeg, k));
}

JNIEXPORT jfloat JNICALL
Java_com_cseini_motiondetect_YuvNative_dewarpAspectT(
        JNIEnv*, jclass, jint srcW, jint srcH, jint roiX, jint roiY, jint roiW, jint roiH,
        jfloat fovDeg, jfloat cx, jfloat cy, jfloat rollDeg, jfloat k) {
    return dewarpAspectT(srcW, srcH, roiX, roiY, roiW, roiH, fovDeg, cx, cy, rollDeg, k);
}

// ★719 후진 파노라마 칸 LUT(각도 창 지정).
JNIEXPORT jlong JNICALL
Java_com_cseini_motiondetect_YuvNative_buildDewarpLutTWin(
        JNIEnv*, jclass, jint srcW, jint srcH, jfloat fovDeg, jfloat cx, jfloat cy, jfloat rollDeg, jfloat k,
        jfloat a0Deg, jfloat a1Deg, jfloat b0Deg, jfloat b1Deg, jint dstW, jint dstH) {
    return reinterpret_cast<jlong>(buildFisheyeLutTWin(srcW, srcH, fovDeg, cx, cy, rollDeg, k, a0Deg, a1Deg, b0Deg, b1Deg, dstW, dstH));
}

JNIEXPORT void JNICALL
Java_com_cseini_motiondetect_YuvNative_remapRgbWithLut(
        JNIEnv* env, jclass, jlong lutHandle, jbyteArray nv21, jint srcW, jint srcH, jbyteArray rgbOut) {
    if (!lutHandle || !nv21 || !rgbOut) return;
    jbyte* s = env->GetByteArrayElements(nv21, nullptr);
    jbyte* d = env->GetByteArrayElements(rgbOut, nullptr);
    remapRgbWithLut(reinterpret_cast<const uint8_t*>(s), srcW, srcH,
                    reinterpret_cast<const DewarpLut*>(lutHandle), reinterpret_cast<uint8_t*>(d));
    env->ReleaseByteArrayElements(nv21, s, JNI_ABORT);   // 소스 읽기만
    env->ReleaseByteArrayElements(rgbOut, d, 0);         // rgb 되쓰기(0=커밋)
}

JNIEXPORT void JNICALL
Java_com_cseini_motiondetect_YuvNative_freeDewarpLut(JNIEnv*, jclass, jlong lutHandle) {
    freeFisheyeLut(reinterpret_cast<DewarpLut*>(lutHandle));
}

// ★617 클러스터 측면캠 커스텀 — 선형 크롭 LUT + NV21 출력 remap
JNIEXPORT jlong JNICALL
Java_com_cseini_motiondetect_YuvNative_buildCropLut(JNIEnv*, jclass, jint roiX, jint roiY, jint roiW, jint roiH, jint dstW, jint dstH, jfloat rollDeg, jint clampW, jint clampH) {
    return reinterpret_cast<jlong>(buildCropLut(roiX, roiY, roiW, roiH, dstW, dstH, rollDeg, clampW, clampH));
}

JNIEXPORT void JNICALL
Java_com_cseini_motiondetect_YuvNative_remapNv21WithLut(
        JNIEnv* env, jclass, jlong lutHandle, jbyteArray nv21, jint frameW, jint frameH, jint offX, jint offY, jint mirror, jbyteArray out) {
    if (!lutHandle || !nv21 || !out) return;
    jbyte* s = env->GetByteArrayElements(nv21, nullptr);
    jbyte* d = env->GetByteArrayElements(out, nullptr);
    remapNv21WithLut(reinterpret_cast<const uint8_t*>(s), frameW, frameH, offX, offY,
                     reinterpret_cast<const DewarpLut*>(lutHandle), mirror, reinterpret_cast<uint8_t*>(d));
    env->ReleaseByteArrayElements(nv21, s, JNI_ABORT);
    env->ReleaseByteArrayElements(out, d, 0);
}

// ★733 헤드유닛 창 직접 그리기 — Surface의 창 버퍼(RGBX_8888)에 LUT 칸들을 바로 쓴다(JPEG 인코딩·디코딩 없음).
JNIEXPORT jlong JNICALL
Java_com_cseini_motiondetect_YuvNative_winAcquire(JNIEnv* env, jclass, jobject surface) {
    return surface ? reinterpret_cast<jlong>(ANativeWindow_fromSurface(env, surface)) : 0;
}

JNIEXPORT void JNICALL
Java_com_cseini_motiondetect_YuvNative_winRelease(JNIEnv*, jclass, jlong win) {
    if (win) ANativeWindow_release(reinterpret_cast<ANativeWindow*>(win));
}

static bool winLock(ANativeWindow* w, int bufW, int bufH, ANativeWindow_Buffer* b) {
    if (ANativeWindow_getWidth(w) != bufW || ANativeWindow_getHeight(w) != bufH || ANativeWindow_getFormat(w) != WINDOW_FORMAT_RGBX_8888)
        ANativeWindow_setBuffersGeometry(w, bufW, bufH, WINDOW_FORMAT_RGBX_8888);
    if (ANativeWindow_lock(w, b, nullptr) != 0) return false;
    for (int y = 0; y < b->height; ++y) memset(static_cast<uint32_t*>(b->bits) + (size_t)y * b->stride, 0, (size_t)b->width * 4);   // 검정(여백)
    return true;
}

// luts[i] 칸마다 meta[i*5..] = {타일 원점 x, y, 반전, 내용 안 x, 내용 안 y}. 내용(contentW×contentH)은 버퍼 가운데.
// 반환 0=그림 · -1=창 없음 · -2=잠금 실패 · -3=내용이 버퍼를 넘음(레이아웃 다시) · -4=프레임 배열 못 얻음
JNIEXPORT jint JNICALL
Java_com_cseini_motiondetect_YuvNative_winRender(JNIEnv* env, jclass, jlong win, jbyteArray nv21, jint frameW, jint frameH,
                                                 jlongArray luts, jintArray meta, jint contentW, jint contentH, jint bufW, jint bufH) {
    ANativeWindow* w = reinterpret_cast<ANativeWindow*>(win);
    if (!w || !nv21 || !luts || !meta) return -1;
    if (contentW > bufW || contentH > bufH) return -3;
    const jsize n = env->GetArrayLength(luts);
    if (env->GetArrayLength(meta) < n * 5) return -1;
    jlong* lh = env->GetLongArrayElements(luts, nullptr);
    jint* m = env->GetIntArrayElements(meta, nullptr);
    ANativeWindow_Buffer b;
    jint rc = 0;
    const long long t0 = nowUs();
    if (ANativeWindow_getWidth(w) != bufW || ANativeWindow_getHeight(w) != bufH || ANativeWindow_getFormat(w) != WINDOW_FORMAT_RGBX_8888)
        ANativeWindow_setBuffersGeometry(w, bufW, bufH, WINDOW_FORMAT_RGBX_8888);
    const bool locked = ANativeWindow_lock(w, &b, nullptr) == 0;
    const long long t1 = nowUs();
    if (locked) for (int y = 0; y < b.height; ++y) memset(static_cast<uint32_t*>(b.bits) + (size_t)y * b.stride, 0, (size_t)b.width * 4);   // 검정(여백)
    const long long t2 = nowUs();
    long long t3 = t2;
    if (!locked) rc = -2;
    else {
        void* s = env->GetPrimitiveArrayCritical(nv21, nullptr);
        if (!s) rc = -4;
        else {
            const int ox = (b.width - contentW) / 2, oy = (b.height - contentH) / 2;
            int maxH = 0;
            for (jsize i = 0; i < n; ++i) {
                const DewarpLut* L = reinterpret_cast<const DewarpLut*>(lh[i]);
                if (!L) continue;
                const int x = ox + m[i * 5 + 3], y = oy + m[i * 5 + 4];
                if (ox < 0 || oy < 0 || x < 0 || y < 0 || x + L->dstW > b.width || y + L->dstH > b.height) rc = -3;
                if (L->dstH > maxH) maxH = L->dstH;
            }
            if (rc == 0) {
                // 위아래 3덩어리를 동시에(표 조회가 메모리 대기라 나눌수록 빨라짐, 맥 시험 13.2→5.0ms).
                const uint8_t* src = static_cast<const uint8_t*>(s);
                uint32_t* bits = static_cast<uint32_t*>(b.bits);
                const int stride = b.stride;
                auto band = [&](int t) {
                    const int y0 = maxH * t / 3, y1 = maxH * (t + 1) / 3;
                    for (jsize i = 0; i < n; ++i) {
                        const DewarpLut* L = reinterpret_cast<const DewarpLut*>(lh[i]);
                        if (L) renderLutRgbxRows(src, frameW, frameH, m[i * 5], m[i * 5 + 1], L, m[i * 5 + 2], bits, stride, ox + m[i * 5 + 3], oy + m[i * 5 + 4], y0, y1);
                    }
                };
                gBands.run(band);
            }
            env->ReleasePrimitiveArrayCritical(nv21, s, JNI_ABORT);
        }
        t3 = nowUs();
        ANativeWindow_unlockAndPost(w);
        if (rc == 0) { gN++; gLockUs += t1 - t0; gClearUs += t2 - t1; gWarpUs += t3 - t2; gPostUs += nowUs() - t3; }
    }
    env->ReleaseIntArrayElements(meta, m, JNI_ABORT);
    env->ReleaseLongArrayElements(luts, lh, JNI_ABORT);
    return rc;
}

// ★764 {장 수, 잠금, 지우기, 펴기, 올리기(마이크로초 합)}을 돌려주고 0으로 되돌린다.
JNIEXPORT jlongArray JNICALL
Java_com_cseini_motiondetect_YuvNative_winStats(JNIEnv* env, jclass) {
    jlong v[5] = { gN.exchange(0), gLockUs.exchange(0), gClearUs.exchange(0), gWarpUs.exchange(0), gPostUs.exchange(0) };
    jlongArray a = env->NewLongArray(5);
    if (a) env->SetLongArrayRegion(a, 0, 5, v);
    return a;
}

// 검정으로 지우기('카메라 신호 없음' — 멈춘 화면을 남기지 않음).
JNIEXPORT jint JNICALL
Java_com_cseini_motiondetect_YuvNative_winClear(JNIEnv*, jclass, jlong win, jint bufW, jint bufH) {
    ANativeWindow* w = reinterpret_cast<ANativeWindow*>(win);
    if (!w) return -1;
    ANativeWindow_Buffer b;
    if (!winLock(w, bufW, bufH, &b)) return -2;
    ANativeWindow_unlockAndPost(w);
    return 0;
}

} // extern "C"
