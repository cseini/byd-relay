package com.cseini.bydsniffer;

import android.util.Log;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class NoiseFilter {
    private static final String TAG = "NoiseFilter";

    // 기본 제외 device 목록
    private static final Set<String> DEVICE_DENYLIST = new HashSet<String>() {{
    }};

    // Anti-flood: (device, eventType, valueKey) → (count, lastTime)
    private static final Map<String, FloodInfo> floodTracker = new HashMap<>();
    private static final long FLOOD_WINDOW_MS = 2000;  // 2초
    private static final int FLOOD_THRESHOLD = 3;  // 3회 이상이면 억제

    public static boolean shouldLog(String device, String eventType, String valueKey) {
        // 1. Denylist 체크
        if (DEVICE_DENYLIST.contains(device)) {
            return false;
        }

        // 2. Anti-flood 체크
        String floodKey = device + ":" + eventType + ":" + valueKey;
        long now = System.currentTimeMillis();

        FloodInfo info = floodTracker.get(floodKey);
        if (info == null) {
            info = new FloodInfo();
            floodTracker.put(floodKey, info);
            return true;  // 첫 발생 시 로그
        }

        // 윈도우 시간 초과했으면 카운트 리셋
        if (now - info.lastTime > FLOOD_WINDOW_MS) {
            info.count = 1;
            info.lastTime = now;
            return true;
        }

        // 윈도우 내에서 카운트 증가
        info.count++;
        info.lastTime = now;

        // 쓰로틀: 3회 이상이면 로그 안 함
        if (info.count >= FLOOD_THRESHOLD) {
            return false;
        }

        return true;
    }

    private static class FloodInfo {
        int count = 0;
        long lastTime = 0;
    }
}
