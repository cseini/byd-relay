package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SentryAuthTest {
    private static final String K = "페어키-테스트-K";
    private static final String VIN = "LGXXXXXXXXXXXXXXXX";
    private static final long NOW = 1_791_190_000_000L;

    private static String sign(String key, String vin, long sgx) {
        return SentryAuth.hmacHex(key, "SENTRY|" + vin + "|" + sgx);
    }

    @Test
    public void validGrant_passes_includingUpperCaseHex() {
        long sgx = NOW + 600_000L;
        String sig = sign(K, VIN, sgx);
        assertTrue(SentryAuth.verify(K, VIN, String.valueOf(sgx), sig, NOW));
        assertTrue(SentryAuth.verify(K, VIN, String.valueOf(sgx), sig.toUpperCase(), NOW));
    }

    @Test
    public void wrongKey_wrongVin_tamperedExpiry_rejected() {
        long sgx = NOW + 600_000L;
        String sig = sign(K, VIN, sgx);
        assertFalse(SentryAuth.verify("다른키", VIN, String.valueOf(sgx), sig, NOW));
        assertFalse(SentryAuth.verify(K, "LGXOTHER", String.valueOf(sgx), sig, NOW));
        assertFalse(SentryAuth.verify(K, VIN, String.valueOf(sgx + 1), sig, NOW));   // 만료를 늘려 재사용
    }

    @Test
    public void expiredOrTooFarFuture_rejected() {
        long past = NOW - 1;
        assertFalse(SentryAuth.verify(K, VIN, String.valueOf(past), sign(K, VIN, past), NOW));
        long far = NOW + 4_000_000L;   // 1시간 넘게
        assertFalse(SentryAuth.verify(K, VIN, String.valueOf(far), sign(K, VIN, far), NOW));
        long edge = NOW + 3_700_000L;
        assertTrue(SentryAuth.verify(K, VIN, String.valueOf(edge), sign(K, VIN, edge), NOW));
    }

    @Test
    public void missingOrMalformedInputs_rejected() {
        long sgx = NOW + 1000L;
        String sig = sign(K, VIN, sgx);
        assertFalse(SentryAuth.verify(null, VIN, String.valueOf(sgx), sig, NOW));
        assertFalse(SentryAuth.verify(K, "", String.valueOf(sgx), sig, NOW));
        assertFalse(SentryAuth.verify(K, VIN, null, sig, NOW));
        assertFalse(SentryAuth.verify(K, VIN, "abc", sig, NOW));
        assertFalse(SentryAuth.verify(K, VIN, String.valueOf(sgx), "", NOW));
        assertFalse(SentryAuth.verify(K, VIN, String.valueOf(sgx), sig.substring(1), NOW));   // 길이 다름
    }

    @Test
    public void grantQuery_lowercasesAndFormats() {
        assertEquals("&sgx=123&sg=abcdef", SentryAuth.grantQuery("123", "ABCDEF"));
    }
}
