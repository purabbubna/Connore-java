@echo off
cd /d "%~dp0"
call tools\java.bat Build.java
if errorlevel 1 (pause & exit /b 1)
call tools\java.bat -cp "out;lib/*" connore.Main --open-browser
pause
