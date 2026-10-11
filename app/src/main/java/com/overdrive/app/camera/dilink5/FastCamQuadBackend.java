package com.overdrive.app.camera.dilink5;

// ★쿼드(4분할) 백엔드(2026-09-16, 운영자 지시 "좌우 후방 360 카메라 다 구현") — 단일 TCP 연결로 4캠 전부를 멀티플렉싱
//   수신(SnifferCamBridge.WANT_ALL)해 2x2로 합성(1920x1300) 후 NV21로 변환, 기존 FrameSink에 그대로 흘려보낸다.
// ★설계 변경 이력(2026-09-16): 처음엔 camId별로 소켓 4개를 따로 열었는데(연결마다 OP_WANT_CAM 다르게), 실차에서 cam2가
//   13초 만에 원인불명 "Stream closed"로 끊기며 전체가 죽었다. 운영자 지적("오버드라이브는 4채널 동시에 잘 나와") — 오버드라이브
//   자체 쿼드뷰가 안정적으로 4채널을 동시에 보여준다면 하드웨어 한계가 아니라 4소켓 방식 자체가 문제. 프레임 프로토콜이 이미
//   프레임마다 camId를 실어보내므로(FRAME_MAGIC 뒤 writeInt(camId)) 애초에 소켓 하나로 전 채널을 멀티플렉싱하고 클라이언트가
//   camId로 역다중화하는 설계였을 가능성이 높다 — 그 방식(단일 연결)으로 전환.
// ⚠️각 칸이 실제로 어느 방향(전/후/좌/우)인지는 실차 확인 전이라 인덱스 순서(0,1,2,3 → 좌상,우상,좌하,우하)로 임시 배치했다.
//   실차로 첫 쿼드 이미지를 보고 방향이 틀리면 QUAD_ORDER 배열만 바꾸면 된다(로직 변경 불필요).
public final class FastCamQuadBackend {
    private static final int TCP_PORT = 5731;   // ★2026-09-19 BeetleLauncher(5601) 충돌 회피 — FastCamTcpBackend와 동일 고유 포트
    private static final String GREETING = "SNF2";   // ★BeetleLauncher(FCAM)와 인사말 분리
    private static final int FRAME_MAGIC = 1178815793;
    private static final int STATUS_MAGIC = 1178817329;
    private static final int OP_WANT_CAM = 1;
    private static final int WANT_ALL = -2;   // SnifferCamBridge.WANT_ALL과 동일해야 함(패키지 분리 안 하고 상수 중복 — 값 고정)
    private static final int TILE_W = 960, TILE_H = 650;
    private static final int CANVAS_W = TILE_W * 2, CANVAS_H = TILE_H * 2;
    private static final long EMIT_MIN_INTERVAL_MS = 150;   // ★합성 발행 스로틀 — camId 4개 프레임 올 때마다 매번 합성하면 과도

    private static final int[] QUAD_ORDER = { 0, 1, 2, 3 };   // 좌상,우상,좌하,우하 ← camId. 실차 확인 후 교정 가능.

    private volatile boolean running = false;
    private java.net.Socket sock;
    private Thread thread;
    private final byte[] canvasRgba = new byte[CANVAS_W * CANVAS_H * 4];
    private final byte[][] tileRgba = new byte[4][TILE_W * TILE_H * 4];
    private final boolean[] tileHas = new boolean[4];   // 최소 1회씩 다 채워져야 첫 합성(검은 칸 방지) — 단 PARTIAL_WAIT_MS 지나면 부분 합성
    // ★607 부분 합성 — 채널 하나라도 안 오는 차(QCarCam 채널 미개통/물리부재)는 4칸 대기로 영원히 fed=0(쪼제 id918·메밀소바 id495).
    //   첫 프레임 후 3초 지나면 도착한 칸만 합성하고 빈 칸은 검정으로 둔다(늦게 붙는 채널은 도착 즉시 채워짐).
    private static final long PARTIAL_WAIT_MS = 3000;
    private volatile long firstTileMs = 0;
    private boolean partialLogged = false;
    private final Object canvasLock = new Object();
    private volatile long lastEmit = 0;
    private DiLink5QCarCamBackend.Logger log = (t, m) -> {};
    private android.content.Context ctxRef;   // ★FB1100 미도착 진단(adb shell tail)용

    public synchronized boolean start(android.content.Context ctx, int fps, DiLink5QCarCamBackend.FrameSink sink, DiLink5QCarCamBackend.Logger log) {
        this.log = (log != null) ? log : this.log;
        this.ctxRef = ctx.getApplicationContext();
        running = true;
        java.util.Arrays.fill(tileHas, false);
        java.util.Arrays.fill(canvasRgba, (byte) 0);
        for (byte[] t : tileRgba) java.util.Arrays.fill(t, (byte) 0);
        lastEmit = 0; firstTileMs = 0; partialLogged = false; snapDone = false; procSnapLogged = false; snap30 = null; sessionStartMs = System.currentTimeMillis();
        retries = 0; retrying = false; owned = false;
        java.util.Arrays.fill(camFrames, 0);

        // 1) 브리지 spawn 확인 — 임의 camId(0)로 기존 단일뷰 경로를 잠깐 써서 네이티브 프로세스+브리지가 뜨는지 확인 후 즉시 닫는다.
        //    (SnifferCamBridge 서버 accept 루프는 특정 클라이언트 연결과 무관하게 계속 살아있으므로, 이 클라이언트를 끊어도 브리지는 안 죽는다.)
        FastCamTcpBackend boot = new FastCamTcpBackend(0);
        boolean spawnedOk = boot.start(ctx, fps, (nv21, w, h) -> {}, (tag, m) -> { try { this.log.log(tag, m); } catch (Throwable ignore) {} });
        try { boot.detach(true); } catch (Throwable ignore) {}   // ★653 stop()→detach() — 채널을 막 연 캡처 데몬에 종료 신호를 보내지 않음. ★655 소유권 이전(stop에서 releaseDaemon)
        owned = spawnedOk;   // ★655 검증 지적 — boot.start가 실패했으면 소유권이 없으므로 stop에서 남의 참조 수를 깎지 않는다
        this.fps = fps;
        if (!spawnedOk) { logi("브리지 spawn 실패 — 쿼드 불가"); running = false; return false; }
        logi("브리지 spawn 확인됨 — 단일연결(WANT_ALL) 시작");

        // 2) 단일 TCP 연결 — OP_WANT_CAM(WANT_ALL)로 4채널 전부 멀티플렉스 수신.
        final int fails0 = 0;
        thread = new Thread(() -> {
            int fails = fails0;
            while (running) {
                connectAndReadLoop(sink);
                if (!running) break;
                fails++;
                logi("멀티플렉스 연결 끊김 → " + Math.min(fails, 5) + "회째 재연결");
                try { Thread.sleep(Math.min(500L * fails, 3000L)); } catch (InterruptedException ie) { break; }
            }
        }, "fastcam-quad-mux");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void connectAndReadLoop(DiLink5QCarCamBackend.FrameSink sink) {
        java.net.Socket s = null;
        try {
            for (int i = 1; i <= 40 && running; i++) {
                try {
                    java.net.Socket c = new java.net.Socket();
                    c.connect(new java.net.InetSocketAddress("127.0.0.1", TCP_PORT), 500);
                    c.setTcpNoDelay(true);
                    s = c;
                    break;
                } catch (Throwable t) { try { Thread.sleep(300); } catch (InterruptedException ie) { return; } }
            }
            if (s == null || !running) { logi("TCP 연결 실패"); return; }
            synchronized (this) { sock = s; }
            java.io.DataInputStream inp = new java.io.DataInputStream(new java.io.BufferedInputStream(s.getInputStream(), 1 << 20));
            byte[] g = new byte[4];
            inp.readFully(g);
            if (!GREETING.equals(new String(g, "UTF-8"))) { logi("인사말 불일치"); return; }
            inp.readInt();   // proto version(미사용)
            java.io.DataOutputStream out = new java.io.DataOutputStream(s.getOutputStream());
            out.writeInt(OP_WANT_CAM);
            out.writeInt(WANT_ALL);
            out.flush();
            logi("WANT_ALL 요청 완료 — 4채널 멀티플렉스 프레임 대기");
            if (!procSnapLogged && ctxRef != null) {   // ★652 세션당 1회(재연결 제외) 카메라 프로세스 스냅샷
                procSnapLogged = true;
                new Thread(() -> { try { logi("쿼드 시작 카메라 프로세스=" + new FastCamTcpBackend(0).camProcSnapshot(ctxRef)); } catch (Throwable ignore) {} }, "quad-ps").start();
            }
            byte[] rgbaBuf = null;
            final java.util.Set<Integer> seenCams = java.util.concurrent.ConcurrentHashMap.newKeySet();
            // ★v544 cam2/cam3 미도착 진단 — WANT_ALL 후 6초 내 안 온 캠 로깅(012772 fed=0=cam0/cam1만 오고 cam2/cam3 미도착→4칸 못 채워 합성 0장). QCarCam 채널 미개통/물리부재 판별용.
            new Thread(() -> {
                try { Thread.sleep(6000); } catch (InterruptedException e) { return; }
                if (!running) return;
                StringBuilder miss = new StringBuilder();
                for (int c = 0; c < 4; c++) if (!seenCams.contains(c)) miss.append("cam").append(c).append(' ');
                if (miss.length() > 0) {
                    logi("★미도착 캠(6초): " + miss.toString().trim() + " — QCarCam 채널 미개통/물리부재로 쿼드 합성 0장(fed=0). 도착캠=" + seenCams);
                    // ★FB1100 점유 vs 부재 판별(2026-09-28 운영자 "비틀런처가 카메라 점유 의심") — fast_cam이 실제 몇 채널 열었나(브리지 헤더 channel_cnt)·
                    //   QCarCam open 에러(EBUSY/in-use=점유 vs not-found=물리부재)·점유자후보(ps beetle/avm)·SELinux(logcat avc)를 D1(CAMRTC)로 올린다.
                    //   브리지·fast_cam은 프레임(cam0/1) 공급 중이라 살아있어 로그도 최신. 읽기전용 tail이라 스트림 무영향.
                    try { if (ctxRef != null) new FastCamTcpBackend(0).diagnoseCamChannels(ctxRef, this.log, miss.toString().trim()); } catch (Throwable ignore) {}
                }
            }, "quad-miss-diag").start();
            while (running) {
                int magic;
                try { magic = inp.readInt(); } catch (Throwable t) { break; }
                if (magic == STATUS_MAGIC) {
                    int len = inp.readInt(); if (len < 0 || len > 4096) break;
                    byte[] sb = new byte[len]; inp.readFully(sb); FastCamTcpBackend.LOOP_RX.addAndGet(8 + len); continue;
                }
                if (magic != FRAME_MAGIC) break;
                int camId = inp.readInt();
                int w = inp.readInt();
                int h = inp.readInt();
                int len = inp.readInt();
                if (len <= 0 || len > 16 * 1024 * 1024 || len != w * h * 4) break;
                if (rgbaBuf == null || rgbaBuf.length < len) rgbaBuf = new byte[len];
                inp.readFully(rgbaBuf, 0, len);
                FastCamTcpBackend.LOOP_RX.addAndGet(20 + len);
                if (w != TILE_W || h != TILE_H) continue;   // 예상 밖 크기는 방어적으로 스킵
                int tileIdx = -1;
                for (int slot = 0; slot < 4; slot++) if (QUAD_ORDER[slot] == camId) { tileIdx = slot; break; }
                if (tileIdx < 0) continue;   // 요청 4캠 외 camId는 무시
                if (seenCams.add(camId)) logi("cam" + camId + "(칸 " + tileIdx + ") 첫 프레임 수신");
                synchronized (canvasLock) {
                    System.arraycopy(rgbaBuf, 0, tileRgba[tileIdx], 0, len);
                    tileHas[tileIdx] = true;
                    camFrames[tileIdx]++;
                    maybeComposeAndEmit(sink);
                }
            }
        } catch (Throwable t) {
            logi("루프 예외: " + t.getClass().getSimpleName() + ":" + t.getMessage());
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignore) {}
        }
    }

    // ★canvasLock 보유 상태에서 호출. 4칸이 전부 최소 1회 채워져야 합성 시작(검은 칸 방지) + 스로틀(EMIT_MIN_INTERVAL_MS).
    private void maybeComposeAndEmit(DiLink5QCarCamBackend.FrameSink sink) {
        long now = System.currentTimeMillis();
        if (firstTileMs == 0) firstTileMs = now;
        int have = 0; for (boolean has : tileHas) if (has) have++;
        if (have < 4) {
            if (now - firstTileMs < PARTIAL_WAIT_MS) return;
            if (!partialLogged) { partialLogged = true; logi("★부분 합성 시작 — " + have + "/4칸 도착(빈 칸 검정), 늦게 오는 채널은 도착 즉시 채움"); }
        }
        if (now - lastEmit < emitMinMs) return;
        lastEmit = now;
        // ★653 빠진 칸 다시 열기(운영자 09-29 승인) — 6초 진단 뒤 포기하던 것. 20초·60초에 아직 빠진 칸이 있으면 캡처 데몬을 다시 띄워 전 채널 재시도
        //   (이전 데몬 종료 확인 후 띄움). 들어온 칸은 마지막 화면을 유지하므로 잠깐 멈췄다 이어진다. 세션당 최대 2회.
        //   ★675 3회로(15·35·60초) — 운영자 "10~20초 간격으로 다시 요청"(10-01). 광주 씨라7(앞 카메라만) 같은 접두 누락이 하루 단위로 생겨 첫 시도를 앞당긴다.
        if (retryEnabled && have < 4 && retries < 3 && now - firstTileMs >= (retries == 0 ? 15000 : retries == 1 ? 35000 : 60000) && !retrying && ctxRef != null) {
            retrying = true; retries++;
            final int n = retries;
            StringBuilder miss = new StringBuilder();
            for (int s = 0; s < 4; s++) if (!tileHas[s]) miss.append("cam").append(QUAD_ORDER[s]).append(' ');
            final String missing = miss.toString().trim();
            new Thread(() -> {
                try {
                    logi("빠진 칸 다시 열기(" + n + "/3) — 누락 " + missing);
                    // ★655 채널이 빠져 있고 비틀런처 카메라 데몬이 있으면 먼저 끈다(운영자 승인 09-29 — 누락 세션 8/8건에 비틀 데몬 동거, 앱 강제종료로는 안 죽음).
                    try { logi("빠진 칸 다시 열기(" + n + "/3) — " + FastCamTcpBackend.beetleKill(ctxRef)); } catch (Throwable ig) {}
                    // 재기동이 실패하면(첫 프레임 4초 없음) 원래 나오던 칸까지 끊기므로 1회 더 띄워 최소한 이전 상태로 복구.
                    boolean ok = false;
                    for (int a = 1; a <= 2 && !ok && running; a++) {
                        FastCamTcpBackend re = new FastCamTcpBackend(0);
                        ok = re.start(ctxRef, fps, (nv21, w, h) -> {}, (tag, m) -> { try { this.log.log(tag, m); } catch (Throwable ignore) {} }, true);   // ★655 소유자 재기동(force)
                        if (ok) try { re.detach(false); } catch (Throwable ignore) {}   // 소유권은 이 쿼드 세션이 계속 가짐
                        logi("빠진 칸 다시 열기(" + n + "/3) 데몬 재기동 시도" + a + " " + (ok ? "OK — 멀티플렉스 재연결 대기" : "실패"));
                    }
                } catch (Throwable t) { logi("빠진 칸 다시 열기 예외: " + t.getClass().getSimpleName()); }
                finally { retrying = false; }
            }, "quad-retry").start();
        }
        if (snap30 == null && now - firstTileMs >= 30000) snap30 = camFrames.clone();
        int mask = slotMask;
        if (mask != 0xF) {   // ★874 보는 칸만 — 4칸 합성·전체 변환(자바 2.5M 화소) 없이 그 칸만 순환 버퍼에 바로 변환
            byte[] nv = nvPool[nvPi];
            if (nv == null) { nv = new byte[CANVAS_W * CANVAS_H * 3 / 2]; nvPool[nvPi] = nv; }
            nvPi = (nvPi + 1) % nvPool.length;
            for (int s = 0; s < 4; s++) if ((mask >> s & 1) != 0) tileToNv21(tileHas[s] ? tileRgba[s] : placeholder(), (s % 2) * TILE_W, (s / 2) * TILE_H, nv);
            if (!snapDone && now - firstTileMs >= 40000) { snapDone = true; logi("★40초 칸 검사:" + tileReport() + " · 녹색 띠 검사 생략(보는 칸만 변환 중)"); }
            try { if (sink != null) sink.onFrame(nv, CANVAS_W, CANVAS_H); } catch (Throwable ignore) {}
            return;
        }
        for (int slot = 0; slot < 4; slot++) {
            int qx = (slot % 2) * TILE_W, qy = (slot / 2) * TILE_H;
            byte[] tile = tileHas[slot] ? tileRgba[slot] : placeholder();   // ★653 빈칸 = "카메라 준비 중" 안내(검정 대신)
            for (int row = 0; row < TILE_H; row++) {
                int srcOff = row * TILE_W * 4;
                int dstOff = ((qy + row) * CANVAS_W + qx) * 4;
                System.arraycopy(tile, srcOff, canvasRgba, dstOff, TILE_W * 4);
            }
        }
        byte[] nv21 = new byte[CANVAS_W * CANVAS_H * 3 / 2];
        DiLink5QCarCamBackend.rgbaToNv21(java.nio.ByteBuffer.wrap(canvasRgba), CANVAS_W, CANVAS_H, nv21);
        // ★649 씨라7 4분할 녹색 세로줄(FB1287: 4분할에서만·30초 뒤·녹화본에도) 판별 — 40초에 1회, 인코딩 전 합성 캔버스를 차 안에서 검사해 숫자만 기록.
        //   (공유판은 카메라 영상 업로드 금지라 이미지는 안 올린다.) 색 성분이 0인 녹색 띠 = RGBA에선 R·B≈0·G 높음, NV21에선 V·U 바이트=0.
        //   RGBA에 띠 있음=원본·합성 / NV21에만=변환 / 둘 다 없음=크기 조정·인코더 단계.
        // ★652 칸별 검정/멈춤 — 30초에 칸별 프레임 수를 적어 두고 40초에 비교(증가 0=멈춤), 칸 평균 밝기<12=검정. (FB1332 일부 칸 누락·내일은맑음 좌측만 빠짐)
        if (!snapDone && now - firstTileMs >= 40000) {
            snapDone = true;
            final String tileRep = tileReport();
            final byte[] rgba = canvasRgba.clone(), yuv = nv21.clone();
            new Thread(() -> {
                try { logi("★40초 칸 검사:" + tileRep + " · 녹색 띠(인코딩 전) — RGBA:" + greenColsRgba(rgba) + " · NV21:" + zeroChromaColsNv21(yuv)); }
                catch (Throwable t) { logi("40초 녹색 띠 검사 실패: " + t.getClass().getSimpleName()); }
            }, "quad-stripe-check").start();
        }
        try { if (sink != null) sink.onFrame(nv21, CANVAS_W, CANVAS_H); } catch (Throwable ignore) {}
    }
    private boolean snapDone = false;
    // canvasLock 보유 상태에서 호출 — 칸별 도착·검정·멈춤(30초 시점 대비 10초간 장 수)
    private String tileReport() {
        StringBuilder tiles = new StringBuilder();
        for (int s = 0; s < 4; s++) {
            int luma = lumaOf(tileRgba[s]), inc = snap30 != null ? camFrames[s] - snap30[s] : -1;
            tiles.append(" cam").append(QUAD_ORDER[s]).append('=').append(!tileHas[s] ? "미도착" : luma < 12 ? "검정" : inc == 0 ? "멈춤" : "정상").append("(밝기").append(luma).append(",10초간").append(inc).append("장)");
        }
        return tiles.toString();
    }
    // ★874 칸 하나(RGBA 960x650)를 합성 화면 NV21(1920x1300)의 제자리로 바로 변환 — DiLink5QCarCamBackend.rgbaToNv21과 같은 식
    static void tileToNv21(byte[] t, int qx, int qy, byte[] out) {
        final int ySize = CANVAS_W * CANVAS_H;
        for (int j = 0; j < TILE_H; j++) {
            int p = j * TILE_W * 4, o = (qy + j) * CANVAS_W + qx;
            for (int i = 0; i < TILE_W; i++, p += 4) {
                int r = t[p] & 0xFF, g = t[p + 1] & 0xFF, b = t[p + 2] & 0xFF;
                out[o + i] = (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
            }
        }
        for (int j = 0; j < TILE_H; j += 2) {
            int o = ySize + ((qy + j) >> 1) * CANVAS_W + qx;
            for (int i = 0; i < TILE_W; i += 2) {
                int p = (j * TILE_W + i) * 4;
                int r = t[p] & 0xFF, g = t[p + 1] & 0xFF, b = t[p + 2] & 0xFF;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                out[o + i] = (byte) (v < 0 ? 0 : v > 255 ? 255 : v);
                out[o + i + 1] = (byte) (u < 0 ? 0 : u > 255 ? 255 : u);
            }
        }
    }
    private static int lumaOf(byte[] t) {
        long sum = 0; int n = 0;
        for (int o = 0; o + 2 < t.length; o += 4 * 97) { sum += (t[o] & 0xff) + (t[o + 1] & 0xff) + (t[o + 2] & 0xff); n++; }
        return n > 0 ? (int) (sum / (3L * n)) : 0;
    }
    // ★653 칸(slot)별 평균 밝기, 한 번도 안 온 칸은 -1 — 가용성 검사가 데몬 1회 기동으로 4채널을 확인할 때 쓴다.
    public int[] tileLuma() {
        synchronized (canvasLock) {
            int[] r = new int[4];
            for (int s = 0; s < 4; s++) r[s] = tileHas[s] ? lumaOf(tileRgba[s]) : -1;
            return r;
        }
    }
    private int fps = 10;
    private volatile boolean owned = false;   // ★655 이 세션이 캡처 데몬 참조 수 1을 소유(boot.start 성공 뒤 detach(true)로 이전받음)
    // ★653 다시 열기는 실시간 보기에만 — 움직임 감지 세션에서 데몬을 재기동하면 4칸이 한꺼번에 바뀌어 움직임 오감지(motion 오발) 위험.
    private volatile boolean retryEnabled = false;
    public void setRetryEnabled(boolean on) { retryEnabled = on; }
    // ★874 보조 카메라(깜빡이·후진 창)용 — 내보내는 간격과 변환할 칸(비트 0~3 = 칸 0~3, 0xF = 지금처럼 4칸 전체 합성).
    //   기본은 예전 그대로(150ms·전체)라 센트리·실시간 보기·움직임 감지는 바뀌지 않는다.
    private volatile long emitMinMs = EMIT_MIN_INTERVAL_MS;
    private volatile int slotMask = 0xF;
    private final byte[][] nvPool = new byte[3][];
    private int nvPi = 0;
    public void setEmitInterval(long ms) { emitMinMs = Math.max(20, ms); }
    public void setSlotMask(int m) { slotMask = m & 0xF; }
    private int retries = 0;
    private volatile boolean retrying = false;
    private byte[] placeholderRgba = null;
    // ★653 빈칸 안내 타일(한 번만 그림) — 아이폰·안드 모두 영상 안에서 그대로 보이게 릴레이가 직접 그린다.
    private byte[] placeholder() {
        if (placeholderRgba != null) return placeholderRgba;
        byte[] out = new byte[TILE_W * TILE_H * 4];
        try {
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(TILE_W, TILE_H, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bm);
            c.drawColor(0xFF1E1E1E);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            p.setColor(0xFFE0E0E0); p.setTextSize(46);
            c.drawText("카메라 준비 중", TILE_W / 2f, TILE_H / 2f - 10, p);
            p.setColor(0xFF9A9A9A); p.setTextSize(28);
            c.drawText("다른 앱이 카메라를 쓰는 중일 수 있어요", TILE_W / 2f, TILE_H / 2f + 40, p);
            bm.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(out));
            bm.recycle();
        } catch (Throwable t) { logi("빈칸 안내 그리기 실패: " + t.getClass().getSimpleName()); }
        placeholderRgba = out;
        return out;
    }
    private volatile boolean procSnapLogged = false;
    private final int[] camFrames = new int[4];   // ★652 칸(slot)별 받은 프레임 수
    private int[] snap30 = null;
    private long sessionStartMs = 0;
    // 열마다 행의 90% 이상이 "R·B≤8, G≥40"인 열 → 띠 구간 목록. 없으면 "없음".
    private static String greenColsRgba(byte[] rgba) {
        boolean[] hit = new boolean[CANVAS_W];
        for (int x = 0; x < CANVAS_W; x++) {
            int n = 0;
            for (int y = 0; y < CANVAS_H; y += 4) {
                int o = (y * CANVAS_W + x) * 4;
                int r = rgba[o] & 0xff, g = rgba[o + 1] & 0xff, b = rgba[o + 2] & 0xff;
                if (r <= 8 && b <= 8 && g >= 40) n++;
            }
            hit[x] = n * 10 >= (CANVAS_H / 4) * 9;
        }
        return ranges(hit, 1);
    }
    // NV21 색 평면(V,U 교차)에서 열(2픽셀 단위)마다 행의 90% 이상이 V=U=0인 곳 → 픽셀 x 구간 목록.
    private static String zeroChromaColsNv21(byte[] nv21) {
        int cw = CANVAS_W / 2, ch = CANVAS_H / 2, base = CANVAS_W * CANVAS_H;
        boolean[] hit = new boolean[cw];
        for (int cx = 0; cx < cw; cx++) {
            int n = 0;
            for (int cy = 0; cy < ch; cy += 2) {
                int o = base + cy * CANVAS_W + cx * 2;
                if (nv21[o] == 0 && nv21[o + 1] == 0) n++;
            }
            hit[cx] = n * 10 >= ((ch + 1) / 2) * 9;
        }
        return ranges(hit, 2);
    }
    private static String ranges(boolean[] hit, int scale) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hit.length; i++) {
            if (!hit[i]) continue;
            int j = i; while (j + 1 < hit.length && hit[j + 1]) j++;
            sb.append(sb.length() > 0 ? "," : "").append(i * scale).append('~').append((j + 1) * scale - 1);
            i = j;
        }
        return sb.length() == 0 ? "없음" : "x=" + sb;
    }

    public synchronized void stop() {
        running = false;
        java.net.Socket s = sock; Thread t = thread;
        sock = null; thread = null;
        try { if (s != null) s.close(); } catch (Throwable ignore) {}
        if (t != null) { try { t.join(500); } catch (Throwable ignore) {} }
        if (owned) { owned = false; try { FastCamTcpBackend.releaseDaemon(ctxRef, log); } catch (Throwable ignore) {} }   // ★655 소유한 세션만 반납(다른 세션이 쓰면 유지)
        StringBuilder fc = new StringBuilder();
        synchronized (canvasLock) { for (int k = 0; k < 4; k++) fc.append(" cam").append(QUAD_ORDER[k]).append('=').append(camFrames[k]); }
        logi("쿼드 백엔드 정지 — " + ((System.currentTimeMillis() - sessionStartMs) / 1000) + "초 동안 칸별 프레임 수" + fc);   // ★652
    }

    private void logi(String m) { try { log.log("D5CAMQUAD", m); } catch (Throwable ignore) {} }
}
