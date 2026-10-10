package com.cseini.bydsniffer.sentry;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// 센트리 상태기계. docs/SENTRY-DESIGN.md 1절, docs/SENTRY-IMPL-CONTRACT.md 모듈 C.
// 스레드 규칙:
//  - 제어(상태 전이·타이머·호스트 호출·인코더 생성/정지)는 전부 핸들러 스레드 하나("sentry-mgr")에서만.
//  - 프레임 스레드(onFrame)는 인코더 큐에 넣고 감지만 돌린 뒤 반환 — 무거운 일은 핸들러로 넘긴다.
//  - 인코더 스레드(onSample)와 핸들러가 함께 만지는 녹화 자료(writer·eventId·조각 목록)는 lock으로 보호.
//  - 호스트 호출(startSentryDetect/stopSentryDetect)과 인코더 start/stop은 lock을 쥔 채로 부르지 않는다(교착 방지).
public final class SentryManager {
    public enum State { IDLE, ARMED, RECORDING, PAUSED }

    private static final SentryManager INSTANCE = new SentryManager();
    public static SentryManager get() { return INSTANCE; }
    private SentryManager() {}

    private static final long ARM_DELAY_MS = 20_000L;       // 시동 OFF 뒤 첫 게이트 평가
    private static final long RECOVER_DELAY_MS = 5_000L;    // 재기동 복구
    private static final long SEG_MS = 60_000L;             // 조각 길이
    private static final long EVENT_MS = 600_000L;          // 연속 녹화 이벤트 길이
    private static final long ORPHAN_MS = 10 * 60_000L;     // 고아 .part 기준
    private static final long RESTART_GAP_MS = 1_500L;      // 설정 변경 재시작 때 stop→start 간격
    private static final long REEVAL_DELAY_MS = 3_000L;    // reevaluate() 합치기 지연 — 호스트 stop→start 연속 호출이 가라앉은 뒤 게이트를 본다
    private static final long OWN_STOP_IGNORE_MS = 2_000L;  // 우리가 멈춘 직후 들어오는 세션 종료 신호 무시
    private static final long STALL_MS = 120_000L;          // 프레임 끊김 경고 기준
    private static final int REMOTE_LIST_MAX_CHARS = 6500;   // 워커가 명령 결과를 8000자로 자르므로 data는 7000자 이내
    private static final int REMOTE_THUMB_MAX_CHARS = 6000;
    private static final Pattern REMOTE_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern REMOTE_MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");
    private static final String PREF_ACTIVE = "sentry_active";
    private static final String PREF_PAUSED_LOC = "sentry_paused_by_location";
    private static final String PREF_IGN_OFF_WALL = "sentry_ign_off_wall";

    private final Object lock = new Object();
    private volatile SentryHost host;
    private volatile Handler handler;
    private volatile SentrySettings settings;
    // ── 객체 판정(10-09 운영자: 사람·차량을 알아보고 박스+종류+%) ─────────────────────────────────────
    // 움직임 직전 정지 상태의 "기준 화면"을 3초마다 3장 돌려가며 쥐고, 이벤트가 시작되면 그 화면과 지금 화면에서 각각 객체를 찾아 기준에 없던 것만 새 물체로 본다(주차된 차 제외).
    private static final long BASE_EVERY_MS = 3000L, BASE_MIN_AGE_MS = 4000L, BASE_MAX_AGE_MS = 25_000L;
    private final Object baseLock = new Object();
    private final byte[][] baseBuf = new byte[3][];
    private final long[] baseTs = new long[3];
    private int baseIdx;
    private long lastBaseCapture;
    private final ExecutorService classifyExec = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "sentry-classify"); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });
    private volatile long classifyDue;       // 다음 재판정 시각(elapsedRealtime), 0=없음
    private volatile String classifyId;
    private volatile int classifyAttempt;
    private boolean noBaseLogged;
    // 알림은 객체 판정 뒤로 미룬다(조명 번쩍임이면 안 보내려고, 운영자 10-09). 0=보류 중 1=보냄 2=조명 의심이라 안 보냄
    private static final long NOTIFY_MAX_DELAY_MS = 5000L;
    private final Object notifyLock = new Object();
    private String notifyId;
    private int notifyState;
    private volatile boolean eventLight;   // 진행 중인 이벤트가 조명 번쩍임 의심인가
    private final List<float[]> eventObjects = new ArrayList<>();   // 진행 중인 이벤트에서 알아낸 객체 {종류,점수%,칸,x0,y0,x1,y1}
    private final SentryMarks marks = new SentryMarks();   // 진행 중인 이벤트의 시간축 표시(움직임 이벤트만, 연속 녹화는 감지기가 없어 비어 있음)
    private volatile SentryStorage storage;
    private volatile SentryIndex index;

    private volatile State state = State.IDLE;
    private volatile String storageYield = null;   // 호스트가 외장 볼륨을 내리려고 양보를 요청한 사유(null=없음) — 게이트 "저장소 내림"
    private volatile String why = "";
    private volatile String err = "";
    private volatile String statusCache;       // 마지막 statusJson/publishStatus 결과(JSON 문자열) — cachedStatusJson용
    private volatile String fullStatusCache;   // 전체 상태(storage 객체 포함) — 핸들러 스레드가 갱신, cachedStatusJson이 우선 사용
    private volatile boolean pausedLoc;
    private volatile String lastEvent = "";
    private volatile int eventsCache;
    private volatile long usedMbCache;
    private volatile long ignOffAtUptime;      // 0 = 이 프로세스에서 시동 OFF를 못 봄
    private volatile long lastOwnStopUptime;
    private volatile int sessionGen;           // 카메라 세션(무장) 단위 번호 — 호스트가 세션 종료 신호에 실어 보낸다
    private volatile long lastFrameElapsed;
    private volatile long lastMotionElapsed;
    private long lastOrphanElapsed;            // 핸들러 스레드 전용
    private volatile State publishedState;     // ★마지막으로 relay_state에 올린 상태·사유 — 꺼짐+IDLE이 이 값과 같으면 tick은 디스크를 안 건드린다
    private volatile String publishedWhy;

    // 파이프라인(인코더·감지). 프레임 스레드가 읽으므로 volatile
    private volatile PreRollEncoder encoder;
    private volatile SentryMotion motion;
    private volatile int frameW, frameH;
    private volatile boolean pipeMotionOnly;
    private final AtomicBoolean pipeBuilding = new AtomicBoolean();
    private final AtomicBoolean motionTrigPending = new AtomicBoolean();
    private final AtomicBoolean clearing = new AtomicBoolean();   // 녹화 전체 삭제 진행 중 — 게이트가 막아 새 이벤트를 안 만든다
    private final AtomicBoolean failPosted = new AtomicBoolean();
    private final AtomicReference<String> thumbWant = new AtomicReference<>();

    // 녹화 자료 — lock 보호
    private int pipeGen;
    private boolean recording;
    // ★핸들러 정지 진단(10-06 공유판 193407: 주차 감지 공유가 끝난 뒤 2시간 넘게 PAUSED '공유하던 주차 감지가 끝남'에 머묾 — 틱이 도는데 상태가 안 바뀌어 핸들러 스레드가 막힌 것으로 의심)
    //   공유판은 SENTRY 로그가 서버로 안 올라오므로 상태값(relay_state.sentry_stall)에 정지 시간과 막힌 곳(스택 위쪽 몇 줄)을 남긴다.
    private HandlerThread handlerThreadRef;
    private volatile long lastTickRunElapsed;
    private long lastStallDiagElapsed;
    private boolean stallReported;
    private static final long STALL_DIAG_MS = 180_000L;
    // ★저장소 입출력 분리(10-06 FB2106·공유판 193407 — 외장(SD·USB)이 마운트로 보이는데 입출력이 죽으면 무장·상태 업로드의 파일·바인더 호출이 안 돌아와 핸들러 스레드가 통째로 멈췄다).
    //   무장·주기 점검·상태 업로드의 저장소 입출력은 별도 'sentry-io' 스레드에서 돌린다. 무장은 5초 안에 못 끝내면 내장 저장소로 진행하고 상태에 ioDelay를 남긴다.
    private final java.util.concurrent.ExecutorService ioExec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "sentry-io"); t.setDaemon(true); return t; });
    private final java.util.concurrent.atomic.AtomicBoolean ioBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> ioQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean publishPending = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean tickIoPending = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long ioRunStartElapsed;   // 지금 도는 입출력 작업의 시작 시각(0=없음) — 오래 안 끝나면 막힌 것
    private volatile boolean ioDelayed;
    private static final long IO_TIMEOUT_MS = 5_000L;
    private static final String PREF_TRACE = "sentry_trace";
    private final java.util.ArrayDeque<String> traceBuf = new java.util.ArrayDeque<>();
    private String lastTraceGate = "";
    // ★사람·차량 거르기(YOLO11n, 10-07 되살림) — 움직임이 잡히면 4칸에서 사람·차량 점수를 재서 기준을 넘을 때만 이벤트를 시작한다. 한 번 못 넘어도 움직임이 이어지면
    //   1초마다 다시 보고(사람이 걸어 들어오는 중일 수 있음, 사전 녹화가 있어 늦게 시작해도 앞부분은 안 잃는다), 4초 동안 계속 못 넘으면 포기하고 4초 쉰다.
    private volatile long objectRejectUntil;
    private long objectFirstFail;
    static final float PERSON_MIN = 0.40f;
    static final float VEHICLE_MIN = 0.45f;
    private static final long OBJECT_RETRY_MS = 1_000L, OBJECT_WINDOW_MS = 4_000L, OBJECT_REJECT_MS = 4_000L;
    private int mountWaitTries;   // 외장을 골랐는데 아직 안 붙었을 때 무장 전에 기다린 횟수(핸들러 스레드 전용, 최대 4번·약 12초)
    private volatile boolean cutRequested;   // '지금 보기' 요청 — 다음 키프레임(최대 1초)에서 현재 조각을 닫고 새 조각을 시작한다
    private static final long CUT_MIN_MS = 2_000L;   // 너무 짧은 조각이 쌓이지 않게 조각이 이만큼은 돼야 자른다
    private volatile String eventId;
    private long eventStartMs, eventStartElapsed, segStartElapsed;
    private int segN;
    private SegmentWriter writer;
    private final List<SentryIndex.Segment> segments = new CopyOnWriteArrayList<>();
    private List<EncodedSample> pendingPreroll;
    private long lastPrePtsUs = Long.MIN_VALUE;   // 프리롤로 이미 쓴 마지막 pts — setLive 직후 겹쳐 들어오는 샘플 건너뛰기용

    // 조각 마감(fsync·rename)은 lock 밖 단일 스레드에서 — 인코더 스레드가 디스크 대기로 막히지 않게
    private final ExecutorService segExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sentry-segfin");
        t.setDaemon(true);
        return t;
    });
    private final Object finMon = new Object();
    private int finPending;                       // finMon 보호

    private final Runnable armTask = new Runnable() { @Override public void run() { safe(() -> evaluate(true)); } };
    private final Runnable restartTask = new Runnable() { @Override public void run() { safe(() -> evaluate(true)); } };
    private final Runnable reevalTask = new Runnable() {
        @Override public void run() {
            safe(() -> {
                if (state == State.PAUSED && !err.isEmpty()) return;
                evaluate(false);
            });
        }
    };
    private final Runnable motionTask = new Runnable() { @Override public void run() { safe(SentryManager.this::motionTick); } };

    // ───────────── 공개 API ─────────────

    public void init(SentryHost hs) {
        synchronized (this) {
            if (host != null) return;
            host = hs;
            settings = SentrySettings.load(hs.prefs());
            storage = new SentryStorage(hs.context(), settings);
            index = new SentryIndex(storage);
            pausedLoc = hs.prefs().getBoolean(PREF_PAUSED_LOC, false);
            HandlerThread ht = new HandlerThread("sentry-mgr");
            ht.start();
            handlerThreadRef = ht;
            lastTickRunElapsed = SystemClock.elapsedRealtime();
            handler = new Handler(ht.getLooper());
        }
        post(() -> {
            lastOrphanElapsed = SystemClock.elapsedRealtime();
            boolean wasActive = host.prefs().getBoolean(PREF_ACTIVE, false);
            if (settings.enabled || wasActive) {   // ★꺼진 채 한 번도 안 켠 차는 부팅 때도 고아 정리로 저장소를 건드리지 않는다
                final SentryStorage stI = storage;
                ioPost(() -> {
                    try {
                        int n = stI.cleanOrphans(ORPHAN_MS);
                        if (n > 0) log("고아 조각 정리 " + n + "개");
                    } catch (Throwable t) { log("고아 조각 정리 실패 — " + t); }
                });
            }
            ioPost(() -> { refreshStats(); publishStatusNow(); });
            try {   // 이전 실행의 마지막 흔적을 올린다(프로세스가 순간 종료됐어도 어디까지 갔는지 보이게)
                String prevTrace = host.prefs().getString(PREF_TRACE, "");
                if (prevTrace != null && !prevTrace.isEmpty()) host.putStatus("sentry_trace_prev", prevTrace);
            } catch (Throwable ignored) {}
            if (wasActive) {
                long offWall = host.prefs().getLong(PREF_IGN_OFF_WALL, 0L);
                long elapsed = System.currentTimeMillis() - offWall;
                long delay = recoverDelayMs(offWall, System.currentTimeMillis());
                if (offWall > 0 && elapsed >= 0 && elapsed < ARM_DELAY_MS) ignOffAtUptime = SystemClock.uptimeMillis() - elapsed;
                log("재기동 복구 — " + (delay / 1000) + "초 뒤 센트리 게이트 평가");
                handler.postDelayed(restartTask, delay);
            }
        });
    }

    // 호스트 프레임 루프에서 매 프레임. 10ms 안에 반환.
    public void onFrame(byte[] nv21, int w, int fh, long nowMs) {
        State st = state;
        if (st != State.ARMED && st != State.RECORDING) return;
        lastFrameElapsed = SystemClock.elapsedRealtime();
        if (armFailures > 0) {   // 프레임이 2분 넘게 끊김 없이 흘렀으면 카메라가 안정된 것 — 재시도 간격을 처음으로
            if (firstFrameAt == 0L) firstFrameAt = lastFrameElapsed;
            else if (lastFrameElapsed - firstFrameAt >= 120_000L) { armFailures = 0; firstFrameAt = 0L; }
        }
        PreRollEncoder e = encoder;
        if (e == null || w != frameW || fh != frameH) {
            if (pipeBuilding.compareAndSet(false, true)) post(() -> buildPipeline(w, fh));
            return;
        }
        String tid = thumbWant.getAndSet(null);
        if (tid != null) {
            byte[] copy = nv21.clone();
            post(() -> saveThumb(tid, copy, w, fh));
        }
        e.feed(nv21, nowMs);
        if (st == State.ARMED && nowMs - lastBaseCapture >= BASE_EVERY_MS && settings.objectsOn()) captureBase(nv21, nowMs);   // 객체 인식을 껐으면 기준 화면 복사(7MB·3초마다)도 안 한다
        long due = classifyDue;
        if (due != 0 && nowMs >= due && st == State.RECORDING && classifyId != null && classifyId.equals(eventId)) {   // 이벤트 중 재판정 시각이 됐으면 이 프레임으로
            classifyDue = 0;
            final byte[] cp = nv21.clone();
            final String cid = classifyId;
            final int at = classifyAttempt;
            classifyExec.execute(() -> classifyEvent(cid, cp, w, fh, at));
        }
        SentryMotion mo = motion;
        boolean motionTrig = false;
        if (mo != null) {
            motionTrig = mo.onFrame(nv21, nowMs);
            long[][] hits = mo.drainConfirmed();
            if (hits.length > 0 && state == State.RECORDING) {   // 녹화 중 확정된 움직임을 시간축 표시로 모은다
                for (long[] h : hits) marks.add(h[0], (int) h[1], (int) h[2], 0);
            }
        }
        if (motionTrig) {
            if (state == State.RECORDING) {
                lastMotionElapsed = SystemClock.elapsedRealtime();
            } else if (state == State.ARMED && SystemClock.elapsedRealtime() >= objectRejectUntil && motionTrigPending.compareAndSet(false, true)) {
                byte[] copy = nv21.clone();
                post(() -> startMotionEvent(copy, w, fh));
            }
        }
    }

    public void onIgnition(boolean on) {
        post(() -> {
            if (on) {
                ignOffAtUptime = 0;
                handler.removeCallbacks(armTask);
                handler.removeCallbacks(restartTask);
                log("시동 ON — 센트리 즉시 중지");
                storageYield = null;
                stopSession(State.IDLE, "시동 켜짐", true);
            } else {
                ignOffAtUptime = SystemClock.uptimeMillis();
                handler.removeCallbacks(armTask);
                handler.postDelayed(armTask, ARM_DELAY_MS);
                if (settings.enabled) {
                    try { host.prefs().edit().putBoolean(PREF_ACTIVE, true).putLong(PREF_IGN_OFF_WALL, System.currentTimeMillis()).commit(); } catch (Throwable ignored) {}   // 직후 프로세스가 죽을 수 있어 즉시 기록
                    State st = state;
                    if (st == State.IDLE || st == State.PAUSED) setState(State.PAUSED, "시동 OFF 20초 대기");
                }
                log("시동 OFF — 20초 뒤 센트리 게이트 평가");
            }
        });
    }

    // 호스트 카메라 세션 종료(실시간 보기·다른 감지 시작 등)
    public void onSessionEnded(String reason) { sessionEnded(reason, -1); }

    // gen = 호스트가 센트리 카메라 세션을 시작할 때 sessionGen()으로 받아 둔 번호. 옛 세션의 종료 신호는 무시한다.
    public void onSessionEnded(String reason, int gen) { sessionEnded(reason, gen); }

    // ★766 호스트가 외장 볼륨(SD/USB)을 내리기 직전에 부른다(USB 유지 멈춤 — 보호·끔). vold는 열린 파일이 있는 프로세스를 죽이므로
    //   외장 선택이면 조각을 닫고 PAUSED "저장소 내림(사유)"로 멈춘 뒤 돌아온다(파일 닫힘 보장, 최대 15초). 내장 선택이면 아무것도 안 한다.
    //   양보 중에는 게이트가 막고, storageBack()(USB 유지 재개)·시동 ON에 풀린다. 어느 스레드에서 불러도 된다.
    public void yieldStorage(String why) {
        final String w = (why == null || why.isEmpty()) ? "USB 유지 멈춤" : why;
        runAndWait(() -> {
            SentrySettings s = settings;
            if (s == null || "internal".equals(s.storage)) return;
            storageYield = w;
            State st = state;
            if (st == State.ARMED || st == State.RECORDING) {
                log("저장소 양보 — " + w + " (조각 닫고 대기)");
                stopSession(State.PAUSED, "저장소 내림(" + w + ")", true);
            }
        }, 15);
    }

    // 호스트가 외장 볼륨을 다시 붙잡았을 때(USB 유지 재개) — 양보 해제 뒤 3초 뒤 게이트 재평가. 양보 중이 아니면 아무것도 안 한다
    public void storageBack() {
        if (storageYield == null) return;
        post(() -> {
            if (storageYield == null) return;
            storageYield = null;
            log("저장소 양보 해제 — 재평가");
            reevaluate();
        });
    }

    // 호스트 세션이 끝난 직후 호출 — 3초 뒤 게이트를 다시 평가해 재무장(연속 호출은 합침). 직전 실패(err)로 PAUSED면 tick 60초에만 맡김
    // ★외장 볼륨(SD·USB)이 마운트됐다는 호스트 알림(SnifferService MEDIA_MOUNTED 수신) — 폴백(내장에 대신 저장) 중이고 지금 이벤트가 없으면 바로 외장으로 복귀하고,
    //   녹화 중이면 건드리지 않는다(다음 이벤트 시작 전·조각 경계에서 복귀). 시동 OFF 직후 무장이 SD 마운트보다 2초 빨라 내장에 고정되던 것(10-06 18:12) 보완.
    public void onExternalMounted() {
        post(() -> {
            SentryStorage st = storage;
            if (st == null || !st.fallback()) return;
            synchronized (lock) { if (recording) return; }
            String prev = ioCall("저장소 복귀", 3000L, st::repinIfFallback, null);
            if (prev != null) {
                log("저장소 복귀(외장 마운트) " + prev + " → " + st.tier() + " " + st.root().getAbsolutePath());
                publishStatus();
            }
        });
    }

    public void reevaluate() {
        Handler hd = handler;
        if (hd == null) return;
        hd.removeCallbacks(reevalTask);
        hd.postDelayed(reevalTask, REEVAL_DELAY_MS);
    }

    // 공유판 /sentry/* 접속 인증 — 호스트가 요구하면(공유판) grant 검증을 통과해야 한다. 호스트가 없으면(초기화 전) 요구로 보아 거부한다(안전 쪽)
    public boolean lanAuthRequired() {
        SentryHost hs = host;
        return hs == null || hs.lanAuthRequired();
    }

    public boolean verifyGrant(String sgx, String sg) {
        SentryHost hs = host;
        return hs != null && sg != null && hs.verifySentryGrant(sgx, sg);
    }

    public int sessionGen() { return sessionGen; }

    private void sessionEnded(String reason, int gen) {
        post(() -> {
            State st = state;
            if (st != State.ARMED && st != State.RECORDING) return;
            if (gen >= 0 ? gen != sessionGen : SystemClock.uptimeMillis() - lastOwnStopUptime < OWN_STOP_IGNORE_MS) return;
            String r = (reason == null || reason.isEmpty()) ? "카메라 세션 종료" : reason;
            log("카메라 세션 종료 신호 — " + r);
            stopSession(State.PAUSED, r, false);
        });
    }

    public void tick() {
        post(this::tickTask);
        diagStall();
    }

    // 릴레이 루프 스레드에서 부른다(핸들러가 막혀도 동작). 마지막 틱 실행이 3분 넘게 없으면 핸들러 스레드 스택 위쪽을 상태에 남긴다(10분에 한 번)
    private boolean ioStuckReported;

    private void diagStall() {
        try {
            long nowIo = SystemClock.elapsedRealtime();
            long ist = ioRunStartElapsed;
            SentryHost hsIo = host;
            if (hsIo != null) {
                if (ist != 0 && nowIo - ist > 10_000L) {
                    hsIo.putStatus("sentry_io", "busy " + (nowIo - ist) / 1000 + "s");
                    ioStuckReported = true;
                } else if (ioStuckReported) {
                    ioStuckReported = false;
                    hsIo.putStatus("sentry_io", "");
                }
            }
        } catch (Throwable ignored) {}
        try {
            long now = SystemClock.elapsedRealtime();
            long last = lastTickRunElapsed;
            SentryHost hs = host;
            HandlerThread ht = handlerThreadRef;
            if (hs == null || ht == null || last == 0 || now - last <= STALL_DIAG_MS) return;
            if (now - lastStallDiagElapsed < 600_000L) return;
            lastStallDiagElapsed = now;
            StackTraceElement[] stk = ht.getLooper().getThread().getStackTrace();
            StringBuilder sb = new StringBuilder("stall ").append((now - last) / 1000).append("s state=").append(state.name()).append(" ").append(ht.getLooper().getThread().getState());
            for (int i = 0; i < Math.min(stk.length, 8); i++) {
                sb.append(" | ").append(stk[i].getClassName().replaceAll("^.*\\.", "")).append(".").append(stk[i].getMethodName()).append(":").append(stk[i].getLineNumber());
            }
            String out = sb.length() > 280 ? sb.substring(0, 280) : sb.toString();
            stallReported = true;
            hs.putStatus("sentry_stall", out);
            hs.log("센트리 핸들러 정지 의심 — " + out);
        } catch (Throwable ignored) {}
    }

    public void reconfigure() { runAndWait(() -> { reconfigureTask(); publishStatus(); }); }

    public JSONObject applySet(JSONObject partial) {
        SentryHost hs = host;
        if (hs == null) return errorJson("센트리 초기화 전");
        JSONObject p;
        try {
            p = partial == null ? new JSONObject() : new JSONObject(partial.toString());
        } catch (JSONException e) {
            return errorJson("설정 JSON 오류");
        }
        if (p.has("pausedByLocation")) {
            boolean v = p.optBoolean("pausedByLocation", false);
            String reason = p.optString("reason", "");
            p.remove("pausedByLocation");
            p.remove("reason");
            setPausedLoc(v, reason);
        }
        JSONObject applied;
        try {
            applied = SentrySettings.apply(hs.prefs(), p);
        } catch (Exception e) {
            log("센트리 설정 적용 실패 — " + e);
            return errorJson("설정 적용 실패");
        }
        reconfigure();
        JSONObject out = statusJson();
        try {
            out.put("settings", applied);
        } catch (JSONException e) {
            log("설정 응답 갱신 실패 — " + e);
        }
        return out;
    }

    public void pauseByPhone(String reason, boolean paused) {
        setPausedLoc(paused, reason);
        post(() -> evaluate(false));
    }

    public State state() { return state; }
    /** ★755 센트리 켬 여부(설정) — USB 유지가 무장 전(시동 OFF 직후 허브 절전 +11.6초)부터 붙잡도록 SnifferService가 본다. */
    public boolean enabled() { SentrySettings st = settings; return st != null && st.enabled; }

    // 실시간 보기 센트리 미리보기(scv)용 — 지금 저장된 칸별 어안 보정 값. 설정이 아직 없으면 null
    public org.json.JSONObject cellViewsForPreview() { SentrySettings st = settings; return st == null ? null : st.cellViews; }

    // 설계 2절 상태 JSON + settings + storage(volumesJson) + fps/drop/codec
    public JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            SentrySettings s = settings;
            SentryStorage st = storage;
            o.put("state", state.name());
            o.put("why", why);
            o.put("cut", true);   // 능력 표시 — 진행 중인 녹화의 현재 조각을 지금 닫아 곧바로 볼 수 있게 하는 '지금 보기'(/sentry/cut·SENTRY_CUT)를 지원한다
            o.put("clear", true);   // 능력 표시 — 녹화 전체 삭제(/sentry/clear·SENTRY_CLEAR)를 지원한다. 폰은 이 값이 있을 때만 전체 삭제 버튼을 보인다
            o.put("pausedByLocation", pausedLoc);   // 폰 안전 위치 일시중지 — 폰은 이 값을 먼저 보고, 없을 때만 why=="안전 위치" 문구로 폴백한다(문구를 바꾸지 말 것)
            if (st != null) {
                o.put("tier", st.tier());
                o.put("root", st.root().getAbsolutePath());
                o.put("limitMb", st.limitMb());
                o.put("fallback", st.fallback());
                String fw = st.fallbackWhy();
                if (fw != null) o.put("fallbackWhy", fw);   // 폴백 이유(unmounted|no_write)
                o.put("storage", st.volumesJson());
                o.put("storageKind", st.selectedKind());
            }
            o.put("usedMb", usedMbCache);
            o.put("events", eventsCache);
            o.put("lastEvent", lastEvent);
            o.put("err", err);
            PreRollEncoder e = encoder;
            o.put("fps", e == null ? 0 : e.encodedFpsLast10s());
            o.put("drop", e == null ? 0 : e.droppedFrames());
            String used = e == null ? null : e.codecInUse();
            o.put("codecInUse", used == null ? "" : used);
            if (s != null) {
                o.put("codec", s.codec);
                o.put("settings", s.toJson());
            }
        } catch (JSONException ex) {
            log("상태 JSON 생성 실패 — " + ex);
        }
        statusCache = o.toString();
        return o;
    }

    // 차단 없는 상태 조회(SENTRY_STATUS용) — 파일시스템을 만지지 않고 마지막으로 만든 상태 JSON을 돌려준다. 아직 없으면 state/why만
    public JSONObject cachedStatusJson() {
        String c = fullStatusCache;
        if (c == null) c = statusCache;
        if (c != null) {
            try {
                return new JSONObject(c);
            } catch (JSONException ex) {
                log("상태 캐시 읽기 실패 — " + ex);
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("state", state.name());
            o.put("why", why);
        } catch (JSONException ex) {
            log("상태 JSON 생성 실패 — " + ex);
        }
        return o;
    }

    // ★워커 경로 원격 목록(SENTRY_LIST) — relay-loop 인라인이라 하루 폴더 하나만 읽는다. 워커가 결과를 8000자로 자르므로 6500자 안으로
    public JSONObject remoteList(String yyyyMmDd) {
        SentryIndex ix = index;
        if (host == null || ix == null) return errorJson("센트리 초기화 전");
        if (yyyyMmDd == null || !REMOTE_DATE.matcher(yyyyMmDd).matches()) return errorJson("date");
        return buildRemoteList(yyyyMmDd, ix.listDate(yyyyMmDd, false), REMOTE_LIST_MAX_CHARS);
    }

    // ★지금 보기(LAN POST /sentry/cut·워커 SENTRY_CUT, 운영자 10-06) — 녹화 중인 조각은 1분이 차야 닫혀 재생할 수 없다. 알림을 받고 바로 보려는 사용자를 위해
    //   현재 조각을 지금 닫아(다음 키프레임, 최대 약 1초) 목록에 올린다. 반환: {ok, cut(실제로 닫았나), id(이벤트), state} — 닫힌 조각이 목록에 오를 때까지 최대 약 3초 기다린다(릴레이 명령 루프를 오래 잡지 않게).
    public JSONObject cutNow() {
        JSONObject o = new JSONObject();
        try {
            String id;
            synchronized (lock) {
                id = recording && writer != null && writer.isOpen() ? eventId : null;
                if (id != null) cutRequested = true;
            }
            o.put("state", state.name());
            if (id == null) {
                o.put("ok", true).put("cut", false).put("why", state == State.RECORDING ? "조각 준비 중" : "녹화 중이 아님");
                return o;
            }
            long until = SystemClock.elapsedRealtime() + 2000;
            while (SystemClock.elapsedRealtime() < until) {
                boolean rolled;
                synchronized (lock) { rolled = !cutRequested; }
                if (rolled) break;
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            boolean done;
            synchronized (lock) { done = !cutRequested; }
            if (done) {
                synchronized (finMon) {
                    long end = SystemClock.elapsedRealtime() + 1000;
                    while (finPending > 0 && SystemClock.elapsedRealtime() < end) {
                        try { finMon.wait(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }
            }
            o.put("ok", true).put("cut", done).put("id", id);
            if (!done) o.put("why", "곧 닫힘");
            log("지금 보기 — 현재 조각 " + (done ? "닫음" : "닫는 중") + " " + id);
        } catch (JSONException e) {
            throw new IllegalStateException("지금 보기 JSON 오류: " + e.getMessage(), e);
        }
        return o;
    }

    // ★녹화 전체 삭제(LAN POST /sentry/clear·워커 SENTRY_CLEAR) — 운영자 요청. 센트리 자기 폴더(모든 저장소 루트 아래 이벤트·썸네일·색인)만 지운다. confirm이 "all"이 아니면 거부.
    //   진행 중 이벤트는 마감한 뒤 지운다(세션을 멈추고 지우기를 새 스레드로 넘긴 뒤 끝나면 게이트를 다시 평가해 재무장). 응답의 deleted·freedMb는 지우기 시작 시점의 이벤트 수·용량이다(지우기는 뒤에서 이어짐)
    public JSONObject clearAll(String confirm) {
        SentryHost hs = host;
        SentryIndex ix = index;
        SentryStorage st = storage;
        if (hs == null || ix == null || st == null) return errorJson("센트리 초기화 전");
        if (!"all".equals(confirm)) return errorJson("confirm");
        if (!clearing.compareAndSet(false, true)) return errorJson("이미 삭제 중");
        int n;
        long mb;
        final List<File> roots;
        try {
            n = ix.allOldestFirst().size();
            mb = st.usedAllMb();
            roots = st.allRoots();
        } catch (Throwable t) {
            clearing.set(false);
            return errorJson("집계 실패");
        }
        log("녹화 전체 삭제 시작 — 이벤트 " + n + "개 " + mb + "MB (저장소 " + roots.size() + "곳)");
        post(() -> {
            State s = state;
            if (s == State.ARMED || s == State.RECORDING) stopSession(State.PAUSED, "전체 삭제 중", true);
            Thread th = new Thread(() -> {
                int dirs;
                try {
                    dirs = SentryIndex.clearRoots(roots);
                    log("녹화 전체 삭제 완료 — 월 폴더 " + dirs + "개");
                } catch (Throwable t) {
                    log("녹화 전체 삭제 오류 — " + t);
                } finally {
                    clearing.set(false);
                }
                ioPost(() -> { refreshStats(); publishStatusNow(); });
                post(this::reevaluate);
            }, "sentry-clear");
            th.start();
        });
        try {
            return new JSONObject().put("ok", true).put("deleted", n).put("freedMb", mb);
        } catch (JSONException e) {
            return errorJson("응답 생성 실패");
        }
    }

    private final Object combineLock = new Object();

    // ★이벤트 전체를 영상 하나로(다운로드·집에서 전체 받기) — 마감돼 색인에 오른 조각만 번호 순으로 이어 붙인다(재인코딩 없음, 진행 중인 조각은 제외).
    //   같은 이벤트·조각 수·크기면 만들어 둔 파일을 재사용한다.
    //   id 형식 오류는 IllegalArgumentException("id"), 나머지 실패는 IOException. tick은 조각마다 불린다(null 가능)
    public File combinedEvent(String id, Runnable tick) throws IOException {
        SentryIndex ix = index;
        SentryHost hs = host;
        if (ix == null || hs == null) throw new IOException("센트리 초기화 전");
        if (!SentryIndex.isValidId(id)) throw new IllegalArgumentException("id");
        SentryIndex.EventMeta meta = ix.read(id);
        if (meta == null || meta.segments.isEmpty()) throw new IOException("녹화 없음");
        List<SentryIndex.Segment> segs = new ArrayList<>(meta.segments);
        SentryIndex.sortSegmentsByN(segs);
        List<File> parts = new ArrayList<>();
        long bytes = 0;
        for (SentryIndex.Segment sg : segs) {
            File f = ix.segmentFile(id, sg.n);
            if (!f.isFile()) throw new IOException("조각 파일 없음 seg " + sg.n);
            parts.add(f);
            bytes += f.length();
        }
        File dir = new File(hs.context().getCacheDir(), "sentry-dl");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("임시 폴더 생성 실패");
        String name = combinedName(id, parts.size(), bytes);
        File out = new File(dir, name);
        synchronized (combineLock) {
            if (out.isFile() && out.length() > 0) return out;
            trimCombinedCache(dir, name, System.currentTimeMillis());
            File tmp = new File(dir, name + ".part");
            log("이벤트 합치기 시작 " + id + " 조각 " + parts.size() + "개 " + String.format(java.util.Locale.US, "%.1f", bytes / 1048576.0) + "MB");
            long t0 = SystemClock.elapsedRealtime();
            SentryRemux.concat(parts, tmp, tick);
            if (out.exists() && !out.delete()) throw new IOException("옛 파일 삭제 실패");
            if (!tmp.renameTo(out)) throw new IOException("임시 파일 이름 변경 실패");
            log("이벤트 합치기 완료 " + String.format(java.util.Locale.US, "%.1f", (SystemClock.elapsedRealtime() - t0) / 1000.0) + "초 · " + String.format(java.util.Locale.US, "%.1f", out.length() / 1048576.0) + "MB");
            return out;
        }
    }

    static String combinedName(String id, int parts, long bytes) {
        return id + "-" + parts + "-" + bytes + ".mp4";
    }

    // 합친 파일 캐시 정리 — 지금 만들 파일(keepName) 말고는 가장 최근 1개만 남기고, 2시간 넘은 것은 지운다(합친 파일은 수백 MB라 개수를 적게)
    static void trimCombinedCache(File dir, String keepName, long nowMs) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));   // 최신이 앞
        int kept = 0;
        for (File f : fs) {
            if (f.getName().equals(keepName)) continue;
            boolean old = nowMs - f.lastModified() > 2 * 3600_000L;
            if (old || kept >= 1 || f.getName().endsWith(".part")) f.delete();
            else kept++;
        }
    }

    // ★워커 경로 달력(SENTRY_CALENDAR) — 집에서도 달력에 녹화 있는 날 점을 찍는다. LAN /sentry/calendar와 같은 {"days":{"5":63,…}}(날짜별 이벤트 수, 한 달치라 작다)
    public JSONObject remoteCalendar(String yyyyMm) {
        SentryIndex ix = index;
        if (host == null || ix == null) return errorJson("센트리 초기화 전");
        if (!remoteMonthOk(yyyyMm)) return errorJson("month");
        return ix.calendarJson(yyyyMm);
    }

    static boolean remoteMonthOk(String yyyyMm) {
        return yyyyMm != null && REMOTE_MONTH.matcher(yyyyMm).matches();
    }

    static final int REMOTE_MARKS_MAX_CHARS = 7200;   // 워커 결과 8000자 제한 안

    // 원격 시간축 표시·객체 판정(SENTRY_MARKS, 10-09) — meta의 marks·objects와 알림 사진(박스 포함)을 줄여 base64로. 사진이 크기에 안 맞으면 뺀다
    public JSONObject remoteMarks(String id) {
        SentryIndex ix = index;
        if (host == null || ix == null) return errorJson("센트리 초기화 전");
        if (!SentryIndex.isValidId(id)) return errorJson("id");
        SentryIndex.EventMeta m = ix.read(id);
        if (m == null) return errorJson("이벤트 없음");
        try {
            JSONObject o = marksJson(m);
            File af = ix.alertFile(id);
            if (af.isFile()) {
                int budget = REMOTE_MARKS_MAX_CHARS - o.toString().length() - 24;
                String b64 = null;
                for (int w : new int[]{320, 240, 160}) {
                    Bitmap bmp = decodeScaled(af, w);
                    if (bmp == null) break;
                    try {
                        for (int q : new int[]{60, 45, 30}) {
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            if (!bmp.compress(Bitmap.CompressFormat.JPEG, q, bo)) break;
                            String c = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                            if (c.length() <= budget) { b64 = c; break; }
                        }
                    } finally {
                        bmp.recycle();
                    }
                    if (b64 != null) break;
                }
                if (b64 != null) o.put("alertJpg", b64);
            }
            return o;
        } catch (JSONException e) {
            return errorJson("표시 JSON 오류");
        }
    }

    // {"id","marks":[[t,kind,cell,pct,dur]…],"objects":[[kind,pct,cell,x0,y0,x1,y1]…],"retro":true} — 없는 키는 뺀다
    static JSONObject marksJson(SentryIndex.EventMeta m) throws JSONException {
        JSONObject full = m.toJson();
        JSONObject o = new JSONObject().put("id", m.id);
        if (full.has("marks")) o.put("marks", full.get("marks"));
        if (full.has("objects")) o.put("objects", full.get("objects"));
        if (m.retro) o.put("retro", true);
        if (m.light) o.put("light", true);
        return o;
    }

    // ★워커 경로 원격 썸네일(SENTRY_THUMB) — 폭 160 JPEG를 base64로(6000자 안). 넘으면 품질 → 폭 120 순으로 낮춘다
    public JSONObject remoteThumb(String id) {
        SentryIndex ix = index;
        if (host == null || ix == null) return errorJson("센트리 초기화 전");
        if (!SentryIndex.isValidId(id)) return errorJson("id");
        File f = ix.thumbFile(id);
        if (!f.isFile()) return errorJson("썸네일 없음");
        try {
            for (int w : new int[]{160, 120}) {
                Bitmap bmp = decodeScaled(f, w);
                if (bmp == null) return errorJson("썸네일 디코드 실패");
                try {
                    for (int q : new int[]{60, 45, 30}) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        if (!bmp.compress(Bitmap.CompressFormat.JPEG, q, bo)) return errorJson("썸네일 압축 실패");
                        String b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                        if (b64.length() <= REMOTE_THUMB_MAX_CHARS) return new JSONObject().put("id", id).put("jpg", b64);
                    }
                } finally {
                    bmp.recycle();
                }
            }
            return errorJson("썸네일 너무 큼");
        } catch (JSONException e) {
            return errorJson("썸네일 JSON 오류");
        }
    }

    // 표본 축소 디코드 뒤 폭을 정확히 outW로 맞춘다(원본이 더 작으면 그대로)
    private static Bitmap decodeScaled(File f, int outW) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), b);
        if (b.outWidth <= 0 || b.outHeight <= 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = SentryIndex.sampleSize(b.outWidth, outW);
        Bitmap full = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (full == null || full.getWidth() <= outW) return full;
        Bitmap scaled = Bitmap.createScaledBitmap(full, outW, Math.max(1, Math.round((float) full.getHeight() * outW / full.getWidth())), true);
        if (scaled != full) full.recycle();
        return scaled;
    }

    // 최신순 metas를 {"date","events":[{id,s,d,n,b,f}],"more"}로 — maxChars를 넘으면 오래된 쪽부터 잘라내고 more=true
    static JSONObject buildRemoteList(String date, List<SentryIndex.EventMeta> metas, int maxChars) {
        try {
            JSONObject head = new JSONObject().put("date", date).put("events", new JSONArray()).put("more", true);
            int used = head.toString().length();
            JSONArray events = new JSONArray();
            boolean more = false;
            for (SentryIndex.EventMeta m : metas) {
                long bytes = 0;
                for (SentryIndex.Segment s : m.segments) bytes += s.bytes;
                JSONObject o = new JSONObject()
                        .put("id", m.id)
                        .put("s", m.startMs)
                        .put("d", Math.round(m.durationS * 10) / 10.0)
                        .put("n", m.segments.size())
                        .put("b", Math.round(bytes / 1048576.0))
                        .put("f", m.favorite ? 1 : 0);
                // ★863 목록에서 바로 보이게(운영자 10-10) — 가장 높은 사람·차량 판정 하나 {종류 1차량·2사람, 점수%}와 조명 의심(l=1). 녹화 하나당 10자 안팎
                float[] best = null;
                for (float[] ob : m.objects) if (ob != null && ob.length >= 2 && (best == null || ob[1] > best[1])) best = ob;
                if (best != null) o.put("o", new JSONArray().put((int) best[0]).put((int) best[1]));
                if (m.light) o.put("l", 1);
                used += o.toString().length() + 1;
                if (used > maxChars) { more = true; break; }
                events.put(o);
            }
            return new JSONObject().put("date", date).put("events", events).put("more", more);
        } catch (JSONException e) {
            return errorJson("목록 JSON 오류");
        }
    }

    private SentryReplay replay;

    // 재생 시험(운영자판 전용) — HTTP /sentry/replay가 쓴다. 초기화 전이면 null
    public synchronized SentryReplay replay() {
        if (replay == null && host != null) {
            replay = new SentryReplay(host, () -> index, () -> storage == null ? null : storage.root(), () -> settings, () -> state == State.RECORDING, () -> eventId, this::log);
        }
        return replay;
    }

    // SentryHttp용 접근자(초기화 전이면 null)
    public SentryIndex index() { return index; }
    public SentryStorage storage() { return storage; }

    // 지금 녹화 중인 이벤트 id(없으면 null) — 삭제 거부 판단용
    public String recordingEventId() {
        synchronized (lock) { return recording ? eventId : null; }
    }

    void log(String msg) {
        SentryHost hs = host;
        if (hs == null) return;
        try { hs.log(msg); } catch (Throwable ignored) {}
    }

    // ───────────── 테스트 가능한 순수 로직 ─────────────

    // 게이트 — 통과면 null, 탈락이면 사유
    static String gate(boolean enabled, int ignition, boolean rtcActive, boolean detectActive, boolean timeAllowed,
                       boolean pausedByLocation, boolean lowVoltage, boolean pauseWhenPhoneNear, boolean phoneNearFresh,
                       String storageYield) {
        return gate(enabled, ignition, false, rtcActive, detectActive, timeAllowed, pausedByLocation, lowVoltage, pauseWhenPhoneNear, phoneNearFresh, storageYield);
    }

    static String gate(boolean enabled, int ignition, boolean camOff, boolean rtcActive, boolean detectActive, boolean timeAllowed,
                       boolean pausedByLocation, boolean lowVoltage, boolean pauseWhenPhoneNear, boolean phoneNearFresh,
                       String storageYield) {
        if (!enabled) return "센트리 꺼짐";
        if (ignition == 1) return "시동 켜짐";
        if (ignition != 0) return "시동 상태 미확정";
        if (camOff) return "카메라 기능 꺼짐(앱 설정)";
        if (rtcActive) return "실시간 보기 중";
        if (detectActive) return "다른 감지 사용 중";
        if (!timeAllowed) return "시간 제한";
        if (pausedByLocation) return "안전 위치";
        if (lowVoltage) return "12V 저전압";
        if (storageYield != null) return "저장소 내림(" + storageYield + ")";
        if (pauseWhenPhoneNear && phoneNearFresh) return "폰이 차 근처";
        return null;
    }

    // 물체 확인 판정 — 측정 불가(null)는 거르지 않고 통과(모델이 없어서 움직임을 다 놓치는 것보다 안전). 켠 종류 중 하나라도 기준을 넘으면 통과
    static boolean objectPass(float[] sc, boolean person, boolean vehicle) {
        if (sc == null || sc.length < 2) return true;
        return (person && sc[0] >= PERSON_MIN) || (vehicle && sc[1] >= VEHICLE_MIN);
    }

    // 세션을 새로 만들어야 하는 설정 변화인가
    static boolean needRestart(SentrySettings a, SentrySettings b) {
        if (!a.storage.equals(b.storage) || !a.resolution.equals(b.resolution) || !a.codec.equals(b.codec)
                || !a.fps.equals(b.fps) || a.bitrateKbps != b.bitrateKbps || a.motionOnly != b.motionOnly
                || a.osdDatetime != b.osdDatetime || a.osdBattery != b.osdBattery || !a.osdPos.equals(b.osdPos)
                || !java.util.Arrays.equals(a.layoutMap, b.layoutMap) || !java.util.Arrays.equals(a.layoutFlip, b.layoutFlip) || !a.cellViews.toString().equals(b.cellViews.toString())) return true;
        if (b.motionOnly) {
            return a.sensitivity != b.sensitivity || a.minDetectS != b.minDetectS || a.roiEnabled != b.roiEnabled
                    || a.shadowFilter != b.shadowFilter || a.lightChangeFilter != b.lightChangeFilter || a.prerollS != b.prerollS
                    || !String.valueOf(a.rois).equals(String.valueOf(b.rois));
        }
        return false;
    }

    // relay_state 업로드용 — 300자 넘으면 중요도 낮은 필드부터 줄임
    static String trimStatus(JSONObject o) {
        try {
            if (o.toString().length() <= 300) return o.toString();
            for (String k : new String[]{"why", "err"}) {
                String v = o.optString(k, "");
                if (v.length() > 30) o.put(k, v.substring(0, 30));
            }
            String[] drop = {"err", "lastEvent", "drop", "fps", "tier", "fallbackWhy", "fallback", "why", "ioDelay"};
            for (String k : drop) {
                if (o.toString().length() <= 300) break;
                o.remove(k);
            }
        } catch (JSONException ignored) {}
        return o.toString();
    }

    // 재기동 복구 지연 — 시동 OFF 뒤 20초 대기가 아직 남았으면 남은 만큼(최소 5초), 기록이 없거나 이미 지났으면 5초, 시각이 거꾸로 가면 20초
    static long recoverDelayMs(long offWallMs, long nowMs) {
        if (offWallMs <= 0) return RECOVER_DELAY_MS;
        long elapsed = nowMs - offWallMs;
        if (elapsed < 0) return ARM_DELAY_MS;
        if (elapsed >= ARM_DELAY_MS) return RECOVER_DELAY_MS;
        return Math.max(RECOVER_DELAY_MS, ARM_DELAY_MS - elapsed);
    }

    // ───────────── 핸들러 스레드: 게이트·상태 전이 ─────────────

    private String gateWhy() {
        if (clearing.get()) return "전체 삭제 중";
        SentryHost hs = host;
        SentrySettings s = settings;
        return gate(s.enabled, hs.ignitionState(), hs.camOff(), hs.rtcActive(), hs.detectActive(),
                s.timeAllows(System.currentTimeMillis()), pausedLoc, hs.lowVoltage(),
                s.pauseWhenPhoneNear, hs.phoneNearFresh(), "internal".equals(s.storage) ? null : storageYield);
    }

    // afterDelay=true면 시동 OFF 20초 대기 규칙을 이미 지난 것으로 본다
    private void evaluate(boolean afterDelay) {
        if (afterDelay) ignOffAtUptime = 0;
        State st = state;
        String g = gateWhy();
        boolean active = st == State.ARMED || st == State.RECORDING;
        String gk = g == null ? "통과" : g;
        if (!gk.equals(lastTraceGate)) { lastTraceGate = gk; trace("게이트 " + gk); }
        if (g != null) {
            mountWaitTries = 0;
            if (active) {
                log("게이트 탈락 — " + g);
                stopSession(gateFailState(), g, true);
            } else {
                setState(gateFailState(), g);
            }
            return;
        }
        if (active) return;
        if (ignOffAtUptime != 0 && SystemClock.uptimeMillis() - ignOffAtUptime < ARM_DELAY_MS) {
            setState(State.PAUSED, "시동 OFF 20초 대기");
            return;
        }
        arm();
    }

    private State gateFailState() {
        return (!settings.enabled || host.ignitionState() == 1 || host.camOff()) ? State.IDLE : State.PAUSED;
    }

    private int armFailures;          // 연속 무장·세션 실패 횟수(씨라7 재시도 간격용)
    private long armBlockedUntil;     // 이 시각(elapsedRealtime)까지 다시 무장하지 않음
    private volatile long firstFrameAt;   // 이번 세션의 첫 프레임 시각(재시도 간격 초기화 판단용)

    // 씨라7 카메라 재시도 간격 — 실패 1회 1분, 이후 2·4·8분, 최대 15분
    static long armBackoffMs(int failures) {
        if (failures <= 0) return 0L;
        return Math.min(15L * 60_000L, 60_000L << Math.min(failures - 1, 4));
    }

    private void noteArmFailure() {
        if (!host.conservativeRetry()) return;
        armFailures++;
        long ms = armBackoffMs(armFailures);
        armBlockedUntil = SystemClock.elapsedRealtime() + ms;
        log("카메라 재시도 간격 늘림 — 연속 실패 " + armFailures + "회, " + (ms / 60000L) + "분 뒤 다시");
    }

    private void arm() {
        final SentryHost hs = host;
        final SentrySettings s = settings;
        if (hs.conservativeRetry() && SystemClock.elapsedRealtime() < armBlockedUntil) {
            setState(State.PAUSED, "카메라 재시도 대기");
            return;
        }
        firstFrameAt = 0L;
        trace("무장 시도 storage=" + s.storage);
        // ★저장소 준비(볼륨 목록 바인더 호출·폴더 쓰기 시험)는 별도 스레드에서 5초까지만 기다린다 — 외장 입출력이 죽어 있으면 여기서 핸들러가 통째로 멈추던 것(FB2106·193407)
        final long t0 = SystemClock.elapsedRealtime();
        Object[] prep = ioCall("저장소 준비", IO_TIMEOUT_MS, () -> {
            SentryStorage ns = new SentryStorage(hs.context(), s);
            boolean mounted = "internal".equals(s.storage) || ns.externalMounted();
            ns.pin();   // 이벤트가 끝날 때까지 루트 고정 — 도중 재해석으로 조각이 갈라지는 것 방지(외장 미마운트면 여기서 내장으로 해석)
            return new Object[]{ns, mounted};
        }, null);
        SentryStorage ns;
        if (prep == null) {
            // 입출력 지연 — 외장은 포기하고 내장 저장소로 무장한다(다음 무장 때 다시 외장을 시도)
            log("저장소 입출력 지연 — 내장 저장소로 무장");
            trace("저장소 준비 지연 → 내장으로 무장");
            try {
                org.json.JSONObject j = s.toJson();
                j.put("storage", "internal");
                ns = new SentryStorage(hs.context(), SentrySettings.fromJson(j));
                ns.pin();
            } catch (Throwable t) {
                err = "저장소 준비 실패 " + t;
                log("저장소 준비 실패 — " + t);
                setState(State.PAUSED, "저장소 준비 실패");
                return;
            }
        } else {
            ns = (SentryStorage) prep[0];
            boolean mounted = (Boolean) prep[1];
            trace("저장소 준비 " + (SystemClock.elapsedRealtime() - t0) + "ms 외장마운트=" + mounted);
            // ★외장을 골랐는데 아직 안 붙었으면 무장 전에 최대 약 12초(3초×4) 기다린다 — 시동 OFF 직후 BYD가 SD를 내렸다가 릴레이가 다시 올리는 2~17초 사이에 무장하면 내장에 고정되던 것
            if (!mounted && mountWaitTries < 4) {
                ns.unpin();
                mountWaitTries++;
                log("저장소 마운트 대기 — 외장이 아직 안 붙음(" + mountWaitTries + "/4)");
                setState(State.PAUSED, "저장소 마운트 대기");
                handler.postDelayed(() -> evaluate(false), 3000L);
                return;
            }
        }
        mountWaitTries = 0;
        try {
            storage = ns;
            index = new SentryIndex(storage);
        } catch (Throwable t) {
            err = "저장소 준비 실패 " + t;
            log("저장소 준비 실패 — " + t);
            setState(State.PAUSED, "저장소 준비 실패");
            return;
        }
        synchronized (lock) { resetDataLocked(); }
        pipeBuilding.set(false);
        motionTrigPending.set(false);
        failPosted.set(false);
        thumbWant.set(null);
        String r = hs.startSentryDetect();
        trace("카메라 세션 시작 결과: " + (r == null ? "null" : r));
        if (r != null && !r.startsWith("감지 시작")) {
            err = r;
            log("센트리 무장 실패 — " + r);
            noteArmFailure();
            storage.unpin();
            setState(State.PAUSED, r);
            return;
        }
        err = "";
        lastFrameElapsed = SystemClock.elapsedRealtime();
        setState(State.ARMED, "카메라 대기");
        log("센트리 무장 — 저장소 " + storage.tier() + " " + storage.root().getAbsolutePath()
                + (s.motionOnly ? " (움직임 감지)" : " (연속 녹화)"));
        if (storage.fallback()) log("저장소 진단 — " + storage.resolveDiag());
    }

    // 녹화 마감 → 파이프라인 정리 → 상태 전환. callHost면 호스트 카메라 세션도 멈춘다.
    private void stopSession(State to, String w, boolean callHost) {
        handler.removeCallbacks(motionTask);
        PreRollEncoder e;
        SentryMotion mo;
        boolean had;
        boolean wasActive = state == State.ARMED || state == State.RECORDING;
        synchronized (lock) {
            had = finishEventLocked();
            e = encoder; mo = motion;
            encoder = null; motion = null;
            frameW = 0; frameH = 0;
            pipeGen++;
            sessionGen++;
            recording = false;
            pendingPreroll = null;
        }
        pipeBuilding.set(false);
        motionTrigPending.set(false);
        thumbWant.set(null);
        setState(to, w);
        if (callHost && wasActive) {
            lastOwnStopUptime = SystemClock.uptimeMillis();
            try { host.stopSentryDetect(); } catch (Throwable t) { log("카메라 세션 중지 실패 — " + t); }
        }
        closePipe(e, mo);
        try { host.objectRelease(); } catch (Throwable t) { log("물체 인식 반납 오류 — " + t); }
        if (had) afterEvent();
        SentryStorage stg = storage;
        if (stg != null) stg.unpin();
    }

    private void closePipe(PreRollEncoder e, SentryMotion mo) {
        if (e != null) {
            try { e.stop(); } catch (Throwable t) { log("인코더 정지 오류 — " + t); }
        }
        if (mo != null) {
            try { mo.close(); } catch (Throwable t) { log("감지기 정리 오류 — " + t); }
        }
    }

    private void failSession(String msg) {
        State st = state;
        if (st != State.ARMED && st != State.RECORDING) return;
        err = msg;
        log("센트리 오류 — " + msg);
        noteArmFailure();
        stopSession(State.PAUSED, msg, true);
    }

    private void postFail(String msg) {
        if (failPosted.compareAndSet(false, true)) post(() -> failSession(msg));
    }

    private void setState(State to, String w) {
        State from = state;
        boolean changed = from != to || !w.equals(why);
        state = to;
        why = w;
        if (changed) { log("센트리 상태 " + from + "→" + to + " — " + w); trace(from.name() + "→" + to.name() + " " + w); }
        if (to == State.ARMED || to == State.RECORDING) setActive(true);
        else if (to == State.IDLE) setActive(false);
        if (changed) publishStatus();
    }

    private void setActive(boolean v) {
        try { host.prefs().edit().putBoolean(PREF_ACTIVE, v).apply(); } catch (Throwable ignored) {}
    }

    private void setPausedLoc(boolean v, String reason) {
        pausedLoc = v;
        try { host.prefs().edit().putBoolean(PREF_PAUSED_LOC, v).apply(); } catch (Throwable ignored) {}
        log("안전 위치 일시중지 " + (v ? "켜짐" : "꺼짐") + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"));
        post(this::publishStatus);   // 상태(state·why)가 안 바뀌어도 폰이 바로 볼 수 있게 올린다
    }

    // ───────────── 핸들러 스레드: 설정·주기 작업 ─────────────

    private void reconfigureTask() {
        SentrySettings old = settings;
        SentrySettings ns = SentrySettings.load(host.prefs());
        settings = ns;
        State st = state;
        boolean active = st == State.ARMED || st == State.RECORDING;
        if (old != null && old.objectsOn() && !ns.objectsOn()) {   // 객체 인식을 껐으면 모델 메모리를 돌려준다
            try { host.objectRelease(); } catch (Throwable t) { log("물체 인식 반납 오류 — " + t); }
        }
        if (!ns.enabled) {
            handler.removeCallbacks(armTask);
            handler.removeCallbacks(restartTask);
            log("센트리 비활성화");
            if (active) stopSession(State.IDLE, "센트리 꺼짐", true);
            else setState(State.IDLE, "센트리 꺼짐");
            return;
        }
        if (active && needRestart(old, ns)) {
            log("설정 변경으로 센트리 세션 재시작");
            stopSession(State.PAUSED, "설정 변경", true);
            handler.removeCallbacks(restartTask);
            handler.postDelayed(restartTask, RESTART_GAP_MS);
            return;
        }
        if (!active || old.limitMb != ns.limitMb) {
            SentryStorage prev = storage;
            storage = new SentryStorage(host.context(), ns);
            if (active) storage.pinTo(prev);   // 녹화 중이면 지금 쓰는 루트를 그대로 이어받는다
            index = new SentryIndex(storage);
        }
        evaluate(false);
    }

    private void tickTask() {
        lastTickRunElapsed = SystemClock.elapsedRealtime();
        if (stallReported) {
            stallReported = false;
            try { host.putStatus("sentry_stall", ""); } catch (Throwable ignored) {}
        }
        evaluate(false);
        if (!settings.enabled && state == State.IDLE && state == publishedState && why.equals(publishedWhy)) return;   // ★꺼짐+IDLE이고 올린 상태가 현재와 같으면 디스크 스캔·업로드·고아 정리 생략(꺼진 차는 매분 저장소를 안 만진다)
        long now = SystemClock.elapsedRealtime();
        if (now - lastOrphanElapsed >= ORPHAN_MS) {
            lastOrphanElapsed = now;
            final SentryStorage stO = storage;
            ioPost(() -> {
                try {
                    int n = stO.cleanOrphans(ORPHAN_MS);
                    if (n > 0) log("고아 조각 정리 " + n + "개");
                } catch (Throwable t) { log("고아 조각 정리 실패 — " + t); }
            });
        }
        State st = state;
        if ((st == State.ARMED || st == State.RECORDING) && now - lastFrameElapsed > STALL_MS) {
            err = "프레임 없음 " + ((now - lastFrameElapsed) / 1000) + "초";
            log("센트리 " + err + " — 세션 재시작");
            stopSession(State.PAUSED, "프레임 끊김", true);
            handler.removeCallbacks(restartTask);
            handler.postDelayed(restartTask, RESTART_GAP_MS);
        }
        if (tickIoPending.compareAndSet(false, true)) ioPost(() -> { tickIoPending.set(false); refreshStats(); publishStatusNow(); });
    }

    private void refreshStats() {
        try {
            usedMbCache = storage.usedAllMb();   // 폴백 때 다른 단계에 남은 영상까지
            eventsCache = index.allOldestFirst().size();
        } catch (Throwable t) { log("용량 집계 실패 — " + t); }
    }

    private void afterEvent() {
        final SentryStorage stA = storage;
        final SentryIndex idxA = index;
        ioPost(() -> {
            try {
                int n = stA.prune(idxA);
                if (n > 0) log("용량 한도 초과 — 오래된 이벤트 " + n + "개 삭제");
            } catch (Throwable t) { log("용량 정리 실패 — " + t); }
            refreshStats();
            publishStatusNow();
        });
    }

    // 상태 업로드는 저장소 정보(볼륨 조회·폴더 시험)를 읽으므로 입출력 스레드에서 돌린다(여러 번 부르면 한 번으로 합침)
    private void publishStatus() {
        if (publishPending.compareAndSet(false, true)) ioPost(() -> { publishPending.set(false); publishStatusNow(); });
    }

    private void publishStatusNow() {
        SentryHost hs = host;
        SentryStorage st = storage;
        if (hs == null || st == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("state", state.name());
            o.put("why", why);
            o.put("clear", true);
            o.put("pausedByLocation", pausedLoc);
            o.put("storage", st.selectedKind());
            o.put("tier", st.tier());
            o.put("usedMb", usedMbCache);
            o.put("limitMb", st.limitMb());
            o.put("events", eventsCache);
            o.put("lastEvent", lastEvent);
            o.put("fallback", st.fallback());
            String fw2 = st.fallbackWhy();
            if (fw2 != null) o.put("fallbackWhy", fw2);
            o.put("err", err);
            if (ioDelayed) o.put("ioDelay", true);   // 외장 저장소 입출력이 막혀 내장으로 무장했거나 입출력 작업이 밀리는 중
            PreRollEncoder e = encoder;
            o.put("fps", e == null ? 0 : e.encodedFpsLast10s());
            o.put("drop", e == null ? 0 : e.droppedFrames());
            statusCache = o.toString();
            hs.putStatus("sentry", trimStatus(o));
            publishedState = state;
            publishedWhy = why;
        } catch (Throwable t) { log("상태 업로드 실패 — " + t); }
        refreshFullStatus();
    }

    // 워커 result 한도(8000자) 안에 들도록 7000자를 넘으면 settings를 빼고 만든다. 핸들러 스레드에서만 부른다(저장소 볼륨 probe 포함)
    private void refreshFullStatus() {
        try {
            JSONObject o = statusJson();
            String full = o.toString();
            if (full.length() > 7000) {
                o.remove("settings");
                full = o.toString();
            }
            fullStatusCache = full;
        } catch (Throwable t) { log("전체 상태 캐시 갱신 실패 — " + t); }
    }

    // ───────────── 핸들러 스레드: 파이프라인·이벤트 ─────────────

    // 첫 프레임(또는 해상도 변경)에서 w/h 확정 후 인코더(+감지) 생성. 연속 모드면 곧바로 이벤트 시작.
    private void buildPipeline(int w, int fh) {
        PreRollEncoder oldE;
        SentryMotion oldMo;
        try {
            State st = state;
            if (st != State.ARMED && st != State.RECORDING) return;
            SentrySettings s = settings;
            int gen;
            synchronized (lock) {
                finishEventLocked();
                oldE = encoder; oldMo = motion;
                encoder = null; motion = null;
                recording = false;
                pendingPreroll = null;
                gen = ++pipeGen;
            }
            closePipe(oldE, oldMo);
            if (s.targetWidth() != 0 && s.targetWidth() != w) {
                log("원본 " + w + "x" + fh + " 그대로 인코딩(1단계는 스케일 안 함, 목표 폭 " + s.targetWidth() + ")");
            }
            int fps = s.targetFps();
            int kbps = s.effectiveBitrateKbps();
            PreRollEncoder e = new PreRollEncoder(w, fh, fps, kbps, s.codec, s.prerollS, new EncListener(gen));
            e.setLayout(new SentryLayout(s.layoutMap, s.layoutFlip));   // 기본(그대로)이면 setLayout이 무시해 비용 없음
            if (s.cellViews.length() > 0) {   // ★칸별 어안 보정·화면 조정(10-07) — 값이 있는 칸만 펴고 나머지는 원본, 비면 비용 없음
                long dt0 = SystemClock.elapsedRealtime();
                SentryLayout dlo = new SentryLayout(s.layoutMap, s.layoutFlip);
                SentryDewarp dw = SentryDewarp.create(w, fh, dlo.isIdentity() ? null : dlo, s.cellViews);
                if (dw == null) log("칸별 어안 보정 못 만듦 — 원본 칸 그대로 녹화(" + w + "x" + fh + ")");
                else {
                    e.setDewarp(dw);
                    log("칸별 어안 보정 켬 — 칸 " + s.cellViews.names() + " 표 생성 " + (SystemClock.elapsedRealtime() - dt0) + "ms");
                }
            }
            if (s.osdDatetime || s.osdBattery) {
                final SentryHost osdHost = host;
                final SentryOsd osd = new SentryOsd(s.osdDatetime, s.osdBattery, s.osdPos, osdHost::batteryPct);
                final AtomicBoolean osdFailLogged = new AtomicBoolean();
                e.setOverlay((buf, ow, oh, wall) -> {
                    try {
                        osd.apply(buf, ow, oh, wall);
                    } catch (RuntimeException ex) {   // 인코더가 덧그림을 끄므로 한 번만 남긴다
                        if (osdFailLogged.compareAndSet(false, true)) log("영상 위 시각·배터리 표시 실패, 끔 — " + ex);
                        throw ex;
                    }
                });
            }
            if (!e.start()) {
                failSession("인코더 시작 실패 " + w + "x" + fh);
                return;
            }
            SentryMotion mo = s.motionOnly ? new SentryMotion(w, fh, s) : null;
            if (mo != null) mo.setLogger(this::log);
            synchronized (lock) {
                encoder = e; motion = mo;
                frameW = w; frameH = fh;
                pipeMotionOnly = s.motionOnly;
            }
            log("센트리 파이프라인 시작 " + w + "x" + fh + " " + fps + "fps " + kbps + "kbps " + s.codec);
            if (!s.motionOnly) startContinuousEvent(e);
            else setState(State.ARMED, "움직임 대기");
        } catch (Throwable t) {
            failSession("파이프라인 생성 실패 " + t);
        } finally {
            pipeBuilding.set(false);
        }
    }

    private void startContinuousEvent(PreRollEncoder e) {
        synchronized (lock) { startEventLocked(false); }
        e.setLive(true);
        setState(State.RECORDING, "연속 녹화");
    }

    // 이벤트 알림을 보낸다(이미 보냈으면 무시). 조명 의심으로 막았다가 나중에 사람·차량이 판정되면 그때 보낸다
    private void notifyNow(String id) {
        synchronized (notifyLock) {
            if (id == null || !id.equals(notifyId) || notifyState == 1) return;
            notifyState = 1;
        }
        if (settings.notify) host.postCarEvent("sentry:" + id);
    }

    // 조명 의심 — 알림을 보내지 않는다(보류 중일 때만)
    private boolean suppressNotify(String id) {
        synchronized (notifyLock) {
            if (!id.equals(notifyId) || notifyState != 0) return false;
            notifyState = 2;
            return true;
        }
    }

    private void captureBase(byte[] nv21, long nowMs) {
        synchronized (baseLock) {
            int i = baseIdx;
            byte[] b = baseBuf[i];
            if (b == null || b.length != nv21.length) { b = new byte[nv21.length]; baseBuf[i] = b; }
            System.arraycopy(nv21, 0, b, 0, nv21.length);
            baseTs[i] = nowMs;
            baseIdx = (i + 1) % baseBuf.length;
            lastBaseCapture = nowMs;
        }
    }

    // 움직임보다 앞선(4초 이상 전) 기준 화면 중 가장 가까운 것의 복사본. 없으면 null
    private volatile long lastBaseAgeMs;   // 방금 고른 기준 화면의 나이(판정 로그용)

    private byte[] takeBase(long nowMs, int len) {
        synchronized (baseLock) {
            int best = -1;
            long bestAge = Long.MAX_VALUE;
            for (int i = 0; i < baseBuf.length; i++) {
                if (baseBuf[i] == null || baseBuf[i].length != len || baseTs[i] == 0) continue;
                long age = nowMs - baseTs[i];
                if (age >= BASE_MIN_AGE_MS && age <= BASE_MAX_AGE_MS && age < bestAge) { bestAge = age; best = i; }
            }
            lastBaseAgeMs = best < 0 ? -1L : bestAge;
            return best < 0 ? null : baseBuf[best].clone();
        }
    }

    // 녹화와 같은 화면(칸 배치·뒤집기·어안 보정)으로 바꾼 프레임 — 객체 인식은 편 화면이 점수가 높다(10-07 시험)
    private byte[] displayFrame(byte[] nv21, int w, int fh) {
        PreRollEncoder e = encoder;
        SentryDewarp dw = e == null ? null : e.dewarp();
        if (dw != null) {
            byte[] t = new byte[nv21.length];
            if (dw.toNv21(nv21, t, w, fh)) return t;
        }
        SentrySettings s = settings;
        if (s != null) {
            SentryLayout lo = new SentryLayout(s.layoutMap, s.layoutFlip);
            if (!lo.isIdentity()) {
                byte[] t = new byte[nv21.length];
                if (lo.toNv21(nv21, t, w, fh)) return t;
            }
        }
        return nv21;
    }

    // 별도 스레드. 이 이벤트의 움직임 칸에서 "기준 화면에 없던" 사람·차량을 찾아 저장한다. 못 찾으면 두 번까지 다시(1.5초·2.5초 뒤).
    private void classifyEvent(String id, byte[] cur, int w, int fh, int attempt) {
        try {
            synchronized (lock) { if (!id.equals(eventId) || !recording) return; }
            if (!settings.objectsOn()) { notifyNow(id); return; }   // 판정 도중에 사용자가 객체 인식을 껐으면 바로 알림
            long t0 = SystemClock.elapsedRealtime();
            byte[] base = takeBase(t0, cur.length);
            if (base == null) {
                if (!noBaseLogged) { noBaseLogged = true; log("물체 판정 건너뜀 — 움직임 전 기준 화면이 아직 없음(무장 직후)"); }
                notifyNow(id);   // 판정할 수 없으니 기다리지 않고 알림
                return;
            }
            noBaseLogged = false;
            SentryMotion mo = motion;
            java.util.TreeSet<Integer> cells = new java.util.TreeSet<>();
            if (mo != null) for (long[] h : mo.recentHits(t0 - 12_000L)) cells.add(h[1] < 0 ? -1 : (int) h[1]);
            if (cells.isEmpty() || cells.contains(-1)) { cells.clear(); for (int d = 0; d < 4; d++) cells.add(d); }
            byte[] curD = displayFrame(cur, w, fh), baseD = displayFrame(base, w, fh);
            int cw = w / 2, ch = fh / 2;
            List<SentryObjects.Found> all = new ArrayList<>();
            final long baseAge = lastBaseAgeMs;
            StringBuilder why = new StringBuilder();   // 못 찾았을 때 칸별 후보와 제외 사유(원인 확정용 로그)
            java.util.TreeSet<Integer> lightCells = new java.util.TreeSet<>();   // 기준 화면 대비 밝기가 계단처럼 변한 칸
            java.util.TreeSet<Integer> headlightCells = new java.util.TreeSet<>();   // 밝기 계단이지만 새 헤드라이트가 보이는 칸(조명 의심 아님 — 먼 차)
            for (int d : cells) {
                int rx = (d % 2) * cw, ry = (d / 2) * ch;
                float[][] dc = host.objectDetect(curD, w, fh, rx, ry, cw, ch, 0.30f);
                float[][] db = host.objectDetect(baseD, w, fh, rx, ry, cw, ch, 0.30f);
                if (dc == null || db == null) { log("물체 판정 불가 — 인식 모델이 준비되지 않음"); notifyNow(id); return; }
                all.addAll(SentryObjects.newOnly(dc, db, d));
                why.append(" | 칸").append(d).append(" 현재 ").append(SentryObjects.describe(dc, db, 4)).append(" / 기준 ").append(db.length).append("개");
                if (settings.lightJudgeOn() && SentryObjects.lightStep(SentryObjects.cellMeanY(curD, w, rx, ry, cw, ch), SentryObjects.cellMeanY(baseD, w, rx, ry, cw, ch))) {
                    float[] sat = SentryObjects.newSaturated(curD, baseD, w, rx, ry, cw, ch);
                    if (SentryObjects.headlightLike(sat)) {
                        headlightCells.add(d);
                        why.append(String.format(java.util.Locale.US, " 헤드라이트 의심(새 밝은 점 %.2f%%·세로 %.2f)", sat[0] * 100f, sat[1]));
                    } else {
                        lightCells.add(d);
                        if (sat[0] > 0f) why.append(String.format(java.util.Locale.US, " 새 밝은 점 %.2f%%·세로 %.2f(헤드라이트 기준 미달)", sat[0] * 100f, sat[1]));
                    }
                }
            }
            long ms = SystemClock.elapsedRealtime() - t0;
            all = SentryObjects.filterKinds(all, settings.personOn(), settings.vehicleOn());   // 사용자가 끈 종류는 판정 결과에서 뺀다
            SentryObjects.Found f = SentryObjects.pick(all);
            if (f == null) {
                boolean light = !lightCells.isEmpty();
                log("물체 판정 — 새로 나타난 사람·차량 없음(칸 " + cells + ", " + ms + "ms, 시도 " + (attempt + 1) + (light ? ", 밝기 변화 칸 " + lightCells : "") + (headlightCells.isEmpty() ? "" : ", 헤드라이트 칸 " + headlightCells) + ")"
                        + " 기준나이 " + String.format(java.util.Locale.US, "%.1f", baseAge / 1000f) + "초" + why);
                if (attempt < 2) { classifyId = id; classifyAttempt = attempt + 1; classifyDue = SystemClock.elapsedRealtime() + (attempt == 0 ? 1500L : 2500L); }
                if (!light) notifyNow(id);   // 일반 움직임(조명 아님)은 첫 판정 뒤 바로 알림
                else if (attempt >= 1) {     // 두 번 봐도 새 사람·차량이 없고 밝기가 계단처럼 바뀌었으면 조명 번쩍임 의심
                    synchronized (lock) { if (id.equals(eventId)) { eventLight = true; for (int lc : lightCells) marks.setCellKind(lc, 3); } }
                    if (suppressNotify(id)) log("조명 번쩍임 의심 — 알림 안 보냄(" + id + ", 칸 " + lightCells + ")");
                }
                return;
            }
            boolean first;
            synchronized (lock) {
                if (!id.equals(eventId)) return;
                first = eventObjects.isEmpty();
                eventLight = false;   // 사람·차량이 판정됐으면 조명 의심 아님
                eventObjects.add(new float[]{f.kind, f.pct(), f.cell, f.x0, f.y0, f.x1, f.y1});
                marks.setCellKind(f.cell, f.kind);
            }
            if (first) {
                try {
                    byte[] jpg = SentryIndex.alertJpeg(curD, w, fh, (f.cell % 2) * cw, (f.cell / 2) * ch, cw, ch, new float[]{f.x0, f.y0, f.x1, f.y1}, f.label(), 640);
                    File af = index.alertFile(id);
                    File dir = af.getParentFile();
                    if (dir != null) dir.mkdirs();
                    try (FileOutputStream fo = new FileOutputStream(af)) { fo.write(jpg); }
                } catch (Throwable t) { log("알림 사진 저장 실패 " + id + " — " + t); }
            }
            if (first && settings.notify) {   // 새 앱이 알림 문구를 "사람 감지 — 좌측"으로 바꾸는 짝 이벤트. 옛 앱은 모르는 이벤트라 무시하고 아래 기존 알림만 보인다
                String oe = SentryObjects.objectEvent(id, f.kind, settings.layoutMap, f.cell);
                host.postCarEvent(oe);
                log("알림 문구 이벤트 " + oe);
            }
            notifyNow(id);   // 사람·차량이 판정되면 (보류했던 알림도) 보낸다
            log("물체 판정 — " + f.label() + " 영상 " + new String[]{"좌상", "우상", "좌하", "우하"}[f.cell] + "칸 (새 물체 " + all.size() + "개, " + ms + "ms)");
        } catch (Throwable t) {
            log("물체 판정 오류 " + id + " — " + t);
        }
    }

    private void startMotionEvent(byte[] frame, int w, int fh) {
        try {
            PreRollEncoder e = encoder;
            if (state != State.ARMED || e == null) return;
            final boolean fPerson = settings.filterPerson && settings.personOn(), fVehicle = settings.filterVehicle && settings.vehicleOn();   // 끈 종류는 거르기에서도 뺀다
            if (fPerson || fVehicle) {
                byte[] det = frame;
                SentryDewarp ddw = e.dewarp();   // 칸별 보정이 켜져 있으면 편 화면으로 인식(어안 그대로보다 점수가 높음, 10-07 시험)
                if (ddw != null) {
                    byte[] t = new byte[frame.length];
                    if (ddw.toNv21(frame, t, w, fh)) det = t;
                }
                float[] sc = host.objectScores(det, w, fh);
                boolean pass = objectPass(sc, fPerson, fVehicle);
                String detail = sc == null ? "측정 불가(통과)" : String.format(java.util.Locale.US, "사람 %.2f 차량 %.2f (%dms)", sc[0], sc[1], host.objectLastMs());
                long nowO = SystemClock.elapsedRealtime();
                if (!pass) {
                    if (objectFirstFail == 0) objectFirstFail = nowO;
                    boolean giveUp = nowO - objectFirstFail >= OBJECT_WINDOW_MS;
                    objectRejectUntil = nowO + (giveUp ? OBJECT_REJECT_MS : OBJECT_RETRY_MS);
                    if (giveUp) objectFirstFail = 0;
                    log("물체 점수 " + detail + " → 해당 물체 아님" + (giveUp ? ", 포기" : ", 다시 확인"));
                    return;
                }
                objectFirstFail = 0;
                log("물체 점수 " + detail + " → 이벤트 시작");
            }
            final SentryStorage stM = storage;
            String prev = ioCall("저장소 복귀", 3000L, stM::repinIfFallback, null);   // ★움직임 모드도 새 이벤트 전에 외장 복귀(10-06: 무장 2초 뒤 SD가 붙어 그 세션 내내 내장에 쓰던 결함)
            if (prev != null) log("저장소 복귀 " + prev + " → " + stM.tier() + " " + stM.root().getAbsolutePath());
            String id;
            synchronized (lock) {
                startEventLocked(true);
                e.setLive(true);
                List<EncodedSample> pre = e.drainPreroll();
                lastPrePtsUs = pre.isEmpty() ? Long.MIN_VALUE : pre.get(pre.size() - 1).ptsUs;
                log("사전 녹화 실제 " + (pre.isEmpty() ? "0" : String.format(java.util.Locale.US, "%.1f", (pre.get(pre.size() - 1).ptsUs - pre.get(0).ptsUs) / 1e6))
                        + "초(" + pre.size() + "개, 설정 " + settings.prerollS + "초)");   // 10-06 실차: 설정 5초인데 영상엔 약 3초만 들어감 — 원인 확인용
                pendingPreroll = pre;
                openWriterLocked();
                id = eventId;
            }
            lastMotionElapsed = SystemClock.elapsedRealtime();
            setState(State.RECORDING, "움직임 감지");
            saveThumb(id, frame, w, fh);
            synchronized (notifyLock) { notifyId = id; notifyState = 0; }
            if (!settings.objectsOn()) {
                notifyNow(id);   // 객체 인식을 껐으면 예전처럼 움직임만으로 바로 알림(판정을 기다리지 않음)
            } else {
                handler.postDelayed(() -> notifyNow(id), NOTIFY_MAX_DELAY_MS);   // 판정이 안 와도 이 시간엔 보낸다
                final byte[] clsFrame = frame;
                classifyExec.execute(() -> classifyEvent(id, clsFrame, w, fh, 0));   // 사람·차량 판정은 녹화·알림을 막지 않게 따로
            }
            handler.removeCallbacks(motionTask);
            handler.postDelayed(motionTask, 1000L);
        } catch (Throwable t) {
            failSession("움직임 이벤트 시작 실패 " + t);
        } finally {
            motionTrigPending.set(false);
        }
    }

    // 감지 모드: 움직임이 끊긴 지 postS초가 지나면 마감하고 ARMED로
    private void motionTick() {
        if (state != State.RECORDING || !pipeMotionOnly) return;
        long idleMs = SystemClock.elapsedRealtime() - lastMotionElapsed;
        if (idleMs < settings.postS * 1000L) {
            handler.postDelayed(motionTask, 1000L);
            return;
        }
        PreRollEncoder e = encoder;
        boolean had;
        synchronized (lock) { had = finishEventLocked(); }
        if (e != null) e.setLive(false);
        setState(State.ARMED, "움직임 대기");
        if (had) afterEvent();
    }

    private void saveThumb(String id, byte[] nv21, int w, int fh) {
        try {
            byte[] src = nv21;   // 썸네일도 녹화와 같은 칸 배치·뒤집기로 만든다(목록에서 보는 모양 = 영상 모양)
            SentrySettings ts = settings;
            PreRollEncoder te = encoder;
            SentryDewarp tdw = te == null ? null : te.dewarp();
            boolean thumbDone = false;
            if (tdw != null) {   // 어안 보정이 켜져 있으면 썸네일도 같은 보정으로
                byte[] t = new byte[nv21.length];
                if (tdw.toNv21(nv21, t, w, fh)) { src = t; thumbDone = true; }
            }
            if (!thumbDone && ts != null) {
                SentryLayout lo = new SentryLayout(ts.layoutMap, ts.layoutFlip);
                if (!lo.isIdentity()) {
                    byte[] t = new byte[nv21.length];
                    if (lo.toNv21(nv21, t, w, fh)) src = t;
                }
            }
            byte[] jpg = SentryIndex.thumbnailJpeg(src, w, fh, 640);
            if (jpg == null || jpg.length == 0) { log("썸네일 생성 실패 " + id); return; }
            synchronized (lock) {
                SentryIndex ix = index;
                if (!id.equals(eventId) && ix.read(id) == null) return;
                File f = ix.thumbFile(id);
                File dir = f.getParentFile();
                if (dir != null) dir.mkdirs();
                File tmp = new File(dir, "thumb.jpg.tmp");
                try (FileOutputStream os = new FileOutputStream(tmp)) { os.write(jpg); }
                if (!tmp.renameTo(f)) { log("썸네일 이름 변경 실패 " + id); tmp.delete(); return; }
            }
            log("썸네일 저장 " + id + " " + jpg.length + "B");
        } catch (Throwable t) {
            log("썸네일 저장 오류 " + id + " — " + t);
        }
    }

    // ───────────── 인코더 스레드: 샘플 기록 ─────────────

    private final class EncListener implements PreRollEncoder.Listener {
        private final int gen;
        EncListener(int gen) { this.gen = gen; }
        @Override public void onSample(EncodedSample s) { handleSample(gen, s); }
        @Override public void onError(String msg) {
            boolean cur;
            synchronized (lock) { cur = gen == pipeGen; }
            if (cur) postFail("인코더 오류 " + msg);
        }
    }

    private void handleSample(int gen, EncodedSample s) {
        String failMsg = null;
        boolean rotated = false;
        synchronized (lock) {
            if (gen != pipeGen || !recording) return;
            if (s.ptsUs <= lastPrePtsUs) return;
            try {
                if (writer == null && !openWriterLocked()) return;
                if (s.keyFrame) {
                    long now = SystemClock.elapsedRealtime();
                    if (!pipeMotionOnly && now - eventStartElapsed >= EVENT_MS) {
                        finishEventLocked();
                        String prev = storage.repinIfFallback();   // 옛 이벤트 마감 뒤·새 이벤트 전 — 폴백 중이면 외장 복귀
                        if (prev != null) log("저장소 복귀 " + prev + " → " + storage.tier() + " " + storage.root().getAbsolutePath());
                        else if (storage.fallback()) log("저장소 진단(폴백 유지) — " + storage.resolveDiag());
                        startEventLocked(false);
                        rotated = true;
                        if (!openWriterLocked()) return;
                    } else if (now - segStartElapsed >= SEG_MS || (cutRequested && now - segStartElapsed >= CUT_MIN_MS)) {
                        rollSegmentLocked();
                    }
                }
                writer.write(s);
            } catch (Throwable t) {
                failMsg = "조각 기록 실패 " + t;
            }
        }
        if (rotated) post(this::afterEvent);
        if (failMsg != null) postFail(failMsg);
    }

    // ───────────── lock을 쥔 채 부르는 내부 ─────────────

    private void resetDataLocked() {
        pipeGen++;
        sessionGen++;
        recording = false;
        eventId = null;
        writer = null;
        segments.clear();
        pendingPreroll = null;
        encoder = null; motion = null;
        frameW = 0; frameH = 0;
    }

    private void startEventLocked(boolean fromMotion) {
        long now = System.currentTimeMillis();
        long startMs = fromMotion ? now - settings.prerollS * 1000L : now;
        eventId = SentryIndex.newEventId(startMs);
        eventStartMs = startMs;
        eventStartElapsed = SystemClock.elapsedRealtime();
        marks.reset(eventStartElapsed, fromMotion ? settings.prerollS * 1000L : 0L);   // 영상 0초 = 사전 녹화 시작
        eventObjects.clear();
        eventLight = false;
        classifyDue = 0;
        SentryMotion moForMarks = motion;
        if (fromMotion && moForMarks != null) {   // 감지 확정 직전(사전 녹화 구간)에 이미 시작된 움직임도 표시
            for (long[] h : moForMarks.recentHits(eventStartElapsed - settings.prerollS * 1000L)) marks.add(h[0], (int) h[1], (int) h[2], 0);
        }
        segStartElapsed = eventStartElapsed;
        segN = 0;
        segments.clear();
        writer = null;
        lastPrePtsUs = Long.MIN_VALUE;
        recording = true;
        lastEvent = eventId;
        if (!fromMotion) thumbWant.set(eventId);
        log("이벤트 시작 " + eventId + (fromMotion ? " (움직임)" : " (연속)"));
    }

    // 인코더 출력 포맷이 아직 없으면 false — 다음 샘플에서 다시 시도
    private boolean openWriterLocked() throws Exception {
        PreRollEncoder e = encoder;
        if (e == null || eventId == null) return false;
        MediaFormat f = e.outputFormat();
        if (f == null) return false;
        writer = new SegmentWriter(index.eventDir(eventId), f);
        segStartElapsed = SystemClock.elapsedRealtime();
        writer.startSegment(segN);
        if (pendingPreroll != null) {
            for (EncodedSample p : pendingPreroll) writer.write(p);
            pendingPreroll = null;
        }
        return true;
    }

    // 새 조각을 먼저 열고 옛 조각 마감(fsync·rename)은 단일 스레드로 넘긴다 — lock 안에서 디스크를 기다리지 않는다
    private void rollSegmentLocked() throws Exception {
        PreRollEncoder e = encoder;
        MediaFormat f = e == null ? null : e.outputFormat();
        if (f == null || eventId == null) throw new IllegalStateException("인코더 포맷 없음");
        SegmentWriter old = writer;
        int oldN = segN;
        cutRequested = false;
        SegmentWriter nw = new SegmentWriter(index.eventDir(eventId), f);
        nw.startSegment(oldN + 1);
        writer = nw;
        segN = oldN + 1;
        segStartElapsed = SystemClock.elapsedRealtime();
        submitSegmentFinish(old, oldN);
    }

    private void submitSegmentFinish(SegmentWriter w, int n) {
        if (w == null || !w.isOpen()) return;
        final long durMs = w.segmentDurationMs();
        final String id = eventId;
        final long startMs = eventStartMs;
        synchronized (finMon) { finPending++; }
        segExec.execute(() -> {
            try {
                finishSegmentIO(w, n, durMs, id, startMs);
            } catch (Throwable t) {
                log("조각 마감 오류 " + id + " seg-" + n + " — " + t);
            } finally {
                synchronized (finMon) { finPending--; finMon.notifyAll(); }
            }
        });
    }

    // 비동기 마감이 모두 끝나기를 기다린다(최대 10초). 마감 작업은 lock을 쓰지 않으므로 lock을 쥔 채 기다려도 교착하지 않는다.
    private void awaitSegFinish() {
        long end = SystemClock.uptimeMillis() + 10_000L;
        boolean timeout = false;
        synchronized (finMon) {
            while (finPending > 0) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) { timeout = true; break; }
                try {
                    finMon.wait(left);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (timeout) log("조각 마감 대기 시간 초과");
    }

    // 현재 조각 마감 + meta 갱신(lock 안, 동기)
    private void closeSegmentLocked() {
        if (writer == null || !writer.isOpen()) return;
        finishSegmentIO(writer, segN, writer.segmentDurationMs(), eventId, eventStartMs);
    }

    // 조각 하나의 fsync·rename 뒤 목록·meta 반영. lock 없이 불러도 안전(segments는 CopyOnWrite, meta는 metaLock).
    private void finishSegmentIO(SegmentWriter w, int n, long durMs, String id, long startMs) {
        File f = w.finishSegment();
        if (f == null) {
            log("조각 마감 실패 " + id + " seg-" + n + " — " + w.lastError());
            return;
        }
        if (!id.equals(eventId)) {
            log("끝난 이벤트의 조각이라 목록에 넣지 않음 " + id + " seg-" + n);
            return;
        }
        SentryIndex.Segment sg = new SentryIndex.Segment();
        sg.n = n;
        sg.bytes = f.length();
        sg.durationS = durMs / 1000.0;
        segments.add(sg);
        log("조각 저장 " + f.getName() + " " + sg.bytes + "B " + String.format(java.util.Locale.US, "%.1f", sg.durationS) + "초");
        writeMeta(id, startMs);
    }

    private void writeMeta(String id, long startMs) {
        try {
            SentryIndex.EventMeta m = new SentryIndex.EventMeta();
            m.id = id;
            m.startMs = startMs;
            m.marks = marks.snapshot();
            m.light = eventLight && eventObjects.isEmpty();
            synchronized (lock) { m.objects = new ArrayList<>(eventObjects); }
            m.segments = new ArrayList<>(segments);
            SentryIndex.sortSegmentsByN(m.segments);
            double total = 0;
            for (SentryIndex.Segment sg : m.segments) total += sg.durationS;
            m.durationS = total;
            synchronized (SentryIndex.META_LOCK) {
                SentryIndex.EventMeta old = index.read(id);
                if (old != null) m.favorite = old.favorite;
                index.write(m);
            }
        } catch (Throwable t) {
            log("meta 저장 실패 " + id + " — " + t);
        }
    }

    // 이벤트 마감. 조각이 하나라도 남았으면 true(후속 용량 정리 필요)
    private boolean finishEventLocked() {
        if (eventId == null) return false;
        String id = eventId;
        awaitSegFinish();
        try {
            closeSegmentLocked();
        } catch (Throwable t) {
            log("조각 마감 오류 " + id + " — " + t);
        }
        writer = null;
        recording = false;
        boolean had = !segments.isEmpty();
        if (had) {
            writeMeta(id, eventStartMs);
            double total = 0;
            for (SentryIndex.Segment sg : segments) total += sg.durationS;
            log("이벤트 마감 " + id + " 조각 " + segments.size() + "개 " + String.format(java.util.Locale.US, "%.1f", total) + "초");
        } else {
            try { index.delete(id); } catch (Throwable t) { log("빈 이벤트 삭제 실패 " + id + " — " + t); }
            log("이벤트 비어 삭제 " + id);
        }
        segments.clear();
        eventId = null;
        return had;
    }

    // ───────────── 스레드 보조 ─────────────

    private void ioPost(Runnable r) {
        ioQueue.add(r);
        kickIo();
    }

    private void kickIo() {
        if (!ioBusy.compareAndSet(false, true)) return;
        ioExec.execute(() -> {
            try {
                Runnable r;
                while ((r = ioQueue.poll()) != null) {
                    ioRunStartElapsed = SystemClock.elapsedRealtime();
                    try { r.run(); } catch (Throwable t) { log("센트리 입출력 작업 오류 — " + t); }
                    finally { ioRunStartElapsed = 0; }
                }
            } finally {
                ioBusy.set(false);
            }
            if (!ioQueue.isEmpty()) kickIo();
        });
    }

    // 저장소 입출력을 별도 스레드에서 돌리고 timeoutMs까지만 기다린다. 앞 작업이 10초 넘게 막혀 있거나 시간을 넘기면 fallback을 돌려주고(작업은 계속 돈다) ioDelay로 표시한다.
    //   앞 작업이 잠깐 도는 중이면(상태 업로드 등) 순서대로 기다린다 — 정상 동작에서 내장으로 잘못 떨어지지 않게
    private <T> T ioCall(String what, long timeoutMs, java.util.concurrent.Callable<T> c, T fallback) {
        long st = ioRunStartElapsed;
        long age = st == 0 ? 0 : SystemClock.elapsedRealtime() - st;
        if (age > 10_000L) {
            ioDelayed = true;
            trace(what + " 건너뜀 — 앞 입출력이 " + (age / 1000) + "s째 막힘");
            log("저장소 입출력 지연 — " + what + " 건너뜀(앞 입출력이 " + (age / 1000) + "초째 진행 중)");
            return fallback;
        }
        java.util.concurrent.Future<T> f = ioExec.submit(() -> {
            ioRunStartElapsed = SystemClock.elapsedRealtime();
            try {
                return c.call();
            } finally {
                ioRunStartElapsed = 0;
            }
        });
        try {
            T v = f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            ioDelayed = false;
            return v;
        } catch (java.util.concurrent.TimeoutException te) {
            ioDelayed = true;
            trace(what + " 시간 초과 " + timeoutMs + "ms");
            log("저장소 입출력 지연 — " + what + " " + timeoutMs + "ms 안에 안 끝남(외장 입출력이 막힌 듯)");
            return fallback;
        } catch (java.util.concurrent.ExecutionException ee) {
            log("저장소 입출력 오류 — " + what + " " + ee.getCause());
            trace(what + " 오류 " + ee.getCause());
            return fallback;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return fallback;
        }
    }

    // 짧은 흔적(최근 8줄)을 폰 저장소(prefs)에 즉시 남기고 상태(sentry_trace)로도 올린다 — 프로세스가 순간 종료돼도 다음 시작 때 sentry_trace_prev로 올라간다
    private void trace(String m) {
        try {
            String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + " " + m;
            String joined;
            synchronized (traceBuf) {
                traceBuf.addLast(line.length() > 120 ? line.substring(0, 120) : line);
                while (traceBuf.size() > 8) traceBuf.removeFirst();
                StringBuilder sb = new StringBuilder();
                for (String x : traceBuf) { if (sb.length() > 0) sb.append(" | "); sb.append(x); }
                joined = sb.length() > 600 ? sb.substring(sb.length() - 600) : sb.toString();
            }
            SentryHost hs = host;
            if (hs == null) return;
            try { hs.prefs().edit().putString(PREF_TRACE, joined).apply(); } catch (Throwable ignored) {}
            hs.putStatus("sentry_trace", joined);
        } catch (Throwable ignored) {}
    }

    private void post(Runnable r) {
        Handler hd = handler;
        if (hd == null) return;
        hd.post(() -> safe(r));
    }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            err = "내부 오류 " + t;
            log("센트리 내부 오류 — " + t);
        }
    }

    // 핸들러 스레드에서 실행하고 끝날 때까지 기다린다(최대 8초)
    private void runAndWait(Runnable r) { runAndWait(r, 8); }

    private void runAndWait(Runnable r, int waitS) {
        Handler hd = handler;
        if (hd == null) return;
        if (Looper.myLooper() == hd.getLooper()) { safe(r); return; }
        CountDownLatch latch = new CountDownLatch(1);
        hd.post(() -> {
            try { safe(r); } finally { latch.countDown(); }
        });
        try {
            if (!latch.await(waitS, TimeUnit.SECONDS)) log("센트리 핸들러 대기 시간 초과(" + waitS + "초)");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static JSONObject errorJson(String msg) {
        try { return new JSONObject().put("ok", false).put("error", msg); } catch (JSONException e) { return new JSONObject(); }
    }
}
