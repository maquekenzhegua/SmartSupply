@echo off
rem SmartSupply one-click stop (double-click or run from terminal)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-all.ps1" %*
echo.
pause
