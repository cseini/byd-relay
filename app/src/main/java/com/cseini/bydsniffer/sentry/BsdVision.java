package com.cseini.bydsniffer.sentry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// BSD 후측방 인식 측정(1단계, 운영자 10-09 "BSD 신호 때 항상 인식, 알림은 깜빡이 켰을 때만, 해당 방향을 더 크게") — 동작은 안 바꾸고 기록만 한다.
// 차 BSD 신호(좌우 합산 하나)가 올 때마다 깜빡이와 상관없이 양쪽 측면 카메라의 후측방을 현재 방식(EfficientDet-Lite0)과 YOLO11n으로 같이 판정해
// 점수·시간·화면을 남기고, 운영자가 "진짜 차였나"를 표시하면 두 모델의 정확도를 같은 기준으로 비교한다. /sentry/bsd 페이지.
public final class BsdVision {
    public interface Host {
        boolean enabled();                                  // 운영자판·돌핀·설정 켬
        // 최근 1.5초 안 측면 카메라 프레임(없으면 null). {nv21, w, h}는 frame()이 돌려주는 holder로
        Frame frame();
        byte[] sideView(Frame f, int side);                  // 측후방을 사이드미러처럼 편 320x320 NV21(side 2=좌 4=우). 실패 null
        float[] oldBest(byte[] view);                        // 현재 방식 최고 {점수, cx, cy, w, h}(없으면 null)
        float[][] yoloBoxes(byte[] view, float minScore);    // YOLO11n 박스 {종류, 점수, x0,y0,x1,y1}(0~1), 실패 null
        int turnDirection();                                 // 지금 깜빡이: 2 좌·4 우·1 없음
        File dir();
        void log(String msg);
    }

    public static final class Frame {
        public final byte[] nv21; public final int w, h; public final long ageMs;
        public Frame(byte[] nv21, int w, int h, long ageMs) { this.nv21 = nv21; this.w = w; this.h = h; this.ageMs = ageMs; }
    }

    static final int MAX_EVENTS = 200;
    static final float DEC = 0.30f;   // 두 모델 모두 "차 있음" 판정 기준(지금 BSD 방식과 같은 0.3)

    private static final BsdVision INSTANCE = new BsdVision();
    public static BsdVision get() { return INSTANCE; }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "bsd-vision"); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });
    private final Object lock = new Object();
    private volatile Host host;
    private long lastRecordMs;

    public void setHost(Host h) { host = h; }

    // BSD 신호(값 2)가 온 순간 호출 — 깜빡이 여부와 상관없이 기록. 연달아 오는 신호는 3초에 한 번만
    public void onBsd(String why) {
        Host h = host;
        if (h == null || !h.enabled()) return;
        long now = System.currentTimeMillis();
        synchronized (lock) { if (now - lastRecordMs < 3000L) return; lastRecordMs = now; }
        final int turn = h.turnDirection();
        exec.execute(() -> record(h, why, now, turn));
    }

    private void record(Host h, String why, long ts, int turn) {
        try {
            Frame f = h.frame();
            if (f == null) { h.log("BSD 인식 — " + why + ": 측면 카메라 프레임 없음(보조 카메라가 꺼져 있거나 오래됨)"); return; }
            String id = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date(ts));
            File dir = new File(h.dir(), "BsdVision");
            dir.mkdirs();
            JSONObject ev = new JSONObject().put("id", id).put("ts", ts).put("why", why).put("turn", turn).put("frameAgeMs", f.ageMs);
            JSONArray sides = new JSONArray();
            StringBuilder lg = new StringBuilder("BSD 인식 — " + why + " · 깜빡이 " + (turn == 2 ? "좌" : turn == 4 ? "우" : "없음") + " ·");
            for (int side : new int[]{2, 4}) {
                String nm = side == 2 ? "L" : "R";
                byte[] view = h.sideView(f, side);
                if (view == null) { sides.put(new JSONObject().put("side", nm).put("error", "화면 못 폄")); continue; }
                long t0 = System.nanoTime();
                float[] old = h.oldBest(view);
                long t1 = System.nanoTime();
                float[][] yb = h.yoloBoxes(view, 0.20f);
                long t2 = System.nanoTime();
                float oldScore = old == null ? 0f : old[0];
                float[] best = null;
                if (yb != null) for (float[] b : yb) if (best == null || b[1] > best[1]) best = b;
                float yoloScore = best == null ? 0f : best[1];
                JSONObject so = new JSONObject().put("side", nm).put("old", Math.round(oldScore * 100) / 100.0).put("oldMs", (t1 - t0) / 1_000_000L)
                        .put("yolo", Math.round(yoloScore * 100) / 100.0).put("yoloMs", (t2 - t1) / 1_000_000L).put("label", JSONObject.NULL);
                if (yb == null) so.put("yoloError", true);
                try {
                    String lab = "현재 " + String.format(Locale.US, "%.2f", oldScore) + " · YOLO " + String.format(Locale.US, "%.2f", yoloScore);
                    byte[] jpg = SentryIndex.alertJpeg(view, 320, 320, 0, 0, 320, 320, best == null ? null : new float[]{best[2], best[3], best[4], best[5]}, lab, 320);
                    try (FileOutputStream fo = new FileOutputStream(new File(dir, id + "_" + nm + ".jpg"))) { fo.write(jpg); }
                    so.put("img", id + "_" + nm + ".jpg");
                } catch (Throwable t) { so.put("imgError", t.getClass().getSimpleName()); }
                sides.put(so);
                lg.append(' ').append(nm).append(" 현재 ").append(String.format(Locale.US, "%.2f", oldScore)).append('(').append((t1 - t0) / 1_000_000L).append("ms) YOLO ")
                        .append(String.format(Locale.US, "%.2f", yoloScore)).append('(').append((t2 - t1) / 1_000_000L).append("ms)");
            }
            ev.put("sides", sides);
            append(dir, ev);
            h.log(lg.toString());
        } catch (Throwable t) {
            h.log("BSD 인식 오류 — " + t);
        }
    }

    // ── 저장(bsd.json: 최근 200건) ──────────────────────────

    private JSONArray load(File dir) {
        File f = new File(dir, "bsd.json");
        if (!f.isFile()) return new JSONArray();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new JSONArray(new String(b, 0, n, "UTF-8"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void save(File dir, JSONArray a) throws Exception {
        File tmp = new File(dir, "bsd.json.part");
        try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(a.toString().getBytes("UTF-8")); }
        tmp.renameTo(new File(dir, "bsd.json"));
    }

    private void append(File dir, JSONObject ev) throws Exception {
        synchronized (lock) {
            JSONArray a = load(dir);
            JSONArray out = new JSONArray();
            int start = Math.max(0, a.length() - (MAX_EVENTS - 1));
            for (int i = start; i < a.length(); i++) out.put(a.get(i));
            for (int i = 0; i < start; i++) { String img = ((JSONObject) a.get(i)).optString("id"); new File(dir, img + "_L.jpg").delete(); new File(dir, img + "_R.jpg").delete(); }
            out.put(ev);
            save(dir, out);
        }
    }

    // 운영자 표시: v 1=차 있음, 0=차 없음, -1=모르겠음(표시 취소는 null)
    public boolean label(String id, String side, Integer v) {
        Host h = host;
        if (h == null || id == null || !id.matches("^\\d{8}_\\d{6}_\\d{3}$") || !("L".equals(side) || "R".equals(side))) return false;
        File dir = new File(h.dir(), "BsdVision");
        synchronized (lock) {
            JSONArray a = load(dir);
            for (int i = 0; i < a.length(); i++) {
                JSONObject ev = a.optJSONObject(i);
                if (ev == null || !id.equals(ev.optString("id"))) continue;
                JSONArray sides = ev.optJSONArray("sides");
                for (int k = 0; sides != null && k < sides.length(); k++) {
                    JSONObject so = sides.optJSONObject(k);
                    if (so != null && side.equals(so.optString("side"))) {
                        try { so.put("label", v == null ? JSONObject.NULL : (Object) v); save(dir, a); return true; } catch (Exception e) { return false; }
                    }
                }
            }
        }
        return false;
    }

    public File imageFile(String name) {
        Host h = host;
        if (h == null || name == null || !name.matches("^\\d{8}_\\d{6}_\\d{3}_[LR]\\.jpg$")) return null;
        File f = new File(new File(h.dir(), "BsdVision"), name);
        return f.isFile() ? f : null;
    }

    public JSONArray events() {
        Host h = host;
        return h == null ? new JSONArray() : load(new File(h.dir(), "BsdVision"));
    }

    // ── 정확도 집계(표시된 것만): 모델별 {TP, FP, FN, TN} — "차 있음" 점수 기준 DEC ───────────
    static int[] confusion(JSONArray events, String model) {
        int tp = 0, fp = 0, fn = 0, tn = 0;
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.optJSONObject(i);
            JSONArray sides = ev == null ? null : ev.optJSONArray("sides");
            for (int k = 0; sides != null && k < sides.length(); k++) {
                JSONObject so = sides.optJSONObject(k);
                if (so == null || so.isNull("label") || !so.has("label")) continue;
                int lab = so.optInt("label", -1);
                if (lab != 0 && lab != 1) continue;
                boolean pos = so.optDouble(model, 0) >= DEC;
                if (lab == 1) { if (pos) tp++; else fn++; } else { if (pos) fp++; else tn++; }
            }
        }
        return new int[]{tp, fp, fn, tn};
    }

    static String page(JSONArray events) {
        List<JSONObject> list = new ArrayList<>();
        for (int i = events.length() - 1; i >= 0; i--) { JSONObject o = events.optJSONObject(i); if (o != null) list.add(o); }
        int[] co = confusion(events, "old"), cy = confusion(events, "yolo");
        StringBuilder sb = new StringBuilder("<!doctype html><html lang='ko'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>BSD 인식</title><style>"
                + "body{margin:0;background:#0b0f14;color:#e6edf3;font-family:-apple-system,'Apple SD Gothic Neo','Noto Sans KR',sans-serif}.w{max-width:900px;margin:0 auto;padding:12px}"
                + "h1{font-size:18px}.sub{color:#9aa7b4;font-size:13px}.ev{background:#151d26;border-radius:10px;padding:10px;margin:10px 0}.row{display:flex;gap:10px;flex-wrap:wrap}"
                + ".s{flex:1;min-width:300px}img{width:100%;max-width:320px;border-radius:8px;display:block}button{margin:4px 4px 0 0;padding:6px 10px;border:0;border-radius:14px;background:#1c2733;color:#e6edf3}"
                + "button.on{background:#2f6fdb}.bar{background:#151d26;border-radius:8px;padding:8px 10px;margin:8px 0;font-size:13px}</style></head><body><div class='w'><h1>BSD 후측방 인식 비교</h1>"
                + "<div class='sub'>BSD 신호가 올 때마다 양쪽 후측방을 현재 방식과 YOLO11n으로 판정한 기록. 화면을 보고 <b>차 있음/없음</b>을 눌러 주세요(동작은 안 바뀝니다).</div>");
        sb.append("<div class='bar'>표시된 것 기준(점수 ≥ ").append(DEC).append("을 차 있음으로): 현재 방식 TP ").append(co[0]).append(" · FP ").append(co[1]).append(" · FN ").append(co[2]).append(" · TN ").append(co[3])
                .append(" &nbsp;|&nbsp; YOLO11n TP ").append(cy[0]).append(" · FP ").append(cy[1]).append(" · FN ").append(cy[2]).append(" · TN ").append(cy[3]).append("</div>");
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA);
        for (JSONObject ev : list) {
            sb.append("<div class='ev'><div class='sub'>").append(f.format(new Date(ev.optLong("ts")))).append(" · ").append(esc(ev.optString("why"))).append(" · 깜빡이 ")
                    .append(ev.optInt("turn") == 2 ? "좌" : ev.optInt("turn") == 4 ? "우" : "없음").append(" · 프레임 ").append(ev.optLong("frameAgeMs")).append("ms 전</div><div class='row'>");
            JSONArray sides = ev.optJSONArray("sides");
            for (int k = 0; sides != null && k < sides.length(); k++) {
                JSONObject so = sides.optJSONObject(k);
                if (so == null) continue;
                String nm = so.optString("side");
                int lab = so.isNull("label") ? 9 : so.optInt("label", 9);
                String id = ev.optString("id");
                sb.append("<div class='s'><b>").append("L".equals(nm) ? "좌측 후방" : "우측 후방").append("</b> <span class='sub'>현재 ").append(so.optDouble("old")).append("(").append(so.optLong("oldMs")).append("ms) · YOLO ")
                        .append(so.optDouble("yolo")).append("(").append(so.optLong("yoloMs")).append("ms)</span>");
                if (so.has("img")) sb.append("<img loading='lazy' src='/sentry/bsd/img?f=").append(esc(so.optString("img"))).append("'>");
                if (so.has("error")) sb.append("<div class='sub'>").append(esc(so.optString("error"))).append("</div>");
                sb.append("<div>");
                for (int[] bt : new int[][]{{1, 0}, {0, 0}, {-1, 0}}) {
                    sb.append("<button class='").append(lab == bt[0] ? "on" : "").append("' onclick=\"lab('").append(id).append("','").append(nm).append("',").append(bt[0]).append(",this)\">")
                            .append(bt[0] == 1 ? "차 있음" : bt[0] == 0 ? "차 없음" : "모르겠음").append("</button>");
                }
                sb.append("</div></div>");
            }
            sb.append("</div></div>");
        }
        if (list.isEmpty()) sb.append("<div class='sub'>아직 기록이 없습니다. BSD 신호가 오면 쌓입니다(보조 카메라가 켜져 있어야 함).</div>");
        sb.append("<script>function lab(id,s,v,b){fetch('/sentry/bsd/label',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:id,side:s,v:v})})"
                + ".then(function(r){return r.json();}).then(function(j){if(j.ok){var p=b.parentNode.children;for(var i=0;i<p.length;i++)p[i].className='';b.className='on';}else alert('저장 실패');});}</script>");
        return sb.append("</div></body></html>").toString();
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;"); }

    public String pageHtml() { return page(events()); }

    public static JSONObject ok(boolean v) { try { return new JSONObject().put("ok", v); } catch (JSONException e) { return new JSONObject(); } }
}
