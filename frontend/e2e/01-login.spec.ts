import { expect, test } from "@playwright/test";
import { waitForApp } from "./helpers";

test.describe("login", () => {
  test("login page renders for an unauthenticated user", async ({ page }) => {
    // Pre-mark the mock auth as logged-out before the page loads.
    await page.addInitScript(() => {
      window.localStorage.setItem("mock-auth-logged-out", "true");
    });
    await page.goto("/login");
    await waitForApp(page);

    await expect(
      page.getByRole("heading", { name: /plans,/i }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: /continue with discord/i }),
    ).toBeVisible();
  });

  test("an already-logged-in user visiting /login is redirected to /", async ({
    page,
  }) => {
    await page.goto("/login");
    await waitForApp(page);
    // currentUser resolves and the LoginHero effect should push us to "/".
    await expect(page).toHaveURL(/\/$/, { timeout: 15_000 });
  });
});
