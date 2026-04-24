import { describe, it, expect, vi, beforeEach } from "vitest";

const mockNext = vi.fn(() => ({ type: "next" }));

vi.mock("next/server", () => ({
  NextResponse: { next: mockNext },
}));

beforeEach(() => {
  vi.resetModules();
  mockNext.mockClear();
});

describe("middleware", () => {
  it("passes through in live mode", async () => {
    process.env.NEXT_PUBLIC_API_MODE = "live";
    const { middleware } = await import("@/middleware");
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    middleware({} as any);
    expect(mockNext).toHaveBeenCalledOnce();
  });

  it("passes through in mock mode", async () => {
    process.env.NEXT_PUBLIC_API_MODE = "mock";
    const { middleware } = await import("@/middleware");
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    middleware({} as any);
    expect(mockNext).toHaveBeenCalledOnce();
  });
});
