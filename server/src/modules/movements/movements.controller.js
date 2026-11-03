const syncService = require("../sync/sync.service");

function getErrorStatus(code) {
    switch (code) {
        case "MOVEMENT_NOT_FOUND":
            return 404;

        case "VERSION_CONFLICT":
        case "MOVEMENT_ALREADY_EXISTS":
        case "MOVEMENT_DELETED":
        case "CONFIRMED_IMMUTABLE":
            return 409;

        default:
            return 422;
    }
}

function sendMutationResponse(res, result, successStatus) {
    if (!result.ok) {
        return res.status(
            getErrorStatus(result.error.code)
        ).json({
            error: {
                code: result.error.code,
                message: result.error.message,
                current_movement:
                    result.error.current_movement
            }
        });
    }

    return res.status(successStatus).json({
        movement: result.movement
    });
}

// GET /api/v1/movements
function getAll(req, res, next) {
    try {
        const items = syncService.getMovements();

        return res.status(200).json({
            items,
            total: items.length
        });
    } catch (error) {
        next(error);
    }
}

// GET /api/v1/movements/:id
function getById(req, res, next) {
    try {
        const movement = syncService.getMovementById(
            req.params.id
        );

        if (!movement) {
            return res.status(404).json({
                error: {
                    code: "MOVEMENT_NOT_FOUND",
                    message: "Không tìm thấy phiếu"
                }
            });
        }

        return res.status(200).json({
            movement
        });
    } catch (error) {
        next(error);
    }
}

// POST /api/v1/movements
function create(req, res, next) {
    try {
        const mutationId = req.get("Idempotency-Key");

        const result = syncService.createMovement(
            req.body,
            mutationId
        );

        return sendMutationResponse(
            res,
            result,
            201
        );
    } catch (error) {
        next(error);
    }
}

// PUT /api/v1/movements/:id
function update(req, res, next) {
    try {
        const mutationId = req.get("Idempotency-Key");

        const result = syncService.updateMovement(
            req.params.id,
            req.body,
            mutationId
        );

        return sendMutationResponse(
            res,
            result,
            200
        );
    } catch (error) {
        next(error);
    }
}

// DELETE /api/v1/movements/:id
function remove(req, res, next) {
    try {
        const mutationId = req.get("Idempotency-Key");

        const baseVersion = req.body?.base_version;

        const result = syncService.deleteMovement(
            req.params.id,
            baseVersion,
            mutationId
        );

        return sendMutationResponse(
            res,
            result,
            200
        );
    } catch (error) {
        next(error);
    }
}

module.exports = {
    getAll,
    getById,
    create,
    update,
    remove
};