package com.cseini.bydsniffer;

/** 씨라7 주차 중 유지(D5 hold)의 순수 판정 — 지렛대 비트와 12V 연속 표본 판정. 단위 시험 대상. */
final class D5HoldLogic {
    static final int MCUPOWER = 1;    // 전원 장치 -1442840502
    static final int FLAGS782 = 2;    // 특수 장치 782237711 · 782237728 (오버드라이브 센트리 깃발)
    static final int FLAGS1901 = 4;   // 특수 장치 1901 · 1902 (우리가 v48 때 이식한 쪽)
    static final int QNX = 8;         // 차량 네트워크 관리(OTA 서비스 바인더)
    static final int CAM = 16;        // 파노라마 작업 모드 하트비트(BYDAutoManager)
    static final int AP = 32;         // vendor.peripheral.shutdown_critical_list 토큰
    static final int SHELL = 64;      // 셸 깨우기(PowerManager.wakeUp) + 화면 끄기 창 — 돌핀에서 쓰던 방식
    static final int ALL = 127;

    static final double CUTOFF_V = 11.8;
    static final int CUTOFF_SAMPLES = 3;

    private D5HoldLogic() {}

    /** 전압 표본 하나를 반영한 연속 저전압 횟수. 표본이 없거나(null) 6~17V 밖이면 값을 믿지 않고 횟수를 그대로 둔다. */
    static int voltStreak(int streak, Double volts, double cutoff) {
        if (volts == null || volts < 6.0 || volts > 17.0) return streak;
        return volts < cutoff ? streak + 1 : 0;
    }

    static boolean cutoffReached(int streak) { return streak >= CUTOFF_SAMPLES; }

    /** 해제할 때 되돌릴 지렛대 이름들(사람이 읽는 로그용). 1901·1902는 오버드라이브도 해제하지 않아 되돌리지 않는다. */
    static String releaseList(int held) {
        StringBuilder sb = new StringBuilder();
        if ((held & FLAGS782) != 0) sb.append("782(0/2) ");
        if ((held & MCUPOWER) != 0) sb.append("전원유지(0) ");
        if ((held & CAM) != 0) sb.append("카메라(0) ");
        if ((held & AP) != 0) sb.append("AP토큰제거 ");
        if ((held & QNX) != 0) sb.append("QNX ");
        return sb.toString().trim();
    }

    static String name(int bit) {
        switch (bit) {
            case MCUPOWER: return "mp";
            case FLAGS782: return "f782";
            case FLAGS1901: return "f1901";
            case QNX: return "qnx";
            case CAM: return "cam";
            case AP: return "ap";
            case SHELL: return "sh";
            default: return "?";
        }
    }
}
