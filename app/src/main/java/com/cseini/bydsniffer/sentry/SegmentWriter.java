package com.cseini.bydsniffer.sentry;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Locale;

// 이벤트 폴더에 mp4 조각(<이벤트id>_NNN.mp4, 옛 이름 seg-NNN.mp4)을 쓴다. 쓰는 동안은 .part, 마감 때 fsync 후 rename.
public final class SegmentWriter {
    private static final String PART = ".part";

    private final File eventDir;
    private final MediaFormat format;
    private final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

    private MediaMuxer muxer;
    private File partFile;
    private File finalFile;
    private int trackIdx;
    private boolean open;
    private boolean gotKey;
    private long firstPtsUs;
    private long lastRelUs;
    private long samples;
    private long bytes;
    private volatile String lastError;

    public SegmentWriter(File eventDir, MediaFormat format) {
        if (format == null) throw new IllegalArgumentException("format null");
        this.eventDir = eventDir;
        this.format = format;
        eventDir.mkdirs();
    }

    public void startSegment(int n) {
        if (open) throw new IllegalStateException("조각이 이미 열려 있음");
        finalFile = new File(eventDir, SentryIndex.segFileName(eventDir.getName(), n));
        partFile = new File(eventDir, finalFile.getName() + PART);
        if (partFile.exists()) partFile.delete();
        try {
            muxer = new MediaMuxer(partFile.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            trackIdx = muxer.addTrack(format);
            muxer.start();
        } catch (IOException e) {
            releaseMuxerQuietly();
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            releaseMuxerQuietly();
            partFile.delete();
            throw e;
        }
        open = true;
        gotKey = false;
        firstPtsUs = 0;
        lastRelUs = 0;
        samples = 0;
        bytes = 0;
    }

    public void write(EncodedSample s) {
        if (!open) throw new IllegalStateException("열린 조각 없음");
        if (s.codecConfig) return;
        if (!gotKey) {
            if (!s.keyFrame) return;
            gotKey = true;
            firstPtsUs = s.ptsUs;
        }
        long rel = rebasePts(s.ptsUs, firstPtsUs, lastRelUs);
        bi.set(0, s.data.length, rel, s.keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        muxer.writeSampleData(trackIdx, ByteBuffer.wrap(s.data), bi);
        lastRelUs = rel;
        samples++;
        bytes += s.data.length;
    }

    public File finishSegment() {
        if (!open) return null;
        open = false;
        if (samples == 0) {
            try { muxer.stop(); } catch (RuntimeException ignored) { }
            releaseMuxerQuietly();
            partFile.delete();
            return null;
        }
        try {
            muxer.stop();
        } catch (RuntimeException e) {
            lastError = "muxer.stop 실패: " + e;
            releaseMuxerQuietly();
            partFile.delete();
            return null;
        }
        releaseMuxerQuietly();
        try (FileOutputStream fos = new FileOutputStream(partFile, true)) {
            fos.getFD().sync();
        } catch (IOException e) {
            lastError = "fsync 실패(계속 진행): " + e;
        }
        if (finalFile.exists()) finalFile.delete();
        if (!partFile.renameTo(finalFile)) {
            lastError = "rename 실패: " + partFile.getName();
            return null;
        }
        return finalFile;
    }

    public long segmentDurationMs() { return lastRelUs / 1000L; }

    // 현재 조각에 쓴 샘플 바이트 합(파일 크기와 약간 다를 수 있음)
    public long segmentBytes() { return bytes; }

    public boolean isOpen() { return open; }

    // 마지막 비치명 문제(fsync·rename·muxer.stop 실패 등). 없으면 null.
    public String lastError() { return lastError; }

    // 고아 .part: 박스 사슬이 ftyp부터 moov를 거쳐 파일 끝까지 온전하면 rename, 아니면 삭제.
    public static File finalizeOrphan(File part) {
        if (part == null || !part.getName().endsWith(PART)) return null;
        if (!part.exists()) return null;
        if (part.length() <= 0 || !looksLikeMp4(part)) {
            part.delete();
            return null;
        }
        File target = new File(part.getParentFile(), part.getName().substring(0, part.getName().length() - PART.length()));
        if (target.exists()) {
            part.delete();
            return null;
        }
        if (!part.renameTo(target)) return null;
        return target;
    }

    // 최상위 박스를 처음부터 따라가 파일 끝에 정확히 닿고(ftyp로 시작, moov 포함) 중간에 잘린 박스가 없어야 true
    private static boolean looksLikeMp4(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long pos = 0;
            boolean moov = false;
            byte[] h = new byte[16];
            while (pos + 8 <= len) {
                raf.seek(pos);
                raf.readFully(h, 0, 8);
                long size = u32(h, 0);
                String type = new String(h, 4, 4, "ISO-8859-1");
                long hdr = 8;
                if (size == 1) {
                    if (pos + 16 > len) return false;
                    raf.readFully(h, 8, 8);
                    size = (u32(h, 8) << 32) | u32(h, 12);
                    hdr = 16;
                } else if (size == 0) {
                    size = len - pos;
                }
                if (size < hdr || pos + size > len) return false;
                if (pos == 0 && !"ftyp".equals(type)) return false;
                if ("moov".equals(type)) moov = true;
                pos += size;
            }
            return pos == len && moov;
        } catch (IOException e) {
            return false;
        }
    }

    private static long u32(byte[] b, int o) {
        return ((b[o] & 0xFFL) << 24) | ((b[o + 1] & 0xFFL) << 16) | ((b[o + 2] & 0xFFL) << 8) | (b[o + 3] & 0xFFL);
    }

    // ---- 순수 로직(JUnit 대상) ----

    // 조각 시작 기준 pts(0부터, 음수·역행 방지)
    static long rebasePts(long ptsUs, long firstPtsUs, long lastRelUs) {
        return Math.max(Math.max(ptsUs - firstPtsUs, 0L), lastRelUs);
    }

    static int indexOfAscii(byte[] buf, int len, String needle) {
        int m = needle.length();
        for (int i = 0; i + m <= len; i++) {
            int j = 0;
            while (j < m && buf[i + j] == (byte) needle.charAt(j)) j++;
            if (j == m) return i;
        }
        return -1;
    }

    private void releaseMuxerQuietly() {
        if (muxer == null) return;
        try { muxer.release(); } catch (RuntimeException ignored) { }
        muxer = null;
    }
}
