import { expect, type Page } from "@playwright/test";

/**
 * Log in as the mock user. The login page's "continue with Discord" button,
 * in mock mode, just clears a localStorage flag and pushes to "/". After this
 * resolves, the user is authenticated for subsequent /api/* calls handled by
 * MSW.
 */
export async function login(page: Page): Promise<void> {
  await page.goto("/login");
  // The mock auth defaults to "logged in" — a fresh browser context has no
  // `mock-auth-logged-out` flag, so the LoginHero `useCurrentUser` effect can
  // auto-redirect to "/" before Playwright's click lands. Race the button
  // click against an already-completed redirect so the helper is robust.
  const button = page.getByRole("button", { name: /continue with discord/i });
  await Promise.race([
    page.waitForURL(/\/$/, { timeout: 15_000 }),
    button.click({ timeout: 15_000 }).catch(() => {
      // The button can detach mid-click when the auto-redirect wins. Falling
      // through is fine — `toHaveURL` below will still verify we got to "/".
    }),
  ]);
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
