import { vi, describe, it, expect, beforeEach } from "vitest";

// Mock Upstash modules before any import that uses them.
// The Ratelimit constructor is called lazily (on first checkPlacesRateLimit call),
// so mocks must be in place before that happens.
vi.mock("@upstash/redis", () => ({
  Redis: { fromEnv: vi.fn(() => ({})) },
}));

const mockWindowLimit = vi.fn();
const mockHourlyLimit = vi.fn();

vi.mock("@upstash/ratelimit", () => {
  const MockRatelimit = vi
    .fn()
    .mockImplementationOnce(() => ({ limit: mockWindowLimit }))
    .mockImplementationOnce(() => ({ limit: mockHourlyLimit }));
  MockRatelimit.slidingWindow = vi.fn(() => ({}));
  MockRatelimit.fixedWindow = vi.fn(() => ({}));
  return { Ratelimit: MockRatelimit };
});

describe("checkPlacesRateLimit — fail open", () => {
  beforeEach(() => {
    vi.resetModules();
    mockWindowLimit.mockReset();
    mockHourlyLimit.mockReset();
    process.env.UPSTASH_REDIS_REST_URL = "https://example.upstash.io";
    process.env.UPSTASH_REDIS_REST_TOKEN = "test-token";
  });

  it("returns allowed:true when Upstash throws", async () => {
    mockWindowLimit.mockRejectedValue(new Error("connection refused"));
    const { checkPlacesRateLimit } = await import("@/lib/rateLimiter");
    const result = await checkPlacesRateLimit("test-session");
    expect(result).toEqual({ allowed: true });
  });
});
