package com.cseini.bydsniffer.sentry;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

// NV21 프레임 → H.264/H.265 인코딩 + 프리롤 링버퍼. feed()는 큐에 넣기만 하고 변환·인코딩은 전용 스레드가 한다.
public final class PreRollEncoder {
    private static final String TAG = "PreRollEncoder";

    public interface Listener { void onSample(EncodedSample s); void onError(String msg); }

    // 인코더 입력 직전(변환해 둔 NV12 사본)에 그리는 덧그림 — 감지·썸네일이 보는 원본 프레임은 건드리지 않는다. 인코더 스레드에서만 불린다
    public interface FrameOverlay { void apply(byte[] nv12, int w, int h, long wallMs); }

    private static final class Frame {
        final byte[] nv21; final long tsMs;
        Frame(byte[] nv21, long tsMs) { this.nv21 = nv21; this.tsMs = tsMs; }
    }

    private static final int QUEUE_MAX = 2;
    private static final int MISMATCH_ERROR_STREAK = 30;

    private final int width, height, fps, bitrateKbps, prerollSec;
    private final String requestedCodec;
    private final Listener listener;
    private final int frameSize;
    private final byte[] nv12;                      // 인코더 스레드 전용 재사용 버퍼
    private volatile FrameOverlay overlay;           // null이면 덧그림 없음
    private volatile SentryDewarp dewarp;           // null이면 어안 보정 없음(원본 칸 그대로) — 켜면 배치·뒤집기도 보정 안에서 함께 적용
    private volatile SentryLayout layout;            // null이면 칸 배치·뒤집기 없음(기존 변환 그대로)

    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private final Object ringLock = new Object();
    private final ArrayDeque<EncodedSample> ring = new ArrayDeque<>();
    private long ringBytes;
    private final long ringLimitBytes;
    private final ArrayDeque<Long> outTimesMs = new ArrayDeque<>();   // ringLock으로 보호
    private long firstOutMs = -1;

    private MediaCodec codec;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean live;
    private volatile MediaFormat outFormat;
    private volatile String codecInUse;
    private volatile long dropped;
    private volatile boolean errorNotified;
    private boolean stopped;
    private long lastPtsUs = Long.MIN_VALUE;
    private int sizeMismatchStreak;                  // 인코더 스레드 전용
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    public PreRollEncoder(int width, int height, int fps, int bitrateKbps, String codec, int prerollSec, Listener l) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrateKbps = bitrateKbps;
        this.requestedCodec = codec;
        this.prerollSec = prerollSec;
        this.listener = l;
        this.frameSize = width * height * 3 / 2;
        this.nv12 = new byte[frameSize];
        this.ringLimitBytes = ringLimitBytes(prerollSec, bitrateKbps);
    }

    public boolean start() {
        boolean wantHevc = "h265".equalsIgnoreCase(requestedCodec) || "hevc".equalsIgnoreCase(requestedCodec);
        MediaCodec c = null;
        if (wantHevc) {
            try {
                c = openCodec("video/hevc");
                codecInUse = "h265";
            } catch (Throwable t) {
                Log.w(TAG, "h265 인코더 사용 불가 → h264 폴백: " + t);
                releaseQuietly(c);
                c = null;
            }
        }
        if (c == null) {
            try {
                c = openCodec("video/avc");
                codecInUse = "h264";
            } catch (Throwable t) {
                releaseQuietly(c);
                notifyError("인코더 시작 실패: " + t);
                return false;
            }
        }
        codec = c;
        running = true;
        thread = new Thread(this::encodeLoop, "sentry-encoder");
        thread.start();
        return true;
    }

    private MediaCodec openCodec(String mime) throws java.io.IOException {
        MediaCodec c = MediaCodec.createEncoderByType(mime);
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            c.start();
            return c;
        } catch (RuntimeException e) {
            releaseQuietly(c);
            throw e;
        }
    }

    // 넘긴 nv21 배열은 인코더 스레드가 쓸 때까지 호출자가 재사용·수정하면 안 된다(참조만 보관).
    public void feed(byte[] nv21, long tsMs) {
        if (!running || nv21 == null) return;
        synchronized (queue) {
            if (queue.size() >= QUEUE_MAX) {
                queue.pollFirst();
                dropped++;
            }
            queue.addLast(new Frame(nv21, tsMs));
            queue.notifyAll();
        }
    }

    public void setOverlay(FrameOverlay o) { this.overlay = o; }

    public void setDewarp(SentryDewarp d) { this.dewarp = d; }

    public SentryDewarp dewarp() { return dewarp; }

    public void setLayout(SentryLayout l) { this.layout = (l == null || l.isIdentity()) ? null : l; }

    public MediaFormat outputFormat() { return outFormat; }

    public String codecInUse() { return codecInUse; }

    public List<EncodedSample> drainPreroll() {
        List<EncodedSample> snap;
        synchronized (ringLock) { snap = new ArrayList<>(ring); }
        int from = prerollStartIndex(snap, prerollSec * 1000000L);
        if (from < 0) return new ArrayList<>();
        return new ArrayList<>(snap.subList(from, snap.size()));
    }

    public void setLive(boolean live) { this.live = live; }

    // 최근 10초 출력 샘플 수 / 10초. 시작 직후 10초 미만이면 경과 시간으로 나눈다(최소 1초).
    public int encodedFpsLast10s() {
        long now = System.nanoTime() / 1000000L;
        synchronized (ringLock) {
            pruneOutTimes(now);
            if (firstOutMs < 0) return 0;
            long windowMs = Math.max(1000L, Math.min(10000L, now - firstOutMs));
            return Math.round(outTimesMs.size() * 1000f / windowMs);
        }
    }

    public long droppedFrames() { return dropped; }

    public boolean isRunning() { return running && thread != null && thread.isAlive(); }

    public void stop() {
        synchronized (this) {
            if (stopped) return;
            stopped = true;
        }
        running = false;
        synchronized (queue) { queue.clear(); queue.notifyAll(); }
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        SentryDewarp dwClose = dewarp;
        dewarp = null;
        if (dwClose != null) dwClose.close();   // 인코더 스레드가 끝난 뒤 네이티브 표를 반납
        MediaCodec c = codec;
        if (c != null) {
            try { c.stop(); } catch (Throwable e) { notifyError("인코더 stop 실패: " + e); }
            try { c.release(); } catch (Throwable e) { notifyError("인코더 release 실패: " + e); }
        }
    }

    private void encodeLoop() {
        try {
            while (running) {
                Frame f;
                synchronized (queue) {
                    if (queue.isEmpty()) {
                        try { queue.wait(20); } catch (InterruptedException e) { break; }
                    }
                    f = queue.pollFirst();
                }
                if (f != null) submit(f);
                drainOutput(f == null ? 10000 : 0);
            }
        } catch (Throwable t) {
            if (running) {
                running = false;
                notifyError("인코더 스레드 오류: " + t);
            }
        }
    }

    private void submit(Frame f) {
        if (f.nv21.length < frameSize) {
            dropped++;
            Log.w(TAG, "프레임 크기 불일치: " + f.nv21.length + " < " + frameSize + " (" + width + "x" + height + ")");
            if (++sizeMismatchStreak >= MISMATCH_ERROR_STREAK) {
                notifyError("프레임 크기 불일치 연속 " + sizeMismatchStreak + "장: " + f.nv21.length + " < " + frameSize + " (" + width + "x" + height + ")");
            }
            return;
        }
        sizeMismatchStreak = 0;
        SentryDewarp dw = dewarp;
        SentryLayout lo = layout;
        if (dw != null && dw.toNv12(f.nv21, nv12, width, height)) {
            // 어안 보정(펴기)+배치+색 변환까지 끝남
        } else if (lo == null || !lo.toNv12(f.nv21, nv12, width, height)) nv21ToNv12(f.nv21, nv12, width, height);   // 배치가 없거나 크기가 안 맞으면 기존 변환
        FrameOverlay ov = overlay;
        if (ov != null) {
            try { ov.apply(nv12, width, height, System.currentTimeMillis()); } catch (Throwable t) { Log.w(TAG, "덧그림 실패 " + t); overlay = null; }
        }
        long pts = f.tsMs * 1000L;
        if (pts <= lastPtsUs) pts = lastPtsUs + 1;
        lastPtsUs = pts;
        int idx = -1;
        for (int i = 0; i < 5 && idx < 0 && running; i++) {
            idx = codec.dequeueInputBuffer(10000);
            if (idx < 0) drainOutput(0);
        }
        if (idx < 0) { dropped++; return; }
        ByteBuffer ib = codec.getInputBuffer(idx);
        ib.clear();
        int n = Math.min(frameSize, ib.capacity());
        ib.put(nv12, 0, n);
        codec.queueInputBuffer(idx, 0, n, pts, 0);
    }

    private void drainOutput(long firstTimeoutUs) {
        long timeout = firstTimeoutUs;
        while (running) {
            int oi = codec.dequeueOutputBuffer(info, timeout);
            timeout = 0;
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { outFormat = codec.getOutputFormat(); continue; }
            if (oi < 0) continue;
            try {
                ByteBuffer ob = codec.getOutputBuffer(oi);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (ob != null && info.size > 0 && !config) {
                    byte[] d = new byte[info.size];
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    ob.get(d);
                    onEncoded(new EncodedSample(d, info.presentationTimeUs,
                            (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0, false));
                }
            } finally {
                codec.releaseOutputBuffer(oi, false);
            }
        }
    }

    private void onEncoded(EncodedSample s) {
        long now = System.nanoTime() / 1000000L;
        synchronized (ringLock) {
            ring.addLast(s);
            ringBytes += s.data.length;
            while (ringBytes > ringLimitBytes && ring.size() > 1) ringBytes -= ring.pollFirst().data.length;
            if (firstOutMs < 0) firstOutMs = now;
            outTimesMs.addLast(now);
            pruneOutTimes(now);
        }
        if (live && listener != null) {
            try { listener.onSample(s); } catch (Throwable t) { notifyError("onSample 처리 오류: " + t); }
        }
    }

    private void pruneOutTimes(long now) {
        while (!outTimesMs.isEmpty() && now - outTimesMs.peekFirst() > 10000L) outTimesMs.pollFirst();
    }

    private void notifyError(String msg) {
        Log.w(TAG, msg);
        if (errorNotified) return;
        errorNotified = true;
        if (listener != null) listener.onError(msg);
    }

    private static void releaseQuietly(MediaCodec c) {
        if (c == null) return;
        try { c.release(); } catch (Throwable ignored) { }
    }

    // ---- 순수 로직(JUnit 대상) ----

    // NV21(Y + VU 교차) → NV12(Y + UV 교차). dst는 w*h*3/2 이상.
    static void nv21ToNv12(byte[] src, byte[] dst, int w, int h) {
        int ySize = w * h;
        System.arraycopy(src, 0, dst, 0, ySize);
        int end = ySize + ySize / 2;
        for (int i = ySize; i + 1 < end; i += 2) {
            dst[i] = src[i + 1];
            dst[i + 1] = src[i];
        }
    }

    // 프리롤 시작 인덱스: 최신 pts - prerollUs 이하인 가장 최근 keyFrame. 없으면 가장 오래된 keyFrame. keyFrame이 아예 없으면 -1.
    static int prerollStartIndex(List<EncodedSample> samples, long prerollUs) {
        int n = samples.size();
        if (n == 0) return -1;
        long threshold = samples.get(n - 1).ptsUs - prerollUs;
        int oldestKey = -1;
        for (int i = 0; i < n; i++) {
            if (samples.get(i).keyFrame) { oldestKey = i; break; }
        }
        if (oldestKey < 0) return -1;
        for (int i = n - 1; i >= oldestKey; i--) {
            EncodedSample s = samples.get(i);
            if (s.keyFrame && s.ptsUs <= threshold) return i;
        }
        return oldestKey;
    }

    // 링버퍼 바이트 상한 = (prerollSec+2)초 × 비트레이트
    static long ringLimitBytes(int prerollSec, int bitrateKbps) {
        return (prerollSec + 2L) * bitrateKbps * 1000L / 8L;
    }
}
