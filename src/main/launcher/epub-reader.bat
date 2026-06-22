@echo off
title EPUB Reader
cd /d "%~dp0"

set JAVA=
if exist "%JAVA_HOME%\bin\java.exe" set JAVA=%JAVA_HOME%\bin\java.exe
if "%JAVA%"=="" for %%e in (java.exe) do set JAVA=%%~$PATH:e
if "%JAVA%"=="" (
    echo Java not found. Please install JDK 11+ or set JAVA_HOME
    pause
    exit /b 1
)

start "" "%JAVA%" -Xmx512m -Dfile.encoding=UTF-8 -jar "%~dp0epub-reader.jar"
