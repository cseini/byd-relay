package com.overdrive.app.camera.dilink5;

// ★fast_cam TCP 브리지 백엔드(BeetleLauncher 1.553 이식, 2026-09-14) — 씨라이언7(DiLink5)에서 실제 검증된 방식.
//   untrusted_app이 fast_cam_capture의 abstract unix socket에 직접 connect 못 함(SELinux denied) → 같은 셸 도메인의
//   브리지 프로세스(SnifferCamBridge, app_process)가 대신 받아 UYVY→RGBA 변환 후 TCP 127.0.0.1:5601 로 재전송,
//   우리 앱은 평범한 TCP 클라이언트로 수신한다. NV21 변환은 DiLink5QCarCamBackend.rgbaToNv21 재사용(같은 패키지).
//   spawn 방식(dadb 셸)은 DiLink5QCarCamBackend.ensureCaptureProcessShell 을 참고해 독립 구현(정적 상태 공유 없음
//   — 두 백엔드가 서로 다른 세션에서 우발적으로 충돌하지 않도록).

import android.content.Context;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class FastCamTcpBackend {

    private static final int TCP_PORT = 5731;   // ★2026-09-19 BeetleLauncher(FastCamTcpClient DEFAULT_PORT=5601·FCAM·PROTO v5)와 포트 충돌 회피 — 고유 포트로 격리(구 5601은 BeetleLauncher 브리지가 점유해 우리 클라가 v5에 붙어 첫프레임0이던 근본).
    private static final String GREETING = "SNF2";   // ★인사말도 분리(양방향 격리 — 우연히 포트 겹쳐도 GREETING 불일치로 서로 안 씀)
    private static final int PROTO_VERSION = 2;
    private static final int FRAME_MAGIC = 1178815793;   // 0x46443F31
    private static final int STATUS_MAGIC = 1178817329;
    private static final int OP_WANT_CAM = 1;

    private static final String SHELL_BIN = "/data/local/tmp/snif_fast_cam_capture";
    private static final String GUARD_SO = "/data/local/tmp/snif_release_guard.so";   // ★712 반납 감시(오버드라이브 방식, 공개 MIT)
    private static final String SHELL_LOG = "/data/local/tmp/snif_fcc.log";
    private static final String BRIDGE_CLASS = "com.overdrive.app.camera.dilink5.SnifferCamBridge";
    private static final String BRIDGE_LOG = "/data/local/tmp/snifcam.log";
    private static final String DASH_DIR = "/data/local/tmp/snifdash";

    private static final SecureRandom RND = new SecureRandom();

    private final int cameraId;
    private DiLink5QCarCamBackend.Logger log = (t, m) -> {};
    private volatile boolean running = false;
    private Thread frameThread;
    private Socket sock;
    private Context ctxRef;

    public FastCamTcpBackend(int cameraId) { this.cameraId = cameraId; }

    private void logi(String m) { try { log.log("D5CAM", m); } catch (Throwable ignore) {} }

    // ★655 데몬 공유 관리(FB1371 스랑 18:22 실측: 앞 세션 종료의 killAll이 다음 세션이 막 띄운 브리지를 죽여 "로딩중") —
    //   start/stop/detach/shutdown을 DAEMON_LOCK 하나로 직렬화하고, 활성 소비자(consumers)가 0일 때만 데몬을 끈다.
    //   쿼드 세션은 boot.detach(true)로 소유권을 넘겨받아 stop에서 releaseDaemon으로 반납한다. 다른 세션이 쓰는 중이면 새 start는 데몬을 새로 띄우지 않고 재사용한다.
    private static final Object DAEMON_LOCK = new Object();
    private static int consumers = 0;
    private boolean counted = false;
    public static int consumers() { synchronized (DAEMON_LOCK) { return consumers; } }
    public static void releaseDaemon(Context ctx, DiLink5QCarCamBackend.Logger lg) {
        synchronized (DAEMON_LOCK) {
            consumers = Math.max(0, consumers - 1);
            FastCamTcpBackend t = new FastCamTcpBackend(0);
            if (ctx != null) t.ctxRef = ctx.getApplicationContext();
            if (lg != null) t.log = lg;
            if (consumers == 0) { t.killAll(); t.logi("데몬 소유권 반납 → 캡처 데몬 종료"); }
            else t.logi("데몬 소유권 반납 — 다른 세션 " + consumers + "개 사용 중이라 유지");
        }
    }
    // ★655 비틀런처 카메라 데몬 종료(운영자 승인 09-29, FB1371) — 앱 강제종료로는 셸 데몬(beetlecam·/data/local/tmp/fast_cam_capture, ppid 1)이 안 죽어 테스트가 무효였다.
    //   우리 데몬(/data/local/tmp/snif_fast_cam_capture)은 경로가 달라 패턴에 안 걸린다. 실시간 보기 재열기 때 채널이 빠져 있으면 자동 실행 + 수동 명령 KILLBEETLECAM.
    // ★680 안전 조건(운영자 10-01) — 비틀 데몬은 깜빡이 측면 카메라·후진 화면을 위해 상시로 카메라를 쥔다. 시동이 켜져 있거나 기어가 P가 아니면(후진·주행 가능) 끄지 않는다.
    //   SnifferService가 "시동 꺼짐(전원 0) + 기어 P + 주행 아님"일 때만 true를 돌려주는 판정을 심는다. 기본값 false(미설정이면 종료하지 않음).
    public static volatile java.util.function.BooleanSupplier killGate = () -> false;
    // ★744 브리지(셸 권한)에서 127.0.0.1로 읽은 바이트 — 이 내부 통신이 안드 앱별 통신량(TrafficStats)에 잡혀 DATAUSE 용도별 칸이
    //   수백 GB로 부풀었다(10-04 060243 감지 322GB). sampleDataUsage가 이만큼 뺀다.
    public static final java.util.concurrent.atomic.AtomicLong LOOP_RX = new java.util.concurrent.atomic.AtomicLong();
    public static String beetleKill(Context ctx) {
        FastCamTcpBackend t = new FastCamTcpBackend(0);
        dadb.Dadb d = null;
        try {
            if (!killGate.getAsBoolean()) return "비틀 데몬 종료 보류 — 시동 켜짐이거나 기어가 P가 아니어서(후진·주행 가능) 건드리지 않음";
            d = t.adb(ctx);
            // ★655 검증 지적 — toybox ps가 -o를 거부하면 plain ps -A로 폴백(NAME 열에 경로 없는 fast_cam_capture만 보임). 우리 데몬(snif_…)은 제외.
            String list = "(ps -A -o PID,PPID,NAME,ARGS 2>/dev/null || ps -A 2>/dev/null) | grep -iE 'beetlecam|fast_cam_capture' | grep -v snif_ | grep -v grep";
            String before = t.sh(d, list).replace('\n', '|');
            if (before.isEmpty()) return "비틀 카메라 데몬 없음";
            t.sh(d, "pkill -f beetlecam; pkill -f '/data/local/tmp/fast_cam_capture'; pkill -x fast_cam_capture 2>/dev/null");
            try { Thread.sleep(1000); } catch (InterruptedException ignore) {}
            String after = t.sh(d, list).replace('\n', '|');
            return "비틀 카메라 데몬 종료: 전=[" + before + "] 후=[" + (after.isEmpty() ? "없음" : after) + "]";
        } catch (Throwable e) { return "비틀 데몬 종료 실패: " + e.getClass().getSimpleName() + ":" + e.getMessage(); }
        finally { try { if (d != null) d.close(); } catch (Throwable ignore) {} }
    }

    /** fast_cam_capture(셸) 배포/spawn + SnifferCamBridge(app_process) 기동 + TCP 클라 연결 + 첫 프레임 4초 대기. */
    public boolean start(Context ctx, int fps, DiLink5QCarCamBackend.FrameSink sink, DiLink5QCarCamBackend.Logger log) { return start(ctx, fps, sink, log, false); }
    /** ★655 forceRespawn=true: 다른 세션이 데몬을 쓰는 중이어도 pkill 후 새로 띄운다(쿼드 재열기 전용 — 호출자가 소유자일 때만). */
    public boolean start(Context ctx, int fps, DiLink5QCarCamBackend.FrameSink sink, DiLink5QCarCamBackend.Logger log, boolean forceRespawn) {
        synchronized (DAEMON_LOCK) { return startLocked(ctx, fps, sink, log, forceRespawn); }
    }
    private boolean startLocked(Context ctx, int fps, DiLink5QCarCamBackend.FrameSink sink, DiLink5QCarCamBackend.Logger log, boolean forceRespawn) {
        this.log = (log != null) ? log : this.log;
        this.ctxRef = ctx.getApplicationContext();
        running = true;

        String socket = null;
        if (consumers > 0 && !forceRespawn) {
            logi("다른 세션의 캡처 데몬 재사용(" + consumers + "개 사용 중) — 새로 띄우지 않음");
            socket = "reuse";
        } else if (forceRespawn && consumers > 1) {   // ★655 검증 지적 — 소유자가 둘 이상이면 재기동이 남의 데몬을 죽이므로 재사용으로 강등
            logi("재기동 생략 — 다른 세션 " + (consumers - 1) + "개가 같은 데몬 사용 중, 재사용");
            socket = "reuse";
        } else {
            // ★682 1단계(관측 전용) — 씨라7 순정 서라운드뷰 상태를 카메라 시작 전에 읽어 기록(동작은 바꾸지 않음). 상태값 뜻은 성공/실패와 함께 모아 판단.
            try { logi("카메라 시작 전 " + AvmStateProbe.probe(ctx)); } catch (Throwable ig) {}
            // ★680 선제 종료 — 이미 비틀 카메라 데몬이 채널을 쥔 차(1572 원주: cam 1·3 점유)는 첫 시도부터 칸이 빠졌다. 안전 조건(시동 꺼짐+P)을 만족할 때만 시작 전에 끈다.
            //   종료 뒤 8초에 다시 떠 있는지 기록한다(비틀이 자동으로 되살리는지 실측용).
            try {
                String kr = beetleKill(ctx);
                if (!kr.startsWith("비틀 카메라 데몬 없음")) logi("카메라 시작 전 점검 — " + kr);
                if (kr.startsWith("비틀 카메라 데몬 종료")) {
                    final Context cx = ctx.getApplicationContext();
                    new Thread(() -> {
                        try {
                            Thread.sleep(8000);
                            FastCamTcpBackend tt = new FastCamTcpBackend(0); dadb.Dadb dd = tt.adb(cx);
                            String again = tt.sh(dd, "(ps -A -o PID,PPID,NAME,ARGS 2>/dev/null || ps -A 2>/dev/null) | grep -iE 'beetlecam|fast_cam_capture' | grep -v snif_ | grep -v grep").replace('\n', '|');
                            try { dd.close(); } catch (Throwable ig) {}
                            logi("비틀 데몬 종료 8초 뒤 재점유 확인 — " + (again.isEmpty() ? "다시 안 뜸" : "다시 떠 있음=[" + again + "]"));
                        } catch (Throwable ig) {}
                    }, "beetle-respawn-check").start();
                }
            } catch (Throwable ig) {}
            dadb.Dadb d = null;
            try {
                d = adb(ctx);
                int[] map = cameraMapping();
                String cams = joinMap(map);
                int capFps = Math.max(1, Math.min(30, fps));
                socket = spawnBridge(ctx, d, cams, capFps);
            } catch (Throwable t) {
                logi("adb 연결/spawn 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage());
                running = false;
                return false;
            } finally {
                try { if (d != null) d.close(); } catch (Throwable ignore) {}
            }
        }
        if (socket == null) { logi("브리지 기동 실패"); running = false; return false; }

        if (!connectTcp(TCP_PORT)) { logi("TCP 브리지 연결 실패(127.0.0.1:" + TCP_PORT + ") 마지막예외=" + lastConnErr); diagnoseBridge(ctx); stop(); return false; }

        try {
            DataInputStream greet = new DataInputStream(sock.getInputStream());
            byte[] g = new byte[4];
            greet.readFully(g);
            if (!GREETING.equals(new String(g, "UTF-8"))) { logi("인사말 불일치: " + new String(g, "UTF-8")); stop(); return false; }
            int ver = greet.readInt();
            logi("브리지에 붙음(v" + ver + ") — 카메라 " + cameraId + " 요청");
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            out.writeInt(OP_WANT_CAM);
            out.writeInt(cameraId);
            out.flush();
        } catch (Throwable t) {
            logi("핸드셰이크 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage());
            stop(); return false;
        }

        final CountDownLatch firstFrame = new CountDownLatch(1);
        frameThread = new Thread(() -> frameLoop(sink, firstFrame), "fastcam-tcp-client");
        frameThread.setDaemon(true);
        frameThread.start();

        boolean gotFrame;
        try { gotFrame = firstFrame.await(4, TimeUnit.SECONDS); } catch (InterruptedException e) { gotFrame = false; }
        if (!gotFrame) { logi("4초 내 첫 프레임 없음 → 폴백"); diagnoseBridge(ctx); stop(); return false; }   // ★2026-09-19 프레임0도 fcc.log 진단(전엔 connectTcp 실패 시만 호출해 QCarCam 실패 사유가 D1에 안 뜸 — 079312 프레임0 원인 미파악)
        logi("첫 프레임 수신 ok — fast_cam TCP 브리지 사용");
        consumers++; counted = true;   // ★655 이 세션이 데몬 소비자로 등록
        return true;
    }

    public void stop() {
        synchronized (DAEMON_LOCK) {
            detachClient();
            if (counted) { counted = false; consumers = Math.max(0, consumers - 1); }
            if (consumers == 0) { killAll(); logi("fast_cam TCP 브리지 정지"); }
            else logi("fast_cam TCP 클라이언트만 정지 — 다른 세션 " + consumers + "개가 캡처 데몬 사용 중이라 유지");   // ★655 세션 겹침 때 남의 데몬 안 죽임
        }
    }
    // ★653 클라이언트 연결만 끊기(캡처 데몬·브리지는 그대로) — 쿼드 시작 때 부팅 확인용 연결을 닫을 때 쓴다.
    //   예전엔 stop()으로 닫아 **채널을 막 연 캡처 데몬에 종료 신호**를 보냈다(주석은 "브리지 안 죽는다"였는데 실제로는 killAll). 유령 채널 후보 제거.
    public void detach() { detach(false); }
    /** ★655 keepCount=true: 데몬 소유권을 호출자(쿼드 세션)에게 넘긴다 — 호출자가 나중에 releaseDaemon으로 반납해야 한다. */
    public void detach(boolean keepCount) {
        synchronized (DAEMON_LOCK) {
            detachClient();
            if (counted) { counted = false; if (!keepCount) consumers = Math.max(0, consumers - 1); }
            if (!keepCount && consumers == 0) { killAll(); logi("연결 닫음 — 남은 소유자 없어 캡처 데몬 종료"); }   // ★655 검증 지적 — 소유자 없는 데몬 잔존 방지
            else logi(keepCount ? "부팅 확인 연결만 닫음(캡처 데몬 유지·소유권 이전)" : "연결만 닫음(캡처 데몬 유지, 사용 중 " + consumers + ")");
        }
    }
    private void detachClient() {
        running = false;
        Thread t = frameThread; frameThread = null;
        if (t != null && t != Thread.currentThread()) { try { t.join(800); } catch (Throwable ignore) {} }
        try { if (sock != null) sock.close(); } catch (Throwable ignore) {}
        sock = null;
    }
    // ★653 캡처 데몬 정상 종료 — 종료 신호 뒤 실제로 끝났는지 확인(최대 3초). 가용성 검사 끝에 1회.
    public void shutdownDaemon(Context ctx, DiLink5QCarCamBackend.Logger dlog) {
        synchronized (DAEMON_LOCK) {   // ★655 사용 중인 세션이 있으면 끄지 않음
            if (consumers > 0) { if (dlog != null) dlog.log("D5CAM", "캡처 데몬 종료 생략 — 세션 " + consumers + "개 사용 중"); return; }
            dadb.Dadb d = null;
            try {
                d = adb(ctx);
                sh(d, "pkill -f " + BRIDGE_CLASS + "; pkill -f " + SHELL_BIN);
                String r = waitCaptureExit(d);
                if (dlog != null) dlog.log("D5CAM", "캡처 데몬 종료 — " + r);
            } catch (Throwable t) {
                if (dlog != null) dlog.log("D5CAM", "캡처 데몬 종료 실패: " + t.getClass().getSimpleName());
            } finally { try { if (d != null) d.close(); } catch (Throwable ignore) {} }
        }
    }
    // ★653 이전 캡처 데몬이 카메라 채널을 다 놓고 끝났는지 확인 — 끝나기 전에 새로 띄우면 채널이 겹쳐 뒤쪽 채널 열기 실패(유령) 의심(FB1332 분석).
    //   [e] 괄호는 이 확인 명령을 실행하는 셸 자신이 걸리지 않게 하는 정규식 요령.
    private String waitCaptureExit(dadb.Dadb d) {
        for (int i = 0; i < 30; i++) {
            if (sh(d, "pgrep -f 'snif_fast_cam_captur[e]'").isEmpty()) return "종료 확인(" + (i * 100) + "ms)";
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        // ★864 FB2329 씨라7 재부팅 — 캡처 데몬(fast_cam_capture)을 SIGKILL하면 그 카메라 그래픽 버퍼를 surfaceflinger가 아직 쓰던 중에 끊겨
        //   composer·surfaceflinger가 그래픽 드라이버 안에서 죽고 헤드유닛이 재부팅된다(10-05·10-10 같은 서명). 캡처는 강제로 죽이지 않는다(d5 공유판·비틀런처와 같게).
        //   그래픽 버퍼를 안 쓰는 우리 브리지만 강제 정리한다.
        sh(d, "pkill -9 -f 'SnifferCamBridg[e]'");
        return "3초 지나도 남아 있음(캡처는 강제 종료 안 함)";
    }

    // ★688 서비스 시작 때 옛 우리 데몬 정리(앱이 정지 없이 죽으면 setsid·--time 0 데몬이 남아 CPU를 쓴다 — 씨라이언7 079312 실측 의심). 보기 사용 중(consumers>0)이면 건드리지 않는다.
    //   대상은 snif_ 이름만(오버드라이브·비틀의 fast_cam_capture는 패턴에 안 걸림). 못 찾으면 "옛 데몬 없음".
    public static String cleanupStale(Context ctx) {
        synchronized (DAEMON_LOCK) {
            if (consumers > 0) return "옛 데몬 없음(보기 사용 중이라 정리 생략)";
            FastCamTcpBackend t = new FastCamTcpBackend(0);
            t.ctxRef = ctx.getApplicationContext();
            dadb.Dadb d = null;
            try {
                d = t.adb(ctx);
                String found = t.sh(d, "for p in $(pgrep -f 'snif_fast_cam_captur[e]'; pgrep -f 'SnifferCamBridg[e]'); do echo $p:$(cut -d' ' -f14,15 /proc/$p/stat); done").replace('\n', ' ').trim();
                if (found.isEmpty()) return "옛 데몬 없음";
                t.sh(d, "pkill -f 'snif_fast_cam_captur[e]'; pkill -f 'SnifferCamBridg[e]'");
                String r = t.waitCaptureExit(d);
                if (!t.sh(d, "pgrep -f 'SnifferCamBridg[e]'").isEmpty()) {
                    t.sh(d, "pkill -9 -f 'SnifferCamBridg[e]'");
                    r += " · 브리지 강제 종료";
                }
                return "옛 데몬 발견 [pid:유저/시스템 CPU틱 = " + found + "] → 정리: " + r;
            } catch (Throwable e) {
                return "옛 데몬 정리 실패: " + e.getClass().getSimpleName() + ":" + e.getMessage();
            } finally { try { if (d != null) d.close(); } catch (Throwable ignore) {} }
        }
    }

    private void frameLoop(DiLink5QCarCamBackend.FrameSink sink, CountDownLatch firstFrame) {
        try {
            DataInputStream inp = new DataInputStream(new BufferedInputStream(sock.getInputStream(), 1 << 20));
            byte[] rgbaBytes = null;
            while (running) {
                int magic;
                try { magic = inp.readInt(); } catch (Throwable t) { break; }
                if (magic == STATUS_MAGIC) {
                    int len = inp.readInt();
                    if (len < 0 || len > 4096) { logi("이상한 상태 줄 len=" + len + " — 끊는다"); break; }
                    byte[] sbuf = new byte[len];
                    inp.readFully(sbuf);
                    LOOP_RX.addAndGet(8 + len);
                    continue;
                }
                if (magic != FRAME_MAGIC) { logi("프레임 magic 불일치 0x" + Integer.toHexString(magic) + " — 끊는다"); break; }
                int camId = inp.readInt();
                int w = inp.readInt();
                int h = inp.readInt();
                int len = inp.readInt();
                if (len <= 0 || len > 64 * 1024 * 1024 || len != w * h * 4) { logi("이상한 프레임 cam=" + camId + " " + w + "x" + h + " len=" + len); break; }
                if (rgbaBytes == null || rgbaBytes.length < len) rgbaBytes = new byte[len];
                inp.readFully(rgbaBytes, 0, len);
                LOOP_RX.addAndGet(20 + len);
                ByteBuffer rgba = ByteBuffer.wrap(rgbaBytes, 0, len);
                byte[] nv21 = new byte[w * h * 3 / 2];
                DiLink5QCarCamBackend.rgbaToNv21(rgba, w, h, nv21);
                try { if (sink != null) sink.onFrame(nv21, w, h); } catch (Throwable ignore) {}
                firstFrame.countDown();
            }
        } catch (Throwable t) {
            logi("프레임 루프 예외: " + t.getClass().getSimpleName() + ":" + t.getMessage());
        } finally {
            firstFrame.countDown();   // 대기 중이면 즉시 깨움(실패로 처리)
        }
    }

    private volatile String lastConnErr = "";
    private boolean connectTcp(int port) {
        for (int i = 1; i <= 40 && running; i++) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", port), 500);
                s.setTcpNoDelay(true);
                sock = s;
                return true;
            } catch (Throwable t) {
                lastConnErr = t.getClass().getSimpleName() + ":" + t.getMessage();   // ECONNREFUSED=포트 안 열림(브리지) / EACCES=SELinux
                try { Thread.sleep(500); } catch (InterruptedException ie) { return false; }
            }
        }
        return false;
    }

    // ★501 진단 — TCP 연결 실패 시 브리지 내부 상태를 새 dadb 셸로 읽어 D1로(브리지 로그는 shared SH 차단이라 직접 못 봄).
    //   삼분기 판별: 브리지 죽음(pidof 빈값) / 포트 미개설(tcp5601 없음) / SELinux(connect 예외 EACCES).
    private void diagnoseBridge(Context ctx) {
        dadb.Dadb d = null;
        try {
            d = adb(ctx);
            String pid = sh(d, "pidof snifcam 2>/dev/null; ps -A 2>/dev/null | grep -c snifcam");
            String tcp = sh(d, "cat /proc/net/tcp 2>/dev/null | grep -i ':1663' | head -2");   // 5731=0x1663
            String blog = sh(d, "tail -c 800 " + BRIDGE_LOG + " 2>/dev/null").replace('\n', '|');
            String flog = sh(d, "tail -c 600 " + SHELL_LOG + " 2>/dev/null").replace('\n', '|');   // ★tail -c(마지막 바이트) — QCarCam open 에러는 맨 끝에 찍혀 substring(0,N)이면 잘렸음(advisor 09-19)
            logi("진단 connErr=" + lastConnErr + " pidof_snifcam=[" + pid.trim() + "] tcp5731=[" + tcp.trim() + "]");
            logi("진단 브리지로그(snifcam.log)=" + blog);
            logi("진단 fastcam로그(snif_fcc.log)=" + flog);
            String lc = sh(d, "logcat -d -t 400 2>/dev/null | grep -iE 'avc: ?denied|fast_cam|libais|qcarcam|snifcam|ais_client' | tail -c 900").replace('\n', '|');   // ★2026-09-19 SELinux denial(avc)+QCarCam은 kernel audit/logcat에만 뜸 — fcc.log(stdout)엔 안 뜰 수 있어 logcat도 수집(로그분석 실증: 089612 09-16 avc denied read libais_client.so scontext=untrusted_app_25)
            logi("진단 logcat(avc/qcarcam)=" + (lc.isEmpty() ? "(매치없음)" : lc));
            // ★675 카메라 점유자 후보 — 4칸이 전부 안 열리는 차(FB1569 일산도시파파: 오버드라이브·비틀 설치, 4채널 전부 Start failed)의 원인 증거. 이 경로(브리지 실패)에선 프로세스 목록이 안 남았다.
            String ps = sh(d, "ps -A 2>/dev/null | grep -iE 'beetle|fast_cam|avm|camera|qcarcam|overdrive|electro|dashcam|sentry|recorder|surveil|bydcontroller|atvtools' | grep -v grep").replace('\n', '|');
            logi("진단 점유자후보(ps)=" + (ps.isEmpty() ? "(없음)" : ps));
        } catch (Throwable t) { logi("브리지 진단 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage()); }
        finally { try { if (d != null) d.close(); } catch (Throwable ignore) {} }
    }

    // ★FB1100 쿼드 미도착 진단(2026-09-28 운영자 "비틀런처 카메라 점유 의심") — 4채널(cams=0,1,2,3) 요청했는데 일부만 오는 차의 원인 규명.
    //   브리지 헤더(snifcam.log: channel_cnt·채널별 camera_id = fast_cam이 QCarCam에서 실제로 연 채널)·fast_cam 로그(snif_fcc.log: QCarCam open 에러,
    //   EBUSY/in-use=다른 앱 점유 vs not-found/absent=물리부재)·점유자후보(ps beetle/avm/camera)·SELinux(logcat avc denied)를 D1로 올린다.
    //   브리지는 프레임 공급 중이라 살아있어 로그 최신. 읽기전용 tail이라 스트림 무영향. FastCamQuadBackend 미도착 분기에서 호출.
    // ★652 카메라 관련 프로세스 스냅샷(점유자 후보) — 4분할 시작 때마다 남겨 성공/누락 세션을 비교(FB1332: 누락 7건 전부 beetlecam 실행 중, 성공 때는 기록 없음).
    public String camProcSnapshot(Context ctx) {
        dadb.Dadb d = null;
        try { d = adb(ctx); return sh(d, "ps -A 2>/dev/null | grep -iE 'beetle|fast_cam|avm|camera|qcarcam|overdrive|electro' | grep -v grep").replace('\n', '|'); }
        catch (Throwable t) { return "스냅샷 실패: " + t.getClass().getSimpleName(); }
        finally { try { if (d != null) d.close(); } catch (Throwable ignore) {} }
    }
    public void diagnoseCamChannels(Context ctx, DiLink5QCarCamBackend.Logger dlog, String reason) {
        dadb.Dadb d = null;
        try {
            d = adb(ctx);
            String blog = sh(d, "tail -c 2500 " + BRIDGE_LOG + " 2>/dev/null").replace('\n', '|');
            String flog = sh(d, "tail -c 2500 " + SHELL_LOG + " 2>/dev/null").replace('\n', '|');
            String ps = sh(d, "ps -A 2>/dev/null | grep -iE 'beetle|fast_cam|avm|camera|qcarcam' | grep -v grep").replace('\n', '|');
            String lc = sh(d, "logcat -d -t 500 2>/dev/null | grep -iE 'avc: ?denied|qcarcam|fast_cam|libais|ais_client|EBUSY|in.?use|busy|no.?such|not.?found|absent' | tail -c 1300").replace('\n', '|');
            dlog.log("CAMRTC", "★쿼드미도착 진단(" + reason + ") 브리지헤더(snifcam channel_cnt·camera_id)=" + blog);
            dlog.log("CAMRTC", "★쿼드미도착 진단 fastcam(snif_fcc QCarCam open, EBUSY=점유/notfound=부재)=" + flog);
            dlog.log("CAMRTC", "★쿼드미도착 진단 점유자후보(ps cam)=" + (ps.isEmpty() ? "(없음)" : ps));
            dlog.log("CAMRTC", "★쿼드미도착 진단 logcat(avc/qcarcam/busy)=" + (lc.isEmpty() ? "(매치없음)" : lc));
        } catch (Throwable t) {
            try { dlog.log("CAMRTC", "쿼드미도착 진단 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage()); } catch (Throwable ignore) {}
        } finally { try { if (d != null) d.close(); } catch (Throwable ignore) {} }
    }

    // ── fast_cam_capture(셸 spawn) + SnifferCamBridge(app_process) 배포/기동 ──
    private String spawnBridge(Context ctx, dadb.Dadb d, String cams, int fps) {
        try {
            File nativeBin = new File(ctx.getApplicationInfo().nativeLibraryDir, "libfast_cam_capture.so");
            if (!nativeBin.isFile()) { logi("libfast_cam_capture.so 없음 — 브리지 불가"); return null; }
            File client = new File(nativeBin.getParentFile(), "libfast_cam_client.so");
            d.push(nativeBin, SHELL_BIN, 493 /*0755*/, System.currentTimeMillis());
            if (client.isFile()) d.push(client, "/data/local/tmp/snif_fast_cam_client.so", 420, System.currentTimeMillis());
            // ★712 오버드라이브 방식(앱 토글 new일 때, ★722부터 기본 아님) — 반납 감시 .so를 캡처에 얹는다. 브리지가 핸드셰이크 비트로 자동 감지해 프레임마다 반납을 보냄(공개 MIT, native/release_guard).
            //   앱 설정 '카메라 방식=기존(legacy)'이면 안 얹음(종전 동작). 안 되는 차는 토글로 즉시 되돌림.
            boolean legacy = "legacy".equals(ctx.getSharedPreferences(com.cseini.bydsniffer.SnifferService.PREFS, Context.MODE_PRIVATE).getString("d5cam_mode", "legacy"));   // ★722 기본 legacy(저장값 없으면 반납 감시 안 얹음)
            File guard = new File(nativeBin.getParentFile(), "libsnif_release_guard.so");
            boolean useGuard = !legacy && guard.isFile();
            if (useGuard) d.push(guard, GUARD_SO, 493 /*0755*/, System.currentTimeMillis());
            sh(d, "chmod 755 " + SHELL_BIN + "; pkill -f " + SHELL_BIN + "; pkill -f " + BRIDGE_CLASS + "; rm -f " + SHELL_LOG + " " + BRIDGE_LOG);
            logi("이전 캡처 데몬 " + waitCaptureExit(d) + " · 반납감시=" + (useGuard ? "ON(오버드라이브)" : (legacy ? "off(기존 모드)" : "off(.so 없음)")));   // ★653 끝난 걸 확인한 뒤 새로 띄움

            String socket = "@dilink5_fast_" + hex(16);
            String capCmd = (useGuard ? "LD_PRELOAD=" + GUARD_SO + " " : "") + "LD_LIBRARY_PATH=/vendor/lib64:/system/lib64:/data/local/tmp " + SHELL_BIN
                    + " --cams " + cams + " --fps " + fps + " --time 0 --socket " + socket;
            sh(d, "setsid nohup sh -c '" + capCmd + "' </dev/null >" + SHELL_LOG + " 2>&1 &");
            try { Thread.sleep(600); } catch (InterruptedException ignore) {}
            String pid = sh(d, "pgrep -f " + SHELL_BIN + " | head -1");
            if (pid.isEmpty() || !pid.matches("\\d+")) {
                String capLog = sh(d, "tail -c 500 " + SHELL_LOG).replace('\n', '|');
                logi("fast_cam_capture 조기종료 — 로그=" + capLog);
                return null;
            }
            // ★소켓 이름 불일치(QCarCam 백엔드와 동일 근본원인) — 바이너리가 --socket 무시하고 자체 이름을 생성할 수 있다.
            //   실제 로그에서 소켓 이름을 다시 읽어 채택(못 읽으면 우리가 준 이름 폴백).
            String actualSock = socket;
            try {
                String found = sh(d, "grep -ao '@dilink5_fast_[0-9a-f]*' " + SHELL_LOG + " | head -1");
                if (found.startsWith("@dilink5_fast_") && found.length() >= 14) actualSock = found;
            } catch (Throwable ignore) {}
            logi("fast_cam_capture 셸 spawn ok pid=" + pid + " cams=" + cams + " fps=" + fps + " socket=" + actualSock);

            String classPath = ctx.getApplicationInfo().sourceDir;
            String bridgeCmd = "CLASSPATH=" + classPath + " setsid app_process /system/bin --nice-name=snifcam "
                    + BRIDGE_CLASS + " --fastcam " + TCP_PORT + " " + actualSock + " " + DASH_DIR;
            sh(d, "setsid nohup sh -c '" + bridgeCmd + "' </dev/null >" + BRIDGE_LOG + " 2>&1 &");
            try { Thread.sleep(400); } catch (InterruptedException ignore) {}
            String bpid = sh(d, "pgrep -f " + BRIDGE_CLASS + " | head -1");
            if (bpid.isEmpty() || !bpid.matches("\\d+")) {
                String brLog = sh(d, "tail -c 800 " + BRIDGE_LOG).replace('\n', '|');
                logi("SnifferCamBridge 조기종료 — 로그=" + brLog);
                return null;
            }
            logi("SnifferCamBridge spawn ok pid=" + bpid + " tcpPort=" + TCP_PORT + " socket=" + actualSock);
            return actualSock;
        } catch (Throwable t) {
            logi("spawnBridge 예외: " + t.getClass().getSimpleName() + ":" + t.getMessage());
            return null;
        }
    }

    private void killAll() {
        if (ctxRef == null) return;
        dadb.Dadb d = null;
        try {
            d = adb(ctxRef);
            sh(d, "pkill -f " + BRIDGE_CLASS + "; pkill -f " + SHELL_BIN);
            String r = waitCaptureExit(d);   // ★688 안 죽으면 강제 종료까지
            if (!r.startsWith("종료 확인")) logi("캡처 데몬 종료 — " + r);
        } catch (Throwable e) {
            logi("캡처 데몬 종료 실패(adb) — " + e.getClass().getSimpleName() + ":" + e.getMessage());   // ★688 예전엔 조용히 무시 → 데몬 잔존 원인 추적 불가
        } finally { try { if (d != null) d.close(); } catch (Throwable ignore) {} }
    }

    private dadb.Dadb adb(Context ctx) throws IOException {
        File pk = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
        if (!pk.exists() || !pub.exists()) throw new IOException("adb 키 없음");
        return dadb.Dadb.Companion.create("127.0.0.1", 5555, dadb.AdbKeyPair.Companion.read(pk, pub), 8000, 30000);
    }

    private String sh(dadb.Dadb d, String cmd) { try { return d.shell(cmd).getAllOutput().trim(); } catch (Throwable t) { return "(sh 실패 " + t.getMessage() + ")"; } }

    // ── 카메라 매핑(input_id): 표준 {0,1,2,3}=Front/Right/Rear/Left, Shark/DMO={8,9,5,4} (DiLink5QCarCamBackend와 동일) ──
    private static int[] cameraMapping() {
        try {
            String s = ((Build.MODEL != null ? Build.MODEL : "") + " " + (Build.PRODUCT != null ? Build.PRODUCT : "")).toLowerCase();
            if (s.contains("shark") || s.contains("dmo")) return new int[]{ 8, 9, 5, 4 };
        } catch (Throwable ignore) {}
        return new int[]{ 0, 1, 2, 3 };
    }

    private static String joinMap(int[] m) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < m.length; i++) { if (i > 0) sb.append(','); sb.append(m[i]); }
        return sb.toString();
    }

    private static String hex(int n) {
        byte[] b = new byte[n]; RND.nextBytes(b);
        char[] c = new char[n * 2]; char[] H = "0123456789abcdef".toCharArray();
        for (int i = 0; i < n; i++) { c[i * 2] = H[(b[i] & 0xFF) >>> 4]; c[i * 2 + 1] = H[b[i] & 0x0F]; }
        return new String(c);
    }
}
