package com.cseini.bydsniffer;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

// 상시생존 앵커 — diplus KDService 방식(별도 프로세스 :relay + 포그라운드 + 접근성 3중).
// 메인(SnifferService) 프로세스가 원격끔 킬로 죽어도 이 프로세스는 분리돼 살아남아 메인을 되살린다.
// ★별도 프로세스라 SnifferService.getInstance()는 항상 null(다른 프로세스 메모리) → 시간 스로틀로 주기 부활.
// 실제 바인딩·생사·부활을 D1로 관측(자체 postToD1, 메인 죽어도 독립 보고).
public class KeepAliveAccessibilityService extends AccessibilityService {
    private static final String TAG = "KeepAliveA11y";
    private static final String LOG_URL = "https://byd.cseini.co.kr/log";
    private static final String LOG_AUTH = BuildConfig.RELAY_SECRET;
    private static final String CH = "keepalive_anchor";
    private static final int FG_ID = 4711;
    private long lastEnsure = 0, lastHb = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "접근성 서비스 연결됨 — 상시생존 앵커(별도프로세스+포그라운드)");
        goForeground();   // 별도 프로세스를 포그라운드로 → 시스템 킬 우선순위 최하위
        postToD1("A11Y", "접근성 바인딩됨(:relay 별도프로세스, 포그라운드) — 상시생존 앵커 확보");
        ensureSnifferRunning("onServiceConnected", true);
        // ★v158 트리거 파일 주기 확인(1.5s) — a11y 이벤트(화면변화)에만 의존하면 정적화면·재바인딩 시 명령을 놓침.
        cmdHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        cmdHandler.postDelayed(cmdPoll, 1500);
        // ★584(운영자 09-25 승인) — 583의 셸 워치독(byd_wd.sh)에만 의존하지 않는 두 번째(자바 in-process) 백업.
        //   접근성 이벤트에 의존하지 않는 자체 타이머로, byd_wd.sh 설치·생존과 무관하게 독립 동작.
        wdHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        wdHandler.postDelayed(wdPoll, WD_INTERVAL_MS);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        long now = System.currentTimeMillis();
        // 20초마다 메인 살리기 시도(별도프로세스라 살아있어도 무해 — START_STICKY 재진입만). 심박은 5분마다.
        // ★662 메인 서비스가 최근 150초 안에 생존 표시(main_alive)를 갱신했으면 기동 요청을 건너뛴다 — 접근성 이벤트마다 대당 하루 약 98번 startForegroundService 를 불러
        //   폰·헤드유닛을 깨우던 것 제거. 표시가 없거나 오래되면(메인이 죽었거나 HB 루프가 멈춤) 종전처럼 20초마다 요청.
        if (now - lastEnsure > 20_000 && !mainAliveRecently(now)) ensureSnifferRunning("a11yEvent", false);
        if (now - lastHb > 300_000) { lastHb = now; postToD1("A11Y", "앵커 생존 심박(접근성 프로세스 살아있음)"); }
        // ★v154 오토스타트 자동화 — 메인 프로세스가 트리거 파일 쓰면 현재 화면을 읽음. dumpsys/uiautomator가 앱uid라 막혀서 a11y가 유일 경로.
        if (now - lastCmdCheck > 1200) { lastCmdCheck = now; checkA11yCmd(); }
        // ★v163 이벤트 캡처 모드 — 벨트/재실 팝업 뜨는 순간의 pkg+텍스트를 로깅(logcat 죽어서 대체). 텍스트 있는 이벤트만, 중복 제외.
        if (capUntil > now) {
            try {
                java.util.List<CharSequence> tl = event.getText();
                CharSequence cd = event.getContentDescription();
                StringBuilder t = new StringBuilder();
                if (tl != null) for (CharSequence c : tl) if (c != null && c.length() > 0) t.append(c).append("|");
                if (cd != null && cd.length() > 0) t.append("[cd]").append(cd);
                String txt = t.toString();
                if (!txt.isEmpty() && !txt.equals(lastCapTxt)) {
                    lastCapTxt = txt;
                    postToD1("A11YEVT", "pkg=" + event.getPackageName() + " type=" + event.getEventType()
                            + " cls=" + event.getClassName() + " txt=" + txt.substring(0, Math.min(txt.length(), 220)));
                }
            } catch (Throwable ignored) {}
        }
    }

    private long capUntil = 0;
    private String lastCapTxt = "";

    private long lastCmdCheck = 0, lastCmdTs = 0;
    private android.os.Handler cmdHandler;
    // ★v158 주기 폴링 — a11y 이벤트 없이도 트리거 파일 확인(정적화면·재바인딩 대응).
    private final Runnable cmdPoll = new Runnable() {
        @Override public void run() {
            // ★liveness 심박 — 릴레이(메인)가 이 파일의 타임스탬프로 a11y 생존 확인(애프터블로우 시퀀스 게이트). setLastModified는 no-op 가능 → 실제 바이트 기록.
            try { java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "a11y_alive")); fo.write(String.valueOf(System.currentTimeMillis()).getBytes("UTF-8")); fo.close(); } catch (Throwable ignored) {}
            try { checkA11yCmd(); } catch (Throwable ignored) {}
            if (cmdHandler != null) cmdHandler.postDelayed(this, 1500);
        }
    };

    // ★584(운영자 09-25 승인) 자바 in-process 이중백업 워치독 — 583의 byd_wd.sh(pidof+heartbeat나이 체크
    //   →am force-stop+재시작)와 동일 로직을 :relay 프로세스 자체 타이머로 재구현. 셸 스크립트 설치·생존에
    //   의존하지 않는 두 번째 경로(온디바이스 dadb self-connect, AdbAccess.runShell과 동일 패턴).
    //   무선ADB 미인증/차단 상태면 AdbAccess.runShell이 null을 돌려주므로 조용히 스킵(byd_wd.sh 경로에 전적 의존, 오탐 없음).
    private static final long WD_INTERVAL_MS = 60_000;   // 1분 주기 — byd_wd.sh(1초)보다 완화(dadb 왕복비용 있음), HB 갱신주기(60s)와 정렬
    private static final long WD_STALE_SEC = 240;        // 583과 동일 임계
    private android.os.Handler wdHandler;
    private final Runnable wdPoll = new Runnable() {
        @Override public void run() {
            new Thread(() -> { try { checkWatchdog(); } catch (Throwable ignored) {} }, "wd2").start();
            if (wdHandler != null) wdHandler.postDelayed(this, WD_INTERVAL_MS);
        }
    };
    private void checkWatchdog() {
        String pkg = getPackageName();
        String pidof = AdbAccess.runShell(this, "pidof " + pkg);
        if (pidof == null) return;   // dadb 미연결 — 셸 워치독 경로에 맡김
        String reason = null;
        if (pidof.trim().isEmpty()) {
            reason = "프로세스없음";
        } else {
            String hbRaw = AdbAccess.runShell(this, "cat /data/local/tmp/byd_hb 2>/dev/null");
            if (hbRaw != null && hbRaw.trim().matches("\\d+")) {
                long age = System.currentTimeMillis() / 1000 - Long.parseLong(hbRaw.trim());
                if (age > WD_STALE_SEC) reason = "심박멈춤(" + age + "s)";
            }
        }
        if (reason == null) return;
        postToD1("WATCHDOG2", "★자바 백업워치독 발동 — " + reason + " → force-stop+재시작");
        AdbAccess.runShell(this, "am force-stop " + pkg);
        AdbAccess.runShell(this, "rm -f /data/local/tmp/byd_hb");
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        String r = AdbAccess.runShell(this, "am start -n " + pkg + "/.MainActivity --es src watchdog2 -f 0x10000000");
        postToD1("WATCHDOG2", "재시작 결과: " + r);
    }

    // 메인(SnifferService)이 getFilesDir()/a11y_trigger에 "dump <ts>" 기록 → 별도프로세스인 이 서비스가 감지해 실행.
    private void checkA11yCmd() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "a11y_trigger");
            if (!f.exists()) return;
            byte[] b = new byte[512];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b); in.close();
            if (n <= 0) return;
            String[] p = new String(b, 0, n, "UTF-8").trim().split("\\s+");
            if (p.length < 2) return;
            long ts = Long.parseLong(p[1]);
            if (ts <= lastCmdTs) return;
            lastCmdTs = ts;
            try { f.delete(); } catch (Throwable ignored) {}   // ★v160 실행 후 트리거 삭제 — 재바인딩마다 옛 명령 재실행(앱 깜빡임) 방지. 원샷 보장.
            // ★stale 폐기 — 미러폴드/오토스타트는 UI를 뺏는 명령이라 시점 의존적. 재바인딩 지연소비(수분 후)로 주행 중 엉뚱하게 실행되면 위험 → 60초↑ 오래된 트리거 무시.
            if (("mirrorfold".equals(p[0]) || "autostartfix".equals(p[0])) && System.currentTimeMillis() - ts > 60000) { postToD1("A11Y", "★stale 트리거 폐기 " + p[0] + " (" + ((System.currentTimeMillis() - ts) / 1000) + "s 지연) — 무시"); return; }
            if ("dump".equals(p[0])) dumpTree();
            else if ("autostartfix".equals(p[0])) { final String tgt = (p.length > 2) ? p[2] : null; final boolean ui = p.length > 3 && "ui".equals(p[3]); postToD1("AUTOSTART", "★디스패치 대상=" + tgt + " ui=" + ui + " (트리거 수신)"); new Thread(() -> autostartFixAll("trigger", tgt, ui), "autostartfix").start(); }
            else if ("cap".equals(p[0])) { capUntil = System.currentTimeMillis() + 90000; lastCapTxt = ""; postToD1("A11YEVT", "★캡처모드 90초 시작 — 팝업 띄우세요"); }
            else if ("mirrorfold".equals(p[0])) { int d = -1; try { if (p.length > 2) d = Integer.parseInt(p[2]); } catch (Throwable ig) {} final int des = d; postToD1("MIRRORFOLD", "★디스패치 des=" + des + " (트리거 수신)"); new Thread(() -> mirrorFold(des), "mirrorfold").start(); }
            else if ("screenwake".equals(p[0])) new Thread(this::wakeScreen, "screenwake").start();   // ★v179 주차 화면 깨우기 실험(일렉트로가 됨 = 가능)
        } catch (Throwable t) { postToD1("A11YDUMP", "cmd 체크 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage()); }
    }

    // ★v163 사이드미러 자동폴딩 수동 토글 — SDK는 가짜OK라 불가 → a11y로 차량설정 UI 조작.
    //   경로(실차덤프): com.byd.carsettings 실행 → "차량" → "운전 컨트롤" 탭 → "자동 폴딩" 스위치 클릭(뒤집기).
    // ★v179 주차 중 화면 꺼진 상태에서 화면 깨우기 — a11y가 설정 UI를 조작하려면 화면이 켜져야 함.
    //   일렉트로가 주차 중 카메라 감지 시 화면에 그림/문구를 띄우는 게 증거(가능). 방법 2종 시도.
    private String wakeScreen() {
        StringBuilder sb = new StringBuilder();
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            sb.append("isInteractive=").append(pm.isInteractive());
            // ★v209 주차 중 화면 켜기 = Activity 윈도우 플래그(FLAG_TURN_SCREEN_ON) — 권한 불필요, 완전 꺼진 화면도 켬.
            //   PowerManager 방식(아래 ①②)은 DEVICE_POWER 권한 거부·완전꺼짐엔 무력이라 이게 주 경로.
            if (!pm.isInteractive()) {
                try {
                    android.content.Intent wi = new android.content.Intent(this, WakeActivity.class);
                    wi.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION);
                    startActivity(wi);
                    sb.append(" | WakeActivity=기동");
                    Thread.sleep(700);   // 화면 켜짐 대기
                    sb.append(" | after1 isInteractive=").append(pm.isInteractive());
                } catch (Throwable t) { sb.append(" | WakeActivity예외:").append(t.getClass().getSimpleName()); }
            }
            // ① DiLink 확장 setBacklightMode(1) — CarSetting 디컴파일서 확인된 헤드유닛 화면 온/오프 API
            try { pm.getClass().getMethod("setBacklightMode", int.class).invoke(pm, 1); sb.append(" | setBacklightMode(1)=OK"); }
            catch (Throwable t) { Throwable c = t.getCause() != null ? t.getCause() : t; sb.append(" | setBacklightMode예외:").append(c.getClass().getSimpleName()).append(":").append(c.getMessage()); }
            // ② 레거시 wakelock(ACQUIRE_CAUSES_WAKEUP) — 화면 강제 온. API29서 deprecated지만 DiLink서 동작 가능.
            try {
                android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP | android.os.PowerManager.ON_AFTER_RELEASE, "byd:screenwake");
                wl.acquire(20000); sb.append(" | wakelock=OK(20s)");
            } catch (Throwable t) { sb.append(" | wakelock예외:").append(t.getClass().getSimpleName()).append(":").append(t.getMessage()); }
            try { Thread.sleep(600); sb.append(" | after isInteractive=").append(pm.isInteractive()); } catch (Throwable ignored) {}
        } catch (Throwable t) { sb.append(" | 예외:").append(t.getClass().getSimpleName()).append(":").append(t.getMessage()); }
        postToD1("SCREENWAKE", sb.toString());
        return sb.toString();
    }

    // ★v206 결정론적 — desired: 0=OFF 1=ON -1=플립. 화면 깨우고, 현재 상태 읽어 원하는 값과 다를 때만 탭.
    private void mirrorFold(int desired) {
        try {
            postToD1("MIRRORFOLD", "①mirrorFold 진입 desired=" + desired);
            // ★v207 원래 앱 기억(작업 후 복귀용) — 차 설정 열기 전 포그라운드 패키지.
            android.view.accessibility.AccessibilityNodeInfo r0 = getRootInActiveWindow();
            String origPkg = (r0 != null && r0.getPackageName() != null) ? r0.getPackageName().toString() : null;
            postToD1("MIRRORFOLD", "②현재 포그라운드 pkg=" + origPkg);
            wakeScreen();   // 화면 꺼져있으면 먼저 깨움(주차 중에도 되게)
            Thread.sleep(450);   // ★v207 반응속도 2배 — 화면 켜짐 안정 대기 단축
            // 이미 자동폴딩 화면이면 바로 시도(복귀 불필요). r: 1=성공 0=화면엔있으나실패 -1=화면아님(네비필요)
            int r = setAutoFold(desired);
            if (r == 1) { postToD1("MIRRORFOLD", "③즉시성공(이미 화면)"); returnToApp(origPkg); return; }
            if (r == 0) { postToD1("MIRRORFOLD", "③화면엔 있으나 토글 실패 — 중단(네비 무의미)"); returnToApp(origPkg); return; }
            // ★HOME 선행 — 타앱(일렉트로/tmap 등)이 포그라운드면 백그라운드 액티비티 시작 제한으로 직행 인텐트가 안 뜰 수 있음.
            try { performGlobalAction(GLOBAL_ACTION_HOME); Thread.sleep(600); postToD1("MIRRORFOLD", "③-b HOME 강제(타앱 대비)"); } catch (Throwable ig) {}
            // ★v213 컴포트운전(자동폴딩) 화면 직행 — CarSetting 디컴파일 확정: action byd.intent.action.acsettings
            //   + extra FUNCTION_ID=00800300000000(ACTION_2ST_DRIVING_COMFORTABLE). 메뉴 클릭 네비 헤맴 제거.
            try {
                android.content.Intent di = new android.content.Intent("byd.intent.action.acsettings");
                di.setClassName("com.byd.carsettings", "com.byd.carsettings.MainActivity");
                di.putExtra("FUNCTION_ID", "00800300000000");
                di.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(di);
                postToD1("MIRRORFOLD", "④직행 인텐트(FUNCTION_ID=00800300000000) 발사");
            } catch (Throwable t) { postToD1("MIRRORFOLD", "④직행 인텐트 예외:" + t.getClass().getSimpleName()); }
            // ★검증-재시도 루프 — 고정 sleep 후 즉시 읽어 -1 오판하던 것 대신, carsettings+'자동 폴딩' 노드가 실제 렌더될 때까지 폴링.
            boolean onScreen = waitForFoldScreen(8);   // 8×500ms=4s
            postToD1("MIRRORFOLD", "④-b 자동폴딩화면 도달=" + onScreen);
            r = onScreen ? setAutoFold(desired) : -1;
            if (r == 1) { postToD1("MIRRORFOLD", "⑤직행 성공"); }
            else {
                // 폴백: 기존 메뉴 클릭 네비(직행 실패 시)
                android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
                boolean inSettings = root != null && root.getPackageName() != null && "com.byd.carsettings".contentEquals(root.getPackageName());
                if (!inSettings) {
                    android.content.Intent i = getPackageManager().getLaunchIntentForPackage("com.byd.carsettings");
                    if (i != null) { i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); Thread.sleep(1000); }
                }
                boolean c1 = clickByText("차량"); Thread.sleep(550);
                boolean c2 = clickByText("컴포트 운전"); Thread.sleep(850);
                postToD1("MIRRORFOLD", "⑤폴백 클릭네비 차량=" + c1 + " 컴포트운전=" + c2);
                boolean onScreen2 = waitForFoldScreen(6);
                r = onScreen2 ? setAutoFold(desired) : -1;
                if (r == 1) postToD1("MIRRORFOLD", "⑥폴백 네비 후 성공");
                else postToD1("MIRRORFOLD", "⑥실패 — " + (r == 0 ? "화면엔 있으나 토글 실패" : "자동폴딩 화면 못 도달"));
            }
            returnToApp(origPkg);
        } catch (Throwable t) { postToD1("MIRRORFOLD", "실패: " + t.getClass().getSimpleName() + ":" + t.getMessage()); }
    }

    // carsettings + '자동 폴딩' 노드가 실제 렌더될 때까지 폴링(직행/폴백 네비 후 타이밍 방어).
    private boolean waitForFoldScreen(int tries) {
        for (int t = 0; t < tries; t++) {
            try {
                android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && root.getPackageName() != null && "com.byd.carsettings".contentEquals(root.getPackageName())) {
                    java.util.List<android.view.accessibility.AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText("자동 폴딩");
                    if (l != null && !l.isEmpty()) return true;
                }
                Thread.sleep(500);
            } catch (Throwable ignored) {}
        }
        return false;
    }

    // 작업 후 원래 앱으로 복귀(차 설정/런처가 아니었으면). 미러·오토스타트 공용.
    private void returnToApp(String origPkg) {
        try {
            Thread.sleep(400);
            if (origPkg != null && !"com.byd.carsettings".equals(origPkg)
                    && !"com.byd.appstartmanagement".equals(origPkg) && !origPkg.contains("launcher")) {
                android.content.Intent bi = getPackageManager().getLaunchIntentForPackage(origPkg);
                if (bi != null) { bi.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(bi); return; }
            }
            // ★돌아갈 앱 없음(오토스타트/런처/null) → 릴레이 설정화면으로(업데이트 직후 오토스타트 화면 갇힘 방지)
            android.content.Intent me = getPackageManager().getLaunchIntentForPackage("com.cseini.bydsniffer");
            if (me != null) { me.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(me); }
            else performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Throwable ignored) {}
    }

    // 현재 화면 '자동 폴딩' 스위치의 상태 재취득(1=ON/0=OFF/-1=화면아님).
    private int readFoldState() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return -1;
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText("자동 폴딩");
            if (l == null || l.isEmpty()) return -1;
            android.graphics.Rect rc = new android.graphics.Rect(); l.get(0).getBoundsInScreen(rc);
            android.view.accessibility.AccessibilityNodeInfo sw = findSwitchNearY(root, rc.centerY());
            if (sw == null) return -1;
            return sw.isChecked() ? 1 : 0;
        } catch (Throwable t) { return -1; }
    }

    private void gestureTap(int x, int y) {
        try {
            android.graphics.Path p = new android.graphics.Path(); p.moveTo(x, y);
            android.accessibilityservice.GestureDescription.Builder b = new android.accessibilityservice.GestureDescription.Builder();
            b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 120));
            dispatchGesture(b.build(), null, null);
        } catch (Throwable ignored) {}
    }

    // ★v206 '자동 폴딩' 결정론적 세팅 — desired: 0=OFF 1=ON -1=플립.
    //   반환 3상태: 1=성공(목표도달/이미목표), 0=화면엔 있으나 토글 실패(네비 무의미), -1=자동폴딩 화면 아님(네비 필요).
    //   ★버그수정: 예전엔 스위치 못찾음·예외에서도 true를 반환해 폴백 네비를 스킵하고 "성공"으로 오기록 + 상태 미러링 누락.
    private int setAutoFold(int desired) {
        try {
            int start = readFoldState();
            if (start < 0) return -1;   // 자동폴딩 화면 아님 → 네비게이션 필요
            int target = (desired < 0) ? (start == 1 ? 0 : 1) : desired;   // -1이면 반대(플립), 아니면 지정값
            if (start == target) { writeMirrorState(start); postToD1("MIRRORFOLD", "이미 " + (start == 1 ? "ON" : "OFF") + " (탭 불필요)"); return 1; }
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> labels = root.findAccessibilityNodeInfosByText("자동 폴딩");
            if (labels == null || labels.isEmpty()) { writeMirrorState(readFoldState()); return -1; }   // 화면 사라짐 → 네비 재시도
            android.graphics.Rect lr = new android.graphics.Rect(); labels.get(0).getBoundsInScreen(lr);
            android.view.accessibility.AccessibilityNodeInfo sw = findSwitchNearY(root, lr.centerY());
            if (sw == null) { postToD1("MIRRORFOLD", "스위치 못 찾음(Y=" + lr.centerY() + ") — 도달했으나 실패"); writeMirrorState(readFoldState()); return 0; }
            android.graphics.Rect sr = new android.graphics.Rect(); sw.getBoundsInScreen(sr);
            postToD1("MIRRORFOLD", "스위치 @" + sr.centerX() + "," + sr.centerY() + " " + (start == 1 ? "ON" : "OFF") + "→목표" + (target == 1 ? "ON" : "OFF"));
            int now = start;
            for (int a = 0; a < 3 && now != target; a++) {
                gestureTap(sr.centerX(), sr.centerY());   // 실터치 제스처(120ms). 목표 도달까지 최대 3회.
                Thread.sleep(500);   // ★v207 반응속도 2배
                now = readFoldState();
            }
            writeMirrorState(now);   // ★성공·실패 무관 실제 상태 미러링(앱 토글 동기)
            boolean ok = (now == target);
            postToD1("MIRRORFOLD", "자동폴딩 결과 " + (now == 1 ? "ON" : now == 0 ? "OFF" : "?") + (ok ? " ✓" : " ✗목표미달"));
            return ok ? 1 : 0;
        } catch (Throwable t) { postToD1("MIRRORFOLD", "세팅 실패: " + t.getMessage()); return 0; }   // 예외=화면 진입 후 실패(네비 재시도 무의미)
    }

    // 자동폴딩 상태를 파일로 — 메인(SnifferService)이 읽어 relay 상태 mirror_fold로 push(앱 토글 동기).
    private void writeMirrorState(int state) {
        if (state < 0) return;
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "mirror_fold"));
            fo.write(String.valueOf(state).getBytes("UTF-8")); fo.close();
        } catch (Throwable ignored) {}
    }

    // 텍스트로 노드 찾아 클릭(클릭가능 조상 또는 좌표탭). 없으면 스킵(이미 그 화면일 수 있음).
    private boolean clickByText(String text) {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText(text);
            if (l == null || l.isEmpty()) return false;
            android.view.accessibility.AccessibilityNodeInfo n = l.get(0), c = n; int g = 0;
            while (c != null && !c.isClickable() && g++ < 6) c = c.getParent();
            if (c != null && c.isClickable()) c.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            else { android.graphics.Rect r = new android.graphics.Rect(); n.getBoundsInScreen(r); tapAt(r.centerX(), r.centerY()); }
            return true;
        } catch (Throwable ignored) { return false; }
    }

    // ★v248 오토스타트금지 앱(com.byd.appstartmanagement)에서 대상 앱 토글을 전부 OFF로(자동시작 허용) — 가디언 앱을 릴레이로 흡수.
    //   가디언 doUnprohibitAll의 검증된 로직 이식: 다대상(PM 라벨) + 목록 스크롤 + 미로딩 재시도 + 작업 후 원래 앱 복귀.
    //   ★스레드에서 호출(수십 초 블로킹) — a11y 메인 Looper를 막으면 a11y_alive 심박이 끊겨 좀비 오판/강제킬 위험.
    private static final String BLOCKER_PKG = "com.byd.appstartmanagement";
    private static final String[] AUTOSTART_TARGETS = { "com.cseini.bydsniffer", "com.cseini.bydautotrip" };   // payload 없을 때 폴백(구 트리거 호환)
    private volatile boolean autostartRunning = false;

    // targetsCsv = 릴레이 메인이 payload로 준 대상 패키지 CSV(사용자 선택). null/빈값이면 폴백 상수.
    private void autostartFixAll(String reason, String targetsCsv, boolean showUi) {
        if (autostartRunning) { postToD1("AUTOSTART", "이미 실행 중 — 무시(" + reason + ")"); return; }   // 진행 중 인스턴스가 UI 소유
        autostartRunning = true;
        boolean ok = false;
        if (showUi) uiShowProgress();   // ★575 진행률 오버레이 + 30초 워치독(운영자 지시)
        String origPkg = null;
        try {
            String[] pkgs = (targetsCsv != null && !targetsCsv.trim().isEmpty()) ? targetsCsv.trim().split(",") : AUTOSTART_TARGETS;
            java.util.Set<String> remaining = new java.util.HashSet<>();
            for (String pk : pkgs) { pk = pk.trim(); if (pk.isEmpty()) continue; String l = appLabel(pk); if (l != null) remaining.add(l); }
            if (remaining.isEmpty()) { postToD1("AUTOSTART", "대상 라벨 없음(payload=" + targetsCsv + ")"); return; }
            android.view.accessibility.AccessibilityNodeInfo r0 = getRootInActiveWindow();
            origPkg = (r0 != null && r0.getPackageName() != null) ? r0.getPackageName().toString() : null;
            postToD1("AUTOSTART", "시작(" + reason + ") 대상=" + remaining + " pkg=" + origPkg);
            wakeScreen();   // 주차 중 화면 꺼졌으면 깨움
            Thread.sleep(600);
            if (getRootInActiveWindow() == null) {
                long w0 = System.currentTimeMillis();
                while (getRootInActiveWindow() == null && System.currentTimeMillis() - w0 < 120000) Thread.sleep(1000);
                android.view.accessibility.AccessibilityNodeInfo rr = getRootInActiveWindow();
                postToD1("AUTOSTART", "화면 읽기 준비 대기 " + (System.currentTimeMillis() - w0) / 1000 + "초 → " + (rr != null ? "준비됨" : "끝내 미준비(계속 진행)"));
                if (origPkg == null && rr != null && rr.getPackageName() != null) origPkg = rr.getPackageName().toString();
            }
            android.content.Intent i = getPackageManager().getLaunchIntentForPackage(BLOCKER_PKG);
            if (i == null) { postToD1("AUTOSTART", "오토스타트금지 앱 런치인텐트 null"); return; }
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Thread.sleep(2200);   // 목록(RecyclerView) 로딩 여유
            int done = 0, notReady = 0;
            for (int s = 0; s < 18 && !remaining.isEmpty(); s++) {
                android.view.accessibility.AccessibilityNodeInfo root = activeRoot(BLOCKER_PKG, 8);
                if (root == null) {
                    if (++notReady <= 5) { postToD1("AUTOSTART", "화면 대기 재시도(" + notReady + ")"); Thread.sleep(1200); continue; }
                    postToD1("AUTOSTART", "오토스타트금지 화면 못 읽음(s=" + s + ")"); break;
                }
                int handled = processAutostartScreen(root, remaining);
                done += handled;
                if (remaining.isEmpty()) break;
                android.view.accessibility.AccessibilityNodeInfo scrollable = findScrollable(root);
                boolean scrolled = scrollable != null && scrollable.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                if (!scrolled) {
                    if (handled == 0 && scrollable == null && ++notReady <= 5) { postToD1("AUTOSTART", "목록 미로딩 추정 → 대기 후 재시도(" + notReady + ")"); Thread.sleep(1500); continue; }
                    postToD1("AUTOSTART", "리스트 끝(더 스크롤 불가)"); break;
                }
                notReady = 0; Thread.sleep(800);
            }
            ok = remaining.isEmpty() || done > 0;   // ★성공=대상 전부 처리됐거나 최소 1개 OFF전환
            postToD1("AUTOSTART", "완료 — 처리(OFF전환/이미OFF)=" + done + " 미발견=" + remaining + (showUi ? " ok=" + ok : ""));
            if (ok) new Thread(() -> { String r = SnifferService.wakeAutotrip(getApplicationContext(), "오토스타트 해제 뒤"); if (r != null) postToD1("AUTOTRIPWAKE", r); }, "autotrip-wake").start();   // ★723 금지 풀린 뒤 오토전비 깨우기
        } catch (Throwable t) { postToD1("AUTOSTART", "실패: " + t.getClass().getSimpleName() + ":" + t.getMessage()); }
        finally { returnToApp(origPkg); autostartRunning = false; if (showUi) uiFinish(ok); }   // ★예외·중단이어도 항상 원래 화면으로 복귀 + 진행률 종료(성공 토스트/실패 폴백)
    }

    // ★★575 오토스타트금지 해제 진행률 UI(운영자 지시 2026-09-24) — 사용자가 "설정 중"을 보고 대기하게.
    //   모두 메인 스레드(WindowManager 요구). 무한로딩 금지: 30초 워치독이 반드시 종료시킨다. 성공=토스트, 실패/타임아웃=재시도 카드.
    private android.os.Handler uiMain;
    private android.view.View uiOverlay;      // 진행중/폴백 오버레이(하나만 유지)
    private volatile boolean uiFinished;
    private final Runnable uiTimeoutR = () -> uiFinish(false);   // 30초 초과 = 실패 취급
    private android.os.Handler uiMain() { if (uiMain == null) uiMain = new android.os.Handler(android.os.Looper.getMainLooper()); return uiMain; }
    private android.view.WindowManager.LayoutParams overlayParams(boolean touchable) {
        int type = (android.os.Build.VERSION.SDK_INT >= 26)
                ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        int flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (!touchable) flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;   // 진행중 오버레이는 a11y/터치 통과(클릭 방해 금지)
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                type, flags, android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.CENTER;
        return lp;
    }
    private android.widget.TextView card(String text) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(text); tv.setTextColor(0xFFFFFFFF); tv.setTextSize(18);
        int pad = (int) (22 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xE6101418); bg.setCornerRadius(24 * getResources().getDisplayMetrics().density);
        tv.setBackground(bg); tv.setGravity(android.view.Gravity.CENTER);
        return tv;
    }
    private void removeOverlayNow() {
        try { if (uiOverlay != null) { ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).removeView(uiOverlay); } } catch (Throwable ignored) {}
        uiOverlay = null;
    }
    private void uiShowProgress() {
        uiMain().post(() -> {
            try {
                uiFinished = false;
                removeOverlayNow();
                uiOverlay = card("🔧 오토스타트금지 자동 설정 중…\n잠시만 기다려 주세요");
                ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).addView(uiOverlay, overlayParams(false));
                uiMain().removeCallbacks(uiTimeoutR);
                uiMain().postDelayed(uiTimeoutR, 30000);   // ★30초 워치독 — 무한로딩 방지(운영자 지시)
            } catch (Throwable t) { postToD1("AUTOSTART", "오버레이 표시 실패: " + t.getClass().getSimpleName()); uiOverlay = null; }
        });
    }
    private void uiFinish(final boolean ok) {
        uiMain().post(() -> {
            if (uiFinished && !ok) return;
            uiFinished = true;
            uiMain().removeCallbacks(uiTimeoutR);
            try {
                if (ok) {
                    removeOverlayNow();
                    android.widget.Toast.makeText(getApplicationContext(), "오토스타트금지 해제 완료", android.widget.Toast.LENGTH_LONG).show();
                } else {
                    showFallbackCard();   // 실패/타임아웃 → 수동 안내 + 재시도
                }
            } catch (Throwable t) { postToD1("AUTOSTART", "진행률 종료 예외: " + t.getClass().getSimpleName()); removeOverlayNow(); }
        });
    }
    @SuppressWarnings("SetTextI18n")
    private void showFallbackCard() {
        try {
            removeOverlayNow();
            android.widget.LinearLayout box = new android.widget.LinearLayout(this);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (22 * getResources().getDisplayMetrics().density);
            box.setPadding(pad, pad, pad, pad);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xF2101418); bg.setCornerRadius(24 * getResources().getDisplayMetrics().density);
            box.setBackground(bg);
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText("오토스타트금지 자동 설정을 마치지 못했습니다.\n설정 → 오토스타트금지에서 ‘링크락’(및 대상 앱)을 직접 켜 주세요.");
            tv.setTextColor(0xFFFFFFFF); tv.setTextSize(16); tv.setGravity(android.view.Gravity.CENTER);
            box.addView(tv);
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setPadding(0, pad, 0, 0); row.setGravity(android.view.Gravity.CENTER);
            android.widget.Button retry = new android.widget.Button(this); retry.setText("재시도");
            android.widget.Button close = new android.widget.Button(this); close.setText("닫기");
            retry.setOnClickListener(v -> { removeOverlayNow(); new Thread(() -> autostartFixAll("재시도", null, true), "autostartfix").start(); });
            close.setOnClickListener(v -> removeOverlayNow());
            row.addView(retry); row.addView(close);
            box.addView(row);
            uiOverlay = box;
            ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).addView(uiOverlay, overlayParams(true));   // 폴백은 터치 가능(버튼)
            uiMain().postDelayed(() -> { if (uiOverlay == box) removeOverlayNow(); }, 30000);   // 폴백도 30초 뒤 자동 닫힘(방치 방지)
        } catch (Throwable t) { postToD1("AUTOSTART", "폴백 카드 예외: " + t.getClass().getSimpleName()); removeOverlayNow(); }
    }

    // 현재 화면에서 remaining 라벨을 찾아 Switch가 ON이면 OFF로. 처리한 라벨은 remaining에서 제거. 처리 수 반환.
    private int processAutostartScreen(android.view.accessibility.AccessibilityNodeInfo root, java.util.Set<String> remaining) {
        int handled = 0;
        for (String label : new java.util.ArrayList<>(remaining)) {
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(label);
            if (found == null || found.isEmpty()) continue;
            android.graphics.Rect rc = new android.graphics.Rect(); found.get(0).getBoundsInScreen(rc);
            android.view.accessibility.AccessibilityNodeInfo sw = findSwitchNearY(root, rc.centerY());
            if (sw == null) continue;
            if (sw.isChecked()) { clickToggleNode(sw); postToD1("AUTOSTART", label + " ON→OFF @Y" + rc.centerY()); }
            else postToD1("AUTOSTART", label + " 이미 OFF");
            remaining.remove(label); handled++;
        }
        return handled;
    }

    // Switch는 CHECKABLE이나 CLICK 불가 → 클릭가능 조상 ACTION_CLICK, 실패 시 좌표 제스처 탭.
    private void clickToggleNode(android.view.accessibility.AccessibilityNodeInfo sw) {
        android.view.accessibility.AccessibilityNodeInfo click = sw; int guard = 0;
        while (click != null && !click.isClickable() && guard++ < 6) click = click.getParent();
        if (click != null && click.isClickable() && click.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return;
        android.graphics.Rect r = new android.graphics.Rect(); sw.getBoundsInScreen(r); tapAt(r.centerX(), r.centerY());
    }

    // 오토스타트금지 목록에 뜨는 라벨 = 앱 applicationLabel. 미설치면 null.
    private String appLabel(String pkg) {
        try { android.content.pm.PackageManager pm = getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Throwable t) { return null; }
    }

    // 활성창이 지정 pkg가 될 때까지 재시도해 root 반환.
    private android.view.accessibility.AccessibilityNodeInfo activeRoot(String pkg, int tries) throws InterruptedException {
        for (int t = 0; t < tries; t++) {
            android.view.accessibility.AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null && pkg.contentEquals(r.getPackageName())) return r;
            Thread.sleep(500);
        }
        return null;
    }

    // 첫 스크롤 가능 노드.
    private android.view.accessibility.AccessibilityNodeInfo findScrollable(android.view.accessibility.AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isScrollable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo r = findScrollable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private android.view.accessibility.AccessibilityNodeInfo findSwitchNearY(android.view.accessibility.AccessibilityNodeInfo n, int targetY) {
        if (n == null) return null;
        CharSequence cls = n.getClassName();
        if (n.isCheckable() && cls != null && cls.toString().contains("Switch")) {
            android.graphics.Rect rc = new android.graphics.Rect(); n.getBoundsInScreen(rc);
            if (Math.abs(rc.centerY() - targetY) <= 40) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo r = findSwitchNearY(n.getChild(i), targetY);
            if (r != null) return r;
        }
        return null;
    }

    // 좌표 탭(제스처) — 노드 ACTION_CLICK이 안 먹는 커스텀 토글용. SDK24+. ★v167 콜백 진단(완료/취소).
    private void tapAt(int x, int y) {
        try {
            android.graphics.Path p = new android.graphics.Path(); p.moveTo(x, y);
            android.accessibilityservice.GestureDescription.Builder b = new android.accessibilityservice.GestureDescription.Builder();
            b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 120));   // 120ms 탭
            boolean disp = dispatchGesture(b.build(), new android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(android.accessibilityservice.GestureDescription g) { postToD1("MIRRORFOLD", "제스처 onCompleted @" + x + "," + y); }
                @Override public void onCancelled(android.accessibilityservice.GestureDescription g) { postToD1("MIRRORFOLD", "제스처 onCancelled(권한/차단) @" + x + "," + y); }
            }, null);
            postToD1("MIRRORFOLD", "dispatchGesture 반환=" + disp + " @" + x + "," + y);
        } catch (Throwable t) { postToD1("MIRRORFOLD", "제스처 예외: " + t.getClass().getSimpleName() + ":" + t.getMessage()); }
    }

    // 현재 활성창의 노드 트리를 D1로 — 텍스트/체크상태/클릭가능/id/bounds. 오토스타트 앱 구조 파악용.
    private void dumpTree() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { postToD1("A11YDUMP", "root null(활성창 못 읽음 — 앱을 포그라운드로)"); return; }
            postToD1("A11YDUMP", "=== 덤프시작 pkg=" + root.getPackageName() + " ===");
            StringBuilder sb = new StringBuilder();
            int[] c = {0};
            traverse(root, 0, sb, c);
            if (sb.length() > 0) postToD1("A11YDUMP", sb.toString());
            postToD1("A11YDUMP", "=== 덤프끝 노드수=" + c[0] + " ===");
        } catch (Throwable t) { postToD1("A11YDUMP", "덤프실패: " + t.getClass().getSimpleName() + ":" + t.getMessage()); }
    }

    private void traverse(android.view.accessibility.AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] c) {
        if (n == null) return;
        c[0]++;
        CharSequence txt = n.getText(), desc = n.getContentDescription(), cls = n.getClassName();
        String vid = n.getViewIdResourceName();
        boolean hasTxt = txt != null && txt.length() > 0, hasDesc = desc != null && desc.length() > 0;
        String cs = cls == null ? "" : cls.toString();
        boolean toggleish = cs.contains("Switch") || cs.contains("CheckBox") || cs.contains("Toggle") || n.isCheckable();
        if (hasTxt || hasDesc || toggleish) {
            android.graphics.Rect rc = new android.graphics.Rect(); n.getBoundsInScreen(rc);
            String cn = cs.isEmpty() ? "?" : cs.substring(cs.lastIndexOf('.') + 1);
            sb.append("[").append(depth).append("]").append(cn)
              .append(hasTxt ? " t='" + txt + "'" : "")
              .append(hasDesc ? " d='" + desc + "'" : "")
              .append(n.isCheckable() ? " CHECKABLE checked=" + n.isChecked() : "")
              .append(n.isClickable() ? " CLICK" : "")
              .append(vid != null ? " id=" + vid.substring(vid.indexOf('/') + 1) : "")
              .append(" @").append(rc.centerX()).append(",").append(rc.centerY())
              .append("\n");
            if (sb.length() > 6000) { postToD1("A11YDUMP", sb.toString()); sb.setLength(0); }
        }
        for (int i = 0; i < n.getChildCount(); i++) traverse(n.getChild(i), depth + 1, sb, c);
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        postToD1("A11Y", "접근성 onUnbind — 시스템이 접근성 분리(킬이 앵커까지 도달)");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        postToD1("A11Y", "접근성 onDestroy — 앵커 프로세스 종료됨");
        super.onDestroy();
    }

    // 접근성 프로세스를 포그라운드 서비스로 — targetSdk 25라 타입 강제는 없지만 매니페스트 specialUse 선언과 맞춤.
    private void goForeground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                NotificationChannel ch = new NotificationChannel(CH, "상시생존 앵커", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification n = new Notification.Builder(this, CH)
                    .setContentTitle("링크락 상시생존")
                    .setContentText("릴레이 유지 중")
                    .setSmallIcon(android.R.drawable.presence_online)
                    .setOngoing(true)
                    .build();
            startForeground(FG_ID, n);
        } catch (Throwable t) { postToD1("A11Y", "앵커 포그라운드 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage()); }
    }

    private boolean mainAliveRecently(long now) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "main_alive");
            return f.exists() && now - f.lastModified() < 150_000L;
        } catch (Throwable t) { return false; }
    }

    private void ensureSnifferRunning(String why, boolean forceLog) {
        long now = System.currentTimeMillis();
        lastEnsure = now;
        try {
            Intent i = new Intent(this, SnifferService.class);
            i.putExtra("src", "a11y:" + why);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
            if (forceLog) postToD1("A11Y", "메인 기동 요청(" + why + ")");
        } catch (Throwable t) {
            postToD1("A11Y", "메인 기동 실패(" + why + "): " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    // SnifferService가 죽어있어도 독립적으로 D1 보고(접근성 프로세스 관측용)
    private void postToD1(String category, String message) {
        // ★v106 운영자 기기만 D1 보고 — 공유 사용자 기기는 침묵. SnifferService.operatorDevice()와 통일(uid 단순비교 폐기,
        //   다계정 SETCLOUDCREDS로 uid가 서브계정 전환돼도 flavor 기준으로 정상 판정).
        if (!SnifferService.operatorDevice(this)) return;
        new Thread(() -> {
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(LOG_URL).openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("X-Auth", LOG_AUTH);
                c.setRequestProperty("content-type", "application/json");
                c.setDoOutput(true); c.setConnectTimeout(8000); c.setReadTimeout(8000);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("ts", System.currentTimeMillis()); o.put("device", "dilink");
                o.put("category", category); o.put("message", message);
                // ★788 :relay 로그에도 차 번호(dev) — 메인 로그처럼 dev로 걸러 볼 수 있게
                String vin = getSharedPreferences(SnifferService.PREFS, MODE_PRIVATE).getString(SnifferService.KEY_VIN, "");
                if (vin.length() >= 6) o.put("dev", vin.substring(vin.length() - 6));
                c.getOutputStream().write(o.toString().getBytes("UTF-8"));
                c.getResponseCode();
            } catch (Throwable ignored) {} finally { if (c != null) c.disconnect(); }
        }).start();
    }
}
