# 인수인계 (2026-11-16 08:3x)

## 1. 오늘 끝낸 것 (모두 커밋, push 안 함)

| 커밋 | 내용 |
|---|---|
| `9fe9ced` | 단어 생성기 **장기 토큰**(`app.vocab.claude-oauth-token`) + **로그인 끊김 폰 알림** |
| `ea7256b` | `.gitignore`의 `config/` 규칙이 백엔드 `config` 패키지를 숨겨 커밋되지 않던 문제 수정(`/config/`) |
| `f6a29de` | 커밋돼 있던 `scripts/__pycache__` 제거 |
| `9b61dff` | **전체 코드 리뷰**(Fable 5.2 + Codex `gpt-6.1-sol` high) 반영 1차: 37건 |
| `afc053e`, `e0a0287` | 두 리뷰어 **교차 검토** 조율 반영 2차 |
| `8403d24` | 최종 확인 반영 3차(서버만 꺼졌을 때 기록이 보관함으로 빠지던 문제 등) |
| `bff6c8c` | 3차 확인 반영 4차 |

- 리뷰·조율 전체 기록: [docs/review/code-review-2026-11-16.md](review/code-review-2026-11-16.md)(결정·이유·보류 항목). 원문은 `.codex-review/full/`(gitignore).
- 검증: 백엔드 테스트 114개 통과(실측 1개는 `HABIT66_LIVE_CLAUDE=1`일 때만), `npm run typecheck`, `sloplint --scope app` FAIL 0, `build-all.bat norun` 성공(운영 jar `backend/build/libs/habit66.jar` 최신). 미리보기에서 서비스 워커 갱신과 서버를 끈 뒤 캐시된 앱 부팅 확인.
- 장기 토큰: 사용자가 `config/application.yml`에 넣었고, 같은 조건의 짧은 호출로 인증 성공 확인(값은 출력하지 않음). 서버를 켜면 `POST /api/v1/admin/vocab/check-login`이 `"state":"OK"`면 끝.

### 동작이 바뀐 것 (사용자 체감)
- 서버는 이제 **이 PC 안(`127.0.0.1`)에서만** 받는다. 폰은 Tailscale(`tailscale serve`)로 들어온다. 같은 Wi-Fi에서 PC IP로 직접 여는 방식은 막혔다(필요하면 `APP_SERVER_ADDRESS=0.0.0.0` + 관리자 토큰).
- 기기 음성은 영어 음성을 고를 수 없으면 읽지 않고 그 문장을 건너뛴다(들음 기록 없음, 3문장 연속이면 멈추고 알림). 다른 언어 음성으로 영어를 읽던 문제(새벽에 들린 소리)의 원인.
- 서버가 계속 거절하는 기록은 버리지 않고 기기에 따로 보관하고 설정 화면에 표시, 앱을 켤 때마다 다시 보낸다.
- 알림을 누르면 열린 앱을 새로 고치지 않고 화면만 바꾼다.

## 2. 폰 연결 (2026-11-16 08:3x 완료)
- Tailscale: 노트북·폰(z-fold8) 같은 tailnet, Serve(HTTPS) 활성화, `tailscale serve --bg 8080` → `https://<pc>.<tailnet>.ts.net/` 가 `http://127.0.0.1:8080` 으로 프록시. 주소는 `tailscale serve status`(`C:\Program Files\Tailscale\tailscale.exe`).
- 확인: HTTPS 로 화면 200, 대시보드(Day 2), `check-login` → `OK`(장기 토큰), tailnet IP 로 8080 직접 접속은 거부(의도).
- 서버는 작업 스케줄러 작업 `habit66 서버`로 부팅 1분 뒤 로그인 없이 자동 시작(로그 `data/server.log`), Tailscale 은 무인 실행 모드. 지금도 이 작업으로 실행 중(확인: HTTPS 200, check-login OK, TTS 660/660).
- 이 PC 에 한 설정 전체와 되돌리는 방법(Oracle 로 옮길 때): [pc-local-setup.md](pc-local-setup.md).
- 수동으로 `run.bat`을 함께 켜면 8080 이 이미 쓰여 그쪽은 바로 끝난다(해는 없음). 서버를 멈추려면 `Stop-ScheduledTask -TaskName "habit66 서버"`.
- 남은 사용자 단계: 폰 Chrome 에서 주소 열기 → 홈 화면에 추가(앱 설치) → 마이크 허용(모닝 락), 설정 시트에서 22시 알림 켜기.
- 노트북을 덮으면(절전) 서버도 멈춘다. 폰은 캐시로 오프라인 학습은 되지만 동기화·22시 알림은 서버가 켜져 있어야 한다.

## 3. 남은 일 / 후속 과제
- 실제 폴드8 기기 확인(지금까지는 브라우저 크기 흉내): 화면, 음성 인식, 기기 음성, 푸시.
- 단어 카드 뒷면 새 문구(로그인 끊김 안내)의 화면 확인(미리보기 창이 숨겨져 못 봄).
- 리뷰 보류 항목(문서 "남은 보류"): 잘린 MP3 판정, `claude.cmd` 인자, 웹 푸시 소켓, 시작일 하한, 오프라인 VIEW 누적, 창 사이 저장 원자성(IndexedDB), 서비스 워커 세대별 캐시.
- 사전형으로 표현 키 합치기(보류: 쌓인 오프라인 이벤트가 옛 키를 가리킴).
- 클라우드 배포(Oracle)는 로그인 문제로 보류, 로컬 PC 운영 유지(사용자 결정).
- 미추적 파일 `docs/deploy/`, `docs/observed-issues-2026-11-15.md`는 다른 작업자 파일이라 손대지 않았다.

## 4. 작업 규칙 메모
- 커밋: `git -c user.name=habit66 -c user.email=habit66@localhost commit`(git 사용자 정보 미설정, 출처 표기 줄 넣지 않음, push 안 함).
- 운영 `data/`와 8080은 신중히. 미리보기는 8090/5174와 `.preview-data/`. 로그인 끊김 재현은 `backend-preview-login`(가짜 401 claude).
- 이 환경은 현재 폴더 실행 파일을 찾지 않는다(`NoDefaultCurrentDirectoryInExePath`): 배치 파일은 전체 경로(`cmd //c "C:\dev\english-66-habit\build-all.bat norun"`).
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`(PATH에 없음, `JAVA_HOME` 지정).
- 큰 치환은 bash heredoc 대신 파이썬 스크립트 파일로. 파이썬 출력 리다이렉트는 cp949가 되니 파일은 `encoding='utf-8'`로 쓴다.
- 브라우저 검사 때 소리 막기(`speechSynthesis.speak`·`HTMLMediaElement.play` 덮어쓰기). 미리보기 창이 숨겨지면 rAF 가 멈춰 애니메이션 중간이 찍히니 끝난 뒤 캡처.
- Codex: `codex exec -s read-only --skip-git-repo-check --ephemeral -m gpt-6.1-sol -c 'model_reasoning_effort="high"' -C <.codex-review 하위 폴더> -o out.md - < bundle.txt`(파일 전문을 묶어 stdin, 개인 정보 grep 후).
- 운영 DB 백업: `data/backup/habit66-before-vocab-20261116.db`(+`-wal`, `-shm`).
