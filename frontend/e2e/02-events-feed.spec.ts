import { expect, test } from "@playwright/test";
import { login, waitForApp } from "./helpers";

test("events feed renders for an authenticated user", async ({ page }) => {
  await login(page);
  await waitForApp(page);

  await expect(
    page.getByRole("heading", { name: /what's happening/i }),
  ).toBeVisible();

  // The feed CTA to create a new event should always be present.
  await expect(
    page.getByRole("link", { name: /new event/i }).first(),
  ).toBeVisible();
});
