import { describe, it, expect, vi, beforeEach } from "vitest";

const mockNext = vi.fn(() => ({ type: "next" }));

vi.mock("next/server", () => ({
  NextResponse: { next: mockNext },
}));

beforeEach(() => {
  vi.resetModules();
  mockNext.mockClear();
});

function makeReq() {
  return {
    nextUrl: { clone: () => ({ pathname: "/" }) },
    cookies: { has: () => false },
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any;
}

describe("middleware", () => {
  it("always passes through (cross-domain auth handled client-side)", async () => {
    process.env.NEXT_PUBLIC_API_MODE = "live";
    const { middleware } = await import("@/middleware");
    middleware(makeReq());
    expect(mockNext).toHaveBeenCalledOnce();
  });

  it("passes through in mock mode", async () => {
    process.env.NEXT_PUBLIC_API_MODE = "mock";
    const { middleware } = await import("@/middleware");
    middleware(makeReq());
    expect(mockNext).toHaveBeenCalledOnce();
  });
});
