package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.TimeZone;

public class SentryIndexTest {

    private static final long MS_BASE = 1_664_012_400_000L;  // 2022-09-25 08:00:00 UTC+0
    private static final TimeZone KST = TimeZone.getTimeZone("Asia/Seoul");

    // ── newEventId(ms, tz) 형식 검증 ──────────────────────

    @Test
    public void newEventId_format() {
        String id = SentryIndex.newEventId(MS_BASE, KST);
        // "2022-09-25_17h00m00s" (UTC+9)
        assertNotNull(id);
        assertTrue(id.matches("^\\d{4}-\\d{2}-\\d{2}_\\d{2}h\\d{2}m\\d{2}s$"));
    }

    @Test
    public void newEventId_defaultTz() {
        String id = SentryIndex.newEventId(MS_BASE);
        assertNotNull(id);
        assertTrue(id.matches("^\\d{4}-\\d{2}-\\d{2}_\\d{2}h\\d{2}m\\d{2}s$"));
    }

    // ── 녹화 전체 삭제(clearRoots) ──────────────────────

    @Test
    public void clearRoots_removesOnlyMonthFolders_acrossRoots_keepsRootAndOthers() throws Exception {
        java.io.File r1 = java.nio.file.Files.createTempDirectory("sd").toFile();
        java.io.File r2 = java.nio.file.Files.createTempDirectory("internal").toFile();
        java.io.File ev1 = new java.io.File(r1, "2026-10/05/2026-10-05_10h00m00s");
        java.io.File ev2 = new java.io.File(r2, "2026-09/30/2026-09-30_23h00m00s");
        assertTrue(ev1.mkdirs() && ev2.mkdirs());
        assertTrue(new java.io.File(ev1, "seg-000.mp4").createNewFile());
        assertTrue(new java.io.File(ev1, "seg-001.mp4.part").createNewFile());
        assertTrue(new java.io.File(ev2, "thumb.jpg").createNewFile());
        java.io.File other = new java.io.File(r1, "keep.txt");
        java.io.File otherDir = new java.io.File(r1, "notes");
        assertTrue(other.createNewFile() && otherDir.mkdirs());
        int n = SentryIndex.clearRoots(java.util.Arrays.asList(r1, r2, new java.io.File(r1, "없는폴더")));
        assertEquals(2, n);
        assertFalse(new java.io.File(r1, "2026-10").exists());
        assertFalse(new java.io.File(r2, "2026-09").exists());
        assertTrue(r1.isDirectory() && r2.isDirectory());
        assertTrue(other.exists() && otherDir.isDirectory());
    }

    // ── 조각 파일명(10-08: <이벤트id>_NNN.mp4, 옛 seg-NNN.mp4도 읽음) ──────────────────────

    @Test
    public void segFileName_isTimestampedAndSortsChronologically() {
        assertEquals("2026-10-08_20h48m34s_000.mp4", SentryIndex.segFileName("2026-10-08_20h48m34s", 0));
        assertEquals("2026-10-08_20h48m34s_012.mp4", SentryIndex.segFileName("2026-10-08_20h48m34s", 12));
        assertTrue(SentryIndex.segFileName("2026-10-08_20h48m34s", 9).compareTo(SentryIndex.segFileName("2026-10-08_20h50m15s", 0)) < 0);
    }

    @Test
    public void segNumber_readsNewAndOldNames() {
        assertEquals(Integer.valueOf(3), SentryIndex.segNumber("2026-10-08_20h48m34s_003.mp4"));
        assertEquals(Integer.valueOf(7), SentryIndex.segNumber("seg-007.mp4"));
        assertNull(SentryIndex.segNumber("seg-007.mp4.part"));
        assertNull(SentryIndex.segNumber("2026-10-08_20h48m34s_003.mp4.part"));
        assertNull(SentryIndex.segNumber("x_003.mp4"));
        assertNull(SentryIndex.segNumber("thumb.jpg"));
        assertNull(SentryIndex.segNumber("meta.json"));
    }

    // ── 시간축 표시(marks) ──────────────────────

    @Test
    public void eventMeta_marksRoundTripAndAreOmittedWhenEmpty() throws Exception {
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = "2026-10-09_00h13m07s";
        m.startMs = 1L;
        assertFalse(m.toJson().has("marks"));
        m.marks.add(new SentryIndex.Mark(6000L, 0, 0, 9, 2500L));
        m.marks.add(new SentryIndex.Mark(9000L, 1, 3, 5));
        SentryIndex.EventMeta back = SentryIndex.EventMeta.fromJson(new JSONObject(m.toJson().toString()));
        assertEquals(2, back.marks.size());
        assertEquals(6000L, back.marks.get(0).tMs);
        assertEquals(9, back.marks.get(0).pct);
        assertEquals(2500L, back.marks.get(0).durMs);
        assertEquals(1, back.marks.get(1).kind);
        assertEquals(3, back.marks.get(1).cell);
    }

    @Test
    public void eventMeta_objectsRoundTrip() throws Exception {
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = "2026-10-09_00h13m07s";
        m.startMs = 1L;
        assertFalse(m.toJson().has("objects"));
        m.objects.add(new float[]{2, 87, 0, 0.25f, 0.30f, 0.60f, 0.90f});
        SentryIndex.EventMeta back = SentryIndex.EventMeta.fromJson(new JSONObject(m.toJson().toString()));
        assertEquals(1, back.objects.size());
        assertEquals(2f, back.objects.get(0)[0], 0f);
        assertEquals(87f, back.objects.get(0)[1], 0f);
        assertEquals(0.25f, back.objects.get(0)[3], 1e-3);
        assertEquals(0.90f, back.objects.get(0)[6], 1e-3);
    }

    @Test
    public void eventMeta_retroFlagRoundTripsAndIsOmittedWhenFalse() throws Exception {
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = "2026-10-09_00h13m07s";
        m.startMs = 1L;
        assertFalse(m.toJson().has("retro"));
        m.retro = true;
        assertTrue(SentryIndex.EventMeta.fromJson(new JSONObject(m.toJson().toString())).retro);
    }

    @Test
    public void eventMeta_lightFlagRoundTripsAndIsOmittedWhenFalse() throws Exception {
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = "2026-10-09_02h31m15s";
        m.startMs = 1L;
        assertFalse(m.toJson().has("light"));
        m.light = true;
        assertTrue(SentryIndex.EventMeta.fromJson(new JSONObject(m.toJson().toString())).light);
    }

    @Test
    public void eventMeta_oldMetaWithoutMarksStillLoads() throws Exception {
        SentryIndex.EventMeta back = SentryIndex.EventMeta.fromJson(new JSONObject("{\"id\":\"2026-10-09_00h13m07s\",\"startMs\":1,\"durationS\":5,\"segments\":[]}"));
        assertTrue(back.marks.isEmpty());
    }

    // ── isValidId ──────────────────────

    @Test
    public void isValidId_valid() {
        assertTrue(SentryIndex.isValidId("2022-09-25_17h00m00s"));
    }

    @Test
    public void isValidId_null() {
        assertFalse(SentryIndex.isValidId(null));
    }

    @Test
    public void isValidId_pathTraversal() {
        assertFalse(SentryIndex.isValidId("../2022-09-25_17h00m00s"));
    }

    @Test
    public void isValidId_absolutePath() {
        assertFalse(SentryIndex.isValidId("/2022-09-25_17h00m00s"));
    }

    @Test
    public void isValidId_tooLong() {
        assertFalse(SentryIndex.isValidId("2022-09-25_17h00m00s_extra"));
    }

    @Test
    public void isValidId_unicode() {
        assertFalse(SentryIndex.isValidId("2022-09-25_17h00m0０s"));  // 전각 0
    }

    @Test
    public void isValidId_newline() {
        assertFalse(SentryIndex.isValidId("2022-09-25_17h00m00s\n"));
    }

    @Test
    public void isValidId_wrongFormat() {
        assertFalse(SentryIndex.isValidId("2022/09/25_17h00m00s"));
    }

    // ── EventMeta toJson/fromJson ──────────────────────

    @Test
    public void eventMeta_roundTrip() throws JSONException {
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = "2022-09-25_17h00m00s";
        m.startMs = MS_BASE;
        m.durationS = 30.5;
        m.favorite = true;

        SentryIndex.Segment s1 = new SentryIndex.Segment();
        s1.n = 0;
        s1.bytes = 1024000;
        s1.durationS = 15.0;
        m.segments.add(s1);

        SentryIndex.Segment s2 = new SentryIndex.Segment();
        s2.n = 1;
        s2.bytes = 1024000;
        s2.durationS = 15.5;
        m.segments.add(s2);

        JSONObject j = m.toJson();
        SentryIndex.EventMeta m2 = SentryIndex.EventMeta.fromJson(j);

        assertEquals(m.id, m2.id);
        assertEquals(m.startMs, m2.startMs);
        assertEquals(m.durationS, m2.durationS, 0.001);
        assertEquals(m.favorite, m2.favorite);
        assertEquals(m.segments.size(), m2.segments.size());
        assertEquals(m.segments.get(0).n, m2.segments.get(0).n);
        assertEquals(m.segments.get(0).bytes, m2.segments.get(0).bytes);
        assertEquals(m.segments.get(1).durationS, m2.segments.get(1).durationS, 0.001);
    }

    @Test
    public void eventMeta_noSegments() throws JSONException {
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = "2022-09-25_17h00m00s";
        m.startMs = MS_BASE;
        m.durationS = 0;
        m.favorite = false;
        // segments는 빈 리스트

        JSONObject j = m.toJson();
        SentryIndex.EventMeta m2 = SentryIndex.EventMeta.fromJson(j);

        assertEquals(m.id, m2.id);
        assertTrue(m2.segments.isEmpty());
    }

    // ── sampleSize ──────────────────────

    @Test
    public void sampleSize_2560_to_640() {
        int ss = SentryIndex.sampleSize(2560, 640);
        assertEquals(4, ss);
    }

    @Test
    public void sampleSize_2560_to_2560() {
        int ss = SentryIndex.sampleSize(2560, 2560);
        assertEquals(1, ss);
    }

    @Test
    public void sampleSize_2560_to_1280() {
        int ss = SentryIndex.sampleSize(2560, 1280);
        assertEquals(2, ss);
    }

    @Test
    public void sampleSize_1920_to_640() {
        int ss = SentryIndex.sampleSize(1920, 640);
        assertEquals(2, ss);
    }


    @Test
    public void sortSegmentsByN_ascending() {
        java.util.List<SentryIndex.Segment> list = new java.util.ArrayList<>();
        for (int n : new int[]{2, 0, 3, 1}) {
            SentryIndex.Segment sg = new SentryIndex.Segment();
            sg.n = n;
            list.add(sg);
        }
        SentryIndex.sortSegmentsByN(list);
        for (int k = 0; k < list.size(); k++) assertEquals(k, list.get(k).n);
    }

    @Test
    public void rev_changesWhenListChangesAndLooksLikeBootDotCounter() {
        String r0 = SentryIndex.rev();
        assertTrue(r0, r0.matches("[0-9a-z]+\\.[0-9]+"));
        SentryIndex.bumpRev();
        String r1 = SentryIndex.rev();
        assertTrue(!r0.equals(r1));
        assertEquals(r1, SentryIndex.rev());   // 바뀌지 않으면 같은 값
    }
}
