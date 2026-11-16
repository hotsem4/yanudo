---
name: clean-blue-ui
description: 66 Habit English 프론트엔드의 클린 라이트 & 로열 블루 디자인 시스템. frontend/ 안의 화면·컴포넌트를 만들거나 고칠 때, 색·여백·타이포·카드·필 세그먼트·플로팅 독·모션을 정할 때 반드시 먼저 읽는다.
---

# clean-blue-ui — Clean Light & Royal Blue

상용 학습 앱 수준의 밝고 산뜻한 UI. 기준 레퍼런스는 `refs/`의 세 장이다.

## 0. 레퍼런스에서 가져온 것

| 레퍼런스 | 가져온 요소 |
|---|---|
| `refs/9687.jpg` (Fluentify 학습 앱) | 로열 블루 히어로 카드, 흰 벤토 요약 카드 2칸(큰 숫자 + 작은 라벨), 하단 둥근 플로팅 내비, 마이크 원형 버튼을 품은 블루 패널, 필 모양 칩 |
| `refs/9697.jpg` (스마트홈 대시보드) | 아이스 블루 캔버스 위 흰 카드, 활성 카드만 블루로 채우기, 원형 게이지, 슬림한 세그먼트(Today/Week/Month) |
| `refs/9704.jpg` (스마트홈 탐색) | 큰 원형 링 게이지, 부드러운 블루 틴트 그림자, 흰 카드 안 아이콘 원형 배지 |

공통 톤: **흰 카드 + 아이스 캔버스 + 블루 한 가지 강조**. 테두리 대신 그림자와 면 색으로 구분한다. 숫자는 크고 굵게, 라벨은 작고 회색.

## 1. 토큰

Tailwind 설정(`frontend/tailwind.config.js`)에 이름으로 등록되어 있다. 새 색을 만들지 말고 이 이름을 쓴다.

| 토큰 | 값 | Tailwind | 용도 |
|---|---|---|---|
| canvas | `#F5F7FB` | `bg-canvas` | 앱 배경 |
| surface | `#FFFFFF` | `bg-white` | 카드 |
| royal | `#2B59FF` | `bg-royal` `text-royal` | 활성 버튼, 어순 힌트, 진행 게이지 |
| royal-soft | `#6B8BFF` | `to-royal-soft` | 그라데이션 끝 |
| tint | `#EEF2FF` | `bg-tint` | 비활성 필 탭, 보조 배지 |
| ink | `#111827` | `text-ink` `bg-ink` | 본문 제목, 플로팅 독 |
| muted | `#64748B` | `text-muted` | 해석, 라벨 (흰 바탕 전용, 4.76:1) |
| muted-strong | `#475569` | `text-muted-strong` | tint·canvas 바탕 위 보조 글자(비활성 탭, 연블루 행). muted 는 tint 위에서 4.26:1로 기준 미달 |
| faint | `#94A3B8` | `text-faint` | 보조 수치, 비활성 아이콘 |
| line | `#E8EDF7` | `bg-line` | 진행 바 바탕, 히트맵 빈칸 |

성공·완료 상태도 초록 대신 royal로 표현한다.

그림자·그라데이션:

```
shadow-card   = 0 8px 30px rgba(43,89,255,0.06)     카드 기본
shadow-lift   = 0 14px 40px rgba(43,89,255,0.14)    눌림 직전·모달
shadow-glow   = 0 10px 30px rgba(43,89,255,0.35)    56px 재생 버튼
bg-hero       = bg-gradient-to-br from-royal to-royal-soft
```

## 2. 타이포그래피

폰트: `Inter`(라틴) → `Pretendard Variable`(한글) 순서. `font-sans`에 이미 묶여 있다. 숫자에는 `tabular-nums`.

| 역할 | 클래스 |
|---|---|
| 영어 원문 | `text-[25px] font-bold tracking-tight leading-[1.35] text-ink` |
| 어순 직역 힌트 | `text-[16px] font-semibold text-royal mt-4` |
| 자연스러운 해석 | `text-[14px] text-muted mt-1.5` |
| 벤토 큰 숫자 | `text-[26px] font-bold tracking-tight tabular-nums` |
| 벤토 라벨 | `text-[12px] font-medium text-muted` (히어로 위에선 `text-white/75`) |
| 칩·세그먼트 | `text-[12.5px] font-semibold` |
| 모달 제목 | `text-[19px] font-bold text-ink` |

한 화면에 굵기 3단계(700 / 600 / 400~500)만 쓴다.

## 3. 레이아웃

```
<main class="max-w-md mx-auto min-h-dvh bg-canvas flex flex-col justify-between px-5 py-6">
  헤더(벤토 2칸) → 세션 탭 → 학습 카드(flex-1) → 컨트롤 1단(필 2줄) → 플로팅 독
```

- 세로 간격은 `gap-4`(16px) 기본, 덩어리 사이 `gap-5`.
- 화면 면적의 40% 이상은 비워 둔다. 카드 안 문장 주변에 최소 `py-8`.
- 플로팅 독은 `fixed bottom-[max(16px,env(safe-area-inset-bottom))]`이고, 본문은 독 높이(72px) + 16px만큼 아래 패딩을 둔다.

### 갤럭시 폴드 8: 화면 두 종류

| 상태 | 조건 | 배치 |
|---|---|---|
| 접은 커버 화면 | 폭 600px 미만 (Tailwind 기본) | 한 열 `max-w-md`: 헤더 → 세션 탭 → 학습 카드 → 컨트롤 2줄 → 하단 독 |
| 펼친 내부 화면 | 폭 600px 이상 (`unfolded:` 접두사) | 두 열 `max-w-[960px]`, 왼쪽 5(최소 280px) : 오른쪽 7. 왼쪽 = 벤토 헤더 → 세션 탭 → 문장 지도 → 컨트롤(바닥까지). 오른쪽 = 학습 카드(높이를 채움) + 그 열 가운데 아래 하단 독 |

- 펼친 화면은 세로가 짧고 정사각형에 가깝다. 오른쪽 학습 카드가 세로 공간을 가져가고, 왼쪽 열은 스크롤 없이 들어가야 한다.
- 펼친 화면의 독은 화면 가운데가 아니라 **학습 카드 열 가운데 아래**에 둔다(`.dock-rail`). 화면 가운데는 힌지 위라 양손으로 잡았을 때 엄지가 가장 닿기 어렵고, 독이 두 열에 걸치면 왼쪽 열까지 독 높이만큼 잘린다. 문장과 재생 버튼이 한 기둥으로 묶인다.
- 왼쪽 열 가운데는 **문장 지도**(`SentenceMap`)가 채운다. 세로가 640px 미만이면 지도를 숨기고 화면 높이 고정도 푼다.
- 접은 화면에는 지도 자리가 없으므로 학습 카드 안의 얇은 진행 레일이 같은 정보(지금 위치, 암기한 문장)를 준다. 펼친 화면에서는 레일을 숨긴다(같은 정보를 두 번 그리지 않는다).
- 펼친 화면에서도 영어 원문 크기는 25px을 유지하고(가독 거리 동일), 여백을 늘려 40% 여백 원칙을 지킨다.
- 바텀시트는 펼친 화면에서 `max-w-lg` 가운데.
- 확인은 폴드8 실측 크기로 한다(2026-11-16 사양 확인: 커버 5.5인치 1248×1972, 메인 7.6인치 2448×1848 4:3, 기기 픽셀 비율 3). 설치한 PWA는 상태 표시줄·제스처 바만큼 세로가 줄어든다.
  - 커버 화면: **416×613** (브라우저 전체 416×657)
  - 펼친 가로: **816×572** (전체 816×616), 펼친 세로: **616×772** (전체 616×816)
  - 펼친 세로(폭 600~699px)는 왼쪽 열이 최소폭 280px이 되므로 긴 라벨·5칸 격자가 좁아진다. 이 구간은 `unfolded:max-[699px]:`로 따로 맞춘다.
  - 실제 기기에서 다시 본다.

## 4. 컴포넌트 레시피

### 카드
```tsx
<div className="rounded-3xl bg-white shadow-card p-5">…</div>
```
- 테두리 금지. 구분이 더 필요하면 면 색(`bg-tint`)이나 그림자를 키운다.

### 히어로 벤토 카드 (Day N / 66, 스트릭)
```tsx
<button className="rounded-3xl bg-hero text-white p-4 shadow-card text-left">
  <span className="text-[12px] text-white/75">66일 습관</span>
  <span className="text-[26px] font-bold tabular-nums">Day 3<span className="text-white/60 text-[15px]"> / 66</span></span>
  <div className="h-1.5 rounded-full bg-white/25"><div className="h-full rounded-full bg-white" style={{width}} /></div>
  <span><Flame /> 연속 2일</span>  <span><Snowflake /> 2</span>   {/* 가운뎃점으로 잇지 않고 따로 배치 */}
</button>
```
- 장식(흐린 원, 반짝이 등)을 넣지 않는다. 그라데이션 자체가 깊이다(hallmark sloplint가 흐린 원을 AI 장식으로 경고해 제거함).
- 벤토 두 카드는 같은 문법으로 쌓는다: 첫 줄(높이 24px) 왼쪽 라벨 + 오른쪽 작은 아이콘(히어로 `CalendarDays`, 타이머 모래시계) → 큰 숫자 → 6px 게이지 → 하단 한 줄. 두 카드의 라벨·숫자·게이지·하단 줄이 같은 높이에 온다.
- 타이머 게이지는 지나간 비율을 `scaleX`로 채운다(시작 전에는 비어 있다). 꽉 찬 royal 막대를 히어로 옆에 세우지 않는다.

### 오늘 할 일 (헤더와 세션 탭 사이 + 체크리스트 시트)
- 한 줄 `h-11 justify-between`, 세션 탭과 같은 영역(`area-tabs`, `flex-col gap-3`)에 둔다(없는 날 빈 줄이 생기지 않게).
- 왼쪽 진행 칩: `h-11 rounded-full bg-surface shadow-card` + 진행 원 30px(`stroke-line` 바탕, `stroke-royal` 진행) + `할 일 n/m`(n 은 royal, /m 은 muted).
- 오른쪽 다음 할 일 버튼: `h-11 rounded-full bg-royal text-surface text-[13px] font-semibold shadow-lift` + 아이콘(재생·멈춤·타이머·책갈피) + `n/m`. 화면에서 로열 블루로 채운 버튼은 이것과 활성 필 두 가지뿐이다. 다 끝나면 버튼 대신 `Flame + 오늘 할 일 끝! N일째`(royal 글자).
- 시트 행: `min-h-14 rounded-2xl`, 할 수 있는 항목은 `bg-canvas` 버튼 + `ChevronRight`, 끝난 항목은 `bg-tint`, 지난 항목은 글자 `text-faint`. 체크 원 28px: 끝남 `bg-royal` + 흰 체크(스프링으로 커지며 채워짐), 아직 `bg-surface ring-2 ring-inset ring-line`, 지남 `bg-line`. 필수 태그 `bg-tint text-royal`, 보너스 `bg-canvas text-muted-strong`.
- 확인 크기: 커버 416px 에서 칩 20~129, 버튼 235~396. 펼친 세로 616px(왼쪽 열 280px)에서 칩과 버튼 사이 10px.

### 필 세그먼트 (가리기 필터 / 오디오 모드 / 세션 탭)
- 바탕: `bg-white rounded-full p-1 shadow-card`
- 항목: `flex-1 h-9 rounded-full text-[12.5px] font-semibold`
- 활성 표시는 `translateX`로 미끄러지는 `bg-royal` 알약(§5), 활성 글자 `text-white font-semibold`, 비활성 글자 `text-muted font-medium`.
- 세션 탭은 바탕을 `bg-tint`로 하고 활성 알약을 `bg-white text-royal shadow-card`로 한다(1차 탭과 2차 컨트롤을 구분). 비활성 탭은 `text-muted-strong font-medium`. 대비 때문에 색을 더 옅게 할 수 없으니 굵기로 활성보다 한 단계 물린다.

### 원형 보조 칩 (배속, 설정)
`h-12 min-w-[56px] px-3 rounded-full bg-white shadow-card text-royal text-[12.5px] font-bold`
- 캔버스 위 `bg-tint`는 거의 보이지 않는다. 옆 세그먼트와 같은 흰 면·같은 높이(48px)로 맞춘다. `bg-tint` 칩은 흰 카드 안에서만 쓴다.

### 화자 칩
- A(Andrew): `bg-tint text-royal`
- B(Ava): `bg-royal text-white`
- 모양: `h-7 px-3 rounded-full text-[12px] font-semibold` + 원형 이니셜.

### 암기 토글
- 미완료: `bg-tint text-royal` + `Check` 아이콘 "암기"
- 완료: `bg-royal text-white` + `CheckCheck` "암기 완료"
- 다시 연습: `bg-white text-muted shadow-card` + `RotateCcw` "다시 연습"

### 어순 조각 (시그니처)
```tsx
<p className="mt-4 -mb-1.5 break-keep text-[16px] font-semibold text-royal">
  <span className="mb-1.5 inline-block max-w-full rounded-[10px] bg-tint px-2 py-1 align-top">무엇을 하나요</span>{" "}
  <span className="…">당신의 자유 시간에?</span>
</p>
```
- `ko_word_order`를 `/`로 나눈 덩어리마다 연블루 조각 하나. 슬래시는 그리지 않는다. 영어 어순이 블록으로 보인다.
- 조각은 `inline-block`이라 조각째 줄을 넘기고, `break-keep`으로 어절 가운데서 끊지 않는다. 해석 문장도 `break-keep`.
- 이 화면에서 대담한 요소는 이것 하나다. 다른 곳에 조각 모양을 쓰지 않는다.

### 진행 레일 (접은 화면, 학습 카드 상단 행 아래)
문장마다 `h-1 flex-1 rounded-full` 한 칸, 간격 3px. 지금 `bg-royal`, 암기 `bg-frost`, 나머지 `bg-line`. `aria-hidden`(수치는 옆의 "n / total"이 읽어 준다). `unfolded:hidden`.

### 문장 지도 (펼친 화면 왼쪽 열)
- 흰 카드 `rounded-3xl p-4`. 머리: 라벨 "암기한 문장" + 큰 숫자 `n / total`(벤토 숫자 문법), 오른쪽에 범례(지금·암기).
- 5열 격자, 타일 `h-12 rounded-2xl`, 간격 8px. 지금 `bg-royal text-white font-bold`, 암기 `bg-frost text-ink font-semibold`, 나머지 `bg-canvas text-muted-strong`.
- 타일을 누르면 `audioEngine.go(index)`. 잠긴 세션에서는 `disabled`.
- 카드는 내용 높이만큼만 차지하고, 문장이 많아 넘치면 격자만 `.thin-scroll`로 스크롤한다.
- 영어 원문은 넣지 않는다(가리기 필터로 암송하는 중에 답이 보이면 안 된다).

### 플로팅 필 독 (하단 2단)
```tsx
<div className="rounded-full bg-ink text-white shadow-xl h-[72px] px-3 flex items-center justify-between w-[260px] mx-auto">
  [이전 44px 아이콘]  [56px bg-royal 원형 + SVG 링]  [다음 44px 아이콘]
</div>
```
- 독 안에는 이 세 개만 둔다. 다른 버튼을 추가하지 않는다.
- 자리는 `.dock-rail`이 정한다: 접은 화면은 화면 가운데, 펼친 화면은 학습 카드 열 가운데(§3). "탭해서 재생 시작" 알약도 같은 레일을 쓴다.
- 링: 반지름 31, 두께 3, 바탕 `white/15`, 진행 `#FFFFFF`. 무음 대기 때만 줄어든다.

### 음성 파형
- 막대 5~7개, `w-1 rounded-full bg-royal`, 높이는 스프링 반복 애니메이션.
- 재생 중: 막대가 빠르게 움직임 + "듣는 중".
- 사용자 차례(무음 대기): 막대가 `bg-royal/40`로 느리게 숨쉬기 + "따라 말해 보세요 · 2.1s".

### 잠금 / 블러
- 가리기: `filter: blur(9px)` + `select-none`, 0.35초 스프링 전환.
- 잠긴 카드: 내용은 `blur(10px)` + `opacity-30`으로 물리고(패널 글자와 겹쳐 읽히지 않게), 그 위에 흰 반투명(`bg-white/75 backdrop-blur-sm`) 패널. 가운데 76px 링 게이지(바탕 `stroke-line`, 진행 `stroke-royal`, 두께 5) 안에 `Lock`, 그 아래 제목 + 진행 수치 + `bg-royal` 필 버튼(48px). 링은 어제 복습 `seen/total`.
- 잠긴 동안 카드의 상단 행·문장·상태 줄은 `aria-hidden`(패널이 유일한 내용).

### 바텀시트 모달
`fixed inset-x-0 bottom-0 max-w-md mx-auto rounded-t-[32px] bg-white shadow-lift`, 배경 `bg-ink/30 backdrop-blur-[2px]`. 위쪽에 `w-10 h-1.5 rounded-full bg-line` 손잡이.
- 패널은 `flex flex-col overflow-hidden`. 손잡이·제목은 고정(`shrink-0 px-6 pt-3`)이고 **스크롤은 그 아래 본문 래퍼만** 한다(`thin-scroll mr-1.5 min-h-0 flex-1 overflow-y-auto overscroll-contain pl-6 pr-[18px]`). 패널 자체를 스크롤하면 스크롤바가 둥근 모서리 바깥에 붙어 양 끝이 네모로 잘린다.
- `.thin-scroll`: 마우스 환경에서 폭 6px, 둥근 `line` 색 손잡이, 투명 트랙, 화살표 버튼 없음. 터치 기기는 기본 오버레이 바를 그대로 쓴다. Chromium은 `scrollbar-width`가 있으면 `::-webkit-scrollbar`를 무시하므로 표준 속성은 `@supports not selector(::-webkit-scrollbar)` 안에만 둔다.

### 히어로 전면 오버레이 (모닝 보이스 락)
배경 `bg-hero`, 흰 반투명 카드(`bg-white/15 backdrop-blur rounded-3xl`), 가운데 88px 흰 원형 마이크 버튼(`bg-white text-royal`), 듣는 동안 바깥으로 퍼지는 원(`bg-white/20` scale 반복).

### 히트맵
- 11열 × 6행, 칸 `aspect-square rounded-[10px]`.
- 달성 `bg-royal`, 프리즈 `bg-[#BFD0FF]` + 눈송이, 놓침 `bg-line`, 오늘 `ring-2 ring-royal ring-offset-2`, 미래 `bg-tint`.

## 4-1. 캐릭터 포롱

알에서 깨어 66일 동안 파랑새로 자라는 캐릭터. 습관이 쌓이는 것을 눈으로 보여 주는 것이 유일한 역할이다.

| 항목 | 규칙 |
|---|---|
| 단계 | `current_day`가 지난 이정표 수. Day 1 알 / 3 금 간 알 / 7 아기 새 / 21 솜털 새 / 42 날개 편 새 / 66(또는 `program_complete`) 완주한 파랑새. 하루를 놓쳐도 되돌아가지 않는다 |
| 기분 | 완주 `party` → 오늘 달성 `done` → 스트릭 0이고 Day 2 이후 `sleepy` → 그 밖 `wait`. 졸린 표정은 혼내는 것이 아니라 깨워 달라는 것 |
| 계산 | `lib/mascot.ts`의 순수 함수만 쓴다. 서버가 준 값만 읽고 날짜를 계산하지 않는다 |
| 그림 | `components/Mascot.tsx`. 색은 토큰 클래스만(`fill-royal`, `fill-royal-soft`, `fill-frost`, `fill-surface`, `fill-ink`, `fill-line`). 새 색을 더하지 않는다 |
| 움직임 | `mascot-bob`(숨쉬기), `mascot-hop`(달성), `mascot-twinkle`(반짝임). transform·opacity만. 감소 모션이면 정지 |

등장 위치는 아래가 전부다. 학습 중인 화면(카드, 컨트롤, 독)에는 넣지 않는다.

| 자리 | 크기 | 비고 |
|---|---|---|
| 히어로 카드 오른쪽 위 흰 원 배지 | 원 38px(그림 32px) | 메인 화면의 유일한 자리. `still`(움직이지 않음). 달력 아이콘을 대신한다 |
| 66일 기록 시트 머리 | 96px | 지금 모습 + 다음 이정표까지 남은 날 + 여섯 단계 성장 띠(지금 단계는 링, 아직인 단계는 `opacity-30`). 이정표 칸에는 Day 숫자 |
| 성공 토스트 | 32px | 달성·게이트 열림·성장 축하 |
| 빈 화면·오류 카드 | 76px | 원형 아이콘 배지 자리. 오류 카드는 마지막으로 본 단계로 졸고 있다 |
| 모닝 스타터 오버레이 | 96px(흰 원 104px) | 파란 바탕이라 흰 원 위에 세운다 |

- 장식으로 놓일 때는 `aria-hidden`, 뜻을 전하는 자리(기록 시트 머리)는 `label`을 줘 `role="img"`로 읽힌다. 히어로 버튼은 `aria-label`에 단계와 기분을 덧붙인다.
- 단계가 오른 날에는 "포롱이 자랐어요" 토스트를 한 번만 띄운다. 본 단계는 localStorage(`habit66.mascot.stage.v1`)에 기억하고, 기록이 없거나 저장소를 쓸 수 없으면 축하를 건너뛴다(되풀이하지 않는 쪽).

## 4-2. 단어장 (SPEC F-08)

세션 탭의 세 번째 `단어장`. 재생 큐와 무관한 별도 화면이라 선택 상태는 App이 들고 URL은 `?view=vocab`이다(`playback`의 탭은 TODAY/REVIEW 그대로).

| 조각 | 규칙 |
|---|---|
| 단어 버튼 | 학습 카드 윗줄, 암기 토글 왼쪽. `h-9 w-9` 원 + `.hit`(48dp), 토글과 간격 12px. 기본 `bg-tint text-royal` + `BookmarkPlus`, 이 문장에서 담은 단어가 있으면 `bg-royal text-white` + `BookmarkCheck`. 잠금 중 `disabled`. 카드 탭 영역(문장) 밖에 둔다 |
| 단어 담기 시트 | 바텀시트. 문장(`bg-canvas`) → "이 문장의 표현"(행 버튼 `min-h-14 rounded-2xl`, 표현 + 뜻) → "나머지 단어"(칩 `h-12 rounded-full`) → "단어장 보기". 담긴 것은 `bg-royal text-white` + 체크, `aria-pressed`. 누르면 담기/빼기 토글 |
| 단어 칩 나누기 | `lib/vocab.ts`의 `sentenceWords()`: 구두점 제거, 중복 제거, 미리 만든 표현에 든 단어 제외 |
| 단어장 카드 | 학습 카드 자리(`rounded-stage p-6`). 위에 필터 세그먼트(`variant="tab"`, 라벨에 개수) → 카운터와 빼기 → 본문 → 하단 `이전 48px / 외웠어요 필 / 다음 48px` |
| 앞면 | 표현 34px bold, 아래 13.5px 안내. 누르면 뒷면 |
| 뒷면 | 표현 22px(누르면 앞면) → 뜻 18px semibold royal + 고치기 → 쓰임 느낌 14px muted → 내 메모 → 예문(듣기 버튼 48px + 영어 15px semibold + 한국어 13px) → "Day N에서 처음 만난 문장"(`bg-tint`). 길면 카드 안에서만 스크롤(`.thin-scroll`) |
| 상태 | `PENDING` 스켈레톤 + "뜻을 만드는 중…" + 직접 적기, `FAILED`·뜻 없음은 입력 폼(뜻·메모, 입력 글자 16px), 빈 상태는 포롱 + 학습 카드로 가기, `view.vocab === null`은 "아직 받지 못했어요" + 다시 받기 |
| 단어 목록 | 펼친 화면 왼쪽 열(문장 지도 자리). 행 `min-h-14 rounded-2xl`: 표현 + 뜻 한 줄, 지금 단어는 `bg-tint` |
| 빼기 | 바로 빼고 5초 토스트에 "되돌리기" |

- 단어장에서는 가리기·재생 모드 컨트롤을 그리지 않는다(`.app-grid--vocab`). 화면 높이를 고정해 카드 안에서만 스크롤한다.
- 단어장에서는 하단 독을 숨긴다(`.app-grid--nodock`). 독은 학습 큐를 움직이므로 단어 카드의 이전·다음과 헷갈린다. 학습 오디오가 나오는 중이면 멈출 수 있게 독을 남긴다.
- 영어 표현과 예문에는 `lang="en" translate="no"`.
- 넘기기는 학습 카드와 같다: 스와이프(80px 또는 속도), 이전·다음 버튼, 키보드 좌우 화살표. 뒤집기는 160ms 크로스페이드(자주 하는 동작이라 3D 회전을 쓰지 않는다).

## 4-3. 말하기 연습 (SPEC F-09)

세션 탭의 네 번째 `말하기`. 단어장과 같은 별도 화면 틀(`.app-grid--vocab`, 독·컨트롤 줄 없음, URL `?view=speak`)을 쓴다. 오늘 할 일 줄은 숨긴다. 빼먹은 날의 보충(SPEC F-05)도 여기서 한다.

| 조각 | 규칙 |
|---|---|
| Day 목록 | 행 `min-h-14 rounded-2xl bg-canvas`(고른 행 `bg-tint`): `Day N` 13px bold royal + 제목 + 오른쪽 `done/total` 또는 체크 + "연습 완료"/"보충 완료". 보충할 Day는 제목 아래 12px royal 한 줄(`RotateCcw` + "보충 5번씩 · 11/18까지 하면 연속 유지", 지킬 연속 기록이 없으면 "보충 5번씩 하면 열려요"). 오늘 Day가 잠겼으면 버튼이 아닌 행(`Day N` faint, 제목 muted, `Lock` + "오늘 3/10"). 보충할 Day가 있으면 목록 위에 13px 안내 한 줄. 펼친 화면은 왼쪽 열(세로가 낮아도 보인다), 접은 화면은 카드 안(`SpeakHome`) |
| 연습 카드 | 학습 카드 자리(`rounded-stage p-6`). 머리 줄: (접은 화면만) 목록으로 + `Day N · 제목` + `i / 10` + 듣기 `h-9 w-9` 원 → (보충이면) 12.5px royal 한 줄 "보충 5번씩 · 11/18까지 하면 연속 유지" 또는 "… · 끝내면 이 Day 가 열려요"(truncate, 툴팁에만 숨기지 않는다). 1번 보충은 점 대신 "한 번만 맞게 말하면 돼요" → 문장 진행 막대(끝낸 문장 royal, 지금 문장 royal/40) → 가운데 스크롤 영역(영어 25px, 보충이면 어순 힌트 16px semibold royal, 해석 14px) → 점 5개 + `n/5` → 하단 `이전 48px / 마이크 64px bg-royal shadow-glow / 다음 48px`(`max-w-[280px] justify-between`) → 상태 문구 13px |
| 판정 | 말한 뒤 맞힌 단어는 ink, 놓친 단어는 faint. 1회 성공하면 점 하나가 채워지고 0.7초 뒤 다시 듣기 시작, 5회면 1초 뒤 다음 문장 |
| 끝 | Day를 다 끝내면 포롱(party) + "처음부터 다시" + (접은 화면) "다른 Day 고르기". 보충이면 "연속 기록이 이어졌어요" |
| 오늘 할 일 시트 | 목록 아래 "보충하면 연속 기록이 이어져요"(행 `min-h-14 rounded-2xl bg-tint`, 누르면 말하기 탭의 그 Day) → "오늘은 어려워요"(`h-11` 필 `bg-canvas`, `Moon`). 누르면 같은 자리에 확인 상자(`bg-canvas p-3.5`, "쉬는 날로 알리기" royal 필 + "취소"). 알린 뒤에는 안내 문단으로 바뀐다. 오늘 달성했으면 그리지 않는다 |
| 라운드(66일 뒤) | 헤더 라벨 `N라운드 k/66`, 큰 숫자는 누적 `Day N`(분모 없음), 막대는 라운드 진행. 히트맵 제목 `N라운드 기록`, 통계 "누적 달성", 칸 아래 12.5px 캡션 "N라운드 k일째 · 이번 라운드 n일 달성", 지난 라운드는 최근 3개(`bg-canvas` 행). 2라운드부터 칸의 이정표 숫자(포롱 성장일)는 숨긴다. 끝을 암시하는 말("완주", "끝까지")은 쓰지 않는다 |
| 히트맵 | `REPAIRED`(보충으로 지킴)는 `bg-tint` + `ring-royal/30` + `RotateCcw` 11px royal(프리즈 칸과 구분) |

- 펼친 화면 616px 폭에서 카드 안쪽이 200px이라 하단 줄은 버튼 3개까지만 둔다(듣기는 머리 줄).

## 5. 모션 (emil-design-eng 기준)

움직이기 전에 "얼마나 자주 보나"를 먼저 묻는다. 자주 보는 것일수록 짧게, 또는 움직이지 않는다.

| 대상 | 빈도 | 처리 |
|---|---|---|
| 버튼·칩 누름 | 매우 잦음 | `active:scale-[0.96]`(make-interfaces-feel-better 고정값, 0.95 미만 금지), `transition-transform` 160ms `--ease-out` |
| 재생↔일시정지 아이콘 | 잦음 | 두 아이콘 교차: scale 0.25→1, opacity 0→1, blur 4px→0, spring `duration 0.3, bounce 0` |
| 세그먼트 알약 | 잦음 | 같은 폭 항목이므로 `translateX(index*100%)` CSS 트랜지션 220ms. framer `layoutId`(메인 스레드) 쓰지 않음 |
| 카드 넘기기 | 잦음 | 24px 슬라이드 + 투명도 + 2px 블러로 교체를 가림, 200ms. 스와이프는 거리 80px 또는 속도 0.11px/ms 이상이면 넘김 |
| 가리기 블러 해제 | 잦음 | `filter`·`opacity` 250ms ease |
| 진행 바 | 상태 | `scaleX` + `origin-left` 300ms `--ease-out` |
| 바텀시트 | 가끔 | 열림 420ms `--ease-drawer` `translateY(100%→0)`, 닫힘 260ms(닫힘이 더 빠르게) |
| 토스트 | 가끔 | 위에서 `translateY(-100%)` → 0, 400ms ease, 같은 방향으로 퇴장 |
| 모닝 락 마이크 퍼짐 | 드묾 | 듣는 동안만 반복, 감소 모션이면 정지 |

- 커브: `--ease-out: cubic-bezier(0.23,1,0.32,1)`, `--ease-in-out: cubic-bezier(0.77,0,0.175,1)`, `--ease-drawer: cubic-bezier(0.32,0.72,0,1)`. 기본 `ease-in` 금지.
- `scale(0)`에서 시작 금지. 등장은 `scale(0.95)` + `opacity:0`부터.
- hover 효과는 `@media (hover:hover) and (pointer:fine)` 안에서만(Tailwind `[@media(hover:hover)]:hover:`).
- framer-motion 11은 `x`/`y` 축약형이 메인 스레드에서 돈다. 반복 애니메이션은 CSS 또는 `transform` 문자열로.
- `prefers-reduced-motion`: 이동·크기 변화를 없애고 투명도·색 전환만 남긴다.

## 5-1. 마감 디테일 (make-interfaces-feel-better)

- 겹친 모서리는 동심원: 바깥 반경 = 안쪽 반경 + 여백. 예) 세그먼트 바탕 `rounded-full p-1` 안 알약 `rounded-full`, 학습 카드 `rounded-stage`(32px) `p-6` 안의 잠금 패널은 `rounded-[8px]`가 아니라 카드 전체를 덮고 같은 32px.
- 재생 삼각형(`Play`)은 오른쪽으로 1.5px 옮겨 광학 중심을 맞춘다.
- 아이콘 선 굵기는 옆 글자 굵기에 맞춘다: 600 옆 `strokeWidth={2}`, 400~500 옆 `1.75`. 아이콘은 lucide 한 벌만, `currentColor`로 상태 색을 바꾼다. 활성 상태만 채움.
- 바뀌는 숫자(타이머, 진행 수, Day)는 모두 `tabular-nums`.
- 제목성 글은 `text-balance`, 해석 문장은 `text-pretty`.
- `AnimatePresence initial={false}`로 첫 렌더 등장 애니메이션을 막는다.
- `will-change`는 첫 프레임 끊김이 보일 때만, transform/opacity/filter에만.
- 모션만으로 상태를 알리지 않는다. 색·아이콘·문구 중 하나가 함께 바뀐다.

## 6. 모바일 네이티브 UX

- `-webkit-tap-highlight-color: transparent`, `touch-action: manipulation`, `overscroll-behavior: none`.
- 터치 대상은 안드로이드 기준 48dp에 맞춘다. 세그먼트는 `p-1` 바탕 + 항목 `h-10`(합계 48px), 칩은 `h-10`, 독 아이콘 버튼은 48px. 인접 터치 대상 사이는 8px 이상.
- 대비: 글자는 4.5:1 이상. `faint`(#94A3B8)는 흰 바탕에서 약 2.6:1이라 아이콘·장식에만 쓰고 글자에는 `muted` 이상을 쓴다. 히어로 그라데이션의 밝은 끝(#6B8BFF) 위에는 작은 흰 글자를 두지 않는다(카드 왼쪽 위 royal 쪽에 배치).
- 아이콘은 lucide SVG. 🔥 같은 이모지는 숫자 옆 장식으로만 쓰고, 의미는 텍스트("연속 2일")로도 전한다. 아이콘만 있는 버튼에는 `aria-label`.
- 포커스 링: `focus-visible:ring-2 ring-royal ring-offset-2`. 포커스 표시를 지우지 않는다.
- 움직임은 한 화면에 핵심 1~2개(카드 교체, 재생 버튼 링). 나머지는 색·투명도 전환만.
- 텍스트 선택은 문장 영역에서만 허용하지 않는다(`select-none`), 설정 시트의 URL은 허용.
- 재생·대기·타이머 중에는 Screen Wake Lock.
- 상태 바 색 `theme-color #F5F7FB`(히어로 오버레이 동안 `#2B59FF`).

## 6-1. 함께 쓰는 디자인 스킬과 이 프로젝트에서의 결정

| 스킬 | 쓰는 시점 | 이 프로젝트에서 가져온 것 |
|---|---|---|
| `ui-ux-pro-max` | 방향 잡기 | Minimalism & Swiss 방향, 48dp 터치, 4.5:1 대비, 화면당 모션 1~2개. 추천 폰트(아동용)·초록 강조는 브리프와 충돌해 버림 |
| `frontend-design` | 새 화면 | 대담함은 학습 카드 한 곳에만. 가운뎃점 메타 문자열·대문자 라벨·버튼 끝 화살표 금지. 계층마다 다른 반경(학습 카드 32px, 벤토 24px, 칩 원형) |
| `hallmark` | 제작 | 범위 = 앱 화면(`shape: app screen`). 아래 Floor 규칙과 4상태, `sloplint --scope app` 검사 |
| `emil-design-eng`, `make-interfaces-feel-better` | 다듬기 | 누름 피드백, 그림자, 광학 정렬, 숫자 tabular |
| `web-design-guidelines` | 마무리 검수 | 접근성·폼·포커스 점검 |

Hallmark Floor (예외 없음):
- 모든 색·폰트는 `src/index.css`의 `:root` 토큰을 거친다. Tailwind 색 이름은 `var(--…)`를 가리킨다. 컴포넌트에 hex·rgba 리터럴을 쓰지 않는다(SVG 포함, `currentColor`나 토큰 사용).
- `transition-all` 금지. width·height·top·left 애니메이션 금지 → 진행 바는 `scaleX`, 위치는 `transform`.
- `html, body { overflow-x: clip }`.
- 모든 조작 요소에 `:focus-visible` 링(즉시 표시).
- 모든 애니메이션에 `prefers-reduced-motion` 대체.
- 이모지 아이콘 금지 → 스트릭은 lucide `Flame`, 프리즈는 `Snowflake`.
- 지어낸 수치 금지. 화면 수치는 모두 서버 값.
- `src/index.css` 첫 줄은 Hallmark 스탬프 주석.

데이터를 불러오는 영역(세션 카드, 히트맵)은 4상태를 모두 그린다: 로딩(카드 모양 스켈레톤), 비어 있음(무엇이 들어올지 + 채우는 행동 하나), 오류(무엇이 깨졌고 무엇을 할지), 정상.
세션 탭 상태는 URL(`?session=review`)에 둔다.

## 7. 절대 금지

- 칙칙한 검은/짙은 회색 화면 배경, 다크 모드 기본값.
- 형광 노랑·주황·초록 강조(🔥 이모지는 허용, 색 면으로 쓰지 않는다).
- `border border-black`, `border-gray-*` 같은 1px 선으로 카드 나누기.
- 하단 독에 버튼을 2줄 이상 넣거나 4개 이상 넣기.
- 그림자 여러 겹, 과한 글래스모피즘, 무지개 그라데이션.
- 한 화면에 강조색 2개 이상.

## 8. 완료 전 확인

- [ ] 새 색 값을 하드코딩하지 않았다(토큰 이름 사용).
- [ ] 카드에 테두리가 없다.
- [ ] 375px 폭에서 가로 스크롤이 없고, 세그먼트 글자가 줄바꿈되지 않는다.
- [ ] 하단 독에는 이전·재생·다음 세 개뿐이다.
- [ ] 활성 상태가 색 + 모양(알약 이동) 두 가지로 보인다.
- [ ] 숫자에 `tabular-nums`.
- [ ] 412×915와 840×760 두 크기에서 가로 스크롤이 없고, 펼친 화면 왼쪽 열이 스크롤 없이 들어간다.
- [ ] 스크롤되는 영역의 스크롤바가 둥근 모서리에 잘리지 않는다.
