package com.cseini.bydsniffer.sentry;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

// 이벤트의 조각 mp4들을 영상 하나로 이어 붙인다(다시 압축하지 않고 샘플을 그대로 옮김 — 화질 손실 없음, CPU 부담 작음).
//   조각은 같은 인코더 설정으로 만들어져 코덱 설정(SPS/PPS)이 같고, 각 조각은 키프레임으로 시작해 시간이 0부터라 이음매에서 끊김이 없다.
//   조각마다 마지막 프레임 시각 + 프레임 간격만큼 다음 조각의 시작 시각을 민다. 순수 안드로이드 미디어 API라 JVM 시험은 못 하고, 계산 부분(nextOffsetUs)만 시험한다.
public final class SentryRemux {
    private SentryRemux() {}

    private static final int MAX_SAMPLE_BYTES = 4 * 1024 * 1024;   // 2560x1920 키프레임도 충분히 담는 크기
    static final long DEFAULT_FRAME_US = 40_000L;                    // 간격을 알 수 없을 때(25fps)

    // 다음 조각의 시작 시각 오프셋 = 지금 오프셋 + 이 조각의 마지막 프레임 시각 + 마지막 프레임 간격
    static long nextOffsetUs(long offsetUs, long lastPtsUs, long lastDeltaUs) {
        return offsetUs + lastPtsUs + (lastDeltaUs > 0 ? lastDeltaUs : DEFAULT_FRAME_US);
    }

    // parts(번호 순)를 out(MP4)으로. 반환은 out. 실패하면 out을 지우고 예외, tick은 조각마다 불린다(null 가능)
    public static File concat(List<File> parts, File out, Runnable tick) throws IOException {
        if (parts == null || parts.isEmpty()) throw new IOException("이을 조각이 없음");
        MediaMuxer mux = null;
        boolean muxStarted = false, ok = false;
        try {
            mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = -1;
            long offsetUs = 0;
            ByteBuffer buf = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (File part : parts) {
                if (tick != null) tick.run();
                MediaExtractor ex = new MediaExtractor();
                try {
                    ex.setDataSource(part.getPath());
                    int vt = -1;
                    for (int i = 0; i < ex.getTrackCount(); i++) {
                        String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                        if (m != null && m.startsWith("video/")) { vt = i; break; }
                    }
                    if (vt < 0) throw new IOException("영상 트랙 없음 " + part.getName());
                    ex.selectTrack(vt);
                    if (!muxStarted) {
                        track = mux.addTrack(ex.getTrackFormat(vt));
                        mux.start();
                        muxStarted = true;
                    }
                    long firstPts = -1, lastPts = 0, prevPts = -1, lastDelta = 0;
                    while (true) {
                        buf.clear();
                        int n = ex.readSampleData(buf, 0);
                        if (n < 0) break;
                        long pts = ex.getSampleTime();
                        if (firstPts < 0) firstPts = pts;
                        long rel = pts - firstPts;   // 조각 안 상대 시각(보통 이미 0부터)
                        info.set(0, n, offsetUs + rel, (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                        mux.writeSampleData(track, buf, info);
                        if (prevPts >= 0 && rel > prevPts) lastDelta = rel - prevPts;
                        prevPts = rel;
                        lastPts = rel;
                        ex.advance();
                    }
                    if (firstPts < 0) throw new IOException("빈 조각 " + part.getName());
                    offsetUs = nextOffsetUs(offsetUs, lastPts, lastDelta);
                } finally {
                    try { ex.release(); } catch (Throwable ignored) {}
                }
            }
            ok = true;
            return out;
        } finally {
            try { if (mux != null && muxStarted) mux.stop(); } catch (Throwable ignored) {}
            try { if (mux != null) mux.release(); } catch (Throwable ignored) {}
            if (!ok) out.delete();
        }
    }
}
