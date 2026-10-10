package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class SentrySettingsTest {

    // ── defaults() → toJson() → fromJson() 왕복 ──────────────────────

    @Test
    public void defaults_roundTrip() throws JSONException {
        SentrySettings s = SentrySettings.defaults();
        JSONObject j = s.toJson();
        SentrySettings s2 = SentrySettings.fromJson(j);

        assertEquals(s.enabled, s2.enabled);
        assertEquals(s.motionOnly, s2.motionOnly);
        assertEquals(s.storage, s2.storage);
        assertEquals(s.limitMb, s2.limitMb);
        assertEquals(s.resolution, s2.resolution);
        assertEquals(s.mode, s2.mode);
        assertEquals(s.layout, s2.layout);
        assertEquals(s.osdDatetime, s2.osdDatetime);
        assertEquals(s.osdBattery, s2.osdBattery);
        assertEquals(s.osdPos, s2.osdPos);
        assertEquals(s.notify, s2.notify);
        assertEquals(s.timeLimitEnabled, s2.timeLimitEnabled);
        assertEquals(s.timeMode, s2.timeMode);
        assertEquals(s.timeFrom, s2.timeFrom);
        assertEquals(s.timeTo, s2.timeTo);
        assertEquals(s.safeLocationEnabled, s2.safeLocationEnabled);
        assertEquals(s.hazardOnMotion, s2.hazardOnMotion);
        assertEquals(s.screenMessage, s2.screenMessage);
        assertEquals(s.sensitivity, s2.sensitivity);
        assertEquals(s.minDetectS, s2.minDetectS);
        assertEquals(s.roiEnabled, s2.roiEnabled);
        assertEquals(s.prerollS, s2.prerollS);
        assertEquals(s.postS, s2.postS);
        assertEquals(s.fps, s2.fps);
        assertEquals(s.bitrateKbps, s2.bitrateKbps);
        assertEquals(s.codec, s2.codec);
        assertEquals(s.shadowFilter, s2.shadowFilter);
        assertEquals(s.lightChangeFilter, s2.lightChangeFilter);
        assertEquals(s.pauseWhenPhoneNear, s2.pauseWhenPhoneNear);
    }

    @Test
    public void lightChangeFilter_defaultTrue_andRoundTripFalse() throws JSONException {
        SentrySettings d = SentrySettings.defaults();
        assertTrue(d.lightChangeFilter);
        assertEquals(true, d.toJson().getBoolean("light_change_filter"));

        JSONObject o = new JSONObject();
        o.put("light_change_filter", false);
        SentrySettings s = SentrySettings.fromJson(o);
        assertFalse(s.lightChangeFilter);
        assertFalse(SentrySettings.fromJson(s.toJson()).lightChangeFilter);
    }

    @Test
    public void cellViews_defaultEmpty_normalizes_roundTrips_andRestartsSession() throws JSONException {
        SentrySettings d = SentrySettings.defaults();
        assertEquals(0, d.cellViews.length());
        assertEquals(0, d.toJson().getJSONObject("cell_views").length());

        JSONObject o = new JSONObject();
        JSONObject cvs = new JSONObject();
        cvs.put("3", new JSONObject().put("dw", true).put("fov", 175).put("pan", true));
        cvs.put("9", new JSONObject().put("dw", true));      // 범위 밖 칸 번호는 버린다
        cvs.put("2", "문자열");                                // 객체가 아닌 값은 버린다
        o.put("cell_views", cvs);
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(1, s.cellViews.length());
        assertTrue(s.cellViews.getJSONObject("3").getBoolean("dw"));

        // prefs 왕복(문자열로 저장돼 돌아옴)
        JSONObject viaPrefs = new JSONObject();
        viaPrefs.put("cell_views", s.toJson().getJSONObject("cell_views").toString());
        SentrySettings r = SentrySettings.fromJson(viaPrefs);
        assertEquals(s.cellViews.toString(), r.cellViews.toString());

        assertTrue(SentryManager.needRestart(d, s));
        assertFalse(SentryManager.needRestart(s, r));
    }

    @Test
    public void mergeCellViews_perCell_nullDeletes_wholeNullClears() throws JSONException {
        JSONObject cur = new JSONObject();
        cur.put("1", new JSONObject().put("dw", true));
        cur.put("2", new JSONObject().put("dw", false).put("mir", true));

        JSONObject patch = new JSONObject();
        patch.put("2", new JSONObject().put("dw", true).put("fov", 160));   // 2번 칸 바꿈
        patch.put("4", new JSONObject().put("pan", true));                   // 4번 칸 추가
        JSONObject m = SentrySettings.mergeCellViews(cur, patch);
        assertTrue(m.getJSONObject("1").getBoolean("dw"));                    // 안 건드린 칸은 그대로
        assertTrue(m.getJSONObject("2").getBoolean("dw"));
        assertFalse(m.getJSONObject("2").has("mir"));                         // 칸 값은 통째로 교체
        assertTrue(m.has("4"));

        JSONObject del = new JSONObject();
        del.put("1", JSONObject.NULL);
        JSONObject m2 = SentrySettings.mergeCellViews(m, del);
        assertFalse(m2.has("1"));
        assertTrue(m2.has("2"));

        assertEquals(0, SentrySettings.mergeCellViews(m2, JSONObject.NULL).length());   // 통째 null=전부 삭제
        assertEquals(m2.toString(), SentrySettings.mergeCellViews(m2, new JSONObject()).toString());   // 빈 객체=변화 없음
        assertEquals(m2.toString(), SentrySettings.mergeCellViews(m2, 5).toString());                    // 잘못된 값=현재 유지
    }

    @Test
    public void layout_defaultsIdentity_andRoundTrips() throws JSONException {
        SentrySettings d = SentrySettings.defaults();
        assertTrue(SentryLayout.isIdentity(d.layoutMap, d.layoutFlip));
        assertEquals("[0,1,2,3]", d.toJson().getJSONArray("layout_map").toString());

        JSONObject o = new JSONObject();
        o.put("layout_map", new org.json.JSONArray("[3,1,2,0]"));
        o.put("layout_flip", new org.json.JSONArray("[1,0,2,3]"));
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(3, s.layoutMap[0]);
        assertEquals(3, s.layoutFlip[3]);
        SentrySettings r = SentrySettings.fromJson(s.toJson());
        assertTrue(java.util.Arrays.equals(s.layoutMap, r.layoutMap));
        assertTrue(java.util.Arrays.equals(s.layoutFlip, r.layoutFlip));

        // ★실차 버그(775·776): 저장소에는 배열이 문자열로 저장돼 load()가 문자열을 돌려준다 — 그 경로를 그대로 재현해 값이 살아남아야 한다
        JSONObject stored = new JSONObject();
        stored.put("layout_map", s.toJson().get("layout_map").toString());
        stored.put("layout_flip", s.toJson().get("layout_flip").toString());
        SentrySettings fromPrefs = SentrySettings.fromJson(stored);
        assertTrue(java.util.Arrays.equals(s.layoutMap, fromPrefs.layoutMap));
        assertTrue(java.util.Arrays.equals(s.layoutFlip, fromPrefs.layoutFlip));

        JSONObject bad = new JSONObject();
        bad.put("layout_map", new org.json.JSONArray("[1,1,1,1]"));
        assertTrue(SentryLayout.isIdentity(SentrySettings.fromJson(bad).layoutMap, new int[]{0, 0, 0, 0}));
    }

    @Test
    public void osdPos_sixValues_legacyKeepsLeftLook_centerAndGarbageUseDefault() throws JSONException {
        String[] ok = {"top_left", "top_center", "top_right", "bottom_left", "bottom_center", "bottom_right"};
        for (String v : ok) {
            JSONObject o = new JSONObject();
            o.put("osd_pos", v);
            assertEquals(v, SentrySettings.fromJson(o).osdPos);
            assertEquals(v, SentrySettings.fromJson(SentrySettings.fromJson(o).toJson()).osdPos);
        }
        assertEquals("top_left", SentrySettings.defaults().osdPos);
        assertEquals("top_left", SentrySettings.normalizeOsdPos("top"));          // 옛 값 — 그때 모양(왼쪽 정렬) 유지
        assertEquals("bottom_left", SentrySettings.normalizeOsdPos("bottom"));
        assertEquals("top_left", SentrySettings.normalizeOsdPos("center"));       // 세로 가운데는 지원 중단
        assertEquals("top_left", SentrySettings.normalizeOsdPos("nope"));
        assertEquals("top_right", SentrySettings.normalizeOsdPos(" TOP_RIGHT "));
        assertEquals("top_left", SentrySettings.normalizeOsdPos(null));
    }

    // ── 범위 클램프 ──────────────────────

    @Test
    public void sensitivity_clamped() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("sensitivity", 0);  // 최소 1
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(1, s.sensitivity);

        o.put("sensitivity", 300);  // 최대 200
        s = SentrySettings.fromJson(o);
        assertEquals(200, s.sensitivity);
    }

    @Test
    public void prerollS_clamped() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("preroll_s", 25);  // 최대 20
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(20, s.prerollS);

        o.put("preroll_s", 0);  // 최소 1
        s = SentrySettings.fromJson(o);
        assertEquals(1, s.prerollS);
    }

    @Test
    public void postS_clamped() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("post_s", 0);  // 최소 1
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(1, s.postS);

        o.put("post_s", 70);  // 최대 60
        s = SentrySettings.fromJson(o);
        assertEquals(60, s.postS);
    }

    @Test
    public void limitMb_clamped() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("limit_mb", 500);  // 최소 1000
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(1000, s.limitMb);
    }

    // ── enum 값 검증 ──────────────────────

    @Test
    public void storage_unknownEnum_defaultValue() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("storage", "unknown");
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals("internal", s.storage);  // 기본값
    }

    @Test
    public void fps_unknownEnum_defaultValue() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("fps", "ultrafast");
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals("normal", s.fps);  // 기본값
    }

    @Test
    public void codec_unknownEnum_defaultValue() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("codec", "vp9");
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals("h264", s.codec);  // 기본값
    }

    // ── effectiveBitrateKbps ──────────────────────

    @Test
    public void effectiveBitrateKbps_manual() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("bitrate_kbps", 2000);
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(2000, s.effectiveBitrateKbps());
    }

    @Test
    public void effectiveBitrateKbps_autoOriginal() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("bitrate_kbps", 0);  // 자동
        o.put("resolution", "original");
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(4000, s.effectiveBitrateKbps());
    }

    @Test
    public void effectiveBitrateKbps_autoFhd() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("bitrate_kbps", 0);
        o.put("resolution", "fhd");
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(3000, s.effectiveBitrateKbps());
    }

    @Test
    public void effectiveBitrateKbps_autoScalesWithFps() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("bitrate_kbps", 0);
        o.put("resolution", "original");
        o.put("fps", "reduced");
        assertEquals(2666, SentrySettings.fromJson(o).effectiveBitrateKbps());
        o.put("fps", "low");
        assertEquals(1333, SentrySettings.fromJson(o).effectiveBitrateKbps());
        o.put("resolution", "hd");
        assertEquals(500, SentrySettings.fromJson(o).effectiveBitrateKbps());   // 1500*5/15=500, 하한
    }

    @Test
    public void effectiveBitrateKbps_manualIgnoresFps() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("bitrate_kbps", 2000);
        o.put("fps", "low");
        assertEquals(2000, SentrySettings.fromJson(o).effectiveBitrateKbps());
    }

    @Test
    public void effectiveBitrateKbps_autoHd() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("bitrate_kbps", 0);
        o.put("resolution", "hd");
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(1500, s.effectiveBitrateKbps());
    }

    // ── targetWidth ──────────────────────

    @Test
    public void targetWidth_hd() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("resolution", "hd");
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(1280, s.targetWidth());
    }

    @Test
    public void targetWidth_fhd() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("resolution", "fhd");
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(1920, s.targetWidth());
    }

    @Test
    public void targetWidth_original() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("resolution", "original");
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(0, s.targetWidth());
    }

    // ── targetFps ──────────────────────

    @Test
    public void targetFps_low() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("fps", "low");
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(5, s.targetFps());
    }

    @Test
    public void targetFps_reduced() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("fps", "reduced");
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(10, s.targetFps());
    }

    @Test
    public void targetFps_normal() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("fps", "normal");
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(15, s.targetFps());
    }

    // ── minMotionPct ──────────────────────

    @Test
    public void minMotionPct_sensitivity100() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("sensitivity", 100);
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(0.045f, s.minMotionPct(), 0.0001f);   // 기본 감도 100 = 4.5%(조명 오탐 3.0~4.3% 위, 실제 차 4.7% 아래)
    }

    @Test
    public void minMotionPct_sensitivity200() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("sensitivity", 200);
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(0.0225f, s.minMotionPct(), 0.0001f);
    }

    @Test
    public void minMotionPct_defaultSensitivityStaysAboveKnownFalsePositivesAndBelowRealPasses() throws JSONException {
        // 10-09 실측: 조명 오탐 구간 최고 ≤4.3%, 실제 차 ≥4.7% — 기본(100)은 그 사이
        float def = SentrySettings.fromJson(new JSONObject()).minMotionPct();
        assertEquals(0.045f, def, 0.0001f);
        assertTrue(def > 0.043f && def < 0.047f);
        // 감도를 올리면 임계가 비례해서 낮아지고, 내리면 높아진다
        JSONObject hi = new JSONObject(); hi.put("sensitivity", 150);
        JSONObject lo = new JSONObject(); lo.put("sensitivity", 50);
        assertEquals(0.03f, SentrySettings.fromJson(hi).minMotionPct(), 0.0001f);
        assertEquals(0.09f, SentrySettings.fromJson(lo).minMotionPct(), 0.0001f);
    }

    @Test
    public void minMotionPct_sensitivity1() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("sensitivity", 1);
        SentrySettings s = SentrySettings.fromJson(o);
        // 0.045 * (100 / 1) = 4.5 → 클램프 3.0
        assertEquals(3.0f, s.minMotionPct(), 0.0001f);
    }

    // ── sustainMs ──────────────────────

    @Test
    public void sustainMs() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("min_detect_s", 3);
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(3000L, s.sustainMs());
    }

    // ── rois() ──────────────────────

    @Test
    public void rois_disabled_returnsFullFrame() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("roi_enabled", false);
        SentrySettings s = SentrySettings.fromJson(o);
        assertTrue(s.rois().size() > 0);  // 기본 1개 ROI
    }

    @Test
    public void rois_enabled_returnsCustom() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("roi_enabled", true);
        JSONArray rois = new JSONArray();
        JSONObject roi = new JSONObject();
        JSONArray nx = new JSONArray();
        JSONArray ny = new JSONArray();
        nx.put(0).put(1).put(1).put(0);
        ny.put(0).put(0).put(1).put(1);
        roi.put("nx", nx).put("ny", ny);
        rois.put(roi);
        o.put("rois", rois);
        SentrySettings s = SentrySettings.fromJson(o);
        assertEquals(1, s.rois().size());
    }

    // ── timeAllows() ──────────────────────

    @Test
    public void timeAllows_disabled_alwaysTrue() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("time_limit_enabled", false);
        SentrySettings s = SentrySettings.fromJson(o);
        assertTrue(s.timeAllows(System.currentTimeMillis()));
    }

    @Test
    public void timeAllows_denyMode_inside() throws JSONException {
        // deny 06:00~18:00, 12:00(720분)에서 테스트
        boolean allowed = SentrySettings.timeAllows(true, "deny", "06:00", "18:00", 720);
        assertFalse(allowed);
    }

    @Test
    public void timeAllows_denyMode_outside() throws JSONException {
        // deny 06:00~18:00, 20:00(1200분)에서 테스트
        boolean allowed = SentrySettings.timeAllows(true, "deny", "06:00", "18:00", 1200);
        assertTrue(allowed);
    }

    @Test
    public void timeAllows_allowMode_inside() throws JSONException {
        // allow 06:00~18:00, 12:00에서 테스트
        boolean allowed = SentrySettings.timeAllows(true, "allow", "06:00", "18:00", 720);
        assertTrue(allowed);
    }

    @Test
    public void timeAllows_allowMode_outside() throws JSONException {
        // allow 06:00~18:00, 20:00에서 테스트
        boolean allowed = SentrySettings.timeAllows(true, "allow", "06:00", "18:00", 1200);
        assertFalse(allowed);
    }

    @Test
    public void timeAllows_midnightOverflow_denyMode() throws JSONException {
        // deny 22:00~06:00(자정 넘김), 23:00과 03:00에서 테스트
        boolean at23 = SentrySettings.timeAllows(true, "deny", "22:00", "06:00", 23 * 60);
        boolean at03 = SentrySettings.timeAllows(true, "deny", "22:00", "06:00", 3 * 60);
        boolean at12 = SentrySettings.timeAllows(true, "deny", "22:00", "06:00", 12 * 60);
        assertFalse(at23);  // 금지 구간 안
        assertFalse(at03);  // 금지 구간 안
        assertTrue(at12);   // 금지 구간 밖
    }

    @Test
    public void timeAllows_fromEqualTo_emptyInterval() throws JSONException {
        // from == to는 빈 구간
        boolean allowed = SentrySettings.timeAllows(true, "deny", "12:00", "12:00", 720);
        assertTrue(allowed);  // 빈 구간이므로 항상 allow
    }

    // ── clampLimitMb ──────────────────────

    @Test
    public void clampLimitMb_zero_halfOfFree() {
        int clamped = SentrySettings.clampLimitMb(0, 20000);  // free 20000, 0 입력
        assertEquals(10000, clamped);
    }

    @Test
    public void clampLimitMb_internalDefault_capsAt3GB() {
        assertEquals(3072, SentrySettings.clampLimitMb(0, 29000, true));    // 절반 14500이어도 3GB까지
        assertEquals(2000, SentrySettings.clampLimitMb(0, 4000, true));     // 절반이 3GB보다 작으면 절반
        assertEquals(1000, SentrySettings.clampLimitMb(0, 1500, true));     // 하한 1GB
        assertEquals(10000, SentrySettings.clampLimitMb(10000, 29000, true));   // 사용자가 정한 값은 존중
        assertEquals(14500, SentrySettings.clampLimitMb(0, 29000, false));  // 외장은 예전대로 절반
    }

    @Test
    public void clampLimitMb_zero_minimumThousand() {
        int clamped = SentrySettings.clampLimitMb(0, 1500);  // free 1500, 0 입력
        assertEquals(1000, clamped);
    }

    @Test
    public void clampLimitMb_maxOfFree() {
        int clamped = SentrySettings.clampLimitMb(50000, 20000);  // free 20000, 50000 입력
        assertEquals(20000, clamped);
    }

    @Test
    public void clampLimitMb_normalRange() {
        int clamped = SentrySettings.clampLimitMb(500, 20000);  // free 20000, 500 입력
        assertEquals(1000, clamped);  // 최소 1000
    }


    @Test
    public void internalOnly_keepsChosenStorageAsMoveTargetAndCapsInternalLimit() throws JSONException {
        JSONObject sd = new JSONObject(); sd.put("storage", "sd");
        assertEquals("sd", SentrySettings.fromJson(sd).storage);
        assertEquals(50000, SentrySettings.clampLimitMb(100000, 50000, true));   // 일반: 내장 한도를 사용자가 크게 정하면 그대로(여유까지)
        SentrySettings.setInternalOnly(true);
        try {
            JSONObject usb = new JSONObject(); usb.put("storage", "usb");
            assertEquals("씨라7 시험판에서도 고른 값은 옮길 곳으로 남는다", "usb", SentrySettings.fromJson(usb).storage);
            assertEquals("내장 임시 저장은 외장 한도를 크게 정해도 3GB까지만", 3072, SentrySettings.clampLimitMb(100000, 50000, true));
            assertEquals(3072, SentrySettings.clampLimitMb(0, 50000, true));
            assertEquals("외장 루트의 한도는 그대로", 50000, SentrySettings.clampLimitMb(100000, 50000, false));
            assertEquals("내장 여유가 적으면 그만큼", 1500, SentrySettings.clampLimitMb(100000, 1500, true));
        } finally {
            SentrySettings.setInternalOnly(false);
        }
        assertEquals(50000, SentrySettings.clampLimitMb(100000, 50000, true));
    }

    @Test
    public void internalOnly_clampsEncoderLoadAndIgnoresDewarpTables() throws JSONException {
        JSONObject heavy = new JSONObject();
        heavy.put("resolution", "original"); heavy.put("fps", "normal"); heavy.put("bitrate_kbps", 8000);
        heavy.put("cell_views", new JSONObject("{\"1\":{\"x\":1}}"));
        SentrySettings.setInternalOnly(true);
        try {
            SentrySettings s = SentrySettings.fromJson(heavy);
            assertEquals("hd", s.resolution);
            assertEquals("reduced", s.fps);
            assertEquals(2000, s.bitrateKbps);
            assertEquals(0, s.cellViews.length());
            assertEquals("reduced", SentrySettings.fromJson(new JSONObject()).fps);   // 기본도 10fps
            JSONObject low = new JSONObject(); low.put("fps", "low");
            assertEquals("low", SentrySettings.fromJson(low).fps);
        } finally {
            SentrySettings.setInternalOnly(false);
        }
        SentrySettings d = SentrySettings.fromJson(heavy);
        assertEquals("original", d.resolution);
        assertEquals("normal", d.fps);
        assertEquals(8000, d.bitrateKbps);
    }

    @Test
    public void objectToggles_defaultOnAndCombine() throws JSONException {
        SentrySettings d = SentrySettings.fromJson(new JSONObject());
        assertTrue(d.objectDetect && d.detectPerson && d.detectVehicle);
        assertTrue(d.objectsOn() && d.personOn() && d.vehicleOn() && d.lightJudgeOn());
        JSONObject off = new JSONObject(); off.put("object_detect", false);
        SentrySettings so = SentrySettings.fromJson(off);
        assertFalse(so.objectsOn() || so.personOn() || so.vehicleOn() || so.lightJudgeOn());
        JSONObject noCar = new JSONObject(); noCar.put("detect_vehicle", false);
        SentrySettings nc = SentrySettings.fromJson(noCar);
        assertTrue(nc.objectsOn() && nc.personOn() && !nc.vehicleOn());
        assertFalse("한쪽만 보면 조명 의심을 믿지 않는다", nc.lightJudgeOn());
        JSONObject none = new JSONObject(); none.put("detect_person", false); none.put("detect_vehicle", false);
        assertFalse("둘 다 끄면 object_detect 끔과 같다", SentrySettings.fromJson(none).objectsOn());
        // 설정 JSON에 실려 나간다
        JSONObject j = nc.toJson();
        assertTrue(j.getBoolean("object_detect") && j.getBoolean("detect_person") && !j.getBoolean("detect_vehicle"));
    }

    @Test
    public void whenFull_defaultsToOverwriteAndAcceptsStop() throws JSONException {
        SentrySettings d = SentrySettings.fromJson(new JSONObject());
        assertEquals("overwrite", d.whenFull);
        assertFalse(d.whenFullStop());
        JSONObject stop = new JSONObject(); stop.put("when_full", "stop");
        SentrySettings st = SentrySettings.fromJson(stop);
        assertTrue(st.whenFullStop());
        assertEquals("stop", st.toJson().getString("when_full"));
        JSONObject bad = new JSONObject(); bad.put("when_full", "delete_all");
        assertEquals("overwrite", SentrySettings.fromJson(bad).whenFull);   // 모르는 값은 기본(자동 삭제)
    }

    @Test
    public void summaryAnnounce_defaultsOnAndRoundTrips() throws JSONException {
        assertTrue(SentrySettings.fromJson(new JSONObject()).summaryAnnounce);
        JSONObject off = new JSONObject(); off.put("summary_announce", false);
        SentrySettings s = SentrySettings.fromJson(off);
        assertFalse(s.summaryAnnounce);
        assertFalse(s.toJson().getBoolean("summary_announce"));
    }

    @Test
    public void continuousEventS_defaultsTo600AndRoundsTo30WithinRange() throws JSONException {
        assertEquals(600, SentrySettings.fromJson(new JSONObject()).continuousEventS);
        assertEquals(600_000L, SentrySettings.fromJson(new JSONObject()).continuousEventMs());
        int[][] cases = {{30, 30}, {44, 30}, {45, 60}, {90, 90}, {300, 300}, {600, 600}, {615, 600}, {1800, 600}, {5000, 600}, {10, 30}, {0, 30}};
        for (int[] c : cases) {
            JSONObject o = new JSONObject(); o.put("continuous_event_s", c[0]);
            assertEquals("입력 " + c[0], c[1], SentrySettings.fromJson(o).continuousEventS);
        }
        JSONObject o = new JSONObject(); o.put("continuous_event_s", 30);
        assertEquals(30, SentrySettings.fromJson(o).toJson().getInt("continuous_event_s"));
        JSONObject bad = new JSONObject(); bad.put("continuous_event_s", "abc");
        assertEquals(600, SentrySettings.fromJson(bad).continuousEventS);   // 모르는 값은 기본
    }

    @Test
    public void continuousDetect_defaultsOffAndNeedsDetectorOnlyWhenOnOrMotionOnly() throws JSONException {
        SentrySettings d = SentrySettings.fromJson(new JSONObject());
        assertFalse(d.continuousDetect);
        assertFalse("연속 녹화 기본은 감지기를 만들지 않는다", d.needsMotionDetector());
        JSONObject on = new JSONObject(); on.put("continuous_detect", true);
        SentrySettings o = SentrySettings.fromJson(on);
        assertTrue(o.continuousDetect && o.needsMotionDetector());
        assertTrue(o.toJson().getBoolean("continuous_detect"));
        JSONObject mo = new JSONObject(); mo.put("motion_only", true);
        assertTrue("움직임 녹화는 늘 감지기가 필요", SentrySettings.fromJson(mo).needsMotionDetector());
        // 켜고 끄면 세션을 다시 만든다
        assertTrue(SentryManager.needRestart(d, o));
        assertFalse(SentryManager.needRestart(d, d));
    }
}
