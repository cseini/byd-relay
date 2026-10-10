package com.cseini.bydsniffer;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import java.lang.reflect.Method;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

public class SnifferApp extends Application {
    private static final String TAG = "SnifferApp";
    // ★2026-09-08: 옛 CF 워커(byd-sniffer.workers.dev, KV 비어있음)→A1 워커 /log(logs 테이블 category=CRASH). 공유 사용자 릴레이는 일반 로그를 버퍼만 하므로
    //   크래시만은 직접 전송 — 씨라이언7 테스터 "화면 자체가 안 뜸" 진단 경로.
    private static final String CRASH_REPORT_URL = "https://byd.cseini.co.kr/log";
    private static final String SECRET = BuildConfig.RELAY_SECRET;
    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = this;

        // ★v36: 앱이 스스로 hidden API 제한을 해제 → BYDAutoPowerDevice.wakeUpMcu() 등 리플렉션 허용(상시생존의 핵심).
        //   일렉트로는 네이티브(JNI)로 우회하지만, 우리는 순수 Java로 VMRuntime.setHiddenApiExemptions("L") 호출(FreeReflection 방식).
        //   → shell(hidden_api_policy)·WRITE_SECURE_SETTINGS·재부팅 전부 불필요, 재설치 후에도 자동 적용.
        exemptHiddenApis();

        // 크래시 핸들러를 가장 먼저 설정 (다른 init보다 먼저)
        Thread.UncaughtExceptionHandler prevHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            reportCrash(thread, throwable, prevHandler);
        });

        installMainLoopGuard();
    }

    // ★메인 스레드 크래시 안전망(2026-09-09 운영자 "크래시 내는건 좀 아니잖아") — BYD SDK가 메인 루퍼 메시지에서 던지는 예외
    //   (예: DiLink5 TsManagerImpl→AbsTsMyCar.isOptimizationApi 캐스팅 실패)로 릴레이 프로세스가 통째로 죽는 것 방지.
    //   메인 루프를 try/catch로 감싸(중첩 Looper.loop) 예외 흡수·로그만 남기고 계속 → 크래시·재시작 루프 소멸. (Cockroach 패턴)
    //   백그라운드 스레드 예외는 여전히 UncaughtExceptionHandler로 감(진짜 버그는 여전히 리포트됨).
    private void installMainLoopGuard() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                while (true) {
                    try {
                        android.os.Looper.loop();
                        break;   // 정상 반환 = 루퍼 종료(프로세스 종료 중) → 재진입 안 함(빈 루프 방지)
                    } catch (Throwable e) {
                        Log.w(TAG, "★메인루프 예외 흡수(크래시 방지)", e);
                        reportCaughtThrottled(e);
                        // 루프 계속 → 프로세스 안 죽음
                    }
                }
            }
        });
    }

    private static long lastCaughtReportTs = 0;
    private static final Set<String> caughtSigs = new HashSet<>();
    static void reportCaughtThrottled(Throwable t) {
        try {
            final String stack = Log.getStackTraceString(t);
            String sig = stack.length() > 180 ? stack.substring(0, 180) : stack;
            synchronized (caughtSigs) {
                long now = System.currentTimeMillis();
                if (caughtSigs.contains(sig) && now - lastCaughtReportTs < 60000) return;   // 같은 예외 60s 1회
                if (now - lastCaughtReportTs < 4000) return;                                 // 전체 4s 1회 상한(스팸·배터리 방지)
                lastCaughtReportTs = now;
                if (caughtSigs.size() > 60) caughtSigs.clear();
                caughtSigs.add(sig);
            }
            final String payload = "CAUGHT (main-loop 흡수)\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " relay v" + BuildConfig.VERSION_NAME + "\nSDK: " + Build.VERSION.SDK_INT + "\n\n" + stack;
            new Thread(() -> { try { uploadLog("MAINLOOP", payload); } catch (Throwable ignored) {} }, "caughtreport").start();
        } catch (Throwable ignored) {}
    }

    public static Context getAppContext() {
        return appContext;
    }

    // dalvik.system.VMRuntime.setHiddenApiExemptions("L") 를 double-reflection으로 호출.
    // 안드로이드 P(28)+ hidden API blacklist를 앱 자체가 해제 — 순수 Java, 권한/네이티브 불필요.
    private void exemptHiddenApis() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        try {
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntimeClass = (Class<?>) Class.class
                    .getDeclaredMethod("forName", String.class)
                    .invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(
                    vmRuntimeClass, "getRuntime", new Class[0]);
            Method setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntimeClass, "setHiddenApiExemptions", new Class[]{String[].class});
            Object vmRuntime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(vmRuntime, new Object[]{new String[]{"L"}});
            Log.i(TAG, "hidden API exemption 적용 — wakeUpMcu 리플렉션 허용");
        } catch (Throwable t) {
            Log.w(TAG, "hidden API exemption 실패: " + t);
        }
    }

    public static void reportCrash(Thread thread, Throwable throwable, Thread.UncaughtExceptionHandler prevHandler) {
        // ★백그라운드 스레드 BYD SDK 예외 안전망(2026-09-09 운영자 "크래시 내는건 좀 아니잖아") — DiLink5서 SDK(BodyworkManagerImpl→AbsTsMyCar.caseFeatureIds 등)가
        //   우리 stub 클래스(BYDAutoEventValue 등)와 불일치해 NoSuchFieldError/ClassCastException을 CarAdapterManager 같은 자기 HandlerThread서 던짐 → 프로세스 死→재시작 루프(화면 깜빡임).
        //   메인루프 안전망은 메인만 커버. 여기선 '백그라운드 스레드 + BYD SDK발 링크에러'만 흡수(그 스레드만 종료, 프로세스 생존)·로그만. 진짜 버그(우리 코드·메인)는 그대로 리포트+종료.
        try {
            boolean bg = thread != null && thread != android.os.Looper.getMainLooper().getThread();
            String st0 = Log.getStackTraceString(throwable);
            boolean sdk = st0.contains("android.hardware.bydauto") || st0.contains("com.ts.lib");
            boolean linkage = (throwable instanceof NoSuchFieldError) || (throwable instanceof NoSuchMethodError)
                    || (throwable instanceof ClassCastException) || (throwable instanceof NoClassDefFoundError)
                    || (throwable instanceof AbstractMethodError) || (throwable instanceof IncompatibleClassChangeError);
            if (bg && sdk && linkage) {
                Log.w(TAG, "★백그라운드 BYD SDK 예외 흡수(크래시 방지, thread=" + thread.getName() + ")", throwable);
                reportCaughtThrottled(throwable);
                return;   // prevHandler 미호출 = 프로세스 안 죽음(그 스레드만 종료)
            }
        } catch (Throwable ignored) {}
        try {
            // 크래시 정보 수집
            String stackTrace = Log.getStackTraceString(throwable);
            String model = Build.MODEL;
            String manufacturer = Build.MANUFACTURER;
            int sdkInt = Build.VERSION.SDK_INT;
            String threadName = thread != null ? thread.getName() : "unknown";

            // 스택트레이스에서 도메인명 추출 및 스킵리스트 추가
            String failedDomain = extractFailedDomain(stackTrace);
            if (failedDomain != null) {
                try {
                    SharedPreferences prefs = appContext.getSharedPreferences("bydsniffer_config", Context.MODE_PRIVATE);
                    Set<String> skipped = new HashSet<>(prefs.getStringSet("skipped_domains", new HashSet<>()));
                    skipped.add(failedDomain);
                    prefs.edit().putStringSet("skipped_domains", skipped).apply();
                    Log.i(TAG, "Added " + failedDomain + " to persistent skip list");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to update skip list", e);
                }
            }

            // 페이로드 구성
            String payload = String.format(
                "CRASH REPORT\n" +
                "Device: %s %s (" + Build.DISPLAY + ") relay v" + BuildConfig.VERSION_NAME + "\n" +
                "SDK: %d\n" +
                "Thread: %s\n" +
                "Time: %d\n" +
                "Failed Domain: %s\n" +
                "\n%s",
                manufacturer, model, sdkInt, threadName,
                System.currentTimeMillis(),
                failedDomain != null ? failedDomain : "unknown",
                stackTrace
            );

            // ★화면 토스트(2026-09-08): 로그를 못 보내는 테스터가 사진으로라도 예외를 전달할 수 있게. 메인 스레드 크래시면 루퍼가 죽어 못 띄움(MainActivity catch가 대신).
            try {
                final String tmsg = "링크락 릴레이 오류(" + threadName + "): " + throwable;
                if (appContext != null && thread != android.os.Looper.getMainLooper().getThread()) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(appContext, tmsg, android.widget.Toast.LENGTH_LONG).show());
                }
            } catch (Throwable ignored) {}

            // 동기 업로드 (별도 스레드, 4초 타임아웃)
            Thread uploadThread = new Thread(() -> {
                try {
                    uploadCrashReport(payload);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to upload crash report", e);
                }
            });
            uploadThread.setName("CrashReporter");
            uploadThread.start();

            try {
                uploadThread.join(4000); // 4초 대기
            } catch (InterruptedException e) {
                Log.e(TAG, "Crash upload interrupted", e);
            }

            // 기존 핸들러 호출 또는 프로세스 종료
            if (prevHandler != null) {
                prevHandler.uncaughtException(thread, throwable);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        } catch (Exception e) {
            Log.e(TAG, "Crash reporter itself crashed", e);
            // Fallback: 기존 핸들러 호출
            if (prevHandler != null) {
                prevHandler.uncaughtException(thread, throwable);
            }
        }
    }

    private static String extractFailedDomain(String stackTrace) {
        String[] patterns = {
            "BYDAutoRadarDevice", "radar",
            "BYDAutoBodyworkDevice", "bodywork",
            "BYDAutoPowerDevice", "power",
            "BYDAutoDoorLockDevice", "doorlock",
            "BYDAutoEngineDevice", "engine",
            "BYDAutoGearboxDevice", "gearbox",
            "BYDAutoInstrumentDevice", "instrument",
            "BYDAutoChargingDevice", "charging",
            "BYDAutoStatisticDevice", "statistic",
            "BYDAutoTyreDevice", "tyre",
            "BYDAutoSafetyBeltDevice", "safetybelt",
            "BYDAutoOtaDevice", "ota",
        };

        for (int i = 0; i < patterns.length; i += 2) {
            if (stackTrace.contains(patterns[i])) {
                return patterns[i + 1];
            }
        }
        return null;
    }

    private static void uploadCrashReport(String payload) throws Exception { uploadLog("CRASH", payload); }
    static void uploadLog(String category, String payload) throws Exception {
        URL url = new URL(CRASH_REPORT_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("X-Auth", SECRET);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(2500);
        conn.setReadTimeout(2500);

        org.json.JSONObject o = new org.json.JSONObject();
        o.put("ts", System.currentTimeMillis()); o.put("device", "dilink"); o.put("category", category); o.put("level", "error");
        o.put("message", payload.length() > 3900 ? payload.substring(0, 3900) : payload);
        try {
            String vin = appContext.getSharedPreferences(SnifferService.PREFS, Context.MODE_PRIVATE).getString(SnifferService.KEY_VIN, "");
            if (vin != null && vin.length() > 6) o.put("dev", vin.substring(vin.length() - 6));
        } catch (Throwable ignored) {}
        try (OutputStream os = conn.getOutputStream()) {
            os.write(o.toString().getBytes("UTF-8"));
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        Log.d(TAG, "Crash report uploaded: HTTP " + responseCode);
        conn.disconnect();
    }
}
