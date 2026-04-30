import { afterAll, afterEach, beforeAll } from "vitest";
import { server } from "@/mocks/server";

// Lifecycle for the MSW node server. Render tests rely on this; pure-node tests
// that don't make network requests are unaffected (handlers list is consulted
// only when the test's code paths actually fetch).
// "bypass" lets unhandled requests fall through to whatever global.fetch is —
// this keeps existing node-environment tests that stub fetch manually working.
beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
