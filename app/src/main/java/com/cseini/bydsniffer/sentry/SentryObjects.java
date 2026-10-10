package com.cseini.bydsniffer.sentry;

import java.util.ArrayList;
import java.util.List;

// 센트리 객체 판정 선별 — "무엇이 새로 나타났나"(운영자 10-09 오버드라이브식 "사람/차량 + 박스 + %").
// 같은 자리에 움직임 직전 기준 화면에도 있던 같은 종류의 박스는 주차된 차·서 있던 물체로 보고 뺀다(어안 영상에서 주차된 차도 0.5~0.7점이 나와 점수만으론 못 가린다).
// 순수 계산이라 JVM에서 시험한다. 박스는 {종류(1=차량 2=사람), 점수, x0, y0, x1, y1}(칸 대비 0~1).
final class SentryObjects {
    private SentryObjects() {}

    static final float PERSON_MIN = 0.40f;
    static final float VEHICLE_MIN = 0.45f;
    // 기준 화면의 같은 종류 박스와 이만큼 겹치면 같은 물체. 10-10 진단 로그: 정지한 차는 겹침 0.96, 기준 화면에 이미 있던 차가 움직인 경우는 0.56~0.66(10:11·10:25·10:28)
    // → 예전 0.35에선 움직인 차가 "원래 있던 물체"로 빠져 놓쳤다. 정지한 차의 박스 흔들림(0.9 안팎)은 넘기고 움직인 차(0.7 이하)는 새것으로 보는 0.8.
    static final float SAME_IOU = 0.8f;

    static final class Found {
        final int kind;      // 1 차량, 2 사람
        final float score;   // 0~1
        final int cell;      // 영상에서 보이는 칸 0~3(좌상·우상·좌하·우하)
        final float x0, y0, x1, y1;

        Found(int kind, float score, int cell, float x0, float y0, float x1, float y1) {
            this.kind = kind; this.score = score; this.cell = cell; this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        }

        int pct() { return Math.round(score * 100f); }
        String kindName() { return kind == 2 ? "사람" : "차량"; }
        String label() { return kindName() + " " + pct() + "%"; }
    }

    static float iou(float[] a, float[] b) {
        float ix = Math.max(0f, Math.min(a[4], b[4]) - Math.max(a[2], b[2]));
        float iy = Math.max(0f, Math.min(a[5], b[5]) - Math.max(a[3], b[3]));
        float inter = ix * iy;
        float ua = (a[4] - a[2]) * (a[5] - a[3]) + (b[4] - b[2]) * (b[5] - b[3]) - inter;
        return ua <= 0f ? 0f : inter / ua;
    }

    // cur 중 기준(base)에 같은 종류로 겹치는 것이 없는 새 박스만. base가 null이면(기준 화면 없음) 비워 둔다 — 정지한 차를 새 차로 오인하느니 판정을 안 한다.
    static List<Found> newOnly(float[][] cur, float[][] base, int cell) {
        List<Found> out = new ArrayList<>();
        if (cur == null || base == null) return out;
        for (float[] c : cur) {
            if (c == null || c.length < 6) continue;
            boolean seen = false;
            for (float[] b : base) {
                if (b == null || b.length < 6 || (int) b[0] != (int) c[0]) continue;
                if (iou(c, b) >= SAME_IOU) { seen = true; break; }
            }
            if (!seen) out.add(new Found((int) c[0], c[1], cell, c[2], c[3], c[4], c[5]));
        }
        return out;
    }

    // 알림 문구용 카메라 글자(운영자 10-09 "사람 감지 — 좌측"): 영상 칸 d(좌상0·우상1·좌하2·우하3)에 놓인 원본 칸 q = layoutMap[d],
    // 돌핀 원본 칸 실측 q0=a1 후방(B)·q1=a2 좌측(L)·q2=a3 우측(R)·q3=a4 전방(F). 모르는 값이면 '?'
    static char cameraCode(int[] layoutMap, int displayCell) {
        if (displayCell < 0 || displayCell > 3) return '?';
        int q = (layoutMap != null && layoutMap.length == 4) ? layoutMap[displayCell] : displayCell;
        switch (q) {
            case 0: return 'B';
            case 1: return 'L';
            case 2: return 'R';
            case 3: return 'F';
            default: return '?';
        }
    }

    // 알림 문구용 푸시 이벤트 이름: "sobj:<이벤트id>:<P사람|V차량><F전방|B후방|L좌측|R우측>" — 워커가 이벤트 이름을 32자까지만 통과시켜 id(20자) 포함 28자로 맞춘다
    static String objectEvent(String eventId, int kind, int[] layoutMap, int displayCell) {
        return "sobj:" + eventId + ":" + (kind == 2 ? 'P' : 'V') + cameraCode(layoutMap, displayCell);
    }

    // 못 찾았을 때 "왜"를 로그로 남기기 위한 설명(운영자 10-09: 실시간 판정이 재생 시험보다 덜 잡음 — 원인 확정용).
    // 점수 0.30 이상 후보를 높은 순으로 최대 maxN개: "차량52%[겹침.62→제외]" / "사람41%[새것]" / "사람35%[점수미달]"
    static String describe(float[][] cur, float[][] base, int maxN) {
        if (cur == null || cur.length == 0) return "후보 없음";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (float[] c : cur) {
            if (c == null || c.length < 6) continue;
            if (n >= maxN) break;
            int kind = (int) c[0];
            float min = kind == 2 ? PERSON_MIN : VEHICLE_MIN;
            float best = 0f;
            if (base != null) for (float[] b : base) {
                if (b == null || b.length < 6 || (int) b[0] != kind) continue;
                best = Math.max(best, iou(c, b));
            }
            String why;
            if (c[1] < min) why = "점수미달";
            else if (best >= SAME_IOU) why = String.format(java.util.Locale.US, "겹침%.2f→제외", best);
            else why = "새것";
            if (n > 0) sb.append(' ');
            sb.append(kind == 2 ? "사람" : "차량").append(Math.round(c[1] * 100f)).append("%[").append(why).append(']');
            n++;
        }
        return n == 0 ? "후보 없음" : sb.toString();
    }

    // ── 조명 번쩍임 판별(운영자 10-09 02:31 영상: 움직임 없이 밝기가 98→107로 계단 상승했는데 이벤트·알림) ─────────────
    // 그 칸의 기준 화면(몇 초 전) 대비 현재 평균 밝기가 이만큼 달라졌고 새 사람·차량이 없으면 "조명 의심"
    static final float LIGHT_STEP = 4.5f;   // 10-09 02:31 번쩍임 영상의 기준 대비 차가 5.7(표본 평균), 자동 노출 흔들림은 보통 3 이하

    // NV21 프레임의 한 칸(rx,ry,rw,rh) 평균 밝기(Y). 8픽셀 간격 표본이라 7MB 프레임에서도 가볍다
    static float cellMeanY(byte[] nv21, int w, int rx, int ry, int rw, int rh) {
        long sum = 0;
        int n = 0;
        for (int y = ry; y < ry + rh; y += 8) {
            int o = y * w;
            for (int x = rx; x < rx + rw; x += 8) { sum += (nv21[o + x] & 0xFF); n++; }
        }
        return n == 0 ? 0f : (float) sum / n;
    }

    static boolean lightStep(float curMean, float baseMean) {
        return Math.abs(curMean - baseMean) >= LIGHT_STEP;
    }

    // 기준 점수를 넘은 것 중 사람이 있으면 사람(가장 높은 점수), 없으면 차량(가장 높은 점수). 없으면 null
    static Found pick(List<Found> found) {
        Found bestPerson = null, bestVehicle = null;
        for (Found f : found) {
            if (f.kind == 2 && f.score >= PERSON_MIN && (bestPerson == null || f.score > bestPerson.score)) bestPerson = f;
            if (f.kind == 1 && f.score >= VEHICLE_MIN && (bestVehicle == null || f.score > bestVehicle.score)) bestVehicle = f;
        }
        return bestPerson != null ? bestPerson : bestVehicle;
    }
}
