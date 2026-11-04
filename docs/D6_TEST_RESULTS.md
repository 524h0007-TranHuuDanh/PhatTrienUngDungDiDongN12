# D6. Kiểm thử và kết quả

## 1. Môi trường kiểm thử

| Thành phần | Môi trường |
|---|---|
| Android unit test | Gradle Wrapper, variant `debug` |
| Android instrumentation test | Android Emulator `Medium_Phone(AVD) - 17`, API 37 |
| Server integration test | Node.js `v24.20.0`, HTTP loopback, SQLite database tạm |
| Ngày chạy | 04/11/2026 |

## 2. Tổng kết

| Nhóm kiểm thử | Tổng số | PASS | FAIL | SKIP |
|---|---:|---:|---:|---:|
| Android unit test | 8 | 8 | 0 | 0 |
| Android instrumentation test | 59 | 59 | 0 | 0 |
| Server integration test | 16 | 16 | 0 | 0 |
| **Tổng cộng** | **83** | **83** | **0** | **0** |

**Kết quả: PASS.** Tất cả 83 test hoàn tất thành công, không có test lỗi hoặc bị bỏ qua.

## 3. Test cases và kết quả

### 3.1. Android unit test — 8/8 PASS

| Test suite | Số lượng | Nội dung kiểm thử | Kết quả |
|---|---:|---|---|
| `ExampleUnitTest` | 1 | Kiểm tra unit test cơ bản | 1 PASS |
| `ConflictResolverTest` | 6 | Quy tắc xử lý xung đột và chọn dữ liệu cần giữ | 6 PASS |
| `SyncSchedulerTest` | 1 | Lên lịch sync duy nhất và chính sách thay thế công việc | 1 PASS |
| **Tổng** | **8** | | **8 PASS, 0 FAIL** |

### 3.2. Android instrumentation test — 59/59 PASS

| Test suite | Số lượng | Nội dung kiểm thử | Kết quả |
|---|---:|---|---|
| `ExampleInstrumentedTest` | 1 | Khởi tạo ngữ cảnh ứng dụng | 1 PASS |
| `MovementRepositoryTest` | 15 | Tạo, sửa, xác nhận, xóa phiếu và kiểm tra dữ liệu hợp lệ | 15 PASS |
| `MultiDeviceConflictEdgeTest` | 4 | Trùng ID, xóa-vs-sửa và xóa đồng thời | 4 PASS |
| `MultiDeviceConflictTest` | 1 | Hai database mô phỏng thiết bị xử lý xung đột phiên bản | 1 PASS |
| `PullDataApplierTest` | 6 | Pull insert/update, cursor, dữ liệu local pending và tombstone | 6 PASS |
| `PushHttpErrorTest` | 8 | Push/pull gặp HTTP 408, 429, 500, 503 và retry | 8 PASS |
| `SyncConsistencyTest` | 4 | Nhất quán dữ liệu, pull tăng dần và xóa mềm | 4 PASS |
| `SyncEngineTest` | 6 | Push/pull, lỗi mạng và phục hồi trạng thái `SYNCING` | 6 PASS |
| `SyncMetricsTest` | 3 | Thời gian sync, tỷ lệ lỗi mô phỏng và idempotency | 3 PASS |
| `SyncScenarioTest` | 7 | Offline, khôi phục, persistence, retry, xóa và đồng bộ lô | 7 PASS |
| `SyncWorkerTest` | 3 | API key, retry và giới hạn số lần thử của worker | 3 PASS |
| `MovementItemTest` | 1 | Hiển thị dữ liệu và trạng thái đồng bộ | 1 PASS |
| **Tổng** | **59** | | **59 PASS, 0 FAIL, 0 SKIP** |

### 3.3. Server integration test — 16/16 PASS

| Test case | Kết quả |
|---|---|
| Health check trả HTTP 200; thiếu API key trả HTTP 401 | PASS |
| Tạo phiếu mới; gửi lại `mutation_id` không tạo bản ghi trùng | PASS |
| Cập nhật với `base_version` hiện tại thành công; version cũ trả HTTP 409 | PASS |
| Pull dữ liệu mới nhất; cursor hiện tại trả danh sách rỗng | PASS |
| Xóa phiếu và nhận tombstone qua pull | PASS |
| Số lượng không hợp lệ trả HTTP 422 | PASS |
| Cursor âm và limit vượt giới hạn trả HTTP 400 | PASS |
| Push danh sách rỗng trả HTTP 422 | PASS |
| Danh sách movements không hiển thị phiếu đã xóa | PASS |
| **Tổng** | **16 PASS, 0 FAIL, 0 SKIP** |

## 4. Kịch bản đồng bộ được kiểm thử tự động

| Kịch bản | Test tiêu biểu | Kết quả |
|---|---|---|
| Tạo/sửa offline; dữ liệu được lưu trong Room và tồn tại sau khi mở lại database | `tc02_offlineCreateAndEdit_persistsInRoom`, `tc04_persistenceAcrossReopen` | PASS |
| Lỗi mạng trả `RETRY`; lần đồng bộ sau thành công | `tc03_networkRecovery_secondSyncSucceeds` | PASS |
| Retry giữ nguyên `mutationId`, không tạo bản ghi trùng | `tc06_retryPreservesMutationId`, `metric3_duplicateRate_idempotency` | PASS |
| Timeout giữ dữ liệu local và trạng thái chờ đồng bộ | `tc05_serverTimeout_preservesLocalChanges` | PASS |
| Xóa phiếu tạo tombstone, không làm dữ liệu bị hồi sinh | `tc08_offlineDelete_notResurrected` | PASS |
| Xung đột giữa hai database mô phỏng thiết bị được xử lý theo version | `tc07_twoDevices_conflictAndResolution`, `tc10`–`tc13` | PASS |
| Movement bị gián đoạn ở trạng thái `SYNCING` được phục hồi và gửi lại | `synchronize_recoversMovementLeftSyncingByInterruptedWorker` | PASS |
| Push/pull gặp lỗi HTTP tạm thời được yêu cầu retry | `PushHttpErrorTest` | PASS |

Các kịch bản offline và nhiều thiết bị dùng Room test database cùng `FakeApiService`; chúng kiểm tra logic đồng bộ tự động, không mô phỏng Wi-Fi hoặc hai thiết bị vật lý. Video demo thao tác UI được thực hiện riêng và không thuộc kết quả test của báo cáo này.

## 5. Minh chứng kết quả

Các báo cáo XML và log của lần chạy được lưu trong thư mục [docs/evidence/D6](./evidence/D6/):

- Android instrumentation: [TEST-Medium_Phone(AVD) - 17.xml](./evidence/D6/TEST-Medium_Phone(AVD)%20-%2017.xml)
- Android unit — `ExampleUnitTest`: [TEST-com.example.coffeestock.ExampleUnitTest.xml](./evidence/D6/TEST-com.example.coffeestock.ExampleUnitTest.xml)
- Android unit — `ConflictResolverTest`: [TEST-com.example.coffeestock.sync.ConflictResolverTest.xml](./evidence/D6/TEST-com.example.coffeestock.sync.ConflictResolverTest.xml)
- Android unit — `SyncSchedulerTest`: [TEST-com.example.coffeestock.sync.SyncSchedulerTest.xml](./evidence/D6/TEST-com.example.coffeestock.sync.SyncSchedulerTest.xml)
- Server integration test: [server-integration-tests.log](./evidence/D6/server-integration-tests.log)

## 6. Lệnh chạy kiểm thử

Chạy Android unit và instrumentation test từ thư mục gốc dự án:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

Chạy server integration test từ thư mục `server/`:

```powershell
npm.cmd test
```
