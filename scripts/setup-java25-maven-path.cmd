@echo off
set "SCRIPT_DIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%setup-java25-maven-path.ps1"
if errorlevel 1 exit /b 1

