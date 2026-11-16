# 인수인계 (2026-11-16 오후)

## 1. 지금 상태 한눈에

- 운영: 이 PC 서버(작업 스케줄러 `habit66 서버`, 8080) + Tailscale Serve 로 폰 접속. 콘텐츠 Day 1~66(660문장, 음성 전부).
- 코드: GitHub `hotsem4/yanudo`(공개 저장소) `main` 에 푸시돼 있다. 로컬 브랜치는 `master` 이고 `origin/main` 을 따라간다(`push.default upstream`) → 그냥 `git push`.
- 학습 기록 DB: `data/habit66.db`(올리지 않음). 매일 자동 백업 `data/backup/`(이 PC 에만 둔다, 사용자 결정).
- 검증: 백엔드 테스트 132개, `npm run typecheck`, `sloplint --scope app` FAIL 0.

## 2. 오늘 오후에 끝낸 것

| 커밋 | 내용 |
|---|---|
| `69d6862` | 오늘의 TODO 체크리스트(자동 체크), 음성 생성 시간 제한 120초, Day 1~5 어순 줄 점검 |
| `40a9717` | **말하기 탭**(세션 탭 네 번째): 문장마다 마이크로 5번 맞게 말하기 |
| `669622f` → `a9fbf05` | **빼먹은 날 보충**(문장마다 5번, 미리 알린 날은 1번), **"오늘은 어려워요"**(30일에 4번, 22시 알림 생략), **보충으로 스트릭 회복**(날짜+3일 안, 지킬 스트릭이 있을 때만) |
| `f70b48e` | **66일은 끝이 아니라 한 라운드**: Day 상한 없음, 라운드, 새 레슨이 없는 날은 **복습 순환**(마지막 달성이 가장 이른 레슨, Fable 5.2 설계 판단) |
| `30f7de4` | **DB 자동 백업**, 오프라인 묶음 전송 분류, 지난 날짜 임포트 경고, 접은 화면 말하기 문장 자리 넓히기 |

- 각 단계마다 Fable 5.2 + Codex(`gpt-6.1-sol` high) 리뷰를 받아 반영했다. 마지막으로 `669622f..30f7de4` 전체 최종 리뷰를 돌렸다(결과·반영은 아래 5장).
- 운영 데이터 조치: Day 1(11/15)은 사용자 요청으로 달성 처리(`day_achievement` 한 행 추가). 그래서 말하기 탭 Day 1 이 열려 있다.

### 사용자 체감 규칙 요약 (자세한 것은 SPEC F-05, F-09)
- 말하기 탭: 달성한 지난 Day 는 열림, 빼먹은 Day 는 보충하면 열림, 오늘 Day 는 오늘의 10문장을 끝내면 열림.
- 보충 기한 안(빼먹은 날 + 3일)에 끝내면 히트맵에 "보충으로 지킴", 프리즈를 쓰지 않는다. 같은 레슨을 연속으로 빼먹었으면 한 번 보충으로 함께 이어진다.
- "오늘 할 일" 시트: 보충할 날 목록(누르면 말하기 탭 그 Day), "오늘은 어려워요"(확인 후, 취소 불가).
- Day 67 부터 헤더는 "2라운드 1/66 · Day 67". 히트맵은 이번 라운드 66칸 + 지난 라운드 요약.

## 3. 폰 연결 (2026-11-16 08:3x 완료, 그대로)
- Tailscale: 노트북·폰 같은 tailnet, `tailscale serve --bg 8080` → `https://<pc>.<tailnet>.ts.net/` 가 `http://127.0.0.1:8080` 으로 프록시. 주소는 `tailscale serve status`(`C:\Program Files\Tailscale\tailscale.exe`).
- 서버는 작업 스케줄러 작업 `habit66 서버`로 부팅 1분 뒤 로그인 없이 자동 시작(로그 `data/server.log`), Tailscale 은 무인 실행 모드.
- 이 PC 설정 전체·되돌리기·**백업에서 되살리기**: [pc-local-setup.md](pc-local-setup.md).
- 노트북을 덮으면(절전) 서버도 멈춘다. 폰은 캐시로 오프라인 학습은 되지만 동기화·22시 알림·새벽 백업은 서버가 켜져 있어야 한다(백업은 켜질 때 그날 것이 없으면 만든다).

## 4. 남은 일 / 후속 과제
- **실제 폴드8 기기 확인**(지금까지 브라우저 크기 흉내 + 가짜 마이크): 말하기 탭 실제 음성 인식, 보충, "오늘은 어려워요", 히트맵, 푸시.
- 리뷰 보류 항목(`docs/review/code-review-2026-11-16.md` "남은 보류"): 잘린 MP3 판정, `claude.cmd` 인자, 웹 푸시 소켓, 시작일 하한, 오프라인 VIEW 누적, 창 사이 저장 원자성(IndexedDB), 서비스 워커 세대별 캐시.
- 의도한 한계(SPEC F-05): 어제 달성이 오늘 기록 뒤에 따로 늦게 오면 오늘 복습 순환 레슨이 바뀔 수 있다. 미달성 날짜는 나중에 그 Day 레슨을 넣으면 일정이 바뀐다(임포트가 경고를 준다).
- 사전형으로 표현 키 합치기(보류: 쌓인 오프라인 이벤트가 옛 키를 가리킴).
- 클라우드 배포(Oracle)는 보류, 로컬 PC 운영 유지. 옮기더라도 접속은 Tailscale 유지([pc-local-setup.md](pc-local-setup.md) D).
- 미추적 파일 `docs/deploy/`, `docs/observed-issues-2026-11-15.md`는 다른 작업자 파일이라 손대지 않았다(그래서 GitHub 에도 없다).

## 5. 최종 리뷰 (669622f..30f7de4, 2026-11-16)
- Fable 5.2 + Codex 각각 전체를 다시 봤다. 높음(데이터 손실·하얀 화면) 없음. 중간·낮음 지적을 모두 반영했다(테스트 132개).
- 주요 반영:
  - 다 끝낸 Day 를 다시 연습하면 첫 문장 뒤 끝나던 회귀 → 이번 연습에서 끝낸 문장 기준.
  - 같은 레슨에 보충이 여럿일 때 대시보드·말하기가 같은 것(스트릭을 지키는 쪽)을 고른다. 기한 지난 보충은 그 레슨을 다른 날 달성했으면 열어 둔다.
  - 지킬 스트릭이 없던 날은 보충해도 REPAIRED 로 칠하지 않는다.
  - 이벤트 묶음을 일어난 시각 순으로 처리, VIEW·REST_DAY 앞에서 앞 날짜 달성을 먼저 기록.
  - 보내기 전 보충 표시는 필요한 횟수 이상·오늘 기록만.
  - 시작일을 옮겨도 오늘 레슨·달성한 레슨·보충할 레슨은 말하기 목록에 남는다.
  - 백업: 다른 위치 사본도 임시 파일 → 검사 → 교체, extra-dir 따옴표 정리, 잘못된 경로여도 로컬 백업은 성공.
  - 앱: 못 그리는 스냅샷은 캐시에서도 지우고, 방금 받은 것도 실패하면 오류 화면.
  - 오늘 Day 레슨 임포트도 경고, 22시 알림 문구 줄임, "오늘은 어려워요" 문구·구간 범위(서버 값).
- 남긴 것(의도): 빈 DB 로 잘못 뜬 서버가 다른 위치의 같은 날짜 사본을 덮을 수 있다(extra-dir 를 쓰지 않기로 해 지금은 해당 없음).

## 6. 작업 규칙 메모
- 커밋: `git -c user.name=habit66 -c user.email=habit66@localhost commit`(출처 표기 줄 넣지 않음). 푸시는 공개 저장소라 **비밀값·개인 정보 검사 후**(토큰·키·웹훅·실제 tailnet 주소·PC 이름·사용자 경로·이메일). `config/`·`data/`·`.preview-data/`·`.codex-review/` 는 gitignore.
- 운영 반영 순서: `Stop-ScheduledTask -TaskName "habit66 서버"` → 8080 의 java 종료 → `cmd //c "C:\dev\english-66-habit\build-all.bat norun"` → `Start-ScheduledTask -TaskName "habit66 서버"`.
- 운영 `data/`와 8080은 신중히. 미리보기는 8090/5174와 `.preview-data/`(미리보기 jar 는 새 빌드 뒤 `.preview-data/habit66-preview.jar` 로 복사). 로그인 끊김 재현은 `backend-preview-login`.
- 이 환경은 현재 폴더 실행 파일을 찾지 않는다(`NoDefaultCurrentDirectoryInExePath`): 배치 파일은 전체 경로.
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`(PATH에 없음, `JAVA_HOME` 지정).
- 큰 치환은 bash heredoc 대신 파이썬 스크립트 파일로(`encoding='utf-8'`, `newline='\n'`). 한글이 든 heredoc 은 깨진다.
- 브라우저 검사 때 소리 막기(`speechSynthesis.speak`·`HTMLMediaElement.play` 덮어쓰기). 음성 인식은 가짜 `SpeechRecognition` 을 넣어 시험한다.
- 프론트가 저장한 스냅샷은 버전이 바뀌면 모양이 다를 수 있다. 새 필드는 optional 로 받고, 계산이 깨지면 스토어가 그 스냅샷을 버리고 서버에서 다시 받는다.
- Codex: `codex exec -s read-only --skip-git-repo-check --ephemeral -m gpt-6.1-sol -c 'model_reasoning_effort="high"' -C <.codex-review 하위 폴더> -o out.md - < bundle.txt`(파일 전문을 묶어 stdin, 개인 정보 grep 후). Fable 5.2 는 Agent 도구 `model: "fable"`.
