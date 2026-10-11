package com.cseini.bydsniffer.sentry;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;

// 센트리 HTTP 라우트(설계 3절). RemoteWebServer.serve가 /sentry/ 경로를 넘겨준다(사설망 게이트는 그쪽에서 이미 통과).
public final class SentryHttp {
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern MONTH = Pattern.compile("\\d{4}-\\d{2}");

    private SentryHttp() {}

    public static boolean handles(String uri) { return uri != null && uri.startsWith("/sentry/"); }

    // 같은 기기 안(루프백)에서 온 요청인가 — 링크락 런처 WebView(127.0.0.1)용. ::1·::ffff:127.0.0.1도 같은 기기다
    static boolean isLoopback(String ip) {
        if (ip == null) return false;
        String s = ip.trim().toLowerCase(Locale.US);
        if (s.startsWith("::ffff:")) s = s.substring(7);
        return s.equals("::1") || s.equals("0:0:0:0:0:0:0:1") || s.equals("localhost") || s.startsWith("127.");
    }

    public static Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        SentryManager m = SentryManager.get();
        try {
            String grantQs = "";
            if (m.lanAuthRequired() && !isLoopback(session.getRemoteIpAddress())) {   // ★공유판: 폰이 페어키로 만든 권한 값(?sgx=&sg=)이 있어야 목록·썸네일·영상·설정 전부 열린다. 같은 헤드유닛(127.0.0.1)의 링크락 런처는 권한 값 없이 허용(운영자 10-11 "로컬 허용")
                Map<String, String> gq = session.getParms();
                String sgx = gq.get("sgx"), sg = gq.get("sg");
                if (sgx == null || sg == null || !m.verifyGrant(sgx, sg)) return text(Response.Status.UNAUTHORIZED, "grant");
                grantQs = SentryAuth.grantQuery(sgx, sg);
            }
            if (Method.GET.equals(method) || Method.HEAD.equals(method)) return serveGet(session, m, uri, grantQs);
            if (Method.POST.equals(method)) return servePost(session, m, uri);
            return text(Response.Status.METHOD_NOT_ALLOWED, "method");
        } catch (Throwable t) {
            m.log("HTTP 처리 오류 " + uri + " — " + t);
            return text(Response.Status.INTERNAL_ERROR, "err " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    private static Response serveGet(IHTTPSession session, SentryManager m, String uri, String grantQs) throws Exception {
        Map<String, String> q = session.getParms();
        if ("/sentry/status".equals(uri)) return json(m.statusJson().toString());
        SentryIndex ix = m.index();
        SentryStorage st = m.storage();
        if (ix == null || st == null) return text(Response.Status.SERVICE_UNAVAILABLE, "센트리 준비 안 됨");

        if ("/sentry/storage".equals(uri)) return json(st.volumesJson().toString());

        if ("/sentry/list".equals(uri)) {
            String date = q.get("date");
            if (date == null || date.isEmpty()) date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (!DATE.matcher(date).matches()) return text(Response.Status.BAD_REQUEST, "date");
            return json(ix.listJson(date, "1".equals(q.get("fav"))).toString());
        }

        if ("/sentry/calendar".equals(uri)) {
            String month = q.get("month");
            if (month == null || month.isEmpty()) month = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
            if (!MONTH.matcher(month).matches()) return text(Response.Status.BAD_REQUEST, "month");
            return json(ix.calendarJson(month).toString());
        }

        if ("/sentry/thumb".equals(uri)) {
            String id = q.get("id");
            if (!SentryIndex.isValidId(id)) return text(Response.Status.BAD_REQUEST, "id");
            File f = ix.thumbFile(id);
            if (!f.isFile()) return text(Response.Status.NOT_FOUND, "no thumb");
            Response r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/jpeg", new FileInputStream(f), f.length());
            r.addHeader("Cache-Control", "private, max-age=3600");
            return r;
        }

        if ("/sentry/replay".equals(uri) || "/sentry/replay/img".equals(uri)) {   // 재생 시험 보고서·확인용 사진(운영자판 전용 기능, 읽기)
            SentryReplay rp = m.replay();
            SentryStorage stx = m.storage();
            if (rp == null || stx == null) return text(Response.Status.NOT_FOUND, "not ready");
            File root = stx.root();
            List<String> runs = SentryReplay.listRuns(root);
            String run = q.get("run");
            if (run == null || run.isEmpty()) run = runs.isEmpty() ? "" : runs.get(0);
            if ("/sentry/replay/img".equals(uri)) {
                String fn = q.get("f");
                if (!SentryReplay.validRunId(run) || fn == null || !fn.matches("^[0-9A-Za-z_\\-]+\\.jpg$")) return text(Response.Status.BAD_REQUEST, "run/f");
                File img = new File(root, "_replay/" + run + "/" + fn);
                if (!img.isFile()) return text(Response.Status.NOT_FOUND, "no image");
                Response r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/jpeg", new FileInputStream(img), img.length());
                r.addHeader("Cache-Control", "private, max-age=3600");
                return r;
            }
            if ("1".equals(q.get("json"))) return json(rp.statusJson().toString());
            return html(SentryReplayPage.build(run.isEmpty() ? null : SentryReplay.readReport(root, run), rp.statusJson(), runs, run));
        }

        if ("/sentry/bsd".equals(uri)) return html(BsdVision.get().pageHtml());   // BSD 후측방 인식 비교(운영자판)
        if ("/sentry/bsd/img".equals(uri)) {
            File bf = BsdVision.get().imageFile(q.get("f"));
            if (bf == null) return text(Response.Status.NOT_FOUND, "no image");
            Response r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/jpeg", new FileInputStream(bf), bf.length());
            r.addHeader("Cache-Control", "private, max-age=3600");
            return r;
        }

        if ("/sentry/alert".equals(uri)) {   // 객체 판정 알림 사진(박스+종류+%) — 없으면 404
            String id = q.get("id");
            if (!SentryIndex.isValidId(id)) return text(Response.Status.BAD_REQUEST, "id");
            File f = ix.alertFile(id);
            if (!f.isFile()) return text(Response.Status.NOT_FOUND, "no alert");
            Response r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/jpeg", new FileInputStream(f), f.length());
            r.addHeader("Cache-Control", "private, max-age=3600");
            return r;
        }

        if ("/sentry/file".equals(uri)) {
            String id = q.get("id");
            if (!SentryIndex.isValidId(id)) return text(Response.Status.BAD_REQUEST, "id");
            int seg;
            try { seg = Integer.parseInt(q.get("seg") == null ? "0" : q.get("seg")); } catch (NumberFormatException e) { return text(Response.Status.BAD_REQUEST, "seg"); }
            if (seg < 0) return text(Response.Status.BAD_REQUEST, "seg");
            return serveFile(session, ix.segmentFile(id, seg), null);
        }

        if ("/sentry/download".equals(uri)) {
            String id = q.get("id");
            if (!SentryIndex.isValidId(id)) return text(Response.Status.BAD_REQUEST, "id");
            // 이벤트 전체를 영상 하나로 합쳐 내려준다(재인코딩 없이 이어 붙임). 합치는 동안 이 요청은 기다린다. 화질 선택(?q=mid|low, 재압축)은 없어졌다 — orig 외 값은 400
            String qual = q.get("q");
            if (qual != null && !qual.isEmpty() && !"orig".equals(qual)) return text(Response.Status.BAD_REQUEST, "q");
            try {
                File whole = m.combinedEvent(id, null);
                return serveFile(session, whole, id + ".mp4");
            } catch (IllegalArgumentException e) {
                return text(Response.Status.BAD_REQUEST, "id");
            } catch (IOException e) {
                m.log("다운로드 합치기 실패 " + id + " — " + e);
                return text(Response.Status.NOT_FOUND, "no event");
            }
        }

        // 폰 앱 WebView·브라우저용 재생 페이지(조각 이어 재생은 페이지 JS가 한다)
        if ("/sentry/play".equals(uri)) {
            String id = q.get("id");
            if (!SentryIndex.isValidId(id)) return text(Response.Status.BAD_REQUEST, "id");
            SentryIndex.EventMeta meta = ix.read(id);
            if (meta == null || meta.segments.isEmpty()) return text(Response.Status.NOT_FOUND, "녹화 없음");
            String playHtml = SentryPlayPage.buildPlayPage(meta, "1".equals(q.get("autoplay")), grantQs);
            return html("1".equals(q.get("ui")) ? SentryUiPage.decoratePlay(playHtml, id, grantQs) : playHtml);   // ui=1 — 런처 화면에서 들어옴: 위에 "← 목록"
        }

        // 헤드유닛 런처용 화면(운영자 10-11): 달력+썸네일 그리드, [전체|즐겨찾기]. 운영자 도구 링크는 없다
        if ("/sentry/ui".equals(uri) || "/sentry/".equals(uri)) {
            String date = q.get("date");
            if (date == null || date.isEmpty()) date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (!DATE.matcher(date).matches()) return text(Response.Status.BAD_REQUEST, "date");
            boolean fav = "1".equals(q.get("fav"));
            java.util.Map<Integer, Integer> calDays = new java.util.TreeMap<>();
            org.json.JSONObject cj = ix.calendarJson(date.substring(0, 7)).optJSONObject("days");
            if (cj != null) for (java.util.Iterator<String> it = cj.keys(); it.hasNext(); ) { String k = it.next(); calDays.put(Integer.parseInt(k), cj.optInt(k)); }
            List<SentryIndex.EventMeta> evs = fav ? recentFavorites(ix, 90, 120) : ix.listDate(date, false);
            return html(SentryUiPage.build(date, evs, grantQs, calDays, fav, new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date())));
        }

        if ("/sentry/web".equals(uri)) {
            String date = q.get("date");
            if (date == null || date.isEmpty()) date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (!DATE.matcher(date).matches()) return text(Response.Status.BAD_REQUEST, "date");
            java.util.Map<Integer, Integer> calDays = new java.util.TreeMap<>();
            org.json.JSONObject cj = ix.calendarJson(date.substring(0, 7)).optJSONObject("days");
            if (cj != null) for (java.util.Iterator<String> it = cj.keys(); it.hasNext(); ) { String k = it.next(); calDays.put(Integer.parseInt(k), cj.optInt(k)); }
            return html(SentryPlayPage.buildWebPage(date, ix.listDate(date, false), grantQs, calDays));
        }

        return text(Response.Status.NOT_FOUND, "not found");
    }

    // 즐겨찾기 모아 보기 — 최근 maxDays일 안에서 즐겨찾기한 이벤트를 최신순으로 limit개까지(날짜별 폴더를 하나씩 본다)
    private static List<SentryIndex.EventMeta> recentFavorites(SentryIndex ix, int maxDays, int limit) {
        List<SentryIndex.EventMeta> out = new java.util.ArrayList<>();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        java.util.Calendar c = java.util.Calendar.getInstance();
        for (int i = 0; i < maxDays && out.size() < limit; i++) {
            out.addAll(ix.listDate(f.format(c.getTime()), true));
            c.add(java.util.Calendar.DAY_OF_MONTH, -1);
        }
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static Response servePost(IHTTPSession session, SentryManager m, String uri) throws Exception {
        if (!"/sentry/fav".equals(uri) && !"/sentry/delete".equals(uri)
                && !"/sentry/settings".equals(uri) && !"/sentry/pause".equals(uri) && !"/sentry/clear".equals(uri) && !"/sentry/cut".equals(uri) && !"/sentry/replay".equals(uri) && !"/sentry/bsd/label".equals(uri)) {
            return text(Response.Status.NOT_FOUND, "not found");
        }
        String ct = session.getHeaders().get("content-type");
        if (ct == null || !ct.trim().toLowerCase(Locale.US).startsWith("application/json")) {
            return text(Response.Status.UNSUPPORTED_MEDIA_TYPE, "content-type");
        }
        // RemoteWebServer /cmd와 같은 방식 — charset 없는 Content-Type이면 NanoHTTPD가 US-ASCII로 읽어 한글이 깨진다
        session.getHeaders().put("content-type", "application/json; charset=utf-8");
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String body = files.get("postData");
        if (body == null || body.isEmpty()) return text(Response.Status.BAD_REQUEST, "body");
        JSONObject o;
        try {
            o = new JSONObject(body);
        } catch (org.json.JSONException je) {
            m.log("HTTP 본문 파싱 실패 " + uri + " — " + je.getMessage());
            return text(Response.Status.BAD_REQUEST, "json");
        }

        if ("/sentry/settings".equals(uri)) return json(m.applySet(o).toString());
        if ("/sentry/bsd/label".equals(uri)) {   // BSD 인식 비교 — 운영자가 "차 있음/없음/모르겠음" 표시(v: 1·0·-1, null이면 취소)
            Integer v = o.isNull("v") ? null : Integer.valueOf(o.optInt("v", -1));
            return json(BsdVision.ok(BsdVision.get().label(o.optString("id"), o.optString("side"), v)).toString());
        }
        if ("/sentry/replay".equals(uri)) {   // 재생 시험 시작·중지 — 운영자판이 주차(P)·정차일 때만 시작된다
            SentryReplay rp = m.replay();
            if (rp == null) return json(new JSONObject().put("ok", false).put("error", "센트리 초기화 전").toString());
            if ("stop".equals(o.optString("action"))) { rp.stop(); return json(new JSONObject().put("ok", true).put("stopping", true).toString()); }
            String err = rp.start(o);
            return json(err == null ? rp.statusJson().put("ok", true).toString() : new JSONObject().put("ok", false).put("error", err).toString());
        }
        if ("/sentry/cut".equals(uri)) return json(m.cutNow().toString());   // 지금 보기 — 현재 조각을 바로 닫음
        if ("/sentry/clear".equals(uri)) return json(m.clearAll(o.optString("confirm", "")).toString());   // 전체 삭제 — confirm:"all"이 있어야만

        if ("/sentry/pause".equals(uri)) {
            m.pauseByPhone(o.optString("reason", ""), o.optBoolean("paused", true));
            return json(m.statusJson().toString());
        }

        SentryIndex ix = m.index();
        if (ix == null) return text(Response.Status.SERVICE_UNAVAILABLE, "센트리 준비 안 됨");
        if ("/sentry/fav".equals(uri) && o.has("ids")) {   // 여러 영상 즐겨찾기 — {"ids":[…],"favorite":true|false}(최대 100개). 응답은 SentryManager.favoriteEvents 형식
            org.json.JSONArray arr = o.optJSONArray("ids");
            if (arr == null || arr.length() == 0) return text(Response.Status.BAD_REQUEST, "ids");
            List<String> list = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) list.add(arr.optString(i, ""));
            return json(m.favoriteEvents(list, o.optBoolean("favorite", false)).toString());
        }
        if ("/sentry/delete".equals(uri) && o.has("ids")) {   // 여러 영상 삭제 — {"ids":["…","…"]}(최대 100개). 응답은 SentryManager.deleteEvents 형식
            org.json.JSONArray arr = o.optJSONArray("ids");
            if (arr == null || arr.length() == 0) return text(Response.Status.BAD_REQUEST, "ids");
            List<String> list = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) list.add(arr.optString(i, ""));
            return json(m.deleteEvents(list).toString());
        }
        String id = o.optString("id", "");
        if (!SentryIndex.isValidId(id)) return text(Response.Status.BAD_REQUEST, "id");

        if ("/sentry/fav".equals(uri)) {
            boolean ok = ix.setFavorite(id, o.optBoolean("favorite", false));
            m.log("즐겨찾기 " + id + " " + (o.optBoolean("favorite", false) ? "설정" : "해제") + (ok ? "" : " 실패(이벤트 없음)"));
            return json(new JSONObject().put("ok", ok).toString());
        }

        // /sentry/delete — 녹화 중인 이벤트는 지우지 않는다
        if (id.equals(m.recordingEventId())) return json(new JSONObject().put("ok", false).put("error", "녹화 중").toString());
        boolean ok = ix.delete(id);
        m.log("이벤트 삭제 " + id + (ok ? "" : " 실패"));
        return json(new JSONObject().put("ok", ok).toString());
    }

    // 파일 응답 — Range 있으면 206, 없으면 200. attachmentName이 있으면 다운로드로 내려준다.
    private static Response serveFile(IHTTPSession session, File f, String attachmentName) throws IOException {
        if (f == null || !f.isFile()) return text(Response.Status.NOT_FOUND, "no file");
        long len = f.length();
        String header = session.getHeaders().get("range");
        Response r;
        if (header == null) {
            r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "video/mp4", new FileInputStream(f), len);
        } else {
            long[] rg = parseRange(header, len);
            if (rg == null) {
                r = text(Response.Status.RANGE_NOT_SATISFIABLE, "range");
                r.addHeader("Content-Range", "bytes */" + len);
                return r;
            }
            long n = rg[1] - rg[0] + 1;
            FileInputStream fis = new FileInputStream(f);
            try {
                skipFully(fis, rg[0]);
            } catch (IOException e) {
                fis.close();
                throw e;
            }
            r = NanoHTTPD.newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "video/mp4", new LimitedStream(fis, n), n);
            r.addHeader("Content-Range", "bytes " + rg[0] + "-" + rg[1] + "/" + len);
            r.addHeader("Content-Length", String.valueOf(n));
        }
        r.addHeader("Accept-Ranges", "bytes");
        if (header == null) r.addHeader("Content-Length", String.valueOf(len));
        if (attachmentName != null) r.addHeader("Content-Disposition", "attachment; filename=\"" + attachmentName + "\"");
        return r;
    }

    // "bytes=a-b" · "bytes=a-" · "bytes=-n" → {start,end}(양끝 포함). 형식 불량·범위 밖·다중 범위면 null(→ 416)
    static long[] parseRange(String header, long len) {
        if (header == null || len <= 0) return null;
        String s = header.trim().toLowerCase(Locale.US);
        if (!s.startsWith("bytes=")) return null;
        s = s.substring(6).trim();
        if (s.indexOf(',') >= 0) return null;
        int dash = s.indexOf('-');
        if (dash < 0) return null;
        String a = s.substring(0, dash).trim();
        String b = s.substring(dash + 1).trim();
        try {
            long start, end;
            if (a.isEmpty()) {
                if (b.isEmpty()) return null;
                long n = Long.parseLong(b);
                if (n <= 0) return null;
                start = Math.max(0, len - n);
                end = len - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? len - 1 : Long.parseLong(b);
                if (end >= len) end = len - 1;
            }
            if (start < 0 || start >= len || end < start) return null;
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) throw new IOException("skip 실패");
            left -= s;
        }
    }

    // 길이 제한 스트림 — 범위 끝까지만 읽고 EOF
    private static final class LimitedStream extends FilterInputStream {
        private long left;
        LimitedStream(InputStream in, long limit) { super(in); left = limit; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }
        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(buf, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
        @Override public int available() throws IOException { return (int) Math.min(super.available(), left); }
    }

    private static Response json(String s) {
        Response r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", s == null ? "{}" : s);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private static Response html(String s) {
        Response r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", s);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private static Response text(Response.Status st, String s) {
        return NanoHTTPD.newFixedLengthResponse(st, "text/plain; charset=utf-8", s);
    }
}
