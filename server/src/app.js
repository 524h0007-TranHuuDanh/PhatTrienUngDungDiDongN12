const express = require("express");

const syncRoutes = require("./modules/sync/sync.routes");
const movementsRoutes = require("./modules/movements/movements.routes");

const errorMiddleware = require("./middleware/error.middleware");

const app = express();

app.use(express.json({
    limit: "1mb"
}));

app.get("/health", (req, res) => {
    res.status(200).json({
        status: "ok",
        service: "CoffeeStock API",
        version: "1.0.0"
    });
});

app.use(
    "/api/v1/sync",
    syncRoutes
);

app.use(
    "/api/v1/movements",
    movementsRoutes
);

app.use((req, res) => {
    res.status(404).json({
        error: {
            code: "NOT_FOUND",
            message: "API không tồn tại"
        }
    });
});

app.use(errorMiddleware);

module.exports = app;