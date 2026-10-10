package com.cseini.motiondetect;

// MOG2 배경차감 native 바인딩. 핸들(long)은 native Mog2* 포인터. MotionDetector가 관리.
final class Mog2Native {
    static { System.loadLibrary("motiondetect"); }
    private Mog2Native() {}

    static native long  create();
    static native void  initialize(long h, int w, int hgt);
    static native void  setParams(long h, float learningRate, float varThreshold, float backgroundRatio);
    static native void  setWeightInit(long h, float w);     // 새 물체 가우시안 초기 weight(기본 0.05)
    static native float apply(long h, byte[] y, byte[] mask); // mask!=null이면 그 안만 카운트(자유형 ROI). → 전경 비율
    static native float foregroundPercentage(long h);
    static native float motionVerticalCenter(long h);       // 0~1(0=위,1=아래), 전경없으면 -1
    static native boolean foregroundStats(long h, float[] out4);   // 전경 무게중심 x·y, 바운딩박스 폭·높이(0~1). 전경 없으면 false
    static native void  reset(long h);
    static native void  destroy(long h);
}
