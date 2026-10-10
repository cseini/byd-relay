package com.cseini.bydsniffer.cloud;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

// BangcleTables — byd_cloud.js / BangcleTables.swift 1:1 포팅.
// bangcle_tables.bin(assets) 파싱: "BGTB" 매직 + 8개 테이블.
public final class BangcleTables {
    public byte[] invRound = new byte[0];
    public byte[] invXor = new byte[0];
    public byte[] invFirst = new byte[0];
    public byte[] round = new byte[0];
    public byte[] xor = new byte[0];
    public byte[] finalTable = new byte[0];
    public byte[] permDecrypt = new byte[0];
    public byte[] permEncrypt = new byte[0];

    public static BangcleTables loadFromAssets(Context ctx, String name) throws Exception {
        InputStream is = ctx.getAssets().open(name);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return loadFromBinary(bos.toByteArray());
        } finally {
            is.close();
        }
    }

    public static BangcleTables loadFromBinary(byte[] data) throws Exception {
        final byte[] MAGIC = { 0x42, 0x47, 0x54, 0x42 }; // "BGTB"
        final int VERSION = 1;
        final int TABLE_COUNT = 8;

        if (data.length < 40) throw new Exception("Data too small");

        int offset = 0;
        for (int i = 0; i < 4; i++) if (data[offset + i] != MAGIC[i]) throw new Exception("Bad magic");
        offset += 4;

        int version = u16le(data, offset); offset += 2;
        if (version != VERSION) throw new Exception("Unsupported version: " + version);

        int tableCount = u16le(data, offset); offset += 2;
        if (tableCount != TABLE_COUNT) throw new Exception("Expected " + TABLE_COUNT + " tables, got " + tableCount);

        long[] tableOffsets = new long[8];
        long[] tableLengths = new long[8];
        for (int i = 0; i < 8; i++) {
            tableOffsets[i] = u32le(data, offset); offset += 4;
            tableLengths[i] = u32le(data, offset); offset += 4;
        }

        byte[][] tables = new byte[8][];
        for (int i = 0; i < 8; i++) {
            long to = tableOffsets[i], tl = tableLengths[i];
            if (to + tl > data.length) throw new Exception("Table " + i + " out of bounds");
            byte[] t = new byte[(int) tl];
            System.arraycopy(data, (int) to, t, 0, (int) tl);
            tables[i] = t;
        }

        BangcleTables r = new BangcleTables();
        r.invRound = tables[0];
        r.invXor = tables[1];
        r.invFirst = tables[2];
        r.round = tables[3];
        r.xor = tables[4];
        r.finalTable = tables[5];
        r.permDecrypt = tables[6];
        r.permEncrypt = tables[7];
        return r;
    }

    private static int u16le(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static long u32le(byte[] d, int o) {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8) | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }
}
