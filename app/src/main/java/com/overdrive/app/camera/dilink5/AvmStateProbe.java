package com.overdrive.app.camera.dilink5;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ★682 1단계(관측 전용) — 씨라7 순정 서라운드뷰(com.ts.avm.AvmAndroidService)의 상태값만 읽어 로그로 남긴다.
 * 오버드라이브 51.6 분석(docs/overdrive-51.6-analysis.md): 바인더 트랜잭션 1=상태 조회(int). 정지(5)·재개(4)는 이 단계에서 부르지 않는다.
 * 상태값의 뜻은 추정이라, 실차 값(카메라 성공/실패와 함께)을 모은 뒤 3단계(인수)에서만 사용한다.
 */
public final class AvmStateProbe {
    private AvmStateProbe() {}

    /** 상태 조회 결과 한 줄. 바인드/호출 실패도 이유를 문자열로 돌려준다(예외를 던지지 않음). 호출 스레드를 최대 ~4초 막는다. */
    public static String probe(Context ctx) {
        final IBinder[] holder = new IBinder[1];
        final CountDownLatch latch = new CountDownLatch(1);
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) { holder[0] = b; latch.countDown(); }
            @Override public void onServiceDisconnected(ComponentName n) {}
        };
        boolean bound = false;
        try {
            bound = ctx.getApplicationContext().bindService(new Intent().setComponent(new ComponentName("com.ts.avm", "com.ts.avm.AvmAndroidService")), conn, Context.BIND_AUTO_CREATE);
            if (!bound) return "AVM 상태 — bindService=false(서비스 없음/권한)";
            if (!latch.await(3000, TimeUnit.MILLISECONDS) || holder[0] == null) return "AVM 상태 — 3초 내 연결 안 됨";
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("com.ts.avm.IAvmServiceInterface");
                holder[0].transact(1, data, reply, 0);
                reply.readException();
                return "AVM 상태 — getState=" + reply.readInt();
            } finally { reply.recycle(); data.recycle(); }
        } catch (Throwable t) {
            return "AVM 상태 — 조회 예외 " + t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (bound) { try { ctx.getApplicationContext().unbindService(conn); } catch (Throwable ignore) {} }
        }
    }
}
