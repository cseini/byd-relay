package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

public class SentryUiPageTest {
    private static SentryIndex.EventMeta ev(String id, long startMs, double dur, boolean fav, float[]... objs) {
        SentryIndex.EventMeta m = new SentryIndex.EventMeta();
        m.id = id; m.startMs = startMs; m.durationS = dur; m.favorite = fav;
        for (float[] o : objs) m.objects.add(o);
        return m;
    }

    @Test
    public void listPage_hasGridCardsCalendarTabsAndNoOperatorLinks() {
        Map<Integer, Integer> days = new TreeMap<>();
        days.put(9, 4); days.put(10, 12);
        String h = SentryUiPage.build("2026-10-10", Arrays.asList(
                ev("2026-10-10_07h50m11s", 1_791_499_811_000L, 55.7, true, new float[]{1, 64, 0, 0, 0, 1, 1}),
                ev("2026-10-10_08h00m00s", 1_791_500_400_000L, 30.0, false)), "&sgx=a&sg=b", days, false, "2026-10-10");
        assertTrue(h.contains("class='brand'>센트리"));
        assertTrue(h.contains("href='/sentry/play?id=2026-10-10_07h50m11s&ui=1&sgx=a&sg=b'"));   // 카드 → 재생(런처 표시 ui=1·권한 값 유지)
        assertTrue(h.contains("src='/sentry/thumb?id=2026-10-10_07h50m11s&sgx=a&sg=b'"));
        assertTrue(h.contains("<span class='fav'>★</span>"));
        assertTrue("차량 판정 칩", h.contains("<span class='chip'>차량</span>"));
        assertTrue(h.contains("2026-10-10") && h.contains("href='/sentry/ui?date=2026-10-09&sgx=a&sg=b'"));   // 전날 이동(날짜 9에 이벤트 4개이기도 함)
        assertTrue("달력 링크는 런처 화면으로", h.contains("<a href='/sentry/ui?date=2026-10-10&sgx=a&sg=b'>10<i>12</i></a>"));
        assertTrue(h.contains("class='on' href='/sentry/ui?date=2026-10-10&sgx=a&sg=b'>전체"));
        assertTrue(h.contains("href='/sentry/ui?fav=1&date=2026-10-10&sgx=a&sg=b'>즐겨찾기"));
        assertFalse("운영자 도구 링크는 없다", h.contains("재생 시험") || h.contains("/sentry/bsd") || h.contains("/sentry/replay"));
        assertTrue("소스 주소 안내(AGPL §13)", h.contains("https://github.com/cseini/byd-relay"));
        assertTrue(h.contains("#0C0E11") && h.contains("#5EC8BD"));   // 링크락 톤
    }

    @Test
    public void emptyDayAndFavoritesTab() {
        String empty = SentryUiPage.build("2026-10-11", Collections.<SentryIndex.EventMeta>emptyList(), "", Collections.<Integer, Integer>emptyMap(), false, "2026-10-11");
        assertTrue(empty.contains("이 날은 녹화가 없어요"));
        String fav = SentryUiPage.build("2026-10-11", Arrays.asList(ev("2026-10-09_20h50m58s", 1_791_460_000_000L, 27.8, true, new float[]{2, 87, 1, 0, 0, 1, 1})), "", null, true, "2026-10-11");
        assertTrue(fav.contains("class='on' href='/sentry/ui?fav=1&date=2026-10-11'>즐겨찾기"));
        assertTrue(fav.contains("<span class='chip'>사람</span>"));
        assertFalse("즐겨찾기 탭에는 달력·날짜 이동이 없다", fav.contains("class='cal'") || fav.contains("class='dn'"));
        String favEmpty = SentryUiPage.build("2026-10-11", Collections.<SentryIndex.EventMeta>emptyList(), "", null, true, "2026-10-11");
        assertTrue(favEmpty.contains("즐겨찾기한 녹화가 없어요"));
    }

    @Test
    public void lightSuspectChipOnlyWhenNoObject() {
        SentryIndex.EventMeta light = ev("2026-10-10_02h31m15s", 1_791_480_000_000L, 26.0, false);
        light.light = true;
        assertTrue(SentryUiPage.build("2026-10-10", Arrays.asList(light), "", null, false, "2026-10-10").contains("<span class='chip l'>조명 의심</span>"));
        SentryIndex.EventMeta both = ev("2026-10-10_02h32m15s", 1_791_480_100_000L, 26.0, false, new float[]{1, 70, 0, 0, 0, 1, 1});
        both.light = true;
        String h = SentryUiPage.build("2026-10-10", Arrays.asList(both), "", null, false, "2026-10-10");
        assertFalse(h.contains("조명 의심"));   // 사람·차량이 판정되면 조명 의심 표시 안 함
        SentryIndex.EventMeta person = ev("2026-10-10_02h33m15s", 1_791_480_200_000L, 26.0, false, new float[]{1, 70, 0, 0, 0, 1, 1}, new float[]{2, 80, 1, 0, 0, 1, 1});
        assertTrue("사람이 차량보다 우선", SentryUiPage.build("2026-10-10", Arrays.asList(person), "", null, false, "2026-10-10").contains("<span class='chip'>사람</span>"));
    }

    @Test
    public void decoratePlay_addsBackLinkAndHuStyle() {
        String play = "<!doctype html><html><head><style>body{margin:0}</style></head><body><div class='w'><h1>센트리 녹화</h1></div></body></html>";
        String h = SentryUiPage.decoratePlay(play, "2026-10-10_07h50m11s", "&sgx=a&sg=b");
        assertTrue(h.contains("<div class='w'><a class='uiback' href='/sentry/ui?date=2026-10-10&sgx=a&sg=b'>← 목록</a>"));
        assertTrue(h.contains(".uiback{") && h.indexOf(".uiback{") < h.indexOf("</style>"));
        assertEquals(1, h.split("class='uiback'", -1).length - 1);
    }

    @Test
    public void shiftDateHandlesMonthEdges() {
        assertEquals("2026-10-01", SentryUiPage.shiftDate("2026-09-30", 1));
        assertEquals("2026-09-30", SentryUiPage.shiftDate("2026-10-01", -1));
        assertEquals("2026-01-01", SentryUiPage.shiftDate("2025-12-31", 1));
    }

    @Test
    public void multiSelectDelete_controlsAndScriptArePresent() {
        String h = SentryUiPage.build("2026-10-10", Arrays.asList(ev("2026-10-10_07h50m11s", 1_791_499_811_000L, 55.7, false)), "&sgx=a&sg=b", null, false, "2026-10-10");
        assertTrue(h.contains("id='bsel'") && h.contains("class='selbar'") && h.contains("id='bdel'") && h.contains("id='ball'") && h.contains("id='bcancel'"));
        assertTrue("카드에 id와 체크 표시", h.contains("data-id='2026-10-10_07h50m11s'") && h.contains("<i class='ck'></i>"));
        assertTrue("삭제는 /sentry/delete에 ids를 보낸다", h.contains("/sentry/delete") && h.contains("JSON.stringify({ids:ids})"));
        assertTrue("100개씩 나눠 보낸다", h.contains("i+=100"));
        assertTrue("공유판 권한 값을 요청에 붙인다", h.contains("var G='&sgx=a&sg=b'"));
        assertTrue("지우기 전에 확인한다", h.contains("confirm(ids.length+'개 녹화를 삭제할까요?"));
        // 권한 값에 따옴표가 있어도 스크립트가 깨지지 않는다
        String q = SentryUiPage.build("2026-10-10", Collections.<SentryIndex.EventMeta>emptyList(), "&sg=a'b", null, false, "2026-10-10");
        assertTrue(q.contains("var G='&sg=a\\'b'"));
    }

    @Test
    public void favoriteButtonsAndScriptArePresent() {
        String h = SentryUiPage.build("2026-10-10", Arrays.asList(ev("2026-10-10_07h50m11s", 1_791_499_811_000L, 55.7, true)), "&sgx=a&sg=b", null, false, "2026-10-10");
        assertTrue(h.contains("id='bfav'") && h.contains("id='bunfav'"));
        assertTrue("카드에 즐겨찾기 값", h.contains("data-fav='1'"));
        assertTrue(h.contains("/sentry/fav") && h.contains("JSON.stringify({ids:ids,favorite:v})"));
        assertTrue("전체 탭에서는 해제해도 카드를 지우지 않는다", h.contains("FAVTAB=false"));
        String fav = SentryUiPage.build("2026-10-10", Arrays.asList(ev("2026-10-10_07h50m11s", 1_791_499_811_000L, 55.7, true)), "", null, true, "2026-10-10");
        assertTrue("즐겨찾기 탭에서는 해제하면 카드를 뺀다", fav.contains("FAVTAB=true"));
    }
}
