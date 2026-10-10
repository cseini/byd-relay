package com.cseini.bydsniffer.sentry;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// 헤드유닛(1920x1080·터치·다크) 센트리 화면 — 링크락 런처가 WebView로 임베드한다(운영자 10-11 "런처에 센트리 화면", 톤: 배경 #0C0E11·청록 #5EC8BD).
// GET /sentry/ui(또는 /sentry/): 왼쪽 달력 + 오른쪽 썸네일 그리드, 위에 [전체|즐겨찾기]·날짜 이동. 카드를 누르면 /sentry/play?id=…&ui=1(재생, 위에 "← 목록").
// 운영자 도구 링크(재생 시험 보고서·BSD 비교)는 넣지 않는다. 순수 문자열 생성이라 JVM에서 시험한다.
final class SentryUiPage {
    private SentryUiPage() {}

    static final String BASE = "/sentry/ui";

    private static final String CSS = ""
            + "*{box-sizing:border-box}"
            + ":root{--bg:#0C0E11;--card:#151A20;--line:#232B34;--acc:#5EC8BD;--tx:#E6EDF3;--mu:#8B97A3}"
            + "body{margin:0;background:var(--bg);color:var(--tx);font-family:-apple-system,'Apple SD Gothic Neo','Noto Sans KR',sans-serif;font-size:20px;-webkit-text-size-adjust:100%}"
            + "a{color:inherit;text-decoration:none}"
            + ".top{display:flex;align-items:center;gap:20px;padding:14px 24px;border-bottom:1px solid var(--line);position:sticky;top:0;background:var(--bg);z-index:5}"
            + ".brand{font-size:26px;font-weight:700;color:var(--acc);margin-right:8px}"
            + ".tabs{display:flex;gap:8px}.tabs a{padding:12px 24px;border-radius:12px;background:var(--card);color:var(--mu);min-height:56px;display:flex;align-items:center}"
            + ".tabs a.on{background:var(--acc);color:#0C0E11;font-weight:700}"
            + ".dn{margin-left:auto;display:flex;align-items:center;gap:12px}.dn a{width:56px;height:56px;display:flex;align-items:center;justify-content:center;border-radius:12px;background:var(--card);font-size:22px}"
            + ".dn span{min-width:160px;text-align:center;font-variant-numeric:tabular-nums}"
            + ".main{display:flex;gap:24px;padding:20px 24px 32px}"
            + "aside{width:340px;flex:none}"
            + ".cal{background:var(--card);border-radius:16px;padding:14px}.cal .mh{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px}"
            + ".cal .mh a{width:48px;height:48px;display:flex;align-items:center;justify-content:center;border-radius:10px;background:#1B222A}"
            + ".cal table{width:100%;border-collapse:collapse;text-align:center}.cal th{color:var(--mu);font-weight:400;font-size:16px;padding:6px 0}"
            + ".cal td{height:48px;color:#5b6773;position:relative}.cal td a{display:flex;flex-direction:column;align-items:center;justify-content:center;height:48px;color:var(--tx);border-radius:10px}"
            + ".cal td a i{font-style:normal;font-size:12px;color:var(--acc)}.cal td.sel a{background:var(--acc);color:#0C0E11;font-weight:700}.cal td.sel a i{color:#0C0E11}"
            + ".cal td.today{outline:1px solid var(--acc);border-radius:10px}"
            + ".grid{flex:1;display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:18px;align-content:start}"
            + ".c{background:var(--card);border-radius:16px;overflow:hidden;display:block}.c img{display:block;width:100%;aspect-ratio:4/3;object-fit:cover;background:#000}"
            + ".c .cap{padding:12px 14px;font-size:18px;line-height:1.4}.c .cap b{font-size:22px}.c .mu{color:var(--mu)}"
            + ".chip{display:inline-block;margin-right:6px;padding:2px 10px;border-radius:999px;background:#1B222A;color:var(--acc);font-size:15px}"
            + ".chip.l{color:var(--mu)}.fav{color:#FFC857}"
            + ".empty{color:var(--mu);padding:60px 0;text-align:center;width:100%}";

    // date=yyyy-MM-dd, g=공유판 권한 값("&sgx=…&sg=…", 없으면 ""), days=그 달 날짜별 이벤트 수. favOnly면 events는 날짜가 섞인 즐겨찾기 목록
    static String build(String date, List<SentryIndex.EventMeta> events, String g, Map<Integer, Integer> days, boolean favOnly, String today) {
        String gq = g == null ? "" : g;
        StringBuilder sb = new StringBuilder("<!doctype html><html lang='ko'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>센트리</title><style>")
                .append(CSS).append("</style></head><body><div class='top'><div class='brand'>센트리</div><div class='tabs'>")
                .append("<a class='").append(favOnly ? "" : "on").append("' href='").append(BASE).append("?date=").append(date).append(gq).append("'>전체</a>")
                .append("<a class='").append(favOnly ? "on" : "").append("' href='").append(BASE).append("?fav=1&date=").append(date).append(gq).append("'>즐겨찾기</a></div>");
        if (!favOnly) {
            sb.append("<div class='dn'><a href='").append(BASE).append("?date=").append(shiftDate(date, -1)).append(gq).append("'>◀</a><span>").append(date)
                    .append("</span><a href='").append(BASE).append("?date=").append(shiftDate(date, 1)).append(gq).append("'>▶</a></div>");
        }
        sb.append("</div><div class='main'>");
        if (!favOnly) sb.append("<aside>").append(calendar(date, days == null ? java.util.Collections.<Integer, Integer>emptyMap() : days, gq, today)).append("</aside>");
        if (events.isEmpty()) {
            sb.append("<div class='empty'>").append(favOnly ? "즐겨찾기한 녹화가 없어요" : "이 날은 녹화가 없어요").append("</div>");
        } else {
            SimpleDateFormat hm = new SimpleDateFormat(favOnly ? "M/d HH:mm" : "HH:mm", Locale.KOREA);
            sb.append("<div class='grid'>");
            for (SentryIndex.EventMeta e : events) {
                sb.append("<a class='c' href='/sentry/play?id=").append(e.id).append("&ui=1").append(gq).append("'><img loading='lazy' src='/sentry/thumb?id=").append(e.id).append(gq)
                        .append("'><div class='cap'>").append(e.favorite ? "<span class='fav'>★</span> " : "").append("<b>").append(hm.format(new Date(e.startMs))).append("</b> <span class='mu'>· ")
                        .append(dur(e.durationS)).append("</span><br>").append(chips(e)).append("</div></a>");
            }
            sb.append("</div>");
        }
        return sb.append("</div><div style='padding:0 24px 28px'>").append(SentrySource.NOTE).append("</div></body></html>").toString();   // 소스 주소 안내는 .main(가로 배치) 밖에 둔다
    }

    // 재생 화면(/sentry/play?…&ui=1)을 런처용으로 — 맨 위에 "← 목록" 링크와 넓은 화면용 스타일을 덧붙인다(재생 화면 코드는 건드리지 않는다)
    static String decoratePlay(String playHtml, String id, String g) {
        String date = id.substring(0, 10);
        String back = "<a class='uiback' href='" + BASE + "?date=" + date + (g == null ? "" : g) + "'>← 목록</a>";
        String css = ".uiback{display:inline-flex;align-items:center;min-height:56px;padding:0 22px;margin:4px 0 6px;border-radius:12px;background:#151A20;color:#5EC8BD;font-size:20px;text-decoration:none}"
                + "body{background:#0C0E11}.w{max-width:1320px}button{min-height:52px;font-size:18px}";
        String out = playHtml.replaceFirst("</style>", java.util.regex.Matcher.quoteReplacement(css + "</style>"));
        return out.replaceFirst("<div class='w'>", java.util.regex.Matcher.quoteReplacement("<div class='w'>" + back));
    }

    private static String chips(SentryIndex.EventMeta e) {
        int kind = 0;
        for (float[] o : e.objects) if (o != null && o.length > 0) kind = Math.max(kind, (int) o[0]);   // 2 사람이 1 차량보다 우선
        StringBuilder s = new StringBuilder();
        if (kind == 2) s.append("<span class='chip'>사람</span>");
        else if (kind == 1) s.append("<span class='chip'>차량</span>");
        if (e.light && kind == 0) s.append("<span class='chip l'>조명 의심</span>");
        return s.toString();
    }

    private static String dur(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        long h = s / 3600, m = (s % 3600) / 60, x = s % 60;
        if (h > 0) return h + "시간 " + m + "분";
        return m > 0 ? m + "분 " + x + "초" : x + "초";
    }

    static String shiftDate(String date, int days) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar c = Calendar.getInstance();
            c.setTime(f.parse(date));
            c.add(Calendar.DAY_OF_MONTH, days);
            return f.format(c.getTime());
        } catch (java.text.ParseException e) {
            return date;
        }
    }

    // 월 달력 — 녹화가 있는 날만 링크(이벤트 수 표시), 선택한 날·오늘 강조, ◀▶로 달 이동(그 달 1일로)
    static String calendar(String date, Map<Integer, Integer> days, String g, String today) {
        int year = Integer.parseInt(date.substring(0, 4)), month = Integer.parseInt(date.substring(5, 7)), selDay = Integer.parseInt(date.substring(8, 10));
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, 1);
        int first = c.get(Calendar.DAY_OF_WEEK) - 1;
        int last = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        Calendar pm = (Calendar) c.clone(), nm = (Calendar) c.clone();
        pm.add(Calendar.MONTH, -1);
        nm.add(Calendar.MONTH, 1);
        SimpleDateFormat ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String monthKey = String.format(Locale.US, "%04d-%02d", year, month);
        StringBuilder sb = new StringBuilder("<div class='cal'><div class='mh'><a href='" + BASE + "?date=").append(ymd.format(pm.getTime())).append(g)
                .append("'>◀</a><span>").append(year).append("년 ").append(month).append("월</span><a href='" + BASE + "?date=").append(ymd.format(nm.getTime())).append(g)
                .append("'>▶</a></div><table><tr>");
        for (String w : new String[]{"일", "월", "화", "수", "목", "금", "토"}) sb.append("<th>").append(w).append("</th>");
        sb.append("</tr><tr>");
        int col = 0;
        for (int i = 0; i < first; i++, col++) sb.append("<td></td>");
        for (int d = 1; d <= last; d++, col++) {
            if (col == 7) { sb.append("</tr><tr>"); col = 0; }
            String key = monthKey + "-" + String.format(Locale.US, "%02d", d);
            Integer n = days.get(d);
            StringBuilder cls = new StringBuilder();
            if (d == selDay) cls.append("sel ");
            if (key.equals(today)) cls.append("today");
            sb.append("<td").append(cls.length() > 0 ? " class='" + cls.toString().trim() + "'" : "").append(">");
            if (n != null && n > 0) sb.append("<a href='" + BASE + "?date=").append(key).append(g).append("'>").append(d).append("<i>").append(n).append("</i></a>");
            else sb.append(d);
            sb.append("</td>");
        }
        for (; col < 7; col++) sb.append("<td></td>");
        return sb.append("</tr></table></div>").toString();
    }
}
