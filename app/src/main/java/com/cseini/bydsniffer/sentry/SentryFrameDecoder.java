package com.cseini.bydsniffer.sentry;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;

// 녹화 mp4 한 조각을 하드웨어 디코더로 풀어 프레임(NV21)을 차례로 넘긴다 — 재생 시험(SentryReplay)용. 화면에 그리지 않고 ByteBuffer로만 받는다.
public final class SentryFrameDecoder {
    private SentryFrameDecoder() {}

    // 마지막으로 쓴 디코더 이름(로그용) — 소프트웨어 디코더면 2560x1920을 초당 15장 안팎밖에 못 푼다(10-09 실차: 실시간 속도)
    public static volatile String lastDecoderName = "";

    // 하드웨어 디코더를 이름으로 골라 쓴다. 없으면 기본 선택.
    static String pickDecoderName(String mime) {
        String sw = null, any = null;
        try {
            for (android.media.MediaCodecInfo ci : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (ci.isEncoder()) continue;
                boolean has = false;
                for (String t : ci.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) { has = true; break; }
                if (!has) continue;
                String n = ci.getName();
                if (any == null) any = n;
                boolean soft = n.startsWith("OMX.google.") || n.startsWith("c2.android.") || (android.os.Build.VERSION.SDK_INT >= 29 && ci.isSoftwareOnly());
                if (!soft) return n;
                if (sw == null) sw = n;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public interface Sink {
        // 이 프레임(index번째, 조각 안 ptsMs)을 받을지 — false면 NV21 변환 자체를 건너뛴다(변환이 디코딩보다 훨씬 느려서 필요한 프레임만 변환)
        default boolean wants(int index, long ptsMs) { return true; }

        // nv21은 매번 같은 배열을 다시 쓴다(복사해 두려면 직접). ptsMs = 조각 안 시각, index = 조각 안 프레임 번호(0부터).
        // 처음엔 Y(밝기) 평면만 채워져 있다 — 색(VU) 평면이 필요하면 fillChroma.run()을 부른다(감지는 Y만 쓰므로 대부분 필요 없다)
        void onFrame(byte[] nv21, int w, int h, long ptsMs, int index, Runnable fillChroma);
    }

    // 성공이면 null, 아니면 사유. cancel이 true를 돌려주면 중간에 멈춘다(사유 "중단"). frames[0]에 처리한 프레임 수를 채운다.
    public static String decode(File mp4, Sink sink, BooleanSupplier cancel, int[] frames) {
        return decode(mp4, sink, cancel, frames, null);
    }

    // stats(길이 4)가 있으면 {Y 변환 ns, 싱크(감지·판정) ns, 받은 프레임 수, 색 변환 ns}를 더한다 — 어디서 시간이 드는지 보려는 진단
    public static String decode(File mp4, Sink sink, BooleanSupplier cancel, int[] frames, long[] stats) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(mp4.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { track = i; fmt = f; break; }
            }
            if (track < 0) return "영상 트랙 없음";
            ex.selectTrack(track);
            String mimeStr = fmt.getString(MediaFormat.KEY_MIME);
            String hw = pickDecoderName(mimeStr);
            codec = hw != null ? MediaCodec.createByCodecName(hw) : MediaCodec.createDecoderByType(mimeStr);
            lastDecoderName = codec.getName() + (hw != null ? " (하드웨어 선택)" : " (기본)");
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            codec.configure(fmt, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            byte[] nv21 = null;
            boolean inDone = false, outDone = false;
            long firstPtsUs = Long.MIN_VALUE;
            int index = 0;
            while (!outDone) {
                if (cancel != null && cancel.getAsBoolean()) return "중단";
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(2_000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int n = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int oi = codec.dequeueOutputBuffer(bi, 5_000);
                if (oi >= 0) {
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    if (bi.size > 0) {
                        if (firstPtsUs == Long.MIN_VALUE) firstPtsUs = bi.presentationTimeUs;
                        long ptsMs = (bi.presentationTimeUs - firstPtsUs) / 1000L;
                        int idx = index++;
                        if (frames != null && frames.length > 0) frames[0]++;
                        if (sink.wants(idx, ptsMs)) {
                            Image img = codec.getOutputImage(oi);
                            if (img != null) {
                                try {
                                    int w = img.getWidth(), h = img.getHeight();
                                    if (nv21 == null || nv21.length != w * h * 3 / 2) nv21 = new byte[w * h * 3 / 2];
                                    long c0 = System.nanoTime();
                                    toY(img, nv21);
                                    long c1 = System.nanoTime();
                                    final Image fimg = img;
                                    final byte[] fnv = nv21;
                                    final boolean[] filled = {false};
                                    final long[] chromaNs = {0L};
                                    sink.onFrame(nv21, w, h, ptsMs, idx, () -> {
                                        if (filled[0]) return;
                                        filled[0] = true;
                                        long k0 = System.nanoTime();
                                        toChroma(fimg, fnv);
                                        chromaNs[0] = System.nanoTime() - k0;
                                    });
                                    if (stats != null && stats.length >= 4) { stats[0] += c1 - c0; stats[1] += System.nanoTime() - c1 - chromaNs[0]; stats[2]++; stats[3] += chromaNs[0]; }
                                } finally {
                                    img.close();
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                }
            }
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ":" + t.getMessage();
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) { }
            ex.release();
        }
    }

    // YUV_420_888 → NV21의 Y 평면. 행 간격이 달라도 맞게 옮긴다
    static void toY(Image img, byte[] out) {
        int w = img.getWidth(), h = img.getHeight();
        ByteBuffer yb = img.getPlanes()[0].getBuffer();
        int ys = img.getPlanes()[0].getRowStride();
        for (int r = 0; r < h; r++) {
            yb.position(r * ys);
            yb.get(out, r * w, w);
        }
    }

    // NV21의 색 평면(V,U 교차). 픽셀 간격이 달라도 맞게 옮긴다
    static void toChroma(Image img, byte[] out) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane[] p = img.getPlanes();
        ByteBuffer ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int cs = p[2].getRowStride(), ps = p[2].getPixelStride();
        int base = w * h, cw = w / 2;
        int rowLen = (cw - 1) * ps + 1;
        byte[] vrow = new byte[rowLen], urow = new byte[rowLen];
        for (int r = 0; r < h / 2; r++) {   // 행마다 통째로 읽고 배열에서 맞춰 넣는다(픽셀마다 버퍼 get보다 수십 배 빠름)
            vb.position(r * cs);
            vb.get(vrow, 0, Math.min(rowLen, vb.remaining()));
            ub.position(r * cs);
            ub.get(urow, 0, Math.min(rowLen, ub.remaining()));
            int o = base + r * w;
            for (int c = 0, k = 0; c < cw; c++, k += ps) {
                out[o + c * 2] = vrow[k];
                out[o + c * 2 + 1] = urow[k];
            }
        }
    }
}
