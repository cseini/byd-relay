package com.cseini.bydsniffer.sentry;

import java.util.Calendar;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

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
        // ★주행 정보 띠(주행 녹화, 운영자 10-11 "순정 블랙박스처럼") 글자 — 영문 대문자와 기호만(한글 글꼴 없이 ASCII 토큰으로)
        put('A', 0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11);
        put('B', 0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E);
        put('C', 0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E);
        put('D', 0x1E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x1E);
        put('E', 0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F);
        put('F', 0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10);
        put('G', 0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F);
        put('H', 0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11);
        put('I', 0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E);
        put('J', 0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C);
        put('K', 0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11);
        put('L', 0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F);
        put('M', 0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11);
        put('N', 0x11, 0x11, 0x19, 0x15, 0x13, 0x11, 0x11);
        put('O', 0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E);
        put('P', 0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10);
        put('Q', 0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D);
        put('R', 0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11);
        put('S', 0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E);
        put('T', 0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04);
        put('U', 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E);
        put('V', 0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04);
        put('W', 0x11, 0x11, 0x11, 0x15, 0x15, 0x15, 0x0A);
        put('X', 0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11);
        put('Y', 0x11, 0x11, 0x11, 0x0A, 0x04, 0x04, 0x04);
        put('Z', 0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F);
        put('/', 0x01, 0x01, 0x02, 0x04, 0x08, 0x10, 0x10);
        put('<', 0x02, 0x04, 0x08, 0x10, 0x08, 0x04, 0x02);
        put('>', 0x08, 0x04, 0x02, 0x01, 0x02, 0x04, 0x08);
        put('!', 0x04, 0x04, 0x04, 0x04, 0x04, 0x00, 0x04);
        put('.', 0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x0C);
        put('+', 0x00, 0x04, 0x04, 0x1F, 0x04, 0x04, 0x00);
    }

    private static void put(char c, long... rows) {
        GLYPH[c] = rows;
    }

    private final boolean showDatetime;
    private final boolean showBattery;
    private final String pos;
    private final IntSupplier battery;   // 0~100, 모르면 음수 — 음수면 배터리 표시를 생략
    private final Supplier<String> info;   // 주행 정보 한 줄(ASCII 대문자 토큰) — null이면 띠를 만들지 않는다(센트리). 0.5초마다만 다시 읽는다
    private long lastInfoMs = Long.MIN_VALUE;
    private String infoText = "";
    private final Supplier<DriveInfo> driveInfo;   // 주행 정보(값) — 있으면 순정 블랙박스 아이콘 띠를 그리고, 아이콘을 못 읽으면 값을 글자로 대신한다
    private DriveInfo curInfo;
    private final Calendar cal = Calendar.getInstance();
    private long lastSec = Long.MIN_VALUE;
    private String timeText = "";

    public SentryOsd(boolean showDatetime, boolean showBattery, String pos, IntSupplier battery) {
        this(showDatetime, showBattery, pos, battery, null);
    }

    public SentryOsd(boolean showDatetime, boolean showBattery, String pos, IntSupplier battery, Supplier<String> info) {
        this(showDatetime, showBattery, pos, battery, info, null);
    }

    public SentryOsd(boolean showDatetime, boolean showBattery, String pos, IntSupplier battery, Supplier<String> info, Supplier<DriveInfo> driveInfo) {
        this.showDatetime = showDatetime;
        this.showBattery = showBattery;
        this.pos = pos == null ? "top_left" : pos;
        this.battery = battery;
        this.info = info;
        this.driveInfo = driveInfo;
    }

    private boolean hasInfoBand() { return info != null || driveInfo != null; }

    @Override
    public void apply(byte[] nv12, int w, int h, long wallMs) {
        if (!showDatetime && !showBattery && !hasInfoBand()) return;
        if (nv12 == null || w < 64 || h < 64 || nv12.length < w * h * 3 / 2) return;
        int barH = Math.max(16, Math.round(h * 0.0375f)) & ~1;
        int s = Math.max(1, (barH * 6 / 10) / 7);
        int barY = barY(h, barH);
        int pct = showBattery && battery != null ? Math.min(100, battery.getAsInt()) : -1;
        if (!showDatetime && !showBattery) {
            drawInfoBand(nv12, w, h, wallMs, barH, s, barY);   // 시각·배터리를 다 껐어도 주행 정보 띠는 그린다
            return;
        }
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
        drawInfoBand(nv12, w, h, wallMs, barH, s, barY);
    }

    // 둘째 띠 — 주행 정보. 위쪽 띠면 그 바로 아래, 아래쪽 띠면 그 바로 위. 정렬은 같은 규칙, 띠보다 길면 오른쪽을 자른다
    private void drawInfoBand(byte[] nv12, int w, int h, long wallMs, int barH, int s, int barY) {
        if (!hasInfoBand()) return;
        if (wallMs / 500L != lastInfoMs) {
            lastInfoMs = wallMs / 500L;
            String t = null;
            DriveInfo di = null;
            try { if (driveInfo != null) di = driveInfo.get(); } catch (RuntimeException e) { di = null; }
            try { if (di == null && info != null) t = info.get(); } catch (RuntimeException e) { t = null; }
            curInfo = di;
            infoText = di != null ? di.toText() : (t == null ? "" : t);
        }
        if (infoText.isEmpty()) return;
        int y2 = pos.startsWith("bottom") ? barY - barH : barY + barH;
        if (y2 < 0 || y2 + barH > h) return;
        y2 &= ~1;
        dimBar(nv12, w, h, y2, barH);
        DriveIcons icons = curInfo != null ? DriveIcons.forSize((barH - 4) & ~1) : null;   // 아직 읽는 중이거나 순정 앱이 없으면 null → 글자 띠
        if (icons != null) { drawIconBand(nv12, w, h, y2, barH, s, curInfo, icons); return; }
        int ty = y2 + (barH - 7 * s) / 2;
        int maxChars = Math.max(1, (w - s * 8) / (6 * s));
        String t = infoText.length() > maxChars ? infoText.substring(0, maxChars) : infoText;
        drawText(nv12, w, h, alignX(w, t.length() * 6 * s, s), ty, s, t);
    }

    // ★순정 블랙박스와 같은 아이콘 줄(운영자 10-11): 기어 P R N D(지금 기어만 켜짐) · 속도 · 좌우 깜빡이 · 브레이크 · 가속(0~10) · 하향·상향·차폭·뒤안개 · 벨트.
    //   읽힌 적 없는 항목(-1)은 그리지 않고, 읽힌 항목은 순정처럼 켜짐(색)/꺼짐(회색) 그림 한 쌍으로 보인다. 정렬은 같은 규칙.
    void drawIconBand(byte[] f, int w, int h, int y0, int bandH, int s, DriveInfo di, DriveIcons ic) {
        java.util.ArrayList<DriveIcons.Sprite> sp = new java.util.ArrayList<>();
        String speed = (di.speedKmh < 0 ? "--" : String.valueOf(di.speedKmh)) + "KM/H";
        for (char g : new char[]{'P', 'R', 'N', 'D'}) sp.add(ic.get(g + (di.gear == g ? "_enable" : "_disable")));
        int afterGear = sp.size();
        sp.add(ic.get("left_turn_signal" + ((di.turn & 1) != 0 ? "_enable" : "_disable")));
        sp.add(ic.get("right_turn_signal" + ((di.turn & 2) != 0 ? "_enable" : "_disable")));
        if (di.brake >= 0) sp.add(ic.get("brake" + (di.brake == 1 ? "_enable" : "_disable")));
        if (di.accel >= 0) sp.add(ic.get("Accelerate_" + Math.min(10, di.accel)));
        if (di.low >= 0) sp.add(ic.get("dipped_headlight" + (di.low == 1 ? "_enable" : "_disable")));
        if (di.high >= 0) sp.add(ic.get("high_beam" + (di.high == 1 ? "_enable" : "_disable")));
        if (di.pos >= 0) sp.add(ic.get("little_lamp" + (di.pos == 1 ? "_enable" : "_disable")));
        if (di.fogRear >= 0) sp.add(ic.get("rear_fog_lamp" + (di.fogRear == 1 ? "_enable" : "_disable")));
        if (di.belt >= 0) sp.add(ic.get("safety_belt" + (di.belt == 0 ? "_enable" : "_disable")));   // 순정: 값 1이면 꺼짐, 0이면 빨강
        int gap = Math.max(2, s * 2), textW = speed.length() * 6 * s;
        int total = textW + gap * 2;
        for (DriveIcons.Sprite q : sp) if (q != null) total += q.w + gap;
        int x = alignX(w, total, s);
        for (int i = 0; i < sp.size(); i++) {
            DriveIcons.Sprite q = sp.get(i);
            if (q != null) { blit(f, w, h, x, y0 + (bandH - q.h) / 2, q); x += q.w + gap; }
            if (i == afterGear - 1) { drawText(f, w, h, x + gap, y0 + (bandH - 7 * s) / 2, s, speed); x += textW + gap * 2; }   // 기어 뒤에 속도
        }
    }

    // 그림 한 장을 NV12 위에 투명도대로 섞는다(짝수 좌표로 맞춤)
    static void blit(byte[] f, int w, int h, int x0, int y0, DriveIcons.Sprite sp) {
        x0 &= ~1;
        y0 &= ~1;
        for (int yy = 0; yy < sp.h; yy++) {
            int y = y0 + yy;
            if (y < 0 || y >= h) continue;
            for (int xx = 0; xx < sp.w; xx++) {
                int x = x0 + xx;
                if (x < 0 || x >= w) continue;
                int a = sp.a[yy * sp.w + xx] & 0xFF;
                if (a == 0) continue;
                int d = f[y * w + x] & 0xFF, src = sp.y[yy * sp.w + xx] & 0xFF;
                f[y * w + x] = (byte) ((d * (255 - a) + src * a) / 255);
            }
        }
        int uvBase = w * h, cw = sp.w / 2, chh = sp.h / 2;
        for (int cy = 0; cy < chh; cy++) {
            int row = y0 / 2 + cy;
            if (row < 0 || row >= h / 2) continue;
            for (int cx = 0; cx < cw; cx++) {
                int ux = x0 + cx * 2;
                if (ux < 0 || ux + 1 >= w) continue;
                int a = sp.ca[cy * cw + cx] & 0xFF;
                if (a == 0) continue;
                int o = uvBase + row * w + ux;
                f[o] = (byte) (((f[o] & 0xFF) * (255 - a) + (sp.u[cy * cw + cx] & 0xFF) * a) / 255);
                f[o + 1] = (byte) (((f[o + 1] & 0xFF) * (255 - a) + (sp.v[cy * cw + cx] & 0xFF) * a) / 255);
            }
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
