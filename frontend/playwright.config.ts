import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright config for Peep Bot smoke tests.
 *
 * Most tests run against the Next.js dev server in mock mode
 * (NEXT_PUBLIC_API_MODE=mock, the default). The MSW worker
 * (src/mocks/handlers.ts) intercepts all /api/* calls and serves an in-memory
 * store, so we don't need the Spring Boot backend, Postgres, or any Discord
 * secrets to exercise the golden-path flows end-to-end in the browser.
 *
 * The "live" project runs a second dev server with NEXT_PUBLIC_API_MODE=live
 * for tests under e2e/live/* that need the real fetch path (e.g. the Discord
 * OAuth popup flow). Those tests stub /api/* via Playwright route interception.
 *
 * To run all browsers locally, set ALL_BROWSERS=1.
 */

const PORT = Number(process.env.PORT ?? 3100);
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL ?? `http://localhost:${PORT}`;
const LIVE_PORT = Number(process.env.LIVE_PORT ?? 3101);
const LIVE_BASE_URL = `http://localhost:${LIVE_PORT}`;
const ALL_BROWSERS = process.env.ALL_BROWSERS === "1";

const projects: NonNullable<Parameters<typeof defineConfig>[0]["projects"]> = [
  {
    name: "chromium",
    testIgnore: /live\//,
    use: { ...devices["Desktop Chrome"] },
  },
  {
    name: "live",
    testMatch: /live\/.*\.spec\.ts/,
    use: { ...devices["Desktop Chrome"], baseURL: LIVE_BASE_URL },
  },
];
if (ALL_BROWSERS) {
  projects.push({
    name: "firefox",
    testIgnore: /live\//,
    use: { ...devices["Desktop Firefox"] },
  });
  projects.push({
    name: "webkit",
    testIgnore: /live\//,
    use: { ...devices["Desktop Safari"] },
  });
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
    : [
        {
          command: `npm run dev -- -p ${PORT}`,
          url: BASE_URL,
          timeout: 120_000,
          reuseExistingServer: !process.env.CI,
          env: {
            NEXT_PUBLIC_API_MODE: "mock",
            NEXT_TELEMETRY_DISABLED: "1",
          },
        },
        {
          command: `npm run dev -- -p ${LIVE_PORT}`,
          url: LIVE_BASE_URL,
          timeout: 120_000,
          reuseExistingServer: !process.env.CI,
          env: {
            NEXT_PUBLIC_API_MODE: "live",
            NEXT_TELEMETRY_DISABLED: "1",
            NEXT_DIST_DIR: ".next-live",
          },
        },
      ],
});
