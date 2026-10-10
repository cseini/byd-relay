// ★2026-09-17 — BYD SDK(android.hardware.bydauto.*) 스텁 전용 모듈.
//   app에 compileOnly로만 들어간다(APK 미포함). 예전엔 app/src/main/java/android/hardware 아래에 있어서 APK dex에 박혔는데,
//   DiLink5(씨라이언7)는 bydauto가 부트클래스패스에 없어 DiLink5Support가 OEM dex를 주입해 쓰는 구조라, APK 안의 스텁이
//   OEM 실클래스를 가려(shadow) adas/light/setting/bodywork/ac 등 모든 도메인 구독이 전멸했다(테스터 4대 reg_domains 전부 '!').
//   스텁이 없는 statistic 도메인만 실값이 나오던 것이 결정적 증거. D3(돌핀)는 부트클래스패스가 parent-first로 실클래스를 주므로 무영향.
//   android.os(IBinder/Parcel 등)를 참조하므로 순수 java-library가 아니라 android-library여야 컴파일된다.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.cseini.bydsdkstubs"
    compileSdk = 34

    defaultConfig {
        minSdk = 25
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}
