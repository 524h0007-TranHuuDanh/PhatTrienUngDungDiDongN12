
const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const tempDirectory = fs.mkdtempSync(
    path.join(os.tmpdir(), "coffeestock-integration-")
);

const databasePath = path.join(
    tempDirectory,
    "coffeestock-test.db"
);

const API_KEY = "coffeestock-integration-test-key";

process.env.API_KEY = API_KEY;
process.env.DATABASE_PATH = databasePath;
process.env.HOST = "127.0.0.1";
process.env.PORT = "0";

const app = require("../src/app");
const db = require("../src/database/connection");

let server;
let baseUrl;

async function startTestServer() {
    server = app.listen(0, "127.0.0.1");

    await new Promise((resolve, reject) => {
        server.once("listening", resolve);
        server.once("error", reject);
    });

    const address = server.address();

    if (!address || typeof address === "string") {
        throw new Error("Không lấy được địa chỉ server kiểm thử");
    }

    baseUrl = `http://127.0.0.1:${address.port}`;
}

async function stopTestServer() {
    if (!server) {
        return;
    }

    await new Promise((resolve, reject) => {
        server.close(error => {
            if (error) {
                reject(error);
            } else {
                resolve();
            }
        });
    });

    server = null;
}

async function apiRequest(
    endpoint,
    options = {},
    includeApiKey = true
) {
    const headers = {
        "Content-Type": "application/json",
        ...(options.headers || {})
    };

    if (includeApiKey) {
        headers["X-API-Key"] = API_KEY;
    }

    const response = await fetch(`${baseUrl}${endpoint}`, {
        ...options,
        headers
    });

    const responseText = await response.text();

    let body = null;

    if (responseText) {
        try {
            body = JSON.parse(responseText);
        } catch {
            throw new Error(
                `Server trả về dữ liệu không phải JSON: ${responseText}`
            );
        }
    }

    return {
        status: response.status,
        body
    };
}

function createMovement(overrides = {}) {
    return {
        id: "movement-test-001",
        operation: "CREATE",
        mutation_id: "mutation-create-001",
        base_version: 0,
        local_rev: 1,

        product_id: "0edb8b1e-79c2-401e-aeb6-50fbd04acda0",
        product_name: "Cà phê Arabica",

        type: "IN",
        quantity: 10,

        batch_code: "BATCH-001",
        partner: "Nhà cung cấp A",
        note: "Phiếu kiểm thử",

        status: "DRAFT",
        is_deleted: false,

        created_at: "2026-11-03T01:00:00.000Z",
        updated_at: "2026-11-03T01:00:00.000Z",

        ...overrides
    };
}

async function pushItems(items) {
    return apiRequest("/api/v1/sync/push", {
        method: "POST",
        body: JSON.stringify({ items })
    });
}

test(
    "CoffeeStock Sync API integration",
    async t => {
        try {
            await startTestServer();
            await t.test("Health check trả HTTP 200", async () => {
                const response = await fetch(`${baseUrl}/health`);

                assert.equal(response.status, 200);

                const body = await response.json();

                assert.equal(body.status, "ok");
                assert.equal(body.service, "CoffeeStock API");
            });

            await t.test("Thiếu API key phải trả HTTP 401", async () => {
                const response = await apiRequest(
                    "/api/v1/sync/pull?after_seq=0&limit=100",
                    {},
                    false
                );

                assert.equal(response.status, 401);
                assert.equal(
                    response.body.error.code,
                    "INVALID_API_KEY"
                );
            });

            let createdMovement;

            await t.test("Tạo phiếu mới thành công", async () => {
                const item = createMovement();

                const response = await pushItems([item]);

                assert.equal(response.status, 200);
                assert.equal(response.body.results.length, 1);

                const result = response.body.results[0];

                assert.equal(result.ok, true);
                assert.equal(result.id, item.id);
                assert.equal(
                    result.mutation_id,
                    item.mutation_id
                );

                assert.equal(result.movement.id, item.id);
                assert.equal(result.movement.server_version, 1);
                assert.equal(result.movement.server_seq, 1);
                assert.equal(result.movement.quantity, 10);
                assert.equal(result.movement.is_deleted, false);

                createdMovement = result.movement;
            });

            await t.test(
                "Gửi lại mutation_id không tạo bản ghi mới",
                async () => {
                    const item = createMovement();

                    const response = await pushItems([item]);

                    assert.equal(response.status, 200);

                    const result = response.body.results[0];

                    assert.equal(result.ok, true);

                    assert.equal(
                        result.movement.server_version,
                        createdMovement.server_version
                    );

                    assert.equal(
                        result.movement.server_seq,
                        createdMovement.server_seq
                    );
                }
            );

            let updatedMovement;

            await t.test(
                "Cập nhật phiếu với base_version đúng",
                async () => {
                    const item = createMovement({
                        operation: "UPDATE",
                        mutation_id: "mutation-update-001",
                        base_version: 1,
                        local_rev: 2,
                        quantity: 15,
                        note: "Đã cập nhật số lượng"
                    });

                    const response = await pushItems([item]);

                    assert.equal(response.status, 200);

                    const result = response.body.results[0];

                    assert.equal(result.ok, true);
                    assert.equal(
                        result.movement.server_version,
                        2
                    );
                    assert.equal(result.movement.quantity, 15);

                    updatedMovement = result.movement;
                }
            );

            await t.test(
                "base_version cũ phải trả HTTP 409",
                async () => {
                    const item = createMovement({
                        operation: "UPDATE",
                        mutation_id: "mutation-conflict-001",
                        base_version: 1,
                        local_rev: 3,
                        quantity: 99,
                        note: "Dữ liệu chỉnh sửa từ thiết bị khác"
                    });

                    const response = await pushItems([item]);

                    assert.equal(response.status, 409);
                    assert.equal(response.body.results.length, 1);

                    const result = response.body.results[0];

                    assert.equal(result.ok, false);

                    assert.equal(
                        result.error.code,
                        "VERSION_CONFLICT"
                    );

                    assert.equal(
                        result.error.current_movement.server_version,
                        2
                    );

                    assert.equal(
                        result.error.current_movement.quantity,
                        15
                    );
                }
            );

            let cursorAfterUpdate = 0;

            await t.test(
                "Pull trả về dữ liệu mới nhất trên server",
                async () => {
                    const response = await apiRequest(
                        "/api/v1/sync/pull?after_seq=0&limit=100"
                    );

                    assert.equal(response.status, 200);
                    assert.equal(response.body.items.length, 1);

                    const movement = response.body.items[0];

                    assert.equal(
                        movement.id,
                        createdMovement.id
                    );

                    assert.equal(movement.server_version, 2);
                    assert.equal(movement.quantity, 15);
                    assert.equal(movement.is_deleted, false);

                    assert.equal(response.body.has_more, false);

                    cursorAfterUpdate = response.body.next_cursor;

                    assert.equal(cursorAfterUpdate, 2);
                }
            );

            await t.test(
                "Pull với cursor mới nhất trả danh sách rỗng",
                async () => {
                    const response = await apiRequest(
                        `/api/v1/sync/pull?after_seq=${cursorAfterUpdate}&limit=100`
                    );

                    assert.equal(response.status, 200);
                    assert.equal(response.body.items.length, 0);
                    assert.equal(
                        response.body.next_cursor,
                        cursorAfterUpdate
                    );
                    assert.equal(response.body.has_more, false);
                }
            );

            let deleteCursor = 0;

            await t.test(
                "Xóa phiếu thành công bằng base_version hiện tại",
                async () => {
                    const item = createMovement({
                        operation: "DELETE",
                        mutation_id: "mutation-delete-001",
                        base_version: 2,
                        local_rev: 4
                    });

                    const response = await pushItems([item]);

                    assert.equal(response.status, 200);

                    const result = response.body.results[0];

                    assert.equal(result.ok, true);
                    assert.equal(result.movement.is_deleted, true);
                    assert.equal(result.movement.server_version, 3);

                    deleteCursor = result.movement.server_seq;
                }
            );

            await t.test(
                "Pull nhận được tombstone sau khi xóa",
                async () => {
                    const response = await apiRequest(
                        `/api/v1/sync/pull?after_seq=${cursorAfterUpdate}&limit=100`
                    );

                    assert.equal(response.status, 200);
                    assert.equal(response.body.items.length, 1);

                    const tombstone = response.body.items[0];

                    assert.equal(
                        tombstone.id,
                        createdMovement.id
                    );

                    assert.equal(tombstone.is_deleted, true);
                    assert.equal(tombstone.server_version, 3);
                    assert.equal(tombstone.server_seq, deleteCursor);
                }
            );

            await t.test(
                "Số lượng bằng 0 phải trả HTTP 422",
                async () => {
                    const item = createMovement({
                        id: "movement-invalid-001",
                        mutation_id: "mutation-invalid-001",
                        quantity: 0
                    });

                    const response = await pushItems([item]);

                    assert.equal(response.status, 422);

                    assert.equal(
                        response.body.error.code,
                        "INVALID_QUANTITY"
                    );
                }
            );

            await t.test(
                "Pull từ cursor âm phải trả HTTP 400",
                async () => {
                    const response = await apiRequest(
                        "/api/v1/sync/pull?after_seq=-1&limit=100"
                    );

                    assert.equal(response.status, 400);

                    assert.equal(
                        response.body.error.code,
                        "INVALID_PULL_PARAMETERS"
                    );
                }
            );

            await t.test(
                "Pull với limit lớn hơn 500 phải trả HTTP 400",
                async () => {
                    const response = await apiRequest(
                        "/api/v1/sync/pull?after_seq=0&limit=501"
                    );

                    assert.equal(response.status, 400);

                    assert.equal(
                        response.body.error.code,
                        "INVALID_PULL_PARAMETERS"
                    );
                }
            );

            await t.test(
                "Push danh sách rỗng phải trả HTTP 422",
                async () => {
                    const response = await pushItems([]);

                    assert.equal(response.status, 422);

                    assert.equal(
                        response.body.error.code,
                        "EMPTY_PUSH_REQUEST"
                    );
                }
            );

            await t.test(
                "Danh sách movements không hiển thị phiếu đã xóa",
                async () => {
                    const response = await apiRequest(
                        "/api/v1/sync/movements"
                    );

                    assert.equal(response.status, 200);
                    assert.ok(Array.isArray(response.body.items));

                    const deletedMovement =
                        response.body.items.find(
                            item => item.id === createdMovement.id
                        );

                    assert.equal(deletedMovement, undefined);
                }
            );
        } finally {
            await stopTestServer();

            if (db.open) {
                db.close();
            }

            fs.rmSync(tempDirectory, {
                recursive: true,
                force: true
            });
        }
    }
);
