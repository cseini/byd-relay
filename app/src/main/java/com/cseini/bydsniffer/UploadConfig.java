package com.cseini.bydsniffer;

public class UploadConfig {
    // 개인용 시크릿은 소스에 두지 않는다 — 빌드할 때 ~/.gradle/gradle.properties의 BYD_UPLOAD_SECRET에서 주입(공개 저장소 준비, 10-08)
    public static final String URL = "https://byd-sniffer.cseini.workers.dev/u";
    public static final String SECRET = BuildConfig.UPLOAD_SECRET;
}
