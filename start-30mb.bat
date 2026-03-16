@echo off
REM ============================================
REM Rust-Java REST Framework - 30MB Memory Mode
REM ============================================
REM Optimized for minimal memory footprint

setlocal

echo Starting Rust-Java REST Framework (30MB Mode)...
echo.

REM Get the directory of this script
set SCRIPT_DIR=%~dp0
set APP_DIR=%SCRIPT_DIR%\..

REM JVM Options for 30MB target
set JVM_OPTS=-Xms16m
set JVM_OPTS=%JVM_OPTS% -Xmx32m
set JVM_OPTS=%JVM_OPTS% -XX:MaxMetaspaceSize=16m
set JVM_OPTS=%JVM_OPTS% -XX:ReservedCodeCacheSize=16m
set JVM_OPTS=%JVM_OPTS% -XX:+UseSerialGC
set JVM_OPTS=%JVM_OPTS% -XX:CICompilerCount=1
set JVM_OPTS=%JVM_OPTS% -XX:ThreadStackSize=256k
set JVM_OPTS=%JVM_OPTS% -Xss256k
set JVM_OPTS=%JVM_OPTS% -XX:+UseCompressedOops
set JVM_OPTS=%JVM_OPTS% -XX:+UseCompressedClassPointers
set JVM_OPTS=%JVM_OPTS% -XX:+UseStringDeduplication
set JVM_OPTS=%JVM_OPTS% -XX:+UnlockExperimentalVMOptions
set JVM_OPTS=%JVM_OPTS% -XX:+UseCompactObjectHeaders
set JVM_OPTS=%JVM_OPTS% -Djava.library.path=%APP_DIR%\src\main\resources\native\windows-x64
set JVM_OPTS=%JVM_OPTS% -Dbuffer.size=4096
set JVM_OPTS=%JVM_OPTS% -Dbuffer.pool.size=20
set JVM_OPTS=%JVM_OPTS% -Dworker.threads=4

REM Application JAR
set APP_JAR=%APP_DIR%\target\rust-java-rest-2.0.0.jar

if not exist "%APP_JAR%" (
    echo ERROR: JAR not found: %APP_JAR%
    echo Please run: mvn clean package -DskipTests
    exit /b 1
)

echo JVM Options: %JVM_OPTS%
echo JAR: %APP_JAR%
echo.

REM Start the application
java %JVM_OPTS% -jar "%APP_JAR%"
