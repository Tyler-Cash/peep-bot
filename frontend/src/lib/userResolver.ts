import { Redis } from "@upstash/redis";

const BACKEND_BASE =
  process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080/api";

const CACHE_PREFIX = "session:discordId:";
const CACHE_TTL_SECONDS = 60 * 60; // 1 hour

let redis: Redis | undefined;
function getRedis(): Redis {
  if (!redis) redis = Redis.fromEnv();
  return redis;
}

export type ResolvedUser = { discordId: string } | { status: 401 | 502 };

export async function resolveDiscordIdFromSession(
  sessionKey: string,
): Promise<ResolvedUser> {
  try {
    const cached = await getRedis().get<string>(`${CACHE_PREFIX}${sessionKey}`);
    if (cached) return { discordId: cached };
  } catch {
    // Redis miss/error: fall through to a backend round-trip.
  }

  let res: Response;
  try {
    res = await fetch(`${BACKEND_BASE}/auth/is-logged-in`, {
      headers: { cookie: `SESSION=${sessionKey}` },
      cache: "no-store",
    });
  } catch (e) {
    console.warn("[userResolver] backend fetch threw:", (e as Error).message);
    return { status: 502 };
  }

  if (res.status === 401) return { status: 401 };
  if (!res.ok) {
    console.warn("[userResolver] backend returned", res.status, "for /auth/is-logged-in");
    return { status: 502 };
  }

  const body = (await res.json().catch(() => null)) as
    | { discordId?: string }
    | null;
  if (!body?.discordId) return { status: 401 };

  try {
    await getRedis().set(
      `${CACHE_PREFIX}${sessionKey}`,
      body.discordId,
      { ex: CACHE_TTL_SECONDS },
    );
  } catch {
    // Best-effort cache; resolution still works without it.
  }

  return { discordId: body.discordId };
}
