import { expect, test } from "@playwright/test";
import { login, waitForApp } from "./helpers";

test("logout clears the session and lands on /login", async ({ page }) => {
  await login(page);
  await waitForApp(page);

  // Mock logout endpoint sets the localStorage flag and the hook redirects to
  // /login. Use the desktop bar's "log out" button.
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.getByRole("button", { name: /log out/i }).click();

  await expect(page).toHaveURL(/\/login$/, { timeout: 15_000 });
  await expect(
    page.getByRole("button", { name: /continue with discord/i }),
  ).toBeVisible();
});
