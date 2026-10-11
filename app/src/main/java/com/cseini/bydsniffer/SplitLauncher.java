package com.cseini.bydsniffer;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ★리모컨 앱런처(2026-09-11 운영자 승인) — 헤드유닛 앱 실행/분할/비율. BydAutoSplit 1.1.6 역공학(docs/AUTOSPLIT-ANALYSIS.md) 이식.
//   루트·서명 없이 dadb(127.0.0.1:5555, filesDir/adbkey) 셸의 am/monkey/am stack만 사용. 운영자(op) flavor 전용.
//   분할 진입: 주앱 --windowingMode 3(못 들어가면 SplitGhostActivity로 도킹 스택 생성 후 am stack move-task) → 부앱 --windowingMode 4
//   비율: am stack list bounds → am stack resize-docked-stack(같은 rect 2회) → 재파싱 ±3% 검증. 판정은 스택 줄의 split-screen-primary/secondary만(설정 줄 fullscreen은 함정).
final class SplitLauncher {
    private static final int POLL_ATTEMPTS = 12;
    private static final long POLL_MS = 250;
    private static final Pattern BOUNDS = Pattern.compile("bounds=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");
    private static final Pattern TASK = Pattern.compile("taskId=(\\d+): ([^ ]+)");

    // 마지막 분할(비율 변경·후진 후 복원용)
    static volatile String lastPkg1, lastPkg2; static volatile int lastPct = 50;
    static volatile int ratioFails = 0;   // ★864 마지막 비율 맞추기가 연달아 실패한 횟수(성공하면 0) — 비율 감시가 실패로 남은 5:5를 운전자 선택으로 오인하지 않게

    static final class Res { final boolean ok; final int exit; final String out;
        Res(boolean ok, int exit, String out) { this.ok = ok; this.exit = exit; this.out = out == null ? "" : out; } }

    // ── 셸 ─────────────────────────────────────────────────────────────
    static Res adb(Context ctx, String cmd) {
        try {
            File pk = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
            if (!pk.exists() || !pub.exists()) return new Res(false, -1, "adb 키 없음 — ADB_AUTH 먼저");
            dadb.AdbKeyPair kp = dadb.AdbKeyPair.Companion.read(pk, pub);
            dadb.Dadb d = dadb.Dadb.Companion.create("127.0.0.1", 5555, kp, 10000, 60000);
            try {
                dadb.AdbShellResponse r = d.shell(cmd);
                int ec = -999; try { ec = r.getExitCode(); } catch (Throwable ignored) {}
                return new Res(ec == 0, ec, r.getOutput());
            } finally { try { d.close(); } catch (Throwable ignored) {} }
        } catch (Throwable t) { return new Res(false, -1, t.getClass().getSimpleName() + ":" + t.getMessage()); }
    }

    // ── 앱 목록(런처 앱 전부 + 분할 지원 여부 + 아이콘) ───────────────────────
    //   ★2026-09-11 운영자: "기본 앱(내비 등)도 있어야지, 아이콘도 진짜 앱 아이콘" → 시스템 앱 필터 제거(Tmap·미디어센터·카플레이…),
    //   제외는 블록리스트만(나머진 꾹 편집으로 숨김). 같은 패키지의 2번째 런처 액티비티(미디어센터 라디오)는 "pkg/Activity" 컴포넌트 id.
    //   split: ActivityInfo.resizeMode(hidden) 0=UNRESIZEABLE→false, 그 외→true. 리플렉션 실패면 "splitUnknown":true(분할 허용하되 배지).
    //   icon: filesDir/appicons/appicon_<pkg>.png(96px, Canvas 렌더=adaptive 아이콘 합성) — 웹 /icon/<name>, 앱은 워커 /relay/appicon/<name>.
    static final java.util.Set<String> SKIP = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.cseini.bydsniffer", "com.cseini.bydhelper", "com.byd.appstartmanagement", "com.google.android.inputmethod.latin", "com.cseini.byd.karaoke.test"));
    // ★671 백그라운드 자동재생 후보 표시 — 폰 앱 시작 앱 설정이 미디어 앱만 골라 보이게. 오너스랩플레이어는 미디어 서비스 선언이 없어 따로 true, 노래방은 제외.
    //   672: 미디어 서비스 선언(MediaBrowserService)이 없어도 미디어 버튼 수신기(MEDIA_BUTTON)가 있으면 미디어 앱으로 본다(FLO·Spotify가 빠졌다는 운영자 10-01 신고). 판정 근거는 lastMediaDiag에 모아 기록.
    //   672 실측(운영자 10-01): 이 헤드유닛에선 모든 앱이 svc=0이고, 미디어 버튼 수신기는 티맵·오토전비·BYD 미디어센터가 같이 갖고 있어 그것만으론 구분이 안 된다.
    //   → 673: 미디어 서비스 선언 OR 앱 분류(오디오/비디오) OR 알려진 미디어 앱 이름. 버튼 수신기는 참고 기록만.
    static final StringBuilder lastMediaDiag = new StringBuilder();
    private static final java.util.Set<String> KNOWN_MEDIA = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.byd.music.kr", "com.byd.mediacenter", "com.vivid.music.byd", "com.iloen.melon", "com.ktmusic.geniemusic",
            "com.google.android.apps.youtube.music", "com.spotify.music", "com.dreamus.flo", "com.naver.vibe", "com.tencent.qqmusic"));
    static boolean isMediaPkg(PackageManager pm, String pkg) {
        if ("com.cseini.byd.thirdplayer".equals(pkg)) return true;
        if (pkg.startsWith("com.cseini.byd.karaoke")) return false;
        int svc = -1, btn = -1, cat = -2;
        try { svc = pm.queryIntentServices(new Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0).size(); } catch (Throwable t) { }
        try { btn = pm.queryBroadcastReceivers(new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg), 0).size(); } catch (Throwable t) { }
        try { cat = pm.getApplicationInfo(pkg, 0).category; } catch (Throwable t) { }
        boolean known = KNOWN_MEDIA.contains(pkg);
        lastMediaDiag.append(' ').append(pkg).append(":svc=").append(svc).append(",btn=").append(btn).append(",cat=").append(cat).append(known ? ",known" : "");
        return svc > 0 || cat == 1 || cat == 2 || known;   // ApplicationInfo.CATEGORY_AUDIO=1, VIDEO=2
    }
    // 673: 미디어 버튼(재생) 신호를 그 앱의 수신기 앞으로만 보내 화면 없이 깨운다. 반환=보낸 수신기 이름 또는 null.
    static String sendPlayButton(Context ctx, String pkg) {
        try {
            java.util.List<ResolveInfo> rs = ctx.getPackageManager().queryBroadcastReceivers(new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg), 0);
            if (rs.isEmpty() || rs.get(0).activityInfo == null) return null;
            android.content.ComponentName cn = new android.content.ComponentName(rs.get(0).activityInfo.packageName, rs.get(0).activityInfo.name);
            for (int act : new int[]{android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP}) {
                Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(cn);
                i.putExtra(Intent.EXTRA_KEY_EVENT, new android.view.KeyEvent(act, android.view.KeyEvent.KEYCODE_MEDIA_PLAY));
                ctx.sendBroadcast(i);
            }
            return cn.getShortClassName();
        } catch (Throwable t) { return null; }
    }
    static org.json.JSONArray listApps(Context ctx) {
        org.json.JSONArray arr = new org.json.JSONArray();
        lastMediaDiag.setLength(0);
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (ResolveInfo ri : ris) {
                ActivityInfo ai = ri.activityInfo; if (ai == null) continue;
                String pkg = ai.packageName;
                if (SKIP.contains(pkg)) continue;
                boolean first = seen.add(pkg);
                String id = first ? pkg : pkg + "/" + ai.name;
                String iconName = iconFile(ctx, pm, ri, first ? pkg : pkg + "_" + ai.name.substring(ai.name.lastIndexOf('.') + 1));
                org.json.JSONObject o = new org.json.JSONObject()
                        .put("pkg", id).put("act", pkg + "/" + ai.name)
                        .put("label", String.valueOf(ri.loadLabel(pm)))
                        .put("sys", (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                if (iconName != null) o.put("icon", iconName);
                o.put("media", isMediaPkg(pm, pkg));
                try {
                    java.lang.reflect.Field f = ActivityInfo.class.getField("resizeMode");
                    int rm = f.getInt(ai);
                    o.put("split", rm != 0).put("resizeMode", rm);
                } catch (Throwable t) { o.put("split", true).put("splitUnknown", true); }
                arr.put(o);
            }
        } catch (Throwable ignored) {}
        return arr;
    }

    static java.io.File iconDir(Context ctx) { return new java.io.File(ctx.getFilesDir(), "appicons"); }

    private static String iconFile(Context ctx, PackageManager pm, ResolveInfo ri, String key) {
        try {
            java.io.File dir = iconDir(ctx); if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "appicon_" + key.replaceAll("[^A-Za-z0-9._]", "_") + ".png");
            if (f.length() > 0) return f.getName();
            android.graphics.drawable.Drawable d = ri.loadIcon(pm); if (d == null) return null;
            int s = 96;
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bm);
            d.setBounds(0, 0, s, s); d.draw(c);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
            bm.recycle();
            return f.length() > 0 ? f.getName() : null;
        } catch (Throwable t) { return null; }
    }

    // id가 "pkg/Activity" 컴포넌트면 그대로, 패키지면 런처 액티비티 조회.
    static String launcherActivity(Context ctx, String pkg) {
        if (pkg.indexOf('/') > 0) return pkg;
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null && i.getComponent() != null) return i.getComponent().flattenToShortString();
        } catch (Throwable ignored) {}
        return null;
    }
    static String pkgOf(String id) { int i = id.indexOf('/'); return i > 0 ? id.substring(0, i) : id; }

    // ── 전면 실행 ─────────────────────────────────────────────────────
    static String launchApp(Context ctx, String pkg) {
        Res r = pkg.indexOf('/') > 0
                ? adb(ctx, "am start --user 0 -n " + q(pkg) + " 2>&1 | tail -1")   // 컴포넌트 id(미디어센터 라디오 등 2번째 런처 액티비티)
                : adb(ctx, "monkey -p " + q(pkg) + " -c android.intent.category.LAUNCHER 1 2>&1 | tail -1");
        return (r.ok ? "실행 " : "실패 ") + pkg + " exit=" + r.exit + " " + one(r.out);
    }

    // ── 분할 지원 프로브(2026-09-11 운영자 "씨라 비율분할 가능여부 확인") ────────────────────────
    //   읽기 전용. 우리 방식의 전제 3가지를 찍는다: ① `am stack list`가 동작하는가(레거시 스택 API) ② `resize-docked-stack` 서브커맨드가 있는가
    //   (Android 12에서 제거) ③ 도킹(mode=3/4) 스택이 있는가. 부팅마다 1회 SPLIT 로그로 남겨 씨라이언7(DiLink5, Android 11) 판정에 쓴다.
    static String probe(Context ctx) {
        StringBuilder sb = new StringBuilder("SPLITPROBE sdk=" + android.os.Build.VERSION.SDK_INT + " ");
        Res l = adb(ctx, "am stack list 2>&1 | head -12");
        sb.append("| stack-list exit=").append(l.exit).append(" ").append(one(l.out).replace('\n', ' '));
        Res r = adb(ctx, "am stack resize-docked-stack 2>&1 | head -3");
        String ro = one(r.out).toLowerCase();
        boolean has = !(ro.contains("unknown") || ro.contains("unrecognized") || ro.contains("not found") || ro.contains("no such"));
        sb.append(" | resize-docked-stack=").append(has ? "있음" : "없음").append(" (").append(one(r.out).replace('\n', ' ')).append(")");
        Res g = adb(ctx, "am stack list 2>&1 | grep -c 'mode=[34]'");
        sb.append(" | docked-stacks=").append(one(g.out).trim());
        return sb.toString();
    }

    // ── 분할 ─────────────────────────────────────────────────────────
    static String split(Context ctx, String pkg1, String pkg2, int pct, StringBuilder log) {
        String a1 = launcherActivity(ctx, pkg1), a2 = launcherActivity(ctx, pkg2);
        if (a1 == null || a2 == null) return "런처 액티비티 없음 " + (a1 == null ? pkg1 : pkg2);
        final String p1 = pkgOf(pkg1), p2 = pkgOf(pkg2);   // 스택 조회는 패키지 단위(컴포넌트 id여도)
        // 주앱
        int[] st = taskState(ctx, p1);   // {taskId, mode}
        log.append("primary task=").append(st[0]).append(" mode=").append(st[1]).append('\n');
        boolean primaryOk;
        if (st[0] < 0) {
            primaryOk = cmd(ctx, "am start --user 0 --windowingMode 3 -n " + q(a1), log) && waitMode(ctx, p1,3, "primary-cold", log);
            if (!primaryOk) primaryOk = cmd(ctx, "am start --user 0 --windowingMode 3 -n " + q(a1), log) && waitMode(ctx, p1,3, "primary-cold-retry", log);
        } else if (st[1] == 3) {
            primaryOk = true; log.append("primary-already-split\n");
        } else {
            String ghost = ctx.getPackageName() + "/" + SplitGhostActivity.class.getName();
            cmd(ctx, "am start --user 0 --windowingMode 3 -n " + q(ghost), log);
            int gs = -1;
            for (int i = 0; i < POLL_ATTEMPTS && gs < 0; i++) { sleep(POLL_MS); gs = stackIdForMode(ctx, 3); }
            log.append("ghost-stack=").append(gs).append('\n');
            if (gs < 0) return "고스트 도킹 스택 생성 실패";
            int[] st2 = taskState(ctx, p1);
            int tid = st2[0] >= 0 ? st2[0] : st[0];
            primaryOk = cmd(ctx, "am stack move-task " + tid + " " + gs + " true", log) && waitMode(ctx, p1,3, "primary-moved", log);
        }
        if (!primaryOk) return "주앱 분할 진입 실패(" + pkg1 + ") — 앱이 분할 미지원이거나 거부";
        // 부앱
        boolean secOk = cmd(ctx, "am start --user 0 --windowingMode 4 -n " + q(a2), log) && waitMode(ctx, p2,4, "secondary", log);
        if (!secOk) {
            int s4 = stackIdForMode(ctx, 4); int[] s2 = taskState(ctx, p2);
            if (s4 >= 0 && s2[0] >= 0) secOk = cmd(ctx, "am stack move-task " + s2[0] + " " + s4 + " true", log) && waitMode(ctx, p2,4, "secondary-moved", log);
            if (!secOk) secOk = cmd(ctx, "am start --user 0 --windowingMode 4 -n " + q(a2), log) && waitMode(ctx, p2,4, "secondary-retry", log);
        }
        if (!secOk) return "부앱 분할 진입 실패(" + pkg2 + ")";
        lastPkg1 = pkg1; lastPkg2 = pkg2; lastPct = pct;
        String rr = applyRatio(ctx, pct, log);
        return "분할 OK " + pkg1 + " | " + pkg2 + " → " + rr;
    }

    // ── 비율 ─────────────────────────────────────────────────────────
    static String applyRatio(Context ctx, int pct, StringBuilder log) {
        pct = Math.max(20, Math.min(80, pct));
        // ★864 운영자 10-10 "3:7로 해도 5:5" — 부앱이 막 들어온 직후엔 분할 창이 아직 움직이는 중이라(주·부 경계가 겹침, 실차 p=[58,0,1011,1080] s=[967,0,1920,1080])
        //   resize-docked-stack이 실패했다. 경계가 두 번 연속 같고 겹치지 않을 때까지 최대 3초 기다린다.
        int[][] pair = null, prev = null;
        for (int i = 0; i < 12; i++) {
            pair = splitBounds(ctx);   // [primary{l,t,r,b}, secondary{...}] or null
            if (pair != null && prev != null && java.util.Arrays.deepEquals(pair, prev) && !overlapping(pair)) break;
            prev = pair;
            sleep(250);
        }
        if (pair == null) { ratioFails++; return "분할 경계 없음(분할 상태 아님)"; }
        int[] p = pair[0], s = pair[1]; int[] target; String axis;
        if (p[0] == s[0] && p[2] == s[2] && p[3] <= s[1]) {          // 상하
            int div = s[1] - p[3]; axis = "Y";
            target = new int[]{p[0], p[1], p[2], p[1] + Math.round(((s[3] - p[1]) - div) * (pct / 100f))};
        } else if (p[1] == s[1] && p[3] == s[3] && p[2] <= s[0]) {   // 좌우
            int div = s[0] - p[2]; axis = "X";
            target = new int[]{p[0], p[1], p[0] + Math.round(((s[2] - p[0]) - div) * (pct / 100f)), p[3]};
        } else { ratioFails++; return "지원 안 하는 경계 p=" + java.util.Arrays.toString(p) + " s=" + java.util.Arrays.toString(s); }
        String rect = target[0] + " " + target[1] + " " + target[2] + " " + target[3];
        Res rr = null;
        for (int i = 0; i < 3; i++) {   // 그래도 거절되면 0.5초 간격으로 두 번 더
            if (i > 0) sleep(500);
            rr = adb(ctx, "am stack resize-docked-stack " + rect + " " + rect);
            log.append("$ am stack resize-docked-stack ").append(rect).append(" → exit=").append(rr.exit).append(' ').append(one(rr.out)).append('\n');
            if (rr.ok) break;
        }
        if (!rr.ok) { ratioFails++; return "resize 실패 axis=" + axis + " exit=" + rr.exit + " " + one(rr.out); }
        sleep(400);
        int actual = actualPercent(ctx);
        lastPct = pct;
        boolean hit = actual >= 0 && Math.abs(actual - pct) <= 3;
        ratioFails = hit ? 0 : ratioFails + 1;
        return "비율 " + pct + "% axis=" + axis + " 실측=" + actual + "%" + (hit ? " ✓" : " ⚠");
    }

    // 주·부 창이 아직 겹친다 = 분할 애니메이션 중
    private static boolean overlapping(int[][] pair) {
        int[] p = pair[0], s = pair[1];
        return p[2] > s[0] && p[0] < s[2] && p[3] > s[1] && p[1] < s[3];
    }

    static int actualPercent(Context ctx) {
        int[][] pair = splitBounds(ctx); if (pair == null) return -1;
        int[] p = pair[0], s = pair[1];
        if (p[0] == s[0] && p[2] == s[2] && p[3] <= s[1]) { int div = s[1] - p[3]; int tot = (s[3] - p[1]) - div; return tot > 0 ? Math.round((p[3] - p[1]) * 100f / tot) : -1; }
        if (p[1] == s[1] && p[3] == s[3] && p[2] <= s[0]) { int div = s[0] - p[2]; int tot = (s[2] - p[0]) - div; return tot > 0 ? Math.round((p[2] - p[0]) * 100f / tot) : -1; }
        return -1;
    }

    static boolean isSplitActive(Context ctx) { return splitBounds(ctx) != null; }

    // ── am stack list 파서 ─────────────────────────────────────────────
    // 형식: "Stack id=N bounds=[l,t][r,b] ..." → " configuration={... mWindowingMode=split-screen-primary ... mActivityType=standard ...}" → "  taskId=T: pkg/act ..."
    private static int[][] splitBounds(Context ctx) {
        Res r = adb(ctx, "am stack list"); if (!r.ok) return null;
        int[] pri = null, sec = null, cur = null;
        for (String line : r.out.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("Stack id=")) { Matcher m = BOUNDS.matcher(t); cur = m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))} : null; }
            else if (cur != null && t.contains("mActivityType=standard")) {
                if (t.contains("mWindowingMode=split-screen-primary")) pri = cur;
                if (t.contains("mWindowingMode=split-screen-secondary")) sec = cur;
            }
        }
        return (pri != null && sec != null) ? new int[][]{pri, sec} : null;
    }

    private static int stackIdForMode(Context ctx, int mode) {
        Res r = adb(ctx, "am stack list"); if (!r.ok) return -1;
        int cur = -1;
        for (String line : r.out.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("Stack id=")) { try { cur = Integer.parseInt(t.substring(9, t.indexOf(' ', 9))); } catch (Throwable e) { cur = -1; } }
            else if (cur >= 0 && modeOf(t) == mode) return cur;
        }
        return -1;
    }

    /** {taskId, mode} — 해당 pkg의 task가 속한 스택의 윈도우 모드(3/4, 그 외 -1). 없으면 {-1,-1}. */
    private static int[] taskState(Context ctx, String pkg) {
        Res r = adb(ctx, "am stack list"); if (!r.ok) return new int[]{-1, -1};
        int mode = -1;
        for (String line : r.out.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("Stack id=")) mode = -1;
            else if (t.startsWith("configuration=")) mode = modeOf(t);
            else if (t.startsWith("taskId=")) { Matcher m = TASK.matcher(t); if (m.find() && m.group(2).startsWith(pkg + "/")) return new int[]{Integer.parseInt(m.group(1)), mode}; }
        }
        return new int[]{-1, -1};
    }

    private static int modeOf(String t) {
        String l = t.toLowerCase(Locale.US);
        if (l.contains("split-screen-primary")) return 3;
        if (l.contains("split-screen-secondary")) return 4;
        if (l.contains("freeform")) return 5;
        return -1;
    }

    private static boolean waitMode(Context ctx, String pkg, int mode, String tag, StringBuilder log) {
        for (int i = 1; i <= POLL_ATTEMPTS; i++) {
            sleep(POLL_MS);
            int[] st = taskState(ctx, pkg);
            if (st[0] >= 0 && st[1] == mode) { log.append(tag).append(" ok poll=").append(i).append(" task=").append(st[0]).append('\n'); return true; }
        }
        log.append(tag).append(" timeout\n"); return false;
    }

    // ★861 링크락 런처 A안(운영자 10-10) — 자유 창(freeform, windowingMode 5). 전제: enable_freeform_support=1·force_resizable_activities=1·재부팅.
    //   --windowingMode 카멜케이스만 된다(하이픈 실패, 10-09 실차). 이미 떠 있는 task는 mode가 안 바뀌어 자유 창이 아니면 force-stop 뒤 새 task로 띄운다.
    static String launchFreeform(Context ctx, String pkg, int l, int t, int r, int b, StringBuilder log) {
        String comp = pkg.indexOf('/') > 0 ? pkg : resolveLauncher(ctx, pkg);
        if (comp == null) return "실패 실행할 화면 없음 " + pkg;
        String p = comp.substring(0, comp.indexOf('/'));
        int[] st = taskState(ctx, p);
        if (st[0] >= 0 && st[1] != 5) cmd(ctx, "am force-stop " + q(p), log);
        if (!cmd(ctx, "am start --user 0 --windowingMode 5 -n " + q(comp), log)) return "실패 실행 " + comp;
        if (!waitMode(ctx, p, 5, "freeform", log)) return "실패 자유 창으로 안 뜸 " + p;
        int task = taskState(ctx, p)[0];
        boolean ok = cmd(ctx, "am task resize " + task + " " + l + " " + t + " " + r + " " + b, log);
        return (ok ? "자유 창 OK task=" : "자유 창은 떴지만 크기 실패 task=") + task + " " + p;
    }
    // ★874 자유 창 제목줄 높이(px) — 시스템 내부 값을 먼저 찾고, 없으면 안드로이드 기본 32dp
    static int captionPx(Context ctx) {
        android.content.res.Resources rs = android.content.res.Resources.getSystem();
        for (String n : new String[]{"freeform_decor_caption_height", "decor_caption_height"}) {
            int id = rs.getIdentifier(n, "dimen", "android");
            if (id != 0) return rs.getDimensionPixelSize(id);
        }
        return Math.round(32 * ctx.getResources().getDisplayMetrics().density);
    }
    static String resizeTask(Context ctx, String target, int l, int t, int r, int b, StringBuilder log) {
        int task = target.matches("\\d+") ? Integer.parseInt(target) : taskState(ctx, target)[0];
        if (task < 0) return "실패 창 없음 " + target;
        return (cmd(ctx, "am task resize " + task + " " + l + " " + t + " " + r + " " + b, log) ? "크기 OK task=" : "크기 실패 task=") + task;
    }
    // 홈(런처)이 앞에 오면 자유 창이 가려진다 — 그 앱 화면을 다시 띄우면 기존 task가 창 모드 그대로 앞으로 온다.
    static String bringFront(Context ctx, String pkg, StringBuilder log) {
        String comp = pkg.indexOf('/') > 0 ? pkg : resolveLauncher(ctx, pkg);
        if (comp == null) return "실패 실행할 화면 없음 " + pkg;
        int[] st = taskState(ctx, comp.substring(0, comp.indexOf('/')));
        if (st[0] < 0) return "실패 창 없음 " + pkg;
        return (cmd(ctx, "am start --user 0 -n " + q(comp), log) ? "앞으로 OK task=" : "앞으로 실패 task=") + st[0] + " mode=" + st[1];
    }
    private static String resolveLauncher(Context ctx, String pkg) {
        Res r = adb(ctx, "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER " + q(pkg) + " 2>&1 | tail -1");
        String c = one(r.out);
        return (r.ok && c.indexOf('/') > 0 && !c.contains(" ")) ? c : null;
    }

    private static boolean cmd(Context ctx, String c, StringBuilder log) {
        Res r = adb(ctx, c);
        log.append("$ ").append(c).append(" → exit=").append(r.exit).append(' ').append(one(r.out)).append('\n');
        return r.ok;
    }
    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private static String one(String s) { s = s == null ? "" : s.replace('\r', ' ').replace('\n', ' ').trim(); return s.length() > 160 ? s.substring(0, 160) : s; }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private SplitLauncher() {}
}
