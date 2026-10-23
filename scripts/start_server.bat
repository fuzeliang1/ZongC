@echo off
rem Build and start the DW server, then open http://localhost:8080 in a browser.
setlocal
cd /d "%~dp0.."
call scripts\build.bat
if errorlevel 1 (
  pause
  exit /b 1
)
java -cp "server\build\classes;server\lib\gson-2.13.1.jar" com.zongc.dw.DwServer --config server\config\default.json %*
echo.
echo Server stopped.
pause
