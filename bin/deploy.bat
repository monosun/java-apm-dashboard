@echo off
setlocal

:: ─────────────────────────────────────────────────────────
:: deploy.bat  — Build and run immediately (one-shot deploy)
::   Usage: deploy.bat [port] [config-file]
:: ─────────────────────────────────────────────────────────

set "SCRIPT_DIR=%~dp0"

echo [DEPLOY] Starting build...
call "%SCRIPT_DIR%build.bat" clean
if %ERRORLEVEL% NEQ 0 exit /b 1

echo.
echo [DEPLOY] Starting server...
call "%SCRIPT_DIR%run.bat" %1 %2
endlocal
