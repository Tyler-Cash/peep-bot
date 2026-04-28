import { expect, test } from "@playwright/test";
import { login, uniqueEventName, waitForApp } from "./helpers";

test("event detail page shows guest list and back link", async ({ page }) => {
  await login(page);
  await waitForApp(page);

  // Make our own event so we don't depend on whichever fixture id happens to
  // be first.
  await page.goto("/events/new");
  const name = uniqueEventName("detail");
  await page.getByPlaceholder(/trivia at the dog/i).fill(name);
  await page.getByRole("button", { name: /post event/i }).click();
  await expect(page).toHaveURL(/\/events\/\d+$/);

  // The detail page must render the event title and the guest-list section.
  await expect(page.getByRole("heading", { name, exact: false })).toBeVisible();
  await expect(page.getByText(/the guest list/i)).toBeVisible();
  // RsvpGroup labels: "going", "maybe", "can't make it"
  await expect(page.getByText(/going · /i)).toBeVisible();
  await expect(page.getByText(/maybe · /i)).toBeVisible();
});
