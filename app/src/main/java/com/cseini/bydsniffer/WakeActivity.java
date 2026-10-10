package com.cseini.bydsniffer;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

// ★v209 주차 중(시동/ACC off, 화면 완전 off) 화면 깨우기 — PowerManager(DEVICE_POWER 권한 필요, 거부됨) 대신
// Activity 윈도우 플래그(FLAG_TURN_SCREEN_ON 등)로 화면 켬. 권한 불필요·완전 꺼진 화면도 켜는 표준 경로(Electro 방식 추정).
// 투명 테마로 떠서 화면만 켜고 잠시 뒤 finish → 뒤의 a11y 네비게이션이 이어감.
public class WakeActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setTurnScreenOn(true);
                setShowWhenLocked(true);
            }
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {}
        // 화면 켜진 뒤 자동 종료(뒤의 a11y가 대상 앱 네비게이션 이어받음). 800ms 유지 후 finish.
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 800);
    }
}
