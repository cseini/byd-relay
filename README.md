# BYD 릴레이 앱 (링크락 헤드유닛 릴레이)

> 이 갈래(share-d3)는 **공유판 d3(돌핀·아토3) 릴레이**의 소스입니다. 배포한 각 번호는 share-NNN 태그로 남깁니다. 운영자판·씨라7 시험판은 main 갈래입니다.


BYD 디링크3 헤드유닛에서 도는 안드로이드 앱 소스입니다. 센트리(주차 중 움직임 녹화)·카메라·차량 제어 중계를 담당합니다.

- 라이선스: **GNU AGPL-3.0** (`LICENSE`). 센트리의 사람·차량 거르기가 YOLO11(AGPL-3.0)을 쓰기 때문에 앱 전체를 같은 조건으로 공개합니다. 제3자 구성요소는 `THIRD_PARTY_NOTICES.md`.
- 빌드: `~/.gradle/gradle.properties`에 `BYD_RELAY_SECRET`·`BYD_UPLOAD_SECRET`(서버와 맞춘 값, 이 저장소엔 없음)을 넣고 `./gradlew :app:assembleOperatorRelease` 등. 값 없이 시험으로만 만들려면 `-PBYD_ALLOW_EMPTY_SECRET=true`(서버와 통신 안 됨), 헬퍼 앱이 없으면 `-PBYD_ALLOW_NO_HELPER=true`.
- **저장소에 없는 것**: 제3자 소유이거나 비밀값이 들어 있어 뺀 파일 — `byd-helper.apk`(별도 앱), `bangcle_tables.bin`·`beetle-sdk-listener.dex`(BYD 앱에서 추출한 자료), 카메라 네이티브 라이브러리(`libdilink5_camera.so`·`libfast_cam_*.so`), `libsnif_release_guard.so`(이 저장소 `native/`에서 빌드). 해당 기능은 파일이 없으면 동작하지 않는다.
- 서버·아이폰 앱은 별도 프로그램이라 이 저장소에 없습니다. 센트리 앱 연동 규격은 `docs/SENTRY-IOS-CONTRACT.md`.
- 배포한 각 버전의 소스는 같은 번호의 태그로 남깁니다.
- **소스 제공(AGPL §13)**: 이 앱이 헤드유닛에서 띄우는 웹 화면(센트리 재생·원격 화면)을 쓰는 사람에게도 이 저장소 주소를 안내합니다.

## 제3자 코드·구성요소
- `app/src/main/java/com/cseini/bydsniffer/cloud/` — BYD 클라우드 클라이언트는 커뮤니티 연구 [pyBYD](https://github.com/jkaberg/pyBYD)(MIT, jkaberg 외), [BYD-re](https://github.com/Niek/BYD-re)(Niek), [ioBroker.byd](https://github.com/TA2k/ioBroker.byd)(TA2k)를 바탕으로 한 포팅입니다. 원 저작권 고지는 각 저장소의 LICENSE를 따릅니다. 암호 표 파일(`bangcle_tables.bin`)은 포함하지 않았습니다.
- YOLO11n(Ultralytics, AGPL-3.0)·EfficientDet-Lite0·MoveNet(Apache-2.0, Google)·TensorFlow Lite(Apache-2.0)·libwebrtc(BSD)·NanoHTTPD(BSD)·dadb(Apache-2.0)·scrcpy-server(Apache-2.0)·jMuxer(MIT).
