package com.cseini.bydsniffer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashSet;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

// ★씨라이언7(DiLink5, Android 11) 전용 로컬 adb self-grant.
//   in-process VMRuntime.setHiddenApiExemptions는 DiLink5에서 OEM SDK가 부르는 hidden 멤버
//   (com.ts.lib.caradapter.CarAdapterManager.getInstance)를 못 풀어 getInstance 실패 → telemetry 0.
//   사용자가 무선 ADB(5555) 켜고 1회 허용하면 로컬 adbd(127.0.0.1:5555)에 dadb RSA 인증으로 붙어
//   shell(uid=shell) 권한으로 hidden-API 완화 + BYDAUTO_*_COMMON grant + ADB 지속 시도.
//   exemption은 프로세스 fork 시 캡처 + 재부팅 리셋 → 적용 후 restartApp, 매 시작 시 재확인(idempotent).
public final class AdbAccess {
    private static final String TAG = "AdbAccess";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 2000;   // ★소켓 레벨 타임아웃 — 미지정(0)이면 auth 블록 시 스레드 좀비
    private static final int SOCKET_TIMEOUT_MS = 2500;

    static volatile String STATUS = "(미실행)";   // 진단/UI 노출용 최근 상태
    static volatile String GRANT_FAIL = "";       // ★grant 실패 권한명(D5 실제 권한명 원격 확인용)
    private static volatile boolean setupInProgress = false;   // runSetup 중복 실행 가드

    // getInstance가 server-side에서 *_COMMON을 체크 → shell로 grant(우리 앱에만 영향, 항상 안전).
    private static final String[] BYDAUTO_COMMON = {
            "android.permission.BYDAUTO_STATISTIC_COMMON",
            "android.permission.BYDAUTO_AC_COMMON",
            "android.permission.BYDAUTO_INSTRUMENT_COMMON",
            // ★D5 읽기 telemetry probe용(읽기 전용). 권한명 D3 규칙 그대로 시도 — 없거나 self-grant 불가면
            //   실패만(무해), 다음 진단 로그로 실제 이름 확인. _SET(제어) 권한은 절대 넣지 않음.
            "android.permission.BYDAUTO_SAFETY_BELT_COMMON",
            "android.permission.BYDAUTO_DOOR_LOCK_COMMON",
            "android.permission.BYDAUTO_SETTING_COMMON",
            "android.permission.BYDAUTO_GEARBOX_COMMON",
            "android.permission.BYDAUTO_SPEED_COMMON",
            "android.permission.BYDAUTO_CHARGING_COMMON",
            "android.permission.BYDAUTO_LIGHT_COMMON",
            "android.permission.BYDAUTO_BODYWORK_COMMON",
            // ★공조·바디워크 제어(2026-09-11 씨라7 실차 SecurityException) — GET/SET도 부여(매니페스트 선언 필수, 위 AndroidManifest에 AC_COMMON/GET 추가함).
            "android.permission.BYDAUTO_AC_GET",
            "android.permission.BYDAUTO_AC_SET",
            "android.permission.BYDAUTO_BODYWORK_GET",
            "android.permission.BYDAUTO_BODYWORK_SET",
    };
    // hidden-API 완화(기기 전역). Lcom/ts/=OEM SDK, Ldalvik/system/=dex injection.
    private static final String[] EXEMPTION_TOKENS = { "Lcom/ts/", "Ldalvik/system/" };
    // ★재부팅/시동 후에도 ADB(5555)를 살려두려는 지속 시도 — best-effort. shell 권한/SELinux/ROM에
    //   따라 실패할 수 있고, 실패해도 무해(로그만). persist.adb.tcp.port는 성공 시 재부팅해도 5555 자동 개방.
    private static final String[] PERSIST_SETTINGS = {
            "settings put global adb_enabled 1",
            "settings put global adb_wifi_enabled 1",
            "setprop persist.adb.tcp.port 5555",
    };

    private AdbAccess() {}

    static boolean isPortOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, PORT), 1500);
            return true;
        } catch (Throwable t) { return false; }
    }

    // ★2026-09-19 adb 없이 adbd 되살리기(닭-달걀 해소) — WRITE_SECURE_SETTINGS(pm grant는 재부팅 넘어 영속)로
    //   adb_enabled=1을 앱 프로세스에서 직접 쓴다(dadb 불필요). 씨라7 012772 실측: 재부팅 후 persist.adb.tcp.port(5555)는
    //   남아 있는데 adb_enabled가 0으로 돌아가 adbd 데몬이 안 떠 5555가 닫힘 → 카메라 프레임 0. adb_enabled=1을 켜면
    //   adbd가 기동하며 그 persist 포트로 tcp를 연다. adb_wifi_enabled도 함께 시도(ROM별 무선 경로). 켠 뒤 최대 8초 재프로브.
    //   WRITE_SECURE_SETTINGS 없는 차는 SecurityException(무해)→false.
    static boolean reviveAdbd(Context ctx) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            int en = android.provider.Settings.Global.getInt(cr, "adb_enabled", 0);
            if (en != 1) android.provider.Settings.Global.putInt(cr, "adb_enabled", 1);
            try { android.provider.Settings.Global.putInt(cr, "adb_wifi_enabled", 1); } catch (Throwable ignored) {}
            Log.i(TAG, "adbd revive: adb_enabled " + en + "→1, 5555 재프로브");
            for (int i = 0; i < 8 && !isPortOpen(); i++) try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            return isPortOpen();
        } catch (Throwable t) { Log.w(TAG, "adbd revive 실패(WRITE_SECURE_SETTINGS 없음?): " + t); return false; }
    }

    private static AdbKeyPair keyPair(Context ctx) throws Exception {
        File priv = new File(ctx.getFilesDir(), "adbkey");
        File pub = new File(ctx.getFilesDir(), "adbkey.pub");
        if (priv.exists() && pub.exists()) {
            try { return AdbKeyPair.read(priv, pub); } catch (Throwable ignored) {}
        }
        AdbKeyPair.generate(priv, pub);
        return AdbKeyPair.read(priv, pub);
    }

    // 스레드 타임아웃으로 감싼 비차단 connect. 소켓 타임아웃으로 auth 무한대기(좀비) 방지.
    private static Dadb tryConnect(AdbKeyPair kp, long timeoutMs) {
        final Dadb[] out = { null };
        Thread th = new Thread(() -> {
            Dadb d = null;
            try {
                d = Dadb.create(HOST, PORT, kp, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS);
                AdbShellResponse r = d.shell("echo ok");
                if (r.getExitCode() == 0) { out[0] = d; d = null; }
            } catch (Throwable ignored) {
            } finally {
                if (d != null) try { d.close(); } catch (Throwable ignored) {}
            }
        }, "adb-connect");
        th.start();
        try { th.join(timeoutMs); } catch (InterruptedException ignored) {}
        if (th.isAlive()) th.interrupt();
        return out[0];
    }

    private static String shellOut(Dadb dadb, String cmd) {
        try { return dadb.shell(cmd).getAllOutput().trim(); }
        catch (Throwable t) { return "?"; }
    }

    // ★862 — 지속 연결(트랙패드 uinput 등, 명령이 끝나지 않고 계속 stdin을 읽는 프로세스용).
    //   runShell()/tryConnect()의 소켓타임아웃(2.5s)은 짧은 1회성 명령 전용이라, 드래그 사이 몇 초 idle에도 소켓이 끊긴다.
    //   여기는 socketTimeout=0(무제한)+keepAlive로 열어 MouseInjector가 직접 close할 때까지 산다.
    static Dadb connectPersistent(Context ctx) {
        if (!isPortOpen()) return null;
        try { return Dadb.create(HOST, PORT, keyPair(ctx), CONNECT_TIMEOUT_MS, 0, true); }
        catch (Throwable t) { return null; }
    }

    private static boolean hasAllTokens(String cur) {
        if (cur == null || cur.isEmpty() || cur.equals("null")) return false;
        for (String tok : EXEMPTION_TOKENS) if (!cur.contains(tok)) return false;
        return true;
    }

    // 기존값 보존: 현재 exemption에 우리 토큰만 append(중복 제거).
    private static String mergeExemptions(String cur) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (cur != null && !cur.isEmpty() && !cur.equals("null")) {
            for (String p : cur.replace("'", "").split(",")) {
                String s = p.trim();
                if (!s.isEmpty()) set.add(s);
            }
        }
        for (String tok : EXEMPTION_TOKENS) set.add(tok);
        return String.join(",", set);
    }

    // ★임의 셸 1회 실행(shell uid) — 씨라7 화면OFF 블투 복구(svc bluetooth enable)용. 실패해도 무해. 반환=출력 or null(연결실패).
    //   앱의 BluetoothAdapter.enable()은 씨라7이 화면OFF 중 무시하지만, shell 경로의 svc는 시스템 권한이라 통할 수 있음(검증 대상).
    public static String runShell(Context ctx, String cmd) {
        if (!isPortOpen()) return null;
        Dadb dadb = null;
        try {
            dadb = tryConnect(keyPair(ctx), 2500);
            if (dadb == null) return null;
            return dadb.shell(cmd).getAllOutput().trim();
        } catch (Throwable t) { return null; }
        finally { if (dadb != null) try { dadb.close(); } catch (Throwable ignored) {} }
    }

    // 매 시작 idempotent 적용. 반환: applied(새 exemption 실제 적용됨=재시작 필요) / already / unreachable / unauthorized / error
    static String ensureVehicleApiAccess(Context ctx) {
        if (!isPortOpen()) {
            // ★2026-09-19 adbd 자동복구 — 재부팅 시 adb_enabled가 0으로 돌아가 adbd가 안 뜨는 ROM(씨라7 012772)에서 WRITE_SECURE_SETTINGS로 5555를 스스로 되살린다. 성공하면 계속 진행, 실패(권한 없음/ROM 차이)면 기존 unreachable.
            if (reviveAdbd(ctx)) STATUS = "adbd 자동복구 성공 (adb_enabled=1 → 5555 재개방)";
            else { STATUS = "adbd 5555 닫힘 (무선 ADB OFF? 자동복구 실패)"; return "unreachable"; }
        }
        Dadb dadb = null;
        try {
            dadb = tryConnect(keyPair(ctx), 2500);
            if (dadb == null) { STATUS = "adb 미인증 (허용 대기)"; return "unauthorized"; }
            String pkg = ctx.getPackageName();
            int granted = 0;
            StringBuilder fail = new StringBuilder();   // grant 실패한 권한 짧은이름 — 원격 진단으로 D5 실제 권한명 확인
            for (String perm : BYDAUTO_COMMON) {
                try {
                    AdbShellResponse r = dadb.shell("pm grant " + pkg + " " + perm);
                    if (r.getExitCode() == 0 || r.getAllOutput().toLowerCase().contains("success")) granted++;
                    else { Log.w(TAG, "grant " + perm + ": " + r.getAllOutput().trim());
                           fail.append(perm.substring(perm.indexOf("BYDAUTO_") + 8)).append(" "); }
                } catch (Throwable t) { Log.w(TAG, "grant " + perm + " 실패: " + t); }
            }
            GRANT_FAIL = fail.toString().trim();
            // ★WRITE_SECURE_SETTINGS — 릴레이 a11y 셀프활성(미러 자동폴딩·오토스타트) 및 오류 소멸용.
            //   protectionLevel에 development 플래그 있어 shell(pm grant)로 부여 가능(표준 방법, Tasker 등 동일).
            //   무선 ADB가 열린 헤드유닛(공유 차주 실측 self-grant=applied)에서만 성공, 실패해도 무해(로그만).
            try {
                AdbShellResponse rw = dadb.shell("pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
                Log.i(TAG, "grant WRITE_SECURE_SETTINGS -> exit " + rw.getExitCode() + " " + rw.getAllOutput().trim());
            } catch (Throwable t) { Log.w(TAG, "grant WRITE_SECURE_SETTINGS 실패: " + t); }
            // ★센트리 녹화 저장(외장 볼륨) — 실패해도 센트리가 내부 저장소로 폴백하므로 로그만
            try {
                AdbShellResponse rs = dadb.shell("pm grant " + pkg + " android.permission.WRITE_EXTERNAL_STORAGE");
                Log.i(TAG, "grant WRITE_EXTERNAL_STORAGE -> exit " + rs.getExitCode() + " " + rs.getAllOutput().trim());
            } catch (Throwable t) { Log.w(TAG, "grant WRITE_EXTERNAL_STORAGE 실패: " + t); }
            // ★767 읽기 권한도 — 쓰기만 부여돼(READ granted=false) 있었다(센트리 세션 18:24). 저장소 그룹은 프로세스 시작 때 정해지므로 다음 재시작부터 반영
            try {
                AdbShellResponse rr = dadb.shell("pm grant " + pkg + " android.permission.READ_EXTERNAL_STORAGE");
                Log.i(TAG, "grant READ_EXTERNAL_STORAGE -> exit " + rr.getExitCode() + " " + rr.getAllOutput().trim());
            } catch (Throwable t) { Log.w(TAG, "grant READ_EXTERNAL_STORAGE 실패: " + t); }
            // ★ADB 지속 시도(best-effort) — 재부팅 후에도 5555 자동 개방을 노림. 실패해도 무해.
            for (String cmd : PERSIST_SETTINGS) {
                try { AdbShellResponse r = dadb.shell(cmd); Log.i(TAG, "persist: " + cmd + " -> exit " + r.getExitCode()); }
                catch (Throwable t) { Log.w(TAG, "persist '" + cmd + "' 실패: " + t); }
            }
            String cur = shellOut(dadb, "settings get global hidden_api_blacklist_exemptions");
            if (hasAllTokens(cur)) {
                STATUS = "grant " + granted + "/" + BYDAUTO_COMMON.length + " · exemption 기존유지";
                Log.i(TAG, STATUS);
                return "already";
            }
            try { dadb.shell("settings put global hidden_api_policy 1"); }
            catch (Throwable t) { Log.w(TAG, "policy set 실패: " + t); }
            String merged = mergeExemptions(cur);
            try { dadb.shell("settings put global hidden_api_blacklist_exemptions '" + merged + "'"); }
            catch (Throwable t) { Log.w(TAG, "exemption put 실패: " + t); }
            // ★put 후 재조회 — 실제 적용됐을 때만 applied. put 실패면 error → TripApp 재시작 안 함(무한루프 차단).
            String after = shellOut(dadb, "settings get global hidden_api_blacklist_exemptions");
            boolean ok = hasAllTokens(after);
            STATUS = "grant " + granted + "/" + BYDAUTO_COMMON.length + " · exemption "
                    + (ok ? "적용(재시작필요)" : "적용실패(" + after + ")");
            Log.i(TAG, STATUS);
            return ok ? "applied" : "error";
        } catch (Throwable t) {
            STATUS = "오류: " + t;
            Log.w(TAG, STATUS);
            return "error";
        } finally {
            if (dadb != null) try { dadb.close(); } catch (Throwable ignored) {}
        }
    }

    // ★HUD 오버레이 권한 자가부여 — appops SYSTEM_ALERT_WINDOW allow(+pm grant). dadb shell. best-effort.
    static boolean grantOverlay(Context ctx) {
        if (!isPortOpen()) return false;
        Dadb d = null;
        try {
            d = tryConnect(keyPair(ctx), 2500);
            if (d == null) return false;
            d.shell("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            d.shell("pm grant " + ctx.getPackageName() + " android.permission.SYSTEM_ALERT_WINDOW");
            return true;
        } catch (Throwable t2) { return false; }
        finally { if (d != null) try { d.close(); } catch (Throwable ignored) {} }
    }

    // ★진단용 실시간 스냅샷 — 진단 버튼 누를 때 현재 ADB/exemption/지속상태 전부 회수.
    //   uptime(부팅 후 분)으로 "재부팅 후에도 유지되는지"를 판별(사용자가 설정→재부팅→진단 1회면 확정).
    static String diagSnapshot(Context ctx) {
        long upMin = SystemClock.elapsedRealtime() / 60000;
        if (!isPortOpen()) return "port=closed uptime=" + upMin + "m";
        Dadb d = null;
        try {
            d = tryConnect(keyPair(ctx), 2500);
            if (d == null) return "port=open authed=N uptime=" + upMin + "m";
            String exe = shellOut(d, "settings get global hidden_api_blacklist_exemptions");
            String adbEn = shellOut(d, "settings get global adb_enabled");
            String pPort = shellOut(d, "getprop persist.adb.tcp.port");
            String sPort = shellOut(d, "getprop service.adb.tcp.port");
            // ★씨라이언7 오버레이 -2 원인 추적 — SYSTEM_ALERT_WINDOW appop 실제 상태(canDrawOverlays와 별개일 수 있음)
            String saw = shellOut(d, "appops get " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW");
            return "port=open authed=Y uptime=" + upMin + "m exe=[" + exe + "] adb_en=" + adbEn
                    + " persist_port=" + pPort + " svc_port=" + sPort + " saw_op=[" + saw.replace('\n', ' ') + "]";
        } catch (Throwable t) {
            return "diag err: " + t;
        } finally {
            if (d != null) try { d.close(); } catch (Throwable ignored) {}
        }
    }

    // 최초 인증 흐름(백그라운드 스레드에서 호출). 인증 성공 시 ensure까지 수행.
    interface Progress { void on(String state); }

    static boolean runSetup(Context ctx, Progress cb) {
        if (setupInProgress) { cb.on("설정이 이미 진행 중입니다…"); return false; }
        setupInProgress = true;
        try {
            if (!isPortOpen()) { cb.on("무선 ADB를 켜주세요 (화면 세로 회전 → ADB 토글)"); return false; }
            AdbKeyPair kp = keyPair(ctx);
            Dadb d = tryConnect(kp, 2500);
            if (d == null) {
                cb.on("차량 화면의 'USB 디버깅 허용'을 눌러주세요…");
                for (int i = 0; i < 60 && d == null; i++) {
                    try { Thread.sleep(3000); } catch (InterruptedException e) { return false; }
                    d = tryConnect(kp, 2500);
                    if (d == null) cb.on("허용 대기 중… (" + (i + 1) + "/60)");
                }
                if (d == null) { cb.on("인증 시간초과 — 허용 후 다시 시도하세요"); return false; }
            }
            try { d.close(); } catch (Throwable ignored) {}
            String r = ensureVehicleApiAccess(ctx);
            cb.on("설정 완료: " + STATUS);
            return r.equals("applied") || r.equals("already");
        } catch (Throwable t) {
            cb.on("실패: " + t);
            return false;
        } finally {
            setupInProgress = false;
        }
    }

    // ★(테스트) 전역 immersive — 상단바 숨김/복구. 모든 앱 status bar 사라짐 → 오버레이 최상단서 안 가림.
    //   되돌리기 쉬움(null 복구). policy_control이 이 ROM에서 먹히는지는 실차 검증(그래서 "테스트").
    static boolean setImmersive(Context ctx, boolean on) {
        if (!isPortOpen()) return false;
        Dadb d = null;
        try {
            d = tryConnect(keyPair(ctx), 2500);
            if (d == null) return false;
            d.shell("settings put global policy_control " + (on ? "immersive.status=*" : "null"));
            return true;
        } catch (Throwable t) { return false; }
        finally { if (d != null) try { d.close(); } catch (Throwable ignored) {} }
    }

    // 최초 인증 포함 immersive 토글(백그라운드 호출). 미인증이면 허용 대기 폴링.
    static boolean applyImmersive(Context ctx, boolean on, Progress cb) {
        try {
            if (!isPortOpen()) { cb.on("무선 ADB(USB디버깅)를 먼저 켜주세요"); return false; }
            AdbKeyPair kp = keyPair(ctx);
            Dadb d = tryConnect(kp, 2500);
            if (d == null) {
                cb.on("차량 화면의 'USB 디버깅 허용'을 눌러주세요…");
                for (int i = 0; i < 40 && d == null; i++) {
                    try { Thread.sleep(3000); } catch (InterruptedException e) { return false; }
                    d = tryConnect(kp, 2500);
                    if (d == null) cb.on("허용 대기 중… (" + (i + 1) + "/40)");
                }
                if (d == null) { cb.on("인증 시간초과 — 허용 후 다시 시도하세요"); return false; }
            }
            try {
                d.shell("settings put global policy_control " + (on ? "immersive.status=*" : "null"));
                cb.on(on ? "상단바 숨김 적용됨" : "상단바 복구됨");
                return true;
            } finally { try { d.close(); } catch (Throwable ignored) {} }
        } catch (Throwable t) { cb.on("실패: " + t); return false; }
    }

    // exemption은 프로세스 fork 시 캡처 → 적용 후 앱 재시작해야 bydauto SDK가 바인딩됨.
    static void restartApp(Context ctx) {
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (launch == null) return;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, launch,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
            AlarmManager am = ctx.getSystemService(AlarmManager.class);
            if (am != null) am.set(AlarmManager.RTC, System.currentTimeMillis() + 400L, pi);
        } catch (Throwable ignored) {}
        Runtime.getRuntime().exit(0);
    }
}
