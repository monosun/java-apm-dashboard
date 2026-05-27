@echo off
setlocal

:: ─────────────────────────────────────────────────────────
:: build.bat  — Java APM Monitor Build Script
::   Usage: build.bat [clean]
:: ─────────────────────────────────────────────────────────

set "JAVA_HOME=D:\jdk\openjdk\jdk-21.0.8"
set "MVN=D:\programs\apache-maven-3.9.9\bin\mvn.cmd"
set "SCRIPT_DIR=%~dp0"
set "PROJECT_DIR=%SCRIPT_DIR%.."

if "%1"=="clean" (
    echo [BUILD] Clean build...
    call "%MVN%" -f "%PROJECT_DIR%\pom.xml" clean package -q
) else (
    echo [BUILD] Building...
    call "%MVN%" -f "%PROJECT_DIR%\pom.xml" package -q
)

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Build failed!
    exit /b 1
)

echo [BUILD] Build complete: %PROJECT_DIR%\target\java-monitor-*.jar
endlocal
