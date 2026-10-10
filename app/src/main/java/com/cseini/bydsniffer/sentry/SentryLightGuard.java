package com.cseini.bydsniffer.sentry;

// 칸(영역) 하나의 조명 변화 판별 — 사람·차는 화면에 서서히 들어오지만, 형광등 깜빡임·조명 켜짐은 한 칸의 밝기나 움직임 면적이 한꺼번에 바뀐다.
// 판정마다 (영역 평균 밝기, 움직임 비율)을 넣으면 조명 변화인지 알려 준다. 조명 변화면 호출한 쪽이 그 칸 배경을 다시 잡는다.
final class SentryLightGuard {
    static final int MEAN_JUMP = 8;            // 한 판정 사이 평균 밝기가 이만큼 바뀌고
    static final float MEAN_JUMP_MIN_FG = 0.25f;   // 그 칸의 25% 넘게 움직였다고 나오면 조명
    static final float FG_JUMP_FROM = 0.10f;   // 직전엔 10% 미만이었는데
    static final float FG_JUMP_TO = 0.50f;     // 한 판정 만에 50% 이상이 되면 조명(들어오는 물체는 이렇게 한 번에 커지지 않는다)

    private int prevMean = -1;
    private float prevFg;

    boolean check(int meanY, float fg) {
        boolean ev = false;
        if (prevMean >= 0) {
            if (Math.abs(meanY - prevMean) >= MEAN_JUMP && fg >= MEAN_JUMP_MIN_FG) ev = true;
            if (prevFg < FG_JUMP_FROM && fg >= FG_JUMP_TO) ev = true;
        }
        prevMean = meanY;
        prevFg = fg;
        return ev;
    }

    void reset() {
        prevMean = -1;
        prevFg = 0f;
    }
}
