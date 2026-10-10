package com.cseini.bydsniffer.sentry;

import java.util.Calendar;
import java.util.function.IntSupplier;

// 영상 위 시각·배터리 표시(OSD) — 인코더가 변환해 둔 NV12 사본에만 그린다(감지·썸네일이 보는 원본 프레임은 건드리지 않아 시계 숫자가 움직임으로 잡히지 않는다).
//   일렉트로 센트리 영상과 같은 모양(10-05 실영상 확인): 맨 위 반투명 검은 띠, 왼쪽에 날짜·시각, 옆에 배터리 모양과 잔량 %. 위치는 위/가운데/아래.
//   5x7 점 글꼴을 정수 배로 키워 쓰므로 안드로이드 그래픽 없이 JVM에서 검증된다. 인코더 스레드 하나만 부른다.
public final class SentryOsd implements PreRollEncoder.FrameOverlay {
    private static final int BAR_DIM_NUM = 100;        // 띠 안 밝기 = 원래 × 100/256 (약 39%)
    private static final int WHITE_Y = 235;
    private static final long[][] GLYPH = new long[128][];

    static {
        put('0', 0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E);
        put('1', 0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E);
        put('2', 0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F);
        put('3', 0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E);
        put('4', 0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02);
        put('5', 0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E);
        put('6', 0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E);
        put('7', 0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08);
        put('8', 0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E);
        put('9', 0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C);
        put('-', 0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00);
        put(':', 0x00, 0x04, 0x04, 0x00, 0x04, 0x04, 0x00);
        put('%', 0x18, 0x19, 0x02, 0x04, 0x08, 0x13, 0x03);
        put(' ', 0, 0, 0, 0, 0, 0, 0);
    }

    private static void put(char c, long... rows) {
        GLYPH[c] = rows;
    }

    private final boolean showDatetime;
    private final boolean showBattery;
    private final String pos;
    private final IntSupplier battery;   // 0~100, 모르면 음수 — 음수면 배터리 표시를 생략
    private final Calendar cal = Calendar.getInstance();
    private long lastSec = Long.MIN_VALUE;
    private String timeText = "";

    public SentryOsd(boolean showDatetime, boolean showBattery, String pos, IntSupplier battery) {
        this.showDatetime = showDatetime;
        this.showBattery = showBattery;
        this.pos = pos == null ? "top_left" : pos;
        this.battery = battery;
    }

    @Override
    public void apply(byte[] nv12, int w, int h, long wallMs) {
        if (!showDatetime && !showBattery) return;
        if (nv12 == null || w < 64 || h < 64 || nv12.length < w * h * 3 / 2) return;
        int barH = Math.max(16, Math.round(h * 0.0375f)) & ~1;
        int s = Math.max(1, (barH * 6 / 10) / 7);
        int barY = barY(h, barH);
        int pct = showBattery && battery != null ? Math.min(100, battery.getAsInt()) : -1;
        dimBar(nv12, w, h, barY, barH);

        int ty = barY + (barH - 7 * s) / 2;
        String t = showDatetime ? timeText(wallMs) : null;
        String pt = pct >= 0 ? pct + "%" : null;
        int bw = s * 16, bh = s * 9;
        int dateW = t == null ? 0 : t.length() * 6 * s;
        int batW = pt == null ? 0 : bw + s * 4 + pt.length() * 6 * s;
        int total = dateW + (t != null && pt != null ? s * 6 : 0) + batW;   // 글자 덩어리 전체 폭 — 띠 안에서 좌·중·우로 맞춘다
        int x = alignX(w, total, s);
        if (t != null) {
            drawText(nv12, w, h, x, ty, s, t);
            x += dateW + s * 6;
        }
        if (pt != null) {
            int by = barY + (barH - bh) / 2;
            drawBattery(nv12, w, h, x, by, bw, bh, s, pct);
            drawText(nv12, w, h, x + bw + s * 4, ty, s, pt);
        }
    }

    // 좌·중·우 정렬 시작 x(띠 안). 덩어리가 띠보다 넓으면 왼쪽 여백부터
    int alignX(int w, int total, int s) {
        int margin = s * 4;
        int x;
        if (pos.endsWith("_center")) x = (w - total) / 2;
        else if (pos.endsWith("_right")) x = w - total - margin;
        else x = margin;
        return Math.max(margin, x);
    }

    int barY(int h, int barH) {
        if (pos.startsWith("bottom")) return (h - barH) & ~1;
        return 0;
    }

    // 초가 바뀔 때만 문자열을 다시 만든다(YYYY-MM-DD HH:MM:SS)
    String timeText(long wallMs) {
        long sec = wallMs / 1000L;
        if (sec != lastSec) {
            lastSec = sec;
            cal.setTimeInMillis(wallMs);
            timeText = String.format(java.util.Locale.US, "%04d-%02d-%02d %02d:%02d:%02d",
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND));
        }
        return timeText;
    }

    // 띠: 밝기를 낮추고 색은 중립으로(글씨가 번지지 않게)
    private static void dimBar(byte[] f, int w, int h, int y0, int bh) {
        for (int y = y0; y < y0 + bh && y < h; y++) {
            int o = y * w;
            for (int x = 0; x < w; x++) f[o + x] = (byte) (((f[o + x] & 0xFF) * BAR_DIM_NUM) >> 8);
        }
        int uvBase = w * h;
        for (int y = y0 / 2; y < (y0 + bh) / 2 && y < h / 2; y++) {
            int o = uvBase + y * w;
            for (int x = 0; x < w; x++) f[o + x] = (byte) 128;
        }
    }

    private static void drawText(byte[] f, int w, int h, int x0, int y0, int s, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            long[] g = c < 128 ? GLYPH[c] : null;
            if (g == null) continue;
            for (int r = 0; r < 7; r++) {
                for (int col = 0; col < 5; col++) {
                    if ((g[r] & (1L << (4 - col))) != 0) fillWhite(f, w, h, x0 + (i * 6 + col) * s, y0 + r * s, s, s);
                }
            }
        }
    }

    // 배터리 모양: 테두리 + 오른쪽 돌기 + 잔량만큼 채움
    private static void drawBattery(byte[] f, int w, int h, int x, int y, int bw, int bh, int s, int pct) {
        int t = Math.max(1, s / 3);
        fillWhite(f, w, h, x, y, bw, t);
        fillWhite(f, w, h, x, y + bh - t, bw, t);
        fillWhite(f, w, h, x, y, t, bh);
        fillWhite(f, w, h, x + bw - t, y, t, bh);
        fillWhite(f, w, h, x + bw, y + bh / 3, Math.max(1, s), bh / 3);
        int inner = bw - 4 * t;
        int fillW = inner * pct / 100;
        if (fillW > 0) fillWhite(f, w, h, x + 2 * t, y + 2 * t, fillW, bh - 4 * t);
    }

    private static void fillWhite(byte[] f, int w, int h, int x0, int y0, int rw, int rh) {
        int xe = Math.min(w, x0 + rw), ye = Math.min(h, y0 + rh);
        for (int y = Math.max(0, y0); y < ye; y++) {
            int o = y * w;
            for (int x = Math.max(0, x0); x < xe; x++) f[o + x] = (byte) WHITE_Y;
        }
    }
}
