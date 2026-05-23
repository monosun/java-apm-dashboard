@echo off
setlocal

set "JAVA=D:\jdk\openjdk\jdk-21.0.8\bin\java.exe"
set "MVN=D:\programs\apache-maven-3.9.9\bin\mvn.cmd"
set "SCRIPT_DIR=%~dp0"
set "DASHBOARD=http://localhost:9090/dashboard"

echo.
echo  =============================================
echo   Java APM Dashboard v1.6.0
echo   Dashboard: %DASHBOARD%
echo  =============================================
echo.

:: Java 확인
if not exist "%JAVA%" (
    where java >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] Java를 찾을 수 없습니다.
        pause & exit /b 1
    )
    set "JAVA=java"
)

:: JAR 찾기 (최신 버전 자동 선택)
set "JAR="
for %%f in ("%SCRIPT_DIR%target\java-monitor-*.jar") do (
    echo %%f | findstr /i "original shaded" >nul || set "JAR=%%f"
)

:: JAR 없으면 빌드
if not defined JAR (
    echo [INFO] JAR 파일 없음 — 빌드를 시작합니다...
    if not exist "%MVN%" (
        echo [ERROR] Maven을 찾을 수 없습니다: %MVN%
        pause & exit /b 1
    )
    call "%MVN%" -f "%SCRIPT_DIR%pom.xml" package -q
    if %ERRORLEVEL% NEQ 0 ( echo [ERROR] 빌드 실패 & pause & exit /b 1 )
    for %%f in ("%SCRIPT_DIR%target\java-monitor-*.jar") do (
        echo %%f | findstr /i "original shaded" >nul || set "JAR=%%f"
    )
)

echo [INFO] JAR: %JAR%
echo [INFO] 3초 후 브라우저를 자동으로 엽니다...
start "" /B cmd /C "timeout /T 3 /NOBREAK >nul && start %DASHBOARD%"

echo [INFO] Ctrl+C 로 종료합니다.
echo.
"%JAVA%" -Xms256m -Xmx512m -XX:+UseG1GC ^
         -Dserver.http.port=9090 ^
         -jar "%JAR%"

echo.
echo [INFO] 서버 종료.
pause
