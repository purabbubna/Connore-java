@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "CONNORE_JAVA="
set "CONNORE_JAVA_MAJOR=0"

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "CONNORE_JAVA=%JAVA_HOME%\bin\java.exe"
if not defined CONNORE_JAVA for %%J in (java.exe) do set "CONNORE_JAVA=%%~$PATH:J"
if not defined CONNORE_JAVA call :findInstalledJdk
if not defined CONNORE_JAVA (
  echo JDK 17 or later was not found. Set JAVA_HOME or add its bin folder to PATH. 1>&2
  exit /b 9009
)

"%CONNORE_JAVA%" %*
exit /b %ERRORLEVEL%

:findInstalledJdk
for /d %%J in ("%ProgramFiles%\Java\jdk*") do call :considerJdk "%%~fJ"
for /d %%J in ("%ProgramFiles(x86)%\Java\jdk*") do call :considerJdk "%%~fJ"
exit /b 0

:considerJdk
if not exist "%~1\bin\javac.exe" exit /b 0
if not exist "%~1\bin\java.exe" exit /b 0
set "CONNORE_CANDIDATE=%~nx1"
set "CONNORE_CANDIDATE=%CONNORE_CANDIDATE:jdk=%"
set "CONNORE_CANDIDATE_MAJOR="
for /f "tokens=1 delims=.-+" %%V in ("%CONNORE_CANDIDATE%") do set "CONNORE_CANDIDATE_MAJOR=%%V"
if not defined CONNORE_CANDIDATE_MAJOR exit /b 0
set "CONNORE_INVALID_MAJOR="
for /f "delims=0123456789" %%V in ("%CONNORE_CANDIDATE_MAJOR%") do set "CONNORE_INVALID_MAJOR=1"
if defined CONNORE_INVALID_MAJOR exit /b 0
if %CONNORE_CANDIDATE_MAJOR% LSS 17 exit /b 0
if %CONNORE_CANDIDATE_MAJOR% LSS %CONNORE_JAVA_MAJOR% exit /b 0
set "CONNORE_JAVA_MAJOR=%CONNORE_CANDIDATE_MAJOR%"
set "CONNORE_JAVA=%~1\bin\java.exe"
exit /b 0
