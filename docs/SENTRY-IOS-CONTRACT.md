# 센트리 iOS 1차 — 구현 계약 (2026-10-05, 운영자 결정 "나·ㄱㄱ")

설계 정본 `SENTRY-DESIGN.md` 5절·3절·4절. 릴레이 응답 형식은 `SENTRY-IMPL-CONTRACT.md`(SentryHttp·remoteList/remoteThumb). 앱 디렉터리 `byd-autolock-ios/BYDAutoLock/`(worktree `/Users/sen/project/byd-sentry`, 브랜치 `sentry2`). SwiftUI, iOS 16 이상(프로젝트 설정 따름), **공용 파일은 아래 명시된 줄만**.

## 0. 원칙
- 새 파일 4개만: `SentryModels.swift`(impl-iOS-A), `SentryAPI.swift`(impl-iOS-A), `SentryViews.swift`(impl-iOS-B: SentryView·SentryStatusCard·SentrySettingsCard·SentryListView), `SentryPlayerView.swift`(impl-iOS-B). 공용 파일 훅은 마지막에 센트리 세션이 넣는다.
- **ContentView 타입 깊이 금지**: 진입은 `NavigationLink(destination: AnyView(SentryView(ctx)))` 1개. 센트리 화면 내부는 자기 파일 안의 struct로.
- 디자인: `Color.bydBg/bydSurface/bydLine/bydCyan/bydMuted/bydGreen/bydOrange/bydRed`, `.bydCard(pad:radius:)`(파일 범위 extension, 그대로 사용). 토글 행은 `extraRow`가 private이라 같은 모양의 `SentryRow`를 `SentryViews.swift`에 복제.
- 설정 저장: 릴레이가 정본. 폰은 `@AppStorage("byd_sentry_*")`에 마지막으로 본 값만 캐시(화면 즉시 반영용), 변경 시 `SENTRY_SET` 전송 후 응답의 `settings`로 덮어씀.
- 문구는 한글, 아이폰이 정본(안드 미러링). 알림 문구: "🛡 센트리 — 움직임 감지 (HH:mm)".

## 1. SentryModels.swift (impl-iOS-A)
```swift
struct SentrySettings: Codable, Equatable {   // 릴레이 toJson()의 snake_case 그대로 — CodingKeys로 매핑
    var enabled: Bool; var motionOnly: Bool; var storage: String /*internal|sd|usb*/; var limitMb: Int
    var resolution: String /*hd|fhd|original*/; var fps: String /*low|reduced|normal*/; var bitrateKbps: Int; var codec: String
    var notify: Bool; var prerollS: Int; var postS: Int; var pauseWhenPhoneNear: Bool
    var timeLimitEnabled: Bool; var timeMode: String; var timeFrom: String; var timeTo: String
    // 나머지 키(osd_*, sensitivity, min_detect_s, roi_enabled, rois, shadow_filter, light_change_filter, safe_location_enabled, hazard_on_motion, screen_message, mode, layout)는 1차 UI 없음 — 디코딩만(옵셔널), 재전송 안 함
    static let `default`: SentrySettings
    func patch(_ changes: [String: Any]) -> [String: Any]   // SENTRY_SET params.sentry 부분집합 생성(snake_case)
}
struct SentryVolume: Codable, Identifiable { var id: String; var kind: String; var label: String; var path: String; var mounted: Bool; var writable: Bool; var totalMb: Int; var freeMb: Int; var usedBySentryMb: Int }
struct SentryStorageInfo: Codable { var selected: String; var tier: String; var fallback: Bool; var root: String; var limitMb: Int; var volumes: [SentryVolume]; var estimateHours: [String: Double] }
struct SentryStatus: Codable {   // /sentry/status · SENTRY_STATUS data
    var state: String /*IDLE|ARMED|RECORDING|PAUSED*/; var why: String; var storageKind: String?; var tier: String?; var usedMb: Int?; var limitMb: Int?
    var events: Int?; var lastEvent: String?; var fallback: Bool?; var err: String?; var fps: Int?; var drop: Int?; var codecInUse: String?
    var settings: SentrySettings?; var storage: SentryStorageInfo?   // 상태 JSON의 "storage"는 객체(HTTP·SENTRY_STATUS 공통)
}
struct SentrySegment: Codable { var n: Int; var bytes: Int64; var durationS: Double }
struct SentryEvent: Codable, Identifiable {   // /sentry/list (LAN) 형식
    var id: String; var startMs: Int64; var durationS: Double; var segments: [SentrySegment]; var favorite: Bool; var thumb: String?
    var date: Date { Date(timeIntervalSince1970: Double(startMs)/1000) }
    static func fromRemote(_ r: SentryRemoteEvent) -> SentryEvent   // 원격 목록(조각 상세 없음)을 같은 모델로
}
struct SentryRemoteEvent: Codable { var id: String; var s: Int64; var d: Double; var n: Int; var b: Int; var f: Int }   // SENTRY_LIST data.events
struct SentryRemoteList: Codable { var date: String; var events: [SentryRemoteEvent]; var more: Bool }
enum SentryError: Error, LocalizedError { case noRelay, notReady(String), lanOnly, http(Int), decode, worker(String) }  // errorDescription 한글
```

## 2. SentryAPI.swift (impl-iOS-A) — `final class SentryAPI`(`@MainActor` 아님, async)
```swift
init(uid: String, vin: String)
func lanBase() async -> URL?            // RelayClient.shared.clusterURL() → scheme+host+port만 남긴 URL(http://host:port). nil이면 LAN 없음. 60초 캐시는 RelayClient가 함
func status() async throws -> SentryStatus   // LAN 있으면 GET /sentry/status, 없으면 commandData(action:"SENTRY_STATUS") data 파싱(둘 다 같은 JSON)
func storage() async throws -> SentryStorageInfo  // LAN: GET /sentry/storage, 없으면 status().storage
func set(_ patch: [String: Any]) async throws -> SentryStatus   // LAN: POST /sentry/settings(JSON, Content-Type: application/json 필수) → 상태 JSON; 없으면 commandData(action:"SENTRY_SET", params:["sentry": patch]) → data {"pending":true} 뒤 2초 후 status() 재조회해 반환
func list(date: String /*yyyy-MM-dd*/) async throws -> (events: [SentryEvent], remote: Bool, more: Bool)
        // LAN: GET /sentry/list?date=&fav=0 → events; 없으면 commandData("SENTRY_LIST", ["date": date]) → SentryRemoteList → fromRemote, remote=true
func thumb(id: String) async throws -> UIImage?   // LAN: GET /sentry/thumb?id= ; 없으면 commandData("SENTRY_THUMB", ["id": id]) → data.jpg base64. 메모리 캐시(NSCache, id 키)
func fileURL(id: String, seg: Int) async -> URL?  // LAN만: http://host:port/sentry/file?id=&seg=N (없으면 nil → 화면은 "차량 네트워크(핫스팟)에 연결하면 재생할 수 있어요")
func delete(id: String) async throws              // LAN POST /sentry/delete {id}; 없으면 SentryError.lanOnly
func setFavorite(id: String, fav: Bool) async throws  // LAN POST /sentry/fav; 없으면 lanOnly (1차 UI는 숨김이지만 API는 둠)
```
- `commandData`는 `RelayClient.shared.commandData(uid:vin:action:params:)` 그대로(워커 경로만, 결과 `data` 문자열). 호출 시 params 키는 `"sentry"`(SET) / `"date"` / `"id"`.
- LAN 요청은 `URLSession.shared`, timeout 8초, `Info.plist`의 `NSAllowsLocalNetworking`이 이미 있어 http 허용.
- 모든 에러는 `SentryError`로 감싸고 한글 설명.

## 3. SentryViews.swift (impl-iOS-B)
```swift
struct SentryContext { let uid: String; let vin: String; let api: SentryAPI }
struct SentryView: View { init(_ ctx: SentryContext) }   // NavigationView 안에서 쓰임(상위가 NavigationView). 세그먼트 2개: "녹화 영상" / "설정". .navigationTitle("센트리")
struct SentryStatusCard: View          // 상태 배지(IDLE 회색 "꺼짐"/ARMED 파랑 "감시 대기"/RECORDING 빨강 "녹화 중"/PAUSED 주황 "잠시 멈춤") + why + "저장: 내장(폴백)·SD·USB" + 사용량/한도 + fps·drop(작게) + 새로고침. fallback이면 경고 줄 "선택한 저장소에 쓸 수 없어 내부에 저장 중"
struct SentrySettingsCard: View        // 켜기 토글(enabled) / "움직임 있을 때만 녹화"(motionOnly) / 저장소 Picker(내장·microSD·USB — 각 줄에 "총 X GB · 남음 Y GB", 미마운트는 비활성+"연결 안 됨") / 저장 한도 Slider(1000~선택 볼륨 free, MB) / 화질 Picker(HD·FHD·원본) / "예상 저장 시간: HD 약 N시간 · FHD 약 M시간 · 원본 약 K시간"(estimateHours, 선택 화질 굵게) / 알림 토글(notify). 변경 즉시 api.set(patch) → 응답 settings로 갱신, 실패 토스트
struct SentryListView: View            // 상단 날짜(어제/오늘 ◀ ▶, 기본 오늘), 카드 목록(썸네일 4:3 + 시각 HH:mm + 길이 "5분 10초" + 조각 n), remote면 상단 안내 "핫스팟에 연결하면 재생할 수 있어요", 당겨서 새로고침, 빈 날은 "녹화 없음". 카드 탭 → NavigationLink(destination: AnyView(SentryPlayerView(ctx, event)))
```
- 모든 네트워크 호출은 `.task`/`Task`에서 `await`, 결과는 `@State`. 화면 상태 enum(loading/ok/error(한글)).
- 토스트: 자체 작은 오버레이(ContentView의 toastOverlay는 private).

## 4. SentryPlayerView.swift (impl-iOS-B)
> **운영자 결정 10-05: 네이티브 플레이어 대신 릴레이 `/sentry/play?id=…&autoplay=1`(HTML)을 앱 내 WebView로 연다.** 아래 AVQueuePlayer 명세는 대체 전 초안(참고용).
```swift
struct SentryPlayerView: View { init(_ ctx: SentryContext, _ ev: SentryEvent) }
```
- `import AVKit`. 조각 n=0…N-1을 `AVPlayerItem(url: api.fileURL(id:seg:))`로 만들어 `AVQueuePlayer`에 순서대로 — 끊김 없이 이어 재생. `VideoPlayer(player:)` + 아래 컨트롤: 재생/일시정지, ◀10초/10초▶(조각 경계 넘으면 다음 아이템으로), 전체화면(회전 허용은 VideoPlayer 기본), 진행 표시 "mm:ss / 총". LAN 없으면(fileURL nil) 썸네일 + "차량 네트워크(핫스팟)에 연결하면 재생할 수 있어요".
- 하단 정보: 시각, 길이, 조각 수·용량(MB), 차량 내 경로(`storage.root`+`/YYYY-MM/DD/<id>/`), 삭제 버튼(확인 알림 → api.delete → pop). 즐겨찾기·사진앱 저장은 **2차**(버튼 없음).
- 화면 떠날 때 `player.pause()`·아이템 제거. 백그라운드 전환 시 일시정지.

## 5. 공용 파일 훅(센트리 세션이 마지막에 삽입 — 구현자는 손대지 말 것)
- `ContentView.swift` `menuScreenContent` `case .extras:` 아래 `if showRelayUI { sentryEntry }`; `private var sentryEntry: some View`는 `autotripEntry`(4162~4180) 복제: `NavigationLink(destination: AnyView(SentryView(SentryContext(uid: userId, vin: selectedVin, api: SentryAPI(uid: userId, vin: selectedVin)))))`, 아이콘 "shield.lefthalf.filled", 제목 "센트리", 부제 "주차 중 4분할 녹화". D5(씨라7)면 숨김 또는 "씨라이언7은 준비 중" 비활성 행.
- `App.swift` 조용한 푸시: `motion:` 분기 앞에 `if ev.hasPrefix("sentry:") { let id = String(ev.dropFirst(7)); 로컬 알림 "🛡 센트리 — 움직임 감지 (HH:mm)" userInfo ["event": ev]; completionHandler(.newData); return }`(settings.notify일 때만 릴레이가 보냄).
- `DoorGuard.swift` `NotifDelegate` 탭: `userInfo["event"]`가 `sentry:`면 `SentryCenter.onOpen?(id)` 정적 클로저(ContentView가 등록 → 센트리 화면 열고 해당 이벤트 플레이어). 1차는 "센트리 화면 열기"까지.
- `Info.plist` 번호는 아이폰 세션 몫(올리지 않음).

## 6. 구현자 공통 규칙
- Swift 5, 기존 코드처럼 간결·한글 주석 최소. `print` 대신 `RemoteLogger.shared.log("SENTRY", …)`(있음)로 오류만.
- 빌드 확인은 검증자가 함(구현자는 돌리지 않음). 단 문법 확신 없는 API(AVQueuePlayer 등)는 Apple 문서 기준으로.
- 접근성: 버튼에 라벨.

## 7. 2단계 추가 사항 (센트리 세션, 2026-10-05 — 릴레이 쪽은 구현·테스트 완료, 폰 화면은 아이폰·안드 세션 몫)

### 7.1 새 설정 키(SENTRY_SET / POST /sentry/settings, 기존과 같은 방식·같은 검증)
- `filter_person` (bool, 기본 false) — "사람이 있을 때만 녹화·알림", `filter_vehicle` (bool, 기본 false) — "차량(자동차·오토바이·자전거·버스·트럭)이 있을 때만". 둘 다 켜면 어느 쪽이든, 둘 다 꺼짐이면 거르지 않음. `motion_only`(움직임 감지)와 함께 켤 때만 의미(연속 녹화에서는 영향 없음). 움직임이 잡히면 릴레이가 4분할 칸 4개에서 선택한 종류의 점수를 재서 0.4 이상일 때만 이벤트를 시작·알림. 모델이 없으면 거르지 않음. **(10-05 20시경 `person_only`를 이 둘로 바꿨음 — 폰 화면이 아직 없어 영향 없음. 객체 인식은 성능을 보고 나중에 릴레이에서 통째로 뺄 수 있으니, 화면은 이 두 토글을 "없어도 되는 선택 기능"으로 두면 좋다)**
- `osd_datetime` (bool, 기본 true), `osd_battery` (bool, 기본 true), `osd_pos` (`top`|`center`|`bottom`, 기본 `top`) — 영상 위 시각·배터리 표시. **이미 1단계 계약에 키가 있었고 이제 릴레이가 실제로 그린다.** 값이 바뀌면 센트리가 다시 시작된다(몇 초). 배터리 값을 모르면 배터리 표시는 생략.
- 이미 있던 키 중 화면만 붙이면 되는 것(릴레이 변경 없음): `sensitivity`(1~200), `min_detect_s`(1~5), `roi_enabled`+`rois`(감지영역 팝업), `preroll_s`(1~20), `post_s`(1~60), `fps`, `bitrate_kbps`, `codec`, `shadow_filter`, `light_change_filter`, `time_limit_enabled`·`time_mode`·`time_from`·`time_to`(방해 금지 시간), `safe_location_enabled`(위치 판단은 폰이 하고 `pausedByLocation`으로 알림), `notify`. 설계 7.7 표 참고.

### 7.2 공유판 접속 인증 — /sentry/* 에 권한 값(grant) 필수 (운영자판은 지금처럼 사설망만, 변경 없음)
- 공유판 릴레이는 LAN `/sentry/*`(목록·썸네일·영상·재생 페이지·설정 전부)에 **`?sgx=<만료 epoch ms>&sg=<서명 16진>`** 이 없거나 틀리면 401을 돌려준다.
- 서명: `sg = HMAC-SHA256(키 = 릴레이 페어키 K, 메시지 = "SENTRY|" + VIN + "|" + sgx)` 소문자 16진. 카메라 보기 권한(CAMRTC `cg`/`cgx`)과 **같은 방식·같은 키·같은 vin**이므로 그 코드를 그대로 재사용하면 된다(메시지 앞 글자만 `SENTRY`).
- 만료 `sgx`는 지금으로부터 **최대 약 1시간**(3,700초까지 허용, 과거는 거부). 한 번 만든 값을 만료 전까지 모든 요청에 재사용한다(구간 요청·재생 중 하위 요청에 헤더를 못 붙이므로 주소에 넣는 방식). POST도 주소에 붙인다.
- 폰이 여는 `/sentry/play`·`/sentry/web` 페이지는 받은 `sgx`·`sg`를 안의 영상·삭제·썸네일·이동 링크에 릴레이가 알아서 이어 붙인다(폰은 처음 주소에만 붙이면 됨). 만료되면 페이지를 새 값으로 다시 열어야 한다(재생 중 1시간 넘는 경우만).
- 워커 경로(SENTRY_SET·SENTRY_STATUS·SENTRY_LIST·SENTRY_THUMB)는 종전대로 명령 서명(csig)이며 변경 없음.

### 7.3 상태에 안전 위치 일시중지 값 (아이폰 980 요청, 센트리 세션 10-05)
- 센트리 status(워커 경로 SENTRY_STATUS 응답·LAN `/sentry/status`·relay_state `sentry` 키)에 **`pausedByLocation`(bool)** 이 항상 들어간다. 폰이 `SENTRY_SET {pausedByLocation, reason}`으로 쉬게 한 상태이고, 릴레이를 다시 시작해도 유지된다(영속). 폰은 이 값을 먼저 쓰고 없을 때만 `why=="안전 위치"` 문구로 폴백한다 — **릴레이는 "안전 위치" 문구를 바꾸지 않는다.**
- 값이 바뀌면 상태 변화가 없어도 즉시 relay_state에 올린다. 시동 ON 때 자동 해제는 하지 않는다(릴레이 설계 판단: 폰이 위치 판단을 맡으므로 폰이 해제).
- 객체 인식 행: status의 settings에 `filter_person`이 보이면 노출하는 약속 — 성능 때문에 릴레이에서 객체 인식을 빼면 그 키가 사라져 폰 행이 자동으로 없어진다.

### 7.4 녹화 카메라 레이아웃 — 칸 배치·뒤집기 (센트리 세션, 10-05 운영자 "링크락 화면과 동일하게")
- **새 설정 키 2개**(SENTRY_SET / POST /sentry/settings, 기존과 같은 검증): `layout_map` = 정수 4개 배열, `layout_flip` = 정수 4개 배열. 잘못된 값(중복·범위 밖·길이 다름)은 기본으로 되돌린다. status의 settings에도 항상 나온다.
- **규칙은 링크락 실시간 보기와 1:1**(워커 카메라 페이지 쿼드 합성 `quadmap`·`camlive`):
  - 원본 프레임(AVM 4분할)의 칸 번호 q = 0 좌상 · 1 우상 · 2 좌하 · 3 우하 (행 우선, 실시간 보기의 카메라1~4 = a1~a4). 돌핀 실측: a1 후방 · a2 왼쪽 · a3 오른쪽 · a4 전방.
  - `layout_map[d]` = 도착 칸 d(0 좌상·1 우상·2 좌하·3 우하)에 놓을 원본 칸 q. 기본 `[0,1,2,3]`(그대로). 실시간 보기의 `quadmap_<vin>`과 같은 의미·같은 순열이다.
  - `layout_flip[q]` = 원본 칸 q 카메라의 뒤집기 비트(1 가로, 2 세로, 3 둘 다). **뒤집기는 원본 카메라에 붙는다(도착 칸 아님)** — 실시간 보기 `camlive_<vin>_a<q+1>`의 {hf,vf}와 같다.
  - 감지영역(ROI)·감도는 **언제나 원본 프레임 좌표** — 배치를 바꿔도 영역을 다시 그릴 필요 없다. ROI 편집 화면은 지금처럼 원본 4분할을 보여 준다(FB1900 결정: 편집·감지 모두 원본).
- **화면(폰)**: 센트리 설정에 "레이아웃" 행 하나. 열면 실시간 보기의 **"배치"(두 칸을 눌러 자리 바꾸기) → "조정"(칸을 눌러 가로·세로 뒤집기)** 와 같은 조작을 그대로 쓴다(구현은 워커 카메라 페이지 재사용을 권장: 센트리용 모드로 열어 저장 대신 앱 통로로 `{layout_map, layout_flip}`을 넘겨 앱이 SENTRY_SET으로 보내기). "지금 실시간 보기 배치와 같게" 버튼을 두면 편하다(실시간 보기 설정에서 값을 가져와 채움). 펴기·크롭 조정은 실시간 보기에서도 숨겨져 있어 센트리에도 넣지 않는다(3단계 후보: 일렉트로식 파노라마 2x2).
- 바뀌면 센트리가 파이프라인을 다시 시작한다(몇 초, 진행 중 이벤트는 마감). 썸네일도 같은 배치로 만들어진다. 기본값(그대로)이면 비용이 없고, 뒤집기·자리바꿈은 프레임당 수 ms~십수 ms라 실차에서 fps를 확인한다.

### 7.5 워커 경로 달력 SENTRY_CALENDAR (아이폰 요청, 집에서 달력 점 찍기)
- 명령 `SENTRY_CALENDAR {month:"YYYY-MM"}` → 결과 data는 LAN `/sentry/calendar`와 같은 `{"days":{"5":63,…}}`(키=앞 0 없는 일, 값=그날 이벤트 수). 한 달치라 작아 워커 8000자 한도 걱정 없음. 월 형식이 틀리면 `{"ok":false,"error":"month"}`.
- 게이트·서명·응답 방식은 SENTRY_LIST와 같다(운영자판·돌핀 전용, 공유판·씨라7은 거부, 앱 K 서명 필요).

### 7.6 표시 위치 6곳 + 녹화 전체 삭제 (아이폰 요청, 운영자 10-06 00시대)
**표시 위치 `osd_pos` — 값 6개**: `top_left`·`top_center`·`top_right`·`bottom_left`·`bottom_center`·`bottom_right`. 띠(가로 전체 반투명 검은 띠)는 위 또는 아래에 깔리고, **띠 안에서 글자 덩어리(날짜·시각 + 배터리 모양·%)만 좌·가운데·우로 정렬**된다. 기본은 `top_left`.
- 옛 값 호환: `top` → `top_left`, `bottom` → `bottom_left`(**지금 영상 모양이 왼쪽 정렬이라 그 모양을 그대로 잇기 위해** 요청하신 `_center`가 아니라 `_left`로 읽음 — 이미 `top`이 저장된 차의 영상이 갑자기 가운데로 바뀌지 않게). 옛 `center`(세로 가운데)와 잘못된 값은 기본(`top_left`)으로 읽는다. status의 settings에는 **항상 새 6개 값 중 하나**로 나오므로 폰은 그 값이 6개 중 하나면 6위치 UI, 아니면 3위치 UI로 가면 된다.
- 바뀌면 센트리 파이프라인이 다시 시작된다(이전과 같음).

**녹화 전체 삭제**
- LAN `POST /sentry/clear` `{"confirm":"all"}`(Content-Type: application/json) · 워커 `SENTRY_CLEAR {confirm:"all"}`(SENTRY_LIST와 같은 게이트·K 서명, 운영자판·돌핀 전용). `confirm`이 없거나 다르면 `{"ok":false,"error":"confirm"}`로 거부. 이미 삭제 중이면 `{"ok":false,"error":"이미 삭제 중"}`.
- 응답 `{"ok":true,"deleted":N,"freedMb":M}` — **지우기 시작 시점의 이벤트 수·용량**이다. 지우기는 뒤에서 이어진다(저장소가 느리면 수 초~).
- 지우는 범위: 센트리 폴더(**모든 저장소 루트** — 지금 쓰는 곳 + 폴백 때 내장·앱전용·공용에 남은 곳)의 `YYYY-MM` 폴더 아래 이벤트·썸네일·색인·고아 조각 전부(즐겨찾기 포함). 루트 폴더 자체와 그 밖의 파일은 건드리지 않는다.
- 진행 중 이벤트: 센트리 세션을 멈춰 이벤트를 **마감한 뒤** 지운다. 삭제 중에는 센트리가 `PAUSED`, why `전체 삭제 중`이고 끝나면 스스로 재무장한다.
- 지원 여부: status(워커 SENTRY_STATUS 응답·LAN /sentry/status·relay_state `sentry` 키)에 **`"clear": true`** 가 있으면 전체 삭제 버튼을 보인다.
- 폴더 표시: status의 `root`(전체 상태)·`storage.volumes[].path`로 어느 폴더인지 보여 줄 수 있다.

### 7.7 집에서 재생 — 직결(SENTRY_RTC) 데이터 채널 (R2 업로드 SENTRY_PUSH는 운영자 "비용 부담 싫어"로 폐기·제거됨, 10-06)
- 집에서 재생은 폰과 헤드유닛의 WebRTC 데이터 채널 직결(779)이다. 요청·응답 규격은 릴레이 세션 문서와 아래 7.7b(화질 선택)를 따른다.

**7.7b 화질 선택(재압축) — 폐기·삭제됨(10-06 운영자)** — 아이폰 983부터 집에서 보기 화질 선택을 없애고 차의 재압축 코드(SentryTranscoder)도 지웠다. 직결 조각 요청 `{"get":n,"id":…,"q":…}`의 `q`는 생략·`orig`만 인정하고, 그 밖의 값은 `{"seg":n,"err":"q"}`(이벤트 전체는 `{"all":true,"err":"q"}`)로 **명시적으로 거부**한다. `transcoding` 상태 응답과 `err:"transcode"`도 더는 없다.

### 7.8 이벤트 전체 다운로드(이어 붙이기) — 운영자 10-06 "다운로드는 헤드유닛에서 이어 붙이는 방향"
- LAN `GET /sentry/download?id=<이벤트>` — **첫 조각만 주던 것이 이벤트 전체를 영상 하나로 합쳐 내려주는 것으로 바뀐다**(재인코딩 없이 샘플을 그대로 이어 붙임, 화질 손실 없음, Range 지원, 파일명 `<id>.mp4`). 마감돼 색인에 오른 조각만 잇고 진행 중 조각은 제외. `?q=`는 생략·`orig`만 인정(그 밖은 400 `q`, 화질 선택은 폐기), 녹화 없음은 404.
- **합치는 동안 요청이 기다린다**(10분 이벤트 원본 약 300MB는 읽고 쓰는 데 수십 초 이상 걸릴 수 있음) — 폰의 요청 제한 시간을 넉넉히(5분 이상) 잡을 것. 같은 이벤트·조각 수·크기·화질이면 차가 만들어 둔 파일을 재사용(최근 1~2개, 2시간, 차의 캐시 폴더).
- 직결(SENTRY_RTC) 데이터 채널: 요청 `{"all":true,"id":…}`(q 생략·orig만) → `{"all":true,"state":"preparing","q":"orig"}`(합치는 중) → `{"all":true,"size":N,"q":"orig"}`(N=합친 파일 크기) → 64KB 이진 → `{"all":true,"done":true}`. 실패는 `{"all":true,"err":"id|q|build|send"}`. 집에서 원본 전체는 용량이 커서(분당 약 30MB) 아이폰은 이 요청 대신 폰에서 조각을 이어 저장한다(983).
- 로그(SENTRY): "이벤트 합치기 시작 <id> 조각 N개 q=… X MB" / "이벤트 합치기 완료 X초 · Y MB". 코드 경로는 JVM에서 못 시험(안드로이드 미디어 API)하므로 첫 실차 실행에서 이음매(끊김·시간 어긋남)를 확인한다.


### 7.9 지금 보기(녹화 중 조각 즉시 닫기) — 10-06
- 배경: 차는 조각을 1분 단위로 쓰고 mp4 목차가 파일 끝에 오므로, 녹화 중인 조각은 목록·재생에 안 나온다(이벤트도 첫 조각이 닫힌 뒤 최대 1분 뒤 목록에 오름). 알림을 받고 바로 보려는 사용자는 '마지막으로 닫힌 조각까지(최대 1분 전)'만 본다.
- 요청: LAN `POST /sentry/cut`(본문 `{}`·Content-Type JSON), 집에서는 워커 명령 `SENTRY_CUT`(K 서명, params 없음). 상태 JSON에 `"cut":true`가 있는 릴레이만 지원(없으면 호출하지 말고 안내 문구만).
- 응답: `{"ok":true,"cut":true|false,"id":"<이벤트id>","state":"RECORDING","why":"…"}`. cut=true면 현재 조각을 방금 닫아 목록에 올렸다(다음 키프레임 최대 1초 + 저장 마감, 릴레이가 최대 약 3초 기다리고 응답). cut=false+why "곧 닫힘"이면 1~2초 뒤 목록을 다시 불러 본다. why "녹화 중이 아님"이면 이미 끝난 이벤트라 그대로 재생.
- 폰 흐름 제안: 이벤트가 진행 중(상태 RECORDING이고 lastEvent==그 이벤트)일 때 재생 화면에서 '지금까지 보기' 버튼 → cut 호출 → 목록 재조회 → 그 이벤트의 조각 재생(새 조각이 쓰이는 동안 지난 부분을 계속 이어 보려면 같은 버튼을 다시). 조각이 2초 미만이면 자르지 않고 기다렸다 자른다(너무 짧은 조각 방지).


### 7.10 사람·차량 거르기 제거 / 저장소 입출력 지연 표시 — 10-06
- **사람·차량 거르기(filter_person·filter_vehicle) 제거(운영자 결정)**: 어안 4분할 영상에서 사람·차량 인식 정확도가 낮아(차량이 뚜렷이 보여도 점수 0.1~0.2, 어안 원을 '오븐'으로 오인) 이벤트를 놓칠 위험이 커서 기능을 없앴다. 릴레이는 이 두 키를 **받아도 무시**하고 상태 JSON·설정 JSON에서 **더 이상 내려주지 않는다**. 폰은 설정 화면에서 해당 토글을 지우면 된다(옛 앱이 보내도 오류 없음). 오탐은 감지영역·감도·최소 지속·조명/그림자 거르기로 줄인다.
- **상태 JSON `ioDelay`: true** — 외장 저장소(SD·USB) 입출력이 막혀 내장 저장소로 무장했거나 입출력 작업이 밀리는 중이다(키가 없으면 정상). 폰은 저장소 카드에 '저장소 응답이 느려 잠시 내장에 저장 중' 같은 안내를 쓸 수 있다.
- 서버 상태 진단 키(relay_state 최상위): `sentry_trace`(최근 8줄 무장·게이트·저장소 준비 흔적), `sentry_trace_prev`(이전 실행의 마지막 흔적), `sentry_io`("busy Ns" — 저장소 입출력 작업이 10초 넘게 막힘, 평소 빈 값), `sentry_stall`(센트리 처리 스레드 정지 의심).


### 7.11 칸별 어안 보정·화면 조정 `cell_views` — 10-07 운영자 "카메라 보조 실시간 카메라 조정처럼 직접 커스텀·센트리용 값은 따로" (827의 dewarp bool 대체)
- 설정 키 `cell_views`(객체, 기본 `{}`): 키는 원본 4분할 칸 번호 **"1"~"4" 행 우선(1좌상·2우상·3좌하·4우하 = a1~a4)**, 값은 카메라 보조 `ccam_*`와 **같은 형식 JSON** — `x·y·w·h`(칸 안 영역 비율 0~1), `dw`(펴기), `fov`(기본 160), `rot`, `mir`, `pan`/`proj`(0 직선·1 파노라마·2 가로 직선), `tilt`, `cx`, `cy`, `k`. 키가 없는 칸 = 원본 그대로, 객체 전체가 비면 보정 없음(비용 0).
- 출력은 칸 원본 크기(돌핀 1280x960)라 합성 프레임은 지금과 같은 2560x1920이다. **영역(x·y·w·h)의 가로세로 비를 4:3으로 잠가야** 안 늘어난다(가로 직선(proj 2)은 각도 범위 비로 맞춰 잘라 늘어나지 않음). 칸의 `mir`는 `layout_flip`의 좌우 뒤집기와 XOR로 합쳐진다.
- 패치: `SENTRY_SET`/`POST /sentry/settings` `{"cell_views": {"2": {...}}}`는 **칸 단위 병합**(그 칸 값은 통째로 교체), `{"cell_views": {"2": null}}`은 그 칸 삭제(원본으로), `{"cell_views": {}}`은 변화 없음, 전체 지우기는 `{"cell_views": null}`. 바꾸면 센트리 세션이 다시 시작된다(몇 초). 상태 JSON `settings.cell_views`가 항상 객체로 내려가면 '지원' 표시.
- 적용 순서: 칸별 펴기 → 배치(`layout_map`)·뒤집기(`layout_flip`) → 시각·배터리 표시. 움직임 감지·감지영역(ROI)은 항상 원본 프레임. 썸네일도 같은 보정으로 만든다.
- 옛 `dewarp`(bool, 827)는 받아도 무시한다(settings에서도 내리지 않음).
- 비용: 4칸을 전부 펴면 프레임마다 4개 스레드로 표 조회 — 827 실측으로 대기 중 앱 CPU 약 +0.6코어, 녹화 fps 13~14(끄면 21~26). 앱 설명에 '켤수록 CPU 사용이 늘어요' 한 줄을 권한다. 조정한 칸만 비용이 든다.
- 식 동기화: 릴레이 `SentryDewarp.buildWarp`는 `SnifferService.ClusterCam.customWarp`(영역·fov 기본·proj/pan·가로 직선 판정)와 같은 식이다 — 한쪽을 바꾸면 다른 쪽도 같이.


### 7.12 사람·차량 거르기 되살림(YOLO11n) — 10-07 운영자 "yolo11n이 별로 안 무겁고 더 좋으면 그걸로, 소스 공개하면 되지"
- 7.10에서 지운 `filter_person`·`filter_vehicle`을 **되살린다**(키·의미 같음, 기본 false). 설정 JSON에 다시 내려가고 SENTRY_SET으로 바꿀 수 있다. 바꿔도 세션 재시작 없음(이벤트 시작 때 읽음).
- 모델이 EfficientDet-Lite0 → **YOLO11n(입력 416)** 으로 바뀌었다. 10-07 시험(해상도를 줄인 썸네일 기준): 가까운 사람 0.74→0.86, 주차된 차 0.21→0.4~0.7. 칸별 펴기(cell_views)를 켠 칸은 편 화면으로 인식해 점수가 더 높다(0.7~0.9).
- 동작: 움직임이 잡히면 4칸에서 사람·차량 최고 점수를 재서 사람 0.40·차량 0.45 이상일 때만 이벤트를 시작한다. 한 번 못 넘어도 움직임이 이어지면 1초마다 다시 보고, 4초 동안 계속 못 넘으면 포기하고 4초 쉰다(사전 녹화가 있어 늦게 시작해도 앞부분은 안 잃음). 모델이 없거나 실패면 거르지 않고 통과.
- 화면: 설정에 토글 '사람만 녹화'·'차량만 녹화'를 다시 둔다. **'실험적 — 어안 영상이라 멀리 있는 사람은 놓칠 수 있어요'** 한 줄을 붙이고 기본 꺼짐을 유지한다. 움직임 감지 모드에서만 의미가 있다(연속 녹화는 해당 없음).
- 비용: 켜면 움직임이 잡힐 때마다 몇 번 추론(차 안 소요 시간은 SENTRY 로그 '물체 점수 … (Nms)'로 확인). 앱 크기 +약 10.7MB. 라이선스: YOLO11은 AGPL-3.0이라 소스를 공개한다(THIRD_PARTY_NOTICES.md).

### 7.13 녹화화면 조정의 쿼드 미리보기 = 실제 녹화될 화면 (`scv=1`) — 10-08 운영자 "수정모드에서 저장해도 상위 쿼드 화면은 수정 전이 보여, 실제 녹화될 화면을 보여줘야지 / 원본 프레임도 화질 좋게"
- 원인: 배치 조정 시트(`SentryLayoutSheet.preview`)의 쿼드는 `captureURL`(감지 영역 편집과 같은 원본 4분할)에서 **첫 프레임 한 장**을 찍어 칸으로 잘라 쓴다. 저장한 어안 보정(`cell_views`)이 이 사진에는 반영되지 않고 "펴짐" 배지만 붙었다. 첫 프레임은 영상 압축이 올라오기 전이라 가장 뭉개진 프레임이기도 하다.
- 릴레이(구현 완료): 실시간 영상 요청(`/camrtc` 페이지 → `/rtc/offer` → `CAMRTC` 명령)에 **`scv=1`** 을 실으면, 릴레이가 지금 저장된 `cell_views`로 **칸별 어안 보정만** 입힌 쿼드를 내보낸다(녹화와 같은 `SentryDewarp`). 저장된 값이 없으면 원본 그대로(비용 0). 서버(`server.js`)는 페이지 URL의 `scv`를 offer에 싣고 params로 전달만 한다. 이 옵션을 모르는 옛 릴레이는 무시하고 원본을 보낸다.
- **배치(`layout_map`)·뒤집기(`layout_flip`)는 릴레이가 입히지 않는다.** 앱이 지금처럼 칸을 놓을 때 직접 입힌다(편집 중인 미저장 값을 바로 보여줘야 해서). 둘을 릴레이가 같이 입히면 이중 적용된다.
- **감지 영역 편집(`SentryRoiSheet`)은 `scv`를 붙이지 않는다** — 감지·ROI는 항상 원본 프레임(7.11). `SentryRoiSheet.captureURL`은 둘이 같이 쓰므로, 배치 시트용 URL을 따로 만들어 `&scv=1`을 덧붙인다. 릴레이 버전이 `scv`를 아는지는 상태 JSON `settings.cell_views`가 객체로 내려오는 것(7.11 '지원' 표시)과 같은 릴레이 버전(이 기능이 들어간 번호는 릴레이 세션이 알려 줌)으로 판단한다 — 모르는 릴레이에선 지금과 같이 원본이 나온다.
- **켜 둔 영상에도 저장이 바로 반영된다(10-08 추가)**: 릴레이는 `scv` 영상이 열려 있는 동안 1초마다 저장된 `cell_views`를 다시 읽고, 바뀌면 보정표를 새로 만들어 이어서 보낸다(로그 "센트리 미리보기 보정 다시 만듦(저장값 바뀜)"). 그래서 **페이지(`/camrtc`)를 라이브로 띄우는 쪽(안드로이드 웹뷰)은 `scv=1` URL만 붙이면 되고 재촬영 신호가 필요 없다.** 저장은 앱이 `SENTRY_SET`을 보내고 끝난 뒤 반영되므로, 앱이 `SENTRY_SET` 성공을 받은 시점부터 최대 약 1초 + 보정표 생성 시간 뒤에 새 값이 영상에 나온다. 페이지 JS는 저장 뒤 스스로 offer를 다시 요청하지 않는다(`lkCcamResult`는 안내 문구만 띄우고 시트를 닫음). 사진 한 장을 찍어 쓰는 쪽(아이폰)은 아래 2번처럼 재촬영한다.
- 앱 몫:
  1. 배치 시트 쿼드 사진을 `scv=1` URL로 찍는다.
  2. **어안 보정을 저장(`saveCell` 성공)하면 사진을 다시 찍는다**(`shotToken` 올리기) — 저장 직후 `SENTRY_SET`이 끝난 뒤에 연다(릴레이는 저장값을 열 때 읽음).
  3. 첫 프레임 바로 쓰지 말고 영상이 올라와 안정된 뒤(첫 프레임 후 약 2~3초) 한 장을 쓴다. 기다리는 동안 이전 사진을 흐리게 유지하면 깜빡임이 없다.
  4. "펴짐" 배지는 그대로 둔다(보정이 이미 사진에 반영되므로 안내 문구에서 '미리보기에는 반영되지 않아요' 류가 있으면 뺀다).
- 확인 방법(릴레이 로그 `CAMRTC`): "센트리 미리보기 보정 켬 — 칸 … 표 생성 Nms" / "저장된 칸별 어안 보정 없음, 원본 그대로" / "보정 못 만듦·실패 — 원본으로 돌아감".

### 7.14 센트리 버튼 → 실시간 카메라 직행 / 헤드유닛 센트리 화면 / 파일명 — 10-08 운영자 승인("ㄱㄱㄱㄱㄱ")
- **센트리 버튼 = 실시간 카메라로 바로(앱 몫, 릴레이 변경 없음)**: 지금은 센트리를 누르면 `SENTRY_CUT`(지금 보기, 7.9)으로 녹화 조각을 끊고 목록으로 간다. 이제 누르면 **실시간 카메라(쿼드) 화면이 바로 뜬다.** 센트리가 녹화 중이면 릴레이가 센트리가 받은 프레임을 실시간 보기와 나눠 쓰므로(791 프레임 공유) **녹화는 끊기지 않는다.** 목록·달력은 그 화면 안의 별도 버튼(예: "녹화 목록")으로 연다. "지금 보기"(`SENTRY_CUT`)는 목록 안의 진행 중 녹화 항목에서만 누르게 옮긴다. 센트리가 꺼져 있으면 지금처럼 카메라를 따로 열어 보여 준다(7.13 scv와 무관, scv는 배치 조정 화면 전용).
  - **10-08 운영자 정정(아이폰 1006에 반영)**: "센트리 버튼"은 **움직임 감지 푸시를 누를 때**를 말한다. 센트리 화면 첫 칸은 그대로(녹화 영상·설정)고, 푸시를 누르면 카메라 메뉴의 '실시간 보기'가 열린다(이 경로에선 `SENTRY_CUT` 안 부름). 실시간 화면은 들어가자마자 재생하지 않고 재생 버튼을 눌러야 영상 연결을 시작한다(카메라 보조 조정 화면은 예외로 바로 띄움). 안드에도 같은 내용 전달.
- **파일명(릴레이, 구현 완료)**: 조각 파일을 `<이벤트id>_NNN.mp4`(예: `2026-10-08_20h48m34s_000.mp4`)로 쓴다. 폴더는 이미 `YYYY-MM/DD/<이벤트id>/`라 그대로. **옛 `seg-NNN.mp4`도 계속 읽는다**(기존 파일 이전 불필요). 앱은 조각을 번호(`seg=N`)로 요청하므로 영향 없음. `/sentry/download` 이름(`<이벤트id>.mp4`)·앱 갤러리 저장 이름(`센트리_yyyyMMdd_HHmmss.mp4`)은 그대로.
- **헤드유닛 센트리 화면(릴레이, 구현 완료)**: 릴레이 앱 메인에 **"🎥 센트리 녹화 보기"** 버튼(운영자판만 보임)이 생기고 `SentryViewerActivity`가 릴레이 자신의 `/sentry/web` 페이지를 WebView로 연다. `/sentry/web`에 **월 달력**(녹화 있는 날만 링크·이벤트 수·선택한 날·오늘 강조·◀▶ 달 이동)을 추가했다. 폰 앱 WebView도 같은 페이지를 열면 달력이 보인다. 헤드유닛에서 2560x1920 영상이 부드럽게 재생되는지는 차에서 시험이 필요하다.

### 7.15 녹화 시간축 표시(marks) — 10-09 운영자 "움직임이 있었던 구간을 녹화 스트림 라인에 표시하고 눌러서 그 시점 영상을 보게" (1단계: 움직임만)
- **릴레이(구현 완료)**: 움직임 감지로 시작한 이벤트(`motion_only=true`)에서, 이벤트 영상 안에서 움직임이 확정된 순간을 `meta.json`의 `marks`에 저장한다. 연속 녹화(`motion_only=false`)는 감지기가 없어 표시가 없다(비어 있음, 이후 CPU 측정 뒤 검토).
- **형식**: 이벤트 객체의 `marks: [[tMs, kind, cell, pct, durMs], …]` — `tMs`=**영상 0초 기준** 시작 시각(ms, 사전 녹화 구간 포함: 감지 확정 직전에 이미 시작된 움직임도 채움), `kind`=0 움직임·1 차량·2 사람(**1단계에선 0만**, 객체 인식이 충분히 좋아진 뒤 채움), `cell`=**영상에서 보이는 칸 0~3(좌상·우상·좌하·우하)** — 배치(`layout_map`)를 바꿔도 영상과 맞게 변환해 저장, 감지 영역을 직접 그렸으면 -1, `pct`=그 칸의 최고 움직임 세기(%), `durMs`=이어진 길이. 같은 칸에서 3초 안에 이어지는 감지는 하나로 합친다. 이벤트당 최대 100개. 표시가 없는 이벤트는 키가 없다(옛 이벤트 그대로).
- **노출**: LAN `/sentry/list`의 각 이벤트 객체(`EventMeta.toJson`)와 `/sentry/play` 웹 페이지에 포함. **원격(워커) 목록(`buildRemoteList` {id,s,d,n,b,f})엔 아직 없다** — 크기 제한(≈7KB) 때문이며, 원격에서 쓰려면 이벤트 하나의 표시만 받는 명령(예: `SENTRY_MARKS {id}`)을 따로 추가해야 한다(앱 요청 시).
- **웹 재생 페이지**: 시간축 막대(움직임=파랑, 차량=주황, 사람=초록)와 최대 12개 칩 버튼을 영상 아래에 그린다. 누르면 그 시각 1초 전으로 이동해 재생한다.
- **앱 몫(아이폰·안드로이드, 릴레이 세션 경유)**: 재생기 시간축에 `marks`를 점/막대로 그리고, 누르면 그 시점으로 이동(`seekGlobal(tMs)`, 1초 앞). 칸 이름은 `cell`로 "좌상·우상·좌하·우하", `kind`로 움직임·차량·사람(1단계는 움직임만). 값이 없으면 지금처럼 표시 없음.

### 7.16 객체 판정(사람·차량) + 알림 사진(박스·종류·%) — 10-09 운영자 "사람이나 차량이 감지된 경우 알림에 그걸 말하고, 해당 객체에 사각형과 종류·퍼센트를 보여 달라"(오버드라이브 "긴급·사람·좌측" 방식) — 1단계(릴레이)
- **릴레이(구현 완료, 운영자판 먼저)**: 움직임 이벤트가 시작되면 별도 스레드에서 YOLO11n으로 **새로 나타난 사람·차량**을 판정한다. 움직임 직전(4초 이상 전)의 **기준 화면**에서 같은 자리·같은 종류로 이미 있던 박스(주차된 차 등)는 빼고(IoU 0.8 — 10-10까지는 0.35였으나 기준 화면에 있던 차가 움직인 경우(겹침 0.56~0.66)도 빠져 놓쳤다), 기준 점수(사람 0.40·차량 0.45)를 넘은 것 중 사람 우선으로 하나를 고른다. 어안 보정이 적용된 영상 칸 기준이다(점수가 더 높음). 못 찾으면 1.5초·2.5초 뒤 두 번까지 다시 본다. 기준 화면이 아직 없으면(무장 직후) 건너뛴다. 이미 있는 `filter_person`/`filter_vehicle`(녹화 거르기)와 별개이며, 꺼져 있어도 판정만 한다.
- **저장**: 이벤트 `meta.json`의 `objects: [[kind, pct, cell, x0, y0, x1, y1], …]` — `kind` 1=차량·2=사람, `pct`=점수%, `cell`=**영상에서 보이는 칸 0~3(좌상·우상·좌하·우하)**, 박스는 그 칸 대비 0~1(좌상 원점). 없으면 키 없음. 시간축 표시(`marks`, 7.15)의 `kind`도 그 칸의 판정으로 채운다(사람 2·차량 1, 사람이 정해진 칸은 차량으로 안 덮음).
- **알림 사진**: `GET /sentry/alert?id=<이벤트id>` — 판정된 칸을 잘라 **빨간 박스와 "사람 87%" 라벨**을 그린 JPEG(폭 640). 이벤트 객체에 사진이 있으면 목록 항목에 `"alert":"/sentry/alert?id=…"`가 붙는다. 없으면 404(판정 실패·옛 이벤트).
- **알림 문구(2단계, 아직 안 함)**: 지금 푸시는 그대로 "센트리 — 움직임 감지"다(아이폰 `DoorGuard`가 `sentry:<이벤트id>` 차량 이벤트로 만든다). 사람·차량을 말하려면 판정 뒤(1~2초) 종류·칸·점수를 담은 이벤트를 하나 더 보내 새 앱이 같은 알림을 "사람 감지 — 좌측"로 바꾸게 하는 프로토콜이 필요하다 — 옛 앱은 모르는 이벤트를 무시해 문구가 그대로다. **카메라 이름(전·후·좌·우)과 원본 칸의 대응표**가 먼저 필요(돌핀 미확정). 운영자 승인·앱 세션 협의 뒤 진행.
- **검증(맥, 같은 tflite·실제 영상 프레임)**: 10-09 00:13 영상에서 좌상 칸의 새 차(0.55·0.73·0.65)만 남고 주차된 차는 빠짐, 조명만 바뀐 영상은 새 객체 0개. **차 안 속도·정확도 실측은 아직**(재생 시험·걷기 시험 예정).

### 7.17 재생 시험(운영자판 전용) — 10-09 운영자 "차에 연속녹화 10GB가 있는데 다 받기엔 크다, 차 안에서 판정 모듈로 하나씩 돌려봐"
- **무엇**: 저장된 녹화 조각을 차 안에서 하드웨어 디코더(`SentryFrameDecoder`)로 풀어 움직임 감지(`SentryMotion`)와 객체 판정(`SentryObjects`+YOLO11n)을 그대로 돌리고, 이벤트마다 "몇 초에 영상의 어느 칸에서 움직임 몇 %, 사람/차량 몇 %"를 보고서와 확인용 사진(박스·종류·% 포함)으로 남긴다. 데이터는 차 밖으로 안 나간다. 폰·헤드유닛 브라우저로 보고서만 본다.
- **주의(근사)**: 녹화 영상은 칸 배치·어안 보정·시각 표시줄이 이미 적용된 화면이다. 실제 감지는 원본 프레임 기준이라 **움직임 세기는 근사**(방향성)이고, 객체 판정은 운영 중과 같은 입력(보정된 영상 칸)이다. 시각·배터리 표시줄(아래 64px)은 감지에서 뺀다. 판정 빈도는 실제와 맞춰 초당 약 7.6번.
- **시작·중지**: `POST /sentry/replay`(JSON) — `{"date":"2026-10-08","limit":40,"skip":0}` 또는 `{"ids":["…"]}`, 선택 `settings:{sensitivity,min_detect_s,light_change_filter,shadow_filter}`로 감도 등을 바꿔 비교, `{"action":"stop"}`. **운영자판이 주차(P)·정차일 때만** 시작되고 그 상태가 풀리면 멈춘다. 센트리가 녹화 중이면 쉰다. 한 번에 최대 500개 녹화, 기본 40개.
- **보기**: `GET /sentry/replay`(최신 실행) / `?run=r…`, 진행 상황 `?json=1`. 사진 `GET /sentry/replay/img?run=&f=`. 결과 저장 위치 `<센트리 루트>/_replay/<실행>/report.json`+JPEG(최대 400장). 헤드유닛·폰 `/sentry/web` 페이지 아래에 "재생 시험 보고서" 링크.
- 앱 몫 없음(운영자 도구). 공유판 비활성(`replayAllowed`가 항상 false).

### 7.18 원격 `SENTRY_MARKS` + 소급 기록(retro) — 10-09 운영자 승인("만들어 ㄱㄱ", "이전 녹화도 소급해줘 지금 차량에서 돌릴 거잖아")
- **원격 명령(운영자판 전용, 공유판 거부)**: 워커 액션 `SENTRY_MARKS`, params `{"id":"<이벤트id>"}`. 응답 data(JSON 문자열, SENTRY_THUMB처럼): `{"id":"…","marks":[[tMs,kind,cell,pct,durMs],…],"objects":[[kind,pct,cell,x0,y0,x1,y1],…],"alertJpg":"<base64>","retro":true}` — **없는 키는 뺀다**(표시·판정이 없는 이벤트는 `id`만), 이벤트가 없으면 실패 응답(`{"ok":false,"error":"이벤트 없음"}`). `marks`·`objects` 형식은 7.15·7.16과 같다(`cell`=영상 칸 0~3). `alertJpg`는 `/sentry/alert` 사진을 폭 320→240→160·품질 60→45→30으로 줄여 전체 결과가 7200자 안에 들면 넣고, 안 맞으면 뺀다(그땐 앱이 글자만).
- **소급 기록(`retro`)**: 이벤트 `meta.json`에 `"retro": true`가 있으면 "예전 녹화를 재생 시험(7.17)으로 나중에 판정해 채운 표시"라는 뜻이다. 칸·시각은 같은 형식이지만 **움직임 세기(pct)는 근사**(녹화 영상 기준)다. 앱은 표시를 같게 그리고, 원하면 "나중에 분석됨" 정도의 구분만 둔다. LAN 목록 이벤트 객체와 `SENTRY_MARKS` 응답에 `retro`가 실린다(없으면 키 없음).
- **재생 시험의 `write`(7.17)**: 요청에 `"write":true`를 주면 이벤트마다 재생이 **끝난 뒤** 결과를 `meta.json`(`marks`·`objects`·`retro:true`)과 `alert.jpg`(가장 확실한 판정 사진, 없을 때만)로 쓴다. **이미 `marks`·`objects`가 있는 이벤트는 덮지 않는다**, 진행 중(녹화 중) 이벤트는 건드리지 않는다, 움직임이 없으면 안 쓴다, 오류·중단된 이벤트는 안 쓴다. 연속 녹화(`motion_only=false`) 이벤트에도 쓴다. 감도 등 `settings`를 바꾼 시험과는 같이 못 쓴다(기본 설정 결과만 기록).
- **속도(854 실측: 293초를 330초 = 0.9배속, 디코더 OMX.qcom.video.decoder.avc 하드웨어 — 병목은 디코더가 아니라 5570프레임 전부 변환 113초+감지 164초(감지 한 번 29ms))**: 이어서 ① 판정 프레임을 시각 기준(131ms마다)으로 골라 받는다 ② 색(VU) 평면 변환은 기준 화면·객체 판정 때만(감지는 Y만 씀) ③ 조명 성분 제거 필터를 가우시안 21탭에서 박스 블러 3회로(같은 영상 검증: 조명 영상 최고 0.2→0.3%, 차 통과 3%↑ 판정 34번 동일, 맥 1.7→0.7ms/칸).
- **속도(853 실측: 293초 이벤트 311초 = 0.94배속)**: 이어서 ① 하드웨어 H.264 디코더를 이름으로 골라 쓴다(없으면 기본), ② 이벤트를 기본 2개 동시에 처리(`"parallel":1~3`), ③ 구간별 시간(변환·감지/판정·디코더 이름)을 SENTRY 로그 "재생 시험 속도 …"에 남긴다.
- **속도**: 녹화 파일 디코딩에서 NV21 변환이 병목이라, 판정에 쓰지 않는 프레임은 변환을 건너뛰고 색 평면 변환을 행 단위로 바꿨다(실제 판정 빈도 초당 약 7.6장 기준).

### 7.19 BSD 후측방 인식 측정(1단계, 운영자판 전용) + 돌핀 카메라 대응표 — 10-09 운영자 "BSD 신호 때 항상 인식하고 있되 깜빡이를 켰을 때만 알림 발화, 해당 방향을 더 크게"
- **방향(운영자 결정 "나")**: ① BSD 신호가 오면 **깜빡이와 상관없이** 양쪽 후측방을 인식한다 ② **알림은 깜빡이를 켰을 때만** 낸다 ③ **해당 방향을 더 크게**(좌우 스피커 음량 차) — ②③은 알림 동작이라 **다음 단계(릴레이 세션의 좌우 스피커 알림음 작업과 맞춰 진행)**, 이번 1단계는 **동작을 바꾸지 않는 측정**이다.
- **측정(구현 완료)**: 차 BSD 신호(`ADAS_BSD_STATE`=2, 돌핀은 좌우 합산 하나)가 오거나 깜빡이를 켰는데 BSD가 이미 2일 때, 3초에 한 번, 최근 1.5초 안 측면 카메라 프레임(보조 카메라 상시 대기·BSD 실험 세션이 공급, 없으면 "프레임 없음" 로그)에서 좌·우 측후방을 사이드미러처럼 펴(320x320, 현재 BSD와 같은 화각 163°·±45°) **현재 방식(EfficientDet-Lite0)과 YOLO11n(차량만)** 으로 각각 판정해 점수·시간·화면을 남긴다. 기존 경보음 판정(`bsdBeep`)은 그대로.
- **보기·정답 표시**: `GET /sentry/bsd` — 최신 기록부터 좌/우 후방 사진(YOLO 박스·두 점수)과 [차 있음/차 없음/모르겠음] 버튼, 맨 위에 표시된 것 기준 모델별 TP/FP/FN/TN(점수 ≥ 0.3을 "차 있음"). 표시는 `POST /sentry/bsd/label {id, side:"L"|"R", v:1|0|-1|null}`. 사진 `GET /sentry/bsd/img?f=`. 저장: 앱 외부 파일 폴더 `BsdVision/bsd.json`(최근 200건)+JPEG. `/sentry/web` 페이지 아래에 "BSD 인식 비교" 링크. 로그 카테고리 `BSDVISION`. 설정 `bsd_vision`(기본 켬)으로 끈다. 공유판·씨라7은 비활성.
- **돌핀 카메라 대응표(확정, 코드 `tileRect` ★570 차주 라벨 캡처)**: 원본 쿼드 칸 a1(좌상)=**후방**, a2(우상)=**좌측**, a3(좌하)=**우측**, a4(우하)=**전방**(파노라마 5120x960은 가로로 후·좌·우·전 순). 운영자 차 배치 `layout_map [3,0,1,2]`로 만든 영상(좌상=전방·우상=후방·좌하=좌측·우하=우측)과 영상으로 대조해 일치. 센트리 알림 문구의 "전방·좌측·우측·후방"에 쓴다(2단계).

### 7.20 조명 번쩍임 의심 표시·알림 보류 — 10-09 운영자 "이건 움직임 아니고 조명 번쩍임인데"(02:31 영상) → 승인("ㄱㄱ")
- **문제**: 움직임 없이 그 칸의 평균 밝기가 계단처럼 변해도(98→107) 이벤트·알림이 났다. 조명 성분 제거 뒤엔 남는 움직임 면적이 4%뿐이라 기존 조명 거름(밝기 급변+면적 25%)이 못 잡았다.
- **판정(릴레이)**: 이벤트 시작 뒤 객체 판정(7.16)에서 **새 사람·차량이 없고**, 움직임 칸의 평균 밝기가 기준 화면(4초 이상 전) 대비 **4.5 이상** 달라졌으면 첫 판정에선 보류하고, **두 번째 판정(약 4초 뒤)에도 새 객체가 없으면 "조명 번쩍임 의심"** 으로 표시한다. 사람·차량이 판정되면(헤드라이트로 밝기가 흔들리는 차 통과 포함) 조명 의심이 아니다. 이벤트·영상은 지우지 않고 **표시만** 한다(자동 삭제는 운영자 요청 시 설정으로).
- **저장·노출**: 이벤트 `meta.json`의 `"light": true`(조명 의심일 때만), 그 칸의 시간축 표시 `kind=3`("조명 의심", 사람·차량 판정을 덮지 않음). LAN 목록 이벤트 객체·`SENTRY_MARKS` 응답에 `light`가 실린다. 웹 재생 페이지는 시각 옆에 "조명 번쩍임 의심"과 회색 표시. 앱은 같은 방식으로 "조명 의심" 배지·회색 점을 그리면 된다(없으면 지금처럼).
- **알림 타이밍 변경**: 이벤트 시작 즉시 보내던 `sentry:<이벤트id>` 차량 이벤트를 **객체 판정 뒤로 미룬다** — 일반 움직임(새 객체 없음·밝기 변화 없음)은 첫 판정 뒤(약 1.3초), 사람·차량이 판정되면 즉시, 조명 의심은 **보내지 않는다**(나중에 사람·차량이 판정되면 그때 보낸다). 판정이 안 와도(기준 화면 없음·모델 없음) 5초 안에 보낸다. 앱 변경 없음(같은 이벤트, 도착이 1~5초 늦을 수 있음). 알림 문구에 종류를 싣는 2단계는 별도.

### 7.21 감도 기준 재조정(기본 100 = 임계 4.5%) — 10-09 운영자 "전체 이벤트의 오탐을 모아 오탐되지 않게, 감도 중앙값을 기본으로 지금 오탐이 안 잡힐 정도로, 올리고 낮추는 데 따라 조절"
- **근거(운영자 차 5분 감시 로그 약 80구간 + 이벤트별 판정)**: 조명 번쩍임 오탐이 걸린 구간의 최고 움직임 세기는 **3.0~4.3%**(영상 좌상=전방 칸에서 몇 분마다 반복), 실제 차·사람은 4.7%(먼 차)·5.8%·10~25%였다. 기존 기본 임계(감도 100 = 3%)는 오탐 영역 안이었다.
- **변경(릴레이)**: `minPct = 4.5% × (100 / sensitivity)`(이전 3%). 설정 키·범위(1~200)·기본(100)은 그대로라 **앱 변경 없음**. 감도 200 → 2.25%(더 민감), 150 → 3%, 50 → 9%, 1 → 450%(최대 300%로 클램프, 사실상 끔). 운영자판 센트리에만 영향(공유판 센트리는 이 코드가 없음).
- **한계**: 4.7%였던 먼 차(00:13)는 기본(100)에서 아슬아슬하다. 놓치는 것이 문제면 감도를 110~120으로 올리면 된다(임계 4.1~3.75%, 오탐이 다시 늘 수 있음).

### 7.22 알림 문구에 종류·방향 싣기(2단계) — 운영자 10-09 승인 "ㄱㄱ", 이모지는 쓰지 않는다
- **지금**: 릴레이는 `sentry:<이벤트id>`를 워커에 보내고, 워커가 폰에 조용한 푸시(`event`)로 전달, 앱이 "센트리 — 움직임 감지 (HH:mm)" 알림을 만든다. 푸시에 종류·방향이 없어 문구를 바꿀 수 없다. 워커는 이벤트 이름을 **32자까지만** 통과시킨다(`server.js /relay/event`: `slice(0,32)`).
- **추가(릴레이, 이 커밋)**: 새 이벤트 `sobj:<이벤트id>:<종류><방향>`(28자). 종류 `P`=사람·`V`=차량, 방향 `F`=전방·`B`=후방·`L`=좌측·`R`=우측(릴레이가 배치표 `layout_map`으로 계산해 보내므로 **앱은 배치 규칙을 몰라도 된다**, 모르면 `?`). 사람·차량이 처음 판정됐을 때 **이벤트당 한 번**, 기존 `sentry:<id>`를 보내기 직전에 보낸다(릴레이 `settings.notify`가 켜진 차만). 기존 `sentry:<id>`는 **그대로** 보낸다(타이밍·형식 불변) — 옛 앱은 `sobj:`를 모르는 이벤트로 무시해 지금과 똑같이 보인다. 워커 변경 없음. 점수(%)는 글자 수 때문에 싣지 않는다(알림 사진·센트리 화면에 있음).
- **앱이 할 일(아이폰·안드 공통, 새 앱만)**:
  1. `sobj:<id>:<PV><FBLR?>`를 받으면 같은 이벤트 알림을 **"사람 감지 — 좌측"**·**"차량 감지 — 후방"**(방향 `?`이면 "사람 감지"·"차량 감지")으로 띄우거나 이미 뜬 일반 알림이 있으면 그 자리(같은 알림 식별자 `sentry-<id>`)를 교체한다. 본문은 지금과 같이 "주차 중 움직임을 녹화했습니다."도 가능하나 문구는 앱 세션이 정본으로 정한다. **이모지·이모티콘 금지**(운영자 10-09) — 기존 "🛡 센트리 — 움직임 감지" 문구의 이모지도 이때 같이 빼 달라.
  2. 알림을 누르면 열리는 곳(`userInfo["sentry"]=<id>` → 그 날짜 → 그 영상)은 지금과 같다.
  3. 같은 id로 `sentry:<id>`가 **나중에** 도착해도 이미 `sobj:`로 올린 문구를 일반 문구로 되돌리지 않는다(최근 60초 안 같은 id에 `sobj:`를 본 적이 있으면 알림 문구는 건드리지 않는다). 푸시는 순서가 바뀌어 도착할 수 있다. 반대로 `sentry:`가 먼저 오면 일반 문구로 띄웠다가 `sobj:`가 오면 교체한다.
  4. `sobj:`는 비상등 깜빡임·지오펜스 확인 같은 **다른 동작을 일으키지 않는다**(그건 기존 `sentry:`가 계속 맡는다).
- **방향 대응(운영자 차 배치 `layout_map=[3,0,1,2]`)**: 영상 좌상=전방·우상=후방·좌하=좌측·우하=우측. 돌핀 원본 칸 실측 a1 후방·a2 좌측·a3 우측·a4 전방.
- **알림이 오는 시점**: 지금과 같다(판정 뒤 1~5초). 못 찾는 경우(오늘 05:42·07:50·07:51·07:55 등)는 `sobj:` 없이 일반 문구 그대로 — 실시간 판정 보강은 별도 진행(진단 로그 2411efe).

### 7.23 근접 미달 기록(운영자 승인 10-10, 앱 변경 없음)
- 센트리 로그(SENTRY)에 "근접 미달 영상 좌상칸 — 최고 4.0%(임계 4.5%) · 5장 0.7초 · 무게중심 이동 0.02(경로 0.04) · 평균 면적 3.5% · 전경 폭×높이 0.30×0.20 · 밝기 100→100(범위 98~109)" 형식으로 한 줄이 남는다. 한 칸의 움직임이 임계의 절반(최소 1.5%) 이상으로 올라갔다가 끝났는데 임계에는 한 번도 못 미친 구간(3장 이상)이다. 시간당 40줄까지.
- 목적: 형광등 깜빡임을 감지 단계에서 거르는 규칙(예: 제자리·밝기 오르내림이면 무시)을 추측하지 않고 실제 자료로 정하려는 것. 지나가는 물체는 무게중심 이동이 크고 깜빡임은 작을 것으로 기대한다.
- 동작은 바뀌지 않는다(기록만). 공유판엔 없음(공유판 로그는 서버에 안 올라옴).
