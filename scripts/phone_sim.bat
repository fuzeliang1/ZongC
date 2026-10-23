@echo off
rem Simulate "walking with a phone" on the PC using the phone app's core code, against a running DW server.
rem Start the server first (scripts\start_server.bat), then:
rem   scripts\phone_sim.bat [--host 127.0.0.1] [--seconds 30] [--id phone-1]
setlocal
cd /d "%~dp0.."
where javac >nul 2>nul
if errorlevel 1 (
  echo [ERROR] javac not found. Please install JDK 17 or newer and add it to PATH.
  exit /b 1
)
if not exist phone\build\phonesim mkdir phone\build\phonesim
javac -encoding UTF-8 -nowarn -d phone\build\phonesim -sourcepath "phone\app\src\main\java;phone\app\src\test\java" phone\app\src\test\java\com\zongc\phone\tools\PhoneSim.java
if errorlevel 1 (
  echo [ERROR] Build failed.
  exit /b 1
)
chcp 65001 >nul
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp phone\build\phonesim com.zongc.phone.tools.PhoneSim %*
