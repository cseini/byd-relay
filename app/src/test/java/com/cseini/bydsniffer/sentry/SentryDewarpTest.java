package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SentryDewarpTest {
    // 4x4 프레임(칸 2x2)에서 한 칸을 NV12로 놓는 규칙 — Y는 그대로(vf면 줄 뒤집기), UV는 VU→UV 쌍 교환
    @Test
    public void composeCell_copiesYAndSwapsUv() {
        int cw = 2, ch = 2, w = 4, h = 4;
        byte[] tile = new byte[cw * ch * 3 / 2];   // Y 4바이트 + VU 2바이트
        tile[0] = 10; tile[1] = 11; tile[2] = 12; tile[3] = 13;
        tile[4] = 100; tile[5] = 101;   // V, U
        byte[] dst = new byte[w * h * 3 / 2];
        SentryDewarp.composeCell(tile, cw, ch, false, dst, w, 2, 2, h, true);   // 오른쪽 아래 칸
        assertEquals(10, dst[2 * w + 2]);
        assertEquals(11, dst[2 * w + 3]);
        assertEquals(12, dst[3 * w + 2]);
        assertEquals(13, dst[3 * w + 3]);
        assertEquals(101, dst[w * h + (2 / 2) * w + 2]);   // U 먼저
        assertEquals(100, dst[w * h + (2 / 2) * w + 3]);
    }

    @Test
    public void composeCell_verticalFlipReversesRows_andKeepsUvOrderWhenNotSwapped() {
        int cw = 2, ch = 2, w = 4, h = 4;
        byte[] tile = new byte[cw * ch * 3 / 2];
        tile[0] = 1; tile[1] = 2; tile[2] = 3; tile[3] = 4; tile[4] = 50; tile[5] = 51;
        byte[] dst = new byte[w * h * 3 / 2];
        SentryDewarp.composeCell(tile, cw, ch, true, dst, w, 0, 0, h, false);
        assertEquals(3, dst[0]); assertEquals(4, dst[1]);
        assertEquals(1, dst[w]); assertEquals(2, dst[w + 1]);
        assertEquals(50, dst[w * h]); assertEquals(51, dst[w * h + 1]);   // 색은 한 줄뿐이라 그대로, 순서 교환 없음(NV21)
    }
}
