@echo off
rem Build the DW server. Only needs JDK 17+ (no Maven). Output: server\build\classes
setlocal
cd /d "%~dp0.."
where javac >nul 2>nul
if errorlevel 1 (
  echo [ERROR] javac not found. Please install JDK 17 or newer and add it to PATH.
  exit /b 1
)
if not exist server\build\classes mkdir server\build\classes
javac -encoding UTF-8 --release 17 -nowarn -cp server\lib\gson-2.13.1.jar -sourcepath server\src\main\java -d server\build\classes server\src\main\java\com\zongc\dw\DwServer.java server\src\main\java\com\zongc\dw\tools\Experiment.java server\src\main\java\com\zongc\dw\tools\VisionBench.java
if errorlevel 1 (
  echo [ERROR] Build failed.
  exit /b 1
)
echo Build OK: server\build\classes
