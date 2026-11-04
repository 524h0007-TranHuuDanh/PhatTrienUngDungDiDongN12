# D4. Mã nguồn máy chủ và API

Tài liệu này mô tả backend CoffeeStock theo mã nguồn hiện tại, gồm vị trí mã nguồn, API CRUD, cơ sở dữ liệu dùng chung, định danh/phiên bản bản ghi, cơ chế đồng bộ, dữ liệu mẫu và cách chạy server.

## 1. Thông tin Repository

Mã nguồn được lưu trên GitHub:

- **Repository:** [524h0007-TranHuuDanh/PhatTrienUngDungDiDongN12](https://github.com/524h0007-TranHuuDanh/PhatTrienUngDungDiDongN12)
- **Nhánh:** `main`
- **Quyền truy cập:** Public
- **Thư mục máy chủ:** `server/` tại thư mục gốc repository

Các thành phần chính:

| Đường dẫn | Chức năng |
|---|---|
| `server/src/server.js`, `server/src/app.js` | Khởi tạo Express, health check, đăng ký routes và middleware |
| `server/src/modules/movements/` | API CRUD phiếu nhập/xuất |
| `server/src/modules/sync/` | API push/pull và xử lý version, idempotency, lịch sử |
| `server/src/middleware/apiKey.middleware.js` | Kiểm tra API key |
| `server/src/database/migrations/001_initial_schema.sql` | Schema SQLite và các index |
| `server/src/scripts/seed.js` | Tạo 5 phiếu mẫu |
| `server/tests/sync.integration.test.js` | Integration test cho API đồng bộ |

Backend dùng Node.js, Express, `better-sqlite3` và SQLite ở chế độ WAL. Mỗi client kết nối cùng một địa chỉ server sẽ đọc/ghi vào cùng database của server.

## 2. Cấu hình API chung

- **Base URL mặc định:** `http://localhost:8080`
- **Health check:** `GET /health` (không yêu cầu API key)
- **Header xác thực:** `X-API-Key: <API_KEY>` (bắt buộc với các endpoint `/api/v1/...`)
- **Content-Type khi gửi JSON:** `application/json`
- **Giới hạn body JSON:** 1 MB

Ví dụ kiểm tra server:

```bash
curl http://localhost:8080/health
```

Phản hồi:

```json
{
  "status": "ok",
  "service": "CoffeeStock API",
  "version": "1.0.0"
}
```

Nếu thiếu hoặc sai API key, server trả HTTP `401`:

```json
{
  "error": {
    "code": "INVALID_API_KEY",
    "message": "API key không hợp lệ"
  }
}
```

## 3. API CRUD phiếu nhập/xuất

Các endpoint CRUD có tiền tố `/api/v1/movements` và đều yêu cầu `X-API-Key`.

| Phương thức | Endpoint | Mô tả | Thành công |
|---|---|---|---|
| `GET` | `/api/v1/movements` | Lấy danh sách phiếu chưa bị xóa mềm | `200` |
| `GET` | `/api/v1/movements/:id` | Lấy một phiếu theo ID | `200`, hoặc `404` nếu không tìm thấy/đã xóa |
| `POST` | `/api/v1/movements` | Tạo phiếu mới | `201` |
| `PUT` | `/api/v1/movements/:id` | Cập nhật phiếu nháp theo `base_version` | `200` |
| `DELETE` | `/api/v1/movements/:id` | Xóa mềm phiếu theo `base_version` | `200` |

### 3.1. Dữ liệu phiếu

Các trường phiếu được trả về bởi API:

| Trường | Ý nghĩa |
|---|---|
| `id` | Định danh phiếu do client tạo; khóa chính duy nhất trên server |
| `product_id`, `product_name` | Định danh và tên sản phẩm |
| `type` | Loại phiếu: `IN` (nhập) hoặc `OUT` (xuất) |
| `quantity` | Số lượng, phải lớn hơn 0 |
| `batch_code` | Mã lô; bắt buộc với phiếu `IN` |
| `partner`, `note` | Đối tác và ghi chú |
| `status` | `DRAFT` hoặc `CONFIRMED`; phiếu đã xác nhận không cho sửa/xóa |
| `server_version` | Phiên bản do server quản lý, tăng sau mỗi cập nhật/xóa |
| `server_seq` | Số thứ tự thay đổi do server cấp, dùng làm cursor đồng bộ |
| `is_deleted` | Cho biết phiếu đã bị xóa mềm |
| `created_at`, `updated_at` | Thời điểm tạo và cập nhật dạng ISO-8601 |

### 3.2. Tạo phiếu

`POST /api/v1/movements` yêu cầu header `Idempotency-Key` riêng cho thao tác:

```bash
curl -X POST http://localhost:8080/api/v1/movements \
  -H "Content-Type: application/json" \
  -H "X-API-Key: coffeestock-dev-key" \
  -H "Idempotency-Key: 3bfc4216-90d9-4ad4-af03-61d0b6725aec" \
  -d '{
    "id": "movement-demo-001",
    "product_id": "product-001",
    "product_name": "Cà phê Arabica",
    "type": "IN",
    "quantity": 10,
    "batch_code": "BATCH-001",
    "partner": "Nhà cung cấp A",
    "note": "Phiếu nhập mẫu",
    "status": "DRAFT",
    "created_at": "2026-11-04T03:00:00.000Z",
    "updated_at": "2026-11-04T03:00:00.000Z"
  }'
```

Phản hồi thành công có dạng `{"movement": {...}}`. Nếu ID đã tồn tại, API trả lỗi `MOVEMENT_ALREADY_EXISTS` với HTTP `409`.

### 3.3. Cập nhật phiếu

`PUT /api/v1/movements/:id` cần `Idempotency-Key` và `base_version` hiện tại của phiếu. Dữ liệu nhập/xuất cần gửi lại các trường bắt buộc tương tự khi tạo:

```json
{
  "base_version": 1,
  "product_id": "product-001",
  "product_name": "Cà phê Arabica",
  "type": "IN",
  "quantity": 15,
  "batch_code": "BATCH-001",
  "partner": "Nhà cung cấp A",
  "note": "Điều chỉnh số lượng",
  "status": "DRAFT",
  "created_at": "2026-11-04T03:00:00.000Z",
  "updated_at": "2026-11-04T03:10:00.000Z"
}
```

Nếu `base_version` khác phiên bản hiện tại trên server, thao tác bị từ chối với HTTP `409` và mã `VERSION_CONFLICT`. Phản hồi cung cấp `current_movement` để client xử lý xung đột.

### 3.4. Xóa phiếu

Xóa là xóa mềm: bản ghi không bị loại khỏi database mà được đánh dấu `deleted_at`; API đồng bộ pull có thể trả về tombstone để các client khác cũng xóa bản ghi cục bộ.

`DELETE /api/v1/movements/:id` cần header `Idempotency-Key` và body:

```json
{
  "base_version": 1
}
```

Phiếu `CONFIRMED` không thể sửa hoặc xóa. Các thao tác cập nhật/xóa trên phiếu đã bị xóa hoặc không tồn tại cũng bị từ chối.

## 4. API đồng bộ hai chiều

Các endpoint đồng bộ dùng tiền tố `/api/v1/sync` và yêu cầu `X-API-Key`.

| Phương thức | Endpoint | Mô tả |
|---|---|---|
| `POST` | `/api/v1/sync/push` | Gửi batch mutation từ client |
| `GET` | `/api/v1/sync/pull` | Lấy thay đổi sau cursor |
| `GET` | `/api/v1/sync/movements` | Lấy danh sách phiếu hiện hành chưa xóa mềm |

### 4.1. Push thay đổi từ client

`POST /api/v1/sync/push` nhận từ 1 đến 100 thao tác trong một request:

```json
{
  "items": [
    {
      "id": "movement-demo-001",
      "operation": "CREATE",
      "mutation_id": "3bfc4216-90d9-4ad4-af03-61d0b6725aec",
      "base_version": 0,
      "local_rev": 1,
      "product_id": "product-001",
      "product_name": "Cà phê Arabica",
      "type": "IN",
      "quantity": 10,
      "batch_code": "BATCH-001",
      "partner": "Nhà cung cấp A",
      "note": "Phiếu nhập mẫu",
      "status": "DRAFT",
      "is_deleted": false,
      "created_at": "2026-11-04T03:00:00.000Z",
      "updated_at": "2026-11-04T03:00:00.000Z"
    }
  ]
}
```

`operation` nhận `CREATE`, `UPDATE` hoặc `DELETE`. Mỗi thao tác phải có `id` và `mutation_id`; thao tác `UPDATE`/`DELETE` cần `base_version` khớp với version trên server. `local_rev` giúp client nhận diện phiên bản chỉnh sửa cục bộ; server không dùng trường này làm version chuẩn.

Phản hồi có danh sách `results`. Mỗi kết quả chứa `id`, `mutation_id`, `ok`, `movement` và `error`. Phiên bản và `server_seq` trong movement do server cấp. Khi có `VERSION_CONFLICT` trong kết quả, HTTP status của request là `409`; các lỗi nghiệp vụ khác được trả trong `results` và request có thể vẫn là HTTP `200`.

### 4.2. Pull thay đổi từ server

`GET /api/v1/sync/pull?after_seq=<cursor>&limit=<số lượng>`

- `after_seq`: số nguyên an toàn, không âm; mặc định `0`.
- `limit`: từ `1` đến `500`; mặc định `100`.
- Server trả các thay đổi có `server_seq` lớn hơn cursor, theo thứ tự tăng dần.
- Dùng `next_cursor` cho lần pull tiếp theo; tiếp tục gọi khi `has_more` là `true`.
- Tombstone xóa mềm cũng được trả về trong pull.

Ví dụ:

```bash
curl "http://localhost:8080/api/v1/sync/pull?after_seq=0&limit=100" \
  -H "X-API-Key: coffeestock-dev-key"
```

Phản hồi:

```json
{
  "items": [
    {
      "id": "movement-demo-001",
      "product_id": "product-001",
      "product_name": "Cà phê Arabica",
      "type": "IN",
      "quantity": 10,
      "batch_code": "BATCH-001",
      "partner": "Nhà cung cấp A",
      "note": "Phiếu nhập mẫu",
      "status": "DRAFT",
      "server_version": 1,
      "server_seq": 1,
      "is_deleted": false,
      "created_at": "2026-11-04T03:00:00.000Z",
      "updated_at": "2026-11-04T03:00:00.000Z"
    }
  ],
  "next_cursor": 1,
  "has_more": false
}
```

Tham số pull không hợp lệ trả HTTP `400` với `INVALID_PULL_PARAMETERS`.

### 4.3. Định danh, version và tính idempotent

1. Client tạo `id` ổn định cho phiếu; server đặt `id` làm khóa chính và không cho tạo trùng.
2. `mutation_id` duy nhất cho mỗi mutation thành công được lưu trong bảng idempotency. Gửi lại mutation đã xử lý thành công với cùng ID trả lại kết quả đã lưu, tránh áp dụng lại thay đổi khi client retry. Kết quả lỗi nghiệp vụ không được lưu vào bảng này.
3. Với tạo mới, server khởi tạo `version = 1`. Với cập nhật hoặc xóa hợp lệ, server tăng version lên 1.
4. Client gửi `base_version` để optimistic locking. Nếu version không khớp, server trả xung đột thay vì ghi đè im lặng.
5. Mỗi lần thay đổi, server cấp `server_seq` tăng dần dùng làm cursor pull; `updated_at` dùng để ghi nhận thời điểm cập nhật.
6. Các thao tác xử lý từng movement được thực hiện trong transaction SQLite. Request push được xử lý theo từng item; không nên hiểu toàn bộ danh sách là một transaction duy nhất.

## 5. Lưu trữ dữ liệu dùng chung

Database mặc định là `server/data/coffeestock.db`. Có thể thay đổi bằng `DATABASE_PATH`. Khi server khởi động, schema được áp dụng từ migration `001_initial_schema.sql`; thư mục chứa database được tạo nếu chưa có.

| Bảng | Dữ liệu lưu |
|---|---|
| `stock_movement` | Trạng thái hiện tại của phiếu, version, sequence và tombstone |
| `movement_history` | Snapshot JSON của trạng thái cũ khi phiếu được cập nhật/xóa |
| `idempotency_records` | Kết quả mutation theo `mutation_id` |
| `sync_meta` | Sequence cuối cùng (`last_server_seq`) dùng để cấp `server_seq` |

Các index gồm `idx_movement_server_seq`, `idx_movement_updated_at` và `idx_history_movement`. SQLite bật WAL, foreign keys và busy timeout 5 giây.

Server không có bảng sản phẩm riêng trong migration hiện tại; `product_id` và `product_name` được lưu trực tiếp trên từng phiếu.

## 6. Dữ liệu mẫu và kiểm thử

Trong thư mục `server/`, cài dependencies rồi chạy script seed:

```bash
npm install
node src/scripts/seed.js
```

Script thêm 5 phiếu với ID cố định từ `mov-seed-001` đến `mov-seed-005`, đại diện các sản phẩm Arabica, Robusta, Culi; có phiếu `IN`, `OUT`, `DRAFT`, `CONFIRMED` và một tombstone đã xóa. Các ID đã có sẽ không bị ghi đè.

Chạy integration test:

```bash
npm test
```

Test dùng database tạm và kiểm tra health check, API key, push/idempotency, optimistic locking, pull/cursor, tombstone và input không hợp lệ.

## 7. Hướng dẫn triển khai server

### 7.1. Chạy local

Yêu cầu Node.js 20+ và npm 10+. Từ thư mục gốc repository:

**Windows PowerShell:**
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

Sửa `server/.env`:

```dotenv
PORT=8080
HOST=0.0.0.0
API_KEY=thay-bang-khoa-rieng-cua-moi-truong
DATABASE_PATH=./data/coffeestock.db
```

`API_KEY` là bắt buộc; server không khởi động nếu chưa cấu hình. Không dùng khóa phát triển công khai trong môi trường triển khai. Sau đó khởi động:

```bash
npm start
```

Chạy chế độ phát triển tự khởi động lại khi sửa mã:

```bash
npm run dev
```

Kiểm tra endpoint health tại `http://localhost:8080/health`. Các lệnh `npm` ở trên được chạy trong thư mục `server/`.

### 7.2. Ghi chú triển khai

- Mỗi môi trường cần một API key riêng; cấu hình cùng API key trong ứng dụng client tương ứng.
- API hiện dùng HTTP ở tầng Express; khi triển khai qua mạng, đặt sau HTTPS reverse proxy/load balancer và chỉ mở cổng cần thiết.
- Bảo vệ file `.env` và database; tạo backup SQLite phù hợp với WAL. Không commit `.env`, database hoặc API key vào Git.
- Nếu ứng dụng chạy trên thiết bị thật, đặt base URL của app thành IP/tên miền server có thể truy cập được, cấu hình firewall và dùng HTTPS khi triển khai ngoài môi trường phát triển.

## 8. Mã lỗi API thường gặp

| HTTP | Mã lỗi | Ý nghĩa |
|---|---|---|
| `400` | `INVALID_JSON`, `INVALID_PULL_PARAMETERS` | JSON hoặc tham số pull không hợp lệ |
| `401` | `INVALID_API_KEY` | Thiếu/sai API key |
| `404` | `NOT_FOUND`, `MOVEMENT_NOT_FOUND` | Endpoint hoặc phiếu không tồn tại |
| `409` | `VERSION_CONFLICT`, `MOVEMENT_ALREADY_EXISTS`, `MOVEMENT_DELETED`, `CONFIRMED_IMMUTABLE` | Xung đột version hoặc thao tác không được phép |
| `413` | `REQUEST_TOO_LARGE` | Body vượt giới hạn 1 MB |
| `422` | `INVALID_*`, `MISSING_REQUIRED_FIELD`, `TOO_MANY_ITEMS` | Dữ liệu nghiệp vụ không hợp lệ |
| `500` | `INTERNAL_SERVER_ERROR` | Lỗi nội bộ server |

Các lỗi nghiệp vụ của `/sync/push` có thể nằm trong từng phần tử `results`; client cần đọc cả HTTP status lẫn `results[].ok`/`results[].error`.

## 9. Tài liệu liên quan

- Hướng dẫn build, chạy server và app: [README.md](../README.md)
- Mã nguồn API CRUD: `server/src/modules/movements/`
- Mã nguồn đồng bộ: `server/src/modules/sync/`
- Schema database: `server/src/database/migrations/001_initial_schema.sql`
- Dữ liệu mẫu: `server/src/scripts/seed.js`
- Integration test: `server/tests/sync.integration.test.js`
