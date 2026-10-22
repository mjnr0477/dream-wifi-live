"use strict";

const { diagnoseSourceNetwork } = require("../product/dream_wifi_source_discovery");

diagnoseSourceNetwork().then(result => {
  process.stdout.write(JSON.stringify(result, null, 2) + "\n");
  if (!result.gatewayDetected) {
    console.error("No private/local default gateway was detected. The source agent may be offline or using an unsupported routing environment.");
    process.exitCode = 2;
  } else if (!result.internet.dnsResolved || !result.internet.tcp443Reachable) {
    console.error("A local gateway was found, but public internet reachability was not fully verified.");
    process.exitCode = 3;
  }
}).catch(error => {
  console.error("Dream Wi-Fi source diagnostic failed:", error.message);
  process.exitCode = 1;
});
