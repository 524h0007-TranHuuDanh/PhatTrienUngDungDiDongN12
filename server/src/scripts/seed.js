require("dotenv").config();

const db = require("../database/connection");

const now = new Date().toISOString();

const PRODUCTS = {
    arabica: { id: "product-001", name: "Cà phê Arabica" },
    robusta: { id: "product-002", name: "Cà phê Robusta" },
    culi:    { id: "product-003", name: "Cà phê Culi" }
};

const SAMPLE_MOVEMENTS = [
    {
        id: "mov-seed-001",
        product: PRODUCTS.arabica,
        type: "IN",
        quantity: 50,
        batch_code: "LOT-ARABICA-001",
        partner: "Nhà cung cấp A",
        note: "Nhập lô đầu kỳ",
        status: "CONFIRMED",
        deleted: false
    },
    {
        id: "mov-seed-002",
        product: PRODUCTS.arabica,
        type: "OUT",
        quantity: 10,
        batch_code: "",
        partner: "Cửa hàng B",
        note: "Xuất bán lẻ",
        status: "CONFIRMED",
        deleted: false
    },
    {
        id: "mov-seed-003",
        product: PRODUCTS.robusta,
        type: "IN",
        quantity: 30,
        batch_code: "LOT-ROBUSTA-001",
        partner: "Nhà cung cấp B",
        note: "Nhập kho tháng 11",
        status: "DRAFT",
        deleted: false
    },
    {
        id: "mov-seed-004",
        product: PRODUCTS.culi,
        type: "IN",
        quantity: 20,
        batch_code: "LOT-CULI-001",
        partner: "Nhà cung cấp C",
        note: "Phiếu nháp chờ xác nhận",
        status: "DRAFT",
        deleted: false
    },
    {
        id: "mov-seed-005",
        product: PRODUCTS.robusta,
        type: "OUT",
        quantity: 5,
        batch_code: "",
        partner: "Cửa hàng D",
        note: "Phiếu đã bị xóa trên server",
        status: "DRAFT",
        deleted: true  // tombstone — minh họa TC08
    }
];

const insertMovement = db.prepare(`
    INSERT OR IGNORE INTO stock_movement (
        id, product_id, product_name, type, quantity,
        batch_code, partner, note, status, version, server_seq,
        deleted_at, created_at, updated_at
    ) VALUES (
        @id, @product_id, @product_name, @type, @quantity,
        @batch_code, @partner, @note, @status, @version, @server_seq,
        @deleted_at, @created_at, @updated_at
    )
`);

const getLastSeq = db.prepare(
    `SELECT last_server_seq FROM sync_meta WHERE id = 1`
);
const updateLastSeq = db.prepare(
    `UPDATE sync_meta SET last_server_seq = ? WHERE id = 1`
);

function seed() {
    const tx = db.transaction(() => {
        let seq = getLastSeq.get().last_server_seq;

        for (const m of SAMPLE_MOVEMENTS) {
            seq += 1;
            insertMovement.run({
                id: m.id,
                product_id: m.product.id,
                product_name: m.product.name,
                type: m.type,
                quantity: m.quantity,
                batch_code: m.batch_code,
                partner: m.partner,
                note: m.note,
                status: m.status,
                version: 1,
                server_seq: seq,
                deleted_at: m.deleted ? now : null,
                created_at: now,
                updated_at: now
            });
        }

        updateLastSeq.run(seq);
    });

    tx();
}

try {
    seed();
    const total = db
        .prepare(`SELECT COUNT(*) AS c FROM stock_movement`)
        .get().c;
    console.log(
        `[seed] Đã thêm ${SAMPLE_MOVEMENTS.length} movements mẫu (tổng trong DB: ${total})`
    );
} catch (e) {
    console.error("[seed] Lỗi:", e);
    process.exitCode = 1;
} finally {
    db.close();
}