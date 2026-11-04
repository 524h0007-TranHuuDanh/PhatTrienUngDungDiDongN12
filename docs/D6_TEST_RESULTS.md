# D6. Bộ kiểm thử và kết quả kiểm thử

Tài liệu tổng hợp test cases cho đồng bộ online/offline, phục hồi mạng, lỗi API, retry, đồng bộ lặp và xung đột dữ liệu. Kết quả bên dưới là từ lần chạy kiểm thử ngày **04/11/2026** trong môi trường phát triển hiện tại.

## 1. Môi trường và lệnh chạy

| Thành phần | Môi trường |
|---|---|
| Android JVM tests | Gradle Wrapper, variant `debug` |
| Android instrumentation tests | Emulator `Medium_Phone(AVD) - 17` |
| Server integration tests | Node.js `v24.20.0`, npm scripts, database tạm do test tạo |
| Repository | Nhánh làm việc hiện tại; test server sử dụng API key giả lập và database tạm |

Các lệnh đã chạy thành công:

**Windows PowerShell, từ thư mục gốc dự án:**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

**Windows PowerShell, trong thư mục `server/`:**

```powershell
npm.cmd ci
npm.cmd test
```

`npm.cmd ci` cài đúng dependencies theo `server/package-lock.json`. `npm test` chạy `node --test tests/*.test.js`.

## 2. Tổng kết kết quả thực tế

| Bộ kiểm thử | Kết quả | Pass | Fail | Skip | Báo cáo |
|---|---:|---:|---:|---:|---|
| Android JVM unit tests | **PASS** | 8 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| Android instrumentation tests | **PASS** | 59 | 0 | 0 | `app/build/reports/androidTests/connected/debug/index.html` |
| Server integration tests | **PASS** | 16 | 0 | 0 | [`server-integration-tests.log`](./evidence/D6/server-integration-tests.log) |
| **Tổng cộng** | **PASS** | **83** | **0** | **0** | Các báo cáo ở trên |

Android report được lấy từ XML kết quả Gradle sau khi chạy test. Server test kết thúc với `ℹ tests 16`, `ℹ pass 16`, `ℹ fail 0`. Server có in log lỗi validation trong các ca cố ý gửi dữ liệu sai (ví dụ số lượng 0 và push rỗng); các response HTTP mong đợi đã được assert và test vẫn **PASS**.

Các XML kết quả của lần chạy này đã được lưu trong [docs/evidence/D6/](./evidence/D6/):

- `TEST-com.example.coffeestock.ExampleUnitTest.xml`
- `TEST-com.example.coffeestock.sync.ConflictResolverTest.xml`
- `TEST-com.example.coffeestock.sync.SyncSchedulerTest.xml`
- `TEST-Medium_Phone(AVD) - 17.xml`
- `server-integration-tests.log`

Android report HTML chi tiết cũng được tạo tại `app/build/reports/androidTests/connected/debug/index.html`; đây là đường dẫn trong thư mục build và có thể bị xóa khi clean/build lại. XML đã sao chép là minh chứng kết quả Android có thể lưu cùng hồ sơ.

## 3. Test cases đã chạy

### 3.1. Android unit tests — 8/8 PASS

| Test suite | Số test | Kết quả |
|---|---:|---|
| `ExampleUnitTest` | 1 | PASS |
| `ConflictResolverTest` | 6 | PASS |
| `SyncSchedulerTest` | 1 | PASS |

`ConflictResolverTest` xác nhận các quy tắc giữ dữ liệu server, retry sửa local và retry xóa local. `SyncSchedulerTest` xác nhận yêu cầu lặp dùng cùng unique work name `coffeestock_sync` và policy `APPEND_OR_REPLACE`.

### 3.2. Android instrumentation — 59/59 PASS

| Test suite | Số test | Phạm vi |
|---|---:|---|
| `ExampleInstrumentedTest` | 1 | Khởi tạo ngữ cảnh ứng dụng |
| `MovementRepositoryTest` | 15 | Validation, tạo/sửa/xác nhận/xóa phiếu; xóa mềm và xóa cứng |
| `MultiDeviceConflictEdgeTest` | 4 | Trùng ID, xóa-vs-sửa và xóa đồng thời |
| `MultiDeviceConflictTest` | 1 | Hai thiết bị sửa cùng phiếu, phát hiện version cũ và giải quyết conflict |
| `PullDataApplierTest` | 6 | Insert/update, không ghi đè pending local, cursor và tombstone |
| `PushHttpErrorTest` | 8 | HTTP 408/429/500/503, retry và phục hồi sau HTTP 500 |
| `SyncConsistencyTest` | 4 | Nhất quán Room/server, pull tăng dần, cập nhật và tombstone |
| `SyncEngineTest` | 6 | Push/pull, lỗi mạng, phục hồi worker kẹt `SYNCING` và reset về `PENDING` |
| `SyncMetricsTest` | 3 | Chỉ số thời gian, success rate và duplicate rate |
| `SyncScenarioTest` | 7 | Offline local, phục hồi mạng, timeout, persistence, mutation ID, delete và nhiều bản ghi |
| `SyncWorkerTest` | 3 | Thiếu API key, retry trước giới hạn và failure ở lần thử tối đa |
| `MovementItemTest` | 1 | Hiển thị dữ liệu và trạng thái đồng bộ |
| **Tổng** | **59** | **59 PASS, 0 FAIL, 0 SKIP** |

Các ca đặc biệt cho yêu cầu D6:

| Mã | Test case | Kết quả |
|---|---|---|
| TC02 | Tạo/sửa khi offline, dữ liệu vẫn nằm trong Room/outbox | PASS |
| TC03 | Lỗi mạng ở lần đầu, bật lại mạng giả lập và sync lần hai thành công | PASS |
| TC05 | Timeout server, giữ nguyên dữ liệu local và trả retry | PASS |
| TC06 | Retry giữ cùng `mutationId`, không tạo bản ghi trùng | PASS |
| TC08 | Xóa offline tạo tombstone, không bị pull làm sống lại | PASS |
| HTTP retry | Push/pull với HTTP 408, 429, 500, 503 | PASS |
| Worker recovery | Movement bị bỏ ở `SYNCING` được đưa về `PENDING` và gửi lại | PASS |
| TC07, TC10–TC13 | Xung đột nhiều thiết bị: stale update, trùng ID, delete-vs-edit, delete đồng thời | PASS |
| Server idempotency | Gửi lại cùng `mutation_id` không tăng version/sequence hay tạo bản ghi mới | PASS |

**Giới hạn cần nêu rõ:** các test Android online/offline và lỗi mạng dùng `FakeApiService`/database test để mô phỏng phản hồi mạng; chúng không bật/tắt Wi-Fi hoặc kiểm thử server triển khai thật. Integration test server chạy HTTP thật trên loopback trong tiến trình test và dùng SQLite database tạm. Vì vậy, kết quả hiện có xác nhận logic ứng dụng/API trong môi trường test, không phải phép đo chất lượng mạng thực tế.

### 3.3. Server integration tests — 16/16 PASS

| Test case | Kết quả |
|---|---|
| Health check trả HTTP 200 | PASS |
| Thiếu API key trả HTTP 401 | PASS |
| Tạo phiếu mới | PASS |
| Gửi lại `mutation_id` không tạo bản ghi mới | PASS |
| Cập nhật với `base_version` đúng | PASS |
| `base_version` cũ trả HTTP 409 | PASS |
| Pull trả dữ liệu mới nhất | PASS |
| Pull với cursor mới nhất trả danh sách rỗng | PASS |
| Xóa phiếu bằng version hiện tại | PASS |
| Pull nhận tombstone | PASS |
| Số lượng bằng 0 trả HTTP 422 | PASS |
| Cursor âm trả HTTP 400 | PASS |
| Limit pull lớn hơn 500 trả HTTP 400 | PASS |
| Push danh sách rỗng trả HTTP 422 | PASS |
| Danh sách không hiển thị phiếu đã xóa | PASS |
| Tổng kết suite `CoffeeStock Sync API integration` | **PASS — 16/16, 0 lỗi** |

## 4. Phân tích kết quả

1. **Kết quả tự động nhất quán:** cả ba bộ kiểm thử đều hoàn tất với 0 lỗi: Android unit 8/8, Android instrumentation 59/59 và server integration 16/16. Tổng cộng 83/83 test pass trong lần chạy được ghi nhận.
2. **Các rủi ro đồng bộ chính đã có test:** dữ liệu local khi offline, phục hồi sau lỗi mạng/HTTP, giữ `mutationId` khi retry, tombstone, pull cursor, worker bị dừng khi đang `SYNCING`, và xung đột giữa hai thiết bị. Các test có assertion trạng thái dữ liệu, version/cursor hoặc số bản ghi, không chỉ dựa vào log.
3. **Server/API được kiểm tra độc lập:** integration tests thực hiện request HTTP tới Express và xác nhận các status code, response body, idempotency, version conflict và xóa mềm. Chúng dùng SQLite tạm nên không sửa database phát triển.
4. **Không có test fail/skip trong lần chạy này:** không cần phân tích lỗi test hiện có. Server vẫn in stack trace cho một số request dữ liệu sai vì middleware log lỗi; các mã HTTP 422 đó là kết quả mong đợi và test tương ứng pass.
5. **Giới hạn của phạm vi xác minh:** Android network failure/offline dùng fake API và Room test database; chúng không chứng minh trạng thái Wi-Fi thực tế, chất lượng mạng hoặc thời gian phục hồi trên hạ tầng triển khai. Kịch bản thủ công M1–M6 chưa chạy, nên hiện chưa có ảnh chụp UI hoặc log từ hai thiết bị thật. Kết quả cũng chỉ phản ánh cấu hình debug/emulator và môi trường ngày chạy, không thay thế kiểm thử tải hay kiểm thử production.

## 5. Kết luận

Trong phạm vi kiểm thử tự động đã chạy, các luồng cốt lõi của ứng dụng và server — lưu thay đổi offline, push/pull, retry/phục hồi, tránh áp dụng mutation lặp, xóa mềm và xử lý xung đột — đều đạt yêu cầu của test suite: **83 PASS, 0 FAIL, 0 SKIP**. Kết quả phù hợp để làm minh chứng kiểm thử logic và API cho D6.

Trước khi nộp kết luận về hoạt động thực tế online/offline, cần chạy các kịch bản M1–M6 trên emulator/thiết bị với server đang chạy, cập nhật PASS/FAIL và bổ sung ảnh/log tương ứng. Không nên diễn giải các test fake network hiện có thành bằng chứng đã kiểm chứng mạng thật.

## 6. Test cases kiểm thử thủ công trên thiết bị thật

Các kịch bản sau cần dùng server và app đang chạy trên emulator/điện thoại, thay vì fake service. Chưa thực hiện trong lượt kiểm thử này; điền trạng thái, thời gian và ảnh/log sau khi chạy.

| Mã | Kịch bản | Kết quả | Minh chứng |
|---|---|---|---|
| M1 | Tạo phiếu khi online, kiểm tra phiếu xuất hiện trên server và thiết bị thứ hai | [Chưa chạy — điền PASS/FAIL] | [Chèn ảnh/log] |
| M2 | Ngắt mạng, tạo và sửa phiếu; khởi động lại app, kiểm tra dữ liệu local còn | [Chưa chạy — điền PASS/FAIL] | [Chèn ảnh/log] |
| M3 | Bật mạng lại, chạy đồng bộ; xác nhận phiếu offline lên server đúng một lần | [Chưa chạy — điền PASS/FAIL] | [Chèn ảnh/log] |
| M4 | Mô phỏng hai thiết bị sửa cùng phiếu rồi đồng bộ lần lượt; kiểm tra conflict/version | [Chưa chạy — điền PASS/FAIL] | [Chèn ảnh/log] |
| M5 | Xóa phiếu đã đồng bộ khi offline; bật mạng lại và xác nhận tombstone tới thiết bị còn lại | [Chưa chạy — điền PASS/FAIL] | [Chèn ảnh/log] |
| M6 | Dừng/kết thúc app khi một phiếu đang `SYNCING`, mở lại và xác nhận phiếu được phục hồi/đồng bộ | [Chưa chạy — điền PASS/FAIL] | [Chèn ảnh/log] |

(**Hướng dẫn chạy M1–M6 — xóa phần hướng dẫn trong ngoặc khi hoàn tất báo cáo:** khởi động server theo [README.md](../README.md), bảo đảm `server/.env` và `local.properties` dùng cùng API key; chạy app trên emulator hoặc hai thiết bị. Dùng Android Settings/Quick Settings để bật/tắt Wi-Fi hoặc chế độ máy bay. Với emulator, kiểm tra `adb reverse tcp:8080 tcp:8080`; sau mỗi kịch bản ghi lại thao tác, trạng thái app/server và kết quả mong đợi/thực tế. Không dùng lệnh xóa app hoặc xóa database trong lúc kiểm tra tính persistence.)

## 7. Hướng dẫn lưu log và ảnh minh chứng

(**Xóa các hướng dẫn trong ngoặc sau khi tạo đủ minh chứng nộp bài.**)

1. Chạy lại bộ cần lấy bằng các lệnh ở mục 1; lưu nguyên phần terminal output, gồm tên lệnh, ngày giờ, thiết bị và dòng tổng kết pass/fail.
2. Với Android instrumentation, mở `app/build/reports/androidTests/connected/debug/index.html`; chụp trang tổng quan có số test/fail và trang chi tiết các suite sync. Các XML nằm trong `app/build/outputs/androidTest-results/connected/debug/`.
3. Với unit test, lưu các XML trong `app/build/test-results/testDebugUnitTest/`.
4. Với backend, chạy `npm.cmd test` trong `server/`; log của lần chạy đã lưu tại `docs/evidence/D6/server-integration-tests.log`. Sau lần chạy mới, cập nhật lại log minh chứng để khớp kết quả mới nhất.
5. Với M1–M6, chụp màn hình trạng thái ứng dụng và thiết bị; che API key, địa chỉ nội bộ hoặc dữ liệu cá nhân nếu có.
6. Đặt file minh chứng vào `docs/evidence/D6/` và điền đường dẫn vào cột **Minh chứng** của mục 4. Không ghi API key thật vào tài liệu/log đã nộp.

## 8. Cách chạy riêng từng nhóm

**Android unit tests:**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

**Android instrumentation — emulator phải đang chạy:**

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

**Chỉ chạy test kịch bản đồng bộ:**

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.coffeestock.sync.SyncScenarioTest"
```

**Server integration tests, chạy trong `server/`:**

```powershell
npm.cmd test
```

**Server test trên Linux/macOS, chạy trong `server/`:**

```bash
npm test
```

## 9. Tài liệu và mã nguồn liên quan

- Mô tả đồng bộ và chính sách xung đột: [D5. Mô-đun đồng bộ và xử lý xung đột](./D5_SYNC_CONFLICTS.md)
- API, schema, seed và triển khai server: [D4. Mã nguồn máy chủ và API](./D4_SERVER_API.md)
- Hướng dẫn build/chạy dự án: [README.md](../README.md)
