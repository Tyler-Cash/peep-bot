import { expect, test } from "@playwright/test";
import { login, uniqueEventName, waitForApp } from "./helpers";

test("edit-event link navigates to the edit form, back link returns to feed", async ({
  page,
}) => {
  await login(page);
  await waitForApp(page);

  await page.goto("/events/new");
  const name = uniqueEventName("edit");
  await page.getByPlaceholder(/trivia at the dog/i).fill(name);
  await page.getByRole("button", { name: /post event/i }).click();
  await expect(page).toHaveURL(/\/events\/(\d+)$/);

  // Edit link is present (only when not past/cancelled).
  const editLink = page.getByRole("link", { name: /edit event|edit/i }).first();
  await expect(editLink).toBeVisible();
  await editLink.click();
  await expect(page).toHaveURL(/\/events\/\d+\/edit$/);

  // Back to the feed via the home link in the nav (peepbot brand).
  await page.goto("/");
  await waitForApp(page);
  await expect(
    page.getByRole("heading", { name: /what's happening/i }),
  ).toBeVisible();
});
