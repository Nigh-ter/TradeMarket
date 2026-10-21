@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

title TradeMarket 2.0 Compiler

echo ========================================
echo        TradeMarket 2.0 Compiler
echo ========================================

echo [1/4] Checking Java...
javac -version
if errorlevel 1 goto FAIL

echo.
echo [2/4] Cleaning output...
if exist "out" rmdir /s /q "out"
if exist "sources.txt" del /q "sources.txt"
mkdir "out" 2>nul

echo [3/4] Collecting sources and compiling...
dir /b /s "src\java\*.java" > sources.txt

set "CP="
for %%J in ("libs\*.jar") do (
    if defined CP (set "CP=!CP!;%%~fJ") else (set "CP=%%~fJ")
)

javac -encoding UTF-8 --release 21 -cp "%CP%" -d "out" @sources.txt
if errorlevel 1 (
    echo.
    echo             COMPILE FAILED
    goto FAIL
)

echo [4/4] Packaging resources and jar...
xcopy /e /y /i "src\resources" "out" >nul
if errorlevel 1 goto FAIL

if not exist "dist" mkdir "dist"
jar --create --file "dist\TradeMarket-1.0.0.jar" -C "out" .
if errorlevel 1 goto FAIL

del /q "sources.txt" 2>nul

echo.
echo ========================================
echo   SUCCESS: dist\TradeMarket-1.0.0.jar
echo ========================================
pause
exit /b 0

:FAIL
echo.
echo                FAILED
echo ========================================
pause
exit /b 1
