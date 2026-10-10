package com.cseini.motiondetect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class IllumFilterTest {
    private static final int W = 96, H = 72;

    @Test
    public void flatImage_becomesMidGray() {
        byte[] a = new byte[W * H];
        Arrays.fill(a, (byte) 77);
        byte[] out = new byte[W * H];
        IllumFilter.highPass(a, W, H, 4f, out);
        for (byte b : out) assertEquals(128, b & 0xFF, 1);
    }

    @Test
    public void slowLightingChange_isCancelled() {
        byte[] a = new byte[W * H], b = new byte[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int tex = 100 + ((x / 6 + y / 6) % 2) * 20;   // 가장자리가 있는 무늬
                a[y * W + x] = (byte) tex;
                b[y * W + x] = (byte) (tex + (int) (40f * x / W));   // 가로로 서서히 밝아지는 조명 변화
            }
        }
        byte[] oa = new byte[W * H], ob = new byte[W * H];
        IllumFilter.highPass(a, W, H, 4f, oa);
        IllumFilter.highPass(b, W, H, 4f, ob);
        long rawDiff = 0, hpDiff = 0;
        int n = 0;
        for (int y = 10; y < H - 10; y++) {
            for (int x = 10; x < W - 10; x++) {
                rawDiff += Math.abs((b[y * W + x] & 0xFF) - (a[y * W + x] & 0xFF));
                hpDiff += Math.abs((ob[y * W + x] & 0xFF) - (oa[y * W + x] & 0xFF));
                n++;
            }
        }
        assertTrue("원본 차이 " + rawDiff / n, rawDiff / n >= 15);
        assertTrue("제거 뒤 차이 " + (double) hpDiff / n, (double) hpDiff / n <= 3.0);
    }

    @Test
    public void smallObject_staysVisible() {
        byte[] a = new byte[W * H];
        Arrays.fill(a, (byte) 100);
        for (int y = 30; y < 40; y++) for (int x = 40; x < 46; x++) a[y * W + x] = (byte) 200;
        byte[] out = new byte[W * H];
        IllumFilter.highPass(a, W, H, 4f, out);
        assertTrue(Math.abs((out[34 * W + 43] & 0xFF) - 128) >= 40);
    }

    @Test
    public void zeroSigma_copies() {
        byte[] a = new byte[W * H];
        for (int i = 0; i < a.length; i++) a[i] = (byte) i;
        byte[] out = new byte[W * H];
        IllumFilter.highPass(a, W, H, 0f, out);
        assertTrue(Arrays.equals(a, out));
    }
}
