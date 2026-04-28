import { expect, test } from "@playwright/test";
import { login, uniqueEventName, waitForApp } from "./helpers";

test("create-event flow lands on the new event detail page", async ({ page }) => {
  await login(page);
  await waitForApp(page);

  await page.goto("/events/new");
  await expect(
    page.getByRole("heading", { name: /new event/i }),
  ).toBeVisible();

  const name = uniqueEventName("create");
  await page
    .getByPlaceholder(/trivia at the dog/i)
    .fill(name);
  await page
    .getByPlaceholder(/a few short lines/i)
    .fill("smoke-test description");

  await page.getByRole("button", { name: /post event/i }).click();

  // Mock createEvent returns an id and the form router-pushes to /events/{id}.
  await expect(page).toHaveURL(/\/events\/\d+$/, { timeout: 15_000 });
  await expect(
    page.getByRole("heading", { name, exact: false }),
  ).toBeVisible({ timeout: 10_000 });
});
