# CoffeeStock — Ứng dụng quản lý kho cà phê (Offline-First)

Ứng dụng Android minh họa kiến trúc **Offline-First** với đồng bộ hai chiều, xử lý xung đột và tự động đồng bộ bằng WorkManager.

Đồ án giữa kỳ — **Đề tài 01: Xây dựng ứng dụng Android hỗ trợ hoạt động ngoại tuyến và đồng bộ dữ liệu tự động**.

---

## 1. Kiến trúc

| Tầng | Công nghệ |
|---|---|
| UI | Jetpack Compose + Material 3 |
| ViewModel | `CoffeeStockViewModel` (StateFlow) |
| Repository | `MovementRepository`, `ProductRepository` |
| Local DB | Room 2.8.5 (4 entity: movement, product, conflict, sync_meta) |
| Network | Retrofit 2.11 + Gson |
| Background | WorkManager 2.12 (unique work + exponential backoff) |
| Server | Node.js 20 + Express 5 + better-sqlite3 (WAL mode) |

**Cơ chế đồng bộ:**
- **Push:** Mỗi movement mang `mutation_id` (UUID) → server idempotent (chống gửi trùng).
- **Pull:** Cursor theo `server_seq` → đồng bộ tăng dần (incremental).
- **Optimistic locking:** `serverVersion` (client) ↔ `version` (server).
- **Conflict:** `VERSION_CONFLICT` → `ConflictResolver` (5 quy tắc ưu tiên).
- **Soft delete:** `isDeleted` (client) ↔ `deleted_at` (server tombstone).

---

## 2. Yêu cầu môi trường

| Công cụ | Phiên bản |
|---|---|
| JDK | 17+ (Android Studio có thể dùng JBR đi kèm) |
| Android Studio | Ladybug 2024.2+ |
| Android SDK/thiết bị | Android SDK Platform 37, Platform-Tools; Android 7.0 (API 24) trở lên |
| Node.js | 20+ |
| npm | 10+ |

Gradle Wrapper (`gradlew`/`gradlew.bat`) sẽ tự tải phiên bản Gradle cần thiết trong lần chạy đầu tiên. Trong Android Studio, mở **SDK Manager** và cài Android SDK Platform 37 nếu máy chưa có.

## 3. Hướng dẫn chạy Server

Mở terminal tại thư mục dự án, sau đó chạy:

**Windows (PowerShell):**
```powershell
cd server
Copy-Item .env.example .env
npm install
```

**Linux/macOS:**
```bash
cd server
cp .env.example .env
npm install
```

Mở `server/.env` và đặt `API_KEY` thành `coffeestock-dev-key` để khớp với cấu hình Android mẫu trong `local.properties.example`. Đây là khóa dùng cho môi trường phát triển; không dùng khóa này khi triển khai thật. Các giá trị khác có thể giữ mặc định (`PORT=8080`, `HOST=0.0.0.0`, `DATABASE_PATH=./data/coffeestock.db`).

Tùy chọn, tạo dữ liệu mẫu (5 movements):
```bash
node src/scripts/seed.js
```

Khởi động server và để terminal này tiếp tục chạy:
```bash
npm start
```

Hoặc dùng chế độ tự khởi động lại khi sửa mã:
```bash
npm run dev
```

Server chạy tại `http://localhost:8080`.

> Chạy các lệnh `npm` trong thư mục `server`. File `server/.env` và cơ sở dữ liệu được loại khỏi Git.

### 3.1. Kiểm tra nhanh

Mở terminal khác để kiểm tra server:

```bash
# Windows PowerShell: dùng curl.exe để tránh alias Invoke-WebRequest
curl.exe http://localhost:8080/health
# → {"status":"ok","service":"CoffeeStock API","version":"1.0.0"}

# Lấy danh sách movements (cần API key)
curl.exe -H "X-API-Key: coffeestock-dev-key" http://localhost:8080/api/v1/movements
```

Linux/macOS có thể dùng `curl` thay cho `curl.exe`.

### 3.2. Test server

```bash
cd server
npm test
```

Lệnh này chạy các integration test trong `tests/sync.integration.test.js`.

---

## 4. Hướng dẫn build & chạy Android

### 4.1. Cấu hình lần đầu

Tại thư mục gốc dự án, tạo `local.properties` từ file mẫu:

**Windows (PowerShell):**
```powershell
Copy-Item local.properties.example local.properties
```

**Linux/macOS:**
```bash
cp local.properties.example local.properties
```

Mở `local.properties` và kiểm tra cấu hình:
```properties
COFFEESTOCK_API_BASE_URL=http://127.0.0.1:8080/
COFFEESTOCK_API_KEY=coffeestock-dev-key
```

Android Studio thường tự ghi `sdk.dir` vào file này khi mở dự án. Nếu build bằng command line mà Gradle không tìm thấy Android SDK, thêm đường dẫn SDK của máy, ví dụ:
```properties
# Windows; thay <YourName> bằng tên người dùng Windows
sdk.dir=C\:\\Users\\<YourName>\\AppData\\Local\\Android\\Sdk
```
Hoặc đặt biến môi trường `ANDROID_HOME` trỏ đến Android SDK.

> `local.properties` nằm trong `.gitignore`: không commit file này. Mỗi máy tự cấu hình URL server, API key và Android SDK.

### 4.2. Build APK

Chạy lệnh từ thư mục gốc dự án:

**Windows:**
```powershell
.\gradlew.bat :app:assembleDebug
```

**Linux/macOS:**
```bash
./gradlew :app:assembleDebug
```

APK debug được tạo tại `app/build/outputs/apk/debug/app-debug.apk`.

### 4.3. Cài đặt và chạy

Đảm bảo server đang chạy theo mục 3 và emulator hoặc thiết bị Android đã kết nối. Kiểm tra thiết bị được nhận diện:
```bash
adb devices
```

Cài ứng dụng:

**Windows:**
```powershell
.\gradlew.bat :app:installDebug
```

**Linux/macOS:**
```bash
./gradlew :app:installDebug
```

Task `installDebug` tự thử chạy `adb reverse` cho cổng 8080. Có thể mở app từ Android Studio (**Run `MainActivity`**) hoặc chạy:
```bash
adb shell am start -n com.example.coffeestock/.MainActivity
```

Nếu cần cài APK đã build thủ công:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.coffeestock/.MainActivity
```

### 4.4. Kết nối emulator ↔ server Node.js

Với cấu hình emulator mặc định, app dùng `127.0.0.1:8080` và `adb reverse` để nối cổng emulator đến máy chạy server:

```bash
adb reverse tcp:8080 tcp:8080
adb reverse --list
```

Gradle tự gọi `adb reverse` trước `installDebug` và `connectedDebugAndroidTest`. Nếu emulator vừa khởi động lại hoặc app không kết nối được, kiểm tra lại kết nối bằng các lệnh trên. Có thể chạy task riêng:
```bash
# Windows
.\gradlew.bat :app:adbReverse

# Linux/macOS
./gradlew :app:adbReverse
```

Hoặc dùng script trong repository:
```bash
# Linux/macOS
./scripts/adb-reverse.sh
```
```powershell
# Windows
.\scripts\adb-reverse.bat
```

### Thiết bị thật (không phải emulator)

Sửa `COFFEESTOCK_API_BASE_URL` trong `local.properties` thành địa chỉ LAN của máy chạy server, ví dụ:
```properties
COFFEESTOCK_API_BASE_URL=http://192.168.1.10:8080/
```

Thay `192.168.1.10` bằng IP LAN thực tế của máy tính. Đảm bảo điện thoại và máy tính cùng mạng Wi-Fi, và firewall cho phép kết nối đến cổng 8080. Server mặc định bind `0.0.0.0`; có thể kiểm tra `HOST` trong `server/.env`.

Trên Windows, nếu firewall chặn kết nối, mở PowerShell với quyền Administrator:
```powershell
New-NetFirewallRule -DisplayName "CoffeeStock 8080" `
    -Direction Inbound -Protocol TCP -LocalPort 8080 `
    -Action Allow -Profile Private
```

---

## 5. Chạy test

### 5.1. Unit test (JVM — nhanh, không cần emulator)
**Windows:**
```powershell
.\gradlew.bat :app:testDebugUnitTest
```

**Linux/macOS:**
```bash
./gradlew :app:testDebugUnitTest
```
Kỳ vọng: 7 test PASS (6 `ConflictResolverTest` + 1 `ExampleUnitTest`).

### 5.2. Instrumented test (cần emulator)
Khởi động emulator trước (Android Studio → Device Manager). Các lệnh dưới đây chạy từ thư mục gốc dự án.

**Windows (PowerShell):**
```powershell
# Chạy tất cả
.\gradlew.bat :app:connectedDebugAndroidTest

# Chỉ TC02–TC09
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.coffeestock.sync.SyncScenarioTest"

# Chỉ metrics D7
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.coffeestock.sync.SyncMetricsTest"

# Chỉ TC07 multi-device
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.coffeestock.sync.MultiDeviceConflictTest"
```

**Linux/macOS:**
```bash
# Chạy tất cả
./gradlew :app:connectedDebugAndroidTest

# Chỉ TC02–TC09
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.coffeestock.sync.SyncScenarioTest

# Chỉ metrics D7
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.coffeestock.sync.SyncMetricsTest

# Chỉ TC07 multi-device
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.coffeestock.sync.MultiDeviceConflictTest
```
Kết quả HTML: `app/build/reports/androidTests/connected/index.html`

### 5.3. Test server (Node.js)
```bash
cd server
npm test
```

## 6. Xử lý lỗi thường gặp

| Lỗi | Nguyên nhân | Cách sửa |
|---|---|---|
| `Connection refused` khi sync trên emulator | Server chưa chạy hoặc `adb reverse` chưa hoạt động | Chạy `npm start` trong `server`, sau đó `adb reverse tcp:8080 tcp:8080` |
| `Connection refused` trên **thiết bị thật** | Sai IP, thiết bị không cùng mạng hoặc firewall chặn kết nối | Kiểm tra IP LAN, Wi-Fi, `HOST=0.0.0.0` trong `server/.env` và firewall |
| `INVALID_API_KEY` | API key không khớp | So `local.properties` với `server/.env` |
| `SDK location not found` | Android SDK chưa được cấu hình | Mở dự án bằng Android Studio hoặc đặt `sdk.dir`/`ANDROID_HOME` như mục 4.1 |
| Test fail do DB cũ | Migration 1→2 | Xóa app: `adb shell pm clear com.example.coffeestock` |

---
