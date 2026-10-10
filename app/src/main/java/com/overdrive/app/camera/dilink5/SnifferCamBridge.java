package com.overdrive.app.camera.dilink5;

// ★fast_cam TCP 브리지 본체(BeetleLauncher 1.553 CamHelper.fastcam() 포팅, 2026-09-14) — 씨라이언7(DiLink5) 실차 검증된 방식.
//   app_process(셸 도메인, --nice-name=snifcam)로 기동되어 fast_cam_capture의 abstract unix socket(DMA-BUF SCM_RIGHTS)에
//   connect(같은 셸 도메인이라 SELinux connectto 허용) → UYVY 프레임을 읽어 RGBA 반해상으로 변환 → 127.0.0.1:5601 TCP로
//   앱(FastCamTcpBackend)에 재전송한다. 순수 Java(네이티브 불필요). 프로토콜 상수·바이트 오프셋·변환식은 beetle155
//   디컴파일 원문(FastCamClient.java·FastCamTcpClient.java·CamHelper.java fastcam$lambda$35/41·uyvyToRgbaHalf)을
//   그대로 옮김 — 사양서 요약이 아니라 원문 그대로.

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SnifferCamBridge {

    // ── fast_cam abstract unix 프로토콜 상수(FastCamClient.java 원문 그대로) ──
    private static final int MAGIC = 1178812749;      // 0x46443F0D (=FAST_CAM_MAGIC)
    private static final int TYPE_FRAME = 3;           // FRAME_READY
    private static final int TYPE_RELEASE = 4;         // ★712 FRAME_RELEASE — 소비자가 프레임 다 썼다고 알림(반납 감시 .so가 이걸 기다림)
    private static final int RELEASE_FENCE_CAP = 0x80000000;   // ★712 핸드셰이크 msg_type 상위비트 = 반납 감시 활성
    private static final int HEADER_LEN = 240;
    private static final int FRAME_MSG_LEN = 36;
    private static final int CHAN_SLOTS = 8;
    private static final int CHAN_SIZE = 28;
    private static final int FRAME_BYTES = 4992000;   // 1920x1300 UYVY(2B/px)
    private static final int STRIDE = 3840;            // 1920 * 2

    // ── TCP 프로토콜 상수(FastCamTcpClient.java 원문 그대로) ──
    private static final String GREETING = "SNF2";   // ★2026-09-19 BeetleLauncher(FCAM·5601)와 분리 — 이 브리지가 클라에 보내는 인사말(FastCamTcpBackend/FastCamQuadBackend와 일치)
    private static final int PROTO_VERSION = 2;
    private static final int FRAME_MAGIC = 1178815793;   // 0x46443F31
    private static final int STATUS_MAGIC = 1178817329;
    private static final int OP_WANT_CAM = 1;
    private static final int OP_DASHCAM = 2;
    private static final int OP_EVENT_LOCK = 3;
    static final int WANT_ALL = -2;   // ★쿼드용 멀티플렉싱(2026-09-16) — OP_WANT_CAM에 이 값을 보내면 camId 필터 없이 전 채널을 한 연결로 받는다.
                                       //   프레임마다 이미 camId가 실려오므로(FRAME_MAGIC 뒤 writeInt(camId)) 클라이언트가 그걸로 역다중화.
                                       //   4소켓 방식(2026-09-16 실차: cam2 13초 만에 원인불명 끊김) 대신 이 방식으로 전환 — 오버드라이브 자체 쿼드뷰가
                                       //   4채널 동시에 안정적이라 원작이 이 방식(단일연결 멀티플렉스)일 가능성이 높음(운영자 지적).

    private static final int HALF_W = 960;
    private static final int HALF_H = 650;

    private static volatile Constructor<?> dbbCtor;

    private SnifferCamBridge() {}

    public static void main(String[] args) {
        try {
            if (args.length < 3 || !"--fastcam".equals(args[0])) {
                System.err.println("usage: --fastcam <tcpPort> <socketName> [dashDir]");
                return;
            }
            int tcpPort = Integer.parseInt(args[1]);
            String socketName = args[2];
            // ★ABSTRACT 소켓은 앞의 @ 없이 순수 이름이어야 커널 abstract 이름과 매칭(2026-09-15 로그분석). 인자에 @가 붙어오면 LocalSocketAddress가 사실상 @@가 되어 자식(fast_cam) 바인딩과 한 글자 불일치 → 50회 재시도 실패·브리지 즉사.
            if (socketName.startsWith("@")) socketName = socketName.substring(1);

            FcSession session = connectAndMap(socketName);
            if (session == null) { log("fast_cam 연결/핸드셰이크 실패 — 종료"); return; }

            final ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", tcpPort));
            log("TCP 서버 시작 127.0.0.1:" + tcpPort);

            // ★쿼드(4분할) 지원(2026-09-16, 운영자 지시) — 원작 CamHelper는 화면 전환용 1클라이언트 가정(outRef/wantCam 전역 단일값)이라
            //   두 번째 클라이언트가 붙으면 outRef가 덮여써져 첫 클라이언트가 끊긴 것처럼 죽었다. 여러 클라이언트가 각자 다른 camId를 동시에
            //   요청할 수 있게 클라이언트별 상태(맵)로 바꾼다 — 앱이 4개 연결(camId 0~3)을 동시에 열어 합성(quad)할 수 있게.
            final Map<Socket, ClientState> clients = new ConcurrentHashMap<>();

            Thread frameThread = new Thread(() -> {
                try {
                    frameServeLoop(session, clients);
                } catch (Throwable t) {
                    log("프레임 루프 예외: " + t);
                } finally {
                    try { server.close(); } catch (Throwable ignore) {}
                    session.free();
                    log("브리지 정리 끝 — 프로세스 종료");
                    System.exit(0);
                }
            }, "snifcam-frame");
            frameThread.setDaemon(true);
            frameThread.start();

            while (true) {
                Socket client = server.accept();
                client.setTcpNoDelay(true);
                DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(client.getOutputStream()));
                dos.writeBytes(GREETING);
                dos.writeInt(PROTO_VERSION);
                dos.flush();
                ClientState st = new ClientState(dos);
                clients.put(client, st);
                log("클라 접속(" + clients.size() + "개 동시) — 프레임 전송 준비");
                final Socket myClient = client;
                Thread reqThread = new Thread(() -> clientRequestLoop(myClient, clients, st), "snifcam-req");
                reqThread.setDaemon(true);
                reqThread.start();
            }
        } catch (Throwable t) {
            log("SnifferCamBridge 예외: " + t);
        }
    }

    // ── fast_cam abstract unix socket 연결 + 240B 헤더 + SCM_RIGHTS FD + DMA-BUF mmap(FastCamClient.handshake 포팅) ──
    private static FcSession connectAndMap(String socketName) {
        android.net.LocalSocketAddress addr = new android.net.LocalSocketAddress(socketName, android.net.LocalSocketAddress.Namespace.ABSTRACT);
        android.net.LocalSocket sock = null;
        for (int i = 1; i <= 50; i++) {
            try {
                android.net.LocalSocket s = new android.net.LocalSocket();
                s.connect(addr);
                sock = s;
                log("소켓 붙음 — @" + socketName + " (시도 " + i + ")");
                break;
            } catch (Throwable t) {
                try { Thread.sleep(150); } catch (InterruptedException ie) { return null; }
            }
        }
        if (sock == null) { log("connect 실패 — @" + socketName + " 에 50회 시도해도 안 붙음"); return null; }

        try {
            InputStream input = sock.getInputStream();
            byte[] hdr = new byte[HEADER_LEN];
            FileDescriptor[] fds = null;
            int got = 0;
            while (got < HEADER_LEN) {
                int n = input.read(hdr, got, HEADER_LEN - got);
                if (n < 0) { log("헤더 도중 EOF(got=" + got + ")"); sock.close(); return null; }
                got += n;
                if (fds == null) {
                    FileDescriptor[] a = sock.getAncillaryFileDescriptors();
                    if (a != null && a.length > 0) fds = a;
                }
            }
            if (fds == null) {
                FileDescriptor[] a = sock.getAncillaryFileDescriptors();
                if (a != null && a.length > 0) fds = a;
            }

            ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
            int magic = h.getInt(0);
            int msgType = h.getInt(4);   // 핸드셰이크 응답 msg_type(HANDSHAKE_RESP=2). 반납 감시(.so)가 얹히면 상위비트(0x80000000)가 켜짐.
            int channelCnt = h.getInt(8);
            int totalBufs = h.getInt(12);
            boolean fence = (msgType & RELEASE_FENCE_CAP) != 0;   // ★712 반납 감시 활성 여부 = 핸드셰이크 비트로 자동 감지(옛 릴레이·기존 모드엔 없어 저절로 꺼짐)
            log(String.format("헤더: magic=0x%08x(기대 0x%08x) msg_type=0x%08x channel_cnt=%d total_bufs=%d 반납감시=%s", magic, MAGIC, msgType, channelCnt, totalBufs, fence ? "ON" : "off"));
            if (magic != MAGIC) { log("magic 불일치 — 소켓/프로토콜이 다르다"); sock.close(); return null; }

            Map<Integer, Integer> channelMap = new HashMap<>();
            for (int i = 0; i < CHAN_SLOTS && i < channelCnt; i++) {
                int base = i * CHAN_SIZE;
                int camId = h.getInt(base + 16);
                int nbufs = h.getInt(base + 36);
                int fdBase = h.getInt(base + 40);
                log("  채널[" + i + "] camera_id=" + camId + " nbufs=" + nbufs + " fd_base=" + fdBase);
                channelMap.put(camId, fdBase);
            }

            int fdCount = fds != null ? fds.length : 0;
            log("FD 개수: 받음=" + fdCount + " · 기대(total_bufs)=" + totalBufs);
            if (fdCount == 0) { log("★SCM_RIGHTS FD 를 0개 받았다★ — 소켓/권한 문제"); sock.close(); return null; }

            long[] addrs = new long[fdCount];
            ByteBuffer[] buffers = new ByteBuffer[fdCount];
            FileDescriptor[] rawFds = new FileDescriptor[fdCount];
            int ok = 0;
            for (int i = 0; i < fdCount; i++) {
                addrs[i] = -1;
                FileDescriptor fd = fds[i];
                rawFds[i] = fd;
                if (fd == null || !fd.valid()) { log("  mmap[" + i + "] 건너뜀 — FD 무효"); continue; }
                try {
                    long mapped = android.system.Os.mmap(0L, FRAME_BYTES, android.system.OsConstants.PROT_READ, android.system.OsConstants.MAP_SHARED, fd, 0L);
                    ByteBuffer bb = wrapDirect(mapped, FRAME_BYTES);
                    if (bb == null) {
                        try { android.system.Os.munmap(mapped, FRAME_BYTES); } catch (Throwable ignore) {}
                        continue;
                    }
                    addrs[i] = mapped;
                    buffers[i] = bb;
                    ok++;
                } catch (Throwable t) {
                    log("  ★mmap[" + i + "] 실패★ " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
            log("mmap 결과: " + ok + "/" + fdCount + " 개 성공");
            if (ok == 0) { log("★mmap 전부 실패★"); sock.close(); return null; }

            FcSession session = new FcSession();
            session.sock = sock;
            session.input = input;
            session.channelMap = channelMap;
            session.buffers = buffers;
            session.addrs = addrs;
            session.rawFds = rawFds;
            session.fence = fence;
            if (fence) session.out = sock.getOutputStream();   // ★712 반납 메시지 보낼 통로(같은 소켓)
            log("핸드셰이크 OK — 채널 " + channelCnt + "개, FD " + ok + "/" + fdCount + " mmap · 반납감시=" + (fence ? "ON" : "off"));
            return session;
        } catch (Throwable t) {
            log("connectAndMap 예외: " + t);
            try { sock.close(); } catch (Throwable ignore) {}
            return null;
        }
    }

    private static ByteBuffer wrapDirect(long address, int cap) {
        try {
            Constructor<?> ctor = dbbCtor;
            if (ctor == null) {
                ctor = Class.forName("java.nio.DirectByteBuffer").getDeclaredConstructor(long.class, int.class);
                ctor.setAccessible(true);
                dbbCtor = ctor;
            }
            Object obj = ctor.newInstance(address, cap);
            return ((ByteBuffer) obj).order(ByteOrder.LITTLE_ENDIAN);
        } catch (Throwable t) {
            log("DirectByteBuffer wrap 실패: " + t);
            return null;
        }
    }

    // ★클라이언트별 상태 — dos(응답 스트림) + wantCam(그 클라이언트가 요청한 카메라, -1=없음). 클라이언트마다 독립.
    private static final class ClientState {
        final DataOutputStream dos;
        volatile int wantCam = -1;
        ClientState(DataOutputStream dos) { this.dos = dos; }
    }

    // ── fast_cam 프레임 수신 → 이 camId를 요청한 클라이언트 전부에게 UYVY→RGBA half 변환 후 전송(다중 클라이언트, 2026-09-16) ──
    private static void frameServeLoop(FcSession session, Map<Socket, ClientState> clients) throws IOException {
        InputStream input = session.input;
        byte[] hdr = new byte[FRAME_MSG_LEN];
        byte[] rgba = new byte[HALF_W * HALF_H * 4];
        boolean firstLogged = false;
        long frames = 0;
        while (true) {
            if (!readFully(input, hdr)) { log("프레임 메시지 EOF — 생산자가 끊음"); return; }
            ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
            if (h.getInt(0) != MAGIC || h.getInt(4) != TYPE_FRAME) continue;   // FRAME_READY 아니면 반납 대상도 아님
            int camId = h.getInt(8);
            int bufIdx = h.getInt(12);
            int seq = h.getInt(16);   // ★712 반납 메시지가 이 cam/buf/seq를 그대로 돌려줘야 감시가 통과시킴
            Integer fdBase = session.channelMap.get(camId);
            int gi = (fdBase == null) ? -1 : fdBase + bufIdx;
            ByteBuffer src = (gi >= 0 && gi < session.buffers.length) ? session.buffers[gi] : null;

            // 이 camId를 원하는 클라이언트가 하나도 없으면 변환도 생략(불필요한 CPU 사용 방지).
            boolean anyWant = false;
            if (src != null) for (ClientState st : clients.values()) { if (st.wantCam == camId || st.wantCam == WANT_ALL) { anyWant = true; break; } }

            if (src != null && anyWant) {
                uyvyToRgbaHalf(src, rgba, HALF_W, HALF_H, 2, STRIDE);   // 버퍼 읽기 — 반납은 이 다음에(읽는 중 캡처가 버퍼 재사용 못 하게)
                for (Map.Entry<Socket, ClientState> e : clients.entrySet()) {
                    ClientState st = e.getValue();
                    if (st.wantCam != camId && st.wantCam != WANT_ALL) continue;
                    try {
                        synchronized (st.dos) {
                            st.dos.writeInt(FRAME_MAGIC);
                            st.dos.writeInt(camId);
                            st.dos.writeInt(HALF_W);
                            st.dos.writeInt(HALF_H);
                            st.dos.writeInt(rgba.length);
                            st.dos.write(rgba);
                            st.dos.flush();
                        }
                    } catch (Throwable t) {
                        clients.remove(e.getKey());
                        try { e.getKey().close(); } catch (Throwable ignore) {}
                    }
                }
                frames++;
                if (!firstLogged) { log("★첫 프레임 보냄★ cam=" + camId); firstLogged = true; }
                else if (frames % 150 == 0) log("프레임 " + frames + " 보냄(반해상 " + HALF_W + "x" + HALF_H + " RGBA, 클라 " + clients.size() + "개)");
            }
            // ★712 반납 — 감시가 켜졌으면 FRAME_READY마다 정확히 1번(cam/buf/seq 일치). 안 보내면 캡처가 3초 뒤 스스로 종료(종료125).
            //   버퍼를 안 쓴 프레임(원하는 클라 없음·채널 없음)도 보내야 캡처가 안 멈춤. 버퍼를 쓴 경우엔 변환 끝난 뒤라 안전.
            if (session.fence) sendRelease(session, camId, bufIdx, seq);
        }
    }

    // ── 반납 메시지(20바이트 LE: magic, type=4, cam, buf, seq) ──
    private static final byte[] relBuf = new byte[20];
    private static void sendRelease(FcSession session, int camId, int bufIdx, int seq) {
        try {
            if (session.out == null) return;
            ByteBuffer b = ByteBuffer.wrap(relBuf).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(0, MAGIC); b.putInt(4, TYPE_RELEASE); b.putInt(8, camId); b.putInt(12, bufIdx); b.putInt(16, seq);
            session.out.write(relBuf); session.out.flush();
        } catch (Throwable t) { log("반납 전송 실패(캡처가 끊겼을 수 있음): " + t); }
    }

    // ── 앱 요청(OP_WANT_CAM/OP_DASHCAM/OP_EVENT_LOCK) 처리(CamHelper.fastcam$lambda$41 포팅, 블랙박스는 스텁) ──
    private static void clientRequestLoop(Socket socket, Map<Socket, ClientState> clients, ClientState myState) {
        try {
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            while (true) {
                int op;
                try { op = dis.readInt(); } catch (Throwable t) { break; }
                if (op == OP_WANT_CAM) {
                    int camId = dis.readInt();
                    myState.wantCam = camId;
                    log("앱 요청: 카메라 " + (camId < 0 ? "없음(끔)" : String.valueOf(camId)));
                } else if (op == OP_DASHCAM) {
                    for (int i = 0; i < 8; i++) dis.readInt();   // 블랙박스 스텁 — 값만 소비, 미구현
                } else if (op == OP_EVENT_LOCK) {
                    // 블랙박스 스텁 — no-op
                } else {
                    log("★모르는 명령 opcode=" + op + " — 연결 종료★");
                    break;
                }
            }
        } catch (Throwable ignore) {
        } finally {
            clients.remove(socket);
            try { socket.close(); } catch (Throwable ignore) {}
        }
    }

    // ── UYVY(1920x1300, stride 3840) → RGBA 반해상(960x650) 변환(CamHelper.uyvyToRgbaHalf 원문 그대로, ITU-R BT.601) ──
    private static void uyvyToRgbaHalf(ByteBuffer src, byte[] out, int ow, int oh, int step, int stride) {
        int i = 0;
        for (int oy = 0; oy < oh; oy++) {
            int rowBase = oy * step * stride;
            for (int ox = 0; ox < ow; ox++) {
                int sx = ox * step;
                int base = ((sx >> 1) * 4) + rowBase;
                int y = src.get((sx & 1) == 0 ? base + 1 : base + 3) & 0xFF;
                int u = (src.get(base) & 0xFF) - 128;
                int v = (src.get(base + 2) & 0xFF) - 128;
                int c = (y - 16) * 298;
                int r = ((c + v * 409) + 128) >> 8;
                int g = (((c - (u * 100)) - (v * 208)) + 128) >> 8;
                int b = ((c + (u * 516)) + 128) >> 8;
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;
                out[i] = (byte) r;
                out[i + 1] = (byte) g;
                out[i + 2] = (byte) b;
                out[i + 3] = (byte) 0xFF;
                i += 4;
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] buf) {
        int total = 0;
        while (total < buf.length) {
            try {
                int n = in.read(buf, total, buf.length - total);
                if (n < 0) return false;
                total += n;
            } catch (Throwable t) { return false; }
        }
        return true;
    }

    private static void log(String s) { System.out.println("[SnifferCamBridge] " + s); }

    private static final class FcSession {
        android.net.LocalSocket sock;
        InputStream input;
        java.io.OutputStream out;   // ★712 반납 메시지(fence 켜졌을 때만)
        boolean fence;
        Map<Integer, Integer> channelMap;
        ByteBuffer[] buffers;
        long[] addrs;
        FileDescriptor[] rawFds;

        void free() {
            for (long a : addrs) { if (a != -1) { try { android.system.Os.munmap(a, FRAME_BYTES); } catch (Throwable ignore) {} } }
            for (FileDescriptor fd : rawFds) { if (fd != null) { try { android.system.Os.close(fd); } catch (Throwable ignore) {} } }
            try { sock.close(); } catch (Throwable ignore) {}
        }
    }
}
