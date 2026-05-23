@echo off
setlocal EnableDelayedExpansion

:: ============================================================
::  shutdown.bat -- Java APM Dashboard v1.6.0
::
::  Steps:
::    1. Health check -- skip if server already down
::    2. Read PID from logs\monitor.pid
::    3. Fallback: wmic JAR-name search
::    4. Fallback: netstat port search
::    5. taskkill /F, then delete PID file
:: ============================================================

set "SCRIPT_DIR=%~dp0"
set "PROJECT_DIR=%SCRIPT_DIR%.."
set "LOG_DIR=%PROJECT_DIR%\logs"
set "PID_FILE=%LOG_DIR%\monitor.pid"
set "PORT=9090"

echo.
echo  =============================================
echo   Java APM Dashboard v1.6.0  ^|  Shutdown
echo  =============================================
echo.

:: -- [1] Health check: skip if already down ------------------
curl -s -o nul -w "%%{http_code}" http://localhost:%PORT%/health 2>nul | findstr "200" >nul 2>&1
if errorlevel 1 (
    echo [INFO] Server not responding. Already stopped.
    if exist "%PID_FILE%" del "%PID_FILE%" >nul 2>&1
    echo.
    pause
    exit /b 0
)
echo [INFO] Server is up (port %PORT%). Proceeding with shutdown...

:: -- [2] Read PID from file ----------------------------------
set "FOUND_PID="
if exist "%PID_FILE%" (
    set /p FOUND_PID=<"%PID_FILE%"
    wmic process where "processid='!FOUND_PID!'" get processid >nul 2>&1
    if errorlevel 1 (
        set "FOUND_PID="
        del "%PID_FILE%" >nul 2>&1
    )
)

:: -- [3] Fallback: wmic JAR-name search ----------------------
if not defined FOUND_PID (
    echo [INFO] PID file not found. Searching by JAR name...
    for /f "skip=1 delims=" %%L in (
        'wmic process where "name='java.exe' and commandline like '%%java-monitor%%'" get processid 2^>nul'
    ) do (
        for /f "tokens=1" %%P in ("%%L") do (
            if not "%%P"=="" if "!FOUND_PID!"=="" set "FOUND_PID=%%P"
        )
    )
)

:: -- [4] Fallback: netstat port search -----------------------
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

:: -- [5] Kill process ----------------------------------------
echo [INFO] Stopping PID: %FOUND_PID%
taskkill /PID %FOUND_PID% /F >nul 2>&1

if %ERRORLEVEL% EQU 0 (
    echo [INFO] Server stopped. (PID: %FOUND_PID%)
    if exist "%PID_FILE%" del "%PID_FILE%" >nul 2>&1
) else (
    echo [WARN] Failed to stop. Try running as Administrator:
    echo          taskkill /PID %FOUND_PID% /F
)

echo.
pause
