package com.cseini.bydsniffer;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

// ★v214 :relay 프로세스 warm 유지 앵커 — a11y(KeepAliveAccessibilityService)가 :relay 단독 프로세스라,
// 딥슬립/자기업데이트 후 onServiceConnected 전에 프로세스가 얼면 goForeground를 못 해 a11y가 영영 바인드 안 되던 문제(닭-달걀) 해결.
// 메인(SnifferService)이 이걸 주기적으로 시작 → startService가 :relay를 깨움 → 포그라운드로 얼지 않게 유지 → a11y 정상 바인드.
public class RelayAnchorService extends Service {
    static final String CH = "relay_anchor";
    static final int FG_ID = 4481;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                NotificationChannel ch = new NotificationChannel(CH, "릴레이 앵커", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification n = new Notification.Builder(this, CH)
                    .setContentTitle("링크락 상시생존")
                    .setContentText("접근성 앵커 유지")
                    .setSmallIcon(android.R.drawable.presence_online)
                    .setOngoing(true)
                    .build();
            startForeground(FG_ID, n);
        } catch (Throwable ignored) {}
        return START_STICKY;
    }

    // 태스크 청소로 죽으면 1초 뒤 부활(:relay 항상 warm).
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            Intent restart = new Intent(this, RelayAnchorService.class);
            PendingIntent pi = PendingIntent.getService(this, 0, restart,
                    PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            long at = System.currentTimeMillis() + 1000;
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Throwable ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ★v221 예외를 삼키지 않고 전파 — 호출자(startRelayAnchorLoop)가 ForegroundServiceStartNotAllowedException 등을 로깅해
    //   "앵커 호출됐으나 거부" vs "호출 안 됨"을 구분(a11y 좀비 근본조사).
    static void start(Context ctx) {
        Intent i = new Intent(ctx, RelayAnchorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }
}
