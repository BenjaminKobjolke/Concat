@echo off
REM Build the Android APK and upload it, plus its version sidecar, under a fixed name.
REM Usage: build_and_upload_android.bat [debug|release] [--dry-run] [--verbose]
REM        (default: release)
REM
REM All the work is done by `release-tool android`: bump the build number
REM (release builds only), run the build, verify the APK, and upload it to FTP.
REM Settings live in android_release.ini; the release-tool checkout path in config.bat.
setlocal

REM Captured before the loop below: `shift` also shifts %0, so %~dp0 is empty afterwards.
set "TOOLS_DIR=%~dp0"

REM Machine-specific paths are kept out of git: config.bat is a local copy of config_example.bat.
if not exist "%TOOLS_DIR%config.bat" (
    echo ERROR: tools\config.bat not found. Copy tools\config_example.bat to tools\config.bat and adjust it.
    exit /b 2
)
call "%TOOLS_DIR%config.bat"
if not exist "%RELEASE_TOOL_DIR%\pyproject.toml" (
    echo ERROR: RELEASE_TOOL_DIR in tools\config.bat is not a release-tool checkout: "%RELEASE_TOOL_DIR%"
    exit /b 2
)

REM Translate the historical `debug`/`release` positional into the tool's flags
REM and pass everything else through. Built in a loop because %* ignores shift.
set "ARGS="
:parse_args
if "%~1"=="" goto run
if /i "%~1"=="debug" (
    set "ARGS=%ARGS% --debug"
) else if /i "%~1"=="release" (
    rem release is the default; nothing to pass
) else (
    set "ARGS=%ARGS% %~1"
)
shift
goto parse_args

:run
REM cd into the release-tool repo so `uv run` resolves its venv (release-tool is
REM not on PATH), then point it back at this project.
cd /d "%RELEASE_TOOL_DIR%"
call uv run python -m release_tool android "%TOOLS_DIR%android_release.ini" --project-root "%TOOLS_DIR%.."%ARGS%
set "EXIT_CODE=%ERRORLEVEL%"
cd /d "%TOOLS_DIR%"
endlocal & exit /b %EXIT_CODE%
