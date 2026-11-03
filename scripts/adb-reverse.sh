set -e

echo "→ Chờ emulator boot..."
adb wait-for-device
sleep 2

adb reverse tcp:8080 tcp:8080

echo ""
echo "✅ Đã forward emulator:8080 → host:8080"
echo "Danh sách forward đang active:"
adb reverse --list