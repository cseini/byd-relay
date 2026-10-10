package com.cseini.bydsniffer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

// 헤드유닛 릴레이 앱 자동 업데이트 — byd-ota의 version.json을 조회해 새 버전이면 APK 다운로드 후 설치창 자동 표시.
//   안드로이드는 사일런트 설치 불가(루팅/시스템앱 아니면) → 다운로드+설치인텐트까지 자동, 마지막 "설치" 탭만 사용자 몫.
//   서비스 시작(부팅) 시 1회 호출. 헤드유닛은 주행마다 부팅되므로 사실상 매 주행 자동 체크.
public class Updater {
    private static final String TAG = "Updater";
    // ★flavor별 OTA 채널 — operator=byd-ota, shared=공유 전용 채널(BuildConfig 주입).
    private static final String VERSION_URL = BuildConfig.OTA_URL;

    // ★552 원격 진단 훅 — 무음 설치 실패 사유가 Log.w로만 남아 테스터 차에서는 원인을 볼 수 없었다(씨라7이 매번
    //   "설치 화면에서 눌러주세요"로 떨어졌는데 어디서 실패했는지 A1에 아무 기록이 없었음). SnifferService가 연결한다.
    public interface RemoteLog { void log(String category, String message); }
    public static volatile RemoteLog remoteLog;
    private static void rlog(String m) {
        RemoteLog r = remoteLog;
        if (r != null) try { r.log("SELFUPD", m); } catch (Throwable ignored) {}
    }

    // ★708 모델별 OTA 채널 — version.json에 models.d5 / models.d3 항목이 있고 세 키(relay·relayApk·relaySha256)를 다 갖추면 그 항목을,
    //   아니면 공통(최상위)을 쓴다. 모델 판정은 DiLink5Support.isDiLink5()(ro.vehicle.type Di5*). 공통은 두 모델 중 낮은 버전으로 유지해
    //   models를 모르는 옛 릴레이(707 이하)가 공통만 읽어도 안전하게 한다.
    static JSONObject forModel(JSONObject j) {
        try {
            JSONObject models = j.optJSONObject("models");
            if (models == null) return j;
            JSONObject m = models.optJSONObject(DiLink5Support.isDiLink5() ? "d5" : "d3");
            if (m != null && m.has("relay") && m.has("relayApk") && m.has("relaySha256")) return m;
        } catch (Throwable ignored) {}
        return j;
    }

    // 수동 "업데이트 확인" 버튼용 진행 알림. 각 단계 메시지 + 종료 여부(done) 전달. 워커 스레드에서 호출됨.
    public interface Listener { void onStatus(String message, boolean done); }

    public static void checkAsync(final Context ctx) { checkAsync(ctx, null, false); }
    public static void checkAsync(final Context ctx, final Listener cb) { checkAsync(ctx, cb, false); }

    // notifyOnly=true(실행 시): 새 버전이면 다운로드/설치 없이 콜백으로 "vNN 있음"만, 최신이면 조용.
    // notifyOnly=false(버튼): 새 버전이면 다운로드·검증·설치창까지.
    public static void checkAsync(final Context ctx, final Listener cb, final boolean notifyOnly) {
        new Thread(new Runnable() {
            @Override public void run() {
                try { check(ctx.getApplicationContext(), cb, notifyOnly); }
                catch (Throwable t) {
                    Log.w(TAG, "update check fail", t);
                    if (cb != null && !notifyOnly) cb.onStatus("업데이트 확인 실패: " + t.getMessage(), true);
                }
            }
        }, "relay-updater").start();
    }

    private static void check(Context ctx, Listener cb, boolean notifyOnly) throws Exception {
        int cur = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        String body = httpGet(VERSION_URL);
        JSONObject j = forModel(new JSONObject(body));   // ★708 모델별 항목
        int latest = j.optInt("relay", 0);
        String apkUrl = j.optString("relayApk", "");
        String expectSha = j.optString("relaySha256", "").trim().toLowerCase();
        if (latest <= cur || apkUrl.isEmpty()) {
            Log.i(TAG, "최신 상태 (설치=" + cur + ", 서버=" + latest + ")");
            if (cb != null && !notifyOnly) cb.onStatus("이미 최신 버전입니다 (v" + cur + ")", true);
            return;
        }
        // 실행 시 알림 모드 — 다운로드/설치 없이 "업데이트 있음"만 토스트로.
        if (notifyOnly) {
            Log.i(TAG, "새 버전 " + latest + " 있음(알림만)");
            if (cb != null) cb.onStatus("릴레이 v" + latest + " 업데이트 있음 — '업데이트 확인'을 누르세요", true);
            return;
        }
        Log.i(TAG, "새 버전 " + latest + " > " + cur + " → 다운로드");
        if (cb != null) cb.onStatus("새 버전 v" + latest + " 다운로드 중…", false);
        File apk = download(ctx, apkUrl);
        if (apk == null || apk.length() <= 0) {
            if (cb != null) cb.onStatus("다운로드 실패", true);
            return;
        }
        // 무결성 검증 — version.json의 relaySha256과 다운로드분 해시 대조. 워커/DNS 탈취로 변조된 APK 설치 차단.
        if (!expectSha.isEmpty()) {
            String actual = sha256Hex(apk);
            if (!expectSha.equals(actual)) {
                Log.e(TAG, "APK 체크섬 불일치 → 설치 취소 (기대=" + expectSha + " 실제=" + actual + ")");
                apk.delete();
                if (cb != null) cb.onStatus("체크섬 불일치 — 설치 취소", true);
                return;
            }
            Log.i(TAG, "체크섬 검증 통과");
        } else {
            Log.w(TAG, "version.json에 relaySha256 없음 → 검증 생략 (다음 배포부터 추가 필요)");
        }
        Log.i(TAG, "다운로드 완료(" + apk.length() + "B) → 설치창 표시");
        if (cb != null) cb.onStatus("v" + latest + " 다운로드 완료 — 설치 화면을 여는 중…", true);
        if (!launchInstall(ctx, apk) && cb != null) cb.onStatus("무음 자동 업데이트 준비 필요 (DiLink5는 설치 화면 없음).\n① 설정 → 개발자 옵션 → USB 디버깅 + 무선 디버깅 켜기\n② 릴레이 실행 시 뜨는 'USB 디버깅 허용?' 팝업에서 '항상 허용' 체크 후 허용\n그러면 다음부터 앱이 알아서 무음 업데이트합니다.", false);
    }

    // ★v123 원격 self-update용 — 버전 비교 없이 version.json의 최신 relayApk를 내려받아 File 반환(sha256 검증).
    //   설치는 호출측(SnifferService.selfUpdate)이 dadb push + 분리셸 pm install로 무음 처리. 실패 시 null.
    public static File fetchLatestApk(Context ctx) {
        try {
            JSONObject j = forModel(new JSONObject(httpGet(VERSION_URL)));   // ★708 모델별 항목
            String apkUrl = j.optString("relayApk", "");
            String expectSha = j.optString("relaySha256", "").trim().toLowerCase();
            if (apkUrl.isEmpty()) { Log.w(TAG, "relayApk URL 없음"); return null; }
            File apk = download(ctx, apkUrl);
            if (apk == null || apk.length() <= 0) { Log.w(TAG, "다운로드 실패"); return null; }
            if (!expectSha.isEmpty()) {
                String actual = sha256Hex(apk);
                if (!expectSha.equals(actual)) { Log.e(TAG, "sha 불일치 기대=" + expectSha + " 실제=" + actual); apk.delete(); return null; }
            }
            return apk;
        } catch (Exception e) { Log.w(TAG, "fetchLatestApk", e); return null; }
    }

    private static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(15000);
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    private static File download(Context ctx, String url) throws Exception {
        File out = new File(ctx.getExternalFilesDir(null), "relay-update.apk");
        if (out.exists()) out.delete();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(180000);   // ★v266 APK 18MB(WebRTC .so 11.5MB)로 커짐 — 차 SIM 저속 다운로드 대비 60s→180s
        InputStream in = c.getInputStream();
        FileOutputStream fo = new FileOutputStream(out);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        fo.close(); in.close();
        return out;
    }

    // ★713/707 씨라7은 설치 화면 요청이 통과해도 실제로 안 뜨는 헤드유닛이 있다(운영자 10-03 …026686 "설치 버튼이 어딨어").
    //   그래서 씨라7에선 "설치 화면에서 '설치'를 눌러주세요"라고 단정하지 않고, 무음 설치 실패 원인과 다음 할 일을 함께 보여 준다.
    private static volatile String lastSilentFail = "";
    private static String installUiMsg() {
        if (!DiLink5Support.isDiLink5()) return "설치 화면에서 '설치'를 눌러주세요";
        return "자동 설치에 실패했습니다" + (lastSilentFail.isEmpty() ? "" : "(" + lastSilentFail + ")")
                + ". 설치 화면이 떴다면 '설치'를 눌러 주세요. 화면이 보이지 않으면 '로그 보내기'를 눌러 주세요.";
    }

    // 반환: true=설치 화면 띄움 / false=이 헤드유닛에 설치 UI 없음(DiLink5 등)·실패 → 호출부가 안내문구 분기.
    private static boolean launchInstall(Context ctx, File apk) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
        } else {
            uri = Uri.fromFile(apk);
        }
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        // ★씨라이언7(DiLink5 SDK30) 첫 테스터(2026-09-08): 이 헤드유닛엔 APK 설치(ACTION_VIEW package-archive) 핸들러가 없어
        //   startActivity가 ActivityNotFoundException → ROM 토스트 "이 작업을 처리할 수 있는 애플리케이션이 없음" + 미포착 크래시("업데이트 시도 후 더 팅김").
        //   dadb 무음설치 실패 시 이 수동설치 폴백이 불려 재현됨. resolveActivity로 사전 확인 — 없으면 조용히 실패(토스트·크래시 없음).
        if (i.resolveActivity(ctx.getPackageManager()) == null) {
            android.util.Log.w("Updater", "APK 설치 UI 없음(이 헤드유닛 미제공) — 수동설치 불가, dadb 무음설치만 가능");
            return false;
        }
        try { ctx.startActivity(i); return true; }
        catch (Throwable t) { android.util.Log.w("Updater", "APK 설치 인텐트 실패: " + t.getClass().getSimpleName()); return false; }
    }

    // ★진행률 콜백 원스톱 업데이트 — 다운로드(%)→무음설치(dadb)→자동재시작→오토스타트(새 프로세스가 버전변경 감지). 일렉트로식 UX.
    public interface ProgressListener { void onProgress(String stage, int pct, boolean done, boolean ok); }   // pct -1=부정형

    private interface DlCb { void on(long downloaded, long total); }

    // 업데이트 있는지만 확인(다운로드 안 함) — 부팅/앱 실행 시 팝업 유도용.
    public interface UpdateCheck { void onResult(boolean available, int latest); }
    public static void checkAvailable(final Context ctx, final UpdateCheck cb) {
        new Thread(new Runnable() { @Override public void run() {
            try {
                int cur = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
                JSONObject j = forModel(new JSONObject(httpGet(VERSION_URL)));   // ★708 모델별 항목
                int latest = j.optInt("relay", 0);
                String apkUrl = j.optString("relayApk", "");
                cb.onResult(latest > cur && !apkUrl.isEmpty(), latest);
            } catch (Exception e) { cb.onResult(false, 0); }
        }}).start();
    }

    public static void updateNow(final Context ctx, final ProgressListener cb) {
        new Thread(new Runnable() { @Override public void run() {
            try {
                int cur = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
                JSONObject j = forModel(new JSONObject(httpGet(VERSION_URL)));   // ★708 모델별 항목
                final int latest = j.optInt("relay", 0);
                String apkUrl = j.optString("relayApk", "");
                String expectSha = j.optString("relaySha256", "").trim().toLowerCase();
                if (latest <= cur || apkUrl.isEmpty()) { cb.onProgress("이미 최신 버전입니다 (v" + cur + ")", 100, true, true); return; }
                cb.onProgress("다운로드 준비 중…", 0, false, true);
                File apk = downloadWithProgress(ctx, apkUrl, new DlCb() { @Override public void on(long dl, long total) {
                    int pct = total > 0 ? (int) (dl * 100 / total) : -1;
                    cb.onProgress("다운로드 중… v" + latest, pct, false, true);
                }});
                if (apk == null || apk.length() <= 0) { cb.onProgress("다운로드 실패", 0, true, false); return; }
                if (!expectSha.isEmpty() && !expectSha.equals(sha256Hex(apk))) { apk.delete(); cb.onProgress("체크섬 불일치 — 설치 취소", 0, true, false); return; }
                File pk = new File(ctx.getFilesDir(), "adbkey");
                File pub = new File(ctx.getFilesDir(), "adbkey.pub");
                if (pk.exists() && pub.exists()) {
                    cb.onProgress("설치 중… 잠시 후 자동 재시작", -1, false, true);
                    lastSilentFail = "";
                    boolean ok = silentInstall(ctx, apk, pk, pub);
                    if (ok) cb.onProgress("설치 완료 — 새 버전으로 재시작합니다 (오토스타트 자동해제)", 100, true, true);
                    else { boolean ui = launchInstall(ctx, apk); cb.onProgress(ui ? installUiMsg() : "무음 설치 실패 — 'USB 디버깅 허용?' 팝업을 허용했는지, 무선 디버깅(ADB)이 켜져 있는지 확인하세요 (설정 → 개발자 옵션). 허용하면 다음부터 자동 업데이트됩니다.", 100, true, false); }
                } else {
                    lastSilentFail = "무선 디버깅 허용 전";
                    boolean ui = launchInstall(ctx, apk);
                    cb.onProgress(ui ? installUiMsg() : "무음 자동 업데이트 준비 필요.\n① 설정 → 개발자 옵션 → USB 디버깅 + 무선 디버깅 켜기\n② 릴레이 실행 시 'USB 디버깅 허용?' 팝업 '항상 허용' 후 허용\n그러면 다음부턴 자동 업데이트됩니다.", 100, true, false);
                }
            } catch (Exception e) { cb.onProgress("업데이트 실패: " + e.getMessage(), 0, true, false); }
        }}).start();
    }

    private static File downloadWithProgress(Context ctx, String url, DlCb cb) throws Exception {
        File out = new File(ctx.getExternalFilesDir(null), "relay-update.apk");
        if (out.exists()) out.delete();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(90000);   // ★2026-09-19 180s→90s: 씨라7 stall 시 무한 대기 단축(25MB는 90s면 충분, 초과 시 SocketTimeout→"업데이트 실패" done=true로 다이얼로그 닫힘)
        long total = c.getContentLengthLong();
        InputStream in = c.getInputStream();
        FileOutputStream fo = new FileOutputStream(out);
        byte[] buf = new byte[16384]; int n; long dl = 0; long lastEmit = 0;
        while ((n = in.read(buf)) > 0) {
            fo.write(buf, 0, n); dl += n;
            if (cb != null && (dl - lastEmit > 262144 || dl == total)) { cb.on(dl, total); lastEmit = dl; }   // 256KB마다 갱신
        }
        fo.close(); in.close();
        if (cb != null) cb.on(dl, total > 0 ? total : dl);
        return out;
    }

    // 무음 설치 — dadb push + 분리셸(setsid) pm install -r + am start(자동실행). selfUpdate와 동일 원리(중복이지만 Updater 자립).
    // ★전체 타임아웃 래퍼(2026-09-17) — D5(씨라7) adbd가 push/shell에서 블록되면 silentInstall이 무한 대기 →
    //   updateNow 다이얼로그가 "설치 중… 잠시 후 자동 재시작"(setCancelable=false)에서 영영 멈춤(신규 테스터 012772 19:25 실측).
    //   45초 내 미완료면 false 반환 → 호출부(updateNow)의 기존 UI 설치(launchInstall) 폴백에 도달하게 함. 블록된 스레드는 dadb read timeout(120s)에 스스로 종료(데몬).
    private static boolean silentInstall(Context ctx, File apk, File pk, File pub) {
        final java.util.concurrent.atomic.AtomicReference<Boolean> res = new java.util.concurrent.atomic.AtomicReference<>(null);
        Thread t = new Thread(() -> res.set(silentInstallInner(ctx, apk, pk, pub)));
        t.setDaemon(true); t.start();
        try { t.join(45000); } catch (InterruptedException ie) { return false; }
        Boolean r = res.get();
        if (r == null) { lastSilentFail = "45초 안에 끝나지 않음"; Log.w(TAG, "silentInstall 45s 타임아웃 — UI 설치로 폴백"); rlog("무음 설치 45초 타임아웃 → 수동 설치 화면으로 폴백"); return false; }
        return r;
    }
    private static boolean silentInstallInner(Context ctx, File apk, File pk, File pub) {
        try {
            dadb.AdbKeyPair kp = dadb.AdbKeyPair.Companion.read(pk, pub);
            dadb.Dadb d = dadb.Dadb.Companion.create("127.0.0.1", 5555, kp, 10000, 120000);
            String pkg = ctx.getPackageName();
            String remote = "/data/local/tmp/byd_update.apk";
            // ★551 adb push 제거 — 25MB를 adb로 밀던 것이 씨라7에서 위 45초 래퍼를 넘겨, 무음 설치가 매번 UI 설치 화면으로
            //   떨어졌다(09-22 실측: dadb 무음설치 성공 이력이 운영자 차(D3)에만 있고 씨라7은 0건. 테스터가 "자동 업데이트가
            //   안 되고 설치를 눌러야 한다"고 보고). 앱이 이미 받아둔 APK를 셸이 같은 기기 안에서 복사하면 adb 전송이 사라진다.
            //   이 디렉터리는 아래 RF(selfupd_last.txt)로 셸이 이미 쓰고 있어 셸 접근이 검증돼 있다.
            //   복사 실패 시 false를 반환해 기존 UI 설치 폴백을 그대로 타게 한다(동작 축소 없음).
            String src = "/sdcard/Android/data/" + pkg + "/files/" + apk.getName();
            if (d.shell("cp " + src + " " + remote).getExitCode() != 0) {
                Log.w(TAG, "무음 설치 — APK 로컬 복사 실패(" + src + ") → UI 설치로 폴백");
                rlog("무음 설치 — APK 기기 내 복사 실패(" + src + ") → 수동 설치 화면으로 폴백");
                lastSilentFail = "기기 안 파일 복사 실패";
                d.close(); return false;
            }
            // ★2026-09-19 성공률<50% 견고화 — 기존은 결과 무관 sleep 2 후 무조건 am start라, install이 늦거나 실패해도
            //   재시작 레이스로 구버전 유지·"설치 중" 다이얼로그 hang이었다. pm install "Success" 확인 후에만 재시작 +
            //   실패 시 1회 재시도. 최종 실패면 재시작 안 함(구버전 유지, 다음 부팅에 재시도). 결과는 byd_selfupd.log에 상세.
            String rf = "/sdcard/Android/data/" + pkg + "/files/selfupd_last.txt";
            String script = "#!/system/bin/sh\n"
                    + "LOG=/data/local/tmp/byd_selfupd.log\n"
                    + "RF=" + rf + "\n"
                    + "echo \"=== manualupd $(date) ===\" >> $LOG\n"
                    + "sleep 1\n"
                    + "R=$(pm install -r " + remote + " 2>&1)\n"
                    + "echo \"install1: $R\" >> $LOG\n"
                    + "if echo \"$R\" | grep -qi Success; then\n"
                    + "  echo \"$(date) OK(버튼) install1: $R\" > $RF 2>/dev/null\n"
                    + "  sleep 1\n"
                    + "  echo \"start: $(am start -n " + pkg + "/.MainActivity --es src manualupd -f 0x10000000 2>&1)\" >> $LOG\n"
                    + "else\n"
                    + "  echo \"install1 실패 → 3s 후 재시도\" >> $LOG\n"
                    + "  sleep 3\n"
                    + "  R2=$(pm install -r " + remote + " 2>&1)\n"
                    + "  echo \"install2: $R2\" >> $LOG\n"
                    + "  if echo \"$R2\" | grep -qi Success; then\n"
                    + "    echo \"$(date) OK(버튼재시도) install2: $R2\" > $RF 2>/dev/null\n"
                    + "    echo \"start2: $(am start -n " + pkg + "/.MainActivity --es src manualupd -f 0x10000000 2>&1)\" >> $LOG\n"
                    + "  else\n"
                    + "    echo \"$(date) FAIL(버튼) install1=$R || install2=$R2\" > $RF 2>/dev/null\n"
                    + "    echo \"install 최종실패 — 재시작 안함(구버전 유지, 다음 부팅때 재시도)\" >> $LOG\n"
                    + "  fi\n"
                    + "fi\n"
                    + "rm -f " + remote + "\n";
            String b64 = android.util.Base64.encodeToString(script.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            d.shell("echo " + b64 + " | base64 -d > /data/local/tmp/byd_selfupd.sh && chmod 755 /data/local/tmp/byd_selfupd.sh");
            d.shell("setsid nohup sh /data/local/tmp/byd_selfupd.sh </dev/null >/dev/null 2>&1 &");
            d.close();
            return true;
        } catch (Throwable t) {
            lastSilentFail = (t instanceof java.net.ConnectException) ? "무선 디버깅(ADB) 연결 안 됨" : t.getClass().getSimpleName();
            Log.w(TAG, "silentInstall 실패", t); rlog("무음 설치 실패: " + t.getClass().getSimpleName() + " " + t.getMessage()); return false;
        }
    }
}
