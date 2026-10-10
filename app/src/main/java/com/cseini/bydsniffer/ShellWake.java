package com.cseini.bydsniffer;

/**
 * ★758 시동 꺼도 USB 유지 — 셸(uid 2000, DEVICE_POWER 있음)이 띄운 app_process에서 BYD 순정 깨우기와 똑같이
 * PowerManager.wakeUp(지금, 9, "ACC_ON")을 부른다(services.jar AccModeManagerService의 Utils.wakeup과 같은 호출 —
 * 10-05 09:37 일렉트로 셸 헬퍼가 이것으로 잠든 USB 허브·SD를 되살렸다). 앱 권한 깨우기(화면 깨우기 잠금·keyevent 224)는
 * 시동 OFF에선 안 먹었다(10-05 15:47 운영자 차 757: 둘 다 켜져 있었는데 5초 뒤 허브·SD 분리, Asleep).
 * 실행: CLASSPATH=<릴레이 apk> app_process /system/bin --nice-name=snif_wake com.cseini.bydsniffer.ShellWake
 */
public final class ShellWake {
    public static void main(String[] args) {
        try {
            Object b = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "power");
            Object pm = Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
            pm.getClass().getMethod("wakeUp", long.class, int.class, String.class, String.class)
                    .invoke(pm, android.os.SystemClock.uptimeMillis(), 9, "ACC_ON", "com.android.shell");
            System.out.println("ok");
        } catch (Throwable t) {
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            System.out.println("실패 " + c.getClass().getSimpleName() + ": " + c.getMessage());
        }
        System.exit(0);
    }
}
