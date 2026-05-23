@echo off
setlocal EnableDelayedExpansion

set "PORT=9090"
set "FOUND_PID="

echo.
echo  =============================================
echo   Java APM Dashboard v1.6.0  ^|  종료
echo  =============================================
echo.

:: ── [1] 헬스 체크 — 이미 꺼져 있으면 바로 종료 ─────────────────
curl -s -o nul -w "%%{http_code}" http://localhost:%PORT%/health 2>nul | findstr "200" >nul 2>&1
if errorlevel 1 (
    echo [INFO] http://localhost:%PORT%/ 에 응답이 없습니다. 이미 종료된 것 같습니다.
    echo.
    pause
    exit /b 0
)
echo [INFO] 서버 응답 확인 (port %PORT%) — 프로세스를 검색합니다...

:: ── [2] JAR 이름으로 PID 탐색 (wmic) ────────────────────────────
for /f "skip=1 delims=" %%L in (
    'wmic process where "name='java.exe' and commandline like '%%java-monitor%%'" get processid 2^>nul'
) do (
    for /f "tokens=1" %%P in ("%%L") do (
        if not "%%P"=="" set "FOUND_PID=%%P"
    )
)

:: ── [3] 폴백: 포트로 LISTENING 프로세스 탐색 ─────────────────────
if not defined FOUND_PID (
    echo [INFO] JAR명으로 찾지 못했습니다. 포트 %PORT% 로 재검색합니다...
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

:: ── [4] 프로세스 종료 ─────────────────────────────────────────────
echo [INFO] PID %FOUND_PID% 종료 중...
taskkill /PID %FOUND_PID% /F >nul 2>&1

if %ERRORLEVEL% EQU 0 (
    echo [INFO] 정상 종료되었습니다.  (PID: %FOUND_PID%)
) else (
    echo [WARN] 종료에 실패했습니다.
    echo        관리자 권한으로 다시 실행하거나 아래 명령을 직접 실행하세요:
    echo          taskkill /PID %FOUND_PID% /F
)

echo.
pause
