package com.cseini.bydsniffer.sentry;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 이벤트 메타(meta.json)·목록·달력·즐겨찾기·삭제·썸네일. 레이아웃은 <root>/YYYY-MM/DD/<eventId>/ (설계 저장소 절)
public final class SentryIndex {
    static final String META = "meta.json";
    private static final String THUMB = "thumb.jpg";
    private static final Pattern ID = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}_\\d{2}h\\d{2}m\\d{2}s$");
    // 조각 파일명 — 새 이름 <이벤트id>_NNN.mp4(차 미디어 플레이어에서 시간순으로 보이게, 10-08)와 옛 이름 seg-NNN.mp4를 모두 읽는다
    private static final Pattern SEG = Pattern.compile("^(?:seg-|\\d{4}-\\d{2}-\\d{2}_\\d{2}h\\d{2}m\\d{2}s_)(\\d{3})\\.mp4$");
    private static final Pattern MONTH = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final Pattern DAY = Pattern.compile("^\\d{2}$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    // meta.json 없이 이 시간 넘게 손대지 않은 이벤트 폴더는 비정상 종료로 보고 조각 파일에서 메타를 되살린다
    private static final long ORPHAN_AGE_MS = 15 * 60 * 1000L;
    private static final double ORPHAN_SEGMENT_S = 60.0;

    public static final class Segment {
        public int n;
        public long bytes;
        public double durationS;
    }

    // 녹화 시간축 표시 한 개 — 이벤트 영상 0초 기준 tMs부터 durMs 동안 pct% 세기의 움직임. cell=영상에서 보이는 칸(0~3, 좌상·우상·좌하·우하), 직접 그린 감지 영역이면 -1. kind 0=움직임 1=차량 2=사람
    public static final class Mark {
        public final long tMs;
        public int kind;
        public final int cell;
        public int pct;
        public long durMs;

        public Mark(long tMs, int kind, int cell, int pct) { this(tMs, kind, cell, pct, 0L); }

        public Mark(long tMs, int kind, int cell, int pct, long durMs) {
            this.tMs = tMs; this.kind = kind; this.cell = cell; this.pct = pct; this.durMs = durMs;
        }
    }

    public static final class EventMeta {
        public String id;
        public long startMs;
        public double durationS;
        public boolean favorite;
        public List<Segment> segments = new ArrayList<>();
        public List<Mark> marks = new ArrayList<>();   // 비어 있으면 meta.json에 안 씀(옛 이벤트·연속 녹화)
        public boolean light;   // 조명 번쩍임 의심(새 사람·차량이 없고 그 칸 밝기가 계단처럼 변함) — true일 때만 meta.json에 씀
        public boolean retro;   // 예전 녹화를 재생 시험으로 나중에 판정해 채운 표시(근사) — true일 때만 meta.json에 씀
        public List<float[]> objects = new ArrayList<>();   // 새로 나타난 사람·차량 판정 {종류 1차량·2사람, 점수%, 칸 0~3(영상 기준), x0,y0,x1,y1(칸 대비 0~1)} — 없으면 meta.json에 안 씀

        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("startMs", startMs);
                o.put("durationS", durationS);
                JSONArray arr = new JSONArray();
                for (Segment s : segments) {
                    JSONObject so = new JSONObject();
                    so.put("n", s.n);
                    so.put("bytes", s.bytes);
                    so.put("durationS", s.durationS);
                    arr.put(so);
                }
                o.put("segments", arr);
                o.put("favorite", favorite);
                if (light) o.put("light", true);
                if (retro) o.put("retro", true);
                if (!objects.isEmpty()) {
                    JSONArray oa = new JSONArray();
                    for (float[] ob : objects) {
                        JSONArray one = new JSONArray();
                        for (int i = 0; i < 3; i++) one.put((int) ob[i]);
                        for (int i = 3; i < 7; i++) one.put(Math.round(ob[i] * 1000f) / 1000.0);
                        oa.put(one);
                    }
                    o.put("objects", oa);
                }
                if (!marks.isEmpty()) {
                    JSONArray ma = new JSONArray();
                    for (Mark k : marks) ma.put(new JSONArray().put(k.tMs).put(k.kind).put(k.cell).put(k.pct).put(k.durMs));   // [t, kind, cell, pct, dur] — 작게
                    o.put("marks", ma);
                }
                return o;
            } catch (JSONException e) {
                throw new IllegalStateException("이벤트 메타 JSON 변환 실패: " + e.getMessage(), e);
            }
        }

        public static EventMeta fromJson(JSONObject o) {
            EventMeta m = new EventMeta();
            m.id = o.optString("id", null);
            m.startMs = o.optLong("startMs", 0);
            m.durationS = o.optDouble("durationS", 0);
            m.favorite = o.optBoolean("favorite", false);
            m.light = o.optBoolean("light", false);
            m.retro = o.optBoolean("retro", false);
            JSONArray oa = o.optJSONArray("objects");
            if (oa != null) {
                for (int i = 0; i < oa.length(); i++) {
                    JSONArray ob = oa.optJSONArray(i);
                    if (ob == null || ob.length() < 7) continue;
                    m.objects.add(new float[]{ob.optInt(0), ob.optInt(1), ob.optInt(2), (float) ob.optDouble(3), (float) ob.optDouble(4), (float) ob.optDouble(5), (float) ob.optDouble(6)});
                }
            }
            JSONArray ma = o.optJSONArray("marks");
            if (ma != null) {
                for (int i = 0; i < ma.length(); i++) {
                    JSONArray k = ma.optJSONArray(i);
                    if (k == null || k.length() < 4) continue;
                    m.marks.add(new Mark(k.optLong(0), k.optInt(1), k.optInt(2), k.optInt(3), k.optLong(4, 0L)));
                }
            }
            JSONArray arr = o.optJSONArray("segments");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject so = arr.optJSONObject(i);
                    if (so == null) continue;
                    Segment s = new Segment();
                    s.n = so.optInt("n", i);
                    s.bytes = so.optLong("bytes", 0);
                    s.durationS = so.optDouble("durationS", 0);
                    m.segments.add(s);
                }
            }
            return m;
        }
    }

    // meta.json 읽고-고쳐-쓰기(즐겨찾기·조각 목록 갱신) 직렬화
    static final Object META_LOCK = new Object();

    private final SentryStorage st;

    public SentryIndex(SentryStorage st) {
        this.st = st;
    }

    // 외부(HTTP)에서 온 id가 경로 조작 없이 안전한지 — 파일 접근 전 반드시 통과
    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    // 목록 변경 번호 — 이벤트가 생기거나(쓰기)·지워지거나·즐겨찾기가 바뀔 때마다 오른다. 앱이 목록을 캐시해 두고 이 값이 같으면 다시 조회하지 않게 한다.
    // 릴레이가 재시작돼도 옛 값과 겹치지 않게 시작 시각(36진수 초)을 앞에 붙인다 — 값은 문자열로 같은지만 비교하면 된다(예: "t8x2k1.57")
    private static final String REV_BOOT = Long.toString(System.currentTimeMillis() / 1000L, 36);
    private static final java.util.concurrent.atomic.AtomicLong REV = new java.util.concurrent.atomic.AtomicLong();
    public static void bumpRev() { REV.incrementAndGet(); }
    public static String rev() { return REV_BOOT + "." + REV.get(); }

    public void write(EventMeta m) {
        File dir = eventDir(m.id);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new UncheckedIOException(new IOException("이벤트 폴더 생성 실패: " + dir));
        }
        synchronized (META_LOCK) {
            try {
                writeAtomic(new File(dir, META), m.toJson().toString().getBytes("UTF-8"));
                bumpRev();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    // meta.json이 없거나 못 읽는 폴더는 비정상 종료 이벤트로 보고 조각에서 되살린다(진행 중인 폴더는 제외). 없으면 null
    public EventMeta read(String eventId) {
        if (!isValidId(eventId)) return null;
        return loadMeta(eventDir(eventId));
    }

    public File eventDir(String eventId) {
        return st.eventDirExisting(eventId);
    }

    public List<EventMeta> listDate(String yyyyMmDd, boolean favOnly) {
        List<EventMeta> out = new ArrayList<>();
        if (yyyyMmDd == null || !DATE.matcher(yyyyMmDd).matches()) return out;
        Set<String> seen = new HashSet<>();
        for (File root : st.allRoots()) {   // 현재 루트 + 폴백 때 쓴 다른 단계
            File dayDir = new File(new File(root, yyyyMmDd.substring(0, 7)), yyyyMmDd.substring(8, 10));
            for (File ev : listEventDirs(dayDir)) {
                EventMeta m = loadMeta(ev);
                if (m != null && seen.add(m.id) && (!favOnly || m.favorite)) out.add(m);
            }
        }
        sortNewestFirst(out);
        return out;
    }

    public List<EventMeta> allOldestFirst() {
        List<EventMeta> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File root : st.allRoots()) {
            File[] months = root.listFiles();
            if (months == null) continue;
            for (File month : months) {
                if (!month.isDirectory() || !MONTH.matcher(month.getName()).matches()) continue;
                File[] days = month.listFiles();
                if (days == null) continue;
                for (File day : days) {
                    if (!day.isDirectory() || !DAY.matcher(day.getName()).matches()) continue;
                    for (File ev : listEventDirs(day)) {
                        EventMeta m = loadMeta(ev);
                        if (m != null && seen.add(m.id)) out.add(m);
                    }
                }
            }
        }
        sortNewestFirst(out);
        Collections.reverse(out);
        return out;
    }

    public JSONObject listJson(String date, boolean favOnly) {
        try {
            JSONArray events = new JSONArray();
            for (EventMeta m : listDate(date, favOnly)) {
                JSONObject o = m.toJson();
                o.put("thumb", "/sentry/thumb?id=" + m.id);
                if (alertFile(m.id).isFile()) o.put("alert", "/sentry/alert?id=" + m.id);
                events.put(o);
            }
            JSONObject res = new JSONObject();
            res.put("date", date);
            res.put("events", events);
            return res;
        } catch (JSONException e) {
            throw new IllegalStateException("목록 JSON 변환 실패: " + e.getMessage(), e);
        }
    }

    // {"days":{"29":4,…}} — 날짜(앞 0 없는 일)별 이벤트 수
    public JSONObject calendarJson(String yyyyMm) {
        try {
            JSONObject days = new JSONObject();
            if (yyyyMm != null && MONTH.matcher(yyyyMm).matches()) {
                Map<String, Integer> counts = new TreeMap<>();
                Set<String> seen = new HashSet<>();
                for (File root : st.allRoots()) {
                    File[] dayDirs = new File(root, yyyyMm).listFiles();
                    if (dayDirs == null) continue;
                    for (File day : dayDirs) {
                        if (!day.isDirectory() || !DAY.matcher(day.getName()).matches()) continue;
                        int count = 0;
                        for (File ev : listEventDirs(day)) { EventMeta m = loadMeta(ev); if (m != null && seen.add(m.id)) count++; }
                        if (count > 0) {
                            String k = String.valueOf(Integer.parseInt(day.getName()));
                            Integer prev = counts.get(k);
                            counts.put(k, (prev == null ? 0 : prev) + count);
                        }
                    }
                }
                for (Map.Entry<String, Integer> e : counts.entrySet()) days.put(e.getKey(), e.getValue());
            }
            JSONObject res = new JSONObject();
            res.put("days", days);
            return res;
        } catch (JSONException e) {
            throw new IllegalStateException("달력 JSON 변환 실패: " + e.getMessage(), e);
        }
    }

    public boolean setFavorite(String id, boolean fav) {
        synchronized (META_LOCK) {
            EventMeta m = read(id);
            if (m == null) return false;
            m.favorite = fav;
            write(m);
            return true;
        }
    }

    public boolean delete(String id) {
        if (!isValidId(id)) return false;
        File dir = eventDir(id);
        if (!dir.exists()) return false;
        deleteRecursive(dir);
        if (dir.exists()) return false;
        bumpRev();
        // 비게 된 날짜·월 폴더 정리
        File day = dir.getParentFile();
        if (day != null && day.list() != null && day.list().length == 0 && day.delete()) {
            File month = day.getParentFile();
            if (month != null && MONTH.matcher(month.getName()).matches()
                    && month.list() != null && month.list().length == 0) {
                month.delete();
            }
        }
        return true;
    }

    public File alertFile(String id) {
        return new File(eventDir(id), "alert.jpg");
    }

    // 알림용 사진 — 칸 하나(rx,ry,rw,rh)를 잘라 박스(칸 대비 0~1 {x0,y0,x1,y1}, null이면 라벨만)와 "사람 87%" 라벨을 그린다(운영자 10-09 오버드라이브식). outW 폭 JPEG
    public static byte[] alertJpeg(byte[] nv21, int w, int h, int rx, int ry, int rw, int rh, float[] box, String label, int outW) {
        rx &= ~1; ry &= ~1; rw &= ~1; rh &= ~1;
        if (rw <= 0 || rh <= 0 || rx + rw > w || ry + rh > h || nv21 == null || nv21.length < w * h * 3 / 2) {
            throw new IllegalArgumentException("알림 사진 입력 이상: " + w + "x" + h + " 칸 " + rx + "," + ry + " " + rw + "x" + rh);
        }
        byte[] crop = new byte[rw * rh * 3 / 2];
        for (int y = 0; y < rh; y++) System.arraycopy(nv21, (ry + y) * w + rx, crop, y * rw, rw);
        int uvBase = w * h, cropUv = rw * rh;
        for (int y = 0; y < rh / 2; y++) System.arraycopy(nv21, uvBase + (ry / 2 + y) * w + rx, crop, cropUv + y * rw, rw);
        ByteArrayOutputStream first = new ByteArrayOutputStream(rw * rh / 8);
        if (!new YuvImage(crop, ImageFormat.NV21, rw, rh, null).compressToJpeg(new Rect(0, 0, rw, rh), 88, first)) throw new IllegalStateException("알림 사진 압축 실패");
        byte[] jpeg = first.toByteArray();
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inMutable = true;
        Bitmap bm = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opt);
        if (bm == null) throw new IllegalStateException("알림 사진 디코드 실패");
        try {
            int target = Math.min(outW, bm.getWidth());
            if (bm.getWidth() != target) {
                Bitmap sc = Bitmap.createScaledBitmap(bm, target, Math.max(1, Math.round((float) bm.getHeight() * target / bm.getWidth())), true);
                bm.recycle();
                bm = sc;
            }
            if (!bm.isMutable()) { Bitmap mu = bm.copy(Bitmap.Config.ARGB_8888, true); bm.recycle(); bm = mu; }
            int bw = bm.getWidth(), bh = bm.getHeight();
            android.graphics.Canvas cv = new android.graphics.Canvas(bm);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            float stroke = Math.max(3f, bw / 180f);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setColor(0xFFE02020);
            float l = box == null ? 0f : box[0] * bw, t = box == null ? 0f : box[1] * bh, r = box == null ? 0f : box[2] * bw, b = box == null ? 0f : box[3] * bh;
            if (box != null) cv.drawRect(l, t, r, b, p);
            if (label != null && !label.isEmpty()) {
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setTextSize(Math.max(18f, bw / 20f));
                p.setFakeBoldText(true);
                float tw = p.measureText(label), th = p.getTextSize();
                float lx = Math.min(Math.max(0f, l), Math.max(0f, bw - tw - 12f));
                float ly = t - 6f < th ? Math.min(bh - 4f, t + th + 6f) : t - 6f;   // 박스 위에 자리가 없으면 안쪽 위에
                p.setColor(0xB0000000);
                cv.drawRect(lx - 4f, ly - th, lx + tw + 8f, ly + 8f, p);
                p.setColor(0xFFFFFFFF);
                cv.drawText(label, lx + 2f, ly, p);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(bw * 60);
            if (!bm.compress(Bitmap.CompressFormat.JPEG, 85, out)) throw new IllegalStateException("알림 사진 압축 실패");
            return out.toByteArray();
        } finally {
            bm.recycle();
        }
    }

    public File thumbFile(String id) {
        return new File(eventDir(id), THUMB);
    }

    // 조각 파일명 → 번호(새·옛 이름 모두), 조각 파일이 아니면 null
    static Integer segNumber(String fileName) {
        Matcher m = SEG.matcher(fileName);
        return m.matches() ? Integer.valueOf(Integer.parseInt(m.group(1))) : null;
    }

    public static String segFileName(String eventId, int n) {
        return String.format(Locale.US, "%s_%03d.mp4", eventId, n);
    }

    // 새 이름이 있으면 그것, 없고 옛 이름(seg-NNN.mp4)이 있으면 옛 이름, 둘 다 없으면 새 이름 경로
    public File segmentFile(String id, int n) {
        File dir = eventDir(id);
        File fresh = new File(dir, segFileName(id, n));
        if (fresh.exists()) return fresh;
        File old = new File(dir, String.format(Locale.US, "seg-%03d.mp4", n));
        return old.exists() ? old : fresh;
    }

    // NV21 → JPEG(q85) → 표본 축소 디코드 → outW로 비율 유지 축소 → JPEG(q80). outW가 원본보다 크면 키우지 않는다
    public static byte[] thumbnailJpeg(byte[] nv21, int w, int h, int outW) {
        if (w <= 0 || h <= 0 || outW <= 0 || nv21 == null || nv21.length < w * h * 3 / 2) {
            throw new IllegalArgumentException("썸네일 입력 크기 이상: " + w + "x" + h + " len=" + (nv21 == null ? -1 : nv21.length));
        }
        ByteArrayOutputStream first = new ByteArrayOutputStream(w * h / 8);
        if (!new YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), 85, first)) {
            throw new IllegalStateException("NV21 JPEG 압축 실패 " + w + "x" + h);
        }
        byte[] jpeg = first.toByteArray();
        int target = Math.min(outW, w);
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sampleSize(w, target);
        Bitmap full = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opt);
        if (full == null) throw new IllegalStateException("썸네일 JPEG 디코드 실패");
        Bitmap scaled = full;
        try {
            if (full.getWidth() != target) {
                int outH = Math.max(1, Math.round((float) h * target / w));
                scaled = Bitmap.createScaledBitmap(full, target, outH, true);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(target * 60);
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)) {
                throw new IllegalStateException("썸네일 JPEG 압축 실패");
            }
            return out.toByteArray();
        } finally {
            if (scaled != full) scaled.recycle();
            full.recycle();
        }
    }

    // 디코드 결과 폭이 target 이상 남는 가장 큰 2의 거듭제곱
    static int sampleSize(int w, int target) {
        int s = 1;
        while (target > 0 && w / (s * 2) >= target) s *= 2;
        return s;
    }

    public static String newEventId(long startMs) {
        return newEventId(startMs, TimeZone.getDefault());
    }

    public static String newEventId(long startMs, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd_HH'h'mm'm'ss's'", Locale.US);
        f.setTimeZone(tz);
        return f.format(new Date(startMs));
    }

    // 이벤트 id → 시작 시각(ms), 형식이 틀리면 -1
    static long startMsFromId(String id, TimeZone tz) {
        if (!isValidId(id)) return -1;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd_HH'h'mm'm'ss's'", Locale.US);
        f.setTimeZone(tz);
        f.setLenient(false);
        try {
            return f.parse(id).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    // 최신순(startMs 내림차순, 같으면 id 내림차순)
    static void sortNewestFirst(List<EventMeta> list) {
        Collections.sort(list, new Comparator<EventMeta>() {
            @Override
            public int compare(EventMeta a, EventMeta b) {
                if (a.startMs != b.startMs) return a.startMs > b.startMs ? -1 : 1;
                String ia = a.id == null ? "" : a.id, ib = b.id == null ? "" : b.id;
                return ib.compareTo(ia);
            }
        });
    }

    // 조각 번호(n) 오름차순
    static void sortSegmentsByN(List<Segment> list) {
        Collections.sort(list, new Comparator<Segment>() {
            @Override
            public int compare(Segment a, Segment b) {
                return Integer.compare(a.n, b.n);
            }
        });
    }

    // 임시 파일 → fsync → rename
    static void writeAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(data);
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("rename 실패: " + target);
        }
    }

    // 센트리 폴더 전체 비우기 — 각 루트 아래 YYYY-MM 폴더(이벤트·썸네일·색인·고아 조각 전부)만 지운다. 루트 폴더와 그 밖의 파일은 건드리지 않는다. 지운 월 폴더 수
    static int clearRoots(List<File> roots) {
        int n = 0;
        for (File root : roots) {
            File[] kids = root.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (k.isDirectory() && MONTH.matcher(k.getName()).matches()) {
                    deleteRecursive(k);
                    n++;
                }
            }
        }
        return n;
    }

    static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    private static List<File> listEventDirs(File dayDir) {
        List<File> out = new ArrayList<>();
        File[] kids = dayDir.listFiles();
        if (kids == null) return out;
        for (File k : kids) if (k.isDirectory() && isValidId(k.getName())) out.add(k);
        return out;
    }

    private static EventMeta loadMeta(File dir) {
        File mf = new File(dir, META);
        if (mf.isFile()) {
            try {
                EventMeta m = EventMeta.fromJson(new JSONObject(readText(mf)));
                if (m.id == null) m.id = dir.getName();
                return m;
            } catch (IOException | JSONException e) {
                // 깨진 meta.json은 조각 기반 복원으로 넘어간다
            }
        }
        return synthesize(dir);
    }

    private static EventMeta synthesize(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        List<Segment> segs = new ArrayList<>();
        long newest = 0;
        for (File f : files) {
            newest = Math.max(newest, f.lastModified());
            Integer segN = segNumber(f.getName());
            if (segN != null) {
                Segment s = new Segment();
                s.n = segN;
                s.bytes = f.length();
                s.durationS = ORPHAN_SEGMENT_S;
                segs.add(s);
            }
        }
        if (segs.isEmpty() || System.currentTimeMillis() - newest < ORPHAN_AGE_MS) return null;
        Collections.sort(segs, new Comparator<Segment>() {
            @Override
            public int compare(Segment a, Segment b) {
                return a.n - b.n;
            }
        });
        EventMeta m = new EventMeta();
        m.id = dir.getName();
        m.startMs = startMsFromId(m.id, TimeZone.getDefault());
        m.segments = segs;
        m.durationS = segs.size() * ORPHAN_SEGMENT_S;
        return m;
    }

    private static String readText(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
