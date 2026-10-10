package com.cseini.motiondetect;

// ★신호등 색 판정용 — NV21 ROI를 저해상 RGB로 뽑아 빨강/초록 픽셀 비율을 반환.
//   같은 패키지라 package-private YuvNative.extractRgbRoi를 그대로 쓴다(기존 시그니처 무변경).
//   MotionDetector(움직임)와 독립 — camDetect가 신호등 ROI에만 호출.
public final class ColorProbe {
    private ColorProbe() {}

    // 정규화 폴리곤(nx,ny)의 바운딩박스를 dsW×dsH RGB로 크롭 → {redFrac, greenFrac, brightness}(0~1).
    //   red   = R 우세 + 충분히 밝은 픽셀 비율(빨강/노랑 신호)
    //   green = G 우세 + 충분히 밝은 픽셀 비율(초록 신호)
    //   brightness = ROI 평균 밝기(야간/역광 판정 보조)
    public static float[] redGreen(byte[] nv21, int srcW, int srcH, float[] nx, float[] ny, int dsW, int dsH) {
        if (nv21 == null || nx == null || nx.length < 3) return new float[]{0f, 0f, 0f};
        float minx = 1f, miny = 1f, maxx = 0f, maxy = 0f;
        for (int k = 0; k < nx.length; k++) {
            minx = Math.min(minx, nx[k]); maxx = Math.max(maxx, nx[k]);
            miny = Math.min(miny, ny[k]); maxy = Math.max(maxy, ny[k]);
        }
        int rx = (int) (minx * srcW), ry = (int) (miny * srcH);
        int rw = (int) ((maxx - minx) * srcW), rh = (int) ((maxy - miny) * srcH);
        if (rw < 2 || rh < 2) return new float[]{0f, 0f, 0f};
        byte[] rgb = new byte[dsW * dsH * 3];
        try {
            YuvNative.extractRgbRoi(nv21, srcW, srcH, rx, ry, rw, rh, dsW, dsH, rgb);
        } catch (Throwable t) {
            return new float[]{0f, 0f, 0f};
        }
        int red = 0, green = 0; long lum = 0; int n = dsW * dsH;
        for (int i = 0; i < n; i++) {
            int r = rgb[i * 3] & 0xff, g = rgb[i * 3 + 1] & 0xff, b = rgb[i * 3 + 2] & 0xff;
            lum += (r + g + b);
            // 신호등 램프는 원색이라 채널 우세 + 밝기로 판별(HSV 변환 없이). 임계는 camDetect에서 재판정 여지.
            if (r > 90 && r >= g + 35 && r >= b + 35) red++;
            else if (g > 90 && g >= r + 20 && g >= b + 10) green++;
        }
        return new float[]{ (float) red / n, (float) green / n, (float) lum / (n * 3f * 255f) };
    }
}
