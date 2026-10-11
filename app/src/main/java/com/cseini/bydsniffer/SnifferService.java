package com.cseini.bydsniffer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.bydauto.BYDAutoEventValue;
import android.hardware.bydauto.adas.AbsBYDAutoADASListener;
import android.hardware.bydauto.energy.AbsBYDAutoEnergyListener;
import android.hardware.bydauto.gearbox.AbsBYDAutoGearboxListener;
import android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener;
import android.hardware.bydauto.light.AbsBYDAutoLightListener;
import android.hardware.bydauto.panorama.AbsBYDAutoPanoramaListener;
import android.hardware.bydauto.radar.AbsBYDAutoRadarListener;
import android.hardware.bydauto.setting.AbsBYDAutoSettingListener;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.cseini.bydsniffer.sentry.SentryHost;
import com.cseini.bydsniffer.sentry.SentryManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SnifferService extends Service {
    private static final String TAG = "SnifferService";
    private static final String CHANNEL_ID = "bydsniffer_channel";
    private static final int NOTIFICATION_ID = 1;

    private static SnifferService instance;
    private List<LogEntry> logs = new ArrayList<>();
    private File csvFile;
    private FileWriter csvWriter;
    private Handler mainHandler;
    private Map<Integer, String> idToName = new HashMap<>();
    private List<Object> devices = new ArrayList<>();
    private Object adasDevice, settingDevice, instrumentDevice;   // getter 폴링용
    private Object safetybeltDevice, doorlockDevice, chargingDevice;   // 전용 도메인 폴링용
    private Object bodyworkDevice;   // 창문/선쉐이드 명령용 (온디맨드 인스턴스화)
    private volatile int lastSunshadeNudgePct = -1;      // ★선쉐이드 중복 nudge 디바운스 — 앱 직접(doStartClimate) + 릴레이 부트온 프리셋이 같은 %를 이중 발사하던 것 방지
    private volatile long lastSunshadeNudgeTs = 0;
    private android.os.PowerManager.WakeLock wakeLock;   // 시동 OFF(Doze) 중에도 CPU 유지 → 릴레이 24h 온라인(일렉트로 방식)
    private volatile boolean pollRunning = true;

    // ── 헤드유닛 상시생존: ACC OFF 후에도 wakeUpMcu()로 헤드유닛을 깨워 유지(Electro wakeUpAfterAccOff 방식) ──
    // 배터리 보호: 마지막 차량활동 후 MAX_KEEPALIVE_MS까지만 유지, 초과하면 절전 허용(Electro의 "timed out").
    private static final String ACTION_WAKE = "com.cseini.bydsniffer.WAKE";
    private static final long WAKE_INTERVAL_MS = 90_000L;          // 유지 중: 90초마다 wakeUpMcu
    private static final long WAKE_RECHECK_MS = 900_000L;          // 절전 중: 15분마다 재점검
    // 배터리 보호: 12V 저전압 경고 플래그(POWER_LOW_VOLTAGE=0/1)가 지속되면 절전. 그 전엔 무제한 유지
    // (충전 중이면 12V 건강→플래그 0→계속 온라인. 12h+ 주차도 배터리 버티는 한 유지). 시간은 백스톱일 뿐.
    private volatile long maxKeepAliveMs = 16L * 60 * 60 * 1000;   // 백스톱 16시간(무활동 시 하드 안전한계, RAW 조정)
    private volatile long keepAliveUntil = 0;                       // 이 시각까지만 깨워둠(활동마다 갱신)
    private volatile int lowVoltFlag = 0;                           // POWER_LOW_VOLTAGE (0=정상, 1=저전압경고)
    private volatile long lowSinceMs = 0;                           // 저전압 플래그가 1로 지속된 시작시각
    private volatile long lowSustainMs = 10L * 60 * 1000;          // 저전압 이만큼 지속돼야 절전(순간노이즈 무시)
    private volatile String kaStateLast = "", wakeResLast = "", prevKa = "";     // ★614 상시생존 관측 변화 감지용
    private volatile boolean wakeEnabled = true;                   // 상시생존 on/off (헤드유닛 앱에서 설정)
    private static final long WAKE_LOOP_MS = 2_000L;               // ★연속 루프: 2초마다 wakeUpMcu(Electro wakeUpAfterAccOff 방식)
    private volatile boolean wakeLoopRunning = false;
    private volatile boolean wakeMcuDiagDone = false;   // wakeUpMcu 첫 결과 1회 보고용(v33 진단)
    private volatile boolean sentryDiagDone = false;    // ★D5 sentry wake(BYDAutoSpecialDevice) 첫 결과 1회 보고용(2026-09-15 Braveheart 이식)
    private volatile long wakeOkCount = 0, wakeFailCount = 0, wakeReportTick = 0;   // wakeUpMcu 성공/실패 추이(원격 시동끔 후 생존 진단)
    private android.app.PendingIntent wakePI;
    // ★v44 사인(死因) 진단 — 심박 prefs·수명이벤트·ApplicationExitInfo로 프로세스가 언제/왜 죽었는지 추적
    private long lastHbCommit = 0, lastWatchdogArm = 0;
    private long prevHb = 0; private String prevLife = null;   // ★v45: 이전 프로세스 사인 스냅샷(wake 루프가 덮어쓰기 전 캡처)
    private long prevOk = 0; private String prevNet = "";       // ★736 이전 프로세스 마지막 심박 때의 서버 성공 시각·망 상태

    // BSD 경보
    private android.media.ToneGenerator toneGen;
    private int lastBsdValue = 1;              // 1=안전(clear), 2=차량감지
    private volatile boolean turnSignalActive = false;
    private volatile long lastTurnSignalOnTs = 0;   // ★깜빡이 마지막 ON 시각 — 차로유지 알림 억제 최근성 판정.
    private long lastBeepMs = 0;
    private android.content.SharedPreferences prefs;
    public static volatile boolean bsdDetected = false;   // UI 표시용
    private BsdListener bsdListener;

    public interface BsdListener { void onBsdChanged(int value); }
    public static volatile int lastBsdStateForUi = 1;
    public void setBsdListener(BsdListener l) { this.bsdListener = l; }

    public static final String PREFS = "bsd_alert";
    public static final String KEY_ALERT = "alert_enabled";       // 경보음 on
    public static final String KEY_TURN_ONLY = "turn_only";       // 깜빡이 켰을 때만
    public static final String KEY_SOUND = "bsd_sound";           // 경보음 톤 인덱스(0~3, 앱 '알림음' 선택과 1:1)
    public static final String KEY_BSD_VOL = "bsd_vol";           // 경보음 음량 0~100(앱 '주행 알림 음량' 통합 슬라이더)
    // ★2026-09-16 운영자 지적 — 헤드유닛마다 실제로 미디어를 뚫고 나오는 오디오 채널이 다를 수 있어(차종/펌웨어별 오디오 라우팅 차이),
    //   하나로 고정하지 않고 톤 선택처럼 채널도 앱에서 골라 실차로 테스트하게 노출. index 0~3, ToneGenerator는 legacy stream,
    //   SoundPool/덕킹 포커스는 AudioAttributes usage — 같은 인덱스로 짝지어 하나의 "채널"처럼 다룬다.
    public static final String KEY_BSD_CHANNEL = "bsd_channel";   // BSD 경보음 채널 인덱스(0~3)
    public static final String KEY_ICC_CHANNEL = "icc_channel";   // ICC/차선 알림음 채널 인덱스(0~3) — LANE도 동일 풀 공유
    private static final int[] CH_STREAM = {
        android.media.AudioManager.STREAM_ALARM, android.media.AudioManager.STREAM_NOTIFICATION,
        android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.STREAM_SYSTEM };
    private static final int[] CH_USAGE = {
        android.media.AudioAttributes.USAGE_ALARM, android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT,
        android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION };
    private static final String[] CH_LABEL = { "경보(Alarm)", "알림(Notification)", "내비게이션", "시스템" };
    private int chIdx(String key) { int i = (prefs != null) ? prefs.getInt(key, 0) : 0; return (i < 0 || i >= CH_STREAM.length) ? 0 : i; }
    public static final String KEY_DIAG = "diagnostic_mode";      // 전체 신호 로깅(크래시 위험)
    public static final String KEY_RELAY = "relay_enabled";       // 오토도어 릴레이 on
    public static final String KEY_UID = "relay_uid";             // BYD userId
    public static final String KEY_VIN = "relay_vin";             // 차량 VIN
    // ★565 원터치 자동연결 — QR을 띄운 순간부터 이 시각까지만 /pair가 devId+K를 내준다(사설망 + 시간 창 이중 게이트).
    //   차 화면에서 QR을 띄우는 행위 자체가 "지금 이 차를 연결하겠다"는 사용자 의사 표시라, 별도 버튼을 두지 않는다.
    static volatile long pairOpenUntil = 0;
    static void openPairWindow() { pairOpenUntil = System.currentTimeMillis() + 60_000; SnifferService s = instance; if (s != null) s.pairAdvStart(); }   // ★577 창 열 때 LAN(mDNS) 광고도 시작 — 워커 없이 갓설치 릴레이도 폰이 발견
    static boolean pairWindowOpen() { return System.currentTimeMillis() < pairOpenUntil; }
    // ★577 LAN 상호 동기화 광고 — 페어링 창(60초) 동안만 `_linklock-pair._tcp` 서비스로 릴레이를 광고.
    //   폰이 같은 WiFi에서 mDNS 브라우징으로 발견 → POST /lan-pair. 갓설치 릴레이(워커가 VIN 모름)도 발견 가능.
    private android.net.nsd.NsdManager nsd; private android.net.nsd.NsdManager.RegistrationListener nsdReg;
    private void pairAdvStart() {
        try {
            int port = (remoteWeb != null) ? remoteWeb.getListeningPort() : 0;
            if (port <= 0) { postToD1("RELAY", "LAN 광고 스킵 — 웹서버 미기동"); return; }
            String dev = readDevId();
            pairAdvStop();
            android.net.nsd.NsdServiceInfo si = new android.net.nsd.NsdServiceInfo();
            si.setServiceName("linklock-" + (dev.length() > 6 ? dev.substring(dev.length() - 6) : dev));
            si.setServiceType("_linklock-pair._tcp.");
            si.setPort(port);
            try { si.setAttribute("dev", dev); } catch (Throwable ignored) {}
            nsd = (android.net.nsd.NsdManager) getSystemService(NSD_SERVICE);
            nsdReg = new android.net.nsd.NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(android.net.nsd.NsdServiceInfo s) { postToD1("RELAY", "LAN 동기화 광고 시작(" + s.getServiceName() + ":" + port + ", 60초)"); }
                @Override public void onRegistrationFailed(android.net.nsd.NsdServiceInfo s, int c) { postToD1("RELAY", "LAN 광고 등록 실패 code=" + c); }
                @Override public void onServiceUnregistered(android.net.nsd.NsdServiceInfo s) {}
                @Override public void onUnregistrationFailed(android.net.nsd.NsdServiceInfo s, int c) {}
            };
            nsd.registerService(si, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, nsdReg);
            if (mainHandler != null) mainHandler.postDelayed(this::pairAdvStop, 61_000);   // 창 만료 후 광고 정지
        } catch (Throwable t) { postToD1("RELAY", "LAN 광고 예외: " + t.getClass().getSimpleName()); }
    }
    private void pairAdvStop() {
        try { if (nsd != null && nsdReg != null) nsd.unregisterService(nsdReg); } catch (Throwable ignored) {}
        nsdReg = null;
    }
    public static final String KEY_PAIR = "relay_pair_key";       // ★차량별 페어키 K(명령 서명, 헤드유닛↔앱 1회 공유)
    public static final String KEY_DEVID = "relay_dev_id";        // ★부트스트랩 신원(persist.sys.cloud.last_vin) — 앱 등록 UI 표시·SETVIN 주소지정
    public static final String KEY_WAKE_ENABLED = "wake_enabled";        // 상시생존(wakeUpMcu) on
    public static final String KEY_WAKE_MAXHOUR = "wake_max_hour";       // 백스톱 시간(무활동 하드한계)
    public static final String KEY_WAKE_SUSTAINMIN = "wake_sustain_min"; // 저전압 지속 분(절전 판정)
    // ★오토스타트금지 해제 대상(사용자 선택, CSV 패키지명). 릴레이=유일 source of truth → a11y/헬퍼엔 payload로 전달.
    public static final String KEY_AUTOSTART_TARGETS = "autostart_targets";
    public static final String DEFAULT_AUTOSTART_TARGETS = "com.cseini.bydsniffer";   // ★디폴트=릴레이(돌핀락)만. 오토트립 등 나머지는 사용자가 대상선택에서 추가.

    // 현재 설정된 오토스타트 해제 대상 CSV(미설정 시 기본값). 트리거 extra·헬퍼 broadcast payload로 실어 보냄.
    // ★돌핀락 내부 컴포넌트(릴레이+헬퍼)는 항상 강제 포함 — 오토스타트금지 상태면 DiLink가 force-stop해 a11y가 자동재시작 안 되고(재설치로만 복구),
    //   특히 헬퍼가 이 상태로 방치되면 시동 껐다 켤 때마다 a11y가 죽고 안 살아났음. 언블록돼야 프로세스 킬돼도 a11y 자동 재바인딩.
    public static String autostartTargetsCsv(android.content.Context ctx) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        set.add("com.cseini.bydsniffer"); set.add(HELPER_PKG);   // 필수(사용자가 빼도 유지)
        try {
            String s = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_AUTOSTART_TARGETS, DEFAULT_AUTOSTART_TARGETS);
            if (s != null) for (String p : s.split(",")) { p = p.trim(); if (!p.isEmpty()) set.add(p); }
        } catch (Throwable ignored) {}
        return android.text.TextUtils.join(",", set);
    }

    private RelayClient relay;                                     // 오토도어 릴레이 클라이언트
    private TripCollector tripCollector;                          // ★전비/진단 수집기(씨라이언7 흡수)
    private volatile int lastDriving = -1;                         // ★주행상태 캐시(gear poll이 갱신, 1=주행/0=정차) — 이탈 자동화용
    private int drivingRawPrev = -2, drivingConfirm = 0;           // ★드라이브 디바운스(2026-09-17) — 클러스터 fast-poll(600ms) 중 주차상태 gear 한틱 오독(D) 플랩 억제용
    private volatile double lastSpeed = Double.NaN;                // ★게이트웨이 속도 캐시 — ICC 알림 정차 제외용(오토홀드 D정차도 속도0이라 gear로는 못 거름)
    private volatile boolean speedLive = false;                    // ★v144 속도센서 살아있음 확정(한 번이라도 >3 관측). false면 게터 죽은 것 → ICC 가드 fail-open(알림 우선)
    private volatile Thread afterblowThread;                       // ★v87 애프터블로우 종료 타이머(항상 최신 1개만)
    private volatile Thread afterblowCompThread;                   // ★v102 컴프레서OFF 재시도 스레드(재실행 시 이전 것 취소 — 누적 방지)
    private volatile Thread afterblowSeqThread;                    // ★v330 AFTERBLOWSEQ 실행 스레드 — 재접근 취소 시 interrupt용
    private volatile int lastPowerLvl = Integer.MIN_VALUE;             // ★680 bodywork.getPowerLevel(2=ON/0=OFF) 캐시 — 카메라 점유 앱 종료 안전 판정용
    private volatile int lastGearVal = Integer.MIN_VALUE;          // ★v87 기어 캐시(3=P) — 애프터블로우 주행 가드
    // ★v572 "주차 시 DRL 끄기"로 **우리가** 끈 상태인지 — 주행복귀·토글해제 때 이 경우에만 1로 복원한다
    //   (사용자가 차 설정에서 직접 끈 DRL을 멋대로 켜지 않도록 구분). 릴레이 재시작 시 false=안전측.
    private volatile boolean drlWeTurnedOff = false;
    private volatile int lastBrakeVal = Integer.MIN_VALUE;         // ★v113 브레이크 페달 캐시(값 인코딩 학습용)
    private volatile long lastBrakeStartTs = 0;                    // ★v113 brake_start 쿨다운
    // ★v115 ICC(ACC) 오프 효과음 — subai 방식(ADAS_ACC_MODE=852492340) 감지 + 미디어 안 끊는 재생(포커스 미요청)
    private android.media.SoundPool iccSoundPool;
    // ★v116 R.raw 상수 직접 참조 — 릴리스 리소스 난독화로 getIdentifier(이름)이 0 반환하던 것 수정.
    private static final int[] ICC_SOUND_RES = {R.raw.se_ding, R.raw.se_blip, R.raw.se_marimba, R.raw.se_off,
            R.raw.se_down, R.raw.se_warn, R.raw.se_low, R.raw.se_up, R.raw.se_bell, R.raw.se_chime,
            R.raw.se_lead_m, R.raw.se_lead_f, R.raw.se_traffic_m, R.raw.se_traffic_f,
            R.raw.se_bsd_0, R.raw.se_bsd_1, R.raw.se_bsd_2, R.raw.se_bsd_3};   // ★629 14~17 BSD 비프(ToneGenerator→SoundPool 이전, BSD_SOUND_BASE)   // 0~3 기존, 4~6 해제느낌, 7~9 설정느낌, ★10~13 전방알림 TTS(앞차 男/女, 신호 男/女) — 배열 뒤 append(중간삽입 금지: 0~9는 prefs 저장)
    private final int[] iccSoundIds = new int[ICC_SOUND_RES.length];
    private final long[] iccSoundMs = new long[ICC_SOUND_RES.length];   // ★629 음원 길이 — 덕킹 시간을 소리 길이에 맞춤(음성 ~2s가 1.5s 덕킹보다 길어 끝이 묻혔다)
    private static final int BSD_SOUND_BASE = 14;
    private volatile int lastAccValue = Integer.MIN_VALUE;
    private volatile boolean accActive = false;

    // ★864 센트리 가동 요약(운영자 10-10, 계약서 7.31) — 시동을 켜면 지난 주차 동안 녹화가 1건 이상일 때만 알림음 → 음성 "주차 중 센트리 녹화 열두 건이 있습니다" + 같은 문구 화면 표시.
    //   켜고 끄기는 폰 앱 센트리 설정(summary_announce, 기본 켜짐). 요약은 SentryManager.takePendingSummary()가 한 번만 준다(시동 켠 4초 뒤 생기고 릴레이 재시작에도 남음).
    private void announceSentrySummary() {
        try {
            if (!sentryEnabled() || !SentryManager.get().summaryAnnounce()) return;
            org.json.JSONObject s = SentryManager.get().takePendingSummary();
            int count = s == null ? 0 : s.optInt("count", 0);
            if (count <= 0) return;
            String msg = com.cseini.bydsniffer.sentry.SentryVoice.line(count);
            playIccSound(9, 0.8f);   // se_chime
            android.widget.Toast.makeText(getApplicationContext(), msg, android.widget.Toast.LENGTH_LONG).show();
            java.util.List<String> ids = com.cseini.bydsniffer.sentry.SentryVoice.clipIds(count);
            mainHandler.postDelayed(() -> playSentryVoice(ids), iccSoundMs[9] > 0 ? iccSoundMs[9] + 200 : 1200);
            postToD1("SENTRY", "가동 요약 안내 — " + msg + " · 조각 " + ids);
        } catch (Throwable t) { postToD1("SENTRY", "가동 요약 안내 실패 " + t.getClass().getSimpleName()); }
    }
    // 음성 조각(assets/sentry_voice/<id>.wav, 16kHz·모노·16bit, 44바이트 헤더)을 PCM으로 이어 붙여 AudioTrack 한 번으로 재생 — 조각마다 따로 틀면 사이가 끊긴다.
    //   효과음과 같은 채널(사용자가 고른 알림음 채널)·같은 덕킹 규칙을 따른다.
    private void playSentryVoice(java.util.List<String> ids) {
        new Thread(() -> {
            android.media.AudioTrack t = null;
            try {
                java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (String id : ids) {
                    java.io.ByteArrayOutputStream one = new java.io.ByteArrayOutputStream();
                    try (java.io.InputStream in = getAssets().open("sentry_voice/" + id + ".wav")) { int n; while ((n = in.read(buf)) > 0) one.write(buf, 0, n); }
                    byte[] w = one.toByteArray();
                    if (w.length > 44) pcm.write(w, 44, w.length - 44);
                }
                byte[] b = pcm.toByteArray();
                if (b.length == 0) return;
                long ms = b.length / 32L;   // 16000Hz × 2바이트
                int usage = CH_USAGE[chIdx(KEY_ICC_CHANNEL)];
                duckMediaBriefly(usage, ms + 400);
                t = new android.media.AudioTrack.Builder()
                        .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(usage).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new android.media.AudioFormat.Builder().setSampleRate(16000).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(b.length).build();
                t.write(b, 0, b.length);
                t.play();
                Thread.sleep(ms + 300);
            } catch (Throwable e) { postToD1("SENTRY", "가동 요약 음성 실패 " + e.getClass().getSimpleName() + ":" + e.getMessage()); }
            finally { if (t != null) try { t.release(); } catch (Throwable ignore) {} }
        }, "sentry-voice").start();
    }
    public String sentrySelfGrant() { RelayClient rc = relay; return rc != null ? rc.selfSentryGrantQuery() : null; }   // ★녹화 보기 화면이 공유판에서도 열리게
    public boolean sentryEnabled() { try { return com.cseini.bydsniffer.sentry.SentryManager.get().enabled(); } catch (Throwable t) { return false; } }   // ★메인 화면 센트리 칸(켠 차만)
    public static SnifferService getInstance() {
        return instance;
    }
    // ★정적 D1 로그(가상 디스플레이 세션 등 인스턴스 밖에서 호출)
    static void d1static(String cat, String msg) { SnifferService i = instance; if (i != null) i.postToD1(cat, msg); }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String act = intent.getAction();
            if ("com.cseini.bydsniffer.UPLOAD".equals(act)) uploadAllCsv();
            else if (ACTION_WAKE.equals(act)) runWakeHeartbeat();
            // ★v44: 누가 재기동시켰는지 기록 (boot=부팅리시버 / job=잡스케줄러 워치독)
            String src = intent.getStringExtra("src");
            if (src != null) postToD1("FORENSIC", "재기동 요청 src=" + src);
        }
        if (!lifeLogged) { lifeLogged = true; logServiceLife(intent == null ? "null(시스템 재시작)" : String.valueOf(intent.getStringExtra("src"))); }
        return START_STICKY;
    }

    // ★662 서비스 재초기화 진단(2026-09-30 운영자 "릴레이 서비스가 하루 52번 새로 만들어진다" 원인 조사) — 서비스가 새로 만들어질 때마다 한 줄:
    //   새 프로세스인지(같은 pid면 같은 프로세스 안에서 서비스만 재생성), 프로세스 가동 시간, 이전 서비스가 만들어진 지 얼마인지, 깨운 주체(src),
    //   새 프로세스면 시스템이 기록한 직전 종료 사유(ApplicationExitInfo). 어느 경로가 죽이고 누가 되살리는지 며칠이면 가려진다.
    private boolean lifeLogged = false;
    private final long serviceCreatedMs = System.currentTimeMillis();   // ★663 서비스 인스턴스 생성 시각(재기동 직후 헬퍼 재설치 보류 판정용)
    private void logServiceLife(String src) {
        try {
            int pid = android.os.Process.myPid();
            long now = System.currentTimeMillis();
            long procUp = Build.VERSION.SDK_INT >= 24 ? (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 1000 : -1;
            int lastPid = prefs != null ? prefs.getInt("life_pid", 0) : 0;
            long lastMs = prefs != null ? prefs.getLong("life_ms", 0) : 0;
            StringBuilder exit = new StringBuilder();
            if (pid != lastPid && Build.VERSION.SDK_INT >= 30) {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                java.util.List<android.app.ApplicationExitInfo> l = am != null ? am.getHistoricalProcessExitReasons(getPackageName(), 0, 2) : null;
                if (l != null) for (android.app.ApplicationExitInfo e : l) {
                    String d = e.getDescription(); if (d != null && d.length() > 40) d = d.substring(0, 40);
                    exit.append(" / 종료기록 ").append(e.getProcessName()).append(" 사유=").append(e.getReason()).append(" ").append((now - e.getTimestamp()) / 1000).append("초전 ").append(d == null ? "" : d);
                }
            }
            postToD1("LIFE", "새 서비스 pid=" + pid + (pid == lastPid ? "(같은 프로세스 재생성)" : "(새 프로세스, 이전 pid=" + lastPid + ")")
 + " OS가동=" + (android.os.SystemClock.elapsedRealtime() / 1000) + "s 프로세스가동=" + procUp + "s 이전서비스=" + (lastMs == 0 ? "?" : ((now - lastMs) / 1000) + "s전") + " src=" + src + exit);
            if (prefs != null) prefs.edit().putInt("life_pid", pid).putLong("life_ms", now).apply();
        } catch (Throwable t) { postToD1("LIFE", "진단 예외 " + t.getClass().getSimpleName()); }
    }

    // 수집된 모든 byd-sniff-*.csv 를 워커로 전송 (크래시 재시작으로 파일이 여러 개일 수 있음)
    private void uploadAllCsv() {
        toast("전송 시작...");
        new Thread(() -> {
            try {
                if (csvWriter != null) {
                    synchronized (csvWriter) {
                        try { csvWriter.flush(); } catch (IOException ignored) {}
                    }
                }
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                File[] files = dir.listFiles((d, name) -> name.startsWith("byd-sniff-") && name.endsWith(".csv"));
                if (files == null || files.length == 0) { toast("업로드할 CSV 없음"); return; }
                int ok = 0, fail = 0; long rows = 0; int lastCode = 0; String lastErr = "";
                for (File f : files) {
                    try {
                        byte[] data = readFile(f);
                        rows += countLines(data);
                        int code = postCsv(data);
                        lastCode = code;
                        if (code >= 200 && code < 300) ok++; else fail++;
                    } catch (Exception e) {
                        fail++; lastErr = String.valueOf(e.getMessage());
                    }
                }
                toast("전송: " + ok + "개 성공(" + rows + "행), 실패 " + fail
                        + (fail > 0 ? " [code=" + lastCode + " " + lastErr + "]" : ""));
            } catch (Exception e) {
                Log.e(TAG, "uploadAllCsv failed", e);
                toast("전송 오류: " + e.getMessage());
            }
        }).start();
    }

    private int postCsv(byte[] data) throws Exception {
        java.net.URL url = new java.net.URL(UploadConfig.URL + "?tag=signal");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("X-Auth", UploadConfig.SECRET);
        conn.setRequestProperty("Content-Type", "text/csv");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        try (java.io.OutputStream os = conn.getOutputStream()) { os.write(data); }
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private byte[] readFile(File f) throws IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, r;
            while (off < buf.length && (r = fis.read(buf, off, buf.length - off)) > 0) off += r;
            return buf;
        }
    }

    private long countLines(byte[] data) {
        long n = 0;
        for (byte b : data) if (b == '\n') n++;
        return n;
    }

    private void toast(String msg) {
        if (mainHandler != null) {
            mainHandler.post(() -> android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show());
        }
    }

    @Override
    public void onCreate() {
        try {
            instance = this;
            super.onCreate();
            com.overdrive.app.camera.dilink5.FastCamTcpBackend.killGate = () -> lastGearVal == 3 && lastDriving != 1 && lastPowerLvl == 0;   // ★680 비틀 데몬 종료 안전 조건: 시동 꺼짐+P+주행 아님
            Updater.remoteLog = this::postToD1;   // ★552 무음 설치 실패 사유를 A1으로(앱 버튼 경로는 여태 기록이 없었다)
            mainHandler = new Handler(Looper.getMainLooper());
            prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            // ★628 비콘 부팅 epoch(FB1098) — 대기 강제종료(USER_REQUESTED) 뒤 옛 프로세스의 iBeacon 광고가 남아(079312 id984·087704 FB1098)
            //   옛 상태(주차 bit1=0)와 새 상태(주행 bit1=1)가 번갈아 송출 → 폰이 주차확정 오판, 주행 중 문열림. 프로세스가 켜질 때마다
            //   1~7 순환 번호를 minor bit12~14에 실어(RelayClient.beaconMinor) 폰이 가장 최신 번호의 비콘만 믿게 한다. 이전 값과 항상 다르다.
            RelayClient.advEpoch = (prefs.getInt("adv_epoch", 0) % 7) + 1;
            prefs.edit().putInt("adv_epoch", RelayClient.advEpoch).apply();
            // ★v45: 사인 스냅샷은 wake 루프가 심박을 덮어쓰기 전 여기서 동기 캡처(v44 "이전심박 0초" 레이스 수정)
            prevHb = prefs.getLong("forensic_hb", 0);
            prevOk = prefs.getLong("forensic_ok", 0); prevNet = prefs.getString("forensic_net", "");   // ★736
            prevKa = prefs.getString("ka_last", "");   // ★614 wake 루프가 덮어쓰기 전 이전 프로세스의 마지막 상시생존 상태
            prevLife = prefs.getString("forensic_life", null);
            try {
                toneGen = new android.media.ToneGenerator(CH_STREAM[chIdx(KEY_BSD_CHANNEL)], prefs.getInt(KEY_BSD_VOL, 100));
            } catch (Exception e) {
                Log.w(TAG, "ToneGenerator init failed", e);
            }
            createNotificationChannel();
            startForeground();
            RelayAnchorService.start(this);   // ★v214 :relay 앵커 기동(a11y 프로세스 warm 유지)
            startRelayAnchorLoop();            // ★v214 60초마다 재기동(딥슬립/업데이트로 얼면 깨움)
            acquireWakeLock();
            requestBatteryOptExemption();
            applyWakeGuardPrefs();
            if (wakeEnabled) { startWakeLoop(); scheduleWakeAlarm(WAKE_INTERVAL_MS); }
            scheduleJobRestart();
            scheduleWatchdogJob();   // ★v44: 원샷 워치독(≤3분 내 부활) — 15분 주기잡과 별개
            reportForensics();       // ★v44: 이전 프로세스 사인(심박 나이·수명이벤트·ExitInfo) 보고
            restoreNear();                             // ★624 재시작 전 근접 상태·주행 래치 복원
            RelayClient.autoGate = this::autoAllowed;   // ★620 시동 프리셋 게이트(킬스위치·주 사용권 폰 근접)
            startD3DoorProbe();      // ★641 돌핀 문 직접 읽기 탐색(기록만)
            initD3DriverDoor();      // ★740 돌핀 운전석 문 기준값(비콘 bit7 유효 — 조수석·뒷문 문트리거 오발 방지)
            startD5Poller();         // ★606 D5 문 폴러를 시작 즉시 — 60초 HB 루프에서 켜던 탓에 시동OFF 직후 재기동(com.ts.appservice.power 강제종료) 뒤 최대 60초 문 장님(하차 문열림 유실)
            selfEnableAccessibility();   // ★v46: 접근성 셀프 활성화 — 원격 공조끔 force-stop 면제 노림
            checkVersionAutostartFix();  // ★v253: 업데이트로 versionCode 바뀌면 오토스타트 자동해제(설치 시 ROM 재금지 자기치유)
            registerHelperAckReceiver(); // ★헬퍼 생존 ack 수신(helperAliveTs 갱신 + mirror_fold 파일 기록)
            tripCollector = new TripCollector(this); tripCollector.start();   // ★전비/진단 수집(relay는 publishTrip로 lazy 접근 → 앱 대시보드)
            resumeDetectIfPending();   // ★재시작 시 진행 중이던 카메라 감지(트렁크 등) 자동 재개
            startRemoteWeb();          // ★웹 세컨드스크린(op 전용) — 핫스팟/동일 WiFi 브라우저용 로컬 HTTP
            registerAppListWatcher();  // ★헤드유닛 앱 설치/삭제/교체 → 런처 목록·아이콘 자동 재발행
            // ★부팅 시 차량 API 권한 재부여(2026-09-11) — 앱 업데이트로 매니페스트에 새 BYDAUTO 권한이 추가돼도(예: 471의 AC_COMMON/GET)
            //   기존 차는 WRITE_SECURE가 이미 부여돼 A11Y 자동부여 경로를 건너뛰어 새 권한이 안 붙던 버그. adbkey 있으면(=이전 ADB 인증됨) 팝업 없이 재부여.
            try {
                java.io.File ak = new java.io.File(getFilesDir(), "adbkey");
                if (ak.exists()) new Thread(() -> { try { Thread.sleep(5000); postToD1("A11Y", "부팅 권한 재부여 → " + AdbAccess.ensureVehicleApiAccess(this)); } catch (Throwable ig) {} }, "boot-grant").start();
            } catch (Throwable ignored) {}
            new Thread(() -> { try { Thread.sleep(20000); } catch (InterruptedException ignored) {} postToD1("SPLIT", SplitLauncher.probe(this)); }, "splitprobe").start();
            loadSplitPrefs(); startSplitRatioKeeper();   // ★642 분할 비율 기억·유지   // ★부팅 1회 분할 지원 프로브(씨라이언7 판정용, 읽기 전용)
            if (huOverlay == null) huOverlay = new HuAssistOverlay(this, prefs, clusterCam, m -> postToD1("HUASSIST", m));   // ★728 헤드유닛 카메라 창 ★733 원본 직접 그리기
            if (huOverlay2 == null) huOverlay2 = new HuAssistOverlay(this, prefs, clusterCam, m -> postToD1("HUASSIST", "[오른쪽 창] " + m));   // ★748 후진 D 오른쪽 따로 창
            if (BuildConfig.IS_OPERATOR && !DiLink5Support.isDiLink5()) {   // ★788 씨라7은 헬퍼 관리 끔(오토스타트 해제가 필요 없는 차종)
                // ★648 릴레이 재시작마다 헬퍼를 무조건 재설치하던 것 중단(운영자 09-29 — 일주일 130회, 멀쩡한 헬퍼까지 죽여 ack 끊김→1분마다 재설치 악순환).
                //   최신 버전이 설치돼 있으면 건드리지 않고 45초 유예 — 헬퍼가 살아 있으면 4초 심박 ack가 들어오고, 안 오면 maybeManageHelper가 재연결→재설치 순으로 복구.
                lastHelperReinstall = System.currentTimeMillis();
                int inst = -1;
                try { inst = getPackageManager().getPackageInfo(HELPER_PKG, 0).versionCode; } catch (Throwable ignored) {}
                if (inst < HELPER_ASSET_VER) {
                    // ★788 업데이트 직후엔 릴레이 접근성이 오토스타트 해제를 하는 중이라 헬퍼 설치를 겹치지 않는다 — 보류가 끝나면 maybeManageHelper가 이어서 설치·업그레이드.
                    if (System.currentTimeMillis() < helperHoldUntil) postToD1("HELPER", "릴레이 시작 — 헬퍼 v" + inst + " → v" + HELPER_ASSET_VER + " 설치는 오토스타트 해제 뒤로 미룸");
                    else { registerHelperA11y(); installHelperFromAsset(); }
                }
                else postToD1("HELPER", "릴레이 시작 — 헬퍼 v" + inst + " 최신, 재설치 생략(심박 대기)");
            }
            initCSVFile();
            buildFeatureIdMap();
            // ★DiLink5(씨라이언7) — bydauto 클래스가 부트클래스패스에 없고 OEM APK에 번들됨. initDevices의 익명
            //   리스너(new AbsBYDAuto…Listener{})가 컴파일타임 참조라 클래스 미주입 시 NoClassDefFoundError로
            //   onCreate 전체를 abort → 이하(startGearPoll의 iBeacon 송출·명령채널)가 안 뜨던 근본 수정.
            //   ensure()로 dex를 먼저 주입(돌핀=이미 로드가능 → 즉시 no-op, 무회귀). 주입 실패해도 initDevices를
            //   격리해 비콘·생존 경로는 계속 살린다(씨라이언7 최소 릴레이 = 상시생존 + iBeacon 깨우기).
            try { DiLink5Support.ensure(this); } catch (Throwable ignored) {}
            try { initDevices(); } catch (Throwable t) { postToD1("RELAY", "initDevices 실패(비콘·생존은 계속 진행): " + t); }
            initIccAudio();   // ★v115 ICC 오프 효과음 SoundPool 준비
            bsdExp.enabled = prefs != null && prefs.getBoolean("bsdexp_enabled", true);   // ★602 BSD실험 활성 상태
            // ★센트리 1단계(DiLink3 전용, 공유판 포함 — 기본 꺼짐이라 켠 차만 동작) — startRelay 앞이어야 릴레이가 보내는 초기 시동 이벤트가 안 버려진다. init 실패가 onCreate를 끊지 않게 격리.
            if (!DiLink5Support.isDiLink5()) {
                try { SentryManager.get().init(sentryHost()); } catch (Throwable t) { postToD1("SENTRY", "init 실패: " + t); }
            }
            startRelay();
            if (relay != null && DiLink5Support.isDiLink5() && prefs != null) relay.putStatus("d5cam_mode", prefs.getString("d5cam_mode", "legacy"));   // ★712 씨라7 카메라 방식(앱 토글 표시용) ★722 기본 legacy(공유판 721 실차 2대 4분할 2칸 까맣게 — 운영자 "722로 기본 기존")
            if (IS_D5) { loadD5Slots(); publishClusterCamCfg(); }   // ★745 씨라7 보조 칸 배치 불러오기 + 앱에 켬·칸 알림
            if (relay != null && btAudioCutOn()) relay.putStatus("bt_audio_off", 1);   // ★715 오디오 끊기 켠 차 표시 ★810 운영자판은 기본 켬
            try { bsdLrConstCheck(); } catch (Throwable ignored) {}   // ★715 BSD 좌우 관측 — LCA 상수 유무
            // ★v30: 헤드유닛 전원 종료(ACC OFF 계열) → 폰에 즉시 이벤트 push(이탈 감시 웨이크). 네트워크 죽기 전 best-effort.
            // ★v33: 일렉트로 "다음 차량 종료 시 적용" 힌트 — 종료 시퀀스 진행 중 wakeUpMcu 연타(200ms×30초)로 전원다운 저지 시도.
            registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, Intent i) {
                    markLife("ACTION_SHUTDOWN");   // ★v44: 진짜 전원다운이면 이 마커가 남음 (원격끔=프로세스킬과 구분)
                    if (relay != null) relay.postCarEvent("accoff");
                    postToD1("WAKE", "ACTION_SHUTDOWN 수신 → wakeUpMcu 버스트 시작(전원유지 시도)");
                    if (bleAdvWanted) { bleAdvActive = false; beaconBoostUntil = System.currentTimeMillis() + 180_000; startBeaconAdvertise(); }   // ★v96 시동종료 → 즉시 재확립 + 3분 고속감시
                    new Thread(() -> {
                        Object p = deviceByName("power");
                        // ★v333 injectPowerContext 추가 — 이 버스트만 빠뜨려(wake루프·ACC_OFF버스트는 부름) 종료 순간
                        //   wakeUpMcu가 mContext NPE로 조용히 실패 → 전원 못 잡음 → 헤드유닛 완전절전(콜드 74s 부활)이던 것 수정.
                        //   컨텍스트 주입 성공 시 wakeUpMcu가 전원을 잡아 하드웨어 유지 → 2~6s 웜부활로 일관.
                        int ok = 0, fail = 0;
                        for (int n = 0; n < 150 && p != null; n++) {
                            try { injectPowerContext(p); p.getClass().getMethod("wakeUpMcu").invoke(p); ok++; } catch (Throwable ignored) { fail++; }
                            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                        }
                        postToD1("WAKE", "wakeUpMcu 버스트 30초 완주(성공 " + ok + "/실패 " + fail + ") — 전원유지 시도 끝");
                    }, "accoff-burst").start();
                }
            }, new android.content.IntentFilter(Intent.ACTION_SHUTDOWN));
            // ★v51: BYD 차량 브로드캐스트 진단 리시버 — 원격 공조끔(CLOSEAIR) 후 실제로 어떤 브로드캐스트가
            //   오는지 D1에 로깅해 즉사 정체 규명(overdrive는 com.byd.action.ACC_OFF/ACC_ON/IGN_ON 등을 받아 상시생존 제어).
            //   ACC_OFF 계열이면 wakeUpMcu 버스트로 전원유지 시도(대응 훅). 동적 등록이라 매니페스트 수정 불필요.
            try {
                android.content.IntentFilter carbc = new android.content.IntentFilter();
                for (String a : new String[]{
                        "com.byd.action.ACC_ON", "com.byd.action.ACC_OFF", "com.byd.action.IGN_ON",
                        "com.byd.accmode.ACC_MODE_CHANGED",
                        Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF,
                        Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED }) {
                    carbc.addAction(a);
                }
                registerReceiver(new android.content.BroadcastReceiver() {
                    @Override public void onReceive(android.content.Context c, Intent i) {
                        String action = (i != null) ? i.getAction() : null;
                        if (action == null) return;
                        postToD1("CARBC", "브로드캐스트 수신: " + action);
                        // ★화면 상태 플래그(2026-09-13) — 블투복구 svc 게이트용. DiLink5는 PowerManager.isInteractive()가 SCREEN_OFF 후에도 stale true라 신뢰 불가 → 브로드캐스트로 직접 추적.
                        if (Intent.ACTION_SCREEN_OFF.equals(action)) screenIsOff = true;
                        else if (Intent.ACTION_SCREEN_ON.equals(action)) screenIsOff = false;
                        // ACC_OFF/IGN_OFF 계열 = 원격끔 후보 → markLife(죽기 직전 마커) + wakeUpMcu 버스트로 전원유지 시도
                        if (action.contains("ACC_OFF")) {
                            markLife("CARBC:ACC_OFF");
                            postToD1("CARBC", "★ACC_OFF — 원격끔 정체 추적 + wakeUpMcu 버스트");
                            new Thread(() -> {
                                Object p = deviceByName("power");
                                for (int n = 0; n < 150 && p != null; n++) {
                                    try { injectPowerContext(p); p.getClass().getMethod("wakeUpMcu").invoke(p); } catch (Throwable ignored) {}
                                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                                }
                                postToD1("CARBC", "ACC_OFF 후 wakeUpMcu 버스트 30초 완주 — 아직 살아있음");
                            }, "accoff-carbc-burst").start();
                        }
                    }
                }, carbc);
                postToD1("CARBC", "v51 차량 브로드캐스트 진단 리시버 등록 완료");
            } catch (Throwable t) { Log.w(TAG, "CARBC 리시버 등록 실패", t); }
            // WRITE_SECURE_SETTINGS를 DiLink ROM이 자동 부여하는지 확인(일렉트로는 adb 없이 설치만으로 생존 — ROM 관대 가능성)
            try {
                boolean wss = checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                postToD1("WAKE", "WRITE_SECURE_SETTINGS " + (wss ? "granted — ROM 자동부여!" : "denied(순정 동작 — adb 필요)"));
            } catch (Throwable ignored) {}
            // 부팅 시 자동 설치창은 제거 — 실행 시 알림/설치는 MainActivity(토스트+버튼)에서만.
            // ⚠️ 무거운 진단(probeV9 3974덤프·forceQueryAll·폴링루프 다수)이 ANR(먹통) 유발 → 전부 제거.
            // 핵심 = initDevices 리스너 → onVehicleEvent → updateFromEvent(앞좌석 벨트/occupant) → relay push. 폴링 불필요.
            // BSD 좌/우 방향 + 뒷좌석 벨트는 주행중 이벤트로 안 옴 → get(int[])로만 읽힘. 가벼운 단일 스레드로 계측.
            startGearPoll();      // ★v78: 주행 시작/종료 폴링(EPB·기어·속도) → drive_start/drive_end 이벤트(폰 CoreMotion 대체 신호)
            probeLocalAdb();      // ★v53: 로컬 adbd(127.0.0.1) 접근·인증 진단 — app_process 워치독 실현가능성 판단
            dadbShellProbe();     // ★v55: dadb로 로컬 adbd RSA 인증 → shell "id" (app_process 다음 단계)
        } catch (Exception e) {
            Log.e(TAG, "onCreate failed", e);
            SnifferApp.reportCrash(Thread.currentThread(), e, Thread.getDefaultUncaughtExceptionHandler());
            throw e;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "BYD Sniffer", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void startForeground() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sniffer_notification))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    // ★v214 :relay 앵커 주기 기동 — 메인은 항상 살아있으므로, 60초마다 :relay 앵커를 시작해
    //   딥슬립/자기업데이트로 얼어붙은 :relay 프로세스를 깨워 a11y가 재바인드되게 한다.
    private int anchorTick = 0;   // ★v221 앵커 기동 계측 카운터
    // ★v234 릴레이 a11y 셀프활성 대상(가디언 수법 이식) — 재설치/딥슬립 후 시스템 자연 재바인딩(≈14분)을 안 기다리고 직접 재활성.
    private static final String A11Y_COMPONENT_SELF = "com.cseini.bydsniffer/com.cseini.bydsniffer.KeepAliveAccessibilityService";
    // WRITE_SECURE_SETTINGS로 enabled_accessibility_services에 릴레이 a11y를 유지 → AMS가 60초 내 재바인드. 재설치 후 미러 자동화 14분 공백 제거.
    // ★애프터블로우 종료(시동OFF) 후 미러 자동폴딩 ON 복원 — 앵커루프(60초)가 조건 충족 시 1회 실행.
    //   시동ON 중 복원하면 미러 펴짐(4조합 확정)이라, 반드시 시동OFF + a11y생존일 때만.
    private void checkMirrorRestore() {
        try {
            if (prefs == null || !prefs.getBoolean("mirror_restore_pending", false)) return;
            long after = prefs.getLong("mirror_restore_after", 0);
            if (System.currentTimeMillis() < after) return;   // 그레이스 전 → 대기(OPENAIR 시동ON 전이 중 복원=미러 펴짐 방지)
            // ★절대 만료 — v372: 사용자 제안(릴레이가 다음에 살아났을 때 복원)이라 만료를 24h로 연장. 릴레이가 애프터블로우 후 죽어도
            //   다음 기상(접근·재시동) 때 복원되게 owed 유지. fold==1 확정 시 위에서 해제되므로 유저 수동OFF 덮어쓰기는 그 경로가 방지.
            if (System.currentTimeMillis() - after > 24 * 3600000L) {
                prefs.edit().putBoolean("mirror_restore_pending", false).apply();
                postToD1("RELAY", "미러 복원 예약 만료(24h 경과) → 포기(잔존 pending 방지)");
                return;
            }
            if (readMirrorFoldFile() == 1) {   // 이미 복원됨(재시도/다른경로) → 예약 해제
                prefs.edit().putBoolean("mirror_restore_pending", false).apply();
                postToD1("RELAY", "미러 자동폴딩 ON 복원 확정(fold=1) → 예약 해제");
                return;
            }
            int ps = (relay != null) ? relay.carPowerState() : -1;
            // ★v372 사용자 제안 — 애프터블로우 송풍이 끝난 뒤엔 시동ON(재탑승)이어도 복원(자동폴딩 ON=미러 펴짐, 재탑승이라 오히려 맞음).
            //   송풍 진행 중(afterblow_end 전)에 시동ON일 때만 대기 = 송풍 20분 중 미러 오펴짐 방지. 릴레이가 죽었다 살아나도
            //   afterblow_end가 이미 지나 있으니 다음 기상(부팅→앵커루프)에서 ps 무관 복원. 구: ps!=0이면 무조건 return(재탑승서 영영 미복원 원인).
            long abEnd = prefs.getLong("afterblow_end", 0);
            boolean blowing = System.currentTimeMillis() < abEnd;
            if (ps != 0 && blowing) return;   // 송풍 중 시동ON만 대기. 송풍 종료 후엔 시동 상태 무관 복원 진행.
            // ★v335 헬퍼 준비 대기 — 미러토글은 헬퍼 broadcast로만 확정됨(릴레이 a11y 파일경로는 발사돼도 미확정, 2026-08-28 08:28 실측:
            //   afterblow_done 직후 헬퍼 바인딩 중이라 릴레이a11y로 발사→미확정→운전으로 ps=1되며 70분 OFF). 헬퍼 신선(ack<8s)일 때만
            //   헬퍼로 발사. 준비 안 됐으면 재바인드 트리거+이번 사이클 대기(다음 60초 폴에서 준비되면 발사). 단 폴백(복원가능 후 2분 넘게 못 켬
            //   =헬퍼 영구 broken 가능)이면 릴레이a11y라도 병행 발사(무한대기·구 40분 심박유실 버그 방지).
            long helperAge = helperAliveAgeMs();   // -1 = ack 없음
            boolean helperReady = (helperAge >= 0 && helperAge < 8000);
            boolean waitedLong = (System.currentTimeMillis() - after) > 120000;   // after=복원가능 시점(그레이스 후)
            if (!helperReady && !waitedLong) {
                maybeManageHelper();   // 헬퍼 깨우기/재바인드(멱등·throttle 내장)
                postToD1("RELAY", "미러 복원 — 헬퍼 준비 대기(helper " + (helperAge < 0 ? "ack없음" : helperAge / 1000 + "s") + ", ps=0) → 준비되면 시도(폴백 2분)");
                return;
            }
            if (isHelperInstalled()) sendHelperCmd(this, "mirrorfold", "1");   // 헬퍼 broadcast(확정 경로)
            if (!helperReady) dispatchA11y("mirrorfold", "1");   // 폴백(헬퍼 미준비 2분 초과)일 때만 릴레이a11y 병행 — 헬퍼 준비되면 broadcast 단독
            postToD1("RELAY", "미러 자동폴딩 ON 복원 요청 (helper " + (helperAge < 0 ? "ack없음" : helperAge / 1000 + "s") + (helperReady ? " 준비" : " 폴백") + ", ps=" + ps + " 송풍" + (System.currentTimeMillis() < prefs.getLong("afterblow_end", 0) ? "중" : "종료") + ", fold=1 확정까지 재시도)");
        } catch (Throwable t) { postToD1("RELAY", "checkMirrorRestore 예외: " + t.getClass().getSimpleName() + " " + t.getMessage()); }
    }

    private volatile long lastAutoGrant = 0;   // ★WRITE_SECURE 자동 self-grant throttle(5분)
    // ★788 접근성 A안(운영자 10-06 승인) — 릴레이 접근성은 늘 켜 두되 되살리기 동작(투명 화면 띄우기·목록에서 뺐다 다시 넣기·전체 끔→켬·:relay 강제 종료)은 모두 없앤다.
    //   그 동작은 재시작·업데이트 직후 옛 프로세스의 심박 파일을 보고 '좀비'로 오판해 돌았고, 화면을 깜빡이게 했으며(투명 화면은 꺼진 화면도 켰다),
    //   막 시작한 오토스타트 해제를 끊었다(10-06 08:24 운영자 차 785: 해제 발사 1초 뒤 전체 끔→켬). 목록을 통째로 다시 쓰는 사이 다른 앱이 넣은 항목도 지워질 수 있었다.
    //   이제는 목록에 우리 항목이 없을 때만(강제 종료되면 시스템이 목록에서 뺀다) 지금 목록 뒤에 한 번 덧붙인다. 바인드는 시스템과 :relay 앵커(60초)에 맡긴다.
    private void selfEnableA11y() {
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // ★자동 self-grant(2026-08-31) — 무선 ADB 열린 헤드유닛이면 릴레이가 스스로 WRITE_SECURE 부여. 5분 스로틀, dadb라 별도 스레드.
            if (System.currentTimeMillis() - lastAutoGrant > 300000) {
                lastAutoGrant = System.currentTimeMillis();
                new Thread(() -> {
                    try { postToD1("A11Y", "WRITE_SECURE 자동부여 시도 → " + AdbAccess.ensureVehicleApiAccess(this)); } catch (Throwable ig) {}
                    if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED) ensureA11yListed("자동부여 뒤");
                    else postToD1("A11Y", "❌ 접근성 등록 불가 — WRITE_SECURE_SETTINGS 미부여(자동부여 실패, 무선ADB OFF 가능)");
                }, "a11ygrant").start();
            }
            return;
        }
        ensureA11yListed("주기 확인");
    }

    // ★788 목록에 우리 항목(짧은 표기 포함)이 없을 때만 지금 목록 뒤에 덧붙인다. 다른 항목·순서는 그대로 둔다.
    //   accessibility_enabled는 1이 아닐 때만 1로 쓴다. 덧붙였거나 전역을 켰을 때만 로그.
    private String ensureA11yListed(String why) {
        try {
            android.content.ContentResolver cr = getContentResolver();
            String cur = android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services");
            if (cur == null) cur = "";
            boolean listed = a11yListed(cur, A11Y_COMPONENT_SELF);
            String r = listed ? "목록에 있음" : "목록에 없어 덧붙임(다른 항목 유지)";
            if (!listed) android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", cur.isEmpty() ? A11Y_COMPONENT_SELF : cur + ":" + A11Y_COMPONENT_SELF);
            boolean globalOn = android.provider.Settings.Secure.getInt(cr, "accessibility_enabled", 0) == 1;
            if (!globalOn) { android.provider.Settings.Secure.putInt(cr, "accessibility_enabled", 1); r += " · 전역 0→1"; }
            if (!listed || !globalOn) postToD1("A11Y", "접근성 " + r + " (" + why + ")");
            return r;
        } catch (Throwable t) { postToD1("A11Y", "접근성 목록 확인 예외(" + why + "): " + t.getClass().getSimpleName()); return "예외"; }
    }

    // ★788 콜론 목록에 comp(패키지/클래스 전체 표기)가 있는지 — 짧은 표기(패키지/.클래스)도 같은 항목으로 본다(ComponentName.unflattenFromString 규칙).
    static boolean a11yListed(String list, String comp) {
        if (list == null || list.isEmpty()) return false;
        for (String p : list.split(":")) {
            p = p.trim();
            int s = p.indexOf('/');
            if (s <= 0) continue;
            String pkg = p.substring(0, s), cls = p.substring(s + 1);
            if (cls.startsWith(".")) cls = pkg + cls;
            if ((pkg + "/" + cls).equals(comp)) return true;
        }
        return false;
    }

    private void startRelayAnchorLoop() {
        if (mainHandler == null) return;
        selfEnableA11y();   // ★시작 즉시 1회 — 목록에 우리 항목이 있는지 확인(없으면 덧붙임, 788).
        // ★a11y 빠른 워치독 — 12초마다 stale 체크, 죽었을 때만 즉시 복구 트리거(평소엔 저비용). 60초 앵커보다 빠른 반응 + 재부팅 후 빠른 바인딩.
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (bestA11yAge() > 15000) { selfEnableA11y(); maybeManageHelper(); }   // 각 복구함수 자체 throttle 보유
                } catch (Throwable ignored) {}
                if (mainHandler != null) mainHandler.postDelayed(this, 12000);
            }
        }, 12000);
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                anchorTick++;
                // ★v221 계측 — 앵커 start()가 호출되는지 + startForegroundService가 거부되는지(백그라운드 제한) 실측.
                //   성공은 10틱(≈10분)마다, 실패는 매번 로깅. 이 로그로 a11y 좀비 근본원인(호출안됨 vs 거부됨) 구분.
                try {
                    RelayAnchorService.start(SnifferService.this);
                    if (anchorTick % 10 == 1) postToD1("A11Y", "앵커 start 호출 OK (tick=" + anchorTick + ")");
                } catch (Throwable t) {
                    postToD1("A11Y", "★앵커 start 실패 tick=" + anchorTick + ": " + t.getClass().getSimpleName() + " " + t.getMessage());
                }
                selfEnableA11y();   // ★매 60초 — DiLink가 목록서 지워도 되살림(가디언 KeepAliveService와 동일).
                maybeManageHelper();   // ★헬퍼 미설치→설치 / a11y stale→로컬 재설치(throttle). 미러·오토스타트 실행 주체 유지.
                // ★580 6시간 주기 오토스타트 재해제 폐지(운영자 지시 2026-09-24) — 재시작마다 오토스타트금지 앱이 자꾸 떠서.
                //   오토스타트 해제는 오직 "사용자가 업데이트 버튼 누른 뒤 단발"(checkVersionAutostartFix)만. 실패 시 수동 안내.
                uploadSelfUpdResult();   // ★2026-09-19 직전 self-update install 결과(selfupd_last.txt, 설치 셸이 남김) → D1 업로드(재시작 실패 케이스도 원인 확보). 파일 있을 때만.
                maybeAutoUpdate();   // ★자동 업데이트(2026-09-17 운영자 지시) — 공유판은 원격 SELFUPDATE가 막혀
                maybeUpdatePrompt();   // ★574 시동 ON 시 화면으로 안내(자동 업데이트가 조용히 실패해도 사용자가 알게) 있어 앱을 안 여는 테스터가 영구 구버전이던 것
                maybeProbeCameras();   // ★547 카메라 가용성 실측(주차 중 24h 1회) → relay_state cams → 워커가 카메라 목록 필터(대시캠 없는 차에서 항목 숨김)
                checkMirrorRestore();   // ★애프터블로우 종료(시동OFF) 감지 시 미러 자동폴딩 ON 복원.
                sampleDataUsage();   // ★650 릴레이 자기 데이터 사용량(모바일/와이파이) 누적
                maybeStorageDiag();   // ★654 시동 OFF 뒤 +1·5·30·120분 저장장치 관찰(재기동 뒤에도 이어감)
                SentryManager.get().tick();   // ★센트리 — 게이트 재평가·고아 정리·상태 업로드(post라 비차단)
                if (relay != null) relay.putStatus("a11y_alive_s", (int) Math.min(99999, a11yAliveAge() / 1000));   // ★a11y 심박 나이(앱 표시용 — 8초 이하=살아있음, 크면 죽음/좀비)
                if (relay != null) { long ha = helperAliveAgeMs(); relay.putStatus("helper_alive_s", (int) (ha < 0 ? -1 : Math.min(99999, ha / 1000))); }   // ★헬퍼 a11y 심박 나이(-1=ack없음)
                if (relay != null && DiLink5Support.isDiLink5()) relay.putStatus("d5_stage", DiLink5Support.d5Stage);   // ★580(1단계) 관측용 — hang 중에도 이 HB 루프가 살아있으면 멈춘 직전 단계 확인 가능
                probeD5TsimplOnce();   // ★581(그룹B) BSD/레이더/HUD/앞차출발 게터 진단 — 부팅 1회, 별도 스레드
                startD5Poller();   // ★598 D5 상태 폴러 — 리스너 전멸 도메인(도어·속도·외기온)을 게터 폴링으로 대체
                // ★583(운영자 09-25) — 워치독이 여태 "프로세스 생존(pidof)"만 봐서, 프로세스는 살아있는데 멈춘 경우
                //   (예: 자동업뎃 다이얼로그가 install 스레드에서 무한대기)를 16시간+ 못 잡았다(186902 실사고). 이 HB 루프가
                //   매 60초 여기까지 오는 것 자체가 "메인스레드 정상"의 증거라, 그 사실을 shell 파일로 남겨 워치독이 읽게 한다.
                //   dadb shell 호출은 블로킹 가능성 있어 별도 스레드(메인 HB 루프를 절대 안 막음).
                new Thread(() -> { try { AdbAccess.runShell(SnifferService.this, "date +%s > /data/local/tmp/byd_hb"); } catch (Throwable ignored) {} }, "hb-touch").start();
                // ★662 접근성 서비스(:relay 프로세스)가 "메인이 살아 있나"를 싸게 알 수 있게 앱 전용 파일 mtime 을 60초마다 갱신(접근성의 20초 기동 요청 줄이기용).
                try { java.io.File af = new java.io.File(getFilesDir(), "main_alive"); if (!af.exists()) af.createNewFile(); af.setLastModified(System.currentTimeMillis()); } catch (Throwable ignored) {}
                if (mainHandler != null) mainHandler.postDelayed(this, 60000);
            }
        }, 60000);
    }

    // 시동 OFF 시 헤드유닛이 Doze로 들어가도 CPU를 계속 깨워둠(블박 앱과 동일 기법).
    // 네트워크는 유심으로 살아있으니, 이 WakeLock이면 릴레이가 24h 온라인 유지될 것으로 기대.
    private void acquireWakeLock() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "bydsniffer:relay");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            Log.i(TAG, "WakeLock 획득 — CPU 상시 유지");
        } catch (Throwable t) { Log.w(TAG, "WakeLock 획득 실패", t); }
    }

    // Doze 화이트리스트 요청 — 예외되면 시동 OFF 후 네트워크/알람이 안 끊김(일렉트로 WRITE_SECURE_SETTINGS 대체).
    private void requestBatteryOptExemption() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(android.net.Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        } catch (Throwable t) { Log.w(TAG, "배터리최적화 예외요청 실패", t); }
    }

    // setExactAndAllowWhileIdle 로 Doze 중에도 깨어나 하트비트 실행(일렉트로 SCHEDULE_EXACT_ALARM 방식).
    private void scheduleWakeAlarm(long delayMs) {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            if (wakePI == null) {
                Intent i = new Intent(this, SnifferService.class).setAction(ACTION_WAKE);
                int fl = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
                wakePI = android.app.PendingIntent.getService(this, 7, i, fl);
            }
            long at = System.currentTimeMillis() + delayMs;
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, wakePI);
        } catch (Throwable t) { Log.w(TAG, "wake 알람 예약 실패", t); }
    }

    // 배터리 보호 설정을 prefs에서 로드(헤드유닛 앱 또는 아이폰 RAW로 저장됨).
    private void applyWakeGuardPrefs() {
        if (prefs == null) return;
        // ★v35: 상시생존 항상 ON 강제. UI(v32 간소화로 제거)가 없어 prefs 토글 불가 +
        //   이전 테스트에서 KEY_WAKE_ENABLED=false로 저장돼 wake 루프가 안 돌던 게 상시생존 실패의 원인이었음.
        //   wakeUpMcu 2초 루프가 헤드유닛 생존의 핵심(일렉트로 동일 원리, 실차 확인). 배터리는 저전압/백스톱 가드로 보호.
        wakeEnabled = true;
        int hours = prefs.getInt(KEY_WAKE_MAXHOUR, 16);
        int smin = prefs.getInt(KEY_WAKE_SUSTAINMIN, 10);
        maxKeepAliveMs = hours * 3600_000L;
        lowSustainMs = smin * 60_000L;
        keepAliveUntil = System.currentTimeMillis() + maxKeepAliveMs;
    }

    // 설정 변경 즉시 적용 (헤드유닛 앱 다이얼로그/아이폰 RAW 저장 후 호출).
    public void applyWakeSettings() {
        applyWakeGuardPrefs();
        if (wakeEnabled) scheduleWakeAlarm(0);
        else { try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {} }
        postToD1("DILINK_RAW", "wake설정: " + (wakeEnabled ? "ON 백스톱" + (maxKeepAliveMs/3600000) + "h 저전압" + (lowSustainMs/60000) + "분" : "OFF(상시생존 끔)"));
    }

    // ACC OFF 후에도 헤드유닛 MCU를 깨워 릴레이 온라인 유지. 배터리 보호: 마지막 활동 후 maxKeepAliveMs 초과
    // 또는 12V<voltCutoffMv 이면 wakeLock 해제·절전 허용(재점검 알람만 유지).
    // 연속 루프 — 2초마다 wakeUpMcu로 헤드유닛을 계속 깨워둠(Electro wakeUpAfterAccOff 방식).
    // 배터리 보호: 12V 저전압 지속 or 무활동 백스톱 초과면 wakeLock 해제·절전 허용(루프는 계속 재점검).
    private void startWakeLoop() {
        if (wakeLoopRunning) return;
        wakeLoopRunning = true;
        postToD1("WAKE", "상시생존 wake 루프 시작 (wakeUpMcu 2초 반복) · 백스톱" + (maxKeepAliveMs / 3600000) + "h");
        new Thread(() -> {
            while (wakeLoopRunning && pollRunning) {
                try {
                    // ★v44 사인 진단: 10초마다 심박 저장(commit=동기 디스크) → 다음 시작 때 사망 시각 역산.
                    //   60초마다 워치독 원샷잡 재장전 — 프로세스 죽으면 마지막 장전분이 ≤3분 내 발화해 부활.
                    long hbNow = System.currentTimeMillis();
                    if (hbNow - lastHbCommit >= 10_000) {
                        lastHbCommit = hbNow;
                        // ★736 심박과 함께 마지막 서버 성공 시각·망 상태 — 다음 시작 때 죽기 전 '서버에 닿고 있었나'(앱 멈춤 vs 망 끊김) 판정(FB1846 아토3 70분 무소식)
                        try { long okAgo = RelayClient.lastOkAgoMs(); prefs.edit().putLong("forensic_hb", hbNow).putLong("forensic_ok", okAgo >= 0 ? hbNow - okAgo : 0).putString("forensic_net", netSummary()).commit(); } catch (Throwable ignored2) {}
                        if (hbNow - lastWatchdogArm >= 60_000) { lastWatchdogArm = hbNow; scheduleWatchdogJob(); }
                    }
                    if (wakeEnabled) {
                        long now = System.currentTimeMillis();
                        boolean sustainedLow = (lowVoltFlag == 1 && lowSinceMs > 0 && (now - lowSinceMs) > lowSustainMs);
                        boolean expired = now > keepAliveUntil;
                        // ★614 상시생존 상태 관측(동작 변경 없음) — 자루씨(165328) "문 닫으면 오프라인" 규명용. 절전 사유·저전압 플래그·wakeUpMcu 성패를
                        //   변화 시 relay_state(링버퍼 아님)에. 직전 상태는 prefs에도 남겨 재시작 후 ka_prev로 보고(죽기 직전 상태 확인).
                        String kaNow = sustainedLow ? "sleep:lowvolt" : (expired ? "sleep:backstop" : "awake");
                        if (!kaNow.equals(kaStateLast)) {
                            kaStateLast = kaNow;
                            String kv = kaNow + "@" + now;
                            if (relay != null) { relay.putStatus("ka_state", kv); relay.putStatus("lv_flag", lowVoltFlag); }
                            try { prefs.edit().putString("ka_last", kv).apply(); } catch (Throwable ignored2) {}
                        }
                        usbKeepTick(sustainedLow || expired);   // ★753 시동 꺼도 USB 유지 — 보호 조건이면 AP 깨우기도 멈춘다
                        if (sustainedLow || expired) {
                            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                        } else {
                            // wakeUpMcu 결과 진단 — 첫 호출 1회 + 이후 60초(30틱)마다 성공/실패 추이·예외 cause 보고.
                            //   (원격 CLOSEAIR 시동끔 후 wakeUpMcu가 계속 실패하는지 / cause가 뭔지 파악용)
                            try {
                                Object p = deviceByName("power");
                                if (p == null) {
                                    wakeFailCount++;
                                    if (!"null".equals(wakeResLast)) { wakeResLast = "null"; if (relay != null) relay.putStatus("wake_mcu", "power null"); }   // ★614
                                    if (!wakeMcuDiagDone || wakeReportTick % 30 == 0) { wakeMcuDiagDone = true; postToD1("WAKE", "wakeUpMcu 불가 — power null (실패 " + wakeFailCount + "/성공 " + wakeOkCount + ")"); }
                                } else {
                                    injectPowerContext(p);   // ★v41: wakeUpMcu 직전 mContext(자기+부모, final 포함) 강제 주입 → Context null NPE 방지
                                    Object r = p.getClass().getMethod("wakeUpMcu").invoke(p);
                                    wakeOkCount++;
                                    if (!"ok".equals(wakeResLast)) { wakeResLast = "ok"; if (relay != null) relay.putStatus("wake_mcu", "ok ret=" + r); }   // ★614
                                    // ★v84: D1 노이즈 축소 — 시작 시 1회 + 이후 1시간(1800틱×2초)마다 요약만. wakeUpMcu 호출 자체는 매 틱 유지(생존 핵심).
                                    if (!wakeMcuDiagDone || wakeReportTick % 1800 == 0) { wakeMcuDiagDone = true; postToD1("WAKE", "wakeUpMcu OK ret=" + r + " (성공 " + wakeOkCount + "/실패 " + wakeFailCount + ")"); }
                                }
                            } catch (Throwable t) {
                                wakeFailCount++;
                                Throwable c = t.getCause();
                                if (!"fail".equals(wakeResLast)) { wakeResLast = "fail"; if (relay != null) relay.putStatus("wake_mcu", "fail " + (c != null ? c.getClass().getSimpleName() : t.getClass().getSimpleName())); }   // ★614
                                if (!wakeMcuDiagDone || wakeReportTick % 30 == 0) { wakeMcuDiagDone = true; postToD1("WAKE", "wakeUpMcu 실패: " + t.getClass().getSimpleName() + " cause=" + (c != null ? c.getClass().getSimpleName() + ":" + c.getMessage() : "?") + " (실패 " + wakeFailCount + "/성공 " + wakeOkCount + ")"); }
                            }
                            wakeReportTick++;
                            // ★D5 sentry wake(2026-09-15 Braveheart v48.16 이식) — 씨라7(DiLink5)은 wakeUpMcu(PowerDevice MCU wake feature -1442840502)가 no-op이라 시동종료 후 미생존. BYDAutoSpecialDevice feature 1901/1902=1(+D5전용 782237711/782237728=1)로 sentry 유지. 30초(15틱)마다 재확언. 근거=docs/overdrive-braveheart-v48/.
                            // ★736 돌핀(D3) 센트리 깨우기 병행 시험(운영자 10-04 "다 해", FB1846) — 운영자판만·스위치 RAW {"d3Sentry":false}로 끔. D3는 1901·1902만(782237711·728은 D5 전용).
                            //   볼 것: 시동 OFF 뒤 생존 시간·12V 저전압 플래그·배터리·차 실제 센트리가 켜지는지(부작용).
                            final boolean d5w = DiLink5Support.isDiLink5();
                            if ((d5w || (BuildConfig.IS_OPERATOR && prefs != null && prefs.getBoolean("d3_sentry", true))) && wakeReportTick % 15 == 0) {
                                try {
                                    Object sp = deviceByName("android.hardware.bydauto.special.BYDAutoSpecialDevice");
                                    if (sp == null) sp = onDemand("android.hardware.special.BYDAutoSpecialDevice");   // ★677 오버드라이브의 두 번째 후보(android.hardware.special…)도 시도
                                    if (sp == null && d5w) sp = specialViaD5Loader();   // ★681 앱로더·시스템앱 로더가 ClassNotFound라 D5 전용 로더(OEM dex 인메모리)로 시도
                                    if (sp != null) {
                                        String r1 = baseSet(sp, 1901, 1), r2 = baseSet(sp, 1902, 1);
                                        if (d5w) { baseSet(sp, 782237711, 1); baseSet(sp, 782237728, 1); }
                                        if (!sentryDiagDone || wakeReportTick % 1800 == 0) { sentryDiagDone = true; postToD1("WAKE", (d5w ? "D5 sentry wake(SpecialDevice 1901/1902/782237711/782237728=1) 발사" : "D3 센트리 깨우기 시험(SpecialDevice 1901·1902=1) 발사 결과 " + r1 + " · " + r2)); }
                                    } else if (!sentryDiagDone) {
                                        sentryDiagDone = true; postToD1("WAKE", (d5w ? "D5" : "D3 시험") + " sentry wake — BYDAutoSpecialDevice null(deviceByName 미해결)");
                                        new Thread(() -> { try { Object dd = null; String direct; try { Class<?> k = Class.forName("android.hardware.bydauto.special.BYDAutoSpecialDevice"); dd = k.getMethod("getInstance", android.content.Context.class).invoke(null, SnifferService.this); direct = "앱로더 getInstance=" + (dd == null ? "null" : "OK"); } catch (Throwable t) { Throwable c = t.getCause() != null ? t.getCause() : t; direct = "앱로더 " + c.getClass().getSimpleName() + ":" + String.valueOf(c.getMessage()); }   // ★678 직접 시도 결과(예외 메시지)
                                            postToD1("WAKE", "SpecialDevice 직접 시도 — " + direct + " / SPECIAL권한 부여=" + checkSelfPermission("android.permission.BYDAUTO_SPECIAL_SET") + "," + checkSelfPermission("android.permission.BYDAUTO_SPECIAL_GET"));
                                            postToD1("WAKE", "SpecialDevice 탐색 — 시스템앱 로드 사유=" + DiLink5Support.lastSysReport + " / 클래스 검색(SpecialDevice)=" + DiLink5Support.findSdkClasses(SnifferService.this, "SpecialDevice")); } catch (Throwable ignored) {} }, "sp-find").start();
                                    }
                                } catch (Throwable ignored2) {}
                            }
                            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
                        }
                    }
                } catch (Throwable ignored) {}
                try { Thread.sleep(WAKE_LOOP_MS); } catch (InterruptedException e) { break; }
            }
            wakeLoopRunning = false;
        }, "wake-loop").start();
    }

    // ★753 시동 꺼도 USB 유지(docs/SENTRY-DESIGN.md 7.6, 운영자 10-05 "USB 허브·SD 시동 OFF 사용 빨리 배포") — 시동 OFF면 USB 허브·SD가 끊기는 건
    //   물리 차단이 아니라 AP가 잠들며 xhci가 서스펜드돼서다(10-05 09:37 실증: AP를 깨우면 허브·SD 복귀). 상시생존 wakeUpMcu는 MCU·네트워크만 깨운다.
    //   수동 토글(usb_keep_user) 또는 센트리가 외장(SD·USB)에 녹화 중일 때 AP를 깨워 둔다. 시동 ON·12V 저전압 지속·백스톱이면 멈춘다(기존 보호 그대로). 씨라7 제외.
    //   방식(usb_keep_mode, 실차로 고름): 0=화면 깨우기 잠금(SCREEN_DIM|ACQUIRE_CAUSES_WAKEUP, 30초마다 다시 잡아 다시 깨움) 1=셸 keyevent 224(WAKEUP) 15초마다 2=둘 다.
    //   ★758 3=셸 깨우기(ShellWake — 순정 Utils.wakeup과 같은 PowerManager.wakeUp(…, 9, "ACC_ON")), 기본. 0~2는 시동 OFF에서 안 먹었다(15:47 운영자 차 757:
    //   잠금+keyevent가 켜진 채로 5초 뒤 허브·SD 분리, Asleep). 기본을 바꾸려고 저장 키를 usb_keep_how로 새로 잡았다(예전 값은 안 읽음).
    private android.os.PowerManager.WakeLock usbKeepLock;
    private volatile String usbKeepStLast = "";
    private long usbKeepKeyAt = 0, usbKeepCycleAt = 0, usbKeepErrAt = 0;
    private boolean usbKeepWanted() {
        if (prefs == null || IS_D5) return false;
        if (prefs.getBoolean("usb_keep_user", false)) return true;
        com.cseini.bydsniffer.sentry.SentryManager sm = com.cseini.bydsniffer.sentry.SentryManager.get();
        com.cseini.bydsniffer.sentry.SentryStorage st = sm.storage();
        String k = st != null ? st.selectedKind() : "internal";
        return sm.enabled() && ("sd".equals(k) || "usb".equals(k));   // ★755 무장(시동 OFF +20초) 전에 허브가 잠들어(+11.6초) — 켜 둔 상태면 시동 OFF 즉시부터 붙잡는다(시동 ON은 아래에서 쉼)
    }
    private void usbKeepTick(boolean guardSleep) {
        boolean want = usbKeepWanted();
        int pw = want ? powerLevelNow() : -1;   // ★732와 같이 전원 단계 게터(2=ON) 우선 — 시동 판별기(공조 전원 엣지)는 원격 공조 중 오독이 있었다
        boolean ignOn = pw >= 0 ? pw == 2 : (relay != null && relay.ignitionState() == 1);
        String why = !want ? "off" : guardSleep ? "pause:보호(저전압·백스톱)" : ignOn ? "pause:시동 켜짐" : "on:" + (prefs.getBoolean("usb_keep_user", false) ? "수동" : "센트리");
        boolean active = why.startsWith("on:");
        int mode = prefs != null ? prefs.getInt("usb_keep_how", 3) : 3;   // ★758 기본 3(셸 깨우기)
        boolean dark = active && mode == 3 && prefs.getBoolean("usb_keep_dark", true) && lastGearVal == 3 && !(lastSpeed > 0.5) && lastDriving != 1;   // ★760 기어 P·정지일 때만
        if (dark != (usbDarkView != null)) usbKeepDark(dark);
        if (!why.equals(usbKeepStLast)) {
            usbKeepStLast = why;
            if (relay != null) relay.putStatus("usb_keep", why + (active ? "/m" + mode : ""));
            postToD1("USBKEEP", "USB 유지 " + why + (active ? " · 방식 " + (mode == 3 ? "셸 깨우기" : mode == 1 ? "keyevent 224" : mode == 2 ? "잠금+keyevent" : "화면 깨우기 잠금") : ""));
        }
        usbKeepSd(active, why);
        if (!active) { releaseUsbKeepLock(); return; }
        long now = System.currentTimeMillis();
        if (mode == 3) { releaseUsbKeepLock(); usbKeepShellWake(now); return; }
        if (mode != 1) {
            try {
                if (usbKeepLock == null) {
                    android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                    usbKeepLock = pm.newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "bydsniffer:usbkeep");
                    usbKeepLock.setReferenceCounted(false);
                }
                if (usbKeepLock.isHeld() && now - usbKeepCycleAt > 30_000L) usbKeepLock.release();   // 롬이 잠금을 무력화해도 다시 잡을 때 다시 깨우게
                if (!usbKeepLock.isHeld()) { usbKeepLock.acquire(); usbKeepCycleAt = now; }
            } catch (Throwable t) {
                if (now - usbKeepErrAt > 60_000L) { usbKeepErrAt = now; postToD1("USBKEEP", "화면 깨우기 잠금 실패 " + t); }
            }
        } else releaseUsbKeepLock();
        if (mode >= 1 && now - usbKeepKeyAt > 10_000L) {   // ★755 15→10초(허브는 AP가 잠들고 약 11.6초 뒤 끊김)
            usbKeepKeyAt = now;
            new Thread(() -> {
                String r = AdbAccess.runShell(this, "input keyevent 224");
                if (r == null) { long t = System.currentTimeMillis(); if (t - usbKeepErrAt > 60_000L) { usbKeepErrAt = t; postToD1("USBKEEP", "keyevent 224 실패(셸 연결 안 됨)"); } }
            }, "usbkeep-key").start();
        }
    }
    private void releaseUsbKeepLock() { try { if (usbKeepLock != null && usbKeepLock.isHeld()) usbKeepLock.release(); } catch (Throwable ignored) {} }
    // ★766 USB 유지 중 SD 다시 올리기(센트리 세션 18:15 운영자 차 실측) — BYD StorageManagerService가 시동 OFF +5초에 외장 볼륨을 언마운트하는데,
    //   셸 깨우기(759+)로 허브가 잠들지 않으면 재열거가 없어 SD가 unmounted로 남는다(허브·카드 리더는 붙어 있음, 셸 `sm mount public:8,97`로 바로 올라옴).
    //   유지가 시작되고 약 10초 뒤부터 45초마다 `sm list-volumes public`에서 unmounted인 볼륨을 `sm mount`.
    //   유지가 멈출 때(보호·끔 — 시동은 그대로 OFF라 곧 허브가 잠들어 카드가 떨어진다) 올라와 있는 외장 볼륨을 `sm unmount`로 먼저 안전하게 내린다
    //   (릴레이가 주차 중 재시작돼도 빠지지 않게 '우리가 올린 것'이 아니라 그때 올라와 있는 것 전부). 시동 ON이면 BYD가 다시 맡는다.
    private long usbSdAt = 0;
    private volatile boolean usbSdBusy = false, usbSdWasActive = false;
    private void usbKeepSd(boolean active, String why) {
        if (usbSdBusy) return;
        long now = System.currentTimeMillis();
        if (active) {
            if (!usbSdWasActive) com.cseini.bydsniffer.sentry.SentryManager.get().storageBack();   // ★767 유지가 다시 켜짐 — 저장소 양보 중이던 센트리를 풀어 줌(양보 중 아니면 바로 반환)
            usbSdWasActive = true;
            if (usbSdAt == 0) usbSdAt = now - 35_000L;   // 첫 확인은 유지 시작 약 10초 뒤(BYD 언마운트가 OFF +5초)
            if (now - usbSdAt < 45_000L) return;
            usbSdAt = now;
            usbKeepSdShell("unmounted", "mount", "SD 다시 올리기 요청");
            return;
        }
        usbSdAt = 0;
        if (!usbSdWasActive) return;
        usbSdWasActive = false;
        if (why.startsWith("pause:시동")) return;
        usbKeepSdShell("mounted", "unmount", "SD 안전하게 내리기(" + why + ")");
    }
    private void usbKeepSdShell(String state, String verb, String what) {
        usbSdBusy = true;
        new Thread(() -> {
            try {
                String r = AdbAccess.runShell(this, "sm list-volumes public 2>&1");
                if (r == null) return;
                boolean yielded = false;
                for (String ln : r.split("\n")) {
                    String[] p = ln.trim().split("\\s+");
                    if (p.length < 2 || !p[0].startsWith("public:") || !state.equals(p[1])) continue;
                    // ★767 내리기 직전 센트리 저장소 양보(센트리 세션 sentry6) — vold는 내리는 볼륨의 열린 파일을 쥔 프로세스를 죽이므로,
                    //   센트리가 SD에 쓰는 중이면 릴레이가 강제 종료된다. 외장 선택이면 조각을 닫고 PAUSED로 멈춘 뒤 돌아온다(최대 15초).
                    if ("unmount".equals(verb) && !yielded) { yielded = true; com.cseini.bydsniffer.sentry.SentryManager.get().yieldStorage(what); }
                    String m = AdbAccess.runShell(this, "sm " + verb + " " + p[0] + " 2>&1");
                    postToD1("USBKEEP", what + " " + p[0] + (p.length > 2 ? "(" + p[2] + ")" : "") + (m != null && !m.trim().isEmpty() ? " → " + m.trim() : ""));
                }
            } finally { usbSdBusy = false; }
        }, "usbkeep-sd").start();
    }

    // ★760 USB 유지 중 화면만 끄기(운영자 10-05 "1 ㄱㄱ" — 759 실측: 깨어 있는 동안 화면 켜짐·밝기 255) — BYD 창 속성
    //   WindowManager.LayoutParams.screenBacklightMode(0=백라이트 끔)를 단 1×1 투명 창을 붙여 둔다(services.jar RootWindowContainer: 보이는 창의
    //   screenBacklightMode가 PowerManager 백라이트 모드를 덮음 → 시스템은 깨어 있고 USB·SD 유지, 백라이트만 꺼짐).
    //   창이 사라지면(떼거나 우리 프로세스가 죽으면) 덮어쓰기가 풀려 화면이 바로 켜진다 — 시동 ON 때 ROM이 앱을 강제 종료해도 꺼진 채 남지 않는다.
    //   붙이는 조건: USB 유지 동작 중(시동 OFF)·셸 깨우기 방식·기어 P·속도 0·주행 아님. 하나라도 어긋나면 2초 틱에서 바로 뗀다. RAW {"usbKeepDark":false}로 끔.
    private volatile android.view.View usbDarkView;
    private volatile boolean usbDarkUnsupported = false;
    private volatile long usbDarkErrAt = 0;
    private void usbKeepDark(boolean want) {
        if (mainHandler == null || (want && (usbDarkUnsupported || System.currentTimeMillis() - usbDarkErrAt < 60_000L))) return;
        mainHandler.post(() -> {
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            try {
                if (want && usbDarkView == null) {
                    android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(1, 1,
                            Build.VERSION.SDK_INT >= 26 ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : android.view.WindowManager.LayoutParams.TYPE_PHONE,
                            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            android.graphics.PixelFormat.TRANSLUCENT);
                    lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                    android.view.WindowManager.LayoutParams.class.getField("screenBacklightMode").setInt(lp, 0);
                    android.view.View v = new android.view.View(this);
                    wm.addView(v, lp);
                    usbDarkView = v;
                    if (relay != null) relay.putStatus("usb_keep_dark", 1);
                    postToD1("USBKEEP", "화면 끔 창 붙임(백라이트 0) — 시스템은 깨어 있음");
                } else if (!want && usbDarkView != null) {
                    android.view.View v = usbDarkView;
                    usbDarkView = null;
                    wm.removeViewImmediate(v);
                    if (relay != null) relay.putStatus("usb_keep_dark", 0);
                    postToD1("USBKEEP", "화면 끔 창 뗌 — 화면 다시 켜짐");
                }
            } catch (NoSuchFieldException e) {
                usbDarkUnsupported = true;
                postToD1("USBKEEP", "화면 끄기 불가 — 이 헤드유닛엔 screenBacklightMode 창 속성이 없음");
            } catch (Throwable t) {
                usbDarkErrAt = System.currentTimeMillis();
                postToD1("USBKEEP", "화면 끔 창 " + (want ? "붙이기" : "떼기") + " 실패 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }
    // ★758 셸 깨우기 — 잠들어 있으면(화면 비활성) 5초 간격으로 바로, 깨어 있어도 1분마다(일렉트로와 같은 주기). 허브는 AP가 잠들고
    //   약 11.6초 뒤 끊기므로 2초 틱에서 잠든 걸 보자마자 깨운다. app_process는 셸(adbd) 자식이라 시동 OFF 때 우리 앱이 죽어도 끝까지 돈다.
    private long usbWakeAt = 0, usbWakeN = 0, usbWakeSleepN = 0;
    private volatile long usbWakeLogAt = 0;
    private volatile String usbWakeRes = "";
    private void usbKeepShellWake(long now) {
        boolean asleep = false;
        try { asleep = !((android.os.PowerManager) getSystemService(POWER_SERVICE)).isInteractive(); } catch (Throwable ignored) {}
        boolean failing = !usbWakeRes.isEmpty() && !"ok".equals(usbWakeRes);   // ★759 실패 중이면 잠들어 있어도 1분 간격(주차 중 5초마다 셸·app_process 띄우지 않게)
        if (now - usbWakeAt <= (asleep && !failing ? 5_000L : 60_000L)) return;
        usbWakeAt = now;
        usbWakeN++;
        if (asleep) usbWakeSleepN++;
        final boolean wasAsleep = asleep;
        final long n = usbWakeN, sn = usbWakeSleepN;
        new Thread(() -> {
            String r = AdbAccess.runShell(this, "CLASSPATH=" + getPackageCodePath() + " timeout 15 app_process /system/bin --nice-name=snif_wake com.cseini.bydsniffer.ShellWake 2>&1 | tail -1");
            String res = r == null ? "셸 연결 안 됨" : r.trim();
            long t = System.currentTimeMillis();
            if (!res.equals(usbWakeRes) || (wasAsleep && t - usbWakeLogAt > 60_000L) || t - usbWakeLogAt > 600_000L) {
                usbWakeRes = res;
                usbWakeLogAt = t;
                postToD1("USBKEEP", "셸 깨우기" + (wasAsleep ? "(잠든 상태에서)" : "") + " " + res + " · 누적 " + n + "회(잠든 상태 " + sn + "회)");
            }
        }, "usbkeep-wake").start();
    }

    // JobScheduler 백스톱(일렉트로 RestarterJobService 방식) — 알람과 별개 경로로 15분마다
    // 서비스 생존 점검·재기동. setPersisted라 재부팅 후에도 시스템이 잡을 유지.
    private void scheduleJobRestart() {
        try {
            android.app.job.JobScheduler js = (android.app.job.JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            android.app.job.JobInfo job = new android.app.job.JobInfo.Builder(1,
                    new android.content.ComponentName(this, SnifferJobService.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build();
            js.schedule(job);
            Log.i(TAG, "JobScheduler 백스톱 등록(15분 주기, persist)");
        } catch (Throwable t) { Log.w(TAG, "JobScheduler 등록 실패", t); }
    }

    // ★v44 워치독 원샷잡(id=2) — 살아있는 동안 60초마다 재장전(60s~3분 창). 프로세스가 죽으면
    //   마지막 장전분이 ≤3분 내 발화 → SnifferJobService가 서비스 재기동. 15분 주기잡보다 5배 빠른 부활.
    //   (force-stop이면 잡도 전멸 — 그 경우는 reportForensics의 ExitInfo/심박으로 사인만 확인)
    private void scheduleWatchdogJob() {
        try {
            android.app.job.JobScheduler js = (android.app.job.JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            js.schedule(new android.app.job.JobInfo.Builder(2,
                    new android.content.ComponentName(this, SnifferJobService.class))
                    .setMinimumLatency(60_000L)
                    .setOverrideDeadline(180_000L)
                    .build());
        } catch (Throwable t) { Log.w(TAG, "워치독잡 등록 실패", t); }
    }

    // ★v44 사인(死因) 자가진단 — 시작 시 이전 프로세스의 마지막 심박·수명이벤트·시스템 ExitInfo를 D1에 보고.
    //   원격 CLOSEAIR 후 릴레이가 죽는 원인 규명용: USER_REQUESTED=강제중지 / SIGNALED·OTHER=시스템킬 /
    //   심박만 끊기고 수명이벤트 없음=순간 프로세스킬(정상 종료 경로 안 탐).
    private void reportForensics() {
        new Thread(() -> {
            try {
                long now = System.currentTimeMillis();
                long hb = prevHb;         // ★v45: onCreate에서 캡처한 스냅샷 사용(레이스 수정)
                String life = prevLife;
                int ver = 0;
                try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Throwable ignored) {}
                StringBuilder sb = new StringBuilder("시작 v" + ver + " SDK" + Build.VERSION.SDK_INT);
                sb.append(hb > 0 ? " · 이전심박 " + ((now - hb) / 1000) + "초 전" : " · 이전심박 없음(첫 실행)");
                sb.append(" · 마지막수명이벤트 ").append(life != null ? life : "없음(순간킬 의심)");
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                        java.util.List<android.app.ApplicationExitInfo> ex = am.getHistoricalProcessExitReasons(getPackageName(), 0, 1);
                        if (ex != null && !ex.isEmpty()) {
                            android.app.ApplicationExitInfo e = ex.get(0);
                            String desc = e.getDescription();
                            // ★610 ExitInfo 시각이 직전 심박보다 이르면 이번 재기동은 프로세스 사망이 아님(서비스만 재생성) — 옛 기록 재사용 표시(09-27 012778 @ts 반복 5회)
                            boolean staleExit = hb > 0 && e.getTimestamp() < hb;
                            setBootWhy("gap=" + (hb > 0 ? (now - hb) / 1000 : -1) + "s " + (staleExit ? "SVC_RECREATE(exit기록없음)" : exitReasonName(e.getReason()).replaceAll("\\(.*", "") + " @" + e.getTimestamp()) + " t=" + now);
                            sb.append(" · 사인 ").append(exitReasonName(e.getReason()))
                              .append("(").append((now - e.getTimestamp()) / 1000).append("초 전)")
                              .append(" desc=").append(desc);
                            // ★2026-09-19 강제종료 범인 PID 해석(로그분석 요청) — USER_REQUESTED(10)/OTHER(13)는 desc에 "from pid N"이 있다.
                            //   실측상 낮은 PID=시스템서비스/런처가 주기 kill. 앱은 재부팅 아닌 재시작이라 그 PID가 대개 살아있음 → ps/cmdline으로 정체 특정.
                            if (!staleExit && (e.getReason() == 10 || e.getReason() == 13) && desc != null) {
                                final String descF = desc;
                                new Thread(() -> {
                                    try {
                                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("pid (\\d+)").matcher(descF);
                                        if (!m.find()) return;
                                        String pid = m.group(1);
                                        String who = AdbAccess.runShell(this, "(ps -p " + pid + " -o NAME= 2>/dev/null; cat /proc/" + pid + "/cmdline 2>/dev/null | tr '\\0' ' ')");
                                        postToD1("FORENSIC", "강제종료 범인 pid=" + pid + " → " + (who == null || who.trim().isEmpty() ? "(이미 종료·adb 미준비)" : who.trim()));
                                        if (who != null && !who.trim().isEmpty()) setBootWhy(bootWhy + " by=" + who.trim().split("\\s+")[0]);
                                    } catch (Throwable ignored) {}
                                }, "kill-culprit").start();
                            }
                        } else sb.append(" · ExitInfo 기록 없음");
                    } catch (Throwable t) { sb.append(" · ExitInfo 실패 ").append(t.getClass().getSimpleName()); }
                } else sb.append(" · ExitInfo 미지원(API<30)");
                // ★v47: 접근성 실제 활성 상태 — 문자열 등록 여부 + 시스템 accessibility_enabled 플래그
                try {
                    android.content.ContentResolver cr = getContentResolver();
                    String en = android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services");
                    String on = android.provider.Settings.Secure.getString(cr, "accessibility_enabled");
                    boolean mine = en != null && en.contains(getPackageName() + "/" + KeepAliveAccessibilityService.class.getName());
                    sb.append(" · 접근성 등록=").append(mine ? "O" : "X").append(" 전역on=").append(on);
                } catch (Throwable ignored) {}
                try { prefs.edit().remove("forensic_life").commit(); } catch (Throwable ignored) {}
                postToD1("FORENSIC", sb.toString());
                // ★736 직전 구간 판정 한 줄(LIFE = 공유판도 바로 올라감, FORENSIC은 링버퍼) — 새샘 아토3(FB1846) "재부팅 뒤 70분 무소식"이 앱이 멈춘 건지 망이 끊긴 건지
                if (hb > 0) {
                    long gap = (now - hb) / 1000, osUp = android.os.SystemClock.elapsedRealtime() / 1000;
                    long okBefore = prevOk > 0 ? (hb - prevOk) / 1000 : -1;
                    String verdict = okBefore < 0 ? "서버 성공 기록 없음" : okBefore > 120 ? "마지막 심박 전 " + (okBefore / 60) + "분간 서버 못 닿음(망·서버 문제로 살아 있었는데 끊김)" : "죽기 직전까지 서버 연결됨(앱이 멈추거나 죽음)";
                    postToD1("LIFE", "직전 구간 — 마지막 심박 " + gap + "초 전 · 그때 망 " + (prevNet.isEmpty() ? "?" : prevNet) + " · 마지막 서버 성공 심박보다 " + (okBefore < 0 ? "?" : okBefore + "초") + " 앞 · OS가동 " + osUp + "s"
                            + (osUp < gap ? "(그사이 헤드유닛 재부팅)" : "") + " → " + verdict);
                }
                // ★v171 릴레이 자체 오토스타트 자동토글 제거 — 이제 별도 '오토스타트 가디언' 앱이 PACKAGE_REPLACED로 전담(중복 팝업 방지). AUTOSTARTFIX 명령은 수동/진단용으로 남김.
            } catch (Throwable ignored) {}
        }, "forensic").start();
    }

    /** ★736 지금 망 한 줄(와이파이·LTE·없음 + 인터넷 확인 여부) — 10초 심박에 같이 저장. */
    private String netSummary() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network n = cm != null ? cm.getActiveNetwork() : null;
            if (n == null) return "없음";
            android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null) return "?";
            String t = c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "와이파이" : c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ? "LTE" : "기타";
            return t + (c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "" : "(인터넷 확인 안 됨)");
        } catch (Throwable t) { return "오류 " + t.getClass().getSimpleName(); }
    }

    // ★606 재시작 사인을 relay_state(boot_why)로 — FORENSIC 로그는 공유판에서 링버퍼라 집계 불가. 시동OFF 직후 재기동 공백(gap)·범인 전수 집계용.
    private volatile String bootWhy = "";
    private void setBootWhy(String s) {
        bootWhy = s;
        if (relay != null) relay.putStatus("boot_why", s);
    }

    private static String exitReasonName(int r) {
        switch (r) {
            case 1: return "EXIT_SELF(자체종료)";
            case 2: return "SIGNALED(시그널킬)";
            case 3: return "LOW_MEMORY(메모리부족)";
            case 4: return "CRASH(자바크래시)";
            case 5: return "NATIVE_CRASH";
            case 6: return "ANR";
            case 9: return "EXCESSIVE_RESOURCE(자원과다)";
            case 10: return "USER_REQUESTED(강제중지!)";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER(시스템킬)";
            case 14: return "FREEZER";
            default: return "UNKNOWN" + r;
        }
    }

    // ★v46: ROM이 자동부여한 WRITE_SECURE_SETTINGS로 접근성 서비스 셀프 활성화(일렉트로 동일 수법 추정).
    //   접근성 서비스는 시스템이 바인딩·자동복구하는 컴포넌트라 DiLink 청소(물리끔)·force-stop(원격 공조끔) 면제 노림.
    //   사용자가 헤드유닛 설정>접근성에 들어갈 필요 없이 앱이 직접 등록.
    // ★788 목록을 정리해 다시 쓰지 않고, 우리 항목이 없을 때만 덧붙인다(ensureA11yListed).
    private void selfEnableAccessibility() {
        try {
            if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                postToD1("FORENSIC", "접근성 셀프활성 불가 — WRITE_SECURE_SETTINGS 미부여");
                return;
            }
            String r = ensureA11yListed("시작");
            String after = android.provider.Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
            postToD1("FORENSIC", "접근성 셀프활성: " + (a11yListed(after, A11Y_COMPONENT_SELF) ? "성공(" + r + ") → " + after : "실패(after=" + after + ")"));
        } catch (Throwable t) {
            postToD1("FORENSIC", "접근성 셀프활성 예외: " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    // ★v216 좀비 a11y 원격 강제 재바인딩 — sniffer a11y를 enabled에서 완전 제거 → 대기(시스템 unbind 인식) → 전체형 하나만 재등록.
    //   중복 등록(축약+전체)이 원인이면 이 정규화로 풀리고, 살아있는 프로세스에도 재연결을 강제한다. 수동 껐다켜기의 원격 대체.
    private String rebindA11y() {
        try {
            if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return "A11YREBIND 불가 — WRITE_SECURE_SETTINGS 미부여";
            String pkg = getPackageName();
            String comp = pkg + "/" + KeepAliveAccessibilityService.class.getName();
            android.content.ContentResolver cr = getContentResolver();
            String cur = android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services");
            if (cur == null) cur = "";
            java.util.ArrayList<String> parts = new java.util.ArrayList<>();
            for (String p : cur.split(":")) {
                if (p.isEmpty()) continue;
                if (p.startsWith(pkg + "/") && p.contains("KeepAliveAccessibilityService")) continue;
                parts.add(p);
            }
            // 1) sniffer a11y 완전 제거
            android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", android.text.TextUtils.join(":", parts));
            Thread.sleep(1800);   // 시스템이 unbind를 인식할 시간
            // 2) 전체형 하나만 재등록 + 전역 플래그 — ★788 다시 읽은 목록에 덧붙인다(1.8초 사이 다른 앱이 넣은 항목을 처음 읽은 값으로 덮어써 지우지 않게)
            String cur2 = android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services");
            if (cur2 == null) cur2 = "";
            if (!a11yListed(cur2, comp)) android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", cur2.isEmpty() ? comp : cur2 + ":" + comp);
            android.provider.Settings.Secure.putString(cr, "accessibility_enabled", "1");
            Thread.sleep(2500);   // rebind 완료 대기
            String after = android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services");
            return "A11YREBIND 완료 → " + after;
        } catch (Throwable t) { return "A11YREBIND 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage(); }
    }

    // ★v44: 종료 직전 수명이벤트를 prefs에 동기 저장 — 죽고 나서 다음 시작 때 reportForensics가 읽음
    private void markLife(String what) {
        try {
            prefs.edit().putString("forensic_life", what + "@"
                    + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date())).commit();
        } catch (Throwable ignored) {}
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        markLife("onTaskRemoved");
        // ★v45 반격: DiLink가 ACC OFF ~1분 뒤 태스크 청소(onTaskRemoved@19:49 실측)로 앱을 죽이고
        //   기존 잡·알람까지 무력화 → 죽기 직전 이 콜백에서 부활 알람을 새로 장전(2s/10s/30s 3중).
        //   setExactAndAllowWhileIdle + getService PI는 프로세스 사후에도 시스템이 서비스를 직접 기동.
        postToD1("FORENSIC", "onTaskRemoved — 태스크 청소 감지, 부활 알람 2s/10s/30s 장전");
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) {
                int fl = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
                long now = System.currentTimeMillis();
                int[] delays = {2000, 10000, 30000};
                for (int i = 0; i < delays.length; i++) {
                    Intent it = new Intent(this, SnifferService.class).setAction(ACTION_WAKE);
                    it.putExtra("src", "taskRemovedRevive" + delays[i]);
                    android.app.PendingIntent pi = android.app.PendingIntent.getService(this, 70 + i, it, fl);
                    am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, now + delays[i], pi);
                }
            }
        } catch (Throwable t) { Log.w(TAG, "부활 알람 장전 실패", t); }
        // 워치독 잡도 즉시 재장전 (ROM이 알람만 취소하는 경우 대비 이중화)
        try { scheduleWatchdogJob(); } catch (Throwable ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onTrimMemory(int level) {
        if (level >= 60) {   // TRIM_MEMORY_MODERATE(60)+ = 킬 후보 진입
            markLife("onTrimMemory" + level);
            postToD1("FORENSIC", "onTrimMemory level=" + level + " — 메모리압박, 킬 임박 신호");
        }
        super.onTrimMemory(level);
    }

    // 알람(백업) — 프로세스가 죽었다 재시작되면 루프를 부활시킨다(setExactAndAllowWhileIdle로 Doze 관통).
    private void runWakeHeartbeat() {
        if (wakeEnabled) startWakeLoop();
        scheduleWakeAlarm(WAKE_INTERVAL_MS);
    }

    private void initCSVFile() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        String filename = "byd-sniff-" + sdf.format(new Date()) + ".csv";

        File dir = getExternalFilesDir(null);
        if (dir == null) {
            dir = getFilesDir();
        }

        csvFile = new File(dir, filename);
        try {
            csvWriter = new FileWriter(csvFile, true);
            csvWriter.write("timestamp_ms,iso_time,device,event,arg0,arg1,arg2,arg3\n");
            csvWriter.flush();

            EventLoggerSingleton.getInstance().setCSVPath(csvFile.getAbsolutePath());
            Log.i(TAG, "CSV file created: " + csvFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to init CSV", e);
        }
    }

    private void buildFeatureIdMap() {
        try {
            // 탑레벨 필드 (int)들 로드
            for (Field field : Class.forName("android.hardware.bydauto.BYDAutoFeatureIds").getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == Integer.TYPE) {
                    try {
                        this.idToName.put(field.getInt(null), field.getName());
                    } catch (Exception ignored) {
                    }
                }
            }

            // 모든 중첩클래스 순회 (Light 포함)
            Class<?> featureIdClass = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            for (Class<?> innerClass : featureIdClass.getDeclaredClasses()) {
                try {
                    for (Field field : innerClass.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) && field.getType() == Integer.TYPE) {
                            try {
                                this.idToName.put(field.getInt(null), field.getName());
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            Log.i(TAG, "Loaded " + this.idToName.size() + " feature IDs.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load feature IDs", e);
        }
    }

    private VehicleContextWrapper vcwRef;   // 창문/공조 등 추가 디바이스 온디맨드 인스턴스화용

    // ★2026-09-17 D5(씨라이언7) 전용 등록 경로 — 아래 D3 경로는 호출 인자로 `new AbsBYDAuto*Listener(){…}`(우리 스텁 상속 익명클래스)를
    //   만들기 때문에, setupDevice 안에서 분기해봐야 이미 늦다(인자 평가가 먼저). 스텁을 APK에서 뺀 뒤 D5에선 그 익명클래스의
    //   상위가 OEM 실클래스로 해석되어 로드/호출 시 터질 수 있으므로, D5는 익명클래스를 아예 만들지 않고 런타임 프록시만 쓴다.
    private void initDevicesD5(VehicleContextWrapper vcw) {
        boolean diag = prefs != null && prefs.getBoolean(KEY_DIAG, false);
        boolean relayOn = prefs != null && prefs.getBoolean(KEY_RELAY, false);
        subscribeRelayDomain("android.hardware.bydauto.adas.BYDAutoADASDevice", "adas");
        subscribeRelayDomain("android.hardware.bydauto.light.BYDAutoLightDevice", "light");
        if (diag || relayOn) {
            subscribeRelayDomain("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice", "instrument");
            // ★547 energy 도메인 구독 추가 — 클러스터 주행모드가 노말/에코를 구분 못 하는 문제(운영자 실측: 노말→'에코', 에코→'에코', 스포츠→'스포츠').
            //   원인은 유일한 소스인 getOperationMode()가 노말·에코 모두 1을 돌려주는 것(로그 400건 분포 1:104 · 2:23 · 3:0).
            //   featureId 읽기도 전멸(READFEAT energy 'OPERATION' 29개 전부 값 없음) → 남은 경로가 **이벤트**인데 이 도메인만 구독에서 빠져 있었다.
            //   구독하면 모드 변경이 clusterRaw("energy:<FEATURE>")에 들어오므로, 실주행 1회로 진짜 신호를 특정할 수 있다.
            subscribeRelayDomain("android.hardware.bydauto.energy.BYDAutoEnergyDevice", "energy");
            subscribeRelayDomain("android.hardware.bydauto.setting.BYDAutoSettingDevice", "setting");
            subscribeRelayDomain("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice", "bodywork");
            subscribeRelayDomain("android.hardware.bydauto.ac.BYDAutoAcDevice", "ac");
            subscribeRelayDomain("android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice", "safetybelt");
            subscribeRelayDomain("android.hardware.bydauto.charging.BYDAutoChargingDevice", "charging");
            subscribeRelayDomain("android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice", "doorlock");
            subscribeRelayDomain("android.hardware.bydauto.power.BYDAutoPowerDevice", "power");
        }
        if (diag) {
            subscribeRelayDomain("android.hardware.bydauto.radar.BYDAutoRadarDevice", "radar");
            subscribeRelayDomain("android.hardware.bydauto.panorama.BYDAutoPanoramaDevice", "panorama");
        }
        if (relay != null) try { relay.putStatus("reg_domains", regDomains.toString()); } catch (Throwable ignored) {}
        Log.i(TAG, "D5 registered domains: " + regDomains);
    }

    private void initDevices() {
        VehicleContextWrapper vcw = new VehicleContextWrapper(this);
        this.vcwRef = vcw;
        if (DiLink5Support.isDiLink5()) { initDevicesD5(vcw); return; }   // ★D5는 익명 리스너 생성 전에 분기
        // 진단모드에서만 전체 도메인 등록. 기본(경보모드)은 ADAS(BSD)+light(깜빡이)만 → 크래시 유발 gearbox/energy 제외
        boolean diag = prefs != null && prefs.getBoolean(KEY_DIAG, false);
        // 릴레이 켜짐이면 instrument/setting도 등록(좌석 안전벨트·도어잠금 읽기용)
        boolean relayOn = prefs != null && prefs.getBoolean(KEY_RELAY, false);

        // ADAS = BSD 신호 (항상)
        setupDevice(vcw, "android.hardware.bydauto.adas.BYDAutoADASDevice",
            "android.hardware.bydauto.adas.AbsBYDAutoADASListener",
            new AbsBYDAutoADASListener() {
                @Override
                public void onDataEventChanged(int featureId, BYDAutoEventValue value) {
                    onVehicleEvent("adas", featureId, value);
                }
            });

        // light = 깜빡이 (항상)
        setupDevice(vcw, "android.hardware.bydauto.light.BYDAutoLightDevice",
            "android.hardware.bydauto.light.AbsBYDAutoLightListener",
            new AbsBYDAutoLightListener() {
                @Override
                public void onDataEventChanged(int featureId, BYDAutoEventValue value) {
                    onVehicleEvent("light", featureId, value);
                }
            });

        // instrument/setting: 진단 또는 릴레이(좌석 안전벨트·도어잠금 읽기)면 등록
        if (diag || relayOn) {
            setupDevice(vcw, "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
                "android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener",
                new AbsBYDAutoInstrumentListener() {
                    @Override public void onDataEventChanged(int featureId, BYDAutoEventValue value) { onVehicleEvent("instrument", featureId, value); }
                });
            setupDevice(vcw, "android.hardware.bydauto.setting.BYDAutoSettingDevice",
                "android.hardware.bydauto.setting.AbsBYDAutoSettingListener",
                new AbsBYDAutoSettingListener() {
                    @Override public void onDataEventChanged(int featureId, BYDAutoEventValue value) { onVehicleEvent("setting", featureId, value); }
                });
        }

        // ★릴레이 전용 도메인 — 조수석·2열 벨트(safetybelt), 배터리(charging), 도어잠금(doorlock), 시동(power).
        // 프로브로 전부 인스턴스화 확인됨. 실패해도 setupDevice가 삼킴(크래시 안전).
        if (relayOn) {
            // ★씨라이언7(DiLink5) 순서 위험 방지 — 아래 setupDevice의 익명 Abs 리스너(new AbsBYDAuto*Listener{})는
            //   컴파일타임 하드참조라, D5 dex에 그 Abs 클래스가 하나라도 없으면 NoClassDefFoundError로 initDevices를
            //   통째로 abort → 블록 끝의 bodywork 구독이 영영 안 되던 문제. D5는 핵심 Bodywork/ac를 먼저 구독.
            boolean d5 = DiLink5Support.isDiLink5();
            if (d5) {
                subscribeRelayDomain("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice", "bodywork");
                subscribeRelayDomain("android.hardware.bydauto.ac.BYDAutoAcDevice", "ac");
            }
            setupDevice(vcw, "android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice",
                "android.hardware.bydauto.safetybelt.AbsBYDAutoSafetyBeltListener",
                new android.hardware.bydauto.safetybelt.AbsBYDAutoSafetyBeltListener() {
                    @Override public void onDataEventChanged(int featureId, BYDAutoEventValue value) { onVehicleEvent("safetybelt", featureId, value); }
                });
            setupDevice(vcw, "android.hardware.bydauto.charging.BYDAutoChargingDevice",
                "android.hardware.bydauto.charging.AbsBYDAutoChargingListener",
                new android.hardware.bydauto.charging.AbsBYDAutoChargingListener() {
                    @Override public void onDataEventChanged(int featureId, BYDAutoEventValue value) { onVehicleEvent("charging", featureId, value); }
                });
            setupDevice(vcw, "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice",
                "android.hardware.bydauto.doorlock.AbsBYDAutoDoorLockListener",
                new android.hardware.bydauto.doorlock.AbsBYDAutoDoorLockListener() {
                    @Override public void onDataEventChanged(int featureId, BYDAutoEventValue value) { onVehicleEvent("doorlock", featureId, value); }
                });
            setupDevice(vcw, "android.hardware.bydauto.power.BYDAutoPowerDevice",
                "android.hardware.bydauto.power.AbsBYDAutoPowerListener",
                new android.hardware.bydauto.power.AbsBYDAutoPowerListener() {
                    @Override public void onDataEventChanged(int featureId, BYDAutoEventValue value) { onVehicleEvent("power", featureId, value); }
                });
            // ★v80 gearbox 이벤트 리스너 — 등록해야 getCurrentGear가 live(주행감지). 크래시 없음 확인(v80 실차).
            //   force-stop 시 같이 죽지만 워치독이 되살리므로 상시 등록해둔다(주차 시 해제 불필요 — 회귀 원인은 워치독 미설치였음).
            registerGearbox();
            registerSpeed();   // ★v144 speed 리스너 등록 — 이벤트에서 속도 직접 캡처(게터 stale 우회) + 리스너 등록 자체가 게터도 live화. ICC 정차 제외 정상화용.
            // 선쉐이드 위치(bodywork) + 공기순환(ac) — SDK에 Abs 리스너가 없어 Proxy로 등록. 이벤트는 onVehicleEvent→relay state.
            //   (D5는 위에서 먼저 구독했으므로 중복 방지 위해 D3만 여기서 등록)
            if (!d5) {
                subscribeRelayDomain("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice", "bodywork");
                subscribeRelayDomain("android.hardware.bydauto.ac.BYDAutoAcDevice", "ac");
                // ★606 D3 에코/노말 구분 실험(운영자 승인 09-27) — getOperationMode가 에코·노말 모두 같은 값이라 energy 이벤트로 실신호 특정.
                //   energy는 과거 "크래시 유발"로 제외됐던 도메인이라 운영자판(운영자 차 1대)만. 이상 시 즉시 되돌림.
                if (BuildConfig.IS_OPERATOR) subscribeRelayDomain("android.hardware.bydauto.energy.BYDAutoEnergyDevice", "energy");
            }
        }

        // RAW 전체캡처(진단) 모드: 크래시 유발 gearbox/energy 제외, 나머지 안전 도메인 전부 등록 + 필터 OFF
        if (diag) {
            setupDevice(vcw, "android.hardware.bydauto.radar.BYDAutoRadarDevice",
                "android.hardware.bydauto.radar.AbsBYDAutoRadarListener",
                new AbsBYDAutoRadarListener() {
                    @Override public void onDataEventChanged(int featureId, BYDAutoEventValue value) { onVehicleEvent("radar", featureId, value); }
                });
            setupDevice(vcw, "android.hardware.bydauto.panorama.BYDAutoPanoramaDevice",
                "android.hardware.bydauto.panorama.AbsBYDAutoPanoramaListener",
                new AbsBYDAutoPanoramaListener() {
                    @Override public void onDataEventChanged(int featureId, BYDAutoEventValue value) { onVehicleEvent("panorama", featureId, value); }
                });
        }

        Log.i(TAG, "Registered " + this.devices.size() + " devices (diag=" + diag + ").");
    }

    private void setupDevice(VehicleContextWrapper vcw, String deviceClassName,
                             String listenerClassName, Object listener) {
        // ★DiLink5/DiLink3 확실한 분기(2026-09-09): D5는 리스너가 obfuscated 타입(a.b.d 등)이라, 컴파일타임 Abs 리스너(new AbsBYDAuto*Listener{})를
        //   registerListener에 넣으면 SDK가 AbsTsMyCar.isOptimization서 a.b.d로 캐스팅 시도→ClassCastException 메인스레드 크래시(씨라이언7 393-share 무한 크래시 실측).
        //   D5는 registerListener 파라미터 타입을 런타임 발견해 맞는 프록시(인터페이스=Proxy·추상클래스=dexmaker) 등록하는 subscribeRelayDomain으로 위임(bodywork/ac가 쓰는 검증된 경로).
        if (DiLink5Support.isDiLink5()) { subscribeRelayDomain(deviceClassName, domainOf(deviceClassName)); return; }
        try {
            Class<?> deviceClass = Class.forName(deviceClassName);
            Object device = deviceClass.getMethod("getInstance", Context.class).invoke(null, vcw);
            if (device != null) {
                deviceClass.getMethod("registerListener", Class.forName(listenerClassName))
                    .invoke(device, listener);
                this.devices.add(device);
                if (deviceClassName.contains(".adas.")) adasDevice = device;
                else if (deviceClassName.contains(".setting.")) settingDevice = device;
                else if (deviceClassName.contains(".instrument.")) instrumentDevice = device;
                else if (deviceClassName.contains(".safetybelt.")) safetybeltDevice = device;
                else if (deviceClassName.contains(".doorlock.")) doorlockDevice = device;
                else if (deviceClassName.contains(".charging.")) chargingDevice = device;
                Log.i(TAG, "Registered listener for " + deviceClassName);
                regDomains.append(domainOf(deviceClassName)).append("+;");
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to setup device: " + deviceClassName, e);
            regDomains.append(domainOf(deviceClassName)).append("!;");   // 리스너명 틀림 등 → 표시
        }
    }

    // ★2026-09-19 registerListener 메서드 탐색 — 인터페이스 리스너 우선(D3 기존 동작 보존), 없으면 추상클래스(D5). D5 실구현 폴백에서 dev2 클래스에도 재사용.
    private static java.lang.reflect.Method findRegisterListener(Class<?> dc) {
        for (java.lang.reflect.Method m : dc.getMethods())
            if (m.getName().equals("registerListener") && m.getParameterTypes().length == 1 && m.getParameterTypes()[0].isInterface()) return m;
        for (java.lang.reflect.Method m : dc.getMethods())
            if (m.getName().equals("registerListener") && m.getParameterTypes().length == 1) return m;
        return null;
    }
    // ★프록시 리스너 생성 — 인터페이스=표준 Proxy, 추상클래스=dexmaker ProxyBuilder. handler 재사용(1차 dev·폴백 dev2 공용).
    private static Object makeRelayListener(Class<?> lt, java.lang.reflect.InvocationHandler handler) throws Exception {
        return lt.isInterface()
                ? java.lang.reflect.Proxy.newProxyInstance(lt.getClassLoader(), new Class[]{lt}, handler)
                : com.android.dx.stock.ProxyBuilder.forClass(lt).handler(handler).build();
    }

    // Abs 리스너 클래스가 없는 도메인(bodywork/ac)을 Proxy 리스너로 등록 — 이벤트는 onVehicleEvent로.
    // (진단용 subscribeDomain은 매 이벤트 postToD1로 스팸 → 상태용은 여기로 분리해 D1 안 더럽힘)
    private void subscribeRelayDomain(String className, String dom) {
        try {
            // ★2026-09-19 D5는 BeetleLauncher 방식 별도 로더(InMemoryDexClassLoader + HiddenApiBypass)로 로드 —
            //   append+setHiddenApiExemptions("L")은 registerListener가 Stub!로 가려졌음(v538 전 도메인 실패). D3는 Class.forName(앱로더) 그대로.
            Class<?> dc = DiLink5Support.isDiLink5() ? DiLink5Support.loadD5Class(this, className) : Class.forName(className);
            if (dc == null) { regDomains.append(dom).append("!d5load;"); postToD1("D5FB", dom + " ← D5 loader 클래스 로드 실패 (" + DiLink5Support.loaderReport + ")"); return; }
            Object dev = dc.getMethod("getInstance", Context.class).invoke(null, vcwRef);
            if (dev == null) { regDomains.append(dom).append("!;"); return; }
            // ★씨라이언7(DiLink5)은 Bodywork/ac가 인터페이스가 아니라 추상클래스(AbsBYDAuto*Listener)로 이벤트를 push한다.
            //   기존엔 isInterface() 리스너만 찾아 D5선 reg=null로 구독 실패(이벤트 전멸)였다. 인터페이스=표준 Proxy,
            //   추상클래스=dexmaker ProxyBuilder로 런타임 서브클래싱해 둘 다 등록(오토전비 TripService 검증 패턴).
            // 인터페이스 리스너 우선(D3 기존 동작 정확 보존) → 없으면 추상클래스(D5) 수용.
            java.lang.reflect.Method reg = findRegisterListener(dc);
            if (reg == null) { regDomains.append(dom).append("!;"); return; }
            Class<?> lt = reg.getParameterTypes()[0];
            java.lang.reflect.InvocationHandler handler = (p, method, args) -> {
                String mn = method.getName();
                if (mn.equals("onDataEventChanged") && args != null && args.length == 2) {
                    // ★args[1]은 OEM BYDAutoEventValue다(우리 스텁 아님) — 스텁을 APK에서 뺐으므로 캐스트가 성립하지만,
                    //   D5 실클래스가 다른 형태일 수 있으니 실패해도 죽지 않게 감싼다.
                    try { onVehicleEvent(dom, (Integer) args[0], (BYDAutoEventValue) args[1]); } catch (Throwable t) { onVehicleEventRaw(dom, mn, args); }
                    return null;
                }
                if (mn.equals("hashCode")) return System.identityHashCode(p);
                if (mn.equals("equals")) return args != null && p == args[0];
                if (mn.equals("toString")) return "relayproxy-" + dom;
                // ★2026-09-17 D5 타입별 콜백 수용 — 비틀런처 ProbeADASListener 실측상 D5 ADAS는 onBSDStateChanged(int)·
                //   onLKSModeChanged(int)·onLDSWTypeChanged(int) 등으로도 이벤트를 보낸다. 예전엔 여기서 전부 버려서
                //   설령 구독이 되어도 BSD·차선이 死였다. 우선 전부 수용·로깅하고, 실차 로그를 보고 매핑을 확정한다.
                if (mn.startsWith("on") && args != null && args.length >= 1) onVehicleEventRaw(dom, mn, args);
                return null;
            };
            Object useDev = dev;
            String tag = lt.isInterface() ? "+" : "+dex";
            try {
                reg.invoke(dev, makeRelayListener(lt, handler));
            } catch (Throwable primary) {
                // ★2026-09-19 D5 실구현 폴백 — data.collect dex의 registerListener가 스텁(RuntimeException:Stub!)이라
                //   getInstance는 되는데 이벤트 등록만 전멸(09-19 v536 cause 로그 확정). 실구현을 가진 소유 시스템앱
                //   (com.byd.hvac 등, Power 제공 실적) dex로 device를 다시 얻어 재등록한다. 부팅 1회·재시도 루프 없음.
                //   D3(부트클래스패스 실구현)·SecurityException(권한벽, 앱 바꿔도 동일)은 폴백 안 함.
                if (!DiLink5Support.isDiLink5()) throw primary;
                Throwable pr = primary; while (pr.getCause() != null && pr.getCause() != pr) pr = pr.getCause();
                if (pr instanceof SecurityException) { regDomains.append(dom).append("!perm;"); postToD1("D5FB", dom + " ← 권한벽(SecurityException) 폴백스킵 — 매니페스트 권한 필요"); return; }
                Object dev2 = DiLink5Support.deviceFromSystemApp(this, className, vcwRef);
                String rep = DiLink5Support.lastSysReport;
                if (dev2 == null) { regDomains.append(dom).append("!fb;"); postToD1("D5FB", dom + " ← 실구현 소유앱 없음 (" + rep + ")"); return; }
                String pk = rep.contains("←") ? rep.substring(rep.lastIndexOf('←') + 1).trim() : "?";
                if (pk.startsWith("com.byd.")) pk = pk.substring(8);
                java.lang.reflect.Method reg2 = findRegisterListener(dev2.getClass());
                if (reg2 == null) { regDomains.append(dom).append("!fbnoreg;"); postToD1("D5FB", dom + " ← " + pk + " registerListener 없음"); return; }
                try {
                    reg2.invoke(dev2, makeRelayListener(reg2.getParameterTypes()[0], handler));
                } catch (Throwable fb) {
                    Throwable fr = fb; while (fr.getCause() != null && fr.getCause() != fr) fr = fr.getCause();
                    regDomains.append(dom).append("!fb").append(fr.getClass().getSimpleName()).append(";");
                    postToD1("D5FB", dom + " ← " + pk + " FAIL:" + fr.getClass().getSimpleName() + (fr.getMessage() != null ? ":" + fr.getMessage().replace('\n', ' ') : ""));
                    return;
                }
                useDev = dev2; tag = "+" + pk;
                postToD1("D5FB", dom + " ← " + pk + " OK (실구현 재등록)");
            }
            this.devices.add(useDev);
            // ★D5 위임 경로(setupDevice→여기)서도 폴링/제어용 디바이스 핸들 보존 — D3 setupDevice와 동일 필드 세팅(없으면 D5서 doorlock/charging 등 읽기 死). 폴백 시 실구현 dev로 세팅(폴링도 실구현 경유).
            switch (dom) {
                case "adas": adasDevice = useDev; break;
                case "setting": settingDevice = useDev; break;
                case "instrument": instrumentDevice = useDev; break;
                case "safetybelt": safetybeltDevice = useDev; break;
                case "doorlock": doorlockDevice = useDev; break;
                case "charging": chargingDevice = useDev; break;
                default: break;
            }
            regDomains.append(dom).append(tag).append(";");
            Log.i(TAG, "relay-subscribed " + className + " " + tag);
        } catch (Throwable t) {
            Log.w(TAG, "subscribeRelayDomain " + className, t);
            // ★2026-09-17 진단 — 예전엔 '!' 한 글자만 남겨 D5 전멸의 원인(클래스 못 찾음/인스턴스 null/프록시 생성 실패)을 구분할 수 없었다.
            //   예외 종류를 같이 남겨 다음 부팅에 차량이 스스로 원인을 relay_state.reg_domains로 올리게 한다.
            // ★2026-09-19 getCause 언랩 — InvocationTargetException 등 껍데기는 getCause()가 진짜 원인(D5 이벤트 등록 실패의 실제 예외). 루트까지 벗겨 클래스+메시지+스택 첫 프레임을 남긴다. reg_domains는 축약, 상세는 D5EVT로.
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String why = root.getClass().getSimpleName();
            String msg = root.getMessage();
            if (msg != null && !msg.isEmpty()) why += ":" + msg.replace(';', ',').replace('\n', ' ');
            StackTraceElement[] st = root.getStackTrace();
            if (st.length > 0) { String cn = st[0].getClassName(); why += "@" + cn.substring(cn.lastIndexOf('.') + 1) + "." + st[0].getMethodName() + ":" + st[0].getLineNumber(); }
            if (why.length() > 120) why = why.substring(0, 120);
            regDomains.append(dom).append("!").append(why).append(";");
            if (t != root || st.length > 0) postToD1("D5EVT", "구독실패 " + dom + " outer=" + t.getClass().getSimpleName() + " root=" + why);
        }
    }

    // ★2026-09-17 D5 타입별 콜백 원본 로깅 — onBSDStateChanged(int) 등 onDataEventChanged가 아닌 경로로 오는 이벤트를 그대로 남긴다.
    //   값 의미(예: BSD state의 int 인코딩)는 실차 로그를 보고 확정한 뒤 handleBsd/handleLaneEvent에 연결한다. 지금은 관측 단계.
    private final java.util.Map<String, Long> rawEvtLastMs = new java.util.concurrent.ConcurrentHashMap<>();
    // ★이벤트 객체 리플렉션 덤프(2026-09-17) — BYDAutoEvent는 toString이 기본(Object@hash)이라 featureId·value가 안 보였다(로그분석 실측 39건 전부 @hash).
    //   필드(상위 클래스까지)와 인자 없는 getter(get*/is*/int*/float*/string*)를 훑어 이름=값으로 펼친다. 중첩 값(BYDAutoEventValue 등)은 1단계만 더.
    private static String describeEvt(Object o, int depth) {
        if (o == null) return "null";
        Class<?> c = o.getClass();
        if (o instanceof Number || o instanceof CharSequence || o instanceof Boolean || c.isPrimitive() || c.isEnum()) return String.valueOf(o);
        if (c.isArray()) { StringBuilder a = new StringBuilder("["); int n = java.lang.reflect.Array.getLength(o); for (int i = 0; i < Math.min(n, 8); i++) { if (i > 0) a.append(","); a.append(describeEvt(java.lang.reflect.Array.get(o, i), depth + 1)); } if (n > 8) a.append(",…"); return a.append("]").toString(); }
        if (depth > 1) return c.getSimpleName() + "@";
        StringBuilder sb = new StringBuilder(c.getSimpleName()).append("{");
        int n = 0;
        try {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try { f.setAccessible(true); Object v = f.get(o); if (n++ > 0) sb.append(","); sb.append(f.getName()).append("=").append(describeEvt(v, depth + 1)); } catch (Throwable ignore) {}
                    if (n >= 12) break;
                }
            }
            if (n == 0) for (java.lang.reflect.Method m : c.getMethods()) {   // 필드가 없으면 getter로
                String mn = m.getName();
                if (m.getParameterTypes().length != 0 || m.getDeclaringClass() == Object.class) continue;
                if (!(mn.startsWith("get") || mn.startsWith("is") || mn.endsWith("Value"))) continue;
                try { Object v = m.invoke(o); if (n++ > 0) sb.append(","); sb.append(mn).append("=").append(describeEvt(v, depth + 1)); } catch (Throwable ignore) {}
                if (n >= 12) break;
            }
        } catch (Throwable ignore) {}
        return sb.append("}").toString();
    }
    // ★2026-09-17 19:00 긴급수정 — v522~526의 이 로깅이 운영자 D3에서 20분에 8,203건(초당 ~7 POST)을 쏟아내 릴레이를 스스로 마비시켰다
    //   (전방 감지가 30초마다 "좀비 → 재무장"만 반복하며 프레임을 못 받음). 원인: 중복 억제 키가 전체 문자열이었는데 BYDAutoEvent@hash가
    //   객체마다 달라 억제가 한 번도 안 걸림 + D3는 bodywork/ac 프록시가 onDataChanged를 초당 수 회 호출. 이 로깅은 D5 값 인코딩 관측용이므로
    //   ① D5에서만 ② 도메인.메서드 단위 10초 억제 ③ 분당 30건 상한.
    private long rawEvtMinuteStart = 0; private int rawEvtMinuteCount = 0;
    private void onVehicleEventRaw(String dom, String method, Object[] args) {
        try {
            if (!DiLink5Support.isDiLink5()) return;
            // ★636 씨라7 BSD 경보 연결(운영자 승인 09-28) — dipilot.onBlindMonitorStateChanged1: 2=감지·1=안전(D3 ADAS_BSD_STATE와 같은 인코딩, 214건 실측).
            //   좌우 구분 없음. 로그 억제(아래) 전에 처리해 경보는 억제와 무관하게 즉시.
            if ("dipilot".equals(dom) && "onBlindMonitorStateChanged1".equals(method) && args != null && args.length > 0 && args[0] instanceof Number) {
                int bv = ((Number) args[0]).intValue();
                if (bv == 1 || bv == 2) handleBsd(bv);
            }
            long now = System.currentTimeMillis();
            String key = dom + "." + method;
            Long last = rawEvtLastMs.get(key);
            if (last != null && now - last < 10000) return;
            if (now - rawEvtMinuteStart > 60000) { rawEvtMinuteStart = now; rawEvtMinuteCount = 0; }
            if (++rawEvtMinuteCount > 30) return;
            rawEvtLastMs.put(key, now);
            StringBuilder sb = new StringBuilder(key).append("(");
            for (int i = 0; i < args.length; i++) { if (i > 0) sb.append(","); sb.append(describeEvt(args[i], 0)); }
            sb.append(")");
            keepAliveUntil = now + maxKeepAliveMs;
            postToD1("D5EVT", sb.toString());
        } catch (Throwable ignored) {}
    }

    private final StringBuilder regDomains = new StringBuilder();
    private static String domainOf(String cls) {
        String[] p = cls.split("\\.");
        return p.length >= 2 ? p[p.length - 2] : cls;   // ...bydauto.<domain>.BYDAuto...
    }



    private String pollGet(Object device, int featureId) {
        try {
            for (Method m : device.getClass().getMethods()) {
                if (!m.getName().equals("get")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && (p[0] == Integer.TYPE || p[0] == Integer.class)) {
                    Object def = (p[1] == Integer.TYPE || p[1] == Integer.class) ? (Integer) (-1)
                            : (p[1] == Boolean.TYPE || p[1] == Boolean.class) ? Boolean.FALSE
                            : (p[1] == Long.TYPE) ? (Long) (-1L) : null;
                    return String.valueOf(m.invoke(device, featureId, def));
                } else if (p.length == 1 && (p[0] == Integer.TYPE || p[0] == Integer.class)) {
                    return String.valueOf(m.invoke(device, featureId));
                }
            }
        } catch (Throwable t) {
            return "ERR:" + t.getClass().getSimpleName();
        }
        return "no-get";
    }

    // ── 릴레이용 좌석 안전벨트·도어잠금 폴링 ──
    // 좌석 벨트 중 운전석(MAIN)만 onDataEventChanged 이벤트를 쏨 → 조수석·2열은 여기서 값을 직접 읽어 relay로.
    private final Map<Integer, String> relayPollTargets = new java.util.LinkedHashMap<>();






    // registerListener/2 오버로드를 찾아 (listener, int[]) 순서 자동감지 후 호출
    private void trySubscribe(Object dev, String tag, int[] ids, Object listener) {
        if (dev == null) return;
        for (java.lang.reflect.Method m : dev.getClass().getMethods()) {
            if (!m.getName().equals("registerListener")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 2) continue;
            // 파라미터 타입 로깅
            logEntry(new LogEntry(System.currentTimeMillis(), "probe", "REGSIG", tag + ": registerListener(" + p[0].getSimpleName() + "," + p[1].getSimpleName() + ")"));
            int arr = -1, lis = -1;
            for (int i = 0; i < 2; i++) {
                if (p[i] == int[].class) arr = i;
                else if (p[i].isInstance(listener)) lis = i;
            }
            try {
                if (arr >= 0 && lis >= 0) {
                    Object[] args = new Object[2];
                    args[arr] = ids; args[lis] = listener;
                    m.invoke(dev, args);
                    logEntry(new LogEntry(System.currentTimeMillis(), "probe", "REGOK", tag + " registerListener/2(ids[" + ids.length + "]) 구독 성공"));
                    if (relay != null) relay.setDiag("sub", tag + "=OK|");
                    return;
                }
            } catch (Throwable t) {
                logEntry(new LogEntry(System.currentTimeMillis(), "probe", "REGERR", tag + ":" + t));
            }
        }
        logEntry(new LogEntry(System.currentTimeMillis(), "probe", "REGNONE", tag + " registerListener/2(int[],listener) 없음"));
    }


    // ── 진단(v9): 8개 디바이스 전부 + ADAS BSD + 전체 feature 덤프 (완전 전수) ──
    private void probeV9() {
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (Exception ignored) {}
            VehicleContextWrapper vcw = new VehicleContextWrapper(this);
            String[] doms = { "adas.BYDAutoADASDevice", "safetybelt.BYDAutoSafetyBeltDevice",
                "charging.BYDAutoChargingDevice", "power.BYDAutoPowerDevice", "energy.BYDAutoEnergyDevice",
                "bodywork.BYDAutoBodyworkDevice", "doorlock.BYDAutoDoorLockDevice", "tyre.BYDAutoTyreDevice",
                "statistic.BYDAutoStatisticDevice", "instrument.BYDAutoInstrumentDevice", "setting.BYDAutoSettingDevice" };
            StringBuilder occ3 = new StringBuilder(), bsd2 = new StringBuilder();
            for (String d : doms) {
                String tag = d.substring(0, d.indexOf('.'));
                Object dev;
                try {
                    Class<?> k = Class.forName("android.hardware.bydauto." + d);
                    dev = k.getMethod("getInstance", android.content.Context.class).invoke(null, vcw);
                } catch (Throwable t) { continue; }
                if (dev == null) continue;
                for (java.lang.reflect.Method mm : dev.getClass().getMethods()) {
                    if (mm.getParameterTypes().length != 0) continue;
                    String n = mm.getName();
                    if (!(n.startsWith("get") || n.startsWith("is") || n.startsWith("has")) || n.equals("getClass")) continue;
                    String val;
                    try {
                        Object v = mm.invoke(dev);
                        val = v != null && v.getClass().isArray() ? arrToStr(v) : String.valueOf(v);
                    } catch (Throwable t) { continue; }
                    if (val.length() > 50) val = val.substring(0, 50);
                    logEntry(new LogEntry(System.currentTimeMillis(), "probe", "DEVGET", tag + "." + n + "=" + val));
                    String u = (tag + "." + n).toUpperCase();
                    if (u.contains("BELT") || u.contains("SEAT") || u.contains("OCCUP") || u.contains("PASSENGER")
                            || u.contains("REAR") || u.contains("CHILD") || u.contains("PRESENCE") || u.contains("MSR")) {
                        if (occ3.length() < 1400) occ3.append(tag).append(".").append(n).append("=").append(val).append("|");
                    }
                    if (tag.equals("adas") && (u.contains("BSD") || u.contains("DOW") || u.contains("LCA")
                            || u.contains("RCTA") || u.contains("CST") || u.contains("BLIND") || u.contains("WARN")
                            || u.contains("LEFT") || u.contains("RIGHT") || u.contains("123"))) {
                        if (bsd2.length() < 1400) bsd2.append(n).append("=").append(val).append("|");
                    }
                }
            }
            if (relay != null) {
                relay.setDiag("occ3", occ3.length() > 0 ? occ3.toString() : "none");
                relay.setDiag("bsd2", bsd2.length() > 0 ? bsd2.toString() : "none");
            }
            int c = 0;
            for (Map.Entry<Integer, String> e : idToName.entrySet()) {
                logEntry(new LogEntry(System.currentTimeMillis(), "probe", "FEAT", e.getValue() + "=" + e.getKey())); c++;
            }
            Log.i(TAG, "probeV9 done occ3=" + occ3.length() + " bsd2=" + bsd2.length() + " feat=" + c);
        }, "probe-v9").start();
    }
    private static String arrToStr(Object arr) {
        try {
            if (arr instanceof int[]) { StringBuilder s = new StringBuilder("["); for (int x : (int[]) arr) s.append(x).append(","); return s.append("]").toString(); }
            if (arr instanceof boolean[]) { StringBuilder s = new StringBuilder("["); for (boolean x : (boolean[]) arr) s.append(x ? 1 : 0).append(","); return s.append("]").toString(); }
            if (arr instanceof byte[]) { StringBuilder s = new StringBuilder("b["); for (byte x : (byte[]) arr) s.append(x & 0xFF).append(","); return s.append("]").toString(); }
        } catch (Throwable ignored) {}
        return "arr";
    }


    private String invokeNoArg(Object dev, String method) {
        try { return String.valueOf(dev.getClass().getMethod(method).invoke(dev)); }
        catch (Throwable t) { return "?"; }
    }


    // ── 진단: instrument/setting 실제 이벤트 name=value 캡처 → 릴레이로(배터리/주행거리 정확 feature 확정용) ──
    private volatile boolean evtCapAll = false;   // ★제어 실험실 — 미지 featureId(idXXXX)도 캡처(순정 조작 시 뜨는 새 id 발견용). 기본 off(노이즈 방지)
    private volatile int setRetLog = 0;   // ★제어 실험실 — set() 반환형/값 1회 로그(void면 sweep 판정은 육안, int면 1/0/-1 실판정)
    private final java.util.LinkedHashMap<String, String> recentEvts = new java.util.LinkedHashMap<>();
    private void captureEvt(String name, String val) {
        synchronized (recentEvts) {
            recentEvts.remove(name);
            recentEvts.put(name, val);
            while (recentEvts.size() > 30) recentEvts.remove(recentEvts.keySet().iterator().next());
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : recentEvts.entrySet()) {
                if (sb.length() > 700) break;
                sb.append(e.getKey().replace("INSTRUMENT_", "I_").replace("SETTING_", "S_")).append("=").append(e.getValue()).append("|");
            }
            if (relay != null) relay.setDiag("evt", sb.toString());
        }
    }

    // ── 진단(전용): 벨트/도어잠금/충전 이벤트를 노이즈에 안 밀리게 별도 채널로 → 릴레이 _belt/_lock/_chg ──
    private final java.util.LinkedHashMap<String, String> beltEvts = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<String, String> lockEvts = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<String, String> chgEvts = new java.util.LinkedHashMap<>();
    private void captureDom(java.util.LinkedHashMap<String, String> m, String diagKey, String name, String val) {
        synchronized (m) {
            m.remove(name); m.put(name, val);
            while (m.size() > 15) m.remove(m.keySet().iterator().next());
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (sb.length() > 700) break;
                sb.append(e.getKey()).append("=").append(e.getValue()).append("|");
            }
            if (relay != null) relay.setDiag(diagKey, sb.toString());
        }
    }

    private void tryPollInto(int id, Object dev, String tag, java.util.LinkedHashMap<String, String> m, String key) {
        if (dev == null) return;
        String v = pollGet(dev, id);
        if (v == null || v.equals("no-get") || v.startsWith("ERR") || v.equals("-1")) return;
        captureDom(m, key, tag + ":" + idToName.getOrDefault(id, "id" + id), v);   // 유효값이면 캡처(어느 디바이스가 읽히는지)
    }

    // ── 진단(BSD 좌우): ADAS 사각지대/도어경보 이벤트 name=value 캡처 → 릴레이 _bsd ──
    private final java.util.LinkedHashMap<String, String> bsdEvts = new java.util.LinkedHashMap<>();
    private void captureBsd(String name, String val) {
        synchronized (bsdEvts) {
            bsdEvts.remove(name);
            bsdEvts.put(name, val);
            while (bsdEvts.size() > 20) bsdEvts.remove(bsdEvts.keySet().iterator().next());
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : bsdEvts.entrySet()) {
                if (sb.length() > 700) break;
                sb.append(e.getKey().replace("ADAS_", "A_")).append("=").append(e.getValue()).append("|");
            }
            if (relay != null) relay.setDiag("bsd", sb.toString());
        }
    }

    // ★715 BSD 좌우 관측(피드백분석 4abd18 요청, 운영자 10-03 승인) — 돌핀 BSD는 좌우 합산(ADAS_BSD_STATE) 하나라 반대편 차에도 울렸다.
    //   LCA 좌/우 경고(ADAS_RIGHT_RADAR_LCA_WARNINGLEFT 0x41800010 · ..._WARNINGRIGHT 0x41800012)나 이름에 LCA·DOW·RCTA·BLIND·RADAR가 든
    //   레이더 이벤트가 실제로 오는지 D1(BSDLR)에 남긴다. captureBsd는 릴레이 메모리 최근 20개뿐이라 서버에서 다시 확인할 수 없었다.
    //   관측 전용(경보 동작 무변경). 키별 값이 바뀔 때만, 분당 30줄 상한.
    private final java.util.Map<String, Integer> bsdLrLast = new java.util.concurrent.ConcurrentHashMap<>();
    private long bsdLrMinStart = 0; private int bsdLrMinCount = 0;
    private void bsdLrObserve(int featureId, String featureName, int intVal) {
        String u = featureName.toUpperCase();
        if (!(featureId == 1098907664 || featureId == 1098907666 || u.contains("LCA") || u.contains("DOW") || u.contains("RCTA") || u.contains("BLIND") || u.contains("RADAR"))) return;
        Integer pv = bsdLrLast.put(featureName, intVal);
        if (pv != null && pv == intVal) return;
        long now = System.currentTimeMillis();
        synchronized (bsdLrLast) {
            if (now - bsdLrMinStart > 60_000) { bsdLrMinStart = now; bsdLrMinCount = 0; }
            if (++bsdLrMinCount > 30) return;
        }
        Object tf = relay != null ? relay.getStatus("turnflash") : null, sp = relay != null ? relay.getStatus("speed") : null;
        int t = (tf instanceof Integer) ? (Integer) tf : -1;
        String side = t == 2 ? "좌" : t == 4 ? "우" : t == 6 ? "비상" : "없음(" + tf + ")";
        postToD1("BSDLR", featureName + "(" + featureId + ")=" + intVal + " (이전 " + pv + ") · BSD=" + lastBsdValue + " · 깜빡이 " + (turnSignalActive ? "켬" : "끔") + "/" + side + " · 속도 " + sp);
    }
    // ★715 BSD 좌우 관측 — 차종별로 LCA 좌/우 상수가 SDK에 있는지(릴레이 시작 1회).
    private void bsdLrConstCheck() {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> a = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds$Adas");
            for (String n : new String[]{"ADAS_RIGHT_RADAR_LCA_WARNINGLEFT", "ADAS_RIGHT_RADAR_LCA_WARNINGRIGHT"}) {
                try { sb.append(n).append("=").append(a.getDeclaredField(n).getInt(null)).append(" "); }
                catch (NoSuchFieldException e) { sb.append(n).append("=없음 "); }
            }
        } catch (ClassNotFoundException e) { sb.append("BYDAutoFeatureIds$Adas 클래스 없음 "); }
        catch (Throwable t) { sb.append("확인 실패 ").append(t.getClass().getSimpleName()).append(" "); }
        sb.append("· 이름표 0x41800010=").append(idToName.getOrDefault(1098907664, "없음")).append(" 0x41800012=").append(idToName.getOrDefault(1098907666, "없음"));
        postToD1("BSDLR", "시작 — LCA 좌우 상수 " + sb + " · " + (DiLink5Support.isDiLink5() ? "D5" : "D3"));
    }

    // 설정 변경 시 릴레이 재시작(서비스 재시작 없이)
    public void restartRelay() {
        if (relay != null) { relay.stop(); relay = null; }
        startRelay();
    }
    public boolean relayRunning() { return relay != null; }   // ★MainActivity가 이미 도는 릴레이를 불필요하게 재생성(state 유실)하지 않게(2026-09-10)

    // 명령 디스패치 — 워커 큐(RelayClient)와 BLE 명령 채널(v107)이 공용. null=성공, 그 외=에러 문자열.
    // ★Phase3a 릴레이 클라우드 직접호출(BydCloud) — 세션(토큰)은 아이폰이 BLE로 전달. 후진 비상등 등에 재사용.
    private com.cseini.bydsniffer.cloud.BydCloud cloud;
    private com.cseini.bydsniffer.cloud.BangcleTables cloudTables;
    private volatile String cloudVin = "", cloudPin = "";
    private volatile long afterblowUntil = 0;   // ★AFTERBLOWSEQ 재점화 가드 — 진행중 OPENAIR 재점화 금지(미러 펴짐 방지). 릴레이가 소유(iOS 아님).

    private String setCloudSession(org.json.JSONObject p) {
        try {
            if (p == null) return "params 없음";
            String user = p.optString("user", "");        // 이메일(imeiMD5=md5(user) — 아이폰 로그인과 동일해야 함)
            String userId = p.optString("userId", "");
            String signTok = p.optString("signToken", "");
            String encryTok = p.optString("encryToken", "");
            String vin = p.optString("vin", "");
            String pin = p.optString("pin", "");
            if (userId.isEmpty() || signTok.isEmpty() || encryTok.isEmpty()) return "세션정보 불완전";
            if (cloudTables == null) cloudTables = com.cseini.bydsniffer.cloud.BangcleTables.loadFromAssets(this, "bangcle_tables.bin");
            com.cseini.bydsniffer.cloud.BydCloud c = new com.cseini.bydsniffer.cloud.BydCloud("KR", cloudTables);
            c.restoreSession(user.isEmpty() ? userId : user, userId, signTok, encryTok);
            cloud = c; cloudVin = vin; cloudPin = pin;
            String device = p.optString("device", "");   // ★#1 세션 소유자 기기 식별(폰마다 고유 identifierForVendor) — 계정 공유해도 판별 가능
            if (prefs != null) prefs.edit()
                    .putString("cloud_user", user).putString("cloud_uid", userId)
                    .putString("cloud_sign", signTok).putString("cloud_enc", encryTok)
                    .putString("cloud_vin", vin).putString("cloud_pin", pin)
                    .putString("cloud_device", device).apply();
            postToD1("CLOUD", "세션 저장 완료 uid=" + userId + " vin말미=" + (vin.length() > 6 ? vin.substring(vin.length() - 6) : vin));
            return null;
        } catch (Throwable t) { return "세션저장 실패:" + t.getMessage(); }
    }

    // ★로그아웃 시 iOS가 보냄 — 릴레이가 쥔 클라우드 토큰(+저장 비번) 무효화. 상시생존이라 인메모리 cloud 객체도
    //   반드시 null 처리해야(prefs만 지우면 cloudFlash가 cloud!=null이라 restore 안 타고 그대로 쏨) 후진 비상등이 멈춘다.
    private String clearCloudSession(org.json.JSONObject p) {
        // ★#1 세션 소유권 검증 — 구주인 로그아웃 CLEARCLOUDSESS가 신주인 SETCLOUDSESS 토큰을 지우던 CRITICAL 버그 방지.
        //   판별자=device(폰마다 고유, prefs가 durable — 릴레이 재시작으로 SETCLOUDSESS↔CLEARCLOUDSESS 사이 죽어도 살아남음).
        //   현재 세션 device와 들어온 device가 둘 다 있고 다르면 → 남의 세션이니 삭제 거부(멱등, null=성공반환으로 iOS 재시도X).
        //   incoming 빈값(레거시 앱)이면 그냥 삭제 → 하위호환. iOS-first/relay-first 배포 순서 모두 안전.
        String incomingDev = (p != null) ? p.optString("device", "") : "";
        String currentDev = (prefs != null) ? prefs.getString("cloud_device", "") : "";
        if (!currentDev.isEmpty() && !incomingDev.isEmpty() && !currentDev.equals(incomingDev)) {
            postToD1("CLOUD", "⚠️ 세션 소유권 불일치(clear device≠현재 세션) → 삭제 거부(멱등)");
            return null;
        }
        postToD1("CLOUD", "세션 삭제 허용 (device 일치/빈값/레거시)");
        cloud = null; cloudVin = ""; cloudPin = "";
        // ★부트온 프리셋(선쉐이드·시트·핸들 등 로컬 SDK 자율 적용)도 정지 — 로그아웃하면 릴레이가 시동ON 시
        //   저장된 프리셋으로 자율 제어하던 것 차단. 상시생존이라 prefs만 지우면 인메모리 값으로 계속 적용 → 인메모리도 꺼야 함.
        if (relay != null) { relay.setStartPreset(false, null); relay.setStartPresetAdv(""); }
        if (prefs != null) prefs.edit()
                .remove("cloud_user").remove("cloud_pass").remove("cloud_uid").remove("cloud_device")
                .remove("cloud_sign").remove("cloud_enc").remove("cloud_vin").remove("cloud_pin")
                .putBoolean("start_preset_on", false).putString("start_preset_cmds", "").putString("start_preset_adv", "").apply();
        postToD1("CLOUD", "세션·부트온 프리셋 삭제 완료(로그아웃) — 후진 비상등·선쉐이드 등 릴레이 자율동작 전면 중단");
        return null;
    }

    private void restoreCloudFromPrefs() {
        try {
            if (prefs == null) return;
            String userId = prefs.getString("cloud_uid", "");
            String signTok = prefs.getString("cloud_sign", "");
            String encryTok = prefs.getString("cloud_enc", "");
            if (userId.isEmpty() || signTok.isEmpty() || encryTok.isEmpty()) return;
            String user = prefs.getString("cloud_user", "");
            if (cloudTables == null) cloudTables = com.cseini.bydsniffer.cloud.BangcleTables.loadFromAssets(this, "bangcle_tables.bin");
            com.cseini.bydsniffer.cloud.BydCloud c = new com.cseini.bydsniffer.cloud.BydCloud("KR", cloudTables);
            c.restoreSession(user.isEmpty() ? userId : user, userId, signTok, encryTok);
            cloud = c;
            cloudVin = prefs.getString("cloud_vin", "");
            cloudPin = prefs.getString("cloud_pin", "");
        } catch (Throwable ignored) {}
    }

    // ★Phase3b — 이메일/비번으로 릴레이가 직접 login(자체 토큰 획득). 성공 시 세션+credential 저장.
    private String setCloudCreds(org.json.JSONObject p) {
        try {
            if (p == null) return "params 없음";
            String user = p.optString("user", "");
            String pass = p.optString("pass", "");
            String vin = p.optString("vin", "");
            String pin = p.optString("pin", "");
            if (user.isEmpty() || pass.isEmpty()) return "user/pass 필요";
            if (cloudTables == null) cloudTables = com.cseini.bydsniffer.cloud.BangcleTables.loadFromAssets(this, "bangcle_tables.bin");
            com.cseini.bydsniffer.cloud.BydCloud c = new com.cseini.bydsniffer.cloud.BydCloud("KR", cloudTables);
            com.cseini.bydsniffer.cloud.BydCloud.Result r = c.login(user, pass);
            if (!r.success) { postToD1("CLOUD", "자체 login 실패: " + r.error); return "login 실패:" + r.error; }
            cloud = c; cloudVin = vin; cloudPin = pin;
            if (prefs != null) prefs.edit()
                    .putString("cloud_user", user).putString("cloud_pass", pass)
                    .putString("cloud_uid", c.userId).putString("cloud_sign", c.signToken).putString("cloud_enc", c.encryToken)
                    .putString("cloud_vin", vin).putString("cloud_pin", pin).apply();
            postToD1("CLOUD", "★자체 login 성공 uid=" + c.userId);
            return null;
        } catch (Throwable t) { return "login 예외:" + t.getMessage(); }
    }

    // ★토큰 자동갱신 — 저장된 credential(cloud_user/pass)로 재login 후 새 토큰 저장. 비번틀림이면 실패(false) → 무한루프 방지.
    private boolean reloginFromPrefs() {
        try {
            if (prefs == null) return false;
            String user = prefs.getString("cloud_user", "");
            String pass = prefs.getString("cloud_pass", "");
            if (user.isEmpty() || pass.isEmpty()) return false;
            if (cloudTables == null) cloudTables = com.cseini.bydsniffer.cloud.BangcleTables.loadFromAssets(this, "bangcle_tables.bin");
            com.cseini.bydsniffer.cloud.BydCloud c = new com.cseini.bydsniffer.cloud.BydCloud("KR", cloudTables);
            com.cseini.bydsniffer.cloud.BydCloud.Result r = c.login(user, pass);
            if (!r.success) { postToD1("CLOUD", "토큰 자동갱신 재login 실패: " + r.error); return false; }
            cloud = c;
            prefs.edit().putString("cloud_uid", c.userId).putString("cloud_sign", c.signToken).putString("cloud_enc", c.encryToken).apply();
            postToD1("CLOUD", "★토큰 자동갱신 성공 uid=" + c.userId);
            return true;
        } catch (Throwable t) { return false; }
    }

    private String cloudFlash() {
        if (cloud == null) restoreCloudFromPrefs();
        // ★재로그인 완전 금지(iOS 마스터 세션 보호) — 릴레이는 iOS가 SETCLOUDSESS로 준 토큰만 사용. 자체 로그인은 iOS 세션을 킥해 주차 잠금·시동끄기를 1008로 무너뜨림.
        if (cloud == null) return "클라우드 세션 없음(iOS SETCLOUDSESS 필요)";
        if (cloudVin == null || cloudVin.isEmpty()) return "vin 없음";
        com.cseini.bydsniffer.cloud.BydCloud.Result r = cloud.flashLights(cloudVin, cloudPin == null ? "" : cloudPin);
        if (!r.success) {   // 같은 토큰으로 1회 재시도(일시 오류 대비). 토큰 만료면 iOS가 갱신해야(재로그인 안 함).
            r = cloud.flashLights(cloudVin, cloudPin == null ? "" : cloudPin);
        }
        postToD1("CLOUD", "flashLights → " + (r.success ? "OK" : ("실패: " + r.error + " (iOS 세션 갱신 필요)")));
        return r.success ? null : ("flash 실패:" + r.error);
    }

    private String execRelayAction(String action, org.json.JSONObject params) {
        // RAW = 임의 device.method(int...) 원격 실험(값 찾기). 그 외는 명명된 명령.
        // ★공유판도 차량 제어 RAW 허용(2026-09-03, 운영자 "제어성 다 열어": 통풍/열선/스티어링열선/창문/공조/미러/시동프리셋). execRaw=차량 SDK 리플렉션(OS 셸 아님). ⚠️전역 SECRET이라 유출 시 타차 제어 가능(문잠금=signature라 불가) — 근본해결=차량별 페어키 K. OS/파일/계정(SH/SELFUPDATE/CAM/PULLFILE/SETCLOUDCREDS)은 아래·별도 게이트로 계속 차단.
        if ("RAW".equals(action)) {
            String rr = execRaw(params);
            if (relay != null && rr != null) relay.setDiag("labret", rr);   // ★제어 실험실 — RAW 실행 결과(매니저 라우팅 반환값 1/0/-1 등)를 _labret로 앱 노출. 설정동기류는 null이라 스킵.
            return rr;
        }
        // ★SETVIN — 코드 기반 자동 프로비저닝. 앱이 헤드유닛 화면의 페어키 K로 서명해 devId 채널로 전송(dispatch가 K검증 완료).
        //   실 VIN을 헤드유닛에서 못 읽어(디바이스ID만 존재) 수동입력하던 것을, 화면코드 1회 스캔으로 대체. 잘못된 vin도 덮어씀.
        if ("SETVIN".equals(action)) {
            String nv = params != null ? params.optString("vin", "").trim() : "";
            String nu = params != null ? params.optString("uid", "").trim() : "";
            if (nv.isEmpty() || prefs == null) return "vin 없음";
            android.content.SharedPreferences.Editor e = prefs.edit();
            e.putString(KEY_VIN, nv);
            if (!nu.isEmpty()) e.putString(KEY_UID, nu);
            e.apply();
            String useUid = nu.isEmpty() ? prefs.getString(KEY_UID, "") : nu;
            if (relay != null) relay.setCredentials(useUid, nv);
            postToD1("RELAY", "SETVIN 프로비저닝 — vin말미=" + (nv.length() > 6 ? nv.substring(nv.length() - 6) : nv) + " uid=" + useUid + " (다음 폴부터 실 vin 보고)");
            return null;
        }
        // ★v205 카메라·파일 명령은 운영자 전용 — 남의 사생활 영상이 운영자 서버(맥미니)를 지나면 안 됨. 공유판은 잠금·공조만.
        //   ★예외 CAMRTC(2026-08-31) — WebRTC P2P라 영상이 서버 안 지나고(폰↔릴레이 직결) RelayClient.dispatch가 자기 vin K-grant로
        //   검증함(byd-camera-k-hardening). 이 게이트가 grant 검증 후에도 막아 공유 카메라가 통째 안 되던 것 수정(씰차주 실측).
        if ((action.startsWith("CAM") || action.equals("PULLFILE")) && !BuildConfig.IS_OPERATOR && !action.equals("CAMRTC") && !action.equals("CAMPROBE") && !action.equals("CAMDETECT")) return "카메라 미지원(공유판)";   // ★599 CAMDETECT 개방(움직임 %만, stream 강제OFF)   // ★CAMPROBE=비파괴 진단(AVMCamera 존재/프리뷰 실증), shared 씨라7 카메라 규명 위해 허용
        // ★냉방 게이트(릴레이 기준=단일 진실원, 2026-09-02) — 폰의 stale relayCoolMin으로 애프터블로우가 오발동하던 것 근본수정.
        //   minCool>0(폰이 조건분 전달)인데 이번 주행 실제 냉방 누적(coolMinutes, 릴레이가 정확히 앎)이 그 미만이면 릴레이가 거부.
        //   afterblow_abort로 폰의 억제창(byd_afterblow_until) 해제. 수동 "지금 실행"은 minCool 미전달(0)이라 통과.
        if (("AFTERBLOW".equals(action) || "AFTERBLOWSEQ".equals(action)) && params != null) {
            int minCool = params.optInt("minCool", 0);
            int actualCool = relay != null ? relay.coolMinutes() : 0;
            if (minCool > 0 && actualCool < minCool) {
                postToD1("RELAY", "애프터블로우 스킵(릴레이 게이트) — 냉방 " + actualCool + "분 < 조건 " + minCool + "분 (폰 stale 방지)");
                if (relay != null) relay.postCarEvent("afterblow_abort");
                return "애프터블로우 스킵(릴레이) — 냉방 " + actualCool + "분 < " + minCool + "분";
            }
        }
        if ("AFTERBLOW".equals(action)) { if (relay != null) relay.postCarEvent("afterblow_start"); return execAfterBlow(params); }   // ★시동 켜진 채 송풍전환 = 즉시 시작 → 앱에 afterblow_start(플래그ON+알림)
        if ("AFTERBLOWSEQ".equals(action)) return afterBlowSeq(params);   // ★애프터블로우 순서보장(2026-09-03 공유판도 허용). 시동꺼짐→미러OFF(hard)→클라우드OPENAIR→송풍전환.
        if ("AFTERBLOW_CANCEL".equals(action)) {   // ★v330 재접근+시동 → 애프터블로우 중단(증발기 건조 무의미) + 프리셋 가드 해제(applyStartPreset 스킵 desync 수정). iOS 재접근-취소 지점서 전송.
            afterblowUntil = 0;
            if (relay != null) relay.setAfterblowUntil(0);   // ★핵심 — RelayClient.afterblowUntilMs=0 → applyStartPreset 프리셋 스킵 해제(통풍 등 접근프리셋 정상 적용)
            Thread st = afterblowSeqThread; if (st != null && st.isAlive()) st.interrupt();
            Thread tt = afterblowThread; if (tt != null && tt.isAlive()) tt.interrupt();
            Thread ct = afterblowCompThread; if (ct != null && ct.isAlive()) ct.interrupt();
            postToD1("RELAY", "AFTERBLOW_CANCEL(재접근 시동) → 애프터블로우 중단 · 프리셋 가드 해제(afterblowUntil=0)");
            return null;
        }
        if ("PULLPOWERAPK".equals(action)) return pullPowerApk();   // ★611 패키지 고정 1회 확보(공유판 허용)
        if ("CLUSTERCAM".equals(action)) return clusterCamCfg(params);   // ★617 클러스터 측면캠 방향별 커스텀(영역·어안보정·반전)
        if ("HUASSIST".equals(action)) return huAssistCmd(params);   // ★728 헤드유닛 카메라 창(깜빡이·후진) 설정·위치 조정
        if ("CLUSTERCFG".equals(action)) return clusterLayoutCfg(params);   // ★619 클러스터 레이아웃 원격 저장(앱 '클러스터 편집(원격)')
        if ("PULLFILE".equals(action)) return pullFile(params);   // ★v187 헤드유닛 파일 raw 업로드(디컴파일용)
        if ("KILLBEETLECAM".equals(action)) { String r = com.overdrive.app.camera.dilink5.FastCamTcpBackend.beetleKill(getApplicationContext()); postToD1("CAMRTC", r); return r; }   // ★655 비틀런처 카메라 데몬 종료(운영자 승인 09-29)
        if ("MIRRORFOLD".equals(action)) {   // ★v206 결정론적 — 원하는 상태(0/1) 전달, -1=플립. 헬퍼 alive면 헬퍼로.
            final int d = params != null ? params.optInt("on", -1) : -1;
            String disp = dispatchA11y("mirrorfold", String.valueOf(d));
            if (disp != null && disp.contains("실패")) { postToD1("MIRRORFOLD", "발사 실패: " + disp); return disp; }   // 진짜 발사실패만 error
            postToD1("MIRRORFOLD", "디스패치: " + disp);
            if (d == 0 || d == 1) {
                // ★최종 토글값 확인·보고 — 별도 스레드(명령 스레드 12s 블로킹 금지: 폴링/하트비트 멈춤→오프라인 오판·중복디스패치). mirror_fold ack 최대 12초 대기.
                new Thread(() -> {
                    for (int i = 0; i < 12; i++) {
                        try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                        if (readMirrorFoldFile() == d) { postToD1("MIRRORFOLD", "✅ 자동폴딩 " + (d == 1 ? "켜기" : "끄기") + " 확정(mirror_fold=" + d + ")"); return; }
                    }
                    postToD1("MIRRORFOLD", "⚠️ 자동폴딩 " + (d == 1 ? "켜기" : "끄기") + " 미확정 — 현재 mirror_fold=" + readMirrorFoldFile() + " (재시도 필요)");
                }, "mirrorfold-confirm").start();
            }
            return null;   // 접수(발사 성공). 최종 토글값은 확인 스레드가 로그+mirror_fold 상태로 보고(명령 스레드 블로킹 회피).
        }
        if ("D5PROBE".equals(action)) {   // ★씨라이언7(DiLink5) 진단 접근 검증 — 읽기전용(self-grant COMMON+hidden_api·dex주입·리플렉션 getter). setter 없음 → shared 허용.
            new Thread(() -> {
                StringBuilder sb = new StringBuilder();
                try {
                    sb.append("vt=").append(DiLink5Support.vehicleType()).append(" isD5=").append(DiLink5Support.isDiLink5()).append(" sdk=").append(android.os.Build.VERSION.SDK_INT).append("\n");
                    String g = AdbAccess.ensureVehicleApiAccess(this);
                    sb.append("self-grant=").append(g).append(" | ").append(AdbAccess.STATUS).append(" | fail=[").append(AdbAccess.GRANT_FAIL).append("]\n");
                    sb.append("diag=").append(AdbAccess.diagSnapshot(this)).append("\n");
                    DiLink5Support.ensure(this);
                    sb.append("dexInject=").append(DiLink5Support.lastReport).append("\n");
                    D5Probe p = new D5Probe(this);
                    sb.append("inst=").append(p.runReadProbe()).append("\n");
                    sb.append("poll=").append(p.pollProbe());
                } catch (Throwable e) { sb.append("\n예외: ").append(e.getClass().getSimpleName()).append(" ").append(e.getMessage()); }
                String report = sb.toString();
                postToD1("D5PROBE", report);   // 운영자 D1(운영자 기기만)
                postDiag(report);              // 공개 /diag(공유 차주 동의 업로드)
            }, "d5probe").start();
            return null;   // 접수(백그라운드 실행 — 결과는 /diag+D1. ~15s 소요라 명령 스레드 블로킹 회피).
        }
        if ("SETCLOUDSESS".equals(action)) return setCloudSession(params);   // ★아이폰→릴레이 클라우드 세션(토큰) 저장 — 클라우드 제어(비상등·공조) 경로. 2026-09-03 공유판도 허용.
        if ("CLEARCLOUDSESS".equals(action)) return clearCloudSession(params);   // ★로그아웃 시 릴레이 클라우드 토큰 무효화. 2026-09-03 공유판도 허용.
        if ("SETCLOUDCREDS".equals(action)) return BuildConfig.IS_OPERATOR ? setCloudCreds(params) : "미지원(공유판)";   // ★Phase3b 릴레이 자체 login(이메일/비번 저장) — 계정 크리덴셜이라 공유판 차단 유지(SETCLOUDSESS 토큰 경로로 충분)
        if ("HUDSET".equals(action)) { broadcastHudSettings(params); postToD1("HUD", "HUD 설정 전달(오토전비)"); return null; }   // ★710 설치된 오토전비에 설정만 전달(설치·제거·되살리기 없음)
        if ("DATAUSE".equals(action)) { RelayClient.RESULT_DATA.set(lastDataUsage + " | " + lastDataByUse); return null; }   // ★측정값 — 요청할 때만(운영자 10-03), 713/708부터 결과 data로
        if ("DATAMONTHS".equals(action)) { RelayClient.RESULT_DATA.set(dataMonthsJson()); return null; }   // ★713/708 차 전체 월별 데이터 사용량(폰 차량 상태 카드)
        if ("REVFLASH".equals(action)) { boolean on = (params == null) || params.optBoolean("on", true); if (prefs != null) prefs.edit().putBoolean("reverse_flash", on).apply(); postToD1("RELAY", "후진 자동 비상등 " + (on ? "ON" : "OFF")); return null; }   // ★후진 비상등 토글(앱 설정 동기)
        if ("CLOUDFLASH".equals(action)) return cloudFlash();   // ★릴레이가 클라우드로 비상등. 2026-09-03 공유판도 허용.
        if ("CAMSHOT".equals(action)) return camShot(params);
        if ("CAMDUALPROBE".equals(action)) return camOffOn() ? CAM_OFF_MSG : clusterDualProbe();   // ★클러스터 2단계 진단(2026-09-17) — 전방 감지 중 AVM view5 동시 open 가능 여부 실차 판정
        if ("SENTRY_SET".equals(action) || "SENTRY_STATUS".equals(action) || "SENTRY_LIST".equals(action) || "SENTRY_CALENDAR".equals(action) || "SENTRY_CLEAR".equals(action) || "SENTRY_DELETE".equals(action) || "SENTRY_FAV".equals(action) || "SENTRY_THUMB".equals(action) || "SENTRY_CUT".equals(action) || "SENTRY_MARKS".equals(action)) {   // ★센트리(docs/SENTRY-DESIGN.md 훅 v2) — relay-loop 인라인이라 SET은 새 스레드로 넘기고 즉시 반환, STATUS는 파일시스템을 안 만지는 캐시만
            if (DiLink5Support.isDiLink5() && !BuildConfig.D5_SENTRY) return "센트리는 DiLink3(돌핀·아토3·씰) 전용";   // ★공유판은 매니저 미초기화라 ok:true로 오인되지 않게 거부(씨라7 시험판 D5_SENTRY만 예외)
            if ("SENTRY_STATUS".equals(action)) { RelayClient.RESULT_DATA.set(SentryManager.get().cachedStatusJson().toString()); return null; }   // ★null=ok:true, 상태는 data로(DATAUSE 방식)
            if ("SENTRY_LIST".equals(action)) { RelayClient.RESULT_DATA.set(SentryManager.get().remoteList(params != null ? params.optString("date") : null).toString()); return null; }   // ★집에서 보는 목록 — 하루치 7000자 이내(워커 8000자 절단)
            if ("SENTRY_CUT".equals(action)) { RelayClient.RESULT_DATA.set(SentryManager.get().cutNow().toString()); return null; }   // ★지금 보기 — 현재 조각을 바로 닫음(최대 약 3초 대기)
            if ("SENTRY_FAV".equals(action)) {   // ★여러 영상 즐겨찾기(운영자 10-11) — params {"ids":[…]}(최대 100개) 또는 {"id":"…"}, "favorite":true|false(기본 true). 응답 {"ok","updated":[…],"failed":[{"id","error"}],"count","favorite"}
                java.util.List<String> favs = new java.util.ArrayList<>();
                if (params != null) {
                    org.json.JSONArray arr = params.optJSONArray("ids");
                    if (arr != null) for (int i = 0; i < arr.length(); i++) favs.add(arr.optString(i, ""));
                    String one = params.optString("id", "");
                    if (!one.isEmpty()) favs.add(one);
                }
                if (favs.isEmpty()) return "즐겨찾기할 id가 없음";
                RelayClient.RESULT_DATA.set(SentryManager.get().favoriteEvents(favs, params == null || params.optBoolean("favorite", true)).toString()); return null;
            }
            if ("SENTRY_DELETE".equals(action)) {   // ★여러 영상 삭제(운영자 10-11) — params {"ids":[…]}(최대 100개) 또는 {"id":"…"}. 응답 {"ok","deleted":[…],"gone":[…],"failed":[{"id","error"}],"count"}
                java.util.List<String> dels = new java.util.ArrayList<>();
                if (params != null) {
                    org.json.JSONArray arr = params.optJSONArray("ids");
                    if (arr != null) for (int i = 0; i < arr.length(); i++) dels.add(arr.optString(i, ""));
                    String one = params.optString("id", "");
                    if (!one.isEmpty()) dels.add(one);
                }
                if (dels.isEmpty()) return "삭제할 id가 없음";
                RelayClient.RESULT_DATA.set(SentryManager.get().deleteEvents(dels).toString()); return null;
            }
            if ("SENTRY_CLEAR".equals(action)) { RelayClient.RESULT_DATA.set(SentryManager.get().clearAll(params != null ? params.optString("confirm") : null).toString()); return null; }   // ★녹화 전체 삭제 — confirm:"all" 필수, 지우기는 뒤에서 이어짐
            if ("SENTRY_CALENDAR".equals(action)) { RelayClient.RESULT_DATA.set(SentryManager.get().remoteCalendar(params != null ? params.optString("month") : null).toString()); return null; }   // ★집에서 보는 달력 — 한 달치 날짜별 이벤트 수
            if ("SENTRY_MARKS".equals(action)) {   // ★집에서 보는 시간축 표시·객체 판정(운영자판 10-09) — marks·objects·알림 사진(7200자 이내)
                RelayClient.RESULT_DATA.set(SentryManager.get().remoteMarks(params != null ? params.optString("id") : null).toString()); return null;
            }
            if ("SENTRY_THUMB".equals(action)) { RelayClient.RESULT_DATA.set(SentryManager.get().remoteThumb(params != null ? params.optString("id") : null).toString()); return null; }   // ★집에서 보는 썸네일 base64 6000자 이내
            final org.json.JSONObject sentryParams = params != null ? params.optJSONObject("sentry") : null;
            new Thread(() -> {
                try { SentryManager.get().applySet(sentryParams); }
                catch (Throwable t) { postToD1("SENTRY", "SENTRY_SET 적용 예외: " + t); }
            }, "sentry-set").start();
            RelayClient.RESULT_DATA.set("{\"pending\":true}"); return null;   // ★적용은 비동기 — 결과는 relay_state.sentry / SENTRY_STATUS로
        }
        if ("CAMDETECT".equals(action) && params != null && "sentry".equals(params.optString("mode")) && !params.optBoolean("stop")) return "센트리는 내부 전용";   // ★외부 명령이 센트리 모드로 카메라를 무기한 점유하는 것 차단(내부 startSentryDetect는 camDetect를 직접 호출)
        if ("CAMDETECT".equals(action)) return camDetect(params);   // ★v188 실제 카메라 프레임 1장 캡처→JPEG→워커(스트리밍 전 단계 실증)
        if ("CAMSTREAM".equals(action)) return camStream(params);   // ★v189 라이브 스트리밍(연속 프레임→워커 push, 폰 /cam 뷰어)
        if ("CAMH264".equals(action)) return camH264(params);   // ★v190 H.264 하드웨어 인코딩 검증(카메라→MediaCodec→Annex-B)
        if ("CAMH264LIVE".equals(action)) return camH264Stream(params);   // ★v195 H.264 라이브(지속연결→워커→폰 WebCodecs)
        if ("CAMRTC".equals(action)) return camRtc(params);   // ★v266 WebRTC P2P — 카메라→libwebrtc→SRTP 폰 직결(워커=시그널링만)
        if ("SENTRY_RTC".equals(action)) {   // ★779 센트리 집에서 재생 — 폰과 데이터 채널 직결로 녹화 조각 전송(카메라 안 씀, 폰 K 서명 명령)
            if (DiLink5Support.isDiLink5()) return "센트리는 DiLink3(돌핀·아토3·씰) 전용";
            return sentryRtc(params);
        }
        if ("CARSET".equals(action)) { String out = "CARSET: " + carSet(params); postToD1("DILINK_CMD", out); return out; }   // ★v177 setProperty 쓰기(★2026-09-03 공유판도 허용 — 제어 테스트).
        String r = sendVehicleCommand(action);
        return r.contains("OK") ? null : r;   // null=성공
    }

    // 오토도어 릴레이 시작 (prefs에 켜짐 + uid/vin 설정된 경우만)
    // ★워커 /rtc/ice에서 STUN+TURN 목록 수신 — 릴레이 카메라 WebRTC용(IPv6-only srflx·까다로운 NAT 폴백). 실패 시 null(호출부 STUN 폴백).
    private java.util.List<org.webrtc.PeerConnection.IceServer> fetchIceServers() {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://byd.cseini.co.kr/rtc/ice?k=" + BuildConfig.RELAY_SECRET).openConnection();
            c.setConnectTimeout(4000); c.setReadTimeout(4000);
            if (c.getResponseCode() != 200) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()));
            StringBuilder sb = new StringBuilder(); String line; while ((line = r.readLine()) != null) sb.append(line); r.close();
            org.json.JSONArray arr = new org.json.JSONObject(sb.toString()).optJSONArray("iceServers");
            if (arr == null) return null;
            java.util.List<org.webrtc.PeerConnection.IceServer> out = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                java.util.List<String> urls = new java.util.ArrayList<>();
                Object u = e.opt("urls");
                if (u instanceof org.json.JSONArray) { org.json.JSONArray ua = (org.json.JSONArray) u; for (int k = 0; k < ua.length(); k++) urls.add(ua.optString(k)); }
                else if (u instanceof String) urls.add((String) u);
                if (urls.isEmpty()) continue;
                org.webrtc.PeerConnection.IceServer.Builder b = org.webrtc.PeerConnection.IceServer.builder(urls);
                String un = e.optString("username", ""), cr = e.optString("credential", "");
                if (!un.isEmpty()) b.setUsername(un);
                if (!cr.isEmpty()) b.setPassword(cr);
                out.add(b.createIceServer());
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable t) { Log.w(TAG, "fetchIceServers 실패: " + t); return null; }
    }

    // ★헤드유닛 고유 신원 — 클라우드가 부여한 device id(실 VIN 아님, 실 VIN은 헤드유닛에 없음). getprop로 읽음.
    //   부트스트랩 프로비저닝 주소지정용(앱이 이 값으로 SETVIN 전송 → 실 VIN 주입).
    // ★575 VIN 형식 검증 — 실 VIN은 정확히 17자, I·O·Q 제외 영숫자. D5의 persist.sys.cloud.last_vin이
    //   빈값이 아니어도 16자·6자·garbled로 오는 사례가 많아(메밀소바 LXCH4CD2T2089612 등), 이걸 devId로 쓰면
    //   ①reportVin이 깨진 VIN이 되어 폰(클린 VIN 조회)에서 영영 안 보이고 ②매 부팅 값이 바뀌면 SETVIN 주소가
    //   흔들려 등록이 만료된다(실측: T2107563 등 잘린주소 SETVIN 전부 expired/error). → 무효면 버리고 안정 해시.
    private static boolean isValidVin(String s) {
        if (s == null || s.length() != 17) return false;
        for (int i = 0; i < 17; i++) {
            char c = Character.toUpperCase(s.charAt(i));
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z' && c != 'I' && c != 'O' && c != 'Q');
            if (!ok) return false;
        }
        return true;
    }
    private String getpropRaw(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine(); r.close(); p.waitFor();
            return line == null ? "" : line.trim();
        } catch (Throwable ignored) { return ""; }
    }
    private String readDevId() {
        String rawSeen = "";   // 부팅 진단: 비어있지 않지만 무효였던 마지막 getprop 값
        for (String key : new String[]{"persist.sys.cloud.last_vin", "sys.virtual.vin"}) {
            // ★재시도 3회 — garbling이 읽기 레이스면 완화(영속 손상이면 어차피 무효로 떨어져 해시 사용).
            for (int attempt = 0; attempt < 3; attempt++) {
                String v = getpropRaw(key);
                if (v.isEmpty()) break;                     // 이 키 빈값 — 재시도 무의미, 다음 키
                if (isValidVin(v)) { bootDevIdLog(key, v.toUpperCase(), true); return v.toUpperCase(); }
                rawSeen = key + "=" + v;                    // 비었지 않으나 무효(깨진/잘린) — 버리고 재시도
                try { Thread.sleep(150); } catch (InterruptedException ie) { break; }
            }
        }
        // ★getprop 유효 VIN 없음(빈값 or 깨짐) → 로컬 안정 fallback devId 생성·재사용(2026-09-09 운영자
        //   "수동이면 페어링코드 화면에 보여줘야지"). devId가 비면 startRelay가 페어키 K 생성 전 early-return해 코드가 아예 안 떴음.
        //   이 fallback으로 부트스트랩 모드 진입→K 생성→MainActivity 코드 표시. SETVIN은 이 devId로 주소지정(불투명 ID·안정).
        try {
            if (prefs != null) {
                String fb = prefs.getString("relay_dev_id_fb", "");
                if (fb.isEmpty()) {
                    byte[] rnd = new byte[6]; new java.security.SecureRandom().nextBytes(rnd);
                    StringBuilder sb = new StringBuilder("D5");
                    for (byte b : rnd) sb.append(String.format("%02X", b));
                    fb = sb.toString();   // 예: D5A1B2C3D4E5 (14자, 안정)
                    prefs.edit().putString("relay_dev_id_fb", fb).apply();
                    Log.i(TAG, "devId getprop 무효/빈값 → fallback devId 생성 " + fb);
                }
                bootDevIdLog(rawSeen.isEmpty() ? "empty" : rawSeen, fb, false);
                return fb;
            }
        } catch (Throwable ignored) {}
        return "";
    }
    // ★575 부팅 시 devId 획득 진단 — 피드백 세션이 워커 DB에서 재프로비저닝 필요 릴레이를 명단화할 근거.
    //   1회/부팅만(중복 방지). raw=원본 getprop, use=실제 devId, valid=유효 VIN 여부.
    private boolean devIdLogged = false;
    private void bootDevIdLog(String raw, String use, boolean valid) {
        if (devIdLogged) return; devIdLogged = true;
        try { postToD1("VINREG", "devId 획득 raw='" + raw + "' use='" + use + "' validVin=" + valid
                + " setvin_vin=" + (prefs != null ? maskTail(prefs.getString(KEY_VIN, "")) : "?")); } catch (Throwable ignored) {}
    }
    private static String maskTail(String s) { return (s == null || s.isEmpty()) ? "(없음)" : (s.length() > 6 ? "…" + s.substring(s.length() - 6) : s); }

    private void startRelay() {
        try {
            if (prefs == null || !prefs.getBoolean(KEY_RELAY, false)) return;
            String uid = prefs.getString(KEY_UID, "");
            String vin = prefs.getString(KEY_VIN, "");
            String devId = readDevId();   // ★부트스트랩 신원(getprop) — vin 없어도/틀려도 앱이 SETVIN으로 프로비저닝 가능
            if (!devId.isEmpty()) prefs.edit().putString(KEY_DEVID, devId).apply();   // MainActivity 표시용
            if (uid.isEmpty() || vin.isEmpty()) {
                if (devId.isEmpty()) { Log.w(TAG, "relay: uid/vin·devId 모두 미설정 → 미시작"); return; }
                Log.i(TAG, "relay: vin 미설정 — devId 부트스트랩 모드(앱 등록 대기)");
            }
            relay = new RelayClient(this::execRelayAction);
            relay.addStateListener((k, v) -> { RemoteWebServer w = remoteWeb; if (w != null) w.pushState(k, v); });   // ★클러스터 SSE(2026-09-17) — 감시 키 변경 즉시 푸시. 웹서버는 늦게 뜰 수 있어 호출 시점에 null 체크
            relay.addStateListener((k, v) -> { if ("lock".equals(k) && v instanceof Integer) btAudioOnLockState((Integer) v); });   // ★810 ② 시동 OFF 뒤 잠금 때 폰 오디오 블루투스 한 번 더 끊기
            relay.ignSnap = this::ignSnapshot;   // ★시동 엣지 시 후보 게터 스냅샷(진단)
            // ★574 시동 ON 순간에 업데이트 안내 — 60초 앵커 틱만으로는 "시동 걸고 30초 만에 출발"하는 차를 영영 못 잡는다
            //   (정작 그런 차가 구버전에 정체된 쪽이다). 네트워크(LTE/WiFi)가 붙을 시간을 20초 준 뒤 확인한다.
            // ★647 시동 끄는 순간 기록 업로드(피드백분석 09-29 자루씨 "시동 끄면 릴레이 오프라인") — 테스터 기록은 링버퍼라 꺼지기 직전이 영영 안 올라왔다.
            // ★654 시동 OFF 순간 저장장치 관찰 시작(sd_off_ts는 동기 저장 — D5는 몇 초 뒤 강제종료되므로 +0분은 즉시 찍고 나머지는 앵커루프가 이어감)
            RelayClient.netGapLog = m -> postToD1("NETGAP", m);   // ★726 서버 끊김이 잠 때문인지 인터넷·서버 때문인지 한 줄
            relay.onIgnitionOff = () -> {
                mainHandler.postDelayed(this::launcherUpdateCheck, 60000);   // ★872 링크락 런처 새 판(켜 둔 경우만, 시동 끈 1분 뒤)
                SentryManager.get().onIgnition(false);   // ★센트리 — OFF +20초에 게이트 평가(앞 문장이 예외를 던져도 불리도록 첫 줄)
                setSplitOwned(false);   // ★724 시동 끄면 이번에 띄운 분할 기억도 끝
                if (prefs != null) prefs.edit().putLong("sd_off_ts", System.currentTimeMillis()).putInt("sd_done", 0).apply();
                new Thread(() -> {
                    storageSnap("시동OFF +0분");
                    if (prefs != null) prefs.edit().putInt("sd_done", prefs.getInt("sd_done", 0) | 1).apply();
                    sleepQuiet(2000); postToD1("RELAY", "시동 끔 → 기록 업로드 " + sendBufferedLogs());
                }, "ign-off-flush").start();
                btAudioOnIgnOff();   // ★715 폰 오디오·통화 연결 관측(스위치 켠 차만 끊기)
                new Thread(clusterCam::keepTick, "keep-ign-off").start();   // ★729 보조 카메라 상시 대기 바로 닫기(폴 틱을 기다리지 않음)
            };
            relay.onIgnitionOn = () -> {
                SentryManager.get().onIgnition(true);   // ★센트리 — 즉시 중지(앞 문장이 예외를 던져도 불리도록 첫 줄)
                setSplitOwned(false);   // ★724 새 시동 — 지난 시동에 띄운 분할은 잊는다(시작 앱이 다시 띄우면 그때 다시 지킴)
                mainHandler.postDelayed(this::maybeUpdatePrompt, 20000); approachLaunchDone = false; maybeApproachLaunch();
                if (prefs != null) prefs.edit().remove("sd_off_ts").putInt("sd_done", 0).apply();   // ★654 OFF 관찰 종료
                new Thread(() -> { sleepQuiet(10000); storageSnap("시동ON +10초(기준)"); }, "sd-on-snap").start();   // USB 허브 재연결 뒤 기준값
                btAudioOnIgnOn();   // ★715 끊었던 연결이 스스로 붙는지 → 안 붙으면 connect 1회
                wakeAutotripAsync("시동 켬");   // ★723 돌핀엔 ACC_ON 방송이 안 와(CARBC 실측) 꺼진 오토전비가 시동에도 안 뜬다
                if (dualFails > 0) { postToD1("CLUSTER", "새 주행 — 지난 주행의 카메라 동시 운용 실패 " + dualFails + "번 지움(다시 같이 씀)"); dualFails = 0; if (relay != null) relay.putStatus("cam_dual", "ok"); }   // ★784
                if (prefs != null && prefs.contains("cam_dual_fail")) prefs.edit().remove("cam_dual_fail").apply();   // ★784 예전 영구 기록 정리(이제 안 읽음)
                new Thread(clusterCam::keepTick, "keep-ign-on").start();   // ★729 시동 켜자마자 보조 카메라 열기(출발 직후 후진 대비)
                mainHandler.postDelayed(this::announceSentrySummary, 15000);   // ★864 센트리 가동 요약 — 헤드유닛이 다 뜬 뒤 알림음+문구(계약서 7.31)
            };
            btAudioInit();   // ★715 A2DP 싱크·HFP 프록시와 연결 상태 리시버(프로세스당 1회)
            registerAutotripStateReceiver();   // ★710/713 오토전비 적용 상태 → relay_state.autotrip_hud
            registerStorageReceivers();   // ★654 MEDIA_*·USB 탈착 이벤트 기록   // ★646 시작 앱 = 시동 켜짐 후(접근 시엔 시동 과정에서 화면이 뒤로 밀림)
            relay.powerLevelReader = () -> {      // ★AC_POWER_STATE 이벤트 수신 즉시 동기 판별용 getPowerLevel
                try { double v = devDbl(deviceByName("bodywork"), "getPowerLevel"); return Double.isNaN(v) ? IgnitionTracker.NA : (int) v; }
                catch (Throwable t) { return IgnitionTracker.NA; }
            };
            // ★부팅 직후 전 도메인 현재값 재방송 요청 — 이벤트 전용 값(공조 등)이 비어 있는 구간 제거.
            new Thread(() -> { sleepQuiet(8000); requestAllStatus("부팅"); }, "allstatus").start();
            relay.setCredentials(uid, vin);
            relay.setDevId(devId);
            // ★579 헤드유닛 기기ID(오토전비·차지로그와 동일 소스) → relay_state.hu_id. 서버 사용자 명부가 VIN을 오토전비/차지로그와 잇는다.
            try { relay.putStatus("hu_id", DiLink5Support.deviceTag(this)); } catch (Throwable ignored) {}
            // ★페어키 K 로드/생성(차량별 명령 서명키). 최초 1회 생성·저장 → MainActivity가 화면 코드로 표시(앱이 입력).
            String pk = prefs.getString(KEY_PAIR, "");
            if (pk.isEmpty()) {
                byte[] rnd = new byte[16]; new java.security.SecureRandom().nextBytes(rnd);
                StringBuilder sb = new StringBuilder();
                for (byte b : rnd) sb.append(String.format("%02x", b));
                pk = sb.toString();
                prefs.edit().putString(KEY_PAIR, pk).apply();
                Log.i(TAG, "페어키 K 생성(최초)");
            }
            relay.setPairKey(pk);
            // ★v149 시동 프리셋 복원(릴레이 재시작 후에도 부트온 자동적용 유지)
            String spCmds = prefs.getString("start_preset_cmds", "");
            relay.setStartPreset(prefs.getBoolean("start_preset_on", false), spCmds.isEmpty() ? null : spCmds);
            relay.setStartPresetAdv(prefs.getString("start_preset_adv", ""));   // ★v403 고급 규칙 복원
            relay.setPresetPrefs(prefs);   // ★presetDone 복원 — 주행 중 재설치 재시작 시 이미 적용된 세션이면 프리셋 재적용 안 함(선쉐이드 안 움직임)
            // ★v109: 홈 워커(한도 없음)로 전원 워커 사용 — v106의 운영자 전용 폴링 게이트 폐기.
            //   D1 로그(postToD1)만 운영자 전용 유지(프라이버시). 이벤트 push도 워커가 vin 스코프로 자기 폰에만 보냄.
            RelayClient.network = true;
            relay.start();
            Log.i(TAG, "relay 시작 uid=" + uid + " vin=..." + (vin.length() > 4 ? vin.substring(vin.length() - 4) : vin));
            announceAutostartTargets("릴레이 시작");   // ★시작 시마다 오토스타트 자동해제 대상 명시(로그+상태)
            startSysRestartDiag();   // ★706 시스템 재시작 진단(읽기 전용, 2시간 1회)
            putAutotripVer();   // ★710 오토전비 설치 여부(버전) 상태 보고
            wakeAutotripAsync("릴레이 시작");   // ★723 청소로 같이 죽은 오토전비 깨우기
            if (relay != null) relay.putStatus("cam_off", camOffOn());   // ★카메라 기능 끔 상태(폰 메뉴 숨김 기준)
            new Thread(() -> { java.io.File[] old = tlDir().listFiles(); long t = System.currentTimeMillis(); if (old != null) for (java.io.File f : old) if (t - f.lastModified() > 600_000L) f.delete(); }, "tldiag-clean").start();   // ★재시작 전 정차의 남은 신호등 사진(10분 지난 것) 정리
            {   // ★687 업데이트가 실제로 반영됐는지 — 이전 실행 버전과 달라졌으면 한 줄 기록
                String lastVer = prefs.getString("last_run_ver", "");
                if (!lastVer.equals(BuildConfig.VERSION_NAME)) {
                    if (!lastVer.isEmpty()) postToD1("SELFUPD", "업데이트 확인됨: " + lastVer + " → " + BuildConfig.VERSION_NAME);
                    prefs.edit().putString("last_run_ver", BuildConfig.VERSION_NAME).apply();
                }
            }
            if (DiLink5Support.isDiLink5()) new Thread(() -> {   // ★688 씨라7: 앱이 죽으며 남긴 옛 캡처 데몬(CPU 점유 의심) 정리 — 보기 중이면 생략, 발견·실패 때만 기록
                try {
                    String r = com.overdrive.app.camera.dilink5.FastCamTcpBackend.cleanupStale(getApplicationContext());
                    if (!r.startsWith("옛 데몬 없음")) postToD1("CAMRTC", "시작 정리 — " + r);
                } catch (Throwable t) { postToD1("CAMRTC", "시작 정리 오류 — " + t); }
            }, "stale-cam-cleanup").start();
            if (BuildConfig.IS_OPERATOR) runSurvivalDiagAsync(false);   // ★684 운영자 차는 릴레이가 뜰 때마다(10분 스로틀) 생존 비교 기록을 남긴다 — 재부팅 직후 시작 경위 포착
            healAutostartAfterRestart();   // ★683 시스템 강제 종료 뒤 재시작이면 오토스타트 금지를 바로 다시 풀어 둔다(재부팅 대비)
            // ★DiLink5 자동시작 검증 — BootReceiver가 기록한 부팅 브로드캐스트 이력을 상태로 push(실차 씨라이언7서 USB 시드 발화 확인용).
            try {
                relay.putStatus("last_boot", prefs.getString("last_boot", ""));
                relay.putStatus("boot_why", bootWhy);
                publishClusterCamCfg();   // ★617 커스텀 뷰 현재값(앱 편집기 초기값)
                publishHuAssist();   // ★728 헤드유닛 카메라 창 켬 상태(폰 설정 화면 표시용)
                if (!IS_D5) relay.putStatus("usb_keep_user", prefs.getBoolean("usb_keep_user", false) ? 1 : 0);   // ★753 USB 유지 토글 현재값(폰 부가기능 카드) — 씨라7은 안 보냄(폰이 카드 숨김)
                applyRebootGuard("릴레이 시작");
                relay.putStatus("cluster_layout", prefs.getString("cluster_layout", ""));   // ★619 원격 편집기 초기값
                relay.putStatus("ka_prev", prevKa);   // ★614 이전 프로세스의 마지막 상시생존 상태(재시작 전 절전이었는지)
                kaStateLast = ""; wakeResLast = ""; usbKeepStLast = "";   // 새 RelayClient(빈 state)에 현재값 재발행
                relay.putStatus("boot_hist", prefs.getString("boot_hist", ""));
                relay.putStatus("dilink5", DiLink5Support.isDiLink5());
                relay.putStatus("reg_domains", regDomains.toString());   // ★D5 Bodywork dexmaker 구독 성공여부(+dex/!) 원격 확인
                // ★probeCaps 결과 재주입 — probeCaps는 서비스 초기 1회뿐인데 RelayClient는 restartRelay마다 빈 state로 재생성돼
                //   caps/remote_manifest가 유실됨(2026-09-10 실차: a11y 워치독 Activity 포크→MainActivity restartRelay). 캐시로 복원.
                if (!capsFlat.isEmpty()) relay.putStatus("caps", capsFlat);
                if (!remoteManifestJson.isEmpty()) relay.putStatus("remote_manifest", remoteManifestJson);
                if (!remoteAppsJson.isEmpty()) relay.putStatus("remote_apps", remoteAppsJson);   // ★앱런처 목록 캐시 복원
            } catch (Throwable ignored) {}
        } catch (Exception e) { Log.e(TAG, "startRelay", e); }
    }

    // ★706 시스템 재시작 진단(읽기 전용) — 10-02 씨라7에서 커널 재부팅 없이 프레임워크가 다시 뜨는(BOOT_COMPLETED 재수신) 일이 공유판 701 설치 뒤 급증.
    //   서버 로그로는 원인 코드를 못 가려, 기기 dropbox의 system_server 기록(watchdog·crash·SYSTEM_RESTART)을 2시간에 한 번 D1로 올린다. 아무것도 바꾸지 않는다.
    //   공유판은 postToD1 버퍼를 우회(LIFE와 동일) — 사용자가 로그를 보내지 않아도 도착해야 집계가 된다. 보낸 뒤에만 스로틀을 기록(실패·조기 종료 땐 다음 시작에 재시도).
    private void startSysRestartDiag() {
        new Thread(() -> {
            try {
                if (prefs == null) return;
                if (System.currentTimeMillis() - prefs.getLong("sysrd_ts", 0) < 2 * 3600_000L) return;
                String list = null;
                for (int i = 0; i < 4 && list == null; i++) {
                    Thread.sleep(i == 0 ? 15_000 : 30_000);
                    list = AdbAccess.runShell(this, "dumpsys dropbox 2>/dev/null | grep -E ' (system_server_watchdog|system_server_crash|system_server_native_crash|system_server_anr|SYSTEM_RESTART|SYSTEM_BOOT) ' | tail -80");
                }
                if (list == null) { postToD1("SYSRESTART", "dropbox 조회 불가(adb 미준비)"); return; }
                String since = prefs.getString("sysrd_last", ""), newest = since;
                java.util.Map<String, Integer> cnt = new java.util.TreeMap<>();
                java.util.List<String> fresh = new java.util.ArrayList<>();
                for (String ln : list.split("\n")) {
                    ln = ln.trim();
                    if (!ln.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} \\S+.*")) continue;
                    String ts = ln.substring(0, 19);
                    if (ts.compareTo(since) <= 0) continue;
                    String tag = ln.substring(20).split("\\s+")[0];
                    cnt.merge(tag, 1, Integer::sum);
                    fresh.add(ts.substring(5) + " " + tag.replace("system_server_", "ss_"));
                    if (ts.compareTo(newest) > 0) newest = ts;
                }
                if (fresh.isEmpty()) postToD1("SYSRESTART", "dropbox 새 항목 없음(기준 " + (since.isEmpty() ? "처음" : since) + ")");
                else {
                    String tail = android.text.TextUtils.join(" | ", fresh.subList(Math.max(0, fresh.size() - 24), fresh.size()));
                    postToD1("SYSRESTART", "dropbox 새 항목 " + fresh.size() + " " + cnt + " — " + tail);
                    if (cnt.containsKey("system_server_watchdog")) postDropboxDetail("system_server_watchdog", "-E '^Subject: |^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]{8} system_server_watchdog'", 24, "사유");
                    if (cnt.containsKey("system_server_crash")) postDropboxDetail("system_server_crash", "-E '^Subject: |^Caused by|^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]{8} system_server_crash|^[a-z]+(\\.[A-Za-z0-9_$]+)*(Exception|Error)[: ]'", 24, "사유");
                    if (cnt.containsKey("system_server_native_crash")) postDropboxDetail("system_server_native_crash", "-E '^Subject: |^signal |^Abort message|^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]{8} system_server_native_crash'", 16, "사유");
                }
                // ★709 최근 크래시의 호출 경로(예외 줄 + 12줄) — 새 항목 유무와 무관하게 버전당 1회(기기 dropbox가 며칠 보관하므로 어제 기록도 나온다)
                if (!prefs.getBoolean("sysrd_stack709", false)) {
                    postDropboxDetail("system_server_crash", "-A12 -E '^[a-z]+(\\.[A-Za-z0-9_$]+)*(Exception|Error)[: ]'", 26, "스택(최근)");
                    prefs.edit().putBoolean("sysrd_stack709", true).apply();
                }
                prefs.edit().putLong("sysrd_ts", System.currentTimeMillis()).putString("sysrd_last", newest).apply();
            } catch (Throwable t) { postToD1("SYSRESTART", "진단 예외 " + t.getClass().getSimpleName()); }
        }, "sysrestart-diag").start();
    }
    private void postDropboxDetail(String tag, String grepArgs, int lines, String label) {
        String out = AdbAccess.runShell(this, "dumpsys dropbox --print " + tag + " 2>/dev/null | grep " + grepArgs + " | tail -" + lines);
        if (out == null || out.isEmpty()) { postToD1("SYSRESTART", tag + " " + label + " 조회 실패/없음"); return; }
        out = out.replace('\n', '|');
        if (out.length() > 1800) out = out.substring(out.length() - 1800);
        postToD1("SYSRESTART", tag + " " + label + " — " + out);
    }

    // ★오토스타트 자동해제 대상 앱을 명시 — 릴레이 시작·설정변경 시 D1 로그 + 상태(앱에서 확인)로 남긴다.
    public void announceAutostartTargets(String why) {
        try {
            String csv = autostartTargetsCsv(this);
            StringBuilder sb = new StringBuilder();
            if (!csv.isEmpty()) for (String pk : csv.split(",")) {
                pk = pk.trim(); if (pk.isEmpty()) continue;
                String l = null;
                try { l = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pk, 0))); } catch (Throwable ignored) {}
                sb.append(l != null ? l : pk).append("(").append(pk).append(") ");
            }
            String list = sb.length() == 0 ? "(없음)" : sb.toString().trim();
            postToD1("AUTOSTART", "오토스타트 자동해제 대상 [" + why + "]: " + list);
            if (relay != null) try { relay.putStatus("autostart_targets", list); } catch (Throwable ignored) {}
        } catch (Throwable t) { postToD1("AUTOSTART", "대상 명시 예외: " + t.getClass().getSimpleName()); }
    }

    // ══════════════════════════════════════════════════════════════════
    // ★a11y 헬퍼 앱(com.cseini.bydhelper) 오케스트레이션 — 미러폴딩·오토스타트 실행을 초경량 헬퍼로 이관.
    //   릴레이 a11y는 시동OFF에 죽고 오직 pm install -r로만 복구되므로, 필요 시 헬퍼만 로컬 재설치해
    //   헬퍼 a11y만 재바인드(메인 릴레이는 안 건드림). 헬퍼 생존은 broadcast ack(4초 심박)로 판정.
    // ══════════════════════════════════════════════════════════════════
    static final String HELPER_PKG = "com.cseini.bydhelper";
    static final int HELPER_ASSET_VER = 5;                          // 번들 assets/byd-helper.apk versionCode(bydhelper build.gradle.kts와 일치시킬 것) — v5(10-06) ack 앞쪽 방송 줄·순번·차 번호 로그
    private int helperUpgradeTries = 0;
    static final String HELPER_CMD_ACTION = "com.cseini.bydhelper.CMD";
    static final String HELPER_ACK_ACTION = "com.cseini.bydhelper.ACK";
    static final String HELPER_A11Y_COMPONENT = "com.cseini.bydhelper/com.cseini.bydhelper.HelperAccessibilityService";
    static final long HELPER_ALIVE_MAX_MS = 12000;   // 4초 심박 × 3 — 이 안이면 헬퍼 a11y bound 살아있음.
    private static volatile long helperAliveTs = 0;  // 헬퍼 ack 최신 수신 시각(메인 프로세스 — MainActivity와 공유).
    private volatile long lastHelperReinstall = 0;
    private volatile long ackReceiverRegMs = 0;   // ★696 ack 리시버 등록 완료 시각(0=이 서비스 시작에서 아직 등록 안 됨)
    private volatile int helperAckCount = 0;      // ★696 이 서비스 시작 이후 받은 헬퍼 ack 건수
    private String ackDiag() { return "리시버 " + (ackReceiverRegMs > 0 ? "등록됨(시작 후 " + (ackReceiverRegMs - serviceCreatedMs) / 1000 + "초)" : "미등록") + " · 서비스 시작 후 " + (System.currentTimeMillis() - serviceCreatedMs) / 1000 + "초 · ack 누적 " + helperAckCount + "건"; }
    private volatile long lastHelperRebindOnly = 0;       // ★648 stale 1단계(재연결만) 시각 — 재설치 전에 먼저 시도
    private volatile long helperRebindDoneTs = 0;       // ★v368 부팅 강제재바인딩(registerHelperA11y) 완료 시각 — 이 이후 ack만 '안정된 헬퍼'로 간주
    private volatile long helperHoldUntil = 0;          // ★788 오토스타트 해제 중(발사 전 대기 포함) — 이 시각까지 헬퍼 설치·재설치·업그레이드를 미룬다(해제 화면 자동화와 겹치지 않게)

    // 헬퍼 ack 나이(ms). -1=아직 ack 없음. MainActivity도 호출(같은 메인 프로세스 static).
    static long helperAliveAgeMs() { return helperAliveTs > 0 ? System.currentTimeMillis() - helperAliveTs : -1; }
    static boolean helperAlive() { long a = helperAliveAgeMs(); return a >= 0 && a < HELPER_ALIVE_MAX_MS; }

    // ★a11y 명령 단일 디스패치 — 헬퍼 alive면 헬퍼로 broadcast, 아니면 릴레이 자체 a11y 트리거파일.
    //   미러/오토스타트/화면깨우기 전 경로가 이 한 지점을 타서 이중 실행(두 a11y가 같은 스위치 탭) 방지.
    static String dispatchA11y(android.content.Context ctx, String cmd, String extra) {
        if (helperAlive()) return sendHelperCmd(ctx, cmd, extra);
        return writeA11yTrigger(ctx, cmd, extra);
    }
    private String dispatchA11y(String cmd, String extra) { return dispatchA11y(this, cmd, extra); }

    static String sendHelperCmd(android.content.Context ctx, String cmd, String extra) {
        try {
            android.content.Intent i = new android.content.Intent(HELPER_CMD_ACTION);
            i.setPackage(HELPER_PKG);
            i.addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES);   // 헬퍼가 (런처없어) stopped여도 도달
            i.putExtra("cmd", cmd);
            if (extra != null) i.putExtra("arg", extra);
            i.putExtra("ts", System.currentTimeMillis());
            String vin = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(KEY_VIN, "");
            if (vin.length() >= 6) i.putExtra("dev", vin.substring(vin.length() - 6));   // ★헬퍼 v5가 기억해 자기 로그에 차 번호를 붙인다(헬퍼 로그를 차별로 가리기)
            ctx.sendBroadcast(i);
            return "헬퍼 broadcast(" + cmd + (extra != null ? " " + extra : "") + ")";
        } catch (Throwable t) { return "헬퍼 broadcast 실패: " + t.getClass().getSimpleName(); }
    }

    // 릴레이 a11y·헬퍼 중 살아있는 쪽의 최신 나이(작을수록 최신). 게이트(afterBlowSeq/checkMirrorRestore)용.
    private long bestA11yAge() {
        long self = a11yAliveAge();
        long h = helperAliveTs > 0 ? System.currentTimeMillis() - helperAliveTs : Long.MAX_VALUE;
        return Math.min(self, h);
    }

    private boolean isHelperInstalled() {
        try { getPackageManager().getPackageInfo(HELPER_PKG, 0); return true; } catch (Throwable t) { return false; }
    }

    // 헬퍼 a11y 컴포넌트를 enabled_accessibility_services에 등록(콜론 RMW — 릴레이 등 다른 a11y 유지) + accessibility_enabled=1.
    //   릴레이가 WRITE_SECURE_SETTINGS 자동부여받는 ROM이라 가능. 설치 후 AMS가 헬퍼 a11y를 바인드하게 함.
    // ★강제 재바인딩 — 제거→1.5s 대기→재삽입. 단순 '목록에 있음'이면 pm install -r로 킬된 a11y가 AMS에 재바인딩 안 됨(릴레이 selfEnableA11y v237 원리).
    //   제거/재삽입 사이 Thread.sleep 필요 → 별도 스레드(메인 블록·ANR 회피).
    private void registerHelperA11y() {
        new Thread(() -> {
            try {
                if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
                android.content.ContentResolver cr = getContentResolver();
                String cur = android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services");
                if (cur == null) cur = "";
                java.util.List<String> parts = new java.util.ArrayList<>();
                // ★648 헬퍼 항목은 짧은 표기(com.cseini.bydhelper/.HelperAccessibilityService)로도 들어가 있어(실측 중복), 긴 표기만 지우면 계속 enabled라 재바인딩이 안 됐다 → 헬퍼 패키지 항목 전부 제거.
                for (String p : cur.split(":")) { p = p.trim(); if (!p.isEmpty() && !p.startsWith(HELPER_PKG + "/")) parts.add(p); }
                String without = android.text.TextUtils.join(":", parts);
                android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", without);   // 헬퍼 컴포넌트만 제거(타 a11y 유지)
                Thread.sleep(1500);
                // ★788 다시 읽은 목록에 덧붙인다 — 1.5초 사이 다른 앱(예: 오버드라이브)이 넣은 항목을 처음 읽은 값으로 덮어써 지우지 않게.
                String cur2 = android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services");
                if (cur2 == null) cur2 = "";
                if (!a11yListed(cur2, HELPER_A11Y_COMPONENT))
                    android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", cur2.isEmpty() ? HELPER_A11Y_COMPONENT : cur2 + ":" + HELPER_A11Y_COMPONENT);       // 재삽입 → AMS 재바인딩
                android.provider.Settings.Secure.putInt(cr, "accessibility_enabled", 1);
                postToD1("HELPER", "헬퍼 a11y 강제 재바인딩(제거→1.5s→재삽입)");
            } catch (Throwable t) { postToD1("HELPER", "헬퍼 a11y 등록 예외: " + t.getClass().getSimpleName()); }
            finally { helperRebindDoneTs = System.currentTimeMillis(); }   // ★v368 권한없음 early return 포함 모든 종료 — 대기 중 오토스타트 해제가 영원히 안 나가는 일 방지
        }, "helperRebind").start();
    }

    // 헬퍼 APK를 assets에서 꺼내 로컬 설치(pm install -r -g) + WRITE_SECURE_SETTINGS grant. 다운로드 없음(길이 가드 불필요).
    //   ★릴레이 자기 재설치와 달리 헬퍼만 재설치 → 헬퍼 a11y만 재바인드, 메인 릴레이(주차/애프터블로우)는 안 건드림.
    //   dadb 블로킹이라 내부 스레드에서 수행(호출측 메인스레드 안전).
    void installHelperFromAsset() {
        new Thread(() -> {
            try {
                java.io.File apk = new java.io.File(getExternalFilesDir(null), "byd-helper.apk");
                if (apk.exists()) apk.delete();
                java.io.InputStream in = getAssets().open("byd-helper.apk");
                java.io.FileOutputStream fo = new java.io.FileOutputStream(apk);
                byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                fo.close(); in.close();
                postToD1("HELPER", "헬퍼 APK 추출 " + apk.length() + "B → push+install");
                java.io.File pk = new java.io.File(getFilesDir(), "adbkey");
                java.io.File pub = new java.io.File(getFilesDir(), "adbkey.pub");
                if (!pk.exists() || !pub.exists()) { postToD1("HELPER", "adb 키 없음 — ADB_AUTH 먼저"); return; }
                dadb.AdbKeyPair kp = dadb.AdbKeyPair.Companion.read(pk, pub);
                dadb.Dadb d = dadb.Dadb.Companion.create("127.0.0.1", 5555, kp, 10000, 120000);
                String remote = "/data/local/tmp/byd_helper.apk";
                d.push(apk, remote, 420, System.currentTimeMillis());
                String script = "#!/system/bin/sh\nLOG=/data/local/tmp/byd_helper.log\n"
                        + "echo \"=== helper install $(date) ===\" >> $LOG\nsleep 1\n"
                        + "echo \"install: $(pm install -r -g " + remote + " 2>&1)\" >> $LOG\nsleep 1\n"
                        + "echo \"grant: $(pm grant " + HELPER_PKG + " android.permission.WRITE_SECURE_SETTINGS 2>&1)\" >> $LOG\n"
                        // ★재설치 후 헬퍼 프로세스 깨우기 — pm install -r로 죽은 헬퍼가 DiLink에 얼어("Binding 갇힘") a11y 재바인드 실패하던 것 방지.
                        //   am startservice(shell 권한)로 KeepAliveService(포그라운드) 기동 → 프로세스 non-frozen → AMS가 a11y 바인드.
                        + "sleep 2\n"
                        // ★648 KeepAliveService는 exported=false라 셸 am startservice가 매번 "not exported"로 실패했다(byd_helper.log 전건) →
                        //   exported CmdReceiver에 명령 브로드캐스트(-f 32=중지된 패키지 포함). a11y 미바인드면 수신 즉시 셀프활성+KeepAlive 기동, 바인드 중이면 모르는 cmd라 무시.
                        + "echo \"wake: $(am broadcast -f 32 -a com.cseini.bydhelper.CMD -n " + HELPER_PKG + "/.CmdReceiver --es cmd wake 2>&1)\" >> $LOG\n"
                        + "rm -f " + remote + "\n";
                String b64 = android.util.Base64.encodeToString(script.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
                d.shell("echo " + b64 + " | base64 -d > /data/local/tmp/byd_helper.sh && chmod 755 /data/local/tmp/byd_helper.sh");
                d.shell("setsid nohup sh /data/local/tmp/byd_helper.sh </dev/null >/dev/null 2>&1 &");
                postToD1("HELPER", "✅ 헬퍼 설치 데몬 실행 — 로그 byd_helper.log");
                d.close();
                // 설치 완료(PACKAGE_REPLACED)엔 수 초 걸림 → 지연 후 a11y 재등록(AMS 바인드 유도).
                //   ★648 그사이 헬퍼 심박이 이미 들어왔으면 생략 — 막 바인드된 헬퍼를 다시 끊던 것 방지(12:27 실측: ack 뒤 11초에 강제 재바인딩).
                final long installTs = System.currentTimeMillis();
                if (mainHandler != null) mainHandler.postDelayed(() -> { if (helperAliveTs < installTs) registerHelperA11y(); }, 12000);
            } catch (Throwable t) { postToD1("HELPER", "헬퍼 설치 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage()); }
        }, "helperinstall").start();
    }

    // ★오토전비 trip wake — 주행 시작 때 HudRemoteReceiver로 TripService만 기동(extras 없음). 오토전비 설정·설치는 오토전비 쪽 몫.
    // ★710 오토전비 설치 여부만 보고(설치·제거·되살리기 없음) — 폰 앱은 autotrip_ver가 있을 때만 "오토전비 화면 설정" 메뉴를 보인다. 시작 때와 패키지 변경 때 갱신.
    private void putAutotripVer() {
        String v = "";
        try { v = String.valueOf(getPackageManager().getPackageInfo("com.cseini.bydautotrip", 0).versionName); } catch (Throwable ignored) {}
        if (relay != null) try { relay.putStatus("autotrip_ver", v); } catch (Throwable ignored) {}
    }
    // ★710 HUDSET — 폰이 보낸 오토전비 화면 설정을 HudRemoteReceiver 방송(extras)으로 전달. hud_enabled(켜기/끄기)도 설정의 하나로 같이 보낸다.
    void broadcastHudSettings(org.json.JSONObject params) {
        try {
            android.content.Intent i = new android.content.Intent("com.cseini.bydautotrip.HUD_REMOTE");
            i.setPackage("com.cseini.bydautotrip");
            i.addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            if (params != null) {
                if (params.has("hud_enabled")) i.putExtra("hud_enabled", params.optBoolean("hud_enabled"));
                for (String k : new String[]{"card_dir","card_shape","card_bg","card_detail","pin_side","pin_side_p","bar_style","bar_clock"}) if (params.has(k)) i.putExtra(k, params.optString(k));
                for (String k : new String[]{"card_font_size","chg_loss","ov_alpha"}) if (params.has(k)) i.putExtra(k, params.optInt(k));
                for (String k : new String[]{"card_full_color","card_trip_color"}) if (params.has(k)) i.putExtra(k, params.optLong(k));
                for (String k : new String[]{"show_full","show_trip","show_trip_time","ov_per100","immersive_overlay","bar_bt","bar_net","pos_reset"}) if (params.has(k)) i.putExtra(k, params.optBoolean(k));
            }
            sendBroadcast(i);
        } catch (Throwable ignored) {}
    }
    void broadcastHud() {
        try {
            android.content.Intent i = new android.content.Intent("com.cseini.bydautotrip.HUD_REMOTE");
            i.setPackage("com.cseini.bydautotrip");
            i.addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            sendBroadcast(i);
        } catch (Throwable ignored) {}
    }
    // ★723 오토전비 깨우기(오토전비 세션 요청·운영자 승인 10-04) — 시동 OFF 직후 DiLink 태스크 청소가 릴레이와 오토전비를 함께 죽이는데
    //   (10-03 13:10:37 실측) 릴레이만 스스로 살아나고 오토전비는 재부팅에도 안 떠 23시간 멈춰 있었다(업데이트 요청도 못 가져감).
    //   꺼져 있을 때만 오토전비 6.50 WakeActivity를 셸로 열어 KeepAliveService를 다시 띄운다(725, 아래 실측) — 살아 있으면 생략.
    //   릴레이 시작·시동 켬·오토스타트 해제 완료 때 부른다. 5초 뒤 상태까지 보므로 백그라운드 스레드에서. 반환 = 로그 한 줄(미설치면 null).
    static String wakeAutotrip(android.content.Context c, String why) {
        // ★750 씨라7은 오토전비 깨우기를 하지 않는다(공유판 737과 같게) — 시동을 끄면 전원 서비스가 앱을 죽이고 릴레이가 6~12초 뒤 다시 뜨는데,
        //   그때 셸로 화면(WakeActivity)을 열면 화면 꺼짐 애니메이션(ColorFade)이 깨져 시스템이 재시작했다(10-05 공유판 744~746 씨라7 다수,
        //   dropbox "Error during updateTexImage" — 10-02 701 때와 같은 크래시). 돌핀만 필요한 기능(돌핀 롬은 방송으로 꺼진 오토전비를 못 띄움, 725).
        if (IS_D5) return null;
        Boolean before = autotripStopped(c);
        if (before == null) return null;
        String psBefore = autotripProcs(c);
        if (psBefore.contains("keepalive")) return "깨움(" + why + ") 생략 — 이미 실행 중(" + psBefore + ")";   // ★725 살아 있으면 아무것도 안 연다(운전 중 화면 방해 없음)
        // ★725 10-04 실측: 돌핀 롬은 방송(앱이 보내든 셸이 보내든)으로는 꺼진 오토전비를 못 띄우고 액티비티 시작으로만 떴다.
        //   오토전비 6.50의 화면 없는 WakeActivity(서비스 시작 후 바로 닫힘)를 셸로 연다(셸은 백그라운드 화면 시작 제한 밖). 6.49 이하·셸 없음은 방송.
        String how = null;
        boolean hasWake = false;
        try { c.getPackageManager().getActivityInfo(new android.content.ComponentName("com.cseini.bydautotrip", "com.cseini.bydautotrip.WakeActivity"), 0); hasWake = true; } catch (Throwable ignored) {}
        if (hasWake) {
            String r = AdbAccess.runShell(c, "am start -n com.cseini.bydautotrip/.WakeActivity");
            if (r != null) how = r.contains("Error") ? "깨우기 화면 열기 오류(" + r.replace('\n', ' ').trim() + ")" : "깨우기 화면 열기";
        }
        if (how == null) {
            try {
                android.content.Intent i = new android.content.Intent("com.cseini.bydsniffer.WAKE_AUTOTRIP");
                i.setComponent(new android.content.ComponentName("com.cseini.bydautotrip", "com.cseini.bydautotrip.BootReceiver"));
                i.addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                c.sendBroadcast(i);
                how = hasWake ? "방송(셸 없음)" : "방송(깨우기 화면 없는 버전)";
            } catch (Throwable t) { return "깨움(" + why + ") 보내기 실패 " + t.getClass().getSimpleName() + " · 정지상태 " + (before ? "예" : "아니오") + " · 실행 " + psBefore; }
        }
        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
        Boolean after = autotripStopped(c);
        return "깨움(" + why + ") " + how + " · 정지상태 " + (before ? "예" : "아니오") + "→" + (after == null ? "?" : (after ? "예" : "아니오")) + " · 실행 " + psBefore + "→" + autotripProcs(c);
    }
    private static Boolean autotripStopped(android.content.Context c) {
        try { return (c.getPackageManager().getApplicationInfo("com.cseini.bydautotrip", 0).flags & android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0; }
        catch (Throwable t) { return null; }
    }
    // 로컬 셸로 오토전비 프로세스(본체·keepalive) — 셸이 없으면 "확인불가"
    private static String autotripProcs(android.content.Context c) {
        String r = AdbAccess.runShell(c, "ps -A -o NAME 2>/dev/null | grep bydautotrip");
        if (r == null) return "확인불가";
        StringBuilder sb = new StringBuilder();
        for (String ln : r.split("\n")) {
            ln = ln.trim();
            if (!ln.startsWith("com.cseini.bydautotrip")) continue;
            if (sb.length() > 0) sb.append('+');
            sb.append(ln.equals("com.cseini.bydautotrip") ? "본체" : ln.substring(ln.indexOf(':') + 1));
        }
        return sb.length() == 0 ? "없음" : sb.toString();
    }
    private void wakeAutotripAsync(final String why) {
        new Thread(() -> { String r = wakeAutotrip(getApplicationContext(), why); if (r != null) postToD1("AUTOTRIPWAKE", r); }, "autotrip-wake").start();
    }

    // ★654 저장장치 관찰(운영자 09-29 승인, 센트리 선행조사) — D5(씨라7)가 시동 OFF 뒤 USB/SD를 살려 두는지 기록이 전혀 없다(D3 돌핀은 SH 실측 생존).
    //   셸 없이 공개 API만 쓴다: StorageManager 볼륨 상태 + UsbManager 장치 목록 + /storage 목록. 시동 OFF +0·1·5·30·120분에 relay_state storage_diag(실시간, 링버퍼 무관)로
    //   올리고 STORAGE 로그도 남긴다. OFF 표식(sd_off_ts)은 prefs라 D5 강제종료→재기동 뒤에도 앵커루프(60초)가 남은 시점을 이어서 찍는다. 동작 변경 없음.
    private static final int[] SD_MARKS_MIN = {0, 1, 5, 30, 120};
    private String storageDiag() {
        StringBuilder sb = new StringBuilder();
        try {
            android.os.storage.StorageManager sm = (android.os.storage.StorageManager) getSystemService(STORAGE_SERVICE);
            java.util.List<android.os.storage.StorageVolume> vols = sm != null ? sm.getStorageVolumes() : null;
            int n = 0;
            if (vols != null) for (android.os.storage.StorageVolume v : vols) {
                if (v.isPrimary() && !v.isRemovable()) continue;   // 내부(emulated)는 생략 — 외장(SD/USB)만
                String id = v.getUuid();
                if (id == null) { try { Object p = v.getClass().getMethod("getPath").invoke(v); id = p == null ? "?" : String.valueOf(p); id = id.substring(id.lastIndexOf('/') + 1); } catch (Throwable ig) { id = "?"; } }
                sb.append(id).append(':').append(v.getState()).append(v.isRemovable() ? "" : "(고정)").append(' '); n++;
            }
            if (n == 0) sb.append(vols == null ? "vol=? " : "외장볼륨없음 ");
        } catch (Throwable t) { sb.append("vol!").append(t.getClass().getSimpleName()).append(' '); }
        try { String[] ls = new java.io.File("/storage").list(); sb.append("| /storage=").append(ls == null ? "?" : java.util.Arrays.toString(ls)); }
        catch (Throwable t) { sb.append("| /storage!"); }
        try {
            android.hardware.usb.UsbManager um = (android.hardware.usb.UsbManager) getSystemService(USB_SERVICE);
            java.util.Map<String, android.hardware.usb.UsbDevice> dl = um != null ? um.getDeviceList() : null;
            sb.append(" | usb=").append(dl == null ? "?" : String.valueOf(dl.size()));
            if (dl != null && !dl.isEmpty()) {
                StringBuilder ids = new StringBuilder();
                for (android.hardware.usb.UsbDevice d : dl.values()) { if (ids.length() > 110) { ids.append("…"); break; } ids.append(String.format(java.util.Locale.US, "%04x:%04x ", d.getVendorId(), d.getProductId())); }
                sb.append('[').append(ids.toString().trim()).append(']');
            }
        } catch (Throwable t) { sb.append(" | usb!").append(t.getClass().getSimpleName()); }
        return sb.toString();
    }
    private void storageSnap(String label) {
        try {
            String line = label + " · " + storageDiag() + " · 시동=" + (relay != null ? relay.ignitionState() : -9) + " D5=" + DiLink5Support.isDiLink5();
            if (relay != null) { relay.putStatus("storage_diag", line); relay.putStatus("storage_diag_ts", System.currentTimeMillis()); }
            postToD1("STORAGE", line);
        } catch (Throwable t) { postToD1("STORAGE", label + " 관찰 실패: " + t.getClass().getSimpleName()); }
    }
    private void maybeStorageDiag() {
        try {
            if (prefs == null || relay == null) return;
            if (relay.ignitionState() == 1) return;   // 켜져 있으면 ON 훅이 이미 정리
            long off = prefs.getLong("sd_off_ts", 0);
            if (off <= 0) {
                // 재기동 등으로 OFF 엣지를 못 본 경우 — 판정기가 저장한 마지막 OFF 시각(ign_last=0, ign_ts)을 기준으로 시작
                if (relay.ignitionState() == 0 && prefs.getInt("ign_last", -1) == 0 && prefs.getLong("ign_ts", 0) > 0) { off = prefs.getLong("ign_ts", 0); prefs.edit().putLong("sd_off_ts", off).putInt("sd_done", 0).apply(); }
                else return;
            }
            long elMin = (System.currentTimeMillis() - off) / 60000;
            int done = prefs.getInt("sd_done", 0), due = -1, mask = done;
            for (int i = 0; i < SD_MARKS_MIN.length; i++) if ((done & (1 << i)) == 0 && elMin >= SD_MARKS_MIN[i]) { due = i; mask |= (1 << i); }
            if (due < 0) return;
            prefs.edit().putInt("sd_done", mask).apply();   // 여러 시점이 한꺼번에 밀렸으면(오래 죽어 있다 재기동) 가장 늦은 시점 하나만 찍는다
            storageSnap("시동OFF +" + SD_MARKS_MIN[due] + "분(실제 +" + elMin + "분)");
        } catch (Throwable ignored) {}
    }
    private long sdEvtLastMs = 0; private int sdEvtN = 0; private long sdEvtWin = 0;
    // ★710/713 오토전비 6.44+ 적용 상태(action AUTOTRIP_STATE, setPackage 릴레이) → relay_state.autotrip_hud(JSON) — 폰이 마지막으로 보낸 값이 아니라 실제 적용값을 보이게.
    //   설정이 바뀔 때·오토전비 시작 때만 오므로 상태 전체 재전송은 드물다. 키를 정렬하고 시각 값은 넣지 않는다(같은 값이 다시 오면 재전송 안 되게).
    private void registerAutotripStateReceiver() {
        try {
            registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, Intent it) {
                    try {
                        android.os.Bundle b = it.getExtras();
                        if (b == null || relay == null) return;
                        java.util.List<String> keys = new java.util.ArrayList<>(b.keySet());
                        java.util.Collections.sort(keys);
                        org.json.JSONObject o = new org.json.JSONObject();
                        for (String k : keys) {
                            Object v = b.get(k);
                            if (v instanceof String || v instanceof Number || v instanceof Boolean) o.put(k, v);
                        }
                        relay.putStatus("autotrip_hud", o.toString());
                    } catch (Throwable ignored) {}
                }
            }, new android.content.IntentFilter("com.cseini.bydsniffer.AUTOTRIP_STATE"));
        } catch (Throwable t) { postToD1("HUD", "오토전비 상태 리시버 등록 실패: " + t.getClass().getSimpleName()); }
    }
    private void registerStorageReceivers() {
        try {
            android.content.IntentFilter mf = new android.content.IntentFilter();
            for (String a : new String[]{Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL, Intent.ACTION_MEDIA_UNMOUNTABLE}) mf.addAction(a);
            mf.addDataScheme("file");
            registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, Intent it) {
                    String a = it.getAction() == null ? "?" : it.getAction().substring(it.getAction().lastIndexOf('.') + 1);
                    storageEvent("미디어 " + a + " " + it.getData());
                }
            }, mf);
            android.content.IntentFilter uf = new android.content.IntentFilter();
            uf.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED); uf.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED);
            registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, Intent it) {
                    android.hardware.usb.UsbDevice d = it.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE);
                    boolean att = android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(it.getAction());
                    storageEvent("USB " + (att ? "연결" : "분리") + (d != null ? String.format(java.util.Locale.US, " %04x:%04x", d.getVendorId(), d.getProductId()) : ""));
                }
            }, uf);
        } catch (Throwable t) { postToD1("STORAGE", "리시버 등록 실패: " + t.getClass().getSimpleName()); }
    }
    private synchronized void storageEvent(String what) {
        long now = System.currentTimeMillis();
        if (now - sdEvtWin > 600_000) { sdEvtWin = now; sdEvtN = 0; }
        if (++sdEvtN > 30) return;   // 10분 30건 상한(시동 ON 때 허브 재연결 연쇄 대비)
        sdEvtLastMs = now;
        if (relay != null) relay.putStatus("storage_evt", what + " @" + now);
        postToD1("STORAGE", what + " · 시동=" + (relay != null ? relay.ignitionState() : -9) + " · " + storageDiag());
    }

    // ★650 릴레이 자기 데이터 사용량(운영자 09-29 승인 — 자루씨 차량 데이터 2GB 소진 사례). 앵커루프(60초)마다 이 앱 UID의 송수신 바이트 증가분을
    //   그 순간 연결(모바일=차량 통신/와이파이=핫스팟 등)에 귀속해 오늘·이번 달(KST) 누적 → relay_state data_usage. 날이 바뀌면 전날 합계를 로그로 1줄.
    //   UID 카운터는 헤드유닛 재부팅 때만 0으로 돌아가므로 마지막 합계를 prefs에 두고 이어서 센다(감소하면 기준만 다시 잡음).
    // ★713/708 차 전체 데이터 사용량(운영자 10-03 "차량 상태에 월별로 차량 자체·핫스팟·합계") — 안드로이드 망별 사용 기록(NetworkStatsManager, 기기 전체).
    //   읽으려면 사용 기록 접근(GET_USAGE_STATS)이 필요하다. 폰이 처음 요청할 때만 무선 디버깅(ADB)으로 스스로 허용한다(모든 차 일괄 아님).
    //   헤드유닛 기록은 약 3개월치라 이번 달 포함 4개월을 돌려준다(오래된 달은 0일 수 있음). 와이파이에는 폰 핫스팟과 다른 와이파이가 함께 들어간다.
    private String dataMonthsJson() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            String[] nets = {"mobile", "wifi", "other"};
            org.json.JSONObject rm = new org.json.JSONObject();
            for (String n : nets) rm.put(n, prefs.getLong("du_m_" + n, 0));
            o.put("relay_month", rm);
            boolean ok = usageAccessOk();
            if (!ok) {
                // ADB가 멈추면 명령 루프(폴링)가 같이 멈추므로 별도 스레드 + 8초 제한(silentInstall과 같은 방식)
                final java.util.concurrent.atomic.AtomicReference<String> out = new java.util.concurrent.atomic.AtomicReference<>(null);
                Thread t = new Thread(() -> out.set(AdbAccess.runShell(this, "appops set " + getPackageName() + " GET_USAGE_STATS allow")));
                t.setDaemon(true); t.start();
                try { t.join(8000); } catch (InterruptedException ignored) {}
                String r = t.isAlive() ? "시간초과" : out.get();
                ok = usageAccessOk();
                if (!ok) o.put("grant", r == null ? "무선 디버깅(ADB) 연결 안 됨" : ("허용 실패 " + r).trim());
            }
            o.put("granted", ok);
            if (ok) {
                android.app.usage.NetworkStatsManager nsm = (android.app.usage.NetworkStatsManager) getSystemService(NETWORK_STATS_SERVICE);
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.set(java.util.Calendar.DAY_OF_MONTH, 1); c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0);
                c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
                long now = System.currentTimeMillis();
                org.json.JSONArray months = new org.json.JSONArray();
                for (int i = 0; i < 4; i++) {
                    long start = c.getTimeInMillis();
                    java.util.Calendar next = (java.util.Calendar) c.clone(); next.add(java.util.Calendar.MONTH, 1);
                    long end = Math.min(next.getTimeInMillis(), now);
                    long[] ru = relayUidBytes(nsm, start, end);
                    months.put(new org.json.JSONObject()
                            .put("month", String.format(java.util.Locale.US, "%04d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1))
                            .put("mobile", nsBytes(nsm, android.net.ConnectivityManager.TYPE_MOBILE, start, end))
                            .put("wifi", nsBytes(nsm, android.net.ConnectivityManager.TYPE_WIFI, start, end))
                            .put("other", nsBytes(nsm, android.net.ConnectivityManager.TYPE_ETHERNET, start, end))
                            .put("relay_mobile", ru[0]).put("relay_wifi", ru[1]));
                    c.add(java.util.Calendar.MONTH, -1);
                }
                o.put("months", months);
            }
        } catch (Throwable t) { try { o.put("error", t.getClass().getSimpleName() + ": " + t.getMessage()); } catch (Throwable ignored) {} }
        return o.toString();
    }
    private boolean usageAccessOk() {
        try {
            android.app.AppOpsManager a = (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
            return a.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) { return false; }
    }
    // ★709/713 릴레이 몫을 순정 "데이터 사용 내역"과 같은 출처(안드로이드 앱별 기록, 이 앱 UID)로(운영자 10-03) — 1분 단위로 몰아 세던 근사치(TrafficStats) 대신.
    private static long[] relayUidBytes(android.app.usage.NetworkStatsManager nsm, long start, long end) {
        long[] r = {0, 0};
        int[] types = {android.net.ConnectivityManager.TYPE_MOBILE, android.net.ConnectivityManager.TYPE_WIFI};
        for (int i = 0; i < 2; i++) {
            android.app.usage.NetworkStats ns = null;
            try {
                ns = nsm.queryDetailsForUid(types[i], null, start, end, android.os.Process.myUid());
                android.app.usage.NetworkStats.Bucket b = new android.app.usage.NetworkStats.Bucket();
                while (ns.hasNextBucket()) { ns.getNextBucket(b); r[i] += b.getRxBytes() + b.getTxBytes(); }
            } catch (Throwable t) { r[i] = -1; }
            finally { if (ns != null) try { ns.close(); } catch (Throwable ignored) {} }
        }
        return r;
    }
    private static long nsBytes(android.app.usage.NetworkStatsManager nsm, int type, long start, long end) {
        try {
            android.app.usage.NetworkStats.Bucket b = nsm.querySummaryForDevice(type, null, start, end);
            return b == null ? 0 : b.getRxBytes() + b.getTxBytes();
        } catch (Throwable t) { return -1; }
    }
    private volatile String lastDataUsage = "", lastDataByUse = "";
    private long duLastLoop = -1;   // ★744 직전 샘플의 내부 통신(127.0.0.1) 읽기 누계 — 프로세스마다 새로(첫 샘플은 기준만)
    private void sampleDataUsage() {
        try {
            if (prefs == null) return;
            int uid = android.os.Process.myUid();
            long rx = android.net.TrafficStats.getUidRxBytes(uid), tx = android.net.TrafficStats.getUidTxBytes(uid);
            if (rx < 0 || tx < 0) return;
            long tot = rx + tx, last = prefs.getLong("du_last_total", -1);
            // ★744 TrafficStats 앱 통신량엔 씨라7 카메라 프레임 내부 통신(셸 브리지 → 127.0.0.1)이 섞인다 — 그만큼 빼야 진짜 망 사용량.
            long loop = com.overdrive.app.camera.dilink5.FastCamTcpBackend.LOOP_RX.get(), dl = duLastLoop < 0 ? -1 : loop - duLastLoop;
            duLastLoop = loop;
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA);
            f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));
            String day = f.format(new java.util.Date()), mon = day.substring(0, 7);
            boolean dayChg = !day.equals(prefs.getString("du_day", day)), monChg = !mon.equals(prefs.getString("du_mon", mon));
            if (dayChg) {
                // ★744 하루 합계 모바일·와이파이는 OS 망별 집계(NetworkStats, 앱 카드·안드 설정 화면과 같은 출처 — 내부 통신 안 섞임)로 그날 하루를 다시 읽는다.
                //   10-04 씨라7 060243 "모바일 344GB(감지 322GB)" 같은 허수 수정. OS 값을 못 읽으면 내부 통신을 뺀 근사치에 "(근사)".
                long[] os = {-1, -1};
                try {
                    long ds = f.parse(prefs.getString("du_day", day)).getTime();
                    os = relayUidBytes((android.app.usage.NetworkStatsManager) getSystemService(NETWORK_STATS_SERVICE), ds, ds + 86400000L);
                } catch (Throwable ignored) {}
                postToD1("DATAUSE", "하루 합계 " + prefs.getString("du_day", "?")
                        + " 모바일 " + (os[0] >= 0 ? mb(os[0]) : mb(prefs.getLong("du_d_mobile", 0)) + "(근사)")
                        + " · 와이파이 " + (os[1] >= 0 ? mb(os[1]) : mb(prefs.getLong("du_d_wifi", 0)) + "(근사)")
                        + " · 기타 " + mb(prefs.getLong("du_d_other", 0)) + " · 내부 통신 제외 " + mb(prefs.getLong("du_d_loop", 0))
                        + " / 모바일 용도별 " + dataUseBreakdown(prefs));
            }
            String[] nets = {"mobile", "wifi", "other"};
            long[] dd = new long[3], mm = new long[3];
            for (int i = 0; i < 3; i++) { dd[i] = dayChg ? 0 : prefs.getLong("du_d_" + nets[i], 0); mm[i] = monChg ? 0 : prefs.getLong("du_m_" + nets[i], 0); }
            long d = (last < 0 || tot < last || dl < 0) ? 0 : Math.max(0, tot - last - dl);
            int k = 2;
            {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                android.net.NetworkCapabilities nc = cm != null ? cm.getNetworkCapabilities(cm.getActiveNetwork()) : null;
                if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) k = 1;
                else if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) k = 0;
                if (d > 0) { dd[k] += d; mm[k] += d; }
            }
            android.content.SharedPreferences.Editor e = prefs.edit().putLong("du_last_total", tot).putString("du_day", day).putString("du_mon", mon);
            for (int i = 0; i < 3; i++) e.putLong("du_d_" + nets[i], dd[i]).putLong("du_m_" + nets[i], mm[i]);
            e.putLong("du_d_loop", (dayChg ? 0 : prefs.getLong("du_d_loop", 0)) + Math.max(0, dl));   // ★744 그날 뺀 내부 통신(검증용)
            // ★699 용도별 계측(관측 전용) — 이번 60초 증가분 d를 모바일일 때만 그 순간 상태 버킷(카메라/감지/시동ON/유휴)에 귀속 + postJson 본문 바이트·횟수.
            //   버킷은 "그 순간 상태" 기준 근사치(카메라 끝난 직후 잔여 등 섞일 수 있음). 날이 바뀌면 0부터.
            long pn = RelayClient.duPollN.getAndSet(0), pb = RelayClient.duPollB.getAndSet(0), xn = RelayClient.duPostN.getAndSet(0), xb = RelayClient.duPostB.getAndSet(0), ph = RelayClient.duPollH.getAndSet(0);
            String[] dk = {"bk_idle", "bk_on", "bk_cam", "bk_det", "poll_n", "poll_b", "post_n", "post_b", "poll_h"};
            long[] dv = new long[dk.length];
            for (int i = 0; i < dk.length; i++) dv[i] = dayChg ? 0 : prefs.getLong("du_d_" + dk[i], 0);
            if (k == 0) {
                if (d > 0) {
                    int bk = (camStreaming || h264Streaming || rtcActive) ? 2 : detectActive ? 3 : (relay != null && relay.ignitionState() == 1) ? 1 : 0;
                    dv[bk] += d;
                }
                dv[4] += pn; dv[5] += pb; dv[6] += xn; dv[7] += xb; dv[8] += ph;
            }
            for (int i = 0; i < dk.length; i++) e.putLong("du_d_" + dk[i], dv[i]);
            // ★701 절감 사전 진단(관측 전용) — 전체 푸시 사유·바뀐 키·압축 효과·폴링 응답 명령 포함 비율. 모바일일 때만 일 누적(그 외엔 비우기만).
            long[] dg = new long[RelayClient.DG_COUNT];
            for (int i = 0; i < dg.length; i++) dg[i] = RelayClient.duDiag.getAndSet(i, 0) + (dayChg ? 0 : prefs.getLong("du_d_dg" + i, 0));
            java.util.Map<String, Integer> chgAdd = new java.util.HashMap<>();
            for (String ck : new java.util.ArrayList<>(RelayClient.duChg.keySet())) { Integer cv = RelayClient.duChg.remove(ck); if (cv != null) chgAdd.put(ck, cv); }
            String chgOld = dayChg ? "" : prefs.getString("du_d_dgchg", "");
            if (k != 0) { for (int i = 0; i < dg.length; i++) dg[i] = dayChg ? 0 : prefs.getLong("du_d_dg" + i, 0); chgAdd.clear(); }
            for (int i = 0; i < dg.length; i++) e.putLong("du_d_dg" + i, dg[i]);
            e.putString("du_d_dgchg", mergeChg(chgOld, chgAdd));
            if (dayChg || prefs.getString("du_dg_since", "").isEmpty()) {
                java.text.SimpleDateFormat hf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA);
                hf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));
                e.putString("du_dg_since", hf.format(new java.util.Date()));
            }
            e.apply();
            // ★측정값은 상태에 안 싣는다(운영자 10-03 "실시간으로 보낼 필요 없다, 폰이 요청할 때만") — 1분마다 바뀌어 상태 전체 재전송을 일으키던 것. DATAUSE 명령으로만 돌려준다.
            lastDataUsage = "오늘 모바일 " + mb(dd[0]) + "·와이파이 " + mb(dd[1])
                    + " / 이번 달 모바일 " + mb(mm[0]) + "·와이파이 " + mb(mm[1]) + "·기타 " + mb(mm[2]);
            lastDataByUse = "오늘 모바일 용도별 " + dataUseBreakdown(prefs);
        } catch (Throwable ignored) {}
    }
    private static String dataUseBreakdown(android.content.SharedPreferences p) {
        return "유휴 " + mb(p.getLong("du_d_bk_idle", 0)) + "·시동ON " + mb(p.getLong("du_d_bk_on", 0)) + "·카메라 " + mb(p.getLong("du_d_bk_cam", 0)) + "·감지 " + mb(p.getLong("du_d_bk_det", 0))
                + " | 폴링 " + p.getLong("du_d_poll_n", 0) + "회 " + mb(p.getLong("du_d_poll_b", 0)) + "(헤더 " + mb(p.getLong("du_d_poll_h", 0)) + " 별도" + (RelayClient.duProto.isEmpty() ? "" : "·" + RelayClient.duProto) + ")·그외전송 " + p.getLong("du_d_post_n", 0) + "회 " + mb(p.getLong("du_d_post_b", 0)) + "(본문만)"
                + " | 진단701(" + p.getString("du_dg_since", "?") + "부터·모바일) 유휴 전체푸시 변경" + p.getLong("du_d_dg0", 0) + "·5분정기" + p.getLong("du_d_dg1", 0) + "·최초재전송" + p.getLong("du_d_dg2", 0)
                + "·비유휴 " + p.getLong("du_d_dg3", 0) + " / 압축 " + p.getLong("du_d_dg6", 0) + "회 원본" + mb(p.getLong("du_d_dg4", 0)) + "→gz " + mb(p.getLong("du_d_dg5", 0))
                + " / 폴링응답 명령포함 유휴 " + p.getLong("du_d_dg8", 0) + "/" + p.getLong("du_d_dg7", 0) + "·비유휴 " + p.getLong("du_d_dg10", 0) + "/" + p.getLong("du_d_dg9", 0)
                + "·전체푸시응답 " + p.getLong("du_d_dg11", 0) + " / 델타푸시 " + p.getLong("du_d_dg12", 0) + "회 / 유휴 바뀐키 상위 " + topChg(p.getString("du_d_dgchg", ""), 8);
    }
    private static java.util.Map<String, Integer> parseChg(String s) {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String part : s.split(",")) {
            int q = part.lastIndexOf('=');
            if (q <= 0) continue;
            try { m.put(part.substring(0, q), Integer.parseInt(part.substring(q + 1))); } catch (NumberFormatException ignored) {}
        }
        return m;
    }
    private static String topChg(String s, int n) {
        java.util.List<java.util.Map.Entry<String, Integer>> l = new java.util.ArrayList<>(parseChg(s).entrySet());
        java.util.Collections.sort(l, (a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < n; i++) sb.append(i > 0 ? "," : "").append(l.get(i).getKey()).append("×").append(l.get(i).getValue());
        return sb.length() == 0 ? "없음" : sb.toString();
    }
    private static String mergeChg(String old, java.util.Map<String, Integer> add) {
        java.util.Map<String, Integer> m = parseChg(old);
        for (java.util.Map.Entry<String, Integer> e : add.entrySet()) m.merge(e.getKey(), e.getValue(), Integer::sum);
        java.util.List<java.util.Map.Entry<String, Integer>> l = new java.util.ArrayList<>(m.entrySet());
        java.util.Collections.sort(l, (a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < 20; i++) sb.append(i > 0 ? "," : "").append(l.get(i).getKey()).append('=').append(l.get(i).getValue());
        return sb.toString();
    }
    private static String mb(long b) { return String.format(java.util.Locale.KOREA, "%.1fMB", b / 1048576.0); }

    // 앵커루프(60초)에서 호출 — 헬퍼 미설치면 설치, 설치됐는데 a11y ack stale(죽음/미바인드)면 로컬 재설치로 재바인드.
    private void maybeManageHelper() {
        // ★공유판 허용(2026-08-31 전수조사) — 헬퍼 설치·부활은 installHelperFromAsset(dadb)라 공유차주도 가능(WiFi ADB 열림+WRITE_SECURE 자동부여로 adb키 확보).
        //   기존 운영자 전용은 자체 a11y 좀비의 화면튐을 공유차주에 강요했음(씰 실측). 무선ADB 없으면 installHelperFromAsset이 무해 실패(자체 a11y 폴백).
        if (DiLink5Support.isDiLink5()) return;   // ★788 씨라7은 헬퍼 관리 끔(오토스타트 해제가 필요 없는 차종)
        long now = System.currentTimeMillis();
        boolean hold = now < helperHoldUntil;   // ★788 오토스타트 해제 중 — 설치·재설치·업그레이드는 미루고 깨우기·재연결만 한다
        if (!isHelperInstalled()) {
            if (hold || now - lastHelperReinstall < 45000) return;   // ★미설치 재설치도 45초로 단축
            lastHelperReinstall = now;
            postToD1("HELPER", "헬퍼 미설치 → 설치 시작");
            installHelperFromAsset();
            return;
        }
        // ★헬퍼 버전 업그레이드(2026-09-08 '링크락 헬퍼' v3): 번들 asset versionCode > 설치본이면 재설치. 기존엔 미설치/좀비만 재설치라 이름·코드 갱신이 영원히 안 들어감.
        //   서명 불일치 등으로 설치가 계속 실패하면 45s마다 무한 시도 → 프로세스당 3회 상한. ★헬퍼 재빌드 시 HELPER_ASSET_VER 같이 올릴 것.
        if (helperUpgradeTries < 3 && !hold) {
            int inst = -1;
            try { inst = getPackageManager().getPackageInfo(HELPER_PKG, 0).versionCode; } catch (Throwable ignored) {}
            if (inst >= 0 && inst < HELPER_ASSET_VER) {
                if (now - lastHelperReinstall < 45000) return;
                lastHelperReinstall = now; helperUpgradeTries++;
                postToD1("HELPER", "헬퍼 구버전 v" + inst + " → 번들 v" + HELPER_ASSET_VER + " 재설치(" + helperUpgradeTries + "/3)");
                installHelperFromAsset();
                return;
            }
        }
        long h = helperAliveAgeMs();
        if (h < 0 || h > 45000) {   // ack 없음/45초↑ = 미바인드/좀비 → 재설치. ★20→45s: 심박 gap(DiLink 순간얼림)에 살아있는 헬퍼를 죽이던 오재설치 방지.
            if (now - lastHelperReinstall < 45000 || now - lastHelperRebindOnly < 45000) return;   // ★45초 throttle(재설치 1사이클) — 안 살면 바로 재시도(구 3분에서 단축)
            // ★648 재설치는 헬퍼를 죽이고 깨우기도 실패해 1분마다 재설치 악순환을 만들었다(09-29 12:12~12:15) → 먼저 재연결만, 45초 뒤에도 안 살면 재설치.
            if (lastHelperRebindOnly <= lastHelperReinstall || helperAliveTs > lastHelperRebindOnly) {
                lastHelperRebindOnly = now;
                postToD1("HELPER", "헬퍼 a11y stale(" + (h < 0 ? "ack없음" : (h / 1000) + "s") + ") → 깨우기 방송 뒤 재연결(재설치 보류) [" + ackDiag() + "]");
                wakeHelperThenRebind();
                return;
            }
            // ★663(FB1500 청주 아토3, 2026-09-30) — 시동OFF로 릴레이가 강제 재기동된 직후엔 헬퍼 접근성이 아직 붙는 중이라 "응답 없음"으로 보인다.
            //   재기동 2분 뒤 헬퍼를 지우고 다시 깔면서(패키지 REMOVED/ADDED 방송·강제 재바인딩) 릴레이가 바빠져, 그 사이 보낸 공조·창문 환기 명령 3건이 90초 만료됐다.
            //   서비스 시작 후 4분 안에는 재연결 시도까지만 하고 재설치는 미룬다(그 뒤에도 안 붙으면 종전대로 재설치).
            //   ★783(10-06 운영자 승인 "재설치 전환 앞당기기") — 하단바 튐 조건이 4분 가까이 이어졌다(00:01 운영자 차). 최근 60초 동안 폰 명령·차 활동이
            //   없으면(663이 지키려던 명령이 없으면) 90초로 앞당기고, 명령이 오가는 중이면 종전대로 4분까지 미룬다.
            boolean quiet = relay == null || now - relay.lastActivityMs() > 60_000L;
            if (now - serviceCreatedMs < (quiet ? 90_000L : 240_000L)) return;
            if (hold) return;   // ★788 재설치는 오토스타트 해제가 끝난 뒤로
            lastHelperReinstall = now;
            postToD1("HELPER", "헬퍼 a11y stale(" + (h < 0 ? "ack없음" : (h / 1000) + "s") + ") — 재연결로 안 살아남 → 로컬 재설치 [" + ackDiag() + "]");
            registerHelperA11y();
            installHelperFromAsset();
        }
    }

    // ★761 BYD가 시동 전환 때 헬퍼를 강제 종료하면 패키지가 멈춤(stopped)으로 남아, 접근성 목록을 다시 넣어도 바인드되지 않았다
    //   (센트리 세션 집계: 운영자 차 "재연결만" 16회 전부 바인드 0, 재설치만 살림 — FB2011 하단바 반복의 뿌리).
    //   멈춤은 FLAG_INCLUDE_STOPPED_PACKAGES 명시 방송이 수신기에 닿을 때 풀린다(10-05 16:54 운영자 차 시험: 강제 종료 → 셸 깨우기 방송 →
    //   stopped=false → 그 뒤 목록 재삽입 2초 만에 바인드+ack). 앱 방송 + (adb 키 있으면) 셸 방송으로 멈춤을 푼 다음 재삽입한다.
    private void wakeHelperThenRebind() {
        new Thread(() -> {
            String r = sendHelperCmd(this, "wake", null);
            String sh = new java.io.File(getFilesDir(), "adbkey").exists()
                    ? AdbAccess.runShell(this, "am broadcast -f 32 -n " + HELPER_PKG + "/.CmdReceiver -a " + HELPER_CMD_ACTION + " --es cmd wake 2>&1 | tail -1") : null;
            postToD1("HELPER", "헬퍼 깨우기 — " + r + (sh != null ? " · 셸 " + sh.trim() : ""));
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            registerHelperA11y();
        }, "helperWake").start();
    }

    // 헬퍼 ack 수신 리시버(메인 프로세스). alive/connected/cmd_done → helperAliveTs 갱신, mirror → mirror_fold 파일 기록.
    //   ★783 전용 스레드에서 받는다 — 메인 스레드가 바쁘면 ack 처리가 밀려 "ack 없음"으로 보이던 가능성 차단(10-06 00:01 운영자 차: 헬퍼는 붙었는데 3분 넘게 0건).
    //   헬퍼 v5가 실어 보내는 seq(순번)·pst(헬퍼 프로세스 시작 시각)·ts로 첫 ack·끊김 뒤 ack의 지연과 헬퍼 프로세스 교체를 기록한다(진단).
    private volatile long helperLastPst = 0;
    private volatile int helperLastSeq = 0;
    private void registerHelperAckReceiver() {
        try {
            android.os.HandlerThread ht = new android.os.HandlerThread("helper-ack");
            ht.start();
            registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, android.content.Intent it) {
                    try {
                        String kind = it.getStringExtra("kind");
                        long now = System.currentTimeMillis();
                        long prevAlive = helperAliveTs;
                        long sentTs = it.getLongExtra("ts", 0);
                        int seq = it.getIntExtra("seq", -1);
                        long pst = it.getLongExtra("pst", 0);
                        helperAliveTs = now;
                        String diag = (sentTs > 0 ? " · 보낸 뒤 " + (now - sentTs) + "ms" : "") + (seq >= 0 ? " · 순번 " + seq : "")
                                + (pst > 0 ? " · 헬퍼 프로세스 " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(new java.util.Date(pst)) + " 시작" : "");
                        if (helperAckCount++ == 0) postToD1("HELPER", "첫 ack 수신 kind=" + kind + " — 서비스 시작 후 " + (now - serviceCreatedMs) / 1000 + "초" + diag);
                        else if (prevAlive > 0 && now - prevAlive > 45000) postToD1("HELPER", "ack 다시 옴 kind=" + kind + " — " + (now - prevAlive) / 1000 + "초 만" + diag
                                + (pst > 0 && pst == helperLastPst && seq > helperLastSeq + 1 ? " · 그 사이 헬퍼가 보낸 " + (seq - helperLastSeq - 1) + "건이 안 닿음" : ""));
                        if (pst > 0 && helperLastPst > 0 && pst != helperLastPst) postToD1("HELPER", "헬퍼 프로세스 바뀜(새로 뜸)" + diag);
                        if (pst > 0) { helperLastPst = pst; helperLastSeq = seq; }
                        if ("connected".equals(kind)) postToD1("HELPER", "헬퍼 a11y 바인드됨(ack)" + diag);
                        if ("mirror".equals(kind)) {
                            String st = it.getStringExtra("detail");
                            if (st != null && (st.equals("0") || st.equals("1"))) {
                                // ★릴레이 a11y가 쓰던 mirror_fold 파일을 여기서 대신 기록 → afterBlowSeq(readMirrorFoldFile)·상태push 그대로 동작.
                                try { java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "mirror_fold")); fo.write(st.getBytes("UTF-8")); fo.close(); } catch (Throwable ignored) {}
                                if (relay != null) try { relay.putStatus("mirror_fold", Integer.parseInt(st)); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }, new android.content.IntentFilter(HELPER_ACK_ACTION), null, new android.os.Handler(ht.getLooper()));
            ackReceiverRegMs = System.currentTimeMillis();
            postToD1("HELPER", "ack 리시버 등록 완료 — 서비스 시작 후 " + (ackReceiverRegMs - serviceCreatedMs) / 1000 + "초");
        } catch (Throwable t) { postToD1("HELPER", "ack 리시버 등록 예외: " + t.getClass().getSimpleName()); }
    }

    // ── D1 로깅 (덤프·명령결과를 Worker /log에 올려 PC에서 조회) ──
    private static final String LOG_URL = "https://byd.cseini.co.kr/log";
    private static final String LOG_AUTH = BuildConfig.RELAY_SECRET;
    // ★v67 네트워크 끊김 추적 — 전송 실패 구간을 기록해두고 복구되면 "N초간 오프라인이었음"을 보고.
    //   (프로세스는 살아있는데 D1에 못 보내 '릴레이 오프라인'으로 보이던 케이스를 사망과 구분)
    private volatile long netDownSince = 0;
    private volatile int netFailCount = 0;
    // ★v106 운영자 전용 게이트 — 타 사용자(공유) 기기는 워커·D1에 아예 접속하지 않는 게 전제.
    //   uid가 운영자(16501)일 때만 D1 로그·워커 폴링·차량이벤트 push. 상태비콘(BLE) 등 로컬 기능은 모두 유지.
    static final String OPERATOR_UID = "0";
    private boolean operatorDevice() { return operatorDevice(this); }
    // ★op flavor = 운영자 헤드유닛(로그 라이브 전송). uid 하드코딩(16501) 판정은 다계정 운영(시동 SETCLOUDCREDS로
    //   릴레이 uid가 서브계정으로 바뀜, 실측 24016)에 깨져 로그가 통째 버퍼링(전송 死)되던 근본 버그 → flavor 기준으로.
    //   ★static화(2026-09-25) — KeepAliveAccessibilityService(별도 :relay 프로세스)가 이 판정을 못 써 옛 uid단순비교를
    //   복제해 갖고 있었고 같은 버그가 거기서 재발 가능했다. 한 곳으로 통일.
    static boolean operatorDevice(android.content.Context ctx) {
        if (BuildConfig.IS_OPERATOR) return true;
        try { return OPERATOR_UID.equals(ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(KEY_UID, "")); } catch (Throwable t) { return false; }
    }

    // ★전비/진단 수집기가 호출 — relay를 lazy 접근(relay는 늦게 초기화됨). relay_state에 넣어 앱 대시보드가 폴링.
    private volatile int lastTripSoc = -1;   // ★센트리 영상 배터리 표시(OSD)용 — TripCollector가 올리는 trip_soc를 그대로 보관(-1=아직 모름)
    void publishTrip(String key, int val) { if ("trip_soc".equals(key)) lastTripSoc = val; try { if (relay != null) relay.putStatus(key, val); } catch (Throwable ignored) {} }

    // ★씨라이언7 진단 업로드 — 공개 /diag로 raw POST(운영자 게이트 없음: 공유 차주가 동의 버튼 누른 결과를 올림).
    private void postDiag(String report) {
        new Thread(() -> {
            java.net.HttpURLConnection c = null;
            try {
                String vin = "";
                try { vin = prefs != null ? prefs.getString(KEY_VIN, "") : ""; } catch (Throwable ignored) {}
                org.json.JSONObject b = new org.json.JSONObject();
                b.put("vin", vin).put("device", android.os.Build.MODEL + "/" + DiLink5Support.vehicleType()).put("report", report);
                c = (java.net.HttpURLConnection) new java.net.URL("https://byd.cseini.co.kr/diag").openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("content-type", "application/json");
                c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setDoOutput(true);
                c.getOutputStream().write(b.toString().getBytes("UTF-8"));
                int code = c.getResponseCode();
                postToD1("D5PROBE", "diag 업로드 → HTTP " + code);
            } catch (Throwable t) { postToD1("D5PROBE", "diag 업로드 실패: " + t.getClass().getSimpleName()); }
            finally { if (c != null) try { c.disconnect(); } catch (Throwable ignored) {} }
        }, "postDiag").start();
    }

    // ★공유 차주 진단 로그 링버퍼 — 라이브 전송 대신 로컬 축적(전송 전까진 데이터 0), '로그 보내기' 버튼으로 배치 전송.
    private static final java.util.ArrayDeque<String> LOG_BUFFER = new java.util.ArrayDeque<>();
    private void bufferLog(String category, String message) {
        if (category == null || message == null) return;
        synchronized (LOG_BUFFER) {
            LOG_BUFFER.addLast(System.currentTimeMillis() + "\t" + category + "\t" + message);
            // ★2000→8000(2026-09-16, 로그분석 세션 지적) — FRONT([진단]/[신호진단], 2초마다 발화)처럼 수다스러운 카테고리가
            //   CAMRTC 같은 저빈도·일회성 이벤트를 몇 분 안에 링버퍼에서 밀어냄 → LOGFLUSH(피드백 제출 등)가 몇 분 뒤에 일어나면
            //   그 사이 CAMRTC 로그가 통째로 증발(폰 쪽 sendFeedback 1500→4000 상향과 동일 계열 버그, 릴레이 쪽도 동일 조치).
            while (LOG_BUFFER.size() > 8000) LOG_BUFFER.removeFirst();
        }
    }

    // '로그 보내기' 버튼(MainActivity가 스레드에서 호출) — 쌓인 로그를 200줄씩 배치 전송, 성공 시 버퍼 비움. 차량태그(dev) 부착.
    public String sendBufferedLogs() {
        java.util.List<String> snap;
        synchronized (LOG_BUFFER) { snap = new java.util.ArrayList<>(LOG_BUFFER); }
        if (snap.isEmpty()) return "보낼 로그가 없습니다";
        String vt = "";
        try { vt = prefs != null ? prefs.getString(KEY_VIN, "") : ""; } catch (Throwable ig) {}
        if (vt.length() > 6) vt = vt.substring(vt.length() - 6);
        int sent = 0;
        try {
            for (int off = 0; off < snap.size(); off += 200) {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (int i = off; i < Math.min(off + 200, snap.size()); i++) {
                    String[] p = snap.get(i).split("\t", 3);
                    if (p.length < 3) continue;
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("ts", Long.parseLong(p[0])); o.put("device", "dilink");
                    o.put("category", p[1]); o.put("message", p[2]);
                    if (!vt.isEmpty()) o.put("dev", vt);
                    arr.put(o);
                }
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(LOG_URL).openConnection();
                c.setRequestMethod("POST"); c.setRequestProperty("X-Auth", LOG_AUTH); c.setRequestProperty("content-type", "application/json");
                c.setDoOutput(true); c.setConnectTimeout(10000); c.setReadTimeout(15000);
                c.getOutputStream().write(arr.toString().getBytes("UTF-8"));
                int code = c.getResponseCode(); c.disconnect();
                if (code / 100 != 2) return "전송 실패 (HTTP " + code + ", " + sent + "줄까지)";
                sent += arr.length();
            }
            synchronized (LOG_BUFFER) { LOG_BUFFER.clear(); }
            return sent + "줄 전송 완료";
        } catch (Throwable t) { return "전송 실패: " + t.getClass().getSimpleName() + " (" + sent + "줄 전송)"; }
    }

    void postToD1(String category, String message) {
        // ★실패(❌ 포함) 로그는 상태에 기록 → 앱이 폴링해 "무슨 동작이 왜 실패" 사용자 알림. 공유 사용자도.
        //   단 '셀프활성 불가'(a11y 자가치유 배경실패)는 제외 — 사용자 요청 동작이 아니고, 공유 차주(WRITE_SECURE_SETTINGS 못줌)는
        //   영구 실패라 반복 알림 스팸이 됨(2026-08-31 씰차주 실측). 로그로만 남김.
        if (message != null && message.contains("❌") && !message.contains("셀프활성") && relay != null) {
            try {
                relay.putStatus("last_error", message.length() > 90 ? message.substring(0, 90) : message);
                relay.putStatus("last_error_ts", System.currentTimeMillis());
            } catch (Throwable ignored) {}
        }
        // ★v106 타 사용자 로그는 라이브 전송 안 함(개인 D1 오염·데이터 부담 방지). 대신 로컬 링버퍼에 쌓아두고,
        //   차주가 릴레이 앱 '로그 보내기' 버튼 누를 때만 배치 전송(2026-08-31 — 베타 진단, 전송 전까진 데이터 0).
        // ★578 VINREG(부팅당 1줄)만 버퍼 우회 — 비운영자도 즉시 전송. 어드민 대시보드가 "누가 어느 devId/해시로 도는지"를
        //   실시간 집계하려면 재부팅 즉시 D1에 올라야 한다(버퍼는 피드백 제출 때만 flush돼 명단이 늦음). 데이터 부담 무시 가능.
        if (!operatorDevice() && !"VINREG".equals(category) && !"LIFE".equals(category) && !"SYSRESTART".equals(category) && !"DATAUSE".equals(category) && !"NETGAP".equals(category) && !"NETFIX".equals(category)) { bufferLog(category, message); return; }   // ★726 NETGAP(끊김 진단)도 바로   // ★706 SYSRESTART도 버퍼 우회 · ★712/706 DATAUSE 하루 합계 1줄도(운영자 10-03 측정 방법 "나")
        new Thread(() -> {
            java.net.HttpURLConnection c = null;
            boolean ok = false;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(LOG_URL).openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("X-Auth", LOG_AUTH);
                c.setRequestProperty("content-type", "application/json");
                c.setDoOutput(true); c.setConnectTimeout(8000); c.setReadTimeout(8000);
                org.json.JSONObject o = new org.json.JSONObject();
                String _vt = "";
                try { _vt = prefs != null ? prefs.getString(KEY_VIN, "") : ""; } catch (Throwable ig) {}
                if (_vt.length() > 6) _vt = _vt.substring(_vt.length() - 6);   // ★차량태그 — 공유 차주 로그를 차량별로 구분(dev)
                o.put("ts", System.currentTimeMillis()); o.put("device", "dilink");
                o.put("category", category); o.put("message", message);
                if (!_vt.isEmpty()) o.put("dev", _vt);
                c.getOutputStream().write(o.toString().getBytes("UTF-8"));
                ok = (c.getResponseCode() / 100 == 2);
            } catch (Throwable ignored) {} finally { if (c != null) c.disconnect(); }
            if (ok) {
                long down = netDownSince;
                if (down > 0) {   // 방금 복구됨 → 오프라인 구간 보고(다음 전송에 실려 감)
                    netDownSince = 0;
                    int fails = netFailCount; netFailCount = 0;
                    final long secs = (System.currentTimeMillis() - down) / 1000;
                    if (secs > 20) postToD1("NET", "네트워크 복구 — " + secs + "초간 전송불가(프로세스는 생존, 실패 " + fails + "회)");
                }
            } else {
                netFailCount++;
                if (netDownSince == 0) netDownSince = System.currentTimeMillis();
            }
        }).start();
    }

    // ── 차량 제어 (UI 버튼 → SDK 직접 명령) ──
    private final int[] DOOR_LF_RF_LR_RR = {960495668, 960495670, 960495672, 960495674};
    private Object getBodyworkDevice() {
        if (bodyworkDevice == null) {
            if (DiLink5Support.isDiLink5()) DiLink5Support.ensure(this);   // ★D5=bydauto 부트클래스패스 부재 → dex 주입 선행(멱등)
            try { bodyworkDevice = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice")
                    .getMethod("getInstance", Context.class).invoke(null, vcwRef);
                  if (bodyworkDevice != null) injectContext(bodyworkDevice); }
            catch (Throwable t) {   // ★씨라7 InvocationTargetException 원인 규명(2026-09-11) — getInstance 내부 실패 사유(권한명 등)를 남긴다.
                Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
                postToD1("RELAY", "bodywork getInstance 실패: " + c.getClass().getSimpleName() + ":" + c.getMessage());
            }
            // ★씨라7(D5) 폴백 — bydcontroller처럼 소유 시스템앱(com.byd.carsettings/mycar 등)에서 bodywork 로드(권한 벽 우회).
            if (bodyworkDevice == null && DiLink5Support.isDiLink5()) {
                bodyworkDevice = DiLink5Support.deviceFromSystemApp(this, "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice", vcwRef);
                if (bodyworkDevice != null) { injectContext(bodyworkDevice); postToD1("RELAY", "D5 시스템앱 bodywork 획득: " + DiLink5Support.lastSysReport); }
            }
        }
        return bodyworkDevice;
    }

    // 창문 4짝(LF/RF/LR/RR) 제어. 실차 확정: bodywork.setBodyWindowCtrlState(area, state) — public.
    // state: 1=완전열기, 2=완전닫기, 4=반개, 5=breath(환기). area: 1=운전석 2=조수석 3=뒷좌左 4=뒷좌右.
    private String windowCtrl(int state) {
        Object dev = getBodyworkDevice();
        if (dev == null) return "bodyworkDevice null";
        StringBuilder sb = new StringBuilder();
        for (int area = 1; area <= 4; area++)
            sb.append(area).append("=").append(callIntMethod(dev, "setBodyWindowCtrlState", area, state)).append(" ");
        return sb.toString();
    }

    // base set(int devType, int featureId, int value) — 임의 디바이스의 상위 클래스 declared set 사용(도어락 실험용).
    // ★후진 자동 비상등(로컬) — LIGHT_CMD_DOUBLE_FLASH_STATE feature 쓰기. (실측: 전용 setter 없어 가짜OK만, 물리동작 안 함 → 미사용. LOCALFLASH 테스트용만 유지)
    private static final int FEAT_DOUBLE_FLASH = 960495667;   // LIGHT_CMD_DOUBLE_FLASH_STATE
    private String localHazard(int value) {
        return baseSet(deviceByName("android.hardware.bydauto.light.BYDAutoLightDevice"), FEAT_DOUBLE_FLASH, value);
    }

    // ★라이트 getter 전체 스냅샷(no-arg int/long/bool) + setting.getHeadlampLevel — 세터 발사 전후 diff로 실제 물리변화 판정.
    private java.util.LinkedHashMap<String, String> lightGetterSnap(Object lt, Object st) {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        if (lt != null) for (java.lang.reflect.Method mm : lt.getClass().getMethods()) {
            if (mm.getParameterTypes().length != 0) continue;
            String n = mm.getName();
            if (!(n.startsWith("get") || n.startsWith("is"))) continue;
            Class<?> rt = mm.getReturnType();
            if (rt == Integer.TYPE || rt == Long.TYPE || rt == Boolean.TYPE) { try { m.put(n, String.valueOf(mm.invoke(lt))); } catch (Throwable ig) {} }
        }
        if (st != null) { try { m.put("setting.getHeadlampLevel", String.valueOf(st.getClass().getMethod("getHeadlampLevel").invoke(st))); } catch (Throwable ig) {} }
        return m;
    }
    private String lightDiff(java.util.Map<String, String> a, java.util.Map<String, String> b) {
        StringBuilder d = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : b.entrySet()) { String av = a.get(e.getKey()); if (av != null && !av.equals(e.getValue())) d.append(e.getKey()).append(":").append(av).append("→").append(e.getValue()).append(" "); }
        return d.toString();
    }
    // ★비상등·헤드라이트 로컬 세터 후보 순차 발사 + read-back diff. 무변화=로컬쓰기 死(클라우드 전용 확정), ★물리변화=로컬 제어 가능.
    private void lightProbe() {
        Object lt = deviceByName("android.hardware.bydauto.light.BYDAutoLightDevice");
        Object st = deviceByName("setting");
        if (lt == null) { postToD1("LIGHTPROBE", "light device null — 중단"); return; }
        java.util.Map<String, String> before = lightGetterSnap(lt, st);
        postToD1("LIGHTPROBE", "BEFORE flash=" + before.get("getTurnLightFlashState") + " drl=" + before.get("getDayTimeLightState") + " headlamp=" + before.get("setting.getHeadlampLevel") + " (getter " + before.size() + "개)");
        Object[][] cands = {
            {"DRL끄기(기준·실작동확인)", lt, "m:setDayTimeLightState", 2},
            {"비상등 DOUBLE_FLASH(960495667)", lt, "b:960495667", 1},
            {"비상등 EMERGENCY(950009880)", lt, "b:950009880", 1},
            {"하향빔 LOW(950009866)", lt, "b:950009866", 1},
            {"상향빔 HIGH(950009868)", lt, "b:950009868", 1},
            {"헤드램프레벨 setHeadlampLevel(light)", lt, "m:setHeadlampLevel", 1},
            {"헤드램프레벨 setHeadlampLevel(setting)", st, "m:setHeadlampLevel", 1},
        };
        for (Object[] c : cands) {
            String label = (String) c[0]; Object dev = c[1]; String spec = (String) c[2]; int v = (Integer) c[3];
            if (dev == null) { postToD1("LIGHTPROBE", label + " → 디바이스 null"); continue; }
            String ret;
            if (spec.startsWith("b:")) ret = baseSet(dev, Integer.parseInt(spec.substring(2)), v);
            else ret = callIntMethod(dev, spec.substring(2), v);
            try { Thread.sleep(600); } catch (Throwable ig) {}
            java.util.Map<String, String> after = lightGetterSnap(lt, st);
            String diff = lightDiff(before, after);
            postToD1("LIGHTPROBE", label + " =" + v + " ret=" + ret + " → " + (diff.isEmpty() ? "무변화(로컬쓰기 無/가짜OK)" : "★물리변화: " + diff));
            before = after;
        }
        baseSet(lt, 960495667, 0); baseSet(lt, 950009880, 0); baseSet(lt, 950009866, 0); baseSet(lt, 950009868, 0);
        callIntMethod(lt, "setDayTimeLightState", 1);
        postToD1("LIGHTPROBE", "복구 시도 완료(비상등/빔 OFF, DRL ON)");
    }

    // ★후진 즉시 비상등 — BLE 비콘(bit5) 재광고로 폰이 트리거(6042 우회, 실질 일꾼). 클라우드 이중 경로는 뒤늦은 발동 오작동으로 제거(2026-09-14).
    // ★모든 자동 기능 정지(킬 스위치) — 폰이 RAW allStop으로 세팅. 켜지면 릴레이 자율 동작(킥·BSD·후진비상등·전방알림) 전부 스킵.
    private boolean allStopOn() { return prefs != null && prefs.getBoolean("all_stop", false); }
    // ★620 주 사용권 폰 근접 게이트(운영자 09-28 "모든 릴레이 기능이 주사용권 폰이 근접했을 때만" · "BSD 후진비상등 등 모든 기능")
    //   09-23 15:44 실측: 폰(대기/부) 없이 차키로 열고 닫자 차가 스스로 전원 ON → 릴레이 시동 프리셋이 시트·핸들열선·선쉐이드를 움직였다.
    //   폰(주 사용권)이 접근/재실 시 RAW phoneNear=true(주기 갱신), 이탈 시 false. 신선도 15분.
    //   ①주행 래치: 출발 시 근접이었으면 그 주행이 끝날 때까지 허용(주행 중 앱 정지로 BSD가 꺼지는 것 방지)
    //   ②프로토콜 미수신 릴레이(구버전 앱·안드 미적용)는 near_proto=false → 기존 동작(일괄 먹통 방지)
    private volatile boolean phoneNearFlag = false; private volatile long phoneNearTs = 0; private volatile boolean nearDriveLatch = false;
    private static final long PHONE_NEAR_TTL_MS = 15 * 60_000L;
    // ★624 근접 상태·주행 래치 영속 — 09-28 07:35/07:55 주행 중 릴레이 재시작(업데이트 설치)으로 메모리 상태가 날아가, 폰이 다음 근접 신호를
    //   보낼 때까지(약 1~3분) 전방 감지·BSD·후진비상등이 "폰 부재"로 막혔다. 저장해 두고 시작 시 복원(restoreNear). 래치는 drive_end에서만 해제.
    private void persistNear() {
        if (prefs == null) return;
        prefs.edit().putBoolean("near_flag", phoneNearFlag).putLong("near_ts", phoneNearTs).putBoolean("near_latch_saved", nearDriveLatch).apply();
    }
    private void restoreNear() {
        if (prefs == null) return;
        phoneNearFlag = prefs.getBoolean("near_flag", false);
        phoneNearTs = prefs.getLong("near_ts", 0);
        nearDriveLatch = prefs.getBoolean("near_latch_saved", false);
    }
    private boolean phoneNearFresh() { return phoneNearFlag && System.currentTimeMillis() - phoneNearTs < PHONE_NEAR_TTL_MS; }
    /** 릴레이 자율 동작 허용 여부 — 킬스위치 OFF && (근접 프로토콜 미사용 || 주행래치 || 주 사용권 폰 근접). */
    private boolean autoAllowed() {
        if (allStopOn()) return false;
        if (prefs == null || !prefs.getBoolean("near_proto", false)) return true;
        return nearDriveLatch || phoneNearFresh();
    }
    private void checkReverseFast(String src) {
        try {
            int gear = devInt(deviceByName("gearbox"), "getCurrentGear");   // ★552 D5 getGear 폴백 금지 — 인코딩 미검증 값으로 비상등이 오발사된다
            if (gear != 1) return;   // R(1) 아니면 무시
            // ★630 계기판 후방/3캠 즉시 전환 — 예전엔 기어 폴(2초)을 기다려 페이지는 후방 칸인데 영상은 이전 것/연결 중이었다. 표시라 자동기능 게이트와 무관.
            if (!DiLink5Support.isDiLink5()) clusterCam.onReverse(true, System.currentTimeMillis());
            if (!autoAllowed()) {   // ★킬 스위치·★620 폰 부재 — 후진 비상등 안 함
                // ★652 건너뛴 이유를 남긴다(운영자 09-29 "어제 되던 후진 비깜이 오늘 안 된다" — 안드 1.2.9부터 근접 신호가 켜져 이 게이트가 새로 적용됨, 지금까진 무기록).
                long now = System.currentTimeMillis();
                if (now - revHazObsTs >= 12000) {
                    revHazObsTs = now;
                    postToD1("REVHAZ", "후진 감지(" + src + ")했지만 비상등 신호 건너뜀 — " + (allStopOn() ? "모든 자동 기능 정지(킬 스위치)"
                            : "주 사용권 휴대폰 근접 신호 없음(마지막 " + (phoneNearTs > 0 ? ((now - phoneNearTs) / 60000) + "분 전 " + (phoneNearFlag ? "근접" : "멀어짐") : "수신 없음") + ", 주행 래치 " + nearDriveLatch + ")"));
                }
                return;
            }
            // ★비콘 즉시 반영(2026-08-28) — R 진입 순간 bit5를 폰이 watchdog 5초 안 기다리고 바로 보게 즉시 재광고.
            //   폰 BLE 비상등(6042 우회, 실질 일꾼)이 최대 5초 늦던 것 → ~1초(다음 레인징)로 단축. 이미 bit5면 스킵(중복 재광고 방지).
            if (relay != null) relay.putStatus("gear", 1);
            if (bleAdvWanted && (bleAdvMinorSent & 32) == 0) { try { startBeaconAdvertise(); } catch (Throwable ig) {} }
            // ★후진 비상등 = BLE 비콘(bit5) 트리거가 실질 일꾼(6042 우회, 거의 100% 성공). 클라우드(cloudFlash) 이중 경로는
            //   6042(운행 게이트) 거부→재시도→후진 끝난 뒤 뒤늦게 켜지는 오작동 유발 → 제거(2026-09-14 운영자 지시, BLE 유지).
            observeReverseHazard(src);
        } catch (Throwable ignored) {}
    }
    // ★651 후진 비상등 실제 점멸 관측(운영자 09-29 승인, FB1277 아토3: 폰 BLE 명령 result=1인데 한 번도 안 깜빡임) — 동작 무변경.
    //   R 진입부터 10초 동안 200ms마다 light.getTurnLightFlashState(실측 1=꺼짐, 9=비상등)·속도·R 유지를 보고 REVHAZ 1줄.
    //   폰 명령 도착 시각은 폰 로그(비상등 성공 BLE)와 시각으로 맞춘다. 안 깜빡이는 조건(출발 후 도착·방향지시등 등) 확인용.
    private volatile long revHazObsTs = 0;
    private void observeReverseHazard(String src) {
        long now = System.currentTimeMillis();
        if (now - revHazObsTs < 12000) return;   // 폴·이벤트 중복 진입 1회로
        revHazObsTs = now;
        new Thread(() -> {
            Object light = deviceByName("light"), gb = deviceByName("gearbox");
            if (light == null) { postToD1("REVHAZ", "후진 진입(" + src + ") — 조명 장치 없음, 관측 불가"); return; }
            long t0 = System.currentTimeMillis();
            double v0 = lastSpeed; Object turn0 = relay != null ? relay.getStatus("turnflash") : null;
            int f0 = callGetInt0(light, "getTurnLightFlashState"), fNow = f0, fFirst = -1; long firstMs = -1, rLeftMs = -1; double vmax = Double.isNaN(v0) ? 0 : v0;
            while (System.currentTimeMillis() - t0 < 10000) {
                sleepQuiet(200);
                long el = System.currentTimeMillis() - t0;
                fNow = callGetInt0(light, "getTurnLightFlashState");
                if (firstMs < 0 && fNow != f0) { firstMs = el; fFirst = fNow; }
                if (!Double.isNaN(lastSpeed)) vmax = Math.max(vmax, lastSpeed);
                if (rLeftMs < 0 && gb != null && devInt(gb, "getCurrentGear") != 1) rLeftMs = el;
            }
            postToD1("REVHAZ", "후진 진입(" + src + ") 속도=" + (Double.isNaN(v0) ? "?" : String.valueOf((int) Math.round(v0))) + " 방향지시등=" + turn0 + " 점멸상태=" + f0
                    + " → 10초: " + (firstMs >= 0 ? "점멸 변화 +" + firstMs + "ms(" + f0 + "→" + fFirst + ")" : "점멸 변화 없음(" + fNow + ")")
                    + " · 최고속도=" + (int) Math.round(vmax) + " · R " + (rLeftMs >= 0 ? rLeftMs + "ms 만에 벗어남" : "10초 유지"));
        }, "revhaz-obs").start();
    }

    private String baseSet(Object dev, int featureId, int value) {
        if (dev == null) return "디바이스 null";
        try {
            int devType = (Integer) dev.getClass().getMethod("getDevicetype").invoke(dev);
            java.lang.reflect.Method setM = null;
            for (Class<?> c = dev.getClass().getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try { setM = c.getDeclaredMethod("set", int.class, int.class, int.class); setM.setAccessible(true); break; }
                catch (NoSuchMethodException ignored) {}
            }
            if (setM == null) return "base set(int,int,int) 없음";
            Object ret = setM.invoke(dev, devType, featureId, value);
            return "OK(" + ret + ")";
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
            return "실패:" + c.getClass().getSimpleName() + ":" + c.getMessage();
        }
    }

    public String sendVehicleCommand(String action) {
        String a = action == null ? "" : action.toUpperCase();
        StringBuilder log = new StringBuilder();
        String r;
        // ★공유판 게이트 — 임의 셸·진단·self-update·전원실험은 운영자 개발판(operator flavor)만.
        //   공유판 APK엔 IS_OPERATOR=false 상수 뒤로 막혀, 전역 SECRET을 뽑아도 실행할 위험 명령이 없다(RCE 차단).
        //   LOCK/UNLOCK/WINDOW/SUNSHADE 등 사용자 기능과 AFTERBLOW는 공유판도 유지.
        if (!BuildConfig.IS_OPERATOR
                && (a.startsWith("SH ") || a.startsWith("FEAT ") || a.startsWith("DUMPDEV ") || a.startsWith("PROBEINT ") || a.startsWith("READFEAT ") || a.startsWith("CONSTS ")
                    || a.equals("SELFUPDATE") || a.equals("ADB_AUTH") || a.equals("WAKE_MCU")
                    || a.startsWith("DUMP_") || a.startsWith("BLE_ADV")
                    // ★v215 공유판 보안 감사 — 누락돼 있던 운영자 전용 명령 추가 차단(RCE·권한상승·진단·프라이버시)
                    || a.startsWith("INSTALLAPK ") || a.equals("GRANTPERMS")
                    || a.equals("A11YDUMP") || a.equals("A11YCAP")
                    || a.equals("AUTOSTARTFIX") || a.startsWith("AUTOSTARTADD ") || a.equals("SCREENWAKE")
                    || a.equals("CARPROBE") || a.equals("APPOPSPROBE")
                    || a.startsWith("BSDEXP"))) {   // ★602 BSD실험 — 운영자 개발판만. 앱런처/분할(LAUNCHAPP·SPLIT·APPLIST)은 2026-09-11 운영자 지시로 공유판 개방 — 명령은 K서명(requireVerify), 실행은 SplitLauncher 고정 셸(임의 명령 아님)
            return a + ": 미지원(공유판)";
        }
        if (camOffOn() && (a.equals("CAMOPEN") || a.equals("CAMPROBE") || a.equals("D5CAMPROBE"))) return a + ": " + CAM_OFF_MSG;   // ★카메라 기능 끔
        // ★v74 범용 셸 — "SH <명령>" 형태. dadb(uid=2000 shell)로 실행하고 결과를 D1 로그로.
        if (a.startsWith("SH ")) { final String c = action.substring(3); new Thread(() -> shellCmd(c), "sh").start(); return "셸 실행 — 로그 확인"; }
        // ★오토스타트 대상 원격 추가(2026-09-18 운영자, 오토전비) — 대상 CSV(autostart_targets)에 패키지 추가 후 AUTOSTARTFIX. 헤드유닛 UI 안 만지고 원격으로.
        if (a.startsWith("AUTOSTARTADD ")) {
            String pkg = action.substring(13).trim();
            if (prefs != null && !pkg.isEmpty()) {
                java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
                String cur = prefs.getString(KEY_AUTOSTART_TARGETS, DEFAULT_AUTOSTART_TARGETS);
                if (cur != null) for (String p : cur.split(",")) { p = p.trim(); if (!p.isEmpty()) set.add(p); }
                set.add(pkg);
                prefs.edit().putString(KEY_AUTOSTART_TARGETS, String.join(",", set)).apply();
                postToD1("AUTOSTART", "대상 원격추가: " + pkg + " → " + set);
            }
            return requestAutostartFix("원격 대상추가");
        }
        // ★리모컨 앱런처(2026-09-11 운영자 승인, docs/AUTOSPLIT-ANALYSIS.md) — LAUNCHAPP <pkg> · SPLIT <pkg1> <pkg2> [pct] · SPLITRATIO <pct> · APPLIST
        //   주차(P)·비주행에서만(주행 중 헤드유닛 화면 급변 방지). 결과는 D1 SPLIT 로그 + relay_state remote_split.
        if (a.equals("SPLITPROBE")) { new Thread(() -> postToD1("SPLIT", SplitLauncher.probe(this)), "splitprobe").start(); return "SPLITPROBE — 로그(SPLIT) 확인"; }
        if (a.startsWith("LAUNCHAPP ") || a.startsWith("SPLIT ") || a.startsWith("SPLITRATIO ") || a.equals("APPLIST")) return appLauncherCmd(action.trim());
        // ★트렁크 클라우드(2026-09-11) — 웹 세컨드스크린/돌핀용. TRUNK {kind:TRUNK_OPEN|TRUNK_CLOSE} 또는 "TRUNK open|close". 릴레이가 쥔 SETCLOUDSESS 세션으로 OPENTRUNK/CLOSETRUNK.
        if (a.equals("TRUNK") || a.startsWith("TRUNK ")) return trunkCmd(action.trim(), null);   // 문자열형("TRUNK open|close"); JSON형은 웹 /cmd(webRunCommand)서 처리
        // ★v123 원격 self-update — 차 안 갈 필요 없이 최신 릴레이 APK를 내려받아 무음 설치+재시작. autostart-blocker도 우회.
        if (a.equals("SELFUPDATE")) { new Thread(this::selfUpdate, "selfupd").start(); return "self-update 시작 — 로그 확인"; }
        // ★679 폰 앱 "릴레이 업데이트" 버튼(운영자 10-01 "사용자가 직접 날리는 건 괜찮다") — 공유판도 허용(위험명령 아님: 폰의 명령서명 검증 통과 + 기어 P·주행 아님일 때만).
        //   헤드유닛 업데이트 팝업의 "업데이트" 버튼과 같은 Updater.updateNow 경로(체크섬 검증·무음 설치, adb 키 없으면 설치 화면 안내).
        if (a.equals("RELAYUPDATE")) {
            if (lastGearVal != 3 || lastDriving == 1) return "주차(P) 상태에서만 업데이트합니다";
            new Thread(() -> Updater.updateNow(this, (stage, pct, done, ok) -> postToD1("SELFUPD", "폰 요청 업데이트: " + stage + (pct >= 0 ? " " + pct + "%" : "") + (done ? (ok ? " [설치 시작됨]" : " [실패]") : ""))), "relayupd").start();
            return "업데이트 시작 — 몇 분 안에 릴레이가 새 버전으로 다시 시작됩니다";
        }
        // ★v162 임의 APK 원격설치 — "INSTALLAPK <url> [grantpkg]". 앱코드 다운로드(byd-ota 도달)+dadb push+pm install(+선택 WRITE_SECURE_SETTINGS grant). 가디언 앱 설치용.
        if (a.startsWith("INSTALLAPK ")) { final String rest = action.substring(11); new Thread(() -> installApk(rest), "installapk").start(); return "APK 설치 시작 — 로그 확인"; }
        // ★v77 feature 이름 검색 — "FEAT SEAT,OCCUP" 처럼 쉼표로 여러 키워드. 어떤 센서가 노출돼 있나 탐색용.
        if (a.startsWith("FEAT ")) { final String k = action.substring(5); new Thread(() -> featScan(k), "feat").start(); return "feature 검색 — 로그 확인"; }
        // ★v77 디바이스 getter 전체 덤프 — "DUMPDEV android.hardware.bydauto.sensor.BYDAutoSensorDevice"
        // ★594 1인자 int getter 프로브 — "PROBEINT <도메인> <메서드> <인자,인자,...>" (예: PROBEINT instrument getWheelPressure 3,1,4,2).
        //   dumpDev는 무인자 getter만 열거해 바퀴별 getWheelPressure(pos) 같은 1인자 getter는 존재 확인이 안 됐다.
        if (a.startsWith("PROBEINT ")) {
            final String[] pp = action.substring(9).trim().split("\\s+");
            new Thread(() -> {
                Object dev = pp.length >= 3 ? deviceByName(pp[0]) : null;
                if (dev == null) { postToD1("FEAT", "PROBEINT 실패 — 사용법 '<도메인> <메서드> <인자,...>' 또는 디바이스 null: " + java.util.Arrays.toString(pp)); return; }
                StringBuilder sb = new StringBuilder(pp[0] + "." + pp[1] + ": ");
                for (String arg : pp[2].split(",")) {
                    try { sb.append(arg).append('=').append(dev.getClass().getMethod(pp[1], int.class).invoke(dev, Integer.parseInt(arg.trim()))).append(" | "); }
                    catch (Throwable t) { Throwable c = t.getCause() != null ? t.getCause() : t; sb.append(arg).append("=err(").append(c.getClass().getSimpleName()).append(") | "); }
                }
                postToD1("FEAT", sb.toString());
            }, "probeint").start();
            return "1인자 프로브 — 로그 확인";
        }
        if (a.startsWith("DUMPDEV ")) { final String c = action.substring(8); new Thread(() -> dumpDev(c), "dumpdev").start(); return "디바이스 덤프 — 로그 확인"; }
        // ★LOCALFLASH <val> — 로컬 비상등 테스트(값 시맨틱 확인용). 1=켜기 후보, 0=끄기 후보.
        if (a.startsWith("LOCALFLASH")) { int v = 1; try { String s = action.substring(10).trim(); if (!s.isEmpty()) v = Integer.parseInt(s); } catch (Throwable ig) {} final int fv = v; String r0 = localHazard(fv); postToD1("DILINK_RAW", "LOCALFLASH(" + fv + ") 로컬 비상등 → " + r0); return "LOCALFLASH " + fv + ": " + r0; }
        // ★LIGHTPROBE — 비상등·헤드라이트·빔 로컬 세터 후보를 순차 발사하고 라이트 getter 전체 스냅샷 diff로 '실제 물리변화' 판정(반환코드는 가짜OK 센티넬이라 못 믿음). DRL끄기=positive control. 주차 중 운영자 전용(systemDanger).
        if (a.equals("LIGHTPROBE")) { new Thread(this::lightProbe, "lightprobe").start(); return "LIGHTPROBE 시작 — 로그(LIGHTPROBE) 확인"; }
        // ★READFEAT <device> <키워드> — 키워드 매칭 feature id 전부를 그 디바이스에서 읽어 값이 나오는 것만 로그.
        //   리모컨 실시간 상태용(설정온도·선쉐이드 위치 등 getter가 없는 값의 읽기 가능 feature 발굴).
        // ★CONSTS <클래스명> [키워드] — 그 클래스(+내부 클래스, +부모)의 static 상수를 이름=값으로 덤프.
        //   BYD SDK는 세터 인자 시맨틱(1=표준/2=큼 등)을 이런 상수로 들고 있다 → 값 추측 대신 정본을 읽는다.
        // ★BRAKEPROBE — 브레이크 감각(컴포트/스포츠) 쓰기 경로 탐색. 반환값 OK(0)는 BYD 가짜 성공이라 못 믿는다 →
        //   매 시도마다 getBrakeFootSenseState()를 읽어 **실제로 값이 바뀌었는지**로만 판정한다.
        if (a.equals("IGNPROBE")) { new Thread(() -> ignSnapshot("수동"), "ignprobe").start(); return "IGNPROBE — 로그(DILINK_RAW) 확인"; }
        // ★SEATPROBE — 돌핀 시트 스위치 신호(버튼 누름 에뮬) 펄스 프로브(2026-09-11 운영자 입회 테스트용).
        //   형식: SEATPROBE <axis> <value> [pulseMs] [device]   예) SEATPROBE horizontal 1 300 setting
        //   axis: horizontal|back|basin|height|height2|back2 · value: 보통 1/2(방향), 0=정지 · pulseMs ≤500 · device 기본 setting
        //   안전: P기어+정차일 때만, 펄스 후 0을 두 번 보내 '버튼 뗌' 보장. SEATPROBE list = 사용 가능한 축·feature id 출력.
        if (a.startsWith("SEATPROBE")) { final String rest = action.length() > 9 ? action.substring(9).trim() : ""; new Thread(() -> seatProbe(rest), "seatprobe").start(); return "SEATPROBE 시작 — 로그(DILINK_RAW) 확인"; }
        if (a.equals("BRAKEPROBE")) { new Thread(this::brakeProbe, "brakeprobe").start(); return "BRAKEPROBE 시작 — 로그(DILINK_RAW) 확인"; }
        // ★RADARPROBE — 전방 근접(초음파) 거리 탐색(2026-09-11 운영자 "전방 근접 센서로 거리 측정하는 게 있을 것").
        //   앞차 출발을 카메라 bbox 대신 **실측 거리**로 판정할 수 있으면 옆차선 오발화·객체 뒤바뀜 문제가 통째로 사라진다.
        //   30초간 1초 간격으로 읽어 어느 프로브가 전방인지·값이 살아있는지 본다(주차/정차 중 앞차 있을 때 실행).
        if (a.equals("RADARPROBE")) { new Thread(this::radarProbe, "radarprobe").start(); return "RADARPROBE 30초 시작 — 로그(DILINK_RAW) 확인"; }
        // ★FCSRPROBE — BYD 순정 '앞차 출발 알림(FCSR=Front Car Start Reminder)'·신호등 인식이 이 차에 있는지.
        //   SDK 표면(dipilot)에 getSupportFcsr/getFcsrState/setFcsrState·setFrontCarStartState·
        //   setTrafficLightRecognizeState·ADAS_TLA_*_TRAFFIC_LIGHT_STATE가 있다. 있으면 카메라 흉내를 걷어내고
        //   **차가 직접 판정한 신호**를 쓸 수 있다 → 옆차선 오발화·객체 뒤바뀜이 원천적으로 사라진다.
        if (a.equals("FCSRPROBE")) { new Thread(this::fcsrProbe, "fcsrprobe").start(); return "FCSRPROBE 시작 — 로그(DILINK_RAW) 확인"; }
        if (a.startsWith("CONSTS ")) {
            final String rest = action.substring(7).trim();
            new Thread(() -> dumpConsts(rest), "consts").start();
            return "CONSTS 시작 — 로그(DILINK_DUMP) 확인";
        }
        if (a.startsWith("READFEAT ")) {
            final String rest = action.substring(9).trim();
            new Thread(() -> readFeatScan(rest), "readfeat").start();
            return "READFEAT 시작 — 로그(DILINK_RAW) 확인";
        }
        // ★602 BSD실험(RECORD-ONLY) — 운영자 D3 돌핀만. 주행 중 AVM quad 4분할 좌/우 카메라 촬영 후 오프라인 분석용 저장.
        if (a.startsWith("BSDEXP")) {
            String cmd = a.length() > 6 ? a.substring(6).trim() : "";
            if (cmd.isEmpty()) return bsdExp.status();
            else if (cmd.equals("ON")) { if (prefs != null) prefs.edit().putBoolean("bsdexp_enabled", true).apply(); return bsdExp.status(); }
            else if (cmd.equals("OFF")) { if (prefs != null) prefs.edit().putBoolean("bsdexp_enabled", false).apply(); bsdExp.stopNow("cmd"); return bsdExp.status(); }
            else return "BSDEXP [ON|OFF] 또는 상태조회";
        }
        switch (a) {
            case "LOCK":   r = doorCmd(1, log); break;
            case "UNLOCK": r = doorCmd(0, log); break;
            case "WINDOW_OPEN":    r = windowCtrl(1); break;   // 확정: 1=완전 열기
            case "WINDOW_CLOSE":   r = windowCtrl(2); break;   // 확정: 2=완전 닫기
            case "SUNSHADE_OPEN":  r = DiLink5Support.isDiLink5() ? callIntMethod(getBodyworkDevice(), "voiceCtlSunshadePanel", 1) : callIntMethod(getBodyworkDevice(), "setSunshadeState", 100); break;   /* ★833 D3는 % 세터라 1이 아니라 100(전체 열기) */   // ★D5=voiceCtl(1열기)
            case "SUNSHADE_CLOSE": r = DiLink5Support.isDiLink5() ? callIntMethod(getBodyworkDevice(), "voiceCtlSunshadePanel", 2) : callIntMethod(getBodyworkDevice(), "setSunshadeState", 0); break;   // ★D5=voiceCtl(2닫기)
            case "WAKE_MCU":   { Object p = deviceByName("power"); r = (p == null) ? "power null" : callIntMethod(p, "wakeUpMcu"); } break;
            case "DUMP_LIFEPROBE": {   // ★685 셸 프로세스(app_process)에서 BYD SDK 호출 가능성 시험(읽기 전용)
                final Context lc = this;
                new Thread(() -> {
                    String out = AdbAccess.runShell(lc, "CLASSPATH=" + getPackageCodePath() + " timeout 25 app_process /system/bin --nice-name=snif_lifeprobe com.cseini.bydsniffer.LifeProbe 2>&1 | head -40");
                    postToD1("LIFEPROBE", out == null ? "adb 불가" : (out.isEmpty() ? "(출력 없음)" : out.replace('\n', '|')));
                }, "lifeprobe").start();
                r = "셸 프로세스 시험 시작(결과는 LIFEPROBE 로그)";
            } break;
            case "DUMP_SURV": r = runSurvivalDiagAsync(true); break;   // ★684 생존 비교 진단(읽기 전용) — 우리 앱·헬퍼 vs 일렉트로: 시스템이 부팅 뒤 어떻게 취급하는지
            case "DUMP_POWER": r = dumpPowerFields(); break;   // wakeUpMcu의 null Context 찾기 — power device 전 필드(자기+부모, static 포함) 덤프
            case "DUMP_POWER_M": r = dumpPowerMethods(); break;   // wakeUpMcu 시그니처+다른 wake/power 메서드 찾기
            case "DUMP_AC": r = dumpAc(); break;   // ★애프터블로우: AC device getter + AC 관련 feature(AFTER/DRY/EVAPOR 등) 스캔 — 순정 애프터블로우 트리거 찾기
            case "DUMP_LIGHT": r = dumpLight(); break;   // ★애프터블로우 조용화: 라이트(DRL/미등/헤드램프) 제어 feature 스캔
            case "ADB_AUTH":    new Thread(this::dadbTry, "dadb-retry").start(); r = "dadb 인증 재시도 — 헤드유닛 팝업 [항상 허용]"; break;   // ★v56 수동 재시도
            case "DUMP_METHODS": r = dumpDeviceMethods(); break;   // ★v99 도어락 등 제어 메서드·EventValue 구조 열거(도어락 실동작 탐색)
            case "BLE_ADV":     r = startBeaconAdvertise(); break;   // ★v94 헤드유닛 iBeacon advertise(ESP32 대체) 실험
            case "BLE_ADV_OFF": r = stopBeaconAdvertise(); break;
            case "A11YREBIND":  r = rebindA11y(); break;   // ★v216 좀비 a11y 원격 강제 재바인딩 — 중복정리 + 제거→대기→재등록으로 unbind/rebind 유발
            case "A11YDUMP":    r = writeA11yTrigger("dump"); break;   // ★v154 오토스타트 앱 화면 덤프 요청(별도프로세스 a11y가 트리거파일 감지해 실행)
            case "A11YCAP":     r = writeA11yTrigger("cap"); break;   // ★v163 a11y 이벤트 캡처 90초(벨트/재실 팝업 pkg+텍스트 잡기, logcat 대체)
            case "MIRRORFOLD":  r = dispatchA11y("mirrorfold", null); break;   // ★v164 사이드미러 자동폴딩 a11y 수동토글(헬퍼 alive면 헬퍼로)
            case "LOGFLUSH":     r = sendBufferedLogs(); break;   // ★v374 공유차주 릴레이 로그를 D1로 flush — 앱 '릴레이 로그 불러오기'가 원격 트리거(차량태그 dev로 저장). systemDanger 아님(로그 전송, 공유 허용).
            case "AUTOSTARTFIX": r = requestAutostartFix("명령"); break;   // ★v156 오토스타트금지 해제. 대상=사용자 선택 CSV payload. v368: 헬퍼 ack 대기 경로(설치직후 경합 회피).
            case "SCREENWAKE":  r = dispatchA11y("screenwake", null); break;   // ★v179 주차 화면 깨우기(헬퍼 alive면 헬퍼로)
            case "CAMPROBE":    r = camProbe(); break;   // ★v184 카메라 스트리밍 도달성 프로브(권한/AVMCamera/IBYDCameraService — 일렉트로 카피 가능여부 판정)
            case "D5CAMPROBE":  new Thread(this::d5CamProbeAll, "d5camprobe").start(); r = "D5CAMPROBE 시작 — camId 0~3 순차 캡처 후 업로드"; break;   // ★2026-09-16 쿼드 방향/실채널 확인용 — 4개 다 전방과 같은지 진단
            case "CAMOPEN":     r = camOpenTest(); break;   // ★v186 openCamera 게이트 테스트(bydcameramanager registerUser+openCamera 권한통과 여부)
            case "CARPROBE":    r = carProbe(); break;   // ★v172 DiCar CarServiceProvider 도달성 테스트(재실/뒷좌석벨트 ICarPropertyManager 채널 접근 가능성)
            case "GRANTPERMS":  r = grantPerms(); break;   // ★v180 Overdrive 방식 — 앱 self pm grant로 도메인별 BYDAUTO_*_SET 권한 부여 시도(어느 게 grantable인지)
            case "APPOPSPROBE": r = appOpsProbe(); break;   // ★v175 오토스타트금지 직접해제 테스트(bg_datacache.setAppOpsData 리플렉션 — 가디언 a11y 대체 후보)
            default:       r = "'" + action + "' 미배선"; break;
        }
        String out = a + ": " + r + "\n" + log;
        postToD1("DILINK_CMD", out);
        Log.i(TAG, out);
        return out;
    }

    // ★v154 별도프로세스(:relay a11y)에 명령 전달 — getFilesDir()는 전 프로세스 공유. a11y가 onAccessibilityEvent에서 이 파일 감지.
    // ★v253 자기치유 — 업데이트로 versionCode가 바뀌면(설치 시 ROM이 오토스타트 재금지) 자동으로 해제 1회.
    //   새 버전 부팅 직후 a11y가 막 바인드되므로(am start) 15s 대기 후 트리거. 최초 설치(saved<0)는 스킵.
    private void checkVersionAutostartFix() {
        try {
            int cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            int saved = prefs.getInt("last_boot_ver", -1);
            if (cur == saved) return;   // 버전 그대로(재부팅) = 재금지 아님, 스킵
            prefs.edit().putInt("last_boot_ver", cur).apply();
            // ★575 D5(씨라7/DiLink5)는 오토스타트금지 자동해제 대상 아님(ROM이 자동 해제) → 스킵(운영자 지시 2026-09-24).
            if (DiLink5Support.isDiLink5()) {
                postToD1("AUTOSTART", "★버전 " + (saved < 0 ? "신규" : saved) + "→" + cur + " — D5는 오토스타트금지 대상 아님, 스킵");
                return;
            }
            // 설치/업데이트 시 ROM이 오토스타트 재금지하므로 항상 해제(첫설치 saved=-1 포함).
            // ★575 진행률 UI 동반(운영자 지시) — 사용자가 "설정 중"을 보고 대기하게. a11y가 오버레이+완료토스트+30초 타임아웃 관리.
            //   ★"릴레이 완전 실행 확인 후" 발사 — a11y가 살아난 뒤 쏘면 설치 직후 "화면 못 읽음"(helperAliveTs=0) 문제를 피한다.
            postToD1("AUTOSTART", "★버전 " + (saved < 0 ? "신규" : saved) + "→" + cur + " 설치 감지 → 오토스타트 자동해제(진행률 표시, a11y 준비 대기)");
            scheduleAutostartUiWhenReady(0);
        } catch (Throwable t) { postToD1("AUTOSTART", "버전확인 예외: " + t.getClass().getSimpleName()); }
    }
    // ★575 a11y가 살아나면(=릴레이 완전 실행) 진행률 UI 동반 오토스타트 해제를 발사. 최대 ~90초 대기(3초 간격),
    //   그 안에 a11y 안 살면 기존 무UI 경로(헬퍼 폴백)로 안전망 발사. 무한대기 없음.
    private void scheduleAutostartUiWhenReady(final int attempt) {
        if (mainHandler == null) { requestAutostartFix("설치(즉시 폴백)"); return; }
        if (attempt == 0) helperHoldUntil = System.currentTimeMillis() + 120_000L;   // ★788 해제가 끝날 때까지 헬퍼 설치·재설치 보류(최대 대기 90초 + 실행)
        mainHandler.postDelayed(() -> {
            long age = a11yAliveAgeMs(this);
            if (age >= 0 && age < 8000) {   // a11y 신선(살아있음) → UI 동반 발사
                postToD1("AUTOSTART", "a11y 준비됨(" + (age / 1000) + "s) → 진행률 동반 오토스타트 해제 발사");
                helperHoldUntil = System.currentTimeMillis() + 60_000L;
                writeA11yTrigger(this, "autostartfix", autostartTargetsCsv(this) + " ui");
            } else if (attempt >= 6 && helperAlive()) {   // ★788 릴레이 접근성이 약 20초 안에 안 붙으면 살아 있는 헬퍼로(예비) — 헬퍼는 진행률 표시 없이 대상만 받는다
                postToD1("AUTOSTART", "릴레이 접근성 미준비(" + (age < 0 ? "심박 없음" : (age / 1000) + "s") + ") → 헬퍼로 오토스타트 해제 발사(예비)");
                helperHoldUntil = System.currentTimeMillis() + 60_000L;
                sendHelperCmd(this, "autostartfix", autostartTargetsCsv(this));
            } else if (attempt < 30) {   // 3초 × 30 = 90초까지 대기
                scheduleAutostartUiWhenReady(attempt + 1);
            } else {   // ★580 a11y 끝내 안 살아남 → 재시도 대신 사용자에게 직접 하라고 안내(운영자 지시: 실패하면 안내만)
                postToD1("AUTOSTART", "a11y 90초 내 미준비 → 자동 해제 포기, 사용자 수동 안내");
                try { android.widget.Toast.makeText(getApplicationContext(),
                        "오토스타트금지 자동 해제에 실패했습니다. 설정 > 오토스타트금지에서 ‘링크락 릴레이’를 직접 꺼주세요.",
                        android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
            }
        }, 3000);
    }

    // ★v368 오토스타트 해제 디스패치 — 설치 직후엔 helperAliveTs=0(새 프로세스)이라 dispatchA11y가 릴레이 자체 a11y로 폴백했는데, 자체 a11y는
    //   이 화면을 못 읽어(2026-09-04 07:47 실측 "화면 대기 재시도" 5회→실패) 헬퍼와 경합만 하고 실패 → 사용자 수동 탭으로만 해제됐음(재시도 없음).
    //   당시 헬퍼로 우회했었는데, ★862(09-26) 실측: 헬퍼 경로(getLaunchIntentForPackage(BLOCKER_PKG))가 최근 "런치인텐트 null"로
    //   100% 실패 중(원인: QUERY_ALL_PACKAGES가 매니페스트엔 있으나 이 ROM이 install-time 자동부여를 안 함 — targetSdk=25라 정상 AOSP라면
    //   레거시 패키지 가시성 예외로 필요 없어야 하는데 이 ROM은 강제 필터링하는 듯. pm grant는 runtime 권한 아니라 "Unknown permission" 예외로 불가).
    //   반면 575(scheduleAutostartUiWhenReady)가 쓰는 "릴레이 자체 a11y 대기 후 트리거" 경로는 같은 기간 100% 성공(완료 ok=true 다수 확인) —
    //   v368 당시 문제는 헬퍼가 필요해서가 아니라 타이밍(막 재시작된 자체 a11y가 화면을 못 읽음)이었고 575가 그걸 이미 고쳤다.
    //   그래서 헬퍼 우회를 폐기하고 575와 동일한 "자체 a11y 신선할 때까지 대기(최대 90초)→트리거" 경로로 통일한다.
    private String requestAutostartFix(String why) {
        final String csv = autostartTargetsCsv(this);
        if (prefs != null) prefs.edit().putLong("last_autostart_fix_ts", System.currentTimeMillis()).apply();
        helperHoldUntil = System.currentTimeMillis() + 120_000L;   // ★788 해제가 끝날 때까지 헬퍼 설치·재설치 보류
        waitThenFireAutostartFix(csv, why, 0);
        return "릴레이 a11y 준비 대기 후 발사(" + why + ")";
    }
    private void waitThenFireAutostartFix(final String csv, final String why, final int attempt) {
        if (mainHandler == null) { writeA11yTrigger(this, "autostartfix", csv); return; }
        mainHandler.postDelayed(() -> {
            long age = a11yAliveAgeMs(this);
            if (age >= 0 && age < 8000) {
                postToD1("AUTOSTART", "오토스타트 해제(" + why + ") — a11y 준비됨(" + (age / 1000) + "s) → 발사");
                helperHoldUntil = System.currentTimeMillis() + 60_000L;
                writeA11yTrigger(this, "autostartfix", csv);
            } else if (attempt >= 6 && helperAlive()) {   // ★788 릴레이 접근성이 약 20초 안에 안 붙으면 살아 있는 헬퍼로(예비)
                postToD1("AUTOSTART", "오토스타트 해제(" + why + ") — 릴레이 접근성 미준비(" + (age < 0 ? "심박 없음" : (age / 1000) + "s") + ") → 헬퍼로 발사(예비)");
                helperHoldUntil = System.currentTimeMillis() + 60_000L;
                sendHelperCmd(this, "autostartfix", csv);
            } else if (attempt < 30) {   // 3초 × 30 = 90초까지 대기(575와 동일 상한 — 무한대기 없음)
                waitThenFireAutostartFix(csv, why, attempt + 1);
            } else {
                postToD1("AUTOSTART", "오토스타트 해제(" + why + ") — a11y 90초 내 미준비, 포기");
            }
        }, 3000);
    }

    // ★684 생존 비교 진단(운영자 10-01 "릴레이가 죽어 있는 동안에도 일렉트로는 잘 살아서 센트리 푸시·카메라가 됐다") — 같은 차에서 일렉트로는 재부팅 뒤 시작됐고 우리는 안 됐다.
    //   무엇이 다른지(패키지 플래그·stopped·부팅 수신기·프로세스 상태·접근성 등록·절전 예외·am 로그의 시작/종료 사유·롬 오토스타트 설정)를 읽기 전용으로 모아 D1(SURV)에 올린다. 어떤 상태도 바꾸지 않는다.
    private long survDiagMs = 0;
    private String runSurvivalDiagAsync(final boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - survDiagMs < 600_000L) return "스로틀";
        survDiagMs = now;
        final Context c = this;
        new Thread(() -> {
            try {
                String[] pkgs = {getPackageName(), "com.cseini.bydhelper", "br.com.rory.electro"};
                for (String pkg : pkgs) {
                    String base = AdbAccess.runShell(c, "dumpsys package " + pkg + " | grep -iE 'flags=|stopped=|firstInstallTime|lastUpdateTime|installerPackageName|enabled=|persistent' | head -12");
                    postToD1("SURV", "[" + pkg + "] 패키지 — " + (base == null ? "adb 불가" : base.replace('\n', '|')));
                    String boot = AdbAccess.runShell(c, "dumpsys package " + pkg + " | grep -iE 'BOOT_COMPLETED|ACC_ON|accessibility|AccessibilityService' | head -10");
                    postToD1("SURV", "[" + pkg + "] 부팅·접근성 선언 — " + (boot == null ? "adb 불가" : boot.replace('\n', '|')));
                    String ps = AdbAccess.runShell(c, "ps -A -o PID,STAT,ARGS 2>/dev/null | grep " + pkg + " | grep -v grep | head -6");
                    String pr = AdbAccess.runShell(c, "dumpsys activity processes 2>/dev/null | grep -A4 'ProcessRecord.*" + pkg + "' | head -14");
                    postToD1("SURV", "[" + pkg + "] 프로세스 — ps=" + (ps == null ? "-" : ps.replace('\n', '|')) + " / am=" + (pr == null ? "-" : pr.replace('\n', '|')));
                    // ★686 시스템이 이 앱을 얼마나 "살려 두는지" — oom_score_adj(낮을수록 안 죽음)·LRU 목록의 상태 사유(top-activity/fg-service 등)·포그라운드 서비스·보이는 창(오버레이/액티비티)
                    String oom = AdbAccess.runShell(c, "for p in $(pidof " + pkg + "); do echo \"pid=$p oom_adj=$(cat /proc/$p/oom_score_adj 2>/dev/null) nice=$(awk '{print $19}' /proc/$p/stat 2>/dev/null)\"; done");
                    String lru = AdbAccess.runShell(c, "dumpsys activity processes 2>/dev/null | grep -E 'Proc #[0-9]+: .*" + pkg + "' | head -4");
                    String fgs = AdbAccess.runShell(c, "dumpsys activity services " + pkg + " 2>/dev/null | grep -iE 'ServiceRecord|isForeground|foregroundId|startRequested' | head -8");
                    String win = AdbAccess.runShell(c, "dumpsys window windows 2>/dev/null | grep -E 'Window\\{.*" + pkg + "' | head -5");
                    postToD1("SURV", "[" + pkg + "] 생존도 — " + (oom == null ? "-" : oom.replace('\n', '|')) + " / LRU=" + (lru == null ? "-" : lru.replace('\n', '|')) + " / 서비스=" + (fgs == null ? "-" : fgs.replace('\n', '|')) + " / 창=" + (win == null || win.isEmpty() ? "없음" : win.replace('\n', '|')));
                    String ap = AdbAccess.runShell(c, "cmd appops get " + pkg + " 2>/dev/null | grep -iE 'BOOT|AUTO|RUN_ANY|RUN_IN_BACKGROUND|START_FOREGROUND' | head -8");
                    String idle = AdbAccess.runShell(c, "dumpsys deviceidle whitelist 2>/dev/null | grep -i " + pkg + " | head -2");
                    postToD1("SURV", "[" + pkg + "] appops=" + (ap == null ? "-" : ap.replace('\n', '|')) + " / 절전예외=" + (idle == null || idle.isEmpty() ? "없음" : idle.replace('\n', '|')));
                }
                String a11y = AdbAccess.runShell(c, "settings get secure enabled_accessibility_services; settings get secure accessibility_enabled");
                postToD1("SURV", "접근성 등록 목록 — " + (a11y == null ? "adb 불가" : a11y.replace("\n", " / ")));
                String ev = AdbAccess.runShell(c, "logcat -b events -d 2>/dev/null | grep -iE 'am_proc_start|am_kill|am_proc_died|am_crash|am_anr' | grep -iE 'bydsniffer|electro|bydhelper' | tail -30");
                postToD1("SURV", "am 이벤트(시작·종료 사유) — " + (ev == null || ev.isEmpty() ? "(없음/버퍼 지나감)" : ev.replace('\n', '|')));
                String sy = AdbAccess.runShell(c, "logcat -b system -d 2>/dev/null | grep -iE 'bydsniffer|br.com.rory.electro' | tail -30");
                postToD1("SURV", "system 로그 — " + (sy == null || sy.isEmpty() ? "(없음)" : sy.replace('\n', '|')));
                String st = AdbAccess.runShell(c, "settings list global 2>/dev/null | grep -iE 'auto.?start|forbid|boot.?start|app.?start|allow.?start' | head -10; settings list secure 2>/dev/null | grep -iE 'auto.?start|forbid|app.?start' | head -10; settings list system 2>/dev/null | grep -iE 'auto.?start|forbid|app.?start' | head -10");
                postToD1("SURV", "롬 오토스타트 관련 설정값 — " + (st == null || st.isEmpty() ? "(키 없음)" : st.replace('\n', '|')));
                postToD1("SURV", "★생존 비교 진단 끝");
            } catch (Throwable t) { postToD1("SURV", "진단 예외: " + t.getClass().getSimpleName() + ":" + t.getMessage()); }
        }, "surv-diag").start();
        return "생존 비교 진단 시작(결과는 SURV 로그)";
    }

    // ★683(운영자 10-01 "릴레이가 죽었다가 못 살아남") — 186902 돌핀: 08:20 시동 끈 뒤 시스템이 앱을 70분마다 강제 종료했고 감시 스크립트가 매번 되살렸다.
    //   15:10:47 헤드유닛이 재부팅되며 마지막 되살리기(am start)가 "Can't find service: activity"로 실패 → 부팅 뒤 롬의 오토스타트 금지(강제 종료 뒤 재금지)로
    //   앱이 시작되지 못해 81분 동안 무소식, 시동을 건 16:33에야 복귀. 지금까지의 재해제는 버전 변경·6시간 주기뿐이라 "강제 종료 → 재시작 → 재부팅" 사이가 비었다.
    //   → 돌핀·아토3(D3계열, 씨라7은 대상 아님)이 시동 OFF 상태에서 (강제 종료 등으로) 다시 시작되면 20분 스로틀로 재해제를 바로 발사한다. 시동 ON이면 안 함(화면 가로채기 방지).
    private void healAutostartAfterRestart() {
        try {
            if (prefs == null || mainHandler == null || DiLink5Support.isDiLink5() || allStopOn()) return;
            mainHandler.postDelayed(() -> {
                try {
                    long now = System.currentTimeMillis();
                    if (now - prefs.getLong("last_autostart_fix_ts", 0) < 20 * 60_000L) return;   // 직전에 이미 해제함
                    if (powerLevelNow() != 0) return;   // 시동 OFF(0)일 때만 — ON·읽기 실패면 안 건드림
                    prefs.edit().putLong("last_autostart_fix_ts", now).apply();
                    postToD1("AUTOSTART", "★683 시동 OFF 상태로 재시작됨 → 오토스타트 금지 재해제(강제 종료 뒤 롬 재금지 대비, 재부팅 후 자동 시작 보장)");
                    scheduleAutostartUiWhenReady(0);
                } catch (Throwable ignored) {}
            }, 25_000L);   // 시작 직후 a11y·전원 값이 자리잡을 시간
        } catch (Throwable ignored) {}
    }

    // ★오토스타트 재금지 자가치유(2026-09-08) — 버전 변경이 없어도 BYD ROM이 시간 경과·force-stop 후 오토스타트를 다시
    //   금지 → DiLink가 릴레이를 force-stop → 시동 ON(ACC_ON) 브로드캐스트를 못 받아 자동시작 불가(아토3 09-08 05:30 7시간 死 근본원인).
    //   기존 checkVersionAutostartFix는 versionCode 바뀔 때만 해제 → 업뎃 안 하는 차(아토3 v381 정체)는 재해제가 영영 안 걸림.
    //   → 주차 중(gear P·주행 중 설정화면 팝업 방지)·헬퍼 준비 상태에서 6시간마다 재해제해 스스로 복구. lastGearVal은 폴링(getCurrentGear)
    //   갱신이라 gearbox 이벤트 없는 차(아토3)에서도 유효. 헬퍼 미준비면 스킵(다음 60초 앵커에서 재시도, 스로틀은 실발사 때만 갱신).
    // ★자동 업데이트(2026-09-17 운영자 지시) — 릴레이엔 백그라운드 업데이트가 없어 ①앱 열 때 프롬프트 ②원격 SELFUPDATE(운영자판 전용, 공유판은
    //   RCE 게이트로 차단)뿐이었다. 그래서 D5 테스터 4대 중 3대가 v510~514에 고착(09-17 실측). 원격 명령을 여는 대신 릴레이가 스스로
    //   고정 OTA_URL(version.json)을 확인해 새 버전이면 selfUpdate(dadb 무음설치)한다 — 임의 명령 표면은 안 늘어남.
    //   조건: 주차(P) · adb 키 있음(없으면 설치 불가라 확인도 안 함) · 킬스위치 아님 · 6시간 스로틀 · 부팅 후 3분 경과(부팅 직후 churn 회피).
    private long autoUpdateLastMs = 0;
    private final long serviceStartMs = System.currentTimeMillis();   // 서비스 인스턴스 생성 시각(부팅 직후 3분 회피용)
    // ★571 자동 업데이트 관문 진단 — 6시간에 1회, "어느 관문에서 멈췄는지"를 남긴다.
    //   실측(09-23, 최근 7일): 자동 업데이트 성공은 **운영자 차 1대뿐**이고 나머지 성공은 전부 `OK(버튼)`=수동이었다.
    //   그런데 551에 넣은 '건너뜀 사유' 로그는 **아래 조용한 관문들보다 뒤에** 있어, 앞에서 막히면 기록이 0건이라
    //   어디가 문제인지 구분이 불가능했다(테스터 100여 대 중 SELFUPD 로그를 남긴 차가 9대뿐인 이유).
    //   고치기 전에 **어느 관문인지부터 실측**한다 — 조건을 좁게 걸어 0건을 "문제 없음"으로 오독한 전례(D5 기어)를 피한다.
    private long autoUpdDiagMs = 0;
    private void autoUpdDiag(String why) {
        long now = System.currentTimeMillis();
        if (now - autoUpdDiagMs < 21_600_000L) return;
        autoUpdDiagMs = now;
        postToD1("SELFUPD", "자동 업데이트 대기 — " + why);
    }
    // ★★574 시동 ON 업데이트 안내(운영자 지시 2026-09-23) — 자동 업데이트가 조용히 실패하면 사용자는 영영 모른다.
    //   실측: 활성 릴레이가 546(11대)·509~558(산발)에 정체돼 있었고, 성공 사례는 대부분 사용자가 앱에서 직접
    //   "업데이트 확인"을 누른 경우였다. 그래서 **시동을 켤 때 화면에 한 번 띄워** 눈에 보이게 한다.
    //   ★안전: **기어 P + 시동 ON**에서만 띄우고, 주행이 시작되면 Activity가 onStop에서 스스로 닫는다.
    //   ★하루 1회(운영자 지정) — 매 시동마다 뜨면 불쾌하고, 지금은 "아예 안 보이는" 상태라 1회만으로도 큰 개선이다.
    //   ★자동 업데이트(maybeAutoUpdate)와 독립 — 그쪽은 기동 3분 게이트·6h 스로틀이 있어 짧게 깨는 차에선
    //   영영 못 넘을 수 있다. 안내는 그 게이트들을 타지 않는다(무거운 다운로드가 아니라 화면 한 장이므로).
    private void maybeUpdatePrompt() {
        try {
            if (prefs == null || allStopOn()) return;
            if (powerLevelNow() <= 0) return;              // 시동 OFF(0)·읽기 실패(-1)면 안 띄움 — 모르면 안 건드리는 쪽
            // ★주차 판정은 기어 P로만 한다. parkedForUpdate()를 쓰면 안 된다 — 그쪽은 D5에서 "시동 OFF"를
            //   주차로 보므로(getGear 인코딩 미검증 시절의 안전책) 시동 ON 조건과 정면으로 모순돼 씨라7에서 영영 안 뜬다.
            //   568에서 D5 getGear→표준 인코딩 정규화가 들어가 lastGearVal은 이제 D3/D5 공통으로 3=P다.
            if (lastGearVal != 3) return;                  // 주행 중 절대 금지
            long now = System.currentTimeMillis();
            // ★하루 1회는 prefs에 남긴다 — 메모리 필드면 헤드유닛이 재부팅할 때마다(주행마다) 초기화돼 "하루 1회"가 깨진다.
            if (now - prefs.getLong("upd_prompt_ms", 0) < 86_400_000L) return;
            // ★체크 자체도 10분 스로틀 — 새 버전이 없을 땐 표시 시각이 안 남으므로, 없으면 매 틱(60초)마다 version.json을
            //   받으러 간다. 시동 켠 채 오래 주차하는 차(충전·공조대기)에서 불필요한 요청이 쌓인다.
            if (now - prefs.getLong("upd_prompt_chk_ms", 0) < 600_000L) return;
            Updater.checkAvailable(this, (available, latest) -> {
                // ★스로틀은 **응답을 실제로 받은 뒤**에만 기록한다. 호출 전에 찍으면, 시동 직후 아직 LTE가 안 붙어
                //   실패했을 때 10분간 재시도가 막혀 주차 창(1~2분)이 통째로 날아간다(엣지+틱 이중 트리거가 무의미해짐).
                //   checkAvailable은 예외 시 latest=0, 성공 시 최신 버전(>0)을 준다 — 이걸로 도달 여부를 가린다.
                if (latest > 0) prefs.edit().putLong("upd_prompt_chk_ms", System.currentTimeMillis()).apply();
                if (!available) return;
                prefs.edit().putLong("upd_prompt_ms", System.currentTimeMillis()).apply();
                try {
                    int cur = BuildConfig.VERSION_CODE;
                    android.content.Intent i = new android.content.Intent(this, UpdatePromptActivity.class);
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    i.putExtra(UpdatePromptActivity.EXTRA_CUR, cur);
                    i.putExtra(UpdatePromptActivity.EXTRA_NEW, latest);
                    startActivity(i);
                    postToD1("SELFUPD", "시동 ON 업데이트 안내 표시 v" + cur + " → v" + latest);
                } catch (Throwable t) {
                    postToD1("SELFUPD", "업데이트 안내 표시 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage());
                }
            });
        } catch (Throwable ignored) {}
    }

    // ★580 무음 자동설치 폐지(운영자 지시 2026-09-24) — 셀프업데이트가 운영자 차만이 아니라 adb 키 있는 테스터 차 전체에
    //   6시간마다 조용히 나갔고, parkedForUpdate가 기어 오독으로 주행중을 주차로 오판해 **주행 중 무음 설치→재시작→오토스타트 팝업**까지
    //   났다(도규아빠 실사고). 업데이트는 오직 maybeUpdatePrompt(시동 ON 안내 → 사용자가 버튼)로만. 여기선 adb_key 상태만 보고한다.
    private void maybeAutoUpdate() {
        try {
            if (prefs == null) return;
            java.io.File pk = new java.io.File(getFilesDir(), "adbkey"), pub = new java.io.File(getFilesDir(), "adbkey.pub");
            if (relay != null) relay.putStatus("adb_key", (pk.exists() && pub.exists()) ? 1 : 0);
        } catch (Throwable ignored) {}
    }
    // ★551 업데이트용 주차 판정 — D3는 기어 P(3)로 본다. 씨라7(D5)은 기어 이벤트가 안 와 lastGearVal이 P가 되는 일이
    //   없어(09-22 실측: 활성 D5 21대 전원 gear=0) 이 게이트만으로 자동 업데이트가 영구 차단됐다. D5는 시동 전원으로
    //   대신 판정한다 — bodywork.getPowerLevel()은 D3/D5 공통으로 읽히고 0=시동 OFF라 주행 중일 수 없다(4777행과 동일 근거).
    //   ★552 정정 — D5는 기어를 아예 보지 않는다. getGear 인코딩이 실차 미검증이라 주행 중 값이 P(3)로 읽히면
    //   주행 중 설치가 나갈 수 있다(551이 그 구멍을 열었다). 검증 전까지 D5의 유일한 근거는 시동 OFF다.
    private boolean parkedForUpdate() {
        if (DiLink5Support.isDiLink5()) return powerLevelNow() == 0;
        return lastGearVal == 3;
    }
    // ★829 FB2157(새샘 아토3, 10-07) — 시동 OFF 뒤 헤드유닛은 깨어 있는데(오버드라이브 USB 유지) 차 LTE 데이터만 끊겨 3시간 서버 무소식, 오버드라이브도 같이 끊김.
    //   안드로이드는 LTE에 붙은 줄 알고 있었다(LIFE "그때 망 LTE"). 시동 OFF에서 서버 전송 실패가 2분 넘으면 망 상태를 남기고 모바일 데이터를 껐다 켜고,
    //   3분 뒤에도 안 되면 비행기 모드를 잠깐, 그 뒤엔 10분마다 데이터 껐다 켜기. 망이 없어 바로 못 올리니 파일(netfix.log)에 쌓았다가 다시 붙으면 NETFIX로 올린다.
    private int netFixStage = 0;
    private long netFixAt = 0, netFixOutageMs = 0;
    private volatile String netFixLast = "";
    private void netFixTick() {
        long now = System.currentTimeMillis();
        long since = RelayClient.netFailSinceMs();
        long okAgo = RelayClient.lastOkAgoMs();
        if (since == 0 && okAgo >= 0 && okAgo < 60_000L) {
            if (netFixStage > 0) {
                netFixFile("다시 연결됨 — 끊김 " + ((now - netFixOutageMs) / 60000) + "분 · 마지막 조치 " + netFixLast + " " + ((now - netFixAt) / 60000) + "분 전 · " + netSnapshot());
                netFixStage = 0; netFixLast = "";
            }
            netFixFlush();
            return;
        }
        if (since == 0 || now - since < 120_000L) return;
        int pw = powerLevelNow();
        boolean ignOn = pw >= 0 ? pw == 2 : (relay != null && relay.ignitionState() == 1);
        if (ignOn) return;   // 시동 켜진 동안(주행 중 터널 등 잠깐 음영)엔 데이터를 끊지 않는다
        if (netFixStage > 0 && now - netFixAt < (netFixStage == 1 ? 180_000L : 600_000L)) return;
        if (netFixStage == 0) netFixOutageMs = since;
        netFixAt = now;
        final int stage = ++netFixStage;
        final String why = RelayClient.netFailReason();
        new Thread(() -> {
            String snap = netSnapshot();
            boolean inet = false;   // 우리 서버만 안 될 때(서버 장애) 모든 차가 데이터를 끊지 않게 — 바깥 주소가 되면 조치하지 않는다
            try (java.net.Socket so = new java.net.Socket()) { so.connect(new java.net.InetSocketAddress("1.1.1.1", 443), 3000); inet = true; } catch (Throwable ignored) {}
            if (inet) {
                netFixLast = "조치 안 함(인터넷은 됨)";
                netFixFile("서버 못 닿음 " + ((now - since) / 60000) + "분(" + why + ") · 1.1.1.1 연결은 됨 → 차 인터넷은 살아 있어 조치 안 함 · " + snap);
                return;
            }
            String act, r;
            if (stage == 2) {
                act = "비행기 모드 켰다 끄기";
                r = AdbAccess.runShell(this, "cmd connectivity airplane-mode enable 2>&1; sleep 3; cmd connectivity airplane-mode disable 2>&1");
                if (r == null || !r.isEmpty()) {   // 안드로이드 10은 cmd connectivity airplane-mode가 없다 → 설정+방송으로 한 번 더
                    String r2 = AdbAccess.runShell(this, "settings put global airplane_mode_on 1; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true 2>&1; sleep 3; settings put global airplane_mode_on 0; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false 2>&1");
                    r = (r == null ? "셸 연결 안 됨" : r) + " | 대체: " + (r2 == null ? "셸 연결 안 됨" : r2);
                }
            } else {
                act = "모바일 데이터 껐다 켜기";
                r = AdbAccess.runShell(this, "svc data disable 2>&1; sleep 2; svc data enable 2>&1");
            }
            netFixLast = act;
            netFixFile("서버 못 닿음 " + ((now - since) / 60000) + "분(" + why + ") · 시동 OFF · 조치 " + stage + "단계 " + act + " → "
                    + (r == null ? "셸 연결 안 됨" : r.isEmpty() ? "OK" : r.replace('\n', ' ')) + " · 조치 전 " + snap);
        }, "netfix").start();
    }
    String netSnapshot() {
        StringBuilder sb = new StringBuilder();
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            sb.append("망=").append(c == null ? "없음" : (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ? "LTE"
                    : c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "와이파이" : "기타")
                    + (c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "(검증됨)" : "(검증 안 됨)"));
        } catch (Throwable t) { sb.append("망=?").append(t.getClass().getSimpleName()); }
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            int ds = tm.getDataState();
            sb.append(" 데이터=").append(ds == 2 ? "연결" : ds == 1 ? "연결 중" : ds == 3 ? "보류" : ds == 0 ? "끊김" : "?" + ds);
            android.telephony.SignalStrength ss = tm.getSignalStrength();
            if (ss != null) sb.append(" 신호=").append(ss.getLevel()).append("/4");
        } catch (Throwable t) { sb.append(" 데이터=?").append(t.getClass().getSimpleName()); }
        try {
            sb.append(" 비행기=").append(android.provider.Settings.Global.getInt(getContentResolver(), android.provider.Settings.Global.AIRPLANE_MODE_ON, 0));
            sb.append(" 모바일데이터=").append(android.provider.Settings.Global.getInt(getContentResolver(), "mobile_data", -1));
        } catch (Throwable ignored) {}
        return sb.toString();
    }
    private synchronized void netFixFile(String m) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "netfix.log");
            if (f.length() > 32_000L) return;
            String t = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.KOREA).format(new java.util.Date());
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) { w.write(t + " " + m + "\n"); }
        } catch (Throwable ignored) {}
    }
    private synchronized void netFixFlush() {
        java.io.File f = new java.io.File(getFilesDir(), "netfix.log");
        if (!f.exists() || f.length() == 0) return;
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { byte[] b = new byte[8192]; int k; while ((k = in.read(b)) > 0) bo.write(b, 0, k); }
            f.delete();
            for (String line : bo.toString("UTF-8").split("\n")) if (!line.trim().isEmpty()) postToD1("NETFIX", line);
        } catch (Throwable t) { postToD1("NETFIX", "기록 올리기 실패 " + t.getClass().getSimpleName()); }
    }

    private int powerLevelNow() {
        try { double v = devDbl(deviceByName("bodywork"), "getPowerLevel"); return Double.isNaN(v) ? -1 : (int) v; }
        catch (Throwable t) { return -1; }
    }

    // ★548 하향등/상향등 상태를 relay_state로 — BYDAutoLightDevice의 static 필드를 리플렉션으로 읽는다.
    //   dumpConsts와 같은 탐색 방식(상속 체인 + setAccessible)을 쓰되, 필드 2개만 보고 값이 바뀔 때만 올린다(폴 루프에서 매초 호출되므로 putState 스팸 방지).
    private int lastLowBeam = -2, lastHighBeam = -2;
    private void publishLightBeams() {
        if (relay == null) return;
        Integer lo = lightBoolField("mLowBeamLightIsOn"), hi = lightBoolField("mHighBeamLightIsOn");
        if (lo != null && lo != lastLowBeam) { lastLowBeam = lo; relay.putState("low_beam", lo); }
        if (hi != null && hi != lastHighBeam) { lastHighBeam = hi; relay.putState("high_beam", hi); }
    }
    private Class<?> lightCls;
    private Integer lightBoolField(String name) {
        try {
            if (lightCls == null) lightCls = Class.forName("android.hardware.bydauto.light.BYDAutoLightDevice");
            for (Class<?> c = lightCls; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(null);   // CONSTS가 static으로 읽어낸 필드(비-static이면 null 컨텍스트에서 예외 → null 반환)
                    if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
                    if (v instanceof Number) return ((Number) v).intValue();
                    return null;
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ★547 카메라 가용성 실측 — 워커 /camrtc 페이지가 "쿼드·카메라1~4·대시캠"을 고정 목록으로 항상 내보내서,
    //   대시캠이 없는 차에서도 항목이 뜨고 누르면 실패했다(운영자 "대시캠 있으면 연결, 없으면 빼고").
    //   d5CamProbeAll(D5CAMPROBE)이 쓰는 검증 방식(프레임 수신 + 평균 휘도로 검정화면 판별)을 그대로 축약해
    //   **주차 중 1회** 돌리고 결과를 relay_state `cams`에 올린다. 워커가 그 목록으로 VIEWS를 걸러내면
    //   iOS·안드로이드 앱은 코드 수정 없이 자동 반영된다(둘 다 window.camList()가 주는 대로 그림).
    //   ★카메라 세션을 잠깐 점유하므로 주차(P)에서만·24시간에 한 번만. 주행/감지 중이면 건너뛴다.
    private long camProbeLastMs = 0;
    private String lastProbeSkip = "";
    private void maybeProbeCameras() {
        try {
            // ★549 스킵 사유 로깅 — 548에서 코드는 배포됐는데(APK 확인) CAMAVAIL 로그가 한 번도 안 남아
            //   "어느 조건에서 막혔는지"를 밖에서 알 수 없었다. 사유가 바뀔 때만 1줄 남겨 폭주 없이 관측한다.
            String skip = null;
            long now = System.currentTimeMillis();
            if (prefs == null) skip = "prefs 없음";
            else if (allStopOn()) skip = "킬 스위치 ON";
            else if (camOffOn()) skip = "카메라 기능 꺼짐";
            else if (lastGearVal != 3) skip = "주차 아님(gear=" + lastGearVal + ")";
            else if (now - serviceStartMs < 180_000) skip = "부팅 3분 이내";
            else if (camStreaming || h264Streaming || rtcActive || detectActive || clusterCam.active)   // ★729 보조 카메라 상시 대기 중(시동 켜진 주차)도 사용 중
                skip = "카메라 사용 중(stream=" + camStreaming + " h264=" + h264Streaming + " rtc=" + rtcActive + " detect=" + detectActive + " assist=" + clusterCam.active + ")";
            else if (now - camProbeLastMs < 86_400_000L && camProbeLastMs > 0) skip = "24h 스로틀";
            if (skip != null) {
                // ★재시작 뒤 카메라 목록 유지(운영자 10-09 "대시캠 선 뺐는데 대시캠 버튼이 계속") — 검사를 건너뛰는 동안 상태에 목록이 없으면 마지막 검사 결과를 다시 올린다.
                //   없으면 서버가 구버전 호환으로 전부(대시캠 포함) 보여 준다. 마지막 결과는 prefs에 저장(아래).
                if (relay != null && prefs != null && relay.getStatus("cams") == null) {
                    String lastCams = prefs.getString("cams_last", "");
                    if (!lastCams.isEmpty()) { relay.putState("cams", lastCams); relay.putState("cams_ts", prefs.getLong("cams_last_ts", 0)); }
                }
                if (!skip.equals(lastProbeSkip)) { lastProbeSkip = skip; postToD1("CAMAVAIL", "가용성 검사 건너뜀 — " + skip); }
                return;
            }
            lastProbeSkip = "";
            camProbeLastMs = now;
            postToD1("CAMAVAIL", "가용성 검사 시작(주차 중)");
            new Thread(this::probeCamerasOnce, "camprobe").start();
        } catch (Throwable t) { postToD1("CAMAVAIL", "가용성 검사 예외: " + t.getClass().getSimpleName()); }
    }

    private void probeCamerasOnce() {
        java.util.List<String> avail = new java.util.ArrayList<>();
        StringBuilder detail = new StringBuilder();
        boolean d5 = DiLink5Support.isDiLink5();
        if (!rtcInactive()) { postToD1("CAMAVAIL", "중단 — 카메라가 사용 중"); return; }
        camProbing = true;   // ★655 검증 지적 — 점검 중엔 감지·실시간 보기가 카메라를 잡지 않게(아래 finally에서 해제)
        try {
        if (d5) {
            // ★653 씨라7 = 캡처 데몬 1회 기동으로 4채널 확인(운영자 09-29 승인). 예전엔 카메라마다 4채널 데몬을 띄웠다 강제 종료(검사 1회에 4번)해,
            //   주차 중 반복 종료가 뒤쪽 채널 유령(열기 실패)을 남긴다는 의심이 있었다(FB1332 분석). 끝나면 데몬 종료를 확인한다.
            final StringBuilder camLog = new StringBuilder();
            com.overdrive.app.camera.dilink5.FastCamQuadBackend q = new com.overdrive.app.camera.dilink5.FastCamQuadBackend();
            boolean ok = false; int[] lum = null;
            try {
                ok = q.start(getApplicationContext(), 10, (nv21, w, h) -> {}, (tag, m) -> { if (camLog.length() < 300) camLog.append(m).append("|"); });
                for (int i = 0; i < 16 && ok; i++) {   // 최대 8초 — 4칸이 다 오면 바로 끝
                    sleepQuiet(500);
                    lum = q.tileLuma();
                    if (lum[0] >= 0 && lum[1] >= 0 && lum[2] >= 0 && lum[3] >= 0) break;
                }
            } catch (Throwable t) { detail.append("쿼드 검사 예외:").append(t.getClass().getSimpleName()).append(' '); }
            finally {
                try { q.stop(); } catch (Throwable ignore) {}
                new com.overdrive.app.camera.dilink5.FastCamTcpBackend(0).shutdownDaemon(getApplicationContext(), (tag, m) -> postToD1("CAMAVAIL", m));
            }
            for (int id = 0; id <= 3; id++) {
                int v = lum == null ? -1 : lum[id];
                if (v < 0) { detail.append("cam").append(id).append(":프레임없음(start=").append(ok).append(id == 0 ? " 사유=" + (camLog.length() > 0 ? camLog : "(로그없음)") : "").append(") "); continue; }
                if (v < 8) { detail.append("cam").append(id).append(":검정(avg=").append(v).append(") "); continue; }
                avail.add(id == 0 ? "dash" : ("a" + id));   // ★cam0 = 워커 VIEWS의 'dash'(대시캠), cam1~3 = a1~a3
                detail.append("cam").append(id).append(":OK(avg=").append(v).append(") ");
            }
        } else {
            // ★549+ DiLink3(돌핀·아토3·씰) 경로 — 대시캠은 **따로 설치한 차만** 있고(운영자: "원래는 대시캠이 없는데 나처럼 따로 설치한 차가 있어"),
            //   차량이 구성을 안 알려준다(cam_sort='rear:0;pano_h:1'에 대시캠 항목 없음). 그래서 **실제로 열어 프레임이 오는지**로만 판별한다.
            //   운영자 차 CAMSHOT 실측: `open(0)` → 1920x1080 프레임 수신, 업로드 사진이 **전방 대시캠 화면**으로 확인됨(지하주차장 전방).
            //   → D3에서 NormalCamera.open(0)에 프레임이 오면 대시캠 있음. AVM(pano)은 별도 슬롯이라 여기서 다루지 않는다(D3 개별 AVM 채널은 항상 존재).
            Boolean dashOk = d3DashFramePresent();
            if (dashOk != null && dashOk) { avail.add("dash"); detail.append("dash:OK(D3 NormalCamera) "); }
            else detail.append("dash:").append(dashOk == null ? "확인불가" : "프레임없음").append("(D3) ");
        }
        if (!d5) {
            // ★D3는 AVM(파노라마)이 항상 있다(cam_sort 'pano_h:1', 기존 쿼드·개별뷰 기능이 이미 그걸 쓴다).
            //   여기서 AVM을 빼면 워커 필터가 **카메라를 통째로 숨겨버리므로** 반드시 먼저 채운다. dash만 실측 결과에 따라 붙인다.
            boolean hasDash = avail.contains("dash");
            avail = new java.util.ArrayList<>(java.util.Arrays.asList("a5", "a1", "a2", "a3", "a4"));
            if (hasDash) avail.add("dash");
        } else if (!avail.isEmpty()) {
            // ★2026-09-25 쿼드(a5)는 cam0~3 4채널 합성이라 하나라도 빠지면 프레임이 영원히 0장이다(CAMRTC 실측: "쿼드 합성 0장(fed=0)").
            //   기존엔 a1 하나만 있어도 쿼드를 내줘서, cam2·cam3가 죽은 차(QCarCam 채널 미개통/물리부재)는 "쿼드" 버튼을 눌러도
            //   평생 화면이 안 떠 "카메라가 안 된다" 신고로 이어졌다(부산ll메밀소바llS7, 09-25). 4채널 전부 확인돼야만 제공.
            if (avail.contains("dash") && avail.contains("a1") && avail.contains("a2") && avail.contains("a3")) avail.add(0, "a5");
        }
        String cams = String.join(",", avail);
        if (relay != null) { relay.putState("cams", cams); relay.putState("cams_ts", System.currentTimeMillis()); }
        if (prefs != null) prefs.edit().putString("cams_last", cams).putLong("cams_last_ts", System.currentTimeMillis()).apply();   // ★재시작 뒤 다시 올리기용
        postToD1("CAMAVAIL", "가용 카메라=[" + cams + "] · " + detail);
        } finally { camProbing = false; }
    }
    private volatile boolean camProbing = false;   // ★655 가용성 검사 진행 중(카메라 점유)
    private boolean rtcInactive() { return !camStreaming && !h264Streaming && !rtcActive && !detectActive; }

    /** ★D3 대시캠 유무 — NormalCamera.open(0)에 실제 프레임이 오는지. true=있음 / false=없음 / null=확인불가(로더·클래스 실패).
     *  camShot과 같은 경로(bmmcamera.jar → NormalCamera → IPreviewCallback)를 쓰되 **JPEG 변환·업로드는 하지 않는다**(판별만, 주차 중 4초). */
    private Boolean d3DashFramePresent() {
        Object cam = null; Class<?> ncCls;
        final boolean[] got = new boolean[1];
        try {
            if (bmmLoader == null) {
                java.io.File jar = new java.io.File("/system/framework/bmmcamera.jar");
                if (!jar.canRead()) return null;
                bmmLoader = new dalvik.system.DexClassLoader(jar.getPath(), getCodeCacheDir().getPath(), "/system/lib64:/system/lib", getClassLoader());
            }
            try { Class.forName("android.hardware.JNILoader", true, bmmLoader).getMethod("load").invoke(null); } catch (Throwable ignored) {}
            ncCls = Class.forName("android.hardware.NormalCamera", true, bmmLoader);
            Class<?> ipcCls = Class.forName("android.hardware.NormalCamera$IPreviewCallback", true, bmmLoader);
            cam = ncCls.getMethod("open", int.class).invoke(null, 0);
            if (cam == null) return false;
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(bmmLoader, new Class[]{ipcCls}, (pr, meth, args) -> {
                if ("onPreview".equals(meth.getName()) && args != null && args.length >= 6) got[0] = true;
                Class<?> rt = meth.getReturnType();
                return rt == boolean.class ? Boolean.FALSE : (rt == int.class ? Integer.valueOf(0) : null);
            });
            ncCls.getMethod("setPreviewCallback", ipcCls).invoke(cam, proxy);
            ncCls.getMethod("enablePreviewCallback").invoke(cam);
            ncCls.getMethod("startPreview").invoke(cam);
            for (int w = 0; w < 40 && !got[0]; w++) Thread.sleep(100);   // 최대 4초(camShot과 동일)
            return got[0];
        } catch (Throwable t) {
            postToD1("CAMAVAIL", "D3 대시캠 확인 예외: " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (cam != null) {
                try { Class<?> c = cam.getClass(); c.getMethod("stopPreview").invoke(cam); c.getMethod("close").invoke(cam); } catch (Throwable ignored) {}
            }
        }
    }

    private void maybeReleaseAutostartPeriodic() {
        try {
            if (prefs == null || lastGearVal != 3 || !helperAlive()) return;   // 주차(P=3) + 헬퍼 준비됐을 때만
            long last = prefs.getLong("last_autostart_fix_ts", 0);
            if (System.currentTimeMillis() - last < 21_600_000L) return;   // 6시간 스로틀
            postToD1("AUTOSTART", "주기 재해제(주차중·6h) — ROM 재금지 자가치유");
            requestAutostartFix("주기 재해제");
        } catch (Throwable ignored) {}
    }

    private String writeA11yTrigger(String cmd) { return writeA11yTrigger(this, cmd, null); }
    private String writeA11yTrigger(String cmd, String extra) { return writeA11yTrigger(this, cmd, extra); }

    // ★별도프로세스(:relay a11y)에 명령 전달 — getFilesDir()는 전 프로세스 공유. MainActivity 버튼에서도 재사용(static).
    static String writeA11yTrigger(android.content.Context ctx, String cmd, String extra) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "a11y_trigger");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            // 형식 "cmd <ts> [extra]" — a11y가 p[1]=ts, p[2]=extra(미러 원하는 상태 등)로 파싱.
            fos.write((cmd + " " + System.currentTimeMillis() + (extra != null ? " " + extra : "")).getBytes("UTF-8"));
            fos.close();
            return "a11y 트리거 기록(" + cmd + ")";
        } catch (Throwable t) { return "a11y 트리거 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage(); }
    }

    // ★a11y 생존 나이(ms) — cmdPoll이 1.5s마다 a11y_alive에 현재 ts 기록. 신선하면 살아있음. -1=파일없음/오류.
    static long a11yAliveAgeMs(android.content.Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "a11y_alive");
            if (!f.exists()) return -1;
            byte[] b = new byte[64];
            java.io.FileInputStream in = new java.io.FileInputStream(f); int n = in.read(b); in.close();
            if (n <= 0) return -1;
            long ts = Long.parseLong(new String(b, 0, n, "UTF-8").trim());
            return System.currentTimeMillis() - ts;
        } catch (Throwable t) { return -1; }
    }

    // ★v180 Overdrive 방식 self pm grant — 앱 내부 Runtime.exec로 도메인별 BYDAUTO 권한 부여 시도. 어느 게 grantable(development)인지 판별.
    //   매니페스트에 선언돼 있어야 grant됨. exit=0 성공 / non-0 실패(signature=not changeable).
    private String grantPerms() {
        String self = getPackageName();
        String[] perms = {
            "BYDAUTO_LIGHT_SET", "BYDAUTO_REAR_VIEW_MIRROR_SET", "BYDAUTO_ADAS_SET", "BYDAUTO_WIPER_SET",
            "BYDAUTO_BODYWORK_SET", "BYDAUTO_SEAT_SET", "BYDAUTO_INSTRUMENT_SET", "BYDAUTO_AC_SET",
            "BYDAUTO_CHARGING_SET", "BYDAUTO_SETTING_SET", "BYDAUTO_DOOR_LOCK_SET"
        };
        StringBuilder sb = new StringBuilder("self=").append(self);
        for (String p : perms) {
            String out = runShort("pm grant " + self + " android.permission." + p + " 2>&1");
            sb.append("\n").append(p).append(" → ").append(out);
        }
        return sb.toString();
    }

    // 앱 내부 Runtime.exec 짧은 실행 — exit + stdout(80자). Overdrive PermissionGranter와 동일 방식.
    private String runShort(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder o = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) o.append(line).append(" ");
            br.close();
            int ex = p.waitFor();
            String out = o.toString().trim();
            return "exit=" + ex + (out.isEmpty() ? " (성공추정)" : " " + out.substring(0, Math.min(out.length(), 90)));
        } catch (Throwable t) { return "예외:" + t.getClass().getSimpleName() + ":" + t.getMessage(); }
    }

    // ★v172 DiCar CarServiceProvider 도달성 프로브 — AppServer가 재실/뒷좌석벨트를 읽는 ICarPropertyManager 채널의 실제 전송은
    //   content://com.byd.car.server.provider.CarServiceProvider/sync_binder 로 바인더를 얻는 방식. 우리(비-시스템 앱)가 이 provider에
    //   붙어 바인더를 받을 수 있는지 SDK 번들 없이 저비용 확인. SecurityException=권한차단(막힘), 바인더획득=SDK 이식 진행 가치 있음.
    private String carProbe() {
        final String AUTH = "com.byd.car.server.provider.CarServiceProvider";
        final String SVC = "com.byd.car.property.ICarPropertyService";   // ★v173 Manager 아닌 Service 인터페이스명(SPI 규약)
        android.net.Uri uri = android.net.Uri.parse("content://" + AUTH + "/sync_binder");
        StringBuilder sb = new StringBuilder();
        android.content.ContentResolver cr = getContentResolver();
        android.database.Cursor cur = null;
        try {
            // ★v173 정확한 규약(AppServer SPI 디컴파일): query(uri, null, null, [서비스클래스명], null) → cursor.extras.getParcelable("binder")
            cur = cr.query(uri, null, null, new String[]{SVC}, null);
            if (cur == null) { postToD1("CARPROBE", "query=null (BinderProvider 예외 — 클래스명/규약 오류 or 서비스 미준비)"); return "query=null (BinderProvider 예외 — 클래스명/규약 오류 or 서비스 미준비)"; }
            android.os.Bundle ex = cur.getExtras();
            if (ex == null) { postToD1("CARPROBE", "cursor OK지만 extras=null"); return "cursor OK지만 extras=null"; }
            ex.setClassLoader(com.byd.spi.ipc.cursor.BinderCursor.BinderParcelable.class.getClassLoader());
            sb.append("cursor OK extras=").append(ex.keySet());
            Object p = ex.getParcelable(com.byd.spi.ipc.cursor.BinderCursor.KEY_BINDER);
            if (!(p instanceof com.byd.spi.ipc.cursor.BinderCursor.BinderParcelable)) {
                sb.append(" | binder파슬 없음/타입불일치(").append(p).append(") — 엔진OFF/서비스미시작 추정");
                postToD1("CARPROBE", sb.toString());
                return sb.toString();
            }
            android.os.IBinder binder = ((com.byd.spi.ipc.cursor.BinderCursor.BinderParcelable) p).getBinder();
            sb.append("★바인더 획득 alive=").append(binder != null && binder.isBinderAlive());
            if (binder == null) { postToD1("CARPROBE", sb.toString()); return sb.toString(); }
            // ★도달성 진단만 — 바인더 획득/transact은 되나 값읽기는 signature 권한벽(재조사 말것, [[byd-occupancy-detection-state]]).
            //   재실 0x31200018 등 = code=20004 "Permission not granted"(BYDAUTO_SAFETY_BELT_GET/SETTING_GET=signature).
            //   차ON 실측·registerValueCallback 구독까지 다 해봐도 20004(2026-08-26). 무root 불가 → 단순 도달성만 남김.
            String[][] batch = { {"재실L","0x31200018"}, {"운전벨트","0x2610001A"}, {"대조:ACC모드","0x32D00034"} };
            for (String[] kv : batch) sb.append("\n").append(kv[0]).append("(").append(kv[1]).append(")=").append(getProp(binder, kv[1]));
            postToD1("CARPROBE", sb.toString());
            return sb.toString();
        } catch (SecurityException se) { postToD1("CARPROBE", sb + " | 보안차단=" + se.getMessage()); return sb + " | 보안차단=" + se.getMessage();
        } catch (Throwable t) { postToD1("CARPROBE", sb + " | 예외=" + t.getClass().getSimpleName() + ":" + t.getMessage()); return sb + " | 예외=" + t.getClass().getSimpleName() + ":" + t.getMessage();
        } finally { if (cur != null) try { cur.close(); } catch (Throwable ignored) {} }
    }

    // ★v184 카메라 도달성 프로브 — 일렉트로/오버드라이브 방식 카메라 스트리밍 가능여부 실차판정.
    //   (1) 권한 (2) ServiceManager 카메라 서비스 목록 (3) AVMCamera 리플렉션 open() (4) IBYDCameraService 바인더.
    //   전부 비파괴 — open 성공하면 즉시 close/release. 결과는 CAMPROBE 로그 + 요약 반환.
    private String camProbe() {
        StringBuilder sb = new StringBuilder();
        // (1) 권한
        try {
            for (String perm : new String[]{"android.permission.CAMERA", "android.permission.BYD_CAMERA",
                    "android.permission.BYDAUTO_PANORAMA_GET", "android.permission.BYDAUTO_PANORAMA_COMMON"}) {
                int g = checkSelfPermission(perm);
                sb.append(perm.substring(perm.lastIndexOf('.') + 1)).append("=").append(g == 0 ? "GRANTED" : "DENIED").append(" ");
            }
        } catch (Throwable t) { sb.append("권한체크예외:").append(t.getMessage()).append(" "); }
        postToD1("CAMPROBE", "권한: " + sb);

        // (2) ServiceManager 서비스 목록에서 카메라 후보
        StringBuilder svcs = new StringBuilder();
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            String[] list = (String[]) sm.getMethod("listServices").invoke(null);
            if (list != null) for (String n : list) {
                String ln = n.toLowerCase();
                if (ln.contains("camera") || ln.contains("avm") || ln.contains("byd") || ln.contains("panora") || ln.contains("dvr") || ln.contains("dashcam"))
                    svcs.append(n).append(" ");
            }
            postToD1("CAMPROBE", "카메라후보서비스: " + (svcs.length() == 0 ? "(없음)" : svcs));
        } catch (Throwable t) { postToD1("CAMPROBE", "listServices예외:" + t.getClass().getSimpleName() + ":" + t.getMessage()); }

        // (3) 씨라7 카메라 후보 5종 정찰 + AVMCamera 프리뷰 실증 (BeetleLauncher CamHelper.probe/open 이식)
        //   ★D5 검증: bmmcamera.jar DexClassLoader 없이 시스템 framework 클래스 직접(앱 도메인). 결과 3분기 해석:
        //     · 후보 클래스 전무 → 이 길 아님(서비스목록 참고)  · 프레임>0 → BeetleLauncher보다 단순, CAMRTC 바로 교체
        //     · 클래스는 있는데 open/start 권한류 예외 → 死 아님, shell daemon(app_process) 구조로 이전 필요
        StringBuilder avm = new StringBuilder();
        avm.append("uid=").append(android.os.Process.myUid()).append(" ");
        String[] camCands = {"android.hardware.AVMCamera", "android.hardware.BmmCameraInfo", "android.hardware.avm.AVMCamera", "com.ts.avm.sdk.AvmManager", "android.hardware.bydauto.panorama.tsimpl.PanoramaManagerImpl"};
        Class<?> avmCls = null; String avmName = null;
        for (String cn : camCands) {
            try {
                Class<?> c = Class.forName(cn);
                avm.append("★있다:").append(cn).append("[");
                java.lang.reflect.Method[] ms = c.getDeclaredMethods();
                int shown = 0;
                for (java.lang.reflect.Method m : ms) {
                    if (shown++ >= 14) { avm.append("…+").append(ms.length - 14); break; }
                    String nm = m.getName();
                    avm.append(nm).append("/").append(m.getParameterCount());
                    String low = nm.toLowerCase();
                    if (low.contains("preview") || nm.equals("open") || nm.equals("startPreview")) avm.append("!");
                    avm.append(" ");
                }
                avm.append("] ");
                if (avmCls == null && cn.endsWith("AVMCamera")) { avmCls = c; avmName = cn; }   // open+preview 있는 AVMCamera류를 실증 대상
            } catch (Throwable ig) { avm.append("·없다:").append(cn.substring(cn.lastIndexOf('.') + 1)).append(" "); }
        }
        if (avmCls != null) {
            avm.append("→실증(").append(avmName).append("): ");
            try {
                Object cam = null;
                try { cam = avmCls.getMethod("open", Integer.TYPE).invoke(null, Integer.valueOf(0)); if (cam != null) avm.append("open(0)static OK "); } catch (Throwable st) {}
                if (cam == null) {
                    try { java.lang.reflect.Constructor<?> ct2 = avmCls.getDeclaredConstructor(Integer.TYPE); ct2.setAccessible(true); cam = ct2.newInstance(Integer.valueOf(0)); if (cam != null) avm.append("생성(0)OK "); }
                    catch (Throwable ct) { avm.append("생성예외=").append(ct.getCause() != null ? ct.getCause() : ct).append(" "); }
                }
                if (cam != null) {
                    final int[] got = {0};
                    android.media.ImageReader ir = android.media.ImageReader.newInstance(1280, 720, android.graphics.ImageFormat.PRIVATE, 3);   // PRIVATE=포맷 불일치 false-negative 방지
                    android.os.HandlerThread ht = new android.os.HandlerThread("avmprobe"); ht.start();
                    ir.setOnImageAvailableListener(r -> { try { android.media.Image im = r.acquireLatestImage(); if (im != null) { got[0]++; im.close(); } } catch (Throwable ig) {} }, new Handler(ht.getLooper()));
                    android.view.Surface surf = ir.getSurface();
                    Object add = null, set = null, start = null;
                    try { add = avmCls.getMethod("addPreviewSurface", android.view.Surface.class, Integer.TYPE).invoke(cam, surf, Integer.valueOf(0)); } catch (Throwable t) { avm.append("add예외=").append(t.getCause() != null ? t.getCause() : t).append(" "); }
                    try { set = avmCls.getMethod("setPreviewSurface", android.view.Surface.class, Integer.TYPE).invoke(cam, surf, Integer.valueOf(0)); } catch (Throwable t) {}
                    try { start = avmCls.getMethod("startPreview").invoke(cam); } catch (Throwable t) { avm.append("start예외=").append(t.getCause() != null ? t.getCause() : t).append(" "); }
                    avm.append("add=").append(add).append(" set=").append(set).append(" start=").append(start).append(" ");
                    try { Thread.sleep(2000); } catch (Throwable ig) {}
                    avm.append("★프레임=").append(got[0]).append(" ");
                    try { avmCls.getMethod("stopPreview").invoke(cam); } catch (Throwable ig) {}
                    try { avmCls.getMethod("closeCamera").invoke(cam); } catch (Throwable ig) { try { avmCls.getMethod("close").invoke(cam); } catch (Throwable ig2) {} }
                    try { ir.close(); } catch (Throwable ig) {}
                    try { ht.quitSafely(); } catch (Throwable ig) {}
                }
            } catch (Throwable t) { avm.append("실증예외=").append(t.getClass().getSimpleName()).append(":").append(t.getMessage()).append(" "); }
        } else {
            avm.append("→AVMCamera류 후보 없음(이 길 아닐 수 — 아래 서비스목록 확인) ");
        }
        postToD1("CAMPROBE", "AVMCamera: " + avm);

        // (4) 실차 발견 서비스명으로 바인더 잡기 + 인터페이스 디스크립터 + 벤치 transact(도달성 판정)
        StringBuilder byd = new StringBuilder();
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getSvc = sm.getMethod("getService", String.class);
            for (String name : new String[]{"bydcameramanager", "bmmcameraserver", "IBYDCDRService",
                    "android.hardware.bydauto.panorama.IBYDAutoPanoService", "byd_car_service", "media.camera"}) {
                try {
                    Object b = getSvc.invoke(null, name);
                    if (b == null) { byd.append(name).append("=null "); continue; }
                    android.os.IBinder binder = (android.os.IBinder) b;
                    String desc = "?"; try { desc = binder.getInterfaceDescriptor(); } catch (Throwable ignore) {}
                    boolean alive = false; try { alive = binder.isBinderAlive(); } catch (Throwable ignore) {}
                    // 벤치 transact: INTERFACE_TRANSACTION(디스크립터 반환) — 비파괴, 권한게이트 도달성 신호
                    String tx = "?";
                    try {
                        android.os.Parcel din = android.os.Parcel.obtain(), dout = android.os.Parcel.obtain();
                        boolean ok = binder.transact(android.os.IBinder.INTERFACE_TRANSACTION, din, dout, 0);
                        tx = "transact=" + ok;
                        din.recycle(); dout.recycle();
                    } catch (SecurityException se) { tx = "SEC:" + se.getMessage();
                    } catch (Throwable te) { tx = te.getClass().getSimpleName() + ":" + te.getMessage(); }
                    byd.append(name).append("[desc=").append(desc).append(" alive=").append(alive).append(" ").append(tx).append("] ");
                    postToD1("CAMPROBE", "SVC " + name + ": desc=" + desc + " alive=" + alive + " " + tx);
                } catch (Throwable e) { byd.append(name).append("=예외:").append(e.getMessage()).append(" "); }
            }
        } catch (Throwable t) { postToD1("CAMPROBE", "getService예외:" + t.getMessage()); }

        return "CAMPROBE — 권한[" + sb + "] AVM[" + avm + "] 서비스[" + (svcs.length() == 0 ? "없음" : svcs) + "] 바인더[" + byd + "]";
    }

    // ★v187 파일 풀 — 헤드유닛 파일(bmmcamera.jar·APK 등)을 raw로 워커에 업로드(디컴파일용). 앱은 /system 644 파일 읽기 가능.
    // ★617 클러스터 측면캠 커스텀 설정 — params {side:2좌|4우|8후진, x,y,w,h(타일 내 0~1), dewarp, fov(90~220), mirror} 또는 {side, reset:true}.
    //   prefs ccam_<side>에 저장, relay_state ccam_cfg로 현재값 공개(앱 편집기가 읽어 초기값). ClusterCam은 다음 프레임부터 반영.
    private volatile int ccamGen = 0;
    // ★718 저장값이 없을 때의 기본 = 추천 화면(운영자 10-04 "따로 조정하지 않으면 디폴트는 추천 화각으로") — 워커 /camrtc 추천값과 같게 유지.
    //   후방=가로 직선 147°·거의 전체(벽~범퍼·번호판)·좌우 반전(★719), 측면=사각지대(파노라마 163°·뒤쪽 바깥 영역·기울기 좌 −40°/우 +40°·위로 15°·사이드미러처럼 좌우 반전).
    //   조정 화면 '초기화'는 저장값을 지워(reset) 이 기본으로 돌아간다.
    static String ccamDefault(int sd) {
        switch (sd) {
            case 8: return "{\"x\":0.03,\"y\":0.05,\"w\":0.94,\"h\":0.95,\"dw\":true,\"fov\":147,\"rot\":0,\"k\":0,\"cx\":0,\"cy\":0,\"pan\":true,\"proj\":2,\"tilt\":0,\"mir\":true}";   // ★719 후방 기본 좌우 반전(운영자 승인 10-04) — 원본은 뒤를 바라본 그대로라 일반 후진 화면과 좌우 반대
            case 2: return "{\"x\":0,\"y\":0.1,\"w\":0.7,\"h\":0.65,\"dw\":true,\"fov\":163,\"rot\":-40,\"k\":0,\"cx\":0,\"cy\":0,\"pan\":true,\"proj\":1,\"tilt\":15,\"mir\":true}";
            case 4: return "{\"x\":0.3,\"y\":0.1,\"w\":0.7,\"h\":0.65,\"dw\":true,\"fov\":163,\"rot\":40,\"k\":0,\"cx\":0,\"cy\":0,\"pan\":true,\"proj\":1,\"tilt\":15,\"mir\":true}";
            // ★751 후진 D 왼쪽·오른쪽(82·84) — 747~750 고정 화면과 같은 값(칸 1280×960 기준 영역 96·160·768×640 / 416·160·768×640, 파노라마 163°, 기울기 ∓50°, 아래로 30°, 좌우 반전).
            //   운영자 10-05 "보여줄 화각이나 위치도 수정할 수 있게" — 깜빡이 화면(2·4)과 따로 저장한다.
            case 82: return "{\"x\":0.075,\"y\":0.166667,\"w\":0.6,\"h\":0.666667,\"dw\":true,\"fov\":163,\"rot\":-50,\"k\":0,\"cx\":0,\"cy\":0,\"pan\":true,\"proj\":1,\"tilt\":-30,\"mir\":true}";
            case 84: return "{\"x\":0.325,\"y\":0.166667,\"w\":0.6,\"h\":0.666667,\"dw\":true,\"fov\":163,\"rot\":50,\"k\":0,\"cx\":0,\"cy\":0,\"pan\":true,\"proj\":1,\"tilt\":-30,\"mir\":true}";
            default: return "";
        }
    }
    private String ccamOf(int sd) {
        String v = prefs != null ? prefs.getString("ccam_" + sd, "") : "";
        return v.isEmpty() ? (IS_D5 ? "" : ccamDefault(sd)) : v;   // ★745 씨라7은 돌핀 렌즈로 맞춘 추천 화면 대신 칸 전체(렌즈 미확인)
    }
    // ★745 씨라7 칸 전체 그대로(조정 화면 시작값) — ccamOf가 ""일 때 실제로 나가는 화면과 같다.
    private static final String D5_WHOLE = "{\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"dw\":false,\"fov\":160,\"rot\":0,\"k\":0,\"cx\":0,\"cy\":0,\"pan\":false,\"proj\":0,\"tilt\":0,\"mir\":false}";
    private String clusterCamCfg(org.json.JSONObject p) {
        if (p == null || prefs == null) return "params 없음";
        // ★745 씨라7 카메라 보조 켬/끔·칸 배치 {d5on, d5map:{rear,left,right}} — 기본 꺼짐, 차주가 앱에서 4분할을 보고 고른다(운영자 10-05).
        if (p.has("d5on") || p.has("d5map")) {
            android.content.SharedPreferences.Editor e = prefs.edit();
            if (p.has("d5on")) e.putBoolean("d5_assist", p.optBoolean("d5on", false));
            org.json.JSONObject m = p.optJSONObject("d5map");
            if (m != null) e.putInt("d5_slot_rear", m.optInt("rear", 2) & 3).putInt("d5_slot_left", m.optInt("left", 3) & 3).putInt("d5_slot_right", m.optInt("right", 1) & 3);
            e.apply();
            loadD5Slots(); ccamGen++; publishClusterCamCfg();
            int[] s = d5Slot;
            postToD1("CLUSTER", "씨라7 보조 " + (prefs.getBoolean("d5_assist", false) ? "켬" : "끔") + " · 칸 후방=" + s[0] + " 왼쪽=" + s[1] + " 오른쪽=" + s[2]);
            return null;
        }
        int sd = p.optInt("side", 0);
        if (sd != 2 && sd != 4 && sd != 8 && sd != 82 && sd != 84) return "side는 2(좌)/4(우)/8(후진)/82·84(후진 왼쪽·오른쪽)";
        String key = "ccam_" + sd;
        // ★734 후진 화면 A·B·C — 폰 963~은 {side:8, layout} 만 보낸다(후방 영역은 그대로). 옛 폰(962·안드 1.2.5x)의 rev3는 좌·후·우=B·후방만=A로.
        // ★749 후진 화면은 D(좌·우 사이드미러)만 — 예전 앱(아이폰 963~972·안드)이 보내는 A·B·C 선택(layout·rev3)은 받기만 하고 무시.
        if (sd == 8 && (p.has("layout") || p.has("rev3"))) {
            postToD1("CLUSTER", "후진 화면 선택 " + p.optString("layout", p.has("rev3") ? "rev3" : "") + " 받음 — 749부터 좌·우 사이드미러(D)만이라 무시");
            if (!p.has("x")) return null;
        }
        if (p.optBoolean("reset")) prefs.edit().remove(key).apply();
        else {
            double x = Math.max(0, Math.min(0.95, p.optDouble("x", 0))), y = Math.max(0, Math.min(0.95, p.optDouble("y", 0)));
            double w = Math.max(0.05, Math.min(1 - x, p.optDouble("w", 1))), h = Math.max(0.05, Math.min(1 - y, p.optDouble("h", 1)));
            try {
                org.json.JSONObject c = new org.json.JSONObject().put("x", x).put("y", y).put("w", w).put("h", h)
                        .put("dw", p.optBoolean("dewarp", false)).put("fov", Math.max(60, Math.min(220, p.optDouble("fov", 160))))   // ★716 하한 90→60(실시간 카메라 조정 A 범위 60~180)   // ★656 기본 160(실측 보정, 아래 encodeCustom 주석)
                        .put("rot", Math.max(-180, Math.min(180, p.optDouble("rot", 0))))   // ★656 기울기(도, 운영자 09-29 "사각형도 회전") ★664 ±180
                        .put("k", Math.max(-0.3, Math.min(0.5, p.optDouble("k", 0))))   // ★716 상한 0.3→0.5(실시간 카메라 조정 B 범위 0~0.5)   // ★712 렌즈 왜곡(자동 보정, r=f·(θ+kθ³))
                        .put("cx", Math.max(-0.15, Math.min(0.15, p.optDouble("cx", 0)))).put("cy", Math.max(-0.15, Math.min(0.15, p.optDouble("cy", 0))))   // ★712 렌즈 중심(타일 가운데 기준 비율)
                        .put("pan", p.optBoolean("pano", false)).put("tilt", Math.max(-90, Math.min(90, p.optDouble("tilt", 0))))   // ★664 원통 파노라마(넓은 영역을 늘어나지 않게 — 후진 좌·후·우 이어 보기)
                        .put("proj", Math.max(0, Math.min(2, p.optInt("proj", p.optBoolean("pano", false) ? 1 : 0))))   // ★718 펴기 방식 0=원근·1=파노라마·2=가로 직선(페이지는 2일 때 pano=true도 보내 옛 릴레이는 파노라마로)
                        .put("mir", p.optBoolean("mirror", false));
                prefs.edit().putString(key, c.toString()).apply();
            } catch (Throwable t) { return "설정 저장 실패: " + t; }
        }
        ccamGen++;
        publishClusterCamCfg();
        postToD1("CLUSTER", "커스텀 뷰 설정 side=" + sd + " " + prefs.getString(key, "(초기화)"));
        return null;
    }
    // ★619 클러스터 레이아웃(운영자 09-28 "집에서 셋팅·커스텀은 할 수 있어야") — 예전엔 클러스터를 띄운 기기의 브라우저 localStorage에만 있어
    //   차 와이파이 직결 상태에서만 편집 가능했다. 릴레이 prefs(cluster_layout)로 옮겨 LAN(/cmd)·원격(워커 명령) 어느 쪽으로도 저장.
    //   params {layout:{...}} 또는 {reset:true}. 클러스터 페이지는 켤 때 /cluster/cfg에서 읽는다.
    private String clusterLayoutCfg(org.json.JSONObject p) {
        if (p == null || prefs == null) return "params 없음";
        if (p.optBoolean("reset")) prefs.edit().remove("cluster_layout").apply();
        else {
            org.json.JSONObject l = p.optJSONObject("layout");
            if (l == null) return "layout 없음";
            String s = l.toString();
            if (s.length() > 6000) return "layout 너무 큼(" + s.length() + "자)";
            prefs.edit().putString("cluster_layout", s).apply();
        }
        if (relay != null) relay.putStatus("cluster_layout", prefs.getString("cluster_layout", ""));
        postToD1("CLUSTER", "레이아웃 저장 " + (p.optBoolean("reset") ? "(초기화)" : prefs.getString("cluster_layout", "").length() + "자"));
        return null;
    }
    String clusterCfgJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            String l = prefs != null ? prefs.getString("cluster_layout", "") : "";
            o.put("layout", l.isEmpty() ? org.json.JSONObject.NULL : new org.json.JSONObject(l));
            org.json.JSONObject cc = new org.json.JSONObject();
            for (int sd : new int[]{2, 4, 8, 82, 84}) { String v = prefs != null ? prefs.getString("ccam_" + sd, "") : ""; if (!v.isEmpty()) cc.put(String.valueOf(sd), new org.json.JSONObject(v)); }
            String lay = clusterCam.revLayout();
            cc.put("rlay", lay).put("rev3", !"A".equals(lay));   // ★734 후진 화면 A·B·C(옛 폰은 rev3: B·C=좌·후·우로 보임)
            cc.put("lens", true);   // ★712 렌즈 왜곡·중심(k·cx·cy)을 받는 릴레이 — 편집기는 이게 있을 때만 그 값을 찾고 보낸다
            o.put("ccam", cc);
            o.put("camActive", clusterCam.active);
            return o.toString();
        } catch (Throwable t) { return "{}"; }
    }
    private void publishClusterCamCfg() {
        if (relay == null || prefs == null) return;
        try {
            org.json.JSONObject all = new org.json.JSONObject();
            for (int sd : new int[]{2, 4, 8, 82, 84}) { String v = prefs.getString("ccam_" + sd, ""); if (!v.isEmpty()) all.put(String.valueOf(sd), new org.json.JSONObject(v)); }
            String lay = clusterCam.revLayout();
            all.put("rlay", lay).put("rev3", !"A".equals(lay));   // ★734 후진 화면 A·B·C(옛 폰은 rev3: B·C=좌·후·우로 보임)
            all.put("revD", true);   // ★747 후진 D(좌·우만)를 아는 릴레이 — 앱은 이게 있을 때만 D를 고르게 한다
            if (!IS_D5) all.put("revAdj", true);   // ★751 후진 왼쪽·오른쪽 화면(82·84)을 조정할 수 있는 릴레이 — 앱은 이게 있을 때만 조정 버튼을 보인다
            all.put("lens", true);   // ★712 clusterCfgJson과 같음(원격 편집기용)
            all.put("proj2", true);   // ★718 가로 직선(proj=2) 지원 — 페이지가 이 표시 없는 옛 릴레이면 안내하고 후방 추천값을 원근으로
            org.json.JSONObject def = new org.json.JSONObject();   // ★718 저장값 없을 때 실제로 쓰는 기본(추천) 화면 — 페이지가 조정 시작값으로 보여 줌
            for (int sd : new int[]{2, 4, 8}) def.put(String.valueOf(sd), new org.json.JSONObject(IS_D5 ? D5_WHOLE : ccamDefault(sd)));
            if (!IS_D5) for (int sd : new int[]{82, 84}) def.put(String.valueOf(sd), new org.json.JSONObject(ccamDefault(sd)));   // ★751 후진 왼쪽·오른쪽 기본
            all.put("def", def);
            if (IS_D5) {   // ★745 씨라7 보조 켬·칸 배치(set=차주가 고른 적 있음) — 앱 설정 화면이 이 값으로 그린다
                int[] s = d5Slot;
                all.put("d5", new org.json.JSONObject().put("on", prefs.getBoolean("d5_assist", false)).put("set", prefs.contains("d5_slot_rear"))
                        .put("rear", s[0]).put("left", s[1]).put("right", s[2]));
            }
            relay.putStatus("ccam_cfg", all.toString());
        } catch (Throwable ignored) {}
    }

    // ★611 com.ts.appservice.power APK 1회 확보(운영자 승인 09-27) — 씨라7 시동OFF 직후 릴레이 강제종료 범인의 예외정책 분석용.
    //   공유판 PULLFILE(임의 경로)은 계속 금지, 이 명령은 패키지 고정·인자 없음(경로 지정 불가) — 시스템 앱 APK와 같은 폴더 oat만 A1로.
    private String pullPowerApk() {
        final String pkg = "com.ts.appservice.power";
        String out = AdbAccess.runShell(this, "pm path " + pkg);
        if (out == null || !out.contains("package:")) return "PULLPOWERAPK pm path 실패: " + out;
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (String ln : out.split("\n")) if (ln.trim().startsWith("package:")) paths.add(ln.trim().substring(8));
        java.io.File dir = new java.io.File(paths.get(0)).getParentFile();
        String oat = AdbAccess.runShell(this, "find " + dir.getPath() + "/oat -type f 2>/dev/null");
        if (oat != null) for (String ln : oat.split("\n")) if (ln.trim().startsWith("/")) paths.add(ln.trim());
        StringBuilder r = new StringBuilder("PULLPOWERAPK");
        long ts = System.currentTimeMillis();
        for (String p : paths) {
            java.io.File f = new java.io.File(p);
            String src = p;
            if (!f.canRead()) {   // 앱 uid로 못 읽으면 shell로 임시 복사(644) 후 업로드
                src = "/data/local/tmp/tspower_" + f.getName();
                AdbAccess.runShell(this, "cp " + p + " " + src + " && chmod 644 " + src);
            }
            String name = "tspower_" + ts + "_" + f.getName();
            try {
                String e = pullFile(new org.json.JSONObject().put("path", src).put("name", name), true);
                r.append(' ').append(name).append(e == null ? "=OK" : "=" + e);
            } catch (Throwable t) { r.append(' ').append(name).append("=예외 ").append(t.getClass().getSimpleName()); }
            if (!src.equals(p)) AdbAccess.runShell(this, "rm -f " + src);
        }
        postToD1("PULLFILE", r.toString());
        return r.toString();   // 결과를 relay_commands.result로 보이게(ok=false 표기지만 내용이 결과)
    }

    private String pullFile(org.json.JSONObject params) { return pullFile(params, false); }
    private String pullFile(org.json.JSONObject params, boolean quiet) {
        if (params == null) return "params 없음";
        String pathStr = params.optString("path");
        String name = params.optString("name", pathStr.substring(pathStr.lastIndexOf('/') + 1));
        if (pathStr.isEmpty()) return "path 없음";
        java.io.File f = new java.io.File(pathStr);
        if (!f.canRead()) return "읽기불가(권한/부재): " + pathStr;
        java.net.HttpURLConnection c = null;
        try {
            String url = "https://byd.cseini.co.kr/relay/pullfile?name=" + java.net.URLEncoder.encode(name, "UTF-8")
                    + "&auth=" + BuildConfig.RELAY_SECRET;
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setDoOutput(true); c.setRequestMethod("POST"); c.setConnectTimeout(15000); c.setReadTimeout(120000);
            c.setFixedLengthStreamingMode(f.length());
            c.setRequestProperty("Content-Type", "application/octet-stream");
            try (java.io.InputStream in = new java.io.FileInputStream(f); java.io.OutputStream out = c.getOutputStream()) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            int code = c.getResponseCode();
            java.io.InputStream rs = code < 400 ? c.getInputStream() : c.getErrorStream();
            String resp = rs == null ? "" : new java.util.Scanner(rs).useDelimiter("\\A").next();
            String r = "PULLFILE " + name + " (" + f.length() + "B) → HTTP " + code + " " + resp;
            if (!quiet || code >= 400) postToD1("PULLFILE", r);
            return code < 400 ? null : r;
        } catch (Throwable t) { String e = "PULLFILE 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage(); postToD1("PULLFILE", e); return e;
        } finally { if (c != null) c.disconnect(); }
    }

    // ★v188 실제 프레임 캡처 — DexClassLoader로 bmmcamera.jar 로드 → NormalCamera.open → onPreview 프레임 1장 → JPEG → 워커.
    //   params: {camId:int(옵션, 없으면 cam_sort 또는 0~9 스윕), fmt:int(NV21=17 기본)}
    private ClassLoader bmmLoader;
    private byte[] camFrame; private int[] camMeta = new int[4]; // w,h,colorFormat,size
    // ★모션감지(CAMDETECT) — byd-motiontest서 검증한 MotionDetector 이식. 카메라 open은 camShot 패턴 재사용,
    //   WebRTC 없이 onPreview 프레임을 로컬 MOG2로 분석 → ROI별 움직임을 relay state(mot<id>)로 업로드.
    //   params: {avm, view, sec, rois:[{id, nx:[..], ny:[..], minPct, sustainMs}], stop}
    private volatile boolean detectActive;
    private volatile boolean detectSentryMode;   // ★센트리 카메라 세션 표지(docs/SENTRY-DESIGN.md 훅 v2) — detectActive와 함께 camDetectLocked가 세운다
    private volatile int detectGen = 0;   // ★이중시작 방지 — 새 camDetect가 증가시키고, 옛 스레드는 gen 다르면 카메라 열자마자 폐기(2 프레임 stall 방지)
    private volatile long detectLastFrameMs = 0;   // ★마지막 카메라 프레임 수신 시각 — 좀비 세션(플래그만 살고 프레임 死) 감지용. 프레임 콜백서 갱신.
    private Thread detectThread;
    // ★전방 주행 세션 자동 무장(2026-09-11 운영자 "매번 감지시작 누르기 귀찮다") — 앱이 보낸 drive 모드 params를 prefs(front_drive_params)에 보관하고,
    //   폴 루프가 주행(D/R) 감지 시 스스로 시작·P기어에서 종료. 명령 스레드(camDetect)와 폴 스레드(자동 무장)가 detectActive/detectGen을 함께 만지므로 detectLock으로 직렬화.
    private volatile boolean detectDriveMode;
    // ★626 클러스터(360 AVM)와 전방 감지(대시캠 NormalCamera) 동시 운용(운영자 09-28 "전방은 대시캠이라 동시에 열 수 있지 않을까") —
    //   서로 다른 카메라라 선점하지 않는다. 첫 프레임이 안 오면 예전 선점 방식으로 자동 복귀.
    //   ★784 복귀는 그 주행에서 두 번 실패했을 때만, 그 주행 동안만(시동 켤 때 dualFails=0) — 예전엔 한 번 실패를 prefs에 영구 기록해,
    //   10-06 06:44 운영자 차에서 시동 직후 근접 감지(AVM)가 넘어가는 순간 첫 프레임이 한 번 안 온 뒤로 모든 주행이 상시 대기 없이 매번 새로 열려 1.1초 늦었다.
    private volatile boolean detectAvm = true;
    private volatile int dualFails;
    private boolean camDualOk() { return dualFails < 2; }
    private void markDualFail(String why) {
        int n = ++dualFails;
        postToD1("CLUSTER", n >= 2 ? "★카메라 동시 운용 실패 " + n + "번째(" + why + ") → 이번 주행은 클러스터가 전방 감지를 선점하는 예전 방식"
                : "카메라 동시 운용 실패 1번째(" + why + ") — 이번 주행에 한 번 더 실패하면 예전 방식");
        if (relay != null) relay.putStatus("cam_dual", "fail" + n + ":" + why);
    }
    private final Object detectLock = new Object();
    private long frontArmTryMs = 0;
    private long frontArmDiagMs = 0;   // ★493 진단 스로틀 — frontAutoArm이 왜 무장 안 하는지 30s마다 사유 로그
    // ★센트리 호스트 창(docs/SENTRY-DESIGN.md 훅 v2) — SentryManager가 보는 유일한 바깥 면. 익명 클래스 안에서 같은 이름 메서드는 자기 재귀이므로 SnifferService.this로 부른다.
    // ★센트리 "사람·차량만"(10-07 YOLO11n로 되살림) — 4칸 각각에서 사람·차량 최고 점수를 재서 칸 중 최대를 돌려준다. 서비스 전체 잠금(this)을 잡지 않는다(추론 1초 안팎)
    private com.cseini.motiondetect.YoloDetector sentryYolo;
    private final Object sentryYoloLock = new Object();
    private float[] sentryObjectScores(byte[] nv21, int w, int h) {
        synchronized (sentryYoloLock) {
            try {
                if (sentryYolo == null) sentryYolo = new com.cseini.motiondetect.YoloDetector(getApplicationContext());
                if (!sentryYolo.isReady()) return null;
                int hw = w / 2, hh = h / 2;
                float p = 0f, v = 0f;
                for (int q = 0; q < 4; q++) {
                    float[] r = sentryYolo.scores(nv21, w, h, (q % 2) * hw, (q / 2) * hh, hw, hh);
                    if (r != null) { p = Math.max(p, r[0]); v = Math.max(v, r[1]); }
                }
                return new float[]{p, v};
            } catch (Throwable t) { postToD1("SENTRY", "물체 인식 오류 " + t.getClass().getSimpleName()); return null; }
        }
    }
    private float[][] sentryObjectDetect(byte[] nv21, int w, int h, int rx, int ry, int rw, int rh, float minScore) {
        synchronized (sentryYoloLock) {
            try {
                if (sentryYolo == null) sentryYolo = new com.cseini.motiondetect.YoloDetector(getApplicationContext());
                if (!sentryYolo.isReady()) return null;
                return sentryYolo.detect(nv21, w, h, rx, ry, rw, rh, minScore);
            } catch (Throwable t) { postToD1("SENTRY", "물체 인식 오류 " + t.getClass().getSimpleName()); return null; }
        }
    }
    private void sentryObjectRelease() {
        synchronized (sentryYoloLock) {
            if (sentryYolo == null) return;
            try { sentryYolo.close(); } catch (Throwable ignored) {}
            sentryYolo = null;
        }
    }

    private SentryHost sentryHost() {
        return new SentryHost() {
            @Override public android.content.Context context() { return SnifferService.this; }
            @Override public android.content.SharedPreferences prefs() { return prefs; }
            @Override public void log(String m) { postToD1("SENTRY", m); }
            @Override public void putStatus(String key, Object val) { RelayClient rc = relay; if (rc != null) rc.putStatus(key, val); }
            @Override public void postCarEvent(String event) { RelayClient rc = relay; if (rc != null) rc.postCarEvent(event); }
            @Override public int ignitionState() { RelayClient rc = relay; return rc != null ? rc.ignitionState() : -1; }
            @Override public boolean rtcActive() { return (rtcActive && !rtcTapsSentry) || camStreaming || h264Streaming; }   // ★실시간 보기가 센트리 프레임을 받아 쓰는 중이면 게이트가 막지 않는다
            @Override public boolean detectActive() { return detectActive && !detectSentryMode && !canTapDetect(); }   // ★주차 AVM 감지는 센트리가 프레임을 나눠 쓰므로 막지 않는다
            @Override public boolean phoneNearFresh() { return SnifferService.this.phoneNearFresh(); }
            @Override public boolean lowVoltage() { return lowVoltFlag == 1 && lowSinceMs > 0 && System.currentTimeMillis() - lowSinceMs > lowSustainMs; }
            @Override public int batteryPct() { return lastTripSoc; }
            @Override public float[] objectScores(byte[] nv21, int w, int h) { return sentryObjectScores(nv21, w, h); }
            @Override public float[][] objectDetect(byte[] nv21, int w, int h, int rx, int ry, int rw, int rh, float minScore) { return sentryObjectDetect(nv21, w, h, rx, ry, rw, rh, minScore); }
            @Override public long objectLastMs() { com.cseini.motiondetect.YoloDetector y = sentryYolo; return y == null ? 0 : y.lastInferMs(); }
            @Override public void objectRelease() { sentryObjectRelease(); }
            @Override public boolean lanAuthRequired() { return !BuildConfig.IS_OPERATOR; }
            @Override public boolean verifySentryGrant(String sgx, String sg) { RelayClient rc = relay; return rc != null && rc.verifySentryGrant(sgx, sg); }
            @Override public boolean camOff() { return camOffOn(); }   // ★790 앱 설정 카메라 끔이면 센트리는 게이트에서 IDLE로 쉰다(센트리 sentry25)
            @Override public String startSentryDetect() {
                if (allStopOn()) return "모든 자동 기능 정지 중";
                synchronized (detectLock) {
                    if (detectActive && !detectSentryMode) {
                        if (canTapDetect()) { sentryTapsDetect = true; postToD1("SENTRY", "다른 감지(주차 카메라 감지)와 카메라 프레임을 나눠 씁니다"); return "감지 시작 — 프레임 공유"; }
                        return "다른 감지 사용 중";
                    }
                    try { return camDetect(new org.json.JSONObject().put("mode", "sentry").put("avm", true).put("view", 5)); }
                    catch (org.json.JSONException e) { return "센트리 감지 시작 실패: " + e; }
                }
            }
            @Override public void stopSentryDetect() {
                sentryTapsDetect = false;   // ★792 프레임 공유 중이었다면 공유만 끊는다(다른 감지 세션은 그대로)
                if (detectSentryMode) { detectSentryMode = false; detectStop(); }
            }
        };
    }
    private String camDetect(org.json.JSONObject params) {
        if (camOffOn()) return CAM_OFF_MSG;   // ★카메라 기능 끔
        synchronized (detectLock) { return camDetectLocked(params); }
    }
    private String camDetectLocked(org.json.JSONObject params) {
        if (params != null && params.optBoolean("stop")) {
            if (detectSentryMode && !"sentry".equals(params.optString("mode"))) return "감지 중지(센트리 유지)";   // ★앱 근접 감지의 자동 stop이 센트리 세션을 죽이지 않게(훅 v2 ⓪)
            detectStop(); clearMotState();
            try { if (prefs != null) prefs.edit().remove("camdetect_resume").remove("camdetect_until").apply(); } catch (Throwable ignored) {}
            return "감지 중지"; }
        final boolean sentryMode = params != null && "sentry".equals(params.optString("mode"));   // ★센트리 세션(docs/SENTRY-DESIGN.md 훅 v2) — 프레임은 SentryManager로, 기존 감지·알림·스트림 전부 건너뜀
        final boolean driveMode = params != null && "drive".equals(params.optString("mode", "park"));   // ★전방 대시캠 주행 알림 모드(앞차출발 role3·신호변경 role4) — 헤드유닛 로컬 사운드, 워커 push 안 함. role 0/1/2 없어 킥 블록은 kd null로 자동 스킵.
        if (driveMode && prefs != null) {
            // ★앱이 전방 ROI를 저장/실행할 때마다 params 보관 → 자동 무장 재료. provision=true면 보관만(시작 안 함: 앱 실행·ROI 편집 시 프로비저닝).
            try { org.json.JSONObject keep = new org.json.JSONObject(params.toString()); keep.remove("provision"); keep.remove("sec");
                  prefs.edit().putString("front_drive_params", keep.toString()).apply(); } catch (Throwable ignored) {}
            if (params.optBoolean("provision")) return "전방 세션 저장(자동 무장 대기)";
            // ★중복 시작 억제 — 이미 같은 뷰의 주행 세션이 살아 있으면 카메라 껐다켜기(2프레임 stall) 안 함. force=true만 재시작.
            if (detectActive && detectDriveMode && !params.optBoolean("force")) return "이미 주행 감지 중";
        }
        if (driveMode && DiLink5Support.isDiLink5()) return "미지원 — 씨라이언7은 대시캠이 없어 신호등 알림 불가";   // ★640 신호등 테스터 공개(운영자 09-29) — 공유판도 허용, D5만 제외
        if (camProbing) return "카메라 점검 중 — 잠시 후 다시";   // ★655
        detectStop();
        clearMotState();   // ★이전 감지 잔여 mot 값 초기화(옛 값 고착=화면 멈춤 방지)
        final boolean avm0 = params == null || params.optBoolean("avm", true);
        if (avm0 || !camDualOk()) bsdExp.stopNow("camDetect");   // ★602 BSD실험 선점 — ★723 대시캠 감지는 360과 다른 카메라라 같이 돈다(626)
        if (clusterCam.active && (avm0 || !camDualOk())) clusterCam.yieldIdle("카메라 감지 우선");   // ★729 보조 카메라 상시 대기·예열(신호 표시 중 아님)이면 주차 감지에 비켜 준다
        if (rtcActive || camStreaming || h264Streaming || (clusterCam.active && (avm0 || !camDualOk())) || (bsdExp.active && (avm0 || !camDualOk()))) return "카메라 사용 중 — 감지 불가";   // ★626 대시캠 감지는 클러스터와 동시 허용   // clusterCam=깜빡이 측면 카메라(2026-09-17), 끝나면 frontAutoArmTick이 재무장
        final boolean avm = avm0;
        detectAvm = avm;
        final int view = params != null ? params.optInt("view", 0) : 0;
        detectViewNum = view;
        final int durationSec = params != null ? params.optInt("sec", 600) : 600;
        final boolean notify = params != null && params.optBoolean("notify", false);   // ★백그라운드 감지확인 push(엣지+쿨다운)
        final boolean stream = BuildConfig.IS_OPERATOR && params != null && params.optBoolean("stream", false);   // ★599 공유판은 프레임 업로드 금지(움직임 %만)   // ★감지하며 그 프레임을 워커에 JPEG로도 올림 → 폰이 /cam으로 라이브+% 동시 확인(테스트/튜닝용). 한 카메라.
        final int sq = params != null ? params.optInt("sq", 40) : 40;                   // 스트림 JPEG 품질
        // ★튜닝 파라미터(재빌드 없이 워커명령으로 조절). lr=learningRate 낮을수록 배경 천천히 적응=느린/작은 움직임 민감(웹서치 확인).
        //   skip=N프레임마다 처리(byd-motiontest=3, 배경적응 느리게). 기본을 byd-motiontest(작동앱)에 맞춤.
        final float lr = params != null ? (float) params.optDouble("lr", 0.008) : 0.008f;
        final float vt = params != null ? (float) params.optDouble("vt", 16) : 16f;
        final float bgr = params != null ? (float) params.optDouble("bgr", 0.9) : 0.9f;
        final int skip = params != null ? Math.max(1, params.optInt("skip", 3)) : 3;
        final java.util.List<com.cseini.motiondetect.Roi> rois = new java.util.ArrayList<>();
        try {
            org.json.JSONArray arr = params != null ? params.optJSONArray("rois") : null;
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject r = arr.getJSONObject(i);
                if (!BuildConfig.IS_OPERATOR && r.optInt("id", i) > 1 && r.optInt("id", i) != 4) continue;   // ★599 공유판은 접근보조(0)·작업영역(1)만 — ★640 신호등(4) 추가 공개. 트렁크(2)는 운영자판 전용
                org.json.JSONArray jx = r.getJSONArray("nx"), jy = r.getJSONArray("ny");
                float[] nx = new float[jx.length()], ny = new float[jy.length()];
                for (int k = 0; k < nx.length; k++) { nx[k] = (float) jx.getDouble(k); ny[k] = (float) jy.getDouble(k); }
                rois.add(new com.cseini.motiondetect.Roi(r.optInt("id", i), nx, ny,
                        (float) r.optDouble("minPct", 0.03), r.optLong("sustainMs", 300)));
            }
        } catch (Throwable t) { return "ROI 파싱 실패: " + t; }
        if (!sentryMode && rois.isEmpty()) return "ROI 없음";

        detectActive = true; detectSentryMode = sentryMode; if (!sentryMode) RelayClient.cameraActive = true; detectDriveMode = driveMode;   // ★센트리는 cameraActive를 세우지 않음(밤새 폴링 600ms 방지)
        // ★활성 감지를 prefs에 저장 → 릴레이가 ACC OFF 태스크청소 등으로 죽어도 재시작(onCreate) 시 창(sec) 안이면 자동 재개.
        //   시동 끄고 트렁크로 가는 시나리오: ACC OFF가 릴레이를 죽여 감지가 통째로 끊기던 것 복구.
        try { if (prefs != null && !driveMode && !sentryMode) prefs.edit()   // ★센트리는 resume 저장 안 함(복구는 SentryManager가 수행) ★주행 모드는 resume 저장 안 함 — 릴레이 재시작 시 주행 세션이 주차 중에 부활하는 것 방지(주행 재무장은 frontAutoArmTick이 담당)
                .putString("camdetect_resume", params != null ? params.toString() : "")
                .putLong("camdetect_until", System.currentTimeMillis() + durationSec * 1000L).apply(); } catch (Throwable ignored) {}
        final int myGen = ++detectGen;   // ★이 시작의 세대 — 더 새 시작이 오면 이 스레드는 폐기
        final int mySentryGen = sentryMode ? SentryManager.get().sessionGen() : -1;   // ★센트리 세션 번호 — finally의 종료 신호가 옛 세션 것이면 매니저가 무시
        detectThread = new Thread(() -> {
            Object cam = null; Class<?> ncCls = null; com.cseini.motiondetect.MotionDetector md = null;
            android.media.ImageReader dummyIr = null;   // ★백그라운드 프레임 지속용 더미 렌더 Surface
            com.overdrive.app.camera.dilink5.FastCamQuadBackend d5q = null;   // ★607 D5 프레임 소스
            final boolean d5 = DiLink5Support.isDiLink5();
            try {
                postToD1("CAMDETECT", "DIAG493 ①스레드 진입 gen=" + myGen + " avm=" + avm + " view=" + view + " bmmLoader=" + (bmmLoader != null));   // ★493 진단: hang 지점 격리
                if (relay != null) relay.putStatus("cam_state", "①진입 v" + view + " @" + (System.currentTimeMillis() % 100000));   // ★495 relay_state 진단(로그채널 우회)
                final byte[][] latest = new byte[1][]; final int[] meta = new int[2]; final int[] fc = new int[1];
                if (d5) {
                    // ★607 D5(씨라7) 감지 소스(운영자 09-27 "돌핀과 같은 것을 바라보되 소스만 다른 것") — bmmcamera(AVMCamera)가 이 ROM에 없어
                    //   D5 감지는 그동안 카메라를 못 열었다. 감지 로직(MOG2·ROI·가림·트리거)은 아래 공용 루프 그대로, 프레임만 CAMRTC와 같은
                    //   FastCamQuadBackend(4분할, 부분 합성)로 공급. ROI 편집기도 view5 4분할이라 좌표계 일치. 4분할 외 view는 D5 미지원.
                    if (view != 5) { postToD1("CAMDETECT", "D5 감지는 4분할(view5)만 지원 — view=" + view + " 시작 안 함"); return; }
                    d5q = new com.overdrive.app.camera.dilink5.FastCamQuadBackend();
                    boolean qok = d5q.start(getApplicationContext(), 15, (nv21, w, h) -> {
                        meta[0] = w; meta[1] = h; latest[0] = nv21; fc[0]++;
                        detectFrameCount++; detectLastFrameMs = System.currentTimeMillis();
                    }, (t, m) -> postToD1("CAMDETECT", m));
                    if (relay != null) relay.putStatus("cam_state", "⑤d5quad=" + qok + " v" + view + " @" + (System.currentTimeMillis() % 100000));
                    if (!qok) { postToD1("CAMDETECT", "D5 4분할 백엔드 시작 실패 — 감지 시작 못 함"); return; }
                    if (myGen != detectGen) { postToD1("CAMDETECT", "이중시작 폐기(gen " + myGen + "→" + detectGen + ")"); return; }
                } else {
                if (bmmLoader == null) {
                    java.io.File jar = new java.io.File("/system/framework/bmmcamera.jar");
                    postToD1("CAMDETECT", "DIAG493 ②bmm jar canRead=" + jar.canRead());
                    bmmLoader = new dalvik.system.DexClassLoader(jar.getPath(), getCodeCacheDir().getPath(), "/system/lib64:/system/lib", getClassLoader());
                }
                try { Class.forName("android.hardware.JNILoader", true, bmmLoader).getMethod("load").invoke(null); postToD1("CAMDETECT", "DIAG493 ③JNI로드 OK"); } catch (Throwable ig) { postToD1("CAMDETECT", "DIAG493 ③JNI로드 예외: " + ig); }
                String clsName = avm ? "android.hardware.AVMCamera" : "android.hardware.NormalCamera";
                ncCls = Class.forName(clsName, true, bmmLoader);
                Class<?> ipcCls = Class.forName(clsName + "$IPreviewCallback", true, bmmLoader);
                postToD1("CAMDETECT", "DIAG493 ④클래스로드 OK → openAvmCam 호출");
                cam = openAvmCam(ncCls, avm ? 1 : 0, avm, "CAMDETECT");
                postToD1("CAMDETECT", "DIAG493 ⑤openAvmCam 반환 cam=" + (cam != null));
                if (relay != null) relay.putStatus("cam_state", "⑤openAvm=" + (cam != null) + " v" + view + " @" + (System.currentTimeMillis() % 100000));   // ★495 relay_state 진단
                if (cam == null) { postToD1("CAMDETECT", "카메라 열기 실패(cam=null) avm=" + avm + " view=" + view + " — 감지 시작 못 함"); return; }
                if (myGen != detectGen) { postToD1("CAMDETECT", "이중시작 폐기(gen " + myGen + "→" + detectGen + ")"); return; }   // ★더 새 시작이 왔으면 이 스레드 폐기(카메라 충돌·2프레임 stall 방지)
                // ★백그라운드 프레임 지속 핵심 — 렌더 대상 Surface가 없으면 프레임워크가 ~89프레임 후 공급정지(백그라운드 Service, 화면 없음).
                //   더미 off-screen Surface(ImageReader, GL/EGL 불필요)를 addPreviewSurface로 붙여 렌더대상 확보 → 주차 상시 감지. (2026-08-26 규명)
                try {
                    int dw = view == 5 ? 2560 : (view == 0 ? 5120 : 1280);   // ★더미 Surface 크기를 뷰에 맞춤(쿼드2560x1920·파노5120x960·단일1280x960) — 크기불일치 거부 방지
                    int dh = view == 5 ? 1920 : 960;
                    dummyIr = android.media.ImageReader.newInstance(dw, dh, android.graphics.ImageFormat.YUV_420_888, 2);
                    // ★시그니처 분기(2026-09-09): AVMCamera.addPreviewSurface(Surface,int) vs NormalCamera.addPreviewSurface(Surface) — 인자 개수가 다름.
                    //   전방 알림(view=-1=NormalCamera)서 2인자로 호출해 NoSuchMethodException→더미 Surface 미부착→~89프레임 후 프레임 정지(전방 감지 死). bmmcamera.jar 디컴파일로 확정.
                    if (avm) ncCls.getMethod("addPreviewSurface", android.view.Surface.class, int.class).invoke(cam, dummyIr.getSurface(), 0);
                    else ncCls.getMethod("addPreviewSurface", android.view.Surface.class).invoke(cam, dummyIr.getSurface());
                    postToD1("CAMDETECT", "더미 프리뷰 Surface 부착 OK (addPreviewSurface" + (avm ? ",0" : "") + ") — 백그라운드 프레임 지속");
                } catch (Throwable t) {
                    postToD1("CAMDETECT", "addPreviewSurface 실패(" + t.getClass().getSimpleName() + ":" + t.getMessage() + ") — 인자/메서드 실차확인 필요");
                }
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(bmmLoader, new Class[]{ipcCls}, (pr, m, a) -> {
                    if ("onPreview".equals(m.getName()) && a != null && a.length >= 6) {
                        try {
                            int size = (int) a[5]; byte[] src = (byte[]) a[1]; byte[] c = new byte[size];
                            System.arraycopy(src, 0, c, 0, Math.min(src.length, size));
                            meta[0] = (int) a[2]; meta[1] = (int) a[3]; latest[0] = c; fc[0]++;
                            if (sentryMode || sentryTapsDetect) { sentryTapW = (int) a[2]; sentryTapH = (int) a[3]; sentryTapFrame = c; sentryTapMs = System.currentTimeMillis(); }   // ★791 실시간 보기와 공유
                            detectFrameCount++;   // ★CAMDUALPROBE 관측용 누계(2026-09-17)
                            detectLastFrameMs = System.currentTimeMillis();   // ★프레임 수신 생존신호(좀비 감지용)
                        } catch (Throwable ig) {}
                    }
                    Class<?> rt = m.getReturnType(); return rt == boolean.class ? Boolean.FALSE : (rt == int.class ? Integer.valueOf(0) : null);
                });
                ncCls.getMethod("setPreviewCallback", ipcCls).invoke(cam, proxy);
                if (avm) ncCls.getMethod("enablePreviewCallback", int.class).invoke(cam, view);
                else ncCls.getMethod("enablePreviewCallback").invoke(cam);
                ncCls.getMethod("startPreview").invoke(cam);
                }
                postToD1("CAMDETECT", "감지 시작 d5=" + d5 + " avm=" + avm + " view=" + view + " rois=" + rois.size() + " lr=" + lr + " vt=" + vt + " skip=" + skip);
                final long startTs = System.currentTimeMillis(); detectLastFrameMs = startTs;   // ★첫프레임 워치독 기준
                final int[] lastTrig = new int[16]; final long[] lastPush = new long[16];
                long end = sentryMode ? Long.MAX_VALUE : System.currentTimeMillis() + durationSec * 1000L; byte[] last = null;
                final int[] peak = new int[1]; long lastDiag = System.currentTimeMillis(); int procN = 0;
                int prevMeanY = -1; long lastLightLog = 0, lightQuietUntil = 0;   // ★713/710 조명 변화 거르기
                // ★가림 감지 트렁크(2026-09-11 운영자 — 킥 폐기) — 다리로 후방 카메라를 덮는 동작 감지. 객체 모델 불필요(밝기 분산 기반).
                //   트렁크(role2) ROI의 Y(밝기) 분산이 평소 대비 급락(균일=렌즈 밀착) 후 지속 → 치움(복귀) 왕복이면 오픈. 모델(KickDetector)은 주차 경로에서 완전 제거.
                int[] trunkBbox = null; boolean trunkBboxTried = false;
                TrunkCover trunkCover = new TrunkCover();
                final String stag = avm ? ("a" + view) : ("c" + view); final int scam = avm ? 1 : 0;   // ★스트림 태그(쿼드=a5)
                // ★더미 Surface(addPreviewSurface, 위)로 백그라운드 프레임 지속 확보 — 프리뷰 재시작(stop/start) 폴백은 무효라 제거(2026-08-26).
                while (detectActive && myGen == detectGen && System.currentTimeMillis() < end) {
                    // ★첫 프레임 워치독 — startPreview 후 8초 안에 프레임 0이면 카메라 프레임이 안 오는 것(HAL 死). 세션 종료 → finally가 플래그 정리 → 다음 주행/폴에서 재시도.
                    if (fc[0] == 0 && System.currentTimeMillis() - startTs > (d5 ? 15000 : 8000)) {   // ★607 D5 4분할은 부분 합성 대기(3초)가 있어 여유
                        if (!avm && clusterCam.active) markDualFail("전방 대시캠 첫 프레임 없음(클러스터 동시)");   // ★626
                        postToD1("CAMDETECT", "첫 프레임 " + (d5 ? 15 : 8) + "초 미수신 — 카메라 프레임 死, 세션 종료 view=" + view);
                        break;
                    }
                    if (!sentryMode && System.currentTimeMillis() - lastDiag > 4000) {
                        byte[] cf = latest[0];
                        postToD1("CAMDETECT", "view=" + view + " 프레임=" + fc[0] + " peak_mot=" + peak[0] + "% size=" + (cf != null ? cf.length : 0) + " (" + meta[0] + "x" + meta[1] + ")");
                        lastDiag = System.currentTimeMillis(); peak[0] = 0;
                    }
                    byte[] f = latest[0];
                    if (f != null && f != last && meta[0] > 0) {
                        last = f;
                        procN++;
                        if (sentryTapsDetect && !sentryMode) SentryManager.get().onFrame(f, meta[0], meta[1], android.os.SystemClock.elapsedRealtime());   // ★792 센트리와 프레임 공유(읽기 전용)
                        if (sentryMode) {   // ★센트리 — 매 프레임 onFrame만(skip 스로틀 앞). md·트렁크 가림·조명·notify·stream 건너뜀. f는 매 프레임 새 배열이라 큐에 그대로 넘겨도 안전
                            SentryManager.get().onFrame(f, meta[0], meta[1], android.os.SystemClock.elapsedRealtime());
                            try { Thread.sleep(20); } catch (InterruptedException ig) { break; }
                            continue;
                        }
                        if (driveMode) videoDiagFeed(f, meta);   // ★669 정차 직전 영상 모으기(운영자판만)
                        if (procN % skip != 0) {   // ★skip 프레임 스로틀(배경 천천히 적응)
                            if (driveMode && flipFastUntilMs > 0 && System.currentTimeMillis() < flipFastUntilMs && frontStoppedSince > 0 && flipLastR4 != null && flipLastMode != 0 && !frontFlipFiredEp && !flipFailedEp) {
                                try { frontFlipTick(f, meta, flipLastR4, System.currentTimeMillis(), flipLastMode, flipLastSound, flipLastTrafficIdx, flipLastVol); } catch (Throwable ignoreFast) {}   // ★667 빠른 구간은 건너뛰던 프레임에서도 FLIP 판정
                            }
                            try { Thread.sleep(66); } catch (InterruptedException ig) { break; } continue;
                        }
                        if (md == null) { md = new com.cseini.motiondetect.MotionDetector(meta[0], meta[1]); md.setRois(rois); md.setParams(lr, vt, bgr); }   // ★byd-motiontest처럼 setParams(민감도)
                        // ★트렁크(role2) 픽셀 bbox 1회 계산 — 가림 감지용(모델 없음). 정규화 union×meta.
                        if (!trunkBboxTried) {
                            trunkBboxTried = true;
                            float mnx2 = 1f, mny2 = 1f, mxx2 = 0f, mxy2 = 0f; boolean has2 = false;
                            for (com.cseini.motiondetect.Roi rr : rois)
                                if (rr.id == 2) { has2 = true; for (int k = 0; k < rr.nx.length; k++) { mnx2 = Math.min(mnx2, rr.nx[k]); mxx2 = Math.max(mxx2, rr.nx[k]); mny2 = Math.min(mny2, rr.ny[k]); mxy2 = Math.max(mxy2, rr.ny[k]); } }
                            if (has2) { trunkBbox = new int[]{(int)(mnx2*meta[0]), (int)(mny2*meta[1]), (int)((mxx2-mnx2)*meta[0]), (int)((mxy2-mny2)*meta[1])};
                                postToD1("CAMDETECT", "가림감지 trunkROI=[" + trunkBbox[0] + "," + trunkBbox[1] + "," + trunkBbox[2] + "," + trunkBbox[3] + "]"); }
                        }
                        // ★713/710 조명 변화 거르기(운영자 10-03 "하차할 때마다 작업영역 보류, 그때 난 카메라 밖") — 화면 전체 평균 밝기가 한 번에 12단계 이상 바뀌면
                        //   (차 조명 꺼짐·켜짐) 움직임이 아니라 조명으로 보고 배경을 다시 잡는다. 13:11:37 실측: 문 닫고 11초 뒤 4초 한 구간만 49%(앞뒤 0%·3%).
                        //   걸어가는 사람은 화면 전체 평균을 이만큼 못 바꾼다. 주차 감지만(주행 모드는 앞차·신호 판정이 따로 있음).
                        if (!driveMode) {
                            long ysum = 0; int yn = 0, yLen = Math.min(f.length, meta[0] * meta[1]);
                            for (int k = 0; k < yLen; k += 64) { ysum += (f[k] & 0xFF); yn++; }
                            int meanY = yn > 0 ? (int) (ysum / yn) : 0;
                            if (prevMeanY >= 0 && Math.abs(meanY - prevMeanY) >= 12) {
                                md.close(); md = new com.cseini.motiondetect.MotionDetector(meta[0], meta[1]); md.setRois(rois); md.setParams(lr, vt, bgr);
                                lightQuietUntil = System.currentTimeMillis() + 1500;
                                if (System.currentTimeMillis() - lastLightLog > 10000) { lastLightLog = System.currentTimeMillis(); postToD1("CAMDETECT", "조명 변화(평균 밝기 " + prevMeanY + "→" + meanY + ") — 움직임 알림 안 보내고 배경 다시 잡음"); }
                            }
                            prevMeanY = meanY;
                        }
                        com.cseini.motiondetect.MotionDetector.Result[] rs = md.onFrame(f, android.os.SystemClock.elapsedRealtime());
                        // ★713/710 감지 시작 5초(노출 맞추는 중, 13:10:48 실측 77%)와 조명 변화 직후 1.5초는 알림 안 보냄. 이때 lastTrig를 안 바꿔 두므로
                        //   진짜 사람이 계속 있으면 이 구간이 끝나자마자 엣지로 알림이 나간다.
                        final boolean motQuiet = !driveMode && (System.currentTimeMillis() - startTs < 5000 || System.currentTimeMillis() < lightQuietUntil);
                        // ★같은 role(id)의 ROI가 여러 개면(복제 사분면) role별 최대치로 집계 — last-wins로 0% 덮어쓰기·push 엣지 꼬임 방지
                        java.util.HashMap<Integer, int[]> agg = new java.util.HashMap<>();  // id -> {maxPct, trig}
                        for (com.cseini.motiondetect.MotionDetector.Result res : rs) {
                            int p = Math.round(res.motionPct * 100);
                            int[] cur = agg.get(res.roiId);
                            if (cur == null) agg.put(res.roiId, new int[]{p, res.triggered ? 1 : 0});
                            else { if (p > cur[0]) cur[0] = p; if (res.triggered) cur[1] = 1; }
                        }
                        for (java.util.Map.Entry<Integer, int[]> e : agg.entrySet()) {
                            int id = e.getKey(), pct = e.getValue()[0], trig = e.getValue()[1];
                            relay.putState("mot" + id, trig);
                            relay.putState("mot" + id + "_p", pct);
                            if (pct > peak[0]) peak[0] = pct;   // ★모든 role 중 최대(구: id==0만 → 트렁크 전용감지서 항상 0% 오표기 버그)
                            if (notify && id >= 0 && id < 3 && !motQuiet) {   // ★role<3(접근/작업/트렁크)만 워커 push — role≥3(앞차/신호)은 주행 로컬 사운드라 push 안 함. 0→1 엣지 + 8초 쿨다운.
                                // ★2026-09-11: 접근/작업영역 사람게이트(객체 모델) 제거 — 로그상 사람점수 0.00~0.08로 실효 0(접근은 워치키 GATT가 담당, 작업영역 보류는 한 번도 발동 안 함). 단순 MOG2 엣지 push만.
                                boolean edge = trig == 1 && lastTrig[id] == 0;
                                // ★646 접근·작업영역(0·1)은 주사용권 폰이 차 근처일 때만 폰에 알림(운영자 09-29 "아주 멀리 왔는데 계속 접근영역 로그") —
                                //   멀리 있을 때 카메라 움직임은 쓸 데가 없고(해제는 워치키 근접 확증 필요) 폰만 계속 깨운다.
                                boolean nearOk = id > 1 || prefs == null || !prefs.getBoolean("near_proto", false) || phoneNearFlag;
                                if (edge && !nearOk) { if (System.currentTimeMillis() - lastPush[id] > 600_000) { lastPush[id] = System.currentTimeMillis(); postToD1("CAMDETECT", "영역" + id + " 움직임 " + pct + "% — 폰이 차 근처 아님, 알림 안 보냄"); } }
                                else if (edge && System.currentTimeMillis() - lastPush[id] > 8000) {
                                    lastPush[id] = System.currentTimeMillis();
                                    if (relay != null) relay.postCarEvent("motion:" + id + ":" + pct);
                                }
                                lastTrig[id] = trig;
                            }
                        }
                        // ★전방 대시캠 주행 모드(앞차출발 role3·신호변경 role4) — 헤드유닛 로컬 사운드.
                        if (driveMode) handleFrontAlert(agg, rois, f, meta);
                        // ★가림 감지 트렁크(2026-09-11, 킥 대체) — 다리로 후방 카메라 렌즈를 덮는 동작. 모델 없이 Y 분산(균일도)만으로 판정.
                        //   렌즈 밀착=화면이 어둡고 균일(분산 급락). 덮음 유지(≥coverHold) 후 치움(복귀) 왕복이면 오픈. 지나가는 사람/그림자/조명은 화면이 균일해지지 않아 안 걸림.
                        if (trunkBbox != null && !driveMode) { int[] m2 = agg.get(2); trunkCover.step(f, meta[0], meta[1], trunkBbox, notify, m2 != null ? m2[0] : 0, "CAMDETECT"); }
                        if (stream) {   // ★감지한 그 프레임을 워커에 JPEG로 → 폰 /cam이 라이브로 봄(테스트/튜닝, 한 카메라)
                            try {
                                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                                new android.graphics.YuvImage(f, android.graphics.ImageFormat.NV21, meta[0], meta[1], null).compressToJpeg(new android.graphics.Rect(0, 0, meta[0], meta[1]), sq, bo);
                                postFrameToWorker(bo.toByteArray(), scam, stag);
                            } catch (Throwable ig) {}
                        }
                    }
                    try { Thread.sleep(sentryMode ? 20 : 66); } catch (InterruptedException ig) { break; }   // ~15fps
                }
            } catch (Throwable t) {
                postToD1("CAMDETECT", "예외: " + t.getClass().getSimpleName() + ":" + t.getMessage());
            } finally {
                try { if (cam != null && ncCls != null) { ncCls.getMethod("stopPreview").invoke(cam); ncCls.getMethod("close").invoke(cam); } } catch (Throwable ig) {}
                try { if (d5q != null) d5q.stop(); } catch (Throwable ig) {}   // ★607 D5 4분할 소스 정리
                try { if (dummyIr != null) dummyIr.close(); } catch (Throwable ig) {}   // ★더미 프리뷰 Surface 정리
                if (md != null) md.close();
                if (frontKd != null) { try { frontKd.close(); } catch (Throwable ig) {} frontKd = null; }   // ★전방 객체인식 Interpreter 해제(다음 세션 재init)
                if (myGen == detectGen && flipDet != null) { flipDet.release(); flipDet = null; flipRgb = null; }   // ★FLIP 링(약 30MB) 해제 — 정차 중 감지가 끝나도 남지 않게
                if (myGen == detectGen) {   // ★더 새 세션이 시작됐으면(join 1.5s 초과로 겹침) 그 세션 플래그를 지우지 않음
                    detectActive = false; if (!sentryMode) RelayClient.cameraActive = false; detectDriveMode = false;
                    if (sentryMode) detectSentryMode = false;
                    else {
                        if (sentryTapsDetect) { sentryTapsDetect = false; SentryManager.get().onSessionEnded("공유하던 주차 감지가 끝남"); }   // ★792 프레임을 나눠 받던 센트리는 끊기고, 아래 재무장 평가가 새로 연다
                        SentryManager.get().reevaluate();   // ★비센트리 세션이 끝났으니 센트리 재무장 평가(센트리 스레드는 부르지 않음 — 재무장 폭주 방지)
                    }
                }
                if (sentryMode) SentryManager.get().onSessionEnded("다른 카메라 기능이 사용 중", mySentryGen);
                if (sentryMode && myGen != detectGen) SentryManager.get().reevaluate();   // ★792 다른 감지가 이 센트리 세션을 밀어냈으면 곧바로 재무장 평가 — 그 감지 프레임을 나눠 받아 이어 간다(1분 틱을 기다리지 않음)
            }
        });
        detectThread.setName("cam-detect"); detectThread.start();
        return "감지 시작 rois=" + rois.size();
    }

    // ★가림 감지용 — NV21 Y(밝기) 평면에서 트렁크 bbox 영역의 평균·분산. 속도 위해 가로/세로 8px 간격 샘플링(최대 ~수백 점).
    //   렌즈가 다리로 덮이면 이 영역이 균일해져 분산이 급락한다(자연 장면=고분산).
    // ★638 가림 트렁크 판정 — 주차 감지(CAMDETECT)와 테스트 화면(camRtc)이 같은 판정을 쓰도록 분리(판정식은 기존 그대로).
    //   렌즈 밀착=화면이 균일(분산 급락) 또는 한 밝기대가 화면을 채움(dom). 덮음 유지(≥cover_hold_ms) 후 치움 = 트리거, cover_max_ms 넘게 덮이면 취소.
    private final class TrunkCover {
        double baseMean = -1, baseVar = -1, baseDom = -1; long start = 0, lastTrig = 0, lastFailLog = 0; boolean armed = false; int epLogs = 0;
        void step(byte[] f, int w, int h, int[] bbox, boolean notify, int motPct, String cat) {
            if (w <= 0 || h <= 0 || f == null) return;
            long now = System.currentTimeMillis();
            double[] mv = lumaMeanVar(f, w, h, bbox);
            if (mv[3] <= 0) return;   // ★638 빈 프레임(샘플 0) — 가림으로 치지 않고 상태도 건드리지 않음(09-28 var=0 dom=0% 오진 5건)
            double mean = mv[0], var = mv[1], domRatio = mv[2];
            boolean lowVar = baseVar > 0 && var < baseVar * (prefs != null ? prefs.getInt("cover_var_ratio", 30) : 30) / 100.0
                    && var < (prefs != null ? prefs.getInt("cover_var_floor", 350) : 350);
            boolean highDom = baseDom > 0 && baseVar > 0
                    && domRatio > baseDom + (prefs != null ? prefs.getInt("cover_dom_rise", 18) : 18) / 100.0
                    && domRatio > (prefs != null ? prefs.getInt("cover_dom_floor", 88) : 88) / 100.0
                    && var < baseVar * (prefs != null ? prefs.getInt("cover_dom_var_pct", 75) : 75) / 100.0;
            boolean coveredNow = lowVar || highDom;
            if (baseVar < 0) { baseMean = mean; baseVar = var; baseDom = domRatio; }
            else if (!coveredNow) { baseMean = baseMean * 0.97 + mean * 0.03; baseVar = baseVar * 0.97 + var * 0.03; baseDom = baseDom * 0.97 + domRatio * 0.03; }
            int coverHold = prefs != null ? prefs.getInt("cover_hold_ms", 400) : 400;
            int coverMax = prefs != null ? prefs.getInt("cover_max_ms", 6000) : 6000;
            String vals = "var=" + (int) var + " base=" + (int) baseVar + " dom=" + (int) (domRatio * 100) + "% domBase=" + (int) (baseDom * 100) + "% mean=" + (int) mean + " n=" + (int) mv[3] + " roi=" + bbox[2] + "x" + bbox[3];
            if (coveredNow) {
                if (start == 0) { start = now; epLogs = 0; }
                if (epLogs < 10) { epLogs++; postToD1(cat, "가림후보 +" + (now - start) + "ms " + vals + " (lowVar=" + lowVar + " highDom=" + highDom + ")"); }   // ★638 에피소드당 최대 10줄
                if (!armed && now - start >= coverHold) { armed = true; postToD1(cat, "가림 감지 — 덮음 확정(" + vals + ") 치우면 오픈"); }
                if (now - start > coverMax) { start = 0; armed = false; postToD1(cat, "가림 취소 — " + coverMax + "ms 넘게 덮임"); }
            } else {
                if (armed) {
                    Object _dt = relay != null ? relay.getStatus("door_trunk") : null, _tc = relay != null ? relay.getStatus("trunk_change_ts") : null;
                    boolean _tOpen = _dt instanceof Integer && (Integer) _dt == 1, _tJustClosed = !_tOpen && _tc instanceof Long && now - (Long) _tc < 30000;
                    if (_tOpen || _tJustClosed) { postToD1(cat, "가림 트리거 억제 — " + (_tOpen ? "트렁크 열린 상태" : "닫힘 30s 이내")); }
                    else if (now - lastTrig > 5000) {
                        lastTrig = now;
                        if (notify && autoAllowed() && relay != null) { relay.bumpTrunkTrigger(); beaconWatchTick(); relay.postCarEvent("kick:2"); }
                        if (relay != null) relay.putState("cover2_trig", now);
                        postToD1(cat, "★트렁크 트리거(가림) " + vals + (notify ? " → kick:2 발송" : " (테스트-표시만)"));
                    }
                } else if (start == 0 && motPct >= 40 && now - lastFailLog > 3000) {   // ★638 큰 움직임인데 덮음 후보 아님 — 가렸는데 안 걸린 경우를 바로 가리기 위함
                    lastFailLog = now; postToD1(cat, "가림 미인식(움직임 " + motPct + "%) " + vals);
                }
                start = 0; armed = false;
            }
            if (relay != null) { relay.putState("cover2", coveredNow ? 1 : 0); relay.putState("cover2_v", (int) var); relay.putState("cover2_b", (int) baseVar); relay.putState("cover2_dom", (int) (domRatio * 100)); relay.putState("cover2_domb", (int) (baseDom * 100)); relay.putState("cover2_armed", armed ? 1 : 0); }
        }
    }
    private double[] lumaMeanVar(byte[] nv21, int w, int h, int[] bbox) {
        int x0 = Math.max(0, bbox[0]), y0 = Math.max(0, bbox[1]);
        int x1 = Math.min(w, bbox[0] + bbox[2]), y1 = Math.min(h, bbox[1] + bbox[3]);
        if (x1 <= x0 || y1 <= y0) { x0 = 0; y0 = 0; x1 = w; y1 = h; }
        int stepX = Math.max(1, (x1 - x0) / 48), stepY = Math.max(1, (y1 - y0) / 48);
        long sum = 0, sumSq = 0; int n = 0;
        int[] hist = new int[16];   // ★Y 히스토그램(16 bins) — 지배 밝기 비율(화면 큰 부분이 같은 색으로 채워짐 감지)
        for (int y = y0; y < y1; y += stepY) {
            int row = y * w;
            for (int x = x0; x < x1; x += stepX) {
                int v = nv21[row + x] & 0xff;   // Y 평면(선두 w*h 바이트)
                sum += v; sumSq += (long) v * v; n++;
                hist[v >> 4]++;
            }
        }
        if (n == 0) return new double[]{0, 0, 0, 0};
        double mean = (double) sum / n;
        double var = (double) sumSq / n - mean * mean;
        // 지배 밝기 비율: 인접 3bin 창의 최대 합 / n (다리·신발·손 무엇이든 렌즈 앞 크게 오면 한 밝기대가 화면을 채움)
        int best = 0;
        for (int i = 0; i < 16; i++) {
            int s = hist[i] + (i > 0 ? hist[i - 1] : 0) + (i < 15 ? hist[i + 1] : 0);
            if (s > best) best = s;
        }
        double domRatio = (double) best / n;
        return new double[]{mean, var < 0 ? 0 : var, domRatio, n};
    }

    // ★전방 대시캠 주행 알림 — 앞차 출발(role3) / 신호 변경(role4). 헤드유닛 로컬 사운드(playIccSound), 워커 push 없음.
    //   객체인식(EfficientDet, 2026-09-08): 앞차=차량(car/bus/truck) 감지를 움직임 트리거의 게이트로(그림자·보행자 오탐 차단),
    //   신호등=신호등 객체 bbox에서 색 판독(고정 ROI보다 정확). 미준비/미검출 시 기존 움직임·색 폴백(무회귀). 정차 중에만 추론(연산 절약).
    private long frontStoppedSince = 0, frontLeadAlert = 0, frontTrafficAlert = 0, frontLeadVehTs = 0, frontObjLastMs = 0;
    // ★앞차 bbox 방향성 상태머신(2026-09-10) — 무장시각(정지한 앞차 확인 시작)/최근검출시각/기준cy·h(정지 중 가장 가까운=하단/큰 위치)/최근 bbox(cy,h)/최근정차시각(출발 유예창)/진단로그 스로틀. ROI정규화 cy 1=하단(가까움).
    private long frontLeadArmedTs = 0, frontLeadSeenTs = 0, frontLastStoppedTs = 0, frontLeadDiagMs = 0, frontTrafDiagMs = 0; private float frontLeadBaseCy = -1, frontLeadBaseH = -1, frontLeadCy = -1, frontLeadH = -1;
    // ★가로 위치(cx) 추적(2026-09-11 운영자 "앞차는 가만히 있는데 옆 차가 가니 앞차 출발이 나온다") —
    //   analyzeClass는 ROI 안에서 '가장 확실한 차' 하나만 돌려준다. 옆 차선 차가 ROI에 들어와 더 크게 잡히면
    //   추적 대상이 소리 없이 그 차로 바뀌고, bbox가 튀면서 '멀어짐'으로 오판했다. cx로 동일 차량인지 가른다.
    private float frontLeadBaseCx = -1, frontLeadCx = -1;
    // ★섀도 판정기(FrontAlertTracker) 계측 상태 — 소리는 옛 로직, 새 판정기는 로그만(2026-09-11). 정차 세션 간 인스턴스 유지.
    private FrontAlertTracker frontShadow;
    private float[][] frontShadowVehs;
    private com.cseini.motiondetect.PlateTracker.Plate frontShadowPlate;
    private float frontShadowLt = -1, frontShadowAsp = 0;
    private final StringBuilder frontTrace = new StringBuilder();
    private long frontTraceFlushMs = 0;
    private int frontTrafTile = 0;   // ★신호등 타일 라운드로빈 인덱스(2026-09-10) — 큰 ROI를 겹치는 3타일로 쪼개 틱당 1개 검사, 위치 무관 신호등 검출(작게 그릴 수 없으니 시스템이 zoom).
    private int frontLeadRecedeN = 0;   // ★앞차 멀어짐 지속 카운터(2026-09-10 v3) — bbox cy/h 노이즈 방어: 2틱 지속 멀어져야 출발 발화(모션게이트 대체, 진단서 명백한 멀어짐이 mot=1%로 막히던 것 근본수정).
    // ★전방 알림 진단(2026-09-09 운영자 "1번 고" — 실주행 프레임 저장해 튜닝) — 정차 프레임을 ROI·감지값 오버레이해 서버 저장.
    private long frontDiagLastMs = 0;
    private volatile float frontDiagVeh = -1, frontDiagLt = -1;
    private volatile int frontDiagMot3 = -1;
    private volatile float[] frontDiagRg = null;
    // ★타일별 적색이력·녹색기준선(2026-09-11 실차 오알림) — 공유 상태였을 땐 가운데 타일의 앞차 브레이크등(r=0.02)이 이력을 만들고
    //   오른쪽 타일의 초록 버스(g=0.09)가 "적→녹"으로 잡혀 5초마다 울렸다. 적색을 본 **같은 타일**에서만 녹색 전환을 인정.
    private final boolean[] frontLastRed = new boolean[3];
    private final float[] frontTrafGreenBase = {1f, 1f, 1f};   // ★적색 구간 g 바닥값 — 녹색 전환 기준선(배경 초록 상쇄)
    // ★LR(lockred, 2026-09-28 운영자 승인 오프라인 실험 이식) — 신호등 "새 방식"(객체인식 없이 정차 첫 3장 색 잠금 후
    //   잔존율+새 초록 발화). 기존 앵커 경로와 나란히 돌되, 정차 구간당 먼저 온 쪽만 울린다(frontTrafficFiredEp 공유).
    private TrafficLockRed lockRed;
    private boolean lrFreshStop = false;
    private boolean frontTrafficFiredEp = false;
    private long frontAnchorMuteMs = 0;
    private int lrAttempts = 0; private long lrLastAttempt = 0;   // ★645 정차 중 재고정 횟수·시각
    // ★FLIP(2026-09-30 운영자 승인 (가), 전체피드백로그분석 스펙) — 신호등 새 방식. 최근 스냅샷 6장 중앙값 대비 "왼쪽 빨강 소멸 + 바로 오른쪽 초록 신규"를 발화.
    //   flipMode 0=끔 / 1=기록만 / 2=소리(1920x1080만). 옛 방식(앵커·LR) 소리는 flipMode 2일 때만 끈다(판정·로그는 유지).
    //   기본값: 운영자판·공유판 모두 2(운영자 10-01 "공유판에도 배포" — 앞 카메라(대시캠) 프레임이 있고 1920x1080일 때만 실제로 소리). 저장값이 있으면 그 값이 우선.
    private static final int FLIP_MODE_DEFAULT = 2;
    private TrafficFlipDetector flipDet;
    private byte[] flipRgb;   // 틱마다 크롭 RGB를 새로 만들지 않도록 재사용(검출기는 반절 해상도로 즉시 줄여 원본을 보관하지 않는다)
    private boolean frontFlipFiredEp = false, flipFailedEp = false;
    private long flipLogMs = 0, flipTickNs = 0, flipErrLogMs = 0, flipNextMs = 0;
    // ★667 후보가 나타난 순간만 빠르게 판정 — 평소엔 3프레임에 1장(초당 1~2번)이지만, 점수가 문턱의 절반을 넘는 후보가 보이면 3초 동안 프레임마다 판정(초당 4~5번)한다.
    //   건너뛰던 프레임에서도 틱을 돌리려고 마지막 호출 인자를 기억해 둔다. 이 구간의 '연속' 조건은 3틱(약 0.6초).
    private volatile long flipFastUntilMs = 0;
    private long flipPrewarmMs = 0;
    // ★689 앞차 인지 신호 관찰(운영자 10-02 "acc 안 켜도 앞차 인지는 계기판에서 되더라") — 동작 변경 없음. 저속(≤2km/h·D단) 동안 1초마다 계기판 ACC 게터를 읽어 '바뀐 값만' 기록.
    //   앞차 있는 정차 vs 첫 줄 정차를 사후 비교하려는 자료. 구간당 40줄 상한. 카메라 차량 수(veh)·ACC_MODE 이벤트값·속도를 같이 찍는다.
    private long accDiagMs = 0, accDiagSegStartMs = 0;
    private int accDiagLines = 0;
    private String accDiagPrev = null;
    private java.util.List<String> accDiagNames = null;
    // ★669 정차 영상 자료(운영자 10-01 "스틸샷이 아닌 영상으로 판단이 정확하고 빠르지 않나") — 저속(≤6km/h·D단) 동안 초당 5장(0.2초)을 위쪽 62%만 JPEG로 링에 들고 있다가,
    //   정차(저속 8초 이상)가 끝나 속도가 6을 넘으면 최근 15초를 서버에 올린다(pulled/vid_<정차시작>_<번호>_<시각>.jpg). 오프라인에서 위치 잠금+영상 추적을 시험할 자료.
    //   운영자판만, 하루 25구간까지, 업로드는 백그라운드·0.15초 간격(다른 전송 방해 최소).
    private final java.util.ArrayDeque<Object[]> vidRing = new java.util.ArrayDeque<>();   // {Long ts, byte[] jpeg}
    private long vidLastMs = 0, vidSlowSince = 0;
    private final java.util.concurrent.atomic.AtomicBoolean vidUploading = new java.util.concurrent.atomic.AtomicBoolean(false);
    private int vidBatchesToday = 0; private long vidDayKey = 0;
    private void videoDiagFeed(byte[] f, int[] meta) {
        if (!BuildConfig.IS_OPERATOR || prefs == null || !prefs.getBoolean("video_diag", true)) return;
        final long now = System.currentTimeMillis();
        final boolean slow = !Double.isNaN(lastSpeed) && lastSpeed <= 6 && lastGearVal == 2;
        if (!slow) {
            if (vidSlowSince > 0 && now - vidSlowSince >= 8000 && vidRing.size() >= 20) videoDiagFlush(now);
            vidSlowSince = 0; vidRing.clear();
            return;
        }
        if (vidSlowSince == 0) vidSlowSince = now;
        if (now - vidLastMs < 200) return;
        vidLastMs = now;
        try {
            final int w = meta[0], h = meta[1];
            final int hh = Math.min(h, Math.round(h * 0.62f)) & ~1;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(80_000);
            new android.graphics.YuvImage(f, android.graphics.ImageFormat.NV21, w, h, null).compressToJpeg(new android.graphics.Rect(0, 0, w, hh), 55, bo);
            vidRing.addLast(new Object[]{now, bo.toByteArray()});
            while (vidRing.size() > 75) vidRing.removeFirst();   // 15초
        } catch (Throwable ignored) {}
    }
    private void videoDiagFlush(final long now) {
        final long day = now / 86_400_000L;
        if (day != vidDayKey) { vidDayKey = day; vidBatchesToday = 0; }
        if (vidBatchesToday >= 25 || !vidUploading.compareAndSet(false, true)) { vidRing.clear(); return; }
        vidBatchesToday++;
        final java.util.ArrayList<Object[]> copy = new java.util.ArrayList<>(vidRing);
        final long stopStart = vidSlowSince;
        final String flipInfo = "flipFire=" + (flipFireAtMs > 0 ? (flipFireAtMs - stopStart) : -1) + "ms flipBest=" + Math.round(flipBest);
        postToD1("VIDEODIAG", "정차 영상 업로드 시작 프레임 " + copy.size() + " 저속 " + ((now - stopStart) / 1000) + "초 " + flipInfo);
        new Thread(() -> {
            int ok = 0;
            try {
                for (int i = 0; i < copy.size(); i++) {
                    long ts = (Long) copy.get(i)[0]; byte[] jpg = (byte[]) copy.get(i)[1];
                    String nm = "vid_" + stopStart + "_" + String.format(java.util.Locale.US, "%03d", i) + "_" + ts;
                    java.io.File out = new java.io.File(getCacheDir(), nm + ".jpg");
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) { fo.write(jpg); }
                    org.json.JSONObject up = new org.json.JSONObject(); up.put("path", out.getPath()); up.put("name", nm + ".jpg");
                    if (pullFile(up, true) == null) ok++;
                    out.delete();
                    try { Thread.sleep(150); } catch (InterruptedException ie) { break; }
                }
            } catch (Throwable t) { postToD1("VIDEODIAG", "업로드 예외 " + t); }
            finally { postToD1("VIDEODIAG", "정차 영상 업로드 끝 성공 " + ok + "/" + copy.size()); vidUploading.set(false); }
        }, "viddiag").start();
        vidRing.clear();
    }
    private int[] flipLastR4; private int flipLastMode, flipLastTrafficIdx, flipLastVol; private boolean flipLastSound;
    private int flipTicks = 0, flipReadyTicks = 0, flipLastSnaps = 0, flipFailN = 0;
    private double flipBest = 0; private long flipBestMs = 0;
    private long flipStopStartMs = 0, flipBestAtMs = 0, flipFireAtMs = 0;
    private int flipCandLogs = 0, flipRefCount = 0, flipTickLogs = 0;
    private long flipTickLogMs = 0;
    private com.cseini.motiondetect.KickDetector frontKd;   // 전방 객체인식 전용(EfficientDet). 지연 init.
    // ★2026-09-17 앞차 검출률 슬라이딩 윈도우(로그분석 세션 프레임 10,746장 실측) — 시간대별 미검출률 06:30 36% · 07:30 75%(역광) ·
    //   18:30 100% · 19:30 98%(야간). "사라짐" 발화가 `미검출 0.5s`만 보고 쏘니 야간엔 상시 미검출이라 무조건 걸렸다(오발화 14건 전부
    //   18:16~19:51). 최근 20 objTick의 검출 여부를 비트로 들고, 검출률이 낮으면 "안 보이니 사라졌다"는 판정 자체를 하지 않는다.
    private int frontDetBits = 0, frontDetN = 0;   // 최근 20틱 검출 비트마스크 / 채워진 틱 수
    private long frontDetGateLogMs = 0;
    private int frontZeroSpdTicks = 0;   // ★정차 = 저속 연속 2틱(2026-09-17) — 단일 틱 ≤3km/h는 "내가 정차하는 순간"에 무장돼 접근 중 bbox를 기준으로 잡았다
    private long frontStoppedEndTs = 0;   // ★적색이력 유예창(2026-09-17) — 정차가 끊겼다 15s 안에 다시 서면(stop-and-go 창) 같은 신호로 보고 이력 유지
    private long frontOccludeTs = 0;      // ★앞차 가림(2026-09-17) — 큰 bbox(h≥0.5) 차량이 최근 검출된 시각. 그 동안은 신호등 판정 스킵(가려서 안 보임)
    // ★조건부 감마(역광 한정, 2026-09-17) — 07:32:23 역광 프레임(앞차가 바로 앞인데 veh=-1)에 감마 0.45를 걸자 지붕선·번호판·좌우 차 구분이 살아남(PIL 실증).
    //   YOLO 입력에만 쓰고 색 판독(ColorProbe.redGreen)은 반드시 원본 — 감마가 R/G 비율을 왜곡해 `r > g*2` 같은 비율 조건이 전부 틀어진다.
    //   트리거는 밝기 지표가 아니라 최근 검출률(frontGammaWanted 참조) — 밝기로는 역광/정상 분리가 안 됐음(09-17 캘리브레이션).
    private static final byte[] FRONT_GAMMA_LUT = new byte[256];
    static { for (int i = 0; i < 256; i++) FRONT_GAMMA_LUT[i] = (byte) Math.round(255.0 * Math.pow(i / 255.0, 0.45)); }
    private byte[] frontGammaBuf;
    private boolean frontGammaOn = false;
    private long frontGammaLogMs = 0;
    // ★감마 트리거 = 밝기 추정이 아니라 "최근 검출률"(2026-09-17 로그분석 캘리브레이션) — 무작위 109장 실측에서 밝기 임계(상단Y≥150·상하차≥60)는
    //   06:00~06:30만 잡고 미검출 79%인 07:30(상단 124)은 놓쳤고, 07:30(미검출 79%)과 18:00(미검출 21%)이 밝기 통계가 거의 동일(124.4 vs 124.1)해
    //   밝기로는 분리 자체가 안 됐다(임계 스윕 전부 음수 분리도). 대신 사라짐 게이트가 이미 계산하는 최근 20틱 검출률을 재사용:
    //   실제 성능 저하를 직접 관측해 반응하므로 역광·저조도·장면 특성 어느 원인이든 커버. 히스테리시스(ON<40%·OFF>60%)로 플래핑 방지.
    //   색 판독·번호판은 원본 유지라 켜져 있어도 안전. 최초 8틱 미만이면 판단 보류(OFF).
    private boolean frontGammaWanted() {
        if (frontDetN < 8) return frontGammaOn;
        float rate = Integer.bitCount(frontDetBits) / (float) Math.min(frontDetN, 20);
        boolean next = frontGammaOn ? (rate <= 0.6f) : (rate < 0.4f);
        long now = System.currentTimeMillis();
        if (next != frontGammaOn || now - frontGammaLogMs > 30000) {
            frontGammaLogMs = now;
            postToD1("FRONT", "[감마] " + (next ? "ON" : "OFF") + " 검출률=" + Math.round(rate * 100) + "%(" + frontDetN + "틱)" + (next != frontGammaOn ? " ←전환" : ""));
        }
        frontGammaOn = next;
        return next;
    }
    // Y평면에만 감마 LUT 적용한 사본(NV21 크로마는 그대로 복사). 멤버 버퍼 재사용(틱당 할당 방지).
    private byte[] frontGammaY(byte[] nv21, int w, int h) {
        int n = nv21.length, yn = w * h;
        if (frontGammaBuf == null || frontGammaBuf.length != n) frontGammaBuf = new byte[n];
        byte[] o = frontGammaBuf;
        for (int i = 0; i < yn; i++) o[i] = FRONT_GAMMA_LUT[nv21[i] & 0xFF];
        System.arraycopy(nv21, yn, o, yn, n - yn);
        return o;
    }
    // ★FLIP — 정차 시작: 링·카운터·자기 플래그 초기화(정차 시작 후 첫 판정은 스냅샷 5장이 모이는 약 14초 뒤).
    private void frontFlipStopStart(long now) {
        frontFlipFiredEp = false; flipFailedEp = false;
        flipTicks = 0; flipReadyTicks = 0; flipLastSnaps = 0; flipTickNs = 0; flipNextMs = 0;
        flipBest = 0; flipBestMs = 0; flipLogMs = 0;
        flipStopStartMs = now; flipBestAtMs = 0; flipFireAtMs = 0; flipCandLogs = 0; flipRefCount = 0; flipTickLogs = 0; flipTickLogMs = 0;
        if (flipDet != null && now - flipPrewarmMs > 6000) flipDet.reset();   // ★668 방금 미리 모은 스냅샷이 있으면 유지
    }
    private void frontFlipPrewarm(byte[] f, int[] meta, int[] r4pre, long now) {
        if (r4pre == null) return;
        try {
            final int W = meta[0], H = meta[1];
            final int ybot = Math.round(0.52f * H);
            final int x0 = r4pre[0] & ~1, y0 = r4pre[1] & ~1;
            final int w = r4pre[2] & ~1, h = (Math.min(H, ybot + 40) - y0) & ~1;
            if (ybot - y0 < 20 || w < 16 || h < 16 || x0 + w > W) return;
            byte[] rgb = flipRgb = TrafficLockRed.nv21CropToRgb(f, W, H, x0, y0, w, h, flipRgb);
            if (flipDet == null) flipDet = new TrafficFlipDetector();
            flipDet.onFrame(rgb, w, h, x0, y0, y0, ybot, now, 1.0e15, 99, 1.0e15);
        } catch (Throwable t) { /* 미리 모으기 실패는 조용히 — 정차 인정 뒤 정상 경로가 다시 시도 */ }
    }
    private void accDiagTick(long now, boolean lowSpd) {
        if (!lowSpd) { accDiagSegStartMs = 0; accDiagPrev = null; accDiagLines = 0; return; }
        if (now - accDiagMs < 1000) return;
        accDiagMs = now;
        try {
            Object dev = deviceByName("instrument");
            if (dev == null) return;
            if (accDiagNames == null) {
                java.util.List<String> ns = new java.util.ArrayList<>();
                for (java.lang.reflect.Method m : dev.getClass().getMethods()) {
                    String n = m.getName(); String nl = n.toLowerCase();
                    if (!n.startsWith("get") || m.getParameterTypes().length != 0 || m.getReturnType() != int.class) continue;
                    if (nl.contains("acc") || nl.contains("2in1") || nl.contains("cruis")) ns.add(n);
                }
                java.util.Collections.sort(ns);
                accDiagNames = ns;
            }
            if (accDiagSegStartMs == 0) { accDiagSegStartMs = now; accDiagLines = 0; accDiagPrev = null; }
            StringBuilder cur = new StringBuilder();
            for (String n : accDiagNames) cur.append(n.substring(3)).append('=').append(callGetInt0(dev, n)).append(' ');
            String curS = cur.toString();
            if (curS.equals(accDiagPrev) || accDiagLines >= 40) return;
            boolean first = accDiagPrev == null;
            accDiagPrev = curS; accDiagLines++;
            postToD1("FRONT", "[ACCDIAG] 저속 " + ((now - accDiagSegStartMs) / 1000) + "초 | spd=" + lastSpeed + " | accMode=" + lastAccValue
                    + " | veh=" + (frontShadowVehs == null ? -1 : frontShadowVehs.length) + " | " + (first ? "처음 " : "변화 ") + curS.trim());
        } catch (Throwable t) { if (accDiagLines < 40) { accDiagLines = 40; postToD1("FRONT", "[ACCDIAG] 오류 " + t.getClass().getSimpleName()); } }
    }

    // ★FLIP — 정차 종료: 요약 로그 후 링·메모리(약 30MB) 해제.
    private void frontFlipStopEnd(long now) {
        if (flipTicks > 0 || flipFailedEp) {
            final java.text.SimpleDateFormat kst = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
            kst.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));
            String why = frontFlipFiredEp ? "발화함" : flipFailedEp ? "오류로 중단" : flipReadyTicks == 0 ? "기준 부족(스냅샷 " + flipLastSnaps + "개)" : "미발화";
            postToD1("FRONT", "[FLIP] 구간 종료 — " + why
                + " / 정차 " + kst.format(new java.util.Date(flipStopStartMs)) + "~" + kst.format(new java.util.Date(now)) + "(" + Math.round((now - flipStopStartMs) / 1000.0) + "초)"
                + " / 최고점수 " + Math.round(flipBest) + (flipBest > 0 ? "(" + kst.format(new java.util.Date(flipBestAtMs)) + " 정차 " + Math.round(flipBestMs / 1000.0) + "초)" : "")
                + " / 발화 판정 " + (flipFireAtMs > 0 ? kst.format(new java.util.Date(flipFireAtMs)) + "(정차 " + Math.round((flipFireAtMs - flipStopStartMs) / 1000.0) + "초)" : "없음")
                + " / 기준 " + flipRefCount + "장 / 판정 " + flipReadyTicks + "회 · 틱 " + flipTicks + "회 · 틱당 평균 " + (flipTicks > 0 ? Math.round(flipTickNs / (double) flipTicks / 1_000_000.0) : 0) + "ms"
                + " / 문턱=" + Math.round(prefs != null ? prefs.getFloat("front_flip_thr", 2500f) : 2500f) + " 지속=" + (prefs != null ? prefs.getInt("front_flip_persist", 2) : 2) + " 모드=" + (prefs != null ? prefs.getInt("front_flip_mode", FLIP_MODE_DEFAULT) : FLIP_MODE_DEFAULT));
        }
        if (flipDet != null) { flipDet.release(); flipDet = null; }
        flipRgb = null;
    }
    // ★FLIP 틱 — 정차·objTick 관문 안에서만 호출. 크롭은 626 확장 전 ROI4(r4pre)에서 만든다(lrb 재사용 금지: 아래쪽 110px가 잘려 적중이 급감).
    private void frontFlipTick(byte[] f, int[] meta, int[] r4pre, long now, int flipMode, boolean flipSound, int trafficIdx, int vol) {
        if (r4pre == null || prefs == null || now < flipNextMs) return;
        flipLastR4 = r4pre; flipLastMode = flipMode; flipLastSound = flipSound; flipLastTrafficIdx = trafficIdx; flipLastVol = vol;
        final long t0 = System.nanoTime();
        try {
            final int W = meta[0], H = meta[1];
            final int ybot = Math.round(0.52f * H);
            final int x0 = r4pre[0] & ~1, y0 = r4pre[1] & ~1;
            final int w = r4pre[2] & ~1, h = (Math.min(H, ybot + 40) - y0) & ~1;
            if (ybot - y0 < 20 || w < 16 || h < 16 || x0 + w > W) {
                if (now - flipErrLogMs > 30000) { flipErrLogMs = now; postToD1("FRONT", "[FLIP] 영역 부적합 — 판정 안 함 (신호등 영역 " + r4pre[0] + "," + r4pre[1] + " " + r4pre[2] + "x" + r4pre[3] + " / 화면 " + W + "x" + H + ")"); }
                return;
            }
            final double thr = prefs.getFloat("front_flip_thr", 2500f);
            final boolean flipFast = now < flipFastUntilMs;
            final int persistN = flipFast ? Math.max(3, prefs.getInt("front_flip_persist", 2)) : prefs.getInt("front_flip_persist", 2);
            final double strongThr = thr * prefs.getFloat("front_flip_strong_x", 2.0f);   // ★738 한 번만 넘어도 울리는 강한 점수(문턱×2.0 = 예전 5000 그대로 — 운영자 10-04 "지금처럼". 1.2는 밤 앞차 브레이크등 꺼짐 헛경보 위험)
            byte[] rgb = flipRgb = TrafficLockRed.nv21CropToRgb(f, W, H, x0, y0, w, h, flipRgb);
            if (flipDet == null) flipDet = new TrafficFlipDetector();
            TrafficFlipDetector.Tick tk = flipDet.onFrame(rgb, w, h, x0, y0, y0, ybot, now, thr, persistN, strongThr);
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            flipTicks++; flipTickNs += System.nanoTime() - t0; flipLastSnaps = tk.snapCount;
            flipNextMs = ms > 250 ? now + Math.min(1000L, ms * 3) : 0;   // 헤드유닛 부하가 크면 틱 간격을 최대 1초까지 늘림
            if (!tk.ready || tk.result == null) return;
            if (tk.result.best >= thr * 0.5 && now >= flipFastUntilMs - 500) {   // ★667 후보 감지 → 3초간 빠른 판정(연장)
                if (now >= flipFastUntilMs) postToD1("FRONT", "[FLIP] 빠른 판정 시작 — 후보 점수 " + Math.round(tk.result.best) + "(문턱 " + Math.round(thr) + "의 절반 이상)");
                flipFastUntilMs = now + 3000;
                tlFlipCand = true;   // ★신호등 정차 사진 기준 ②
            }
            final double stopSec = frontStoppedSince > 0 ? (now - frontStoppedSince) / 1000.0 : 0;
            flipReadyTicks++; flipRefCount = tk.refCount;
            if (flipReadyTicks == 1) postToD1("FRONT", "[FLIP] 판정 시작 — 정차 " + String.format(java.util.Locale.US, "%.1f", stopSec) + "초 기준 " + tk.refCount + "장 스냅샷#" + tk.snapSeq + " 문턱=" + Math.round(thr) + " 지속=" + persistN + " 강한=" + Math.round(strongThr) + " 모드=" + flipMode + " 틱=" + ms + "ms");
            final double sc = tk.result.best;
            if (sc > flipBest) { flipBest = sc; flipBestMs = frontStoppedSince > 0 ? now - frontStoppedSince : 0; flipBestAtMs = now; }
            if (flipTickLogs < 40 && now - flipTickLogMs >= 1000) {
                flipTickLogMs = now; flipTickLogs++;
                postToD1("FRONT", "[FLIPTICK] 정차 " + String.format(java.util.Locale.US, "%.1f", stopSec) + "초 점수=" + Math.round(sc) + " 빨강꺼짐 " + tk.result.nRed + "개(최대 " + Math.round(tk.result.maxRpk) + ") 초록켜짐 " + tk.result.nGrn + "개(최대 " + Math.round(tk.result.maxGpk) + " 밝기 " + tk.result.maxGv + ") 짝 " + tk.result.cands.size() + "개 지속=" + tk.persist + "/" + persistN + " 기준=" + tk.refCount + "장");
            }
            TrafficFlipDetector.Cand c = tk.result.bestCand;
            if (c == null || sc < Math.min(1100.0, thr)) return;
            final String det = "점수=" + Math.round(sc) + " 빨강(정점 " + Math.round(c.rpk) + " " + c.rw + "x" + c.rh + " @" + c.rx + "," + c.ry + ") 초록(정점 " + Math.round(c.gpk) + " " + c.gw + "x" + c.gh + " @" + c.gx + "," + c.gy + " 밝기 " + c.gv + ") 오른쪽 안바뀐 빨강 " + c.rightSame + "개(오른쪽에서 " + (c.rightSame + 1) + "번째) 기준=" + tk.refCount + "장 스냅샷#" + tk.snapSeq + " 정차 " + String.format(java.util.Locale.US, "%.1f", stopSec) + "초 지속=" + tk.persist + "/" + persistN;
            if (!tk.hit) {
                if (now - flipLogMs >= 1000) {
                    if (flipCandLogs < 20) { flipLogMs = now; flipCandLogs++; postToD1("FRONT", "[FLIP] 후보 " + det + " 문턱=" + Math.round(thr)); }
                    else if (flipCandLogs == 20) { flipCandLogs++; postToD1("FRONT", "[FLIP] 후보 로그 정차당 20줄 상한 도달 — 이후는 구간 종료 요약에만 반영"); }
                }
                return;
            }
            // ★738 다른 방향 신호 거르기(피드백분석 10-04 운영자 차 대조: 18:20:45 오른쪽 다른 방향 신호 @1296,532·안바뀐 빨강 13개에 70초 일찍 울림) —
            //   신호등이 많은 큰 교차로(오른쪽 안 바뀐 빨강 front_flip_side_rs개 이상)에서 바뀐 신호가 화면 아래쪽(1080 기준 y front_flip_side_y 이상)이면 보류.
            //   밤·비 적중(19:34:09, 안바뀐 빨강 13개·y 412)은 그대로 울린다. side_rs=0이면 끔. 먼 작은 신호는 일부러 안 맞춤(운영자 "너무 멀리 있는 건 안 울려도 된다").
            //   ★오프라인 대조(피드백분석 10-04, 9-29~10-04 발화 52건): 보류 6건 = 오발 1 + 적중 4(밤 큰 교차로는 우리 신호도 y 500~560·오른쪽 빨강 다수) → 기본 끔(0), 구분 특징 찾으면 원격으로.
            final int sideRs = prefs.getInt("front_flip_side_rs", 0), sideY = prefs.getInt("front_flip_side_y", 500);
            if (sideRs > 0 && c.rightSame >= sideRs && c.ry >= sideY * H / 1080) {
                if (now - flipLogMs >= 1000) { flipLogMs = now; postToD1("FRONT", "[FLIP] 발화 보류 — 다른 방향 신호 의심(오른쪽 안바뀐 빨강 " + c.rightSame + "개·높이 " + c.ry + " ≥ " + (sideY * H / 1080) + ") " + det); }
                return;
            }
            final boolean guardOk = now - frontTrafficAlert > 4000;   // 옛 경로(앵커·LR 포함)의 직전 신호 알림 4초 안에는 울리지 않음
            if (flipSound) {
                if (guardOk) {
                    frontFlipFiredEp = true; flipFireAtMs = now; frontTrafficFiredEp = true; frontTrafficAlert = now;   // 실제로 소리를 낼 때만 공용 플래그·시각을 세운다
                    playIccSound(trafficIdx, vol / 100f);
                    postToD1("FRONT", "[FLIP] 발화 " + det + " → sound " + trafficIdx);
                } else if (now - flipLogMs >= 1000) {
                    flipLogMs = now; postToD1("FRONT", "[FLIP] 발화 보류 — 직전 신호 알림 " + (now - frontTrafficAlert) + "ms 전(4초 규칙) " + det);
                }
            } else {
                frontFlipFiredEp = true; flipFireAtMs = now;
                postToD1("FRONT", "[FLIP] 발화 판정(소리 안 냄 — " + (flipMode == 2 ? "1920x1080 아님" : "기록만 모드") + ") " + det + " 4초규칙=" + (guardOk ? "통과" : "직전 알림 있음"));
            }
        } catch (Throwable t) {
            flipFailedEp = true; flipFailN++;
            if (flipDet != null) { try { flipDet.release(); } catch (Throwable ig) {} flipDet = null; }
            flipRgb = null;
            postToD1("FRONT", "[FLIP] 오류 — 이번 정차는 판정 중단(누적 " + flipFailN + "회, 다음 정차에 다시 시도): " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }
    // ROI(id) 폴리곤 → 프레임 픽셀 bbox [x,y,w,h]. 없으면 null.
    private int[] frontRoiBbox(java.util.List<com.cseini.motiondetect.Roi> rois, int id, int[] meta) {
        float mnx = 1f, mny = 1f, mxx = 0f, mxy = 0f; boolean has = false;
        for (com.cseini.motiondetect.Roi rr : rois) if (rr.id == id) { has = true; for (int k = 0; k < rr.nx.length; k++) { mnx = Math.min(mnx, rr.nx[k]); mxx = Math.max(mxx, rr.nx[k]); mny = Math.min(mny, rr.ny[k]); mxy = Math.max(mxy, rr.ny[k]); } }
        if (!has) return null;
        return new int[]{(int)(mnx*meta[0]), (int)(mny*meta[1]), (int)((mxx-mnx)*meta[0]), (int)((mxy-mny)*meta[1])};
    }
    private void handleFrontAlert(java.util.HashMap<Integer, int[]> agg, java.util.List<com.cseini.motiondetect.Roi> rois, byte[] f, int[] meta) {
        if (!autoAllowed()) return;   // ★킬 스위치·★620 폰 부재 — 전방 알림(앞차·신호) 안 함
        long now = System.currentTimeMillis();
        // ★정차 판정 = 저속(≤2km/h) **연속 2틱** + 전진 D(2026-09-17, 프레임 실측) — 옛 `≤3 단일 틱`은 "내가 정차하는 순간"에 참이 돼
        //   접근 중(3km/h면 틱 2.5s에 2m) bbox를 기준으로 무장했고, 발화 6건이 spd 1~3 구간(07:48:59 spd=2 등)이었다.
        //   완전정차서 getCurrentSpeed가 1~2 미세값을 내므로 ==0은 못 쓴다 → ≤2 를 2틱(≈5s) 연속 요구. 후진R=1·주차P=3 제외(2026-09-09).
        boolean lowSpd = speedLive && !Double.isNaN(lastSpeed) && lastSpeed <= 2 && lastGearVal == 2;
        frontZeroSpdTicks = lowSpd ? Math.min(frontZeroSpdTicks + 1, 99) : 0;
        boolean stopped = lowSpd && frontZeroSpdTicks >= 2;
        if (stopped) {
            if (frontStoppedSince == 0) {
                frontStoppedSince = now;
                tlStopBegin(now);   // ★신호등 정차 사진 — 정차 단위 시작
                // ★적색이력 유예창(2026-09-17) — 정차마다 즉시 와이프하면 stop-and-go로 1~2초 굴렀다 다시 설 때 같은 적신호의 이력이 날아가
                //   녹색 전환을 못 잡는다. 직전 정차 종료 후 15s 안이면 같은 신호로 보고 이력·기준선을 유지.
                if (now - frontStoppedEndTs > 15000) { java.util.Arrays.fill(frontLastRed, false); java.util.Arrays.fill(frontTrafGreenBase, 1f); frontTrafficFiredEp = false; }
                lrFreshStop = true;   // ★LR — 정차마다 고정 좌표 리셋(15s 유예와 무관, 차가 굴렀으면 픽셀 위치가 어긋난다)
                frontFlipStopStart(now);
            }
        } else if (frontStoppedSince != 0) {
            frontStoppedSince = 0; frontStoppedEndTs = now;
            frontFlipStopEnd(now);
            tlStopEnd(now, lockRed != null && lockRed.isLocked());   // ★신호등 정차 사진 — 기준 보고 올리거나 지움
            if (lockRed != null) { postToD1("FRONT", "[LR] 구간 종료 — " + (lockRed.isFired() ? "발화함" : lockRed.isLocked() ? "고정됐으나 미발화" : "고정 실패/미고정")); lockRed = null; }
        }
        boolean leadOn = false;   // ★639 카메라 앞차출발 폐기(운영자 09-29 "이제 ICC/ACC만") — 앞차출발은 ACC 전이 경로(front_lead)만. 카메라는 신호등 전용.
        boolean trafficOn = prefs == null || prefs.getBoolean("front_traffic", true);
        // ★668 기준 사진 미리 모으기 — '정차'는 저속 2틱(≈5초) 뒤에야 인정돼 그때부터 기준을 모으면 첫 판정까지 10~16초 걸렸다(운영자 "12초 너무 길다").
        //   저속(≤2km/h·D단)이 되는 즉시 같은 크롭으로 스냅샷만 쌓는다(문턱을 무한대로 줘서 발화·후보 판정은 안 함). 정차가 인정되면 이 스냅샷을 이어 쓴다.
        if (trafficOn && lowSpd && !stopped && prefs != null && prefs.getInt("front_flip_mode", FLIP_MODE_DEFAULT) != 0 && now - flipPrewarmMs >= 600) {
            flipPrewarmMs = now;
            frontFlipPrewarm(f, meta, frontRoiBbox(rois, 4, meta), now);
        }
        accDiagTick(now, lowSpd);
        boolean objOn = prefs == null || prefs.getBoolean("front_object", true);   // ★객체인식 게이트(기본 ON, RAW frontObject로 A/B)
        int vol = prefs != null ? prefs.getInt("front_vol", 80) : 80;
        int leadIdx = prefs != null ? prefs.getInt("front_lead_idx", 10) : 10;        // 기본 10=앞차 男
        int trafficIdx = prefs != null ? prefs.getInt("front_traffic_idx", 12) : 12;   // 기본 12=신호 男
        // ★객체인식 준비 — 정차 중에만(연산 절약). front 전용 KickDetector 지연 init(트렁크 kd와 별개, 주행모드라 공존 안 함).
        if (objOn && stopped && frontKd == null) {
            try { frontKd = new com.cseini.motiondetect.KickDetector(getApplicationContext()); postToD1("CAMDETECT", "전방 객체인식 init ready=" + frontKd.isReady()); }
            catch (Throwable t) { frontKd = null; postToD1("CAMDETECT", "전방 객체인식 init 실패: " + t.getClass().getSimpleName()); }
        }
        boolean objReady = objOn && frontKd != null && frontKd.isReady() && stopped;
        boolean objTick = objReady && (now - frontObjLastMs > 350);   // ★EfficientDet 추론 스로틀(~3fps) — 헤드유닛 부하 완화. 사이엔 frontLeadVehTs(role3)·고정ROI(role4) 폴백.
        if (objTick) frontObjLastMs = now;
        // ★검출 입력(fDet) vs 색판독 입력(f) 경로 분리(2026-09-17) — 역광이면 YOLO에는 감마 사본, ColorProbe는 항상 원본 f.
        byte[] fDet = f;
        if (objTick && frontGammaWanted()) fDet = frontGammaY(f, meta[0], meta[1]);   // ★트리거=최근 검출률(밝기 아님, 2026-09-17 캘리브레이션)
        // ★앞차 출발 — bbox 방향성 상태머신(2026-09-10, 운영자 "진짜 출발엔 안 울리고 앞차 뒤 정지할 때 울림" 실프레임 분석):
        //   기존 '움직임+차량있음'은 방향성이 없어 도착(앞차 등장+정착 움직임)도 출발로 오판. 새 로직=①정차 중 앞차 bbox(cy,h) 추적
        //   ②앞차가 정지 상태(cy·h 안정)로 2.5s+ 유지=무장(진짜 정지한 앞차 확인, 도착 순간 배제) ③무장 후 bbox가 위로 이동(cy↓=멀어짐)
        //   하거나 근거리에 있던 차가 사라지면=출발→발화. ROI 움직임(a3)은 '실제 이동 있었나' 보조확인만. 객체인식 미준비=미발화(오발화보다 미발화 선호).
        if (leadOn) {
            int[] a3 = agg.get(3);
            if (a3 != null) frontDiagMot3 = a3[0];
            float leadConf = 0, leadCy = -1, leadH = -1, leadCx = -1;
            if (objTick) {
                int[] r3 = frontRoiBbox(rois, 3, meta);
                if (r3 != null) {
                    float[] veh = frontKd.analyzeClass(fDet, meta[0], meta[1], r3[0], r3[1], r3[2], r3[3], new int[]{2, 5, 7});   // car/bus/truck → [conf,cx,cy,w,h](ROI 정규화, cy 1=하단/가까움). ★입력=fDet(역광이면 감마 사본)
                    if (veh != null && veh[0] >= 0.4f) { leadConf = veh[0]; leadCx = veh[1]; leadCy = veh[2]; leadH = veh[4]; frontLeadVehTs = now; frontDiagVeh = veh[0]; }
                    // ★검출률 창 갱신(2026-09-17) — 이 틱에 차량이 잡혔는지 20틱 비트마스크로 누적("사라짐" 적응 게이트 입력).
                    frontDetBits = ((frontDetBits << 1) | ((veh != null && veh[0] >= 0.4f) ? 1 : 0)) & 0xFFFFF;
                    if (frontDetN < 20) frontDetN++;
                    // ★앞차 가림 스탬프(2026-09-17) — 큰 bbox(h≥0.5) 차량이 바로 앞이면 신호등이 보일 공간이 없다(프레임 실측: 앞차 있음 적색검출 1.2% vs 맨앞 7.2%).
                    if (veh != null && veh[0] >= 0.4f && veh[4] >= 0.5f) frontOccludeTs = now;
                    // ★섀도 판정기 입력(2026-09-11) — 25박스 전부(analyzeClassAll) + 최고 박스 안 번호판(PlateTracker, Y평면·색무관).
                    //   소리는 여전히 옛 로직이 낸다. 새 판정기는 같은 틱을 먹고 '이랬을 것'만 로그(FRONT [섀도]) → 실차 A/B.
                    try { frontShadowVehs = frontKd.analyzeClassAll(fDet, meta[0], meta[1], r3[0], r3[1], r3[2], r3[3], new int[]{2, 5, 7}, 0.35f); }
                    catch (Throwable t) { frontShadowVehs = null; }
                    frontShadowPlate = null;
                    if (veh != null && veh[0] >= 0.4f) {
                        try {
                            int vw = Math.max(8, Math.round(veh[3] * r3[2])), vh = Math.max(8, Math.round(veh[4] * r3[3]));
                            int vx = r3[0] + Math.round(veh[1] * r3[2]) - vw / 2, vy = r3[1] + Math.round(veh[2] * r3[3]) - vh / 2;
                            frontShadowPlate = com.cseini.motiondetect.PlateTracker.find(f, meta[0], meta[1], vx, vy, vw, vh);
                        } catch (Throwable t) { frontShadowPlate = null; }
                    }
                }
            }
            if (stopped) frontLastStoppedTs = now;
            // ★유예창 1.8s→3.0s (2026-09-11 운영자 "실제로 앞차 출발해도 안 울린다") — 진단로그가 전부 `무장0ms`였다.
            //   무장 타이머가 계속 0으로 리셋돼 발화조건(recedeN≥2, 무장 2.5s+)에 **구조적으로 도달 못 하는** 상태였다.
            //   원인 후보는 정차판정(speed≤3 && gear=D) 깜빡임 → 창을 넓히고, 풀릴 땐 그 순간 입력값을 남긴다.
            boolean stopWin = stopped || now - frontLastStoppedTs < 3000;
            if (!stopWin) {
                if (frontLeadArmedTs > 0)
                    postToD1("FRONT", "[진단] 무장 해제 — 미정차 " + (now - frontLastStoppedTs) + "ms · speed=" + lastSpeed
                            + " gear=" + lastGearVal + " speedLive=" + speedLive + " (무장유지 " + (now - frontLeadArmedTs) + "ms)");
                frontLeadArmedTs = 0; frontLeadSeenTs = 0; frontLeadRecedeN = 0; frontLeadBaseCx = -1;
            }
            if (stopWin && objTick) {
                boolean armed = frontLeadArmedTs > 0 && now - frontLeadArmedTs >= 2500;
                if (leadConf >= 0.4f) {
                    // ★동일 차량 판정에 cx 추가 — 가로로 크게 움직였으면 그건 '내 앞차가 멀어진 것'이 아니라 **다른 차**다.
                    boolean sameCar = frontLeadBaseCx < 0 || Math.abs(leadCx - frontLeadBaseCx) < 0.13f;
                    boolean stable = frontLeadSeenTs > 0 && Math.abs(leadCy - frontLeadCy) < 0.06f && Math.abs(leadH - frontLeadH) < 0.10f
                            && Math.abs(leadCx - frontLeadCx) < 0.10f;   // bbox 노이즈 감안 완화(+cx)
                    // ★뭉침(merge) 방어(2026-09-17, 프레임 실측 07:26:21→24) — 앞차가 1px도 안 움직였는데 `h 0.96→0.64`로 "멀어짐" 발화.
                    //   h=0.96은 화면 세로 96%인데 육안은 0.35 — 혼잡 시 좌우 차까지 한 덩어리로 잡혔다가 다음 프레임에 다르게 잘린 것.
                    //   conf도 0.52→0.43으로 무장문턱 0.4를 간신히 넘나들었다. 47초간 6연발, baseH가 매번 0.96으로 재무장.
                    //   ①h>0.7이면 뭉침/클리핑으로 보고 무장 거부 ②무장은 conf≥0.6, 추적 유지는 0.4(이중 문턱) ③정지 중 기준갱신도 h>0.7 블롭은 무시.
                    boolean armOk = leadConf >= 0.6f && leadH <= 0.7f;
                    if (frontLeadArmedTs == 0 && stopped) {
                        if (armOk) { frontLeadArmedTs = now; frontLeadBaseCy = leadCy; frontLeadBaseH = leadH; frontLeadBaseCx = leadCx; frontLeadRecedeN = 0; }   // 무장 시작(정지 중). 도착 중엔 stable 전이라 발화 안 함
                        else if (now - frontLeadDiagMs > 1500) { frontLeadDiagMs = now; postToD1("FRONT", "[진단] 무장 거부 — " + (leadH > 0.7f ? "h=" + String.format(java.util.Locale.US, "%.2f", leadH) + ">0.7(뭉침/클리핑)" : "conf=" + String.format(java.util.Locale.US, "%.2f", leadConf) + "<0.6")); }
                    }
                    else if (!sameCar) {
                        // 추적 대상이 옆 차선 차 등으로 바뀜 → 발화 금지. 정지 중이면 그 차로 기준을 다시 잡는다(뭉침 가드 동일 적용).
                        if (frontLeadDiagMs != now && now - frontLeadDiagMs > 1500) {
                            frontLeadDiagMs = now;
                            postToD1("FRONT", "[진단] 다른 차량 전환 무시 cx " + String.format(java.util.Locale.US, "%.2f→%.2f", frontLeadBaseCx, leadCx) + " (발화 안 함)");
                        }
                        if (stopped && armOk) { frontLeadArmedTs = now; frontLeadBaseCy = leadCy; frontLeadBaseH = leadH; frontLeadBaseCx = leadCx; }
                        else if (stopped) { frontLeadArmedTs = 0; }
                        frontLeadRecedeN = 0;
                    }
                    else if (stable && stopped) { if (leadCy > frontLeadBaseCy) frontLeadBaseCy = leadCy; if (leadH > frontLeadBaseH && leadH <= 0.7f) frontLeadBaseH = leadH; frontLeadBaseCx = leadCx; frontLeadRecedeN = 0; }   // 정지 유지 — 가장 가까운(하단/큰) 기준 갱신(단 h>0.7 블롭은 기준으로 안 씀), recede 리셋
                    else if (armed) {
                        // ★출발 = 무장 후 앞차 bbox가 멀어짐(cy 0.08+ 위로 or h 30%+ 축소). 모션게이트 제거(멀어짐 자체가 신호, mot=1%로 막히던 것 수정). 노이즈는 2틱 지속 요구로 방어.
                        // ★모순 게이트(2026-09-11 실차 오발화 07:29:15 `cy 0.53→0.29 h 0.50→0.57`) —
                        //   멀어지는 차는 화면 위로 가면서 **반드시 작아진다**. 위로 갔는데 커졌다면 그건 다른 차로 바뀐 것.
                        //   기존 OR 조건이 cy 항목만 보고 쐈다. 커진 프레임은 아예 '멀어짐'으로 세지 않는다.
                        // ★2026-09-16 운영자 "앞차 다 멈춰있었는데 출발 오발화"로 a3 모션 게이트를 넣었다가, 같은 날 07:41:41 실차 로그(진짜 출발인데
                        //   mot=0~1%라 게이트에 막혀 무발화)로 즉시 되돌림 — 2026-09-10에 이미 "mot=1%로 막히던 것"으로 제거된 전례가 있었다(위 주석).
                        //   화면 전체 픽셀변화율(a3)과 실제 앞차 이동은 상관관계가 약함 — 재조사 금지, 노이즈 방어는 아래 recedeN 2틱/모순게이트로만.
                        boolean grew = leadH > frontLeadBaseH * 1.05f;
                        boolean receding = !grew && ((frontLeadBaseCy - leadCy) > 0.08f || (frontLeadBaseH - leadH) > frontLeadBaseH * 0.30f);
                        // ★강한 멀어짐은 1틱으로 발화(2026-09-11 20:06:05 실차: h 0.31→0.17(45% 축소) 1틱 뒤 내가 출발해 2틱을 못 채움). 틱 간격 ~2.5s라 2틱=5s는 늦다.
                        boolean strong = !grew && ((frontLeadBaseCy - leadCy) > 0.15f || (frontLeadBaseH - leadH) > frontLeadBaseH * 0.45f);
                        if (receding) frontLeadRecedeN += strong ? 2 : 1; else frontLeadRecedeN = Math.max(0, frontLeadRecedeN - 1);
                        if (frontLeadRecedeN >= 2 && now - frontLeadAlert > 4000) {
                            frontLeadAlert = now; frontLeadArmedTs = 0; frontLeadRecedeN = 0;
                            playIccSound(leadIdx, vol / 100f);
                            postToD1("FRONT", "앞차 출발(멀어짐 cy " + String.format(java.util.Locale.US, "%.2f→%.2f h %.2f→%.2f", frontLeadBaseCy, leadCy, frontLeadBaseH, leadH) + ") veh=" + String.format(java.util.Locale.US, "%.2f", leadConf) + " → sound " + leadIdx);
                        }
                    }
                    frontLeadCy = leadCy; frontLeadH = leadH; frontLeadCx = leadCx; frontLeadSeenTs = now;
                } else if (frontLeadArmedTs > 0) {
                    // 무장 상태서 미검출 — 근~중거리(baseCy>0.35)에 정지해 있던 앞차가 프레임서 빠짐=출발. 모션(a3≥8) 동반(잠깐 깜빡임 방어) + 500ms 홀드, 2.5s+ 지속 미검출은 무장 해제.
                    // ★근접 판정에 h 추가(2026-09-11 20:03:52 실차: baseCy=0.33·h=0.65 바로 앞 차가 사라졌는데 cy 0.35 문턱에 걸려 무발화). 큰 bbox(h≥0.30)는 중심이 화면 중간(cy~0.33)에 오므로 cy만으론 근접을 못 본다.
                    // ★검출신뢰도 적응 게이트(2026-09-17, 프레임 실측) — 야간/역광엔 미검출이 상시(98~100%)라 "안 보임=출발"이 무조건 걸렸다(사라짐 오발화 14건 전부
                    //   18:16~19:51). mot≥8 게이트는 방어가 안 됐음(발화 mot 8~14%로 문턱 바로 위, a3와 실제 이동 상관 약함 — 위 주석). 최근 20틱 검출률 <40%면
                    //   "앞차가 안 보이는 환경"이므로 사라짐 판정 자체를 하지 않고, 검출률이 좋을 때도 홀드를 500→1200ms로 늘려 1~2틱 깜빡임을 거른다.
                    float detRate = frontDetN > 0 ? Integer.bitCount(frontDetBits) / (float) Math.min(frontDetN, 20) : 0f;
                    boolean detReliable = frontDetN >= 8 && detRate >= 0.4f;
                    if (armed && !detReliable && now - frontDetGateLogMs > 5000) { frontDetGateLogMs = now; postToD1("FRONT", "[진단] 사라짐 판정 보류 — 최근 검출률 " + Math.round(detRate * 100) + "%(" + frontDetN + "틱) 낮음(야간/역광)"); }
                    if (armed && detReliable && a3 != null && a3[0] >= 8 && (frontLeadBaseCy > 0.35f || frontLeadBaseH > 0.30f) && now - frontLeadVehTs > 1200 && now - frontLeadAlert > 4000) {
                        frontLeadAlert = now; frontLeadArmedTs = 0; frontLeadRecedeN = 0;
                        playIccSound(leadIdx, vol / 100f);
                        postToD1("FRONT", "앞차 출발(사라짐 baseCy " + String.format(java.util.Locale.US, "%.2f", frontLeadBaseCy) + ") mot=" + a3[0] + "% 검출률=" + Math.round(detRate * 100) + "% → sound " + leadIdx);
                    } else if (now - frontLeadVehTs > 2500) { frontLeadArmedTs = 0; frontLeadSeenTs = 0; frontLeadRecedeN = 0; }
                }
                // ★진단 — 무장 중 궤적 1.5s 스로틀(발화 안 해도 궤적 관찰).
                if (frontLeadArmedTs > 0 && now - frontLeadDiagMs > 1500) {
                    frontLeadDiagMs = now;
                    postToD1("FRONT", "[진단] 무장" + (armed ? "✓" : (now - frontLeadArmedTs) + "ms") + " baseCy=" + String.format(java.util.Locale.US, "%.2f", frontLeadBaseCy) + " cy=" + String.format(java.util.Locale.US, "%.2f", leadConf >= 0.4f ? leadCy : -1) + " h=" + String.format(java.util.Locale.US, "%.2f→%.2f", frontLeadBaseH, leadConf >= 0.4f ? leadH : -1) + " cx=" + String.format(java.util.Locale.US, "%.2f→%.2f", frontLeadBaseCx, leadConf >= 0.4f ? leadCx : -1) + " recedeN=" + frontLeadRecedeN + " mot=" + (a3 != null ? a3[0] : -1) + "% spd=" + lastSpeed + " gear=" + lastGearVal);
                }
            }
        }
        // ★신호 변경 — 객체 앵커 전용(2026-09-10 실프레임 분석: 거대 고정 ROI 폴백이 천장조명 등 색을 희석해 신호등 거의 미작동·오탐).
        //   신호등 객체가 검출됐을 때만(conf 0.3→0.45 상향, 조명 오탐 차단) 그 작은 bbox에서만 색 판독→적→녹 전환 발화. 고정 ROI 폴백 삭제(미검출=미발화).
        //   정차(stopped) 게이트 유지(고속주행 오발화 차단, 2026-09-09). 객체 틱(objTick)에만 실행.
        if (trafficOn && stopped && objTick) {
            int[] r4b = frontRoiBbox(rois, 4, meta);
            // ★645 새 방식(LR)은 확장 전 사용자 영역을 화면 위 52%로 잘라 쓴다(첫 실주행: 확장된 위 80%에서 고정 13회 중 12회가 앞차 브레이크등·버스등·반사·보행자등).
            int[] lrb = null;
            if (r4b != null) { int ly1 = Math.min(r4b[1] + r4b[3], Math.round(meta[1] * 0.52f)); if (ly1 - r4b[1] >= 20) lrb = new int[]{r4b[0], r4b[1], r4b[2], ly1 - r4b[1]}; }
            final int[] r4pre = r4b;   // ★FLIP은 626 확장 전 사용자 영역을 쓴다(아래 확장이 r4b에 새 배열을 대입하므로 이 참조는 확장 전 값 그대로)
            final int flipMode = prefs != null ? prefs.getInt("front_flip_mode", FLIP_MODE_DEFAULT) : FLIP_MODE_DEFAULT;
            final boolean flipSound = flipMode == 2 && meta[0] == 1920 && meta[1] == 1080;   // 1920x1080 운영자 차 ROI에서만 검증됨 — 그 밖은 기록만
            // ★626 신호등 영역 자동 확장(운영자 09-28 "알아서 넓혀주고 디폴트로") — 09-23~28 [신호진단] 1604건 중 앵커 통과 4%, 프레임상 신호등이
            //   사용자가 그린 영역 아래쪽 밖(정차 위치·거리마다 높이가 달라짐). 기본으로 아래 60%·위 10% 넓힌다(prefs front_traffic_expand_down/up으로 조정).
            if (r4b != null && prefs != null) {
                float dn = prefs.getFloat("front_traffic_expand_down", 0.6f), up = prefs.getFloat("front_traffic_expand_up", 0.1f);
                int y0 = Math.max(0, r4b[1] - Math.round(r4b[3] * up));
                int y1 = Math.min(meta[1], r4b[1] + r4b[3] + Math.round(r4b[3] * dn));
                r4b = new int[]{r4b[0], y0, r4b[2], Math.max(1, y1 - y0)};
            }
            com.cseini.motiondetect.Roi r4 = null;
            for (com.cseini.motiondetect.Roi rr : rois) if (rr.id == 4) { r4 = rr; break; }
            if (r4b != null && r4 != null) {
                // ★타일 라운드로빈(2026-09-10) — 신호등 위치 매번 달라 작게 못 그림 → 큰 ROI를 겹치는 3타일(폭 40%)로 쪼개 틱당 1개 검사.
                //   타일 크롭은 좁아 신호등이 상대적으로 커져 검출 conf↑(전체 ROI 크롭은 신호등이 입력 320px서 너무 작음). ~1초에 전체 스캔, 추론비용 동일.
                int N = 3, ti = ((frontTrafTile++ % N) + N) % N;
                int tw = Math.round(r4b[2] * 0.4f);
                int tx = r4b[0] + Math.round((r4b[2] - tw) * (ti / (float)(N - 1)));   // ti=0 좌·1 중앙·2 우(겹침)
                float[] lt = frontKd.analyzeClass(fDet, meta[0], meta[1], tx, r4b[1], tw, r4b[3], new int[]{9});   // 타일에서 traffic light. ★입력=fDet(역광이면 감마 사본) — 색 판독은 아래서 원본 f
                float ltConf = lt != null ? lt[0] : -1;
                if (lt != null) frontDiagLt = lt[0];
                if (lt != null && frontStoppedSince > 0 && lt[0] > tlLtMax) tlLtMax = lt[0];   // ★신호등 정차 사진 기준 ①
                float ltAsp = (lt != null && lt[4] > 0.001f) ? (lt[3] * tw) / (lt[4] * r4b[3]) : 0;   // ★실제 종횡비(가로px/세로px) — 가로형=차량신호등, 세로형=횡단보도 보행신호.
                // ★앞차 가림 스킵(2026-09-17, 프레임 실측) — 앞차 있음(801프레임) 적색검출 1.2% vs 맨앞(1567) 7.2%, 6배 차. 큰 bbox(h≥0.5)가 바로 앞이면
                //   신호등이 보일 공간이 없어 판독은 희석 노이즈만 만든다. 최근 3s 내 큰 차량이 검출됐으면 이번 판정은 건너뛴다(단일 틱 미검출로 "맨앞" 오판 방지 — 역광 프레임처럼 앞차 있는데 veh=-1 나는 경우는 3s 스탬프가 덮는다).
                boolean occluded = now - frontOccludeTs < 3000;
                float[] rg = null; boolean anchored = false;
                // ★종횡비 임계 1.2→0.7(2026-09-17, 프레임 실측) — 검출 성공 267건 중 169건(63%)이 "세로배제"로 탈락했다. YOLO가 신호등 몸체가 아니라 **램프 하나(원형)**만
                //   잡아 asp≈0.9(정사각)가 되기 때문(중앙값 0.92, p90 1.9). 결정적 프레임 06:31:16/18: 가로형 차량신호등이 ROI 안에 선명한데 lt=0.43·asp=0.84/0.96으로 배제.
                //   세로배제 도입(09-10) 근거였던 보행신호 오발화는 로그에 실증 없음(당시 오발화는 전부 희석 폴백/녹색 단독). asp로는 차량/보행 구분이 안 된다.
                if (!occluded && lt != null && lt[0] >= 0.25f && ltAsp >= 0.7f) {
                    float cx = (tx + lt[1] * tw) / meta[0], cy = (r4b[1] + lt[2] * r4b[3]) / meta[1];
                    float hw = (lt[3] * tw) / meta[0] / 2f, hh = (lt[4] * r4b[3]) / meta[1] / 2f;
                    float[] lnx = {cx - hw, cx + hw, cx + hw, cx - hw}, lny = {cy - hh, cy - hh, cy + hh, cy + hh};
                    rg = com.cseini.motiondetect.ColorProbe.redGreen(f, meta[0], meta[1], lnx, lny, 32, 24); anchored = true;   // ★원본 f — 감마 사본은 R/G 비율을 왜곡
                }
                // ★앵커 실패 시 폴백 색판독 금지(2026-09-17, 프레임 실측) — 타일 전체 폴백은 램프가 타일의 0.1%라 희석돼 r=0.01 수준. 실발화 34건이 전부 r=0.00~0.04
                //   (희석 노이즈)로 진짜 신호와 무관하게 울렸고 정작 신호 바뀔 땐 안 울렸다("한 번도 된 적 없다"). 앵커 없으면 이번 틱은 판정 없음(미발화 선호).
                frontDiagRg = rg;
                frontShadowLt = ltConf; frontShadowAsp = ltAsp;   // ★섀도 판정기 입력(신호등)
                // ★임계 재설계 — 절대 0.10은 희석된 값에 맞지 않아 적색이 한 번도 안 걸렸다(169건 중 6건).
                //   ①적색: 낮은 절대바닥 + 강한 우세비 ②녹색: **적색 구간의 g를 기준선으로 잡아 그보다 확 오를 때만**.
                //   기준선 방식이라 나무·잔디처럼 상시 깔린 초록(실측 47건)에 안 속는다.
                boolean nowRed = rg != null && rg[0] > 0.015f && rg[0] > rg[1] * 2.0f;
                boolean nowGreen = rg != null && frontLastRed[ti]
                        && rg[1] > frontTrafGreenBase[ti] + 0.03f
                        && rg[1] > rg[0] * 2.0f;
                // ★신호진단(2026-09-10) — 정차 중 1.5s 스로틀. 타일별 lt·종횡비(asp)·r·g 관찰 → 임계·종횡비 튜닝.
                if (now - frontTrafDiagMs > 1500) {
                    frontTrafDiagMs = now;
                    postToD1("FRONT", "[신호진단] 타일" + ti + " lt=" + String.format(java.util.Locale.US, "%.2f", ltConf) + " asp=" + String.format(java.util.Locale.US, "%.2f", ltAsp) + " r=" + (rg == null ? "-" : String.format(java.util.Locale.US, "%.2f", rg[0])) + " g=" + (rg == null ? "-" : String.format(java.util.Locale.US, "%.2f", rg[1])) + " 적색이력=" + frontLastRed[ti] + " gBase=" + String.format(java.util.Locale.US, "%.2f", frontTrafGreenBase[ti]) + " " + (occluded ? "앞차가림스킵" : anchored ? "앵커" : ltAsp > 0 && ltAsp < 0.7f ? "세로배제" : "앵커없음(판정없음)"));
                }
                if (nowGreen && !frontTrafficFiredEp && now - frontTrafficAlert > 4000) {
                    if (flipMode == 2) {
                        if (now - frontAnchorMuteMs > 4000) {
                            frontAnchorMuteMs = now;
                            postToD1("FRONT", "신호 변경(적→녹) r=" + String.format(java.util.Locale.US, "%.2f", rg[0]) + " g=" + String.format(java.util.Locale.US, "%.2f", rg[1]) + " lt=" + String.format(java.util.Locale.US, "%.2f", ltConf) + " " + (anchored ? "앵커" : "고정ROI") + " → 소리 끔(새 방식이 소리 담당)");
                        }
                    } else {
                        frontTrafficAlert = now; frontTrafficFiredEp = true;   // ★LR과 정차 구간당 1회 공유(2026-09-28)
                        playIccSound(trafficIdx, vol / 100f);
                        postToD1("FRONT", "신호 변경(적→녹) r=" + String.format(java.util.Locale.US, "%.2f", rg[0]) + " g=" + String.format(java.util.Locale.US, "%.2f", rg[1]) + " lt=" + String.format(java.util.Locale.US, "%.2f", ltConf) + " " + (anchored ? "앵커" : "고정ROI") + " → sound " + trafficIdx);
                    }
                }
                // 적색 구간 동안 g의 '바닥'을 기억 — 이게 녹색 판정의 기준선이 된다(배경 초록 상쇄).
                if (nowRed) {
                    if (!frontLastRed[ti] || rg[1] < frontTrafGreenBase[ti]) frontTrafGreenBase[ti] = rg[1];
                    frontLastRed[ti] = true;
                } else if (nowGreen) { frontLastRed[ti] = false; frontTrafGreenBase[ti] = 1f; }
            }
            // ★LR(lockred, 2026-09-28) — 신호등 "새 방식", 객체인식 앵커와 무관하게 r4b 크롭 자체에서 색 잠금.
            //   운영자판(돌핀) 전용, front_traffic 토글 공유(이 블록 자체가 trafficOn 게이트 안). 로그는 [LR] 접두.
            if (lrb != null && flipMode != 2) {   // ★640 신호등 테스터 공개 — 새 방식(LR)도 공유판에 적용. ★690 모드 2(새 방식 소리)에선 LR이 소리를 안 내는데 고정 계산이 5초씩 틱을 막아 건너뜀
                if (lrFreshStop) { lockRed = new TrafficLockRed(new TrafficLockRed.Config()); lrFreshStop = false; lrAttempts = 1; lrLastAttempt = now; }
                else if (lockRed != null && lockRed.isLockFailed() && now - lrLastAttempt >= 5000 && lrAttempts < 12) {   // ★645 긴 정차 중 5초마다 재고정(처음 3장 실패로 정차 전체 포기하던 것)
                    lockRed = new TrafficLockRed(new TrafficLockRed.Config()); lrAttempts++; lrLastAttempt = now;
                }
                if (lockRed != null && !frontTrafficFiredEp) {
                    byte[] lrgb = TrafficLockRed.nv21CropToRgb(f, meta[0], meta[1], lrb[0], lrb[1], lrb[2], lrb[3]);
                    if (!lockRed.isLocked()) {
                        java.util.List<TrafficLockRed.Box> vehBoxes = null;
                        if (leadOn && frontShadowVehs != null && frontShadowVehs.length > 0) {   // ★leadOn 꺼지면 frontShadowVehs가 옛 틱 값 — 배제 스킵
                            int[] r3b = frontRoiBbox(rois, 3, meta);
                            if (r3b != null) {
                                vehBoxes = new java.util.ArrayList<>();
                                for (float[] v : frontShadowVehs) {
                                    float vw = v[3] * r3b[2], vh = v[4] * r3b[3];
                                    float vx = r3b[0] + v[1] * r3b[2] - vw / 2f - lrb[0];
                                    float vy = r3b[1] + v[2] * r3b[3] - vh / 2f - lrb[1];
                                    vehBoxes.add(new TrafficLockRed.Box(vx, vy, vw, vh));
                                }
                            }
                        }
                        TrafficLockRed.LockOutcome lo = lockRed.addLockFrame(lrgb, lrb[2], lrb[3], vehBoxes);
                        if (lo.diag != null) postToD1("FRONT", lo.diag);
                    } else {
                        TrafficLockRed.TrackOutcome to = lockRed.onTrackFrame(now, lrgb, lrb[2], lrb[3]);
                        if (to.diag != null) postToD1("FRONT", to.diag);
                        if (to.fired && !frontTrafficFiredEp) {
                            if (flipMode == 2) postToD1("FRONT", "[LR] 발화 판정(소리 끔 — 새 방식이 소리 담당)");
                            else {
                                frontTrafficFiredEp = true; frontTrafficAlert = now;
                                playIccSound(trafficIdx, vol / 100f);
                            }
                        }
                    }
                }
            }
            if (flipMode != 0 && !frontFlipFiredEp && !flipFailedEp) frontFlipTick(f, meta, r4pre, now, flipMode, flipSound, trafficIdx, vol);
        }
        // ★섀도 판정기 + 원본 틱 트레이스(2026-09-11) — 1.5s 진단 요약으론 recedeN≥2 같은 틱 단위 판정을 재현할 수 없었다
        //   (재생서 E009·E018 옛 발화가 숨은 틱에서 났음). 350ms objTick마다 새 판정기(FrontAlertTracker)를 같은 입력으로 돌리고
        //   결과를 [섀도]로 로그, 틱 원본은 10초 묶음(FRONTTRACE)으로 남긴다. 소리·동작은 옛 로직 그대로(A/B 계측 전용).
        boolean shadowWin = stopped || now - frontLastStoppedTs < 3000;   // leadOn 블록의 stopWin과 동일 정의(스코프 밖이라 재계산)
        if (objTick && shadowWin) {
            try {
                if (frontShadow == null) frontShadow = new FrontAlertTracker(new FrontAlertTracker.Config());
                FrontAlertTracker.Tick tk = new FrontAlertTracker.Tick();
                tk.nowMs = now; tk.stopped = stopped; tk.motPct = frontDiagMot3;
                if (frontShadowVehs != null) {
                    tk.vehicles = new FrontAlertTracker.Box[frontShadowVehs.length];
                    for (int i = 0; i < frontShadowVehs.length; i++) { float[] v = frontShadowVehs[i]; FrontAlertTracker.Box b = new FrontAlertTracker.Box(); b.conf = v[0]; b.cx = v[1]; b.cy = v[2]; b.w = v[3]; b.h = v[4]; tk.vehicles[i] = b; }
                } else tk.vehicles = new FrontAlertTracker.Box[0];
                if (frontShadowLt >= 0.25f) { FrontAlertTracker.Box lb = new FrontAlertTracker.Box(); lb.conf = frontShadowLt; tk.light = lb; tk.lightAsp = frontShadowAsp; }
                if (frontDiagRg != null) { tk.r = frontDiagRg[0]; tk.g = frontDiagRg[1]; }
                tk.plate = frontShadowPlate;
                FrontAlertTracker.Result rs = frontShadow.onTick(tk);
                if (rs != null && rs.event != FrontAlertTracker.Event.NONE)
                    postToD1("FRONT", "[섀도] " + rs.event + " — " + rs.why + " | " + frontShadow.diag());
                // 트레이스 한 줄: t|stopped|veh0(conf,cx,cy,w,h)|nveh|plateW|lt|r|g|mot
                // ★w 추가(2026-09-17) — 폭은 그대로인데 h만 급변하면 뭉침(merge) 시그니처. 옛 트레이스는 v0[3]=w를 버려 프레임 없이는 뭉침을 못 봤다.
                float[] v0 = (frontShadowVehs != null && frontShadowVehs.length > 0) ? frontShadowVehs[0] : null;
                frontTrace.append(now % 100000).append('|').append(stopped ? 1 : 0).append('|')
                        .append(v0 == null ? "-" : String.format(java.util.Locale.US, "%.2f,%.2f,%.2f,%.2f,%.2f", v0[0], v0[1], v0[2], v0[3], v0[4]))
                        .append('|').append(frontShadowVehs == null ? 0 : frontShadowVehs.length)
                        .append('|').append(frontShadowPlate == null ? "-" : String.format(java.util.Locale.US, "%.3f", frontShadowPlate.wNorm))
                        .append('|').append(String.format(java.util.Locale.US, "%.2f", frontShadowLt))
                        .append('|').append(frontDiagRg == null ? "-" : String.format(java.util.Locale.US, "%.2f/%.2f", frontDiagRg[0], frontDiagRg[1]))
                        .append('|').append(frontDiagMot3).append('\n');
                if (now - frontTraceFlushMs > 10000 || frontTrace.length() > 3200) {
                    if (frontTrace.length() > 0) postToD1("FRONTTRACE", frontTrace.toString());
                    frontTrace.setLength(0); frontTraceFlushMs = now;
                }
            } catch (Throwable t) { if (now - frontTraceFlushMs > 30000) { frontTraceFlushMs = now; postToD1("FRONT", "[섀도] 예외 " + t); } }
        } else if (frontTrace.length() > 0 && now - frontTraceFlushMs > 5000) { postToD1("FRONTTRACE", frontTrace.toString()); frontTrace.setLength(0); frontTraceFlushMs = now; }
        // ★진단 프레임 저장 — 정차 중 2s마다 현재 프레임을 ROI·감지값 오버레이해 서버로(운영자가 실주행 후 pull해 튜닝).
        if (stopped && prefs != null && prefs.getBoolean("front_diag", false)) frontDiagCapture(f, meta, rois);
    }
    // ★카메라 기능 끔(운영자 10-03, 폰 '앱 설정 → 카메라 기능 사용') — 기본은 켬(지금과 동일). 끄면 카메라를 여는 모든 입구
    //   (명령·자동 시작·주차 중 가용성 검사·클러스터 측면 카메라)를 막고, 끄는 순간 돌던 감지·실시간 보기·클러스터 카메라를 멈춘다.
    private boolean camOffOn() { return prefs != null && prefs.getBoolean("cam_off", false); }
    // ★센트리 녹화 중 실시간 보기(운영자 10-06 "알림 오면 궁금해서 바로 실시간을 연다") — 켜 두면 실시간 보기가 센트리 카메라 세션을 끊지 않고 각자 카메라를 연다(돌핀=BYD 카메라 서비스 공유형).
    //   같은 앱 안 AVM 이중 open은 실차 미검증 — 센트리 프레임이 끊기면 SentryManager 정지 감시가 PAUSED로 두고 실시간 보기가 끝난 뒤 재무장한다. pref sentry_live_coexist=false면 예전처럼 센트리가 비킨다.
    // ★791 센트리 프레임 공유(탭) — 790 실차 시험(10-06 13:32): 센트리가 쥔 AVM view5를 실시간 보기가 따로 열자 센트리 프레임이 끊김(140초 뒤 정지 감시가 재시작).
    //   그래서 카메라는 센트리 세션이 한 번만 열고, 실시간 보기(view5·쿼드)는 센트리가 받은 프레임을 그대로 받아 쓴다. 프레임 배열은 읽기 전용(복사 안 함).
    private volatile byte[] sentryTapFrame; private volatile int sentryTapW, sentryTapH; private volatile long sentryTapMs;
    // ★792 센트리가 앱의 주차 카메라 감지(접근·작업·트렁크 ROI, AVM view5)와 프레임을 나눠 쓴다 — 아토3 테스터 "일렉트로 꺼도 다른 감지 사용 중이라 안 된다"(10-06):
    //   '다른 감지'는 일렉트로가 아니라 우리 앱의 주차 감지였다. 카메라는 그 감지 세션이 한 번만 열고, 센트리는 그 루프가 받은 프레임을 받아 쓴다(카메라 충돌 없음).
    private volatile boolean sentryTapsDetect = false;
    private volatile int detectViewNum = -1;
    private boolean canTapDetect() { return detectActive && !detectSentryMode && !detectDriveMode && detectAvm && detectViewNum == 5 && !DiLink5Support.isDiLink5(); }
    private volatile boolean rtcTapsSentry = false;   // 지금 실시간 보기가 센트리 프레임을 받아 쓰는 중 — 이때만 센트리 게이트가 실시간 보기를 막지 않는다
    private boolean sentryLiveCoexist() { return !DiLink5Support.isDiLink5() && (prefs == null || prefs.getBoolean("sentry_live_coexist", true)); }
    private static final String CAM_OFF_MSG = "카메라 기능이 꺼져 있습니다(앱 설정에서 켜기)";
    private void stopAllCameraUse() {
        try { detectStop(); } catch (Throwable ignored) {}
        try { rtcStop(); } catch (Throwable ignored) {}
        try { clusterCam.stopNow("카메라 끔"); } catch (Throwable ignored) {}
        camStreaming = false; h264Streaming = false;
    }
    // ★신호등 정차 사진(운영자 10-03 "앞차진단은 포기, 신호등 관련 정차사진만 남기자") — 정차 중엔 cache/tldiag에 모았다가 정차가 끝날 때
    //   신호등 정차(정차 중 lt 최대 ≥0.15 · FLIP 후보 구간/발화 · LR 고정)면 tldiag_<정차시작>_<순번>_<시각>.jpg로 올리고, 아니면 지운다.
    //   기준 0.15는 정답 대조(신호 보임 82·빨→초 21·신호 없음 49구간)에서 빨→초 21/21·신호 보임 83%를 잡는 값(운영자 선택 "균형").
    private static final int TL_MAX_PER_STOP = 120, TL_MAX_STOPS_PER_DAY = 40;
    private static final float TL_LT_MIN = 0.15f;
    private volatile long tlStopStart = 0; private int tlSeq = 0; private float tlLtMax = -1f; private boolean tlFlipCand = false;
    private int tlStopsToday = 0; private long tlDayKey = 0;
    private final java.util.concurrent.ExecutorService tlExec = java.util.concurrent.Executors.newSingleThreadExecutor();
    private java.io.File tlDir() { java.io.File d = new java.io.File(getCacheDir(), "tldiag"); if (!d.isDirectory()) d.mkdirs(); return d; }
    private void tlStopBegin(long now) {
        if (tlStopStart != 0) tlStopEnd(now, false);
        tlStopStart = now; tlSeq = 0; tlLtMax = -1f; tlFlipCand = false;
    }
    private void tlStopEnd(long now, boolean lrLocked) {
        final long ss = tlStopStart; tlStopStart = 0;
        if (ss == 0) return;
        final boolean fired = flipFireAtMs > 0;
        final java.io.File[] files = tlDir().listFiles((d, n) -> n.startsWith("tldiag_" + ss + "_"));
        final int n = files == null ? 0 : files.length;
        if (n == 0) return;
        final boolean ok = tlLtMax >= TL_LT_MIN || tlFlipCand || fired || lrLocked;
        final String why = String.format(java.util.Locale.US, "lt최대=%.2f · FLIP 후보 %s · 발화 %s · LR 고정 %s · 정차 %d초",
                tlLtMax, tlFlipCand ? "있음" : "없음", fired ? "있음" : "없음", lrLocked ? "있음" : "없음", (now - ss) / 1000);
        long day = now / 86_400_000L;
        if (day != tlDayKey) { tlDayKey = day; tlStopsToday = 0; }
        if (!ok || tlStopsToday >= TL_MAX_STOPS_PER_DAY) {
            for (java.io.File f : files) f.delete();
            postToD1("TLDIAG", (ok ? "하루 " + TL_MAX_STOPS_PER_DAY + "정차 상한 — " : "신호등 아님 — ") + n + "장 삭제 (" + why + ")");
            return;
        }
        tlStopsToday++;
        java.util.Arrays.sort(files);
        postToD1("TLDIAG", "신호등 정차 사진 올림 시작 " + n + "장 (" + why + ")");
        tlExec.execute(() -> {
            int up = 0;
            for (java.io.File f : files) {
                try {
                    org.json.JSONObject p = new org.json.JSONObject(); p.put("path", f.getPath()); p.put("name", f.getName());
                    if (pullFile(p, true) == null) up++;
                } catch (Throwable ignored) {}
                f.delete();
                try { Thread.sleep(150); } catch (InterruptedException ie) { break; }
            }
            postToD1("TLDIAG", "신호등 정차 사진 올림 끝 " + up + "/" + n);
        });
    }
    private void frontDiagCapture(byte[] f, int[] meta, java.util.List<com.cseini.motiondetect.Roi> rois) {
        long now = System.currentTimeMillis();
        if (now - frontDiagLastMs < (now < flipFastUntilMs ? 500 : 2000)) return;   // 2s 스로틀(★667 후보 구간은 0.5초 — 시험 자료용)
        if (tlStopStart == 0 || tlSeq >= TL_MAX_PER_STOP) return;   // ★신호등 정차 사진 — 정차 단위로만 모음(정차당 상한)
        frontDiagLastMs = now;
        try {
            int w = meta[0], h = meta[1];
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            new android.graphics.YuvImage(f, android.graphics.ImageFormat.NV21, w, h, null).compressToJpeg(new android.graphics.Rect(0, 0, w, h), 80, bo);
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(bo.toByteArray(), 0, bo.size());
            if (bmp == null) return;
            android.graphics.Bitmap mut = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            android.graphics.Canvas c = new android.graphics.Canvas(mut);
            android.graphics.Paint p = new android.graphics.Paint(); p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(4); p.setAntiAlias(true);
            for (com.cseini.motiondetect.Roi rr : rois) {
                if (rr.id != 4) continue;   // 신호등 영역만(앞차 영역 오버레이는 폐지)
                p.setColor(0xFFFF5252);
                android.graphics.Path path = new android.graphics.Path();
                for (int i = 0; i < rr.nx.length; i++) { float x = rr.nx[i] * w, y = rr.ny[i] * h; if (i == 0) path.moveTo(x, y); else path.lineTo(x, y); }
                path.close(); c.drawPath(path, p);
            }
            float[] rg = frontDiagRg;
            String line = String.format(java.util.Locale.US, "spd=%.0f lt=%.2f r=%.2f g=%.2f flip=%d",
                    lastSpeed, frontDiagLt, rg != null ? rg[0] : -1f, rg != null ? rg[1] : -1f, Math.round(flipBest));
            android.graphics.Paint tp = new android.graphics.Paint(); tp.setColor(0xFF00E5FF); tp.setTextSize(30); tp.setAntiAlias(true);
            android.graphics.Paint bg = new android.graphics.Paint(); bg.setColor(0xC0000000);
            c.drawRect(0, 0, w, 40, bg); c.drawText(line, 8, 30, tp);
            java.io.ByteArrayOutputStream bo2 = new java.io.ByteArrayOutputStream();
            mut.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bo2);
            String nm = "tldiag_" + tlStopStart + "_" + String.format(java.util.Locale.US, "%03d", tlSeq) + "_" + now;
            java.io.File out = new java.io.File(tlDir(), nm + ".jpg");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) { fo.write(bo2.toByteArray()); }
            tlSeq++;
            frontDiagVeh = -1; frontDiagLt = -1;   // 다음 프레임 새로 채우게 리셋
        } catch (Throwable ignored) {}
    }
    // ★2026-09-16 D5CAMPROBE — 쿼드 4칸이 전부 전방으로 나오는 문제 진단. 오버드라이브(브레이브허트) 디컴파일 확인 결과
    //   PanoramicCameraGpu가 각 슬롯을 "frame-15/50 검증 → HAS DATA/BLACK" 식으로 실측 판정 후 죽은 슬롯만 대체 id로 폴백함
    //   (camId 0~3을 무조건 다 믿지 않음). 우리도 같은 방식으로 camId 0~3을 검증된 단일뷰 백엔드(FastCamTcpBackend)로
    //   하나씩 순차 요청해 실제로 서로 다른/비검정 화면인지부터 실측한다. 동시성 문제와 분리해서 원인부터 좁힌다.
    private void d5CamProbeAll() {
        postToD1("D5CAMPROBE", "시작 — camId 0,1,2,3 순차 단독요청 후 비교");
        int[] ids = {0, 1, 2, 3};
        java.util.List<String> results = new java.util.ArrayList<>();
        for (int id : ids) {
            final byte[][] holder = new byte[1][];
            final int[] wh = new int[2];
            final java.util.concurrent.CountDownLatch got = new java.util.concurrent.CountDownLatch(1);
            com.overdrive.app.camera.dilink5.FastCamTcpBackend bk = new com.overdrive.app.camera.dilink5.FastCamTcpBackend(id);
            boolean ok = bk.start(getApplicationContext(), 10, (nv21, w, h) -> {
                if (holder[0] == null) { holder[0] = nv21.clone(); wh[0] = w; wh[1] = h; got.countDown(); }
            }, (tag, m) -> postToD1("D5CAMPROBE", "[cam" + id + "] " + tag + ": " + m));
            try { got.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            try { bk.stop(); } catch (Throwable ignore) {}
            if (holder[0] == null) { results.add("cam" + id + ": 프레임없음(start=" + ok + ")"); continue; }
            byte[] nv21 = holder[0]; int w = wh[0], h = wh[1];
            long sum = 0; int n = w * h;
            for (int i = 0; i < n; i++) sum += (nv21[i] & 0xFF);
            double avg = n > 0 ? (double) sum / n : 0;
            try {
                android.graphics.YuvImage yi = new android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                yi.compressToJpeg(new android.graphics.Rect(0, 0, w, h), 85, bo);
                String nm = "d5camprobe_cam" + id + "_" + System.currentTimeMillis();
                java.io.File out = new java.io.File(getCacheDir(), nm + ".jpg");
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) { fo.write(bo.toByteArray()); }
                org.json.JSONObject up = new org.json.JSONObject(); up.put("path", out.getPath()); up.put("name", nm + ".jpg");
                pullFile(up);
            } catch (Throwable ignore) {}
            results.add("cam" + id + ": " + w + "x" + h + " avgY=" + String.format(java.util.Locale.US, "%.1f", avg) + (avg < 10 ? " (BLACK)" : " (DATA)"));
            try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        postToD1("D5CAMPROBE", "완료: " + String.join(" | ", results));
    }
    private void detectStop() {
        synchronized (detectLock) {
            detectActive = false; detectDriveMode = false;   // ★detectDriveMode도 내림 — 안 내리면 "이미 주행 감지 중" 좀비 스킵의 씨앗이 됨(2026-09-13)
            if (detectThread != null) { try { detectThread.join(1500); } catch (InterruptedException ig) {} detectThread = null; }
        }
    }

    // ★전방 주행 세션 자동 무장/해제(2026-09-11) — 폴 루프(2s)에서 호출. 상태 기반(엣지 아님)이라 주행 중 릴레이 재시작도 커버.
    //   켜기: 주행(D/R) && 전방알림 ON && 킬스위치 OFF && 저장 params 있음 && 주행 세션 없음 → 30s 간격 시도(카메라 사용 중이면 다음 시도).
    //   끄기: P기어 && 주행 세션 활성 → 종료(카메라 반납 → 앱의 주차 감지가 받음). N 정차는 유지(신호대기 N 넣어도 카메라 안 껐다켬).
    //   세션은 sec 6h(구 1h 상한 제거) — 만료돼도 주행 중이면 다음 틱이 재무장.
    private void frontAutoArmTick(int driving, int gear) {
        if (prefs == null) return;
        long now = System.currentTimeMillis();
        if (driving == 1) {
            boolean diag = now - frontArmDiagMs > 30000;   // ★493 진단 30s 스로틀
            if (relay != null) { relay.putStatus("fa_state", "act" + detectActive + " dm" + detectDriveMode + " g" + gear); relay.putStatus("_fa_ms", "frm" + (detectLastFrameMs == 0 ? "0" : (now - detectLastFrameMs)) + " try" + (now - frontArmTryMs)); }   // ★10-10 데이터 절감 — 매 틱 바뀌는 ms 값은 변화 판정에서 빠지는 _fa_ms로(주행 중 전체 올림 1.5초마다 유발)   // ★495 relay_state 진단 — 로그채널(CAMDETECT) 미도착 우회, 무장 상태를 relay_state로 확실히 노출
            // ★클러스터 측면 카메라(2026-09-17)가 카메라를 쥔 동안은 재무장 금지 — 안 막으면 !detectActive를 보고 camDetect가 NormalCamera를 겹쳐 연다. 종료 시 frontArmTryMs=0으로 즉시 재무장.
            if (clusterCam.active && !camDualOk()) { if (diag) { frontArmDiagMs = now; postToD1("CAMDETECT", "DIAG493 arm skip: 클러스터 측면 카메라 사용 중(동시 운용 실패 기록)"); } return; }   // ★626 동시 운용 가능하면 무장 허용
            if (detectActive && detectDriveMode) {
                // ★좀비 세션 자가복구(2026-09-13) — 활성 표시인데 최근 프레임이 안 오면(카메라 死/스레드 좀비) 정리 후 재무장. 정상(프레임 살아있음)이면 그대로.
                if (detectLastFrameMs != 0 && now - detectLastFrameMs < 12000) { if (diag) { frontArmDiagMs = now; postToD1("CAMDETECT", "DIAG493 arm skip: 정상 감지중(frame " + (now - detectLastFrameMs) + "ms)"); } return; }
                postToD1("CAMDETECT", "전방 세션 좀비 감지(프레임 " + (detectLastFrameMs == 0 ? "0" : (now - detectLastFrameMs) + "ms") + " 없음) → 정리 후 재무장");
                detectStop();
            }
            if (!autoAllowed()) { if (diag) { frontArmDiagMs = now; postToD1("CAMDETECT", "DIAG493 arm skip: 킬스위치/폰 부재(620)"); } return; }
            if (!prefs.getBoolean("front_traffic", true)) { if (diag) { frontArmDiagMs = now; postToD1("CAMDETECT", "DIAG493 arm skip: 신호등 토글 OFF"); } return; }   // ★639 전방 카메라=신호등 전용(앞차출발은 ACC)
            final String pj = prefs.getString("front_drive_params", null);
            if (pj == null || pj.isEmpty()) { if (diag) { frontArmDiagMs = now; postToD1("CAMDETECT", "DIAG493 arm skip: front_drive_params 없음"); } return; }
            if (now - frontArmTryMs < 30000) return;
            if (diag) { frontArmDiagMs = now; postToD1("CAMDETECT", "DIAG493 arm 시도 → camDetect 호출"); }
            frontArmTryMs = now;
            new Thread(() -> {
                try {
                    org.json.JSONObject p = new org.json.JSONObject(pj);
                    p.put("sec", 6 * 3600);
                    String r = camDetect(p);
                    postToD1("CAMDETECT", "전방 감지 자동 시작(주행 감지 gear=" + gear + ") → " + r);
                } catch (Throwable t) { postToD1("CAMDETECT", "전방 자동 시작 실패: " + t); }
            }, "front-autoarm").start();
        } else if (gear == 3 && detectActive && detectDriveMode) {
            new Thread(() -> {
                synchronized (detectLock) {
                    if (!(detectActive && detectDriveMode)) return;
                    postToD1("CAMDETECT", "전방 감지 종료(P기어)");
                    detectStop(); clearMotState();
                }
            }, "front-autostop").start();
        }
    }

    // ★릴레이 재시작(ACC OFF 태스크청소·크래시) 시 진행 중이던 카메라 감지 자동 재개 — 창(sec) 안이면.
    //   시동 끄고 트렁크로 가면 ACC OFF가 릴레이를 죽여 감지가 끊기던 것 복구. camDetect가 카메라 사용 중이면 스스로 스킵.
    private void resumeDetectIfPending() {
        try {
            if (prefs == null) return;
            long until = prefs.getLong("camdetect_until", 0);
            String pj = prefs.getString("camdetect_resume", null);
            long now = System.currentTimeMillis();
            if (pj == null || pj.isEmpty() || until <= now) return;
            final org.json.JSONObject p = new org.json.JSONObject(pj);
            final int remainSec = (int) ((until - now) / 1000);
            p.put("sec", Math.max(5, remainSec));   // 남은 창만큼만(창 연장 방지)
            postToD1("CAMDETECT", "재시작 감지 재개 예약 — 남은 " + remainSec + "s view=" + p.optInt("view"));
            if (mainHandler != null) mainHandler.postDelayed(() -> {
                try {
                    // ★v378 지연 4s 사이 앱이 stop+새 CAMDETECT를 보냈으면 구 params로 덮지 않음 — 2026-09-07 08:35:56 재시작→:57 앱 rois=5(트렁크) 세션 시작→:00 예약재개(rois=3 구 세션)가 detectGen++로 트렁크 세션 폐기→킥 무감지(peak_mot 0%). 활성 세션 있으면 스킵, prefs 재읽기(stop이 지웠으면 null→스킵)해 최신 params만.
                    if (detectActive) { postToD1("CAMDETECT", "재개 스킵 — 이미 새 감지 세션 활성(재시작 후 앱 명령 선처리)"); return; }
                    String pj2 = prefs != null ? prefs.getString("camdetect_resume", null) : null;
                    if (pj2 == null || pj2.isEmpty()) { postToD1("CAMDETECT", "재개 스킵 — 저장 세션 없음(stop됨)"); return; }
                    org.json.JSONObject p2 = new org.json.JSONObject(pj2);
                    p2.put("sec", Math.max(5, (int) ((prefs.getLong("camdetect_until", 0) - System.currentTimeMillis()) / 1000)));
                    camDetect(p2);
                } catch (Throwable ig) {}
            }, 4000);   // 카메라 서브시스템 안정 대기
        } catch (Throwable t) { postToD1("CAMDETECT", "감지 재개 실패: " + t); }
    }

    // ★mot 상태 초기화 — 감지 시작/중지 시 이전 값 고착(화면 멈춤) 방지. 현재 감지하는 ROI만 새로 채워짐.
    private void clearMotState() {
        for (int i = 0; i < 16; i++) { relay.putState("mot" + i, 0); relay.putState("mot" + i + "_p", 0); }
    }

    private String camShot(org.json.JSONObject params) {
        if (camOffOn()) return CAM_OFF_MSG;   // ★카메라 기능 끔
        StringBuilder sb = new StringBuilder();
        try {
            if (bmmLoader == null) {
                java.io.File jar = new java.io.File("/system/framework/bmmcamera.jar");
                if (!jar.canRead()) return "bmmcamera.jar 읽기불가";
                bmmLoader = new dalvik.system.DexClassLoader(jar.getPath(), getCodeCacheDir().getPath(), "/system/lib64:/system/lib", getClassLoader());
            }
            try { Class.forName("android.hardware.JNILoader", true, bmmLoader).getMethod("load").invoke(null); sb.append("JNI로드OK "); }
            catch (Throwable t) { sb.append("JNI로드예외:").append(rootMsg(t)).append(" "); }
            // ★AVM 모드 — {avm:true, camId(pano id, 기본1), view(VIEW_CHANNEL 1~4=전후좌우)}
            final boolean avm = params != null && params.optBoolean("avm");
            final int view = params != null ? params.optInt("view", 1) : 1;
            String clsName = avm ? "android.hardware.AVMCamera" : "android.hardware.NormalCamera";
            Class<?> ncCls = Class.forName(clsName, true, bmmLoader);
            Class<?> ipcCls = Class.forName(clsName + "$IPreviewCallback", true, bmmLoader);
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            if (params != null && params.has("camId")) ids.add(params.getInt("camId"));
            else if (avm) ids.add(1);   // pano
            else {
                String cs = runShort("getprop vehicle.config.cam_sort").trim();
                sb.append("cam_sort='").append(cs).append("' ");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(":(\\d+)").matcher(cs);
                while (m.find()) { int v = Integer.parseInt(m.group(1)); if (!ids.contains(v)) ids.add(v); }
                if (ids.isEmpty()) for (int i = 0; i < 8; i++) ids.add(i);
            }
            for (int id : ids) {
                camFrame = null;
                Object cam = ncCls.getMethod("open", int.class).invoke(null, id);
                if (cam == null) { sb.append("open(").append(id).append(")=null "); continue; }
                sb.append("★open(").append(id).append(")OK").append(avm ? "(AVM view=" + view + ") " : " ");
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(bmmLoader, new Class[]{ipcCls}, (pr, meth, args) -> {
                    if ("onPreview".equals(meth.getName()) && args != null && args.length >= 6 && camFrame == null) {
                        camFrame = (byte[]) args[1];
                        camMeta[0] = (int) args[2]; camMeta[1] = (int) args[3]; camMeta[2] = (int) args[4]; camMeta[3] = (int) args[5];
                    }
                    Class<?> rt = meth.getReturnType();
                    return rt == boolean.class ? Boolean.FALSE : (rt == int.class ? Integer.valueOf(0) : null);
                });
                ncCls.getMethod("setPreviewCallback", ipcCls).invoke(cam, proxy);
                if (avm) ncCls.getMethod("enablePreviewCallback", int.class).invoke(cam, view);
                else ncCls.getMethod("enablePreviewCallback").invoke(cam);
                Object started = ncCls.getMethod("startPreview").invoke(cam);
                sb.append("startPreview=").append(started).append(" ");
                for (int w = 0; w < 40 && camFrame == null; w++) Thread.sleep(100);   // 최대 4s
                try { ncCls.getMethod("stopPreview").invoke(cam); ncCls.getMethod("close").invoke(cam); } catch (Throwable ignore) {}
                if (camFrame != null) {
                    int w = camMeta[0], h = camMeta[1], cf = camMeta[2], sz = camMeta[3];
                    sb.append("프레임! ").append(w).append("x").append(h).append(" fmt=").append(cf).append(" size=").append(sz).append(" ");
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    try {
                        new android.graphics.YuvImage(camFrame, android.graphics.ImageFormat.NV21, w, h, null)
                                .compressToJpeg(new android.graphics.Rect(0, 0, w, h), 85, bo);
                    } catch (Throwable yt) { sb.append("YUV변환예외:").append(rootMsg(yt)).append(" "); }
                    byte[] jpg = bo.toByteArray();
                    String nm = avm ? ("camavm_" + view) : ("camshot_" + id);
                    java.io.File out = new java.io.File(getCacheDir(), nm + ".jpg");
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) { fo.write(jpg); }
                    org.json.JSONObject up = new org.json.JSONObject();
                    up.put("path", out.getPath()); up.put("name", nm + ".jpg");
                    pullFile(up);
                    sb.append("JPEG ").append(jpg.length).append("B 업로드 ");
                    postToD1("CAMSHOT", sb.toString());
                    return null;
                } else sb.append("프레임 없음(4s) ");
            }
            postToD1("CAMSHOT", sb.toString());
            return "프레임 못받음 — " + sb;
        } catch (Throwable t) { String e = "CAMSHOT 실패: " + rootMsg(t) + " | " + sb; postToD1("CAMSHOT", e); return e; }
    }
    private String rootMsg(Throwable t) {
        Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
        return c.getClass().getSimpleName() + ":" + c.getMessage();
    }

    // ★v189 라이브 스트리밍 — 카메라 연속 프레임 → JPEG → 워커 push(폰이 /cam 뷰어로 시청). params {camId,fps,q,sec,stop}
    private volatile boolean camStreaming = false;
    private Thread camThread;
    private String camStream(org.json.JSONObject params) {
        if (camOffOn()) return CAM_OFF_MSG;   // ★카메라 기능 끔
        if (params != null && params.optBoolean("stop")) { camStreaming = false; return "스트림 중지 요청"; }
        if (rtcActive) return "RTC 카메라 세션 중 — 중지 후 재시도";   // ★v266 한 카메라 두 소비자 금지
        // 전환 — 기존 스트림 있으면 먼저 중지하고 스레드 종료 대기(카메라 릴리스 후 새로 시작)
        if (camStreaming) {
            camStreaming = false;
            try { if (camThread != null) camThread.join(3000); } catch (InterruptedException ig) {}
        }
        final int camId = params != null ? params.optInt("camId", 0) : 0;
        final int fps = params != null ? params.optInt("fps", 5) : 5;
        final int quality = params != null ? params.optInt("q", 45) : 45;
        final int durationSec = params != null ? params.optInt("sec", 300) : 300;
        final boolean avm = params != null && params.optBoolean("avm");   // ★AVM 개별/통합뷰
        final int view = params != null ? params.optInt("view", 1) : 1;
        final int streamCamId = avm ? (params != null ? params.optInt("camId", 1) : 1) : camId;   // AVM=pano id(기본1)
        final String tag = avm ? ("a" + view) : ("c" + camId);
        camStreaming = true; RelayClient.cameraActive = true;
        camThread = new Thread(() -> {
            Object cam = null; Class<?> ncCls = null;
            java.net.HttpURLConnection conn = null; java.io.OutputStream netOut = null;
            final byte[][] latest = new byte[1][]; final int[] meta = new int[4];
            try {
                if (bmmLoader == null) {
                    java.io.File jar = new java.io.File("/system/framework/bmmcamera.jar");
                    bmmLoader = new dalvik.system.DexClassLoader(jar.getPath(), getCodeCacheDir().getPath(), "/system/lib64:/system/lib", getClassLoader());
                }
                Class.forName("android.hardware.JNILoader", true, bmmLoader).getMethod("load").invoke(null);
                String clsName = avm ? "android.hardware.AVMCamera" : "android.hardware.NormalCamera";
                ncCls = Class.forName(clsName, true, bmmLoader);
                Class<?> ipcCls = Class.forName(clsName + "$IPreviewCallback", true, bmmLoader);
                cam = openAvmCam(ncCls, streamCamId, avm, "CAMSTREAM");
                if (cam == null) { camStreaming = false; return; }
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(bmmLoader, new Class[]{ipcCls}, (pr, m, a) -> {
                    if ("onPreview".equals(m.getName()) && a != null && a.length >= 6) {
                        try {
                            byte[] d = (byte[]) a[1]; int size = (int) a[5];
                            byte[] copy = new byte[size]; System.arraycopy(d, 0, copy, 0, Math.min(d.length, size));
                            meta[0] = (int) a[2]; meta[1] = (int) a[3]; meta[2] = (int) a[4]; meta[3] = size; latest[0] = copy;
                        } catch (Throwable ig) {}
                    }
                    Class<?> rt = m.getReturnType(); return rt == boolean.class ? Boolean.FALSE : (rt == int.class ? Integer.valueOf(0) : null);
                });
                ncCls.getMethod("setPreviewCallback", ipcCls).invoke(cam, proxy);
                if (avm) ncCls.getMethod("enablePreviewCallback", int.class).invoke(cam, view);
                else ncCls.getMethod("enablePreviewCallback").invoke(cam);
                ncCls.getMethod("startPreview").invoke(cam);
                // ★v197 per-frame POST + keep-alive(연결 재사용, 핸드셰이크 제거, CF친화). 지속연결은 CF버퍼링으로 폐기.
                postToD1("CAMSTREAM", "시작 " + (avm ? "AVM view=" + view : "cam=" + camId) + " fps=" + fps + " q=" + quality);
                long end = System.currentTimeMillis() + durationSec * 1000L, interval = 1000 / Math.max(1, fps);
                int sent = 0; byte[] lastFed = null;
                while (camStreaming && System.currentTimeMillis() < end) {
                    long t0 = System.currentTimeMillis();
                    byte[] f = latest[0];
                    if (f != null && f != lastFed && meta[0] > 0) {
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        new android.graphics.YuvImage(f, android.graphics.ImageFormat.NV21, meta[0], meta[1], null)
                                .compressToJpeg(new android.graphics.Rect(0, 0, meta[0], meta[1]), quality, bo);
                        if (postFrameToWorker(bo.toByteArray(), streamCamId, tag)) sent++;
                        lastFed = f;
                    }
                    long dt = System.currentTimeMillis() - t0; if (dt < interval) try { Thread.sleep(interval - dt); } catch (InterruptedException e) { break; }
                }
                postToD1("CAMSTREAM", "종료 cam=" + camId + " sent=" + sent);
            } catch (Throwable t) { postToD1("CAMSTREAM", "실패:" + rootMsg(t)); }
            finally { camStreaming = false; RelayClient.cameraActive = false;
                try { if (cam != null && ncCls != null) { ncCls.getMethod("stopPreview").invoke(cam); ncCls.getMethod("close").invoke(cam); } } catch (Throwable ig) {} }
        }, "camstream"); camThread.start();
        return null;
    }
    // ★v190 H.264 하드웨어 인코딩 검증 — 카메라→MediaCodec(surface input)→H.264 Annex-B → 파일저장+업로드(유효성 확인).
    //   params {camId, sec(기본5), bitrate(기본2500000), fps(기본20)}
    private String camH264(org.json.JSONObject params) {
        if (camOffOn()) return CAM_OFF_MSG;   // ★카메라 기능 끔
        final int camId = params != null ? params.optInt("camId", 0) : 0;
        final int sec = params != null ? params.optInt("sec", 5) : 5;
        final int bitrate = params != null ? params.optInt("bitrate", 2500000) : 2500000;
        final int fps = params != null ? params.optInt("fps", 20) : 20;
        final int W = 1920, H = 1080;
        StringBuilder sb = new StringBuilder();
        Object cam = null; Class<?> ncCls = null; android.media.MediaCodec enc = null; java.io.FileOutputStream fos = null;
        final byte[][] latest = new byte[1][]; final int[] meta = new int[4];
        try {
            if (bmmLoader == null) { java.io.File jar = new java.io.File("/system/framework/bmmcamera.jar");
                bmmLoader = new dalvik.system.DexClassLoader(jar.getPath(), getCodeCacheDir().getPath(), "/system/lib64:/system/lib", getClassLoader()); }
            Class.forName("android.hardware.JNILoader", true, bmmLoader).getMethod("load").invoke(null);
            ncCls = Class.forName("android.hardware.NormalCamera", true, bmmLoader);
            Class<?> ipcCls = Class.forName("android.hardware.NormalCamera$IPreviewCallback", true, bmmLoader);
            // 카메라 먼저 열어 실제 프레임 크기 확보
            cam = ncCls.getMethod("open", int.class).invoke(null, camId);
            if (cam == null) return "open(" + camId + ")=null";
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(bmmLoader, new Class[]{ipcCls}, (pr, m, a) -> {
                if ("onPreview".equals(m.getName()) && a != null && a.length >= 6) {
                    try { int size = (int) a[5]; byte[] copy = new byte[size]; System.arraycopy((byte[]) a[1], 0, copy, 0, Math.min(((byte[]) a[1]).length, size));
                        meta[0] = (int) a[2]; meta[1] = (int) a[3]; meta[2] = (int) a[4]; meta[3] = size; latest[0] = copy; } catch (Throwable ig) {}
                }
                Class<?> rt = m.getReturnType(); return rt == boolean.class ? Boolean.FALSE : (rt == int.class ? Integer.valueOf(0) : null);
            });
            ncCls.getMethod("setPreviewCallback", ipcCls).invoke(cam, proxy);
            ncCls.getMethod("enablePreviewCallback").invoke(cam);
            ncCls.getMethod("startPreview").invoke(cam);
            // 첫 프레임 대기(크기 확정)
            for (int w = 0; w < 30 && latest[0] == null; w++) Thread.sleep(100);
            if (latest[0] == null) return "프레임 안옴 — " + sb;
            int fw = meta[0], fh = meta[1]; sb.append("프레임 ").append(fw).append("x").append(fh).append(" cf=").append(meta[2]).append(" ");
            // H.264 인코더(바이트버퍼 YUV420SemiPlanar=NV12 입력, colorFormat 21과 매칭)
            android.media.MediaFormat fmt = android.media.MediaFormat.createVideoFormat("video/avc", fw, fh);
            fmt.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            fmt.setInteger(android.media.MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(android.media.MediaFormat.KEY_FRAME_RATE, fps);
            fmt.setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            enc = android.media.MediaCodec.createEncoderByType("video/avc");
            enc.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            sb.append("인코더OK ");
            java.io.File out = new java.io.File(getCacheDir(), "cam_" + camId + ".h264");
            fos = new java.io.FileOutputStream(out);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            long end = System.currentTimeMillis() + sec * 1000L; int nal = 0, key = 0, cfg = 0, fed = 0; long bytes = 0, pts = 0; long frameIntervalUs = 1000000L / fps;
            byte[] lastFed = null;
            while (System.currentTimeMillis() < end) {
                byte[] f = latest[0];
                if (f != null && f != lastFed) {
                    int ii = enc.dequeueInputBuffer(5000);
                    if (ii >= 0) {
                        java.nio.ByteBuffer ib = enc.getInputBuffer(ii); ib.clear();
                        int put = Math.min(f.length, ib.capacity()); ib.put(f, 0, put);
                        enc.queueInputBuffer(ii, 0, put, pts, 0); pts += frameIntervalUs; fed++; lastFed = f;
                    }
                }
                int oi = enc.dequeueOutputBuffer(info, 5000);
                if (oi >= 0) {
                    java.nio.ByteBuffer b = enc.getOutputBuffer(oi);
                    if (b != null && info.size > 0) {
                        byte[] d = new byte[info.size]; b.position(info.offset); b.get(d);
                        fos.write(d); bytes += d.length; nal++;
                        if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) cfg++;
                        if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) key++;
                    }
                    enc.releaseOutputBuffer(oi, false);
                }
            }
            fos.flush(); fos.close(); fos = null;
            sb.append("fed=").append(fed).append(" ");
            sb.append("NAL=").append(nal).append(" key=").append(key).append(" cfg=").append(cfg).append(" bytes=").append(bytes).append(" ");
            org.json.JSONObject up = new org.json.JSONObject(); up.put("path", out.getPath()); up.put("name", "cam_" + camId + ".h264");
            pullFile(up);
            sb.append("업로드 ");
            postToD1("CAMH264", sb.toString());
            return nal > 0 ? null : "H.264 NAL 0개 — " + sb;
        } catch (Throwable t) {
            java.io.StringWriter swr = new java.io.StringWriter(); t.printStackTrace(new java.io.PrintWriter(swr));
            String trace = swr.toString(); if (trace.length() > 800) trace = trace.substring(0, 800);
            String e = "CAMH264 실패:" + rootMsg(t) + " | " + sb + "\n" + trace; postToD1("CAMH264", e); return e;
        }
        finally {
            try { if (cam != null && ncCls != null) { ncCls.getMethod("stopPreview").invoke(cam); ncCls.getMethod("close").invoke(cam); } } catch (Throwable ig) {}
            try { if (enc != null) { enc.stop(); enc.release(); } } catch (Throwable ig) {}
            try { if (fos != null) fos.close(); } catch (Throwable ig) {}
        }
    }

    // ★v195 H.264 라이브 — 카메라(NV12)→MediaCodec→access-unit을 지속 HTTP연결로 워커에 스트리밍(프레이밍: [4B len][1B flags key=1][data]).
    private volatile boolean h264Streaming = false; private Thread h264Thread;
    private String camH264Stream(org.json.JSONObject params) {
        if (camOffOn()) return CAM_OFF_MSG;   // ★카메라 기능 끔
        if (params != null && params.optBoolean("stop")) { h264Streaming = false; return "H264 중지"; }
        if (rtcActive) return "RTC 카메라 세션 중 — 중지 후 재시도";   // ★v266 한 카메라 두 소비자 금지
        if (h264Streaming) { h264Streaming = false; try { if (h264Thread != null) h264Thread.join(800); } catch (InterruptedException ig) {} }   // ★v218 전환 빠르게 — 이전 스트림 종료 대기 3000→800ms(워커 뷰태그가 이전뷰 프레임 차단하므로 겹쳐도 안전)
        final boolean avm = params != null && params.optBoolean("avm");
        final int view = params != null ? params.optInt("view", 1) : 1;
        final int camId = avm ? (params != null ? params.optInt("camId", 1) : 1) : (params != null ? params.optInt("camId", 0) : 0);
        final int bitrate = params != null ? params.optInt("bitrate", 2000000) : 2000000;
        final int fps = params != null ? params.optInt("fps", 15) : 15;
        final int durationSec = params != null ? params.optInt("sec", 600) : 600;
        h264Streaming = true; RelayClient.cameraActive = true;
        h264Thread = new Thread(() -> {
            Object cam = null; Class<?> ncCls = null; android.media.MediaCodec enc = null;
            java.net.HttpURLConnection conn = null; java.io.OutputStream netOut = null;
            final byte[][] latest = new byte[1][]; final int[] meta = new int[4]; final byte[][] cfg = new byte[1][];
            try {
                if (bmmLoader == null) { java.io.File jar = new java.io.File("/system/framework/bmmcamera.jar");
                    bmmLoader = new dalvik.system.DexClassLoader(jar.getPath(), getCodeCacheDir().getPath(), "/system/lib64:/system/lib", getClassLoader()); }
                Class.forName("android.hardware.JNILoader", true, bmmLoader).getMethod("load").invoke(null);
                String clsName = avm ? "android.hardware.AVMCamera" : "android.hardware.NormalCamera";
                ncCls = Class.forName(clsName, true, bmmLoader);
                Class<?> ipcCls = Class.forName(clsName + "$IPreviewCallback", true, bmmLoader);
                cam = openAvmCam(ncCls, camId, avm, "CAMH264");
                if (cam == null) { h264Streaming = false; return; }
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(bmmLoader, new Class[]{ipcCls}, (pr, m, a) -> {
                    if ("onPreview".equals(m.getName()) && a != null && a.length >= 6) {
                        try { int size = (int) a[5]; byte[] c = new byte[size]; System.arraycopy((byte[]) a[1], 0, c, 0, Math.min(((byte[]) a[1]).length, size));
                            meta[0] = (int) a[2]; meta[1] = (int) a[3]; meta[2] = (int) a[4]; meta[3] = size; latest[0] = c; } catch (Throwable ig) {}
                    }
                    Class<?> rt = m.getReturnType(); return rt == boolean.class ? Boolean.FALSE : (rt == int.class ? Integer.valueOf(0) : null);
                });
                ncCls.getMethod("setPreviewCallback", ipcCls).invoke(cam, proxy);
                if (avm) ncCls.getMethod("enablePreviewCallback", int.class).invoke(cam, view);
                else ncCls.getMethod("enablePreviewCallback").invoke(cam);
                ncCls.getMethod("startPreview").invoke(cam);
                for (int w = 0; w < 40 && latest[0] == null; w++) Thread.sleep(100);
                if (latest[0] == null) { postToD1("CAMH264", "프레임 안옴"); h264Streaming = false; return; }
                int fw = meta[0], fh = meta[1];
                android.media.MediaFormat fmt = android.media.MediaFormat.createVideoFormat("video/avc", fw, fh);
                fmt.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
                fmt.setInteger(android.media.MediaFormat.KEY_BIT_RATE, bitrate);
                fmt.setInteger(android.media.MediaFormat.KEY_FRAME_RATE, fps);
                fmt.setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                enc = android.media.MediaCodec.createEncoderByType("video/avc");
                enc.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
                enc.start();
                // 지속 연결 (chunked POST)
                String vin = prefs != null ? prefs.getString(KEY_VIN, "") : "";
                String viewTag = avm ? ("a" + view) : ("c" + camId);   // ★v218 워커 camActiveTag와 일치 — 이전 뷰 POST 프레임 차단(전환 잔상 방지)
                String url = "https://byd.cseini.co.kr/relay/camh264?vin=" + java.net.URLEncoder.encode(vin, "UTF-8")
                        + "&w=" + fw + "&h=" + fh + "&view=" + viewTag + "&auth=" + BuildConfig.RELAY_SECRET;
                conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setDoOutput(true); conn.setRequestMethod("POST"); conn.setChunkedStreamingMode(0);
                conn.setConnectTimeout(10000); conn.setRequestProperty("Content-Type", "application/octet-stream");
                netOut = conn.getOutputStream();
                postToD1("CAMH264", "라이브시작 " + (avm ? "AVM view=" + view : "cam=" + camId) + " " + fw + "x" + fh + " " + (bitrate / 1000) + "kbps");
                android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
                long frameIntervalUs = 1000000L / fps, pts = 0; byte[] lastFed = null; int sent = 0;
                long endMs = System.currentTimeMillis() + durationSec * 1000L;
                while (h264Streaming && System.currentTimeMillis() < endMs) {
                    byte[] f = latest[0];
                    if (f != null && f != lastFed) {
                        // ★v217 색감 — 카메라 프레임은 NV21(크로마 VU순)인데 인코더는 NV12(UV순) 기대 → 크로마 바이트쌍 스왑 안 하면 파랑↔주황 반전(MJPEG는 NV21로 해석해 정상이었음)
                        for (int ci = fw * fh; ci + 1 < f.length; ci += 2) { byte t = f[ci]; f[ci] = f[ci + 1]; f[ci + 1] = t; }
                        int ii = enc.dequeueInputBuffer(3000);
                        if (ii >= 0) { java.nio.ByteBuffer ib = enc.getInputBuffer(ii); ib.clear(); int put = Math.min(f.length, ib.capacity());
                            ib.put(f, 0, put); enc.queueInputBuffer(ii, 0, put, pts, 0); pts += frameIntervalUs; lastFed = f; }
                    }
                    int oi = enc.dequeueOutputBuffer(info, 3000);
                    if (oi >= 0) {
                        java.nio.ByteBuffer b = enc.getOutputBuffer(oi);
                        if (b != null && info.size > 0) {
                            byte[] d = new byte[info.size]; b.position(info.offset); b.get(d);
                            boolean isCfg = (info.flags & android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                            boolean isKey = (info.flags & android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            if (isCfg) { cfg[0] = d; }   // SPS/PPS 저장, 키프레임에 prepend
                            else {
                                byte[] payload = d;
                                if (isKey && cfg[0] != null) { payload = new byte[cfg[0].length + d.length];
                                    System.arraycopy(cfg[0], 0, payload, 0, cfg[0].length); System.arraycopy(d, 0, payload, cfg[0].length, d.length); }
                                // 프레이밍 [4B len][1B flags][8B 촬영시각ms][data]
                                int n = payload.length; long cts = System.currentTimeMillis();
                                netOut.write((n >>> 24) & 0xff); netOut.write((n >>> 16) & 0xff); netOut.write((n >>> 8) & 0xff); netOut.write(n & 0xff);
                                netOut.write(isKey ? 1 : 0);
                                netOut.write((int)((cts >>> 56) & 0xff)); netOut.write((int)((cts >>> 48) & 0xff)); netOut.write((int)((cts >>> 40) & 0xff)); netOut.write((int)((cts >>> 32) & 0xff));
                                netOut.write((int)((cts >>> 24) & 0xff)); netOut.write((int)((cts >>> 16) & 0xff)); netOut.write((int)((cts >>> 8) & 0xff)); netOut.write((int)(cts & 0xff));
                                netOut.write(payload); netOut.flush(); sent++;
                            }
                        }
                        enc.releaseOutputBuffer(oi, false);
                    }
                }
                try { netOut.close(); } catch (Throwable ig) {}
                int rc = -1; try { rc = conn.getResponseCode(); } catch (Throwable ig) {}
                postToD1("CAMH264", "라이브종료 sent=" + sent + " http=" + rc);
            } catch (Throwable t) { postToD1("CAMH264", "라이브실패:" + rootMsg(t)); }
            finally { h264Streaming = false; RelayClient.cameraActive = false;
                try { if (cam != null && ncCls != null) { ncCls.getMethod("stopPreview").invoke(cam); ncCls.getMethod("close").invoke(cam); } } catch (Throwable ig) {}
                try { if (enc != null) { enc.stop(); enc.release(); } } catch (Throwable ig) {}
                try { if (netOut != null) netOut.close(); } catch (Throwable ig) {}
                try { if (conn != null) conn.disconnect(); } catch (Throwable ig) {} }
        }, "camh264stream"); h264Thread.start();
        return null;
    }

    // ★v266 WebRTC P2P 카메라 — 카메라 원시프레임(NV21)→libwebrtc VideoSource→HW인코더→SRTP로 폰 직결(srflx↔srflx).
    //   워커는 시그널링(offer/answer)만 중계, 미디어는 P2P 직결(미디어서버 안 거침). 오디오 완전 비활성.
    //   params {sid, sdp(폰 offer), avm, view, camId, sec}. non-trickle(gathering 완료 후 candidate 포함 answer 전송).
    private static class SdpCb implements org.webrtc.SdpObserver {
        public void onCreateSuccess(org.webrtc.SessionDescription sdp) {}
        public void onSetSuccess() {}
        public void onCreateFailure(String s) {}
        public void onSetFailure(String s) {}
    }
    volatile boolean rtcActive = false;
    volatile boolean rtcIceConnected = false;   // ★730 폰과 실제로 연결돼 있나(ICE CONNECTED·COMPLETED) — 아니면 보조 카메라가 그 세션을 닫고 카메라를 가져간다
    private Thread rtcThread;
    private static boolean rtcInited = false;

    private void rtcStop() {
        rtcActive = false; rtcTapsSentry = false;
        Thread t = rtcThread;
        if (t != null) { t.interrupt(); try { t.join(4000); } catch (InterruptedException ig) {} }   // gathering.await 등 블로킹 깨우기
    }

    // ★Overdrive식 pano 카메라 ID 동적 조회(2026-09-01) — AVM pano ID가 차종마다 다름(씰 실측: 1 아님→open(1)=null).
    //   android.hardware.BmmCameraInfo.getCameraId(tag)로 차량에서 직접 조회. 참조: Overdrive AvmCameraHelper.discoverPanoCameraId.
    private int discoverPanoCamId() {
        try {
            Class<?> bmm = Class.forName("android.hardware.BmmCameraInfo");
            java.lang.reflect.Method gci = bmm.getDeclaredMethod("getCameraId", String.class);
            gci.setAccessible(true);
            for (String tag : new String[]{"pano_h", "pano_l", "byd_apa", "apa"}) {
                try { Object r = gci.invoke(null, tag); if (r instanceof Integer && (Integer) r >= 0) return (Integer) r; } catch (Throwable ig) {}
            }
        } catch (Throwable ignored) {}
        return -1;
    }
    // ★AVM 카메라 open — 요청 ID 먼저(돌핀 open(1) 무변경), null이면 pano discovery→0~5 스윕. 열린 cam 반환(전부 실패=null).
    //   비-AVM(NormalCamera)은 요청 ID만(기존 동작 보존). 폴백 성공/실패 시 실제 id 로깅.
    private Object openAvmCam(Class<?> ncCls, int reqId, boolean avm, String logCat) {
        java.lang.reflect.Method openM;
        try { openM = ncCls.getMethod("open", int.class); } catch (Throwable t) { postToD1(logCat, "open 메서드 없음: " + t); return null; }
        java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
        ids.add(reqId);
        int disc = -1;
        if (avm) { disc = discoverPanoCamId(); if (disc >= 0) ids.add(disc); for (int i = 0; i <= 5; i++) ids.add(i); }
        for (int id : ids) {
            try { Object c = openM.invoke(null, id); if (c != null) { if (id != reqId) postToD1(logCat, "open 폴백 성공 id=" + id + " (요청" + reqId + " 실패·pano disc=" + disc + ")"); return c; } }
            catch (Throwable ig) {}
        }
        postToD1(logCat, "open(" + reqId + ")=null · pano disc=" + disc + " · 스윕0-5 전부 실패");
        return null;
    }

    private String camRtc(org.json.JSONObject params) {
        if (camOffOn()) return CAM_OFF_MSG;   // ★카메라 기능 끔
        if (params != null && params.optBoolean("stop")) { rtcStop(); return "RTC 중지"; }
        if (rtcActive) rtcStop();   // 기존 세션 선점
        for (int i = 0; i < 75 && camProbing; i++) sleepQuiet(200);   // ★655 가용성 검사(≤15초) 끝날 때까지 대기 — 검사 중 카메라를 뺏어 양쪽 다 깨지던 것 방지
        final int viewEarly = params != null ? params.optInt("view", 1) : 1;
        final boolean keepSentry = detectActive && (detectSentryMode || sentryTapsDetect) && sentryLiveCoexist() && viewEarly == 5 && System.currentTimeMillis() - sentryTapMs < 3000;   // ★791 쿼드(view5)만 — 센트리 프레임을 받아 쓴다
        if (keepSentry) postToD1("CAMRTC", "센트리 녹화 중 — 센트리가 받은 카메라 프레임을 실시간 보기에 나눠 씁니다(카메라는 한 번만 열림)");
        else { detectStop(); clearMotState(); }   // ★백그라운드 ROI 감지(camDetect) 중이면 정리 — 한 카메라 단일 소비자(뷰어 진입 시 충돌 방지)
        clusterCam.stopNow("CAMRTC");    // ★깜빡이 측면 카메라(2026-09-17)도 같은 이유로 선점 종료
        bsdExp.stopNow("CAMRTC");        // ★602 BSD실험도 선점
        // 겹치는 MJPEG/H264 스트림 중지 — 한 카메라 단일 소비자
        camStreaming = false; try { if (camThread != null) camThread.join(1000); } catch (InterruptedException ig) {}
        // ★카메라 하드웨어 해제 대기(2026-09-15) — rtcStop/detectStop이 스레드는 종료해도 bmmcamera 물리 해제가 지연돼,
        //   재진입 시 새 세션이 이전 카메라(전방 채널)를 재사용/공유해 4분할 대신 전방으로 나오거나 로딩 멈춤(ICE)이 간헐 발생.
        //   500ms 여유로 이전 카메라 완전 해제 후 새 open — 첫 진입=4분할, 재진입도 4분할 일관성 확보.
        try { Thread.sleep(500); } catch (InterruptedException ig) { return "중단"; }
        h264Streaming = false; try { if (h264Thread != null) h264Thread.join(1000); } catch (InterruptedException ig) {}

        final String sid = params != null ? params.optString("sid", "") : "";
        final String offerSdp = params != null ? params.optString("sdp", "") : "";
        if (offerSdp.isEmpty()) return "offer SDP 없음";
        final int view = params != null ? params.optInt("view", 1) : 1;
        // ★view5(4분할 쿼드)는 avm(pano) 전제 — ROI 편집기 프리뷰(snapURL)가 avm 파라미터를 안 실어 camId=0(전방)으로 열리며
        //   접근·작업·트렁크 편집이 전방 어안으로 나오던 근본(2026-09-14). CAMDETECT view5(avm=true, camId=1)와 일치시킴.
        final boolean avm = (params != null && params.optBoolean("avm")) || view >= 5;
        final int camId = avm ? (params != null ? params.optInt("camId", 1) : 1) : (params != null ? params.optInt("camId", 0) : 0);
        final boolean hdReq = params != null && params.optBoolean("hd", false);   // ★566 HD 토글 — 워커가 params에 이미 실어 보내는데 릴레이가 읽지 않고 있었다
        final boolean notify = params != null && params.optBoolean("notify", false);   // ★테스트화면 실제 오픈(2026-09-04) — true면 kdr 사람게이트(personMot) 통과 시 kick:2 발송(화면 보며 오픈). ROI에디터 '실제 오픈' 토글.
        final int durationSec = params != null ? params.optInt("sec", 600) : 600;
        final boolean hd = params != null && params.optBoolean("hd", false);   // ★HD: 풀해상도+높은비트레이트 / 아니면 다운스케일+절약

        rtcTapsSentry = keepSentry;
        rtcActive = true; rtcIceConnected = false; RelayClient.cameraActive = true; camRtcT0 = System.currentTimeMillis();
        rtcThread = new Thread(() -> {
            Object cam = null; Class<?> ncCls = null;
            org.webrtc.PeerConnectionFactory factory = null;
            org.webrtc.VideoSource vsrc = null; org.webrtc.PeerConnection pc = null;   // ★스레드 로컬 — 세션 교차 dispose 레이스 방지
            com.cseini.motiondetect.MotionDetector dmd = null; int dProc = 0;   // ★감지통합 — finally 정리 위해 try 밖 선언
            int[] tbb = null; TrunkCover rtcCover = new TrunkCover();   // ★테스트화면 킥 진단(2026-09-02) — 트렁크 ROI 사람/발목/킥을 kick2 state로 발행
            final byte[][] latest = new byte[1][]; final int[] meta = new int[4];
            final com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend[] d5cam = new com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend[1];   // ★DiLink5 QCarCam 백엔드(씨라7) — finally서 stop
            final com.overdrive.app.camera.dilink5.FastCamTcpBackend[] fastcamBk = new com.overdrive.app.camera.dilink5.FastCamTcpBackend[1];   // ★fast_cam TCP 브리지 백엔드(BeetleLauncher 1.553 이식) — 우선 시도, finally서 stop
            final com.overdrive.app.camera.dilink5.FastCamQuadBackend[] quadBk = new com.overdrive.app.camera.dilink5.FastCamQuadBackend[1];   // ★쿼드(4분할) 백엔드(2026-09-16, 씨라7 전/후/좌/우) — view==5(avm) 요청 시, finally서 stop
            final org.webrtc.DataChannel[] metaDc = new org.webrtc.DataChannel[1];   // ★폰이 만든 meta 채널 — 촬영시각(cts) 전송용
            final java.util.concurrent.CountDownLatch gatherDone = new java.util.concurrent.CountDownLatch(1);
            try {
                postToD1("CAMRTC", "start avm=" + avm + " cam=" + camId + " sdpLen=" + offerSdp.length()
                        + (DiLink5Support.isDiLink5() && relay != null ? " · D5상태 lock=" + relay.getStatus("d5_lock") + " mirror=" + relay.getStatus("d5_mirror") + " pwr=" + relay.carPowerState() : ""));   // ★638 씨라7 2캠/4캠 상태 의존 판별(잠김·미러·전원) — 피드백분석 09-28
                // 1) libwebrtc 초기화(프로세스당 1회)
                if (!rtcInited) {
                    org.webrtc.PeerConnectionFactory.initialize(
                        org.webrtc.PeerConnectionFactory.InitializationOptions.builder(getApplicationContext())
                            .createInitializationOptions());
                    rtcInited = true;
                }
                postToD1("CAMRTC", "init OK");
                // ★EGL 없이 — 백그라운드 서비스엔 디스플레이 없어 EglBase.create()가 행(hang). 바이트버퍼(NV21→I420) 입력이라 EGL 불필요.
                //   null 컨텍스트 → HW 인코더가 YUV 바이트버퍼 모드로 동작.
                factory = org.webrtc.PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(new org.webrtc.DefaultVideoEncoderFactory(null, true, true))
                    .setVideoDecoderFactory(new org.webrtc.DefaultVideoDecoderFactory((org.webrtc.EglBase.Context) null))
                    // ★LegacyADM은 네이티브 ADM을 즉시 생성→AudioFlinger 접근→이 헤드유닛서 SIGABRT(TimeCheck timeout).
                    //   JavaADM은 오디오(AudioRecord/Track)를 start 시점까지 지연 → 오디오 트랙 없는 우리는 AudioFlinger 아예 안 건드림.
                    .setAudioDeviceModule(org.webrtc.audio.JavaAudioDeviceModule.builder(getApplicationContext())
                        .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
                        .createAudioDeviceModule())
                    .createPeerConnectionFactory();
                postToD1("CAMRTC", "factory OK");

                // 2) 비디오 소스 + 트랙(오디오 트랙 없음)
                // ★649 씨라7은 화면공유형 소스 — 638의 MAINTAIN_RESOLUTION에도 송출 크기가 360→480→720으로 바뀌었다(1920x1300의 WebRTC 표준 배율,
                //   FB1287 "30초 뒤 4분할 녹색 줄"과 시점 일치). 화면공유형은 해상도 자동 조절(quality scaler)을 기본으로 끈다.
                vsrc = factory.createVideoSource(DiLink5Support.isDiLink5());
                // ★566 저해상도로 시작 — 4분할 원본이 2560x1920이라 첫 프레임까지 인코딩 부하가 크고, 대역 상한(300kbps)에도 과하다.
                //   adaptOutputFormat으로 송신 전 다운스케일하면 첫 화면이 더 빨리 뜬다. HD 토글이면 원본 그대로.
                //   4분할은 사분면당 480x360이 남고(사람·물체 식별 가능), 단일 카메라는 640x480.
                if (!hdReq) {
                    try {
                        // ★643 씨라7 녹색 세로줄(FB1054/1173/1196) 근본 — D5 원본은 1920x1300(쿼드 합성·단일 칸 960x650)인데 4:3(960x720)을 요청하면
                        //   라이브러리가 가로를 잘라 866x650이 되고, 폭 866(색 성분 폭 433=홀수)이 하드웨어 인코더 정렬과 어긋나 매 행 같은 자리에 0바이트(녹색).
                        //   원본 비율(1.477)과 거의 같고 16의 배수인 944x640으로 보낸다.
                        if (DiLink5Support.isDiLink5()) vsrc.adaptOutputFormat(944, 640, 15);
                        else if (avm) vsrc.adaptOutputFormat(960, 720, 15);
                        else vsrc.adaptOutputFormat(640, 480, 15);
                        postToD1("CAMRTC", "저해상도 시작 " + (DiLink5Support.isDiLink5() ? "944x640" : avm ? "960x720" : "640x480") + "@15 (HD 토글 시 원본)");
                    } catch (Throwable t) { postToD1("CAMRTC", "해상도 제한 실패: " + t.getClass().getSimpleName()); }
                }
                org.webrtc.VideoTrack videoTrack = factory.createVideoTrack("cam0", vsrc);
                final org.webrtc.CapturerObserver capObs = vsrc.getCapturerObserver();
                capObs.onCapturerStarted(true);

                // 3) PeerConnection — 워커 /rtc/ice에서 STUN+TURN 수신. ★릴레이 srflx가 IPv6-only이거나 까다로운 NAT면
                //   직접경로가 끊겨도 TURN(IPv4 릴레이 후보)으로 폴백해 붙는다(2026-08-31 씰차주 실측: IPv6 srflx→끊김).
                java.util.List<org.webrtc.PeerConnection.IceServer> iceServers = fetchIceServers();
                if (iceServers == null || iceServers.isEmpty()) {
                    iceServers = new java.util.ArrayList<>();
                    iceServers.add(org.webrtc.PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
                    postToD1("CAMRTC", "iceServers 수신 실패 → STUN만 폴백");
                } else postToD1("CAMRTC", "iceServers " + iceServers.size() + "개 수신(TURN 포함)");
                org.webrtc.PeerConnection.RTCConfiguration rc = new org.webrtc.PeerConnection.RTCConfiguration(iceServers);
                rc.sdpSemantics = org.webrtc.PeerConnection.SdpSemantics.UNIFIED_PLAN;
                rc.continualGatheringPolicy = org.webrtc.PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
                final String[] err = new String[1];
                pc = factory.createPeerConnection(rc, new org.webrtc.PeerConnection.Observer() {
                    public void onSignalingChange(org.webrtc.PeerConnection.SignalingState s) {}
                    public void onIceConnectionChange(org.webrtc.PeerConnection.IceConnectionState s) { postToD1("CAMRTC", "ice " + s);
                        rtcIceConnected = s == org.webrtc.PeerConnection.IceConnectionState.CONNECTED || s == org.webrtc.PeerConnection.IceConnectionState.COMPLETED;
                        if (s == org.webrtc.PeerConnection.IceConnectionState.FAILED || s == org.webrtc.PeerConnection.IceConnectionState.CLOSED) rtcActive = false; }   // ★폰 이탈(백그라운드 등) → 세션 종료(카메라·인코더·TURN 전송 중단)
                    public void onIceConnectionReceivingChange(boolean b) {}
                    public void onIceGatheringChange(org.webrtc.PeerConnection.IceGatheringState s) { if (s == org.webrtc.PeerConnection.IceGatheringState.COMPLETE) gatherDone.countDown(); }
                    public void onIceCandidate(org.webrtc.IceCandidate c) {}
                    public void onIceCandidatesRemoved(org.webrtc.IceCandidate[] c) {}
                    public void onAddStream(org.webrtc.MediaStream m) {}
                    public void onRemoveStream(org.webrtc.MediaStream m) {}
                    public void onDataChannel(org.webrtc.DataChannel d) { metaDc[0] = d; }   // ★폰 meta 채널 — cts 전송용
                    public void onRenegotiationNeeded() {}
                });
                if (pc == null) { postToD1("CAMRTC", "PeerConnection 생성 실패"); rtcActive = false; return; }
                final org.webrtc.PeerConnection fpc = pc;   // 콜백 캡처용 final 별칭

                // 4) setRemoteDescription(폰 offer)
                final java.util.concurrent.CountDownLatch remoteSet = new java.util.concurrent.CountDownLatch(1);
                fpc.setRemoteDescription(new SdpCb(){ public void onSetSuccess(){ remoteSet.countDown(); } public void onSetFailure(String e){ err[0]="setRemote:"+e; remoteSet.countDown(); } },
                    new org.webrtc.SessionDescription(org.webrtc.SessionDescription.Type.OFFER, offerSdp));
                boolean rok = remoteSet.await(6, java.util.concurrent.TimeUnit.SECONDS);
                if (err[0] != null) { postToD1("CAMRTC", err[0]); rtcActive = false; return; }
                postToD1("CAMRTC", "remote set ok=" + rok);

                // 5) 우리 비디오 트랙 부착(offer의 recvonly video ↔ 우리 sendonly)
                org.webrtc.RtpSender sender = fpc.addTrack(videoTrack, java.util.Collections.singletonList("stream0"));
                try {   // ★HD면 높은 max 비트레이트, 아니면 절약(셀룰러/TURN 데이터)
                    org.webrtc.RtpParameters rp = sender.getParameters();
                    if (rp != null && rp.encodings != null && !rp.encodings.isEmpty()) {
                        rp.encodings.get(0).maxBitrateBps = hd ? 6000000 : 2000000;
                        if (hd) rp.encodings.get(0).minBitrateBps = 2000000;   // HD 비트레이트 floor(초기 저해상 램프 최소화)
                        // ★638 씨라7은 SD도 해상도 유지 — 대역 적응으로 런타임 해상도가 바뀌면 하드웨어 인코더가 가장자리에 초록·노랑 세로줄(FB1054/517, 추정). 대신 fps를 낮춤.
                        rp.degradationPreference = (hd || DiLink5Support.isDiLink5()) ? org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                                                      : org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE;   // ★HD=해상도 유지(fps 희생), SD=프레임 유지(해상도 희생=고프레임)
                        sender.setParameters(rp);
                    }
                } catch (Throwable ig) {}

                // 6) createAnswer → setLocalDescription
                final java.util.concurrent.CountDownLatch answerSet = new java.util.concurrent.CountDownLatch(1);
                fpc.createAnswer(new SdpCb(){
                    public void onCreateSuccess(org.webrtc.SessionDescription sdp){
                        fpc.setLocalDescription(new SdpCb(){ public void onSetSuccess(){ answerSet.countDown(); } public void onSetFailure(String e){ err[0]="setLocal:"+e; answerSet.countDown(); } }, sdp);
                    }
                    public void onCreateFailure(String e){ err[0]="createAnswer:"+e; answerSet.countDown(); }
                }, new org.webrtc.MediaConstraints());
                boolean aok = answerSet.await(6, java.util.concurrent.TimeUnit.SECONDS);
                if (err[0] != null) { postToD1("CAMRTC", err[0]); rtcActive = false; return; }
                // ★649 협상 뒤 해상도 유지 재적용 — addTrack 직후 건 degradationPreference가 협상 과정에서 초기화되는 경우 대비(씨라7).
                if (DiLink5Support.isDiLink5()) try {
                    org.webrtc.RtpParameters rp2 = sender.getParameters();
                    if (rp2 != null) {
                        rp2.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION;
                        postToD1("CAMRTC", "협상 후 해상도 유지 재적용 → " + sender.setParameters(rp2));
                    }
                } catch (Throwable ig) { postToD1("CAMRTC", "해상도 유지 재적용 실패: " + ig.getClass().getSimpleName()); }
                postToD1("CAMRTC", "answer created ok=" + aok + " → gathering 대기");

                // 7) ICE gathering 완료 대기(non-trickle) → localDescription에 candidate 포함
                boolean gok = gatherDone.await(15, java.util.concurrent.TimeUnit.SECONDS);
                postToD1("CAMRTC", "gathering done=" + gok);
                org.webrtc.SessionDescription answer = fpc.getLocalDescription();
                if (answer == null) { postToD1("CAMRTC", "answer null"); rtcActive = false; return; }

                // 8) answer를 워커로 POST(폰이 /rtc/answer 폴링해서 가져감)
                postAnswer(sid, answer.description);
                StringBuilder cands = new StringBuilder();   // 실제 candidate IP+typ — 헤드유닛 인터페이스(WiFi/SIM) 도달성 진단
                for (String ln : answer.description.split("\n")) {
                    int ti = ln.indexOf(" typ ");
                    if (ln.startsWith("a=candidate") && ti > 0) {
                        String[] t = ln.trim().split(" ");
                        if (t.length >= 8) cands.append(t[7]).append(":").append(t[4]).append("(").append(t[5]).append(") ");   // typ IP(port)
                    }
                }
                postToD1("CAMRTC", "answer 전송 sid=" + sid + " cands=[ " + cands + "]");

                // 9) 카메라 열고 프레임을 VideoSource로 주입
                if (DiLink5Support.isDiLink5()) {
                    // ★DiLink5(씨라7): bydauto 카메라(bmmcamera) 없음 → QCarCam 백엔드. UYVY→GL텍스처(nativeBindLatestFrame)→glReadPixels→NV21을 latest[0]에 공급([[byd-dilink5-qcarcam-port]]).
                    com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.FrameSink d5sink =
                            (nv21, w, h) -> { meta[0] = w; meta[1] = h; meta[2] = 0; meta[3] = nv21.length; latest[0] = nv21; };
                    com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.Logger d5log = (t, m) -> postToD1("CAMRTC", m);
                    if (view == 5 && avm) {
                        // ★쿼드(4분할) — 전/후/좌/우 4캠 동시 합성(2026-09-16, 운영자 지시). 방향 매핑은 실차 확인 전 임시(FastCamQuadBackend.QUAD_ORDER).
                        postToD1("CAMRTC", "DiLink5 쿼드(4분할) 경로 — camId0~3 동시 합성");
                        quadBk[0] = new com.overdrive.app.camera.dilink5.FastCamQuadBackend();
                        quadBk[0].setRetryEnabled(true);   // ★653 실시간 보기만 빠진 칸 다시 열기(감지 세션은 끔)
                        boolean qok = quadBk[0].start(getApplicationContext(), 20, d5sink, d5log);
                        if (!qok) { postToD1("CAMRTC", "DiLink5 쿼드 시작 실패"); quadBk[0] = null; rtcActive = false; return; }
                        postToD1("CAMRTC", "쿼드 백엔드 사용(4캠 합성 1920x1300)");
                    } else {
                        final int d5Cam = (view == 2 || camId == 2) ? 2 : 0;   // 0=전방 기본, 2=후방(임시 매핑 — 실차 관찰 후 view→cameraId 정밀화)
                        postToD1("CAMRTC", "DiLink5 QCarCam 경로(view=" + view + " camId=" + camId + " → d5cam=" + d5Cam + ")");
                        // ★fast_cam TCP 브리지 우선(BeetleLauncher 1.553 실차 검증 경로) — AVMCamera 클래스는 이 ROM에 없음(死 확인) → 제외.
                        fastcamBk[0] = new com.overdrive.app.camera.dilink5.FastCamTcpBackend(d5Cam);
                        boolean d5ok = fastcamBk[0].start(getApplicationContext(), 20, d5sink, d5log);
                        if (d5ok) { postToD1("CAMRTC", "fast_cam TCP 브리지 백엔드 사용(camId=" + d5Cam + ")"); }
                        else {
                            fastcamBk[0].stop(); fastcamBk[0] = null;
                            postToD1("CAMRTC", "fast_cam TCP 브리지 실패 → QCarCam 폴백");
                            d5cam[0] = new com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend(d5Cam);
                            d5ok = d5cam[0].start(getApplicationContext(), 20, d5sink, d5log);
                            if (!d5ok) { postToD1("CAMRTC", "DiLink5 QCarCam 시작 실패(카메라 미지원/HAL 없음 가능)"); rtcActive = false; return; }
                        }
                    }
                } else if (keepSentry) {
                    // ★791 카메라는 열지 않는다 — 센트리 세션이 받은 최신 프레임을 이 루프의 latest로 넘겨 준다(센트리가 끊기면 3초 뒤 실시간 보기도 끝냄)
                    final Thread feeder = new Thread(() -> {
                        byte[] last = null; long lastNew = System.currentTimeMillis();
                        while (rtcActive && !Thread.currentThread().isInterrupted()) {
                            byte[] t = sentryTapFrame;
                            if (t != null && t != last) { meta[0] = sentryTapW; meta[1] = sentryTapH; meta[3] = t.length; latest[0] = t; last = t; lastNew = System.currentTimeMillis(); }
                            else if (System.currentTimeMillis() - lastNew > 3000) { postToD1("CAMRTC", "센트리 프레임이 3초 넘게 끊겨 실시간 보기를 끝냅니다(다시 켜면 카메라를 따로 엽니다)"); rtcActive = false; break; }
                            try { Thread.sleep(15); } catch (InterruptedException ie) { break; }
                        }
                    }, "rtc-sentry-tap");
                    feeder.setDaemon(true); feeder.start();
                } else {
                    if (bmmLoader == null) { java.io.File jar = new java.io.File("/system/framework/bmmcamera.jar");
                        bmmLoader = new dalvik.system.DexClassLoader(jar.getPath(), getCodeCacheDir().getPath(), "/system/lib64:/system/lib", getClassLoader()); }
                    Class.forName("android.hardware.JNILoader", true, bmmLoader).getMethod("load").invoke(null);
                    String clsName = avm ? "android.hardware.AVMCamera" : "android.hardware.NormalCamera";
                    ncCls = Class.forName(clsName, true, bmmLoader);
                    Class<?> ipcCls = Class.forName(clsName + "$IPreviewCallback", true, bmmLoader);
                    cam = openAvmCam(ncCls, camId, avm, "CAMRTC");
                    if (cam == null) { rtcActive = false; return; }
                    // ★더미 Surface 부착(camDetect와 동일) — view별 크기(쿼드5=2560x1920·파노0=5120x960·단일=1280x960)로 카메라 출력 모드 확정.
                    //   이게 없으면 카메라가 전방 기본 채널로 출력 → ROI 편집기(view5)가 4분할 대신 전방 어안으로 나오던 근본(2026-09-14). 스트림 스레드 동안 참조 유지.
                    android.media.ImageReader rtcDummyIr = null;
                    try {
                        int dw = view == 5 ? 2560 : (view == 0 ? 5120 : 1280);
                        int dh = view == 5 ? 1920 : 960;
                        rtcDummyIr = android.media.ImageReader.newInstance(dw, dh, android.graphics.ImageFormat.YUV_420_888, 2);
                        if (avm) ncCls.getMethod("addPreviewSurface", android.view.Surface.class, int.class).invoke(cam, rtcDummyIr.getSurface(), 0);
                        else ncCls.getMethod("addPreviewSurface", android.view.Surface.class).invoke(cam, rtcDummyIr.getSurface());
                        postToD1("CAMRTC", "더미 Surface 부착 OK view=" + view + " " + dw + "x" + dh);
                    } catch (Throwable t) { postToD1("CAMRTC", "더미 Surface 실패(" + t.getClass().getSimpleName() + ":" + t.getMessage() + ")"); }
                    final android.media.ImageReader rtcDummyKeep = rtcDummyIr;   // 스레드 스코프 참조 유지(GC 방지)
                    Object proxy = java.lang.reflect.Proxy.newProxyInstance(bmmLoader, new Class[]{ipcCls}, (pr, m, a) -> {
                        if ("onPreview".equals(m.getName()) && a != null && a.length >= 6) {
                            try { int size = (int) a[5]; byte[] c = new byte[size]; System.arraycopy((byte[]) a[1], 0, c, 0, Math.min(((byte[]) a[1]).length, size));
                                meta[0] = (int) a[2]; meta[1] = (int) a[3]; meta[2] = (int) a[4]; meta[3] = size; latest[0] = c; } catch (Throwable ig) {}
                        }
                        Class<?> rt = m.getReturnType(); return rt == boolean.class ? Boolean.FALSE : (rt == int.class ? Integer.valueOf(0) : null);
                    });
                    if (rtcDummyKeep != null) { /* keep reference alive for camera surface */ }
                    ncCls.getMethod("setPreviewCallback", ipcCls).invoke(cam, proxy);
                    if (avm) ncCls.getMethod("enablePreviewCallback", int.class).invoke(cam, view);
                    else ncCls.getMethod("enablePreviewCallback").invoke(cam);
                    ncCls.getMethod("startPreview").invoke(cam);
                }

                // ★실시간뷰+감지 통합(2026-08-26) — 이 camRtc 프레임으로 MOG2도 돌려 mot state 발행(폰 폴링). 카메라 한 번 open으로 뷰+감지 동시(배타충돌 없음).
                java.util.List<com.cseini.motiondetect.Roi> drois = new java.util.ArrayList<>();
                try {
                    org.json.JSONArray rarr = params != null ? params.optJSONArray("rois") : null;
                    if (rarr != null) for (int i = 0; i < rarr.length(); i++) {
                        org.json.JSONObject r = rarr.getJSONObject(i);
                        org.json.JSONArray jx = r.getJSONArray("nx"), jy = r.getJSONArray("ny");
                        float[] nx = new float[jx.length()], ny = new float[jy.length()];
                        for (int k = 0; k < nx.length; k++) { nx[k] = (float) jx.getDouble(k); ny[k] = (float) jy.getDouble(k); }
                        drois.add(new com.cseini.motiondetect.Roi(r.optInt("id", i), nx, ny, (float) r.optDouble("minPct", 0.03), r.optLong("sustainMs", 300)));
                    }
                } catch (Throwable ig) {}
                if (!drois.isEmpty()) { clearMotState(); postToD1("CAMRTC", "감지통합 rois=" + drois.size()); }

                // 10) 프레임 주입 루프 — onPreview NV21(실측, 크로마 VU) → NV21Buffer(스왑없이 색 정확) → (>1920이면 다운스케일) → VideoFrame
                long endMs = System.currentTimeMillis() + durationSec * 1000L; byte[] lastFed = null; int fed = 0; long lastCtsSend = 0;
                final String[] lastRes = {""}; long lastStatsMs = 0;
                while (rtcActive && System.currentTimeMillis() < endMs) {
                    long nowMs = System.currentTimeMillis();
                    // ★638 실제 송출 해상도·제한 사유 기록(씨라7 세로줄 FB1054/517 — "처음엔 괜찮다 조금 지나면 줄" = 런타임 해상도 전환 시점 확인용). 바뀔 때만 로그.
                    if (nowMs - lastStatsMs > 5000) {
                        lastStatsMs = nowMs;
                        try {
                            fpc.getStats(rep -> {
                                try {
                                    for (org.webrtc.RTCStats st : rep.getStatsMap().values()) {
                                        if (!"outbound-rtp".equals(st.getType()) || !"video".equals(String.valueOf(st.getMembers().get("kind")))) continue;
                                        java.util.Map<String, Object> mb = st.getMembers();
                                        String res = mb.get("frameWidth") + "x" + mb.get("frameHeight");
                                        if (!res.equals(lastRes[0])) {
                                            postToD1("CAMRTC", "송출 해상도 " + (lastRes[0].isEmpty() ? "" : lastRes[0] + "→") + res + " fps=" + mb.get("framesPerSecond") + " 제한=" + mb.get("qualityLimitationReason") + " enc=" + mb.get("encoderImplementation"));
                                            lastRes[0] = res;
                                        }
                                    }
                                } catch (Throwable ig) {}
                            });
                        } catch (Throwable ig) {}
                    }
                    if (metaDc[0] != null && nowMs - lastCtsSend > 1000 && metaDc[0].state() == org.webrtc.DataChannel.State.OPEN) {   // ★촬영시각(cts) 1Hz 전송 → 뷰어가 실제 영상시각 표시
                        try { byte[] cb = String.valueOf(nowMs).getBytes("UTF-8");
                            metaDc[0].send(new org.webrtc.DataChannel.Buffer(java.nio.ByteBuffer.wrap(cb), false)); lastCtsSend = nowMs; } catch (Throwable ig) {}
                    }
                    byte[] f = latest[0];
                    if (f != null && f != lastFed && meta[0] > 0) {
                        int w = meta[0], h = meta[1];
                        if (lastFed == null) postToD1("CAMRTC", "★첫 프레임 크기 " + w + "x" + h + " (4분할=2560x1920 / 전방단일이면 다른 크기) view=" + view + " avm=" + avm);   // ★진단: camRtc onPreview 실제 프레임 크기 = 4분할인지 판별
                        org.webrtc.NV21Buffer nv = new org.webrtc.NV21Buffer(f, w, h, null);
                        org.webrtc.VideoFrame.Buffer buf = nv;
                        int capW = hd ? 1920 : 960;   // ★HD=1920 고해상도(프레임 희생), SD=960 저해상(고프레임 유지)
                        if (w > capW) {
                            int sw = capW, sh = (h * capW / w) & ~1;
                            buf = nv.cropAndScale(0, 0, w, h, sw, sh);
                            nv.release();
                        }
                        org.webrtc.VideoFrame vf = new org.webrtc.VideoFrame(buf, 0, System.nanoTime());
                        capObs.onFrameCaptured(vf);
                        vf.release();
                        lastFed = f; fed++;
                        // ★같은 프레임으로 MOG2 감지 → mot state 발행(폰 실시간 폴링). 뷰 30fps, 감지는 3프레임당(~10fps).
                        if (!drois.isEmpty()) {
                            dProc++;
                            if (dProc % 3 == 0) {
                                try {
                                    if (dmd == null) { dmd = new com.cseini.motiondetect.MotionDetector(w, h); dmd.setRois(drois); }
                                    // ★같은 role(id)의 ROI가 여러 개면(복제 사분면) role별 최대치로 집계 후 발행 — last-wins로 0% 덮어쓰기 방지
                                    java.util.HashMap<Integer, int[]> agg = new java.util.HashMap<>();  // id -> {maxPct, trig}
                                    for (com.cseini.motiondetect.MotionDetector.Result res : dmd.onFrame(f, android.os.SystemClock.elapsedRealtime())) {
                                        int p = Math.round(res.motionPct * 100);
                                        int[] cur = agg.get(res.roiId);
                                        if (cur == null) agg.put(res.roiId, new int[]{p, res.triggered ? 1 : 0});
                                        else { if (p > cur[0]) cur[0] = p; if (res.triggered) cur[1] = 1; }
                                    }
                                    for (java.util.Map.Entry<Integer, int[]> e : agg.entrySet()) {
                                        relay.putState("mot" + e.getKey(), e.getValue()[1]);
                                        relay.putState("mot" + e.getKey() + "_p", e.getValue()[0]);
                                    }
                                    // ★638 테스트 화면도 주차 경로와 같은 가림 판정(운영자 09-28 "테스트가 실제와 안 맞음") — 옛 킥·사람 진단(09-11 폐기) 제거.
                                    if (tbb == null) {
                                        for (com.cseini.motiondetect.Roi rr : drois) {
                                            if (rr.id != 2) continue;
                                            float mnx=1,mxx=0,mny=1,mxy=0;
                                            for (int k=0;k<rr.nx.length;k++){ mnx=Math.min(mnx,rr.nx[k]); mxx=Math.max(mxx,rr.nx[k]); mny=Math.min(mny,rr.ny[k]); mxy=Math.max(mxy,rr.ny[k]); }
                                            tbb = new int[]{(int)(mnx*w),(int)(mny*h),(int)((mxx-mnx)*w),(int)((mxy-mny)*h)}; break;
                                        }
                                    }
                                    if (tbb != null) { int[] ka = agg.get(2); rtcCover.step(f, w, h, tbb, notify, ka != null ? ka[0] : 0, "CAMRTC"); }
                                } catch (Throwable ig) {}
                            }
                        }
                    }
                    try { Thread.sleep(33); } catch (InterruptedException e) { break; }
                }
                postToD1("CAMRTC", "종료 fed=" + fed);
            } catch (Throwable t) {
                postToD1("CAMRTC", "실패:" + rootMsg(t));
            } finally {
                rtcActive = false; rtcTapsSentry = false; RelayClient.cameraActive = false;
                try { if (dmd != null) { dmd.close(); clearMotState(); } } catch (Throwable ig) {}   // ★감지통합 정리 + mot state 클리어(stream 종료 후 mot=1 잔존 방지)
                try { if (cam != null && ncCls != null) { ncCls.getMethod("stopPreview").invoke(cam); ncCls.getMethod("close").invoke(cam); } } catch (Throwable ig) {}
                try { if (fastcamBk[0] != null) fastcamBk[0].stop(); } catch (Throwable ig) {}   // ★fast_cam TCP 브리지 정지(fast_cam_capture+브리지 프로세스)
                try { if (d5cam[0] != null) d5cam[0].stop(); } catch (Throwable ig) {}   // ★DiLink5 QCarCam 정지(프로세스+GL스레드)
                try { if (quadBk[0] != null) quadBk[0].stop(); } catch (Throwable ig) {}   // ★쿼드 백엔드 정지(4연결+브리지)
                postToD1("CAMRTC", "원격 영상 " + rtcUsage(pc, camRtcT0));   // ★863 원격 영상 데이터 기록
                try { if (pc != null) pc.dispose(); } catch (Throwable ig) {}
                try { if (vsrc != null) vsrc.dispose(); } catch (Throwable ig) {}
                try { if (factory != null) factory.dispose(); } catch (Throwable ig) {}
                SentryManager.get().reevaluate();   // ★실시간 보기가 끝났으니 센트리 재무장 평가(카메라 정리 뒤)
            }
        }, "camrtc"); rtcThread.start();
        return null;
    }

    // answer SDP를 워커에 전달(폰이 /rtc/answer 폴링으로 가져감)
    // ★779 센트리 집에서 재생(10-06 운영자 "R2 비용 싫어 — 무료로, 폰과 헤드유닛 직접") — 폰 offer(데이터 채널만, 영상 트랙 없음)에 answer를 만들어
    //   /rtc/answer?sid로 보내고, 폰이 연 채널로 녹화 조각 mp4를 그대로 보낸다(재압축·서버 저장 없음). 연결은 원격 카메라와 같은 STUN/TURN
    //   (Cloudflare 무료 한도 → A1 coturn). 카메라는 안 건드려 센트리 녹화·감지와 무관하다.
    //   폰 요청 {"get":조각,"id":이벤트} → {"seg":n,"size":N} · 64KB 이진 조각들 · {"done":n}. 색인에 오른(마감된) 조각만, 아니면 {"seg":n,"err":"seg|id|…"}.
    //   한 번에 한 세션(새 요청이 앞 세션을 닫음), 요청·전송 없이 90초면 정리. 명령은 폰 K 서명(일반 명령 검증) — 녹화 영상이라 필수.
    private final Object sentryRtcLock = new Object();
    private org.webrtc.PeerConnection sentryRtcPc;
    private org.webrtc.PeerConnectionFactory sentryRtcFactory;
    private int sentryRtcGen;
    private volatile long sentryRtcLastMs;

    private String sentryRtc(org.json.JSONObject params) {
        final String sid = params != null ? params.optString("sid", "") : "";
        final String offerSdp = params != null ? params.optString("sdp", "") : "";
        if (sid.isEmpty() || offerSdp.isEmpty()) return "sid·sdp 없음";
        final int gen;
        synchronized (sentryRtcLock) { gen = ++sentryRtcGen; }
        new Thread(() -> runSentryRtc(gen, sid, offerSdp), "sentry-rtc").start();
        return null;
    }

    private boolean sentryRtcCurrent(int gen) {
        synchronized (sentryRtcLock) { return sentryRtcGen == gen; }
    }

    // ★863 원격 영상 데이터 기록(운영자 10-10 "링크락이 쓴 양만") — 연결을 닫기 직전 WebRTC 통계에서 실제로 보낸 바이트와 경로를 읽는다.
    //   경로 relay = 서버(TURN) 중계, host·srflx·prflx = 폰과 직접. 통계를 0.8초 안에 못 받으면 "?"로 남긴다.
    private volatile long camRtcT0 = 0, sentryRtcT0 = 0;
    private String rtcUsage(org.webrtc.PeerConnection pc, long t0) {
        final long[] b = {-1}; final String[] path = {"?"};
        if (pc != null) {
            final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
            try {
                pc.getStats(rep -> {
                    try {
                        java.util.Map<String, org.webrtc.RTCStats> m = rep.getStatsMap();
                        for (org.webrtc.RTCStats st : m.values()) {
                            Object v = st.getMembers().get("bytesSent");
                            if ("transport".equals(st.getType()) && v instanceof Number) b[0] = Math.max(b[0], ((Number) v).longValue());
                            if ("candidate-pair".equals(st.getType()) && Boolean.TRUE.equals(st.getMembers().get("nominated"))) {
                                org.webrtc.RTCStats lc = m.get(String.valueOf(st.getMembers().get("localCandidateId")));
                                if (lc != null) path[0] = String.valueOf(lc.getMembers().get("candidateType"));
                            }
                        }
                    } catch (Throwable ig) {} finally { l.countDown(); }
                });
                l.await(800, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Throwable ig) {}
        }
        long sec = t0 > 0 ? (System.currentTimeMillis() - t0) / 1000 : -1;
        return "보낸 양 " + (b[0] < 0 ? "?" : String.format(java.util.Locale.US, "%.1fMB", b[0] / 1048576.0)) + " · " + sec + "초 · 경로 " + path[0] + ("relay".equals(path[0]) ? "(서버 중계)" : "") + " · 망 " + netSummary();
    }

    // 콜백(시그널링 스레드) 안에서 dispose하면 멈출 수 있어 항상 별도 스레드에서 부른다
    private void closeSentryRtc(String why) {
        org.webrtc.PeerConnection pc;
        org.webrtc.PeerConnectionFactory f;
        synchronized (sentryRtcLock) {
            pc = sentryRtcPc; f = sentryRtcFactory;
            sentryRtcPc = null; sentryRtcFactory = null;
        }
        if (pc == null && f == null) return;
        postToD1("SENTRYRTC", "원격 재생 " + rtcUsage(pc, sentryRtcT0));   // ★863 원격 영상 데이터 기록
        try { if (pc != null) pc.dispose(); } catch (Throwable t) { postToD1("SENTRYRTC", "연결 정리 오류 " + rootMsg(t)); }
        try { if (f != null) f.dispose(); } catch (Throwable t) { postToD1("SENTRYRTC", "팩토리 정리 오류 " + rootMsg(t)); }
        postToD1("SENTRYRTC", "세션 정리 — " + why);
    }

    private void runSentryRtc(int gen, String sid, String offerSdp) {
        closeSentryRtc("새 요청");
        org.webrtc.PeerConnectionFactory factory = null;
        org.webrtc.PeerConnection pc = null;
        boolean owned = false;
        try {
            synchronized (SnifferService.class) {
                if (!rtcInited) {
                    org.webrtc.PeerConnectionFactory.initialize(
                        org.webrtc.PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions());
                    rtcInited = true;
                }
            }
            // 데이터 채널만이라 영상 코덱은 필요 없다. 오디오 모듈은 camRtc와 같이 JavaADM(LegacyADM은 이 헤드유닛서 AudioFlinger SIGABRT)
            factory = org.webrtc.PeerConnectionFactory.builder()
                .setAudioDeviceModule(org.webrtc.audio.JavaAudioDeviceModule.builder(getApplicationContext())
                    .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
                    .createAudioDeviceModule())
                .createPeerConnectionFactory();
            java.util.List<org.webrtc.PeerConnection.IceServer> iceServers = fetchIceServers();
            if (iceServers == null || iceServers.isEmpty()) {
                iceServers = new java.util.ArrayList<>();
                iceServers.add(org.webrtc.PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
                postToD1("SENTRYRTC", "iceServers 수신 실패 → STUN만");
            }
            org.webrtc.PeerConnection.RTCConfiguration rc = new org.webrtc.PeerConnection.RTCConfiguration(iceServers);
            rc.sdpSemantics = org.webrtc.PeerConnection.SdpSemantics.UNIFIED_PLAN;
            rc.continualGatheringPolicy = org.webrtc.PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
            final java.util.concurrent.CountDownLatch gatherDone = new java.util.concurrent.CountDownLatch(1);
            pc = factory.createPeerConnection(rc, new org.webrtc.PeerConnection.Observer() {
                public void onSignalingChange(org.webrtc.PeerConnection.SignalingState s) {}
                public void onIceConnectionChange(org.webrtc.PeerConnection.IceConnectionState s) {
                    postToD1("SENTRYRTC", "ice " + s);
                    if (s == org.webrtc.PeerConnection.IceConnectionState.FAILED || s == org.webrtc.PeerConnection.IceConnectionState.CLOSED)
                        new Thread(() -> { if (sentryRtcCurrent(gen)) closeSentryRtc("ice " + s); }).start();
                }
                public void onIceConnectionReceivingChange(boolean b) {}
                public void onIceGatheringChange(org.webrtc.PeerConnection.IceGatheringState s) {
                    if (s == org.webrtc.PeerConnection.IceGatheringState.COMPLETE) gatherDone.countDown();
                }
                public void onIceCandidate(org.webrtc.IceCandidate c) {}
                public void onIceCandidatesRemoved(org.webrtc.IceCandidate[] c) {}
                public void onAddStream(org.webrtc.MediaStream m) {}
                public void onRemoveStream(org.webrtc.MediaStream m) {}
                public void onDataChannel(org.webrtc.DataChannel d) { attachSentryFileChannel(gen, d); }
                public void onRenegotiationNeeded() {}
            });
            if (pc == null) throw new IllegalStateException("PeerConnection 생성 실패");
            synchronized (sentryRtcLock) {
                if (sentryRtcGen == gen) { sentryRtcPc = pc; sentryRtcFactory = factory; owned = true; sentryRtcT0 = System.currentTimeMillis(); }
            }
            if (!owned) throw new IllegalStateException("그새 새 요청이 와서 이 세션은 버림");
            final org.webrtc.PeerConnection fpc = pc;
            final String[] err = new String[1];
            final java.util.concurrent.CountDownLatch remoteSet = new java.util.concurrent.CountDownLatch(1);
            fpc.setRemoteDescription(new SdpCb() {
                public void onSetSuccess() { remoteSet.countDown(); }
                public void onSetFailure(String e) { err[0] = "setRemote:" + e; remoteSet.countDown(); }
            }, new org.webrtc.SessionDescription(org.webrtc.SessionDescription.Type.OFFER, offerSdp));
            if (!remoteSet.await(6, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("setRemote 시간 초과");
            if (err[0] != null) throw new IllegalStateException(err[0]);
            final java.util.concurrent.CountDownLatch answerSet = new java.util.concurrent.CountDownLatch(1);
            fpc.createAnswer(new SdpCb() {
                public void onCreateSuccess(org.webrtc.SessionDescription sdp) {
                    fpc.setLocalDescription(new SdpCb() {
                        public void onSetSuccess() { answerSet.countDown(); }
                        public void onSetFailure(String e) { err[0] = "setLocal:" + e; answerSet.countDown(); }
                    }, sdp);
                }
                public void onCreateFailure(String e) { err[0] = "createAnswer:" + e; answerSet.countDown(); }
            }, new org.webrtc.MediaConstraints());
            if (!answerSet.await(6, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("answer 시간 초과");
            if (err[0] != null) throw new IllegalStateException(err[0]);
            boolean gok = gatherDone.await(15, java.util.concurrent.TimeUnit.SECONDS);
            org.webrtc.SessionDescription answer = fpc.getLocalDescription();
            if (answer == null) throw new IllegalStateException("answer 없음");
            // 응답은 서버를 거쳐 가므로 페어키로 서명해 끝 줄에 붙인다(폰이 이 줄을 떼고 확인). 키가 없으면 보내지 않는다
            String sdpOut = answer.description.endsWith("\n") ? answer.description : answer.description + "\r\n";
            String sig = relay != null ? relay.signWithPairKey("SENTRYRTC|" + sid + "|" + sdpOut) : null;
            if (sig == null) throw new IllegalStateException("페어키 없음 — 응답에 서명할 수 없어 보내지 않음");
            postAnswer(sid, sdpOut + "a=x-lk-sig:" + sig + "\r\n");
            sentryRtcLastMs = android.os.SystemClock.elapsedRealtime();
            postToD1("SENTRYRTC", "answer 전송 sid=" + sid + " gathering=" + gok);
            while (sentryRtcCurrent(gen)) {
                Thread.sleep(5000);
                synchronized (sentryRtcLock) { if (sentryRtcPc != fpc) return; }
                if (android.os.SystemClock.elapsedRealtime() - sentryRtcLastMs > 90_000L) { closeSentryRtc("90초 동안 요청 없음"); return; }
            }
        } catch (Throwable t) {
            postToD1("SENTRYRTC", "세션 실패 — " + rootMsg(t));
            if (owned) {
                if (sentryRtcCurrent(gen)) closeSentryRtc("세션 실패");
            } else {
                try { if (pc != null) pc.dispose(); } catch (Throwable ig) { postToD1("SENTRYRTC", "연결 정리 오류 " + rootMsg(ig)); }
                try { if (factory != null) factory.dispose(); } catch (Throwable ig) { postToD1("SENTRYRTC", "팩토리 정리 오류 " + rootMsg(ig)); }
            }
        }
    }

    private void attachSentryFileChannel(int gen, org.webrtc.DataChannel dc) {
        final java.util.concurrent.ExecutorService sender = java.util.concurrent.Executors.newSingleThreadExecutor();
        final Object flow = new Object();
        postToD1("SENTRYRTC", "채널 열림 요청 " + dc.label());
        dc.registerObserver(new org.webrtc.DataChannel.Observer() {
            public void onBufferedAmountChange(long previous) { synchronized (flow) { flow.notifyAll(); } }
            public void onStateChange() {
                org.webrtc.DataChannel.State s = dc.state();
                postToD1("SENTRYRTC", "채널 " + s);
                if (s == org.webrtc.DataChannel.State.CLOSED) {
                    sender.shutdownNow();
                    new Thread(() -> { if (sentryRtcCurrent(gen)) closeSentryRtc("채널 닫힘"); }).start();
                }
            }
            public void onMessage(org.webrtc.DataChannel.Buffer b) {
                if (b.binary) return;
                byte[] raw = new byte[b.data.remaining()];
                b.data.get(raw);
                sentryRtcLastMs = android.os.SystemClock.elapsedRealtime();
                try {
                    org.json.JSONObject req = new org.json.JSONObject(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
                    final String id = req.optString("id", "");
                    final int seg = req.optInt("get", -1);
                    final String q = req.optString("q", "orig");   // ★화질 선택(orig·mid·low) — 센트리 세션이 추가
                    final boolean mf = req.optBoolean("mf", false);   // 784: 목차(moov)를 앞으로 옮겨(빠른 시작) 보냄 — 폰이 받는 중에 재생
                    if (req.optBoolean("all", false)) sender.execute(() -> sendSentryEvent(dc, flow, id, q));   // ★이벤트 전체(합쳐서) — 센트리 세션 추가
                    else sender.execute(() -> sendSentrySegment(dc, flow, id, seg, q, mf));
                } catch (Throwable t) { postToD1("SENTRYRTC", "요청 형식 오류 " + rootMsg(t)); }
            }
        });
    }

    private void sendSentryText(org.webrtc.DataChannel dc, org.json.JSONObject o) {
        dc.send(new org.webrtc.DataChannel.Buffer(java.nio.ByteBuffer.wrap(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), false));
    }

    // 파일 하나를 64KB씩 보낸다(대기열 4MB 상한). 보낸 바이트 수 반환 — 조각 보내기와 이벤트 전체 보내기가 같이 쓴다
    private long streamSentryFile(org.webrtc.DataChannel dc, Object flow, java.io.File f) throws Exception {
        return streamSentryRange(dc, flow, f, 0, f.length());
    }

    // 파일의 [from, to) 구간을 64KB씩 보낸다 — 784 빠른 시작은 목차 앞·뒤 구간으로 나눠 부른다
    private long streamSentryRange(org.webrtc.DataChannel dc, Object flow, java.io.File f, long from, long to) throws Exception {
        long sent = 0;
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(f, "r")) {
            in.seek(from);
            byte[] buf = new byte[64 * 1024];
            while (from + sent < to) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, to - from - sent));
                if (n <= 0) throw new java.io.IOException("파일이 예상보다 짧음(" + (from + sent) + "/" + to + ")");
                sendSentryChunk(dc, flow, buf, 0, n);
                sent += n;
            }
        }
        return sent;
    }

    // 64KB 이하 덩어리 하나 — 보내기 대기열이 4MB를 넘으면 줄 때까지 기다린다(libwebrtc는 16MB를 넘기면 채널을 닫음)
    private void sendSentryChunk(org.webrtc.DataChannel dc, Object flow, byte[] b, int off, int n) throws Exception {
        synchronized (flow) {
            long waitStart = android.os.SystemClock.elapsedRealtime();
            while (dc.bufferedAmount() > 4L * 1024 * 1024) {
                if (dc.state() != org.webrtc.DataChannel.State.OPEN) throw new java.io.IOException("채널 닫힘");
                if (android.os.SystemClock.elapsedRealtime() - waitStart > 60_000L) throw new java.io.IOException("60초 동안 못 보냄");
                flow.wait(500);
            }
        }
        if (dc.state() != org.webrtc.DataChannel.State.OPEN) throw new java.io.IOException("채널 닫힘");
        if (!dc.send(new org.webrtc.DataChannel.Buffer(java.nio.ByteBuffer.wrap(b, off, n), true))) throw new java.io.IOException("보내기 실패");
        sentryRtcLastMs = android.os.SystemClock.elapsedRealtime();
    }

    // ★이벤트 전체를 영상 하나로 합쳐 보낸다(센트리 세션 추가, 요청 {"all":true,"id":…,"q":…}). 합치기(필요하면 조각별 재압축 포함)는 차에서 하고 그동안 {"all":true,"state":"preparing"}.
    //   응답 {"all":true,"size":N,"q":…} → 64KB 이진 → {"all":true,"done":true}. 실패는 {"all":true,"err":"id|q|build|send"}
    private void sendSentryEvent(org.webrtc.DataChannel dc, Object flow, String id, String q) {
        try {
            com.cseini.bydsniffer.sentry.SentryManager m = com.cseini.bydsniffer.sentry.SentryManager.get();
            if (m == null || m.index() == null) { sendSentryText(dc, new org.json.JSONObject().put("all", true).put("err", "build")); return; }
            sendSentryText(dc, new org.json.JSONObject().put("all", true).put("state", "preparing").put("q", q));
            if (!"orig".equals(q) && !q.isEmpty()) { sendSentryText(dc, new org.json.JSONObject().put("all", true).put("err", "q")); return; }   // 화질 선택(재압축)은 없어졌다 — 원본만
            java.io.File f;
            try {
                f = m.combinedEvent(id, () -> sentryRtcLastMs = android.os.SystemClock.elapsedRealtime());
            } catch (IllegalArgumentException e) {
                sendSentryText(dc, new org.json.JSONObject().put("all", true).put("err", "id"));
                return;
            } catch (Throwable t) {
                postToD1("SENTRYRTC", "이벤트 합치기 실패 " + id + " q=" + q + " — " + rootMsg(t));
                sendSentryText(dc, new org.json.JSONObject().put("all", true).put("err", "build"));
                return;
            }
            sentryRtcLastMs = android.os.SystemClock.elapsedRealtime();
            final long t0 = android.os.SystemClock.elapsedRealtime();
            sendSentryText(dc, new org.json.JSONObject().put("all", true).put("size", f.length()).put("q", q));
            long sent = streamSentryFile(dc, flow, f);
            sendSentryText(dc, new org.json.JSONObject().put("all", true).put("done", true));
            long ms = Math.max(1, android.os.SystemClock.elapsedRealtime() - t0);
            postToD1("SENTRYRTC", "이벤트 전체 보냄 " + id + " q=" + q + " " + String.format(java.util.Locale.US, "%.1fMB · %.1f초 · %.1fMbps", sent / 1048576.0, ms / 1000.0, sent * 8.0 / 1e6 / (ms / 1000.0)));
        } catch (Throwable t) {
            postToD1("SENTRYRTC", "이벤트 전체 보내기 실패 " + id + " — " + rootMsg(t));
            try { sendSentryText(dc, new org.json.JSONObject().put("all", true).put("err", "send")); } catch (Throwable ig) { postToD1("SENTRYRTC", "실패 알림도 못 보냄 " + rootMsg(ig)); }
        }
    }

    // 조각 하나를 64KB씩 — 보내기 대기열이 4MB를 넘으면 줄 때까지 기다린다(libwebrtc는 16MB를 넘기면 채널을 닫음)
    //   784 mf(빠른 시작): 녹화 조각은 목차(moov)가 파일 끝이라 폰 플레이어가 받는 중에 재생하지 못한다(10-06 맥 시험: 목차를 먼저 보내도
    //   영상 데이터 요청이 다 차야 재생 시작, 목차가 앞인 파일은 0.4초에 재생 시작·끊김 없음) → 파일은 그대로 두고 보내는 순서만
    //   [ftyp][새 목차(덩어리 25프레임 단위·위치는 새 목차 길이만큼 밈)][나머지 앞부분][목차 뒤 나머지]로 바꿔 목차가 앞인 mp4로 보낸다
    //   (qt-faststart와 같은 방식, 프레임 데이터는 그대로). 머리말 "fs":true, size는 바뀐 길이. 목차가 이미 앞이거나 못 찾거나 고칠 수 없으면 그대로.
    private void sendSentrySegment(org.webrtc.DataChannel dc, Object flow, String id, int seg, String q, boolean mf) {
        try {
            com.cseini.bydsniffer.sentry.SentryManager m = com.cseini.bydsniffer.sentry.SentryManager.get();
            com.cseini.bydsniffer.sentry.SentryIndex ix = m != null ? m.index() : null;
            String bad = null;
            java.io.File f = null;
            if (ix == null) bad = "준비 안 됨";
            else if (!com.cseini.bydsniffer.sentry.SentryIndex.isValidId(id)) bad = "id";
            else {
                com.cseini.bydsniffer.sentry.SentryIndex.EventMeta meta = ix.read(id);
                boolean listed = false;
                if (meta != null) for (com.cseini.bydsniffer.sentry.SentryIndex.Segment s : meta.segments) if (s.n == seg) { listed = true; break; }
                if (!listed) bad = "seg";   // 녹화 중인 조각(목차 없음)은 마감 전이라 색인에 없다
                else {
                    f = ix.segmentFile(id, seg);
                    if (!f.isFile()) bad = "seg";
                }
            }
            if (bad != null) {
                sendSentryText(dc, new org.json.JSONObject().put("seg", seg).put("err", bad));
                postToD1("SENTRYRTC", "요청 거절 " + id + " seg " + seg + " — " + bad);
                return;
            }
            if (!"orig".equals(q) && !q.isEmpty()) {   // 화질 선택(재압축)은 없어졌다 — 원본만. 조용히 무시하지 않고 명시적으로 거부
                sendSentryText(dc, new org.json.JSONObject().put("seg", seg).put("err", "q"));
                return;
            }
            final long len = f.length();
            final long t0 = android.os.SystemClock.elapsedRealtime();
            final long[] mv = mf ? mp4MoovAfterMdat(f) : null;   // {목차 위치, 목차 길이, 끼울 자리(ftyp 끝)}
            final byte[] moov = mv != null ? fastStartMoov(f, mv) : null;
            final long outLen = moov != null ? len - mv[1] + moov.length : len;   // 새 목차는 덩어리 표가 늘어 조금 길다
            org.json.JSONObject hdr = new org.json.JSONObject().put("seg", seg).put("size", outLen).put("q", q);
            if (moov != null) hdr.put("fs", true);
            sendSentryText(dc, hdr);
            long sent;
            if (moov == null) {
                sent = streamSentryFile(dc, flow, f);
            } else {
                sent = streamSentryRange(dc, flow, f, 0, mv[2]);
                for (int o = 0; o < moov.length; o += 64 * 1024) {
                    int n = Math.min(64 * 1024, moov.length - o);
                    sendSentryChunk(dc, flow, moov, o, n);
                    sent += n;
                }
                sent += streamSentryRange(dc, flow, f, mv[2], mv[0]) + streamSentryRange(dc, flow, f, mv[0] + mv[1], len);
            }
            sendSentryText(dc, new org.json.JSONObject().put("done", seg));
            long ms = Math.max(1, android.os.SystemClock.elapsedRealtime() - t0);
            postToD1("SENTRYRTC", "보냄 " + id + " seg " + seg + (moov != null ? " 빠른 시작" : "") + " " + String.format(java.util.Locale.US, "%.1fMB · %.1f초 · %.1fMbps", sent / 1048576.0, ms / 1000.0, sent * 8.0 / 1e6 / (ms / 1000.0)));
        } catch (Throwable t) {
            postToD1("SENTRYRTC", "보내기 실패 " + id + " seg " + seg + " — " + rootMsg(t));
            try { sendSentryText(dc, new org.json.JSONObject().put("seg", seg).put("err", "send")); } catch (Throwable ig) { postToD1("SENTRYRTC", "실패 알림도 못 보냄 " + rootMsg(ig)); }
        }
    }

    // mp4 최상위 상자를 차례로 훑어 목차(moov)가 영상 데이터(mdat) 뒤에 있으면 {위치, 길이, 끼울 자리(맨 앞 ftyp 끝, 없으면 0)}, 아니면 null(그대로 보내면 됨)
    private long[] mp4MoovAfterMdat(java.io.File f) {
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(f, "r")) {
            final long len = in.length();
            long off = 0, insertAt = 0;
            boolean mdatSeen = false;
            byte[] h = new byte[8];
            while (off + 8 <= len) {
                in.seek(off);
                in.readFully(h);
                long size = ((h[0] & 0xFFL) << 24) | ((h[1] & 0xFFL) << 16) | ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
                String type = new String(h, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
                if (size == 1) {
                    if (off + 16 > len) break;
                    size = in.readLong();
                } else if (size == 0) size = len - off;
                if (size < 8 || off + size > len) {
                    postToD1("SENTRYRTC", "목차 위치 못 찾음 " + f.getName() + " — 상자 " + type + " 크기 " + size + " @" + off + "/" + len + " → 그대로 보냄");
                    return null;
                }
                if (off == 0 && "ftyp".equals(type)) insertAt = size;
                if ("moov".equals(type)) return mdatSeen ? new long[]{off, size, insertAt} : null;
                if ("mdat".equals(type)) mdatSeen = true;
                off += size;
            }
            postToD1("SENTRYRTC", "목차 없음 " + f.getName() + " → 그대로 보냄");
        } catch (Exception e) {
            postToD1("SENTRYRTC", "목차 위치 읽기 실패 " + f.getName() + " — " + rootMsg(e) + " → 그대로 보냄");
        }
        return null;
    }

    // 빠른 시작용 목차 — 원래 목차를 읽어 fastStartMoovOf로 고친다. 못 하면 null(그대로 보냄)
    private byte[] fastStartMoov(java.io.File f, long[] mv) {
        if (mv[1] > 16L << 20) {
            postToD1("SENTRYRTC", "목차가 너무 큼(" + mv[1] + ") " + f.getName() + " → 그대로 보냄");
            return null;
        }
        byte[] m = new byte[(int) mv[1]];
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(f, "r")) {
            in.seek(mv[0]);
            in.readFully(m);
        } catch (Exception e) {
            postToD1("SENTRYRTC", "목차 읽기 실패 " + f.getName() + " — " + rootMsg(e) + " → 그대로 보냄");
            return null;
        }
        byte[] out = fastStartMoovOf(m, mv[0]);
        if (out == null) postToD1("SENTRYRTC", "목차를 고칠 수 없음 " + f.getName() + " → 그대로 보냄");
        return out;
    }

    static final int FS_CHUNK_FRAMES = 25;

    // 목차를 [끼울 자리](ftyp 뒤)로 옮길 때의 새 목차 — 덩어리 표(stsc·stco)를 25프레임 단위로 나누고 덩어리 위치를 새 목차 길이만큼 민다.
    //   헤드유닛 MediaMuxer는 영상만 있으면 조각 전체를 덩어리 하나로 쓰는데(10-06 확인: 993프레임 1덩어리) 아이폰 플레이어는 덩어리를 다 받아야
    //   쓰기 시작해, 목차만 앞으로 옮기면 여전히 다 받은 뒤 재생됐다(맥 시험). 프레임 데이터는 그대로, 표만 바꾼다.
    //   dataEnd(원래 목차 위치)보다 뒤를 가리키는 덩어리가 있거나 형식이 이상하면 null
    static byte[] fastStartMoovOf(byte[] moov, long dataEnd) {
        for (boolean wide : new boolean[]{false, true}) {
            byte[] a = rebuildMoovBoxes(moov, 0, moov.length, 0, dataEnd, wide);   // 1차: 새 길이만 알면 됨
            if (a == null) continue;
            byte[] b = rebuildMoovBoxes(moov, 0, moov.length, a.length, dataEnd, wide);   // 2차: 그 길이만큼 민 위치로
            if (b != null && b.length == a.length) return b;
        }
        return null;
    }

    // 상자 목록 [from, to)를 다시 쓴다 — moov·trak·mdia·minf는 안으로 들어가고 stbl은 덩어리 표를 새로 만든다. 그 밖은 그대로 복사
    static byte[] rebuildMoovBoxes(byte[] m, int from, int to, long delta, long dataEnd, boolean wide) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(m);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int p = from;
        while (p + 8 <= to) {
            long size = bb.getInt(p) & 0xFFFFFFFFL;
            String type = new String(m, p + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int hdr = 8;
            if (size == 1) {
                if (p + 16 > to) return null;
                size = bb.getLong(p + 8);
                hdr = 16;
            } else if (size == 0) size = to - p;
            if (size < hdr || p + size > to) return null;
            int end = (int) (p + size);
            byte[] body = null;
            if (type.equals("moov") || type.equals("trak") || type.equals("mdia") || type.equals("minf")) {
                body = rebuildMoovBoxes(m, p + hdr, end, delta, dataEnd, wide);
                if (body == null) return null;
            } else if (type.equals("stbl")) {
                body = rebuildStbl(m, p + hdr, end, delta, dataEnd, wide);
                if (body == null) return null;
            }
            if (body == null) out.write(m, p, end - p);
            else writeMp4Box(out, type, body);
            p = end;
        }
        return p == to ? out.toByteArray() : null;
    }

    static void writeMp4Box(java.io.ByteArrayOutputStream out, String type, byte[] body) {
        int n = 8 + body.length;
        out.write(n >>> 24); out.write(n >>> 16); out.write(n >>> 8); out.write(n);
        out.write(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, 4);
        out.write(body, 0, body.length);
    }

    // stbl 안 — 프레임 크기(stsz)·원래 덩어리 표(stsc)·위치(stco/co64)로 프레임 위치를 다시 셈해 덩어리를 25프레임 이하로 나눈다
    static byte[] rebuildStbl(byte[] m, int from, int to, long delta, long dataEnd, boolean wide) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(m);
        java.util.List<int[]> kids = new java.util.ArrayList<>();   // {시작, 끝, 머리 길이}
        int stsz = -1, stsc = -1, stco = -1;
        boolean co64 = false;
        int p = from;
        while (p + 8 <= to) {
            long size = bb.getInt(p) & 0xFFFFFFFFL;
            String type = new String(m, p + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int hdr = 8;
            if (size == 1) {
                if (p + 16 > to) return null;
                size = bb.getLong(p + 8);
                hdr = 16;
            } else if (size == 0) size = to - p;
            if (size < hdr || p + size > to) return null;
            int k = kids.size();
            kids.add(new int[]{p, (int) (p + size), hdr});
            if (type.equals("stsz")) stsz = k;
            else if (type.equals("stsc")) stsc = k;
            else if (type.equals("stco")) stco = k;
            else if (type.equals("co64")) { stco = k; co64 = true; }
            p += (int) size;
        }
        if (p != to || stsz < 0 || stsc < 0 || stco < 0) return null;
        int[] z = kids.get(stsz);
        int zc = z[0] + z[2] + 4;
        if (zc + 8 > z[1]) return null;
        long fixed = bb.getInt(zc) & 0xFFFFFFFFL, count = bb.getInt(zc + 4) & 0xFFFFFFFFL;
        final int sizesAt = zc + 8;
        if (count > 5_000_000 || (fixed == 0 && sizesAt + count * 4 > z[1])) return null;
        int[] o = kids.get(stco);
        int oc = o[0] + o[2] + 4;
        if (oc + 4 > o[1]) return null;
        long nChunks = bb.getInt(oc) & 0xFFFFFFFFL;
        oc += 4;
        if (nChunks > 5_000_000 || oc + nChunks * (co64 ? 8 : 4) > o[1]) return null;
        int[] s = kids.get(stsc);
        int sc = s[0] + s[2] + 4;
        if (sc + 4 > s[1]) return null;
        long nRuns = bb.getInt(sc) & 0xFFFFFFFFL;
        sc += 4;
        if (nRuns == 0 || nRuns > 5_000_000 || sc + nRuns * 12 > s[1]) return null;
        java.util.List<long[]> chunks = new java.util.ArrayList<>();   // {새 덩어리 원래 위치, 프레임 수, 설명 번호}
        long sample = 0;
        int run = 0;
        for (long ci = 0; ci < nChunks; ci++) {
            while (run + 1 < nRuns && (bb.getInt(sc + (run + 1) * 12) & 0xFFFFFFFFL) <= ci + 1) run++;
            long spc = bb.getInt(sc + run * 12 + 4) & 0xFFFFFFFFL, sdi = bb.getInt(sc + run * 12 + 8) & 0xFFFFFFFFL;
            long off = co64 ? bb.getLong(oc + (int) ci * 8) : (bb.getInt(oc + (int) ci * 4) & 0xFFFFFFFFL);
            for (long k = 0; k < spc; k += FS_CHUNK_FRAMES) {
                long n = Math.min(FS_CHUNK_FRAMES, spc - k);
                if (off >= dataEnd) return null;
                chunks.add(new long[]{off, n, sdi});
                for (long j = 0; j < n; j++) {
                    if (sample >= count) return null;
                    off += fixed != 0 ? fixed : (bb.getInt(sizesAt + (int) sample * 4) & 0xFFFFFFFFL);
                    sample++;
                }
            }
        }
        if (sample != count) return null;
        java.io.ByteArrayOutputStream runs = new java.io.ByteArrayOutputStream();
        int nNewRuns = 0;
        long lastN = -1, lastSdi = -1;
        for (int i = 0; i < chunks.size(); i++) {
            long[] c = chunks.get(i);
            if (c[1] == lastN && c[2] == lastSdi) continue;
            writeU32(runs, i + 1); writeU32(runs, c[1]); writeU32(runs, c[2]);
            lastN = c[1]; lastSdi = c[2];
            nNewRuns++;
        }
        java.io.ByteArrayOutputStream stscBody = new java.io.ByteArrayOutputStream();
        writeU32(stscBody, 0);
        writeU32(stscBody, nNewRuns);
        byte[] rb = runs.toByteArray();
        stscBody.write(rb, 0, rb.length);
        java.io.ByteArrayOutputStream stcoBody = new java.io.ByteArrayOutputStream();
        writeU32(stcoBody, 0);
        writeU32(stcoBody, chunks.size());
        for (long[] c : chunks) {
            long v = c[0] + delta;
            if (wide) { writeU32(stcoBody, v >>> 32); writeU32(stcoBody, v); }
            else {
                if (v > 0xFFFFFFFFL) return null;
                writeU32(stcoBody, v);
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int k = 0; k < kids.size(); k++) {
            int[] c = kids.get(k);
            if (k == stsc) writeMp4Box(out, "stsc", stscBody.toByteArray());
            else if (k == stco) writeMp4Box(out, wide ? "co64" : "stco", stcoBody.toByteArray());
            else out.write(m, c[0], c[1] - c[0]);
        }
        return out.toByteArray();
    }

    static void writeU32(java.io.ByteArrayOutputStream out, long v) {
        out.write((int) (v >>> 24)); out.write((int) (v >>> 16)); out.write((int) (v >>> 8)); out.write((int) v);
    }

    private void postAnswer(String sid, String sdp) {
        java.net.HttpURLConnection c = null;
        try {
            String vin = prefs != null ? prefs.getString(KEY_VIN, "") : "";
            String url = "https://byd.cseini.co.kr/rtc/answer?vin=" + java.net.URLEncoder.encode(vin, "UTF-8")
                    + "&sid=" + java.net.URLEncoder.encode(sid, "UTF-8") + "&auth=" + BuildConfig.RELAY_SECRET;
            byte[] body = sdp.getBytes("UTF-8");
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setDoOutput(true); c.setRequestMethod("POST"); c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setFixedLengthStreamingMode(body.length); c.setRequestProperty("Content-Type", "application/sdp");
            try (java.io.OutputStream o = c.getOutputStream()) { o.write(body); }
            int rc = c.getResponseCode();
            try (java.io.InputStream in = rc < 400 ? c.getInputStream() : c.getErrorStream()) { if (in != null) { byte[] b = new byte[256]; while (in.read(b) > 0) {} } } catch (Throwable ig) {}
        } catch (Throwable ig) { postToD1("CAMRTC", "answer POST 실패:" + rootMsg(ig)); }
        finally { try { if (c != null) c.disconnect(); } catch (Throwable ig) {} }
    }

    private boolean postFrameToWorker(byte[] jpg, int camId) { return postFrameToWorker(jpg, camId, "c" + camId); }
    private boolean postFrameToWorker(byte[] jpg, int camId, String tag) {
        java.net.HttpURLConnection c = null;
        try {
            String vin = prefs != null ? prefs.getString(KEY_VIN, "") : "";
            String url = "https://byd.cseini.co.kr/relay/camframe?vin=" + java.net.URLEncoder.encode(vin, "UTF-8")
                    + "&cam=" + camId + "&tag=" + java.net.URLEncoder.encode(tag, "UTF-8") + "&cts=" + System.currentTimeMillis() + "&auth=" + BuildConfig.RELAY_SECRET;
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setDoOutput(true); c.setRequestMethod("POST"); c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setFixedLengthStreamingMode(jpg.length); c.setRequestProperty("Content-Type", "image/jpeg");
            c.setRequestProperty("Connection", "keep-alive");
            try (java.io.OutputStream o = c.getOutputStream()) { o.write(jpg); }
            int rc = c.getResponseCode();
            // ★keep-alive — 응답 본문을 끝까지 읽어 소켓을 풀에 반납(다음 프레임이 핸드셰이크 없이 재사용). disconnect() 안 함.
            try (java.io.InputStream in = rc < 400 ? c.getInputStream() : c.getErrorStream()) { if (in != null) { byte[] b = new byte[512]; while (in.read(b) > 0) {} } } catch (Throwable ig) {}
            return rc < 400;
        } catch (Throwable ignore) { return false; }
    }

    // GC 방지 — openCamera 콜백이 비동기라 유저 스텁을 살려둔다.
    private android.hardware.IBYDCameraUser camUserHold;

    // ★v186 openCamera 게이트 테스트 — bydcameramanager(IBYDCameraService)에 실제 유저등록+openCamera.
    //   권한체크 통과여부 실차판정. 통과+onOpenCamera 콜백 오면 스트리밍 서버 구현으로 직행.
    private String camOpenTest() {
        final int camId = 0;   // 0=전방 추정(추후 스윕)
        // 콜백 유저 스텁 — 헤드유닛이 열림/에러를 여기로 콜백.
        android.hardware.IBYDCameraUser user = new android.hardware.IBYDCameraUser.Stub() {
            public boolean onPreOpenCamera(android.hardware.IBYDCameraUser r, int id) { postToD1("CAMOPEN", "콜백 onPreOpenCamera(id=" + id + ")"); return true; }
            public boolean onOpenCamera(android.hardware.IBYDCameraUser r, int id) { postToD1("CAMOPEN", "★콜백 onOpenCamera(id=" + id + ") — 카메라 열림!"); return true; }
            public boolean onCloseCamera(android.hardware.IBYDCameraUser r, int id) { postToD1("CAMOPEN", "콜백 onCloseCamera(id=" + id + ")"); return true; }
            public String getPackageName() { return SnifferService.this.getPackageName(); }
            public int getCameraId() { return camId; }
            public String getProperty(String key) { return ""; }
            public boolean onError(String err, int code) { postToD1("CAMOPEN", "콜백 onError(" + err + "," + code + ")"); return true; }
        };
        camUserHold = user;
        StringBuilder sb = new StringBuilder();
        try {
            Object b = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "bydcameramanager");
            if (b == null) return "bydcameramanager=null";
            android.hardware.IBYDCameraService svc = android.hardware.IBYDCameraService.Stub.asInterface((android.os.IBinder) b);
            if (svc == null) return "asInterface=null";
            sb.append("svc획득 ");
            try { boolean reg = svc.registerUser(user); sb.append("registerUser=").append(reg).append(" "); }
            catch (SecurityException se) { sb.append("registerUser SEC:").append(se.getMessage()).append(" "); }
            catch (Throwable t) { sb.append("registerUser예외:").append(t.getClass().getSimpleName()).append(":").append(t.getMessage()).append(" "); }
            try { boolean pre = svc.preOpenCamera(user); sb.append("preOpen=").append(pre).append(" "); }
            catch (SecurityException se) { sb.append("preOpen SEC:").append(se.getMessage()).append(" "); }
            catch (Throwable t) { sb.append("preOpen예외:").append(t.getClass().getSimpleName()).append(":").append(t.getMessage()).append(" "); }
            try { svc.openCamera(user); sb.append("★openCamera호출OK(예외없음) "); }
            catch (SecurityException se) { sb.append("openCamera SEC:").append(se.getMessage()).append(" "); }
            catch (Throwable t) { sb.append("openCamera예외:").append(t.getClass().getSimpleName()).append(":").append(t.getMessage()).append(" "); }
        } catch (Throwable t) { sb.append("최상위예외:").append(t.getClass().getSimpleName()).append(":").append(t.getMessage()); }
        postToD1("CAMOPEN", sb.toString());
        return "CAMOPEN — " + sb;
    }

    // ★v174 getProperty(code=2) 한 키 읽고 reply parcel을 직접 파싱(클래스 이식 없이).
    //   reply: readException → readInt(1=결과) → Response{writeParcelable(Status), writeParcelable(CarPropertyValue)}
    //   Status: clsName,int code,String desc / CPV: clsName,String key,String id,String valCls,값(타입별)
    private String getProp(android.os.IBinder binder, String key) {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken("com.byd.car.property.ICarPropertyService");
            data.writeString(key);
            if (!binder.transact(2, data, reply, 0)) return "transact=false";
            reply.readException();
            if (reply.readInt() != 1) return "결과없음";
            reply.readString(); int code = reply.readInt(); reply.readString();   // Status: cls, code, desc
            String cpvCls = reply.readString();                                    // CarPropertyValue cls (null이면 result null)
            if (cpvCls == null) return "code=" + code + " val=null";
            reply.readString(); reply.readString();                                // propKey, propId
            String vCls = reply.readString();
            String val;
            if ("java.lang.Integer".equals(vCls)) val = String.valueOf(reply.readInt());
            else if ("java.lang.Boolean".equals(vCls)) val = String.valueOf(reply.readInt() != 0);
            else if ("java.lang.String".equals(vCls)) val = "\"" + reply.readString() + "\"";
            else if ("java.lang.Long".equals(vCls)) val = String.valueOf(reply.readLong());
            else if ("java.lang.Float".equals(vCls)) val = String.valueOf(reply.readFloat());
            else if ("java.lang.Double".equals(vCls)) val = String.valueOf(reply.readDouble());
            else if ("[B".equals(vCls)) { byte[] b = reply.createByteArray(); val = "byte[" + (b == null ? 0 : b.length) + "]"; }
            else if ("[I".equals(vCls)) { reply.readInt(); int[] a = reply.createIntArray(); val = java.util.Arrays.toString(a); }
            else val = "type=" + vCls;
            return (code == 0 ? "" : "st" + code + " ") + "val=" + val;
        } catch (Throwable t) { return "예외:" + t.getClass().getSimpleName() + ":" + t.getMessage();
        } finally { data.recycle(); reply.recycle(); }
    }

    // ★v177 CarServiceProvider 바인더 획득(공용) — carProbe/carSet 재사용.
    private android.os.IBinder getCarBinder() {
        android.net.Uri uri = android.net.Uri.parse("content://com.byd.car.server.provider.CarServiceProvider/sync_binder");
        android.database.Cursor cur = null;
        try {
            cur = getContentResolver().query(uri, null, null, new String[]{"com.byd.car.property.ICarPropertyService"}, null);
            if (cur == null) return null;
            android.os.Bundle ex = cur.getExtras();
            if (ex == null) return null;
            ex.setClassLoader(com.byd.spi.ipc.cursor.BinderCursor.BinderParcelable.class.getClassLoader());
            Object p = ex.getParcelable(com.byd.spi.ipc.cursor.BinderCursor.KEY_BINDER);
            if (!(p instanceof com.byd.spi.ipc.cursor.BinderCursor.BinderParcelable)) return null;
            return ((com.byd.spi.ipc.cursor.BinderCursor.BinderParcelable) p).getBinder();
        } catch (Throwable t) { return null;
        } finally { if (cur != null) try { cur.close(); } catch (Throwable ignored) {} }
    }

    // ★v177 setProperties(code=1) 쓰기 테스트 — params{key,val}. 전/후 값 읽어 실제 변경 확인. 서명권한 필요/무권한 판별.
    //   마샬: token + writeTypedArray(CarPropertyValue[1]{key,null,"java.lang.Integer",val}). reply: readException→readInt(1)→Status{code,desc}.
    private String carSet(org.json.JSONObject params) {
        if (params == null) return "params 없음(key,val 필요)";
        String key = params.optString("key", "");
        int val = params.optInt("val", Integer.MIN_VALUE);
        if (key.isEmpty() || val == Integer.MIN_VALUE) return "key/val 파싱실패";
        android.os.IBinder binder = getCarBinder();
        if (binder == null) return "바인더 획득 실패(차 꺼짐/규약)";
        String before = getProp(binder, key);
        String setr;
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken("com.byd.car.property.ICarPropertyService");
            data.writeInt(1); data.writeInt(1);                 // 배열길이1, non-null 원소
            data.writeString(key); data.writeString(null); data.writeString("java.lang.Integer"); data.writeInt(val);   // CarPropertyValue
            boolean ok = binder.transact(1, data, reply, 0);
            reply.readException();
            int has = reply.readInt();
            if (has == 0) setr = "transact=" + ok + " status=null";
            else { int code = reply.readInt(); String desc = reply.readString(); setr = "transact=" + ok + " status.code=" + code + (desc != null && !desc.isEmpty() ? "/" + desc : ""); }
        } catch (SecurityException se) { setr = "★보안차단=" + se.getMessage();
        } catch (Throwable t) { setr = "예외:" + t.getClass().getSimpleName() + ":" + t.getMessage();
        } finally { data.recycle(); reply.recycle(); }
        String after = getProp(binder, key);
        return "setProperty(" + key + "=" + val + ") before=[" + before + "] " + setr + " after=[" + after + "]";
    }

    // ★v175 오토스타트금지 직접해제 프로브 — AppStartManagement가 쓰는 숨은 시스템서비스 "bg_datacache"(AppOpsDataCachedManager)를
    //   리플렉션 호출. setAppOpsData(uid문자열, 1=자동시작허용/0=금지). 되면 가디언 a11y 폐기 가능. getAppOpsData로 전/후 확인.
    //   1=허용이 우리가 원하는 상태라 성공해도 무해. 엔진 무관(안드로이드 시스템 서비스).
    private String appOpsProbe() {
        StringBuilder sb = new StringBuilder();
        Object svc;
        try { svc = getSystemService("bg_datacache"); }
        catch (Throwable t) { return "getSystemService(bg_datacache) 예외:" + t.getClass().getSimpleName() + ":" + t.getMessage(); }
        if (svc == null) return "bg_datacache 서비스 null(서비스명 다름/미존재)";
        sb.append("svc=").append(svc.getClass().getName());
        java.lang.reflect.Method getM = null, setM = null;
        try { getM = svc.getClass().getMethod("getAppOpsData", String.class); } catch (Throwable t) { sb.append(" [getAppOpsData없음]"); }
        try { setM = svc.getClass().getMethod("setAppOpsData", String.class, int.class); } catch (Throwable t) { sb.append(" [setAppOpsData없음]"); }
        for (String pkg : new String[]{"com.cseini.bydsniffer", "com.cseini.bydautotrip", "com.kapp.youtube.final"}) {
            int uid;
            try { uid = getPackageManager().getApplicationInfo(pkg, 0).uid; }
            catch (Throwable t) { sb.append("\n").append(pkg).append(": 미설치"); continue; }
            String u = String.valueOf(uid), before = "-", after = "-", setr = "-";
            try { if (getM != null) before = String.valueOf(getM.invoke(svc, u)); } catch (Throwable t) { Throwable c = t.getCause() != null ? t.getCause() : t; before = "예외:" + c.getClass().getSimpleName(); }
            try { if (setM != null) { setM.invoke(svc, u, 1); setr = "OK"; } } catch (Throwable t) { Throwable c = t.getCause() != null ? t.getCause() : t; setr = "예외:" + c.getClass().getSimpleName() + ":" + c.getMessage(); }
            try { if (getM != null) after = String.valueOf(getM.invoke(svc, u)); } catch (Throwable t) { after = "예외"; }
            sb.append("\n").append(pkg).append("(uid ").append(u).append("): before=").append(before).append(" set=").append(setr).append(" after=").append(after);
        }
        return sb.toString();
    }

    // 문: 전용 setDoorLock(int) + per-door write 둘 다 시도(주차 후 뭐가 실제로 되는지 로그로 판별)
    private String doorCmd(int value, StringBuilder log) {
        String r1 = callIntMethod(settingDevice, "setDoorLock", value);
        String r2 = writeAll(doorlockDevice, DOOR_LF_RF_LR_RR, value, log);
        return "setDoorLock=" + r1 + " | per-door=" + r2;
    }

    // 전용 int 인자 메서드 호출 (setSunshadeState 등)
    // ★635 D5 공조 전원 — 무인자 start()/stop()은 NoSuchMethod(실측 23건). 비틀런처: ac.start(zone)/stop(zone), 실패 시 setAcWindLevel(zone, 켜기3/끄기0).
    // ★634/637 씨라7 온도 — setAcTemperature(zone0, ℃, 0, 0)(BeetleLauncher). 단위(℃ vs ℃×2) 미실측 → 1.5초 뒤 AC_TEMP_MAIN(℃×2)과 대조 로그.
    private String d5SetTemp(Object dev, int c) {
        // ★643 씨라7 온도 형식 자동 탐색(운영자 승인 09-29) — 테스트3 실측: (0,℃,0,0)은 호출 OK인데 설정온도 불변(전=43 후=43, 두 차 18회).
        //   (구역, 값 형식) 후보를 차례로 보내 AC_TEMP_MAIN(℃×2)이 목표로 바뀐 형식을 찾으면 prefs(d5_temp_variant)에 기억해 이후 그것만 씀.
        final int[][] V = {{0, 1, 0}, {0, 2, 0}, {1, 1, 0}, {1, 2, 0}, {0, 2, 1}, {1, 2, 1}};   // {구역, 값배수(1=℃, 2=℃×2), 3번째 인자}
        int want = c * 2;
        int known = prefs != null ? prefs.getInt("d5_temp_variant", -1) : -1;
        int before = relay != null ? relay.acTempRaw() : -1;
        if (before == want) { postToD1("DILINK_RAW", "[D5]온도 " + c + "℃ — 이미 그 온도(raw " + before + ")"); return null; }
        int from = known >= 0 && known < V.length ? known : 0, to = known >= 0 && known < V.length ? known + 1 : V.length;
        String last = "";
        for (int i = from; i < to; i++) {
            int[] v = V[i];
            String rr = callIntMethod(dev, "setAcTemperature", v[0], c * v[1], v[2], 0);
            try { Thread.sleep(1500); } catch (Throwable ig) {}
            int after = relay != null ? relay.acTempRaw() : -1;
            // ★650 차가 1~2초 늦게 반영하는 경우(쪼제 FB1235 실측) — 1.5초에 목표가 아니면 2초 더 기다려 재확인. 늦게 반영된 걸 다음 후보 성공으로 오판하던 것 방지.
            boolean late = false;
            if (after != want) {
                try { Thread.sleep(2000); } catch (Throwable ig) {}
                int after2 = relay != null ? relay.acTempRaw() : -1;
                if (after2 == want) { after = after2; late = true; }
            }
            boolean hit = after == want;
            postToD1("DILINK_RAW", "[D5]온도 형식" + i + " setAcTemperature(" + v[0] + "," + (c * v[1]) + "," + v[2] + ",0) → " + rr + " · 설정온도raw 전=" + before + " 후=" + after + " 목표=" + want + (hit ? (late ? " ✅(3.5초에 반영)" : " ✅") : " ❌"));
            last = rr;
            if (hit) {
                if (prefs != null && known != i) { prefs.edit().putInt("d5_temp_variant", i).apply(); postToD1("DILINK_RAW", "[D5]온도 형식 확정 → 형식" + i + " 기억"); }
                return null;
            }
            if (after != before && after >= 0) before = after;   // 다른 값으로 바뀌었으면 그 값을 새 기준으로(다음 후보 판정용)
        }
        if (known >= 0 && prefs != null) prefs.edit().remove("d5_temp_variant").apply();   // 기억한 형식이 안 먹으면 다음엔 다시 탐색
        return last.startsWith("OK") ? "온도 명령은 전달됐지만 차 설정온도가 바뀌지 않음" : last;
    }
    private String d5AcPower(Object dev, int v) {
        String m = v == 1 ? "start" : "stop";
        String r = callIntMethod(dev, m, 0);
        if (!r.startsWith("OK")) { String r2 = callIntMethod(dev, "setAcWindLevel", 0, v == 1 ? 3 : 0); r = r + " → 폴백 " + r2; if (r2.startsWith("OK")) r = "OK " + r; }
        postToD1("DILINK_RAW", "[D5]공조전원 " + (v == 1 ? "켜기" : "끄기") + " → " + r);
        return r.startsWith("OK") ? null : r;
    }
    private String callIntMethod(Object dev, String method, int... args) {
        if (dev == null) return method + ": 디바이스 null";
        try {
            Class<?>[] types = new Class<?>[args.length];
            Object[] vals = new Object[args.length];
            for (int i = 0; i < args.length; i++) { types[i] = int.class; vals[i] = args[i]; }
            dev.getClass().getMethod(method, types).invoke(dev, vals);
            return "OK " + method + java.util.Arrays.toString(args);
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
            return "실패 " + method + ":" + c.getClass().getSimpleName() + ":" + c.getMessage();
        }
    }

    // 릴레이 RAW — 재설치 없이 반복 실험용. 4가지 모드:
    //  1) 메서드 호출:   {"device":"bodywork","method":"setBodyWindowCtrlState","args":[1,1]}
    //  2) feature 열거:  {"dumpFeatures":"LOCK"}  → BYDAutoFeatureIds 상수 이름=id 를 DILINK_FEAT 로
    //  3) feature write: {"device":"doorlock","feature":"NAME"|"featureId":123,"value":1}  → set(int[],EV)
    //  4) base set:      {"device":"doorlock","baseFeature":"NAME"|"baseId":123,"value":1}  → set(int,int,int)
    // wakeUpMcu 직전 mContext 강제 주입 — DUMP_POWER로 확정: BYDAutoPowerDevice.mContext와 부모 AbsBYDAutoDevice.mContext가
    //   둘 다 NULL이라 wakeUpMcu 내부 mContext.enforceCallingOrSelfPermission이 NPE. 두 mContext에 vcwRef(권한통과 wrapper) 주입.
    private volatile int wakeCtxLog = 0;
    private void injectPowerContext(Object p) {
        Class<?> c = p.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getName().equals("mContext") || Context.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        if (f.get(p) == null) {
                            f.set(p, vcwRef);
                            if (wakeCtxLog < 4) { wakeCtxLog++; postToD1("WAKE", "mContext 주입 성공: " + c.getSimpleName() + "." + f.getName()); }
                        }
                    } catch (Throwable t) {
                        if (wakeCtxLog < 4) { wakeCtxLog++; postToD1("WAKE", "mContext 주입 실패: " + c.getSimpleName() + "." + f.getName() + " → " + t.getClass().getSimpleName() + ":" + t.getMessage()); }
                    }
                }
            }
            c = c.getSuperclass();
        }
    }

    // ★v112 도어 폴링 제거 — 실차 진단(v111) 결과 getFeatureInt는 도어에 항상 -2147482648(쓰레기),
    //   readState는 null → 도어는 어떤 getter로도 폴링 불가, SDK 이벤트 전용 확정. 3초 reflection 루프가
    //   doorEdge를 한 번도 못 태우면서 부하만 유발해 제거. 도어는 이벤트 리스너(정상 동작 확인) + 앱 v327 방어로 커버.

    // ★10-04 D3 문 게터 탐침(운영자판 D3만, 관측 전용) — v112는 범용 읽기(getFeatureInt·readState)만 시험했고, 씨라7이 쓰는
    //   bodywork 전용 게터(getDoorState(area))류는 D3에서 미시험. 운전석 문 이벤트마다 이벤트 값과 bodywork의 문 게터 값을 나란히
    //   남겨, 일치하면 D3도 1초 폴링으로 문 유실을 근본 해결(씨라7 폴러 경로 재사용). 첫 호출 때 문 게터 목록을 한 번 남긴다.
    private volatile boolean d3DoorProbeListed = false;
    // ★740 D3 운전석 문 기준값 — 릴레이가 뜨면 bodywork.getDoorState(1)(10-04 DOORPROBE: 운전석 이벤트 1·0과 [1,..]·[0,..]로 일치)을 읽어
    //   door_driver를 바로 채운다(비콘 bit7 유효). 장치 준비 전이면 2초 간격으로 다시(최대 1분).
    private void initD3DriverDoor() {
        if (DiLink5Support.isDiLink5()) return;
        new Thread(() -> {
            for (int i = 0; i < 30; i++) {
                if (i > 0) try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                if (relay == null) continue;
                if (relay.getStatus("door_driver") instanceof Integer) return;   // 이벤트가 먼저 채움
                Object bw = deviceByName("bodywork");
                if (bw == null) continue;
                int v = callGetInt(bw, "getDoorState", 1);
                if (v != 0 && v != 1) continue;
                relay.doorInit("door_driver", v);
                postToD1("DOORDIAG", "시작 기준값 운전석=" + v + " (bodywork.getDoorState(1)) — 비콘 운전석 비트 유효");
                return;
            }
            postToD1("DOORDIAG", "시작 기준값 운전석 — 1분 안에 못 읽음(bodywork 미준비)");
        }, "d3-door-init").start();
    }
    private void d3DoorProbe(String why) {
        if (!BuildConfig.IS_OPERATOR || DiLink5Support.isDiLink5()) return;
        Object bw = deviceByName("bodywork");
        if (bw == null) { postToD1("DOORPROBE", why + " · bodywork 장치 없음"); return; }
        try {
            java.lang.reflect.Method[] ms = bw.getClass().getMethods();
            if (!d3DoorProbeListed) {
                d3DoorProbeListed = true;
                StringBuilder sb = new StringBuilder();
                for (java.lang.reflect.Method m : ms) {
                    String n = m.getName();
                    if (n.toLowerCase().contains("door") && (n.startsWith("get") || n.startsWith("is"))) sb.append(n).append("/").append(m.getParameterTypes().length).append(" ");
                }
                postToD1("DOORPROBE", "D3 bodywork 문 게터: " + (sb.length() > 0 ? sb.toString().trim() : "(없음)"));
            }
            StringBuilder r = new StringBuilder(why + " ·");
            for (java.lang.reflect.Method m : ms) {
                String n = m.getName();
                if (!n.toLowerCase().contains("door") || !(n.startsWith("get") || n.startsWith("is"))) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 0) {
                    Object v; try { v = m.invoke(bw); } catch (Throwable t) { v = "E:" + t.getClass().getSimpleName(); }
                    r.append(" ").append(n).append("=").append(v);
                } else if (pt.length == 1 && pt[0] == int.class) {
                    r.append(" ").append(n).append("[");
                    for (int a = 1; a <= 5; a++) { Object v; try { v = m.invoke(bw, a); } catch (Throwable t) { v = "E"; } r.append(v).append(a < 5 ? "," : ""); }
                    r.append("]");
                }
            }
            postToD1("DOORPROBE", r.toString());
        } catch (Throwable t) { postToD1("DOORPROBE", why + " · 예외 " + t.getClass().getSimpleName()); }
    }

    // ★v78 주행 시작/종료 감지 — 기어박스는 이벤트 구독이 크래시를 유발해(initDevices 주석) 폴링으로 읽는다.
    //   차량이 확정하는 신호(EPB·기어·게이트웨이속도)라 폰 CoreMotion 추측을 대체할 authoritative 주행상태.
    //   1단계: 전이의 원시값을 전부 로깅해 인코딩 확정 + drive_start/drive_end 이벤트 발사(iOS는 로그만).
    //   판정 기본축 = EPB(전자 주차브레이크): 주차 시 1, 주행 시 0. 속도(getSpeedFromGateway)로 보강.
    private void startGearPoll() {
        new Thread(() -> {
            try { Thread.sleep(9000); } catch (InterruptedException e) { return; }
            // ★v89 ac 도메인 자동구독 — AC_AIR_CONDITION_WORK_MODE(1=송풍/2=A/C) 이벤트를 state로 흘려
            //   아이폰 차량상태 "공조 모드" 표시. (구독해야 이벤트·getter가 산다 — 2026-07-30 00:45 실차 확인)
            try { String r = subscribeDomain("android.hardware.bydauto.ac.BYDAutoAcDevice"); postToD1("RELAY", "ac 자동구독: " + r); } catch (Throwable ignored) {}
            // ★차종옵션 자동감지 — hasFeature로 통풍/열선/선쉐이드 지원여부를 relay state(caps)로. 앱이 없는 옵션 UI 숨김.
            try { probeCaps(); } catch (Throwable ignored) {}
            // ★v94 헤드유닛 iBeacon 상시 송출(ESP32 대체) + 생존 감시 자동 시작. ★v107 BLE 명령 채널도 함께.
            if (bleAdvWanted) { try { startBeaconAdvertise(); startBeaconWatchdog(); postToD1("BLECMD", "명령 채널: " + startCmdGattServer()); } catch (Throwable t) { postToD1("BLEADV", "자동시작 실패: " + t); } }
            // ★v81 주행 판정 = getCurrentGear (P=3). 실주행(후진1→전진2→파킹3)으로 인코딩 확정.
            //   gearbox 리스너가 등록돼 있어야 이 getter가 live다(v80에서 확인 — 등록 없으면 EPB=1/gear=2에 얼어붙음).
            //   P(3) = 주차, 그 외(R=1/D=2/N등) = 주행. 주차↔주행 전이에서만 drive_end/drive_start 발사.
            final int GEAR_P = 3;
            int prevDriving = -1;
            long lastRawLog = 0;
            boolean acWmLogged = false;   // ★AC work_mode 능동조회 성공 메서드 1회 로그용
            int acWmLastNum = Integer.MIN_VALUE, acWmLastEv = Integer.MIN_VALUE;   // ★v378 AC검증 불일치 로그 — 마지막 기록 값 쌍(바뀔 때만 재기록, 스팸 억제)
            // ★시동 판정은 IgnitionTracker(RelayClient)가 D3/D5 공통으로 담당(2026-09-11) — 여기선 getPowerLevel을 읽어 넘기기만.
            //   옛 D5 전용 sawPwr2/pwrZeroStreak/prevIgn 로직은 판별기 안으로 이관(JUnit 19케이스로 고정).
            RelayClient ignBootedFor = null;   // ★적대적 검증 #6: 어느 RelayClient 인스턴스에 onBoot을 줬는지 — restartRelay()로 새 인스턴스가 오면 다시 onBoot
            while (pollRunning) {
                try {
                    // ★시동 판정 입력(D3/D5 공통, 2026-09-11) — bodywork.getPowerLevel() 2=ON/0=OFF(실차 확정: 공조 토글에 무반응).
                    //   D5는 SDK 미준비 구간에서 화면 프록시(isInteractive)를 보조로 넘김. D3는 false.
                    if (relay != null) {
                        double pw = Double.NaN;
                        try { pw = devDbl(deviceByName("bodywork"), "getPowerLevel"); } catch (Throwable ignored) {}
                        int pwrI = Double.isNaN(pw) ? IgnitionTracker.NA : (int) pw;
                        lastPowerLvl = Double.isNaN(pw) ? Integer.MIN_VALUE : (int) pw;   // ★680 비틀 데몬 종료 안전 조건(시동 꺼짐=0)
                        boolean scr = false;
                        if (DiLink5Support.isDiLink5()) {
                            try { scr = ((android.os.PowerManager) getSystemService(POWER_SERVICE)).isInteractive(); } catch (Throwable ignored) {}
                            if (!Double.isNaN(pw)) relay.putStatus("d5_power", pw);
                            relay.putStatus("d5_screen", scr ? 1 : 0);
                        }
                        if (ignBootedFor != relay) { ignBootedFor = relay; relay.onPowerLevelBoot(pwrI); }
                        else relay.onPowerLevelPoll(pwrI, scr);
                    }
                    Object gb = deviceByName("gearbox");
                    int gear = devInt(gb, "getCurrentGear");
                    // ★568 씨라7(D5) 기어 정규화 — D5는 getCurrentGear가 **항상 0**이라 아래 판정이 통째로 죽어 있었다.
                    //   死였던 것: 후진비상등 비콘비트(gear==1), 주행판정(1||2), 주차판정(==GEAR_P) → CAMAVAIL·SEATPROBE·
                    //   브레이크로깅·분할복원. 즉 **씨라7은 후진비상등이 한 번도 발화한 적이 없다.**
                    //   인코딩은 조포스(…060495) 실차 2회 독립시행으로 확정(09-23): d5 1=P · 2=R · 3=N · 4=D.
                    //   여기서 한 번만 D3 체계(1=R·2=D·3=P·0=N)로 옮기면 아래 판정부를 하나도 안 건드리고 전부 살아난다.
                    //   ★안전원칙(551 롤백 교훈): **모르는 값은 절대 P로 만들지 않는다.** 예상 밖 값이면 gear를 그대로 둬
                    //   기존(=0, N) 동작을 유지한다 — P로 오인하면 주행 중 pm install·비상등 오발사가 난다.
                    if (DiLink5Support.isDiLink5()) {
                        int g5raw = devInt(gb, "getGear");
                        int mapped = (g5raw == 1) ? GEAR_P : (g5raw == 2) ? 1 : (g5raw == 3) ? 0 : (g5raw == 4) ? 2 : Integer.MIN_VALUE;
                        if (mapped != Integer.MIN_VALUE) gear = mapped;
                    }
                    // ★557 씨라7 기어 관측 — 앞선 552~556의 전제가 틀렸다. "D5엔 getCurrentGear가 없다"고 봤는데(덤프의
                    //   GearboxManagerImpl 기준) 실제로는 **존재하고 0을 반환**한다(relay_state의 gear=0이 그 값이다 — MIN_VALUE면
                    //   아래 putStatus 블록에 못 들어가 아예 안 올라온다). 그래서 555/556의 진단 조건(gear==MIN_VALUE)은
                    //   한 번도 성립하지 않아 D5GEAR 로그가 0건이었다. D5면 무조건 1회 찍어 실물을 본다.
                    //   ★0은 D3 인코딩에서 N(중립)이다. 주차 중인데 0이면 getter가 값을 못 받아 얼어붙은 것일 수 있다
                    //   (v80 교훈: gearbox 리스너 미등록 시 getter가 고정값에 얼어붙는다) — 진단 결과로 갈린다.
                    if (DiLink5Support.isDiLink5() && relay != null) {
                        int g5 = devInt(gb, "getGear");
                        if (g5 != Integer.MIN_VALUE && g5 != lastD5Gear) {
                            // ★561 값이 바뀔 때마다 로그 — 스냅샷만으론 "지금 1인데 이게 P냐 D냐"를 알 수 없어 인코딩 확정이 불가능했다
                            //   (09-22 메밀소바 실측 응답). 전이(이전→현재)와 속도를 함께 남기면 차주가 넣은 순서와 대조된다.
                            postToD1("D5GEAR", "기어 값 변화 " + (lastD5Gear == Integer.MIN_VALUE ? "?" : String.valueOf(lastD5Gear))
                                    + "→" + g5 + " (속도=" + (Double.isNaN(lastSpeed) ? "?" : String.valueOf((int) lastSpeed)) + " 시동=" + powerLevelNow() + ")");
                            lastD5Gear = g5; relay.putState("d5_gear", g5);
                        }
                        if (!d5GearProbed) {
                            d5GearProbed = true;
                            StringBuilder sb = new StringBuilder("gearbox dev=").append(gb == null ? "null" : gb.getClass().getName());
                            sb.append(" / getCurrentGear=").append(gear == Integer.MIN_VALUE ? "없음" : String.valueOf(gear));
                            if (gb != null) {
                                sb.append(" / getGear=").append(g5 == Integer.MIN_VALUE ? "없음" : String.valueOf(g5)).append(" / get*:");
                                int n = 0;
                                for (java.lang.reflect.Method m : gb.getClass().getMethods()) {
                                    if (m.getParameterTypes().length == 0 && m.getName().startsWith("get") && n++ < 40) sb.append(m.getName()).append(' ');
                                }
                            }
                            postToD1("D5GEAR", sb.toString());
                        }
                    }
                    // ★v293 차속 = getCurrentSpeed (실차 실증: 0~110km/h 실값. getSpeedFromGateway는 항상0 죽은게터라 폐기).
                    //   byd-trip-stats 발견 → SPEEDCMP 로그 383/430 실값 확인. 이 값으로 lastSpeed/speedLive 갱신 → ICC 속도가드(정차 억제) 복원.
                    double spGw = devDbl(deviceByName("speed"), "getCurrentSpeed");
                    if (!Double.isNaN(spGw)) { lastSpeed = spGw; if (spGw > 3) speedLive = true; }
                    if (gear != Integer.MIN_VALUE) {
                        // ★v105 중립주차 대응(08-07 08:20 실측): N(0)을 '주행'으로 치면 중립주차 시 drive_end가 영영 안 오고,
                        //   N↔P 셔플로 drive_start/end가 난사돼 push 유실 시 폰에 '주행중'이 박제 → 이탈 자동잠금 차단.
                        //   D(2)/R(1)만 주행 확정, P(3)=주차, N(0)은 속도로 판정(굴러가면 주행, 정지면 주차).
                        // ★580 측정 속도 최우선(도규아빠 실사고) — 기어 게터가 주행 중 순간 P로 오독되면(속도>3인데) 주차로 커밋돼
                        //   비콘 주행비트가 깜빡→폰 주차언락 6042 폭주 + parkedForUpdate 오판까지 났다. 속도로 명백히 움직이면 기어 오독 무시.
                        int drivingRaw;
                        if (!Double.isNaN(spGw) && spGw > 3.0) drivingRaw = 1;   // 명백히 주행(3km/h+) — 기어 오독보다 우선
                        else if (gear == 1 || gear == 2) drivingRaw = 1;
                        else if (gear == GEAR_P) drivingRaw = 0;
                        else drivingRaw = 0;                                     // N·불명 + 저속 = 주차
                        // ★드라이브 디바운스(2026-09-17 실사고) — 클러스터 fast-poll(600ms) 중 주차 상태에서 브레이크/EPB 조작 순간
                        //   gear가 D(2)로 한 틱 오독돼 drive_start↔end가 플랩 → 비콘 주행비트 토글 → 폰이 "주차확정→언락" 반복(운영자 21:28 실측).
                        //   새 상태가 2연속 폴 확인돼야 커밋. 단 실제 속도>3(진짜 주행)이면 즉시 확정(지연 0).
                        if (drivingRaw == drivingRawPrev) { if (drivingConfirm < 2) drivingConfirm++; }
                        else { drivingRawPrev = drivingRaw; drivingConfirm = 1; }
                        boolean fastConfirm = (!Double.isNaN(spGw) && spGw > 3.0);
                        int driving = (prevDriving == -1 || drivingConfirm >= 2 || fastConfirm) ? drivingRaw : prevDriving;
                        // ★728 가짜 주행 차단(FB1892 — 씨라7 주차 중 새벽 03:15 잠 깨며 기어 오독 → drive_start→2초 뒤 drive_end → 폰 주차 자동 잠금해제로 문 열림).
                        //   시동이 확실히 꺼져 있으면(전원 0) 주행으로 넘어가지 않는다 — 진짜 출발은 시동이 먼저 켜진다(사건 순서). 속도로 움직이는 건 그대로 주행.
                        if (driving == 1 && prevDriving == 0 && !fastConfirm && powerLevelNow() == 0) {
                            long nowF = System.currentTimeMillis();
                            if (nowF - lastFakeDriveLogMs > 60_000L) { lastFakeDriveLogMs = nowF; postToD1("DRIVE", "주행 전환 보류 — 시동 꺼짐(전원 0)인데 기어=" + gear + " · 가짜 주행 방지"); }
                            driving = 0;
                        }
                        lastDriving = driving;   // ★필드 갱신 — handleLaneEvent(정차 시 ICC 알림 제외)에서 참조
                        // ★후진(R=1) 자동 비상등 — 폴링 폴백(2s라 느림). 진짜 빠른 건 아래 checkReverseFast(파킹브레이크 이벤트 즉시).
                        //   쿨다운 공유로 이벤트와 중복 안 됨. 로컬 SDK는 setter 없음(가짜OK) → 클라우드 연타로.
                        if (gear == 1 && lastGearVal != 1 && prefs != null && prefs.getBoolean("reverse_flash", true)) {
                            checkReverseFast("poll");
                        }
                        // ★570 후진 카메라 — R 진입/이탈을 클러스터에 알린다(표시만 전환, 카메라는 예열로 이미 열려 있음).
                        //   568 기어 정규화 덕에 씨라7도 같은 조건으로 걸린다(단 측면/후방 표시는 현재 D3 전용, onReverse 내부 가드).
                        if ((gear == 1) != (lastGearVal == 1)) clusterCam.onReverse(gear == 1, System.currentTimeMillis());
                        clusterCam.keepTick();   // ★729 보조 켬 + 시동 켜짐이면 카메라 상시 대기(비켜 줬다가 다시 열기 포함)
                        if (prefs != null && prefs.getBoolean("reboot_guard", true) && System.currentTimeMillis() - rebootGuardAt > 30 * 60_000L) applyRebootGuard("30분 확인");   // ★739 BYD가 다시 켰으면 다시 끔
                        // ★분할 복원(2026-09-11, BydAutoSplit CameraRestore 대응) — 후진(R) 카메라가 전면을 가져간 뒤 R을 벗어나면 마지막 분할 재실행.
                        //   R 진입 순간 "분할 활성이었나"를 비동기로 기록, R 이탈 시 그 플래그가 참이면 1.5s 뒤(카메라 화면 종료 대기) SPLIT 재실행.
                        if (gear == 1 && lastGearVal != 1 && SplitLauncher.lastPkg1 != null && splitOwned && !allStopOn()) {   // ★724 릴레이가 띄운 분할만
                            new Thread(() -> { splitBeforeReverse = SplitLauncher.isSplitActive(this); }, "split-r-enter").start();
                        } else if (lastGearVal == 1 && gear == 3 && splitBeforeReverse) {   // ★P로 들어갈 때만 복원(R→D 주행 중 화면 급변 금지). R→N/D면 플래그 폐기.
                            splitBeforeReverse = false;
                            final String p1 = SplitLauncher.lastPkg1, p2 = SplitLauncher.lastPkg2; final int pct = SplitLauncher.lastPct;
                            new Thread(() -> { sleepQuiet(1500); StringBuilder lg = new StringBuilder();
                                String r = SplitLauncher.split(this, p1, p2, pct, lg);
                                postToD1("SPLIT", "후진 후 분할 복원 → " + r); }, "split-restore").start();
                        } else if (lastGearVal == 1 && gear != 1) {
                            splitBeforeReverse = false;   // R→N/D: 복원 안 함(주행), 플래그 폐기 — 나중 R→P에 stale 복원 방지
                        }
                        lastGearVal = gear;   // ★v87: 애프터블로우 종료 가드(주행 중 스킵)용 캐시
                        if (relay != null) {
                            relay.putStatus("drive_state", driving);
                            relay.putStatus("gear", gear);
                            if (!Double.isNaN(spGw)) relay.putStatus("speed", (int) Math.round(spGw));
                        }
                        if (prevDriving != -1 && prevDriving != driving) {
                            String ev = driving == 1 ? "drive_start" : "drive_end";
                            // ★620 주행 래치 — 출발 순간 주 사용권 폰이 근처였으면 이 주행 동안 자율 기능 유지(주행 중 앱 정지로 BSD 등이 꺼지는 것 방지). 주차 시 해제.
                            nearDriveLatch = driving == 1 && phoneNearFresh();
                            persistNear();   // ★624
                            if (relay != null) relay.putStatus("near_latch", nearDriveLatch ? 1 : 0);
                            if (relay != null) { relay.putStatus("drive_change_ts", System.currentTimeMillis()); relay.postCarEvent(ev); if (prefs != null) prefs.edit().putInt("last_emit_drive", driving).apply(); }
                            // ★오토전비 trip wake(2026-09-19) — 주행 시작 시 HudRemoteReceiver로 TripService만 기동(extras 없음=오버레이 설정 불변, 루프만 시작). 미설치 차량은 no-op. 시동OFF 종료·push는 앱 기존 루프(pwr 2→0)에 맡김.
                            if (driving == 1) broadcastHud();
                            // ★비콘 즉시 반영(2026-08-28) — 주행/주차 전이 순간 bit1을 폰이 watchdog 5초 안 기다리고 바로 보게 즉시 재광고.
                            //   주차 언락(폰의 비콘 bit1 경로)이 push(APNs ~1.5-2s)보다 늦어 push 속도로 나던 것 → ~1초(다음 레인징)로 단축.
                            if (bleAdvWanted) { try { startBeaconAdvertise(); } catch (Throwable ig) {} }
                            // ★냉방 리셋은 drive_start가 아니라 시동ON(AC_POWER 0→1)으로 이동 — 후진주차(D→N→R) drive_start가 그 주행 냉방시간을 지우던 버그 수정.
                            postToD1("DRIVE", ev + " — gear=" + gear + " 속도=" + spGw);
                            // ★v572 주차 시 주간주행등 끄기(앱 토글). setDayTimeLightState(2=끄기 · 1=켜기, 실차확정).
                            //   ★★"주행복귀 시 차가 DRL을 자동으로 켜므로 복원 불필요"라던 v146 전제는 **실차에서 깨졌다**
                            //   (09-23 id189 아톰 아토3: 끄기 발사 40분 뒤에도 getDayTimeLightState()가 2로 남아 있었다.
                            //    사용자는 그동안 DRL이 죽은 채 다니다 설정을 껐다 켰다 반복하고 결국 포기했다).
                            //   → **우리가 끈 것은 우리가 되돌린다.** 끈 사실을 기록해 주행복귀·토글해제 때 1로 복원.
                            if (driving == 0 && prefs != null && prefs.getBoolean("drl_park_off", false) && autoAllowed()) {   // ★620 끄기만 게이트(복원은 우리가 끈 것 되돌리기라 예외)
                                String rr = callIntMethod(deviceByName("android.hardware.bydauto.light.BYDAutoLightDevice"), "setDayTimeLightState", 2);
                                drlWeTurnedOff = true;
                                postToD1("DRL", "주차 DRL 끄기(2) → " + rr);
                            }
                            // ★v572 주행 복귀 → 우리가 껐던 경우에만 켜기(1) 복원. 차 설정에서 사용자가 직접 끈 DRL은 안 건드린다.
                            if (driving == 1 && drlWeTurnedOff) {
                                drlWeTurnedOff = false;
                                String rr = callIntMethod(deviceByName("android.hardware.bydauto.light.BYDAutoLightDevice"), "setDayTimeLightState", 1);
                                postToD1("DRL", "주행 복귀 — DRL 켜기(1) 복원 → " + rr);
                            }
                        } else if (prevDriving == -1) {
                            postToD1("DRIVE", "초기 gear=" + gear + " (" + (driving == 1 ? "주행" : "주차") + ") 속도=" + spGw);
                            // ★부팅 재동기(2026-09-09) — 재부팅이 주차/주행 전환과 겹쳐 drive_end/start 유실 시 폰 drive_state 박제→이탈 자동잠금 死(실측 12:16 재부팅이 재파킹 drive_end 유실).
                            //   부팅 첫 판독(gearbox 등록됨=getter live, 초기 gear 정확)에서 현재 상태를 drive_sync로 방출 → 폰이 자동화 없이 drive_state만 최신화(unlockOnPark 재트리거 안 함).
                            // ★661 배터리 — 릴레이가 시간당 3~5번 다시 시작될 때마다 drive_sync 푸시가 폰을 깨웠다(2일 반 161번, 자는 동안에도 15분마다).
                            //   마지막으로 알린 주행 상태(last_emit_drive)와 같고 주차(0)면 생략한다. 재시작 중 전환을 놓쳤으면 값이 달라지거나 주행(1)이라 그대로 보낸다.
                            if (relay != null) {
                                relay.putStatus("drive_change_ts", System.currentTimeMillis());
                                int lastEmit = prefs != null ? prefs.getInt("last_emit_drive", -1) : -1;
                                if (driving == 1 || lastEmit != driving) {
                                    relay.postCarEvent("drive_sync:" + driving);
                                    if (prefs != null) prefs.edit().putInt("last_emit_drive", driving).apply();
                                } else {
                                    postToD1("DRIVE", "부팅 재동기 생략 — 이미 알린 주행 상태(" + driving + ")와 같음(폰 깨움 절감)");
                                }
                            }
                            if (driving != 1 && nearDriveLatch) { nearDriveLatch = false; persistNear(); }   // ★625 부팅 시 주차면 복원된 주행 래치 해제(drive_end 유실 대비)
                        }
                        prevDriving = driving;
                        frontAutoArmTick(driving, gear);   // ★전방 주행 세션 자동 무장(주행)/해제(P) — 앱 버튼 불필요
                        bsdExp.tick(driving);              // ★602 BSD실험 주행 폴링
                        // ★496 웹서버 재기동 — 핫스팟을 시동 후 켜면 부팅 시 웹서버(8765) 미기동 → LAN 직결 死. 안 떠 있으면 30s마다 재시도(핫스팟 올라오면 그때 뜸).
                        if (remoteWeb == null && System.currentTimeMillis() - lastWebRetry > 30000) { lastWebRetry = System.currentTimeMillis(); startRemoteWeb(); }
                        publishRemoteWebUrl(false);         // ★웹 세컨드스크린 접속 URL 30s 재발행(핫스팟/WiFi 늦게 올라와도 반영)
                        // 주행 중 인코딩 관찰용 heartbeat(60초) — R/D/N 값 매핑 보강.
                        if (driving == 1 && System.currentTimeMillis() - lastRawLog > 60000) {
                            lastRawLog = System.currentTimeMillis();
                            postToD1("DRIVE", "주행중 gear=" + gear + " 속도=" + lastSpeed + " gw=" + spGw + " speedLive=" + speedLive);
                        }
                    }
                    // ★v127 좌석 통풍/열선·핸들열선 상태 폴링(car→app 미러) — 차/물리버튼으로 바꿔도 앱이 따라오게.
                    //   값 1=끄기 2=1단 3=2단(핸들 1/2). 읽기 실패(시동 꺼짐 등)=-1 → 앱이 무시하고 마지막값 유지.
                    Object setg = deviceByName("setting");
                    if (setg != null && relay != null) {
                        relay.putStatus("seat_dv", callGetInt(setg, "getSeatVentilatingState", 1));
                        relay.putStatus("seat_dh", callGetInt(setg, "getSeatHeatingState", 1));
                        relay.putStatus("seat_pv", callGetInt(setg, "getSeatVentilatingState", 2));
                        relay.putStatus("seat_ph", callGetInt(setg, "getSeatHeatingState", 2));
                        relay.putStatus("steer_h", callGetInt0(setg, "getSteeringWheelHeatingState"));
                    }
                    // ★리모컨 실시간 상태(2026-09-10 운영자 "선쉐이드·풍량·내외기·DRL 현재값 반영 안 됨") — 지금까지 시트·핸들만 올렸다.
                    //   기기별 getter 이름이 달라 후보를 순서대로 시도하고, 성공한 이름을 1회 로그로 남겨 다음 세션이 확정할 수 있게 한다.
                    try {
                        Object acd = deviceByName("ac"), ltd = deviceByName("light"), bwd = getBodyworkDevice();
                        // ★온도·선쉐이드는 getter가 없다(2026-09-10 DUMP_AC 실측: 설정온도 게터 부재) → feature 읽기로.
                        //   온도 feature 값은 ℃×2(쓰기와 동일 스케일) → 패널이 그대로 쓰게 2로 나누지 않고 원값 발행 후 앱이 환산.
                        // ★feature id 실차 확정(2026-09-10 FEAT 덤프): AC_TEMP_MAIN=1077936168 · AC_DEFROST_FRONT_STATE=1077936150
                        //   · AC_DEFROST_REAR_STATE=1128267825 · BODYWORK_SUNSHADE_PANEL_PERCENT=1101004816. getter는 없다.
                        //   _SET id는 읽지 않는다(SDK가 캐시한 '내가 마지막에 쓴 값'이라 차 물리버튼 조작을 놓침 — acSwitch stale과 같은 패턴).
                        publishLiveFeat("ac_temp_raw", acd, "AC_TEMP_MAIN");
                        // ★768 FB2025 — 씨라7 헤드유닛은 시동 OFF 중 선쉐이드 feature에 옛 값을 돌려준다(044219 13:22·16:58 pwr=0에서 100, 실제 닫힘).
                        //   시동 ON일 때만 읽고, 지금 실시간인지(sunshade_live)·마지막 읽은 시각(sunshade_ts)을 같이 올려 앱이 단정하지 않게 한다.
                        int shadePw = powerLevelNow();
                        boolean shadeLive = shadePw >= 0 ? shadePw == 2 : relay.ignitionState() == 1;
                        if (shadeLive) publishLiveFeat("sunshade_pct", bwd, "BODYWORK_SUNSHADE_PANEL_PERCENT");
                        relay.putStatus("sunshade_live", shadeLive ? 1 : 0);
                        publishLiveFeat("defog_rear", acd, "AC_DEFROST_REAR_STATE");
                        publishLiveFeat("defog_front", acd, "AC_DEFROST_FRONT_STATE");
                        publishLive("ac_wind",  acd, new String[]{"getAcWindLevel", "getWindLevel", "getAcWindLevelNum"});
                        publishLive("ac_cycle", acd, new String[]{"getAcCycleMode", "getCycleMode", "getAcCirculateMode"});
                        Object std = deviceByName("setting");
                        publishLive("regen",      std, new String[]{"getEnergyFeedback"});
                        publishLive("steer_mode", std, new String[]{"getSteerAssis"});
                        publishLive("brake_mode",
                                DiLink5Support.isDiLink5() ? std : adasDevice,
                                new String[]{DiLink5Support.isDiLink5() ? "getBrakePedalMode" : "getBrakeFootSenseState"});
                        // ★drive_mode·epedal 게터는 발행하되 매니페스트 state는 안 건다 — 쓰기/읽기 스케일이 어긋난 게
                        //   실차에서 확인됐다(주행모드 한 칸 밀림). 값 대조용 계측으로만 남긴다.
                        // ★시동 신호 후보 상시 기록(2026-09-11) — 시동 OFF 순간은 릴레이가 같이 내려가 스냅샷을 못 남긴다.
                        //   대신 값을 relay_state에 계속 올려 drive_state/gear/도어이벤트와 시계열로 대조한다.
                        //   유력 후보=bodywork.getPowerLevel(D5 시동판정과 동일 메서드). 공조 토글엔 무반응 확인됨(IGNSNAP 4회).
                        publishLive("power_level", bwd, new String[]{"getPowerLevel"});
                        publishLive("bcm_state", deviceByName("energy"), new String[]{"getBCMState"});
                        publishLive("ac_start_st", acd, new String[]{"getAcStartState"});   // ★공조 전원 실상태(ACSNAP 확정: 켜짐1/꺼짐0)
                        publishLive("drive_mode_get", deviceByName("energy"), new String[]{"getOperationMode"});
                        if (sunroofCfg == 1 && relay != null) {   // ★835 아토3 선루프 위치 %를 그대로(리모컨 상태)
                            int sp = callGetInt0(bwd, "getSunroofPosition");
                            if (sp >= 0) relay.putStatus("sunroof_pos_v", sp);   // 운영자 10-09 "환기 표시는 필요 없어, 그냥 %로"
                        }
                        publishLive("drl",      ltd, new String[]{"getDayTimeLightState"});
                    } catch (Throwable ignored) {}
                    // ★548 하향등·상향등 — 클러스터가 쓰던 `raw('light:LIGHT_LOW_BEAM_LIGHT')`는 **존재하지 않는 이름**이라 값이 영영 안 왔다
                    //   (운영자 실측 "하향등 켜도 안 됨"). 실차 CONSTS 결과: `LIGHT_LOW_BEAM=2 · LIGHT_HIGH_BEAM=3`(라이트 종류 상수)와
                    //   **static 필드 `mLowBeamLightIsOn` / `mHighBeamLightIsOn`**(실측값 0)이 존재. 이 필드를 직접 읽어 relay_state로 올린다.
                    //   raw(인메모리·LAN 전용) 대신 putState라 서버에도 남아 원격·사후 확인이 된다.
                    //   ★미검증: 이 static 필드가 실제 점등에 따라 갱신되는지. 실차에서 하향등 ON 시 값이 1로 바뀌는지 확인 필요(안 바뀌면 getter 탐색으로 전환).
                    try { publishLightBeams(); } catch (Throwable ignored) {}
                    // ★비상깜빡이(비상등) 상태 — getTurnLightFlashState(OFF=1·좌2·우4·양쪽6=비상등, 위상무관, 다른세션 실측). 폰 이탈 자동화가 6이면 시동끔/잠금 스킵+알림.
                    try {
                        // ★v405 버그수정 — deviceByName("light") 짧은 별칭은 스위치에 case 없어 항상 null → hazard 비트가 한 번도 안 채워져 하차 hazard 스킵이 死였음(2026-09-09 운영자 "비상등 점등중 하차트리거 안되는건 안해?"). 풀 클래스명으로.
                        Object ldev = deviceByName("android.hardware.bydauto.light.BYDAutoLightDevice");
                        // ★616 D5 제외 — D5는 getTurnLightFlashState가 항상 0이라 여기서 turnflash=0으로 덮어써, D5 폴러(getTurnLightState 1/2/4)와
                        //   번갈아 0↔실값 플랩(09-27 씨라7 테스트2 "turn 항상 0"·BSD turnOnly 알림 死의 원인). D5 turnflash는 startD5Poller 전담.
                        if (ldev != null && relay != null && !DiLink5Support.isDiLink5()) {
                            int tf = callGetInt0(ldev, "getTurnLightFlashState");
                            if (tf >= 0 && tf <= 1) turnReleasedSide = 0;
                            boolean releasedTail = turnReleasedSide != 0 && tf == turnReleasedSide && System.currentTimeMillis() - turnReleasedAt < 3000;   // ★873
                            if (tf >= 0 && !releasedTail && !(tf != turnEvtVal && System.currentTimeMillis() - turnEvtAt < 600))   // ★739 이벤트 직후 getter가 아직 옛값이면 덮지 않음
                                applyTurnFlash(tf, "poll", System.currentTimeMillis());   // ★2026-09-17 이벤트 경로와 같은 적용 함수(turnflash/hazard + 클러스터 측면 카메라 판단). 폴은 백스톱
                        }
                    } catch (Throwable ignored) {
                    }
                    // ★getAcWindModeNum 검증(2026-08-18) — v219 능동조회 4후보 다 실패(AC device엔 getAcWorkMode류 없음).
                    //   getAcWindModeNum이 이벤트 wm(1송풍/2A/C)과 항상 일치하면 능동조회로 반영 예정. 지금은 cool에 반영 안 함(오작동 오염 방지) — 값 비교 로그만.
                    if (relay != null) {
                        Object acp = deviceByName("android.hardware.bydauto.ac.BYDAutoAcDevice");
                        if (acp != null) {
                            int wnum = callGetInt0(acp, "getAcWindModeNum");
                            int wmode = callGetInt0(acp, "getAcWindMode");
                            Integer evWm = relay.acWorkMode();   // 이벤트로 받은 wm(없으면 null)
                            // ★v378 불일치 '매번' 로그 → 값 쌍(wnum,evWm)이 바뀔 때만 — 씰(DiLink3)은 getAcWindModeNum=2 vs 이벤트wm=1이 항상 불일치라 매 폴링 찍혀 D1 3531건/일 스팸(2026-09-07 실측).
                            if (evWm != null && (!acWmLogged || (wnum != evWm && (wnum != acWmLastNum || evWm != acWmLastEv)))) {
                                acWmLogged = true; acWmLastNum = wnum; acWmLastEv = evWm;
                                postToD1("RELAY", "AC검증 getAcWindModeNum=" + wnum + " getAcWindMode=" + wmode + " 이벤트wm=" + evWm + (wnum == evWm ? " ✓일치" : " ✗불일치(값 변화 시만 기록)"));
                            }
                        }
                    }
                    // ★v168 사이드미러 자동폴딩 상태 — a11y(:relay 프로세스)가 파일에 기록한 걸 읽어 앱 상태로 push(아이폰 토글 동기).
                    if (relay != null) {
                        try {
                            java.io.File mf = new java.io.File(getFilesDir(), "mirror_fold");
                            if (mf.exists()) {
                                byte[] mb = new byte[8]; java.io.FileInputStream mi = new java.io.FileInputStream(mf); int mn = mi.read(mb); mi.close();
                                if (mn > 0) { String ms = new String(mb, 0, mn, "UTF-8").trim(); if (ms.equals("0") || ms.equals("1")) relay.putStatus("mirror_fold", Integer.parseInt(ms)); }
                            }
                        } catch (Throwable ignored) {}
                    }
                    // ★외기온도 능동 조회 — 이벤트로 안 와서 getTemprature(4)로 직접 읽음(BydAutoStart 방식). 유효범위만 저장(시동 꺼짐 시 오값 스킵).
                    Object acd = deviceByName("android.hardware.bydauto.ac.BYDAutoAcDevice");
                    if (acd != null && relay != null) {
                        try {
                            Object r = acd.getClass().getMethod("getTemprature", int.class).invoke(acd, 4);
                            if (r instanceof Number) { int ot = ((Number) r).intValue(); if (ot > -40 && ot < 80) relay.putStatus("out_temp", ot); }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
                // ★적응형 폴 — 활성(P아님: R/D/N)엔 500ms로 빠르게(주행 중 D→N→R 후진을 즉시 잡아 6042 전에 비상등). 주차(P)엔 2s(배터리).
                int sleepMs = (lastGearVal == 3) ? 2000 : 500;
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) { break; }
            }
        }, "gear-poll").start();
    }

    // ★v82 gearbox 리스너 생명주기 — 주행 중에만 파워트레인 HAL에 바인딩. 주차/시동꺼짐 시 해제해 상시생존 보호.
    private Object gearboxDevice, gearboxListener;
    private volatile boolean gearboxRegistered = false;
    synchronized void registerGearbox() {
        if (gearboxRegistered) return;
        try {
            Class<?> gc = Class.forName("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
            if (gearboxDevice == null) {
                gearboxDevice = gc.getMethod("getInstance", Context.class).invoke(null, vcwRef);
                if (gearboxDevice != null) injectContext(gearboxDevice);
            }
            if (gearboxDevice == null) return;
            if (DiLink5Support.isDiLink5()) {
                // ★D5(2026-09-09): 하드코딩 AbsBYDAutoGearboxListener 등록 시 SDK가 TsManagerImpl→AbsTsMyCar.isOptimizationApi서 a.b.d로 캐스팅 실패→ClassCastException 메인스레드 크래시(씨라이언7 등록흐름 무한 크래시 실측 SnifferService$20).
                //   → registerListener 파라미터 타입을 런타임 발견해 맞는 프록시(인터페이스=Proxy·추상클래스=dexmaker) 등록. speed/bodywork/ac가 쓰는 검증 경로. onDataEventChanged→onVehicleEvent("gearbox").
                java.lang.reflect.Method reg = null;
                for (java.lang.reflect.Method m : gc.getMethods())
                    if (m.getName().equals("registerListener") && m.getParameterTypes().length == 1 && m.getParameterTypes()[0].isInterface()) { reg = m; break; }
                if (reg == null)
                    for (java.lang.reflect.Method m : gc.getMethods())
                        if (m.getName().equals("registerListener") && m.getParameterTypes().length == 1) { reg = m; break; }
                if (reg == null) { Log.w(TAG, "gearbox registerListener 없음(D5)"); return; }
                Class<?> lt = reg.getParameterTypes()[0];
                java.lang.reflect.InvocationHandler h = (p, method, args) -> {
                    if (method.getName().equals("onDataEventChanged") && args != null && args.length == 2) {
                        try { onVehicleEvent("gearbox", (Integer) args[0], (BYDAutoEventValue) args[1]); } catch (Throwable ignored) {}
                        return null;
                    }
                    if (method.getName().equals("hashCode")) return System.identityHashCode(p);
                    if (method.getName().equals("equals")) return args != null && p == args[0];
                    if (method.getName().equals("toString")) return "proxy-gearbox";
                    return null;
                };
                if (gearboxListener == null)
                    gearboxListener = lt.isInterface()
                        ? java.lang.reflect.Proxy.newProxyInstance(lt.getClassLoader(), new Class[]{lt}, h)
                        : com.android.dx.stock.ProxyBuilder.forClass(lt).handler(h).build();
                reg.invoke(gearboxDevice, gearboxListener);
            } else {
                Class<?> lc = Class.forName("android.hardware.bydauto.gearbox.AbsBYDAutoGearboxListener");
                if (gearboxListener == null) {
                    gearboxListener = new android.hardware.bydauto.gearbox.AbsBYDAutoGearboxListener() {
                        @Override public void onDataEventChanged(int featureId, BYDAutoEventValue value) { onVehicleEvent("gearbox", featureId, value); }
                    };
                }
                gc.getMethod("registerListener", lc).invoke(gearboxDevice, gearboxListener);
            }
            gearboxRegistered = true;
            Log.i(TAG, "gearbox 리스너 등록(주행감지 ON)");
        } catch (Throwable t) { Log.w(TAG, "gearbox 등록 실패", t); }
    }

    // ★581(그룹B, 09-24 비틀런처 classes.dex VehicleData.bsdProbe() 디컴파일 실측) — BSD/충돌경보·레이더·HUD·앞차출발(DiPilot)은
    //   registerListener가 아니라 BYDAutoADASDevice와는 **별개인 tsimpl 패키지 Manager 클래스**(AdasManagerImpl 등)의
    //   게터를 직접 폴링(비틀런처도 registerListener 안 씀). 정확한 BSD 게터명은 실디컴파일로 확보(bsdProbe() 원문).
    //   DiPilot은 정확한 메서드명 미확보라 get* 전수 덤프(instrument getterdump와 동일 패턴)로 실측.
    //   ★09-09 "TsManagerImpl→AbsTsMyCar 캐스팅 크래시"(registerGearbox 주석 참고)가 메인스레드에서 났던 전례 —
    //   이 프로브는 **별도 스레드**로 격리(hang·크래시가 나도 메인스레드·나머지 초기화엔 무영향). 부팅 1회만.
    private volatile boolean d5TsimplProbed = false;
    private void probeD5TsimplOnce() {
        if (d5TsimplProbed || !DiLink5Support.isDiLink5()) return;
        d5TsimplProbed = true;
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            probeTsimplDomain(sb, "adas", "android.hardware.bydauto.adas.tsimpl.AdasManagerImpl",
                new String[]{"getBlindMonitorState", "getBlindMonitorState1", "getBlindMonitorState2", "getBsisCollisionStateR",
                        "getBsisEnableR", "getBsisFuncStatusR", "getBsisFault", "getBlindFault", "getCollisionWarningState"});
            probeTsimplDomain(sb, "radar", "android.hardware.bydauto.radar.tsimpl.RadarManagerImpl", null);
            probeTsimplDomain(sb, "whud", "android.hardware.bydauto.whud.tsimpl.WhudManagerImpl",
                new String[]{"getHudState", "getHudMode", "getHudNavigationState", "getHudBrightness", "getHudPattern", "getHudSafeDriveState"});
            probeTsimplDomain(sb, "dipilot", "android.hardware.bydauto.dipilot.tsimpl.DiPilotManagerImpl", null);
            postToD1("D5TSIMPL", sb.toString());
        }, "d5-tsimpl-probe").start();
    }
    // ★598 D5 상태 폴러 — D5는 reg_domains 전 도메인이 fbRuntimeException(SDK Stub)이라 이벤트로 받던 값이 통째로 비었다
    //   (최근 7일 D5 52대 door_open/closed 0건 vs D3 828/1111건, speed·any_door_open 0%). 비틀런처 1.74 VehicleData.read를
    //   dexdump로 확인한 방식 그대로 게터를 1초마다 폴링해, 같은 feature 이름으로 relay.updateFromEvent에 넣는다
    //   → doorEdge·any_door_open·postDoorEvent 등 D3 이벤트 경로를 무수정 재사용.
    //   도어 = bodywork tsimpl BodyworkManagerImpl.getDoorState(area), area {1,3,2,4}, 1=열림, 0~1만 유효(비틀런처 read$bodyDoor).
    //   area→문 위치 대응은 미확인(any_door_open 판정엔 무관). 인코딩이 불확실한 값(트렁크·잠금·미러·BSD)은 이번엔 진단 로그만.
    private volatile boolean d5PollStarted = false;
    private boolean d5BootDoorChecked = false;
    private boolean d5TurnProbed = false;
    private Object d5TurnInst = null;
    private final java.util.List<String> d5TurnMethods = new java.util.ArrayList<>();
    // ★612 D5 깜빡이 후보 tsimpl 탐색 — 존재 클래스·Turn 무인자 int 게터를 relay_state(d5_turn_probe)로. 첫 발견 클래스만 매 틱 폴링(turn5_raw 뒤 병기).
    private void d5TurnProbe() {
        String[] cands = {"android.hardware.bydauto.light.tsimpl.LightManagerImpl", "android.hardware.bydauto.instrument.tsimpl.InstrumentManagerImpl",
                "android.hardware.bydauto.bodywork.tsimpl.BodyworkManagerImpl", "android.hardware.bydauto.dipilot.tsimpl.DiPilotManagerImpl"};
        StringBuilder rep = new StringBuilder();
        for (String fqn : cands) {
            String sn = fqn.substring(fqn.lastIndexOf('.') + 1);
            Class<?> c;
            try { c = DiLink5Support.loadD5Class(this, fqn); } catch (Throwable t) { c = null; }
            if (c == null) { rep.append(sn).append("=없음;"); continue; }
            java.util.List<String> ms = new java.util.ArrayList<>();
            for (java.lang.reflect.Method m : c.getMethods())
                if (m.getParameterTypes().length == 0 && m.getReturnType() == int.class && m.getName().startsWith("get") && m.getName().toLowerCase().contains("turn")) ms.add(m.getName());
            rep.append(sn).append('=').append(ms).append(';');
            if (d5TurnInst == null && !ms.isEmpty()) {
                Object inst = d5TsimplInstance(fqn);
                if (inst != null) { d5TurnInst = inst; d5TurnMethods.addAll(ms); rep.append("(폴링)"); }
            }
        }
        if (relay != null) relay.putStatus("d5_turn_probe", rep.toString());
        postToD1("D5POLL", "깜빡이 후보 탐색: " + rep);
    }
    private final java.util.Map<String, String> d5Last = new java.util.HashMap<>();
    private final java.util.Map<String, Object> d5Tsimpl = new java.util.HashMap<>();
    private Object d5TsimplInstance(String fqn) {
        if (d5Tsimpl.containsKey(fqn)) return d5Tsimpl.get(fqn);
        Object inst = null;
        try {
            Class<?> c = DiLink5Support.loadD5Class(this, fqn);
            if (c != null) {
                java.lang.reflect.Method f;
                try { f = c.getMethod("getInstance", Context.class); }
                catch (NoSuchMethodException e) { f = c.getMethod("getsInstance", Context.class); }
                inst = f.invoke(null, vcwRef);
            }
        } catch (Throwable t) { postToD1("D5POLL", fqn + " 획득 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage()); }
        if (inst != null) d5Tsimpl.put(fqn, inst);   // null(autoservice 미준비)이면 다음 틱에 재시도
        return inst;
    }
    // ★617 D5 DiPilot 리스너(비틀런처 실차 성공 경로) — 파사드가 아니라 DiPilotManagerImpl.registerListener(AbsBYDAutoDiPilotListener).
    //   게터(getAccState 등)는 D5에서 항상 0·2로 고정이라 콜백만 살아있다. 크루즈 상태(onAINavigationStateChanged 1해제/5ACC/3ICC — 실차 10-02 확정)를
    //   relay_state d5_ainav(원값)·d5_cruise(해석)로 노출하고 변화 시 D5ACC 로그. 나머지 int 콜백은 d5ev_<이름>으로 변화 시 노출 + D5EVT 로그(스로틀).
    private boolean d5DiPilotSubscribed = false;
    private int d5AiNavLast = -1;
    private void subscribeD5DiPilot() {
        d5DiPilotSubscribed = true;
        try {
            Object dp = d5TsimplInstance("android.hardware.bydauto.dipilot.tsimpl.DiPilotManagerImpl");
            if (dp == null) { relay.putStatus("d5_dipilot", "inst null"); return; }
            java.lang.reflect.Method reg = findRegisterListener(dp.getClass());
            if (reg == null) { relay.putStatus("d5_dipilot", "registerListener 없음"); return; }
            java.lang.reflect.InvocationHandler h = (p, m, a) -> {
                String mn = m.getName();
                if (mn.equals("hashCode")) return System.identityHashCode(p);
                if (mn.equals("equals")) return a != null && p == a[0];
                if (mn.equals("toString")) return "relayproxy-d5dipilot";
                if (mn.startsWith("on") && a != null && a.length == 1 && a[0] instanceof Integer && relay != null) {
                    int v = (Integer) a[0];
                    if (mn.equals("onAINavigationStateChanged")) {
                        relay.putStatus("d5_ainav", v); relay.putStatus("d5_ainav_ts", System.currentTimeMillis());
                        String cruise = v == 1 ? "해제" : v == 5 ? "ACC" : v == 3 ? "ICC" : "기타(" + v + ")";
                        relay.putStatus("d5_cruise", cruise);
                        if (v != d5AiNavLast) { int prevNav = d5AiNavLast; d5AiNavLast = v; postToD1("D5ACC", "크루즈 상태 " + cruise + " (값 " + v + ")"); d5IccLaneTransition(prevNav, v); }
                    }
                    else relay.putStatus("d5ev_" + mn.substring(2), v);
                }
                if (mn.startsWith("on") && a != null) onVehicleEventRaw("dipilot", mn, a);
                Class<?> rt = m.getReturnType();
                return rt == boolean.class ? Boolean.FALSE : (rt == int.class ? Integer.valueOf(0) : null);
            };
            reg.invoke(dp, makeRelayListener(reg.getParameterTypes()[0], h));
            relay.putStatus("d5_dipilot", "OK " + reg.getParameterTypes()[0].getSimpleName());
            postToD1("D5POLL", "DiPilot 리스너 등록 OK(" + reg.getParameterTypes()[0].getName() + ")");
        } catch (Throwable t) {
            if (relay != null) relay.putStatus("d5_dipilot", "FAIL " + rootMsg(t));
            postToD1("D5POLL", "DiPilot 리스너 등록 실패: " + rootMsg(t));
        }
    }
    // ★650 씨라7 ICC 차선 신호 탐색 2단계(운영자 09-29 승인) — 관측만, 동작 무변경. 폴링 게터는 D5에서 설정·고장·고정값이라 변화가 없었다.
    //   ① instrument·whud·adas·light tsimpl에도 DiPilot과 같은 방식으로 리스너 등록 → 콜백 이름·값을 D5LANE로(억제 없이, 10분 300줄 상한).
    //   ② VHAL(CarPropertyManager) 제조사 속성 0x2140~0x2161 구독 → 바뀐 값만 D5VHAL로(시끄러운 속성은 자동 제외). 권한 CAR_VENDOR_EXTENSION은 로컬 adb pm grant 시도.
    static final java.util.regex.Pattern D5_LANE_NAME = java.util.regex.Pattern.compile("(?i)lane|line|adas|acc|icc|lcc|lks|lka|tja|hwa|pilot|cruise|deviat|steer|hands|hod|nop|dnp|iop");
    private boolean d5ExtraSubscribed = false;
    private long d5LaneCbWin = 0; private int d5LaneCbN = 0;
    private final java.util.HashMap<String, Long> d5LaneCbLast = new java.util.HashMap<>();
    private void subscribeD5Extra() {
        d5ExtraSubscribed = true;
        StringBuilder rep = new StringBuilder();
        String[][] doms = {{"instrument", "android.hardware.bydauto.instrument.tsimpl.InstrumentManagerImpl"},
                           {"whud", "android.hardware.bydauto.whud.tsimpl.WhudManagerImpl"},
                           {"adas", "android.hardware.bydauto.adas.tsimpl.AdasManagerImpl"},
                           {"light", "android.hardware.bydauto.light.tsimpl.LightManagerImpl"}};
        for (String[] d : doms) {
            final String dom = d[0];
            try {
                Object inst = d5TsimplInstance(d[1]);
                if (inst == null) { rep.append(dom).append("=인스턴스없음 "); continue; }
                java.lang.reflect.Method reg = findRegisterListener(inst.getClass());
                if (reg == null) { rep.append(dom).append("=registerListener없음 "); continue; }
                final boolean filter = "instrument".equals(dom) || "light".equals(dom);
                java.lang.reflect.InvocationHandler h = (p, m, a) -> {
                    String mn = m.getName();
                    if (mn.equals("hashCode")) return System.identityHashCode(p);
                    if (mn.equals("equals")) return a != null && p == a[0];
                    if (mn.equals("toString")) return "relayproxy-d5" + dom;
                    if (mn.startsWith("on") && (!filter || D5_LANE_NAME.matcher(mn).find())) d5LaneCallback(dom, mn, a);
                    Class<?> rt = m.getReturnType();
                    return rt == boolean.class ? Boolean.FALSE : (rt == int.class ? Integer.valueOf(0) : null);
                };
                reg.invoke(inst, makeRelayListener(reg.getParameterTypes()[0], h));
                rep.append(dom).append("=OK ");
            } catch (Throwable t) { rep.append(dom).append("=FAIL(").append(rootMsg(t)).append(") "); }
        }
        if (relay != null) relay.putStatus("d5_lane_listeners", rep.toString().trim());
        postToD1("D5LANE", "계기판·HUD·ADAS·조명 리스너 등록: " + rep.toString().trim());
    }
    private synchronized void d5LaneCallback(String dom, String mn, Object[] a) {
        String val = a == null ? "" : java.util.Arrays.deepToString(a);
        String key = dom + "." + mn + val;
        long now = System.currentTimeMillis();
        Long prev = d5LaneCbLast.put(key, now);
        if (prev != null && now - prev < 2000) return;   // 같은 값 반복 2초 억제
        if (now - d5LaneCbWin > 600_000) { d5LaneCbWin = now; d5LaneCbN = 0; }
        if (++d5LaneCbN > 300) return;
        postToD1("D5LANE", "콜백 " + dom + "." + mn + val + " speed=" + (Double.isNaN(lastSpeed) ? "?" : String.valueOf((int) Math.round(lastSpeed))));
    }
    // ② VHAL 제조사 속성 관측 — android.car는 D5에만 있어 전부 리플렉션.
    private boolean d5VhalTried = false;
    private final java.util.HashMap<Integer, String> d5VhalLast = new java.util.HashMap<>();
    private final java.util.HashMap<Integer, Integer> d5VhalChg = new java.util.HashMap<>();
    private final java.util.HashSet<Integer> d5VhalNoisy = new java.util.HashSet<>();
    private long d5VhalStart = 0, d5VhalWin = 0; private int d5VhalN = 0;
    private void startD5VhalProbe() {
        d5VhalTried = true;
        new Thread(() -> {
            try {
                String perm = "android.car.permission.CAR_VENDOR_EXTENSION";
                if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    SplitLauncher.Res gr = SplitLauncher.adb(this, "pm grant " + getPackageName() + " " + perm);
                    postToD1("D5VHAL", "권한 부여 시도(pm grant) → " + (gr.ok ? "exit0 " : "실패 ") + gr.out.trim()
                            + " · 결과=" + (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED ? "부여됨" : "미부여"));
                }
                Class<?> carC = Class.forName("android.car.Car");
                Object car = carC.getMethod("createCar", Context.class).invoke(null, getApplicationContext());
                Object pm = carC.getMethod("getCarManager", String.class).invoke(car, "property");
                java.util.List<?> cfgs = (java.util.List<?>) pm.getClass().getMethod("getPropertyList").invoke(pm);
                Class<?> cbC = Class.forName("android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback");
                java.lang.reflect.Method regM = pm.getClass().getMethod("registerCallback", cbC, int.class, float.class);
                Object cb = java.lang.reflect.Proxy.newProxyInstance(cbC.getClassLoader(), new Class<?>[]{cbC}, (p, m, a) -> {
                    String mn = m.getName();
                    if (mn.equals("hashCode")) return System.identityHashCode(p);
                    if (mn.equals("equals")) return a != null && p == a[0];
                    if (mn.equals("toString")) return "relayproxy-d5vhal";
                    if (mn.equals("onChangeEvent") && a != null && a.length == 1 && a[0] != null) d5VhalEvent(a[0]);
                    return null;
                });
                int total = 0, ok = 0; StringBuilder fail = new StringBuilder();
                d5VhalStart = System.currentTimeMillis();
                for (Object cfg : cfgs) {
                    int id = (Integer) cfg.getClass().getMethod("getPropertyId").invoke(cfg);
                    if (id < 0x21400000 || id > 0x2161FFFF) continue;
                    total++;
                    try { if ((Boolean) regM.invoke(pm, cb, id, 0f)) ok++; }
                    catch (Throwable t) { if (fail.length() < 200) fail.append(Integer.toHexString(id)).append(':').append(rootMsg(t)).append(' '); }
                }
                postToD1("D5VHAL", "VHAL 전체 속성 " + cfgs.size() + "개 중 0x2140~0x2161 " + total + "개 구독 OK=" + ok + (fail.length() > 0 ? " 실패 예: " + fail : ""));
                if (relay != null) relay.putStatus("d5_vhal", "props=" + total + " ok=" + ok);
            } catch (Throwable t) {
                postToD1("D5VHAL", "VHAL 관측 실패: " + rootMsg(t));
                if (relay != null) relay.putStatus("d5_vhal", "FAIL " + rootMsg(t));
            }
        }, "d5-vhal").start();
    }
    private synchronized void d5VhalEvent(Object v) {
        try {
            int id = (Integer) v.getClass().getMethod("getPropertyId").invoke(v);
            Object val = v.getClass().getMethod("getValue").invoke(v);
            String s = val instanceof Object[] ? java.util.Arrays.deepToString((Object[]) val) : String.valueOf(val);
            if (d5VhalNoisy.contains(id)) return;
            String prev = d5VhalLast.put(id, s);
            if (prev == null || prev.equals(s)) return;
            long now = System.currentTimeMillis();
            int c = d5VhalChg.merge(id, 1, Integer::sum);
            if (now - d5VhalStart < 120_000 && c > 20) { d5VhalNoisy.add(id); postToD1("D5VHAL", "시끄러운 속성 제외 0x" + Integer.toHexString(id) + "(2분 20회↑)"); return; }
            if (now - d5VhalWin > 600_000) { d5VhalWin = now; d5VhalN = 0; }
            if (++d5VhalN > 300) return;
            postToD1("D5VHAL", "0x" + Integer.toHexString(id) + " " + prev + "→" + s + " speed=" + (Double.isNaN(lastSpeed) ? "?" : String.valueOf((int) Math.round(lastSpeed))));
        } catch (Throwable ignored) {}
    }
    private void d5Feed(String feature, int v) {
        String s = String.valueOf(v);
        if (s.equals(d5Last.put(feature, s))) return;
        relay.updateFromEvent(feature, s);
    }
    // ★638 씨라7 ICC(차선중앙유지) 신호 탐색(관측만, 동작 무변경) — D3는 ADAS TJA_ICA_STATE 3→2 전이로 알림하지만 D5엔 그 이벤트가 없다.
    //   D5TSIMPL 덤프는 로그 길이 한도로 get*B에서 잘려 차선 게터가 한 번도 안 찍혔다. dipilot의 차선·크루즈·핸즈오프 계열 무인자 int 게터를
    //   1초 폴에서 읽어 값이 바뀔 때만 D5LANE로(10분당 60줄 상한). ICC 켜고 차선 놓침·복귀를 겪은 로그로 어느 게터가 D3의 3→2인지 확정.
    // ★644 운전보조 값 전수 관측(운영자 09-29 "adas가 주는 모든 필드를 전수 조사") — 638의 이름 필터(27개)로는 ICC 사용 중 변화 0건.
    //   dipilot·adas tsimpl의 무인자 int/byte/boolean 게터 전부를 1초마다 읽어 바뀐 것만 D5LANE로(10분당 200줄 상한). 어느 값이 차선 놓침·복귀에 반응하는지 확정용.
    private java.util.List<Object[]> d5LaneMethods = null;   // {dev, Method, label}
    private final java.util.HashMap<String, Integer> d5LaneLast = new java.util.HashMap<>();
    private long d5LaneWinStart = 0; private int d5LaneWinN = 0;
    private void d5LaneObserve(Object dp) {
        try {
            if (d5LaneMethods == null) {
                d5LaneMethods = new java.util.ArrayList<>();
                Object adas = d5TsimplInstance("android.hardware.bydauto.adas.tsimpl.AdasManagerImpl");
                // ★650 계기판·HUD 게터 추가(운영자 09-29 "ICC가 있는데 차선 신호가 없을 리 없다 — 접근 방법 문제") — 차선을 실제로 그리는 곳.
                //   비틀런처 1.74는 whud getLeftLine/getRightLine/getShowAdasWork, instrument getINSAdasCenterDisplay를 읽는다. 둘은 속도 등 상시 변하는 값이 많아 이름 필터.
                Object instr = d5TsimplInstance("android.hardware.bydauto.instrument.tsimpl.InstrumentManagerImpl");
                Object whud = d5TsimplInstance("android.hardware.bydauto.whud.tsimpl.WhudManagerImpl");
                java.util.Map<String, Integer> cnt = new java.util.LinkedHashMap<>();
                for (Object[] dv : new Object[][]{{dp, "dipilot"}, {adas, "adas"}, {instr, "instrument"}, {whud, "whud"}}) {
                    if (dv[0] == null) { cnt.put((String) dv[1], -1); continue; }
                    boolean filter = "instrument".equals(dv[1]) || "whud".equals(dv[1]);
                    int n = 0;
                    for (java.lang.reflect.Method m : dv[0].getClass().getMethods()) {
                        if (!m.getName().startsWith("get") || m.getParameterTypes().length != 0 || m.getDeclaringClass() == Object.class) continue;
                        Class<?> rt = m.getReturnType();
                        if (rt != int.class && rt != byte.class && rt != boolean.class) continue;
                        if (filter && !D5_LANE_NAME.matcher(m.getName()).find()) continue;
                        d5LaneMethods.add(new Object[]{dv[0], m, dv[1] + "." + m.getName()});
                        n++;
                    }
                    cnt.put((String) dv[1], n);
                }
                postToD1("D5LANE", "운전보조 값 전수 관측 시작 — " + cnt + " (-1=인스턴스 없음)");
            }
            StringBuilder ch = new StringBuilder(); int first = 0;
            for (Object[] t : d5LaneMethods) {
                int v;
                try { Object r = ((java.lang.reflect.Method) t[1]).invoke(t[0]); v = r instanceof Boolean ? ((Boolean) r ? 1 : 0) : ((Number) r).intValue(); } catch (Throwable e) { continue; }
                Integer prev = d5LaneLast.put((String) t[2], v);
                if (prev == null) first++;
                else if (prev != v) ch.append(t[2]).append('=').append(prev).append("→").append(v).append(' ');
            }
            if (first > 0 && d5LaneLast.size() == first) postToD1("D5LANE", "첫 판독 " + first + "개 완료(이후 바뀐 값만 기록)");
            if (ch.length() == 0) return;
            long now = System.currentTimeMillis();
            if (now - d5LaneWinStart > 600_000) { d5LaneWinStart = now; d5LaneWinN = 0; }
            if (++d5LaneWinN > 200) return;
            postToD1("D5LANE", ch + "speed=" + (Double.isNaN(lastSpeed) ? "?" : String.valueOf((int) Math.round(lastSpeed))) + " turn=" + (relay != null ? relay.getStatus("turnflash") : null));
        } catch (Throwable ignored) {}
    }
    // ★642 차에 다가가면 헤드유닛에 원하는 화면(앱 하나 또는 비율 분할)을 미리 띄움(운영자 09-29). 폰 근접 false→true 순간 1회,
    //   떠날 때까지 다시 안 띄움(직접 바꾼 화면은 건드리지 않음). 주행 중·후진 중엔 안 함. 헤드유닛이 막 깨어났을 수 있어 3초 뒤 실행.
    private volatile boolean approachLaunchDone = false;
    private void maybeApproachLaunch() {
        if (approachLaunchDone || prefs == null) return;
        String js = prefs.getString("approach_launch", "");
        if (js.isEmpty()) return;
        final org.json.JSONObject al; try { al = new org.json.JSONObject(js); } catch (Throwable t) { return; }
        final String mode = al.optString("mode", "off");
        if ("off".equals(mode)) return;
        if (lastGearVal == 1) { postToD1("SPLIT", "시작 앱 건너뜀 — 후진 중"); return; }
        approachLaunchDone = true;
        new Thread(() -> {
            // ★648 시동 후 10초 안에 띄운다(운영자 09-29 "2분까지 안 가고 10초 내에"). 1초마다 맨 위 화면을 보고, 3초 동안 안 바뀌면 바로, 늦어도 10초에 띄움.
            //   그 10초 동안 뜬 화면 순서를 함께 기록(기본 앱이 몇 초에 뭐가 뜨는지 — 기준 다듬기용). 띄운 뒤 5초에 덮였으면 1회 다시 띄움.
            long t0 = System.currentTimeMillis();
            String lastTop = null; long stableSince = t0;
            StringBuilder seq = new StringBuilder();
            while (System.currentTimeMillis() - t0 < 10000) {
                String top = topActivity();
                long el = System.currentTimeMillis() - t0;
                if (!top.equals(lastTop)) { lastTop = top; stableSince = System.currentTimeMillis(); seq.append(" +").append(el / 1000).append("s ").append(top.isEmpty() ? "?" : top); }
                else if (el >= 3000 && System.currentTimeMillis() - stableSince >= 3000) break;
                sleepQuiet(1000);
            }
            postToD1("SPLIT", "시작 앱 — 시동 후 화면 순서:" + seq + " → " + ((System.currentTimeMillis() - t0) / 1000) + "초에 띄움");
            if (lastGearVal == 1) { postToD1("SPLIT", "시작 앱 건너뜀 — 후진 중"); return; }
            String r = launchStartApp(al, mode);
            postToD1("SPLIT", "시작 앱 띄움(" + mode + ", 시동 후) → " + r);
            sleepQuiet(5000);
            String after = topActivity();
            String p1 = SplitLauncher.pkgOf(al.optString("p1")), p2 = "split".equals(mode) ? SplitLauncher.pkgOf(al.optString("p2")) : "";
            if (!after.startsWith(p1 + "/") && (p2.isEmpty() || !after.startsWith(p2 + "/"))) {
                if (lastGearVal == 1) { postToD1("SPLIT", "시작 앱 재실행 건너뜀 — 후진 중"); return; }
                postToD1("SPLIT", "시작 앱이 " + (after.isEmpty() ? "?" : after) + "에 덮임 → 1회 다시 띄움 → " + launchStartApp(al, mode));
            }
            // ★643 미디어 앱 자동 재생(운영자 09-29) — 띄운 앱 중 미디어 앱(MediaBrowserService 선언)이 있으면 4초 뒤 재생 키 1회.
            if (al.optBoolean("autoplay", true)) {
                String media = null;
                for (String id : new String[]{al.optString("p1"), "split".equals(mode) ? al.optString("p2") : ""}) if (!id.isEmpty() && (THIRD_PLAYER_PKG.equals(SplitLauncher.pkgOf(id)) || isMediaApp(SplitLauncher.pkgOf(id)))) { media = id; break; }
                // ★660 진단(FB1473 아톰 "멜론 자동재생") — 시작 앱이 재생 대상으로 인식됐는지, 재생 키 전후에 어느 앱이 재생 중인지 기록(동작은 그대로).
                postToD1("SPLIT", "시작 앱 자동재생 대상 판정 — p1=" + al.optString("p1") + "(" + mediaJudge(al.optString("p1")) + ")"
                        + ("split".equals(mode) ? " p2=" + al.optString("p2") + "(" + mediaJudge(al.optString("p2")) + ")" : "") + " → 선택=" + (media == null ? "없음" : media));
                if (media != null) {
                    sleepQuiet(4000);
                    postToD1("SPLIT", "재생 키 전 미디어 세션 — " + mediaSessionBrief());
                    SplitLauncher.Res kr;
                    if (THIRD_PLAYER_PKG.equals(SplitLauncher.pkgOf(media))) {
                        // ★648 써드파티플레이어 = 전용 이어재생 액션(메인 세션 1.81). media3라 MediaBrowserService 선언이 없어 미디어 앱 판정에서 빠졌고,
                        //   공용 재생 키(126)는 다른 활성 세션(byd.mediacenter·musicmate)으로 샐 수 있다(09-29 실측). 큐가 비면 플레이어가 무시(안전).
                        kr = SplitLauncher.adb(this, "am startservice -n " + THIRD_PLAYER_PKG + "/.player.PlaybackService -a " + THIRD_PLAYER_PKG + ".action.RESUME_LAST");
                        postToD1("SPLIT", "시작 앱 자동 재생(" + media + ") → " + (kr.ok ? "이어재생 요청 " + kr.out.trim() : "실패 " + kr.out));
                    } else {
                        kr = SplitLauncher.adb(this, "input keyevent 126");   // KEYCODE_MEDIA_PLAY — 현재 미디어 세션(방금 띄운 앱)에 재생
                        postToD1("SPLIT", "시작 앱 자동 재생(" + media + ") → " + (kr.ok ? "재생 키 보냄" : "실패 " + kr.out));
                    }
                    sleepQuiet(2500);
                    postToD1("SPLIT", "재생 키 후 미디어 세션 — " + mediaSessionBrief());
                    if (THIRD_PLAYER_PKG.equals(SplitLauncher.pkgOf(media))) watchThirdPlayer("화면 띄움");
                }
            }
            // ★671 백그라운드 자동재생(운영자 10-01) — 화면 구성과 별개로 고른 미디어 앱 하나를 화면에 올리지 않고 재생. 플레이어=이어재생 서비스 호출,
            //   그 밖은 미디어 세션 연결로 재생 요청, 안 되면 잠깐 앞으로 올려 재생 키 → 시작 화면 다시 띄움. 단계마다 기록.
            final String bg = al.optString("bg", "");
            if (!bg.isEmpty()) {
                final String bgPkg = SplitLauncher.pkgOf(bg);
                sleepQuiet(2000);
                postToD1("SPLIT", "백그라운드 자동재생 시작 — " + bg + " / 재생 전 세션 " + mediaSessionBrief());
                if (THIRD_PLAYER_PKG.equals(bgPkg)) {
                    probeThirdPlayerState("요청 전", true);
                    // ★784 10-02~10-05 시동마다 뒤에서 깨우기(서비스 직접 시작·그 전 방송)는 오랩플 프로세스조차 안 뜸(서비스 시작 종료코드=-1, 24초 내내 세션 없음) —
                    //   헤드유닛이 화면에 없는 앱 깨우기를 막는 것으로 보임. 화면에 띄운 상태의 이어재생 요청은 재생됨(10-02 18:27·18:29 4초 뒤 state=3).
                    //   → 오랩플이 살아 있으면 뒤에서 먼저, 없거나 재생이 안 잡히면 잠깐 앞으로 올려 이어재생 요청 → 시작 화면 다시 띄움. 성공은 출력 문구 말고 실제 재생으로 본다.
                    boolean alive = !SplitLauncher.adb(this, "pidof " + THIRD_PLAYER_PKG).out.trim().isEmpty();
                    boolean playing = false;
                    if (alive) {
                        SplitLauncher.Res kr = SplitLauncher.adb(this, "am start-foreground-service -n " + THIRD_PLAYER_PKG + "/.player.PlaybackService -a " + THIRD_PLAYER_PKG + ".action.RESUME_LAST");
                        sleepQuiet(4000);
                        playing = bgPkgPlaying(THIRD_PLAYER_PKG);
                        postToD1("SPLIT", "백그라운드 자동재생(플레이어) 1단계 뒤에서 서비스 시작 종료코드=" + kr.exit + " " + kr.out.trim().replaceAll("\\s+", " ") + " → 재생 " + (playing ? "중 확인" : "확인 안 됨"));
                    } else {
                        postToD1("SPLIT", "백그라운드 자동재생(플레이어) 1단계 건너뜀 — 프로세스 없음(이 헤드유닛은 뒤에서 깨우기 안 됨)");
                    }
                    if (!playing) {
                        if (lastGearVal == 1) { postToD1("SPLIT", "백그라운드 자동재생 2단계 건너뜀 — 후진 중"); return; }
                        String lr = SplitLauncher.launchApp(this, bg);
                        sleepQuiet(4000);
                        SplitLauncher.Res sr = SplitLauncher.adb(this, "am startservice -n " + THIRD_PLAYER_PKG + "/.player.PlaybackService -a " + THIRD_PLAYER_PKG + ".action.RESUME_LAST");
                        sleepQuiet(2500);
                        playing = bgPkgPlaying(THIRD_PLAYER_PKG);
                        postToD1("SPLIT", "백그라운드 자동재생(플레이어) 2단계 앞으로 올려 이어재생 요청 → 띄움 " + lr + " / 요청 " + (sr.ok ? "보냄" : "실패 종료코드=" + sr.exit + " " + sr.out.trim().replaceAll("\\s+", " "))
                                + " / 재생 " + (playing ? "중 확인" : "확인 안 됨") + " → 시작 화면 다시 띄움 " + launchStartApp(al, mode));
                        // ★785 운영자 10-06 "안 되면 앞으로 띄워 재생 후 본 화면" — 오랩플은 앞에 띄우면 "앱 실행 시 마지막 음악 이어재생"으로 스스로도 재생하고
                        //   재생이 시작되면 전체 뮤직 화면을 연다(재생 시작 뒤 최대 ~10초, 메인 세션). 그 화면이 시작 화면 위로 다시 올라오면 12초 동안 2번까지 시작 화면을 다시 띄운다
                        int again = 0;
                        for (int k = 1; k <= 6 && again < 2; k++) {
                            sleepQuiet(2000);
                            String top = topActivity();
                            if (!top.startsWith(THIRD_PLAYER_PKG + "/")) continue;
                            if (lastGearVal == 1) { postToD1("SPLIT", "백그라운드 자동재생(플레이어) 뮤직 화면이 다시 올라왔지만 후진 중이라 둠"); break; }
                            again++;
                            postToD1("SPLIT", "백그라운드 자동재생(플레이어) 뮤직 화면이 다시 올라옴(+" + (k * 2) + "초 " + top + ") → 시작 화면 다시 " + launchStartApp(al, mode));
                        }
                    }
                } else {
                    String r1 = bgMediaPlay(bgPkg);
                    postToD1("SPLIT", "백그라운드 자동재생(" + bgPkg + ") 1단계 미디어 세션 연결 → " + r1);
                    boolean ok = r1.startsWith("재생 요청");
                    if (!ok) {
                        String rcv = SplitLauncher.sendPlayButton(this, bgPkg);
                        sleepQuiet(4000);
                        ok = rcv != null && bgPkgPlaying(bgPkg);
                        postToD1("SPLIT", "백그라운드 자동재생(" + bgPkg + ") 1.5단계 재생 버튼을 앱 수신기로 전송(" + (rcv == null ? "수신기 없음" : rcv) + ") → " + (ok ? "재생 중 확인" : "재생 확인 안 됨"));
                    }
                    if (!ok) {
                        if (lastGearVal == 1) { postToD1("SPLIT", "백그라운드 자동재생 2단계 건너뜀 — 후진 중"); return; }
                        String lr = SplitLauncher.launchApp(this, bg);
                        sleepQuiet(4000);
                        SplitLauncher.Res kr = SplitLauncher.adb(this, "input keyevent 126");
                        sleepQuiet(1500);
                        postToD1("SPLIT", "백그라운드 자동재생(" + bgPkg + ") 2단계 앞으로 올려 재생 키 → 띄움 " + lr + " / 키 " + (kr.ok ? "보냄" : "실패 " + kr.out) + " → 시작 화면 다시 띄움 " + launchStartApp(al, mode));
                    }
                }
                sleepQuiet(2500);
                postToD1("SPLIT", "백그라운드 자동재생 후 미디어 세션 — " + mediaSessionBrief());
                if (THIRD_PLAYER_PKG.equals(bgPkg)) watchThirdPlayer("백그라운드");
            }
        }, "approach-launch").start();
    }
    // ★693 오랩플 깨우기 전후 관찰(동작 변경 없음) — 플레이어 프로세스 유무·중요도, 정지 상태, 방송 수신기 등록, 배터리 제한(RUN_IN_BACKGROUND)을 기록한다. full=true 면 패키지 등록 상태까지.
    private void probeThirdPlayerState(String tag, boolean full) {
        try {
            SplitLauncher.Res pid = SplitLauncher.adb(this, "pidof " + THIRD_PLAYER_PKG);
            SplitLauncher.Res pr = SplitLauncher.adb(this, "dumpsys activity processes | grep -E 'thirdplayer' | head -6");
            SplitLauncher.Res ops = SplitLauncher.adb(this, "cmd appops get " + THIRD_PLAYER_PKG + " RUN_IN_BACKGROUND; cmd appops get " + THIRD_PLAYER_PKG + " RUN_ANY_IN_BACKGROUND");
            String ps = pr.out.replaceAll("\\s+", " ").trim();
            postToD1("SPLIT", "오랩플 상태(" + tag + ") 프로세스 " + (pid.out.trim().isEmpty() ? "없음" : "pid " + pid.out.trim()) + " / 백그라운드 허용 " + ops.out.replaceAll("\\s+", " ").trim()
                    + " / 프로세스 정보 " + (ps.isEmpty() ? "(없음)" : (ps.length() > 500 ? ps.substring(0, 500) + "…" : ps)));
            if (full) {
                SplitLauncher.Res pk = SplitLauncher.adb(this, "dumpsys package " + THIRD_PLAYER_PKG + " | grep -E 'stopped=|ResumeReceiver|RESUME_LAST|enabled=|versionName=' | head -10");
                String p = pk.out.replaceAll("\\s+", " ").trim();
                postToD1("SPLIT", "오랩플 등록(" + tag + ") — " + (p.isEmpty() ? "(조회 결과 없음)" : (p.length() > 700 ? p.substring(0, 700) + "…" : p)));
            }
        } catch (Throwable t) { postToD1("SPLIT", "오랩플 상태 관찰 예외 " + t.getClass().getSimpleName()); }
    }
    // ★687 오랩플 자동재생 안 됨 진단 — 요청 뒤 최대 24초간 플레이어 세션 상태를 4초마다 기록하고, 끝나면 플레이어·서비스 관련 시스템 로그 일부를 남긴다.
    private void watchThirdPlayer(String tag) {
        try {
            String last = "";
            for (int i = 1; i <= 6; i++) {
                sleepQuiet(4000);
                SplitLauncher.Res r = SplitLauncher.adb(this, "dumpsys media_session | grep -E 'package=|state=PlaybackState'");
                String st = "세션 없음"; String cur = "";
                if (r.ok) for (String ln : r.out.split("\\n")) {
                    int k = ln.indexOf("package=");
                    if (k >= 0) cur = ln.substring(k + 8).trim().split("\\s+")[0];
                    else if (cur.equals(THIRD_PLAYER_PKG)) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("state=(\\d+), position=(-?\\d+)").matcher(ln); if (m.find()) { st = "state=" + m.group(1) + " pos=" + m.group(2); break; } }
                }
                last = st;
                postToD1("SPLIT", "오랩플 재생 추적(" + tag + ") +" + (i * 4) + "초 — " + st);
                if (st.startsWith("state=3")) return;
            }
            SplitLauncher.Res lg = SplitLauncher.adb(this, "logcat -d -t 600 | grep -iE 'thirdplayer|PlaybackService|MixEngine|ForegroundService|startForeground' | tail -8");
            String o = lg.out.replaceAll("\\s+", " ").trim();
            postToD1("SPLIT", "오랩플 재생 안 됨(" + tag + ", 마지막 " + last + ") 시스템 로그 — " + (o.isEmpty() ? "(관련 로그 없음)" : (o.length() > 900 ? o.substring(0, 900) + "…" : o)));
        } catch (Throwable t) { postToD1("SPLIT", "오랩플 재생 추적 예외 " + t.getClass().getSimpleName()); }
    }
    // ★673 미디어 세션 목록에서 그 앱이 재생 중(state=3)인지 본다.
    private boolean bgPkgPlaying(String pkg) {
        try {
            SplitLauncher.Res r = SplitLauncher.adb(this, "dumpsys media_session | grep -E 'package=|state=PlaybackState'");
            if (!r.ok) return false;
            String cur = "";
            for (String ln : r.out.split("\\n")) {
                int i = ln.indexOf("package=");
                if (i >= 0) cur = ln.substring(i + 8).trim().split("\\s+")[0];
                else if (cur.equals(pkg) && ln.contains("state=3")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
    // ★671 미디어 앱의 재생 서비스에 직접 연결해 재생 요청(화면 없이 서비스만 깨움). 반환 "재생 요청…"=성공, 그 밖은 실패 사유.
    private String bgMediaPlay(String pkg) {
        try {
            java.util.List<android.content.pm.ResolveInfo> rs = getPackageManager().queryIntentServices(new android.content.Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0);
            if (rs.isEmpty() || rs.get(0).serviceInfo == null) return "미디어 서비스 없음";
            final android.content.pm.ServiceInfo si = rs.get(0).serviceInfo;
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            final String[] res = {"연결 시간 초과(8초)"};
            final android.media.browse.MediaBrowser[] br = new android.media.browse.MediaBrowser[1];
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    br[0] = new android.media.browse.MediaBrowser(SnifferService.this, new android.content.ComponentName(si.packageName, si.name),
                        new android.media.browse.MediaBrowser.ConnectionCallback() {
                            @Override public void onConnected() {
                                try {
                                    android.media.session.MediaController mc = new android.media.session.MediaController(SnifferService.this, br[0].getSessionToken());
                                    mc.getTransportControls().play();
                                    res[0] = "재생 요청 보냄(" + si.name.substring(si.name.lastIndexOf('.') + 1) + ")";
                                } catch (Throwable t) { res[0] = "재생 요청 예외 " + t.getClass().getSimpleName() + ":" + t.getMessage(); }
                                done.countDown();
                            }
                            @Override public void onConnectionFailed() { res[0] = "연결 거부"; done.countDown(); }
                        }, null);
                    br[0].connect();
                } catch (Throwable t) { res[0] = "연결 예외 " + t.getClass().getSimpleName() + ":" + t.getMessage(); done.countDown(); }
            });
            done.await(8, java.util.concurrent.TimeUnit.SECONDS);
            sleepQuiet(3000);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> { try { if (br[0] != null) br[0].disconnect(); } catch (Throwable ignored) {} });
            return res[0];
        } catch (Throwable t) { return "예외 " + t.getClass().getSimpleName() + ":" + t.getMessage(); }
    }
    static final String THIRD_PLAYER_PKG = "com.cseini.byd.thirdplayer";
    // ★660 진단용 — 이 시작 앱 항목이 자동재생 대상(미디어 앱)으로 인식되는지 한 줄 사유.
    private String mediaJudge(String id) {
        if (id == null || id.isEmpty()) return "없음";
        String pkg = SplitLauncher.pkgOf(id);
        if (THIRD_PLAYER_PKG.equals(pkg)) return "오너스랩플레이어";
        if (pkg.startsWith("com.cseini.byd.karaoke")) return "노래방 제외";
        return isMediaApp(pkg) ? "미디어앱 인식" : "미디어앱 아님(MediaBrowserService 선언 없음)";
    }
    // ★660 진단용 — 미디어 세션 패키지·재생상태 요약(dumpsys media_session). 못 읽으면 사유.
    private String mediaSessionBrief() {
        try {
            SplitLauncher.Res r = SplitLauncher.adb(this, "dumpsys media_session | grep -E 'package=|state=PlaybackState' | head -12");
            if (!r.ok) return "조회 실패 " + r.out;
            String o = r.out.replaceAll("\\s+", " ").trim();
            return o.isEmpty() ? "(활성 세션 없음)" : (o.length() > 420 ? o.substring(0, 420) + "…" : o);
        } catch (Throwable t) { return "예외 " + t.getClass().getSimpleName(); }
    }
    // 맨 위(resumed) 화면의 컴포넌트 "패키지/액티비티" — 못 읽으면 "".
    private String topActivity() {
        SplitLauncher.Res tr = SplitLauncher.adb(this, "dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity'");
        if (!tr.ok) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([\\w.]+/[\\w.$]+)").matcher(tr.out);
        return m.find() ? m.group(1) : "";
    }
    private String launchStartApp(org.json.JSONObject al, String mode) {
        StringBuilder lg = new StringBuilder(); String r;
        try {
            if ("split".equals(mode)) r = SplitLauncher.split(this, al.optString("p1"), al.optString("p2"), al.optInt("pct", 50), lg);
            else r = SplitLauncher.launchApp(this, al.optString("p1"));
        } catch (Throwable t) { r = "예외 " + t.getClass().getSimpleName(); }
        if ("split".equals(mode)) { saveSplitPrefs(); if (r != null && r.startsWith("분할 OK")) setSplitOwned(true); }   // ★724
        return r;
    }
    private boolean isMediaApp(String pkg) {
        if (pkg.startsWith("com.cseini.byd.karaoke")) return false;   // ★646 오너스랩노래방은 자동 재생 제외(운영자 09-29)
        return SplitLauncher.isMediaPkg(getPackageManager(), pkg);   // ★673 이 헤드유닛은 서비스 선언 조회가 전부 0이라 분류·알려진 앱 이름도 함께 본다
    }
    // ★642 분할 비율 유지(운영자 09-29 "7:3으로 띄웠는데 카메라 등 다른 화면 갔다 오면 5:5로 바뀜") — 차 쪽이 다른 화면에서 돌아올 때
    //   분할을 기본 5:5로 되돌린다. 실측 비율이 저장값과 다르고 5:5(±3)면 저장 비율로 복원. 5:5가 아닌 값은 사용자가 직접 바꾼 것으로 보고 새로 기억.
    //   앱·비율은 재시작 후에도 유지(prefs).
    // ★728 헤드유닛 카메라 창 설정·조정 — 폰 '주행/주차 보조 → 보여줄 곳 = 헤드유닛'이 HUASSIST {turn, rev}를, '헤드유닛에서 위치·크기 조정'이 {adjust: 2|4|8}을 보낸다.
    private String huAssistCmd(org.json.JSONObject p) {
        if (p == null || prefs == null) return "형식 오류";
        if (p.has("adjust")) {   // ★745 씨라7도 허용(보조 켬일 때 헤드유닛 창)
            int sd = p.optInt("adjust");
            int arr = sd == 8 && clusterCam.revSplit() ? clusterCam.revArr() : 0;
            if (sd == 8 && clusterCam.revSplit() && arr == 0) {   // ★748 후진 D = 왼쪽·오른쪽 따로 창 둘 다 조정
                if (huOverlay != null) huOverlay.adjust(82);
                if (huOverlay2 != null) huOverlay2.adjust(84);
            } else if (huOverlay != null) huOverlay.adjust(sd == 8 && arr != 0 ? (arr == 1 ? 85 : 86) : sd);   // ★815 붙인 한 창이면 그 창 하나
            postToD1("HUASSIST", "위치·크기 조정 창 " + sd + (sd == 8 && clusterCam.revSplit() ? (arr == 0 ? "(D 왼쪽·오른쪽 따로)" : arr == 1 ? "(D 가로로 붙임)" : "(D 세로로 붙임)") : ""));
            return null;   // ★731 null=성공(명령 규약) — 문자열을 돌려주면 BLE·워커 경로에선 실패로 보였다
        }
        // ★815 후진 D 헤드유닛 배치(0 따로 창 · 1 가로 · 2 세로) — 폰 990~이 함께 보낸다. 없으면(구버전 폰) 저장값 그대로. 이것만 온 명령이면 다른 설정은 그대로.
        if (p.has("rev_arr")) {
            int arr = Math.max(0, Math.min(2, p.optInt("rev_arr", 0)));
            if (arr != prefs.getInt("hu_rev_arr", 0)) {
                prefs.edit().putInt("hu_rev_arr", arr).apply();
                postToD1("HUASSIST", "후진 창 배치 " + (arr == 0 ? "왼쪽·오른쪽 따로 창" : arr == 1 ? "가로로 붙인 한 창" : "세로로 붙인 한 창") + " — 다음 후진부터 적용");
            }
            if (!p.has("place") && !p.has("turn") && !p.has("rev") && !p.has("hide_ms")) {
                publishHuAssist();
                return null;
            }
        }
        // ★739 신호가 끝나고 영상이 사라지기까지(0~3초, 0.1초 단위, 기본 1초) — 폰 964~가 함께 보낸다. 이것만 온 명령이면 다른 설정은 그대로.
        if (p.has("hide_ms")) {
            prefs.edit().putInt("hu_hide_ms", Math.max(0, Math.min(3000, p.optInt("hide_ms", 1000)))).apply();
            if (!p.has("place") && !p.has("turn") && !p.has("rev")) {
                publishHuAssist();
                postToD1("HUASSIST", "영상 사라지는 시간 " + prefs.getInt("hu_hide_ms", 1000) / 100 / 10.0 + "초");
                return null;
            }
        }
        // ★729 폰 962~는 기능 켬(on_turn·on_rev)과 보여줄 곳(place, 깜빡이·후진 공통)을 따로 보낸다 — 켜 두면 시동 켜진 동안 카메라 상시 대기.
        //   place 없는 961·안드 1.2.55 형식은 turn·rev가 '헤드유닛 표시'만 뜻한다(폰 표시를 켰는지는 모름 → 상시 대기 안 함).
        boolean onT, onR; String place;
        if (p.has("place")) {
            place = "hu".equals(p.optString("place")) ? "hu" : "phone";
            onT = p.optBoolean("on_turn", p.optBoolean("turn")); onR = p.optBoolean("on_rev", p.optBoolean("rev"));
        } else {
            onT = p.optBoolean("turn"); onR = p.optBoolean("rev");
            place = (onT || onR) ? "hu" : prefs.getString("assist_place", "phone");
        }
        boolean hu = "hu".equals(place);
        prefs.edit().putBoolean("assist_on_turn", onT).putBoolean("assist_on_rev", onR).putString("assist_place", place)
                .putBoolean("hu_assist_turn", hu && onT).putBoolean("hu_assist_rev", hu && onR).apply();
        publishHuAssist();
        String r = "카메라 보조 — 깜빡이 " + (onT ? "켬" : "끔") + " · 후진 " + (onR ? "켬" : "끔") + " · 보여줄 곳 " + (hu ? "헤드유닛" : "폰")
                + ((onT || onR) ? " · 시동 켜진 동안 카메라 상시 대기" : "") + " · 사라지는 시간 " + prefs.getInt("hu_hide_ms", 1000) / 100 / 10.0 + "초";
        postToD1("HUASSIST", r);
        return null;   // ★731 null=성공
    }
    private void publishHuAssist() {
        if (relay == null || prefs == null) return;
        if (!prefs.contains("assist_place") && (prefs.getBoolean("hu_assist_turn", false) || prefs.getBoolean("hu_assist_rev", false)))   // ★729 728에서 켠 헤드유닛 창 설정 이어받기
            prefs.edit().putBoolean("assist_on_turn", prefs.getBoolean("hu_assist_turn", false)).putBoolean("assist_on_rev", prefs.getBoolean("hu_assist_rev", false)).putString("assist_place", "hu").apply();
        relay.putStatus("hu_assist", (prefs.getBoolean("hu_assist_turn", false) ? "turn" : "") + "," + (prefs.getBoolean("hu_assist_rev", false) ? "rev" : ""));
        relay.putStatus("assist_cfg", "t" + (prefs.getBoolean("assist_on_turn", false) ? 1 : 0) + "r" + (prefs.getBoolean("assist_on_rev", false) ? 1 : 0) + "@" + prefs.getString("assist_place", "phone"));   // ★729 폰이 자기 설정과 비교해 다르면 다시 보낸다
        relay.putStatus("assist_hide", prefs.getInt("hu_hide_ms", 1000));   // ★739 따로 둔다(assist_cfg 모양을 바꾸면 963 이하 폰이 1분마다 다시 보냄)
        relay.putStatus("assist_rev_arr", prefs.getInt("hu_rev_arr", 0));   // ★815 후진 D 헤드유닛 배치 — 따로 둔다(같은 이유). 이 값이 있으면 폰은 '이 릴레이가 배치 선택을 지원'으로 본다
    }
    // ★739 재부팅 루틴 방지(운영자 10-04 "일렉트로처럼") — BYD 클라이언트 설정 서비스(CloudReboot)는 시동 꺼짐·잠금 상태에서 헤드유닛이 24시간
    //   (72시간·7일 기준도 있음) 넘게 켜져 있으면 reboot("bydcloud")로 재부팅한다(운영자 차 10-02 15:46·10-03 15:39·10-04 18:44, 그때마다 릴레이도 끊김).
    //   기준 시간(persist.sys.cloud_reboot_timeout)은 셸이 못 바꿔서(setprop 거부, 10-04 시험) 일렉트로처럼 그 앱을 끈다(pm disable-user).
    //   끄면 그 앱이 받는 BYD 원격 설정도 함께 멈춘다. 방지를 풀면 우리가 끈 경우에만 다시 켠다(다른 앱이 끈 것은 그대로).
    private static final String CLOUD_REBOOT_PKG = "com.byd.clientconfigurationservice";
    private volatile long rebootGuardAt;
    private void applyRebootGuard(String why) {
        if (prefs == null) return;
        rebootGuardAt = System.currentTimeMillis();
        new Thread(() -> {
            boolean on = prefs.getBoolean("reboot_guard", true);   // ★740 선택 아님 — 항상 켬(운영자 10-04 "옵션이 아니고 무조건 꺼져 있게"), RAW rebootGuard:false는 비상용
            String st = null;
            for (int i = 0; i < 4 && st == null; i++) {
                if (i > 0) try { Thread.sleep(20_000); } catch (InterruptedException e) { return; }
                st = AdbAccess.runShell(this, "pm list packages -d " + CLOUD_REBOOT_PKG);
            }
            if (st == null) {
                if (relay != null) relay.putStatus("reboot_guard_st", "?");
                if (on) postToD1("RELAY", "재부팅 루틴 방지(" + why + ") — 셸 연결 안 됨, 30분 뒤 다시");
                return;
            }
            boolean off = st.contains(CLOUD_REBOOT_PKG);
            String act = "";
            if (on && !off) {
                act = " → 끔: " + AdbAccess.runShell(this, "pm disable-user --user 0 " + CLOUD_REBOOT_PKG);
                prefs.edit().putBoolean("reboot_guard_did", true).apply();
            } else if (!on && off && prefs.getBoolean("reboot_guard_did", false)) {
                act = " → 다시 켬: " + AdbAccess.runShell(this, "pm enable --user 0 " + CLOUD_REBOOT_PKG);
                prefs.edit().putBoolean("reboot_guard_did", false).apply();
            }
            if (!act.isEmpty()) { String s2 = AdbAccess.runShell(this, "pm list packages -d " + CLOUD_REBOOT_PKG); if (s2 != null) off = s2.contains(CLOUD_REBOOT_PKG); }
            if (relay != null) relay.putStatus("reboot_guard_st", off ? "off" : "on");   // ★740 스위치 상태(reboot_guard)는 안 알림 — 964 폰이 스위치를 숨김
            if (!act.isEmpty() || "폰 앱 설정".equals(why))
                postToD1("RELAY", "재부팅 루틴 방지 " + (on ? "켬" : "끔") + "(" + why + ") — BYD 클라우드 재부팅 앱 " + (off ? "꺼짐(재부팅 안 함)" : "켜짐") + act);
        }, "reboot-guard").start();
    }
    private void loadSplitPrefs() {
        if (prefs == null) return;
        splitOwned = prefs.getBoolean("split_owned", false);   // ★724 같은 시동 중 릴레이 재시작이면 유지
        if (SplitLauncher.lastPkg1 != null) return;
        String p1 = prefs.getString("split_p1", null), p2 = prefs.getString("split_p2", null);
        if (p1 != null && p2 != null) { SplitLauncher.lastPkg1 = p1; SplitLauncher.lastPkg2 = p2; SplitLauncher.lastPct = prefs.getInt("split_pct", 50); }
    }
    private void saveSplitPrefs() {
        if (prefs == null || SplitLauncher.lastPkg1 == null) return;
        prefs.edit().putString("split_p1", SplitLauncher.lastPkg1).putString("split_p2", SplitLauncher.lastPkg2).putInt("split_pct", SplitLauncher.lastPct).apply();
    }
    // ★724 운영자 10-04 "시작 앱 비활성화했는데도 3:7로 떴어" — 헤드유닛이 스스로 되살린 5:5 분할을 저장 비율로 4번 바꿨다(킬 스위치 켠 상태에서도).
    //   이번 시동에 릴레이가 직접 띄운 분할(시작 앱·폰 원격 분할·원격 비율)만 지킨다. 시동 켬·끔 때 잊는다.
    private volatile boolean splitOwned = false;
    private HuAssistOverlay huOverlay;   // ★728 헤드유닛 카메라 창(깜빡이·후진)
    private HuAssistOverlay huOverlay2;  // ★748 후진 D(좌·우만)의 오른쪽 따로 창 — 왼쪽은 huOverlay가 맡는다(운영자 10-05 "좌측 우측 분리해서 놓고 싶은 위치에, 후방 영상 좌우에")
    private volatile long lastFakeDriveLogMs = 0;   // ★728 가짜 주행 보류 기록 1분 스로틀
    private void setSplitOwned(boolean v) {
        splitOwned = v;
        if (prefs != null) prefs.edit().putBoolean("split_owned", v).apply();
    }
    private volatile boolean splitKeeperStarted = false;
    private void startSplitRatioKeeper() {
        if (splitKeeperStarted) return;
        splitKeeperStarted = true;
        new Thread(() -> {
            long lastFix = 0; long wait = 15000; long lastActive = 0; boolean gap = false;
            while (true) {
                try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
                wait = System.currentTimeMillis() - lastActive < 120_000 ? 3000 : 15000;   // 확인 1회 = 로컬 adb 연결 1회 — 분할 중·분할이 사라진 뒤 2분(카메라 등 다녀오는 동안)은 3초, 그 외 15초
                try {
                    if (!splitOwned || allStopOn() || SplitLauncher.lastPkg1 == null) { gap = false; continue; }   // ★724 릴레이가 띄운 분할 아님·모든 자동 기능 정지
                    if (lastGearVal == 1) { gap = true; continue; }   // 후진 중(후진 복원 로직이 따로 담당) — 다른 화면에 다녀온 것으로 표시
                    int actual = SplitLauncher.actualPercent(this);
                    if (actual < 0) { gap = true; continue; }   // 지금 분할 상태 아님(다른 화면) — 다녀온 것으로 표시
                    wait = 3000; lastActive = System.currentTimeMillis();
                    int want = SplitLauncher.lastPct;
                    if (Math.abs(actual - want) <= 3) { gap = false; continue; }
                    // ★724 사건 순서로 가른다 — 분할이 사라졌다(다른 화면·후진) 5:5로 다시 나타나면 차가 초기화한 것 → 복원.
                    //   분할이 계속 떠 있는 채로 바뀐 비율(5:5 포함)은 운전자가 직접 바꾼 것 → 새 비율 기억, 되돌리지 않음.
                    // ★864 비율 맞추기가 실패해 남은 5:5도 운전자가 바꾼 게 아니다 — 두 번까지 다시 맞춘다(운영자 10-10 "3:7로 해도 5:5")
                    boolean failedApply = SplitLauncher.ratioFails > 0 && SplitLauncher.ratioFails < 3;
                    if ((gap || failedApply) && Math.abs(actual - 50) <= 3) {
                        long now = System.currentTimeMillis();
                        if (now - lastFix < 5000) continue;
                        lastFix = now;
                        StringBuilder lg = new StringBuilder();
                        String r = SplitLauncher.applyRatio(this, want, lg);
                        if (!r.endsWith("✓")) gap = false;   // 못 맞추면 더 다투지 않는다
                        postToD1("SPLIT", "분할 비율 복원 " + actual + "% → " + want + "% (" + (gap ? "다른 화면 다녀온 뒤 5:5로 초기화됨" : "앞서 비율 맞추기 실패") + ") → " + r);
                    } else {
                        gap = false;
                        SplitLauncher.lastPct = actual; saveSplitPrefs();
                        postToD1("SPLIT", "분할 비율을 화면에서 직접 바꿈 → 새 비율 " + actual + "% 기억");
                    }
                } catch (Throwable ignored) {}
            }
        }, "split-keeper").start();
    }
    // ★641 돌핀 문 상태 직접 읽기 탐색(기록만, 동작 무변경) — D3는 문을 이벤트로만 받는다(D5는 1초 폴러 있음).
    //   bodywork·instrument의 'Door' 게터(무인자 또는 area 1)를 1초마다 읽어 값이 바뀔 때만 DOORPOLL로(이벤트 기준값 병기, 10분당 120줄 상한).
    private volatile boolean d3DoorProbeStarted = false;
    private void startD3DoorProbe() {
        if (d3DoorProbeStarted || DiLink5Support.isDiLink5()) return;
        d3DoorProbeStarted = true;
        new Thread(() -> {
            java.util.List<Object[]> tg = null;   // {dev, Method, Integer argOrNull, label}
            java.util.HashMap<String, Integer> last = new java.util.HashMap<>();
            long winStart = 0; int winN = 0;
            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                try {
                    if (tg == null) {
                        Object bw = deviceByName("bodywork"), ins = deviceByName("instrument");
                        if (bw == null && ins == null) continue;
                        tg = new java.util.ArrayList<>();
                        StringBuilder nm = new StringBuilder();
                        for (Object[] dv : new Object[][]{{bw, "bodywork"}, {ins, "instrument"}}) {
                            if (dv[0] == null) continue;
                            for (java.lang.reflect.Method m : dv[0].getClass().getMethods()) {
                                String n = m.getName();
                                if (!n.startsWith("get") || !n.contains("Door") || m.getReturnType() != int.class) continue;
                                Class<?>[] pt = m.getParameterTypes();
                                if (pt.length == 0) tg.add(new Object[]{dv[0], m, null, dv[1] + "." + n});
                                else if (pt.length == 1 && pt[0] == int.class) tg.add(new Object[]{dv[0], m, 1, dv[1] + "." + n + "(1)"});
                                else continue;
                                nm.append(dv[1]).append('.').append(n).append(' ');
                            }
                        }
                        postToD1("DOORPOLL", "문 게터 후보 " + tg.size() + "개: " + nm);
                    }
                    StringBuilder ch = new StringBuilder();
                    for (Object[] t : tg) {
                        int v;
                        try { java.lang.reflect.Method m = (java.lang.reflect.Method) t[1]; Object r = t[2] == null ? m.invoke(t[0]) : m.invoke(t[0], t[2]); v = ((Number) r).intValue(); } catch (Throwable e) { continue; }
                        Integer pv = last.put((String) t[3], v);
                        if (pv != null && pv != v) ch.append(t[3]).append('=').append(pv).append("→").append(v).append(' ');
                    }
                    if (ch.length() == 0) continue;
                    long now = System.currentTimeMillis();
                    if (now - winStart > 600_000) { winStart = now; winN = 0; }
                    if (++winN > 120) continue;
                    postToD1("DOORPOLL", ch + "(이벤트 운전석=" + (relay != null ? relay.getStatus("door_driver") : null) + ")");
                } catch (Throwable ignored) {}
            }
        }, "d3-door-probe").start();
    }
    private void startD5Poller() {
        if (d5PollStarted || !DiLink5Support.isDiLink5()) return;
        d5PollStarted = true;
        new Thread(() -> {
            final int[] AREA = {1, 3, 2, 4};
            final String[] DOOR = {"INSTRUMENT_DD_LEFT_FRONT_DOOR_STATE", "INSTRUMENT_DD_RIGHT_FRONT_DOOR_STATE",
                    "INSTRUMENT_DD_LEFT_REAR_DOOR_STATE", "INSTRUMENT_DD_RIGHT_REAR_DOOR_STATE"};
            long lastDiag = 0;
            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                if (relay == null || vcwRef == null) continue;   // ★606 onCreate 즉시 기동 — initDevices(vcwRef)·릴레이 준비 전 틱은 건너뜀
                if (!d5DiPilotSubscribed) subscribeD5DiPilot();   // ★617 1회
                if (!d5ExtraSubscribed) subscribeD5Extra();       // ★650 계기판·HUD·ADAS·조명 리스너(관측)
                if (!d5VhalTried) startD5VhalProbe();             // ★650 VHAL 제조사 속성(관측)
                try {
                    boolean diagNow = System.currentTimeMillis() - lastDiag > 30 * 60_000L;   // 30분마다 1회 원값 진단
                    StringBuilder sb = diagNow ? new StringBuilder() : null;
                    Object bm = d5TsimplInstance("android.hardware.bydauto.bodywork.tsimpl.BodyworkManagerImpl");
                    if (bm != null) {
                        for (int i = 0; i < 4; i++) {
                            int v = callGetInt(bm, "getDoorState", AREA[i]);
                            if (sb != null) sb.append("door").append(AREA[i]).append('=').append(v).append(' ');
                            if (v == 0 || v == 1) d5Feed(DOOR[i], v);
                        }
                        // ★612 트렁크 후보 원값 관측(동작 변경 없음) — 씨라7 테스트2: D5 트렁크 이벤트 0건(폴러가 area1~4만 봄).
                        //   오토전비 D5 게터덤프에 bodywork getBackDoorCurState·getBackDoorPosition 존재. getDoorState(5)도 후보.
                        //   "cur/pos/area5"를 relay_state d5_trunk로(변화 시만) → 테스터 여닫음으로 의미 확정 후 door_trunk 연결.
                        String tk5 = callGetInt0(bm, "getBackDoorCurState") + "/" + callGetInt0(bm, "getBackDoorPosition") + "/" + callGetInt(bm, "getDoorState", 5);
                        if (!tk5.equals(d5Last.put("_trunk5", tk5))) { relay.putStatus("d5_trunk", tk5); relay.putStatus("d5_trunk_ts", System.currentTimeMillis()); }
                        // ★617→621 트렁크 연결 — 실차 확정(09-28 05:31 044186: 닫힘 "2/0/0" → 여는 중 "3/64/0" → 열림 "1/99/0").
                        //   범례 1/2/3은 getBackDoorCurState(1=열림·2=닫힘·3=작동 중)였고, getBackDoorPosition은 개도 %(0~99)라
                        //   617의 Position 1/3=열림 해석은 개도 1~3%에서 오판할 수 있었다. CurState 1·3 → 열림, 2 → 닫힘. 0=미지원 무시.
                        int bdc = callGetInt0(bm, "getBackDoorCurState");
                        if (bdc == 1 || bdc == 3) d5Feed("INSTRUMENT_DD_LUGGAGE_DOOR_STATE", 1);
                        else if (bdc == 2) d5Feed("INSTRUMENT_DD_LUGGAGE_DOOR_STATE", 0);
                        // ★609 재기동 첫 판독 '운전석 열림' → door_open 1회(운영자 승인 09-27). 하차 문열림 순간이 곧
                        //   com.ts.appservice.power 강제종료 시점이라(060251: drive_end+45s 킬·4s 부활·닫힘만 기록) 열림 엣지가 킬과 함께 사라지고,
                        //   doorEdge는 첫 값을 엣지로 안 쳐 이벤트가 없었다. 폰엔 늦게 도착한 하차 문열림과 동일. 첫 판독 '닫힘'은 합성 안 함(문트리거 시동 오발 위험).
                        if (!d5BootDoorChecked) {
                            d5BootDoorChecked = true;
                            Object dd = relay.getStatus("door_driver");
                            if (dd instanceof Integer && (Integer) dd == 1) {
                                // ★663(FB1499 양주 S7) — 하차 시동OFF에 릴레이가 강제 종료되면 drive_end 푸시가 같이 유실된다. 폰은 "주차 뒤 재접근 없음 = 하차 국면"일 때만
                                //   문트리거 시동 켜기를 막는데(drive_end 시각 기준), drive_end가 없어 이 합성 문 열림 직후 공조가 켜지고 시동이 걸렸다.
                                //   주행(drive_start)으로 기록된 채 운전석이 열려 있으면 하차이므로, 유실된 drive_end를 문 열림보다 먼저 보낸다.
                                int lastEmitB = prefs != null ? prefs.getInt("last_emit_drive", -1) : -1;
                                if (lastEmitB == 1) {
                                    relay.putStatus("drive_change_ts", System.currentTimeMillis());
                                    relay.postCarEvent("drive_end");
                                    if (prefs != null) prefs.edit().putInt("last_emit_drive", 0).apply();
                                    postToD1("D5POLL", "재기동 첫 판독: 주행으로 기록된 채 운전석 열림 → 유실된 drive_end 먼저 발사");
                                }
                                relay.postDoorEvent(1);
                                postToD1("D5POLL", "재기동 첫 판독 운전석 열림 → door_open 1회 발사(킬 중 유실된 하차 열림 보완)");
                            }
                        }
                        // ★610 잠금·미러 원값 관측(동작 변경 없음) — 문 열림·닫힘이 킬 공백 안에서 끝나는 (b)유형의 대체신호 후보.
                        //   추정(피드백분석 09-27, 미확인): lock 2=잠김·1=해제(lock1=운전석은 항상 0=미지원), mirror 1=접힘·2=펼침.
                        //   relay_state(링버퍼 없음)에 변화 시만 실어 drive_end 전후로 의미 검증. 이벤트화/앱 사용은 운영자 승인 후.
                        StringBuilder lk = new StringBuilder();
                        for (int a = 1; a <= 5; a++) lk.append(a > 1 ? "/" : "").append(callGetInt(bm, "getDoorLockStatus", a));
                        if (!lk.toString().equals(d5Last.put("_lock5", lk.toString()))) { relay.putStatus("d5_lock", lk.toString()); relay.putStatus("d5_lock_ts", System.currentTimeMillis()); }
                        Object mir5 = deviceByName("android.hardware.bydauto.doormirror.BYDAutoRearViewMirrorDevice");
                        if (mir5 != null) {
                            String mv = String.valueOf(callGetInt0(mir5, "getAutoExternalRearMirrorFoldedState"));
                            if (!mv.equals(d5Last.put("_mirror5", mv))) { relay.putStatus("d5_mirror", mv); relay.putStatus("d5_mirror_ts", System.currentTimeMillis()); }
                        }
                        if (sb != null) {
                            sb.append("lock=").append(lk).append(' ');
                            sb.append("shift=").append(callGetInt0(bm, "getShiftMode")).append(' ');
                        }
                    } else if (sb != null) sb.append("bodyMgr=null ");
                    // ★604 D5 BSD 좌/우 후보 실측(기록 전용, 경보 무변경) — 601 D5TSIMPL 결과(8대): BSD 게터는 AdasManagerImpl이 아니라
                    //   DiPilotManagerImpl에 있음(getBlindMonitorState=1·State1=1·State2=1, 주차 중). 주행 중 차가 있을 때 값이 바뀌는지,
                    //   1/2가 좌/우로 갈리는지 보려고 relay_state(bsd5_*)에 싣고 변화 시 D5BSD 로그. relay_state는 링버퍼 없이 항상 올라온다.
                    Object dp = d5TsimplInstance("android.hardware.bydauto.dipilot.tsimpl.DiPilotManagerImpl");
                    if (dp != null) {
                        int b0 = callGetInt0(dp, "getBlindMonitorState"), b1 = callGetInt0(dp, "getBlindMonitorState1"), b2 = callGetInt0(dp, "getBlindMonitorState2");
                        String bk = b0 + "/" + b1 + "/" + b2;
                        if (b1 == 1 || b1 == 2) handleBsd(b1);   // ★636 이벤트 유실 대비 폴 백스톱(같은 값이면 handleBsd가 무시)
                        d5LaneObserve(dp);
                        if (!bk.equals(d5Last.put("_bsd5", bk))) {
                            relay.putStatus("bsd5_all", b0); relay.putStatus("bsd5_1", b1); relay.putStatus("bsd5_2", b2);
                            postToD1("D5BSD", "BSD 원값 변화 all/1/2=" + bk + " speed=" + (Double.isNaN(lastSpeed) ? "?" : String.valueOf((int) Math.round(lastSpeed))) + " turn=" + relay.getStatus("turnflash"));
                        }
                    }
                    // ★605 D5 깜빡이 — 메인 폴의 getTurnLightFlashState가 D5에선 실패(-1)해 relay_state turnflash가 0으로 고정(09-27 bsd5 집계
                    //   38건 전부 turnflash=0). D5POLL 진단상 getTurnLightState는 값(주차 중 1)을 준다 → FlashState 우선, 실패 시 TurnLightState.
                    //   D3 인코딩(1=꺼짐·2=좌·4=우·6=비상)으로 보이는 값만 적용하고 원값은 turn5_raw로 남겨 실주행으로 확인.
                    Object ld = deviceByName("android.hardware.bydauto.light.BYDAutoLightDevice");
                    // ★612 D5 깜빡이 대체경로 탐색(관측만) — 씨라7 테스트2: 주행 중 차선변경에도 turn≠0 0건(light 디바이스는 0/1 고정).
                    if (!d5TurnProbed) { d5TurnProbed = true; d5TurnProbe(); }
                    if (ld != null) {
                        int tfs = callGetInt0(ld, "getTurnLightFlashState");
                        int tls = callGetInt0(ld, "getTurnLightState");
                        String tk = tfs + "/" + tls;
                        if (d5TurnInst != null) for (String m : d5TurnMethods) tk += "|" + callGetInt0(d5TurnInst, m);   // ★612 tsimpl 후보 원값 병기
                        if (!tk.equals(d5Last.put("_turn5", tk))) relay.putStatus("turn5_raw", tk);
                        int tf = (tfs == 1 || tfs == 2 || tfs == 4 || tfs == 6) ? tfs : tls;
                        if (tf == 1 || tf == 2 || tf == 4 || tf == 6) {
                            applyTurnFlash(tf, "d5poll", System.currentTimeMillis());
                            // ★636 D5는 LIGHT_TURN_SIGNAL 이벤트가 없어 turnSignalActive가 늘 false → BSD '깜빡이 때만' 게이트가 영영 닫혀 있었다. 폴 값으로 갱신.
                            boolean on = tf != 1, was = turnSignalActive;
                            turnSignalActive = on;
                            if (on) lastTurnSignalOnTs = System.currentTimeMillis();
                            if (on && !was && lastBsdValue == 2 && prefs != null && prefs.getBoolean(KEY_ALERT, false) && autoAllowed()) beep();
                        }
                    }
                    Object sp = deviceByName("speed");
                    double s1 = devDbl(sp, "getCurrentSpeed"), s2 = devDbl(sp, "getSpeedValue");
                    double spd = !Double.isNaN(s1) ? s1 : s2;
                    if (!Double.isNaN(spd) && spd >= 0 && spd < 300) { relay.putStatus("speed", (int) Math.round(spd)); lastSpeed = spd; if (spd > 3) speedLive = true; }
                    Object inst = deviceByName("instrument");
                    double ot = devDbl(inst, "getOutCarTemperature");
                    if (!Double.isNaN(ot) && ot > -40 && ot < 80) relay.putStatus("out_temp", (int) Math.round(ot));
                    if (sb != null) {
                        sb.append("speed=").append(s1).append('/').append(s2).append(" out=").append(ot);
                        Object st = deviceByName("setting");
                        sb.append(" tail=").append(callGetInt0(st, "getCurrentStateOfTheTailState"));
                        Object mir = deviceByName("android.hardware.bydauto.doormirror.BYDAutoRearViewMirrorDevice");
                        sb.append(" mirror=").append(callGetInt0(mir, "getAutoExternalRearMirrorFoldedState"));
                        sb.append(" turn=").append(callGetInt0(deviceByName("light"), "getTurnLightState"));
                        postToD1("D5POLL", sb.toString());
                        lastDiag = System.currentTimeMillis();
                    }
                } catch (Throwable t) {
                    if (System.currentTimeMillis() - lastDiag > 30 * 60_000L) { postToD1("D5POLL", "폴링 예외 " + t.getClass().getSimpleName() + ":" + t.getMessage()); lastDiag = System.currentTimeMillis(); }
                }
            }
        }, "d5-poll").start();
    }
    private void probeTsimplDomain(StringBuilder out, String tag, String fqn, String[] methods) {
        try {
            Class<?> c = DiLink5Support.loadD5Class(this, fqn);
            if (c == null) { out.append(tag).append("=클래스없음; "); return; }
            // ★594 — 582 전멸 근본원인(09-27 비틀런처 1.74 Dev.get 디컴파일 확인): adas/whud/dipilot ManagerImpl은 정상 철자
            //   getInstance가 없고 BYD SDK 오타형 팩토리 getsInstance(Context)만 있다(radar만 정상 철자라 통과했음).
            java.lang.reflect.Method factory;
            try { factory = c.getMethod("getInstance", Context.class); }
            catch (NoSuchMethodException e) { factory = c.getMethod("getsInstance", Context.class); }
            Object dev = factory.invoke(null, vcwRef);
            if (dev == null) { out.append(tag).append("=getInstance null; "); return; }
            out.append(tag).append('[');
            if (methods != null) {
                for (String mn : methods) {
                    try { out.append(mn).append('=').append(c.getMethod(mn).invoke(dev)).append(' '); }
                    catch (Throwable t) { out.append(mn).append("=err(").append(t.getClass().getSimpleName()).append(") "); }
                }
            } else if ("radar".equals(tag)) {
                try {
                    java.lang.reflect.Method m = c.getMethod("getRadarObstacleDistance", int.class);
                    for (int i = 0; i < 6; i++) { try { out.append(m.invoke(dev, i)).append('/'); } catch (Throwable t) { out.append("err/"); } }
                } catch (Throwable t) { out.append("getRadarObstacleDistance 없음(").append(t.getClass().getSimpleName()).append(')'); }
            } else {
                int n = 0;
                for (java.lang.reflect.Method m : c.getMethods()) {
                    if (m.getParameterTypes().length == 0 && m.getName().startsWith("get") && n++ < 30) {
                        try { out.append(m.getName()).append('=').append(m.invoke(dev)).append(' '); } catch (Throwable ignored) {}
                    }
                }
            }
            out.append("]; ");
        } catch (Throwable t) {
            out.append(tag).append("=예외(").append(t.getClass().getSimpleName()).append(':').append(t.getMessage()).append("); ");
        }
    }

    synchronized void unregisterGearbox() {
        if (!gearboxRegistered || gearboxDevice == null || gearboxListener == null) return;
        try {
            Class<?> lc = Class.forName("android.hardware.bydauto.gearbox.AbsBYDAutoGearboxListener");
            try { gearboxDevice.getClass().getMethod("unregisterListener", lc).invoke(gearboxDevice, gearboxListener); }
            catch (NoSuchMethodException e) { gearboxDevice.getClass().getMethod("unregisterListener").invoke(gearboxDevice); }
            gearboxRegistered = false;
            Log.i(TAG, "gearbox 리스너 해제(주차/시동꺼짐 — 파워트레인 바인딩 해제, 생존보호)");
        } catch (Throwable t) { Log.w(TAG, "gearbox 해제 실패", t); }
    }

    // ★v144 speed 리스너 — getSpeedFromGateway가 리스너 없이 stale(역대 전건 0.0)이라 이벤트로 속도를 직접 캡처.
    //   ICC 차로알림 정차 제외(속도≤3) 판정에 씀. registerListener(interface) 오버로드가 없으면 조용히 skip → fail-open이 알림 커버.
    private Object speedDevice, speedListener;
    private volatile boolean speedRegistered = false;
    private volatile long lastSpeedEvtLog = 0;
    synchronized void registerSpeed() {
        if (speedRegistered) return;
        try {
            String cn = "android.hardware.bydauto.speed.BYDAutoSpeedDevice";
            Class<?> dc = Class.forName(cn);
            speedDevice = dc.getMethod("getInstance", Context.class).invoke(null, vcwRef);
            if (speedDevice == null) { postToD1("RELAY", "speed device null"); return; }
            injectContext(speedDevice);
            java.lang.reflect.Method reg = null;
            for (java.lang.reflect.Method m : dc.getMethods())
                if (m.getName().equals("registerListener") && m.getParameterTypes().length == 1 && m.getParameterTypes()[0].isInterface()) { reg = m; break; }
            if (reg == null) { onDemandDevices.put(cn, speedDevice); postToD1("RELAY", "speed registerListener(interface) 없음 — 게터 fallback(fail-open)"); return; }
            Class<?> itf = reg.getParameterTypes()[0];
            speedListener = java.lang.reflect.Proxy.newProxyInstance(itf.getClassLoader(), new Class[]{itf}, (p, method, args) -> {
                if (method.getName().equals("onDataEventChanged") && args != null && args.length == 2) {
                    try {
                        int fid = (Integer) args[0];
                        int iv = args[1].getClass().getField("intValue").getInt(args[1]);
                        if (fid == 874512392 && iv >= 0 && iv <= 300) { lastSpeed = iv; if (iv > 3) speedLive = true; }   // ★v152 fid 874512392만 실차속(km/h). 303038472 등 다른 fid는 항상0이라 lastSpeed 오염 → 제외
                        long now = System.currentTimeMillis();
                        boolean spdFid = (fid == 874512392 || fid == 874512400);   // ★v220 속도가드 재검토(2026-08-18) — 두 속도후보 fid를 5초 조밀 로깅해 정차/주행 신뢰성 실측(안정적이면 ICC 정차억제 재도입)
                        if (now - lastSpeedEvtLog > (spdFid ? 5000 : 60000)) { lastSpeedEvtLog = now; postToD1("SPEEDEVT", "fid=" + fid + " v=" + iv); }
                    } catch (Throwable ignored) {}
                    return null;
                }
                if (method.getName().equals("hashCode")) return System.identityHashCode(p);
                if (method.getName().equals("equals")) return args != null && p == args[0];
                if (method.getName().equals("toString")) return "proxy-speed";
                return null;
            });
            reg.invoke(speedDevice, speedListener);
            onDemandDevices.put(cn, speedDevice);   // deviceByName("speed")도 이 인스턴스 사용(게터 live 공유)
            speedRegistered = true;
            postToD1("RELAY", "speed 리스너 등록 OK");
        } catch (Throwable t) { postToD1("RELAY", "speed 등록 실패: " + t.getClass().getSimpleName()); }
    }

    // 무인자 getter 리플렉션 헬퍼 — 못 읽으면 int는 MIN_VALUE, double은 NaN.
    private int lastD5Gear = Integer.MIN_VALUE;   // ★552 씨라7 getGear 관측값(판정에는 쓰지 않음 — 인코딩 실차검증 전)
    private boolean d5GearProbed = false;         // ★555 gearbox 실물 클래스·메서드 1회 덤프(이름 확정용)
    private long lastLaneSkipLogTs = 0;           // ★558 차선알림 스킵 사유 로그 스로틀(60s)
    private int devInt(Object dev, String getter) {
        if (dev == null) return Integer.MIN_VALUE;
        try { Object r = dev.getClass().getMethod(getter).invoke(dev);
            if (r instanceof Number) return ((Number) r).intValue();
            if (r instanceof Boolean) return ((Boolean) r) ? 1 : 0;
        } catch (Throwable ignored) {}
        return Integer.MIN_VALUE;
    }
    private double devDbl(Object dev, String getter) {
        if (dev == null) return Double.NaN;
        try { Object r = dev.getClass().getMethod(getter).invoke(dev);
            if (r instanceof Number) return ((Number) r).doubleValue();
        } catch (Throwable ignored) {}
        return Double.NaN;
    }
    // ★v127 int인자 getter(getSeatVentilatingState(seat) 등) 호출 — 실패(-1). 좌석상태 미러 폴링용.
    private int callGetInt(Object dev, String method, int arg) {
        try { Object r = dev.getClass().getMethod(method, int.class).invoke(dev, arg);
            if (r instanceof Number) return ((Number) r).intValue(); } catch (Throwable ignored) {}
        return -1;
    }
    // ★리모컨 실시간 상태 발행 — 후보 getter를 순서대로 시도해 성공한 값을 relay_state[key]로. 성공 메서드는 키당 1회만 로그(스팸 방지).
    //   -1/쓰레기(-2147482648)는 실패로 보고 다음 후보. 전부 실패면 발행 안 함(패널은 마지막 값 유지).
    private final java.util.Set<String> liveLogged = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    // ★feature 읽기로 상태 발행 — getter가 없는 항목(설정온도·선쉐이드 위치·성에)은 get(int[],Class) 경로로 읽는다.
    // ★2026-09-10 실차 확정: 이 차(D3 돌핀)는 get(int[],Class) feature 읽기가 전 디바이스에서 NA다.
    //   한 번 실패한 키는 다시 시도하지 않는다(매 폴링 리플렉션 낭비 방지). 값은 이벤트 구독으로만 들어온다.
    // ★768 FB2025 — 씨라7은 feature 읽기가 되는 기종이라 한 번 무효(시동 OFF 중 등)로 영구 포기하면 그 프로세스 동안 값이 얼어붙는다 → 10분 뒤 다시 시도.
    //   돌핀(D3)은 전 디바이스 NA라 종전대로 영구 포기.
    private final java.util.Map<String, Long> featReadDead = new java.util.concurrent.ConcurrentHashMap<>();
    private int lastShadeVal = Integer.MIN_VALUE; private long lastShadeTsMs = 0;
    private void publishLiveFeat(String key, Object dev, String... featNames) {
        if (dev == null || relay == null) return;
        Long deadAt = featReadDead.get(key);
        if (deadAt != null) {
            if (!DiLink5Support.isDiLink5() || System.currentTimeMillis() - deadAt < 600_000L) return;
            featReadDead.remove(key);
        }
        StringBuilder miss = new StringBuilder();
        for (String featName : featNames) {
            int fid;
            try { fid = featureIdByName(featName); }
            catch (Throwable t) { miss.append(featName).append("=이름없음 "); continue; }
            int v = getFeatureInt(dev, fid);
            if (v == Integer.MIN_VALUE || v == -2147482648 || v == 65535 || v < 0) { miss.append(featName).append("=").append(v).append(" "); continue; }
            relay.putStatus(key, v);
            if ("sunshade_pct".equals(key) && (v != lastShadeVal || System.currentTimeMillis() - lastShadeTsMs > 60_000L)) { lastShadeVal = v; lastShadeTsMs = System.currentTimeMillis(); relay.putStatus("sunshade_ts", lastShadeTsMs); }   // ★10-10 값이 바뀔 때나 1분에 한 번만(매 읽기마다 쓰면 주행 중 전체 올림 유발)   // ★768 FB2025 마지막으로 읽은 시각(앱 '시동 끈 뒤 확인 안 됨 · 시각')
            if (liveLogged.add(key)) postToD1("CAPS", "실시간 상태 " + key + " ← feature " + featName + "(" + fid + ") = " + v);
            return;
        }
        featReadDead.put(key, System.currentTimeMillis());
        if (liveLogged.add(key + ":fail")) postToD1("CAPS", "실시간 상태 " + key + " — feature 전부 실패(" + (DiLink5Support.isDiLink5() ? "10분 뒤 다시" : "이후 재시도 안 함") + "): " + miss);
    }

    private void publishLive(String key, Object dev, String[] candidates) {
        if (dev == null || relay == null) return;
        for (String m : candidates) {
            int v = callGetInt0(dev, m);
            if (v == -1 || v == Integer.MIN_VALUE || v == -2147482648) continue;
            relay.putStatus(key, v);
            if (liveLogged.add(key)) postToD1("CAPS", "실시간 상태 " + key + " ← " + m + "() = " + v);
            return;
        }
        if (liveLogged.add(key + ":fail")) postToD1("CAPS", "실시간 상태 " + key + " — 후보 getter 전부 실패(" + candidates.length + "개)");
    }

    private volatile int sunroofCfg = -1;   // ★835 getMoonRoofConfig(1=유리 선루프 있음·아토3) — 리모컨 목록 만들 때 채움
    private int callGetInt0(Object dev, String method) {
        try { Object r = dev.getClass().getMethod(method).invoke(dev);
            if (r instanceof Number) return ((Number) r).intValue(); } catch (Throwable ignored) {}
        return -1;
    }

    // ── ★v55 dadb 로컬 adbd 인증 진단 — app_process 원격끔 면역의 다음 단계 ──
    //   ADBPROBE에서 AUTH(adbd 살아있음·RSA 필요) 확인됨. 여기선 dadb로 RSA 키 생성 후 실제 인증+shell "id" 시도.
    //   최초 1회 헤드유닛에 "USB 디버깅 허용?" 팝업 → 사용자가 허용해야 통과. 성공하면 shell 권한 확보 →
    //   다음 단계로 app_process 데몬 실행 가능(force-stop 면역). prefs "adb_probe_done"로 1회만 실행.
    private void dadbShellProbe() {
        new Thread(() -> {
            try { Thread.sleep(12000); } catch (InterruptedException e) { return; }
            try {
                java.io.File pk = new java.io.File(getFilesDir(), "adbkey");
                java.io.File pub = new java.io.File(getFilesDir(), "adbkey.pub");
                if (!pk.exists() || !pub.exists()) {
                    dadb.AdbKeyPair.Companion.generate(pk, pub);
                    postToD1("ADBSHELL", "RSA 키 생성 완료 (" + pk.getName() + ")");
                }
                dadb.AdbKeyPair kp = dadb.AdbKeyPair.Companion.read(pk, pub);
                postToD1("ADBSHELL", "dadb 연결 시도 127.0.0.1:5555 — 헤드유닛에 'USB 디버깅 허용?' 팝업 뜨면 허용하세요");
                dadb.Dadb d = dadb.Dadb.Companion.create("127.0.0.1", 5555, kp, 8000, 20000);
                dadb.AdbShellResponse r = d.shell("id");
                postToD1("ADBSHELL", "✅ shell 성공! exit=" + r.getExitCode() + " id=" + r.getOutput().trim());
                // 부가 확인: 우리 앱 stopped 상태 + adbd 재부팅유지 여부 판단용 프로퍼티
                try { dadb.AdbShellResponse r2 = d.shell("getprop service.adb.tcp.port"); postToD1("ADBSHELL", "adb.tcp.port=" + r2.getOutput().trim()); } catch (Throwable ignored) {}
                // ★v80 배터리 최적화 예외 자동등록 — 헤드유닛엔 설정 다이얼로그가 없어("멀티미디어 시스템 미지원") 자동요청이 실패한다.
                //   shell로 deviceidle 화이트리스트에 직접 넣어 isIgnoringBatteryOptimizations=true로 만든다(백그라운드 제한 완화).
                //   BYD 자작앱 전부(릴레이·오토트립·노래방) 한 번에 — 개별 앱은 shell이 없어 릴레이가 대행. 재부팅 시 초기화되므로 매 시작 재등록.
                for (String pkg : new String[]{"com.cseini.bydsniffer", "com.cseini.bydautotrip", "com.cseini.byd.karaoke"}) {
                    try { d.shell("dumpsys deviceidle whitelist +" + pkg); } catch (Throwable ignored) {}
                }
                try {
                    dadb.AdbShellResponse wl = d.shell("dumpsys deviceidle whitelist | grep -c cseini");
                    postToD1("BATTERY", "화이트리스트 자동등록 완료 — cseini 앱 " + wl.getOutput().trim() + "개 등재");
                } catch (Throwable ignored) {}
                // ★v83 워치독 자동 재설치 — 원격 CLOSEAIR(force-stop) 부활의 유일한 수단인 shell 데몬을 매 시작마다 재설치.
                //   기존엔 수동 ADB_AUTH 때만 설치돼, 헤드유닛 콜드부팅으로 데몬이 죽으면 재설치 안 돼 원격끔 생존이 깨졌다(2026-07-29 회귀).
                //   installWatchdog이 pkill로 기존 정리 후 재설치 → 멱등. 이제 재부팅 후에도 자동 복구.
                try { installWatchdog(d); } catch (Throwable t) { postToD1("WATCHDOG", "시작 시 재설치 실패: " + t.getClass().getSimpleName()); }
                d.close();
            } catch (Throwable t) {
                Throwable c = t.getCause();
                postToD1("ADBSHELL", "dadb 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage()
                        + (c != null ? " cause=" + c.getClass().getSimpleName() + ":" + c.getMessage() : "")
                        + " (팝업 미허용 or adbd 인증거부)");
            }
        }, "dadb-probe").start();
    }

    // ★애프터블로우: AC device의 no-arg getter 전부 + idToName의 AC 관련 feature(AFTER/DRY/EVAPOR/BLOW/CLEAN 등) 스캔.
    //   순정 애프터블로우(시동 끄고 자동 송풍)를 트리거하는 feature/스위치가 있는지 발견용. 시동 켜진 상태에서 실행해야 유효.
    // ★차종옵션 감지 — device.hasFeature("이름")(1=지원). BydAutoStart 방식(2026-08-13 실차검증: 돌핀액티브 앞좌통풍열선·핸들열선 O, 뒷좌열선 X).
    //   결과를 relay state "caps"(|S1V|S1H|WH|SHADE| 형태)로 → 앱이 없는 옵션 UI 숨김.
    private int hasFeat(Object dev, String name) {
        if (dev == null) return -1;
        try { return ((Number) dev.getClass().getMethod("hasFeature", String.class).invoke(dev, name)).intValue(); }
        catch (Throwable t) { return -2; }
    }
    // ★714/712 유리 선루프(문루프) 읽기 진단(운영자 10-03 "아토3는 틸트·슬라이드 다 된다") — 차를 움직이는 명령은 부르지 않는다.
    //   bodywork의 선루프·가리개 관련 메서드 목록(인자형)과 인자 없는 get/is 값만 relay_state.roof_diag에 올린다. 열기·닫기·틸트 값 의미는 차주와 시간을 맞춘 실차 시험에서 확정.
    private String roofDiag(Object bw) {
        if (bw == null) return "bodywork 없음";
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        try {
            for (java.lang.reflect.Method m : bw.getClass().getMethods()) {
                String n = m.getName();
                if (!n.toLowerCase(java.util.Locale.US).matches(".*(moonroof|sunroof|sunshade|windowblind).*")) continue;
                Class<?>[] pt = m.getParameterTypes();
                StringBuilder sig = new StringBuilder(n).append('(');
                for (int i = 0; i < pt.length; i++) { if (i > 0) sig.append(','); sig.append(pt[i].getSimpleName()); }
                sig.append(')');
                if (pt.length == 0 && (n.startsWith("get") || n.startsWith("is"))) {
                    try { sig.append('=').append(m.invoke(bw)); } catch (Throwable t) { sig.append("=예외"); }
                }
                out.add(sig.toString());
            }
        } catch (Throwable t) { out.add("진단예외 " + t.getClass().getSimpleName()); }
        return String.join(" ", out);
    }
    private void probeCaps() {
        Object st = deviceByName("setting");
        Object bw = getBodyworkDevice();
        String[][] seat = {{"S1H","DriverSeatHeating"},{"S1V","DriverSeatVentilating"},{"S2H","PassengerSeatHeating"},
                {"S2V","PassengerSeatVentilating"},{"S3H","RearLeftSeatHeating"},{"S4H","RearRightSeatHeating"},{"WH","SteeringWheelHeatingState"}};
        StringBuilder c = new StringBuilder("|");
        for (String[] s : seat) { if (hasFeat(st, s[1]) == 1) c.append(s[0]).append("|"); }
        if (hasFeat(bw, "MoonRoof") == 1) c.append("SHADE|");
        if (hasFeat(bw, "MoonRoof") == 1 && relay != null) relay.putStatus("roof_diag", roofDiag(bw));   // ★714/712 유리 선루프 읽기 진단(시작 때 1회)
        capsFlat = c.toString();   // ★캐시 — RelayClient 재생성(startRelay) 시 재주입(2026-09-10 실차: Activity 포크→restartRelay로 caps 유실)
        if (relay != null) relay.putStatus("caps", capsFlat);
        postToD1("CAPS", "차종옵션 caps=" + c);
        // ★Phase 0 리모컨 manifest(2026-09-10 운영자 착수) — flat caps는 하위호환 유지, 리치 manifest를 remote_manifest로 병행 발행.
        try {
            String m = buildRemoteManifest(st, bw);
            remoteManifestJson = m;
            if (relay != null) relay.putStatus("remote_manifest", m);
            postToD1("CAPS", "remote_manifest " + m.length() + "B caps=" + remoteManifestCount);
            publishRemoteApps();   // ★앱런처 목록도 manifest와 함께 발행(op 전용)
        } catch (Throwable t) { postToD1("CAPS", "remote_manifest 실패: " + t); }
    }

    // ── 리모컨 manifest 빌더 — 정본 docs/BYD-CONTROL-CATALOG.md §9. 소비자(앱 탭·웹 패널)는 action RAW {device,method,args}로 실행:
    //    "$v"=선택값 치환 · multi=인자목록 반복 호출 · featureId=RAW feature write 모드 · type memory={recall,save}.
    //    hasFeat(옵션)·DiLink5 분기로 차종별 노출. ✅/🟢만 포함, chk=true는 제3자 확정·값 미확정(패널이 '확인' 배지). ❓(온도·D3성에·문루프·램프레벨)는 프로브 후 추가.
    private volatile String capsFlat = "";
    private volatile String remoteManifestJson = "";
    private int remoteManifestCount = 0;
    // ★앱런처(2026-09-11) — 설치 앱 목록(+분할 지원 여부) JSON. manifest처럼 relay_state("remote_apps")로 발행, 재시작 복원용 캐시.
    private volatile String remoteAppsJson = "";
    private volatile boolean splitBeforeReverse = false;   // R기어 진입 직전 분할 활성 여부 → R 이탈 시 복원
    private final java.util.Set<String> uploadedAppIcons = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private void publishRemoteApps() {
        try {
            org.json.JSONArray apps = SplitLauncher.listApps(this);
            remoteAppsJson = apps.toString();
            if (relay != null) relay.putStatus("remote_apps", remoteAppsJson);
            postToD1("SPLIT", "remote_apps " + apps.length() + "개 " + remoteAppsJson.length() + "B");
            postToD1("SPLIT", "미디어 앱 판정 근거(svc=미디어 서비스 수, btn=미디어 버튼 수신기 수, -1=조회 실패):" + SplitLauncher.lastMediaDiag);
            uploadAppIcons(apps);
        } catch (Throwable t) { postToD1("SPLIT", "remote_apps 실패: " + t); }
    }
    // ★헤드유닛에 앱이 설치/삭제/교체되면 런처 목록(remote_apps)과 아이콘을 다시 발행(2026-09-11 운영자 "앱 추가되면 세컨드스크린에도 자동으로?").
    //   교체(업데이트)면 그 앱의 캐시 아이콘을 지워 새 아이콘으로 다시 렌더·업로드. 설치 직후 PackageManager 반영 지연 대비 3초 뒤 실행.
    private void registerAppListWatcher() {
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_PACKAGE_ADDED);
            f.addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);
            f.addAction(android.content.Intent.ACTION_PACKAGE_REPLACED);
            f.addDataScheme("package");
            registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                    final String pkg = i.getData() != null ? i.getData().getSchemeSpecificPart() : "";
                    if (pkg.isEmpty() || pkg.equals(getPackageName())) return;
                    if ("com.cseini.bydautotrip".equals(pkg)) putAutotripVer();   // ★710 오토전비 설치·삭제·교체 즉시 상태 갱신
                    if (android.content.Intent.ACTION_PACKAGE_REPLACED.equals(i.getAction())) {
                        java.io.File[] old = SplitLauncher.iconDir(SnifferService.this).listFiles((d, n) -> n.startsWith("appicon_" + pkg));
                        if (old != null) for (java.io.File o : old) { if (o.delete()) uploadedAppIcons.remove(o.getName()); }
                    }
                    postToD1("SPLIT", "앱 " + i.getAction().replace("android.intent.action.PACKAGE_", "") + " " + pkg + " → 런처 목록 갱신");
                    new Thread(() -> { try { Thread.sleep(3000); } catch (InterruptedException ignored) {} publishRemoteApps(); }, "applist-watch").start();
                }
            }, f);
        } catch (Throwable t) { postToD1("SPLIT", "앱 변경 감시 등록 실패: " + t); }
    }
    // ★앱 아이콘을 워커 pulled/에 1회 올려 iOS 앱이 /relay/appicon/<name>으로 받는다(웹은 헤드유닛 /icon/ 직접). 프로세스당 아이콘별 1회, 로그는 요약 1줄.
    private void uploadAppIcons(org.json.JSONArray apps) {
        final java.util.List<String> todo = new java.util.ArrayList<>();
        for (int i = 0; i < apps.length(); i++) { String n = apps.optJSONObject(i).optString("icon", ""); if (!n.isEmpty() && !uploadedAppIcons.contains(n)) todo.add(n); }
        if (todo.isEmpty()) return;
        new Thread(() -> {
            int ok = 0;
            for (String n : todo) {
                try {
                    org.json.JSONObject p = new org.json.JSONObject().put("path", new java.io.File(SplitLauncher.iconDir(this), n).getAbsolutePath()).put("name", n);
                    if (pullFile(p, true) == null) { ok++; uploadedAppIcons.add(n); }
                } catch (Throwable ignored) {}
            }
            postToD1("SPLIT", "앱 아이콘 업로드 " + ok + "/" + todo.size());
        }, "appicons").start();
    }
    // ★트렁크 클라우드 실행 — 앱은 자기 클라우드로 가지만 웹 세컨드스크린(클라우드 없음)·돌핀(SDK 없음)은 릴레이 세션으로.
    private String trunkCmd(String action, org.json.JSONObject params) {
        String kind = params != null ? params.optString("kind", "") : "";
        if (kind.isEmpty()) { String[] t = action.split("\\s+"); kind = t.length >= 2 && t[1].equalsIgnoreCase("close") ? "TRUNK_CLOSE" : "TRUNK_OPEN"; }
        if (!parkedForLauncher()) return "주차(P)에서만 가능";
        if (cloud == null) restoreCloudFromPrefs();
        if (cloud == null) return "클라우드 세션 없음(앱 로그인 필요)";
        final String ct = kind.equals("TRUNK_CLOSE") ? "CLOSETRUNK" : "OPENTRUNK";
        new Thread(() -> {
            try {
                com.cseini.bydsniffer.cloud.BydCloud.Result r = cloud.sendCommand(cloudVin, ct, null, cloudPin == null ? "" : cloudPin, true);
                postToD1("TRUNK", ct + " → " + (r != null && r.success ? "OK" : "실패 " + (r != null ? r.error : "null")));
            } catch (Throwable t) { postToD1("TRUNK", ct + " 예외 " + t); }
        }, "trunk-cloud").start();
        return ct + " 전송";
    }

    // ★웹 세컨드스크린 서버(op 전용) — 핫스팟/동일 WiFi 브라우저. 시작 실패(네트워크 없음)해도 서비스는 계속.
    private RemoteWebServer remoteWeb;
    private long lastWebRetry = 0;   // ★496 웹서버 재시도 스로틀 — 핫스팟을 시동 후 켜면 부팅 시 웹서버 미기동, 폴 루프서 30s마다 재시도
    private void startRemoteWeb() {
        if (remoteWeb != null) return;   // ★2026-09-11 운영자 지시로 공유판도 개방(사설망 전용은 그대로)
        try {
            remoteWeb = RemoteWebServer.startAny(this, new RemoteWebServer.Host() {
                @Override public String manifestJson() { return webManifestJson(); }
                @Override public String runCommand(String action, org.json.JSONObject params) { return webRunCommand(action, params); }
                // ★클러스터 2단계(2026-09-17)
                @Override public String clusterSnapshotJson() {   // 상태 전체(RelayClient.clusterKeyOk 규칙) + "raw" 원시 차량이벤트 맵
                    try { org.json.JSONObject o = relay != null ? relay.clusterSnapshot() : new org.json.JSONObject(); o.put("raw", clusterRawJson()); return o.toString(); }
                    catch (Throwable t) { return "{}"; }
                }
                @Override public void clusterClients(int n) { clusterClients = n; if (relay != null) relay.setClusterClients(n); clusterBackfillSet(n > 0); }
                @Override public void clusterCamClients(int n) { clusterCamClients = n; clusterCam.onClients(n); }   // ★716 클러스터 페이지(nocam)는 카메라 예열 안 함 — 카메라 위젯을 뺐다
                @Override public RemoteWebServer.Frame clusterFrame(long afterSeq, long waitMs) { return clusterCam.frameAfter(afterSeq, waitMs); }
                @Override public String clusterCfgJson() { return SnifferService.this.clusterCfgJson(); }   // ★619
                @Override public byte[] clusterTile(int side) { return clusterCam.tileJpeg(side); }       // ★619
                @Override public void log(String msg) { postToD1("CLUSTER", msg); }
                @Override public void noteLan() { if (relay != null) relay.noteLan(); }   // ★560 LAN 직결 감지 → 워커 폴링 완화
                // ★565 원터치 자동연결 — QR 표시 후 60초 창 안에서만 신원을 내준다. 닫혀 있으면 값 없이 거절.
                @Override public String pairInfoJson() {
                    try {
                        if (!pairWindowOpen()) return new org.json.JSONObject().put("ok", false).put("error", "closed").toString();
                        String dev = readDevId();
                        String k = prefs != null ? prefs.getString(KEY_PAIR, "") : "";
                        if (dev == null || dev.isEmpty() || k.isEmpty())
                            return new org.json.JSONObject().put("ok", false).put("error", "not-ready").toString();
                        postToD1("RELAY", "자동연결 — 같은 망의 폰이 신원 조회(devId=" + dev + ")");
                        return new org.json.JSONObject().put("ok", true).put("devId", dev).put("k", k).toString();
                    } catch (Throwable t) { return "{\"ok\":false}"; }
                }
                // ★577 LAN 상호 동기화 — 폰이 uid/vin을 직접 주입, 릴레이가 창·VIN 검증 후 즉시 등록하고 {devId,k} 반환.
                //   QR 스캔·워커 SETVIN 왕복 없이 버튼 두 번(양쪽)으로 완료. 창(60초)이 곧 상호 확인(차 주인이 헤드유닛 버튼).
                @Override public String lanPair(String uid, String vin) {
                    try {
                        if (!pairWindowOpen()) return "{\"ok\":false,\"error\":\"closed\"}";
                        if (!isValidVin(vin)) { postToD1("RELAY", "LAN동기화 거절 — VIN 형식 오류(len=" + (vin == null ? 0 : vin.length()) + ")"); return "{\"ok\":false,\"error\":\"vin\"}"; }
                        String dev = readDevId();
                        String k = prefs != null ? prefs.getString(KEY_PAIR, "") : "";
                        if (dev == null || dev.isEmpty() || k.isEmpty()) return "{\"ok\":false,\"error\":\"not-ready\"}";
                        // 실 VIN 주입(SETVIN과 동일 저장). uid는 있으면 반영(D5는 빈값일 수 있음).
                        if (prefs != null) {
                            android.content.SharedPreferences.Editor e = prefs.edit();
                            e.putString(KEY_VIN, vin);
                            if (uid != null && !uid.isEmpty()) e.putString(KEY_UID, uid);
                            e.apply();
                        }
                        String useUid = (uid != null && !uid.isEmpty()) ? uid : (prefs != null ? prefs.getString(KEY_UID, "") : "");
                        if (relay != null) relay.setCredentials(useUid, vin);
                        postToD1("RELAY", "★LAN 상호 동기화 완료 — vin말미=" + maskTail(vin) + " uid=" + useUid + " (즉시 클린 등록)");
                        try { pairAdvStop(); } catch (Throwable ignored) {}   // 등록 성공 → 광고·창 닫기(중복 방지)
                        pairOpenUntil = 0;
                        return new org.json.JSONObject().put("ok", true).put("devId", dev).put("k", k).toString();
                    } catch (Throwable t) { return "{\"ok\":false,\"error\":\"" + t.getClass().getSimpleName() + "\"}"; }
                }
            });
            if (remoteWeb == null) { postToD1("WEB", "리모컨 웹서버 시작 실패(포트)"); return; }
            publishRemoteWebUrl(true);
        } catch (Throwable t) { postToD1("WEB", "웹서버 예외 " + t); }
    }
    // ★접속 URL 재발행 — 핫스팟/WiFi는 부팅 후 늦게 올라오므로 폴 루프에서 30s마다 IP 후보를 다시 읽어 relay_state remote_web(JSON 배열)로.
    private long remoteWebPubMs = 0; private java.util.List<String> remoteWebLast = java.util.Collections.emptyList();
    /** MainActivity 웹 QR용 — 현재 접속 URL 후보(핫스팟/WiFi IP 우선). 서버 미기동이면 빈 목록. */
    public java.util.List<String> remoteWebUrls() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (remoteWeb == null) return out;
        for (String ip : RemoteWebServer.localIps()) out.add("http://" + ip + ":" + remoteWeb.getListeningPort() + "/");
        return out;
    }
    public int remoteWebPort() { return remoteWeb == null ? 0 : remoteWeb.getListeningPort(); }
    private void publishRemoteWebUrl(boolean force) {
        if (remoteWeb == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - remoteWebPubMs < 30000) return;
        remoteWebPubMs = now;
        try {
            java.util.List<String> ips = RemoteWebServer.localIps();
            // ★567 실제 List로 저장 — 이전엔 JSONArray.toString()(문자열)을 putStatus에 넣어서, state가 다시
            //   new JSONObject(state)로 직렬화될 때 배열이 "[\"http://...\"]" 형태로 이중 인코딩됐다. 워커
            //   /relay/pending의 Array.isArray(st.remote_web) 판정이 항상 false → 원버튼 자동연결(iOS 846·
            //   안드 진행중)이 실제 LAN 후보가 있어도 항상 빈 목록만 받았다(2026-09-22, 안드로이드 세션 실측 발견).
            //   List를 그대로 넘기면 org.json이 중첩 JSONArray로 정직하게 직렬화한다.
            java.util.List<String> urls = new java.util.ArrayList<>();
            for (String ip : ips) urls.add("http://" + ip + ":" + remoteWeb.getListeningPort() + "/");
            if (relay != null) relay.putStatus("remote_web", urls);
            if (!urls.equals(remoteWebLast)) { remoteWebLast = urls; postToD1("WEB", "리모컨 웹 접속 URL " + (ips.isEmpty() ? "(네트워크 없음 — 핫스팟/WiFi 켜면 표시)" : urls.toString())); }
        } catch (Throwable ignored) {}
    }
    private void stopRemoteWeb() { try { if (remoteWeb != null) remoteWeb.stop(); } catch (Throwable ignored) {} remoteWeb = null; }

    // ═══ ★웹 클러스터 2단계(2026-09-17) — 깜빡이 즉시 반영 + 깜빡이 방향 측면 카메라 ═══════════════════════════════════════
    //   1단계는 /manifest 2초 폴링 + 릴레이 폴 fastPoll(600ms) → 최악 ~3초. 2단계: 상태 변경은 RelayClient.StateListener → RemoteWebServer SSE로 즉시 푸시,
    //   깜빡이는 D3 light 이벤트 수신 즉시 getter로 좌/우 확정해 폴을 기다리지 않는다. 폴(접속 중 600ms 고정)은 백스톱.
    private volatile int clusterClients = 0;          // SSE 접속 수(RemoteWebServer.Host.clusterClients) — 폴 주기·값 보충용
    private volatile int clusterCamClients = 0;       // ★716 카메라 쓰는 SSE 접속 수(폰 보조 카메라) — 0이면 측면·후방 카메라 절대 안 열림
    boolean clusterViewing() { return clusterClients > 0; }
    private final ClusterCam clusterCam = new ClusterCam();
    private final BsdExp bsdExp = new BsdExp();       // ★602 BSD실험(RECORD-ONLY) — 주행 중 좌/우 카메라 프레임 저장
    private volatile long detectFrameCount = 0;       // 전방 감지 콜백 프레임 누계 — CAMDUALPROBE 관측용

    // ★클러스터 원시 차량이벤트 맵(운영자 2026-09-17) — onVehicleEvent 최상단에서 필터·NoiseFilter·로그와 무관하게 모든 이벤트의 최신값을 "device:FEATURE"로 보관(상한 400키, 삽입순 축출).
    //   워커 relay_state 업로드엔 절대 안 실림(페이로드 비대 방지) — 클러스터 로컬 SSE 전용. 값이 바뀐 키만 pushRaw(같은 값 반복 이벤트 억제), 접속 스냅샷엔 전체.
    private final java.util.LinkedHashMap<String, Object> clusterRaw = new java.util.LinkedHashMap<String, Object>(512, 0.75f, true) {   // accessOrder=true → put도 접근 취급 = 진짜 LRU(삽입순이면 활발한 키가 먼저 밀리고 재진입 시 같은 값에도 pushRaw 발화)
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Object> e) { return size() > 400; }
    };
    // ★저빈도 중요 키는 evict 안 함(2026-09-18) — 타이어·주행가능·충전값은 가끔만 와서 LRU에서 밀리면 SSE 스냅샷에 없어 클러스터가 영원히 "수신없음". 별도 맵에 pin.
    private final java.util.HashMap<String, Object> clusterPinned = new java.util.HashMap<>();
    private static boolean clusterPinKey(String k) { return k.contains("TYRE") || k.contains("SET_ELECTRIC_RANGE") || k.contains("CHARGING") || k.contains("BATTERY_POWER"); }
    // ★567 백스톱 폴이 09-24/09-26 실차 양쪽에서 "유효값 0개"로 확정 실패(이미 2026-09-10에 get(int[],Class)가
    //   이 차종 전 디바이스에서 NA로 확인된 바 있음, publishLiveFeat 주석 참고) — 그럼 핀 4종(타이어·주행가능·충전·배터리파워)이
    //   실제 vehicle 이벤트로라도 한 번이라도 오는지 자체가 미확인이었다. 접속자 유무와 무관하게, 프로세스 생애 중
    //   핀 키가 실제 이벤트로 처음 들어온 순간만 1회 A1에 남겨 "이벤트 자체가 오는가"를 실증한다(스팸 없음).
    private final java.util.Set<String> clusterPinFirstSeen = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private Class<?> rawValCls; private java.lang.reflect.Field rawFStr, rawFDbl, rawFFlt;   // BYDAutoEventValue 필드 캐시(클래스별 1회 조회)
    /** 이벤트 값 — stringValue(비어있지 않음) > doubleValue(≠0) > floatValue(≠0) > intValue 순으로 '채워진 것'을 고른다. */
    private Object rawEventValue(Object value, int intVal) {
        if (value == null) return intVal;
        try {
            Class<?> c = value.getClass();
            if (c != rawValCls) { rawFStr = pubField(c, "stringValue"); rawFDbl = pubField(c, "doubleValue"); rawFFlt = pubField(c, "floatValue"); rawValCls = c; }
            if (rawFStr != null) { Object s = rawFStr.get(value); if (s instanceof String && !((String) s).isEmpty()) return s; }
            if (rawFDbl != null) { double d = rawFDbl.getDouble(value); if (d != 0 && !Double.isNaN(d) && !Double.isInfinite(d)) return d; }
            if (rawFFlt != null) { float d = rawFFlt.getFloat(value); if (d != 0 && !Float.isNaN(d) && !Float.isInfinite(d)) return (double) d; }
        } catch (Throwable ignored) {}
        return intVal;
    }
    private static java.lang.reflect.Field pubField(Class<?> c, String n) { try { return c.getField(n); } catch (Throwable t) { return null; } }
    private void clusterRawUpdate(String key, Object v) {
        Object prev;
        synchronized (clusterRaw) { prev = clusterPinKey(key) ? clusterPinned.put(key, v) : clusterRaw.put(key, v); }
        if (v.equals(prev)) return;
        RemoteWebServer w = remoteWeb; if (w != null) w.pushRaw(key, v);   // 접속자 없으면 pushRaw가 즉시 반환
    }
    // ★566 저빈도 클러스터 값 백스톱 폴 — 타이어압/온도·주행가능거리·충전상태·배터리파워는 '변화 이벤트'로만 clusterRaw에
    //   들어온다. 그래서 앱이 재시작된 뒤 그 이벤트가 한 번도 안 오면 클러스터엔 영원히 '–'로 남았다
    //   (09-22 실차: 계기판은 346km·88%·타이어 정상인데 웹 클러스터는 그 칸들이 전부 비어 있었다).
    //   클러스터 접속 중에만 별도 스레드에서 featureId로 직접 읽어 '이벤트와 같은 키'로 채운다 — 이후 진짜 이벤트가
    //   오면 같은 경로로 덮어써서 자연히 최신값이 이긴다. 접속 0이면 즉시 멈춰 주차 중 배터리를 쓰지 않는다.
    private volatile Thread clusterBackfill = null;
    private java.util.List<Object[]> clusterBackfillTargets = null;   // {Integer featureId, String domain, String rawKey}
    private void clusterBackfillSet(boolean on) {
        if (!on) { Thread t = clusterBackfill; clusterBackfill = null; if (t != null) t.interrupt(); return; }
        if (clusterBackfill != null) return;
        Thread t = new Thread(() -> {
            while (clusterBackfill == Thread.currentThread() && clusterClients > 0) {
                try { clusterBackfillOnce(); } catch (Throwable ignored) {}
                try { Thread.sleep(15000L); } catch (InterruptedException ie) { return; }
            }
        }, "cluster-backfill");
        clusterBackfill = t; t.setDaemon(true); t.start();
    }
    private void clusterBackfillOnce() {
        java.util.List<Object[]> tg = clusterBackfillTargets;
        if (tg == null) {
            if (idToName.isEmpty()) return;                   // 아직 feature 목록 로드 전 — 다음 주기에 다시
            tg = new java.util.ArrayList<>();
            for (Map.Entry<Integer, String> e : idToName.entrySet()) {
                String dom = clusterBackfillDomain(e.getValue());
                if (dom != null) tg.add(new Object[]{ e.getKey(), dom, dom + ":" + e.getValue() });
            }
            clusterBackfillTargets = tg;
            postToD1("CLUSTER", "백스톱 폴 대상 " + tg.size() + "개(타이어·주행가능·충전·파워)");
        }
        int filled = 0;
        for (Object[] o : tg) {
            Object dev = deviceByName((String) o[1]);
            if (dev == null) continue;
            // ★567 pollGet이 아니라 getFeatureInt — BYD 디바이스의 실제 읽기 메서드는 get(int[], Class)이고
            //   pollGet이 찾던 get(int, 기본값)은 존재하지 않는다(566에서 "유효값 0개"로 실측 확인).
            //   publishLiveFeat이 쓰는 검증된 경로이며 실패값 필터도 그쪽과 동일하게 맞췄다.
            int v = getFeatureInt(dev, (Integer) o[0]);
            if (v == NA || v == Integer.MIN_VALUE || v == -2147482648 || v == 65535 || v < 0) continue;
            clusterRawUpdate((String) o[2], v); filled++;      // 값 동일하면 clusterRawUpdate가 알아서 푸시 생략
        }
        if (filled == 0 && clusterBackfillTargets != null) postToD1("CLUSTER", "백스톱 폴 유효값 0개(getter 미지원 가능)");
        clusterBackfillNamedGetters();
    }
    // ★590 09-26 DUMPDEV 실측 — get(int[],Class) 방식(위 루프)은 이 차종(D3)에서 전 도메인 NA로 이미 확정(2026-09-10)됐지만,
    //   brake_mode·steer_mode·regen 등에 이미 쓰이는 무인자 named getter 경로(callGetInt0)는 charging 도메인에서 살아있는 값을
    //   돌려준다(getChargingGunState=1 등, 쓰레기값 아님). cluster.html이 기대하는 정확한 raw 키로 매핑해 공급.
    //   ⚠️인코딩 불일치 확인(CONSTS charging GUN, 09-26) — getChargingGunState는 CHARGING_GUN_STATE_*(1=CONNECTED_NONE
    //   즉 미연결, 2~5=AC/DC/AC_DC/VTOL 연결)인데, cluster.html의 CHARGING_GUN_CONNECT_STATE 키는 이벤트쪽 인코딩
    //   {0:해제,1:연결,2:연결}을 기대한다. 그대로 넣으면 미연결(1)이 "연결"로 오표시됨 → 반드시 변환.
    //   ⚠️충전기 미연결 상태에서만 실측 — 실제 충전 중 값이 바뀌는지는 아직 미검증([[byd-getter-ok-not-physical-proof-2026-09-23]] 전례).
    private static int chargingGunStateToConnectState(int v) { return v <= 1 ? 0 : 1; }   // CONNECTED_NONE(1)→해제(0), 그 외(2~5)→연결(1)
    private static final Object[][] CLUSTER_NAMED_GETTER_MAP = {
        { "charging", "getChargingGunState", "charging:CHARGING_GUN_CONNECT_STATE", (java.util.function.IntUnaryOperator) SnifferService::chargingGunStateToConnectState },
        // ★595 주행가능거리 — 09-26 dumpDev가 statistic 도메인을 안 봐서 못 찾았던 것(비틀런처 디컴파일이 단서). 09-27 운영자차
        //   DUMPDEV statistic 실측: getElecDrivingRangeValue=175(km), 같은 덤프 SOC 49% → 돌핀 만충 ~360km 대비 물리적으로 그럴듯.
        { "statistic", "getElecDrivingRangeValue", "setting:SET_ELECTRIC_RANGE", (java.util.function.IntUnaryOperator) (v -> v) },
    };
    private void clusterBackfillNamedGetters() {
        for (Object[] o : CLUSTER_NAMED_GETTER_MAP) {
            Object dev = deviceByName((String) o[0]);
            if (dev == null) continue;
            int v = callGetInt0(dev, (String) o[1]);
            if (v == -1 || v == Integer.MIN_VALUE || v == -2147482648 || v == 65535 || v < 0) continue;
            clusterRawUpdate((String) o[2], ((java.util.function.IntUnaryOperator) o[3]).applyAsInt(v));
        }
        // ★596 타이어 — 1인자 getter. pos→바퀴는 비틀런처 VehicleData 타이어 루프(라벨 {앞왼,앞오,뒤왼,뒤오}[i]와
        //   getWheelPressure(WHEEL_POS[i]) 같은 행, WHEEL_POS={3,1,4,2})로 확정(추론 아님). 09-27 운영자 돌핀 PROBEINT 실측:
        //   압력 377/379/377/372·온도 24/24/22/22 — 앞축이 뒤축보다 높고 따뜻해 매핑과 물리적으로 일치.
        Object inst = deviceByName("instrument");
        if (inst == null) return;
        String[][] wheels = { {"LF", "3"}, {"RF", "1"}, {"LB", "4"}, {"RB", "2"} };
        for (String[] w : wheels) {
            int pos = Integer.parseInt(w[1]);
            int pr = callGetInt(inst, "getWheelPressure", pos);
            int tp = callGetInt(inst, "getWheelTemperature", pos);
            if (pr > 0 && pr != 65535 && pr != -2147482648) clusterRawUpdate("instrument:I_2IN1_" + w[0] + "_TYRE_PRESSURE", pr);
            if (tp != -1 && tp != 65535 && tp != -2147482648) clusterRawUpdate("instrument:I_2IN1_" + w[0] + "_TYRE_TEMPERATURE", tp);
        }
    }
    private static String clusterBackfillDomain(String n) {
        if (n == null) return null;
        if (n.contains("TYRE_PRESSURE") || n.contains("TYRE_TEMPERATURE")) return "instrument";
        if (n.equals("SET_ELECTRIC_RANGE")) return "setting";
        if (n.startsWith("CHARGING_")) return "charging";
        if (n.equals("BODYWORK_BATTERY_POWER")) return "bodywork";
        return null;
    }
    private org.json.JSONObject clusterRawJson() {
        synchronized (clusterRaw) {
            org.json.JSONObject o = new org.json.JSONObject(clusterRaw);
            for (java.util.Map.Entry<String, Object> e : clusterPinned.entrySet()) { try { o.put(e.getKey(), e.getValue()); } catch (Throwable ignored) {} }
            return o;
        }
    }

    /** 깜빡이 단일 적용 경로 — 이벤트(즉시)·폴(백스톱) 모두 여기로. getTurnLightFlashState: 1=OFF·2=좌·4=우·6=비상등(위상무관). 값 변화 시·접속자 있을 때만 로그. */
    private void applyTurnFlash(int tf, String src, long ts) {
        if (relay == null || tf < 0) return;
        Object prev = relay.getStatus("turnflash");
        boolean changed = !(prev instanceof Integer) || (Integer) prev != tf;
        relay.putStatus("turnflash", tf); relay.putStatus("hazard", tf == 6 ? 1 : 0);   // putStatus가 값 변화 시 SSE 리스너 통지(turnflash는 디바운스 없이 즉시 flush)
        if (changed && clusterClients > 0) postToD1("CLUSTER", "깜빡이 " + prev + "→" + tf + " (" + src + ", putStatus +" + (System.currentTimeMillis() - ts) + "ms)");
        clusterCam.onTurn(tf, ts);
    }
    // ★D3 light 이벤트(LIGHT_TURN_SIGNAL_LIGHT_SWITCH_STATE) → 즉시 getter로 좌/우 확정. 콜백 시점에 HAL이 아직 옛값일 수 있어 이벤트(ON/OFF)와 getter가
    //   불일치하면 150ms 뒤 1회 재읽기. 이벤트값·getter·재읽기값을 로그로 남겨 첫 실차에서 인코딩/타이밍이 드러나게. D5는 이 이벤트가 없어 폴 경로가 담당.
    // ★739 이벤트값 1·2·4는 getter가 따라잡은 최종값과 늘 같았다(10-04 실측 7일 90/90: evt 4→4·evt 2→2·evt 1→1) — getter 확인(평균 0.18초, 불일치면 +150ms)
    //   없이 바로 적용해 카메라 창이 그만큼 빨리 뜬다. 그 밖의 값(5·6 등)만 getter로 확정. 폴(백스톱)은 이벤트 직후 0.6초 동안 다른 값이면 무시(getter가 아직 옛값).
    private volatile long turnEvtAt; private volatile int turnEvtVal = -1;
    private volatile int turnReleasedSide; private volatile long turnReleasedAt;   // ★873 잡았다 놓은 쪽 — 등이 꺼질 때까지(최대 3초) 폴이 이 값을 다시 켜지 않게
    // ★816 꺼짐(1)은 바로 적용하지 않는다 — 이 이벤트는 레버 위치라, 한 번 딸깍(3번 깜빡임)이면 레버는 0.3초 만에 돌아와도 등은 계속 깜빡인다.
    //   바로 끄면 창이 닫혔다가 폴이 '아직 깜빡임'을 읽어 다시 떴다(운영자 10-06 "우리 영상도 깜빡 난리나", 19:43:05 꺼짐 0.3초→0.55초 쉼→2.3초 다시 뜸).
    private void onTurnSignalEvent(final int intVal) {
        final long ts = System.currentTimeMillis();
        if (intVal == 2 || intVal == 4) {
            turnEvtAt = ts; turnEvtVal = intVal; turnReleasedSide = 0;
            new Thread(() -> applyTurnFlash(intVal, "evt " + intVal, ts), "turn-evt").start();
            return;
        }
        // ★873 레버를 0.6초 넘게 잡고 있다 놓았으면 한 번 딸깍(편의 3번 깜빡임)이 아니다 — 등이 마저 꺼질 때까지(0.6~2초, 10-11 실측 닫힘 0.86~2.3초) 기다리지 않고
        //   바로 끈다(운영자 10-11 "꺼지는 속도가 0.3초 셋팅인데 너무 오래 걸리네"). 폴이 아직 깜빡이는 같은 쪽 값을 읽어 창을 다시 띄우지 않게 등이 꺼질 때까지 그 값만 무시.
        if (intVal == 1 && (turnEvtVal == 2 || turnEvtVal == 4) && ts - turnEvtAt >= 600) {
            final int side = turnEvtVal; final long held = ts - turnEvtAt;
            turnReleasedSide = side; turnReleasedAt = ts; turnEvtAt = ts; turnEvtVal = 1;
            new Thread(() -> { postToD1("HUASSIST", "깜빡이 레버 " + held + "ms 잡았다 놓음 — 바로 끔"); applyTurnFlash(1, "evt 1 레버 놓음", ts); }, "turn-evt").start();
            return;
        }
        new Thread(() -> {
            Object ldev = deviceByName("android.hardware.bydauto.light.BYDAutoLightDevice");
            int tf = ldev != null ? callGetInt0(ldev, "getTurnLightFlashState") : -1;
            boolean evOn = intVal != 0 && intVal != 1;
            String detail = "evt " + intVal + " getter " + tf;
            if (ldev != null && ((evOn && tf <= 1) || (!evOn && tf > 1))) {
                try { Thread.sleep(150); } catch (InterruptedException e) { return; }
                int tf2 = callGetInt0(ldev, "getTurnLightFlashState");
                detail += " 재읽기 " + tf2;
                if (tf2 >= 0) tf = tf2;
            }
            if (tf < 0) tf = evOn ? intVal : 1;   // getter 실패 폴백 — 이벤트값 그대로(좌/우 인코딩 미확정)
            if (intVal == 1 && tf > 1) postToD1("HUASSIST", "깜빡이 레버는 돌아왔지만 등은 아직 깜빡임(" + detail + ") — 꺼짐 보류, 등이 꺼지면 폴이 끔");
            applyTurnFlash(tf, detail, ts);
        }, "turn-evt").start();
    }

    // ★AVM 4분할 NV21에서 사분면 하나를 JPEG로. 사분면(실측 확정, 메모리 byd-dolphin-avm-quad-layout): 좌상=후방·우상=왼쪽·좌하=오른쪽·우하=전방
    //   → 좌 깜빡이(2)=우상(w/2,0) · 우 깜빡이(4)=좌하(0,h/2). scale=1: YuvImage Rect 크롭(안전 기준선, 1280x960). scale=2: 정수 서브샘플(640x480, prefs cluster_cam_scale).
    // ★656 클러스터 프레임의 칸 좌표 {x, y, 칸폭, 칸높이} — 프레임 레이아웃 두 가지를 모두 지원한다.
    //   ① 4분할 2560x1920(콜백 경로): 사분면 좌상=후방(8)·우상=왼쪽(2)·좌하=오른쪽(4)·우하=전방 — 차주 라벨 캡처로 확정([[byd-dolphin-avm-quad-layout-2026-09-17]]).
    //   ② 가로 띠 5120x960(634 서피스 직접 경로가 실제로 주는 프레임): 1280x960 4칸이 사분면 읽기 순서 그대로 가로 배치 [후방, 왼쪽, 오른쪽, 전방]
    //      — 운영자 09-29 실측: 띠에 사분면 계산을 그대로 써서 편집기 '원본'에 오른쪽·전방 두 칸의 위쪽 절반이 늘어나 보였고(= 띠의 3·4번째 칸),
    //      측면 뷰·후진 3캠·어안 보정이 전부 엉뚱한 영역을 잘랐다. 띠 판정은 w >= 4h(5.33 vs 1.33).
    // ★745 씨라7 보조 카메라 칸 배치 — 1920x1300 4분할(960x650 칸)에서 어느 칸이 후방·왼쪽·오른쪽인지는 실차 미확인이라, 차주가 앱에서 4분할을 보고 고른 값을 쓴다.
    //   칸 번호 0=왼쪽 위·1=오른쪽 위·2=왼쪽 아래·3=오른쪽 아래(FastCamQuadBackend 자리). 기본은 코드 추정(cam0=앞·1=오른쪽·2=뒤·3=왼쪽).
    private static final boolean IS_D5 = DiLink5Support.isDiLink5();   // 시스템 속성 읽기라 프레임마다 부르지 않게 한 번만
    static volatile int[] d5Slot = {2, 3, 1};   // 후방, 왼쪽, 오른쪽
    private void loadD5Slots() {
        if (prefs == null) return;
        d5Slot = new int[]{prefs.getInt("d5_slot_rear", 2) & 3, prefs.getInt("d5_slot_left", 3) & 3, prefs.getInt("d5_slot_right", 1) & 3};
    }
    private static int[] tileRect(int w, int h, int side) {
        // ★871 side 1=전방(링크락 런처 카메라 띠) — 후·좌·우가 아닌 남은 한 칸(실차 확인 필요)
        if (w >= 4 * h) { int tw = w / 4; int idx = side == 8 ? 0 : side == 2 ? 1 : side == 1 ? 3 : 2; return new int[]{idx * tw, 0, tw, h}; }
        int qw = w / 2, qh = h / 2;
        if (IS_D5) { int[] s = d5Slot; int slot = side == 8 ? s[0] : side == 2 ? s[1] : side == 1 ? 6 - s[0] - s[1] - s[2] : s[2]; return new int[]{(slot & 1) * qw, (slot >> 1) * qh, qw, qh}; }   // ★745
        return new int[]{side == 2 || side == 1 ? qw : 0, side == 4 || side == 1 ? qh : 0, qw, qh};
    }
    private byte[] encodeQuadrant(byte[] nv21, int w, int h, int side, int scale, int q) {
        try {
            // ★570 사분면 배치는 **차주 라벨 캡처로 확정된 값**이다(추론 금지). side: 2=좌깜빡이(왼쪽 카메라) · 4=우깜빡이(오른쪽 카메라) · 8=후진(후방 카메라).
            int[] tr = tileRect(w, h, side);   // ★656 4분할·가로 띠 공통
            int qx = tr[0], qy = tr[1], qw = tr[2], qh = tr[3];
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(96 * 1024);
            if (scale <= 1) {
                new android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null).compressToJpeg(new android.graphics.Rect(qx, qy, qx + qw, qy + qh), q, bo);
                return bo.toByteArray();
            }
            int ow = (qw / scale) & ~1, oh = (qh / scale) & ~1;
            byte[] out = new byte[ow * oh * 3 / 2];
            for (int y = 0; y < oh; y++) { int s = (qy + y * scale) * w + qx, d = y * ow; for (int x = 0; x < ow; x++) out[d + x] = nv21[s + x * scale]; }
            int uvSrc = w * h, uvDst = ow * oh;   // VU 인터리브 평면: 행=w바이트(쌍 w/2개), 출력 쌍 c·행 r ← 원본 행 qy/2+r*scale, 쌍 qx/2+c*scale
            for (int r = 0; r < oh / 2; r++) {
                int s = uvSrc + (qy / 2 + r * scale) * w + qx, d = uvDst + r * ow;
                for (int c = 0; c < ow / 2; c++) { int si = s + c * scale * 2, di = d + c * 2; out[di] = nv21[si]; out[di + 1] = nv21[si + 1]; }
            }
            new android.graphics.YuvImage(out, android.graphics.ImageFormat.NV21, ow, oh, null).compressToJpeg(new android.graphics.Rect(0, 0, ow, oh), q, bo);
            return bo.toByteArray();
        } catch (Throwable t) { return null; }
    }

    // ★CAMDUALPROBE — 전방 감지(NormalCamera view0)가 도는 채로 AVM view5를 3초 열어 두 세션의 프레임이 동시에 오는지 실차 판정.
    //   동시 가능이면 측면 카메라를 감지 선점 없이(별도 세션) 붙일 수 있고, 불가면 지금의 선점 방식 유지. 감지가 멎어도 12s 좀비복구가 재무장.
    private String clusterDualProbe() {
        long now = System.currentTimeMillis();
        if (!detectActive || detectLastFrameMs == 0 || now - detectLastFrameMs > 5000) { postToD1("CLUSTER", "CAMDUALPROBE: 감지 비활성(최근 프레임 없음) — 프로브 무의미"); return "감지 비활성 — 프로브 무의미"; }
        if (clusterCam.active) return "측면 카메라 활성 중 — 종료 후 재시도";
        new Thread(() -> {
            Object cam = null; Class<?> ncCls = null; final int[] fc = new int[1]; StringBuilder sb = new StringBuilder("CAMDUALPROBE: ");
            long d0 = detectFrameCount;
            try {
                if (bmmLoader == null) {
                    java.io.File jar = new java.io.File("/system/framework/bmmcamera.jar");
                    bmmLoader = new dalvik.system.DexClassLoader(jar.getPath(), getCodeCacheDir().getPath(), "/system/lib64:/system/lib", getClassLoader());
                }
                try { Class.forName("android.hardware.JNILoader", true, bmmLoader).getMethod("load").invoke(null); } catch (Throwable ignored) {}
                ncCls = Class.forName("android.hardware.AVMCamera", true, bmmLoader);
                Class<?> ipcCls = Class.forName("android.hardware.AVMCamera$IPreviewCallback", true, bmmLoader);
                cam = openAvmCam(ncCls, 1, true, "CLUSTER");
                sb.append("AVM open=").append(cam != null).append(' ');
                if (cam != null) {
                    Object proxy = java.lang.reflect.Proxy.newProxyInstance(bmmLoader, new Class[]{ipcCls}, (pr, m, a) -> {
                        if ("onPreview".equals(m.getName())) fc[0]++;
                        Class<?> rt = m.getReturnType(); return rt == boolean.class ? Boolean.FALSE : (rt == int.class ? Integer.valueOf(0) : null);
                    });
                    ncCls.getMethod("setPreviewCallback", ipcCls).invoke(cam, proxy);
                    ncCls.getMethod("enablePreviewCallback", int.class).invoke(cam, 5);
                    ncCls.getMethod("startPreview").invoke(cam);
                    Thread.sleep(3000);
                }
            } catch (Throwable t) { sb.append("예외 ").append(rootMsg(t)).append(' '); }
            finally { try { if (cam != null && ncCls != null) { ncCls.getMethod("stopPreview").invoke(cam); ncCls.getMethod("close").invoke(cam); } } catch (Throwable ig) {} }
            long d1 = detectFrameCount;
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            long d2 = detectFrameCount;
            String verdict = fc[0] > 0 && d1 - d0 > 0 ? "★동시 open 가능(두 세션 프레임 동시 수신)" : fc[0] > 0 ? "AVM이 감지 프레임을 뺏음(단일 소비자)" : "AVM 프레임 없음(단일 소비자 또는 open 실패)";
            sb.append("AVM프레임(3s)=").append(fc[0]).append(" 감지프레임 동시(3s)=").append(d1 - d0).append(" 종료후(3s)=").append(d2 - d1).append(" → ").append(verdict);
            postToD1("CLUSTER", sb.toString());
        }, "camdualprobe").start();
        return "CAMDUALPROBE 시작 — 로그(CLUSTER) 확인";
    }

    // ═══ 깜빡이 방향 측면 카메라 세션 ═══
    //   SSE 접속자가 있고 turnflash가 2(좌)/4(우)이면 AVM 4분할(view5)을 열어 해당 사분면만 JPEG로 잘라 /cluster/cam.mjpg 로 흘린다(≤12fps, 최신 1장 보관).
    //   카메라는 앱 레벨 단일 소비자: 전방 **주행** 감지(detectDriveMode)만 선점(detectStop+500ms 해제 대기, camRtc 2026-09-15 패턴)하고 끝나면 frontAutoArmTick이 재무장.
    //   앱이 띄운 주차 감지(접근/작업/트렁크, detectDriveMode=false)는 재시작 경로가 없어 선점하지 않는다(스킵+로그). RTC/스트림 사용 중에도 스킵.
    //   깜빡이 OFF 후 2초 유예(짧은 OFF→ON 재진입에 카메라 껐다켜기 방지) 후 close. hazard(6)는 측면 아님. D5(씨라7)는 fast_cam 단일 스위처라 이번 범위 밖(SSE·turnflash만).
    private final class ClusterCam implements HuAssistOverlay.FrameSource {
        volatile boolean active;          // 카메라 세션 살아있음(camDetectLocked·frontAutoArmTick 게이트가 봄)
        private volatile int side;        // 2=좌 4=우 (세션 중 전환 가능 — 크롭만 바뀜)
        private volatile long stopAtMs;   // 유예 종료 예정 시각(0=없음)
        private int gen; private Thread thread;
        // ★617 방향별 커스텀 뷰(운영자 09-27 "어디를 어떻게 보여줄지 커스텀 + 어안보정") — prefs ccam_<side> JSON
        //   {x,y,w,h: 타일 내 정규화 영역, dw: 어안보정, fov: 렌즈 화각 추정, mir: 좌우반전}. 설정 변경(ccamGen)·해상도 변화 시 LUT 재생성.
        private com.cseini.motiondetect.ViewWarp warp; private String warpKey = ""; private byte[] warpBuf;
        // ★619 클러스터 편집 '카메라 화면 조정' 배경 — 예열 중 쿼드 최신 프레임에서 해당 쪽 타일 전체를 640x480 JPEG로(설정 무관 원본).
        private volatile byte[] tileNv; private volatile int tileW, tileH;
        private volatile int surfFc = 0; private android.os.HandlerThread irThread;   // ★623 더미 Surface 도착 프레임 수·비우기 스레드
        private volatile long surfCb, copyN, copyUs;   // ★792 카메라 창 병목 가르기 — 카메라가 서피스에 넣은 장 수(콜백마다)·쿼드 복사 횟수·복사 누적 µs(끝까지 누적, 창이 차이로 계산)
        @Override public long[] camCounts() { return IS_D5 ? null : new long[]{surfCb, copyN, copyUs}; }
        // ★814 카메라 창이 떠 있는 동안만 카메라 SDK의 프레임 생성 스레드(AVMCamera_Render, 우리 프로세스 안·nice 19=백그라운드 묶음)를 그리기 스레드와 같은 표시 등급으로.
        //   계측(10-06 18:39): 창을 띄우면 카메라가 보낸 장이 16~20fps로 떨어진다(쉬는 동안 25~28) — CPU 90%대에서 우리 표시 등급 스레드에 밀리는 것으로 본다.
        //   창이 여러 개(후진 좌·우)여도 마지막 창이 닫힐 때 원래 값으로 되돌린다. 카메라를 다시 열면 스레드가 새로 생기므로 실측 줄마다 다시 적용.
        private final java.util.concurrent.atomic.AtomicInteger camBoostUsers = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.Map<Integer, Integer> camBoostOrig = new java.util.concurrent.ConcurrentHashMap<>();
        @Override public void boostCam(boolean on) {
            if (IS_D5) return;
            if (on) { camBoostUsers.incrementAndGet(); applyCamBoost(true); }
            else if (camBoostUsers.decrementAndGet() <= 0) { camBoostUsers.set(0); applyCamBoost(false); }
        }
        @Override public void reboostCam() { if (!IS_D5 && camBoostUsers.get() > 0) applyCamBoost(true); }
        private synchronized void applyCamBoost(boolean on) {
            try {
                java.io.File[] ts = new java.io.File("/proc/self/task").listFiles();
                if (ts == null) return;
                StringBuilder sb = new StringBuilder();
                for (java.io.File t : ts) {
                    int tid;
                    try { tid = Integer.parseInt(t.getName()); } catch (NumberFormatException e) { continue; }
                    String comm;
                    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new java.io.File(t, "comm")))) { comm = r.readLine(); } catch (Throwable e) { continue; }
                    if (comm == null || !comm.startsWith("AVMCamera_Ren")) continue;
                    int cur = android.os.Process.getThreadPriority(tid);
                    if (on) {
                        if (cur == android.os.Process.THREAD_PRIORITY_DISPLAY) continue;
                        camBoostOrig.putIfAbsent(tid, cur);
                        android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_DISPLAY);
                        sb.append(comm).append('(').append(tid).append(") ").append(cur).append("→").append(android.os.Process.getThreadPriority(tid)).append(' ');
                    } else {
                        Integer orig = camBoostOrig.get(tid);
                        if (orig == null || cur == orig) continue;
                        android.os.Process.setThreadPriority(tid, orig);
                        sb.append(comm).append('(').append(tid).append(") ").append(cur).append("→").append(orig).append(' ');
                    }
                }
                if (!on) camBoostOrig.clear();
                if (sb.length() > 0) postToD1("HUASSIST", "카메라 SDK 스레드 우선순위 " + (on ? "올림 " : "되돌림 ") + sb.toString().trim());
            } catch (Throwable t) { postToD1("HUASSIST", "카메라 SDK 스레드 우선순위 " + (on ? "올리기" : "되돌리기") + " 실패 " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
        }
        byte[] tileJpeg(int sd) {
            byte[] nv = tileNv; int w = tileW, h = tileH;
            // ★656 편집기 '원본' 요청 기록 — 운영자 09-29 "우측·후면이 안 나옴"이 카메라 미준비인지 인코딩 실패인지 기록 없이는 못 가림.
            if (!active || nv == null || w <= 0 || h <= 0) { postToD1("CLUSTER", "편집 원본 side=" + sd + " 없음 — 카메라 " + (active ? "프레임 대기" : "미시작") + " (프레임 " + (nv != null ? "있음" : "없음") + " " + w + "x" + h + ")"); return null; }
            byte[] jpg = encodeQuadrant(nv, w, h, sd, 2, 70);
            postToD1("CLUSTER", "편집 원본 side=" + sd + " " + w + "x" + h + " → " + (jpg == null ? "인코딩 실패" : jpg.length + "B"));
            return jpg;
        }
        byte[] encodeCustom(byte[] nv21, int w, int h, int sd, int q) {
            String cfg = ccamOf(sd);   // ★718 저장값 없으면 기본(추천) 화면
            if (cfg.isEmpty()) return null;
            try {
                int[] tr = tileRect(w, h, sd); int qw = tr[2], qh = tr[3];   // ★656 4분할·가로 띠 공통
                String key = sd + "|" + w + "x" + h + "|" + ccamGen + "|" + cfg;
                if (!key.equals(warpKey)) {
                    if (warp != null) { warp.close(); warp = null; }
                    warpKey = key;
                    org.json.JSONObject c = new org.json.JSONObject(cfg);
                    int[] roi = customRoi(c, qw, qh);
                    int dw = 480, dh = Math.round(480f * customAspect(c, qw, qh)) & ~1;
                    warp = customWarp(c, tr, qw, qh, dw, dh);
                    warpBuf = new byte[dw * dh * 3 / 2];
                    postToD1("CLUSTER", "커스텀 뷰 적용 side=" + sd + " 영역=" + roi[0] + "," + roi[1] + "," + roi[2] + "x" + roi[3] + " 출력=" + dw + "x" + dh + " cfg=" + cfg);
                }
                if (warp == null || !warp.remap(nv21, w, h, warpBuf)) return null;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(48 * 1024);
                new android.graphics.YuvImage(warpBuf, android.graphics.ImageFormat.NV21, warp.width(), warp.height(), null)
                        .compressToJpeg(new android.graphics.Rect(0, 0, warp.width(), warp.height()), q, bo);
                return bo.toByteArray();
            } catch (Throwable t) { postToD1("CLUSTER", "커스텀 뷰 실패 side=" + sd + " " + rootMsg(t) + " — 기본 크롭으로"); warpKey = ""; if (warp != null) { warp.close(); warp = null; } return null; }
        }
        // ★733 커스텀 뷰 영역·비율·보정표 — 폰(480폭 JPEG)과 헤드유닛 창(창 크기 그대로)이 같은 설정으로 만든다.
        private int[] customRoi(org.json.JSONObject c, int qw, int qh) {
            return new int[]{(int) (c.optDouble("x", 0) * qw), (int) (c.optDouble("y", 0) * qh),
                    Math.max(16, (int) (c.optDouble("w", 1) * qw)), Math.max(16, (int) (c.optDouble("h", 1) * qh))};
        }
        private boolean customTv(org.json.JSONObject c) { return c.optBoolean("dw", false) && c.optInt("proj", c.optBoolean("pan", false) ? 1 : 0) == 2; }   // ★718 가로 직선
        /** 출력 세로÷가로 — ★718 가로 직선은 영역의 각도 범위 비(픽셀 비로 잡으면 미리보기와 달리 찌그러짐, 상한 2배), 아니면 영역 픽셀 비(상한 1). 하한 1/3. */
        private float customAspect(org.json.JSONObject c, int qw, int qh) {
            int[] r = customRoi(c, qw, qh);
            if (customTv(c)) return Math.max(1f / 3, Math.min(2f, com.cseini.motiondetect.ViewWarp.transverseAspect(qw, qh, r[0], r[1], r[2], r[3],
                    (float) c.optDouble("fov", 160), (float) c.optDouble("rot", 0), (float) c.optDouble("cx", 0), (float) c.optDouble("cy", 0), (float) c.optDouble("k", 0))));
            return Math.max(1f / 3, Math.min(1f, (float) r[3] / r[2]));
        }
        // ★656 fov 기본 160 — 돌핀 순정 360 카메라 낮·밤 실측 샘플로 화각 120~200 비교(직선도 점수·심사): 등거리·타일중심 모델에선 155~165가
        //   주차선·벽띠가 곧고 190은 가장자리가 과하게 늘어남(운영자 09-29 "켜면 잘 펴지는 값으로"). rot=기울기(도). ★712 cx·cy·k=렌즈 중심·왜곡(없으면 0=종전).
        private com.cseini.motiondetect.ViewWarp customWarp(org.json.JSONObject c, int[] tr, int qw, int qh, int dw, int dh) {
            int[] r = customRoi(c, qw, qh);
            return new com.cseini.motiondetect.ViewWarp(tr[0], tr[1], qw, qh, r[0], r[1], r[2], r[3],
                    c.optBoolean("dw", false), (float) c.optDouble("fov", 160), (float) c.optDouble("rot", 0), c.optBoolean("mir", false), dw, dh,
                    c.optBoolean("pan", false), (float) c.optDouble("tilt", 0), (float) c.optDouble("cx", 0), (float) c.optDouble("cy", 0), (float) c.optDouble("k", 0), customTv(c));
        }
        // ★734 후진 화면 3가지(운영자 10-04 "후진할 때 좌우 카메라 전혀 도움 안 됨" → 운영자 돌핀 원본으로 미리 그린 후보 중 "a,b,c 다 넣고 고르게").
        //   예전 ★719 좌·후·우 파노라마는 양옆이 옆 차·먼 바닥만 비추고 내 차 옆면이 안 보여 뺐다.
        //   A 후방만 넓게: 가로 직선 ±78°(양쪽 주차선)·세로 가운데 27°(벽·천장 덜고 바닥 위주, 아래 끝이 범퍼)
        //   B 사이드미러 내려 보기 + 후방: 옆 = 원통 펴기 아래로 30°·기울기 ∓50°(내 차 옆면과 그 옆 주차선) 폭 280/1190, 후방 ±58°
        //   C 좁은 옆 띠 + 넓은 후방: 옆 = 깜빡이 기본 영역을 아래로 30° 폭 170/1190, 후방 ±62°
        //   셋 다 좌우 반전(화면 왼쪽 = 차 왼쪽), 칸 사이 6px 검정. 후방 세로 범위는 가로·세로 같은 축척이 되게 칸 비율로 정한다.
        //   영역 좌표는 원본 칸 1280×960 기준(다른 칸 크기면 비율로). 깜빡이 화면 설정(ccam_2/4/8)은 쓰지 않는다. 실패하면 이번 세션은 후방 커스텀 뷰로.
        static final int REV_W = 896, REV_H = 416;   // 폰·계기판 JPEG(헤드유닛 창은 창 크기로 따로 — HuAssistOverlay)
        private volatile boolean rvBroken = false;
        private com.cseini.motiondetect.ViewWarp[] rvWarps; private int[] rvXs; private byte[][] rvBuf; private byte[] rvCanvas; private String rvKey = "";
        // ★749 후진 화면은 D(좌·우 사이드미러)만 — 운영자 10-05 "abc는 구려서 못 쓰겠고 D를 디폴트로, 좌우 사이드미러만 보여 준다고". 뒤쪽은 순정 후방 화면이 맡는다.
        String revLayout() { return "D"; }
        /** ★747 좌·우 사이드미러 내려 보기 두 칸을 반씩 나란히(폰·계기판 JPEG) — 칸들(왼쪽부터)과 칸 x, 내용 W×H(짝수). 헤드유닛은 따로 창 두 개(82·84, ★748). */
        com.cseini.motiondetect.ViewWarp[] revWarps(String lay, int fw, int fh, int W, int H, int[] xs) {
            int[] tl = tileRect(fw, fh, 2), tr = tileRect(fw, fh, 4); int qw = tl[2], qh = tl[3];
            final int SEP = 6, hw = ((W - SEP) / 2) & ~1;
            com.cseini.motiondetect.ViewWarp dl = revSideWarp(82, tl, qw, qh, hw, H), dr = revSideWarp(84, tr, qw, qh, hw, H);
            xs[0] = 0; xs[1] = hw + SEP;
            return new com.cseini.motiondetect.ViewWarp[]{dl, dr};
        }
        // ★751 후진 왼쪽(82)·오른쪽(84) 화면 — 차주가 실시간 카메라 조정 화면에서 맞춘 값(ccam_82·84), 없으면 747~750 고정 화면과 같은 기본값.
        com.cseini.motiondetect.ViewWarp revSideWarp(int sd, int[] tile, int qw, int qh, int dw, int dh) {
            String v = ccamOf(sd);
            if (v.isEmpty()) v = ccamDefault(sd);
            try { return customWarp(new org.json.JSONObject(v), tile, qw, qh, dw, dh); }
            catch (org.json.JSONException e) { throw new IllegalStateException("후진 화면 설정 읽기 실패 side=" + sd, e); }
        }
        byte[] encodeRev(byte[] nv21, int w, int h, int q) {
            if (rvBroken || IS_D5) return null;   // ★745 후진 A·B·C는 돌핀 렌즈로 맞춘 화면 — 씨라7은 후방 칸(또는 차주 조정값) 그대로
            try {
                String lay = revLayout(), key = lay + "|" + w + "x" + h + "|" + ccamGen;   // ★751 후진 왼쪽·오른쪽 조정값이 바뀌면 다시 만든다
                if (!key.equals(rvKey)) {
                    if (rvWarps != null) for (com.cseini.motiondetect.ViewWarp v : rvWarps) if (v != null) v.close();
                    rvKey = key;
                    int[] xs = new int[3];
                    rvWarps = revWarps(lay, w, h, REV_W, REV_H, xs);
                    rvXs = java.util.Arrays.copyOf(xs, rvWarps.length);
                    rvBuf = new byte[rvWarps.length][];
                    for (int i = 0; i < rvWarps.length; i++) rvBuf[i] = new byte[rvWarps[i].width() * rvWarps[i].height() * 3 / 2];
                    rvCanvas = new byte[REV_W * REV_H * 3 / 2];
                    java.util.Arrays.fill(rvCanvas, REV_W * REV_H, rvCanvas.length, (byte) 128);   // 칸 사이 검정(Y 0·VU 128)
                    postToD1("CLUSTER", "후진 화면 " + lay + " 적용 출력=" + REV_W + "x" + REV_H + " 칸=" + rvWarps.length + " 프레임=" + w + "x" + h);
                }
                for (int i = 0; i < rvWarps.length; i++) {
                    com.cseini.motiondetect.ViewWarp v = rvWarps[i];
                    if (!v.remap(nv21, w, h, rvBuf[i])) { rvBroken = true; postToD1("CLUSTER", "후진 화면 remap 실패 칸=" + i + " — 이번 세션은 후방 커스텀 뷰로"); return null; }
                    int pw = v.width(), ph = v.height(), x0 = rvXs[i];
                    for (int y = 0; y < ph; y++) System.arraycopy(rvBuf[i], y * pw, rvCanvas, y * REV_W + x0, pw);
                    for (int y = 0; y < ph / 2; y++) System.arraycopy(rvBuf[i], pw * ph + y * pw, rvCanvas, REV_W * REV_H + y * REV_W + x0, pw);
                }
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(96 * 1024);
                new android.graphics.YuvImage(rvCanvas, android.graphics.ImageFormat.NV21, REV_W, REV_H, null).compressToJpeg(new android.graphics.Rect(0, 0, REV_W, REV_H), q, bo);
                return bo.toByteArray();
            } catch (Throwable t) {
                rvBroken = true; rvKey = "";
                postToD1("CLUSTER", "후진 화면 실패 " + rootMsg(t) + " — 이번 세션은 후방 커스텀 뷰로");
                return null;
            }
        }
        private final Object frameLock = new Object();
        private RemoteWebServer.Frame latest; private long seq;

        // ★★570 예열(pre-warm) — "즉시 떠야 한다"(운영자)에 대한 근본해결.
        //   실측(09-22): 트리거 후 **카메라 open에만 평균 1,074ms**, 첫 프레임까지 1.1~1.2초. 인코딩·전송은 그 뒤 100~250ms뿐이라
        //   **트리거를 받고 열기 시작하는 한 절대 즉시가 될 수 없다.** 그래서 "카메라를 여는 것"과 "화면에 보내는 것"을 분리한다:
        //   클러스터를 보는 동안(clusterClients>0) 카메라는 **미리 열어두고**, 깜빡이·후진이 걸리면 `display`만 바꾼다 → 크롭 전환은
        //   원래부터 세션 중 가능하던 동작이라 다음 프레임(≈50ms)에 바로 나간다.
        //   ⚠️대가: 클러스터를 여는 동안 카메라 단일소비자를 계속 점유해 전방/주차 감지가 그동안 선점된다(종료 시 자연 재무장).
        private volatile int display;     // 0=표시 안 함(예열만) · 2=좌 · 4=우 · 8=후진
        /** ★745 씨라7은 차주가 앱에서 켰을 때만(기본 꺼짐 — 칸 배치 실차 미확인·비틀런처와 카메라 다툼). 돌핀은 항상 true. */
        private boolean d5Ok() { return !IS_D5 || (prefs != null && prefs.getBoolean("d5_assist", false)); }
        synchronized void onTurn(int tf, long ts) {
            if (!d5Ok()) return;
            boolean on = (tf == 2 || tf == 4);
            if (on) {
                if (display == 8) return;        // 후진이 우선 — 후진 중 깜빡이엔 화면 안 뺏김
                display = tf; side = tf; stopAtMs = 0;
                if (!active && (clusterCamClients > 0 || huShow(tf))) { if (!takeFromIdleRtc(ts)) start(tf, ts); }   // ★728 헤드유닛 창도 카메라를 연다
            } else if (display != 8) {
                display = 0;                      // 표시만 끈다. 카메라는 예열 상태로 유지(다음 트리거가 즉시 뜨도록)
                if (active && clusterCamClients == 0 && stopAtMs == 0 && !keepWanted()) stopAtMs = System.currentTimeMillis() + 2000;   // ★729 상시 대기면 안 닫음
            }
            huDisplay(display);
        }
        /** ★570 후진 카메라 — 기어 R 진입/이탈. 568 기어 정규화 덕에 돌핀·씨라7 같은 코드로 판정된다(D3 인코딩 1=R). */
        synchronized void onReverse(boolean inR, long ts) {
            if (!d5Ok()) return;
            if (inR) {
                display = 8; side = 8; stopAtMs = 0;
                if (!active && (clusterCamClients > 0 || huShow(8))) { if (!takeFromIdleRtc(ts)) start(8, ts); }   // ★728 헤드유닛 창도 카메라를 연다
            } else if (display == 8) {
                Object v = relay != null ? relay.getStatus("turnflash") : null;   // 후진 해제 시 깜빡이 켜져 있으면 그쪽으로 승계
                int tf = (v instanceof Integer) ? (Integer) v : 1;
                if (tf == 2 || tf == 4) { display = tf; side = tf; }
                else { display = 0; if (active && clusterCamClients == 0 && stopAtMs == 0 && !keepWanted()) stopAtMs = System.currentTimeMillis() + 2000; }   // ★729 상시 대기면 안 닫음
            }
            huDisplay(display);
        }
        // ★728 헤드유닛 카메라 창 — 켜 둔 상황(깜빡이/후진)이고 '모든 자동 기능 정지'가 아니면 띄운다.
        private boolean huShow(int sd) { return huOverlay != null && !allStopOn() && huOverlay.wants(sd); }
        /** ★748 후진 화면 D(좌·우만)면 헤드유닛엔 왼쪽·오른쪽 따로 창(폰은 나란히 한 장). 씨라7은 A·B·C·D를 안 씀. */
        boolean revSplit() { return !IS_D5 && "D".equals(revLayout()); }
        /** ★815 후진 D 헤드유닛 배치 — 0=왼쪽·오른쪽 따로 창(기본) · 1=가로로 붙인 한 창 · 2=세로로 붙인 한 창(왼쪽이 위). 폰이 HUASSIST rev_arr로 정한다. */
        int revArr() { return prefs == null ? 0 : Math.max(0, Math.min(2, prefs.getInt("hu_rev_arr", 0))); }
        private void huDisplay(int sd) {
            if (huOverlay == null) return;
            boolean ok = !allStopOn();
            if (sd == 8 && revSplit()) {
                int arr = revArr();
                if (arr != 0) {   // ★815 붙인 한 창(85=가로·86=세로)은 huOverlay가 맡고 오른쪽 따로 창은 닫는다
                    huOverlay.onDisplay(arr == 1 ? 85 : 86, ok);
                    if (huOverlay2 != null) huOverlay2.onDisplay(0, ok);
                    return;
                }
                huOverlay.onDisplay(82, ok);   // ★748 왼쪽은 huOverlay(82), 오른쪽은 huOverlay2(84)
                if (huOverlay2 != null) huOverlay2.onDisplay(84, ok);
                return;
            }
            huOverlay.onDisplay(sd, ok);
            if (huOverlay2 != null) huOverlay2.onDisplay(0, ok);   // 후진 D가 아니면 오른쪽 따로 창은 닫힘
        }
        // ★729 보조 카메라 상시 대기(운영자 10-04 "기능을 켜면 연결 유지") — 신호가 온 뒤 카메라를 열면 1~2.4초 늦었다(10-04 실측, 미리 열려 있던 때는 깜빡이와 같은 초).
        //   깜빡이·후진 보조를 켜 두면(보여줄 곳 무관) 시동 켜진 동안 카메라를 계속 열어 두고, 신호가 오면 display만 바꾼다.
        //   대기 안 함: 시동 꺼짐·기능 끔·모든 자동 기능 정지·카메라 끔·씨라7·이번 주행에 동시 운용이 두 번 실패(전방 감지와 못 나눠 씀, ★784).
        //   주차 감지(360)·폰 카메라 화면(RTC)·스트리밍이 카메라를 쓰는 동안은 비켜 주고 끝나면 다음 틱(2초)에 다시 연다.
        private long keepRetryAt; private boolean keepOpened;
        boolean keepWanted() {
            if (prefs == null || !d5Ok()) return false;   // ★745 씨라7도 켜 두면 미리 열기(운영자 10-05 "미리 열어 두되 설정에 안내")
            if (!prefs.getBoolean("assist_on_turn", false) && !prefs.getBoolean("assist_on_rev", false)) return false;
            // ★732 시동 = 전원 단계 게터(2=ON)를 직접 — 시동 판별기(공조 전원 엣지)는 원격 공조 중 탑승해 브레이크 밟는 순간 OFF로 읽혔고,
            //   곧 릴레이가 재시작되며 OFF를 이어받아 첫 후진이 차갑게 열렸다(10-04 19:29:25 대기 끝 → 19:29:37 후진 첫 화면 2.1초). 게터 실패(-1)면 판별기.
            int pw = powerLevelNow();
            boolean on = pw >= 0 ? pw == 2 : (relay != null && relay.carPowerState() == 1);
            return on && !camOffOn() && !allStopOn() && camDualOk();
        }
        synchronized void keepTick() {
            long now = System.currentTimeMillis();
            boolean want = keepWanted();
            if (huOverlay != null) huOverlay.standby(want && (huShow(2) || huShow(8)));   // ★739 헤드유닛 표시면 창도 미리 만들어 숨겨 둠
            if (huOverlay2 != null) huOverlay2.standby(want && huShow(8) && revSplit() && revArr() == 0);   // ★748 후진 D 오른쪽 따로 창도(★815 붙인 한 창이면 안 만듦)
            if (want) {
                if (active || now < keepRetryAt) return;
                if (takingRtc || rtcActive || camStreaming || h264Streaming || camProbing || (detectActive && (detectAvm || !camDualOk()))) return;
                keepRetryAt = now + 10_000L;   // 열기 실패·첫 프레임 없음이면 10초 뒤 다시
                keepOpened = true;
                postToD1("CLUSTER", "보조 카메라 상시 대기 시작(시동 켜짐 · 보조 켬)");
                start(display != 0 ? display : 2, now);
            } else if (keepOpened && active && display == 0 && clusterCamClients == 0 && stopAtMs == 0) {
                keepOpened = false;
                stopAtMs = now;
                postToD1("CLUSTER", "보조 카메라 상시 대기 끝(시동 꺼짐·보조 끔·정지·카메라 끔)");
            }
        }
        /** 주차 감지가 카메라를 원할 때 — 신호 표시 중이 아니면(대기·예열만) 비켜 준다. 5초 동안 다시 안 연다(감지가 카메라를 잡을 시간). */
        boolean yieldIdle(String why) {
            synchronized (this) { if (!active || display != 0) return false; keepRetryAt = System.currentTimeMillis() + 5000L; }
            stopNow(why);
            return true;
        }
        // ★730 폰이 카메라 화면을 닫아도 릴레이 쪽 RTC 세션은 ICE가 실패로 끝날 때까지(~20초) 카메라를 쥐고 있어, 그사이 깜빡이·후진 보조가
        //   못 떴다(10-04 18:09 안드 '카메라 연결 중…'에서 안 넘어감 — 측면 카메라 스킵 5회). 신호가 와서 보조 화면이 필요할 때
        //   그 세션이 폰과 연결돼 있지 않으면(확인 중·끊김) 닫고 보조 카메라를 연다. 실제로 보고 있는(연결된) 화면은 그대로 둔다.
        private volatile boolean takingRtc;
        private boolean takeFromIdleRtc(long ts) {
            if (takingRtc) return true;   // 닫는 중 — 끝나면 그때 표시 대상으로 연다(카메라 해제 전에 겹쳐 열지 않게)
            if (!rtcActive || rtcIceConnected) return false;
            takingRtc = true;
            postToD1("CLUSTER", "보조 카메라 우선 — 폰과 끊긴 카메라 화면 세션을 닫고 연다");
            new Thread(() -> {
                try { rtcStop(); }
                finally {
                    synchronized (ClusterCam.this) {
                        takingRtc = false;
                        if (!active && display != 0 && (clusterCamClients > 0 || huShow(display))) start(display, ts);
                    }
                }
            }, "assist-take-rtc").start();
            return true;
        }
        /** 보는 사람이 있나 — 없으면 신호가 와도 인코딩하지 않는다(상시 대기 중 폰이 안 붙어 있을 때 CPU 낭비 방지). */
        private boolean watched() { return clusterCamClients > 0 || RemoteWebServer.mjpegOpen.get() > 0 || (huOverlay != null && huOverlay.needsJpeg()) || (huOverlay2 != null && huOverlay2.needsJpeg()); }   // ★733 헤드유닛 창은 원본을 직접 그려 JPEG 불필요(안 되는 기기만)
        synchronized void onClients(int n) {
            if (n == 0) {
                if (!(display != 0 && huShow(display))) display = 0;   // ★729 헤드유닛 창이 보고 있으면 표시 유지
                if (active && display == 0 && !keepWanted()) stopAtMs = System.currentTimeMillis();   // ★729 상시 대기면 카메라 유지
                return;
            }
            stopAtMs = 0;
            Object v = relay != null ? relay.getStatus("turnflash") : null;
            int tf = (v instanceof Integer) ? (Integer) v : 1;
            boolean rev = lastGearVal == 1;                                   // 후진 중 접속하면 바로 후방
            if (rev) { display = 8; side = 8; }
            else if (tf == 2 || tf == 4) { display = tf; side = tf; }
            else { display = 0; side = 2; }                                   // 표시는 없지만 아래에서 예열은 시작
            if (!active && d5Ok()) start(side, System.currentTimeMillis());   // ★예열 — 접속 즉시 카메라만 열어둔다
        }
        /** camRtc 등 다른 카메라 소비자 진입 시 — 즉시 종료 후 스레드 종료 대기. */
        void stopNow(String why) {
            Thread t;
            synchronized (this) {   // gen 불일치 → 루프 즉시 탈출. 플래그는 여기서 직접 내림 — run()의 finally는 myGen==gen일 때만 내리므로(더 새 세션 보호) 여기서 안 내리면 active가 영구 true(재무장 死)
                if (!active) return; gen++; t = thread;
                active = false; stopAtMs = 0; RelayClient.cameraActive = false;
            }
            postToD1("CLUSTER", "측면 카메라 강제 종료(" + why + ")");
            try { if (t != null) t.join(2500); } catch (InterruptedException ignored) {}
        }
        RemoteWebServer.Frame frameAfter(long afterSeq, long waitMs) {
            synchronized (frameLock) {
                long end = System.currentTimeMillis() + waitMs;
                while (true) {
                    RemoteWebServer.Frame f = latest;
                    if (f != null && f.seq > afterSeq) return f;
                    long w = end - System.currentTimeMillis();
                    if (w <= 0) return null;
                    try { frameLock.wait(w); } catch (InterruptedException e) { return null; }
                }
            }
        }
        private void publish(byte[] jpeg, int sd) { synchronized (frameLock) { latest = new RemoteWebServer.Frame(++seq, jpeg, sd); frameLock.notifyAll(); } }

        // ★733 헤드유닛 창 직접 그리기 — 원본 NV21(순환 버퍼 그대로)과 창 크기 보정표를 HuAssistOverlay에 준다.
        private final Object nvLock = new Object();
        private volatile HuAssistOverlay.Nv lastNv; private long nvSeq;
        private void publishNv(byte[] nv, int w, int h) { synchronized (nvLock) { lastNv = new HuAssistOverlay.Nv(nv, w, h, ++nvSeq); nvLock.notifyAll(); } }
        @Override public RemoteWebServer.Frame next(long afterSeq, long waitMs) { return frameAfter(afterSeq, waitMs); }
        @Override public HuAssistOverlay.Nv nextNv(long afterSeq, long waitMs) {
            synchronized (nvLock) {
                long end = System.currentTimeMillis() + waitMs;
                while (true) {
                    HuAssistOverlay.Nv f = lastNv;
                    if (f != null && f.seq > afterSeq) return f;
                    long w = end - System.currentTimeMillis();
                    if (w <= 0) return null;
                    try { nvLock.wait(w); } catch (InterruptedException e) { return null; }
                }
            }
        }
        private String huFailKey = "";
        /** 폰과 같은 설정(깜빡이=ccam_2·4 커스텀 뷰, 후진=좌·후·우 파노라마 또는 ccam_8)으로 창 버퍼(bw×bh) 안에 비율 그대로 가장 크게. */
        @Override public HuAssistOverlay.Layout layout(int sd, int fw, int fh, int bw, int bh, HuAssistOverlay.Layout prev) {
            if (sd == 82 || sd == 84) {   // ★748 후진 D 따로 창 — D(B와 같은) 사이드미러 내려 보기 한 칸을 창 버퍼 가득(창 크기·비율은 운영자가 ◢로)
                String key = sd + "|" + fw + "x" + fh + "|" + bw + "x" + bh + "|" + ccamGen;
                if (prev != null && prev.key.equals(key)) return prev;
                try {
                    int[] tt = tileRect(fw, fh, sd == 82 ? 2 : 4); int qw = tt[2], qh = tt[3];
                    int dw = bw & ~1, dh = bh & ~1;
                    if (dw < 32 || dh < 32) return null;
                    com.cseini.motiondetect.ViewWarp w = revSideWarp(sd, tt, qw, qh, dw, dh);   // ★751 차주 조정값(없으면 기본)
                    return new HuAssistOverlay.Layout(key, new com.cseini.motiondetect.ViewWarp[]{w}, new int[]{0}, new int[]{0}, dw, dh, bw, bh);
                } catch (Throwable t) {
                    if (!key.equals(huFailKey)) { huFailKey = key; postToD1("HUASSIST", "창 보정표 실패 side=" + sd + " " + rootMsg(t)); }
                    return null;
                }
            }
            if (sd == 85 || sd == 86) {   // ★815 후진 D 왼쪽·오른쪽을 한 창에 붙임 — 85=가로(왼쪽|오른쪽) · 86=세로(위 왼쪽·아래 오른쪽). 칸마다 82·84와 같은 화면을 창 비율에 맞춰 가득(칸 사이 6px 검정)
                String key = sd + "|" + fw + "x" + fh + "|" + bw + "x" + bh + "|" + ccamGen;
                if (prev != null && prev.key.equals(key)) return prev;
                com.cseini.motiondetect.ViewWarp w1 = null;
                try {
                    final int SEP = 6; boolean hor = sd == 85;
                    int dw = (hor ? (bw - SEP) / 2 : bw) & ~1, dh = (hor ? bh : (bh - SEP) / 2) & ~1;
                    if (dw < 32 || dh < 32) return null;
                    int[] tl = tileRect(fw, fh, 2), tr = tileRect(fw, fh, 4);
                    w1 = revSideWarp(82, tl, tl[2], tl[3], dw, dh);
                    com.cseini.motiondetect.ViewWarp w2 = revSideWarp(84, tr, tr[2], tr[3], dw, dh);
                    return new HuAssistOverlay.Layout(key, new com.cseini.motiondetect.ViewWarp[]{w1, w2}, new int[]{0, hor ? dw + SEP : 0}, new int[]{0, hor ? 0 : dh + SEP},
                            hor ? 2 * dw + SEP : dw, hor ? dh : 2 * dh + SEP, bw, bh);
                } catch (Throwable t) {
                    if (w1 != null) w1.close();   // 둘째 칸이 실패하면 만든 첫 칸 보정표를 놓아준다
                    if (!key.equals(huFailKey)) { huFailKey = key; postToD1("HUASSIST", "창 보정표 실패 side=" + sd + " " + rootMsg(t)); }
                    return null;
                }
            }
            String lay = sd == 8 && !rvBroken && !IS_D5 ? revLayout() : "";   // ★734 후진 = 고른 화면(A·B·C) ★745 씨라7은 후방 칸 그대로
            String cfg = lay.isEmpty() ? ccamOf(sd) : "";
            String key = sd + "|" + fw + "x" + fh + "|" + bw + "x" + bh + "|" + ccamGen + "|" + lay + "|" + cfg;
            if (prev != null && prev.key.equals(key)) return prev;
            try {
                if (!lay.isEmpty()) {
                    int W = Math.min(bw, (int) ((long) bh * REV_W / REV_H)) & ~1, H = Math.min(bh, (int) ((long) W * REV_H / REV_W)) & ~1;   // 폰과 같은 비율로 버퍼 안에 가장 크게
                    if (W < 64 || H < 32) return null;
                    int[] xs = new int[3];
                    com.cseini.motiondetect.ViewWarp[] ws = revWarps(lay, fw, fh, W, H, xs);
                    return new HuAssistOverlay.Layout(key, ws, java.util.Arrays.copyOf(xs, ws.length), new int[ws.length], W, H, bw, bh);
                }
                int[] tr = tileRect(fw, fh, sd); int qw = tr[2], qh = tr[3];
                org.json.JSONObject c = cfg.isEmpty() ? null : new org.json.JSONObject(cfg);
                float a = c != null ? customAspect(c, qw, qh) : (float) qh / qw;
                int dw = Math.min(bw, (int) (bh / a)) & ~1, dh = Math.min(bh, Math.round(dw * a)) & ~1;
                if (dw < 32 || dh < 32) return null;
                com.cseini.motiondetect.ViewWarp w = c != null ? customWarp(c, tr, qw, qh, dw, dh)
                        : new com.cseini.motiondetect.ViewWarp(tr[0], tr[1], qw, qh, 0, 0, qw, qh, false, 160f, 0f, false, dw, dh, false, 0f, 0f, 0f, 0f);
                return new HuAssistOverlay.Layout(key, new com.cseini.motiondetect.ViewWarp[]{w}, new int[]{0}, new int[]{0}, dw, dh, bw, bh);
            } catch (Throwable t) {
                if (!key.equals(huFailKey)) { huFailKey = key; postToD1("HUASSIST", "창 보정표 실패 side=" + sd + " " + rootMsg(t)); }
                return null;
            }
        }

        private void start(int tf, long ts) {
            if (camOffOn()) return;   // ★카메라 기능 끔 — 계기 정보만, 카메라 칸 없음
            if (rtcActive || camStreaming || h264Streaming) { postToD1("CLUSTER", "측면 카메라 스킵 — 다른 카메라 세션(RTC/스트림) 사용 중"); return; }
            // ★주차 중에도 측면(어라운드뷰) 표시(2026-09-18 운영자) — 주차 감지(접근/작업/트렁크) 활성이어도 전환 순간 선점(run에서 detectStop). 감지는 종료 후 다음 트리거에 재무장.
            side = tf; active = true; stopAtMs = 0; RelayClient.cameraActive = true;
            rvBroken = false;   // ★734 새 세션은 후진 화면 다시 시도
            final int myGen = ++gen;
            thread = new Thread(() -> run(myGen, tf, ts), "cluster-cam"); thread.start();
        }

        private void run(int myGen, int tf0, long evtTs) {
            Object cam = null; Class<?> ncCls = null; android.media.ImageReader dummyIr = null;
            com.overdrive.app.camera.dilink5.FastCamQuadBackend d5q = null;   // ★745 씨라7 4분할 카메라
            final long t0 = System.currentTimeMillis(); int sent = 0;
            try {
                boolean hadDrive = detectActive && (detectAvm || !camDualOk());   // ★주행·주차 감지 모두 선점(주차 어라운드뷰 지원) — 종료 후 자연 재무장. ★626 대시캠 감지는 동시 운용(선점 안 함)
                if (hadDrive) { detectStop(); clearMotState(); }
                bsdExp.stopNow("clusterCam");   // ★602 BSD실험 선점
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }   // ★하드웨어 해제 대기(camRtc 패턴) — 직전 세션 채널 재사용으로 4분할 대신 전방이 나오던 것 방지
                if (myGen != gen) return;
                final byte[][] latestNv = new byte[1][]; final int[] meta = new int[2]; final int[] fc = new int[1];
                if (IS_D5) {
                    // ★745 씨라7 = AVMCamera 없음 → 우리 캡처 데몬(FastCamQuadBackend, 1920x1300 4분할·칸 960x650)을 연다. 프레임마다 새 배열이라 순환 버퍼 불필요.
                    //   칸 배치는 차주가 고른 d5Slot(tileRect). 비틀런처·오버드라이브가 채널을 쥐고 있으면 빈 칸("카메라 준비 중")이 나올 수 있다(앱 안내).
                    d5q = new com.overdrive.app.camera.dilink5.FastCamQuadBackend();
                    d5q.setRetryEnabled(true);   // 빠진 칸 다시 열기(실시간 보기와 같음)
                    boolean ok = d5q.start(getApplicationContext(), 20, (nv21, w, h) -> {
                        meta[0] = w; meta[1] = h; latestNv[0] = nv21; fc[0]++;
                        tileNv = nv21; tileW = w; tileH = h;
                        publishNv(nv21, w, h);
                    }, (tag, m) -> postToD1("CLUSTER", "씨라7 카메라 " + m));
                    if (!ok) { postToD1("CLUSTER", "씨라7 보조 카메라 시작 실패(캡처 데몬)"); return; }
                } else {   // ★745 아래는 돌핀 AVMCamera(그대로)
                if (bmmLoader == null) {
                    java.io.File jar = new java.io.File("/system/framework/bmmcamera.jar");
                    bmmLoader = new dalvik.system.DexClassLoader(jar.getPath(), getCodeCacheDir().getPath(), "/system/lib64:/system/lib", getClassLoader());
                }
                try { Class.forName("android.hardware.JNILoader", true, bmmLoader).getMethod("load").invoke(null); } catch (Throwable ignored) {}
                ncCls = Class.forName("android.hardware.AVMCamera", true, bmmLoader);
                Class<?> ipcCls = Class.forName("android.hardware.AVMCamera$IPreviewCallback", true, bmmLoader);
                cam = openAvmCam(ncCls, 1, true, "CLUSTER");
                if (cam == null) { postToD1("CLUSTER", "측면 카메라 open 실패(AVM cam=null)"); return; }
                // ★613 프레임 버퍼 재사용(3개 순환) — 예전엔 콜백마다 2560x1920 NV21(7.3MB)을 새로 할당해 초당 수백 MB 가비지 → GC 멈춤으로 끊김.
                //   인코딩(~15ms)이 버퍼 한 바퀴(3프레임≈100ms)보다 훨씬 짧아 읽는 중 덮어쓰기 없음. 새 프레임 판정은 참조 비교 대신 fc 카운터.
                final byte[][] pool = new byte[3][]; final int[] pi = new int[1];
                // ★634 서피스 프레임 직접 사용(운영자 09-28 "프레임 더 올려야") — 실측 콜백 9fps vs 서피스 23fps. 서피스 NV21 변환이
                //   한 번이라도 성공하면 콜백 경로는 건너뛰고, 변환 불가 레이아웃이면 surfFail로 기존 콜백 경로 유지(자동 폴백).
                final boolean[] surfOk = new boolean[1], surfFail = new boolean[1];
                try {   // ★더미 렌더 Surface — 없으면 ~89프레임 후 프레임 공급 정지(신호대기 중 깜빡이는 6초를 넘긴다). camDetect와 동일 패턴
                    // ★814 버퍼 2→4 — 카메라 창 계측(792, 10-06 18:39): 카메라가 보낸 장 16~20fps(쉬는 동안 25~28). 우리가 한 장을 14ms 붙잡고 복사하는 사이 카메라가 빈 버퍼를 기다리지 않게
                    dummyIr = android.media.ImageReader.newInstance(2560, 1920, android.graphics.ImageFormat.YUV_420_888, 4);
                    // ★623 더미 Surface 비우기(운영자 09-28 "반응속도·끊김 획기적 개선 안 되면 못 쓴다") — 실측 카메라 원본 2~9fps(인코딩 10~20ms라
                    //   병목 아님). 이 ImageReader(maxImages=2)를 한 번도 비우지 않아 버퍼큐가 차면 카메라 파이프라인이 감속할 수 있다.
                    //   매 프레임 acquireLatest→close로 비우고, 서피스 쪽 도착 fps를 따로 세어 원본(콜백) fps와 비교(다음 단계: 서피스 프레임 직접 사용).
                    android.os.HandlerThread irT = new android.os.HandlerThread("cluster-ir", android.os.Process.THREAD_PRIORITY_DISPLAY); irT.start();   // ★765 카메라 창 프레임 수신 — 헤드유닛 CPU 92% 중에도 새 프레임을 제때 받게 화면 표시 등급
                    irThread = irT;
                    dummyIr.setOnImageAvailableListener(r -> {
                        android.media.Image im = null; surfCb++;   // ★792 콜백 하나 = 카메라가 넣은 한 장(acquireLatest가 건너뛴 장 포함)
                        try {
                            im = r.acquireLatestImage(); if (im == null) return;
                            surfFc++;
                            if (surfFail[0]) return;
                            if (surfOk[0] && display == 0 && !turnSignalActive && (surfFc % 5) != 0) return;   // ★729 신호 없는 대기 중엔 5장에 1장만 복사(상시 대기 CPU) — 신호 오면 바로 전부
                            int w = im.getWidth(), h = im.getHeight(), ySize = w * h, size = ySize * 3 / 2;
                            android.media.Image.Plane[] pl = im.getPlanes();
                            if (pl.length < 3 || pl[2].getPixelStride() != 2 || pl[2].getRowStride() != w) {
                                surfFail[0] = true; postToD1("CLUSTER", "서피스 프레임 NV21 아님(uvPx=" + (pl.length > 2 ? pl[2].getPixelStride() : -1) + " uvRow=" + (pl.length > 2 ? pl[2].getRowStride() : -1) + ") → 콜백 경로 유지"); return;
                            }
                            long cpT0 = System.nanoTime();
                            byte[] c = pool[pi[0]];
                            if (c == null || c.length != size) { c = new byte[size]; pool[pi[0]] = c; }
                            java.nio.ByteBuffer yb = pl[0].getBuffer().duplicate(); int yRow = pl[0].getRowStride();
                            // ★743 재부팅·카메라 전환 중 비정상 프레임(평면 버퍼가 w·h 계산보다 작은데 yRow==w라 ySize만큼 읽어 네이티브 오버리드) → cluster-ir 스레드 SIGSEGV(10-03·04 D3 운영자 차 10회, memcpy).
                            //   복사 전에 Y 평면 remaining이 필요한 만큼 있는지 보고, 모자라면 이 프레임만 건너뛴다(크래시 대신). 재부팅 잦은 차에서 간헐 발생.
                            long yNeed = yRow == w ? (long) ySize : (long) yRow * (h - 1) + w;
                            if (yb.remaining() < yNeed) { if ((surfFc % 50) == 1) postToD1("CLUSTER", "서피스 프레임 비정상(Y 버퍼 " + yb.remaining() + " < 필요 " + yNeed + ", " + w + "x" + h + ") → 이 프레임 건너뜀"); return; }
                            if (yRow == w) yb.get(c, 0, ySize);
                            else for (int y = 0; y < h; y++) { yb.position(y * yRow); yb.get(c, y * w, w); }
                            java.nio.ByteBuffer vb = pl[2].getBuffer().duplicate();   // V 평면(픽셀간격2)=VU 인터리브 = NV21 그대로
                            int uvLen = Math.min(vb.remaining(), ySize / 2 - 1);
                            vb.get(c, ySize, uvLen);
                            java.nio.ByteBuffer ub = pl[1].getBuffer();
                            if (ub.limit() > 0) c[size - 1] = ub.get(ub.limit() - 1);   // 마지막 U 1바이트
                            copyUs += (System.nanoTime() - cpT0) / 1000; copyN++;
                            pi[0] = (pi[0] + 1) % pool.length;
                            meta[0] = w; meta[1] = h; latestNv[0] = c; fc[0]++;
                            tileNv = c; tileW = w; tileH = h;
                            publishNv(c, w, h);   // ★733 헤드유닛 창 직접 그리기
                            if (!surfOk[0]) { surfOk[0] = true; postToD1("CLUSTER", "서피스 프레임 직접 사용 " + w + "x" + h + " (콜백 경로 중지)"); if (huOverlay != null) huOverlay.prewarm(w, h); if (huOverlay2 != null) huOverlay2.prewarm(w, h); }
                        } catch (Throwable t) {
                            if (!surfFail[0]) { surfFail[0] = true; surfOk[0] = false; postToD1("CLUSTER", "서피스 프레임 변환 실패 " + rootMsg(t) + " → 콜백 경로 유지"); }
                        } finally { if (im != null) try { im.close(); } catch (Throwable ignored) {} }
                    }, new android.os.Handler(irT.getLooper()));
                    ncCls.getMethod("addPreviewSurface", android.view.Surface.class, int.class).invoke(cam, dummyIr.getSurface(), 0);
                } catch (Throwable t) { postToD1("CLUSTER", "addPreviewSurface 실패 " + rootMsg(t)); }
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(bmmLoader, new Class[]{ipcCls}, (pr, m, a) -> {
                    if ("onPreview".equals(m.getName()) && a != null && a.length >= 6 && !surfOk[0]) {
                        try {
                            int size = (int) a[5]; byte[] src = (byte[]) a[1];
                            byte[] c = pool[pi[0]];
                            if (c == null || c.length != size) { c = new byte[size]; pool[pi[0]] = c; }
                            pi[0] = (pi[0] + 1) % pool.length;
                            System.arraycopy(src, 0, c, 0, Math.min(src.length, size));
                            meta[0] = (int) a[2]; meta[1] = (int) a[3]; latestNv[0] = c; fc[0]++;
                            tileNv = c; tileW = meta[0]; tileH = meta[1];   // ★619 클러스터 '카메라 화면 조정' 배경 스냅샷용
                            publishNv(c, meta[0], meta[1]);   // ★733
                        } catch (Throwable ig) {}
                    }
                    Class<?> rt = m.getReturnType(); return rt == boolean.class ? Boolean.FALSE : (rt == int.class ? Integer.valueOf(0) : null);
                });
                ncCls.getMethod("setPreviewCallback", ipcCls).invoke(cam, proxy);
                ncCls.getMethod("enablePreviewCallback", int.class).invoke(cam, 5);
                ncCls.getMethod("startPreview").invoke(cam);
                }   // ★745 돌핀 AVMCamera 끝
                final long tOpen = System.currentTimeMillis();
                postToD1("CLUSTER", (IS_D5 ? "씨라7 " : "") + "측면 카메라 시작 side=" + tf0 + " 주행감지선점=" + hadDrive + " open +" + (tOpen - t0) + "ms (이벤트 +" + (tOpen - evtTs) + "ms)");
                // ★570 측면 카메라 = 화질보다 즉시성(운영자 "화질 더 낮춰서라도 지연 거의 없고 부드럽게").
                //   실측(09-22 로그): 소스 2560x1920 → 사분면 1280x960. scale=1 시절 JPEG **평균 31KB·최대 105KB**로
                //   15fps면 초당 465KB — 핫스팟엔 과해 끊겼다. 566에서 scale2/q50으로 낮췄고, 이번엔 한 단계 더:
                //   **scale 3(426x320, 픽셀 44%)·q 42**로 줄이고 남는 여유를 **간격 66→50ms(20fps)**에 써서 부드럽게.
                //   ★자바 서브샘플 루프가 픽셀당 돌아 해상도를 낮추면 인코딩 CPU도 같이 내려간다(전송량만의 문제가 아님).
                //   전부 prefs로 되돌릴 수 있다(cluster_cam_scale·_q·_ms).
                //   ★735 간격 50→30ms(운영자 10-04 폰 "너무 끊긴다") — 실측 카메라 원본 24~26fps·인코딩 28~44ms인데 50ms 간격과 프레임 도착이 어긋나
                //   15~16fps만 나갔다. 30ms면 새 프레임마다 바로 인코딩해 원본 속도(~25fps)까지. 측면 장당 9~13KB라 초당 ~300KB(핫스팟 충분).
                final int scale = IS_D5 ? (prefs != null ? prefs.getInt("cluster_cam_scale_d5", 2) : 2)   // ★745 씨라7 칸 960x650 — 3이면 320폭이라 2(480폭)
                        : (prefs != null ? prefs.getInt("cluster_cam_scale", 3) : 3);
                final int q = prefs != null ? prefs.getInt("cluster_cam_q", 42) : 42;
                final int minMs = prefs != null ? prefs.getInt("cluster_cam_ms", 30) : 30;
                int lastFc = 0; long lastEnc = 0; boolean first = true;
                // ★570 튜닝 근거용 실측 — 5초마다 평균 인코딩ms·평균 크기·실제 fps를 남긴다(추측으로 더 낮추지 않기 위해).
                long statT0 = System.currentTimeMillis(); int statN = 0; long statEncMs = 0, statBytes = 0; int statFc0 = fc[0]; int statSf0 = surfFc;
                int stallFc = 0; long stallAt = tOpen;
                while (active && myGen == gen) {
                    long now = System.currentTimeMillis();
                    long st = stopAtMs; if (st != 0 && now >= st) break;
                    if (fc[0] == 0 && now - tOpen > (IS_D5 ? 15000 : 8000)) { if (!IS_D5 && detectActive && !detectAvm) markDualFail("클러스터 첫 프레임 없음(전방 감지 동시)"); postToD1("CLUSTER", "첫 프레임 " + (IS_D5 ? 15 : 8) + "초 미수신 — 카메라 프레임 死, 종료"); break; }   // ★626 ★745 씨라7은 데몬 기동·4칸 합성 대기로 첫 장이 늦다
                    if (fc[0] != stallFc) { stallFc = fc[0]; stallAt = now; }
                    else if (fc[0] > 0 && now - stallAt > 5000) { postToD1("CLUSTER", "카메라 프레임 5초 멈춤 — 닫고 다시 열기"); break; }   // ★729 상시 대기는 세션이 길어 중간에 멈추면 시동 끌 때까지 죽은 채 남는다(다시 열기는 keepTick)
                    // ★570 예열 중(display==0)엔 인코딩·전송을 하지 않는다 — 카메라만 열어둬 다음 트리거가 즉시 뜨게.
                    if (!IS_D5) bsdExp.feedFromCluster(latestNv[0], meta[0], meta[1]);   // ★603 BSD 기록 실험 — 클러스터 쿼드 프레임 공유(예열 중 포함) ★745 돌핀 칸 배치 전용
                    if (display == 0 || !watched()) { try { Thread.sleep(20); } catch (InterruptedException e) { break; } continue; }   // ★729 보는 사람(폰·헤드유닛 창) 없으면 인코딩 안 함
                    side = display;   // 트리거가 바꾼 표시 대상을 크롭에 반영(세션 중 전환)
                    byte[] f = latestNv[0]; int fcNow = fc[0];
                    if (f == null || fcNow == lastFc || meta[0] <= 0 || now - lastEnc < minMs) { try { Thread.sleep(10); } catch (InterruptedException e) { break; } continue; }
                    lastFc = fcNow; lastEnc = now;
                    long encT0 = System.currentTimeMillis();
                    byte[] jpg = side == 8 ? encodeRev(f, meta[0], meta[1], q) : null;   // ★734 후진 화면 A·B·C(실패 시 아래 후방 커스텀 뷰)
                    if (jpg == null) jpg = encodeCustom(f, meta[0], meta[1], side, q);   // ★617 방향별 커스텀(영역·어안보정·반전) — 설정 없으면 null
                    if (jpg == null) jpg = encodeQuadrant(f, meta[0], meta[1], side, scale, q);
                    if (jpg == null) continue;
                    publish(jpg, side); sent++;
                    statN++; statEncMs += System.currentTimeMillis() - encT0; statBytes += jpg.length;
                    if (first) { first = false; postToD1("CLUSTER", "첫 프레임 side=" + side + " " + meta[0] + "x" + meta[1] + " jpeg=" + jpg.length + "B (시작 +" + (now - t0) + "ms · 이벤트 +" + (now - evtTs) + "ms)"); }
                    long span = now - statT0;
                    if (span >= 5000) {
                        postToD1("CLUSTER", "카메라 실측 " + statN + "프레임/" + (span / 1000) + "s = " + (statN * 1000 / Math.max(1, span)) + "fps"
                                + " · 인코딩 평균 " + (statEncMs / Math.max(1, statN)) + "ms · 평균 " + (statBytes / Math.max(1, statN) / 1024) + "KB"
                                + " (scale=" + scale + " q=" + q + ") · 카메라 원본 " + ((fc[0] - statFc0) * 1000 / Math.max(1, span)) + "fps"
                                + " · 서피스 " + ((surfFc - statSf0) * 1000 / Math.max(1, span)) + "fps");   // ★613 병목 분리(원본 공급 vs 전송) ★623 서피스 도착 fps
                        statT0 = now; statN = 0; statEncMs = 0; statBytes = 0; statFc0 = fc[0]; statSf0 = surfFc;
                    }
                }
            } catch (Throwable t) {
                postToD1("CLUSTER", "측면 카메라 예외 " + rootMsg(t));
            } finally {
                try { if (d5q != null) d5q.stop(); } catch (Throwable ig) {}   // ★745 씨라7 캡처 연결 해제(다른 소비자 없으면 데몬도 정리)
                try { if (cam != null && ncCls != null) { ncCls.getMethod("stopPreview").invoke(cam); ncCls.getMethod("close").invoke(cam); } } catch (Throwable ig) {}
                try { if (dummyIr != null) dummyIr.close(); } catch (Throwable ig) {}
                try { if (irThread != null) { irThread.quitSafely(); irThread = null; } } catch (Throwable ig) {}   // ★623
                synchronized (frameLock) { latest = null; frameLock.notifyAll(); }   // 다음 세션에 이전 프레임이 안 보이게(seq는 단조 유지)
                synchronized (nvLock) { lastNv = null; nvLock.notifyAll(); }   // ★733
                try { Thread.sleep(500); } catch (InterruptedException ig) {}   // 하드웨어 해제 대기 — active를 아직 내리지 않아 이 사이 폴 틱이 NormalCamera를 겹쳐 열지 못함(재무장·재시작 게이트 유지)
                synchronized (this) { if (myGen == gen) { active = false; stopAtMs = 0; RelayClient.cameraActive = false; } }   // stopNow(gen 불일치)면 거기서 이미 내림
                frontArmTryMs = 0;   // ★frontAutoArmTick 30s 스로틀 해제 → 다음 폴 틱(2s)에서 즉시 재무장
                postToD1("CLUSTER", "측면 카메라 종료 side=" + side + " 지속 " + ((System.currentTimeMillis() - t0) / 1000) + "s 전송 " + sent + "프레임");
            }
        }
    }

    // ★602 BSD실험(RECORD-ONLY) — 주행 중 좌/우 카메라 촬영 후 JPEG 저장(오프라인 분석용).
    //   우선순위: CAMRTC > ClusterCam > frontDetect > BSDEXP. 주행 중만(주차 금지). 깜빡이 ON 또는 BSD_STATE==2 트리거.
    private final class BsdExp {
        private volatile boolean active; private volatile boolean enabled;
        private volatile long failBackoffUntil = 0;
        private volatile int gen; private Thread thread;
        private final Object ringLock = new Object();
        private static final int RING_SIZE = 10;
        private class FrameEntry { long ts; byte[] jpegL; byte[] jpegR; int tf; int bsd; int speed; }
        private final FrameEntry[] ring = new FrameEntry[RING_SIZE];
        private int ringIdx = 0; private long lastKeptMs = 0;

        private volatile boolean flushRing = false;
        private volatile long captureUntil = 0;
        private volatile long lastLogMs = 0;
        private volatile long framesEncoded = 0, triggerCount = 0, filesWritten = 0;
        private volatile int prevBsd = 1, prevTf = 1;
        private long lastWrittenTs = 0; private long lastStopMs = 0;

        // ★723 깜빡이 켤 때만 측면 카메라(운영자 10-04 "깜빡이 킬 때 BSD 신호와 그쪽 카메라 보고 판정") — 예전엔 주행 내내 돌리려 했지만
        //   전방 감지(대시캠)가 켜지면 늘 비켜 줘서 주행 중 화면이 0장이었다(10-03 "시작 → yield(camDetect) → frame=0"). 대시캠과 360은
        //   다른 카메라라 같이 돈다(626, cam_dual_fail이면 예전처럼 비킴). 깜빡이 이벤트에서 바로 열고 끄고 3초 뒤 닫는다. tick은 놓친 이벤트 대비.
        synchronized void startForTurn() {
            if (!autoAllowed()) return;   // ★620
            if (lastDriving != 1 || !turnSignalActive || !enabled || DiLink5Support.isDiLink5() || !BuildConfig.IS_OPERATOR) return;
            if (active || rtcActive || camStreaming || h264Streaming || (detectActive && (detectAvm || !camDualOk())) || clusterCam.active) return;
            long now = System.currentTimeMillis();
            if (now - lastStopMs < 1000) return;   // 닫자마자 다시 열기 방지
            if (now < failBackoffUntil) return;     // ★602 카메라 open 실패/첫 프레임 미수신 후 5분 재시도 금지(2초 tick마다 재시작·로그 반복 방지)
            active = true; gen++; final int myGen = gen;
            thread = new Thread(() -> run(myGen), "bsdexp");
            thread.start();
        }
        void tick(int driving) { if (driving == 1 && turnSignalActive) startForTurn(); }

        void onTurnSignal(int tfRaw) {
            int tf = (tfRaw == 2 || tfRaw == 4) ? tfRaw : 1;
            if (tf != prevTf && (active || fedByCluster()) && lastDriving == 1) {   // ★613 클러스터 공유 모드에서도 트리거(예전엔 active만 → 09-27 주행 트리거 0·저장 0)
                prevTf = tf;
                if (tf != 1) { flushRing = true; captureUntil = Math.max(captureUntil, System.currentTimeMillis() + 3000); triggerCount++; }
            }
        }

        void onBsdEvent(int bsdVal) {
            if (bsdVal == prevBsd || !(active || fedByCluster()) || lastDriving != 1) return;
            prevBsd = bsdVal;
            if (bsdVal == 2) { flushRing = true; captureUntil = Math.max(captureUntil, System.currentTimeMillis() + 3000); triggerCount++; }
        }

        private void run(int myGen) {
            Object cam = null; Class<?> ncCls = null; android.media.ImageReader dummyIr = null;
            final long t0 = System.currentTimeMillis();
            try {
                if (bmmLoader == null) {
                    java.io.File jar = new java.io.File("/system/framework/bmmcamera.jar");
                    bmmLoader = new dalvik.system.DexClassLoader(jar.getPath(), getCodeCacheDir().getPath(), "/system/lib64:/system/lib", getClassLoader());
                }
                try { Class.forName("android.hardware.JNILoader", true, bmmLoader).getMethod("load").invoke(null); } catch (Throwable ignored) {}
                ncCls = Class.forName("android.hardware.AVMCamera", true, bmmLoader);
                Class<?> ipcCls = Class.forName("android.hardware.AVMCamera$IPreviewCallback", true, bmmLoader);
                cam = openAvmCam(ncCls, 1, true, "BSDEXP");
                if (cam == null) { failBackoffUntil = System.currentTimeMillis() + 300_000L; postToD1("BSDEXP", "카메라 open 실패 — 5분 뒤 재시도"); return; }
                try {
                    dummyIr = android.media.ImageReader.newInstance(2560, 1920, android.graphics.ImageFormat.YUV_420_888, 2);
                    ncCls.getMethod("addPreviewSurface", android.view.Surface.class, int.class).invoke(cam, dummyIr.getSurface(), 0);
                } catch (Throwable t) { postToD1("BSDEXP", "addPreviewSurface 실패"); }
                final byte[][] latestNv = new byte[1][]; final int[] meta = new int[2]; final int[] fc = new int[1]; final long[] firstAt = new long[1];
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(bmmLoader, new Class[]{ipcCls}, (pr, m, a) -> {
                    if ("onPreview".equals(m.getName()) && a != null && a.length >= 6) {
                        try {
                            int size = (int) a[5]; byte[] src = (byte[]) a[1]; byte[] c = new byte[size];
                            System.arraycopy(src, 0, c, 0, Math.min(src.length, size));
                            if (fc[0] == 0) firstAt[0] = System.currentTimeMillis();
                            meta[0] = (int) a[2]; meta[1] = (int) a[3]; latestNv[0] = c; fc[0]++;
                        } catch (Throwable ig) {}
                    }
                    Class<?> rt = m.getReturnType(); return rt == boolean.class ? Boolean.FALSE : (rt == int.class ? Integer.valueOf(0) : null);
                });
                ncCls.getMethod("setPreviewCallback", ipcCls).invoke(cam, proxy);
                ncCls.getMethod("enablePreviewCallback", int.class).invoke(cam, 5);
                ncCls.getMethod("startPreview").invoke(cam);

                final int scale = prefs != null ? prefs.getInt("bsdexp_scale", 1) : 1;
                byte[] last = null;
                long lastTurnMs = t0; boolean firstLogged = false;

                postToD1("BSDEXP", "시작 open +" + (System.currentTimeMillis() - t0) + "ms");

                while (active && myGen == gen && lastDriving == 1) {
                    if (!enabled) break;
                    long now = System.currentTimeMillis();
                    if (turnSignalActive) lastTurnMs = now; else if (now - lastTurnMs > 3000) break;   // ★723 깜빡이 끄고 3초 뒤 닫기
                    if (!firstLogged && firstAt[0] > 0) { firstLogged = true; postToD1("BSDEXP", "첫 화면 +" + (firstAt[0] - t0) + "ms" + (detectActive ? " · 전방 감지와 동시" : "")); }   // ★723 깜빡이~첫 화면 실측
                    if (fc[0] == 0 && now - t0 > 8000) { failBackoffUntil = now + 300_000L; postToD1("BSDEXP", "첫 프레임 8초 미수신 — 종료(5분 뒤 재시도)"); break; }
                    if (now - lastKeptMs < 333) { try { Thread.sleep(10); } catch (InterruptedException e) { break; } continue; }   // ~3fps target
                    lastKeptMs = now;
                    byte[] f = latestNv[0];
                    if (f == null || f == last || meta[0] <= 0) { try { Thread.sleep(10); } catch (InterruptedException e) { break; } continue; }
                    last = f;
                    processFrame(f, meta[0], meta[1], now, scale);
                }
            } catch (Throwable t) {
                Log.e(TAG, "BSDEXP run exception", t);
                postToD1("BSDEXP", "예외: " + t.getClass().getSimpleName());
            } finally {
                try { if (cam != null && ncCls != null) { ncCls.getMethod("stopPreview").invoke(cam); ncCls.getMethod("close").invoke(cam); } } catch (Throwable ig) {}
                synchronized (this) { if (myGen == gen) { active = false; lastStopMs = System.currentTimeMillis(); } }
                if (System.currentTimeMillis() < captureUntil) try { flushRingToDisk(false); } catch (Throwable ignored) {}   // ★603 트리거 창 안에서 끝났을 때만(무관 프레임 저장 안 함)
                postToD1("BSDEXP", "종료 — frame=" + framesEncoded + " trigger=" + triggerCount + " files=" + filesWritten);
            }
        }

        // ★603 한 프레임 처리(자체 세션·클러스터 공유 공용) — 좌/우 타일 인코딩 → 링버퍼 → 트리거 창이면 디스크.
        //   602 버그 수정: 트리거(깜빡이·BSD=2)가 flushRing/captureUntil만 세우고 실제 저장은 세션 종료 때 마지막 링만 써서
        //   "트리거 전후 3초"가 저장되지 않았다. 이제 트리거 순간 링(직전 ~3초)을 쓰고, captureUntil까지 새 프레임을 계속 쓴다.
        private long statT0 = System.currentTimeMillis();
        volatile byte[] lastNv; volatile int lastW, lastH; volatile long lastNvMs;   // ★712 BSD 방향 확인용 최근 쿼드 프레임(자체 세션·클러스터 공유 모두 매번 새 배열)
        private void processFrame(byte[] f, int w, int h, long now, int scale) {
            lastW = w; lastH = h; lastNv = f; lastNvMs = now;
            byte[] jpgL = encodeQuadrant(f, w, h, 2, scale, 60);
            byte[] jpgR = encodeQuadrant(f, w, h, 4, scale, 60);
            if (jpgL == null || jpgR == null) return;
            Object tfObj = relay != null ? relay.getStatus("turnflash") : null;
            int tf = (tfObj instanceof Integer) ? (Integer) tfObj : 1;
            int speed = (lastSpeed > 3.0) ? (int) Math.round(lastSpeed) : 0;
            synchronized (ringLock) {
                FrameEntry e = ring[ringIdx];
                if (e == null) e = ring[ringIdx] = new FrameEntry();
                e.ts = now; e.jpegL = jpgL; e.jpegR = jpgR; e.tf = tf; e.bsd = lastBsdValue; e.speed = speed;
                ringIdx = (ringIdx + 1) % RING_SIZE;
                framesEncoded++;
            }
            if (flushRing || now < captureUntil) { flushRing = false; flushRingToDisk(false); }
            if (now - statT0 >= 1_800_000L) {   // 하트비트 30분
                statT0 = now;
                postToD1("BSDEXP", "hb frame=" + framesEncoded + " trigger=" + triggerCount + " written=" + filesWritten + " disk_mb=" + estimateDiskUsage() + " src=" + (clusterCam.active ? "cluster" : "own"));
            }
        }
        // ★603 클러스터 측면캠 세션 공유(운영자 09-27 "클러스터 상시 켜두고 깜빡이 카메라 볼 건데 그 세션을 쓰면 되지 않나") —
        //   클러스터를 보는 동안엔 ClusterCam이 쿼드를 이미 열어두므로(예열 포함) 그 NV21 프레임을 ~3fps로 받아 같은 처리. 카메라 경쟁 없음.
        private long lastExtMs, lastTurnFeedMs; private volatile boolean extBusy;
        // ★613 클러스터 카메라 끊김 근본(운영자 09-27 "너무 끊겨") — 예전엔 이 처리(원본해상도 L/R JPEG 2장 인코딩)를 ClusterCam 루프 스레드에서
        //   동기로 돌려 0.33초마다 클러스터 전송이 수백 ms씩 멈췄다(실측 20fps 목표에 2~10fps, 주행 중만=BSD실험 조건과 일치).
        //   이제 프레임을 복사해 별도 스레드로 넘기고, 이전 처리가 안 끝났으면 이번 프레임은 버린다(클러스터 루프는 절대 안 기다림).
        void feedFromCluster(byte[] f, int w, int h) {
            if (f == null || w <= 0 || extBusy) return;
            if (!enabled || !BuildConfig.IS_OPERATOR || DiLink5Support.isDiLink5() || lastDriving != 1 || active) return;
            long now = System.currentTimeMillis();
            if (turnSignalActive || lastBsdValue == 2) lastTurnFeedMs = now; else if (now - lastTurnFeedMs > 3000) return;   // ★729 깜빡이·BSD 경보 동안(+3초)만 — 보조 카메라 상시 대기로 주행 내내 원본 L/R 인코딩(초당 3회)하던 것 방지
            if (now - lastExtMs < 333) return;
            lastExtMs = now; extBusy = true;
            final byte[] cp = f.clone();   // ClusterCam 버퍼는 재사용되므로 복사본으로 처리
            final int scale = prefs != null ? prefs.getInt("bsdexp_scale", 1) : 1;
            new Thread(() -> {
                try {
                    processFrame(cp, w, h, now, scale);
                    // ★613 공유 모드 저장 — 디스크 기록은 자체 run 루프에만 있어 클러스터 공유 중엔 트리거가 와도 한 장도 안 써졌다.
                    if (flushRing || System.currentTimeMillis() < captureUntil) { flushRing = false; flushRingToDisk(false); }
                } catch (Throwable ignored) {}
                finally { extBusy = false; }
            }, "bsdexp-ext").start();
        }
        private boolean fedByCluster() { return System.currentTimeMillis() - lastExtMs < 2000 || clusterCam.active; }   // ★729 상시 대기 중엔 신호 전엔 처리를 쉬므로 카메라가 열려 있으면 공유 중으로 본다
        private void flushRingToDisk(boolean allRemaining) {
            try {
                synchronized (ringLock) {
                    java.io.File baseDir = getExternalFilesDir(null);
                    if (baseDir == null) return;
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
                    String dateDir = sdf.format(new Date());
                    java.io.File dir = new java.io.File(baseDir, "bsdexp/" + dateDir);
                    if (!dir.exists()) dir.mkdirs();

                    java.io.PrintWriter csv = new java.io.PrintWriter(new java.io.FileWriter(new java.io.File(dir, "index.csv"), true));

                    long now = System.currentTimeMillis();
                    for (int k = 0; k < RING_SIZE; k++) {   // ★603 오래된 것부터(ringIdx=가장 오래된 칸) — 순서가 섞이면 lastWrittenTs에 막혀 앞 프레임이 빠졌다
                        FrameEntry e = ring[(ringIdx + k) % RING_SIZE];
                        if (e == null || e.jpegL == null) continue;
                        if (!allRemaining && e.ts <= lastWrittenTs) continue;
                        if (e.ts > now) continue;   // 미래 프레임 스킵

                        String tfStr = e.tf == 2 ? "1" : (e.tf == 4 ? "2" : "0");
                        String nameL = e.ts + "_L_turn" + tfStr + "_bsd" + e.bsd + ".jpg";
                        String nameR = e.ts + "_R_turn" + tfStr + "_bsd" + e.bsd + ".jpg";

                        java.io.FileOutputStream fL = new java.io.FileOutputStream(new java.io.File(dir, nameL));
                        fL.write(e.jpegL); fL.close();
                        java.io.FileOutputStream fR = new java.io.FileOutputStream(new java.io.File(dir, nameR));
                        fR.write(e.jpegR); fR.close();

                        csv.println(e.ts + "," + tfStr + "," + e.bsd + "," + e.speed + "," + nameL + "," + nameR);
                        lastWrittenTs = e.ts;
                        filesWritten++;
                    }
                    csv.close();
                    cleanupOldDays();
                }
            } catch (Throwable t) { Log.w(TAG, "flushRingToDisk", t); }
        }

        private void cleanupOldDays() {
            try {
                java.io.File baseDir = getExternalFilesDir(null);
                if (baseDir == null) return;
                java.io.File bsdDir = new java.io.File(baseDir, "bsdexp");
                if (!bsdDir.exists()) return;

                long used = 0;
                for (java.io.File d : bsdDir.listFiles()) { if (d.isDirectory()) used += getDirSize(d); }

                if (used > 200 * 1024 * 1024) {
                    java.io.File[] dirs = bsdDir.listFiles();
                    if (dirs != null) {
                        java.util.Arrays.sort(dirs);
                        for (java.io.File d : dirs) {
                            if (used <= 200 * 1024 * 1024) break;
                            long sz = getDirSize(d);
                            deleteRecursive(d);
                            used -= sz;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        private long getDirSize(java.io.File d) { long sz = 0; java.io.File[] ff = d.listFiles(); if (ff != null) for (java.io.File f : ff) sz += f.isDirectory() ? getDirSize(f) : f.length(); return sz; }
        private void deleteRecursive(java.io.File d) { java.io.File[] ff = d.listFiles(); if (ff != null) for (java.io.File f : ff) { if (f.isDirectory()) deleteRecursive(f); else f.delete(); } d.delete(); }

        private long estimateDiskUsage() {
            try {
                java.io.File baseDir = getExternalFilesDir(null);
                if (baseDir == null) return 0;
                java.io.File bsdDir = new java.io.File(baseDir, "bsdexp");
                if (!bsdDir.exists()) return 0;
                long total = 0;
                for (java.io.File d : bsdDir.listFiles()) { if (d.isDirectory()) total += getDirSize(d); }
                return total / (1024 * 1024);
            } catch (Throwable ignored) { return 0; }
        }

        void stopNow(String why) {
            Thread t;
            synchronized (this) {
                if (!active) return;
                gen++; t = thread;
                active = false;
            }
            postToD1("BSDEXP", "yield(" + why + ")");
            try { if (t != null) t.join(2500); } catch (InterruptedException ignored) {}
        }

        String status() {
            return "BSDEXP enabled=" + enabled + " active=" + active + " frame=" + framesEncoded + " trigger=" + triggerCount + " files=" + filesWritten + " disk=" + estimateDiskUsage() + "MB";
        }
    }

    /** /manifest — 앱 리모컨과 같은 재료를 한 JSON으로(caps·apps·split·state·parked). */
    private String webManifestJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            org.json.JSONObject m = remoteManifestJson.isEmpty() ? new org.json.JSONObject() : new org.json.JSONObject(remoteManifestJson);
            o.put("v", 1).put("model", m.optString("model", "")).put("dilink", m.optInt("dilink", 3)).put("caps", m.optJSONArray("caps") != null ? m.optJSONArray("caps") : new org.json.JSONArray());
            o.put("apps", remoteAppsJson.isEmpty() ? new org.json.JSONArray() : new org.json.JSONArray(remoteAppsJson));
            String sp = relay != null ? String.valueOf(relay.getStatus("remote_split")) : null;
            o.put("split", sp != null && sp.startsWith("{") ? new org.json.JSONObject(sp) : org.json.JSONObject.NULL);
            org.json.JSONObject st = relay != null ? new org.json.JSONObject(relay.stateJson()) : new org.json.JSONObject();
            st.remove("remote_manifest"); st.remove("remote_apps"); st.remove("remote_split");
            o.put("state", st).put("parked", parkedForLauncher()).put("vin", cloudVin == null || cloudVin.length() < 6 ? "x" : cloudVin.substring(cloudVin.length() - 6));
            o.put("modelName", DiLink5Support.isDiLink5() ? "씨라이언7" : "리모컨");
            return o.toString();
        } catch (Throwable t) { return "{\"error\":\"" + t.getClass().getSimpleName() + "\"}"; }
    }
    /** /cmd — 앱의 RelayClient 경유와 같은 실행 경로로 수렴(RAW=execRelayAction, 앱런처/트렁크=전용 핸들러). 그 외 액션은 거부(웹은 무인증). */
    private String webRunCommand(String action, org.json.JSONObject params) {
        try {
            if ("RAW".equals(action)) return execRelayAction("RAW", params);
            if (action.startsWith("LAUNCHAPP ") || action.startsWith("SPLIT ") || action.startsWith("SPLITRATIO ") || action.equals("APPLIST")) return appLauncherCmd(action);
            if (action.equals("TRUNK") || action.startsWith("TRUNK ")) return trunkCmd(action, params);
            // ★862 트랙패드 실험 — 고빈도(드래그 초당 수십콜)라 워커 경유가 없다(웹/LAN 전용). uinput 지속연결은 MouseInjector가 관리.
            if ("CLUSTERCFG".equals(action)) return clusterLayoutCfg(params);   // ★619 클러스터 레이아웃(위젯·테마·칩) 릴레이 저장 — 클러스터 페이지 LAN 저장
            if ("CLUSTERCAM".equals(action)) return clusterCamCfg(params);      // ★619 클러스터 안 '카메라 화면 조정' LAN 적용
            if ("HUASSIST".equals(action)) return huAssistCmd(params);          // ★728 헤드유닛 카메라 창 설정·위치 조정(폰 직결 경로)
            if ("MOUSE".equals(action)) return MouseInjector.handle(this, params);
            // ★871 링크락 런처 요청(운영자 10-11) — 순정 홈 되돌리기(탈출 버튼, 게이트 없음)·공조 전원 켜고 끄기·비상등(클라우드)
            if ("RESTOREHOME".equals(action)) return restoreStockHome();
            if ("CLIMATE".equals(action)) {
                Object cur = relay != null ? relay.getStatus("ac_start_st") : null;
                int v = params != null && params.has("on") ? (params.optBoolean("on") ? 1 : 0) : ((cur instanceof Integer && (Integer) cur == 1) ? 0 : 1);
                String r = execRelayAction("RAW", new org.json.JSONObject().put("device", "ac").put("baseFeature", "AC_POWER_STATE_SET").put("value", v));
                postToD1("LAUNCHER", "공조 " + (v == 1 ? "켜기" : "끄기") + " → " + (r == null ? "OK" : r));
                return r == null ? "공조 " + (v == 1 ? "켬" : "끔") : "공조 실패 " + r;
            }
            if ("HAZARD".equals(action)) { String r = cloudFlash(); postToD1("LAUNCHER", "비상등 → " + r); return r; }   // ★601 공유판 개방(운영자 09-27 "다 오픈하고 피드백 받아 수정") — 씨라7 등 uinput 미검증 차종은 A1 TRACKPAD 로그로 확인
            return "웹에서 허용되지 않는 명령: " + action;
        } catch (Throwable t) { return "실패 " + t.getClass().getSimpleName() + ":" + t.getMessage(); }
    }
    // ★872 '링크락 런처 사용' 배포 스위치(운영자 10-11, 런처 세션 요청 A0) — 켜면 A1에서 런처를 받아 해시 확인 뒤 셸로 설치하고 헤드유닛 홈으로 지정,
    //   끄면 홈을 순정으로 되돌리고 지운 뒤 우리가 바꾼 시스템 설정만 원래 값으로 되돌린다([[설정 원복 원칙]]). 설치·제거·업데이트는 P단 주차(또는 시동 꺼짐) 중에만.
    //   APK는 앱에 넣지 않고 서버에서 받는다(FB2011). 운영자판 먼저.
    static final String LAUNCHER_PKG = "com.cseini.linklock";
    private final java.util.concurrent.atomic.AtomicBoolean launcherBusy = new java.util.concurrent.atomic.AtomicBoolean(false);
    public boolean launcherOn() { return prefs != null && prefs.getBoolean("launcher_on", false); }
    private boolean launcherParked() { return lastGearVal == 3 || (relay != null && relay.ignitionState() != 1); }
    private int installedLauncherVer() {
        try { return getPackageManager().getPackageInfo(LAUNCHER_PKG, 0).versionCode; } catch (Throwable t) { return -1; }
    }
    /** 화면 버튼에서 부른다 — 바로 돌아오고 결과는 토스트·로그로 */
    public String launcherSet(boolean on) {
        if (!launcherParked()) return "P단에 두거나 시동을 끈 뒤에 바꿀 수 있어요";
        if (!launcherBusy.compareAndSet(false, true)) return "이미 처리 중이에요";
        if (prefs != null) prefs.edit().putBoolean("launcher_on", on).apply();
        new Thread(() -> {
            String r;
            try { r = on ? launcherInstallOrUpdate(true) : launcherRemove(); }
            catch (Throwable t) { r = "실패 " + t.getClass().getSimpleName() + ":" + t.getMessage(); }
            finally { launcherBusy.set(false); }
            if (on && (r == null || !(r.contains(" 설치") || r.contains("그대로"))) && prefs != null) prefs.edit().putBoolean("launcher_on", false).apply();   // 못 깔았으면 스위치도 꺼 둔다
            postToD1("LAUNCHER", (on ? "켜기" : "끄기") + " → " + r);
            final String msg = r;
            mainHandler.post(() -> android.widget.Toast.makeText(getApplicationContext(), "링크락 런처 — " + msg, android.widget.Toast.LENGTH_LONG).show());
        }, "launcher-set").start();
        return on ? "링크락 런처를 받아 설치하는 중이에요" : "순정 홈으로 되돌리는 중이에요";
    }
    /** 시동을 끌 때마다(켜 둔 경우만) 새 판이 있으면 올린다 */
    private void launcherUpdateCheck() {
        if (!launcherOn() || !launcherParked() || !launcherBusy.compareAndSet(false, true)) return;
        new Thread(() -> {
            try { String r = launcherInstallOrUpdate(false); if (r != null) postToD1("LAUNCHER", "업데이트 확인 → " + r); }
            catch (Throwable t) { postToD1("LAUNCHER", "업데이트 확인 실패 " + t.getClass().getSimpleName()); }
            finally { launcherBusy.set(false); }
        }, "launcher-update").start();
    }
    /** force=true면 이미 최신이어도 홈 지정까지 다시. 할 일이 없으면 null */
    private String launcherInstallOrUpdate(boolean force) throws Exception {
        org.json.JSONObject meta;
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://byd.cseini.co.kr/lllauncher.json").openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(20000);
        try (java.io.InputStream in = c.getInputStream()) { meta = new org.json.JSONObject(new String(readAll(in), "UTF-8")); }
        if (!meta.optBoolean("ok")) return "서버에 런처 판이 없어요";
        int ver = meta.optInt("ver"), have = installedLauncherVer();
        String sha = meta.optString("sha256");
        if (have >= ver && !force) return null;
        if (have < ver) {
            java.io.File apk = new java.io.File(getExternalFilesDir(null), "lllauncher.apk");
            java.net.HttpURLConnection d = (java.net.HttpURLConnection) new java.net.URL("https://byd.cseini.co.kr" + meta.optString("url")).openConnection();
            d.setConnectTimeout(10000); d.setReadTimeout(90000);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = d.getInputStream(); java.io.FileOutputStream fo = new java.io.FileOutputStream(apk)) {
                byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) { fo.write(buf, 0, n); md.update(buf, 0, n); }
            }
            StringBuilder hx = new StringBuilder(); for (byte x : md.digest()) hx.append(String.format("%02x", x));
            if (!hx.toString().equalsIgnoreCase(sha)) { apk.delete(); return "받은 파일 해시가 달라 설치 안 함"; }
            java.io.File pk = new java.io.File(getFilesDir(), "adbkey"), pub = new java.io.File(getFilesDir(), "adbkey.pub");
            if (!pk.exists() || !pub.exists()) return "셸 연결 키 없음(ADB_AUTH 먼저)";
            dadb.Dadb dd = dadb.Dadb.Companion.create("127.0.0.1", 5555, dadb.AdbKeyPair.Companion.read(pk, pub), 10000, 120000);
            try {
                String remote = "/data/local/tmp/lllauncher.apk";
                dd.push(apk, remote, 420, System.currentTimeMillis());
                dadb.AdbShellResponse ir = dd.shell("pm install -r -g " + remote + " 2>&1; rm -f " + remote);
                String out = ir.getAllOutput() == null ? "" : ir.getAllOutput().trim();
                if (!out.contains("Success")) return "설치 실패 " + (out.length() > 120 ? out.substring(0, 120) : out);
            } finally { try { dd.close(); } catch (Throwable ignored) {} apk.delete(); }
        }
        // 우리가 바꾸기 전 값을 한 번만 적어 둔다(끌 때 그대로 되돌림, 없던 값은 지움)
        if (prefs != null && !prefs.contains("launcher_orig_freeform")) {
            prefs.edit().putString("launcher_orig_freeform", SplitLauncher.adb(this, "settings get global enable_freeform_support").out.trim())
                    .putString("launcher_orig_resizable", SplitLauncher.adb(this, "settings get global force_resizable_activities").out.trim()).apply();
        }
        SplitLauncher.adb(this, "settings put global enable_freeform_support 1");
        SplitLauncher.adb(this, "settings put global force_resizable_activities 1");
        SplitLauncher.Res role = SplitLauncher.adb(this, "cmd role add-role-holder android.app.role.HOME " + LAUNCHER_PKG + " 2>&1");
        SplitLauncher.adb(this, "input keyevent 3");
        int now = installedLauncherVer();
        if (relay != null) relay.putStatus("launcher", now > 0 ? (launcherOn() ? "on:" : "off:") + now : "none");
        return (have < ver ? "판 " + ver + " 설치" : "판 " + now + " 그대로") + (role.ok ? " · 홈 지정" : " · 홈 지정 실패(" + role.exit + ")")
                + (have < 0 ? " · 자유 창은 헤드유닛을 한 번 껐다 켠 뒤부터" : "");
    }
    private String launcherRemove() {
        String home = restoreStockHome();
        SplitLauncher.Res un = SplitLauncher.adb(this, "pm uninstall " + LAUNCHER_PKG + " 2>&1");
        if (prefs != null && prefs.contains("launcher_orig_freeform")) {
            for (String[] kv : new String[][]{{"enable_freeform_support", prefs.getString("launcher_orig_freeform", "null")}, {"force_resizable_activities", prefs.getString("launcher_orig_resizable", "null")}}) {
                String v = kv[1] == null ? "null" : kv[1].trim();
                SplitLauncher.adb(this, v.isEmpty() || "null".equals(v) ? "settings delete global " + kv[0] : "settings put global " + kv[0] + " " + v);
            }
            prefs.edit().remove("launcher_orig_freeform").remove("launcher_orig_resizable").apply();
        }
        if (relay != null) relay.putStatus("launcher", "none");
        return home + " · 지움 " + (un.out.contains("Success") ? "OK" : un.out.trim());
    }
    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }
    // 런처 탈출 — 홈 역할을 풀고 순정 런처를 홈으로 지정한 뒤 홈 키. 셸이 없으면 실패를 돌려준다(런처가 홈 선택 화면으로 대신)
    private String restoreStockHome() {
        SplitLauncher.Res a = SplitLauncher.adb(this, "cmd role remove-role-holder android.app.role.HOME com.cseini.linklock 2>&1");
        SplitLauncher.Res b = SplitLauncher.adb(this, "cmd package set-home-activity com.android.launcher3/com.android.launcher3.home.MainActivity 2>&1");
        SplitLauncher.adb(this, "input keyevent 3");
        String r = (a.ok && b.ok) ? "순정 홈으로 되돌림" : "순정 홈 되돌리기 실패(role " + a.exit + " · home " + b.exit + " " + (b.out.length() > 80 ? b.out.substring(0, 80) : b.out).trim() + ")";
        postToD1("LAUNCHER", "RESTOREHOME → " + r);
        return r;
    }
    private boolean parkedForLauncher() { return lastGearVal == 3 && lastDriving != 1; }
    private String appLauncherCmd(String action) {
        if (action.equals("APPLIST")) { new Thread(this::publishRemoteApps, "applist").start(); return "앱 목록 갱신"; }
        // ★주차 게이트 없음(2026-09-11 운영자 "앱 런처는 주행 중에도 다 열어줘") — 주차 제한은 트렁크(trunkCmd)만.
        final String[] tok = action.split("\\s+");
        new Thread(() -> {
            String res; StringBuilder log = new StringBuilder();
            try {
                if (tok[0].equals("LAUNCHAPP") && tok.length >= 2) res = SplitLauncher.launchApp(this, tok[1]);
                else if (tok[0].equals("SPLIT") && tok.length >= 3) {
                    int pct = tok.length >= 4 ? Integer.parseInt(tok[3]) : 50;
                    res = SplitLauncher.split(this, tok[1], tok[2], pct, log);
                } else if (tok[0].equals("SPLITRATIO") && tok.length >= 2) res = SplitLauncher.applyRatio(this, Integer.parseInt(tok[1]), log);
                else res = "형식 오류: " + action;
            } catch (Throwable t) { res = "예외 " + t.getClass().getSimpleName() + ":" + t.getMessage(); }
            postToD1("SPLIT", action + " → " + res + (log.length() > 0 ? "\n" + log.toString().trim() : ""));
            saveSplitPrefs();
            if ((tok[0].equals("SPLIT") && res.startsWith("분할 OK")) || (tok[0].equals("SPLITRATIO") && res.startsWith("비율 "))) setSplitOwned(true);   // ★724 폰에서 띄운·맞춘 분할은 지킨다
            try {
                if (relay != null) relay.putStatus("remote_split", new org.json.JSONObject()
                        .put("active", SplitLauncher.isSplitActive(this)).put("p1", SplitLauncher.lastPkg1).put("p2", SplitLauncher.lastPkg2)
                        .put("pct", SplitLauncher.lastPct).put("last", res).put("ts", System.currentTimeMillis()).toString());
            } catch (Throwable ignored) {}
        }, "applauncher").start();
        return "실행 중 — 로그 확인";
    }
    private String buildRemoteManifest(Object st, Object bw) throws Exception {
        boolean d5 = DiLink5Support.isDiLink5();
        org.json.JSONArray caps = new org.json.JSONArray();
        String[][] seats = {{"1","운전석","DriverSeat"},{"2","동승석","PassengerSeat"},{"3","2열 좌측","RearLeftSeat"},{"4","2열 우측","RearRightSeat"}};
        for (String[] s : seats) {
            int idx = Integer.parseInt(s[0]);
            // ★열선↔통풍은 배타(2026-09-10 운영자) — 한쪽을 켜면 반대편은 끄기(1)를 함께 보내야 한다(앱 v345 시트 5단 모델과 동일).
            //   exclusive 그룹 + offValue를 실어 패널이 반대편 끄기 명령까지 발사하게 한다.
            if (hasFeat(st, s[2] + "Heating") == 1)
                caps.put(mf("seat_heat_" + idx, "seat", s[1] + " 열선", "🔥", "cycle", "setting", "setSeatHeatingState", ja(idx, "$v")).put("steps", steps(1,"꺼짐",2,"1단",3,"2단")).put("state", "getSeatHeatingState:" + idx).put("exclusive", "seat" + idx).put("offValue", 1));
            if (hasFeat(st, s[2] + "Ventilating") == 1)
                caps.put(mf("seat_vent_" + idx, "seat", s[1] + " 통풍", "💨", "cycle", "setting", "setSeatVentilatingState", ja(idx, "$v")).put("steps", steps(1,"꺼짐",2,"1단",3,"2단")).put("state", "getSeatVentilatingState:" + idx).put("exclusive", "seat" + idx).put("offValue", 1));
        }
        if (hasFeat(st, "SteeringWheelHeatingState") == 1)
            caps.put(mf("steer_heat", "seat", "핸들 열선", "🎡", "cycle", "setting", "setSteeringWheelHeatingState", ja("$v")).put("steps", steps(1,"꺼짐",2,"켜짐")).put("state", "getSteeringWheelHeatingState"));
        if (d5) {
            for (int n = 1; n <= 2; n++)
                caps.put(mf("seat_mem_" + n, "seat", "메모리 " + n, "💺", "memory", "setting", null, null)
                        .put("recall", new org.json.JSONObject().put("method", "resetSeatParams").put("args", ja(n, 1)))
                        .put("save", new org.json.JSONObject().put("method", "saveSeatParamsAll").put("args", ja(n, 1))));   // ★712 메모리시트는 참조(BYDcontroller)와 일치 — 死 아님. 테스터 "안 됨"은 '길게 저장' 먼저 안 한 가능성(복원만 눌렀음). 태그 안 달고 저장먼저 안내(타일 '짧게 복원·길게 저장')로.
        }
        // 창문 — 기능 하나=버튼 하나: 전체 환기 / 전체 닫기(창문만). D3 state 5=breath(환기)·2=닫기 실차확정, D5 cmd 1=열기(환기값 미확인)·2=닫기.
        //   ★2026-09-13 메밀소바 실차: 개별 4개 연속 발사가 씨라 MCU서 랜덤 씹힘 → setAllWindowState(4창 한 번에)로 전환. 릴레이가 실패 시 개별(winFallback) 순차 폴백.
        String winM = d5 ? "setWindowControlState" : "setBodyWindowCtrlState";
        // ★556 D5 환기값 1→5 수정 — 1은 "환기값을 못 찾아서" 넣은 대체값인데 D3에서 1=완전열기다. 즉 씨라7 차주가
        //   "전체 환기"를 누르면 창문이 활짝 열리고 있었다(비·도난 위험). 운영자 제공 씨라7 레퍼런스(비틀런처 실측 문서)에
        //   `bodyMgr.setWindowControlState([5,5,5,5]) = 살짝 열기 5`로 적혀 있어 D3와 같은 5로 통일한다.
        //   5가 D5에서 무효면 거부/무동작으로 끝나 완전열기보다 안전하다. 실차 확인 후 확정할 것.
        int ventV = 5;
        caps.put(mf("win_vent", "window", "전체 환기", "🪟", "act", "bodywork", "setAllWindowState", ja(ventV, ventV, ventV, ventV)).put("winFallback", winM).put("chk", d5));
        caps.put(mf("win_close", "window", "전체 닫기", "🪟", "act", "bodywork", "setAllWindowState", ja(2, 2, 2, 2)).put("winFallback", winM));
        // ★834 FB2223(도규아빠 A3, 운영자 10-09 "창문은 개별 제어로 다 있어도 될 듯") — 전체 다 열기 + 창문별(운전·동승·뒤 왼쪽·뒤 오른쪽) 단계.
        //   D3 setBodyWindowCtrlState(area, 2닫기·5조금(환기)·4반·1다 열기) 실차 확정(카탈로그 §3). D5는 카탈로그의 setWindowOpenPercent(area, %) — 미확인이라 확인 배지.
        caps.put(mf("win_open", "window", "전체 다 열기", "🪟", "act", "bodywork", "setAllWindowState", ja(1, 1, 1, 1)).put("winFallback", winM).put("chk", d5));
        String[] winNames = {"운전석", "동승석", "뒤 왼쪽", "뒤 오른쪽"};
        for (int a = 1; a <= 4; a++) {
            if (d5) caps.put(mf("win_" + a, "window", winNames[a - 1] + " 창문", "🪟", "cycle", "bodywork", "setWindowOpenPercent", ja(a, "$v"))
                    .put("steps", steps(0,"닫힘",20,"조금",50,"반",100,"다 열기")).put("chk", true));
            else caps.put(mf("win_" + a, "window", winNames[a - 1] + " 창문", "🪟", "cycle", "bodywork", "setBodyWindowCtrlState", ja(a, "$v"))
                    .put("steps", steps(2,"닫힘",5,"조금",4,"반",1,"다 열기")));
        }
        if (hasFeat(bw, "MoonRoof") == 1) {
            if (d5) caps.put(mf("shade", "body", "선쉐이드", "☀", "cycle", "bodywork", "voiceCtlSunshadePanel", ja("$v")).put("steps", steps(2,"닫힘",1,"열림")).put("state", "getSunroofWindowblindPosition"));
            // ★D3 선쉐이드는 % 제어(2026-09-10 운영자) — 앱 검증 경로: 열기는 nudgeSunshadeState(pct)(그냥 열기가 씹혀 닫기→열기 우회),
            //   0(닫기)만 setSunshadeState. methodZero로 0일 때 메서드를 바꿔 쓴다. 상태는 릴레이 sunshade_pct.
            //   ★10%씩 ◀▶는 실사용 불가(2026-09-10 운영자 "열리다 닫히다 난리남") — 한 칸마다 명령이 나가 모터가 계속 방향을 바꾼다.
            //   프리셋 3단 순환(닫힘 0 → 반 50 → 전체 100)으로 변경: 탭 1회 = 명령 1회.
            else caps.put(mf("shade", "body", "선쉐이드", "☀", "cycle", "bodywork", "nudgeSunshadeState", ja("$v"))
                    .put("methodZero", "setSunshadeState").put("steps", steps(0,"닫힘",50,"반",100,"전체")).put("state", "sunshade_pct"));
            // ★833 아토3 유리 선루프 시험 버튼(운영자 10-09 "있는 거 다 리모컨에 넣어서 검증") — getMoonRoofConfig 1=선루프 있음(아토3 실측)·2=없음(돌핀).
            //   값 의미 미확정이라 확인 배지. 열림 %(setMoonRoofState)·동작 값 1~4(voiceCtlMoonRoof — 열기·닫기·틸트 후보)·정지. 상태는 원값.
            sunroofCfg = d5 ? 0 : callGetInt0(bw, "getMoonRoofConfig");
            if (!d5 && sunroofCfg == 1) {
                // ★835 비틀런처 1.23(아토3) 확인값(피드백 세션 디컴파일, 운영자 10-09 "썬루프 진행 해") — setMoonRoofState 하나로 0=닫기·1~100=열림%·253=틸트(살짝 들어올림)·254=멈춤.
                //   252(컴포트)는 뜻 불명이라 안 넣는다. 상태는 getSunroofPosition %를 sunroof_pos_v로 그대로.
                caps.put(mf("sunroof", "body", "선루프", "🌤", "cycle", "bodywork", "setMoonRoofState", ja("$v"))
                        .put("steps", steps(0,"닫힘",50,"50%",100,"100%")).put("state", "sunroof_pos_v").put("chk", true));
                caps.put(mf("sunroof_tilt", "body", "선루프 틸트", "🌤", "cycle", "bodywork", "setMoonRoofState", ja("$v"))
                        .put("steps", steps(0,"닫힘",253,"틸트")).put("state", "sunroof_tilt_st").put("chk", true));   // ★10-10 FB2253 틸트↔닫힘 번갈아(253 반복은 변화 없음). 상태는 릴레이가 마지막으로 보낸 값
                if (relay != null && relay.getStatus("sunroof_tilt_st") == null) relay.putStatus("sunroof_tilt_st", 0);   // 운영자 10-09 "틸트는 별개 버튼으로" — 선택 표시 없음
                // 시험용 '선루프 동작(voiceCtlMoonRoof 값1~4)'은 뺌 — 비틀런처 값(setMoonRoofState)이 확인돼 같은 선루프를 움직이는 버튼이 겹쳤다(운영자 10-09 "배타적인 건 알아서")
                caps.put(mf("sunroof_stop", "body", "선루프 멈춤", "⏹", "act", "bodywork", "setMoonRoofState", ja(254)).put("chk", true));   // ★835 비틀런처와 같은 254
            }
        }
        // ★주행 감각 설정(2026-09-10 운영자 요청) — DUMP_METHODS 실차 확정 세터. 값 시맨틱은 미확정이라 chk 배지.
        //   현재값(DUMPDEV 실측): getEnergyFeedback=2 · getSteerAssis=2 · getEPedalMode=2.
        //   상태는 publishLive가 같은 getter로 올리므로, 탭 후 타일이 따라오면 그 단계 매핑이 맞다는 뜻이다.
        //   ★회생제동 값은 자료 정본(byd-기술자료 개발자-레퍼런스): setEnergyFeedback 표준=2 / 강=3. (1 아님)
        caps.put(mf("regen", "drive", "회생제동", "🔋", "cycle", "setting", "setEnergyFeedback", ja("$v"))
                .put("steps", steps(2,"표준",3,"강")).put("state", "getEnergyFeedback"));
        //   ★스티어링 값 — D3(돌핀)=SDK 상수 SET_DR_ST_ASSIS COMFORT=1/SPORT=2 2단. D5(씨라7)=BeetleLauncher 1.534 setSteerAssis 컴포트1/보통2/스포츠3 3단.
        caps.put(mf("steer_mode", "drive", "스티어링", "🎯", "cycle", "setting", "setSteerAssis", ja("$v"))
                .put("steps", d5 ? steps(1,"컴포트",2,"보통",3,"스포츠") : steps(1,"컴포트",2,"스포츠")).put("state", "getSteerAssis"));
        // ★브레이크 감각 — D3(돌핀)=adas.setBrakeFootSenseState 컴포트2/스포츠0(Overdrive v48.7). D5(씨라7)=setting.setBrakePedalMode 컴포트1/스포츠2(BeetleLauncher 1.534).
        //   09-12 씨라 adas 경로 "가짜성공·무동작" 관측 → D5는 setting 경로가 정답(BeetleLauncher로 확정).
        caps.put(mf("brake_mode", "drive", "브레이크", "🛑", "cycle",
                        d5 ? "setting" : "adas",
                        d5 ? "setBrakePedalMode" : "setBrakeFootSenseState", ja("$v"))
                // ★558 D5 라벨 반전 수정 — 씨라7 차주 실차 신고(피드백 id149, 09-22 "컴포트 스포츠가 반대로 나와요").
                //   09-12 메모에 "하이라이트가 차 화면과 반대면 라벨만 교체(값은 고정)"로 예견해둔 케이스가 실제로 확인됐다.
                //   D5는 1=스포츠·2=컴포트다(D3의 setBrakeFootSenseState 2/0과는 별개 체계). 값은 그대로 두고 라벨만 뒤집는다.
                .put("steps", d5 ? steps(1,"스포츠",2,"컴포트") : steps(2,"컴포트",0,"스포츠"))
                .put("state", d5 ? "getBrakePedalMode" : "getBrakeFootSenseState").put("chk", d5));   // ★833 돌핀은 확인 배지 뺌(운영자 10-09)
        // ★E-페달 타일 제거(2026-09-11 운영자 "이페달은 뭘 하는 건지 모르겠고 차량에 그런 이름의 기능은 없다") —
        //   SDK엔 setEPedalMode·SET_E_PEDAL_MODE_STATE_SET·getEPedalMode가 다 있고 baseSet도 OK(0)를 돌려주지만
        //   **돌핀 액티브엔 해당 기능 자체가 없다**. 세터·feature·게터가 다 있어도 차종에 기능이 없으면 없는 것.
        //   (엠비언트와 같은 패턴 — §9.3 "세터 존재 ≠ 기능 존재".) 재조사 금지.
        // ★주행모드(2026-09-10 운영자 "오버드라이브에서 아토3는 자동화로 제어해") — **energy 디바이스의 setOperationMode**.
        //   내가 처음에 "세터 없음"이라 한 건 DUMP_METHODS가 adas/instrument/setting/charging/ac만 훑어 energy를 빠뜨렸기 때문.
        //   ★값 스케일은 **실차 반응으로 확정**(2026-09-11 운영자: "노말 선택하면 에코, 에코 선택하면 스포츠") —
        //   내가 처음 쓴 ENERGY_OPERATION_MODE_*(NORMAL 1/ECO 2/SPORT 3)는 한 칸씩 밀렸다.
        //   맞는 건 접두 없는 ENERGY_OPERATION_*: **ECONOMY=1 · SPORT=2 · NORMAL=3**. (보내기 1→에코, 2→스포츠 실측 일치)
        //   getOperationMode는 이 스케일과 다를 수 있어(브레이크처럼 SET/GET 분리) state 미지정 — 틀린 하이라이트보다 무표시.
        // ★D5(씨라7) 주행모드 set 매핑 미확정 — 실차 "한 칸 밀림" 관측. BeetleLauncher 1.534는 set 미구현(getSportModeState 읽기)이고
        //   energy.getOperationMode/setOperationMode 경로는 프로브조차 안 함(불가 증명 아님). D5DIAG operMode/roadSurf로 스케일 확정될 때까지 타일 임시 제외(확정 후 D5 steps로 복구).
        if (!d5) caps.put(mf("drive_mode", "drive", "주행모드", "🚗", "cycle", "energy", "setOperationMode", ja("$v"))
                .put("steps", steps(3,"노말",1,"에코",2,"스포츠")));
        // ★노면모드(눈/진흙/모래)는 게터만 존재(getRoadSurfaceMode=1) — energy 세터 목록에 setRoadSurfaceMode 없음 → 타일 없음.
        // 공조 — 풍량 D3 실차확정(setAcWindLevel(0,lvl) v128), 전원 값 미확정(chk). 온도 4인자·D3 성에 3인자는 ❓라 제외.
        caps.put(mf("ac_wind", "climate", "풍량", "🌬", "step", "ac", "setAcWindLevel", ja(0, "$v")).put("min", 1).put("max", 7).put("inc", 1).put("unit", "단").put("chk", d5));
        // ★공조 전원(2026-09-10 운영자 "에어컨 확인 안 됨 — 시동 안 끄고 공조만 끄는 게 공조설정에 있잖아") —
        //   릴레이 setAcPower는 실차 미동작. 앱 공조설정이 쓰는 **클라우드 OPENAIR/CLOSEAIR**가 검증된 경로다.
        //   패널이 릴레이 RAW 대신 클라우드를 타도록 cloud 액션으로 표시.
        //   ★클라우드 CLOSEAIR는 원격공조 세션을 끝내 시동까지 꺼진다(2026-09-10 실차) → SDK 경로로 교체.
        //   애프터블로우가 실차 검증한 조합: 컴프레서만 내려 냉방을 끄고 송풍은 유지 = 시동 유지한 채 "에어컨만" on/off.
        // ★씨라(D5)=BydController 2.1.1 명명 메서드(baseFeature가 씨라서 무동작 → 명령 X표 근본, 2026-09-13). 돌핀(D3)=기존 baseFeature 유지.
        if (d5) {
            caps.put(mf("ac_cool", "climate", "에어컨(냉방)", "❄", "cycle", "ac", "setAcCompressorMode", ja(0, "$v")).put("steps", steps(0,"꺼짐",1,"켜짐")).put("state", "ac_cool_st"));
            caps.put(mf("ac_power", "climate", "공조 전원", "🔌", "cycle", "ac", "__ACPOWER__", ja("$v")).put("steps", steps(0,"꺼짐",1,"켜짐")).put("state", "ac_start_st"));
            caps.put(mf("ac_vent", "climate", "송풍 온리", "💨", "cycle", "ac", "setAcVentilationState", ja(0, "$v")).put("steps", steps(0,"꺼짐",1,"켜짐")).put("state", "ac_vent_st"));
        } else {
            caps.put(mf("ac_cool", "climate", "에어컨(냉방)", "❄", "cycle", "ac", null, null)
                    .put("baseFeature", "AC_COMPRESSOR_MODE_SET").put("steps", steps(0,"꺼짐",1,"켜짐")).put("state", "ac_cool_st"));
            caps.put(mf("ac_power", "climate", "공조 전원", "🔌", "cycle", "ac", null, null)
                    .put("baseFeature", "AC_POWER_STATE_SET").put("steps", steps(0,"꺼짐",1,"켜짐")).put("state", "ac_start_st"));
            caps.put(mf("ac_vent", "climate", "송풍 온리", "💨", "cycle", "ac", null, null)
                    .put("baseFeature", "AC_VENTILATION_STATE_SET").put("steps", steps(0,"꺼짐",1,"켜짐")).put("state", "ac_vent_st"));
        }
        // ★온도(2026-09-10 운영자) — 앱 검증 경로 그대로: baseFeature 3종 순차 write, 값은 ℃×2 정수(17.0~26.5). writes 모드.
        // ★634 D5(씨라7)=setAcTemperature(0,℃,0,0) 1℃ 단위 17~33(BeetleLauncher). D3 baseFeature 3종 write는 D5가 무시(FB1157).
        if (d5) caps.put(mf("ac_temp", "climate", "온도", "🌡", "step", "ac", "setAcTemperature", ja(0, "$v", 0, 0)).put("min", 17).put("max", 33).put("inc", 1).put("unit", "°C"));
        else caps.put(mf("ac_temp", "climate", "온도", "🌡", "step", "ac", null, null)
                .put("writes", new org.json.JSONArray()
                        .put(new org.json.JSONObject().put("baseFeature", "AC_CTRL_SOURCE_SET").put("value", 1))
                        .put(new org.json.JSONObject().put("baseFeature", "AC_TEMP_MAIN_SET").put("value", "$v2"))
                        .put(new org.json.JSONObject().put("baseFeature", "AC_TEMP_DEPUTY_SET").put("value", "$v2")))
                .put("min", 17).put("max", 26.5).put("inc", 0.5).put("unit", "°C"));
        // ★내/외기 — D5=setAcCycleMode(0,v) 명명(BydController 2.1.1), D3=baseSet AC_CYCLE_MODE_SET. SDK 1=내기순환 / 0=외기유입.
        if (d5) caps.put(mf("ac_cycle", "climate", "내/외기", "🔄", "cycle", "ac", "setAcCycleMode", ja(0, "$v")).put("steps", steps(0,"외기",1,"내기")).put("state", "ac_cycle"));
        else caps.put(mf("ac_cycle", "climate", "내/외기", "🔄", "cycle", "ac", null, null)
                .put("baseFeature", "AC_CYCLE_MODE_SET").put("steps", steps(0,"외기",1,"내기")));
        // ★오토(앱 검증): setAcControlMode(0,0)=자동 풍량. 수동 복귀는 풍량 타일(setAcWindLevel)이 담당.
        caps.put(mf("ac_auto", "climate", "오토 풍량", "🅰", "act", "ac", "setAcControlMode", ja(0, 0)));
        // ★뒷유리·미러 열선 D5 — BydController: setElectricDefrostState, set은 사용자 켜기1→차2·끄기0→차1 변환(execRaw 처리). steps는 set 변환값(1 꺼짐/2 켜짐).
        if (d5) {
            // ★617 비틀런처 실차확인(열선-성에-API-정리.md: "한 회로") — ac.setAcDefrostState(0, 2, 1/0). 기존 setElectricDefrostState(BydController 참조)는 미검증이라 교체.
            caps.put(mf("defog_rear", "climate", "뒷유리·미러 열선", "♨", "cycle", "ac", "setAcDefrostState", ja(0, 2, "$v")).put("steps", steps(0,"꺼짐",1,"켜짐")));
            // ★앞유리 성에 D5 — BydController setAcDefrostState(0, 1, v). 0=끄기 1=켜기.
            caps.put(mf("defog_front", "climate", "앞유리 성에", "🌬", "cycle", "ac", "setAcDefrostState", ja(0, 1, "$v")).put("steps", steps(0,"꺼짐",1,"켜짐")));
        }
        // ★D3 뒷유리·사이드미러 열선(2026-09-10 운영자 "못 켜?") — 전용 메서드 인자 미확정이라 baseSet 경로로 시도(다른 AC baseFeature는 실차 동작 확정).
        //   미러 열선은 뒷유리 성에에 통합되는 것이 일반적(설계 §9). 실차 확인 전이라 '확인' 배지.
        //   ★2026-09-10 운영자 실차 확인 — 뒷유리·미러 열선 동작 성공 → chk 해제. 같은 경로로 앞유리 성에도 추가.
        else {
            caps.put(mf("defog_rear", "climate", "뒷유리·미러 열선", "♨", "cycle", "ac", null, null)
                    .put("baseFeature", "AC_DEFROST_REAR_STATE_SET").put("steps", steps(0,"꺼짐",1,"켜짐")));
            caps.put(mf("defog_front", "climate", "앞유리 성에", "🌬", "cycle", "ac", null, null)
                    .put("baseFeature", "AC_DEFROST_FRONT_STATE_SET").put("steps", steps(0,"꺼짐",1,"켜짐")));
        }
        // 라이트 — DRL(끄기=2 D3 실차, 켜기=1 D5 APK), D5 헤드라이트 모드 1=OFF 2=AUTO(주차중만), D5 라이트 계열(비틀런처 확정, 값은 chk).
        caps.put(mf("drl", "light", "DRL", "🔆", "cycle", "light", "setDayTimeLightState", ja("$v")).put("steps", steps(2,"꺼짐",1,"켜짐")).put("state", "getDayTimeLightState"));
        // ★D3에도 있는데 D5 전용으로 막아뒀던 것들(2026-09-11 전 디바이스 세터 덤프로 확정) —
        //   `setIALBrightness`·`setSmartWelcomeLightState`는 **light가 아니라 setting 디바이스** 소속이다.
        //   (돌핀 light 디바이스의 세터는 setDayTimeLightState 하나뿐. light로 보내던 기존 D5 항목도 그래서 의심스럽다.)
        //   엠비언트 온/오프 = 밝기로 처리(켜기 6 / 끄기 1, 자료 §4 실차확정) → 1~6 한 타일로 겸한다.
        // ★D3 엠비언트·웰컴라이트 타일 제거(2026-09-11 운영자 "돌핀은 엠비언트 없어") — 세터(setIALBrightness/
        //   setSmartWelcomeLightState)는 SDK에 있지만 **돌핀엔 그 조명 하드웨어가 없다**. 세터 존재 ≠ 기능 존재.
        //   씨라이언(D5)용 자료를 보고 D3에 열었던 것이 오류. D5 블록은 그대로 둔다.
        if (d5) {
            caps.put(mf("headlight", "light", "헤드라이트", "🔦", "cycle", "light", "setHeadlightControlMode", ja("$v")).put("steps", steps(1,"OFF",2,"AUTO")).put("state", "getHeadlightControlMode").put("requires", ja("parked")));
            // ★617 비틀런처 실차(result-20260909-150: "엠비언트(2844)를 이 방법으로 살렸다") — light feature 2844 = 별고리 밝기 1=꺼짐…6=최대(켜고 끄기도 밝기로).
            // ★635 D5 feature write(set(int[],EV))는 NoSuchFieldError intArrayValue로 죽는다(1715 실측) → 명명 메서드 setIALBrightness(area3,v,src0)(씨라7 14건 성공 기록).
            caps.put(mf("amb_bright", "light", "엠비언트 밝기", "🌈", "step", "light", "setIALBrightness", ja(3, "$v", 0)).put("min", 1).put("max", 6).put("inc", 1).put("unit", "").put("chk", true));
            // ★617 "문 열면 실내등" — setting.setInsideLightDoorState 1=꺼짐·2=켜짐(비틀런처 실차 덤프·Spec). 실내등 본체 스위치는 비틀런처도 미발견.
            caps.put(mf("inside_door_light", "light", "문 열면 실내등", "💡", "cycle", "setting", "setInsideLightDoorState", ja("$v")).put("steps", steps(1,"꺼짐",2,"켜짐")).put("state", "getInsideLightDoorState").put("chk", true).put("na", d5));   // ★712 씨라7 반응없음(테스터 #7)
            // ★635 1인자 호출은 씨라7에서 NoSuchMethod(실측) — 카탈로그 §4: (area, v) OPEN 1 / CLOSE 2. area는 엠비언트 밝기와 같은 3(미실측, chk).
            caps.put(mf("amb_dyn", "light", "엠비언트 다이내믹", "🎨", "cycle", "light", "setAmbientMulticolorState", ja(3, "$v")).put("steps", steps(2,"꺼짐",1,"켜짐")).put("chk", true));
            caps.put(mf("amb_music", "light", "리듬 엠비언트", "🎵", "cycle", "light", "setAmbientMusicModeState", ja(3, "$v")).put("steps", steps(2,"꺼짐",1,"켜짐")).put("chk", true));
            // ★635 실내등(bodywork feature 1715) 제거 — D5 feature write는 NoSuchFieldError(실측), 실내등 명명 메서드는 비틀런처도 미발견.
            caps.put(mf("door_light", "light", "도어등", "🚪", "cycle", "light", "setDoorLightState", ja("$v")).put("steps", steps(0,"꺼짐",1,"켜짐")).put("chk", true).put("na", d5));   // ★712 씨라7 반응없음(테스터 #7)
            caps.put(mf("cargo_light", "light", "트렁크등", "🧳", "cycle", "light", "setCargoLightSwitchState", ja("$v")).put("steps", steps(0,"꺼짐",1,"켜짐")).put("chk", true).put("na", d5));   // ★712 씨라7 반응없음(테스터 #7)
            // ★635 웰컴라이트는 light엔 없음(NoSuchMethod 실측) — setting 소속(SDK 덤프)
            caps.put(mf("welcome", "light", "웰컴라이트", "✨", "cycle", "setting", "setSmartWelcomeLightState", ja("$v")).put("steps", steps(0,"꺼짐",1,"켜짐")).put("chk", true));
            caps.put(mf("rear_fog", "light", "뒷안개등", "🌫", "cycle", "instrument", "setRearFogLightState", ja("$v")).put("steps", steps(0,"꺼짐",1,"켜짐")).put("chk", true).put("na", d5));   // ★712 씨라7 반응없음(테스터 #7)
        }
        // ★트렁크(2026-09-11 운영자) — 전동 테일게이트(아토3 이상 전 차종)는 SDK 기능 감지로: setting.voiceCtlBackDoor 열기1/닫기3(카탈로그 §9 실차확정).
        //   없으면(돌핀) 클라우드 OPENTRUNK만(닫기는 수동). 앱=cloud 필드로 자기 클라우드 경로, 웹=/cmd TRUNK(릴레이 SETCLOUDSESS 세션).
        int tgFeat = hasFeat(st, "BackDoorElectricMode");
        int tgOnline = -9; try { tgOnline = devInt(st, "getBackDoorElectricModeOnlineState"); } catch (Throwable ignored) {}   // 2차 신호(테스터 로그로 감지키 확정용)
        postToD1("CAPS", "테일게이트 감지 hasFeat(BackDoorElectricMode)=" + tgFeat + " onlineState=" + tgOnline);
        if (tgFeat == 1 || tgOnline == 1) {
            caps.put(mf("tailgate_open", "body", "테일게이트 열기", "🚪", "act", "setting", "voiceCtlBackDoor", ja(1)).put("requires", ja("parked")).put("chk", true));
            caps.put(mf("tailgate_close", "body", "테일게이트 닫기", "🚪", "act", "setting", "voiceCtlBackDoor", ja(3)).put("requires", ja("parked")).put("chk", true));
        } else if (d5) {
            // ★씨라이언7 트렁크 = 로컬 setting.voiceCtlBackDoor(1 열기/3 닫기) — BydController 2.1.1·Overdrive 둘 다 검증(2026-09-13).
            //   기존 클라우드 OPENTRUNK/CLOSETRUNK는 왕복 느림 → 로컬 SDK로 교체(테일게이트 보유 확정, hasFeat 0이어도 있음).
            caps.put(mf("tailgate_open", "body", "테일게이트 열기", "🚪", "act", "setting", "voiceCtlBackDoor", ja(1)).put("requires", ja("parked")));
            caps.put(mf("tailgate_close", "body", "테일게이트 닫기", "🚪", "act", "setting", "voiceCtlBackDoor", ja(3)).put("requires", ja("parked")));
        } else {
            caps.put(mf("trunk_open", "body", "트렁크 열기", "🧳", "act", "cloud", null, null).put("cloud", "TRUNK_OPEN").put("requires", ja("parked")));
        }
        remoteManifestCount = caps.length();
        return new org.json.JSONObject().put("v", 1).put("model", DiLink5Support.vehicleType()).put("dilink", d5 ? 5 : 3).put("caps", caps).toString();
    }
    private org.json.JSONObject mf(String id, String cat, String label, String icon, String type, String device, String method, org.json.JSONArray args) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject().put("id", id).put("cat", cat).put("label", label).put("icon", icon).put("type", type).put("action", "RAW").put("device", device);
        if (method != null) o.put("method", method);
        if (args != null) o.put("args", args);
        return o;
    }
    private org.json.JSONArray ja(Object... a) { org.json.JSONArray r = new org.json.JSONArray(); for (Object x : a) r.put(x); return r; }
    private org.json.JSONArray steps(Object... vl) throws Exception {   // (v,label,v,label,…)
        org.json.JSONArray r = new org.json.JSONArray();
        for (int i = 0; i + 1 < vl.length; i += 2) r.put(new org.json.JSONObject().put("v", vl[i]).put("l", vl[i + 1]));
        return r;
    }
    private org.json.JSONArray multi4(int state) { org.json.JSONArray r = new org.json.JSONArray(); for (int a = 1; a <= 4; a++) r.put(ja(a, state)); return r; }

    private String dumpAc() {
        Object ac = deviceByName("android.hardware.bydauto.ac.BYDAutoAcDevice");
        StringBuilder g = new StringBuilder();
        if (ac != null) {
            for (java.lang.reflect.Method m : ac.getClass().getMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                String n = m.getName();
                if (!(n.startsWith("get") || n.startsWith("is"))) continue;
                Class<?> rt = m.getReturnType();
                if (rt == Integer.TYPE || rt == Long.TYPE || rt == Double.TYPE || rt == Float.TYPE || rt == Boolean.TYPE) {
                    try { Object v = m.invoke(ac); g.append(n).append("=").append(v).append(" | "); } catch (Throwable ignored) {}
                }
            }
        }
        postToD1("PROBE", "AC_GETTERS: " + (g.length() == 0 ? "(ac null or getter 없음)" : g.toString()));
        // idToName에서 애프터블로우/증발기건조 관련 feature 이름 스캔
        StringBuilder feat = new StringBuilder();
        for (java.util.Map.Entry<Integer, String> e : idToName.entrySet()) {
            String nm = e.getValue(); if (nm == null) continue;
            String u = nm.toUpperCase();
            if (u.contains("AFTER") || u.contains("_DRY") || u.contains("EVAPOR") || u.contains("BLOW")
                    || u.contains("CLEAN") || u.contains("STERIL") || u.contains("PURIF") || u.contains("POST")) {
                if (feat.length() < 1400) feat.append(nm).append("=").append(e.getKey()).append(" | ");
            }
        }
        postToD1("PROBE", "AC_AFTERBLOW후보: " + (feat.length() == 0 ? "(없음)" : feat.toString()));
        return "ok";
    }

    // ★애프터블로우 조용화: light device getter + LIGHT/LAMP/DRL 관련 feature 스캔.
    //   시동 켜진 상태에서 밖에 보이는 등(DRL·미등·헤드램프)을 끌 수 있는지 발견용.
    private String dumpLight() {
        Object lt = deviceByName("android.hardware.bydauto.light.BYDAutoLightDevice");
        StringBuilder g = new StringBuilder();
        if (lt != null) {
            for (java.lang.reflect.Method m : lt.getClass().getMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                String n = m.getName();
                if (!(n.startsWith("get") || n.startsWith("is"))) continue;
                Class<?> rt = m.getReturnType();
                if (rt == Integer.TYPE || rt == Long.TYPE || rt == Boolean.TYPE) {
                    try { Object v = m.invoke(lt); g.append(n).append("=").append(v).append(" | "); } catch (Throwable ignored) {}
                }
            }
            // set 메서드 이름도 (뭘 끌 수 있나)
            StringBuilder setters = new StringBuilder();
            for (java.lang.reflect.Method m : lt.getClass().getMethods()) {
                if (m.getName().startsWith("set")) setters.append(m.getName()).append("(").append(m.getParameterTypes().length).append(") ");
            }
            postToD1("PROBE", "LIGHT_SETTERS: " + setters);
        }
        postToD1("PROBE", "LIGHT_GETTERS: " + (g.length() == 0 ? "(light null or getter 없음)" : g.toString()));
        StringBuilder feat = new StringBuilder();
        for (java.util.Map.Entry<Integer, String> e : idToName.entrySet()) {
            String nm = e.getValue(); if (nm == null) continue;
            String u = nm.toUpperCase();
            if (u.contains("LIGHT") || u.contains("LAMP") || u.contains("DRL") || u.contains("POSITION")
                    || u.contains("DAYTIME") || u.contains("HEAD") || u.contains("동주행") || u.contains("미등")) {
                if (feat.length() < 1400) feat.append(nm).append("=").append(e.getKey()).append(" | ");
            }
        }
        postToD1("PROBE", "LIGHT_FEATURES: " + (feat.length() == 0 ? "(없음)" : feat.toString()));
        return "ok";
    }


    // ★v77 featureId 이름 검색 — BYDAutoFeatureIds 전체에서 키워드를 포함한 항목을 찾는다.
    //   착좌·재실 감지처럼 있는지조차 모르는 센서를 발견하는 용도. 결과가 길면 나눠서 보고.
    void featScan(String keywords) {
        String[] kws = keywords.toUpperCase().split(",");
        StringBuilder sb = new StringBuilder();
        int hit = 0;
        for (java.util.Map.Entry<Integer, String> e : idToName.entrySet()) {
            String nm = e.getValue();
            if (nm == null) continue;
            String u = nm.toUpperCase();
            boolean match = false;
            for (String k : kws) { if (!k.trim().isEmpty() && u.contains(k.trim())) { match = true; break; } }
            if (!match) continue;
            hit++;
            sb.append(nm).append("=").append(e.getKey()).append(" | ");
            if (sb.length() > 1400) { postToD1("FEAT", sb.toString()); sb.setLength(0); }
        }
        postToD1("FEAT", "검색 [" + keywords + "] " + hit + "건" + (sb.length() > 0 ? "\n" + sb : "")
                + (hit == 0 ? " — 해당 이름 없음(맵 " + idToName.size() + "개)" : ""));
    }

    // ★v77 디바이스의 무인자 getter 전부 호출해 현재값 덤프 — 어떤 값이 실제로 읽히는지 확인용.
    void dumpDev(String cls) {
        Object dev = deviceByName(cls);
        if (dev == null) { postToD1("FEAT", cls + " → null (없거나 접근 불가)"); return; }
        StringBuilder sb = new StringBuilder();
        for (java.lang.reflect.Method m : dev.getClass().getMethods()) {
            if (m.getParameterTypes().length != 0) continue;
            String n = m.getName();
            if (!(n.startsWith("get") || n.startsWith("is"))) continue;
            Class<?> rt = m.getReturnType();
            if (rt != Integer.TYPE && rt != Long.TYPE && rt != Boolean.TYPE && rt != Float.TYPE && rt != Double.TYPE) continue;
            try { sb.append(n).append("=").append(m.invoke(dev)).append(" | "); } catch (Throwable ignored) {}
            if (sb.length() > 1400) { postToD1("FEAT", sb.toString()); sb.setLength(0); }
        }
        postToD1("FEAT", cls.substring(cls.lastIndexOf('.') + 1) + ": " + (sb.length() == 0 ? "(읽히는 값 없음)" : sb.toString()));
    }

    // ★v74 범용 셸 실행 — 진단용. dadb 인증키는 이미 헤드유닛 adb_keys에 등록돼 팝업 없이 붙는다.
    void shellCmd(String cmd) {
        try {
            java.io.File pk = new java.io.File(getFilesDir(), "adbkey");
            java.io.File pub = new java.io.File(getFilesDir(), "adbkey.pub");
            if (!pk.exists() || !pub.exists()) { postToD1("SH", "adb 키 없음 — ADB_AUTH 먼저"); return; }
            dadb.AdbKeyPair kp = dadb.AdbKeyPair.Companion.read(pk, pub);
            dadb.Dadb d = dadb.Dadb.Companion.create("127.0.0.1", 5555, kp, 10000, 60000);
            dadb.AdbShellResponse res = d.shell(cmd);
            d.close();
            String out = res.getOutput();
            int ec = -999; try { ec = res.getExitCode(); } catch (Throwable ignored) {}
            String body = out.trim();
            // ★v161 진단 — exit code + raw 길이 표기(무거운 명령 빈결과 원인 추적)
            postToD1("SH", "$ " + cmd + "\n[exit=" + ec + " rawlen=" + out.length() + "]\n" + (body.isEmpty() ? "(빈 결과)" : body.substring(0, Math.min(body.length(), 3500))));
        } catch (Throwable t) {
            postToD1("SH", "$ " + cmd + "\n셸 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage() + (t.getCause() != null ? " / cause=" + t.getCause() : ""));
        }
    }

    // ★v123 원격 self-update — Java로 최신 APK 다운로드 → dadb push로 /data/local/tmp(셸 소유) 이관 →
    //   워치독과 동일한 분리셸(setsid nohup)로 pm install -r + am start. 설치 중 앱이 죽어도 셸이 완주하고,
    //   am start(Activity)는 수동 실행이라 autostart-blocker(부팅 자동시작 차단)도 우회한다. curl·파일권한 문제 없음.
    // ★2026-09-19 직전 self-update install 결과 D1 업로드 — 설치 셸이 selfupd_last.txt(외부, shell 쓰기·앱 읽기)에 남긴 install1/install2 결과를
    //   부팅 후 앵커 루프에서 1회 읽어 올린다. 재시작 성공은 새 프로세스가, 재시작 실패는 다음 부팅/수동재시작 때 올라온다. 파일은 읽고 삭제.
    private void uploadSelfUpdResult() {
        try {
            java.io.File rf = new java.io.File(getExternalFilesDir(null), "selfupd_last.txt");
            if (!rf.exists()) return;
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(rf))) {
                String line; while ((line = r.readLine()) != null) sb.append(line).append(' ');
            }
            String s = sb.toString().trim();
            if (!s.isEmpty()) postToD1("SELFUPD", "직전 설치결과: " + (s.length() > 300 ? s.substring(0, 300) : s));
            rf.delete();
        } catch (Throwable ignored) {}
    }

    void selfUpdate() {
        try {
            postToD1("SELFUPD", "OTA APK 다운로드 시작");
            java.io.File apk = Updater.fetchLatestApk(this);
            if (apk == null || apk.length() < 500000) {
                postToD1("SELFUPD", "다운로드/검증 실패 (len=" + (apk == null ? "null" : apk.length()) + ")"); return;
            }
            java.io.File pk = new java.io.File(getFilesDir(), "adbkey");
            java.io.File pub = new java.io.File(getFilesDir(), "adbkey.pub");
            if (!pk.exists() || !pub.exists()) { postToD1("SELFUPD", "adb 키 없음 — ADB_AUTH 먼저"); return; }
            dadb.AdbKeyPair kp = dadb.AdbKeyPair.Companion.read(pk, pub);
            dadb.Dadb d = dadb.Dadb.Companion.create("127.0.0.1", 5555, kp, 10000, 120000);
            String pkg = getPackageName();
            String remote = "/data/local/tmp/byd_update.apk";
            // ★551 adb push 제거 — Updater.silentInstallInner와 같은 근거(씨라7에서 25MB adb 전송이 느려 무음 설치가 성립하지 않았다).
            //   앱이 받아둔 APK를 셸이 기기 안에서 복사한다. 실패하면 설치를 중단하고 사유를 남긴다.
            String src = "/sdcard/Android/data/" + pkg + "/files/" + apk.getName();
            postToD1("SELFUPD", "다운로드 완료 " + apk.length() + "B → 기기 내 복사");
            if (d.shell("cp " + src + " " + remote).getExitCode() != 0) {
                postToD1("SELFUPD", "APK 기기 내 복사 실패(" + src + ") — 설치 중단");
                d.close(); return;
            }
            // 분리 설치 스크립트 — 설치가 앱을 죽여도 셸 프로세스(setsid)라 pm install·am start 완주.
            // ★2026-09-19 성공률<50% 견고화 — 기존 결과무관 sleep 2 후 무조건 am start를 pm install "Success" 확인 후에만 재시작 +
            //   실패 시 1회 재시도로 교체(레이스로 구버전 유지·다이얼로그 hang 방지). 결과를 외부파일(RF, shell 쓰기·앱 읽기 가능)에
            //   남겨 다음 부팅에 릴레이가 D1로 올림(uploadSelfUpdResult) — 재시작 실패 케이스도 원인이 A1에 도달.
            String rf = "/sdcard/Android/data/" + pkg + "/files/selfupd_last.txt";
            String script = "#!/system/bin/sh\n"
                    + "LOG=/data/local/tmp/byd_selfupd.log\n"
                    + "RF=" + rf + "\n"
                    + "echo \"=== selfupd $(date) ===\" >> $LOG\n"
                    + "sleep 1\n"
                    + "R=$(pm install -r " + remote + " 2>&1)\n"
                    + "echo \"install1: $R\" >> $LOG\n"
                    + "if echo \"$R\" | grep -qi Success; then\n"
                    + "  echo \"$(date) OK install1: $R\" > $RF 2>/dev/null\n"
                    + "  sleep 1\n"
                    + "  echo \"start: $(am start -n " + pkg + "/.MainActivity --es src selfupd -f 0x10000000 2>&1)\" >> $LOG\n"
                    + "else\n"
                    + "  echo \"install1 실패 → 3s 후 재시도\" >> $LOG\n"
                    + "  sleep 3\n"
                    + "  R2=$(pm install -r " + remote + " 2>&1)\n"
                    + "  echo \"install2: $R2\" >> $LOG\n"
                    + "  if echo \"$R2\" | grep -qi Success; then\n"
                    + "    echo \"$(date) OK(재시도) install2: $R2\" > $RF 2>/dev/null\n"
                    + "    echo \"start2: $(am start -n " + pkg + "/.MainActivity --es src selfupd -f 0x10000000 2>&1)\" >> $LOG\n"
                    + "  else\n"
                    + "    echo \"$(date) FAIL install1=$R || install2=$R2\" > $RF 2>/dev/null\n"
                    + "    echo \"install 최종실패 — 재시작 안함(구버전 유지, 다음 부팅때 재시도)\" >> $LOG\n"
                    + "  fi\n"
                    + "fi\n"
                    + "rm -f " + remote + "\n";
            String b64 = android.util.Base64.encodeToString(script.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            d.shell("echo " + b64 + " | base64 -d > /data/local/tmp/byd_selfupd.sh && chmod 755 /data/local/tmp/byd_selfupd.sh");
            d.shell("setsid nohup sh /data/local/tmp/byd_selfupd.sh </dev/null >/dev/null 2>&1 &");
            postToD1("SELFUPD", "✅ 설치 데몬 실행 — 몇 초 후 새 버전으로 재시작(로그 byd_selfupd.log)");
            d.close();
        } catch (Throwable t) {
            postToD1("SELFUPD", "self-update 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    // ★v162 임의 APK 원격설치 — selfUpdate 머신 재사용. rest="<url> [grantpkg]". 앱코드 다운로드(byd-ota 도달)+dadb push+pm install.
    void installApk(String rest) {
        try {
            String[] parts = rest.trim().split("\\s+");
            String url = parts[0];
            String grantPkg = parts.length > 1 ? parts[1] : null;
            postToD1("INSTALLAPK", "다운로드: " + url);
            java.io.File apk = new java.io.File(getExternalFilesDir(null), "install.apk");
            if (apk.exists()) apk.delete();
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setInstanceFollowRedirects(true); c.setConnectTimeout(10000); c.setReadTimeout(90000);
            java.io.InputStream in = c.getInputStream();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(apk);
            byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close(); in.close();
            if (apk.length() < 100000) { postToD1("INSTALLAPK", "다운로드 실패 len=" + apk.length()); return; }
            postToD1("INSTALLAPK", "다운로드 완료 " + apk.length() + "B → push+install");
            java.io.File pk = new java.io.File(getFilesDir(), "adbkey");
            java.io.File pub = new java.io.File(getFilesDir(), "adbkey.pub");
            if (!pk.exists() || !pub.exists()) { postToD1("INSTALLAPK", "adb 키 없음 — ADB_AUTH 먼저"); return; }
            dadb.AdbKeyPair kp = dadb.AdbKeyPair.Companion.read(pk, pub);
            dadb.Dadb d = dadb.Dadb.Companion.create("127.0.0.1", 5555, kp, 10000, 120000);
            String remote = "/data/local/tmp/byd_install.apk";
            d.push(apk, remote, 420, System.currentTimeMillis());
            String grant = (grantPkg != null) ? "echo \"grant: $(pm grant " + grantPkg + " android.permission.WRITE_SECURE_SETTINGS 2>&1)\" >> $LOG\n" : "";
            String script = "#!/system/bin/sh\nLOG=/data/local/tmp/byd_install.log\n"
                    + "echo \"=== install $(date) ===\" >> $LOG\n"
                    + "sleep 1\n"
                    + "echo \"install: $(pm install -r -g " + remote + " 2>&1)\" >> $LOG\n"
                    + "sleep 1\n"
                    + grant
                    + "rm -f " + remote + "\n";
            String b64 = android.util.Base64.encodeToString(script.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            d.shell("echo " + b64 + " | base64 -d > /data/local/tmp/byd_install.sh && chmod 755 /data/local/tmp/byd_install.sh");
            d.shell("setsid nohup sh /data/local/tmp/byd_install.sh </dev/null >/dev/null 2>&1 &");
            postToD1("INSTALLAPK", "✅ 설치 데몬 실행 — 로그 byd_install.log");
            d.close();
        } catch (Throwable t) {
            postToD1("INSTALLAPK", "실패: " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    // 실제 dadb 인증+shell — 시작 시 1회 + ADB_AUTH 명령으로 수동 재시도(팝업 여유있게 허용용).
    //   ★v56: socketTimeout 60초(팝업 승인 대기). 성공하면 shell 권한 → app_process 데몬 길.
    void dadbTry() {
        try {
            java.io.File pk = new java.io.File(getFilesDir(), "adbkey");
            java.io.File pub = new java.io.File(getFilesDir(), "adbkey.pub");
            if (!pk.exists() || !pub.exists()) {
                dadb.AdbKeyPair.Companion.generate(pk, pub);
                postToD1("ADBSHELL", "RSA 키 생성 완료 (" + pk.getName() + ")");
            }
            dadb.AdbKeyPair kp = dadb.AdbKeyPair.Companion.read(pk, pub);
            postToD1("ADBSHELL", "dadb 연결 시도 127.0.0.1:5555 — 헤드유닛 'USB 디버깅 허용?' 팝업 뜨면 [항상 허용] (60초 대기)");
            dadb.Dadb d = dadb.Dadb.Companion.create("127.0.0.1", 5555, kp, 10000, 60000);
            dadb.AdbShellResponse r = d.shell("id");
            postToD1("ADBSHELL", "✅ shell 성공! exit=" + r.getExitCode() + " id=" + r.getOutput().trim());
            try { dadb.AdbShellResponse r2 = d.shell("getprop service.adb.tcp.port"); postToD1("ADBSHELL", "adb.tcp.port=" + r2.getOutput().trim()); } catch (Throwable ignored) {}
            try { dadb.AdbShellResponse r3 = d.shell("dumpsys package " + getPackageName() + " | grep -i stopped"); postToD1("ADBSHELL", "stopped상태: " + r3.getOutput().trim()); } catch (Throwable ignored) {}
            installWatchdog(d);   // ★v57: shell 권한으로 watchdog 데몬 설치 — 원격끔 force-stop 시 앱 부활(shell 프로세스라 면역)
            d.close();
        } catch (Throwable t) {
            Throwable c = t.getCause();
            postToD1("ADBSHELL", "dadb 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage()
                    + (c != null ? " cause=" + c.getClass().getSimpleName() + ":" + c.getMessage() : "")
                    + " (팝업 미허용 or adbd 인증거부 — ADB_AUTH 재시도 가능)");
        }
    }

    // ── ★v53 로컬 adbd 진단 — app_process 워치독(원격끔 force-stop 면역)의 전제조건 검증 ──
    //   overdrive/diplus는 dadb로 127.0.0.1:5555 로컬 adbd에 RSA로 붙어 앱 밖 native 데몬 실행(네트워크 무관).
    //   여기선 소켓 연결 + adb CNXN handshake로 ① adbd tcp 개방 여부 ② 인증상태(CNXN=인증됨/AUTH=RSA필요) 확인.
    //   되면 → dadb 이식해 shell 권한 확보 → app_process 데몬 가능. 안 되면 → 백업안(공조켜기 재부팅).
    private void probeLocalAdb() {
        new Thread(() -> {
            try { Thread.sleep(8000); } catch (InterruptedException e) { return; }
            int[] ports = {5555, 5556, 5557, 5037, 4444};
            boolean anyOpen = false;
            for (int port : ports) {
                java.net.Socket s = null;
                try {
                    s = new java.net.Socket();
                    s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1500);
                    anyOpen = true;
                    // adb CNXN 패킷 전송 → adbd면 CNXN(인증됨) 또는 AUTH(RSA 인증필요) 응답
                    byte[] payload = "host:: ".getBytes("UTF-8");
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(24 + payload.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    int cmd = 0x4e584e43;   // "CNXN"
                    int crc = 0; for (byte b : payload) crc += (b & 0xff);
                    bb.putInt(cmd); bb.putInt(0x01000001); bb.putInt(256 * 1024);
                    bb.putInt(payload.length); bb.putInt(crc); bb.putInt(~cmd); bb.put(payload);
                    s.getOutputStream().write(bb.array()); s.getOutputStream().flush();
                    s.setSoTimeout(2500);
                    byte[] buf = new byte[24];
                    int n = s.getInputStream().read(buf);
                    if (n >= 4) {
                        String c = new String(buf, 0, 4, "US-ASCII");
                        postToD1("ADBPROBE", "127.0.0.1:" + port + " → adb 응답 " + n + "바이트 cmd=" + c
                                + (c.equals("AUTH") ? " (adbd 살아있음·RSA 인증필요)" : c.equals("CNXN") ? " (adbd 인증됨·바로사용가능!)" : " (미상)"));
                    } else {
                        postToD1("ADBPROBE", "127.0.0.1:" + port + " 연결됨(listen 중)이나 adb 응답 없음(" + n + "바이트) — 다른 서비스일 수");
                    }
                } catch (java.net.SocketTimeoutException te) {
                    postToD1("ADBPROBE", "127.0.0.1:" + port + " 연결됨 but 응답 타임아웃 (adbd 아닐 수)");
                } catch (java.net.ConnectException ce) {
                    /* 포트 안 열림 — 조용히 스킵 */
                } catch (Throwable t) {
                    postToD1("ADBPROBE", "127.0.0.1:" + port + " 오류: " + t.getClass().getSimpleName());
                } finally { if (s != null) try { s.close(); } catch (Throwable ignored) {} }
            }
            if (!anyOpen) postToD1("ADBPROBE", "로컬 adb 포트(5555등) 전부 닫힘 — adbd tcp 미개방. WRITE_SECURE로 service.adb.tcp.port 설정 시도 필요할 수");
        }, "adb-probe").start();
    }


    // ── ★v57 watchdog 데몬 — shell(adbd) 하위 프로세스라 앱 패키지 force-stop과 별개로 살아남아,
    //   원격끔으로 우리 앱이 죽으면 am start로 8초 내 부활. app_process 원격끔 정복의 핵심.
    //   base64로 스크립트 전송(따옴표 이스케이프 안전) → setsid로 세션 분리 실행(adb 연결 닫혀도 유지).
    private void installWatchdog(dadb.Dadb d) {
        try {
            String pkg = getPackageName();
            // ★v58: 지난 watchdog 동작 로그 회수 — 이전 원격끔 때 watchdog이 am을 시도했는지·결과가 뭔지 확인
            try {
                dadb.AdbShellResponse pl = d.shell("tail -25 /data/local/tmp/byd_wd.log 2>/dev/null");
                String log = pl.getOutput().trim();
                if (!log.isEmpty()) postToD1("WATCHDOG", "지난 로그:\n" + log);
            } catch (Throwable ignored) {}
            // ★v58: watchdog·앱 프로세스 UID 확인 (watchdog이 shell UID로 도는지, 앱과 별개인지)
            try {
                dadb.AdbShellResponse ps = d.shell("ps -A -o USER,PID,NAME 2>/dev/null | grep -iE 'byd_wd|bydsniffer|sh$' | grep -v grep | head -6");
                postToD1("WATCHDOG", "프로세스:\n" + ps.getOutput().trim());
            } catch (Throwable ignored) {}
            // 재부팅 후에도 로컬 adbd tcp 유지 시도
            try { d.shell("setprop persist.adb.tcp.port 5555"); postToD1("ADBSHELL", "persist.adb.tcp.port=5555 설정"); } catch (Throwable ignored) {}
            // ★v332 watchdog 스크립트 — 폴링 1초로 단축(구 5초). DiLink가 ACC OFF 종료 시퀀스(~1분) 동안
            //   반복 force-stop하면 am start가 즉시 다시 죽는데, 매초 재발사해 DiLink가 놓는 순간 ≤1초 부활.
            // ★v59: force-stopped 앱은 service start로는 안 살아나고 Activity start로만 FLAG_STOPPED 해제됨.
            //   → am start Activity(MainActivity, src=watchdog)로 부활. MainActivity가 서비스만 켜고 즉시 사라짐.
            // ★v511(2026-09-15, Overdrive 이식 — 빠졌던 조각): 이 스크립트는 setsid+nohup으로 adb 연결과 독립 실행되는
            //   shell UID 프로세스라 adbd가 죽어도(reparent) 살아있다. 그런데 지금까지 우리 "앱"만 감시하고 adbd 자체는
            //   안 봤음 — 씨라7 원격(시동종료 후) 카메라가 sentry wake로 헤드유닛은 살아도 adbd만 별도로 죽어 실패하던 근본.
            //   여기서 adbd 헬스체크(init.svc.adbd)를 추가해 죽어있으면 ctl.restart로 되살린다(shell UID면 대개 허용됨).
            String script = "#!/system/bin/sh\n"
                    + "LOG=/data/local/tmp/byd_wd.log\n"
                    + "echo \"=== wd start $(date) pid=$$ ===\" >> $LOG\n"
                    + "(while true; do\n"   // ★adbd 헬스체크 — 별도 백그라운드 루프(5초 주기), 앱 부활 루프의 1초 타이밍은 그대로 유지
                    + "  ASVC=$(getprop init.svc.adbd)\n"
                    + "  if [ \"$ASVC\" != \"running\" ]; then\n"
                    + "    echo \"$(date) adbd DOWN(init.svc.adbd=$ASVC) → ctl.restart 시도\" >> $LOG\n"
                    + "    setprop ctl.restart adbd 2>>$LOG\n"
                    + "    sleep 1\n"
                    + "    echo \"  → 재시도 후 init.svc.adbd=$(getprop init.svc.adbd)\" >> $LOG\n"
                    + "  fi\n"
                    + "  sleep 5\n"
                    + "done) &\n"
                    // ★583(운영자 09-25 "이런 경우를 대비한 백업이 있어야하는거 아냐") — pidof만으론 "프로세스는 살아있는데
                    //   멈춘 경우"(186902 16시간+ 실사고: 자동업뎃 다이얼로그가 install 스레드에서 무한대기 추정)를 못 잡는다.
                    //   앱이 매 60초 HB 루프에서 갱신하는 /data/local/tmp/byd_hb 나이를 같이 본다 — 4분(HB 4틱) 넘게
                    //   안 갱신되면 프로세스가 떠 있어도 멈춘 것으로 보고 강제 재시작. HB 파일이 아직 없으면(신규설치
                    //   직후·구버전) 얌전히 무시 — 오탐 금지.
                    + "HB=/data/local/tmp/byd_hb\n"
                    // ★660 오판 방지 — 감시 루프 자신이 멈췄다 깬(기기 절전 추정) 직후엔 앱 심박(uptime 기준 60초 루프)이 아직 갱신 전이라
                    //   멀쩡한 앱을 "심박멈춤"으로 죽였다(3일 137건 중 241초+ 나이가 대부분, 정상이면 240~245초에 잡혀야 함). 루프 간격이 10초를 넘으면
                    //   120초간 심박멈춤 판정을 유예하고 그 사실을 로그에 남긴다. 프로세스없음 판정은 그대로.
                    + "PREV=0\n"
                    + "GRACE=0\n"
                    + "while true; do\n"
                    + "  NOW=$(date +%s)\n"
                    + "  if [ \"$PREV\" -gt 0 ] && [ $((NOW - PREV)) -gt 10 ]; then\n"
                    + "    GRACE=$((NOW + 120))\n"
                    + "    echo \"$(date) 감시 루프 정지 $((NOW - PREV))초 감지 → 심박멈춤 판정 120초 유예\" >> $LOG\n"
                    + "  fi\n"
                    + "  KICK=\"\"\n"
                    + "  if ! pidof " + pkg + " >/dev/null 2>&1; then\n"
                    + "    KICK=\"프로세스없음\"\n"
                    + "  elif [ -f \"$HB\" ]; then\n"
                    + "    HBT=$(cat \"$HB\" 2>/dev/null)\n"
                    // ★583 방어 — HBT가 순수 숫자가 아니면(쓰기 도중 인터럽트 등) 무시. dash류 sh는 비숫자 산술이
                    //   스크립트 전체를 즉시 종료시킨다(실측: "Illegal number" exit 2) — 검증 없이 쓰면 워치독 자체가
                    //   영구히 죽어 "고치려던 문제보다 나쁜 상태"가 된다. 반드시 case 패턴으로 걸러야 한다.
                    + "    case \"$HBT\" in ''|*[!0-9]*) HBT=\"\" ;; esac\n"
                    + "    if [ -n \"$HBT\" ]; then\n"
                    + "      AGE=$(( NOW - HBT ))\n"
                    + "      if [ \"$AGE\" -gt 240 ]; then\n"
                    + "        if [ \"$NOW\" -ge \"$GRACE\" ]; then KICK=\"심박멈춤(${AGE}s)\"; fi\n"
                    + "      fi\n"
                    + "    fi\n"
                    + "  fi\n"
                    + "  if [ -n \"$KICK\" ]; then\n"
                    + "    echo \"$(date) $KICK → force-stop + Activity start\" >> $LOG\n"
                    // ★683 프로세스가 이미 없으면 force-stop 생략 — 할 일이 없는 강제 종료가 롬의 오토스타트 재금지를 부추긴다(186902 10-01). 멈춘(심박멈춤) 경우만 강제 종료.
                    // ★750 씨라7은 683 전처럼 늘 강제 종료 뒤 연다(공유판 737과 같게) — 683을 공유판에 옮길 때도 돌핀만 넣었다(720). 10-02 701·10-05 744~746 씨라7 시스템 재시작 용의.
                    + (IS_D5 ? "    am force-stop " + pkg + " 2>>$LOG\n"
                             : "    case \"$KICK\" in 프로세스없음) ;; *) am force-stop " + pkg + " 2>>$LOG ;; esac\n")
                    + "    rm -f $HB\n"
                    + "    sleep 1\n"
                    + "    echo \"  act: $(am start -n " + pkg + "/.MainActivity --es src watchdog -f 0x10000000 2>&1)\" >> $LOG\n"
                    + "    sleep 9\n"   // ★재시작 유예 — 새 프로세스가 첫 HB를 쓸 시간을 줘 즉시 재킥(로그 스팸) 방지
                    + "  fi\n"
                    + "  PREV=$(date +%s)\n"
                    + "  sleep 1\n"
                    + "done\n";
            String b64 = android.util.Base64.encodeToString(script.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            d.shell("pkill -f byd_wd.sh 2>/dev/null; sleep 1");   // 기존 종료
            dadb.AdbShellResponse w = d.shell("echo " + b64 + " | base64 -d > /data/local/tmp/byd_wd.sh && chmod 755 /data/local/tmp/byd_wd.sh && echo OK");
            postToD1("ADBSHELL", "watchdog 스크립트 작성: " + w.getOutput().trim());
            // setsid + nohup 이중으로 세션 완전 분리 (adb 연결 닫혀도·앱 죽어도 유지)
            d.shell("setsid nohup sh /data/local/tmp/byd_wd.sh </dev/null >/dev/null 2>&1 &");
            dadb.AdbShellResponse chk = d.shell("sleep 1; pgrep -f byd_wd.sh 2>/dev/null | head -2 | tr '\\n' ' '");
            postToD1("ADBSHELL", "✅ watchdog 데몬 실행 pid=[" + chk.getOutput().trim() + "] — 원격끔 시 1초 주기 부활 시도(자가로깅)");
        } catch (Throwable t) {
            postToD1("ADBSHELL", "watchdog 설치 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }



    private String dumpPowerMethods() {
        Object p = deviceByName("power");
        if (p == null) return "power null";
        StringBuilder sb = new StringBuilder();
        for (java.lang.reflect.Method m : p.getClass().getMethods()) {
            String n = m.getName().toLowerCase();
            if (n.contains("wake") || n.contains("mcu") || n.contains("acc") || n.contains("keep") || n.contains("sleep") || n.contains("power") || n.contains("alive")) {
                sb.append(m.getName()).append("(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) sb.append(i > 0 ? "," : "").append(ps[i].getSimpleName());
                sb.append(")->").append(m.getReturnType().getSimpleName()).append(" ");
            }
        }
        postToD1("PROBE", "POWER_METHODS: " + (sb.length() == 0 ? "(없음)" : sb.toString()));
        return "ok";
    }

    // power device 전 필드 덤프 — wakeUpMcu가 참조하는 Context가 어느 필드(정적 포함)에 있고 null인지 찾는다.
    private String dumpPowerFields() {
        Object p = deviceByName("power");
        if (p == null) return "power null";
        StringBuilder sb = new StringBuilder();
        Class<?> c = p.getClass();
        int depth = 0;
        while (c != null && c != Object.class && depth < 6) {
            sb.append("[").append(c.getName()).append("] ");
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                boolean isCtx = Context.class.isAssignableFrom(f.getType());
                boolean isStatic = java.lang.reflect.Modifier.isStatic(f.getModifiers());
                if (isCtx || f.getType().getSimpleName().contains("Context")) {
                    Object v = null; try { f.setAccessible(true); v = f.get(p); } catch (Throwable t) {}
                    sb.append(f.getName()).append(isStatic ? "(static)" : "").append(":").append(f.getType().getSimpleName())
                      .append("=").append(v == null ? "NULL" : v.getClass().getSimpleName()).append(" ");
                }
            }
            c = c.getSuperclass(); depth++;
        }
        postToD1("PROBE", "POWER_CTX_FIELDS: " + (sb.length() == 0 ? "(Context 필드 없음 — wakeUpMcu가 getBaseContext/외부참조)" : sb.toString()));
        return "ok";
    }

    // 애프터블로우: 냉방 끄고 송풍전용(풍량·순환 선택) → N분 후 afterblow_done 이벤트 → 폰이 공조끄기(CLOSEAIR)로 마무리.
    //   실차 확정: COMPRESSOR_MANUAL_SIGN=0 + VENTILATION_STATE=1 + WIND_LEVEL_SET → WORK_MODE가 A/C(2)→ONLY(1)로 전환.
    //   VENTILATION=0(완전정지)은 안 먹혀 종료는 풍량1로 낮춤 + 폰 CLOSEAIR(push 유실 시 클라우드 세션만료가 백스톱).
    //   온도는 절대 안 건드림(건드리면 AUTO+A/C로 튐).
    // mirror_fold 파일 현재값(-1=모름/0=OFF/1=ON). a11y(:relay)가 씀, 메인이 읽음(같은 getFilesDir).
    private int readMirrorFoldFile() {
        try {
            java.io.File mf = new java.io.File(getFilesDir(), "mirror_fold");
            if (!mf.exists()) return -1;
            byte[] mb = new byte[8]; java.io.FileInputStream in = new java.io.FileInputStream(mf); int mn = in.read(mb); in.close();
            if (mn <= 0) return -1;
            String ms = new String(mb, 0, mn, "UTF-8").trim();
            return ms.equals("0") ? 0 : ms.equals("1") ? 1 : -1;
        } catch (Throwable t) { return -1; }
    }

    // a11y 심박 나이(ms). 클수록 죽음. 파일 없으면 MAX. a11y가 파일에 쓴 타임스탬프로 계산(setLastModified no-op 회피).
    private long a11yAliveAge() {
        try {
            java.io.File hb = new java.io.File(getFilesDir(), "a11y_alive");
            if (!hb.exists()) return Long.MAX_VALUE;
            byte[] b = new byte[32]; java.io.FileInputStream in = new java.io.FileInputStream(hb); int n = in.read(b); in.close();
            if (n > 0) { try { return System.currentTimeMillis() - Long.parseLong(new String(b, 0, n, "UTF-8").trim()); } catch (Exception e) {} }
            return System.currentTimeMillis() - hb.lastModified();
        } catch (Throwable t) { return Long.MAX_VALUE; }
    }

    // ★애프터블로우 전체 시퀀스를 릴레이가 순서보장 실행 — 앱 백그라운드/포그라운드 무관.
    //   ① a11y 생존 게이트 → ② 미러OFF 완료 확정(hard, 실패 시 OPENAIR 취소=미러 펴짐 방지) → ③ 클라우드 OPENAIR(시동ON) → ④ 송풍전환(미러ON 복원 포함).
    //   재점화 가드: 진행중 재호출이면 OPENAIR 스킵하고 송풍만 재적용(시동ON 전이 반복=미러 펴짐 방지).
    private String afterBlowSeq(org.json.JSONObject p) {
        final double celsius = (p != null) ? p.optDouble("celsius", 22) : 22;
        final int minutes = (p != null) ? p.optInt("minutes", 10) : 10;
        int c = (p != null) ? p.optInt("cycle", 1) : 1;
        final int cycle = (c == 1 || c == 2) ? c : 1;   // ★OPENAIR cycleMode는 1/2만 유효 — 0(안바꿈)이 클라우드로 새는 것 방지.
        final org.json.JSONObject fp = p;
        final boolean useMirror = (p == null) || p.optBoolean("mirror", true);   // ★자동폴딩 연동(디폴트 ON). OFF면 미러 단계 생략 → 폴딩 실패해도 애프터블로우는 실행.
        // ★재점화 가드 — 진행중 재호출이면 OPENAIR 스킵, 송풍만(시동ON 전이 반복=미러 펴짐 방지).
        if (System.currentTimeMillis() < afterblowUntil) {
            postToD1("RELAY", "AFTERBLOWSEQ 재호출 — 이미 진행중(재점화 금지) → 송풍만 재적용");
            return execAfterBlow(p);
        }
        // ★#1 진입 즉시 provisional 가드 — 미러폴+OPENAIR 윈도우 동안 중복 AFTERBLOWSEQ 차단. a11y 대기(최대90s) 포함해 넉넉히 3분.
        afterblowUntil = System.currentTimeMillis() + 180000L;
        // ★#3 command 스레드를 20초~2분 붙잡으면 릴레이 폴링/하트비트가 멈춰 앱이 오프라인으로 오판 → 워커 재디스패치(중복). 별도 스레드로 수행하고 즉시 리턴.
        afterblowSeqThread = new Thread(() -> {
            try {
                boolean doMirror = useMirror;   // ★a11y 장애 시 런타임에 false로 강등 — 미러만 포기하고 애프터블로우(증발기 건조)는 진행.
                if (doMirror) {
                // ① a11y 생존 게이트 — 죽어있으면 미러OFF가 안 되므로 OPENAIR 금지(미러 펴짐).
                //   ★애프터블로우는 시동OFF(=릴레이재시작·헬퍼재설치) 직후 발동돼 a11y가 아직 재바인드 전일 수 있음 → 즉시중단 말고 최대 90s 대기(헬퍼 재설치 재바인드 ~75s).
                long age = bestA11yAge();   // 헬퍼·릴레이 a11y 중 살아있는 쪽(실행은 dispatchA11y가 헬퍼 우선)
                for (int w = 0; w < 45 && age > 8000; w++) {
                    if (w == 0) postToD1("RELAY", "AFTERBLOWSEQ — a11y 재바인드 대기 중(심박 " + (age == Long.MAX_VALUE ? "없음" : (age / 1000) + "s") + ")");
                    try { Thread.sleep(2000); } catch (InterruptedException e) { afterblowUntil = 0; return; }
                    age = bestA11yAge();
                }
                if (age > 8000) { postToD1("RELAY", "⚠️ AFTERBLOWSEQ — a11y 90s 대기 후에도 없음 → 미러 단계 생략하고 애프터블로우 진행(미러 펴짐 감수, 증발기 건조 우선)"); doMirror = false; }
                else {
                    postToD1("RELAY", "AFTERBLOWSEQ 시작 (a11y 심박 " + (age / 1000) + "s) → ①미러 자동폴딩 OFF 요청");
                    // ② 미러OFF → mirror_fold=0 확정까지 최대 20초. 안 되면 미러만 생략(애프터블로우는 진행).
                    dispatchA11y("mirrorfold", "0");
                    boolean off = false;
                    for (int i = 0; i < 20; i++) { try { Thread.sleep(1000); } catch (InterruptedException e) { afterblowUntil = 0; return; } if (readMirrorFoldFile() == 0) { off = true; break; } }
                    if (!off) { postToD1("RELAY", "⚠️ 미러OFF 미확정(mirror_fold≠0) → 미러 단계 생략하고 애프터블로우 진행(미러 펴짐 감수)"); doMirror = false; }
                    else {
                        postToD1("RELAY", "②미러 OFF 확정 → 클라우드 OPENAIR(시동ON)");
                        // ★미러 OFF 확정 즉시 복원 예약 — 이후 OPENAIR실패/수동시동끄기/스레드인터럽트로 중단돼도 자동폴딩 OFF 영구방치 방지(실차: 밤새 OFF).
                        //   그레이스 60초 후 시동OFF(ps==0) 확정 시 checkMirrorRestore가 복원. 중단돼 시동 안 켜졌으면 그레이스 뒤 바로 복원.
                        if (prefs != null) {
                        boolean okc = prefs.edit().putBoolean("mirror_restore_pending", true)
                                .putLong("mirror_restore_after", System.currentTimeMillis() + 60000L).commit();   // ★commit(동기 디스크) — 직후 시동OFF→onTaskRemoved 사망 전 확실 저장(apply는 유실 가능)
                        postToD1("RELAY", "미러 복원 예약 설정 commit=" + okc + " readback=" + prefs.getBoolean("mirror_restore_pending", false));   // ★관측(추론→관측): 예약 실제 저장 확인
                    }
                    }
                }
                } else {
                    postToD1("RELAY", "AFTERBLOWSEQ — 자동폴딩 연동 OFF(사용자 설정) → 미러 단계 생략, 바로 OPENAIR(시동ON)");
                }
                // ③ 클라우드 OPENAIR — 릴레이 직접(cloudPin 보유). 풍량 자동(null). 실패 시 토큰갱신 1회 재시도.
                // ★세션 공유 — iOS 토큰(SETCLOUDSESS/restoreCloudFromPrefs)만 사용. 릴레이 자체 재로그인은 iOS 세션을 킥하므로 절대 안 함.
                if (cloud == null) restoreCloudFromPrefs();
                if (cloud == null) { postToD1("RELAY", "❌ AFTERBLOWSEQ 중단 — 클라우드 세션 없음(iOS SETCLOUDSESS 필요)"); afterblowUntil = 0; if (relay != null) relay.postCarEvent("afterblow_abort"); return; }
                if (cloudVin == null || cloudVin.isEmpty()) { postToD1("RELAY", "❌ AFTERBLOWSEQ 중단 — vin 없음"); afterblowUntil = 0; if (relay != null) relay.postCarEvent("afterblow_abort"); return; }
                // ★OPENAIR 직전 프리셋 억제 창 설정 — 시동ON(AC_POWER 0→1)이 부트온 프리셋(선쉐이드 열기)을 발동하지 않게(미러접힘·선쉐이드닫힘 유지).
                if (relay != null) relay.setAfterblowUntil(System.currentTimeMillis() + minutes * 60000L + 300000L);
                com.cseini.bydsniffer.cloud.BydCloud.Result r = cloud.openAir(cloudVin, celsius, minutes, cycle, null, cloudPin == null ? "" : cloudPin);
                if (r == null || !r.success) {
                    // 재로그인(iOS 킥) 금지 → 같은 토큰으로 1회 재시도(1008 등 일시 오류 대비).
                    postToD1("CLOUD", "OPENAIR 실패(" + (r != null ? r.error : "null") + ") → 같은 세션 재시도");
                    try { Thread.sleep(2000); } catch (InterruptedException e) { afterblowUntil = 0; return; }
                    r = cloud.openAir(cloudVin, celsius, minutes, cycle, null, cloudPin == null ? "" : cloudPin);
                }
                if (r == null || !r.success) { postToD1("RELAY", "❌ OPENAIR 실패: " + (r != null ? r.error : "null") + " → 애프터블로우 취소(iOS 세션 갱신 필요)"); afterblowUntil = 0; if (relay != null) { relay.setAfterblowUntil(0); relay.postCarEvent("afterblow_abort"); } return; }
                afterblowUntil = System.currentTimeMillis() + minutes * 60000L + 300000L;   // ★OPENAIR 성공 → 정식 가드 연장(iOS 아닌 릴레이 소유)
                if (relay != null) relay.postCarEvent("afterblow_start");   // ★실제 AC 켜짐 → 앱이 이때 "애프터블로우중" 플래그ON + 시작 알림(낙관적 enqueue 아님)
                postToD1("RELAY", "③OPENAIR 성공(시동ON) → ④애프터블로우 송풍전환 진입");
                // ④ 송풍전환(컴프레서OFF·순환·풍량). execAfterBlow는 자체 백그라운드 스레드 사용.
                execAfterBlow(fp);
                // ★미러ON 복원 예약 — 시동ON 중 복원하면 미러 펴짐. 애프터블로우 종료(시동OFF) 후 앵커루프 checkMirrorRestore가 복원.
                //   플래그(prefs)라 릴레이가 시동OFF로 죽었다 부활해도 유지 → 부활 후 a11y 살면 복원.
                if (doMirror) postToD1("RELAY", "④송풍전환 진행 — 미러 복원 예약은 ②미러OFF 시점에 설정됨(시동OFF+그레이스 후 복원)");
            } catch (Throwable t) { postToD1("RELAY", "AFTERBLOWSEQ 예외: " + t); afterblowUntil = 0; }
        }, "afterblowseq");
        afterblowSeqThread.start();
        return "AFTERBLOWSEQ 시작(백그라운드 시퀀스)";
    }

    private String execAfterBlow(org.json.JSONObject params) {
        try {
            // ★미러 자동폴딩 OFF는 iOS가 OPENAIR(시동ON) 전에 완료. ON 복원은 안정화(송풍전환=시동성공) 후.
            //   iOS가 애프터블로우 진행중 OPENAIR 재점화를 금지하므로 시동ON 전이가 1회뿐 → 토글ON 복원해도 미러 안 펴짐(자동폴딩은 전이에만 반응).
            int mins = (params != null) ? params.optInt("minutes", 30) : 30;
            int wind = (params != null) ? params.optInt("wind", 7) : 7;      // 1~7단
            int cycle = (params != null) ? params.optInt("cycle", 0) : 0;    // 1=외기유입 2=내기순환 0=안 바꿈
            if (wind < 1) wind = 1; if (wind > 7) wind = 7;
            Object ac = deviceByName("android.hardware.bydauto.ac.BYDAutoAcDevice");
            if (ac == null) return "afterblow: ac null";
            // ★v88 실측 교정(2026-07-30 실차 덤프) —
            //   ①순환 로컬 스케일 = 0=외기/1=내기 (클라우드 1=외기/2=내기와 다름. 동시각 대조: cloud cycleChoice=2 ↔ local ac_cycle=1)
            //   ②순환은 유효 쓰기 시 HVAC 재계산이 일어나 직전 수동설정(송풍·풍량)을 리셋 → 반드시 "먼저" 쓰고 그 뒤 송풍 조합.
            //   ③컴프레서 끄기 진짜 쓰기 = AC_COMPRESSOR_MODE_SET (MANUAL_SIGN은 상태 feature여서 안 먹혔음 — "에어컨 안 꺼짐" 원인)
            String r1 = baseSet(ac, featureIdByName("AC_CTRL_SOURCE_SET"), 1);
            // ★v92 송풍 먼저 확립(순환 없이) — 순환은 HVAC 재계산으로 컴프레서 끄기를 방해하므로 '맨 마지막'에 한 번만.
            String r3 = baseSet(ac, featureIdByName("AC_VENTILATION_STATE"), 1);
            String r4 = baseSet(ac, featureIdByName("AC_VENTILATION_STATE_SET"), 1);
            // ★v128 풍량은 공조설정과 동일하게 setAcWindLevel(0,level) 메서드로 — 기존 baseSet(AC_WIND_LEVEL_SET)+AC_WIND_MODE_SET=0 경로는 실차에서 풍량이 안 바뀜(자동모드로 무시된 것으로 추정). 메서드 실패 시에만 baseSet 폴백.
            String r5 = "메서드";
            String r6;
            try { ac.getClass().getMethod("setAcWindLevel", int.class, int.class).invoke(ac, 0, wind); r6 = "OK(setAcWindLevel " + wind + ")"; }
            catch (Throwable t) { r6 = baseSet(ac, featureIdByName("AC_WIND_LEVEL_SET"), wind) + "(폴백:" + t + ")"; }
            String r2 = baseSet(ac, featureIdByName("AC_COMPRESSOR_MODE_SET"), 0);
            String r2b = baseSet(ac, featureIdByName("AC_COMPRESSOR_MANUAL_SIGN"), 0);
            postToD1("RELAY", "애프터블로우 송풍" + wind + "단 시작 (" + mins + "분 후 종료→폰 CLOSEAIR) src=" + r1 + " comp=" + r2 + "/" + r2b + " vent=" + r3 + "/" + r4 + " windMode=" + r5 + " wind=" + r6 + " → 컴프레서OFF 확정 재시도 시작");
            if (prefs != null) prefs.edit().putLong("afterblow_end", System.currentTimeMillis() + mins * 60000L).apply();   // ★v372 송풍 종료 예정 시각 — 이후엔 시동ON(재탑승)이어도 미러 자동폴딩 복원 허용(checkMirrorRestore)
            // ★v92 컴프레서 끄기 재시도 — OPENAIR(원격공조) 재점화 직후 수 분간 A/C를 강제로 붙잡아 compOff가 안 먹는 것(2026-07-30 11:32~39 실측)
            //   해법: work_mode(구독 이벤트)가 1(송풍)이 될 때까지 4초 간격으로 comp-off 재전송, 최대 90초. 성공 즉시 순환 적용+검증 로그.
            final int expWind = wind, fCycle = cycle;
            // ★v102 재실행 시 이전 재시도 스레드 취소 — 메인 타이머(v87)만 중복방지 되고 이 스레드는 누적되던 버그.
            //   누적되면 옛 스레드가 각자 comp-off·순환을 다른 파라미터로 재전송해 설정이 뒤섞임.
            Thread prevComp = afterblowCompThread;
            if (prevComp != null && prevComp.isAlive()) { prevComp.interrupt(); postToD1("RELAY", "애프터블로우 이전 컴프레서OFF 재시도 취소(재실행)"); }
            afterblowCompThread = new Thread(() -> {
                boolean ok = false;
                for (int i = 0; i < 40; i++) {   // 4s × 40 ≈ 160s — 재점화(OPENAIR) 후 헤드유닛 부팅+A/C강제 해제까지 커버
                    try { Thread.sleep(4000); } catch (InterruptedException e) { return; }
                    Integer wm = (relay != null) ? relay.acWorkMode() : null;
                    if (wm != null && wm == 1) { ok = true;
                        postToD1("RELAY", "컴프레서OFF 확정(work_mode=1, " + (i * 4) + "s) → 송풍 전환 성공");
                        // ★미러ON 복원은 여기서 안 함 — 시동ON 중 자동폴딩 ON 복원하면 미러 펴짐(4조합 실차확정 2026-08-18).
                        //   복원은 afterBlowSeq가 애프터블로우 종료(시동OFF) 후 checkMirrorRestore로 수행.
                        break; }
                    Object acV = deviceByName("android.hardware.bydauto.ac.BYDAutoAcDevice");
                    if (acV != null) {   // 아직 A/C → 다시 끄기 시도
                        acSet(acV, "AC_COMPRESSOR_MODE_SET", 0);
                        acSet(acV, "AC_COMPRESSOR_MANUAL_SIGN", 0);
                        acSet(acV, "AC_VENTILATION_STATE", 1);
                    }
                    if (i % 5 == 4) postToD1("RELAY", "컴프레서OFF 재시도 " + (i * 4) + "s (work_mode=" + wm + ", 아직 A/C)");
                }
                // 순환은 컴프레서 끈 뒤 마지막에 한 번만(HVAC 재계산이 이제 송풍을 안 깨뜨림). 0=안 바꿈.
                if (fCycle == 1 || fCycle == 2) {
                    Object acV = deviceByName("android.hardware.bydauto.ac.BYDAutoAcDevice");
                    if (acV != null) {
                        int localCycle = (fCycle == 2) ? 1 : 0;   // 클라우드(1=외기/2=내기) → 로컬(0=외기/1=내기)
                        String rcc = acSet(acV, "AC_CYCLE_MODE_SET", localCycle);
                        acSet(acV, "AC_COMPRESSOR_MODE_SET", 0);   // 순환이 컴프레서를 다시 깨울 수 있어 한 번 더 보정
                        postToD1("RELAY", "애프터블로우 순환 적용 local" + localCycle + "=" + rcc + " (컴프레서 확정 후)");
                    }
                }
                if (!ok) postToD1("RELAY", "⚠️ 컴프레서OFF 160s 내 미확정 — work_mode 여전히 A/C(OPENAIR 강제 지속 의심)");
                // ★송풍 전환·순환 쓰기가 HVAC 재계산으로 풍량을 1단으로 리셋 → 0.6s 후 사용자 설정 풍량 재적용(2026-08-13 실차 확인: 7단 설정이 1단으로 실행되던 버그).
                try { Thread.sleep(600); } catch (InterruptedException e) { return; }
                Object acW = deviceByName("android.hardware.bydauto.ac.BYDAutoAcDevice");
                if (acW != null) {
                    try { acW.getClass().getMethod("setAcWindLevel", int.class, int.class).invoke(acW, 0, expWind);
                        postToD1("RELAY", "애프터블로우 풍량 재적용 " + expWind + "단 (송풍전환·순환 후 리셋 보정)"); }
                    catch (Throwable ignore) {}
                }
            }, "afterblow-compoff");
            afterblowCompThread.start();
            final int m = mins;
            // ★v87 중복 방지 — 연타/재실행 시 이전 종료 타이머 취소(하나만 산다). 없으면 타이머가 쌓여 각각 1단·done을 쏨.
            Thread prev = afterblowThread;
            if (prev != null && prev.isAlive()) { prev.interrupt(); postToD1("RELAY", "애프터블로우 이전 타이머 취소(재실행)"); }
            afterblowThread = new Thread(() -> {
                // ★v90: 종료를 세션만료 75초 '전'에 발화 — 이탈 자동수행은 OPENAIR timeSpan=N분과 같이 돌므로,
                //   N분 그대로 자면 세션만료→헤드유닛 전원다운이 먼저 와서 타이머가 발화 못 함(2026-07-30 00:56 실측 레이스).
                long sleepMs = Math.max(60_000L, m * 60_000L - 75_000L);
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) { return; }
                try {
                    // ★v87 주행 가드 — 즉시버튼 테스트 후 주행을 시작했으면 종료동작(1단·공조끄기)이 운전을 방해 → 스킵.
                    // ★v102 강화 — 캐시(lastGearVal)는 기어폴 시작 전(9s)이나 getter 동결 시 미덥지 못함(감사 지적 레이스).
                    //   발화 시점에 기어를 직접 읽고(실패 시 캐시 폴백) 게이트웨이 속도까지 교차 — 둘 중 하나라도 주행이면 스킵.
                    int gearNow = devInt(deviceByName("gearbox"), "getCurrentGear");   // ★552 D5 getGear 폴백 금지 — 인코딩 미검증(D5는 아래 속도 교차판정으로 넘어감)
                    if (gearNow == Integer.MIN_VALUE) gearNow = lastGearVal;
                    double spNow = devDbl(deviceByName("speed"), "getCurrentSpeed");   // ★v293 getCurrentSpeed(실값)
                    boolean drivingNow = (gearNow != Integer.MIN_VALUE && gearNow != 3)
                            || (!Double.isNaN(spNow) && spNow > 1.0);
                    if (drivingNow) {
                        postToD1("RELAY", "애프터블로우 " + m + "분 완료 — 주행 중(gear=" + gearNow + " 속도=" + spNow + ") → 종료동작 스킵");
                        return;
                    }
                    Object ac2 = deviceByName("android.hardware.bydauto.ac.BYDAutoAcDevice");
                    if (ac2 != null) baseSet(ac2, featureIdByName("AC_WIND_LEVEL_SET"), 1);
                    postToD1("RELAY", "애프터블로우 " + m + "분 완료 — 송풍1단 + afterblow_done 발사(폰이 공조끄기)");
                    if (relay != null) relay.postCarEvent("afterblow_done");   // → 워커 push → 폰 CLOSEAIR
                } catch (Throwable ignored) {}
            }, "afterblow");
            afterblowThread.start();
            return null;   // null=성공 (v87: "ok" 반환이 dispatch에서 에러로 오기록되던 것 수정)
        } catch (Throwable t) { return "afterblow 실패:" + t; }
    }

    // ★v94 헤드유닛 iBeacon advertise — 차 안에 고정된 헤드유닛을 ESP32 대체 비콘으로. 폰이 iBeacon 리전으로 감지(백그라운드 네이티브 웨이크).
    //   UUID는 폰 ESP32 리전과 동일(4d181ce0…) → 앱 변경 없이 mode3 ESP32 경로가 그대로 잡음.
    private static final java.util.UUID BEACON_UUID = java.util.UUID.fromString("4d181ce0-0f04-4fcc-8c24-75f4ca8cbe91");
    // ★v104 차량 고유 UUID — VIN에서 UUIDv5 유도(폰 SharedStore.vehicleBeaconUUID와 동일 공식·네임스페이스).
    //   폰·릴레이가 각자 같은 VIN으로 같은 값을 계산 → 전달 없이 자동 일치, 공유 사용자 간 충돌 없음. VIN 미설정 시 구 공용 UUID.
    private static final byte[] UUID_NAMESPACE = {
            (byte)0xe4,(byte)0xb2,(byte)0xb7,(byte)0xa0,0x3f,0x54,0x4b,0x0e,
            (byte)0x9c,0x1a,0x6d,0x1b,(byte)0x8a,0x2f,0x4c,0x33 };
    private java.util.UUID vehicleBeaconUuid() {
        try {
            String vin = (prefs != null) ? prefs.getString(KEY_VIN, "") : "";
            if (vin.isEmpty()) return BEACON_UUID;
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            md.update(UUID_NAMESPACE);
            md.update(("BYD-AUTODOOR:" + vin.toUpperCase()).getBytes("UTF-8"));
            byte[] b = md.digest();
            b[6] = (byte)((b[6] & 0x0F) | 0x50);   // version 5
            b[8] = (byte)((b[8] & 0x3F) | 0x80);   // RFC4122 variant
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b, 0, 16);
            return new java.util.UUID(bb.getLong(), bb.getLong());
        } catch (Exception e) { return BEACON_UUID; }
    }
    private android.bluetooth.le.BluetoothLeAdvertiser bleAdvertiser;
    private android.bluetooth.le.AdvertiseCallback bleAdvCallback;
    private volatile boolean bleAdvWanted = true;    // ★v94 상시화 — 켜두면 부팅/부활/감시에서 자동 재개(끄려면 BLE_ADV_OFF)
    private volatile boolean bleAdvActive = false;   // 콜백 기반 실제 송출 상태
    private volatile int bleAdvMinorSent = -1;       // ★v97 마지막 송출 minor(상태 비트) — 변화 감지용
    private volatile int bleAdvMajorSent = -1;       // ★마지막 송출 major(이벤트) — 변화 감지용
    // ★비콘 이벤트 즉시 재광고(RelayClient.postBeaconEvent가 호출) — 30s 워치독 대기 없이 major 갱신.
    void nudgeBeacon() { if (bleAdvWanted) { try { startBeaconAdvertise(); } catch (Throwable ignored) {} } }
    // ★621 관측 — D5 강제종료 뒤 옛 프로세스 광고(문 열림 비트) 잔존 가설(079312 id984: 재기동 뒤 relay_state 닫힘인데 폰 비콘 bit0=열림).
    //   이 프로세스의 첫 광고 결과를 relay_state adv_first로(링버퍼 아님). fail2(TOO_MANY_ADVERTISERS)면 잔존 광고가 슬롯을 잡고 있다는 강한 신호.
    private volatile boolean advFirstNoted = false;
    private void noteFirstAdv(String r) {
        if (advFirstNoted) return; advFirstNoted = true;
        if (relay != null) relay.putStatus("adv_first", r + " @" + System.currentTimeMillis());
    }
    // ★628 FB1098 — 워치독·nudgeBeacon·블투복구·기어폴이 서로 다른 스레드에서 동시에 부르면 둘 다 같은 옛 콜백만 끄고 각자 새 콜백을 켜
    //   하나가 고아로 남아 옛 minor를 계속 송출했다(087704 15:27:23·15:32:12 "지원여부" 동시 2회). 직렬화 + 이 프로세스가 켠 광고를 전부 끄고 1개만 켠다.
    private final Object advLock = new Object();
    private final java.util.List<android.bluetooth.le.AdvertiseCallback> advCallbacks = new java.util.ArrayList<>();
    private String startBeaconAdvertise() { synchronized (advLock) { return startBeaconAdvertiseLocked(); } }
    private String startBeaconAdvertiseLocked() {
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ad = (bm != null) ? bm.getAdapter() : null;
            if (ad == null) return "블투 어댑터 null";
            if (!ad.isEnabled()) return "블투 꺼짐(BluetoothAdapter disabled) — 켜야 함";
            boolean multi = ad.isMultipleAdvertisementSupported();
            postToD1("BLEADV", "지원여부: multiAdvertisement=" + multi + " · le2M=" + ad.isLe2MPhySupported());
            if (!multi) return "이 헤드유닛은 BLE advertise 미지원(isMultipleAdvertisementSupported=false)";
            android.bluetooth.le.BluetoothLeAdvertiser adv = ad.getBluetoothLeAdvertiser();
            if (adv == null) return "BluetoothLeAdvertiser null(미지원)";
            for (android.bluetooth.le.AdvertiseCallback cb : advCallbacks) {   // ★628 켠 광고 전부 정지(블투 재시작으로 advertiser 객체가 바뀌었을 수 있어 양쪽 다)
                try { if (bleAdvertiser != null) bleAdvertiser.stopAdvertising(cb); } catch (Throwable ignored) {}
                try { adv.stopAdvertising(cb); } catch (Throwable ignored) {}
            }
            advCallbacks.clear();
            bleAdvertiser = adv;
            // iBeacon manufacturer data: 0x02 0x15 + UUID(16) + major(2) + minor(2) + txPower(1) = 23바이트, companyId=0x004C(Apple)
            // ★v97 minor = 차량 상태 비트필드(RelayClient.beaconMinor) — 폰이 레인징만으로 도어/주행/잠금/송풍 인지.
            int minor = (relay != null) ? relay.beaconMinor() : 0;
            bleAdvMinorSent = minor;
            if (relay != null) relay.putStatus("adv_epoch", RelayClient.advEpoch);   // ★628 현재 프로세스 epoch 게시(폰·진단 대조용)
            java.util.UUID advUuid = vehicleBeaconUuid();   // ★v104 VIN 유도 차량 UUID(미설정 시 구 공용)
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(23);
            bb.put((byte) 0x02).put((byte) 0x15);
            bb.putLong(advUuid.getMostSignificantBits());
            bb.putLong(advUuid.getLeastSignificantBits());
            int major = (relay != null) ? relay.beaconMajor() : 0;   // ★비콘 이벤트 채널(2026-09-11) — 이벤트타입<<8 | seq. 0=이벤트 없음
            bleAdvMajorSent = major;
            bb.putShort((short) major);      // major = 이벤트(타입+seq) — 폰이 seq 변화로 즉시 반응
            bb.putShort((short) minor);      // minor = 상태 비트필드
            bb.put((byte) 0xC5);             // txPower(-59dBm 기준)
            android.bluetooth.le.AdvertiseSettings settings = new android.bluetooth.le.AdvertiseSettings.Builder()
                    .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false).setTimeout(0).build();
            android.bluetooth.le.AdvertiseData data = new android.bluetooth.le.AdvertiseData.Builder()
                    .addManufacturerData(0x004C, bb.array()).build();
            bleAdvCallback = new android.bluetooth.le.AdvertiseCallback() {
                @Override public void onStartSuccess(android.bluetooth.le.AdvertiseSettings s) { bleAdvActive = true; noteFirstAdv("ok minor=" + minor); postToD1("BLEADV", "★iBeacon advertise 송출 중 (UUID " + advUuid.toString().substring(0, 8) + (advUuid.equals(BEACON_UUID) ? "=공용" : "=VIN유도") + ")"); }
                @Override public void onStartFailure(int e) { bleAdvActive = (e == 3); noteFirstAdv("fail" + e + " minor=" + minor); postToD1("BLEADV", "advertise 실패 code=" + e + " (3=이미송출중=OK, 1=데이터큼 2=슬롯부족(잔존광고?) 4=내부오류 5=미지원)"); }
            };
            bleAdvWanted = true;   // 상시화 켬 — 감시/부활에서 자동 재개
            advCallbacks.add(bleAdvCallback);
            adv.startAdvertising(settings, data, bleAdvCallback);
            // ★v103 연결형 GATT 서버 비활성 — S3(펌웨어 v2/v3)가 RSSI notify 특성을 갖고 백그라운드 앱을 깨우는데,
            //   헤드유닛 GATT 서버는 상시 연결형 광고라 폰이 여기 먼저 붙어버림(notify 없음) → 밤샘 콜드스타트 접근 실패
            //   (2026-08-06 06:21 실측: 폰이 헤드유닛 64:31:35에 연결·notify 없어 suspend). 헤드유닛은 iBeacon 레인징+
            //   상태비트만 담당, 연결(notify)은 S3 전담으로 분리. S3 없을 때만 폴백 필요하면 재검토.
            // if (gattServer == null) { String g = startGattServer(); postToD1("BLEGATT", "GATT 서버 시작: " + g); }
            return "advertise 요청 전송(연결형 GATT 서버 OFF·v103) — 콜백 로그 확인";
        } catch (Throwable t) { return "advertise 실패:" + t; }
    }
    // ★v97 GATT 서버(ESP32 펌웨어 v1 호환) — 폰 CarBeaconManager가 connect→토큰 read 인증→readRSSI 체인.
    //   연결형 광고(서비스UUID)는 iBeacon과 별도 세트로 병행(multiAdvertisement). rssiChar(v2)는 서버 측 RSSI 측정 불가라 생략 → 폰이 readRSSI 폴백.
    private static final java.util.UUID GATT_CHAR_TOKEN = java.util.UUID.fromString("f86309ec-8aad-4fdf-b486-7f67a6a1763f");
    private static final java.util.UUID GATT_CHAR_MODE  = java.util.UUID.fromString("ad55702e-4b8f-46d3-af62-1d083f9e3dde");
    private static final String GATT_TOKEN = "byd-usenu-16501";
    private android.bluetooth.BluetoothGattServer gattServer;
    private android.bluetooth.le.AdvertiseCallback connAdvCallback;
    private String startGattServer() {
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ad = (bm != null) ? bm.getAdapter() : null;
            if (bm == null || ad == null || !ad.isEnabled()) return "블투 미준비";
            if (gattServer != null) { try { gattServer.close(); } catch (Throwable ignored) {} }
            gattServer = bm.openGattServer(this, new android.bluetooth.BluetoothGattServerCallback() {
                @Override public void onConnectionStateChange(android.bluetooth.BluetoothDevice d, int status, int newState) {
                    postToD1("BLEGATT", "연결상태 " + newState + " (2=연결/0=끊김) " + d.getAddress());
                }
                @Override public void onCharacteristicReadRequest(android.bluetooth.BluetoothDevice d, int reqId, int offset, android.bluetooth.BluetoothGattCharacteristic ch) {
                    byte[] v = GATT_CHAR_TOKEN.equals(ch.getUuid()) ? GATT_TOKEN.getBytes() : new byte[]{0};
                    byte[] slice = (offset < v.length) ? java.util.Arrays.copyOfRange(v, offset, v.length) : new byte[0];
                    gattServer.sendResponse(d, reqId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, slice);
                    if (GATT_CHAR_TOKEN.equals(ch.getUuid()) && offset == 0) postToD1("BLEGATT", "토큰 read(인증) 응답 → " + d.getAddress());
                }
                @Override public void onCharacteristicWriteRequest(android.bluetooth.BluetoothDevice d, int reqId, android.bluetooth.BluetoothGattCharacteristic ch, boolean prepared, boolean respond, int offset, byte[] value) {
                    if (respond) gattServer.sendResponse(d, reqId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, value);   // modeChar 등 write는 수용만(무동작)
                }
            });
            if (gattServer == null) return "openGattServer null";
            android.bluetooth.BluetoothGattService svc = new android.bluetooth.BluetoothGattService(BEACON_UUID, android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY);
            android.bluetooth.BluetoothGattCharacteristic tokenCh = new android.bluetooth.BluetoothGattCharacteristic(GATT_CHAR_TOKEN,
                    android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ, android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ);
            android.bluetooth.BluetoothGattCharacteristic modeCh = new android.bluetooth.BluetoothGattCharacteristic(GATT_CHAR_MODE,
                    android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE, android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE);
            svc.addCharacteristic(tokenCh); svc.addCharacteristic(modeCh);
            gattServer.addService(svc);
            // 연결형 광고(서비스UUID 노출) — 폰 스캔 필터(serviceUUID)에 잡히는 두 번째 광고 세트
            android.bluetooth.le.BluetoothLeAdvertiser adv = ad.getBluetoothLeAdvertiser();
            if (adv == null) return "advertiser null";
            if (connAdvCallback != null) { try { adv.stopAdvertising(connAdvCallback); } catch (Throwable ignored) {} }
            android.bluetooth.le.AdvertiseSettings st = new android.bluetooth.le.AdvertiseSettings.Builder()
                    .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true).setTimeout(0).build();
            android.bluetooth.le.AdvertiseData dt = new android.bluetooth.le.AdvertiseData.Builder()
                    .addServiceUuid(new android.os.ParcelUuid(BEACON_UUID)).build();
            connAdvCallback = new android.bluetooth.le.AdvertiseCallback() {
                @Override public void onStartSuccess(android.bluetooth.le.AdvertiseSettings s) { postToD1("BLEGATT", "★연결형 광고 송출 중 (GATT 서버 준비 — ESP32 v1 호환)"); }
                @Override public void onStartFailure(int e) { postToD1("BLEGATT", "연결형 광고 실패 code=" + e); }
            };
            adv.startAdvertising(st, dt, connAdvCallback);
            return "GATT 서버+연결형 광고 시작";
        } catch (Throwable t) { return "GATT 실패:" + t; }
    }

    // ★v107 BLE 명령 채널 — 워커 없는 폰→릴레이 직결(근거리). 별도 서비스 UUID라 폰의 S3 자동연결 스캔(4d181ce0)에
    //   안 잡힘 → v103에서 헤드유닛 GATT를 껐던 충돌(폰이 S3 대신 여기 붙어 백그라운드 생존 파괴)이 원천적으로 없다.
    //   인증: 명령 JSON의 auth == VIN 유도 UUID 문자열(폰이 같은 공식으로 계산) — BLE 사거리 내 타인 차단.
    private static final java.util.UUID CMD_SERVICE_UUID = java.util.UUID.fromString("4d18c0de-0f04-4fcc-8c24-75f4ca8cbe91");
    private static final java.util.UUID CMD_CHAR_UUID    = java.util.UUID.fromString("4d18c0d1-0f04-4fcc-8c24-75f4ca8cbe91"); // write: {auth,action,params} / notify: 결과
    private static final java.util.UUID CMD_STATUS_UUID  = java.util.UUID.fromString("4d18c0d2-0f04-4fcc-8c24-75f4ca8cbe91"); // read: 릴레이 상태 JSON
    private static final java.util.UUID CCC_DESC_UUID    = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private android.bluetooth.BluetoothGattServer cmdGatt;
    private android.bluetooth.le.AdvertiseCallback cmdAdvCallback;
    private String startCmdGattServer() {
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ad = (bm != null) ? bm.getAdapter() : null;
            if (bm == null || ad == null || !ad.isEnabled()) return "블투 미준비";
            if (cmdGatt != null) { try { cmdGatt.close(); } catch (Throwable ignored) {} }
            final android.bluetooth.BluetoothGattCharacteristic cmdCh = new android.bluetooth.BluetoothGattCharacteristic(CMD_CHAR_UUID,
                    android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE | android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE);
            cmdCh.addDescriptor(new android.bluetooth.BluetoothGattDescriptor(CCC_DESC_UUID,
                    android.bluetooth.BluetoothGattDescriptor.PERMISSION_READ | android.bluetooth.BluetoothGattDescriptor.PERMISSION_WRITE));
            final android.bluetooth.BluetoothGattCharacteristic stCh = new android.bluetooth.BluetoothGattCharacteristic(CMD_STATUS_UUID,
                    android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ, android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ);
            cmdGatt = bm.openGattServer(this, new android.bluetooth.BluetoothGattServerCallback() {
                @Override public void onCharacteristicReadRequest(android.bluetooth.BluetoothDevice d, int reqId, int offset, android.bluetooth.BluetoothGattCharacteristic ch) {
                    byte[] v = CMD_STATUS_UUID.equals(ch.getUuid()) && relay != null ? relay.stateJson().getBytes() : new byte[]{0};
                    byte[] slice = (offset < v.length) ? java.util.Arrays.copyOfRange(v, offset, v.length) : new byte[0];
                    cmdGatt.sendResponse(d, reqId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, slice);
                }
                @Override public void onDescriptorWriteRequest(android.bluetooth.BluetoothDevice d, int reqId, android.bluetooth.BluetoothGattDescriptor desc, boolean prepared, boolean respond, int offset, byte[] value) {
                    if (respond) cmdGatt.sendResponse(d, reqId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, value);   // notify 구독 수용
                }
                @Override public void onCharacteristicWriteRequest(android.bluetooth.BluetoothDevice d, int reqId, android.bluetooth.BluetoothGattCharacteristic ch, boolean prepared, boolean respond, int offset, byte[] value) {
                    if (respond) cmdGatt.sendResponse(d, reqId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, value);
                    if (!CMD_CHAR_UUID.equals(ch.getUuid()) || value == null) return;
                    new Thread(() -> {
                        String result;
                        String act = "?";
                        try {
                            org.json.JSONObject j = new org.json.JSONObject(new String(value, "UTF-8"));
                            act = j.optString("action", "?");
                            String auth = j.optString("auth", "");
                            // ★보안(2026-09-12 ble-1): 공유판 BLE는 pairKey csig 필수 — VIN 유도 UUID만으로 근거리 제어 차단. 운영자판은 기존 유지(운영자 리스크는 별도).
                            //   검증된 praw를 params로 사용(워커 큐와 동일 — 워커가 params 바꿔쳐도 서명된 원본만 실행).
                            boolean bleOk = !BuildConfig.IS_OPERATOR ? (relay != null && relay.verifyBleCmd(j, j.optString("action"))) : true;
                            if (!vehicleBeaconUuid().toString().equalsIgnoreCase(auth)) { result = "err:unauthorized"; }
                            else if (!bleOk) { result = "err:서명 검증 실패"; }
                            else {
                                String praw = j.optString("praw", "");
                                org.json.JSONObject execParams = (!praw.isEmpty()) ? new org.json.JSONObject(praw) : j.optJSONObject("params");
                                String err = execRelayAction(j.optString("action"), execParams);
                                result = (err == null) ? "ok" : ("err:" + err);
                            }
                        } catch (Throwable t) { result = "err:" + t.getMessage(); }
                        postToD1("BLECMD", "BLE 명령 " + act + " → " + (result.length() > 60 ? result.substring(0, 60) : result));
                        try {
                            cmdCh.setValue(result.getBytes("UTF-8"));
                            cmdGatt.notifyCharacteristicChanged(d, cmdCh, false);
                        } catch (Throwable ignored) {}
                    }, "ble-cmd").start();
                }
            });
            if (cmdGatt == null) return "openGattServer null";
            android.bluetooth.BluetoothGattService svc = new android.bluetooth.BluetoothGattService(CMD_SERVICE_UUID, android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY);
            svc.addCharacteristic(cmdCh); svc.addCharacteristic(stCh);
            cmdGatt.addService(svc);
            android.bluetooth.le.BluetoothLeAdvertiser adv = ad.getBluetoothLeAdvertiser();
            if (adv == null) return "advertiser null";
            if (cmdAdvCallback != null) { try { adv.stopAdvertising(cmdAdvCallback); } catch (Throwable ignored) {} }
            android.bluetooth.le.AdvertiseSettings st = new android.bluetooth.le.AdvertiseSettings.Builder()
                    .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true).setTimeout(0).build();
            android.bluetooth.le.AdvertiseData dt = new android.bluetooth.le.AdvertiseData.Builder()
                    .addServiceUuid(new android.os.ParcelUuid(CMD_SERVICE_UUID)).build();
            cmdAdvCallback = new android.bluetooth.le.AdvertiseCallback() {
                @Override public void onStartSuccess(android.bluetooth.le.AdvertiseSettings s) { postToD1("BLECMD", "★명령 채널 광고 중(연결형, 4d18c0de)"); }
                @Override public void onStartFailure(int e) { postToD1("BLECMD", "명령 채널 광고 실패 code=" + e); }
            };
            adv.startAdvertising(st, dt, cmdAdvCallback);
            return "명령 GATT+광고 시작";
        } catch (Throwable t) { return "명령 GATT 실패:" + t; }
    }

    private String stopBeaconAdvertise() {
        synchronized (advLock) {
            try {
                bleAdvWanted = false; bleAdvActive = false;
                if (bleAdvertiser == null || advCallbacks.isEmpty()) return "실행 중 아님";
                for (android.bluetooth.le.AdvertiseCallback cb : advCallbacks) { try { bleAdvertiser.stopAdvertising(cb); } catch (Throwable ignored) {} }   // ★628 켠 광고 전부
                advCallbacks.clear();
                postToD1("BLEADV", "advertise 중지(상시화 해제)"); return "중지";
            } catch (Throwable t) { return "중지 실패:" + t; }
        }
    }

    // ★v94 비콘 생존 감시 — 시동 종료·도즈로 advertise가 죽어도 자동 부활. 상시생존으로 프로세스가 살아있는 한 이 루프가 지킴.
    //   ① 블투 어댑터 꺼졌으면 켜기 ② advertise 안 나가면 재시작. 평시 30초 주기 + ★v96 부스트(시동종료 후 3분은 2초 주기).
    private volatile boolean beaconWatchStarted = false;
    private volatile long beaconBoostUntil = 0;   // ★v96 시동종료 직후 고속감시 창(초반 1분 불안정 즉시복구)
    private volatile long lastD5BtSvcAt = 0;       // ★씨라7 화면OFF 블투복구 svc 백오프(30s)
    private volatile boolean screenIsOff = false;  // ★화면 상태(SCREEN_OFF/ON 브로드캐스트 추적) — 블투복구 게이트
    private void beaconWatchTick() {
        if (!bleAdvWanted) return;
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ad = (bm != null) ? bm.getAdapter() : null;
            if (ad == null) return;
            if (!ad.isEnabled()) {   // 시동 종료로 블투 꺼졌을 때 되살림
                try { boolean ok = ad.enable(); postToD1("BLEADV", "블투 꺼짐 감지 → enable() = " + ok); } catch (Throwable t) { postToD1("BLEADV", "블투 enable 실패: " + t.getMessage()); }
                // ★화면OFF 블투복구(2026-09-13) — SDK30 신형 헤드유닛(씨라7·아토3 등)은 SCREEN_OFF 시 블투를 끄고 화면 꺼진 동안 app enable()을 무시한다
                //   (테스터 실측: enable()=true인데 STATE_ON은 SCREEN_ON에서만). shell(uid=shell)의 `svc bluetooth enable`은 시스템 경로라 화면OFF서도 통할 수 있어 우회.
                //   게이트=차종 무관 "화면OFF"만(돌핀은 화면OFF에도 블투 유지 → 이 분기 자체에 안 옴). 30초 백오프. svc 통하면 STATE_ON 리시버가 advertise 재시작.
                //   ★화면판정은 브로드캐스트 screenIsOff(DiLink5 isInteractive()는 stale true라 신뢰불가 — 483 svc 미발동 원인).
                long nowMs = System.currentTimeMillis();
                if (screenIsOff && nowMs - lastD5BtSvcAt > 30_000) {
                    lastD5BtSvcAt = nowMs;
                    new Thread(() -> {
                        String r = AdbAccess.runShell(this, "svc bluetooth enable");
                        postToD1("BLEADV", "화면OFF 블투복구 svc bluetooth enable → " + (r == null ? "adb연결실패" : (r.isEmpty() ? "ok(무출력)" : r)));
                    }, "bt-svc").start();
                }
                return;   // STATE_ON 리시버가 켜지는 순간 즉시 advertise 재시작(v96)
            }
            if (!bleAdvActive) { postToD1("BLEADV", "advertise 꺼짐 감지 → 재시작"); startBeaconAdvertise(); return; }
            // ★v97 상태 비트 변화 → minor 갱신(재광고). 문 여닫음·주행·잠금 변화가 수 초 내 폰 레인징에 반영됨.
            int m = (relay != null) ? relay.beaconMinor() : 0;
            int mj = (relay != null) ? relay.beaconMajor() : 0;
            if (m != bleAdvMinorSent || mj != bleAdvMajorSent) {
                postToD1("BLEADV", "비콘 변화 minor " + bleAdvMinorSent + "→" + m + " major " + bleAdvMajorSent + "→" + mj + " → 갱신");
                startBeaconAdvertise();
            }
        } catch (Throwable ignored) {}
    }
    private void startBeaconWatchdog() {
        if (beaconWatchStarted) return; beaconWatchStarted = true;
        // ★v96 블투 상태 리시버 — STATE_ON 되는 '순간' advertise 재시작(30초 폴링 대기 제거).
        try {
            registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, Intent i) {
                    int st = i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1);
                    if (st == android.bluetooth.BluetoothAdapter.STATE_ON && bleAdvWanted) {
                        bleAdvActive = false;
                        postToD1("BLEADV", "블투 STATE_ON → 즉시 advertise 재시작");
                        startBeaconAdvertise();
                        startCmdGattServer();   // ★v107 명령 채널도 부활
                    } else if (st == android.bluetooth.BluetoothAdapter.STATE_OFF && bleAdvWanted) {
                        bleAdvActive = false;
                        beaconBoostUntil = System.currentTimeMillis() + 180_000;   // 꺼짐 감지 → 3분 부스트
                        try { android.bluetooth.BluetoothAdapter.getDefaultAdapter().enable(); postToD1("BLEADV", "블투 STATE_OFF → 즉시 enable 요청 + 부스트 3분"); } catch (Throwable ignored) {}
                    }
                }
            }, new android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED));
        } catch (Throwable t) { postToD1("BLEADV", "상태리시버 등록 실패: " + t.getMessage()); }
        new Thread(() -> {
            while (true) {
                long interval = (System.currentTimeMillis() < beaconBoostUntil) ? 2000 : 5000;   // ★v96 부스트 2초 / 평시 5초(v97 minor 변화 반영)
                try { Thread.sleep(interval); } catch (InterruptedException e) { return; }
                beaconWatchTick();
            }
        }, "beacon-watchdog").start();
    }

    // ★715 시동 OFF 때 폰 오디오·통화 블루투스만 끊기 시험(피드백분석 4abd18 요청, 운영자 10-03 승인 — 관측 우선, 운영자판만).
    //   배경(사용자 의견): 시동을 꺼도 릴레이가 헤드유닛을 깨워 둬 폰이 차 오디오·통화에 계속 붙어 있다. 어댑터는 절대 끄지 않는다
    //   (iBeacon·상태비콘·BLE 명령 채널이 같은 어댑터). 헤드유닛 쪽 프로필 = A2DP 싱크(11)·HFP 핸즈프리(16).
    //   연결 상태 변화·시동 엣지 스냅샷 기록은 항상, 실제 끊기(hidden disconnect)는 원격 스위치 bt_audio_off(기본 끔) 켠 차만.
    //   우선순위·연결정책(setPriority/setConnectionPolicy)은 영속이라 릴레이가 죽으면 영영 자동 재연결이 안 될 수 있어 1차 시험에선 안 쓴다.
    //   끊은 연결은 prefs bt_audio_cut에 저장 — 시동 ON 사이 릴레이가 재시작돼도 재연결 확인이 이어진다.
    private static final int BT_A2DP_SINK = 11, BT_HFP_CLIENT = 16;
    private final java.util.Map<Integer, android.bluetooth.BluetoothProfile> btProxies = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean btAudioInitDone = false, btBydProbed = false;
    private volatile long btIgnOffAt = 0, btIgnOnAt = 0;
    private void btAudioInit() {
        if (btAudioInitDone) return;
        btAudioInitDone = true;
        try {
            android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ad == null) { postToD1("BTAUDIO", "어댑터 없음 — 시험 불가"); return; }
            for (int p : new int[]{BT_A2DP_SINK, BT_HFP_CLIENT}) {
                final int prof = p;
                boolean ok = ad.getProfileProxy(this, new android.bluetooth.BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) { btProxies.put(prof, proxy); }
                    @Override public void onServiceDisconnected(int profile) { btProxies.remove(prof); }
                }, prof);
                if (!ok) postToD1("BTAUDIO", btProfName(prof) + " 프록시 요청 거부(getProfileProxy=false) — 이 헤드유닛 안드로이드 스택에 그 프로필 없음 의심");
            }
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED");
            f.addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED");
            registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, Intent i) {
                    int st = i.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1);
                    int pv = i.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE, -1);
                    if (st != android.bluetooth.BluetoothProfile.STATE_CONNECTED && st != android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) return;   // 연결중·끊는중 생략
                    android.bluetooth.BluetoothDevice d = i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
                    String prof = String.valueOf(i.getAction()).contains("a2dp-sink") ? "A2DP싱크" : "HFP";
                    postToD1("BTAUDIO", prof + " " + btStName(pv) + "→" + btStName(st) + " " + btDev(d) + btIgnAgo());
                    if (d == null || d.getAddress() == null) return;
                    if (st == android.bluetooth.BluetoothProfile.STATE_CONNECTED && prefs != null) prefs.edit().putString("bt_audio_last", d.getAddress()).apply();
                    else if (st == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED && pv == android.bluetooth.BluetoothProfile.STATE_CONNECTING) btHealOnConnectFail(d);
                }
            }, f);
            Thread healT = new Thread(() -> { while (true) { sleepQuiet(10_000); try { btHealTick(); } catch (Throwable t) { postToD1("BTAUDIO", "자동 복구 틱 오류 " + t); } } }, "bt-audio-heal");
            healT.setDaemon(true); healT.start();
        } catch (Throwable t) { postToD1("BTAUDIO", "초기화 실패 " + t); }
    }
    // ★810 2단계(운영자 10-06 "2단계로 바로 가", 피드백분석 4abd18 전달) — 목적: 폰이 차 오디오 블루투스 연결·끊김으로 핫스팟을 켜고 끄는 자동화.
    //   ① 시동 OFF 때 끊기 ② 시동 OFF 상태에서 잠길 때 한 번 더 끊기 ③ 시동 ON 때 잠금해제와 상관없이 끊었던 기기에 바로 연결·재시도.
    //   운영자판은 기본 켬, 공유판은 종전대로 RAW {"btAudioOff":true} 켠 차만. 어댑터 끄기·연결정책 변경은 여전히 안 쓴다(715 원칙).
    private boolean btAudioCutOn() { return prefs != null && prefs.getBoolean("bt_audio_off", BuildConfig.IS_OPERATOR); }
    private volatile int btLastLock = -1;
    private volatile long btLockCutAt = 0;
    private void btAudioOnIgnOff() {
        btIgnOffAt = System.currentTimeMillis();
        final long myOff = btIgnOffAt;
        new Thread(() -> {
            sleepQuiet(5000);
            boolean on = btAudioCutOn();
            postToD1("BTAUDIO", "시동 OFF +5초 연결 " + btAudioSnap() + " · " + bleAdvSnap() + " · 끊기 스위치 " + (on ? "켬" : "끔(관측만)"));
            if (!on) return;
            // ① 시동을 끈 직후 헤드유닛이 블루투스를 잠깐 껐다 켜며 폰을 다시 붙인다(10-06 08:37 +5초 어댑터 꺼짐 → +9초 HFP 연결됨).
            //   +5초에 한 번만 보면 끊을 게 없어서, 끈 뒤 60초까지 2초마다 보다가 연결이 보이면 3초 더 기다려(두 프로필이 다 붙게) 끊는다.
            while (System.currentTimeMillis() < myOff + 60_000 && btIgnOnAt < myOff && btConnectedCount() == 0) sleepQuiet(2000);
            if (btIgnOnAt >= myOff) return;   // 그사이 시동을 다시 켬
            if (btConnectedCount() > 0) sleepQuiet(3000);
            if (btIgnOnAt >= myOff) return;
            btAudioCut("시동 OFF");
        }, "bt-audio-off").start();
    }
    // ② 풀림→잠김 순간만(릴레이 시작 때 첫 값은 제외). 잠김 신호가 연달아 와도 30초에 한 번.
    private void btAudioOnLockState(int v) {
        int pv = btLastLock; btLastLock = v;
        if (pv != 0 || v != 1 || !btAudioCutOn() || relay == null || relay.ignitionState() == 1) return;
        long now = System.currentTimeMillis();
        if (now - btLockCutAt < 30_000) return;
        btLockCutAt = now;
        new Thread(() -> { sleepQuiet(2000); btAudioCut("시동 OFF 뒤 잠금"); }, "bt-audio-lock").start();
    }
    private int btConnectedCount() {
        int n = 0;
        for (int p : new int[]{BT_A2DP_SINK, BT_HFP_CLIENT}) {
            android.bluetooth.BluetoothProfile px = btProxies.get(p);
            if (px == null) continue;
            try { n += px.getConnectedDevices().size(); } catch (Throwable ignored) {}
        }
        return n;
    }
    // 지금 붙어 있는 A2DP 싱크·HFP 연결을 끊고 끊은 것을 bt_audio_cut에 더한다 — ③에서 다시 붙일 대상.
    //   실제로 연결돼 있던 기기만 들어가므로 연결 시도만 하다 끝나는 다른 기기(KN32…)는 대상이 아니다(운영자 ④ "최근에 끊어진 기기").
    private void btAudioCut(String why) {
        btBydProbeOnce();
        StringBuilder cut = new StringBuilder(), res = new StringBuilder();
        for (int p : new int[]{BT_A2DP_SINK, BT_HFP_CLIENT}) {
            android.bluetooth.BluetoothProfile px = btProxies.get(p);
            if (px == null) continue;
            java.util.List<android.bluetooth.BluetoothDevice> l;
            try { l = px.getConnectedDevices(); } catch (Throwable t) { res.append(btProfName(p)).append(" 목록 실패(").append(t.getClass().getSimpleName()).append(") | "); continue; }
            for (android.bluetooth.BluetoothDevice d : l) {
                String r = btInvoke(px, "disconnect", d);
                res.append(btProfName(p)).append(" ").append(btDev(d)).append(" disconnect → ").append(r).append(" | ");
                if (r.startsWith("true")) cut.append(p).append("@").append(d.getAddress()).append(",");
            }
        }
        if (res.length() == 0) { postToD1("BTAUDIO", why + " — 끊을 연결 없음 · " + btAudioSnap() + " · " + bleAdvSnap()); return; }
        if (prefs != null && cut.length() > 0) {
            String old = prefs.getString("bt_audio_cut", "");
            StringBuilder m = new StringBuilder(old);
            for (String e : cut.toString().split(",")) if (!e.isEmpty() && !("," + old).contains("," + e + ",")) m.append(e).append(",");
            prefs.edit().putString("bt_audio_cut", m.toString()).apply();
        }
        postToD1("BTAUDIO", why + " 끊기 시도 " + res);
        sleepQuiet(4000);
        postToD1("BTAUDIO", why + " 끊기 +4초 연결 " + btAudioSnap() + " · " + bleAdvSnap());
        sleepQuiet(56000);
        postToD1("BTAUDIO", why + " 끊기 +60초 연결 " + btAudioSnap() + " · " + bleAdvSnap() + " (다시 붙었으면 헤드유닛·폰이 자동 재연결한 것)");
    }
    private void btAudioOnIgnOn() {
        btIgnOnAt = System.currentTimeMillis();
        final long myOn = btIgnOnAt;
        final String cut = prefs != null ? prefs.getString("bt_audio_cut", "") : "";
        new Thread(() -> {
            sleepQuiet(3000);
            postToD1("BTAUDIO", "시동 ON +3초 연결 " + btAudioSnap() + (cut.isEmpty() ? "" : " · 끊었던 연결 " + cut.split(",").length + "개"));
            if (cut.isEmpty()) return;
            // ③ 바로 연결 요청, 안 붙으면 10초마다 다시(최대 9번 ≈ 1분 반 — 헤드유닛이 막 깨어나 프록시가 늦게 붙는 경우 포함). 다 붙으면 기록을 지우고, 못 붙이면 다음 시동 때 다시.
            boolean all = false;
            for (int round = 1; round <= 9 && btIgnOnAt == myOn && btIgnOffAt < myOn; round++) {
                StringBuilder res = new StringBuilder(); all = true;
                for (String e : cut.split(",")) {
                    int at = e.indexOf('@');
                    if (at <= 0) continue;
                    int p;
                    try { p = Integer.parseInt(e.substring(0, at)); } catch (NumberFormatException x) { continue; }
                    android.bluetooth.BluetoothProfile px = btProxies.get(p);
                    if (px == null) { all = false; res.append(btProfName(p)).append(" 프록시없음 | "); continue; }
                    android.bluetooth.BluetoothDevice d;
                    try { d = android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice(e.substring(at + 1)); }
                    catch (Throwable t) { res.append(btProfName(p)).append(" 주소 오류 | "); continue; }
                    int st = -1;
                    try { st = px.getConnectionState(d); } catch (Throwable ignored) {}
                    if (st == android.bluetooth.BluetoothProfile.STATE_CONNECTED) { res.append(btProfName(p)).append(" ").append(btDev(d)).append(" 연결됨 | "); continue; }
                    all = false;
                    if (st == android.bluetooth.BluetoothProfile.STATE_CONNECTING) res.append(btProfName(p)).append(" ").append(btDev(d)).append(" 연결 중 | ");
                    else res.append(btProfName(p)).append(" ").append(btDev(d)).append(" 안 붙음 → connect ").append(btInvoke(px, "connect", d)).append(" | ");
                }
                postToD1("BTAUDIO", "시동 ON 재연결 " + round + "회 " + res);
                if (all) break;
                sleepQuiet(10000);
            }
            if (all && prefs != null) prefs.edit().remove("bt_audio_cut").apply();
            sleepQuiet(15000);
            postToD1("BTAUDIO", "시동 ON 재연결 뒤 연결 " + btAudioSnap() + (all ? "" : " · 못 붙인 기록은 다음 시동 때 다시"));
        }, "bt-audio-on").start();
    }
    // ★씨라7 블투 오디오 자동 복구(FB2183·FB2203, 운영자 10-08 "1·2번 다 공유판으로 바로" — 돌핀은 정상, 씨라7만 문제라 테스터 차로 확인).
    //   ① 같은 기기가 60초 안에 연결중→끊김을 5번 이상 반복하면 그 기기의 오디오 두 프로필만 disconnect(차의 재연결 폭풍 멈추기, 5분에 한 번).
    //   ② 시동 ON·주 사용권 폰 근접인데 오디오가 하나도 안 붙어 있으면 45초 뒤부터 마지막으로 붙었던 기기에 connect(20초 간격, 최대 5번).
    //   어댑터·연결정책은 건드리지 않는다(비콘·워치키는 같은 어댑터). 스위치 bt_audio_heal: 씨라7 기본 켬, 그 밖에는 꺼짐, RAW {"btAudioHeal":true|false}.
    private boolean btHealOn() { return prefs != null && prefs.getBoolean("bt_audio_heal", IS_D5); }
    private final java.util.Map<String, java.util.ArrayDeque<Long>> btFailTimes = new java.util.HashMap<>();
    private volatile long btHealBreakAt = 0, btHealSince = 0, btHealLastTry = 0;
    private volatile int btHealTries = 0;
    private void btHealOnConnectFail(android.bluetooth.BluetoothDevice d) {
        if (!btHealOn()) return;
        long now = System.currentTimeMillis();
        int n;
        synchronized (btFailTimes) {
            java.util.ArrayDeque<Long> q = btFailTimes.get(d.getAddress());
            if (q == null) { q = new java.util.ArrayDeque<>(); btFailTimes.put(d.getAddress(), q); }
            q.addLast(now);
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
            n = q.size();
            if (n < 5 || now - btHealBreakAt < 300_000) return;
            btHealBreakAt = now; q.clear();
        }
        final int fails = n;
        new Thread(() -> {
            StringBuilder res = new StringBuilder();
            for (int p : new int[]{BT_A2DP_SINK, BT_HFP_CLIENT}) {
                android.bluetooth.BluetoothProfile px = btProxies.get(p);
                if (px == null) { res.append(btProfName(p)).append(" 프록시없음 | "); continue; }
                res.append(btProfName(p)).append(" disconnect → ").append(btInvoke(px, "disconnect", d)).append(" | ");
            }
            postToD1("BTAUDIO", "자동 복구① " + btDev(d) + " 60초 안에 연결중→끊김 " + fails + "번 → 재연결 폭풍 멈추기 " + res);
            sleepQuiet(30_000);
            postToD1("BTAUDIO", "자동 복구① +30초 연결 " + btAudioSnap());
        }, "bt-heal-break").start();
    }
    private void btHealTick() {
        if (!btHealOn() || relay == null || relay.ignitionState() != 1) { btHealSince = 0; btHealTries = 0; return; }
        if (btConnectedCount() > 0) { btHealSince = 0; btHealTries = 0; return; }
        if (!phoneNearFresh()) { btHealSince = 0; btHealTries = 0; return; }
        String last = prefs.getString("bt_audio_last", "");
        if (last.isEmpty() || (btProxies.get(BT_A2DP_SINK) == null && btProxies.get(BT_HFP_CLIENT) == null)) return;
        long now = System.currentTimeMillis();
        if (btHealSince == 0) { btHealSince = now; return; }
        if (now - btHealSince < 45_000 || btHealTries >= 5 || now - btHealLastTry < 20_000) return;
        android.bluetooth.BluetoothDevice d;
        try { d = android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice(last); } catch (Throwable t) { return; }
        btHealLastTry = now; btHealTries++;
        StringBuilder res = new StringBuilder();
        for (int p : new int[]{BT_A2DP_SINK, BT_HFP_CLIENT}) {
            android.bluetooth.BluetoothProfile px = btProxies.get(p);
            if (px == null) { res.append(btProfName(p)).append(" 프록시없음 | "); continue; }
            int st = -1;
            try { st = px.getConnectionState(d); } catch (Throwable ignored) {}
            if (st == android.bluetooth.BluetoothProfile.STATE_CONNECTING) res.append(btProfName(p)).append(" 연결 중 | ");
            else res.append(btProfName(p)).append(" connect → ").append(btInvoke(px, "connect", d)).append(" | ");
        }
        postToD1("BTAUDIO", "자동 복구② " + btHealTries + "회 " + btDev(d) + " (시동 ON·폰 근접인데 오디오 연결 " + ((now - btHealSince) / 1000) + "초째 없음) " + res);
    }
    private String btAudioSnap() {
        StringBuilder sb = new StringBuilder();
        for (int p : new int[]{BT_A2DP_SINK, BT_HFP_CLIENT}) {
            android.bluetooth.BluetoothProfile px = btProxies.get(p);
            sb.append(btProfName(p)).append("=");
            if (px == null) { sb.append("프록시없음 "); continue; }
            try {
                java.util.List<android.bluetooth.BluetoothDevice> l = px.getConnectedDevices();
                sb.append(l.size()).append("대");
                for (android.bluetooth.BluetoothDevice d : l) sb.append("[").append(btDev(d)).append("]");
                sb.append(" ");
            } catch (Throwable t) { sb.append("읽기실패(").append(t.getClass().getSimpleName()).append(") "); }
        }
        return sb.toString().trim();
    }
    private String bleAdvSnap() {
        android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        return "어댑터 " + (ad != null && ad.isEnabled() ? "켜짐" : "꺼짐") + " · 비콘광고 " + (bleAdvActive ? "중" : "멈춤") + " · 명령채널 " + (cmdGatt != null ? "열림" : "없음");
    }
    // hidden connect/disconnect(BluetoothDevice) — 공개 리플렉션이 막히면 HiddenApiBypass로 한 번 더. 결과·거부 사유를 그대로 문자열로.
    private String btInvoke(Object proxy, String name, android.bluetooth.BluetoothDevice d) {
        try {
            return String.valueOf(proxy.getClass().getMethod(name, android.bluetooth.BluetoothDevice.class).invoke(proxy, d));
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable c = ite.getCause();
            return "거부(" + (c == null ? "?" : c.getClass().getSimpleName() + ": " + btCut(c.getMessage())) + ")";
        } catch (NoSuchMethodException nsme) {
            try { return org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(proxy.getClass(), proxy, name, d) + "(우회)"; }
            catch (Throwable t2) { return "메서드없음·우회실패(" + t2.getClass().getSimpleName() + ": " + btCut(t2.getMessage()) + ")"; }
        } catch (Throwable t) { return "실패(" + t.getClass().getSimpleName() + ": " + btCut(t.getMessage()) + ")"; }
    }
    // BYD SDK에 블루투스 디바이스가 있는지(매니페스트에 BYDAUTO_BLUETOOTH_GET/SET 권한이 있음) — 첫 끊기 때 1회, 후보 클래스 하나만.
    private void btBydProbeOnce() {
        if (btBydProbed) return;
        btBydProbed = true;
        try {
            Class<?> c = Class.forName("android.hardware.bydauto.bluetooth.BYDAutoBluetoothDevice");
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Method m : c.getMethods()) {
                String n = m.getName().toLowerCase(java.util.Locale.US);
                if (n.contains("connect") || n.contains("a2dp") || n.contains("hfp") || n.contains("phone") || n.contains("music") || n.contains("audio"))
                    sb.append(m.getName()).append("(").append(m.getParameterCount()).append(") ");
            }
            postToD1("BTAUDIO", "BYD 블투 SDK 있음 — 관련 메서드: " + (sb.length() == 0 ? "없음" : sb.toString().trim()));
        } catch (ClassNotFoundException e) { postToD1("BTAUDIO", "BYD 블투 SDK 클래스 없음(bydauto.bluetooth.BYDAutoBluetoothDevice)"); }
        catch (Throwable t) { postToD1("BTAUDIO", "BYD 블투 SDK 탐색 실패 " + t.getClass().getSimpleName()); }
    }
    private static String btProfName(int p) { return p == BT_A2DP_SINK ? "A2DP싱크" : p == BT_HFP_CLIENT ? "HFP" : "프로필" + p; }
    private static String btStName(int s) { return s == 0 ? "끊김" : s == 1 ? "연결중" : s == 2 ? "연결됨" : s == 3 ? "끊는중" : String.valueOf(s); }
    private static String btCut(String s) { return s == null ? "" : (s.length() > 80 ? s.substring(0, 80) : s); }
    private static String btDev(android.bluetooth.BluetoothDevice d) {
        if (d == null) return "?";
        String n = null, a = d.getAddress();
        try { n = d.getName(); } catch (Throwable ignored) {}
        return (n == null ? "?" : (n.length() > 4 ? n.substring(0, 4) + "…" : n)) + "/…" + (a != null && a.length() > 5 ? a.substring(a.length() - 5) : a);
    }
    private String btIgnAgo() {
        long now = System.currentTimeMillis();
        if (btIgnOffAt == 0 && btIgnOnAt == 0) return "";
        return btIgnOffAt >= btIgnOnAt ? " (시동 OFF " + (now - btIgnOffAt) / 1000 + "초 후)" : " (시동 ON " + (now - btIgnOnAt) / 1000 + "초 후)";
    }

    // AC feature 하나를 읽어 문자열로 — 이름이 없거나 실패해도 "?"로 넘어가 검증 로그 전체가 안 죽게.
    private String readAcFeat(Object ac, String name) {
        try { return naFmt(getFeatureInt(ac, featureIdByName(name))); } catch (Throwable t) { return "?"; }
    }
    // 이름으로 baseSet — featureIdByName의 checked exception을 삼켜 스레드/람다에서 편히 쓰게.
    private String acSet(Object ac, String name, int val) {
        try { return baseSet(ac, featureIdByName(name), val); } catch (Throwable t) { return "이름실패:" + name; }
    }

    private String execRaw(org.json.JSONObject params) {
        if (params == null) return "RAW: params 없음";
        if (DiLink5Support.isDiLink5()) DiLink5Support.ensure(this);   // ★DiLink5=bydauto가 부트클래스패스에 없음 → dex 주입 선행(멱등). 이후 deviceByName/onDemand의 Class.forName·getInstance 성공.
        try {
            // 2) feature 상수 열거
            String filter = params.optString("dumpFeatures");
            if (filter != null && !filter.isEmpty()) return dumpFeatureIds(filter);

            // ★v122 디바이스 전체 메서드 열거(이름+인자타입) — BSD 방향 메서드가 인자 받는 형태로 숨어있나(접근2 완전확인).
            String dm = params.optString("dumpMethods");
            if (dm != null && !dm.isEmpty()) {
                Object dev = deviceByName(dm);
                if (dev == null) { postToD1("ADASMETH", dm + " → null"); return null; }
                StringBuilder sb = new StringBuilder();
                for (java.lang.reflect.Method m : dev.getClass().getMethods()) {
                    String n = m.getName();
                    if (n.equals("toString") || n.equals("hashCode") || n.equals("equals") || n.startsWith("register") || n.startsWith("unregister")) continue;
                    Class<?>[] ps = m.getParameterTypes();
                    sb.append(n).append("(");
                    for (int i = 0; i < ps.length; i++) sb.append(i > 0 ? "," : "").append(ps[i].getSimpleName());
                    sb.append(")|");
                    if (sb.length() > 1400) { postToD1("ADASMETH", sb.toString()); sb.setLength(0); }
                }
                if (sb.length() > 0) postToD1("ADASMETH", sb.toString());
                return null;
            }

            // ★제어 실험실 — 미지 id 캡처 토글. on 시 recentEvts 비우고, 순정 MyCar 조작 시 뜨는 새 featureId(idXXXX)를 _evt로 노출(앱 실시간 표시).
            if (params.has("evtCap")) {
                boolean on = params.optBoolean("evtCap");
                evtCapAll = on;
                if (prefs != null) prefs.edit().putBoolean("evt_cap_all", on).apply();
                synchronized (recentEvts) { recentEvts.clear(); }
                if (relay != null) relay.setDiag("evt", on ? "(캡처 시작 — 순정 화면 조작하세요)" : "(캡처 중지)");
                postToD1("EVTCAP", "미지 id 캡처 = " + on);
                return null;
            }

            // ★제어 실험실 — 매니저 순회 write: 같은 featureId를 라이트/바디/계기판/설정에 연속 발사하고 각 반환을 _labret로 한 번에 비교.
            //   매니저를 바꿔가며 재시도할 필요 없음(어디서 OK/1 나오는지 즉시). 쓰기라 도어락(signature)·ADAS·공조는 기본 제외(오발사 방지),
            //   필요시 {"sweep":id,"devices":["adas",..]}로 확장. featureId는 숫자 또는 이름(String) 허용(캡처 스트림 이름 그대로 발사).
            if (params.has("sweep")) {
                Object sw = params.opt("sweep");
                int fid = (sw instanceof Number) ? ((Number) sw).intValue() : featureIdByName(String.valueOf(sw));
                int val = params.optInt("value", 1);
                String[] devs, labels;
                org.json.JSONArray dj = params.optJSONArray("devices");
                if (dj != null && dj.length() > 0) {
                    devs = new String[dj.length()]; labels = new String[dj.length()];
                    for (int i = 0; i < dj.length(); i++) { devs[i] = dj.optString(i); labels[i] = devs[i]; }
                } else {
                    devs = new String[]{"android.hardware.bydauto.light.BYDAutoLightDevice", "bodywork", "instrument", "setting"};
                    labels = new String[]{"라이트", "바디", "계기판", "설정"};
                }
                StringBuilder ret = new StringBuilder();
                StringBuilder full = new StringBuilder();
                for (int i = 0; i < devs.length; i++) {
                    Object d = deviceByName(devs[i]);
                    String r; StringBuilder lg = new StringBuilder();
                    if (d == null) r = "디바이스 null";
                    else r = writeOne(d, fid, val, lg);
                    String tag;
                    if (r.startsWith("OK")) { int ix = r.indexOf("ret="); tag = ix >= 0 ? r.substring(ix + 4).trim() : "OK"; }
                    else if (r.contains("없음")) tag = "무set";
                    else if (r.startsWith("디바이스")) tag = "무";
                    else tag = "거부";
                    ret.append(labels[i]).append("=").append(tag).append("  ");
                    full.append(labels[i]).append(":").append(r).append(" | ");
                }
                postToD1("DILINK_RAW", "sweep[" + fid + "]=" + val + " → " + full);
                return ret.toString();
            }

            // ★전방 대시캠 알림 설정(앞차출발·신호변경) 동기 — 헤드유닛 로컬 사운드 인덱스/on-off/음량.
            org.json.JSONObject front = params.optJSONObject("frontAlert");
            if (front != null && prefs != null) {
                android.content.SharedPreferences.Editor e = prefs.edit();
                if (front.has("lead")) e.putBoolean("front_lead", front.getBoolean("lead"));
                if (front.has("traffic")) e.putBoolean("front_traffic", front.getBoolean("traffic"));
                if (front.has("leadIdx")) e.putInt("front_lead_idx", front.getInt("leadIdx"));
                if (front.has("trafficIdx")) e.putInt("front_traffic_idx", front.getInt("trafficIdx"));
                if (front.has("vol")) e.putInt("front_vol", front.getInt("vol"));
                if (front.has("object")) e.putBoolean("front_object", front.getBoolean("object"));   // ★객체인식 게이트(앞차=차량확인·신호등=객체색). 기본 ON.
                if (front.has("diag")) e.putBoolean("front_diag", front.getBoolean("diag"));   // ★진단 프레임 저장(정차 프레임 ROI·감지값 오버레이 → 서버 pull)
                // ★FLIP 신호등 새 방식(09-30) — 묶음에 있는 키만 저장값을 바꾼다. 범위 밖 값은 거부하고 로그.
                if (front.has("flipMode")) { int v = front.optInt("flipMode", -1); if (v >= 0 && v <= 2) e.putInt("front_flip_mode", v); else postToD1("FRONT", "[FLIP] 설정 거부 flipMode=" + front.opt("flipMode") + " (0~2만 허용)"); }
                if (front.has("flipThr")) { double v = front.optDouble("flipThr", Double.NaN); if (v >= 500 && v <= 20000) e.putFloat("front_flip_thr", (float) v); else postToD1("FRONT", "[FLIP] 설정 거부 flipThr=" + front.opt("flipThr") + " (500~20000만 허용)"); }
                if (front.has("flipPersist")) { int v = front.optInt("flipPersist", -1); if (v >= 1 && v <= 10) e.putInt("front_flip_persist", v); else postToD1("FRONT", "[FLIP] 설정 거부 flipPersist=" + front.opt("flipPersist") + " (1~10만 허용)"); }
                e.apply();
                postToD1("FRONT", "설정 lead=" + prefs.getBoolean("front_lead", true) + " traffic=" + prefs.getBoolean("front_traffic", true) + " leadIdx=" + prefs.getInt("front_lead_idx", 10) + " trafficIdx=" + prefs.getInt("front_traffic_idx", 12) + " vol=" + prefs.getInt("front_vol", 80) + " object=" + prefs.getBoolean("front_object", true)
                    + " flipMode=" + prefs.getInt("front_flip_mode", FLIP_MODE_DEFAULT) + " flipThr=" + Math.round(prefs.getFloat("front_flip_thr", 2500f)) + " flipPersist=" + prefs.getInt("front_flip_persist", 2));
                return null;
            }

            // ★후측방(BSD) 경보 설정 동기 — 경보 on/off·깜빡이 때만·알림음 톤 인덱스를 릴레이 prefs로.
            org.json.JSONObject bsd = params.optJSONObject("bsdAlert");
            if (bsd != null && prefs != null) {
                android.content.SharedPreferences.Editor e = prefs.edit();
                if (bsd.has("on")) e.putBoolean(KEY_ALERT, bsd.getBoolean("on"));
                if (bsd.has("turnOnly")) e.putBoolean(KEY_TURN_ONLY, bsd.getBoolean("turnOnly"));
                if (bsd.has("sound")) e.putInt(KEY_SOUND, bsd.getInt("sound"));
                if (bsd.has("vol")) e.putInt(KEY_BSD_VOL, Math.max(0, Math.min(100, bsd.getInt("vol"))));
                if (bsd.has("channel")) e.putInt(KEY_BSD_CHANNEL, Math.max(0, Math.min(CH_STREAM.length - 1, bsd.getInt("channel"))));   // ★2026-09-16 채널 선택
                e.apply();
                if (bsd.has("vol") || bsd.has("channel")) rebuildToneGen();
                postToD1("BSD", "설정 on=" + prefs.getBoolean(KEY_ALERT, false) + " turnOnly=" + prefs.getBoolean(KEY_TURN_ONLY, false) + " sound=" + prefs.getInt(KEY_SOUND, 0) + " vol=" + prefs.getInt(KEY_BSD_VOL, 100) + " channel=" + CH_LABEL[chIdx(KEY_BSD_CHANNEL)]);
                return null;
            }
            // ★BSD 알림음 미리듣기 — 선택(또는 지정) 톤 1회 재생(쿨다운 무시). 헤드유닛 스피커.
            if (params.has("bsdTest")) {
                int t = params.optInt("bsdTest", -1);
                float v = (params.has("vol") ? params.optInt("vol", 100) : (prefs != null ? prefs.getInt(KEY_BSD_VOL, 100) : 100)) / 100f;   // ★629 vol 받음(구 앱은 저장값)
                playBsd((t >= 0 && t < BSD_TONES.length) ? t : bsdIdx(), v);
                return null;
            }
            if (params.has("rebootGuard") && prefs != null) {   // ★739 재부팅 루틴 방지 — ★740부터 항상 켬(폰 스위치 없음), 이 RAW는 비상용 원격 끄기·다시 켜기
                boolean on = params.optBoolean("rebootGuard");
                prefs.edit().putBoolean("reboot_guard", on).apply();
                applyRebootGuard("폰 앱 설정");
                return null;
            }
            // ★모든 자동 기능 정지(킬 스위치, 2026-09-08) — 폰 앱의 정지 토글 동기. all_stop이면 릴레이가 킥트렁크·BSD·후진비상등·전방알림을 전부 스킵.
            //   개별 설정(KEY_ALERT·reverse_flash 등)은 보존 — all_stop 해제 시 원래대로 자동 복원. 정지 시 돌던 camDetect도 중단.
            if (params.has("camOff") && prefs != null) {   // ★카메라 기능 끔(폰 앱 설정) — 차 기준 설정, 모든 폰이 relay_state.cam_off로 따라감
                boolean off = params.optBoolean("camOff");
                prefs.edit().putBoolean("cam_off", off).apply();
                if (relay != null) relay.putStatus("cam_off", off);
                if (off) stopAllCameraUse();
                SentryManager.get().reevaluate();   // ★790 센트리 게이트 바로 다시 보기 — 끄면 IDLE, 켜면 3초 뒤 재무장(1분 tick을 기다리지 않게)
                postToD1("RELAY", "카메라 기능 " + (off ? "끔" : "켬") + " (폰 앱 설정)");
                return null;
            }
            // ★712 씨라7 카메라 방식 토글(앱 설정·서버 원격 공통) — new=오버드라이브(반납 감시 .so 얹음), legacy=기존(안 얹음, ★722부터 기본).
            //   안 되는 차는 이 값만 legacy로 바꾸면 다음 카메라 열 때부터 종전 방식. 서버에서 차별 RAW로도 바꿀 수 있음.
            if (params.has("d5camMode") && prefs != null) {
                String mode = "legacy".equals(params.optString("d5camMode")) ? "legacy" : "new";
                prefs.edit().putString("d5cam_mode", mode).apply();
                if (relay != null) relay.putStatus("d5cam_mode", mode);
                try { com.overdrive.app.camera.dilink5.FastCamTcpBackend.releaseDaemon(this, (t, m) -> postToD1(t, m)); } catch (Throwable ig) {}   // 다음 열 때 새 방식으로 뜨게 현 데몬 반납
                postToD1("RELAY", "씨라7 카메라 방식 = " + mode + (mode.equals("new") ? "(오버드라이브 반납감시)" : "(기존)"));
                return null;
            }
            // ★715 시동 OFF 폰 오디오·통화 끊기 시험 스위치(테스터 한정 원격, 기본 끔) — 서버·앱 RAW {"btAudioOff":true|false}.
            if (params.has("btAudioOff") && prefs != null) {
                boolean on = params.optBoolean("btAudioOff");
                prefs.edit().putBoolean("bt_audio_off", on).apply();
                if (relay != null) relay.putStatus("bt_audio_off", on ? 1 : 0);
                postToD1("BTAUDIO", "시동 OFF 오디오 끊기 시험 스위치 = " + (on ? "켬" : "끔"));
                return null;
            }
            // ★씨라7 블투 오디오 자동 복구 스위치 — RAW {"btAudioHeal":true|false}. 기본은 씨라7 켬·그 밖에 끔.
            if (params.has("btAudioHeal") && prefs != null) {
                boolean on = params.optBoolean("btAudioHeal");
                prefs.edit().putBoolean("bt_audio_heal", on).apply();
                if (relay != null) relay.putStatus("bt_audio_heal", on ? 1 : 0);
                postToD1("BTAUDIO", "블투 오디오 자동 복구 스위치 = " + (on ? "켬" : "끔"));
                return null;
            }
            // ★753 시동 꺼도 USB 유지(부가기능 토글) — RAW {"usbKeep":true|false}, 실차 시험용 {"usbKeepMode":0|1|2|3}(0 화면 깨우기 잠금·1 keyevent 224·2 둘 다·★758 3 셸 깨우기=기본)
            if ((params.has("usbKeep") || params.has("usbKeepMode") || params.has("usbKeepDark")) && prefs != null) {
                android.content.SharedPreferences.Editor e = prefs.edit();
                if (params.has("usbKeep")) e.putBoolean("usb_keep_user", params.optBoolean("usbKeep"));
                if (params.has("usbKeepMode")) e.putInt("usb_keep_how", Math.max(0, Math.min(3, params.optInt("usbKeepMode", 3))));   // ★758 3=셸 깨우기
                if (params.has("usbKeepDark")) e.putBoolean("usb_keep_dark", params.optBoolean("usbKeepDark"));   // ★760 USB 유지 중 화면 끄기(기본 켬)
                e.apply();
                usbKeepStLast = "";   // 다음 틱(2초)에 상태 다시 알림
                if (relay != null) relay.putStatus("usb_keep_user", prefs.getBoolean("usb_keep_user", false) ? 1 : 0);
                postToD1("USBKEEP", "설정 — 수동 " + (prefs.getBoolean("usb_keep_user", false) ? "켬" : "끔") + " · 방식 " + prefs.getInt("usb_keep_how", 3) + " · 화면 끄기 " + (prefs.getBoolean("usb_keep_dark", true) ? "켬" : "끔"));
                return null;
            }
            // ★736 돌핀 센트리 깨우기 시험 스위치(운영자판, 기본 켬) — RAW {"d3Sentry":false}로 끔.
            if (params.has("d3Sentry") && prefs != null) {
                boolean on = params.optBoolean("d3Sentry");
                prefs.edit().putBoolean("d3_sentry", on).apply();
                if (relay != null) relay.putStatus("d3_sentry", on ? 1 : 0);
                postToD1("WAKE", "D3 센트리 깨우기 시험 스위치 = " + (on ? "켬" : "끔"));
                return null;
            }
            if (params.has("allStop") && prefs != null) {
                boolean on = params.optBoolean("allStop");
                prefs.edit().putBoolean("all_stop", on).apply();
                if (on) { try { detectStop(); } catch (Throwable ig) {} }   // 돌던 카메라 감지 즉시 정지
                postToD1("RELAY", "모든 자동 기능 정지(킬 스위치) = " + on);
                return null;
            }
            // ★620 주 사용권 폰 근접 신호 — 폰이 접근/재실 시 true(주기 갱신), 이탈 시 false. 첫 수신부터 near_proto 활성(이후 근접 게이트 적용).
            if (params.has("phoneNear") && prefs != null) {
                boolean near = params.optBoolean("phoneNear");
                boolean changed = near != phoneNearFlag;
                phoneNearFlag = near; phoneNearTs = System.currentTimeMillis();
                if (!prefs.getBoolean("near_proto", false)) prefs.edit().putBoolean("near_proto", true).apply();
                if (!near && lastDriving != 1) nearDriveLatch = false;
                persistNear();   // ★624 재시작에도 유지
                if (relay != null) { relay.putStatus("phone_near", near ? 1 : 0); relay.putStatus("phone_near_ts", phoneNearTs); }
                if (changed) postToD1("RELAY", "주 사용권 폰 근접 = " + near + (nearDriveLatch ? " (주행 래치 유지)" : ""));

                return null;
            }
            // ★642 접근 시 화면 띄우기 설정(폰 앱 리모컨) — {mode:off|app|split, p1, p2, pct}. 저장만, 실행은 다음 접근 때.
            if (params.has("approachLaunch") && prefs != null) {
                org.json.JSONObject al = params.optJSONObject("approachLaunch");
                prefs.edit().putString("approach_launch", al == null ? "" : al.toString()).apply();
                if (relay != null) relay.putStatus("approach_launch", al == null ? "" : al.toString());
                postToD1("SPLIT", "접근 시 화면 설정 저장 → " + (al == null ? "끔" : al.toString()));
                return null;
            }

            // ★v115 ICC 효과음 설정 저장(폰에서 동기) / 미리듣기
            org.json.JSONObject icc = params.optJSONObject("iccSound");
            if (icc != null && prefs != null) {
                android.content.SharedPreferences.Editor e = prefs.edit();
                if (icc.has("on")) e.putBoolean("icc_sound_on", icc.getBoolean("on"));
                if (icc.has("idx")) e.putInt("icc_sound_idx", icc.getInt("idx"));
                if (icc.has("vol")) e.putInt("icc_sound_vol", icc.getInt("vol"));
                if (icc.has("channel")) e.putInt(KEY_ICC_CHANNEL, Math.max(0, Math.min(CH_STREAM.length - 1, icc.getInt("channel"))));   // ★2026-09-16 채널 선택 — LANE도 같은 풀 공유라 함께 적용
                e.apply();
                if (icc.has("channel")) initIccAudio();
                postToD1("ICC", "설정 on=" + prefs.getBoolean("icc_sound_on", false) + " idx=" + prefs.getInt("icc_sound_idx", 0) + " vol=" + prefs.getInt("icc_sound_vol", 70) + " channel=" + CH_LABEL[chIdx(KEY_ICC_CHANNEL)]);
                return null;
            }
            // ★차로중앙유지 알림 설정(폰 동기) — TJA_ICA 3↔2 전이 소리/음량.
            org.json.JSONObject lane = params.optJSONObject("laneAlert");
            if (lane != null && prefs != null) {
                android.content.SharedPreferences.Editor e = prefs.edit();
                if (lane.has("on")) e.putBoolean("lane_alert_on", lane.getBoolean("on"));
                if (lane.has("lostIdx")) e.putInt("lane_lost_idx", lane.getInt("lostIdx"));
                if (lane.has("recoverOn")) e.putBoolean("lane_recover_on", lane.getBoolean("recoverOn"));
                if (lane.has("recoverIdx")) e.putInt("lane_recover_idx", lane.getInt("recoverIdx"));
                if (lane.has("vol")) e.putInt("lane_vol", lane.getInt("vol"));
                if (lane.has("muteStop")) e.putBoolean("lane_mute_stop", lane.getBoolean("muteStop"));   // ★완전정차 시 알림 억제
                if (lane.has("muteTurn")) e.putBoolean("lane_mute_turn", lane.getBoolean("muteTurn"));   // ★깜빡이 켜짐 시 알림 억제
                if (lane.has("channel")) e.putInt(KEY_ICC_CHANNEL, Math.max(0, Math.min(CH_STREAM.length - 1, lane.getInt("channel"))));   // ★2026-09-16 채널 선택 — playIccSound 공유라 ICC 효과음도 함께 바뀜
                e.apply();
                if (lane.has("channel")) initIccAudio();
                postToD1("LANE", "설정 on=" + prefs.getBoolean("lane_alert_on", false) + " lost=" + prefs.getInt("lane_lost_idx", 1) + " recover=" + prefs.getBoolean("lane_recover_on", false) + "/" + prefs.getInt("lane_recover_idx", 0) + " vol=" + prefs.getInt("lane_vol", 70) + " 억제:정차=" + prefs.getBoolean("lane_mute_stop", false) + " 깜빡이=" + prefs.getBoolean("lane_mute_turn", false) + " channel=" + CH_LABEL[chIdx(KEY_ICC_CHANNEL)]);
                return null;
            }
            if (params.has("drlParkOff") && prefs != null) {   // ★v145 주차 시 주간주행등 끄기 토글 동기
                boolean v = params.getBoolean("drlParkOff");
                prefs.edit().putBoolean("drl_park_off", v).apply();
                postToD1("DRL", "주차 시 DRL 끄기 = " + v);
                // ★v572 토글을 끄면 **즉시 되돌린다** — 종전엔 껐던 DRL이 그대로 남아 사용자가 "설정을 껐는데도 안 켜진다"며
                //   껐다 켰다를 반복하다 포기했다(09-23 id189 실측). 우리가 끈 경우에만 복원.
                if (!v && drlWeTurnedOff) {
                    drlWeTurnedOff = false;
                    String rr = callIntMethod(deviceByName("android.hardware.bydauto.light.BYDAutoLightDevice"), "setDayTimeLightState", 1);
                    postToD1("DRL", "토글 해제 — DRL 켜기(1) 복원 → " + rr);
                }
                // ★v574 반대쪽도 즉시 반영 — 572에서 "끄면 즉시 켜기"만 넣어 **켜면 아무 일도 안 나는** 비대칭이 생겼다.
                //   끄기는 drive_end 전이에서만 발사되므로, 이미 주차 중인 차는 다음 주행→주차 때까지 아무 변화가 없다.
                //   사용자 눈엔 "끄면 켜지는데 켜면 안 먹는" 고장으로 보인다. 주차 중이면 이 자리에서 바로 끈다.
                if (v && !drlWeTurnedOff && lastGearVal == 3 && lastDriving != 1) {
                    String rr = callIntMethod(deviceByName("android.hardware.bydauto.light.BYDAutoLightDevice"), "setDayTimeLightState", 2);
                    drlWeTurnedOff = true;
                    postToD1("DRL", "토글 설정(주차 중) — DRL 끄기(2) 즉시 적용 → " + rr);
                }
                return null;
            }
            if (params.has("startPreset") && relay != null) {   // ★v149 시동 시 자동적용 프리셋 동기(시트·선쉐이드·창문 등 RAW 명령 목록)
                org.json.JSONObject sp = params.optJSONObject("startPreset");
                boolean on = sp != null && sp.optBoolean("on", false);
                org.json.JSONArray cmds = sp != null ? sp.optJSONArray("cmds") : null;
                org.json.JSONObject adv = sp != null ? sp.optJSONObject("adv") : null;   // ★v403 고급 규칙
                String advStr = (adv != null && adv.length() > 0) ? adv.toString() : "";
                relay.setStartPreset(on, cmds != null ? cmds.toString() : null);
                relay.setStartPresetAdv(advStr);
                if (prefs != null) { prefs.edit().putBoolean("start_preset_on", on).putString("start_preset_cmds", cmds != null ? cmds.toString() : "").putString("start_preset_adv", advStr).apply(); }   // 릴레이 재시작 후 복원
                postToD1("RELAY", "시동 프리셋 동기: on=" + on + " cmds=" + (cmds != null ? cmds.length() : 0) + "개 adv=" + (advStr.isEmpty() ? "없음" : advStr.length() + "B"));
                return null;
            }
            if (params.has("kickZone") && prefs != null) {   // ★킥 범퍼존/스윙시간 런타임 튠 — de-warp로 crop 좌표가 바뀌어 구 70/85% 문턱이 안 닿음(실측 킥 최대 55%). 다음 킥디텍터 init부터 반영
                org.json.JSONObject kz = params.getJSONObject("kickZone");
                android.content.SharedPreferences.Editor e = prefs.edit();
                if (kz.has("enter")) e.putFloat("kick_zone_enter", (float) kz.getDouble("enter"));
                if (kz.has("deep")) e.putFloat("kick_zone_deep", (float) kz.getDouble("deep"));
                if (kz.has("dwellMin")) e.putInt("kick_dwell_min", kz.getInt("dwellMin"));
                if (kz.has("dwellMax")) e.putInt("kick_dwell_max", kz.getInt("dwellMax"));
                if (kz.has("personMot")) e.putInt("kick_person_mot", kz.getInt("personMot"));   // ★킥 스파이크 최소 모션%
                if (kz.has("personDwell")) e.putInt("kick_person_dwell", kz.getInt("personDwell"));   // ★체류 요구 ms(걸어들어오자마자 오픈 방지)
                if (kz.has("kickFsm")) e.putBoolean("kick_fsm", kz.getBoolean("kickFsm"));   // ★발 스윙 FSM 트리거 포함(앱 '발 스윙(킥)도 감지' 토글, 663)
                e.apply();
                postToD1("KICK", "트리거 설정 personMot=" + prefs.getInt("kick_person_mot", 15) + "% personDwell=" + prefs.getInt("kick_person_dwell", 1200) + "ms kickFsm=" + prefs.getBoolean("kick_fsm", false) + " (personMot/Dwell/kickFsm=즉시 반영, 범퍼존은 다음 카메라 세션)");
                return null;
            }
            if (params.has("kickDewarp") && prefs != null) {   // ★de-warp A/B·튜닝(DEWARP-00-PLAN §6) — 다음 킥디텍터 init(카메라 세션 시작)부터 반영
                org.json.JSONObject kdw = params.getJSONObject("kickDewarp");
                android.content.SharedPreferences.Editor e = prefs.edit();
                if (kdw.has("on")) e.putBoolean("kick_dewarp", kdw.getBoolean("on"));
                if (kdw.has("fov")) e.putFloat("kick_fov", (float) kdw.getDouble("fov"));
                if (kdw.has("margin")) e.putFloat("kick_margin", (float) kdw.getDouble("margin"));
                e.apply();
                postToD1("KICK", "de-warp 설정 on=" + prefs.getBoolean("kick_dewarp", false) + " fov=" + prefs.getFloat("kick_fov", 130f) + " margin=" + prefs.getFloat("kick_margin", 0.6f) + " (다음 카메라 세션부터 반영)");
                return null;
            }
            if (params.has("playSound")) {   // 미리듣기 — 헤드유닛 스피커로 재생(포커스 미요청)
                int idx = params.getInt("playSound");
                float vol = (params.has("vol") ? params.getInt("vol") : (prefs != null ? prefs.getInt("icc_sound_vol", 70) : 70)) / 100f;
                playIccSoundRaw(idx, vol);   // ★620 미리듣기는 게이트 예외
                return null;
            }

            // 도메인 구독: statistic 등 미구독 도메인을 Proxy 리스너로 구독 → 이벤트를 DILINK_EVT로 로깅("구독 후 읽기")
            String sub = params.optString("subscribe");
            if (sub != null && !sub.isEmpty()) { String r = subscribeDomain(sub); postToD1("DILINK_EVT", "subscribe " + sub + " → " + r); return r.startsWith("OK") || r.startsWith("이미") ? null : r; }

            // ★리모컨 패널이 열렸다는 신호 — 업로드 주기를 20초간 600ms로 올린다(주차 idle 기본 15초라 상태가 늦게 반영).
            //   2026-09-11 운영자 "양방향 실시간 동기화가 다 안 되네". 앱이 패널 열 때 1회 보낸다.
            if (params.has("fastPoll")) { if (relay != null) relay.kickFastPoll(); requestAllStatus("리모컨 열림"); return null; }

            // 상태폴 설정: [{key,device,id}] 저장 → 폴 스레드가 읽어 relay state로 push (배터리/주행/기어/잠금…)
            org.json.JSONArray poll = params.optJSONArray("statusPoll");
            if (poll != null) { statusPollCfg = poll; startStatusPoll();
                postToD1("DILINK_RAW", "statusPoll 설정 " + poll.length() + "개"); return null; }

            // wake 킵얼라이브 조정: {"wakeGuard":{"maxMin":240,"cutoffMv":11800}} — 재설치 없이 배터리보호 튜닝
            // 배터리 보호 설정: 헤드유닛 앱 다이얼로그와 동일하게 prefs에 저장 → 적용(아이폰이 보내도 재시작 후 유지).
            org.json.JSONObject wg = params.optJSONObject("wakeGuard");
            if (wg != null && prefs != null) {
                android.content.SharedPreferences.Editor e = prefs.edit();
                if (wg.has("enabled")) e.putBoolean(KEY_WAKE_ENABLED, wg.getBoolean("enabled"));
                if (wg.has("maxHour")) e.putInt(KEY_WAKE_MAXHOUR, wg.getInt("maxHour"));
                if (wg.has("sustainMin")) e.putInt(KEY_WAKE_SUSTAINMIN, wg.getInt("sustainMin"));
                e.apply();
                applyWakeSettings();
                return null;
            }

            // ★차종옵션 감지 — device.hasFeature("이름")(1=지원). BydAutoStart 방식(실차검증). {"device":"setting","hasFeature":"DriverSeatVentilating"}
            String hasFeat = params.optString("hasFeature");
            if (hasFeat != null && !hasFeat.isEmpty()) {
                String dn = params.optString("device", "setting");
                Object dev = deviceByName(dn);
                String out;
                if (dev == null) out = "device null:" + dn;
                else { try { out = String.valueOf(((Number) dev.getClass().getMethod("hasFeature", String.class).invoke(dev, hasFeat)).intValue()); }
                       catch (Throwable t) { Throwable c=(t instanceof java.lang.reflect.InvocationTargetException&&t.getCause()!=null)?t.getCause():t; out = "실패:"+c.getClass().getSimpleName()+":"+c.getMessage(); } }
                postToD1("CAPS", dn + ".hasFeature(" + hasFeat + ")=" + out);
                return null;
            }

            // readFeature: 한 feature를 지정(또는 여러) 디바이스에서 읽어 결과 로그 — 어느 디바이스가 값을 주는지 발견용
            if (params.has("readFeature")) {
                int fid = params.getInt("readFeature");
                String dn0 = params.optString("device");
                String[] devs = dn0.isEmpty()
                        ? new String[]{"instrument","setting","charging","power","gearbox","statistic","doorlock","bodywork"}
                        : new String[]{dn0};
                StringBuilder sb = new StringBuilder();
                for (String dn : devs) { Object d = deviceByName(dn); sb.append(dn).append("=").append(d == null ? "null" : naFmt(getFeatureInt(d, fid))).append(" | "); }
                postToD1("DILINK_RAW", "readFeature[" + fid + "]: " + sb);
                return null;
            }

            // (구 method 핸들러 제거 — value 단일int만 받아 args 배열(선쉐이드 pct·창문 area,state)을 씹고
            //  실패해도 null 반환하던 버그. 아래 '1) 메서드 호출' 블록이 args를 정확히 처리하고 실제 결과를 반환한다.)

            String devName = params.optString("device");
            Object dev = deviceByName(devName);
            if (dev == null) return "device 없음:" + devName;
            int value = params.optInt("value", 0);
            StringBuilder log = new StringBuilder();

            // 3) feature write: set(int[]{featureId}, EV{intValue})
            String feature = params.optString("feature");
            if ((feature != null && !feature.isEmpty()) || params.has("featureId")) {
                int fid = params.has("featureId") ? params.getInt("featureId") : featureIdByName(feature);
                String r = writeOne(dev, fid, value, log);
                postToD1("DILINK_RAW", devName + ".set([" + (feature.isEmpty() ? fid : feature + "(" + fid + ")") + "],iv=" + value + ") → " + r + " " + log);
                if (relay != null) relay.setDiag("labret", r);   // ★성공(OK)도 _labret 노출 — 앱 '반환' 칸이 성공 시 비던 버그 수정(실패만 뜨던 것)
                return r.startsWith("OK") ? null : r;
            }
            // 4) base set(devType, featureId, value)
            String baseFeature = params.optString("baseFeature");
            // ★635 D5 공조 전원 — 앱 '공조 즉시 끄기'가 baseFeature AC_POWER_STATE_SET을 보내는데 D5는 Stub!(09-26까지 11대 70건) → 명명 경로로.
            if (DiLink5Support.isDiLink5() && "AC_POWER_STATE_SET".equals(baseFeature)) return d5AcPower(dev, value);
            // ★637 D5 공조 화면 '즉시 반영'(iOS·안드 공통, 캡 아닌 직접 RAW) — D3 baseFeature는 D5 Stub! → 명명 메서드로 번역.
            //   온도=CTRL_SOURCE(1)→TEMP_MAIN(℃×2)→TEMP_DEPUTY(℃×2) 3연속으로 오므로 MAIN만 setAcTemperature(0,℃)로, 나머지 둘은 무동작 성공.
            if (DiLink5Support.isDiLink5()) {
                if ("AC_TEMP_MAIN_SET".equals(baseFeature)) return d5SetTemp(dev, Math.round(value / 2f));
                if ("AC_CTRL_SOURCE_SET".equals(baseFeature) || "AC_TEMP_DEPUTY_SET".equals(baseFeature)) { postToD1("DILINK_RAW", "[D5]" + baseFeature + " 생략(온도는 TEMP_MAIN에서 setAcTemperature로)"); return null; }
                if ("AC_CYCLE_MODE_SET".equals(baseFeature)) { String rr = callIntMethod(dev, "setAcCycleMode", 0, value); postToD1("DILINK_RAW", "[D5]순환 AC_CYCLE_MODE_SET→setAcCycleMode(0," + value + ") → " + rr); return rr.startsWith("OK") ? null : rr; }
            }
            if ((baseFeature != null && !baseFeature.isEmpty()) || params.has("baseId")) {
                int fid = params.has("baseId") ? params.getInt("baseId") : featureIdByName(baseFeature);
                // ★공조 전원을 사람이 직접 끈 것은 '무탑승 자동 종료'가 아니다(2026-09-11 실차 오발화) — 시각을 남긴다.
                if ("AC_POWER_STATE_SET".equals(baseFeature) && relay != null) relay.markAcPowerManual();
                // ★공조 계열 쓰기 직후 전 게터 스냅샷(2026-09-11) — 어느 게터가 그 타일의 진짜 상태인지
                //   '쓰기 전후 비교'로만 가릴 수 있다(BYD OK(0)는 가짜 성공). 저녁 실차 토글 1회로 확정용.
                if (baseFeature.startsWith("AC_")) { final String bf = baseFeature; final int bv = value;
                    new Thread(() -> { sleepQuiet(1200); acSnapshot(bf + "=" + bv); }, "acsnap").start(); }
                String r = baseSet(dev, fid, value);
                postToD1("DILINK_RAW", devName + ".baseSet[" + (baseFeature.isEmpty() ? fid : baseFeature + "(" + fid + ")") + "]=" + value + " → " + r);
                if (relay != null) relay.setDiag("labret", r);   // ★성공(OK)도 _labret 노출 — 위 feature write와 동일 수정
                return r.startsWith("OK") ? null : r;
            }
            // ★v100 callRet — 메서드 호출 후 '리턴값'을 로그(getDoorLockStatus 등 검증센서 읽기용). callIntMethod는 리턴 버림.
            String callRet = params.optString("callRet");
            if (callRet != null && !callRet.isEmpty()) {
                org.json.JSONArray ra = params.optJSONArray("args");
                int n = ra == null ? 0 : ra.length();
                Class<?>[] ts = new Class<?>[n]; Object[] vs = new Object[n];
                for (int i = 0; i < n; i++) { ts[i] = int.class; vs[i] = ra.getInt(i); }
                String out;
                try {
                    Object ret = dev.getClass().getMethod(callRet, ts).invoke(dev, vs);
                    String iv = "";
                    if (ret != null && ret.getClass().getSimpleName().contains("EventValue")) {
                        try { iv = " intValue=" + ret.getClass().getField("intValue").getInt(ret); } catch (Throwable ignore) {}
                    }
                    out = callRet + java.util.Arrays.toString(vs) + " → ret=" + ret + iv;
                } catch (Throwable t) {
                    Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause()!=null)?t.getCause():t;
                    out = callRet + " 실패:" + c.getClass().getSimpleName() + ":" + c.getMessage();
                }
                postToD1("DILINK_RAW", devName + "." + out);
                return null;
            }
            // 1) 메서드 호출
            String method = params.optString("method");
            org.json.JSONArray aj = params.optJSONArray("args");
            int[] args = new int[aj == null ? 0 : aj.length()];
            for (int i = 0; i < args.length; i++) args[i] = aj.getInt(i);
            // ★10-10 FB2253 아토3 선루프 틸트 버튼 상태 — 차가 틸트 여부를 알려 주는 값이 확정 안 돼, 릴레이가 마지막으로 보낸 선루프 명령으로 정한다(253=틸트, 0·50·100=닫힘 쪽).
            //   차의 물리 버튼으로 조작하면 어긋날 수 있다(그때는 한 번 더 누르면 맞춰진다).
            if (method.equals("setMoonRoofState") && args.length >= 1 && relay != null && (args[0] == 253 || args[0] == 0 || args[0] == 50 || args[0] == 100))
                relay.putStatus("sunroof_tilt_st", args[0] == 253 ? 253 : 0);
            // ★DiLink5 시그니처 번역 (BydAutoPanel 역공학 2026-09-04) — 프리셋/앱은 DiLink3 이름으로 보내고 릴레이가 D5용 메서드로 변환.
            //   좌석 통풍/열선 setSeatHeating/VentilatingState(seat,val)은 D5도 동일 시그니처 → 번역 불필요. 핸들열선·선쉐이드만 다름.
            if (DiLink5Support.isDiLink5() && dev != null) {
                if (method.equals("setSteeringWheelHeatingState") && args.length >= 1) {
                    // D3=state(1끄기/2켜기)만. D5=state(2켜기/1끄기)+setSteeringWheelHeatingGear(1~3단). 프리셋 val 1/2 → 끄기/켜기(기본 2단).
                    if (args[0] <= 1) { String rr = callIntMethod(dev, "setSteeringWheelHeatingState", 1); postToD1("DILINK_RAW", "[D5]핸들열선 끄기 → " + rr); return rr.startsWith("OK") ? null : rr; }
                    callIntMethod(dev, "setSteeringWheelHeatingState", 2);
                    try { Thread.sleep(120); } catch (Throwable ig) {}
                    String rr = callIntMethod(dev, "setSteeringWheelHeatingGear", 2);
                    postToD1("DILINK_RAW", "[D5]핸들열선 켜기(state2+gear2) → " + rr);
                    return rr.startsWith("OK") ? null : rr;
                }
                if (method.equals("setBodyWindowCtrlState") && args.length >= 2) {
                    // ★635 앱(자동적용·수동 창문·이탈 닫기)은 D3 명령 setBodyWindowCtrlState(area, state)를 보낸다 — D5엔 없음(NPE Stub, 09-28까지 5대 65건).
                    //   D5=setWindowOpenPercent(area, %)(창문 일괄에서 쓰는 검증 경로). D3 state 2=닫기·5=환기·4=반개·1=완전 → 0/20/50/100%.
                    int st = args[1], pct = st == 2 ? 0 : st == 5 ? 20 : st == 4 ? 50 : st == 1 ? 100 : -1;
                    if (pct < 0) { postToD1("DILINK_RAW", "[D5]창문 state=" + st + " 매핑 없음 — 무시"); return "D5 창문 state " + st + " 미지원"; }
                    String rr = callIntMethod(dev, "setWindowOpenPercent", args[0], pct);
                    postToD1("DILINK_RAW", "[D5]창문 area" + args[0] + " state" + st + "→" + pct + "% (setWindowOpenPercent) → " + rr);
                    return rr.startsWith("OK") ? null : rr;
                }
                if (method.equals("setAcTemperature") && args.length == 4) return d5SetTemp(dev, args[1]);
                if ((method.equals("setSunshadeState") || method.equals("nudgeSunshadeState")) && args.length >= 1) {
                    // D3=setSunshadeState(pct). D5=voiceCtlSunshadePanel(1열기/2닫기) on bodywork.
                    String rr = callIntMethod(dev, "voiceCtlSunshadePanel", args[0] > 0 ? 1 : 2);
                    postToD1("DILINK_RAW", "[D5]선쉐이드 " + (args[0] > 0 ? "열기" : "닫기") + "(voiceCtl) → " + rr);
                    return rr.startsWith("OK") ? null : rr;
                }
            }
            // ★선쉐이드 우회 열기: setSunshadeState(pct)를 "그냥 열기"로 보내면 차량이 씹을 때가 있어(같은 값/위치불명 재전송),
            //   닫기(0) → sleep → 열기(pct)로 값 변화를 강제. 헤드유닛 로컬 실행이라 iOS 백그라운드 제약과 무관.
            if (method.equals("nudgeSunshadeState") && args.length >= 1) {
                int pct = args[0];
                // ★센서 멱등 — 이미 목표 위치(±5%)면 close→open 자체를 안 하고 스킵. 열려있는데 또 nudge해서
                //   "닫히다 열림"이 반복되던 것(2026-08-19 실측: 열린 선쉐이드가 시동/명령마다 닫혔다 열림) 근본 차단.
                int curPct = (relay != null) ? relay.sunshadePct() : -1;
                // ★위치 미상(-1) — 재시작 직후(브레이크 시동 등)엔 sunshade_pct 센서가 아직 미보고 → 멱등 스킵 못해 오nudge(닫히다 열림).
                //   센서 보고를 최대 3초 기다렸다 판정. 이미 열려있으면 curPct 확정돼 스킵 → flicker 없음.
                if (curPct < 0 && relay != null) {
                    for (int w = 0; w < 15 && curPct < 0; w++) { try { Thread.sleep(200); } catch (Throwable ig) {} curPct = relay.sunshadePct(); }
                    postToD1("DILINK_RAW", "선쉐이드 위치 미상 → 센서 대기 후 curPct=" + curPct + " (목표 " + pct + ")");
                }
                if (curPct >= 0 && Math.abs(curPct - pct) <= 5) {
                    postToD1("DILINK_RAW", devName + ".nudgeSunshadeState[" + pct + "] 스킵 — 이미 " + curPct + "% (이동 불필요)");
                    lastSunshadeNudgePct = pct; lastSunshadeNudgeTs = System.currentTimeMillis();
                    return null;
                }
                // ★중복 nudge 디바운스 — 앱 시동켜기 시 (A)doStartClimate 직접 + (B)릴레이 부트온 프리셋이 같은 %를
                //   ~4초 간격으로 이중 발사 → 닫힘→열림→닫힘→열림("열림/닫힘/열림")으로 보이던 것 방지.
                //   같은 pct가 15초 내 재도착하면 이미 그 동작이 진행/완료됐으므로 스킵(근본: 두 경로가 같은 목표를 씀).
                long now = System.currentTimeMillis();
                if (pct == lastSunshadeNudgePct && now - lastSunshadeNudgeTs < 15000) {
                    postToD1("DILINK_RAW", devName + ".nudgeSunshadeState[" + pct + "] 중복 스킵 (" + ((now - lastSunshadeNudgeTs) / 1000) + "s 전 동일 %)");
                    return null;
                }
                lastSunshadeNudgePct = pct; lastSunshadeNudgeTs = now;
                // ★833 비틀런처처럼 목표 %로 바로 보낸다(운영자 10-09 "항상 닫았다 열어서 오동작") — 4초 안에 위치가 움직이면 끝.
                //   차가 씹었을 때(08-26 실측: OK인데 미이동)만 아래 닫기→열기 우회로 넘어간다. 닫기(0)는 움직이지 않아도 우회하지 않는다.
                String rd = callIntMethod(dev, "setSunshadeState", pct);
                int startPct = curPct, nowPct = curPct;
                for (int w = 0; w < 20; w++) {
                    try { Thread.sleep(200); } catch (InterruptedException ignore) {}
                    nowPct = relay != null ? relay.sunshadePct() : -1;
                    if (nowPct >= 0 && (Math.abs(nowPct - pct) <= 5 || (startPct >= 0 && Math.abs(nowPct - startPct) >= 3))) break;
                }
                boolean moved = nowPct >= 0 && (Math.abs(nowPct - pct) <= 5 || (startPct >= 0 && Math.abs(nowPct - startPct) >= 3));
                postToD1("DILINK_RAW", devName + ".setSunshadeState[" + pct + "] (바로 이동) → " + rd + " · 위치 " + startPct + "→" + nowPct + (moved ? " 움직임" : " 안 움직임"));
                if (moved || pct <= 0) return rd.startsWith("OK") ? null : rd;
                // ★v319-hotfix: 위치 미상이라도 '열기(pct>0)'는 close(0)→open 우회로 확실히 이동시킨다.
                //   직접 setSunshadeState는 차가 씹어(가짜OK) 안 열리던 것(2026-08-26 실측: 접근/수동 100% 전송해도 setSunshadeState OK인데 미이동).
                //   재시작 직후 센서 미보고(-1)로 폴백 타면 영영 안 열리던 데드락 해소. 이미 열려있으면 닫았다 열려 미세 flicker 가능하나 안 열리는 것보다 나음.
                String r0 = callIntMethod(dev, "setSunshadeState", 0);
                postToD1("DILINK_RAW", devName + ".setSunshadeState[0] (우회 닫기) → " + r0);
                try { Thread.sleep(2000); } catch (InterruptedException ignore) {}
                String r1 = callIntMethod(dev, "setSunshadeState", pct);
                postToD1("DILINK_RAW", devName + ".setSunshadeState[" + pct + "] (우회 열기) → " + r1);
                return r1.startsWith("OK") ? null : r1;
            }
            // ★창문 4창 일괄(2026-09-13, 메밀소바 실차: 개별 4개 연속 발사 시 씨라 MCU가 랜덤으로 1~2개 씹음 → 다시 눌러야 됨).
            //   setAllWindowState(4창 한 번에)로 MCU 단일 처리 → 씹힘 제거. 없거나 실패하면 개별(winFallback) 순차 300ms 폴백(연속 무간격보다 완화).
            // ★공조 전원 D5(BydController 2.1.1) — start()/stop() 2메서드. cycle 타일 v=1→start·0→stop.
            if ("__ACPOWER__".equals(method)) return d5AcPower(dev, args.length > 0 ? args[0] : 1);
            if ("setAllWindowState".equals(method)) {
                int st0 = args.length > 0 ? args[0] : 2;   // 매니페스트 args[0]: 닫기=2, 환기=ventV(그 외)
                // ★씨라(D5)=BydController 2.1.1 검증: setWindowOpenPercent(area 1~4, percent). 환기=20%, 닫기=0%(5% 단위). 개별 4창 순차(250ms) — MCU 연속 씹힘 회피.
                if (DiLink5Support.isDiLink5()) {
                    int pct = (st0 == 2) ? 0 : 20;
                    StringBuilder sb = new StringBuilder("창문 D5 setWindowOpenPercent pct=" + pct + " ");
                    for (int a = 1; a <= 4; a++) { String rr = callIntMethod(dev, "setWindowOpenPercent", a, pct); sb.append(a).append("=").append(rr.startsWith("OK") ? "ok" : rr).append(" "); sleepQuiet(250); }
                    postToD1("DILINK_RAW", sb.toString()); return null;
                }
                // 돌핀(D3): 4창 일괄 setAllWindowState 시도 → 실패 시 개별(winFallback) 순차.
                String r = callIntMethod(dev, method, args);
                if (r.startsWith("OK")) { postToD1("DILINK_RAW", "창문 일괄 setAllWindowState" + java.util.Arrays.toString(args) + " → " + r); return null; }
                String fb = params.optString("winFallback");
                if (!fb.isEmpty()) {
                    StringBuilder sb = new StringBuilder("setAllWindowState 실패(" + r + ") → 개별 순차 " + fb + " ");
                    for (int a = 1; a <= 4; a++) { String rr = callIntMethod(dev, fb, a, st0); sb.append(a).append("=").append(rr.startsWith("OK") ? "ok" : rr).append(" "); sleepQuiet(300); }
                    postToD1("DILINK_RAW", sb.toString()); return null;
                }
                postToD1("DILINK_RAW", "setAllWindowState" + java.util.Arrays.toString(args) + " → " + r);
                return r.startsWith("OK") ? null : r;
            }
            String r = callIntMethod(dev, method, args);
            postToD1("DILINK_RAW", devName + "." + method + java.util.Arrays.toString(args) + " → " + r);
            return r.startsWith("OK") ? null : r;
        } catch (Throwable t) { return "RAW 실패:" + t; }
    }

    // ★브레이크 감각 쓰기 경로 탐색(2026-09-11) — 운영자: "브레이크 모드 컴포트/스포츠인데 조절이 안 돼".
    //   현황: adas.setBrakeFootSenseState(v)=OK(0) 가짜성공·무동작 / adas.baseSet(ADAS_CMD_BRAKE_FOOT_SENSE)=OK(-2147482648) 거부.
    //   게터 getBrakeFootSenseState()는 살아있다(실측 0=COMFORTABLE_GET) → **읽기 전후 비교만이 유일한 진실 판정**.
    private void brakeProbe() {
        // ★v2(2026-09-11 운영자 "같이 있는데 하나는 되고 하나는 안 된다고?") — 차 화면엔 스티어링 모드와 브레이크 모드가
        //   나란히 있다. 스티어링은 **setting.setSteerAssis**로 성공했으므로 브레이크도 setting 쪽 경로가 있을 가능성이 높다.
        //   1차 프로브는 adas 하나만 보고 접었는데, 그 근거였던 세터 덤프가 4000자에서 잘린 목록이었다(setting 232개 중 114개).
        //   → 이번엔 setting·adas의 brake/pedal 관련 메서드를 **리플렉션으로 전수 열거**하고, int 1인자 세터를 전부 눌러본다.
        String[] devNames = {"setting", "adas", "instrument", "bodywork", "gearbox"};
        StringBuilder sb = new StringBuilder("BRAKEPROBE v2 — brake/pedal 메서드 전수 + 게터 전후 비교\n");
        // ① 열거: 이름에 brake 또는 pedal이 들어간 메서드 전부(시그니처 포함)
        java.util.List<Object[]> setters = new java.util.ArrayList<>();   // {devName, dev, methodName}
        java.util.List<Object[]> getters = new java.util.ArrayList<>();
        for (String dn : devNames) {
            Object d = deviceByName(dn);
            if (d == null) continue;
            for (java.lang.reflect.Method m : d.getClass().getMethods()) {
                String n = m.getName(); String nl = n.toLowerCase();
                if (!nl.contains("brake") && !nl.contains("pedal")) continue;
                Class<?>[] pt = m.getParameterTypes();
                sb.append(dn).append('.').append(n).append('(').append(pt.length == 0 ? "" : (pt.length + "인자")).append(") ");
                if (n.startsWith("set") && pt.length == 1 && pt[0] == int.class) setters.add(new Object[]{dn, d, n});
                else if (n.startsWith("get") && pt.length == 0) getters.add(new Object[]{dn, d, n});
            }
        }
        sb.append("\n→ 1인자 세터 ").append(setters.size()).append("개 · 무인자 게터 ").append(getters.size()).append("개\n");
        // ② 스냅샷 헬퍼 — 모든 브레이크 게터를 한 줄로
        StringBuilder snap = new StringBuilder();
        for (Object[] g : getters) snap.append(g[2]).append('=').append(callGetInt0(g[1], (String) g[2])).append(' ');
        sb.append("시작 [").append(snap).append("]\n");
        postToD1("DILINK_RAW", sb.toString()); sb.setLength(0);
        // ③ 세터를 1,2로 눌러보고 매번 전체 게터 재판독 — 하나라도 값이 변하면 그게 진짜 경로다.
        for (Object[] s : setters) {
            for (int v : new int[]{1, 2}) {
                String r = callIntMethod(s[1], (String) s[2], v);
                sleepQuiet(800);
                StringBuilder after = new StringBuilder();
                for (Object[] g : getters) after.append(g[2]).append('=').append(callGetInt0(g[1], (String) g[2])).append(' ');
                sb.append(s[0]).append('.').append(s[2]).append('(').append(v).append(") → ").append(r)
                  .append(" · [").append(after).append("]\n");
                if (sb.length() > 2400) { postToD1("DILINK_RAW", sb.toString()); sb.setLength(0); }
            }
        }
        postToD1("DILINK_RAW", sb.length() > 0 ? sb.toString() : "BRAKEPROBE v2 끝");
    }
    // ★공조 게터 전수 스냅샷 — 쓰기 직후 어떤 값이 따라 움직였는지 본다.
    private void acSnapshot(String tag) {
        Object ac = deviceByName("ac");
        if (ac == null) return;
        StringBuilder sb = new StringBuilder("ACSNAP [" + tag + "] ");
        for (java.lang.reflect.Method m : ac.getClass().getMethods()) {
            if (!m.getName().startsWith("get") || m.getParameterTypes().length != 0) continue;
            if (m.getReturnType() != int.class) continue;
            sb.append(m.getName().substring(3)).append('=').append(callGetInt0(ac, m.getName())).append(' ');
        }
        if (relay != null) sb.append("| 이벤트 work_mode=").append(relay.acWorkMode());
        postToD1("DILINK_RAW", sb.toString());
    }

    // ★진짜 시동값 탐색(2026-09-11 운영자 "진짜 시동값과 ac를 구분지어야겠는데") —
    //   릴레이는 D3에서 AC_POWER_STATE 하나를 '시동'과 '공조 전원' 양쪽에 쓰고 있다. 리모컨으로 공조를 끄면
    //   시동이 꺼진 걸로 오인돼 무탑승 마무리까지 발화했다(오늘 아침). 두 신호를 물리적으로 분리해야 한다.
    //   방법: **AC_POWER_STATE가 바뀌는 순간** 후보 게터를 전부 찍어, 공조만 껐을 때 안 바뀌고
    //   실제 시동을 껐을 때만 바뀌는 값을 고른다.
    void ignSnapshot(String tag) {
        String[][] devs = {
                {"bodywork", "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"},
                {"power", "android.hardware.bydauto.power.BYDAutoPowerDevice"},
                {"energy", "android.hardware.bydauto.energy.BYDAutoEnergyDevice"},
                {"gearbox", "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"},
                {"charging", "android.hardware.bydauto.charging.BYDAutoChargingDevice"},
                {"setting", "android.hardware.bydauto.setting.BYDAutoSettingDevice"},
                {"instrument", "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"},
        };
        StringBuilder sb = new StringBuilder("IGNSNAP [" + tag + "]\n");
        for (String[] d : devs) {
            Object dev = deviceByName(d[1]);
            if (dev == null) continue;
            StringBuilder line = new StringBuilder();
            for (java.lang.reflect.Method m : dev.getClass().getMethods()) {
                String n = m.getName(); String nl = n.toLowerCase();
                if (!n.startsWith("get") || m.getParameterTypes().length != 0 || m.getReturnType() != int.class) continue;
                // 시동/전원 후보만 — 이름 기반(전 게터를 다 찍으면 로그가 터진다)
                if (!(nl.contains("power") || nl.contains("start") || nl.contains("acc")
                        || nl.contains("ign") || nl.contains("energystate") || nl.contains("systemstate")
                        || nl.contains("gear") || nl.contains("handbrake") || nl.contains("bcm"))) continue;
                int v = callGetInt0(dev, n);
                if (v == Integer.MIN_VALUE) continue;
                line.append(n.substring(3)).append('=').append(v).append(' ');
            }
            if (line.length() > 0) sb.append(d[0]).append(": ").append(line).append('\n');
            if (sb.length() > 2600) { postToD1("DILINK_RAW", sb.toString()); sb.setLength(0); }
        }
        postToD1("DILINK_RAW", sb.toString());
    }

    // ★재시작 후 빈 상태 채우기(2026-09-11) — 공조·바디 등 상당수 값이 **이벤트로만** 온다. 릴레이가 재시작하면
    //   차에서 뭔가 바뀌기 전까지 relay_state가 비어 리모컨 타일이 아무것도 못 보여준다(445 실측: ac_* 전부 undefined).
    //   BYD SDK의 `setAllStatus()`는 "현재 상태를 전부 다시 방송" 요청 → 구독 중인 리스너로 값이 다시 들어온다.
    void requestAllStatus(String why) {
        String[] devs = {
                "android.hardware.bydauto.ac.BYDAutoAcDevice",
                "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
                "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
                "android.hardware.bydauto.setting.BYDAutoSettingDevice",
                "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
                "android.hardware.bydauto.charging.BYDAutoChargingDevice",
                "android.hardware.bydauto.power.BYDAutoPowerDevice",
                "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
        };
        StringBuilder sb = new StringBuilder("ALLSTATUS(" + why + ") ");
        for (String cn : devs) {
            Object d = deviceByName(cn);
            if (d == null) { sb.append(cn.substring(cn.lastIndexOf('.') + 1)).append("=null "); continue; }
            try {
                java.lang.reflect.Method m = d.getClass().getMethod("setAllStatus");
                m.invoke(d);
                sb.append(cn.substring(cn.lastIndexOf('.') + 1)).append("=OK ");
            } catch (Throwable t) {
                sb.append(cn.substring(cn.lastIndexOf('.') + 1)).append("=").append(t.getClass().getSimpleName()).append(' ');
            }
        }
        postToD1("RELAY", sb.toString());
        // ★씨라(D5) 제어 게터 진단(2026-09-13) — BydController/Overdrive에 없거나 씨라서 밀리는 6종 실값 확보용.
        //   메밀소바가 리모컨 열고 차 화면에서 주행모드/스티어링 등 바꿀 때마다 이 값이 남아 매핑 확정(get 반환↔모드).
        if (DiLink5Support.isDiLink5()) {
            try {
                Object en = deviceByName("energy"), st = deviceByName("setting"), ac = deviceByName("ac");
                StringBuilder d = new StringBuilder("D5DIAG ");
                d.append("operMode=").append(callGetInt0(en, "getOperationMode"));
                d.append(" roadSurf=").append(callGetInt0(en, "getRoadSurfaceMode"));
                d.append(" steer=").append(callGetInt0(st, "getSteerAssis"));
                d.append(" brake=").append(callGetInt0(adasDevice, "getBrakeFootSenseState"));
                d.append(" brakePedal=").append(callGetInt0(st, "getBrakePedalMode"));
                d.append(" regen=").append(callGetInt0(st, "getEnergyFeedback"));
                d.append(" acStart=").append(callGetInt0(ac, "getAcStartState"));
                d.append(" acComp=").append(callGetInt0(ac, "getAcCompressorMode"));
                d.append(" acCycle=").append(callGetInt0(ac, "getAcCycleMode"));
                d.append(" acVent=").append(callGetInt0(ac, "getAcVentilationState"));
                d.append(" acWind=").append(callGetInt0(ac, "getAcWindLevel"));
                d.append(" defRear=").append(callGetInt0(ac, "getElectricDefrostState"));
                postToD1("D5DIAG", d.toString());
            } catch (Throwable ignore) {}
        }
    }

    // ★시트 스위치 신호 펄스 프로브 — 메모리 없는 전동시트에 "버튼 누름"을 헤드유닛이 대신 보내 상대 이동시키는 가설 검증.
    //   근거: FEAT 덤프에 SET_MAINDRIVER_SEAT_*_SIGNAL_SET 4종 + SET_DRIVER_SEAT_*_SIGNAL_SET 2종 존재, 위치 이벤트는 0건(센서 없음 추정).
    //   판정: baseSet 반환(OK(0)=수용 / OK(-2147482648)=거부) + 운영자 육안(시트 움직임). 절대 위치 SET(abs)도 같은 틀로.
    private static final String[][] SEAT_AXES = {
            {"horizontal", "SET_MAINDRIVER_SEAT_HORIZONTAL_POSITION_SIGNAL_SET"},
            {"back",       "SET_MAINDRIVER_SEAT_BACK_SWITCH_SIGNAL_SET"},
            {"basin",      "SET_MAINDRIVER_SEAT_BASIN_SWITCH_SIGNAL_SET"},
            {"height",     "SET_MAINFRIVER_SEAT_HEIGHT_SWITCH_SIGNAL_SET"},   // BYD 오타 그대로
            {"height2",    "SET_DRIVER_SEAT_HEIGHT_SIGNAL_SET"},
            {"back2",      "SET_DRIVER_SEAT_BACKREST_SIGNAL_SET"},
            {"balance",    "SET_DRIVER_SEAT_BALANCE_SIGNAL_SET"},
            {"abs_h",      "BODY_DRIVER_SEAT_HORIZONTAL_POSITION_SET"},      // 절대 위치(센서 없으면 무효 예상)
            {"abs_back",   "BODY_DRIVER_SEAT_BACKREST_POSITION_SET"},
            {"abs_height", "BODY_DRIVER_SEAT_HEIGHT_POSITION_SET"},
    };
    private void seatProbe(String rest) {
        StringBuilder sb = new StringBuilder("SEATPROBE ");
        String[] tok = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        if (tok.length == 0 || tok[0].equalsIgnoreCase("list")) {
            sb.append("사용법: SEATPROBE <axis> <value> [pulseMs≤500] [device=setting|bodywork]\n");
            for (String[] ax : SEAT_AXES) { int fid; try { fid = featureIdByName(ax[1]); } catch (Throwable t) { fid = -1; } sb.append(ax[0]).append(" = ").append(ax[1]).append("(").append(fid).append(")\n"); }
            sb.append("현재 gear=").append(lastGearVal).append(" speed=").append(lastSpeed).append(" speedLive=").append(speedLive);
            postToD1("DILINK_RAW", sb.toString()); return;
        }
        // 안전 게이트: P기어 + 정차
        boolean parked = lastGearVal == 3 && (Double.isNaN(lastSpeed) || lastSpeed <= 0.5);
        if (!parked) { postToD1("DILINK_RAW", "SEATPROBE 거부 — 주차(P·정차) 아님: gear=" + lastGearVal + " speed=" + lastSpeed); return; }
        String axis = tok[0].toLowerCase();
        String featName = null; for (String[] ax : SEAT_AXES) if (ax[0].equals(axis)) featName = ax[1];
        if (featName == null) { postToD1("DILINK_RAW", "SEATPROBE 알 수 없는 axis: " + axis + " (list로 확인)"); return; }
        int value; try { value = Integer.parseInt(tok[1]); } catch (Throwable t) { postToD1("DILINK_RAW", "SEATPROBE value 없음/오류"); return; }
        int pulseMs = 300; if (tok.length > 2) { try { pulseMs = Math.min(500, Math.max(50, Integer.parseInt(tok[2]))); } catch (Throwable ignored) {} }
        String devName = tok.length > 3 ? tok[3] : "setting";
        Object dev = deviceByName(devName);
        if (dev == null) { postToD1("DILINK_RAW", "SEATPROBE device null: " + devName); return; }
        int fid; try { fid = featureIdByName(featName); } catch (Throwable t) { postToD1("DILINK_RAW", "SEATPROBE feature 이름 없음: " + featName); return; }
        boolean isAbs = axis.startsWith("abs_");
        sb.append(axis).append(" ").append(featName).append("(").append(fid).append(") value=").append(value)
          .append(isAbs ? " [절대위치]" : " pulse=" + pulseMs + "ms").append(" dev=").append(devName).append("\n");
        String r1 = baseSet(dev, fid, value);
        sb.append("press → ").append(r1).append("\n");
        if (!isAbs) {
            sleepQuiet(pulseMs);
            String r2 = baseSet(dev, fid, 0);
            sleepQuiet(200);
            String r3 = baseSet(dev, fid, 0);   // '버튼 뗌' 2회 보장
            sb.append("release(0) → ").append(r2).append(" / ").append(r3).append("\n");
        }
        sb.append(r1.contains("-2147482648") ? "판정: SDK 거부(NA) — 이 경로 불가" : "판정: 수용됨(OK) — 시트가 움직였는지 육안 확인. 반환 OK는 가짜일 수 있음");
        postToD1("DILINK_RAW", sb.toString());
    }

    private void sleepQuiet(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    // ★전방 근접 거리 탐색 — radar 디바이스의 거리 게터를 1초마다 30회 읽어 변화를 본다.
    //   배열/리스트를 돌려주는 게터(getAllRadarDistance)는 원소별로 펼쳐 어느 인덱스가 전방인지 가린다.
    private void radarProbe() {
        Object rd = deviceByName("radar");
        if (rd == null) { postToD1("DILINK_RAW", "RADARPROBE: radar 디바이스 null"); return; }
        StringBuilder head = new StringBuilder("RADARPROBE — radar 메서드\n");
        java.util.List<java.lang.reflect.Method> gs = new java.util.ArrayList<>();
        for (java.lang.reflect.Method m : rd.getClass().getMethods()) {
            if (!m.getName().startsWith("get") || m.getParameterTypes().length != 0) continue;
            head.append(m.getName()).append("→").append(m.getReturnType().getSimpleName()).append(' ');
            gs.add(m);
        }
        postToD1("DILINK_RAW", head.toString());
        String prev = null;
        for (int i = 0; i < 30; i++) {
            StringBuilder line = new StringBuilder();
            for (java.lang.reflect.Method m : gs) {
                Object v;
                try { m.setAccessible(true); v = m.invoke(rd); } catch (Throwable t) { continue; }
                line.append(m.getName().substring(3)).append('=').append(fmtVal(v)).append(' ');
            }
            String cur = line.toString();
            if (!cur.equals(prev)) { postToD1("DILINK_RAW", "RADAR t=" + i + "s " + cur); prev = cur; }   // 변할 때만(스팸 방지)
            sleepQuiet(1000);
        }
        postToD1("DILINK_RAW", "RADARPROBE 끝");
    }
    // ★FCSR(앞차 출발 알림)·신호등 인식 실존 여부 — 후보 디바이스 클래스를 로드해보고, 있으면 관련 게터를 전부 읽는다.
    private void fcsrProbe() {
        String[] cands = {
                "android.hardware.bydauto.dipilot.BYDAutoDipilotDevice",
                "android.hardware.bydauto.adas.BYDAutoADASDevice",
                "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
                "android.hardware.bydauto.dms.BYDAutoDmsDevice",
        };
        StringBuilder sb = new StringBuilder("FCSRPROBE — 앞차출발/신호등 인식 실존 확인\n");
        for (String cn : cands) {
            Object d;
            try { d = onDemand(cn); } catch (Throwable t) { d = null; }
            if (d == null) { sb.append("✗ ").append(cn.substring(cn.lastIndexOf('.') + 1)).append(" 없음\n"); continue; }
            sb.append("✓ ").append(cn.substring(cn.lastIndexOf('.') + 1)).append("\n");
            for (java.lang.reflect.Method m : d.getClass().getMethods()) {
                String n = m.getName(), nl = n.toLowerCase();
                if (!(nl.contains("fcsr") || nl.contains("frontcar") || nl.contains("trafficlight")
                        || nl.contains("greenlight") || nl.contains("tla"))) continue;
                if (m.getParameterTypes().length == 0 && n.startsWith("get")) {
                    sb.append("   ").append(n).append("=").append(callGetInt0(d, n)).append('\n');
                } else {
                    sb.append("   (세터) ").append(n).append('(').append(m.getParameterTypes().length).append("인자)\n");
                }
                if (sb.length() > 2600) { postToD1("DILINK_RAW", sb.toString()); sb.setLength(0); }
            }
        }
        // 신호등 상태 feature — 이벤트로만 오는지 확인용으로 이름/ID도 남긴다(읽기는 이 차에서 死).
        sb.append("신호등 feature: ADAS_TLA_STRAIGHT/LEFT/RIGHT_TRAFFIC_LIGHT_STATE (이벤트 구독으로만 관측 가능)");
        postToD1("DILINK_RAW", sb.toString());
    }

    private String fmtVal(Object v) {
        if (v == null) return "null";
        if (v instanceof int[][]) {   // ★getAllRadarStatus가 int[][] — 중첩 안 펼쳐 [I@... 로만 찍히던 것 수정
            StringBuilder b = new StringBuilder("[");
            for (int[] row : (int[][]) v) b.append(java.util.Arrays.toString(row));
            return b.append(']').toString();
        }
        if (v instanceof int[]) return java.util.Arrays.toString((int[]) v);
        if (v instanceof float[]) return java.util.Arrays.toString((float[]) v);
        if (v instanceof byte[]) return java.util.Arrays.toString((byte[]) v);
        if (v instanceof Object[]) return java.util.Arrays.toString((Object[]) v);
        return String.valueOf(v);
    }

    // ★CONSTS — "<클래스명> [키워드]". 클래스 본체 + 내부 클래스 + 상위 클래스 체인의 static 상수를 이름=값으로.
    private void dumpConsts(String rest) {
        int sp = rest.indexOf(' ');
        String cn = (sp > 0 ? rest.substring(0, sp) : rest).trim();
        String kw = (sp > 0 ? rest.substring(sp + 1).trim().toUpperCase() : "");
        if (!cn.contains(".")) cn = classNameFor(cn);
        StringBuilder sb = new StringBuilder("■ CONSTS " + cn + (kw.isEmpty() ? "" : " ['" + kw + "']") + "\n");
        int n = 0;
        try {
            java.util.List<Class<?>> classes = new java.util.ArrayList<>();
            for (Class<?> c = Class.forName(cn); c != null && c != Object.class; c = c.getSuperclass()) {
                classes.add(c);
                for (Class<?> inner : c.getDeclaredClasses()) classes.add(inner);
            }
            for (Class<?> c : classes) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (!kw.isEmpty() && !f.getName().toUpperCase().contains(kw)) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(null);
                        if (!(v instanceof Number) && !(v instanceof String)) continue;
                        sb.append(f.getName()).append('=').append(v).append(" | ");
                        n++;
                    } catch (Throwable ignored) {}
                    if (sb.length() > 3000) { postToD1("DILINK_DUMP", sb.toString()); sb.setLength(0); }
                }
            }
        } catch (Throwable t) { sb.append("예외 ").append(t); }
        sb.append("\n— 상수 ").append(n).append("개");
        postToD1("DILINK_DUMP", sb.toString());
    }

    // ★READFEAT — "<device> <키워드>" 로 받은 키워드에 맞는 feature id를 전부 그 디바이스에서 읽어 유효값만 로그.
    private void readFeatScan(String rest) {
        int sp = rest.indexOf(' ');
        if (sp <= 0) { postToD1("DILINK_RAW", "READFEAT 형식: READFEAT <device> <키워드>"); return; }
        String dn = rest.substring(0, sp).trim();
        String up = rest.substring(sp + 1).trim().toUpperCase();
        Object dev = deviceByName(dn);
        if (dev == null) { postToD1("DILINK_RAW", "READFEAT: device " + dn + " null"); return; }
        StringBuilder sb = new StringBuilder("READFEAT " + dn + " '" + up + "'\n");
        int hit = 0, tried = 0;
        try {
            java.util.List<Class<?>> classes = new java.util.ArrayList<>();
            Class<?> root = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            classes.add(root);
            for (Class<?> inner : root.getDeclaredClasses()) classes.add(inner);
            for (Class<?> c : classes) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() != int.class) continue;
                    if (!f.getName().toUpperCase().contains(up)) continue;
                    int fid;
                    try { f.setAccessible(true); fid = f.getInt(null); } catch (Throwable t) { continue; }
                    tried++;
                    int v = getFeatureInt(dev, fid);
                    if (v == NA || v == -2147482648 || v == 65535 || v < 0) continue;
                    hit++;
                    sb.append(f.getName()).append('(').append(fid).append(")=").append(v).append('\n');
                    if (sb.length() > 3000) { postToD1("DILINK_RAW", sb.toString()); sb.setLength(0); }
                }
            }
        } catch (Throwable t) { sb.append("예외 ").append(t).append('\n'); }
        sb.append("— 시도 ").append(tried).append("개 중 값 있는 것 ").append(hit).append("개");
        postToD1("DILINK_RAW", sb.toString());
    }

    private int featureIdByName(String name) throws Exception {
        return Class.forName("android.hardware.bydauto.BYDAutoFeatureIds").getField(name).getInt(null);
    }

    // BYDAutoFeatureIds(+내부 클래스)의 static int 상수 중 이름에 filter 포함된 것을 DILINK_FEAT 로 올림.
    private String dumpFeatureIds(String filter) {
        String up = filter.toUpperCase();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        try {
            java.util.List<Class<?>> classes = new java.util.ArrayList<>();
            Class<?> root = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            classes.add(root);
            for (Class<?> inner : root.getDeclaredClasses()) classes.add(inner);
            for (Class<?> c : classes) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() != int.class) continue;
                    if (!f.getName().toUpperCase().contains(up)) continue;
                    try { f.setAccessible(true); sb.append(f.getName()).append('=').append(f.getInt(null)).append('\n'); n++; }
                    catch (Throwable ignored) {}
                    if (sb.length() > 3500) { postToD1("DILINK_FEAT", sb.toString()); sb.setLength(0); }
                }
            }
            if (sb.length() > 0) postToD1("DILINK_FEAT", sb.toString());
            postToD1("DILINK_FEAT", "=== '" + filter + "' 매칭 " + n + "개 ===");
        } catch (Throwable t) { return "dumpFeatures 실패:" + t; }
        return null;
    }
    // ── 상태 폴링 (배터리/주행/기어/잠금 등을 읽어 relay state로 push) ──
    private volatile org.json.JSONArray statusPollCfg;
    private volatile boolean statusPollRunning = false;
    private void startStatusPoll() {
        if (statusPollRunning) return;
        statusPollRunning = true;
        new Thread(() -> {
            while (statusPollRunning && pollRunning) {
                try {
                    org.json.JSONArray cfg = statusPollCfg;
                    if (cfg != null && relay != null) {
                        for (int i = 0; i < cfg.length(); i++) {
                            org.json.JSONObject o = cfg.getJSONObject(i);
                            Object d = deviceByName(o.optString("device"));
                            if (d == null) continue;
                            int v = getFeatureInt(d, o.getInt("id"));
                            if (v == NA) continue;
                            relay.putStatus(o.getString("key"), v);
                        }
                    }
                } catch (Throwable ignored) {}
                try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
            }
        }, "status-poll").start();
    }

    // 임의 도메인 구독(Proxy로 리스너 동적 생성) — 이벤트를 D1(DILINK_EVT)로 로깅 + relay 상태 반영.
    // statistic 등 미구독 도메인의 배터리/주행이 이벤트로 오는지 확인용("구독 후 읽기").
    private String classNameFor(String name) {
        if (name.startsWith("android.hardware")) return name;
        switch (name) {
            case "statistic": return "android.hardware.bydauto.statistic.BYDAutoStatisticDevice";
            case "power": return "android.hardware.bydauto.power.BYDAutoPowerDevice";
            case "gearbox": return "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice";
            case "energy": return "android.hardware.bydauto.energy.BYDAutoEnergyDevice";
            case "radar": return "android.hardware.bydauto.radar.BYDAutoRadarDevice";   // ★전방 근접 거리(2026-09-11)
            case "charging": return "android.hardware.bydauto.charging.BYDAutoChargingDevice";
            case "instrument": return "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice";
            // ★CONSTS/READFEAT가 짧은 이름으로도 붙게(2026-09-10) — 여기 없던 setting/ac/light/bodywork/doorlock 때문에
            //   `CONSTS setting …`이 Class.forName("setting") 예외로 죽던 것.
            case "setting": return "android.hardware.bydauto.setting.BYDAutoSettingDevice";
            case "ac": return "android.hardware.bydauto.ac.BYDAutoAcDevice";
            case "light": return "android.hardware.bydauto.light.BYDAutoLightDevice";
            case "bodywork": return "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice";
            case "doorlock": return "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice";
            case "adas": return "android.hardware.bydauto.adas.BYDAutoADASDevice";
            case "seat": return "android.hardware.bydauto.seat.BYDAutoSeatDevice";
            case "audio": return "android.hardware.bydauto.audio.BYDAutoAudioDevice";
            default: return name;
        }
    }
    private final java.util.Set<String> subscribedDomains = new java.util.HashSet<>();
    private String subscribeDomain(String shortOrClass) {
        String cn = classNameFor(shortOrClass);
        if (subscribedDomains.contains(cn)) return "이미 구독:" + cn;
        try {
            Class<?> dc = Class.forName(cn);
            Object dev = dc.getMethod("getInstance", Context.class).invoke(null, vcwRef);
            if (dev == null) return "device null:" + cn;
            java.lang.reflect.Method reg = null;
            for (java.lang.reflect.Method m : dc.getMethods()) {
                if (m.getName().equals("registerListener") && m.getParameterTypes().length == 1 && m.getParameterTypes()[0].isInterface()) { reg = m; break; }
            }
            if (reg == null) return "registerListener 없음:" + cn;
            Class<?> itf = reg.getParameterTypes()[0];
            final String dom = domainOf(cn);
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(itf.getClassLoader(), new Class[]{itf}, (p, method, args) -> {
                String mn = method.getName();
                if (mn.equals("onDataEventChanged") && args != null && args.length == 2) {
                    try {
                        int fid = (Integer) args[0];
                        int iv = -1;
                        try { iv = args[1].getClass().getField("intValue").getInt(args[1]); } catch (Throwable ignored) {}
                        String fn = idToName.getOrDefault(fid, "id" + fid);
                        postToD1("DILINK_EVT", dom + " " + fn + "=" + iv);
                        if (relay != null) relay.updateFromEvent(fn, String.valueOf(iv));
                    } catch (Throwable ignored) {}
                    return null;
                }
                if (mn.equals("hashCode")) return System.identityHashCode(p);
                if (mn.equals("equals")) return args != null && p == args[0];
                if (mn.equals("toString")) return "proxy-" + dom;
                return null;
            });
            reg.invoke(dev, proxy);
            onDemandDevices.put(cn, dev);
            subscribedDomains.add(cn);
            return "OK subscribed " + cn;
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
            return "FAIL " + cn + ": " + c.getClass().getSimpleName() + ":" + c.getMessage();
        }
    }

    private final Map<String,Object> onDemandDevices = new java.util.HashMap<>();
    // ★681 D5 sentry wake용 SpecialDevice — 앱 로더/시스템앱 DexClassLoader는 전부 ClassNotFound였다(678 실측, 클래스는 APK dex에 존재).
    //   이벤트 등록에 이미 쓰는 D5 전용 로더(OEM com.byd.data.collect dex를 InMemoryDexClassLoader로)로 getInstance 시도. 결과는 한 번만 보고.
    private Object specialDevD5;
    private boolean specialD5Reported = false;
    private Object specialViaD5Loader() {
        if (specialDevD5 != null) return specialDevD5;
        try {
            Class<?> k = DiLink5Support.loadD5Class(this, "android.hardware.bydauto.special.BYDAutoSpecialDevice");
            if (k == null) { if (!specialD5Reported) { specialD5Reported = true; postToD1("WAKE", "SpecialDevice D5로더 클래스 로드 실패 — " + DiLink5Support.loaderReport); } return null; }
            Object d = k.getMethod("getInstance", Context.class).invoke(null, vcwRef);
            if (d != null) { injectContext(d); specialDevD5 = d; postToD1("WAKE", "SpecialDevice D5로더 획득 OK"); }
            else if (!specialD5Reported) { specialD5Reported = true; postToD1("WAKE", "SpecialDevice D5로더 getInstance=null"); }
            return d;
        } catch (Throwable t) {
            if (!specialD5Reported) {
                specialD5Reported = true;
                Throwable c = (t.getCause() != null) ? t.getCause() : t;
                postToD1("WAKE", "SpecialDevice D5로더 예외 — " + c.getClass().getSimpleName() + ": " + String.valueOf(c.getMessage()));
            }
            return null;
        }
    }

    private Object onDemand(String className) {
        Object d = onDemandDevices.get(className);
        if (d == null) {
            if (DiLink5Support.isDiLink5()) DiLink5Support.ensure(this);   // ★D5 dex 주입 선행(멱등) — 온디맨드 setter 디바이스도 D5서 잡히게
            try { d = Class.forName(className).getMethod("getInstance", Context.class).invoke(null, vcwRef);
                  if (d != null) { injectContext(d); onDemandDevices.put(className, d); } }
            catch (Throwable ignored) {}
            // ★씨라7(D5) 폴백 — data.collect 경로가 권한/InvocationTargetException으로 null이면 bydcontroller처럼 소유 시스템앱(hvac·carsettings…)에서 로드.
            if (d == null && DiLink5Support.isDiLink5()) {
                d = DiLink5Support.deviceFromSystemApp(this, className, vcwRef);
                if (d != null) { injectContext(d); onDemandDevices.put(className, d); postToD1("RELAY", "D5 시스템앱 장치 획득: " + DiLink5Support.lastSysReport); }
            }
        }
        return d;
    }

    // ★device 내부 Context 필드를 권한통과 wrapper(vcwRef)로 강제 주입 — wakeUpMcu가 내부 Context.enforceCallingOrSelfPermission을
    //   호출할 때 null이면 NPE(상시생존 실패의 진짜 원인). getInstance(Context)로 넘겨도 device가 저장 안 해서 직접 심는다.
    private void injectContext(Object dev) {
        Class<?> c = dev.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (Context.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object cur = f.get(dev);
                        if (!(cur instanceof VehicleContextWrapper)) f.set(dev, vcwRef);
                    } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
    }
    private Object deviceByName(String name) {
        if (name == null) return null;
        switch (name) {
            case "setting":
                // ★D5 폴백 — 좌석/핸들 제어는 setting 디바이스인데, D5는 setting Abs리스너 부재로 setupDevice가 못 세팅(settingDevice=null).
                //   제어는 리스너 없이 setter만 있으면 되므로 온디맨드로 획득(D5Support.ensure 후 getInstance). D3는 이미 setupDevice가 세팅.
                if (settingDevice == null) settingDevice = onDemand("android.hardware.bydauto.setting.BYDAutoSettingDevice");
                return settingDevice;
            case "doorlock": return doorlockDevice;
            case "bodywork": return getBodyworkDevice();
            case "adas": return adasDevice;
            case "instrument": return instrumentDevice;
            case "charging": return chargingDevice;
            case "safetybelt": return safetybeltDevice;
            case "power": return onDemand("android.hardware.bydauto.power.BYDAutoPowerDevice");
            case "gearbox": return onDemand("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
            case "speed": return onDemand("android.hardware.bydauto.speed.BYDAutoSpeedDevice");
            case "statistic": return onDemand("android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
            case "ac": return onDemand("android.hardware.bydauto.ac.BYDAutoAcDevice");            // ★리모컨 manifest 짧은이름(2026-09-10)
            case "light": return onDemand("android.hardware.bydauto.light.BYDAutoLightDevice");
            case "energy": return onDemand("android.hardware.bydauto.energy.BYDAutoEnergyDevice");   // ★주행모드 setOperationMode(2026-09-10)
            case "radar": return onDemand("android.hardware.bydauto.radar.BYDAutoRadarDevice");   // ★getAllRadarDistance 등 초음파 거리
            default:
                if (name.startsWith("android.hardware.bydauto")) return onDemand(name);  // 풀 클래스명 직접
                return null;
        }
    }

    // set(int[], BYDAutoEventValue) — EV는 get 결과와 동일 클래스, public int 필드 intValue 세팅.
    private String writeOne(Object dev, int featureId, int value, StringBuilder log) {
        if (dev == null) return "디바이스 null";
        try {
            java.lang.reflect.Method setM = null;
            for (java.lang.reflect.Method m : dev.getClass().getMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (m.getName().equals("set") && ps.length == 2 && ps[0] == int[].class) { setM = m; break; }
            }
            if (setM == null) return "set(int[],EV) 없음";
            Class<?> evCls = setM.getParameterTypes()[1];
            Object ev;
            try { ev = evCls.getDeclaredConstructor().newInstance(); }
            catch (Throwable t) { java.lang.reflect.Constructor<?> ct = evCls.getDeclaredConstructors()[0]; ct.setAccessible(true);
                Class<?>[] cp = ct.getParameterTypes(); Object[] args = new Object[cp.length];
                for (int i=0;i<cp.length;i++) args[i] = cp[i]==int.class?0:(cp[i]==boolean.class?false:null); ev = ct.newInstance(args); }
            try { evCls.getField("intValue").setInt(ev, value); } catch (Throwable t) { log.append("intValue?").append(t.getMessage()).append(" "); }
            Object rv = setM.invoke(dev, new int[]{featureId}, ev);
            if (setRetLog < 2) { setRetLog++; postToD1("DILINK_RAW", "set() 반환형=" + setM.getReturnType().getSimpleName() + " 값=" + rv); }
            return "OK set([" + featureId + "], intValue=" + value + ")" + (rv != null ? " ret=" + rv : "");
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause()!=null)?t.getCause():t;
            return "실패:" + c.getClass().getSimpleName() + ":" + c.getMessage();
        }
    }
    private String writeAll(Object dev, int[] featureIds, int value, StringBuilder log) {
        StringBuilder ok = new StringBuilder();
        for (int f : featureIds) { ok.append(writeOne(dev, f, value, log)).append("\n"); }
        return ok.toString();
    }

    // SDK write API 발견 — 전체 디바이스 메서드 + BYDAutoEventValue 구조를 D1에 올림(디바이스별 분할).
    public String dumpDeviceMethods() {
        java.util.List<Object> all = new java.util.ArrayList<>(devices);
        for (String cn : new String[]{
                "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
                "android.hardware.bydauto.airconditioner.BYDAutoAirConditionerDevice",
                "android.hardware.bydauto.air.BYDAutoAirDevice",
                "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice",
                "android.hardware.bydauto.setting.BYDAutoSettingDevice",
                // ★2026-09-10 추가 — 이 5개가 빠져 있어 "세터가 없다"는 잘못된 결론이 났다(주행모드=energy.setOperationMode를
                //   한 번 '제어 불가'로 오판). 세터를 못 찾으면 디바이스가 목록에 있는지부터 볼 것.
                "android.hardware.bydauto.light.BYDAutoLightDevice",
                "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
                "android.hardware.bydauto.power.BYDAutoPowerDevice",
                "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
                "android.hardware.bydauto.seat.BYDAutoSeatDevice"}) {
            try { Object d = Class.forName(cn).getMethod("getInstance", Context.class).invoke(null, vcwRef); if (d != null) all.add(d); }
            catch (Throwable ignored) {}
        }
        int count = 0;
        Class<?> evCls = null;
        for (Object dev : all) {
            if (dev == null) continue;
            Class<?> c = dev.getClass();
            StringBuilder sb = new StringBuilder("■ ").append(c.getName()).append("\n");
            for (java.lang.reflect.Method m : c.getMethods()) {
                String n = m.getName(); String nl = n.toLowerCase();
                // ★v99 도어락 제어 메서드 탐색 — set/control/send 외에 lock/unlock/door/open 이름도 포함(제어 후보 놓치지 않게).
                if (!(n.startsWith("set") || n.equals("get") || n.equals("control") || n.contains("send")
                        || nl.contains("lock") || nl.contains("door") || nl.contains("open") || nl.contains("request") || nl.contains("execute"))) continue;
                sb.append("  ").append(n).append("(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) { if (i > 0) sb.append(","); sb.append(ps[i].getSimpleName()); if (n.equals("set") && i==1) evCls = ps[i]; }
                sb.append(")\n");
            }
            postToD1("DILINK_DUMP", sb.toString());
            count++;
        }
        // BYDAutoEventValue 실제 구조(생성자·필드) — write용 값 구성 근거
        if (evCls != null) {
            StringBuilder sb = new StringBuilder("★ ").append(evCls.getName()).append(" (EV 구조)\n생성자:\n");
            for (java.lang.reflect.Constructor<?> ct : evCls.getDeclaredConstructors()) {
                sb.append("  (");
                Class<?>[] ps = ct.getParameterTypes();
                for (int i=0;i<ps.length;i++){ if(i>0) sb.append(","); sb.append(ps[i].getSimpleName()); }
                sb.append(")\n");
            }
            sb.append("필드:\n");
            for (java.lang.reflect.Field fld : evCls.getFields()) sb.append("  ").append(fld.getType().getSimpleName()).append(" ").append(fld.getName()).append("\n");
            postToD1("DILINK_DUMP", sb.toString());
            count++;
        }
        String out = "덤프 " + count + "개 블록을 D1에 올림 (PC에서 조회)";
        Log.i(TAG, out);
        postToD1("DILINK_DUMP", "=== 덤프 완료: " + count + " 블록 ===");
        return out;
    }

    // ── BSD 좌/우 방향 계측 ──
    // 실차 read 시그니처는 get(int[])→BYDAutoEventValue (get(int)/get(int,int) 아님 → 기존 pollGet은 항상 no-get).
    // 이 경로로 feature ID를 직접 읽어 intValue 추출.
    private static final int NA = Integer.MIN_VALUE;
    // 실차 read 시그니처 = get(int[], Class) → BYDAutoEventValue. (int[] 단일 아님 → 그래서 옛 get(int[])/get(int)가 전부 실패)
    // 두번째 Class 인자 의미 미상 → 후보를 순서대로 시도, 성공 조합 캐시.
    private volatile Class<?> getArgClsCache;
    private int getFeatureInt(Object device, int featureId) {
        if (device == null) return NA;
        java.lang.reflect.Method m;
        try { m = device.getClass().getMethod("get", int[].class, Class.class); }
        catch (Throwable t) { return NA; }
        Class<?>[] cands = (getArgClsCache != null)
                ? new Class<?>[]{ getArgClsCache }
                : new Class<?>[]{ BYDAutoEventValue.class, Integer.class, device.getClass(), Object.class };
        for (Class<?> c : cands) {
            try {
                Object ev = m.invoke(device, new int[]{featureId}, c);
                if (ev == null) continue;
                int iv = ev.getClass().getField("intValue").getInt(ev);
                getArgClsCache = c;
                return iv;
            } catch (Throwable ignore) { /* 다음 Class 후보 */ }
        }
        return NA;
    }
    private static String naFmt(int v) { return v == NA ? "na" : String.valueOf(v); }

    // ★v115 ICC 효과음 오디오 — 포커스 미요청 = 미디어 위에 믹싱. 채널은 앱에서 선택(KEY_ICC_CHANNEL, 2026-09-16).
    private void initIccAudio() {
        try {
            if (iccSoundPool != null) { try { iccSoundPool.release(); } catch (Throwable ignore) {} }   // ★채널 변경 시 재빌드 대비
            android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                .setUsage(CH_USAGE[chIdx(KEY_ICC_CHANNEL)])
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            iccSoundPool = new android.media.SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build();
            for (int i = 0; i < ICC_SOUND_RES.length; i++) {
                iccSoundIds[i] = iccSoundPool.load(this, ICC_SOUND_RES[i], 1);
                iccSoundMs[i] = soundDurationMs(ICC_SOUND_RES[i]);
            }
            postToD1("ICC", "오디오 초기화 — 사운드 " + java.util.Arrays.toString(iccSoundIds));
        } catch (Throwable t) { Log.w(TAG, "ICC 오디오 초기화 실패", t); }
    }
    // ★2026-09-16 운영자 지적 — 주행 중(음악/내비 재생 중) BSD·차선 경보음이 안 들림. requestAudioFocus를 아예 안 하니
    //   미디어는 안 끊기지만(의도한 동작) 경보음이 미디어 볼륨에 그대로 묻힘. AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK로
    //   딱 경보음 재생 시간만 다른 채널을 "일시적으로 낮추기(duck)"만 하고 바로 반납 — 정지/끊김 없이 들리게.
    private android.media.AudioManager.OnAudioFocusChangeListener duckFocusListener = fc -> {};
    private android.media.AudioFocusRequest duckFocusReq;   // API26+
    private void duckMediaBriefly(int usage, long ms) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                .setUsage(usage).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.media.AudioFocusRequest req = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs).setWillPauseWhenDucked(false).setOnAudioFocusChangeListener(duckFocusListener).build();
                duckFocusReq = req;
                // ★558 포커스 거부를 남긴다 — 다른 앱(내비·SubAI 등)이 포커스를 잡고 있으면 경보음이 묻힌다.
                //   "알람 소리를 못 들었다" 신고에서 이 값이 없어 충돌 가설을 확인할 수 없었다. 거부일 때만 기록.
                int fr = am.requestAudioFocus(req);
                if (fr != android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    postToD1("ICC", "오디오 포커스 거부(" + fr + ") — 경보음이 다른 앱 소리에 묻힐 수 있음");
                if (mainHandler != null) mainHandler.postDelayed(() -> { try { am.abandonAudioFocusRequest(req); } catch (Throwable ignore) {} }, ms);
            } else {
                int streamType = (usage == android.media.AudioAttributes.USAGE_ALARM) ? android.media.AudioManager.STREAM_ALARM : android.media.AudioManager.STREAM_MUSIC;
                am.requestAudioFocus(duckFocusListener, streamType, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                if (mainHandler != null) mainHandler.postDelayed(() -> { try { am.abandonAudioFocus(duckFocusListener); } catch (Throwable ignore) {} }, ms);
            }
        } catch (Throwable ignore) {}
    }
    // ★620 자동 알림음(앞차출발·신호·ICC·차로)은 킬스위치·주 사용권 폰 부재 시 무음. 미리듣기(playSound)는 사용자 조작이라 Raw로 예외.
    void playIccSound(int index, float vol) {
        if (!autoAllowed()) return;
        playIccSoundRaw(index, vol);
    }
    void playIccSoundRaw(int index, float vol) { playIccSoundRaw(index, vol, 0); }
    // ★833 side: 0=양쪽, 2=왼쪽, 4=오른쪽(깜빡이 값과 같은 번호) — 반대쪽은 0.3으로 남긴다(운전석에서 조수석 쪽이 너무 작아지지 않게)
    void playIccSoundRaw(int index, float vol, int side) {
        if (iccSoundPool == null || index < 0 || index >= iccSoundIds.length || iccSoundIds[index] == 0) {
            postToD1("ICC", "재생 스킵 — pool=" + (iccSoundPool != null) + " idx=" + index + " id=" + (index >= 0 && index < iccSoundIds.length ? iccSoundIds[index] : -1));
            return;
        }
        duckMediaBriefly(CH_USAGE[chIdx(KEY_ICC_CHANNEL)], iccSoundMs[index] > 0 ? iccSoundMs[index] + 400 : 1500);   // ★629 소리 길이+0.4s(측정 실패 시 1.5s). BSD 0.46s→0.86s
        float lv = side == 4 ? vol * 0.3f : vol, rv = side == 2 ? vol * 0.3f : vol;
        int stream = iccSoundPool.play(iccSoundIds[index], lv, rv, 1, 0, 1.0f);
        // ★629 헤드유닛 시스템 음량도 함께 — "알림이 작다" 신고 때 앱 슬라이더 탓인지 차 볼륨 탓인지 가르기 위해
        String sys = "?";
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            int st = CH_STREAM[chIdx(KEY_ICC_CHANNEL)];
            if (am != null) sys = am.getStreamVolume(st) + "/" + am.getStreamMaxVolume(st);
        } catch (Throwable ignore) {}
        postToD1("ICC", "효과음 재생 idx=" + index + " vol=" + (int) (vol * 100) + (side == 2 ? " 왼쪽" : side == 4 ? " 오른쪽" : "") + " sysVol=" + sys + " ch=" + CH_LABEL[chIdx(KEY_ICC_CHANNEL)] + " streamId=" + stream);
    }
    private long soundDurationMs(int res) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try (android.content.res.AssetFileDescriptor fd = getResources().openRawResourceFd(res)) {
            r.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            String s = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return s != null ? Long.parseLong(s) : 0;
        } catch (Throwable t) { return 0; }
        finally { try { r.release(); } catch (Throwable ignore) {} }
    }
    // ★v115 ADAS_ACC_MODE 판정(subai): 3=ON, 2/4=OFF(활성중), 0/1=비활성. 활성→오프 전이에서만 오프 효과음.
    private void handleAccMode(int v) {
        if (v == lastAccValue) return;
        int prev = lastAccValue; lastAccValue = v;
        postToD1("ICCDIAG", "ADAS_ACC_MODE " + prev + "→" + v);   // 실차 값 인코딩 확인용
        // ★앞차 출발 알림 = ICC 정차(6/7)→주행(3/5) 전이(2026-09-18 정답지 검증: 정밀도 78%·출발보다 중앙 2.0s 선행·오검출 0건).
        //   값 의미 6/7=정차·3/5=주행(09-17 실측). 위 v115 subai 주석(3=ON)은 낡음. 제외: 2→1(재생 정밀도 5.6% 붕괴)·6→7(정차 내부 전이).
        //   카메라 경로(handleFrontAlert)는 무손 대조군, frontLeadAlert(4s dedup) 공유. 이벤트 경로라 적시성 즉시(폴 지연 0). 커버리지 51%(폴링 보완은 getter 확인 후 2단계).
        if ((prev == 6 || prev == 7) && (v == 3 || v == 5) && autoAllowed()
                && (prefs == null || prefs.getBoolean("front_lead", true))) {
            long now = System.currentTimeMillis();
            if (now - frontLeadAlert > 4000) {
                frontLeadAlert = now;
                int vol = prefs != null ? prefs.getInt("front_vol", 80) : 80;
                int leadIdx = prefs != null ? prefs.getInt("front_lead_idx", 10) : 10;
                playIccSound(leadIdx, vol / 100f);
                postToD1("FRONT", "앞차 출발(ACC " + prev + "→" + v + ") → sound " + leadIdx);
            }
        }
        if (v == 3) { accActive = true; return; }
        if (v == 2 || v == 4 || v == 0 || v == 1) accActive = false;   // ★ICC 해제 — 차로중앙유지 알림용 상태만 갱신(ICC 해제 효과음 제거 2026-08-13, 버튼/브레이크로 의도적 해제라 불필요)
    }

    // ★v129 ICC 차선 추적 상태 — LEFT/RIGHT_LINE_TRACKING_STATUS(1=차선 잡음/0=놓침) + TJA_ICA_STATE.
    //   adas 도메인은 BSD용으로 상시 구독중이라 별도 구독 없이 이벤트가 들어온다(2026-08-13 주행 실측).
    //   경고 조건 = ICC 활성(accActive) AND 좌우 둘 다 놓침(양측 상실). 단측 상실은 반대쪽이 잡고 있으면
    //   중심유지 멀쩡 → 스팸이라 제외. TJA_ICA_STATE(2=정상주행/3=예외)는 상시발화라 트리거로 안 씀(로깅만).
    //   양측상실 실이벤트는 아직 미관측이라 임계값 확정 전 — LANEDIAG로 raw 로깅해 튜닝, 쿨다운 5초로 스팸 억제.
    private volatile int lastLeftTrack = Integer.MIN_VALUE;
    private volatile int lastRightTrack = Integer.MIN_VALUE;
    private volatile int lastTjaState = Integer.MIN_VALUE;
    private volatile long lastLaneAlertTs = 0;
    private void handleLaneEvent(int featureId, int v) {
        // LINE_TRACKING(좌우 차선 인식)은 참고 로깅만 — 알림 판정은 종합 상태(TJA_ICA)로.
        if (featureId == 828375052) { if (v == lastLeftTrack) return; lastLeftTrack = v; postToD1("LANEDIAG", "LEFT_TRACK=" + v + " (R=" + lastRightTrack + " acc=" + accActive + ")"); return; }
        if (featureId == 828375082) { if (v == lastRightTrack) return; lastRightTrack = v; postToD1("LANEDIAG", "RIGHT_TRACK=" + v + " (L=" + lastLeftTrack + " acc=" + accActive + ")"); return; }
        // ★TJA_ICA_STATE(828375084) = 계기판 차로중앙유지 표시. 3=ON(양쪽 잡고 제어), 2=OFF(놓침·해제). 실차 확정(2026-08-13 사용자 관찰).
        //   한쪽만 놓치면 3 유지, 양쪽 놓쳐 실제 제어를 놓을 때만 2 → 계기판 표시와 정확히 일치. 이 전이에 맞춰 즉시 알림.
        if (v == lastTjaState) return;
        int prev = lastTjaState;
        lastTjaState = v;
        boolean on = (v == 3), wasOn = (prev == 3);
        postToD1("LANEDIAG", "TJA_ICA=" + v + " 중앙유지" + (on ? "ON" : "OFF") + " (L=" + lastLeftTrack + " R=" + lastRightTrack + " acc=" + accActive + ")");
        // 알림은 ICC 동작 중(accActive)에만. 폰 앱 설정(RAW laneAlert)으로 on/소리/음량 제어.
        // ★558 스킵 사유를 남긴다 — "차선 알림 켜놨는데 소리를 못 들었다"(피드백 id146) 신고에서, 이 조용한 return 때문에
        //   "기능이 고장"인지 "조건 미충족(ACC 미사용)"인지 구분이 불가능했다. 차선 알림은 ACC 사용 중에만 동작한다.
        //   설정을 켜둔 사용자에게만, 60초 스로틀로 남긴다(정체길 ON/OFF 반복에 로그가 홍수되지 않게).
        if (!accActive || prefs == null || prev == Integer.MIN_VALUE) {
            if (!accActive && prefs != null && prefs.getBoolean("lane_alert_on", false)) {
                long nowSkip = System.currentTimeMillis();
                if (nowSkip - lastLaneSkipLogTs > 60_000) {
                    lastLaneSkipLogTs = nowSkip;
                    postToD1("LANE", "중앙유지 변화 감지했으나 알림 스킵 — ACC 비활성(차선 알림은 ACC 사용 중에만 동작)");
                }
            }
            return;
        }
        // ★v293 속도 가드 복원 — v153에서 죽은게터(getSpeedFromGateway)로 정차판별 불가라 제거했으나, getCurrentSpeed
        //   실증(0~110km/h)으로 lastSpeed/speedLive가 이제 정확 → 아래 muteStop(정차 억제)이 제대로 작동한다. 정체길 완전정차 스팸 차단.
        if (!prefs.getBoolean("lane_alert_on", false)) return;   // 앱에서 켜야 동작(opt-in)
        // ★정차/깜빡이 시 알림 억제(각 옵션) — 정체 완전정차·차선변경(깜빡이) 중 중앙유지 ON/OFF 반복 스팸 방지.
        //   정차 억제는 speedLive(getCurrentSpeed 살아있음)일 때만 — 못 읽으면 fail-open(알림 우선).
        boolean muteStop = prefs.getBoolean("lane_mute_stop", false) && speedLive && lastSpeed <= 3;   // ★v295 ≤3km/h(정차·크립만) — 완전정차서 getCurrentSpeed가 1~2 미세값 주므로 ==0은 미작동(실측 07:08 속도2에 울림). 3=정차/크립만, 서행부턴 울림.
        boolean muteTurn = prefs.getBoolean("lane_mute_turn", false) && (turnSignalActive || System.currentTimeMillis() - lastTurnSignalOnTs < 2000);   // ★깜빡이 켠 직후 2초는 억제 — 차로유지 해제 이벤트가 깜빡이 갱신보다 먼저 와도 놓치지 않게.
        if (muteStop || muteTurn) { postToD1("LANE", "중앙유지 " + (on ? "복구" : "해제") + " — 알림 억제(" + (muteStop ? "정차 " : "") + (muteTurn ? "깜빡이" : "") + ")"); return; }
        if (wasOn && !on)                                                   // 중앙유지 ON→OFF(놓침) → 즉시 알림
            fireLaneSound(prefs.getInt("lane_lost_idx", 1), "중앙유지 해제");
        else if (!wasOn && on && prefs.getBoolean("lane_recover_on", false)) // OFF→ON(복구) → 복구음(옵션)
            fireLaneSound(prefs.getInt("lane_recover_idx", 0), "중앙유지 복구");
    }
    // ★833 FB2200 씨라7 차로 알림 — D3의 중앙유지 표시(TJA_ICA) 신호가 D5엔 없어 크루즈 상태 전이로 같은 판단.
    //   신호 문서(10-06, 씨라7 실측): 3=ICC, 3→2/5=ICC 종료·ACC 유지(차로를 놓쳐 ICC만 풀림), 2/5→3=ICC 복귀, 3→1=둘 다 해제(무음).
    //   accActive도 여기서 세팅(종전엔 D3 ACC_MODE 경로만 세팅해 씨라7은 항상 false). 첫 값(-1)은 기준만.
    private void d5IccLaneTransition(int prev, int v) {
        accActive = (v == 3);
        if (prev < 0 || prefs == null || !prefs.getBoolean("lane_alert_on", false)) return;
        boolean lost = prev == 3 && (v == 2 || v == 5);
        boolean back = (prev == 2 || prev == 5) && v == 3;
        if (!lost && !back) return;
        boolean muteStop = prefs.getBoolean("lane_mute_stop", false) && speedLive && lastSpeed <= 3;
        boolean muteTurn = prefs.getBoolean("lane_mute_turn", false) && (turnSignalActive || System.currentTimeMillis() - lastTurnSignalOnTs < 2000);
        if (muteStop || muteTurn) { postToD1("LANE", "씨라7 ICC " + (lost ? "해제" : "복귀") + " — 알림 억제(" + (muteStop ? "정차 " : "") + (muteTurn ? "깜빡이" : "") + ")"); return; }
        if (lost) fireLaneSound(prefs.getInt("lane_lost_idx", 1), "씨라7 ICC 해제(" + prev + "→" + v + ")");
        else if (prefs.getBoolean("lane_recover_on", false)) fireLaneSound(prefs.getInt("lane_recover_idx", 0), "씨라7 ICC 복귀(" + prev + "→" + v + ")");
    }
    private void fireLaneSound(int idx, String reason) {
        long now = System.currentTimeMillis();
        if (now - lastLaneAlertTs < 1500) return;   // 연타 방지(짧게 — 즉시성 우선)
        lastLaneAlertTs = now;
        postToD1("LANE", reason + " → 알림음 idx=" + idx);
        int vol = prefs != null ? prefs.getInt("lane_vol", 70) : 70;
        playIccSound(idx, vol / 100f);
    }

    // ★v113 주차(P) 중 브레이크 페달 눌림 → 폰에 brake_start(공조 예열). 값 인코딩 학습용 로깅 겸함.
    //   getter 폴링 불가(이벤트 전용, readFeature=쓰레기)라 이 이벤트가 유일 경로. 눌림값(0=뗌 가정, 실차로 확인).
    private void onBrakePedal(int v) {
        int prev = lastBrakeVal;
        lastBrakeVal = v;
        // ★v114 인코딩 확정(1=밟음/0=뗌) 후 주행중 스팸 제거 — 주차(P)일 때만 진단 로깅.
        if (v != prev && lastGearVal == 3) postToD1("BRAKE", "BRAKE_PEDAL=" + v + " gear=P(주차)");
        boolean pressedEdge = (v != prev) && v != 0 && v != Integer.MIN_VALUE;
        if (pressedEdge && lastGearVal == 3) {
            // ★v256 brake_start 게이트 재설계 — pw=-1(미상)의 두 의미를 afterblow 상태로 구분.
            //   ①주차 후 시동꺼짐 → 릴레이 재시작 → state 비어 pw=-1(미상이나 실제 꺼짐) = 발사해야 함(사용자 케이스, 2026-08-19 실측: 17:30:34 재시작→pw=-1로 브레이크 차단).
            //   ②애프터블로우 중(OPENAIR로 시동ON, AC_POWER=1 이벤트 놓쳐 pw=-1) = 실제 켜짐 → 차단(2026-08-18 오발사 재점화 버그).
            //   구분: afterblow 창 안이면 켜짐 취급(차단), 밖이면 pw==1(확실 켜짐)만 차단 → pw=0/‑1(꺼짐/미상)은 발사.
            int pw = relay != null ? relay.carPowerState() : -1;
            boolean inAfterblow = System.currentTimeMillis() < afterblowUntil;
            if (pw == 1 || inAfterblow) { postToD1("BRAKE", "brake 무시 — 시동 켜짐(pw=" + pw + " afterblow=" + inAfterblow + ")"); return; }
            long now = System.currentTimeMillis();
            if (now - lastBrakeStartTs > 300_000) {   // 5분 쿨다운 — 주차 세션당 1회
                lastBrakeStartTs = now;
                postToD1("BRAKE", "주차·시동꺼짐 중 브레이크 눌림(pw=" + pw + ") → brake_start 발사");
                if (relay != null) relay.postCarEvent("brake_start");
            }
        }
    }

    private final java.util.Map<String, String> energyEvtLast = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> energyEvtTs = new java.util.concurrent.ConcurrentHashMap<>();
    private void onVehicleEvent(String deviceName, int featureId, BYDAutoEventValue value) {
        try {
            int intVal = -1;
            if (value != null) {
                try {
                    intVal = value.getClass().getField("intValue").getInt(value);
                } catch (Exception ignored) {
                }
            }

            String featureName = idToName.getOrDefault(featureId, "id" + featureId);
            String rawKey = deviceName + ":" + featureName;
            Object rawVal = rawEventValue(value, intVal);
            clusterRawUpdate(rawKey, rawVal);   // ★클러스터 raw 이벤트 맵(2026-09-17) — 필터·NoiseFilter·로그 이전 단계, 모든 이벤트
            if (clusterPinKey(rawKey) && clusterPinFirstSeen.add(rawKey)) postToD1("CLUSTER", "핀 키 최초 이벤트 수신: " + rawKey + "=" + rawVal);   // ★567 실증용(위 주석)
            // ★607 에코/노말 실험(606 energy 구독) — clusterRaw는 헤드유닛 메모리에만 있어 A1에서 안 보임 → 운영자판만 값 변화 시 ENERGYEVT 로그(키별 5초 스로틀).
            if (BuildConfig.IS_OPERATOR && "energy".equals(deviceName)) {
                long nowE = System.currentTimeMillis(); String vs = String.valueOf(rawVal);
                String pv = energyEvtLast.put(featureName, vs); Long pt = energyEvtTs.get(featureName);
                if (!vs.equals(pv) && (pt == null || nowE - pt > 5000)) { energyEvtTs.put(featureName, nowE); postToD1("ENERGYEVT", featureName + "=" + vs + " (이전 " + pv + ") opMode=" + (relay != null ? relay.getStatus("drive_mode_get") : "?")); }
            }

            // ★v115 ICC(ACC) 오프 효과음 — subai 방식: ADAS_ACC_MODE(852492340) 이벤트로 감지.
            if (featureId == 852492340) handleAccMode(intVal);
            // ★v129 ICC 차선 추적 — LEFT/RIGHT_LINE_TRACKING_STATUS·TJA_ICA_STATE 감지(값 raw 로깅 + 양측상실 경고).
            else if (featureId == 828375052 || featureId == 828375082 || featureId == 828375084) handleLaneEvent(featureId, intVal);

            // ★v81 gearbox 이벤트 — 리스너 등록 자체가 HAL이 gearbox getter를 live로 유지시키는 핵심(등록 없으면 stale).
            //   주행 판정은 폴링(getCurrentGear, 이제 live)이 담당. 여기선 EPB/파킹브레이크만 저volume 로깅(BRAKE_PEDAL 스팸 제외).
            if ("gearbox".equals(deviceName)) {
                String gu = featureName.toUpperCase();
                if (gu.contains("EPB") || gu.contains("PARK_BRAKE") || gu.contains("GEARBOX_STATE")) {
                    postToD1("GEAREVT", featureName + "=" + intVal);
                    // ★후진 즉시감지 — 파킹브레이크/EPB 이벤트는 기어 넣는 순간 즉시 옴(2s 폴링과 비교불가). 여기서 기어 바로 읽어 R이면 클라우드 비상등 연타 → 6042 게이트 이기기.
                    if (prefs != null && prefs.getBoolean("reverse_flash", true)) checkReverseFast("evt");
                }
                else if (featureId == 692060194 || gu.contains("BRAKE_PEDAL"))   // ★v113 주차 중 브레이크 → 공조 예열 트리거
                    onBrakePedal(intVal);
            }

            // 차량 활동 감지 → 유지시간 갱신(마지막 활동 후 maxKeepAliveMs 유지). 절전중이었으면 즉시 재개.
            keepAliveUntil = System.currentTimeMillis() + maxKeepAliveMs;
            if (wakeLock != null && !wakeLock.isHeld()) scheduleWakeAlarm(0);
            if (featureId == 315621437) {   // POWER_LOW_VOLTAGE (0=정상, 1=저전압경고)
                lowVoltFlag = intVal;
                if (intVal == 1) { if (lowSinceMs == 0) lowSinceMs = System.currentTimeMillis(); }
                else lowSinceMs = 0;
            }

            // 오토도어 릴레이로 최신 상태 전달 (필터와 무관하게 항상)
            if (relay != null) {
                if (featureName.contains("DOOR_STATE") && !DiLink5Support.isDiLink5()) {
                    // ★641 돌핀 문 신호 추적(기록만) — 09-22~28 운영자 차 자동잠금 9회 중 7회 '주차~잠금 사이 문열림' 누락, 닫힘은 옴.
                    //   원값·이전값·폰 전송 여부를 남겨 어디서 빠지는지 확정.
                    Object pv = relay.getStatus("door_driver"), pts = relay.getStatus("door_change_ts");
                    try { relay.updateFromEvent(featureName, String.valueOf(intVal)); } catch (Exception ignored) {}
                    Object nts = relay.getStatus("door_change_ts");
                    postToD1("DOORDIAG", "이벤트 " + featureName + "=" + intVal + " 이전 운전석=" + pv + " → " + (featureName.contains("LEFT_FRONT") ? (nts != null && !nts.equals(pts) ? "폰 전송" : "전송 안 함(무변화/최초값)") : "운전석 아님"));
                    if (featureName.contains("LEFT_FRONT") && BuildConfig.IS_OPERATOR) {   // ★10-04 D3 문 게터 탐침(운영자판, 별도 스레드 — 이벤트 스레드 안 막게)
                        final String w = "운전석 이벤트=" + intVal;
                        new Thread(() -> d3DoorProbe(w), "d3doorprobe").start();
                    }
                } else {
                try { relay.updateFromEvent(featureName, String.valueOf(intVal)); } catch (Exception ignored) {}
                }
                if ("AC_TEMP_OUT".equals(featureName)) { try { relay.putStatus("out_temp", intVal); } catch (Exception ignored) {} }   // ★외기온도 → 앱 차량상태
                // 진단: 정확 feature 확정용 이벤트 캡처(BUZZLE 노이즈만 제외). 전용 도메인 우선 포함.
                if (!featureName.equals("INSTRUMENT_ALARM_BUZZLE_STATE") && (evtCapAll || !featureName.startsWith("id"))) {
                    try { captureEvt(deviceName + ":" + featureName, String.valueOf(intVal)); } catch (Exception ignored) {}
                }
                // 전용 채널(노이즈에 안 밀림): 벨트/도어잠금/충전 이벤트 별도 보존
                try {
                    String u = featureName.toUpperCase();
                    if (deviceName.equals("safetybelt") || u.contains("SAFETYBELT") || u.contains("SAFETY_BELT") || u.contains("SEATBELT")
                            || u.contains("CPD") || u.contains("SAFEBELT") || u.contains("PRESENCE") || u.contains("SECOND_ROW") || u.contains("SAFE_WARN"))
                        captureDom(beltEvts, "belt", deviceName + ":" + featureName, String.valueOf(intVal));
                    else if (deviceName.equals("doorlock") || u.contains("DOOR_LOCK") || u.contains("DOORLOCK"))
                        captureDom(lockEvts, "lock", deviceName + ":" + featureName, String.valueOf(intVal));
                    else if (deviceName.equals("charging") || deviceName.equals("power") || u.contains("SOC") || u.contains("ENDURANCE") || u.contains("BATTERY"))
                        captureDom(chgEvts, "chg", deviceName + ":" + featureName, String.valueOf(intVal));
                } catch (Exception ignored) {}
                // 진단(BSD 좌우 탐색): ADAS 사각지대/도어경보/차선변경 관련 이벤트 별도 캡처 → 릴레이 _bsd
                if (deviceName.equals("adas") && (featureName.contains("BSD") || featureName.contains("BLIND")
                        || featureName.contains("DOW") || featureName.contains("LCA") || featureName.contains("RCTA")
                        || featureName.contains("LEFT") || featureName.contains("RIGHT") || featureName.contains("WARN"))) {
                    try { captureBsd(featureName, String.valueOf(intVal)); } catch (Exception ignored) {}
                }
                if (deviceName.equals("adas")) { try { bsdLrObserve(featureId, featureName, intVal); } catch (Throwable ignored) {} }   // ★715 BSD 좌우 관측(D1 BSDLR)
            }

            // BSD 경보 처리 (로그 필터 이전 — 필터돼도 경보는 동작)
            if ("ADAS_BSD_STATE".equals(featureName)) {
                handleBsd(intVal);
                bsdExp.onBsdEvent(intVal);   // ★602 BSD실험 이벤트
            } else if ("LIGHT_TURN_SIGNAL_LIGHT_SWITCH_STATE".equals(featureName)) {
                boolean now = (intVal != 1 && intVal != 0);
                boolean was = turnSignalActive;
                turnSignalActive = now;
                if (now) lastTurnSignalOnTs = System.currentTimeMillis();   // ★깜빡이 켠 시각 — 차로유지 해제 이벤트가 이보다 먼저 와도 억제 커버(최근성 판정용)
                if (now && !was) bsdExp.startForTurn();   // ★723 BSD 방향 확인용 측면 카메라 — 경보 판정보다 먼저 연다
                // 깜빡이를 새로 켰는데 이미 사각지대에 차가 있으면(BSD=2) 경보 — 타이밍(깜빡이가 나중) 대응
                if (now && !was && lastBsdValue == 2) {
                    boolean alertOn = (prefs != null) && prefs.getBoolean(KEY_ALERT, false);
                    if (alertOn && autoAllowed()) bsdBeep("깜빡이 켬(BSD 이미 감지)");   // ★712 깜빡이 쪽 측후방 카메라 확인
                }
                onTurnSignalEvent(intVal);   // ★클러스터 2단계(2026-09-17) — 이벤트 즉시 getter로 좌/우 확정 → turnflash 푸시·측면 카메라(폴 600ms 안 기다림)
                bsdExp.onTurnSignal(intVal);   // ★602 BSD실험 깜빡이 이벤트
            }

            // NoiseFilter 적용 (RAW 전체캡처 모드에선 필터 OFF — 반복 신호도 전부 기록)
            boolean raw = prefs != null && prefs.getBoolean(KEY_DIAG, false);
            if (!raw && !NoiseFilter.shouldLog(deviceName, featureName, String.valueOf(intVal))) {
                return;
            }

            LogEntry entry = new LogEntry(System.currentTimeMillis(), deviceName, featureName, String.valueOf(intVal));
            logEntry(entry);

            // 깜빡이/BSD 강조
            if (featureName.contains("LIGHT") || featureName.contains("TURN") ||
                featureName.contains("BLIND_SPOT") || featureName.contains("BSD") ||
                featureName.contains("COLLISION")) {
                Log.d(TAG, "HIGHLIGHT: " + deviceName + " | " + featureName + " = " + intVal);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in onVehicleEvent", e);
        }
    }

    // 값 객체(BYDAutoEventValue)의 모든 필드 + toString 덤프 → 좌/우 방향 필드 탐색용
    private String dumpValue(Object v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> c = v.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        sb.append(f.getName()).append("=").append(fmt(f.get(v))).append(";");
                    } catch (Exception ignored) {}
                }
                c = c.getSuperclass();
            }
            for (Method m : v.getClass().getMethods()) {
                String mn = m.getName();
                if (m.getParameterCount() == 0 && (mn.startsWith("get") || mn.startsWith("is"))
                        && !mn.equals("getClass")) {
                    try { sb.append(mn).append("()=").append(fmt(m.invoke(v))).append(";"); }
                    catch (Exception ignored) {}
                }
            }
            sb.append("toString=").append(String.valueOf(v).replace(",", " ").replace("\n", " "));
        } catch (Exception e) {
            sb.append("dumpErr:").append(e.getMessage());
        }
        return sb.toString().replace(",", " ");
    }

    private String fmt(Object val) {
        if (val == null) return "null";
        if (val instanceof byte[]) return java.util.Arrays.toString((byte[]) val);
        if (val instanceof int[]) return java.util.Arrays.toString((int[]) val);
        if (val instanceof boolean[]) return java.util.Arrays.toString((boolean[]) val);
        if (val instanceof Object[]) return java.util.Arrays.toString((Object[]) val);
        return String.valueOf(val).replace(",", " ");
    }

    // BSD_STATE 값 변화 처리: 2=차량 감지, 1=안전. 1→2 순간 경보음.
    private void handleBsd(int value) {
        if (value == lastBsdValue) return;
        lastBsdValue = value;
        lastBsdStateForUi = value;
        boolean detected = (value == 2);
        bsdDetected = detected;
        final int val = value;
        if (mainHandler != null) {
            mainHandler.post(() -> { if (bsdListener != null) bsdListener.onBsdChanged(val); });
        }
        // 감지(값 2)일 때만 경보음. 값 1(안전)은 무음.
        if (value == 2) {
            boolean alertOn = (prefs != null) && prefs.getBoolean(KEY_ALERT, false);
            boolean turnOnly = (prefs != null) && prefs.getBoolean(KEY_TURN_ONLY, false);
            if (alertOn && autoAllowed() && (!turnOnly || turnSignalActive)) {
                bsdBeep("BSD 감지");
            }
        }
    }

    // ★712 BSD 방향 확인(운영자 10-03 "깜빡이 때 그 방향 측후방에 차가 있을 때만") — 돌핀은 차 BSD 신호가 좌우 합산 하나라 반대편 차에도 울렸다
    //   (10-03 채점: 깜빡이+BSD 실제 2건이 둘 다 반대편 차). 양쪽 측면 카메라에서 측후방만 사이드미러처럼 펴(화각 163·좌 −45°/우 +45°)
    //   차량(승용·이륜·버스·트럭)을 찾고, 반대편에만 차가 보이면 울리지 않는다. 최근 1.5초 안 카메라 프레임(BSD 실험 세션·클러스터 공유)이나 인식기가 없으면 종전대로 울린다.
    //   깜빡이가 꺼져 있거나 비상등이면 방향 정보가 없으니 종전대로. 운영자판·돌핀만, prefs bsd_cam(기본 켬).
    private com.cseini.motiondetect.KickDetector bsdKd;
    private final java.util.concurrent.atomic.AtomicBoolean bsdCamBusy = new java.util.concurrent.atomic.AtomicBoolean(false);
    private void bsdBeep(final String why) {
        boolean camGate = BuildConfig.IS_OPERATOR && !DiLink5Support.isDiLink5() && turnSignalActive && (prefs == null || prefs.getBoolean("bsd_cam", true));
        if (!camGate) { beep(); return; }
        if (!bsdCamBusy.compareAndSet(false, true)) return;   // 이미 판정 중
        final long callTs = System.currentTimeMillis();
        new Thread(() -> {
            boolean beeped = false;
            try {
                int tf = 1; long until = callTs + 500;   // 깜빡이 좌/우는 getter로 최대 150ms 늦게 확정된다
                while (true) {
                    Object o = relay != null ? relay.getStatus("turnflash") : null;
                    tf = (o instanceof Integer) ? (Integer) o : 1;
                    if (tf == 2 || tf == 4 || System.currentTimeMillis() > until) break;
                    Thread.sleep(50);
                }
                if (tf != 2 && tf != 4) { beep(); postToD1("BSDCAM", why + " — 깜빡이 방향 미확정(" + tf + "), 종전대로 울림"); return; }
                // ★723 깜빡이와 함께 막 연 측면 카메라 — 첫 화면을 호출부터 최대 1초까지 기다린다(못 받으면 종전대로 울림)
                long w0 = System.currentTimeMillis();
                while (bsdExp.active && (bsdExp.lastNv == null || System.currentTimeMillis() - bsdExp.lastNvMs > 1500) && System.currentTimeMillis() - callTs < 1000) Thread.sleep(30);
                final long waited = System.currentTimeMillis() - w0;
                final String waitNote = waited >= 30 ? " · 첫 화면 대기 " + waited + "ms" : "";
                final byte[] f = bsdExp.lastNv; final int w = bsdExp.lastW, h = bsdExp.lastH; final long age = System.currentTimeMillis() - bsdExp.lastNvMs;
                if (f == null || age > 1500) { beep(); postToD1("BSDCAM", why + " — 카메라 프레임 " + (f == null ? "없음" : age + "ms 전") + waitNote + ", 종전대로 울림"); return; }
                if (bsdKd == null) bsdKd = new com.cseini.motiondetect.KickDetector(getApplicationContext());
                if (!bsdKd.isReady()) { beep(); postToD1("BSDCAM", why + " — 인식기 준비 안 됨, 종전대로 울림"); return; }
                long t0 = System.currentTimeMillis();
                // 안전 쪽: 반대편에서 차가 보이고 깜빡이 쪽엔 안 보일 때만 막는다(10-03 채점 — 헛경보 2건은 둘 다 반대쪽 0.3~0.79). 양쪽 다 못 보면
                //   (바짝 붙어 일부만 보이는 차 등 카메라가 놓친 경우, BSD 2인데 못 찾은 비율 13%) 차 BSD 신호를 믿고 울린다.
                //   ★723 깜빡이 쪽에 차가 보이면 반대쪽은 볼 것 없이 바로 울린다(위험한 경우의 경고 지연을 인식 1번으로).
                float near = bsdSideScore(f, w, h, tf);
                if (near >= 0.3f) { beep(); beeped = true; }
                long tNear = System.currentTimeMillis() - t0;
                float far = bsdSideScore(f, w, h, tf == 2 ? 4 : 2);
                boolean skip = near < 0.3f && far >= 0.3f;
                if (!skip && near < 0.3f) beep();
                int[] tr = tileRect(w, h, tf);
                postToD1("BSDCAM", why + " 깜빡이 " + (tf == 2 ? "좌" : "우") + " — 그쪽 측후방 차 " + String.format(java.util.Locale.US, "%.2f", near) + " · 반대쪽 " + String.format(java.util.Locale.US, "%.2f", far)
                        + " → " + (skip ? "안 울림(반대편 차)" : (near >= 0.3f ? "울림(그쪽 차)" : "울림(카메라 못 봄, BSD 신호 따름)")) + " · 프레임 " + w + "x" + h + " 칸 " + tr[0] + "," + tr[1] + " " + tr[2] + "x" + tr[3]
                        + " · " + age + "ms 전" + waitNote + " · 판정 " + (System.currentTimeMillis() - t0) + "ms(그쪽 " + tNear + "ms) · 호출~판정 " + (System.currentTimeMillis() - callTs) + "ms");
            } catch (Throwable t) { if (!beeped) beep(); postToD1("BSDCAM", why + " — 판정 예외 " + t.getClass().getSimpleName() + ", 종전대로 울림"); }
            finally { bsdCamBusy.set(false); }
        }, "bsdcam").start();
    }
    // 측면 카메라 칸에서 측후방(좌캠은 화면 왼쪽·우캠은 오른쪽 = 차 뒤쪽, 배경 흐름으로 확인)만 겨냥해 320×320으로 펴고 차량 최고 점수.
    private float bsdSideScore(byte[] f, int w, int h, int side) {
        int[] tr = tileRect(w, h, side); int qw = tr[2], qh = tr[3]; boolean left = side == 2;
        int rx = left ? 0 : (int) (0.74 * qw), ry = (int) (0.22 * qh), rw = (int) (0.26 * qw), rh = (int) (0.36 * qh);
        com.cseini.motiondetect.ViewWarp vw = new com.cseini.motiondetect.ViewWarp(tr[0], tr[1], qw, qh, rx, ry, rw, rh, true, 163f, left ? -45f : 45f, false, 320, 320, false, 0f, 0f, 0f, 0f);
        try {
            byte[] out = new byte[320 * 320 * 3 / 2];
            if (!vw.remap(f, w, h, out)) return 0f;
            float[] r = bsdKd.analyzeClass(out, 320, 320, 0, 0, 320, 320, new int[]{2, 3, 5, 7});
            return r == null ? 0f : r[0];
        } finally { vw.close(); }
    }

    // BSD 경보음 톤 후보 (헤드유닛 ToneGenerator, STREAM_ALARM). 인덱스는 앱 '알림음' 선택과 1:1.
    private static final int[] BSD_TONES = {
        android.media.ToneGenerator.TONE_CDMA_HIGH_L,            // 0 더블 고음(기본, 기존 동작 유지)
        android.media.ToneGenerator.TONE_PROP_BEEP2,            // 1 부드러운 삑삑
        android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, // 2 경고 차임
        android.media.ToneGenerator.TONE_SUP_ERROR,            // 3 강한 경고(3연음)
    };
    private int bsdTone() {
        int idx = (prefs != null) ? prefs.getInt(KEY_SOUND, 0) : 0;
        if (idx < 0 || idx >= BSD_TONES.length) idx = 0;
        return BSD_TONES[idx];
    }

    // 경보음 (더블 발생, 1.2초 쿨다운). 톤은 앱에서 선택(KEY_SOUND).
    public void beep() {
        long now = System.currentTimeMillis();
        if (now - lastBeepMs < 1200) return;
        lastBeepMs = now;
        // ★833 좌우 스피커 구분(운영자 10-09) — 깜빡이 방향(turnflash 2=좌·4=우) 쪽을 크게. 모르면 양쪽 같게.
        Object tfo = relay != null ? relay.getStatus("turnflash") : null;
        int side = (tfo instanceof Integer && ((Integer) tfo == 2 || (Integer) tfo == 4)) ? (Integer) tfo : 0;
        playBsd(bsdIdx(), (prefs != null ? prefs.getInt(KEY_BSD_VOL, 100) : 100) / 100f, side);
        // ★712 BSD 발화 로그(피드백분석 세션 요청 10-03) — 실제 경보 횟수를 셀 수 있게. 값·속도·깜빡이·소리채널 한 줄.
        try { postToD1("BSDFIRE", "BSD 경보 — 값=" + lastBsdValue + " 방향=" + (side == 2 ? "왼쪽" : side == 4 ? "오른쪽" : "양쪽") + " 속도=" + (Double.isNaN(lastSpeed) ? "?" : String.valueOf((int) Math.round(lastSpeed))) + " 깜빡이=" + (turnSignalActive ? "켜짐" : "꺼짐") + " 소리idx=" + bsdIdx()); } catch (Throwable ignore) {}
    }
    private int bsdIdx() {
        int idx = (prefs != null) ? prefs.getInt(KEY_SOUND, 0) : 0;
        return (idx < 0 || idx >= BSD_TONES.length) ? 0 : idx;
    }
    // ★629 BSD 비프를 SoundPool로(알림음 통합, 운영자 09-28 "ICC만 크고 나머지 작다") — ToneGenerator 볼륨은 dB(로그) 변환이라
    //   슬라이더 70%≈−15dB로 다른 알림보다 훨씬 작았다. 톤 4종을 다른 알림과 같은 크기의 wav(se_bsd_0~3, 기존 톤 근사·2연타 포함)로 만들어
    //   같은 선형 음량·같은 경로(덕킹·채널=icc_channel, 앱은 bsd/lane 채널을 늘 같은 값으로 보냄)로 재생. 음원 로드 실패 시 기존 톤 폴백.
    private void playBsd(int toneIdx, float vol) { playBsd(toneIdx, vol, 0); }
    private void playBsd(int toneIdx, float vol, int side) {
        if (toneIdx < 0 || toneIdx >= BSD_TONES.length) toneIdx = 0;
        int idx = BSD_SOUND_BASE + toneIdx;
        if (iccSoundPool != null && idx < iccSoundIds.length && iccSoundIds[idx] != 0) { playIccSoundRaw(idx, vol, side); return; }
        playTone(BSD_TONES[toneIdx]);
    }

    // 경보/미리듣기 공통 발생기 — 선택 톤 2연타. (미리듣기는 쿨다운 무시하고 직접 호출)
    private void rebuildToneGen() {
        try {
            if (toneGen != null) toneGen.release();
            toneGen = new android.media.ToneGenerator(CH_STREAM[chIdx(KEY_BSD_CHANNEL)], prefs.getInt(KEY_BSD_VOL, 100));
        } catch (Exception e) {
            Log.w(TAG, "ToneGenerator rebuild failed", e);
        }
    }

    private void playTone(final int tone) {
        try {
            if (toneGen == null) return;
            duckMediaBriefly(CH_USAGE[chIdx(KEY_BSD_CHANNEL)], 700);   // 200+260+200ms 더블비프 커버
            toneGen.startTone(tone, 200);
            if (mainHandler != null) {
                mainHandler.postDelayed(() -> {
                    try { if (toneGen != null) toneGen.startTone(tone, 200); }
                    catch (Exception ignored) {}
                }, 260);
            }
        } catch (Exception e) {
            Log.w(TAG, "beep failed", e);
        }
    }

    public boolean isBsdDetected() { return bsdDetected; }

    public void logImpactEvent(long timestampMs, String isoTime, float peakG) {
        try {
            LogEntry entry = new LogEntry(timestampMs, "impact", "IMPACT", "g=" + String.format("%.2f", peakG));
            logEntry(entry);
            Log.d(TAG, "Impact event logged: " + peakG + " m/s²");
        } catch (Exception e) {
            Log.e(TAG, "Error in logImpactEvent", e);
        }
    }

    private void logEntry(LogEntry entry) {
        try {
            logs.add(entry);

            // EventLoggerSingleton에 추가 (리스너 트리거)
            EventLoggerSingleton.getInstance().addLog(entry);

            // CSV에 쓰기
            if (csvWriter != null) {
                synchronized (csvWriter) {
                    try {
                        csvWriter.write(entry.getCSVLine() + "\n");
                        csvWriter.flush();
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to write CSV", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in logEntry", e);
        }
    }

    @Override
    public void onDestroy() {
        bsdExp.stopNow("destroy");   // ★602 BSD실험 정리
        stopRemoteWeb();   // ★웹 세컨드스크린 서버 정리
        markLife("onDestroy");   // ★v44 사인 추적 — 정상 종료 경로를 탔음을 기록
        postToD1("FORENSIC", "onDestroy — 서비스 정상 종료 경로(stopService/강제중지 계열)");
        super.onDestroy();
        pollRunning = false;
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        try { android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
              if (am != null && wakePI != null) am.cancel(wakePI); } catch (Throwable ignored) {}
        if (csvWriter != null) {
            try {
                csvWriter.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close CSV writer", e);
            }
        }
        if (toneGen != null) {
            try { toneGen.release(); } catch (Exception ignored) {}
            toneGen = null;
        }
        instance = null;
    }
}
