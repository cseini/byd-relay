package com.cseini.bydsniffer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

// ★v574 시동 ON 업데이트 안내(운영자 지시 2026-09-23) — 자동 업데이트가 조용히 실패하면 사용자는 영영 모른다.
//   실측: 활성 릴레이가 546(11대)·509~558(산발)에 정체돼 있었고, 성공한 업데이트는 대부분 사용자가 앱에서
//   직접 "업데이트 확인"을 누른 경우였다. 그래서 **시동을 켤 때 화면에 띄워** 한 번은 보이게 한다.
//
//   ★안전: 띄우는 조건은 SnifferService가 판단한다(시동 ON + 기어 P + 하루 1회). 여기서는 **주행 시작하면
//   스스로 닫는다** — onStop에서 finish. 운전 중 화면을 덮는 일은 없어야 한다.
//   ★이 ROM은 백그라운드 Activity 실행을 허용한다(RelayPokeActivity가 같은 방식으로 이미 동작 중).
public class UpdatePromptActivity extends Activity {
    public static final String EXTRA_CUR = "cur";
    public static final String EXTRA_NEW = "latest";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true);
        } catch (Throwable ignored) {}

        int cur = getIntent() != null ? getIntent().getIntExtra(EXTRA_CUR, 0) : 0;
        int latest = getIntent() != null ? getIntent().getIntExtra(EXTRA_NEW, 0) : 0;

        String msg = "링크락 릴레이 새 버전이 있습니다.\n\n"
                + "현재 v" + cur + "  →  새 버전 v" + latest + "\n\n"
                + "설치 중 앱이 한 번 재시작됩니다. 1~2분 걸립니다.";

        // ★취소 가능 — 뒤로/바깥 탭으로도 닫히게 둔다. 운전 직전 화면을 붙잡아두면 그게 더 위험하다("나중에"와 동일 취급).
        new AlertDialog.Builder(this)
                .setTitle("릴레이 업데이트")
                .setMessage(msg)
                .setOnCancelListener(d -> finish())
                .setPositiveButton("지금 설치", (d, w) -> {
                    // ★설치는 MainActivity의 기존 경로(Updater.updateNow)로 넘긴다. selfUpdate()를 직접 부르면
                    //   **adb 키 없는 차에서 조용히 실패**한다(selfUpdate는 키 없으면 로그만 남기고 return).
                    //   updateNow는 체크섬 검증·무음설치 45초 타임아웃·실패 시 **시스템 설치화면 폴백**까지 갖춰
                    //   키 없는 차(=지금 구버전에 정체된 차)도 실제로 설치까지 간다. 진행률도 사용자에게 보인다.
                    try {
                        Intent i = new Intent(this, MainActivity.class);
                        i.putExtra("src", "updprompt");
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(i);
                    } catch (Throwable t) {
                        Toast.makeText(this, "업데이트 화면을 열지 못했습니다 — 릴레이 앱에서 '업데이트'를 눌러주세요", Toast.LENGTH_LONG).show();
                    }
                    finish();
                })
                .setNegativeButton("나중에", (d, w) -> finish())
                .show();
    }

    // ★주행 시작 등으로 화면이 가려지면 즉시 닫는다 — 운전 중 잔존 금지.
    @Override protected void onStop() { super.onStop(); finish(); }
}
