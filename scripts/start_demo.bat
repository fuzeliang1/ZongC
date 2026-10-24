@echo off
rem One-click autonomous demo (no manual operation needed after start):
rem   1. DW server running the "demo" scenario script (roadblocks, unknown obstacles, rush hours ... loop forever)
rem   2. 5 simulated cars: the MATLAB fleet (run_fleet) if MATLAB is installed, otherwise Python mock cars
rem   3. 1 mock phone (pedestrian)
rem   4. browser: 2D main view + 3D view (auto tour)
rem Usage: start_demo.bat [scenario]   e.g. start_demo.bat perception
setlocal
cd /d "%~dp0.."
set SCENARIO=demo
if not "%~1"=="" set SCENARIO=%~1
start "MiniDiDi DW server" cmd /c scripts\start_server.bat --scenario %SCENARIO%
echo Waiting for the server to start...
rem ping instead of timeout: timeout fails when input is redirected (CI, some IDE terminals)
ping -n 7 127.0.0.1 >nul
set PY=python
where python >nul 2>nul
if errorlevel 1 set PY=py -3
where matlab >nul 2>nul
if errorlevel 1 goto mockcars
echo Starting the MATLAB fleet (5 cars). MATLAB needs about half a minute to start;
echo until then the digital world drives the cars itself.
start "MATLAB fleet" matlab -nosplash -r "cd('%CD%\matlab'); run_fleet"
goto phones
:mockcars
echo MATLAB not found: using Python mock cars instead of the MATLAB fleet.
start "Mock cars" cmd /k %PY% tools\mock_devices.py --cars 5 --phones 0 --kind matlab
:phones
start "Mock phone" cmd /k %PY% tools\mock_devices.py --cars 0 --phones 1
start "" http://localhost:8080
start "" "http://localhost:8080/3d.html?mode=tour"
echo Demo started: 2D http://localhost:8080   3D http://localhost:8080/3d.html
