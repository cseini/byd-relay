package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** 주행 녹화(drive) — 센트리와 코드를 나누는 부분: 게이트 방향, 설정 접두어·기본값·강제값, 주행 정보 띠. */
public class SentryDriveTest {
    private static final int W = 1280, H = 960;

    // 게이트: enabled, ignition, camOff, rtc, detect, timeAllowed, pausedByLocation, lowVoltage, pauseWhenPhoneNear, phoneNearFresh, storageYield
    private static String g(boolean drive, boolean enabled, int ign) {
        return SentryManager.gate(drive, enabled, ign, false, false, false, true, false, false, false, false, null);
    }

    @Test
    public void gate_driveNeedsIgnitionOn_sentryNeedsIgnitionOff() {
        assertNull(g(true, true, 1));
        assertEquals("시동 꺼짐", g(true, true, 0));
        assertEquals("시동 상태 미확정", g(true, true, -1));
        assertEquals("주행 녹화 꺼짐", g(true, false, 1));
        assertNull(g(false, true, 0));
        assertEquals("시동 켜짐", g(false, true, 1));
        assertEquals("센트리 꺼짐", g(false, false, 0));
    }

    @Test
    public void gate_driveYieldsToOtherCameraUsers() {
        assertEquals("실시간 보기 중", SentryManager.gate(true, true, 1, false, true, false, true, false, false, false, false, null));
        assertEquals("다른 감지 사용 중", SentryManager.gate(true, true, 1, false, false, true, true, false, false, false, false, null));
        assertEquals("카메라 기능 꺼짐(앱 설정)", SentryManager.gate(true, true, 1, true, false, false, true, false, false, false, false, null));
    }

    @Test
    public void gate_oldOverloadStaysSentry() {
        assertEquals("시동 켜짐", SentryManager.gate(true, 1, false, false, true, false, false, false, false, null));
        assertNull(SentryManager.gate(true, 0, false, false, true, false, false, false, false, null));
    }

    @Test
    public void driveOffStatus_isTinyValidJsonTheAppCanKeyOn() throws JSONException {
        JSONObject o = new JSONObject(SentryManager.DRIVE_OFF_STATUS);
        assertEquals("drive", o.getString("kind"));
        assertEquals("IDLE", o.getString("state"));
        assertEquals("주행 녹화 꺼짐", o.getString("why"));
        assertTrue(SentryManager.DRIVE_OFF_STATUS.length() < 100);
    }

    @Test
    public void cachedStatus_ofDriveManagerAlwaysSaysDrive_evenBeforeAnyStatusWasBuilt() throws JSONException {
        JSONObject d = SentryManager.drive().cachedStatusJson();
        assertEquals("drive", d.getString("kind"));
        assertEquals("주행 녹화 꺼짐", d.getString("why"));
        assertEquals("sentry", SentryManager.get().cachedStatusJson().getString("kind"));
    }

    @Test
    public void managers_areSeparateInstances() {
        assertFalse(SentryManager.get().isDrive());
        assertTrue(SentryManager.drive().isDrive());
        assertSame(SentryManager.get(), SentryManager.of(null));
        assertSame(SentryManager.drive(), SentryManager.of("drive"));
        assertSame(SentryManager.get(), SentryManager.of("sentry"));
    }

    @Test
    public void driveSettings_defaultsAreOffHdTenFpsWithDriveBand() throws JSONException {
        SentrySettings d = SentrySettings.fromJson(SentrySettings.driveForce(SentrySettings.driveDefaults()));
        assertFalse("기본 꺼짐(켜기 전엔 아무 동작 없음)", d.enabled);
        assertEquals("hd", d.resolution);
        assertEquals("reduced", d.fps);   // 10fps
        assertEquals(300, d.continuousEventS);
        assertTrue(d.osdDatetime);
        assertTrue(d.osdBattery);
        assertTrue("주행 정보 띠 기본 켬", d.osdDrive);
        assertFalse("센트리 기본은 주행 정보 띠 끔", SentrySettings.defaults().osdDrive);
    }

    @Test
    public void driveSettings_forceOffSentryOnlyBehaviors_evenIfStored() throws JSONException {
        JSONObject o = SentrySettings.driveDefaults();
        o.put("enabled", true);
        o.put("pause_when_phone_near", true);
        o.put("motion_only", true);
        o.put("continuous_detect", true);
        o.put("object_detect", true);
        o.put("safe_location_enabled", true);
        o.put("time_limit_enabled", true);
        o.put("notify", true);
        o.put("summary_announce", true);
        SentrySettings s = SentrySettings.fromJson(SentrySettings.driveForce(o));
        assertTrue(s.enabled);
        assertFalse("주행 중 폰은 늘 차 근처라 쉬면 영영 못 녹화한다", s.pauseWhenPhoneNear);
        assertFalse(s.motionOnly);
        assertFalse(s.continuousDetect);
        assertFalse(s.needsMotionDetector());
        assertFalse(s.objectDetect);
        assertFalse(s.safeLocationEnabled);
        assertFalse(s.notify);
        assertFalse(s.summaryAnnounce);
    }

    /** 메모리 SharedPreferences — load/apply의 접두어 분리 시험용 */
    private static final class MemPrefs implements SharedPreferences {
        final Map<String, Object> m = new HashMap<>();
        @Override public Map<String, ?> getAll() { return new HashMap<>(m); }
        @Override public String getString(String k, String d) { Object v = m.get(k); return v instanceof String ? (String) v : d; }
        @Override public Set<String> getStringSet(String k, Set<String> d) { return d; }
        @Override public int getInt(String k, int d) { Object v = m.get(k); return v instanceof Integer ? (Integer) v : d; }
        @Override public long getLong(String k, long d) { Object v = m.get(k); return v instanceof Long ? (Long) v : d; }
        @Override public float getFloat(String k, float d) { return d; }
        @Override public boolean getBoolean(String k, boolean d) { Object v = m.get(k); return v instanceof Boolean ? (Boolean) v : d; }
        @Override public boolean contains(String k) { return m.containsKey(k); }
        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> pending = new HashMap<>();
                @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { return this; }
                @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { return this; }
                @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
                @Override public Editor remove(String k) { pending.put(k, null); return this; }
                @Override public Editor clear() { return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() { for (Map.Entry<String, Object> e : pending.entrySet()) { if (e.getValue() == null) m.remove(e.getKey()); else m.put(e.getKey(), e.getValue()); } }
            };
        }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    }

    @Test
    public void prefs_driveAndSentrySettingsDoNotMix() throws JSONException {
        MemPrefs p = new MemPrefs();
        JSONObject part = new JSONObject();
        part.put("enabled", true);
        part.put("fps", "low");
        part.put("pause_when_phone_near", true);
        JSONObject applied = SentrySettings.apply(p, part, "drive_", true);
        assertTrue(applied.getBoolean("enabled"));
        assertEquals("low", applied.getString("fps"));
        assertFalse("강제값은 저장해도 적용 결과에서 꺼진다", applied.getBoolean("pause_when_phone_near"));
        for (String k : p.m.keySet()) assertTrue("drive_ 접두어만 써야 함: " + k, k.startsWith("drive_"));
        assertTrue(SentrySettings.load(p, "drive_", true).enabled);
        assertFalse("센트리 설정은 그대로(꺼짐)", SentrySettings.load(p).enabled);
        SentrySettings.apply(p, new JSONObject().put("enabled", true), "sentry_", false);
        assertTrue(SentrySettings.load(p).enabled);
        assertEquals("low", SentrySettings.load(p, "drive_", true).fps);
    }

    @Test
    public void uiKindSwitch_keepsGrantAndFlipsKind() {
        String onSentry = SentryUiPage.kindSwitch("&sgx=a&sg=b", "2026-10-11");
        assertTrue(onSentry.contains("href='/sentry/ui?date=2026-10-11&sgx=a&sg=b'>센트리"));
        assertTrue(onSentry.contains("href='/sentry/ui?kind=drive&date=2026-10-11&sgx=a&sg=b'>주행"));
        String onDrive = SentryUiPage.kindSwitch("&sgx=a&sg=b&kind=drive", "2026-10-11");
        assertTrue("센트리로 돌아가는 링크에는 kind가 없다", onDrive.contains("href='/sentry/ui?date=2026-10-11&sgx=a&sg=b'>센트리"));
        assertTrue(onDrive.contains("href='/sentry/ui?kind=drive&date=2026-10-11&sgx=a&sg=b'>주행"));
        assertTrue(SentryUiPage.kindSwitch("", "2026-10-11").contains("href='/sentry/ui?kind=drive&date=2026-10-11'>주행"));
    }

    @Test
    public void driveInfo_toText_listsOnlyWhatIsKnownAndOn() {
        DriveInfo d = new DriveInfo();
        d.gear = 'D'; d.speedKmh = 58; d.turn = 1; d.brake = 1; d.accel = 3; d.low = 1; d.pos = 1; d.high = 0; d.belt = 0;
        assertEquals("D  58KM/H  <L  BRK  ACC 30%  POS LOW  BELT!", d.toText());
        DriveInfo p = new DriveInfo();
        p.gear = 'P'; p.speedKmh = 0;
        assertEquals("P  0KM/H", p.toText());
        DriveInfo u = new DriveInfo();
        assertEquals("-  --KM/H", u.toText());
        u.turn = 3;
        assertTrue(u.toText().contains("<HAZ>"));
    }

    @Test
    public void icons_fromArgb_computesVideoColorAndKeepsTransparencyOut() {
        int[] argb = new int[4 * 4];
        java.util.Arrays.fill(argb, 0xFFFF0000);          // 불투명 빨강
        argb[0] = 0x00000000;                              // 투명 한 점
        DriveIcons.Sprite s = DriveIcons.fromArgb(argb, 4, 4);
        assertEquals(0, s.a[0] & 0xFF);
        assertEquals(255, s.a[1] & 0xFF);
        assertEquals("빨강 휘도(BT.601 제한범위)", 82, s.y[1] & 0xFF, 2);
        assertTrue("빨강은 V가 높고 U가 낮다", (s.v[3] & 0xFF) > 200 && (s.u[3] & 0xFF) < 100);
        int[] clear = new int[16];
        DriveIcons.Sprite c = DriveIcons.fromArgb(clear, 4, 4);
        assertEquals("완전 투명 칸은 색이 중립", 128, c.u[0] & 0xFF);
        assertEquals(0, c.ca[0] & 0xFF);
    }

    private static DriveIcons.Sprite solid(int argb, int px) {
        int[] a = new int[px * px];
        java.util.Arrays.fill(a, argb);
        return DriveIcons.fromArgb(a, px, px);
    }

    @Test
    public void osd_blit_blendsByAlpha_clipsAtTheEdges() {
        byte[] f = gray(100);
        DriveIcons.Sprite white = solid(0xFFFFFFFF, 8);
        SentryOsd.blit(f, W, H, 10, 10, white);
        assertEquals("불투명 흰 그림은 휘도가 235", 235, f[12 * W + 12] & 0xFF);
        assertEquals("그림 밖은 그대로", 100, f[12 * W + 30] & 0xFF);
        byte[] g = gray(100);
        SentryOsd.blit(g, W, H, 10, 10, solid(0x80FFFFFF, 8));   // 절반 투명
        int mid = g[12 * W + 12] & 0xFF;
        assertTrue("절반 섞임: " + mid, mid > 150 && mid < 200);
        byte[] h = gray(100);
        SentryOsd.blit(h, W, H, W - 3, H - 3, white);            // 모서리 밖으로 나가도 예외 없이 잘림
        SentryOsd.blit(h, W, H, -5, -5, white);
        assertEquals(235, h[(H - 2) * W + (W - 2)] & 0xFF);
    }

    @Test
    public void osd_iconBand_drawsOnlyKnownItemsAndLightsTheCurrentGear() {
        java.util.Map<String, DriveIcons.Sprite> m = new java.util.HashMap<>();
        for (String n : DriveIcons.NAMES) m.put(n, solid(n.endsWith("_enable") ? 0xFFFFFFFF : 0xFF404040, 24));
        DriveIcons ic = new DriveIcons(m);
        int barH = Math.round(H * 0.0375f) & ~1;
        DriveInfo d = new DriveInfo();
        d.gear = 'D'; d.speedKmh = 40; d.turn = 2;
        byte[] a = gray(100);
        new SentryOsd(false, false, "top_left", () -> -1, null, () -> d).drawIconBand(a, W, H, 0, barH, 2, d, ic);
        int whiteA = count(a, 0, barH, 230);
        assertTrue("켜진 D·오른쪽 깜빡이·속도 글씨가 흰색으로", whiteA > 300);
        d.brake = 1; d.accel = 5; d.low = 1; d.high = 1; d.pos = 1; d.fogRear = 1; d.belt = 0;
        byte[] b = gray(100);
        new SentryOsd(false, false, "top_left", () -> -1, null, () -> d).drawIconBand(b, W, H, 0, barH, 2, d, ic);
        assertTrue("읽힌 항목이 늘면 켜진 그림도 늘어난다", count(b, 0, barH, 230) > whiteA + 1000);
    }

    private static byte[] gray(int v) {
        byte[] f = new byte[W * H * 3 / 2];
        java.util.Arrays.fill(f, 0, W * H, (byte) v);
        java.util.Arrays.fill(f, W * H, f.length, (byte) 128);
        return f;
    }

    private static int count(byte[] f, int y0, int y1, int min) {
        int n = 0;
        for (int y = y0; y < y1; y++) for (int x = 0; x < W; x++) if ((f[y * W + x] & 0xFF) >= min) n++;
        return n;
    }

    @Test
    public void osd_infoBand_isSecondBandBelowTheFirstAndLeavesTheRestAlone() {
        byte[] f = gray(200);
        new SentryOsd(true, true, "top_left", () -> 74, () -> "D  45KM/H  <L  POS LOW ACC 30%").apply(f, W, H, 1_791_190_345_000L);
        int barH = Math.round(H * 0.0375f) & ~1;
        assertTrue("첫 띠에 시각", count(f, 0, barH, 230) > 200);
        assertTrue("둘째 띠에 주행 정보 글씨", count(f, barH, 2 * barH, 230) > 200);
        assertEquals("두 띠 밖은 그대로", 200, f[(2 * barH + 5) * W + 100] & 0xFF);
        assertEquals("둘째 띠 안 글씨 없는 곳은 어두워짐", (200 * 100) >> 8, f[(2 * barH - 1) * W + (W - 5)] & 0xFF);
    }

    @Test
    public void osd_noInfoSupplier_meansNoSecondBand() {
        byte[] f = gray(200);
        new SentryOsd(true, true, "top_left", () -> 74).apply(f, W, H, 1_791_190_345_000L);
        int barH = Math.round(H * 0.0375f) & ~1;
        assertEquals("센트리는 둘째 띠가 없다", 200, f[(barH + 5) * W + 100] & 0xFF);
    }

    @Test
    public void osd_infoBand_bottomPosGoesAboveTheBottomBar() {
        byte[] f = gray(200);
        new SentryOsd(true, true, "bottom_left", () -> 74, () -> "P  0KM/H").apply(f, W, H, 0L);
        int barH = Math.round(H * 0.0375f) & ~1;
        int bottomBar = (H - barH) & ~1;
        assertTrue(count(f, bottomBar, H, 230) > 100);
        assertTrue("아래쪽 띠면 정보 띠는 그 바로 위", count(f, bottomBar - barH, bottomBar, 230) > 100);
    }

    @Test
    public void osd_infoBand_everyLetterWeUseHasAGlyph() {
        // 주행 정보에 쓰는 글자(P R N D K M H / L < > A C B O F G I W Z)가 비어 있지 않게 그려지는지 — 글리프 누락이면 띠가 비어 보인다
        String letters = "PRNDKMH/L<>ACBOFGIWZXYTSUVEJQ!.+0123456789%";
        for (int i = 0; i < letters.length(); i++) {
            final char c = letters.charAt(i);
            byte[] f = gray(0);
            new SentryOsd(false, false, "top_left", () -> -1, () -> "" + c).apply(f, W, H, 0L);
            int barH = Math.round(H * 0.0375f) & ~1;
            assertTrue("글자 '" + c + "'가 안 그려짐", count(f, 0, 2 * barH, 230) > 0);
        }
    }
}
