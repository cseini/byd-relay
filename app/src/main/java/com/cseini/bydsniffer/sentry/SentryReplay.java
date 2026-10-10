package com.cseini.bydsniffer.sentry;

import com.cseini.motiondetect.Roi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

// 재생 시험(운영자 10-09 "차에 연속녹화 10GB가 있는데 다 받기엔 크다, 차 안에서 판정 모듈로 하나씩 돌려봐") — 운영자판 전용.
// 저장된 녹화 조각을 하드웨어 디코더로 풀어 움직임 감지(SentryMotion)와 객체 판정(SentryObjects + 호스트 YOLO)을 그대로 돌리고,
// 이벤트마다 "몇 초에 어느 칸에서 움직임 몇 %, 사람/차량 몇 %"를 보고서(JSON)와 확인용 사진(박스 포함)으로 남긴다.
// 주의: 녹화 영상은 칸 배치·어안 보정·시각 표시줄이 이미 적용된 화면이다. 실제 감지는 원본 프레임 기준이므로 움직임 세기는 근사값이고(방향성),
//       객체 판정은 운영 중과 같은 입력(보정된 영상 칸)이다. 시각·배터리 표시줄(아래 64px)은 감지에서 뺀다.
public final class SentryReplay {
    static final int OSD_PX = 64;            // 녹화 영상 아래 시각·배터리 표시줄 높이(2560x1920 기준) — 감지에서 뺀다
    static final long BASE_EVERY_MS = 3000L, BASE_MIN_AGE_MS = 4000L, BASE_MAX_AGE_MS = 25_000L;
    static final int MAX_IMAGES = 400, DEFAULT_MAX_EVENTS = 40;
    static final double LIVE_EVAL_HZ = 7.6;   // 운영자 차 5분 로그: 판정 약 2300장/300초
    static final long EVAL_PERIOD_MS = Math.round(1000.0 / LIVE_EVAL_HZ);   // 131ms

    private final SentryHost host;
    private final Supplier<SentryIndex> indexSup;
    private final Supplier<File> rootSup;
    private final Supplier<SentrySettings> settingsSup;
    private final BooleanSupplier recordingSup;   // 센트리가 지금 녹화 중이면 잠깐 쉰다(CPU 경쟁 방지)
    private final Supplier<String> activeIdSup;     // 지금 녹화 중인 이벤트 id(소급 기록에서 건드리지 않는다)
    private final Consumer<String> logger;

    private volatile boolean running, cancel;
    private volatile String runId = "", current = "", error = "";
    private volatile int evTotal, evDone, markTotal, personTotal, vehicleTotal, imgCount;
    private volatile long startedMs, finishedMs;
    private Thread thread;
    private int parallel = 2;

    private synchronized void addMarks(int n) { markTotal += n; }
    private synchronized void addObj(int kind) { if (kind == 2) personTotal++; else vehicleTotal++; }
    private synchronized boolean takeImageSlot() { if (imgCount >= MAX_IMAGES) return false; imgCount++; return true; }
    private synchronized void evFinished() { evDone++; }

    public SentryReplay(SentryHost host, Supplier<SentryIndex> indexSup, Supplier<File> rootSup, Supplier<SentrySettings> settingsSup, BooleanSupplier recordingSup, Supplier<String> activeIdSup, Consumer<String> logger) {
        this.host = host; this.indexSup = indexSup; this.rootSup = rootSup; this.settingsSup = settingsSup; this.recordingSup = recordingSup; this.activeIdSup = activeIdSup; this.logger = logger;
    }

    // ── 순수 도우미(JVM 시험) ───────────────────────────────

    // 녹화 영상(배치·보정 적용된 2x2 화면)의 칸 4개 — 아래 두 칸은 시각·배터리 표시줄을 뺀다. 좌표는 프레임 대비 0~1
    static List<Roi> cellRois(float minPct, long sustainMs, int frameH) {
        float yb = frameH <= 0 ? 0.5f : 1f - (float) OSD_PX / frameH;
        List<Roi> out = new ArrayList<>();
        for (int q = 0; q < 4; q++) {
            float x0 = (q % 2) * 0.5f, y0 = (q / 2) * 0.5f, x1 = x0 + 0.5f, y1 = q / 2 == 0 ? 0.5f : yb;
            out.add(new Roi(q, new float[]{x0, x1, x1, x0}, new float[]{y0, y0, y1, y1}, minPct, sustainMs));
        }
        return out;
    }

    // 지난 실행(최신순)
    static List<String> listRuns(File root) {
        List<String> out = new ArrayList<>();
        File[] ds = new File(root, "_replay").listFiles();
        if (ds != null) for (File d : ds) if (d.isDirectory() && validRunId(d.getName())) out.add(d.getName());
        Collections.sort(out, Collections.reverseOrder());
        return out;
    }

    static JSONObject readReport(File root, String run) {
        File f = new File(root, "_replay/" + run + "/report.json");
        if (!validRunId(run) || !f.isFile()) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new JSONObject(new String(b, 0, n, "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    static String newRunId(long nowMs) {
        return "r" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(nowMs));
    }

    static boolean validRunId(String s) { return s != null && s.matches("^r\\d{8}_\\d{6}$"); }

    static String imageName(String eventId, long tMs, int cell) {
        long s = tMs / 1000;
        return eventId + "_" + String.format(Locale.US, "%02dm%02ds", s / 60, s % 60) + "_c" + cell + ".jpg";
    }

    // 감지 설정 — 칸 배치·보정은 이미 영상에 들어 있으니 항등으로 두고, 시험하려는 값(sensitivity 등)만 덮어쓴다
    static SentrySettings replaySettings(SentrySettings base, JSONObject over) {
        try {
            JSONObject j = base.toJson();
            j.put("layout_map", new JSONArray().put(0).put(1).put(2).put(3));
            j.put("layout_flip", new JSONArray().put(0).put(0).put(0).put(0));
            j.put("roi_enabled", false);
            j.put("cell_views", new JSONObject());
            j.put("motion_only", true);
            if (over != null) for (String k : new String[]{"sensitivity", "min_detect_s", "light_change_filter", "shadow_filter"}) if (over.has(k)) j.put(k, over.get(k));
            return SentrySettings.fromJson(j);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 실행 제어 ──────────────────────────────────────

    public JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("running", running).put("run", runId).put("current", current).put("error", error)
                    .put("events", evTotal).put("done", evDone).put("marks", markTotal).put("person", personTotal).put("vehicle", vehicleTotal)
                    .put("images", imgCount).put("startedMs", startedMs).put("finishedMs", finishedMs);
        } catch (JSONException ignored) { }
        return o;
    }

    public String runDir() { return runId.isEmpty() ? null : new File(rootSup.get(), "_replay/" + runId).getPath(); }

    public synchronized String start(JSONObject req) {
        if (running) return "이미 돌고 있음(" + runId + ")";
        if (!host.replayAllowed()) return "지금은 못 돌림 — 운영자판이 주차(P)·정차 상태이고 12V 전압이 정상이어야 함";
        SentryIndex ix = indexSup.get();
        File root = rootSup.get();
        SentrySettings base = settingsSup.get();
        if (ix == null || root == null || base == null) return "센트리가 아직 준비되지 않음";
        List<SentryIndex.EventMeta> metas = new ArrayList<>();
        JSONArray ids = req.optJSONArray("ids");
        if (ids != null) {
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.optString(i);
                if (!SentryIndex.isValidId(id)) return "id 형식 오류: " + id;
                SentryIndex.EventMeta m = ix.read(id);
                if (m != null && !m.segments.isEmpty()) metas.add(m);
            }
        } else {
            String date = req.optString("date", "");
            if (!date.matches("^\\d{4}-\\d{2}-\\d{2}$")) return "date(yyyy-MM-dd) 또는 ids가 필요함";
            List<SentryIndex.EventMeta> day = ix.listDate(date, false);
            Collections.reverse(day);   // 오래된 것부터
            for (SentryIndex.EventMeta m : day) if (!m.segments.isEmpty()) metas.add(m);
        }
        int skip = Math.max(0, req.optInt("skip", 0)), max = Math.max(1, Math.min(500, req.optInt("limit", DEFAULT_MAX_EVENTS)));
        List<SentryIndex.EventMeta> pick = new ArrayList<>(metas.subList(Math.min(skip, metas.size()), Math.min(metas.size(), skip + max)));
        if (pick.isEmpty()) return "돌릴 녹화가 없음";
        final JSONObject over = req.optJSONObject("settings");
        final boolean write = req.optBoolean("write", false);
        if (write && over != null && over.length() > 0) return "write(소급 기록)는 설정을 바꾼 시험과 함께 못 씀 — 기본 설정으로만";
        final SentrySettings rs = replaySettings(base, over);
        runId = newRunId(System.currentTimeMillis());
        error = ""; current = ""; evTotal = pick.size(); evDone = 0; markTotal = 0; personTotal = 0; vehicleTotal = 0; imgCount = 0;
        startedMs = System.currentTimeMillis(); finishedMs = 0;
        parallel = Math.max(1, Math.min(3, req.optInt("parallel", 2)));
        cancel = false; running = true;
        final String rid = runId;
        thread = new Thread(() -> runAll(rid, pick, rs, ix, root, write), "sentry-replay");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        thread.start();
        logger.accept("재생 시험 시작 " + rid + " — 녹화 " + pick.size() + "개, 감도 " + rs.sensitivity + (write ? ", 결과를 이벤트 메타에 소급 기록" : ""));
        return null;
    }

    public void stop() { cancel = true; }

    private boolean mustStop() {
        return cancel || !host.replayAllowed();
    }

    private void runAll(String rid, List<SentryIndex.EventMeta> metas, SentrySettings rs, SentryIndex ix, File root, boolean write) {
        File dir = new File(root, "_replay/" + rid);
        dir.mkdirs();
        final JSONObject[] results = new JSONObject[metas.size()];
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        final int n = Math.min(parallel, metas.size());
        final Object reportLock = new Object();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < n; w++) {
            Thread t = new Thread(() -> {
                try {
                    while (true) {
                        int i = next.getAndIncrement();
                        if (i >= metas.size()) return;
                        if (mustStop()) { error = cancel ? "중단" : "주차·정차가 풀려 멈춤"; return; }
                        while (recordingSup.getAsBoolean() && !mustStop()) { try { Thread.sleep(2000); } catch (InterruptedException ie) { cancel = true; } }
                        SentryIndex.EventMeta m = metas.get(i);
                        current = m.id;
                        JSONObject ev = replayEvent(m, rs, ix, dir, write);
                        results[i] = ev;
                        evFinished();
                        synchronized (reportLock) { writeReport(dir, rid, rs, collect(results), false); }
                    }
                } catch (Throwable th) {
                    error = th.getClass().getSimpleName() + ":" + th.getMessage();
                    logger.accept("재생 시험 오류 " + rid + " — " + th);
                }
            }, "sentry-replay-" + w);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 2);
            workers.add(t);
            t.start();
        }
        try {
            for (Thread t : workers) t.join();
        } catch (InterruptedException ie) {
            cancel = true;
        } finally {
            synchronized (reportLock) { writeReport(dir, rid, rs, collect(results), true); }
            running = false;
            finishedMs = System.currentTimeMillis();
            current = "";
            logger.accept("재생 시험 끝 " + rid + " — 녹화 " + evDone + "/" + evTotal + "개, 움직임 표시 " + markTotal + "개(사람 " + personTotal + "·차량 " + vehicleTotal + ")" + (error.isEmpty() ? "" : " · " + error) + " · 동시 " + n + "개");
        }
    }

    private static JSONArray collect(JSONObject[] results) {
        JSONArray a = new JSONArray();
        for (JSONObject r : results) if (r != null) a.put(r);
        return a;
    }

    private void writeReport(File dir, String rid, SentrySettings rs, JSONArray evs, boolean done) {
        try {
            JSONObject r = new JSONObject();
            r.put("run", rid).put("done", done).put("events", evs).put("sensitivity", rs.sensitivity).put("minDetectS", rs.minDetectS)
                    .put("lightChangeFilter", rs.lightChangeFilter).put("shadowFilter", rs.shadowFilter)
                    .put("totals", new JSONObject().put("events", evDone).put("marks", markTotal).put("person", personTotal).put("vehicle", vehicleTotal))
                    .put("note", "녹화 영상(보정·배치·표시줄 적용 화면) 기준 — 움직임 세기는 근사, 칸은 영상에서 보이는 칸");
            File tmp = new File(dir, "report.json.part");
            try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(r.toString().getBytes("UTF-8")); }
            tmp.renameTo(new File(dir, "report.json"));
        } catch (Throwable t) {
            logger.accept("재생 시험 보고서 저장 실패 — " + t);
        }
    }

    // 한 이벤트(조각들을 이어서)를 돌린다. write=true면 끝난 뒤 meta.json에 소급 기록한다
    private JSONObject replayEvent(SentryIndex.EventMeta meta, SentrySettings rs, SentryIndex ix, File dir, boolean write) throws JSONException {
        final SentryMarks marks = new SentryMarks();
        marks.reset(0L, 0L);
        final List<long[]> pending = new ArrayList<>();   // {칸, 처음 시각ms, 다음 시도 시각ms, 시도 횟수}
        final List<JSONObject> objs = new ArrayList<>();
        final byte[][] ring = new byte[3][];
        final long[] ringTs = new long[3];
        final int[] ringIdx = {0};
        final long[] lastRing = {-BASE_EVERY_MS};
        final long[] segOff = {0L};
        final SentryMotion[] mo = {null};
        final boolean[] lightSeen = {false};   // 새 사람·차량 없이 밝기만 계단처럼 변한 칸이 있었나(실시간 판정의 "조명 번쩍임 의심"과 같은 기준)
        final long[] lastEval = {-EVAL_PERIOD_MS};   // 마지막으로 판정한 시각 — 실제 감지는 초당 약 7.6번(131ms마다)이라 시각 기준으로 골라 받는다
        long durMs = 0;
        String fail = null;
        final long[] stats = new long[4];
        int totalFrames = 0;
        long wallNs = 0;
        for (SentryIndex.Segment sg : meta.segments) {
            if (mustStop()) { fail = "중단"; break; }
            File f = ix.segmentFile(meta.id, sg.n);
            if (!f.isFile()) { fail = "조각 없음 " + sg.n; break; }
            final long off = segOff[0];
            int[] frames = new int[1];
            long segT0 = System.nanoTime();
            String err = SentryFrameDecoder.decode(f, new SentryFrameDecoder.Sink() {
                @Override public boolean wants(int index, long ptsMs) {
                    return off + ptsMs - lastEval[0] >= EVAL_PERIOD_MS;
                }

                @Override public void onFrame(byte[] nv21, int w, int h, long ptsMs, int index, Runnable fillChroma) {
                    long t = off + ptsMs;
                    lastEval[0] = t;
                    if (mo[0] == null) mo[0] = new SentryMotion(w, h, rs, cellRois(rs.minMotionPct(), rs.sustainMs(), h));
                    if (t - lastRing[0] >= BASE_EVERY_MS) {   // 기준 화면 — 3초마다 3장 돌려가며(객체 판정에 색이 필요하니 색 평면까지)
                        fillChroma.run();
                        int i = ringIdx[0];
                        if (ring[i] == null || ring[i].length != nv21.length) ring[i] = new byte[nv21.length];
                        System.arraycopy(nv21, 0, ring[i], 0, nv21.length);
                        ringTs[i] = t + 1;   // 0=비어 있음 구분
                        ringIdx[0] = (i + 1) % 3;
                        lastRing[0] = t;
                    }
                    for (int rep = 0; rep < 3; rep++) mo[0].onFrame(nv21, t);   // SentryMotion은 3번째 호출마다 판정
                    for (long[] hit : mo[0].drainConfirmed()) {
                        if (marks.add(hit[0], (int) hit[1], (int) hit[2], 0)) { pending.add(new long[]{hit[1], hit[0], hit[0], 0}); addMarks(1); }
                    }
                    for (int p = pending.size() - 1; p >= 0; p--) {
                        long[] pd = pending.get(p);
                        if (t < pd[2]) continue;
                        fillChroma.run();   // 객체 판정은 색 평면이 필요
                        boolean[] lightNow = {false};
                        JSONObject found = judge(meta.id, nv21, w, h, (int) pd[0], t, ring, ringTs, dir, lightNow);
                        if (found != null) { objs.add(found); pending.remove(p); marks.setCellKind((int) pd[0], found.optInt("kind")); }
                        else if (pd[3] >= 2) pending.remove(p);
                        else {
                            if (pd[3] >= 1 && lightNow[0]) { lightSeen[0] = true; marks.setCellKind((int) pd[0], 3); }   // 두 번 봐도 새 사람·차량이 없고 밝기만 바뀜 → 조명 의심(실시간과 같게 두 번째 판정부터)
                            pd[3]++; pd[2] = t + 2000L;
                        }
                    }
                }
            }, this::mustStop, frames, stats);
            totalFrames += frames[0];
            wallNs += System.nanoTime() - segT0;
            if (err != null) { fail = err; break; }
            segOff[0] += Math.round(sg.durationS * 1000.0);
            durMs = segOff[0];
        }
        JSONObject ev = new JSONObject();
        ev.put("id", meta.id).put("startMs", meta.startMs).put("durationS", Math.round(durMs / 100.0) / 10.0).put("segments", meta.segments.size());
        if (fail != null) ev.put("error", fail);
        List<SentryIndex.Mark> snap = marks.snapshot();
        JSONArray ma = new JSONArray();
        for (SentryIndex.Mark k : snap) {
            JSONObject mj = new JSONObject();
            mj.put("t", Math.round(k.tMs / 100.0) / 10.0).put("cell", k.cell).put("pct", k.pct).put("dur", Math.round(k.durMs / 100.0) / 10.0).put("kind", k.kind);
            for (JSONObject ob : objs) if (ob.optInt("cell") == k.cell && Math.abs(ob.optLong("tMs") - k.tMs) <= 8000L) { mj.put("obj", ob.optString("label")).put("img", ob.optString("img")); break; }
            ma.put(mj);
        }
        ev.put("marks", ma);
        for (JSONObject ob : objs) addObj(ob.optInt("kind"));
        boolean light = lightSeen[0] && objs.isEmpty();   // 실시간과 같게: 사람·차량이 판정되면 조명 의심 아님
        if (light) ev.put("light", true);
        String wrote = "";
        if (write && fail == null) wrote = writeBack(meta.id, ix, snap, objs, dir, light);
        if (!wrote.isEmpty()) ev.put("wrote", wrote);
        double wall = wallNs / 1e9;
        logger.accept("재생 시험 속도 " + meta.id + " — 영상 " + Math.round(durMs / 1000.0) + "초를 " + Math.round(wall) + "초에(" + String.format(Locale.US, "%.1f", wall > 0 ? durMs / 1000.0 / wall : 0.0) + "배속), 프레임 "
                + totalFrames + "개 중 " + stats[2] + "개 받음(밝기 변환 " + Math.round(stats[0] / 1e9) + "초·색 변환 " + Math.round(stats[3] / 1e9) + "초)·감지/판정 " + Math.round(stats[1] / 1e9) + "초, 디코더 " + SentryFrameDecoder.lastDecoderName);
        logger.accept("재생 시험 " + meta.id + " — " + meta.segments.size() + "조각 " + Math.round(durMs / 1000.0) + "초, 움직임 표시 " + ma.length() + "개, 사람·차량 판정 " + objs.size() + "개" + (fail == null ? "" : " · " + fail) + (wrote.isEmpty() ? "" : " · " + wrote));
        return ev;
    }

    // 소급 기록(운영자 10-09 "이전 녹화도 소급해줘") — 이미 marks·objects가 있는 이벤트는 덮지 않고, 쓰면 meta에 retro=true를 남긴다. 진행 중인 이벤트는 건드리지 않는다.
    private String writeBack(String id, SentryIndex ix, List<SentryIndex.Mark> snap, List<JSONObject> objs, File dir, boolean light) {
        try {
            if (id.equals(activeIdSup.get())) return "진행 중이라 안 씀";
            synchronized (SentryIndex.META_LOCK) {
                SentryIndex.EventMeta m = ix.read(id);
                if (m == null) return "메타 없음";
                if (!m.marks.isEmpty() || !m.objects.isEmpty()) return "이미 표시 있음(안 덮음)";
                if (snap.isEmpty()) return "움직임 없음(안 씀)";
                m.marks = new ArrayList<>(snap);
                JSONObject best = null;
                for (JSONObject ob : objs) {
                    m.objects.add(new float[]{ob.optInt("kind"), ob.optInt("pct"), ob.optInt("cell"), (float) ob.optDouble("x0"), (float) ob.optDouble("y0"), (float) ob.optDouble("x1"), (float) ob.optDouble("y1")});
                    if (best == null || ob.optInt("kind") > best.optInt("kind") || (ob.optInt("kind") == best.optInt("kind") && ob.optInt("pct") > best.optInt("pct"))) best = ob;
                }
                m.retro = true;
                m.light = light;
                ix.write(m);
                if (best != null && !best.optString("img").isEmpty() && !ix.alertFile(id).isFile()) {
                    java.nio.file.Files.copy(new File(dir, best.optString("img")).toPath(), ix.alertFile(id).toPath());
                }
                return "소급 기록(표시 " + snap.size() + "·판정 " + objs.size() + ")";
            }
        } catch (Throwable t) {
            return "소급 기록 실패 " + t.getClass().getSimpleName();
        }
    }

    // 이 칸의 움직임 시작 순간에 "기준 화면에 없던" 사람·차량을 찾는다. 찾으면 박스 사진을 저장하고 요약 JSON을 돌려준다
    private JSONObject judge(String eventId, byte[] cur, int w, int h, int cell, long tMs, byte[][] ring, long[] ringTs, File dir, boolean[] lightOut) {
        try {
            byte[] base = null;
            long bestAge = Long.MAX_VALUE;
            for (int i = 0; i < ring.length; i++) {
                if (ring[i] == null || ringTs[i] == 0) continue;
                long age = tMs - (ringTs[i] - 1);
                if (age >= BASE_MIN_AGE_MS && age <= BASE_MAX_AGE_MS && age < bestAge) { bestAge = age; base = ring[i]; }
            }
            if (base == null) return null;
            int cw = w / 2, ch = h / 2, rx = (cell % 2) * cw, ry = (cell / 2) * ch;
            float[][] dc = host.objectDetect(cur, w, h, rx, ry, cw, ch, 0.30f);
            float[][] db = host.objectDetect(base, w, h, rx, ry, cw, ch, 0.30f);
            if (dc == null || db == null) return null;
            SentryObjects.Found f = SentryObjects.pick(SentryObjects.newOnly(dc, db, cell));
            if (f == null) {
                lightOut[0] = SentryObjects.lightStep(SentryObjects.cellMeanY(cur, w, rx, ry, cw, ch), SentryObjects.cellMeanY(base, w, rx, ry, cw, ch))
                        && !SentryObjects.headlightLike(SentryObjects.newSaturated(cur, base, w, rx, ry, cw, ch));   // 멀리서 켜진 헤드라이트는 조명 의심이 아니다(실시간과 같게)
                return null;
            }
            JSONObject o = new JSONObject();
            o.put("tMs", tMs).put("cell", cell).put("kind", f.kind).put("pct", f.pct()).put("label", f.label()).put("x0", f.x0).put("y0", f.y0).put("x1", f.x1).put("y1", f.y1);
            if (takeImageSlot()) {
                String name = imageName(eventId, tMs, cell);
                byte[] jpg = SentryIndex.alertJpeg(cur, w, h, rx, ry, cw, ch, new float[]{f.x0, f.y0, f.x1, f.y1}, f.label(), 480);
                try (FileOutputStream fo = new FileOutputStream(new File(dir, name))) { fo.write(jpg); }
                o.put("img", name);
            }
            return o;
        } catch (Throwable t) {
            logger.accept("재생 시험 판정 오류 " + eventId + " — " + t);
            return null;
        }
    }
}
