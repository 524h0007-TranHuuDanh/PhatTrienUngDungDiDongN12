require("dotenv").config();

const { randomUUID } = require("node:crypto");

const BASE_URL = (
    process.env.API_BASE_URL || "http://localhost:8080"
).replace(/\/$/, "");

const API_KEY = process.env.API_KEY;

const [
    movementId,
    productId,
    productName,
    type,
    quantityArg
] = process.argv.slice(2);

function assert(condition, message) {
    if (!condition) {
        throw new Error(message);
    }
}

async function push(item) {
    const response = await fetch(`${BASE_URL}/api/v1/sync/push`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "X-API-Key": API_KEY
        },
        body: JSON.stringify({ items: [item] })
    });

    let body;

    try {
        body = await response.json();
    } catch {
        throw new Error(
            `Server trả response không phải JSON (HTTP ${response.status})`
        );
    }

    return {
        status: response.status,
        body
    };
}

async function main() {
    assert(API_KEY, "Thiếu API_KEY trong file .env");

    assert(
        movementId && productId && productName && type && quantityArg,
        "Thiếu tham số. Cú pháp: node src/scripts/test-conflict.js <movementId> <productId> <productName> <type> <quantity>"
    );

    const quantity = Number(quantityArg);

    assert(
        Number.isFinite(quantity) && quantity > 0,
        "quantity phải là số lớn hơn 0"
    );

    assert(
        type === "IN" || type === "OUT",
        "type chỉ được là IN hoặc OUT"
    );

    const now = new Date().toISOString();

    const item = {
        id: movementId,
        operation: "UPDATE",
        mutation_id: randomUUID(),
        base_version: 1, 
        local_rev: 2,
        product_id: productId,
        product_name: productName,
        type: type,
        quantity: quantity,
        batch_code: type === "IN" ? "BATCH-CONFLICT-TEST" : "", 
        partner: "CLI conflict test",
        note: "Cập nhật từ thiết bị B để kiểm thử xung đột",
        status: "DRAFT",
        is_deleted: false,
        created_at: now,
        updated_at: now
    };

    console.log("[1/2] Cập nhật phiếu trên server...");
    console.log(`Movement ID: ${movementId}`);

    const result = await push(item);

    assert(
        result.status === 200,
        `Cập nhật thất bại: HTTP ${result.status} ${JSON.stringify(result.body)}`
    );

    const pushResult = result.body.results?.[0];

    assert(
        pushResult?.ok === true,
        `Server từ chối cập nhật: ${JSON.stringify(result.body)}`
    );

    assert(
        pushResult.movement?.server_version === 2,
        `Mong đợi server_version = 2 nhưng nhận ${pushResult.movement?.server_version}`
    );

    assert(
        pushResult.movement?.quantity === quantity,
        "Quantity trả về không khớp"
    );

    console.log("[2/2] Xác nhận dữ liệu server...");

    console.log("\nPASS: Đã cập nhật phiếu trên server.");
    console.log(`Movement ID: ${movementId}`);
    console.log("Server version: 1 -> 2");
    console.log(`Server quantity: ${quantity}`);
    console.log("\nBây giờ hãy quay lại Android:");
    console.log("1. Giữ nguyên phiếu local đang ở version 1.");
    console.log("2. Chỉnh sửa phiếu local trên app.");
    console.log("3. Nhấn Đồng bộ ngay để kiểm tra HTTP 409.");
}

main().catch(error => {
    console.error("\nFAIL:", error.message);
    process.exitCode = 1;
});