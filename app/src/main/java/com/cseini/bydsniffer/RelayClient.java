package com.cseini.bydsniffer;

import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONArray;
import org.json.JSONObject;

// 헤드유닛 릴레이 클라이언트 — 폰(오토도어 앱)과 Worker를 통해 명령/상태를 주고받는다.
//  · 상태 push: SnifferService가 수집한 차량값(락/안전벨트/배터리…)을 주기적으로 /relay/state 로 전송
//  · 명령 수신·실행: 응답의 commands 를 로컬 SDK(setInt)로 실행 후 /relay/result 로 결과 보고
//  · 인증: sig = HMAC-SHA256(SECRET, uid+":"+vin) — 폰과 동일 계정이면 동일 sig(같은 방)
//
// ★ 실차 확정 필요:
//   (1) uid/vin — 헤드유닛의 BYD 계정 로그인으로 확보(포grammera Java 로그인 이식). 임시로 setCredentials()로 주입.
//   (2) 상태 feature ID — updateFromEvent()에서 락/안전벨트/도어 featureId 매핑(스니퍼 로그로 확인).
//   (3) 제어 feature ID — control()의 setInt(featureId, ...) 값(RemoteControl.proto 532 지령 참고 + 실차).
public class RelayClient {
    private static final String TAG = "RelayClient";
    private static final String BASE = "https://byd.cseini.co.kr";
    private static final String SECRET = BuildConfig.RELAY_SECRET; // 폰 RelayClient와 동일
    // ★공유판 보안 — 차량별 페어키 K(헤드유닛 화면코드로 앱과 1회 공유, SnifferService가 SharedPreferences에서 로드해 주입).
    //   워커는 K를 모르는 파이프. 명령은 앱이 K로 서명(action|praw|nonce|ts) → 릴레이가 여기서 검증(재전송 방어 포함) 후 실행.
    //   operator flavor는 검증 안 함(전역 SECRET 경로 유지, 전환기 자물쇠 방지).
    private volatile String pairKey = null;   // null=미페어링(공유판에선 모든 명령 거부)
    public void setPairKey(String k) {
        this.pairKey = (k == null || k.isEmpty()) ? null : k;
        // ★631 FB1126 — 키 지문(SHA256(K) 앞 8자) 공개. 폰이 자기 K 지문과 비교해 "키 어긋남 → 다시 동기화"를 스스로 알리고,
        //   워커는 지문이 다른 K 업로드를 거부한다. 지문은 단방향이라 K를 드러내지 않는다.
        putStatus("pk_fp", pairKey == null ? "" : keyFingerprint(pairKey));
    }
    public static String keyFingerprint(String k) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(k.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
    // ★631 FB1126 — 검증 실패 사유. 예전엔 참/거짓만 남아 키 불일치·시계·재사용이 모두 같은 "서명 검증 실패"라 원인 분석이 불가능했다.
    private volatile String lastVerifyFail = "";
    private final java.util.Set<String> usedNonces = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private volatile String uid = "";
    private volatile String vin = "";
    private volatile String devId = "";   // ★부트스트랩 신원 — persist.sys.cloud.last_vin(헤드유닛 고유). vin 틀려도 이 채널로 SETVIN 수신.
    public void setDevId(String d) { this.devId = (d == null) ? "" : d.trim(); }
    public String getDevId() { return devId; }
    private volatile boolean running = false;
    private final Map<String, Object> state = new ConcurrentHashMap<>();
    public void putState(String k, Object v) { stateSet(k, v); }   // ★모션감지(camDetect) 결과 업로드용
    public Object getStatus(String k) { return state.get(k); }   // ★v377 SnifferService 킥 트리거가 door_trunk(1=열림)·trunk_change_ts 읽어 여닫음 쿨다운
    // ★웹 클러스터 2단계(2026-09-17) — 상태 변경 즉시 통지(RemoteWebServer SSE 푸시). 폴이 600ms마다 같은 값을 넣으므로 **실제로 바뀔 때만**.
    //   대상은 상태 전체(운영자 지시) — 단 `_` 접두(진단)와 문자열 길이 200 초과(remote_manifest/apps/split·adv_stored 같은 JSON 덩어리) 제외.
    //   리스너는 절대 블로킹하지 않을 것(RemoteWebServer.pushState는 큐잉 후 즉시 반환) — 폴/이벤트 스레드에서 호출됨.
    public interface StateListener { void onChange(String key, Object value); }
    private final java.util.concurrent.CopyOnWriteArrayList<StateListener> stateListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    public void addStateListener(StateListener l) { if (l != null) stateListeners.addIfAbsent(l); }
    public void removeStateListener(StateListener l) { stateListeners.remove(l); }
    static boolean clusterKeyOk(String k, Object v) { return k != null && v != null && !k.startsWith("_") && String.valueOf(v).length() <= 200; }
    private void stateSet(String k, Object v) {
        Object prev = state.put(k, v);
        if (lpConn != null && !v.equals(prev) && ("drive_state".equals(k) || "power_level".equals(k) || "gear".equals(k))) abortLongPoll();   // ★롱폴 중 주행·시동이 바뀌면 바로 새 상태를 올리게
        if (stateListeners.isEmpty() || v.equals(prev) || !clusterKeyOk(k, v)) return;
        for (StateListener l : stateListeners) { try { l.onChange(k, v); } catch (Throwable ignored) {} }
    }
    /** SSE 접속 시 1회 스냅샷 — 상태 전체(위 규칙). raw 차량이벤트 맵은 SnifferService가 "raw" 키로 합친다(워커 업로드엔 안 실림). */
    public JSONObject clusterSnapshot() {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Object> e : state.entrySet()) { if (clusterKeyOk(e.getKey(), e.getValue())) { try { o.put(e.getKey(), e.getValue()); } catch (Exception ignored) {} } }
        return o;
    }
    // ★클러스터 접속 중엔 상태 폴 600ms 고정(1단계의 15초 주기 RAW fastPoll POST 해킹 대체). SSE 접속 수를 RemoteWebServer.Host가 넣어준다.
    private volatile int clusterClients = 0;
    public void setClusterClients(int n) { clusterClients = n; }
    private final ControlBridge control;   // 로컬 SDK 제어 다리 (setInt 등)

    public interface ControlBridge {
        // action(LOCK/UNLOCK/AC_ON/AC_OFF/WINDOW_CLOSE …)을 로컬 SDK로 실행. 성공/에러 반환.
        String execute(String action, JSONObject params); // null=성공, 그 외=에러메시지
    }

    public RelayClient(ControlBridge control) { this.control = control; }

    // ★v149 시동 시(AC_POWER_STATE 0→1) 자동적용 프리셋 — 앱이 RAW {startPreset:{on,cmds:[{device,method,args},...]}}로 동기.
    //   릴레이가 차량 부트온을 직접 보고 로컬 SDK로 replay → 물리 시동/클라우드 무관하게 즉시 적용(앱 클라우드감지 지연·누락 해소).
    private volatile boolean startPresetOn = false;
    private volatile String startPresetCmds = null;   // JSONArray 문자열
    private volatile String startPresetAdv = null;    // ★v403 고급 규칙 {temp,seat,shade} JSON — 부트온에 외기온도·시계로 평가
    public void setStartPreset(boolean on, String cmdsJson) { startPresetOn = on; startPresetCmds = cmdsJson; }
    public void setStartPresetAdv(String advJson) { startPresetAdv = (advJson == null || advJson.isEmpty() || "{}".equals(advJson)) ? null : advJson; }
    private volatile String presetDbg = "(미실행)";   // ★v199 applyStartPreset 마지막 진단
    private volatile boolean presetDone = false;   // ★v199 이번 차-켜짐 세션에 프리셋 적용했나(중복방지+콜드부팅 만회)
    private android.content.SharedPreferences presetPrefs;   // ★재시작 후 presetDone 복원 — 주행 중 릴레이 재설치 시 콜드부팅만회가 프리셋(선쉐이드) 재적용하던 것 방지.
    public void setPresetPrefs(android.content.SharedPreferences p) { presetPrefs = p; presetDone = p.getBoolean("preset_done", false);
        coolAccumMs = p.getLong("cool_accum_ms", 0); coolStartMs = p.getLong("cool_start_ms", 0); }   // ★재시작 후 냉방누적 복원 — 시동off 릴레이 재시작에 애프터블로우 조건이 0으로 리셋되던 버그 수정
    private void savePresetDone(boolean v) { if (presetPrefs != null) presetPrefs.edit().putBoolean("preset_done", v).apply(); }
    private void saveCoolTimer() { if (presetPrefs != null) presetPrefs.edit().putLong("cool_accum_ms", coolAccumMs).putLong("cool_start_ms", coolStartMs).apply(); }
    // ★v282 외기온(out_temp) persist — 콜드부팅 시 AC_TEMP_OUT이 늦게 와서 시트규칙 평가를 못 하던 것 만회. 직전 세션값을 즉시 폴백.
    private void saveOutTemp(int t) { if (presetPrefs != null) presetPrefs.edit().putInt("last_out_temp", t).apply(); }
    private int loadOutTemp() { return presetPrefs != null ? presetPrefs.getInt("last_out_temp", Integer.MIN_VALUE) : Integer.MIN_VALUE; }
    private volatile boolean presetRunning = false;
    // ★afterblow 중 시동ON(OPENAIR)엔 부트온 프리셋(선쉐이드 열기 등) 억제 — 송풍건조는 미러접힘·선쉐이드닫힘 유지가 목적.
    //   SnifferService.afterBlowSeq가 afterblowUntil을 여기로 push. 이 창 안엔 applyStartPreset 스킵.
    private volatile long afterblowUntilMs = 0;
    public void setAfterblowUntil(long ts) { afterblowUntilMs = ts; }
    // ═══ 시동 판정(2026-09-11 재설계) ═══════════════════════════════════════════════════════════
    // 옛 구조: AC_POWER_STATE 이벤트 하나가 '시동'과 '공조 전원' 겸용 → 리모컨으로 공조만 꺼도 시동 꺼짐으로 오판
    //          (무탑승 마무리 오발화, 하루 12회·새벽 6회). 진짜 시동값은 bodywork.getPowerLevel()(2=ON/0=OFF, 실차 확정).
    // 새 구조: IgnitionTracker(순수 Java, JUnit 19케이스)가 단일 판정기.
    //   · AC_POWER_STATE 이벤트 = 트리거. 수신 즉시 getPowerLevel을 동기로 읽어 판별(2면 공조만 꺼진 것 → 시동 유지).
    //   · 폴링(P기어 2s) = 양방향 백스톱. D3/D5 공통(옛 D5 전용 sawPwr2/zeroStreak 로직은 판정기 안으로).
    //   · 시동상태 영속(prefs) — 시동 OFF 순간 릴레이가 죽어 OFF 엣지를 못 보는 D3 특성을 부팅 시 대조로 만회.
    //   `state["ac_power"]`는 계속 **시동**을 뜻한다(미러복원 게이트·brake_start 게이트·iOS _relayAcOn이 그 의미로 읽음).
    //   공조 전원 타일은 별도 게터 ac_start_st(getAcStartState, ACSNAP 확정)를 본다.
    private volatile IgnitionTracker ignTracker;
    private volatile long acPowerOnTs = 0;
    public interface PowerLevelReader { int read(); }          // bodywork.getPowerLevel() — 실패 시 IgnitionTracker.NA
    public volatile Runnable onIgnitionOn;
    public volatile Runnable onIgnitionOff;                    // ★647 시동 OFF 엣지 훅(기록 업로드) — 설정 안 하면 아무 일도 안 함                     // ★574 시동 ON 엣지 훅(업데이트 안내) — 설정 안 하면 아무 일도 안 함
    public volatile PowerLevelReader powerLevelReader;
    public interface IgnSnap { void snap(String tag); }        // 엣지 시 진단 스냅샷(SnifferService.ignSnapshot)
    public volatile IgnSnap ignSnap;
    // ★리모컨/앱이 공조 전원을 직접 조작한 시각 — 방어층으로 유지(판별기가 getPowerLevel로 이미 거르지만 NA 폴백 대비).
    private volatile long acPowerManualTs = 0;
    public void markAcPowerManual() { acPowerManualTs = System.currentTimeMillis(); }

    private IgnitionTracker ign() {
        IgnitionTracker t = ignTracker;
        if (t == null) {
            IgnitionTracker.Persisted p = new IgnitionTracker.Persisted();
            if (presetPrefs != null) {
                p.lastIgn = presetPrefs.getInt("ign_last", -1);
                p.lastIgnTs = presetPrefs.getLong("ign_ts", 0);
                // 옛 D5 키(d5_saw_pwr2)도 이어받음 — 한 번 2를 본 차량은 계속 신뢰
                p.sawPwr2 = presetPrefs.getBoolean("ign_saw2", presetPrefs.getBoolean("d5_saw_pwr2", false));
                p.boarded = presetPrefs.getBoolean("ign_boarded", false);
                p.lastSeenTs = presetPrefs.getLong("ign_seen", 0);
            }
            t = new IgnitionTracker(p, System.currentTimeMillis());
            ignTracker = t;
            if (p.lastIgn >= 0) stateSet("ac_power", p.lastIgn);   // 부팅 직후 소비처가 -1(미상) 대신 마지막 판정을 보게
            // ★적대적 검증 #2(2026-09-11): acPowerOnTs가 RAM 전용이라 ON 엣지 뒤 프로세스가 죽으면 부활 인스턴스에서 0 →
            //   `acPowerOnTs > 0` 게이트에 막혀 무탑승 마무리가 영구 침묵(재설계의 존재 이유였던 케이스). 영속 ON 시각으로 시드.
            if (p.lastIgn == 1 && p.lastIgnTs > 0 && acPowerOnTs == 0) acPowerOnTs = p.lastIgnTs;
        }
        return t;
    }
    private IgnitionTracker.Persisted ignLastSaved;   // ★재검증 부작용: 매 폴(500ms~2s) prefs 쓰기 방지 — 변화 시 또는 30s마다만
    private void persistIgn() {
        if (presetPrefs == null || ignTracker == null) return;
        IgnitionTracker.Persisted p = ignTracker.snapshot();
        IgnitionTracker.Persisted q = ignLastSaved;
        boolean changed = q == null || q.lastIgn != p.lastIgn || q.lastIgnTs != p.lastIgnTs || q.sawPwr2 != p.sawPwr2 || q.boarded != p.boarded
                || (p.lastSeenTs - q.lastSeenTs) >= 30000;   // lastSeenTs는 stale(10분) 판정용이라 30s 해상도면 충분
        if (!changed) return;
        ignLastSaved = p;
        presetPrefs.edit().putInt("ign_last", p.lastIgn).putLong("ign_ts", p.lastIgnTs).putLong("ign_seen", p.lastSeenTs)
                .putBoolean("ign_saw2", p.sawPwr2).putBoolean("ign_boarded", p.boarded).apply();
    }
    private int readPowerLevel() {
        PowerLevelReader r = powerLevelReader;
        if (r == null) return IgnitionTracker.NA;
        try { return r.read(); } catch (Throwable t) { return IgnitionTracker.NA; }
    }

    /** AC_POWER_STATE 이벤트(D3). 공조 전용 키 갱신 + 판별기 트리거. */
    private synchronized void onAcPowerEvent(int p) {   // ★적대적 검증 #7: 판별기 진입점 직렬화
        state.put("ac_start_ev", p);   // 공조 전원 이벤트값(진단용). 타일은 ac_start_st(게터)를 본다.
        long now = System.currentTimeMillis();
        int pwr = readPowerLevel();
        IgnitionTracker.Edge e = ign().onAcPowerEvent(p, pwr, now);
        Log.i(TAG, "AC_POWER_STATE=" + p + " pwrLevel=" + (pwr == IgnitionTracker.NA ? "NA" : pwr) + " → " + e);
        applyIgnEdge(e, now, "AC이벤트 " + p + "/pwr=" + (pwr == IgnitionTracker.NA ? "NA" : pwr));
    }
    /** getPowerLevel 폴링(D3/D5 공통). screenOn=D5 화면 프록시(D3는 false). */
    public synchronized void onPowerLevelPoll(int pwrLevel, boolean screenOn) {
        long now = System.currentTimeMillis();
        applyIgnEdge(ign().onPowerLevelPoll(pwrLevel, screenOn, now), now, "폴링 pwr=" + (pwrLevel == IgnitionTracker.NA ? "NA" : pwrLevel));
    }
    /** 부팅 첫 관측 — 영속 상태와 대조해 놓친 엣지/재시작 구분. */
    public synchronized void onPowerLevelBoot(int pwrLevel) {
        long now = System.currentTimeMillis();
        IgnitionTracker.Edge e = ign().onBoot(pwrLevel, now);
        Log.i(TAG, "부팅 시동판정 pwr=" + (pwrLevel == IgnitionTracker.NA ? "NA" : pwrLevel) + " persisted=" + ign().ign() + " → " + e);
        applyIgnEdge(e, now, "부팅 pwr=" + (pwrLevel == IgnitionTracker.NA ? "NA" : pwrLevel));
    }
    public int ignitionState() { return ign().ign(); }

    private synchronized void applyIgnEdge(IgnitionTracker.Edge e, long now, String why) {
        IgnitionTracker t = ign();
        stateSet("ac_power", t.ign());   // 소비처 계약: ac_power = 시동(-1/0/1). stateSet=클러스터 SSE 통지 경로(2026-09-17)
        persistIgn();
        if (e == IgnitionTracker.Edge.NONE) return;
        final IgnSnap snap = ignSnap;
        if (snap != null) { final String tag = "시동 " + e + " (" + why + ")"; new Thread(() -> { try { Thread.sleep(1500); } catch (InterruptedException ignored) {} snap.snap(tag); }, "ignsnap").start(); }
        if (e == IgnitionTracker.Edge.ON) {
            acPowerOnTs = now; resetCoolTimer(); presetDone = false; savePresetDone(false);
            Log.i(TAG, "시동ON(" + why + ") boarded=" + t.boarded() + " → 프리셋");
            applyStartPreset();   // 세션당 1회(presetDone 가드)
            Runnable h = onIgnitionOn;
            if (h != null) { try { h.run(); } catch (Throwable ignored) {} }   // ★574 업데이트 안내 — 프리셋 경로를 막지 않게 예외 격리
        } else {   // OFF
            Runnable ho = onIgnitionOff;
            if (ho != null) { try { ho.run(); } catch (Throwable ignored) {} }
            if (coolStartMs > 0) { coolAccumMs += now - coolStartMs; coolStartMs = 0; saveCoolTimer(); }
            boolean manualOff = now - acPowerManualTs < 60000;
            if (manualOff) Log.i(TAG, "시동OFF 직전 수동 공조조작(" + ((now - acPowerManualTs) / 1000) + "s 전) → 무탑승 마무리 스킵(방어층)");
            if (!t.boarded() && !manualOff && acPowerOnTs > 0 && now >= afterblowUntilMs) {
                Log.i(TAG, "시동OFF·무탑승(도어이벤트 없음) → climate_off_noboard 발사 (" + why + ")");
                postCarEvent("climate_off_noboard");
            } else {
                Log.i(TAG, "시동OFF(" + why + ") boarded=" + t.boarded() + " onTs=" + acPowerOnTs + " → 무탑승 마무리 안 함");
            }
            presetDone = false; savePresetDone(false);
        }
    }

    // ★620 자율 동작 게이트(킬스위치·주 사용권 폰 근접) — SnifferService.autoAllowed 주입. 09-23 15:44 실측: 폰(대기/부) 없이 차키로 열고 닫자
    //   차가 스스로 전원 ON → 이 프리셋이 시트·핸들열선·선쉐이드를 움직였다.
    static volatile java.util.function.BooleanSupplier autoGate;
    private void applyStartPreset() {
        final boolean advOn = startPresetAdv != null;
        if (!startPresetOn && !advOn) return;
        java.util.function.BooleanSupplier g = autoGate;
        if (g != null && !g.getAsBoolean()) { presetDbg = "자율 동작 차단(킬스위치 또는 주 사용권 폰 부재) — 프리셋 스킵"; return; }
        if (System.currentTimeMillis() < afterblowUntilMs) { presetDbg = "afterblow 중 — 프리셋 스킵(선쉐이드/미러 유지)"; return; }   // ★afterblow OPENAIR엔 프리셋 미발동
        // ★786 시동이 켜져 있을 때만 — 10-06 08:21 운영자 차: 시동 끄는 순간 릴레이가 다시 시작(태스크 정리) → AC 초기 이벤트로 아래 콜드부팅 만회(v199)가
        //   시동 꺼진 채 프리셋을 돌려(꺼질 때 acPowerOnTs=0이라 3분 창도 안 걸림) 아침 규칙 선쉐이드 100% → 위치 미상 우회로 닫혔다 다시 열림(운영자 "끝까지 닫아야").
        //   전원 단계 게터(2=ON)를 먼저, 못 읽으면 시동 판별기.
        int pwNow = readPowerLevel();
        boolean ignOn = pwNow != IgnitionTracker.NA ? pwNow == 2 : ign().ign() == 1;
        if (!ignOn) { presetDbg = "시동 꺼짐(전원 " + pwNow + "·판별 " + ign().ign() + ") — 프리셋 스킵"; return; }
        // ★v279 부트온 시간창 — 시동ON 후 3분 내에만 적용. out_temp(외기온)가 아주 늦게 도착(콜드부팅 이상)해
        //   운행 중 시트/선쉐이드가 뜬금 적용되던 것(2026-08-20 실측: 시동 15분 뒤 운행 중 통풍시트 켜짐) 방지.
        //   acPowerOnTs=0(전원0→1 놓친 콜드부팅 만회)이면 시간창 미적용(기존 만회 로직 유지).
        if (acPowerOnTs > 0 && System.currentTimeMillis() - acPowerOnTs > 180000) {
            presetDbg = "부트온 시간창 초과(시동 " + ((System.currentTimeMillis() - acPowerOnTs) / 1000) + "s 전) — 프리셋 스킵";
            presetDone = true; savePresetDone(true); return;
        }
        if (presetDone || presetRunning) return; presetRunning = true;   // 세션당 1회(단 out_temp 없어 시트 스킵되면 재시도 허용)
        final String cmdsJson = startPresetCmds;
        final String advJson = startPresetAdv;
        new Thread(() -> {
            try { Thread.sleep(4000); } catch (InterruptedException e) { return; }   // 부트 후 좌석/공조 시스템 준비 대기(실측 ~3s)
            try {
                // ★v403 고급 시트/선쉐이드 평가 — 켜진 항목은 고정 cmds 대신 외기온도·시계 기반 컴퓨티드로.
                boolean advSeat = false, advShade = false, advSteer = false;
                int shadeTarget = -1;   // ★v282 선쉐이드 폐루프 목표(-1=없음)
                java.util.List<JSONObject> computed = new java.util.ArrayList<>();
                java.util.Map<String, Integer> seatTargets = new java.util.HashMap<>();   // ★v203 seat_dv/dh/pv/ph 목표 — read-back 검증용
                if (advJson != null) {
                    try {
                        JSONObject adv = new JSONObject(advJson);
                        Integer ot = null; Object o = state.get("out_temp");
                        if (o instanceof Number) { ot = ((Number) o).intValue(); saveOutTemp(ot); }
                        else { int lt = loadOutTemp(); if (lt != Integer.MIN_VALUE) ot = lt; }   // ★콜드부팅: 직전 저장 외기온 즉시 폴백(신선값 오면 다음 트리거서 갱신)
                        // ★v202 시트 조건규칙 — 운전석/조수석 분리. op:0초과 1이상 2이하 3미만. 온도는 앱측 담당.
                        //   adv.seat.rules=운전석, adv.seat.prules=조수석(없거나 비면 조수석은 동작 안 함 — 10-08 FB2197).
                        JSONObject seatAdv = adv.optJSONObject("seat");
                        if (seatAdv != null && seatAdv.optBoolean("on") && ot != null) {
                            org.json.JSONArray dRules = seatAdv.optJSONArray("rules");
                            org.json.JSONArray pRules = seatAdv.optJSONArray("prules");
                            Integer dpos = evalRules(dRules, ot);
                            Integer ppos = evalRules(pRules, ot);
                            if (dpos != null) { advSeat = true; addSeatCmds(computed, 1, dpos); seatTargets.put("seat_dh", dpos == 0 ? 3 : (dpos == 1 ? 2 : 1)); seatTargets.put("seat_dv", dpos == 3 ? 2 : (dpos == 4 ? 3 : 1)); Log.i(TAG, "고급 시트 운전석: 외기" + ot + " pos" + dpos); }
                            if (ppos != null) { advSeat = true; addSeatCmds(computed, 2, ppos); seatTargets.put("seat_ph", ppos == 0 ? 3 : (ppos == 1 ? 2 : 1)); seatTargets.put("seat_pv", ppos == 3 ? 2 : (ppos == 4 ? 3 : 1)); Log.i(TAG, "고급 시트 조수석: 외기" + ot + " pos" + ppos); }
                        }
                        // ★v202 핸들열선 온도 고급 — adv.steer.rules val: 1=끄기 2=켜기.
                        JSONObject steerAdv = adv.optJSONObject("steer");
                        if (steerAdv != null && steerAdv.optBoolean("on") && ot != null) {
                            Integer sv = evalRules(steerAdv.optJSONArray("rules"), ot);
                            if (sv != null) { advSteer = true;
                                JSONObject sc = new JSONObject();
                                try { sc.put("device", "setting"); sc.put("method", "setSteeringWheelHeatingState"); sc.put("args", new org.json.JSONArray().put(sv)); } catch (Throwable ignored3) {}
                                computed.add(sc); seatTargets.put("steer_h", sv); Log.i(TAG, "고급 핸들열선: 외기" + ot + " val" + sv);   // ★v204 리셋방어 검증 대상
                            }
                        }
                        JSONObject shade = adv.optJSONObject("shade");
                        if (shade != null && shade.optBoolean("on")) {
                            advShade = true;
                            java.util.Calendar cal = java.util.Calendar.getInstance();
                            int nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
                            int st = shade.optInt("start"), en = shade.optInt("end"), pct = shade.optInt("pct");
                            boolean inRange = st <= en ? (nowMin >= st && nowMin < en) : (nowMin >= st || nowMin < en);
                            int p = inRange ? pct : 0;
                            if (p > 0) { computed.add(bodyRaw("nudgeSunshadeState", p)); shadeTarget = p; }
                            Log.i(TAG, "고급 선쉐이드: " + nowMin + "분 " + (inRange ? p + "%" : "구간밖"));
                        }
                    } catch (Throwable ignored) {}
                }
                // 고정 cmds(startPresetOn일 때만). 고급이 담당하는 항목(시트/선쉐이드)은 스킵.
                if (startPresetOn && cmdsJson != null) {
                    org.json.JSONArray arr = new org.json.JSONArray(cmdsJson);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject cmd = arr.optJSONObject(i);
                        if (cmd == null) continue;
                        String m = cmd.optString("method", "");
                        if (advSeat && (m.equals("setSeatHeatingState") || m.equals("setSeatVentilatingState"))) continue;
                if (advSteer && m.equals("setSteeringWheelHeatingState")) continue;
                        if (advShade && m.equals("nudgeSunshadeState")) continue;
                        try { control.execute("RAW", cmd); } catch (Throwable ignored) {}
                        try { Thread.sleep(300); } catch (InterruptedException e) { return; }
                    }
                }
                Integer otDbg = null; Object oo = state.get("out_temp"); if (oo instanceof Number) otDbg = ((Number) oo).intValue();
                presetDbg = "실행 advOn=" + advOn + " ot=" + otDbg + " advSeat=" + advSeat + " advShade=" + advShade + " computed=" + computed.size() + " @" + (System.currentTimeMillis() % 100000);
                for (JSONObject cmd : computed) {
                    try { String er = control.execute("RAW", cmd); if (er != null) presetDbg += " ERR:" + er; } catch (Throwable t2) { presetDbg += " EX:" + t2.getMessage(); }
                    try { Thread.sleep(300); } catch (InterruptedException e) { presetRunning = false; return; }
                }
                // ★v203 통풍 리셋 방어 — 블라인드 재적용 대신 read-back 폐루프. 차가 좌석 off로 리셋하면 감지해 재적용, 2회 연속 목표 일치하면 멈춤.
                //   seat_dv/dh/pv/ph는 gear-poll(2s)이 실차 getSeatXxxState로 갱신 → 실제 걸렸는지 정확 판정.
                if ((advSeat || advSteer) && !seatTargets.isEmpty()) {
                    int stable = 0, reapply = 0;
                    for (int attempt = 0; attempt < 15 && stable < 2; attempt++) {
                        try { Thread.sleep(2500); } catch (InterruptedException e) { presetRunning = false; return; }
                        boolean allOk = true;
                        for (java.util.Map.Entry<String, Integer> e : seatTargets.entrySet()) {
                            Object cur = state.get(e.getKey());
                            if (!(cur instanceof Number) || ((Number) cur).intValue() != e.getValue()) { allOk = false; break; }
                        }
                        if (allOk) { stable++; }
                        else {
                            stable = 0; reapply++;
                            for (JSONObject cmd : computed) {
                                String mm = cmd.optString("method", "");
                                if (mm.equals("setSeatVentilatingState") || mm.equals("setSeatHeatingState") || mm.equals("setSteeringWheelHeatingState")) {
                                    try { control.execute("RAW", cmd); } catch (Throwable ignored4) {}
                                    try { Thread.sleep(200); } catch (InterruptedException e) { presetRunning = false; return; }
                                }
                            }
                        }
                    }
                    presetDbg += " 검증(안정" + stable + " 재적용" + reapply + ")";
                }
                // ★v282 선쉐이드 read-back 폐루프 — 콜드부팅 시 setSunshade OK 떠도 모터 안 움직이던 것 방지. 목표 도달까지 재적용(멱등 nudge).
                if (advShade && shadeTarget > 0) {
                    int sstable = 0;
                    for (int at = 0; at < 10 && sstable < 2; at++) {
                        try { Thread.sleep(2500); } catch (InterruptedException e) { presetRunning = false; return; }
                        int cur = sunshadePct();
                        if (cur >= 0 && Math.abs(cur - shadeTarget) <= 5) sstable++;
                        else { sstable = 0; try { control.execute("RAW", bodyRaw("nudgeSunshadeState", shadeTarget)); } catch (Throwable ignoredSs) {} }
                    }
                    presetDbg += " 선쉐이드검증(안정" + sstable + " cur" + sunshadePct() + ")";
                }
                // ★v201 시트 원하는데 외기(out_temp) 아직 안 왔으면 마킹 안 하고 재시도 허용(콜드부팅 시 AC_TEMP_OUT 늦게 옴)
                boolean retry = false;
                try { JSONObject adv = advJson != null ? new JSONObject(advJson) : null;
                    JSONObject sa = adv != null ? adv.optJSONObject("seat") : null;
                    if (sa != null && sa.optBoolean("on") && !(state.get("out_temp") instanceof Number) && loadOutTemp() == Integer.MIN_VALUE) retry = true;   // ★v282 저장 외기온 폴백 있으면 재시도 불필요
                } catch (Throwable ignored2) {}
                presetRunning = false;
                if (!retry) { presetDone = true; savePresetDone(true); } else presetDbg += " (외기대기·재시도)";
            } catch (Throwable t) { presetDbg = "예외:" + t.getMessage(); presetRunning = false; }
        }, "startpreset").start();
    }
    // ★v403 외기온도 → 시트 pos(0=열선2 1=열선1 2=정지 3=통풍1 4=통풍2).
    private int seatPosFromTemp(int t, JSONObject seat) {
        double h1 = seat.optDouble("h1", 12), h2 = seat.optDouble("h2", 3), v1 = seat.optDouble("v1", 26), v2 = seat.optDouble("v2", 32);
        if (t <= h2) return 0; if (t <= h1) return 1; if (t >= v2) return 4; if (t >= v1) return 3; return 2;
    }
    private JSONObject seatRaw(String method, int seat, int val) {
        JSONObject o = new JSONObject();
        try { o.put("device", "setting"); o.put("method", method); o.put("args", new org.json.JSONArray().put(seat).put(val)); } catch (Throwable ignored) {}
        return o;
    }
    // ★v202 조건규칙 평가 — 위에서 첫 매칭 val 반환(없으면 null). op:0초과 1이상 2이하 3미만.
    private Integer evalRules(org.json.JSONArray rules, int ot) {
        if (rules == null) return null;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.optJSONObject(i); if (r == null) continue;
            double th = r.optDouble("th", 25); int op = r.optInt("op", 1);
            boolean m = (op == 0) ? ot > th : (op == 1) ? ot >= th : (op == 2) ? ot <= th : ot < th;
            if (m) return r.optInt("val", 2);
        }
        return null;
    }
    // 시트 pos(0=열선2 1=열선1 2=정지 3=통풍1 4=통풍2) → heat/vent 명령 추가.
    private void addSeatCmds(java.util.List<JSONObject> computed, int seat, int pos) {
        int heat = pos == 0 ? 3 : (pos == 1 ? 2 : 1);
        int vent = pos == 3 ? 2 : (pos == 4 ? 3 : 1);
        computed.add(seatRaw("setSeatHeatingState", seat, heat));
        computed.add(seatRaw("setSeatVentilatingState", seat, vent));
    }
    private JSONObject bodyRaw(String method, int val) {
        JSONObject o = new JSONObject();
        try { o.put("device", "bodywork"); o.put("method", method); o.put("args", new org.json.JSONArray().put(val)); } catch (Throwable ignored) {}
        return o;
    }

    public void setCredentials(String uid, String vin) { this.uid = uid == null ? "" : uid; this.vin = vin == null ? "" : vin; }

    // 상태 폴링(배터리/주행/기어/잠금 등) 직접 주입 — SnifferService 폴 스레드가 호출.
    public void putStatus(String key, Object val) { if (key != null && val != null) stateSet(key, val); }
    // ★v212 차 전원상태(AC_POWER_STATE) — 1=시동켜짐 0=꺼짐 -1=미상. 브레이크-공조 게이트용.
    public int carPowerState() { return ign().ign(); }   // ★시동(판별기). -1=미상/0/1
    // ★선쉐이드 현재 위치(%) — BODYWORK_SUNSHADE_PANEL_PERCENT 센서값. 멱등 nudge(이미 목표면 스킵)용. -1=미상.
    public int sunshadePct() { Object o = state.get("sunshade_pct"); return (o instanceof Integer) ? (Integer) o : -1; }
    public int acTempRaw() { Object o = state.get("ac_temp_raw"); return (o instanceof Integer) ? (Integer) o : -1; }   // ★634 설정온도 ℃×2(AC_TEMP_MAIN 이벤트), -1=미상
    // ★v107 BLE 명령 채널용 — 상태 전체를 JSON으로(폰이 GATT read로 워커 없이 상태 조회)
    public String stateJson() { try { return new JSONObject(state).toString(); } catch (Throwable t) { return "{}"; } }

    // SnifferService.onVehicleEvent / relay 폴링에서 관심 신호를 여기로.
    // ★정확 매칭만 — 느슨한 substring이 엉뚱한 값(배터리 8% 등)을 잡던 문제 해결. 확인된 것만 올린다.
    public void updateFromEvent(String featureName, String value) {
        // ★Phase 1(overdrive-porting-plan.md): 차종별 이벤트명을 돌핀 정규명으로 정규화.
        //   돌핀(DiLink3) 이름은 표에 없어 그대로 통과 = 무회귀. DiLink5 별칭은 실차 덤프로 채움.
        String f = FeatureAlias.normalize(featureName).toUpperCase();
        try {
            int v01 = value.contains("1") ? 1 : 0;
            // 좌석별 안전벨트(실차 확정 이름, 1=착용/0=미착용) → 개별 + 집계 occupant
            // 운전석=instrument, 조수석(DEPUTY)·2열=safetybelt 디바이스.
            if (f.equals("INSTRUMENT_DD_MAIN_SAFETYBELT_STATE")) { state.put("belt_driver", v01); recomputeOccupant(); }
            else if (f.equals("SAFETY_BELT_COMMAND_AREA_MAIN")) { state.put("belt_driver", v01); recomputeOccupant(); }   // safetybelt 도메인 운전석 이벤트(실차 확인)
            // ★v151 재실(착석·압력센서 SAFETY_BELT_PASSENGER_COMMAND) — 벨트 안 매도 사람 앉으면 1. 클러스터 "미체결" 경고의 근거.
            //   벨트(COMMAND_AREA)와 이름이 겹쳐 먼저 분기(안 그러면 아래 벨트 handler가 덮어썼음 — 버그). 운전석(MAIN)은 재실센서 없음(운전자 가정). 값 인코딩 실차확인 대기.
            else if (f.contains("SAFETY_BELT_PASSENGER_COMMAND")) {
                if (f.contains("DEPUTY")) { state.put("occ_passenger", v01); recomputeOccupant(); }
                else if (f.contains("SECOND_ROW_SEAT_LEFT")) { state.put("occ_rear_l", v01); recomputeOccupant(); }
                else if (f.contains("SECOND_ROW_SEAT_MID")) { state.put("occ_rear_m", v01); recomputeOccupant(); }
                else if (f.contains("SECOND_ROW_SEAT_RIGHT")) { state.put("occ_rear_r", v01); recomputeOccupant(); }
            }
            else if (f.contains("SAFETY_BELT_COMMAND_AREA_DEPUTY")) { }   // ★조수석 안전벨트는 받지 않음(운영자 10-03 "아예 필요없다") — 주차 중 값이 흔들려 상태 전체 재전송의 최대 원인(29대 하루 2만 회+). 분기는 남겨 아래 분기로 새지 않게
            else if (f.contains("SAFETY_BELT_COMMAND_AREA") && f.contains("SECOND_ROW_SEAT_LEFT")) { state.put("belt_rear_l", v01); recomputeOccupant(); }
            else if (f.contains("SAFETY_BELT_COMMAND_AREA") && f.contains("SECOND_ROW_SEAT_RIGHT")) { state.put("belt_rear_r", v01); recomputeOccupant(); }
            else if (f.equals("INSTRUMENT_DD_REAR_LEFT_SAFETYBELT_STATE")) { state.put("belt_rear_l", v01); recomputeOccupant(); }   // 뒷좌석 벨트(폴링 전용)
            else if (f.equals("INSTRUMENT_DD_REAR_RIGHT_SAFETYBELT_STATE")) { state.put("belt_rear_r", v01); recomputeOccupant(); }
            // 도어별 잠금(정확 이름) → 개별 + 집계 lock
            else if (f.contains("DOOR_LOCK_COMMAND_AREA_LEFT_FRONT")) { state.put("lock_lf", parseLock(value)); recomputeLock(); }
            else if (f.contains("DOOR_LOCK_COMMAND_AREA_RIGHT_FRONT")) { state.put("lock_rf", parseLock(value)); recomputeLock(); }
            else if (f.contains("DOOR_LOCK_COMMAND_AREA_LEFT_REAR")) { state.put("lock_lr", parseLock(value)); recomputeLock(); }
            else if (f.contains("DOOR_LOCK_COMMAND_AREA_RIGHT_REAR")) { state.put("lock_rr", parseLock(value)); recomputeLock(); }
            // ★하차 감지(v30): 도어 여닫음 엣지 → /relay/event → 폰 silent push 즉시 웨이크(이탈 감시).
            //   INSTRUMENT_DD_*_DOOR_STATE: 1=열림/0=닫힘(실차 확정 2026-07-08). 최초값(부팅)은 엣지 아님 → 전송 안 함.
            //   ★v91 4문+트렁크 전부 추적(이름 실차 덤프 확정) — 2열 문(카시트)이 안 잡혀 이탈 오잠금 나던 것 근본 수정.
            //     any_door_open을 state로 → 폰이 "문 열려 있으면 이탈 보류" 가드.
            else if (f.equals("INSTRUMENT_DD_LEFT_FRONT_DOOR_STATE")) { doorEdge("door_driver", v01); }
            else if (f.equals("INSTRUMENT_DD_RIGHT_FRONT_DOOR_STATE")) { doorEdge("door_rf", v01); }
            else if (f.equals("INSTRUMENT_DD_LEFT_REAR_DOOR_STATE")) { doorEdge("door_lr", v01); }
            else if (f.equals("INSTRUMENT_DD_RIGHT_REAR_DOOR_STATE")) { doorEdge("door_rr", v01); }
            else if (f.equals("INSTRUMENT_DD_LUGGAGE_DOOR_STATE")) { doorEdge("door_trunk", v01); }
            // BSD 좌/우 방향 후보 (폴링 전용, raw 값 보존). DOW_WARN은 하차경고지만 유일한 방향성 후보 — 실차 검증 대기.
            else if (f.equals("ADAS_DOW_WARN_LEFT")) { state.put("bsd_left", parseInt(value)); }
            else if (f.equals("ADAS_DOW_WARN_RIGHT")) { state.put("bsd_right", parseInt(value)); }
            else if (f.equals("ADAS_BSD_STATE")) { stateSet("bsd_state", parseInt(value)); }   // stateSet=클러스터 SSE 통지(2026-09-17)
            else if (f.contains("LEFT_OUT_MIRROR_SYSTEM_WORKING")) { state.put("mirror_l", parseInt(value)); }
            else if (f.contains("RIGHT_OUT_MIRROR_SYSTEM_WORKING")) { state.put("mirror_r", parseInt(value)); }
            // 선쉐이드 위치(0~100%) + 공기순환(AC_CYCLE_MODE: 1=외기/2=내기) — bodywork/ac 구독으로 옴.
            else if (f.equals("BODYWORK_SUNSHADE_PANEL_PERCENT")) { state.put("sunshade_pct", parseInt(value)); }
            else if (f.equals("AC_CYCLE_MODE")) { state.put("ac_cycle", parseInt(value)); }
            // ★v149 차량 부트온(공조 전원 0→1) 감지 → 시동 시 프리셋 자동적용(물리/클라우드 무관). BOOT_COMPLETED는 웜스타트 놓쳐 부적합.
            else if (f.equals("AC_POWER_STATE")) {
                onAcPowerEvent(parseInt(value));   // ★2026-09-11 판별기 경유 — 공조만 꺼진 건 시동 엣지가 아니다
                // ★공조 표시 보강(운영자 10-09 "ㅇㅇ") — 차가 보낸 전원 신호로 리모컨 전원 칸을 바로 맞추고(2초 폴링 안 기다림),
                //   꺼짐이면 에어컨·송풍 칸도 꺼짐으로(차가 꺼질 때 공조 모드 신호를 다시 안 보내 마지막 모드 칸이 켜진 채 남던 것). 성에·열선은 무관.
                int pwrV = parseInt(value);
                if (pwrV == 0 || pwrV == 1) state.put("ac_start_st", pwrV);
                if (pwrV == 0) { state.put("ac_cool_st", 0); state.put("ac_vent_st", 0); }
            }
            // ★v89 공조 워크모드(1=송풍ONLY/2=A/C, 히터값 미확정—관찰) + 풍량 — 아이폰 차량상태 "공조 모드" 표시용.
            else if (f.equals("AC_AIR_CONDITION_WORK_MODE")) {
                int wm = parseInt(value);
                Object prev = state.get("ac_work_mode");
                stateSet("ac_work_mode", wm);
                // ★리모컨 타일 상태 파생(2026-09-11 운영자 "에어컨·송풍온리 상태값 반영 안 됨") —
                //   ac_cool이 쓰던 getAcCompressorMode는 항상 1을 주는 '모드값'이라 on/off를 못 나타낸다.
                //   실제 구분은 이 이벤트에 있다: AC_AIR_CONDITION_WORK_MODE 1=송풍ONLY · 2=A/C.
                //   앱 매니페스트는 키→값 단순비교라, 매핑은 릴레이가 해서 0/1로 내려준다.
                state.put("ac_cool_st", wm == 2 ? 1 : 0);
                state.put("ac_vent_st", wm == 1 ? 1 : 0);
                // ★v147 냉방(A/C=2) 켜진 시간 누적 — 하차 애프터블로우 "AC N분 이상" 조건용. 전이 시점에 갱신.
                boolean wasCool = (prev instanceof Integer) && ((Integer) prev == 2);
                boolean nowCool = (wm == 2);
                if (nowCool && !wasCool) { coolStartMs = System.currentTimeMillis(); saveCoolTimer(); }
                else if (!nowCool && wasCool && coolStartMs > 0) { coolAccumMs += System.currentTimeMillis() - coolStartMs; coolStartMs = 0; saveCoolTimer(); }
            }
            else if (f.equals("AC_WIND_LEVEL")) { stateSet("ac_wind", parseInt(value)); }   // stateSet=클러스터 SSE 통지(2026-09-17)
            // ★리모컨 실시간 상태(2026-09-10) — 차 안 물리버튼으로 바꿔도 앱 타일이 따라오게 이벤트로 미러.
            //   설정온도는 SDK 원값이 ℃×2(쓰기 스케일과 동일) → 앱이 2로 나눠 표시.
            else if (f.equals("AC_TEMP_MAIN")) { stateSet("ac_temp_raw", parseInt(value)); }
            else if (f.equals("AC_DEFROST_REAR_STATE")) { state.put("defog_rear", parseInt(value)); }
            else if (f.equals("AC_DEFROST_FRONT_STATE")) { state.put("defog_front", parseInt(value)); }
            else if (f.equals("BODYWORK_SUNROOF_WINDOBLIND_POSITION")) { state.put("shade_pos", parseInt(value)); }
            // ★v199 콜드부팅 만회 — 릴레이가 늦게 시작해 AC_POWER_STATE 0→1을 놓쳐도, AC 이벤트가 흐르면(차 켜짐) 프리셋 1회 적용.
            { Object otc = state.get("out_temp"); if (otc instanceof Number) saveOutTemp(((Number) otc).intValue()); }   // ★v282 최신 외기온 지속 persist(콜드부팅 폴백 신선도)
            if (!presetDone && f.startsWith("AC_") && (startPresetOn || startPresetAdv != null)) applyStartPreset();
            // ⚠️ 배터리/주행거리/시동은 정확한 featureId 확정 전까지 매핑 안 함(엉터리 값 방지). 진단으로 찾는 중.
        } catch (Exception ignored) {}
    }

    // ── 진단 채널 — 프로브 결과를 상태에 실어 폰/워커로(내가 /relay/status로 읽음) ──
    private final Map<String, Object> diag = new ConcurrentHashMap<>();
    public void setDiag(String key, String val) { diag.put("_" + key, val); }

    // ★재실판정 = 압력센서(occ_*)만 사용. 벨트 신호는 재실 프록시로 부적합해 제거(2026-08-26):
    //   ①벨트=체결상태이지 재실 아님 ②운전석뒤 2열 벨트연장킷 상시장착으로 belt_rear_l 영구1 → occupant 영구오염.
    //   실제 재실키(0x31200018 등)는 ICarProperty signature 권한벽으로 읽기 불가([[byd-occupancy-detection-state]]) →
    //   occ_*는 현재 미populate = occupant는 사실상 0. 벨트 OR 제거로 "빈 차인데 occupant=1" 유령값 종결.
    private void recomputeOccupant() {
        int occ = 0;
        for (String k : new String[]{"occ_passenger", "occ_rear_l", "occ_rear_m", "occ_rear_r"}) {
            Object v = state.get(k);
            if (v instanceof Integer && (Integer) v == 1) { occ = 1; break; }
        }
        state.put("occupant", occ);
    }

    // 아는 도어가 전부 잠김(1)이면 lock=1, 하나라도 해제면 0
    private void recomputeLock() {
        boolean any = false, allLocked = true;
        for (String k : new String[]{"lock_lf", "lock_rf", "lock_lr", "lock_rr"}) {
            Object v = state.get(k);
            if (v instanceof Integer) { any = true; if ((Integer) v != 1) allLocked = false; }
        }
        if (any) stateSet("lock", allLocked ? 1 : 0);
    }

    // ★v106 운영자 전용 네트워크 게이트 — false면 워커 폴링·이벤트 push 전부 침묵(상태캐시·비콘 minor만 사용).
    public static volatile boolean network = true;

    public void start() {
        if (running || !network) return;
        running = true;
        new Thread(this::loop, "relay-loop").start();
    }
    public void stop() { running = false; }

    private volatile long lastActivityMs = 0;   // ★v147 적응형 폴링 — 명령수신·차이벤트·승차 시각(활성 판정)
    // ★롱폴(운영자 10-07 "롤백준비 해놓고 도전하자") — 유휴 경량 폴에 wait를 붙이면 서버가 명령이 생길 때까지 최대 20초 붙잡았다 바로 답한다.
    //   서버가 longpoll.off면 lp 없이 바로 답해 종전 4초 주기로 돌아간다(릴레이 재배포 없이 롤백). 붙잡힌 동안 주행·시동이 바뀌면 끊고 새 상태를 올린다.
    static final int LP_WAIT_S = 20;
    private volatile boolean lastPollHeld = false;
    private volatile HttpURLConnection lpConn;
    private volatile boolean lpAborted = false;
    private void abortLongPoll() {
        HttpURLConnection c = lpConn;
        if (c == null) return;
        lpConn = null; lpAborted = true;
        try { c.disconnect(); } catch (Throwable ignored) {}
    }

    private void loop() {
        while (running) {
            try {
                if (!vin.isEmpty() || !devId.isEmpty()) {   // ★vin 미설정(신규)이라도 devId 있으면 부트스트랩 폴링(SETVIN 수신)
                    JSONArray cmds = pushStateAndGetCommands();
                    if (cmds != null && cmds.length() > 0) {
                        lastActivityMs = System.currentTimeMillis();   // 명령 받음 → 후속 명령 대비 잠시 고속
                        kickFastPoll();                                 // ★리모컨 연속 조작 반응성 — 20초간 600ms
                        for (int i = 0; i < cmds.length(); i++) dispatch(cmds.getJSONObject(i));
                    }
                }
            } catch (Exception e) { Log.w(TAG, "loop", e); }
            long d = lastPollHeld ? 100 : pollDelay();   // ★롱폴로 답을 받았으면(서버가 이미 기다렸다) 바로 다시 — 서버가 롱폴을 끄면 lp가 없어 종전 주기
            lastPollHeld = false;
            try { Thread.sleep(d); } catch (InterruptedException e) { break; }
        }
    }

    // ★v147 적응형 폴링 주기 — 주행 중이거나 최근 90초 내 활동(명령/도어이벤트/승차)이면 1.5초(명령 반응성),
    //   주차 유휴면 15초(워커 온라인 판정 30초 이내 유지하면서 데이터 ~90% 절감). 상시생존은 네트워크와 무관(접근성/워치독).
    public static volatile boolean cameraActive = false;   // ★v196 카메라 스트리밍 중엔 빠른 폴링(전환 명령 즉시 수신)
    // ★리모컨 조작 직후 고속 폴링(2026-09-10 운영자 "반응이 한 템포씩 느린데") — 폰→워커→릴레이 폴링 왕복이 체감 지연의 대부분.
    //   명령을 하나 받으면 20초간 600ms로 당겨, 연속 조작(온도 ◀▶ 등)이 즉각 반응하게 한다. 유휴 복귀는 자동.
    private volatile long fastPollUntil = 0;
    private volatile long lastLanReqMs = 0;   // ★560 폰이 LAN(핫스팟·같은 라우터)으로 직접 붙은 마지막 시각
    public void noteLan() { lastLanReqMs = System.currentTimeMillis(); }
    public void kickFastPoll() { fastPollUntil = System.currentTimeMillis() + 20000; }
    private long pollDelay() {
        if (cameraActive) return 600;   // 스트리밍 중 — 명령 지연 최소화
        if (clusterClients > 0) return 600;   // ★클러스터 SSE 접속 중 — 깜빡이/속도 폴 백스톱 600ms(2026-09-17)
        if (System.currentTimeMillis() < fastPollUntil) return 600;
        Object dr = state.get("drive_state");
        boolean driving = (dr instanceof Integer) && ((Integer) dr == 1);
        boolean recent = (System.currentTimeMillis() - lastActivityMs) < 90000;
        // ★562 LAN 완화 철회 — 560에서 "LAN 직결 중이면 60초"로 늦췄으나, 카메라·원격 명령이 워커를 거치므로
        //   그만큼 그대로 대기가 됐다(실측: 뷰어 열고 릴레이가 집어가기까지 12초, 그 앞 3회는 응답 전에 사용자가 닫음).
        //   데이터 절감은 559(상태 무변화 시 경량 폴 ~0.2KB)가 이미 달성하므로 이 완화는 불필요한 손해였다.
        // ★유휴 15초→4초: 559로 1회 전송이 16.8KB→0.2KB가 되어, 주기를 당겨도 월 사용량은 2.71GB→약 0.8GB로 오히려 준다.
        //   (전체 전송은 여전히 상태가 바뀔 때와 60초 안전망에서만 나간다.)
        return (driving || recent) ? 1500 : 4000;
    }

    // 상태 push + 대기 명령 수신 (한 콜)
    private JSONArray pushStateAndGetCommands() throws Exception {
        JSONObject st = new JSONObject(state);
        for (Map.Entry<String, Object> e : diag.entrySet()) st.put(e.getKey(), e.getValue());   // 진단 필드 병합
        st.put("cool_min", coolMinutes());   // ★v147 이번 주행 냉방 누적 분 — 앱 애프터블로우 "AC N분 이상" 조건용
        st.put("adv_stored", startPresetAdv == null ? "null" : startPresetAdv);   // ★v199 진단 — 릴레이가 가진 고급 프리셋(시트 미적용 원인 추적)
        st.put("preset_last", presetDbg);   // 마지막 applyStartPreset 결과
        st.put("relay_ver", BuildConfig.VERSION_CODE);   // ★v201 릴레이 버전 — 앱에서 확인용
        st.put("_os_up", android.os.SystemClock.elapsedRealtime() / 1000);   // ★676 헤드유닛 OS 가동 초 — 서버 relay_uptime 이력이 '릴레이만 죽었나/헤드유닛이 재부팅했나'를 가린다(_ 접두=변화 판정 제외)
        if (!devId.isEmpty()) st.put("dev_id", devId);   // ★부트스트랩 신원 노출(앱 등록 UI가 표시·주소지정)
        st.put("shared", !BuildConfig.IS_OPERATOR);   // ★flavor — 공유판이면 앱이 애프터블로우 OPENAIR를 폰 클라우드로 대행(릴레이 클라우드 없음)
        // ★vin 미설정 시 devId로 보고(빈 vin 상태행 충돌 방지). 프로비저닝(SETVIN) 후 실제 vin으로 전환.
        String reportVin = vin.isEmpty() ? devId : vin;
        // ★★559 데이터 절감 — 상태에 의미있는 변화가 없으면 전체(실측 16.8KB) 대신 경량 /relay/poll(~0.2KB)만 보낸다.
        //   실측(09-22): 유휴 15초 주기로 매번 전체를 올려 아무 일 없이 주차만 해도 월 2.7GB를 썼다. 그중 82%가
        //   remote_apps(41%)·remote_manifest(41%) — 거의 바뀌지 않는 정적 데이터의 반복 전송이었다.
        //   워커엔 이미 /relay/poll(명령만 반환 + updated_at 갱신) 경로가 있는데 릴레이가 한 번도 쓰지 않았다.
        //   ★안전장치 ①vin 미설정(devId 보고) 중엔 1분마다 전체 — ★712/706(운영자 10-03) 종전 '매 틱 전체'는 미등록 차가 54KB를 5초마다 보내 하루 약 1GB를 썼다.
        //             그 사이 경량 폴도 vin=devId로 가므로 워커가 dev_id 주소 명령(SETVIN)을 그대로 돌려준다(/relay/poll → pendingCommands(vin)).
        //           ②변화가 없어도 5분마다 한 번은 전체 — 워커 상태가 낡지 않게(폰이 이 값을 읽어 화면에 쓴다).
        //   변화 판정은 워커의 stable 규칙(`_` 접두사 제외)에 생존 시각 2칸(slowKey) 제외를 더한 것.
        String stable = stableOf(st);
        long nowMs = System.currentTimeMillis();
        boolean idle = !cameraActive && clusterClients <= 0 && ignitionState() != 1;   // ★701 진단용 유휴 판정(시동 OFF·카메라/클러스터 없음)
        boolean mustFull = lastStableSent == null || nowMs - lastFullPushMs > (vin.isEmpty() ? 60_000L : 300_000L);
        if (!mustFull && stable.equals(lastStableSent)) {
            JSONObject lb = new JSONObject().put("uid", uid).put("vin", reportVin).put("sig", sig(uid, reportVin));
            if (pollDelay() >= 4000) lb.put("wait", LP_WAIT_S);   // ★롱폴 — 유휴일 때만(주행·카메라·클러스터·직후 고속 폴은 종전)
            JSONObject lr = postJson("/relay/poll", lb);
            lastPollHeld = lr != null && lr.optBoolean("lp", false);
            if (lr != null) {
                JSONArray pc = lr.optJSONArray("commands");
                duDiag.incrementAndGet(idle ? DG_PI_N : DG_PA_N);
                if (pc != null && pc.length() > 0) duDiag.incrementAndGet(idle ? DG_PI_CMD : DG_PA_CMD);
            }
            return lr != null ? lr.optJSONArray("commands") : null;
        }
        boolean first = vin.isEmpty() || lastStableSent == null;
        boolean changed = !first && !stable.equals(lastStableSent);
        Map<String, String> prevMap = lastStableMap, curMap = stableMapOf(st);
        JSONObject body = new JSONObject();
        body.put("uid", uid).put("vin", reportVin).put("sig", sig(uid, reportVin));
        if (!devId.isEmpty()) body.put("dev_id", devId);   // ★워커가 dev_id 주소 명령(SETVIN)도 반환하게
        // ★839 데이터절감 B — 델타 푸시(c&2): 전체 대신 바뀐 키만. 조건: 서버 델타 허용 + 전체 강제(mustFull) 아님 + 첫 푸시 아님 + 직전 전체본(prevMap) 있음.
        //   항상 포함: '_'로 시작하는 키 전부와 relay_ver(stableMap에서 빠지는 값). removed=직전에 있다 사라진 키. mustFull(5분·vin빈 60초) 경로는 손대지 않음.
        boolean useDelta = (serverCaps & 2) != 0 && !mustFull && !first && prevMap != null;
        if (useDelta) {
            JSONObject ds = new JSONObject(); JSONArray removed = new JSONArray();
            for (Map.Entry<String, String> e : curMap.entrySet()) if (!e.getValue().equals(prevMap.get(e.getKey()))) ds.put(e.getKey(), st.opt(e.getKey()));
            for (String k : prevMap.keySet()) if (!curMap.containsKey(k)) removed.put(k);
            for (Iterator<String> it = st.keys(); it.hasNext(); ) { String k = it.next(); if (k.startsWith("_") || "relay_ver".equals(k)) ds.put(k, st.opt(k)); }
            body.put("delta", true).put("state", ds); if (removed.length() > 0) body.put("removed", removed);
        } else {
            body.put("state", st);
        }
        lastStableSent = stable; lastFullPushMs = nowMs; lastStableMap = curMap;
        JSONObject resp = postJson("/relay/state", body);
        if (resp == null) { lastStableSent = null; lastStableMap = null; }   // ★615 전송 실패인데 '보냈음'으로 남아 최대 5분간 재전송 안 되던 것 — 다음 틱에 전체 재전송
        else if (resp.optBoolean("need_full", false)) { lastStableSent = null; lastStableMap = null; }   // ★839 서버에 저장본 없음/델타 꺼짐 → 다음 틱에 전체 재전송
        else {
            JSONArray fc = resp.optJSONArray("commands");
            try { recordFullPushDiag(body, idle, first, changed, prevMap, curMap, fc != null && fc.length() > 0, useDelta); }
            catch (Exception e) { Log.w(TAG, "전체 푸시 진단 기록 실패(전송과 무관)", e); }
        }
        return resp != null ? resp.optJSONArray("commands") : null;
    }

    private volatile String lastStableSent = null;   // ★559 마지막으로 전체 전송한 상태(변화 판정용)
    private volatile long lastFullPushMs = 0;
    private volatile Map<String, String> lastStableMap = null;   // ★701 진단 — 마지막 전체 전송의 키별 값(어떤 키가 바뀌었는지 세기 위함)

    // ★701 데이터 절감 사전 진단(관측 전용, 전송 동작 불변) — SnifferService.sampleDataUsage가 60초마다 비우며 모바일일 때만 일 누적.
    static final int DG_FI_CHG = 0, DG_FI_FORCED = 1, DG_FI_FIRST = 2, DG_FA_N = 3, DG_GZ_RAW = 4, DG_GZ_COMP = 5, DG_GZ_N = 6,
            DG_PI_N = 7, DG_PI_CMD = 8, DG_PA_N = 9, DG_PA_CMD = 10, DG_F_CMD = 11, DG_DELTA_N = 12, DG_COUNT = 13;   // ★839 DG_DELTA_N=델타 푸시 횟수
    static final java.util.concurrent.atomic.AtomicLongArray duDiag = new java.util.concurrent.atomic.AtomicLongArray(DG_COUNT);
    static final ConcurrentHashMap<String, Integer> duChg = new ConcurrentHashMap<>();   // 유휴 변경 푸시에서 바뀐 키별 횟수

    // ★712/706 생존 시각 2칸은 변화 판정에서 뺀다(운영자 10-03) — 수시로 바뀌어 상태 전체 재전송을 일으키던 것(51대 하루 약 1만 회). 값은 전체 전송마다 그대로 실린다.
    private static boolean slowKey(String k) { return "a11y_alive_s".equals(k) || "helper_alive_s".equals(k) || "sentry_trace".equals(k); }   // ★839 C: sentry_trace도 5분 그룹(유휴 전체푸시 1131/1247이 센트리 탓)
    // ★839 C: 비교 안정화 — sentry 값에서 계속 변하는 숫자(fps·drop·usedMb·events·lastEvent·ioDelay)를 빼고 state·why·fallback 등 진짜 상태만 남겨 비교. 표시용 최신값은 5분 전체 푸시 때 올라감.
    private static String stableVal(String k, Object v) {
        if (!"sentry".equals(k)) return String.valueOf(v);
        try {
            JSONObject o = new JSONObject(String.valueOf(v));
            for (String rm : new String[]{"fps", "drop", "usedMb", "events", "lastEvent", "ioDelay"}) o.remove(rm);
            java.util.List<String> ks = new java.util.ArrayList<>();
            for (Iterator<String> it = o.keys(); it.hasNext(); ) ks.add(it.next());
            java.util.Collections.sort(ks);
            StringBuilder sb = new StringBuilder();
            for (String kk : ks) sb.append(kk).append(':').append(o.opt(kk)).append(',');
            return sb.toString();
        } catch (Throwable t) { return String.valueOf(v); }
    }

    private Map<String, String> stableMapOf(JSONObject st) {
        Map<String, String> m = new java.util.HashMap<>();
        for (Iterator<String> it = st.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!k.startsWith("_") && !slowKey(k)) m.put(k, stableVal(k, st.opt(k)));
        }
        return m;
    }

    private void recordFullPushDiag(JSONObject body, boolean idle, boolean first, boolean changed,
                                    Map<String, String> prevMap, Map<String, String> curMap, boolean hasCmd, boolean delta) throws Exception {
        if (delta) duDiag.incrementAndGet(DG_DELTA_N);   // ★839 델타 푸시는 따로 센다(내일 DATAUSE 비교용 — 전체와 안 섞이게)
        if (idle) {
            duDiag.incrementAndGet(first ? DG_FI_FIRST : changed ? DG_FI_CHG : DG_FI_FORCED);
            if (changed && prevMap != null) {
                for (Map.Entry<String, String> e : curMap.entrySet()) {
                    if (!e.getValue().equals(prevMap.get(e.getKey()))) duChg.merge(e.getKey(), 1, Integer::sum);
                }
                for (String k : prevMap.keySet()) if (!curMap.containsKey(k)) duChg.merge(k, 1, Integer::sum);
            }
        } else duDiag.incrementAndGet(DG_FA_N);
        if (hasCmd) duDiag.incrementAndGet(DG_F_CMD);
        byte[] raw = body.toString().getBytes("UTF-8");
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(raw.length / 4 + 64);
        try (java.util.zip.GZIPOutputStream g = new java.util.zip.GZIPOutputStream(bo)) { g.write(raw); }
        duDiag.incrementAndGet(DG_GZ_N); duDiag.addAndGet(DG_GZ_RAW, raw.length); duDiag.addAndGet(DG_GZ_COMP, bo.size());
    }

    /** 워커의 stable 규칙(`_` 접두사 필드 제외)에 생존 시각 2칸(slowKey) 제외를 더함 + 키 정렬. 이 문자열이 같으면 "변화 없음". */
    private String stableOf(JSONObject st) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (java.util.Iterator<String> it = st.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!k.startsWith("_") && !slowKey(k)) keys.add(k);
        }
        java.util.Collections.sort(keys);
        StringBuilder sb = new StringBuilder(2048);
        for (String k : keys) sb.append(k).append('=').append(stableVal(k, st.opt(k))).append(';');
        return sb.toString();
    }

    // ★713/708 명령 결과 데이터 — execute()의 반환값은 오류 문구라, 값을 돌려줄 명령은 이 칸에 담고 null을 반환한다(같은 스레드).
    static final ThreadLocal<String> RESULT_DATA = new ThreadLocal<>();

    private void dispatch(JSONObject cmd) {
        String id = cmd.optString("id");
        String action = cmd.optString("action");
        JSONObject params = cmd.optJSONObject("params");
        // ★위험명령(SELFUPDATE·SH·RAW 등 operator 전용, shared에선 아래 게이트로 거부)은 K 검증 예외 — 서버(운영자 전역 sig)로 인증.
        //   K 페어링 후 operator 릴레이가 SELFUPDATE(K 서명 없는 서버 명령)를 거부하던 것 방지.
        // ★v150 SH/FEAT/DUMPDEV는 인자를 action 문자열에 담아 옴("SH ...", "FEAT ...") → equals가 아니라 startsWith로 예외 처리해야 함(K검증 우회).
        // ★2026-09-03 dangerous→systemDanger/vehicleControl 2분류. systemDanger=OS·파일·계정·카메라(공유판 완전거부, operator만 전역sig).
        //   제어성(RAW·CARSET·MIRRORFOLD·AUTOSTARTFIX·HUD·CLOUDFLASH·SETCLOUDSESS·CLEARCLOUDSESS·D5PROBE)은 이 목록서 빠져 → 아래 requireVerify(csig) 통과해야 실행 = 공유차주(K서명)는 되고 운영자(K없음)는 위조 불가.
        // ★599 CAMDETECT(카메라 움직임 감지) 공유판 개방(운영자 09-27) — systemDanger서 빼서 아래 requireVerify(K 명령서명)로. 결과는 움직임 %만 push,
        //   프레임 업로드 옵션(stream)은 SnifferService.camDetect가 공유판에서 강제 OFF.
        boolean systemDanger = action.equals("SELFUPDATE") || action.startsWith("SH ") || action.startsWith("FEAT ")
                || action.startsWith("DUMP") || action.startsWith("PROBEINT ") || action.startsWith("CONSTS ") || action.startsWith("READFEAT ")   // ★2026-09-10 신규 진단 2종 — 여기 빠져 있어 '서명 검증 실패'로 거부되던 것
                || action.equals("BLE_ADV") || action.equals("WAKE_MCU") || action.equals("ADB_AUTH")
                || action.equals("A11YDUMP") || action.equals("A11YCAP") || action.equals("A11YREBIND") || action.startsWith("INSTALLAPK ")
                || action.equals("ALLEVT")   // ★833 전체 이벤트 덤프(운영자판 돌핀 착좌 이벤트 탐색, 관찰 전용)
                || action.equals("ADEVDUMP")   // ★834 전 장치 getter 전수(관찰 전용)
                || action.equals("BELTMON")   // ★835 벨트 모니터(관찰 전용)
                || action.equals("MODEMON")   // ★860 주행모드 모니터(관찰 전용)
                || action.equals("CARPROBE") || action.equals("CAMPROBE") || action.equals("CAMOPEN") || action.equals("PULLFILE") || action.equals("CAMSHOT") || action.equals("CAMSTREAM") || action.equals("CAMH264") || action.equals("CAMH264LIVE") || action.equals("APPOPSPROBE") || action.startsWith("LOCALFLASH") || action.equals("LIGHTPROBE") || action.equals("BRAKEPROBE") || action.equals("RADARPROBE") || action.startsWith("SEATPROBE") || action.equals("IGNPROBE") || action.equals("FCSRPROBE") || action.equals("SCREENWAKE") || action.equals("GRANTPERMS") || action.equals("SETCLOUDCREDS") || action.equals("CARBLESCAN")
                || action.equals("SPLITPROBE")   // ★앱런처(LAUNCHAPP·SPLIT·APPLIST)는 2026-09-11 운영자 지시로 공유판 개방 → systemDanger서 빼서 K서명(requireVerify) 경로. SPLITPROBE(진단)만 op 전용.
                || action.equals("CAMDUALPROBE") || action.startsWith("BSDEXP")
                || action.equals("PULLPOWERAPK");   // ★611 com.ts.appservice.power APK 1회 확보(운영자 승인 09-27) — 인자 없음·패키지 고정, 아래 공유판 거부서 예외   // ★602 BSD실험(BSDEXP) — 운영자 개발용 진단·카메라. CAM 접두 아니라 명시 추가. 2026-09-27 운영자 승인.
        // ★v457/276 카메라(CAMRTC) — 운영자·공유 모두 차량별 K-grant 필수(strict). offer SDP가 동적이라 표준 verifyCmd(praw 서명)
        //   불가 → 전용 grant로 '릴레이 자기 vin'에 바인딩 검증(A차 grant를 B차에 재생 불가). 운영자 면제 제거 =
        //   공유 사용자가 전역 sig+vin만으로 운영자 카메라를 훔쳐보던 구멍 차단. 페어링된 앱(v457+)만 grant 생성 가능.
        //   SDP 자체는 서명 안 함 — grant 없인 relay가 answer를 안 만들므로 위조 SDP는 무의미.
        // ★CARBLESCAN — 헤드유닛 BT가 같은 차 워치키 모듈에 연결되는지 실차 프로브(읽기전용, async→직접 result post).
        if (action.equals("CARBLESCAN")) {
            final String mac = params != null ? params.optString("mac", "") : "";
            final boolean doScan = params != null && (params.optBoolean("scan", false) || mac.isEmpty());
            final String fUid = uid, fVin = vin, fId = id;
            CarBleProbe.Cb cb = (ok, detail) -> new Thread(() -> {
                try {   // ★결과 post는 반드시 백그라운드 스레드 — 콜백이 메인루퍼서 오면 NetworkOnMainThreadException로 유실됨
                    JSONObject r = new JSONObject();
                    r.put("uid", fUid).put("vin", fVin).put("sig", sig(fUid, fVin)).put("id", fId).put("ok", ok);
                    r.put("data", detail);   // ★워커는 data 필드만 저장 — detail을 data로
                    if (!ok) r.put("error", detail);
                    postJson("/relay/result", r);
                } catch (Exception ignore) {}
            }).start();
            if (doScan) CarBleProbe.scan(SnifferApp.getAppContext(), cb);
            else CarBleProbe.run(SnifferApp.getAppContext(), mac, cb);
            return;
        }
        if (action.equals("CAMRTC")) {
            if (!verifyCamGrant(params)) {
                Log.w(TAG, "CAMRTC grant 검증 실패 — 거부");
                try {
                    JSONObject r = new JSONObject();
                    r.put("uid", uid).put("vin", vin).put("sig", sig(uid, vin)).put("id", id).put("ok", false).put("error", "카메라 인증 실패(" + lastVerifyFail + ")");
                    postJson("/relay/result", r);
                } catch (Exception ignore) {}
                return;
            }
        }
        // ★공유판 보안 — operator 전용 위험명령은 shared에서 아예 거부(K 검증 우회 실행 구멍 차단).
        //   dangerous는 K검증을 건너뛰므로(requireVerify=false) 게이트 없으면 전역 sig만으로 RAW/SH/SELFUPDATE 실행 가능.
        //   shared가 정당히 쓰는 건 K검증 통과하는 일반 제어명령 + 자기 vin grant를 검증하는 CAMRTC뿐.
        //   예외: CAMRTC(자기 vin grant 검증됨) + MIRRORFOLD·AUTOSTARTFIX(로컬 a11y 자동화 — RCE 아님, 베타 정식기능).
        if (systemDanger && !BuildConfig.IS_OPERATOR && !action.equals("CAMPROBE") && !action.equals("PULLPOWERAPK")) {   // ★CAMPROBE=비파괴 진단(AVMCamera 존재/프리뷰 실증, 프레임 미전송·요약만) — 씨라7 카메라 경로 규명용 임시 허용, 확정 후 제거
            Log.w(TAG, "공유판 시스템명령 거부(OS·파일·계정·카메라): " + action);
            try {
                JSONObject r = new JSONObject();
                r.put("uid", uid).put("vin", vin).put("sig", sig(uid, vin)).put("id", id).put("ok", false).put("error", "미지원(공유판)");
                postJson("/relay/result", r);
            } catch (Exception ignore) {}
            return;
        }
        // 명령 서명 검증(워커 신뢰 안 함). 공유판=일반 제어명령 항상 필수. 운영자판=K 페어링됐을 때만(폴백).
        boolean requireVerify = !systemDanger && !action.equals("CAMRTC") && (!BuildConfig.IS_OPERATOR || pairKey != null);   // ★제어성(vehicleControl)은 systemDanger 아님 → 공유판·페어링운영자서 csig 필수. 운영자도 K없으면 위조 불가. CAMRTC는 verifyCamGrant(위) 별도.
        if (requireVerify) {
            if (!verifyCmd(cmd, action)) {
                Log.w(TAG, "명령 서명 검증 실패 — 거부: " + action);
                try {
                    JSONObject r = new JSONObject();
                    r.put("uid", uid).put("vin", vin).put("sig", sig(uid, vin)).put("id", id).put("ok", false).put("error", "서명 검증 실패(" + lastVerifyFail + ")");
                    postJson("/relay/result", r);
                } catch (Exception ignore) {}
                return;
            }
            // ★검증된 praw(서명 대상)로 params 대체 — 워커가 params 객체를 바꿔치기해도 서명된 원본만 실행.
            String praw = cmd.optString("praw", "");
            try { params = praw.isEmpty() ? null : new JSONObject(praw); } catch (Exception e) { params = null; }
        }
        Log.i(TAG, "명령 수신: " + action);
        String err;
        RESULT_DATA.remove();
        try { err = control.execute(action, params); }
        catch (Exception e) { err = String.valueOf(e.getMessage()); }
        String data = RESULT_DATA.get(); RESULT_DATA.remove();
        try {
            JSONObject r = new JSONObject();
            r.put("uid", uid).put("vin", vin).put("sig", sig(uid, vin)).put("id", id).put("ok", err == null);
            if (err != null) r.put("error", err);
            if (data != null) r.put("data", data);   // ★713/708 결과 데이터(DATAUSE·DATAMONTHS)는 오류 칸이 아니라 data로
            postJson("/relay/result", r);
        } catch (Exception e) { Log.w(TAG, "result", e); }
    }

    // ★v91 문 하나(4문+트렁크)의 엣지 처리 — 상태 갱신 + any_door_open 재계산 + 전이 시 door 이벤트 발사.
    private static final String[] DOOR_KEYS = {"door_driver", "door_rf", "door_lr", "door_rr", "door_trunk"};
    private void doorEdge(String key, int v01) {
        Object prev = state.get(key);
        // ★10-04 D3 문 유실 근본 수정 — state는 메모리라 릴레이 재시작 시 비고, D3는 타고 내릴 때쯤 재시작이 잦아 그 문 여닫음이
        //   '최초값'으로 버려졌다(DOORDIAG 5일 47대: 운전석 504건 중 309건=61% "이전 null → 전송 안 함", 열림 110·닫힘 199).
        //   D3만: ①직전 값이 없으면 저장해 둔 마지막 문 상태와 비교 ②그것도 없으면(첫 설치) 운전석 '열림'만 엣지로(씨라7 609와 같은 원리 —
        //   닫힘은 오래된 상태일 수 있어 합성 안 함). 씨라7은 1초 폴러가 시작 즉시 기준값을 깔고 609가 받쳐 이미 정상(97%)이라 건드리지 않는다.
        boolean d3 = !DiLink5Support.isDiLink5();
        if (d3 && !(prev instanceof Integer) && presetPrefs != null) {
            int sv = presetPrefs.getInt("door_last_" + key, -1);
            if (sv == 0 || sv == 1) prev = sv;
        }
        stateSet(key, v01);   // ★632 stateSet — 클러스터 문 위젯 실시간 푸시(state.put은 SSE 미발행)
        if (d3 && presetPrefs != null) { try { presetPrefs.edit().putInt("door_last_" + key, v01).apply(); } catch (Exception ignored) {} }
        int any = 0;
        for (String k : DOOR_KEYS) { Object v = state.get(k); if (v instanceof Integer && (Integer) v == 1) { any = 1; break; } }
        stateSet("any_door_open", any);
        if (!(prev instanceof Integer)) {   // 최초값
            if (d3 && "door_driver".equals(key) && v01 == 1) postDoorEvent(1);   // 재시작 직후 사람이 운전석 문을 연 것
            return;
        }
        if (((Integer) prev).intValue() == v01) return;   // 무변화 = 엣지 아님
        // ★v370 도어 이벤트 = 운전석만(door_open/door_closed → 폰 문트리거·탑승/하차 확증·도어게이트). 트렁크는 trunk_open/trunk_closed 별도(폰 카메라 감지 정지/재개용).
        //   조수석·뒷문은 이벤트 없음(상태·any_door_open·비콘 bit0 가드만). 2026-09-04 08:38 실측: 트렁크 여닫음이 door_open/closed로 나가 "문트리거→시동 켜기 시도" 2회 발화.
        if ("door_driver".equals(key)) postDoorEvent(v01);   // 폴링과 병합(이중발사 방지)
        else if ("door_trunk".equals(key)) { putStatus("trunk_change_ts", System.currentTimeMillis()); postCarEvent(v01 == 1 ? "trunk_open" : "trunk_closed"); }
    }

    // 폴링 경로용 공개 래퍼 — 이벤트 경로와 같은 doorEdge(상태+any_door_open+전이 push, postDoorEvent가 4s 병합) 재사용.
    public void doorEdgeFromPoll(String key, int v01) { doorEdge(key, v01); }

    // ★740 D3 시작 때 게터로 읽은 문 기준값 — door_driver가 비어 있으면 비콘 bit7(운전석 유효)이 빠져 폰이 bit0(아무 문)으로 대신 판단해
    //   조수석·뒷문 여닫음이 문트리거 시동을 켰다(10-04 14:39·23:32, "이전 운전석=null"). 이벤트가 먼저 왔으면 건드리지 않는다.
    //   닫힘은 알리지 않고(오래된 상태일 수 있음), 꺼져 있던 사이 운전석을 연 경우만 열림을 알린다(doorEdge 최초값과 같은 원리).
    public void doorInit(String key, int v01) {
        if (state.get(key) instanceof Integer) return;
        int sv = presetPrefs != null ? presetPrefs.getInt("door_last_" + key, -1) : -1;
        stateSet(key, v01);
        if (presetPrefs != null) { try { presetPrefs.edit().putInt("door_last_" + key, v01).apply(); } catch (Exception ignored) {} }
        if ("door_driver".equals(key) && v01 == 1 && sv != 1) postDoorEvent(1);
    }

    // 애프터블로우 재시도 검증용 — 구독(v89)으로 갱신되는 공조 워크모드(1=송풍ONLY/2=A/C). 없으면 null.
    public Integer acWorkMode() { Object v = state.get("ac_work_mode"); return (v instanceof Integer) ? (Integer) v : null; }

    // ★v147 냉방(A/C=2) 켜진 누적시간 — 하차 애프터블로우 "AC N분 이상" 조건용. drive_start에 리셋.
    private volatile long coolStartMs = 0;     // 현재 냉방 세션 시작(0=냉방off)
    private volatile long coolAccumMs = 0;     // 이번 주행 냉방 누적(ms)
    // 현재까지 냉방 누적 분(진행 중이면 지금까지 포함). state 푸시에 실림.
    public int coolMinutes() {
        long total = coolAccumMs + (coolStartMs > 0 ? System.currentTimeMillis() - coolStartMs : 0);
        return (int) (total / 60000L);
    }
    // 주행 시작 시 리셋(이번 주행의 냉방시간만 세도록). 냉방 중이면 시작점만 now로.
    public void resetCoolTimer() {
        coolAccumMs = 0;
        coolStartMs = (coolStartMs > 0) ? System.currentTimeMillis() : 0;
        saveCoolTimer();
    }

    // ★v97 상태 인코딩 비콘 — iBeacon minor에 차량 상태 비트필드를 실어 폰이 push 없이 즉시 인지.
    //   bit0=any_door_open bit1=drive_state(주행) bit2=lock(잠김) bit3=lock값 유효 bit4=ac_work_mode==1(송풍)
    //   bit15=상태비콘 시그니처(0x8000) — ESP32(고정 minor)와 구분. 폰은 이 비트 있을 때만 상태 비트 신뢰.
    //   bit12~14=부팅 epoch(628, 1~7 순환, 0=구버전) — 옛 프로세스 잔존 광고를 폰이 가려내는 표식(SnifferService.onCreate에서 설정).
    public static volatile int advEpoch = 0;
    public int beaconMinor() {
        int m = 0x8000;
        m |= (advEpoch & 7) << 12;
        Object d = state.get("any_door_open"); if (d instanceof Integer && (Integer) d == 1) m |= 1;
        Object dr = state.get("drive_state");  if (dr instanceof Integer && (Integer) dr == 1) m |= 2;
        Object lk = state.get("lock");
        if (lk instanceof Integer) { m |= 8; if ((Integer) lk == 1) m |= 4; }
        Object wm = state.get("ac_work_mode"); if (wm instanceof Integer && (Integer) wm == 1) m |= 16;
        Object gr = state.get("gear"); if (gr instanceof Integer && (Integer) gr == 1) m |= 32;   // ★bit5=후진(R) — 폰이 레인징 감지해 BLE 비상등(클라우드 6042 게이트 회피)
        m |= (trunkTrigSeq & 0xF) << 8;   // ★bit8~11=트렁크 트리거 seq(v369, 1~15 순환·0=없음) — 폰이 레인징으로 seq 변화 보고 즉시 BLE 트렁크 오픈(워커/APNs ~2.7s 우회)
        Object dd = state.get("door_driver"); if (dd instanceof Integer) { m |= 0x80; if ((Integer) dd == 1) m |= 0x40; }   // ★v370 bit6=운전석 문 열림, bit7=운전석비트 유효 — 폰 도어 엣지(문트리거·탑승/하차 확증)는 운전석만. bit0(any_door)은 '문 열려있으면 잠금 보류' 가드용 유지
        return m;
    }

    // ★v369 릴레이→폰 직접 채널 — kick:2 발송 순간 seq 증가 + 즉시 재광고(SnifferService.beaconWatchTick). 재시작 시 0으로 리셋(폰은 새 seq≠0·이전 읽기 신선일 때만 반응).
    private volatile int trunkTrigSeq = 0;
    public void bumpTrunkTrigger() { trunkTrigSeq = (trunkTrigSeq % 15) + 1; }

    // ★비콘 이벤트 채널(2026-09-11) — major 16비트 = 이벤트타입(상위 8) + seq(하위 8, 1~255 순환). 폰이 레인징으로 major seq 변화를 잡으면
    //   해당 타입에 반응(주로 '워커에서 상세 당겨오기'=LTE 없이도 근처 폰 즉시 반응). minor(상태 비트필드)와 독립·병행. iBeacon 리전은 UUID만이라 major 변경 무해.
    //   타입: 1=범용(폴), 2=도어/잠금, 3=공조, 4=카메라움직임/가림, 5=긴급. (트렁크 오픈은 minor bit8~11 seq 유지)
    private volatile int beaconEvtType = 0, beaconEvtSeq = 0;
    public int beaconMajor() { return ((beaconEvtType & 0xFF) << 8) | (beaconEvtSeq & 0xFF); }
    public void postBeaconEvent(int type) {
        beaconEvtType = type & 0xFF; beaconEvtSeq = (beaconEvtSeq % 255) + 1;
        SnifferService s = SnifferService.getInstance(); if (s != null) s.nudgeBeacon();   // 즉시 재광고(30s 워치독 대기 없이)
    }
    // postCarEvent(type 문자열) → 비콘 이벤트 타입 코드
    static int beaconTypeFor(String type) {
        if (type == null) return 1;
        if (type.startsWith("door") || type.startsWith("lock") || type.contains("lock")) return 2;
        if (type.contains("air") || type.contains("climate") || type.contains("afterblow")) return 3;
        if (type.startsWith("motion") || type.startsWith("kick") || type.contains("cover")) return 4;
        if (type.contains("stop") || type.contains("crash") || type.contains("alert")) return 5;
        return 1;
    }

    // ★v78 도어 이벤트 단일화 — 이벤트 리스너와 폴링 두 경로가 같은 여닫음을 각각 쏘던 이중발사를 여기서 병합.
    //   상태 기반 dedup: 같은 상태가 반대 전이 없이 반복되면(=이중소스 메아리) 무시. 진짜 open→close는
    //   상태가 바뀌므로(1↔0) 절대 병합 안 됨 → 도어 이벤트가 씹히는 일이 없다. 4초 창은 폴링 지연(≤3초) 흡수용.
    private volatile long lastDoorTs = 0;
    private volatile int lastDoorState = -1;
    public void postDoorEvent(int state) {
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (state == lastDoorState && now - lastDoorTs < 4000) return;   // 이중소스 메아리만 병합
            lastDoorState = state; lastDoorTs = now;
        }
        putStatus("door_driver", state);
        putStatus("door_change_ts", System.currentTimeMillis());
        synchronized (this) { ign().onDoorEvent(System.currentTimeMillis()); persistIgn(); }   // ★도어 여닫음 = 탑승 → 시동 꺼질 때 무탑승 마무리 안 함(판별기 락)
        postCarEvent(state == 1 ? "door_open" : "door_closed");
    }

    // 차량 이벤트(도어 여닫음·주행·ACC OFF) → /relay/event — 워커가 폰에 즉시 silent push(이탈 감시 웨이크, v30).
    //   ★v78 네트워크 두절 대비 재시도 — 도어 이벤트 순간 릴레이가 통신 끊겨도 복구될 때까지 재전송(~2분 창).
    //   실패해도 door_change_ts는 /relay/state 폴링으로 별도 동기돼(폰이 폴링복구) 이중 안전. evt_ts=실제 발생시각.
    public void postCarEvent(String type) {
        postBeaconEvent(beaconTypeFor(type));   // ★비콘 넛지 먼저 — LTE 없어도 근처 폰이 즉시 반응(워커 전송 실패해도 유효). trunk(kick:2)는 bumpTrunkTrigger가 별도 minor seq도 올림.
        if (!network || uid.isEmpty() || vin.isEmpty()) return;   // ★v106 운영자 외 워커 침묵
        lastActivityMs = System.currentTimeMillis();   // ★v147 도어 여닫음·주행·승차 = 활동 → 폴링 고속화(명령 반응성)
        final long evtTs = System.currentTimeMillis();
        new Thread(() -> {
            long[] backoff = {0, 2000, 5000, 10000, 20000, 30000, 60000};   // 총 ~2분
            for (int i = 0; i < backoff.length; i++) {
                if (backoff[i] > 0) { try { Thread.sleep(backoff[i]); } catch (InterruptedException e) { return; } }
                try {
                    JSONObject body = new JSONObject();
                    body.put("uid", uid).put("vin", vin).put("sig", sig(uid, vin)).put("type", type).put("evt_ts", evtTs);
                    if (postJson("/relay/event", body) != null) {
                        Log.i(TAG, "차량이벤트 전송: " + type + (i > 0 ? " (재시도 " + i + ")" : ""));
                        return;
                    }
                } catch (Exception e) { Log.w(TAG, "carEvent", e); }
            }
            Log.w(TAG, "차량이벤트 전송 최종실패(네트워크 지속두절) — state 폴링으로 복구 예정: " + type);
        }, "car-event").start();
    }

    // ── HTTP / HMAC ──
    // ★698 연결 재사용(모바일 데이터 절감) — 성공 시 disconnect하지 않고 본문을 끝까지 읽은 뒤 스트림만 닫아 풀에 반납(TLS 재핸드셰이크 제거).
    //   재사용 연결이 서버/Cloudflare 쪽에서 낡아 끊긴 경우(타임아웃 제외 IOException)만 새 연결로 1회 재시도, 재시도도 실패하면 기존대로 null.
    private JSONObject postJson(String path, JSONObject body) {
        // ★인증 강화(10-09) 차 키 증명 — 페어링 키 K가 있으면 /relay/* 요청에 kg=HMAC(K,"KG|vin|kgx")를 붙인다(서버가 이 차의 진짜 릴레이·폰인지 확인해 묶기를 믿을 수 있게 올림). 지금 서버는 관찰만 한다.
        if (path.startsWith("/relay/") && pairKey != null && body != null && !body.has("kg")) {
            try {
                String kv = body.optString("vin", "");
                if (!kv.isEmpty()) {
                    long kx = System.currentTimeMillis() + 3600_000L;
                    Mac km = Mac.getInstance("HmacSHA256");
                    km.init(new SecretKeySpec(pairKey.getBytes("UTF-8"), "HmacSHA256"));
                    byte[] kh = km.doFinal(("KG|" + kv + "|" + kx).getBytes("UTF-8"));
                    StringBuilder kb = new StringBuilder();
                    for (byte b : kh) kb.append(String.format("%02x", b));
                    body.put("kgx", kx).put("kg", kb.toString());
                }
            } catch (Throwable ignoredKg) {}
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = null;
            boolean done = false;
            try {
                URL url = new URL(BASE + path);
                c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(8000); c.setReadTimeout(30000);
                if ("/relay/poll".equals(path) && body.has("wait")) { lpAborted = false; lpConn = c; }
                c.setDoOutput(true);
                c.setRequestProperty("content-type", "application/json");
                c.setRequestProperty("User-Agent", "LL");   // ★842 머리말 줄이기 — 안드로이드 기본 User-Agent(약 70자)가 폴링마다 실렸다
                byte[] out = body.toString().getBytes("UTF-8");
                // ★839 데이터절감 A — 서버가 gzip 허용(c&1)하면 큰 POST(/relay/poll 제외) 본문을 gzip으로 압축해 보냄. 깨진 gzip이면 서버가 400(bad gzip).
                if ((serverCaps & 1) != 0 && "/relay/state".equals(path) && out.length > 500) {
                    java.io.ByteArrayOutputStream gzbo = new java.io.ByteArrayOutputStream();
                    try (java.util.zip.GZIPOutputStream gos = new java.util.zip.GZIPOutputStream(gzbo)) { gos.write(out); }
                    out = gzbo.toByteArray();
                    c.setRequestProperty("Content-Encoding", "gzip");
                }
                try (OutputStream os = c.getOutputStream()) { os.write(out); }
                int code = c.getResponseCode();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream()) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                }
                done = true;
                if ("/relay/poll".equals(path)) { duPollN.incrementAndGet(); duPollB.addAndGet(out.length + bo.size()); duPollH.addAndGet(hdrBytes(c, out.length)); }
                else { duPostN.incrementAndGet(); duPostB.addAndGet(out.length + bo.size()); }
                JSONObject res = new JSONObject(bo.toString("UTF-8"));
                if ("/relay/poll".equals(path) || "/relay/state".equals(path)) serverCaps = res.optInt("c", 0);   // ★839 c는 poll·state 응답에만 붙음(event·result엔 없어 0으로 꺼지던 것 방지)
                // ★615 네트워크 공백 관측(동작 변경 없음) — 자루씨(165328) "문 닫으면 오프라인": 지하 음영(살아있음·전송만 실패)과 사망을 가르기 위해
                //   1분 넘게 워커 전송이 끊겼다가 다시 붙으면 공백 길이를 net_gap("초s@복구시각")으로 남긴다. 사망 후 재시작이면 lastPostOkMs가 0이라 안 찍힌다.
                long okNow = System.currentTimeMillis();
                long okEl = android.os.SystemClock.elapsedRealtime(), okUp = android.os.SystemClock.uptimeMillis();
                if (lastPostOkMs > 0 && okNow - lastPostOkMs > 60_000L) {
                    // ★726 운영자 10-04 "상시생존에 실패하는 이유가 도대체 왤까" — 끊김이 '헤드유닛이 잠들어서'인지 '깨어 있는데 인터넷·서버가 안 닿아서'인지 가른다.
                    //   잠든 시간 = (잠 포함 시계 elapsedRealtime 증가) − (잠 제외 시계 uptimeMillis 증가). 깨어 있던 동안의 전송 실패 횟수·마지막 사유를 같이 남긴다.
                    long slept = lastOkEl > 0 ? Math.max(0, ((okEl - lastOkEl) - (okUp - lastOkUp)) / 1000) : -1;
                    long gapS = (okNow - lastPostOkMs) / 1000;
                    putStatus("net_gap", gapS + "s@" + okNow + " 잠" + slept + "s 실패" + netFailN + (netFailN > 0 ? "(" + netFailWhy + ")" : ""));
                    java.util.function.Consumer<String> sink = netGapLog;
                    if (sink != null && gapS > 120) {
                        String kind = slept >= 0 && slept >= gapS - 60 ? "헤드유닛이 잠든 동안" : (netFailN > 0 ? "깨어 있었는데 서버에 못 닿음" : "깨어 있었는데 전송 시도 없음");
                        try { sink.accept("서버 다시 연결 — 끊김 " + (gapS / 60) + "분 · 그동안 잠 " + (slept < 0 ? "?" : (slept / 60) + "분") + " · 전송 실패 " + netFailN + "회"
                                + (netFailN > 0 ? "(마지막: " + netFailWhy + ", 첫 실패 " + ((okNow - netFailFirstMs) / 60000) + "분 전)" : "") + " → " + kind); } catch (Throwable ignored) {}
                    }
                }
                lastPostOkMs = okNow; lastOkEl = okEl; lastOkUp = okUp;
                netFailN = 0; netFailWhy = ""; netFailFirstMs = 0;
                return res;
            } catch (java.net.SocketTimeoutException e) {
                Log.w(TAG, "postJson " + path, e); noteNetFail(e); return null;
            } catch (java.io.IOException e) {
                if (lpAborted && "/relay/poll".equals(path)) { lpAborted = false; return null; }   // ★롱폴을 일부러 끊음(주행·시동 변화) — 실패로 세지 않고 바로 새 상태로
                if (attempt == 0) { Log.w(TAG, "postJson " + path + " 연결 실패 → 새 연결로 1회 재시도", e); continue; }
                Log.w(TAG, "postJson " + path, e); noteNetFail(e); return null;
            } catch (Exception e) { Log.w(TAG, "postJson " + path, e); noteNetFail(e); return null; }
            finally { if (lpConn == c) lpConn = null; if (c != null && !done) c.disconnect(); }
        }
        return null;
    }
    // ★699 데이터 용도별 계측(관측 전용) — postJson 요청+응답 본문 바이트·횟수(폴링/그 외). SnifferService.sampleDataUsage가 60초마다 비우며 모바일일 때만 일 누적.
    static final java.util.concurrent.atomic.AtomicLong duPollN = new java.util.concurrent.atomic.AtomicLong(), duPollB = new java.util.concurrent.atomic.AtomicLong(),
            duPostN = new java.util.concurrent.atomic.AtomicLong(), duPostB = new java.util.concurrent.atomic.AtomicLong(),
            duPollH = new java.util.concurrent.atomic.AtomicLong();   // ★842 폴링 HTTP 헤더 바이트(씨라7 822와 같은 기준으로 전 판 계측)
    // ★822/842 HTTP/1.1 헤더 바이트(폴링) — 응답은 받은 헤더 그대로(안드로이드 내부용 X-Android-* 가짜 헤더는 실제로 오가지 않아 뺌), 요청은 HttpURLConnection이 붙이는 줄로 계산.
    //   실제 쓰인 프로토콜(X-Android-Selected-Protocol)을 처음 한 번 남긴다 — HTTP/2면 헤더가 압축돼 실제 바이트는 이보다 훨씬 작다.
    static volatile String duProto = "";
    private static long hdrBytes(HttpURLConnection c, int bodyLen) {
        try {   // 계측 실패가 폴링 실패(postJson null)로 번지지 않게
            long n = 2;
            for (java.util.Map.Entry<String, java.util.List<String>> e : c.getHeaderFields().entrySet()) {
                String k = e.getKey();
                if (k != null && k.startsWith("X-Android-")) { if ("X-Android-Selected-Protocol".equals(k) && !e.getValue().isEmpty()) duProto = String.valueOf(e.getValue().get(0)); continue; }
                for (String v : e.getValue()) n += (k == null ? 0 : k.length() + 2) + (v == null ? 0 : v.length()) + 2;
            }
            String ua = "LL";
            n += ("POST " + c.getURL().getFile() + " HTTP/1.1\r\nHost: " + c.getURL().getHost() + "\r\nUser-Agent: " + ua + "\r\nConnection: Keep-Alive\r\nAccept-Encoding: gzip\r\n"
                    + "content-type: application/json\r\nContent-Length: " + bodyLen + "\r\n\r\n").length();
            return n;
        } catch (Throwable t) { return 0; }
    }
    // ★726 끊김 진단 — 마지막 성공 때의 두 시계, 그 뒤 전송 실패 횟수·마지막 사유·첫 실패 시각. 기록은 SnifferService가 넣는 netGapLog로(NETGAP).
    private static volatile long lastOkEl = 0, lastOkUp = 0, netFailFirstMs = 0;
    /** ★736 마지막 서버 성공이 몇 ms 전인가(없으면 -1) — 심박과 함께 저장해 다음 시작 때 '살아 있었는데 못 닿았나'를 가린다. */
    static long lastOkAgoMs() { long el = lastOkEl; return el > 0 ? android.os.SystemClock.elapsedRealtime() - el : -1; }
    private static volatile int netFailN = 0;
    private static volatile String netFailWhy = "";
    // ★829 FB2157 — 끊김 복구(SnifferService.netFixTick)가 '전송을 시도했는데 실패 중'인지·언제부터인지 본다
    static long netFailSinceMs() { return netFailN > 0 ? netFailFirstMs : 0; }
    static String netFailReason() { return netFailWhy; }
    public static volatile java.util.function.Consumer<String> netGapLog;
    private static void noteNetFail(Exception e) {
        if (netFailN == 0) netFailFirstMs = System.currentTimeMillis();
        netFailN++;
        String m = e.getMessage();
        String why = (e instanceof java.net.UnknownHostException) ? "주소 못 찾음(인터넷 없음·DNS)"
                : (e instanceof java.net.SocketTimeoutException) ? "시간 초과"
                : (e instanceof java.net.ConnectException || e instanceof java.net.NoRouteToHostException) ? "연결 불가"
                : (e instanceof javax.net.ssl.SSLException) ? "보안 연결 실패"
                : e.getClass().getSimpleName();
        netFailWhy = why + (m == null ? "" : " " + (m.length() > 40 ? m.substring(0, 40) : m));
    }
    private static volatile long lastPostOkMs = 0;   // ★615 프로세스 단위(static) — RelayClient 재생성(restartRelay)에도 공백 추적 유지
    static volatile int serverCaps = 0;   // ★839 데이터절감 서버 기능 비트(매 응답의 c를 따름: 1=gzip 허용·2=델타 허용·0/없음=종전 전체·무압축). 롤백은 서버 gzip.off/delta.off

    private static String sig(String uid, String vin) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes("UTF-8"), "HmacSHA256"));
        byte[] h = mac.doFinal((uid + ":" + vin).getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ── 공유판 명령 서명 검증 (페어키 K, 재전송 방어) ──
    //   앱이 서명한 원문 = action|praw|nonce|ts. praw=앱이 만든 params 원본 문자열(재직렬화 불일치 방지).
    // ★BLE 명령 채널(4d18c0de) 서명 검증 공용화(2026-09-12 보안) — 워커 큐와 동일 verifyCmd(pairKey csig|nonce|ts|praw). 공유판 BLE는 이걸 필수화해 VIN만 아는 근거리 공격 차단.
    public boolean verifyBleCmd(JSONObject cmd, String action) { return verifyCmd(cmd, action); }
    private boolean verifyCmd(JSONObject cmd, String action) {
        String key = pairKey;
        if (key == null) { lastVerifyFail = "릴레이 미페어링"; return false; }   // 미페어링 → 전부 거부
        String csig = cmd.optString("csig", "");
        String nonce = cmd.optString("nonce", "");
        long ts = cmd.optLong("ts", 0L);
        String praw = cmd.optString("praw", "");
        if (csig.isEmpty() || nonce.isEmpty() || ts == 0L) { lastVerifyFail = "서명 없음(폰에 키 없음)"; return false; }
        long age = System.currentTimeMillis() - ts;
        if (age > 300000L || age < -120000L) { lastVerifyFail = "시간 어긋남 " + (age / 1000) + "초(헤드유닛·폰 시계 확인)"; return false; }   // ts 신선도: 과거 5분 / 미래 2분(시계오차)
        String msg = action + "|" + praw + "|" + nonce + "|" + ts;
        String expect;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
            byte[] h = mac.doFinal(msg.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            expect = sb.toString();
        } catch (Exception e) { lastVerifyFail = "검증 오류 " + e.getClass().getSimpleName(); return false; }
        if (!constantTimeEq(expect, csig)) { lastVerifyFail = "키 불일치(릴레이 지문 " + keyFingerprint(key) + ") — 폰에서 릴레이와 다시 동기화 필요"; return false; }
        if (!usedNonces.add(nonce)) { lastVerifyFail = "재전송(같은 nonce)"; return false; }   // 서명 확인 후에만 nonce 소비(중복=재전송 거부)
        if (usedNonces.size() > 4096) usedNonces.clear();    // 무한증가 방지(오래 도는 헤드유닛 대비 상한)
        return true;
    }
    private static boolean constantTimeEq(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    // ★센트리 접속 grant 검증(공유판 /sentry/*) — sg=HMAC(pairKey, "SENTRY|"+vin+"|"+sgx). 규칙은 sentry/SentryAuth(순수 로직, JUnit)
    public String selfSentryGrantQuery() { return com.cseini.bydsniffer.sentry.SentryAuth.selfGrantQuery(pairKey, vin, System.currentTimeMillis()); }
    public boolean verifySentryGrant(String sgx, String sg) {
        String key = pairKey;
        if (key == null) { lastVerifyFail = "릴레이 미페어링"; return false; }
        boolean ok = com.cseini.bydsniffer.sentry.SentryAuth.verify(key, vin, sgx, sg, System.currentTimeMillis());
        if (!ok) lastVerifyFail = "센트리 권한 값 검증 실패";
        return ok;
    }

    // 마지막 명령 수신·차 활동 시각(헬퍼 재설치를 명령이 오가는 중엔 미루는 판단용, 783)
    public long lastActivityMs() { return lastActivityMs; }

    // ★779 센트리 집에서 재생 — 차가 만든 연결 응답(answer)에 페어키 서명(폰이 확인 → 서버가 응답을 바꿔치는 중간자 차단). 미페어링이면 null
    public String signWithPairKey(String msg) {
        String key = pairKey;
        if (key == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
            byte[] h = mac.doFinal(msg.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "페어키 서명 실패", e);
            return null;
        }
    }

    // ★v457 카메라 grant 검증 — cg=HMAC(pairKey, "CAMRTC|"+vin+"|"+cgx). vin=릴레이 자기 vin(다른 차 grant 재생 방지).
    //   cgx=만료 epoch(ms). now<cgx≤now+~1h. HD 재연결이 같은 grant를 재사용하도록 nonce 없이 시간창 방식(K 없인 위조 불가).
    private boolean verifyCamGrant(JSONObject params) {
        String key = pairKey;
        if (key == null || params == null) { lastVerifyFail = key == null ? "릴레이 미페어링" : "grant 없음"; return false; }
        String cg = params.optString("cg", "");
        long cgx = params.optLong("cgx", 0L);
        if (cg.isEmpty() || cgx == 0L) { lastVerifyFail = "grant 없음(폰에 키 없음)"; return false; }
        long now = System.currentTimeMillis();
        if (cgx <= now || cgx - now > 3700000L) { lastVerifyFail = "grant 시간 어긋남 " + ((cgx - now) / 1000) + "초(시계 확인)"; return false; }   // 만료 or 과도한 미래(최대 ~1h)
        String msg = "CAMRTC|" + vin + "|" + cgx;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
            byte[] h = mac.doFinal(msg.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            boolean ok = constantTimeEq(sb.toString(), cg);
            if (!ok) lastVerifyFail = "키 불일치(릴레이 지문 " + keyFingerprint(key) + ") — 폰에서 릴레이와 다시 동기화 필요";
            return ok;
        } catch (Exception e) { lastVerifyFail = "검증 오류 " + e.getClass().getSimpleName(); return false; }
    }

    private static int parseLock(String v) { return v.contains("1") ? 1 : (v.contains("2") ? 1 : 0); }
    private static int parseInt(String v) { try { return (int) Double.parseDouble(v.replaceAll("[^0-9.\\-]", "")); } catch (Exception e) { return 0; } }
}
