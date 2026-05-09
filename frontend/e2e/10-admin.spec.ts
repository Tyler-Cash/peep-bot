import { expect, test, type Page } from "@playwright/test";
import { gotoApp, login, waitForApp } from "./helpers";

// The top-bar GuildSwitcher button has its title attribute set to the active guild
// name. Use that to find it without depending on which guild happens to be active.
function topSwitcherButton(page: Page) {
  return page.locator("nav button[title]:not([aria-label])").first();
}

// Navigate to /admin and wait until the admin chrome has settled. We use page.goto
// rather than clicking the tab to dodge the React-re-render race that otherwise
// makes Link clicks flaky right after auth resolves.
async function enterAdmin(page: Page) {
  await page.goto("/admin", { waitUntil: "domcontentloaded" });
  await expect(page).toHaveURL(/\/admin\/?$/);
  await expect(page.locator("nav[data-admin-mode]")).toBeVisible();
}

test.describe("admin panel access + chrome", () => {
  // Defence in depth: even though Playwright gives each test a fresh context,
  // make sure no mock-mode flags leak from a parallel/serial neighbour.
  test.beforeEach(async ({ page }) => {
    await page.goto("/login");
    await page.evaluate(() => {
      window.localStorage.removeItem("mock-non-admin");
      window.localStorage.removeItem("mock-auth-logged-out");
      window.localStorage.removeItem("peepbot.activeGuild");
    });
  });


  test("admin tab is visible to admins; /admin inverts the navbar", async ({
    page,
  }) => {
    await login(page);
    await waitForApp(page);

    // The admin tab is rendered for admin users.
    await expect(page.getByRole("link", { name: /^admin$/i })).toBeVisible();

    // No admin styling before we enter.
    await expect(page.locator("nav[data-admin-mode]")).toHaveCount(0);

    await enterAdmin(page);
  });

  test("non-admin users do not see the admin tab", async ({ page }) => {
    // MSW intercepts /api/* in-page (service worker), so Playwright's network-level
    // page.route can't override responses. Instead, the mock handler checks this
    // localStorage flag and returns the same user with admin: false.
    await page.evaluate(() => window.localStorage.setItem("mock-non-admin", "true"));

    await login(page);
    await waitForApp(page);

    await expect(page.getByRole("link", { name: /^admin$/i })).toHaveCount(0);
  });

  test("non-admin who hits /admin gets redirected and never sees admin chrome", async ({
    page,
  }) => {
    await page.evaluate(() => window.localStorage.setItem("mock-non-admin", "true"));

    await login(page);
    await waitForApp(page);

    await page.goto("/admin");
    // AdminGate redirects non-admins back to "/".
    await expect(page).toHaveURL(/\/$/);
    // And no admin chrome ever shows.
    await expect(page.locator("nav[data-admin-mode]")).toHaveCount(0);
  });

  test("admin switcher lists the admin-guilds superset", async ({ page }) => {
    await login(page);
    await waitForApp(page);
    await enterAdmin(page);

    await topSwitcherButton(page).click();
    await expect(page.getByText(/all servers \(admin\)/i)).toBeVisible();
    // "couch co-op" is in admin guilds but NOT in the user's member list — its presence
    // confirms we're rendering the admin superset.
    await expect(page.getByRole("button", { name: /couch co-op/i })).toBeVisible();
  });

  test("leaving admin with a non-member guild active falls back to the first member guild", async ({
    page,
  }) => {
    await login(page);
    await waitForApp(page);
    await enterAdmin(page);

    // Pick "couch co-op" — admin-only, not in the user's member list.
    await topSwitcherButton(page).click();
    await page.getByRole("button", { name: /couch co-op/i }).click();

    // localStorage holds the admin-only guild id.
    await expect
      .poll(() =>
        page.evaluate(() =>
          window.localStorage.getItem("peepbot.activeGuild"),
        ),
      )
      .toBe("mockguild-3");

    // Leave admin via direct navigation (the in-nav <Link> click occasionally races
    // re-renders triggered by SWR settling on entry to admin mode).
    await gotoApp(page, "/");
    await expect(page).toHaveURL(/^[^?#]*\/$/);
    await expect(
      page.getByRole("button", { name: /porch pigeons/i }).first(),
    ).toBeVisible();
  });

  test("clicking the gear icon opens that guild's settings", async ({
    page,
  }) => {
    await login(page);
    await waitForApp(page);
    // Wait for the switcher to actually render before we click — under SWR
    // refetches the dropdown can otherwise re-render mid-click and the
    // navigation gets dropped. Semantic waiter beats `networkidle`.
    await expect(topSwitcherButton(page)).toBeVisible();

    // The user only owns mockguild-1 → the gear icon only appears for porch pigeons.
    await topSwitcherButton(page).click();
    const gear = page.getByRole("button", { name: /settings for porch pigeons/i });
    await expect(gear).toBeVisible();

    const navPromise = page.waitForURL(/\/guild\/mockguild-1\/settings/, {
      timeout: 15_000,
    });
    await gear.click();
    await navPromise;
  });

  test("on /guild/{id}/settings, switching guilds via the switcher reroutes to that guild's settings", async ({
    page,
  }) => {
    await login(page);
    await waitForApp(page);

    // Land on porch pigeons settings directly. (Same click-race avoidance as enterAdmin.)
    await page.goto("/guild/mockguild-1/settings");
    await expect(page).toHaveURL(/\/guild\/mockguild-1\/settings/);

    // Open the switcher; wait for the dropdown to fully render before clicking a row.
    await topSwitcherButton(page).click();
    const target = page.getByRole("button", { name: /moonlight bowls/i });
    await expect(target).toBeVisible();
    await Promise.all([
      page.waitForURL(/\/guild\/mockguild-2\/settings/),
      target.click(),
    ]);
  });
});
