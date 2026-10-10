package com.cseini.bydsniffer.cloud;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// CryptoUtils — byd_cloud.js / CryptoUtils.swift 1:1 포팅.
// 릴레이(Java)가 BYD 클라우드를 직접 호출하기 위한 크립토 유틸.
public final class CloudCrypto {

    // md5(str) → 대문자 hex (Swift/Node와 동일)
    public static String md5Hex(String str) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(str.getBytes("UTF-8"));
            return bytesToHex(d).toUpperCase();
        } catch (Exception e) { return ""; }
    }

    // md5(md5(str))
    public static String pwdLoginKey(String str) {
        return md5Hex(md5Hex(str));
    }

    // SHA1 → 짝수idx 대문자·홀수idx 소문자, 그 뒤 짝수 위치의 '0'만 제거
    public static String sha1Mixed(String str) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(str.getBytes("UTF-8"));
            StringBuilder hexStr = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                String hex = String.format("%02x", digest[i] & 0xFF);
                if (i % 2 == 0) hexStr.append(hex.toUpperCase());
                else hexStr.append(hex.toLowerCase());
            }
            StringBuilder result = new StringBuilder();
            String hs = hexStr.toString();
            for (int i = 0; i < hs.length(); i++) {
                char c = hs.charAt(i);
                if (c != '0' || i % 2 != 0) result.append(c);
            }
            return result.toString();
        } catch (Exception e) { return ""; }
    }

    // md5(str) 소문자의 특정 부분 재배열: [24..32)+[8..16)+[16..24)+[0..8)
    public static String computeCheckcode(String str) {
        String lc = md5Hex(str).toLowerCase();
        String s24_32 = lc.substring(24, 32);
        String s8_16 = lc.substring(8, 16);
        String s16_24 = lc.substring(16, 24);
        String s0_8 = lc.substring(0, 8);
        return s24_32 + s8_16 + s16_24 + s0_8;
    }

    // AES-128-CBC, PKCS7(=PKCS5), zero IV. 결과는 대문자 hex.
    public static String aesEncryptHex(String str, String keyHex) {
        try {
            byte[] key = hexToBytes(keyHex);
            if (key == null) return "";
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            byte[] out = c.doFinal(str.getBytes("UTF-8"));
            return bytesToHex(out).toUpperCase();
        } catch (Exception e) { return ""; }
    }

    // AES-128-CBC, PKCS7 unpad, zero IV → UTF8
    public static String aesDecryptUtf8(String hexStr, String keyHex) {
        try {
            byte[] ct = hexToBytes(hexStr);
            byte[] key = hexToBytes(keyHex);
            if (ct == null || key == null) return "";
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Exception e) { return ""; }
    }

    // [(key,val),...] → "key=val" 문자열들 정렬 후 "...&password=pwd"
    public static String buildSignString(List<String[]> pairs, String password) {
        List<String> strs = new ArrayList<>();
        for (String[] kv : pairs) {
            String val = (kv[1] == null || kv[1].isEmpty()) ? "null" : kv[1];
            strs.add(kv[0] + "=" + val);
        }
        Collections.sort(strs);   // 코드포인트 오름차순(Swift/Node와 동일)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < strs.size(); i++) {
            if (i > 0) sb.append("&");
            sb.append(strs.get(i));
        }
        sb.append("&password=").append(password);
        return sb.toString();
    }

    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        if (len % 2 != 0) return null;
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    private CloudCrypto() {}
}
