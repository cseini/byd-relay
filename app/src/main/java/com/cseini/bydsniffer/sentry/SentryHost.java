package com.cseini.bydsniffer.sentry;

import android.content.Context;
import android.content.SharedPreferences;

// ★센트리 패키지가 보는 유일한 바깥 창(docs/SENTRY-DESIGN.md 1절). SnifferService가 구현해 SentryManager.init(host)로 넘긴다.
//   패키지는 SnifferService·RelayClient를 직접 참조하지 않는다(단독 빌드·JUnit). 모든 메서드는 어느 스레드에서든 호출될 수 있다.
public interface SentryHost {
    Context context();
    SharedPreferences prefs();                 // 릴레이 공용 prefs — 키는 sentry_* 접두어만 쓴다

    void log(String msg);                      // SENTRY 카테고리 한 줄(postToD1)
    void putStatus(String key, Object val);    // relay_state 키(실시간, 링버퍼 무관). 센트리는 "sentry" 키 하나에 JSON 문자열(≤300자)
    void postCarEvent(String event);           // 워커 push("sentry:<eventId>")

    int ignitionState();                       // 0=시동 OFF, 1=ON, 음수=미확정
    boolean rtcActive();                       // 실시간 보기(CAMRTC) 중 — 센트리는 양보
    boolean detectActive();                    // 다른 camDetect(앱 근접 감지 등) 중 — 센트리는 PAUSED
    boolean phoneNearFresh();                  // 주 사용권 폰 근접(15분 신선) — pause_when_phone_near 게이트
    default boolean conservativeRetry() { return false; }   // 씨라7: 카메라(점유형) 열기가 실패하면 1·2·4·8·15분 간격으로만 다시 시도(다른 카메라 앱을 흔들지 않게)
    boolean lowVoltage();                      // 12V 저전압 지속(상시생존의 sustainedLow) — 센트리도 멈춤
    default boolean camOff() { return false; }   // 앱 설정에서 카메라 기능을 꺼 둠(cam_off) — 센트리는 카메라를 못 쓰므로 IDLE로 쉰다(켜질 때까지 1분마다 무장 시도·실패 로그가 쌓이지 않게)
    default int batteryPct() { return -1; }
    default String runShell(String cmd) { return null; }   // 차 셸(shell uid) 1회 실행 — 출력, 셸에 못 붙으면 null. 외장 포맷(sm format)에만 쓴다
    default float[] objectScores(byte[] nv21, int w, int h) { return null; }   // 4칸 각각에서 잰 {사람 최고 점수, 차량 최고 점수}(0~1), 모델이 없거나 실패면 null — null이면 거르지 않고 통과(안전 쪽)
    default long objectLastMs() { return 0; }
    // 재생 시험(저장된 녹화를 차 안에서 다시 돌려 판정)을 지금 돌려도 되는가 — 운영자판이 주차(P)·정차일 때만
    default boolean replayAllowed() { return false; }
    // 박스까지: 영역(rx,ry,rw,rh) 하나에서 {종류(1차량·2사람), 점수, x0,y0,x1,y1}(영역 대비 0~1) 목록. 모델이 없거나 실패면 null
    default float[][] objectDetect(byte[] nv21, int w, int h, int rx, int ry, int rw, int rh, float minScore) { return null; }
    default void objectRelease() { }   // 물체 인식 모델 메모리 반납(센트리 세션이 끝날 때)
    default boolean lanAuthRequired() { return false; }                    // 공유판은 /sentry/* 접속에 페어키 서명(grant)을 요구한다. 운영자판은 사설망만(false)
    default boolean verifySentryGrant(String sgx, String sg) { return true; }   // lanAuthRequired일 때만 부름 — 릴레이 페어키 K로 grant 검증                                      // 물체 인식 모델 메모리 반납(센트리 세션이 끝날 때)    // 구동 배터리 잔량 0~100, 모르면 음수 — 영상 위 배터리 표시(OSD)용

    // 카메라 세션: 호스트가 camDetect(mode="sentry", view=5, 무제한)를 시작/중지한다. 프레임은 SentryManager.onFrame으로 들어온다.
    String startSentryDetect();                // 성공이면 null 또는 "감지 시작…", 실패면 사유 문자열
    void stopSentryDetect();
}
