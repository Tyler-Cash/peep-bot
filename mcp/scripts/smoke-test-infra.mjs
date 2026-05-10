#!/usr/bin/env node
// Standalone smoke for test_affinity + test_mocks (no MCP framing).
import { testAffinityReport, testMocksReport } from "../dist/testInfra.js";

console.log("=== test_affinity (groupsOnly) ===");
console.log((await testAffinityReport({ groupsOnly: true })).slice(0, 4000));
console.log();

console.log("=== test_mocks ===");
console.log((await testMocksReport({})).slice(0, 4000));
console.log();

console.log("=== test_mocks(type='DiscordService') ===");
console.log(await testMocksReport({ type: "DiscordService" }));
