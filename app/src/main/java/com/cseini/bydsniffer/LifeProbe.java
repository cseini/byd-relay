package com.cseini.bydsniffer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Looper;

/**
 * ★685 오드식 상시생존 2단계의 첫 확인(읽기 전용) — 셸이 띄운 app_process 프로세스(앱 강제 종료에 안 죽는 형태)에서
 * 시스템 Context를 만들고 BYD SDK(바디워크·전원·SpecialDevice)를 부를 수 있는지만 본다. 어떤 설정도 바꾸지 않는다.
 * 실행: CLASSPATH=<릴레이 apk> app_process /system/bin --nice-name=snif_lifeprobe com.cseini.bydsniffer.LifeProbe
 * (오버드라이브 51.6 분석: ActivityThread.systemMain().getSystemContext() → SDK 호출)
 */
public final class LifeProbe {
    private static void p(String s) { System.out.println(s); }

    public static void main(String[] args) {
        try {
            p("프로세스 uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid());
            Looper.prepareMainLooper();
            Context sys;
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object th = at.getMethod("systemMain").invoke(null);
                sys = (Context) at.getMethod("getSystemContext").invoke(th);
                p("시스템 Context 획득=" + (sys != null) + (sys != null ? " pkg=" + sys.getPackageName() : ""));
            } catch (Throwable t) { p("시스템 Context 실패 " + describe(t)); System.exit(0); return; }

            ClassLoader cl = null;
            try {
                ApplicationInfo ai = sys.getPackageManager().getApplicationInfo("com.byd.data.collect", 0);
                cl = new dalvik.system.DexClassLoader(ai.sourceDir, "/data/local/tmp", ai.nativeLibraryDir, LifeProbe.class.getClassLoader());
                p("OEM 로더 생성 OK src=" + ai.sourceDir);
            } catch (Throwable t) { p("OEM 로더 실패 " + describe(t)); }

            String[][] devs = {
                {"bodywork", "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice", "getPowerLevel"},
                {"power", "android.hardware.bydauto.power.BYDAutoPowerDevice", null},
                {"special", "android.hardware.bydauto.special.BYDAutoSpecialDevice", null},
            };
            for (String[] d : devs) {
                try {
                    Class<?> k = null;
                    try { k = Class.forName(d[1]); p(d[0] + " — 시스템 클래스패스(기본 로더)에서 로드"); } catch (Throwable ig) { /* D3는 프레임워크에 있음, D5는 OEM APK */ }
                    if (k == null) {
                        if (cl == null) { p(d[0] + " — 기본 로더에 없고 OEM 로더도 없음"); continue; }
                        k = cl.loadClass(d[1]);
                    }
                    Object inst = k.getMethod("getInstance", Context.class).invoke(null, sys);
                    String res = d[0] + " getInstance=" + (inst == null ? "null" : "OK");
                    if (inst != null && d[2] != null) {
                        try { res += " " + d[2] + "=" + k.getMethod(d[2]).invoke(inst); } catch (Throwable t) { res += " " + d[2] + " 예외 " + describe(t); }
                    }
                    p(res);
                } catch (Throwable t) { p(d[0] + " 실패 " + describe(t)); }
            }
        } catch (Throwable t) { p("예외 " + describe(t)); }
        p("끝");
        System.exit(0);
    }

    private static String describe(Throwable t) {
        Throwable c = (t.getCause() != null) ? t.getCause() : t;
        String m = String.valueOf(c.getMessage());
        return c.getClass().getSimpleName() + ":" + (m.length() > 140 ? m.substring(0, 140) : m);
    }
}
