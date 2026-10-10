package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Context·StatFs·파일시스템이 필요한 부분(root 폴백, volumesJson, prune, cleanOrphans)은 실기 검증,
 * 아래는 순수 static 계산만 JVM에서 검증한다.
 */
public class SentryStorageTest {

    @Test
    public void estimateHours_designExamples() {
        assertEquals(14.8, SentryStorage.calcEstimateHours(10000, 1500), 0.0001);
        assertEquals(7.4, SentryStorage.calcEstimateHours(10000, 3000), 0.0001);
        assertEquals(5.6, SentryStorage.calcEstimateHours(10000, 4000), 0.0001);
    }

    @Test
    public void shouldRepin_onlyWhilePinnedFallbackAndRemovableMounted() {
        assertTrue(SentryStorage.shouldRepin(true, "sd", "internal", true));
        assertTrue(SentryStorage.shouldRepin(true, "usb", "appdir", true));
        assertFalse(SentryStorage.shouldRepin(true, "sd", "public", true));     // 이미 공용 폴더
        assertFalse(SentryStorage.shouldRepin(true, "internal", "internal", true)); // 내장 선택은 폴백 아님
        assertFalse(SentryStorage.shouldRepin(true, "sd", "internal", false));  // 외장 아직 없음
        assertFalse(SentryStorage.shouldRepin(false, "sd", "internal", true));  // 고정 아님(무장 전·중지 뒤)은 root()가 알아서
    }

    @Test
    public void eventRel_andFirstExisting_findLeftoverInOtherRoot() throws Exception {
        String id = "2026-10-05_16h26m16s";
        assertEquals("2026-10/05/" + id, SentryStorage.eventRel(id).replace(java.io.File.separatorChar, '/'));
        java.io.File sd = java.nio.file.Files.createTempDirectory("sd").toFile();
        java.io.File internal = java.nio.file.Files.createTempDirectory("internal").toFile();
        java.io.File leftover = new java.io.File(internal, SentryStorage.eventRel(id));
        assertTrue(leftover.mkdirs());
        java.io.File cur = new java.io.File(sd, SentryStorage.eventRel(id));
        // 현재 루트(SD)에는 없고 내장에만 있으면 내장 폴더
        assertEquals(leftover, SentryStorage.firstExisting(java.util.Arrays.asList(internal), SentryStorage.eventRel(id), cur));
        // 어디에도 없으면(새 이벤트) 현재 루트
        java.io.File fresh = new java.io.File(sd, SentryStorage.eventRel("2026-10-05_17h00m00s"));
        assertEquals(fresh, SentryStorage.firstExisting(java.util.Arrays.asList(internal), SentryStorage.eventRel("2026-10-05_17h00m00s"), fresh));
    }

    @Test
    public void underRoot_onlyEventsInsideThatRoot_andNotInProgress() throws Exception {
        java.io.File sd = java.nio.file.Files.createTempDirectory("sd").toFile();
        java.io.File internal = java.nio.file.Files.createTempDirectory("internal").toFile();
        String onSd = "2026-09-20_10h00m00s", onInternal = "2026-10-05_16h26m16s", recording = "2026-10-05_16h46m17s";
        assertTrue(new java.io.File(sd, SentryStorage.eventRel(onSd)).mkdirs());
        assertTrue(new java.io.File(internal, SentryStorage.eventRel(onInternal)).mkdirs());
        java.io.File rec = new java.io.File(internal, SentryStorage.eventRel(recording));
        assertTrue(rec.mkdirs());
        assertTrue(new java.io.File(rec, "seg-000.mp4.part").createNewFile());
        java.util.List<SentryIndex.EventMeta> all = new java.util.ArrayList<>();
        for (String id : new String[]{onSd, onInternal, recording}) { SentryIndex.EventMeta m = new SentryIndex.EventMeta(); m.id = id; all.add(m); }
        java.util.List<SentryIndex.EventMeta> i = SentryStorage.underRoot(all, internal);
        assertEquals(1, i.size());
        assertEquals(onInternal, i.get(0).id);   // SD 보관 영상은 내장 한도 정리 후보가 아니고, 녹화 중(.part)도 제외
        java.util.List<SentryIndex.EventMeta> s = SentryStorage.underRoot(all, sd);
        assertEquals(1, s.size());
        assertEquals(onSd, s.get(0).id);
    }

    @Test
    public void estimateHours_zeroOrNegativeInputs_returnZero() {
        assertEquals(0.0, SentryStorage.calcEstimateHours(0, 1500), 0.0);
        assertEquals(0.0, SentryStorage.calcEstimateHours(10000, 0), 0.0);
        assertEquals(0.0, SentryStorage.calcEstimateHours(-1, 1500), 0.0);
    }
}
