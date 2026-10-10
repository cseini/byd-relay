package com.cseini.bydsniffer;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

// ★728 헤드유닛 카메라 보조 창(운영자 10-04 "카메라 주행보조랑 후진 보조를 헤드유닛에 보이는 것도 바로 — 위치나 크기나 자유롭게 조절할 수 있게").
//   깜빡이(좌 2·우 4)·후진(8) 때 보조 화면을 헤드유닛 화면 위 떠 있는 창에 띄운다.
//   창 아무 곳이나 끌면 이동, 오른쪽 아래 ◢를 끌면 크기 — 좌·우·후진 창마다 위치·크기를 따로 기억(prefs hu_rect_<side>).
//   ✕ = 이번 상황(깜빡이·후진)이 끝날 때까지 닫기. 설정(hu_assist_turn·hu_assist_rev)은 폰이 HUASSIST로 보낸다(기본 꺼짐).
// ★733 직접 그리기(운영자 10-04 "헤드유닛에서 직접 나오는 영상인데도 끊김·딜레이, 헤드유닛은 고프레임 고해상도") — 예전엔 폰용 JPEG
//   (측면 480×334·초당 10~16장, 후진 좌·후·우 1198×558·초당 4~10장)를 받아 풀어 그렸다. 이제 카메라 원본(NV21)을 폰과 같은 설정의 보정표로
//   창 크기 그대로 펴서 SurfaceView 창 버퍼에 바로 쓴다(HdRender, 압축·풀기 없음, 카메라 프레임마다). 안 되는 기기면 예전 JPEG로 자동 전환.
final class HuAssistOverlay {
    /** ★733 원본 프레임(NV21 띠·4분할 전체) — seq 단조증가, ts=받은 시각. nv는 ClusterCam 순환 버퍼라 받은 즉시 그려야 한다. */
    static final class Nv {
        final byte[] nv; final int w, h; final long seq, ts = System.currentTimeMillis();
        Nv(byte[] nv, int w, int h, long seq) { this.nv = nv; this.w = w; this.h = h; this.seq = seq; }
    }
    /** ★733 창 크기 보정표 묶음 — 칸(왼쪽부터)·내용 안 칸 x·내용 크기·버퍼 크기. key가 같으면 다시 안 만든다. */
    static final class Layout {
        final String key; final com.cseini.motiondetect.ViewWarp[] warps; final int[] xs; final int cw, ch, bufW, bufH;
        Layout(String key, com.cseini.motiondetect.ViewWarp[] warps, int[] xs, int cw, int ch, int bufW, int bufH) {
            this.key = key; this.warps = warps; this.xs = xs; this.cw = cw; this.ch = ch; this.bufW = bufW; this.bufH = bufH;
        }
        void close() { for (com.cseini.motiondetect.ViewWarp w : warps) if (w != null) w.close(); }
    }
    interface FrameSource {
        RemoteWebServer.Frame next(long afterSeq, long waitMs);   // 폰과 같은 JPEG(직접 그리기가 안 되는 기기에서만)
        Nv nextNv(long afterSeq, long waitMs);                     // ★733 카메라 원본
        Layout layout(int side, int fw, int fh, int bufW, int bufH, Layout prev);   // ★733 창 크기 보정표(설정·크기가 같으면 prev 그대로)
        long[] camCounts();   // ★792 누적 {카메라가 넣은 장, 복사 횟수, 복사 µs} — 돌핀 서피스 경로만, 아니면 null
        void boostCam(boolean on);   // ★814 창이 떠 있는 동안 카메라 SDK 프레임 생성 스레드 우선순위 올림(창 수만큼 짝 맞춰 부름)
        void reboostCam();           // ★814 카메라를 다시 열어 스레드가 새로 생겼을 때 다시 적용(실측 줄마다)
    }
    interface Log { void d(String msg); }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final FrameSource src;
    private final Log log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private FrameLayout root;
    private SurfaceView sv;
    private ImageView img;
    private TextView label, hint;
    private WindowManager.LayoutParams lp;
    private volatile int shownSide = 0;          // 지금 떠 있는 창(0=없음)
    private volatile int dismissedSide = 0;      // ✕로 닫은 상황 — 표시가 0이 될 때까지 다시 안 띄움
    private volatile boolean adjusting = false;  // 폰 '위치·크기 조정' 중(빈 창 60초)
    private volatile int pumpGen = 0;
    private volatile boolean ending = false;     // ★729 신호가 끝나 닫히기 직전 — 새 화면이 안 오는 게 정상이라 '신호 없음'을 띄우지 않음
    private volatile long resumeAt = 0;          // ★729 신호가 다시 온 시각 — 그때부터 1초를 센다
    private final Runnable hideR = () -> hideNow();
    private final Runnable adjustEndR = () -> { adjusting = false; hideNow(); };
    private final Runnable revealR = () -> reveal();
    // ★739 보조 상시 대기 동안 창을 지우지 않고 숨겨 둔다(투명·터치 통과, 표면은 살아 있음) — 신호가 오면 자리·크기만 바꿔 첫 화면을 그린 뒤 보인다.
    //   예전엔 신호마다 창을 새로 만들고 표면 준비를 기다렸다(10-04 실측: 깜빡이 → 창 0.06~0.14초 → 첫 화면 +0.1초).
    private volatile boolean standby;
    private boolean hidden;                                          // 메인 스레드 전용 — 붙어 있지만 안 보이는 창
    private volatile int wantW, wantH; private volatile long wantAt;  // ★739 그릴 버퍼 크기 기준 = 창 크기(◢로 끄는 중이면 wantAt=그 시각)
    private volatile boolean drawn;                                  // 이번 표시에서 한 장이라도 그렸나(표면 크기 콜백이 그린 화면을 지우지 않게)
    // ★733 직접 그리기 — 그리기·보정표 만들기는 한 스레드(hdExec)에서만, 창 버퍼는 hdLock 안에서만 쓴다(창이 닫힐 때 같이 잠금).
    private final java.util.concurrent.ExecutorService hdExec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "hu-assist-hd"));
    private final com.cseini.motiondetect.HdRender hd = new com.cseini.motiondetect.HdRender();
    private final Object hdLock = new Object();
    private volatile boolean svReady = false, hdBroken = false;
    private int curBufW, curBufH;                                   // hdExec 전용
    private final Layout[] cache = new Layout[85];                  // hdExec 전용(방향별 — ★748 82·84=후진 D 왼쪽·오른쪽 따로 창)
    private long layoutRetryAt;                                     // hdExec 전용 — 보정표 실패 뒤 1초 쉬기

    HuAssistOverlay(Context ctx, SharedPreferences prefs, FrameSource src, Log log) {
        this.ctx = ctx; this.prefs = prefs; this.src = src; this.log = log;
    }

    /** ★733 JPEG가 필요한가 — 직접 그리기가 안 되는 기기에서 카메라 화면을 띄우고 있을 때만(ClusterCam이 이때만 인코딩). */
    boolean needsJpeg() { return shownSide != 0 && !adjusting && hdBroken; }

    /** 이 상황(2/4=깜빡이, 8=후진)을 헤드유닛에 띄우도록 켜져 있나. */
    boolean wants(int side) {
        if (prefs == null) return false;
        if (side == 2 || side == 4) return prefs.getBoolean("hu_assist_turn", false);
        if (side == 8 || side == 82 || side == 84) return prefs.getBoolean("hu_assist_rev", false);   // ★748 82·84=후진 D 좌·우 따로 창
        return false;
    }

    // ★732 신호가 끝나고 닫기까지 — 깜빡이 끈 직후·P↔R 사이 깜빡임 방지. ★739 폰에서 0~3초(0.1초 단위)로 설정(운영자 10-04), 기본 1초.
    private long hideMs() { return prefs == null ? 1000L : Math.max(0, Math.min(3000, prefs.getInt("hu_hide_ms", 1000))); }

    /** ClusterCam 표시 대상이 바뀔 때마다(깜빡이·후진·해제) 부른다. */
    void onDisplay(int side, boolean allowed) {
        if (adjusting) return;
        if (side == 0) {
            dismissedSide = 0;
            // ★732 주행 중엔 폴(0.5초)마다 '깜빡이 꺼짐'이 다시 들어와 닫기 예약을 계속 미뤄 창이 영영 안 닫혔다(운영자 10-04 "깜빡이 꺼도 영상창이 안 꺼짐") — 이미 닫는 중이면 그대로.
            if (!ending) { ending = true; main.removeCallbacks(hideR); main.postDelayed(hideR, hideMs()); }
            return;
        }
        if (!allowed || !wants(side) || side == dismissedSide) { main.removeCallbacks(hideR); main.post(hideR); return; }
        main.removeCallbacks(hideR);
        if (ending) { ending = false; resumeAt = System.currentTimeMillis(); }
        main.post(() -> showNow(side));
    }

    /** 폰 '헤드유닛에서 위치·크기 조정' — 그 창을 빈 화면으로 60초 띄운다. */
    void adjust(int side) {
        if (side != 2 && side != 4 && side != 8 && side != 82 && side != 84) return;
        adjusting = true;
        main.removeCallbacks(hideR); main.removeCallbacks(adjustEndR);
        main.post(() -> { hideNow(); showNow(side); });
        main.postDelayed(adjustEndR, 60_000L);
    }

    /** ★739 보조 상시 대기(시동 켜짐·보조 켬·헤드유닛 표시)인 동안 창을 미리 만들어 숨겨 둔다 — ClusterCam.keepTick이 매번 부른다(바뀔 때만 동작). */
    void standby(boolean on) {
        if (standby == on) return;
        standby = on;
        main.post(() -> { if (standby) parkNow(); else if (shownSide == 0 && hidden) removeNow(); });
    }

    /** ★733 카메라가 열려 첫 프레임 크기를 알면 켜 둔 방향들의 보정표를 미리 만든다(첫 신호 때 만드는 시간 0.1초가량을 없앰). */
    void prewarm(int fw, int fh) {
        if (hdBroken) return;
        hdExec.execute(() -> {
            for (int sd : new int[]{2, 4, 8, 82, 84}) {
                if (!wants(sd)) continue;
                int[] r = rectOf(sd), b = capBuf(r[2], r[3]);
                layoutFor(sd, fw, fh, b[0], b[1]);
            }
        });
    }

    private static String nameOf(int side) { return side == 2 ? "◀ 왼쪽" : side == 4 ? "오른쪽 ▶" : side == 82 ? "◀ 왼쪽(후진)" : side == 84 ? "오른쪽 ▶(후진)" : "후진"; }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()); }

    // 저장된 위치·크기(없으면 기본) — 화면 밖으로 나가지 않게 맞춘다.
    private int[] rectOf(int side) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int sw = dm.widthPixels, sh = dm.heightPixels;
        int[] r = null;
        String s = prefs != null ? prefs.getString("hu_rect_" + side, "") : "";
        if (s != null && !s.isEmpty()) {
            try { String[] t = s.split(","); r = new int[]{Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3])}; } catch (Throwable ignored) { r = null; }
        }
        if (r == null) {
            if (side == 82 || side == 84) { int w = (int) (sw * 0.2f), h = (int) (sh * 0.42f), x = side == 82 ? (int) (sw * 0.01f) : sw - w - (int) (sw * 0.01f); r = new int[]{x, (sh - h) / 2, w, h}; }   // ★748 후진 D 따로 창 — 처음엔 화면 양끝(운영자가 순정 후방 영상 양옆 등으로 옮김)
            else if (side == 8) { int w = (int) (sw * 0.62f), h = (int) (w / 2.15f); r = new int[]{(sw - w) / 2, sh - h - (int) (sh * 0.05f), w, h}; }
            else { int w = (int) (sw * 0.34f), h = (int) (w / 1.44f), x = side == 2 ? (int) (sw * 0.02f) : sw - w - (int) (sw * 0.02f); r = new int[]{x, sh - h - (int) (sh * 0.05f), w, h}; }
        }
        return clamp(r, sw, sh);
    }
    private int[] clamp(int[] r, int sw, int sh) {
        int minW = dp(160), minH = dp(100);
        r[2] = Math.max(minW, Math.min(r[2], sw)); r[3] = Math.max(minH, Math.min(r[3], sh));
        r[0] = Math.max(0, Math.min(r[0], sw - r[2])); r[1] = Math.max(0, Math.min(r[1], sh - r[3]));
        return r;
    }
    private void saveRect() {
        if (lp == null || shownSide == 0 || prefs == null) return;
        prefs.edit().putString("hu_rect_" + shownSide, lp.x + "," + lp.y + "," + lp.width + "," + lp.height).apply();
    }
    /** ★733 창 버퍼 크기 — 창 크기 그대로, 단 1600×1000을 넘으면 비율 그대로 줄인다(그리기 시간 상한, 화면은 창 크기로 늘려 보임). */
    // ★756 그리기 버퍼는 창보다 작게(화면 합성기가 창 크기로 늘림) — 운영자 10-05 "카메라 보조가 너무 끊긴다": 돌핀에서 창 1034×744(77만 화소)를 그리는 데
    //   평균 130~155ms → 6~7fps(카메라는 25fps). 화소마다 원본(5120×960)을 띄엄띄엄 읽느라 메모리 대기가 길어 화소 수에 비례한다.
    //   22만 화소로 줄이면 약 40ms → 20fps 이상. 원본 영역(약 768×640~896×624)보다 크게 그리던 몫이라 화질 손해는 작다.
    private static final float PX_BUDGET = 220_000f;
    private static int[] capBuf(int w, int h) {
        float s = Math.min(1f, Math.min(Math.min(1600f / Math.max(1, w), 1000f / Math.max(1, h)), (float) Math.sqrt(PX_BUDGET / Math.max(1f, (float) w * h))));
        return new int[]{Math.max(2, (int) (w * s) & ~1), Math.max(2, (int) (h * s) & ~1)};
    }

    private void ensureViews() {
        if (root != null) return;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);
        sv = new SurfaceView(ctx);   // ★733 직접 그리기 — 창 뒤 층에 그리고 창에 구멍을 내 보인다(창 형식 TRANSLUCENT)
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                boolean ok;
                synchronized (hdLock) { ok = hd.attach(h.getSurface()); svReady = ok; }
                if (!ok) log.d("직접 그리기 창 연결 실패");
            }
            @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
                if (w > 0 && hh > 0) { int[] b = capBuf(w, hh); synchronized (hdLock) { if (svReady && !drawn) hd.clear(b[0], b[1]); } }   // 첫 화면 전 창 구멍으로 뒤 화면이 비치지 않게 검정부터(★739 이미 그린 화면은 안 지움)
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) { synchronized (hdLock) { svReady = false; hd.release(); } }
        });
        root.addView(sv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        img = new ImageView(ctx);   // 직접 그리기가 안 되는 기기용(JPEG)
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setVisibility(View.GONE);
        root.addView(img, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        hint = new TextView(ctx);
        hint.setTextColor(0xCCFFFFFF); hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15); hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(hint, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        label = new TextView(ctx);
        label.setTextColor(Color.WHITE); label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setBackgroundColor(0x99000000); label.setPadding(dp(10), dp(4), dp(10), dp(4));
        root.addView(label, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        TextView close = new TextView(ctx);
        close.setText("✕"); close.setTextColor(Color.WHITE); close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20); close.setGravity(Gravity.CENTER);
        close.setBackgroundColor(0x99000000);
        root.addView(close, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END));
        close.setOnClickListener(v -> { if (adjusting) { main.removeCallbacks(adjustEndR); adjusting = false; } else dismissedSide = shownSide; hideNow(); });
        TextView grip = new TextView(ctx);
        grip.setText("◢"); grip.setTextColor(0xEEFFFFFF); grip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22); grip.setGravity(Gravity.BOTTOM | Gravity.END);
        grip.setPadding(0, 0, dp(4), dp(2));
        root.addView(grip, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.END));
        // 크기 — 오른쪽 아래 ◢를 끈다(왼쪽 위 모서리는 그대로).
        grip.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int w0, h0;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (lp == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: sx = e.getRawX(); sy = e.getRawY(); w0 = lp.width; h0 = lp.height; return true;
                    case MotionEvent.ACTION_MOVE: {
                        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                        int[] r = clamp(new int[]{lp.x, lp.y, w0 + (int) (e.getRawX() - sx), h0 + (int) (e.getRawY() - sy)}, dm.widthPixels, dm.heightPixels);
                        lp.width = r[2]; lp.height = r[3];
                        wantW = r[2]; wantH = r[3]; wantAt = System.currentTimeMillis();
                        try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
                        return true;
                    }
                    case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: saveRect(); return true;
                }
                return false;
            }
        });
        // 이동 — 창 아무 곳이나 끈다.
        root.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int x0, y0;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (lp == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: sx = e.getRawX(); sy = e.getRawY(); x0 = lp.x; y0 = lp.y; return true;
                    case MotionEvent.ACTION_MOVE: {
                        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                        int[] r = clamp(new int[]{x0 + (int) (e.getRawX() - sx), y0 + (int) (e.getRawY() - sy), lp.width, lp.height}, dm.widthPixels, dm.heightPixels);
                        lp.x = r[0]; lp.y = r[1];
                        try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
                        return true;
                    }
                    case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: saveRect(); return true;
                }
                return false;
            }
        });
    }

    private boolean canDraw() {
        try { return android.provider.Settings.canDrawOverlays(ctx); } catch (Throwable t) { return true; }
    }

    // 메인 스레드에서만.
    private void showNow(int side) {
        if (!canDraw()) {
            log.d("화면 위 창 권한 없음 → 셸로 허용 시도 후 다시 띄움");
            new Thread(() -> {
                String r = AdbAccess.runShell(ctx, "appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                log.d("화면 위 창 권한 허용 " + (r == null ? "실패(셸 없음)" : "완료"));
                main.post(() -> { if (canDraw()) showNow(side); });
            }, "hu-perm").start();
            return;
        }
        ensureViews();
        if (side == shownSide && !adjusting && root.getParent() != null) return;   // ★729 같은 상황 반복 호출 — 다시 그리면 화면이 한 번 비어 깜빡인다
        if (shownSide != 0 && shownSide != side) saveRect();
        int[] r = rectOf(side);
        boolean fresh = lp == null || root.getParent() == null, wasParked = !fresh && hidden && shownSide == 0;
        if (lp == null) lp = newLp(r);
        lp.x = r[0]; lp.y = r[1]; lp.width = r[2]; lp.height = r[3];
        wantW = r[2]; wantH = r[3]; wantAt = 0;
        // ★739 첫 화면을 그린 뒤 보인다(이전 방향 화면·빈 창이 번쩍이지 않게) — 0.3초 안에 못 그리면 '연결 중'으로 보인다. 조정 창·JPEG 기기는 바로.
        boolean quiet = !adjusting && !hdBroken;
        setVisible(!quiet);
        shownSide = side;
        label.setText(nameOf(side));
        hint.setText(adjusting ? "위치·크기 조정 — 창을 끌어 옮기고 오른쪽 아래 ◢로 크기를 바꾸세요\n(" + nameOf(side) + " 창 · 60초 뒤 닫힘, ✕로 끝내기)" : "카메라 연결 중…");
        hint.setVisibility(View.VISIBLE);
        img.setImageBitmap(null);
        sv.setVisibility(hdBroken || adjusting ? View.GONE : View.VISIBLE);   // 조정 창은 검은 바탕만(그리기 층 없음)
        img.setVisibility(hdBroken ? View.VISIBLE : View.GONE);
        try {
            if (fresh) wm.addView(root, lp); else wm.updateViewLayout(root, lp);
        } catch (Throwable t) { log.d("창 띄우기 실패 " + t.getClass().getSimpleName() + ": " + t.getMessage()); shownSide = 0; hidden = false; return; }
        if (fresh || wasParked) log.d((adjusting ? "조정 창 " : "카메라 창 ") + nameOf(side) + " " + lp.width + "x" + lp.height + " @" + lp.x + "," + lp.y + (wasParked ? " · 숨겨 둔 창" : ""));
        if (adjusting) return;
        main.removeCallbacks(revealR);
        if (quiet) main.postDelayed(revealR, 300);
        if (hdBroken) startPump(); else startHd();
    }
    private WindowManager.LayoutParams newLp(int[] r) {
        int type = android.os.Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(r[2], r[3], type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);   // ★733 SurfaceView가 창 구멍으로 보이게
        p.gravity = Gravity.TOP | Gravity.START;
        return p;
    }
    // 메인 스레드 — 숨김 = 투명 + 터치 통과(창·표면은 그대로 붙어 있음).
    private void setVisible(boolean v) {
        hidden = !v;
        lp.alpha = v ? 1f : 0f;
        if (v) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    }
    // 숨겨 둘 땐 1×1로 줄인다 — 투명·터치 통과라도 큰 창이 남아 있으면 '가려진 화면에선 누르기 무시'하는 시스템 확인 창(설치 등)을 막을 수 있다.
    private void parkRect() { lp.x = 0; lp.y = 0; lp.width = 1; lp.height = 1; }
    // 메인 스레드 — 첫 화면을 그렸거나 0.3초가 지나면 보인다.
    private void reveal() {
        main.removeCallbacks(revealR);
        if (!hidden || shownSide == 0 || lp == null || root == null || root.getParent() == null) return;
        setVisible(true);
        try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
    }
    // 메인 스레드 — ★739 보조 상시 대기 시작: 창을 미리 붙여 숨겨 둔다.
    private void parkNow() {
        if (hdBroken || adjusting || shownSide != 0 || !canDraw()) return;
        ensureViews();
        if (root.getParent() != null) return;
        if (lp == null) lp = newLp(rectOf(8));
        parkRect();
        setVisible(false);
        hint.setVisibility(View.GONE);
        sv.setVisibility(View.VISIBLE); img.setVisibility(View.GONE);
        try { wm.addView(root, lp); log.d("카메라 창 미리 만들어 숨겨 둠(보조 상시 대기)"); }
        catch (Throwable t) { hidden = false; log.d("창 숨겨 두기 실패 " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
    }

    // ★733 직접 그리기 — 카메라 프레임마다 그 방향 보정표로 펴서 창 버퍼에 쓴다. 새 프레임이 1초(첫 화면 3초) 넘게 없으면 검정+'카메라 신호 없음'.
    private void startHd() {
        final int g = ++pumpGen;
        hdExec.execute(() -> hdLoop(g));
    }
    private void hdLoop(int g) {
        // ★764 화면 표시 등급 우선순위 — 헤드유닛 CPU가 92%까지 차는 중(10-05 17:3x 실측)에 기본 우선순위로는 그리기가 밀려 장당 80~150ms였다.
        //   네이티브 보조 스레드 2개도 이 스레드에서 처음 만들어져 같은 우선순위를 물려받는다.
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY); } catch (Throwable ignored) {}
        // ★739 이번 창 크기로 바로 그린다 — 예전엔 직전 창(다른 방향) 크기로 0.3초 그리느라 그 크기 보정표를 만들어 미리 만든 것까지 버리고 다시 만들었다(방향 바뀔 때 첫 화면 +0.3~0.6초).
        { int[] b0 = capBuf(wantW, wantH); curBufW = b0[0]; curBufH = b0[1]; }
        drawn = false;
        synchronized (hdLock) { if (svReady && curBufW > 0) hd.clear(curBufW, curBufH); }   // 방향이 바뀌면 이전 방향 화면을 남기지 않음
        long seq = 0; int n = 0, fails = 0;
        final long t0 = System.currentTimeMillis(); long lastAt = t0, statT0 = t0, statMs = 0; int statN = 0; boolean stale = false;
        long statSeq0 = -1, statMax = 0; int statSlow = 0; long[] cc0 = null;   // ★792 원본 넘겨받은 장·40ms 넘긴 장·최대·카메라 쪽 누적값 시작점
        src.boostCam(true);   // ★814 창이 떠 있는 동안 카메라 SDK 프레임 생성 스레드 우선순위 올림(아래 finally에서 짝 맞춰 내림)
        try {
        while (g == pumpGen && shownSide != 0) {
            Nv f;
            try { f = src.nextNv(seq, 300); } catch (Throwable t) { f = null; }
            if (g != pumpGen) break;
            long now = System.currentTimeMillis();
            if (f != null && !svReady && now - t0 < 1000) { try { Thread.sleep(5); } catch (InterruptedException e) { return; } continue; }   // ★739 표면 준비 전이면 이 프레임을 버리지 않고 기다렸다 바로 그림
            if (f != null) seq = f.seq;
            Layout L = null;
            if (f != null && now - f.ts <= 500) {
                int[] b = targetBuf(now);
                if (b != null) {
                    L = layoutFor(shownSide, f.w, f.h, b[0], b[1]);
                    if (L != null && System.currentTimeMillis() - now > 30) continue;   // 보정표를 새로 만드느라 늦었으면 이 프레임은 버림(순환 버퍼가 덮였을 수 있음)
                }
            }
            if (L == null) {
                if (!stale && !ending && (n > 0 ? now - Math.max(lastAt, resumeAt) > 1000 : now - t0 > 3000)) {
                    stale = true;
                    log.d(n > 0 ? "카메라 창 새 화면 1초 없음 → 신호 없음 표시" : "카메라 창 첫 화면 3초 없음 → 신호 없음 표시");
                    synchronized (hdLock) { if (svReady && curBufW > 0) hd.clear(curBufW, curBufH); }
                    main.post(() -> { if (g == pumpGen && hint != null) { hint.setText("카메라 신호 없음"); hint.setVisibility(View.VISIBLE); } });
                }
                continue;
            }
            int rc;
            synchronized (hdLock) { rc = svReady ? hd.render(f.nv, f.w, f.h, L.warps, L.xs, L.cw, L.ch, L.bufW, L.bufH) : -1; if (rc == 0) drawn = true; }
            long ms = System.currentTimeMillis() - now;
            if (rc != 0) {
                if (rc == -3) cache[shownSide] = closeAndNull(cache[shownSide]);
                if (++fails >= 15 && n == 0) {
                    log.d("직접 그리기 실패(rc=" + rc + ") 15회 — 폰과 같은 화면(JPEG)으로 바꿈");
                    main.post(() -> fallbackToJpeg(g));
                    return;
                }
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                continue;
            }
            fails = 0; lastAt = now;
            if (stale || n == 0) { stale = false; final boolean first = n == 0; main.post(() -> { if (g == pumpGen && hint != null) { hint.setVisibility(View.GONE); if (first) reveal(); } }); }
            if (n++ == 0) log.d("카메라 창 첫 화면 +" + (now - t0) + "ms · 직접 그리기 " + L.cw + "x" + L.ch + "(버퍼 " + L.bufW + "x" + L.bufH + ") · 카메라 " + f.w + "x" + f.h + " · 프레임 나이 " + (now - f.ts) + "ms");
            statN++; statMs += ms;
            if (ms > 40) statSlow++;
            if (ms > statMax) statMax = ms;
            if (statSeq0 < 0) { statSeq0 = f.seq - 1; cc0 = src.camCounts(); }
            if (now - statT0 >= 10_000L) {
                String br = com.cseini.motiondetect.HdRender.stats();   // ★764 구간별(잠금·지우기·펴기·올리기) — 남은 고정 비용 찾기
                // ★792 병목 가르기 — 카메라가 넣은 장 → 복사해 넘겨받은 장 → 그린 장. 원본이 25장 근처인데 덜 그리면 그리기, 원본부터 적으면 카메라·복사 쪽
                long[] cc = src.camCounts(); String cam = "";
                if (cc != null && cc0 != null) { long cn = cc[1] - cc0[1]; cam = " · 카메라 " + (cc[0] - cc0[0]) + "장 · 복사 평균 " + (cn > 0 ? Math.round((cc[2] - cc0[2]) / (double) cn / 100.0) / 10.0 : 0) + "ms"; }
                log.d("카메라 창 실측 " + statN + "장/" + ((now - statT0) / 1000) + "s = " + (statN * 1000 / Math.max(1, now - statT0)) + "fps · 그리기 평균 " + (statMs / Math.max(1, statN)) + "ms · 40ms 넘김 " + statSlow + "장 · 최대 " + statMax + "ms · 원본 받음 " + (f.seq - statSeq0) + "장" + cam + (br.isEmpty() ? "" : " (" + br + ")"));
                statT0 = now; statN = 0; statMs = 0; statSlow = 0; statMax = 0; statSeq0 = f.seq; cc0 = cc;
                src.reboostCam();   // ★814 카메라를 다시 열었으면 새 스레드에도 적용
            }
        }
        } finally { src.boostCam(false); }
    }
    private static Layout closeAndNull(Layout l) { if (l != null) l.close(); return null; }
    /** 버퍼 크기 = 창 크기(★739 표면 크기 콜백을 기다리지 않음). ◢로 끄는 중이면 0.3초 멈출 때까지 이전 버퍼 크기로 그린다(화면은 창 크기로 늘려 보임) — 보정표를 매 움직임마다 새로 만들지 않게. */
    private int[] targetBuf(long now) {
        if (!svReady || wantW <= 0 || wantH <= 0) return null;
        int[] t = capBuf(wantW, wantH);
        if ((t[0] != curBufW || t[1] != curBufH) && (curBufW == 0 || now - wantAt > 300)) { curBufW = t[0]; curBufH = t[1]; }
        return new int[]{curBufW, curBufH};
    }
    private Layout layoutFor(int sd, int fw, int fh, int bw, int bh) {
        if (sd <= 0 || sd >= cache.length) return null;
        Layout prev = cache[sd];
        if (prev == null && System.currentTimeMillis() < layoutRetryAt) return null;
        Layout l;
        try { l = src.layout(sd, fw, fh, bw, bh, prev); } catch (Throwable t) { l = null; }
        if (l != prev) {
            if (prev != null) prev.close();
            cache[sd] = l;
            if (l != null) log.d("창 보정표 " + nameOf(sd) + " 내용 " + l.cw + "x" + l.ch + " · 버퍼 " + bw + "x" + bh + " · 카메라 " + fw + "x" + fh);
        }
        if (l == null) layoutRetryAt = System.currentTimeMillis() + 1000;
        return l;
    }
    // 메인 스레드 — 직접 그리기가 안 되는 기기: 이번 프로세스 동안 폰과 같은 JPEG로.
    private void fallbackToJpeg(int g) {
        if (g != pumpGen) return;
        hdBroken = true;
        if (sv != null) sv.setVisibility(View.GONE);
        if (img != null) img.setVisibility(View.VISIBLE);
        reveal();
        if (shownSide != 0 && !adjusting) startPump();
    }

    // ★729 멈춘 화면 방지 — 새 프레임이 1초 넘게 안 오면 마지막 화면을 지우고 "카메라 신호 없음"(후진 중 멈춘 영상을 실시간으로 착각하지 않게).
    //   다른 방향(side) 프레임은 건너뛴다(좌→후진 전환 직후 이전 방향 프레임이 잠깐 섞이는 것 방지). ★733부터 직접 그리기가 안 되는 기기에서만.
    private void startPump() {
        final int g = ++pumpGen;
        new Thread(() -> {
            long seq = 0; int n = 0; long t0 = System.currentTimeMillis(), lastAt = t0; boolean stale = false;
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inPreferredConfig = Bitmap.Config.RGB_565;
            while (g == pumpGen && shownSide != 0) {
                RemoteWebServer.Frame f;
                try { f = src.next(seq, 300); } catch (Throwable t) { f = null; }
                if (g != pumpGen) break;
                long now = System.currentTimeMillis();
                if (f != null) seq = f.seq;
                int want = shownSide == 82 || shownSide == 84 ? 8 : shownSide;   // ★748 따로 창은 폰 JPEG(후진 D 나란히)를 그대로 — 직접 그리기 안 되는 기기에서만
                if (f == null || (f.side != 0 && f.side != want) || now - f.ts > 500) {   // 다른 방향·지난 신호 때 화면은 건너뜀
                    if (!stale && !ending && (n > 0 ? now - Math.max(lastAt, resumeAt) > 1000 : now - t0 > 3000)) {
                        stale = true;
                        log.d(n > 0 ? "카메라 창 새 화면 1초 없음 → 신호 없음 표시" : "카메라 창 첫 화면 3초 없음 → 신호 없음 표시");
                        main.post(() -> { if (g == pumpGen && img != null) { img.setImageBitmap(null); hint.setText("카메라 신호 없음"); hint.setVisibility(View.VISIBLE); } });
                    }
                    continue;
                }
                final Bitmap bm;
                try { bm = BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.length, o); } catch (Throwable t) { continue; }
                if (bm == null) continue;
                lastAt = now; stale = false;
                if (n++ == 0) { final long ms = now - t0; log.d("카메라 창 첫 화면 +" + ms + "ms (" + bm.getWidth() + "x" + bm.getHeight() + ", JPEG)"); }
                main.post(() -> { if (g == pumpGen && img != null) { img.setImageBitmap(bm); hint.setVisibility(View.GONE); } });
            }
        }, "hu-assist-pump").start();
    }

    // 메인 스레드 — 신호가 끝났거나 ✕. ★739 보조 상시 대기 중이면 지우지 않고 숨겨 둔다(다음 신호 때 바로).
    private void hideNow() {
        if (!standby || hdBroken || lp == null || root == null || root.getParent() == null) { removeNow(); return; }
        pumpGen++;
        main.removeCallbacks(revealR);
        saveRect();
        shownSide = 0;
        parkRect();
        setVisible(false);
        try { wm.updateViewLayout(root, lp); } catch (Throwable t) { removeNow(); }
    }

    private void removeNow() {
        pumpGen++;
        main.removeCallbacks(revealR);
        if (root != null && root.getParent() != null) {
            saveRect();
            try { wm.removeView(root); } catch (Throwable ignored) {}
        }
        shownSide = 0; hidden = false;
    }
}
