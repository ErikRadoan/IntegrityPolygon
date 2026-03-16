@echo off
setlocal enabledelayedexpansion

set "REMOTE_URL=https://github.com/ErikRadoan/IntegrityPolygon-Modules.git"
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "ROOT=%%~fI"
set "MODULES_DIR=%ROOT%\repo"
set "TARGET_MODULES=modules"
set "TARGET_INDEX=modules.json"
set "TARGET_CHECKSUMS=checksums.json"
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
if not defined COMMIT_MSG set "COMMIT_MSG=Update module artifacts"

if not exist "%MODULES_DIR%" (
    echo [ERROR] Nested repo directory not found: "%MODULES_DIR%"
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

echo [INFO] Target repo: "%MODULES_DIR%"
echo [INFO] Staging paths: "%TARGET_MODULES%", "%TARGET_INDEX%", "%TARGET_CHECKSUMS%"
if "%DRY_RUN%"=="1" (
    echo [INFO] Dry-run mode enabled. No commit or push will be performed.
)

echo [INFO] Syncing module artifacts and repository metadata...
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%generate-modules-repo.ps1" -ProjectRoot "%ROOT%" -RepoRoot "%MODULES_DIR%"
if errorlevel 1 (
    echo [ERROR] Failed to generate repo modules/checksums metadata.
    popd
    exit /b 1
)

if "%DRY_RUN%"=="1" (
    echo [INFO] Module path changes preview:
    git status --short -- "%TARGET_MODULES%" "%TARGET_INDEX%" "%TARGET_CHECKSUMS%"
) else (
    git add -A -- "%TARGET_MODULES%" "%TARGET_INDEX%" "%TARGET_CHECKSUMS%"
    git diff --cached --quiet
    if errorlevel 1 (
        git commit -m "!COMMIT_MSG!"
        if errorlevel 1 (
            echo [ERROR] Commit failed.
            popd
            exit /b 1
        )
    ) else (
        echo [INFO] No staged changes in configured staging paths.
    )
)

for /f "delims=" %%B in ('git rev-parse --abbrev-ref HEAD') do set "BRANCH=%%B"
if not defined BRANCH (
    echo [ERROR] Could not resolve current branch.
    popd
    exit /b 1
)

if "%DRY_RUN%"=="1" (
    echo [INFO] Current branch: !BRANCH!
    git status --short -- "%TARGET_MODULES%" "%TARGET_INDEX%" "%TARGET_CHECKSUMS%"
    echo [OK] Dry-run completed.
) else (
    git push -u origin "!BRANCH!"
    if errorlevel 1 (
        echo [ERROR] Push failed.
        popd
        exit /b 1
    )
    echo [OK] Modules repo pushed to origin/!BRANCH!.
)

popd
endlocal

