package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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

    // ── 내장에 남은 영상을 외장으로 옮기기 ──
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File makeEvent(File root, String id, String... names) throws IOException {
        File d = new File(root, SentryStorage.eventRel(id));
        assertTrue(d.mkdirs());
        for (String n : names) Files.write(new File(d, n).toPath(), (id + n).getBytes(StandardCharsets.UTF_8));
        return d;
    }

    @Test
    public void moveEventDir_copiesVerifiesThenRemovesSourceAndEmptyParents() throws IOException {
        File internal = tmp.newFolder("internal"), sd = tmp.newFolder("sd");
        String id = "2026-10-09_20h50m58s";
        File src = makeEvent(internal, id, "meta.json", "000.mp4", "thumb.jpg");
        long bytes = SentryStorage.moveEventDir(src, sd, SentryStorage.eventRel(id));
        File dest = new File(sd, SentryStorage.eventRel(id));
        assertTrue(dest.isDirectory());
        assertEquals((id + "meta.json").length() + (id + "000.mp4").length() + (id + "thumb.jpg").length(), bytes);
        assertEquals(id + "000.mp4", new String(Files.readAllBytes(new File(dest, "000.mp4").toPath()), StandardCharsets.UTF_8));
        assertFalse("원본은 지워진다", src.exists());
        assertFalse("비게 된 날짜 폴더도 정리", new File(internal, "2026-10").exists());
        assertTrue("루트는 남는다", internal.isDirectory());
        assertFalse(new File(dest.getParentFile(), id + ".moving").exists());
    }

    @Test
    public void moveEventDir_refusesWhenDestinationAlreadyHasIt_andKeepsSource() throws IOException {
        File internal = tmp.newFolder("internal2"), sd = tmp.newFolder("sd2");
        String id = "2026-10-09_20h50m58s";
        File src = makeEvent(internal, id, "meta.json");
        makeEvent(sd, id, "meta.json");
        try {
            SentryStorage.moveEventDir(src, sd, SentryStorage.eventRel(id));
            org.junit.Assert.fail("대상에 이미 있으면 실패해야 한다");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("이미 있음"));
        }
        assertTrue("원본 유지", new File(src, "meta.json").isFile());
    }

    @Test
    public void moveEventDir_replacesLeftoverMovingFolderFromAnInterruptedRun() throws IOException {
        File internal = tmp.newFolder("internal3"), sd = tmp.newFolder("sd3");
        String id = "2026-10-09_20h50m58s";
        File src = makeEvent(internal, id, "meta.json");
        File dest = new File(sd, SentryStorage.eventRel(id));
        File stale = new File(dest.getParentFile(), id + ".moving");
        assertTrue(stale.mkdirs());
        Files.write(new File(stale, "garbage").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        SentryStorage.moveEventDir(src, sd, SentryStorage.eventRel(id));
        assertTrue(new File(dest, "meta.json").isFile());
        assertFalse("옛 임시 폴더의 찌꺼기가 섞이지 않는다", new File(dest, "garbage").exists());
        assertFalse(stale.exists());
    }

    @Test
    public void moveEventDir_failureLeavesSourceIntactAndNoTempFolder() throws IOException {
        File internal = tmp.newFolder("internal4"), sd = tmp.newFolder("sd4");
        String id = "2026-10-09_20h50m58s";
        File src = makeEvent(internal, id, "meta.json", "000.mp4");
        File bad = new File(src, "000.mp4");
        org.junit.Assume.assumeTrue("읽기 금지가 통하는 환경만(root 제외)", bad.setReadable(false) && !bad.canRead());
        try {
            SentryStorage.moveEventDir(src, sd, SentryStorage.eventRel(id));
            org.junit.Assert.fail("읽을 수 없는 파일이 있으면 실패해야 한다");
        } catch (IOException expected) {
            // 기대한 실패
        } finally {
            bad.setReadable(true);
        }
        assertTrue("원본 그대로", new File(src, "meta.json").isFile() && new File(src, "000.mp4").isFile());
        File dest = new File(sd, SentryStorage.eventRel(id));
        assertFalse("대상에 반쪽이 남지 않는다", dest.exists());
        assertFalse("임시 폴더도 치운다", new File(dest.getParentFile(), id + ".moving").exists());
    }

    @Test
    public void cleanMovingLeftovers_removesOnlyOldOnes() throws IOException {
        File root = tmp.newFolder("root5");
        File oldDir = new File(root, "2026-10/09/2026-10-09_20h50m58s.moving");
        File newDir = new File(root, "2026-10/09/2026-10-09_21h00m00s.moving");
        File real = makeEvent(root, "2026-10-09_22h00m00s", "meta.json");
        assertTrue(oldDir.mkdirs() && newDir.mkdirs());
        File f = new File(oldDir, "a");
        Files.write(f.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        long old = System.currentTimeMillis() - 7200_000L;
        assertTrue(f.setLastModified(old) && oldDir.setLastModified(old));
        assertEquals(1, SentryStorage.cleanMovingLeftovers(root, 3600_000L));
        assertFalse(oldDir.exists());
        assertTrue("방금 만든 임시 폴더는 둔다", newDir.exists());
        assertTrue("진짜 이벤트는 건드리지 않는다", real.isDirectory());
    }

    @Test
    public void lastModifiedTree_returnsNewestFileTime() throws IOException {
        File d = tmp.newFolder("d6");
        File a = new File(d, "a"), b = new File(d, "b");
        Files.write(a.toPath(), "1".getBytes(StandardCharsets.UTF_8));
        Files.write(b.toPath(), "2".getBytes(StandardCharsets.UTF_8));
        assertTrue(a.setLastModified(1_000_000_000_000L));
        assertTrue(b.setLastModified(1_700_000_000_000L));
        assertTrue(d.setLastModified(1_000_000_000_000L));
        assertEquals(1_700_000_000_000L, SentryStorage.lastModifiedTree(d));
    }

    @Test
    public void estimateJson_followsTheFrameRateSetting() throws Exception {
        org.json.JSONObject normal = new org.json.JSONObject(); normal.put("fps", "normal");
        org.json.JSONObject reduced = new org.json.JSONObject(); reduced.put("fps", "reduced");
        org.json.JSONObject low = new org.json.JSONObject(); low.put("fps", "low");
        org.json.JSONObject n = SentryStorage.estimateJson(10000, SentrySettings.fromJson(normal));
        org.json.JSONObject r = SentryStorage.estimateJson(10000, SentrySettings.fromJson(reduced));
        org.json.JSONObject l = SentryStorage.estimateJson(10000, SentrySettings.fromJson(low));
        // 기본 해상도는 원본(4000kbps@15fps) — 10fps면 2666kbps라 1.5배 오래, 5fps면 1333kbps라 3배 오래 저장된다
        assertEquals(5.6, n.getDouble("original"), 0.0001);
        assertEquals(8.3, r.getDouble("original"), 0.0001);
        assertEquals(16.7, l.getDouble("original"), 0.0001);
        assertEquals(14.8, n.getDouble("hd"), 0.0001);
        assertTrue("프레임을 낮추면 모든 화질의 예상 시간이 늘어난다", r.getDouble("hd") > n.getDouble("hd") && r.getDouble("fhd") > n.getDouble("fhd") && l.getDouble("hd") >= r.getDouble("hd"));
    }

    @Test
    public void estimateJson_usesManualBitrateForTheSelectedResolution() throws Exception {
        org.json.JSONObject o = new org.json.JSONObject(); o.put("resolution", "hd"); o.put("bitrate_kbps", 3000);
        org.json.JSONObject e = SentryStorage.estimateJson(10000, SentrySettings.fromJson(o));
        assertEquals(7.4, e.getDouble("hd"), 0.0001);   // 선택한 화질(HD)은 수동 3000kbps 기준
        assertEquals(7.4, e.getDouble("fhd"), 0.0001);   // 나머지는 자동값(FHD 3000)
    }
}
