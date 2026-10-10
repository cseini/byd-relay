package com.cseini.bydsniffer;

import android.content.Context;

// 씨라이언7(DiLink5) 진단 접근 검증용 읽기전용 프로브 — bydautotrip TripService의 read-probe 이식(setter 없음).
//   runReadProbe: 각 device getInstance 성패 + getter 개수 + listener 추상콜백 이름 열거.
//   pollProbe: 핵심 getter 값 스냅샷(전비·차속·기어·벨트 등). 전부 리플렉션·읽기전용이라 부작용 없음.
public final class D5Probe {
    private final VehicleContextWrapper vcw;
    private Object acDev, instrDev;

    public D5Probe(Context c) { this.vcw = new VehicleContextWrapper(c); }

    private static final String[] PROBE_DEVICES = {
            "android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice",
            "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice",
            "android.hardware.bydauto.adas.BYDAutoADASDevice",
            "android.hardware.bydauto.setting.BYDAutoSettingDevice",
            "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
            "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
            "android.hardware.bydauto.charging.BYDAutoChargingDevice",
            "android.hardware.bydauto.light.BYDAutoLightDevice",
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
    };

    private static String alias(String cn) {
        return cn.substring(cn.lastIndexOf('.') + 1).replace("BYDAuto", "").replace("Device", "");
    }

    private Object device(String cls) {
        try {
            Object d = Class.forName(cls).getMethod("getInstance", Context.class).invoke(null, vcw);
            if (d != null) injectContext(d);
            return d;
        } catch (Throwable t) { return null; }
    }

    private void injectContext(Object dev) {
        Class<?> c = dev.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (Context.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        if (!(f.get(dev) instanceof VehicleContextWrapper)) f.set(dev, vcw);
                    } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
    }

    private String probeCall(Object dev, String name, int... a) {
        if (dev == null) return "noDev";
        try {
            java.lang.reflect.Method m = a.length == 0
                    ? dev.getClass().getMethod(name)
                    : dev.getClass().getMethod(name, int.class);
            Object r = a.length == 0 ? m.invoke(dev) : m.invoke(dev, a[0]);
            return String.valueOf(r);
        } catch (NoSuchMethodException e) {
            return "noM";
        } catch (Throwable t) {
            return "e:" + t.getClass().getSimpleName();
        }
    }

    // getInstance 성패 + getter 개수 + listener 추상클래스 on* 콜백 이름 열거.
    public String runReadProbe() {
        StringBuilder out = new StringBuilder();
        for (String cn : PROBE_DEVICES) {
            String a = alias(cn);
            try {
                Class<?> dc = Class.forName(cn);
                Object dev = dc.getMethod("getInstance", Context.class).invoke(null, vcw);
                if (dev == null) { out.append(a).append(":X "); continue; }
                injectContext(dev);
                int getters = 0;
                for (java.lang.reflect.Method m : dc.getMethods()) if (m.getName().startsWith("get")) getters++;
                StringBuilder cbs = new StringBuilder();
                try {
                    String ln = cn.substring(0, cn.lastIndexOf('.') + 1) + "Abs"
                            + cn.substring(cn.lastIndexOf('.') + 1).replace("Device", "Listener");
                    Class<?> lc = Class.forName(ln);
                    for (java.lang.reflect.Method m : lc.getMethods()) if (m.getName().startsWith("on")) cbs.append(m.getName()).append(",");
                } catch (Throwable t) { cbs.append("noAbs"); }
                out.append(a).append(":O/get").append(getters).append("/cb:").append(cbs).append(" ");
            } catch (Throwable t) {
                out.append(a).append(":ERR(").append(t.getClass().getSimpleName()).append(") ");
            }
        }
        return out.toString().trim();
    }

    // 핵심 getter 값 스냅샷(읽기전용) — 릴레이가 수집할 후보 항목.
    public String pollProbe() {
        Object gear = device("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
        Object speed = device("android.hardware.bydauto.speed.BYDAutoSpeedDevice");
        Object setg = device("android.hardware.bydauto.setting.BYDAutoSettingDevice");
        acDev = device("android.hardware.bydauto.ac.BYDAutoAcDevice");
        instrDev = device("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
        StringBuilder out = new StringBuilder();
        out.append("gear=").append(probeCall(gear, "getCurrentGear"));
        out.append(" spd=").append(probeCall(speed, "getCurrentSpeed"));
        out.append(" last50=").append(probeCall(instrDev, "getLast50KmPowerConsume"));
        out.append(" acOnline=").append(probeCall(acDev, "getAcOnlineState"));
        out.append(" outT=").append(probeCall(acDev, "getTemprature", 4));
        out.append(" belt0=").append(probeCall(instrDev, "getSafetyBeltStatus", 0));
        out.append(" belt1=").append(probeCall(instrDev, "getSafetyBeltStatus", 1));
        out.append(" seatV1=").append(probeCall(setg, "getSeatVentilatingState", 1));
        out.append(" steerH=").append(probeCall(setg, "getSteeringWheelHeatingState"));
        return out.toString();
    }
}
