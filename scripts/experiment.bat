@echo off
rem Offline experiment (no server needed). Example:
rem   scripts\experiment.bat --minutes 30 --seeds 1,2,3 --strategies GREEDY,HUNGARIAN --csv results\cmp.csv
setlocal
cd /d "%~dp0.."
call scripts\build.bat >nul
if errorlevel 1 exit /b 1
if not exist results mkdir results
java -cp "server\build\classes;server\lib\gson-2.13.1.jar" com.zongc.dw.tools.Experiment --config server\config\default.json %*
