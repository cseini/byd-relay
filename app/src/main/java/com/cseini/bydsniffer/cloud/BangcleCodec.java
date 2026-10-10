package com.cseini.bydsniffer.cloud;

import android.util.Base64;
import java.io.ByteArrayOutputStream;

// BangcleCodec — byd_cloud.js / BangcleCodec.swift 1:1 포팅. 화이트박스 AES-CBC.
// 봉인(encodeEnvelope) / 해제(decodeEnvelope). byte는 전부 &0xFF로 부호없이 처리.
public final class BangcleCodec {
    private final BangcleTables t;

    public BangcleCodec(BangcleTables tables) { this.t = tables; }

    private void prepareAesMatrix(byte[] input, byte[] output) {
        for (int i = 0; i < 4; i++)
            for (int i2 = 0; i2 < 4; i2++)
                output[(i * 8) + i2] = input[(i2 * 4) + i];
    }

    byte[] encryptBlockAuth(byte[] input, int rounds) {
        byte[] state = new byte[32];
        byte[] temp = new byte[64];
        byte[] output = new byte[16];

        prepareAesMatrix(input, state);
        int maxRounds = Math.min(9, Math.max(0, rounds));

        for (int round = 0; round < maxRounds; round++) {
            int roundBase = round * 4;
            for (int col = 0; col < 4; col++) {
                int permIndex = t.permEncrypt[col * 2] & 0xFF;
                for (int row = 0; row < 4; row++) {
                    int adjusted = (permIndex + row) & 3;
                    int stateByte = state[(col * 8) + adjusted] & 0xFF;
                    int colOffset = col + ((roundBase + adjusted) * 4);
                    int idx = stateByte + (colOffset * 256);
                    if (idx >= 0 && idx * 4 + 3 < t.round.length) {
                        int sourceStart = idx * 4;
                        int sourceEnd = Math.min(idx * 4 + 4, t.round.length);
                        int destStart = (col * 16) + (row * 4);
                        for (int srcIdx = sourceStart; srcIdx < sourceEnd; srcIdx++) {
                            int dstIdx = destStart + (srcIdx - sourceStart);
                            if (dstIdx < temp.length) temp[dstIdx] = t.round[srcIdx];
                        }
                    }
                }
            }
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 4; col++) {
                    int base = row + col * 4;
                    int lowNibble = temp[base] & 0x0F;
                    int highNibble = temp[base] & 0xF0;
                    int byte2 = temp[base + 16] & 0xFF;
                    int byte3 = temp[base + 32] & 0xFF;
                    int byte4 = temp[base + 48] & 0xFF;
                    int tableBase = (col * 24) + (round * 96) + (6 * row);
                    for (int i = 0; i < 3; i++) {
                        int byteVal = i == 0 ? byte2 : (i == 1 ? byte3 : byte4);
                        int idx1 = ((tableBase + (i * 2)) * 256) + (((byteVal << 4) & 0xFF) | lowNibble);
                        int idx2 = ((tableBase + (i * 2) + 1) * 256) + ((((byteVal >> 4) << 4) | (highNibble >> 4)) & 0xFF);
                        lowNibble = t.xor[idx1] & 0x0F;
                        highNibble = (t.xor[idx2] & 0x0F) << 4;
                    }
                    state[(row * 8) + col] = (byte) ((highNibble | lowNibble) & 0xFF);
                }
            }
        }

        if (rounds == 10) {
            byte[] orig = state.clone();
            for (int i = 0; i < 4; i++) {
                int i31 = i & 3;
                state[i] = t.finalTable[(orig[i31] & 0xFF) + (i31 * 1024)];
                int i33 = (i + 1) & 3;
                state[i + 8] = t.finalTable[(orig[i33 + 8] & 0xFF) + (i33 * 1024) + 256];
                int i34 = (i + 2) & 3;
                state[i + 16] = t.finalTable[(orig[i34 + 16] & 0xFF) + (i34 * 1024) + 512];
                int i35 = (i + 3) & 3;
                state[i + 24] = t.finalTable[(orig[i35 + 24] & 0xFF) + (i35 * 1024) + 768];
            }
        }

        for (int row = 0; row < 4; row++)
            for (int col = 0; col < 4; col++)
                output[(col * 4) + row] = state[(row * 8) + col];
        return output;
    }

    byte[] decryptBlockAuth(byte[] input, int rounds) {
        byte[] state = new byte[32];
        byte[] temp = new byte[64];
        byte[] output = new byte[16];

        prepareAesMatrix(input, state);
        int minRounds = Math.max(0, rounds);

        for (int round = 9; round >= minRounds; round--) {
            int roundBase = round * 4;
            for (int col = 0; col < 4; col++) {
                int permIndex = t.permDecrypt[col * 2] & 0xFF;
                for (int row = 0; row < 4; row++) {
                    int adjusted = (permIndex + row) & 3;
                    int stateByte = state[(col * 8) + adjusted] & 0xFF;
                    int colOffset = col + ((roundBase + adjusted) * 4);
                    int idx = stateByte + (colOffset * 256);
                    if (idx >= 0 && idx * 4 + 3 < t.invRound.length) {
                        int sourceStart = idx * 4;
                        int sourceEnd = Math.min(idx * 4 + 4, t.invRound.length);
                        int destStart = (col * 16) + (row * 4);
                        for (int srcIdx = sourceStart; srcIdx < sourceEnd; srcIdx++) {
                            int dstIdx = destStart + (srcIdx - sourceStart);
                            if (dstIdx < temp.length) temp[dstIdx] = t.invRound[srcIdx];
                        }
                    }
                }
            }
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 4; col++) {
                    int base = row + col * 4;
                    int lowNibble = temp[base] & 0x0F;
                    int highNibble = temp[base] & 0xF0;
                    int byte2 = temp[base + 16] & 0xFF;
                    int byte3 = temp[base + 32] & 0xFF;
                    int byte4 = temp[base + 48] & 0xFF;
                    int tableBase = (col * 24) + (round * 96) + (6 * row);
                    for (int i = 0; i < 3; i++) {
                        int byteVal = i == 0 ? byte2 : (i == 1 ? byte3 : byte4);
                        int idx1 = ((tableBase + (i * 2)) * 256) + (((byteVal << 4) & 0xFF) | lowNibble);
                        int idx2 = ((tableBase + (i * 2) + 1) * 256) + ((((byteVal >> 4) << 4) | (highNibble >> 4)) & 0xFF);
                        lowNibble = t.invXor[idx1] & 0x0F;
                        highNibble = (t.invXor[idx2] & 0x0F) << 4;
                    }
                    state[(row * 8) + col] = (byte) ((highNibble | lowNibble) & 0xFF);
                }
            }
        }

        if (rounds == 1) {
            byte[] orig = state.clone();
            for (int i = 0; i < 4; i++) {
                state[i] = t.invFirst[(orig[i] & 0xFF) + (i * 1024)];
                int i34 = (3 + i) & 3;
                state[i + 8] = t.invFirst[(orig[i34 + 8] & 0xFF) + (i34 * 1024) + 256];
                int i35 = (2 + i) & 3;
                state[i + 16] = t.invFirst[(orig[i35 + 16] & 0xFF) + (i35 * 1024) + 512];
                int i36 = (1 + i) & 3;
                state[i + 24] = t.invFirst[(orig[i36 + 24] & 0xFF) + (i36 * 1024) + 768];
            }
        }

        for (int row = 0; row < 4; row++)
            for (int col = 0; col < 4; col++)
                output[(col * 4) + row] = state[(row * 8) + col];
        return output;
    }

    private void xorInto(byte[] data, byte[] key, int keyOffset) {
        for (int i = 0; i < data.length; i++) data[i] = (byte) (data[i] ^ key[keyOffset + i]);
    }

    byte[] encryptCbc(byte[] plaintext, byte[] iv) {
        if (plaintext.length % 16 != 0) return new byte[0];
        byte[] ciphertext = new byte[plaintext.length];
        byte[] currentIv = iv.clone();
        byte[] block = new byte[16];
        for (int offset = 0; offset < plaintext.length; offset += 16) {
            for (int i = 0; i < 16; i++) block[i] = plaintext[offset + i];
            xorInto(block, currentIv, 0);
            byte[] encrypted = encryptBlockAuth(block, 10);
            for (int i = 0; i < 16; i++) ciphertext[offset + i] = encrypted[i];
            currentIv = encrypted.clone();
        }
        return ciphertext;
    }

    byte[] decryptCbc(byte[] ciphertext, byte[] iv) {
        if (ciphertext.length % 16 != 0) return new byte[0];
        byte[] plaintext = new byte[ciphertext.length];
        byte[] currentIv = iv.clone();
        byte[] block = new byte[16];
        for (int offset = 0; offset < ciphertext.length; offset += 16) {
            for (int i = 0; i < 16; i++) block[i] = ciphertext[offset + i];
            byte[] decrypted = decryptBlockAuth(block, 1);
            xorInto(decrypted, currentIv, 0);
            for (int i = 0; i < 16; i++) plaintext[offset + i] = decrypted[i];
            currentIv = block.clone();
        }
        return plaintext;
    }

    private byte[] addPkcs7(byte[] data) {
        int paddingLength = 16 - (data.length % 16);
        byte[] padded = new byte[data.length + paddingLength];
        System.arraycopy(data, 0, padded, 0, data.length);
        for (int i = 0; i < paddingLength; i++) padded[data.length + i] = (byte) paddingLength;
        return padded;
    }

    private byte[] stripPkcs7(byte[] data) {
        if (data.length == 0) return data;
        int paddingLength = data[data.length - 1] & 0xFF;
        if (paddingLength < 1 || paddingLength > 16 || paddingLength > data.length) return data;
        byte[] out = new byte[data.length - paddingLength];
        System.arraycopy(data, 0, out, 0, out.length);
        return out;
    }

    public String encodeEnvelope(String json) {
        try {
            byte[] plainBytes = json.getBytes("UTF-8");
            byte[] paddedBytes = addPkcs7(plainBytes);
            byte[] cipherBytes = encryptCbc(paddedBytes, new byte[16]);
            String base64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP);
            return "F" + base64;
        } catch (Exception e) { return ""; }
    }

    public String decodeEnvelope(String envelope) {
        try {
            String cleaned = envelope
                    .replace(" ", "").replace("\t", "").replace("\n", "").replace("\r", "")
                    .trim().replace("-", "+").replace("_", "/");
            if (cleaned.isEmpty() || !cleaned.startsWith("F")) return "";
            cleaned = cleaned.substring(1);
            int padding = (4 - (cleaned.length() % 4)) % 4;
            for (int i = 0; i < padding; i++) cleaned += "=";
            byte[] cipherBytes = Base64.decode(cleaned, Base64.NO_WRAP);
            byte[] plainBytes = decryptCbc(cipherBytes, new byte[16]);
            byte[] unpadded = stripPkcs7(plainBytes);
            return new String(unpadded, "UTF-8");
        } catch (Exception e) { return ""; }
    }
}
