@echo off
setlocal EnableDelayedExpansion

:: ============================================================
::  stop.bat -- Java APM Dashboard v1.12.2
::  Quick stop: no PID file, wmic + netstat fallback.
::  For managed stop (with PID file), use shutdown.bat.
:: ============================================================

set "PORT=9090"
set "FOUND_PID="

echo.
echo  =============================================
echo   Java APM Dashboard v1.12.2  ^|  Stop
echo  =============================================
echo.

:: -- [1] Health check: skip if already down ------------------
curl -s -o nul -w "%%{http_code}" http://localhost:%PORT%/health 2>nul | findstr "200" >nul 2>&1
if errorlevel 1 (
    echo [INFO] Server not responding. Already stopped.
    echo.
    pause
    exit /b 0
)
echo [INFO] Server is up (port %PORT%). Searching for process...

:: -- [2] wmic JAR-name search --------------------------------
for /f "skip=1 delims=" %%L in (
    'wmic process where "name='java.exe' and commandline like '%%java-monitor%%'" get processid 2^>nul'
) do (
    for /f "tokens=1" %%P in ("%%L") do (
        if not "%%P"=="" set "FOUND_PID=%%P"
    )
)

:: -- [3] Fallback: netstat port search -----------------------
if not defined FOUND_PID (
    echo [INFO] Searching by port %PORT%...
    for /f "tokens=5" %%P in (
        'netstat -ano 2^>nul ^| findstr ":%PORT% " ^| findstr "LISTENING"'
    ) do (
        if not "%%P"=="" if "!FOUND_PID!"=="" set "FOUND_PID=%%P"
    )
)

if not defined FOUND_PID (
    echo [WARN] No running process found.
    echo.
    pause
    exit /b 1
)

:: -- [4] Kill process ----------------------------------------
echo [INFO] Stopping PID: %FOUND_PID%
taskkill /PID %FOUND_PID% /F >nul 2>&1

if %ERRORLEVEL% EQU 0 (
    echo [INFO] Server stopped. (PID: %FOUND_PID%)
) else (
    echo [WARN] Failed to stop. Try running as Administrator:
    echo          taskkill /PID %FOUND_PID% /F
)

echo.
pause
