package com.cseini.bydsniffer.sentry;

// AGPL-3.0 §13 — 이 앱이 헤드유닛에서 띄우는 웹 화면(센트리 재생·달력·재생 시험·BSD 비교)을 쓰는 사람에게 소스 저장소 주소를 알린다(공개 저장소 10-10).
final class SentrySource {
    private SentrySource() {}

    static final String URL = "https://github.com/cseini/byd-relay";
    static final String NOTE = "<p style='margin:18px 0 6px;font-size:12px;opacity:.65'>이 앱은 오픈소스(AGPL-3.0)입니다 · 소스 코드: <a href='" + URL + "' style='color:inherit'>" + URL + "</a></p>";
}
