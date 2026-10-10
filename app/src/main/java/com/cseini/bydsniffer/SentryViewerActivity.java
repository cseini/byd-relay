package com.cseini.bydsniffer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;

// 센트리 녹화 보기 — 헤드유닛에서 달력·날짜별 목록을 보고 눌러서 재생한다(운영자 10-08).
// 릴레이 안의 웹 서버(포트 8765~8767)가 내주는 /sentry/web 페이지를 그대로 WebView로 연다 — 앱 안에 따로 만든 뷰어 코드가 없다.
public class SentryViewerActivity extends Activity {
    private WebView web;
    private TextView msg;
    private LinearLayout bar;
    private volatile boolean pageFs = false;   // ★866 플레이어가 전체화면 중(뒤로 가기 = 전체화면 끝)

    // ★866 플레이어 전체화면 다리 — 페이지가 LLHU.fullscreen(true|false)를 부르면 위 막대와 시스템 줄을 숨기고 되살린다(이 기능만, 릴레이 로컬 페이지에서만 연다)
    private final class FsBridge {
        @android.webkit.JavascriptInterface
        public void fullscreen(boolean on) {
            runOnUiThread(() -> {
                pageFs = on;
                if (bar != null) bar.setVisibility(on ? View.GONE : View.VISIBLE);
                getWindow().getDecorView().setSystemUiVisibility(on
                        ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        : View.SYSTEM_UI_FLAG_VISIBLE);
            });
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#151d26"));
        Button close = new Button(this);
        close.setText("← 닫기");
        close.setOnClickListener(v -> finish());
        bar.addView(close);
        msg = new TextView(this);
        msg.setTextColor(Color.parseColor("#9aa7b4"));
        msg.setTextSize(14f);
        msg.setPadding(24, 0, 0, 0);
        msg.setText("센트리 녹화를 불러오는 중…");
        bar.addView(msg);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        try {
            web = new WebView(this);
        } catch (Throwable t) {   // 헤드유닛 ROM에 WebView가 없을 수 있다
            Toast.makeText(this, "이 차 화면에는 웹 보기가 없어 열 수 없어요: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(Color.BLACK);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new FsBridge(), "LLHU");
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        findPortAndLoad();
    }

    // 릴레이 웹 서버가 8765→8766→8767 중 먼저 열린 포트를 쓰므로 /sentry/status가 답하는 포트를 찾는다
    private void findPortAndLoad() {
        new Thread(() -> {
            SnifferService svc = SnifferService.getInstance();
            String g = svc != null ? svc.sentrySelfGrant() : null;
            final String q = g != null ? "?" + g : "";   // 공유판은 권한 값이 있어야 /sentry/*가 열린다(운영자판은 무시)
            int found = 0;
            for (int p : new int[]{8765, 8766, 8767}) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + p + "/sentry/status" + q).openConnection();
                    c.setConnectTimeout(1500);
                    c.setReadTimeout(2500);
                    int code = c.getResponseCode();
                    c.disconnect();
                    if (code == 200) { found = p; break; }
                } catch (Exception ignored) { }
            }
            final int port = found;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (port == 0) {
                    msg.setText("센트리에 연결하지 못했어요. 릴레이가 켜져 있는지 확인해 주세요.");
                    return;
                }
                msg.setText("센트리 녹화");
                web.loadUrl("http://127.0.0.1:" + port + "/sentry/web" + q);
            });
        }, "sentry-viewer-port").start();
    }

    @Override
    public void onBackPressed() {
        if (pageFs && web != null) { web.evaluateJavascript("window.exitFs&&exitFs()", null); return; }   // ★866 전체화면이면 먼저 빠져나온다
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            try { web.stopLoading(); ((ViewGroup) web.getParent()).removeView(web); web.destroy(); } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }
}
