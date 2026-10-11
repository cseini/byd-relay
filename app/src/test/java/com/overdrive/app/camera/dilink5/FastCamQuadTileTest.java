package com.overdrive.app.camera.dilink5;

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.util.Random;
import org.junit.Test;

// 보는 칸만 변환(tileToNv21)이 4칸 전체 합성 뒤 변환(rgbaToNv21)과 그 칸 자리에서 같은 값인지
public class FastCamQuadTileTest {
    private static final int TW = 960, TH = 650, CW = TW * 2, CH = TH * 2;

    @Test public void tileMatchesFullConversionInEverySlot() {
        Random rnd = new Random(7);
        byte[] tile = new byte[TW * TH * 4];
        rnd.nextBytes(tile);
        for (int slot = 0; slot < 4; slot++) {
            int qx = (slot % 2) * TW, qy = (slot / 2) * TH;
            byte[] canvas = new byte[CW * CH * 4];
            for (int row = 0; row < TH; row++) System.arraycopy(tile, row * TW * 4, canvas, ((qy + row) * CW + qx) * 4, TW * 4);
            byte[] full = new byte[CW * CH * 3 / 2];
            DiLink5QCarCamBackend.rgbaToNv21(ByteBuffer.wrap(canvas), CW, CH, full);
            byte[] part = new byte[CW * CH * 3 / 2];
            FastCamQuadBackend.tileToNv21(tile, qx, qy, part);
            for (int y = qy; y < qy + TH; y++) for (int x = qx; x < qx + TW; x++)
                assertEquals("Y slot " + slot + " " + x + "," + y, full[y * CW + x], part[y * CW + x]);
            for (int r = qy / 2; r < (qy + TH) / 2; r++) for (int x = qx; x < qx + TW; x++)
                assertEquals("VU slot " + slot + " " + x + "," + r, full[CW * CH + r * CW + x], part[CW * CH + r * CW + x]);
        }
    }
}
