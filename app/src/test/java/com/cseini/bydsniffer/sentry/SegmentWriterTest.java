package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;

public class SegmentWriterTest {

    // ── rebasePts ──────────────────────

    @Test
    public void rebasePts_monotonic_firstSampleZero() {
        // 첫 샘플은 0, 이후 단조증가
        long r0 = SegmentWriter.rebasePts(1000000, 1000000, 0);
        assertEquals(0, r0);

        long r1 = SegmentWriter.rebasePts(1010000, 1000000, 0);
        assertEquals(10000, r1);

        long r2 = SegmentWriter.rebasePts(1020000, 1000000, 10000);
        assertEquals(20000, r2);
    }

    @Test
    public void rebasePts_samePts_staysMonotonic() {
        // 호출자가 같은 pts를 다시 보내도, rebasePts는 max 연산으로 lastRelUs 유지
        // "같은 pts +1" 처리는 호출자의 책임(lastRelUs를 직접 증가)
        long r0 = SegmentWriter.rebasePts(2000000, 2000000, 0);
        assertEquals(0, r0);

        // 호출자가 lastRelUs를 명시적으로 증가시키면 단조성 유지
        long r1 = SegmentWriter.rebasePts(2000000, 2000000, 1);
        assertEquals(1, r1);  // max(0, 1) = 1
    }

    @Test
    public void rebasePts_backwardPts_clipped() {
        // pts가 역행하면 max로 클립
        long r0 = SegmentWriter.rebasePts(2000000, 2000000, 0);
        assertEquals(0, r0);

        long r1 = SegmentWriter.rebasePts(1990000, 2000000, 0);
        assertEquals(0, r1);  // ptsUs - firstPtsUs = -10000, 클립 0
    }

    @Test
    public void rebasePts_negativePts_becomeZero() {
        // 음수 pts는 0으로 클립
        long r = SegmentWriter.rebasePts(1000000, 2000000, 0);
        assertEquals(0, r);
    }

    @Test
    public void rebasePts_maintainLastRel() {
        // lastRelUs보다 작으면 lastRelUs 유지
        long r0 = SegmentWriter.rebasePts(2005000, 2000000, 10000);
        assertEquals(10000, r0);  // ptsUs - firstPtsUs = 5000 < lastRelUs 10000

        long r1 = SegmentWriter.rebasePts(2025000, 2000000, 10000);
        assertEquals(25000, r1);  // ptsUs - firstPtsUs = 25000 > lastRelUs
    }

    // ── indexOfAscii ──────────────────────

    @Test
    public void indexOfAscii_found() {
        byte[] buf = "ftyp____moov".getBytes();
        int idx = SegmentWriter.indexOfAscii(buf, buf.length, "ftyp");
        assertEquals(0, idx);
    }

    @Test
    public void indexOfAscii_foundMiddle() {
        byte[] buf = "____moov____".getBytes();
        int idx = SegmentWriter.indexOfAscii(buf, buf.length, "moov");
        assertEquals(4, idx);
    }

    @Test
    public void indexOfAscii_notFound() {
        byte[] buf = "ftyp____mdat".getBytes();
        int idx = SegmentWriter.indexOfAscii(buf, buf.length, "moov");
        assertEquals(-1, idx);
    }

    @Test
    public void indexOfAscii_needle_longer_than_buf() {
        byte[] buf = "ftyp".getBytes();
        int idx = SegmentWriter.indexOfAscii(buf, buf.length, "ftyp____");
        assertEquals(-1, idx);
    }

    @Test
    public void indexOfAscii_partial_match_not_found() {
        byte[] buf = "ftypXmoov".getBytes();
        int idx = SegmentWriter.indexOfAscii(buf, buf.length, "typ");
        assertEquals(1, idx);  // "typ"는 1번지에서 시작
    }

    @Test
    public void indexOfAscii_empty_needle() {
        byte[] buf = "ftyp____moov".getBytes();
        int idx = SegmentWriter.indexOfAscii(buf, buf.length, "");
        assertEquals(0, idx);  // 0번지에서 찾음 (0길이 패턴)
    }

    @Test
    public void indexOfAscii_limit_len() {
        byte[] buf = "ftyp____moov".getBytes();
        int idx = SegmentWriter.indexOfAscii(buf, 4, "moov");
        assertEquals(-1, idx);  // len=4이므로 "moov"(12~15)는 범위 밖
    }


    // ── finalizeOrphan: 박스 사슬 검증 ──────────────────────

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] box(String type, int payload) {
        ByteBuffer b = ByteBuffer.allocate(8 + payload);
        b.putInt(8 + payload);
        b.put(type.getBytes());
        return b.array();
    }

    private File part(byte[]... boxes) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] x : boxes) o.write(x);
        File f = tmp.newFile("seg-000.mp4.part");
        Files.write(f.toPath(), o.toByteArray());
        return f;
    }

    @Test
    public void finalizeOrphan_completeChain_renamed() throws IOException {
        File f = part(box("ftyp", 8), box("mdat", 100), box("moov", 20));
        File r = SegmentWriter.finalizeOrphan(f);
        assertNotNull(r);
        assertEquals("seg-000.mp4", r.getName());
    }

    @Test
    public void finalizeOrphan_noMoov_deleted() throws IOException {
        File f = part(box("ftyp", 8), box("mdat", 100));
        assertNull(SegmentWriter.finalizeOrphan(f));
        assertFalse(f.exists());
    }

    @Test
    public void finalizeOrphan_truncatedBox_deleted() throws IOException {
        byte[] moov = box("moov", 20);
        byte[] cut = java.util.Arrays.copyOf(moov, moov.length - 5);
        File f = part(box("ftyp", 8), box("mdat", 100), cut);
        assertNull(SegmentWriter.finalizeOrphan(f));
        assertFalse(f.exists());
    }

    @Test
    public void finalizeOrphan_moovWordInsideMdatOnly_deleted() throws IOException {
        // 예전 방식(끝 2MB에서 문자열 "moov" 검색)이라면 통과했을 가짜
        byte[] mdat = box("mdat", 100);
        System.arraycopy("moov".getBytes(), 0, mdat, 50, 4);
        File f = part(box("ftyp", 8), mdat);
        assertNull(SegmentWriter.finalizeOrphan(f));
    }
}
