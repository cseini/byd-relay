package com.cseini.bydsniffer;

import android.content.Context;

// 전비/진단 수집기 — bydautotrip TripService 수집을 릴레이로 격리 이식(읽기전용 리플렉션).
//   돌핀=bydauto 부트클래스패스, 씨라이언7=DiLink5Support.ensure(dex주입)+AdbAccess self-grant 선행 필요.
//   시동ON 중 30초 주기로 getter를 relay_state에 넣음 → 돌핀락 앱 대시보드가 폴링 표시. setter 없음.
public final class TripCollector {
    private final SnifferService svc;
    private final Context ctx;
    private final VehicleContextWrapper vcw;
    private volatile boolean running = false;
    private Object statDev, acDev, instrDev, speedDev;
    private boolean probed = false;
    private static boolean valid(double v) { return !Double.isNaN(v) && v >= 0 && v < 100000; }

    public TripCollector(SnifferService s) { this.svc = s; this.ctx = s; this.vcw = new VehicleContextWrapper(s); }

    public void start() {
        if (running) return;
        running = true;
        new Thread(this::loop, "trip-collect").start();
    }
    public void stop() { running = false; }

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
                    try { f.setAccessible(true); if (!(f.get(dev) instanceof VehicleContextWrapper)) f.set(dev, vcw); } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
    }
    private double num(Object dev, String m, int... a) {
        if (dev == null) return Double.NaN;
        try {
            java.lang.reflect.Method mm = a.length == 0 ? dev.getClass().getMethod(m) : dev.getClass().getMethod(m, int.class);
            Object r = a.length == 0 ? mm.invoke(dev) : mm.invoke(dev, a[0]);
            return (r instanceof Number) ? ((Number) r).doubleValue() : Double.NaN;
        } catch (Throwable t) { return Double.NaN; }
    }

    private void loop() {
        // 씨라이언7이면 접근 선행(dex 주입 + self-grant). 돌핀은 no-op(이미 로드가능).
        try { if (DiLink5Support.isDiLink5()) { AdbAccess.ensureVehicleApiAccess(ctx); DiLink5Support.ensure(ctx); } } catch (Throwable ignored) {}
        while (running) {
            try {
                if (statDev == null) statDev = device("android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
                if (acDev == null) acDev = device("android.hardware.bydauto.ac.BYDAutoAcDevice");
                if (instrDev == null) instrDev = device("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
                if (speedDev == null) speedDev = device("android.hardware.bydauto.speed.BYDAutoSpeedDevice");

                double last50 = num(instrDev, "getLast50KmPowerConsume");   // 최근 50km 전비(kWh/100km)
                double odo = num(statDev, "getTotalMileageValue");          // 총주행거리 km
                double soc = num(statDev, "getElecPercentageValue");        // SOC %
                double inst = num(statDev, "getInstantElecConValue");       // 순간 전비
                double spd = num(speedDev, "getCurrentSpeed");              // 차속

                if (!Double.isNaN(last50)) svc.publishTrip("trip_eff", (int) Math.round(last50 * 10));   // ×10 정수(소수1자리 보존)
                if (!Double.isNaN(odo)) svc.publishTrip("trip_odo", (int) odo);
                if (!Double.isNaN(soc)) svc.publishTrip("trip_soc", (int) Math.round(soc));
                if (!Double.isNaN(inst)) svc.publishTrip("trip_inst", (int) Math.round(inst * 10));
                if (!Double.isNaN(spd)) svc.publishTrip("trip_speed", (int) Math.round(spd));
                // ★632 클러스터 개편 — 평균속도·이번 여정 거리/시간. 여정거리는 09-28 돌핀 실측으로 살아있음 확인(34.5km, 시동마다 0부터).
                //   평균속도·여정시간은 미실측이라 첫 회 원시값을 로그로 남겨 단위를 확인한다.
                double avgSpd = num(instrDev, "getAverageSpeed");
                double jkm = num(instrDev, "getCurrentJourneyDriveMileage");
                double jtime = num(instrDev, "getCurrentJourneyDriveTime");
                if (!probed) { probed = true; svc.postToD1("TRIP", "여정 게터 원시값 avgSpd=" + avgSpd + " jkm=" + jkm + " jtime=" + jtime); }
                if (valid(avgSpd)) svc.publishTrip("trip_avg_spd", (int) Math.round(avgSpd));
                if (valid(jkm)) svc.publishTrip("trip_jkm", (int) Math.round(jkm * 10));
                if (valid(jtime)) svc.publishTrip("trip_jtime", (int) Math.round(jtime));
            } catch (Throwable ignored) {}
            // 클러스터를 보는 동안엔 2초(순간 전비·속도가 살아 움직여야 함), 아니면 기존 30초.
            long wait = svc.clusterViewing() ? 2000 : 30000;
            try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
        }
    }
}
