package com.cseini.bydsniffer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.SystemClock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 씨라7(DiLink5) 주차 중 유지 — 차량 네트워크 관리(CAN NM) 메시지. 오버드라이브 v54.2 Di5QnxNetworkControlClient와 같은 프로토콜:
 * com.ts.ota.otasdkmgr/.OtaTsUpdateService에 bindService → IOtaSdkManager 코드 135에 [int 0][int 0=시작|1=정지][콜백 바인더]를 보내고
 * 콜백(IAbsMessageCallback 코드 11)으로 오는 int를 최대 4초 기다린다. 오버드라이브는 셸 데몬이라 앱 수신기를 거치지만 우리는 앱 프로세스라 직접 바인드한다.
 * 결과 코드: 콜백 값(오버드라이브 기준 1=확인) · 2=보냈지만 콜백 없음 · -101 바인드 실패 · -102 전송 거절 · -103 서비스 없음/비공개/서술자 불일치 · -104 예외.
 */
final class D5QnxNet {
    private static final String PKG = "com.ts.ota.otasdkmgr";
    private static final String SVC = "com.ts.ota.otasdkmgr.OtaTsUpdateService";
    private static final String DESC = "com.ts.ota.otasdkmgr.IOtaSdkManager";
    private static final String CB_DESC = "com.ts.ota.otasdkmgr.IAbsMessageCallback";
    private static final int TX_SEND_CAN_NM = 135;

    private static final Object LOCK = new Object();
    private static IBinder binder;
    private static boolean bound;
    private static CountDownLatch latch = new CountDownLatch(1);
    private static final Callback CALLBACK = new Callback();

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            synchronized (LOCK) { binder = b; latch.countDown(); }
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            synchronized (LOCK) { binder = null; latch = new CountDownLatch(1); }
        }
        @Override public void onBindingDied(ComponentName n) {
            synchronized (LOCK) { binder = null; bound = false; latch = new CountDownLatch(1); }
        }
        @Override public void onNullBinding(ComponentName n) {
            synchronized (LOCK) { binder = null; latch.countDown(); }
        }
    };

    private static final class Callback extends Binder implements IInterface {
        private final Object mon = new Object();
        private long seq;
        private int value;

        Callback() { attachInterface(this, CB_DESC); }

        @Override public IBinder asBinder() { return this; }

        long seq() { synchronized (mon) { return seq; } }

        Integer await(long afterSeq) {
            long end = SystemClock.elapsedRealtime() + 4000L;
            synchronized (mon) {
                while (seq <= afterSeq) {
                    long left = end - SystemClock.elapsedRealtime();
                    if (left <= 0) return null;
                    try { mon.wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
                }
                return value;
            }
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CB_DESC);
                return true;
            }
            if (code != 11) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(CB_DESC);
            int v = data.readInt();
            synchronized (mon) { value = v; seq++; mon.notifyAll(); }
            if (reply != null) reply.writeNoException();
            return true;
        }
    }

    private D5QnxNet() {}

    /** cmd 0=시작(네트워크 깨어 있기 요청) 1=정지. 워커 스레드에서만 부를 것(최대 약 10초 막힘). */
    static int control(Context ctx, int cmd) {
        synchronized (LOCK) {
            try {
                ServiceInfo si;
                try { si = ctx.getPackageManager().getServiceInfo(new ComponentName(PKG, SVC), 0); }
                catch (PackageManager.NameNotFoundException e) { return -103; }
                if (si == null || !si.enabled || !si.exported) return -103;
                IBinder b = ensureBound(ctx);
                if (b == null) return -101;
                if (!DESC.equals(b.getInterfaceDescriptor())) { unbind(ctx); return -103; }
                long before = CALLBACK.seq();
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESC);
                    data.writeInt(0);
                    data.writeInt(cmd);
                    data.writeStrongBinder(CALLBACK);
                    if (!b.transact(TX_SEND_CAN_NM, data, reply, 0)) { unbind(ctx); return -102; }
                    reply.readException();
                    if (reply.readInt() == 0) return -102;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
                Integer v = CALLBACK.await(before);
                return v == null ? 2 : v;
            } catch (Throwable t) {
                unbind(ctx);
                return -104;
            }
        }
    }

    /** 바인딩만 끊는다(정지 메시지는 보내지 않음) — 시동이 켜질 때처럼 차가 곧 직접 맡는 경우. */
    static void detach(Context ctx) {
        synchronized (LOCK) { unbind(ctx); }
    }

    private static IBinder ensureBound(Context ctx) throws InterruptedException {
        if (binder != null && binder.isBinderAlive()) return binder;
        if (!bound) {
            latch = new CountDownLatch(1);
            Intent it = new Intent("android.Otasdk.OtaTsUpdateService").setComponent(new ComponentName(PKG, SVC));
            bound = ctx.bindService(it, CONN, Context.BIND_AUTO_CREATE);
            if (!bound) return null;
        }
        return awaitConnected(latch) && binder != null && binder.isBinderAlive() ? binder : null;
    }

    private static boolean awaitConnected(CountDownLatch l) throws InterruptedException {
        // control()의 synchronized(LOCK) 안에서 부른다 — LOCK.wait로 잠금을 놓아야 연결 콜백(LOCK 필요)이 latch를 내릴 수 있다
        long end = SystemClock.elapsedRealtime() + 3000L;
        while (l.getCount() > 0) {
            long left = end - SystemClock.elapsedRealtime();
            if (left <= 0) return false;
            LOCK.wait(Math.min(left, 200L));
        }
        return true;
    }

    private static void unbind(Context ctx) {
        binder = null;
        if (bound) {
            try { ctx.unbindService(CONN); } catch (Throwable ignored) {}
            bound = false;
        }
        latch = new CountDownLatch(1);
    }
}
