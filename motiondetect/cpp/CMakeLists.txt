# 모션감지 native 라이브러리 — libmotiondetect.so (System.loadLibrary("motiondetect")).
# 릴레이(byd-sniffer app) build.gradle의 externalNativeBuild가 이 파일을 가리킴.
cmake_minimum_required(VERSION 3.18.1)
project(motiondetect)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(motiondetect SHARED
        mog2.cpp
        yuv.cpp
        jni_bridge.cpp)

# 최적화 — 픽셀 루프가 핫패스. -O2.
target_compile_options(motiondetect PRIVATE -O2 -ffast-math)

# jni.h는 NDK 툴체인이 자동 제공. 로그 필요시 log 링크(현재 미사용).
find_library(log-lib log)
find_library(android-lib android)   # ★733 헤드유닛 창 직접 그리기(ANativeWindow)
target_link_libraries(motiondetect ${log-lib} ${android-lib})
