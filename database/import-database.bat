@echo off
setlocal
title Burn AI Database Import
color 0A
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0import-database.ps1"
set "RESULT=%ERRORLEVEL%"
pause
exit /b %RESULT%
