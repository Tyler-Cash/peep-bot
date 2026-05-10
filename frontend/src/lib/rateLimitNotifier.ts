type Handler = (retryAfterSeconds: number) => void;

const handlers = new Set<Handler>();

export function subscribeRateLimited(handler: Handler): () => void {
  handlers.add(handler);
  return () => handlers.delete(handler);
}

export function notifyRateLimited(retryAfterSeconds: number): void {
  handlers.forEach((h) => {
    try {
      h(retryAfterSeconds);
    } catch {
      // never let a subscriber break the API client
    }
  });
}
