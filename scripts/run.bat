@echo off
setlocal EnableDelayedExpansion

:: ============================================================
:: Java Performance Monitor - Windows Run Script
:: Usage:
::   run.bat              Build and run demo
::   run.bat build        Build only (Maven)
::   run.bat run          Run existing JAR (skip build)
::   run.bat dev          Compile with javac + run (no Maven)
::   run.bat clean        Delete build output
:: ============================================================

set SCRIPT_DIR=%~dp0
set PROJECT_DIR=%SCRIPT_DIR%..
set TARGET_JAR=%PROJECT_DIR%\target\java-monitor-1.6.0.jar
set MAIN_CLASS=com.monosun.monitor.demo.MonitoringDemo
set SRC_DIR=%PROJECT_DIR%\src\main\java
set CLASS_DIR=%PROJECT_DIR%\target\classes
set LOG_DIR=%PROJECT_DIR%\logs

:: JVM options (recommended for production)
set JVM_OPTS=-Xms256m -Xmx512m
set JVM_OPTS=%JVM_OPTS% -XX:+UseG1GC
set JVM_OPTS=%JVM_OPTS% -XX:MaxGCPauseMillis=200
set JVM_OPTS=%JVM_OPTS% -XX:+HeapDumpOnOutOfMemoryError
set JVM_OPTS=%JVM_OPTS% -XX:HeapDumpPath=%LOG_DIR%\heapdump.hprof
set JVM_OPTS=%JVM_OPTS% -Djava.util.logging.config.file=%SCRIPT_DIR%logging.properties

set CMD=%1
if "%CMD%"=="" set CMD=all

:: Create logs directory
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

echo.
echo  ==========================================
echo   Java APM Dashboard v1.6.0
echo   Package: com.monosun.monitor
echo  ==========================================
echo.

:: -- Check Java -------------------------------------------------
where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found. Check JAVA_HOME or PATH.
    exit /b 1
)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VERSION=%%v
echo [INFO] Java: %JAVA_VERSION%

:: -- Route command ----------------------------------------------
if /i "%CMD%"=="clean"  goto :DO_CLEAN
if /i "%CMD%"=="build"  goto :DO_BUILD
if /i "%CMD%"=="run"    goto :DO_RUN_JAR
if /i "%CMD%"=="dev"    goto :DO_DEV
if /i "%CMD%"=="all"    goto :DO_ALL
echo [ERROR] Unknown command: %CMD%
goto :USAGE

:DO_ALL
call :DO_BUILD
if errorlevel 1 exit /b 1
goto :DO_RUN_JAR

:: -- Build with Maven -------------------------------------------
:DO_BUILD
echo [BUILD] Starting Maven build...
where mvn >nul 2>&1
if errorlevel 1 (
    echo [WARN] Maven not found. Switching to javac dev mode.
    goto :DO_DEV
)
pushd "%PROJECT_DIR%"
call mvn package -q -DskipTests
popd
if errorlevel 1 (
    echo [ERROR] Build failed.
    exit /b 1
)
echo [BUILD] Done: %TARGET_JAR%
exit /b 0

:: -- Run JAR ----------------------------------------------------
:DO_RUN_JAR
if not exist "%TARGET_JAR%" (
    echo [ERROR] JAR not found: %TARGET_JAR%
    echo [ERROR] Run build first: run.bat build
    exit /b 1
)
echo [RUN] Starting in JAR mode...
echo [RUN] Dashboard:    http://localhost:9090/dashboard
echo [RUN] Metrics:      http://localhost:9090/metrics
echo [RUN] Press Ctrl+C to stop
echo.
java %JVM_OPTS% -jar "%TARGET_JAR%"
goto :EOF

:: -- Dev mode: compile with javac (no Maven) --------------------
:DO_DEV
echo [DEV] Compiling with javac (no Maven)...
if not exist "%CLASS_DIR%" mkdir "%CLASS_DIR%"

:: Collect source files (exclude servlet and jaxrs)
set SOURCES=
for /r "%SRC_DIR%\com\monosun\monitor" %%f in (*.java) do (
    echo %%f | findstr /i "servlet jaxrs" >nul
    if errorlevel 1 set SOURCES=!SOURCES! "%%f"
)

if "!SOURCES!"=="" (
    echo [ERROR] No source files found.
    exit /b 1
)

javac --release 21 -encoding UTF-8 -d "%CLASS_DIR%" -sourcepath "%SRC_DIR%" !SOURCES!
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    exit /b 1
)

echo [DEV] Compilation done. Starting...
echo [DEV] Dashboard:    http://localhost:9090/dashboard
echo [DEV] Metrics:      http://localhost:9090/metrics
echo [DEV] Press Ctrl+C to stop
echo.
java %JVM_OPTS% -cp "%CLASS_DIR%" %MAIN_CLASS%
goto :EOF

:: -- Clean ------------------------------------------------------
:DO_CLEAN
echo [CLEAN] Removing target directory...
if exist "%PROJECT_DIR%\target" rmdir /s /q "%PROJECT_DIR%\target"
echo [CLEAN] Done.
goto :EOF

:USAGE
echo.
echo Usage: run.bat [command]
echo   (none)   Build + run
echo   build    Maven build only
echo   run      Run existing JAR
echo   dev      Compile with javac + run (no Maven required)
echo   clean    Delete build output
echo.
exit /b 1
