import { NextResponse, type NextRequest } from "next/server";

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export function middleware(req: NextRequest) {
  const mode = process.env.NEXT_PUBLIC_API_MODE ?? "mock";
  if (mode === "mock") return NextResponse.next();
  // Cross-domain auth: SESSION cookie lives on api.event.tylercash.dev,
  // not visible here. Auth protection is handled client-side via 401 → OAuth redirect.
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/|favicon.ico|mockServiceWorker.js).*)"],
};
