# 이 PC(로컬 서버) 설정 목록과 되돌리는 방법

이 노트북을 서버로 쓰려고 한 설정이다. 나중에 서버를 Oracle 등으로 옮겨 이 PC 에서 켤 일이 없어지면 아래 "되돌리기"를 순서대로 하면 된다.
명령은 **관리자 PowerShell** 기준이다. 최초 작성: 2026-11-16.

## A. Claude 가 한 설정

| # | 무엇 | 언제 | 확인 | 되돌리기 |
|---|---|---|---|---|
| A1 | 작업 스케줄러 작업 **`habit66 서버`**: 부팅 1분 뒤 로그인 없이(S4U, 비밀번호 저장 안 함) `run.bat` 실행, 로그 `data\server.log`, 배터리에서도 계속, 시간 제한 없음, 죽으면 1분 간격 3회 재시작 | 2026-11-16 | `Get-ScheduledTask -TaskName "habit66 서버"` | 서버 멈추기(아래) 후 `Unregister-ScheduledTask -TaskName "habit66 서버" -Confirm:$false` |
| A2 | Tailscale **무인 실행 모드**(Windows 에서 로그아웃해도 연결 유지) | 2026-11-16 | `& "C:\Program Files\Tailscale\tailscale.exe" debug prefs` 의 `ForceDaemon: true` | `& "C:\Program Files\Tailscale\tailscale.exe" set --unattended=false` |
| A3 | **Tailscale Serve**: `https://<pc>.<tailnet>.ts.net/` → `http://127.0.0.1:8080` (재부팅해도 유지. `run.bat` 도 켤 때마다 같은 설정을 다시 건다) | 2026-11-16 | `& "C:\Program Files\Tailscale\tailscale.exe" serve status` | `& "C:\Program Files\Tailscale\tailscale.exe" serve reset` |
| A4 | `data\server.log`(자동 시작 서버의 콘솔 출력, **켤 때마다 덮인다**)와 `data\logs\habit66.log`(서버가 직접 남기는 로그, 5MB·하루마다 회전해 14일·최대 50MB 보관, 2026-11-16 추가). 서버가 죽고 자동 재시작된 뒤 원인은 `data\logs` 쪽에서 찾는다 | 2026-11-16 | 파일 존재 | A1 을 지운 뒤 파일·폴더 삭제 |
| A5 | **DB 자동 백업**(서버 안의 기능): 매일 04:30 과 서버가 켜질 때(그날 것이 없으면) `data\backup\habit66-YYYY-MM-DD.db`, 최근 14개 보관. `app.backup.extra-dir`(또는 환경 변수 `APP_BACKUP_EXTRA_DIR`)를 정하면 같은 사본과 `vapid.json`을 그 폴더에도 둔다 | 2026-11-16 | `dir data\backup`, 로그의 `DB backup written` | `config\application.yml` 에 `app.backup.enabled: false` |

**서버 멈추기**: `Stop-ScheduledTask`는 바깥 `cmd`만 끄고 그 안의 `java`(서버)는 남는다(2026-11-16 확인). 그래서 두 줄을 같이 쓴다.
```powershell
Stop-ScheduledTask -TaskName "habit66 서버"
Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```
다시 켜기: `Start-ScheduledTask -TaskName "habit66 서버"`. 새로 빌드할 때도 이 순서로 멈춘 뒤 빌드한다(실행 중인 jar 는 덮어쓸 수 없다).

## B. 사용자가 한 설정 (계정·기기)

| # | 무엇 | 되돌리기 |
|---|---|---|
| B1 | 이 PC 에 Tailscale 설치·로그인 | 설정 → 앱 → Tailscale 제거. [관리 콘솔 Machines](https://login.tailscale.com/admin/machines)에서 이 PC 항목 `⋯` → Remove |
| B2 | tailnet 에서 Serve·HTTPS 인증서 허용(2026-11-16 링크 승인) | 다른 기기에서 계속 쓰면 그대로 둔다. 끄려면 [관리 콘솔 DNS](https://login.tailscale.com/admin/dns)에서 HTTPS Certificates 끄기 |
| B3 | 폰(z-fold8)에 Tailscale 설치·로그인 | Oracle 서버를 Tailscale 없이 공개 HTTPS 로 열면 폰 앱 제거. Oracle 도 tailnet 에 넣을 거면 그대로 |
| B4 | `config\application.yml`에 Claude 장기 토큰(`claude setup-token`, 1년) — gitignore 대상 | Oracle 서버에서도 단어 뜻을 만들려면 그 서버에 같은 파일을 만든다(토큰을 새로 발급하는 편이 안전). 이 PC 에서는 파일 삭제 |
| B5 | 폰에 이 PC 주소로 PWA 설치(홈 화면 앱), 22시 알림 구독 | 주소가 바뀌면 새 주소로 다시 설치·알림 다시 켜기(아래 C 참고) |
| B6 | **관리자 토큰**(2026-11-16): `config\application.yml` 의 `app.admin-token` 과 사용자 환경 변수 `APP_ADMIN_TOKEN` 에 같은 무작위 값. 이제 `/api/v1/admin/**`(시작일 변경·레슨 임포트·오디오 업로드·수동 백업)는 `X-Admin-Token` 헤더가 있어야 한다(없거나 틀리면 401). 폰 앱·매일 자동 백업·22시 알림은 관리 API 를 쓰지 않아 그대로. `scripts/generate_tts.py` 는 새 창에서 환경 변수를 자동으로 읽는다. 값은 채팅·커밋에 넣지 않는다. 확인: 새 PowerShell 창에서 `curl.exe -s -o NUL -w "%{http_code}" -H "X-Admin-Token: $env:APP_ADMIN_TOKEN" http://127.0.0.1:8080/api/v1/admin/settings` 가 200 | `config\application.yml` 에서 `admin-token` 줄 삭제, `[Environment]::SetEnvironmentVariable("APP_ADMIN_TOKEN", $null, "User")`, 서버 다시 켜기 |
| B7 | **Azure Speech 음성**(2026-11-16): Azure 에 Speech 리소스(가격 등급 **F0 무료**: 신경망 음성 월 50만 자, 1분 20건)를 만들고 `config\application.yml` 에 `app.tts.engine: azure`, `app.tts.azure.key: <키 1>`, `app.tts.azure.region: "<리소스 지역, 예: eastus>"` 를 넣었다. 키는 Azure Portal → 리소스 → **키 및 엔드포인트**에 있다. 키는 이 파일에만 두고 채팅·저장소에 올리지 않는다. 서버를 다시 켜면 모든 문장을 48kHz·192kbps 로 새로 만든다(3.1초 간격, 660문장 약 35분, 진행은 `/api/v1/admin/tts/status`). 660문장 전체가 약 3.5만 자(영어 원문 기준)라 무료 한도 안이다 | edge-tts 로 돌아가려면 `engine: azure` 줄을 지우거나 `edge` 로 바꾸고 서버를 다시 켠다(예전 edge 파일이 남아 있어 바로 예전 소리가 난다). 키가 새 나갔다면 Portal 에서 **키 다시 생성**. 그만 쓰려면 Speech 리소스 삭제 |

## C. 권장했지만 사용자가 할 설정 (했다면 여기 적고 되돌리기)

| # | 무엇 | 되돌리기 |
|---|---|---|
| C1 | 전원: 덮개를 닫을 때 "아무것도 안 함", 절전 "안 함" | 제어판 → 전원 옵션 → 덮개를 닫으면 수행할 작업 / 절전 시간을 원래대로 |
| C2 | Tailscale 이 PC 키 만료 끄기(Disable key expiry) | 관리 콘솔 Machines → 이 PC `⋯` → Enable key expiry |

## 백업에서 되살리기

1. 서버를 멈춘다(위 "서버 멈추기" 두 줄).
2. 지금 DB 를 옆으로 치운다: `data\habit66.db`, `habit66.db-wal`, `habit66.db-shm` 세 파일을 다른 폴더로 옮긴다(지우지 말 것).
3. 되살릴 백업을 `data\habit66.db` 로 복사한다. 예: `copy data\backup\habit66-2026-11-16.db data\habit66.db`
   (백업은 `VACUUM INTO` 로 만든 한 파일짜리라 `-wal`·`-shm` 이 필요 없다.)
4. 서버를 켠다(`Start-ScheduledTask -TaskName "habit66 서버"`). 폰은 앱을 새로 고치면 서버 기록을 다시 받는다. 폰에 남아 있던 미전송 기록은 그대로 보내진다.
5. 이 PC 가 통째로 망가진 경우: 새 PC 에 코드를 받고(`git clone https://github.com/hotsem4/yanudo.git`), 프로젝트 루트에 `data\` 폴더를 만든 뒤 다른 곳에 둔 백업(extra-dir)을 `data\habit66.db` 로, 같이 둔 `vapid.json` 을 `data\vapid.json` 으로 놓는다. `config\application.yml`(장기 토큰·관리자 토큰·extra-dir, GitHub 에 없음)을 다시 만들고 `build-all.bat`. 음성 파일은 서버가 다시 만든다. 이 PC 에만 백업을 두기로 했으므로(2026-11-16) PC 를 잃으면 기록도 잃는다는 점을 기억한다.
- 지금 바로 백업(새 PowerShell 창): `curl.exe -X POST -H "X-Admin-Token: $env:APP_ADMIN_TOKEN" http://127.0.0.1:8080/api/v1/admin/backup`(관리자 토큰은 B6).

## D. Oracle 로 옮길 때 챙길 것 (순서)

1. **폰의 미전송 기록부터 보낸다**: 미전송 기록은 폰 브라우저에 **주소(출처)별로** 저장된다. 주소가 바뀌면 옛 주소의 미전송 기록은 새 앱에서 보이지 않는다. 옮기기 직전에 옛 주소 앱을 열어 설정 시트의 "보내지 않은 기록 0건"을 확인한다.
2. 이 PC 의 서버를 멈춘다(A1 의 `Stop-ScheduledTask`) → `data\` 폴더 전체(`habit66.db`, `-wal`, `-shm`, `audio\`, `vapid.json`)를 서버로 복사한다. `vapid.json`을 옮겨야 푸시 키가 그대로라 알림 구독이 덜 깨진다(주소가 바뀌면 어차피 폰에서 다시 켠다).
3. **접속 방식은 Tailscale 유지**(2026-11-16 사용자 결정): Oracle VM 에도 Tailscale(ARM 패키지)을 설치해 같은 tailnet 에 넣고 `tailscale serve --bg 8080` 을 건다. `server.address` 는 `127.0.0.1` 그대로, 인터넷에 포트를 열지 않는다(앱에 로그인이 없어 공개하면 누구나 기록·생성 기능을 쓸 수 있다). 폰은 지금처럼 Tailscale 을 켜 두면 된다.
   - 검토했던 다른 방식: Cloudflare Tunnel + Access(도메인 필요, 구글 로그인), 무료 주소(DuckDNS) + Caddy + 앱 비밀번호(포트 공개, 로그인 화면 개발 필요). 공개 방식을 고르면 로그인 관문이 반드시 필요하다.
4. 시간대 `app.timezone`(기본 Asia/Seoul)은 서버 위치와 무관하게 그대로 둔다(하루 경계가 한국 시간 기준).
5. 폰에서 새 주소로 PWA 를 설치하고 22시 알림을 다시 켠다. 옛 홈 화면 앱은 지운다.
6. 이 PC 정리: A1 → A3 → A2 → A4 순서로 되돌리고, B·C 는 필요에 따라.

검토 자료: [docs/deploy/oracle-always-free-review.md](deploy/oracle-always-free-review.md)(다른 작업자 문서).
