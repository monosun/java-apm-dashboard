@echo off
setlocal

:: ─────────────────────────────────────────────────────────
:: build.bat  — Java APM Monitor 빌드 스크립트
::   사용법: build.bat [clean]
:: ─────────────────────────────────────────────────────────

set "JAVA_HOME=D:\jdk\openjdk\jdk-21.0.8"
set "MVN=D:\programs\apache-maven-3.9.9\bin\mvn.cmd"
set "PROJECT_DIR=%~dp0.."

if "%1"=="clean" (
    echo [BUILD] 클린 후 빌드...
    call "%MVN%" -f "%PROJECT_DIR%\pom.xml" clean package -q
) else (
    echo [BUILD] 빌드 중...
    call "%MVN%" -f "%PROJECT_DIR%\pom.xml" package -q
)

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] 빌드 실패!
    exit /b 1
)

echo [BUILD] 빌드 완료: %PROJECT_DIR%\target\java-monitor-*.jar
endlocal
