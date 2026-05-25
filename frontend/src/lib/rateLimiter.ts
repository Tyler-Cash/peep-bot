import { Ratelimit } from "@upstash/ratelimit";
import { Redis } from "@upstash/redis";

let windowLimiter: Ratelimit | undefined;
let hourlyLimiter: Ratelimit | undefined;
let staticMapDailyLimiter: Ratelimit | undefined;

function getWindowLimiter(): Ratelimit {
  if (!windowLimiter) {
    windowLimiter = new Ratelimit({
      redis: Redis.fromEnv(),
      limiter: Ratelimit.slidingWindow(10, "1 s"),
      prefix: "places:window",
    });
  }
  return windowLimiter;
}

function getHourlyLimiter(): Ratelimit {
  if (!hourlyLimiter) {
    hourlyLimiter = new Ratelimit({
      redis: Redis.fromEnv(),
      limiter: Ratelimit.fixedWindow(50, "1 h"),
      prefix: "places:hourly",
    });
  }
  return hourlyLimiter;
}

export type RateLimitResult =
  | { allowed: true }
  | { allowed: false; retryAfter: number; retryAfterMs: number };

function blocked(reset: number): RateLimitResult {
  const retryAfterMs = Math.max(50, reset - Date.now());
  return {
    allowed: false,
    retryAfter: Math.max(1, Math.ceil(retryAfterMs / 1000)),
    retryAfterMs,
  };
}

function getStaticMapDailyLimiter(): Ratelimit {
  if (!staticMapDailyLimiter) {
    staticMapDailyLimiter = new Ratelimit({
      redis: Redis.fromEnv(),
      limiter: Ratelimit.fixedWindow(50, "1 d"),
      prefix: "staticmap:daily",
    });
  }
  return staticMapDailyLimiter;
}

export async function checkStaticMapRateLimit(
  sessionKey: string,
): Promise<RateLimitResult> {
  try {
    const result = await getStaticMapDailyLimiter().limit(sessionKey);
    if (!result.success) return blocked(result.reset);
    return { allowed: true };
  } catch {
    return { allowed: true };
  }
}

export async function checkPlacesRateLimit(
  sessionKey: string,
): Promise<RateLimitResult> {
  try {
    const [windowResult, hourlyResult] = await Promise.all([
      getWindowLimiter().limit(sessionKey),
      getHourlyLimiter().limit(sessionKey),
    ]);
    if (!windowResult.success) return blocked(windowResult.reset);
    if (!hourlyResult.success) return blocked(hourlyResult.reset);
    return { allowed: true };
  } catch {
    return { allowed: true };
  }
}
