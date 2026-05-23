@echo off
setlocal EnableDelayedExpansion

:: ============================================================
::  startup.bat  —  Java APM Dashboard v1.6.0  백그라운드 시작
::
::  동작 순서:
::    1. 이중 기동 방지 (PID 파일 + 프로세스 존재 확인)
::    2. target\ 에서 최신 JAR 자동 탐색
::    3. 최소화 창으로 백그라운드 시작
::    4. wmic 으로 PID 확인 후 logs\monitor.pid 저장
::    5. 헬스 체크 통과 시 브라우저 자동 오픈
:: ============================================================

set "SCRIPT_DIR=%~dp0"
set "PROJECT_DIR=%SCRIPT_DIR%.."
set "LOG_DIR=%PROJECT_DIR%\logs"
set "PID_FILE=%LOG_DIR%\monitor.pid"
set "PORT=9090"
set "DASHBOARD=http://localhost:%PORT%/dashboard"

:: Java 경로 (PATH 우선, 없으면 고정 경로)
set "JAVA=java"
if exist "D:\jdk\openjdk\jdk-21.0.8\bin\java.exe" (
    set "JAVA=D:\jdk\openjdk\jdk-21.0.8\bin\java.exe"
)

echo.
echo  =============================================
echo   Java APM Dashboard v1.6.0  ^|  시작
echo   Dashboard : %DASHBOARD%
echo  =============================================
echo.

:: ── [1] logs 디렉토리 생성 ───────────────────────────────────
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

:: ── [2] 이중 기동 방지 ────────────────────────────────────────
if exist "%PID_FILE%" (
    set /p EXISTING_PID=<"%PID_FILE%"
    wmic process where "processid='!EXISTING_PID!'" get processid >nul 2>&1
    if not errorlevel 1 (
        echo [WARN] 이미 실행 중입니다. PID: !EXISTING_PID!
        echo [WARN] 종료하려면 shutdown.bat 을 실행하세요.
        echo.
        pause
        exit /b 0
    )
    del "%PID_FILE%" >nul 2>&1
)

:: ── [3] JAR 탐색 (original / agent 제외, 최신 선택) ──────────
set "JAR="
for %%f in ("%PROJECT_DIR%\target\java-monitor-*.jar") do (
    echo %%f | findstr /i "original agent" >nul || set "JAR=%%f"
)

if not defined JAR (
    echo [ERROR] 빌드된 JAR 파일이 없습니다.
    echo [INFO]  scripts\build.bat 을 먼저 실행하세요.
    echo.
    pause
    exit /b 1
)
echo [INFO] JAR  : %JAR%

:: ── [4] JVM 인수 구성 ─────────────────────────────────────────
set "JVM=-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
set "JVM=%JVM% -XX:+HeapDumpOnOutOfMemoryError"
set "JVM=%JVM% -XX:HeapDumpPath=%LOG_DIR%\heapdump.hprof"
set "JVM=%JVM% -Xlog:gc*:file=%LOG_DIR%\gc.log:time:filecount=3,filesize=10m"
set "JVM=%JVM% -Djava.util.logging.config.file=%SCRIPT_DIR%logging.properties"
set "JVM=%JVM% -Dserver.http.port=%PORT%"

:: ── [5] 백그라운드 시작 (최소화 독립 창) ─────────────────────
echo [INFO] 서버를 시작합니다...
start "Java APM Dashboard" /MIN "%JAVA%" %JVM% -jar "%JAR%"

:: ── [6] PID 확인 (최대 10초 재시도) ─────────────────────────
echo [INFO] PID 확인 중...
set "PID="
for /L %%i in (1,1,10) do (
    if not defined PID (
        timeout /T 1 /NOBREAK >nul
        for /f "skip=1 delims=" %%L in (
            'wmic process where "name='java.exe' and commandline like '%%java-monitor%%'" get processid 2^>nul'
        ) do (
            for /f "tokens=1" %%P in ("%%L") do (
                if not "%%P"=="" set "PID=%%P"
            )
        )
    )
)

if defined PID (
    echo !PID!> "%PID_FILE%"
    echo [INFO] PID : !PID!   저장됨 : %PID_FILE%
) else (
    echo [WARN] PID 를 자동으로 확인하지 못했습니다.
)

:: ── [7] 헬스 체크 대기 (최대 30초) ─────────────────────────
echo [INFO] 서버 기동 대기 중...
set "READY="
for /L %%i in (1,1,30) do (
    if not defined READY (
        timeout /T 1 /NOBREAK >nul
        curl -s -o nul -w "%%{http_code}" http://localhost:%PORT%/health 2>nul | findstr "200" >nul 2>&1
        if not errorlevel 1 set "READY=1"
    )
)

echo.
if defined READY (
    echo [INFO] 서버가 정상 기동되었습니다.
    echo [INFO] 대시보드 : %DASHBOARD%
    start "" "%DASHBOARD%"
) else (
    echo [WARN] 헬스 체크 응답 없음. 로그를 확인하세요.
    echo [WARN] 로그 경로 : %LOG_DIR%
)

echo.
pause
