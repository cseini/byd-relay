package com.cseini.bydsniffer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import java.util.ArrayList;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;
import java.util.LinkedHashMap;
import java.util.Map;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

// ★CARBLESCAN 프로브 — 헤드유닛 BT가 "같은 차" 워치키 BLE 모듈에 연결되는지 실차 확인(읽기전용).
//   스캔은 targetSdk25+위치권한無라 결과가 빌 수 있으므로, 폰이 넘긴 MAC으로 '직접 연결'(안드로이드 가능, 데모 방식).
//   확인 항목: (1) connectGatt 연결 성공? (2) 서비스탐색에 워치키 서비스(42594420…) 있나? (3) RSSI.
//   이게 되면 → 후진 비상등/접근·이탈 문제어를 릴레이 로컬 BLE로 이관 가능.
public class CarBleProbe {
    private static final String TAG = "CarBleProbe";
    private static final String WATCH_SVC = "42594420-4155-544f-e0a9-e50e24dcca9e";

    public interface Cb { void done(boolean ok, String detail); }

    // 동시 1건만
    private static volatile boolean busy = false;

    // ★스캔 모드 — 워치키 서비스(42594420) 광고하는 기기 탐색. 안드로이드 스캔은 진짜 MAC 반환(iOS와 반대).
    //   결과 비면 = 위치권한無(SDK25) 또는 모듈 미광고. 채워지면 = 실제 MAC+RSSI+헤드유닛 가시성 확답.
    public static void scan(Context ctx, Cb cb) {
        if (busy) { cb.done(false, "프로브 진행중"); return; }
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { cb.done(false, "BluetoothAdapter 없음"); return; }
        if (!adapter.isEnabled()) { cb.done(false, "★BT 라디오 OFF — enabled=false"); return; }
        final BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null) { cb.done(false, "BluetoothLeScanner 없음(BT off?)"); return; }
        busy = true;
        final Handler main = new Handler(Looper.getMainLooper());
        final Map<String, String> found = new LinkedHashMap<>();
        final int[] total = {0};
        final boolean[] fin = {false};
        final ScanCallback scb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                try {
                    total[0]++;
                    BluetoothDevice d = r.getDevice(); int rssi = r.getRssi();
                    String mac = d.getAddress(); String nm = d.getName();
                    boolean hasWatch = false; StringBuilder su = new StringBuilder();
                    if (r.getScanRecord() != null && r.getScanRecord().getServiceUuids() != null) {
                        for (ParcelUuid pu : r.getScanRecord().getServiceUuids()) {
                            su.append(pu.getUuid().toString().substring(0, 8)).append(" ");
                            if (pu.getUuid().toString().equalsIgnoreCase(WATCH_SVC)) hasWatch = true;
                        }
                    }
                    // 워치키 서비스거나 이름이 BYD계열이면만 기록(스팸 방지)
                    boolean bydName = nm != null && (nm.toUpperCase().contains("BYD") || nm.toUpperCase().contains("DOLPHIN") || nm.toUpperCase().contains("SEOS"));
                    if (hasWatch || bydName) found.put(mac, "mac=" + mac + " name=" + nm + " rssi=" + rssi + (hasWatch ? " ★워치키서비스" : "") + " svc=[" + su.toString().trim() + "]");
                } catch (Throwable ignore) {}
            }
            @Override public void onScanFailed(int code) { synchronized (fin) { if (fin[0]) return; fin[0] = true; } main.post(() -> { busy = false; cb.done(false, "스캔 실패 code=" + code + "(2=이미시작 등)"); }); }
        };
        try { sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scb); }
        catch (Throwable t) { busy = false; cb.done(false, "startScan 예외: " + t.getMessage()); return; }
        main.postDelayed(() -> {
            synchronized (fin) { if (fin[0]) return; fin[0] = true; }
            try { sc.stopScan(scb); } catch (Throwable ignore) {}
            busy = false;
            cb.done(!found.isEmpty(), "스캔완료 총탐지" + total[0] + "개·매칭" + found.size() + "개: " + found.values() + (total[0]==0?" (총0=위치권한無/서비스OFF 의심)":""));
        }, 9000);
    }

    public static void run(Context ctx, String mac, Cb cb) {
        if (busy) { cb.done(false, "프로브 진행중 — 중복"); return; }
        if (mac == null || mac.trim().isEmpty()) { cb.done(false, "mac 파라미터 없음(폰 프로비저닝의 byd_ble_mac 전달 필요)"); return; }
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { cb.done(false, "BluetoothAdapter 없음"); return; }
        if (!adapter.isEnabled()) { cb.done(false, "★BT 라디오 OFF(차 OFF 절전?) — enabled=false"); return; }

        final String m = mac.trim().toUpperCase();
        final BluetoothDevice dev;
        try { dev = adapter.getRemoteDevice(m); }
        catch (Exception e) { cb.done(false, "getRemoteDevice 실패: " + e.getMessage()); return; }

        busy = true;
        final StringBuilder log = new StringBuilder("BT ON, mac=" + m + "; ");
        final Handler main = new Handler(Looper.getMainLooper());
        final boolean[] finished = {false};
        final BluetoothGatt[] gattHolder = new BluetoothGatt[1];

        final Runnable finish = new Runnable() {
            @Override public void run() {
                synchronized (finished) {
                    if (finished[0]) return;
                    finished[0] = true;
                }
                main.post(() -> {
                    try { if (gattHolder[0] != null) { gattHolder[0].disconnect(); gattHolder[0].close(); } } catch (Exception ignore) {}
                    busy = false;
                    boolean ok = log.indexOf("서비스탐색OK") >= 0;
                    cb.done(ok, log.toString());
                });
            }
        };

        final ArrayList<BluetoothGattCharacteristic> readQ = new ArrayList<>();
        final int[] readIdx = {0};
        BluetoothGattCallback gcb = new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                log.append("연결상태 status=").append(status).append(" newState=").append(newState).append("; ");
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log.append("★연결됨! 서비스탐색 시작; ");
                    try { g.readRemoteRssi(); } catch (Exception ignore) {}
                    boolean disc = g.discoverServices();
                    log.append("discoverServices()=").append(disc).append("; ");
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log.append("연결끊김; ");
                    finish.run();
                }
            }
            @Override public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
                log.append("RSSI=").append(rssi).append("; ");
            }
            @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                log.append("서비스탐색OK status=").append(status).append("; ");
                boolean hasWatch = false;
                List<BluetoothGattService> svcs = g.getServices();
                for (BluetoothGattService s : svcs) {
                    String u = s.getUuid().toString();
                    boolean isWatch = u.equalsIgnoreCase(WATCH_SVC);
                    if (isWatch) hasWatch = true;
                    log.append(isWatch ? "★svc42594420{" : ("svc" + u.substring(0, 8) + "{"));
                    for (BluetoothGattCharacteristic ch : s.getCharacteristics()) {
                        int pr = ch.getProperties();
                        String flags = ((pr & BluetoothGattCharacteristic.PROPERTY_READ) != 0 ? "R" : "")
                                + ((pr & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ? "W" : "")
                                + ((pr & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ? "w" : "")
                                + ((pr & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ? "N" : "")
                                + ((pr & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 ? "I" : "");
                        log.append(ch.getUuid().toString().substring(0, 8)).append("(").append(flags).append(") ");
                        if ((pr & BluetoothGattCharacteristic.PROPERTY_READ) != 0) readQ.add(ch);   // 읽기 가능 → 값 덤프 대상
                    }
                    log.append("} ");
                }
                log.append("워치키서비스=").append(hasWatch ? "★있음" : "없음").append("; 읽기가능=").append(readQ.size()).append("개; ");
                finish.run();   // ★값 읽기는 보안(bonding) hang 유발 → 특성 목록만 덤프하고 즉시 종료
            }
        };

        try {
            log.append("connectGatt(autoConnect=true·광고대기 pend) 시도; ");
            gattHolder[0] = dev.connectGatt(ctx, true, gcb, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            log.append("connectGatt 예외: ").append(t.getMessage()).append("; ");
            finish.run();
            return;
        }
        // 12초 타임아웃
        main.postDelayed(() -> { if (!finished[0]) { log.append("30초 타임아웃(연결 못함·autoConnect pend)"); finish.run(); } }, 30000);
    }
}
