package com.cseini.bydsniffer;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;

public class MainActivity extends AppCompatActivity {
    private TextView statusTextView;
    private Button qrButton;   // ★2026-09-16 QR 상시노출 대신 버튼→팝업(showQrDialog)
    private android.widget.ImageView webQrImageView;   // ★웹 리모컨 접속 QR(2026-09-11)
    private TextView webUrlView;
    private int webUrlIdx = 0; private String webQrLast = "";
    private android.content.SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            // ★v59: watchdog이 force-stop 우회로 Activity를 띄운 경우 — 서비스만 켜고 즉시 사라짐(UI 없이 부활).
            //   force-stopped 앱은 am start-service로는 안 살아나고 Activity start로만 FLAG_STOPPED 해제됨.
            if (getIntent() != null && "watchdog".equals(getIntent().getStringExtra("src"))) {
                startSnifferService();
                moveTaskToBack(true);
                finish();
                return;
            }
            setContentView(R.layout.activity_main);
            requestNotificationPermission();
            startSnifferService();
            setupUI();
        } catch (Throwable e) {   // ★Throwable(2026-09-08): NoClassDefFoundError 등 Error도 잡아 토스트+리포트 — 씨라이언7 "화면 자체가 안 뜸" 진단
            Log.e("MainActivity", "onCreate failed", e);
            try { Toast.makeText(getApplicationContext(), "릴레이 시작 오류: " + e, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
            SnifferApp.reportCrash(Thread.currentThread(), e, Thread.getDefaultUncaughtExceptionHandler());
            finish();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                    // ★씨라이언7(DiLink5, SDK≥33) 첫 테스터(2026-09-08): 이 화면이 ROM에 없어 startActivity가 catch돼도 ROM이
                    //   "이 작업을 처리할 수 있는 애플리케이션이 없음" 토스트를 매 실행마다 띄움(무서워서 삭제). resolveActivity로 사전 확인 — 없으면 아예 안 띄움.
                    if (intent.resolveActivity(getPackageManager()) == null) { Log.w("MainActivity", "알림설정 화면 없음(ROM 미제공) — 실행 안 함"); return; }
                    startActivity(intent);
                } catch (Exception e) {
                    // 헤드유닛 멀티미디어 시스템엔 알림설정 화면이 없어 "지원하지 않습니다"가 뜸 — 조용히 건너뜀
                    Log.w("MainActivity", "알림설정 화면 없음(헤드유닛) — 건너뜀", e);
                }
            }
        }
    }

    private void startSnifferService() {
        Intent serviceIntent = new Intent(this, SnifferService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent);
        else startService(serviceIntent);
    }

    private void setupUI() {
        prefs = getSharedPreferences(SnifferService.PREFS, MODE_PRIVATE);
        statusTextView = findViewById(R.id.statusTextView);
        qrButton = findViewById(R.id.qrButton);
        qrButton.setOnClickListener(v -> showQrDialog());
        webQrImageView = findViewById(R.id.webQrImageView);
        webUrlView = findViewById(R.id.webUrlView);
        webQrImageView.setOnClickListener(v -> { webUrlIdx++; webQrLast = ""; refreshStatus(); });   // ★IP 후보 여러 개면 탭해서 전환(노래방 QrSwitcher 방식)
        // ★릴레이 앱 열림 = 폰과 연결 의사 → KEY_RELAY ON + 폴링 확실히 시작(2026-09-10 운영자 "릴레이 설정 버튼 굳이 눌러야?").
        //   근본: onCreate가 startSnifferService를 KEY_RELAY 세팅 前 호출 → 서비스가 false로 읽어 폴링 안 하던 레이스. 이제 KEY_RELAY 먼저 세팅 + 서비스 뜬 뒤 restartRelay 재시도(지연)로 방어.
        prefs.edit().putBoolean(SnifferService.KEY_RELAY, true).apply();
        // ★이미 폴링 중이면 재생성 금지 — a11y 워치독이 이 Activity를 주기적으로 포크하는데, 무조건 restartRelay하면 RelayClient state(caps·remote_manifest)가 매번 유실됨(2026-09-10 실차).
        SnifferService s0 = SnifferService.getInstance();
        if (s0 != null && !s0.relayRunning()) s0.restartRelay();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            SnifferService s = SnifferService.getInstance();
            if (s != null && !s.relayRunning()) s.restartRelay();   // 서비스가 막 시작됐어도 이 시점엔 준비됨 → 폴링 확실히 시작(미가동일 때만)
            refreshStatus();
        }, 1000);

        Button sentryViewBtn = findViewById(R.id.sentryViewButton);   // ★센트리 녹화 보기(10-08 운영자) — 운영자판만(공유판은 /sentry/*에 접속 권한 서명이 필요해 이 화면에서 못 연다)
        if (BuildConfig.IS_OPERATOR) {
            findViewById(R.id.sentrySection).setVisibility(android.view.View.VISIBLE);
            sentryViewBtn.setOnClickListener(v -> startActivity(new Intent(this, SentryViewerActivity.class)));
        }
        ((Button) findViewById(R.id.relayButton)).setOnClickListener(v -> showRelayDialog());
        ((Button) findViewById(R.id.sendLogButton)).setOnClickListener(v -> sendDiagLogs());   // ★메인 노출(2026-09-08 운영자) — 설정 대화상자 안에 묻혀 테스터가 못 찾던 것

        ((Button) findViewById(R.id.autostartButton)).setOnClickListener(v -> onAutostartFix());

        ((Button) findViewById(R.id.autostartTargetsButton)).setOnClickListener(v -> showAutostartTargetsDialog());

        findViewById(R.id.netRecoverButton).setOnClickListener(v -> showNetRecoverDialog());   // ★847 통신 복구는 버튼 하나 → 팝업(운영자 10-08)
        if (seatOn()) {   // ★845 뒷좌석 착좌 시험(운영자판·돌핀만) · ★847 체크하면 팝업
            findViewById(R.id.seatSection).setVisibility(android.view.View.VISIBLE);
            ((android.widget.CheckBox) findViewById(R.id.seatCheck)).setOnCheckedChangeListener((b, on) -> { if (on) showSeatDialog(); else if (seatDialog != null) seatDialog.dismiss(); });
        }

        setupVersion();
        refreshStatus();
        startStatusPoll();   // ★주기 갱신(2026-09-10 운영자 "QR 떠 있으면 연결되는 순간 바로 연결처리") — vin 세팅(SETVIN 수신) 즉시 "릴레이 연결됨"으로 자동 전환.
    }

    private android.os.Handler statusPoll;
    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            refreshStatus();
            if (statusPoll != null) statusPoll.postDelayed(this, 3000);
        }
    };
    private void startStatusPoll() {
        if (statusPoll == null) statusPoll = new android.os.Handler(android.os.Looper.getMainLooper());
        statusPoll.removeCallbacks(statusTick);
        statusPoll.postDelayed(statusTick, 3000);
    }
    @Override protected void onDestroy() {
        if (statusPoll != null) statusPoll.removeCallbacks(statusTick);   // ★핸들러 누수 방지
        super.onDestroy();
    }


    private void refreshStatus() {
        boolean on = prefs.getBoolean(SnifferService.KEY_RELAY, false);
        String uid = prefs.getString(SnifferService.KEY_UID, "");
        String vin = prefs.getString(SnifferService.KEY_VIN, "");
        String pk = prefs.getString(SnifferService.KEY_PAIR, "");
        String devId = prefs.getString(SnifferService.KEY_DEVID, "");
        if (!on) { statusTextView.setText("릴레이 미설정 — 아래 '수동 입력'을 눌러 폰과 연결하세요."); qrButton.setVisibility(android.view.View.GONE); return; }
        boolean registered = !vin.isEmpty();
        if (registered) statusTextView.setText("릴레이 연결됨 · userId " + uid + "\nVIN " + vin);
        else statusTextView.setText("릴레이 등록 대기 — 아래 '폰과 동기화' 버튼을 눌러 폰으로 스캔하세요");
        // ★2026-09-16 운영자 지적 — QR을 화면에 상시 노출하지 않고 버튼만 노출, 실제 QR/코드는 눌렀을 때 팝업으로(showQrDialog).
        //   등록완료 후에도 버튼은 유지(2026-09-15 결정 계승) — 이미 등록된 릴레이에 두 번째 기기(다른 폰·계정) 추가 등록용.
        qrButton.setVisibility((!devId.isEmpty() && !pk.isEmpty()) ? android.view.View.VISIBLE : android.view.View.GONE);
        // ★웹 리모컨 QR은 헤드유닛에서 제거(2026-09-12 운영자) — 링크락 앱 리모컨 상단 "공유" 버튼이 QR을 띄운다(헤드유닛에 띄울 필요 없음).
        webQrImageView.setVisibility(android.view.View.GONE);
        webUrlView.setVisibility(android.view.View.GONE);
    }

    // ★2026-09-16 QR 팝업 — dialog_qr.xml에 QR 비트맵+수동코드를 채워 AlertDialog로 표시.
    private void showQrDialog() {
        String pk = prefs.getString(SnifferService.KEY_PAIR, "");
        String devId = prefs.getString(SnifferService.KEY_DEVID, "");
        String vin = prefs.getString(SnifferService.KEY_VIN, "");
        if (devId.isEmpty() || pk.isEmpty()) { Toast.makeText(this, "아직 릴레이 ID/페어코드가 없습니다.", Toast.LENGTH_SHORT).show(); return; }
        android.view.View v = getLayoutInflater().inflate(R.layout.dialog_qr, null);
        android.widget.ImageView img = v.findViewById(R.id.qrDialogImage);
        TextView hint = v.findViewById(R.id.qrDialogHint);
        TextView codes = v.findViewById(R.id.qrDialogCodes);
        android.graphics.Bitmap qr = makeQr("linklock://pair?d=" + devId + "&k=" + pk, 600);
        if (qr != null) img.setImageBitmap(qr);
        hint.setText(vin.isEmpty()
                ? "폰 링크락 앱에서 '릴레이와 동기화'를 누르세요 (같은 WiFi면 자동 연결).\n안 되면 아래 QR을 스캔하세요."
                : "다른 기기(다른 폰·계정) 추가 등록용 — 폰에서 '릴레이와 동기화' 또는 QR 스캔");
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append("릴레이 ID (수동용)\n"); bigCode(sb, devId);
        sb.append("\n\n페어코드 (수동용)\n"); bigCode(sb, formatPair(pk));
        codes.setText(sb);
        SnifferService.openPairWindow();   // ★565 QR 표시 = 연결 의사 → 60초간 폰이 같은 망에서 자동으로 가져갈 수 있게 연다
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("릴레이 등록 QR")
                .setView(v)
                .setPositiveButton("닫기", null)
                .show();
    }

    // 스캔 타겟 — 크게·monospace·볼드·시안색
    private void bigCode(android.text.SpannableStringBuilder sb, String text) {
        int st = sb.length();
        sb.append(text);
        sb.setSpan(new android.text.style.RelativeSizeSpan(2.0f), st, sb.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.TypefaceSpan("monospace"), st, sb.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sb.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF3FE0C0), st, sb.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // 페어키(32자 hex)를 4자씩 묶어 읽기 쉽게 — 앱 입력은 하이픈/공백/대소문자 무시하고 파싱.
    private static String formatPair(String pk) {
        StringBuilder sb = new StringBuilder();
        String u = pk.toUpperCase();
        for (int i = 0; i < u.length(); i += 4) {
            if (i > 0) sb.append('-');
            sb.append(u, i, Math.min(i + 4, u.length()));
        }
        return sb.toString();
    }

    private void setupVersion() {
        TextView versionTextView = findViewById(R.id.versionTextView);
        Button updateButton = findViewById(R.id.updateButton);
        String ver;
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { ver = "?"; }
        versionTextView.setText("릴레이 v" + ver);

        // ★574 시동 ON 안내(UpdatePromptActivity)에서 "지금 설치"를 눌러 넘어온 경우 — 확인은 이미 받았으니
        //   같은 질문을 또 띄우지 않고 바로 진행률 화면으로 들어간다.
        boolean fromPrompt = getIntent() != null && "updprompt".equals(getIntent().getStringExtra("src"));

        // 실행 시: 새 버전 있으면 팝업으로 물어보고 버튼 누르면 바로 업데이트(부팅 자동실행·수동 열기 모두).
        if (fromPrompt) startUpdateWithProgress();
        else Updater.checkAvailable(this, (available, latest) -> runOnUiThread(() -> { if (available) showUpdatePrompt(latest); }));

        updateButton.setOnClickListener(v -> startUpdateWithProgress());
    }

    // 부팅/실행 시 업데이트 있으면 팝업으로 물어보고, 누르면 바로 진행.
    private void showUpdatePrompt(int latest) {
        try {
            new android.app.AlertDialog.Builder(this)
                .setTitle("업데이트 있음")
                .setMessage("릴레이 v" + latest + " 새 버전이 있습니다. 지금 업데이트할까요?\n다운로드→설치→자동 재시작까지 자동으로 진행됩니다.")
                .setPositiveButton("업데이트", (d, w) -> startUpdateWithProgress())
                .setNegativeButton("나중에", null)
                .setCancelable(true)
                .show();
        } catch (Throwable ignore) {}
    }

    // ★일렉트로식 원스톱 업데이트 — 진행률바(다운로드%)→무음설치→자동재시작→오토스타트 자동해제. 버튼 한 번으로 끝.
    private void startUpdateWithProgress() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        final android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText("업데이트 확인 중…"); tv.setTextSize(15);
        final android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100); bar.setIndeterminate(true);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        box.addView(tv); box.addView(bar, lp);
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("릴레이 업데이트").setView(box)
                .setNegativeButton("닫기", null)   // ★2026-09-19 무한로딩 탈출 — 다운로드/설치 stall 시 사용자가 닫고 수동설치 가능(012772·044177 자동업뎃 무한로딩 실측)
                .setCancelable(true).create();
        dlg.show();
        Updater.updateNow(this, (stage, pct, done, ok) -> runOnUiThread(() -> {
            tv.setText(stage);
            if (pct < 0) bar.setIndeterminate(true);
            else { bar.setIndeterminate(false); bar.setProgress(pct); }
            if (done) {
                try { dlg.dismiss(); } catch (Throwable ignore) {}   // 완료(성공/이미최신/실패) → 팝업 닫고 토스트로 결과 표시(안 사라지던 것 수정)
                toast(stage);
            }
        }));
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    // 🚀 오토스타트금지 해제 — :relay a11y 프로세스에 트리거 파일로 요청(가디언 앱 흡수).
    //   a11y가 죽어있으면 즉시 실패 표시(트리거만 남기면 재바인딩 시 주행 중 엉뚱하게 실행될 수 있어 안 남김).
    private void onAutostartFix() {
        long selfAge = SnifferService.a11yAliveAgeMs(this);
        boolean selfOk = selfAge >= 0 && selfAge <= 10_000;
        boolean helperOk = SnifferService.helperAlive();   // 헬퍼 a11y 살아있으면 헬퍼로 실행
        if (!selfOk && !helperOk) {
            toast("접근성(a11y)이 죽어 있어 지금 실행 불가 — 릴레이 재시작 후 다시 시도" + (selfAge < 0 ? "" : " (" + (selfAge / 1000) + "s 무응답)"));
            return;
        }
        String csv = SnifferService.autostartTargetsCsv(this);
        if (csv.isEmpty()) { toast("해제 대상 앱이 없습니다 — '해제 대상 앱 선택'에서 먼저 고르세요."); return; }
        String r = SnifferService.dispatchA11y(this, "autostartfix", csv);
        toast("오토스타트금지 해제 실행 중… (헤드유닛 화면에서 진행)");
        Log.i("MainActivity", "autostartfix 디스패치: " + r + " 대상=" + csv);
    }

    // 📋 오토스타트금지 자동해제 대상 앱 선택 — 사용자 설치 앱(차량 기본앱 제외)만 목록에 표시, 체크로 저장.
    //   저장값(CSV)은 릴레이가 트리거/헬퍼 payload로 실어 보냄(릴레이=유일 source of truth).
    private void showAutostartTargetsDialog() {
        // 현재 저장된 대상
        java.util.LinkedHashSet<String> saved = new java.util.LinkedHashSet<>();
        for (String p : SnifferService.autostartTargetsCsv(this).split(",")) { p = p.trim(); if (!p.isEmpty()) saved.add(p); }

        // 설치된 사용자 앱(비시스템)만 수집 — FLAG_SYSTEM·업데이트된 시스템앱 제외.
        android.content.pm.PackageManager pm = getPackageManager();
        java.util.List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
        java.util.List<String[]> rows = new java.util.ArrayList<>();   // [pkg, label]
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (android.content.pm.ApplicationInfo ai : apps) {
            boolean sys = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    || (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (sys) continue;
            String label = String.valueOf(pm.getApplicationLabel(ai));
            rows.add(new String[]{ ai.packageName, label });
            seen.add(ai.packageName);
        }
        // ★저장된 대상이 필터에 안 걸려도(시스템앱으로 프리인스톨 등) 목록에 남긴다 — 사용자 선택이 조용히 사라지면 안 됨.
        for (String pk : saved) {
            if (seen.contains(pk)) continue;
            String l;
            try { l = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pk, 0))); }
            catch (Throwable t) { l = pk + " (미설치?)"; }
            rows.add(new String[]{ pk, l });
        }
        // 라벨 가나다/알파벳 정렬
        java.util.Collections.sort(rows, (a, b) -> a[1].compareToIgnoreCase(b[1]));

        final String[] labels = new String[rows.size()];
        final String[] pkgs = new String[rows.size()];
        final boolean[] checked = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            pkgs[i] = rows.get(i)[0];
            labels[i] = rows.get(i)[1] + "\n" + rows.get(i)[0];
            checked[i] = saved.contains(pkgs[i]);
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("오토스타트 자동해제 대상 선택")
                .setMultiChoiceItems(labels, checked, (dlg, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("저장", (dlg, w) -> {
                    java.util.List<String> sel = new java.util.ArrayList<>();
                    for (int i = 0; i < pkgs.length; i++) if (checked[i]) sel.add(pkgs[i]);
                    String csv = android.text.TextUtils.join(",", sel);
                    prefs.edit().putString(SnifferService.KEY_AUTOSTART_TARGETS, csv).apply();
                    SnifferService s = SnifferService.getInstance();
                    if (s != null) s.announceAutostartTargets("설정변경");
                    toast(sel.isEmpty() ? "대상 없음으로 저장됨" : ("대상 " + sel.size() + "개 저장됨"));
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ★릴레이 등록 QR 생성(ZXing) — devId+페어코드를 linklock://pair URL로. 폰이 스캔해 자동 입력·SETVIN.
    private android.graphics.Bitmap makeQr(String text, int size) {
        try {
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) bmp.setPixel(x, y, m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            return bmp;
        } catch (Throwable t) { Log.w("MainActivity", "QR 생성 실패", t); return null; }
    }
    // ★헤드유닛 로컬 prop 조회(getprop) — userId 자동 프리필용(폰 계정 userId와 대개 동일, 물리 VIN은 여기 없어 스캔/수동)
    private String readProp(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine(); r.close(); p.destroy();
            return line != null ? line.trim() : "";
        } catch (Throwable t) { return ""; }
    }

    // 오토도어 릴레이 설정 (BYD userId + VIN — 폰 QR 스캔이 자동, 수동 입력은 폴백)
    private void showRelayDialog() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);
        android.widget.LinearLayout ll = new android.widget.LinearLayout(this);
        ll.setOrientation(android.widget.LinearLayout.VERTICAL);
        ll.setPadding(pad, pad, pad, pad);

        final android.widget.EditText uidEt = new android.widget.EditText(this);
        uidEt.setHint("BYD userId (예: 16501)");
        uidEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        String savedUid = prefs.getString(SnifferService.KEY_UID, "");
        if (savedUid.isEmpty()) savedUid = readProp("persist.sys.cloud.user_id");   // ★헤드유닛 prop 자동조회 — 대개 폰 계정 userId와 동일
        uidEt.setText(savedUid);
        final android.widget.EditText vinEt = new android.widget.EditText(this);
        vinEt.setHint("차량 VIN (폰 QR 스캔 시 자동)");
        // ★564 대문자 강제 — 실사례(09-22): 차주가 VIN을 소문자로 입력해 서버 등록 키가 달라졌고, 릴레이는 정상인데
        //   앱에서만 며칠째 오프라인으로 보였다. VIN은 대문자만 쓰므로 입력 단계에서 아예 막는다(IME 힌트 + 필터).
        vinEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        vinEt.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.AllCaps(), new android.text.InputFilter.LengthFilter(17) });
        vinEt.setText(prefs.getString(SnifferService.KEY_VIN, ""));
        ll.addView(uidEt);
        ll.addView(vinEt);

        new android.app.AlertDialog.Builder(this)
                .setTitle("링크락 릴레이 설정")
                .setMessage("폰 앱에서 QR을 스캔하면 자동 등록됩니다. 이 수동 입력은 스캔이 안 될 때만 쓰세요. (userId는 헤드유닛에서 자동으로 채웠습니다)")
                .setView(ll)
                .setPositiveButton("저장", (dlg, w) -> {
                    prefs.edit()
                            .putString(SnifferService.KEY_UID, uidEt.getText().toString().trim())
                            .putString(SnifferService.KEY_VIN, vinEt.getText().toString().trim().toUpperCase(java.util.Locale.US).replaceAll("[^A-Z0-9]", ""))   // ★564 대문자·공백/기호 제거로 정규화
                            .putBoolean(SnifferService.KEY_RELAY, true)   // ★무조건 사용 — 설정 저장 = 연결 의사(체크 제거)
                            .apply();
                    SnifferService s = SnifferService.getInstance();
                    if (s != null) s.restartRelay();
                    toast("릴레이 설정 저장됨");
                    refreshStatus();
                })
                .setNegativeButton("취소", null)
                .setNeutralButton("로그 보내기(진단)", (dlg, w) -> sendDiagLogs())
                .show();
    }

    // ★845 뒷좌석 3자리 착좌 화면 — 운영자판·돌핀만 · ★847 팝업이 떠 있는 동안만 1초마다 갱신
    private boolean seatOn() { return BuildConfig.IS_OPERATOR && !DiLink5Support.isDiLink5(); }
    private final android.os.Handler seatH = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable seatTick = new Runnable() { @Override public void run() { refreshSeat(); seatH.postDelayed(this, 1000); } };
    private android.app.Dialog seatDialog;
    @Override protected void onResume() { super.onResume(); if (seatDialog != null) { seatH.removeCallbacks(seatTick); seatH.post(seatTick); } }
    @Override protected void onPause() { seatH.removeCallbacks(seatTick); super.onPause(); }
    // ★847 팝업 공통 — 어두운 카드 화면을 제목줄 없이 화면 폭 70%로
    private android.app.Dialog showPanel(int layout) {
        android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        d.setContentView(layout);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.7f), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        return d;
    }
    private void showSeatDialog() {
        if (seatDialog != null) return;
        seatDialog = showPanel(R.layout.dialog_seat);
        seatDialog.findViewById(R.id.seatCloseButton).setOnClickListener(v -> seatDialog.dismiss());
        seatDialog.setOnDismissListener(d -> {
            seatH.removeCallbacks(seatTick);
            seatDialog = null;
            ((android.widget.CheckBox) findViewById(R.id.seatCheck)).setChecked(false);
        });
        seatH.removeCallbacks(seatTick);
        seatH.post(seatTick);
    }
    private void showNetRecoverDialog() {
        android.app.Dialog d = showPanel(R.layout.dialog_netrecover);
        d.findViewById(R.id.netCloseButton).setOnClickListener(v -> d.dismiss());
        bindNetRecover(d, R.id.netStateButton, "통신 상태 보기", null);
        bindNetRecover(d, R.id.netAirOffButton, "비행기 모드 끄기", "cmd connectivity airplane-mode disable 2>&1; settings put global airplane_mode_on 0 2>&1; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false 2>&1", () -> putGlobal(android.provider.Settings.Global.AIRPLANE_MODE_ON, 0));
        bindNetRecover(d, R.id.netDataOnButton, "모바일 데이터 켜기", "svc data enable 2>&1; settings put global mobile_data 1 2>&1", () -> putGlobal("mobile_data", 1));
        bindNetRecover(d, R.id.netApnButton, "APN 기본값 복원", "content delete --uri content://telephony/carriers/restore 2>&1");
        bindNetRecover(d, R.id.netReconnectButton, "통신 재연결", "svc data disable 2>&1; sleep 2; svc data enable 2>&1");
    }
    private void refreshSeat() {
        SnifferService s = SnifferService.getInstance();
        if (s == null || seatDialog == null) return;
        SeatOccupancy.Snapshot sn = s.seatOcc().snapshot();
        int[] ids = {R.id.seatL, R.id.seatC, R.id.seatR};
        int[] colors = {0xFF5A6270, 0xFFF2C94C, 0xFF3DD68C, 0xFFF2994A};   // 빈자리 회색·벨트 안 맴 노랑·확실 초록·아마 주황
        StringBuilder rs = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            TextView t = (TextView) seatDialog.findViewById(ids[i]);
            t.setText(SeatOccupancy.NAME[i] + "\n" + SeatOccupancy.VNAME[sn.verdict[i]] + (sn.remind[i] >= 0 ? "\n경고 " + sn.remind[i] : ""));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(colors[sn.verdict[i]]);
            bg.setCornerRadius(14 * getResources().getDisplayMetrics().density);
            t.setBackground(bg);
            rs.append(SeatOccupancy.NAME[i]).append(": ").append(sn.reason[i]).append('\n');
        }
        rs.append("체결 원값: ").append(sn.beltRaw.isEmpty() ? "—" : sn.beltRaw);
        ((TextView) seatDialog.findViewById(R.id.seatReasonView)).setText(rs);
        ((TextView) seatDialog.findViewById(R.id.seatEventsView)).setText(sn.events.isEmpty() ? "최근 이벤트 없음" : "최근 이벤트\n" + String.join("\n", sn.events));
    }
    private void bindNetRecover(android.app.Dialog d, int buttonId, String name, String cmd) { bindNetRecover(d, buttonId, name, cmd, null); }
    // ★832 셸이 안 될 때(무선 ADB 닫힘) 앱 권한(WRITE_SECURE_SETTINGS)으로 직접 저장하는 대안 — 비행기 모드·모바일 데이터만
    private String putGlobal(String key, int val) {
        try {
            android.provider.Settings.Global.putInt(getContentResolver(), key, val);
            return key + "=" + val + " 저장(라디오 반영은 차마다 다름 — 안 바뀌면 시동을 껐다 켜 주세요)";
        } catch (Throwable t) { return key + " 저장 실패 " + t.getClass().getSimpleName(); }
    }
    private void bindNetRecover(android.app.Dialog d, int buttonId, String name, String cmd, java.util.function.Supplier<String> appFallback) {
        d.findViewById(buttonId).setOnClickListener(v -> {
            final TextView out = (TextView) d.findViewById(R.id.netResultView);
            out.setText(name + " 실행 중…");
            new Thread(() -> {
                SnifferService s = SnifferService.getInstance();
                if (s == null) { runOnUiThread(() -> out.setText("릴레이가 실행 중이 아닙니다")); return; }
                String before = s.netSnapshot();
                String res = "(읽기만)";
                if (cmd != null) {
                    String adbNote = "";
                    if (!AdbAccess.isPortOpen()) adbNote = AdbAccess.reviveAdbd(this) ? "[무선 ADB 되살림] " : "[무선 ADB 닫힘] ";   // ★832 NETFIX 때 이 차가 "셸 연결 안 됨"이었음
                    String r = AdbAccess.runShell(this, cmd);
                    if (r == null && appFallback != null) r = "셸 안 됨 → 앱 권한으로 " + appFallback.get();
                    res = adbNote + (r == null ? "셸 연결 안 됨(무선 ADB 꺼짐·미허용)" : r.isEmpty() ? "OK" : r.replace('\n', ' '));
                    try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
                }
                String after = cmd == null ? before : s.netSnapshot();
                final String msg = name + " → " + res + "\n전: " + before + "\n후: " + after;
                s.postToD1("NETREC", msg.replace('\n', ' '));
                runOnUiThread(() -> out.setText(msg));
            }, "netrecover").start();
        });
    }

    // ★로그 보내기(진단) — 쌓인 릴레이 로그를 개발자(D1)에게 1회 전송. 누를 때만(데이터 부담 0). 메인 버튼 + 설정 대화상자 공용.
    private void sendDiagLogs() {
        toast("로그 전송 중…");
        new Thread(() -> {
            SnifferService s = SnifferService.getInstance();
            final String r = (s != null) ? s.sendBufferedLogs() : "릴레이 미실행";
            runOnUiThread(() -> toast(r));
        }, "sendlogs").start();
    }
}
