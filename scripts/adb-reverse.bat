@echo off
REM ============================================================
REM Tự động forward emulator:8080 -> host:8080.
REM Cần chạy lại MỖI KHI emulator restart.
REM ============================================================

echo → Chờ emulator boot...
adb wait-for-device
timeout /t 2 /nobreak > nul

adb reverse tcp:8080 tcp:8080

echo.
echo Đã forward emulator:8080 -^> host:8080
echo Danh sách forward đang active:
adb reverse --list

pause