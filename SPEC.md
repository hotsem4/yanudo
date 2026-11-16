# SPEC — 66 Habit English (영어 회화 암송 · 66일 습관 PWA)

1인용 영어 회화 암송 앱. Windows PC 한 대가 서버이고, 갤럭시 Z 폴드에서 Tailscale HTTPS로 PWA를 설치해 쓴다.
이 문서는 기능과 API의 **계약**이다. 코드를 바꾸면 이 문서도 같이 고친다. 모듈 구조와 설계 근거는 [docs/architecture.md](docs/architecture.md).

---

## 0. 용어

| 용어 | 뜻 |
|---|---|
| 논리 날짜(`today`) | 서버 시간대(`app.timezone`, 기본 `Asia/Seoul`)에서 `app.day-rollover-hour`(기본 03시) 이전이면 전날로 친다. 새벽 1시 학습은 전날 기록이다. |
| `current_day` | 시작일(`start_date`)부터 오늘까지 지난 날 수 + 1(1 이상). 하루를 빠져도 Day는 달력대로 넘어가고, **66 뒤에도 계속 는다**. |
| 라운드 | 66일(`total_days`)마다 한 라운드. `round = (current_day-1)/66 + 1`, `round_day = (current_day-1)%66 + 1`. 66일은 습관이 붙는 최소 기간이지 앱이 끝나는 날이 아니다. |
| 레슨 일정 | 날짜마다 공부할 레슨. ① 그 날짜에 달성했으면 그때 끝낸 레슨(고정) ② 그 Day의 레슨이 등록돼 있으면 그것 ③ 없으면 그 Day보다 앞 레슨 중 마지막으로 달성한 날이 가장 이른 것(**복습 순환**, 한 번도 달성 못 한 레슨이 먼저, 같으면 앞 Day). ③은 그 날짜 전날까지의 달성만 본다. |
| `lesson_day` | 오늘의 레슨 일정. 등록된 레슨이 없으면 0. 그 Day의 레슨이 아니면(복습 순환) `content_missing = true`. |
| 세션 | `TODAY`(오늘의 10문장) 또는 `REVIEW`(누적 복습). |
| 열람(view) | 한 문장을 한 번 거쳤다는 기록. `via = AUDIO`(오디오 차례가 끝남) 또는 `CARD`(카드를 1.2초 이상 봄). 날짜·문장·세션·경로마다 한 번만 저장한다. |
| 이벤트 | 클라이언트가 만든 학습 사실 하나(VIEW, MARK, FOCUS, MORNING_LOCK). 클라이언트가 정한 `id`로 멱등. `POST /events` 하나로만 쓴다. |
| 오늘 구간 | 오늘 논리 날짜의 시작·끝 epoch ms(`valid_from`, `valid_until`). 프론트는 이 구간 안의 대기 이벤트만 오늘 화면에 반영한다. |

---

## 1. 기능

### F-01. 일일 학습 큐 & 누적 복습

- 하루 신규 10문장(레슨 1개). 66일 × 10 = 660문장까지 확장한다.
- 상단 세그먼트 탭: `오늘의 10문장 (Day N)` / `누적 복습`.
- 누적 복습 큐 = 어제부터 거꾸로 레슨 일정의 레슨 최대 4개(겹치면 한 번, 오늘 레슨은 빼고). **최근 것이 먼저**(보통 Day N-1 → N-2 → …). 레슨 안에서는 `seq` 순서.
  - Day 2~4는 쌓인 전체 일차가 그대로 나온다.
  - 앞선 레슨이 없으면(Day 1) 오늘 문장을 미리보기 복습으로 보여 준다(`preview: true`).
  - 복습 순환 날에는 그동안 다시 한 레슨들이 복습 큐가 된다.

### F-02. 3단계 어순 인출 플래시카드

- 문장 필드: `en_text`(영어 원문), `ko_word_order`(영어 어순 직역, 덩어리를 ` / `로 구분), `ko_natural`(자연스러운 해석).
- 가리기 필터(원탭 전환):

| 필터 | en_text | ko_word_order | ko_natural |
|---|---|---|---|
| `all` 전체 | 보임 | 보임 | 보임 |
| `hint` 어순힌트 | 블러 | **보임** | 블러 |
| `korean` 한글만 | 블러 | 블러 | **보임** |

- 블러된 카드를 탭하면 부드러운 전환으로 2.5초 동안 블러가 풀린다(다시 탭하면 즉시 닫힘).
- 카드 좌우 스와이프 = 이전/다음 문장. 카드와 오디오 플레이어는 **같은 커서(index)** 를 쓴다.
- **따라 말하기(오늘의 10문장, 2026-11-16 결정: 학습 앱이라 듣기만으로는 달성이 아니다)**: 오늘 탭 카드에서는 하단 독이 `[이 문장 듣기] [마이크] [다음 문장 듣기]`가 된다(연속 재생 중에는 멈출 수 있게 재생 독). 한 문장을 **두 단계**로 말한다(Fable 5.2 설계 판단).
  1. **보고 말하기**: 영어를 보며 따라 말한다. 말하는 동안 맞힌 단어가 진해진다.
  2. **가리고 말하기**: 영어를 가리고 해석·어순만 보고 말한다. 이 단계를 통과해야 `VIEW`(`session: TODAY`, `via: SPEAK`)를 남긴다.
  - **오늘 탭에서는 이 두 단계만 영어 표시를 정한다**(2026-11-16 QA 반영). 가리기 필터·역할극 가림은 쓰지 않고 해석·어순은 늘 보인다. 가린 단계는 마이크를 켠 뒤에도, 인식 결과가 오기 전에도 가린 채다. 카드를 탭해 엿볼 수 없다. 오늘 탭 컨트롤은 배속·설정만 둔다(가리기 필터·재생 모드는 복습 탭 전용). 배속은 문장 들려주기에도 적용된다.
  - 1단계를 통과하면 맞힌·놓친 단어를 1.5초 보여 준 뒤 영어를 가리고 2단계로 간다. 1단계 통과와 받아 적기 선택은 그 앱 실행 동안 기억한다(문장·탭을 오가도 이어짐, 새로고침하면 처음부터).
  - 인식은 연속 모드다. 말이 1.8초 끊기거나 문장을 다 맞히면 스스로 멈추고, 그때의 최종 결과로 판정한다(숨 고르기는 넘긴다. 마이크를 다시 눌러 바로 끝낼 수도 있다): 모닝 보이스 락과 같은 `wordMatch`, 순서대로 맞힌 단어 60% 이상. 말하는 동안 가린 단계에서는 맞힌 단어만 드러나고 나머지는 빈칸, 말을 끝내면 맞힌 단어(진하게)·놓친 단어(흐리게, 점선 밑줄)를 다 보여 준다. 단계 칩과 안내 띠는 진행 레일 바로 아래("2/2 가리고 말하기" / "단어 9개 중 8개 (88%)…"). 오늘 10문장을 다 통과하면 단계 칩은 사라진다.
  - 통과하면 1.2초 뒤 아직 통과하지 못한 다음 문장으로 넘어가 그 문장을 들려준다(없으면 "오늘 10문장 모두 통과"). 레일·문장 지도·독 "다음"·스와이프로 옮겨도 그 문장을 들려준다. 들려주는 동안 띠에 "문장을 들려주는 중"을 보인다. 마이크를 켜기 전에 재생·미리듣기·기기 음성을 모두 멈춘다(앱 소리가 인식되지 않게).
  - 오늘 탭에 들어오거나 앱을 다시 열면 지금 문장이 이미 통과한 문장일 때 첫 미통과 문장으로 옮긴다.
  - 소리 내기 어렵거나 마이크를 쓸 수 없으면 **받아 적기**(단계 칩 옆 버튼으로 전환): 영어를 가린 채 카드 아래 입력칸에 들은 문장을 적는다. 80% 이상이면 통과(철자는 인식 오차가 없어 기준이 높다). 맞힌 단어만 드러난다. 받아 적기로 통과하면 다음 문장은 소리 없이 넘어간다.
  - 진행 레일은 문장마다 한 칸(누름 영역 44px, 누르면 그 문장으로)이고 통과한 문장을 채운다. 문장 지도가 보이는 화면(너비 600px·높이 640px 이상)에서만 숨고, 지도는 오늘 탭에서 "따라 말하기 통과 n/10"을 센다.
  - 오늘 탭이 잠겨 있으면 하단 독을 숨긴다(잠금 패널 버튼을 가리지 않게). 잠겨 있어도 오늘 탭 컨트롤은 배속·설정만 둔다. 잠금 패널 제목은 "Day N 문장부터 복습해요"(오래 쉬고 돌아오면 "어제" 레슨이 처음 보는 레슨일 수 있다).
  - 날이 바뀌어 큐(문장 목록)가 달라지면 그 탭의 재생 위치를 처음으로 되돌린다(오늘 탭은 첫 미통과 문장, 복습은 1번). 앱을 켜 둔 채 날이 바뀌어도 어제 위치에서 이어 가지 않는다(이어 가면 복습 게이트의 앞 문장들을 못 채운다).
  - 오늘 문장을 연속 재생으로 끝까지 들으면 "들은 것은 진행으로만 남아요. 문장마다 따라 말해야 오늘이 달성돼요" 안내 뒤 첫 미통과 문장으로 옮긴다.
  - 날이 바뀌었는데 서버에 닿지 못해 아직 어제 문장이면, 안내 띠가 "지금 통과는 오늘 달성에 세지 않아요"라고 알린다.
  - "오늘은 어려워요"를 알린 날은 오늘 탭 배너로도 알린다.
  - 오늘 통과한 문장은 쉬는 동안 영어를 보여 준다(단계 칩 "통과한 문장"). 다시 말해 보면 그때 단계대로 가린다. 다 끝낸 뒤에도 문장을 다시 볼 수 있다.
  - 받아 적기를 해 본 문장에서 말하기로 돌아가면 가린 단계(2/2)로 간다(받아 적기 ↔ 말하기를 오가며 답을 보지 않게). 듣는 중에 받아 적기로 바꾸면 듣던 말은 채점하지 않고 버린다.
  - 마이크를 쓸 수 없는 오류(`not-allowed`, `service-not-allowed`, `audio-capture`)만 그 실행 동안 받아 적기로 고정한다. `network` 는 그 시도의 일시 오류로 안내만 한다. 말한 단어가 있으면 뒤따른 오류보다 판정이 먼저다.
  - 커버 화면은 위쪽(헤더·할 일·탭)이 길어 문장이 독 뒤로 내려간다. 문장을 들려주거나 마이크를 켜면 문장 블록(영어·어순·해석)이 독 위에 오도록 스크롤한다(처음 열 때는 움직이지 않는다).
  - 문장이 바뀐 첫 프레임에도 가릴 문장은 가린 채로 그린다(답이 번쩍 보이지 않게). 가린 영어는 읽기 프로그램에서도 숨긴다.
- 문장별 상태 토글: `NEW` → `MEMORIZED`(암기 완료) ↔ `PRACTICE`(다시 연습). 서버에 저장한다.

### F-03. 3모드 핸즈프리 오디오 플레이어

| 모드 | 동작 |
|---|---|
| `full` 연속 | 큐를 처음부터 끝까지 재생. 문장 사이 0.6초 간격. |
| `shadowing` 끊어읽기 | 문장 재생이 끝나면(`ended`) `duration_ms × 1.3` 동안 무음 대기. 재생 버튼 테두리와 카드에 카운트다운 링 표시. |
| `roleplay` 역할극 | `speaker = A`는 정상 재생. `speaker = B`는 소리를 내지 않고 영어 원문을 가린 채 `duration_ms × 1.3` 동안 대기(사용자가 B 대사를 말함). |

- 큐 끝에 닿으면 멈춘다.
- **프리로딩**: 세션을 불러오면 그 큐의 MP3를 모두 `Cache API`(`audio-v1`)에 받아 두고 `Blob URL`로 재생한다. 지하철 터널에서 네트워크가 끊겨도 재생이 이어진다. Cache API가 없으면 `fetch → Blob`만 쓴다.
- **무음 대기 중 미디어 세션 유지**: 대기 동안 같은 `<audio>` 요소에 1초짜리 무음 WAV를 반복 재생해 잠금 화면 컨트롤과 버즈 버튼이 살아 있게 한다.
- **Media Session API**: 메타데이터(영어 문장, `Day N · A/B`), `play` / `pause` / `previoustrack` / `nexttrack` 핸들러.
- 배속: `0.85` / `1.0` / `1.15` 순환 토글. 무음 대기 시간은 배속과 무관하게 `duration_ms × 1.3`.
- 오디오 파일이 아직 없거나 받아 두지 못한 문장(4초 안에 받지 못한 경우 포함)은 기기 `speechSynthesis`의 영어 음성으로 대신 읽는다. 음성 목록이 늦게 오면 잠깐 기다리고, 목록을 3초 안에 받지 못했거나 기기에 영어 음성이 없거나 읽기가 오류로 끝나면, 다른 언어 음성으로 읽지 않고 그 문장을 들음 기록 없이 건너뛴다. 3문장 연속이면 재생을 멈추고 알린다. `duration_ms`는 서버가 늘 채운다(클립이 없으면 단어 수 × 380ms 추정, `duration_estimated: true`).
- 무음 대기와 문장 간격은 그 길이의 무음 WAV를 같은 `<audio>` 요소에서 재생하고 `ended`로 끝낸다(화면이 꺼져도 타이머가 밀리지 않고, 일시정지하면 남은 시간이 보존된다).
- `AUDIO` 열람 보고 시점: full·shadowing·역할극 A는 발화가 끝날 때, 역할극 B는 대기가 끝날 때.
- 프리로드 상태(`준비 n/m`)를 화면에 작게 보인다.

### F-04. 부드러운 강제성 장치 4종

1. **URL 자동 실행**: `/?autoplay=true&mode=shadowing|roleplay|full`
   - 복습 게이트가 잠겨 있으면 `누적 복습`을 먼저 재생하고, 재생 중 게이트가 풀리면 큐가 끝난 뒤 `오늘의 10문장`으로 이어서 재생한다.
   - 자동 재생은 모닝 보이스 락보다 우선한다(오디오 재생 자체가 학습 시작).
   - 브라우저가 소리 재생을 막으면(`NotAllowedError`) "탭해서 재생 시작" 버튼을 띄운다. 홈 화면에 설치한 PWA는 Chrome 정책상 자동 재생이 허용된다.
   - 매니페스트 `shortcuts`에 세 모드의 자동 실행 URL을 등록한다(앱 아이콘 길게 누르기).
2. **모닝 보이스 락**: 벽시계 `06:00 ~ 09:30`에 앱을 열었고(앱 시작·화면 복귀 때 판단), 오늘 열람 기록이 하나도 없고(서버 + 대기 이벤트), 오늘 락을 아직 처리하지 않았으면 표시한다. 시간 창은 서버가 epoch ms로 내려 주므로 오프라인에서도 판단한다. 판정은 클라이언트가 하고 결과만 `MORNING_LOCK` 이벤트로 보낸다.
   - 오늘 레슨에서 `core = true`인 문장 3개(모자라면 `seq` 순으로 채움).
   - `SpeechRecognition`(en-US, interim 결과 사용)으로 인식. 정답 단어 중 **순서대로** 인식된 단어 비율(소문자·구두점 제거·축약형 펼침, 최장 공통 부분 수열)이 **60% 이상**이면 그 문장 클리어. 단어를 뒤섞어 말하면 통과하지 못한다. 발음·억양은 평가하지 않는다(브라우저 인식의 한계). 3문장이면 잠금 해제(`CLEARED`).
   - **받아 적기**: 말하기가 어려우면 "받아 적기로" 바꿔 문장을 듣고 영어로 입력한다. 통과 전까지 영어 문장은 가리고(맞힌 단어만 드러냄) 같은 60% 규칙으로 채점한다. 마이크·인식을 쓸 수 없으면(미지원, 권한 거부, 마이크 없음, 네트워크) 자동으로 받아 적기가 된다.
   - `긴급 건너뛰기`(`SKIPPED`)는 마이크·인식을 쓸 수 없을 때나 한 문장에서 3번 못 넘겼을 때만 보이고, 한 번 더 확인받는다. 문장 듣기 버튼 제공.
   - 범위는 PWA 화면 안이다. 폰의 다른 앱을 막지는 못한다(웹 앱 권한 밖).
3. **어제 복습 게이트**: "어제 레슨" = 어제의 레슨 일정(보통 Day `current_day-1`, 복습 순환 날이면 그날 다시 한 레슨). 오늘 레슨과 같으면 게이트 없음. 그 레슨 문장이 **오늘** `REVIEW` 세션에서 모두 열람(`AUDIO` 또는 `CARD`)될 때까지 `오늘의 10문장` 카드는 블러·잠금 상태다. 다 채우는 순간 즉시 풀린다. 어제 레슨이 없으면(Day 1) 게이트도 없다.
4. **22:00 마감 리마인드**: `@Scheduled(cron = "0 0 22 * * *", zone = app.timezone)` + 22:30 재시도 + 서버 시작 시 22:00~03:00이면 보충. **서버에 도착한 기록 기준**으로 오늘 습관이 미달성이면 Web Push(VAPID) + 선택적으로 Telegram/Discord 웹훅 발송. 하루 한 번만 보내고(`reminder_log`), 모든 채널이 실패하면 하루 3회까지 다시 시도한다.
   - VAPID 키가 설정에 없으면 첫 실행 때 만들어 `data/vapid.json`에 저장한다.
   - 푸시 서비스가 404/410을 돌려주면 그 구독을 지우고 주소를 `push_expired`에 기억한다. 같은 주소로 다시 구독하면 저장하지 않고 `{"subscribed":false,"expired":true}`로 답한다(브라우저에 남은 만료 구독을 계속 다시 보내 "켜짐"인데 알림이 오지 않는 일을 막는다).
   - 복구: 앱은 열 때·알림을 켤 때·시험 알림이 0건일 때 `expired`를 받으면 옛 구독을 지우고 새로 구독한다. 서비스 워커는 브라우저가 `pushsubscriptionchange`를 보내면 앱을 열지 않아도 새 구독을 등록하고 옛 구독을 지운다(브라우저가 이 이벤트를 보내 줄 때만. 안 오면 다음에 앱을 열 때 복구되고, 그 사이 22시 알림은 놓칠 수 있다).
   - 알림 모양: 상태 표시줄 배지는 투명 바탕의 흰 실루엣 `icons/badge-96.png`(안드로이드는 알파만 쓴다), 진동 `[200,100,200]`, 누르면 `/?view=today`.
   - PC가 22시 전후에 꺼져 있으면 알림이 가지 않는다.
   - 등록된 레슨이 하나도 없거나 "오늘은 어려워요"를 알린 날은 보내지 않는다. 66일 뒤에도 계속 보낸다.

### F-05. 66일 습관 트래커 & 스트릭 방어

- 오늘 달성 = 오늘 레슨 문장 전부를 **따라 말하기로 통과**(`TODAY`·`SPEAK` 열람, 받아 적기 포함). 듣기(`AUDIO`)·카드 넘기기(`CARD`)는 진행으로만 남고 달성에 세지 않는다. 완주하는 순간 서버가 `day_achievement`에 한 번 기록한다. 이 기록은 이후 콘텐츠가 바뀌어도 뒤집히지 않는다. 빈 레슨은 완주로 치지 않는다.
- TODAY 열람인데 그 문장이 그 날짜의 오늘 레슨에 속하지 않으면(롤오버 뒤에도 열어 둔 어제 큐 등) 서버가 REVIEW로 바꿔 기록한다.
- 스트릭 프리즈: 30일 단위 구간(`Day 1-30`, `31-60`, `61-90`, …, 끝없이)마다 2개. 쓰지 않은 프리즈는 다음 구간으로 넘어가지 않는다.
- **보충(makeup)**: 지난 날 중 달성하지 못한 날은 그 날짜의 레슨(Day = 시작일부터 지난 날 + 1)을 말하기 탭에서 문장마다 **5번** 맞게 말해 보충한다(F-09). 그날 안에 "오늘은 어려워요"를 알렸으면 **1번**씩이면 된다. 보충은 그 날짜 **다음 날부터** 한 `speak_practice`만 센다(필요 횟수 이상인 기록). 모든 문장이 채워진 날이 보충한 날이다.
- **스트릭 회복**: 보충을 그 날짜 + 3일까지(그날 포함) 끝내면 그날은 `REPAIRED`가 된다. 프리즈처럼 스트릭을 유지하되(늘지 않음) 프리즈는 쓰지 않는다. 기한을 넘겨 보충하면 말하기 탭에서 열리기만 하고 스트릭은 돌아오지 않는다. 보충은 매 요청 다시 계산하며 저장하지 않는다.
  - 보충이 **스트릭을 지키는가**(`repairable`, `makeups_due`): 아직 안 끝낸 보충은 기한 안이고, 기한 안의 보충을 모두 끝냈다고 가정해 걸었을 때 그 날짜 직전 스트릭이 0보다 클 때다(목록에 남길지). 처음부터 안 했거나 앞에서 이미 끊겼으면 보충은 Day를 열기만 한다.
  - **끝낸 보충은 가정 없이 실제 기록만으로** 지켰는지 판정한다(2026-11-16). 앞날을 비운 채 뒷날만 보충하면 그 뒷날은 아직 `REPAIRED`가 아니다. 지킨 날짜 집합이 더 줄지 않을 때까지 다시 걷는다. 그래서 "지킴"으로 칠했다가 앞날 기한이 지나 "놓침"으로 되돌아가는 일이 없고, 앞날까지 끝내면 그때 함께 칠해진다.
  - **보충 효과**(`effect`, `streak_if_done`): 아직 안 끝낸 보충을 지금 이것만 끝내면 생기는 일. `STREAK`(끊긴 연속이 n일로 이어짐), `FREEZE`(그날 쓴 프리즈만 돌아옴, 연속은 이미 지켜져 있음), `WITH_OTHERS`(뒤의 보충까지 끝내야 n일로 이어짐), `AFTER_EARLIER`(앞날 보충부터 해야 반영됨), `NONE`(Day만 열림). 할 일 시트·말하기 탭은 이 값으로 안내한다("11/18까지 하면 연속 4일로 이어져요"). 여러 개면 "이른 날부터".
  - "오늘은 어려워요" 횟수는 알린 뒤 그날 달성한 날과 프로그램 밖 날짜를 세지 않는다. 이미 달성한 날에 알리면 받기만 하고 남기지 않는다.
- **"오늘은 어려워요"**: 그날 안에 `REST_DAY` 이벤트로 알린다(`rest_day`). 30일 구간마다 4번. 알린 날은 22시 리마인드를 보내지 않는다. 알려도 달성으로 치지는 않는다(보충이 가벼워질 뿐).
- 스트릭 계산(시작일부터 어제까지 순서대로 끝없이, 결정적, 저장하지 않음):
  - 달성한 날: `streak += 1`
  - 미달성 + 기한 안에 보충함: 스트릭 유지(증가 없음), 프리즈 차감 없음, 그날은 `repaired`
  - 미달성 + 스트릭 > 0 + 그 구간 프리즈 남음: 프리즈 1개 차감, 스트릭 유지(증가 없음), 그날은 `frozen`
  - 미달성 + (스트릭 0 또는 프리즈 없음): `streak = 0`
  - 오늘: 달성했으면 `+1`, 아직이면 그대로(오늘은 끊지 않는다).
- 66일 뒤에도 스트릭·프리즈·보충·"오늘은 어려워요"·22시 알림이 그대로 계속된다. 스트릭 순회는 시작일부터 끝없이 이어진다.
- 같은 레슨을 연속으로 빼먹으면(복습 순환 중) 날짜마다 보충이 생기지만 **한 번 보충으로 함께 이어진다**(보충 기록은 그 레슨 문장마다의 말하기 연습이라 날짜를 가리지 않는다). 할 일 시트와 말하기 탭에는 가장 이른 날짜 한 줄만 보인다.
- 오프라인에서 어제 기록과 오늘 기록이 한 묶음으로 오면, 서버는 날짜가 넘어갈 때 앞 날짜의 달성을 먼저 기록하고 일정을 다시 계산한 뒤 오늘 기록을 분류한다.
- 한계(의도한 단순화): 어제 달성이 따로 늦게(오늘 기록 뒤에) 도착하면 오늘의 복습 순환 레슨이 바뀔 수 있다. 미달성 날짜는 나중에 그 Day 레슨을 넣으면 일정이 바뀐다(달성한 날은 고정).
- 관리자가 시작일을 바꾸면 Day 귀속·히트맵·스트릭이 새 시작일 기준으로 다시 계산된다(기록은 날짜로 저장되어 사라지지 않는다).
- 헤더 좌측 카드: 1라운드는 `66일 습관` + `Day N / 66`, 2라운드부터 `N라운드 k/66` + 누적 `Day N`(분모 없음). 진행률 바는 라운드 진행. `🔥 N`, 남은 프리즈. 탭하면 이번 라운드 66칸 히트맵 모달.
- 새 라운드에 들어선 뒤 처음 앱을 열면 "N라운드를 시작해요"를 한 번 띄운다(기기마다). 포롱은 Day 66에 다 자란 모습이 되어 유지된다.

### F-06. 25분 모래시계 타이머

- 헤더 우측 카드. 탭하면 25:00부터 카운트다운, 모래시계 게이지가 줄어든다.
- 0이 되면 알람·강제 종료 없이 `+00:01`부터 조용히 카운트업(초과 집중).
- 시작 시각은 `localStorage`에 저장해 새로고침해도 이어진다. 끝내기를 누르면 `FOCUS` 이벤트(`id = focus:<started_at>`)로 집중 시간을 기록한다.

### F-07. 콘텐츠 확장 파이프라인

- 첫 실행 때 레슨 테이블이 비어 있으면 `classpath:seed/lessons-day01-05.json`(Day 1~5, 50문장)을 넣는다.
- `POST /api/v1/admin/lessons/import`로 JSON 일괄 업서트. 같은 `(day, seq)`면 텍스트만 바꾸고 문장 id와 암기 상태(`sentence_mark`)는 그대로 둔다. JSON에 없는 기존 `seq`는 지우지 않는다. 텍스트·화자가 바뀌면 파일 이름이 바뀌어 새 오디오가 필요해진다(자동).
- `scripts/generate_tts.py`가 서버에서 작업 목록을 받아 MP3를 병렬로 만들고 서버에 올린다. 파일 이름·길이 규칙은 서버(Java)에만 있다.

### F-08. 단어장

- **표현(term)**: 단어 또는 짧은 구. 항목은 `text`(사전형, 예: `grab`), `meaning`(이 뜻의 짧은 한국어), `nuance`(언제·어떤 느낌으로 쓰는지 한국어 한 문장), `examples`(일상 예문 3개, 각각 `en`·`ko`).
- **미리 만든 표현**: 레슨 임포트 때 문장마다 배울 만한 표현 2~3개를 함께 넣는다(`terms`, 4.7 형식). 같은 표현은 `term_key` = 소문자·공백 정리한 `text`로 하나로 합친다.
- **담기**: 학습 카드의 단어 버튼 → 그 문장의 미리 만든 표현(뜻 포함)과 나머지 단어 칩 → 눌러 담는다. 오프라인에서도 담기는 이벤트(`VOCAB_ADD`)로 쌓였다가 전송된다.
- **미리 만들지 않은 단어**를 담으면 서버가 생성기로 뜻·느낌·예문 3개를 만든다(`status: PENDING` → `READY` / `FAILED`). 생성기는 `app.vocab.generator`로 고른다.
  - `claude-cli`(기본): 로그인된 Claude Code 구독으로 `claude -p` 헤드리스 실행. 사용자 설정 훅을 피하려고 `--setting-sources project`, 출력은 `--json-schema`로 강제, 도구 없음(`--tools ""`), 세션 저장 안 함. 실패하면 `FAILED`로 두고 다음 시작·재시도 때 다시 한다.
    - **장기 토큰(선택)**: `claude setup-token`(구독으로 만드는 1년짜리 토큰, API 요금 아님)을 `app.vocab.claude-oauth-token`에 넣으면 `CLAUDE_CODE_OAUTH_TOKEN`으로 넘긴다. 비우면 PC에 로그인된 구독을 쓴다(만료일이 있어 약 한 달마다 다시 로그인).
    - **로그인 끊김 알림**: 서버 시작 때와 매일 `login-check-cron`(기본 12:00)에 짧은 실제 호출 한 번으로 로그인을 점검한다(`claude auth status`는 토큰을 확인하지 않아 쓰지 않는다). 생성이나 점검이 로그인 문제(401·`authentication_error`)로 실패하면 폰(웹 푸시·웹훅)에 알리고, 구독 로그인은 만료 `login-warn-days`(기본 3)일 전부터 미리 알린다. 같은 알림은 논리 날짜 하루 한 번. 다시 성공하면 그사이 실패한 표현을 자동으로 다시 만든다. 단어장 화면은 뜻을 기다리는 단어의 뒷면에 로그인이 끊긴 이유를 보여 준다.
  - `anthropic-api`: API 키로 Claude Haiku 5.5. `none`: 생성하지 않고 사용자가 직접 뜻을 적는다.
  - 사용자는 어떤 표현이든 뜻·메모를 직접 고칠 수 있다(직접 입력이 생성값보다 우선).
- **예문 음성**: 단어장에 담는 순간 그 표현의 예문 3개 음성을 만든다(1·3번 Ava, 2번 Andrew, `-10%`). 준비 전에는 기기 음성으로 대신 읽는다.
- **단어장 탭**: 세션 탭 세 번째. 카드처럼 넘겨 본다(앞면: 표현 / 탭하면: 뜻·느낌·예문 3개·처음 본 학습 문장). 예문마다 듣기 버튼. "외웠어요" 표시, 외우는 중 / 외운 단어 / 전체 필터, 지우기.

### F-09. 말하기 연습과 보충

- **목적**: 공부한 문장을 입에 붙이는 반복 연습이자, 빼먹은 날의 보충 수단(F-05). 학습 화면은 그대로 두고 따로 연다. 달성 판정에는 쓰지 않는다.
- **말하기 탭**: 세션 탭 네 번째(`?view=speak`). 오늘 레슨 일차까지의 Day 목록을 최근 먼저 보여 준다.
- **Day 상태**(그 Day의 날짜 = 시작일 + Day - 1):
  - `OPEN`: 그날 달성했거나 보충을 끝낸 지난 Day, 오늘 달성한 오늘 Day. 자유 연습(문장마다 5번), 행에 연습을 끝낸 문장 수.
  - `MAKEUP`: 달성하지 못한 지난 Day. 보충(문장마다 5번, 미리 알린 날은 1번)을 끝내면 `OPEN`이 된다. 행에 "보충 5번씩 · 11/18까지"(기한 안일 때)와 보충한 문장 수.
  - `TODAY_LOCKED`: 오늘의 10문장을 끝내기 전의 오늘 Day. 자물쇠와 "오늘 n/10". 누르지 못한다.
- 보충 화면은 처음 보는 문장일 수 있어 어순 힌트(`ko_word_order`)도 보여 준다.
- "오늘 할 일" 시트 아래에 기한 안의 보충 목록(누르면 말하기 탭의 그 Day)과 "오늘은 어려워요" 버튼(한 번 더 확인)을 둔다.
- **연습**: Day를 고르면 문장을 하나씩 보여 준다(영어 + 자연 해석, 듣기 버튼). 마이크를 눌러 말하면 모닝 보이스 락과 같은 단어 맞춤(`wordMatch`, 순서대로 맞힌 단어 60% 이상)으로 판정해 1회로 센다. **5회**를 채우면 그 문장을 끝낸 것으로 기록하고 다음 문장으로 넘어간다. 이미 끝낸 문장도 다시 연습할 수 있다. 처음 열 때는 아직 끝내지 않은 첫 문장부터.
- **기록**: 문장을 끝낼 때 `SPEAK` 이벤트 하나(오프라인이면 아웃박스에 쌓였다가 전송). 서버는 횟수만 남기고 아무 파생값도 만들지 않는다.
- 마이크를 쓸 수 없는 브라우저나 권한 거부면 그 이유를 보여 주고 **받아 적기**로 센다(2026-11-16, 마이크가 없으면 보충을 못 해 연속을 되살릴 수단이 없었다). 영어를 가린 채 문장을 듣고 적어 80% 이상이면 1회. 가운데 버튼은 문장 듣기가 되고, 어순 힌트도 보인다.
- 목록 순서: 오늘 레슨을 맨 위에, 나머지는 최근 Day 먼저(2라운드부터는 오늘 레슨 번호가 작아 맨 아래로 가던 것).
- 보충 행·화면의 안내는 F-05의 보충 효과(`effect`)로 말한다. 2라운드부터는 Day(레슨 번호)와 날짜가 달라, 할 일 시트는 "11/17 보충 · Day 2 문장 5번씩"처럼 날짜로 부른다.

---

## 2. TTS & 오디오 캐시

| 화자 | 보이스 | 속도 |
|---|---|---|
| A | `en-US-AndrewMultilingualNeural` | `-10%` |
| B | `en-US-AvaMultilingualNeural` | `-10%` |

- 파일 이름: `sha1("{voice}|{rate}|{en_text}")` 16진 앞 20자 + `.mp3`. **규칙은 Java(`tts.Utterance`)에만 있다.** Python 스크립트는 서버가 계산한 이름을 받아 쓴다.
- 저장 위치: `{app.data-dir}/audio/` (기본 `./data/audio/`). **디스크가 진실**이다. DB에 오디오 컬럼이 없고, 서버가 시작할 때 폴더를 읽어 길이를 메모리에 색인한다.
- `duration_ms`: MP3 프레임 헤더를 끝까지 세어 계산(외부 라이브러리 없음, Java `tts.Mp3Frames`). edge-tts 출력은 24kHz MPEG-2 Layer III.
- 받아들이는 조건: 프레임이 파일 바이트의 90% 이상을 덮고 길이 200ms 이상. 같은 이름의 유효한 파일이 이미 있으면 덮어쓰지 않고 성공으로 끝난다.
- 백엔드 생성: `app.tts.command`(기본 `python -m edge_tts`) + `--voice` `--rate=-10%` `--file <임시 txt>` `--write-media <임시 mp3>` → 검증 → 원자적 이동. 문장 텍스트는 파일로 넘겨 Windows 인자 따옴표 문제를 피한다. 단일 스레드이고, 작업 중 새로 생긴 작업은 끝난 뒤 한 번 더 스캔한다.
- 서버 시작 시 `app.tts.auto-generate: true`이면 없는 클립을 백그라운드에서 만든다.
- 서빙: `GET /api/v1/audio/{file}.mp3` (정적 리소스 핸들러, Range 지원, `Cache-Control: public, max-age=31536000, immutable`).

---

## 3. 데이터베이스 (SQLite, `data/habit66.db`)

스키마 원본: `backend/src/main/resources/schema.sql`. 이미 있는 표의 CHECK 제약을 넓혀야 하면 `common.SchemaMigrations`가 시작할 때 한 번 표를 새로 만들어 옮긴다(SQLite 는 CHECK 를 ALTER 할 수 없다. 2026-11-16 `via`에 `SPEAK` 추가). 날짜는 `TEXT 'YYYY-MM-DD'`, 시각은 epoch millis `INTEGER`. **사실만 저장하고 파생값(Day 번호, 게이트, 스트릭, 프리즈 사용)은 저장하지 않는다.** 테이블마다 쓰기 소유자는 하나다.

```
lesson(id PK, day_number UNIQUE, title, theme, created_at, updated_at)                 -- content
sentence(id PK, lesson_id FK, seq, speaker 'A'|'B', en_text, ko_word_order, ko_natural,
         is_core, created_at, updated_at, UNIQUE(lesson_id, seq))                       -- content
sentence_view(id PK, log_date, sentence_id FK, session 'TODAY'|'REVIEW', via 'AUDIO'|'CARD'|'SPEAK',
              event_id, created_at, UNIQUE(log_date, sentence_id, session, via))         -- progress
sentence_mark(sentence_id PK FK, status 'NEW'|'MEMORIZED'|'PRACTICE', changed_at)        -- progress, 최신값 우선
focus_session(started_at PK, log_date, seconds, updated_at)                              -- progress, 재전송 시 큰 값
morning_lock(log_date PK, state 'CLEARED'|'SKIPPED', decided_at)                         -- progress, 첫 결정 유지
day_achievement(log_date PK, lesson_day, via 'AUDIO'|'CARD'|'SPEAK', achieved_at)        -- progress, 완주 순간 1회(지금은 SPEAK 만)
reminder_log(log_date PK, status 'SENDING'|'SENT'|'FAILED', attempts, push_sent,
             webhooks_sent, updated_at)                                                  -- notify
push_subscription(id PK, endpoint UNIQUE, p256dh, auth, user_agent, created_at)          -- notify
app_setting(key PK, value)                                                               -- common (start_date)
term(id PK, term_key UNIQUE, text, meaning, nuance, examples_json, source 'CONTENT'|'GENERATED'|'MANUAL',
     status 'READY'|'PENDING'|'FAILED', attempts, created_at, updated_at)                -- vocab (내용 임포트도 이 표에 업서트)
sentence_term(sentence_id FK, term_id FK, form, PRIMARY KEY(sentence_id, term_id))        -- content (form: 문장 속 실제 형태)
vocab_entry(term_id PK FK, sentence_id FK NULL, note, mastered, mastered_at, added_at)    -- vocab (담긴 표현)
applied_event(id PK, applied_at)                                                         -- progress (처리한 VIEW·MORNING_LOCK 이벤트 id)
vocab_event(id PK, applied_at)                                                           -- vocab (처리한 VOCAB_* 이벤트 id)
speak_practice(id PK, sentence_id FK, log_date, reps, event_id UNIQUE, created_at)        -- progress (말하기 연습·보충을 끝낸 기록, F-09)
rest_day(log_date PK, declared_at)                                                       -- progress ("오늘은 어려워요", F-05)
```

---

## 4. REST API (`/api/v1`, JSON은 snake_case)

모든 프론트엔드 호출은 **상대 경로**다. 오류 응답: `{"error": "코드", "message": "설명"}`. 시각 필드(`*_at`, `server_time`, `valid_*`, `window_*`)는 모두 epoch ms 숫자다.

### 4.1 문장 (세션 응답 안의 모양)

```json
{ "id": 21, "day": 3, "seq": 1, "speaker": "A",
  "en_text": "Hi, what can I get for you today?",
  "ko_word_order": "안녕하세요, / 무엇을 제가 드릴 수 있을까요 / 오늘?",
  "ko_natural": "안녕하세요, 오늘은 뭘로 드릴까요?",
  "core": false,
  "status": "NEW", "status_changed_at": null,
  "audio_url": "/api/v1/audio/3f2a9c0d1e2b3a4c5d6e.mp3",
  "duration_ms": 2304, "duration_estimated": false,
  "terms": [ { "term_key": "what can i get for you", "text": "What can I get for you", "form": "what can I get for you",
               "meaning": "뭘로 드릴까요" } ] }
```

`terms`는 단어 고르기 화면용 가벼운 모양이다(뜻까지만). 전체 항목은 4.9 단어장 API에 있다.

### 4.2 대시보드

`GET /api/v1/dashboard`

```json
{
  "today": "2026-11-15",
  "server_time": 1763180000000,
  "valid_from": 1763143200000, "valid_until": 1763229600000,
  "current_day": 3, "total_days": 66, "lesson_day": 3, "max_lesson_day": 5,
  "content_missing": false, "round": 1, "round_day": 3,
  "streak": { "current": 2, "if_achieved_today": 3, "longest": 2, "achieved_today": false,
              "freezes_left": 2, "freezes_per_period": 2 },
  "today_progress": { "total": 10, "sentence_ids": [21, 22],
                      "audio_seen_ids": [21], "card_seen_ids": [21, 22], "spoken_seen_ids": [21] },
  "review_gate": { "required": true, "cleared": false, "yesterday_day": 2,
                   "unlock_ids": [11, 12], "seen_review_ids": [11] },
  "review_progress": { "days": [2, 1], "total": 20, "seen": 3 },
  "morning_lock": { "window_start": 1763154000000, "window_end": 1763166600000,
                    "state": "NONE", "sentences": [ /* 문장 × 3 */ ] },
  "focus": { "seconds_today": 1500, "started_ats": [1763170000000] },
  "rest_day": { "declared_today": false, "left": 4, "per_period": 4 },
  "makeups_due": [ { "day": 3, "date": "2026-11-15", "rest": false, "reps": 5, "deadline": "2026-11-18",
                     "sentence_ids": [21, 22], "done_ids": [21] } ]
}
```

- `makeups_due`: 아직 끝내지 않았고 끝내면 스트릭을 지키는 보충(F-05). 항목마다 `effect`·`streak_if_done`. 프론트는 아직 보내지 않은 `SPEAK`를 `done_ids`에 합친다. `rest_day.left`: 이번 30일 구간에 남은 "오늘은 어려워요" 횟수.

- `lesson_day = 0`이면(레슨 없음) 문장 목록은 비고 `content_missing = true`.
- `review_gate.required = false`면 `unlock_ids`는 빈 배열.
- `streak.if_achieved_today`: 오늘 달성하면 될 스트릭 값(오프라인 낙관 표시용).

### 4.3 세션

`GET /api/v1/sessions/today` · `GET /api/v1/sessions/review`

```json
{ "session": "TODAY", "days": [3], "title": "카페에서 주문하기", "preview": false,
  "sentences": [ /* 4.1 문장 */ ] }
```

잠금 여부는 세션에 없다. 게이트의 출처는 대시보드 `review_gate` 하나다.

### 4.4 이벤트 (모든 학습 쓰기)

`POST /api/v1/events`

```json
{ "events": [
  { "id": "6f1c…", "type": "VIEW", "occurred_at": 1763170000000,
    "sentence_id": 21, "session": "TODAY", "via": "SPEAK" },
  { "id": "a2b9…", "type": "MARK", "occurred_at": 1763170001000,
    "sentence_id": 21, "status": "MEMORIZED" },
  { "id": "focus:1763168000000", "type": "FOCUS", "occurred_at": 1763170002000,
    "started_at": 1763168000000, "seconds": 1620 },
  { "id": "c7d0…", "type": "MORNING_LOCK", "occurred_at": 1763155000000, "result": "CLEARED" }
] }
```

응답:

```json
{ "accepted": ["6f1c…", "a2b9…"], "rejected": [{ "id": "zz", "reason": "UNKNOWN_SENTENCE" }],
  "dashboard": { /* 4.2 */ } }
```

- `id`: 1~64자 `[A-Za-z0-9:._-]`. 같은 id를 다시 보내도 결과가 같다. 한 요청 최대 500개. 시각 필드에 소수가 오면 서버가 소수부를 버린다(클라이언트는 정수로 보낸다).
- `rejected[].reason`: `INVALID_ID` | `INVALID_TYPE` | `INVALID_FIELD` | `UNKNOWN_SENTENCE` | `REST_LIMIT`.
- `occurred_at`은 서버 시계 추정값(클라이언트가 `server_time`으로 보정). 서버는 미래 값을 수신 시각으로 자른 뒤 논리 날짜로 바꾼다. 그래서 응답을 잃고 롤오버를 지나 다시 보낸 이벤트는 다른 날짜로 계산될 수 있으므로, 처리한 `VIEW`·`MORNING_LOCK` id를 `applied_event`에 남겨(자연 키로 무시된 것 포함) 한 번만 반영한다.
- VIEW(TODAY)의 문장이 그 날짜의 오늘 레슨이 아니면 REVIEW로 바꿔 기록한다. 그 결과 오늘 레슨을 한 경로로 완주하면 `day_achievement`를 기록한다.
- MARK는 `changed_at`(= `occurred_at`)이 같거나 더 새로울 때 바꾼다(같은 시각이면 나중에 온 것). FOCUS는 `started_at` 기준으로 큰 `seconds`만 남기고(1~21600초), 그 기록의 날짜는 `occurred_at`(끝낸 시각) 기준이다. MORNING_LOCK은 그날 첫 결정만 남는다.
- 형식 오류·없는 문장은 그 이벤트만 `rejected`, 나머지는 한 트랜잭션으로 수용한다.
- 단어장 이벤트(F-08):
  - `{"type":"VOCAB_ADD", "text":"grab", "sentence_id":21, "meaning":null, "note":null}`: `text`의 `term_key`로 표현을 찾고, 없으면 `PENDING`으로 만들어 생성기에 맡긴다. 이미 담겨 있으면 무시(멱등). `meaning`을 주면 직접 입력으로 저장(`source: MANUAL`, 생성 안 함). `note`는 메모로만 저장한다(생성은 그대로). `sentence_id`가 현재 콘텐츠에 없으면 거절하지 않고 문장 연결만 버린다(거절하면 담기가 아웃박스에서 사라진다).
  - `{"type":"VOCAB_REMOVE", "term_key":"grab"}`: 단어장에서 뺀다(표현 자체는 남는다).
  - `{"type":"VOCAB_MASTER", "term_key":"grab", "mastered":true}`: 외웠어요 표시. 같은 시각이면 나중 것.
  - `{"type":"VOCAB_EDIT", "term_key":"grab", "meaning":"…", "note":"…"}`: 직접 고친 뜻·메모.
  - 단어장 이벤트는 순서에 따라 결과가 달라서(담기 → 빼기) 서버가 처리한 id를 기록하고, 같은 id가 다시 오면 적용하지 않고 `accepted`로 돌려준다.
- 말하기 연습 이벤트(F-09): `{"type":"SPEAK", "sentence_id":31, "reps":5}`. `reps`는 1~50(보충은 그 Day에 필요한 횟수). `event_id` UNIQUE로 한 번만 남긴다. 없는 문장은 `UNKNOWN_SENTENCE`. 보충을 끝내면 스트릭이 바뀔 수 있다(F-05).
- "오늘은 어려워요"(F-05): `{"type":"REST_DAY"}`. `occurred_at`의 논리 날짜를 `rest_day`에 남긴다. 그 30일 구간에 이미 4번이면 `REST_LIMIT`로 거절한다(이미 알린 날짜는 그대로 받는다). 롤오버를 지난 재전송이 다른 날짜로 들어가지 않게 id를 `applied_event`에 남긴다.

### 4.5 히트맵

`GET /api/v1/habit/heatmap`

```json
{
  "start_date": "2026-11-13", "current_day": 3, "round": 1, "round_day": 3, "round_start": "2026-11-13",
  "current_streak": 2, "longest_streak": 2, "achieved_days": 2, "freezes_left": 2,
  "cells": [ { "day": 1, "date": "2026-11-13", "status": "ACHIEVED" } ],
  "past_rounds": [ { "round": 1, "achieved_days": 58, "days": 66 } ]
}
```

- `cells`·`achieved_days`는 이번 라운드(`round_start`부터 66일), `day`는 라운드 안의 Day. `past_rounds`는 끝난 라운드마다 달성한 날 수(화면은 최근 3개와 누적 달성).

`status`: `ACHIEVED` | `FROZEN` | `REPAIRED` | `MISSED` | `TODAY` | `FUTURE`. 오늘 달성했으면 `ACHIEVED`. 정확히 66칸(이번 라운드).

### 4.6 Web Push

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/push/vapid-public-key` | `{"public_key":"BP…"}` |
| `POST` | `/push/subscribe` | 브라우저 `PushSubscription.toJSON()` 그대로(`endpoint`, `keys.p256dh`, `keys.auth`). 엔드포인트 기준 업서트. |
| `POST` | `/push/unsubscribe` | `{"endpoint":"…"}` |
| `POST` | `/push/test` | 모든 구독 + 웹훅에 시험 알림. `{"push_sent":1,"webhooks_sent":0}` |

푸시 페이로드: `{"title":"…","body":"…","url":"/?view=today","tag":"habit66-reminder"}`. 알림을 누르면 자동 재생 없이 오늘의 10문장을 연다(달성은 따라 말하기라서, 재생으로 열면 마이크가 없는 재생 독이 뜬다). 앱이 열려 있으면 서비스 워커 메시지로 오늘 탭만 바꾼다.

### 4.7 관리자 (선택 토큰: `app.admin-token`이 있으면 `X-Admin-Token` 헤더 필수)

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/admin/lessons/import` | 아래 형식. 응답 `{"lessons":5,"created":50,"updated":0,"warnings":[]}`. 이미 지난 날짜의 Day 인데 레슨이 없던 Day 를 새로 넣으면 `warnings`에 알린다(그날을 달성하지 않았다면 그날 일정이 이 레슨으로 바뀌어 보충 대상이 될 수 있다). 임포트는 그대로 한다 |
| `POST` | `/admin/backup` | 지금 DB 백업을 만든다. `{"file":"…/habit66-2026-11-16.db","bytes":123456,"extra_copy":null}` |
| `GET` | `/admin/tts/jobs` | 아직 없는 클립 목록 `{"jobs":[{"file":"…mp3","voice":"…","rate":"-10%","text":"…","sentence_ids":[1]}]}` |
| `PUT` | `/admin/audio/{file}` | 본문 `audio/mpeg` 바이트. `{"file":"…","duration_ms":2304,"created":true}`. 이미 있으면 `created:false`(200), 모르는 이름 404, 검증 실패 422 |
| `POST` | `/admin/tts/generate` | 백엔드 edge-tts로 없는 클립 생성 시작 `{"started":true}` |
| `GET` | `/admin/tts/status` | `{"running":false,"queued":0,"done":50,"failed":0,"last_error":null,"clips_total":50,"clips_missing":0}` |
| `POST` | `/admin/tts/sync` | 디스크를 다시 읽어 색인 `{"indexed":50}` |
| `GET` | `/admin/settings` | `{"start_date":"…","timezone":"Asia/Seoul","day_rollover_hour":3}` |
| `PUT` | `/admin/settings/start-date` | `{"start_date":"2026-11-13"}`. 오늘(논리 날짜)보다 미래이거나 10년 전보다 앞서면 400 |

임포트 형식(시드 파일과 같음):

```json
{
  "lessons": [
    {
      "day": 6, "title": "식당 예약", "theme": "Restaurant",
      "sentences": [
        { "speaker": "A", "en_text": "I'd like to make a reservation.",
          "ko_word_order": "저는 하고 싶어요 / 예약을",
          "ko_natural": "예약하고 싶어요.", "core": true,
          "terms": [
            { "text": "make a reservation", "form": "make a reservation",
              "meaning": "예약하다",
              "nuance": "식당·호텔·병원 자리를 미리 잡을 때 가장 흔한 표현. 구어에서는 book도 많이 쓴다.",
              "examples": [
                { "en": "I'd like to make a reservation for two.", "ko": "두 명 예약하고 싶어요." },
                { "en": "Did you make a reservation for tonight?", "ko": "오늘 저녁 예약했어?" },
                { "en": "You need to make a reservation in advance.", "ko": "미리 예약해야 해요." }
              ] }
          ] }
      ]
    }
  ]
}
```

`terms`(선택): 문장마다 0~3개. `text`는 사전형, `form`은 문장에 실제로 나온 형태(같으면 생략 가능, 대소문자 무시하고 `en_text` 안에 있어야 함). `examples`는 정확히 3개.

검증: `day` 1~999(66 뒤의 레슨도 넣을 수 있다), 문장 1개 이상, `speaker`는 `A`/`B`, 세 텍스트 필드는 비어 있으면 안 된다. 문장 순서가 `seq`(1부터)가 된다. 일차는 건너뛰어도 된다(`lesson_day` 정의 참고).

### 4.8 오디오

`GET /api/v1/audio/{file}.mp3`

### 4.9 단어장

`GET /api/v1/vocab`

```json
{ "entries": [
  { "term_key": "grab", "text": "grab", "meaning": "가볍게 먹다·사다", "nuance": "친구 사이 구어체. 격식 자리에서는 have나 get.",
    "examples": [ { "en": "Let's grab some coffee.", "ko": "커피 한잔하자.",
                    "audio_url": "/api/v1/audio/….mp3", "duration_ms": 1500, "duration_estimated": false } ],
    "status": "READY", "source": "CONTENT", "note": null,
    "mastered": false, "added_at": 1763170000000,
    "sentence": { "id": 43, "day": 5, "en_text": "How about grabbing dinner together?", "ko_natural": "같이 저녁 먹는 거 어때요?" } } ],
  "generator": { "state": "OK", "message": null, "expires_at": null } }
```

- 담긴 순서의 역순(최근 먼저). `status: PENDING`이면 뜻·예문이 비어 있을 수 있다(생성 중). `FAILED`면 직접 입력을 안내한다.
- 예문 음성은 `GET /admin/tts/jobs`에도 나와 스크립트로 채울 수 있다(담긴 표현의 예문만).
- `generator.state`: `OK` | `EXPIRING`(구독 로그인 만료 임박, `expires_at`) | `LOGIN_REQUIRED`(로그인이 끊겨 생성이 멈춤, `message`에 할 일) | `OFF`(`generator: none`). 메모리에만 있는 값이라 서버를 다시 켜면 시작 점검 결과로 다시 정해진다.
- `POST /api/v1/admin/vocab/retry`: `FAILED`·`PENDING` 표현 생성을 다시 시도. `{"queued": 2}`
- `POST /api/v1/admin/vocab/check-login`: 로그인을 지금 점검하고 `generator`와 같은 모양을 돌려준다(몇 초 걸린다).

설정(`application.yml`):

```yaml
app:
  vocab:
    generator: claude-cli        # claude-cli | anthropic-api | none
    claude-command: claude
    claude-model: haiku          # claude -p --model 값
    timeout-seconds: 120
    claude-oauth-token: ""       # claude setup-token 값. 프로젝트 루트 config/application.yml(커밋 안 됨)에 넣는다
    login-check-cron: "0 0 12 * * *"
    login-warn-days: 3
```

### 4.10 말하기 연습 (F-09)

`GET /api/v1/speak`: 오늘 레슨 일차까지의 Day 목록(최근 먼저).

```json
{ "lesson_day": 4,
  "days": [
    { "day": 4, "title": "길 묻기와 출퇴근", "sentence_ids": [31, 32], "done_ids": [], "state": "TODAY_LOCKED",
      "reps": 5, "seen": 3, "rest": false, "deadline": null, "repairable": false },
    { "day": 3, "title": "카페에서 주문하기", "sentence_ids": [21, 22], "done_ids": [21], "state": "MAKEUP",
      "reps": 5, "seen": 0, "rest": false, "deadline": "2026-11-18", "repairable": true } ] }
```

- `state`: `OPEN` | `MAKEUP` | `TODAY_LOCKED`(F-09). `reps`: 문장마다 맞게 말할 횟수. `seen`: `TODAY_LOCKED`일 때 오늘의 10문장 중 따라 말하기로 통과한 문장 수. `deadline`·`repairable`·`effect`·`streak_if_done`: `MAKEUP`일 때 기한과 끝냈을 때의 효과(F-05).
- `done_ids`: `OPEN`은 한 번이라도 연습을 끝낸 문장, `MAKEUP`은 보충으로 센 문장. 프론트는 아직 보내지 않은 `SPEAK` 이벤트의 문장과 합쳐 바로 보인다.

`GET /api/v1/speak/days/{day}`: 그 Day의 문장(4.1 모양, 음성 포함)과 위와 같은 `done_ids`·`state`·`reps`·`rest`·`deadline`·`repairable`. 오늘 레슨 일차를 넘거나 `TODAY_LOCKED`인 Day는 `404 SPEAK_DAY_LOCKED`.

---

## 5. 프론트엔드 화면

```
┌──────────── max-w-md, bg #F5F7FB ────────────┐
│ [히어로 카드: Day N/66 · 진행바 · 🔥 · ❄]  [타이머 카드: 모래시계 · 25:00 · 복습 n/m] │
│ (◔ 할 일 2/5)                 [▶ 오늘의 10문장 3/10]   (오늘 할 일)  │
│ [ 오늘의 10문장 · Day N | 누적 복습 · Day a–b ]   (세그먼트 탭)          │
│ ┌ 학습 카드 ───────────────────────────────┐ │
│ │ (A · Andrew)  3/10        [✓ 암기 완료]  │ │
│ │                                          │ │
│ │  What do you do in your free time?       │ │
│ │  무엇을 하나요 / 당신의 자유 시간에?        │ │
│ │  여가 시간에는 무엇을 하시나요?            │ │
│ │                                          │ │
│ │  ▂▅▇▅▂ 파형 / 따라 말해 보세요 2.1s        │ │
│ └──────────────────────────────────────────┘ │
│ [전체|어순힌트|한글만]                 (1.0x) │
│ [연속|끊어읽기|역할극]                  (⚙)  │
│        ( ◀    (● 56px 링)    ▶ )  플로팅 독     │
└──────────────────────────────────────────────┘
```

**오늘 할 일**(헤더와 세션 탭 사이 한 줄 + 체크리스트 시트): 화면만 보고 하루에 할 일을 알고, 하나씩 체크되는 맛을 주는 장치. 모두 **서버 값으로 자동 체크**한다(직접 체크하지 않는다). 판정은 `lib/todo.ts`.

| 항목 | 구분 | 체크 조건 |
|---|---|---|
| 모닝 스타터 | 보너스 | 오늘 모닝 락 `CLEARED`. `SKIPPED`이거나 아침 시간이 지났거나 락 전에 이미 학습했으면 "지남"(진행률에서 뺀다) |
| 어제 복습 | 필수 | 어제 복습 게이트가 열림(`n/m`). 어제 레슨이 없으면(Day 1) 항목 없음 |
| 오늘의 10문장 | 필수 | 오늘 습관 달성 |
| 25분 집중 | 보너스 | 오늘 집중 합계 ≥ 25분 |
| 새 단어 1개 담기 | 보너스 | 오늘 구간(`valid_from`~`valid_until`)에 담은 표현이 있음 |

- 줄: 왼쪽 `할 일 n/m`(진행 원, 누르면 시트), 오른쪽 **다음 할 일 버튼**(복습 → 오늘 10문장 → 집중 → 단어 순). 다 끝나면 `오늘 할 일 끝! N일째`.
  - 복습: 그 탭으로 가서 이번 재생만 `한글만` + `끊어읽기`로 하고 재생(재생 중이면 멈춤). 저장한 가리기 필터·재생 모드(자동 실행이 쓰는 값)는 바꾸지 않는다. 오늘 10문장(마이크 아이콘): 오늘 탭으로 가서 첫 미통과 문장을 들려준다(필터·재생 모드는 바꾸지 않는다).
  - 어제 복습 설명은 "Day N 문장을 해석과 함께 다시 보고 듣기"다(게이트는 열람 수로 열리고 말하기 통과를 요구하지 않는다).
  - 집중: 타이머 시작. 단어: 지금 문장의 단어 담기 시트.
- 시트: 진행 원 + 상태 문구(필수 남음 / 필수 끝 / 모두 끝, 끝나면 마스코트), 항목마다 체크 원·필수/보너스 표시·진행. 할 수 있는 항목을 누르면 바로 시작한다.
- 알림: 집중·단어가 끝나는 순간, 모두 끝낸 순간 토스트. 복습·오늘 10문장·모닝 스타터는 기존 알림(오늘의 10문장 열림, 습관 달성, 모닝 스타터 완료)을 쓴다.
- 레슨이 없거나 66일이 끝났으면 숨긴다. 새벽 3시(논리 날짜)가 바뀌면 새 목록.
- 가린 카드의 상태 줄은 할 일을 알려 준다: "해석(또는 어순)을 보고 영어로 말한 뒤, 탭해서 확인".

오버레이: 모닝 보이스 락(전면), 히트맵 바텀시트, 설정 바텀시트(푸시 알림 켜기·시험 발송, 루틴용 URL 복사), 자동 재생 차단 시 "탭해서 재생", 토스트.

---

## 6. PWA

- `manifest.json`: `display: standalone`, `start_url: /`, `theme_color: #F5F7FB`(캔버스와 같아 상태 표시줄이 화면과 이어진다, `index.html`의 `theme-color`도 같다), `background_color: #F5F7FB`, 아이콘 192/512(maskable 포함), 모드별 `shortcuts`.
- `sw.js`(직접 작성):
  - 앱 셸: 네비게이션은 네트워크 우선, 실패 시 캐시된 `/index.html`.
  - `/assets/*`, 폰트: 캐시 우선.
  - `/api/v1/audio/*`: 캐시 우선(`audio-v1`, 페이지 프리로더와 같은 캐시).
  - 그 밖의 `/api/*`: 네트워크만.
  - `push` → `showNotification`, `notificationclick` → 기존 창 포커스 또는 `url` 열기.
- Screen Wake Lock: 재생·대기 중이거나 타이머가 돌 때 요청, 화면 복귀 시 다시 요청.
- 실패한 열람 보고는 `localStorage` 큐에 쌓았다가 다음 성공 때 함께 보낸다.

---

## 7. 배포

- 개발: `backend`는 `gradlew bootRun`(작업 디렉터리 = 프로젝트 루트), `frontend`는 `npm run dev`(5173, `/api` → `localhost:8080` 프록시).
- 운영: `build-all.bat` → 프론트 빌드 → `dist/`를 `backend/src/main/resources/static/`로 복사 → `bootJar` → 루트에서 `java -jar`. 8080 한 포트로 SPA + API + 오디오를 모두 서빙한다.
- SPA 라우팅: `/api`가 아닌 확장자 없는 경로는 `index.html`로 보낸다.
- DB 자동 백업: 매일 `app.backup.cron`(기본 04:30, 롤오버 뒤)과 서버 시작 때(그날 것이 없으면) `VACUUM INTO`로 `data/backup/habit66-YYYY-MM-DD.db`(논리 날짜)를 만들고, 사본을 따로 열어 `PRAGMA integrity_check`가 `ok`인 것만 남긴다. 최근 `keep`(14)개 보관, 이름 규칙이 다른 수동 백업은 건드리지 않는다. `extra-dir`를 정하면 같은 사본과 `vapid.json`을 그곳에도 둔다. 실패해도 서버 동작에는 영향이 없다(로그만). 복원 방법은 `docs/pc-local-setup.md`.
- 외부 접속: `tailscale serve --bg 8080` → `https://<pc-name>.<tailnet>.ts.net/`. 서버는 기본으로 `127.0.0.1`에만 묶는다(`server.address`, 같은 Wi-Fi의 다른 기기가 관리자 API를 직접 부르지 못하게). LAN에서 직접 열어야 하면 `APP_SERVER_ADDRESS=0.0.0.0`과 관리자 토큰을 함께 쓴다.
