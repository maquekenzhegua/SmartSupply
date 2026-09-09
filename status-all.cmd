@echo off
rem SmartSupply health status
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\status-all.ps1" %*
echo.
pause
