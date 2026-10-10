package com.cseini.bydsniffer.cloud;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

// BydCloud — byd_cloud.js BYDCloud 1:1 포팅. 릴레이(Java)가 BYD 클라우드를 직접 호출.
// 동기(blocking) — 호출자가 워커 스레드에서 실행할 것.
public final class BydCloud {

    public static final class Result {
        public final boolean success;
        public final String error;
        public final String userId;
        Result(boolean s, String e, String u) { success = s; error = e; userId = u; }
        static Result ok(String u) { return new Result(true, null, u); }
        static Result fail(String e) { return new Result(false, e, null); }
    }

    private final String baseUrl, countryCode, language, timeZone;
    private final BangcleCodec codec;
    private final Map<String, String> dp = new LinkedHashMap<>();   // deviceProfile

    public String userId = "";
    public String signToken = "";
    public String encryToken = "";

    public BydCloud(String region, BangcleTables tables) {
        String r = (region == null ? "KR" : region).toUpperCase().trim();
        // KR 고정 config(운영자 리전). 필요 시 확장.
        this.baseUrl = "https://dilinkappoversea-kr-ali.byd.auto";
        this.countryCode = "KR";
        this.language = "ko";
        this.timeZone = "Asia/Seoul";
        this.codec = new BangcleCodec(tables);
        dp.put("ostype", "and");
        dp.put("imei", "BANGCLE01234");
        dp.put("mac", "00:00:00:00:00:00");
        dp.put("model", "POCO F1");
        dp.put("sdk", "35");
        dp.put("mod", "Xiaomi");
        dp.put("imeiMD5", "00000000000000000000000000000000");
        dp.put("mobileBrand", "XIAOMI");
        dp.put("mobileModel", "POCO F1");
        dp.put("deviceType", "0");
        dp.put("networkType", "wifi");
        dp.put("osType", "15");
        dp.put("osVersion", "35");
        dp.put("appInnerVersion", "322");
        dp.put("appVersion", "3.2.2");
    }

    public void restoreSession(String username, String uid, String signTok, String encryTok) {
        dp.put("imeiMD5", CloudCrypto.md5Hex(username));
        this.userId = uid;
        this.signToken = signTok;
        this.encryToken = encryTok;
    }

    private Map<String, String> buildDeviceMap(String username) {
        dp.put("imeiMD5", CloudCrypto.md5Hex(username));
        return dp;
    }

    // dict: List of Object[]{key(String), value(String|Integer|List<Object[]>|null)} — 순서 유지 JSON.
    @SuppressWarnings("unchecked")
    private String toSortedJson(List<Object[]> dict) {
        StringBuilder p = new StringBuilder("{");
        boolean first = true;
        for (Object[] kv : dict) {
            if (!first) p.append(",");
            first = false;
            String key = (String) kv[0];
            p.append("\"").append(key.replace("\"", "\\\"")).append("\":");
            Object v = kv[1];
            if (v instanceof String) {
                p.append("\"").append(((String) v).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            } else if (v instanceof Integer || v instanceof Long) {
                p.append(v.toString());
            } else if (v == null) {
                p.append("null");
            } else if (v instanceof List) {
                p.append(toSortedJson((List<Object[]>) v));
            } else {
                p.append("\"\"");
            }
        }
        p.append("}");
        return p.toString();
    }

    private List<String[]> buildSignPairs(List<String[]> dict) {
        List<String[]> sorted = new ArrayList<>(dict);
        Collections.sort(sorted, (a, b) -> a[0].compareTo(b[0]));
        return sorted;
    }

    private String rnd(int len) {
        return CloudCrypto.md5Hex(String.valueOf(Math.random())).toUpperCase().substring(0, len);
    }

    public Result login(String username, String password) {
        try {
            Map<String, String> d = buildDeviceMap(username);
            String timeStamp = String.valueOf(System.currentTimeMillis());
            String random = rnd(32);

            List<Object[]> innerMap = new ArrayList<>();
            innerMap.add(new Object[]{"agreeStatus", "0"});
            innerMap.add(new Object[]{"agreementType", "[1,2]"});
            innerMap.add(new Object[]{"appInnerVersion", nn(d.get("appInnerVersion"))});
            innerMap.add(new Object[]{"appVersion", nn(d.get("appVersion"))});
            innerMap.add(new Object[]{"deviceName", nn(d.get("mobileBrand")) + " " + nn(d.get("mobileModel"))});
            innerMap.add(new Object[]{"deviceType", nn(d.get("deviceType"))});
            innerMap.add(new Object[]{"imeiMD5", nn(d.get("imeiMD5"))});
            innerMap.add(new Object[]{"isAuto", "0"});
            innerMap.add(new Object[]{"mobileBrand", nn(d.get("mobileBrand"))});
            innerMap.add(new Object[]{"mobileModel", nn(d.get("mobileModel"))});
            innerMap.add(new Object[]{"networkType", nn(d.get("networkType"))});
            innerMap.add(new Object[]{"osType", nn(d.get("osType"))});
            innerMap.add(new Object[]{"osVersion", nn(d.get("osVersion"))});
            innerMap.add(new Object[]{"random", random});
            innerMap.add(new Object[]{"softType", "1"});
            innerMap.add(new Object[]{"timeStamp", timeStamp});
            innerMap.add(new Object[]{"timeZone", timeZone});

            String innerJson = toSortedJson(innerMap);
            String pwdKey = CloudCrypto.pwdLoginKey(password);
            String encryData = CloudCrypto.aesEncryptHex(innerJson, pwdKey);

            List<String[]> signMap = new ArrayList<>();
            for (Object[] kv : innerMap) if (kv[1] instanceof String) signMap.add(new String[]{(String) kv[0], (String) kv[1]});
            signMap.add(new String[]{"appName", "pyBYD+0.1.dev2+ge0a1f5e27"});
            signMap.add(new String[]{"countryCode", countryCode});
            signMap.add(new String[]{"functionType", "pwdLogin"});
            signMap.add(new String[]{"identifier", username});
            signMap.add(new String[]{"identifierType", "0"});
            signMap.add(new String[]{"language", language});
            signMap.add(new String[]{"reqTimestamp", timeStamp});

            String signString = CloudCrypto.buildSignString(buildSignPairs(signMap), CloudCrypto.md5Hex(password));
            String sign = CloudCrypto.sha1Mixed(signString);

            List<Object[]> envelopeMap = new ArrayList<>();
            envelopeMap.add(new Object[]{"appName", "pyBYD+0.1.dev2+ge0a1f5e27"});
            envelopeMap.add(new Object[]{"countryCode", countryCode});
            envelopeMap.add(new Object[]{"encryData", encryData});
            envelopeMap.add(new Object[]{"functionType", "pwdLogin"});
            envelopeMap.add(new Object[]{"identifier", username});
            envelopeMap.add(new Object[]{"identifierType", "0"});
            envelopeMap.add(new Object[]{"imeiMD5", nn(d.get("imeiMD5"))});
            envelopeMap.add(new Object[]{"isAuto", "0"});
            envelopeMap.add(new Object[]{"language", language});
            envelopeMap.add(new Object[]{"reqTimestamp", timeStamp});
            envelopeMap.add(new Object[]{"sign", sign});
            envelopeMap.add(new Object[]{"signKey", password});
            envelopeMap.add(new Object[]{"ostype", nn(d.get("ostype"))});
            envelopeMap.add(new Object[]{"imei", nn(d.get("imei"))});
            envelopeMap.add(new Object[]{"mac", nn(d.get("mac"))});
            envelopeMap.add(new Object[]{"model", nn(d.get("model"))});
            envelopeMap.add(new Object[]{"sdk", nn(d.get("sdk"))});
            envelopeMap.add(new Object[]{"mod", nn(d.get("mod"))});
            envelopeMap.add(new Object[]{"serviceTime", timeStamp});

            String envelopeJson = toSortedJson(envelopeMap);
            String checkcode = CloudCrypto.computeCheckcode(envelopeJson);
            List<Object[]> finalMap = new ArrayList<>(envelopeMap);
            finalMap.add(new Object[]{"checkcode", checkcode});
            String encoded = codec.encodeEnvelope(toSortedJson(finalMap));

            JSONObject resp = post("/app/account/login", encoded);
            String code = resp.optString("code", "0");
            if (!code.equals("0")) return Result.fail(code + ": " + resp.optString("message", "Unknown error"));

            String respondData = resp.optString("respondData", "");
            if (respondData.isEmpty()) return Result.fail("No respondData");
            String innerData = CloudCrypto.aesDecryptUtf8(respondData, pwdKey);
            JSONObject inner = new JSONObject(innerData);
            JSONObject token = inner.optJSONObject("token");
            if (token == null) return Result.fail("No token");
            this.userId = token.optString("userId", "");
            this.signToken = token.optString("signToken", "");
            this.encryToken = token.optString("encryToken", "");
            dp.put("imeiMD5", CloudCrypto.md5Hex(username));
            return Result.ok(userId);
        } catch (Exception e) {
            return Result.fail("Error: " + e.getMessage());
        }
    }

    // 명령 전송. params=null 이면 controlParamsMap 생략. poll=true면 완료 폴링.
    public Result sendCommand(String vin, String commandType, List<Object[]> params, String pin, boolean poll) {
        int maxAttempts = 4;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String timeStamp = String.valueOf(System.currentTimeMillis());
                String random = rnd(16);

                List<Object[]> innerMap = new ArrayList<>();
                innerMap.add(new Object[]{"deviceType", nn(dp.get("deviceType"))});
                innerMap.add(new Object[]{"imeiMD5", nn(dp.get("imeiMD5"))});
                innerMap.add(new Object[]{"networkType", nn(dp.get("networkType"))});
                innerMap.add(new Object[]{"random", random});
                innerMap.add(new Object[]{"timeStamp", timeStamp});
                innerMap.add(new Object[]{"version", nn(dp.get("appInnerVersion"))});
                innerMap.add(new Object[]{"vin", vin});
                innerMap.add(new Object[]{"commandType", commandType});
                innerMap.add(new Object[]{"commandPwd", CloudCrypto.md5Hex(pin)});
                if (params != null && !params.isEmpty()) innerMap.add(new Object[]{"controlParamsMap", toSortedJson(params)});

                String innerJson = toSortedJson(innerMap);
                String encryData = CloudCrypto.aesEncryptHex(innerJson, CloudCrypto.md5Hex(encryToken));

                // 자바 HashMap dedup 동일 처리(키 중복 병합)
                LinkedHashMap<String, String> signDict = new LinkedHashMap<>();
                for (Object[] kv : innerMap) if (kv[1] instanceof String) signDict.put((String) kv[0], (String) kv[1]);
                signDict.put("countryCode", countryCode);
                signDict.put("identifier", userId);
                signDict.put("imeiMD5", nn(dp.get("imeiMD5")));
                signDict.put("language", language);
                signDict.put("reqTimestamp", timeStamp);
                List<String[]> sp = new ArrayList<>();
                for (Map.Entry<String, String> e : signDict.entrySet()) sp.add(new String[]{e.getKey(), e.getValue()});
                Collections.sort(sp, (a, b) -> a[0].compareTo(b[0]));
                String signString = CloudCrypto.buildSignString(sp, CloudCrypto.md5Hex(signToken));
                String sign = CloudCrypto.sha1Mixed(signString);

                List<Object[]> envelopeMap = new ArrayList<>();
                envelopeMap.add(new Object[]{"countryCode", countryCode});
                envelopeMap.add(new Object[]{"encryData", encryData});
                envelopeMap.add(new Object[]{"identifier", userId});
                envelopeMap.add(new Object[]{"imeiMD5", nn(dp.get("imeiMD5"))});
                envelopeMap.add(new Object[]{"language", language});
                envelopeMap.add(new Object[]{"reqTimestamp", timeStamp});
                envelopeMap.add(new Object[]{"sign", sign});
                envelopeMap.add(new Object[]{"ostype", nn(dp.get("ostype"))});
                envelopeMap.add(new Object[]{"imei", nn(dp.get("imei"))});
                envelopeMap.add(new Object[]{"mac", nn(dp.get("mac"))});
                envelopeMap.add(new Object[]{"model", nn(dp.get("model"))});
                envelopeMap.add(new Object[]{"sdk", nn(dp.get("sdk"))});
                envelopeMap.add(new Object[]{"mod", nn(dp.get("mod"))});
                envelopeMap.add(new Object[]{"serviceTime", timeStamp});

                String checkcode = CloudCrypto.computeCheckcode(toSortedJson(envelopeMap));
                List<Object[]> finalMap = new ArrayList<>(envelopeMap);
                finalMap.add(new Object[]{"checkcode", checkcode});
                String encoded = codec.encodeEnvelope(toSortedJson(finalMap));

                JSONObject resp = post("/control/remoteControl", encoded);
                String code = resp.optString("code", "0");
                if (!code.equals("0")) {
                    if ((code.equals("1000") || code.equals("1008") || code.equals("6024")) && attempt < maxAttempts) {
                        Thread.sleep(attempt * 4000L);
                        continue;
                    }
                    return Result.fail(code + ": " + resp.optString("message", "Unknown"));
                }
                if (!poll) return Result.ok(null);

                JSONObject inner = decryptRespond(resp);
                if (inner != null) {
                    int state = intVal(inner.opt("controlState"));
                    int res = intVal(inner.opt("res"));
                    if (state == 1 || res == 2) return Result.ok(null);
                    String serial = inner.optString("requestSerial", "");
                    if (!serial.isEmpty()) return pollControlResult(vin, commandType, serial, pin);
                }
                return Result.ok(null);
            } catch (Exception e) {
                if (attempt < maxAttempts) { try { Thread.sleep(3000); } catch (InterruptedException ignored) {} continue; }
                return Result.fail(e.getMessage());
            }
        }
        return Result.fail("Rate limit retry exceeded");
    }

    private Result pollControlResult(String vin, String commandType, String requestSerial, String pin) {
        for (int i = 1; i <= 12; i++) {
            try {
                Thread.sleep(2000);
                String timeStamp = String.valueOf(System.currentTimeMillis());
                String random = rnd(16);
                List<Object[]> innerMap = new ArrayList<>();
                innerMap.add(new Object[]{"deviceType", nn(dp.get("deviceType"))});
                innerMap.add(new Object[]{"imeiMD5", nn(dp.get("imeiMD5"))});
                innerMap.add(new Object[]{"networkType", nn(dp.get("networkType"))});
                innerMap.add(new Object[]{"random", random});
                innerMap.add(new Object[]{"timeStamp", timeStamp});
                innerMap.add(new Object[]{"version", nn(dp.get("appInnerVersion"))});
                innerMap.add(new Object[]{"vin", vin});
                innerMap.add(new Object[]{"requestSerial", requestSerial});
                innerMap.add(new Object[]{"commandType", commandType});
                innerMap.add(new Object[]{"commandPwd", CloudCrypto.md5Hex(pin)});

                String innerJson = toSortedJson(innerMap);
                String encryData = CloudCrypto.aesEncryptHex(innerJson, CloudCrypto.md5Hex(encryToken));
                LinkedHashMap<String, String> signDict = new LinkedHashMap<>();
                for (Object[] kv : innerMap) if (kv[1] instanceof String) signDict.put((String) kv[0], (String) kv[1]);
                signDict.put("countryCode", countryCode);
                signDict.put("identifier", userId);
                signDict.put("imeiMD5", nn(dp.get("imeiMD5")));
                signDict.put("language", language);
                signDict.put("reqTimestamp", timeStamp);
                List<String[]> sp = new ArrayList<>();
                for (Map.Entry<String, String> e : signDict.entrySet()) sp.add(new String[]{e.getKey(), e.getValue()});
                Collections.sort(sp, (a, b) -> a[0].compareTo(b[0]));
                String sign = CloudCrypto.sha1Mixed(CloudCrypto.buildSignString(sp, CloudCrypto.md5Hex(signToken)));

                List<Object[]> envelopeMap = new ArrayList<>();
                envelopeMap.add(new Object[]{"countryCode", countryCode});
                envelopeMap.add(new Object[]{"encryData", encryData});
                envelopeMap.add(new Object[]{"identifier", userId});
                envelopeMap.add(new Object[]{"imeiMD5", nn(dp.get("imeiMD5"))});
                envelopeMap.add(new Object[]{"language", language});
                envelopeMap.add(new Object[]{"reqTimestamp", timeStamp});
                envelopeMap.add(new Object[]{"sign", sign});
                envelopeMap.add(new Object[]{"ostype", nn(dp.get("ostype"))});
                envelopeMap.add(new Object[]{"imei", nn(dp.get("imei"))});
                envelopeMap.add(new Object[]{"mac", nn(dp.get("mac"))});
                envelopeMap.add(new Object[]{"model", nn(dp.get("model"))});
                envelopeMap.add(new Object[]{"sdk", nn(dp.get("sdk"))});
                envelopeMap.add(new Object[]{"mod", nn(dp.get("mod"))});
                envelopeMap.add(new Object[]{"serviceTime", timeStamp});
                String checkcode = CloudCrypto.computeCheckcode(toSortedJson(envelopeMap));
                List<Object[]> finalMap = new ArrayList<>(envelopeMap);
                finalMap.add(new Object[]{"checkcode", checkcode});
                String encoded = codec.encodeEnvelope(toSortedJson(finalMap));

                JSONObject resp = post("/control/remoteControlResult", encoded);
                if (!resp.optString("code", "0").equals("0")) continue;
                JSONObject inner = decryptRespond(resp);
                if (inner == null) inner = resp;
                int state = intVal(inner.opt("controlState"));
                int res = intVal(inner.opt("res"));
                if (state == 1 || res == 2) return Result.ok(null);
                if (state == 2 || res > 2) return Result.fail("Control failed: " + inner.optString("message", "Failed"));
            } catch (Exception e) { /* retry */ }
        }
        return Result.fail("Polling timeout");
    }

    public Result flashLights(String vin, String pin) {
        return sendCommand(vin, "FLASHLIGHTNOWHISTLE", null, pin, true);
    }

    // ★공조 켜기(OPENAIR) — iOS startClimate 이식. 애프터블로우 시퀀스에서 릴레이가 직접 시동ON.
    //   windLevel==null → 풍량 자동(airConditioningMode=1), 아니면 풍량 지정(=2).
    public Result openAir(String vin, double celsius, int minutes, int cycleMode, Integer windLevel, String pin) {
        int scale = celsiusToScale(celsius);
        List<Object[]> p = new ArrayList<>();
        p.add(new Object[]{"mainSettingTemp", scale});
        p.add(new Object[]{"copilotSettingTemp", scale});
        p.add(new Object[]{"cycleMode", cycleMode});
        p.add(new Object[]{"timeSpan", minutesToTimeSpan(minutes)});
        p.add(new Object[]{"remoteMode", 4});
        p.add(new Object[]{"airAccuracy", 2});
        if (windLevel != null) { p.add(new Object[]{"airConditioningMode", 2}); p.add(new Object[]{"windLevel", windLevel}); }
        else p.add(new Object[]{"airConditioningMode", 1});
        p.add(new Object[]{"airSet", null});
        return sendCommand(vin, "OPENAIR", p, pin, true);
    }

    // BYD 온도 스케일: clamp(17~27℃) → round((℃-16.5)×2) = 1~21. (iOS/안드로 확정 동일)
    private int celsiusToScale(double celsius) {
        double c = Math.max(17.0, Math.min(27.0, celsius));
        return Math.max(1, Math.min(21, (int) Math.round((c - 16.5) * 2.0)));
    }

    // 분→timeSpan: 10/15/20/25/30 → 1/2/3/4/5, 그 외 1.
    private int minutesToTimeSpan(int minutes) {
        switch (minutes) { case 10: return 1; case 15: return 2; case 20: return 3; case 25: return 4; case 30: return 5; default: return 1; }
    }

    private JSONObject decryptRespond(JSONObject resp) {
        try {
            String rd = resp.optString("respondData", "");
            if (rd.isEmpty()) return null;
            String plain = CloudCrypto.aesDecryptUtf8(rd, CloudCrypto.md5Hex(encryToken));
            return new JSONObject(plain);
        } catch (Exception e) { return null; }
    }

    private int intVal(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) { try { return Integer.parseInt((String) v); } catch (Exception e) { return 0; } }
        return 0;
    }

    private JSONObject post(String path, String encoded) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        con.setRequestProperty("User-Agent", "okhttp/4.12.0");
        con.setRequestProperty("Accept-Encoding", "identity");
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setDoOutput(true);
        String payload = new JSONObject().put("request", encoded).toString();
        OutputStream os = con.getOutputStream();
        os.write(payload.getBytes("UTF-8"));
        os.close();
        int status = con.getResponseCode();
        InputStream is = (status == 200) ? con.getInputStream() : con.getErrorStream();
        String body = readAll(is);
        con.disconnect();
        if (status != 200) throw new Exception("HTTP " + status + " " + body.substring(0, Math.min(200, body.length())));
        JSONObject json = new JSONObject(body);
        String responseStr = json.optString("response", "");
        if (responseStr.isEmpty()) throw new Exception("No response field");
        String decoded = codec.decodeEnvelope(responseStr).trim();
        if (decoded.startsWith("F{") || decoded.startsWith("F[")) decoded = decoded.substring(1);
        return new JSONObject(decoded);
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String nn(String s) { return s == null ? "" : s; }
}
