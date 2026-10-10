package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * MediaCodec이 필요한 부분(feed 큐·인코딩·drainPreroll)은 실기 검증, 아래는 순수 static 로직만 JVM에서 검증한다.
 */
public class PreRollEncoderTest {

    private static EncodedSample s(long ptsSec, boolean key) {
        return new EncodedSample(new byte[]{1}, ptsSec * 1000000L, key, false);
    }

    @Test
    public void nv21ToNv12_swapsChromaPairs() {
        // 4x2 프레임: Y 8바이트 + 색차 4바이트(NV21 = V,U,V,U)
        byte[] src = {1, 2, 3, 4, 5, 6, 7, 8, 10, 20, 11, 21};
        byte[] dst = new byte[12];
        PreRollEncoder.nv21ToNv12(src, dst, 4, 2);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 20, 10, 21, 11}, dst);
    }

    @Test
    public void prerollStartIndex_picksLatestKeyframeOlderThanThreshold() {
        // pts 0~4초 1초 간격, 0·2·4초가 키프레임. 프리롤 1.5초 → 기준 2.5초 이하 중 가장 최근 키프레임 = 2초(인덱스 2)
        List<EncodedSample> l = new ArrayList<>();
        for (int i = 0; i <= 4; i++) l.add(s(i, i % 2 == 0));
        assertEquals(2, PreRollEncoder.prerollStartIndex(l, 1500000L));
    }

    @Test
    public void prerollStartIndex_noKeyOldEnough_usesOldestKey() {
        List<EncodedSample> l = new ArrayList<>();
        l.add(s(0, false));
        l.add(s(1, true));
        l.add(s(2, false));
        // 기준 2-10 = -8초보다 오래된 키프레임이 없으면 가장 오래된 키프레임
        assertEquals(1, PreRollEncoder.prerollStartIndex(l, 10000000L));
    }

    @Test
    public void prerollStartIndex_noKeyframeOrEmpty_returnsMinusOne() {
        List<EncodedSample> l = new ArrayList<>();
        assertEquals(-1, PreRollEncoder.prerollStartIndex(l, 1000000L));
        l.add(s(0, false));
        l.add(s(1, false));
        assertEquals(-1, PreRollEncoder.prerollStartIndex(l, 1000000L));
    }

    @Test
    public void ringLimitBytes_isPrerollPlusTwoSecondsOfBitrate() {
        // (10+2)초 × 1500kbps = 12 × 1500 × 1000 / 8 = 2,250,000바이트
        assertEquals(2250000L, PreRollEncoder.ringLimitBytes(10, 1500));
        assertEquals(2L * 3000 * 1000 / 8, PreRollEncoder.ringLimitBytes(0, 3000));
    }
}
