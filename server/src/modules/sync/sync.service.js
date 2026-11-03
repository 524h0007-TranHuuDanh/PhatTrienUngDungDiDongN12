const repository = require("./sync.repository");
const HttpError = require("../../utils/httpError");

function getNow() {
    return new Date().toISOString();
}

function normalizeTimestamp(value) {
    if (value === undefined || value === null || value === "") {
        return getNow();
    }

    const numericValue = Number(value);

    const date = /^\d+$/.test(String(value))
        ? new Date(numericValue)
        : new Date(value);

    if (Number.isNaN(date.getTime())) {
        throw new HttpError(
            422,
            "INVALID_TIMESTAMP",
            "Thời gian không hợp lệ"
        );
    }

    return date.toISOString();
}

function validateMovement(item) {
    if (
        !item.id ||
        typeof item.id !== "string" ||
        !item.mutation_id ||
        typeof item.mutation_id !== "string" ||
        !item.operation
    ) {
        throw new HttpError(
            422,
            "MISSING_REQUIRED_FIELD",
            "Thiếu id, mutation_id hoặc operation"
        );
    }

    if (
        !["CREATE", "UPDATE", "DELETE"].includes(item.operation)
    ) {
        throw new HttpError(
            422,
            "INVALID_OPERATION",
            "Operation không hợp lệ"
        );
    }

    if (item.operation === "DELETE") {
        return;
    }

    if (
        !item.product_id ||
        typeof item.product_id !== "string" ||
        !item.product_name ||
        typeof item.product_name !== "string"
    ) {
        throw new HttpError(
            422,
            "INVALID_PRODUCT",
            "Thiếu thông tin sản phẩm"
        );
    }

    if (!["IN", "OUT"].includes(item.type)) {
        throw new HttpError(
            422,
            "INVALID_MOVEMENT_TYPE",
            "Loại phiếu phải là IN hoặc OUT"
        );
    }

    if (
        typeof item.quantity !== "number" ||
        !Number.isFinite(item.quantity) ||
        item.quantity <= 0
    ) {
        throw new HttpError(
            422,
            "INVALID_QUANTITY",
            "Số lượng phải lớn hơn 0"
        );
    }

    if (!["DRAFT", "CONFIRMED"].includes(item.status)) {
        throw new HttpError(
            422,
            "INVALID_STATUS",
            "Trạng thái phiếu không hợp lệ"
        );
    }

    if (
        item.type === "IN" &&
        (
            typeof item.batch_code !== "string" ||
            !item.batch_code.trim()
        )
    ) {
        throw new HttpError(
            422,
            "BATCH_CODE_REQUIRED",
            "Phiếu nhập bắt buộc có mã lô"
        );
    }

    if (
        item.partner !== undefined &&
        typeof item.partner !== "string"
    ) {
        throw new HttpError(
            422,
            "INVALID_PARTNER",
            "Đối tác không hợp lệ"
        );
    }

    if (
        item.note !== undefined &&
        typeof item.note !== "string"
    ) {
        throw new HttpError(
            422,
            "INVALID_NOTE",
            "Ghi chú không hợp lệ"
        );
    }

    normalizeTimestamp(item.created_at);
    normalizeTimestamp(item.updated_at);
}

function validateBaseVersion(value) {
    if (
        !Number.isSafeInteger(value) ||
        value < 1
    ) {
        throw new HttpError(
            422,
            "INVALID_BASE_VERSION",
            "base_version phải là số nguyên lớn hơn hoặc bằng 1"
        );
    }
}

function validateMutationId(mutationId) {
    if (
        typeof mutationId !== "string" ||
        mutationId.trim().length === 0 ||
        mutationId.length > 200
    ) {
        throw new HttpError(
            422,
            "INVALID_MUTATION_ID",
            "Idempotency-Key không hợp lệ hoặc bị thiếu"
        );
    }
}

function toRemoteMovement(row) {
    if (!row) {
        return null;
    }

    return {
        id: row.id,
        product_id: row.product_id,
        product_name: row.product_name,
        type: row.type,
        quantity: row.quantity,
        batch_code: row.batch_code,
        partner: row.partner,
        note: row.note,
        status: row.status,
        server_version: row.version,
        server_seq: row.server_seq,
        is_deleted: row.deleted_at !== null,
        created_at: row.created_at,
        updated_at: row.updated_at
    };
}

function createErrorResult(
    item,
    code,
    message,
    currentMovement
) {
    return {
        id: item.id,
        mutation_id: item.mutation_id,
        ok: false,
        movement: null,
        error: {
            code,
            message,
            current_movement: toRemoteMovement(currentMovement)
        }
    };
}

function createSuccessResult(item, movement) {
    return {
        id: item.id,
        mutation_id: item.mutation_id,
        ok: true,
        movement: toRemoteMovement(movement),
        error: null
    };
}

function saveHistoryAndUpdate(oldMovement, newMovement, now) {
    repository.saveHistory(oldMovement, now);

    repository.updateMovement(newMovement);
}

function processOne(item) {
    validateMovement(item);

    const now = getNow();

    const previousResult = repository.getIdempotency(
        item.mutation_id
    );

    if (previousResult) {
        return JSON.parse(previousResult.response_json);
    }

    const current = repository.getMovement(item.id);

    if (item.operation === "CREATE") {
        if (current) {
            return createErrorResult(
                item,
                "MOVEMENT_ALREADY_EXISTS",
                "Phiếu đã tồn tại trên máy chủ",
                current
            );
        }

        const sequence = repository.nextServerSequence();

        const movement = {
            id: item.id,
            product_id: item.product_id,
            product_name: item.product_name,
            type: item.type,
            quantity: item.quantity,
            batch_code: item.batch_code || "",
            partner: item.partner || "",
            note: item.note || "",
            status: item.status,
            version: 1,
            server_seq: sequence,
            deleted_at: null,
            created_at: normalizeTimestamp(item.created_at),
            updated_at: normalizeTimestamp(item.updated_at)
        };

        repository.insertMovement(movement);

        const result = createSuccessResult(
            item,
            movement
        );

        repository.saveIdempotency(
            item.mutation_id,
            result,
            now
        );

        return result;
    }

    if (!current) {
        return createErrorResult(
            item,
            "MOVEMENT_NOT_FOUND",
            "Không tìm thấy phiếu trên máy chủ",
            null
        );
    }

    if (current.version !== item.base_version) {
        return createErrorResult(
            item,
            "VERSION_CONFLICT",
            "Phiên bản phiếu trên máy chủ đã thay đổi",
            current
        );
    }

    if (current.deleted_at !== null) {
        return createErrorResult(
            item,
            "MOVEMENT_DELETED",
            "Phiếu đã bị xóa trên máy chủ",
            current
        );
    }

    if (current.status === "CONFIRMED") {
        return createErrorResult(
            item,
            "CONFIRMED_IMMUTABLE",
            "Phiếu đã xác nhận không thể sửa hoặc xóa",
            current
        );
    }

    const sequence = repository.nextServerSequence();

    const updated = {
        ...current,
        version: current.version + 1,
        server_seq: sequence,
        updated_at: now
    };

    if (item.operation === "DELETE") {
        updated.deleted_at = now;
    }

    else {
        updated.product_id = item.product_id;
        updated.product_name = item.product_name;
        updated.type = item.type;
        updated.quantity = item.quantity;
        updated.batch_code = item.batch_code || "";
        updated.partner = item.partner || "";
        updated.note = item.note || "";
        updated.status = item.status;
        updated.created_at = current.created_at;
        updated.deleted_at = null;
    }

    saveHistoryAndUpdate(
        current,
        updated,
        now
    );

    const result = createSuccessResult(
        item,
        updated
    );

    repository.saveIdempotency(
        item.mutation_id,
        result,
        now
    );

    return result;
}

function executeMovement(item) {
    return repository.db.transaction(() => {
        return processOne(item);
    })();
}

function pushChanges(request) {
    if (
        !request ||
        !Array.isArray(request.items)
    ) {
        throw new HttpError(
            422,
            "INVALID_PUSH_REQUEST",
            "Request phải chứa danh sách items"
        );
    }

    if (request.items.length === 0) {
        throw new HttpError(
            422,
            "EMPTY_PUSH_REQUEST",
            "Danh sách items không được rỗng"
        );
    }

    if (request.items.length > 100) {
        throw new HttpError(
            422,
            "TOO_MANY_ITEMS",
            "Mỗi request tối đa 100 phiếu"
        );
    }

    const results = [];

    for (const item of request.items) {
        const result = executeMovement(item);

        results.push(result);
    }

    return results;
}

function pullChanges(afterSeq, limit) {
    const rows = repository.getChanges(
        afterSeq,
        limit + 1
    );

    const hasMore = rows.length > limit;

    const items = rows.slice(0, limit);

    const nextCursor = items.length > 0
        ? items[items.length - 1].server_seq
        : afterSeq;

    return {
        items: items.map(toRemoteMovement),
        next_cursor: nextCursor,
        has_more: hasMore
    };
}

function getMovements() {
    return repository.getAllMovements().map(
        toRemoteMovement
    );
}

function getMovementById(id) {
    const movement = repository.getMovement(id);

    if (!movement || movement.deleted_at !== null) {
        return null;
    }

    return toRemoteMovement(movement);
}

function createMovement(data, mutationId) {
    validateMutationId(mutationId);

    if (!data || typeof data !== "object" || Array.isArray(data)) {
        throw new HttpError(
            422,
            "INVALID_MOVEMENT_DATA",
            "Dữ liệu phiếu không hợp lệ"
        );
    }

    const item = {
        ...data,
        operation: "CREATE",
        mutation_id: mutationId
    };

    return executeMovement(item);
}

function updateMovement(id, data, mutationId) {
    validateMutationId(mutationId);

    if (!data || typeof data !== "object" || Array.isArray(data)) {
        throw new HttpError(
            422,
            "INVALID_MOVEMENT_DATA",
            "Dữ liệu phiếu không hợp lệ"
        );
    }

    validateBaseVersion(data.base_version);

    const item = {
        ...data,
        id,
        operation: "UPDATE",
        mutation_id: mutationId
    };

    return executeMovement(item);
}

function deleteMovement(id, baseVersion, mutationId) {
    validateMutationId(mutationId);

    validateBaseVersion(baseVersion);

    const item = {
        id,
        operation: "DELETE",
        base_version: baseVersion,
        mutation_id: mutationId
    };

    return executeMovement(item);
}

module.exports = {
    pushChanges,
    pullChanges,

    getMovements,
    getMovementById,

    createMovement,
    updateMovement,
    deleteMovement
};