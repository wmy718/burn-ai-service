@echo off
setlocal
title Burn AI ui - start
color 0A
if exist "%~dp0config-local.bat" call "%~dp0config-local.bat"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\scripts\manage-service.ps1" -Service ui -Action start
set "RESULT=%ERRORLEVEL%"
if /I not "%~1"=="--no-pause" pause
exit /b %RESULT%