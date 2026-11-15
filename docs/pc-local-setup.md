# 이 PC(로컬 서버) 설정 목록과 되돌리는 방법

이 노트북을 서버로 쓰려고 한 설정이다. 나중에 서버를 Oracle 등으로 옮겨 이 PC 에서 켤 일이 없어지면 아래 "되돌리기"를 순서대로 하면 된다.
명령은 **관리자 PowerShell** 기준이다. 최초 작성: 2026-11-16.

## A. Claude 가 한 설정

| # | 무엇 | 언제 | 확인 | 되돌리기 |
|---|---|---|---|---|
| A1 | 작업 스케줄러 작업 **`habit66 서버`**: 부팅 1분 뒤 로그인 없이(S4U, 비밀번호 저장 안 함) `run.bat` 실행, 로그 `data\server.log`, 배터리에서도 계속, 시간 제한 없음, 죽으면 1분 간격 3회 재시작 | 2026-11-16 | `Get-ScheduledTask -TaskName "habit66 서버"` | `Stop-ScheduledTask -TaskName "habit66 서버"` 후 `Unregister-ScheduledTask -TaskName "habit66 서버" -Confirm:$false` |
| A2 | Tailscale **무인 실행 모드**(Windows 에서 로그아웃해도 연결 유지) | 2026-11-16 | `& "C:\Program Files\Tailscale\tailscale.exe" debug prefs` 의 `ForceDaemon: true` | `& "C:\Program Files\Tailscale\tailscale.exe" set --unattended=false` |
| A3 | **Tailscale Serve**: `https://<pc>.<tailnet>.ts.net/` → `http://127.0.0.1:8080` (재부팅해도 유지. `run.bat` 도 켤 때마다 같은 설정을 다시 건다) | 2026-11-16 | `& "C:\Program Files\Tailscale\tailscale.exe" serve status` | `& "C:\Program Files\Tailscale\tailscale.exe" serve reset` |
| A4 | `data\server.log`(자동 시작 서버의 로그 파일) | 2026-11-16 | 파일 존재 | A1 을 지운 뒤 파일 삭제 |

## B. 사용자가 한 설정 (계정·기기)

| # | 무엇 | 되돌리기 |
|---|---|---|
| B1 | 이 PC 에 Tailscale 설치·로그인 | 설정 → 앱 → Tailscale 제거. [관리 콘솔 Machines](https://login.tailscale.com/admin/machines)에서 이 PC 항목 `⋯` → Remove |
| B2 | tailnet 에서 Serve·HTTPS 인증서 허용(2026-11-16 링크 승인) | 다른 기기에서 계속 쓰면 그대로 둔다. 끄려면 [관리 콘솔 DNS](https://login.tailscale.com/admin/dns)에서 HTTPS Certificates 끄기 |
| B3 | 폰(z-fold8)에 Tailscale 설치·로그인 | Oracle 서버를 Tailscale 없이 공개 HTTPS 로 열면 폰 앱 제거. Oracle 도 tailnet 에 넣을 거면 그대로 |
| B4 | `config\application.yml`에 Claude 장기 토큰(`claude setup-token`, 1년) — gitignore 대상 | Oracle 서버에서도 단어 뜻을 만들려면 그 서버에 같은 파일을 만든다(토큰을 새로 발급하는 편이 안전). 이 PC 에서는 파일 삭제 |
| B5 | 폰에 이 PC 주소로 PWA 설치(홈 화면 앱), 22시 알림 구독 | 주소가 바뀌면 새 주소로 다시 설치·알림 다시 켜기(아래 C 참고) |

## C. 권장했지만 사용자가 할 설정 (했다면 여기 적고 되돌리기)

| # | 무엇 | 되돌리기 |
|---|---|---|
| C1 | 전원: 덮개를 닫을 때 "아무것도 안 함", 절전 "안 함" | 제어판 → 전원 옵션 → 덮개를 닫으면 수행할 작업 / 절전 시간을 원래대로 |
| C2 | Tailscale 이 PC 키 만료 끄기(Disable key expiry) | 관리 콘솔 Machines → 이 PC `⋯` → Enable key expiry |

## D. Oracle 로 옮길 때 챙길 것 (순서)

1. **폰의 미전송 기록부터 보낸다**: 미전송 기록은 폰 브라우저에 **주소(출처)별로** 저장된다. 주소가 바뀌면 옛 주소의 미전송 기록은 새 앱에서 보이지 않는다. 옮기기 직전에 옛 주소 앱을 열어 설정 시트의 "보내지 않은 기록 0건"을 확인한다.
2. 이 PC 의 서버를 멈춘다(A1 의 `Stop-ScheduledTask`) → `data\` 폴더 전체(`habit66.db`, `-wal`, `-shm`, `audio\`, `vapid.json`)를 서버로 복사한다. `vapid.json`을 옮겨야 푸시 키가 그대로라 알림 구독이 덜 깨진다(주소가 바뀌면 어차피 폰에서 다시 켠다).
3. 서버에서 `server.address`: 이 PC 는 `127.0.0.1` 전용이다. Oracle 앞에 리버스 프록시(nginx·Caddy)를 두면 그대로 두고, 직접 열면 `APP_SERVER_ADDRESS=0.0.0.0` + **관리자 토큰(`app.admin-token`) 필수**.
4. 시간대 `app.timezone`(기본 Asia/Seoul)은 서버 위치와 무관하게 그대로 둔다(하루 경계가 한국 시간 기준).
5. 폰에서 새 주소로 PWA 를 설치하고 22시 알림을 다시 켠다. 옛 홈 화면 앱은 지운다.
6. 이 PC 정리: A1 → A3 → A2 → A4 순서로 되돌리고, B·C 는 필요에 따라.

검토 자료: [docs/deploy/oracle-always-free-review.md](deploy/oracle-always-free-review.md)(다른 작업자 문서).
