@echo off
setlocal

set "JAVA=D:\jdk\openjdk\jdk-21.0.8\bin\java.exe"
set "MVN=D:\programs\apache-maven-3.9.9\bin\mvn.cmd"
set "SCRIPT_DIR=%~dp0"
set "PROJECT_DIR=%SCRIPT_DIR%.."
set "DASHBOARD=http://localhost:9090/dashboard"

echo.
echo  =============================================
echo   Java APM Dashboard v1.12.1
echo   Dashboard: %DASHBOARD%
echo  =============================================
echo.

:: Check Java
if not exist "%JAVA%" (
    where java >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] Java not found.
        pause & exit /b 1
    )
    set "JAVA=java"
)

:: Find JAR (exclude original / agent / integration)
set "JAR="
for %%f in ("%PROJECT_DIR%\target\java-monitor-*.jar") do (
    echo %%f | findstr /i "original agent integration" >nul || set "JAR=%%f"
)

:: Build if no JAR found
if not defined JAR (
    echo [INFO] No JAR found -- starting build...
    if not exist "%MVN%" (
        echo [ERROR] Maven not found: %MVN%
        pause & exit /b 1
    )
    call "%MVN%" -f "%PROJECT_DIR%\pom.xml" package -q
    if %ERRORLEVEL% NEQ 0 ( echo [ERROR] Build failed & pause & exit /b 1 )
    for %%f in ("%PROJECT_DIR%\target\java-monitor-*.jar") do (
        echo %%f | findstr /i "original agent integration" >nul || set "JAR=%%f"
    )
)

echo [INFO] JAR: %JAR%
echo [INFO] Opening browser in 3 seconds...
start "" /B cmd /C "timeout /T 3 /NOBREAK >nul && start %DASHBOARD%"

echo [INFO] Press Ctrl+C to stop.
echo.
"%JAVA%" -Xms256m -Xmx512m -XX:+UseG1GC ^
         -Djava.util.logging.config.file="%SCRIPT_DIR%logging.properties" ^
         -Dserver.http.port=9090 ^
         -jar "%JAR%"

echo.
echo [INFO] Server stopped.
pause
