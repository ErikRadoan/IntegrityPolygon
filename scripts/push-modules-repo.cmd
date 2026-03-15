@echo off
setlocal enabledelayedexpansion

set "REMOTE_URL=https://github.com/ErikRadoan/IntegrityPolygon.git"
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "ROOT=%%~fI"
set "MODULES_DIR=%ROOT%\modules"

if not exist "%MODULES_DIR%" (
    echo [ERROR] Modules directory not found: "%MODULES_DIR%"
    exit /b 1
)

if not exist "%MODULES_DIR%\.git" (
    echo [ERROR] "%MODULES_DIR%" is not a nested git repo.
    echo         Initialize it first or clone the nested repo there.
    exit /b 1
)

pushd "%MODULES_DIR%"
git rev-parse --is-inside-work-tree >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Git repository check failed in "%MODULES_DIR%".
    popd
    exit /b 1
)

for /f "delims=" %%U in ('git remote get-url origin 2^>nul') do set "CURRENT_REMOTE=%%U"
if not defined CURRENT_REMOTE (
    git remote add origin "%REMOTE_URL%"
) else if /I not "!CURRENT_REMOTE!"=="%REMOTE_URL%" (
    git remote set-url origin "%REMOTE_URL%"
)

if "%~1"=="" (
    set "COMMIT_MSG=Update modules"
) else (
    set "COMMIT_MSG=%*"
)

git add -A
git diff --cached --quiet
if errorlevel 1 (
    git commit -m "!COMMIT_MSG!"
    if errorlevel 1 (
        echo [ERROR] Commit failed.
        popd
        exit /b 1
    )
) else (
    echo [INFO] No staged module changes to commit.
)

for /f "delims=" %%B in ('git rev-parse --abbrev-ref HEAD') do set "BRANCH=%%B"
if not defined BRANCH (
    echo [ERROR] Could not resolve current branch.
    popd
    exit /b 1
)

git push -u origin "!BRANCH!"
if errorlevel 1 (
    echo [ERROR] Push failed.
    popd
    exit /b 1
)

echo [OK] Modules repo pushed to origin/!BRANCH!.
popd
endlocal

