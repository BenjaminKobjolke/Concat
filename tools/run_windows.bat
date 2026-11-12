@echo off
REM Builds and starts the Concat desktop app (Windows).
REM For manual use: the run lasts until the app window is closed, so do not
REM register it as a Tickets Watcher command.

REM Cargo workspace root is src\, not the repo root.
pushd "%~dp0..\src"
cargo run --profile quick -p concat -- %*
set RUNRESULT=%ERRORLEVEL%
popd
exit /b %RUNRESULT%
