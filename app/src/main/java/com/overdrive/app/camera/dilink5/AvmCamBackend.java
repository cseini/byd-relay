package com.overdrive.app.camera.dilink5;

// ★DiLink5(씨라이언7) AVMCamera 앱직접 백엔드 — BeetleLauncher CamHelper.open 경로 이식.
//   QCarCam(fast_cam_capture+abstract unix socket) SELinux connectto 벽을 우회: 시스템 framework
//   android.hardware.AVMCamera(리플렉션)로 카메라를 열어 앱 프로세스의 ImageReader Surface에 직접 프리뷰.
//   native 실행파일·소켓 없음. ImageReader(RGBA_8888)→rgbaToNv21→기존 파이프라인(FrameSink NV21) 공급.
//   앱 도메인에서 AVMCamera가 막히면(권한/SELinux) start=false → 호출측이 QCarCam으로 폴백.

import android.content.Context;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.nio.ByteBuffer;

public class AvmCamBackend {
    private final int viewIndex;              // AVMCamera camId/preview index (BeetleLauncher: 좌=3·우=2·전체=0)
    private Class<?> cls;
    private Object cam;
    private ImageReader ir;
    private HandlerThread ht;
    private volatile boolean running;
    private java.util.concurrent.CountDownLatch firstFrame;   // 첫 프레임 실수신 확인 — open만 되고 프레임 0인 케이스(앱도메인 차단) 판정

    public AvmCamBackend(int viewIndex) { this.viewIndex = viewIndex; }

    /** 카메라 시작: AVMCamera open→addPreviewSurface(ImageReader)→startPreview. 프레임은 sink.onFrame(NV21,w,h). */
    public synchronized boolean start(Context ctx, int fps, final DiLink5QCarCamBackend.FrameSink sink, final DiLink5QCarCamBackend.Logger log) {
        try {
            cls = Class.forName("android.hardware.AVMCamera");
            // open(int) static 우선 → 생성자(int) 폴백 (BeetleLauncher와 동일)
            try { cam = cls.getMethod("open", Integer.TYPE).invoke(null, Integer.valueOf(viewIndex)); } catch (Throwable ig) {}
            if (cam == null) {
                try { java.lang.reflect.Constructor<?> c = cls.getDeclaredConstructor(Integer.TYPE); c.setAccessible(true); cam = c.newInstance(Integer.valueOf(viewIndex)); }
                catch (Throwable ct) { if (log != null) log.log("AVMCAM", "open/생성 실패 view=" + viewIndex + " : " + (ct.getCause() != null ? ct.getCause() : ct)); }
            }
            if (cam == null) { if (log != null) log.log("AVMCAM", "open(" + viewIndex + ")=null"); stop(); return false; }

            final int W = 1280, H = 720;
            ir = ImageReader.newInstance(W, H, android.graphics.PixelFormat.RGBA_8888, 3);
            ht = new HandlerThread("avmcam"); ht.start();
            firstFrame = new java.util.concurrent.CountDownLatch(1);
            final boolean[] firstLogged = { false };
            ir.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader r) {
                    if (!running) { Image im = r.acquireLatestImage(); if (im != null) im.close(); return; }
                    Image im = null;
                    try {
                        im = r.acquireLatestImage();
                        if (im == null) return;
                        Image.Plane p = im.getPlanes()[0];
                        int w = im.getWidth(), h = im.getHeight();
                        int rowStride = p.getRowStride();
                        ByteBuffer buf = p.getBuffer();
                        ByteBuffer cont;
                        if (rowStride == w * 4) {
                            cont = buf;   // 패딩 없음 → 그대로
                        } else {
                            byte[] tmp = new byte[w * h * 4];   // rowStride 패딩 제거 → 연속 RGBA
                            byte[] row = new byte[rowStride];
                            for (int j = 0; j < h; j++) {
                                buf.position(j * rowStride);
                                int rl = Math.min(row.length, buf.remaining());
                                buf.get(row, 0, rl);
                                System.arraycopy(row, 0, tmp, j * w * 4, w * 4);
                            }
                            cont = ByteBuffer.wrap(tmp);
                        }
                        if (!firstLogged[0]) { firstLogged[0] = true; if (log != null) log.log("AVMCAM", "첫 프레임 " + w + "x" + h + " rowStride=" + rowStride + (rowStride == w * 4 ? "(연속)" : "(패딩제거)")); }
                        byte[] nv21 = new byte[w * h * 3 / 2];
                        DiLink5QCarCamBackend.rgbaToNv21(cont, w, h, nv21);
                        if (sink != null) sink.onFrame(nv21, w, h);
                        if (firstFrame != null) firstFrame.countDown();   // 첫 프레임 실수신 신호
                    } catch (Throwable ignore) {
                    } finally { if (im != null) try { im.close(); } catch (Throwable ig) {} }
                }
            }, new Handler(ht.getLooper()));

            Surface surf = ir.getSurface();
            Object add = null, set = null, start = null;
            try { add = cls.getMethod("addPreviewSurface", Surface.class, Integer.TYPE).invoke(cam, surf, Integer.valueOf(viewIndex)); }
            catch (Throwable t) { if (log != null) log.log("AVMCAM", "addPreviewSurface 예외: " + (t.getCause() != null ? t.getCause() : t)); }
            try { set = cls.getMethod("setPreviewSurface", Surface.class, Integer.TYPE).invoke(cam, surf, Integer.valueOf(viewIndex)); } catch (Throwable ig) {}
            try { start = cls.getMethod("startPreview").invoke(cam); }
            catch (Throwable t) { if (log != null) log.log("AVMCAM", "startPreview 예외: " + (t.getCause() != null ? t.getCause() : t)); }

            // add/set/start 중 하나도 성공(true 또는 예외없음) 안 하면 실패로 간주
            if (!Boolean.TRUE.equals(add) && !Boolean.TRUE.equals(set) && !Boolean.TRUE.equals(start)) {
                if (log != null) log.log("AVMCAM", "프리뷰 연결 실패 add=" + add + " set=" + set + " start=" + start);
                stop(); return false;
            }
            running = true;   // ★await 전에 켜야 onImageAvailable이 프레임 처리+countDown
            // ★첫 프레임 실수신 확인(4s) — open/start는 됐는데 프레임 0(앱도메인 프리뷰 차단, BeetleLauncher가 shell daemon 쓴 이유)이면
            //   여기서 false 반환 → 호출측 QCarCam 폴백. 로그가 "클래스有+권한OK인데 프레임0=daemon 필요" 판정을 가름.
            boolean gotFrame = false;
            try { gotFrame = firstFrame.await(4, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ig) {}
            if (!gotFrame) {
                if (log != null) log.log("AVMCAM", "open/start OK인데 프레임 0(앱도메인 프리뷰 차단 의심 → daemon 필요) view=" + viewIndex + " add=" + add + " set=" + set + " start=" + start);
                stop(); return false;
            }
            if (log != null) log.log("AVMCAM", "AVMCamera 앱직접 시작 OK·프레임 수신 view=" + viewIndex + " add=" + add + " set=" + set + " start=" + start);
            return true;
        } catch (ClassNotFoundException cnf) {
            if (log != null) log.log("AVMCAM", "android.hardware.AVMCamera 클래스 없음(이 ROM 미존재)");
            stop(); return false;
        } catch (Throwable t) {
            if (log != null) log.log("AVMCAM", "start 실패: " + t.getClass().getSimpleName() + ":" + t.getMessage());
            stop(); return false;
        }
    }

    public synchronized void stop() {
        running = false;
        try {
            if (cam != null && cls != null) {
                try { cls.getMethod("stopPreview").invoke(cam); } catch (Throwable ig) {}
                try { cls.getMethod("closeCamera").invoke(cam); } catch (Throwable ig) { try { cls.getMethod("close").invoke(cam); } catch (Throwable ig2) {} }
            }
        } catch (Throwable ig) {}
        cam = null;
        try { if (ht != null) ht.quitSafely(); } catch (Throwable ig) {}   // ★리스너 스레드 먼저 종료(닫힌 reader 접근 경쟁 방지)
        ht = null;
        try { if (ir != null) ir.close(); } catch (Throwable ig) {}
        ir = null;
    }
}
