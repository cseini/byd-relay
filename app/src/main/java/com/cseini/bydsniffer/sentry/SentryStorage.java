package com.cseini.bydsniffer.sentry;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

// 저장소 선택(내부/SD/USB) → 실제 루트(공용 → 앱전용 → 내부 폴백), 용량·예상 시간, 한도 초과 정리, 고아 .part 정리
public final class SentryStorage {
    private static final long MB = 1024L * 1024L;
    // 선택한 외장 저장소가 폴백 중이면 이 간격마다 다시 시도한다(마운트 복귀 대비)
    private static final long RETRY_MS = 60_000L;
    // 폴백 중 재해석·진행 중 이벤트 판별용
    private static final String PART_SUFFIX = ".part";

    private static final class Vol {
        final String uuid;
        final String path;
        final String label;

        Vol(String uuid, String path, String label) {
            this.uuid = uuid;
            this.path = path;
            this.label = label;
        }
    }

    private static final class Resolved {
        final File root;
        final String tier;
        final long at;

        Resolved(File root, String tier) {
            this.root = root;
            this.tier = tier;
            this.at = System.currentTimeMillis();
        }
    }

    private final Context ctx;
    private final SentrySettings settings;
    private Resolved cached;
    private boolean pinned;

    public SentryStorage(Context ctx, SentrySettings s) {
        this.ctx = ctx;
        this.settings = s;
    }

    // 이벤트 진행 중 루트를 고정한다 — 고정 중에는 폴더 재검사·외장 복귀 재시도를 하지 않는다
    public synchronized void pin() {
        pinned = true;
        root();
    }

    public synchronized void unpin() {
        pinned = false;
    }

    // 이벤트 경계(10분 교체)에서만 부른다 — 고정 중인데 폴백(외장 선택·공용 폴더 아님)이고 외장이 지금 마운트돼 있으면
    //   루트를 다시 해석해 새 이벤트부터 거기에 쓴다(10-05 실측: 무장 4초 뒤 SD가 마운트돼 녹화 내내 내장에 쓴 결함).
    //   바뀌었으면 "이전 단계 이전경로" 문자열, 아니면 null
    public synchronized String repinIfFallback() {
        Resolved r = cached;
        if (r == null || !shouldRepin(pinned, settings.storage, r.tier, !removableMounted().isEmpty())) return null;
        Resolved n = resolve();
        if (n.root.equals(r.root)) return null;
        cached = n;
        return r.tier + " " + r.root.getAbsolutePath();
    }

    // 순수 판정: 고정 중 + 외장 선택 + 공용 폴더 아님 + 외장 마운트됨 일 때만 다시 해석
    public static boolean shouldRepin(boolean pinned, String selected, String tier, boolean removableMounted) {
        return pinned && !"internal".equals(selected) && !"public".equals(tier) && removableMounted;
    }

    // 다른 저장소 객체가 쓰던 루트를 이어받아 고정(설정 갱신으로 객체만 바꿀 때)
    public void pinTo(SentryStorage other) {
        Resolved r = other == null ? null : other.snapshot();
        synchronized (this) {
            if (r != null) cached = r;
            pin();
        }
    }

    private synchronized Resolved snapshot() {
        return cached;
    }

    public synchronized File root() {
        Resolved r = cached;
        if (pinned && r != null) return r.root;
        if (r != null && r.root.isDirectory()) {
            boolean stepDown = !"internal".equals(settings.storage) && !"public".equals(r.tier);
            if (!stepDown || System.currentTimeMillis() - r.at < RETRY_MS) return r.root;
        }
        cached = resolve();
        return cached.root;
    }

    public synchronized String tier() {
        root();
        return cached.tier;
    }

    // 외장(SD·USB)이 지금 하나라도 마운트돼 있는가 — 무장 직전 마운트 대기 판정용
    public boolean externalMounted() { return !removableMounted().isEmpty(); }

    // 외장을 골랐는데 내장·앱전용에 대신 쓰는 이유 — 폰이 이유별로 안내한다(10-06: 시동 끈 뒤 SD가 아직 안 붙어서인데 'ADB 허용' 안내가 나가던 것)
    //   "unmounted"=외장이 아직 안 붙음(곧 붙으면 자동 복귀), "no_write"=붙어 있는데 쓰기 못 함(무선 ADB 권한 등). 폴백이 아니면 null
    public String fallbackWhy() {
        if (!fallback()) return null;
        return removableMounted().isEmpty() ? "unmounted" : "no_write";
    }

    // 외장을 골랐는데 공용 폴더가 아닌 곳(앱전용·내부)에 쓰고 있으면 true
    public boolean fallback() {
        return !"internal".equals(settings.storage) && !"public".equals(tier());
    }

    public String selectedKind() {
        return settings.storage;
    }

    public JSONObject volumesJson() {
        try {
            long limit = limitMb();
            JSONObject o = new JSONObject();
            o.put("selected", settings.storage);
            o.put("tier", tier());
            o.put("fallback", fallback());
            o.put("root", root().getPath());
            o.put("limitMb", limit);
            JSONArray vols = new JSONArray();
            vols.put(internalJson());
            List<Vol> rem = removableMounted();
            vols.put(removableJson("sd", rem.size() > 0 ? rem.get(0) : null, "sd".equals(settings.storage)));
            vols.put(removableJson("usb", rem.size() > 1 ? rem.get(1) : null, "usb".equals(settings.storage)));
            o.put("volumes", vols);
            JSONObject est = new JSONObject();
            est.put("hd", calcEstimateHours(limit, kbpsFor("hd")));
            est.put("fhd", calcEstimateHours(limit, kbpsFor("fhd")));
            est.put("original", calcEstimateHours(limit, kbpsFor("original")));
            o.put("estimateHours", est);
            return o;
        } catch (JSONException e) {
            throw new IllegalStateException("저장소 JSON 변환 실패: " + e.getMessage(), e);
        }
    }

    // 센트리가 쓸 수 있는 총량(남은 공간 + 이미 센트리가 쓴 양)을 기준으로 한도를 잡는다 — 녹화가 늘수록 한도가 줄어드는 것 방지
    public long limitMb() {
        File r0 = root();
        return SentrySettings.clampLimitMb(settings.limitMb, freeMb(r0) + usedMb(), isInternalRoot(r0));
    }

    // 내장 저장소 안의 루트인가(앱 전용 폴더·앱 내부 폴더) — 한도를 안 정했을 때 내장은 3GB로 막는다
    static boolean isInternalRoot(File r) {
        if (r == null) return false;
        String p = r.getPath();
        return p.startsWith("/storage/emulated/") || p.startsWith("/sdcard/") || p.startsWith("/data/");
    }

    public long freeMb(File dir) {
        StatFs s = statFs(dir);
        return s == null ? 0 : s.getAvailableBytes() / MB;
    }

    public long totalMb(File dir) {
        StatFs s = statFs(dir);
        return s == null ? 0 : s.getTotalBytes() / MB;
    }

    public double estimateHours(int kbps) {
        return calcEstimateHours(limitMb(), kbps);
    }

    // 한도 MB × 8000(kbit) ÷ kbps ÷ 3600, 소수 1자리 반올림 (설계 예: 10000MB·1500kbps → 14.8시간)
    public static double calcEstimateHours(long limitMb, int kbps) {
        if (limitMb <= 0 || kbps <= 0) return 0.0;
        return Math.round(limitMb * 8000.0 / kbps / 3600.0 * 10.0) / 10.0;
    }

    public File eventDir(String eventId) {
        return new File(root(), eventRel(eventId));
    }

    // 이벤트 폴더 — 현재 루트에 있으면 그것, 없으면 다른 단계(폴백 때 쓴 곳)에서 찾고, 어디에도 없으면(새 이벤트) 현재 루트
    public File eventDirExisting(String eventId) {
        File cur = eventDir(eventId);
        if (cur.isDirectory()) return cur;
        return firstExisting(extraRoots(), eventRel(eventId), cur);
    }

    // 루트 아래 상대 경로 YYYY-MM/DD/<id>
    static String eventRel(String eventId) {
        if (!SentryIndex.isValidId(eventId)) throw new IllegalArgumentException("이벤트 id 형식 이상: " + eventId);
        return eventId.substring(0, 7) + File.separator + eventId.substring(8, 10) + File.separator + eventId;
    }

    // 순수 판정: roots 중 rel 폴더가 실제로 있는 첫 번째, 없으면 fallback
    public static File firstExisting(List<File> roots, String rel, File fallback) {
        for (File r : roots) {
            File f = new File(r, rel);
            if (f.isDirectory()) return f;
        }
        return fallback;
    }

    // 현재 루트 + 센트리 폴더가 실제로 있는 다른 단계(내부·공용·앱전용) — 폴백 중 쓴 영상이 남는 곳까지 목록·삭제·정리 대상. 폴더를 만들지 않는다
    public synchronized List<File> allRoots() {
        List<File> out = new ArrayList<>();
        out.add(root());
        out.addAll(extraRoots());
        return out;
    }

    public synchronized List<File> extraRoots() {
        File cur = root();
        List<File> cands = new ArrayList<>();
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) cands.add(new File(ext, "Sentry"));
        cands.add(new File(ctx.getFilesDir(), "Sentry"));
        for (Vol v : removableMounted()) {
            cands.add(publicDir(v));
            File app = appDir(v);
            if (app != null) cands.add(app);
        }
        List<File> out = new ArrayList<>();
        for (File c : cands) if (!c.equals(cur) && !out.contains(c) && c.isDirectory()) out.add(c);
        return out;
    }

    // 현재 루트가 쓴 양(한도 계산용 — 한도는 선택한 볼륨 기준)
    public long usedMb() {
        return dirSize(root()) / MB;
    }

    // 모든 단계를 합친 양(상태 표시·정리용)
    public long usedAllMb() {
        return usedAllBytes() / MB;
    }

    private long usedAllBytes() {
        long b = 0;
        for (File r : allRoots()) b += dirSize(r);
        return b;
    }

    // 한도 초과면 오래된 이벤트부터 지운다(즐겨찾기·녹화 중인 이벤트 제외). 지운 개수 반환.
    //   단계(루트)별로 따로 판단한다 — 한도는 그 루트가 있는 볼륨 기준이고 후보도 그 루트 안 이벤트뿐.
    //   합쳐서 판단하면 내장(15GB)에 잠깐 폴백한 사이 SD의 오래된 보관 영상을 내장 한도에 맞춰 지워 버린다.
    public int prune(SentryIndex idx) {
        int n = 0;
        List<SentryIndex.EventMeta> all = idx.allOldestFirst();
        for (final File r : allRoots()) {
            long usedBytes = dirSize(r);
            long limitBytes = SentrySettings.clampLimitMb(settings.limitMb, freeMb(r) + usedBytes / MB, isInternalRoot(r)) * MB;
            if (usedBytes <= limitBytes) continue;
            List<SentryIndex.EventMeta> candidates = underRoot(all, r);
            List<String> victims = selectPrune(candidates, new ToLongFunction<SentryIndex.EventMeta>() {
                @Override
                public long applyAsLong(SentryIndex.EventMeta m) {
                    return dirSize(new File(r, eventRel(m.id)));
                }
            }, usedBytes, limitBytes);
            for (String id : victims) if (idx.delete(id)) n++;
        }
        return n;
    }

    // 순수 판정: 이 루트 안에 폴더가 있고 진행 중(.part)이 아닌 이벤트만(순서 유지)
    static List<SentryIndex.EventMeta> underRoot(List<SentryIndex.EventMeta> all, File root) {
        List<SentryIndex.EventMeta> out = new ArrayList<>();
        for (SentryIndex.EventMeta m : all) {
            File d = new File(root, eventRel(m.id));
            if (d.isDirectory() && !hasPart(d)) out.add(m);
        }
        return out;
    }

    // 순수 로직: 오래된 순 목록에서 즐겨찾기를 건너뛰며 used가 limit 이하가 될 때까지 지울 id를 고른다
    public static List<String> selectPrune(List<SentryIndex.EventMeta> oldestFirst,
                                           ToLongFunction<SentryIndex.EventMeta> sizeOf,
                                           long usedBytes, long limitBytes) {
        List<String> out = new ArrayList<>();
        long used = usedBytes;
        for (SentryIndex.EventMeta m : oldestFirst) {
            if (used <= limitBytes) break;
            if (m.favorite) continue;
            used -= sizeOf.applyAsLong(m);
            out.add(m.id);
        }
        return out;
    }

    // olderThanMs = 마지막 수정 뒤 경과 시간(ms). 이보다 오래된 *.part를 마감 시도 — 시도한 개수 반환
    public int cleanOrphans(long olderThanMs) {
        return scanOrphans(root(), System.currentTimeMillis() - olderThanMs, 0);
    }

    public static boolean probeWritable(File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return false;
            File t = File.createTempFile("probe", ".tmp", dir);
            return t.delete() || !t.exists();
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private int scanOrphans(File dir, long cutoff, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File f : kids) {
            if (f.isDirectory()) {
                if (depth < 4) n += scanOrphans(f, cutoff, depth + 1);
            } else if (f.getName().endsWith(PART_SUFFIX) && f.lastModified() < cutoff) {
                SegmentWriter.finalizeOrphan(f);
                n++;
            }
        }
        return n;
    }

    private static boolean hasPart(File dir) {
        String[] names = dir.list();
        if (names == null) return false;
        for (String s : names) if (s.endsWith(PART_SUFFIX)) return true;
        return false;
    }

    // 마지막 해석의 근거(외장 선택일 때만) — 폴백 원인 진단용. 호스트 로그 "저장소 진단"
    private volatile String resolveDiag = "";

    public String resolveDiag() { return resolveDiag; }

    private Resolved resolve() {
        String sel = settings.storage;
        if (!"internal".equals(sel)) {
            StringBuilder d = new StringBuilder();
            List<Vol> rem = removableMounted();
            d.append("선택=").append(sel).append(" 외장 마운트=").append(rem.size());
            for (Vol v : rem) d.append(' ').append(v.path);
            int i = "sd".equals(sel) ? 0 : 1;
            if (rem.size() > i) {
                Vol v = rem.get(i);
                File pub = publicDir(v);
                boolean pubOk = probeWritable(pub);
                d.append(" · 공용 ").append(pub.getPath()).append(pubOk ? " 쓰기 ok" : " 쓰기 실패");
                if (pubOk) { resolveDiag = d.toString(); return new Resolved(pub, "public"); }
                File app = appDir(v);
                boolean appOk = app != null && probeWritable(app);
                d.append(" · 앱전용 ").append(app == null ? "없음" : app.getPath() + (appOk ? " 쓰기 ok" : " 쓰기 실패"));
                d.append(" · 시스템 외부 폴더=").append(extDirsForDiag());
                if (appOk) { resolveDiag = d.toString(); return new Resolved(app, "appdir"); }
            }
            d.append(" → 내부");
            resolveDiag = d.toString();
        }
        return new Resolved(internalDir(), "internal");
    }

    private String extDirsForDiag() {
        try {
            File[] dirs = ctx.getExternalFilesDirs(null);
            if (dirs == null) return "null";
            StringBuilder b = new StringBuilder("[");
            for (File f : dirs) b.append(f == null ? "null" : f.getPath()).append(';');
            return b.append(']').toString();
        } catch (Throwable t) {
            return "조회 실패 " + t.getClass().getSimpleName();
        }
    }

    private File internalDir() {
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) {
            File d = new File(ext, "Sentry");
            if (probeWritable(d)) return d;
        }
        File d = new File(ctx.getFilesDir(), "Sentry");
        d.mkdirs();
        return d;
    }

    private static File publicDir(Vol v) {
        return new File(new File(v.path, "LinkLock"), "Sentry");
    }

    // getExternalFilesDirs()에서 이 볼륨 경로 아래 항목(…/Android/data/<pkg>/files) + /Sentry
    private File appDir(Vol v) {
        File[] dirs = ctx.getExternalFilesDirs(null);
        if (dirs == null) return null;
        for (File f : dirs) {
            if (f == null) continue;
            String p = f.getPath();
            if (p.startsWith(v.path + "/") || (v.uuid != null && p.contains("/" + v.uuid + "/"))) {
                return new File(f, "Sentry");
            }
        }
        return null;
    }

    // 쓸 수 있는 곳(공용 → 앱전용 순), 둘 다 안 되면 null
    private File writableTarget(Vol v) {
        File pub = publicDir(v);
        if (probeWritable(pub)) return pub;
        File app = appDir(v);
        if (app != null && probeWritable(app)) return app;
        return null;
    }

    // 마운트된 removable 볼륨 — 첫 번째=SD, 두 번째=USB (설계 저장소 절)
    private List<Vol> removableMounted() {
        List<Vol> out = new ArrayList<>();
        StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) return out;
        for (StorageVolume sv : sm.getStorageVolumes()) {
            if (!sv.isRemovable() || !Environment.MEDIA_MOUNTED.equals(sv.getState())) continue;
            String uuid = sv.getUuid();
            String path = volumePath(sv);
            if (path == null && uuid != null) path = "/storage/" + uuid;
            if (path == null) continue;
            out.add(new Vol(uuid, path, sv.getDescription(ctx)));
        }
        return out;
    }

    // StorageVolume.getPath()는 숨김 API라 리플렉션, 실패하면 null(호출자가 /storage/<uuid>로 대체)
    private static String volumePath(StorageVolume sv) {
        try {
            Method m = StorageVolume.class.getMethod("getPath");
            Object p = m.invoke(sv);
            return p instanceof String ? (String) p : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private JSONObject internalJson() throws JSONException {
        File dir = internalDir();
        JSONObject o = new JSONObject();
        o.put("id", "internal");
        o.put("kind", "internal");
        o.put("label", "내부 저장소");
        o.put("path", dir.getPath());
        o.put("mounted", true);
        o.put("writable", probeWritable(dir));
        o.put("totalMb", totalMb(dir));
        o.put("freeMb", freeMb(dir));
        o.put("usedBySentryMb", dirSize(dir) / MB);
        return o;
    }

    private JSONObject removableJson(String kind, Vol v, boolean selected) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("kind", kind);
        if (v == null) {
            o.put("id", "");
            o.put("label", "sd".equals(kind) ? "microSD" : "USB");
            o.put("path", "");
            o.put("mounted", false);
            o.put("writable", false);
            o.put("totalMb", 0);
            o.put("freeMb", 0);
            o.put("usedBySentryMb", 0);
            return o;
        }
        // 선택하지 않은 볼륨에는 폴더를 만들지 않는다(probeWritable은 mkdirs 포함) — 이미 있는 폴더만 확인
        File target = selected ? writableTarget(v) : null;
        File pub = publicDir(v);
        File shown = target != null ? target : pub;
        File statDir = target != null ? target : new File(v.path);
        boolean writable = selected ? target != null : canWriteExisting(pub);
        o.put("id", v.uuid == null ? "" : v.uuid);
        o.put("label", v.label == null || v.label.isEmpty() ? ("sd".equals(kind) ? "microSD" : "USB") : v.label);
        o.put("path", shown.getPath());
        o.put("mounted", true);
        o.put("writable", writable);
        o.put("totalMb", totalMb(statDir));
        o.put("freeMb", freeMb(statDir));
        o.put("usedBySentryMb", target != null ? dirSize(target) / MB : (pub.isDirectory() ? dirSize(pub) / MB : 0));
        return o;
    }

    // 폴더를 만들지 않고, 존재하는 가장 가까운 상위 폴더가 쓰기 가능한지만 본다
    private static boolean canWriteExisting(File dir) {
        File d = dir;
        while (d != null && !d.exists()) d = d.getParentFile();
        return d != null && d.isDirectory() && d.canWrite();
    }

    // 선택한 화질은 수동 비트레이트 포함 실효값, 나머지는 자동값
    private int kbpsFor(String res) {
        return res.equals(settings.resolution) ? settings.effectiveBitrateKbps() : SentrySettings.autoBitrateKbps(res, settings.fps);
    }

    // 없는 폴더면 존재하는 가장 가까운 상위로 올라가 StatFs. 구할 수 없으면 null
    private static StatFs statFs(File dir) {
        File d = dir;
        while (d != null && !d.exists()) d = d.getParentFile();
        if (d == null) return null;
        try {
            return new StatFs(d.getPath());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long dirSize(File f) {
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) sum += dirSize(k);
        return sum;
    }
}
