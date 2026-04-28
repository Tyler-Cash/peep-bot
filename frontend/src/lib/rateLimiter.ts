import { Ratelimit } from "@upstash/ratelimit";
import { Redis } from "@upstash/redis";

let windowLimiter: Ratelimit | undefined;
let hourlyLimiter: Ratelimit | undefined;

function getWindowLimiter(): Ratelimit {
  if (!windowLimiter) {
    windowLimiter = new Ratelimit({
      redis: Redis.fromEnv(),
      limiter: Ratelimit.slidingWindow(1, "300 ms"),
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
  | { allowed: false; retryAfter: number };

export async function checkPlacesRateLimit(
  sessionKey: string,
): Promise<RateLimitResult> {
  try {
    const windowResult = await getWindowLimiter().limit(sessionKey);
    if (!windowResult.success) {
      return {
        allowed: false,
        retryAfter: Math.max(
          1,
          Math.ceil((windowResult.reset - Date.now()) / 1000),
        ),
      };
    }
    const hourlyResult = await getHourlyLimiter().limit(sessionKey);
    if (!hourlyResult.success) {
      return {
        allowed: false,
        retryAfter: Math.max(
          1,
          Math.ceil((hourlyResult.reset - Date.now()) / 1000),
        ),
      };
    }
    return { allowed: true };
  } catch {
    return { allowed: true };
  }
}
