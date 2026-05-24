@echo off
setlocal EnableDelayedExpansion

:: ============================================================
::  startup.bat -- Java APM Dashboard v1.11.0
::
::  Steps:
::    1. Prevent duplicate launch (PID file check)
::    2. Find latest JAR under target\
::    3. Start JVM as minimized background process
::    4. Detect PID via wmic, save to logs\monitor.pid
::    5. Health-check loop, open browser on success
:: ============================================================

set "SCRIPT_DIR=%~dp0"
set "PROJECT_DIR=%SCRIPT_DIR%.."
set "LOG_DIR=%PROJECT_DIR%\logs"
set "PID_FILE=%LOG_DIR%\monitor.pid"
set "PORT=9090"
set "DASHBOARD=http://localhost:%PORT%/dashboard"

:: Java path (PATH first, then fixed path)
set "JAVA=java"
if exist "D:\jdk\openjdk\jdk-21.0.8\bin\java.exe" (
    set "JAVA=D:\jdk\openjdk\jdk-21.0.8\bin\java.exe"
)

echo.
echo  =============================================
echo   Java APM Dashboard v1.11.0  ^|  Start
echo   Dashboard : %DASHBOARD%
echo  =============================================
echo.

:: -- [1] Create logs directory --------------------------------
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

:: -- [2] Prevent duplicate launch ----------------------------
if exist "%PID_FILE%" (
    set /p EXISTING_PID=<"%PID_FILE%"
    wmic process where "processid='!EXISTING_PID!'" get processid >nul 2>&1
    if not errorlevel 1 (
        echo [WARN] Already running. PID: !EXISTING_PID!
        echo [WARN] Run bin\shutdown.bat to stop.
        echo.
        pause
        exit /b 0
    )
    del "%PID_FILE%" >nul 2>&1
)

:: -- [3] Find JAR (exclude original / agent / integration) ---
set "JAR="
for %%f in ("%PROJECT_DIR%\target\java-monitor-*.jar") do (
    echo %%f | findstr /i "original agent integration" >nul || set "JAR=%%f"
)

if not defined JAR (
    echo [ERROR] No JAR found. Run bin\build.bat first.
    echo.
    pause
    exit /b 1
)
echo [INFO] JAR : %JAR%

:: -- [4] Build JVM arguments ---------------------------------
set "JVM=-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
set "JVM=%JVM% -XX:+HeapDumpOnOutOfMemoryError"
set "JVM=%JVM% -XX:HeapDumpPath=%LOG_DIR%\heapdump.hprof"
set "JVM=%JVM% -Djava.util.logging.config.file=%SCRIPT_DIR%logging.properties"
set "JVM=%JVM% -Dserver.http.port=%PORT%"

:: -- [5] Start as minimized independent window ---------------
echo [INFO] Starting server...
start "Java APM Dashboard" /MIN "%JAVA%" %JVM% -jar "%JAR%"

:: -- [6] Detect PID (retry up to 10s) -----------------------
echo [INFO] Detecting PID...
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
    echo [INFO] PID : !PID!  saved : %PID_FILE%
) else (
    echo [WARN] Could not detect PID automatically.
)

:: -- [7] Health-check loop (up to 30s) ----------------------
echo [INFO] Waiting for server to be ready...
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
    echo [INFO] Server started successfully.
    echo [INFO] Dashboard : %DASHBOARD%
    start "" "%DASHBOARD%"
) else (
    echo [WARN] Health check timed out. Check logs: %LOG_DIR%
)

echo.
pause
