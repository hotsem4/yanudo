@echo off
chcp 65001 >nul
rem 빌드 없이 서버만 실행한다. 먼저 build-all.bat 을 한 번 실행해야 한다.
setlocal
cd /d "%~dp0"
if not exist backend\build\libs\habit66.jar (
  echo [오류] jar 가 없습니다. build-all.bat 을 먼저 실행하세요.
  exit /b 1
)
where java >nul 2>nul
if errorlevel 1 (
  for /d %%J in ("%ProgramFiles%\Eclipse Adoptium\jdk-*") do set "JAVA_HOME=%%J"
)
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
where tailscale >nul 2>nul
if not errorlevel 1 tailscale serve --bg 8080 >nul 2>nul
echo http://localhost:8080  (종료: Ctrl+C)
java -Dfile.encoding=UTF-8 -jar backend\build\libs\habit66.jar
