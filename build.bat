@echo off
setlocal

rem Java 21 used by this project
set "JAVA_HOME=C:\Apps\Java\App\java-21-sdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "%~dp0"

echo Using JAVA_HOME=%JAVA_HOME%
java -version

echo.
echo Building AFK Rollback...
echo.

call "%~dp0gradlew.bat" --no-daemon build %*
set "EXITCODE=%ERRORLEVEL%"

echo.
if "%EXITCODE%"=="0" (
    echo Build completed successfully.
    echo JAR files are in: build\libs\
) else (
    echo Build failed with exit code %EXITCODE%.
)

echo.
pause
exit /b %EXITCODE%
