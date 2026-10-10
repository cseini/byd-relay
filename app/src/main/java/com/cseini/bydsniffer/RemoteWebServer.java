package com.cseini.bydsniffer;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

// ★웹 세컨드스크린(2026-09-11 운영자 승인, docs/REMOTE-CONTROL-DESIGN.md §6 + UX 확정 메모리) — 헤드유닛이 띄우는 로컬 HTTP.
//   폰/태블릿 브라우저가 차량 핫스팟 또는 같은 WiFi로 접속(운영자: 둘 다 되게). 인증 없음(사설망=신뢰), 대신 사설 IP(RFC1918)·링크로컬만 허용.
//   GET /            → assets/remote.html (자체완결, CDN·외부폰트 없음)
//   GET /manifest    → {model, dilink, caps(remote_manifest), apps(remote_apps), split(remote_split), state(라이브 키), online, parked}
//   POST /cmd        → {"action":"RAW","params":{...}} | {"action":"LAUNCHAPP <pkg>"} | {"action":"SPLIT a b 60"} | {"action":"TRUNK open"} …
//   GET /cluster         → assets/cluster.html (웹 클러스터, docs/CLUSTER-DESIGN.md)
//   GET /cluster/events  → SSE(text/event-stream): 접속 시 감시 키 스냅샷 1회, 이후 변경분만 즉시 푸시(★2단계 2026-09-17)
//   GET /cluster/cam.mjpg → 깜빡이 방향 측면 카메라 MJPEG(multipart/x-mixed-replace). 프레임은 SnifferService.ClusterCam이 공급.
//   실행은 SnifferService.Host가 기존 execRelayAction/appLauncherCmd로 수렴(보안·주차 게이트 유지). op flavor 전용.
final class RemoteWebServer extends NanoHTTPD {
    interface Host {
        String manifestJson();                       // 전체 JSON(문자열)
        String runCommand(String action, org.json.JSONObject params);   // 결과 문자열(널이면 OK)
        // ★웹 클러스터 2단계(2026-09-17) — SSE 푸시·측면 카메라 MJPEG
        String clusterSnapshotJson();                // 상태 전체(`_`접두·200자 초과 제외) + "raw":{device:FEATURE→값} (SSE 접속 시 1회)
        void clusterClients(int n);                  // SSE 접속 수 변화 → 릴레이 폴 600ms 고정
        void clusterCamClients(int n);               // ★716 카메라 쓰는 SSE 접속 수(?nocam=1 제외 — 클러스터 페이지는 카메라를 안 띄움) → 측면·후방 카메라 예열·표시 게이트
        Frame clusterFrame(long afterSeq, long waitMs);   // afterSeq보다 새 측면 JPEG(없으면 waitMs 대기 후 null)
        String clusterCfgJson();                     // ★619 {layout, ccam, camActive} — 클러스터 레이아웃·카메라 조정 설정(릴레이 저장)
        byte[] clusterTile(int side);                // ★619 카메라 화면 조정 배경 — 해당 쪽 타일 JPEG(카메라 예열 중일 때만, 아니면 null)
        void log(String msg);                        // D1 CLUSTER 로그(지연 실측)
        void noteLan();                              // ★560 폰이 LAN 직결로 붙음 — 워커 폴링을 늦춰 데이터 절약
        String pairInfoJson();                       // ★565 QR 표시 중일 때만 {devId,k} — 아니면 {ok:false}
        String lanPair(String uid, String vin);      // ★577 LAN 상호 동기화 — 폰이 uid/vin 주입 → 릴레이가 {devId,k} 반환(창 열림+VIN유효 시). 워커·QR 불필요.
    }
    /** 측면 카메라 JPEG 1장 — seq 단조증가(클라이언트가 같은 프레임을 두 번 받지 않게). side=이 프레임의 방향(2좌·4우·8후진, ★729 폰·헤드유닛 창이 다른 방향 프레임을 거른다). */
    static final class Frame {
        final long seq; final byte[] jpeg; final int side; final long ts = System.currentTimeMillis();   // ★729 ts — 1초 넘은 프레임은 안 보낸다(상시 대기면 지난 신호의 마지막 화면이 남아 있다)
        Frame(long seq, byte[] jpeg, int side) { this.seq = seq; this.jpeg = jpeg; this.side = side; }
    }
    static final java.util.concurrent.atomic.AtomicInteger mjpegOpen = new java.util.concurrent.atomic.AtomicInteger();   // ★729 열려 있는 MJPEG 수 — 보는 사람이 있을 때만 인코딩
    static final int[] PORTS = {8765, 8766, 8767};
    private final Context ctx;
    private final Host host;
    private byte[] pageCache;

    private RemoteWebServer(Context ctx, int port, Host host) { super(port); this.ctx = ctx.getApplicationContext(); this.host = host; }

    /** 포트 후보 순서로 시작. 성공 시 인스턴스, 전부 실패면 null. */
    static RemoteWebServer startAny(Context ctx, Host host) {
        for (int p : PORTS) {
            RemoteWebServer s = new RemoteWebServer(ctx, p, host);
            try { s.start(SOCKET_READ_TIMEOUT, false); return s; } catch (Throwable ignored) {}
        }
        return null;
    }

    @Override public void stop() { super.stop(); sseExec.shutdownNow(); }   // ★SSE flush 스레드 정리 — 웹서버 재시작(핫스팟 늦게 켬)마다 데몬 스레드 누수 방지(2026-09-17)

    // ★SSE/MJPEG 스트림은 gzip 금지(2026-09-17) — NanoHTTPD가 "text/" MIME(event-stream 포함)을 자동 gzip 하면 GZIPOutputStream 버퍼에 이벤트가 갇혀
    //   지연·미전달. serve() 안에서 setGzipEncoding을 꺼도 세션이 serve 뒤에 덮어쓰므로(HTTPSession.execute) 이 훅만 유효.
    @Override protected boolean useGzipWhenAccepted(Response r) {
        String m = r.getMimeType();
        if (m != null && (m.startsWith("text/event-stream") || m.startsWith("multipart/"))) return false;
        return super.useGzipWhenAccepted(r);
    }
    // ★TCP_NODELAY(2026-09-17) — 소켓 출력이 무버퍼라 chunk마다 "hex\r\n"+data+"\r\n" 3회 write. Nagle+지연ACK면 이벤트당 40~200ms가 붙어
    //   클러스터 반응속도 요구에 직결. 접속 소켓마다 끈다.
    @Override protected ClientHandler createClientHandler(java.net.Socket finalAccept, InputStream inputStream) {
        try { finalAccept.setTcpNoDelay(true); } catch (Throwable ignored) {}
        return super.createClientHandler(finalAccept, inputStream);
    }

    /** 폰이 접속할 IPv4 후보(핫스팟/WiFi 우선, 셀룰러 제외). 첫 번째가 대표. */
    static List<String> localIps() {
        List<String> wifi = new ArrayList<>(), other = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase();
                if (n.startsWith("rmnet") || n.startsWith("rev_rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") || n.startsWith("clat")) continue;
                boolean isWifi = n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.contains("wifi") || n.startsWith("p2p");
                Enumeration<InetAddress> ad = ni.getInetAddresses();
                while (ad.hasMoreElements()) {
                    InetAddress a = ad.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    (isWifi ? wifi : other).add(a.getHostAddress());
                }
            }
        } catch (Throwable ignored) {}
        wifi.addAll(other);
        return wifi;
    }

    /** ★848 헤드유닛이 손님으로 붙은 와이파이 망의 인터페이스 이름들 */
    private static java.util.Set<String> clientWifiIfaces(Context ctx) {
        java.util.Set<String> clientIf = new java.util.HashSet<>();
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (android.net.Network nw : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(nw);
                android.net.LinkProperties lp = cm.getLinkProperties(nw);
                if (nc != null && lp != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) && lp.getInterfaceName() != null) clientIf.add(lp.getInterfaceName());
            }
        } catch (Throwable ignored) {}
        return clientIf;
    }

    /** ★848 헤드유닛이 직접 켠 핫스팟 주소 — 와이파이 계열 인터페이스 중 '손님으로 붙은 와이파이'(ConnectivityManager WIFI 망)의 인터페이스를 뺀 나머지. p2p 제외. */
    static List<String> apIps(Context ctx) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> clientIf = clientWifiIfaces(ctx);
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback() || clientIf.contains(ni.getName())) continue;
                String n = ni.getName().toLowerCase();
                if (!(n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap"))) continue;
                Enumeration<InetAddress> ad = ni.getInetAddresses();
                while (ad.hasMoreElements()) {
                    InetAddress a = ad.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** ★848 진단 로그용 — "wlan0=172.20.10.7(손님) ap0=192.168.43.1" 형태. 차종별 핫스팟 인터페이스 이름 확인용. */
    static String ifaceSummary(Context ctx) {
        java.util.Set<String> clientIf = clientWifiIfaces(ctx);
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> ad = ni.getInetAddresses();
                while (ad.hasMoreElements()) {
                    InetAddress a = ad.nextElement();
                    if (!(a instanceof Inet4Address)) continue;
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(ni.getName()).append('=').append(a.getHostAddress()).append(clientIf.contains(ni.getName()) ? "(손님)" : "");
                }
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private static boolean isPrivate(String ip) {
        if (ip == null) return false;
        ip = ip.trim().toLowerCase();
        if (ip.startsWith("::ffff:")) ip = ip.substring(7);                       // IPv4-mapped IPv6(듀얼스택 바인딩 시 흔함)
        if (ip.equals("::1") || ip.startsWith("fe80:") || ip.startsWith("fc") || ip.startsWith("fd")) return true;   // 루프백·링크로컬·ULA
        return ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.") || ip.equals("127.0.0.1")
                || (ip.startsWith("172.") && ip.length() > 6 && inRange(ip.substring(4, ip.indexOf('.', 4)), 16, 31));
    }
    private static boolean inRange(String s, int lo, int hi) { try { int v = Integer.parseInt(s); return v >= lo && v <= hi; } catch (Throwable t) { return false; } }

    @Override
    public Response serve(IHTTPSession session) {
        // ★560 LAN 요청 수신 = 폰이 같은 망에 있다는 증거. 명령이 이 경로로 직접 오므로 워커 폴링을 늦춘다.
        //   ★871 같은 헤드유닛 안(링크락 런처·센트리 보기)의 루프백 요청은 폰이 아니다 — 세면 집 밖 원격 명령 폴링이 늦어진다(센트리 세션 요청)
        String remote = session.getRemoteIpAddress();
        boolean loopback = remote != null && (remote.startsWith("127.") || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote));
        if (!loopback) try { host.noteLan(); } catch (Throwable ignored) {}
        if (!isPrivate(remote)) return text(Response.Status.FORBIDDEN, "private network only");
        String uri = session.getUri();
        if (com.cseini.bydsniffer.sentry.SentryHttp.handles(uri)) return com.cseini.bydsniffer.sentry.SentryHttp.serve(session);   // ★센트리 /sentry/* (GET·HEAD·POST 전부 — docs/SENTRY-DESIGN.md 훅 v2)
        try {
            if (Method.GET.equals(session.getMethod())) {
                if ("/".equals(uri) || "/index.html".equals(uri)) return page();
                if ("/manifest".equals(uri)) return json(host.manifestJson());
                if ("/ping".equals(uri)) return text(Response.Status.OK, "ok");
                // ★565 원터치 자동연결 — 차 화면에 QR이 떠 있는 60초 동안만 devId+페어키를 내준다.
                //   폰이 같은 망(핫스팟·공유기)에서 이 값을 받아 등록까지 자동으로 끝낸다. QR 촬영도 수동 입력도 불필요.
                //   게이트 2중: ①사설망만(위 isPrivate) ②QR 표시 후 60초. 차 안에서 QR을 띄운 사람만 열 수 있다.
                if ("/pair".equals(uri)) return json(host.pairInfoJson());
                if (uri.startsWith("/icon/")) return icon(uri.substring(6));
                if ("/manifest.webmanifest".equals(uri)) return webManifest();
                if ("/appicon.png".equals(uri)) return appIcon();
                if ("/jmuxer.min.js".equals(uri)) return jmuxer();   // ★MSE 폴백 디코더(Android 크롬은 http에서 WebCodecs 없음) — 핫스팟 폰은 인터넷이 없을 수 있어 릴레이가 직접 서빙
                if ("/cluster".equals(uri)) return cluster();        // ★웹 클러스터(계기판) — docs/CLUSTER-DESIGN.md. 2단계: 상태는 /cluster/events SSE, 측면 카메라는 /cluster/cam.mjpg
                if ("/cluster/events".equals(uri)) return sseEvents(!"1".equals(session.getParms().get("nocam")));   // ★716 nocam=1=클러스터 페이지(카메라 안 씀), 그 외=폰 보조 카메라
                if ("/cluster/state".equals(uri)) return clusterState();
                if ("/cluster/cam.mjpg".equals(uri)) return camMjpeg("1".equals(session.getParms().get("keep")));   // ★729 keep=1 = 폰이 미리 열어 두는 연결
                if ("/cluster/cfg".equals(uri)) { Response r = json(host.clusterCfgJson()); r.addHeader("Cache-Control", "no-store"); return r; }   // ★619
                if ("/cluster/tile.jpg".equals(uri)) {   // ★619 ?side=2|4|8 · ★871 1=전방(링크락 런처 카메라 띠, 운영자 10-11)
                    int sd = 0; try { sd = Integer.parseInt(session.getParms().get("side")); } catch (Throwable ignored) {}
                    byte[] jpg = (sd == 1 || sd == 2 || sd == 4 || sd == 8) ? host.clusterTile(sd) : null;
                    if (jpg == null) return text(Response.Status.SERVICE_UNAVAILABLE, "카메라 준비 안 됨");
                    Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", new java.io.ByteArrayInputStream(jpg), jpg.length);
                    r.addHeader("Cache-Control", "no-store"); return r;
                }
                return text(Response.Status.NOT_FOUND, "not found");
            }
            // ★577 LAN 상호 동기화 — 폰이 uid/vin을 직접 넣고 devId+K를 받아 한 번에 등록 완료(QR·워커 불필요).
            //   게이트: 사설망(위) + 페어링 창 열림(헤드유닛 '폰과 동기화' 버튼)에서만 수락. 릴레이 lanPair가 창·VIN 검증.
            if (Method.POST.equals(session.getMethod()) && "/lan-pair".equals(uri)) {
                // ★592 근본원인 수정 — NanoHTTPD ContentType.getEncoding()은 Content-Type에 charset이 없으면
                //   "US-ASCII"로 디폴트(라이브러리 자체 동작, 2.3.1 확인). iOS RelayClient는 매 POST마다
                //   "application/json"만 보내고 charset을 안 붙여서, 한글 등 비ASCII 바이트가 전부 U+FFFD(깨짐)로
                //   치환된 채 parseBody()에서 나온다 — 트랙패드 한글 입력이 "◇?◇?"로 보인 진짜 원인(클립보드+붙여넣기로
                //   바꾼 592 수정 이전부터 있던, 완전히 별개의 버그). 클라이언트가 뭘 보내든 이 두 라우트는 JSON 전용이므로
                //   parseBody 호출 직전에 헤더를 강제로 utf-8로 덮어써 라이브러리 기본값을 우회한다.
                session.getHeaders().put("content-type", "application/json; charset=utf-8");
                Map<String, String> lf = new HashMap<>();
                try { session.parseBody(lf); } catch (Throwable ignored) {}
                String lb = lf.get("postData");
                if (lb == null || lb.isEmpty()) return json("{\"ok\":false,\"error\":\"body\"}");
                try {
                    org.json.JSONObject lo = new org.json.JSONObject(lb);
                    String r = host.lanPair(lo.optString("uid", "").trim(), lo.optString("vin", "").trim());
                    return json(r);
                } catch (Throwable t) { return json("{\"ok\":false,\"error\":\"json\"}"); }
            }
            if (Method.POST.equals(session.getMethod()) && "/cmd".equals(uri)) {
                session.getHeaders().put("content-type", "application/json; charset=utf-8");   // ★592, 위 /lan-pair 주석 참고
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String body = files.get("postData");
                if (body == null || body.isEmpty()) return text(Response.Status.BAD_REQUEST, "body");
                // ★558 진단 — 폰이 보낸 명령 JSON이 **정확히 512번째 글자에서 끊겨** 파싱 실패하는 사례가 있다(24h 5건 실측,
                //   `Unterminated string at character 512 of {"action":"RAW",…`). 폰이 잘라 보낸 것인지 이쪽이 덜 읽은 것인지
                //   구분이 안 돼 추측이 불가능하다 → 받은 길이와 Content-Length를 대조해 남긴다. 읽기만 하므로 동작 변화 없음.
                org.json.JSONObject o;
                try {
                    o = new org.json.JSONObject(body);
                } catch (org.json.JSONException je) {
                    host.log("CMD 본문 파싱 실패 — 받은 " + body.length() + "자 / Content-Length="
                            + session.getHeaders().get("content-length") + " / " + je.getMessage());
                    return text(Response.Status.BAD_REQUEST, "json");
                }
                String action = o.optString("action", "").trim();
                if (action.isEmpty()) return text(Response.Status.BAD_REQUEST, "action");
                org.json.JSONObject params = o.optJSONObject("params");
                String r = host.runCommand(action, params);
                // ★"웹에서 허용되지 않는 명령"은 403 — 앱 LAN 직결이 이 응답만 보고 워커로 폴백한다(200은 실행된 것으로 간주, 이중 실행 방지).
                if (r != null && r.startsWith("웹에서 허용되지 않")) {
                    Response nr = json(new org.json.JSONObject().put("ok", false).put("result", r).toString());
                    nr.setStatus(Response.Status.FORBIDDEN); return nr;
                }
                return json(new org.json.JSONObject().put("ok", r == null || !r.contains("실패") && !r.contains("거부") && !r.contains("불가")).put("result", r == null ? "" : r).toString());
            }
            return text(Response.Status.METHOD_NOT_ALLOWED, "method");
        } catch (Throwable t) {
            return text(Response.Status.INTERNAL_ERROR, "err " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    // ═══ ★웹 클러스터 2단계 SSE(2026-09-17) ═══════════════════════════════════════════════════════════════════
    //   1단계의 /manifest 2초 폴링(+15초마다 RAW fastPoll POST 해킹) → 릴레이 상태가 바뀌는 즉시 푸시.
    //   상태 변경: RelayClient.StateListener → pushState(폴/이벤트 스레드, 큐잉만), 원시 차량이벤트: SnifferService.onVehicleEvent → pushRaw.
    //   둘을 50ms 디바운스로 묶어 event:state 1건 — data:{상태키…, "raw":{"device:FEATURE":값…}} (turnflash/hazard는 즉시 flush)
    //   → 접속 클라이언트별 큐 → chunked 응답. 10초 무변화면 event:ping(클라이언트 연결표시 + 죽은 소켓 검출: write 실패 → NanoHTTPD가 Response.close → 본문 close()).
    private static final byte[] SSE_EOF = new byte[0];
    private static final byte[] SSE_PING = "event: ping\ndata: {}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final java.util.Set<SseClient> sseClients = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ScheduledExecutorService sseExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "cluster-sse"); t.setDaemon(true); return t; });
    private final java.util.LinkedHashMap<String, Object> pending = new java.util.LinkedHashMap<>();      // 상태 키 변경분(최상위)
    private final java.util.LinkedHashMap<String, Object> pendingRaw = new java.util.LinkedHashMap<>();   // 원시 이벤트 변경분("raw" 객체)
    private long pendingSinceMs = 0;
    private java.util.concurrent.ScheduledFuture<?> flushTask;

    /** RelayClient.StateListener → 여기(SnifferService가 배선). 호출 스레드를 절대 블로킹하지 않음 — 큐잉 후 즉시 반환. 접속자 없으면 아무것도 안 함. */
    public void pushState(String key, Object value) { push(key, value, false); }
    /** 원시 차량 이벤트(device:FEATURE → 값) — SnifferService.clusterRaw가 값이 바뀐 키만 넘긴다. 같은 디바운스에 "raw" 그룹으로 묶임. */
    public void pushRaw(String key, Object value) { push(key, value, true); }
    private void push(String key, Object value, boolean raw) {
        if (sseClients.isEmpty()) return;
        boolean immediate = !raw && ("turnflash".equals(key) || "hazard".equals(key));   // 깜빡이는 디바운스 없이 즉시(반응속도)
        synchronized (pending) {
            (raw ? pendingRaw : pending).put(key, value);
            if (pendingSinceMs == 0) pendingSinceMs = System.currentTimeMillis();
            if (immediate) sseExec.execute(this::flushPending);
            else if (flushTask == null) flushTask = sseExec.schedule(this::flushPending, 50, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }
    private void flushPending() {
        String json; long since;
        synchronized (pending) {
            flushTask = null;
            if (pending.isEmpty() && pendingRaw.isEmpty()) return;
            org.json.JSONObject o = new org.json.JSONObject(pending);
            if (!pendingRaw.isEmpty()) { try { o.put("raw", new org.json.JSONObject(pendingRaw)); } catch (Exception ignored) {} }
            json = o.toString(); since = pendingSinceMs; pendingSinceMs = 0; pending.clear(); pendingRaw.clear();
        }
        if (json == null) return;   // Android JSONObject.toString()은 직렬화 실패(NaN 등) 시 null
        byte[] msg = ("event: state\ndata: " + json + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = 0;
        for (SseClient c : sseClients) { if (c.offer(msg)) n++; else c.end(); }
        if (json.contains("\"turnflash\"")) host.log("SSE 푸시 " + json + " → " + n + "클라 (+" + (System.currentTimeMillis() - since) + "ms)");
    }
    private int sseCamCount() { int m = 0; for (SseClient c : sseClients) if (c.cam) m++; return m; }
    private Response sseEvents(boolean cam) {
        SseClient c = new SseClient(cam);
        String snap = host.clusterSnapshotJson();
        c.offer(("retry: 3000\nevent: state\ndata: " + (snap == null ? "{}" : snap) + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        sseClients.add(c);
        int n = sseClients.size(), m = sseCamCount(); host.clusterClients(n); host.clusterCamClients(m); host.log("SSE 접속 → " + n + "클라(카메라 " + m + ")" + (cam ? "" : " · 클러스터"));
        Response r = newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", c);
        r.addHeader("Cache-Control", "no-store"); r.addHeader("X-Accel-Buffering", "no"); r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }
    /** ★551 폴링 폴백 — 스냅샷 1회(JSON). 페이지가 SSE로 데이터를 못 받으면 이걸 주기 호출해 계기판이 멈추지 않게 한다. */
    private Response clusterState() {
        String snap = host.clusterSnapshotJson();
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", snap == null ? "{}" : snap);
        r.addHeader("Cache-Control", "no-store"); r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }
    /** SSE 본문 — NanoHTTPD 응답 스레드가 read()로 당겨 chunk 전송. 큐가 차면(느린/죽은 클라) end()로 종료. */
    private final class SseClient extends InputStream {
        final boolean cam;   // ★716 카메라 쓰는 접속(폰 보조 카메라)인가
        SseClient(boolean cam) { this.cam = cam; }
        private final java.util.concurrent.BlockingQueue<byte[]> q = new java.util.concurrent.LinkedBlockingQueue<>(128);
        private byte[] cur; private int pos; private volatile boolean closed;
        private volatile long sent; private final long openedMs = System.currentTimeMillis();
        boolean offer(byte[] b) { return !closed && q.offer(b); }
        void end() { closed = true; q.offer(SSE_EOF); }
        @Override public int read() throws IOException { byte[] b = new byte[1]; int n = read(b, 0, 1); return n < 0 ? -1 : (b[0] & 0xff); }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            while (cur == null || pos >= cur.length) {
                if (closed) return -1;
                byte[] next;
                try { next = q.poll(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { return -1; }
                if (next == SSE_EOF) return -1;
                cur = next == null ? SSE_PING : next; pos = 0;   // 10초 무변화 → ping
            }
            int n = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, n); pos += n; sent += n;
            return n;
        }
        // ★551 진단 — 종료 시 실제로 내보낸 바이트를 남긴다. 0B면 NanoHTTPD가 본문을 아예 안 읽은 것(응답 경로 문제),
        //   N>0이면 클라이언트/네트워크가 끊은 것. 09-21 "접속↔종료 같은 초, 본문 0바이트" 증상을 이 한 줄로 가른다.
        @Override public void close() {   // NanoHTTPD가 응답 종료(정상·write 예외) 시 호출 — remove 결과로 멱등
            closed = true;
            if (sseClients.remove(this)) { int n = sseClients.size(), m = sseCamCount(); host.clusterClients(n); host.clusterCamClients(m); host.log("SSE 접속 종료 → " + n + "클라(카메라 " + m + ") (보낸 " + sent + "B, " + (System.currentTimeMillis() - openedMs) + "ms)"); }
        }
    }

    // ═══ ★측면 카메라 MJPEG(2026-09-17) — multipart/x-mixed-replace ═══════════════════════════════════════════
    //   프레임은 SnifferService.ClusterCam의 최신 JPEG(seq)만 전송 — 같은 프레임 재전송 없음, 느린 클라이언트는 중간 프레임을 건너뜀(항상 최신).
    //   카메라가 안 켜진 동안은 대기(빈 파트 없음). 30초간 프레임이 없으면 스트림 종료(응답 스레드 누수 방지) → 페이지가 깜빡이 활성이면 1초 후 재요청.
    //   ★729 keep=1(폰이 신호 전에 미리 연 연결) — 안 닫고 3초마다 빈 파트(X-Keepalive)를 보내 연결을 살려 둔다(끊긴 폰은 쓰기 실패로 정리).
    //   프레임마다 X-Side(2좌·4우·8후진)·X-Seq를 붙여 폰이 다른 방향·지난 프레임을 거른다.
    private Response camMjpeg(boolean keep) {
        Response r = newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=frame", new MjpegBody(keep));
        r.addHeader("Cache-Control", "no-store"); r.addHeader("Pragma", "no-cache");
        return r;
    }
    private final class MjpegBody extends InputStream {
        private final boolean keep;
        private long seq = 0, lastFrameMs = System.currentTimeMillis(), lastSentMs = lastFrameMs; private byte[] cur; private int pos; private int frames; private volatile boolean closed;
        MjpegBody(boolean keep) { this.keep = keep; mjpegOpen.incrementAndGet(); }
        @Override public int read() throws IOException { byte[] b = new byte[1]; int n = read(b, 0, 1); return n < 0 ? -1 : (b[0] & 0xff); }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            while (cur == null || pos >= cur.length) {
                if (closed) return -1;
                Frame f = host.clusterFrame(seq, 1000);
                if (f == null) {
                    long now = System.currentTimeMillis();
                    if (!keep) { if (now - lastFrameMs > 30000) return -1; continue; }
                    if (now - lastSentMs < 3000) continue;
                    lastSentMs = now;
                    cur = "--frame\r\nContent-Type: text/plain\r\nX-Keepalive: 1\r\nContent-Length: 0\r\n\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII); pos = 0;
                    continue;
                }
                seq = f.seq;
                if (System.currentTimeMillis() - f.ts > 1000) continue;   // ★729 지난 신호 때 화면 — 새로 붙은 연결에 멈춘 옛 화면을 먼저 보여 주지 않게
                lastFrameMs = lastSentMs = System.currentTimeMillis(); frames++;
                byte[] hdr = ("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.jpeg.length + "\r\nX-Side: " + f.side + "\r\nX-Seq: " + f.seq + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                cur = new byte[hdr.length + f.jpeg.length + 2];
                System.arraycopy(hdr, 0, cur, 0, hdr.length); System.arraycopy(f.jpeg, 0, cur, hdr.length, f.jpeg.length);
                cur[cur.length - 2] = '\r'; cur[cur.length - 1] = '\n';
                pos = 0;
            }
            int n = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, n); pos += n;
            return n;
        }
        @Override public void close() { if (!closed) { closed = true; mjpegOpen.decrementAndGet(); host.log("MJPEG 종료 " + frames + "프레임" + (keep ? " (미리 연결)" : "")); } }
    }

    private byte[] jmuxerCache;
    private Response jmuxer() {
        try {
            if (jmuxerCache == null) {
                InputStream in = ctx.getAssets().open("jmuxer.min.js");
                ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close(); jmuxerCache = bo.toByteArray();
            }
            Response r = newFixedLengthResponse(Response.Status.OK, "application/javascript; charset=utf-8", new java.io.ByteArrayInputStream(jmuxerCache), jmuxerCache.length);
            r.addHeader("Cache-Control", "max-age=86400");
            return r;
        } catch (Throwable t) { return text(Response.Status.INTERNAL_ERROR, "jmuxer " + t.getMessage()); }
    }
    private Response page() {
        try {
            if (pageCache == null) {
                InputStream in = ctx.getAssets().open("remote.html");
                ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close(); pageCache = bo.toByteArray();
            }
            Response r = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", new java.io.ByteArrayInputStream(pageCache), pageCache.length);
            r.addHeader("Cache-Control", "no-store");
            return r;
        } catch (Throwable t) { return text(Response.Status.INTERNAL_ERROR, "page " + t.getMessage()); }
    }
    // ★웹 클러스터(계기판) 페이지 — 깜빡이 방향 사이드카메라 표시(2단계, 2026-09-17). 상태는 SSE, 카메라는 MJPEG.
    private byte[] clusterCache;
    private Response cluster() {
        try {
            if (clusterCache == null) {
                InputStream in = ctx.getAssets().open("cluster.html");
                ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close(); clusterCache = bo.toByteArray();
            }
            Response r = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", new java.io.ByteArrayInputStream(clusterCache), clusterCache.length);
            r.addHeader("Cache-Control", "no-store");
            return r;
        } catch (Throwable t) { return text(Response.Status.INTERNAL_ERROR, "cluster " + t.getMessage()); }
    }
    // 앱런처 실제 아이콘(SplitLauncher.iconFile이 filesDir/appicons에 렌더해 둔 PNG). 이름은 [A-Za-z0-9._]만.
    private Response icon(String name) {
        if (!name.matches("appicon_[A-Za-z0-9._]{1,160}\\.png")) return text(Response.Status.BAD_REQUEST, "name");
        java.io.File f = new java.io.File(SplitLauncher.iconDir(ctx), name);
        if (!f.isFile()) return text(Response.Status.NOT_FOUND, "no icon");
        try {
            Response r = newFixedLengthResponse(Response.Status.OK, "image/png", new java.io.FileInputStream(f), f.length());
            r.addHeader("Cache-Control", "public, max-age=86400");
            return r;
        } catch (Throwable t) { return text(Response.Status.INTERNAL_ERROR, "icon " + t.getMessage()); }
    }
    // ★전체화면(2026-09-11 운영자 "주소창 줄이 두꺼워") — 아이폰 사파리는 페이지 Fullscreen API가 없어 '홈 화면에 추가'(standalone)가 유일한 길.
    //   PWA 매니페스트(display standalone) + 릴레이 자기 앱 아이콘(apple-touch-icon·manifest icons)으로 홈 화면 아이콘까지 링크락 로고.
    private Response webManifest() {
        String m = "{\"name\":\"링크락 리모컨\",\"short_name\":\"리모컨\",\"start_url\":\"/\",\"scope\":\"/\",\"display\":\"standalone\",\"orientation\":\"any\","
                + "\"background_color\":\"#0b0f14\",\"theme_color\":\"#0b0f14\",\"icons\":[{\"src\":\"/appicon.png\",\"sizes\":\"192x192\",\"type\":\"image/png\"}]}";
        Response r = newFixedLengthResponse(Response.Status.OK, "application/manifest+json; charset=utf-8", m);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }
    private byte[] appIconCache;
    private Response appIcon() {
        try {
            if (appIconCache == null) {
                android.graphics.drawable.Drawable d = ctx.getPackageManager().getApplicationIcon(ctx.getPackageName());
                int s = 192;
                android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(bm);
                c.drawColor(0xFF0B0F14);   // apple-touch-icon은 투명을 검정으로 채우므로 앱 배경색으로 미리 깔아 둔다
                d.setBounds(0, 0, s, s); d.draw(c);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bo);
                bm.recycle(); appIconCache = bo.toByteArray();
            }
            Response r = newFixedLengthResponse(Response.Status.OK, "image/png", new java.io.ByteArrayInputStream(appIconCache), appIconCache.length);
            r.addHeader("Cache-Control", "public, max-age=86400");
            return r;
        } catch (Throwable t) { return text(Response.Status.INTERNAL_ERROR, "appicon " + t.getMessage()); }
    }
    private static Response json(String s) {
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", s == null ? "{}" : s);
        r.addHeader("Cache-Control", "no-store"); r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }
    private static Response text(Response.Status st, String s) { return newFixedLengthResponse(st, "text/plain; charset=utf-8", s); }
}
