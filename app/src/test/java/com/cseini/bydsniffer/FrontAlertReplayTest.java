package com.cseini.bydsniffer;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * docs/front-alert-episodes.jsonl(운영자가 D1 로그에서 직접 생성한 재생용 데이터셋, 2026-09-11)로
 * FrontAlertTracker를 재생한다. 파일이 없으면(다른 머신·CI) Assume으로 스킵 — 실패시키지 않는다.
 *
 * 스키마(운영자 확정, 31 에피소드):
 *   {"id","start_kst","end_kst","duration_s",
 *    "label":{"ego_departed_at_kst":".."|null,"ego_depart_after_end_s":F|null},
 *    "ticks":[
 *      {"kind":"lead","ts":epochMs,"armed":B,"armed_ms":N|null,"baseCy":F,"cy":F|null,
 *       "baseH":F,"h":F|null,"baseCx":F|null,"cx":F|null,"recedeN":N,"mot":N,"spd":F|null,"gear":N|null},
 *      {"kind":"light","ts":epochMs,"tile":N,"lt":F|null,"asp":F,"r":F,"g":F,"redHist":B,"gBase":F|null,"mode":".."},
 *      {"kind":"disarm"|"swap", ...}   // 원 알고리즘의 상태전이 로그 — Tick으로 변환하지 않고 건너뜀
 *    ],
 *    "actual_alerts":[{"ts":..,"type":"lead"|"light","detail":".."}]}   // ★"정답"이 아니라 옛 로직이 실제로 쏜 발화
 *
 * ego_departed_at_kst = 운영자 차가 그 정차에서 출발한 시각(앞차가 몇 초 전 출발했다는 근사 정답).
 * cx는 현재 데이터셋에 전부 null(릴레이 440부터 로깅 시작) — FrontAlertTracker.Box.cx=NaN으로 넘겨
 * cx 게이트를 비활성화한다(withinOrUnknown, cy·h만으로 매칭).
 *
 * 채점은 참고용이며 실패 기준이 없다 — 표로만 출력한다. actual_alerts는 옛 로직의 발화이지 정답이
 * 아니므로, 새 추적기가 이를 그대로 재현하는지가 아니라 **막아야 할 오발화(모순·노이즈·중복 사라짐)를
 * 실제로 막는지**를 episode별로 사람이 detail과 함께 읽고 판단하도록 표에 옛 발화까지 같이 낸다.
 */
public class FrontAlertReplayTest {

    private static final String FILE_PATH =
            "/Users/sen/project/byd/byd-autolock-ios/docs/front-alert-episodes.jsonl";

    @Test
    public void replayEpisodes() throws IOException {
        Path p = Paths.get(FILE_PATH);
        Assume.assumeTrue("episodes 파일 없음 — 스킵: " + FILE_PATH, Files.exists(p));

        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        int total = 0, oldFiredCount = 0, newFiredCount = 0;
        StringBuilder table = new StringBuilder();
        table.append(String.format("%-6s %-8s %-8s %-10s %s%n",
                "id", "옛발화", "새발화", "ego갭(s)", "옛detail"));

        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            total++;

            @SuppressWarnings("unchecked")
            Map<String, Object> ep = (Map<String, Object>) MiniJson.parse(t);
            String id = str(ep, "id");
            @SuppressWarnings("unchecked")
            Map<String, Object> label = (Map<String, Object>) ep.get("label");
            @SuppressWarnings("unchecked")
            List<Object> ticksList = (List<Object>) ep.get("ticks");
            @SuppressWarnings("unchecked")
            List<Object> oldAlertsList = (List<Object>) ep.get("actual_alerts");

            String egoDepartKst = label != null ? str(label, "ego_departed_at_kst") : null;
            Long egoDepartMs = egoDepartKst != null ? parseKstLoose(egoDepartKst) : null;

            List<String> newEvents = new ArrayList<>();   // "LEAD_DEPART@t" 등
            FrontAlertTracker tracker = new FrontAlertTracker(new FrontAlertTracker.Config());
            if (ticksList != null) {
                for (Object o : ticksList) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tk = (Map<String, Object>) o;
                    String kind = str(tk, "kind");
                    FrontAlertTracker.Tick tick = "lead".equals(kind) ? toLeadTick(tk)
                            : "light".equals(kind) ? toLightTick(tk) : null;
                    if (tick == null) continue;   // disarm/swap — 원 알고리즘 상태전이 로그, 재생 입력 아님
                    FrontAlertTracker.Result r = tracker.onTick(tick);
                    if (r.event != FrontAlertTracker.Event.NONE) newEvents.add(r.event + "@" + tick.nowMs);
                }
            }

            String oldSummary = "";
            if (oldAlertsList != null) {
                for (Object o : oldAlertsList) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> a = (Map<String, Object>) o;
                    if (!oldSummary.isEmpty()) oldSummary += " | ";
                    oldSummary += str(a, "detail");
                }
            }
            boolean oldFired = oldAlertsList != null && !oldAlertsList.isEmpty();
            boolean newFired = !newEvents.isEmpty();
            if (oldFired) oldFiredCount++;
            if (newFired) newFiredCount++;

            // ★ego갭 = (새 발화 시각 - ego 출발 시각), 초 — 이 데이터셋은 "몇 초 전"이 아니라 대개 수십~백여 초
            //   전(운영자가 바로 안 움직이는 실제 교차로 거동)이라 고정 시간창으로 적중/오답을 가르지 않는다.
            //   음수(발화가 ego 출발보다 먼저)가 방향상 맞고, 값이 클수록(수십초+) 이른 경보 — 정성 판단용 참고치.
            String gapStr = "-";
            if (egoDepartMs != null && newFired) {
                StringBuilder gaps = new StringBuilder();
                for (String e : newEvents) {
                    long ts = Long.parseLong(e.substring(e.indexOf('@') + 1));
                    if (gaps.length() > 0) gaps.append(",");
                    gaps.append(String.format(java.util.Locale.US, "%.1f", (ts - egoDepartMs) / 1000.0));
                }
                gapStr = gaps.toString();
            }

            table.append(String.format("%-6s %-8s %-8s %-10s %s%n",
                    id, oldFired ? "O" : "-", newFired ? String.join(",", newEvents) : "-", gapStr, oldSummary));
        }

        System.out.println("=== FrontAlertReplayTest ===");
        System.out.println("총 에피소드=" + total + " 옛발화 에피소드=" + oldFiredCount + " 새발화 에피소드=" + newFiredCount);
        System.out.println(table);
        // 측정이 목적 — 채점 결과로 테스트를 실패시키지 않는다. 사람이 detail·ego갭과 함께 표를 읽고 판단.
    }

    private static FrontAlertTracker.Tick toLeadTick(Map<String, Object> tk) {
        FrontAlertTracker.Tick t = new FrontAlertTracker.Tick();
        Double ts = num(tk, "ts"); t.nowMs = ts != null ? ts.longValue() : 0L;
        Double spd = num(tk, "spd"), gear = num(tk, "gear");
        // 진단 로그는 정차 중에만 찍혔다 — spd/gear가 없으면(대부분) 정차로 간주.
        t.stopped = (spd == null || gear == null) || (spd <= 3 && gear.intValue() == 2);
        Double cy = num(tk, "cy"), h = num(tk, "h"), cx = num(tk, "cx");
        Double mot = num(tk, "mot");
        t.motPct = mot != null ? mot.intValue() : 0;
        if (cy != null) {
            // ★cx는 이 데이터셋에 전부 null — Box.cx=NaN으로 넘겨 cx 게이트를 비활성화(cy·h만으로 매칭).
            t.vehicles = new FrontAlertTracker.Box[]{
                    new FrontAlertTracker.Box(0.6f, cx != null ? cx.floatValue() : Float.NaN, cy.floatValue(), 0f,
                            h != null ? h.floatValue() : 0f)};
        } else {
            t.vehicles = new FrontAlertTracker.Box[0];   // 미검출
        }
        return t;
    }

    private static FrontAlertTracker.Tick toLightTick(Map<String, Object> tk) {
        FrontAlertTracker.Tick t = new FrontAlertTracker.Tick();
        Double ts = num(tk, "ts"); t.nowMs = ts != null ? ts.longValue() : 0L;
        t.stopped = true;   // light 진단도 정차 중에만 찍힘
        Double lt = num(tk, "lt"), asp = num(tk, "asp"), r = num(tk, "r"), g = num(tk, "g");
        if (lt != null) t.light = new FrontAlertTracker.Box(lt.floatValue(), 0.5f, 0.5f, 0f, 0f);
        t.lightAsp = asp != null ? asp.floatValue() : 0f;
        t.r = r != null ? r.floatValue() : 0f;
        t.g = g != null ? g.floatValue() : 0f;
        return t;
    }

    // "2026-09-10 21:22:40.069" — 밀리초까지 있을 수 있으니 초 단위까지만(19자) 잘라 KST로 파싱.
    private static Long parseKstLoose(String s) {
        if (s == null || s.length() < 19) return null;
        try {
            LocalDateTime ldt = LocalDateTime.parse(s.substring(0, 19).replace(' ', 'T'));
            return ldt.toInstant(ZoneOffset.ofHours(9)).toEpochMilli();
        } catch (Exception e) { return null; }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof String ? (String) v : null;
    }

    private static Double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Double ? (Double) v : null;
    }

    // ── 최소 JSON 파서(테스트 전용) — org.json은 Android 유닛테스트에서 미지원(스텁 예외)이라 자체 구현 ──
    private static final class MiniJson {
        static Object parse(String s) { return new P(s).parseValue(); }

        private static final class P {
            final String s; int i = 0;
            P(String s) { this.s = s; }
            void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
            Object parseValue() {
                ws();
                char c = s.charAt(i);
                if (c == '{') return parseObj();
                if (c == '[') return parseArr();
                if (c == '"') return parseStr();
                if (c == 't') { i += 4; return Boolean.TRUE; }
                if (c == 'f') { i += 5; return Boolean.FALSE; }
                if (c == 'n') { i += 4; return null; }
                return parseNum();
            }
            Map<String, Object> parseObj() {
                Map<String, Object> m = new LinkedHashMap<>();
                i++; ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String key = parseStr();
                    ws(); i++; // ':'
                    Object val = parseValue();
                    m.put(key, val);
                    ws();
                    char c = s.charAt(i);
                    if (c == ',') { i++; continue; }
                    i++; // '}'
                    break;
                }
                return m;
            }
            List<Object> parseArr() {
                List<Object> l = new ArrayList<>();
                i++; ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(parseValue());
                    ws();
                    char c = s.charAt(i);
                    if (c == ',') { i++; continue; }
                    i++; // ']'
                    break;
                }
                return l;
            }
            String parseStr() {
                i++; // 여는 따옴표
                StringBuilder sb = new StringBuilder();
                while (s.charAt(i) != '"') {
                    char c = s.charAt(i);
                    if (c == '\\') {
                        i++;
                        char e = s.charAt(i);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'n': sb.append('\n'); break;
                            case 't': sb.append('\t'); break;
                            case 'r': sb.append('\r'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'u': sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; break;
                            default: sb.append(e);
                        }
                        i++;
                    } else { sb.append(c); i++; }
                }
                i++; // 닫는 따옴표
                return sb.toString();
            }
            Double parseNum() {
                int start = i;
                while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
                return Double.parseDouble(s.substring(start, i));
            }
        }
    }
}
