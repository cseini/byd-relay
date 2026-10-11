package com.cseini.bydsniffer.sentry;

/** 주행 녹화 영상 위 둘째 띠에 그릴 주행 정보 한 장 — 호스트가 이미 캐시한 값으로 채운다(프레임마다 BYD getter를 부르지 않음). -1은 "이 차에서 읽힌 적 없음"이라 그 아이콘은 그리지 않는다. */
public final class DriveInfo {
    public char gear = '-';   // P R N D, 모르면 '-'
    public int speedKmh = -1;
    public int turn;          // 0 꺼짐 · 1 왼쪽 · 2 오른쪽 · 3 양쪽(비상등)
    public int brake = -1;    // 0 안 밟음 · 1 밟음
    public int accel = -1;    // 가속 페달 0~10
    public int pos = -1, low = -1, high = -1, fogRear = -1;   // 차폭·하향·상향·뒤 안개등: 0 꺼짐 · 1 켜짐
    public int belt = -1;     // 순정 블랙박스와 같은 해석: 1이면 아이콘 꺼짐(정상), 0이면 빨간 경고 아이콘

    /** 아이콘을 못 쓸 때(순정 앱이 없는 기기) 띠에 찍는 ASCII 한 줄. */
    public String toText() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(gear).append("  ").append(speedKmh < 0 ? "--" : String.valueOf(speedKmh)).append("KM/H");
        if (turn == 1) sb.append("  <L");
        else if (turn == 2) sb.append("  R>");
        else if (turn == 3) sb.append("  <HAZ>");
        if (brake == 1) sb.append("  BRK");
        if (accel > 0) sb.append("  ACC ").append(accel * 10).append('%');
        StringBuilder li = new StringBuilder();
        if (pos == 1) li.append(" POS");
        if (low == 1) li.append(" LOW");
        if (high == 1) li.append(" HIGH");
        if (fogRear == 1) li.append(" RFOG");
        if (li.length() > 0) sb.append(' ').append(li);
        if (belt == 0) sb.append("  BELT!");
        return sb.toString();
    }
}
