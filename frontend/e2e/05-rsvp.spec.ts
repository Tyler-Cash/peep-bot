import { expect, test } from "@playwright/test";
import { login, uniqueEventName, waitForApp } from "./helpers";

test("rsvp going then maybe updates the headline and guest list", async ({
  page,
}) => {
  await login(page);
  await waitForApp(page);

  // Create our own event so we know the initial RSVP state.
  await page.goto("/events/new");
  const name = uniqueEventName("rsvp");
  await page.getByPlaceholder(/trivia at the dog/i).fill(name);
  await page.getByRole("button", { name: /post event/i }).click();
  await expect(page).toHaveURL(/\/events\/\d+$/);

  // Mock createEvent auto-RSVPs the host as "going", so the headline should
  // already say "you're in".
  await expect(page.getByText(/you're in/i)).toBeVisible({ timeout: 10_000 });

  // Switch to "maybe" — clicking the maybe button should flip the headline.
  await page.getByRole("button", { name: /^maybe$/i }).click();
  await expect(page.getByText(/you said maybe/i)).toBeVisible({
    timeout: 10_000,
  });

  // And "can't" — declined.
  await page.getByRole("button", { name: /^can't$/i }).click();
  await expect(page.getByText(/you can't make it/i)).toBeVisible({
    timeout: 10_000,
  });
});
