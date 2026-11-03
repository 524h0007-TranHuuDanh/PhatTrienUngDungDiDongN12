@echo off
chcp 65001 >nul
echo Dang cho emulator khoi dong...
set ADB_PATH="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

%ADB_PATH% wait-for-device
timeout /t 2 /nobreak > nul
%ADB_PATH% reverse tcp:8080 tcp:8080

echo.
echo [OK] Da forward emulator:8080 -^> host:8080
echo Danh sach forward dang active:
%ADB_PATH% reverse --list

pause