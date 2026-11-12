@echo off
rem Builds the Android APK in WSL Ubuntu. One-time setup first: see setup.sh.
setlocal
rem The WSL clone builds what refs/wsl-build names: a snapshot of the working tree,
rem so uncommitted edits to tracked files are built too. `stash create` prints
rem nothing when the tree is clean, and changes neither the tree nor the stash list.
set "REV=HEAD"
for /f %%h in ('git -C "%~dp0." stash create') do set "REV=%%h"
git -C "%~dp0." update-ref refs/wsl-build %REV% || exit /b 1
wsl -d Ubuntu --cd "%~dp0" -- bash ./build-apk.sh
exit /b %ERRORLEVEL%
