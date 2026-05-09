import type { FullConfig } from "@playwright/test";

// `next dev` compiles each route lazily on first visit. On a cold GH Actions
// runner that compile can take 30–60s, which blows past Playwright's 30s
// per-test budget for whichever spec hits the route first. Warming the routes
// once here makes every test see an already-compiled server.
async function warm(baseURL: string, routes: string[]): Promise<void> {
  for (const route of routes) {
    const url = `${baseURL}${route}`;
    try {
      const res = await fetch(url, { redirect: "manual" });
      // Drain so dev server finishes streaming/compiling.
      await res.arrayBuffer().catch(() => undefined);
    } catch {
      // The dev server is up (Playwright waited on the webServer URL), so a
      // failure here means an actual route problem — let the real test
      // surface it instead of failing setup.
    }
  }
}

export default async function globalSetup(config: FullConfig): Promise<void> {
  const projects = config.projects ?? [];
  const seen = new Set<string>();
  // Every page the smoke suite navigates to. Dynamic segments compile once
  // for the whole [id] route, so any concrete id will do.
  const routes = [
    "/login",
    "/",
    "/events/new",
    "/events/1",
    "/events/1/edit",
    "/admin",
    "/guild/mockguild-1/settings",
  ];
  await Promise.all(
    projects.flatMap((p) => {
      const baseURL = p.use?.baseURL;
      if (!baseURL || seen.has(baseURL)) return [];
      seen.add(baseURL);
      return [warm(baseURL, routes)];
    }),
  );
}
