const app = require("./app");
const config = require("./config/env");

require("./database/connection");

app.listen(config.port, config.host, () => {
    console.log("------------------------------------");
    console.log("CoffeeStock API is running");
    console.log(`Address: http://localhost:${config.port}`);
    console.log(`Health: http://localhost:${config.port}/health`);
    console.log("------------------------------------");
});