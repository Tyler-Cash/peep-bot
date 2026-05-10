import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["test/**/*.test.ts"],
    // Each test file imports compiled output via the .js extension; we run
    // tsc once before the suite via the npm test script.
    pool: "forks",
    testTimeout: 30_000,
  },
});
