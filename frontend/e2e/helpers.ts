import { expect, type Page } from "@playwright/test";

/**
 * Log in as the mock user. The login page's "continue with Discord" button,
 * in mock mode, just clears a localStorage flag and pushes to "/". After this
 * resolves, the user is authenticated for subsequent /api/* calls handled by
 * MSW.
 */
export async function login(page: Page): Promise<void> {
  await page.goto("/login");
  // Wait for MSW to have started — the LoginHero polls useCurrentUser, and once
  // the worker is up we either see the hero or get auto-redirected to "/".
  await page.getByRole("button", { name: /continue with discord/i }).click();
  await expect(page).toHaveURL(/\/$/, { timeout: 15_000 });
}

/** Generate a unique event name so specs don't collide on the in-memory store. */
export function uniqueEventName(prefix = "e2e"): string {
  return `${prefix} ${Date.now()}-${Math.floor(Math.random() * 10_000)}`;
}

/**
 * Wait for the Next.js / SWR / MSW boot dance to settle. The Providers
 * component renders an empty paper shell until MSW is ready, so we wait for
 * any actual UI to appear.
 */
export async function waitForApp(page: Page): Promise<void> {
  await expect(page.locator("nav, main, h1, [role='button']").first()).toBeVisible({
    timeout: 15_000,
  });
}
