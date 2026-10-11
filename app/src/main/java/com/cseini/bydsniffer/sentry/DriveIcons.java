package com.cseini.bydsniffer.sentry;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 순정 블랙박스(com.byd.cdr)의 주행 정보 아이콘 — 같은 항목을 같은 그림으로(운영자 10-11 "동일한 항목 동일한 아이콘으로").
 * BYD 그림을 앱에 넣어 배포하지 않고, 차에 이미 있는 순정 앱(/system/app/BydCDR/BydCDR.apk)의 assets/watermark/*_hd.png를 그 자리에서 읽어 영상 크기에 맞춰 줄인다.
 * 순정 앱이 없으면 null을 돌려주고 호출자는 글자 띠로 대신한다. 인코더 스레드를 막지 않도록 읽기는 백그라운드에서 한 번만 한다.
 */
public final class DriveIcons {
    static final String APK = "/system/app/BydCDR/BydCDR.apk";
    static final String DIR = "assets/watermark/";
    // 쓰는 그림 이름(_hd 붙이기 전). 켜짐/꺼짐 한 쌍씩이고 가속은 단계별 한 장
    static final String[] NAMES;
    static {
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        for (String g : new String[]{"P", "R", "N", "D"}) { l.add(g + "_enable"); l.add(g + "_disable"); }
        for (String n : new String[]{"brake", "left_turn_signal", "right_turn_signal", "dipped_headlight", "high_beam", "little_lamp", "rear_fog_lamp", "safety_belt"}) { l.add(n + "_enable"); l.add(n + "_disable"); }
        for (int i = 0; i <= 10; i++) l.add("Accelerate_" + i);
        NAMES = l.toArray(new String[0]);
    }

    /** 한 장의 그림 — 영상 색(Y·U·V)과 투명도를 미리 계산해 둔다. 크기는 짝수(NV12 색 평면이 절반 크기라서). */
    public static final class Sprite {
        public final int w, h;
        final byte[] y, a;      // w*h
        final byte[] u, v, ca;  // (w/2)*(h/2)

        Sprite(int w, int h, byte[] y, byte[] a, byte[] u, byte[] v, byte[] ca) {
            this.w = w; this.h = h; this.y = y; this.a = a; this.u = u; this.v = v; this.ca = ca;
        }
    }

    private final Map<String, Sprite> map;

    DriveIcons(Map<String, Sprite> map) { this.map = map; }

    public Sprite get(String name) { return map.get(name); }

    /** ARGB 픽셀(미리 곱하지 않은 값)에서 Sprite를 만든다 — 순수 계산이라 JVM에서 시험한다. w·h는 짝수. */
    public static Sprite fromArgb(int[] argb, int w, int h) {
        byte[] y = new byte[w * h], a = new byte[w * h];
        for (int i = 0; i < w * h; i++) {
            int p = argb[i], al = p >>> 24, r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            y[i] = (byte) clamp(16 + ((66 * r + 129 * g + 25 * b + 128) >> 8));
            a[i] = (byte) al;
        }
        int cw = w / 2, ch = h / 2;
        byte[] u = new byte[cw * ch], v = new byte[cw * ch], ca = new byte[cw * ch];
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                int sa = 0, sr = 0, sg = 0, sb = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int p = argb[(cy * 2 + dy) * w + cx * 2 + dx], al = p >>> 24;
                        sa += al;
                        sr += ((p >> 16) & 0xFF) * al;   // 투명한 픽셀의 색이 번지지 않게 투명도로 가중
                        sg += ((p >> 8) & 0xFF) * al;
                        sb += (p & 0xFF) * al;
                    }
                }
                int o = cy * cw + cx;
                if (sa == 0) { u[o] = (byte) 128; v[o] = (byte) 128; ca[o] = 0; continue; }
                int r = sr / sa, g = sg / sa, b = sb / sa;
                u[o] = (byte) clamp(128 + ((-38 * r - 74 * g + 112 * b + 128) >> 8));
                v[o] = (byte) clamp(128 + ((112 * r - 94 * g - 18 * b + 128) >> 8));
                ca[o] = (byte) (sa / 4);
            }
        }
        return new Sprite(w, h, y, a, u, v, ca);
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(255, v); }

    // ───────── 차에서 읽어 오기(안드로이드 그래픽 사용 — 백그라운드 스레드에서만) ─────────
    private static volatile DriveIcons cached;
    private static volatile int cachedPx;
    private static volatile boolean loading;
    private static volatile long failedAtMs;

    /** px(짝수) 크기의 아이콘 묶음. 아직 읽는 중이거나 순정 앱이 없으면 null(호출자는 글자로 대신). 실패하면 10분 뒤에 다시 시도한다. */
    public static DriveIcons forSize(final int px) {
        DriveIcons c = cached;
        if (c != null && cachedPx == px) return c;
        if (loading || (failedAtMs != 0 && System.currentTimeMillis() - failedAtMs < 600_000L)) return null;
        loading = true;
        Thread t = new Thread(() -> {
            try {
                DriveIcons d = load(px);
                if (d != null) { cached = d; cachedPx = px; failedAtMs = 0; }
                else failedAtMs = System.currentTimeMillis();
            } catch (Throwable e) {
                failedAtMs = System.currentTimeMillis();
            } finally { loading = false; }
        }, "drive-icons");
        t.setDaemon(true);
        t.start();
        return null;
    }

    private static DriveIcons load(int px) throws java.io.IOException {
        java.io.File f = new java.io.File(APK);
        if (!f.canRead()) return null;
        Map<String, Sprite> m = new HashMap<>();
        try (ZipFile zf = new ZipFile(f)) {
            for (String n : NAMES) {
                ZipEntry e = zf.getEntry(DIR + n + "_hd.png");
                if (e == null) return null;   // 한 장이라도 없으면 쓰지 않는다(순정 앱 버전이 달라 이름이 바뀐 경우)
                try (InputStream in = zf.getInputStream(e)) {
                    android.graphics.Bitmap raw = android.graphics.BitmapFactory.decodeStream(in);
                    if (raw == null) return null;
                    android.graphics.Bitmap sc = android.graphics.Bitmap.createScaledBitmap(raw, px, px, true);
                    int[] argb = new int[px * px];
                    sc.getPixels(argb, 0, px, 0, 0, px, px);
                    if (sc != raw) sc.recycle();
                    raw.recycle();
                    m.put(n, fromArgb(argb, px, px));
                }
            }
        }
        return new DriveIcons(m);
    }
}
