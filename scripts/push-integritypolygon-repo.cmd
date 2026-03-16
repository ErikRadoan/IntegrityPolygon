@echo off
setlocal enabledelayedexpansion

set "REMOTE_URL=https://github.com/ErikRadoan/IntegrityPolygon.git"
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "ROOT=%%~fI"
set "DRY_RUN=0"

:parse_args
if "%~1"=="" goto args_done
if /I "%~1"=="--dry-run" (
    set "DRY_RUN=1"
) else (
    if defined COMMIT_MSG (
        set "COMMIT_MSG=!COMMIT_MSG! %~1"
    ) else (
        set "COMMIT_MSG=%~1"
    )
)
shift
goto parse_args

:args_done
if not defined COMMIT_MSG set "COMMIT_MSG=Update IntegrityPolygon workspace"

if not exist "%ROOT%" (
    echo [ERROR] Workspace root not found: "%ROOT%"
    exit /b 1
)

pushd "%ROOT%"

if not exist "%ROOT%\.git" (
    echo [WARN] No local .git found in workspace root. Initializing nested repo...
    git init
    if errorlevel 1 (
        echo [ERROR] Could not initialize git repo in "%ROOT%".
        popd
        exit /b 1
    )
)

for /f "delims=" %%U in ('git remote get-url origin 2^>nul') do set "CURRENT_REMOTE=%%U"
if not defined CURRENT_REMOTE (
    git remote add origin "%REMOTE_URL%"
) else if /I not "!CURRENT_REMOTE!"=="%REMOTE_URL%" (
    git remote set-url origin "%REMOTE_URL%"
)

echo [INFO] Target repo: "%ROOT%"
if "%DRY_RUN%"=="1" (
    echo [INFO] Dry-run mode enabled. No commit or push will be performed.
)

if "%DRY_RUN%"=="1" (
    echo [INFO] Workspace changes preview:
    git status --short
) else (
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
        echo [INFO] No staged changes to commit.
    )
)

for /f "delims=" %%B in ('git branch --show-current') do set "BRANCH=%%B"
if not defined BRANCH (
    set "BRANCH=main"
    if "%DRY_RUN%"=="0" (
        git checkout -B !BRANCH! >nul 2>&1
    )
)

if "%DRY_RUN%"=="1" (
    echo [INFO] Current branch: !BRANCH!
    git status --short
    echo [OK] Dry-run completed.
) else (
    git push -u origin "!BRANCH!"
    if errorlevel 1 (
        echo [ERROR] Push failed.
        popd
        exit /b 1
    )
    echo [OK] Root repo pushed to origin/!BRANCH!.
)

popd
endlocal

