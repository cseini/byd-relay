package com.cseini.bydsniffer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// ★DiLink5(씨라이언7 등, Android 11/SDK30+) 실험 지원.
//   DiLink3(돌핀)와 달리 android.hardware.bydauto.* 가 부트클래스패스에 없고 시스템앱
//   com.byd.data.collect APK 안에 번들돼 있어 Class.forName이 실패한다. 그 APK의 dex를
//   메모리로 읽어 우리 앱 classloader에 붙이면 기존 리플렉션 경로(device()/subscribeDomain)가
//   그대로 실제 클래스를 잡는다. hidden 멤버 접근(dalvik.system.*)은 TripApp의
//   setHiddenApiExemptions("L")가 이미 열어둔다. DiLink3에선 isDiLink5()=false라 아무것도 안 함.
//   ※씨라이언7 미검증(운영자 차량은 돌핀) — 실차 피드백으로 조정.
public final class DiLink5Support {
    private static final String TAG = "DiLink5Support";
    private static final String OEM_PKG = "com.byd.data.collect";
    private static final String PROBE_SPEED = "android.hardware.bydauto.speed.BYDAutoSpeedDevice";   // ★loaderD5 검증용(BeetleLauncher BydSdk.PROBE와 동일)
    private static boolean injected = false;
    private static volatile ClassLoader d5Loader;   // ★2026-09-19 BeetleLauncher 방식 별도 로더(InMemoryDexClassLoader + HiddenApiBypass) — 이벤트 등록용
    private static boolean hiddenExempted;
    public static volatile String lastReport = "(injection 미실행 — DiLink3이거나 아직 시작 전)";
    public static volatile String loaderReport = "(D5 loader 미실행)";
    // ★580(1단계) — 위험 호출(InMemory 생성+검증) 타임아웃 격리용. v543 hang 교훈(초입 무거운코드 금지·try/catch로 hang 못막음)
    //   재현. 이번 프로세스 생애주기 동안 한 번 타임아웃되면 재시도 안 함(도메인마다 8초씩 반복 방지) — 다음 재부팅에 재시도.
    private static volatile boolean d5LoaderGaveUp = false;
    public static volatile String d5Stage = "idle";   // ★관측용 단계 마커(putStatus로 올림) — hang 중에도 살아있어 멈춘 직전 단계=hang 위치.
    private static final String BEETLE_LISTENER_ASSET = "beetle-sdk-listener.dex";

    private DiLink5Support() {}

    // ★579 헤드유닛 기기ID(8 hex 소문자) — 오토전비 DiLink5Support.deviceTag()와 **바이트 단위로 동일**해야 한다
    //   (서버 통합 사용자 명부가 VIN→relay_state.hu_id→오토전비 dev→차지로그 device_tag를 한 줄로 잇는다).
    //   식: SHA-256(ANDROID_ID) 앞 4바이트를 %02x. 조금이라도 다르면(md5·16hex·대문자) 안 맞으니 그대로 유지.
    public static String deviceTag(android.content.Context ctx) {
        try {
            String aid = android.provider.Settings.Secure.getString(ctx.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (aid == null || aid.isEmpty()) return "unk";
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(aid.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Throwable t) { return "unk"; }
    }

    // 진단용 — ro.vehicle.type 원문 노출.
    public static String vehicleType() {
        String vt = sysProp("ro.vehicle.type");
        return vt == null ? "(읽기 실패)" : vt;
    }

    // DiLink5 헤드유닛 판별 — ro.vehicle.type 이 "Di5"로 시작(씨라이언7 = Di5.0_DXF_W).
    public static boolean isDiLink5() {
        if (Build.VERSION.SDK_INT < 30) return false;
        String vt = sysProp("ro.vehicle.type");
        return vt != null && vt.startsWith("Di5");
    }

    private static String sysProp(String key) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, key);
        } catch (Throwable t) { return null; }
    }

    // com.byd.data.collect 의 dex를 우리 classloader에 주입(1회). 이미 잡히면 no-op.
    public static synchronized void ensure(Context ctx) {
        if (injected) return;
        ClassLoader loader = ctx.getClassLoader();
        if (probe(loader)) { injected = true; lastReport = "이미 로드가능(주입 불필요)"; return; }
        try {
            List<String> apks = oemApks(ctx);
            if (apks.isEmpty()) { lastReport = OEM_PKG + " 미설치 — 주입 불가"; Log.w(TAG, lastReport); return; }

            Class<?> baseCl = Class.forName("dalvik.system.BaseDexClassLoader");
            java.lang.reflect.Field pathListF = baseCl.getDeclaredField("pathList");
            pathListF.setAccessible(true);
            Object pathList = pathListF.get(loader);

            java.lang.reflect.Field elF = pathList.getClass().getDeclaredField("dexElements");
            elF.setAccessible(true);
            Object[] cur = (Object[]) elF.get(pathList);

            Object[] add = inMemoryElements(pathList.getClass(), apks);
            if (add == null || add.length == 0) { Log.w(TAG, "dex element 생성 실패"); return; }

            // append(뒤에 추가) — 우리 앱 클래스가 이름 충돌 시 우선(OEM 번들 라이브러리 shadow 방지).
            Object[] merged = (Object[]) java.lang.reflect.Array.newInstance(
                    cur.getClass().getComponentType(), cur.length + add.length);
            System.arraycopy(cur, 0, merged, 0, cur.length);
            System.arraycopy(add, 0, merged, cur.length, add.length);
            elF.set(pathList, merged);

            injected = probe(loader);
            lastReport = "dex " + add.length + "개 주입(apk " + apks.size() + ") · bydauto loadable=" + injected;
            Log.i(TAG, lastReport);
        } catch (Throwable t) {
            lastReport = "주입 실패: " + t;
            Log.w(TAG, lastReport);
        }
    }

    // ★bydcontroller(com.ik.bydcontroller) 방식 — 특정 bydauto 장치를 그 기능을 소유한 BYD 시스템앱 APK에서
    //   DexClassLoader로 로드해 getInstance. data.collect 주입 경로가 실패(권한/InvocationTargetException)하는 장치(AC·bodywork)를
    //   소유 시스템앱(com.byd.hvac=공조, com.byd.carsettings/mycar=바디워크·설정) SDK로 잡으면 권한 벽을 넘는다. 캐시(클래스로더 재사용).
    private static final String[] SDK_PACKAGES = {"com.byd.hvac", "com.byd.carsettings", "com.byd.mycar", "com.byd.scenemode", "com.byd.autoservice"};
    private static final java.util.Map<String, ClassLoader> sdkLoaders = new java.util.HashMap<>();
    public static volatile String lastSysReport = "";
    public static synchronized Object deviceFromSystemApp(Context ctx, String className, Context deviceCtx) {
        StringBuilder log = new StringBuilder();
        for (String pkg : SDK_PACKAGES) {
            try {
                ClassLoader cl = sdkLoaders.get(pkg);
                if (cl == null) {
                    ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
                    if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) { log.append(pkg).append("=비시스템앱 "); continue; }
                    StringBuilder sb = new StringBuilder(ai.sourceDir);
                    if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) sb.append(java.io.File.pathSeparator).append(s);
                    cl = new dalvik.system.DexClassLoader(sb.toString(), ctx.getCodeCacheDir().getAbsolutePath(), ai.nativeLibraryDir, ctx.getClassLoader());
                    sdkLoaders.put(pkg, cl);
                }
                Class<?> cls = cl.loadClass(className);
                Object dev = cls.getMethod("getInstance", Context.class).invoke(null, deviceCtx);
                if (dev == null) log.append(pkg).append("=getInstance null ");
                if (dev != null) { lastSysReport = className.substring(className.lastIndexOf('.') + 1) + " ← " + pkg; Log.i(TAG, lastSysReport); return dev; }
                log.append(pkg).append("=null ");
            } catch (Throwable t) {
                Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
                String cm = c.getMessage(); if (cm != null && cm.length() > 90) cm = cm.substring(0, 90);   // ★678 예외 메시지(누락 클래스·권한 사유)까지
                log.append(pkg).append("=").append(c.getClass().getSimpleName()).append(cm != null ? "(" + cm + ")" : "").append(" ");
            }
        }
        lastSysReport = className.substring(className.lastIndexOf('.') + 1) + " 시스템앱 로드 실패: " + log;
        Log.w(TAG, lastSysReport);
        return null;
    }

    // ★677 진단 — 씨라7 전부에서 BYDAutoSpecialDevice를 못 잡음(D5 sentry wake 미동작, 10-01). 어느 시스템 앱 APK에 어떤 이름의 클래스가 있는지 한 줄로.
    //   (오버드라이브는 같은 클래스명 'android.hardware.bydauto.special.BYDAutoSpecialDevice' 로 getInstance 하는데 우리는 null이었다 — 클래스 부재인지, 로드 실패인지 가린다.)
    @SuppressWarnings("deprecation")
    public static String findSdkClasses(Context ctx, String needle) {
        StringBuilder out = new StringBuilder();
        java.util.List<String> pkgs = new ArrayList<>(java.util.Arrays.asList(SDK_PACKAGES));
        pkgs.add(OEM_PKG);
        for (String pkg : pkgs) {
            try {
                ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
                StringBuilder found = new StringBuilder(); int n = 0;
                java.util.List<String> paths = new ArrayList<>(); paths.add(ai.sourceDir);
                if (ai.splitSourceDirs != null) paths.addAll(java.util.Arrays.asList(ai.splitSourceDirs));
                for (String path : paths) {
                    dalvik.system.DexFile df = null;
                    try {
                        df = new dalvik.system.DexFile(path);
                        java.util.Enumeration<String> en = df.entries();
                        while (en.hasMoreElements() && n < 6) { String cn = en.nextElement(); if (cn.contains(needle)) { found.append(cn).append(','); n++; } }
                    } catch (Throwable ignored) {} finally { try { if (df != null) df.close(); } catch (Throwable ignored) {} }
                }
                out.append(pkg).append('=').append(found.length() == 0 ? "없음" : found).append(' ');
            } catch (Throwable t) { out.append(pkg).append("=미설치 "); }
        }
        return out.toString();
    }

    private static boolean probe(ClassLoader loader) {
        try {
            Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice", false, loader);
            return true;
        } catch (Throwable t) { return false; }
    }

    private static List<String> oemApks(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(OEM_PKG, 0);
            if (ai.sourceDir != null) out.add(ai.sourceDir);
            if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) out.add(s);
        } catch (Throwable ignored) {}
        return out;
    }

    // 각 APK의 classesN.dex를 메모리로 읽어 DexPathList.makeInMemoryDexElements 로 element 생성.
    //   in-memory(파일 아님)라 ART가 이 classloader 컨텍스트에서 새로 검증 — 시스템앱 원본 oat 재사용
    //   으로 일부 클래스 검증이 드롭되는 문제를 피한다. makeInMemoryDexElements 는 API26+ 상시 존재.
    private static Object[] inMemoryElements(Class<?> dexListCls, List<String> apks) {
        try {
            java.lang.reflect.Method m = dexListCls.getDeclaredMethod(
                    "makeInMemoryDexElements", ByteBuffer[].class, List.class);
            m.setAccessible(true);

            List<ByteBuffer> buffers = new ArrayList<>();
            for (String path : apks) {
                try (ZipFile zip = new ZipFile(path)) {
                    java.util.Enumeration<? extends ZipEntry> e = zip.entries();
                    while (e.hasMoreElements()) {
                        ZipEntry entry = e.nextElement();
                        String n = entry.getName();
                        if (n.startsWith("classes") && n.endsWith(".dex") && !n.contains("/")) {
                            buffers.add(ByteBuffer.wrap(readAll(zip.getInputStream(entry))));
                        }
                    }
                } catch (Throwable t) { Log.w(TAG, "dex 읽기 실패 " + path + ": " + t); }
            }
            if (buffers.isEmpty()) return null;
            return (Object[]) m.invoke(null,
                    buffers.toArray(new ByteBuffer[0]), new ArrayList<Exception>());
        } catch (Throwable t) {
            Log.w(TAG, "makeInMemoryDexElements 실패: " + t);
            return null;
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        in.close();
        return bos.toByteArray();
    }

    // ★2026-09-19 hidden API 우회 — BeetleLauncher 1.70이 D5 이벤트를 받는 핵심(BydSdk.exemptHiddenApi 이식).
    //   우리 setHiddenApiExemptions("L")은 D5(SDK30+)서 안 먹혀 bydauto registerListener 실구현이 hidden으로 가려져
    //   스텁 폴백이던 것(v538 실패). lsposed HiddenApiBypass가 meta-reflection으로 확실히 예외 처리한다.
    private static synchronized void exemptHiddenApi() {
        if (hiddenExempted) return;
        hiddenExempted = true;
        try {
            boolean ok = org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Lcom/byd/", "Landroid/hardware/bydauto/", "Lcom/ts/");
            Log.i(TAG, "HiddenApiBypass 예외: " + (ok ? "설정됨" : "실패"));
        } catch (Throwable t) { Log.w(TAG, "HiddenApiBypass 실패: " + t); }
    }

    // ★2026-09-19 D5 이벤트용 별도 로더(BeetleLauncher BydSdk.resolve 이식) — OEM(com.byd.data.collect) dex를
    //   앱 pathList에 append하던 v538(ensure)과 달리, 별도 InMemoryDexClassLoader(parent=앱로더)로 로드한다.
    //   append+setHiddenApiExemptions("L")은 registerListener가 Stub!로 가려졌으나(전 도메인 실패), 비틀런처는 이 방식으로 실구현 도달.
    //   시스템 클래스패스에 있으면(D3) 앱로더 그대로 반환. 캐시.
    public static synchronized ClassLoader loaderD5(Context ctx) {
        if (d5Loader != null) return d5Loader;
        if (d5LoaderGaveUp) return null;   // ★이번 프로세스에서 이미 타임아웃 — 도메인마다 8초씩 재시도 방지(다음 재부팅에 재시도)
        exemptHiddenApi();
        ClassLoader appLoader = ctx.getClassLoader();
        // ★2026-09-19 v546 회귀복구 — v543의 isDiLink5() InMemory 강제생성이 initDevicesD5 hang 유발(012772 v543 죽음).
        //   v542 동등으로 원복: append로 로드가능하면 appLoader 반환(InMemory 미생성=hang 없음).
        try { Class.forName(PROBE_SPEED, false, appLoader); d5Loader = appLoader; loaderReport = "D5 loader: 시스템 클래스패스(D3/append)"; Log.i(TAG, loaderReport); return d5Loader; } catch (Throwable ignored) {}
        List<String> apks = oemApks(ctx);
        if (apks.isEmpty()) { loaderReport = "D5 loader 불가: " + OEM_PKG + " 미설치"; Log.w(TAG, loaderReport); return null; }
        // ★580(1단계, 09-24 비틀런처 D5(1.70) 디컴파일 실측) — BydSdk.resolve()와 완전히 동일한 조합 재현:
        //   OEM(com.byd.data.collect) dex + beetle-sdk-listener.dex(비틀런처 실제 프로덕션 리스너 DoorListener/InstrumentListener 등,
        //   Probe*Listener 28종은 meter_test 디버그 전용이라 무관)를 **같은 InMemoryDexClassLoader**에 함께 로드.
        //   우리는 그동안 OEM dex만 넣었었다 — 비틀런처와의 유일한 실질 차이(로더·권한·device획득은 이미 100% 동일 확인됨).
        //   ★v543 교훈 — InMemory 생성+검증(Class.forName)은 hang 위험(try/catch로 못 잡음) → 별도 데몬스레드+8초 타임아웃으로 격리.
        //   타임아웃되면 이번 부팅은 이벤트 스킵(기존 게터·기능엔 영향 없음), 백그라운드 스레드는 데몬이라 계속 hang해도 무해.
        final ClassLoader[] result = new ClassLoader[1];
        final String[] report = new String[1];
        Thread warm = new Thread(() -> {
            try {
                d5Stage = "loader_apk_scan";
                List<ByteBuffer> buffers = new ArrayList<>();
                for (String path : apks) {
                    try (ZipFile zip = new ZipFile(path)) {
                        java.util.Enumeration<? extends ZipEntry> e = zip.entries();
                        while (e.hasMoreElements()) {
                            ZipEntry entry = e.nextElement();
                            String n = entry.getName();
                            if (n.startsWith("classes") && n.endsWith(".dex") && !n.contains("/"))
                                buffers.add(ByteBuffer.wrap(readAll(zip.getInputStream(entry))));
                        }
                    }
                }
                if (buffers.isEmpty()) { report[0] = "D5 loader 실패: dex 없음"; return; }
                int oemCount = buffers.size();
                d5Stage = "loader_beetle_dex_read";
                try {
                    byte[] bl = readAll(ctx.getAssets().open(BEETLE_LISTENER_ASSET));
                    buffers.add(ByteBuffer.wrap(bl));
                    Log.i(TAG, "beetle-sdk-listener.dex 포함(" + bl.length + "B)");
                } catch (Throwable tb) {
                    Log.w(TAG, "beetle-sdk-listener.dex 없음(OEM dex " + oemCount + "개만으로 계속): " + tb);
                }
                d5Stage = "loader_inmemory_creating";
                dalvik.system.InMemoryDexClassLoader l = new dalvik.system.InMemoryDexClassLoader(buffers.toArray(new ByteBuffer[0]), appLoader);
                d5Stage = "loader_inmemory_created";
                Class.forName(PROBE_SPEED, false, l);   // 검증 — 실 SDK 로드 확인
                d5Stage = "loader_verified";
                result[0] = l;
                report[0] = "D5 loader: " + buffers.size() + " dex(oem " + oemCount + "+beetle) + HiddenApiBypass (InMemoryDexClassLoader)";
            } catch (Throwable t) {
                report[0] = "D5 loader 실패: " + t;
            }
        }, "d5-loader-warmup");
        warm.setDaemon(true);
        warm.start();
        try { warm.join(8000); } catch (InterruptedException ignored) {}
        if (result[0] != null) {
            d5Loader = result[0];
            loaderReport = report[0];
            Log.i(TAG, loaderReport);
            return d5Loader;
        }
        d5LoaderGaveUp = true;
        loaderReport = warm.isAlive() ? "D5 loader 타임아웃(8s, stage=" + d5Stage + ") — 이번 부팅 이벤트 스킵" : (report[0] != null ? report[0] : "D5 loader 실패(알수없음)");
        Log.w(TAG, loaderReport);
        return null;
    }

    // D5 이벤트용 — 별도 로더로 bydauto 클래스 로드(registerListener 실구현 도달용). null이면 로더 불가.
    public static Class<?> loadD5Class(Context ctx, String fqn) {
        ClassLoader l = loaderD5(ctx);
        if (l == null) return null;
        try { return Class.forName(fqn, true, l); } catch (Throwable t) { Log.w(TAG, fqn + " (D5 loader) 로드 실패: " + t); return null; }
    }

}
