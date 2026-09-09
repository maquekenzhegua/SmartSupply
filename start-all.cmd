@echo off
rem SmartSupply one-click start (double-click or run from terminal)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-all.ps1" %*
echo.
pause
