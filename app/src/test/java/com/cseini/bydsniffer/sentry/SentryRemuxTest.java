package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

public class SentryRemuxTest {
    @Test
    public void nextOffset_addsLastPtsAndFrameDelta_withDefaultWhenUnknown() {
        assertEquals(60_040_000L, SentryRemux.nextOffsetUs(0L, 60_000_000L, 40_000L));
        assertEquals(120_100_000L, SentryRemux.nextOffsetUs(60_040_000L, 59_990_000L, 70_000L));
        assertEquals(SentryRemux.DEFAULT_FRAME_US + 5_000_000L, SentryRemux.nextOffsetUs(0L, 5_000_000L, 0L));   // 간격 모르면 기본(25fps)
    }

    @Test
    public void combinedName_includesIdPartsBytes() {
        assertEquals("2026-10-06_00h13m05s-3-46000000.mp4", SentryManager.combinedName("2026-10-06_00h13m05s", 3, 46000000L));
    }

    @Test
    public void trimCombinedCache_keepsTarget_andOnlyNewestOther_dropsOldAndPartial() throws Exception {
        File dir = Files.createTempDirectory("dlcache").toFile();
        long now = System.currentTimeMillis();
        File target = new File(dir, "target.mp4"), n1 = new File(dir, "n1.mp4"), n2 = new File(dir, "n2.mp4"),
                old = new File(dir, "old.mp4"), part = new File(dir, "x.mp4.part");
        for (File f : new File[]{target, n1, n2, old, part}) Files.write(f.toPath(), new byte[]{1});
        n1.setLastModified(now - 60_000L);       // 가장 최근 다른 파일
        n2.setLastModified(now - 120_000L);      // 그 다음 — 지워짐
        old.setLastModified(now - 3 * 3600_000L);
        part.setLastModified(now - 10_000L);
        SentryManager.trimCombinedCache(dir, "target.mp4", now);
        assertTrue(target.exists());
        assertTrue(n1.exists());
        assertFalse(n2.exists());
        assertFalse(old.exists());
        assertFalse(part.exists());
    }
}
