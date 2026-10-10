package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SentryHttpTest {

    // ── parseRange ──────────────────────

    @Test
    public void parseRange_bytes0ToEnd() {
        long[] rg = SentryHttp.parseRange("bytes=0-", 1000);
        assertArrayEquals(new long[]{0, 999}, rg);
    }

    @Test
    public void parseRange_bytesNegativeN() {
        long[] rg = SentryHttp.parseRange("bytes=-500", 1000);
        assertArrayEquals(new long[]{500, 999}, rg);
    }

    @Test
    public void parseRange_bytes100To199() {
        long[] rg = SentryHttp.parseRange("bytes=100-199", 1000);
        assertArrayEquals(new long[]{100, 199}, rg);
    }

    @Test
    public void parseRange_endClamp() {
        long[] rg = SentryHttp.parseRange("bytes=900-1500", 1000);
        assertArrayEquals(new long[]{900, 999}, rg);  // 클램프 999
    }

    @Test
    public void parseRange_startGteLen() {
        long[] rg = SentryHttp.parseRange("bytes=1000-", 1000);
        assertNull(rg);  // 416 에러
    }

    @Test
    public void parseRange_bytesNegativeZero() {
        long[] rg = SentryHttp.parseRange("bytes=-0", 1000);
        assertNull(rg);  // -0은 유효하지 않음
    }

    @Test
    public void parseRange_multipleRanges() {
        long[] rg = SentryHttp.parseRange("bytes=0-99,200-299", 1000);
        assertNull(rg);  // 다중 범위는 지원하지 않음
    }

    @Test
    public void parseRange_malformedFormat() {
        long[] rg = SentryHttp.parseRange("bytes", 1000);
        assertNull(rg);
    }

    @Test
    public void parseRange_noBytesPrefix() {
        long[] rg = SentryHttp.parseRange("0-", 1000);
        assertNull(rg);
    }

    @Test
    public void parseRange_digitOverflow() {
        // Long.MAX_VALUE보다 큰 숫자는 NumberFormatException
        long[] rg = SentryHttp.parseRange("bytes=99999999999999999999-", 1000);
        assertNull(rg);
    }

    @Test
    public void parseRange_spaceBetweenBytesAndEquals() {
        // "bytes = 0-99"는 "bytes="가 아니므로 실패
        long[] rg = SentryHttp.parseRange("bytes = 0-99", 1000);
        assertNull(rg);
    }

    @Test
    public void parseRange_headerNull() {
        long[] rg = SentryHttp.parseRange(null, 1000);
        assertNull(rg);
    }

    @Test
    public void parseRange_lenZero() {
        long[] rg = SentryHttp.parseRange("bytes=0-", 0);
        assertNull(rg);
    }

    @Test
    public void parseRange_bytesNegative501_clamp() {
        // 파일 길이 1000에서 -501은 start = max(0, 1000-501) = 499
        long[] rg = SentryHttp.parseRange("bytes=-501", 1000);
        assertArrayEquals(new long[]{499, 999}, rg);
    }

    @Test
    public void parseRange_emptyBothSides() {
        // "bytes=-"는 "-" 이후 비어있으므로 숫자 파싱 실패
        long[] rg = SentryHttp.parseRange("bytes=-", 1000);
        assertNull(rg);
    }

    @Test
    public void parseRange_endBeforeStart() {
        long[] rg = SentryHttp.parseRange("bytes=200-100", 1000);
        assertNull(rg);  // end < start
    }


    // ── 재생 페이지 ──────────────────────

    @Test
    public void fmtDuration_분초() {
        assertEquals("5분 10초", SentryPlayPage.fmtDuration(310));
        assertEquals("0분 45초", SentryPlayPage.fmtDuration(44.6));
        assertEquals("1시간 2분 3초", SentryPlayPage.fmtDuration(3723));
    }

    @Test
    public void buildPlayPage_idAndSegments() {
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = "2026-10-05_14h30m00s";
        m.startMs = 1759642200000L;
        for (int i = 0; i < 3; i++) {
            SentryIndex.Segment s = new SentryIndex.Segment();
            s.n = i;
            s.bytes = 5L * 1048576;
            s.durationS = i == 2 ? 70 : 120;
            m.segments.add(s);
        }
        String h = SentryPlayPage.buildPlayPage(m, true);
        assertTrue(h.contains("id:'2026-10-05_14h30m00s'"));
        assertTrue(h.contains("auto:true"));
        assertTrue(h.contains("조각 3개"));
        assertTrue(h.contains("5분 10초"));
        assertTrue(h.contains("{n:2,d:70.0}"));
        assertTrue(h.contains("차량 저장소 › Sentry › 2026-10 › 05 › 2026-10-05_14h30m00s"));
        assertFalse(h.contains("__"));
        assertTrue("운영자판은 권한 값이 비어 있어야 함", h.contains("var G='';"));
    }

    @Test
    public void grantQuery_isCarriedIntoPlayAndWebPages() {
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = "2026-10-05_14h30m00s";
        m.startMs = 1759642200000L;
        SentryIndex.Segment sg = new SentryIndex.Segment();
        sg.n = 0; sg.bytes = 1048576; sg.durationS = 60;
        m.segments.add(sg);
        String g = "&sgx=1791190600000&sg=abcdef0123";
        String play = SentryPlayPage.buildPlayPage(m, false, g);
        assertTrue(play.contains("var G='" + g + "';"));
        assertTrue(play.contains("'&seg='+segs[i].n+G"));
        assertTrue(play.contains("'/sentry/delete'+(G?'?'+G.substring(1):'')"));
        String web = SentryPlayPage.buildWebPage("2026-10-05", java.util.Arrays.asList(m), g);
        assertTrue(web.contains("/sentry/play?id=2026-10-05_14h30m00s" + g));
        assertTrue(web.contains("/sentry/thumb?id=2026-10-05_14h30m00s" + g));
        assertTrue(web.contains("/sentry/web?date=2026-10-04" + g));
        assertTrue(web.contains("/sentry/web?date=2026-10-06" + g));
    }
}
