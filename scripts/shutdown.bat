@echo off
setlocal EnableDelayedExpansion

:: ============================================================
::  shutdown.bat  —  Java APM Dashboard v1.6.0  종료
::
::  동작 순서:
::    1. 헬스 체크 — 이미 꺼져 있으면 즉시 종료
::    2. logs\monitor.pid 에서 PID 읽기
::    3. wmic 으로 JAR 이름 기반 폴백
::    4. netstat 으로 포트 기반 폴백
::    5. taskkill /F 실행 후 PID 파일 삭제
:: ============================================================

set "SCRIPT_DIR=%~dp0"
set "PROJECT_DIR=%SCRIPT_DIR%.."
set "LOG_DIR=%PROJECT_DIR%\logs"
set "PID_FILE=%LOG_DIR%\monitor.pid"
set "PORT=9090"

echo.
echo  =============================================
echo   Java APM Dashboard v1.6.0  ^|  종료
echo  =============================================
echo.

:: ── [1] 헬스 체크 — 이미 꺼져 있으면 종료 ───────────────────
curl -s -o nul -w "%%{http_code}" http://localhost:%PORT%/health 2>nul | findstr "200" >nul 2>&1
if errorlevel 1 (
    echo [INFO] 서버가 응답하지 않습니다. 이미 종료된 것 같습니다.
    if exist "%PID_FILE%" del "%PID_FILE%" >nul 2>&1
    echo.
    pause
    exit /b 0
)
echo [INFO] 서버 응답 확인 (port %PORT%). 종료를 진행합니다...

:: ── [2] PID 파일에서 읽기 ────────────────────────────────────
set "FOUND_PID="
if exist "%PID_FILE%" (
    set /p FOUND_PID=<"%PID_FILE%"
    wmic process where "processid='!FOUND_PID!'" get processid >nul 2>&1
    if errorlevel 1 (
        set "FOUND_PID="
        del "%PID_FILE%" >nul 2>&1
    )
)

:: ── [3] wmic 폴백 — JAR 이름 기반 ───────────────────────────
if not defined FOUND_PID (
    echo [INFO] PID 파일 없음. JAR 이름으로 프로세스를 검색합니다...
    for /f "skip=1 delims=" %%L in (
        'wmic process where "name='java.exe' and commandline like '%%java-monitor%%'" get processid 2^>nul'
    ) do (
        for /f "tokens=1" %%P in ("%%L") do (
            if not "%%P"=="" if "!FOUND_PID!"=="" set "FOUND_PID=%%P"
        )
    )
)

:: ── [4] netstat 폴백 — 포트 기반 ────────────────────────────
if not defined FOUND_PID (
    echo [INFO] 포트 %PORT% 로 재검색합니다...
    for /f "tokens=5" %%P in (
        'netstat -ano 2^>nul ^| findstr ":%PORT% " ^| findstr "LISTENING"'
    ) do (
        if not "%%P"=="" if "!FOUND_PID!"=="" set "FOUND_PID=%%P"
    )
)

if not defined FOUND_PID (
    echo [WARN] 실행 중인 프로세스를 찾을 수 없습니다.
    echo.
    pause
    exit /b 1
)

:: ── [5] 프로세스 종료 ─────────────────────────────────────────
echo [INFO] 종료 중... PID: %FOUND_PID%
taskkill /PID %FOUND_PID% /F >nul 2>&1

if %ERRORLEVEL% EQU 0 (
    echo [INFO] 정상 종료되었습니다. (PID: %FOUND_PID%)
    if exist "%PID_FILE%" del "%PID_FILE%" >nul 2>&1
) else (
    echo [WARN] 종료에 실패했습니다. 관리자 권한으로 다시 실행하거나
    echo        아래 명령을 직접 실행하세요:
    echo          taskkill /PID %FOUND_PID% /F
)

echo.
pause
