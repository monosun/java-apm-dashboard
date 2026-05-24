@echo off
setlocal

:: ─────────────────────────────────────────────────────────
:: deploy.bat  — 빌드 후 즉시 실행 (one-shot 배포)
::   사용법: deploy.bat [port] [config-file]
:: ─────────────────────────────────────────────────────────

set "SCRIPT_DIR=%~dp0"

echo [DEPLOY] 빌드 시작...
call "%SCRIPT_DIR%build.bat" clean
if %ERRORLEVEL% NEQ 0 exit /b 1

echo.
echo [DEPLOY] 실행 시작...
call "%SCRIPT_DIR%run.bat" %1 %2
endlocal
