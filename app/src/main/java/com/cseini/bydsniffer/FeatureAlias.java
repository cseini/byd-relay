package com.cseini.bydsniffer;

import java.util.HashMap;
import java.util.Map;

// Overdrive 패턴① 이식(overdrive-porting-plan.md Phase 1) — 차종별 이벤트 featureName 정규화.
//
// 배경: SnifferService.idToName 은 각 차량의 android.hardware.bydauto.BYDAutoFeatureIds 를
//   리플렉션으로 읽어 featureId(int)→필드명을 만든다(이미 동적 해석). 따라서 돌핀(DiLink3)에선
//   RelayClient.updateFromEvent 의 정규 switch 이름(INSTRUMENT_DD_*_DOOR_STATE 등)이 그대로 매칭된다.
//   DiLink5(씨라이언7)에선 같은 의미의 필드명이 달라 매칭에 실패할 수 있다.
//
// 역할: DiLink5 필드명 → 돌핀 정규명으로 정규화해 updateFromEvent 의 기존 switch 에 흡수시킨다.
//   테이블에 없는 이름은 그대로 통과 = 돌핀 무회귀.
//
// ⚠️ DiLink5 실제 필드명은 씨라이언7 실차 이벤트 덤프(DILINK_EVT, D1 로그)로 확정 후 아래 표에 채운다.
//   현재는 실측 데이터가 없어 비어 있음(스캐폴딩). 검증 안 된 매핑은 넣지 않는다.
public final class FeatureAlias {
    private FeatureAlias() {}

    // key = DiLink5 필드명(대문자), value = 돌핀 정규명(updateFromEvent 가 매칭하는 이름).
    private static final Map<String, String> ALIAS = new HashMap<>();
    static {
        // 예) ALIAS.put("D5_LEFT_FRONT_DOOR_OPEN", "INSTRUMENT_DD_LEFT_FRONT_DOOR_STATE");
        // 씨라이언7 DILINK_EVT 덤프로 확정 후 추가.
    }

    // 원 featureName 을 돌핀 정규명으로 정규화. 매핑 없으면 원본 그대로 반환.
    public static String normalize(String rawName) {
        if (rawName == null) return "";
        String u = rawName.toUpperCase();
        String canon = ALIAS.get(u);
        return canon != null ? canon : rawName;
    }
}
