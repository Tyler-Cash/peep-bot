import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright config for Peep Bot smoke tests.
 *
 * Tests run against the Next.js dev server in mock mode (NEXT_PUBLIC_API_MODE=mock,
 * the default). The MSW worker (src/mocks/handlers.ts) intercepts all /api/* calls
 * and serves an in-memory store, so we don't need the Spring Boot backend, Postgres,
 * or any Discord secrets to exercise the golden-path flows end-to-end in the browser.
 *
 * To run all browsers locally, set ALL_BROWSERS=1.
 */

const PORT = Number(process.env.PORT ?? 3100);
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL ?? `http://localhost:${PORT}`;
const ALL_BROWSERS = process.env.ALL_BROWSERS === "1";

const projects = [
  { name: "chromium", use: { ...devices["Desktop Chrome"] } },
];
if (ALL_BROWSERS) {
  projects.push({ name: "firefox", use: { ...devices["Desktop Firefox"] } });
  projects.push({ name: "webkit", use: { ...devices["Desktop Safari"] } });
}

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [["github"], ["list"]] : "list",
  use: {
    baseURL: BASE_URL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects,
  webServer: process.env.PLAYWRIGHT_SKIP_WEBSERVER
    ? undefined
    : {
        command: `npm run dev -- -p ${PORT}`,
        url: BASE_URL,
        timeout: 120_000,
        reuseExistingServer: !process.env.CI,
        env: {
          NEXT_PUBLIC_API_MODE: "mock",
          NEXT_TELEMETRY_DISABLED: "1",
        },
      },
});
