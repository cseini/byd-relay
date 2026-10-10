package com.cseini.bydsniffer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

// ★분할 고스트(2026-09-11, BydAutoSplit 이식) — 셸 `am start --windowingMode 3`로 띄워 split-screen-primary 도킹 스택을 만드는 용도.
//   대상 앱이 직접 분할에 못 들어갈 때 이 스택으로 `am stack move-task`. 4.5초 뒤 스스로 종료(그 안에 move-task 완료).
//   매니페스트: exported=true(셸 실행 필수) · resizeableActivity=true · excludeFromRecents.
public final class SplitGhostActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable finishTask = () -> { if (!isFinishing()) finish(); };

    @Override protected void onCreate(Bundle b) { super.onCreate(b); }
    @Override protected void onResume() { super.onResume(); handler.removeCallbacks(finishTask); handler.postDelayed(finishTask, 4500); }
    @Override protected void onDestroy() { handler.removeCallbacks(finishTask); super.onDestroy(); }
}
