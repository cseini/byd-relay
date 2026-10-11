package com.cseini.bydsniffer.sentry;

import android.content.SharedPreferences;

import com.cseini.motiondetect.Roi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 센트리 설정 — 설계 1절 표. prefs 키 = "sentry_" + JSON 필드명(snake_case). 값은 항상 범위·enum 검증을 거친 뒤에만 보관한다.
public final class SentrySettings {
    private static final String PREFIX = "sentry_";
    private static final String[] KEYS = {
            "enabled", "motion_only", "storage", "limit_mb", "resolution", "mode", "layout",
            "osd_datetime", "osd_battery", "osd_pos", "notify",
            "time_limit_enabled", "time_mode", "time_from", "time_to",
            "safe_location_enabled", "hazard_on_motion", "screen_message",
            "sensitivity", "min_detect_s", "roi_enabled", "rois", "layout_map", "layout_flip",
            "preroll_s", "post_s", "fps", "bitrate_kbps", "codec", "shadow_filter", "light_change_filter", "cell_views", "filter_person", "filter_vehicle", "object_detect", "detect_person", "detect_vehicle", "when_full", "osd_drive", "internal_limit_mb", "migrate_to_external", "summary_announce", "continuous_event_s", "continuous_detect", "pause_when_phone_near", "test_ign_off_until"};   // 옛 키 dewarp(10-07 cell_views로 대체)는 목록에 없어 받아도 무시된다

    private static final String[] STORAGES = {"internal", "sd", "usb"};
    private static final String[] RESOLUTIONS = {"hd", "fhd", "original"};
    private static final String[] MODES = {"off", "off_locked"};
    private static final String[] LAYOUTS = {"quad"};
    private static final String[] OSD_POS = {"top_left", "top_center", "top_right", "bottom_left", "bottom_center", "bottom_right"};
    private static final String[] TIME_MODES = {"deny", "allow"};
    private static final String[] WHEN_FULL = {"overwrite", "stop"};
    private static final String[] FPS_MODES = {"low", "reduced", "normal"};
    private static final String[] CODECS = {"h264", "h265"};
    private static final Pattern HM = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$");

    public final boolean enabled;
    public final boolean motionOnly;
    // 씨라7(DiLink5) 시험판은 "주차 중엔 차량 내장에 녹화 → 시동 켜고 주행할 때 선택한 USB·SD로 옮기기"(운영자 10-10): 녹화는 늘 내장에 하고 storage는 사용자가 고른 옮길 곳(sd·usb)을 그대로 둔다.
    // 내장 임시 저장 한도는 3GB로 막는다. 릴레이 시작 때 SnifferService가 켠다(이름은 처음 "내장만" 때 그대로).
    private static volatile boolean internalOnly;
    public static void setInternalOnly(boolean v) { internalOnly = v; }
    public static boolean isInternalOnly() { return internalOnly; }
    public final String storage;
    public final int limitMb;
    public final String resolution;
    public final String mode;
    public final String layout;
    public final boolean osdDatetime;
    public final boolean osdBattery;
    public final String osdPos;
    public final boolean osdDrive;   // 영상 맨 위 띠에 주행 정보(기어·속도·깜빡이·등화·브레이크·가속·벨트)를 넣는다 — 주행 녹화(drive) 전용, 센트리는 늘 끔(순정 블랙박스처럼, 운영자 10-11)
    public final boolean filterPerson;    // 움직임 감지 모드에서 "사람"이 있을 때만 이벤트·알림(기본 꺼짐, YOLO11n — 10-07 되살림)
    public final boolean filterVehicle;
    public final int continuousEventS;    // 연속 녹화(motion_only=false) 때 이벤트(녹화 파일) 하나의 길이(초) — 30~600, 30초 단위, 기본 600(운영자 10-10, 최대 10분). 움직임 녹화에는 영향 없음
    public final boolean continuousDetect; // 연속 녹화(motion_only=false) 중에도 움직임 감지·물체 판정을 표시용으로 돌림(기본 꺼짐 — CPU·발열) — 운영자 10-10. 녹화를 시작·끊는 데는 쓰지 않는다
    public final boolean summaryAnnounce; // 시동을 켤 때 릴레이 앱(헤드유닛)이 센트리 가동 요약을 음성 읽기+화면 문구로 안내(기본 켜짐) — 운영자 10-10 "음성 설정은 폰 앱 센트리 설정에, 기본 켜짐"
    public final int internalLimitMb;     // 씨라7 내장 임시 저장 상한 선택(0=기본 3GB, 그 외 1000~) — 운영자 10-11. limit_mb(외장 한도)와 별개라 이미 외장 한도를 정해 둔 사람의 내장 상한이 갑자기 늘지 않는다
    public final boolean migrateToExternal;   // 내장 임시 영상을 USB·SD로 옮기기(기본 켬). 끄면 영상은 내장에 남는다
    public final String whenFull;         // 저장 공간이 가득 차면: overwrite(가장 오래된 것부터 자동 삭제, 기본) | stop(지우지 않고 녹화 중지 + 가득 차 가면 알림) — 운영자 10-10
    public final boolean objectDetect;    // 객체 인식(YOLO) 사용 — 끄면 YOLO를 아예 안 돌리고 예전처럼 움직임만으로 이벤트·알림(운영자 10-10, 앱 센트리 설정에서 사용자가 고름)
    public final boolean detectPerson;    // 판정 결과에서 사람을 남김
    public final boolean detectVehicle;   // 판정 결과에서 차량을 남김   // "차량(자동차·오토바이·자전거·버스·트럭)"이 있을 때만. 둘 다 켜면 어느 쪽이든, 둘 다 꺼짐이면 거르지 않음
    public final JSONObject cellViews;   // 칸별 어안 보정·화면 조정(10-07) — {"1":{…},"3":{…}} 칸 1~4(행 우선 1좌상·2우상·3좌하·4우하) → 카메라 보조 조정과 같은 형식. 없는 칸은 원본 그대로, 비면 보정 없음(비용 0)
    public final boolean notify;
    public final boolean timeLimitEnabled;
    public final String timeMode;
    public final String timeFrom;
    public final String timeTo;
    public final boolean safeLocationEnabled;
    public final boolean hazardOnMotion;
    public final boolean screenMessage;
    public final int sensitivity;
    public final int minDetectS;
    public final int testIgnOffUntilS;   // ★운영자판 시험 스위치 — 이 시각(epoch 초)까지 주차(P)·정차면 시동 ON이어도 시동 OFF로 본다(릴레이 SnifferService가 읽음). 0=끔, 30분보다 먼 값은 무시
    public final boolean roiEnabled;
    public final JSONArray rois;
    public final int[] layoutMap;    // 녹화 칸 배치: 도착 칸 d(좌상·우상·좌하·우하)에 놓을 원본 칸 q, 기본 [0,1,2,3] — 링크락 실시간 보기 "배치"와 같은 규칙
    public final int[] layoutFlip;   // 원본 칸 q 카메라의 뒤집기 비트(1=가로·2=세로), 기본 [0,0,0,0] — 실시간 보기 "조정(뒤집기)"와 같은 규칙
    public final int prerollS;
    public final int postS;
    public final String fps;
    public final int bitrateKbps;
    public final String codec;
    public final boolean shadowFilter;
    public final boolean lightChangeFilter;
    public final boolean pauseWhenPhoneNear;

    // o의 값은 검증되지 않은 원본 — 없거나 잘못되면 기본값, 범위 밖이면 클램프
    private SentrySettings(JSONObject o) {
        enabled = bool(o, "enabled", false);
        motionOnly = bool(o, "motion_only", false);
        storage = pick(o, "storage", "internal", STORAGES);   // 씨라7 시험판에선 "옮길 곳" — 녹화 위치는 SentryStorage가 늘 내장으로 해석한다
        int lim = num(o, "limit_mb", 0, 0, Integer.MAX_VALUE);
        limitMb = (lim > 0 && lim < 1000) ? 1000 : lim;
        resolution = internalOnly ? "hd" : pick(o, "resolution", "original", RESOLUTIONS);   // 씨라7 시험판: 인코더 부하를 줄이려 HD 고정
        mode = pick(o, "mode", "off", MODES);
        layout = pick(o, "layout", "quad", LAYOUTS);
        osdDatetime = bool(o, "osd_datetime", true);
        osdBattery = bool(o, "osd_battery", true);
        osdPos = normalizeOsdPos(o.opt("osd_pos"));
        osdDrive = bool(o, "osd_drive", false);
        cellViews = internalOnly ? new JSONObject() : normalizeCellViews(o.opt("cell_views"));   // 씨라7 시험판: 어안 보정표는 돌핀 렌즈 기준이라 쓰지 않는다
        filterPerson = bool(o, "filter_person", false);
        filterVehicle = bool(o, "filter_vehicle", false);
        int ilim = num(o, "internal_limit_mb", 0, 0, Integer.MAX_VALUE);
        internalLimitMb = (ilim > 0 && ilim < 1000) ? 1000 : ilim;
        migrateToExternal = bool(o, "migrate_to_external", true);
        whenFull = pick(o, "when_full", "overwrite", WHEN_FULL);
        summaryAnnounce = bool(o, "summary_announce", true);
        continuousEventS = roundTo30(num(o, "continuous_event_s", 600, 30, 600));
        continuousDetect = bool(o, "continuous_detect", false);
        objectDetect = bool(o, "object_detect", true);
        detectPerson = bool(o, "detect_person", true);
        detectVehicle = bool(o, "detect_vehicle", true);
        notify = bool(o, "notify", true);
        timeLimitEnabled = bool(o, "time_limit_enabled", false);
        timeMode = pick(o, "time_mode", "deny", TIME_MODES);
        timeFrom = hm(o, "time_from", "06:00");
        timeTo = hm(o, "time_to", "18:00");
        safeLocationEnabled = bool(o, "safe_location_enabled", false);
        hazardOnMotion = bool(o, "hazard_on_motion", false);
        screenMessage = bool(o, "screen_message", false);
        sensitivity = num(o, "sensitivity", 100, 1, 200);
        minDetectS = num(o, "min_detect_s", 2, 1, 5);
        testIgnOffUntilS = num(o, "test_ign_off_until", 0, 0, Integer.MAX_VALUE);
        roiEnabled = bool(o, "roi_enabled", false);
        rois = normalizeRois(o.opt("rois"));
        layoutMap = SentryLayout.normalizeMap(o.opt("layout_map"));
        layoutFlip = SentryLayout.normalizeFlip(o.opt("layout_flip"));
        prerollS = num(o, "preroll_s", 10, 1, 20);
        postS = num(o, "post_s", 35, 1, 60);
        String fpsPick = pick(o, "fps", internalOnly ? "reduced" : "normal", FPS_MODES);
        fps = internalOnly && "normal".equals(fpsPick) ? "reduced" : fpsPick;   // 씨라7 시험판: 최대 10fps
        int br = num(o, "bitrate_kbps", 0, 0, 8000);
        if (internalOnly && br > 2000) br = 2000;   // 씨라7 시험판: 최대 2000kbps
        bitrateKbps = (br > 0 && br < 500) ? 500 : br;
        codec = pick(o, "codec", "h264", CODECS);
        shadowFilter = bool(o, "shadow_filter", true);
        lightChangeFilter = bool(o, "light_change_filter", true);
        pauseWhenPhoneNear = bool(o, "pause_when_phone_near", true);
    }

    public static SentrySettings defaults() {
        return new SentrySettings(new JSONObject());
    }

    // JSON(설계 표의 필드명)에서 검증해 만든다 — JUnit·apply 공용
    public static SentrySettings fromJson(JSONObject o) {
        return new SentrySettings(o);
    }

    public static SentrySettings load(SharedPreferences p) {
        return load(p, PREFIX, false);
    }

    // ★주행 녹화(drive, 운영자 10-11 "센트리와 비슷한 주행녹화") — 같은 설정 클래스를 접두어(drive_)와 기본값·강제값만 달리해 쓴다
    //   기본: 꺼짐·HD·10fps·연속 녹화 5분 단위·시각/배터리/주행 정보 띠 켬. 강제(저장돼 있어도 무시): 폰 근처 쉬기·움직임 녹화·움직임 감지·객체 확인·안전 위치·시간 제한·알림·가동 요약 모두 끔
    //   (주행 중에는 폰이 늘 차 근처이고, 감지·알림은 센트리 것이며, BSD가 이미 객체 인식을 돌린다)
    static JSONObject driveDefaults() {
        JSONObject d = new JSONObject();
        try {
            d.put("resolution", "hd");
            d.put("fps", "reduced");
            d.put("continuous_event_s", 300);
            d.put("osd_datetime", true);
            d.put("osd_battery", true);
            d.put("osd_drive", true);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return d;
    }

    static JSONObject driveForce(JSONObject o) {
        try {
            o.put("pause_when_phone_near", false);
            o.put("motion_only", false);
            o.put("continuous_detect", false);
            o.put("object_detect", false);
            o.put("safe_location_enabled", false);
            o.put("time_limit_enabled", false);
            o.put("notify", false);
            o.put("summary_announce", false);
            o.put("hazard_on_motion", false);
            o.put("screen_message", false);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public static SentrySettings load(SharedPreferences p, String prefix, boolean drive) {
        JSONObject o = drive ? driveDefaults() : new JSONObject();
        try {
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                String k = e.getKey();
                if (e.getValue() == null || !k.startsWith(prefix)) continue;
                String name = k.substring(prefix.length());
                if (Arrays.asList(KEYS).contains(name)) o.put(name, e.getValue());
            }
        } catch (JSONException e) {
            throw new IllegalStateException("센트리 설정 읽기 실패: " + e.getMessage(), e);
        }
        return new SentrySettings(drive ? driveForce(o) : o);
    }

    // partial에 든 알려진 키만 현재 값 위에 덮어 검증·클램프 → 그 키만 저장 → 적용된 전체 설정 JSON
    public static JSONObject apply(SharedPreferences p, JSONObject partial) {
        return apply(p, partial, PREFIX, false);
    }

    public static JSONObject apply(SharedPreferences p, JSONObject partial, String prefix, boolean drive) {
        JSONObject merged = load(p, prefix, drive).toJson();
        List<String> touched = new ArrayList<>();
        try {
            if (partial.has("cell_views")) {   // 칸 단위 병합 — {"2":{…}}는 그 칸만 바꾸고 {"2":null}은 그 칸 삭제, 통째 null은 전부 삭제
                merged.put("cell_views", mergeCellViews(normalizeCellViews(merged.opt("cell_views")), partial.opt("cell_views")));
                touched.add("cell_views");
            }
            for (String k : KEYS) {
                if (!"cell_views".equals(k) && partial.has(k) && !partial.isNull(k)) {
                    merged.put(k, partial.get(k));
                    touched.add(k);
                }
            }
            JSONObject out = new SentrySettings(drive ? driveForce(merged) : merged).toJson();
            SharedPreferences.Editor ed = p.edit();
            for (String k : touched) {
                Object v = out.get(k);
                String key = prefix + k;
                if (v instanceof Boolean) ed.putBoolean(key, (Boolean) v);
                else if (v instanceof Number) ed.putInt(key, ((Number) v).intValue());
                else ed.putString(key, v.toString());
            }
            ed.apply();
            return out;
        } catch (JSONException e) {
            throw new IllegalStateException("센트리 설정 저장 실패: " + e.getMessage(), e);
        }
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("enabled", enabled);
            o.put("motion_only", motionOnly);
            o.put("storage", storage);
            o.put("limit_mb", limitMb);
            o.put("resolution", resolution);
            o.put("mode", mode);
            o.put("layout", layout);
            o.put("cell_views", cellViews);
            o.put("filter_person", filterPerson);
            o.put("filter_vehicle", filterVehicle);
            o.put("when_full", whenFull);
            o.put("osd_drive", osdDrive);
            o.put("internal_limit_mb", internalLimitMb);
            o.put("migrate_to_external", migrateToExternal);
            o.put("summary_announce", summaryAnnounce);
            o.put("continuous_event_s", continuousEventS);
            o.put("continuous_detect", continuousDetect);
            o.put("object_detect", objectDetect);
            o.put("detect_person", detectPerson);
            o.put("detect_vehicle", detectVehicle);
            o.put("osd_datetime", osdDatetime);
            o.put("osd_battery", osdBattery);
            o.put("osd_pos", osdPos);
            o.put("notify", notify);
            o.put("time_limit_enabled", timeLimitEnabled);
            o.put("time_mode", timeMode);
            o.put("time_from", timeFrom);
            o.put("time_to", timeTo);
            o.put("safe_location_enabled", safeLocationEnabled);
            o.put("hazard_on_motion", hazardOnMotion);
            o.put("screen_message", screenMessage);
            o.put("sensitivity", sensitivity);
            o.put("min_detect_s", minDetectS);
            o.put("roi_enabled", roiEnabled);
            o.put("rois", rois);
            o.put("layout_map", SentryLayout.toJson(layoutMap));
            o.put("layout_flip", SentryLayout.toJson(layoutFlip));
            o.put("preroll_s", prerollS);
            o.put("post_s", postS);
            o.put("fps", fps);
            o.put("bitrate_kbps", bitrateKbps);
            o.put("codec", codec);
            o.put("shadow_filter", shadowFilter);
            o.put("light_change_filter", lightChangeFilter);
            o.put("test_ign_off_until", testIgnOffUntilS);
            o.put("pause_when_phone_near", pauseWhenPhoneNear);
            return o;
        } catch (JSONException e) {
            throw new IllegalStateException("센트리 설정 JSON 변환 실패: " + e.getMessage(), e);
        }
    }

    // 이 화질(hd·fhd·original)로 녹화할 때의 비트레이트 — 선택한 화질은 수동 비트레이트까지 반영한 실효값, 나머지는 현재 프레임 설정의 자동값(예상 저장 시간용)
    public int kbpsFor(String res) {
        return res.equals(resolution) ? effectiveBitrateKbps() : autoBitrateKbps(res, fps);
    }

    public int effectiveBitrateKbps() {
        return bitrateKbps > 0 ? bitrateKbps : autoBitrateKbps(resolution, fps);
    }

    // 자동 비트레이트는 보통(15fps) 기준값에 프레임 비율을 곱한다 — 프레임을 낮추면 같은 화질로 용량이 줄고, 너무 낮아 깨지지 않게 500 아래로는 안 내린다
    public static int autoBitrateKbps(String resolution, String fps) {
        int base = "hd".equals(resolution) ? 1500 : "fhd".equals(resolution) ? 3000 : 4000;
        int f = "low".equals(fps) ? 5 : "reduced".equals(fps) ? 10 : 15;
        return Math.max(500, base * f / 15);
    }

    public int targetWidth() {
        if ("hd".equals(resolution)) return 1280;
        if ("fhd".equals(resolution)) return 1920;
        return 0;
    }

    public int targetFps() {
        if ("low".equals(fps)) return 5;
        if ("reduced".equals(fps)) return 10;
        return 15;
    }

    // 감도 100(슬라이더 중앙값, 기본)의 임계 — 차 로그 5분 요약 약 80구간으로 정했다(10-09 운영자 "오탐이 안 잡힐 정도를 기본으로"):
    //   조명 번쩍임 오탐의 구간 최고값은 3.0~4.3%(전방 칸에서 몇 분마다 반복), 실제 차·사람은 4.7%(먼 차)·5.8%·10~25%.
    //   그 사이인 4.5%를 100으로 두고, 감도를 올리면(최대 200 → 2.25%) 더 민감하게, 내리면(1 → 450%=사실상 끔) 둔하게 반비례로 바꾼다.
    static final float BASE_MOTION_PCT = 0.045f;

    public float minMotionPct() {
        return Math.max(0.0001f, Math.min(3.0f, BASE_MOTION_PCT * (100f / sensitivity)));
    }

    public long sustainMs() {
        return minDetectS * 1000L;
    }

    public List<Roi> rois() {
        List<Roi> out = new ArrayList<>();
        if (roiEnabled && rois.length() > 0) {
            for (int i = 0; i < rois.length(); i++) {
                JSONObject r = rois.optJSONObject(i);
                JSONArray nx = r.optJSONArray("nx"), ny = r.optJSONArray("ny");
                float[] fx = new float[nx.length()], fy = new float[ny.length()];
                for (int j = 0; j < fx.length; j++) {
                    fx[j] = (float) nx.optDouble(j);
                    fy[j] = (float) ny.optDouble(j);
                }
                out.add(new Roi(i, fx, fy, minMotionPct(), sustainMs()));
            }
            return out;
        }
        out.add(new Roi(0, new float[]{0f, 1f, 1f, 0f}, new float[]{0f, 0f, 1f, 1f}, minMotionPct(), sustainMs()));
        return out;
    }

    // 객체 인식 실제 사용 여부 — object_detect가 꺼졌거나 사람·차량이 둘 다 꺼졌으면 YOLO를 돌리지 않는다
    public boolean objectsOn() { return objectDetect && (detectPerson || detectVehicle); }
    // 30초 단위로 맞춤(45→60, 44→30), 범위는 30~600
    static int roundTo30(int v) { return Math.max(30, Math.min(600, Math.round(v / 30f) * 30)); }

    // 움직임 감지기를 만들어야 하나 — 움직임 녹화이거나, 연속 녹화에서 표시용 감지를 켠 때
    public boolean needsMotionDetector() { return motionOnly || continuousDetect; }

    public long continuousEventMs() { return continuousEventS * 1000L; }

    public boolean whenFullStop() { return "stop".equals(whenFull); }
    public boolean personOn() { return objectsOn() && detectPerson; }
    public boolean vehicleOn() { return objectsOn() && detectVehicle; }
    // 조명 의심 판단은 "사람·차량이 새로 없다"에 기대므로 두 종류를 다 볼 때만 믿는다(한쪽만 보면 차가 지나가도 '없음'이라 조명으로 오판)
    public boolean lightJudgeOn() { return objectsOn() && detectPerson && detectVehicle; }

    public boolean hasUserRois() { return roiEnabled && rois.length() > 0; }

    // 움직임 감지에 쓰는 영역 — 감지 영역을 직접 그렸으면 그대로, 아니면 화면 전체 대신 네 칸(1좌상·2우상·3좌하·4우하)을 각각 따로 본다
    public List<Roi> detectRois() {
        if (roiEnabled && rois.length() > 0) return rois();
        List<Roi> out = new ArrayList<>();
        for (int q = 0; q < 4; q++) {
            float x0 = (q % 2) * 0.5f, y0 = (q / 2) * 0.5f;
            out.add(new Roi(q, new float[]{x0, x0 + 0.5f, x0 + 0.5f, x0}, new float[]{y0, y0, y0 + 0.5f, y0 + 0.5f}, minMotionPct(), sustainMs()));
        }
        return out;
    }

    public boolean timeAllows(long nowMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        return timeAllows(timeLimitEnabled, timeMode, timeFrom, timeTo,
                c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE));
    }

    // 순수 판정 — from==to는 빈 구간으로 본다. 자정 넘김(from>to)은 [from,24:00)∪[00:00,to)
    public static boolean timeAllows(boolean enabled, String mode, String from, String to, int minuteOfDay) {
        if (!enabled) return true;
        int f = parseHm(from), t = parseHm(to);
        boolean inside;
        if (f < 0 || t < 0 || f == t) inside = false;
        else if (f < t) inside = minuteOfDay >= f && minuteOfDay < t;
        else inside = minuteOfDay >= f || minuteOfDay < t;
        return "allow".equals(mode) ? inside : !inside;
    }

    // 0이면 free의 50%(최소 1000), 그 외 1000~free 클램프. 결과는 늘 1000 이상
    public static final int INTERNAL_DEFAULT_LIMIT_MB = 3072;   // 한도를 정하지 않은 내장 저장소 기본 상한 — 내장이 꽉 차면 차량 시스템에 부담(공개 전 보강, 10-06 운영자 승인)

    // ★내장 하한(운영자 10-11, 차량 시스템 보호) — 내장 저장소는 전체의 5%(최소 4GB)를 센트리가 건드리지 못하게 비워 둔다
    public static final long INTERNAL_FLOOR_MIN_MB = 4096L;
    public static long internalFloorMb(long totalMb) { return Math.max(INTERNAL_FLOOR_MIN_MB, Math.max(0L, totalMb) * 5L / 100L); }

    // 루트 종류별 최종 한도(MB). freeUsedMb=센트리가 쓸 수 있는 총량(남은 공간+이미 쓴 양), totalMb=그 저장소 전체 크기.
    //   외장: 예전 그대로. 내장: 씨라7 임시 저장은 internal_limit_mb(0이면 3GB), 그 밖의 내장은 limit_mb를 쓰고(0이면 남은 공간의 절반, 3GB 이내), 둘 다 하한을 지킨 뒤의 총량(freeUsed-하한)을 넘지 못한다.
    public static int limitMbFor(int limitMb, int internalLimitMb, long freeUsedMb, boolean internalRoot, long totalMb) {
        if (!internalRoot) return clampLimitMb(limitMb, freeUsedMb, false);
        int wanted = internalOnly ? internalLimitMb : limitMb;
        long v;
        if (wanted <= 0) v = Math.max(1000L, Math.min(freeUsedMb / 2, INTERNAL_DEFAULT_LIMIT_MB));
        else v = Math.max(1000L, Math.min((long) wanted, freeUsedMb));
        v = Math.min(v, Math.max(freeUsedMb - internalFloorMb(totalMb), 1L));   // 하한을 못 지키는 처지여도 한도는 1MB 이상(0은 "한도 없음"으로 읽히는 곳이 있다) — 녹화 중지는 SentryManager가 floorExhausted로 막는다
        return (int) Math.min(v, Integer.MAX_VALUE);
    }

    // 하한 때문에 한도가 줄었는가(상태 표시용) — 하한이 없었다면 잡혔을 한도보다 실제 한도가 작으면 true
    public static boolean floorHit(int limitMb, int internalLimitMb, long freeUsedMb, long totalMb) {
        int wanted = internalOnly ? internalLimitMb : limitMb;
        long v;
        if (wanted <= 0) v = Math.max(1000L, Math.min(freeUsedMb / 2, INTERNAL_DEFAULT_LIMIT_MB));
        else v = Math.max(1000L, Math.min((long) wanted, freeUsedMb));
        return Math.max(freeUsedMb - internalFloorMb(totalMb), 0L) < v;
    }

    public static int clampLimitMb(int wanted, long freeMb) {
        return clampLimitMb(wanted, freeMb, false);
    }

    // internal=true(내장 저장소)이고 한도를 안 정했으면(0) 남은 용량의 절반이되 3GB를 넘기지 않는다. 사용자가 직접 정한 한도는 그대로 존중
    public static int clampLimitMb(int wanted, long freeMb, boolean internal) {
        long v;
        if (wanted <= 0) v = Math.max(1000L, internal ? Math.min(freeMb / 2, INTERNAL_DEFAULT_LIMIT_MB) : freeMb / 2);
        else v = Math.max(1000L, Math.min((long) wanted, freeMb));
        if (internal && internalOnly) v = Math.min(v, INTERNAL_DEFAULT_LIMIT_MB);   // 씨라7 임시 저장 — 외장 한도를 크게 정했어도 차량 내장은 3GB까지만
        return (int) Math.min(v, Integer.MAX_VALUE);
    }

    // "H:mm"/"HH:mm" → 분(0~1439), 형식 틀리면 -1
    static int parseHm(String s) {
        if (s == null) return -1;
        Matcher m = HM.matcher(s.trim());
        if (!m.matches()) return -1;
        return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
    }

    private static String hm(JSONObject o, String k, String def) {
        Object v = o.opt(k);
        int m = v instanceof String ? parseHm((String) v) : -1;
        if (m < 0) return def;
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    private static boolean bool(JSONObject o, String k, boolean def) {
        Object v = o.opt(k);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(Locale.US);
            if (s.equals("true") || s.equals("1")) return true;
            if (s.equals("false") || s.equals("0")) return false;
        }
        return def;
    }

    private static int num(JSONObject o, String k, int def, int min, int max) {
        Object v = o.opt(k);
        long n;
        if (v instanceof Number) {
            n = Math.round(((Number) v).doubleValue());
        } else if (v instanceof String) {
            try {
                n = Math.round(Double.parseDouble(((String) v).trim()));
            } catch (NumberFormatException e) {
                return def;
            }
        } else {
            return def;
        }
        return (int) Math.max(min, Math.min(max, n));
    }

    // 옛 값 top·bottom은 그때 모양(왼쪽 정렬)을 그대로 이어 top_left·bottom_left로, 옛 center(세로 가운데)는 지원 중단이라 기본으로, 그 외 잘못된 값도 기본(top_left)
    static String normalizeOsdPos(Object v) {
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(Locale.US);
            for (String a : OSD_POS) if (a.equals(s)) return a;
            if ("top".equals(s)) return "top_left";
            if ("bottom".equals(s)) return "bottom_left";
        }
        return "top_left";
    }

    // 칸별 조정 값 정규화 — 키는 "1"~"4"만, 값은 JSON 객체만 인정(prefs에는 문자열로 저장돼 오므로 문자열도 읽는다). 키는 번호 순으로 정렬해 같은 내용이면 같은 문자열이 되게 한다
    static JSONObject normalizeCellViews(Object v) {
        JSONObject src = null;
        if (v instanceof JSONObject) src = (JSONObject) v;
        else if (v instanceof String) {
            try { src = new JSONObject((String) v); } catch (JSONException e) { src = null; }
        }
        JSONObject out = new JSONObject();
        if (src == null) return out;
        try {
            for (int c = 1; c <= 4; c++) {
                JSONObject cell = src.optJSONObject(String.valueOf(c));
                if (cell != null) out.put(String.valueOf(c), new JSONObject(cell.toString()));
            }
        } catch (JSONException e) {
            throw new IllegalStateException("칸별 조정 값 정리 실패: " + e.getMessage(), e);
        }
        return out;
    }

    // patch가 객체면 칸 단위 병합(값 null=그 칸 삭제), JSON null이면 전부 삭제, 그 밖(잘못된 값)은 현재 값 유지
    static JSONObject mergeCellViews(JSONObject current, Object patch) {
        try {
            if (patch == JSONObject.NULL || patch == null) return new JSONObject();
            JSONObject p = patch instanceof JSONObject ? (JSONObject) patch : null;
            if (p == null && patch instanceof String) {
                try { p = new JSONObject((String) patch); } catch (JSONException e) { p = null; }
            }
            if (p == null) return current;
            JSONObject out = new JSONObject(current.toString());
            for (int c = 1; c <= 4; c++) {
                String k = String.valueOf(c);
                if (!p.has(k)) continue;
                JSONObject cell = p.isNull(k) ? null : p.optJSONObject(k);
                if (p.isNull(k)) out.remove(k);
                else if (cell != null) out.put(k, new JSONObject(cell.toString()));
            }
            return normalizeCellViews(out);
        } catch (JSONException e) {
            throw new IllegalStateException("칸별 조정 값 병합 실패: " + e.getMessage(), e);
        }
    }

    private static String pick(JSONObject o, String k, String def, String[] allowed) {
        Object v = o.opt(k);
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(Locale.US);
            for (String a : allowed) if (a.equals(s)) return a;
        }
        return def;
    }

    // [{nx:[…],ny:[…]}] — 길이 같고 정점 3개 이상인 것만 남기고 좌표는 0~1로 클램프. JSON 문자열(prefs 저장형)도 받는다
    private static JSONArray normalizeRois(Object v) {
        JSONArray src = null;
        if (v instanceof JSONArray) {
            src = (JSONArray) v;
        } else if (v instanceof String) {
            try {
                src = new JSONArray((String) v);
            } catch (JSONException e) {
                src = null;
            }
        }
        JSONArray out = new JSONArray();
        if (src == null) return out;
        try {
            for (int i = 0; i < src.length(); i++) {
                JSONObject r = src.optJSONObject(i);
                if (r == null) continue;
                JSONArray nx = r.optJSONArray("nx"), ny = r.optJSONArray("ny");
                if (nx == null || ny == null || nx.length() != ny.length() || nx.length() < 3) continue;
                JSONArray cx = new JSONArray(), cy = new JSONArray();
                boolean ok = true;
                for (int j = 0; j < nx.length(); j++) {
                    double x = nx.optDouble(j, Double.NaN), y = ny.optDouble(j, Double.NaN);
                    if (Double.isNaN(x) || Double.isNaN(y)) { ok = false; break; }
                    cx.put(Math.max(0.0, Math.min(1.0, x)));
                    cy.put(Math.max(0.0, Math.min(1.0, y)));
                }
                if (!ok) continue;
                JSONObject c = new JSONObject();
                c.put("nx", cx);
                c.put("ny", cy);
                out.put(c);
            }
        } catch (JSONException e) {
            throw new IllegalStateException("ROI 정리 실패: " + e.getMessage(), e);
        }
        return out;
    }
}
