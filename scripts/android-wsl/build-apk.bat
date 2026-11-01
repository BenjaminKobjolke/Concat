@echo off
rem Builds the Android APK in WSL Ubuntu. One-time setup first: see setup.sh.
wsl -d Ubuntu --cd "%~dp0" -- bash ./build-apk.sh
