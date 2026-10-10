package com.cseini.bydsniffer.sentry;

import com.cseini.motiondetect.ViewWarp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// 칸별 어안 보정·화면 조정 — 4분할 원본의 각 칸을 칸마다 따로 정한 값(cell_views)으로 펴거나 잘라 다시 같은 크기 4분할로 합친다(10-07 운영자 "카메라 보조 실시간 카메라 조정처럼 직접 커스텀·센트리용 값은 따로").
//   값 형식은 SnifferService.ClusterCam.customWarp와 같다(x·y·w·h 영역 비율, dw 펴기, fov(기본 160), rot, mir, pan/proj(0 직선·1 파노라마·2 가로 직선), tilt, cx, cy, k) —
//   그 식이 바뀌면 이 파일의 buildWarp도 함께 바꾼다. 값이 없는 칸은 원본 그대로(선형 복사). 렌즈 모델은 릴레이 ViewWarp(네이티브 표 조회 remap, 후진·깜빡이 화면과 같음).
//   표는 파이프라인 시작 때 칸마다 1회, 매 프레임은 4칸을 4개 스레드로 나눠 조회만. 출력은 칸 원본 크기라 합성 프레임은 지금과 같은 크기다.
//   배치(칸 바꾸기)·뒤집기는 펴기 뒤에 적용한다 — 좌우 반전은 ViewWarp 거울(칸 설정 mir와 XOR), 상하 반전은 합칠 때 줄을 뒤집는다.
//   움직임 감지는 원본 프레임에서 그대로 돌아 감지영역(ROI) 좌표와 무관하다. 맥 시험(10-07, 20h24 썸네일): 파노라마·화각 175°가 가장 자연스럽고, 원근은 가장자리가 크게 늘어났다.
public final class SentryDewarp {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "sentry-dewarp");
        t.setDaemon(true);
        return t;
    });

    private final int cw, ch, w, h;
    private final ViewWarp[] warps;
    private final byte[][] tiles = new byte[4][];
    private final SentryLayout layout;   // null이면 배치 그대로

    private SentryDewarp(int w, int h, ViewWarp[] warps, SentryLayout layout) {
        this.w = w;
        this.h = h;
        this.cw = w / 2;
        this.ch = h / 2;
        this.warps = warps;
        this.layout = layout;
        for (int i = 0; i < 4; i++) tiles[i] = new byte[cw * ch * 3 / 2];
    }

    // 값이 하나라도 있고, 프레임 크기가 칸 단위로 나뉘고(가로·세로 4의 배수) 네이티브 표를 만들 수 있으면 생성, 아니면 null. 표 생성은 칸마다 약 0.1~0.3초라 4칸을 나눠 만든다.
    public static SentryDewarp create(final int w, final int h, final SentryLayout layout, final org.json.JSONObject cellViews) {
        if (!SentryLayout.fits(w, h) || cellViews == null || cellViews.length() == 0) return null;
        final int cw = w / 2, ch = h / 2;
        List<Future<ViewWarp>> fs = new ArrayList<>();
        for (int q = 0; q < 4; q++) {
            final int qq = q;
            fs.add(POOL.submit(() -> buildWarp(cellViews.optJSONObject(String.valueOf(qq + 1)), (qq % 2) * cw, (qq / 2) * ch, cw, ch,
                    layout != null && (layout.flipOf(qq) & 1) != 0)));
        }
        ViewWarp[] ws = new ViewWarp[4];
        try {
            for (int q = 0; q < 4; q++) ws[q] = fs.get(q).get();
        } catch (Throwable t) {
            for (ViewWarp v : ws) if (v != null) v.close();
            return null;
        }
        return new SentryDewarp(w, h, ws, layout);
    }

    // 한 칸의 보정표. c==null이면 선형 복사(원본 그대로). extraMirror=배치 쪽 좌우 뒤집기(칸 설정 mir와 XOR). 식은 ClusterCam.customWarp와 같다
    static ViewWarp buildWarp(org.json.JSONObject c, int tileX, int tileY, int cw, int ch, boolean extraMirror) {
        if (c == null) {
            return new ViewWarp(tileX, tileY, cw, ch, 0, 0, cw, ch, false, 160f, 0f, extraMirror, cw, ch, false, 0f, 0f, 0f, 0f);
        }
        int rx = (int) (c.optDouble("x", 0) * cw), ry = (int) (c.optDouble("y", 0) * ch);
        int rw = Math.max(16, (int) (c.optDouble("w", 1) * cw)), rh = Math.max(16, (int) (c.optDouble("h", 1) * ch));
        boolean dw = c.optBoolean("dw", false);
        boolean pan = c.optBoolean("pan", false);
        boolean tv = dw && c.optInt("proj", pan ? 1 : 0) == 2;
        boolean mirror = c.optBoolean("mir", false) ^ extraMirror;
        return new ViewWarp(tileX, tileY, cw, ch, rx, ry, rw, rh, dw, (float) c.optDouble("fov", 160), (float) c.optDouble("rot", 0), mirror, cw, ch,
                pan, (float) c.optDouble("tilt", 0), (float) c.optDouble("cx", 0), (float) c.optDouble("cy", 0), (float) c.optDouble("k", 0), tv);
    }

    // 인코더 입력(NV12)으로 — 펴기+배치+색 순서 변환. 실패(크기 불일치·네이티브 오류)면 false
    public synchronized boolean toNv12(byte[] src, byte[] dst, int fw, int fh) { return run(src, dst, fw, fh, true); }

    // 썸네일용(NV21)
    public synchronized boolean toNv21(byte[] src, byte[] dst, int fw, int fh) { return run(src, dst, fw, fh, false); }

    private boolean run(final byte[] src, byte[] dst, int fw, int fh, boolean swapUv) {
        if (fw != w || fh != h || src == null || dst == null || src.length < w * h * 3 / 2 || dst.length < w * h * 3 / 2) return false;
        try {
            List<Future<Boolean>> fs = new ArrayList<>();
            for (int q = 0; q < 4; q++) {
                final int qq = q;
                fs.add(POOL.submit(() -> warps[qq].remap(src, w, h, tiles[qq])));
            }
            for (Future<Boolean> f : fs) if (!f.get()) return false;
        } catch (Throwable t) {
            return false;
        }
        for (int d = 0; d < 4; d++) {
            int q = layout == null ? d : layout.sourceOf(d);
            boolean vf = layout != null && (layout.flipOf(q) & 2) != 0;
            composeCell(tiles[q], cw, ch, vf, dst, w, (d % 2) * cw, (d / 2) * ch, h, swapUv);
        }
        return true;
    }

    // 펴진 한 칸(NV21, cw×ch)을 합성 프레임(dstW 폭, dstH 높이)의 (dx,dy)에 놓는다. vf면 상하 반전, swapUv면 VU→UV 순서 변환(NV12). 순수 계산이라 JVM에서 시험한다
    static void composeCell(byte[] tile, int cw, int ch, boolean vf, byte[] dst, int dstW, int dx, int dy, int dstH, boolean swapUv) {
        for (int r = 0; r < ch; r++) {
            int sr = vf ? ch - 1 - r : r;
            System.arraycopy(tile, sr * cw, dst, (dy + r) * dstW + dx, cw);
        }
        int tUv = cw * ch, dUv = dstW * dstH, rows = ch / 2;
        for (int r = 0; r < rows; r++) {
            int sr = vf ? rows - 1 - r : r;
            int so = tUv + sr * cw, doff = dUv + (dy / 2 + r) * dstW + dx;
            if (!swapUv) {
                System.arraycopy(tile, so, dst, doff, cw);
            } else {
                for (int x = 0; x < cw; x += 2) {
                    dst[doff + x] = tile[so + x + 1];
                    dst[doff + x + 1] = tile[so + x];
                }
            }
        }
    }

    public synchronized void close() {
        for (int i = 0; i < 4; i++) {
            if (warps[i] != null) warps[i].close();
        }
    }
}
