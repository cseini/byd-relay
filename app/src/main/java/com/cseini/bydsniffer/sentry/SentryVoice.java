package com.cseini.bydsniffer.sentry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// 센트리 가동 요약 안내 — 헤드유닛(돌핀)에는 TTS 엔진이 없어서(운영자 10-10) 미리 녹음한 조각(assets/sentry_voice/<id>.wav)을 이어 읽는다.
//   "주차 중 센트리 녹화 열두 건이 있습니다."  (운영자 "시각 필요 없이 — 주차 중 센트리 녹화 몇 건이 있습니다로만")
// 건수는 고유어(열두)라 숫자 대신 한글로 만든 조각을 쓰고, 앱이 커지지 않게 십의 자리와 일의 자리를 조합한다(약 30조각). 순수 계산이라 JVM에서 시험한다.
// 조각 파일은 tools/gen_sentry_voice.py가 만든다(id가 같아야 한다 — 시험이 파일 존재를 확인한다).
// 재생은 릴레이 앱이 한다: 각 조각 WAV(16kHz·모노·16bit)의 PCM을 순서대로 이어 하나의 AudioTrack으로 읽는다. 화면 문구는 line().
public final class SentryVoice {
    private SentryVoice() {}

    public static final String ASSET_DIR = "sentry_voice";   // assets 폴더 이름 — 파일은 <id>.wav

    // 읽을 조각 id 순서: intro, 건수 조각(아래), outro. 녹화가 1건 미만이면 빈 목록(읽지 않는다)
    //   1~9 → u{n}("한 건이") · 10·20…90 → t{n}("열 건이"·"스무 건이") · 그 밖 → p{십의 자리}("스물") + u{일의 자리}("한 건이") · 100건 이상 → c_over
    public static List<String> clipIds(int count) {
        List<String> out = new ArrayList<>();
        if (count < 1) return out;
        out.add("intro");
        if (count >= 100) out.add("c_over");
        else if (count < 10) out.add("u" + count);
        else if (count % 10 == 0) out.add("t" + count);
        else { out.add("p" + (count - count % 10)); out.add("u" + count % 10); }
        out.add("outro");
        return out;
    }

    // 화면 문구 한 줄 — "주차 중 센트리 녹화 12건이 있습니다" (100건 이상은 "100건 이상이")
    public static String line(int count) {
        return "주차 중 센트리 녹화 " + (count >= 100 ? "100건 이상이" : count + "건이") + " 있습니다";
    }

    // 모든 조각 id(생성기·시험용)
    public static List<String> allClipIds() {
        List<String> all = new ArrayList<>();
        all.add("intro");
        for (int o = 1; o <= 9; o++) all.add("u" + o);
        for (int t = 10; t <= 90; t += 10) { all.add("t" + t); all.add("p" + t); }
        all.add("c_over");
        all.add("outro");
        return all;
    }
}
