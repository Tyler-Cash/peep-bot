import { NextResponse, type NextRequest } from "next/server";

// Cross-domain auth: SESSION cookie lives on api.event.tylercash.dev,
// not visible here. Auth protection is handled client-side via 401 → OAuth redirect.
// eslint-disable-next-line @typescript-eslint/no-unused-vars
export function middleware(_req: NextRequest) {
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/|favicon.ico|mockServiceWorker.js).*)"],
};
