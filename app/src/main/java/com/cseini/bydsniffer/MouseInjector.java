package com.cseini.bydsniffer;

import android.content.Context;

import org.json.JSONObject;

import dadb.AdbStream;
import dadb.Dadb;

// ★862 실험(운영자 지시 — "된다고 알고 있어 일단 넣어봐 안되면 뺄대 빼더라도") — 폰 트랙패드 드래그를 헤드유닛 마우스 커서로.
//   ★594 — 이 헤드유닛(D3 돌핀, Android10)엔 /system/bin/uinput 바이너리 자체가 없다(592에서 실측 확인, OEM이 뺌).
//   /dev/uinput 커널 장치는 있고 shell(uhid 그룹)이 쓸 수 있으며 SELinux Enforcing에도 오픈 허용(실측 OPEN_OK) —
//   그래서 우리가 직접 작성한 초소형 대체 바이너리(llmouse.c, ioctl+write로 /dev/uinput 직접 구동, 6.6KB arm64)를
//   워커(byd.cseini.co.kr/llmouse)에서 내려받아 dadb push로 /data/local/tmp/에 심고 그걸 실행한다. AOSP uinput의
//   JSON 프로토콜을 흉내내지 않고 이 프로젝트 전용 줄단위 프로토콜만 쓴다: "M dx dy"(이동)·"D"(다운)·"U"(업), EOF(종료).
//   DiLink UI가 실제 커서를 렌더링/반응하는지는 여전히 이 실험의 핵심 질문. 안 되면 이 파일+SnifferService의 MOUSE
//   디스패치 한 줄+ContentView의 TrackpadView/RelayClient.mouseCmd를 되돌리면 원상복구.
//   연결은 최초 호출 때 1회 열고 계속 재사용. 끊기면 다음 호출이 재연결(+재push, 6KB라 무해).
final class MouseInjector {
    private static final Object LOCK = new Object();
    private static Dadb dadb;
    private static AdbStream stream;
    private static final String REMOTE_BIN = "/data/local/tmp/llmouse";

    // ★594 — 다운로드/push/실행 중 어떤 이유로든 실패하면 30초간 재시도를 쉰다. 592/593 때의 "uinput 영구 없음"과
    //   달리 이번 실패 원인(네트워크·dadb push 등)은 대부분 일시적이라 영구 차단 대신 짧은 쿨다운만 둔다.
    //   드래그 초당 ~22회 호출에도 실제 재시도는 30초에 한 번뿐이라 A1 로그 폭주([[worker CPU 사태]] 직후 특히 주의)도 막는다.
    private static volatile long blockedUntil = 0;

    private MouseInjector() {}

    // ★591 진단 — ensure/send가 전부 Throwable을 삼켜 실패해도 iOS엔 HTTP 200/성공으로 보였다(mouseCmd가 상태코드만 봄).
    //   실제 원인을 A1에 남겨 다음 테스트에서 바로 판별 가능하게.
    // ★592 스로틀 — send()가 드래그 중 초당 ~22회 호출되므로, 같은 실패가 반복되면 A1에 폭주. 동일 메시지가 10초 내
    //   재발생하면 스킵(위 blockedUntil 쿨다운과는 별개 — 이건 "같은 문장 반복"만 거른다).
    private static String lastLogMsg;
    private static long lastLogAt;
    private static void log(String msg) {
        synchronized (MouseInjector.class) {
            long now = System.currentTimeMillis();
            if (msg.equals(lastLogMsg) && now - lastLogAt < 10_000) return;
            lastLogMsg = msg; lastLogAt = now;
        }
        SnifferService.d1static("TRACKPAD", msg);
    }

    // ★594 — llmouse 바이너리를 앱 files 디렉토리에 캐시(재다운로드 방지) 후 dadb push로 헤드유닛에 심는다.
    //   레포에 실행파일을 커밋하지 않으려고 빌드타임 asset이 아니라 런타임 다운로드로 처리(자기업데이트 APK와 동일 패턴,
    //   SnifferService.installApk 참고).
    private static boolean ensureLlmouseBinary(Context ctx, Dadb d) {
        try {
            java.io.File local = new java.io.File(ctx.getFilesDir(), "llmouse");
            if (!local.exists() || local.length() < 1000) {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://byd.cseini.co.kr/llmouse").openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(15000);
                java.io.InputStream in = c.getInputStream();
                java.io.FileOutputStream fo = new java.io.FileOutputStream(local);
                byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                fo.close(); in.close();
                if (local.length() < 1000) { log("llmouse 다운로드 실패 len=" + local.length()); local.delete(); return false; }
                log("llmouse 다운로드 완료 " + local.length() + "B");
            }
            d.push(local, REMOTE_BIN, 493, System.currentTimeMillis());   // 493(8진 0755)=실행가능
            return true;
        } catch (Throwable t) {
            log("llmouse push 실패 — " + t.getClass().getSimpleName() + ":" + t.getMessage());
            return false;
        }
    }

    private static boolean ensure(Context ctx) {
        if (System.currentTimeMillis() < blockedUntil) return false;
        if (stream != null) return true;
        Dadb d = AdbAccess.connectPersistent(ctx);
        if (d == null) { log("연결 실패 — dadb connectPersistent null(인증/타임아웃)"); blockedUntil = System.currentTimeMillis() + 30_000; return false; }
        if (!ensureLlmouseBinary(ctx, d)) {
            try { d.close(); } catch (Throwable ignored) {}
            blockedUntil = System.currentTimeMillis() + 30_000;
            return false;
        }
        try {
            // ★592 근본원인 — "shell,v2,raw:"는 adbd가 stdin을 프레임([id 1B][길이 4B LE][payload])으로 기대하는
            //   shell-protocol v2. "v2" 없이 "shell,raw:"만 쓰면 프레임 없는 순수 파이프(v1)라 이 파일의 read/write가 맞는다.
            AdbStream s = d.open("shell,raw:" + REMOTE_BIN);
            dadb = d; stream = s;
            log("llmouse 실행");
            startReader(s);
            scheduleRegisterCheck(ctx, s);
            return true;
        } catch (Throwable t) {
            log("llmouse 실행 실패 — " + t.getClass().getSimpleName() + ":" + t.getMessage());
            try { d.close(); } catch (Throwable ignored) {}
            blockedUntil = System.currentTimeMillis() + 30_000;
            return false;
        }
    }

    // ★592 진단 — 자식 프로세스의 stdout/stderr를 실제로 읽은 적이 없었다(그냥 write만 하고 방치). llmouse는 등록
    //   성공 시 "READY"를, 실패 시 stderr로 사유를 한 줄 찍는다(printf, "...실패: %s"). EOF는 "프로세스 종료"의 확정
    //   신호 — 조용하면(EOF 없음) 오히려 정상 동작 중이라는 뜻.
    private static void startReader(AdbStream s) {
        Thread t = new Thread(() -> {
            try {
                okio.BufferedSource src = s.getSource();
                String line;
                while ((line = src.readUtf8Line()) != null) {
                    log("llmouse 응답: " + line);
                    // ★594 — llmouse 자체가 찍는 실패 문구(모두 "...실패: %s" 형태). 일시적일 수 있어 30초 쿨다운만.
                    if (line.contains("실패")) {
                        blockedUntil = System.currentTimeMillis() + 30_000;
                    }
                }
                log("llmouse 종료(EOF)");
            } catch (Throwable t2) {
                log("llmouse 읽기 중단 — " + t2.getClass().getSimpleName() + ":" + t2.getMessage());
            } finally {
                synchronized (LOCK) { if (stream == s) close(); }
            }
        }, "TrackpadUinputReader");
        t.setDaemon(true);
        t.start();
    }

    // ★592 진단 — 등록이 실제로 가상 디바이스를 만들었는지 getevent -p로 직접 확인해 A1에 남긴다.
    //   이름이 있으면 등록은 성공한 것 — 그래도 커서가 안 보이면 "DiLink가 uinput 입력을 안 그린다"는 별개 질문이 된다.
    private static void scheduleRegisterCheck(Context ctx, AdbStream s) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            synchronized (LOCK) { if (stream != s) return; }
            String out = AdbAccess.runShell(ctx, "getevent -p");
            boolean found = out != null && out.contains("LinkLockTrackpad");
            log(found ? "가상마우스 등록 확인됨(getevent -p에 존재)" : "가상마우스 등록 안 됨(getevent -p에 없음)");
        }, "TrackpadRegisterCheck");
        t.setDaemon(true);
        t.start();
    }

    private static boolean send(Context ctx, String line) {
        synchronized (LOCK) {
            if (!ensure(ctx)) return false;
            try {
                stream.getSink().writeUtf8(line + "\n");
                stream.getSink().flush();
                return true;
            } catch (Throwable t) {
                log("전송 실패(재연결 예정) — " + t.getClass().getSimpleName() + ":" + t.getMessage());
                close();
                return false;
            }
        }
    }

    private static void close() {
        try { if (stream != null) stream.close(); } catch (Throwable ignored) {}
        try { if (dadb != null) dadb.close(); } catch (Throwable ignored) {}
        stream = null; dadb = null;
    }

    /** SnifferService.webRunCommand("MOUSE", params)에서 호출. params: {op:"move"|"down"|"up"|"text", dx, dy, text} */
    static String handle(Context ctx, JSONObject params) {
        if (params == null) return "params 없음";
        String op = params.optString("op", "");
        try {
            switch (op) {
                case "move": {
                    if (System.currentTimeMillis() < blockedUntil) return "재시도 대기 중(잠시 후 자동 재시도)";
                    int dx = params.optInt("dx", 0), dy = params.optInt("dy", 0);
                    if (dx == 0 && dy == 0) return null;
                    boolean ok = send(ctx, "M " + dx + " " + dy);
                    return ok ? null : "실패(연결)";
                }
                case "down": case "up": {
                    if (System.currentTimeMillis() < blockedUntil) return "재시도 대기 중(잠시 후 자동 재시도)";
                    boolean ok = send(ctx, "down".equals(op) ? "D" : "U");
                    return ok ? null : "실패(연결)";
                }
                case "text": {
                    String t = params.optString("text", "");
                    if (t.isEmpty()) return "text 없음";
                    // ★591 한글 깨짐 수정 — "input text"는 US 가상키보드 KeyCharacterMap 기반이라 한글 등
                    //   비ASCII 문자를 표현할 키 자체가 없어 전송할 때마다 깨진다. 클립보드에 넣고 붙여넣기(KEYCODE_PASTE)로
                    //   전환 — 클립보드 쓰기는 권한 제약 없음(읽기만 포커스 제한), 붙여넣기는 일반 텍스트 입력란이면 동작.
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("trackpad", t));
                    AdbAccess.runShell(ctx, "input keyevent 279");   // KEYCODE_PASTE
                    return null;
                }
                case "key": {
                    // ★600 특수 키(운영자 09-27 "실제 키 입력") — 허용 목록만. 한글은 헤드유닛 IME가 조합하므로 키코드로 못 치고 text(붙여넣기)로.
                    //   우클릭도 여기로: 안드로이드 마우스 우클릭 기본 동작=BACK(4).
                    int code = params.optInt("code", -1);
                    java.util.Set<Integer> allow = new java.util.HashSet<>(java.util.Arrays.asList(3, 4, 19, 20, 21, 22, 61, 66));   // HOME·BACK·↑↓←→·TAB·ENTER
                    if (!allow.contains(code)) return "허용 안 된 키: " + code;
                    AdbAccess.runShell(ctx, "input keyevent " + code);
                    return null;
                }
                case "backspace": {
                    // ★591 백스페이스 — 편집 자체가 없었음. KEYCODE_DEL(67)은 텍스트 무관 물리키라 한글에도 정상 동작.
                    int n = Math.max(1, params.optInt("n", 1));
                    StringBuilder cmd = new StringBuilder("input keyevent");
                    for (int i = 0; i < n; i++) cmd.append(" 67");
                    AdbAccess.runShell(ctx, cmd.toString());
                    return null;
                }
                default: return "op 오류: " + op;
            }
        } catch (Throwable t) { return "예외 " + t.getClass().getSimpleName(); }
    }
}
