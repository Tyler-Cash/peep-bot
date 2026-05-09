import { expect, test } from "@playwright/test";
import { gotoApp, login, waitForApp } from "./helpers";

test("clicking a guild in the switcher activates it", async ({ page }) => {
  await login(page);
  await waitForApp(page);

  // The mock backend returns two guilds; the first is the default active.
  const switcher = page.getByRole("button", { name: /porch pigeons/i }).first();
  await expect(switcher).toBeVisible();

  await switcher.click();

  const target = page.getByRole("button", { name: /moonlight bowls/i });
  await target.click();

  // Switcher button now shows the new active guild.
  await expect(
    page.getByRole("button", { name: /moonlight bowls/i }).first(),
  ).toBeVisible();

  // localStorage reflects the switch.
  const stored = await page.evaluate(() =>
    window.localStorage.getItem("peepbot.activeGuild"),
  );
  expect(stored).toBe("mockguild-2");

  // Survives a navigation.
  await gotoApp(page, "/");
  await expect(
    page.getByRole("button", { name: /moonlight bowls/i }).first(),
  ).toBeVisible();
});
