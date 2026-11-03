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
| JDK | 17+ |
| Android Studio | Ladybug 2024.2+ |
| Android SDK | compileSdk 37, minSdk 24 |
| Node.js | 20+ |
| npm | 10+ |


## 3. Hướng dẫn chạy Server

```bash
cd server
npm install
cp .env.example .env
# Sửa .env nếu cần (mặc định API_KEY=coffeestock-dev-key)

# (tùy chọn) Tạo dữ liệu mẫu — 5 movements
node src/scripts/seed.js

# Chạy server
npm start
```

Server chạy tại `http://localhost:8080`.

### 3.1. Kiểm tra nhanh

```bash
# Health check (không cần API key)
curl http://localhost:8080/health
# → {"status":"ok","service":"CoffeeStock API","version":"1.0.0"}

# Lấy danh sách movements (cần API key)
curl -H "X-API-Key: coffeestock-dev-key" \
     http://localhost:8080/api/v1/movements
```

### 3.2. Test server

```bash
cd server
npm test
```
Chạy 15 integration test trong `tests/sync.integration.test.js`.

---

## 4. Hướng dẫn build & chạy Android

### 4.1. Cấu hình lần đầu (bắt buộc)

Copy file template:
```bash
cp local.properties.example local.properties
```
Mở `local.properties`, sửa 2 dòng (giữ nguyên các comment):
```properties
COFFEESTOCK_API_BASE_URL=http://127.0.0.1:8080/
COFFEESTOCK_API_KEY=coffeestock-dev-key
```

> `local.properties` đã nằm trong `.gitignore` → **không commit**, không bị ghi đè khi pull code. Mỗi dev tự set trên máy mình.

### 4.2. Build

```bash
# Debug APK
./gradlew :app:assembleDebug

# Cài lên emulator/thiết bị (tự chạy adb reverse)
./gradlew :app:installDebug
```
APK đầu ra: `app/build/outputs/apk/debug/app-debug.apk`

### 4.3. Chạy

Mở Android Studio → chọn `app` → Run `MainActivity`, hoặc:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.coffeestock/.MainActivity
```

---

## 4.4. Kết nối emulator ↔ server Node.js  

### Vấn đề: firewall máy host chặn port 8080
| Đường đi | Firewall áp dụng? | Kết quả |
|---|---|---|
| App → `10.0.2.2:8080` |  Có — qua virtual NIC của emulator |  BỊ CHẶN |
| App → `127.0.0.1:8080` (qua `adb reverse`) | Không — qua kênh ADB loopback | OK |

**→ Bắt buộc dùng `127.0.0.1` + `adb reverse`.**

### Chạy `adb reverse` — **đã tự động hoá**
`adb reverse` **bị reset mỗi khi emulator restart**. Đã có 3 cách xử lý:

**Cách 1 — Tự động qua Gradle (khuyến nghị)**
Task `adbReverse` tự chạy trước `installDebug` và `connectedDebugAndroidTest`:
```bash
./gradlew :app:installDebug           # → adbReverse tự chạy
./gradlew :app:connectedDebugAndroidTest
```
Chạy tay khi cần:
```bash
./gradlew :app:adbReverse
```

**Cách 2 — Script 1 click (khi không rebuild)**
```bash
./scripts/adb-reverse.sh           # Linux/Mac
scripts\adb-reverse.bat            # Windows
```

**Cách 3 — Kiểm tra thủ công**
```bash
adb reverse --list
# Kỳ vọng: <serial> tcp:8080 tcp:8080
```
Nếu chưa có:
```bash
adb reverse tcp:8080 tcp:8080
```

### Thiết bị thật (không phải emulator)
Sửa `local.properties`:
```properties
COFFEESTOCK_API_BASE_URL=http://192.168.1.10:8080/
```
Trong đó `192.168.1.10` là **IP LAN của máy tính chạy server**. Đảm bảo điện thoại và máy tính **cùng mạng WiFi**.

Mở firewall port 8080 cho phép inbound (Windows):
```powershell
# PowerShell (Run as Administrator)
New-NetFirewallRule -DisplayName "CoffeeStock 8080" `
    -Direction Inbound -Protocol TCP -LocalPort 8080 `
    -Action Allow -Profile Private
```

---

## 5. Chạy test

### 5.1. Unit test (JVM — nhanh, không cần emulator)
```bash
./gradlew :app:testDebugUnitTest
```
Kỳ vọng: 7 test PASS (6 `ConflictResolverTest` + 1 `ExampleUnitTest`).

### 5.2. Instrumented test (cần emulator)
```bash
# Khởi động emulator trước (Android Studio → Device Manager)

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
| `Connection refused` khi sync | Chưa `adb reverse` hoặc server chưa chạy | `./gradlew :app:adbReverse` + `npm start` |
| `Connected refused` trên **thiết bị thật** | Server chỉ bind `127.0.0.1` | Sửa `server/.env`: `HOST=0.0.0.0`, mở firewall |
| `INVALID_API_KEY` | API key không khớp | So `local.properties` với `server/.env` |
| Build lỗi `Unresolved reference: page` | Bug `SyncEngine.pullChanges` (do-while scope) | Cập nhật `SyncEngine.kt` bản đã fix |
| Test fail do DB cũ | Migration 1→2 | Xóa app: `adb shell pm clear com.example.coffeestock` |

---

