@echo off
chcp 65001 >nul
rem ==========================================================
rem  66 Habit English - 전체 빌드 후 실행 (Windows)
rem   1) frontend 빌드 (npm)
rem   2) dist 를 backend/src/main/resources/static 으로 복사
rem   3) backend bootJar (Gradle wrapper)
rem   4) tailscale serve --bg 8080 (설치되어 있으면)
rem   5) 프로젝트 루트에서 java -jar 실행 (data\ 가 루트에 생김)
rem  사용법:  build-all.bat          빌드 후 실행
rem          build-all.bat norun    빌드만
rem ==========================================================
setlocal
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
  if exist "%ProgramFiles%\Eclipse Adoptium" (
    for /d %%J in ("%ProgramFiles%\Eclipse Adoptium\jdk-*") do set "JAVA_HOME=%%J"
  )
)
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
where java >nul 2>nul
if errorlevel 1 (
  echo [오류] Java 17 이상이 필요합니다. https://adoptium.net 에서 설치하세요.
  exit /b 1
)
where npm >nul 2>nul
if errorlevel 1 (
  echo [오류] Node.js 가 필요합니다. https://nodejs.org 에서 설치하세요.
  exit /b 1
)

echo.
echo [1/4] 프론트엔드 빌드
pushd frontend
if not exist node_modules (
  call npm install --no-fund --no-audit || goto :fail
)
call npm run build || goto :fail
popd

echo.
echo [2/4] dist -^> backend\src\main\resources\static 복사
if exist backend\src\main\resources\static rmdir /s /q backend\src\main\resources\static
robocopy frontend\dist backend\src\main\resources\static /E /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 goto :fail

echo.
echo [3/4] 백엔드 jar 빌드
pushd backend
call gradlew.bat bootJar --no-daemon -q || goto :fail
popd

if /i "%~1"=="norun" (
  echo.
  echo 빌드 완료: backend\build\libs\habit66.jar
  exit /b 0
)

echo.
echo [4/4] 실행
where tailscale >nul 2>nul
if not errorlevel 1 (
  tailscale serve --bg 8080 >nul 2>nul && echo  - tailscale serve: https 주소는 "tailscale serve status" 로 확인
)
echo  - http://localhost:8080  (종료: Ctrl+C)
java -Dfile.encoding=UTF-8 -jar backend\build\libs\habit66.jar
exit /b %errorlevel%

:fail
echo.
echo [실패] 위 오류를 확인하세요.
popd 2>nul
exit /b 1
