# D7. Báo cáo thực nghiệm và đánh giá

## 1. Mục tiêu

Báo cáo đánh giá thời gian đồng bộ theo kích thước dữ liệu, tỷ lệ thành công khi có lỗi mạng giả lập, tính idempotent khi gửi lại mutation, tính nhất quán dữ liệu và các giới hạn của phép thử. Kết quả được chia rõ giữa số đo thực nghiệm đã chạy và các phép đo triển khai thật chưa thực hiện.

## 2. Môi trường và phương pháp

| Hạng mục | Môi trường/phương pháp |
|---|---|
| Thiết bị chạy Android test | Android Emulator `Medium_Phone(AVD) - 17`, Google `sdk_gphone16k_x86_64`, API 37 |
| Ứng dụng | Variant `debug`; Android instrumentation test |
| Lưu trữ trong phép đo Android | Room in-memory database |
| API trong phép đo Android | `FakeApiService`; không có độ trễ mạng/server thật |
| Dữ liệu | Movement tổng hợp; ID `m1...mN`, cùng `product-1`, số lượng thay đổi theo bản ghi |
| Lỗi mạng | Giả lập lỗi `IOException` có tính xác định cho 30% lần gọi trong bộ 20 lượt |
| Server integration riêng | Node.js `v24.20.0`, HTTP loopback và SQLite tạm do test tạo |
| Thời điểm chạy nhóm đo | 04/11/2026 |

Nhóm đo Android được chạy riêng bằng lệnh:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.coffeestock.sync.SyncMetricsTest"
```

Ba test đo đều **PASS** (3 pass, 0 fail, 0 skip). XML và logcat đầy đủ đã lưu trong [evidence D7](./evidence/D7/).

## 3. Kết quả đo được

### 3.1. Thời gian đồng bộ theo số bản ghi

Đo thời gian gọi `SyncEngine.synchronize()` gồm push, pull và xử lý Room trên emulator; mỗi kích thước được đo một lần bằng fake API.

| Số bản ghi (N) | Thời gian (ms) | Thời gian/bản ghi (ms) | Kết quả |
|---:|---:|---:|---|
| 1 | 52 | 52.00 | Thành công, đủ 1 bản ghi phía API giả lập |
| 5 | 105 | 21.00 | Thành công, đủ 5 bản ghi |
| 10 | 188 | 18.80 | Thành công, đủ 10 bản ghi |
| 25 | 446 | 17.84 | Thành công, đủ 25 bản ghi |
| 50 | 903 | 18.06 | Thành công, đủ 50 bản ghi |

```mermaid
xychart-beta
    title "Thời gian synchronize theo số bản ghi (FakeApiService)"
    x-axis "Số bản ghi" [1, 5, 10, 25, 50]
    y-axis "Thời gian (ms)" 0 --> 1000
    bar [52, 105, 188, 446, 903]
```

Số đo cho thấy tổng thời gian tăng theo kích thước dữ liệu trong cấu hình thử nghiệm. Mức thời gian/bản ghi giảm từ N=1 đến các lô lớn hơn và gần 18 ms/bản ghi ở N=25–50. Đây chỉ là thời gian xử lý trong emulator với API giả lập, **không phải** thời gian round-trip qua mạng hoặc hiệu năng server.

### 3.2. Tỷ lệ thành công khi giả lập lỗi mạng

Thử 20 lượt đồng bộ độc lập, mỗi lượt có một movement. Mẫu lỗi lặp xác định tạo 6 lượt trả `RETRY` và 14 lượt `SUCCESS`.

| Kết quả lượt đầu | Số lượt | Tỷ lệ |
|---|---:|---:|
| `SUCCESS` | 14/20 | 70% |
| `RETRY` do lỗi mạng giả lập | 6/20 | 30% |
| `FAILURE` | 0/20 | 0% |

Phép tính tỷ lệ thành công lượt đầu: `14 / 20 × 100 = 70%`. Sáu lượt `RETRY` không được chạy lại trong phép đo này; vì vậy **70% không phải tỷ lệ phục hồi cuối cùng sau retry**, cũng không đại diện cho tỷ lệ thành công trên mạng thật. Test xác nhận hệ thống nhận diện lỗi tạm thời và yêu cầu retry, không đo độ tin cậy của một lần đồng bộ nhiều lần thử.

### 3.3. Gửi lặp và tính nhất quán

| Kiểm tra | Thao tác | Kết quả đo/kiểm chứng |
|---|---|---|
| Idempotency | Push mutation `m1` một lần, sau đó yêu cầu đồng bộ lại 5 lần với cùng `mutationId` | Server giả lập giữ 1 record; local giữ 1 record |
| Bản ghi trùng | So sánh số record sau các lần gửi lặp với số record kỳ vọng | 0 record trùng |
| Đồng bộ lô | Đồng bộ 20 movement trong `SyncScenarioTest` | API giả lập nhận đủ 20; từng movement được assertion ở trạng thái `SYNCED` |
| Luồng API server | 16 integration tests trên HTTP loopback và SQLite tạm | 16 pass, 0 fail; có kiểm tra version conflict, cursor, tombstone và idempotency |

Với phép đo idempotency, tỷ lệ trùng được tính là `(tổng record - số record duy nhất) / số record duy nhất × 100`; kết quả `(1 - 1) / 1 × 100 = 0%`. Các test consistency còn kiểm tra dữ liệu pending local không bị pull ghi đè và tombstone không làm phiếu đã xóa sống lại; xem chi tiết tại [D6. Bộ kiểm thử và kết quả kiểm thử](./D6_TEST_RESULTS.md).

## 4. Tổng hợp kết quả và phân tích

| Chỉ tiêu | Kết quả hiện có | Diễn giải |
|---|---|---|
| Thời gian đồng bộ cục bộ | 52–903 ms với N=1–50 | Đo `SyncEngine` + Room in-memory + FakeApiService; không phải thời gian mạng thật |
| Thành công lượt đầu dưới lỗi giả lập | 14/20 = 70% | 6 lượt được yêu cầu `RETRY`; chưa đo kết quả sau khi chạy lại |
| Tỷ lệ test tự động pass trong lần chạy D6 | 83/83 = 100% | Đây là tỷ lệ pass của test suite, không phải tỷ lệ thành công đồng bộ production |
| Mutation trùng trong phép thử idempotency | 0/1 = 0% | Năm lần gọi đồng bộ lại không tạo thêm record trong API giả lập |
| Nhất quán trong test dữ liệu | Các assertion về trạng thái, số record, version/cursor và tombstone đều pass | Chỉ xác nhận các kịch bản test đã viết |
| Đồng bộ qua hạ tầng thật | Chưa đo | Cần chạy server/app trên cùng mạng hoặc hai thiết bị và ghi thời gian thực tế |

Kết quả hỗ trợ nhận định rằng các luồng cốt lõi đã có kiểm thử tự động: ứng dụng giữ thay đổi local, xử lý lỗi tạm thời bằng retry, giữ cùng mutation ID khi retry và không tạo bản ghi trùng trong kịch bản idempotency. Tính nhất quán được xác minh bằng assertion dữ liệu ở Room và API giả lập, cộng với integration tests server.

## 5. Hạn chế và phép đo cần bổ sung

- Thời gian ở mục 3.1 là một lần đo cho mỗi N, trên emulator và fake API; chưa có nhiều lần lặp để tính trung bình/độ lệch chuẩn.
- Fake API loại bỏ độ trễ, jitter, mất gói, DNS, TLS và tải thực của server; không dùng các số này để dự đoán trải nghiệm người dùng trên Internet.
- Tỷ lệ 70% chỉ đo kết quả lượt đầu theo mẫu lỗi cưỡng bức; chưa đo retry đến khi phục hồi hoặc hết giới hạn lần thử.
- Chưa có phép đo throughput/end-to-end trên backend triển khai, thiết bị vật lý hoặc hai thiết bị đồng bộ đồng thời.
- Server integration tests dùng HTTP loopback và SQLite tạm; chúng không đánh giá tải cao, độ bền dữ liệu production hoặc nhiều client đồng thời dưới tải.
- Kết quả nhất quán có phạm vi theo test case hiện có, không chứng minh mọi thứ tự thao tác hoặc mọi tình huống cạnh tranh đều đúng.

(**Hướng dẫn chạy phép đo thực tế — xóa toàn bộ phần hướng dẫn trong ngoặc này sau khi đã điền bảng và lưu minh chứng:**

1. **Khởi động và cấu hình:** chạy server theo hướng dẫn trong [D4](./D4_SERVER_API.md); cấu hình `local.properties` dùng cùng API key. Với emulator, kiểm tra `adb reverse tcp:8080 tcp:8080`; với điện thoại thật, dùng địa chỉ LAN của máy server và bảo đảm firewall cho phép kết nối. Nếu thử đồng bộ hai thiết bị, cài app trên cả hai và trỏ cùng một server/database.
2. **Đo thời gian end-to-end:** chuẩn bị số phiếu cần đo (N=1, 10, 50, 100) ở trạng thái chờ đồng bộ. Với từng N, thực hiện 3 lượt độc lập: bấm **Đồng bộ ngay**, bấm giờ từ lúc bắt đầu đến khi UI báo đồng bộ thành công và server xác nhận đủ N phiếu; ghi thời gian từng lượt, N, thiết bị, mạng, kết quả và lỗi. Tính trung bình ba thời gian bằng `(lượt 1 + lượt 2 + lượt 3) / 3`. Mỗi lượt cần bộ ID phiếu riêng hoặc dọn dữ liệu thử an toàn trước lượt sau để không đếm nhầm dữ liệu cũ.
3. **Đo thành công/phục hồi offline:** tắt Wi-Fi/mạng, tạo một phiếu mới, xác nhận phiếu còn trong app và ở trạng thái chờ; bật mạng lại, ghi thời gian từ lúc mạng hoạt động đến lúc phiếu báo đã đồng bộ. Chạy 10 lượt độc lập; ghi số lượt đồng bộ thành công, số lượt retry/thất bại và thời gian phục hồi. Không xóa dữ liệu ứng dụng khi đang kiểm tra persistence.
4. **Kiểm tra nhất quán hai thiết bị:** tạo/đồng bộ phiếu trên thiết bị A, xác nhận phiếu xuất hiện trên B; thử cập nhật hoặc xóa theo kịch bản, đồng bộ cả hai và đối chiếu ID, version, trạng thái xóa/conflict. Ghi rõ dữ liệu mong đợi, dữ liệu thực tế và mọi bản ghi thiếu/trùng.
5. **Lưu minh chứng:** chụp UI trước/sau đồng bộ và lưu log thời gian/kết quả trong `docs/evidence/D7/`. Che API key, địa chỉ riêng tư và dữ liệu cá nhân. Điền các bảng ngay dưới đây; không ghi một số trung bình nếu chưa lưu các lần đo thành phần.

**Những phần bạn cần tự chạy:** đo end-to-end với backend thật (không dùng FakeApiService), kiểm tra phục hồi sau ngắt/kết nối mạng thật, và đối chiếu dữ liệu giữa hai thiết bị. Các số đo giả lập ở mục 3 vẫn giữ nguyên để làm kết quả test tự động, không thay thế các phép đo này.**)

| Số phiếu (N) | Lượt 1 (ms) | Lượt 2 (ms) | Lượt 3 (ms) | Trung bình (ms) | Thành công | Thiết bị/mạng | Minh chứng |
|---:|---:|---:|---:|---:|---:|---|---|
| 1 | [Chưa đo] | [Chưa đo] | [Chưa đo] | [Chưa đo] | [Chưa đo] | [Điền] | [Ảnh/log] |
| 10 | [Chưa đo] | [Chưa đo] | [Chưa đo] | [Chưa đo] | [Chưa đo] | [Điền] | [Ảnh/log] |
| 50 | [Chưa đo] | [Chưa đo] | [Chưa đo] | [Chưa đo] | [Chưa đo] | [Điền] | [Ảnh/log] |
| 100 | [Chưa đo] | [Chưa đo] | [Chưa đo] | [Chưa đo] | [Chưa đo] | [Điền] | [Ảnh/log] |

| Kịch bản phục hồi offline | Số lượt | Đồng bộ thành công | Retry/thất bại | Thời gian phục hồi trung bình | Kết quả nhất quán hai thiết bị | Minh chứng |
|---|---:|---:|---:|---:|---|---|
| Tạo phiếu offline, bật mạng lại | 10 | [Chưa đo] | [Chưa đo] | [Chưa đo] | [Chưa kiểm tra] | [Ảnh/log] |

## 6. Kết luận

Trong môi trường kiểm thử hiện tại, `SyncEngine` xử lý được lô tổng hợp đến 50 movement với 903 ms trên emulator + FakeApiService; khi 30% request bị ép lỗi, 14/20 lượt hoàn tất thành công ngay và 6/20 yêu cầu retry. Thử gửi lại cùng mutation ID năm lần cho kết quả 1 record, 0 record trùng. Các kiểm tra consistency liên quan đều pass trong các test suite đã chạy.

Các kết quả này là minh chứng cho logic đồng bộ trong môi trường kiểm thử, **chưa phải đánh giá hiệu năng hoặc độ tin cậy trên hạ tầng triển khai thật**. Cần bổ sung phép đo end-to-end, lặp nhiều lần, kiểm thử phục hồi mạng thật và ghi nhận hai thiết bị hội tụ trước khi đưa ra kết luận định lượng về môi trường sử dụng thực tế.

## 7. Minh chứng

- [XML kết quả ba test đo](./evidence/D7/TEST-Medium_Phone-AVD-17.xml)
- [Logcat đo thời gian theo số bản ghi](./evidence/D7/logcat-com.example.coffeestock.sync.SyncMetricsTest-metric1_syncTimeByDataSize.txt)
- [Logcat tỷ lệ thành công khi giả lập lỗi](./evidence/D7/logcat-com.example.coffeestock.sync.SyncMetricsTest-metric2_successRate_30PercentFailure.txt)
- [Logcat thử idempotency/gửi lặp](./evidence/D7/logcat-com.example.coffeestock.sync.SyncMetricsTest-metric3_duplicateRate_idempotency.txt)
- [Báo cáo test cases và kết quả D6](./D6_TEST_RESULTS.md)
- [Mã nguồn các phép đo](../app/src/androidTest/java/com/example/coffeestock/sync/SyncMetricsTest.kt)
- [Mã nguồn đo đồng bộ lô 20 movement](../app/src/androidTest/java/com/example/coffeestock/sync/SyncScenarioTest.kt)
