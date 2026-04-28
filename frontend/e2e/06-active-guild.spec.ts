import { expect, test } from "@playwright/test";
import { login, waitForApp } from "./helpers";

test("active guild localStorage state survives navigation", async ({ page }) => {
  await login(page);
  await waitForApp(page);

  // Pre-seed the active guild key the way GuildSwitcher would.
  await page.evaluate(() => {
    window.localStorage.setItem("peepbot.activeGuild", "test-guild-id");
  });

  // Navigate around — feed → new → back home.
  await page.goto("/");
  await waitForApp(page);
  await page.goto("/events/new");
  await waitForApp(page);
  await page.goto("/");
  await waitForApp(page);

  const stored = await page.evaluate(() =>
    window.localStorage.getItem("peepbot.activeGuild"),
  );
  expect(stored).toBe("test-guild-id");
});
