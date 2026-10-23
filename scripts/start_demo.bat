@echo off
rem One-click demo: DW server + 5 mock cars (instead of MATLAB) + 1 mock phone + browser.
setlocal
cd /d "%~dp0.."
start "MiniDiDi DW server" cmd /c scripts\start_server.bat --scenario demo
echo Waiting for the server to start...
timeout /t 5 /nobreak >nul
start "" http://localhost:8080
set PY=python
where python >nul 2>nul
if errorlevel 1 set PY=py -3
start "Mock devices" cmd /k %PY% tools\mock_devices.py --cars 5 --phones 1 --kind matlab
