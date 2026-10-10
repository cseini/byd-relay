package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class SentryPlayPageTest {

    private static String count(String html, String needle) {
        int n = 0, i = 0;
        while ((i = html.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return String.valueOf(n);
    }

    @Test
    public void calendar_linksOnlyDaysWithRecordings_andShowsCounts() {
        Map<Integer, Integer> days = new HashMap<>();
        days.put(5, 2);
        days.put(8, 1);
        String h = SentryPlayPage.calendarHtml("2026-10-08", days, "", "2026-10-08");
        assertTrue(h.contains("2026년 10월"));
        assertTrue(h.contains("<a href='/sentry/web?date=2026-10-05'>5<i>2</i></a>"));
        assertTrue(h.contains("<a href='/sentry/web?date=2026-10-08'>8<i>1</i></a>"));
        assertFalse(h.contains("date=2026-10-06'"));
        assertEquals("2", count(h, "<i>"));
    }

    @Test
    public void calendar_marksSelectedAndToday_andMovesMonth() {
        Map<Integer, Integer> days = new HashMap<>();
        String h = SentryPlayPage.calendarHtml("2026-10-08", days, "", "2026-10-09");
        assertTrue(h.contains("<td class='sel'>8</td>"));
        assertTrue(h.contains("<td class='today'>9</td>"));
        assertTrue(h.contains("date=2026-09-01'>◀"));
        assertTrue(h.contains("date=2026-11-01'>▶"));
    }

    @Test
    public void calendar_layoutOffsetsAndLength() {
        String h = SentryPlayPage.calendarHtml("2026-10-08", new HashMap<Integer, Integer>(), "", "");
        // 2026-10-01은 목요일 → 앞 빈 칸 4개 + 31일 = 35칸(5주, 뒤 빈 칸 없음)
        assertEquals("35", count(h, "<td"));
    }

    @Test
    public void playPage_embedsMarksAsSecondsAndHasTimeline() {
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = "2026-10-09_00h13m07s";
        m.startMs = 1_700_000_000_000L;
        SentryIndex.Segment sg = new SentryIndex.Segment();
        sg.n = 0; sg.bytes = 1024; sg.durationS = 30.0;
        m.segments.add(sg);
        m.marks.add(new SentryIndex.Mark(6000L, 0, 0, 9, 2500L));
        String h = SentryPlayPage.buildPlayPage(m, false, "");
        assertTrue(h.contains("marks:[[6.0,0,0,9,2.5]]"));
        assertTrue(h.contains("id='tl'"));
        assertTrue(h.contains("function seekAbs"));
    }

    @Test
    public void playPage_marksLightSuspectEvent() {
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = "2026-10-09_02h31m15s";
        m.startMs = 1_700_000_000_000L;
        m.light = true;
        SentryIndex.Segment sg = new SentryIndex.Segment();
        sg.n = 0; sg.bytes = 1024; sg.durationS = 26.0;
        m.segments.add(sg);
        m.marks.add(new SentryIndex.Mark(5400L, 3, 0, 4, 1300L));
        String h = SentryPlayPage.buildPlayPage(m, false, "");
        assertTrue(h.contains("조명 번쩍임 의심"));
        assertTrue(h.contains("marks:[[5.4,3,0,4,1.3]]"));
        assertTrue(h.contains("'조명 의심'"));
    }

    @Test
    public void playPage_withoutMarksHasEmptyList() {
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = "2026-10-09_00h13m07s";
        m.startMs = 1_700_000_000_000L;
        SentryIndex.Segment sg = new SentryIndex.Segment();
        sg.n = 0; sg.bytes = 1024; sg.durationS = 30.0;
        m.segments.add(sg);
        assertTrue(SentryPlayPage.buildPlayPage(m, false, "").contains("marks:[]"));
    }

    @Test
    public void calendar_keepsGrantQueryOnLinks() {
        Map<Integer, Integer> days = new HashMap<>();
        days.put(3, 1);
        String h = SentryPlayPage.calendarHtml("2026-10-08", days, "&sg=abc", "");
        assertTrue(h.contains("date=2026-10-03&sg=abc'"));
        assertTrue(h.contains("date=2026-09-01&sg=abc'"));
    }

    @Test
    public void pagesShowSourceRepositoryNotice() {
        // AGPL §13 — 웹 화면 어디서든 소스 저장소 주소가 보여야 한다
        String web = SentryPlayPage.buildWebPage("2026-10-10", java.util.Collections.<SentryIndex.EventMeta>emptyList(), "");
        assertTrue(web.contains("https://github.com/cseini/byd-relay") && web.contains("AGPL-3.0"));
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = "2026-10-10_13h59m55s";
        m.durationS = 10;
        SentryIndex.Segment sg = new SentryIndex.Segment();
        sg.n = 0; sg.bytes = 1; sg.durationS = 10;
        m.segments.add(sg);
        assertTrue(SentryPlayPage.buildPlayPage(m, false, "").contains("https://github.com/cseini/byd-relay"));
        assertTrue(SentryReplayPage.build(null, null, null, "").contains("https://github.com/cseini/byd-relay"));
    }
}
