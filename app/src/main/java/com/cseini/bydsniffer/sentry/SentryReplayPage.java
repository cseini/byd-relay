package com.cseini.bydsniffer.sentry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

// 재생 시험 보고서 페이지(/sentry/replay) — 이벤트마다 움직임 표시(시각·영상 칸·세기·길이)와 판정(사람/차량 %) + 확인용 사진. 외부 리소스 없음.
final class SentryReplayPage {
    private SentryReplayPage() {}

    private static final String[] POS = {"좌상", "우상", "좌하", "우하"};

    private static final String CSS = ""
            + "body{margin:0;background:#0b0f14;color:#e6edf3;font-family:-apple-system,'Apple SD Gothic Neo','Noto Sans KR',sans-serif}"
            + ".w{max-width:1000px;margin:0 auto;padding:12px 12px 40px}h1{font-size:18px;margin:4px 0 2px}h2{font-size:15px;margin:18px 0 6px}"
            + ".sub{color:#9aa7b4;font-size:13px;margin:2px 0 6px}a{color:#4da3ff;text-decoration:none}"
            + "table{width:100%;border-collapse:collapse;font-size:13px}td,th{padding:5px 6px;border-bottom:1px solid #1c2733;text-align:left;vertical-align:middle}th{color:#9aa7b4;font-weight:normal}"
            + "img{width:150px;border-radius:6px;display:block}.p{color:#5ed67a}.v{color:#ffb84d}.m{color:#9aa7b4}.bar{background:#151d26;border-radius:8px;padding:8px 10px;margin:8px 0}";

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }

    static String clock(double sec) {
        long s = Math.round(sec);
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    // report가 null이면 아직 결과 없음. runs = 지난 실행 이름(최신순)
    static String build(JSONObject report, JSONObject status, List<String> runs, String shownRun) {
        StringBuilder sb = new StringBuilder("<!doctype html><html lang='ko'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>재생 시험</title><style>")
                .append(CSS).append("</style></head><body><div class='w'><h1>센트리 재생 시험 보고서</h1>");
        if (status != null && status.optBoolean("running")) {
            sb.append("<div class='bar'>진행 중 ").append(status.optInt("done")).append('/').append(status.optInt("events")).append(" · 지금 ")
                    .append(esc(status.optString("current"))).append(" · 표시 ").append(status.optInt("marks")).append(" · 사람 ").append(status.optInt("person"))
                    .append(" · 차량 ").append(status.optInt("vehicle")).append(" <a href='/sentry/replay?run=").append(esc(shownRun)).append("'>새로고침</a></div>");
        }
        if (report == null) {
            sb.append("<div class='sub'>보고서가 아직 없습니다. 시작: POST /sentry/replay {\"date\":\"yyyy-MM-dd\"}</div>");
        } else {
            JSONObject t = report.optJSONObject("totals");
            sb.append("<div class='sub'>").append(esc(report.optString("run"))).append(report.optBoolean("done") ? " · 완료" : " · 진행 중").append(" · 감도 ").append(report.optInt("sensitivity"))
                    .append(" · 최소 ").append(report.optInt("minDetectS")).append("초").append(report.optBoolean("lightChangeFilter") ? " · 조명 무시" : "").append("</div>");
            if (t != null) sb.append("<div class='bar'>녹화 ").append(t.optInt("events")).append("개 · 움직임 표시 ").append(t.optInt("marks")).append("개 · <span class='p'>사람 ")
                    .append(t.optInt("person")).append("</span> · <span class='v'>차량 ").append(t.optInt("vehicle")).append("</span></div>");
            sb.append("<div class='sub'>").append(esc(report.optString("note"))).append("</div>");
            JSONArray evs = report.optJSONArray("events");
            for (int i = 0; evs != null && i < evs.length(); i++) {
                JSONObject ev = evs.optJSONObject(i);
                if (ev == null) continue;
                String id = ev.optString("id");
                sb.append("<h2><a href='/sentry/play?id=").append(esc(id)).append("'>").append(esc(id)).append("</a> <span class='sub'>").append(ev.optDouble("durationS")).append("초 · 조각 ")
                        .append(ev.optInt("segments")).append(ev.has("error") ? " · 오류 " + esc(ev.optString("error")) : "").append("</span></h2>");
                JSONArray ms = ev.optJSONArray("marks");
                if (ms == null || ms.length() == 0) { sb.append("<div class='m'>움직임 없음</div>"); continue; }
                sb.append("<table><tr><th>시각</th><th>영상 칸</th><th>세기</th><th>길이</th><th>판정</th><th></th></tr>");
                for (int k = 0; k < ms.length(); k++) {
                    JSONObject m = ms.optJSONObject(k);
                    if (m == null) continue;
                    int cell = m.optInt("cell", -1);
                    String obj = m.optString("obj", "");
                    int kind = m.optInt("kind", 0);
                    sb.append("<tr><td>").append(clock(m.optDouble("t"))).append("</td><td>").append(cell >= 0 && cell < 4 ? POS[cell] : "영역").append("</td><td>")
                            .append(m.optInt("pct")).append("%</td><td>").append(m.optDouble("dur")).append("초</td><td class='").append(kind == 2 ? "p" : kind == 1 ? "v" : "m").append("'>")
                            .append(obj.isEmpty() ? (kind == 3 ? "조명 번쩍임 의심" : "움직임") : esc(obj)).append("</td><td>");
                    String img = m.optString("img", "");
                    if (!img.isEmpty()) sb.append("<a href='/sentry/replay/img?run=").append(esc(shownRun)).append("&f=").append(esc(img)).append("'><img loading='lazy' src='/sentry/replay/img?run=")
                            .append(esc(shownRun)).append("&f=").append(esc(img)).append("'></a>");
                    sb.append("</td></tr>");
                }
                sb.append("</table>");
            }
        }
        if (runs != null && !runs.isEmpty()) {
            sb.append("<h2>지난 실행</h2><div class='sub'>");
            for (String r : runs) sb.append("<a href='/sentry/replay?run=").append(esc(r)).append("'>").append(esc(r)).append("</a> &nbsp; ");
            sb.append("</div>");
        }
        return sb.append("</div></body></html>").toString();
    }
}
