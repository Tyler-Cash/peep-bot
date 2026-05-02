import { expect, test } from "@playwright/test";

/**
 * Runs against the "live" dev server (NEXT_PUBLIC_API_MODE=live), so /api/*
 * calls hit the network instead of being short-circuited by MSW. We stub the
 * two endpoints the popup flow touches via Playwright route interception.
 */
test("discord login opens a popup and signs the parent in", async ({
  context,
  page,
}) => {
  const oauthRequests: string[] = [];

  await context.route("**/api/oauth2/authorization/discord", async (route) => {
    oauthRequests.push(route.request().url());
    const origin = new URL(page.url()).origin;
    await route.fulfill({
      status: 302,
      headers: { location: `${origin}/` },
    });
  });

  await context.route("**/api/auth/is-logged-in", async (route) => {
    if (oauthRequests.length === 0) {
      await route.fulfill({ status: 401, body: "" });
    } else {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          id: "1",
          username: "tester",
          admin: false,
          guilds: [],
        }),
      });
    }
  });

  await context.route("**/api/csrf", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ token: "test-csrf" }),
    });
  });

  await page.goto("/login");
  await expect(
    page.getByRole("button", { name: /continue with discord/i }),
  ).toBeVisible();

  const [popup] = await Promise.all([
    page.waitForEvent("popup"),
    page.getByRole("button", { name: /continue with discord/i }).click(),
  ]);

  await popup.waitForEvent("close", { timeout: 10_000 });
  await expect(page).toHaveURL(/\/$/, { timeout: 10_000 });
  expect(oauthRequests).toHaveLength(1);
  expect(oauthRequests[0]).toContain("/api/oauth2/authorization/discord");
});
