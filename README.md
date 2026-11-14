# Google for Logent

Logent에서 Google Drive·Gmail·Google Calendar·Google Photos를 다루고 Android Google 앱을 여는
독립 `.lpg` 플러그인이에요.

- 저장소: `logent-google`
- 플러그인 id: `com.sleepysoong.logent.google`
- 플러그인 key: `sleepysoong:google`
- 진입점: `com.sleepysoong.logent.google.GooglePlugin`
- 공개 API: Logent 플러그인 API 4.5.0
- 인증: OAuth 2.0 Device Authorization Grant (기기 승인)

`logent-plugin.json`의 `entry`와 실제 구현 클래스가 같아야 해요. `dependencies`는 비어 있어요.

## 도구

| 도구 | 접근 | 자식 공개 | 설명 |
|---|---|---|---|
| `google_auth` | `WRITE` (직렬화) | 아니요 | Google 계정 연결·상태·해제예요. |
| `google_drive` | `WRITE` | 아니요 | Drive 검색·조회·텍스트 파일·폴더·이동·휴지통이에요. |
| `google_gmail` | `WRITE` | 아니요 | Gmail 검색·읽기·보내기·초안·라벨이에요. |
| `google_calendar` | `WRITE` | 아니요 | primary 캘린더 일정 조회·생성·삭제예요. |
| `google_photos` | `READ_ONLY` | 예 | Google 포토 미디어·앨범 메타데이터 조회예요. |
| `google_apps` | `WRITE` | 아니요 | 지메일·드라이브·포토·캘린더·지도·유튜브 앱 또는 웹을 열어요. |

### `google_auth`

`action`은 `start`, `poll`, `status`, `logout` 중 하나예요.

1. `start`가 `https://oauth2.googleapis.com/device/code`에 `client_id`와 필요한 scope를 보내요.
2. 응답의 `user_code`, `verification_url`, `expires_in`, `interval`을 사용자에게 보여 줘요.
   `device_code`는 응답·로그에 넣지 않고 `host.dataDir`의 소유자 전용(0600) 파일에만 저장해요.
3. 사용자가 브라우저에서 코드를 승인하면 `poll`이 토큰 엔드포인트에 기기 승인 grant를 보내요.
   `authorization_pending`, `slow_down`(간격 5초 증가), `expired_token`, `access_denied`를 구분해요.
4. 성공하면 `refresh_token`을 SECRET 설정 `refresh-token`에 저장하고, 액세스 토큰은 메모리에만 둬요.
5. `status`는 연결 여부와 부여된 scope만 돌려줘요. 토큰 값은 절대 돌려주지 않아요.
6. `logout`은 저장된 `refresh_token`, 승인 대기 정보, 메모리 액세스 토큰을 지워요.
   Google 계정 쪽 앱 액세스는 Google 계정 보안 설정에서 직접 제거해야 해요.

액세스 토큰 만료나 API의 401 응답을 만나면 `refresh_token`으로 **한 번만** 갱신하고 같은 요청을
**한 번만** 재시도해요. 갱신이 실패하면 `google_auth start`로 다시 연결하라고 오류를 돌려주고
자동으로 반복하지 않아요.

### `google_drive`

`action`은 `search`, `get`, `read_text`, `create_text`, `create_folder`, `move`, `trash`예요.

- `search`: 검색어를 `fullText contains '...'` Drive 쿼리로 만들고 작은따옴표·역슬래시를
  이스케이프해요. 결과는 `id`, `name`, `mimeType`, `modifiedTime`, `size`, `webViewLink`예요.
- `get`: 파일 메타데이터와 부모·휴지통 여부를 보여 줘요.
- `read_text`: `files/{id}?alt=media`로 최대 512KB를 읽어요. `text/*`, `application/json`,
  `application/xml`, `+json`, `+xml` 같은 텍스트 MIME만 허용해요. Google 문서·시트·슬라이드 같은
  네이티브 파일과 PDF·이미지 같은 바이너리는 명확한 오류를 돌려줘요.
- `create_text`: `uploadType=multipart`로 메타데이터와 본문을 한 번에 올려요.
  UTF-8 기준 200KB까지예요. 업로드가 실패하면 오류를 그대로 돌려주고 빈 파일을 만들지 않아요.
- `create_folder`: metadata-only `POST`로 폴더를 만들어요.
- `move`: Drive API v2 `PUT`의 `addParents`/`removeParents`를 써요. v3 `files.update`는 `PATCH`만
  받는데 호스트의 `PluginHost.http` 계약은 `PATCH`를 허용하지 않기 때문이에요.
- `trash`: Drive API v2 `POST files/{id}/trash`를 써요. 영구 삭제가 아니라 휴지통 이동이에요.

### `google_gmail`

`action`은 `search`, `read`, `send`, `draft`, `labels`예요.

- `search`: Gmail 검색 쿼리로 최대 20개를 찾고 `id`, `threadId`, `From`, `Subject`, `Date`,
  `snippet`을 보여 줘요.
- `read`: payload의 `text/plain`을 우선하고, 없으면 `text/html`을 base64url로 디코드해요.
  최대 20KB만 돌려주고 잘리면 알려 줘요. 첨부파일 본문은 다루지 않아요.
- `send`·`draft`: RFC 2822 MIME을 만들어 본문은 UTF-8 base64, 제목은 필요하면 RFC 2047
  encoded-word로 인코딩해요. 헤더에 CR/LF를 넣으려는 입력은 거부해요.
- `labels`: 라벨 id·이름·종류를 보여 줘요.

### `google_calendar`

`action`은 `list`, `create`, `delete`예요. `time_min`/`time_max`/`start`/`end`는 RFC3339
형식이어야 하고, `start`는 `end`보다 앞서야 해요. `create`의 시간대는 기기 기본 시간대를 써요.

### `google_photos`

`action`은 `list`, `albums`, `search`예요. `search`는 `album_id` 또는 `start_date`/`end_date`
(`yyyy-MM-dd`) 중 하나를 받아요. 응답은 미디어 id·파일 이름·MIME·생성 시각·해상도와 앨범
id·제목·항목 수 같은 메타데이터예요.

### `google_apps`

`app`은 `gmail`, `drive`, `photos`, `calendar`, `maps`, `youtube` 중 하나예요. 설치된 앱의
launch intent를 먼저 시도하고, 없으면 같은 서비스의 HTTPS 웹 페이지를 열어요. Android Context가
없는 환경에서는 실행하지 않고 오류를 돌려줘요.

## OAuth 클라이언트 만들기

플러그인에는 고정 client secret이 들어 있지 않아요. 사용자가 Google Cloud Console에서 만든
OAuth 클라이언트를 설정에 넣어요.

1. <https://console.cloud.google.com/>에서 프로젝트를 만들거나 선택해요.
2. **API 및 서비스 → 라이브러리**에서 다음 API를 사용 설정해요.
   - Google Drive API
   - Gmail API
   - Google Calendar API
   - Photos Library API
3. **API 및 서비스 → OAuth 동의 화면**을 구성해요.
   - User Type은 개인 테스트면 `External`, 조직 전용이면 `Internal`을 써요.
   - 앱 이름·지원 이메일·개발자 연락처를 채워요.
   - `External` + `Testing` 상태면 **테스트 사용자**에 연결할 Google 계정을 등록해야 해요.
     등록하지 않은 계정은 승인 단계에서 `access_denied`가 날 수 있어요.
   - Data Access에서 아래 scope를 추가해요. 검증이 필요한 scope는 Google 검수 전까지
     테스트 사용자에게 경고 화면이 보일 수 있어요.
4. **API 및 서비스 → 사용자 인증 정보 → 사용자 인증 정보 만들기 → OAuth 클라이언트 ID**에서
   애플리케이션 유형을 **TVs and Limited Input devices**(TV 및 제한 입력 기기)로 선택해요.
   Device Authorization Grant는 이 유형에서 동작해요.
5. 생성된 **클라이언트 ID**와 **클라이언트 보안 비밀**을 Logent의 이 플러그인 설정에 입력해요.
   두 값 모두 SECRET 필드예요.
6. `google_auth start` → 브라우저에서 `verification_url` 열기 → `user_code` 입력 → 승인 →
   `google_auth poll` 순서로 연결해요.

앱을 `Testing`이 아닌 상태로 배포하면 Google의 OAuth 검수와 브랜드 확인이 필요할 수 있어요.

## OAuth scope

한 번에 다음 네 범위를 요청해요.

| scope | 용도 |
|---|---|
| `https://www.googleapis.com/auth/drive.file` | 이 플러그인이 만들거나 연 Drive 파일 |
| `https://www.googleapis.com/auth/gmail.modify` | Gmail 읽기·보내기·초안·라벨 |
| `https://www.googleapis.com/auth/calendar` | primary 캘린더 일정 |
| `https://www.googleapis.com/auth/photoslibrary.readonly` | Google 포토 메타데이터 읽기 |

`drive.file`은 최소 권한 범위라서 사용자가 이미 가지고 있는 모든 Drive 파일을 검색하지 못해요.
이 플러그인이 만들거나 연 파일, 공유받은 파일만 접근할 수 있어요. 전체 Drive 접근이 필요하면
코드의 `GoogleScopes.DRIVE_FILE`을 `https://www.googleapis.com/auth/drive`로 바꿔 다시 빌드해야
하며, 이 경우 Google 검수 범위가 달라져요.

**동의 화면에서 일부 scope 제공에 동의하지 않으면** Google이 돌려준 오류를 그대로 보여 줘요.
필요한 scope가 빠지면 해당 도구는 403 오류와 함께 동의 화면 설정을 확인하라고 안내해요.

## 토큰과 비밀값 저장 위치

| 값 | 저장 위치 | 수명 |
|---|---|---|
| `client-id` | SECRET 설정 | 사용자가 지울 때까지 |
| `client-secret` | SECRET 설정 | 사용자가 지울 때까지 |
| `refresh-token` | SECRET 설정 `refresh-token` | `logout` 또는 재연결까지 |
| `granted-scope` | TEXT 설정 | 연결·갱신 시 갱신 |
| 승인 대기 `device_code`·`user_code` | `host.dataDir/google-device-auth.properties` (0600) | 승인 성공·만료·거절·로그아웃 시 삭제 |
| 액세스 토큰 | 플러그인 메모리 | 만료 또는 세션 초기화까지 |

토큰과 비밀값은 도구 결과·오류·로그·프롬프트에 넣지 않아요. HTTP `Authorization` 헤더는
`PluginHost.http`에만 전달하고 셸·URL·본문에 넣지 않아요. `google_auth status`는 연결 여부와
scope만 보여 주고 토큰 값을 반환하지 않아요.

## 한계

- **바이너리 미지원**: `PluginHost.http`는 응답 본문을 String으로 돌려줘요. Drive 바이너리
  업로드·다운로드, Gmail 첨부파일 다운로드, Google 포토 원본 다운로드는 지원하지 않아요.
  - Drive `read_text`는 텍스트 MIME이 아니면 오류를 돌려주고, Google 문서·시트 같은 네이티브
    파일은 Drive에서 텍스트로 내보낸 뒤 사용해야 해요.
  - Gmail `read`는 본문 텍스트만 디코드하고 첨부파일은 이름만 알려 줘요.
  - Google 포토는 메타데이터만 보여 주고, 원본은 Google 포토 앱·웹에서 직접 내려받아야 해요.
    Photos API의 `baseUrl`은 `Authorization` 헤더가 필요한 바이너리 응답이라 이 플러그인의
    HTTP 계약으로 받을 수 없어요.
- **읽기·쓰기 한도**: Drive 텍스트 읽기 512KB, Gmail 본문 20KB, Gmail 전송 본문 100KB,
  Drive 텍스트 생성 200KB예요. 호스트 HTTP 응답 한도(최대 4MiB)와 요청 본문 한도(256KiB)도
  적용돼요.
- **Drive 범위**: 위에서 설명한 `drive.file` 제한이 있어요.
- **Gmail 검색**: 검색 결과마다 메타데이터를 한 번 더 요청하므로 최대 20건까지 순차 조회해요.
- **Calendar**: primary 캘린더만 다뤄요. 반복 일정 생성, 참석자 초대, 알림 설정은 지원하지 않아요.
- **Google 앱 열기**: Android Context가 없으면 동작하지 않아요. 앱이 없으면 웹 URL을 열고,
  브라우저도 없으면 오류를 돌려줘요.

## 보안

- 고정 client secret을 저장소나 APK에 넣지 않아요. 사용자가 설정에 입력한 SECRET 값만 써요.
- 갱신 토큰·클라이언트 자격 증명은 `PluginSettingKind.SECRET`으로만 저장해요.
- 승인 대기 파일은 `host.dataDir`에 만들고 소유자만 읽고 쓸 수 있게 권한을 제한해요.
- 액세스 토큰은 메모리에만 두고, 401이면 갱신 후 한 번만 재시도해요.
- Drive 파일·Gmail 본문은 신뢰할 수 없는 데이터예요. 모델에는 `<untrusted_drive_text>`,
  `<untrusted_email_body>` 경계와 함께 전달하고, 그 안의 지시를 따르지 않아야 해요.
- 네트워크는 코어의 `PluginHost.http`만 써요. HTTPS만 허용되고 공용 주소·리다이렉트·시간·
  응답 크기는 호스트가 검사해요.

## 개발과 테스트

순수 JVM 테스트는 OAuth 요청 본문, 토큰 오류 분기, base64url/MIME 생성, RFC3339 검증,
Drive 쿼리 이스케이프, Gmail 본문 디코드를 실제 Google API 호출 없이 검증해요.

```bash
./scripts/test.sh
```

`.lpg` 패키지는 키 없이 다음 명령으로 만들어요.

```bash
./scripts/build.sh -PlogentPluginApiMavenLocal=true
```

산출물은 `build/dist/logent-google-v<version>.lpg` 하나예요. 이 저장소에는 비밀값을
커밋하지 않아요.

## 라이선스

GNU General Public License v3.0이에요. 전문은 `plugin/LICENSE`에 있어요.
출처와 의존성 안내는 `NOTICE`에 있어요.
