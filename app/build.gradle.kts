plugins {
    id("com.android.application")
}

// ★비밀값은 소스에 두지 않는다(공개 저장소 준비, 운영자 10-08 "공개로 가자") — 빌드 때 ~/.gradle/gradle.properties(또는 환경변수)의 값을 BuildConfig로 주입한다.
//   값이 없으면 앱을 만드는 작업(assemble·bundle)이 일부러 실패한다(빈 값으로 만든 앱은 서버와 통신 못 함). 컴파일·단위 시험은 빈 값으로도 된다.
val relaySecret: String = (project.findProperty("BYD_RELAY_SECRET") as String?) ?: System.getenv("BYD_RELAY_SECRET") ?: ""
val uploadSecret: String = (project.findProperty("BYD_UPLOAD_SECRET") as String?) ?: System.getenv("BYD_UPLOAD_SECRET") ?: ""
// ★씨라7(DiLink5) 센트리 시험판 스위치(운영자 10-10 "베타의 베타 테스터") — -PD5_SENTRY=true로 만든 시험판 빌드에서만 씨라7에서도 센트리 코드가 살아난다. 기본(일반 배포)은 꺼짐.
val d5Sentry: Boolean = (project.findProperty("D5_SENTRY") as String?) == "true" || System.getenv("D5_SENTRY") == "true"

android {
    namespace = "com.cseini.bydsniffer"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    ndkVersion = "26.3.11579264"   // ★모션감지 native(libmotiondetect.so) CMake 빌드용

    defaultConfig {
        applicationId = "com.cseini.bydsniffer"
        minSdk = 25
        targetSdk = 25   // ★일렉트로 동일 — 레거시 타깃으로 API 26+ 백그라운드 제한(서비스 강제종료·Doze 네트워크 차단) 회피
        versionCode = 880
        versionName = "880.0"
        buildConfigField("String", "RELAY_SECRET", "\"" + relaySecret + "\"")
        buildConfigField("String", "UPLOAD_SECRET", "\"" + uploadSecret + "\"")
        buildConfigField("boolean", "D5_SENTRY", d5Sentry.toString())

        // ★WebRTC(libwebrtc)는 arm64-v8a만 번들 — 헤드유닛은 arm64(/system/lib64). APK 4ABI×20MB → 단일ABI로 축소.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // ★모션감지 모듈 — byd-motiontest와 단일 소스 공유(상대경로 참조, 복사 아님).
    externalNativeBuild {
        cmake {
            path = file("../motiondetect/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets {
        getByName("main") {
            java.srcDir("../motiondetect/java")
        }
    }

    // ★킥 트렁크(사람게이트+포즈) TFLite 모델 무압축 — Interpreter가 mmap 로드(압축시 로드 실패).
    androidResources {
        noCompress += "tflite"
        noCompress += "fast_cam_capture"   // ★DiLink5 QCarCam ELF — 압축 시 추출 바이너리 손상 방지(바이트 정확)
    }

    buildFeatures {
        buildConfig = true   // ★flavor별 IS_OPERATOR·OTA_URL 상수 주입
    }

    // ★공유 대비 이원화 — 한 소스, 두 산출물. operator=내 개발판(셸·진단·self-update 포함),
    //   shared=재능기부 배포판(위험 명령 컴파일 게이트로 차단, 별도 OTA 채널). 포크 아님.
    flavorDimensions += "audience"
    productFlavors {
        create("operator") {
            dimension = "audience"
            buildConfigField("boolean", "IS_OPERATOR", "true")
            buildConfigField("String", "OTA_URL", "\"https://byd-ota.pages.dev/version.json\"")
        }
        create("shared") {
            dimension = "audience"
            versionNameSuffix = "-share"
            buildConfigField("boolean", "IS_OPERATOR", "false")
            buildConfigField("String", "OTA_URL", "\"https://byd-relay-share.pages.dev/version.json\"")
        }
    }

    lint {
        // targetSdk 25는 의도적(일렉트로 동일 — 백그라운드 제한 회피). 스토어 배포 안 하므로 Play 정책 린트 무시.
        disable += "ExpiredTargetSdkVersion"
    }

    // ★릴리스도 debug.keystore로 자동 서명 — apksigner 수동 단계 제거(OTA 사이드로드 전용, 스토어 미배포).
    signingConfigs {
        create("relaySign") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("relaySign")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md"
            )
        }
        // ★DiLink5 QCarCam — fast_cam_capture 실행파일을 libfast_cam_capture.so로 번들해 nativeLibraryDir에서 exec.
        //   AGP 8 기본(useLegacyPackaging=false → extractNativeLibs=false)은 .so를 APK에서 mmap만 하고 파일로 추출 안 해
        //   isFile()=false로 실행 불가(2026-09-10 089612 릴레이 414 실차 "없음/실행불가"). 레거시 패키징으로 설치 시 추출 강제.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    // ★2026-09-17 BYD SDK 스텁은 컴파일에만 쓰고 APK엔 넣지 않는다 — APK에 들어가면 DiLink5에서 OEM 실클래스를
    //   가려(shadow) adas/light/setting/bodywork/ac 도메인 구독이 전멸한다(씨라7 4대 reg_domains 전부 "!"). D3는 부트클래스패스가 실클래스 제공.
    compileOnly(project(":bydsdk-stubs"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.1")
    // ★v55 app_process 원격끔 면역 — dadb로 로컬 adbd(127.0.0.1:5555) RSA 인증 → shell 권한.
    implementation("dev.mobile:dadb:1.2.10")
    implementation("org.nanohttpd:nanohttpd:2.3.1")   // ★웹 세컨드스크린(2026-09-11) — 핫스팟/동일 WiFi의 폰·태블릿 브라우저용 로컬 HTTP(노래방과 동일 라이브러리)
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")   // ★가상 디스플레이 미러(2026-09-11) — 웹소켓으로 H.264 스트림 down·터치 up
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")   // ★씨라이언7(DiLink5) Bodywork abstract 리스너 런타임 서브클래싱(오토전비와 동일)
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    // ★WebRTC P2P 카메라(CAMRTC) — libwebrtc 프리빌드(org.webrtc). minSdk 21, arm64 포함.
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")   // ★킥 트렁크 — EfficientDet-Lite0(사람) + MoveNet(발목포즈) 온디바이스 추론
    implementation("com.google.zxing:core:3.5.3")   // ★릴레이 등록 QR 생성(devId+페어코드) — 폰 앱이 스캔해 자동 등록
    // ★2026-09-19 씨라7(D5) 이벤트 hidden API 우회 — BeetleLauncher 1.70이 이걸로 D5 도어·ADAS·차선 다 받음(해부 확인).
    //   우리 setHiddenApiExemptions("L")은 D5(SDK30+)서 안 먹혀 bydauto registerListener 실구현이 안 보여 스텁 폴백이던 것.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // ★센트리 JUnit — 안드로이드 org.json 스텁 대신 실제 구현(테스트 전용)
}

// ★헬퍼 설치 파일 확인(10-05 FB2011) — assets/byd-helper.apk가 git 제외(*.apk)라 작업 트리 빌드(공유판 703~737·750·799~802)에서 조용히 빠져
//   헬퍼 재설치가 FileNotFoundException으로 실패했다. 이제 저장소에 넣고(.gitignore 예외), 없으면 빌드를 멈춘다.
//   헬퍼를 바꾸면 bydhelper 릴리스 APK로 이 파일을 교체하고 SnifferService.HELPER_ASSET_VER를 그 versionCode로 맞출 것.
val checkHelperAsset by tasks.registering {
    val f = file("src/main/assets/byd-helper.apk")
    doLast {
        // 공개 저장소(10-08)에는 헬퍼 APK가 없다(비밀값 포함·별도 앱) — 직접 만드는 사람은 -PBYD_ALLOW_NO_HELPER=true
        if ((!f.isFile || f.length() < 10_000) && project.findProperty("BYD_ALLOW_NO_HELPER") != "true") throw GradleException("헬퍼 설치 파일 없음: ${f.path}")
    }
}
tasks.named("preBuild") { dependsOn(checkHelperAsset) }

// 앱을 만드는 작업이 그래프에 있으면 시작하자마자(컴파일 전에) 값이 없는 경우를 막는다
gradle.taskGraph.whenReady {
    val building = allTasks.any { it.project == project && (it.name.startsWith("assemble") || it.name.startsWith("bundle")) }
    if (building && (relaySecret.isEmpty() || uploadSecret.isEmpty()) && project.findProperty("BYD_ALLOW_EMPTY_SECRET") != "true") {
        throw GradleException("BYD_RELAY_SECRET·BYD_UPLOAD_SECRET이 없습니다 — ~/.gradle/gradle.properties에 넣거나 -PBYD_ALLOW_EMPTY_SECRET=true(통신 안 되는 시험용)로 빌드하세요")
    }
}
