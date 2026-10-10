package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** SentryManager의 순수 static 로직(gate·trimStatus·needRestart·recoverDelayMs). 상태기계·스레드는 실기 검증. */
public class SentryManagerTest {

    // gate(enabled, ignition, rtcActive, detectActive, timeAllowed, pausedByLocation, lowVoltage, pauseWhenPhoneNear, phoneNearFresh)
    private static String gateOk() {
        return SentryManager.gate(true, 0, false, false, true, false, false, false, false, null);
    }

    @Test
    public void gate_allConditionsMet_passes() {
        assertNull(gateOk());
    }

    @Test
    public void gate_storageYield_blocksAfterVoltage_onlyWhenGiven() {
        assertEquals("저장소 내림(pause:보호)", SentryManager.gate(true, 0, false, false, true, false, false, false, false, "pause:보호"));
        assertEquals("12V 저전압", SentryManager.gate(true, 0, false, false, true, false, true, false, false, "pause:보호"));
        assertNull(SentryManager.gate(true, 0, false, false, true, false, false, false, false, null));
    }

    @Test
    public void objectPass_nullPasses_thresholdsPerKind() {
        assertTrue(SentryManager.objectPass(null, true, true));                       // 측정 불가는 통과
        assertTrue(SentryManager.objectPass(new float[]{0.40f, 0f}, true, false));    // 사람 기준 0.40
        assertFalse(SentryManager.objectPass(new float[]{0.39f, 0.9f}, true, false)); // 사람만 켰으면 차량 점수는 안 본다
        assertTrue(SentryManager.objectPass(new float[]{0f, 0.45f}, false, true));    // 차량 기준 0.45
        assertFalse(SentryManager.objectPass(new float[]{0.9f, 0.44f}, false, true));
        assertTrue(SentryManager.objectPass(new float[]{0.1f, 0.5f}, true, true));    // 둘 다 켜면 어느 쪽이든
        assertFalse(SentryManager.objectPass(new float[]{0.1f, 0.1f}, true, true));
    }

    @Test
    public void gate_camOff_blocksAfterIgnitionAndEnabled() {
        assertEquals("카메라 기능 꺼짐(앱 설정)", SentryManager.gate(true, 0, true, false, false, true, false, false, false, false, null));
        assertEquals("센트리 꺼짐", SentryManager.gate(false, 0, true, false, false, true, false, false, false, false, null));
        assertEquals("시동 켜짐", SentryManager.gate(true, 1, true, false, false, true, false, false, false, false, null));
        assertEquals("카메라 기능 꺼짐(앱 설정)", SentryManager.gate(true, 0, true, true, true, true, false, false, false, false, null));
        assertNull(SentryManager.gate(true, 0, false, false, false, true, false, false, false, false, null));
    }

    @Test
    public void trimStatus_keepsPausedByLocation_evenWhenTrimming() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("state", "PAUSED");
        o.put("why", "안전 위치");
        o.put("pausedByLocation", true);
        o.put("err", new String(new char[400]).replace('\0', 'x'));   // 300자를 넘겨 줄이기 시작하게
        o.put("lastEvent", "2026-10-05_23h00m12s");
        o.put("fps", 26);
        String t = SentryManager.trimStatus(o);
        assertTrue(t.length() <= 300);
        assertTrue(new JSONObject(t).getBoolean("pausedByLocation"));
    }

    @Test
    public void remoteMonthOk_acceptsOnlyValidYearMonth() {
        assertTrue(SentryManager.remoteMonthOk("2026-10"));
        assertTrue(SentryManager.remoteMonthOk("2026-01"));
        assertFalse(SentryManager.remoteMonthOk("2026-13"));
        assertFalse(SentryManager.remoteMonthOk("2026-00"));
        assertFalse(SentryManager.remoteMonthOk("2026-1"));
        assertFalse(SentryManager.remoteMonthOk("2026-10-05"));
        assertFalse(SentryManager.remoteMonthOk("../2026"));
        assertFalse(SentryManager.remoteMonthOk(null));
    }

    @Test
    public void gate_disabled() {
        assertEquals("센트리 꺼짐", SentryManager.gate(false, 0, false, false, true, false, false, false, false, null));
    }

    @Test
    public void gate_ignition() {
        assertEquals("시동 켜짐", SentryManager.gate(true, 1, false, false, true, false, false, false, false, null));
        assertEquals("시동 상태 미확정", SentryManager.gate(true, -1, false, false, true, false, false, false, false, null));
    }

    @Test
    public void gate_rtcAndOtherDetect() {
        assertEquals("실시간 보기 중", SentryManager.gate(true, 0, true, false, true, false, false, false, false, null));
        assertEquals("다른 감지 사용 중", SentryManager.gate(true, 0, false, true, true, false, false, false, false, null));
    }

    @Test
    public void gate_timeLocationVoltage() {
        assertEquals("시간 제한", SentryManager.gate(true, 0, false, false, false, false, false, false, false, null));
        assertEquals("안전 위치", SentryManager.gate(true, 0, false, false, true, true, false, false, false, null));
        assertEquals("12V 저전압", SentryManager.gate(true, 0, false, false, true, false, true, false, false, null));
    }

    @Test
    public void gate_phoneNear_onlyWhenPauseOptionOn() {
        assertEquals("폰이 차 근처", SentryManager.gate(true, 0, false, false, true, false, false, true, true, null));
        assertNull(SentryManager.gate(true, 0, false, false, true, false, false, true, false, null));
        assertNull(SentryManager.gate(true, 0, false, false, true, false, false, false, true, null));
    }

    @Test
    public void trimStatus_under300_unchanged() throws JSONException {
        JSONObject o = new JSONObject().put("state", "ARMED").put("why", "카메라 대기").put("fps", 15);
        String before = o.toString();
        assertEquals(before, SentryManager.trimStatus(o));
    }

    @Test
    public void trimStatus_over300_trimmedAndKeepsState() throws JSONException {
        char[] filler = new char[250];
        Arrays.fill(filler, '가');
        JSONObject o = new JSONObject();
        o.put("state", "PAUSED");
        o.put("why", new String(filler));
        o.put("err", new String(filler));
        o.put("storage", "sd");
        o.put("tier", "public");
        o.put("usedMb", 123);
        o.put("limitMb", 10000);
        o.put("events", 7);
        o.put("lastEvent", "2026-10-05_01h02m03s");
        o.put("fallback", false);
        o.put("fps", 15);
        o.put("drop", 0);
        String out = SentryManager.trimStatus(o);
        assertTrue(out.length() <= 300);
        assertEquals("PAUSED", new JSONObject(out).getString("state"));
    }

    private static SentrySettings withJson(String key, Object val) throws JSONException {
        return SentrySettings.fromJson(new JSONObject().put(key, val));
    }

    @Test
    public void needRestart_storageChanged_true() throws JSONException {
        assertTrue(SentryManager.needRestart(SentrySettings.defaults(), withJson("storage", "sd")));
    }

    @Test
    public void needRestart_notifyChanged_false() throws JSONException {
        assertFalse(SentryManager.needRestart(SentrySettings.defaults(), withJson("notify", false)));
    }

    @Test
    public void needRestart_motionOnlyChanged_true() throws JSONException {
        assertTrue(SentryManager.needRestart(SentrySettings.defaults(), withJson("motion_only", true)));
    }

    @Test
    public void needRestart_sensitivityMatters_onlyInMotionMode() throws JSONException {
        SentrySettings cont = SentrySettings.defaults();
        assertFalse(SentryManager.needRestart(cont, withJson("sensitivity", 150)));
        SentrySettings m1 = SentrySettings.fromJson(new JSONObject().put("motion_only", true));
        SentrySettings m2 = SentrySettings.fromJson(new JSONObject().put("motion_only", true).put("sensitivity", 150));
        assertTrue(SentryManager.needRestart(m1, m2));
    }

    @Test
    public void recoverDelay_followsRemainingOffWait() {
        long now = 1_000_000L;
        assertEquals(5_000L, SentryManager.recoverDelayMs(0, now));                // OFF 기록 없음
        assertEquals(15_000L, SentryManager.recoverDelayMs(now - 5_000L, now));    // 5초 지남 → 남은 15초
        assertEquals(5_000L, SentryManager.recoverDelayMs(now - 18_000L, now));    // 남은 2초 → 최소 5초
        assertEquals(5_000L, SentryManager.recoverDelayMs(now - 60_000L, now));    // 이미 지남
        assertEquals(20_000L, SentryManager.recoverDelayMs(now + 5_000L, now));    // 시각 역행 → 20초
    }

    private static SentryIndex.EventMeta meta(String id, long startMs, double dur, boolean fav, long... segBytes) {
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = id;
        m.startMs = startMs;
        m.durationS = dur;
        m.favorite = fav;
        for (int i = 0; i < segBytes.length; i++) {
            SentryIndex.Segment s = new SentryIndex.Segment();
            s.n = i;
            s.bytes = segBytes[i];
            s.durationS = dur / segBytes.length;
            m.segments.add(s);
        }
        return m;
    }

    @Test
    public void buildRemoteList_assemblesFields() throws JSONException {
        List<SentryIndex.EventMeta> metas = new ArrayList<>();
        metas.add(meta("2026-10-05_14h02m10s", 2000L, 125.44, true, 30L * 1048576, 20L * 1048576));
        metas.add(meta("2026-10-05_09h00m00s", 1000L, 60.0, false, 5L * 1048576));
        JSONObject r = SentryManager.buildRemoteList("2026-10-05", metas, 6500);
        assertEquals("2026-10-05", r.getString("date"));
        assertFalse(r.getBoolean("more"));
        JSONArray ev = r.getJSONArray("events");
        assertEquals(2, ev.length());
        JSONObject a = ev.getJSONObject(0);
        assertEquals("2026-10-05_14h02m10s", a.getString("id"));
        assertEquals(2000L, a.getLong("s"));
        assertEquals(125.4, a.getDouble("d"), 0.0001);
        assertEquals(2, a.getInt("n"));
        assertEquals(50, a.getInt("b"));
        assertEquals(1, a.getInt("f"));
        assertEquals(0, ev.getJSONObject(1).getInt("f"));
        assertEquals(0, SentryManager.buildRemoteList("2026-10-05", new ArrayList<>(), 6500).getJSONArray("events").length());
    }

    @Test
    public void buildRemoteList_truncatesOldestAndSetsMore() throws JSONException {
        List<SentryIndex.EventMeta> metas = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            metas.add(meta(String.format("2026-10-05_%02dh%02dm00s", 23 - i / 60, 59 - i % 60), 1_000_000L - i, 60.0, false, 1048576L));
        }
        JSONObject r = SentryManager.buildRemoteList("2026-10-05", metas, 6500);
        assertTrue(r.getBoolean("more"));
        assertTrue(r.toString().length() <= 6500);
        JSONArray ev = r.getJSONArray("events");
        assertTrue(ev.length() > 0 && ev.length() < 200);
        assertEquals(metas.get(0).id, ev.getJSONObject(0).getString("id"));
        assertEquals(metas.get(ev.length() - 1).id, ev.getJSONObject(ev.length() - 1).getString("id"));
    }
    @org.junit.Test
    public void marksJson_includesOnlyPresentKeysAndRetro() throws Exception {
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = "2026-10-09_00h13m07s";
        m.startMs = 1L;
        org.json.JSONObject empty = SentryManager.marksJson(m);
        org.junit.Assert.assertEquals("2026-10-09_00h13m07s", empty.getString("id"));
        org.junit.Assert.assertFalse(empty.has("marks"));
        org.junit.Assert.assertFalse(empty.has("objects"));
        org.junit.Assert.assertFalse(empty.has("retro"));
        m.marks.add(new SentryIndex.Mark(6000L, 1, 0, 8, 2900L));
        m.objects.add(new float[]{1, 76, 0, 0.5f, 0.25f, 0.95f, 0.55f});
        m.retro = true;
        org.json.JSONObject full = SentryManager.marksJson(m);
        org.junit.Assert.assertEquals(6000L, full.getJSONArray("marks").getJSONArray(0).getLong(0));
        org.junit.Assert.assertEquals(76, full.getJSONArray("objects").getJSONArray(0).getInt(1));
        org.junit.Assert.assertTrue(full.getBoolean("retro"));
    }

    @Test
    public void armBackoff_growsFromOneToFifteenMinutes() {
        assertEquals(0L, SentryManager.armBackoffMs(0));
        assertEquals(60_000L, SentryManager.armBackoffMs(1));
        assertEquals(120_000L, SentryManager.armBackoffMs(2));
        assertEquals(240_000L, SentryManager.armBackoffMs(3));
        assertEquals(480_000L, SentryManager.armBackoffMs(4));
        assertEquals(900_000L, SentryManager.armBackoffMs(5));
        assertEquals(900_000L, SentryManager.armBackoffMs(50));
    }
}
