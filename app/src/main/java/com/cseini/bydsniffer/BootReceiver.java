package com.cseini.bydsniffer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

// 헤드유닛 부팅(시동 ON 시 재부팅) 후 스니퍼 서비스 자동 시작 — 릴레이가 시동 사이클에도 살아있게.
public class BootReceiver extends BroadcastReceiver {
    // ★씨라이언7(DiLink5) 자동시작 시드 — 헤드유닛 내부 USB 허브(Microchip)가 시동 시 재연결되며
    //   USB_DEVICE_ATTACHED 브로드캐스트를 보낸다. 정적 등록이라 앱이 죽어 있어도 받는다(오토전비 v5.12 검증).
    //   돌핀(DiLink3)은 BOOT_COMPLETED/ACC_ON으로 시동 감지 → USB 시드는 D5 전용 보강, 무회귀.
    private static final int BYD_USB_VID = 1060;    // 0x0424
    private static final int BYD_USB_PID = 18848;   // 0x49A0

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        String src = "boot:" + action;
        // USB attach는 BYD 헤드유닛 허브(VID/PID 일치)일 때만 — USB 메모리 등 다른 기기는 무시.
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(action)) {
            android.hardware.usb.UsbDevice dev = intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE);
            if (dev == null || dev.getVendorId() != BYD_USB_VID || dev.getProductId() != BYD_USB_PID) {
                Log.i("BootReceiver", "USB attach 무시(BYD 허브 아님): " + (dev != null ? dev.getVendorId() + "/" + dev.getProductId() : "null"));
                return;
            }
            src = "usb-hub";
            Log.i("BootReceiver", "★BYD USB 허브 재연결(시동) → 서비스 시작");
        }
        // ★부팅 진단 — 시동 시 어떤 브로드캐스트가 실제 오는지 prefs에 누적(실차 검증용, onStartCommand가 상태로 push).
        try {
            SharedPreferences p = context.getSharedPreferences(SnifferService.PREFS, Context.MODE_PRIVATE);
            String act = action != null ? action.substring(action.lastIndexOf('.') + 1) : "?";
            String hist = act + " " + p.getString("boot_hist", "");
            if (hist.length() > 140) hist = hist.substring(0, 140);
            p.edit().putString("last_boot", action + "@" + System.currentTimeMillis())
                    .putString("boot_hist", hist).apply();
        } catch (Throwable ignored) {}

        Log.i("BootReceiver", "부팅 브로드캐스트 수신: " + action + " → 서비스 시작");
        Intent svc = new Intent(context, SnifferService.class);
        svc.putExtra("src", src);   // ★사인 추적 — 어떤 경로로 부활했는지 FORENSIC 로그에 남김
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            Log.e("BootReceiver", "서비스 시작 실패", e);
        }
    }
}
