package com.overdrive.app.camera.dilink5;

// ★DiLink5(씨라이언7) QCarCam 카메라 백엔드 — Overdrive braveheart v47.1 네이티브 재사용(approach A).
//   DiLink5엔 bydauto 카메라(bmmcamera) 없음(CAMRTC ClassNotFound JNILoader) → QCarCam 정공법.
//   패키지·클래스명은 libdilink5_camera.so의 JNI 심볼(Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_*)과
//   반드시 일치해야 하므로 이 이름 고정(우리 앱 내 재구현, deploy/spawn/EGL/glReadPixels는 우리 코드).
//   프레임: fast_cam_capture(spawn)→DMA소켓→libfast_cam_client.so→nativeBindLatestFrame이 '바인딩된 GL텍스처'에 glTexImage2D.
//   → 우리는 EGL 오프스크린 컨텍스트+텍스처+FBO 만들어 glReadPixels(RGBA)→NV21 변환해 기존 파이프라인(latest[0])에 공급.

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

public final class DiLink5QCarCamBackend {

    public interface Logger { void log(String tag, String msg); }
    public interface FrameSink { void onFrame(byte[] nv21, int w, int h); }

    // ── JNI 네이티브(libdilink5_camera.so, static/instance는 디컴파일 확정과 정확히 일치) ──
    private static native int nativeBindLatestFrame(int cameraId);
    private static native long nativeGetLatestFrameTimestamp();
    private native long nativeInit(int id, String socketPath, long pid, int fps);
    private static native boolean nativeIsSupported();
    private native boolean nativeRelease(long handle);
    private static native void nativeSetActiveCamera(int cameraId);
    private static native void nativeSetCameraMapping(int f, int r, int re, int l, int x);
    private static native void nativeSetOutputFps(int fps);
    private native boolean nativeStart(long handle);
    private native boolean nativeStop(long handle);

    private static volatile boolean sLibsLoaded = false;
    private static volatile boolean sLibLoadOk = false;
    private static final SecureRandom RND = new SecureRandom();
    private static final Object PROC_LOCK = new Object();
    private static volatile java.lang.Process sProc;          // fast_cam_capture(단일 프로세스)
    private static volatile String sSocket;         // @dilink5_fast_...
    private static volatile int sChildPid = -1;
    private static volatile long sFrameAvail = 0;   // onNativeFrameAvailable 콜백 카운터

    private long handle = 0;
    private final int cameraId;
    private Thread glThread;
    private volatile boolean running = false;
    private Logger log = (t, m) -> {};

    public DiLink5QCarCamBackend(int cameraId) { this.cameraId = cameraId; }

    private void logi(String m) { try { log.log("D5CAM", m); } catch (Throwable ignore) {} }

    // 네이티브가 새 프레임 준비 시 호출(정적) — Overdrive와 동일 시그니처. 폴링 보조.
    public static void onNativeFrameAvailable(long ts) { sFrameAvail++; }

    private static synchronized boolean loadLibs() {
        if (sLibsLoaded) return sLibLoadOk;
        sLibsLoaded = true;
        try {
            System.loadLibrary("c++_shared");        // libfast_cam_client.so가 NEEDED
            System.loadLibrary("fast_cam_client");   // libdilink5_camera.so가 NEEDED
            System.loadLibrary("dilink5_camera");
            sLibLoadOk = true;
        } catch (Throwable t) {
            sLibLoadOk = false;
        }
        return sLibLoadOk;
    }

    /** AIS/QCarCam 지원 여부(라이브러리 로드 + 네이티브 체크). isDiLink5() 게이트는 호출자 몫. */
    public static boolean isSupported(Logger log) {
        try {
            if (!loadLibs()) { if (log != null) log.log("D5CAM", "네이티브 라이브러리 로드 실패"); return false; }
            boolean ok = nativeIsSupported();
            if (log != null) log.log("D5CAM", "nativeIsSupported=" + ok);
            return ok;
        } catch (Throwable t) {
            if (log != null) log.log("D5CAM", "isSupported 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage());
            return false;
        }
    }

    /** 카메라 시작: 배포+spawn+init+start+GL 캡처 스레드. 프레임은 sink.onFrame(NV21,w,h)로 전달. */
    public synchronized boolean start(Context ctx, int fps, FrameSink sink, Logger log) {
        this.log = (log != null) ? log : this.log;
        try {
            if (!loadLibs()) { logi("라이브러리 로드 실패 → 중단"); return false; }
            if (!nativeIsSupported()) { logi("nativeIsSupported=false(AIS 미지원) → 중단"); return false; }

            int[] map = cameraMapping();
            String cams = joinMap(map);
            int capFps = Math.max(1, Math.min(30, fps));

            try { nativeSetCameraMapping(map[0], map[1], map[2], map[3], map.length == 5 ? map[4] : -1); }
            catch (Throwable t) { logi("setCameraMapping 실패: " + t.getMessage()); }

            if (!ensureCaptureProcess(ctx, cams, capFps)) { logi("fast_cam_capture 시작 실패"); return false; }

            long h = nativeInit(cameraId, sSocket, sChildPid, capFps);
            if (h == 0) {
                logi("nativeInit=0(브리지 초기화 실패)" + (sShell ? " — 셸 도메인 소켓 접속 거부(SELinux connectto/fd) 의심" : ""));
                if (sShell) { try { logi(captureChildLogcat(android.os.Process.myPid(), new File(SHELL_BIN))); } catch (Throwable ignore) {} }
                stopProcess(); return false;
            }
            this.handle = h;
            logi("nativeInit ok handle=0x" + Long.toHexString(h) + " (DMA 소켓·첫 프레임 대기)");

            try { nativeSetActiveCamera(cameraId); } catch (Throwable ignore) {}
            try { nativeSetOutputFps(capFps); } catch (Throwable ignore) {}

            if (!nativeStart(handle)) { logi("nativeStart 실패"); releaseHandle(); stopProcess(); return false; }
            logi("nativeStart ok → GL 캡처 스레드 시작(cam=" + cameraId + " cams=" + cams + " fps=" + capFps + ")");

            running = true;
            final int fpsF = capFps;
            glThread = new Thread(() -> glLoop(sink, fpsF), "d5cam-gl");
            glThread.setDaemon(true);
            glThread.start();
            return true;
        } catch (Throwable t) {
            logi("start 예외: " + t.getClass().getSimpleName() + ":" + t.getMessage());
            try { releaseHandle(); stopProcess(); } catch (Throwable ignore) {}
            return false;
        }
    }

    public synchronized void stop() {
        running = false;
        Thread t = glThread; glThread = null;
        if (t != null) { try { t.join(1500); } catch (Throwable ignore) {} }
        try { if (handle != 0) nativeStop(handle); } catch (Throwable ignore) {}
        releaseHandle();
        stopProcess();
        logi("카메라 정지");
    }

    private void releaseHandle() {
        try { if (handle != 0) { nativeRelease(handle); handle = 0; } } catch (Throwable ignore) {}
    }

    // ★조기 종료 진단(2026-09-11, SEALION7-CAMERA-PLAN §VI 진단1·2) — 자식 pid의 logcat 버퍼(-d, 죽은 pid도 버퍼에 남음) +
    //   태그 필터 폴백(pid 못 잡았을 때) + 바이너리 SELinux 컨텍스트/권한(ls -Z). 전부 900자 내로 잘라 한 줄 로그.
    private String captureChildLogcat(int pid, File bin) {
        StringBuilder sb = new StringBuilder("[조기종료 진단] ");
        try {
            String[] cmd = pid > 0
                    ? new String[]{"logcat", "-d", "-v", "brief", "--pid=" + pid}
                    : new String[]{"logcat", "-d", "-v", "brief", "-s", "fast_cam:*", "qcarcam:*", "ais:*", "QCarCam:*", "linker:*"};
            java.lang.Process lp = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder o = new StringBuilder();
            try (InputStream is = lp.getInputStream()) {
                byte[] b = new byte[4096]; int n;
                while ((n = is.read(b)) > 0 && o.length() < 4000) o.append(new String(b, 0, n, "UTF-8"));
            }
            lp.waitFor(2, TimeUnit.SECONDS);
            String s = o.toString().replace('\n', '|').trim();
            // 빈 결과면 pid 필터가 안 먹은 것(logcat 구현차) → 태그 폴백 1회
            if (s.isEmpty() && pid > 0) {
                java.lang.Process lp2 = new ProcessBuilder("logcat", "-d", "-v", "brief", "-s",
                        "fast_cam:*", "qcarcam:*", "ais:*", "QCarCam:*", "linker:*", "AndroidRuntime:*").redirectErrorStream(true).start();
                StringBuilder o2 = new StringBuilder();
                try (InputStream is = lp2.getInputStream()) { byte[] b = new byte[4096]; int n; while ((n = is.read(b)) > 0 && o2.length() < 4000) o2.append(new String(b, 0, n, "UTF-8")); }
                lp2.waitFor(2, TimeUnit.SECONDS);
                s = o2.toString().replace('\n', '|').trim();
                sb.append("(pid필터 빈결과→태그폴백) ");
            }
            sb.append("logcat=").append(s.isEmpty() ? "(없음 — 읽기권한 부족이거나 네이티브가 로그를 안 남김)" : s.substring(Math.max(0, s.length() - 700)));
        } catch (Throwable t) { sb.append("logcat 실패:").append(t.getClass().getSimpleName()).append(':').append(t.getMessage()); }
        try {
            java.lang.Process ls = new ProcessBuilder("ls", "-lZ", bin.getPath()).redirectErrorStream(true).start();
            StringBuilder o = new StringBuilder();
            try (InputStream is = ls.getInputStream()) { byte[] b = new byte[1024]; int n; while ((n = is.read(b)) > 0 && o.length() < 600) o.append(new String(b, 0, n, "UTF-8")); }
            ls.waitFor(2, TimeUnit.SECONDS);
            sb.append(" · ls-Z=").append(o.toString().trim());
        } catch (Throwable t) { sb.append(" · ls-Z 실패:").append(t.getMessage()); }
        return sb.toString();
    }

    // ★APK(lib/arm64-v8a/…)에서 직접 꺼내 filesDir/fastcam 으로 복사 + 실행권한. nativeLibraryDir 추출이 안 된 기기 폴백.
    //   targetSdk 25라 앱 데이터 디렉터리 exec 허용(W^X는 targetSdk 29+). 이미 같은 크기면 재복사 생략.
    private File extractFromApk(Context ctx, String soName) {
        try {
            File outDir = new File(ctx.getFilesDir(), "fastcam");
            if (!outDir.isDirectory() && !outDir.mkdirs()) return null;
            File out = new File(outDir, soName);
            java.util.zip.ZipFile z = new java.util.zip.ZipFile(ctx.getApplicationInfo().sourceDir);
            try {
                java.util.zip.ZipEntry e = z.getEntry("lib/arm64-v8a/" + soName);
                if (e == null) return null;
                if (!out.isFile() || out.length() != e.getSize()) {
                    java.io.InputStream is = z.getInputStream(e);
                    java.io.FileOutputStream os = new java.io.FileOutputStream(out);
                    try { byte[] b = new byte[65536]; int n; while ((n = is.read(b)) > 0) os.write(b, 0, n); }
                    finally { try { os.close(); } catch (Throwable ig) {} try { is.close(); } catch (Throwable ig) {} }
                }
            } finally { try { z.close(); } catch (Throwable ig) {} }
            out.setReadable(true, true);
            out.setExecutable(true, true);
            return (out.isFile() && out.canExecute()) ? out : null;
        } catch (Throwable t) { logi("APK 추출 예외 " + soName + ": " + t); return null; }
    }

    // ── ★셸 도메인 spawn(2026-09-11 씨라7 실차 449 진단 판독) ──
    //   앱(untrusted_app_25)에서 spawn한 fast_cam_capture는 SELinux가 /vendor/lib64/libais_client.so 읽기를 거부한다:
    //   `avc: denied { read } for name="libais_client.so" scontext=u:r:untrusted_app_25 tcontext=u:object_r:vendor_file permissive=0`
    //   → 배너 직후 code=1. Overdrive는 데몬 자체가 adb(shell 도메인)에서 돌아 vendor 라이브러리를 읽는다. 우리는 dadb(127.0.0.1:5555,
    //   shell uid)로 바이너리를 /data/local/tmp에 push하고 셸에서 띄운다(/data/local/tmp는 [unrestricted] 링커 네임스페이스라 vendor lib 로드 가능).
    //   남은 미지수 = 앱→셸 소켓 connect·DMA fd 전달의 SELinux 허용 여부 → nativeInit 결과와 logcat으로 판정(실패 시 아래 진단 로그).
    private static volatile boolean sShell = false;
    private static volatile Context ctxRef;
    private static final String SHELL_BIN = "/data/local/tmp/fast_cam_capture", SHELL_LOG = "/data/local/tmp/fcc.log";
    private dadb.Dadb adb(Context ctx) throws java.io.IOException {
        File pk = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
        if (!pk.exists() || !pub.exists()) throw new java.io.IOException("adb 키 없음");
        return dadb.Dadb.Companion.create("127.0.0.1", 5555, dadb.AdbKeyPair.Companion.read(pk, pub), 8000, 30000);
    }
    private String sh(dadb.Dadb d, String cmd) { try { return d.shell(cmd).getAllOutput().trim(); } catch (Throwable t) { return "(sh 실패 " + t.getMessage() + ")"; } }
    private boolean ensureCaptureProcessShell(Context ctx, File bin, String cams, int fps) {
        dadb.Dadb d = null;
        try {
            d = adb(ctx);
            if (sShell && sChildPid > 0 && !sh(d, "pgrep -f " + SHELL_BIN).isEmpty()) return true;   // 이미 살아있음
            File client = new File(bin.getParentFile(), "libfast_cam_client.so");
            d.push(bin, SHELL_BIN, 493 /*0755*/, System.currentTimeMillis());
            if (client.isFile()) d.push(client, "/data/local/tmp/libfast_cam_client.so", 420, System.currentTimeMillis());
            sh(d, "chmod 755 " + SHELL_BIN + "; pkill -f " + SHELL_BIN + "; rm -f " + SHELL_LOG);
            String socket = "@dilink5_fast_" + hex(16);
            String cmd = "LD_LIBRARY_PATH=/vendor/lib64:/system/lib64:/data/local/tmp " + SHELL_BIN + " --cams " + cams + " --fps " + fps + " --time 0 --socket " + socket;
            sh(d, "setsid nohup sh -c '" + cmd + "' </dev/null >" + SHELL_LOG + " 2>&1 &");
            try { Thread.sleep(600); } catch (InterruptedException ignore) {}
            String pid = sh(d, "pgrep -f " + SHELL_BIN + " | head -1");
            if (pid.isEmpty() || !pid.matches("\\d+")) {
                String log = sh(d, "tail -c 500 " + SHELL_LOG).replace('\n', '|');
                logi("[셸spawn] 조기 종료 — fcc.log=" + log);
                try {   // 자식 로그 태그로 logcat(권한/HAL 사유)
                    java.lang.Process lp = new ProcessBuilder("logcat", "-d", "-v", "brief", "-s", "libfast_cam_cap:*", "libfast_cam_capture:*").redirectErrorStream(true).start();
                    String o = new String(readAll(lp.getInputStream(), 800), "UTF-8").replace('\n', '|');
                    logi("[셸spawn] logcat=" + o);
                } catch (Throwable ignore) {}
                return false;
            }
            // ★2026-09-13 소켓 이름 불일치 근본수정(메밀소바 fcc.log) — fast_cam_capture 바이너리가 --socket 인자를 무시하고 IPC 서버 소켓을 자체 이름으로 만든다
            //   (fcc.log: "IPC Server initialized (@dilink5_fast_61f5...)" ≠ 우리가 준 이름). 우리가 준 이름으로 nativeInit하면 대기 소켓이 없어 프레임 0(자식은 120fps 정상 캡처 중).
            //   → fcc.log에서 실제 소켓 이름을 읽어 nativeInit에 쓴다. 못 읽으면 우리 이름 폴백.
            String actualSock = socket;
            try {
                String found = sh(d, "grep -ao '@dilink5_fast_[0-9a-f]*' " + SHELL_LOG + " | head -1");
                if (found.startsWith("@dilink5_fast_") && found.length() >= 14) actualSock = found;
            } catch (Throwable ignore) {}
            sProc = null; sShell = true; sSocket = actualSock; sChildPid = Integer.parseInt(pid); sChildDiagDone = false;
            logi("fast_cam_capture 셸 spawn ok pid=" + sChildPid + " cams=" + cams + " fps=" + fps + " socket=" + actualSock + (actualSock.equals(socket) ? "" : " (자체생성·--socket 무시 → fcc.log서 실제소켓 채택)") + " (shell 도메인)");
            return true;
        } catch (Throwable t) {
            logi("[셸spawn] 불가(" + t.getMessage() + ") → 앱 도메인 spawn 폴백");
            return false;
        } finally { try { if (d != null) d.close(); } catch (Throwable ignore) {} }
    }
    // ★프레임0 진단(2026-09-13) — spawn·nativeInit·GL스레드는 다 뜨는데 자식이 DMA 소켓에 프레임을 안 쓸 때(size=0 ts=0 지속),
    //   자식(fast_cam_capture) 생존 여부 + fcc.log(자식 stdout/stderr=QCarCam 열기 실패·HAL·포맷 사유)를 1회 떠서 D1로. 셸 도메인 spawn일 때만.
    private static volatile boolean sChildDiagDone = false;
    private void dumpChildDiag() {
        if (sChildDiagDone || !sShell || ctxRef == null) return;
        sChildDiagDone = true;
        new Thread(() -> {
            dadb.Dadb d = null;
            try {
                d = adb(ctxRef);
                String alive = sh(d, "pgrep -f " + SHELL_BIN + " | head -1");
                String log = sh(d, "tail -c 1200 " + SHELL_LOG).replace('\n', '|');
                logi("[프레임0 진단] child=" + (alive.isEmpty() ? "죽음(자식 종료)" : "pid " + alive) + " fcc.log=" + (log.isEmpty() ? "(비어있음)" : log));
                // ★2026-09-14 소비자 프레임0 벽 격리(로그분석 세션 요청) — 앱→셸 abstract 소켓 connect 성공했나 + avc denial.
                String unix = sh(d, "cat /proc/net/unix | grep dilink5_fast").replace('\n', '|');
                logi("[프레임0 진단] unix소켓=" + (unix.isEmpty() ? "(없음)" : unix) + " ※1줄(리스닝만)=connect거부(a) / 2줄(연결됨)=DMA전달벽(b)");
                String avc = sh(d, "logcat -d -b main,system | grep -E 'avc:.*(bydsniffer|untrusted_app_25)' | tail -c 1200").replace('\n', '|');
                logi("[프레임0 진단] avc=" + (avc.isEmpty() ? "(denial 없음)" : avc));
            } catch (Throwable t) { logi("[프레임0 진단] 실패: " + t.getMessage()); }
            finally { try { if (d != null) d.close(); } catch (Throwable ig) {} }
        }, "cam-childdiag").start();
    }
    private static byte[] readAll(java.io.InputStream is, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[2048]; int n;
        while ((n = is.read(b)) > 0 && bo.size() < max) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    // ── fast_cam_capture 배포 + spawn (단일 프로세스, 프로세스락) ──
    private boolean ensureCaptureProcess(Context ctx, String cams, int fps) {
        synchronized (PROC_LOCK) {
            try {
                java.lang.Process p = sProc;
                if (p != null && p.isAlive()) return true;   // 이미 살아있음
                ctxRef = ctx.getApplicationContext();
                File shellBin = new File(ctx.getApplicationInfo().nativeLibraryDir, "libfast_cam_capture.so");
                if (!shellBin.isFile()) shellBin = extractFromApk(ctx, "libfast_cam_capture.so");
                if (shellBin != null && ensureCaptureProcessShell(ctx, shellBin, cams, fps)) return true;
                // ★jniLibs 번들(libfast_cam_capture.so) — 설치 시 nativeLibraryDir에 실행권한으로 자동 추출돼 배포 불필요.
                //   /data/local/tmp 복사는 안드11(SDK30) 일반앱 EACCES(2026-09-10 044177 실차: "deployBinary 예외 ... Permission denied").
                File bin = new File(ctx.getApplicationInfo().nativeLibraryDir, "libfast_cam_capture.so");
                if (!bin.isFile() || !bin.canExecute()) {
                    // ★nativeLibraryDir 추출 실패 실차 대응(2026-09-11 089612) — 원인을 눈으로 보게 디렉터리를 찍고,
                    //   APK에서 직접 꺼내 filesDir로 복사해 실행한다. targetSdk=25라 W^X(=targetSdk 29+ 제한) 대상이 아니라
                    //   앱 데이터 디렉터리 실행이 허용된다. /data/local/tmp(EACCES, 안드11)와 다른 경로.
                    StringBuilder ls = new StringBuilder();
                    File dir = new File(ctx.getApplicationInfo().nativeLibraryDir);
                    File[] fs = dir.listFiles();
                    if (fs == null) ls.append("(목록 못 읽음)");
                    else for (File x : fs) ls.append(x.getName()).append(x.canExecute() ? "(x) " : "(-) ");
                    logi("fast_cam_capture 없음/실행불가 " + bin.getPath() + " · nativeLibraryDir=[" + ls + "] → APK 추출 폴백 시도");
                    bin = extractFromApk(ctx, "libfast_cam_capture.so");
                    extractFromApk(ctx, "libfast_cam_client.so");   // 런타임 의존 .so도 같은 폴더로
                    if (bin == null) { logi("APK 추출 폴백도 실패 — QCarCam 불가"); return false; }
                    logi("APK 추출 폴백 성공 " + bin.getPath() + " (" + bin.length() + "B, exec=" + bin.canExecute() + ")");
                }
                killStale(bin);

                String socket = "@dilink5_fast_" + hex(16);
                ProcessBuilder pb = new ProcessBuilder(bin.getPath(), "--cams", cams, "--fps",
                        Integer.toString(fps), "--time", "0", "--socket", socket);
                // ★폴백으로 filesDir에서 실행할 수도 있으니 그 폴더도 경로에 포함(libfast_cam_client 동반 추출).
                pb.environment().put("LD_LIBRARY_PATH", "/vendor/lib64:/system/lib64:"
                        + ctx.getApplicationInfo().nativeLibraryDir + ":" + bin.getParent());
                pb.redirectErrorStream(true);
                java.lang.Process np = pb.start();
                sProc = np; sShell = false;
                sSocket = socket;
                sChildPid = pidOf(np);
                // 출력 드레인(파이프 막힘 방지) — ★앞부분은 버리지 않고 보관: 조기 종료 원인(사용법·권한·HAL 에러)이 여기 찍힌다
                //   (2026-09-10 씨라7 실차 `조기 종료 code=1`인데 출력이 폐기돼 원인 불명이었음). stderr는 redirectErrorStream으로 합류.
                final StringBuilder out = new StringBuilder();
                Thread drain = new Thread(() -> {
                    try { java.io.InputStream is = np.getInputStream(); byte[] b = new byte[4096]; int n;
                        while ((n = is.read(b)) > 0) { synchronized (out) { if (out.length() < 900) out.append(new String(b, 0, n, "UTF-8")); } }
                    } catch (Throwable ignore) {}
                }, "d5cam-drain");
                drain.setDaemon(true); drain.start();
                // 초기 크래시 감지
                if (np.waitFor(300, TimeUnit.MILLISECONDS)) {
                    int code = np.exitValue();
                    try { drain.join(250); } catch (Throwable ignore) {}
                    String o; synchronized (out) { o = out.toString().replace('\n', '|').trim(); }
                    logi("fast_cam_capture 조기 종료 code=" + code + " 출력=" + (o.isEmpty() ? "(없음)" : o.substring(0, Math.min(400, o.length()))));
                    // ★진단 1순위(SEALION7-CAMERA-PLAN §VI, 2026-09-11) — 434 폴백 이후 바이너리는 실행되지만 배너 직후 code=1.
                    //   stdout엔 에러가 없다 = 네이티브 로그(__android_log_print)로 갔다(NEEDED에 liblog.so). 자식 pid로 logcat 버퍼를
                    //   덤프해 dlopen/HAL/권한 중 뭔지 한 번에 가린다 + 바이너리 SELinux 컨텍스트(ls -Z).
                    logi(captureChildLogcat(sChildPid, bin));
                    sProc = null; return false;
                }
                logi("fast_cam_capture spawn ok pid=" + sChildPid + " cams=" + cams + " fps=" + fps + " socket=" + socket);
                return true;
            } catch (Throwable t) {
                logi("ensureCaptureProcess 예외: " + t.getMessage());
                return false;
            }
        }
    }

    private void stopProcess() {
        synchronized (PROC_LOCK) {
            java.lang.Process p = sProc; boolean shell = sShell; int pid = sChildPid;
            sProc = null; sSocket = null; sChildPid = -1; sShell = false;
            if (shell) {   // 셸 도메인 프로세스는 dadb로 종료
                try { dadb.Dadb d = adb(ctxRef); try { sh(d, "kill " + pid + " 2>/dev/null; pkill -f " + SHELL_BIN); } finally { d.close(); } } catch (Throwable ignore) {}
                return;
            }
            if (p == null) return;
            try { p.destroy(); if (!p.waitFor(1000, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); p.waitFor(1000, TimeUnit.MILLISECONDS); } }
            catch (Throwable ignore) {}
        }
    }

    private void killStale(File bin) {
        try {
            String self = bin.getCanonicalPath();
            File[] procs = new File("/proc").listFiles();
            if (procs == null) return;
            for (File f : procs) {
                String name = f.getName();
                if (!name.matches("\\d+")) continue;
                int pid; try { pid = Integer.parseInt(name); } catch (Throwable e) { continue; }
                if (pid <= 0 || pid == Process.myPid()) continue;
                try {
                    String exe = Os.readlink("/proc/" + pid + "/exe");
                    if (exe.endsWith(" (deleted)")) exe = exe.substring(0, exe.length() - 10);
                    if (self.equals(exe)) { try { Os.kill(pid, OsConstants.SIGKILL); } catch (Throwable ignore) {} }
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
    }

    // ── GL 캡처 루프: EGL 오프스크린 + 텍스처 + FBO + nativeBindLatestFrame + glReadPixels + RGBA→NV21 ──
    private void glLoop(FrameSink sink, int fps) {
        EGLDisplay dpy = EGL14.EGL_NO_DISPLAY; EGLContext ctx = EGL14.EGL_NO_CONTEXT; EGLSurface surf = EGL14.EGL_NO_SURFACE;
        int texId = 0, fbo = 0;
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { logi("eglInitialize 실패"); return; }
            int[] cfgAttr = { EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE };
            EGLConfig[] cfgs = new EGLConfig[1]; int[] num = new int[1];
            if (!EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, num, 0) || num[0] <= 0) { logi("eglChooseConfig 실패"); return; }
            ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, new int[]{ EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE }, 0);
            if (ctx == EGL14.EGL_NO_CONTEXT) { logi("eglCreateContext 실패"); return; }
            surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], new int[]{ EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE }, 0);
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) { logi("eglMakeCurrent 실패"); return; }

            int[] t = new int[1]; GLES20.glGenTextures(1, t, 0); texId = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            int[] fb = new int[1]; GLES20.glGenFramebuffers(1, fb, 0); fbo = fb[0];

            long lastTs = -1; long frameMs = Math.max(20, 1000 / Math.max(1, fps));
            int w = 0, h = 0; ByteBuffer rgba = null; boolean firstLogged = false; int emptyStreak = 0;

            while (running) {
                long ts = 0; try { ts = nativeGetLatestFrameTimestamp(); } catch (Throwable ignore) {}
                if (ts == lastTs) { try { Thread.sleep(5); } catch (Throwable ignore) {} continue; }

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
                int size;
                try { size = nativeBindLatestFrame(cameraId); } catch (Throwable t2) { logi("nativeBindLatestFrame 예외: " + t2.getMessage()); break; }
                if (size <= 0) { if (++emptyStreak == 1 || emptyStreak % 200 == 0) logi("프레임 없음(size=" + size + " ts=" + ts + ")"); if (emptyStreak == 250) dumpChildDiag(); try { Thread.sleep(10); } catch (Throwable ignore) {} continue; }
                emptyStreak = 0; lastTs = ts;

                int qw = texDim(android.opengl.GLES31.GL_TEXTURE_WIDTH), qh = texDim(android.opengl.GLES31.GL_TEXTURE_HEIGHT);
                if (qw > 0 && qh > 0) { w = qw; h = qh; }
                else if (w == 0) { w = 1280; h = Math.max(1, size / (w * 4)); }   // 폴백: RGBA 가정, 첫런 로그로 교정
                if ((w & 1) == 1) w &= ~1; if ((h & 1) == 1) h &= ~1;
                if (w <= 0 || h <= 0) continue;

                if (rgba == null || rgba.capacity() < w * h * 4) rgba = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
                rgba.position(0);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texId, 0);
                int fbs = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
                if (fbs != GLES20.GL_FRAMEBUFFER_COMPLETE) { if (!firstLogged) logi("FBO 불완전 status=0x" + Integer.toHexString(fbs) + " (texW=" + qw + " texH=" + qh + " size=" + size + ")"); continue; }
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba);
                int glErr = GLES20.glGetError();

                if (!firstLogged) {
                    firstLogged = true;
                    logi("첫 프레임: bindSize=" + size + " texW=" + qw + " texH=" + qh + " → 사용 " + w + "x" + h
                            + " (size/(w*h)=" + (w * h > 0 ? (size / (w * h)) : 0) + "bpp) glReadErr=0x" + Integer.toHexString(glErr));
                }

                byte[] nv21 = new byte[w * h * 3 / 2];
                rgbaToNv21(rgba, w, h, nv21);
                try { if (sink != null) sink.onFrame(nv21, w, h); } catch (Throwable ignore) {}

                try { Thread.sleep(frameMs); } catch (Throwable ignore) {}
            }
        } catch (Throwable t2) {
            logi("glLoop 예외: " + t2.getClass().getSimpleName() + ":" + t2.getMessage());
        } finally {
            try { if (fbo != 0) GLES20.glDeleteFramebuffers(1, new int[]{ fbo }, 0); } catch (Throwable ignore) {}
            try { if (texId != 0) GLES20.glDeleteTextures(1, new int[]{ texId }, 0); } catch (Throwable ignore) {}
            try {
                if (dpy != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
                    if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
                    EGL14.eglTerminate(dpy);
                }
            } catch (Throwable ignore) {}
        }
    }

    private static int texDim(int pname) {
        // GLES3.1 glGetTexLevelParameteriv — SA8155P(DiLink5)는 GLES3.2 지원. 실패/미지원이면 0 반환.
        try {
            int[] v = new int[1];
            android.opengl.GLES31.glGetTexLevelParameteriv(GLES20.GL_TEXTURE_2D, 0, pname, v, 0);
            if (GLES20.glGetError() == GLES20.GL_NO_ERROR && v[0] > 0) return v[0];
        } catch (Throwable ignore) {}
        return 0;
    }

    // RGBA(직접버퍼) → NV21(Y plane + interleaved VU). CPU 변환(첫 동작 우선, 최적화는 후속).
    static void rgbaToNv21(ByteBuffer rgba, int w, int h, byte[] out) {   // package-visible: AvmCamBackend 재사용
        int ySize = w * h;
        byte[] src = new byte[w * h * 4];
        rgba.position(0); rgba.get(src);
        // Y
        for (int j = 0; j < h; j++) {
            int rowOff = j * w * 4;
            int yOff = j * w;
            for (int i = 0; i < w; i++) {
                int p = rowOff + i * 4;
                int r = src[p] & 0xFF, g = src[p + 1] & 0xFF, b = src[p + 2] & 0xFF;
                int y = (66 * r + 129 * g + 25 * b + 128) >> 8;
                out[yOff + i] = (byte) (y + 16);
            }
        }
        // VU (2x2 서브샘플, NV21=V먼저 U)
        int uvOff = ySize;
        for (int j = 0; j < h; j += 2) {
            for (int i = 0; i < w; i += 2) {
                int p = (j * w + i) * 4;
                int r = src[p] & 0xFF, g = src[p + 1] & 0xFF, b = src[p + 2] & 0xFF;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                out[uvOff++] = (byte) clamp(v);
                out[uvOff++] = (byte) clamp(u);
            }
        }
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    // ── 카메라 매핑(input_id): 표준 {0,1,2,3}=Front/Right/Rear/Left, Shark/DMO={8,9,5,4} ──
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

    private static int pidOf(java.lang.Process p) {
        try {
            String s = p.toString();
            int a = s.indexOf("pid="); if (a < 0) return -1;
            int b = s.indexOf(',', a); if (b < 0) b = s.indexOf(']', a);
            return Integer.parseInt(s.substring(a + 4, b > 0 ? b : s.length()).trim());
        } catch (Throwable t) { return -1; }
    }

    private static String hex(int n) {
        byte[] b = new byte[n]; RND.nextBytes(b);
        char[] c = new char[n * 2]; char[] H = "0123456789abcdef".toCharArray();
        for (int i = 0; i < n; i++) { c[i * 2] = H[(b[i] & 0xFF) >>> 4]; c[i * 2 + 1] = H[b[i] & 0x0F]; }
        return new String(c);
    }

}
