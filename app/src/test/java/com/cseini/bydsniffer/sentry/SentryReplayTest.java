package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cseini.motiondetect.Roi;

import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class SentryReplayTest {

    @Test
    public void cellRoisCoverFourCellsAndTrimOsdBandOnBottomOnly() {
        List<Roi> r = SentryReplay.cellRois(0.03f, 2000L, 1920);
        assertEquals(4, r.size());
        float yb = 1f - 64f / 1920f;
        assertEquals(0.5f, r.get(0).ny[2], 1e-5);          // 위 두 칸은 그대로 0.5까지
        assertEquals(0.5f, r.get(1).ny[2], 1e-5);
        assertEquals(yb, r.get(2).ny[2], 1e-5);            // 아래 두 칸은 표시줄 64px을 뺌
        assertEquals(yb, r.get(3).ny[2], 1e-5);
        assertEquals(0.5f, r.get(3).nx[0], 1e-5);          // 우하 칸은 x 0.5부터
        assertEquals(0.03f, r.get(0).minMotionPct, 1e-6);
        assertEquals(2000L, r.get(0).sustainMs);
    }

    @Test
    public void runIdFormatAndValidation() {
        String id = SentryReplay.newRunId(1_791_000_000_000L);
        assertTrue(id, SentryReplay.validRunId(id));
        assertFalse(SentryReplay.validRunId("../x"));
        assertFalse(SentryReplay.validRunId(null));
        assertFalse(SentryReplay.validRunId("r2026"));
    }

    @Test
    public void imageNameIsFileSafeAndSortable() {
        String n = SentryReplay.imageName("2026-10-08_17h06m12s", 125_400L, 0);
        assertEquals("2026-10-08_17h06m12s_02m05s_c0.jpg", n);
        assertTrue(n.matches("^[0-9A-Za-z_\\-]+\\.jpg$"));
    }

    @Test
    public void replaySettingsForceIdentityLayoutAndApplyOnlyAllowedOverrides() throws Exception {
        SentrySettings base = SentrySettings.fromJson(new JSONObject("{\"sensitivity\":30,\"layout_map\":[3,0,1,2],\"roi_enabled\":true,\"motion_only\":false}"));
        JSONObject over = new JSONObject("{\"sensitivity\":100,\"storage\":\"usb\",\"min_detect_s\":3}");
        SentrySettings rs = SentryReplay.replaySettings(base, over);
        assertTrue(Arrays.equals(new int[]{0, 1, 2, 3}, rs.layoutMap));   // 영상에 이미 배치가 들어 있다
        assertFalse(rs.roiEnabled);
        assertTrue(rs.motionOnly);
        assertEquals(100, rs.sensitivity);                                  // 허용된 덮어쓰기
        assertEquals(3, rs.minDetectS);
        assertFalse("usb".equals(rs.storage));                              // 허용 목록 밖은 무시
        assertEquals(30, SentryReplay.replaySettings(base, null).sensitivity);
    }

    @Test
    public void reportPageShowsMarksObjectsAndEscapes() throws Exception {
        JSONObject rep = new JSONObject("{\"run\":\"r20261009_030000\",\"done\":true,\"sensitivity\":100,\"minDetectS\":2,\"lightChangeFilter\":true,\"note\":\"n<b>\","
                + "\"totals\":{\"events\":1,\"marks\":2,\"person\":1,\"vehicle\":0},"
                + "\"events\":[{\"id\":\"2026-10-08_17h06m12s\",\"durationS\":600.0,\"segments\":10,\"marks\":["
                + "{\"t\":127.3,\"cell\":0,\"pct\":9,\"dur\":6.2,\"kind\":2,\"obj\":\"사람 87%\",\"img\":\"a_c0.jpg\"},"
                + "{\"t\":300.0,\"cell\":3,\"pct\":4,\"dur\":1.0,\"kind\":0},"
                + "{\"t\":400.0,\"cell\":0,\"pct\":5,\"dur\":2.0,\"kind\":3}]}]}");
        String h = SentryReplayPage.build(rep, null, Arrays.asList("r20261009_030000"), "r20261009_030000");
        assertTrue(h.contains("사람 87%"));
        assertTrue(h.contains("02:07"));                       // 127.3초
        assertTrue(h.contains("좌상") && h.contains("우하"));
        assertTrue(h.contains("/sentry/replay/img?run=r20261009_030000&f=a_c0.jpg"));
        assertTrue(h.contains("움직임"));
        assertTrue(h.contains("조명 번쩍임 의심"));
        assertTrue(h.contains("n&lt;b&gt;"));                  // 이스케이프
        assertTrue(h.contains("/sentry/play?id=2026-10-08_17h06m12s"));
    }

    @Test
    public void reportPageWithoutReportExplainsHowToStart() {
        String h = SentryReplayPage.build(null, null, null, "");
        assertTrue(h.contains("보고서가 아직 없습니다"));
    }
}
