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
