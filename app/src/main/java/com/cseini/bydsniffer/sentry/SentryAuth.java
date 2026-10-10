package com.cseini.bydsniffer.sentry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// 공유판 센트리 접속(/sentry/*) 인증 — 폰 앱이 페어키 K로 만든 "권한 값(grant)"을 주소 끝에 붙인다: ?sgx=<만료 epoch ms>&sg=<HMAC 16진>.
//   원문 = "SENTRY|" + vin + "|" + sgx. 카메라 보기 권한(CAMRTC grant)과 같은 방식 — 한 번 만든 값을 만료(최대 약 1시간)까지 재사용하므로
//   영상 재생의 구간 요청·웹 페이지 안 하위 요청(헤더를 못 붙임)에도 그대로 쓴다. vin이 들어가 다른 차에 재사용할 수 없고, K 없이는 못 만든다.
//   운영자판은 쓰지 않는다(사설망만 허용 그대로). 순수 로직이라 JVM에서 검증한다.
public final class SentryAuth {
    static final long MAX_FUTURE_MS = 3_700_000L;   // 만료는 지금으로부터 최대 약 1시간 + 시계 오차

    private SentryAuth() {}

    static String grantMessage(String vin, long sgx) {
        return "SENTRY|" + vin + "|" + sgx;
    }

    static boolean timeOk(long sgx, long nowMs) {
        return sgx > nowMs && sgx - nowMs <= MAX_FUTURE_MS;
    }

    // 키·vin·만료·서명이 모두 맞으면 true
    public static boolean verify(String key, String vin, String sgxStr, String sig, long nowMs) {
        if (key == null || key.isEmpty() || vin == null || vin.isEmpty() || sgxStr == null || sig == null || sig.isEmpty()) return false;
        long sgx;
        try {
            sgx = Long.parseLong(sgxStr);
        } catch (NumberFormatException e) {
            return false;
        }
        if (!timeOk(sgx, nowMs)) return false;
        String expect = hmacHex(key, grantMessage(vin, sgx));
        return expect != null && constantTimeEq(expect, sig.toLowerCase(java.util.Locale.US));
    }

    static String hmacHex(String key, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
            byte[] h = mac.doFinal(msg.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format(java.util.Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static boolean constantTimeEq(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    // ★헤드유닛 자신의 녹화 보기 화면(SentryViewerActivity)용 — 릴레이가 자기 K로 권한 값을 만든다. 공유판은 /sentry/*에 권한 값이 있어야 열린다
    public static String selfGrantQuery(String key, String vin, long nowMs) {
        if (key == null || key.isEmpty() || vin == null || vin.isEmpty()) return null;
        long sgx = nowMs + 3_300_000L;
        String sg = hmacHex(key, grantMessage(vin, sgx));
        return sg == null ? null : "sgx=" + sgx + "&sg=" + sg;
    }

    // 주소에 이어 붙일 값(검증을 통과한 값만 되돌려 쓴다 — 숫자·16진만이라 주입 위험 없음)
    static String grantQuery(String sgx, String sg) {
        return "&sgx=" + sgx + "&sg=" + sg.toLowerCase(java.util.Locale.US);
    }
}
